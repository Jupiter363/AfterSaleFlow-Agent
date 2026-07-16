# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

import math
import re
from typing import Any

from app.harness.evidence_context_assembler import EvidenceTurnWorkingSet
from app.schemas import (
    EvidenceFactLink,
    EvidenceHumanReviewSignal,
    EvidenceItemAssessment,
    EvidenceRiskFlag,
)


VISUAL_DAMAGE_TERMS = (
    "划痕",
    "磨损",
    "破损",
    "变形",
    "裂纹",
    "色差",
    "污渍",
    "损坏",
)

class EvidenceAssessmentPolicy:
    """Enforce model capability boundaries and fail visual gaps to review."""

    # 所属模块：证据室 Agent > 确定性评估策略；函数角色：类/闭包内部方法。
    # 具体功能：`apply` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`dict.fromkeys`、`item.get`、`asset_manifest.get`。
    # 上下游：上游为 Java 按参与方权限筛选的证据、事实目标、私有会话；下游为 本文件的 `_normalize`。
    # 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
    def apply(
        self,
        assessments: list[EvidenceItemAssessment],
        working_set: EvidenceTurnWorkingSet,
        asset_manifest: dict[str, Any],
    ) -> list[EvidenceItemAssessment]:
        target_ids = list(
            dict.fromkeys(
                str(item)
                for item in working_set.current_event.get("attachment_refs", [])
            )
        )
        if not target_ids:
            return []
        allowed = {item.evidence_id: item for item in working_set.available_evidence}
        by_id = {item.evidence_id: item for item in assessments}
        manifest_by_id = {
            str(item.get("evidence_id")): item
            for item in asset_manifest.get("items", [])
            if isinstance(item, dict)
        }
        return [
            self._normalize(
                by_id.get(evidence_id),
                allowed[evidence_id],
                manifest_by_id.get(evidence_id, {}),
                working_set,
            )
            for evidence_id in target_ids
            if evidence_id in allowed
        ]

    # 所属模块：证据室 Agent > 确定性评估策略；函数角色：类/闭包内部方法。
    # 具体功能：`_normalize` 把本阶段状态转换为稳定的接口、提示词或页面表达；关键协作调用：`EvidenceItemAssessment.model_validate`、`reasons.append`、`instructions.append`。
    # 上下游：上游为 本文件的 `EvidenceAssessmentPolicy.apply`；下游为 本文件的 `_is_visual_evidence`、`_requires_fine_visual_review`、`_analysis_method`、`_missing_assessment`。
    # 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
    def _normalize(
        self,
        assessment: EvidenceItemAssessment | None,
        evidence: Any,
        asset: dict[str, Any],
        working_set: EvidenceTurnWorkingSet,
    ) -> EvidenceItemAssessment:
        if assessment is None:
            return _missing_assessment(evidence.evidence_id, asset)

        reasons = list(assessment.human_review.reason_codes)
        instructions = list(assessment.human_review.instructions)
        limitations = list(assessment.limitations)
        risk_flags = list(assessment.risk_flags)
        visual_status = str(asset.get("visual_input_status") or "NOT_REQUESTED")
        is_visual = _is_visual_evidence(evidence)
        # AssetLoader 是“模型实际收到哪些输入”的权威来源。模型字段只能
        # 描述观察结论，不能把已经注入的 IMAGE_PIXELS 自行降级成 TEXT_ONLY。
        inspected_modalities = list(
            dict.fromkeys(
                str(item)
                for item in asset.get("inspected_modalities", [])
                if str(item) in {"OCR_TEXT", "IMAGE_PIXELS", "FILE_METADATA"}
            )
        )
        visual_loaded = visual_status in {"LOADED", "LOADED_WITHOUT_HASH"}
        authenticity_score = assessment.authenticity_score
        relevance_score = assessment.relevance_score
        completeness_score = assessment.completeness_score
        assessment_confidence = assessment.assessment_confidence
        findings = list(assessment.findings)
        allowed_fact_ids = {
            str(item.get("fact_id") or "")
            for item in working_set.allowed_fact_targets
            if str(item.get("fact_id") or "")
        }
        candidate_fact_links = [
            link for link in assessment.fact_links if link.fact_id in allowed_fact_ids
        ]
        supplied_supported_fact_ids = list(assessment.supported_fact_ids)
        if (
            len(candidate_fact_links) != len(assessment.fact_links)
            or any(
                fact_id not in allowed_fact_ids
                for fact_id in supplied_supported_fact_ids
            )
        ):
            reasons.append("UNKNOWN_FACT_REFERENCE")
            instructions.append("请核对该证据应关联到接待卷宗中的哪一项既有待证事实。")
            limitations.append("模型返回了接待卷宗事实白名单之外的事实引用，已阻止写入证据矩阵。")

        if relevance_score >= 0.5 and not candidate_fact_links:
            recovered_link = _recover_fact_coordinate(
                evidence,
                working_set.allowed_fact_targets,
                relevance_score=relevance_score,
                assessment_confidence=assessment_confidence,
            )
            if recovered_link is not None:
                candidate_fact_links = [recovered_link]
                reasons.append("FACT_LINK_RECOVERED_FROM_SHARED_TEXT")
                instructions.append(
                    "请人工确认系统依据材料正文与双方案情矩阵文本重合恢复的事实坐标。"
                )
                limitations.append(
                    "模型遗漏正式 fact_id；系统仅按共享文本最高匹配恢复为 INCONCLUSIVE 关系，"
                    "不代表该事实已获支持。"
                )
            else:
                relevance_score = min(relevance_score, 0.49)
                reasons.append("FACT_LINK_UNRESOLVED")
                instructions.append("请人工把该材料映射到双方案情矩阵中的既有事实坐标。")
                limitations.append(
                    "模型未提供事实坐标，且材料文本不足以确定性恢复；本轮不写入事实证据矩阵。"
                )

        if is_visual and not visual_loaded:
            reasons.append(f"VISUAL_INPUT_{visual_status}")
            instructions.append("请审核员打开原始文件，核对清晰度、完整画面和关键细节。")
            limitation = str(asset.get("limitation") or "模型未读取到原始视觉内容。")
            limitations.append(limitation)
            inspected_modalities = [
                item for item in inspected_modalities if item != "IMAGE_PIXELS"
            ]
            findings = []
            authenticity_score = min(authenticity_score, 0.5)
            completeness_score = min(completeness_score, 0.5)
            assessment_confidence = min(assessment_confidence, 0.4)
        if visual_status == "LOADED_WITHOUT_HASH":
            reasons.append("SOURCE_HASH_MISSING")
            instructions.append("请人工核对原件来源和入库哈希。")
        if is_visual and visual_loaded and "IMAGE_PIXELS" not in inspected_modalities:
            reasons.append("VISUAL_NOT_INSPECTED")
            instructions.append("请人工确认模型是否遗漏图片中的关键区域。")
        if _requires_fine_visual_review(evidence, working_set):
            reasons.append("FINE_VISUAL_DAMAGE_REQUIRES_HUMAN")
            instructions.append("请核对疑似划痕、磨损或破损区域；不得仅凭图片判断形成时间和责任。")
            limitations.append("图片可提示疑似外观缺陷，但不能单独证明缺陷形成时间、原因或责任归属。")
        if authenticity_score < 0.5:
            reasons.append("LOW_AUTHENTICITY_SUSPECTED_FORGERY")
            instructions.append(
                "请人工复核证据原件、来源链和完整上下文；疑似造假标签不是最终造假认定。"
            )
            if not any(flag.code == "SUSPECTED_FORGERY" for flag in risk_flags):
                risk_flags.append(
                    EvidenceRiskFlag(
                        code="SUSPECTED_FORGERY",
                        severity="HIGH",
                        description="疑似造假",
                    )
                )
        if relevance_score < 0.5:
            reasons.append("LOW_RELEVANCE_SCORE")
            instructions.append(
                "请人工核对材料内容与提交方填写的证明目标是否存在直接、可解释的关联。"
            )
            if not any(flag.code == "LOW_RELEVANCE" for flag in risk_flags):
                risk_flags.append(
                    EvidenceRiskFlag(
                        code="LOW_RELEVANCE",
                        severity="MEDIUM",
                        description="关联度低",
                    )
                )
        if completeness_score < 0.5:
            reasons.append("LOW_COMPLETENESS_SCORE")
        if assessment_confidence < 0.65:
            reasons.append("LOW_ASSESSMENT_CONFIDENCE")
        if any(flag.severity == "HIGH" for flag in assessment.risk_flags):
            reasons.append("HIGH_RISK_FLAG")

        # 事实坐标与证据强弱是两件事。相关性达到业务阈值后，模型必须先说明
        # 材料对应哪一个既有 fact_id；真实性或完整性不足只会把关系降级为
        # INCONCLUSIVE，不能把事实坐标一并清空。低相关材料则不写入矩阵。
        fact_links = _finalize_fact_links(
            evidence_id=assessment.evidence_id,
            candidate_links=candidate_fact_links,
            submitted_fact_ids=[link.fact_id for link in assessment.fact_links],
            supplied_supported_fact_ids=supplied_supported_fact_ids,
            allowed_fact_ids=sorted(allowed_fact_ids),
            relevance_score=relevance_score,
            authenticity_score=authenticity_score,
            completeness_score=completeness_score,
            assessment_confidence=assessment_confidence,
        )
        supported_fact_ids = [
            link.fact_id for link in fact_links if link.relation == "SUPPORTS"
        ]

        required = assessment.human_review.required or bool(reasons)
        recommendation = (
            "NEEDS_HUMAN_REVIEW" if required else assessment.recommendation
        )
        if required and not risk_flags:
            risk_flags.append(
                EvidenceRiskFlag(
                    code="HUMAN_REVIEW_REQUIRED",
                    severity="MEDIUM",
                    description="该证据存在模型能力边界或核验缺口，需要人工复核。",
                )
            )
        analysis_method = _analysis_method(inspected_modalities)
        return EvidenceItemAssessment.model_validate(
            {
                **assessment.model_dump(mode="json"),
                "analysis_method": analysis_method,
                "inspected_modalities": inspected_modalities[:10],
                "fact_links": [item.model_dump(mode="json") for item in fact_links[:50]],
                "supported_fact_ids": supported_fact_ids[:50],
                "authenticity_score": authenticity_score,
                "relevance_score": relevance_score,
                "completeness_score": completeness_score,
                "assessment_confidence": assessment_confidence,
                "findings": [item.model_dump(mode="json") for item in findings[:30]],
                "limitations": _dedupe(limitations, limit=30),
                "risk_flags": [item.model_dump(mode="json") for item in risk_flags[:30]],
                "recommendation": recommendation,
                "asset_audit": _asset_audit(asset),
                "human_review": EvidenceHumanReviewSignal(
                    required=required,
                    reason_codes=_dedupe(reasons, limit=20),
                    instructions=_dedupe(instructions, limit=20),
                ).model_dump(mode="json"),
            }
        )


