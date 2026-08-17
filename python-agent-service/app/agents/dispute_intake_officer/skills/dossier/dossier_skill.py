# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

import copy
import math
import re
from difflib import SequenceMatcher
from dataclasses import dataclass
from typing import Any, Literal

from app.schemas import IntakeTurnRequest
from app.schemas.intake_case_matrix import UnilateralCaseMatrixDraftV1
from app.schemas.case_fact_matrix import CaseFactMatrixDeltaV2
from app.agents.dispute_intake_officer.case_fact_matrix import (
    finalize_case_fact_matrix,
)
from app.contracts.v1.codec import canonical_sha256
from app.llm import AgentOutputSchemaError


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
RESPONDENT_AUTHORED_CURRENT_MESSAGE = "RESPONDENT_AUTHORED_CURRENT_MESSAGE"
DIRECT_RESPONDENT_CONFIDENCE = 0.65
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
_SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES = frozenset(
    {
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
    }
)
_RESPONDENT_ATTITUDE_SOURCE_IDENTIFIER = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
)
_DIRECT_ATTITUDE_CLAUSE_BOUNDARY = re.compile(
    r"[。！？!?；;，,\n]+|\b(?:but|however|nevertheless|yet)\b",
    re.IGNORECASE,
)
_DIRECT_ATTITUDE_COORDINATION_BOUNDARY = re.compile(r"[；;，,]+")
_DIRECT_ATTITUDE_HISTORICAL_SCOPE_ZH = re.compile(
    r"(?:最初|起初|此前|之前|原先|先前|曾经|原本|一开始|早先)"
)
_DIRECT_ATTITUDE_CURRENT_SCOPE_ZH = re.compile(
    r"^\s*(?:(?:本公司|本人|我方|我们|本方|我司|我|用户|买家|客户|消费者|"
    r"商家|卖家|店铺|商户|客服)\s*)?(?:现阶段|现在|目前|当前|如今|现)"
)
_DIRECT_ATTITUDE_CONDITIONAL_SCOPE_ZH = re.compile(
    r"(?:^|\s)(?:如|若|如果|倘若|一旦|只要|只有|经|待)"
    r"|(?:在|于).{0,32}(?:条件下|情况下|确认后|检测后|完成后)"
    r"|(?:条件|要求|标准).{0,12}(?:满足|符合|达成|成立)(?:时|后|则)?"
    r"|(?:无法|不能|未能).{0,16}(?:时|则|的情况下)"
    r"|(?:否则|反之)"
)
_DIRECT_ATTITUDE_REMEDY_ACTION_ZH = re.compile(
    r"延长保修|更换|换货|退款|退货|维修|补偿|赔偿|退还|延保|补发|重发"
)
_DIRECT_ATTITUDE_INVESTIGATION_ACTION_ZH = re.compile(r"送检|检测|核查|调查|核验|验证|排查|查验")
_DIRECT_ATTITUDE_INVESTIGATION_AGREEMENT_ZH = re.compile(
    rf"(?:同意|接受|愿意|支持).{{0,12}}"
    rf"(?:{_DIRECT_ATTITUDE_INVESTIGATION_ACTION_ZH.pattern})"
)
_DIRECT_ATTITUDE_CONDITIONAL_REMEDY_COMMITMENT_ZH = re.compile(
    rf"(?:则|就|将|会|可|可以|愿意|同意|接受).{{0,12}}"
    rf"(?:{_DIRECT_ATTITUDE_REMEDY_ACTION_ZH.pattern})"
)
_DIRECT_ATTITUDE_REMEDY_SCOPE_ZH = {
    "更换": "EXCHANGE",
    "换货": "EXCHANGE",
    "退款": "REFUND",
    "退还": "REFUND",
    "退货": "RETURN",
    "维修": "REPAIR",
    "补偿": "COMPENSATION",
    "赔偿": "COMPENSATION",
    "延长保修": "WARRANTY_EXTENSION",
    "延保": "WARRANTY_EXTENSION",
    "补发": "RESHIP",
    "重发": "RESHIP",
}
_DIRECT_ATTITUDE_SIGNAL_EN = re.compile(
    r"\b(?:partially\s+(?:agree|agreed|accept|accepted)|agree|agreed|agrees|accept|"
    r"accepted|accepts|reject|rejected|rejects|refuse|refused|refuses|disagree|"
    r"disagreed|disagrees|offer|offered|offers|propose|proposed|proposes|suggest|"
    r"suggested|suggests|need\s+more\s+information|"
    r"request\s+more\s+information)\b",
    re.IGNORECASE,
)
_DIRECT_ATTITUDE_SELF_EN = re.compile(
    r"^\s*(?:i|we|our\s+(?:company|side|firm|business|organization))\b(?P<body>.*)$",
    re.IGNORECASE,
)
_DIRECT_ATTITUDE_THIRD_PARTY_EN = (
    r"(?:buyer|customer|consumer|merchant|seller|store|counterparty)"
)
_DIRECT_ATTITUDE_THIRD_PARTY_PROPOSAL_OBJECT_EN = re.compile(
    rf"\b(?:the\s+)?{_DIRECT_ATTITUDE_THIRD_PARTY_EN}(?:['’]s)\s*"
    r"(?:proposed|suggested|offered)\b"
    rf"|\b(?:proposed|suggested|offered)\s+by\s+(?:the\s+)?"
    rf"{_DIRECT_ATTITUDE_THIRD_PARTY_EN}\b",
    re.IGNORECASE,
)
_DIRECT_ATTITUDE_SIGNAL_ZH = re.compile(
    r"部分(?:同意|接受)|同意|接受|拒绝|不同意|不支持|不接受|愿意|"
    r"提出|建议|替代方案|要求补充|需要更多信息|未表态|没有表态"
)
_DIRECT_ATTITUDE_SELF_ZH = re.compile(
    r"^\s*(?:本公司|本人|我方|我们|本方|我司|我)(?P<body>.*)$"
)
_DIRECT_ATTITUDE_ROLE_SELF_ZH = {
    "USER": re.compile(r"^\s*(?:用户|买家|客户|消费者)(?P<body>.*)$"),
    "MERCHANT": re.compile(r"^\s*(?:商家|卖家|店铺|商户|客服)(?P<body>.*)$"),
}
_DIRECT_ATTITUDE_THIRD_PARTY_ZH = (
    r"(?:平台客服|用户|买家|客户|消费者|对方|商家|卖家|店铺|商户|客服|平台|第三方)"
)
_DIRECT_ATTITUDE_ATTRIBUTION_ZH = re.compile(
    rf"(?:的是|由)\s*{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}"
    rf"|(?:据|按|根据)\s*{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}(?:的)?\s*"
    r"(?:说法|意见|观点|立场|态度|回复|回应|答复|方案|建议)"
    rf"|{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}.{{0,12}}"
    r"(?:说|表示|回复|回应|答复|声称|称|认为|提出|建议|"
    r"部分(?:同意|接受)|同意|接受|拒绝|不同意|不支持|不接受|愿意|"
    r"要求补充|需要更多信息|未表态|没有表态)"
)
_DIRECT_ATTITUDE_THIRD_PARTY_TOPIC_ZH = re.compile(
    rf"{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}.{{0,16}}"
    r"(?:处理意见|意见|立场|方案|建议|说法).{0,8}(?:如下|是|为|包括)\s*$"
)
_DIRECT_ATTITUDE_THIRD_PARTY_PROPOSAL_OBJECT_ZH = re.compile(
    rf"(?:由\s*)?{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}\s*(?:所\s*)?"
    r"(?:(?:提出|建议)的|的)(?P<object>"
    r"[^，,。！？!?；;]{0,24}(?:请求|诉求|方案|建议|要求|主张))"
)
_DIRECT_ATTITUDE_DEFERRED_ATTRIBUTION_ZH = re.compile(
    rf"[（(]\s*(?:以上|上述|前述|该内容|这些内容)?\s*(?:为|是|属于)?\s*"
    rf"{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}\s*(?:的)?\s*"
    r"(?:意见|观点|立场|态度|看法)\s*[）)]"
    rf"|(?:以上|上述|前述|该内容|这些内容|以上内容|上述内容)\s*"
    rf"(?:为|是|属于)\s*{_DIRECT_ATTITUDE_THIRD_PARTY_ZH}\s*(?:的)?\s*"
    r"(?:意见|观点|立场|态度|看法)"
)
_DIRECT_ATTITUDE_ATTRIBUTION_EN = re.compile(
    r"\b(?:the\s+)?(?:buyer|customer|consumer|merchant|seller|store|counterparty)\b"
    r".{0,24}\b(?:agree|agreed|accept|accepted|reject|rejected|refuse|refused|"
    r"disagree|disagreed|offer|offered|propose|proposed|suggest|suggested)\b"
    r"|\b(?:agree|agreed|accept|accepted|reject|rejected|refuse|refused|"
    r"disagree|disagreed|offer|offered|propose|proposed|suggest|suggested)\b"
    r".{0,12}\bby\s+(?:the\s+)?"
    r"(?:buyer|customer|consumer|merchant|seller|store|counterparty)\b",
    re.IGNORECASE,
)
_DIRECT_ATTITUDE_DEFERRED_ATTRIBUTION_EN = re.compile(
    r"\(\s*(?:the\s+)?(?:counterparty|buyer|customer|merchant|seller)(?:'s)?\s+"
    r"(?:opinion|view|position|attitude)\s*\)"
    r"|\b(?:the\s+)?(?:above|foregoing|preceding)\s+"
    r"(?:is|was|reflects)\s+(?:the\s+)?"
    r"(?:counterparty|buyer|customer|merchant|seller)(?:'s)?\s+"
    r"(?:opinion|view|position|attitude)\b",
    re.IGNORECASE,
)
_REPORTED_RESPONDENT_ATTITUDE_TERM_EN = (
    r"partially\s+(?:agreed|accepted)|agreed|accepted|rejected|refused|disagreed|"
    r"offered|proposed"
)
_REPORTED_RESPONDENT_ATTITUDE_MODIFIER_EN = (
    r"has|have|had|explicitly|clearly|already|also|firmly|directly|"
    r"previously|now"
)
_REPORTED_ATTITUDE_NEGATION_EN = re.compile(
    r"\b(?:not|never|no\s+longer|without|hardly)\b|n['’]t\b",
    re.IGNORECASE,
)
_REPORTED_ATTITUDE_POST_NEGATION_EN = re.compile(
    r"\b(?:no|none|nothing|neither|not|never|without)\b|n['’]t\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class DirectRespondentAttitudeDetection:
    state: Literal["NONE", "SUBSTANTIVE", "UNRESOLVED"]
    candidate: dict[str, Any] | None = None


@dataclass(frozen=True, slots=True)
class _IntakeQualityAuthority:
    order_reference: bool
    after_sales_reference: bool
    logistics_reference: bool
    authoritative_story: bool
    authoritative_event: bool
    initiator_position: bool
    respondent_state: bool
    requested_resolution: bool
    normalized_request: bool
    conflict_type: bool
    core_conflict: bool
    action_target: bool
    action_path: bool


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
        "party_intake_state",
        "case_fact_matrix",
        "handoff_remark_partition",
        "unilateral_case_matrix",
    }
)
CASE_DETAIL_MAX_DEPTH = 12
CASE_DETAIL_MAX_NODES = 5_000
CASE_DETAIL_MAX_TEXT_CHARACTERS = 200_000

# The quality score is persisted authority, so its six prompt-defined components
# have fixed maxima and are derived from the normalized dossier rather than model
# supplied score, breakdown, threshold, or ready fields.
_QUALITY_SCORE_COMPONENT_MAXIMA = {
    "references": 15,
    "event_story": 20,
    "party_positions": 20,
    "requested_resolution": 15,
    "risk_and_conflicts": 15,
    "next_action_clarity": 15,
}
PARTY_INTAKE_STATE_SCHEMA_VERSION = "party-intake-state.v1"
_PARTY_INTAKE_ROLES = ("USER", "MERCHANT")
_PARTY_INTAKE_ENTRY_FIELDS = frozenset(
    {"intake_quality", "missing_information", "handoff_notes", "admission"}
)
_PARTY_INTAKE_REMARK_STATUSES = frozenset(
    {
        "NOT_READY",
        "READY_PENDING_REMARK_INVITE",
        "WAITING_FOR_REMARK",
        "HAS_REMARKS",
        "NO_EXTRA_REMARKS",
    }
)
_PARTY_INTAKE_READY_REMARK_STATUSES = _PARTY_INTAKE_REMARK_STATUSES - {
    "NOT_READY"
}
_INTAKE_CONVERSATION_ACTIONS = frozenset(
    {
        "ASK_SUBSTANTIVE",
        "INVITE_OPTIONAL_REMARK",
        "ACK_REMARK",
        "ACK_NO_REMARK",
    }
)
_INTAKE_REMARK_ACK_ACTIONS = frozenset({"ACK_REMARK", "ACK_NO_REMARK"})
_HANDOFF_REMARK_PARTITION_SCHEMA_VERSION = "handoff_remark_partition.v1"
_HANDOFF_REMARK_PARTITION_FIELDS = frozenset(
    {
        "schema_version",
        "case_fact_matrix_id",
        "case_fact_matrix_version",
        "case_fact_matrix_hash",
        "parties",
    }
)
_HANDOFF_REMARK_PARTY_BASE_FIELDS = frozenset(
    {"party_role", "remark_status", "latest_remark", "remarks"}
)
_ROOM_MESSAGE_REMARK_SOURCE_FIELDS = frozenset(
    {"source_kind", "message_id", "message_hash"}
)
_FORMAL_CONFIRMATION_REMARK_SOURCE_FIELDS = frozenset(
    {"source_kind", "command_id", "request_hash"}
)
_HANDOFF_REMARK_ENTRY_FIELDS = frozenset(
    {
        "party_role",
        "text",
        "source_message_id",
        "source_message_hash",
        "turn_source",
    }
)
_SHA256_HEX_RE = re.compile(r"^[0-9a-f]{64}$")
_PARTY_INTAKE_RECOMMENDATIONS = frozenset(
    {"NEED_MORE_INFO", "ACCEPTED", "NOT_ADMISSIBLE"}
)
_PARTY_INTAKE_STATE_FIELDS = frozenset(
    {"schema_version", *_PARTY_INTAKE_ROLES}
)
_QUALITY_UNKNOWN_CODES = frozenset(
    {
        "",
        "UNKNOWN",
        "UNSPECIFIED",
        "NOT_PROVIDED",
        "PLATFORM_UNKNOWN",
        "NONE",
        "N/A",
        "NA",
        "TBD",
    }
)
_QUALITY_METADATA_KEYS = frozenset(
    {"id", "message_id", "schema_version", "source", "time_hint", "type"}
)
_QUALITY_CONFLICT_TYPES = frozenset(
    {
        "CLAIM_UNANSWERED",
        "CLAIM_ACCEPTED_PENDING_VERIFICATION",
        "CLAIM_PARTIALLY_ACCEPTED",
        "CLAIM_REJECTED_WITH_FACT_DISPUTE",
        "CLAIM_WITH_EVIDENCE_GAP",
    }
)

