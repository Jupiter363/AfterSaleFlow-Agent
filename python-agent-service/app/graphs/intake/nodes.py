from __future__ import annotations

import hashlib
from collections.abc import Callable, Mapping
from copy import deepcopy
from typing import Any, TypeAlias, cast

from langgraph.runtime import Runtime

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.reducers import merge_node_results
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import (
    IntakeGraphStateV2,
    IntakeMessageState,
    IntakeTurnContext,
    JsonObject,
    merge_intake_messages,
)
from app.graphs.intake.validators import (
    MATRIX_AUTHORITY_RECORD_KEY,
    ingress,
    bootstrap_event_ingress,
    matrix_authority_record,
    validate_cognition_patch,
    validate_dossier_transition,
    validate_event,
    validate_node_patch,
    validate_proposal_binding,
    validate_snapshot,
    validate_state,
    validate_terminal_proposal,
)


IntakeCognitionNode: TypeAlias = Callable[
    [IntakeGraphStateV2, Runtime[IntakeTurnContext]], Mapping[str, Any]
]

_DOSSIER_BRANCHES = frozenset(
    {
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
    }
)


def guard_intake_cognition(node: IntakeCognitionNode) -> IntakeCognitionNode:
    def guarded(
        state: IntakeGraphStateV2,
        runtime: Runtime[IntakeTurnContext],
    ) -> Mapping[str, Any]:
        return validate_cognition_patch(state, node(state, runtime))

    return guarded


