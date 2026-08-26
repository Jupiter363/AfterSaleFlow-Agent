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
)
from app.graphs.intake.contracts import MatrixFactKey


DIALOGUE_SEGMENT_MAX_LENGTH = 80
DIALOGUE_SEGMENT_MAX_ITEMS = 1
DOSSIER_TEXT_MAX_LENGTH = 100
DOSSIER_SHORT_TEXT_MAX_LENGTH = 60
DOSSIER_FACT_MAX_ITEMS = 5
DOSSIER_NEW_FACT_SUFFIX_MAX_LENGTH = 32

DialogueSegmentText = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=DIALOGUE_SEGMENT_MAX_LENGTH,
        pattern=r"^[^?？]+$",
    ),
]
QualityReasoning = Annotated[str, StringConstraints(min_length=1, max_length=600)]
QualityQuestion = Annotated[str, StringConstraints(min_length=2, max_length=160)]
QualityCandidateQuestion = Annotated[
    str,
    StringConstraints(
        min_length=2,
        max_length=160,
        pattern=r"^[^\r\n]{1,159}？$",
    ),
]
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
QUALITY_DIMENSION_MAXIMA: Mapping[Dimension, int] = {
    "REFERENCES": 15,
    "EVENT_STORY": 20,
    "PARTY_POSITIONS": 20,
    "REQUESTED_RESOLUTION": 15,
    "RISK_AND_CONFLICTS": 15,
    "NEXT_ACTION_CLARITY": 15,
}
DossierCategory = Literal[
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
DossierMateriality = Literal["CORE", "SUPPORTING", "CONTEXT"]
DossierStance = Literal["CONFIRM", "DENY", "PARTIAL", "UNKNOWN"]
DossierRespondentAttitude = Literal[
    "AGREE",
    "PARTIALLY_AGREE",
    "DISAGREE",
    "ALTERNATIVE_PROPOSED",
    "NEED_MORE_INFO",
]
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


class DialoguePublicSegmentDraftV3(StrictFrameOutput):
    segment_kind: Literal[
        "ACKNOWLEDGEMENT",
        "TRANSITION",
        "REMARK_ACKNOWLEDGEMENT",
    ]
    candidate_text: DialogueSegmentText

    @model_validator(mode="after")
    def reject_model_authored_questions(self) -> DialoguePublicSegmentDraftV3:
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


class IntakeDialogueFrameV3(StrictFrameOutput):
    public_projection_items: tuple[DialoguePublicSegmentDraftV3, ...] = Field(
        min_length=1, max_length=DIALOGUE_SEGMENT_MAX_ITEMS
    )
    dialogue: DialogueFrameValueV2


class DialogueRemarkUpdateDraftV4(StrictFrameOutput):
    remark_disposition: DialogueRemarkDisposition


class IntakeDialogueGenerationV4(StrictFrameOutput):
    """Provider draft for non-remark phases; Java-known nulls are absent."""

    public_projection_items: tuple[DialoguePublicSegmentDraftV3, ...] = Field(
        min_length=1, max_length=DIALOGUE_SEGMENT_MAX_ITEMS
    )


class IntakeDialogueRemarkGenerationV4(IntakeDialogueGenerationV4):
    dialogue: DialogueRemarkUpdateDraftV4


def request_bound_dialogue_output_types(
    *,
    persisted_phase: str,
) -> tuple[type[BaseModel], type[DialoguePublicSegmentDraftV3]]:
    """Expose only the remark distinction that this exact turn may author."""

    if persisted_phase not in {
        "NOT_READY",
        "READY_PENDING_REMARK_INVITE",
        "WAITING_FOR_REMARK",
    }:
        raise ValueError("request-bound Dialogue phase cannot accept a ROOM_MESSAGE")
    frame_type: type[BaseModel] = (
        IntakeDialogueRemarkGenerationV4
        if persisted_phase == "WAITING_FOR_REMARK"
        else IntakeDialogueGenerationV4
    )
    return (
        frame_type,
        DialoguePublicSegmentDraftV3,
    )


class DossierCurrentFactDraftV3(StrictFrameOutput):
    fact_key: MatrixFactKey
    category: DossierCategory = Field(
        description=(
            "Exact enum: ORDER, PRODUCT_PAGE, PAYMENT, FULFILLMENT, LOGISTICS, "
            "PRODUCT_STATE, COMMUNICATION, AFTER_SALES, TIME, or OTHER."
        )
    )
    fact_target: DossierLongText
    materiality: DossierMateriality = Field(
        description="Exact enum: CORE, SUPPORTING, or CONTEXT."
    )
    stance: DossierStance = Field(
        description="Exact enum: CONFIRM, DENY, PARTIAL, or UNKNOWN."
    )
    position_summary: DossierLongText
    asserted_value: DossierShortText | None = None


class DossierRespondentClaimV2(StrictFrameOutput):
    attitude: DossierRespondentAttitude = Field(
        description=(
            "Exact enum: AGREE, PARTIALLY_AGREE, DISAGREE, "
            "ALTERNATIVE_PROPOSED, or NEED_MORE_INFO."
        )
    )
    position_summary: DossierLongText
    alternative_proposal: DossierLongText | None = None

class DossierPublicFactDraftV3(StrictFrameOutput):
    source_row: DossierCurrentFactDraftV3


class DossierCurrentFactDraftV5(StrictFrameOutput):
    """Provider-owned current-source statement without server classification fields."""

    fact_key: MatrixFactKey
    fact_target: DossierLongText
    stance: DossierStance = Field(
        description="Exact enum: CONFIRM, DENY, PARTIAL, or UNKNOWN."
    )
    position_summary: DossierLongText
    asserted_value: DossierShortText | None = None


class DossierPublicFactDraftV5(StrictFrameOutput):
    source_row: DossierCurrentFactDraftV5


class DossierFrameDeltaV2(StrictFrameOutput):
    respondent_claim: DossierRespondentClaimV2 | None = None


class DossierRespondentClaimDraftV4(StrictFrameOutput):
    attitude: DossierRespondentAttitude = Field(
        description=(
            "Exact enum: AGREE, PARTIALLY_AGREE, DISAGREE, "
            "ALTERNATIVE_PROPOSED, or NEED_MORE_INFO; PARTIAL is not valid here."
        )
    )
    position_summary: DossierLongText
    alternative_proposals: tuple[DossierLongText, ...] = Field(max_length=1)


class DossierFrameDeltaDraftV4(StrictFrameOutput):
    respondent_claim_updates: tuple[DossierRespondentClaimDraftV4, ...] = Field(
        max_length=1
    )


class IntakeDossierFrameV3(StrictFrameOutput):
    public_projection_items: tuple[DossierPublicFactDraftV3, ...] = Field(
        max_length=DOSSIER_FACT_MAX_ITEMS
    )
    dossier_delta: DossierFrameDeltaV2

    @model_validator(mode="after")
    def validate_fact_identity(self) -> IntakeDossierFrameV3:
        fact_keys = tuple(item.source_row.fact_key for item in self.public_projection_items)
        if len(fact_keys) != len(set(fact_keys)):
            raise ValueError("Dossier current-source facts must have unique fact keys")
        if not self.public_projection_items and self.dossier_delta.respondent_claim:
            raise ValueError("Dossier respondent claim requires one current-source fact")
        return self

    def materialized_dossier_patch(self) -> dict[str, Any]:
        return _materialize_dossier_patch(self.public_projection_items)


class IntakeDossierGenerationV4(StrictFrameOutput):
    """Provider draft containing only current-source facts for an initiator."""

    public_projection_items: tuple[DossierPublicFactDraftV3, ...] = Field(
        max_length=DOSSIER_FACT_MAX_ITEMS
    )

    @model_validator(mode="after")
    def validate_fact_identity(self) -> IntakeDossierGenerationV4:
        fact_keys = tuple(item.source_row.fact_key for item in self.public_projection_items)
        if len(fact_keys) != len(set(fact_keys)):
            raise ValueError("Dossier current-source facts must have unique fact keys")
        return self


class IntakeRespondentDossierGenerationV4(IntakeDossierGenerationV4):
    dossier_delta: DossierFrameDeltaDraftV4


class IntakeDossierGenerationV5(StrictFrameOutput):
    """Provider draft with server-owned category and materiality omitted."""

    public_projection_items: tuple[DossierPublicFactDraftV5, ...] = Field(
        max_length=DOSSIER_FACT_MAX_ITEMS
    )

    @model_validator(mode="after")
    def validate_fact_identity(self) -> IntakeDossierGenerationV5:
        fact_keys = tuple(item.source_row.fact_key for item in self.public_projection_items)
        if len(fact_keys) != len(set(fact_keys)):
            raise ValueError("Dossier current-source facts must have unique fact keys")
        return self


class IntakeRespondentDossierGenerationV5(IntakeDossierGenerationV5):
    dossier_delta: DossierFrameDeltaDraftV4

def request_bound_dossier_output_types(
    *,
    existing_fact_keys: tuple[str, ...],
    new_fact_key_prefix: str,
    respondent_capacity: bool,
) -> tuple[type[BaseModel], type[DossierPublicFactDraftV5]]:
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
            max_length=(
                len(new_fact_key_prefix) + DOSSIER_NEW_FACT_SUFFIX_MAX_LENGTH
            ),
            pattern=(
                rf"^{re.escape(new_fact_key_prefix)}[A-Za-z0-9_]"
                rf"{{1,{DOSSIER_NEW_FACT_SUFFIX_MAX_LENGTH}}}$"
            ),
        ),
    ]
    fact_key_type: Any = new_key_type
    if existing_fact_keys:
        existing_key_type = Literal.__getitem__(existing_fact_keys)
        fact_key_type = existing_key_type | new_key_type

    row_type = create_model(
        f"DossierCurrentFactDraftV5_{identity}",
        __base__=DossierCurrentFactDraftV5,
        __module__=__name__,
        fact_key=(fact_key_type, ...),
    )

    item_type = create_model(
        f"DossierPublicFactDraftV5_{identity}",
        __base__=DossierPublicFactDraftV5,
        __module__=__name__,
        source_row=(row_type, ...),
    )
    frame_base: type[BaseModel] = (
        IntakeRespondentDossierGenerationV5
        if respondent_capacity
        else IntakeDossierGenerationV5
    )
    frame_type = create_model(
        f"IntakeDossierGenerationV5_{identity}",
        __base__=frame_base,
        __module__=__name__,
        public_projection_items=(
            tuple[item_type, ...],
            Field(max_length=DOSSIER_FACT_MAX_ITEMS),
        ),
    )
    return (
        frame_type,
        cast(type[DossierPublicFactDraftV5], item_type),
    )