FIELD_DISPLAY_LABELS = {
    "CURRENT_PARTY_STATEMENT": "当前参与方对案情的直接说明",
    "ORDER_REFERENCE": "订单号",
    "AFTER_SALES_REFERENCE": "售后单号",
    "LOGISTICS_REFERENCE": "物流单号",
    "REQUESTED_RESOLUTION": "明确的处理诉求",
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
        conversation_action: str,
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
        if conversation_action not in _INTAKE_CONVERSATION_ACTIONS:
            raise _party_intake_state_error(
                "INTAKE_CONVERSATION_ACTION_INVALID",
                "conversation_action is not supported by the Intake reducer",
            )
        if llm_case_matrix_delta is not None and llm_unilateral_case_matrix is not None:
            raise ValueError("provide only llm_case_matrix_delta")
        effective_matrix_delta = llm_case_matrix_delta or llm_unilateral_case_matrix
        if llm_case_detail is None and llm_scroll_snapshot:
            llm_case_detail = llm_scroll_snapshot

        previous = _case_detail_fields_only(request.previous_case_detail or {})
        actor_role = _require_party_actor_role(request.agent_context.actor_role)
        current_message = request.current_user_message
        if (
            current_message is not None
            and str(current_message.role or "").upper() != actor_role
        ):
            raise _party_intake_state_error(
                "INTAKE_PARTY_STATE_CURRENT_MESSAGE_ACTOR_MISMATCH",
                "current Intake message role does not match the authenticated actor",
            )
        has_current_actor_answer = (
            current_message is not None and _quality_text(current_message.text)
        )
        current_message_id = (
            str(current_message.message_id or "").strip()
            if has_current_actor_answer
            else ""
        )
        initiator_role = _proven_initiator_role(request, previous)
        party_intake_state = _party_intake_state_for_turn(
            previous,
            actor_role=actor_role,
            initiator_role=initiator_role,
            current_message_id=current_message_id,
        )
        bounded_llm_case_detail = _case_detail_fields_only(llm_case_detail or {})
        proposed_party_state = bounded_llm_case_detail.pop("party_intake_state", None)
        proposed_remark_partition = bounded_llm_case_detail.pop(
            "handoff_remark_partition",
            None,
        )
        previous_actor_entry = copy.deepcopy(party_intake_state[actor_role])
        previous_remark_status = _handoff_remark_status(previous_actor_entry)
        previous_remark_partition = _validated_handoff_remark_partition(
            previous.get("handoff_remark_partition"),
            matrix=previous.get("case_fact_matrix"),
            source="persisted handoff_remark_partition",
            allow_missing=True,
        )
        if previous_remark_partition is None and any(
            _handoff_remark_status(party_intake_state[role]) != "NOT_READY"
            for role in _PARTY_INTAKE_ROLES
        ):
            if not isinstance(previous.get("case_fact_matrix"), dict):
                raise _party_intake_state_error(
                    "INTAKE_HANDOFF_REMARK_PARTITION_REQUIRED",
                    "ready Intake authority requires the persisted handoff remark partition",
                )
            previous_remark_partition = _legacy_handoff_remark_partition(
                matrix=previous["case_fact_matrix"],
                party_intake_state=party_intake_state,
                request=request,
            )
            previous["handoff_remark_partition"] = copy.deepcopy(
                previous_remark_partition
            )
        previous_actor_partition = (
            previous_remark_partition["parties"][actor_role]
            if previous_remark_partition is not None
            else None
        )
        previous_partition_status = (
            str(previous_actor_partition["remark_status"])
            if previous_actor_partition is not None
            else previous_remark_status
        )
        if (
            previous_actor_partition is not None
            and previous_partition_status != previous_remark_status
        ):
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_AUTHORITY_CONFLICT",
                "persisted handoff partition and party Intake state disagree",
            )
        actor_substantive_frozen = previous_partition_status != "NOT_READY"
        if actor_substantive_frozen:
            return _render_frozen_handoff_remark_turn(
                request=request,
                conversation_action=conversation_action,
                room_utterance=room_utterance,
                previous=previous,
                party_intake_state=party_intake_state,
                actor_role=actor_role,
                previous_actor_entry=previous_actor_entry,
                previous_partition=previous_remark_partition,
                effective_matrix_delta=effective_matrix_delta,
                llm_case_detail=bounded_llm_case_detail,
                llm_admission_recommendation=llm_admission_recommendation,
                llm_missing_fields=llm_missing_fields,
                llm_confidence=llm_confidence,
            )
        if proposed_remark_partition is not None:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_MODEL_AUTHORITY_FORBIDDEN",
                "model output cannot create handoff remark authority",
            )
        if proposed_party_state is not None:
            validated_proposal = _validated_party_intake_state(
                proposed_party_state,
                source="model party_intake_state",
            )
            if validated_proposal != party_intake_state:
                raise _party_intake_state_error(
                    "INTAKE_PARTY_STATE_MODEL_AUTHORITY_FORBIDDEN",
                    "model output cannot create or advance party-scoped Intake authority",
                )
        if conversation_action == "ACK_REMARK":
            raise _party_intake_state_error(
                "INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT",
                "an actual remark requires previously frozen substantive authority",
            )
        combined_no_remark = conversation_action == "ACK_NO_REMARK"
        if combined_no_remark and effective_matrix_delta is None:
            raise _party_intake_state_error(
                "INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT",
                "a same-turn no-remark acknowledgement requires a substantive matrix delta",
            )
        previous_phase_source_message_id = str(
            previous_actor_entry["handoff_notes"].get("phase_source_message_id") or ""
        )
        detail = self._default_case_detail(request)
        detail = _deep_merge(detail, previous if _is_case_detail(previous) else {})
        _set_party_intake_mirror(detail, previous_actor_entry)
        detail = _deep_merge(detail, bounded_llm_case_detail)
        _restore_party_handoff_authority(detail, previous_actor_entry)
        detail["schema_version"] = self.schema_version
        _enforce_claim_resolution(detail, request, previous)
        _enforce_party_position_voice(detail)
        _enforce_respondent_attitude_source(
            detail,
            request,
            previous,
            bounded_llm_case_detail,
            effective_matrix_delta,
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
        actor_source_records = _authoritative_intake_source_records(
            request,
            initiator_role=initiator_role,
        )
        if not actor_source_records and "CURRENT_PARTY_STATEMENT" not in missing:
            missing.append("CURRENT_PARTY_STATEMENT")
        claim = _quality_mapping(detail.get("claim_resolution"))
        actor_is_initiator = (
            initiator_role is not None and actor_role == initiator_role
        )
        if actor_is_initiator:
            resolution_authorized = (
                _known_resolution_code(claim.get("requested_resolution")) is not None
            )
        else:
            resolution_authorized = _respondent_resolution_authorized(
                detail,
                actor_role=actor_role,
                initiator_role=initiator_role,
            )
        if not resolution_authorized and "REQUESTED_RESOLUTION" not in missing:
            missing.append("REQUESTED_RESOLUTION")
        missing_info["blocking_gaps"] = _human_missing_fields(missing)
        _normalize_next_verification_focus(detail)
        score_breakdown = _derive_intake_quality_breakdown(
            detail,
            request=request,
            missing=missing,
            initiator_role=initiator_role,
            actor_source_records=actor_source_records,
        )
        score = sum(score_breakdown.values())
        quality = _ensure_dict(detail, "intake_quality")
        quality["score_breakdown"] = score_breakdown
        quality["score"] = score
        quality["threshold"] = self.readiness_threshold
        threshold_reached = score >= self.readiness_threshold and not missing
        phase_has_authority = (
            previous_remark_status != "NOT_READY" or has_current_actor_answer
        )
        quality["ready_for_next_step"] = threshold_reached and phase_has_authority
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

        actor_remark_status = "NOT_READY"
        if quality["ready_for_next_step"]:
            if conversation_action not in {
                "INVITE_OPTIONAL_REMARK",
                "ACK_NO_REMARK",
            }:
                raise _party_intake_state_error(
                    "INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT",
                    "the first ready turn must invite the optional remark or acknowledge an explicit same-turn no-remark decision",
                )
            if not current_message_id:
                raise _party_intake_state_error(
                    "INTAKE_HANDOFF_REMARK_SOURCE_REQUIRED",
                    "the first ready turn requires an authenticated participant message",
                )
            missing_info["next_questions"] = []
            actor_remark_status = (
                "NO_EXTRA_REMARKS"
                if combined_no_remark
                else "WAITING_FOR_REMARK"
            )
            notes = _ensure_handoff_notes(
                detail,
                remark_status=actor_remark_status,
                phase_source_message_id=current_message_id,
            )
            notes["latest_remark"] = (
                "无额外备注。" if combined_no_remark else ""
            )
            notes["remarks"] = []
        else:
            if conversation_action != "ASK_SUBSTANTIVE":
                raise _party_intake_state_error(
                    "INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT",
                    "an incomplete Intake turn must ask substantive questions",
                )
            phase_source_message_id = previous_phase_source_message_id
            if previous_remark_status != "NOT_READY" and current_message_id:
                phase_source_message_id = current_message_id
            notes = _ensure_handoff_notes(
                detail,
                remark_status="NOT_READY",
                phase_source_message_id=phase_source_message_id,
            )
            notes["latest_remark"] = ""
            notes["remarks"] = []
            if not missing_info.get("next_questions"):
                question = _question_for_missing(missing) or _question_for_quality_gap(
                    score_breakdown
                )
                missing_info["next_questions"] = [question]
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
        admission["reasoning"] = str(admission.get("reasoning") or "")

        current_actor_entry = _canonical_party_intake_entry(
            detail,
            role=actor_role,
        )
        party_intake_state[actor_role] = current_actor_entry
        detail["party_intake_state"] = copy.deepcopy(party_intake_state)
        _set_party_intake_mirror(detail, current_actor_entry)

        detail["case_fact_matrix"] = finalize_case_fact_matrix(
            request=request,
            case_detail=detail,
            delta=effective_matrix_delta,
        ).model_dump(mode="json")
        detail.pop("unilateral_case_matrix", None)
        if quality["ready_for_next_step"]:
            detail["handoff_remark_partition"] = _initial_handoff_remark_partition(
                matrix=detail["case_fact_matrix"],
                party_intake_state=party_intake_state,
                previous_partition=previous_remark_partition,
                actor_role=actor_role,
                actor_status=actor_remark_status,
                actor_source=_room_message_remark_source(
                    message_id=current_message_id,
                    role=actor_role,
                    text=str(current_message.text),
                ),
            )
        else:
            # Before the threshold there is no frozen matrix to which a remark
            # partition can bind.  Creating one here would make the next
            # substantive matrix revision look like an authority rebind.
            detail.pop("handoff_remark_partition", None)

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
    # 具体功能：`_trusted_references` 围绕业务引用号核对可信业务引用。
    # 上下游：上游为 Intake 请求；下游为确定性卷宗归并。
    # 系统意义：只接受表单、既有权威或参与方原文中的业务引用。
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

    @staticmethod
    def _hard_missing_fields(trusted_refs: dict[str, str]) -> list[str]:
        missing: list[str] = []
        if not trusted_refs.get("order_reference"):
            missing.append("ORDER_REFERENCE")
        return missing

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
                "user_claim": source_text if initiator_role == "USER" else "",
                "merchant_claim": source_text if initiator_role == "MERCHANT" else "",
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
            "claim_resolution": _default_claim_resolution(initial, source_text),
            "respondent_attitude": _default_respondent_attitude(initial),
            "dispute_core_state": _default_dispute_core_state(initial, source_text),
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


def _render_frozen_handoff_remark_turn(
    *,
    request: IntakeTurnRequest,
    conversation_action: str,
    room_utterance: str,
    previous: dict[str, Any],
    party_intake_state: dict[str, Any],
    actor_role: str,
    previous_actor_entry: dict[str, Any],
    previous_partition: dict[str, Any] | None,
    effective_matrix_delta: CaseFactMatrixDeltaV2 | UnilateralCaseMatrixDraftV1 | None,
    llm_case_detail: dict[str, Any],
    llm_admission_recommendation: str,
    llm_missing_fields: list[str],
    llm_confidence: float,
) -> DossierRenderResult:
    if conversation_action not in _INTAKE_REMARK_ACK_ACTIONS:
        raise _party_intake_state_error(
            "INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT",
            "frozen substantive authority accepts only a remark acknowledgement",
        )
    if effective_matrix_delta is not None:
        # Matrix material from a post-threshold model output is non-authoritative;
        # retain the exact frozen matrix instead of deriving another version.
        effective_matrix_delta = None
    # Model-authored core/matrix material is non-authoritative once the threshold
    # snapshot is frozen. It is intentionally ignored; only the structured
    # conversation action and authenticated participant message may advance the
    # remark partition.
    del llm_case_detail, llm_admission_recommendation, llm_missing_fields
    if not isinstance(previous.get("case_fact_matrix"), dict):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_MATRIX_REQUIRED",
            "frozen substantive authority requires a formal case fact matrix",
        )
    current = request.current_user_message
    if (
        current is None
        or request.turn_source != "ROOM_MESSAGE"
        or current.source != "ROOM_MESSAGE"
        or str(current.role or "").upper() != actor_role
        or not str(current.message_id or "").strip()
        or not str(current.text or "").strip()
    ):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_SOURCE_REQUIRED",
            "remark acknowledgement requires an authenticated participant ROOM_MESSAGE",
        )

    if previous_partition is None:
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_PARTITION_REQUIRED",
            "frozen substantive authority requires the handoff remark partition",
        )
    partition = copy.deepcopy(previous_partition)
    current_text = str(current.text)
    current_message_id = str(current.message_id).strip()
    current_message_hash = _room_message_remark_hash(
        message_id=current_message_id,
        role=actor_role,
        text=current_text,
    )
    actor_partition = partition["parties"][actor_role]
    existing_by_source = {
        str(item["source_message_id"]): item for item in actor_partition["remarks"]
    }
    existing = existing_by_source.get(current_message_id)
    source = {
        "source_kind": "ROOM_MESSAGE",
        "message_id": current_message_id,
        "message_hash": current_message_hash,
    }
    notes = copy.deepcopy(previous_actor_entry["handoff_notes"])
    source_replays_phase = (
        str(actor_partition.get("source", {}).get("message_id") or "")
        == current_message_id
    )

    if conversation_action == "ACK_REMARK":
        remark = {
            "party_role": actor_role,
            "text": current_text,
            "source_message_id": current_message_id,
            "source_message_hash": current_message_hash,
            "turn_source": "ROOM_MESSAGE",
        }
        if existing is not None and existing != remark:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_REPLAY_CONFLICT",
                "a remark source message cannot bind changed text or hash",
            )
        if existing is None and source_replays_phase:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_REPLAY_CONFLICT",
                "a phase source message cannot be rebound as a remark",
            )
        if existing is None:
            actor_partition["remarks"].append(remark)
        actor_partition["remark_status"] = "HAS_REMARKS"
        actor_partition["source"] = source
        actor_partition["latest_remark"] = current_text
        notes["remark_status"] = "HAS_REMARKS"
        notes["phase_source_message_id"] = current_message_id
        notes["latest_remark"] = current_text
        compatibility_remark = {
            "role": actor_role,
            "text": current_text,
            "source_message_id": current_message_id,
            "turn_source": "ROOM_MESSAGE",
        }
        compatibility_by_source = {
            str(item.get("source_message_id")): item
            for item in notes["remarks"]
            if isinstance(item, dict)
        }
        prior_compatibility = compatibility_by_source.get(current_message_id)
        if prior_compatibility is not None and prior_compatibility != compatibility_remark:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_REPLAY_CONFLICT",
                "nested handoff replay conflicts with the participant message",
            )
        if prior_compatibility is None:
            notes["remarks"].append(compatibility_remark)
    else:
        if existing is not None:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_REPLAY_CONFLICT",
                "a persisted remark source cannot be rebound as no-remark authority",
            )
        actor_had_remarks = bool(actor_partition["remarks"])
        if source_replays_phase and not actor_had_remarks:
            if (
                actor_partition["remark_status"] != "NO_EXTRA_REMARKS"
                or actor_partition.get("source") != source
                or actor_partition["latest_remark"]
                or actor_partition["remarks"]
            ):
                raise _party_intake_state_error(
                    "INTAKE_HANDOFF_REMARK_REPLAY_CONFLICT",
                    "a no-remark source message cannot bind changed text or hash",
                )
        elif actor_partition["remark_status"] == "NO_EXTRA_REMARKS":
            # Another explicit no-remark turn is a semantic no-op. The existing
            # source remains the authority because NO_EXTRA_REMARKS has no
            # same-status source-rebinding transition.
            pass
        if actor_had_remarks:
            # "No further remarks" after one or more real remarks is an Agent
            # acknowledgement only.  The append-only HAS_REMARKS authority is
            # already complete and must remain byte-for-byte unchanged.
            actor_partition["remark_status"] = "HAS_REMARKS"
            actor_partition["latest_remark"] = str(
                actor_partition["remarks"][-1]["text"]
            )
            notes["remark_status"] = "HAS_REMARKS"
            notes["latest_remark"] = str(notes["remarks"][-1]["text"])
        elif actor_partition["remark_status"] != "NO_EXTRA_REMARKS":
            actor_partition["remark_status"] = "NO_EXTRA_REMARKS"
            actor_partition["latest_remark"] = ""
            actor_partition["remarks"] = []
            actor_partition["source"] = source
            notes["remark_status"] = "NO_EXTRA_REMARKS"
            notes["latest_remark"] = "无额外备注。"
            notes["remarks"] = []
            notes["phase_source_message_id"] = current_message_id

    detail = copy.deepcopy(previous)
    detail["handoff_remark_partition"] = _validated_handoff_remark_partition(
        partition,
        matrix=detail["case_fact_matrix"],
        source="derived handoff_remark_partition",
        allow_missing=False,
    )
    current_actor_entry = copy.deepcopy(previous_actor_entry)
    current_actor_entry["handoff_notes"] = notes
    party_intake_state[actor_role] = _validated_party_intake_entry(
        current_actor_entry,
        role=actor_role,
        source="derived frozen current actor entry",
    )
    detail["party_intake_state"] = copy.deepcopy(party_intake_state)
    _set_party_intake_mirror(detail, party_intake_state[actor_role])
    score = int(party_intake_state[actor_role]["intake_quality"]["score"])
    confidence = _clamp_confidence(
        party_intake_state[actor_role]["admission"].get(
            "confidence",
            llm_confidence,
        )
    )
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
        admission_recommendation="ACCEPTED",
        missing_fields=[],
        confidence=confidence,
    )


