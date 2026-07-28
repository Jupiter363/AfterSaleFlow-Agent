from __future__ import annotations

import re
from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, NoReturn

from app.contracts.v1.codec import canonical_sha256_omitting, canonicalize
from app.graph_runtime.reducers import (
    merge_execution_receipts,
    merge_node_results,
    merge_usage_by_invocation,
)
from app.graph_runtime.state import (
    GraphStateLimits,
    validate_graph_patch,
    validate_graph_state,
)
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.contracts import (
    CaseFactMatrixDeltaV2,
    IntakeCognitionDraft,
    IntakeDomainSnapshot,
    IntakeTurnEvent,
    IntakeTurnProposal,
    UnilateralCaseMatrixDraftV1,
)
from app.graphs.intake.state import (
    IntakeGraphStateV2,
    IntakeTurnContext,
    merge_intake_bindings,
    merge_intake_messages,
    merge_intake_version_pins,
)


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_THREAD_ID = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MATRIX_FACT_ID = re.compile(r"^FACT_[A-Za-z0-9_:-]{1,123}$")
_FROZEN_MATRIX_ID = re.compile(r"^CASE_MATRIX_[A-F0-9]{20}$")
MATRIX_AUTHORITY_RECORD_KEY = "matrix-authority:v1"
_MATRIX_AUTHORITY_FIELDS = frozenset(
    {
        "schema_version",
        "kind",
        "source_snapshot_hash",
        "case_id",
        "room_epoch",
        "thread_id",
        "actor_scope_hash",
        "actor_role",
        "initiator_role",
        "proposal_mode",
        "formal_matrix_hash",
    }
)
_FROZEN_MATRIX_FIELDS = frozenset(
    {
        "schema_version",
        "case_id",
        "matrix_id",
        "matrix_version",
        "matrix_kind",
        "parent_ref",
        "content_hash",
        "party_map",
        "source_refs",
        "case_overview",
        "claims",
        "fact_rows",
        "fact_relationships",
        "generation_ref",
        "fact_indexes",
    }
)
_FROZEN_PARTY_MAP_FIELDS = frozenset({"initiator_role", "respondent_role"})
_FROZEN_OVERVIEW_FIELDS = frozenset({"neutral_summary", "core_conflict", "summary_source_fact_ids"})
_FROZEN_CLAIM_FIELDS = frozenset(
    {
        "initiator_claim",
        "respondent_reported_by_initiator",
        "respondent_direct",
        "claim_conflict",
    }
)
_FROZEN_INITIATOR_CLAIM_REQUIRED_FIELDS = frozenset(
    {
        "initiator_role",
        "requested_resolution",
        "reason_summary",
        "position_summary",
        "source_refs",
    }
)
_FROZEN_INITIATOR_CLAIM_ALLOWED_FIELDS = _FROZEN_INITIATOR_CLAIM_REQUIRED_FIELDS | frozenset(
    {"requested_amount", "requested_items"}
)
_FROZEN_REPORTED_RESPONDENT_FIELDS = frozenset(
    {"respondent_role", "attitude", "position_summary", "source_type", "source_refs"}
)
_FROZEN_ROW_FIELDS = frozenset(
    {
        "fact_id",
        "category",
        "fact_target",
        "materiality",
        "origin",
        "positions",
        "party_alignment",
        "requires_resolution",
        "truth_status",
        "evidence_coverage_status",
    }
)
_FROZEN_ORIGIN_FIELDS = frozenset({"introduced_stage", "source_refs"})
_FROZEN_POSITION_FIELDS = frozenset(
    {"stance", "position_summary", "asserted_value", "source_type", "source_refs"}
)
_FROZEN_ALIGNMENT_FIELDS = frozenset({"status", "agreed_statement", "conflict_summary"})
_FROZEN_GENERATION_FIELDS = frozenset(
    {"actor_role", "source_stage", "latest_source_ref", "source_context_hash"}
)
_FROZEN_INDEX_FIELDS = frozenset(
    {
        "not_computed_fact_ids",
        "agreed_fact_ids",
        "partially_agreed_fact_ids",
        "contested_fact_ids",
        "one_sided_fact_ids",
        "unresolved_fact_ids",
        "core_fact_ids",
        "requires_resolution_fact_ids",
    }
)
_FROZEN_CATEGORIES = frozenset(
    {
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
    }
)
_FROZEN_MATERIALITIES = frozenset({"CORE", "SUPPORTING", "CONTEXT"})
_FROZEN_INITIATOR_STANCES = frozenset({"CONFIRM", "DENY", "PARTIAL"})
_FROZEN_CLAIM_ATTITUDES = frozenset(
    {
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
        "NOT_ADDRESSED",
    }
)
_MAX_COGNITIVE_REVISION = (1 << 63) - 1
_STATE_FIELDS = frozenset(
    {
        "schema_version",
        "bindings",
        "version_pins",
        "cognitive_revision",
        "initial_snapshot_ref",
        "initial_snapshot_hash",
        "initial_domain_revision",
        "last_event_ref",
        "last_event_hash",
        "last_event_sequence",
        "messages",
        "memory_summary",
        "dossier_draft",
        "readiness",
        "missing_fields",
        "recommendation",
        "node_results",
        "execution_receipts",
        "usage_by_invocation",
        "route",
        "terminal_draft",
        "result_json",
    }
)
_REQUIRED_STATE_FIELDS = frozenset(
    {
        "schema_version",
        "bindings",
        "version_pins",
        "cognitive_revision",
        "messages",
        "memory_summary",
        "dossier_draft",
        "readiness",
        "missing_fields",
        "recommendation",
        "node_results",
        "execution_receipts",
        "usage_by_invocation",
    }
)
_COGNITION_PATCH_FIELDS = frozenset(
    {
        "cognitive_revision",
        "terminal_draft",
        "memory_summary",
        "node_results",
        "execution_receipts",
        "usage_by_invocation",
    }
)
_FORBIDDEN_KEYS = frozenset(
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
        "open_evidence",
        "complete_party",
        "send_summons",
        "execute_tool",
    }
)
_PROPOSAL_FORBIDDEN_KEYS = _FORBIDDEN_KEYS | frozenset(
    {
        "process_state",
        "case_status",
        "room_transition",
        "evidence_deadline",
        "review_instructions",
        "tool_instructions",
        "admit_case",
        "cancel_case",
        "cancel_intake",
        "freeze_matrix",
        "open_room",
        "set_deadline",
        "invite_participant",
    }
)
_INTAKE_LIMITS = GraphStateLimits(message_count=6)


