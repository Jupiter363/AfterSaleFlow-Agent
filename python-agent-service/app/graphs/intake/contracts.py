from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)


Identifier = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    ),
]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
MatrixFactKey = Annotated[
    str,
    StringConstraints(
        pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$",
    ),
]
ThreadId = Annotated[
    str,
    StringConstraints(pattern=r"^grt\.v1\.[0-9a-f]{32}$"),
]
Audience = Literal["USER", "MERCHANT"]
NonNegativeInt = Annotated[int, Field(strict=True, ge=0)]
PositiveInt = Annotated[int, Field(strict=True, ge=1)]


MODEL_CONTROLLED_FORBIDDEN_FIELDS = frozenset(
    {
        "memory_frame",
        "internal_handoff",
        "handoff_notes",
        "hidden_reasoning",
        "chain_of_thought",
        "tool_calls",
        "tool_parameters",
        "writer_mode",
        "credentials",
        "credential",
        "password",
        "api_key",
        "access_token",
        "refresh_token",
        "authorization_header",
        "private_key",
        "client_secret",
        "raw_audit_records",
        "audit_records",
        "reviewer_notes",
        "other_party_private_messages",
        "opposing_party_private_messages",
        "private_conversation",
        "internal_notes",
        "opposing_party_messages",
        "opposing_party_private",
        "other_party_messages",
        "other_party_private",
        "trusted_model_profile",
        "prompt_version",
        "model_profile_id",
        "policy_version",
        "guardrail_version",
        "tool_policy_version",
        "process_state",
        "case_status",
        "room_transition",
        "evidence_deadline",
        "review_instructions",
        "tool_instructions",
        "open_evidence",
        "complete_party",
        "send_summons",
        "execute_tool",
        "admit_case",
        "cancel_case",
        "cancel_intake",
        "freeze_matrix",
        "open_room",
        "set_deadline",
        "invite_participant",
    }
)

_DOSSIER_MATRIX_AUTHORITY_FIELDS = frozenset(
    {
        "case_fact_matrix",
        "unilateral_case_matrix",
        "matrix_patch",
        "matrix_id",
        "matrix_version",
        "matrix_kind",
        "source_binding",
        "generation_ref",
        "parent_ref",
        "party_map",
        "fact_indexes",
        "fact_relationships",
        "summary_source_fact_ids",
        "truth_status",
        "evidence_coverage_status",
    }
)
_DOSSIER_MATRIX_SCHEMA_VERSIONS = frozenset(
    {
        "unilateral_case_matrix.v1",
        "unilateral_case_matrix.draft.v1",
        "case_fact_matrix.v2",
        "case_fact_matrix.delta.v2",
    }
)


class StrictIntakeModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class SnapshotMessage(StrictIntakeModel):
    message_id: Identifier
    role: Literal["HUMAN", "AI"]
    audience: Audience
    sequence: NonNegativeInt
    text: str = Field(max_length=8192)
    source_hash: Sha256


class IntakeDomainSnapshot(StrictIntakeModel):
    schema_version: Literal["intake-domain-snapshot.v2"]
    snapshot_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    room_type: Literal["INTAKE"]
    room_epoch: NonNegativeInt
    thread_id: ThreadId
    actor_scope_hash: Sha256
    agent_session_id: Identifier
    domain_revision: NonNegativeInt
    room_revision: NonNegativeInt
    projection_revision: NonNegativeInt
    visibility: Literal["PRIVATE"]
    source_refs: tuple[Identifier, ...] = Field(min_length=1, max_length=128)
    initial_case_facts: dict[str, Any] = Field(max_length=64)
    shareable_projection: dict[str, Any] = Field(max_length=64)
    own_messages: tuple[SnapshotMessage, ...] = Field(max_length=6)
    current_dossier: dict[str, Any] = Field(max_length=64)
    created_at: AwareDatetime
    snapshot_hash: Sha256

    @model_validator(mode="after")
    def unique_source_refs(self) -> IntakeDomainSnapshot:
        if len(self.source_refs) != len(set(self.source_refs)):
            raise ValueError("source_refs must be unique")
        return self


