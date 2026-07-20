from __future__ import annotations

import re
from collections.abc import Mapping
from copy import deepcopy
from typing import Any

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
    IntakeCognitionDraft,
    IntakeDomainSnapshot,
    IntakeTurnEvent,
    IntakeTurnProposal,
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
    if kind not in {"SNAPSHOT", "EVENT"} or not isinstance(payload, dict):
        raise IntakeGraphContractError("INTAKE_INGRESS_INVALID")
    _reject_forbidden_keys(payload)
    return kind, payload


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
