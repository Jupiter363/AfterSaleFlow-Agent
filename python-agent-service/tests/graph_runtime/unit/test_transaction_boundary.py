from __future__ import annotations

import asyncio
from typing import Any

import anyio
import pytest

from app.graph_runtime.transaction_boundary import run_postgres_transaction


class _Transaction:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def __aenter__(self) -> "_Transaction":
        self._events.append("transaction-enter")
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self._events.append("transaction-rollback" if exc_type else "transaction-commit")


class _ConnectionContext:
    def __init__(self, connection: "_Connection", events: list[str]) -> None:
        self._connection = connection
        self._events = events

    async def __aenter__(self) -> "_Connection":
        self._events.append("pool-enter")
        return self._connection

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self._events.append("pool-exit")


class _Connection:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.query_started = asyncio.Event()
        self.query_cancelled = asyncio.Event()

    def transaction(self) -> _Transaction:
        return _Transaction(self.events)

    async def cancel_safe(self, *, timeout: float) -> None:
        assert timeout > 0
        self.events.append("cancel-safe")
        self.query_cancelled.set()


class _Pool:
    def __init__(self, connection: _Connection) -> None:
        self.connection_value = connection

    def connection(self, *, timeout: float | None) -> _ConnectionContext:
        assert timeout == 3.0
        return _ConnectionContext(self.connection_value, self.connection_value.events)


@pytest.mark.asyncio
async def test_cancellation_cancels_query_and_drains_transaction_before_propagating() -> None:
    connection = _Connection()

    async def operation(selected: _Connection) -> str:
        selected.query_started.set()
        await selected.query_cancelled.wait()
        raise RuntimeError("simulated query cancellation")

    task = asyncio.create_task(
        run_postgres_transaction(
            _Pool(connection),
            timeout=3.0,
            operation=operation,
            operation_name="checkpoint write",
        )
    )
    await connection.query_started.wait()
    task.cancel()

    with pytest.raises(asyncio.CancelledError):
        await task

    assert connection.events == [
        "pool-enter",
        "transaction-enter",
        "cancel-safe",
        "transaction-rollback",
        "pool-exit",
    ]


@pytest.mark.asyncio
async def test_successful_transaction_keeps_commit_path_unchanged() -> None:
    connection = _Connection()

    async def operation(_: _Connection) -> str:
        return "ok"

    result = await run_postgres_transaction(
        _Pool(connection),
        timeout=3.0,
        operation=operation,
        operation_name="normal write",
    )

    assert result == "ok"
    assert connection.events == [
        "pool-enter",
        "transaction-enter",
        "transaction-commit",
        "pool-exit",
    ]


@pytest.mark.asyncio
async def test_anyio_level_cancellation_still_returns_connection_after_rollback() -> None:
    connection = _Connection()

    async def operation(selected: _Connection) -> None:
        selected.query_started.set()
        await selected.query_cancelled.wait()
        raise RuntimeError("simulated database query cancellation")

    with pytest.raises(TimeoutError):
        with anyio.fail_after(0.01):
            await run_postgres_transaction(
                _Pool(connection),
                timeout=3.0,
                operation=operation,
                operation_name="anyio checkpoint write",
            )

    assert connection.events == [
        "pool-enter",
        "transaction-enter",
        "cancel-safe",
        "transaction-rollback",
        "pool-exit",
    ]
