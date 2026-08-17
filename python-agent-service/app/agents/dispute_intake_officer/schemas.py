# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from functools import lru_cache
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, create_model, model_validator
from typing_extensions import NotRequired, Required, TypedDict

from app.schemas.case_fact_matrix import (
    CaseFactMatrixDeltaV2,
    RespondentClaimDeltaV2,
)
from app.schemas.final_agents import IntakeTurnRequest
from app.schemas.intake_case_matrix import UnilateralCaseMatrixDraftV1


AdmissionRecommendation = Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
KnowledgeAnswerMode = Literal["NONE", "STUB"]
PartyRole = Literal["USER", "MERCHANT"]
IntakeConversationAction = Literal[
    "ASK_SUBSTANTIVE",
    "INVITE_OPTIONAL_REMARK",
    "ACK_REMARK",
    "ACK_NO_REMARK",
]

INTAKE_ROOM_SECTION_KINDS = (
    "CASE_MATRIX",
    "CASE_STORY",
    "PARTY_POSITIONS",
    "CLAIM_AND_RESPONSE",
    "DISPUTE_FOCUS",
    "VERIFICATION_FOCUS",
    "RISK_ASSESSMENT",
    "MISSING_INFORMATION",
    "HANDOFF_SUMMARY",
    "TURN_EVALUATION",
)


class StrictIntakeRoomModel(BaseModel):
    """Strict provider-facing model for the ordered Intake room stream."""

    model_config = ConfigDict(extra="forbid")


class IntakeRoomSourceBindingV1(StrictIntakeRoomModel):
    schema_version: Literal["respondent-claim-binding.v1"]
    binding_kind: Literal["CURRENT_ACTOR_DIRECT", "NO_DIRECT_POSITION"]
    subject_role: PartyRole | None
    source_quote: Annotated[str, Field(min_length=1, max_length=8_192)] | None
    linked_fact_keys: list[
        Annotated[
            str,
            Field(
                pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$"
            ),
        ]
    ] = Field(max_length=32)

    @model_validator(mode="after")
    def require_complete_binding_shape(self) -> "IntakeRoomSourceBindingV1":
        if self.binding_kind == "CURRENT_ACTOR_DIRECT":
            if self.subject_role is None or self.source_quote is None or not self.linked_fact_keys:
                raise ValueError("a direct respondent binding requires role, quote, and fact keys")
        elif self.subject_role is not None or self.source_quote is not None or self.linked_fact_keys:
            raise ValueError("NO_DIRECT_POSITION cannot carry a direct source binding")
        return self


class IntakeRoomBoundRespondentClaim(RespondentClaimDeltaV2):
    source_binding: IntakeRoomSourceBindingV1

    @model_validator(mode="after")
    def bind_attitude_to_source_kind(self) -> "IntakeRoomBoundRespondentClaim":
        if self.attitude == "NOT_ADDRESSED":
            if self.source_binding.binding_kind != "NO_DIRECT_POSITION":
                raise ValueError("NOT_ADDRESSED requires NO_DIRECT_POSITION")
        elif self.source_binding.binding_kind != "CURRENT_ACTOR_DIRECT":
            raise ValueError("a substantive respondent claim requires a direct binding")
        return self


class IntakeInitiatorRoomMatrixDelta(CaseFactMatrixDeltaV2):
    respondent_claim: None = None


class IntakeRespondentRoomMatrixDelta(CaseFactMatrixDeltaV2):
    respondent_claim: IntakeRoomBoundRespondentClaim


class IntakeRoomCaseStoryValue(StrictIntakeRoomModel):
    title: str = Field(min_length=1, max_length=512)
    one_sentence_summary: str = Field(min_length=1, max_length=20_000)


class IntakeRoomPartyPositionsValue(StrictIntakeRoomModel):
    user_claim: str = Field(max_length=20_000)
    merchant_claim: str = Field(max_length=20_000)
    initiator_position: str = Field(max_length=20_000)
    respondent_position: str = Field(max_length=20_000)
    platform_observation: str = Field(max_length=20_000)


class IntakeRoomClaimResolutionValue(StrictIntakeRoomModel):
    initiator_role: PartyRole
    requested_resolution: Literal[
        "REFUND",
        "RETURN_REFUND",
        "RESHIP",
        "REPLACE_OR_REPAIR",
        "COMPENSATION",
        "CANCEL_ORDER",
        "VERIFY_OR_EXPLAIN_ONLY",
        "OTHER",
        "UNKNOWN",
    ]
    requested_amount: float | None
    requested_items: str | None = Field(max_length=2_000)
    request_reason: str = Field(max_length=20_000)
    normalized_statement: str = Field(min_length=1, max_length=20_000)


