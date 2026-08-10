from __future__ import annotations

import math
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
RESPONDENT_OPENING_MARKER = "RESPONDENT_OPENING"


MODEL_CONTROLLED_FORBIDDEN_FIELDS = frozenset(
    {
        "memory_frame",
        "internal_handoff",
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

_PARTY_INTAKE_ROLES = ("USER", "MERCHANT")
_PARTY_INTAKE_STATE_FIELDS = frozenset({"schema_version", *_PARTY_INTAKE_ROLES})
_PARTY_INTAKE_ENTRY_FIELDS = frozenset(
    {"intake_quality", "missing_information", "handoff_notes", "admission"}
)
_PARTY_INTAKE_REMARK_STATUSES = frozenset(
    {
        "NOT_READY",
        "READY_PENDING_REMARK_INVITE",
        "WAITING_FOR_REMARK",
        "HAS_REMARKS",
        "NO_EXTRA_REMARKS",
    }
)
_PARTY_INTAKE_READY_REMARK_STATUSES = _PARTY_INTAKE_REMARK_STATUSES - {
    "NOT_READY"
}
_PARTY_INTAKE_RECOMMENDATIONS = frozenset(
    {"NEED_MORE_INFO", "ACCEPTED", "NOT_ADMISSIBLE"}
)
_PARTY_INTAKE_QUALITY_COMPONENT_MAXIMA = {
    "references": 15,
    "event_story": 20,
    "party_positions": 20,
    "requested_resolution": 15,
    "risk_and_conflicts": 15,
    "next_action_clarity": 15,
}


def _validate_party_intake_state(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != _PARTY_INTAKE_STATE_FIELDS:
        raise ValueError(
            "party_intake_state must contain exactly schema_version, USER, and MERCHANT"
        )
    if value.get("schema_version") != "party-intake-state.v1":
        raise ValueError("party_intake_state has an unsupported schema_version")

    for role in _PARTY_INTAKE_ROLES:
        entry = value.get(role)
        if not isinstance(entry, dict) or set(entry) != _PARTY_INTAKE_ENTRY_FIELDS:
            raise ValueError(
                f"party_intake_state.{role} must contain exactly the four Intake state branches"
            )

        quality = entry.get("intake_quality")
        quality_fields = {
            "score",
            "threshold",
            "ready_for_next_step",
            "score_breakdown",
            "improvement_reason",
        }
        if not isinstance(quality, dict) or set(quality) != quality_fields:
            raise ValueError(f"party_intake_state.{role}.intake_quality is malformed")
        score = quality.get("score")
        threshold = quality.get("threshold")
        ready = quality.get("ready_for_next_step")
        breakdown = quality.get("score_breakdown")
        if (
            type(score) is not int
            or not 0 <= score <= 100
            or type(threshold) is not int
            or threshold != 85
            or type(ready) is not bool
            or not isinstance(quality.get("improvement_reason"), str)
            or not isinstance(breakdown, dict)
            or set(breakdown) != set(_PARTY_INTAKE_QUALITY_COMPONENT_MAXIMA)
            or any(
                type(breakdown.get(component)) is not int
                or not 0 <= breakdown[component] <= maximum
                for component, maximum in _PARTY_INTAKE_QUALITY_COMPONENT_MAXIMA.items()
            )
            or sum(breakdown.values()) != score
        ):
            raise ValueError(
                f"party_intake_state.{role}.intake_quality violates the canonical score contract"
            )

        missing = entry.get("missing_information")
        missing_fields = {"blocking_gaps", "nice_to_have_gaps", "next_questions"}
        if (
            not isinstance(missing, dict)
            or set(missing) != missing_fields
            or any(
                not isinstance(missing.get(field), list)
                or any(not isinstance(item, str) for item in missing[field])
                for field in missing_fields
            )
        ):
            raise ValueError(
                f"party_intake_state.{role}.missing_information is malformed"
            )

        notes = entry.get("handoff_notes")
        notes_fields = {
            "remark_status",
            "phase_source_message_id",
            "latest_remark",
            "remarks",
            "instruction",
        }
        if (
            not isinstance(notes, dict)
            or set(notes) != notes_fields
            or notes.get("remark_status") not in _PARTY_INTAKE_REMARK_STATUSES
            or not isinstance(notes.get("phase_source_message_id"), str)
            or not isinstance(notes.get("latest_remark"), str)
            or not isinstance(notes.get("instruction"), str)
            or not isinstance(notes.get("remarks"), list)
        ):
            raise ValueError(f"party_intake_state.{role}.handoff_notes is malformed")
        remark_source_ids: set[str] = set()
        for remark in notes["remarks"]:
            if (
                not isinstance(remark, dict)
                or set(remark)
                != {"role", "text", "source_message_id", "turn_source"}
                or remark.get("role") != role
                or any(
                    not isinstance(remark.get(field), str)
                    for field in ("text", "source_message_id", "turn_source")
                )
            ):
                raise ValueError(
                    f"party_intake_state.{role}.handoff_notes contains a foreign or malformed remark"
                )
            source_message_id = remark["source_message_id"]
            if source_message_id in remark_source_ids:
                raise ValueError(
                    f"party_intake_state.{role}.handoff_notes repeats a remark source message"
                )
            remark_source_ids.add(source_message_id)

        remark_status = notes["remark_status"]
        latest_remark = notes["latest_remark"]
        remarks = notes["remarks"]
        if remark_status in {
            "NOT_READY",
            "READY_PENDING_REMARK_INVITE",
            "WAITING_FOR_REMARK",
        }:
            canonical_remark_state = not latest_remark and not remarks
        elif remark_status == "HAS_REMARKS":
            canonical_remark_state = (
                bool(latest_remark)
                and bool(remarks)
                and remarks[-1]["text"] == latest_remark
            )
        else:
            canonical_remark_state = (
                latest_remark == "\u65e0\u989d\u5916\u5907\u6ce8\u3002" and not remarks
            )
        if not canonical_remark_state:
            raise ValueError(
                f"party_intake_state.{role}.handoff_notes status and payload disagree"
            )

        admission = entry.get("admission")
        admission_fields = {"recommendation", "reasoning", "confidence"}
        confidence = admission.get("confidence") if isinstance(admission, dict) else None
        if (
            not isinstance(admission, dict)
            or set(admission) != admission_fields
            or admission.get("recommendation") not in _PARTY_INTAKE_RECOMMENDATIONS
            or not isinstance(admission.get("reasoning"), str)
            or type(confidence) not in {int, float}
            or not math.isfinite(float(confidence))
            or not 0.0 <= float(confidence) <= 1.0
        ):
            raise ValueError(f"party_intake_state.{role}.admission is malformed")

        if ready:
            valid_cross_state = (
                score >= 85
                and not missing["blocking_gaps"]
                and admission["recommendation"] == "ACCEPTED"
                and notes["remark_status"] in _PARTY_INTAKE_READY_REMARK_STATUSES
            )
        else:
            valid_cross_state = (
                admission["recommendation"] != "ACCEPTED"
                and notes["remark_status"] == "NOT_READY"
            )
        if not valid_cross_state:
            raise ValueError(
                f"party_intake_state.{role} readiness, handoff, and admission disagree"
            )


class StrictIntakeModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class PartyIntakeState(StrictIntakeModel):
    schema_version: Literal["party-intake-state.v1"]
    USER: dict[str, Any] = Field(max_length=4)
    MERCHANT: dict[str, Any] = Field(max_length=4)

    @model_validator(mode="after")
    def require_exact_independent_party_contract(self) -> PartyIntakeState:
        _validate_party_intake_state(self.model_dump(mode="python"))
        return self


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
    source_type: Literal[
        "INITIAL_FORM",
        "ROOM_MESSAGE",
        "FORMAL_EVENT",
        "RESPONDENT_OPENING",
    ]
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
    schema_version: Literal["intake-dossier.v2", "intake_case_detail.v1"] | None = None
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
    handoff_notes: dict[str, Any] | None = Field(default=None, max_length=64)
    party_intake_state: PartyIntakeState | None = None

    @model_validator(mode="after")
    def reject_explicit_null_branches(self) -> DossierPatch:
        if any(getattr(self, name) is None for name in self.model_fields_set):
            raise ValueError("dossier patch branches cannot be null")
        _reject_dossier_matrix_authority(
            self.model_dump(mode="python", exclude_none=True, exclude_unset=True)
        )
        if self.party_intake_state is not None:
            missing_mirrors = _PARTY_INTAKE_ENTRY_FIELDS - self.model_fields_set
            if missing_mirrors:
                raise ValueError(
                    "party_intake_state requires all four shared Intake state mirrors"
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
        strict dossier contract.  Providers can also return mutually contradictory
        readiness and recommendation enums.  Reconcile only those enum pairs at
        this model-output boundary, always toward the non-admitting state: missing
        information remains incomplete, a rejection remains reviewable, and an
        inconsistent acceptance becomes ``NEED_MORE_INFO``.  The normal MatrixPatch
        validation still runs afterwards, while every formal matrix authority field
        remains forbidden inside the dossier.
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
            "handoff_notes",
            "party_intake_state",
        ):
            if field_name in canonical_dossier and canonical_dossier[field_name] is None:
                canonical_dossier.pop(field_name)
        if "matrix_patch" in canonical_dossier:
            if "matrix_patch" in value:
                raise ValueError("matrix_patch cannot be present in both envelope locations")
            canonical["matrix_patch"] = canonical_dossier.pop("matrix_patch")
        canonical["dossier_patch"] = canonical_dossier
        missing_fields = canonical.get("missing_fields")
        has_missing_fields = isinstance(missing_fields, list | tuple) and bool(
            missing_fields
        )
        readiness = canonical.get("readiness")
        recommendation = canonical.get("recommendation")
        if has_missing_fields and readiness == "READY_TO_CONFIRM":
            readiness = "INCOMPLETE"
        if recommendation == "NOT_ADMISSIBLE":
            readiness = "NEEDS_REVIEW"
        elif recommendation == "NEED_MORE_INFO" and readiness == "READY_TO_CONFIRM":
            readiness = "INCOMPLETE"
        elif recommendation == "ACCEPTED" and readiness != "READY_TO_CONFIRM":
            recommendation = "NEED_MORE_INFO"
        canonical["readiness"] = readiness
        canonical["recommendation"] = recommendation
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
