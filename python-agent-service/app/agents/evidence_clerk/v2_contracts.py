"""Evidence-room v2 business frame contracts.

The provider owns every ``public_text`` byte.  The state machine owns the
deterministic leading-frame header so the provider can emit the first public
string before spending tokens on semantic frame headers.  Remaining frames
keep their source-bound header ahead of their public text.
"""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    TypeAdapter,
    field_serializer,
    model_validator,
)


Identifier = Annotated[
    str,
    Field(
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    ),
]
ShortText = Annotated[str, Field(min_length=1, max_length=1_000)]
ReasonText = Annotated[str, Field(min_length=1, max_length=500)]
PublicText = Annotated[str, Field(min_length=1, max_length=100_000)]

FrameType = Literal[
    "ROOM_WELCOME",
    "OPENING_ORIENTATION",
    "MATERIAL_RECEIPT",
    "TEXT_FOLLOWUP_REPLY",
    "EVIDENCE_OBSERVATION",
    "EVIDENCE_ASSESSMENT",
    "EVIDENCE_REQUEST",
    "HUMAN_REVIEW_TASK",
    "ROOM_READINESS",
]


class EvidenceV2Model(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class EvidenceFactBindingV2(EvidenceV2Model):
    fact_id: Identifier
    relation: Literal[
        "CONTENT_SUPPORTS",
        "CONTENT_CONTRADICTS",
        "CONTEXT_ONLY",
        "INCONCLUSIVE",
    ]
    reason: ReasonText


class EvidenceFrameHeaderBaseV2(EvidenceV2Model):
    frame_sequence: int = Field(ge=1, le=128)


class EvidenceRoomWelcomeFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["ROOM_WELCOME"]


class EvidenceOpeningOrientationFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["OPENING_ORIENTATION"]
    focus_fact_ids: list[Identifier] = Field(min_length=1, max_length=20)


class EvidenceMaterialReceiptFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["MATERIAL_RECEIPT"]
    evidence_ids: list[Identifier] = Field(min_length=1, max_length=50)
    focus_fact_ids: list[Identifier] = Field(default_factory=list, max_length=20)


class EvidenceTextFollowupFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["TEXT_FOLLOWUP_REPLY"]


class EvidenceObservationFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["EVIDENCE_OBSERVATION"]
    observation_slot: Identifier
    source_unit_id: Identifier
    binding_status: Literal["BOUND", "UNRELATED", "AMBIGUOUS"]
    fact_bindings: list[EvidenceFactBindingV2] = Field(default_factory=list, max_length=20)
    candidate_fact_ids: list[Identifier] = Field(default_factory=list, max_length=20)
    binding_reason: ReasonText | None = None
    observation_kind: Literal[
        "PARSED_RECORD",
        "PARSED_PARTY_STATEMENT",
        "PARSED_TRANSACTION_STATUS",
        "OCR_TEXT",
        "IMAGE_PIXELS",
        "PLATFORM_RECORD",
    ]
    epistemic_status: Literal["PENDING_VERIFICATION", "PROVISIONAL"]

    @model_validator(mode="after")
    def validate_binding_authority(self) -> "EvidenceObservationFrameHeaderV2":
        if self.binding_status == "BOUND" and not self.fact_bindings:
            raise ValueError("bound observation requires fact bindings")
        if self.binding_status == "UNRELATED" and self.fact_bindings:
            raise ValueError("unrelated observation cannot bind facts")
        if self.binding_status == "AMBIGUOUS" and not self.candidate_fact_ids:
            raise ValueError("ambiguous observation requires candidate facts")
        return self


class EvidenceAssessmentFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["EVIDENCE_ASSESSMENT"]
    evidence_id: Identifier
    observation_slots: list[Identifier] = Field(default_factory=list, max_length=20)
    relevance: Literal[
        "DIRECT",
        "PARTIAL",
        "CONTEXTUAL",
        "UNRELATED",
        "UNAVAILABLE",
    ]
    source_chain_status: Literal[
        "TRACEABLE",
        "PARTIAL",
        "UNTRACEABLE",
        "UNAVAILABLE",
    ]
    formation_time_status: Literal[
        "CONFIRMED",
        "PARTIAL",
        "UNKNOWN",
        "CONFLICTING",
    ]
    integrity_status: Literal[
        "INTACT",
        "PARTIAL",
        "ANOMALY_DETECTED",
        "UNAVAILABLE",
    ]
    readability: Literal["CLEAR", "PARTIAL", "UNREADABLE", "UNAVAILABLE"]
    cross_source_consistency: Literal[
        "CONSISTENT",
        "MIXED",
        "CONFLICTING",
        "NOT_ASSESSED",
    ]
    authenticity_status: Literal[
        "UNVERIFIED",
        "PROVISIONALLY_CONSISTENT",
        "ANOMALY_DETECTED",
        "UNAVAILABLE",
        "REQUIRES_HUMAN_REVIEW",
    ]
    capability_status: Literal[
        "FULL_CONTENT",
        "TEXT_ONLY",
        "OCR_ONLY",
        "PIXELS_LOADED",
        "PARTIAL",
        "UNAVAILABLE",
    ]
    limitations: list[ShortText] = Field(default_factory=list, max_length=20)
    conflict_findings: list[ShortText] = Field(default_factory=list, max_length=20)


class EvidenceRequestFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["EVIDENCE_REQUEST"]
    request_slot: Identifier
    target_fact_ids: list[Identifier] = Field(default_factory=list, max_length=20)
    gap_codes: list[Identifier] = Field(default_factory=list, max_length=10)
    requested_material_kind: ShortText
    priority: Literal["LOW", "MEDIUM", "HIGH"]
    reason: ReasonText | None = None

    @model_validator(mode="after")
    def validate_request_authority(self) -> "EvidenceRequestFrameHeaderV2":
        if not self.target_fact_ids and not self.gap_codes:
            raise ValueError("request must identify a fact or gap")
        return self


class EvidenceHumanReviewFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["HUMAN_REVIEW_TASK"]
    evidence_id: Identifier
    trigger_code: Identifier
    review_target: ShortText
    review_instruction: ShortText
    priority: Literal["LOW", "MEDIUM", "HIGH"]


class EvidenceRoomReadinessFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["ROOM_READINESS"]
    core_fact_coverage: Literal["COMPLETE", "PARTIAL", "NONE", "UNKNOWN"]
    source_chain_coverage: Literal["COMPLETE", "PARTIAL", "NONE", "UNKNOWN"]
    time_integrity_coverage: Literal["COMPLETE", "PARTIAL", "NONE", "UNKNOWN"]
    unresolved_conflicts: list[ShortText] = Field(default_factory=list, max_length=20)
    remaining_core_fact_ids: list[Identifier] = Field(default_factory=list, max_length=50)
    human_review_status: Literal["NONE", "PENDING", "REQUIRED"]
    overall_readiness: Literal["READY", "PARTIAL", "NOT_READY", "UNKNOWN"]
    readiness_reasons: list[ShortText] = Field(default_factory=list, max_length=20)

EvidenceFrameHeaderV2 = Annotated[
    EvidenceRoomWelcomeFrameHeaderV2
    | EvidenceOpeningOrientationFrameHeaderV2
    | EvidenceMaterialReceiptFrameHeaderV2
    | EvidenceTextFollowupFrameHeaderV2
    | EvidenceObservationFrameHeaderV2
    | EvidenceAssessmentFrameHeaderV2
    | EvidenceRequestFrameHeaderV2
    | EvidenceHumanReviewFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]
_EVIDENCE_FRAME_HEADER_ADAPTER = TypeAdapter(EvidenceFrameHeaderV2)

EvidencePublicFrameHeaderV2 = Annotated[
    EvidenceRoomWelcomeFrameHeaderV2
    | EvidenceOpeningOrientationFrameHeaderV2
    | EvidenceMaterialReceiptFrameHeaderV2
    | EvidenceTextFollowupFrameHeaderV2
    | EvidenceObservationFrameHeaderV2
    | EvidenceAssessmentFrameHeaderV2
    | EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]
def validate_evidence_frame_header_v2(value: Any) -> EvidenceFrameHeaderV2:
    return _EVIDENCE_FRAME_HEADER_ADAPTER.validate_python(value)


def leading_evidence_frame_header_v2(
    mode: Literal["ROOM_OPENING", "MATERIAL_REVIEW", "TEXT_FOLLOWUP"],
    *,
    attachment_ids: tuple[str, ...] = (),
) -> EvidenceFrameHeaderV2:
    """Project the only legal first-frame header from authoritative turn mode."""

    if mode == "ROOM_OPENING":
        return EvidenceRoomWelcomeFrameHeaderV2(
            frame_sequence=1,
            frame_type="ROOM_WELCOME",
        )
    if mode == "MATERIAL_REVIEW":
        return EvidenceMaterialReceiptFrameHeaderV2(
            frame_sequence=1,
            frame_type="MATERIAL_RECEIPT",
            evidence_ids=list(attachment_ids),
        )
    if mode == "TEXT_FOLLOWUP":
        return EvidenceTextFollowupFrameHeaderV2(
            frame_sequence=1,
            frame_type="TEXT_FOLLOWUP_REPLY",
        )
    raise ValueError("unsupported evidence leading-frame mode")


class EvidenceFrameObjectV2(EvidenceV2Model):
    """Generic ordered frame object used only by the shared result model."""

    header: EvidenceFrameHeaderV2
    public_text: PublicText | None

    @model_validator(mode="after")
    def validate_public_slot(self) -> "EvidenceFrameObjectV2":
        internal = self.header.frame_type == "HUMAN_REVIEW_TASK"
        if internal != (self.public_text is None):
            raise ValueError("frame public_text slot conflicts with frame type")
        return self


EvidenceRoomOpeningFrameHeaderV2 = Annotated[
    EvidenceOpeningOrientationFrameHeaderV2
    | EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]


class EvidenceRoomOpeningFrameObjectV2(EvidenceV2Model):
    """Opening semantic frame after the state-machine-owned welcome frame."""

    header: EvidenceRoomOpeningFrameHeaderV2
    public_text: PublicText


EvidenceMaterialReviewPublicFrameHeaderV2 = Annotated[
    EvidenceObservationFrameHeaderV2
    | EvidenceAssessmentFrameHeaderV2
    | EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]
class EvidenceMaterialReviewPublicFrameObjectV2(EvidenceV2Model):
    """Material-review public branch with an explicit string slot."""

    header: EvidenceMaterialReviewPublicFrameHeaderV2
    public_text: PublicText


class EvidenceHumanReviewFrameObjectV2(EvidenceV2Model):
    """Material-review internal branch; no public bytes are permitted."""

    header: EvidenceHumanReviewFrameHeaderV2
    public_text: None


EvidenceMaterialReviewFrameObjectV2 = (
    EvidenceMaterialReviewPublicFrameObjectV2 | EvidenceHumanReviewFrameObjectV2
)


EvidenceTextFollowupModeFrameHeaderV2 = Annotated[
    EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]


class EvidenceTextFollowupFrameObjectV2(EvidenceV2Model):
    """Text-followup wire object; every allowed frame is public."""

    header: EvidenceTextFollowupModeFrameHeaderV2
    public_text: PublicText


class EvidenceTurnStreamV2(EvidenceV2Model):
    schema_version: Literal["evidence_turn_stream.v2"] = "evidence_turn_stream.v2"
    lead_public_text: PublicText
    frames: list[EvidenceFrameObjectV2] = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_sequence(self) -> "EvidenceTurnStreamV2":
        sequences = [frame.header.frame_sequence for frame in self.frames]
        if sequences != list(range(2, len(sequences) + 2)):
            raise ValueError("evidence frame sequences must be contiguous")
        if self.frames[-1].header.frame_type != "ROOM_READINESS":
            raise ValueError("evidence stream must end with room readiness")
        return self


class EvidenceRoomOpeningStreamV2(EvidenceTurnStreamV2):
    """Opening-specific provider contract; cardinality is checked by the executor."""

    frames: list[EvidenceRoomOpeningFrameObjectV2] = Field(
        min_length=1, max_length=128
    )


class EvidenceMaterialReviewStreamV2(EvidenceTurnStreamV2):
    """Attachment review-specific provider contract."""

    frames: list[EvidenceMaterialReviewFrameObjectV2] = Field(
        min_length=1, max_length=128
    )


class EvidenceTextFollowupStreamV2(EvidenceTurnStreamV2):
    """Text-only follow-up provider contract."""

    frames: list[EvidenceTextFollowupFrameObjectV2] = Field(
        min_length=1, max_length=128
    )


class CommittedEvidenceFrameV2(EvidenceV2Model):
    frame_id: Identifier
    frame_sequence: int = Field(ge=1, le=128)
    frame_type: FrameType
    header: EvidenceFrameHeaderV2
    header_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    public_text: str | None = Field(default=None, max_length=100_000)
    public_text_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    public_text_length: int = Field(ge=0, le=100_000)
    frame_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")

    @field_serializer("header")
    def serialize_header(self, header: EvidenceFrameHeaderV2) -> dict[str, Any]:
        return header.model_dump(
            mode="json",
            exclude_none=True,
            exclude_defaults=True,
        )


class EvidenceTurnResultV2(EvidenceV2Model):
    schema_version: Literal["evidence-turn-result.v2"] = "evidence-turn-result.v2"
    frame_authority_schema: Literal["evidence-turn-frame.v2"] = "evidence-turn-frame.v2"
    frame_manifest: list[CommittedEvidenceFrameV2] = Field(min_length=1, max_length=128)
    frame_manifest_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    room_utterance: PublicText
    referenced_evidence_ids: list[Identifier] = Field(default_factory=list, max_length=50)
    observation_graph: list[dict[str, Any]] = Field(default_factory=list, max_length=50)
    evidence_assessments: list[dict[str, Any]] = Field(default_factory=list, max_length=50)
    evidence_requests: list[dict[str, Any]] = Field(default_factory=list, max_length=3)
    human_review_tasks: list[dict[str, Any]] = Field(default_factory=list, max_length=50)
    room_readiness: dict[str, Any] = Field(default_factory=dict)


__all__ = [
    "CommittedEvidenceFrameV2",
    "EvidenceFactBindingV2",
    "EvidenceFrameHeaderV2",
    "EvidenceFrameObjectV2",
    "EvidenceMaterialReviewStreamV2",
    "EvidenceRoomOpeningStreamV2",
    "EvidenceTextFollowupStreamV2",
    "EvidenceTurnResultV2",
    "EvidenceTurnStreamV2",
    "leading_evidence_frame_header_v2",
]
