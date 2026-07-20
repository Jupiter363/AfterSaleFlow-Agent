from __future__ import annotations

from collections.abc import Callable, Mapping
from copy import deepcopy
from typing import Any, TypeAlias, cast

from langgraph.runtime import Runtime

from app.contracts.v1.codec import canonical_sha256
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import (
    IntakeGraphStateV2,
    IntakeMessageState,
    IntakeTurnContext,
    JsonObject,
)
from app.graphs.intake.validators import (
    ingress,
    validate_event,
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
        "case_fact_matrix",
        "unilateral_case_matrix",
    }
)


def authorize_and_load(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    validate_state(state)
    kind, payload = ingress(runtime.context)
    if kind == "SNAPSHOT":
        validate_snapshot(state, payload)
        source_id = cast(str, payload["snapshot_id"])
        source_hash = cast(str, payload["snapshot_hash"])
    else:
        validate_event(state, payload)
        source_id = cast(str, payload["event_id"])
        source_hash = cast(str, payload["event_hash"])
    return {
        "node_results": {
            f"authorized:{state['bindings']['command_id']}": {
                "kind": kind,
                "source_id": source_id,
                "source_hash": source_hash,
            }
        }
    }


def import_snapshot_once_or_apply_event(
    state: IntakeGraphStateV2,
    runtime: Runtime[IntakeTurnContext],
) -> dict[str, Any]:
    kind, payload = ingress(runtime.context)
    if kind == "SNAPSHOT":
        return _import_snapshot(state, payload)
    return _apply_event(state, payload)


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
    return {
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
    }


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
    del runtime
    cached = state.get("result_json")
    if not isinstance(cached, dict):
        raise IntakeGraphContractError("INTAKE_REPLAY_RESULT_MISSING")
    return {"terminal_draft": deepcopy(cached)}


def apply_dossier_patch(state: IntakeGraphStateV2) -> dict[str, Any]:
    if state.get("route") == "replay":
        return {}
    draft = _turn_draft(state)
    patch = draft.get("dossier_patch", {})
    if not isinstance(patch, dict) or not set(patch) <= _DOSSIER_BRANCHES:
        raise IntakeGraphContractError("INTAKE_DOSSIER_PATCH_INVALID")
    dossier = _merge_object(state["dossier_draft"], patch)
    return {"dossier_draft": dossier}


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
    if not isinstance(missing, list) or not all(
        isinstance(item, str) and item for item in missing
    ):
        raise IntakeGraphContractError("INTAKE_MISSING_FIELDS_INVALID")
    normalized_missing = sorted(set(missing))
    if readiness == "READY_TO_CONFIRM" and normalized_missing:
        raise IntakeGraphContractError("INTAKE_READY_WITH_MISSING_FIELDS")
    return {
        "readiness": {
            "status": readiness,
            "evaluated_revision": state["cognitive_revision"],
        },
        "missing_fields": normalized_missing,
        "recommendation": recommendation,
    }


def project_intake_proposal(state: IntakeGraphStateV2) -> dict[str, Any]:
    if state.get("route") == "replay":
        return {}
    draft = _turn_draft(state)
    bindings = state["bindings"]
    pins = state["version_pins"]
    proposal: JsonObject = {
        "schema_version": "intake-turn-proposal.v2",
        "command_id": bindings["command_id"],
        "logical_run_id": bindings["logical_run_id"],
        "attempt_id": bindings["attempt_id"],
        "case_id": bindings["case_id"],
        "room_epoch": bindings["room_epoch"],
        "thread_id": bindings["thread_id"],
        "actor_scope_hash": bindings["actor_scope_hash"],
        "agent_session_id": bindings["agent_session_id"],
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
    return {"terminal_draft": proposal}


def checkpoint_terminal(state: IntakeGraphStateV2) -> dict[str, Any]:
    proposal = state.get("terminal_draft")
    if not isinstance(proposal, dict):
        raise IntakeGraphContractError("INTAKE_TERMINAL_DRAFT_MISSING")
    validate_terminal_proposal(proposal)
    return {"result_json": deepcopy(proposal)}


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
    last_sequence = max(
        (message["sequence"] for message in messages.values()),
        default=0,
    )
    return {
        "initial_snapshot_ref": snapshot["snapshot_id"],
        "initial_snapshot_hash": snapshot_hash,
        "initial_domain_revision": snapshot["domain_revision"],
        "messages": messages,
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
    previous_sequence = state.get("last_event_sequence", 0)
    if sequence == previous_sequence and event_hash == state.get("last_event_hash"):
        return {"route": "replay"}
    if sequence == previous_sequence:
        raise IntakeGraphContractError("INTAKE_EVENT_REPLAY_CONFLICT")
    if sequence != previous_sequence + 1:
        raise IntakeGraphContractError("INTAKE_EVENT_SEQUENCE_INVALID")
    message: IntakeMessageState = {
        "message_id": cast(str, event["message_id"]),
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
        "route": "message",
    }


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
