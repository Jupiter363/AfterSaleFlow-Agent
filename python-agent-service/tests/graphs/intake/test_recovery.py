from __future__ import annotations

import copy
from collections.abc import Callable

from langgraph.checkpoint.memory import InMemorySaver

from app.graphs.intake.graph import (
    _create_test_only_intake_cognition,
    build_intake_v2_graph,
)
from app.graphs.intake.nodes import deterministic_message_fallback
from app.graphs.intake.state import IntakeTurnContext, new_intake_graph_state


def _compiled_graph(checkpointer: InMemorySaver, cognition: Callable):
    return build_intake_v2_graph(intake_lcel=_create_test_only_intake_cognition(cognition)).compile(
        checkpointer=checkpointer
    )


def _config(thread_id: str) -> dict:
    return {"configurable": {"thread_id": thread_id}}


def _initial_state(bindings, version_pins):
    return new_intake_graph_state(bindings=bindings, version_pins=version_pins)


def _initialize_thread(graph, config, bindings, version_pins, snapshot) -> None:
    graph.invoke(
        _initial_state(bindings, version_pins),
        config,
        context=IntakeTurnContext("SNAPSHOT", snapshot),
    )


def _next_command(bindings) -> dict:
    next_bindings = copy.deepcopy(bindings)
    next_bindings["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    return {"bindings": next_bindings}


def test_resume_from_pre_model_checkpoint_invokes_cognition_once(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    calls = 0

    def cognition(state, runtime):
        nonlocal calls
        calls += 1
        return deterministic_message_fallback(state, runtime)

    config = _config("intake-recovery-before-model")
    graph = _compiled_graph(saver, cognition)
    _initialize_thread(graph, config, bindings, version_pins, snapshot)
    context = IntakeTurnContext("EVENT", event)

    interrupted = graph.invoke(
        _next_command(bindings),
        config,
        context=context,
        interrupt_before=["intake_lcel"],
    )

    assert calls == 0
    assert interrupted["route"] == "message"
    assert graph.get_state(config).next == ("intake_lcel",)

    replacement = _compiled_graph(saver, cognition)
    recovered = replacement.invoke(None, config, context=context)

    assert calls == 1
    assert replacement.get_state(config).next == ()
    assert recovered["result_json"]["cognitive_revision"] == 2


def test_replacement_graph_resumes_after_model_without_a_second_invocation(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    calls = 0

    def cognition(state, runtime):
        nonlocal calls
        calls += 1
        return deterministic_message_fallback(state, runtime)

    config = _config("intake-recovery-after-model")
    graph = _compiled_graph(saver, cognition)
    _initialize_thread(graph, config, bindings, version_pins, snapshot)
    context = IntakeTurnContext("EVENT", event)
    interrupted = graph.invoke(
        _next_command(bindings),
        config,
        context=context,
        interrupt_after=["intake_lcel"],
    )

    assert calls == 1
    assert interrupted["cognitive_revision"] == 2
    assert graph.get_state(config).next == ("apply_dossier_patch",)

    def repeated_model_call_is_a_failure(state, runtime):
        del state, runtime
        raise AssertionError("a committed cognition checkpoint must not call the model again")

    replacement = _compiled_graph(saver, repeated_model_call_is_a_failure)
    recovered = replacement.invoke(None, config, context=context)

    assert calls == 1
    assert replacement.get_state(config).next == ()
    assert recovered["result_json"]["proposal_hash"]


def test_projected_proposal_survives_a_crash_before_terminal_checkpoint(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    config = _config("intake-recovery-before-terminal-checkpoint")
    graph = _compiled_graph(saver, deterministic_message_fallback)
    _initialize_thread(graph, config, bindings, version_pins, snapshot)
    context = IntakeTurnContext("EVENT", event)
    interrupted = graph.invoke(
        _next_command(bindings),
        config,
        context=context,
        interrupt_before=["checkpoint_terminal"],
    )
    projected = interrupted["terminal_draft"]

    assert interrupted["result_json"]["command_id"] == "COMMAND_P4_USER_1"
    assert projected["command_id"] == "COMMAND_P4_USER_2"
    assert graph.get_state(config).next == ("checkpoint_terminal",)

    replacement = _compiled_graph(saver, deterministic_message_fallback)
    recovered = replacement.invoke(None, config, context=context)

    assert recovered["result_json"] == projected
    assert replacement.get_state(config).next == ()


def test_terminal_checkpoint_is_reused_after_response_loss(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    calls = 0

    def cognition(state, runtime):
        nonlocal calls
        calls += 1
        return deterministic_message_fallback(state, runtime)

    config = _config("intake-recovery-after-terminal-checkpoint")
    graph = _compiled_graph(saver, cognition)
    _initialize_thread(graph, config, bindings, version_pins, snapshot)
    context = IntakeTurnContext("EVENT", event)
    committed = graph.invoke(
        _next_command(bindings),
        config,
        context=context,
        interrupt_after=["checkpoint_terminal"],
    )

    assert calls == 1
    assert committed["result_json"] == committed["terminal_draft"]

    def repeated_model_call_is_a_failure(state, runtime):
        del state, runtime
        raise AssertionError("response recovery must reuse the terminal checkpoint")

    replacement = _compiled_graph(saver, repeated_model_call_is_a_failure)
    recovered = replacement.invoke(None, config, context=context)

    assert calls == 1
    assert recovered["result_json"] == committed["result_json"]
    assert replacement.get_state(config).next == ()
