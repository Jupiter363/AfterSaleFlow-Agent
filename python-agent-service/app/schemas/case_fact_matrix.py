"""Unified, source-bound case fact matrix used from intake through adjudication."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.intake_case_matrix import (
    ClaimAttitude,
    FactCategory,
    FactMateriality,
    FactStance,
    FactTruthStatus,
    MatrixPartyMap,
)
from app.schemas.models import Identifier, LongText, ShortText, StrictModel


PartyRole = Literal["USER", "MERCHANT"]


class CaseMatrixKind(StrEnum):
    INITIATOR_FROZEN = "INITIATOR_FROZEN"
    BILATERAL_FROZEN = "BILATERAL_FROZEN"
    RESPONDENT_TIMEOUT_FROZEN = "RESPONDENT_TIMEOUT_FROZEN"
    HEARING_CLARIFIED_FROZEN = "HEARING_CLARIFIED_FROZEN"


class EvidenceCoverageStatus(StrEnum):
    PENDING_EVIDENCE_REVIEW = "PENDING_EVIDENCE_REVIEW"
    COVERED_BY_FROZEN_DOSSIER = "COVERED_BY_FROZEN_DOSSIER"
    PARTIALLY_COVERED_BY_FROZEN_DOSSIER = (
        "PARTIALLY_COVERED_BY_FROZEN_DOSSIER"
    )
    NOT_COVERED_BY_FROZEN_DOSSIER = "NOT_COVERED_BY_FROZEN_DOSSIER"


class CaseAlignmentStatus(StrEnum):
    NOT_COMPUTED = "NOT_COMPUTED"
    AGREED = "AGREED"
    PARTIALLY_AGREED = "PARTIALLY_AGREED"
    CONTESTED = "CONTESTED"
    ONE_SIDED = "ONE_SIDED"
    UNRESOLVED = "UNRESOLVED"


class CaseMatrixSourceScope(StrEnum):
    CURRENT_SOURCE = "CURRENT_SOURCE"
    PREVIOUS_MATRIX = "PREVIOUS_MATRIX"
    PREVIOUS_AND_CURRENT_SOURCE = "PREVIOUS_AND_CURRENT_SOURCE"


class CaseFactMatrixParentRef(StrictModel):
    matrix_id: Identifier
    matrix_version: Annotated[int, Field(ge=1)]
    content_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CaseFactMatrixGenerationRef(StrictModel):
    actor_role: Literal["USER", "MERCHANT", "SYSTEM"]
    source_stage: Literal[
        "INITIATOR_INTAKE",
        "RESPONDENT_INTAKE",
        "RESPONDENT_TIMEOUT",
        "HEARING_CLARIFICATION",
    ]
    latest_source_ref: Identifier
    source_context_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CaseOverviewV2(StrictModel):
    neutral_summary: LongText
    core_conflict: LongText
    summary_source_fact_ids: Annotated[
        list[Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]],
        Field(min_length=1, max_length=200),
    ]


class InitiatorClaimV2(StrictModel):
    initiator_role: PartyRole
    requested_resolution: Identifier
    requested_amount: Annotated[float, Field(ge=0)] | None = None
    requested_items: ShortText | None = None
    reason_summary: LongText
    position_summary: LongText
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=50)]


class ReportedRespondentPositionV2(StrictModel):
    respondent_role: PartyRole
    attitude: Identifier
    position_summary: LongText
    source_type: Literal["INITIATOR_SUBJECTIVE_REPORT"] = "INITIATOR_SUBJECTIVE_REPORT"
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=50)]


class DirectRespondentPositionV2(StrictModel):
    respondent_role: PartyRole
    attitude: ClaimAttitude
    position_summary: LongText
    alternative_proposal: LongText | None = None
    source_type: Literal["RESPONDENT_DIRECT_INTAKE"] = "RESPONDENT_DIRECT_INTAKE"
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=50)]


class CaseClaimsV2(StrictModel):
    initiator_claim: InitiatorClaimV2
    respondent_reported_by_initiator: ReportedRespondentPositionV2 | None = None
    respondent_direct: DirectRespondentPositionV2 | None = None
    claim_conflict: LongText | None = None


class CaseFactOriginV2(StrictModel):
    introduced_stage: Literal[
        "INITIATOR_INTAKE",
        "RESPONDENT_INTAKE",
        "HEARING_CLARIFICATION",
    ]
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=50)]


class CaseFactPartyPositionV2(StrictModel):
    stance: FactStance
    position_summary: LongText
    asserted_value: ShortText | None = None
    source_type: Literal["DIRECT_PARTY_STATEMENT", "NO_DIRECT_POSITION"]
    source_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(default_factory=list)

    @model_validator(mode="after")
    def source_semantics(self) -> "CaseFactPartyPositionV2":
        if self.stance == FactStance.NOT_ADDRESSED:
            if self.source_refs or self.source_type != "NO_DIRECT_POSITION":
                raise ValueError("NOT_ADDRESSED position cannot have direct sources")
        elif not self.source_refs or self.source_type != "DIRECT_PARTY_STATEMENT":
            raise ValueError("a substantive party position requires direct sources")
        return self


class CaseFactPositionsV2(StrictModel):
    USER: CaseFactPartyPositionV2
    MERCHANT: CaseFactPartyPositionV2

    def for_role(self, role: PartyRole) -> CaseFactPartyPositionV2:
        return self.USER if role == "USER" else self.MERCHANT


class CaseFactAlignmentV2(StrictModel):
    status: CaseAlignmentStatus
    agreed_statement: LongText | None = None
    conflict_summary: LongText | None = None


class CaseFactRowV2(StrictModel):
    fact_id: Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]
    category: FactCategory
    fact_target: LongText
    materiality: FactMateriality
    origin: CaseFactOriginV2
    positions: CaseFactPositionsV2
    party_alignment: CaseFactAlignmentV2
    requires_resolution: bool | None
    truth_status: Literal[FactTruthStatus.NOT_EVALUATED] = FactTruthStatus.NOT_EVALUATED
    evidence_coverage_status: EvidenceCoverageStatus | None = None

    @model_validator(mode="after")
    def deterministic_fields_are_consistent(self) -> "CaseFactRowV2":
        status = self.party_alignment.status
        if status == CaseAlignmentStatus.NOT_COMPUTED:
            if self.requires_resolution is not None:
                raise ValueError("NOT_COMPUTED alignment requires null requires_resolution")
        elif self.requires_resolution != (status != CaseAlignmentStatus.AGREED):
            raise ValueError("requires_resolution must be derived from party_alignment")
        return self


class CaseFactRelationshipV2(StrictModel):
    relationship_type: Literal["CORRECTS", "QUALIFIES", "DUPLICATES"]
    from_fact_id: Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]
    to_fact_id: Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class CaseFactIndexesV2(StrictModel):
    not_computed_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    agreed_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    partially_agreed_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    contested_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    one_sided_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    unresolved_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    core_fact_ids: list[str] = Field(default_factory=list, max_length=200)
    requires_resolution_fact_ids: list[str] = Field(default_factory=list, max_length=200)


class CaseFactMatrixV2(StrictModel):
    schema_version: Literal["case_fact_matrix.v2"] = "case_fact_matrix.v2"
    case_id: Annotated[str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
    matrix_id: Identifier
    matrix_version: Annotated[int, Field(ge=1)]
    matrix_kind: CaseMatrixKind
    parent_ref: CaseFactMatrixParentRef | None = None
    content_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    party_map: MatrixPartyMap
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=256)]
    case_overview: CaseOverviewV2
    claims: CaseClaimsV2
    fact_rows: Annotated[list[CaseFactRowV2], Field(min_length=1, max_length=200)]
    fact_relationships: Annotated[list[CaseFactRelationshipV2], Field(max_length=100)] = Field(
        default_factory=list
    )
    generation_ref: CaseFactMatrixGenerationRef
    fact_indexes: CaseFactIndexesV2

    @model_validator(mode="after")
    def matrix_invariants(self) -> "CaseFactMatrixV2":
        ids = [row.fact_id for row in self.fact_rows]
        if len(ids) != len(set(ids)):
            raise ValueError("case fact ids must be unique")
        known = set(ids)
        if not set(self.case_overview.summary_source_fact_ids).issubset(known):
            raise ValueError("case overview references unknown facts")
        if self.claims.initiator_claim.initiator_role != self.party_map.initiator_role:
            raise ValueError("initiator claim role must match party map")
        if self.matrix_kind == CaseMatrixKind.INITIATOR_FROZEN:
            if self.claims.respondent_direct is not None:
                raise ValueError("initiator matrix cannot contain a direct respondent claim")
            if any(
                row.party_alignment.status != CaseAlignmentStatus.NOT_COMPUTED
                for row in self.fact_rows
            ):
                raise ValueError("initiator matrix alignment must remain NOT_COMPUTED")
        return self


class RespondentClaimDeltaV2(StrictModel):
    attitude: ClaimAttitude
    position_summary: LongText
    alternative_proposal: LongText | None = None


class CaseFactDeltaRowV2(StrictModel):
    fact_key: Annotated[
        str,
        Field(pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$"),
    ]
    category: FactCategory
    fact_target: LongText
    materiality: FactMateriality
    stance: FactStance = FactStance.CONFIRM
    position_summary: LongText
    asserted_value: ShortText | None = None
    source_scope: CaseMatrixSourceScope
    agreed_statement: LongText | None = None
    conflict_summary: LongText | None = None

    @model_validator(mode="after")
    def delta_position_is_substantive(self) -> "CaseFactDeltaRowV2":
        if self.stance == FactStance.NOT_ADDRESSED:
            if self.fact_key.startswith("NEW_"):
                raise ValueError("a new matrix fact cannot be NOT_ADDRESSED")
            if self.source_scope != CaseMatrixSourceScope.PREVIOUS_MATRIX:
                raise ValueError(
                    "NOT_ADDRESSED is only valid when carrying a previous matrix fact"
                )
            if self.asserted_value is not None:
                raise ValueError("NOT_ADDRESSED cannot contain asserted_value")
        return self


class CaseFactMatrixDeltaV2(StrictModel):
    schema_version: Literal["case_fact_matrix.delta.v2"] = "case_fact_matrix.delta.v2"
    fact_rows: Annotated[list[CaseFactDeltaRowV2], Field(min_length=1, max_length=200)]
    summary_source_fact_keys: Annotated[list[str], Field(min_length=1, max_length=200)]
    respondent_claim: RespondentClaimDeltaV2 | None = None

    @model_validator(mode="after")
    def keys_are_valid(self) -> "CaseFactMatrixDeltaV2":
        keys = [row.fact_key for row in self.fact_rows]
        if len(keys) != len(set(keys)):
            raise ValueError("case fact delta keys must be unique")
        if not set(self.summary_source_fact_keys).issubset(set(keys)):
            raise ValueError("summary_source_fact_keys must reference delta facts")
        return self
