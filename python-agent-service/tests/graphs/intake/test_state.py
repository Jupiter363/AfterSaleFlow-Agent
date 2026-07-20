from __future__ import annotations

import copy

import pytest

from app.graph_runtime.reducers import KeyedReducerConflict
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import (
    IntakeMessageState,
    merge_intake_bindings,
    merge_intake_messages,
    merge_intake_version_pins,
)


def _message(sequence: int, *, text: str | None = None) -> IntakeMessageState:
    return {
        "message_id": f"MESSAGE_{sequence}",
        "role": "HUMAN",
        "audience": "USER",
        "content": text or f"message {sequence}",
        "sequence": sequence,
        "source_hash": f"{sequence % 10}" * 64,
    }


def test_message_reducer_keeps_the_latest_six_in_sequence_order() -> None:
    merged: dict[str, IntakeMessageState] = {}
    for sequence in range(1, 9):
        message = _message(sequence)
        merged = merge_intake_messages(merged, {message["message_id"]: message})

    assert list(merged) == [f"MESSAGE_{sequence}" for sequence in range(3, 9)]


def test_message_reducer_replays_identical_stable_id() -> None:
    message = _message(1)
    assert merge_intake_messages(
        {message["message_id"]: message}, {message["message_id"]: message}
    ) == {message["message_id"]: message}


def test_message_reducer_rejects_stable_id_rebinding() -> None:
    message = _message(1)
    changed = _message(1, text="changed")
    with pytest.raises(KeyedReducerConflict):
        merge_intake_messages(
            {message["message_id"]: message},
            {changed["message_id"]: changed},
        )


def test_binding_reducer_allows_only_per_command_replacement(bindings) -> None:
    next_bindings = copy.deepcopy(bindings)
    next_bindings["command"] = {
        "schema_version": "intake-command-binding.v1",
        "command_id": "COMMAND_P4_USER_2",
        "logical_run_id": "RUN_P4_USER_2",
        "attempt_id": "ATTEMPT_P4_USER_2_1",
    }

    merged = merge_intake_bindings(bindings, next_bindings)

    assert merged["private"] == bindings["private"]
    assert merged["command"] == next_bindings["command"]


@pytest.mark.parametrize(
    ("field", "replacement"),
    [
        ("tenant_surrogate", "tenant-other"),
        ("case_id", "CASE_OTHER"),
        ("room_epoch", 2),
        ("thread_id", "grt.v1.11111111111111111111111111111111"),
        ("actor_scope_hash", "2" * 64),
        ("agent_session_id", "AGENT_SESSION_OTHER"),
        ("audience", "MERCHANT"),
    ],
)
def test_binding_reducer_rejects_private_identity_drift(bindings, field, replacement) -> None:
    changed = copy.deepcopy(bindings)
    changed["private"][field] = replacement

    with pytest.raises(IntakeGraphContractError, match="INTAKE_PRIVATE_BINDING_IMMUTABLE"):
        merge_intake_bindings(bindings, changed)


def test_version_pin_reducer_rejects_profile_drift(version_pins) -> None:
    changed = dict(version_pins)
    changed["model_profile_id"] = "intake-model.other.v1"

    with pytest.raises(IntakeGraphContractError, match="INTAKE_VERSION_PINS_IMMUTABLE"):
        merge_intake_version_pins(version_pins, changed)
