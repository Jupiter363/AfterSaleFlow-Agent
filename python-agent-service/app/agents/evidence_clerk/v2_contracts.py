"""Evidence-room v2 business frame contracts.

The provider owns the natural-language bytes in the second tuple item.  These
models only describe the small, source-bound header needed by the server to
route, persist, replay and derive room projections.
"""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    RootModel,
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
PublicText = Annotated[str, Field(max_length=100_000)]

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


class EvidenceFrameHeaderV2(EvidenceV2Model):
    frame_sequence: int = Field(ge=1, le=128)
    frame_type: FrameType

    # Opening / receipt focus
    focus_fact_ids: list[Identifier] = Field(default_factory=list, max_length=20)
    evidence_ids: list[Identifier] = Field(default_factory=list, max_length=50)

    # Observation authority selection
    observation_slot: Identifier | None = None
    source_unit_id: Identifier | None = None
    binding_status: Literal["BOUND", "UNRELATED", "AMBIGUOUS"] | None = None
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
    ] | None = None
    epistemic_status: Literal["PENDING_VERIFICATION", "PROVISIONAL"] | None = None

    # Assessment authority
    evidence_id: Identifier | None = None
    observation_slots: list[Identifier] = Field(default_factory=list, max_length=20)
    relevance: Literal[
        "DIRECT",
        "PARTIAL",
        "CONTEXTUAL",
        "UNRELATED",
        "UNAVAILABLE",
    ] | None = None
    source_chain_status: Literal[
        "TRACEABLE",
        "PARTIAL",
        "UNTRACEABLE",
        "UNAVAILABLE",
    ] | None = None
    formation_time_status: Literal[
        "CONFIRMED",
        "PARTIAL",
        "UNKNOWN",
        "CONFLICTING",
    ] | None = None
    integrity_status: Literal[
        "INTACT",
        "PARTIAL",
        "ANOMALY_DETECTED",
        "UNAVAILABLE",
    ] | None = None
    readability: Literal["CLEAR", "PARTIAL", "UNREADABLE", "UNAVAILABLE"] | None = None
    cross_source_consistency: Literal[
        "CONSISTENT",
        "MIXED",
        "CONFLICTING",
        "NOT_ASSESSED",
    ] | None = None
    authenticity_status: Literal[
        "UNVERIFIED",
        "PROVISIONALLY_CONSISTENT",
        "ANOMALY_DETECTED",
        "UNAVAILABLE",
        "REQUIRES_HUMAN_REVIEW",
    ] | None = None
    capability_status: Literal[
        "FULL_CONTENT",
        "TEXT_ONLY",
        "OCR_ONLY",
        "PIXELS_LOADED",
        "PARTIAL",
        "UNAVAILABLE",
    ] | None = None
    limitations: list[ShortText] = Field(default_factory=list, max_length=20)
    conflict_findings: list[ShortText] = Field(default_factory=list, max_length=20)

    # Request authority
    request_slot: Identifier | None = None
    target_fact_ids: list[Identifier] = Field(default_factory=list, max_length=20)
    gap_codes: list[Identifier] = Field(default_factory=list, max_length=10)
    requested_material_kind: ShortText | None = None
    priority: Literal["LOW", "MEDIUM", "HIGH"] | None = None
    reason: ReasonText | None = None

    # Internal review authority
    trigger_code: Identifier | None = None
    review_target: ShortText | None = None
    review_instruction: ShortText | None = None

    # Readiness authority
    core_fact_coverage: Literal["COMPLETE", "PARTIAL", "NONE", "UNKNOWN"] | None = None
    source_chain_coverage: Literal["COMPLETE", "PARTIAL", "NONE", "UNKNOWN"] | None = None
    time_integrity_coverage: Literal["COMPLETE", "PARTIAL", "NONE", "UNKNOWN"] | None = None
    unresolved_conflicts: list[ShortText] = Field(default_factory=list, max_length=20)
    remaining_core_fact_ids: list[Identifier] = Field(default_factory=list, max_length=50)
    human_review_status: Literal["NONE", "PENDING", "REQUIRED"] | None = None
    overall_readiness: Literal["READY", "PARTIAL", "NOT_READY", "UNKNOWN"] | None = None
    readiness_reasons: list[ShortText] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_frame_specific_fields(self) -> "EvidenceFrameHeaderV2":
        frame_type = self.frame_type
        # A single discriminated header keeps the provider schema compact, but
        # its fields still belong to one frame type only.  Empty defaults are
        # tolerated (providers often serialize them); a non-empty field from a
        # different frame is rejected so it cannot smuggle authority across
        # the frame-order boundary.
        allowed_fields = _FRAME_ALLOWED_FIELDS[frame_type]
        values = self.model_dump(mode="python", exclude_none=True, exclude_defaults=True)
        foreign_fields = set(values) - allowed_fields
        if foreign_fields:
            raise ValueError(
                "frame header contains fields from another frame type: "
                + ",".join(sorted(foreign_fields))
            )
        if frame_type in {"ROOM_WELCOME", "TEXT_FOLLOWUP_REPLY"}:
            # These are intentionally text-only frames.  The generic check
            # above handles every optional authority field; keep this branch
            # as an explicit readability guard for future fields.
            if frame_type == "ROOM_WELCOME" and self.focus_fact_ids:
                raise ValueError("welcome frame cannot carry focus facts")
        if frame_type == "OPENING_ORIENTATION" and not self.focus_fact_ids:
            raise ValueError("opening orientation requires focus facts")
        if frame_type == "MATERIAL_RECEIPT" and not self.evidence_ids:
            raise ValueError("material receipt requires evidence ids")
        if frame_type == "EVIDENCE_OBSERVATION":
            if not self.observation_slot or not self.source_unit_id:
                raise ValueError("observation requires slot and source unit")
            if self.binding_status == "BOUND" and not self.fact_bindings:
                raise ValueError("bound observation requires fact bindings")
            if self.binding_status == "UNRELATED" and self.fact_bindings:
                raise ValueError("unrelated observation cannot bind facts")
            if self.binding_status == "AMBIGUOUS" and not self.candidate_fact_ids:
                raise ValueError("ambiguous observation requires candidate facts")
            if self.observation_kind is None or self.epistemic_status is None:
                raise ValueError("observation kind and epistemic status are required")
        if frame_type == "EVIDENCE_ASSESSMENT":
            required = (
                self.evidence_id,
                self.relevance,
                self.source_chain_status,
                self.formation_time_status,
                self.integrity_status,
                self.readability,
                self.cross_source_consistency,
                self.authenticity_status,
                self.capability_status,
            )
            if any(value is None for value in required):
                raise ValueError("assessment is missing a required authority field")
        if frame_type == "EVIDENCE_REQUEST":
            if not self.request_slot or not self.requested_material_kind or not self.priority:
                raise ValueError("request is missing its authority fields")
            if not self.target_fact_ids and not self.gap_codes:
                raise ValueError("request must identify a fact or gap")
        if frame_type == "HUMAN_REVIEW_TASK":
            if not self.evidence_id or not self.trigger_code or not self.review_target:
                raise ValueError("review task is missing its authority fields")
            if not self.review_instruction or not self.priority:
                raise ValueError("review task is missing its instruction or priority")
        if frame_type == "ROOM_READINESS":
            required = (
                self.core_fact_coverage,
                self.source_chain_coverage,
                self.time_integrity_coverage,
                self.human_review_status,
                self.overall_readiness,
            )
            if any(value is None for value in required):
                raise ValueError("readiness is missing a coverage dimension")
        return self