class QualityPublicMetricDraftV2(StrictFrameOutput):
    projection_kind: Literal["DIMENSION_SCORE"]
    dimension: Dimension
    candidate_score: int = Field(ge=0, le=20)


class QualityReferencesPublicMetricDraftV2(QualityPublicMetricDraftV2):
    dimension: Literal["REFERENCES"]
    candidate_score: int = Field(ge=0, le=15)


class QualityEventStoryPublicMetricDraftV2(QualityPublicMetricDraftV2):
    dimension: Literal["EVENT_STORY"]
    candidate_score: int = Field(ge=0, le=20)


class QualityPartyPositionsPublicMetricDraftV2(QualityPublicMetricDraftV2):
    dimension: Literal["PARTY_POSITIONS"]
    candidate_score: int = Field(ge=0, le=20)


class QualityRequestedResolutionPublicMetricDraftV2(
    QualityPublicMetricDraftV2
):
    dimension: Literal["REQUESTED_RESOLUTION"]
    candidate_score: int = Field(ge=0, le=15)


class QualityRiskAndConflictsPublicMetricDraftV2(
    QualityPublicMetricDraftV2
):
    dimension: Literal["RISK_AND_CONFLICTS"]
    candidate_score: int = Field(ge=0, le=15)


class QualityNextActionClarityPublicMetricDraftV2(
    QualityPublicMetricDraftV2
):
    dimension: Literal["NEXT_ACTION_CLARITY"]
    candidate_score: int = Field(ge=0, le=15)


