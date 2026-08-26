from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping
from typing import Annotated, Any, Literal, TypeAlias, cast

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    RootModel,
    StringConstraints,
    create_model,
    model_validator,
)

from app.graphs.intake.parallel_contracts import (
    Identifier,
    ParallelFrameType,
    PartyRole,
)
from app.graphs.intake.contracts import (
    CaseFactDeltaRowV2,
    CaseFactMatrixDeltaV2,
    MatrixFactKey,
    RespondentClaimDeltaV2,
)


DIALOGUE_SEGMENT_MAX_LENGTH = 200
DIALOGUE_SEGMENT_MAX_ITEMS = 2
DOSSIER_TEXT_MAX_LENGTH = 240
DOSSIER_SHORT_TEXT_MAX_LENGTH = 160
DOSSIER_FACT_MAX_ITEMS = 6

DialogueSegmentText = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=DIALOGUE_SEGMENT_MAX_LENGTH,
        pattern=r"^[^?？]+$",
    ),
]
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
DossierLongText = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=DOSSIER_TEXT_MAX_LENGTH,
        pattern=r"[\s\S]*\S[\s\S]*",
    ),
]
DossierShortText = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=DOSSIER_SHORT_TEXT_MAX_LENGTH,
        pattern=r"[\s\S]*\S[\s\S]*",
    ),
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
    candidate_text: DialogueSegmentText

    @model_validator(mode="after")
    def reject_model_authored_questions(self) -> DialoguePublicSegmentProposalV1:
        if "?" in self.candidate_text or "？" in self.candidate_text:
            raise ValueError("Dialogue segments cannot create question text")
        return self


DialogueRemarkDisposition = Literal["REMARK", "NO_REMARK"]


class DialogueFrameValueV2(StrictFrameOutput):
    # The persisted phase remains Java authority for the visible action.  The
    # Provider owns only the one semantic distinction that the phase cannot
    # determine on its own: whether a WAITING_FOR_REMARK message contains a
    # remark or explicitly declines one.  Request-bound Schema narrows this to
    # null for every other phase.
    remark_disposition: DialogueRemarkDisposition | None


class IntakeDialogueFrameV2(StrictFrameOutput):
    public_projection_items: tuple[DialoguePublicSegmentProposalV1, ...] = Field(
        min_length=1, max_length=DIALOGUE_SEGMENT_MAX_ITEMS
    )
    frame_type: Literal["DIALOGUE_FRAME"]
    schema_version: Literal["intake.dialogue-frame.v2"]
    dialogue: DialogueFrameValueV2


def request_bound_dialogue_output_types(
    *,
    persisted_phase: str,
) -> tuple[type[IntakeDialogueFrameV2], type[DialoguePublicSegmentProposalV1]]:
    """Expose only the remark distinction that this exact turn may author."""

    if persisted_phase not in {
        "NOT_READY",
        "READY_PENDING_REMARK_INVITE",
        "WAITING_FOR_REMARK",
    }:
        raise ValueError("request-bound Dialogue phase cannot accept a ROOM_MESSAGE")
    identity = hashlib.sha256(persisted_phase.encode("utf-8")).hexdigest()[:12]
    disposition_type: Any = (
        DialogueRemarkDisposition
        if persisted_phase == "WAITING_FOR_REMARK"
        else Literal[None]
    )
    dialogue_type = create_model(
        f"DialogueFrameValueV2_{identity}",
        __base__=DialogueFrameValueV2,
        __module__=__name__,
        remark_disposition=(disposition_type, ...),
    )
    frame_type = create_model(
        f"IntakeDialogueFrameV2_{identity}",
        __base__=IntakeDialogueFrameV2,
        __module__=__name__,
        dialogue=(dialogue_type, ...),
    )
    return (
        cast(type[IntakeDialogueFrameV2], frame_type),
        DialoguePublicSegmentProposalV1,
    )


class DossierCurrentFactRowV2(StrictFrameOutput):
    fact_key: MatrixFactKey
    category: Literal[
        "ORDER",
        "PRODUCT_PAGE",
        "PAYMENT",
        "FULFILLMENT",
        "LOGISTICS",
        "PRODUCT_STATE",
        "COMMUNICATION",
        "AFTER_SALES",
        "TIME",
        "OTHER",
    ]
    fact_target: DossierLongText
    materiality: Literal["CORE", "SUPPORTING", "CONTEXT"]
    stance: Literal["CONFIRM", "DENY", "PARTIAL", "UNKNOWN"]
    position_summary: DossierLongText
    asserted_value: DossierShortText | None = None
    source_scope: Literal["CURRENT_SOURCE", "PREVIOUS_AND_CURRENT_SOURCE"]
    agreed_statement: DossierLongText | None = None
    conflict_summary: DossierLongText | None = None

    def materialized_row(self) -> CaseFactDeltaRowV2:
        return CaseFactDeltaRowV2.model_validate(self.model_dump(mode="json"))