def _finalize_fact_links(
    *,
    evidence_id: str,
    candidate_links: list[EvidenceFactLink],
    submitted_fact_ids: list[str],
    supplied_supported_fact_ids: list[str],
    allowed_fact_ids: list[str],
    relevance_score: float,
    authenticity_score: float,
    completeness_score: float,
    assessment_confidence: float,
) -> list[EvidenceFactLink]:
    """Normalize accepted fact coordinates independently from evidence strength."""

    if relevance_score < 0.5:
        return []
    if not candidate_links:
        raise ValueError(
            "evidence assessment with relevance_score >= 0.5 must reference at "
            f"least one allowed fact_id: evidence_id={evidence_id}; "
            f"submitted_fact_ids={submitted_fact_ids}; "
            f"supported_fact_ids={supplied_supported_fact_ids}; "
            f"allowed_fact_ids={allowed_fact_ids}"
        )

    strength_insufficient = authenticity_score <= 0.5 or completeness_score <= 0.5
    result: list[EvidenceFactLink] = []
    seen_fact_ids: set[str] = set()
    for link in candidate_links:
        if link.fact_id in seen_fact_ids:
            continue
        seen_fact_ids.add(link.fact_id)
        relation = "INCONCLUSIVE" if strength_insufficient else link.relation
        reason = link.reason
        if strength_insufficient and link.relation != "INCONCLUSIVE":
            reason = (
                "材料与该事实存在关联，但真实性或完整性不足，"
                "当前仅登记为待核验关系。"
            )
        result.append(
            EvidenceFactLink(
                fact_id=link.fact_id,
                relation=relation,
                reason=reason,
                confidence=min(
                    float(link.confidence),
                    float(relevance_score),
                    float(assessment_confidence),
                ),
            )
        )
    return result[:50]