class QualityPublicGapDraftV2(StrictFrameOutput):
    projection_kind: Literal["BLOCKING_GAP"]
    dimension: Dimension
    question: QualityQuestion
    linked_fact_keys: tuple[Identifier, ...] = Field(max_length=16)

    @model_validator(mode="after")
    def validate_gap(self) -> QualityPublicGapDraftV2:
        _validate_gap_question_and_keys(self.question, self.linked_fact_keys)
        return self


QualityPublicProjectionValueV2: TypeAlias = (
    QualityReferencesPublicMetricDraftV2
    | QualityEventStoryPublicMetricDraftV2
    | QualityPartyPositionsPublicMetricDraftV2
    | QualityRequestedResolutionPublicMetricDraftV2
    | QualityRiskAndConflictsPublicMetricDraftV2
    | QualityNextActionClarityPublicMetricDraftV2
    | QualityPublicGapDraftV2
)


class QualityPublicProjectionDraftV2(
    RootModel[QualityPublicProjectionValueV2]
):
    model_config = ConfigDict(frozen=True)

class QualityFrameValueV2(StrictFrameOutput):
    assessment_reasoning: QualityReasoning


QualityScoreProjectionValueV3: TypeAlias = (
    QualityReferencesPublicMetricDraftV2
    | QualityEventStoryPublicMetricDraftV2
    | QualityPartyPositionsPublicMetricDraftV2
    | QualityRequestedResolutionPublicMetricDraftV2
    | QualityRiskAndConflictsPublicMetricDraftV2
    | QualityNextActionClarityPublicMetricDraftV2
)


