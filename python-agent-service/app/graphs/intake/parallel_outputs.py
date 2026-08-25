from __future__ import annotations

from collections.abc import Mapping
from typing import Annotated, Any, Literal, TypeAlias

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    RootModel,
    StringConstraints,
    model_validator,
)

from app.graphs.intake.parallel_contracts import (
    ConversationAction,
    Identifier,
    ParallelFrameType,
    PartyRole,
    Sha256,
)
from app.graphs.intake.contracts import CaseFactDeltaRowV2, CaseFactMatrixDeltaV2


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
QUALITY_DIMENSION_ORDER: tuple[Dimension, ...] = (
    "REFERENCES",
    "EVENT_STORY",
    "PARTY_POSITIONS",
    "REQUESTED_RESOLUTION",
    "RISK_AND_CONFLICTS",
    "NEXT_ACTION_CLARITY",
)
DossierSummary = Annotated[
    str,
    StringConstraints(min_length=1, max_length=20_000),
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
    projection_kind: Literal["CURRENT_FACT"]
    projection_path_id: Literal["case_story.one_sentence_summary"]
    source_row: CaseFactDeltaRowV2
    candidate_value: DossierSummary

    @model_validator(mode="after")
    def validate_current_source_authority(self) -> DossierPublicPatchProposalV1:
        if (
            self.source_row.source_scope
            not in {"CURRENT_SOURCE", "PREVIOUS_AND_CURRENT_SOURCE"}
            or self.source_row.stance == "NOT_ADDRESSED"
        ):
            raise ValueError(
                "Dossier public facts require one substantive current-source row"
            )
        if self.candidate_value != self.source_row.position_summary:
            raise ValueError(
                "Dossier public fact must be derived from its typed source row"
            )
        return self


class DossierFrameDeltaV1(StrictFrameOutput):
    matrix_patch: CaseFactMatrixDeltaV2 | None
    public_projection_slots: tuple[Identifier, ...] = Field(max_length=32)


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
        expected_rows = _current_fact_rows(self.dossier_delta.matrix_patch)
        projected_rows = tuple(
            item.source_row for item in self.public_projection_items
        )
        if projected_rows != expected_rows:
            raise ValueError(
                "Dossier public facts must exactly project the typed matrix delta"
            )
        return self

    def materialized_dossier_patch(self) -> dict[str, Any]:
        return _materialize_dossier_patch(self.public_projection_items)


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
        _validate_gap_question_and_keys(self.question, self.linked_fact_keys)
        return self


class QualityPublicMetricProposalV1(StrictFrameOutput):
    schema_version: Literal["intake.quality-public-metric-proposal.v1"]
    provider_slot_id: Identifier
    projection_kind: Literal["DIMENSION_SCORE"]
    dimension: Dimension
    candidate_score: int = Field(ge=0, le=20)
    linked_fact_keys: tuple[Identifier, ...] = Field(max_length=16)


class QualityPublicGapProposalV1(StrictFrameOutput):
    schema_version: Literal["intake.quality-public-gap-proposal.v1"]
    provider_slot_id: Identifier
    projection_kind: Literal["BLOCKING_GAP"]
    dimension: Dimension
    question: BoundedQuestion
    source_role: PartyRole
    linked_fact_keys: tuple[Identifier, ...] = Field(max_length=16)

    @model_validator(mode="after")
    def validate_gap(self) -> QualityPublicGapProposalV1:
        _validate_gap_question_and_keys(self.question, self.linked_fact_keys)
        return self


QualityPublicProjectionValueV1: TypeAlias = Annotated[
    QualityPublicMetricProposalV1 | QualityPublicGapProposalV1,
    Field(discriminator="projection_kind"),
]


class QualityPublicProjectionProposalV1(
    RootModel[QualityPublicProjectionValueV1]
):
    model_config = ConfigDict(frozen=True)

    @property
    def provider_slot_id(self) -> Identifier:
        return self.root.provider_slot_id


class QualityFrameValueV1(StrictFrameOutput):
    scores: IntakeQualityScoresV1
    gap_proposals: tuple[QualityGapProposalV1, ...] = Field(max_length=6)
    assessment_reasoning: BoundedReasoning
    public_projection_slots: tuple[Identifier, ...] = Field(
        min_length=6, max_length=12
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
    public_projection_items: tuple[QualityPublicProjectionProposalV1, ...] = Field(
        min_length=6, max_length=12
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
        expected_scores = {
            "REFERENCES": self.quality.scores.references,
            "EVENT_STORY": self.quality.scores.event_story,
            "PARTY_POSITIONS": self.quality.scores.party_positions,
            "REQUESTED_RESOLUTION": self.quality.scores.requested_resolution,
            "RISK_AND_CONFLICTS": self.quality.scores.risk_and_conflicts,
            "NEXT_ACTION_CLARITY": self.quality.scores.next_action_clarity,
        }
        score_items = self.public_projection_items[: len(QUALITY_DIMENSION_ORDER)]
        for expected_dimension, wrapped in zip(
            QUALITY_DIMENSION_ORDER,
            score_items,
            strict=True,
        ):
            item = wrapped.root
            if not isinstance(item, QualityPublicMetricProposalV1):
                raise ValueError("Quality public trace must emit all scores before gaps")
            if item.dimension != expected_dimension:
                raise ValueError("Quality public score order differs from the contract")
            if item.candidate_score != expected_scores[expected_dimension]:
                raise ValueError("Quality public trace differs from score authority")

        gap_items = self.public_projection_items[len(QUALITY_DIMENSION_ORDER) :]
        if len(gap_items) != len(self.quality.gap_proposals):
            raise ValueError("Quality public gaps must exactly trace sealed gaps")
        for wrapped, sealed_gap in zip(
            gap_items,
            self.quality.gap_proposals,
            strict=True,
        ):
            item = wrapped.root
            if not isinstance(item, QualityPublicGapProposalV1):
                raise ValueError("Quality public trace cannot emit a score after gaps")
            if (
                item.dimension != sealed_gap.dimension
                or item.question != sealed_gap.question
                or item.source_role != sealed_gap.source_role
                or item.linked_fact_keys != sealed_gap.linked_fact_keys
            ):
                raise ValueError("Quality public gap differs from sealed gap authority")
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


def _materialize_dossier_patch(
    items: tuple[DossierPublicPatchProposalV1, ...],
) -> dict[str, Any]:
    if not items:
        return {}
    summary = "；".join(item.candidate_value for item in items)
    if len(summary) > 20_000:
        raise ValueError("Dossier current-source summary exceeds the persisted field limit")
    return {
        "case_story": {
            "one_sentence_summary": summary,
        }
    }


def _current_fact_rows(
    matrix_patch: CaseFactMatrixDeltaV2 | None,
) -> tuple[CaseFactDeltaRowV2, ...]:
    if matrix_patch is None:
        return ()
    return tuple(
        row
        for row in matrix_patch.fact_rows
        if row.source_scope in {"CURRENT_SOURCE", "PREVIOUS_AND_CURRENT_SOURCE"}
        and row.stance != "NOT_ADDRESSED"
    )


def _require_exact_projection_slots(
    items: tuple[Any, ...],
    slots: tuple[Identifier, ...],
) -> None:
    item_slots = tuple(item.provider_slot_id for item in items)
    if item_slots != slots or len(slots) != len(set(slots)):
        raise ValueError("public projection slots must match items once and in order")


def _validate_gap_question_and_keys(
    question: str,
    linked_fact_keys: tuple[Identifier, ...],
) -> None:
    if not question.endswith("？"):
        raise ValueError("Quality gap must be one concrete Chinese question")
    if len(linked_fact_keys) != len(set(linked_fact_keys)):
        raise ValueError("Quality gap cannot repeat linked fact keys")


__all__ = [
    "FRAME_OUTPUT_MODELS",
    "IntakeDialogueFrameV1",
    "IntakeDossierFrameV1",
    "IntakeQualityFrameV1",
    "ParallelFrameOutput",
    "QUALITY_DIMENSION_ORDER",
    "QualityPublicProjectionProposalV1",
    "validate_parallel_frame_output",
]
