from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

from app.contracts.v1.codec import canonical_sha256_omitting, canonicalize
from app.graph_runtime.state import GraphStateLimits, validate_graph_state
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.contracts import (
    IntakeDomainSnapshot,
    IntakeTurnEvent,
    IntakeTurnProposal,
)
from app.graphs.intake.state import IntakeGraphStateV2, IntakeTurnContext


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_THREAD_ID = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
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
        "open_evidence",
        "complete_party",
        "send_summons",
        "execute_tool",
    }
)
_INTAKE_LIMITS = GraphStateLimits(message_count=6)


def validate_state(state: IntakeGraphStateV2) -> None:
    if state.get("schema_version") != "intake-graph-state.v2":
        raise IntakeGraphContractError("INTAKE_STATE_SCHEMA_INVALID")
    _reject_forbidden_keys(state)
    try:
        validate_graph_state(dict(state), limits=_INTAKE_LIMITS)
    except ValueError as error:
        raise IntakeGraphContractError(str(error)) from error
    bindings = state.get("bindings")
    if not isinstance(bindings, dict):
        raise IntakeGraphContractError("INTAKE_BINDINGS_MISSING")
    if bindings.get("room_type") != "INTAKE":
        raise IntakeGraphContractError("INTAKE_ROOM_TYPE_INVALID")
    if not _THREAD_ID.fullmatch(str(bindings.get("thread_id", ""))):
        raise IntakeGraphContractError("INTAKE_THREAD_ID_INVALID")
    if not _SHA256.fullmatch(str(bindings.get("actor_scope_hash", ""))):
        raise IntakeGraphContractError("INTAKE_ACTOR_SCOPE_HASH_INVALID")
    if bindings.get("audience") not in {"USER", "MERCHANT"}:
        raise IntakeGraphContractError("INTAKE_AUDIENCE_INVALID")
    pins = state.get("version_pins")
    if not isinstance(pins, dict) or pins.get("output_schema_version") != (
        "intake-turn-proposal.v2"
    ):
        raise IntakeGraphContractError("INTAKE_VERSION_PINS_INVALID")


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
    if len(canonicalize(snapshot)) > 262_144:
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_TOO_LARGE")
    if snapshot.get("schema_version") != "intake-domain-snapshot.v2":
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_SCHEMA_INVALID")
    _validate_binding(state, snapshot)
    if snapshot.get("visibility") != "PRIVATE":
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_VISIBILITY_INVALID")
    messages = snapshot.get("own_messages")
    if not isinstance(messages, list) or len(messages) > 6:
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_MESSAGES_INVALID")
    _verify_self_hash(snapshot, "snapshot_hash", "INTAKE_SNAPSHOT_HASH_INVALID")
    _validate_safe_json(snapshot)


def validate_event(state: IntakeGraphStateV2, event: Mapping[str, Any]) -> None:
    _validate_model(IntakeTurnEvent, event, "INTAKE_EVENT_SCHEMA_INVALID")
    if len(canonicalize(event)) > 32_768:
        raise IntakeGraphContractError("INTAKE_EVENT_TOO_LARGE")
    if event.get("schema_version") != "intake-turn-event.v2":
        raise IntakeGraphContractError("INTAKE_EVENT_SCHEMA_INVALID")
    _validate_binding(state, event)
    if event.get("audience") != state["bindings"]["audience"]:
        raise IntakeGraphContractError("INTAKE_EVENT_AUDIENCE_MISMATCH")
    text = event.get("text")
    if not isinstance(text, str) or not text or len(text.encode("utf-8")) > 8192:
        raise IntakeGraphContractError("INTAKE_EVENT_TEXT_INVALID")
    _verify_self_hash(event, "event_hash", "INTAKE_EVENT_HASH_INVALID")


def validate_terminal_proposal(proposal: Mapping[str, Any]) -> None:
    _validate_model(IntakeTurnProposal, proposal, "INTAKE_PROPOSAL_SCHEMA_INVALID")
    _reject_forbidden_keys(proposal)
    if proposal.get("schema_version") != "intake-turn-proposal.v2":
        raise IntakeGraphContractError("INTAKE_PROPOSAL_SCHEMA_INVALID")
    if len(canonicalize(proposal)) > 65_536:
        raise IntakeGraphContractError("INTAKE_PROPOSAL_TOO_LARGE")
    _verify_self_hash(proposal, "proposal_hash", "INTAKE_PROPOSAL_HASH_INVALID")
    _validate_safe_json(proposal)


def _validate_binding(
    state: IntakeGraphStateV2,
    payload: Mapping[str, Any],
) -> None:
    bindings = state["bindings"]
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


def _reject_forbidden_keys(value: Any) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if key in _FORBIDDEN_KEYS:
                raise IntakeGraphContractError("INTAKE_FORBIDDEN_FIELD")
            _reject_forbidden_keys(child)
    elif isinstance(value, list):
        for child in value:
            _reject_forbidden_keys(child)


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
