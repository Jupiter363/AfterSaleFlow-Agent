from __future__ import annotations

import pytest

from app.graph_runtime.reducers import KeyedReducerConflict
from app.graphs.intake.state import IntakeMessageState, merge_intake_messages


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
    assert merge_intake_messages({message["message_id"]: message}, {message["message_id"]: message}) == {
        message["message_id"]: message
    }


def test_message_reducer_rejects_stable_id_rebinding() -> None:
    message = _message(1)
    changed = _message(1, text="changed")
    with pytest.raises(KeyedReducerConflict):
        merge_intake_messages(
            {message["message_id"]: message},
            {changed["message_id"]: changed},
        )