def authorize_and_load(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    validate_state(state)
    kind, payload = ingress(runtime.context)
    if kind == "SNAPSHOT":
        validate_snapshot(state, payload)
    elif kind == "EVENT":
        validate_event(state, payload)
    else:
        snapshot, event = bootstrap_event_ingress(runtime.context)
        validate_snapshot(state, snapshot)
        validate_event(state, event)
    return {}


def import_snapshot_once_or_apply_event(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    kind, payload = ingress(runtime.context)
    if kind == "SNAPSHOT":
        return validate_node_patch(state, _import_snapshot(state, payload))
    if kind == "EVENT":
        return validate_node_patch(state, _apply_event(state, payload))
    snapshot, event = bootstrap_event_ingress(runtime.context)
    snapshot_patch = _import_snapshot(state, snapshot)
    imported = _state_after_patch(state, snapshot_patch)
    event_patch = _apply_event(imported, event)
    return validate_node_patch(state, _merge_bootstrap_patches(snapshot_patch, event_patch))


def route_turn(state: IntakeGraphStateV2) -> dict[str, Any]:
    route = state.get("route")
    if route not in {"initialize", "message", "replay"}:
        raise IntakeGraphContractError("INTAKE_ROUTE_INVALID")
    return {}


def deterministic_seed(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    del runtime
    return validate_cognition_patch(
        state,
        {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": {
                "room_utterance": "Please provide the missing Intake details.",
                "dossier_patch": {},
                "matrix_patch": None,
                "readiness": "INCOMPLETE",
                "missing_fields": ["requested_resolution_detail"],
                "recommendation": "NEED_MORE_INFO",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.0,
            },
        },
    )


def unconfigured_intake_lcel(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    del state, runtime
    raise IntakeGraphContractError("INTAKE_LCEL_NOT_CONFIGURED")


def deterministic_message_fallback(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    del runtime
    return {
        "cognitive_revision": state["cognitive_revision"] + 1,
        "terminal_draft": {
            "room_utterance": "The Intake message was recorded for structured review.",
            "dossier_patch": {},
            "matrix_patch": None,
            "readiness": "INCOMPLETE",
            "missing_fields": [],
            "recommendation": "NEED_MORE_INFO",
            "knowledge_answer_mode": "NONE",
            "confidence": 0.0,
        },
    }


def cached_terminal_projection(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    cached = state.get("result_json")
    if not isinstance(cached, dict):
        raise IntakeGraphContractError("INTAKE_REPLAY_RESULT_MISSING")
    validate_terminal_proposal(cached)
    validate_proposal_binding(state, cached)
    kind, payload = ingress(runtime.context)
    if kind == "SNAPSHOT":
        if cached.get("source_event_hash") is not None or state.get("last_event_hash") is not None:
            raise IntakeGraphContractError("INTAKE_REPLAY_SOURCE_MISMATCH")
    elif kind == "EVENT":
        if cached.get("source_event_hash") != payload.get("event_hash"):
            raise IntakeGraphContractError("INTAKE_REPLAY_SOURCE_MISMATCH")
    elif kind == "BOOTSTRAP_EVENT":
        _, event = bootstrap_event_ingress(runtime.context)
        if cached.get("source_event_hash") != event.get("event_hash"):
            raise IntakeGraphContractError("INTAKE_REPLAY_SOURCE_MISMATCH")
    else:
        raise IntakeGraphContractError("INTAKE_REPLAY_SOURCE_MISMATCH")
    return validate_node_patch(state, {"terminal_draft": deepcopy(cached)})


def apply_dossier_patch(state: IntakeGraphStateV2) -> dict[str, Any]:
    if state.get("route") == "replay":
        return {}
    draft = _turn_draft(state)
    patch = draft.get("dossier_patch", {})
    if not isinstance(patch, dict) or not set(patch) <= _DOSSIER_BRANCHES:
        raise IntakeGraphContractError("INTAKE_DOSSIER_PATCH_INVALID")
    dossier = _merge_object(state["dossier_draft"], patch)
    validate_dossier_transition(state["dossier_draft"], dossier)
    return validate_node_patch(state, {"dossier_draft": dossier})


def validate_readiness(state: IntakeGraphStateV2) -> dict[str, Any]:
    if state.get("route") == "replay":
        return {}
    draft = _turn_draft(state)
    readiness = draft.get("readiness")
    recommendation = draft.get("recommendation")
    missing = draft.get("missing_fields")
    if readiness not in {"INCOMPLETE", "READY_TO_CONFIRM", "NEEDS_REVIEW"}:
        raise IntakeGraphContractError("INTAKE_READINESS_INVALID")
    if recommendation not in {"ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"}:
        raise IntakeGraphContractError("INTAKE_RECOMMENDATION_INVALID")
    if not isinstance(missing, list) or not all(isinstance(item, str) and item for item in missing):
        raise IntakeGraphContractError("INTAKE_MISSING_FIELDS_INVALID")
    normalized_missing = sorted(set(missing))
    if readiness == "READY_TO_CONFIRM" and normalized_missing:
        raise IntakeGraphContractError("INTAKE_READY_WITH_MISSING_FIELDS")
    return validate_node_patch(
        state,
        {
            "readiness": {
                "status": readiness,
                "evaluated_revision": state["cognitive_revision"],
            },
            "missing_fields": normalized_missing,
            "recommendation": recommendation,
        },
    )


def project_intake_proposal(state: IntakeGraphStateV2) -> dict[str, Any]:
    if state.get("route") == "replay":
        return {}
    draft = _turn_draft(state)
    private = state["bindings"]["private"]
    command = state["bindings"]["command"]
    pins = state["version_pins"]
    proposal: JsonObject = {
        "schema_version": "intake-turn-proposal.v2",
        "command_id": command["command_id"],
        "logical_run_id": command["logical_run_id"],
        "attempt_id": command["attempt_id"],
        "case_id": private["case_id"],
        "room_epoch": private["room_epoch"],
        "thread_id": private["thread_id"],
        "actor_scope_hash": private["actor_scope_hash"],
        "agent_session_id": private["agent_session_id"],
        "cognitive_revision": state["cognitive_revision"],
        "source_snapshot_hash": state["initial_snapshot_hash"],
        "room_utterance": _required_text(draft, "room_utterance"),
        "dossier_patch": deepcopy(cast(dict[str, Any], draft["dossier_patch"])),
        "matrix_patch": deepcopy(draft.get("matrix_patch")),
        "readiness": state["readiness"]["status"],
        "missing_fields": list(state["missing_fields"]),
        "recommendation": state["recommendation"],
        "knowledge_answer_mode": draft.get("knowledge_answer_mode", "NONE"),
        "confidence": _confidence(draft.get("confidence")),
        "profile_versions": {
            "graph_version": pins["graph_version"],
            "checkpoint_schema_version": pins["checkpoint_schema_version"],
            "prompt_version": pins["prompt_version"],
            "model_profile_id": pins["model_profile_id"],
            "output_schema_version": pins["output_schema_version"],
            "policy_version": pins["policy_version"],
            "guardrail_version": pins["guardrail_version"],
            "tool_policy_version": pins["tool_policy_version"],
        },
    }
    if state.get("last_event_hash") is not None:
        proposal["source_event_hash"] = state["last_event_hash"]
    proposal["proposal_hash"] = canonical_sha256(proposal)
    validate_terminal_proposal(proposal)
    validate_proposal_binding(state, proposal)
    return validate_node_patch(state, {"terminal_draft": proposal})


def checkpoint_terminal(state: IntakeGraphStateV2) -> dict[str, Any]:
    proposal = state.get("terminal_draft")
    if not isinstance(proposal, dict):
        raise IntakeGraphContractError("INTAKE_TERMINAL_DRAFT_MISSING")
    validate_terminal_proposal(proposal)
    validate_proposal_binding(state, proposal)
    return validate_node_patch(state, {"result_json": deepcopy(proposal)})


def _import_snapshot(
    state: IntakeGraphStateV2,
    snapshot: Mapping[str, Any],
) -> dict[str, Any]:
    snapshot_hash = cast(str, snapshot["snapshot_hash"])
    existing = state.get("initial_snapshot_hash")
    if existing is not None:
        if existing != snapshot_hash:
            raise IntakeGraphContractError("INTAKE_SNAPSHOT_REIMPORT_CONFLICT")
        return {"route": "replay"}
    messages: dict[str, IntakeMessageState] = {}
    stable_records: dict[str, JsonObject] = {
        MATRIX_AUTHORITY_RECORD_KEY: matrix_authority_record(state, snapshot)
    }
    for value in cast(list[dict[str, Any]], snapshot["own_messages"]):
        message: IntakeMessageState = {
            "message_id": cast(str, value["message_id"]),
            "role": cast(Any, value["role"]),
            "audience": cast(Any, value["audience"]),
            "content": cast(str, value["text"]),
            "sequence": cast(int, value["sequence"]),
            "source_hash": cast(str, value["source_hash"]),
        }
        messages[message["message_id"]] = message
        stable_records[_stable_record_key("message", message["message_id"])] = {
            "kind": "MESSAGE",
            "stable_id": message["message_id"],
            "content_hash": message["source_hash"],
            "sequence": message["sequence"],
        }
    last_sequence = max(
        (message["sequence"] for message in messages.values()),
        default=0,
    )
    return {
        "initial_snapshot_ref": snapshot["snapshot_id"],
        "initial_snapshot_hash": snapshot_hash,
        "initial_domain_revision": snapshot["domain_revision"],
        "messages": messages,
        "node_results": stable_records,
        "last_event_sequence": last_sequence,
        "dossier_draft": deepcopy(snapshot["current_dossier"]),
        "route": "initialize",
    }


def _apply_event(
    state: IntakeGraphStateV2,
    event: Mapping[str, Any],
) -> dict[str, Any]:
    if state.get("initial_snapshot_hash") is None:
        raise IntakeGraphContractError("INTAKE_EVENT_BEFORE_SNAPSHOT")
    sequence = cast(int, event["sequence_no"])
    event_hash = cast(str, event["event_hash"])
    event_id = cast(str, event["event_id"])
    message_id = cast(str, event["message_id"])
    previous_sequence = state.get("last_event_sequence", 0)
    _reject_stable_record_rebinding(state, "event", event_id, event_hash)
    _reject_stable_record_rebinding(state, "message", message_id, event_hash)
    if event_id == state.get("last_event_ref"):
        if sequence == previous_sequence and event_hash == state.get("last_event_hash"):
            return {"route": "replay"}
        raise IntakeGraphContractError("INTAKE_EVENT_REPLAY_CONFLICT")
    if sequence == previous_sequence and event_hash == state.get("last_event_hash"):
        return {"route": "replay"}
    if sequence == previous_sequence:
        raise IntakeGraphContractError("INTAKE_EVENT_REPLAY_CONFLICT")
    if sequence != previous_sequence + 1:
        raise IntakeGraphContractError("INTAKE_EVENT_SEQUENCE_INVALID")
    message: IntakeMessageState = {
        "message_id": message_id,
        "role": "HUMAN",
        "audience": cast(Any, event["audience"]),
        "content": cast(str, event["text"]),
        "sequence": sequence,
        "source_hash": event_hash,
    }
    return {
        "last_event_ref": event_id,
        "last_event_hash": event_hash,
        "last_event_sequence": sequence,
        "messages": {message["message_id"]: message},
        "node_results": {
            _stable_record_key("event", event_id): {
                "kind": "EVENT",
                "stable_id": event_id,
                "content_hash": event_hash,
                "sequence": sequence,
                "message_id": message_id,
            },
            _stable_record_key("message", message_id): {
                "kind": "MESSAGE",
                "stable_id": message_id,
                "content_hash": event_hash,
                "sequence": sequence,
            },
        },
        "route": "message",
    }


def _state_after_patch(
    state: IntakeGraphStateV2,
    patch: Mapping[str, Any],
) -> IntakeGraphStateV2:
    """Apply only the reducers needed before the bootstrap event is evaluated."""
    validate_node_patch(state, patch)
    candidate = deepcopy(dict(state))
    for field, reducer in (
        ("messages", merge_intake_messages),
        ("node_results", merge_node_results),
    ):
        if field in patch:
            candidate[field] = reducer(candidate.get(field), patch[field])
    candidate.update(
        {
            key: deepcopy(value)
            for key, value in patch.items()
            if key not in {"messages", "node_results"}
        }
    )
    validate_state(cast(IntakeGraphStateV2, candidate))
    return cast(IntakeGraphStateV2, candidate)


def _merge_bootstrap_patches(
    snapshot_patch: Mapping[str, Any],
    event_patch: Mapping[str, Any],
) -> dict[str, Any]:
    """Preserve reducer semantics while making snapshot import precede the first event."""
    patch = deepcopy(dict(snapshot_patch))
    for field, reducer in (("messages", merge_intake_messages), ("node_results", merge_node_results)):
        left = patch.get(field)
        right = event_patch.get(field)
        if right is not None:
            patch[field] = reducer(left, right) if left is not None else deepcopy(right)
    patch.update(
        {
            key: deepcopy(value)
            for key, value in event_patch.items()
            if key not in {"messages", "node_results"}
        }
    )
    return patch


def _reject_stable_record_rebinding(
    state: IntakeGraphStateV2,
    kind: str,
    stable_id: str,
    content_hash: str,
) -> None:
    record = state["node_results"].get(_stable_record_key(kind, stable_id))
    if record is not None and (
        record.get("stable_id") != stable_id or record.get("content_hash") != content_hash
    ):
        raise IntakeGraphContractError("INTAKE_STABLE_ID_REBINDING")


def _stable_record_key(kind: str, stable_id: str) -> str:
    digest = hashlib.sha256(stable_id.encode("utf-8")).hexdigest()
    return f"{kind}:{digest}"


def _turn_draft(state: IntakeGraphStateV2) -> JsonObject:
    value = state.get("terminal_draft")
    if not isinstance(value, dict):
        raise IntakeGraphContractError("INTAKE_COGNITION_DRAFT_MISSING")
    return value


def _merge_object(left: Mapping[str, Any], right: Mapping[str, Any]) -> JsonObject:
    merged = deepcopy(dict(left))
    for key in sorted(right):
        incoming = right[key]
        existing = merged.get(key)
        if isinstance(existing, dict) and isinstance(incoming, dict):
            merged[key] = _merge_object(existing, incoming)
        else:
            merged[key] = deepcopy(incoming)
    return cast(JsonObject, merged)


def _required_text(value: Mapping[str, Any], member: str) -> str:
    text = value.get(member)
    if not isinstance(text, str) or not text or len(text) > 20_000:
        raise IntakeGraphContractError("INTAKE_ROOM_UTTERANCE_INVALID")
    return text


def _confidence(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise IntakeGraphContractError("INTAKE_CONFIDENCE_INVALID")
    normalized = float(value)
    if not 0 <= normalized <= 1:
        raise IntakeGraphContractError("INTAKE_CONFIDENCE_INVALID")
    return normalized
