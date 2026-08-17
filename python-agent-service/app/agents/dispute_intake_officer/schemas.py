# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

from collections.abc import Mapping
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from typing_extensions import NotRequired, Required, TypedDict

from app.schemas.case_fact_matrix import (
    CaseFactMatrixDeltaV2,
    RespondentClaimDeltaV2,
)
from app.schemas.final_agents import IntakeTurnRequest
from app.schemas.intake_case_matrix import UnilateralCaseMatrixDraftV1


AdmissionRecommendation = Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
KnowledgeAnswerMode = Literal["NONE", "STUB"]
IntakeConversationAction = Literal[
    "ASK_SUBSTANTIVE",
    "INVITE_OPTIONAL_REMARK",
    "ACK_REMARK",
    "ACK_NO_REMARK",
]


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
        return IntakeFreshFormOpeningLlmOutput
    if is_exact_handoff_remark_turn(request):
        return IntakeRemarkAcknowledgementLlmOutput
    if is_exact_respondent_substantive_turn(request):
        return IntakeRespondentSubstantiveLlmOutput
    return IntakeCaseDetailLlmOutput
