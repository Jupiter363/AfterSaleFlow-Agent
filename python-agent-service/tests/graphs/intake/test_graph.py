from __future__ import annotations

import copy
import json
from pathlib import Path

import jsonschema
import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.contracts.v1.codec import canonical_sha256_omitting
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.graph import (
    _create_test_only_intake_cognition,
    build_intake_v2_graph,
    compile_intake_v2_graph,
)
from app.graphs.intake.nodes import deterministic_message_fallback
from app.graphs.intake.state import IntakeTurnContext, new_intake_graph_state


ROOT = Path(__file__).resolve().parents[4]
SCHEMA = json.loads(
    (
        ROOT / "contracts" / "agent-platform" / "intake" / "v2" / "intake-turn-proposal.schema.json"
    ).read_text(encoding="utf-8")
)


def _run_snapshot(bindings, version_pins, snapshot):
    graph = compile_intake_v2_graph(
        intake_lcel=_create_test_only_intake_cognition(deterministic_message_fallback)
    )
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
        "intake_lcel",
        "cached_terminal_projection",
        "apply_dossier_patch",
        "validate_readiness",
        "project_intake_proposal",
        "checkpoint_terminal",
    }


def test_persisted_state_accepts_command_delta_but_rejects_private_drift(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph = build_intake_v2_graph(
        intake_lcel=_create_test_only_intake_cognition(deterministic_message_fallback)
    ).compile(checkpointer=InMemorySaver())
    config = {"configurable": {"thread_id": "intake-private-binding-test"}}
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    first = graph.invoke(
        state,
        config,
        context=IntakeTurnContext("SNAPSHOT", snapshot),
    )
    next_bindings = copy.deepcopy(first["bindings"])
    next_bindings["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    second = graph.invoke(
        {"bindings": next_bindings},
        config,
        context=IntakeTurnContext("EVENT", event),
    )

    assert second["result_json"]["command_id"] == "COMMAND_P4_USER_2"
    drifted = copy.deepcopy(next_bindings)
    drifted["private"]["case_id"] = "CASE_OTHER"
    drifted["command"]["command_id"] = "COMMAND_P4_USER_3"
    with pytest.raises(IntakeGraphContractError, match="INTAKE_PRIVATE_BINDING_IMMUTABLE"):
        graph.invoke(
            {"bindings": drifted},
            config,
            context=IntakeTurnContext("EVENT", event),
        )


def test_snapshot_import_produces_schema_valid_proposal(
    bindings,
    version_pins,
    snapshot,
) -> None:
    snapshot["initial_case_facts"]["private_loader_marker"] = "NOT_CHECKPOINTED"
    snapshot["initial_case_facts"]["order_reference"] = "ORDER_CURRENT_CASE_1"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")
    _, result = _run_snapshot(bindings, version_pins, snapshot)

    assert result["route"] == "initialize"
    assert result["initial_snapshot_hash"] == snapshot["snapshot_hash"]
    assert result["cognitive_revision"] == 1
    assert len(result["messages"]) == 1
    assert "memory_frame" not in repr(result)
    assert "NOT_CHECKPOINTED" not in repr(result)
    initial_context = json.loads(result["memory_summary"])["authorized_initial_case_facts"]
    assert initial_context["order_reference"] == "ORDER_CURRENT_CASE_1"
    assert initial_context["form_description"] == "Synthetic order arrived damaged."
    assert "private_loader_marker" not in initial_context
    assert result["result_json"]["room_utterance"] == "已记录本轮接待信息，正在继续整理案情。"
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

    graph = compile_intake_v2_graph(intake_lcel=_create_test_only_intake_cognition(cognition))
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    state = graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", snapshot))
    next_bindings = copy.deepcopy(bindings)
    next_bindings["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state["bindings"] = next_bindings

    result = graph.invoke(state, context=IntakeTurnContext("EVENT", event))

    assert result["route"] == "message"
    assert result["last_event_sequence"] == 2
    assert result["last_event_hash"] == event["event_hash"]
    assert result["messages"][event["message_id"]]["role"] == "HUMAN"
    assert event["source_type"] == "ROOM_MESSAGE"
    assert result["readiness"]["status"] == "READY_TO_CONFIRM"
    assert result["dossier_draft"]["requested_resolution"] == {"kind": "REFUND"}
    jsonschema.Draft202012Validator(SCHEMA).validate(result["result_json"])


def test_participant_transcript_stays_complete_after_the_dialogue_window_rolls(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    expected: list[tuple[str, str]] = []

    for sequence in range(2, 10):
        current = copy.deepcopy(event)
        message_id = f"MESSAGE_P4_USER_{sequence}"
        text = f"Participant answer {sequence}."
        current.update(
            event_id=f"EVENT_P4_USER_{sequence}",
            message_id=message_id,
            sequence_no=sequence,
            domain_revision=sequence + 3,
            text=text,
            source_refs=[message_id],
        )
        current["event_hash"] = canonical_sha256_omitting(current, "event_hash")
        state = graph.invoke(state, context=IntakeTurnContext("EVENT", current))
        expected.append((f"INTAKE_TURN_{sequence}", text))

    transcript = json.loads(state["memory_summary"])[
        "initiator_statement_transcript"
    ]
    assert [(item["message_id"], item["text"]) for item in transcript] == expected
    assert {item["role"] for item in transcript} == {"USER"}
    assert snapshot["initial_case_facts"]["form_description"] not in {
        item["text"] for item in transcript
    }
    assert len(state["messages"]) == 6


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
    changed_bindings = copy.deepcopy(first["bindings"])
    changed_bindings["command"]["command_id"] = "COMMAND_P4_USER_CONFLICT"
    first["bindings"] = changed_bindings

    with pytest.raises(IntakeGraphContractError, match="INTAKE_SNAPSHOT_REIMPORT_CONFLICT"):
        graph.invoke(first, context=IntakeTurnContext("SNAPSHOT", changed))


def test_event_before_snapshot_fails_closed(bindings, version_pins, event) -> None:
    graph = compile_intake_v2_graph()
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    with pytest.raises(IntakeGraphContractError, match="INTAKE_EVENT_BEFORE_SNAPSHOT"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", event))


def test_bootstrap_event_imports_snapshot_before_first_event(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    """A fresh command may carry both exact ingress objects without weakening EVENT alone."""
    snapshot["own_messages"] = []
    snapshot["source_refs"] = ["SNAPSHOT_SOURCE_P4"]
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")
    event["sequence_no"] = 1
    event["source_type"] = "INITIAL_FORM"
    event["event_hash"] = canonical_sha256_omitting(event, "event_hash")
    graph = compile_intake_v2_graph(
        intake_lcel=_create_test_only_intake_cognition(deterministic_message_fallback)
    )
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)

    result = graph.invoke(
        state,
        context=IntakeTurnContext(
            "BOOTSTRAP_EVENT",
            {"snapshot": snapshot, "event": event},
        ),
    )

    assert result["initial_snapshot_hash"] == snapshot["snapshot_hash"]
    assert result["last_event_hash"] == event["event_hash"]
    assert result["last_event_sequence"] == 1
    assert result["messages"] == {}
    assert event["text"] not in result["memory_summary"]
    assert "authorized_initial_case_facts" in result["memory_summary"]
    source_records = [
        record
        for record in result["node_results"].values()
        if record.get("kind") == "INITIAL_FORM_SOURCE"
    ]
    assert source_records == [
        {
            "kind": "INITIAL_FORM_SOURCE",
            "stable_id": event["message_id"],
            "content_hash": event["event_hash"],
            "sequence": 1,
            "source_type": "INITIAL_FORM",
        }
    ]
    assert result["route"] == "message"


def test_initial_form_is_rejected_outside_the_fresh_bootstrap_contract(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    snapshot["own_messages"] = []
    snapshot["source_refs"] = ["SNAPSHOT_SOURCE_P4"]
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")
    event["sequence_no"] = 1
    event["source_type"] = "INITIAL_FORM"
    event["event_hash"] = canonical_sha256_omitting(event, "event_hash")
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_INITIAL_FORM_INVALID",
        logical_run_id="RUN_P4_INITIAL_FORM_INVALID",
        attempt_id="ATTEMPT_P4_INITIAL_FORM_INVALID_1",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_INITIAL_FORM_EVENT_INVALID"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", event))


def test_identical_event_replay_uses_cached_proposal(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    next_bindings = copy.deepcopy(bindings)
    next_bindings["command"].update(
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


def test_snapshot_initialization_fails_closed_until_governed_lcel_is_bound(
    bindings,
    version_pins,
    snapshot,
) -> None:
    graph = compile_intake_v2_graph()
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)

    with pytest.raises(IntakeGraphContractError, match="INTAKE_LCEL_NOT_CONFIGURED"):
        graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", snapshot))


def test_event_replay_requires_the_exact_cached_command_binding(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state = graph.invoke(state, context=IntakeTurnContext("EVENT", event))
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_REBOUND",
        logical_run_id="RUN_P4_USER_REBOUND",
        attempt_id="ATTEMPT_P4_USER_REBOUND_1",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_PROPOSAL_BINDING_MISMATCH"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", copy.deepcopy(event)))


def test_stable_event_id_cannot_be_rebound_to_another_hash(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state = graph.invoke(state, context=IntakeTurnContext("EVENT", event))
    changed = copy.deepcopy(event)
    changed.update(
        sequence_no=3,
        message_id="MESSAGE_P4_USER_3",
        text="Conflicting event payload.",
        source_refs=["MESSAGE_P4_USER_3"],
    )
    changed["event_hash"] = canonical_sha256_omitting(changed, "event_hash")
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_3",
        logical_run_id="RUN_P4_USER_3",
        attempt_id="ATTEMPT_P4_USER_3_1",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_STABLE_ID_REBINDING"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", changed))


def test_historical_event_id_remains_hash_bound(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state = graph.invoke(state, context=IntakeTurnContext("EVENT", event))
    next_event = copy.deepcopy(event)
    next_event.update(
        event_id="EVENT_P4_USER_3",
        message_id="MESSAGE_P4_USER_3",
        sequence_no=3,
        text="A later accepted event.",
        source_refs=["MESSAGE_P4_USER_3"],
    )
    next_event["event_hash"] = canonical_sha256_omitting(next_event, "event_hash")
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_3",
        logical_run_id="RUN_P4_USER_3",
        attempt_id="ATTEMPT_P4_USER_3_1",
    )
    state = graph.invoke(state, context=IntakeTurnContext("EVENT", next_event))
    rebound = copy.deepcopy(event)
    rebound.update(
        sequence_no=4,
        message_id="MESSAGE_P4_USER_4",
        text="Rebound historical event ID.",
        source_refs=["MESSAGE_P4_USER_4"],
    )
    rebound["event_hash"] = canonical_sha256_omitting(rebound, "event_hash")
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_4",
        logical_run_id="RUN_P4_USER_4",
        attempt_id="ATTEMPT_P4_USER_4_1",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_STABLE_ID_REBINDING"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", rebound))


