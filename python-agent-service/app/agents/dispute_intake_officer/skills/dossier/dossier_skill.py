# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

import copy
import re
from difflib import SequenceMatcher
from dataclasses import dataclass
from typing import Any

from app.schemas import IntakeTurnRequest
from app.schemas.intake_case_matrix import UnilateralCaseMatrixDraftV1
from app.schemas.case_fact_matrix import CaseFactMatrixDeltaV2
from app.agents.dispute_intake_officer.case_fact_matrix import (
    finalize_case_fact_matrix,
)


ORDER_REFERENCE_RE = re.compile(r"\b(?:ORDER|ORD|订单)[-_]?[A-Za-z0-9]{3,40}\b", re.IGNORECASE)
AFTER_SALES_REFERENCE_RE = re.compile(
    r"\b(?:AS|AFTERSALE|售后)[-_]?[A-Za-z0-9]{3,40}\b",
    re.IGNORECASE,
)
LOGISTICS_REFERENCE_RE = re.compile(
    r"\b(?:SF|EMS|JD|JT|YTO|ZTO|STO|YD|YZ|DBL|HTKY|LOG|TRACK)[-_]?[A-Za-z0-9]{5,40}\b",
    re.IGNORECASE,
)

ORIGINAL_STATEMENT_SEPARATOR = "\n\n"
ORIGINAL_STATEMENT_MISSING = "外部系统未提供发起方原话"
ORIGINAL_STATEMENT_POLICY = "INITIATOR_INPUTS_V1"
SUBJECTIVE_RESPONDENT_SOURCE = "发起方单方陈述（主观）"
DIRECT_RESPONDENT_SOURCE = "被发起方接待室直接陈述"
SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE = (
    "仅表示从发起方单方陈述中提取态度的明确度，不代表事实真实性。"
)
RESPONDENT_ATTITUDE_CODES = {
    "NOT_RESPONDED",
    "AGREE",
    "PARTIALLY_AGREE",
    "DISAGREE",
    "ALTERNATIVE_PROPOSED",
    "NEED_MORE_INFO",
    "PLATFORM_UNKNOWN",
}

CASE_DETAIL_TOP_LEVEL_FIELDS = frozenset(
    {
        "schema_version",
        "case_story",
        "references",
        "party_positions",
        "dispute_focus",
        "requested_resolution",
        "claim_resolution",
        "respondent_attitude",
        "dispute_core_state",
        "risk_assessment",
        "missing_information",
        "intake_quality",
        "admission",
        "handoff_notes",
        "case_fact_matrix",
        "unilateral_case_matrix",
    }
)
CASE_DETAIL_MAX_DEPTH = 12
CASE_DETAIL_MAX_NODES = 5_000
CASE_DETAIL_MAX_TEXT_CHARACTERS = 200_000

FIELD_DISPLAY_LABELS = {
    "ORDER_REFERENCE": "订单号",
    "AFTER_SALES_REFERENCE": "售后单号",
    "LOGISTICS_REFERENCE": "物流单号",
    "order_reference_confirmation": "订单号核对",
    "after_sales_reference_confirmation": "售后单号核对",
    "logistics_reference_confirmation": "物流单号核对",
    "product_issue_details": "故障细节",
    "product_quality_details": "商品质量细节",
    "user_statement": "用户原始陈述",
    "merchant_statement": "商家原始陈述",
    "merchant_requested_outcome": "商家期望处理方案",
    "requested_outcome": "期望处理结果",
    "evidence_attachments": "证据材料",
    "buyer_evidence": "买家证据材料",
    "user_evidence": "用户证据材料",
    "merchant_evidence": "商家证据材料",
    "merchant_outbound_photos": "商家发货前照片",
    "merchant_outbound_records": "商家发货前记录",
    "merchant_quality_inspection": "商家质检记录",
    "buyer_photos": "买家照片",
    "user_photos": "用户照片",
    "unboxing_video": "开箱视频",
    "opening_video": "开箱视频",
    "delivery_record": "物流派送记录",
    "proof_of_delivery": "签收凭证",
}


@dataclass(frozen=True)
class DossierRenderResult:
    dossier_patch: dict[str, Any]
    scroll_snapshot: dict[str, Any]
    canvas_operations: list[dict[str, Any]]
    admission_recommendation: str
    missing_fields: list[str]
    confidence: float