def validate_state(state: IntakeGraphStateV2) -> None:
    if not isinstance(state, dict):
        raise IntakeGraphContractError("INTAKE_STATE_INVALID")
    fields = set(state)
    if not _REQUIRED_STATE_FIELDS <= fields or not fields <= _STATE_FIELDS:
        raise IntakeGraphContractError("INTAKE_STATE_FIELDS_INVALID")
    if state.get("schema_version") != "intake-graph-state.v2":
        raise IntakeGraphContractError("INTAKE_STATE_SCHEMA_INVALID")
    _reject_forbidden_keys(state)
    try:
        validate_graph_state(dict(state), limits=_INTAKE_LIMITS)
    except ValueError as error:
        raise IntakeGraphContractError(str(error)) from error
    private, _ = _validate_bindings(state.get("bindings"))
    _validate_version_pins(state.get("version_pins"))
    revision = _strict_int(state.get("cognitive_revision"), minimum=0)
    if revision > _MAX_COGNITIVE_REVISION:
        raise IntakeGraphContractError("INTAKE_COGNITIVE_REVISION_INVALID")
    _validate_messages(state.get("messages"), audience=private["audience"])
    _validate_state_payloads(state)


def ingress(context: IntakeTurnContext) -> tuple[str, dict[str, Any]]:
    kind = context.ingress_kind
    payload = context.ingress_payload
    if kind not in {"SNAPSHOT", "EVENT", "BOOTSTRAP_EVENT"} or not isinstance(payload, dict):
        raise IntakeGraphContractError("INTAKE_INGRESS_INVALID")
    _reject_forbidden_keys(payload)
    return kind, payload