class IntakeRoomRespondentAttitudeValue(StrictIntakeRoomModel):
    respondent_role: PartyRole
    source_attribution: Literal[
        "INITIATOR_REPORTED",
        "RESPONDENT_DIRECT",
        "NO_DIRECT_POSITION",
    ]
    attitude: Literal[
        "NOT_RESPONDED",
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
        "PLATFORM_UNKNOWN",
    ]
    position: str = Field(max_length=20_000)
    alternative_proposal: str | None = Field(max_length=20_000)

    @model_validator(mode="after")
    def bind_attitude_to_attribution(self) -> "IntakeRoomRespondentAttitudeValue":
        substantive = self.attitude in {
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
        }
        if self.source_attribution == "NO_DIRECT_POSITION":
            if substantive:
                raise ValueError("a substantive attitude requires an attributed source")
        elif not substantive:
            raise ValueError("an attributed attitude must be substantive")
        return self


class IntakeRoomClaimAndResponseValue(StrictIntakeRoomModel):
    claim_resolution: IntakeRoomClaimResolutionValue
    respondent_attitude: IntakeRoomRespondentAttitudeValue


class IntakeRoomDisputeCoreValue(StrictIntakeRoomModel):
    conflict_type: str = Field(min_length=1, max_length=128)
    core_conflict: str = Field(min_length=1, max_length=20_000)
    facts_in_dispute: list[Annotated[str, Field(min_length=1, max_length=2_000)]] = (
        Field(max_length=12)
    )


class IntakeRoomDisputeFocusValue(StrictIntakeRoomModel):
    core_issue: str = Field(min_length=1, max_length=20_000)
    focus_points: list[Annotated[str, Field(min_length=1, max_length=2_000)]] = (
        Field(max_length=12)
    )


class IntakeRoomDisputeSectionValue(StrictIntakeRoomModel):
    dispute_core_state: IntakeRoomDisputeCoreValue
    dispute_focus: IntakeRoomDisputeFocusValue


class IntakeRoomVerificationFocusValue(StrictIntakeRoomModel):
    items: list[Annotated[str, Field(min_length=1, max_length=2_000)]] = Field(
        max_length=12
    )


class IntakeRoomRiskAssessmentValue(StrictIntakeRoomModel):
    case_grade: Literal["LOW", "MEDIUM", "HIGH", "UNKNOWN"]
    risk_points: list[Annotated[str, Field(min_length=1, max_length=2_000)]] = Field(
        max_length=12
    )
    summary: str = Field(max_length=20_000)


class IntakeRoomMissingInformationValue(StrictIntakeRoomModel):
    blocking_gaps: list[Annotated[str, Field(min_length=1, max_length=2_000)]] = (
        Field(max_length=30)
    )
    nice_to_have_gaps: list[
        Annotated[str, Field(min_length=1, max_length=2_000)]
    ] = Field(max_length=30)
    next_questions: list[Annotated[str, Field(min_length=1, max_length=2_000)]] = (
        Field(max_length=2)
    )


class IntakeRoomHandoffSummaryValue(StrictIntakeRoomModel):
    remark_status: Literal[
        "NOT_READY",
        "WAITING_FOR_REMARK",
        "HAS_REMARKS",
        "NO_EXTRA_REMARKS",
    ]
    latest_remark: str = Field(max_length=8_192)
    instruction: str = Field(max_length=20_000)


class IntakeRoomScoreBreakdown(StrictIntakeRoomModel):
    references: int = Field(ge=0, le=15)
    event_story: int = Field(ge=0, le=20)
    party_positions: int = Field(ge=0, le=20)
    requested_resolution: int = Field(ge=0, le=15)
    risk_and_conflicts: int = Field(ge=0, le=15)
    next_action_clarity: int = Field(ge=0, le=15)


class IntakeRoomTurnEvaluationValue(StrictIntakeRoomModel):
    score_breakdown: IntakeRoomScoreBreakdown
    total_score: int = Field(ge=0, le=100)
    threshold: Literal[85]
    ready_for_next_step: bool
    improvement_reason: str = Field(max_length=20_000)
    admission_recommendation: AdmissionRecommendation
    admission_reasoning: str = Field(max_length=20_000)
    confidence: float = Field(ge=0, le=1)
    conversation_action: IntakeConversationAction
    knowledge_answer_mode: KnowledgeAnswerMode

    @model_validator(mode="after")
    def score_breakdown_matches_total(self) -> "IntakeRoomTurnEvaluationValue":
        if sum(self.score_breakdown.model_dump().values()) != self.total_score:
            raise ValueError("turn evaluation score components must equal total_score")
        return self


class IntakeRoomCaseMatrixSection(StrictIntakeRoomModel):
    sequence: Literal[1]
    kind: Literal["CASE_MATRIX"]
    value: IntakeInitiatorRoomMatrixDelta


class IntakeRespondentRoomCaseMatrixSection(StrictIntakeRoomModel):
    sequence: Literal[1]
    kind: Literal["CASE_MATRIX"]
    value: IntakeRespondentRoomMatrixDelta


class IntakeRoomCaseStorySection(StrictIntakeRoomModel):
    sequence: Literal[2]
    kind: Literal["CASE_STORY"]
    value: IntakeRoomCaseStoryValue


