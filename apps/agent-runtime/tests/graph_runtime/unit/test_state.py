from __future__ import annotations

import pytest

from app.graph_runtime.state import (
    GraphStateLimitError,
    GraphStateLimits,
    validate_graph_patch,
    validate_graph_state,
)


def minimal_state() -> dict[str, object]:
    return {
        "bindings": {"schema_version": "graph-command-binding.v1"},
        "version_pins": {"schema_version": "graph-version-pins.v1"},
        "cognitive_revision": 0,
        "messages": {},
        "work_items": {},
        "work_results": {},
        "artifact_refs": {},
        "node_results": {},
        "execution_receipts": {},
        "usage_by_invocation": {},
    }


def test_state_usage_is_bounded_and_reports_eighty_percent_warning() -> None:
    state = minimal_state()
    state["messages"] = {
        "message_1": {
            "message_id": "message_1",
            "role": "HUMAN",
            "audience": "USER",
            "content": "a" * 80,
            "sequence": 1,
        }
    }

    usage = validate_graph_state(
        state,
        limits=GraphStateLimits(
            checkpoint_bytes=10_000,
            message_count=4,
            message_total_bytes=100,
            message_bytes=100,
        ),
    )

    assert usage.message_total_bytes == 80
    assert "message_total_bytes" in usage.warning_fields


@pytest.mark.parametrize(
    ("mutate", "code"),
    [
        (
            lambda state: state.update(
                messages={
                    f"message_{index}": {
                        "message_id": f"message_{index}",
                        "role": "HUMAN",
                        "audience": "USER",
                        "content": "x",
                        "sequence": index,
                    }
                    for index in range(33)
                }
            ),
            "GRAPH_STATE_MESSAGES_TOO_MANY",
        ),
        (
            lambda state: state.update(
                messages={
                    "message_1": {
                        "message_id": "message_1",
                        "role": "HUMAN",
                        "audience": "USER",
                        "content": "x" * 8193,
                        "sequence": 1,
                    }
                }
            ),
            "GRAPH_STATE_MESSAGE_TOO_LARGE",
        ),
        (
            lambda state: state.update(
                work_items={f"work_{index}": {"value": index} for index in range(65)}
            ),
            "GRAPH_STATE_PENDING_WORK_TOO_LARGE",
        ),
        (
            lambda state: state.update(
                artifact_refs={f"artifact_{index}": {"value": index} for index in range(101)}
            ),
            "GRAPH_STATE_ARTIFACTS_TOO_MANY",
        ),
        (
            lambda state: state.update(memory_summary="x" * 16385),
            "GRAPH_STATE_SUMMARY_TOO_LARGE",
        ),
    ],
)
def test_each_numeric_state_limit_fails_closed(mutate, code: str) -> None:
    state = minimal_state()
    mutate(state)

    with pytest.raises(GraphStateLimitError) as captured:
        validate_graph_state(state)
    assert captured.value.code == code


def test_state_and_patch_reject_clients_and_oversized_payloads() -> None:
    state = minimal_state()
    state["client"] = object()
    with pytest.raises(GraphStateLimitError) as state_error:
        validate_graph_state(state)
    assert state_error.value.code == "GRAPH_STATE_NOT_CANONICAL_JSON"

    with pytest.raises(GraphStateLimitError) as patch_error:
        validate_graph_patch(
            {"large": "x" * 101},
            limits=GraphStateLimits(patch_bytes=100),
        )
    assert patch_error.value.code == "GRAPH_PATCH_TOO_LARGE"


def test_invalid_custom_limits_are_rejected() -> None:
    with pytest.raises(ValueError):
        GraphStateLimits(checkpoint_bytes=0)
    with pytest.raises(ValueError):
        GraphStateLimits(warning_ratio=1.1)