def bootstrap_event_ingress(context: IntakeTurnContext) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return the two independently authorized payloads for a first-message ingress."""
    kind, payload = ingress(context)
    if kind != "BOOTSTRAP_EVENT" or set(payload) != {"snapshot", "event"}:
        raise IntakeGraphContractError("INTAKE_BOOTSTRAP_INGRESS_INVALID")
    snapshot = payload["snapshot"]
    event = payload["event"]
    if not isinstance(snapshot, dict) or not isinstance(event, dict):
        raise IntakeGraphContractError("INTAKE_BOOTSTRAP_INGRESS_INVALID")
    _reject_forbidden_keys(snapshot)
    _reject_forbidden_keys(event)
    return snapshot, event


def validate_snapshot(
    state: IntakeGraphStateV2,
    snapshot: Mapping[str, Any],
) -> None:
    _validate_model(IntakeDomainSnapshot, snapshot, "INTAKE_SNAPSHOT_SCHEMA_INVALID")
    _reject_forbidden_keys(snapshot)
    if _canonical_size(snapshot, "INTAKE_SNAPSHOT_SCHEMA_INVALID") > 262_144:
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_TOO_LARGE")
    if snapshot.get("schema_version") != "intake-domain-snapshot.v2":
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_SCHEMA_INVALID")
    _validate_binding(state, snapshot)
    if snapshot.get("visibility") != "PRIVATE":
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_VISIBILITY_INVALID")
    messages = snapshot.get("own_messages")
    if not isinstance(messages, list) or len(messages) > 6:
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGES_INVALID")
    message_ids: set[str] = set()
    sequences: list[int] = []
    source_refs = set(snapshot.get("source_refs", []))
    expected_audience = state["bindings"]["private"]["audience"]
    for message in messages:
        message_id = message.get("message_id")
        if message.get("audience") != expected_audience:
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGE_AUDIENCE_MISMATCH")
        if message_id in message_ids:
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGE_ID_CONFLICT")
        if message_id not in source_refs:
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGE_SOURCE_MISSING")
        message_ids.add(message_id)
        sequences.append(message["sequence"])
        if len(message["text"].encode("utf-8")) > 8192:
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGE_TOO_LARGE")
    if len(sequences) != len(set(sequences)) or sequences != sorted(sequences):
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGE_SEQUENCE_INVALID")
    _verify_self_hash(snapshot, "snapshot_hash", "INTAKE_SNAPSHOT_HASH_INVALID")
    _validate_safe_json(snapshot)


def validate_event(state: IntakeGraphStateV2, event: Mapping[str, Any]) -> None:
    _validate_model(IntakeTurnEvent, event, "INTAKE_EVENT_SCHEMA_INVALID")
    _reject_forbidden_keys(event)
    if _canonical_size(event, "INTAKE_EVENT_SCHEMA_INVALID") > 32_768:
        raise IntakeGraphContractError("INTAKE_EVENT_TOO_LARGE")
    if event.get("schema_version") != "intake-turn-event.v2":
        raise IntakeGraphContractError("INTAKE_EVENT_SCHEMA_INVALID")
    _validate_binding(state, event)
    if event.get("audience") != state["bindings"]["private"]["audience"]:
        raise IntakeGraphContractError("INTAKE_EVENT_AUDIENCE_MISMATCH")
    text = event.get("text")
    if not isinstance(text, str) or not text or len(text.encode("utf-8")) > 8192:
        raise IntakeGraphContractError("INTAKE_EVENT_TEXT_INVALID")
    if event.get("message_id") not in event.get("source_refs", []):
        raise IntakeGraphContractError("INTAKE_EVENT_MESSAGE_SOURCE_MISSING")
    _verify_self_hash(event, "event_hash", "INTAKE_EVENT_HASH_INVALID")
    _validate_safe_json(event)


def validate_terminal_proposal(proposal: Mapping[str, Any]) -> None:
    if isinstance(proposal.get("confidence"), bool):
        raise IntakeGraphContractError("INTAKE_PROPOSAL_SCHEMA_INVALID")
    _validate_model(IntakeTurnProposal, proposal, "INTAKE_PROPOSAL_SCHEMA_INVALID")
    _reject_forbidden_keys(proposal, forbidden=_PROPOSAL_FORBIDDEN_KEYS)
    if proposal.get("schema_version") != "intake-turn-proposal.v2":
        raise IntakeGraphContractError("INTAKE_PROPOSAL_SCHEMA_INVALID")
    if _canonical_size(proposal, "INTAKE_PROPOSAL_SCHEMA_INVALID") > 65_536:
        raise IntakeGraphContractError("INTAKE_PROPOSAL_TOO_LARGE")
    _verify_self_hash(proposal, "proposal_hash", "INTAKE_PROPOSAL_HASH_INVALID")
    _validate_safe_json(proposal)


def validate_cognition_patch(
    state: IntakeGraphStateV2,
    patch: Mapping[str, Any],
) -> dict[str, Any]:
    fields = set(patch) if isinstance(patch, Mapping) else set()
    if not {"cognitive_revision", "terminal_draft"} <= fields or not fields <= (
        _COGNITION_PATCH_FIELDS
    ):
        raise IntakeGraphContractError("INTAKE_COGNITION_PATCH_FIELDS_INVALID")
    expected_revision = _next_revision(state)
    if patch.get("cognitive_revision") != expected_revision:
        raise IntakeGraphContractError("INTAKE_COGNITIVE_REVISION_INVALID")
    draft = patch.get("terminal_draft")
    if not isinstance(draft, Mapping) or isinstance(draft.get("confidence"), bool):
        raise IntakeGraphContractError("INTAKE_COGNITION_DRAFT_INVALID")
    _validate_model(IntakeCognitionDraft, draft, "INTAKE_COGNITION_DRAFT_INVALID")
    _reject_forbidden_keys(draft, forbidden=_PROPOSAL_FORBIDDEN_KEYS)
    _validate_safe_json(draft)
    if _canonical_size(draft, "INTAKE_COGNITION_DRAFT_INVALID") > 65_536:
        raise IntakeGraphContractError("INTAKE_COGNITION_DRAFT_TOO_LARGE")
    validate_matrix_patch(state, draft.get("matrix_patch"))
    return validate_node_patch(state, patch)


def validate_node_patch(
    state: IntakeGraphStateV2,
    patch: Mapping[str, Any],
) -> dict[str, Any]:
    if not isinstance(patch, Mapping) or not set(patch) <= _STATE_FIELDS:
        raise IntakeGraphContractError("INTAKE_NODE_PATCH_FIELDS_INVALID")
    try:
        validate_graph_patch(dict(patch), limits=_INTAKE_LIMITS)
        normalized = deepcopy(dict(patch))
        candidate = deepcopy(dict(state))
        for field, reducer in (
            ("bindings", merge_intake_bindings),
            ("version_pins", merge_intake_version_pins),
            ("messages", merge_intake_messages),
            ("node_results", merge_node_results),
            ("execution_receipts", merge_execution_receipts),
            ("usage_by_invocation", merge_usage_by_invocation),
        ):
            if field in normalized:
                candidate[field] = reducer(candidate.get(field), normalized.pop(field))
        candidate.update(normalized)
        validate_state(candidate)  # type: ignore[arg-type]
    except IntakeGraphContractError:
        raise
    except ValueError as error:
        raise IntakeGraphContractError(str(error)) from error
    return deepcopy(dict(patch))


def validate_proposal_binding(
    state: IntakeGraphStateV2,
    proposal: Mapping[str, Any],
) -> None:
    private = state["bindings"]["private"]
    command = state["bindings"]["command"]
    expected = {
        "command_id": command["command_id"],
        "logical_run_id": command["logical_run_id"],
        "attempt_id": command["attempt_id"],
        "case_id": private["case_id"],
        "room_epoch": private["room_epoch"],
        "thread_id": private["thread_id"],
        "actor_scope_hash": private["actor_scope_hash"],
        "agent_session_id": private["agent_session_id"],
        "cognitive_revision": state["cognitive_revision"],
        "source_snapshot_hash": state.get("initial_snapshot_hash"),
    }
    if any(proposal.get(field) != value for field, value in expected.items()):
        raise IntakeGraphContractError("INTAKE_PROPOSAL_BINDING_MISMATCH")
    expected_event_hash = state.get("last_event_hash")
    if proposal.get("source_event_hash") != expected_event_hash:
        raise IntakeGraphContractError("INTAKE_PROPOSAL_SOURCE_MISMATCH")
    pins = state["version_pins"]
    expected_profiles = {
        "graph_version": pins["graph_version"],
        "checkpoint_schema_version": pins["checkpoint_schema_version"],
        "prompt_version": pins["prompt_version"],
        "model_profile_id": pins["model_profile_id"],
        "output_schema_version": pins["output_schema_version"],
        "policy_version": pins["policy_version"],
        "guardrail_version": pins["guardrail_version"],
        "tool_policy_version": pins["tool_policy_version"],
    }
    if proposal.get("profile_versions") != expected_profiles:
        raise IntakeGraphContractError("INTAKE_PROPOSAL_PROFILE_MISMATCH")
    validate_matrix_patch(state, proposal.get("matrix_patch"))


def validate_matrix_patch(
    state: IntakeGraphStateV2,
    matrix_patch: Any,
) -> None:
    if matrix_patch is None:
        return
    if not isinstance(matrix_patch, Mapping):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    schema_version = matrix_patch.get("schema_version")
    if schema_version == "unilateral_case_matrix.draft.v1":
        model_type = UnilateralCaseMatrixDraftV1
        required_mode = "UNILATERAL"
    elif schema_version == "case_fact_matrix.delta.v2":
        model_type = CaseFactMatrixDeltaV2
        required_mode = "RESPONDENT_DELTA"
    else:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    _validate_model(model_type, matrix_patch, "INTAKE_MATRIX_PATCH_INVALID")
    actor_role, frozen_authority = _require_matrix_authority(
        state,
        required_mode=required_mode,
    )
    current_source = state.get("last_event_ref") or state.get("initial_snapshot_ref")
    if not isinstance(current_source, str) or not _IDENTIFIER.fullmatch(current_source):
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_SOURCE_MISSING")

    if required_mode == "RESPONDENT_DELTA":
        if frozen_authority is None:
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
        previous_by_id = dict(frozen_authority.facts_by_id)
        previous_by_fingerprint = dict(frozen_authority.facts_by_fingerprint)
    else:
        previous_by_id, previous_by_fingerprint = _visible_unilateral_fact_index(
            state.get("dossier_draft")
        )
    resolved_by_key: dict[str, tuple[str, bytes | str]] = {}
    resolved: set[tuple[str, bytes | str]] = set()
    rows = matrix_patch["fact_rows"]
    for row in rows:
        fact_key = row["fact_key"]
        fingerprint = canonicalize(
            {
                "category": row["category"],
                "fact_target": row["fact_target"],
            }
        )
        prior: Mapping[str, Any] | None
        if fact_key.startswith("FACT_"):
            prior = previous_by_id.get(fact_key)
            if prior is None:
                raise IntakeGraphContractError("INTAKE_MATRIX_FACT_UNKNOWN")
            if fingerprint != _matrix_row_fingerprint(prior):
                raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
            if row["materiality"] != prior.get("materiality"):
                raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
            resolution: tuple[str, bytes | str] = ("FACT", fact_key)
        else:
            if row["source_scope"] == "PREVIOUS_MATRIX":
                raise IntakeGraphContractError("INTAKE_MATRIX_SOURCE_SCOPE_INVALID")
            prior_id = previous_by_fingerprint.get(fingerprint)
            if prior_id is not None:
                raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
            prior = None
            resolution = ("NEW", fingerprint)

        if resolution in resolved:
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_ID_CONFLICT")
        resolved.add(resolution)
        resolved_by_key[fact_key] = resolution

        if row["source_scope"] == "PREVIOUS_MATRIX":
            if prior is None or not _matches_previous_matrix_semantics(
                row,
                prior,
                actor_role=actor_role,
                patch_kind=required_mode,
            ):
                raise IntakeGraphContractError("INTAKE_MATRIX_PREVIOUS_FACT_MUTATED")

    if required_mode == "RESPONDENT_DELTA":
        carried_fact_ids = {row["fact_key"] for row in rows if row["fact_key"].startswith("FACT_")}
        if carried_fact_ids != set(previous_by_id):
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_MEMBERSHIP_INVALID")

    summary_resolutions: set[tuple[str, bytes | str]] = set()
    for fact_key in matrix_patch["summary_source_fact_keys"]:
        resolution = resolved_by_key.get(fact_key)
        if resolution is None or resolution in summary_resolutions:
            raise IntakeGraphContractError("INTAKE_MATRIX_SUMMARY_SOURCE_INVALID")
        summary_resolutions.add(resolution)


def matrix_authority_record(
    state: IntakeGraphStateV2,
    snapshot: Mapping[str, Any],
) -> dict[str, Any]:
    private = state["bindings"]["private"]
    actor_role = private["audience"]
    initial_facts = snapshot.get("initial_case_facts")
    initiator_role = (
        initial_facts.get("initiator_role") if isinstance(initial_facts, Mapping) else None
    )
    proposal_mode = "NONE"
    formal_matrix_hash: str | None = None
    if initiator_role in {"USER", "MERCHANT"}:
        if actor_role == initiator_role:
            proposal_mode = "UNILATERAL"
        else:
            frozen_authority = _locked_initiator_matrix_authority(
                snapshot.get("current_dossier"),
                case_id=private["case_id"],
                initiator_role=initiator_role,
                respondent_role=actor_role,
            )
            if frozen_authority is not None:
                formal_matrix_hash = frozen_authority.content_hash
                proposal_mode = "RESPONDENT_DELTA"
    return {
        "schema_version": "intake-matrix-authority.v1",
        "kind": "MATRIX_AUTHORITY",
        "source_snapshot_hash": snapshot["snapshot_hash"],
        "case_id": private["case_id"],
        "room_epoch": private["room_epoch"],
        "thread_id": private["thread_id"],
        "actor_scope_hash": private["actor_scope_hash"],
        "actor_role": actor_role,
        "initiator_role": initiator_role if initiator_role in {"USER", "MERCHANT"} else None,
        "proposal_mode": proposal_mode,
        "formal_matrix_hash": formal_matrix_hash,
    }


def _require_matrix_authority(
    state: IntakeGraphStateV2,
    *,
    required_mode: str,
) -> tuple[str, _FrozenInitiatorMatrixAuthority | None]:
    record = state.get("node_results", {}).get(MATRIX_AUTHORITY_RECORD_KEY)
    private = state["bindings"]["private"]
    if not isinstance(record, Mapping) or set(record) != _MATRIX_AUTHORITY_FIELDS:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    expected = {
        "schema_version": "intake-matrix-authority.v1",
        "kind": "MATRIX_AUTHORITY",
        "source_snapshot_hash": state.get("initial_snapshot_hash"),
        "case_id": private["case_id"],
        "room_epoch": private["room_epoch"],
        "thread_id": private["thread_id"],
        "actor_scope_hash": private["actor_scope_hash"],
        "actor_role": private["audience"],
    }
    if any(record.get(field) != value for field, value in expected.items()):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    actor_role = record.get("actor_role")
    initiator_role = record.get("initiator_role")
    if (
        actor_role not in {"USER", "MERCHANT"}
        or initiator_role not in {"USER", "MERCHANT"}
        or record.get("proposal_mode") != required_mode
    ):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    if required_mode == "UNILATERAL":
        if actor_role != initiator_role or record.get("formal_matrix_hash") is not None:
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
        frozen_authority = None
    elif required_mode == "RESPONDENT_DELTA":
        frozen_authority = _locked_initiator_matrix_authority(
            state.get("dossier_draft"),
            case_id=private["case_id"],
            initiator_role=initiator_role,
            respondent_role=actor_role,
        )
        if (
            actor_role == initiator_role
            or frozen_authority is None
            or frozen_authority.content_hash != record.get("formal_matrix_hash")
        ):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    else:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    return actor_role, frozen_authority


@dataclass(frozen=True, slots=True)
class _FrozenInitiatorMatrixAuthority:
    content_hash: str
    facts_by_id: Mapping[str, Mapping[str, Any]]
    facts_by_fingerprint: Mapping[bytes, str]


def _locked_initiator_matrix_authority(
    dossier: Any,
    *,
    case_id: str,
    initiator_role: str,
    respondent_role: str,
) -> _FrozenInitiatorMatrixAuthority | None:
    if not isinstance(dossier, Mapping):
        return None
    matrix = dossier.get("case_fact_matrix")
    if matrix is None:
        return None
    matrix = _formal_object(matrix, _FROZEN_MATRIX_FIELDS)
    content_hash = _formal_hash(matrix, "content_hash")
    try:
        expected_hash = canonical_sha256_omitting(matrix, "content_hash")
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID") from error
    matrix_version = matrix.get("matrix_version")
    matrix_id = _formal_text(matrix, "matrix_id", 128)
    if (
        _formal_text(matrix, "schema_version", 64) != "case_fact_matrix.v2"
        or _formal_identifier(matrix, "case_id") != case_id
        or _formal_text(matrix, "matrix_kind", 64) != "INITIATOR_FROZEN"
        or matrix.get("parent_ref") is not None
        or isinstance(matrix_version, bool)
        or not isinstance(matrix_version, int)
        or matrix_version != 1
        or not _FROZEN_MATRIX_ID.fullmatch(matrix_id)
        or content_hash != expected_hash
    ):
        _reject_frozen_matrix()

    party_map = _formal_child_object(matrix, "party_map", _FROZEN_PARTY_MAP_FIELDS)
    if (
        party_map.get("initiator_role") != initiator_role
        or party_map.get("respondent_role") != respondent_role
        or initiator_role == respondent_role
    ):
        _reject_frozen_matrix()

    source_refs = _formal_identifier_list(matrix, "source_refs", minimum=1, maximum=256)
    declared_sources = set(source_refs)
    generation = _formal_child_object(
        matrix,
        "generation_ref",
        _FROZEN_GENERATION_FIELDS,
    )
    latest_source_ref = _formal_identifier(generation, "latest_source_ref")
    if (
        generation.get("actor_role") != initiator_role
        or generation.get("source_stage") != "INITIATOR_INTAKE"
        or latest_source_ref not in declared_sources
    ):
        _reject_frozen_matrix()
    _formal_hash(generation, "source_context_hash")

    overview = _formal_child_object(matrix, "case_overview", _FROZEN_OVERVIEW_FIELDS)
    _formal_text(overview, "neutral_summary", 20_000)
    _formal_text(overview, "core_conflict", 20_000)
    summary_fact_ids = _formal_identifier_list(
        overview,
        "summary_source_fact_ids",
        minimum=1,
        maximum=200,
        pattern=_MATRIX_FACT_ID,
    )
    _validate_frozen_claims(
        matrix,
        initiator_role=initiator_role,
        respondent_role=respondent_role,
        declared_sources=declared_sources,
    )
    relationships = matrix.get("fact_relationships")
    if not isinstance(relationships, list) or relationships:
        _reject_frozen_matrix()

    fact_rows = matrix.get("fact_rows")
    if not isinstance(fact_rows, list) or not 1 <= len(fact_rows) <= 200:
        _reject_frozen_matrix()
    facts_by_id: dict[str, Mapping[str, Any]] = {}
    facts_by_fingerprint: dict[bytes, str] = {}
    core_fact_ids: list[str] = []
    for candidate in fact_rows:
        row, fact_id, is_core = _validate_frozen_fact_row(
            candidate,
            initiator_role=initiator_role,
            respondent_role=respondent_role,
            declared_sources=declared_sources,
        )
        if fact_id in facts_by_id:
            _reject_frozen_matrix()
        facts_by_id[fact_id] = row
        facts_by_fingerprint.setdefault(_matrix_row_fingerprint(row), fact_id)
        if is_core:
            core_fact_ids.append(fact_id)

    if not set(summary_fact_ids) <= set(facts_by_id):
        _reject_frozen_matrix()
    _validate_frozen_indexes(
        matrix,
        fact_ids=list(facts_by_id),
        core_fact_ids=core_fact_ids,
    )
    return _FrozenInitiatorMatrixAuthority(
        content_hash=content_hash,
        facts_by_id=facts_by_id,
        facts_by_fingerprint=facts_by_fingerprint,
    )


def _validate_frozen_claims(
    matrix: Mapping[str, Any],
    *,
    initiator_role: str,
    respondent_role: str,
    declared_sources: set[str],
) -> None:
    claims = _formal_child_object(matrix, "claims", _FROZEN_CLAIM_FIELDS)
    initiator_claim = claims.get("initiator_claim")
    if not isinstance(initiator_claim, Mapping):
        _reject_frozen_matrix()
    fields = set(initiator_claim)
    if not _FROZEN_INITIATOR_CLAIM_REQUIRED_FIELDS <= fields or not fields <= (
        _FROZEN_INITIATOR_CLAIM_ALLOWED_FIELDS
    ):
        _reject_frozen_matrix()
    if initiator_claim.get("initiator_role") != initiator_role or not _IDENTIFIER.fullmatch(
        _formal_text(initiator_claim, "requested_resolution", 128)
    ):
        _reject_frozen_matrix()
    requested_amount = initiator_claim.get("requested_amount")
    if requested_amount is not None and (
        isinstance(requested_amount, bool)
        or not isinstance(requested_amount, int | float)
        or requested_amount < 0
    ):
        _reject_frozen_matrix()
    if initiator_claim.get("requested_items") is not None:
        _formal_text(initiator_claim, "requested_items", 2_000)
    _formal_text(initiator_claim, "reason_summary", 20_000)
    _formal_text(initiator_claim, "position_summary", 20_000)
    initiator_sources = _formal_identifier_list(
        initiator_claim,
        "source_refs",
        minimum=1,
        maximum=50,
    )
    if not set(initiator_sources) <= declared_sources:
        _reject_frozen_matrix()
    if claims.get("respondent_direct") is not None or claims.get("claim_conflict") is not None:
        _reject_frozen_matrix()

    reported = claims.get("respondent_reported_by_initiator")
    if reported is None:
        return
    reported = _formal_object(reported, _FROZEN_REPORTED_RESPONDENT_FIELDS)
    attitude = _formal_identifier(reported, "attitude")
    if (
        reported.get("respondent_role") != respondent_role
        or attitude not in _FROZEN_CLAIM_ATTITUDES
        or reported.get("source_type") != "INITIATOR_SUBJECTIVE_REPORT"
    ):
        _reject_frozen_matrix()
    _formal_text(reported, "position_summary", 20_000)
    reported_sources = _formal_identifier_list(
        reported,
        "source_refs",
        minimum=1,
        maximum=50,
    )
    if not set(reported_sources) <= declared_sources:
        _reject_frozen_matrix()


def _validate_frozen_fact_row(
    candidate: Any,
    *,
    initiator_role: str,
    respondent_role: str,
    declared_sources: set[str],
) -> tuple[Mapping[str, Any], str, bool]:
    row = _formal_object(candidate, _FROZEN_ROW_FIELDS)
    fact_id = _formal_text(row, "fact_id", 128)
    category = _formal_text(row, "category", 64)
    materiality = _formal_text(row, "materiality", 32)
    if (
        not _MATRIX_FACT_ID.fullmatch(fact_id)
        or category not in _FROZEN_CATEGORIES
        or materiality not in _FROZEN_MATERIALITIES
    ):
        _reject_frozen_matrix()
    _formal_text(row, "fact_target", 20_000)
    if (
        row.get("truth_status") != "NOT_EVALUATED"
        or row.get("evidence_coverage_status") != "PENDING_EVIDENCE_REVIEW"
        or row.get("requires_resolution") is not None
    ):
        _reject_frozen_matrix()

    origin = _formal_child_object(row, "origin", _FROZEN_ORIGIN_FIELDS)
    origin_sources = _formal_identifier_list(
        origin,
        "source_refs",
        minimum=1,
        maximum=50,
    )
    if (
        origin.get("introduced_stage") != "INITIATOR_INTAKE"
        or not set(origin_sources) <= declared_sources
    ):
        _reject_frozen_matrix()

    positions = _formal_child_object(
        row,
        "positions",
        frozenset({initiator_role, respondent_role}),
    )
    initiator = _formal_child_object(
        positions,
        initiator_role,
        _FROZEN_POSITION_FIELDS,
    )
    initiator_sources = _formal_identifier_list(
        initiator,
        "source_refs",
        minimum=1,
        maximum=50,
    )
    if (
        initiator.get("stance") not in _FROZEN_INITIATOR_STANCES
        or initiator.get("source_type") != "DIRECT_PARTY_STATEMENT"
        or not set(initiator_sources) <= declared_sources
    ):
        _reject_frozen_matrix()
    _formal_text(initiator, "position_summary", 20_000)
    _formal_text(initiator, "asserted_value", 2_000)

    respondent = _formal_child_object(
        positions,
        respondent_role,
        _FROZEN_POSITION_FIELDS,
    )
    if (
        respondent.get("stance") != "NOT_ADDRESSED"
        or respondent.get("position_summary") != "No direct respondent position is recorded."
        or respondent.get("asserted_value") is not None
        or respondent.get("source_type") != "NO_DIRECT_POSITION"
        or respondent.get("source_refs") != []
    ):
        _reject_frozen_matrix()

    alignment = _formal_child_object(
        row,
        "party_alignment",
        _FROZEN_ALIGNMENT_FIELDS,
    )
    if (
        alignment.get("status") != "NOT_COMPUTED"
        or alignment.get("agreed_statement") is not None
        or alignment.get("conflict_summary") is not None
    ):
        _reject_frozen_matrix()
    return row, fact_id, materiality == "CORE"


def _validate_frozen_indexes(
    matrix: Mapping[str, Any],
    *,
    fact_ids: list[str],
    core_fact_ids: list[str],
) -> None:
    indexes = _formal_child_object(matrix, "fact_indexes", _FROZEN_INDEX_FIELDS)
    expected = {
        "not_computed_fact_ids": fact_ids,
        "agreed_fact_ids": [],
        "partially_agreed_fact_ids": [],
        "contested_fact_ids": [],
        "one_sided_fact_ids": [],
        "unresolved_fact_ids": [],
        "core_fact_ids": core_fact_ids,
        "requires_resolution_fact_ids": [],
    }
    for field, expected_ids in expected.items():
        actual = indexes.get(field)
        if (
            not isinstance(actual, list)
            or actual != expected_ids
            or len(actual) != len(set(actual))
            or any(
                not isinstance(fact_id, str) or not _MATRIX_FACT_ID.fullmatch(fact_id)
                for fact_id in actual
            )
        ):
            _reject_frozen_matrix()


def _formal_object(value: Any, fields: frozenset[str]) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or set(value) != fields:
        _reject_frozen_matrix()
    return value


def _formal_child_object(
    owner: Mapping[str, Any],
    field: str,
    fields: frozenset[str],
) -> Mapping[str, Any]:
    return _formal_object(owner.get(field), fields)


def _formal_text(owner: Mapping[str, Any], field: str, maximum: int) -> str:
    value = owner.get(field)
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        _reject_frozen_matrix()
    return value


def _formal_identifier(owner: Mapping[str, Any], field: str) -> str:
    value = _formal_text(owner, field, 128)
    if not _IDENTIFIER.fullmatch(value):
        _reject_frozen_matrix()
    return value


def _formal_hash(owner: Mapping[str, Any], field: str) -> str:
    value = _formal_text(owner, field, 64)
    if not _SHA256.fullmatch(value):
        _reject_frozen_matrix()
    return value


def _formal_identifier_list(
    owner: Mapping[str, Any],
    field: str,
    *,
    minimum: int,
    maximum: int,
    pattern: re.Pattern[str] = _IDENTIFIER,
) -> list[str]:
    values = owner.get(field)
    if not isinstance(values, list) or not minimum <= len(values) <= maximum:
        _reject_frozen_matrix()
    if any(
        not isinstance(value, str)
        or not value.strip()
        or len(value) > 128
        or not pattern.fullmatch(value)
        for value in values
    ) or len(values) != len(set(values)):
        _reject_frozen_matrix()
    return values


def _reject_frozen_matrix() -> NoReturn:
    raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")


def _visible_unilateral_fact_index(
    dossier: Any,
) -> tuple[dict[str, Mapping[str, Any]], dict[bytes, str]]:
    if not isinstance(dossier, Mapping):
        raise IntakeGraphContractError("INTAKE_DOSSIER_INVALID")
    by_id: dict[str, Mapping[str, Any]] = {}
    by_fingerprint: dict[bytes, str] = {}
    matrix = dossier.get("unilateral_case_matrix")
    if matrix is None:
        return by_id, by_fingerprint
    if not isinstance(matrix, Mapping):
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")
    rows = matrix.get("fact_rows")
    if not isinstance(rows, list) or not 1 <= len(rows) <= 100:
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")
    for row in rows:
        if not isinstance(row, Mapping):
            raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")
        fact_id = row.get("fact_id")
        if not isinstance(fact_id, str) or not _MATRIX_FACT_ID.fullmatch(fact_id):
            raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")
        if fact_id in by_id:
            raise IntakeGraphContractError("INTAKE_DOSSIER_STABLE_ID_CONFLICT")
        fingerprint = _matrix_row_fingerprint(row)
        by_id[fact_id] = row
        by_fingerprint.setdefault(fingerprint, fact_id)
    return by_id, by_fingerprint


def _matrix_row_fingerprint(row: Mapping[str, Any]) -> bytes:
    category = row.get("category")
    fact_target = row.get("fact_target")
    if not isinstance(category, str) or not isinstance(fact_target, str) or not fact_target.strip():
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")
    return canonicalize({"category": category, "fact_target": fact_target})


def _matches_previous_matrix_semantics(
    draft: Mapping[str, Any],
    previous: Mapping[str, Any],
    *,
    actor_role: str,
    patch_kind: str,
) -> bool:
    if draft.get("materiality") != previous.get("materiality"):
        return False
    if patch_kind == "RESPONDENT_DELTA" and draft.get("stance") == "NOT_ADDRESSED":
        return True
    positions = previous.get("positions")
    position = positions.get(actor_role) if isinstance(positions, Mapping) else None
    if not isinstance(position, Mapping):
        position = previous.get("initiator_position")
    if not isinstance(position, Mapping):
        return False
    expected = {
        "position_summary": position.get("position_summary"),
        "asserted_value": position.get("asserted_value"),
    }
    if patch_kind == "RESPONDENT_DELTA":
        expected["stance"] = position.get("stance")
    return all(draft.get(field) == value for field, value in expected.items())


def validate_dossier_transition(previous: Mapping[str, Any], current: Mapping[str, Any]) -> None:
    previous_ids, previous_bindings, previous_sources = _stable_dossier_index(previous)
    current_ids, current_bindings, current_sources = _stable_dossier_index(current)
    if not previous_ids <= current_ids or not previous_sources <= current_sources:
        raise IntakeGraphContractError("INTAKE_DOSSIER_STABLE_ID_DELETED")
    for stable_id, binding in previous_bindings.items():
        if current_bindings.get(stable_id) != binding:
            raise IntakeGraphContractError("INTAKE_DOSSIER_STABLE_ID_REBOUND")


def _validate_binding(
    state: IntakeGraphStateV2,
    payload: Mapping[str, Any],
) -> None:
    bindings = state["bindings"]["private"]
    fields = (
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "thread_id",
        "actor_scope_hash",
        "agent_session_id",
    )
    if any(payload.get(field) != bindings.get(field) for field in fields):
        raise IntakeGraphContractError("INTAKE_PRIVATE_BINDING_MISMATCH")


def _stable_dossier_index(
    value: Any,
) -> tuple[set[str], dict[str, bytes], set[str]]:
    fact_ids: set[str] = set()
    bindings: dict[str, bytes] = {}
    source_refs: set[str] = set()

    def visit(candidate: Any) -> None:
        if isinstance(candidate, Mapping):
            fact_id = candidate.get("fact_id")
            if isinstance(fact_id, str):
                fact_ids.add(fact_id)
                if "category" in candidate and "fact_target" in candidate:
                    register(
                        f"fact:{fact_id}",
                        {
                            "category": candidate["category"],
                            "fact_target": candidate["fact_target"],
                        },
                    )
                if isinstance(candidate.get("content_hash"), str):
                    register(f"fact-hash:{fact_id}", candidate["content_hash"])
            source_id = candidate.get("source_id")
            if isinstance(source_id, str):
                source_refs.add(source_id)
                for hash_field in ("source_hash", "sha256", "content_hash"):
                    if isinstance(candidate.get(hash_field), str):
                        register(f"source:{source_id}", candidate[hash_field])
                        break
            refs = candidate.get("source_refs")
            if isinstance(refs, list | tuple):
                source_refs.update(ref for ref in refs if isinstance(ref, str))
            for child in candidate.values():
                visit(child)
        elif isinstance(candidate, list | tuple):
            for child in candidate:
                visit(child)

    def register(stable_id: str, binding: Any) -> None:
        encoded = canonicalize(binding)
        existing = bindings.get(stable_id)
        if existing is not None and existing != encoded:
            raise IntakeGraphContractError("INTAKE_DOSSIER_STABLE_ID_CONFLICT")
        bindings[stable_id] = encoded

    visit(value)
    return fact_ids, bindings, source_refs


def _validate_bindings(value: Any) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(value, dict) or set(value) != {
        "schema_version",
        "private",
        "command",
    }:
        raise IntakeGraphContractError("INTAKE_BINDINGS_INVALID")
    if value.get("schema_version") != "intake-graph-bindings.v2":
        raise IntakeGraphContractError("INTAKE_BINDINGS_INVALID")
    private = value.get("private")
    command = value.get("command")
    if not isinstance(private, dict) or set(private) != {
        "schema_version",
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "actor_scope_hash",
        "thread_id",
        "agent_session_id",
        "audience",
    }:
        raise IntakeGraphContractError("INTAKE_PRIVATE_BINDING_INVALID")
    if private.get("schema_version") != "intake-private-binding.v1":
        raise IntakeGraphContractError("INTAKE_PRIVATE_BINDING_INVALID")
    for field in ("tenant_surrogate", "case_id", "agent_session_id"):
        _require_identifier(private.get(field), "INTAKE_PRIVATE_BINDING_INVALID")
    if private.get("room_type") != "INTAKE":
        raise IntakeGraphContractError("INTAKE_ROOM_TYPE_INVALID")
    _strict_int(private.get("room_epoch"), minimum=0)
    if not _THREAD_ID.fullmatch(str(private.get("thread_id", ""))):
        raise IntakeGraphContractError("INTAKE_THREAD_ID_INVALID")
    if not _SHA256.fullmatch(str(private.get("actor_scope_hash", ""))):
        raise IntakeGraphContractError("INTAKE_ACTOR_SCOPE_HASH_INVALID")
    if private.get("audience") not in {"USER", "MERCHANT"}:
        raise IntakeGraphContractError("INTAKE_AUDIENCE_INVALID")
    if not isinstance(command, dict) or set(command) != {
        "schema_version",
        "command_id",
        "logical_run_id",
        "attempt_id",
    }:
        raise IntakeGraphContractError("INTAKE_COMMAND_BINDING_INVALID")
    if command.get("schema_version") != "intake-command-binding.v1":
        raise IntakeGraphContractError("INTAKE_COMMAND_BINDING_INVALID")
    for field in ("command_id", "logical_run_id", "attempt_id"):
        _require_identifier(command.get(field), "INTAKE_COMMAND_BINDING_INVALID")
    return private, command


def _validate_version_pins(value: Any) -> None:
    fields = {
        "schema_version",
        "graph_key",
        "graph_version",
        "checkpoint_schema_version",
        "state_schema_version",
        "prompt_version",
        "model_profile_id",
        "output_schema_version",
        "policy_version",
        "guardrail_version",
        "tool_policy_version",
    }
    if not isinstance(value, dict) or set(value) != fields:
        raise IntakeGraphContractError("INTAKE_VERSION_PINS_INVALID")
    if (
        value.get("schema_version") != "graph-version-pins.v1"
        or value.get("graph_key") != "intake.v2"
        or value.get("state_schema_version") != "intake-graph-state.v2"
        or value.get("output_schema_version") != "intake-turn-proposal.v2"
    ):
        raise IntakeGraphContractError("INTAKE_VERSION_PINS_INVALID")
    for field in fields - {"schema_version"}:
        _require_identifier(value.get(field), "INTAKE_VERSION_PINS_INVALID")


def _validate_messages(value: Any, *, audience: str) -> None:
    if not isinstance(value, dict):
        raise IntakeGraphContractError("INTAKE_MESSAGES_INVALID")
    sequences: set[int] = set()
    for key, message in value.items():
        _require_identifier(key, "INTAKE_MESSAGE_INVALID")
        if not isinstance(message, dict) or set(message) != {
            "message_id",
            "role",
            "audience",
            "content",
            "sequence",
            "source_hash",
        }:
            raise IntakeGraphContractError("INTAKE_MESSAGE_INVALID")
        if message.get("message_id") != key or message.get("role") not in {"HUMAN", "AI"}:
            raise IntakeGraphContractError("INTAKE_MESSAGE_INVALID")
        if message.get("audience") != audience:
            raise IntakeGraphContractError("INTAKE_MESSAGE_AUDIENCE_MISMATCH")
        sequence = _strict_int(message.get("sequence"), minimum=0)
        if sequence in sequences:
            raise IntakeGraphContractError("INTAKE_MESSAGE_SEQUENCE_INVALID")
        sequences.add(sequence)
        content = message.get("content")
        if not isinstance(content, str) or len(content.encode("utf-8")) > 8192:
            raise IntakeGraphContractError("INTAKE_MESSAGE_INVALID")
        if not _SHA256.fullmatch(str(message.get("source_hash", ""))):
            raise IntakeGraphContractError("INTAKE_MESSAGE_INVALID")


def _validate_state_payloads(state: IntakeGraphStateV2) -> None:
    summary = state.get("memory_summary")
    if not isinstance(summary, str):
        raise IntakeGraphContractError("INTAKE_MEMORY_SUMMARY_INVALID")
    dossier = state.get("dossier_draft")
    if not isinstance(dossier, dict):
        raise IntakeGraphContractError("INTAKE_DOSSIER_INVALID")
    _validate_safe_json(dossier)
    readiness = state.get("readiness")
    if not isinstance(readiness, dict) or set(readiness) != {"status", "evaluated_revision"}:
        raise IntakeGraphContractError("INTAKE_READINESS_INVALID")
    if readiness.get("status") not in {"INCOMPLETE", "READY_TO_CONFIRM", "NEEDS_REVIEW"}:
        raise IntakeGraphContractError("INTAKE_READINESS_INVALID")
    evaluated_revision = _strict_int(readiness.get("evaluated_revision"), minimum=0)
    if evaluated_revision > state["cognitive_revision"]:
        raise IntakeGraphContractError("INTAKE_READINESS_REVISION_INVALID")
    missing = state.get("missing_fields")
    if not isinstance(missing, list) or len(missing) > 30 or len(missing) != len(set(missing)):
        raise IntakeGraphContractError("INTAKE_MISSING_FIELDS_INVALID")
    for field in missing:
        _require_identifier(field, "INTAKE_MISSING_FIELDS_INVALID")
    if state.get("recommendation") not in {"ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"}:
        raise IntakeGraphContractError("INTAKE_RECOMMENDATION_INVALID")
    _validate_optional_state_refs(state)
    route = state.get("route")
    if route is not None and route not in {"initialize", "message", "replay"}:
        raise IntakeGraphContractError("INTAKE_ROUTE_INVALID")
    for field in ("node_results", "execution_receipts", "usage_by_invocation"):
        value = state.get(field)
        if not isinstance(value, dict):
            raise IntakeGraphContractError("INTAKE_KEYED_STATE_INVALID")
        for key, item in value.items():
            _require_identifier(key, "INTAKE_KEYED_STATE_INVALID")
            _validate_safe_json(item)
    for field in ("terminal_draft", "result_json"):
        value = state.get(field)
        if value is not None:
            if not isinstance(value, dict):
                raise IntakeGraphContractError("INTAKE_TERMINAL_STATE_INVALID")
            _validate_safe_json(value)
    result = state.get("result_json")
    if isinstance(result, dict):
        validate_terminal_proposal(result)


def _validate_optional_state_refs(state: IntakeGraphStateV2) -> None:
    initial = (
        state.get("initial_snapshot_ref"),
        state.get("initial_snapshot_hash"),
        state.get("initial_domain_revision"),
    )
    if any(value is not None for value in initial):
        if any(value is None for value in initial):
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_STATE_INCOMPLETE")
        _require_identifier(initial[0], "INTAKE_SNAPSHOT_STATE_INVALID")
        if not _SHA256.fullmatch(str(initial[1])):
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_STATE_INVALID")
        _strict_int(initial[2], minimum=0)
    event_ref = state.get("last_event_ref")
    event_hash = state.get("last_event_hash")
    if (event_ref is None) != (event_hash is None):
        raise IntakeGraphContractError("INTAKE_EVENT_STATE_INCOMPLETE")
    if event_ref is not None:
        _require_identifier(event_ref, "INTAKE_EVENT_STATE_INVALID")
        if not _SHA256.fullmatch(str(event_hash)):
            raise IntakeGraphContractError("INTAKE_EVENT_STATE_INVALID")
    sequence = state.get("last_event_sequence")
    if sequence is not None:
        _strict_int(sequence, minimum=0)
    if event_ref is not None and sequence is None:
        raise IntakeGraphContractError("INTAKE_EVENT_STATE_INCOMPLETE")


def _next_revision(state: IntakeGraphStateV2) -> int:
    revision = _strict_int(state.get("cognitive_revision"), minimum=0)
    if revision >= _MAX_COGNITIVE_REVISION:
        raise IntakeGraphContractError("INTAKE_COGNITIVE_REVISION_EXHAUSTED")
    return revision + 1


def _strict_int(value: Any, *, minimum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise IntakeGraphContractError("INTAKE_INTEGER_INVALID")
    return value


def _require_identifier(value: Any, code: str) -> str:
    if not isinstance(value, str) or not _IDENTIFIER.fullmatch(value):
        raise IntakeGraphContractError(code)
    return value


def _verify_self_hash(
    value: Mapping[str, Any],
    member: str,
    code: str,
) -> None:
    actual = value.get(member)
    if not isinstance(actual, str) or not _SHA256.fullmatch(actual):
        raise IntakeGraphContractError(code)
    try:
        expected = canonical_sha256_omitting(value, member)
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError(code) from error
    if actual != expected:
        raise IntakeGraphContractError(code)


def _canonical_size(value: Any, code: str) -> int:
    try:
        return len(canonicalize(value))
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError(code) from error


def _reject_forbidden_keys(
    value: Any,
    *,
    forbidden: frozenset[str] = _FORBIDDEN_KEYS,
) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if key in forbidden:
                raise IntakeGraphContractError("INTAKE_FORBIDDEN_FIELD")
            _reject_forbidden_keys(child, forbidden=forbidden)
    elif isinstance(value, list):
        for child in value:
            _reject_forbidden_keys(child, forbidden=forbidden)


def _validate_model(model_type: Any, value: Mapping[str, Any], code: str) -> None:
    try:
        model_type.model_validate(value)
    except ValueError as error:
        raise IntakeGraphContractError(code) from error


def _validate_safe_json(value: Any) -> None:
    if isinstance(value, Mapping):
        if len(value) > 64:
            raise IntakeGraphContractError("INTAKE_OBJECT_TOO_WIDE")
        for child in value.values():
            _validate_safe_json(child)
    elif isinstance(value, list | tuple):
        if len(value) > 128:
            raise IntakeGraphContractError("INTAKE_ARRAY_TOO_LONG")
        for child in value:
            _validate_safe_json(child)
    elif isinstance(value, str) and len(value) > 20_000:
        raise IntakeGraphContractError("INTAKE_STRING_TOO_LONG")
    elif value is not None and not isinstance(value, str | int | float | bool):
        raise IntakeGraphContractError("INTAKE_VALUE_NOT_CANONICAL_JSON")