class IntakeTurnEvent(StrictIntakeModel):
    schema_version: Literal["intake-turn-event.v2"]
    event_id: Identifier
    message_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    room_type: Literal["INTAKE"]
    room_epoch: NonNegativeInt
    thread_id: ThreadId
    actor_scope_hash: Sha256
    agent_session_id: Identifier
    sequence_no: PositiveInt
    domain_revision: NonNegativeInt
    audience: Audience
    source_type: Literal["INITIAL_FORM", "ROOM_MESSAGE", "FORMAL_EVENT"]
    text: str = Field(min_length=1, max_length=8192)
    source_refs: tuple[Identifier, ...] = Field(min_length=1, max_length=32)
    occurred_at: AwareDatetime
    event_hash: Sha256

    @model_validator(mode="after")
    def unique_source_refs(self) -> IntakeTurnEvent:
        if len(self.source_refs) != len(set(self.source_refs)):
            raise ValueError("source_refs must be unique")
        return self


class DossierPatch(StrictIntakeModel):
    schema_version: Literal["intake-dossier.v2"] | None = None
    case_story: dict[str, Any] | None = Field(default=None, max_length=64)
    references: dict[str, Any] | None = Field(default=None, max_length=64)
    party_positions: dict[str, Any] | None = Field(default=None, max_length=64)
    dispute_focus: dict[str, Any] | None = Field(default=None, max_length=64)
    requested_resolution: dict[str, Any] | None = Field(default=None, max_length=64)
    claim_resolution: dict[str, Any] | None = Field(default=None, max_length=64)
    respondent_attitude: dict[str, Any] | None = Field(default=None, max_length=64)
    dispute_core_state: dict[str, Any] | None = Field(default=None, max_length=64)
    risk_assessment: dict[str, Any] | None = Field(default=None, max_length=64)
    missing_information: dict[str, Any] | None = Field(default=None, max_length=64)
    intake_quality: dict[str, Any] | None = Field(default=None, max_length=64)
    admission: dict[str, Any] | None = Field(default=None, max_length=64)

    @model_validator(mode="after")
    def reject_explicit_null_branches(self) -> DossierPatch:
        if any(getattr(self, name) is None for name in self.model_fields_set):
            raise ValueError("dossier patch branches cannot be null")
        _reject_dossier_matrix_authority(
            self.model_dump(mode="python", exclude_none=True, exclude_unset=True)
        )
        return self


class UnilateralFactDraft(StrictIntakeModel):
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
    fact_target: str = Field(min_length=1, max_length=20000)
    materiality: Literal["CORE", "SUPPORTING", "CONTEXT"]
    position_summary: str = Field(min_length=1, max_length=20000)
    asserted_value: str = Field(min_length=1, max_length=2000)
    source_scope: Literal[
        "CURRENT_SOURCE",
        "PREVIOUS_MATRIX",
        "PREVIOUS_AND_CURRENT_SOURCE",
    ]

    @model_validator(mode="after")
    def reject_blank_semantics(self) -> UnilateralFactDraft:
        for field in ("fact_target", "position_summary", "asserted_value"):
            if not getattr(self, field).strip():
                raise ValueError(f"{field} cannot be blank")
        return self


class UnilateralCaseMatrixDraftV1(StrictIntakeModel):
    schema_version: Literal["unilateral_case_matrix.draft.v1"]
    fact_rows: tuple[UnilateralFactDraft, ...] = Field(min_length=1, max_length=100)
    summary_source_fact_keys: tuple[MatrixFactKey, ...] = Field(
        min_length=1,
        max_length=100,
    )

    @model_validator(mode="after")
    def validate_local_fact_keys(self) -> UnilateralCaseMatrixDraftV1:
        fact_keys = tuple(row.fact_key for row in self.fact_rows)
        if len(fact_keys) != len(set(fact_keys)):
            raise ValueError("matrix draft fact keys must be unique")
        summary_keys = self.summary_source_fact_keys
        if len(summary_keys) != len(set(summary_keys)):
            raise ValueError("matrix summary fact keys must be unique")
        if not set(summary_keys) <= set(fact_keys):
            raise ValueError("matrix summary keys must reference draft fact rows")
        return self