def _matrix_remark_partition_binding(matrix: Any) -> dict[str, Any]:
    if not isinstance(matrix, dict):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_MATRIX_REQUIRED",
            "handoff remark authority requires a case fact matrix",
        )
    matrix_id = matrix.get("matrix_id")
    matrix_version = matrix.get("matrix_version")
    content_hash = matrix.get("content_hash")
    if (
        not isinstance(matrix_id, str)
        or not matrix_id
        or type(matrix_version) is not int
        or matrix_version < 1
        or not isinstance(content_hash, str)
        or _SHA256_HEX_RE.fullmatch(content_hash) is None
    ):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_MATRIX_INVALID",
            "handoff remark matrix binding is malformed",
        )
    return {
        "case_fact_matrix_id": matrix_id,
        "case_fact_matrix_version": matrix_version,
        "case_fact_matrix_hash": content_hash,
    }


def _room_message_remark_hash(*, message_id: str, role: str, text: str) -> str:
    return canonical_sha256(
        {
            "message_id": message_id,
            "role": role,
            "source": "ROOM_MESSAGE",
            "text": text,
        }
    )


def _room_message_remark_source(
    *,
    message_id: str,
    role: str,
    text: str,
) -> dict[str, str]:
    normalized_id = str(message_id or "").strip()
    normalized_role = _require_party_actor_role(role)
    normalized_text = str(text or "")
    if not normalized_id or not normalized_text.strip():
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_SOURCE_REQUIRED",
            "ROOM_MESSAGE remark authority requires message id and text",
        )
    return {
        "source_kind": "ROOM_MESSAGE",
        "message_id": normalized_id,
        "message_hash": _room_message_remark_hash(
            message_id=normalized_id,
            role=normalized_role,
            text=normalized_text,
        ),
    }


def _default_handoff_remark_party(role: str) -> dict[str, Any]:
    return {
        "party_role": _require_party_actor_role(role),
        "remark_status": "NOT_READY",
        "latest_remark": "",
        "remarks": [],
    }


def _legacy_handoff_remark_partition(
    *,
    matrix: dict[str, Any],
    party_intake_state: dict[str, Any],
    request: IntakeTurnRequest,
) -> dict[str, Any]:
    parties: dict[str, dict[str, Any]] = {}
    for role in _PARTY_INTAKE_ROLES:
        entry = party_intake_state[role]
        notes = entry["handoff_notes"]
        status = str(notes["remark_status"])
        party = _default_handoff_remark_party(role)
        if status == "NOT_READY":
            parties[role] = party
            continue
        source_message_id = str(notes["phase_source_message_id"] or "").strip()
        source_text = _participant_message_text_for_source(
            request,
            message_id=source_message_id,
            role=role,
        )
        party["remark_status"] = status
        party["source"] = _room_message_remark_source(
            message_id=source_message_id,
            role=role,
            text=source_text,
        )
        if status == "HAS_REMARKS":
            remarks = []
            for legacy_remark in notes["remarks"]:
                remark_id = str(legacy_remark["source_message_id"])
                remark_text = str(legacy_remark["text"])
                remarks.append(
                    {
                        "party_role": role,
                        "text": remark_text,
                        "source_message_id": remark_id,
                        "source_message_hash": _room_message_remark_hash(
                            message_id=remark_id,
                            role=role,
                            text=remark_text,
                        ),
                        "turn_source": "ROOM_MESSAGE",
                    }
                )
            party["latest_remark"] = str(notes["latest_remark"])
            party["remarks"] = remarks
        elif status == "NO_EXTRA_REMARKS":
            party["latest_remark"] = ""
            party["remarks"] = []
        parties[role] = party
    upgraded = _validated_handoff_remark_partition(
        {
            "schema_version": _HANDOFF_REMARK_PARTITION_SCHEMA_VERSION,
            **_matrix_remark_partition_binding(matrix),
            "parties": parties,
        },
        matrix=matrix,
        source="legacy handoff_remark_partition upgrade",
        allow_missing=False,
    )
    if upgraded is None:
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_PARTITION_INVALID",
            "legacy handoff remark partition upgrade produced no authority",
        )
    return upgraded


def _participant_message_text_for_source(
    request: IntakeTurnRequest,
    *,
    message_id: str,
    role: str,
) -> str:
    messages = [
        *request.initiator_statement_transcript,
        *request.recent_dialogue_messages,
    ]
    if request.current_user_message is not None:
        messages.append(request.current_user_message)
    for message in messages:
        if (
            getattr(message, "message_id", None) == message_id
            and getattr(message, "role", None) == role
        ):
            return str(message.text)
    raise _party_intake_state_error(
        "INTAKE_HANDOFF_REMARK_SOURCE_UNAVAILABLE",
        "legacy ready authority cannot be upgraded without its participant message",
    )


def _initial_handoff_remark_partition(
    *,
    matrix: dict[str, Any],
    party_intake_state: dict[str, Any],
    previous_partition: dict[str, Any] | None,
    actor_role: str,
    actor_status: str,
    actor_source: dict[str, str] | None,
) -> dict[str, Any]:
    actor = _require_party_actor_role(actor_role)
    if actor_status not in _PARTY_INTAKE_REMARK_STATUSES:
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_STATUS_INVALID",
            "handoff remark status is not supported",
        )
    if previous_partition is not None:
        parties = copy.deepcopy(previous_partition["parties"])
    else:
        parties = {
            role: _default_handoff_remark_party(role) for role in _PARTY_INTAKE_ROLES
        }
    parties[actor]["remark_status"] = actor_status
    if actor_status != "NOT_READY":
        if actor_source is None:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_SOURCE_REQUIRED",
                "non-NOT_READY handoff authority requires a source",
            )
        parties[actor]["source"] = copy.deepcopy(actor_source)
    return {
        "schema_version": _HANDOFF_REMARK_PARTITION_SCHEMA_VERSION,
        **_matrix_remark_partition_binding(matrix),
        "parties": parties,
    }


def _validated_handoff_remark_partition(
    value: Any,
    *,
    matrix: Any,
    source: str,
    allow_missing: bool,
) -> dict[str, Any] | None:
    if value is None and allow_missing:
        return None
    if not isinstance(value, dict) or set(value) != _HANDOFF_REMARK_PARTITION_FIELDS:
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_PARTITION_INVALID",
            f"{source} has an invalid top-level shape",
        )
    if value.get("schema_version") != _HANDOFF_REMARK_PARTITION_SCHEMA_VERSION:
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_PARTITION_INVALID",
            f"{source} has an unsupported schema_version",
        )
    expected_binding = _matrix_remark_partition_binding(matrix)
    if any(value.get(field) != expected for field, expected in expected_binding.items()):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_MATRIX_CONFLICT",
            f"{source} does not bind the adjacent case fact matrix",
        )
    parties = value.get("parties")
    if not isinstance(parties, dict) or set(parties) != set(_PARTY_INTAKE_ROLES):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_PARTITION_INVALID",
            f"{source}.parties must contain exactly USER and MERCHANT",
        )
    validated = {
        "schema_version": _HANDOFF_REMARK_PARTITION_SCHEMA_VERSION,
        **expected_binding,
        "parties": {},
    }
    for role in _PARTY_INTAKE_ROLES:
        party = parties.get(role)
        if not isinstance(party, dict):
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_PARTY_INVALID",
                f"{source}.{role} is malformed",
            )
        status = party.get("remark_status")
        expected_fields = set(_HANDOFF_REMARK_PARTY_BASE_FIELDS)
        if status != "NOT_READY":
            expected_fields.add("source")
        if set(party) != expected_fields or party.get("party_role") != role:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_PARTY_INVALID",
                f"{source}.{role} has an invalid shape or party_role",
            )
        if status not in _PARTY_INTAKE_REMARK_STATUSES:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_STATUS_INVALID",
                f"{source}.{role} has an unsupported remark_status",
            )
        source_value = party.get("source")
        if status != "NOT_READY":
            _validate_handoff_remark_source(source_value, source=f"{source}.{role}.source")
        latest = party.get("latest_remark")
        remarks = party.get("remarks")
        if not isinstance(latest, str) or not isinstance(remarks, list):
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_PARTY_INVALID",
                f"{source}.{role} has malformed remark data",
            )
        seen_ids: set[str] = set()
        for remark in remarks:
            _validate_handoff_remark_entry(remark, role=role, source=source)
            source_id = str(remark["source_message_id"])
            if source_id in seen_ids:
                raise _party_intake_state_error(
                    "INTAKE_HANDOFF_REMARK_REPLAY_CONFLICT",
                    f"{source}.{role} repeats a remark source message",
                )
            seen_ids.add(source_id)
        if status == "HAS_REMARKS":
            canonical_payload = bool(remarks) and latest == remarks[-1]["text"]
        else:
            canonical_payload = not latest and not remarks
        if not canonical_payload:
            raise _party_intake_state_error(
                "INTAKE_HANDOFF_REMARK_STATUS_CONFLICT",
                f"{source}.{role} status and remark payload disagree",
            )
        validated["parties"][role] = copy.deepcopy(party)
    return validated


def _validate_handoff_remark_source(value: Any, *, source: str) -> None:
    if not isinstance(value, dict):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_SOURCE_INVALID",
            f"{source} is malformed",
        )
    source_kind = value.get("source_kind")
    if source_kind == "ROOM_MESSAGE":
        fields = _ROOM_MESSAGE_REMARK_SOURCE_FIELDS
        identifiers = ("message_id", "message_hash")
    elif source_kind == "FORMAL_CONFIRMATION":
        fields = _FORMAL_CONFIRMATION_REMARK_SOURCE_FIELDS
        identifiers = ("command_id", "request_hash")
    else:
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_SOURCE_INVALID",
            f"{source} has an unsupported source_kind",
        )
    if (
        set(value) != fields
        or not isinstance(value.get(identifiers[0]), str)
        or not value[identifiers[0]]
        or not isinstance(value.get(identifiers[1]), str)
        or _SHA256_HEX_RE.fullmatch(value[identifiers[1]]) is None
    ):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_SOURCE_INVALID",
            f"{source} has malformed source identity",
        )


