from __future__ import annotations

import copy
import json
from pathlib import Path

import jsonschema
import pytest

from app.contracts.v1.codec import canonical_sha256_omitting
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.graph import build_intake_v2_graph, compile_intake_v2_graph
from app.graphs.intake.state import IntakeTurnContext, new_intake_graph_state


ROOT = Path(__file__).resolve().parents[4]
SCHEMA = json.loads(
    (
        ROOT
        / "contracts"
        / "agent-platform"
        / "intake"
        / "v2"
        / "intake-turn-proposal.schema.json"
    ).read_text(encoding="utf-8")
)


def _run_snapshot(bindings, version_pins, snapshot):
    graph = compile_intake_v2_graph()
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    return graph, graph.invoke(
        state,
        context=IntakeTurnContext("SNAPSHOT", snapshot),
    )


def test_topology_is_fixed_and_exhaustive() -> None:
    graph = build_intake_v2_graph()
    assert set(graph.nodes) == {
        "authorize_and_load",
        "import_snapshot_once_or_apply_event",
        "route_turn",
        "deterministic_seed",
        "intake_lcel",
        "cached_terminal_projection",
        "apply_dossier_patch",
        "validate_readiness",
        "project_intake_proposal",
        "checkpoint_terminal",
    }


def test_snapshot_import_produces_schema_valid_proposal(
    bindings,
    version_pins,
    snapshot,
) -> None:
    snapshot["initial_case_facts"]["private_loader_marker"] = "NOT_CHECKPOINTED"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")
    _, result = _run_snapshot(bindings, version_pins, snapshot)

    assert result["route"] == "initialize"
    assert result["initial_snapshot_hash"] == snapshot["snapshot_hash"]
    assert result["cognitive_revision"] == 1
    assert len(result["messages"]) == 1
    assert "memory_frame" not in repr(result)
    assert "NOT_CHECKPOINTED" not in repr(result)
    jsonschema.Draft202012Validator(SCHEMA).validate(result["result_json"])


def test_event_applies_once_and_uses_injected_cognition(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    def cognition(state, runtime):
        del runtime
        return {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": {
                "room_utterance": "Please confirm the requested resolution.",
                "dossier_patch": {"requested_resolution": {"kind": "REFUND"}},
                "matrix_patch": None,
                "readiness": "READY_TO_CONFIRM",
                "missing_fields": [],
                "recommendation": "ACCEPTED",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.9,
            },
        }

    graph = compile_intake_v2_graph(intake_lcel=cognition)
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    state = graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", snapshot))
    next_bindings = dict(bindings)
    next_bindings.update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state["bindings"] = next_bindings

    result = graph.invoke(state, context=IntakeTurnContext("EVENT", event))

    assert result["route"] == "message"
    assert result["last_event_sequence"] == 2
    assert result["last_event_hash"] == event["event_hash"]
    assert result["readiness"]["status"] == "READY_TO_CONFIRM"
    assert result["dossier_draft"]["requested_resolution"] == {"kind": "REFUND"}
    jsonschema.Draft202012Validator(SCHEMA).validate(result["result_json"])


def test_identical_snapshot_replay_returns_cached_terminal_without_revision_change(
    bindings,
    version_pins,
    snapshot,
) -> None:
    graph, first = _run_snapshot(bindings, version_pins, snapshot)
    replay = graph.invoke(
        first,
        context=IntakeTurnContext("SNAPSHOT", copy.deepcopy(snapshot)),
    )

    assert replay["route"] == "replay"
    assert replay["cognitive_revision"] == first["cognitive_revision"]
    assert replay["result_json"] == first["result_json"]


def test_snapshot_reimport_with_another_hash_fails_closed(
    bindings,
    version_pins,
    snapshot,
) -> None:
    graph, first = _run_snapshot(bindings, version_pins, snapshot)
    changed = copy.deepcopy(snapshot)
    changed["current_dossier"]["case_story"] = {"summary": "different"}
    changed["snapshot_hash"] = canonical_sha256_omitting(changed, "snapshot_hash")
    changed_bindings = dict(first["bindings"])
    changed_bindings["command_id"] = "COMMAND_P4_USER_CONFLICT"
    first["bindings"] = changed_bindings

    with pytest.raises(IntakeGraphContractError, match="INTAKE_SNAPSHOT_REIMPORT_CONFLICT"):
        graph.invoke(first, context=IntakeTurnContext("SNAPSHOT", changed))


def test_event_before_snapshot_fails_closed(bindings, version_pins, event) -> None:
    graph = compile_intake_v2_graph()
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    with pytest.raises(IntakeGraphContractError, match="INTAKE_EVENT_BEFORE_SNAPSHOT"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", event))


def test_identical_event_replay_uses_cached_proposal(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    next_bindings = dict(bindings)
    next_bindings.update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state["bindings"] = next_bindings
    first = graph.invoke(state, context=IntakeTurnContext("EVENT", event))
    replay = graph.invoke(
        first,
        context=IntakeTurnContext("EVENT", copy.deepcopy(event)),
    )

    assert replay["route"] == "replay"
    assert replay["cognitive_revision"] == first["cognitive_revision"]
    assert replay["result_json"] == first["result_json"]