class QualityScoreProjectionDraftV3(RootModel[QualityScoreProjectionValueV3]):
    model_config = ConfigDict(frozen=True)


class QualityGapCandidateDraftV3(StrictFrameOutput):
    dimension: Dimension
    question: QualityCandidateQuestion
    linked_fact_keys: tuple[Identifier, ...] = Field(
        max_length=16,
        json_schema_extra={"uniqueItems": True},
    )


class IntakeQualityGenerationV3(StrictFrameOutput):
    """Provider draft with a wire-fixed score prefix and separate gap candidates."""

    public_projection_items: tuple[
        QualityReferencesPublicMetricDraftV2,
        QualityEventStoryPublicMetricDraftV2,
        QualityPartyPositionsPublicMetricDraftV2,
        QualityRequestedResolutionPublicMetricDraftV2,
        QualityRiskAndConflictsPublicMetricDraftV2,
        QualityNextActionClarityPublicMetricDraftV2,
    ]
    gap_candidates: tuple[QualityGapCandidateDraftV3, ...] = Field(max_length=6)
    quality: QualityFrameValueV2


class IntakeQualityFrameV2(StrictFrameOutput):
    public_projection_items: tuple[QualityPublicProjectionDraftV2, ...] = Field(
        min_length=6, max_length=12
    )
    quality: QualityFrameValueV2

    @model_validator(mode="after")
    def validate_projection_trace(self) -> IntakeQualityFrameV2:
        score_items = self.public_projection_items[: len(QUALITY_DIMENSION_ORDER)]
        scores: dict[Dimension, int] = {}
        for expected_dimension, wrapped in zip(
            QUALITY_DIMENSION_ORDER,
            score_items,
            strict=True,
        ):
            item = wrapped.root
            if not isinstance(item, QualityPublicMetricDraftV2):
                raise ValueError("Quality public trace must emit all scores before gaps")
            if item.dimension != expected_dimension:
                raise ValueError("Quality public score order differs from the contract")
            scores[expected_dimension] = item.candidate_score

        gap_items = self.public_projection_items[len(QUALITY_DIMENSION_ORDER) :]
        dimensions: list[Dimension] = []
        for wrapped in gap_items:
            item = wrapped.root
            if not isinstance(item, QualityPublicGapDraftV2):
                raise ValueError("Quality public trace cannot emit a score after gaps")
            dimensions.append(item.dimension)
            if scores[item.dimension] == QUALITY_DIMENSION_MAXIMA[item.dimension]:
                raise ValueError("A full-score dimension cannot remain blocking")
        if len(dimensions) != len(set(dimensions)):
            raise ValueError("Quality Frame allows at most one gap per dimension")
        return self


