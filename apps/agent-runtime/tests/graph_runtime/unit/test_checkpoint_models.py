from __future__ import annotations

from dataclasses import replace
import inspect

import pytest

from app.graph_runtime.persistence_models import (
    GraphFenceContext,
    GraphPersistenceConfigurationError,
    GraphPoolConfig,
)
from app.graph_runtime.checkpoint import create_graph_pool


SHA_A = "a" * 64
SHA_B = "b" * 64


def _fence(**overrides: object) -> GraphFenceContext:
    values: dict[str, object] = {
        "thread_id": f"grt.v1.{'1' * 32}",
        "command_id": "command-1",
        "owner_id": "graph-worker-1",
        "fencing_token": 1,
        "request_hash": SHA_A,
        "room_epoch": 3,
        "graph_version": "hearing_flow.v2",
        "checkpoint_schema_version": "hearing_checkpoint.v2",
    }
    if "graph_key" in inspect.signature(GraphFenceContext).parameters:
        values["graph_key"] = "hearing_flow"
    values.update(overrides)
    return GraphFenceContext(**values)  # type: ignore[arg-type]


def test_fence_context_binds_graph_key_into_checkpoint_metadata() -> None:
    assert "graph_key" in inspect.signature(GraphFenceContext).parameters
    fence = _fence()

    assert fence.graph_key == "hearing_flow"
    assert fence.checkpoint_metadata() == {
        "graph_thread_id": fence.thread_id,
        "graph_command_id": fence.command_id,
        "graph_request_hash": fence.request_hash,
        "graph_room_epoch": fence.room_epoch,
        "graph_key": fence.graph_key,
        "graph_version": fence.graph_version,
        "graph_checkpoint_schema_version": fence.checkpoint_schema_version,
        "graph_execution_lane": "SHADOW",
        "graph_activation_id": None,
        "graph_room_fencing_token": None,
        "graph_command_hash": None,
        "graph_command_envelope_hash": None,
        "graph_execution_provider": None,
        "graph_execution_model": None,
        "graph_environment_id": None,
        "graph_environment_generation": None,
        "graph_tenant_surrogate": None,
        "graph_case_id": None,
        "graph_room_type": None,
        "graph_binding_hash": None,
        "graph_code_build_id": None,
        "graph_proposal_hash": None,
        "graph_result_envelope_hash": None,
        "graph_fencing_token": fence.fencing_token,
        "graph_result_hash": None,
        "graph_result_ref": None,
    }


@pytest.mark.parametrize("graph_key", ["", "x" * 129, "hearing\x00flow"])
def test_fence_context_rejects_an_invalid_graph_key(graph_key: str) -> None:
    assert "graph_key" in inspect.signature(GraphFenceContext).parameters
    with pytest.raises(GraphPersistenceConfigurationError, match="graph_key"):
        _fence(graph_key=graph_key)


@pytest.mark.parametrize(
    ("field_name", "invalid_value", "message"),
    [
        ("thread_id", "hearing:case-1", "thread_id"),
        ("fencing_token", 0, "fencing_token"),
        ("request_hash", "not-a-hash", "request_hash"),
        ("room_epoch", -1, "room_epoch"),
        ("graph_version", "", "graph_version"),
        ("checkpoint_schema_version", "", "checkpoint_schema_version"),
    ],
)
def test_fence_context_rejects_invalid_identity_bindings(
    field_name: str,
    invalid_value: object,
    message: str,
) -> None:
    with pytest.raises(GraphPersistenceConfigurationError, match=message):
        _fence(**{field_name: invalid_value})


def test_fence_context_requires_result_hash_and_reference_as_a_pair() -> None:
    with pytest.raises(GraphPersistenceConfigurationError, match="result_ref"):
        _fence(result_ref="urn:graph-result:orphan")

    with pytest.raises(GraphPersistenceConfigurationError, match="result_ref"):
        _fence(result_hash=SHA_B)

    terminal = _fence(
        result_hash=SHA_B,
        result_ref="urn:graph-result:sha256:" + SHA_B,
    )
    assert terminal.result_hash == SHA_B
    assert terminal.result_ref == "urn:graph-result:sha256:" + SHA_B


def test_fence_context_is_immutable() -> None:
    fence = _fence()

    with pytest.raises((AttributeError, TypeError)):
        fence.fencing_token = 2  # type: ignore[misc]

    assert replace(fence, fencing_token=2).fencing_token == 2


def test_pool_waiter_bound_cannot_be_smaller_than_pool_capacity() -> None:
    with pytest.raises(GraphPersistenceConfigurationError, match="max_waiting"):
        GraphPoolConfig(max_size=16, max_waiting=15)

    assert GraphPoolConfig(max_size=16, max_waiting=16).max_waiting == 16


def test_graph_pool_search_path_puts_temporary_objects_last() -> None:
    pool = create_graph_pool(
        "postgresql://graph_runtime:unused@localhost/graph_db",
        GraphPoolConfig(schema="graph_runtime"),
    )

    assert "-csearch_path=graph_runtime,pg_catalog,pg_temp" in pool.kwargs["options"]
    assert "-cidle_in_transaction_session_timeout=150000" in pool.kwargs["options"]
