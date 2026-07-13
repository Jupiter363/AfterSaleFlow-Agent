# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

from typing import Any

from app.harness.evidence_context_assembler import EvidenceTurnWorkingSet
from app.schemas import (
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
        completeness_score = assessment.completeness_score
        assessment_confidence = assessment.assessment_confidence
        findings = list(assessment.findings)
        allowed_fact_ids = {
            str(item.get("fact_id") or "")
            for item in working_set.allowed_fact_targets
            if str(item.get("fact_id") or "")
        }
        fact_links = [
            link for link in assessment.fact_links if link.fact_id in allowed_fact_ids
        ]
        supported_fact_ids = [
            fact_id
            for fact_id in assessment.supported_fact_ids
            if fact_id in allowed_fact_ids
        ]
        if (
            len(fact_links) != len(assessment.fact_links)
            or len(supported_fact_ids) != len(assessment.supported_fact_ids)
        ):
            reasons.append("UNKNOWN_FACT_REFERENCE")
            instructions.append("请核对该证据应关联到接待卷宗中的哪一项既有待证事实。")
            limitations.append("模型返回了接待卷宗事实白名单之外的事实引用，已阻止写入证据矩阵。")

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
            reasons.append("LOW_AUTHENTICITY_SCORE")
        if completeness_score < 0.5:
            reasons.append("LOW_COMPLETENESS_SCORE")
        if assessment_confidence < 0.65:
            reasons.append("LOW_ASSESSMENT_CONFIDENCE")
        if any(flag.severity == "HIGH" for flag in assessment.risk_flags):
            reasons.append("HIGH_RISK_FLAG")

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