class CaseDetailDossierSkill:
    """Render the right-side case-detail board for the intake room.

    The LLM may draft the board and score it, but this skill performs the
    deterministic merge and readiness gate. It prevents invented references
    from opening the next room.
    """

    schema_version = "intake_case_detail.v1"
    readiness_threshold = 85

    # 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：类/闭包内部方法。
    # 具体功能：`render` 把本阶段状态转换为稳定的接口、提示词或页面表达；关键协作调用：`missing.extend`、`DossierRenderResult`、`missing_info.get`。
    # 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_case_detail_fields_only`、`_default_case_detail`、`_deep_merge`、`_enforce_claim_resolution`。
    # 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
    def render(
        self,
        *,
        request: IntakeTurnRequest,
        room_utterance: str,
        llm_case_detail: dict[str, Any] | None,
        llm_dossier_patch: dict[str, Any] | None,
        llm_scroll_snapshot: dict[str, Any] | None,
        llm_canvas_operations: list[dict[str, Any]],
        llm_admission_recommendation: str,
        llm_missing_fields: list[str],
        llm_confidence: float,
        llm_case_matrix_delta: (
            CaseFactMatrixDeltaV2 | UnilateralCaseMatrixDraftV1 | None
        ) = None,
        llm_unilateral_case_matrix: UnilateralCaseMatrixDraftV1 | None = None,
    ) -> DossierRenderResult:
        if llm_case_matrix_delta is not None and llm_unilateral_case_matrix is not None:
            raise ValueError("provide only llm_case_matrix_delta")
        effective_matrix_delta = llm_case_matrix_delta or llm_unilateral_case_matrix
        if llm_case_detail is None and llm_scroll_snapshot:
            return self._legacy_passthrough(
                llm_dossier_patch=llm_dossier_patch,
                llm_scroll_snapshot=llm_scroll_snapshot,
                llm_canvas_operations=llm_canvas_operations,
                llm_admission_recommendation=llm_admission_recommendation,
                llm_missing_fields=llm_missing_fields,
                llm_confidence=llm_confidence,
            )

        previous = _case_detail_fields_only(request.previous_case_detail or {})
        bounded_llm_case_detail = _case_detail_fields_only(llm_case_detail or {})
        previous_remark_status = (
            _handoff_remark_status(previous) if isinstance(previous, dict) else ""
        )
        previous_waiting_for_remark = previous_remark_status == "WAITING_FOR_REMARK"
        detail = self._default_case_detail(request)
        detail = _deep_merge(detail, previous if _is_case_detail(previous) else {})
        detail = _deep_merge(detail, bounded_llm_case_detail)
        detail["schema_version"] = self.schema_version
        _enforce_claim_resolution(detail, request, previous)
        _enforce_party_position_voice(detail)
        _enforce_respondent_attitude_source(
            detail,
            request,
            previous,
            bounded_llm_case_detail,
        )
        _enforce_dispute_core_state(detail)
        _enforce_case_story_summary(
            detail,
            request,
            previous,
            bounded_llm_case_detail,
        )

        trusted_refs = self._trusted_references(request)
        detail["references"] = {
            "order_reference": trusted_refs.get("order_reference") or "",
            "after_sales_reference": trusted_refs.get("after_sales_reference") or "",
            "logistics_reference": trusted_refs.get("logistics_reference") or "",
        }

        missing_info = _ensure_dict(detail, "missing_information")
        actor_role = str(request.agent_context.actor_role or "").upper()
        for field_name in ("blocking_gaps", "nice_to_have_gaps", "next_questions"):
            values = [
                value
                for value in _list_values(missing_info.get(field_name))
                if not _is_evidence_material_request(value)
                and not _question_targets_resolved_intake_field(
                    value,
                    detail,
                    actor_role=actor_role,
                )
            ]
            missing_info[field_name] = values[:2] if field_name == "next_questions" else values
        utterance_questions = _follow_up_questions_from_utterance(room_utterance)
        if utterance_questions and not _is_evidence_material_request(room_utterance):
            missing_info["next_questions"] = [
                question
                for question in utterance_questions
                if not _question_targets_resolved_intake_field(
                    question,
                    detail,
                    actor_role=actor_role,
                )
            ][:2]
        llm_missing_from_detail = _list_values(missing_info.get("blocking_gaps"))
        missing = self._hard_missing_fields(trusted_refs)
        missing.extend(
            field
            for field in llm_missing_fields
            if field not in missing and not _is_evidence_material_request(field)
        )
        missing.extend(field for field in llm_missing_from_detail if field not in missing)
        score = _clamp_score(
            detail.get("intake_quality", {}).get("score")
            if isinstance(detail.get("intake_quality"), dict)
            else None
        )
        quality = _ensure_dict(detail, "intake_quality")
        quality["score"] = score
        quality["threshold"] = self.readiness_threshold
        quality["ready_for_next_step"] = (
            score >= self.readiness_threshold and not missing
        )
        if quality["ready_for_next_step"]:
            missing = []
            quality["improvement_reason"] = "信息完整度已达到提交阈值。"
        elif missing:
            quality["improvement_reason"] = "仍缺少可信的" + "、".join(
                _human_missing_fields(missing)
            )
        else:
            quality["improvement_reason"] = _humanize_internal_tokens(
                str(quality.get("improvement_reason") or "")
            )

        missing_info["blocking_gaps"] = _human_missing_fields(missing)
        if quality["ready_for_next_step"]:
            missing_info["next_questions"] = []
            if previous_remark_status == "READY_PENDING_REMARK_INVITE":
                next_remark_status = "WAITING_FOR_REMARK"
            elif previous_remark_status in {
                "WAITING_FOR_REMARK",
                "HAS_REMARKS",
                "NO_EXTRA_REMARKS",
            }:
                next_remark_status = previous_remark_status
            else:
                next_remark_status = "READY_PENDING_REMARK_INVITE"
            _ensure_handoff_notes(detail, remark_status=next_remark_status)
        else:
            _ensure_handoff_notes(detail, remark_status="NOT_READY")
            if not missing_info.get("next_questions"):
                missing_info["next_questions"] = [_question_for_missing(missing)]
        _normalize_next_verification_focus(detail)
        _record_handoff_remark_if_needed(
            detail,
            request,
            previous_waiting_for_remark=previous_waiting_for_remark,
        )

        admission = _ensure_dict(detail, "admission")
        if quality["ready_for_next_step"]:
            admission["recommendation"] = "ACCEPTED"
        elif llm_admission_recommendation == "NOT_ADMISSIBLE":
            admission["recommendation"] = "NOT_ADMISSIBLE"
        else:
            admission["recommendation"] = "NEED_MORE_INFO"
        admission["confidence"] = _clamp_confidence(
            admission.get("confidence", llm_confidence)
        )

        detail["case_fact_matrix"] = finalize_case_fact_matrix(
            request=request,
            case_detail=detail,
            delta=effective_matrix_delta,
        ).model_dump(mode="json")
        detail.pop("unilateral_case_matrix", None)

        operations = [
            {
                "type": "UPSERT_CASE_DETAIL",
                "target_key": "case_detail",
                "animation": "ink-write",
                "value": detail,
            },
            {
                "type": "SET_QUALITY_SCORE",
                "target_key": "intake_quality",
                "animation": "score-rise",
                "value": score,
            },
        ]
        return DossierRenderResult(
            dossier_patch={
                "case_detail": detail,
                "room_utterance_source": room_utterance,
            },
            scroll_snapshot=detail,
            canvas_operations=operations,
            admission_recommendation=str(admission["recommendation"]),
            missing_fields=missing,
            confidence=float(admission["confidence"]),
        )

    # 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：类/闭包内部方法。
    # 具体功能：`_legacy_passthrough` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`DossierRenderResult`。
    # 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_assert_bounded_case_detail_tree`。
    # 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
    def _legacy_passthrough(
        self,
        *,
        llm_dossier_patch: dict[str, Any] | None,
        llm_scroll_snapshot: dict[str, Any],
        llm_canvas_operations: list[dict[str, Any]],
        llm_admission_recommendation: str,
        llm_missing_fields: list[str],
        llm_confidence: float,
    ) -> DossierRenderResult:
        _assert_bounded_case_detail_tree(
            llm_scroll_snapshot,
            source="llm_scroll_snapshot",
        )
        if llm_dossier_patch is not None:
            _assert_bounded_case_detail_tree(
                llm_dossier_patch,
                source="llm_dossier_patch",
            )
        return DossierRenderResult(
            dossier_patch=llm_dossier_patch or {},
            scroll_snapshot=llm_scroll_snapshot,
            canvas_operations=llm_canvas_operations,
            admission_recommendation=llm_admission_recommendation,
            missing_fields=llm_missing_fields,
            confidence=llm_confidence,
        )

    # 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：类/闭包内部方法。
    # 具体功能：`_trusted_references` 围绕业务引用号计算该函数独立负责的业务派生值；关键协作调用：`join`、`previous.get`、`previous_refs.get`；返回/更新字段：`order_reference`、`after_sales_reference`、`logistics_reference`。
    # 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_first_match`。
    # 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
    def _trusted_references(self, request: IntakeTurnRequest) -> dict[str, str]:
        current_text = (
            request.current_user_message.text
            if request.current_user_message is not None
            else ""
        )
        transcript_text = "\n".join(
            message.text for message in request.initiator_statement_transcript
        )
        previous = request.previous_case_detail or {}
        previous_refs = previous.get("references") if isinstance(previous, dict) else {}
        if not isinstance(previous_refs, dict):
            previous_refs = {}
        source_text = transcript_text or current_text
        initial = request.initial_case_facts
        return {
            "order_reference": (
                (initial.order_reference if initial is not None else None)
                or str(previous_refs.get("order_reference") or "")
                or _first_match(ORDER_REFERENCE_RE, source_text)
            ),
            "after_sales_reference": (
                (initial.after_sales_reference if initial is not None else None)
                or str(previous_refs.get("after_sales_reference") or "")
                or _first_match(AFTER_SALES_REFERENCE_RE, source_text)
            ),
            "logistics_reference": (
                (initial.logistics_reference if initial is not None else None)
                or str(previous_refs.get("logistics_reference") or "")
                or _first_match(LOGISTICS_REFERENCE_RE, source_text)
            ),
        }

    # 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：类/闭包内部方法。
    # 具体功能：`_hard_missing_fields` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`trusted_refs.get`、`missing.append`。
    # 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `trusted_refs.get`、`missing.append`。
    # 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
    @staticmethod
    def _hard_missing_fields(trusted_refs: dict[str, str]) -> list[str]:
        missing: list[str] = []
        if not trusted_refs.get("order_reference"):
            missing.append("ORDER_REFERENCE")
        if not trusted_refs.get("logistics_reference"):
            missing.append("LOGISTICS_REFERENCE")
        return missing

    # 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：类/闭包内部方法。
    # 具体功能：`_default_case_detail` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`schema_version`、`case_story`、`references`、`party_positions`。
    # 上下游：上游为 本文件的 `CaseDetailDossierSkill.render`；下游为 本文件的 `_turn_source_text`、`_party_role_or_default`、`_default_claim_resolution`、`_default_respondent_attitude`。
    # 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
    def _default_case_detail(self, request: IntakeTurnRequest) -> dict[str, Any]:
        source_text = _turn_source_text(request)
        initial = request.initial_case_facts
        initiator_role = _party_role_or_default(
            getattr(initial, "initiator_role", None)
            or (
                request.current_user_message.role
                if request.current_user_message is not None
                else None
            )
        )
        return {
            "schema_version": self.schema_version,
            "case_story": {
                "title": "待梳理履约争议",
                "one_sentence_summary": source_text,
            },
            "references": {
                "order_reference": "",
                "after_sales_reference": "",
                "logistics_reference": "",
            },
            "party_positions": {
                "user_claim": (
                    source_text
                    if initiator_role == "USER"
                    else ""
                ),
                "merchant_claim": (
                    source_text
                    if initiator_role == "MERCHANT"
                    else ""
                ),
                "raw_statement": (
                    request.current_user_message.text
                    if request.current_user_message is not None
                    else ""
                ),
                "platform_observation": "",
            },
            "dispute_focus": {
                "core_issue": "UNKNOWN",
                "key_conflicts": [],
                "facts_to_verify": [],
            },
            "requested_resolution": {
                "requested_outcome": (
                    getattr(initial, "requested_outcome_hint", None) or "UNKNOWN"
                ),
                "expected_resolution_text": "",
            },
            "claim_resolution": _default_claim_resolution(
                initial,
                source_text,
            ),
            "respondent_attitude": _default_respondent_attitude(
                initial
            ),
            "dispute_core_state": _default_dispute_core_state(
                initial,
                source_text,
            ),
            "risk_assessment": {
                "case_grade": "LOW",
                "risk_signals": [],
                "reasoning": "",
            },
            "missing_information": {
                "blocking_gaps": [],
                "nice_to_have_gaps": [],
                "next_questions": [],
            },
            "intake_quality": {
                "score": 0,
                "threshold": self.readiness_threshold,
                "ready_for_next_step": False,
                "score_breakdown": {
                    "references": 0,
                    "event_story": 0,
                    "party_positions": 0,
                    "requested_resolution": 0,
                    "risk_and_conflicts": 0,
                    "next_action_clarity": 0,
                },
                "improvement_reason": "等待接待官完成案件详情整理。",
            },
            "admission": {
                "recommendation": "NEED_MORE_INFO",
                "reasoning": "",
                "confidence": 0.0,
            },
            "handoff_notes": {
                "remark_status": "NOT_READY",
                "latest_remark": "",
                "remarks": [],
                "instruction": "案件详情达标后，接待官会询问是否有备注需要交接给证据书记官。",
            },
        }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_case_detail_fields_only` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`value.items`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill.render`；下游为 本文件的 `_assert_bounded_case_detail_tree`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _case_detail_fields_only(value: dict[str, Any]) -> dict[str, Any]:
    """Drop model/context echo fields before they can become persistent state."""

    if not isinstance(value, dict):
        return {}
    _assert_bounded_case_detail_tree(value, source="case_detail")
    return {
        key: item
        for key, item in value.items()
        if key in CASE_DETAIL_TOP_LEVEL_FIELDS
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_assert_bounded_case_detail_tree` 校验本阶段状态的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`stack.pop`、`ValueError`、`seen_containers.add`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._legacy_passthrough`、`_case_detail_fields_only`、`_deep_merge`；下游为 协作调用 `stack.pop`、`ValueError`、`seen_containers.add`、`current.items`。
# 系统意义：这是信任边界：只建档追问，不收正式证据、不定责、不承诺赔付。
def _assert_bounded_case_detail_tree(value: Any, *, source: str) -> None:
    """Bound a JSON-like dossier tree without allocating a serialized copy."""

    stack: list[tuple[Any, int]] = [(value, 0)]
    seen_containers: set[int] = set()
    node_count = 0
    text_characters = 0
    while stack:
        current, depth = stack.pop()
        node_count += 1
        if node_count > CASE_DETAIL_MAX_NODES:
            raise ValueError(
                f"{source} exceeds {CASE_DETAIL_MAX_NODES} values"
            )
        if depth > CASE_DETAIL_MAX_DEPTH:
            raise ValueError(
                f"{source} exceeds nesting depth {CASE_DETAIL_MAX_DEPTH}"
            )
        if isinstance(current, str):
            text_characters += len(current)
        elif isinstance(current, dict):
            identity = id(current)
            if identity in seen_containers:
                raise ValueError(f"{source} must be an acyclic JSON tree")
            seen_containers.add(identity)
            for key, item in current.items():
                text_characters += len(str(key))
                stack.append((item, depth + 1))
        elif isinstance(current, (list, tuple)):
            identity = id(current)
            if identity in seen_containers:
                raise ValueError(f"{source} must be an acyclic JSON tree")
            seen_containers.add(identity)
            stack.extend((item, depth + 1) for item in current)
        if text_characters > CASE_DETAIL_MAX_TEXT_CHARACTERS:
            raise ValueError(
                f"{source} exceeds {CASE_DETAIL_MAX_TEXT_CHARACTERS} text characters"
            )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_deep_merge` 把本阶段状态写入或合并到可追溯的阶段状态；关键协作调用：`copy.deepcopy`、`pending.pop`、`source.items`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill.render`；下游为 本文件的 `_assert_bounded_case_detail_tree`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _deep_merge(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
    """Merge bounded trees with one base copy instead of recursive deep copies."""

    _assert_bounded_case_detail_tree(base, source="merge base")
    _assert_bounded_case_detail_tree(patch, source="merge patch")
    merged = copy.deepcopy(base)
    pending: list[tuple[dict[str, Any], dict[str, Any]]] = [(merged, patch)]
    while pending:
        target, source = pending.pop()
        for key, value in source.items():
            if isinstance(value, dict) and isinstance(target.get(key), dict):
                pending.append((target[key], value))
            elif value is not None:
                if value == "" and target.get(key):
                    continue
                target[key] = copy.deepcopy(value)
    _assert_bounded_case_detail_tree(merged, source="merged case_detail")
    return merged


CLAIM_RESOLUTION_LABELS = {
    "REFUND": "退款",
    "RETURN_REFUND": "退货退款",
    "RESHIP": "补发",
    "REPLACE_OR_REPAIR": "换货或维修",
    "REPLACEMENT": "换货或维修",
    "REPAIR": "换货或维修",
    "COMPENSATION": "赔付",
    "CANCEL_ORDER": "取消订单",
    "VERIFY_OR_EXPLAIN_ONLY": "核验或解释",
    "OTHER": "其他处理",
    "UNKNOWN": "待确认处理",
}


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_enforce_case_story_summary` 校验本阶段状态的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`strip`、`source_texts.extend`、`previous.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_ensure_dict`、`_party_role_or_default`、`_remove_ungrounded_respondent_attitude`、`_uncovered_current_facts`。
# 系统意义：这是信任边界：只建档追问，不收正式证据、不定责、不承诺赔付。
def _enforce_case_story_summary(
    detail: dict[str, Any],
    request: IntakeTurnRequest,
    previous: dict[str, Any],
    llm_case_detail: dict[str, Any],
) -> None:
    """Keep one model-authored cumulative summary instead of concatenating fragments."""

    story = _ensure_dict(detail, "case_story")
    llm_story = llm_case_detail.get("case_story")
    candidate = (
        str(llm_story.get("one_sentence_summary") or "").strip()
        if isinstance(llm_story, dict)
        else ""
    )
    previous_story = previous.get("case_story") if isinstance(previous, dict) else None
    previous_summary = (
        str(previous_story.get("one_sentence_summary") or "").strip()
        if isinstance(previous_story, dict)
        else ""
    )
    current = request.current_user_message
    initial = request.initial_case_facts
    form_description = str(getattr(initial, "form_description", None) or "").strip()
    previous_matrix = previous.get("case_fact_matrix") if isinstance(previous, dict) else None
    previous_party_map = (
        previous_matrix.get("party_map")
        if isinstance(previous_matrix, dict)
        else {}
    )
    initiator_role = _party_role_or_default(
        getattr(initial, "initiator_role", None)
        or previous_party_map.get("initiator_role")
        or (current.role if current is not None else None)
    )
    actor_role = str(request.agent_context.actor_role or "").upper()
    current_is_direct_respondent = (
        current is not None and actor_role == _opposite_party(initiator_role)
    )
    source_texts = [form_description] if form_description else []
    source_texts.extend(
        message.text for message in request.initiator_statement_transcript
    )
    if current is not None and not _transcript_contains_current(request):
        source_texts.append(current.text)

    grounded_respondent = current_is_direct_respondent or any(
        _has_explicit_respondent_report(text, initiator_role)
        for text in source_texts
    )

    if not grounded_respondent:
        candidate = _remove_ungrounded_respondent_attitude(
            candidate,
            initiator_role,
        )
        previous_summary = _remove_ungrounded_respondent_attitude(
            previous_summary,
            initiator_role,
        )

    if current is None:
        summary = candidate or form_description or "案件表单信息待进一步说明。"
    else:
        # A normal turn must replace the previous summary with the model's
        # complete event summary.  The old fragment-appending guard produced
        # duplicated, broken prose whenever a paraphrase failed fuzzy matching.
        # Direct DossierSkill callers may omit a patch, in which case retaining
        # the last complete summary is safer than manufacturing prose here.
        summary = candidate or previous_summary
    story["one_sentence_summary"] = summary


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_remove_ungrounded_respondent_attitude` 围绕庭审轮次计算该函数独立负责的业务派生值；关键协作调用：`re.split`、`strip`、`join`。
# 上下游：上游为 本文件的 `_enforce_case_story_summary`；下游为 本文件的 `_has_explicit_respondent_report`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _remove_ungrounded_respondent_attitude(
    text: str,
    initiator_role: str,
) -> str:
    clauses = re.split(r"(?<=[，,。！？!?；;])", str(text or ""))
    kept = [
        clause
        for clause in clauses
        if clause.strip()
        and not _has_explicit_respondent_report(clause, initiator_role)
    ]
    return "".join(kept).strip()


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_is_material_case_supplement` 判断本阶段状态是否满足当前业务分支条件；关键协作调用：`re.sub`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `re.sub`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _is_material_case_supplement(text: str) -> bool:
    normalized = re.sub(r"[\s。！？!?，,；;]", "", str(text or ""))
    return normalized not in {
        "",
        "好的",
        "好",
        "知道了",
        "明白了",
        "谢谢",
        "没有补充",
        "无补充",
        "没有了",
        "无",
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_summary_covers_current_fact` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_uncovered_current_facts`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _summary_covers_current_fact(summary: str, current_fact: str) -> bool:
    return not _uncovered_current_facts(summary, current_fact)


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_uncovered_current_facts` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`fragment.strip`、`re.split`。
# 上下游：上游为 本文件的 `_enforce_case_story_summary`、`_summary_covers_current_fact`；下游为 本文件的 `_fact_comparison_text`、`_summary_fragment_is_covered`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _uncovered_current_facts(summary: str, current_fact: str) -> list[str]:
    normalized_summary = _fact_comparison_text(summary)
    normalized_current = _fact_comparison_text(current_fact)
    if not normalized_current:
        return []
    if normalized_current in normalized_summary:
        return []
    fragments = [
        fragment.strip()
        for fragment in re.split(r"[，,；;。！？!?]", current_fact)
    ]
    meaningful = [fragment for fragment in fragments if len(_fact_comparison_text(fragment)) >= 2]
    return [
        fragment
        for fragment in meaningful
        if not _summary_fragment_is_covered(summary, fragment)
    ]


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_summary_fragment_is_covered` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`re.split`、`ratio`、`SequenceMatcher`。
# 上下游：上游为 本文件的 `_uncovered_current_facts`；下游为 本文件的 `_fact_comparison_text`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _summary_fragment_is_covered(summary: str, fragment: str) -> bool:
    normalized_fragment = _fact_comparison_text(fragment)
    normalized_summary = _fact_comparison_text(summary)
    if normalized_fragment in normalized_summary:
        return True
    summary_fragments = [
        _fact_comparison_text(value)
        for value in re.split(r"[，,；;。！？!?]", summary)
        if len(_fact_comparison_text(value)) >= 2
    ]
    return any(
        SequenceMatcher(None, normalized_fragment, candidate).ratio() >= 0.58
        for candidate in summary_fragments
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_transcript_contains_current` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 本文件的 `_enforce_case_story_summary`；下游为 接待话术、卷宗补丁、受理建议、证据室。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _transcript_contains_current(request: IntakeTurnRequest) -> bool:
    current = request.current_user_message
    if current is None:
        return False
    return any(
        message.role == current.role and message.text == current.text
        for message in request.initiator_statement_transcript
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_fact_comparison_text` 围绕展示文本计算该函数独立负责的业务派生值；关键协作调用：`re.sub`、`normalized.startswith`。
# 上下游：上游为 本文件的 `_uncovered_current_facts`、`_summary_fragment_is_covered`；下游为 协作调用 `re.sub`、`normalized.startswith`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _fact_comparison_text(text: str) -> str:
    normalized = re.sub(r"[\s，,；;。！？!?：:]", "", str(text or ""))
    for prefix in ("用户称", "商家称", "用户", "商家", "本人", "我方", "我们", "我"):
        if normalized.startswith(prefix):
            normalized = normalized[len(prefix) :]
            break
    return normalized


_CLAIM_REMEDY_PATTERN = re.compile(
    r"退款|退货|补发|重发|换货|维修|修理|赔偿|赔付|补偿|取消订单|撤销订单|"
    r"核验|解释|道歉|refund|return|reship|replace|repair|compensat|cancel",
    re.IGNORECASE,
)
_CLAIM_INTENT_PATTERN = re.compile(
    r"(?:我|本人|我们|我方|用户|买家|商家|卖家)?"
    r"(?:希望|要求|申请|请求|想要|我要|我需要|诉求(?:是|为)?|期望|请)"
    r".{0,40}"
    r"(?:退款|退货|补发|重发|换货|维修|修理|赔偿|赔付|补偿|取消|撤销|核验|解释|道歉)",
    re.IGNORECASE,
)


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_turn_source_text` 围绕展示文本计算该函数独立负责的业务派生值。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._default_case_detail`；下游为 接待话术、卷宗补丁、受理建议、证据室。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _turn_source_text(request: IntakeTurnRequest) -> str:
    if request.current_user_message is not None:
        return request.current_user_message.text
    initial = request.initial_case_facts
    return str(getattr(initial, "form_description", None) or "")


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_is_explicit_claim_text` 判断当事人主张是否满足当前业务分支条件；关键协作调用：`strip`、`_CLAIM_INTENT_PATTERN.search`、`re.fullmatch`。
# 上下游：上游为 本文件的 `_enforce_claim_resolution`；下游为 协作调用 `strip`、`_CLAIM_INTENT_PATTERN.search`、`re.fullmatch`、`_CLAIM_REMEDY_PATTERN.search`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _is_explicit_claim_text(text: str) -> bool:
    normalized = str(text or "").strip()
    if not normalized or not _CLAIM_REMEDY_PATTERN.search(normalized):
        return False
    if _CLAIM_INTENT_PATTERN.search(normalized):
        return True
    return bool(
        re.fullmatch(
            r"(?:还是|就是|只要|仅要|除了)?\s*"
            r"(?:退款|退货退款|退货|补发|重发|换货|维修|赔偿|赔付|补偿|取消订单|核验|解释|道歉)"
            r"(?:\s*[0-9]+(?:\.[0-9]+)?\s*元?)?[。！!？?\s]*",
            normalized,
            re.IGNORECASE,
        )
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_requested_resolution_from_claim_text` 围绕当事人主张计算该函数独立负责的业务派生值；关键协作调用：`casefold`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `casefold`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _requested_resolution_from_claim_text(text: str) -> str | None:
    normalized = str(text or "").casefold()
    if "退货退款" in normalized:
        return "RETURN_REFUND"
    if any(term in normalized for term in ("补发", "重发", "reship")):
        return "RESHIP"
    if any(term in normalized for term in ("换货", "维修", "修理", "replace", "repair")):
        return "REPLACE_OR_REPAIR"
    if any(term in normalized for term in ("赔偿", "赔付", "补偿", "compensat")):
        return "COMPENSATION"
    if any(term in normalized for term in ("取消订单", "撤销订单", "cancel")):
        return "CANCEL_ORDER"
    if any(term in normalized for term in ("核验", "解释")):
        return "VERIFY_OR_EXPLAIN_ONLY"
    if any(term in normalized for term in ("退款", "refund")):
        return "REFUND"
    if any(term in normalized for term in ("退货", "return")):
        return "RETURN_REFUND"
    return None


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_default_claim_resolution` 围绕当事人主张计算该函数独立负责的业务派生值；返回/更新字段：`initiator_role`、`requested_resolution`、`requested_amount`、`requested_items`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._default_case_detail`、`_enforce_claim_resolution`、`_default_dispute_core_state`；下游为 本文件的 `_initial_original_statement`、`_party_role_or_default`、`_normalized_claim_statement`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _default_claim_resolution(lobby_seed: Any, source_text: str) -> dict[str, Any]:
    seed = getattr(lobby_seed, "claim_resolution_seed", None)
    initiator_role = (
        getattr(seed, "initiator_role", None)
        or _party_role_or_default(getattr(lobby_seed, "initiator_role", None))
    )
    requested_resolution = (
        getattr(seed, "requested_resolution", None)
        or getattr(lobby_seed, "requested_outcome_hint", None)
        or "UNKNOWN"
    )
    request_reason = getattr(seed, "request_reason", None) or ""
    original_statement = _initial_original_statement(lobby_seed, source_text)
    return {
        "initiator_role": initiator_role,
        "requested_resolution": requested_resolution,
        "requested_amount": getattr(seed, "requested_amount", None),
        "requested_items": getattr(seed, "requested_items", None) or "",
        "request_reason": request_reason,
        "original_statement": original_statement,
        "normalized_statement": _normalized_claim_statement(
            initiator_role,
            requested_resolution,
            request_reason,
        ),
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_enforce_claim_resolution` 校验当事人主张的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`strip`、`previous.get`、`statement_messages.append`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill.render`；下游为 本文件的 `_default_claim_resolution`、`_ensure_dict`、`_is_explicit_claim_text`、`_party_role_or_default`。
# 系统意义：这是信任边界：只建档追问，不收正式证据、不定责、不承诺赔付。
def _enforce_claim_resolution(
    detail: dict[str, Any],
    request: IntakeTurnRequest,
    previous: dict[str, Any],
) -> None:
    """Keep facts and claim text separate while preserving explicit claim quotes."""

    initial = request.initial_case_facts
    form_description = str(getattr(initial, "form_description", None) or "")
    defaults = _default_claim_resolution(initial, form_description)
    claim = _ensure_dict(detail, "claim_resolution")
    previous_claim = (
        previous.get("claim_resolution") if isinstance(previous, dict) else None
    )
    if not isinstance(previous_claim, dict):
        previous_claim = {}
    current = request.current_user_message
    current_text = current.text if current is not None else ""
    previous_matrix = previous.get("case_fact_matrix") if isinstance(previous, dict) else None
    previous_party_map = (
        previous_matrix.get("party_map")
        if isinstance(previous_matrix, dict)
        else {}
    )
    initiator_role = _party_role_or_default(
        str(
            previous_party_map.get("initiator_role")
            or previous_claim.get("initiator_role")
            or getattr(initial, "initiator_role", None)
            or (current.role if current is not None else "")
        )
    )
    actor_role = str(request.agent_context.actor_role or "").upper()
    current_is_initiator = current is None or actor_role == initiator_role
    current_is_claim = current_is_initiator and _is_explicit_claim_text(current_text)

    semantic_fields = (
        "initiator_role",
        "requested_resolution",
        "requested_amount",
        "requested_items",
        "request_reason",
    )
    if current is None:
        for field_name in semantic_fields:
            claim[field_name] = copy.deepcopy(defaults.get(field_name))
    elif not current_is_initiator:
        for field_name in (*semantic_fields, "normalized_statement"):
            if field_name in previous_claim:
                claim[field_name] = copy.deepcopy(previous_claim[field_name])
            else:
                claim[field_name] = copy.deepcopy(defaults.get(field_name))
    elif not current_is_claim:
        for field_name in semantic_fields:
            if field_name in previous_claim:
                claim[field_name] = copy.deepcopy(previous_claim[field_name])
            else:
                claim[field_name] = copy.deepcopy(defaults.get(field_name))
    else:
        claim["initiator_role"] = _party_role_or_default(
            str(
                claim.get("initiator_role")
                or getattr(initial, "initiator_role", None)
                or current.role
            )
        )
        inferred_resolution = _requested_resolution_from_claim_text(current_text)
        if inferred_resolution is not None:
            claim["requested_resolution"] = inferred_resolution
        if not str(claim.get("request_reason") or "").strip():
            claim["request_reason"] = current_text

    initiator_role = _party_role_or_default(str(claim.get("initiator_role") or ""))
    requested_resolution = str(claim.get("requested_resolution") or "UNKNOWN")
    request_reason = str(claim.get("request_reason") or "")
    claim["initiator_role"] = initiator_role
    claim["requested_resolution"] = requested_resolution
    model_normalized = str(claim.get("normalized_statement") or "").strip()
    if model_normalized and not re.search(
        r"(?:^|[，。；：、\s])(?:我本人|我方|我们|本店|本人|我)",
        model_normalized,
    ):
        claim["normalized_statement"] = model_normalized
    else:
        claim["normalized_statement"] = _normalized_claim_statement(
            initiator_role,
            requested_resolution,
            request_reason,
        )

    requested = _ensure_dict(detail, "requested_resolution")
    requested["requested_outcome"] = requested_resolution
    requested["expected_resolution_text"] = claim["normalized_statement"]

    if current is not None and not current_is_initiator:
        claim.pop("original_statement", None)
        claim.pop("original_statement_provenance", None)
        return

    (
        original_statement,
        original_source,
        submission_count,
        last_message_id,
    ) = _ordered_original_statement(
        request=request,
        previous_claim=previous_claim,
        form_description=form_description,
    )
    claim["original_statement"] = original_statement
    claim["original_statement_provenance"] = {
        "policy": ORIGINAL_STATEMENT_POLICY,
        "last_message_id": last_message_id,
        "submission_count": submission_count,
        "separator": "BLANK_LINE",
        "source": original_source,
    }


def _ordered_original_statement(
    *,
    request: IntakeTurnRequest,
    previous_claim: dict[str, Any],
    form_description: str,
) -> tuple[str, str, int, str]:
    """Append form and party inputs verbatim without trusting model text.

    Java sends the opening form only on the first turn and sends a room-message
    transcript on later turns.  The persisted, Harness-authored statement is
    therefore the continuation anchor; rebuilding from only the later
    transcript would silently discard the form, as the real E2E case exposed.
    """

    previous_statement = str(previous_claim.get("original_statement") or "")
    previous_provenance = previous_claim.get("original_statement_provenance")
    previous_is_trusted = (
        bool(previous_statement)
        and isinstance(previous_provenance, dict)
        and previous_provenance.get("policy") == ORIGINAL_STATEMENT_POLICY
    )
    if previous_is_trusted:
        blocks = previous_statement.split(ORIGINAL_STATEMENT_SEPARATOR)
        last_message_id = str(previous_provenance.get("last_message_id") or "")
    else:
        opening = form_description or _initial_original_statement(
            request.initial_case_facts,
            form_description,
        )
        blocks = [opening] if opening else []
        last_message_id = ""

    transcript = list(request.initiator_statement_transcript)
    if request.current_user_message is not None and not _transcript_contains_current(
        request
    ):
        transcript.append(request.current_user_message)

    new_messages = transcript
    if previous_is_trusted and transcript:
        last_index = next(
            (
                index
                for index, message in enumerate(transcript)
                if last_message_id and message.message_id == last_message_id
            ),
            None,
        )
        if last_index is not None:
            new_messages = transcript[last_index + 1 :]
        else:
            # Older Java payloads may not retain the same synthetic message ID.
            # Remove only the longest exact overlap between the persisted suffix
            # and transcript prefix; repeated later statements remain intact.
            overlap = 0
            transcript_texts = [message.text for message in transcript]
            for size in range(min(len(blocks), len(transcript_texts)), 0, -1):
                if blocks[-size:] == transcript_texts[:size]:
                    overlap = size
                    break
            new_messages = transcript[overlap:]

    for message in new_messages:
        blocks.append(message.text)
        last_message_id = message.message_id

    original_statement = ORIGINAL_STATEMENT_SEPARATOR.join(blocks)
    if transcript or previous_is_trusted:
        source = "INITIATOR_STATEMENT_TRANSCRIPT"
    elif blocks:
        source = "INITIAL_FORM_DESCRIPTION"
    else:
        source = "NO_PARTICIPANT_STATEMENT"
    return original_statement, source, len(blocks), last_message_id


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_enforce_party_position_voice` 校验参与方信息的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`strip`、`claim.get`、`positions.get`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill.render`；下游为 本文件的 `_ensure_dict`、`_party_role_or_default`、`_third_person_text`。
# 系统意义：这是信任边界：只建档追问，不收正式证据、不定责、不承诺赔付。
def _enforce_party_position_voice(detail: dict[str, Any]) -> None:
    """Keep the normalized party-position slot in objective third person."""

    claim = _ensure_dict(detail, "claim_resolution")
    initiator_role = _party_role_or_default(str(claim.get("initiator_role") or ""))
    positions = _ensure_dict(detail, "party_positions")
    key = "merchant_claim" if initiator_role == "MERCHANT" else "user_claim"
    value = str(positions.get(key) or "").strip()
    if value:
        positions[key] = _third_person_text(value, initiator_role)


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_initial_original_statement` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`original_statement.strip`。
# 上下游：上游为 本文件的 `_default_claim_resolution`；下游为 协作调用 `original_statement.strip`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _initial_original_statement(lobby_seed: Any, source_text: str) -> str:
    seed = getattr(lobby_seed, "claim_resolution_seed", None)
    if seed is not None:
        original_statement = getattr(seed, "original_statement", None)
        if isinstance(original_statement, str) and original_statement.strip():
            return original_statement
    return ""


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_non_negative_int` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 接待话术、卷宗补丁、受理建议、证据室。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _non_negative_int(value: Any) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_enforce_respondent_attitude_source` 校验对方态度的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`prior_reported_texts.extend`、`previous_attitude.get`、`previous.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_party_role_or_default`、`_nested_attitude`、`_subjective_attitude`、`_has_explicit_respondent_report`。
# 系统意义：这是信任边界：只建档追问，不收正式证据、不定责、不承诺赔付。
def _enforce_respondent_attitude_source(
    detail: dict[str, Any],
    request: IntakeTurnRequest,
    previous: dict[str, Any],
    llm_case_detail: dict[str, Any] | None,
) -> None:
    """Persist only attitudes reported by the initiator in this private room.

    A formal response belongs to a shared or respondent-authored room, not the
    intake room. Legacy snapshots and external seeds with formal provenance are
    therefore discarded instead of being relabelled as a subjective report.
    """

    initial = request.initial_case_facts
    current = request.current_user_message
    previous_matrix = (
        previous.get("case_fact_matrix") if isinstance(previous, dict) else None
    )
    previous_party_map = (
        previous_matrix.get("party_map")
        if isinstance(previous_matrix, dict)
        else {}
    )
    initiator_role = _party_role_or_default(
        getattr(initial, "initiator_role", None)
        or previous_party_map.get("initiator_role")
        or (current.role if current is not None else None)
    )
    actor_role = str(request.agent_context.actor_role or "").upper()
    if current is not None and actor_role == _opposite_party(initiator_role):
        llm_attitude = _nested_attitude(llm_case_detail)
        candidate = _reported_attitude(llm_attitude)
        if candidate is None:
            candidate = {
                "attitude": "NEED_MORE_INFO",
                "position": current.text,
                "confidence": 0.5,
            }
        detail["respondent_attitude"] = {
            "respondent_role": actor_role,
            "attitude": candidate["attitude"],
            "position": candidate["position"],
            "source": DIRECT_RESPONDENT_SOURCE,
            "confidence": _clamp_confidence(candidate.get("confidence", 0.5)),
            "grounding": {
                "source": "RESPONDENT_PARTICIPANT_MESSAGE",
                "message_id": current.message_id,
            },
        }
        return
    form_description = str(getattr(initial, "form_description", None) or "")
    current_reports_attitude = current is not None and _has_explicit_respondent_report(
        current.text,
        initiator_role,
    )
    prior_reported_texts = [form_description] if form_description else []
    prior_reported_texts.extend(
        message.text
        for message in request.initiator_statement_transcript
        if current is None or message.message_id != current.message_id
    )
    prior_has_report = any(
        _has_explicit_respondent_report(text, initiator_role)
        for text in prior_reported_texts
    )

    llm_attitude = _nested_attitude(llm_case_detail)
    previous_attitude = (
        previous.get("respondent_attitude") if isinstance(previous, dict) else None
    )
    if not isinstance(previous_attitude, dict):
        previous_attitude = {}

    previous_candidate = _subjective_attitude(previous_attitude)
    previous_grounding = previous_attitude.get("grounding")
    previous_is_grounded = prior_has_report or (
        isinstance(previous_grounding, dict)
        and str(previous_grounding.get("source") or "")
        in {"INITIAL_FORM", "PARTICIPANT_MESSAGE"}
    )

    if current is not None and not current_reports_attitude:
        candidate = previous_candidate if previous_is_grounded else None
        grounding_source = "PARTICIPANT_MESSAGE"
        grounding_message_id = str(
            (previous_grounding or {}).get("message_id")
            if isinstance(previous_grounding, dict)
            else ""
        )
    elif current_reports_attitude:
        candidate = (
            _reported_attitude(llm_attitude)
            or _reported_attitude_from_text(current.text, initiator_role)
            or previous_candidate
        )
        candidate = _pin_attitude_position_to_source(
            candidate,
            current.text,
            initiator_role,
        )
        grounding_source = "PARTICIPANT_MESSAGE"
        grounding_message_id = current.message_id if current is not None else ""
    elif form_description and _has_explicit_respondent_report(
        form_description,
        initiator_role,
    ):
        candidate = _reported_attitude(llm_attitude)
        if candidate is None:
            candidate = _subjective_seed_attitude(initial)
        if candidate is None:
            candidate = _reported_attitude_from_text(
                form_description,
                initiator_role,
            )
        candidate = _pin_attitude_position_to_source(
            candidate,
            form_description,
            initiator_role,
        )
        grounding_source = "INITIAL_FORM"
        grounding_message_id = ""
    elif previous_is_grounded:
        candidate = previous_candidate
        grounding_source = "PARTICIPANT_MESSAGE"
        grounding_message_id = ""
    else:
        candidate = None
        grounding_source = ""
        grounding_message_id = ""

    if candidate is None:
        detail["respondent_attitude"] = _default_respondent_attitude(
            initial,
            allow_subjective_seed=False,
        )
        _clear_ungrounded_counterparty_position(detail, initiator_role)
        return

    detail["respondent_attitude"] = {
        "respondent_role": _opposite_party(initiator_role),
        "attitude": candidate["attitude"],
        "position": candidate["position"],
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": _clamp_confidence(candidate.get("confidence", 0.5)),
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        "grounding": {
            "source": grounding_source,
            "message_id": grounding_message_id,
        },
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_has_explicit_respondent_report` 判断本阶段状态是否满足当前业务分支条件；关键协作调用：`strip`、`re.search`。
# 上下游：上游为 本文件的 `_remove_ungrounded_respondent_attitude`、`_enforce_respondent_attitude_source`；下游为 协作调用 `strip`、`re.search`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _has_explicit_respondent_report(text: str, initiator_role: str) -> bool:
    normalized = str(text or "").strip()
    if not normalized:
        return False
    party_pattern = (
        r"(?:用户|买家|客户|对方)"
        if initiator_role == "MERCHANT"
        else r"(?:商家|卖家|店铺|商户|客服|对方)"
    )
    attitude_pattern = (
        r"(?:说|表示|回复|回应|答复|同意|接受|拒绝|不同意|不支持|"
        r"要求|提出|建议|愿意|承诺|让我|让其|未回应|没回应|没有回应)"
    )
    return bool(
        re.search(rf"{party_pattern}.{{0,24}}{attitude_pattern}", normalized)
        or re.search(rf"{attitude_pattern}.{{0,16}}{party_pattern}", normalized)
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_reported_attitude_from_text` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`strip`、`re.search`、`normalized.rstrip`；返回/更新字段：`attitude`、`position`、`confidence`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `strip`、`re.search`、`normalized.rstrip`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _reported_attitude_from_text(
    text: str,
    initiator_role: str = "USER",
) -> dict[str, Any] | None:
    normalized = str(text or "").strip()
    if not normalized:
        return None
    if re.search(r"部分同意|部分接受|只能.{0,8}(退|赔|补发|换货)", normalized):
        attitude = "PARTIALLY_AGREE"
    elif re.search(
        r"拒绝|不同意|不支持|不接受|不给|不退|不赔|不愿意|不能|不可以",
        normalized,
    ):
        attitude = "DISAGREE"
    elif re.search(r"同意|接受|支持|愿意|承诺", normalized):
        attitude = "AGREE"
    elif re.search(r"替代方案|改为|建议|提出.{0,12}(方案|处理)", normalized):
        attitude = "ALTERNATIVE_PROPOSED"
    elif re.search(r"要求.{0,12}(补充|说明)|需要更多信息", normalized):
        attitude = "NEED_MORE_INFO"
    elif re.search(r"未回应|没回应|没有回应", normalized):
        return None
    else:
        attitude = "PLATFORM_UNKNOWN"
    return {
        "attitude": attitude,
        "position": (
            _reported_attitude_position(normalized, initiator_role)
            or normalized.rstrip("。") + "。"
        ),
        "confidence": 0.65,
    }


def _pin_attitude_position_to_source(
    candidate: dict[str, Any] | None,
    source_text: str,
    initiator_role: str,
) -> dict[str, Any] | None:
    """Prevent a model from copying the whole case narrative into attitude."""

    if candidate is None:
        return None
    position = _reported_attitude_position(source_text, initiator_role)
    if not position:
        return candidate
    pinned = dict(candidate)
    pinned["position"] = position
    return pinned


def _reported_attitude_position(text: str, initiator_role: str) -> str:
    """Extract only clauses attributed to the counterparty.

    This is a provenance guard rather than a second semantic classifier.  The
    model still chooses the attitude code in the single business call, while
    the Harness ensures the persisted position is a bounded slice of what the
    initiator actually said about the other party.
    """

    normalized = str(text or "").strip()
    if not normalized:
        return ""
    party_pattern = (
        r"(?:用户|买家|客户|对方)"
        if initiator_role == "MERCHANT"
        else r"(?:商家|卖家|店铺|商户|客服|对方)"
    )
    attitude_pattern = (
        r"(?:说|表示|回复|回应|答复|同意|接受|拒绝|不同意|不支持|"
        r"不能|不可以|只同意|只接受|要求|提出|建议|愿意|承诺|让我|让其)"
    )
    extracted: list[str] = []
    for sentence in re.split(r"(?<=[。！？!?])", normalized):
        sentence = sentence.strip()
        if not sentence or not _has_explicit_respondent_report(
            sentence,
            initiator_role,
        ):
            continue
        attributed = ""
        for party in re.finditer(party_pattern, sentence):
            prefix = sentence[: party.start()].rstrip("，,；; ")
            if re.search(
                r"(?:我|本人|我方|我们|用户|买家|商家|卖家).{0,24}"
                r"(?:希望|要求|申请|请求|愿意|可以接受|可接受|接受)$",
                prefix,
            ) or re.search(
                r"(?:联系|咨询|询问|找到|告知|通知|请求|要求|申请|向)$",
                prefix,
            ):
                # “我联系商家要求换货”中的商家是联系对象，并不是
                # “要求换货”的发言主体。继续寻找后面的“商家回复……”。
                continue
            candidate = sentence[party.start() :]
            if re.search(attitude_pattern, candidate) is None:
                continue
            attributed = candidate
            break
        if not attributed:
            continue
        clauses: list[str] = []
        for clause in re.split(r"(?<=[，,；;])", attributed):
            cleaned = clause.strip()
            if not cleaned:
                continue
            if clauses and _CLAIM_INTENT_PATTERN.search(cleaned):
                break
            clauses.append(cleaned)
        value = "".join(clauses).strip("，,；;。 ")
        if value:
            extracted.append(value)
    if not extracted:
        return ""
    return "；".join(extracted)[:500].rstrip("。；; ") + "。"


def _follow_up_questions_from_utterance(value: Any) -> list[str]:
    """Extract the model's user-visible follow-up questions in display order."""

    text = str(value or "").strip()
    if not text:
        return []

    questions: list[str] = []
    numbered = list(re.finditer(r"(?<!\d)([1-9]\d*)[.、．)]\s*", text))
    if numbered:
        for index, marker in enumerate(numbered):
            end = numbered[index + 1].start() if index + 1 < len(numbered) else len(text)
            candidate = text[marker.end() : end].strip()
            question_end = re.search(r"[？?]", candidate)
            if question_end is not None:
                candidate = candidate[: question_end.end()].strip()
            if candidate:
                questions.append(candidate)
    else:
        questions.extend(
            match.group(0).strip()
            for match in re.finditer(r"[^。！？?\r\n]+[？?]", text)
            if match.group(0).strip()
        )

    unique: list[str] = []
    for question in questions:
        if question not in unique:
            unique.append(question)
    return unique[:2]


def _question_targets_resolved_intake_field(
    value: Any,
    case_detail: dict[str, Any],
    *,
    actor_role: str | None = None,
) -> bool:
    """Drop follow-up questions whose answer already exists in the trusted dossier.

    The model remains responsible for deciding what to ask in the single turn.
    This guard only prevents a known structured field from being asked again.
    """

    question = str(value or "").strip()
    if not question:
        return False
    claim = case_detail.get("claim_resolution")
    claim = claim if isinstance(claim, dict) else {}
    claim_role = str(claim.get("initiator_role") or "").upper()
    normalized_actor_role = str(actor_role or "").upper()
    claim_belongs_to_actor = (
        not normalized_actor_role
        or not claim_role
        or normalized_actor_role == claim_role
    )
    requested_resolution = str(claim.get("requested_resolution") or "").upper()
    if (
        claim_belongs_to_actor
        and requested_resolution
        and requested_resolution != "UNKNOWN"
        and re.search(
        r"具体诉求|诉求是|希望.{0,12}(怎么处理|如何处理)|"
        r"换货.{0,12}退货退款|处理方式",
        question,
        )
    ):
        return True
    if claim_belongs_to_actor and claim.get("requested_amount") is not None and re.search(
        r"诉求金额|要求.{0,8}金额|补偿.{0,8}金额|退款.{0,8}金额",
        question,
    ):
        return True
    attitude = case_detail.get("respondent_attitude")
    attitude = attitude if isinstance(attitude, dict) else {}
    attitude_code = str(attitude.get("attitude") or "NOT_RESPONDED").upper()
    if attitude_code not in {"", "NOT_RESPONDED", "PLATFORM_UNKNOWN"} and re.search(
        r"对方.{0,12}(态度|回应)|商家.{0,12}(态度|回应)|"
        r"用户.{0,12}(态度|回应)|是否同意|是否接受",
        question,
    ):
        return True
    return False


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_clear_ungrounded_counterparty_position` 围绕庭审轮次计算该函数独立负责的业务派生值。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_ensure_dict`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _clear_ungrounded_counterparty_position(
    detail: dict[str, Any],
    initiator_role: str,
) -> None:
    positions = _ensure_dict(detail, "party_positions")
    positions["user_claim" if initiator_role == "MERCHANT" else "merchant_claim"] = ""


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_enforce_dispute_core_state` 校验本阶段状态的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`upper`、`CLAIM_RESOLUTION_LABELS.get`、`claim.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_ensure_dict`、`_party_role_or_default`、`_opposite_party`、`_role_label`。
# 系统意义：这是信任边界：只建档追问，不收正式证据、不定责、不承诺赔付。
def _enforce_dispute_core_state(detail: dict[str, Any]) -> None:
    claim = _ensure_dict(detail, "claim_resolution")
    attitude = _ensure_dict(detail, "respondent_attitude")
    core = _ensure_dict(detail, "dispute_core_state")
    initiator_role = _party_role_or_default(str(claim.get("initiator_role") or ""))
    respondent_role = _opposite_party(initiator_role)
    resolution_code = str(claim.get("requested_resolution") or "UNKNOWN").upper()
    resolution_label = CLAIM_RESOLUTION_LABELS.get(resolution_code, "相关处理")
    initiator = _role_label(initiator_role)
    respondent = _role_label(respondent_role)
    attitude_code = str(attitude.get("attitude") or "NOT_RESPONDED").upper()
    if attitude_code == "NOT_RESPONDED":
        core["conflict_type"] = "CLAIM_UNANSWERED"
        core["core_conflict"] = (
            f"{initiator}的具体处理诉求待确认，{respondent}态度尚待补充。"
            if resolution_code == "UNKNOWN"
            else f"{initiator}请求{resolution_label}，但{respondent}态度尚待补充。"
        )
    elif attitude_code == "AGREE":
        core["conflict_type"] = "CLAIM_ACCEPTED_PENDING_VERIFICATION"
        core["core_conflict"] = (
            f"{respondent}被转述为同意{initiator}提出的{resolution_label}诉求，相关事实仍待核验。"
        )
    elif attitude_code == "PARTIALLY_AGREE":
        core["conflict_type"] = "CLAIM_PARTIALLY_ACCEPTED"
        core["core_conflict"] = (
            f"{respondent}被转述为仅部分接受{initiator}提出的{resolution_label}诉求。"
        )
    elif attitude_code == "DISAGREE":
        core["conflict_type"] = "CLAIM_REJECTED_WITH_FACT_DISPUTE"
        core["core_conflict"] = (
            f"{initiator}请求{resolution_label}，但{respondent}被转述为不同意该诉求。"
        )
    else:
        core["conflict_type"] = "CLAIM_WITH_EVIDENCE_GAP"
        core["core_conflict"] = (
            f"{initiator}请求{resolution_label}，{respondent}被转述的回应仍需进一步核验。"
        )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_subjective_seed_attitude` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`seed.model_dump`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_subjective_attitude`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _subjective_seed_attitude(initial: Any) -> dict[str, Any] | None:
    seed = getattr(initial, "respondent_attitude_seed", None)
    seed_values = (
        seed.model_dump(mode="python")
        if seed is not None and hasattr(seed, "model_dump")
        else {}
    )
    return _subjective_attitude(seed_values)


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_nested_attitude` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`case_detail.get`。
# 上下游：上游为 本文件的 `_enforce_respondent_attitude_source`；下游为 协作调用 `case_detail.get`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _nested_attitude(case_detail: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(case_detail, dict):
        return {}
    attitude = case_detail.get("respondent_attitude")
    return attitude if isinstance(attitude, dict) else {}


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_reported_attitude` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`upper`、`strip`、`candidate.get`；返回/更新字段：`attitude`、`position`、`confidence`。
# 上下游：上游为 本文件的 `_subjective_attitude`；下游为 协作调用 `upper`、`strip`、`candidate.get`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _reported_attitude(candidate: dict[str, Any]) -> dict[str, Any] | None:
    attitude_code = str(candidate.get("attitude") or "NOT_RESPONDED").upper()
    position = str(candidate.get("position") or "").strip()
    if (
        attitude_code not in RESPONDENT_ATTITUDE_CODES
        or attitude_code == "NOT_RESPONDED"
        or not position
    ):
        return None
    return {
        "attitude": attitude_code,
        "position": position,
        "confidence": candidate.get("confidence", 0.5),
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_subjective_attitude` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`strip`、`candidate.get`。
# 上下游：上游为 本文件的 `_enforce_respondent_attitude_source`、`_subjective_seed_attitude`、`_default_respondent_attitude`；下游为 本文件的 `_reported_attitude`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _subjective_attitude(candidate: dict[str, Any]) -> dict[str, Any] | None:
    if str(candidate.get("source") or "").strip() != SUBJECTIVE_RESPONDENT_SOURCE:
        return None
    return _reported_attitude(candidate)


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_default_respondent_attitude` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`seed.model_dump`、`subjective_seed.get`；返回/更新字段：`respondent_role`、`attitude`、`position`、`source`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._default_case_detail`、`_enforce_respondent_attitude_source`、`_default_dispute_core_state`；下游为 本文件的 `_party_role_or_default`、`_opposite_party`、`_subjective_attitude`、`_clamp_confidence`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _default_respondent_attitude(
    lobby_seed: Any,
    *,
    allow_subjective_seed: bool = True,
) -> dict[str, Any]:
    seed = getattr(lobby_seed, "respondent_attitude_seed", None)
    initiator_role = _party_role_or_default(getattr(lobby_seed, "initiator_role", None))
    respondent_role = _opposite_party(initiator_role)
    seed_values = (
        seed.model_dump(mode="python")
        if seed is not None and hasattr(seed, "model_dump")
        else {}
    )
    subjective_seed = _subjective_attitude(seed_values) if allow_subjective_seed else None
    if subjective_seed is not None:
        return {
            "respondent_role": respondent_role,
            "attitude": subjective_seed["attitude"],
            "position": subjective_seed["position"],
            "source": SUBJECTIVE_RESPONDENT_SOURCE,
            "confidence": _clamp_confidence(
                subjective_seed.get("confidence", 0.5)
            ),
            "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        }
    return {
        "respondent_role": respondent_role,
        "attitude": "NOT_RESPONDED",
        "position": f"{_role_label(respondent_role)}尚未在接待室表达态度。",
        "source": "尚未回应",
        "confidence": 0.5,
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_default_dispute_core_state` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`CLAIM_RESOLUTION_LABELS.get`、`upper`；返回/更新字段：`core_conflict`、`conflict_type`、`facts_in_dispute`、`next_verification_focus`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._default_case_detail`；下游为 本文件的 `_default_claim_resolution`、`_default_respondent_attitude`、`_role_label`、`_verification_focus_for_text`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _default_dispute_core_state(lobby_seed: Any, source_text: str) -> dict[str, Any]:
    claim = _default_claim_resolution(lobby_seed, source_text)
    attitude = _default_respondent_attitude(lobby_seed)
    resolution_label = CLAIM_RESOLUTION_LABELS.get(
        str(claim["requested_resolution"] or "UNKNOWN").upper(),
        "相关处理",
    )
    initiator = _role_label(claim["initiator_role"])
    respondent = _role_label(attitude["respondent_role"])
    if attitude["attitude"] == "NOT_RESPONDED":
        conflict_type = "CLAIM_UNANSWERED"
        core_conflict = f"{initiator}请求{resolution_label}，但{respondent}态度尚待补充。"
    elif attitude["attitude"] == "DISAGREE":
        conflict_type = "CLAIM_REJECTED_WITH_FACT_DISPUTE"
        core_conflict = f"{initiator}请求{resolution_label}，但{respondent}不同意该诉求。"
    else:
        conflict_type = "CLAIM_WITH_EVIDENCE_GAP"
        core_conflict = f"{initiator}请求{resolution_label}，{respondent}回应状态仍需结合证据核验。"
    return {
        "core_conflict": core_conflict,
        "conflict_type": conflict_type,
        "facts_in_dispute": [],
        "next_verification_focus": _verification_focus_for_text(source_text),
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_normalized_claim_statement` 把当事人主张转换为稳定的接口、提示词或页面表达；关键协作调用：`CLAIM_RESOLUTION_LABELS.get`、`upper`。
# 上下游：上游为 本文件的 `_default_claim_resolution`、`_enforce_claim_resolution`；下游为 本文件的 `_role_label`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _normalized_claim_statement(
    initiator_role: str,
    requested_resolution: str,
    request_reason: str,
) -> str:
    role = _role_label(initiator_role)
    resolution = CLAIM_RESOLUTION_LABELS.get(
        str(requested_resolution or "UNKNOWN").upper(),
        "相关处理",
    )
    if str(requested_resolution or "UNKNOWN").upper() == "UNKNOWN":
        return f"{role}的具体处理诉求待确认。"
    return f"{role}请求{resolution}。"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_third_person_text` 围绕展示文本计算该函数独立负责的业务派生值；关键协作调用：`strip`、`replacements.items`、`normalized.replace`。
# 上下游：上游为 本文件的 `_enforce_party_position_voice`；下游为 本文件的 `_role_label`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _third_person_text(text: str, initiator_role: str) -> str:
    role = _role_label(initiator_role)
    normalized = (text or "").strip("。 ")
    replacements = {
        "我本人": role,
        "我方": role,
        "我们": role,
        "本店": role,
        "本人": role,
        "我": role,
    }
    for source, target in replacements.items():
        normalized = normalized.replace(source, target)
    return normalized or "争议发起方提出处理诉求"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_verification_focus_for_text` 围绕展示文本计算该函数独立负责的业务派生值；关键协作调用：`focus.extend`、`focus.append`。
# 上下游：上游为 本文件的 `_default_dispute_core_state`；下游为 协作调用 `focus.extend`、`focus.append`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _verification_focus_for_text(text: str) -> list[str]:
    focus: list[str] = []
    if "签收" in text or "物流" in text:
        focus.extend(["签收人身份", "签收位置", "物流投递轨迹"])
    if "未收到" in text or "没收到" in text:
        focus.append("用户未收货证明")
    return focus


_VERIFICATION_FOCUS_RULES: tuple[tuple[str, re.Pattern[str], str], ...] = (
    (
        "subscription-use",
        re.compile(r"新周期.{0,10}(使用|服务)|扣款后.{0,10}(未使用|使用)"),
        "核验新周期服务是否实际使用",
    ),
    (
        "subscription-charge",
        re.compile(
            r"自动续费|续费扣款|到期日.{0,8}扣款|扣款.{0,12}(时间|金额|周期|日期)|"
            r"(时间|日期|发现).{0,12}扣款"
        ),
        "核验自动续费扣款时间、金额与服务周期",
    ),
    (
        "renewal-notice",
        re.compile(
            r"续费.{0,10}(提醒|通知|提示)|提醒.{0,12}(短信|邮件|推送|显著|渠道)"
        ),
        "核验续费提醒的发送时间、渠道与显著性",
    ),
    (
        "promotion-promise",
        re.compile(
            r"直播间|主播|宣传.{0,12}(承诺|返现|差价|优惠|活动|规则)|"
            r"(承诺|返现|差价|优惠|活动).{0,12}(规则|条件|名额|宣传)"
        ),
        "核验直播宣传承诺、适用条件与活动规则",
    ),
    (
        "product-page",
        re.compile(r"商品.{0,8}(页面|详情|描述)|页面.{0,8}(截图|快照|描述)|详情页|商品链接"),
        "核对商品页面完整描述、截图或快照",
    ),
    (
        "communication",
        re.compile(
            r"沟通记录|聊天记录|聊天截图|客服记录|协商记录|"
            r"客服.{0,12}(回复|回应|答复)|"
            r"联系.{0,4}客服|客服.{0,6}(联系|沟通)|"
            r"与商家.{0,8}(沟通|聊天)|与用户.{0,8}(沟通|聊天)"
        ),
        "核验用户与商家的完整沟通记录",
    ),
    (
        "product-condition",
        re.compile(
            r"开箱|拆箱|磨损|划痕|破损|损坏|外观|瑕疵|"
            r"商品.{0,6}(照片|图片|视频)|(照片|图片|视频).{0,6}(磨损|划痕|破损|损坏|瑕疵)"
        ),
        "核验商品异常照片或开箱视频，确认商品状态及形成时间",
    ),
    (
        "logistics-signoff",
        re.compile(r"物流|签收|投递|派送|收货|验货|快递|包裹|开包|打开检查|开启包裹"),
        "核验物流签收及投递记录，确认签收人身份、位置、时间与开箱检查间隔",
    ),
    (
        "order",
        re.compile(r"订单号|订单信息|涉案商品|商品数量"),
        "核对订单信息与涉案商品",
    ),
    (
        "after-sale",
        re.compile(r"售后单|售后申请|售后记录|处理记录"),
        "核对售后申请与处理记录",
    ),
    (
        "respondent-attitude",
        re.compile(
            r"对方.{0,20}(回应|态度)|商家.{0,20}(回应|态度)|"
            r"用户.{0,20}(回应|态度)|是否接受.{0,12}(退款|诉求|补偿)"
        ),
        "核实对方对诉求的明确回应",
    ),
)

_PROCESS_VERIFICATION_FOCUS_RE = re.compile(
    r"信息完整度|完整度已达到|提交阈值|可以提交|等待接待官|接待官.{0,12}整理|"
    r"完成案件详情|案件详情整理|进入下一步|后续流程|流程推进|"
    r"ready_for_next_step|READY_PENDING_REMARK_INVITE|WAITING_FOR_REMARK|NOT_READY",
    re.IGNORECASE,
)


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_clean_verification_focus` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`text.strip`、`re.sub`。
# 上下游：上游为 本文件的 `_generic_verification_action`、`_canonical_verification_focus`；下游为 协作调用 `text.strip`、`re.sub`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _clean_verification_focus(value: Any) -> str:
    text = str(value or "")
    substitutions = (
        (r"^[\s·•\-—]+", ""),
        (r"^(仍然|仍|目前)?缺少(可信的|完整的|相关的)?", ""),
        (r"^(请问)?(您|你)?是否有", ""),
        (r"^(能否|是否可以|可否)(请)?(提供|补充)?", ""),
        (r"^(请|麻烦)(您|你)?(提供|补充|说明|确认|核实|核对)?", ""),
        (r"(是否可以提供|是否能提供|可以提供吗|能提供吗)$", ""),
        (r"[？?。；;，,\s]+$", ""),
    )
    for pattern, replacement in substitutions:
        text = re.sub(pattern, replacement, text)
    return text.strip()


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_generic_verification_action` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`re.sub`、`re.match`。
# 上下游：上游为 本文件的 `_canonical_verification_focus`；下游为 本文件的 `_clean_verification_focus`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _generic_verification_action(value: Any) -> str:
    text = _clean_verification_focus(value)
    if not text:
        return ""
    text = re.sub(r"^(获取|收集|补充|提供)", "核验", text)
    if re.match(r"^(核验|核对|核实|确认)", text):
        return text
    return f"核验{text}"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_canonical_verification_focus` 判断本阶段状态是否满足当前业务分支条件；关键协作调用：`next`、`seen.add`、`normalized.append`。
# 上下游：上游为 本文件的 `_normalize_next_verification_focus`；下游为 本文件的 `_generic_verification_action`、`_respondent_party_for_focus`、`_clean_verification_focus`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _canonical_verification_focus(
    values: list[Any],
    *,
    respondent_role: str | None = None,
) -> list[str]:
    normalized: list[str] = []
    seen_keys: set[str] = set()
    seen_semantics: list[str] = []
    sources = [
        source
        for value in values
        if (source := _clean_verification_focus(value))
        and not _PROCESS_VERIFICATION_FOCUS_RE.search(source)
    ]
    has_product_condition_context = any(
        re.search(r"开箱|拆箱|磨损|划痕|破损|损坏|外观|瑕疵", source)
        for source in sources
    )
    for source in sources:
        rule = next(
            (candidate for candidate in _VERIFICATION_FOCUS_RULES if candidate[1].search(source)),
            None,
        )
        if (
            rule is None
            and has_product_condition_context
            and re.fullmatch(r"照片|图片|视频", source)
        ):
            rule = next(
                candidate
                for candidate in _VERIFICATION_FOCUS_RULES
                if candidate[0] == "product-condition"
            )
        text = rule[2] if rule is not None else _generic_verification_action(source)
        if rule is not None and rule[0] == "respondent-attitude":
            party = _respondent_party_for_focus(source, respondent_role)
            text = f"核实{party}对诉求的明确回应"
        respondent_position = bool(
            re.search(r"(?:商家|卖家|店铺|商户|客服|用户|买家|客户|对方)", source)
            and re.search(
                r"诉求|回应|态度|处理方案|处理意见|退款|补发|换货|维修|费用承担",
                source,
            )
        )
        key = (
            "respondent-position"
            if respondent_position
            else rule[0]
            if rule is not None
            else re.sub(r"[\s、，,。；;：:]", "", text)
        )
        semantic_text = re.sub(
            r"^(?:核验|核对|核实|确认)|[\s、，,。；;：:]",
            "",
            text,
        )
        is_near_duplicate = any(
            semantic_text in prior
            or prior in semantic_text
            or SequenceMatcher(None, semantic_text, prior).ratio() >= 0.78
            for prior in seen_semantics
        )
        if not text or key in seen_keys or is_near_duplicate:
            continue
        seen_keys.add(key)
        seen_semantics.append(semantic_text)
        normalized.append(text)
        if len(normalized) >= 4:
            break
    return normalized


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_respondent_party_for_focus` 围绕参与方信息计算该函数独立负责的业务派生值；关键协作调用：`upper`、`re.search`。
# 上下游：上游为 本文件的 `_canonical_verification_focus`；下游为 协作调用 `upper`、`re.search`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _respondent_party_for_focus(source: str, respondent_role: str | None) -> str:
    explicit = str(respondent_role or "").upper()
    if explicit == "MERCHANT":
        return "商家"
    if explicit == "USER":
        return "用户"
    if re.search(r"商家.{0,20}(回应|态度|诉求)|商家客服", source):
        return "商家"
    if re.search(r"用户.{0,20}(回应|态度|诉求)", source):
        return "用户"
    return "对方"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_normalize_next_verification_focus` 把本阶段状态转换为稳定的接口、提示词或页面表达；关键协作调用：`detail.get`、`attitude.get`、`core.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_ensure_dict`、`_canonical_verification_focus`、`_list_values`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _normalize_next_verification_focus(detail: dict[str, Any]) -> None:
    core = _ensure_dict(detail, "dispute_core_state")
    dispute_focus = _ensure_dict(detail, "dispute_focus")
    missing = _ensure_dict(detail, "missing_information")
    attitude = detail.get("respondent_attitude")
    respondent_role = (
        str(attitude.get("respondent_role") or "")
        if isinstance(attitude, dict)
        else ""
    )
    candidates = [
        *_list_values(core.get("next_verification_focus")),
        *_list_values(dispute_focus.get("facts_to_verify")),
        *_list_values(missing.get("blocking_gaps")),
        *_list_values(missing.get("nice_to_have_gaps")),
    ]
    core["next_verification_focus"] = _canonical_verification_focus(
        candidates,
        respondent_role=respondent_role,
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_party_role_or_default` 围绕参与方信息计算该函数独立负责的业务派生值；关键协作调用：`upper`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._default_case_detail`、`_enforce_case_story_summary`、`_default_claim_resolution`、`_enforce_claim_resolution`；下游为 协作调用 `upper`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _party_role_or_default(value: str | None) -> str:
    value = str(value or "").upper()
    if value == "MERCHANT":
        return "MERCHANT"
    return "USER"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_opposite_party` 围绕参与方信息计算该函数独立负责的业务派生值；关键协作调用：`upper`。
# 上下游：上游为 本文件的 `_enforce_dispute_core_state`、`_default_respondent_attitude`；下游为 协作调用 `upper`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _opposite_party(value: str | None) -> str:
    return "USER" if str(value or "").upper() == "MERCHANT" else "MERCHANT"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_role_label` 围绕角色权限计算该函数独立负责的业务派生值；关键协作调用：`upper`。
# 上下游：上游为 本文件的 `_enforce_dispute_core_state`、`_default_respondent_attitude`、`_default_dispute_core_state`、`_normalized_claim_statement`；下游为 协作调用 `upper`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _role_label(value: str | None) -> str:
    return "商家" if str(value or "").upper() == "MERCHANT" else "用户"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_is_case_detail` 判断本阶段状态是否满足当前业务分支条件；关键协作调用：`value.get`。
# 上下游：上游为 本文件的 `_case_detail_ready`；下游为 协作调用 `value.get`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _is_case_detail(value: dict[str, Any]) -> bool:
    return value.get("schema_version") == CaseDetailDossierSkill.schema_version


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_case_detail_ready` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`value.get`、`quality.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_is_case_detail`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _case_detail_ready(value: dict[str, Any]) -> bool:
    if not _is_case_detail(value):
        return False
    quality = value.get("intake_quality")
    return isinstance(quality, dict) and quality.get("ready_for_next_step") is True


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_handoff_remark_status` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`value.get`、`notes.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `value.get`、`notes.get`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _handoff_remark_status(value: dict[str, Any]) -> str:
    notes = value.get("handoff_notes")
    if not isinstance(notes, dict):
        return ""
    return str(notes.get("remark_status") or "")


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_ensure_handoff_notes` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`notes.setdefault`、`notes.get`。
# 上下游：上游为 本文件的 `_record_handoff_remark_if_needed`；下游为 本文件的 `_ensure_dict`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _ensure_handoff_notes(
    detail: dict[str, Any],
    *,
    remark_status: str | None = None,
) -> dict[str, Any]:
    notes = _ensure_dict(detail, "handoff_notes")
    if remark_status is not None:
        notes["remark_status"] = remark_status
    elif not notes.get("remark_status") or notes.get("remark_status") == "NOT_READY":
        notes["remark_status"] = "WAITING_FOR_REMARK"
    notes.setdefault("latest_remark", "")
    remarks = notes.get("remarks")
    if not isinstance(remarks, list):
        notes["remarks"] = []
    notes.setdefault("instruction", "如有备注，将随案件详情提交给证据书记官。")
    return notes


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_record_handoff_remark_if_needed` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`current.text.strip`、`remarks.append`、`item.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_ensure_handoff_notes`、`_is_no_extra_remark`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _record_handoff_remark_if_needed(
    detail: dict[str, Any],
    request: IntakeTurnRequest,
    *,
    previous_waiting_for_remark: bool,
) -> None:
    current = request.current_user_message
    if (
        current is None
        or not previous_waiting_for_remark
        or not current.text.strip()
    ):
        return

    notes = _ensure_handoff_notes(detail)
    text = current.text.strip()
    if _is_no_extra_remark(text):
        notes["remark_status"] = "NO_EXTRA_REMARKS"
        notes["latest_remark"] = "无额外备注。"
        return

    notes["remark_status"] = "HAS_REMARKS"
    notes["latest_remark"] = text
    remarks = notes["remarks"]
    source_message_id = current.message_id
    if not any(
        isinstance(item, dict) and item.get("source_message_id") == source_message_id
        for item in remarks
    ):
        remarks.append(
            {
                "role": current.role,
                "text": text,
                "source_message_id": source_message_id,
                "turn_source": request.turn_source,
            }
        )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_is_no_extra_remark` 判断本阶段状态是否满足当前业务分支条件；关键协作调用：`casefold`、`re.sub`。
# 上下游：上游为 本文件的 `_record_handoff_remark_if_needed`；下游为 协作调用 `casefold`、`re.sub`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _is_no_extra_remark(text: str) -> bool:
    normalized = re.sub(r"\s+", "", text or "").casefold()
    return normalized in {
        "没有",
        "无",
        "没有补充",
        "无补充",
        "没有备注",
        "无备注",
        "不用备注",
        "no",
        "nothingelse",
        "noadditionalnotes",
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_ensure_dict` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`container.get`。
# 上下游：上游为 本文件的 `_enforce_case_story_summary`、`_enforce_claim_resolution`、`_enforce_party_position_voice`、`_clear_ungrounded_counterparty_position`；下游为 协作调用 `container.get`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _ensure_dict(container: dict[str, Any], key: str) -> dict[str, Any]:
    value = container.get(key)
    if not isinstance(value, dict):
        value = {}
        container[key] = value
    return value


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_first_match` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`pattern.search`、`upper`、`match.group`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._trusted_references`；下游为 协作调用 `pattern.search`、`upper`、`match.group`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _first_match(pattern: re.Pattern[str], text: str) -> str:
    match = pattern.search(text or "")
    return match.group(0).upper() if match else ""


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_clamp_score` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 接待话术、卷宗补丁、受理建议、证据室。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _clamp_score(value: Any) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return max(0, min(100, number))


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_clamp_confidence` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 本文件的 `_default_respondent_attitude`；下游为 接待话术、卷宗补丁、受理建议、证据室。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _clamp_confidence(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, number))


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_human_field_label` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`strip`、`normalized.lower`、`re.search`。
# 上下游：上游为 本文件的 `_human_missing_fields`、`_question_for_missing`；下游为 协作调用 `strip`、`normalized.lower`、`re.search`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _human_field_label(field: str) -> str:
    if field in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[field]
    normalized = str(field or "").strip()
    if normalized in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[normalized]
    lower = normalized.lower()
    if lower in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[lower]
    if re.search(r"[A-Za-z_]{3,}", normalized):
        return "相关补充材料"
    return normalized or "相关补充材料"


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_human_missing_fields` 围绕本阶段状态计算该函数独立负责的业务派生值。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_human_field_label`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _human_missing_fields(missing: list[str]) -> list[str]:
    return [_human_field_label(field) for field in missing]


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_humanize_internal_tokens` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`FIELD_DISPLAY_LABELS.items`、`output.replace`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `FIELD_DISPLAY_LABELS.items`、`output.replace`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _humanize_internal_tokens(text: str) -> str:
    output = text
    for token, label in sorted(
        FIELD_DISPLAY_LABELS.items(), key=lambda item: len(item[0]), reverse=True
    ):
        output = output.replace(token, label)
    return output


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_list_values` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`value.strip`、`strip`。
# 上下游：上游为 本文件的 `_normalize_next_verification_focus`；下游为 协作调用 `value.strip`、`strip`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _list_values(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item or "").strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_is_evidence_material_request` 判断当前可见证据是否满足当前业务分支条件；关键协作调用：`strip`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `strip`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _is_evidence_material_request(value: Any) -> bool:
    text = str(value or "").strip()
    if not text:
        return False
    if any(
        marker in text
        for marker in (
            "截图",
            "照片",
            "视频",
            "聊天记录",
            "沟通记录",
            "录音",
            "凭证",
            "证明材料",
            "证据材料",
            "上传",
            "补交",
            "提供证据",
            "提供材料",
            "提交材料",
        )
    ):
        return True
    evidence_actions = ("提供", "提交", "出示", "发送", "发来", "附上")
    evidence_objects = (
        "检测报告",
        "检验报告",
        "发票",
        "交易流水",
        "支付流水",
        "快递底单",
        "签收单",
        "文件",
    )
    return any(action in text for action in evidence_actions) and any(
        evidence_object in text for evidence_object in evidence_objects
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_question_for_missing` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`join`、`questions.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_human_field_label`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _question_for_missing(missing: list[str]) -> str:
    questions = {
        "ORDER_REFERENCE": "请补充订单号或平台可识别的订单引用。",
        "LOGISTICS_REFERENCE": "请补充物流单号或平台可识别的物流引用。",
    }
    return " ".join(
        questions.get(field, f"请补充{_human_field_label(field)}。")
        for field in missing
    )