class IntakeRoomPartyPositionsSection(StrictIntakeRoomModel):
    sequence: Literal[3]
    kind: Literal["PARTY_POSITIONS"]
    value: IntakeRoomPartyPositionsValue


class IntakeRoomClaimAndResponseSection(StrictIntakeRoomModel):
    sequence: Literal[4]
    kind: Literal["CLAIM_AND_RESPONSE"]
    value: IntakeRoomClaimAndResponseValue


class IntakeRoomDisputeFocusSection(StrictIntakeRoomModel):
    sequence: Literal[5]
    kind: Literal["DISPUTE_FOCUS"]
    value: IntakeRoomDisputeSectionValue


class IntakeRoomVerificationFocusSection(StrictIntakeRoomModel):
    sequence: Literal[6]
    kind: Literal["VERIFICATION_FOCUS"]
    value: IntakeRoomVerificationFocusValue


class IntakeRoomRiskAssessmentSection(StrictIntakeRoomModel):
    sequence: Literal[7]
    kind: Literal["RISK_ASSESSMENT"]
    value: IntakeRoomRiskAssessmentValue


class IntakeRoomMissingInformationSection(StrictIntakeRoomModel):
    sequence: Literal[8]
    kind: Literal["MISSING_INFORMATION"]
    value: IntakeRoomMissingInformationValue


class IntakeRoomHandoffSummarySection(StrictIntakeRoomModel):
    sequence: Literal[9]
    kind: Literal["HANDOFF_SUMMARY"]
    value: IntakeRoomHandoffSummaryValue


class IntakeRoomTurnEvaluationSection(StrictIntakeRoomModel):
    sequence: Literal[10]
    kind: Literal["TURN_EVALUATION"]
    value: IntakeRoomTurnEvaluationValue


IntakeInitiatorRoomSections = tuple[
    IntakeRoomCaseMatrixSection,
    IntakeRoomCaseStorySection,
    IntakeRoomPartyPositionsSection,
    IntakeRoomClaimAndResponseSection,
    IntakeRoomDisputeFocusSection,
    IntakeRoomVerificationFocusSection,
    IntakeRoomRiskAssessmentSection,
    IntakeRoomMissingInformationSection,
    IntakeRoomHandoffSummarySection,
    IntakeRoomTurnEvaluationSection,
]
IntakeRespondentRoomSections = tuple[
    IntakeRespondentRoomCaseMatrixSection,
    IntakeRoomCaseStorySection,
    IntakeRoomPartyPositionsSection,
    IntakeRoomClaimAndResponseSection,
    IntakeRoomDisputeFocusSection,
    IntakeRoomVerificationFocusSection,
    IntakeRoomRiskAssessmentSection,
    IntakeRoomMissingInformationSection,
    IntakeRoomHandoffSummarySection,
    IntakeRoomTurnEvaluationSection,
]


def _validate_ordered_room_outcome(
    sections: IntakeInitiatorRoomSections | IntakeRespondentRoomSections,
) -> None:
    missing = sections[7].value
    handoff = sections[8].value
    evaluation = sections[9].value
    derived_ready = (
        evaluation.total_score >= evaluation.threshold
        and not missing.blocking_gaps
    )
    if evaluation.ready_for_next_step != derived_ready:
        raise ValueError(
            "ready_for_next_step must follow the rubric score and blocking gaps"
        )
    if evaluation.ready_for_next_step:
        if evaluation.admission_recommendation != "ACCEPTED":
            raise ValueError("a ready turn requires ACCEPTED admission")
        expected_status = {
            "INVITE_OPTIONAL_REMARK": "WAITING_FOR_REMARK",
            "ACK_NO_REMARK": "NO_EXTRA_REMARKS",
        }.get(evaluation.conversation_action)
        if expected_status is None or handoff.remark_status != expected_status:
            raise ValueError("ready turn action and handoff status disagree")
        if missing.next_questions:
            raise ValueError("a ready turn cannot carry another substantive question")
        return
    if (
        evaluation.conversation_action != "ASK_SUBSTANTIVE"
        or handoff.remark_status != "NOT_READY"
        or evaluation.admission_recommendation == "ACCEPTED"
    ):
        raise ValueError("an incomplete turn must remain in substantive Intake")


class IntakeInitiatorRoomLlmOutputV3(StrictIntakeRoomModel):
    """Ordered provider contract for initiator substantive/opening turns."""

    room_utterance: str = Field(min_length=1, max_length=20_000)
    ordered_sections: IntakeInitiatorRoomSections

    @model_validator(mode="after")
    def validate_turn_outcome(self) -> "IntakeInitiatorRoomLlmOutputV3":
        _validate_ordered_room_outcome(self.ordered_sections)
        if (
            self.ordered_sections[3].value.respondent_attitude.source_attribution
            == "RESPONDENT_DIRECT"
        ):
            raise ValueError(
                "an initiator turn cannot create direct respondent attribution"
            )
        return self