def _recover_fact_coordinate(
    evidence: Any,
    allowed_fact_targets: tuple[dict[str, str], ...],
    *,
    relevance_score: float,
    assessment_confidence: float,
) -> EvidenceFactLink | None:
    """Recover one conservative coordinate when the model omits every fact_id.

    This does not infer support or opposition. It only selects the strongest shared-text
    coordinate and records an INCONCLUSIVE link that is forced to human review upstream.
    """

    evidence_text = " ".join(
        value
        for value in (
            str(getattr(evidence, "claimed_fact", "") or ""),
            str(getattr(evidence, "content", "") or ""),
            str(getattr(evidence, "original_filename", "") or ""),
        )
        if value
    )
    evidence_grams = _text_bigrams(evidence_text)
    if not evidence_grams:
        return None

    candidates: list[tuple[float, str]] = []
    for target in allowed_fact_targets:
        fact_id = str(target.get("fact_id") or "")
        target_text = " ".join(
            value
            for value in (
                str(target.get("fact") or ""),
                str(target.get("category") or ""),
                str(target.get("match_text") or ""),
            )
            if value
        )
        target_grams = _text_bigrams(target_text)
        if not fact_id or not target_grams:
            continue
        overlap = len(evidence_grams & target_grams)
        score = overlap / math.sqrt(len(target_grams) * len(evidence_grams))
        candidates.append((score, fact_id))

    if not candidates:
        return None
    best_score, best_fact_id = max(candidates, key=lambda item: (item[0], item[1]))
    if best_score < 0.08:
        return None
    return EvidenceFactLink(
        fact_id=best_fact_id,
        relation="INCONCLUSIVE",
        reason=(
            "材料正文与该待证事实的共享文本重合度最高；因模型遗漏正式事实坐标，"
            "当前仅登记为待人工确认的事实关联。"
        ),
        confidence=min(
            0.6,
            float(relevance_score),
            float(assessment_confidence),
            max(0.5, best_score),
        ),
    )