_FRAME_ALLOWED_FIELDS: dict[str, frozenset[str]] = {
    "ROOM_WELCOME": frozenset({"frame_sequence", "frame_type"}),
    "OPENING_ORIENTATION": frozenset({"frame_sequence", "frame_type", "focus_fact_ids"}),
    "MATERIAL_RECEIPT": frozenset(
        {"frame_sequence", "frame_type", "evidence_ids", "focus_fact_ids"}
    ),
    "TEXT_FOLLOWUP_REPLY": frozenset({"frame_sequence", "frame_type"}),
    "EVIDENCE_OBSERVATION": frozenset(
        {
            "frame_sequence",
            "frame_type",
            "observation_slot",
            "source_unit_id",
            "binding_status",
            "fact_bindings",
            "candidate_fact_ids",
            "binding_reason",
            "observation_kind",
            "epistemic_status",
        }
    ),
    "EVIDENCE_ASSESSMENT": frozenset(
        {
            "frame_sequence",
            "frame_type",
            "evidence_id",
            "observation_slots",
            "relevance",
            "source_chain_status",
            "formation_time_status",
            "integrity_status",
            "readability",
            "cross_source_consistency",
            "authenticity_status",
            "capability_status",
            "limitations",
            "conflict_findings",
        }
    ),
    "EVIDENCE_REQUEST": frozenset(
        {
            "frame_sequence",
            "frame_type",
            "request_slot",
            "target_fact_ids",
            "gap_codes",
            "requested_material_kind",
            "priority",
            "reason",
        }
    ),
    "HUMAN_REVIEW_TASK": frozenset(
        {
            "frame_sequence",
            "frame_type",
            "evidence_id",
            "trigger_code",
            "review_target",
            "review_instruction",
            "priority",
        }
    ),
    "ROOM_READINESS": frozenset(
        {
            "frame_sequence",
            "frame_type",
            "core_fact_coverage",
            "source_chain_coverage",
            "time_integrity_coverage",
            "unresolved_conflicts",
            "remaining_core_fact_ids",
            "human_review_status",
            "overall_readiness",
            "readiness_reasons",
        }
    ),
}