class DossierRespondentClaimV2(StrictFrameOutput):
    attitude: Literal[
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
    ]
    position_summary: DossierLongText
    alternative_proposal: DossierLongText | None = None

    def materialized_claim(self) -> RespondentClaimDeltaV2:
        return RespondentClaimDeltaV2.model_validate(self.model_dump(mode="json"))


class DossierPublicFactProposalV2(StrictFrameOutput):
    schema_version: Literal["intake.dossier-public-fact-proposal.v2"]
    projection_kind: Literal["CURRENT_FACT"]
    projection_path_id: Literal["case_story.one_sentence_summary"]
    source_row: DossierCurrentFactRowV2


class DossierFrameDeltaV2(StrictFrameOutput):
    respondent_claim: DossierRespondentClaimV2 | None = None


class IntakeDossierFrameV2(StrictFrameOutput):
    public_projection_items: tuple[DossierPublicFactProposalV2, ...] = Field(
        max_length=DOSSIER_FACT_MAX_ITEMS
    )
    frame_type: Literal["DOSSIER_FRAME"]
    schema_version: Literal["intake.dossier-frame.v2"]
    dossier_delta: DossierFrameDeltaV2

    @model_validator(mode="after")
    def validate_fact_identity(self) -> IntakeDossierFrameV2:
        fact_keys = tuple(item.source_row.fact_key for item in self.public_projection_items)
        if len(fact_keys) != len(set(fact_keys)):
            raise ValueError("Dossier current-source facts must have unique fact keys")
        if not self.public_projection_items and self.dossier_delta.respondent_claim:
            raise ValueError("Dossier respondent claim requires one current-source fact")
        return self

    def materialized_dossier_patch(self) -> dict[str, Any]:
        return _materialize_dossier_patch(self.public_projection_items)

    def materialized_matrix_patch(self) -> CaseFactMatrixDeltaV2 | None:
        if not self.public_projection_items:
            return None
        rows = tuple(
            item.source_row.materialized_row()
            for item in self.public_projection_items
        )
        claim = self.dossier_delta.respondent_claim
        return CaseFactMatrixDeltaV2(
            schema_version="case_fact_matrix.delta.v2",
            fact_rows=rows,
            summary_source_fact_keys=tuple(row.fact_key for row in rows),
            respondent_claim=(claim.materialized_claim() if claim else None),
        )


def request_bound_dossier_output_types(
    *,
    existing_fact_keys: tuple[str, ...],
    new_fact_key_prefix: str,
    respondent_capacity: bool,
) -> tuple[type[IntakeDossierFrameV2], type[DossierPublicFactProposalV2]]:
    """Narrow Dossier authority in the provider-visible Schema for this exact turn."""

    if len(existing_fact_keys) > 200 or len(existing_fact_keys) != len(
        set(existing_fact_keys)
    ):
        raise ValueError("request-bound existing fact keys are invalid")
    if any(
        re.fullmatch(r"FACT_[A-Za-z0-9_:-]{1,123}", key) is None
        for key in existing_fact_keys
    ):
        raise ValueError("request-bound existing fact key is invalid")
    if re.fullmatch(r"NEW_[A-F0-9]{24}_", new_fact_key_prefix) is None:
        raise ValueError("request-bound new fact-key prefix is invalid")

    identity = hashlib.sha256(
        ("\0".join((*existing_fact_keys, new_fact_key_prefix, str(respondent_capacity))))
        .encode("utf-8")
    ).hexdigest()[:12]
    new_key_type = Annotated[
        str,
        StringConstraints(
            min_length=len(new_fact_key_prefix) + 1,
            max_length=128,
            pattern=(
                rf"^{re.escape(new_fact_key_prefix)}[A-Za-z0-9_]"
                rf"{{1,{128 - len(new_fact_key_prefix)}}}$"
            ),
        ),
    ]
    new_row = create_model(
        f"DossierCurrentNewFactRowV2_{identity}",
        __base__=DossierCurrentFactRowV2,
        __module__=__name__,
        fact_key=(new_key_type, ...),
        source_scope=(Literal["CURRENT_SOURCE"], ...),
    )
    row_type: Any = new_row
    if existing_fact_keys:
        existing_key_type = Literal.__getitem__(existing_fact_keys)
        existing_row = create_model(
            f"DossierCurrentExistingFactRowV2_{identity}",
            __base__=DossierCurrentFactRowV2,
            __module__=__name__,
            fact_key=(existing_key_type, ...),
            source_scope=(Literal["PREVIOUS_AND_CURRENT_SOURCE"], ...),
        )
        row_type = existing_row | new_row

    item_type = create_model(
        f"DossierPublicFactProposalV2_{identity}",
        __base__=DossierPublicFactProposalV2,
        __module__=__name__,
        source_row=(row_type, ...),
    )
    delta_fields: dict[str, tuple[Any, Any]] = {}
    if not respondent_capacity:
        delta_fields["respondent_claim"] = (Literal[None], None)
    delta_type = create_model(
        f"DossierFrameDeltaV2_{identity}",
        __base__=DossierFrameDeltaV2,
        __module__=__name__,
        **delta_fields,
    )
    frame_type = create_model(
        f"IntakeDossierFrameV2_{identity}",
        __base__=IntakeDossierFrameV2,
        __module__=__name__,
        public_projection_items=(
            tuple[item_type, ...],
            Field(max_length=DOSSIER_FACT_MAX_ITEMS),
        ),
        dossier_delta=(delta_type, ...),
    )
    return (
        cast(type[IntakeDossierFrameV2], frame_type),
        cast(type[DossierPublicFactProposalV2], item_type),
    )


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


