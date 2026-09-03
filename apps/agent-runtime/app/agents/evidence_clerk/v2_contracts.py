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
    "ROOM_READINESS",
]

EvidenceScore = Annotated[float, Field(ge=0.0, le=1.0)]


class EvidenceV2Model(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)


class EvidenceFactBindingV2(EvidenceV2Model):
    fact_id: str | None = None
    relation: str | None = None
    reason: str | None = None


class EvidenceFrameHeaderBaseV2(EvidenceV2Model):
    frame_sequence: int = Field(ge=1, le=128)


class EvidenceRoomWelcomeFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["ROOM_WELCOME"]


class EvidenceOpeningOrientationFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["OPENING_ORIENTATION"]
    focus_fact_ids: list[str] = Field(default_factory=list, max_length=20)


class EvidenceMaterialReceiptFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["MATERIAL_RECEIPT"]
    evidence_ids: list[str] = Field(default_factory=list, max_length=50)
    focus_fact_ids: list[str] = Field(default_factory=list, max_length=20)


class EvidenceTextFollowupFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["TEXT_FOLLOWUP_REPLY"]


class EvidenceObservationFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["EVIDENCE_OBSERVATION"]
    observation_slot: str | None = None
    source_unit_id: str | None = None
    binding_status: str | None = None
    fact_bindings: list[EvidenceFactBindingV2] = Field(default_factory=list, max_length=20)
    candidate_fact_ids: list[str] = Field(default_factory=list, max_length=20)
    binding_reason: str | None = None
    # Source vocabulary is model-owned.  In particular PARSED_TEXT is a valid
    # source basis even though older releases only enumerated semantic aliases
    # such as PARSED_RECORD.
    observation_kind: str | None = None
    epistemic_status: str | None = None


class EvidenceAssessmentFindingV2(EvidenceV2Model):
    finding_type: str | None = None
    description: str | None = None


class EvidenceAssessmentFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["EVIDENCE_ASSESSMENT"]
    evidence_id: str | None = None
    observation_slots: list[str] = Field(default_factory=list, max_length=20)
    authenticity_score: float | None = None
    authenticity_score_explanation: str | None = None
    relevance_score: float | None = None
    relevance_score_explanation: str | None = None
    completeness_score: float | None = None
    completeness_score_explanation: str | None = None
    assessment_confidence: float | None = None
    assessment_confidence_explanation: str | None = None
    risk_level: str | None = None
    risk_explanation: str | None = None
    source_basis: list[str] = Field(default_factory=list, max_length=20)
    formation_time_assessment: str | None = None
    findings: list[EvidenceAssessmentFindingV2] = Field(default_factory=list, max_length=20)
    limitations: list[str] = Field(default_factory=list, max_length=20)
    unsupported_claims: list[str] = Field(default_factory=list, max_length=20)


class EvidenceRequestFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["EVIDENCE_REQUEST"]
    request_slot: str | None = None
    target_fact_ids: list[str] = Field(default_factory=list, max_length=20)
    gap_codes: list[str] = Field(default_factory=list, max_length=10)
    requested_material_kind: str | None = None
    priority: str | None = None
    reason: str | None = None


class EvidenceRoomReadinessFrameHeaderV2(EvidenceFrameHeaderBaseV2):
    frame_type: Literal["ROOM_READINESS"]
    core_fact_coverage: str | None = None
    source_chain_coverage: str | None = None
    time_integrity_coverage: str | None = None
    unresolved_conflicts: list[str] = Field(default_factory=list, max_length=20)
    remaining_core_fact_ids: list[str] = Field(default_factory=list, max_length=50)
    overall_readiness: str | None = None
    readiness_reasons: list[str] = Field(default_factory=list, max_length=20)

