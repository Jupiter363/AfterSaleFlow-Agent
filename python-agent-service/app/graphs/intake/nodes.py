from __future__ import annotations

import hashlib
from collections.abc import Callable, Mapping
from copy import deepcopy
from typing import Any, TypeAlias, cast

from langgraph.runtime import Runtime

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.reducers import merge_node_results
from app.graphs.intake.baseline import (
    append_intake_baseline_statement,
    build_intake_baseline_memory_summary,
)
from app.graphs.intake.contracts import RESPONDENT_OPENING_MARKER
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import (
    IntakeGraphStateV2,
    IntakeMessageState,
    IntakeTurnContext,
    JsonObject,
    merge_intake_dossier,
    merge_intake_messages,
)
from app.graphs.intake.validators import (
    MATRIX_AUTHORITY_RECORD_KEY,
    bind_baseline_pending_case_detail,
    ingress,
    bootstrap_event_ingress,
    matrix_authority_record,
    require_respondent_opening_matrix_authority,
    validate_cognition_patch,
    validate_dossier_transition,
    validate_event,
    validate_node_patch,
    validate_proposal_binding,
    validate_snapshot,
    validate_state,
    validate_baseline_pending_promotion,
    validate_terminal_proposal,
)
from app.schemas import IntakeInitialCaseFacts


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
        "handoff_notes",
        "party_intake_state",
    }
)
_PARTY_INTAKE_MIRROR_BRANCHES = (
    "intake_quality",
    "missing_information",
    "handoff_notes",
    "admission",
)
_PARTY_INTAKE_ROLES = frozenset({"USER", "MERCHANT"})

