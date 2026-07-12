"""Strict contracts for the six final AI Native agent roles."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.harness.invocation_context import AgentInvocationContext
from app.schemas.models import (
    Confidence,
    EvidenceItem,
    Identifier,
    LongText,
    PartyClaim,
    PolicyCandidate,
    ShortText,
    StrictModel,
)


class DisputeIntakeRequest(StrictModel):
    submission_id: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    raw_text: LongText
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    attachments: Annotated[list[Identifier], Field(max_length=30)] = Field(
        default_factory=list
    )
    channel: Identifier


class IntakeClaim(StrictModel):
    party: Literal["USER", "MERCHANT"]
    claim_text: LongText
    source_ref: Identifier


class DisputeIntakeResult(StrictModel):
    admissible: bool
    initiator_role: Literal["USER", "MERCHANT"]
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    party_claims: Annotated[list[IntakeClaim], Field(min_length=1, max_length=20)]
    requested_outcome: Literal[
        "REFUND",
        "REPLACEMENT",
        "RETURN",
        "REJECT_REFUND",
        "OTHER",
        "UNKNOWN",
    ]
    initial_risk_signals: list[Identifier] = Field(default_factory=list)
    admission_recommendation: Literal[
        "ACCEPTED",
        "NEED_MORE_INFO",
        "NOT_ADMISSIBLE",
    ]
    dispute_type: Identifier | None = None
    missing_initial_fields: list[Identifier] = Field(default_factory=list)
    confidence: Annotated[float, Field(ge=0, le=1)]
    next_step: Literal[
        "BUILD_DOSSIER",
        "REQUEST_MORE_INFO",
        "TRANSFER",
    ]
    room_utterance: LongText
    liability_determined: Literal[False] = False
    remedy_promised: Literal[False] = False

    @property
    def is_potential_dispute(self) -> bool:
        """Compatibility view for callers migrating to ``admissible``."""

        return self.admissible

    @property
    def admissibility_recommendation(self) -> str:
        return self.admission_recommendation

    @property
    def initiator(self) -> str:
        return self.initiator_role

    @property
    def claims(self) -> list[IntakeClaim]:
        return self.party_claims

    @property
    def requested_remedy(self) -> str:
        return self.requested_outcome

    @property
    def risk_signals(self) -> list[Identifier]:
        return self.initial_risk_signals


RiskLevelLiteral = Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
RawTransportText = Annotated[
    str,
    Field(min_length=1, max_length=2_000_000),
]
RawReference = Annotated[
    str,
    Field(min_length=1, max_length=128),
]


class SimulatedExternalImportRequest(StrictModel):
    count: Annotated[int, Field(ge=1, le=1)] = 1
    scenario: ShortText = "履约争议订单"
    risk_level_hint: RiskLevelLiteral | None = "MEDIUM"
    initiator_role_hint: Literal["USER", "MERCHANT"]
    current_actor_id: Identifier
    counterparty_actor_id: Identifier
    simulation_batch_id: Identifier | None = None

    @model_validator(mode="after")
    def validate_fixed_demo_parties(self) -> "SimulatedExternalImportRequest":
        expected_current = (
            "user-local"
            if self.initiator_role_hint == "USER"
            else "merchant-local"
        )
        expected_counterparty = (
            "merchant-local"
            if self.initiator_role_hint == "USER"
            else "user-local"
        )
        if self.current_actor_id != expected_current:
            raise ValueError(f"current_actor_id must be {expected_current}")
        if self.counterparty_actor_id != expected_counterparty:
            raise ValueError(
                f"counterparty_actor_id must be {expected_counterparty}"
            )
        return self


class SimulatedExternalDispute(StrictModel):
    source_system: Identifier = "LLM_SIMULATED_OMS"
    external_case_reference: Identifier
    order_reference: Identifier
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    user_id: Identifier
    merchant_id: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    dispute_type: Identifier
    title: ShortText
    description: LongText
    risk_level: RiskLevelLiteral


class SimulatedExternalImportResult(StrictModel):
    items: Annotated[
        list[SimulatedExternalDispute],
        Field(min_length=1, max_length=1),
    ]


class IntakeClaimResolutionSeed(StrictModel):
    initiator_role: Literal["USER", "MERCHANT"] | None = None
    requested_resolution: Identifier | None = None
    requested_amount: float | None = None
    requested_items: ShortText | None = None
    request_reason: LongText | None = None
    original_statement: LongText | None = None


class IntakeRespondentAttitudeSeed(StrictModel):
    """Optional extraction hint from the initiator's unilateral statement.

    The intake workflow ignores seeds carrying formal/external provenance. A
    non-empty attitude is only eligible when ``source`` is exactly
    ``发起方单方陈述（主观）``.
    """

    respondent_role: Literal["USER", "MERCHANT"] | None = None
    attitude: Identifier | None = None
    position: LongText | None = None
    source: ShortText | None = Field(
        default=None,
        description="仅允许表示发起方单方陈述（主观）的提取来源。",
    )
    confidence: Confidence | None = Field(
        default=None,
        description="仅表示主观态度提取的明确度，不表示真实性。",
    )


class IntakeLobbySeed(StrictModel):
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    initiator_role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM"] = (
        "USER"
    )
    raw_text: LongText
    requested_outcome_hint: Identifier | None = None
    claim_resolution_seed: IntakeClaimResolutionSeed | None = None
    respondent_attitude_seed: IntakeRespondentAttitudeSeed | None = None


class IntakeTurnMessage(StrictModel):
    message_id: Identifier
    role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM", "AGENT"]
    text: LongText


class IntakeDialogueMessage(StrictModel):
    message_id: Identifier
    sequence_no: Annotated[int, Field(ge=1)]
    role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM", "AGENT"]
    source: Literal[
        "EXTERNAL_IMPORT", "FORM_SUBMISSION", "ROOM_MESSAGE", "AGENT_RESPONSE"
    ]
    text: LongText


class IntakeInitialCaseFacts(StrictModel):
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    initiator_role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM"] = (
        "USER"
    )
    requested_outcome_hint: Identifier | None = None
    claim_resolution_seed: IntakeClaimResolutionSeed | None = None
    respondent_attitude_seed: IntakeRespondentAttitudeSeed | None = None


class IntakeTurnRequest(StrictModel):
    case_id: Annotated[
        str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")
    ]
    room_type: Literal["INTAKE"]
    turn_source: Literal["EXTERNAL_IMPORT", "FORM_SUBMISSION", "ROOM_MESSAGE"]
    initial_case_facts: IntakeInitialCaseFacts
    current_user_message: IntakeDialogueMessage
    recent_dialogue_messages: Annotated[
        list[IntakeDialogueMessage], Field(max_length=6)
    ] = Field(default_factory=list)
    previous_case_detail: dict[str, object] | None = None
    initiator_statement_transcript: list[IntakeTurnMessage] = Field(
        default_factory=list
    )
    agent_context: AgentInvocationContext

    @model_validator(mode="after")
    def enforce_agent_context_scope(self) -> "IntakeTurnRequest":
        if self.case_id != self.agent_context.case_id:
            raise ValueError("case_id must match agent_context.case_id")
        if self.room_type != self.agent_context.room_type:
            raise ValueError("room_type must match agent_context.room_type")
        if self.current_user_message.source != self.turn_source:
            raise ValueError("current_user_message.source must match turn_source")
        if any(
            message.sequence_no >= self.current_user_message.sequence_no
            for message in self.recent_dialogue_messages
        ):
            raise ValueError(
                "recent_dialogue_messages must contain only messages before the current message"
            )
        if self.recent_dialogue_messages != sorted(
            self.recent_dialogue_messages, key=lambda message: message.sequence_no
        ):
            raise ValueError("recent_dialogue_messages must be ordered by sequence_no")
        if (
            self.recent_dialogue_messages
            and self.recent_dialogue_messages[0].role == "AGENT"
        ):
            raise ValueError("recent_dialogue_messages must start with a participant message")
        return self


class IntakeTurnResult(StrictModel):
    room_utterance: LongText
    dossier_patch: dict[str, object]
    scroll_snapshot: dict[str, object]
    canvas_operations: Annotated[list[dict[str, object]], Field(max_length=100)]
    memory_frame: dict[str, object] = Field(default_factory=dict)
    admission_recommendation: Literal[
        "ACCEPTED",
        "NEED_MORE_INFO",
        "NOT_ADMISSIBLE",
    ]
    missing_fields: list[Identifier] = Field(default_factory=list)
    knowledge_query_intent: bool = False
    knowledge_answer_mode: Literal["NONE", "STUB"] = "NONE"
    confidence: Annotated[float, Field(ge=0, le=1)]


class EvidenceTurnMessage(StrictModel):
    message_id: Identifier
    role: Literal["USER", "MERCHANT"]
    message_type: Identifier | None = None
    text: LongText
    attachment_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )


class EvidenceCaseSnapshotV1(StrictModel):
    """Raw, trusted case data supplied by the Java business boundary."""

    case_id: Annotated[str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
    case_version: Annotated[int, Field(ge=0)]
    case_status: Identifier
    case_type: Identifier
    dispute_type: Identifier | None
    title: ShortText
    description: RawTransportText
    risk_level: Identifier
    route_type: Identifier | None
    order_id: RawReference | None
    after_sale_id: RawReference | None
    logistics_id: RawReference | None
    source_type: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    source_system: RawReference | None
    external_case_ref: RawReference | None
    current_room: Identifier | None
    current_deadline_at: ShortText | None


class EvidenceIntakeDossierSnapshotV1(StrictModel):
    dossier_id: Identifier
    schema_version: Identifier | None
    dossier_version: Annotated[int, Field(ge=1)]
    source_turn_no: Annotated[int, Field(ge=1)]
    quality_score: Annotated[int, Field(ge=0, le=100)]
    ready_for_next_step: bool
    admission_recommendation: Identifier
    updated_at: ShortText
    payload: dict[str, object]


class EvidenceActorSnapshotV1(StrictModel):
    actor_id: Identifier
    actor_role: Literal["USER", "MERCHANT"]
    initiator_role: Literal["USER", "MERCHANT"]
    access_session_id: Identifier
    agent_session_id: Identifier
    conversation_scope: Annotated[str, Field(min_length=10, max_length=512)]
    prompt_profile_id: Identifier
    memory_policy_id: Identifier


class EvidenceCurrentEventV1(StrictModel):
    event_id: Identifier
    event_type: Literal["ROOM_OPENING", "PARTY_MESSAGE"]
    message_type: Literal[
        "AGENT_MESSAGE",
        "PARTY_TEXT",
        "PARTY_EVIDENCE_REFERENCE",
    ]
    actor_id: Identifier
    actor_role: Literal["USER", "MERCHANT"]
    text: Annotated[str, Field(max_length=2_000_000)] | None
    attachment_refs: Annotated[list[Identifier], Field(max_length=50)]
    turn_no: Annotated[int, Field(ge=1)]
    occurred_at: ShortText

    @model_validator(mode="after")
    def validate_event_shape(self) -> "EvidenceCurrentEventV1":
        if self.event_type == "ROOM_OPENING":
            if self.message_type != "AGENT_MESSAGE":
                raise ValueError("ROOM_OPENING message_type must be AGENT_MESSAGE")
            if self.text is not None:
                raise ValueError("ROOM_OPENING text must be null")
            if self.attachment_refs:
                raise ValueError("ROOM_OPENING attachment_refs must be empty")
            return self
        if self.message_type not in {"PARTY_TEXT", "PARTY_EVIDENCE_REFERENCE"}:
            raise ValueError(
                "PARTY_MESSAGE message_type must be PARTY_TEXT or "
                "PARTY_EVIDENCE_REFERENCE"
            )
        if not (self.text and self.text.strip()) and not self.attachment_refs:
            raise ValueError("PARTY_MESSAGE requires text or attachment_refs")
        return self


class EvidenceVisibleEvidenceItemV1(StrictModel):
    evidence_id: Identifier
    dossier_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    submitted_by_role: Literal[
        "USER",
        "MERCHANT",
        "PLATFORM",
        "CUSTOMER_SERVICE",
    ]
    submitted_by_id: Identifier
    original_filename: ShortText | None
    content_type: ShortText | None
    file_size: Annotated[int, Field(ge=0)] | None
    file_hash: ShortText | None
    parsed_text: RawTransportText | None
    parse_status: Identifier
    visibility: Identifier
    desensitized: bool
    metadata: dict[str, object]
    extraction: dict[str, object]
    occurred_at: ShortText | None
    created_at: ShortText
    submitted_at: ShortText | None
    submission_status: Literal["SUBMITTED"]
    submission_batch_id: Identifier | None
    content_url: ShortText


class EvidencePrivateConversationTurnV1(StrictModel):
    turn_no: Annotated[int, Field(ge=0)]
    actor_id: Identifier | None
    answer_role: Identifier | None
    answer_content: RawTransportText | None
    agent_role: Identifier | None
    agent_response: RawTransportText | None
    scroll_snapshot: dict[str, object]
    agent_session_id: Identifier
    conversation_scope: Annotated[str, Field(min_length=10, max_length=512)]


class EvidencePrivateConversationV1(StrictModel):
    agent_session_id: Identifier
    conversation_scope: Annotated[str, Field(min_length=10, max_length=512)]
    source_count: Annotated[int, Field(ge=0)]
    truncated: bool
    recent_turns: Annotated[
        list[EvidencePrivateConversationTurnV1],
        Field(max_length=20),
    ]


class EvidenceRoomPolicyV1(StrictModel):
    room_id: Identifier
    room_type: Literal["EVIDENCE"]
    room_status: Identifier
    current_deadline_at: ShortText | None
    initiator_role: Literal["USER", "MERCHANT"]
    initiator_evidence_required: bool


class EvidenceContextEnvelopeV1(StrictModel):
    """Versioned Java-to-Harness evidence context boundary."""

    schema_version: Literal["evidence_context_envelope.v1"]
    captured_at: ShortText
    case_snapshot: EvidenceCaseSnapshotV1
    intake_dossier_snapshot: EvidenceIntakeDossierSnapshotV1 | None
    actor_snapshot: EvidenceActorSnapshotV1
    current_event: EvidenceCurrentEventV1
    visible_evidence: list[EvidenceVisibleEvidenceItemV1]
    private_conversation: EvidencePrivateConversationV1
    room_policy: EvidenceRoomPolicyV1

    @model_validator(mode="after")
    def validate_visible_evidence_scope(self) -> "EvidenceContextEnvelopeV1":
        actor = self.actor_snapshot
        visible_ids: set[str] = set()
        for item in self.visible_evidence:
            if item.submitted_by_role != actor.actor_role:
                raise ValueError(
                    "visible_evidence submitted_by_role must match "
                    "actor_snapshot.actor_role"
                )
            if item.submitted_by_id != actor.actor_id:
                raise ValueError(
                    "visible_evidence submitted_by_id must match "
                    "actor_snapshot.actor_id"
                )
            if item.evidence_id in visible_ids:
                raise ValueError("visible_evidence evidence_id values must be unique")
            visible_ids.add(item.evidence_id)
        unknown_attachments = set(self.current_event.attachment_refs) - visible_ids
        if unknown_attachments:
            raise ValueError(
                "current_event.attachment_refs must reference visible_evidence"
            )
        return self


class EvidenceTurnEvidenceItem(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    content: LongText
    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    occurred_at: ShortText | None = None
    related_claim_ids: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )
    parser_warning: ShortText | None = None
    submitted_by_role: Literal["USER", "MERCHANT", "PLATFORM", "CUSTOMER_SERVICE"] | None = None
    visibility: Identifier | None = None
    content_url: ShortText | None = None
    content_type: ShortText | None = None
    parse_status: Identifier | None = None
    original_filename: ShortText | None = None
    redacted: bool = False


class EvidenceTurnQuestion(StrictModel):
    question_id: Identifier
    target_evidence_id: Identifier | None = None
    question: LongText
    reason: ShortText


class EvidenceVerificationSuggestion(StrictModel):
    evidence_id: Identifier
    suggestion: LongText
    confidence_score: Annotated[
        float,
        Field(
            ge=0,
            le=1,
            description="0.0-1.0 confidence score for this evidence-verification suggestion.",
        ),
    ]


class EvidenceAuthenticityFlag(StrictModel):
    evidence_id: Identifier | None = None
    flag_type: Identifier
    description: ShortText
    severity: Literal["LOW", "MEDIUM", "HIGH"] = "LOW"


class EvidenceFactLink(StrictModel):
    fact_id: Identifier
    relation: Literal["SUPPORTS", "OPPOSES", "INCONCLUSIVE"]
    reason: ShortText
    confidence: Confidence


class EvidenceVisualFinding(StrictModel):
    finding_type: Identifier
    description: ShortText
    visual_region: ShortText | None = None


class EvidenceRiskFlag(StrictModel):
    code: Identifier
    severity: Literal["LOW", "MEDIUM", "HIGH"]
    description: ShortText


class EvidenceHumanReviewSignal(StrictModel):
    required: bool = False
    reason_codes: Annotated[list[Identifier], Field(max_length=20)] = Field(
        default_factory=list
    )
    instructions: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )


class EvidenceItemAssessment(StrictModel):
    """Auditable, per-evidence model assessment; never a truth guarantee."""

    evidence_id: Identifier
    analysis_method: Literal["TEXT_ONLY", "MULTIMODAL", "HYBRID"]
    inspected_modalities: Annotated[list[Identifier], Field(max_length=10)] = Field(
        default_factory=list
    )
    fact_links: Annotated[list[EvidenceFactLink], Field(max_length=50)] = Field(
        default_factory=list
    )
    authenticity_score: Confidence
    relevance_score: Confidence
    completeness_score: Confidence
    assessment_confidence: Confidence
    findings: Annotated[list[EvidenceVisualFinding], Field(max_length=30)] = Field(
        default_factory=list
    )
    limitations: Annotated[list[ShortText], Field(max_length=30)] = Field(
        default_factory=list
    )
    risk_flags: Annotated[list[EvidenceRiskFlag], Field(max_length=30)] = Field(
        default_factory=list
    )
    recommendation: Literal[
        "PLAUSIBLE",
        "SUSPICIOUS",
        "NEEDS_HUMAN_REVIEW",
    ]
    human_review: EvidenceHumanReviewSignal = Field(
        default_factory=EvidenceHumanReviewSignal
    )
    asset_audit: dict[str, object] = Field(default_factory=dict)
    summary: LongText


class EvidenceTurnLlmOutput(StrictModel):
    room_utterance: LongText
    evidence_requests: Annotated[
        list[EvidenceTurnQuestion],
        Field(max_length=30),
    ] = Field(default_factory=list)
    verification_suggestions: Annotated[
        list[EvidenceVerificationSuggestion],
        Field(max_length=100),
    ] = Field(default_factory=list)
    authenticity_flags: Annotated[
        list[EvidenceAuthenticityFlag],
        Field(max_length=100),
    ] = Field(default_factory=list)
    evidence_assessments: Annotated[
        list[EvidenceItemAssessment],
        Field(max_length=50),
    ] = Field(default_factory=list)
    confidence: Confidence


class EvidenceTurnRequest(StrictModel):
    context_envelope: EvidenceContextEnvelopeV1
    agent_context: AgentInvocationContext

    @model_validator(mode="after")
    def enforce_agent_context_scope(self) -> "EvidenceTurnRequest":
        self._validate_context_envelope_scope()
        return self

    def _validate_context_envelope_scope(self) -> None:
        envelope = self.context_envelope
        case_snapshot = envelope.case_snapshot
        actor_snapshot = envelope.actor_snapshot
        current_event = envelope.current_event
        conversation = envelope.private_conversation
        room_policy = envelope.room_policy
        context = self.agent_context

        if context.agent_key != "EVIDENCE_CLERK":
            raise ValueError("agent_context.agent_key must be EVIDENCE_CLERK")
        if context.scope_type != "EVIDENCE_PARTY_PRIVATE":
            raise ValueError(
                "agent_context.scope_type must be EVIDENCE_PARTY_PRIVATE"
            )
        if case_snapshot.case_id != context.case_id:
            raise ValueError(
                "context_envelope.case_snapshot.case_id must match "
                "agent_context.case_id"
            )
        if room_policy.room_type != context.room_type:
            raise ValueError(
                "context_envelope.room_policy.room_type must match "
                "agent_context.room_type"
            )
        if (
            case_snapshot.current_room is not None
            and case_snapshot.current_room != room_policy.room_type
        ):
            raise ValueError(
                "context_envelope.case_snapshot.current_room must match "
                "room_policy.room_type"
            )
        if (
            case_snapshot.current_deadline_at is not None
            and room_policy.current_deadline_at is not None
            and case_snapshot.current_deadline_at != room_policy.current_deadline_at
        ):
            raise ValueError(
                "context_envelope case and room deadline snapshots must match"
            )
        if actor_snapshot.actor_id != context.actor_id:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_id must match agent_context.actor_id"
            )
        if actor_snapshot.actor_role != context.actor_role:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_role must match "
                "agent_context.actor_role"
            )
        if actor_snapshot.actor_id not in context.allowed_actor_ids:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_id must be present in "
                "agent_context.allowed_actor_ids"
            )
        if actor_snapshot.actor_role not in context.allowed_actor_roles:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_role must be present in "
                "agent_context.allowed_actor_roles"
            )
        expected_permission = (
            "PARTY_USER" if actor_snapshot.actor_role == "USER" else "PARTY_MERCHANT"
        )
        if context.permission_level != expected_permission:
            raise ValueError(
                "agent_context.permission_level must match actor_snapshot.actor_role"
            )
        if actor_snapshot.access_session_id != context.access_session_id:
            raise ValueError(
                "context_envelope.actor_snapshot.access_session_id must match "
                "agent_context.access_session_id"
            )
        if actor_snapshot.agent_session_id != context.agent_session_id:
            raise ValueError(
                "context_envelope.actor_snapshot.agent_session_id must match "
                "agent_context.agent_session_id"
            )
        if actor_snapshot.conversation_scope != context.conversation_scope:
            raise ValueError(
                "context_envelope.actor_snapshot.conversation_scope must match "
                "agent_context.conversation_scope"
            )
        if actor_snapshot.prompt_profile_id != context.prompt_profile_id:
            raise ValueError(
                "context_envelope.actor_snapshot.prompt_profile_id must match "
                "agent_context.prompt_profile_id"
            )
        if actor_snapshot.memory_policy_id != context.memory_policy_id:
            raise ValueError(
                "context_envelope.actor_snapshot.memory_policy_id must match "
                "agent_context.memory_policy_id"
            )
        if current_event.actor_id != actor_snapshot.actor_id:
            raise ValueError(
                "context_envelope.current_event.actor_id must match actor_snapshot.actor_id"
            )
        if current_event.actor_role != actor_snapshot.actor_role:
            raise ValueError(
                "context_envelope.current_event.actor_role must match "
                "actor_snapshot.actor_role"
            )
        if actor_snapshot.initiator_role != case_snapshot.initiator_role:
            raise ValueError(
                "context_envelope.actor_snapshot.initiator_role must match "
                "case_snapshot.initiator_role"
            )
        if room_policy.initiator_role != case_snapshot.initiator_role:
            raise ValueError(
                "context_envelope.room_policy.initiator_role must match "
                "case_snapshot.initiator_role"
            )
        if conversation.agent_session_id != actor_snapshot.agent_session_id:
            raise ValueError(
                "context_envelope.private_conversation.agent_session_id must match "
                "actor_snapshot.agent_session_id"
            )
        if conversation.conversation_scope != actor_snapshot.conversation_scope:
            raise ValueError(
                "context_envelope.private_conversation.conversation_scope must match "
                "actor_snapshot.conversation_scope"
            )
        for turn in conversation.recent_turns:
            if turn.agent_session_id != conversation.agent_session_id:
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns agent_session_id "
                    "must match private_conversation.agent_session_id"
                )
            if turn.conversation_scope != conversation.conversation_scope:
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns "
                    "conversation_scope must match private_conversation.conversation_scope"
                )


class EvidenceTurnResult(EvidenceTurnLlmOutput):
    memory_frame: dict[str, object] = Field(default_factory=dict)
    non_final: Literal[True] = True
    liability_determined: Literal[False] = False
    remedy_recommended: Literal[False] = False


class HearingStage(StrEnum):
    C1_ISSUE_FRAMING = "C1_ISSUE_FRAMING"
    C2_EVIDENCE_GAP = "C2_EVIDENCE_GAP"
    C3_EVIDENCE_REQUEST = "C3_EVIDENCE_REQUEST"
    C4_EVIDENCE_CROSS_CHECK = "C4_EVIDENCE_CROSS_CHECK"
    C5_RULE_APPLICATION = "C5_RULE_APPLICATION"
    C6_DRAFT_GENERATION = "C6_DRAFT_GENERATION"


class HearingStageRequest(StrictModel):
    case_id: Identifier
    workflow_id: Identifier
    user_id: Identifier | None = None
    stage: HearingStage
    dossier_version: Annotated[int, Field(ge=1)]
    claims: Annotated[list[PartyClaim], Field(min_length=1, max_length=50)]
    evidence: Annotated[list[EvidenceItem], Field(max_length=100)] = Field(
        default_factory=list
    )
    policy_candidates: Annotated[list[PolicyCandidate], Field(max_length=30)] = Field(
        default_factory=list
    )
    previous_stage_outputs: dict[str, object] = Field(default_factory=dict)
    evidence_timeout: bool = False
    round_no: Annotated[int, Field(ge=1, le=3)] = 1
    stop_reason: Literal[
        "FACTS_SUFFICIENT",
        "SETTLEMENT_CONFIRMED",
        "MAX_ROUNDS",
        "DEADLINE_EXPIRED",
    ] | None = None
    deadline_expired: bool = False
    round_limit_reached: bool = False
    latest_frozen_dossier_version: Annotated[int, Field(ge=1)] | None = None
    party_absence_flags: dict[Literal["USER", "MERCHANT"], bool] = Field(
        default_factory=dict
    )
    current_settlement_version: Annotated[int, Field(ge=1)] | None = None

    @model_validator(mode="after")
    def require_forced_convergence_context(self) -> "HearingStageRequest":
        if self.stage is not HearingStage.C6_DRAFT_GENERATION:
            return self
        if self.stop_reason is None or self.latest_frozen_dossier_version is None:
            raise ValueError(
                "C6 requires stop_reason and latest_frozen_dossier_version"
            )
        if set(self.party_absence_flags) != {"USER", "MERCHANT"}:
            raise ValueError("C6 requires USER and MERCHANT absence flags")
        if self.latest_frozen_dossier_version != self.dossier_version:
            raise ValueError("C6 must use the latest frozen dossier version")
        if self.stop_reason == "DEADLINE_EXPIRED" and not self.deadline_expired:
            raise ValueError("deadline stop requires deadline_expired=true")
        if self.stop_reason == "MAX_ROUNDS" and not self.round_limit_reached:
            raise ValueError("round-limit stop requires round_limit_reached=true")
        return self


class HearingStageResult(StrictModel):
    case_id: Identifier
    workflow_id: Identifier
    stage: HearingStage
    dossier_version: Annotated[int, Field(ge=1)]
    output: dict[str, object]
    output_schema: Identifier
    prompt_version: Identifier
    model: Identifier
    evidence_gaps: list[ShortText] = Field(default_factory=list)
    uncertainties: list[ShortText] = Field(default_factory=list)
    reviewer_attention: list[ShortText] = Field(default_factory=list)
    recommended_draft: dict[str, object] | None = None
    non_final: Literal[True] = True
    requires_human_review: Literal[True] = True


class HearingRoundPartySubmission(StrictModel):
    participant_role: Literal["USER", "MERCHANT"]
    participant_id: Identifier
    submission_source: Identifier
    submission_json: LongText


class HearingRoundTurnRequest(StrictModel):
    case_id: Annotated[
        str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")
    ]
    workflow_id: Identifier
    order_id: Identifier | None = None
    after_sale_id: Identifier | None = None
    logistics_id: Identifier | None = None
    dispute_type: Identifier | None = None
    title: ShortText
    case_description: LongText
    risk_level: RiskLevelLiteral = "MEDIUM"
    round_no: Annotated[int, Field(ge=1, le=3)]
    dossier_version: Annotated[int, Field(ge=1)]
    final_round: bool = False
    round_status: Identifier
    stop_reason: Identifier | None = None
    round_summary_json: LongText = "{}"
    courtroom_context: dict[str, object] = Field(default_factory=dict)
    party_submissions: Annotated[
        list[HearingRoundPartySubmission],
        Field(max_length=10),
    ] = Field(default_factory=list)


class HearingRoundTurnResult(StrictModel):
    speaker_role: Literal["JUDGE"] = "JUDGE"
    message_text: LongText
    round_summary: LongText
    questions_for_user: Annotated[list[ShortText], Field(max_length=10)] = Field(
        default_factory=list
    )
    questions_for_merchant: Annotated[list[ShortText], Field(max_length=10)] = Field(
        default_factory=list
    )
    court_event_type: Literal[
        "JUDGE_OPENING_READY",
        "JUDGE_NEXT_QUESTIONS_READY",
        "FINAL_DRAFT_REQUIRED",
    ]
    round_no: Annotated[int, Field(ge=1, le=3)]
    next_round_no: Annotated[int, Field(ge=1, le=3)] | None = None
    final_draft_required: bool = False
    review_focus_signal: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )
    prompt_version: Identifier = "hearing-round-turn-v1"
    model: Identifier = "unknown"
    non_final: Literal[True] = True
    requires_human_review: Literal[True] = True


class EvidenceBuildRequest(StrictModel):
    case_id: Identifier
    case_version: Annotated[int, Field(ge=1)]
    submission_version: Annotated[int, Field(ge=1)]
    current_dossier_version: Annotated[int, Field(ge=1)] | None = None
    party_claims: Annotated[list[PartyClaim], Field(max_length=50)] = Field(
        default_factory=list
    )
    evidence: Annotated[list["DossierEvidenceItem"], Field(max_length=200)] = Field(
        default_factory=list
    )


class DossierEvidenceItem(EvidenceItem):
    """Original evidence plus separately stored derived representations."""

    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    occurred_at: ShortText | None = None
    related_claim_ids: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )
    parser_warning: ShortText | None = None


class EvidenceCatalogEntry(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    original_ref: Identifier
    original_content: LongText
    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    is_party_statement: bool


class EvidenceVerificationRecommendation(StrEnum):
    VERIFIED = "VERIFIED"
    PLAUSIBLE = "PLAUSIBLE"
    SUSPICIOUS = "SUSPICIOUS"
    REJECTED = "REJECTED"
    NEEDS_HUMAN_REVIEW = "NEEDS_HUMAN_REVIEW"


class EvidenceVerificationAssessment(StrictModel):
    evidence_ref: Identifier
    recommendation: EvidenceVerificationRecommendation
    reasons: list[ShortText] = Field(default_factory=list)
    visibility_warning: ShortText | None = None
    authenticity_guaranteed: Literal[False] = False


class ClaimIssueEvidenceLink(StrictModel):
    claim_id: Identifier
    issue_id: Identifier | None = None
    evidence_refs: Annotated[list[Identifier], Field(max_length=100)] = Field(
        default_factory=list
    )


class EvidenceTimelineEvent(StrictModel):
    event_id: Identifier
    occurred_at: ShortText
    description: LongText
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class EvidenceDossierResult(StrictModel):
    case_id: Identifier
    dossier_version: Annotated[int, Field(ge=1)]
    timeline: list[EvidenceTimelineEvent] = Field(default_factory=list)
    party_claims: list[PartyClaim] = Field(default_factory=list)
    evidence_catalog: list[EvidenceCatalogEntry] = Field(default_factory=list)
    claim_issue_evidence_matrix: list[ClaimIssueEvidenceLink] = Field(
        default_factory=list
    )
    conflicts: list[ShortText] = Field(default_factory=list)
    gaps: list[ShortText] = Field(default_factory=list)
    duplicate_groups: list[list[Identifier]] = Field(default_factory=list)
    parser_warnings: list[ShortText] = Field(default_factory=list)
    policy_candidates: list[Identifier] = Field(default_factory=list)
    source_citations: list[Identifier] = Field(default_factory=list)
    deterministic_evidence_refs: list[Identifier] = Field(default_factory=list)
    verification_recommendations: list[EvidenceVerificationAssessment] = Field(
        default_factory=list
    )
    visibility_warnings: list[ShortText] = Field(default_factory=list)
    authenticity_guaranteed: Literal[False] = False
    authenticity_disclaimer: Literal[
        "Verification is a risk recommendation, not an authenticity guarantee."
    ] = "Verification is a risk recommendation, not an authenticity guarantee."
    liability_determined: Literal[False] = False
    remedy_recommended: Literal[False] = False


class CriticType(StrEnum):
    EVIDENCE = "EVIDENCE_CRITIC"
    RULE = "RULE_CRITIC"
    RISK = "RISK_CRITIC"
    REMEDY = "REMEDY_CRITIC"
    FAIRNESS = "FAIRNESS_CRITIC"


class CriticSeverity(StrEnum):
    NONE = "NONE"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    BLOCKER = "BLOCKER"


class CriticStatus(StrEnum):
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"


class FrozenDeliberationInput(StrictModel):
    """One immutable version tuple shared by every panel member."""

    case_id: Identifier
    case_snapshot_version: Annotated[int, Field(ge=1)]
    dossier_version: Annotated[int, Field(ge=1)]
    adjudication_draft_version: Annotated[int, Field(ge=1)]
    rule_version: Identifier
    remedy_plan_candidate_version: Annotated[int, Field(ge=1)] | None = None
    frozen_dossier_snapshot: dict[str, object]
    frozen_draft_snapshot: dict[str, object]
    frozen_at_event_sequence: Annotated[int, Field(ge=0)]


class CriticDraft(StrictModel):
    severity: CriticSeverity
    findings: list[ShortText] = Field(default_factory=list)
    blocking_issues: list[Identifier] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    frozen_input_fingerprint: str | None = None


class CriticReport(StrictModel):
    critic: CriticType
    scope: Literal["EVIDENCE", "RULE", "RISK", "REMEDY", "FAIRNESS"]
    status: CriticStatus
    severity: CriticSeverity
    findings: list[ShortText] = Field(default_factory=list)
    blocking_issues: list[Identifier] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    frozen_input_fingerprint: str
    failure_reason: ShortText | None = None
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


class DeliberationRequest(StrictModel):
    frozen_input: FrozenDeliberationInput
    trigger_reasons: Annotated[list[Identifier], Field(min_length=1, max_length=30)]


class DeliberationReport(StrictModel):
    deliberation_id: Identifier
    report_version: Annotated[int, Field(ge=1)] = 1
    panel_result: Literal[
        "NO_MAJOR_OBJECTION",
        "REVISION_REQUIRED",
        "MANUAL_REVIEW_REQUIRED",
    ]
    frozen_input_fingerprint: str
    critic_reports: list[CriticReport]
    trigger_reasons: list[Identifier] = Field(default_factory=list)
    major_risks: list[Identifier] = Field(default_factory=list)
    consensus: list[ShortText] = Field(default_factory=list)
    disagreements: list[ShortText] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    reviewer_attention: list[ShortText] = Field(default_factory=list)
    revision_required: bool
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


class ReviewCopilotRequest(StrictModel):
    review_id: Identifier
    case_id: Identifier
    review_packet_version: Annotated[int, Field(ge=1)]
    reviewer_role: Literal["PLATFORM_REVIEWER"]
    question: LongText
    available_fact_refs: list[Identifier] = Field(default_factory=list)
    available_rule_refs: list[Identifier] = Field(default_factory=list)
    available_draft_refs: list[Identifier] = Field(default_factory=list)
    available_deliberation_refs: list[Identifier] = Field(default_factory=list)
    frozen_packet: dict[str, object] = Field(default_factory=dict)


class ReviewStatement(StrictModel):
    kind: Literal["FACT", "INFERENCE", "SUGGESTION"]
    text: LongText
    refs: list[Identifier] = Field(default_factory=list)


class ReviewCopilotAnswer(StrictModel):
    answer: LongText
    statements: Annotated[list[ReviewStatement], Field(min_length=1, max_length=50)]
    fact_refs: list[Identifier] = Field(default_factory=list)
    rule_refs: list[Identifier] = Field(default_factory=list)
    draft_refs: list[Identifier] = Field(default_factory=list)
    deliberation_refs: list[Identifier] = Field(default_factory=list)
    uncertainties: list[ShortText] = Field(default_factory=list)
    suggested_review_focus: list[ShortText] = Field(default_factory=list)
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False