EvidenceFrameHeaderV2 = Annotated[
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
    public_text: str | None = None


EvidenceRoomOpeningFrameHeaderV2 = Annotated[
    EvidenceOpeningOrientationFrameHeaderV2
    | EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]


class EvidenceRoomOpeningFrameObjectV2(EvidenceV2Model):
    """Opening semantic frame after the state-machine-owned welcome frame."""

    header: EvidenceRoomOpeningFrameHeaderV2
    public_text: str | None = None


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
    public_text: str | None = None


EvidenceMaterialReviewFrameObjectV2 = EvidenceMaterialReviewPublicFrameObjectV2


EvidenceMaterialReviewNoObservationFrameHeaderV2 = Annotated[
    EvidenceAssessmentFrameHeaderV2
    | EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]


class EvidenceMaterialReviewNoObservationFrameObjectV2(EvidenceV2Model):
    """Material review when no parsed text or loaded pixels own source authority."""

    header: EvidenceMaterialReviewNoObservationFrameHeaderV2
    public_text: str | None = None


EvidenceTextFollowupModeFrameHeaderV2 = Annotated[
    EvidenceRequestFrameHeaderV2
    | EvidenceRoomReadinessFrameHeaderV2,
    Field(discriminator="frame_type"),
]


class EvidenceTextFollowupFrameObjectV2(EvidenceV2Model):
    """Text-followup wire object; every allowed frame is public."""

    header: EvidenceTextFollowupModeFrameHeaderV2
    public_text: str | None = None


class EvidenceTurnStreamV2(EvidenceV2Model):
    schema_version: Literal["evidence_turn_stream.v3"] = "evidence_turn_stream.v3"
    lead_public_text: str | None = None
    frames: list[EvidenceFrameObjectV2] = Field(default_factory=list, max_length=128)


class EvidenceRoomOpeningStreamV2(EvidenceTurnStreamV2):
    """Opening-specific provider contract; cardinality is checked by the executor."""

    frames: list[EvidenceRoomOpeningFrameObjectV2] = Field(
        default_factory=list, max_length=128
    )


class EvidenceMaterialReviewStreamV2(EvidenceTurnStreamV2):
    """Attachment review-specific provider contract."""

    frames: list[EvidenceMaterialReviewFrameObjectV2] = Field(
        default_factory=list, max_length=128
    )


class EvidenceMaterialReviewNoObservationStreamV2(EvidenceTurnStreamV2):
    """Attachment review contract that makes observations provider-inaccessible."""

    frames: list[EvidenceMaterialReviewNoObservationFrameObjectV2] = Field(
        default_factory=list, max_length=128
    )


class EvidenceTextFollowupStreamV2(EvidenceTurnStreamV2):
    """Text-only follow-up provider contract."""

    frames: list[EvidenceTextFollowupFrameObjectV2] = Field(
        default_factory=list, max_length=128
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
    schema_version: Literal["evidence-turn-result.v3"] = "evidence-turn-result.v3"
    frame_authority_schema: Literal["evidence-turn-frame.v3"] = "evidence-turn-frame.v3"
    frame_manifest: list[CommittedEvidenceFrameV2] = Field(min_length=1, max_length=128)
    frame_manifest_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    room_utterance: str = Field(default="", max_length=100_000)
    referenced_evidence_ids: list[Identifier] = Field(default_factory=list, max_length=50)
    observation_graph: list[dict[str, Any]] = Field(default_factory=list, max_length=50)
    evidence_assessments: list[dict[str, Any]] = Field(default_factory=list, max_length=50)
    evidence_requests: list[dict[str, Any]] = Field(default_factory=list, max_length=3)
    room_readiness: dict[str, Any] = Field(default_factory=dict)


__all__ = [
    "CommittedEvidenceFrameV2",
    "EvidenceFactBindingV2",
    "EvidenceFrameHeaderV2",
    "EvidenceFrameObjectV2",
    "EvidenceMaterialReviewNoObservationStreamV2",
    "EvidenceMaterialReviewStreamV2",
    "EvidenceRoomOpeningStreamV2",
    "EvidenceTextFollowupStreamV2",
    "EvidenceTurnResultV2",
    "EvidenceTurnStreamV2",
    "leading_evidence_frame_header_v2",
]