class IntakeRespondentRoomLlmOutputV3(StrictIntakeRoomModel):
    """Ordered provider contract for an authenticated respondent turn."""

    room_utterance: str = Field(min_length=1, max_length=20_000)
    ordered_sections: IntakeRespondentRoomSections

    @model_validator(mode="after")
    def bind_display_attitude_to_matrix_claim(
        self,
    ) -> "IntakeRespondentRoomLlmOutputV3":
        _validate_ordered_room_outcome(self.ordered_sections)
        claim = self.ordered_sections[0].value.respondent_claim
        display = self.ordered_sections[3].value.respondent_attitude
        binding = claim.source_binding
        if binding.subject_role is not None and display.respondent_role != binding.subject_role:
            raise ValueError("respondent display role must match the bound claim role")
        if claim.attitude != "NOT_ADDRESSED" and display.attitude != claim.attitude:
            raise ValueError("respondent display attitude must match the bound matrix claim")
        expected_attribution = (
            "NO_DIRECT_POSITION"
            if claim.attitude == "NOT_ADDRESSED"
            else "RESPONDENT_DIRECT"
        )
        if display.source_attribution != expected_attribution:
            raise ValueError(
                "respondent display attribution must match the bound matrix claim"
            )
        return self


_FROZEN_CLAIM_FIELDS = (
    "initiator_role",
    "requested_resolution",
    "requested_amount",
    "requested_items",
    "request_reason",
    "normalized_statement",
)


def _frozen_respondent_claim_authority(
    request: IntakeTurnRequest,
) -> tuple[Any, ...] | None:
    """Return the complete initiator claim already frozen before a respondent turn."""

    previous = request.previous_case_detail
    if not isinstance(previous, Mapping):
        return None
    claim = previous.get("claim_resolution")
    if not isinstance(claim, Mapping) or any(
        field not in claim for field in _FROZEN_CLAIM_FIELDS
    ):
        return None
    try:
        canonical = IntakeRoomClaimResolutionValue.model_validate(
            {field: claim[field] for field in _FROZEN_CLAIM_FIELDS}
        ).model_dump(mode="python")
    except ValueError:
        return None
    return tuple(canonical[field] for field in _FROZEN_CLAIM_FIELDS)