def request_bound_quality_output_types(
    *,
    existing_fact_keys: tuple[str, ...],
) -> tuple[type[IntakeQualityGenerationV3], type[BaseModel]]:
    """Expose only frozen-matrix fact keys to this independent scoring task."""

    if len(existing_fact_keys) > 200 or len(existing_fact_keys) != len(
        set(existing_fact_keys)
    ):
        raise ValueError("Quality fact-key authority is invalid")
    if any(not key.startswith("FACT_") for key in existing_fact_keys):
        raise ValueError("Quality fact-key authority only accepts formal FACT_ keys")
    identity = hashlib.sha256("\0".join(existing_fact_keys).encode("utf-8")).hexdigest()[
        :12
    ]
    if existing_fact_keys:
        linked_key_type: Any = Literal.__getitem__(existing_fact_keys)
        linked_keys_type: Any = tuple[linked_key_type, ...]
        linked_keys_limit = min(16, len(existing_fact_keys))
    else:
        linked_keys_type = tuple[Identifier, ...]
        linked_keys_limit = 0
    gap_type = create_model(
        f"QualityGapCandidateDraftV3_{identity}",
        __base__=QualityGapCandidateDraftV3,
        __module__=__name__,
        linked_fact_keys=(
            linked_keys_type,
            Field(
                max_length=linked_keys_limit,
                json_schema_extra={"uniqueItems": True},
            ),
        ),
    )
    output_type = create_model(
        f"IntakeQualityGenerationV3_{identity}",
        __base__=IntakeQualityGenerationV3,
        __module__=__name__,
        gap_candidates=(
            tuple[gap_type, ...],
            Field(max_length=6),
        ),
    )
    return (
        cast(type[IntakeQualityGenerationV3], output_type),
        QualityScoreProjectionDraftV3,
    )


ParallelFrameOutput: TypeAlias = (
    IntakeDialogueFrameV3 | IntakeDossierFrameV3 | IntakeQualityFrameV2
)

FRAME_OUTPUT_MODELS: Mapping[ParallelFrameType, type[StrictFrameOutput]] = {
    "DIALOGUE_FRAME": IntakeDialogueFrameV3,
    "DOSSIER_FRAME": IntakeDossierFrameV3,
    "QUALITY_FRAME": IntakeQualityFrameV2,
}


def materialize_request_bound_frame_output(
    frame_type: ParallelFrameType,
    value: Mapping[str, Any] | BaseModel,
    *,
    persisted_phase: str,
    respondent_capacity: bool,
    frozen_case_matrix: Mapping[str, Any] | None = None,
) -> ParallelFrameOutput:
    """Convert a bounded Provider draft into the stable sealed Frame contract."""

    payload = value.model_dump(mode="json") if isinstance(value, BaseModel) else dict(value)
    if frame_type == "DIALOGUE_FRAME":
        items = payload.get("public_projection_items")
        if persisted_phase == "WAITING_FOR_REMARK":
            draft = IntakeDialogueRemarkGenerationV4.model_validate(payload)
            disposition: DialogueRemarkDisposition | None = (
                draft.dialogue.remark_disposition
            )
            items = draft.public_projection_items
        else:
            draft = IntakeDialogueGenerationV4.model_validate(payload)
            disposition = None
            items = draft.public_projection_items
        return IntakeDialogueFrameV3.model_validate(
            {
                "public_projection_items": [
                    item.model_dump(mode="json") for item in items
                ],
                "dialogue": {"remark_disposition": disposition},
            }
        )
    if frame_type == "DOSSIER_FRAME":
        if respondent_capacity:
            draft = IntakeRespondentDossierGenerationV5.model_validate(payload)
            updates = draft.dossier_delta.respondent_claim_updates
        else:
            draft = IntakeDossierGenerationV5.model_validate(payload)
            updates = ()
        respondent_claim: dict[str, Any] | None = None
        if updates:
            update = updates[0]
            respondent_claim = {
                "attitude": update.attitude,
                "position_summary": update.position_summary,
                "alternative_proposal": (
                    update.alternative_proposals[0]
                    if update.alternative_proposals
                    else None
                ),
            }
        materialized_items = [
            materialize_request_bound_dossier_item(
                item,
                frozen_case_matrix=frozen_case_matrix,
            ).model_dump(mode="json")
            for item in draft.public_projection_items
        ]
        return IntakeDossierFrameV3.model_validate(
            {
                "public_projection_items": materialized_items,
                "dossier_delta": {"respondent_claim": respondent_claim},
            }
        )
    draft = IntakeQualityGenerationV3.model_validate(payload)
    score_items = [
        item.model_dump(mode="json") for item in draft.public_projection_items
    ]
    scores = {
        item.dimension: item.candidate_score
        for item in draft.public_projection_items
    }
    candidates_by_dimension: dict[
        Dimension,
        set[tuple[str, tuple[Identifier, ...]]],
    ] = {}
    for candidate in draft.gap_candidates:
        if (
            scores[candidate.dimension]
            == QUALITY_DIMENSION_MAXIMA[candidate.dimension]
        ):
            continue
        linked_fact_keys = tuple(dict.fromkeys(candidate.linked_fact_keys))
        candidates_by_dimension.setdefault(candidate.dimension, set()).add(
            (candidate.question, linked_fact_keys)
        )

    gap_items: list[dict[str, Any]] = []
    for dimension in QUALITY_DIMENSION_ORDER:
        candidates = candidates_by_dimension.get(dimension)
        if not candidates:
            continue
        question, linked_fact_keys = min(candidates)
        gap_items.append(
            {
                "projection_kind": "BLOCKING_GAP",
                "dimension": dimension,
                "question": question,
                "linked_fact_keys": list(linked_fact_keys),
            }
        )
    return IntakeQualityFrameV2.model_validate(
        {
            "public_projection_items": [*score_items, *gap_items],
            "quality": draft.quality.model_dump(mode="json"),
        }
    )


