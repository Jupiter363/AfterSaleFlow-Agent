from __future__ import annotations

import pytest

from app.api.graph_lifecycle import GraphApplicationRuntime
from app.graph_runtime import checkpoint
from app.graph_runtime.persistence_models import (
    GraphGatewayMode,
    GraphPersistenceConfigurationError,
    GraphPoolConfig,
)


class _Pool:
    def __init__(self, config: GraphPoolConfig, events: list[tuple[str, str]]) -> None:
        self.config = config
        self._events = events

    async def open(self, *, wait: bool, timeout: float) -> None:
        assert wait is True
        self._events.append(("open", self.config.application_name))

    async def close(self, *, timeout: float) -> None:
        self._events.append(("close", self.config.application_name))


@pytest.mark.asyncio
async def test_runtime_reserves_pre_warmed_control_pool_without_expanding_maximum(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[tuple[str, str]] = []
    pools: list[_Pool] = []

    def create_pool(_dsn: str, config: GraphPoolConfig) -> _Pool:
        pool = _Pool(config, events)
        pools.append(pool)
        return pool

    monkeypatch.setattr(checkpoint, "create_graph_pool", create_pool)
    monkeypatch.setattr(checkpoint, "FencedPostgresSaver", lambda *args, **kwargs: object())

    runtime = await checkpoint.GraphCheckpointRuntime.open("postgresql://graph")

    checkpoint_pool, control_pool = pools
    assert runtime.pool is checkpoint_pool
    assert runtime.control_pool is control_pool
    assert checkpoint_pool.config.max_size == 14
    assert checkpoint_pool.config.min_size == 2
    assert control_pool.config.max_size == 2
    assert control_pool.config.min_size == 1
    assert checkpoint_pool.config.max_size + control_pool.config.max_size == 16
    assert checkpoint_pool.config.application_name.endswith("-checkpoint")
    assert control_pool.config.application_name.endswith("-control")
    assert len(checkpoint_pool.config.application_name) <= 63
    assert len(control_pool.config.application_name) <= 63

    await runtime.close()

    assert events == [
        ("open", checkpoint_pool.config.application_name),
        ("open", control_pool.config.application_name),
        ("close", control_pool.config.application_name),
        ("close", checkpoint_pool.config.application_name),
    ]


def test_runtime_pool_split_keeps_both_lanes_at_two_connection_limit() -> None:
    checkpoint_config, control_config = checkpoint._runtime_pool_configs(
        GraphPoolConfig(min_size=2, max_size=2, max_waiting=2)
    )

    assert (checkpoint_config.min_size, checkpoint_config.max_size) == (1, 1)
    assert (control_config.min_size, control_config.max_size) == (1, 1)
    assert checkpoint_config.max_size + control_config.max_size == 2


def test_runtime_pool_split_rejects_one_connection_budget() -> None:
    with pytest.raises(GraphPersistenceConfigurationError, match="at least two connections"):
        checkpoint._runtime_pool_configs(GraphPoolConfig(min_size=1, max_size=1))


def test_target_e2e_lifecycle_routes_to_control_pool() -> None:
    checkpoint_pool = object()
    control_pool = object()
    runtime = GraphApplicationRuntime(
        checkpoint_runtime=type(
            "RuntimePools",
            (),
            {"pool": checkpoint_pool, "control_pool": control_pool},
        )(),
        persistence_probe=object(),
        durable_bulkhead=object(),
        security_runtime=object(),
        gateway=object(),
        stream_service=object(),
        reconciliation_service=object(),
        admission_gate=object(),
        execution_verifier=object(),
        reconciliation_verifier=object(),
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
    )

    assert runtime.target_e2e_lifecycle_pool is control_pool
