from __future__ import annotations

from collections.abc import Mapping
from typing import Annotated, Any, Literal, TypeAlias

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.graphs.intake.parallel_contracts import (
    ConversationAction,
    Identifier,
    ParallelFrameType,
    PartyRole,
    Sha256,
)


BoundedChineseText = Annotated[str, StringConstraints(min_length=1, max_length=500)]
BoundedReasoning = Annotated[str, StringConstraints(min_length=1, max_length=2000)]
BoundedQuestion = Annotated[str, StringConstraints(min_length=2, max_length=1000)]
Dimension = Literal[
    "REFERENCES",
    "EVENT_STORY",
    "PARTY_POSITIONS",
    "REQUESTED_RESOLUTION",
    "RISK_AND_CONFLICTS",
    "NEXT_ACTION_CLARITY",
]


class StrictFrameOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class DialoguePublicSegmentProposalV1(StrictFrameOutput):
    schema_version: Literal["intake.dialogue-public-segment-proposal.v1"]
    provider_slot_id: Identifier
    segment_kind: Literal[
        "ACKNOWLEDGEMENT",
        "TRANSITION",
        "REMARK_ACKNOWLEDGEMENT",
    ]
    candidate_text: BoundedChineseText

    @model_validator(mode="after")
    def reject_model_authored_questions(self) -> DialoguePublicSegmentProposalV1:
        if "?" in self.candidate_text or "？" in self.candidate_text:
            raise ValueError("Dialogue segments cannot create question text")
        return self


class DialogueActionBindingV1(StrictFrameOutput):
    action: ConversationAction
    phase_source_sha256: Sha256


class DialogueFrameValueV1(StrictFrameOutput):
    action_binding: DialogueActionBindingV1
    public_projection_slots: tuple[Identifier, ...] = Field(
        min_length=1, max_length=4
    )
    language: Literal["zh-CN"]


class IntakeDialogueFrameV1(StrictFrameOutput):
    public_projection_items: tuple[DialoguePublicSegmentProposalV1, ...] = Field(
        min_length=1, max_length=4
    )
    frame_type: Literal["DIALOGUE_FRAME"]
    schema_version: Literal["intake.dialogue-frame.v1"]
    dialogue: DialogueFrameValueV1

    @model_validator(mode="after")
    def validate_projection_trace(self) -> IntakeDialogueFrameV1:
        _require_exact_projection_slots(
            self.public_projection_items,
            self.dialogue.public_projection_slots,
        )
        return self


class DossierPublicPatchProposalV1(StrictFrameOutput):
    schema_version: Literal["intake.dossier-public-patch-proposal.v1"]
    provider_slot_id: Identifier
    projection_kind: Identifier
    projection_path_id: Identifier
    fact_key: Identifier | None = None
    source_binding_id: Identifier | None = None
    candidate_value: Any


class DossierFrameDeltaV1(StrictFrameOutput):
    dossier_patch: dict[str, Any]
    matrix_patch: dict[str, Any] | None
    public_projection_slots: tuple[Identifier, ...] = Field(max_length=32)

    @model_validator(mode="after")
    def reject_server_owned_branches(self) -> DossierFrameDeltaV1:
        forbidden = {
            "intake_quality",
            "missing_information",
            "handoff_notes",
            "admission",
            "party_intake_state",
            "handoff_remark_partition",
            "case_fact_matrix",
            "unilateral_case_matrix",
        }
        conflict = forbidden.intersection(self.dossier_patch)
        if conflict:
            raise ValueError(
                f"Dossier Frame attempted to write server-owned branches: {sorted(conflict)}"
            )
        return self


class IntakeDossierFrameV1(StrictFrameOutput):
    public_projection_items: tuple[DossierPublicPatchProposalV1, ...] = Field(
        max_length=32
    )
    frame_type: Literal["DOSSIER_FRAME"]
    schema_version: Literal["intake.dossier-frame.v1"]
    dossier_delta: DossierFrameDeltaV1

    @model_validator(mode="after")
    def validate_projection_trace(self) -> IntakeDossierFrameV1:
        _require_exact_projection_slots(
            self.public_projection_items,
            self.dossier_delta.public_projection_slots,
        )
        return self


class IntakeQualityScoresV1(StrictFrameOutput):
    references: int = Field(ge=0, le=15)
    event_story: int = Field(ge=0, le=20)
    party_positions: int = Field(ge=0, le=20)
    requested_resolution: int = Field(ge=0, le=15)
    risk_and_conflicts: int = Field(ge=0, le=15)
    next_action_clarity: int = Field(ge=0, le=15)