class CaseFactDeltaRowV2(StrictIntakeModel):
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
    fact_target: str = Field(min_length=1, max_length=20000)
    materiality: Literal["CORE", "SUPPORTING", "CONTEXT"]
    stance: Literal["CONFIRM", "DENY", "PARTIAL", "UNKNOWN", "NOT_ADDRESSED"]
    position_summary: str = Field(min_length=1, max_length=20000)
    asserted_value: str | None = Field(default=None, min_length=1, max_length=2000)
    source_scope: Literal[
        "CURRENT_SOURCE",
        "PREVIOUS_MATRIX",
        "PREVIOUS_AND_CURRENT_SOURCE",
    ]
    agreed_statement: str | None = Field(default=None, min_length=1, max_length=20000)
    conflict_summary: str | None = Field(default=None, min_length=1, max_length=20000)

    @model_validator(mode="after")
    def validate_delta_semantics(self) -> CaseFactDeltaRowV2:
        for field in (
            "fact_target",
            "position_summary",
            "asserted_value",
            "agreed_statement",
            "conflict_summary",
        ):
            value = getattr(self, field)
            if isinstance(value, str) and not value.strip():
                raise ValueError(f"{field} cannot be blank")
        if self.fact_key.startswith("NEW_"):
            if self.stance == "NOT_ADDRESSED":
                raise ValueError("a new matrix fact cannot be NOT_ADDRESSED")
            if self.source_scope == "PREVIOUS_MATRIX":
                raise ValueError("a new matrix fact cannot come from PREVIOUS_MATRIX")
        if self.stance == "NOT_ADDRESSED" and (
            not self.fact_key.startswith("FACT_")
            or self.source_scope != "PREVIOUS_MATRIX"
            or self.asserted_value is not None
        ):
            raise ValueError("NOT_ADDRESSED requires a prior FACT_ row without asserted_value")
        return self


class RespondentClaimDeltaV2(StrictIntakeModel):
    attitude: Literal[
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
        "NOT_ADDRESSED",
    ]
    position_summary: str = Field(min_length=1, max_length=20000)
    alternative_proposal: str | None = Field(default=None, min_length=1, max_length=20000)

    @model_validator(mode="after")
    def reject_blank_semantics(self) -> RespondentClaimDeltaV2:
        for field in ("position_summary", "alternative_proposal"):
            value = getattr(self, field)
            if isinstance(value, str) and not value.strip():
                raise ValueError(f"{field} cannot be blank")
        return self


class CaseFactMatrixDeltaV2(StrictIntakeModel):
    schema_version: Literal["case_fact_matrix.delta.v2"]
    fact_rows: tuple[CaseFactDeltaRowV2, ...] = Field(min_length=1, max_length=200)
    summary_source_fact_keys: tuple[MatrixFactKey, ...] = Field(
        min_length=1,
        max_length=200,
    )
    respondent_claim: RespondentClaimDeltaV2 | None = None

    @model_validator(mode="after")
    def validate_local_fact_keys(self) -> CaseFactMatrixDeltaV2:
        fact_keys = tuple(row.fact_key for row in self.fact_rows)
        if len(fact_keys) != len(set(fact_keys)):
            raise ValueError("matrix delta fact keys must be unique")
        summary_keys = self.summary_source_fact_keys
        if len(summary_keys) != len(set(summary_keys)):
            raise ValueError("matrix delta summary fact keys must be unique")
        if not set(summary_keys) <= set(fact_keys):
            raise ValueError("matrix delta summary keys must reference delta fact rows")
        return self


MatrixPatch = UnilateralCaseMatrixDraftV1 | CaseFactMatrixDeltaV2