def _validate_handoff_remark_entry(
    value: Any,
    *,
    role: str,
    source: str,
) -> None:
    if (
        not isinstance(value, dict)
        or set(value) != _HANDOFF_REMARK_ENTRY_FIELDS
        or value.get("party_role") != role
        or value.get("turn_source") != "ROOM_MESSAGE"
        or any(
            not isinstance(value.get(field), str) or not value[field]
            for field in (
                "text",
                "source_message_id",
                "source_message_hash",
            )
        )
        or _SHA256_HEX_RE.fullmatch(value["source_message_hash"]) is None
        or value["source_message_hash"]
        != _room_message_remark_hash(
            message_id=value["source_message_id"],
            role=role,
            text=value["text"],
        )
    ):
        raise _party_intake_state_error(
            "INTAKE_HANDOFF_REMARK_ENTRY_INVALID",
            f"{source}.{role} contains a malformed or unauthenticated remark",
        )

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


def _party_intake_state_error(code: str, message: str) -> AgentOutputSchemaError:
    return AgentOutputSchemaError(
        "intake_turn_case_detail",
        message,
        safe_code=code,
    )


def _require_party_actor_role(value: Any) -> str:
    actor_role = str(value or "").upper()
    if actor_role not in _PARTY_INTAKE_ROLES:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_ACTOR_INVALID",
            "party-scoped Intake authority requires an exact USER or MERCHANT actor",
        )
    return actor_role


def _proven_initiator_role(
    request: IntakeTurnRequest,
    previous: dict[str, Any],
) -> str | None:
    candidates: list[str] = []

    initial_role = str(
        getattr(request.initial_case_facts, "initiator_role", None) or ""
    ).upper()
    if initial_role in _PARTY_INTAKE_ROLES:
        candidates.append(initial_role)

    matrix = previous.get("case_fact_matrix") if isinstance(previous, dict) else None
    party_map = matrix.get("party_map") if isinstance(matrix, dict) else None
    if isinstance(party_map, dict):
        matrix_initiator = str(party_map.get("initiator_role") or "").upper()
        matrix_respondent = str(party_map.get("respondent_role") or "").upper()
        if (
            matrix_initiator not in _PARTY_INTAKE_ROLES
            or matrix_respondent not in _PARTY_INTAKE_ROLES
            or matrix_initiator == matrix_respondent
        ):
            raise _party_intake_state_error(
                "INTAKE_PARTY_STATE_ROLE_AUTHORITY_INVALID",
                "formal matrix party authority is malformed",
            )
        candidates.append(matrix_initiator)

    claim = previous.get("claim_resolution") if isinstance(previous, dict) else None
    if isinstance(claim, dict):
        claim_initiator = str(claim.get("initiator_role") or "").upper()
        if claim_initiator in _PARTY_INTAKE_ROLES:
            candidates.append(claim_initiator)

    if not candidates:
        return None
    if any(candidate != candidates[0] for candidate in candidates[1:]):
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_ROLE_AUTHORITY_DRIFT",
            "initiator role authorities disagree",
        )
    return candidates[0]


def _default_party_intake_entry() -> dict[str, Any]:
    return {
        "intake_quality": {
            "score": 0,
            "threshold": CaseDetailDossierSkill.readiness_threshold,
            "ready_for_next_step": False,
            "score_breakdown": {
                component: 0 for component in _QUALITY_SCORE_COMPONENT_MAXIMA
            },
            "improvement_reason": "等待当前参与方补充案情。",
        },
        "missing_information": {
            "blocking_gaps": [],
            "nice_to_have_gaps": [],
            "next_questions": [],
        },
        "handoff_notes": {
            "remark_status": "NOT_READY",
            "phase_source_message_id": "",
            "latest_remark": "",
            "remarks": [],
            "instruction": "当前参与方案情达到阈值后，接待官会询问交接备注。",
        },
        "admission": {
            "recommendation": "NEED_MORE_INFO",
            "reasoning": "",
            "confidence": 0.0,
        },
    }


def _default_party_intake_state() -> dict[str, Any]:
    return {
        "schema_version": PARTY_INTAKE_STATE_SCHEMA_VERSION,
        "USER": _default_party_intake_entry(),
        "MERCHANT": _default_party_intake_entry(),
    }


def _score_breakdown_for_total(score: int) -> dict[str, int]:
    remaining = max(0, min(100, score))
    result: dict[str, int] = {}
    for component, maximum in _QUALITY_SCORE_COMPONENT_MAXIMA.items():
        value = min(maximum, remaining)
        result[component] = value
        remaining -= value
    return result


def _legacy_party_intake_entry(
    previous: dict[str, Any],
    *,
    phase_source_message_id: str = "",
) -> dict[str, Any]:
    entry = _default_party_intake_entry()
    quality = previous.get("intake_quality")
    if isinstance(quality, dict):
        raw_score = quality.get("score")
        score = (
            max(0, min(100, raw_score))
            if type(raw_score) is int
            else 0
        )
        raw_breakdown = quality.get("score_breakdown")
        if (
            isinstance(raw_breakdown, dict)
            and set(raw_breakdown) == set(_QUALITY_SCORE_COMPONENT_MAXIMA)
            and all(
                type(raw_breakdown.get(component)) is int
                and 0 <= raw_breakdown[component] <= maximum
                for component, maximum in _QUALITY_SCORE_COMPONENT_MAXIMA.items()
            )
            and sum(raw_breakdown.values()) == score
        ):
            breakdown = copy.deepcopy(raw_breakdown)
        else:
            breakdown = _score_breakdown_for_total(score)
        ready = quality.get("ready_for_next_step") is True and score >= 85
        entry["intake_quality"] = {
            "score": score,
            "threshold": 85,
            "ready_for_next_step": ready,
            "score_breakdown": breakdown,
            "improvement_reason": str(quality.get("improvement_reason") or ""),
        }

    missing = previous.get("missing_information")
    if isinstance(missing, dict):
        entry["missing_information"] = {
            field: [
                item for item in missing.get(field, []) if isinstance(item, str)
            ]
            if isinstance(missing.get(field, []), list)
            else []
            for field in ("blocking_gaps", "nice_to_have_gaps", "next_questions")
        }

    notes = previous.get("handoff_notes")
    if isinstance(notes, dict):
        status = str(notes.get("remark_status") or "NOT_READY")
        if status not in _PARTY_INTAKE_REMARK_STATUSES:
            status = "NOT_READY"
        remarks = [
            copy.deepcopy(item)
            for item in notes.get("remarks", [])
            if isinstance(item, dict)
            and set(item) == {"role", "text", "source_message_id", "turn_source"}
            and item.get("role") in _PARTY_INTAKE_ROLES
            and all(
                isinstance(item.get(field), str)
                for field in ("text", "source_message_id", "turn_source")
            )
        ] if isinstance(notes.get("remarks", []), list) else []
        entry["handoff_notes"] = {
            "remark_status": status,
            "phase_source_message_id": str(
                notes.get("phase_source_message_id") or ""
            ),
            "latest_remark": str(notes.get("latest_remark") or ""),
            "remarks": remarks,
            "instruction": str(notes.get("instruction") or ""),
        }

    admission = previous.get("admission")
    if isinstance(admission, dict):
        recommendation = str(admission.get("recommendation") or "NEED_MORE_INFO")
        if recommendation not in _PARTY_INTAKE_RECOMMENDATIONS:
            recommendation = "NEED_MORE_INFO"
        confidence = admission.get("confidence")
        confidence_value = (
            float(confidence)
            if type(confidence) in {int, float} and math.isfinite(float(confidence))
            else 0.0
        )
        entry["admission"] = {
            "recommendation": recommendation,
            "reasoning": str(admission.get("reasoning") or ""),
            "confidence": max(0.0, min(1.0, confidence_value)),
        }

    ready = entry["intake_quality"]["ready_for_next_step"]
    if ready:
        entry["admission"]["recommendation"] = "ACCEPTED"
        if entry["handoff_notes"]["remark_status"] == "NOT_READY":
            entry["handoff_notes"]["remark_status"] = (
                "READY_PENDING_REMARK_INVITE"
            )
            if phase_source_message_id:
                entry["handoff_notes"]["phase_source_message_id"] = (
                    phase_source_message_id
                )
    else:
        if entry["admission"]["recommendation"] == "ACCEPTED":
            entry["admission"]["recommendation"] = "NEED_MORE_INFO"
        entry["handoff_notes"]["remark_status"] = "NOT_READY"
        entry["handoff_notes"]["latest_remark"] = ""
        entry["handoff_notes"]["remarks"] = []
    return entry


def _party_intake_state_for_turn(
    previous: dict[str, Any],
    *,
    actor_role: str,
    initiator_role: str | None,
    current_message_id: str,
) -> dict[str, Any]:
    persisted = previous.get("party_intake_state")
    if persisted is not None:
        return _validated_party_intake_state(
            persisted,
            source="persisted party_intake_state",
        )

    state = _default_party_intake_state()
    if initiator_role is not None and actor_role == initiator_role:
        state[actor_role] = _legacy_party_intake_entry(
            previous,
            phase_source_message_id=current_message_id,
        )
    return state


def party_intake_prompt_mirror(
    snapshot: dict[str, Any],
    *,
    actor_role: str,
) -> dict[str, Any]:
    """Return only the trusted current actor's four compatibility branches."""

    actor = _require_party_actor_role(actor_role)
    persisted = snapshot.get("party_intake_state")
    if persisted is not None:
        state = _validated_party_intake_state(
            persisted,
            source="prompt party_intake_state",
        )
        return copy.deepcopy(state[actor])

    candidates: list[str] = []
    matrix = snapshot.get("case_fact_matrix")
    party_map = matrix.get("party_map") if isinstance(matrix, dict) else None
    if isinstance(party_map, dict):
        initiator = str(party_map.get("initiator_role") or "").upper()
        respondent = str(party_map.get("respondent_role") or "").upper()
        if (
            initiator not in _PARTY_INTAKE_ROLES
            or respondent not in _PARTY_INTAKE_ROLES
            or initiator == respondent
        ):
            raise _party_intake_state_error(
                "INTAKE_PARTY_STATE_ROLE_AUTHORITY_INVALID",
                "prompt matrix party authority is malformed",
            )
        candidates.append(initiator)
    claim = snapshot.get("claim_resolution")
    if isinstance(claim, dict):
        initiator = str(claim.get("initiator_role") or "").upper()
        if initiator in _PARTY_INTAKE_ROLES:
            candidates.append(initiator)
    if candidates and any(candidate != candidates[0] for candidate in candidates[1:]):
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_ROLE_AUTHORITY_DRIFT",
            "prompt initiator role authorities disagree",
        )
    if candidates and actor == candidates[0]:
        return _legacy_party_intake_entry(snapshot)
    return _default_party_intake_entry()


def _validated_party_intake_state(value: Any, *, source: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != _PARTY_INTAKE_STATE_FIELDS:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_SCHEMA_INVALID",
            f"{source} must contain exactly schema_version, USER, and MERCHANT",
        )
    if value.get("schema_version") != PARTY_INTAKE_STATE_SCHEMA_VERSION:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_SCHEMA_INVALID",
            f"{source} has an unsupported schema_version",
        )
    return {
        "schema_version": PARTY_INTAKE_STATE_SCHEMA_VERSION,
        **{
            role: _validated_party_intake_entry(value.get(role), role=role, source=source)
            for role in _PARTY_INTAKE_ROLES
        },
    }


def _validated_party_intake_entry(
    value: Any,
    *,
    role: str,
    source: str,
) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != _PARTY_INTAKE_ENTRY_FIELDS:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_ENTRY_INVALID",
            f"{source}.{role} must contain exactly the four Intake state branches",
        )

    quality = value.get("intake_quality")
    quality_fields = {
        "score",
        "threshold",
        "ready_for_next_step",
        "score_breakdown",
        "improvement_reason",
    }
    if not isinstance(quality, dict) or set(quality) != quality_fields:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_QUALITY_INVALID",
            f"{source}.{role}.intake_quality is malformed",
        )
    score = quality.get("score")
    threshold = quality.get("threshold")
    ready = quality.get("ready_for_next_step")
    breakdown = quality.get("score_breakdown")
    if (
        type(score) is not int
        or not 0 <= score <= 100
        or type(threshold) is not int
        or threshold != 85
        or type(ready) is not bool
        or not isinstance(quality.get("improvement_reason"), str)
        or not isinstance(breakdown, dict)
        or set(breakdown) != set(_QUALITY_SCORE_COMPONENT_MAXIMA)
        or any(
            type(breakdown.get(component)) is not int
            or not 0 <= breakdown[component] <= maximum
            for component, maximum in _QUALITY_SCORE_COMPONENT_MAXIMA.items()
        )
        or sum(breakdown.values()) != score
    ):
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_QUALITY_INVALID",
            f"{source}.{role}.intake_quality violates the canonical score contract",
        )

    missing = value.get("missing_information")
    missing_fields = {"blocking_gaps", "nice_to_have_gaps", "next_questions"}
    if (
        not isinstance(missing, dict)
        or set(missing) != missing_fields
        or any(
            not isinstance(missing.get(field), list)
            or any(not isinstance(item, str) for item in missing[field])
            for field in missing_fields
        )
    ):
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_MISSING_INVALID",
            f"{source}.{role}.missing_information is malformed",
        )

    notes = value.get("handoff_notes")
    notes_fields = {
        "remark_status",
        "phase_source_message_id",
        "latest_remark",
        "remarks",
        "instruction",
    }
    if (
        not isinstance(notes, dict)
        or set(notes) != notes_fields
        or notes.get("remark_status") not in _PARTY_INTAKE_REMARK_STATUSES
        or not isinstance(notes.get("phase_source_message_id"), str)
        or not isinstance(notes.get("latest_remark"), str)
        or not isinstance(notes.get("instruction"), str)
        or not isinstance(notes.get("remarks"), list)
    ):
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_HANDOFF_INVALID",
            f"{source}.{role}.handoff_notes is malformed",
        )
    remark_source_ids: set[str] = set()
    for remark in notes["remarks"]:
        if (
            not isinstance(remark, dict)
            or set(remark) != {"role", "text", "source_message_id", "turn_source"}
            or remark.get("role") != role
            or any(
                not isinstance(remark.get(field), str)
                for field in ("text", "source_message_id", "turn_source")
            )
        ):
            raise _party_intake_state_error(
                "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                f"{source}.{role}.handoff_notes contains a foreign or malformed remark",
            )
        source_message_id = remark["source_message_id"]
        if source_message_id in remark_source_ids:
            raise _party_intake_state_error(
                "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                f"{source}.{role}.handoff_notes repeats a remark source message",
            )
        remark_source_ids.add(source_message_id)

    remark_status = notes["remark_status"]
    latest_remark = notes["latest_remark"]
    remarks = notes["remarks"]
    if remark_status in {"NOT_READY", "READY_PENDING_REMARK_INVITE", "WAITING_FOR_REMARK"}:
        canonical_remark_state = not latest_remark and not remarks
    elif remark_status == "HAS_REMARKS":
        canonical_remark_state = (
            bool(latest_remark)
            and bool(remarks)
            and remarks[-1]["text"] == latest_remark
        )
    else:
        canonical_remark_state = latest_remark == "无额外备注。" and not remarks
    if not canonical_remark_state:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_HANDOFF_INVALID",
            f"{source}.{role}.handoff_notes status and payload disagree",
        )

    admission = value.get("admission")
    admission_fields = {"recommendation", "reasoning", "confidence"}
    confidence = admission.get("confidence") if isinstance(admission, dict) else None
    if (
        not isinstance(admission, dict)
        or set(admission) != admission_fields
        or admission.get("recommendation") not in _PARTY_INTAKE_RECOMMENDATIONS
        or not isinstance(admission.get("reasoning"), str)
        or type(confidence) not in {int, float}
        or not math.isfinite(float(confidence))
        or not 0.0 <= float(confidence) <= 1.0
    ):
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_ADMISSION_INVALID",
            f"{source}.{role}.admission is malformed",
        )

    if ready:
        valid_cross_state = (
            score >= 85
            and not missing["blocking_gaps"]
            and admission["recommendation"] == "ACCEPTED"
            and notes["remark_status"] in _PARTY_INTAKE_READY_REMARK_STATUSES
        )
    else:
        valid_cross_state = (
            admission["recommendation"] != "ACCEPTED"
            and notes["remark_status"] == "NOT_READY"
        )
    if not valid_cross_state:
        raise _party_intake_state_error(
            "INTAKE_PARTY_STATE_OUTCOME_CONFLICT",
            f"{source}.{role} readiness, handoff, and admission disagree",
        )
    return copy.deepcopy(value)