@lru_cache(maxsize=256)
def _respondent_output_type_with_frozen_claim(
    initiator_role: str,
    requested_resolution: str,
    requested_amount: float | None,
    requested_items: str | None,
    request_reason: str,
    normalized_statement: str,
) -> type[BaseModel]:
    """Build a Provider Schema whose cross-party claim is an exact frozen projection."""

    authority = {
        "initiator_role": initiator_role,
        "requested_resolution": requested_resolution,
        "requested_amount": requested_amount,
        "requested_items": requested_items,
        "request_reason": request_reason,
        "normalized_statement": normalized_statement,
    }
    suffix = hashlib.sha256(
        json.dumps(
            authority,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()[:16]
    respondent_role = "MERCHANT" if initiator_role == "USER" else "USER"
    frozen_claim_type = create_model(
        f"IntakeFrozenClaimResolution_{suffix}",
        __base__=IntakeRoomClaimResolutionValue,
        initiator_role=(Literal[initiator_role], ...),
        requested_resolution=(Literal[requested_resolution], ...),
        requested_amount=(Literal[requested_amount], ...),
        requested_items=(Literal[requested_items], ...),
        request_reason=(Literal[request_reason], ...),
        normalized_statement=(Literal[normalized_statement], ...),
    )
    frozen_attitude_type = create_model(
        f"IntakeFrozenRespondentRole_{suffix}",
        __base__=IntakeRoomRespondentAttitudeValue,
        respondent_role=(Literal[respondent_role], ...),
    )
    frozen_claim_and_response_type = create_model(
        f"IntakeFrozenClaimAndResponse_{suffix}",
        __base__=IntakeRoomClaimAndResponseValue,
        claim_resolution=(frozen_claim_type, ...),
        respondent_attitude=(frozen_attitude_type, ...),
    )
    frozen_claim_section_type = create_model(
        f"IntakeFrozenClaimAndResponseSection_{suffix}",
        __base__=IntakeRoomClaimAndResponseSection,
        value=(frozen_claim_and_response_type, ...),
    )
    ordered_sections_type = tuple[
        IntakeRespondentRoomCaseMatrixSection,
        IntakeRoomCaseStorySection,
        IntakeRoomPartyPositionsSection,
        frozen_claim_section_type,
        IntakeRoomDisputeFocusSection,
        IntakeRoomVerificationFocusSection,
        IntakeRoomRiskAssessmentSection,
        IntakeRoomMissingInformationSection,
        IntakeRoomHandoffSummarySection,
        IntakeRoomTurnEvaluationSection,
    ]
    return create_model(
        f"IntakeRespondentRoomLlmOutputV3_{suffix}",
        __base__=IntakeRespondentRoomLlmOutputV3,
        ordered_sections=(ordered_sections_type, ...),
    )


class IntakeCaseStoryPatch(TypedDict, total=False):
    """Model-authored case-story fields that may change in one intake turn."""

    title: NotRequired[str]
    one_sentence_summary: Required[
        Annotated[str, Field(min_length=1, max_length=20_000)]
    ]
    event_timeline: NotRequired[list[dict[str, Any]]]


class IntakeCaseDetailPatch(TypedDict, total=False):
    """Incremental case-detail branches accepted from the intake model."""

    schema_version: NotRequired[Literal["intake_case_detail.v1"]]
    case_story: NotRequired[IntakeCaseStoryPatch]
    references: NotRequired[dict[str, Any]]
    party_positions: NotRequired[dict[str, Any]]
    dispute_focus: NotRequired[dict[str, Any]]
    requested_resolution: NotRequired[dict[str, Any]]
    claim_resolution: NotRequired[dict[str, Any]]
    respondent_attitude: NotRequired[dict[str, Any]]
    dispute_core_state: NotRequired[dict[str, Any]]
    risk_assessment: NotRequired[dict[str, Any]]
    missing_information: NotRequired[dict[str, Any]]
    intake_quality: NotRequired[dict[str, Any]]
    admission: NotRequired[dict[str, Any]]
    handoff_notes: NotRequired[dict[str, Any]]
    case_fact_matrix: NotRequired[dict[str, Any]]
    unilateral_case_matrix: NotRequired[dict[str, Any]]


class IntakeFreshFormCaseStory(BaseModel):
    """Only the form-derived summary may cross the fresh-opening provider boundary."""

    model_config = ConfigDict(extra="forbid")

    one_sentence_summary: Annotated[
        str, Field(min_length=1, max_length=20_000)
    ]


class IntakeFreshFormCaseDetail(BaseModel):
    """Closed fresh-form detail without full-dossier provider authority."""

    model_config = ConfigDict(extra="forbid")

    case_story: IntakeFreshFormCaseStory


class IntakeRespondentOpeningLlmOutput(BaseModel):
    """Provider fields owned by an authenticated respondent-opening turn."""

    model_config = ConfigDict(extra="ignore")

    room_utterance: str = Field(min_length=1, max_length=20_000)
    confidence: float = Field(ge=0, le=1)


class IntakeRemarkAcknowledgementLlmOutput(BaseModel):
    """Provider fields owned by an authorized optional-remark turn."""

    model_config = ConfigDict(extra="forbid")

    conversation_action: Literal["ACK_REMARK", "ACK_NO_REMARK"]
    room_utterance: str = Field(min_length=1, max_length=20_000)
    confidence: float = Field(ge=0, le=1)


class IntakeCaseDetailLlmOutput(BaseModel):
    """争议接待官大模型节点生成的结构化输出。"""

    model_config = ConfigDict(extra="forbid")

    conversation_action: IntakeConversationAction
    room_utterance: str = Field(min_length=1, max_length=20_000)
    case_detail: IntakeCaseDetailPatch
    case_matrix_delta: CaseFactMatrixDeltaV2 | None = None
    unilateral_case_matrix: UnilateralCaseMatrixDraftV1 | None = None
    dossier_patch: dict[str, Any] | None = None
    scroll_snapshot: dict[str, Any] | None = None
    canvas_operations: list[dict[str, Any]] = Field(default_factory=list, max_length=100)
    admission_recommendation: AdmissionRecommendation = "NEED_MORE_INFO"
    missing_fields: list[str] = Field(default_factory=list, max_length=30)
    knowledge_query_intent: bool = False
    knowledge_answer_mode: KnowledgeAnswerMode = "NONE"
    confidence: float = Field(default=0.0, ge=0, le=1)

    @model_validator(mode="after")
    def require_complete_case_summary(self) -> "IntakeCaseDetailLlmOutput":
        """每轮模型输出都必须生成新的累计事件摘要。"""

        if not isinstance(self.case_detail, dict):
            raise ValueError("case_detail is required")
        story = self.case_detail.get("case_story")
        summary = (
            str(story.get("one_sentence_summary") or "").strip()
            if isinstance(story, dict)
            else ""
        )
        if self.conversation_action in {
            "ASK_SUBSTANTIVE",
            "INVITE_OPTIONAL_REMARK",
        } and not summary:
            raise ValueError(
                "case_detail.case_story.one_sentence_summary is required"
            )
        if (
            self.conversation_action
            in {"ASK_SUBSTANTIVE", "INVITE_OPTIONAL_REMARK"}
            and self.case_matrix_delta is None
            and self.unilateral_case_matrix is None
        ):
            raise ValueError("case_matrix_delta is required")
        # ACK matrix material is parsed but remains non-authoritative; the
        # frozen-handoff reducer discards it only after exact source validation.
        if self.case_matrix_delta is not None and self.unilateral_case_matrix is not None:
            raise ValueError("provide only case_matrix_delta")
        return self


class MaterializedIntakeRoomLlmOutputV3(IntakeCaseDetailLlmOutput):
    """Internal legacy-shaped projection of the ordered provider contract.

    The two excluded fields are carried only long enough for the Target/baseline
    adapter to select the model-trusted V3 projection path. They never enter the
    durable model-authored dossier or the public terminal proposal.
    """

    source_contract_version: Literal["intake-room-output.v3"] = Field(exclude=True)
    ordered_sections_payload: tuple[dict[str, Any], ...] = Field(exclude=True)
    respondent_source_binding: IntakeRoomSourceBindingV1 | None = Field(
        default=None,
        exclude=True,
    )


class IntakeRespondentSubstantiveMatrixDelta(CaseFactMatrixDeltaV2):
    """Matrix delta whose provider wire schema requires an explicit claim."""

    respondent_claim: RespondentClaimDeltaV2


class IntakeRespondentSubstantiveLlmOutput(IntakeCaseDetailLlmOutput):
    """Provider contract for one authenticated respondent substantive turn."""

    case_matrix_delta: IntakeRespondentSubstantiveMatrixDelta
    unilateral_case_matrix: None = None

    @model_validator(mode="after")
    def require_explicit_respondent_claim(
        self,
    ) -> "IntakeRespondentSubstantiveLlmOutput":
        if self.case_matrix_delta.respondent_claim is None:
            raise ValueError(
                "authenticated respondent substantive output requires respondent_claim"
            )
        return self


class IntakeFreshFormMatrixDelta(CaseFactMatrixDeltaV2):
    """Fresh initiator matrix without authority to classify the respondent."""

    respondent_claim: None = None


class IntakeFreshFormOpeningLlmOutput(BaseModel):
    """Provider fields allowed before any authenticated participant room turn."""

    model_config = ConfigDict(extra="forbid")

    conversation_action: Literal["ASK_SUBSTANTIVE"]
    room_utterance: str = Field(min_length=1, max_length=20_000)
    case_detail: IntakeFreshFormCaseDetail
    case_matrix_delta: IntakeFreshFormMatrixDelta
    missing_fields: list[
        Annotated[str, Field(min_length=1, max_length=128)]
    ] = Field(min_length=1, max_length=30)
    confidence: float = Field(ge=0, le=1)


def is_exact_fresh_form_opening(request: IntakeTurnRequest) -> bool:
    """Select the form-only opening phase from the validated request authority."""

    if not isinstance(request, IntakeTurnRequest):
        raise TypeError("request must be a validated IntakeTurnRequest")
    return (
        request.turn_source in {"EXTERNAL_IMPORT", "FORM_SUBMISSION"}
        and request.initial_case_facts is not None
        and request.current_user_message is None
        and request.previous_case_detail is None
    )


def is_exact_handoff_remark_turn(request: IntakeTurnRequest) -> bool:
    """Select the narrow acknowledgement contract from frozen phase authority."""

    if not isinstance(request, IntakeTurnRequest):
        raise TypeError("request must be a validated IntakeTurnRequest")
    if request.turn_source != "ROOM_MESSAGE" or request.current_user_message is None:
        return False
    actor = request.agent_context.actor_role
    previous = request.previous_case_detail
    if actor not in {"USER", "MERCHANT"} or not isinstance(previous, Mapping):
        return False
    party_state = previous.get("party_intake_state")
    actor_entry = party_state.get(actor) if isinstance(party_state, Mapping) else None
    actor_notes = (
        actor_entry.get("handoff_notes")
        if isinstance(actor_entry, Mapping)
        else None
    )
    mirror_notes = previous.get("handoff_notes")
    quality = previous.get("intake_quality")
    missing = previous.get("missing_information")
    matrix = previous.get("case_fact_matrix")
    partition = previous.get("handoff_remark_partition")
    parties = partition.get("parties") if isinstance(partition, Mapping) else None
    actor_partition = parties.get(actor) if isinstance(parties, Mapping) else None
    if not all(
        isinstance(value, Mapping)
        for value in (
            actor_notes,
            mirror_notes,
            quality,
            missing,
            matrix,
            partition,
            actor_partition,
        )
    ):
        return False
    return (
        actor_notes.get("remark_status") == "WAITING_FOR_REMARK"
        and mirror_notes.get("remark_status") == "WAITING_FOR_REMARK"
        and actor_partition.get("remark_status") == "WAITING_FOR_REMARK"
        and quality.get("ready_for_next_step") is True
        and missing.get("next_questions") == []
        and partition.get("case_fact_matrix_id") == matrix.get("matrix_id")
        and partition.get("case_fact_matrix_version") == matrix.get("matrix_version")
        and partition.get("case_fact_matrix_hash") == matrix.get("content_hash")
    )


def is_exact_respondent_substantive_turn(request: IntakeTurnRequest) -> bool:
    """Select a current respondent message backed by frozen matrix party authority."""

    if not isinstance(request, IntakeTurnRequest):
        raise TypeError("request must be a validated IntakeTurnRequest")
    current = request.current_user_message
    previous = request.previous_case_detail
    actor = request.agent_context.actor_role
    if (
        request.turn_source != "ROOM_MESSAGE"
        or current is None
        or current.source != "ROOM_MESSAGE"
        or current.role != actor
        or actor not in {"USER", "MERCHANT"}
        or not isinstance(previous, Mapping)
        or is_exact_handoff_remark_turn(request)
    ):
        return False
    matrix = previous.get("case_fact_matrix")
    party_map = matrix.get("party_map") if isinstance(matrix, Mapping) else None
    return (
        isinstance(matrix, Mapping)
        and matrix.get("schema_version") == "case_fact_matrix.v2"
        and isinstance(party_map, Mapping)
        and party_map.get("respondent_role") == actor
        and party_map.get("initiator_role") in {"USER", "MERCHANT"}
        and party_map.get("initiator_role") != actor
    )


def materialize_intake_case_detail_output(
    request: IntakeTurnRequest,
    output: BaseModel,
) -> IntakeCaseDetailLlmOutput:
    """Expand a phase-limited provider result before the existing reducer."""

    if not isinstance(request, IntakeTurnRequest):
        raise TypeError("request must be a validated IntakeTurnRequest")
    if isinstance(
        output,
        (IntakeInitiatorRoomLlmOutputV3, IntakeRespondentRoomLlmOutputV3),
    ):
        return _materialize_ordered_intake_room_output(request, output)
    if isinstance(output, IntakeFreshFormOpeningLlmOutput):
        if not is_exact_fresh_form_opening(request):
            raise ValueError("fresh form opening output lacks phase authority")
        return IntakeCaseDetailLlmOutput.model_validate(
            {
                "conversation_action": output.conversation_action,
                "room_utterance": output.room_utterance,
                "case_detail": output.case_detail.model_dump(
                    mode="json",
                    exclude_none=True,
                ),
                "case_matrix_delta": output.case_matrix_delta.model_dump(
                    mode="json",
                    exclude_none=True,
                ),
                "unilateral_case_matrix": None,
                "dossier_patch": None,
                "scroll_snapshot": None,
                "canvas_operations": [],
                "admission_recommendation": "NEED_MORE_INFO",
                "missing_fields": output.missing_fields,
                "knowledge_query_intent": False,
                "knowledge_answer_mode": "NONE",
                "confidence": output.confidence,
            }
        )
    if isinstance(output, IntakeCaseDetailLlmOutput):
        return output
    if not isinstance(output, IntakeRemarkAcknowledgementLlmOutput):
        raise TypeError("unsupported Intake provider output")
    if not is_exact_handoff_remark_turn(request):
        raise ValueError("remark acknowledgement output lacks phase authority")
    return IntakeCaseDetailLlmOutput.model_validate(
        {
            "conversation_action": output.conversation_action,
            "room_utterance": output.room_utterance,
            "case_detail": {},
            "case_matrix_delta": None,
            "unilateral_case_matrix": None,
            "dossier_patch": None,
            "scroll_snapshot": None,
            "canvas_operations": [],
            "admission_recommendation": "ACCEPTED",
            "missing_fields": [],
            "knowledge_query_intent": False,
            "knowledge_answer_mode": "NONE",
            "confidence": output.confidence,
        }
    )


def intake_case_detail_output_type(
    request: IntakeTurnRequest,
) -> type[BaseModel]:
    """Return the provider contract authorized for this exact Intake phase."""

    if is_exact_fresh_form_opening(request):
        return IntakeInitiatorRoomLlmOutputV3
    if is_exact_handoff_remark_turn(request):
        return IntakeRemarkAcknowledgementLlmOutput
    if is_exact_respondent_substantive_turn(request):
        frozen_claim = _frozen_respondent_claim_authority(request)
        if frozen_claim is not None:
            return _respondent_output_type_with_frozen_claim(*frozen_claim)
        return IntakeRespondentRoomLlmOutputV3
    return IntakeInitiatorRoomLlmOutputV3


def _materialize_ordered_intake_room_output(
    request: IntakeTurnRequest,
    output: IntakeInitiatorRoomLlmOutputV3 | IntakeRespondentRoomLlmOutputV3,
) -> MaterializedIntakeRoomLlmOutputV3:
    """Mechanically project ordered sections into the established terminal shape."""

    if isinstance(output, IntakeRespondentRoomLlmOutputV3):
        if not is_exact_respondent_substantive_turn(request):
            raise ValueError("respondent room output lacks respondent phase authority")
    elif is_exact_respondent_substantive_turn(request):
        raise ValueError("respondent turn requires the respondent room output contract")

    sections = output.ordered_sections
    matrix_payload = sections[0].value.model_dump(mode="json", exclude_none=True)
    respondent_claim = matrix_payload.get("respondent_claim")
    respondent_source_binding: IntakeRoomSourceBindingV1 | None = None
    if isinstance(respondent_claim, dict):
        if isinstance(output, IntakeRespondentRoomLlmOutputV3):
            respondent_source_binding = (
                output.ordered_sections[0].value.respondent_claim.source_binding
            )
        respondent_claim.pop("source_binding", None)

    case_story = sections[1].value.model_dump(mode="json")
    party_positions = sections[2].value.model_dump(mode="json")
    claim_and_response = sections[3].value
    dispute = sections[4].value
    verification_focus = sections[5].value
    risk_section = sections[6].value
    risk_assessment = {
        "case_grade": risk_section.case_grade,
        "risk_signals": list(risk_section.risk_points),
        "reasoning": risk_section.summary,
    }
    missing_information = sections[7].value.model_dump(mode="json")
    handoff_summary = sections[8].value.model_dump(mode="json")
    evaluation = sections[9].value

    dispute_core_state = dispute.dispute_core_state.model_dump(mode="json")
    dispute_core_state["next_verification_focus"] = list(verification_focus.items)
    respondent_attitude = claim_and_response.respondent_attitude.model_dump(
        mode="json",
        exclude_none=True,
    )
    # Provider-only attribution selects the trusted source branch below; public
    # dossiers retain the established source/grounding contract instead of a
    # second, model-owned provenance field.
    respondent_attitude.pop("source_attribution", None)
    case_detail = {
        "case_story": case_story,
        "party_positions": party_positions,
        "claim_resolution": claim_and_response.claim_resolution.model_dump(
            mode="json"
        ),
        "respondent_attitude": respondent_attitude,
        "dispute_core_state": dispute_core_state,
        "dispute_focus": dispute.dispute_focus.model_dump(mode="json"),
        "risk_assessment": risk_assessment,
        "missing_information": missing_information,
        "intake_quality": {
            "score": evaluation.total_score,
            "threshold": evaluation.threshold,
            "ready_for_next_step": evaluation.ready_for_next_step,
            "score_breakdown": evaluation.score_breakdown.model_dump(mode="json"),
            "improvement_reason": evaluation.improvement_reason,
        },
        "admission": {
            "recommendation": evaluation.admission_recommendation,
            "reasoning": evaluation.admission_reasoning,
            "confidence": evaluation.confidence,
        },
        "handoff_notes": handoff_summary,
    }
    return MaterializedIntakeRoomLlmOutputV3.model_validate(
        {
            "source_contract_version": "intake-room-output.v3",
            "ordered_sections_payload": tuple(
                section.model_dump(mode="json", exclude_none=True)
                for section in sections
            ),
            "respondent_source_binding": respondent_source_binding,
            "room_utterance": output.room_utterance,
            "conversation_action": evaluation.conversation_action,
            "case_detail": case_detail,
            "case_matrix_delta": matrix_payload,
            "unilateral_case_matrix": None,
            "dossier_patch": None,
            "scroll_snapshot": None,
            "canvas_operations": [],
            "admission_recommendation": evaluation.admission_recommendation,
            "missing_fields": missing_information["blocking_gaps"],
            "knowledge_query_intent": evaluation.knowledge_answer_mode == "STUB",
            "knowledge_answer_mode": evaluation.knowledge_answer_mode,
            "confidence": evaluation.confidence,
        }
    )


def revalidate_materialized_intake_output(
    original: IntakeCaseDetailLlmOutput,
    payload: Mapping[str, Any],
) -> IntakeCaseDetailLlmOutput:
    """Revalidate a legacy-shaped mutation without losing V3 private authority.

    Fact-key canonicalization is server-owned and may rebuild the public matrix
    payload.  The ordered provider contract marker and its private source binding
    must survive that mechanical rebuild so the downstream dossier adapter cannot
    accidentally fall back to legacy regex semantics.
    """

    if not isinstance(original, MaterializedIntakeRoomLlmOutputV3):
        return IntakeCaseDetailLlmOutput.model_validate(payload)
    materialized_payload = dict(payload)
    source_binding = original.respondent_source_binding
    source_binding_payload = (
        source_binding.model_dump(mode="json")
        if source_binding is not None
        else None
    )
    previous_matrix = original.case_matrix_delta
    next_matrix = materialized_payload.get("case_matrix_delta")
    if (
        source_binding_payload is not None
        and previous_matrix is not None
        and isinstance(next_matrix, Mapping)
        and isinstance(next_matrix.get("fact_rows"), list)
    ):
        previous_keys = [row.fact_key for row in previous_matrix.fact_rows]
        next_keys = [
            row.get("fact_key")
            for row in next_matrix["fact_rows"][: len(previous_keys)]
            if isinstance(row, Mapping)
        ]
        if len(previous_keys) == len(next_keys) and all(
            isinstance(key, str) for key in next_keys
        ):
            normalized_by_previous = dict(zip(previous_keys, next_keys, strict=True))
            source_binding_payload["linked_fact_keys"] = [
                normalized_by_previous.get(key, key)
                for key in source_binding_payload["linked_fact_keys"]
            ]
    materialized_payload.update(
        {
            "source_contract_version": original.source_contract_version,
            "ordered_sections_payload": original.ordered_sections_payload,
            "respondent_source_binding": source_binding_payload,
        }
    )
    return MaterializedIntakeRoomLlmOutputV3.model_validate(materialized_payload)