def test_message_id_remains_hash_bound_after_leaving_six_message_window(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    for sequence in range(2, 8):
        next_event = copy.deepcopy(event)
        next_event.update(
            event_id=f"EVENT_P4_USER_{sequence}",
            message_id=f"MESSAGE_P4_USER_{sequence}",
            sequence_no=sequence,
            text=f"Accepted event {sequence}.",
            source_refs=[f"MESSAGE_P4_USER_{sequence}"],
        )
        next_event["event_hash"] = canonical_sha256_omitting(next_event, "event_hash")
        state["bindings"]["command"].update(
            command_id=f"COMMAND_P4_USER_{sequence}",
            logical_run_id=f"RUN_P4_USER_{sequence}",
            attempt_id=f"ATTEMPT_P4_USER_{sequence}_1",
        )
        state = graph.invoke(state, context=IntakeTurnContext("EVENT", next_event))

    assert "MESSAGE_P4_USER_1" not in state["messages"]
    rebound = copy.deepcopy(event)
    rebound.update(
        event_id="EVENT_P4_USER_8",
        message_id="MESSAGE_P4_USER_1",
        sequence_no=8,
        text="Rebound evicted message ID.",
        source_refs=["MESSAGE_P4_USER_1"],
    )
    rebound["event_hash"] = canonical_sha256_omitting(rebound, "event_hash")
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_8",
        logical_run_id="RUN_P4_USER_8",
        attempt_id="ATTEMPT_P4_USER_8_1",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_STABLE_ID_REBINDING"):
        graph.invoke(state, context=IntakeTurnContext("EVENT", rebound))


def test_snapshot_cannot_replay_the_latest_event_proposal(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    graph, state = _run_snapshot(bindings, version_pins, snapshot)
    state["bindings"]["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    state = graph.invoke(state, context=IntakeTurnContext("EVENT", event))

    with pytest.raises(IntakeGraphContractError, match="INTAKE_REPLAY_SOURCE_MISMATCH"):
        graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", copy.deepcopy(snapshot)))


def test_cognition_cannot_overwrite_authority_state(
    bindings,
    version_pins,
    snapshot,
) -> None:
    def cognition(state, runtime):
        del runtime
        return {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": {
                "room_utterance": "Attempted authority mutation.",
                "dossier_patch": {},
                "matrix_patch": None,
                "readiness": "INCOMPLETE",
                "missing_fields": [],
                "recommendation": "NEED_MORE_INFO",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.0,
            },
            "bindings": copy.deepcopy(state["bindings"]),
        }

    graph = compile_intake_v2_graph(intake_lcel=_create_test_only_intake_cognition(cognition))
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)

    with pytest.raises(IntakeGraphContractError, match="INTAKE_COGNITION_PATCH_FIELDS_INVALID"):
        graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", snapshot))


def test_cognition_draft_is_rejected_before_an_oversized_state_patch(
    bindings,
    version_pins,
    snapshot,
) -> None:
    def cognition(state, runtime):
        del runtime
        return {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": {
                "room_utterance": "Bounded response.",
                "dossier_patch": {"case_story": {"bulk": ["x" * 1000] * 70}},
                "matrix_patch": None,
                "readiness": "INCOMPLETE",
                "missing_fields": [],
                "recommendation": "NEED_MORE_INFO",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.0,
            },
        }

    graph = compile_intake_v2_graph(intake_lcel=_create_test_only_intake_cognition(cognition))
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)

    with pytest.raises(IntakeGraphContractError, match="INTAKE_COGNITION_DRAFT_TOO_LARGE"):
        graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", snapshot))


