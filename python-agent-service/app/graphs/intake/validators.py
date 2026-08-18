from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, NoReturn, cast

from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting, canonicalize
from app.agents.dispute_intake_officer.case_fact_matrix import (
    _explicit_previous_fact_bindings,
    _fact_collision_digest,
    _fact_collision_is_conflicting,
    _new_fact_resolution_plan,
    finalize_case_fact_matrix,
    validate_case_fact_matrix_content_hash,
)
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
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CASE_DETAIL_TOP_LEVEL_FIELDS,
)
from app.graphs.intake.baseline import (
    BASELINE_INTAKE_NODE_NAME,
    build_intake_baseline_request,
    read_intake_baseline_memory_summary,
)
from app.graphs.intake.contracts import (
    CaseFactMatrixDeltaV2,
    DossierPatch,
    HandoffRemarkPartition,
    IntakeCognitionDraft,
    IntakeDomainSnapshot,
    IntakeTurnEvent,
    IntakeTurnProposal,
    RESPONDENT_OPENING_MARKER,
    UnilateralCaseMatrixDraftV1,
)
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import (
    IntakeGraphStateV2,
    IntakeTurnContext,
    merge_intake_dossier,
    merge_intake_bindings,
    merge_intake_messages,
    merge_intake_version_pins,
)
from app.llm import AgentOutputSchemaError
from app.schemas.case_fact_matrix import CaseFactMatrixDeltaV2 as FormalCaseFactMatrixDeltaV2
from app.schemas.case_fact_matrix import CaseFactMatrixV2
from app.schemas.final_agents import IntakeTurnRequest
from app.schemas.intake_case_matrix import (
    UnilateralCaseMatrixDraftV1 as FormalUnilateralCaseMatrixDraftV1,
)


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_THREAD_ID = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MATRIX_FACT_ID = re.compile(r"^FACT_[A-Za-z0-9_:-]{1,123}$")
_FROZEN_MATRIX_ID = re.compile(r"^CASE_MATRIX_[A-F0-9]{20}$")
MATRIX_AUTHORITY_RECORD_KEY = "matrix-authority:v1"
_MATRIX_PROPOSAL_UNILATERAL = "UNILATERAL"
_MATRIX_PROPOSAL_INITIATOR_DELTA = "INITIATOR_DELTA"
_MATRIX_PROPOSAL_RESPONDENT_DELTA = "RESPONDENT_DELTA"
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
_FROZEN_PARENT_REF_FIELDS = frozenset({"matrix_id", "matrix_version", "content_hash"})
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
_FROZEN_INITIATOR_STANCES = frozenset({"CONFIRM", "DENY", "PARTIAL", "UNKNOWN"})
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
        "baseline_previous_case_detail",
        "baseline_pending_case_detail",
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
        "baseline_pending_case_detail",
        "memory_summary",
        "messages",
        "node_results",
        "execution_receipts",
        "usage_by_invocation",
    }
)
_FORBIDDEN_KEYS = frozenset(
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
_BASELINE_CONTEXT_ENVELOPE_SCHEMA = "intake-baseline-context.v1"
_BASELINE_CONTEXT_ENVELOPE_KIND = "BASELINE_SCROLL_SNAPSHOT"
_BASELINE_CONTEXT_UNASSESSED_MATRIX_MODE = "BASELINE_FINALIZER_UNASSESSED_V1"
_BASELINE_CONTEXT_ENVELOPE_FIELDS = frozenset(
    {
        "schema_version",
        "kind",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "source_turn_hash",
        "target_cognitive_revision",
        "terminal_draft_hash",
        "execution_receipt_invocation_id",
        "execution_receipt_node_name",
        "normalized_matrix_patch",
        "matrix_patch_hash",
        "proposal_hash",
        "matrix_authority_mode",
        "authority_input_matrix",
        "authority_input_content_hash",
        "authority_input_matrix_hash",
        "matrix_derivation_request_base",
        "matrix_derivation_request_base_hash",
        "formal_matrix",
        "formal_matrix_hash",
        "authority_anchor_hash",
        "public_dossier_hash",
        "private_binding",
        "initial_snapshot_lineage",
        "source_lineage",
        "committed_proposal_identity",
        "snapshot",
        "snapshot_hash",
        "envelope_hash",
    }
)
_BASELINE_CONTEXT_PRIVATE_BINDING_FIELDS = frozenset(
    {
        "tenant_surrogate",
        "case_id",
        "room_type",
        "thread_id",
        "room_epoch",
        "actor_scope_hash",
        "agent_session_id",
        "audience",
    }
)
_BASELINE_CONTEXT_INITIAL_LINEAGE_FIELDS = frozenset(
    {
        "snapshot_ref",
        "snapshot_hash",
        "domain_revision",
    }
)
_BASELINE_CONTEXT_SOURCE_LINEAGE_FIELDS = frozenset(
    {
        "kind",
        "source_ref",
        "source_turn_hash",
        "sequence",
    }
)
_BASELINE_CONTEXT_PROPOSAL_IDENTITY_FIELDS = frozenset(
    {
        "command_id",
        "logical_run_id",
        "attempt_id",
        "case_id",
        "room_epoch",
        "thread_id",
        "actor_scope_hash",
        "agent_session_id",
        "cognitive_revision",
        "source_snapshot_hash",
        "source_event_hash",
    }
)
_BASELINE_SCROLL_SNAPSHOT_FIELDS = (
    CASE_DETAIL_TOP_LEVEL_FIELDS | frozenset({"handoff_remark_partition"})
) - frozenset({"unilateral_case_matrix"})
_BASELINE_SCROLL_SNAPSHOT_SCHEMA_VERSIONS = frozenset(
    {"intake_case_detail.v1", "intake-dossier.v2"}
)
_BASELINE_SCROLL_SNAPSHOT_MAX_BYTES = 524_288
_MATRIX_DERIVATION_REQUEST_BASE_MAX_BYTES = 65_536
_NORMALIZED_MATRIX_PATCH_MAX_BYTES = 65_536


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
    current_dossier = snapshot.get("current_dossier")
    if not isinstance(current_dossier, Mapping):
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_SCHEMA_INVALID")
    _validate_handoff_remark_partition(
        current_dossier.get("handoff_remark_partition"),
        formal_matrix=current_dossier.get("case_fact_matrix"),
        require_formal_matrix=True,
        error_code="INTAKE_SNAPSHOT_HANDOFF_REMARK_INVALID",
    )
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
    *,
    require_baseline_pending_context: bool = False,
) -> dict[str, Any]:
    if (
        isinstance(patch, Mapping)
        and "memory_summary" in patch
        and patch.get("memory_summary") != state.get("memory_summary")
    ):
        raise IntakeGraphContractError("INTAKE_MEMORY_SUMMARY_IMMUTABLE")
    fields = set(patch) if isinstance(patch, Mapping) else set()
    if not {"cognitive_revision", "terminal_draft"} <= fields or not fields <= (
        _COGNITION_PATCH_FIELDS
    ):
        raise IntakeGraphContractError("INTAKE_COGNITION_PATCH_FIELDS_INVALID")
    expected_revision = next_intake_cognitive_revision(state)
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
    validated = validate_node_patch(state, patch)
    if not require_baseline_pending_context:
        return validated
    pending = validated.get("baseline_pending_case_detail")
    if not isinstance(pending, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_REQUIRED")
    candidate = deepcopy(dict(state))
    candidate.update(
        {
            "cognitive_revision": validated["cognitive_revision"],
            "terminal_draft": deepcopy(validated["terminal_draft"]),
            "baseline_pending_case_detail": deepcopy(dict(pending)),
        }
    )
    if "execution_receipts" in validated:
        candidate["execution_receipts"] = merge_execution_receipts(
            state.get("execution_receipts"),
            validated["execution_receipts"],
        )
    _validate_baseline_context_envelope(
        pending,
        require_bound=False,
        state=candidate,
    )
    _require_pending_envelope_matches_cognitive_draft(candidate, pending)
    _require_pending_authority_input_matches_current_context(state, pending)
    _require_pending_derivation_request_matches_pre_model_state(
        state,
        pending,
        response_content=draft.get("room_utterance"),
        allow_response_absent=True,
    )
    return validated


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
    draft = state.get("terminal_draft")
    if (
        not isinstance(draft, Mapping)
        or proposal.get("conversation_action") != draft.get("conversation_action")
    ):
        raise IntakeGraphContractError("INTAKE_PROPOSAL_ACTION_MISMATCH")
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
    if not _proposal_matrix_is_attested_by_current_pending_context(state, proposal):
        validate_matrix_patch(state, proposal.get("matrix_patch"))
    pending = state.get("baseline_pending_case_detail")
    if _is_baseline_context_envelope(pending):
        _require_pending_formal_matrix_derivation(
            state,
            pending,
            matrix_patch=proposal.get("matrix_patch"),
            response_content=proposal.get("room_utterance"),
        )


def _proposal_matrix_is_attested_by_current_pending_context(
    state: IntakeGraphStateV2,
    proposal: Mapping[str, Any],
) -> bool:
    """Accept only a capsule bound to this exact turn and proposal.

    During cognition/apply the current capsule is held in
    ``baseline_pending_case_detail``.  ``checkpoint_terminal`` then promotes that
    same capsule to ``baseline_previous_case_detail`` before the runtime extracts
    and validates the terminal proposal one last time.  In that promoted state,
    re-reading the capsule's freshly derived formal matrix as historical authority
    would make a valid first-turn unilateral proposal reject itself.

    A promoted capsule is therefore an attestation only when its complete binding,
    committed result, public dossier, normalized patch, and deterministic formal
    derivation all match the exact current proposal.  A prior-turn capsule has a
    different proposal identity and falls through to the ordinary authority
    validator.
    """

    pending = state.get("baseline_pending_case_detail")
    if not _is_baseline_context_envelope(pending):
        previous = state.get("baseline_previous_case_detail")
        if (
            not _is_baseline_context_envelope(previous)
            or previous.get("proposal_hash") != proposal.get("proposal_hash")
        ):
            return False
        # A current promoted capsule may attest its own proposal only when the
        # central Java-commit selector says this exact formal candidate became
        # the next parent.  Non-persisted respondent candidates fall through and
        # are validated against their retained authority input instead.
        committed = _selected_committed_baseline_matrix(state, previous)
        if committed != previous.get("formal_matrix"):
            return False
        _require_promoted_current_capsule_attestation(state, previous, proposal)
        return True

    terminal_draft = state.get("terminal_draft")
    if not isinstance(terminal_draft, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_PROPOSAL_MISSING")
    if pending.get("proposal_hash") is None:
        _validate_baseline_context_envelope(
            pending,
            require_bound=False,
            state=state,
        )
        _require_pending_envelope_matches_cognitive_draft(state, pending)
    else:
        _validate_baseline_context_envelope(
            pending,
            require_bound=True,
            state=state,
        )
        _require_pending_envelope_matches_proposal(state, pending, proposal)
        _require_pending_public_dossier_matches_state(state, pending)
    if proposal.get("matrix_patch") != terminal_draft.get("matrix_patch"):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH")
    _require_pending_normalized_matrix_patch(
        pending,
        proposal.get("matrix_patch"),
    )
    authority_input = pending.get("authority_input_matrix")
    if authority_input is None:
        return False
    if not isinstance(authority_input, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_INVALID")
    current_authority = _authority_input_matrix_from_current_context(state)
    if current_authority is not None and current_authority != authority_input:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_MISMATCH")
    replay_state = deepcopy(dict(state))
    if current_authority is None:
        dossier = replay_state.get("dossier_draft")
        if not isinstance(dossier, Mapping) or "case_fact_matrix" in dossier:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_INVALID")
        replay_state["dossier_draft"] = {
            **deepcopy(dict(dossier)),
            "case_fact_matrix": deepcopy(dict(authority_input)),
        }
    # This is deliberately the original full contract validator, not a hash
    # shortcut.  It cannot recurse into this proposal attestation path.
    validate_matrix_patch(cast(IntakeGraphStateV2, replay_state), proposal.get("matrix_patch"))
    return True


def _require_promoted_current_capsule_attestation(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
    proposal: Mapping[str, Any],
) -> None:
    """Prove that a promoted capsule is the result being extracted right now."""

    _validate_baseline_context_envelope(
        envelope,
        require_bound=True,
        state=state,
    )
    _require_baseline_previous_result_lineage(state, envelope)
    _require_pending_envelope_matches_proposal(state, envelope, proposal)
    _require_pending_public_dossier_matches_state(state, envelope)
    terminal_draft = state.get("terminal_draft")
    committed_result = state.get("result_json")
    if (
        not isinstance(terminal_draft, Mapping)
        or not isinstance(committed_result, Mapping)
        or dict(terminal_draft) != dict(proposal)
        or dict(committed_result) != dict(proposal)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_RESULT_MISMATCH")
    _require_pending_normalized_matrix_patch(envelope, proposal.get("matrix_patch"))
    _require_pending_formal_matrix_derivation(
        state,
        envelope,
        matrix_patch=proposal.get("matrix_patch"),
        response_content=proposal.get("room_utterance"),
    )


def validate_matrix_patch(
    state: IntakeGraphStateV2,
    matrix_patch: Any,
) -> None:
    if matrix_patch is None:
        return
    if not isinstance(matrix_patch, Mapping):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    # Matrix text records facts and may legitimately describe whether material
    # was provided. Public evidence-collection policy belongs to the visible
    # room/dossier boundary; applying it here misclassifies factual provenance.
    schema_version = matrix_patch.get("schema_version")
    if schema_version == "unilateral_case_matrix.draft.v1":
        model_type = UnilateralCaseMatrixDraftV1
        required_mode = _MATRIX_PROPOSAL_UNILATERAL
    elif schema_version == "case_fact_matrix.delta.v2":
        model_type = CaseFactMatrixDeltaV2
        required_mode = _delta_proposal_mode(state)
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

    if required_mode == _MATRIX_PROPOSAL_INITIATOR_DELTA:
        _validate_initiator_delta_patch(matrix_patch, frozen_authority=frozen_authority)

    if required_mode in {
        _MATRIX_PROPOSAL_INITIATOR_DELTA,
        _MATRIX_PROPOSAL_RESPONDENT_DELTA,
    }:
        if frozen_authority is None:
            previous_by_id = {}
            previous_by_fingerprint = {}
        else:
            previous_by_id = dict(frozen_authority.facts_by_id)
            previous_by_fingerprint = dict(frozen_authority.facts_by_fingerprint)
    else:
        previous_by_id, previous_by_fingerprint = _visible_unilateral_fact_index(
            _matrix_proposal_context(state)
        )
    resolved_by_key: dict[str, tuple[str, bytes | str]] = {}
    resolved: set[tuple[str, bytes | str]] = set()
    rows = matrix_patch["fact_rows"]
    try:
        explicit_previous_bindings = _explicit_previous_fact_bindings(
            rows,
            previous_rows=previous_by_id,
            previous_ids_by_fingerprint=previous_by_fingerprint,
        )
        new_previous_bindings, genuinely_new_groups = _new_fact_resolution_plan(
            rows,
            previous_ids_by_fingerprint=previous_by_fingerprint,
            explicitly_bound_previous_ids=set(explicit_previous_bindings.values()),
        )
    except AgentOutputSchemaError as error:
        error_code = (
            "INTAKE_MATRIX_FACT_UNKNOWN"
            if error.safe_code == "INTAKE_MATRIX_FACT_UNKNOWN"
            else "INTAKE_MATRIX_FACT_ID_CONFLICT"
        )
        raise IntakeGraphContractError(error_code) from error
    for fingerprint, items in genuinely_new_groups.items():
        if len(items) > 1 and _fact_collision_is_conflicting(items):
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_ID_CONFLICT")
    for row in rows:
        fact_key = row["fact_key"]
        fingerprint = _matrix_row_fingerprint(row)
        prior: Mapping[str, Any] | None
        if fact_key.startswith("FACT_"):
            corrected_fact_id = explicit_previous_bindings[fact_key]
            prior = previous_by_id[corrected_fact_id]
            if not _matches_matrix_binding(row, prior):
                raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
            if row["materiality"] != prior.get("materiality"):
                raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
            resolution: tuple[str, bytes | str] = ("FACT", corrected_fact_id)
        else:
            if row["source_scope"] == "PREVIOUS_MATRIX":
                raise IntakeGraphContractError("INTAKE_MATRIX_SOURCE_SCOPE_INVALID")
            prior_id = new_previous_bindings.get(fact_key)
            if prior_id is not None:
                prior = previous_by_id[prior_id]
                if not _matches_matrix_binding(row, prior):
                    raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
                if row["materiality"] != prior.get("materiality"):
                    raise IntakeGraphContractError("INTAKE_MATRIX_FACT_REBOUND")
                resolution = ("FACT", prior_id)
            else:
                prior = None
                resolution_identity: str = fingerprint
                collision_items = genuinely_new_groups[fingerprint]
                if previous_by_fingerprint.get(fingerprint) or len(collision_items) > 1:
                    resolution_identity += ":" + _fact_collision_digest(row)
                resolution = ("NEW", resolution_identity)

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

    if (
        required_mode
        in {
            _MATRIX_PROPOSAL_INITIATOR_DELTA,
            _MATRIX_PROPOSAL_RESPONDENT_DELTA,
        }
        and frozen_authority is not None
    ):
        carried_fact_ids = {
            fact_id
            for resolution_kind, fact_id in resolved
            if resolution_kind == "FACT" and isinstance(fact_id, str)
        }
        if carried_fact_ids != set(previous_by_id):
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_MEMBERSHIP_INVALID")

    summary_resolutions: set[tuple[str, bytes | str]] = set()
    for fact_key in matrix_patch["summary_source_fact_keys"]:
        resolution = resolved_by_key.get(fact_key)
        if resolution is None or resolution in summary_resolutions:
            raise IntakeGraphContractError("INTAKE_MATRIX_SUMMARY_SOURCE_INVALID")
        summary_resolutions.add(resolution)


def _trusted_initiator_role(state: IntakeGraphStateV2) -> str:
    """Re-derive the role from imported, baseline-validated initial facts.

    The matrix authority record is immutable in normal graph reductions, but it
    must never be its own source of truth when selecting a party branch.
    """

    try:
        initial_facts, _ = read_intake_baseline_memory_summary(state.get("memory_summary", ""))
    except IntakeGraphContractError as error:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED") from error
    initiator_role = initial_facts.get("initiator_role")
    if initiator_role not in {"USER", "MERCHANT"}:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    return initiator_role


def _delta_proposal_mode(state: IntakeGraphStateV2) -> str:
    """Choose the unified-delta branch before validating its full authority.

    ``_require_matrix_authority`` verifies the record below; this dispatch only
    keeps the baseline's single ``case_fact_matrix.delta.v2`` envelope intact.
    """

    initiator_role = _trusted_initiator_role(state)
    record = state.get("node_results", {}).get(MATRIX_AUTHORITY_RECORD_KEY)
    actor_role = state["bindings"]["private"]["audience"]
    if (
        actor_role == initiator_role
        and isinstance(record, Mapping)
        and record.get("initiator_role") == initiator_role
    ):
        return _MATRIX_PROPOSAL_INITIATOR_DELTA
    return _MATRIX_PROPOSAL_RESPONDENT_DELTA


def _validate_initiator_delta_patch(
    matrix_patch: Mapping[str, Any],
    *,
    frozen_authority: _FrozenInitiatorMatrixAuthority | None,
) -> None:
    """Enforce initiator authority without rewriting the baseline delta shape."""

    if matrix_patch.get("respondent_claim") is not None:
        raise IntakeGraphContractError("INTAKE_MATRIX_INITIATOR_CLAIM_UNAUTHORIZED")
    for row in matrix_patch["fact_rows"]:
        fact_key = row["fact_key"]
        if frozen_authority is None:
            if (
                not fact_key.startswith("NEW_")
                or row["stance"] == "NOT_ADDRESSED"
                or row["source_scope"] != "CURRENT_SOURCE"
            ):
                raise IntakeGraphContractError("INTAKE_MATRIX_INITIATOR_OPENING_INVALID")


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
        respondent_role = _respondent_role(initiator_role)
        frozen_authority = _locked_initiator_matrix_authority(
            snapshot.get("current_dossier"),
            case_id=private["case_id"],
            initiator_role=initiator_role,
            respondent_role=respondent_role,
            allow_bilateral_successor=True,
        )
        if actor_role == initiator_role:
            proposal_mode = _MATRIX_PROPOSAL_INITIATOR_DELTA
            if frozen_authority is not None:
                formal_matrix_hash = frozen_authority.content_hash
        elif actor_role == respondent_role and frozen_authority is not None:
            formal_matrix_hash = frozen_authority.content_hash
            proposal_mode = _MATRIX_PROPOSAL_RESPONDENT_DELTA
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


def _respondent_role(initiator_role: str) -> str:
    if initiator_role == "USER":
        return "MERCHANT"
    if initiator_role == "MERCHANT":
        return "USER"
    raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")


def _matrix_proposal_context(state: IntakeGraphStateV2) -> Any:
    """Return the latest semantic detail available to matrix authorization.

    Formal matrix material is intentionally absent from the public
    ``dossier_draft`` after a model turn.  New checkpoints instead keep the
    deterministic baseline finalizer's full scroll snapshot in a private state
    field; old checkpoints deterministically fall back to their dossier.
    """

    if "baseline_previous_case_detail" in state:
        context = state["baseline_previous_case_detail"]
        if context is None:
            return state.get("dossier_draft")
        if _is_baseline_context_envelope(context):
            return _committed_baseline_context(state, context)
        if not isinstance(context, Mapping):
            raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
        return context
    return state.get("dossier_draft")


def _has_verified_baseline_context(state: IntakeGraphStateV2) -> bool:
    context = state.get("baseline_previous_case_detail")
    if not _is_baseline_context_envelope(context):
        return False
    _validate_baseline_context_envelope(
        context,
        require_bound=True,
        state=state,
    )
    _require_baseline_previous_result_lineage(state, context)
    return True


def _committed_baseline_context(
    state: IntakeGraphStateV2 | Mapping[str, Any],
    envelope: Mapping[str, Any],
) -> dict[str, Any]:
    """Project one bound private capsule through the Java matrix commit contract."""

    typed_state = cast(IntakeGraphStateV2, state)
    selected = _selected_committed_baseline_matrix(typed_state, envelope)
    snapshot = envelope.get("snapshot")
    if not isinstance(snapshot, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")
    context = deepcopy(dict(snapshot))
    context.pop("case_fact_matrix", None)
    context.pop("unilateral_case_matrix", None)
    if selected is not None:
        context["case_fact_matrix"] = selected
    return context


def _selected_committed_baseline_matrix(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
) -> dict[str, Any] | None:
    """Select only the matrix that Java persists for the bound terminal turn."""

    _validate_baseline_context_envelope(
        envelope,
        require_bound=True,
        state=state,
    )
    _require_baseline_previous_result_lineage(state, envelope)
    result = state.get("result_json")
    if not isinstance(result, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_RESULT_MISSING")
    matrix_patch = result.get("matrix_patch")
    _require_pending_normalized_matrix_patch(envelope, matrix_patch)

    private_binding = envelope.get("private_binding")
    if not isinstance(private_binding, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")
    request = _matrix_derivation_request_base(
        envelope,
        expected_private_binding=private_binding,
    )
    actor_role = request.agent_context.actor_role
    initiator_role = _trusted_initiator_role(state)
    respondent_role = _respondent_role(initiator_role)
    if actor_role != private_binding.get("audience") or actor_role not in {
        initiator_role,
        respondent_role,
    }:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")

    authority_input = envelope.get("authority_input_matrix")
    formal_matrix = envelope.get("formal_matrix")
    if authority_input is not None and not isinstance(authority_input, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")
    if not isinstance(formal_matrix, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")

    if matrix_patch is None:
        selected = authority_input
    else:
        opening = _bound_envelope_has_respondent_opening_receipt(
            state,
            envelope,
            turn_source=request.turn_source,
        )
        if actor_role == initiator_role:
            if opening:
                raise IntakeGraphContractError(
                    "INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID"
                )
            selected = formal_matrix
        else:
            if not isinstance(matrix_patch, Mapping) or matrix_patch.get(
                "schema_version"
            ) != "case_fact_matrix.delta.v2":
                raise IntakeGraphContractError(
                    "INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID"
                )
            readiness = result.get("readiness")
            missing_fields = result.get("missing_fields")
            if opening:
                selected = formal_matrix
            elif readiness == "READY_TO_CONFIRM":
                if not isinstance(missing_fields, list) or missing_fields:
                    raise IntakeGraphContractError(
                        "INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID"
                    )
                selected = formal_matrix
            elif readiness in {"INCOMPLETE", "NEEDS_REVIEW"}:
                selected = authority_input
            else:
                raise IntakeGraphContractError(
                    "INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID"
                )

    if selected is None:
        return None
    _validate_baseline_formal_matrix(
        selected,
        expected_case_id=private_binding["case_id"],
    )
    return deepcopy(dict(selected))


def _bound_envelope_has_respondent_opening_receipt(
    state: Mapping[str, Any],
    envelope: Mapping[str, Any],
    *,
    turn_source: str,
) -> bool:
    """Recognize opening only from its immutable event and source receipts."""

    source_lineage = envelope.get("source_lineage")
    records = state.get("node_results")
    if not isinstance(source_lineage, Mapping) or not isinstance(records, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")
    event_ref = source_lineage.get("source_ref")
    event_key = (
        "event:" + hashlib.sha256(event_ref.encode("utf-8")).hexdigest()
        if isinstance(event_ref, str)
        else None
    )
    event = records.get(event_key) if event_key is not None else None
    message_id = event.get("message_id") if isinstance(event, Mapping) else None
    source_key = (
        "message:" + hashlib.sha256(message_id.encode("utf-8")).hexdigest()
        if isinstance(message_id, str)
        else None
    )
    source = records.get(source_key) if source_key is not None else None
    event_is_marked = isinstance(event, Mapping) and (
        event.get("source_type") == RESPONDENT_OPENING_MARKER
        or event.get("control_marker") == RESPONDENT_OPENING_MARKER
    )
    source_is_marked = isinstance(source, Mapping) and (
        source.get("source_type") == RESPONDENT_OPENING_MARKER
        or source.get("control_marker") == RESPONDENT_OPENING_MARKER
    )
    requested_opening = turn_source == RESPONDENT_OPENING_MARKER
    if not requested_opening:
        if event_is_marked or source_is_marked:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")
        return False

    source_refs = event.get("source_refs") if isinstance(event, Mapping) else None
    if (
        source_lineage.get("kind") != "EVENT"
        or source_lineage.get("sequence") != 1
        or not isinstance(event, Mapping)
        or event.get("kind") != "EVENT"
        or event.get("stable_id") != event_ref
        or event.get("content_hash") != source_lineage.get("source_turn_hash")
        or event.get("sequence") != 1
        or event.get("source_type") != RESPONDENT_OPENING_MARKER
        or event.get("control_marker") != RESPONDENT_OPENING_MARKER
        or not isinstance(source_refs, (list, tuple))
        or message_id not in source_refs
        or not isinstance(source, Mapping)
        or source.get("kind") != "RESPONDENT_OPENING_SOURCE"
        or source.get("stable_id") != message_id
        or source.get("content_hash") != source_lineage.get("source_turn_hash")
        or source.get("sequence") != 1
        or source.get("source_type") != RESPONDENT_OPENING_MARKER
        or source.get("control_marker") != RESPONDENT_OPENING_MARKER
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_MATRIX_INVALID")
    return True


def _authority_input_matrix_from_current_context(
    state: IntakeGraphStateV2 | Mapping[str, Any],
) -> dict[str, Any] | None:
    """Return the exact formal matrix used by the current matrix validator.

    This helper intentionally follows ``_matrix_proposal_context`` instead of
    reading the public dossier directly.  A verified prior capsule is the
    authority for M1/Mn, while an imported M0 exists only in the pre-apply
    public dossier on its first turn.
    """

    typed_state = cast(IntakeGraphStateV2, state)
    context = _matrix_proposal_context(typed_state)
    if not isinstance(context, Mapping):
        return None
    matrix = context.get("case_fact_matrix")
    if matrix is None:
        return None
    bindings = typed_state.get("bindings")
    private = bindings.get("private") if isinstance(bindings, Mapping) else None
    if not isinstance(private, Mapping) or not isinstance(matrix, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_INVALID")
    try:
        _validate_baseline_formal_matrix(matrix, expected_case_id=private["case_id"])
    except IntakeGraphContractError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_INVALID") from error
    return deepcopy(dict(matrix))


def _require_pending_authority_input_matches_current_context(
    state: IntakeGraphStateV2 | Mapping[str, Any],
    envelope: Mapping[str, Any],
) -> None:
    """Bind an unbound capsule input to the authority visible before apply."""

    current = _authority_input_matrix_from_current_context(state)
    pending = envelope.get("authority_input_matrix")
    if pending is None:
        if current is not None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_MISMATCH")
        return
    if current is None or pending != current:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_MISMATCH")


def _validated_ingress_matrix_authority_record(
    state: IntakeGraphStateV2 | Mapping[str, Any],
) -> Mapping[str, Any]:
    """Return only the immutable, snapshot-bound authority record.

    The record is intentionally not advanced by later Target reductions.  Its
    formal hash is the anchor for every private capsule descended from the
    imported matrix, rather than an active-matrix hash that would become stale
    on the next turn.
    """

    records = state.get("node_results")
    record = records.get(MATRIX_AUTHORITY_RECORD_KEY) if isinstance(records, Mapping) else None
    bindings = state.get("bindings")
    private = bindings.get("private") if isinstance(bindings, Mapping) else None
    if (
        not isinstance(record, Mapping)
        or set(record) != _MATRIX_AUTHORITY_FIELDS
        or not isinstance(private, Mapping)
    ):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    expected = {
        "schema_version": "intake-matrix-authority.v1",
        "kind": "MATRIX_AUTHORITY",
        "source_snapshot_hash": state.get("initial_snapshot_hash"),
        "case_id": private.get("case_id"),
        "room_epoch": private.get("room_epoch"),
        "thread_id": private.get("thread_id"),
        "actor_scope_hash": private.get("actor_scope_hash"),
        "actor_role": private.get("audience"),
    }
    if any(record.get(field) != value for field, value in expected.items()):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    record_formal_hash = record.get("formal_matrix_hash")
    if record_formal_hash is not None and (
        not isinstance(record_formal_hash, str) or not _SHA256.fullmatch(record_formal_hash)
    ):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    return record


def _ingress_matrix_authority_anchor_hash(
    state: IntakeGraphStateV2 | Mapping[str, Any],
) -> str | None:
    return cast(
        str | None,
        _validated_ingress_matrix_authority_record(state).get("formal_matrix_hash"),
    )


def _baseline_authority_anchor_hash(state: IntakeGraphStateV2) -> str | None:
    """Inherit the verified immutable anchor or start at the ingress record."""

    ingress_anchor = _ingress_matrix_authority_anchor_hash(state)
    previous = state.get("baseline_previous_case_detail")
    if not _is_baseline_context_envelope(previous):
        return ingress_anchor
    _validate_baseline_context_envelope(
        previous,
        require_bound=True,
        state=state,
    )
    _require_baseline_previous_result_lineage(state, previous)
    inherited_anchor = previous.get("authority_anchor_hash")
    if inherited_anchor != ingress_anchor:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_ANCHOR_INVALID")
    return cast(str | None, inherited_anchor)


def _verified_capsule_authority_succeeds_ingress(
    state: IntakeGraphStateV2,
    *,
    active_formal_hash: str | None,
    ingress_formal_hash: str | None,
) -> bool:
    """Allow a newer active matrix only through a fully bound private capsule."""

    previous = state.get("baseline_previous_case_detail")
    if not _is_baseline_context_envelope(previous):
        return False
    _validate_baseline_context_envelope(
        previous,
        require_bound=True,
        state=state,
    )
    _require_baseline_previous_result_lineage(state, previous)
    formal_matrix = _selected_committed_baseline_matrix(state, previous)
    return (
        formal_matrix is not None
        and formal_matrix.get("content_hash") == active_formal_hash
        and previous.get("authority_anchor_hash") == ingress_formal_hash
    )


def _require_matrix_authority(
    state: IntakeGraphStateV2,
    *,
    required_mode: str,
) -> tuple[str, _FrozenInitiatorMatrixAuthority | None]:
    record = _validated_ingress_matrix_authority_record(state)
    private = state["bindings"]["private"]
    actor_role = record.get("actor_role")
    initiator_role = record.get("initiator_role")
    if (
        actor_role not in {"USER", "MERCHANT"}
        or initiator_role not in {"USER", "MERCHANT"}
        or initiator_role != _trusted_initiator_role(state)
    ):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    record_formal_hash = record.get("formal_matrix_hash")
    if record_formal_hash is not None and (
        not isinstance(record_formal_hash, str) or not _SHA256.fullmatch(record_formal_hash)
    ):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    respondent_role = _respondent_role(initiator_role)
    proposal_context = _matrix_proposal_context(state)
    uses_baseline_context = _has_verified_baseline_context(state)
    if required_mode == _MATRIX_PROPOSAL_UNILATERAL:
        if (
            record.get("proposal_mode")
            not in {_MATRIX_PROPOSAL_UNILATERAL, _MATRIX_PROPOSAL_INITIATOR_DELTA}
            or actor_role != initiator_role
            or record.get("formal_matrix_hash") is not None
        ):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
        # Compatibility for historical unilateral output exists only before a
        # formal matrix.  Still inspect the semantic proposal context, so a
        # forged formal matrix cannot be ignored by this legacy branch.
        frozen_authority = _locked_initiator_matrix_authority(
            proposal_context,
            case_id=private["case_id"],
            initiator_role=initiator_role,
            respondent_role=respondent_role,
            allow_unassessed_evidence_coverage=uses_baseline_context,
            allow_bilateral_successor=uses_baseline_context,
            allow_legacy_content_hash=uses_baseline_context,
        )
        if frozen_authority is not None:
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
        frozen_authority = None
    elif required_mode == _MATRIX_PROPOSAL_INITIATOR_DELTA:
        if (
            record.get("proposal_mode")
            not in {_MATRIX_PROPOSAL_INITIATOR_DELTA, _MATRIX_PROPOSAL_UNILATERAL}
            or actor_role != initiator_role
        ):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
        frozen_authority = _locked_initiator_matrix_authority(
            proposal_context,
            case_id=private["case_id"],
            initiator_role=initiator_role,
            respondent_role=respondent_role,
            allow_unassessed_evidence_coverage=uses_baseline_context,
            allow_bilateral_successor=uses_baseline_context,
            allow_legacy_content_hash=uses_baseline_context,
        )
        expected_formal_hash = (
            frozen_authority.content_hash if frozen_authority is not None else None
        )
        # The ingress record is immutable M0 provenance.  A later active
        # authority M1/Mn is accepted only when the verified private capsule
        # binds that active matrix to the same immutable ingress anchor.
        if (
            record_formal_hash != expected_formal_hash
            and not _verified_capsule_authority_succeeds_ingress(
                state,
                active_formal_hash=expected_formal_hash,
                ingress_formal_hash=record_formal_hash,
            )
        ):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    elif required_mode == _MATRIX_PROPOSAL_RESPONDENT_DELTA:
        frozen_authority = _locked_initiator_matrix_authority(
            proposal_context,
            case_id=private["case_id"],
            initiator_role=initiator_role,
            respondent_role=actor_role,
            allow_unassessed_evidence_coverage=uses_baseline_context,
            allow_bilateral_successor=True,
            allow_legacy_content_hash=uses_baseline_context,
        )
        if (
            record.get("proposal_mode") != _MATRIX_PROPOSAL_RESPONDENT_DELTA
            or actor_role != respondent_role
            or frozen_authority is None
            or (
                frozen_authority.content_hash != record_formal_hash
                and not _verified_capsule_authority_succeeds_ingress(
                    state,
                    active_formal_hash=frozen_authority.content_hash,
                    ingress_formal_hash=record_formal_hash,
                )
            )
        ):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    else:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_UNAUTHORIZED")
    return actor_role, frozen_authority


@dataclass(frozen=True, slots=True)
class _FrozenInitiatorMatrixAuthority:
    content_hash: str
    facts_by_id: Mapping[str, Mapping[str, Any]]
    facts_by_fingerprint: Mapping[str, tuple[str, ...]]


def _locked_initiator_matrix_authority(
    dossier: Any,
    *,
    case_id: str,
    initiator_role: str,
    respondent_role: str,
    allow_unassessed_evidence_coverage: bool = False,
    allow_bilateral_successor: bool = False,
    allow_legacy_content_hash: bool = False,
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
        content_hash_is_valid = content_hash == expected_hash or (
            allow_legacy_content_hash
            and validate_case_fact_matrix_content_hash(matrix)
        )
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID") from error
    matrix_version = matrix.get("matrix_version")
    matrix_id = _formal_text(matrix, "matrix_id", 128)
    matrix_kind = _formal_text(matrix, "matrix_kind", 64)
    is_bilateral_successor = matrix_kind == "BILATERAL_FROZEN"
    if (
        _formal_text(matrix, "schema_version", 64) != "case_fact_matrix.v2"
        or _formal_identifier(matrix, "case_id") != case_id
        or matrix_kind not in {"INITIATOR_FROZEN", "BILATERAL_FROZEN"}
        or (is_bilateral_successor and not allow_bilateral_successor)
        or isinstance(matrix_version, bool)
        or not isinstance(matrix_version, int)
        or matrix_version < 1
        or (is_bilateral_successor and matrix_version < 2)
        or not _FROZEN_MATRIX_ID.fullmatch(matrix_id)
        or not content_hash_is_valid
    ):
        _reject_frozen_matrix()
    _validate_frozen_parent_ref(matrix.get("parent_ref"), matrix_version=matrix_version)

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
    expected_generation = (
        (respondent_role, "RESPONDENT_INTAKE")
        if is_bilateral_successor
        else (initiator_role, "INITIATOR_INTAKE")
    )
    if (
        generation.get("actor_role") != expected_generation[0]
        or generation.get("source_stage") != expected_generation[1]
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
        allow_bilateral_successor=is_bilateral_successor,
    )
    relationships = matrix.get("fact_relationships")
    if not isinstance(relationships, list) or relationships:
        _reject_frozen_matrix()

    fact_rows = matrix.get("fact_rows")
    if not isinstance(fact_rows, list) or not 1 <= len(fact_rows) <= 200:
        _reject_frozen_matrix()
    facts_by_id: dict[str, Mapping[str, Any]] = {}
    facts_by_fingerprint: dict[str, tuple[str, ...]] = {}
    core_fact_ids: list[str] = []
    for candidate in fact_rows:
        row, fact_id, is_core = _validate_frozen_fact_row(
            candidate,
            initiator_role=initiator_role,
            respondent_role=respondent_role,
            declared_sources=declared_sources,
            allow_unassessed_evidence_coverage=allow_unassessed_evidence_coverage,
            allow_bilateral_successor=is_bilateral_successor,
        )
        if fact_id in facts_by_id:
            _reject_frozen_matrix()
        facts_by_id[fact_id] = row
        fingerprint = _matrix_row_fingerprint(row)
        facts_by_fingerprint[fingerprint] = (
            *facts_by_fingerprint.get(fingerprint, ()),
            fact_id,
        )
        if is_core:
            core_fact_ids.append(fact_id)

    if not set(summary_fact_ids) <= set(facts_by_id):
        _reject_frozen_matrix()
    _validate_frozen_indexes(
        matrix,
        fact_ids=list(facts_by_id),
        core_fact_ids=core_fact_ids,
        allow_bilateral_successor=is_bilateral_successor,
    )
    return _FrozenInitiatorMatrixAuthority(
        content_hash=content_hash,
        facts_by_id=facts_by_id,
        facts_by_fingerprint=facts_by_fingerprint,
    )


def require_respondent_opening_matrix_authority(
    state: IntakeGraphStateV2 | Mapping[str, Any],
) -> dict[str, Any]:
    """Return the exact fresh M0 context authorized for respondent opening.

    Unlike ordinary matrix-delta authorization, this gate deliberately does not
    accept a later capsule as authority.  The control opening can exist only on
    a fresh bootstrap whose immutable ingress record binds the respondent's
    private scope to the imported initiator-frozen matrix.
    """

    typed_state = cast(IntakeGraphStateV2, state)
    record = _validated_ingress_matrix_authority_record(typed_state)
    bindings = typed_state.get("bindings")
    private = bindings.get("private") if isinstance(bindings, Mapping) else None
    if not isinstance(private, Mapping):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID")
    actor_role = record.get("actor_role")
    initiator_role = record.get("initiator_role")
    respondent_role = (
        _respondent_role(initiator_role)
        if initiator_role in {"USER", "MERCHANT"}
        else None
    )
    context = typed_state.get("dossier_draft")
    if (
        actor_role != respondent_role
        or actor_role != private.get("audience")
        or initiator_role != _trusted_initiator_role(typed_state)
        or record.get("proposal_mode") != _MATRIX_PROPOSAL_RESPONDENT_DELTA
        or not isinstance(context, Mapping)
        or any(
            typed_state.get(field) is not None
            for field in (
                "baseline_previous_case_detail",
                "result_json",
            )
        )
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID")
    frozen = _locked_initiator_matrix_authority(
        context,
        case_id=cast(str, private.get("case_id")),
        initiator_role=cast(str, initiator_role),
        respondent_role=cast(str, actor_role),
    )
    authoritative_context = context
    if frozen is None:
        pending = typed_state.get("baseline_pending_case_detail")
        if not isinstance(pending, Mapping):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID")
        _validate_baseline_context_envelope(
            pending,
            require_bound=None,
            state=typed_state,
        )
        pending_patch = pending.get("normalized_matrix_patch")
        if pending_patch is not None:
            terminal = typed_state.get("terminal_draft")
            if not isinstance(terminal, Mapping):
                terminal = typed_state.get("result_json")
            response_content = (
                terminal.get("room_utterance") if isinstance(terminal, Mapping) else None
            )
            if not isinstance(response_content, str):
                raise IntakeGraphContractError(
                    "INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID"
                )
            try:
                _require_pending_formal_matrix_derivation(
                    typed_state,
                    pending,
                    matrix_patch=pending_patch,
                    response_content=response_content,
                    allow_response_absent=True,
                )
            except IntakeGraphContractError as error:
                raise IntakeGraphContractError(
                    "INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID"
                ) from error
        authority_input = pending.get("authority_input_matrix")
        if not isinstance(authority_input, Mapping):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID")
        authoritative_context = {"case_fact_matrix": deepcopy(dict(authority_input))}
        frozen = _locked_initiator_matrix_authority(
            authoritative_context,
            case_id=cast(str, private.get("case_id")),
            initiator_role=cast(str, initiator_role),
            respondent_role=cast(str, actor_role),
        )
    if frozen is None or record.get("formal_matrix_hash") != frozen.content_hash:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID")
    return deepcopy(dict(authoritative_context))


def validated_respondent_opening_frozen_context(
    state: IntakeGraphStateV2 | Mapping[str, Any],
) -> dict[str, Any]:
    """Verify the reducer-owned opening receipts and return their frozen M0."""

    typed_state = cast(IntakeGraphStateV2, state)
    event_ref = typed_state.get("last_event_ref")
    event_hash = typed_state.get("last_event_hash")
    sequence = typed_state.get("last_event_sequence")
    records = typed_state.get("node_results")
    messages = typed_state.get("messages")
    if (
        typed_state.get("route") != "respondent_opening"
        or not isinstance(event_ref, str)
        or not _IDENTIFIER.fullmatch(event_ref)
        or not isinstance(event_hash, str)
        or not _SHA256.fullmatch(event_hash)
        or sequence != 1
        or not isinstance(records, Mapping)
        or not isinstance(messages, Mapping)
        or bool(messages)
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_RECEIPT_INVALID")
    event_key = "event:" + hashlib.sha256(event_ref.encode("utf-8")).hexdigest()
    event = records.get(event_key)
    message_id = event.get("message_id") if isinstance(event, Mapping) else None
    if not isinstance(message_id, str) or not _IDENTIFIER.fullmatch(message_id):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_RECEIPT_INVALID")
    source_key = "message:" + hashlib.sha256(message_id.encode("utf-8")).hexdigest()
    source = records.get(source_key)
    source_refs = event.get("source_refs") if isinstance(event, Mapping) else None
    if (
        not isinstance(event, Mapping)
        or event.get("kind") != "EVENT"
        or event.get("stable_id") != event_ref
        or event.get("content_hash") != event_hash
        or event.get("sequence") != 1
        or event.get("source_type") != RESPONDENT_OPENING_MARKER
        or event.get("control_marker") != RESPONDENT_OPENING_MARKER
        or not isinstance(source_refs, (list, tuple))
        or message_id not in source_refs
        or not isinstance(source, Mapping)
        or source.get("kind") != "RESPONDENT_OPENING_SOURCE"
        or source.get("stable_id") != message_id
        or source.get("content_hash") != event_hash
        or source.get("sequence") != 1
        or source.get("source_type") != RESPONDENT_OPENING_MARKER
        or source.get("control_marker") != RESPONDENT_OPENING_MARKER
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_RECEIPT_INVALID")
    return require_respondent_opening_matrix_authority(typed_state)


def _validate_frozen_parent_ref(value: Any, *, matrix_version: int) -> None:
    if matrix_version == 1:
        if value is not None:
            _reject_frozen_matrix()
        return
    parent = _formal_object(value, _FROZEN_PARENT_REF_FIELDS)
    parent_version = parent.get("matrix_version")
    if (
        not _FROZEN_MATRIX_ID.fullmatch(_formal_text(parent, "matrix_id", 128))
        or isinstance(parent_version, bool)
        or not isinstance(parent_version, int)
        or parent_version != matrix_version - 1
    ):
        _reject_frozen_matrix()
    _formal_hash(parent, "content_hash")


def _validate_frozen_claims(
    matrix: Mapping[str, Any],
    *,
    initiator_role: str,
    respondent_role: str,
    declared_sources: set[str],
    allow_bilateral_successor: bool = False,
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
    direct = claims.get("respondent_direct")
    conflict = claims.get("claim_conflict")
    if not allow_bilateral_successor:
        if direct is not None or conflict is not None:
            _reject_frozen_matrix()
    elif direct is None:
        if conflict is not None:
            _reject_frozen_matrix()
    else:
        _validate_bilateral_direct_respondent_claim(
            direct,
            respondent_role=respondent_role,
            declared_sources=declared_sources,
        )
        _formal_text(claims, "claim_conflict", 20_000)

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


def _validate_bilateral_direct_respondent_claim(
    candidate: Any,
    *,
    respondent_role: str,
    declared_sources: set[str],
) -> None:
    direct = _formal_object(
        candidate,
        frozenset(
            {
                "respondent_role",
                "attitude",
                "position_summary",
                "alternative_proposal",
                "source_type",
                "source_refs",
            }
        ),
    )
    attitude = _formal_identifier(direct, "attitude")
    if (
        direct.get("respondent_role") != respondent_role
        or attitude not in _FROZEN_CLAIM_ATTITUDES - {"NOT_ADDRESSED"}
        or direct.get("source_type") != "RESPONDENT_DIRECT_INTAKE"
    ):
        _reject_frozen_matrix()
    _formal_text(direct, "position_summary", 20_000)
    if direct.get("alternative_proposal") is not None:
        _formal_text(direct, "alternative_proposal", 20_000)
    sources = _formal_identifier_list(direct, "source_refs", minimum=1, maximum=50)
    if not set(sources) <= declared_sources:
        _reject_frozen_matrix()


def _validate_frozen_fact_row(
    candidate: Any,
    *,
    initiator_role: str,
    respondent_role: str,
    declared_sources: set[str],
    allow_unassessed_evidence_coverage: bool,
    allow_bilateral_successor: bool = False,
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
        or row.get("evidence_coverage_status")
        not in (
            {"PENDING_EVIDENCE_REVIEW", None}
            if allow_unassessed_evidence_coverage
            else {"PENDING_EVIDENCE_REVIEW"}
        )
        or (not allow_bilateral_successor and row.get("requires_resolution") is not None)
    ):
        _reject_frozen_matrix()

    origin = _formal_child_object(row, "origin", _FROZEN_ORIGIN_FIELDS)
    origin_sources = _formal_identifier_list(
        origin,
        "source_refs",
        minimum=1,
        maximum=50,
    )
    allowed_origin_stages = (
        {"INITIATOR_INTAKE", "RESPONDENT_INTAKE"}
        if allow_bilateral_successor
        else {"INITIATOR_INTAKE"}
    )
    if (
        origin.get("introduced_stage") not in allowed_origin_stages
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
    respondent = _formal_child_object(
        positions,
        respondent_role,
        _FROZEN_POSITION_FIELDS,
    )
    if allow_bilateral_successor:
        _validate_bilateral_position(initiator, declared_sources=declared_sources)
        _validate_bilateral_position(respondent, declared_sources=declared_sources)
        alignment = _formal_child_object(
            row,
            "party_alignment",
            _FROZEN_ALIGNMENT_FIELDS,
        )
        _validate_bilateral_alignment(row, alignment)
        return row, fact_id, materiality == "CORE"

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
    if initiator.get("asserted_value") is not None:
        _formal_text(initiator, "asserted_value", 2_000)

    if (
        respondent.get("stance") != "NOT_ADDRESSED"
        or respondent.get("position_summary") != "该方尚未直接陈述。"
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


def _validate_bilateral_position(
    position: Mapping[str, Any],
    *,
    declared_sources: set[str],
) -> None:
    stance = position.get("stance")
    if stance == "NOT_ADDRESSED":
        if (
            position.get("asserted_value") is not None
            or position.get("source_type") != "NO_DIRECT_POSITION"
            or position.get("source_refs") != []
            or not isinstance(position.get("position_summary"), str)
        ):
            _reject_frozen_matrix()
        return
    sources = _formal_identifier_list(position, "source_refs", minimum=1, maximum=50)
    if (
        stance not in _FROZEN_INITIATOR_STANCES
        or position.get("source_type") != "DIRECT_PARTY_STATEMENT"
        or not set(sources) <= declared_sources
    ):
        _reject_frozen_matrix()
    _formal_text(position, "position_summary", 20_000)
    if position.get("asserted_value") is not None:
        _formal_text(position, "asserted_value", 2_000)


def _validate_bilateral_alignment(
    row: Mapping[str, Any],
    alignment: Mapping[str, Any],
) -> None:
    status = alignment.get("status")
    if status not in {
        "NOT_COMPUTED",
        "AGREED",
        "PARTIALLY_AGREED",
        "CONTESTED",
        "ONE_SIDED",
        "UNRESOLVED",
    }:
        _reject_frozen_matrix()
    agreed = alignment.get("agreed_statement")
    conflict = alignment.get("conflict_summary")
    requires_resolution = row.get("requires_resolution")
    if status == "NOT_COMPUTED":
        if agreed is not None or conflict is not None or requires_resolution is not None:
            _reject_frozen_matrix()
        return
    if requires_resolution != (status != "AGREED"):
        _reject_frozen_matrix()
    if status == "AGREED":
        if conflict is not None:
            _reject_frozen_matrix()
        _formal_text(alignment, "agreed_statement", 20_000)
        return
    if status == "PARTIALLY_AGREED":
        _formal_text(alignment, "agreed_statement", 20_000)
        _formal_text(alignment, "conflict_summary", 20_000)
        return
    if agreed is not None:
        _reject_frozen_matrix()
    _formal_text(alignment, "conflict_summary", 20_000)


def _validate_frozen_indexes(
    matrix: Mapping[str, Any],
    *,
    fact_ids: list[str],
    core_fact_ids: list[str],
    allow_bilateral_successor: bool = False,
) -> None:
    indexes = _formal_child_object(matrix, "fact_indexes", _FROZEN_INDEX_FIELDS)
    expected = {
        "not_computed_fact_ids": [],
        "agreed_fact_ids": [],
        "partially_agreed_fact_ids": [],
        "contested_fact_ids": [],
        "one_sided_fact_ids": [],
        "unresolved_fact_ids": [],
        "core_fact_ids": core_fact_ids,
        "requires_resolution_fact_ids": [],
    }
    if allow_bilateral_successor:
        status_index = {
            "NOT_COMPUTED": "not_computed_fact_ids",
            "AGREED": "agreed_fact_ids",
            "PARTIALLY_AGREED": "partially_agreed_fact_ids",
            "CONTESTED": "contested_fact_ids",
            "ONE_SIDED": "one_sided_fact_ids",
            "UNRESOLVED": "unresolved_fact_ids",
        }
        rows = matrix.get("fact_rows")
        if not isinstance(rows, list):
            _reject_frozen_matrix()
        for row in rows:
            if not isinstance(row, Mapping):
                _reject_frozen_matrix()
            alignment = row.get("party_alignment")
            fact_id = row.get("fact_id")
            if not isinstance(alignment, Mapping) or not isinstance(fact_id, str):
                _reject_frozen_matrix()
            index_name = status_index.get(alignment.get("status"))
            if index_name is None:
                _reject_frozen_matrix()
            expected[index_name].append(fact_id)
            if row.get("requires_resolution") is True:
                expected["requires_resolution_fact_ids"].append(fact_id)
    else:
        expected["not_computed_fact_ids"] = fact_ids
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
) -> tuple[dict[str, Mapping[str, Any]], dict[str, tuple[str, ...]]]:
    if not isinstance(dossier, Mapping):
        raise IntakeGraphContractError("INTAKE_DOSSIER_INVALID")
    by_id: dict[str, Mapping[str, Any]] = {}
    by_fingerprint: dict[str, tuple[str, ...]] = {}
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
        by_fingerprint[fingerprint] = (*by_fingerprint.get(fingerprint, ()), fact_id)
    return by_id, by_fingerprint


def _matrix_row_fingerprint(row: Mapping[str, Any]) -> str:
    category = row.get("category")
    fact_target = row.get("fact_target")
    if not isinstance(category, str) or not isinstance(fact_target, str) or not fact_target.strip():
        raise IntakeGraphContractError("INTAKE_MATRIX_CURRENT_INVALID")
    return category + ":" + re.sub(r"\s+", "", fact_target).casefold()


def _unique_fact_id_for_fingerprint(
    index: Mapping[str, tuple[str, ...]],
    fingerprint: str,
) -> str | None:
    matches = index.get(fingerprint, ())
    if len(matches) > 1:
        raise IntakeGraphContractError("INTAKE_MATRIX_FACT_ID_CONFLICT")
    return matches[0] if matches else None


def _matches_matrix_binding(
    draft: Mapping[str, Any],
    previous: Mapping[str, Any],
) -> bool:
    """Require exact persisted text after normalized-key correction.

    The baseline fingerprint is deliberately normalized to repair a model's
    unique local key.  It is not authorization to overwrite the durable category
    or fact wording stored in the formal matrix.
    """

    return draft.get("category") == previous.get("category") and draft.get(
        "fact_target"
    ) == previous.get("fact_target")


def _matches_previous_matrix_semantics(
    draft: Mapping[str, Any],
    previous: Mapping[str, Any],
    *,
    actor_role: str,
    patch_kind: str,
) -> bool:
    if draft.get("materiality") != previous.get("materiality"):
        return False
    if (
        patch_kind
        in {
            _MATRIX_PROPOSAL_INITIATOR_DELTA,
            _MATRIX_PROPOSAL_RESPONDENT_DELTA,
        }
        and draft.get("stance") == "NOT_ADDRESSED"
    ):
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
    if patch_kind in {
        _MATRIX_PROPOSAL_INITIATOR_DELTA,
        _MATRIX_PROPOSAL_RESPONDENT_DELTA,
    }:
        expected["stance"] = position.get("stance")
    return all(draft.get(field) == value for field, value in expected.items())


def validate_dossier_transition(
    previous: Mapping[str, Any],
    current: Mapping[str, Any],
    *,
    actor_role: str | None = None,
    current_message: Mapping[str, Any] | None = None,
    formal_matrix: Mapping[str, Any] | None = None,
) -> None:
    previous_ids, previous_bindings, previous_sources = _stable_dossier_index(previous)
    current_ids, current_bindings, current_sources = _stable_dossier_index(current)
    if not previous_ids <= current_ids or not previous_sources <= current_sources:
        raise IntakeGraphContractError("INTAKE_DOSSIER_STABLE_ID_DELETED")
    for stable_id, binding in previous_bindings.items():
        if current_bindings.get(stable_id) != binding:
            raise IntakeGraphContractError("INTAKE_DOSSIER_STABLE_ID_REBOUND")
    _validate_handoff_remark_transition(
        previous.get("handoff_remark_partition"),
        current.get("handoff_remark_partition"),
        actor_role=actor_role,
        current_message=current_message,
        formal_matrix=formal_matrix,
    )


def handoff_remark_message_hash(
    *,
    party_role: str,
    message_id: str,
    text: str,
) -> str:
    """Return the Java ContractJson-compatible participant-message identity."""

    return canonical_sha256(
        {
            "message_id": message_id,
            "role": party_role,
            "source": "ROOM_MESSAGE",
            "text": text,
        }
    )


def _validate_handoff_remark_partition(
    value: Any,
    *,
    formal_matrix: Any = None,
    require_formal_matrix: bool = False,
    error_code: str = "INTAKE_DOSSIER_HANDOFF_REMARK_INVALID",
) -> Mapping[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, Mapping):
        raise IntakeGraphContractError(error_code)
    _validate_model(HandoffRemarkPartition, value, error_code)
    for role in ("USER", "MERCHANT"):
        party = value["parties"][role]
        for remark in party["remarks"]:
            if remark["source_message_hash"] != handoff_remark_message_hash(
                party_role=role,
                message_id=remark["source_message_id"],
                text=remark["text"],
            ):
                raise IntakeGraphContractError(error_code)
    if require_formal_matrix or formal_matrix is not None:
        if not isinstance(formal_matrix, Mapping) or (
            value.get("case_fact_matrix_id") != formal_matrix.get("matrix_id")
            or value.get("case_fact_matrix_version") != formal_matrix.get("matrix_version")
            or value.get("case_fact_matrix_hash") != formal_matrix.get("content_hash")
        ):
            raise IntakeGraphContractError(error_code)
    return value


def rebind_respondent_opening_handoff_partition(
    public_dossier: Mapping[str, Any],
    *,
    authority_dossier: Mapping[str, Any],
    successor_matrix: Mapping[str, Any],
) -> dict[str, Any]:
    """Atomically carry an existing remark partition across respondent opening.

    ``RESPONDENT_OPENING`` is a server-owned control event.  Its deterministic
    matrix finalizer advances the initiator-frozen matrix to an authority-neutral
    bilateral successor, but the event owns no participant message and therefore
    cannot change either party's remark status, source, or append-only entries.
    Validate the incoming partition against the trusted pre-opening matrix, then
    update only its adjacent-matrix triple and validate that successor binding.
    """

    error_code = "INTAKE_RESPONDENT_OPENING_HANDOFF_REMARK_INVALID"
    if (
        not isinstance(public_dossier, Mapping)
        or not isinstance(authority_dossier, Mapping)
        or not isinstance(successor_matrix, Mapping)
    ):
        raise IntakeGraphContractError(error_code)

    authority_matrix = authority_dossier.get("case_fact_matrix")
    authority_partition = _validate_handoff_remark_partition(
        authority_dossier.get("handoff_remark_partition"),
        formal_matrix=authority_matrix,
        require_formal_matrix=True,
        error_code=error_code,
    )
    carried_partition = public_dossier.get("handoff_remark_partition")
    if authority_partition is None:
        if carried_partition is not None:
            raise IntakeGraphContractError(error_code)
        return deepcopy(dict(public_dossier))
    if not isinstance(carried_partition, Mapping) or dict(carried_partition) != dict(
        authority_partition
    ):
        raise IntakeGraphContractError(error_code)

    rebound_dossier = deepcopy(dict(public_dossier))
    rebound_partition = deepcopy(dict(authority_partition))
    rebound_partition["case_fact_matrix_id"] = successor_matrix.get("matrix_id")
    rebound_partition["case_fact_matrix_version"] = successor_matrix.get(
        "matrix_version"
    )
    rebound_partition["case_fact_matrix_hash"] = successor_matrix.get("content_hash")
    _validate_handoff_remark_partition(
        rebound_partition,
        formal_matrix=successor_matrix,
        require_formal_matrix=True,
        error_code=error_code,
    )
    if rebound_partition.get("parties") != authority_partition.get("parties"):
        raise IntakeGraphContractError(error_code)
    rebound_dossier["handoff_remark_partition"] = rebound_partition
    return rebound_dossier


def rebind_matrix_successor_handoff_partition(
    public_dossier: Mapping[str, Any],
    *,
    authority_dossier: Mapping[str, Any],
    successor_matrix: Mapping[str, Any],
) -> dict[str, Any]:
    """Move an unchanged handoff partition onto one validated matrix successor.

    Matrix authorization and finalization happen before this helper is called.
    This boundary accepts only their exact parent-bound successor and changes
    only the partition's adjacent-matrix triple. Party state remains the prior
    authority so the ordinary transition validator can independently authorize
    the current actor's subsequent phase change.
    """

    error_code = "INTAKE_DOSSIER_HANDOFF_MATRIX_REBIND_INVALID"
    if (
        not isinstance(public_dossier, Mapping)
        or not isinstance(authority_dossier, Mapping)
        or not isinstance(successor_matrix, Mapping)
    ):
        raise IntakeGraphContractError(error_code)
    authority_matrix = authority_dossier.get("case_fact_matrix")
    authority_partition = _validate_handoff_remark_partition(
        authority_dossier.get("handoff_remark_partition"),
        formal_matrix=authority_matrix,
        require_formal_matrix=True,
        error_code=error_code,
    )
    carried_partition = public_dossier.get("handoff_remark_partition")
    authority_case_id = (
        authority_matrix.get("case_id")
        if isinstance(authority_matrix, Mapping)
        else None
    )
    authority_version = (
        authority_matrix.get("matrix_version")
        if isinstance(authority_matrix, Mapping)
        else None
    )
    successor_version = successor_matrix.get("matrix_version")
    if not isinstance(authority_case_id, str):
        raise IntakeGraphContractError(error_code)
    try:
        _validate_baseline_formal_matrix(
            authority_matrix,
            expected_case_id=authority_case_id,
        )
        _validate_baseline_formal_matrix(
            successor_matrix,
            expected_case_id=authority_case_id,
        )
    except IntakeGraphContractError as error:
        raise IntakeGraphContractError(error_code) from error
    if (
        authority_partition is None
        or not isinstance(authority_matrix, Mapping)
        or not isinstance(carried_partition, Mapping)
        or dict(carried_partition) != dict(authority_partition)
        or type(authority_version) is not int
        or type(successor_version) is not int
        or successor_version != authority_version + 1
        or successor_matrix.get("party_map") != authority_matrix.get("party_map")
        or successor_matrix.get("parent_ref")
        != {
            "matrix_id": authority_matrix.get("matrix_id"),
            "matrix_version": authority_matrix.get("matrix_version"),
            "content_hash": authority_matrix.get("content_hash"),
        }
    ):
        raise IntakeGraphContractError(error_code)

    rebound_dossier = deepcopy(dict(public_dossier))
    rebound_partition = deepcopy(dict(authority_partition))
    rebound_partition["case_fact_matrix_id"] = successor_matrix.get("matrix_id")
    rebound_partition["case_fact_matrix_version"] = successor_matrix.get(
        "matrix_version"
    )
    rebound_partition["case_fact_matrix_hash"] = successor_matrix.get("content_hash")
    _validate_handoff_remark_partition(
        rebound_partition,
        formal_matrix=successor_matrix,
        require_formal_matrix=True,
        error_code=error_code,
    )
    if rebound_partition.get("parties") != authority_partition.get("parties"):
        raise IntakeGraphContractError(error_code)
    rebound_dossier["handoff_remark_partition"] = rebound_partition
    return rebound_dossier


def _validate_handoff_remark_transition(
    previous: Any,
    current: Any,
    *,
    actor_role: str | None,
    current_message: Mapping[str, Any] | None,
    formal_matrix: Mapping[str, Any] | None,
) -> None:
    previous_partition = _validate_handoff_remark_partition(previous)
    current_partition = _validate_handoff_remark_partition(
        current,
        formal_matrix=formal_matrix,
        require_formal_matrix=actor_role is not None,
    )
    if previous_partition is None and current_partition is None:
        return
    if current_partition is None:
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_REMARK_DELETED")
    if previous_partition is None and actor_role is None:
        # LCEL also validates an isolated patch to detect self-conflicting stable
        # identities.  Without the prior dossier or actor/message authority it
        # may validate the strict partition shape and self-hashes only; the real
        # previous-to-current transition is checked again at the apply boundary
        # with all trusted inputs present.
        return
    if previous_partition is not None and any(
        current_partition.get(field) != previous_partition.get(field)
        for field in (
            "schema_version",
            "case_fact_matrix_id",
            "case_fact_matrix_version",
            "case_fact_matrix_hash",
        )
    ):
        if not _is_actor_bound_matrix_successor_rebind(
            previous_partition,
            current_partition,
            actor_role=actor_role,
            formal_matrix=formal_matrix,
        ):
            raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_MATRIX_REBOUND")

    current_parties = current_partition["parties"]
    previous_parties = previous_partition["parties"] if previous_partition is not None else None
    changed_roles = {
        role
        for role in ("USER", "MERCHANT")
        if previous_parties is None or current_parties[role] != previous_parties[role]
    }
    if previous_parties is None:
        # Introducing the partition materializes both canonical party slots.
        # A foreign slot has no authority to start beyond NOT_READY.
        active_roles = {
            role
            for role in ("USER", "MERCHANT")
            if current_parties[role]["remark_status"] != "NOT_READY"
        }
        if len(active_roles) > 1:
            raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_PARTY_UNAUTHORIZED")
        changed_roles = active_roles

    if actor_role is not None:
        if actor_role not in {"USER", "MERCHANT"} or not changed_roles <= {actor_role}:
            raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_PARTY_UNAUTHORIZED")
    elif len(changed_roles) > 1:
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_PARTY_UNAUTHORIZED")
    if not changed_roles:
        return

    changed_role = next(iter(changed_roles))
    before = previous_parties[changed_role] if previous_parties is not None else {
        "party_role": changed_role,
        "remark_status": "NOT_READY",
        "latest_remark": "",
        "remarks": [],
    }
    after = current_parties[changed_role]
    before_status = before["remark_status"]
    after_status = after["remark_status"]
    allowed = {
        ("NOT_READY", "READY_PENDING_REMARK_INVITE"),
        ("NOT_READY", "WAITING_FOR_REMARK"),
        ("NOT_READY", "NO_EXTRA_REMARKS"),
        ("READY_PENDING_REMARK_INVITE", "HAS_REMARKS"),
        ("READY_PENDING_REMARK_INVITE", "NO_EXTRA_REMARKS"),
        ("WAITING_FOR_REMARK", "HAS_REMARKS"),
        ("WAITING_FOR_REMARK", "NO_EXTRA_REMARKS"),
        ("HAS_REMARKS", "HAS_REMARKS"),
        ("NO_EXTRA_REMARKS", "HAS_REMARKS"),
    }
    if (before_status, after_status) not in allowed:
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_STATUS_TRANSITION_INVALID")

    before_remarks = before["remarks"]
    after_remarks = after["remarks"]
    if after_status == "HAS_REMARKS":
        append_count = len(after_remarks) - len(before_remarks)
        if append_count not in {0, 1} or after_remarks[: len(before_remarks)] != before_remarks:
            raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_REMARK_APPEND_INVALID")
    elif after_remarks != before_remarks:
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_REMARK_APPEND_INVALID")

    source = after.get("source")
    if not isinstance(source, Mapping) or source.get("source_kind") != "ROOM_MESSAGE":
        # Java may introduce FORMAL_CONFIRMATION authority at its own trusted
        # commit boundary.  Python accepts that authority only as exact carry,
        # which returned above before any changed-party transition is evaluated.
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_SOURCE_UNAUTHORIZED")
    if actor_role is not None or current_message is not None:
        _validate_current_handoff_message(
            role=changed_role,
            source=source,
            current_message=current_message,
        )
    if after_status == "HAS_REMARKS" and len(after_remarks) == len(before_remarks) + 1:
        latest = after_remarks[-1]
        if (
            latest.get("source_message_id") != source.get("message_id")
            or latest.get("source_message_hash") != source.get("message_hash")
        ):
            raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_REMARK_SOURCE_MISMATCH")


def _is_actor_bound_matrix_successor_rebind(
    previous_partition: Mapping[str, Any],
    current_partition: Mapping[str, Any],
    *,
    actor_role: str | None,
    formal_matrix: Mapping[str, Any] | None,
) -> bool:
    """Recognize only apply-time rebind to the validated immediate successor."""

    if actor_role not in {"USER", "MERCHANT"} or not isinstance(formal_matrix, Mapping):
        return False
    previous_version = previous_partition.get("case_fact_matrix_version")
    current_version = current_partition.get("case_fact_matrix_version")
    return bool(
        previous_partition.get("schema_version")
        == current_partition.get("schema_version")
        and type(previous_version) is int
        and type(current_version) is int
        and current_version == previous_version + 1
        and formal_matrix.get("parent_ref")
        == {
            "matrix_id": previous_partition.get("case_fact_matrix_id"),
            "matrix_version": previous_version,
            "content_hash": previous_partition.get("case_fact_matrix_hash"),
        }
        and current_partition.get("case_fact_matrix_id")
        == formal_matrix.get("matrix_id")
        and current_version == formal_matrix.get("matrix_version")
        and current_partition.get("case_fact_matrix_hash")
        == formal_matrix.get("content_hash")
    )


def _validate_current_handoff_message(
    *,
    role: str,
    source: Mapping[str, Any],
    current_message: Mapping[str, Any] | None,
) -> None:
    if not isinstance(current_message, Mapping):
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_CURRENT_MESSAGE_MISSING")
    message_id = current_message.get("message_id")
    text = current_message.get("content")
    if (
        current_message.get("role") != "HUMAN"
        or current_message.get("audience") != role
        or not isinstance(message_id, str)
        or not isinstance(text, str)
        or source.get("message_id") != message_id
        or source.get("message_hash")
        != handoff_remark_message_hash(
            party_role=role,
            message_id=message_id,
            text=text,
        )
    ):
        raise IntakeGraphContractError("INTAKE_DOSSIER_HANDOFF_CURRENT_MESSAGE_MISMATCH")


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
    sequence_roles: set[tuple[int, str]] = set()
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
        role = str(message["role"])
        sequence_role = (sequence, role)
        if sequence_role in sequence_roles:
            raise IntakeGraphContractError("INTAKE_MESSAGE_SEQUENCE_INVALID")
        # A generated AI response shares the immutable source-event sequence of
        # the HUMAN message it answers.  The role disambiguates that bounded
        # pair, while a second HUMAN or AI at the same sequence remains invalid.
        sequence_roles.add(sequence_role)
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
    _validate_handoff_remark_partition(dossier.get("handoff_remark_partition"))
    _validate_optional_state_refs(state)
    _validate_baseline_case_detail_contexts(state)
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
    route = state.get("route")
    if route is not None and route not in {
        "initialize",
        "message",
        "respondent_opening",
        "replay",
    }:
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


def build_baseline_pending_case_detail(
    state: IntakeGraphStateV2,
    *,
    terminal_draft: Mapping[str, Any],
    formal_matrix: Mapping[str, Any],
    public_dossier: Mapping[str, Any],
    matrix_derivation_request_base: Mapping[str, Any],
    execution_receipt_invocation_id: str,
    execution_receipt_node_name: str,
    execution_receipt_output_hash: str,
) -> dict[str, Any]:
    """Create an unbound, formal-matrix-only private context envelope.

    The model-facing public dossier is still mutable until ``apply_dossier_patch``
    has merged the normalized draft.  Persisting a whole scroll snapshot here
    would therefore retain the pre-normalizer dossier.  Keep only the already
    finalized formal matrix plus the normalized cognitive-draft identity; the
    complete snapshot is materialized and bound after that public merge.
    """

    command = state["bindings"]["command"]
    matrix_patch = terminal_draft.get("matrix_patch")
    terminal_draft_hash = canonical_sha256(terminal_draft)
    if execution_receipt_output_hash != terminal_draft_hash:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_RECEIPT_OUTPUT_MISMATCH")
    authority_input_matrix = _authority_input_matrix_from_current_context(state)
    authority_input_content_hash = (
        authority_input_matrix.get("content_hash") if authority_input_matrix is not None else None
    )
    envelope: dict[str, Any] = {
        "schema_version": _BASELINE_CONTEXT_ENVELOPE_SCHEMA,
        "kind": _BASELINE_CONTEXT_ENVELOPE_KIND,
        "command_id": command["command_id"],
        "logical_run_id": command["logical_run_id"],
        "attempt_id": command["attempt_id"],
        "source_turn_hash": _current_source_turn_hash(state),
        "target_cognitive_revision": next_intake_cognitive_revision(state),
        "terminal_draft_hash": terminal_draft_hash,
        "execution_receipt_invocation_id": execution_receipt_invocation_id,
        "execution_receipt_node_name": execution_receipt_node_name,
        "normalized_matrix_patch": deepcopy(matrix_patch),
        "matrix_patch_hash": canonical_sha256(matrix_patch),
        "proposal_hash": None,
        "matrix_authority_mode": _BASELINE_CONTEXT_UNASSESSED_MATRIX_MODE,
        # The public dossier intentionally strips formal authority during apply.
        # Retain the exact M0/Mn input that just passed full matrix validation so
        # project/checkpoint can replay that same validation without trusting a
        # mutable public copy or a self-hash-only attestation.
        "authority_input_matrix": authority_input_matrix,
        "authority_input_content_hash": authority_input_content_hash,
        "authority_input_matrix_hash": (
            canonical_sha256(authority_input_matrix) if authority_input_matrix is not None else None
        ),
        "matrix_derivation_request_base": deepcopy(dict(matrix_derivation_request_base)),
        "matrix_derivation_request_base_hash": canonical_sha256(matrix_derivation_request_base),
        "formal_matrix": deepcopy(dict(formal_matrix)),
        "formal_matrix_hash": canonical_sha256(formal_matrix),
        # Preserve the immutable ingress authority anchor across all later
        # finalized matrices.  The active formal matrix may advance each turn,
        # but it is accepted only when its fully verified capsule still names
        # the same imported authority (or the explicit no-import null anchor).
        "authority_anchor_hash": _baseline_authority_anchor_hash(state),
        "public_dossier_hash": canonical_sha256(public_dossier),
        "private_binding": _baseline_private_binding(state),
        "initial_snapshot_lineage": _baseline_initial_snapshot_lineage(state),
        "source_lineage": _baseline_source_lineage(state),
        "committed_proposal_identity": None,
        "snapshot": None,
        "snapshot_hash": None,
        # The canonical helper requires the omitted self-hash member to exist.
        "envelope_hash": "0" * 64,
    }
    envelope["envelope_hash"] = canonical_sha256_omitting(envelope, "envelope_hash")
    _validate_baseline_context_envelope(
        envelope,
        require_bound=False,
        state=state,
        require_execution_receipt=False,
    )
    _require_pending_authority_input_matches_current_context(state, envelope)
    return envelope


def bind_baseline_pending_case_detail(
    state: IntakeGraphStateV2,
    *,
    proposal: Mapping[str, Any],
) -> dict[str, Any]:
    """Materialize and bind a pending capsule to its terminal proposal."""

    pending = state.get("baseline_pending_case_detail")
    if not isinstance(pending, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MISSING")
    _validate_baseline_context_envelope(
        pending,
        require_bound=False,
        state=state,
    )
    _require_pending_envelope_matches_cognitive_draft(state, pending)
    _require_pending_public_dossier_matches_state(state, pending)
    validate_terminal_proposal(proposal)
    _require_pending_derivation_request_matches_pre_model_state(
        state,
        pending,
        response_content=proposal.get("room_utterance"),
    )
    validate_proposal_binding(state, proposal)
    bound = deepcopy(dict(pending))
    snapshot = _materialize_baseline_scroll_snapshot(state, bound)
    bound["snapshot"] = snapshot
    bound["snapshot_hash"] = canonical_sha256(snapshot)
    bound["proposal_hash"] = proposal["proposal_hash"]
    bound["committed_proposal_identity"] = _baseline_proposal_identity(proposal)
    bound["envelope_hash"] = canonical_sha256_omitting(bound, "envelope_hash")
    _validate_baseline_context_envelope(
        bound,
        require_bound=True,
        state=state,
    )
    _require_pending_envelope_matches_proposal(state, bound, proposal)
    return bound


def validate_baseline_pending_promotion(
    state: IntakeGraphStateV2,
    *,
    proposal: Mapping[str, Any],
) -> dict[str, Any]:
    """Return only a fully bound envelope eligible for terminal promotion."""

    pending = state.get("baseline_pending_case_detail")
    if not isinstance(pending, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MISSING")
    _validate_baseline_context_envelope(
        pending,
        require_bound=True,
        state=state,
    )
    _require_pending_envelope_matches_proposal(state, pending, proposal)
    _require_pending_public_dossier_matches_state(state, pending)
    _require_pending_formal_matrix_derivation(
        state,
        pending,
        matrix_patch=proposal.get("matrix_patch"),
        response_content=proposal.get("room_utterance"),
    )
    return deepcopy(dict(pending))


def _validate_baseline_case_detail_contexts(state: IntakeGraphStateV2) -> None:
    """Validate private baseline context envelopes and legacy snapshot fallback."""

    if "baseline_previous_case_detail" in state:
        previous = state["baseline_previous_case_detail"]
        if previous is None:
            pass
        elif _is_baseline_context_envelope(previous):
            _validate_baseline_context_envelope(
                previous,
                require_bound=True,
                state=state,
            )
            _require_baseline_previous_result_lineage(state, previous)
        else:
            # Pre-envelope checkpoints remain readable, but cannot unlock the
            # envelope-only evidence-coverage compatibility path.
            _validate_baseline_scroll_snapshot(previous)
    if "baseline_pending_case_detail" in state:
        pending = state["baseline_pending_case_detail"]
        if pending is None:
            return
        if not _is_baseline_context_envelope(pending):
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
        _validate_baseline_context_envelope(
            pending,
            require_bound=None,
            state=state,
        )
        if pending.get("proposal_hash") is None:
            _require_pending_envelope_matches_cognitive_draft(state, pending)
        else:
            proposal = state.get("terminal_draft")
            if not isinstance(proposal, Mapping):
                raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_PROPOSAL_MISSING")
            _require_pending_envelope_matches_proposal(state, pending, proposal)
            _require_pending_public_dossier_matches_state(state, pending)


def _is_baseline_context_envelope(value: Any) -> bool:
    return (
        isinstance(value, Mapping)
        and value.get("schema_version") == _BASELINE_CONTEXT_ENVELOPE_SCHEMA
    )


def _validate_baseline_context_envelope(
    envelope: Mapping[str, Any],
    *,
    require_bound: bool | None,
    state: Mapping[str, Any],
    require_execution_receipt: bool = True,
) -> None:
    if not isinstance(envelope, Mapping) or set(envelope) != _BASELINE_CONTEXT_ENVELOPE_FIELDS:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    if (
        envelope.get("schema_version") != _BASELINE_CONTEXT_ENVELOPE_SCHEMA
        or envelope.get("kind") != _BASELINE_CONTEXT_ENVELOPE_KIND
        or envelope.get("matrix_authority_mode") != _BASELINE_CONTEXT_UNASSESSED_MATRIX_MODE
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    for field in ("command_id", "logical_run_id", "attempt_id"):
        _require_identifier(envelope.get(field), "INTAKE_BASELINE_CONTEXT_INVALID")
    for field in (
        "source_turn_hash",
        "terminal_draft_hash",
        "matrix_patch_hash",
        "matrix_derivation_request_base_hash",
        "formal_matrix_hash",
        "public_dossier_hash",
        "envelope_hash",
    ):
        value = envelope.get(field)
        if not isinstance(value, str) or not _SHA256.fullmatch(value):
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    for field in (
        "execution_receipt_invocation_id",
        "execution_receipt_node_name",
    ):
        _require_identifier(envelope.get(field), "INTAKE_BASELINE_CONTEXT_RECEIPT_INVALID")
    normalized_matrix_patch = envelope.get("normalized_matrix_patch")
    if _canonical_size(
        normalized_matrix_patch,
        "INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH",
    ) > _NORMALIZED_MATRIX_PATCH_MAX_BYTES or canonical_sha256(
        normalized_matrix_patch
    ) != envelope.get("matrix_patch_hash"):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH")
    if normalized_matrix_patch is not None:
        _formal_delta_from_matrix_patch(normalized_matrix_patch)
    proposal_hash = envelope.get("proposal_hash")
    if proposal_hash is not None and (
        not isinstance(proposal_hash, str) or not _SHA256.fullmatch(proposal_hash)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    target_revision = envelope.get("target_cognitive_revision")
    if (
        isinstance(target_revision, bool)
        or not isinstance(target_revision, int)
        or target_revision < 1
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    _validate_baseline_private_binding(envelope.get("private_binding"))
    _validate_baseline_initial_snapshot_lineage(envelope.get("initial_snapshot_lineage"))
    _validate_baseline_source_lineage(envelope.get("source_lineage"))
    private_binding = envelope["private_binding"]
    formal_matrix = envelope.get("formal_matrix")
    _validate_baseline_formal_matrix(
        formal_matrix,
        expected_case_id=private_binding["case_id"],
    )
    if envelope["formal_matrix_hash"] != canonical_sha256(formal_matrix):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_HASH_INVALID")
    _validate_baseline_authority_input(
        envelope,
        expected_case_id=private_binding["case_id"],
    )
    derivation_request = _matrix_derivation_request_base(
        envelope,
        expected_private_binding=private_binding,
    )
    _validate_baseline_execution_receipt_binding(
        envelope,
        state=state,
        require_present=require_execution_receipt,
        expected_invocation_id=derivation_request.agent_context.agent_invocation_id,
    )
    authority_anchor_hash = envelope.get("authority_anchor_hash")
    if authority_anchor_hash is not None and (
        not isinstance(authority_anchor_hash, str) or not _SHA256.fullmatch(authority_anchor_hash)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_ANCHOR_INVALID")
    if authority_anchor_hash != _ingress_matrix_authority_anchor_hash(state):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_ANCHOR_INVALID")
    proposal_identity = envelope.get("committed_proposal_identity")
    is_bound = proposal_hash is not None
    if require_bound is True and not is_bound:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_UNBOUND")
    if require_bound is False and is_bound:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_ALREADY_BOUND")
    if is_bound:
        _validate_baseline_proposal_identity(proposal_identity)
        if proposal_identity is None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_UNBOUND")
    else:
        if proposal_identity is not None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    snapshot = envelope.get("snapshot")
    snapshot_hash = envelope.get("snapshot_hash")
    if not is_bound:
        if snapshot is not None or snapshot_hash is not None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_UNBOUND_SNAPSHOT")
    else:
        if not isinstance(snapshot_hash, str) or not _SHA256.fullmatch(snapshot_hash):
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
        _validate_baseline_scroll_snapshot(
            snapshot,
            expected_case_id=private_binding["case_id"],
        )
        if snapshot_hash != canonical_sha256(snapshot):
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_HASH_INVALID")
        if canonical_sha256(snapshot["case_fact_matrix"]) != envelope["formal_matrix_hash"]:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_MISMATCH")
        if (
            canonical_sha256(_baseline_snapshot_public_dossier(snapshot))
            != envelope["public_dossier_hash"]
        ):
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_MISMATCH")
    _verify_self_hash(envelope, "envelope_hash", "INTAKE_BASELINE_CONTEXT_HASH_INVALID")
    _require_baseline_envelope_state_lineage(state, envelope)


def unwrap_verified_baseline_previous_case_detail(
    state: Mapping[str, Any],
) -> dict[str, Any]:
    """Return an envelope snapshot only after current state bindings match it.

    This is deliberately separate from the generic state validator because the
    baseline prompt adapter is another trust boundary: a persisted envelope
    must not be unwrapped merely because its internal self-hash is valid.
    """

    previous = state.get("baseline_previous_case_detail")
    if not _is_baseline_context_envelope(previous):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    return _committed_baseline_context(state, previous)


def _baseline_private_binding(state: Mapping[str, Any]) -> dict[str, Any]:
    bindings = state.get("bindings")
    private = bindings.get("private") if isinstance(bindings, Mapping) else None
    if not isinstance(private, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    value = {
        field: deepcopy(private.get(field)) for field in _BASELINE_CONTEXT_PRIVATE_BINDING_FIELDS
    }
    _validate_baseline_private_binding(value)
    return value


def _validate_baseline_private_binding(value: Any) -> None:
    if not isinstance(value, Mapping) or set(value) != _BASELINE_CONTEXT_PRIVATE_BINDING_FIELDS:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    for field in ("tenant_surrogate", "case_id", "agent_session_id"):
        _require_identifier(value.get(field), "INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if value.get("room_type") != "INTAKE":
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if not _THREAD_ID.fullmatch(str(value.get("thread_id", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _strict_int(value.get("room_epoch"), minimum=0)
    if not _SHA256.fullmatch(str(value.get("actor_scope_hash", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if value.get("audience") not in {"USER", "MERCHANT"}:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")


def _baseline_initial_snapshot_lineage(state: Mapping[str, Any]) -> dict[str, Any]:
    value = {
        "snapshot_ref": state.get("initial_snapshot_ref"),
        "snapshot_hash": state.get("initial_snapshot_hash"),
        "domain_revision": state.get("initial_domain_revision"),
    }
    _validate_baseline_initial_snapshot_lineage(value)
    return value


def _validate_baseline_initial_snapshot_lineage(value: Any) -> None:
    if not isinstance(value, Mapping) or set(value) != _BASELINE_CONTEXT_INITIAL_LINEAGE_FIELDS:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _require_identifier(value.get("snapshot_ref"), "INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if not _SHA256.fullmatch(str(value.get("snapshot_hash", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _strict_int(value.get("domain_revision"), minimum=0)


def _baseline_source_lineage(state: Mapping[str, Any]) -> dict[str, Any]:
    event_ref = state.get("last_event_ref")
    event_hash = state.get("last_event_hash")
    sequence = state.get("last_event_sequence")
    if event_ref is None:
        if event_hash is not None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
        initial = _baseline_initial_snapshot_lineage(state)
        return {
            "kind": "INITIAL_SNAPSHOT",
            "source_ref": initial["snapshot_ref"],
            "source_turn_hash": initial["snapshot_hash"],
            "sequence": 0,
        }
    value = {
        "kind": "EVENT",
        "source_ref": event_ref,
        "source_turn_hash": event_hash,
        "sequence": sequence,
    }
    _validate_baseline_source_lineage(value)
    return value


def _validate_baseline_source_lineage(value: Any) -> None:
    if not isinstance(value, Mapping) or set(value) != _BASELINE_CONTEXT_SOURCE_LINEAGE_FIELDS:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    kind = value.get("kind")
    if kind not in {"INITIAL_SNAPSHOT", "EVENT"}:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    _require_identifier(value.get("source_ref"), "INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    if not _SHA256.fullmatch(str(value.get("source_turn_hash", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    sequence = _strict_int(value.get("sequence"), minimum=0)
    if kind == "INITIAL_SNAPSHOT" and sequence != 0:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    if kind == "EVENT" and sequence < 1:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")


def _baseline_proposal_identity(proposal: Mapping[str, Any]) -> dict[str, Any]:
    value = {
        field: deepcopy(proposal.get(field)) for field in _BASELINE_CONTEXT_PROPOSAL_IDENTITY_FIELDS
    }
    _validate_baseline_proposal_identity(value)
    return value


def _validate_baseline_proposal_identity(value: Any) -> None:
    if not isinstance(value, Mapping) or set(value) != _BASELINE_CONTEXT_PROPOSAL_IDENTITY_FIELDS:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    for field in ("command_id", "logical_run_id", "attempt_id", "case_id", "agent_session_id"):
        _require_identifier(value.get(field), "INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if not _THREAD_ID.fullmatch(str(value.get("thread_id", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _strict_int(value.get("room_epoch"), minimum=0)
    _strict_int(value.get("cognitive_revision"), minimum=1)
    if not _SHA256.fullmatch(str(value.get("actor_scope_hash", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if not _SHA256.fullmatch(str(value.get("source_snapshot_hash", ""))):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    source_event_hash = value.get("source_event_hash")
    if source_event_hash is not None and (
        not isinstance(source_event_hash, str) or not _SHA256.fullmatch(source_event_hash)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")


def _require_baseline_envelope_state_lineage(
    state: Mapping[str, Any],
    envelope: Mapping[str, Any],
) -> None:
    if envelope.get("private_binding") != _baseline_private_binding(state):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if envelope.get("initial_snapshot_lineage") != _baseline_initial_snapshot_lineage(state):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    source_lineage = envelope.get("source_lineage")
    _validate_baseline_source_lineage(source_lineage)
    if envelope.get("source_turn_hash") != source_lineage["source_turn_hash"]:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    if source_lineage["kind"] == "INITIAL_SNAPSHOT":
        initial = _baseline_initial_snapshot_lineage(state)
        if (
            source_lineage["source_ref"] != initial["snapshot_ref"]
            or source_lineage["source_turn_hash"] != initial["snapshot_hash"]
        ):
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
        # A later event does not invalidate an initial-snapshot provenance
        # record.  It only means that the current generation has a newer input.
    elif not _state_has_baseline_source_event(state, source_lineage):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _require_baseline_proposal_identity_matches_envelope(envelope)


def _require_baseline_previous_result_lineage(
    state: Mapping[str, Any],
    envelope: Mapping[str, Any],
) -> None:
    """Require the persisted snapshot to name the actually committed result.

    The current command may later change, so this deliberately compares the
    envelope against the retained committed result rather than asking that old
    command identifiers equal the next command's binding.
    """

    result = state.get("result_json")
    if not isinstance(result, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_RESULT_MISSING")
    try:
        validate_terminal_proposal(result)
    except IntakeGraphContractError as error:
        raise IntakeGraphContractError(
            "INTAKE_BASELINE_CONTEXT_COMMITTED_RESULT_INVALID"
        ) from error
    if result.get("proposal_hash") != envelope.get("proposal_hash"):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_RESULT_MISMATCH")
    if _baseline_proposal_identity(result) != envelope.get("committed_proposal_identity"):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_COMMITTED_RESULT_MISMATCH")


def _state_has_baseline_source_event(
    state: Mapping[str, Any],
    source_lineage: Mapping[str, Any],
) -> bool:
    records = state.get("node_results")
    if not isinstance(records, Mapping):
        return False
    return any(
        isinstance(record, Mapping)
        and record.get("kind") == "EVENT"
        and record.get("stable_id") == source_lineage["source_ref"]
        and record.get("content_hash") == source_lineage["source_turn_hash"]
        and record.get("sequence") == source_lineage["sequence"]
        for record in records.values()
    )


def _require_baseline_proposal_identity_matches_envelope(envelope: Mapping[str, Any]) -> None:
    proposal_hash = envelope.get("proposal_hash")
    identity = envelope.get("committed_proposal_identity")
    if proposal_hash is None:
        if identity is not None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
        return
    _validate_baseline_proposal_identity(identity)
    private_binding = envelope["private_binding"]
    initial = envelope["initial_snapshot_lineage"]
    source = envelope["source_lineage"]
    expected = {
        "command_id": envelope["command_id"],
        "logical_run_id": envelope["logical_run_id"],
        "attempt_id": envelope["attempt_id"],
        "case_id": private_binding["case_id"],
        "room_epoch": private_binding["room_epoch"],
        "thread_id": private_binding["thread_id"],
        "actor_scope_hash": private_binding["actor_scope_hash"],
        "agent_session_id": private_binding["agent_session_id"],
        "cognitive_revision": envelope["target_cognitive_revision"],
        "source_snapshot_hash": initial["snapshot_hash"],
        "source_event_hash": (source["source_turn_hash"] if source["kind"] == "EVENT" else None),
    }
    if any(identity.get(field) != value for field, value in expected.items()):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")


def _validate_baseline_scroll_snapshot(
    snapshot: Any,
    *,
    expected_case_id: str | None = None,
) -> None:
    if (
        not isinstance(snapshot, Mapping)
        or not {
            "schema_version",
            "case_story",
            "case_fact_matrix",
        }
        <= set(snapshot)
        <= _BASELINE_SCROLL_SNAPSHOT_FIELDS
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    if snapshot.get("schema_version") not in _BASELINE_SCROLL_SNAPSHOT_SCHEMA_VERSIONS:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    case_story = snapshot.get("case_story")
    if not isinstance(case_story, Mapping) or not case_story:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    _reject_forbidden_keys(snapshot)
    _validate_safe_json(snapshot, max_array_length=256)
    if (
        _canonical_size(snapshot, "INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
        > _BASELINE_SCROLL_SNAPSHOT_MAX_BYTES
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_TOO_LARGE")
    public_detail = {
        key: deepcopy(value) for key, value in snapshot.items() if key != "case_fact_matrix"
    }
    _validate_model(DossierPatch, public_detail, "INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    matrix = snapshot.get("case_fact_matrix")
    if not isinstance(matrix, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    _validate_model(CaseFactMatrixV2, matrix, "INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    try:
        content_hash_is_valid = validate_case_fact_matrix_content_hash(matrix)
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID") from error
    if not content_hash_is_valid:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID")
    if expected_case_id is not None and matrix.get("case_id") != expected_case_id:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _validate_handoff_remark_partition(
        snapshot.get("handoff_remark_partition"),
        formal_matrix=matrix,
        require_formal_matrix=True,
        error_code="INTAKE_BASELINE_CONTEXT_SNAPSHOT_INVALID",
    )


def _validate_baseline_formal_matrix(
    matrix: Any,
    *,
    expected_case_id: str,
) -> None:
    """Validate the finalizer-owned formal matrix retained before snapshot bind."""

    if not isinstance(matrix, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_INVALID")
    _validate_model(
        CaseFactMatrixV2,
        matrix,
        "INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_INVALID",
    )
    try:
        content_hash_is_valid = validate_case_fact_matrix_content_hash(matrix)
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_INVALID") from error
    if not content_hash_is_valid:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_INVALID")
    _validate_safe_json(matrix, max_array_length=256)
    if (
        _canonical_size(matrix, "INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_INVALID")
        > _BASELINE_SCROLL_SNAPSHOT_MAX_BYTES
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_MATRIX_TOO_LARGE")
    if matrix.get("case_id") != expected_case_id:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")


def _validate_baseline_authority_input(
    envelope: Mapping[str, Any],
    *,
    expected_case_id: str,
) -> None:
    """Validate the private M0/Mn replay material independently of self-hashes."""

    matrix = envelope.get("authority_input_matrix")
    content_hash = envelope.get("authority_input_content_hash")
    matrix_hash = envelope.get("authority_input_matrix_hash")
    if matrix is None:
        if content_hash is not None or matrix_hash is not None:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_INVALID")
        return
    if (
        not isinstance(matrix, Mapping)
        or not isinstance(content_hash, str)
        or not _SHA256.fullmatch(content_hash)
        or not isinstance(matrix_hash, str)
        or not _SHA256.fullmatch(matrix_hash)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_INVALID")
    try:
        _validate_baseline_formal_matrix(matrix, expected_case_id=expected_case_id)
        actual_matrix_hash = canonical_sha256(matrix)
    except (IntakeGraphContractError, TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_INVALID") from error
    if content_hash != matrix.get("content_hash") or matrix_hash != actual_matrix_hash:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_AUTHORITY_INPUT_HASH_INVALID")


def _validate_baseline_execution_receipt_binding(
    envelope: Mapping[str, Any],
    *,
    state: Mapping[str, Any],
    require_present: bool,
    expected_invocation_id: str,
) -> None:
    """Bind the retained cognitive hash to the governed intake policy receipt."""

    invocation_id = envelope.get("execution_receipt_invocation_id")
    node_name = envelope.get("execution_receipt_node_name")
    terminal_draft_hash = envelope.get("terminal_draft_hash")
    if (
        not isinstance(invocation_id, str)
        or not _IDENTIFIER.fullmatch(invocation_id)
        or not isinstance(node_name, str)
        or not _IDENTIFIER.fullmatch(node_name)
        or not isinstance(terminal_draft_hash, str)
        or not _SHA256.fullmatch(terminal_draft_hash)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_RECEIPT_INVALID")
    # The production intake binding derives all three values from one governed
    # execution: command.attempt_id, agent_context.agent_invocation_id, and the
    # fixed baseline intake node.  A self-consistent receipt from a different
    # invocation or node is not authority for this capsule.
    if (
        invocation_id != envelope.get("attempt_id")
        or invocation_id != expected_invocation_id
        or node_name != BASELINE_INTAKE_NODE_NAME
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_RECEIPT_BINDING_INVALID")
    receipts = state.get("execution_receipts")
    if not isinstance(receipts, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_RECEIPT_INVALID")
    receipt = receipts.get(invocation_id)
    if receipt is None:
        if require_present:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_RECEIPT_MISSING")
        return
    expected = {
        "invocation_id": invocation_id,
        "node_name": node_name,
        "output_hash": terminal_draft_hash,
    }
    if not isinstance(receipt, Mapping) or dict(receipt) != expected:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_RECEIPT_OUTPUT_MISMATCH")


def _matrix_derivation_request_base(
    envelope: Mapping[str, Any],
    *,
    expected_private_binding: Mapping[str, Any],
) -> IntakeTurnRequest:
    """Parse the compact, previous-detail-free finalizer request base."""

    base = envelope.get("matrix_derivation_request_base")
    expected_hash = envelope.get("matrix_derivation_request_base_hash")
    if (
        not isinstance(base, Mapping)
        or not isinstance(expected_hash, str)
        or not _SHA256.fullmatch(expected_hash)
        or base.get("previous_case_detail") is not None
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_INVALID")
    if (
        _canonical_size(base, "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_INVALID")
        > _MATRIX_DERIVATION_REQUEST_BASE_MAX_BYTES
        or canonical_sha256(base) != expected_hash
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_HASH_INVALID")
    validation_base = deepcopy(dict(base))
    respondent_opening = base.get("turn_source") == RESPONDENT_OPENING_MARKER
    if respondent_opening:
        authority_input = envelope.get("authority_input_matrix")
        if not isinstance(authority_input, Mapping):
            raise IntakeGraphContractError(
                "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_INVALID"
            )
        validation_base["previous_case_detail"] = {
            "case_fact_matrix": deepcopy(dict(authority_input))
        }
    try:
        request = IntakeTurnRequest.model_validate(validation_base)
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_INVALID"
        ) from error
    serialized = request.model_dump(mode="json")
    if respondent_opening:
        serialized["previous_case_detail"] = None
    if serialized != dict(base):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_INVALID")
    context = request.agent_context
    if (
        request.case_id != expected_private_binding.get("case_id")
        or request.room_type != expected_private_binding.get("room_type")
        or context.case_id != expected_private_binding.get("case_id")
        or context.room_type != expected_private_binding.get("room_type")
        or context.agent_session_id != expected_private_binding.get("agent_session_id")
        or context.actor_role != expected_private_binding.get("audience")
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID")
    return request


def _pre_model_state_for_pending_derivation(
    state: Mapping[str, Any],
    *,
    envelope: Mapping[str, Any],
    response_content: Any,
    allow_response_absent: bool,
) -> tuple[dict[str, Any], bool]:
    """Remove only the capsule-bound current-turn AI response.

    The response is identified from the immutable cognitive draft hash retained
    by the pending envelope, never from the later terminal proposal hash.  Its
    content is still checked against the caller's proposal/cognitive utterance,
    so a proposal substitution cannot make the request-base replay skip the
    response binding.
    """

    source_turn_hash = envelope.get("source_turn_hash")
    output_hash = envelope.get("terminal_draft_hash")
    if (
        not isinstance(source_turn_hash, str)
        or not _SHA256.fullmatch(source_turn_hash)
        or not isinstance(output_hash, str)
        or not _SHA256.fullmatch(output_hash)
        or not isinstance(response_content, str)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID")
    response_message_id = (
        "INTAKE_AI_"
        + canonical_sha256({"source_turn_hash": source_turn_hash, "output_hash": output_hash})[:32]
    )
    messages = state.get("messages")
    if not isinstance(messages, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID")
    candidate = deepcopy(dict(state))
    candidate_messages = deepcopy(dict(messages))
    response = candidate_messages.get(response_message_id)
    if response is None:
        if not allow_response_absent:
            raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_RESPONSE_MISSING")
        candidate["messages"] = candidate_messages
        return candidate, False
    private = (
        state.get("bindings", {}).get("private")
        if isinstance(state.get("bindings"), Mapping)
        else None
    )
    expected = {
        "message_id": response_message_id,
        "role": "AI",
        "audience": private.get("audience") if isinstance(private, Mapping) else None,
        "content": response_content,
        "sequence": state.get("last_event_sequence", 0),
        "source_hash": output_hash,
    }
    if not isinstance(response, Mapping) or dict(response) != expected:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_RESPONSE_INVALID")
    candidate_messages.pop(response_message_id)
    candidate["messages"] = candidate_messages
    return candidate, True


def _rewind_promoted_respondent_opening_pre_model_state(
    state: IntakeGraphStateV2,
    candidate: dict[str, Any],
    *,
    envelope: Mapping[str, Any],
    response_removed: bool,
) -> None:
    """Remove only the exact current opening promotion from replay state."""

    previous = state.get("baseline_previous_case_detail")
    result = state.get("result_json")
    if previous is None and result is None:
        return

    terminal_draft = state.get("terminal_draft")
    if (
        not response_removed
        or state.get("route") != "respondent_opening"
        or "baseline_pending_case_detail" not in state
        or state.get("baseline_pending_case_detail") is not None
        or not isinstance(previous, Mapping)
        or dict(previous) != dict(envelope)
        or not isinstance(terminal_draft, Mapping)
        or not isinstance(result, Mapping)
        or dict(terminal_draft) != dict(result)
    ):
        raise IntakeGraphContractError(
            "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID"
        )
    _validate_baseline_context_envelope(
        envelope,
        require_bound=True,
        state=state,
    )
    _require_pending_envelope_matches_proposal(state, envelope, result)
    _require_baseline_previous_result_lineage(state, envelope)
    _require_pending_public_dossier_matches_state(state, envelope)
    candidate["baseline_previous_case_detail"] = None
    candidate["result_json"] = None


def _matches_derivation_request_forward_compaction(
    authoritative: Mapping[str, Any],
    actual: Mapping[str, Any],
) -> bool:
    """Accept only the bounded suffix produced by appending one verified AI response."""

    if dict(actual) == dict(authoritative):
        return True
    message_capacity = _INTAKE_LIMITS.message_count
    if message_capacity < 2:
        return False
    authoritative_recent = authoritative.get("recent_dialogue_messages")
    authoritative_current = authoritative.get("current_user_message")
    actual_recent = actual.get("recent_dialogue_messages")
    if (
        not isinstance(authoritative_recent, list)
        or not isinstance(authoritative_current, Mapping)
        or not isinstance(actual_recent, list)
    ):
        return False
    # The authoritative window is recent + current. Appending the already
    # verified AI response may evict only its mathematically required oldest
    # prefix under merge_intake_messages; removing that AI leaves this suffix.
    dropped = max(0, len(authoritative_recent) + 2 - message_capacity)
    if dropped != 1:
        return False
    expected = deepcopy(dict(authoritative))
    expected["recent_dialogue_messages"] = deepcopy(authoritative_recent[dropped:])
    return expected == dict(actual)


def _require_pending_derivation_request_matches_pre_model_state(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
    *,
    response_content: Any,
    allow_response_absent: bool = False,
) -> None:
    """Tie the compact request base to the exact pre-model state projection."""

    private_binding = envelope.get("private_binding")
    if not isinstance(private_binding, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID")
    request = _matrix_derivation_request_base(
        envelope,
        expected_private_binding=private_binding,
    )
    pre_model_state, response_removed = _pre_model_state_for_pending_derivation(
        state,
        envelope=envelope,
        response_content=response_content,
        allow_response_absent=allow_response_absent,
    )
    if request.turn_source == RESPONDENT_OPENING_MARKER:
        _rewind_promoted_respondent_opening_pre_model_state(
            state,
            pre_model_state,
            envelope=envelope,
            response_removed=response_removed,
        )
        authority_input = envelope.get("authority_input_matrix")
        dossier = pre_model_state.get("dossier_draft")
        if not isinstance(authority_input, Mapping) or not isinstance(dossier, Mapping):
            raise IntakeGraphContractError(
                "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID"
            )
        existing_matrix = dossier.get("case_fact_matrix")
        if existing_matrix is not None and existing_matrix != authority_input:
            raise IntakeGraphContractError(
                "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID"
            )
        pre_model_state["dossier_draft"] = {
            **deepcopy(dict(dossier)),
            "case_fact_matrix": deepcopy(dict(authority_input)),
        }
    try:
        expected = build_intake_baseline_request(
            pre_model_state,
            agent_context=request.agent_context,
        ).model_dump(mode="json")
    except (IntakeGraphContractError, TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID"
        ) from error
    # The complete prior detail is deliberately not retained.  Its only formal
    # authority is checked separately through ``authority_input_matrix``.
    expected["previous_case_detail"] = None
    authoritative = envelope.get("matrix_derivation_request_base")
    if expected == authoritative:
        return
    # Before the AI patch is merged, response_removed is false and the original
    # exact authority check above remains mandatory. Only the terminal state may
    # account for the one oldest recent message evicted by the bounded reducer.
    if (
        not response_removed
        or not isinstance(authoritative, Mapping)
        or not _matches_derivation_request_forward_compaction(authoritative, expected)
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID")


def _derivation_request_with_authority_input(
    envelope: Mapping[str, Any],
) -> IntakeTurnRequest:
    private_binding = envelope.get("private_binding")
    if not isinstance(private_binding, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_BINDING_INVALID")
    base = _matrix_derivation_request_base(
        envelope,
        expected_private_binding=private_binding,
    ).model_dump(mode="json")
    authority_input = envelope.get("authority_input_matrix")
    base["previous_case_detail"] = (
        {"case_fact_matrix": deepcopy(dict(authority_input))}
        if isinstance(authority_input, Mapping)
        else None
    )
    try:
        return IntakeTurnRequest.model_validate(base)
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_BASELINE_CONTEXT_DERIVATION_REQUEST_INVALID"
        ) from error


def _require_pending_normalized_matrix_patch(
    envelope: Mapping[str, Any],
    matrix_patch: Any,
) -> None:
    """Require the exact normalized patch retained before proposal projection."""

    if matrix_patch != envelope.get("normalized_matrix_patch") or envelope.get(
        "matrix_patch_hash"
    ) != canonical_sha256(matrix_patch):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH")


def _formal_delta_from_matrix_patch(matrix_patch: Any) -> Any:
    if matrix_patch is None:
        return None
    if not isinstance(matrix_patch, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH")
    try:
        if matrix_patch.get("schema_version") == "case_fact_matrix.delta.v2":
            return FormalCaseFactMatrixDeltaV2.model_validate(matrix_patch)
        if matrix_patch.get("schema_version") == "unilateral_case_matrix.draft.v1":
            return FormalUnilateralCaseMatrixDraftV1.model_validate(matrix_patch)
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH") from error
    raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PENDING_MATRIX_MISMATCH")


def _require_pending_formal_matrix_derivation(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
    *,
    matrix_patch: Any,
    response_content: Any,
    allow_response_absent: bool = False,
) -> None:
    """Re-run the canonical finalizer from the retained input, patch and public dossier."""

    _require_pending_normalized_matrix_patch(envelope, matrix_patch)
    _require_pending_derivation_request_matches_pre_model_state(
        state,
        envelope,
        response_content=response_content,
        allow_response_absent=allow_response_absent,
    )
    _require_pending_public_dossier_matches_state(state, envelope)
    dossier = state.get("dossier_draft")
    if not isinstance(dossier, Mapping) or "case_fact_matrix" in dossier:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_INVALID")
    terminal_draft = state.get("terminal_draft")
    conversation_action = (
        terminal_draft.get("conversation_action")
        if isinstance(terminal_draft, Mapping)
        else None
    )
    if conversation_action in {"ACK_REMARK", "ACK_NO_REMARK"}:
        private_binding = envelope.get("private_binding")
        actor_role = (
            private_binding.get("audience")
            if isinstance(private_binding, Mapping)
            else None
        )
        partition = dossier.get("handoff_remark_partition")
        parties = (
            partition.get("parties") if isinstance(partition, Mapping) else None
        )
        actor_partition = (
            parties.get(actor_role)
            if actor_role in {"USER", "MERCHANT"}
            and isinstance(parties, Mapping)
            else None
        )
        authority_input = envelope.get("authority_input_matrix")
        formal_matrix = envelope.get("formal_matrix")
        parent_ref = (
            formal_matrix.get("parent_ref")
            if isinstance(formal_matrix, Mapping)
            else None
        )
        combined_substantive_no_remark = bool(
            conversation_action == "ACK_NO_REMARK"
            and matrix_patch is not None
            and isinstance(authority_input, Mapping)
            and isinstance(formal_matrix, Mapping)
            and isinstance(parent_ref, Mapping)
            and isinstance(actor_partition, Mapping)
            and actor_partition.get("party_role") == actor_role
            and actor_partition.get("remark_status") == "NO_EXTRA_REMARKS"
            and parent_ref.get("matrix_id") == authority_input.get("matrix_id")
            and parent_ref.get("matrix_version")
            == authority_input.get("matrix_version")
            and parent_ref.get("content_hash") == authority_input.get("content_hash")
        )
        if not combined_substantive_no_remark:
            if matrix_patch is not None:
                raise IntakeGraphContractError("INTAKE_REMARK_MATRIX_PATCH_FORBIDDEN")
            previous_context = state.get("baseline_previous_case_detail")
            if _is_baseline_context_envelope(previous_context):
                previous = unwrap_verified_baseline_previous_case_detail(state)
            elif isinstance(previous_context, Mapping):
                # ``validate_state`` has already validated the legacy scroll
                # snapshot before this derivation check.  It remains read-only and
                # is replaced by the current hash-bound envelope at commit.
                previous = deepcopy(dict(previous_context))
            else:
                raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
            frozen_matrix = previous.get("case_fact_matrix")
            partition = dossier.get("handoff_remark_partition")
            if (
                not isinstance(frozen_matrix, Mapping)
                or not isinstance(partition, Mapping)
                or not isinstance(formal_matrix, Mapping)
                or dict(formal_matrix) != dict(frozen_matrix)
                or partition.get("case_fact_matrix_id") != frozen_matrix.get("matrix_id")
                or partition.get("case_fact_matrix_version")
                != frozen_matrix.get("matrix_version")
                or partition.get("case_fact_matrix_hash")
                != frozen_matrix.get("content_hash")
            ):
                raise IntakeGraphContractError("INTAKE_REMARK_FROZEN_MATRIX_CONFLICT")
            return
    try:
        expected = finalize_case_fact_matrix(
            request=_derivation_request_with_authority_input(envelope),
            case_detail=deepcopy(dict(dossier)),
            delta=_formal_delta_from_matrix_patch(matrix_patch),
        ).model_dump(mode="json")
    except (AgentOutputSchemaError, IntakeGraphContractError, TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_BASELINE_CONTEXT_FORMAL_DERIVATION_INVALID"
        ) from error
    formal_matrix = envelope.get("formal_matrix")
    if (
        not isinstance(formal_matrix, Mapping)
        or canonical_sha256(expected) != canonical_sha256(formal_matrix)
        or canonical_sha256(expected) != envelope.get("formal_matrix_hash")
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_FORMAL_DERIVATION_MISMATCH")


def _baseline_snapshot_public_dossier(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    """Return the public half of a complete baseline scroll snapshot."""

    public = deepcopy(dict(snapshot))
    public.pop("case_fact_matrix", None)
    public.pop("unilateral_case_matrix", None)
    return public


def _materialize_baseline_scroll_snapshot(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
) -> dict[str, Any]:
    """Attach a trusted formal matrix to the exact post-apply public dossier."""

    _require_pending_public_dossier_matches_state(state, envelope)
    dossier = state.get("dossier_draft")
    if not isinstance(dossier, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_INVALID")
    if "case_fact_matrix" in dossier or "unilateral_case_matrix" in dossier:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_INVALID")
    formal_matrix = envelope.get("formal_matrix")
    private_binding = envelope.get("private_binding")
    if not isinstance(private_binding, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _validate_baseline_formal_matrix(
        formal_matrix,
        expected_case_id=private_binding["case_id"],
    )
    snapshot = deepcopy(dict(dossier))
    snapshot["case_fact_matrix"] = deepcopy(dict(formal_matrix))
    _validate_baseline_scroll_snapshot(
        snapshot,
        expected_case_id=private_binding["case_id"],
    )
    if canonical_sha256(_baseline_snapshot_public_dossier(snapshot)) != envelope.get(
        "public_dossier_hash"
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_MISMATCH")
    return snapshot


def _require_pending_public_dossier_consistency(
    state: Mapping[str, Any],
    envelope: Mapping[str, Any],
) -> None:
    """Accept only the pre-apply or post-apply public state for an unbound turn."""

    dossier = state.get("dossier_draft")
    terminal_draft = state.get("terminal_draft")
    if not isinstance(dossier, Mapping) or not isinstance(terminal_draft, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_INVALID")
    patch = terminal_draft.get("dossier_patch")
    if not isinstance(patch, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_INVALID")
    expected_hash = envelope.get("public_dossier_hash")
    if canonical_sha256(dossier) == expected_hash:
        return
    projected = merge_intake_dossier(dossier, patch)
    if canonical_sha256(projected) != expected_hash:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_MISMATCH")


def _require_pending_public_dossier_matches_state(
    state: Mapping[str, Any],
    envelope: Mapping[str, Any],
) -> None:
    dossier = state.get("dossier_draft")
    if not isinstance(dossier, Mapping) or canonical_sha256(dossier) != envelope.get(
        "public_dossier_hash"
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_PUBLIC_DOSSIER_MISMATCH")


def _current_source_turn_hash(state: IntakeGraphStateV2) -> str:
    source_turn_hash = state.get("last_event_hash") or state.get("initial_snapshot_hash")
    if not isinstance(source_turn_hash, str) or not _SHA256.fullmatch(source_turn_hash):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_SOURCE_INVALID")
    return source_turn_hash


def _require_pending_envelope_matches_cognitive_draft(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
) -> None:
    command = state["bindings"]["command"]
    expected = {
        "command_id": command["command_id"],
        "logical_run_id": command["logical_run_id"],
        "attempt_id": command["attempt_id"],
        "source_turn_hash": _current_source_turn_hash(state),
        "target_cognitive_revision": state["cognitive_revision"],
    }
    if any(envelope.get(field) != value for field, value in expected.items()):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if envelope.get("proposal_hash") is not None:
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    terminal_draft = state.get("terminal_draft")
    if (
        not isinstance(terminal_draft, Mapping)
        or terminal_draft.get("schema_version") == "intake-turn-proposal.v2"
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if envelope.get("terminal_draft_hash") != canonical_sha256(terminal_draft):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _require_pending_normalized_matrix_patch(
        envelope,
        terminal_draft.get("matrix_patch"),
    )
    _require_pending_public_dossier_consistency(state, envelope)


def _require_pending_envelope_matches_proposal(
    state: IntakeGraphStateV2,
    envelope: Mapping[str, Any],
    proposal: Mapping[str, Any],
) -> None:
    command = state["bindings"]["command"]
    expected = {
        "command_id": command["command_id"],
        "logical_run_id": command["logical_run_id"],
        "attempt_id": command["attempt_id"],
        "source_turn_hash": _current_source_turn_hash(state),
        "target_cognitive_revision": state["cognitive_revision"],
        "proposal_hash": proposal.get("proposal_hash"),
    }
    if any(envelope.get(field) != value for field, value in expected.items()):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    if proposal.get("proposal_hash") != canonical_sha256_omitting(proposal, "proposal_hash"):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_BINDING_INVALID")
    _require_pending_normalized_matrix_patch(envelope, proposal.get("matrix_patch"))


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


def next_intake_cognitive_revision(state: Mapping[str, Any]) -> int:
    """Return the one terminal revision permitted for the current Intake turn.

    The process-owned fresh checkpoint is labelled ``1`` before it contains a
    terminal proposal, while the external thread registry remains at revision
    ``0``.  That label represents the first turn's target revision, not an
    already committed turn.  Standalone graph construction begins at ``0`` and
    has the same first target.  Once a terminal proposal exists, each resumed
    turn advances exactly once from that committed revision.
    """

    revision = _strict_int(state.get("cognitive_revision"), minimum=0)
    result = state.get("result_json")
    if result is None:
        if revision in {0, 1}:
            return 1
        raise IntakeGraphContractError("INTAKE_COGNITIVE_REVISION_INVALID")
    if not isinstance(result, Mapping) or revision < 1:
        raise IntakeGraphContractError("INTAKE_COGNITIVE_REVISION_INVALID")
    try:
        validate_terminal_proposal(result)
    except IntakeGraphContractError as error:
        raise IntakeGraphContractError(
            "INTAKE_COGNITIVE_REVISION_INVALID"
        ) from error
    result_revision = _strict_int(result.get("cognitive_revision"), minimum=1)
    if result_revision != revision:
        raise IntakeGraphContractError("INTAKE_COGNITIVE_REVISION_INVALID")
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


def _validate_safe_json(value: Any, *, max_array_length: int = 128) -> None:
    if isinstance(value, Mapping):
        if len(value) > 64:
            raise IntakeGraphContractError("INTAKE_OBJECT_TOO_WIDE")
        for child in value.values():
            _validate_safe_json(child, max_array_length=max_array_length)
    elif isinstance(value, list | tuple):
        if len(value) > max_array_length:
            raise IntakeGraphContractError("INTAKE_ARRAY_TOO_LONG")
        for child in value:
            _validate_safe_json(child, max_array_length=max_array_length)
    elif isinstance(value, str) and len(value) > 20_000:
        raise IntakeGraphContractError("INTAKE_STRING_TOO_LONG")
    elif value is not None and not isinstance(value, str | int | float | bool):
        raise IntakeGraphContractError("INTAKE_VALUE_NOT_CANONICAL_JSON")