class IntakeCognitionDraft(StrictIntakeModel):
    room_utterance: str = Field(min_length=1, max_length=20000)
    dossier_patch: DossierPatch
    matrix_patch: MatrixPatch | None = None
    readiness: Literal["INCOMPLETE", "READY_TO_CONFIRM", "NEEDS_REVIEW"]
    missing_fields: tuple[Identifier, ...] = Field(max_length=30)
    recommendation: Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
    knowledge_answer_mode: Literal["NONE", "STUB"]
    confidence: float = Field(strict=True, ge=0, le=1, allow_inf_nan=False)

    @model_validator(mode="before")
    @classmethod
    def canonicalize_matrix_proposal_envelope(cls, value: Any) -> Any:
        """Canonicalize narrowly bounded provider-envelope mistakes before validation.

        Some strict-JSON providers place the already typed internal ``matrix_patch``
        beside dossier branches instead of beside ``dossier_patch``.  Accept only
        that exact, unambiguous shape and immediately move it back across the typed
        boundary.  They can also materialize every nullable dossier branch as JSON
        ``null``; at this model-output boundary only, a null patch branch means
        "omitted" and is removed.  Unknown null keys remain untouched and fail the
        strict dossier contract.  The normal MatrixPatch validation still runs
        afterwards, while every formal matrix authority field remains forbidden
        inside the dossier.
        """

        if not isinstance(value, dict):
            return value
        dossier_patch = value.get("dossier_patch")
        if not isinstance(dossier_patch, dict):
            return value

        canonical = dict(value)
        canonical_dossier = dict(dossier_patch)
        for field_name in (
            "schema_version",
            "case_story",
            "references",
            "party_positions",
            "dispute_focus",
            "requested_resolution",
            "claim_resolution",
            "respondent_attitude",
            "dispute_core_state",
            "risk_assessment",
            "missing_information",
            "intake_quality",
            "admission",
        ):
            if field_name in canonical_dossier and canonical_dossier[field_name] is None:
                canonical_dossier.pop(field_name)
        if "matrix_patch" in canonical_dossier:
            if "matrix_patch" in value:
                raise ValueError("matrix_patch cannot be present in both envelope locations")
            canonical["matrix_patch"] = canonical_dossier.pop("matrix_patch")
        canonical["dossier_patch"] = canonical_dossier
        return canonical

    @model_validator(mode="after")
    def draft_invariants(self) -> IntakeCognitionDraft:
        if len(self.missing_fields) != len(set(self.missing_fields)):
            raise ValueError("missing_fields must be unique")
        if self.readiness == "READY_TO_CONFIRM" and self.missing_fields:
            raise ValueError("ready draft cannot contain missing fields")
        _reject_model_controlled_fields(
            self.model_dump(mode="python", exclude_none=True, exclude_unset=True)
        )
        return self


class ProposalProfileVersions(StrictIntakeModel):
    graph_version: Identifier
    checkpoint_schema_version: Identifier
    prompt_version: Identifier
    model_profile_id: Identifier
    output_schema_version: Literal["intake-turn-proposal.v2"]
    policy_version: Identifier
    guardrail_version: Identifier
    tool_policy_version: Identifier


class IntakeTurnProposal(StrictIntakeModel):
    schema_version: Literal["intake-turn-proposal.v2"]
    command_id: Identifier
    logical_run_id: Identifier
    attempt_id: Identifier
    case_id: Identifier
    room_epoch: NonNegativeInt
    thread_id: ThreadId
    actor_scope_hash: Sha256
    agent_session_id: Identifier
    cognitive_revision: PositiveInt
    source_snapshot_hash: Sha256
    source_event_hash: Sha256 | None = None
    room_utterance: str = Field(min_length=1, max_length=20000)
    dossier_patch: DossierPatch
    matrix_patch: MatrixPatch | None = None
    readiness: Literal["INCOMPLETE", "READY_TO_CONFIRM", "NEEDS_REVIEW"]
    missing_fields: tuple[Identifier, ...] = Field(max_length=30)
    recommendation: Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
    knowledge_answer_mode: Literal["NONE", "STUB"]
    confidence: float = Field(strict=True, ge=0, le=1, allow_inf_nan=False)
    profile_versions: ProposalProfileVersions
    proposal_hash: Sha256

    @model_validator(mode="after")
    def unique_missing_fields(self) -> IntakeTurnProposal:
        if len(self.missing_fields) != len(set(self.missing_fields)):
            raise ValueError("missing_fields must be unique")
        if "source_event_hash" in self.model_fields_set and self.source_event_hash is None:
            raise ValueError("source_event_hash cannot be null")
        return self


def _reject_model_controlled_fields(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in MODEL_CONTROLLED_FORBIDDEN_FIELDS:
                raise ValueError(f"model-controlled field is forbidden: {key}")
            _reject_model_controlled_fields(child)
    elif isinstance(value, list | tuple):
        for child in value:
            _reject_model_controlled_fields(child)


def _reject_dossier_matrix_authority(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in _DOSSIER_MATRIX_AUTHORITY_FIELDS or (
                key == "schema_version" and child in _DOSSIER_MATRIX_SCHEMA_VERSIONS
            ):
                raise ValueError(f"dossier matrix authority field is forbidden: {key}")
            _reject_dossier_matrix_authority(child)
    elif isinstance(value, list | tuple):
        for child in value:
            _reject_dossier_matrix_authority(child)
