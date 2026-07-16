"""Shared fact enums and initiator-intake matrix contracts."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.models import Identifier, LongText, ShortText, StrictModel


PartyRole = Literal["USER", "MERCHANT"]


class FactCategory(StrEnum):
    ORDER = "ORDER"
    PRODUCT_PAGE = "PRODUCT_PAGE"
    PAYMENT = "PAYMENT"
    FULFILLMENT = "FULFILLMENT"
    LOGISTICS = "LOGISTICS"
    PRODUCT_STATE = "PRODUCT_STATE"
    COMMUNICATION = "COMMUNICATION"
    AFTER_SALES = "AFTER_SALES"
    TIME = "TIME"
    OTHER = "OTHER"


class FactMateriality(StrEnum):
    CORE = "CORE"
    SUPPORTING = "SUPPORTING"
    CONTEXT = "CONTEXT"


class FactStance(StrEnum):
    CONFIRM = "CONFIRM"
    DENY = "DENY"
    PARTIAL = "PARTIAL"
    UNKNOWN = "UNKNOWN"
    NOT_ADDRESSED = "NOT_ADDRESSED"


class FactTruthStatus(StrEnum):
    NOT_EVALUATED = "NOT_EVALUATED"


class IntakeFactSourceScope(StrEnum):
    CURRENT_SOURCE = "CURRENT_SOURCE"
    PREVIOUS_MATRIX = "PREVIOUS_MATRIX"
    PREVIOUS_AND_CURRENT_SOURCE = "PREVIOUS_AND_CURRENT_SOURCE"


class ClaimAttitude(StrEnum):
    AGREE = "AGREE"
    PARTIALLY_AGREE = "PARTIALLY_AGREE"
    DISAGREE = "DISAGREE"
    ALTERNATIVE_PROPOSED = "ALTERNATIVE_PROPOSED"
    NEED_MORE_INFO = "NEED_MORE_INFO"
    NOT_ADDRESSED = "NOT_ADDRESSED"


class MatrixPartyMap(StrictModel):
    initiator_role: PartyRole
    respondent_role: PartyRole

    @model_validator(mode="after")
    def roles_must_differ(self) -> "MatrixPartyMap":
        if self.initiator_role == self.respondent_role:
            raise ValueError("initiator_role and respondent_role must differ")
        return self


class UnilateralMatrixSourceBinding(StrictModel):
    case_id: Annotated[str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
    source_stage: Literal["INTAKE"] = "INTAKE"
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=128)]
    latest_source_ref: Identifier
    source_context_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class UnilateralClaimPosition(StrictModel):
    initiator_role: PartyRole
    requested_resolution: Identifier
    requested_amount: Annotated[float, Field(ge=0)] | None = None
    requested_items: ShortText | None = None
    reason_summary: LongText
    position_summary: LongText
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class ReportedRespondentAttitude(StrictModel):
    respondent_role: PartyRole
    attitude: Identifier
    position_summary: LongText
    source_type: Literal["INITIATOR_SUBJECTIVE_REPORT"] = (
        "INITIATOR_SUBJECTIVE_REPORT"
    )
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class UnilateralDisputeCoreState(StrictModel):
    core_conflict: LongText
    facts_in_dispute: Annotated[list[ShortText], Field(max_length=50)] = Field(
        default_factory=list
    )
    next_verification_focus: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )


class FactOrigin(StrictModel):
    source_stage: Literal["INTAKE", "RESPONDENT_INTAKE"]
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class SourceInitiatorFactPosition(StrictModel):
    stance: Literal[FactStance.CONFIRM, FactStance.DENY, FactStance.PARTIAL]
    position_summary: LongText
    asserted_value: ShortText
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class UnilateralFactRow(StrictModel):
    fact_id: Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]
    category: FactCategory
    fact_target: LongText
    materiality: FactMateriality
    origin: FactOrigin
    initiator_position: SourceInitiatorFactPosition
    truth_status: Literal[FactTruthStatus.NOT_EVALUATED] = (
        FactTruthStatus.NOT_EVALUATED
    )


class UnilateralFactDraft(StrictModel):
    """Model-authored semantic fact; IDs and provenance are finalized in code."""

    fact_key: Annotated[
        str,
        Field(pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$"),
    ]
    category: FactCategory
    fact_target: LongText
    materiality: FactMateriality
    position_summary: LongText
    asserted_value: ShortText
    source_scope: IntakeFactSourceScope


class UnilateralCaseMatrixDraftV1(StrictModel):
    schema_version: Literal["unilateral_case_matrix.draft.v1"] = (
        "unilateral_case_matrix.draft.v1"
    )
    fact_rows: Annotated[list[UnilateralFactDraft], Field(min_length=1, max_length=100)]
    summary_source_fact_keys: Annotated[
        list[
            Annotated[
                str,
                Field(
                    pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$"
                ),
            ]
        ],
        Field(min_length=1, max_length=100),
    ]

    @model_validator(mode="after")
    def validate_fact_keys(self) -> "UnilateralCaseMatrixDraftV1":
        keys = [row.fact_key for row in self.fact_rows]
        if len(keys) != len(set(keys)):
            raise ValueError("unilateral fact draft keys must be unique")
        if not set(self.summary_source_fact_keys).issubset(set(keys)):
            raise ValueError("summary_source_fact_keys must reference draft fact rows")
        return self


class UnilateralCaseMatrixV1(StrictModel):
    schema_version: Literal["unilateral_case_matrix.v1"] = "unilateral_case_matrix.v1"
    matrix_version: Annotated[int, Field(ge=1)]
    content_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    source_binding: UnilateralMatrixSourceBinding
    party_map: MatrixPartyMap
    case_summary: LongText
    summary_source_fact_ids: Annotated[
        list[Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]],
        Field(min_length=1, max_length=100),
    ]
    claim_resolution: UnilateralClaimPosition
    reported_respondent_attitude: ReportedRespondentAttitude | None = None
    dispute_core_state: UnilateralDisputeCoreState
    fact_rows: Annotated[list[UnilateralFactRow], Field(min_length=1, max_length=100)]

    @model_validator(mode="after")
    def validate_source_matrix(self) -> "UnilateralCaseMatrixV1":
        if self.claim_resolution.initiator_role != self.party_map.initiator_role:
            raise ValueError("claim_resolution initiator_role must match party_map")
        if (
            self.reported_respondent_attitude is not None
            and self.reported_respondent_attitude.respondent_role
            != self.party_map.respondent_role
        ):
            raise ValueError(
                "reported_respondent_attitude respondent_role must match party_map"
            )
        fact_ids = [row.fact_id for row in self.fact_rows]
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("source fact_rows must have unique fact_id values")
        if not set(self.summary_source_fact_ids).issubset(set(fact_ids)):
            raise ValueError("summary_source_fact_ids must reference source fact_rows")
        bound_refs = set(self.source_binding.source_refs)
        if self.source_binding.latest_source_ref not in bound_refs:
            raise ValueError("latest_source_ref must be included in source_refs")
        referenced = set(self.claim_resolution.source_refs)
        if self.reported_respondent_attitude is not None:
            referenced.update(self.reported_respondent_attitude.source_refs)
        for row in self.fact_rows:
            referenced.update(row.origin.source_refs)
            referenced.update(row.initiator_position.source_refs)
        if not referenced.issubset(bound_refs):
            raise ValueError("all unilateral source refs must be declared in source_binding")
        return self