@pytest.mark.parametrize(
    "matrix_patch",
    [
        {"fact_rows": [], "source_refs": []},
        {
            "fact_rows": [
                {
                    "fact_id": "FACT_DAMAGE",
                    "category": "PRODUCT",
                    "fact_target": "A rebound fact target.",
                }
            ],
            "source_refs": ["MESSAGE_P4_USER_1"],
        },
    ],
)
def test_dossier_patch_cannot_bypass_the_dedicated_matrix_patch(
    bindings,
    version_pins,
    snapshot,
    matrix_patch,
) -> None:
    snapshot["current_dossier"]["case_fact_matrix"] = {
        "fact_rows": [
            {
                "fact_id": "FACT_DAMAGE",
                "category": "PRODUCT",
                "fact_target": "The product arrived damaged.",
            }
        ],
        "source_refs": ["MESSAGE_P4_USER_1"],
    }
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    def cognition(state, runtime):
        del runtime
        return {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": {
                "room_utterance": "Structured update.",
                "dossier_patch": {"case_fact_matrix": matrix_patch},
                "matrix_patch": None,
                "readiness": "INCOMPLETE",
                "missing_fields": [],
                "recommendation": "NEED_MORE_INFO",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.0,
            },
        }

    graph = compile_intake_v2_graph(intake_lcel=_create_test_only_intake_cognition(cognition))
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)

    with pytest.raises(IntakeGraphContractError, match="INTAKE_COGNITION_DRAFT_INVALID"):
        graph.invoke(state, context=IntakeTurnContext("SNAPSHOT", snapshot))