class QualityReferencesPublicMetricProposalV1(QualityPublicMetricProposalV1):
    dimension: Literal["REFERENCES"]
    candidate_score: int = Field(ge=0, le=15)


class QualityEventStoryPublicMetricProposalV1(QualityPublicMetricProposalV1):
    dimension: Literal["EVENT_STORY"]
    candidate_score: int = Field(ge=0, le=20)


class QualityPartyPositionsPublicMetricProposalV1(QualityPublicMetricProposalV1):
    dimension: Literal["PARTY_POSITIONS"]
    candidate_score: int = Field(ge=0, le=20)


class QualityRequestedResolutionPublicMetricProposalV1(
    QualityPublicMetricProposalV1
):
    dimension: Literal["REQUESTED_RESOLUTION"]
    candidate_score: int = Field(ge=0, le=15)


class QualityRiskAndConflictsPublicMetricProposalV1(
    QualityPublicMetricProposalV1
):
    dimension: Literal["RISK_AND_CONFLICTS"]
    candidate_score: int = Field(ge=0, le=15)


class QualityNextActionClarityPublicMetricProposalV1(
    QualityPublicMetricProposalV1
):
    dimension: Literal["NEXT_ACTION_CLARITY"]
    candidate_score: int = Field(ge=0, le=15)


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


QualityPublicProjectionValueV1: TypeAlias = (
    QualityReferencesPublicMetricProposalV1
    | QualityEventStoryPublicMetricProposalV1
    | QualityPartyPositionsPublicMetricProposalV1
    | QualityRequestedResolutionPublicMetricProposalV1
    | QualityRiskAndConflictsPublicMetricProposalV1
    | QualityNextActionClarityPublicMetricProposalV1
    | QualityPublicGapProposalV1
)


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
    IntakeDialogueFrameV2 | IntakeDossierFrameV2 | IntakeQualityFrameV1
)

FRAME_OUTPUT_MODELS: Mapping[ParallelFrameType, type[StrictFrameOutput]] = {
    "DIALOGUE_FRAME": IntakeDialogueFrameV2,
    "DOSSIER_FRAME": IntakeDossierFrameV2,
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
    items: tuple[DossierPublicFactProposalV2, ...],
) -> dict[str, Any]:
    if not items:
        return {}
    summary = "；".join(item.source_row.position_summary for item in items)
    if len(summary) > 20_000:
        raise ValueError("Dossier current-source summary exceeds the persisted field limit")
    return {
        "case_story": {
            "one_sentence_summary": summary,
        }
    }


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
    "IntakeDialogueFrameV2",
    "IntakeDossierFrameV2",
    "IntakeQualityFrameV1",
    "ParallelFrameOutput",
    "QUALITY_DIMENSION_ORDER",
    "QualityPublicProjectionProposalV1",
    "request_bound_dialogue_output_types",
    "request_bound_dossier_output_types",
    "validate_parallel_frame_output",
]