def _text_bigrams(value: str) -> set[str]:
    normalized = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", value.casefold())
    if len(normalized) < 2:
        return set()
    return {normalized[index : index + 2] for index in range(len(normalized) - 1)}


# 所属模块：证据室 Agent > 确定性评估策略；函数角色：模块私有业务函数。
# 具体功能：`_missing_assessment` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`EvidenceItemAssessment`、`asset.get`、`EvidenceHumanReviewSignal`。
# 上下游：上游为 本文件的 `EvidenceAssessmentPolicy._normalize`；下游为 本文件的 `_asset_audit`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _missing_assessment(
    evidence_id: str,
    asset: dict[str, Any],
) -> EvidenceItemAssessment:
    visual_status = str(asset.get("visual_input_status") or "NOT_ASSESSED")
    return EvidenceItemAssessment(
        evidence_id=evidence_id,
        analysis_method="TEXT_ONLY",
        inspected_modalities=[],
        fact_links=[],
        authenticity_score=0.5,
        relevance_score=0.0,
        completeness_score=0.0,
        assessment_confidence=0.0,
        source_basis=["模型未返回该证据的来源依据。"],
        supported_fact_ids=[],
        unsupported_claims=["当前无法判断该证据可以支持哪些争议事实。"],
        formation_time_assessment="证据形成时间尚未完成核验。",
        findings=[],
        limitations=["模型没有返回该证据的结构化核验结果。"],
        risk_flags=[
            EvidenceRiskFlag(
                code="ASSESSMENT_MISSING",
                severity="HIGH",
                description="证据缺少可审计的模型核验结果。",
            )
        ],
        recommendation="NEEDS_HUMAN_REVIEW",
        human_review=EvidenceHumanReviewSignal(
            required=True,
            reason_codes=[f"ASSESSMENT_MISSING_{visual_status}"],
            instructions=["请人工打开原件并完成真实性风险、相关性和完整性复核。"],
        ),
        asset_audit=_asset_audit(asset),
        summary="该证据未获得完整的模型核验结果，已转人工复核。",
    )