class QualityGapProposalV1(StrictFrameOutput):
    dimension: Dimension
    question: BoundedQuestion
    source_role: PartyRole
    linked_fact_keys: tuple[Identifier, ...] = Field(max_length=16)

    @model_validator(mode="after")
    def validate_gap(self) -> QualityGapProposalV1:
        if not self.question.endswith("？"):
            raise ValueError("Quality gap must be one concrete Chinese question")
        if len(self.linked_fact_keys) != len(set(self.linked_fact_keys)):
            raise ValueError("Quality gap cannot repeat linked fact keys")
        return self


class QualityPublicMetricProposalV1(StrictFrameOutput):
    schema_version: Literal["intake.quality-public-metric-proposal.v1"]
    provider_slot_id: Identifier
    projection_kind: Literal["DIMENSION_SCORE"]
    dimension: Dimension
    candidate_score: int = Field(ge=0, le=20)
    linked_fact_keys: tuple[Identifier, ...] = Field(max_length=16)


class QualityFrameValueV1(StrictFrameOutput):
    scores: IntakeQualityScoresV1
    gap_proposals: tuple[QualityGapProposalV1, ...] = Field(max_length=6)
    assessment_reasoning: BoundedReasoning
    public_projection_slots: tuple[Identifier, ...] = Field(
        min_length=6, max_length=6
    )

    @model_validator(mode="after")
    def validate_gap_dimensions(self) -> QualityFrameValueV1:
        dimensions = [gap.dimension for gap in self.gap_proposals]
        if len(dimensions) != len(set(dimensions)):
            raise ValueError("Quality Frame allows at most one gap per dimension")
        maxima = {
            "REFERENCES": ("references", 15),
            "EVENT_STORY": ("event_story", 20),
            "PARTY_POSITIONS": ("party_positions", 20),
            "REQUESTED_RESOLUTION": ("requested_resolution", 15),
            "RISK_AND_CONFLICTS": ("risk_and_conflicts", 15),
            "NEXT_ACTION_CLARITY": ("next_action_clarity", 15),
        }
        for gap in self.gap_proposals:
            field, maximum = maxima[gap.dimension]
            if getattr(self.scores, field) == maximum:
                raise ValueError("A full-score dimension cannot remain blocking")
        return self


class IntakeQualityFrameV1(StrictFrameOutput):
    public_projection_items: tuple[QualityPublicMetricProposalV1, ...] = Field(
        min_length=6, max_length=6
    )
    frame_type: Literal["QUALITY_FRAME"]
    schema_version: Literal["intake.quality-frame.v1"]
    quality: QualityFrameValueV1

    @model_validator(mode="after")
    def validate_projection_trace(self) -> IntakeQualityFrameV1:
        _require_exact_projection_slots(
            self.public_projection_items,
            self.quality.public_projection_slots,
        )
        expected = {
            "REFERENCES": self.quality.scores.references,
            "EVENT_STORY": self.quality.scores.event_story,
            "PARTY_POSITIONS": self.quality.scores.party_positions,
            "REQUESTED_RESOLUTION": self.quality.scores.requested_resolution,
            "RISK_AND_CONFLICTS": self.quality.scores.risk_and_conflicts,
            "NEXT_ACTION_CLARITY": self.quality.scores.next_action_clarity,
        }
        observed: set[Dimension] = set()
        for item in self.public_projection_items:
            if item.dimension in observed:
                raise ValueError("Quality public trace repeats a dimension")
            observed.add(item.dimension)
            if item.candidate_score != expected[item.dimension]:
                raise ValueError("Quality public trace differs from score authority")
        if observed != set(expected):
            raise ValueError("Quality public trace must contain every score dimension")
        return self


ParallelFrameOutput: TypeAlias = (
    IntakeDialogueFrameV1 | IntakeDossierFrameV1 | IntakeQualityFrameV1
)

FRAME_OUTPUT_MODELS: Mapping[ParallelFrameType, type[StrictFrameOutput]] = {
    "DIALOGUE_FRAME": IntakeDialogueFrameV1,
    "DOSSIER_FRAME": IntakeDossierFrameV1,
    "QUALITY_FRAME": IntakeQualityFrameV1,
}


def validate_parallel_frame_output(
    frame_type: ParallelFrameType,
    value: Mapping[str, Any] | BaseModel,
) -> ParallelFrameOutput:
    model = FRAME_OUTPUT_MODELS[frame_type]
    payload = value.model_dump(mode="json") if isinstance(value, BaseModel) else value
    result = model.model_validate(payload)
    return result  # type: ignore[return-value]


def _require_exact_projection_slots(
    items: tuple[Any, ...],
    slots: tuple[Identifier, ...],
) -> None:
    item_slots = tuple(item.provider_slot_id for item in items)
    if item_slots != slots or len(slots) != len(set(slots)):
        raise ValueError("public projection slots must match items once and in order")


__all__ = [
    "FRAME_OUTPUT_MODELS",
    "IntakeDialogueFrameV1",
    "IntakeDossierFrameV1",
    "IntakeQualityFrameV1",
    "ParallelFrameOutput",
    "validate_parallel_frame_output",
]