def materialize_request_bound_dossier_item(
    value: Mapping[str, Any] | BaseModel,
    *,
    frozen_case_matrix: Mapping[str, Any] | None,
) -> DossierPublicFactDraftV3:
    """Restore server-owned matrix classification around one Provider fact draft."""

    payload = value.model_dump(mode="json") if isinstance(value, BaseModel) else value
    draft = DossierPublicFactDraftV5.model_validate(payload)
    source = draft.source_row
    frozen_rows: dict[str, Mapping[str, Any]] = {}
    if frozen_case_matrix is not None:
        candidates = frozen_case_matrix.get("fact_rows")
        if not isinstance(candidates, list):
            raise ValueError("frozen matrix fact rows are absent")
        for candidate in candidates:
            if not isinstance(candidate, Mapping):
                raise ValueError("frozen matrix fact row is invalid")
            fact_id = candidate.get("fact_id")
            if not isinstance(fact_id, str) or fact_id in frozen_rows:
                raise ValueError("frozen matrix fact authority is invalid")
            frozen_rows[fact_id] = candidate

    if source.fact_key.startswith("FACT_"):
        prior = frozen_rows.get(source.fact_key)
        if prior is None:
            raise ValueError("Dossier fact references an unknown formal FACT_ key")
        category = prior.get("category")
        fact_target = prior.get("fact_target")
        materiality = prior.get("materiality")
    else:
        # A newly observed statement has no prior classification authority.  Keep
        # its precise target/summary and use one conservative server-owned
        # classification for the existing stable Frame contract.
        category = "OTHER"
        fact_target = source.fact_target
        materiality = "CORE"

    return DossierPublicFactDraftV3.model_validate(
        {
            "source_row": {
                "fact_key": source.fact_key,
                "category": category,
                "fact_target": fact_target,
                "materiality": materiality,
                "stance": source.stance,
                "position_summary": source.position_summary,
                "asserted_value": source.asserted_value,
            }
        }
    )


def validate_parallel_frame_output(
    frame_type: ParallelFrameType,
    value: Mapping[str, Any] | BaseModel,
) -> ParallelFrameOutput:
    model = FRAME_OUTPUT_MODELS[frame_type]
    payload = value.model_dump(mode="json") if isinstance(value, BaseModel) else value
    result = model.model_validate(payload)
    return result  # type: ignore[return-value]


def _materialize_dossier_patch(
    items: tuple[DossierPublicFactDraftV3, ...],
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


def _validate_gap_question_and_keys(
    question: str,
    linked_fact_keys: tuple[Identifier, ...],
) -> None:
    if not question.endswith("？"):
        raise ValueError("Quality gap must be one concrete Chinese question")
    if len(linked_fact_keys) != len(set(linked_fact_keys)):
        raise ValueError("Quality gap cannot repeat linked fact keys")


__all__ = [
    "DialoguePublicSegmentDraftV3",
    "DossierPublicFactDraftV3",
    "FRAME_OUTPUT_MODELS",
    "IntakeDialogueFrameV3",
    "IntakeDossierFrameV3",
    "IntakeQualityFrameV2",
    "ParallelFrameOutput",
    "QUALITY_DIMENSION_ORDER",
    "QualityPublicProjectionDraftV2",
    "materialize_request_bound_dossier_item",
    "materialize_request_bound_frame_output",
    "request_bound_dialogue_output_types",
    "request_bound_dossier_output_types",
    "request_bound_quality_output_types",
    "validate_parallel_frame_output",
]