# 所属模块：证据室 Agent > 确定性评估策略；函数角色：模块私有业务函数。
# 具体功能：`_is_visual_evidence` 判断当前可见证据是否满足当前业务分支条件；关键协作调用：`lower`、`upper`、`content_type.startswith`。
# 上下游：上游为 本文件的 `EvidenceAssessmentPolicy._normalize`、`_requires_fine_visual_review`；下游为 协作调用 `lower`、`upper`、`content_type.startswith`、`filename.endswith`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _is_visual_evidence(evidence: Any) -> bool:
    content_type = str(getattr(evidence, "content_type", "") or "").lower()
    evidence_type = str(getattr(evidence, "evidence_type", "") or "").upper()
    filename = str(getattr(evidence, "original_filename", "") or "").lower()
    return (
        content_type.startswith("image/")
        or evidence_type in {"IMAGE", "CHAT_SCREENSHOT", "VIDEO"}
        or filename.endswith((".png", ".jpg", ".jpeg", ".webp", ".gif"))
    )


# 所属模块：证据室 Agent > 确定性评估策略；函数角色：模块私有业务函数。
# 具体功能：`_requires_fine_visual_review` 围绕人工复核信息计算该函数独立负责的业务派生值；关键协作调用：`join`。
# 上下游：上游为 本文件的 `EvidenceAssessmentPolicy._normalize`；下游为 本文件的 `_is_visual_evidence`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _requires_fine_visual_review(
    evidence: Any,
    working_set: EvidenceTurnWorkingSet,
) -> bool:
    if not _is_visual_evidence(evidence):
        return False
    material = " ".join(
        (
            str(getattr(evidence, "content", "") or ""),
            str(working_set.case_intake_dossier),
        )
    )
    return any(term in material for term in VISUAL_DAMAGE_TERMS)


# 所属模块：证据室 Agent > 确定性评估策略；函数角色：模块私有业务函数。
# 具体功能：`_analysis_method` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 本文件的 `EvidenceAssessmentPolicy._normalize`；下游为 事实证据矩阵、人工核验任务、庭审交接。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _analysis_method(inspected_modalities: list[str]) -> str:
    has_visual = "IMAGE_PIXELS" in inspected_modalities
    has_text = "OCR_TEXT" in inspected_modalities
    if has_visual and has_text:
        return "HYBRID"
    if has_visual:
        return "MULTIMODAL"
    return "TEXT_ONLY"


# 所属模块：证据室 Agent > 确定性评估策略；函数角色：模块私有业务函数。
# 具体功能：`_dedupe` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`dict.fromkeys`。
# 上下游：上游为 本文件的 `EvidenceAssessmentPolicy._normalize`；下游为 协作调用 `dict.fromkeys`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _dedupe(values: list[str], *, limit: int | None = None) -> list[str]:
    result = list(dict.fromkeys(value for value in values if value))
    return result if limit is None else result[:limit]


# 所属模块：证据室 Agent > 确定性评估策略；函数角色：模块私有业务函数。
# 具体功能：`_asset_audit` 围绕证据附件计算该函数独立负责的业务派生值。
# 上下游：上游为 本文件的 `_missing_assessment`；下游为 事实证据矩阵、人工核验任务、庭审交接。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _asset_audit(asset: dict[str, Any]) -> dict[str, object]:
    allowed_fields = (
        "visual_input_status",
        "content_type",
        "declared_file_size",
        "actual_file_size",
        "inspected_modalities",
        "privacy_basis",
        "model_processing_authorized",
        "limitation",
    )
    return {key: asset[key] for key in allowed_fields if key in asset}