class EvidenceFrameTupleV2(RootModel[tuple[EvidenceFrameHeaderV2, str | None]]):
    """Wire tuple: complete header first, then public text or null."""

    root: tuple[EvidenceFrameHeaderV2, str | None]

    @model_validator(mode="after")
    def validate_public_slot(self) -> "EvidenceFrameTupleV2":
        header, public_text = self.root
        internal = header.frame_type == "HUMAN_REVIEW_TASK"
        if internal and public_text is not None:
            raise ValueError("human review frame cannot carry public text")
        if not internal and public_text is None:
            raise ValueError("public frame requires a string text slot")
        return self

    @property
    def header(self) -> EvidenceFrameHeaderV2:
        return self.root[0]

    @property
    def public_text(self) -> str | None:
        return self.root[1]


class EvidenceTurnStreamV2(EvidenceV2Model):
    schema_version: Literal["evidence_turn_stream.v2"] = "evidence_turn_stream.v2"
    frames: list[EvidenceFrameTupleV2] = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_sequence(self) -> "EvidenceTurnStreamV2":
        sequences = [frame.header.frame_sequence for frame in self.frames]
        if sequences != list(range(1, len(sequences) + 1)):
            raise ValueError("evidence frame sequences must be contiguous")
        if self.frames[-1].header.frame_type != "ROOM_READINESS":
            raise ValueError("evidence stream must end with room readiness")
        return self


class EvidenceRoomOpeningStreamV2(EvidenceTurnStreamV2):
    """Opening-specific provider contract; cardinality is checked by the executor."""


class EvidenceMaterialReviewStreamV2(EvidenceTurnStreamV2):
    """Attachment review-specific provider contract."""


class EvidenceTextFollowupStreamV2(EvidenceTurnStreamV2):
    """Text-only follow-up provider contract."""


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
    "EvidenceFrameTupleV2",
    "EvidenceMaterialReviewStreamV2",
    "EvidenceRoomOpeningStreamV2",
    "EvidenceTextFollowupStreamV2",
    "EvidenceTurnResultV2",
    "EvidenceTurnStreamV2",
]