def _set_party_intake_mirror(
    detail: dict[str, Any],
    entry: dict[str, Any],
) -> None:
    for field in _PARTY_INTAKE_ENTRY_FIELDS:
        detail[field] = copy.deepcopy(entry[field])


def _restore_party_handoff_authority(
    detail: dict[str, Any],
    previous_actor_entry: dict[str, Any],
) -> None:
    detail["handoff_notes"] = copy.deepcopy(previous_actor_entry["handoff_notes"])


def _canonical_party_intake_entry(
    detail: dict[str, Any],
    *,
    role: str,
) -> dict[str, Any]:
    quality = _quality_mapping(detail.get("intake_quality"))
    breakdown = quality.get("score_breakdown")
    if not isinstance(breakdown, dict):
        breakdown = {component: 0 for component in _QUALITY_SCORE_COMPONENT_MAXIMA}
    score = quality.get("score")
    canonical_quality = {
        "score": score if type(score) is int else 0,
        "threshold": 85,
        "ready_for_next_step": quality.get("ready_for_next_step") is True,
        "score_breakdown": {
            component: (
                breakdown.get(component)
                if type(breakdown.get(component)) is int
                else 0
            )
            for component in _QUALITY_SCORE_COMPONENT_MAXIMA
        },
        "improvement_reason": str(quality.get("improvement_reason") or ""),
    }
    missing = _quality_mapping(detail.get("missing_information"))
    canonical_missing = {
        field: [item for item in missing.get(field, []) if isinstance(item, str)]
        if isinstance(missing.get(field), list)
        else []
        for field in ("blocking_gaps", "nice_to_have_gaps", "next_questions")
    }
    notes = _quality_mapping(detail.get("handoff_notes"))
    canonical_notes = {
        "remark_status": str(notes.get("remark_status") or "NOT_READY"),
        "phase_source_message_id": str(notes.get("phase_source_message_id") or ""),
        "latest_remark": str(notes.get("latest_remark") or ""),
        "remarks": copy.deepcopy(notes.get("remarks"))
        if isinstance(notes.get("remarks"), list)
        else [],
        "instruction": str(notes.get("instruction") or ""),
    }
    admission = _quality_mapping(detail.get("admission"))
    confidence = admission.get("confidence")
    canonical_admission = {
        "recommendation": str(admission.get("recommendation") or "NEED_MORE_INFO"),
        "reasoning": str(admission.get("reasoning") or ""),
        "confidence": _clamp_confidence(confidence),
    }
    entry = {
        "intake_quality": canonical_quality,
        "missing_information": canonical_missing,
        "handoff_notes": canonical_notes,
        "admission": canonical_admission,
    }
    return _validated_party_intake_entry(
        entry,
        role=_require_party_actor_role(role),
        source="derived current actor entry",
    )


def _is_substantive_case_question(value: Any) -> bool:
    text = str(value or "").strip()
    if not text or _is_evidence_material_request(text):
        return False
    process_markers = (
        "备注",
        "提交",
        "下一步",
        "证据书记官",
        "handoff",
        "submit",
        "next step",
    )
    return not any(marker in text.casefold() for marker in process_markers)


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