_AUTHORIZED_INITIAL_CONTEXT_FIELDS = (
    "form_source",
    "form_description",
    "order_reference",
    "after_sales_reference",
    "logistics_reference",
    "initiator_role",
    "requested_outcome_hint",
    "claim_resolution_seed",
    "respondent_attitude_seed",
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
        return validate_node_patch(
            state,
            _apply_event(state, payload, allow_initial_form=False),
        )
    snapshot, event = bootstrap_event_ingress(runtime.context)
    snapshot_patch = _import_snapshot(state, snapshot)
    imported = _state_after_patch(state, snapshot_patch)
    event_patch = _apply_event(imported, event, allow_initial_form=True)
    return validate_node_patch(state, _merge_bootstrap_patches(snapshot_patch, event_patch))


def route_turn(state: IntakeGraphStateV2) -> dict[str, Any]:
    route = state.get("route")
    if route not in {"initialize", "message", "respondent_opening", "replay"}:
        raise IntakeGraphContractError("INTAKE_ROUTE_INVALID")
    return {}


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
        # This test-only continuity fallback is deliberately not a baseline
        # finalizer result.  Its explicit null sentinel prevents a pending
        # envelope from a failed prior command from being bound or promoted.
        "baseline_pending_case_detail": None,
        "terminal_draft": {
            "room_utterance": "已记录本轮接待信息，正在继续整理案情。",
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
    _validate_party_intake_patch_authority(state, patch)
    dossier = merge_intake_dossier(state["dossier_draft"], patch)
    previous_public = merge_intake_dossier(state["dossier_draft"], {})
    validate_dossier_transition(previous_public, dossier)
    return validate_node_patch(state, {"dossier_draft": dossier})


def _validate_party_intake_patch_authority(
    state: IntakeGraphStateV2,
    patch: Mapping[str, Any],
) -> None:
    party_state = patch.get("party_intake_state")
    if party_state is None:
        return
    actor = state["bindings"]["private"]["audience"]
    if (
        actor not in _PARTY_INTAKE_ROLES
        or not isinstance(party_state, Mapping)
        or set(party_state) != {"schema_version", "USER", "MERCHANT"}
        or any(branch not in patch for branch in _PARTY_INTAKE_MIRROR_BRANCHES)
    ):
        raise IntakeGraphContractError("INTAKE_DOSSIER_PARTY_STATE_UNAUTHORIZED")
    shared_mirror = {
        branch: patch[branch] for branch in _PARTY_INTAKE_MIRROR_BRANCHES
    }
    if shared_mirror != party_state.get(actor):
        raise IntakeGraphContractError("INTAKE_DOSSIER_PARTY_STATE_UNAUTHORIZED")

    previous = state["dossier_draft"].get("party_intake_state")
    if previous is None:
        return
    other = "MERCHANT" if actor == "USER" else "USER"
    if (
        not isinstance(previous, Mapping)
        or set(previous) != {"schema_version", "USER", "MERCHANT"}
        or party_state.get(other) != previous.get(other)
    ):
        raise IntakeGraphContractError("INTAKE_DOSSIER_PARTY_STATE_UNAUTHORIZED")


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
    if state.get("baseline_pending_case_detail") is None:
        # Deterministic test-only fallback has no finalized baseline snapshot.
        # It may still project a public proposal, but it cannot consume a
        # context generated by any other command or attempt.
        return validate_node_patch(state, {"terminal_draft": proposal})
    pending = bind_baseline_pending_case_detail(state, proposal=proposal)
    return validate_node_patch(
        state,
        {
            "terminal_draft": proposal,
            "baseline_pending_case_detail": pending,
        },
    )


def checkpoint_terminal(state: IntakeGraphStateV2) -> dict[str, Any]:
    if state.get("route") == "replay":
        # Replays must reuse the existing terminal result and never consume or
        # promote a pending context from a different execution attempt.
        return {}
    proposal = state.get("terminal_draft")
    if not isinstance(proposal, dict):
        raise IntakeGraphContractError("INTAKE_TERMINAL_DRAFT_MISSING")
    validate_terminal_proposal(proposal)
    validate_proposal_binding(state, proposal)
    patch: dict[str, Any] = {
        "result_json": deepcopy(proposal),
        "baseline_pending_case_detail": None,
    }
    if state.get("baseline_pending_case_detail") is None:
        # There is no finalized private snapshot to promote.  A fallback's new
        # public result cannot be allowed to coexist with a prior envelope
        # bound to another committed proposal, so clear both private slots.
        patch["baseline_previous_case_detail"] = None
        return validate_node_patch(state, patch)
    pending = validate_baseline_pending_promotion(state, proposal=proposal)
    previous = state.get("baseline_previous_case_detail")
    if previous is None or canonical_sha256(previous) != canonical_sha256(pending):
        # Promote only after the current terminal proposal has passed its own
        # matrix validation and every pending-envelope binding matches.  This
        # makes a failed or cross-command attempt impossible to promote later.
        patch["baseline_previous_case_detail"] = pending
    return validate_node_patch(state, patch)


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
        "memory_summary": _authorized_initial_context_summary(snapshot),
        "node_results": stable_records,
        "last_event_sequence": last_sequence,
        "dossier_draft": deepcopy(snapshot["current_dossier"]),
        "route": "initialize",
    }


def _authorized_initial_context_summary(snapshot: Mapping[str, Any]) -> str:
    """Persist only the bounded form fields that the model may reuse on later turns."""

    initial = snapshot.get("initial_case_facts")
    if not isinstance(initial, Mapping):
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_INITIAL_FACTS_INVALID")
    projected: dict[str, Any] = {}
    for field in _AUTHORIZED_INITIAL_CONTEXT_FIELDS:
        value = initial.get(field)
        if value is not None and value != "":
            projected[field] = deepcopy(value)
    if not projected:
        return ""
    try:
        validated = IntakeInitialCaseFacts.model_validate(projected)
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_SNAPSHOT_INITIAL_FACTS_INVALID") from error
    return build_intake_baseline_memory_summary(
        validated.model_dump(mode="json", exclude_none=True),
    )


def _apply_event(
    state: IntakeGraphStateV2,
    event: Mapping[str, Any],
    *,
    allow_initial_form: bool,
) -> dict[str, Any]:
    if state.get("initial_snapshot_hash") is None:
        raise IntakeGraphContractError("INTAKE_EVENT_BEFORE_SNAPSHOT")
    sequence = cast(int, event["sequence_no"])
    event_hash = cast(str, event["event_hash"])
    event_id = cast(str, event["event_id"])
    message_id = cast(str, event["message_id"])
    source_type = cast(str, event["source_type"])
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
    initial_form = source_type == "INITIAL_FORM"
    if initial_form and (
        not allow_initial_form or sequence != 1 or previous_sequence != 0 or bool(state["messages"])
    ):
        raise IntakeGraphContractError("INTAKE_INITIAL_FORM_EVENT_INVALID")
    respondent_opening = source_type == RESPONDENT_OPENING_MARKER
    if respondent_opening:
        if (
            not allow_initial_form
            or sequence != 1
            or previous_sequence != 0
            or bool(state["messages"])
            or event.get("text") != RESPONDENT_OPENING_MARKER
        ):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_EVENT_INVALID")
        require_respondent_opening_matrix_authority(state)

    event_record: JsonObject = {
        "kind": "EVENT",
        "stable_id": event_id,
        "content_hash": event_hash,
        "sequence": sequence,
        "message_id": message_id,
        "source_type": source_type,
        "source_refs": deepcopy(cast(list[str], event["source_refs"])),
    }
    source_record: JsonObject = {
        "kind": (
            "INITIAL_FORM_SOURCE"
            if initial_form
            else "RESPONDENT_OPENING_SOURCE"
            if respondent_opening
            else "MESSAGE"
        ),
        "stable_id": message_id,
        "content_hash": event_hash,
        "sequence": sequence,
        "source_type": source_type,
    }
    patch: dict[str, Any] = {
        "last_event_ref": event_id,
        "last_event_hash": event_hash,
        "last_event_sequence": sequence,
        "node_results": {
            _stable_record_key("event", event_id): event_record,
            _stable_record_key("message", message_id): source_record,
        },
        "route": "respondent_opening" if respondent_opening else "message",
    }
    if respondent_opening:
        event_record["control_marker"] = RESPONDENT_OPENING_MARKER
        source_record["control_marker"] = RESPONDENT_OPENING_MARKER
    if initial_form:
        # The form is already represented by the bounded authorized_initial_case_facts
        # summary.  Keeping its cursor/source receipts without inserting a HUMAN message
        # preserves replay and provenance while matching the baseline first-turn contract.
        return patch
    if respondent_opening:
        # This server-owned control event opens the respondent's private room; it
        # is provenance for the deterministic M0 carry, never a party statement.
        return patch

    # Match the legacy RoomTurnMemory query: participant answers are retained
    # in full and ordered by their turn cursor, independently of the bounded
    # six-message dialogue window used by the prompt.
    patch["memory_summary"] = append_intake_baseline_statement(
        state["memory_summary"],
        turn_no=sequence,
        actor_role=cast(str, event["audience"]),
        text=cast(str, event["text"]),
    )

    message: IntakeMessageState = {
        "message_id": message_id,
        "role": "HUMAN",
        "audience": cast(Any, event["audience"]),
        "content": cast(str, event["text"]),
        "sequence": sequence,
        "source_hash": event_hash,
    }
    patch["messages"] = {message["message_id"]: message}
    return patch


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
    for field, reducer in (
        ("messages", merge_intake_messages),
        ("node_results", merge_node_results),
    ):
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