def _known_resolution_code(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    code = value.strip().upper()
    if code == "UNKNOWN" or code not in CLAIM_RESOLUTION_LABELS:
        return None
    return code


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
        claim["initiator_role"] = initiator_role
        inferred_resolution = _requested_resolution_from_claim_text(current_text)
        if inferred_resolution is not None:
            claim["requested_resolution"] = inferred_resolution
        if not str(claim.get("request_reason") or "").strip():
            claim["request_reason"] = current_text

    initiator_role = _party_role_or_default(str(claim.get("initiator_role") or ""))
    requested_resolution = _known_resolution_code(
        claim.get("requested_resolution")
    ) or "UNKNOWN"
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
    matrix_delta: CaseFactMatrixDeltaV2 | UnilateralCaseMatrixDraftV1 | None,
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
    enforced_claim = detail.get("claim_resolution")
    enforced_initiator_role = (
        enforced_claim.get("initiator_role")
        if isinstance(enforced_claim, dict)
        else None
    )
    initiator_role = _party_role_or_default(
        previous_party_map.get("initiator_role")
        or enforced_initiator_role
        or getattr(initial, "initiator_role", None)
        or (current.role if current is not None else None)
    )
    actor_role = str(request.agent_context.actor_role or "").upper()
    previous_attitude = (
        previous.get("respondent_attitude") if isinstance(previous, dict) else None
    )
    if not isinstance(previous_attitude, dict):
        previous_attitude = {}
    carried_previous_attitude = _grounded_prior_respondent_attitude(
        previous_attitude,
        expected_respondent_role=_opposite_party(initiator_role),
    )
    if current is not None and actor_role == _opposite_party(initiator_role):
        model_claim = _current_respondent_model_claim(
            request,
            initiator_role=initiator_role,
            matrix_delta=matrix_delta,
            llm_case_detail=llm_case_detail,
            has_prior_grounded_attitude=carried_previous_attitude is not None,
        )
        detection = detect_direct_respondent_attitude(
            current.text,
            source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
            respondent_role=actor_role,
        )
        if detection.state == "UNRESOLVED":
            raise AgentOutputSchemaError(
                "intake_turn_case_detail",
                "respondent attitude signal unresolved",
                safe_code="INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
            )
        if model_claim is not None:
            candidate = detection.candidate
            if (
                detection.state == "SUBSTANTIVE"
                and (
                    candidate is None
                    or candidate.get("attitude") != model_claim["attitude"]
                )
            ):
                raise AgentOutputSchemaError(
                    "intake_turn_case_detail",
                    "respondent attitude detector conflicts with matrix claim",
                    safe_code="INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
                )
            direct_attitude = {
                "respondent_role": actor_role,
                "attitude": model_claim["attitude"],
                "position": model_claim["position_summary"],
                "source": DIRECT_RESPONDENT_SOURCE,
                "confidence": DIRECT_RESPONDENT_CONFIDENCE,
                "grounding": {
                    "source": "RESPONDENT_PARTICIPANT_MESSAGE",
                    "message_id": current.message_id,
                },
            }
            if model_claim.get("alternative_proposal"):
                direct_attitude["alternative_proposal"] = model_claim[
                    "alternative_proposal"
                ]
            detail["respondent_attitude"] = direct_attitude
            return
        if detection.state == "NONE":
            detail["respondent_attitude"] = (
                copy.deepcopy(carried_previous_attitude)
                if carried_previous_attitude is not None
                else _default_respondent_attitude(
                    initial,
                    allow_subjective_seed=False,
                    initiator_role=initiator_role,
                )
            )
            return
        candidate = detection.candidate
        if candidate is None:
            raise AgentOutputSchemaError(
                "intake_turn_case_detail",
                "respondent attitude signal unresolved",
                safe_code="INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
            )
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
    current_reported_attitude = (
        attributed_reported_respondent_attitude(current.text, initiator_role)
        if current is not None
        else None
    )
    llm_attitude = _nested_attitude(llm_case_detail)

    if current is not None and current_reported_attitude is None:
        if carried_previous_attitude is not None:
            detail["respondent_attitude"] = copy.deepcopy(carried_previous_attitude)
            return
        candidate = None
        grounding_source = ""
        grounding_message_id = ""
    elif current_reported_attitude is not None:
        candidate = copy.deepcopy(current_reported_attitude)
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
    elif carried_previous_attitude is not None:
        detail["respondent_attitude"] = copy.deepcopy(carried_previous_attitude)
        return
    else:
        candidate = None
        grounding_source = ""
        grounding_message_id = ""

    if candidate is None:
        detail["respondent_attitude"] = _default_respondent_attitude(
            initial,
            allow_subjective_seed=False,
            initiator_role=initiator_role,
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


def _current_respondent_model_claim(
    request: IntakeTurnRequest,
    *,
    initiator_role: str,
    matrix_delta: CaseFactMatrixDeltaV2 | UnilateralCaseMatrixDraftV1 | None,
    llm_case_detail: dict[str, Any] | None,
    has_prior_grounded_attitude: bool,
) -> dict[str, Any] | None:
    """Bind one model stance to the authenticated current respondent source."""

    if not isinstance(matrix_delta, CaseFactMatrixDeltaV2):
        return None
    current = request.current_user_message
    actor_role = str(request.agent_context.actor_role or "").upper()
    if (
        current is None
        or request.turn_source != "ROOM_MESSAGE"
        or current.source != "ROOM_MESSAGE"
        or str(current.role or "").upper() != actor_role
        or actor_role != _opposite_party(initiator_role)
    ):
        return None
    if not any(
        row.source_scope.value
        in {"CURRENT_SOURCE", "PREVIOUS_AND_CURRENT_SOURCE"}
        and row.stance.value != "NOT_ADDRESSED"
        for row in matrix_delta.fact_rows
    ):
        return None

    matrix_claim = None
    if matrix_delta.respondent_claim is not None:
        matrix_claim = matrix_delta.respondent_claim.model_dump(mode="json")
        if (
            matrix_claim["attitude"]
            not in _SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES
        ):
            raise _party_intake_state_error(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
                "matrix respondent claim is not a substantive stance",
            )

    dossier_claim = _current_case_detail_respondent_claim(
        llm_case_detail,
        actor_role=actor_role,
    )
    if matrix_claim is not None and dossier_claim is not None:
        if matrix_claim != dossier_claim:
            raise _party_intake_state_error(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
                "model respondent attitude conflicts with matrix claim",
            )
        return matrix_claim
    if matrix_claim is not None:
        return matrix_claim
    if dossier_claim is not None and has_prior_grounded_attitude:
        raise _party_intake_state_error(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
            "a prior respondent attitude requires an explicit current matrix claim",
        )
    return dossier_claim


def _current_case_detail_respondent_claim(
    llm_case_detail: dict[str, Any] | None,
    *,
    actor_role: str,
) -> dict[str, Any] | None:
    candidate = _nested_attitude(llm_case_detail)
    if not candidate:
        return None
    forbidden_authority_fields = {
        "source",
        "grounding",
        "confidence_note",
        "status",
    }
    proposed_role = candidate.get("respondent_role")
    attitude = candidate.get("attitude")
    position = candidate.get("position")
    alternative = candidate.get("alternative_proposal")
    if (
        bool(forbidden_authority_fields.intersection(candidate))
        or (
            proposed_role is not None
            and (
                not isinstance(proposed_role, str)
                or proposed_role.upper() != actor_role
            )
        )
        or not isinstance(attitude, str)
        or attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES
        or not isinstance(position, str)
        or not position.strip()
        or (
            alternative is not None
            and (not isinstance(alternative, str) or not alternative.strip())
        )
    ):
        raise _party_intake_state_error(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
            "model respondent attitude is structurally invalid",
        )
    return {
        "attitude": attitude,
        "position_summary": position.strip(),
        "alternative_proposal": (
            alternative.strip() if isinstance(alternative, str) else None
        ),
    }


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_has_explicit_respondent_report` 判断本阶段状态是否满足当前业务分支条件；关键协作调用：`strip`、`re.search`。
# 上下游：上游为 本文件的 `_remove_ungrounded_respondent_attitude`、`_enforce_respondent_attitude_source`；下游为 协作调用 `strip`、`re.search`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _has_explicit_respondent_report(text: str, initiator_role: str) -> bool:
    return bool(_reported_attitude_position(text, initiator_role))


def _direct_attitude_clauses(text: str) -> list[tuple[str, str | None]]:
    clauses: list[tuple[str, str | None]] = []
    cursor = 0
    preceding_boundary: str | None = None
    for boundary in _DIRECT_ATTITUDE_CLAUSE_BOUNDARY.finditer(text):
        clause = text[cursor : boundary.start()].strip()
        if clause:
            clauses.append((clause, preceding_boundary))
        preceding_boundary = boundary.group(0)
        cursor = boundary.end()
    clause = text[cursor:].strip()
    if clause:
        clauses.append((clause, preceding_boundary))
    return clauses


def _direct_attitude_current_transition_clauses_zh(
    clauses: list[tuple[str, str | None]],
    *,
    authenticated_current_message: bool,
) -> list[tuple[str, str | None]] | None:
    """Scope an explicit historical-to-current transition to its current stance."""

    if not authenticated_current_message:
        return None
    historical_attitude_seen = False
    for index, (clause, _) in enumerate(clauses):
        if (
            _DIRECT_ATTITUDE_HISTORICAL_SCOPE_ZH.search(clause) is not None
            and _DIRECT_ATTITUDE_SIGNAL_ZH.search(clause) is not None
        ):
            historical_attitude_seen = True
            continue
        if (
            historical_attitude_seen
            and _DIRECT_ATTITUDE_CURRENT_SCOPE_ZH.match(clause) is not None
            and _DIRECT_ATTITUDE_SIGNAL_ZH.search(clause) is not None
        ):
            return clauses[index:]
    return None


def _direct_attitude_self_subject_zh(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> tuple[bool, str]:
    subject = _DIRECT_ATTITUDE_SELF_ZH.match(clause)
    if subject is None:
        role_subject = _DIRECT_ATTITUDE_ROLE_SELF_ZH.get(
            str(respondent_role or "").upper()
        )
        subject = role_subject.match(clause) if role_subject is not None else None
    if subject is None:
        return False, clause.strip()
    return True, subject.group("body").strip()


def _direct_attitude_semantic_clause_zh(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> str:
    semantic_clause = _DIRECT_ATTITUDE_THIRD_PARTY_PROPOSAL_OBJECT_ZH.sub(
        lambda match: "其" + match.group("object"),
        clause,
    )
    role_subject = _DIRECT_ATTITUDE_ROLE_SELF_ZH.get(
        str(respondent_role or "").upper()
    )
    if role_subject is not None:
        semantic_clause = role_subject.sub(
            lambda match: "本方" + match.group("body"),
            semantic_clause,
            count=1,
        )
    return semantic_clause


def _direct_attitude_explicit_third_party_zh(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> bool:
    semantic_clause = _direct_attitude_semantic_clause_zh(
        clause,
        respondent_role=respondent_role,
    )
    return bool(
        _DIRECT_ATTITUDE_ATTRIBUTION_ZH.search(semantic_clause)
        or _DIRECT_ATTITUDE_THIRD_PARTY_TOPIC_ZH.search(semantic_clause)
    )


def _direct_attitude_explicit_third_party_topic_zh(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> bool:
    return bool(
        _DIRECT_ATTITUDE_THIRD_PARTY_TOPIC_ZH.search(
            _direct_attitude_semantic_clause_zh(
                clause,
                respondent_role=respondent_role,
            )
        )
    )


def _direct_attitude_remedy_scopes_zh(clause: str) -> set[str]:
    return {
        _DIRECT_ATTITUDE_REMEDY_SCOPE_ZH[term]
        for term in _DIRECT_ATTITUDE_REMEDY_ACTION_ZH.findall(clause)
    }


def _direct_attitude_asserted_remedy_scopes_zh(clause: str) -> set[str]:
    signal = _DIRECT_ATTITUDE_SIGNAL_ZH.search(clause)
    if signal is None:
        return set()
    return _direct_attitude_remedy_scopes_zh(clause[signal.start() :])


def _direct_attitude_conditional_remedy_scopes_zh(clause: str) -> set[str]:
    commitment = _DIRECT_ATTITUDE_CONDITIONAL_REMEDY_COMMITMENT_ZH.search(clause)
    if commitment is None:
        return set()
    return _direct_attitude_remedy_scopes_zh(clause[commitment.start() :])


def _direct_attitude_has_investigation_agreement_zh(clause: str) -> bool:
    for agreement in _DIRECT_ATTITUDE_INVESTIGATION_AGREEMENT_ZH.finditer(clause):
        if not any(
            condition.start() < agreement.start()
            for condition in _DIRECT_ATTITUDE_CONDITIONAL_SCOPE_ZH.finditer(clause)
        ):
            return True
    return False


def _direct_attitude_action_scoped_alternative(
    *,
    authenticated_current_message: bool,
    respondent_role: str | None,
    codes: set[str],
    refused_remedy_scopes: set[str],
    conditional_remedy_scopes: set[str],
    investigation_agreement_seen: bool,
) -> bool:
    """Resolve only a conditional plan whose remedies are distinct from the refusal."""

    if not authenticated_current_message:
        return False
    if str(respondent_role or "").upper() not in _DIRECT_ATTITUDE_ROLE_SELF_ZH:
        return False
    if not {"AGREE", "DISAGREE"}.issubset(codes) or not codes.issubset(
        {"AGREE", "DISAGREE", "ALTERNATIVE_PROPOSED"}
    ):
        return False
    if (
        not investigation_agreement_seen
        or not refused_remedy_scopes
        or not conditional_remedy_scopes
    ):
        return False
    return refused_remedy_scopes.isdisjoint(conditional_remedy_scopes)


def _direct_attitude_scope_body_zh(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> str:
    semantic_clause = _direct_attitude_semantic_clause_zh(
        clause,
        respondent_role=respondent_role,
    )
    has_subject, body = _direct_attitude_self_subject_zh(
        semantic_clause,
        respondent_role=respondent_role,
    )
    return body if has_subject else semantic_clause


def _direct_attitude_semantic_clause_en(clause: str) -> str:
    return _DIRECT_ATTITUDE_THIRD_PARTY_PROPOSAL_OBJECT_EN.sub(
        "third-party proposal",
        clause,
    )


def _direct_attitude_mixes_attribution_zh(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> bool:
    semantic_clause = _direct_attitude_semantic_clause_zh(
        clause,
        respondent_role=respondent_role,
    )
    if _DIRECT_ATTITUDE_ATTRIBUTION_ZH.search(semantic_clause) is None:
        return False
    self_terms = r"(?:本公司|本人|我方|我们|本方|我司|我)"
    role = str(respondent_role or "").upper()
    if role == "USER":
        self_terms += r"|(?:用户|买家|客户|消费者)"
        foreign_terms = r"(?:对方|商家|卖家|店铺|商户|客服|平台|第三方)"
    elif role == "MERCHANT":
        self_terms += r"|(?:商家|卖家|店铺|商户|客服)"
        foreign_terms = r"(?:用户|买家|客户|消费者|对方|平台|第三方)"
    else:
        foreign_terms = _DIRECT_ATTITUDE_THIRD_PARTY_ZH
    for subject in re.finditer(self_terms, semantic_clause):
        subject_scope = semantic_clause[subject.end() :]
        signal = _DIRECT_ATTITUDE_SIGNAL_ZH.search(subject_scope)
        if signal is None:
            continue
        intervening_party = re.search(
            foreign_terms,
            subject_scope[: signal.start()],
        )
        if intervening_party is not None:
            if role not in _DIRECT_ATTITUDE_ROLE_SELF_ZH:
                return True
            continue
        preceding_reporters = list(
            re.finditer(foreign_terms, semantic_clause[: subject.start()])
        )
        if preceding_reporters:
            reporter_scope = semantic_clause[
                preceding_reporters[-1].end() : subject.start()
            ]
            if _DIRECT_ATTITUDE_SIGNAL_ZH.search(reporter_scope) is None:
                continue
        return True
    return False


def _direct_attitude_mixes_attribution_en(
    clause: str,
    *,
    respondent_role: str | None = None,
) -> bool:
    if _DIRECT_ATTITUDE_ATTRIBUTION_EN.search(clause) is None:
        return False
    role = str(respondent_role or "").upper()
    if role == "USER":
        foreign_terms = r"(?:merchant|seller|store|counterparty)"
    elif role == "MERCHANT":
        foreign_terms = r"(?:buyer|customer|consumer|counterparty)"
    else:
        foreign_terms = _DIRECT_ATTITUDE_THIRD_PARTY_EN
    for subject in re.finditer(
        r"\b(?:i|we|our\s+(?:company|side|firm|business|organization))\b",
        clause,
        re.IGNORECASE,
    ):
        subject_scope = clause[subject.end() :]
        signal = _DIRECT_ATTITUDE_SIGNAL_EN.search(subject_scope)
        if signal is None:
            continue
        intervening_party = re.search(
            rf"\b(?:the\s+)?{foreign_terms}\b",
            subject_scope[: signal.start()],
            re.IGNORECASE,
        )
        if intervening_party is not None:
            if role not in _DIRECT_ATTITUDE_ROLE_SELF_ZH:
                return True
            continue
        preceding_reporters = list(
            re.finditer(
                rf"\b(?:the\s+)?{foreign_terms}\b",
                clause[: subject.start()],
                re.IGNORECASE,
            )
        )
        if preceding_reporters:
            reporter_scope = clause[
                preceding_reporters[-1].end() : subject.start()
            ]
            if _DIRECT_ATTITUDE_SIGNAL_EN.search(reporter_scope) is None:
                continue
        return True
    return False


def detect_direct_respondent_attitude(
    text: str,
    *,
    source_authority: str | None = None,
    respondent_role: str | None = None,
) -> DirectRespondentAttitudeDetection:
    """Classify direct attitude under an explicit current-message authority."""

    normalized = str(text or "").strip()
    if not normalized:
        return DirectRespondentAttitudeDetection("NONE")
    if (
        _DIRECT_ATTITUDE_SIGNAL_ZH.search(normalized) is not None
        and _DIRECT_ATTITUDE_DEFERRED_ATTRIBUTION_ZH.search(normalized) is not None
    ) or (
        _DIRECT_ATTITUDE_SIGNAL_EN.search(normalized) is not None
        and _DIRECT_ATTITUDE_DEFERRED_ATTRIBUTION_EN.search(normalized) is not None
    ):
        return DirectRespondentAttitudeDetection("UNRESOLVED")
    resolved: list[str] = []
    unresolved_signal = False
    third_party_signal = False
    mixed_attribution_signal = False
    refused_remedy_scopes: set[str] = set()
    conditional_remedy_scopes: set[str] = set()
    investigation_agreement_seen = False
    authenticated_current_message = (
        source_authority == RESPONDENT_AUTHORED_CURRENT_MESSAGE
    )
    chinese_speaker_context = "SELF" if authenticated_current_message else "UNKNOWN"
    persistent_third_party_topic = False
    clauses = _direct_attitude_clauses(normalized)
    current_transition_clauses = _direct_attitude_current_transition_clauses_zh(
        clauses,
        authenticated_current_message=authenticated_current_message,
    )
    if current_transition_clauses is not None:
        clauses = current_transition_clauses
    for clause_index, (clause, preceding_boundary) in enumerate(clauses):
        coordinated_with_previous = (
            preceding_boundary is not None
            and _DIRECT_ATTITUDE_COORDINATION_BOUNDARY.fullmatch(
                preceding_boundary
            )
            is not None
        )
        if preceding_boundary is not None and not coordinated_with_previous:
            if persistent_third_party_topic:
                chinese_speaker_context = "THIRD_PARTY"
            else:
                chinese_speaker_context = (
                    "SELF" if authenticated_current_message else "UNKNOWN"
                )
        explicit_self, _ = _direct_attitude_self_subject_zh(
            clause,
            respondent_role=respondent_role,
        )
        explicit_third_party = _direct_attitude_explicit_third_party_zh(
            clause,
            respondent_role=respondent_role,
        )
        explicit_third_party_topic = (
            _direct_attitude_explicit_third_party_topic_zh(
                clause,
                respondent_role=respondent_role,
            )
        )
        if explicit_self:
            chinese_speaker_context = "SELF"
            persistent_third_party_topic = explicit_third_party_topic
        elif explicit_third_party:
            chinese_speaker_context = "THIRD_PARTY"
            persistent_third_party_topic = (
                persistent_third_party_topic or explicit_third_party_topic
            )
        scoped_clause = clause
        if clause_index > 0 and coordinated_with_previous:
            scoped_clause = clauses[clause_index - 1][0] + preceding_boundary + clause
        scoped_body = _direct_attitude_scope_body_zh(
            scoped_clause,
            respondent_role=respondent_role,
        )
        semantic_clause_zh = _direct_attitude_semantic_clause_zh(
            clause,
            respondent_role=respondent_role,
        )
        if _DIRECT_ATTITUDE_SIGNAL_ZH.search(semantic_clause_zh):
            if explicit_third_party or chinese_speaker_context == "THIRD_PARTY":
                third_party_signal = True
                if explicit_third_party and _direct_attitude_mixes_attribution_zh(
                    clause,
                    respondent_role=respondent_role,
                ):
                    mixed_attribution_signal = True
                code = "NONE"
            else:
                code = _direct_respondent_attitude_clause_zh(
                    clause,
                    inherited_subject=chinese_speaker_context == "SELF",
                    respondent_role=respondent_role,
                )
            if code is None:
                unresolved_signal = True
            elif code != "NONE":
                remedy_scopes = _direct_attitude_asserted_remedy_scopes_zh(clause)
                investigation_agreement = (
                    code == "AGREE"
                    and _direct_attitude_has_investigation_agreement_zh(clause)
                )
                if investigation_agreement:
                    investigation_agreement_seen = True
                if investigation_agreement and not remedy_scopes:
                    continue
                resolved.append(code)
                if code == "AGREE" and _DIRECT_ATTITUDE_CONDITIONAL_SCOPE_ZH.search(
                    scoped_body
                ):
                    conditional_remedy_scopes.update(remedy_scopes)
                if code == "DISAGREE":
                    refused_remedy_scopes.update(remedy_scopes)
            continue
        if (
            authenticated_current_message
            and chinese_speaker_context == "SELF"
            and not explicit_third_party
            and _DIRECT_ATTITUDE_CONDITIONAL_SCOPE_ZH.search(scoped_body)
            and _DIRECT_ATTITUDE_CONDITIONAL_REMEDY_COMMITMENT_ZH.search(clause)
        ):
            conditional_remedy_scopes.update(
                _direct_attitude_conditional_remedy_scopes_zh(clause)
            )
            resolved.append("AGREE")
        if _DIRECT_ATTITUDE_SIGNAL_EN.search(clause):
            semantic_clause = _direct_attitude_semantic_clause_en(clause)
            explicit_third_party = (
                _DIRECT_ATTITUDE_ATTRIBUTION_EN.search(semantic_clause) is not None
            )
            if explicit_third_party:
                third_party_signal = True
                if _direct_attitude_mixes_attribution_en(
                    semantic_clause,
                    respondent_role=respondent_role,
                ):
                    mixed_attribution_signal = True
                code = "NONE"
            else:
                code = _direct_respondent_attitude_clause_en(semantic_clause)
            if code is None:
                unresolved_signal = True
            elif code != "NONE":
                resolved.append(code)
    codes = set(resolved)
    authenticated_party_scope = (
        authenticated_current_message
        and str(respondent_role or "").upper() in _DIRECT_ATTITUDE_ROLE_SELF_ZH
    )
    if (
        unresolved_signal
        or mixed_attribution_signal
        or (third_party_signal and resolved and not authenticated_party_scope)
    ):
        return DirectRespondentAttitudeDetection("UNRESOLVED")
    if _direct_attitude_action_scoped_alternative(
        authenticated_current_message=authenticated_current_message,
        respondent_role=respondent_role,
        codes=codes,
        refused_remedy_scopes=refused_remedy_scopes,
        conditional_remedy_scopes=conditional_remedy_scopes,
        investigation_agreement_seen=investigation_agreement_seen,
    ):
        code = "ALTERNATIVE_PROPOSED"
    else:
        code = _reduce_direct_respondent_attitude_codes(codes)
    if code is not None:
        return DirectRespondentAttitudeDetection(
            "SUBSTANTIVE",
            {
                "attitude": code,
                "position": normalized,
                "confidence": DIRECT_RESPONDENT_CONFIDENCE,
            },
        )
    if codes:
        return DirectRespondentAttitudeDetection("UNRESOLVED")
    if third_party_signal:
        return DirectRespondentAttitudeDetection("NONE")
    return DirectRespondentAttitudeDetection("NONE")


def _reduce_direct_respondent_attitude_codes(
    codes: set[str],
    *,
    coherent_conditional_stance: bool = False,
) -> str | None:
    if len(codes) == 1:
        return next(iter(codes))
    if codes == {"DISAGREE", "ALTERNATIVE_PROPOSED"}:
        return "ALTERNATIVE_PROPOSED"
    if coherent_conditional_stance and codes == {
        "AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
    }:
        return "ALTERNATIVE_PROPOSED"
    return None


def _direct_respondent_attitude_clause_zh(
    clause: str,
    *,
    inherited_subject: bool = False,
    respondent_role: str | None = None,
) -> str | None:
    semantic_clause = _direct_attitude_semantic_clause_zh(
        clause,
        respondent_role=respondent_role,
    )
    if _DIRECT_ATTITUDE_ATTRIBUTION_ZH.search(semantic_clause) is not None:
        return None
    has_subject, subject_body = _direct_attitude_self_subject_zh(
        semantic_clause,
        respondent_role=respondent_role,
    )
    if not has_subject:
        if not inherited_subject:
            return None
        body = semantic_clause.strip()
    else:
        body = subject_body
    no_alternative = re.compile(
        r"(?:并|且|也)?(?:不|未|没有)\s*(?:提出|提供|给出|建议)\s*"
        rf"(?:任何|其他|其它|额外)?\s*"
        rf"(?:(?:{_DIRECT_ATTITUDE_REMEDY_ACTION_ZH.pattern})\s*)?"
        r"(?:替代方案|方案|建议)"
    )
    denied_alternative = no_alternative.search(body) is not None
    body = no_alternative.sub("", body)
    if re.fullmatch(r"(?:尚未|未|没有)\s*(?:明确)?表态", body):
        return "NONE"
    if re.search(r"(?:并非|不是|没有|未)\s*(?:不同意|不接受|拒绝|不支持)", body):
        return None
    codes: set[str] = set()
    remaining_body = body
    negative_positive = re.compile(r"(?:并不|没有|未|不)\s*(?:同意|接受|支持|愿意)")
    if negative_positive.search(remaining_body):
        codes.add("DISAGREE")
        remaining_body = negative_positive.sub("", remaining_body)
    partial = re.compile(r"部分(?:同意|接受)|只(?:同意|接受)")
    partial_match = partial.search(remaining_body)
    if partial_match:
        codes.add("PARTIALLY_AGREE")
        remaining_body = partial.sub("", remaining_body)
    disagreement = re.compile(r"不同意|不接受|拒绝|不支持")
    if disagreement.search(remaining_body):
        codes.add("DISAGREE")
        remaining_body = disagreement.sub("", remaining_body)
    if partial_match is None and re.search(r"同意|接受|支持|愿意", remaining_body):
        codes.add("AGREE")
    if re.search(r"提出|建议|替代方案", body):
        codes.add("ALTERNATIVE_PROPOSED")
    if re.search(r"要求补充|需要更多信息", body):
        codes.add("NEED_MORE_INFO")
    reduced = _reduce_direct_respondent_attitude_codes(codes)
    if reduced is None and denied_alternative and not codes:
        return "NONE"
    return reduced


def _direct_respondent_attitude_clause_en(clause: str) -> str | None:
    semantic_clause = _direct_attitude_semantic_clause_en(clause)
    if _DIRECT_ATTITUDE_ATTRIBUTION_EN.search(semantic_clause) is not None:
        return None
    subject = _DIRECT_ATTITUDE_SELF_EN.match(semantic_clause)
    if subject is None:
        return None
    body = subject.group("body").strip()
    matches = list(_DIRECT_ATTITUDE_SIGNAL_EN.finditer(body))
    if not matches:
        return None
    codes: set[str] = set()
    for match in matches:
        term = re.sub(r"\s+", " ", match.group(0).strip().lower())
        prefix = body[max(0, match.start() - 32) : match.start()]
        suffix = body[match.end() : match.end() + 32]
        negated = re.search(
            r"\b(?:not|never|no\s+longer|without|hardly)\b|n['’]t\b",
            prefix,
            re.IGNORECASE,
        ) is not None
        if term in {
            "disagree",
            "disagreed",
            "disagrees",
            "reject",
            "rejected",
            "rejects",
            "refuse",
            "refused",
            "refuses",
        }:
            if negated:
                return None
            codes.add("DISAGREE")
        elif term.startswith("partially "):
            if negated:
                return None
            codes.add("PARTIALLY_AGREE")
        elif term in {"agree", "agreed", "agrees", "accept", "accepted", "accepts"}:
            if negated or re.match(r"\s+(?:no|none|nothing|neither|not|never)\b", suffix):
                return None
            codes.add("AGREE")
        elif term in {
            "offer",
            "offered",
            "offers",
            "propose",
            "proposed",
            "proposes",
            "suggest",
            "suggested",
            "suggests",
        }:
            if negated:
                return None
            codes.add("ALTERNATIVE_PROPOSED")
        else:
            if negated:
                return None
            codes.add("NEED_MORE_INFO")
    return _reduce_direct_respondent_attitude_codes(codes)


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
        if not sentence:
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


def attributed_reported_respondent_attitude(
    text: str,
    initiator_role: str,
) -> dict[str, Any] | None:
    """Return only a substantive attitude from a counterparty-attributed slice."""

    position = _reported_attitude_position(text, initiator_role)
    if position:
        candidate = _reported_attitude_from_text(position, initiator_role)
        if candidate.get("attitude") in _SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES:
            return candidate
    return _reported_respondent_attitude_from_text_en(text, initiator_role)


def _reported_respondent_attitude_from_text_en(
    text: str,
    initiator_role: str,
) -> dict[str, Any] | None:
    respondent = (
        r"user|buyer|customer|consumer|counterparty"
        if initiator_role == "MERCHANT"
        else r"merchant|seller|store|customer service|counterparty"
    )
    subject = re.compile(
        rf"\b(?:the\s+)?(?:{respondent})\b(?!['’]s)"
        rf"(?:\s+(?:{_REPORTED_RESPONDENT_ATTITUDE_MODIFIER_EN}))*\s+"
        rf"(?P<attitude>{_REPORTED_RESPONDENT_ATTITUDE_TERM_EN})\b(?!\s+by\b)",
        re.IGNORECASE,
    )
    passive = re.compile(
        rf"\b(?P<attitude>{_REPORTED_RESPONDENT_ATTITUDE_TERM_EN})\b"
        rf"\s+by\s+(?:the\s+)?(?:{respondent})\b",
        re.IGNORECASE,
    )
    matches = [
        match
        for pattern in (subject, passive)
        if (match := pattern.search(text))
        and not _is_negated_en_reported_attitude(
            text,
            match.start("attitude"),
            match.end("attitude"),
        )
    ]
    if not matches:
        return None
    attributed = min(matches, key=lambda match: match.start())
    attitude = _reported_attitude_code_from_en_term(attributed.group("attitude"))
    if attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES:
        return None
    return {
        "attitude": attitude,
        "position": text.strip(),
        "confidence": 0.65,
    }


def _is_negated_en_reported_attitude(
    text: str,
    attitude_start: int,
    attitude_end: int,
) -> bool:
    prefix = text[:attitude_start]
    clause_start = max((prefix.rfind(boundary) for boundary in ".!?;\n"), default=-1)
    bounded_clause_prefix = prefix[max(clause_start + 1, len(prefix) - 64) :]
    if _REPORTED_ATTITUDE_NEGATION_EN.search(bounded_clause_prefix) is not None:
        return True
    suffix = text[attitude_end:]
    clause_end_candidates = [
        index for boundary in ".!?;\n" if (index := suffix.find(boundary)) >= 0
    ]
    clause_end = min(clause_end_candidates, default=len(suffix))
    bounded_clause_suffix = suffix[: min(clause_end, 64)]
    return _REPORTED_ATTITUDE_POST_NEGATION_EN.search(bounded_clause_suffix) is not None


def _reported_attitude_code_from_en_term(value: str) -> str | None:
    normalized = " ".join(value.lower().split())
    if normalized in {
        "partially agree",
        "partially agreed",
        "partially accept",
        "partially accepted",
    }:
        return "PARTIALLY_AGREE"
    if normalized in {"reject", "rejected", "refuse", "refused", "disagree", "disagreed"}:
        return "DISAGREE"
    if normalized in {"agree", "agreed", "accept", "accepted"}:
        return "AGREE"
    if normalized in {"offer", "offered", "propose", "proposed"}:
        return "ALTERNATIVE_PROPOSED"
    return None


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
        r"具体诉求|诉求是(?!否.{0,16}(?:回应|回复|同意|接受|处理))|"
        r"希望.{0,12}(怎么处理|如何处理)|"
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


def _grounded_prior_respondent_attitude(
    candidate: dict[str, Any],
    *,
    expected_respondent_role: str,
) -> dict[str, Any] | None:
    """Return only an immutable, source-bound substantive prior branch."""

    if (
        candidate.get("respondent_role") != expected_respondent_role
        or "attitude" not in candidate
        or "status" in candidate
    ):
        return None
    attitude = str(candidate.get("attitude") or "").strip().upper()
    position = candidate.get("position")
    confidence = candidate.get("confidence")
    if (
        attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES
        or not isinstance(position, str)
        or not position.strip()
        or isinstance(confidence, bool)
        or not isinstance(confidence, int | float)
        or not 0 <= confidence <= 1
    ):
        return None
    grounding = candidate.get("grounding")
    if not isinstance(grounding, dict) or not {"source", "message_id"} <= set(grounding):
        return None
    grounding_source = grounding.get("source")
    message_id = grounding.get("message_id")
    if not isinstance(message_id, str):
        return None
    source = str(candidate.get("source") or "").strip()
    if source == SUBJECTIVE_RESPONDENT_SOURCE:
        if candidate.get("confidence_note") != SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE:
            return None
        if grounding_source == "INITIAL_FORM" and message_id == "":
            return copy.deepcopy(candidate)
        if (
            grounding_source == "PARTICIPANT_MESSAGE"
            and _RESPONDENT_ATTITUDE_SOURCE_IDENTIFIER.fullmatch(message_id)
        ):
            return copy.deepcopy(candidate)
        return None
    if (
        source == DIRECT_RESPONDENT_SOURCE
        and grounding_source == "RESPONDENT_PARTICIPANT_MESSAGE"
        and _RESPONDENT_ATTITUDE_SOURCE_IDENTIFIER.fullmatch(message_id)
    ):
        return copy.deepcopy(candidate)
    return None


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_default_respondent_attitude` 围绕对方态度计算该函数独立负责的业务派生值；关键协作调用：`seed.model_dump`、`subjective_seed.get`；返回/更新字段：`respondent_role`、`attitude`、`position`、`source`。
# 上下游：上游为 本文件的 `CaseDetailDossierSkill._default_case_detail`、`_enforce_respondent_attitude_source`、`_default_dispute_core_state`；下游为 本文件的 `_party_role_or_default`、`_opposite_party`、`_subjective_attitude`、`_clamp_confidence`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _default_respondent_attitude(
    lobby_seed: Any,
    *,
    allow_subjective_seed: bool = True,
    initiator_role: str | None = None,
) -> dict[str, Any]:
    seed = getattr(lobby_seed, "respondent_attitude_seed", None)
    normalized_initiator_role = _party_role_or_default(
        initiator_role or getattr(lobby_seed, "initiator_role", None)
    )
    respondent_role = _opposite_party(normalized_initiator_role)
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
        *_quality_focus_values(core.get("next_verification_focus")),
        *_quality_focus_values(dispute_focus.get("facts_to_verify")),
        *_quality_focus_values(missing.get("blocking_gaps")),
        *_quality_focus_values(missing.get("nice_to_have_gaps")),
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
    phase_source_message_id: str | None = None,
) -> dict[str, Any]:
    notes = _ensure_dict(detail, "handoff_notes")
    if remark_status is not None:
        notes["remark_status"] = remark_status
    elif not notes.get("remark_status") or notes.get("remark_status") == "NOT_READY":
        notes["remark_status"] = "WAITING_FOR_REMARK"
    if phase_source_message_id is not None:
        notes["phase_source_message_id"] = phase_source_message_id
    else:
        notes.setdefault("phase_source_message_id", "")
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
    previous_phase_source_message_id: str,
) -> None:
    current = request.current_user_message
    if (
        current is None
        or not previous_waiting_for_remark
        or not current.text.strip()
        or current.message_id == previous_phase_source_message_id
    ):
        return

    notes = _ensure_handoff_notes(detail)
    text = current.text.strip()
    if _is_no_extra_remark(text):
        notes["remark_status"] = "NO_EXTRA_REMARKS"
        notes["phase_source_message_id"] = current.message_id
        notes["latest_remark"] = "无额外备注。"
        return

    notes["remark_status"] = "HAS_REMARKS"
    notes["phase_source_message_id"] = current.message_id
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


def _quality_mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _quality_text(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    return value.strip().upper() not in _QUALITY_UNKNOWN_CODES


def _quality_meaningful_value(
    value: Any,
    *,
    seen: set[int] | None = None,
) -> bool:
    if isinstance(value, str):
        return _quality_text(value)
    if not isinstance(value, dict | list | tuple):
        return False
    if seen is None:
        seen = set()
    identity = id(value)
    if identity in seen:
        return False
    seen.add(identity)
    if isinstance(value, dict):
        nested = (
            item
            for key, item in value.items()
            if str(key).strip().lower() not in _QUALITY_METADATA_KEYS
        )
    else:
        nested = iter(value)
    return any(_quality_meaningful_value(item, seen=seen) for item in nested)


def _quality_items(value: Any) -> bool:
    return isinstance(value, list) and any(
        _quality_meaningful_value(item) for item in value
    )


def _quality_focus_values(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value.strip()] if _quality_text(value) else []
    if not isinstance(value, list):
        return []
    return [
        item.strip()
        for item in value
        if isinstance(item, str) and _quality_text(item)
    ]


def _respondent_resolution_authorized(
    detail: dict[str, Any],
    *,
    actor_role: str,
    initiator_role: str | None,
) -> bool:
    if (
        initiator_role not in _PARTY_INTAKE_ROLES
        or actor_role != _opposite_party(initiator_role)
    ):
        return False
    attitude = _quality_mapping(detail.get("respondent_attitude"))
    return (
        str(attitude.get("respondent_role") or "").upper() == actor_role
        and str(attitude.get("source") or "") == DIRECT_RESPONDENT_SOURCE
        and str(attitude.get("attitude") or "").upper()
        in _SUBSTANTIVE_RESPONDENT_ATTITUDE_CODES
        and _quality_text(attitude.get("position"))
    )


def _authoritative_intake_source_records(
    request: IntakeTurnRequest,
    *,
    initiator_role: str | None,
) -> tuple[tuple[str, str], ...]:
    records: list[tuple[str, str]] = []
    seen: set[str] = set()
    actor_role = _require_party_actor_role(request.agent_context.actor_role)

    def append_record(identifier: Any, text: Any) -> None:
        normalized_id = str(identifier or "").strip()
        normalized_text = str(text or "").strip()
        if (
            not normalized_id
            or normalized_id in seen
            or not _quality_text(normalized_text)
        ):
            return
        seen.add(normalized_id)
        records.append((normalized_id, normalized_text))

    if initiator_role is not None and actor_role == initiator_role:
        initial = request.initial_case_facts
        append_record(
            getattr(initial, "form_source", None) or "INITIAL_FORM",
            getattr(initial, "form_description", None),
        )
        for message in request.initiator_statement_transcript:
            if str(message.role or "").upper() == actor_role:
                append_record(message.message_id, message.text)

    for message in request.recent_dialogue_messages:
        if str(message.role or "").upper() == actor_role:
            append_record(message.message_id, message.text)
    current = request.current_user_message
    if current is not None and str(current.role or "").upper() == actor_role:
        append_record(current.message_id, current.text)
    return tuple(records)


def _build_intake_quality_authority(
    detail: dict[str, Any],
    *,
    request: IntakeTurnRequest,
    missing: list[str],
    initiator_role: str | None,
    actor_source_records: tuple[tuple[str, str], ...],
) -> _IntakeQualityAuthority:
    references = _quality_mapping(detail.get("references"))
    records = actor_source_records
    claim = _quality_mapping(detail.get("claim_resolution"))
    actor_role = _require_party_actor_role(request.agent_context.actor_role)
    actor_is_initiator = initiator_role is not None and actor_role == initiator_role
    source_text = "\n".join(text for _, text in records)
    compact_source_length = len(re.sub(r"\s+", "", source_text))
    attitude = _quality_mapping(detail.get("respondent_attitude"))
    direct_respondent_state = _respondent_resolution_authorized(
        detail,
        actor_role=actor_role,
        initiator_role=initiator_role,
    )
    resolution = _known_resolution_code(claim.get("requested_resolution"))
    core = _quality_mapping(detail.get("dispute_core_state"))
    conflict_type = str(core.get("conflict_type") or "").upper()
    known_conflict = conflict_type in _QUALITY_CONFLICT_TYPES
    missing_question = _question_for_missing(missing)

    if not records:
        return _IntakeQualityAuthority(*([False] * 13))

    if actor_is_initiator:
        initiator_position = any(
            _quality_text(claim.get(key))
            for key in ("normalized_statement", "original_statement", "request_reason")
        )
        respondent_state = (
            str(attitude.get("respondent_role") or "").upper()
            == _opposite_party(initiator_role)
            and str(attitude.get("attitude") or "").upper()
            in RESPONDENT_ATTITUDE_CODES
            and _quality_text(attitude.get("position"))
        )
        requested_resolution = resolution is not None
        normalized_request = requested_resolution and _quality_text(
            claim.get("normalized_statement")
        )
        order_reference = _quality_text(references.get("order_reference"))
        after_sales_reference = _quality_text(references.get("after_sales_reference"))
        logistics_reference = _quality_text(references.get("logistics_reference"))
        conflict_authority = known_conflict
        core_conflict_authority = known_conflict and _quality_text(
            core.get("core_conflict")
        )
        action_target = bool(missing) or (known_conflict and resolution is not None)
        action_path = (
            bool(missing_question)
            if missing
            else known_conflict and resolution is not None
        )
    else:
        folded_source = source_text.casefold()

        def actor_stated_reference(field: str) -> bool:
            value = str(references.get(field) or "").strip()
            return bool(value) and value.casefold() in folded_source

        order_reference = actor_stated_reference("order_reference")
        after_sales_reference = actor_stated_reference("after_sales_reference")
        logistics_reference = actor_stated_reference("logistics_reference")
        initiator_position = compact_source_length >= 8
        respondent_state = direct_respondent_state
        requested_resolution = direct_respondent_state
        normalized_request = direct_respondent_state and compact_source_length >= 16
        conflict_authority = compact_source_length >= 12
        core_conflict_authority = compact_source_length >= 16
        action_target = compact_source_length >= 8
        action_path = compact_source_length >= 16

    return _IntakeQualityAuthority(
        order_reference=order_reference,
        after_sales_reference=after_sales_reference,
        logistics_reference=logistics_reference,
        authoritative_story=compact_source_length >= 8,
        authoritative_event=(
            compact_source_length >= 16
            and any(_quality_text(identifier) for identifier, _ in records)
        ),
        initiator_position=initiator_position,
        respondent_state=respondent_state,
        requested_resolution=requested_resolution,
        normalized_request=normalized_request,
        conflict_type=conflict_authority,
        core_conflict=core_conflict_authority,
        action_target=action_target,
        action_path=action_path,
    )


def _derive_intake_quality_breakdown(
    detail: dict[str, Any],
    *,
    request: IntakeTurnRequest,
    missing: list[str],
    initiator_role: str | None,
    actor_source_records: tuple[tuple[str, str], ...],
) -> dict[str, int]:
    """Score only request-bound authority using the six fixed prompt maxima."""

    authority = _build_intake_quality_authority(
        detail,
        request=request,
        missing=missing,
        initiator_role=initiator_role,
        actor_source_records=actor_source_records,
    )
    breakdown = {
        "references": (
            (6 if authority.order_reference else 0)
            + (3 if authority.after_sales_reference else 0)
            + (6 if authority.logistics_reference else 0)
        ),
        "event_story": (10 if authority.authoritative_story else 0)
        + (10 if authority.authoritative_event else 0),
        "party_positions": (10 if authority.initiator_position else 0)
        + (10 if authority.respondent_state else 0),
        "requested_resolution": (10 if authority.requested_resolution else 0)
        + (5 if authority.normalized_request else 0),
        "risk_and_conflicts": (8 if authority.conflict_type else 0)
        + (7 if authority.core_conflict else 0),
        "next_action_clarity": (8 if authority.action_target else 0)
        + (7 if authority.action_path else 0),
    }
    if any(
        score < 0 or score > _QUALITY_SCORE_COMPONENT_MAXIMA[component]
        for component, score in breakdown.items()
    ):
        raise AssertionError("derived intake quality component exceeded its fixed maximum")
    return breakdown


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


# 接待室只拒绝把材料交到当前房间的指令；不能因普通案情澄清提到材料名就覆盖模型回复。
_EVIDENCE_TRANSFER_OBJECT_RE = re.compile(
    r"(?:"
    r"截图|图片|照片|视频|聊天记录|沟通记录|通话记录|物流记录|交易记录|录音|凭证|证明材料|证据材料|证据(?!书记官|室)|"
    r"检测报告|检验报告|发票|交易流水|支付流水|快递底单|签收单|物流单(?!号)|运单(?!号)|文件|文档|附件|材料|订单确认稿|"
    r"screenshots?|images?|photos?|videos?|chat\s+records?|communication\s+records?|"
    r"recordings?|vouchers?|receipts?|documents?|files?|materials?|attachments?|"
    r"order[-\s]?confirmation\s+(?:draft|document)|\b(?:evidence|proofs?)\b"
    r")",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_ACTION_RE = re.compile(
    r"(?:上传|补交|补充(?!说明)|提供|提交|发送|发来|附上|出示|发给|发至|寄给|分享|共享|"
    r"\b(?:upload|provide|submit|send|attach|show|email|share)\b)",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_REQUEST_CUE_RE = re.compile(
    r"(?:请(?:您)?|麻烦(?:您)?|劳烦|烦请|还请|能否|可否|是否(?:可以|能)|方便|"
    r"please|could\s+you|can\s+you|would\s+you|kindly)",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_DIRECT_CUE_RE = re.compile(
    r"^(?:请(?:您)?|麻烦(?:您)?|劳烦|烦请|还请|能否|可否|是否(?:可以|能)|方便|"
    r"please|could\s+you|can\s+you|would\s+you|kindly)\s*",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_OBLIGATION_RE = re.compile(
    r"(?:还需要|还需|需要|必须|务必|应当|"
    r"\bmust\b|\bneed\s+to\b|\brequired\s+to\b)",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_ATTRIBUTION_RE = re.compile(
    r"(?:商家|用户|对方|发起方|被发起方).{0,24}(?:称|表示|说|回复|主张|认为|告知|反馈)",
)
_EVIDENCE_FACTUAL_HISTORY_RE = re.compile(
    r"(?:确认|核实|说明|告知|提到).{0,80}(?:已经|已|此前|之前|曾|曾经|目前)",
)
_EVIDENCE_FACTUAL_ACTOR_QUERY_RE = re.compile(
    r"(?:请问|确认|核实|说明|告知|提到).{0,80}"
    r"(?:商家|用户|对方|发起方|被发起方).{0,48}"
    r"(?:是否|还需要|还需|需要|必须|务必|应当)",
)


def _is_evidence_action_history_form(text: str, action: re.Match[str]) -> bool:
    """Recognize forms such as ``提供的`` and ``上传过`` as material history."""

    return text[action.end() :].lstrip().startswith(("的", "方", "过", "了"))


def _is_current_evidence_transfer_instruction(
    clause: str,
    action: re.Match[str] | None,
) -> bool:
    """Recognize a current officer-to-user transfer direction before attribution checks."""

    if action is None:
        return False
    leading = re.sub(r"^(?:[-*•]|\d+[.、)])\s*", "", clause).lstrip()
    leading_action = _EVIDENCE_TRANSFER_ACTION_RE.match(leading)
    if leading_action is not None:
        return not _is_evidence_action_history_form(leading, leading_action)

    direct_cue = _EVIDENCE_TRANSFER_DIRECT_CUE_RE.match(leading)
    if direct_cue is None:
        return False
    remainder = re.sub(
        r"^(?:现在|立即|马上|尽快|now|immediately|right\s+away)\s*",
        "",
        leading[direct_cue.end() :],
        flags=re.IGNORECASE,
    )
    direct_action = _EVIDENCE_TRANSFER_ACTION_RE.match(remainder)
    if direct_action is not None:
        return not _is_evidence_action_history_form(remainder, direct_action)
    return remainder.startswith(("把", "将"))


def _is_factual_evidence_reference(
    clause: str,
    action: re.Match[str] | None,
) -> bool:
    """Return whether a clause refers to material history rather than asks for delivery."""

    if _EVIDENCE_TRANSFER_ATTRIBUTION_RE.search(clause):
        return True
    if action is None:
        return False
    before_action = clause[: action.start()]
    if _is_evidence_action_history_form(clause, action):
        return True
    if re.search(r"(?:由谁|谁|哪一方|哪个主体)\s*$", before_action):
        return True
    return (
        _EVIDENCE_FACTUAL_HISTORY_RE.search(before_action) is not None
        or _EVIDENCE_FACTUAL_ACTOR_QUERY_RE.search(before_action) is not None
    )


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_is_evidence_material_request` 仅识别要求在接待室传递证据材料的明确指令；关键协作调用：`strip`、`search`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 协作调用 `strip`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付，同时保留正常事实澄清。
def _is_evidence_material_request(value: Any) -> bool:
    text = str(value or "").strip()
    if not text:
        return False
    for clause in re.split(r"[，,。！？?；;\n]+", text):
        evidence_object = _EVIDENCE_TRANSFER_OBJECT_RE.search(clause)
        if evidence_object is None:
            continue

        action = _EVIDENCE_TRANSFER_ACTION_RE.search(clause)
        # 先识别接待官当下向用户发出的交付指令。这样“请上传商家称需要的
        # 物流凭证”不会因修饰语中的归属转述而放行。
        if _is_current_evidence_transfer_instruction(clause, action):
            return True
        # 转述商家/任一当事人对材料的要求，或询问已经提供的材料内容，都是
        # 事实澄清而非接待官要求当前用户交付，不能覆盖已流式生成的话术。
        if _is_factual_evidence_reference(clause, action):
            continue

        # “还需要物流凭证”“必须提供发票”均是要求把材料补入当前房间的
        # 明确义务；前者即便省略交付动作，也不能作为普通事实澄清放行。
        if _EVIDENCE_TRANSFER_OBLIGATION_RE.search(clause):
            return True

        if action is None:
            continue

        # 「订单确认稿具体是哪个版本的沟通记录或文件」这类事实澄清会提到
        # 材料名称，但没有要求用户把材料传入接待室，必须原样保留。
        if _EVIDENCE_TRANSFER_REQUEST_CUE_RE.search(clause):
            return True

        # “把/将材料发送、上传”本身构成明确的交付指令，即使省略了“请”。
        if re.search(
            r"(?:把|将).{0,80}(?:上传|补交|提供|提交|发送|发来|附上|出示|发给|发至|寄给|分享|共享|"
            r"\b(?:send|email|share)\b)",
            clause,
            re.IGNORECASE,
        ):
            return True
    return False


# 所属模块：接待室 Agent > 接待卷宗确定性整理；函数角色：模块私有业务函数。
# 具体功能：`_question_for_missing` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`join`、`questions.get`。
# 上下游：上游为 表单、当前参与方私聊、上一版卷宗；下游为 本文件的 `_human_field_label`。
# 系统意义：该函数在系统中的业务边界是：只建档追问，不收正式证据、不定责、不承诺赔付。
def _question_for_missing(missing: list[str]) -> str:
    questions = {
        "ORDER_REFERENCE": "请补充订单号或平台可识别的订单引用。",
        "LOGISTICS_REFERENCE": "请补充物流单号或平台可识别的物流引用。",
        "REQUESTED_RESOLUTION": "请明确希望获得的处理方式。",
    }
    return " ".join(
        questions.get(field, f"请补充{_human_field_label(field)}。")
        for field in missing
    )


def _question_for_quality_gap(breakdown: dict[str, int]) -> str:
    questions = {
        "references": "请继续补充可核验的业务引用。",
        "event_story": "请继续补充可核验的事件经过。",
        "party_positions": "请继续补充当事方的已知立场。",
        "requested_resolution": "请明确希望获得的处理方式。",
        "risk_and_conflicts": "请继续补充需要核验的争议事实。",
        "next_action_clarity": "请继续补充下一步需要核验的事项。",
    }
    for component, maximum in _QUALITY_SCORE_COMPONENT_MAXIMA.items():
        if breakdown.get(component, 0) < maximum:
            return questions[component]
    return "请继续补充可核验的案件事实。"
