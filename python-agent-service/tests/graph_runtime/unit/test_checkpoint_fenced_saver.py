from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any

import pytest

from app.graph_runtime.checkpoint import (
    FENCE_CONTEXT_KEY,
    TERMINAL_RESULT_CONTEXT_KEY,
    FencedPostgresSaver,
    bind_fence_context,
    bind_terminal_result_context,
)
from app.graph_runtime.ledger import ResultRecord
from app.graph_runtime.persistence_models import (
    GraphBindingError,
    GraphFenceContext,
    GraphFenceError,
)
from langgraph.checkpoint.base import CheckpointTuple


SHA_A = "a" * 64
SHA_B = "b" * 64


def _fence() -> GraphFenceContext:
    return GraphFenceContext(
        thread_id=f"grt.v1.{'1' * 32}",
        command_id="command-1",
        owner_id="worker-1",
        fencing_token=1,
        request_hash=SHA_A,
        room_epoch=3,
        graph_key="hearing_flow",
        graph_version="hearing_flow.v2",
        checkpoint_schema_version="hearing_checkpoint.v2",
    )


def _config(*, checkpoint: bool = False) -> dict[str, Any]:
    configurable: dict[str, Any] = {"thread_id": _fence().thread_id}
    if checkpoint:
        configurable.update({"checkpoint_ns": "hearing", "checkpoint_id": "cp-parent"})
    return bind_fence_context({"configurable": configurable}, _fence())


def _metadata(**overrides: Any) -> dict[str, Any]:
    values = _fence().checkpoint_metadata()
    values.update(overrides)
    return values


def _result(*, checkpoint_id: str = "cp-1") -> ResultRecord:
    return ResultRecord(
        result_id="result-1",
        thread_id=_fence().thread_id,
        command_id=_fence().command_id,
        request_hash=_fence().request_hash,
        result_schema_version="room-graph-result.v1",
        checkpoint_ns="hearing",
        checkpoint_id=checkpoint_id,
        cognitive_revision=4,
        terminal_status="COMPLETED",
        result_json={"output_hash": SHA_B},
        result_ref="urn:graph-result:command-1",
        result_hash=SHA_B,
        usage_json={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
    )


class _Cursor:
    def __init__(self, row: Any = None, rows: list[Any] | None = None) -> None:
        self._row = row
        self._rows = rows or []

    async def fetchone(self) -> Any:
        return self._row

    async def fetchall(self) -> list[Any]:
        return self._rows


class _Transaction:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def __aenter__(self) -> None:
        self._events.append("transaction:enter")

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self._events.append("transaction:rollback" if exc_type else "transaction:commit")


class _Connection:
    def __init__(
        self,
        *,
        fence_current: bool = True,
        binding_current: bool = True,
    ) -> None:
        self.events: list[str] = []
        self.fence_current = fence_current
        self.binding_current = binding_current

    def transaction(self) -> _Transaction:
        return _Transaction(self.events)

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        normalized = " ".join(query.split()).lower()
        if "from agent_graph_lease" in normalized:
            self.events.append("sql:fence")
            return _Cursor({"fencing_token": 1} if self.fence_current else None)
        if "from checkpoints" in normalized:
            self.events.append("sql:checkpoint-metadata")
            return _Cursor({"metadata": _metadata()})
        if "update agent_graph_command" in normalized:
            self.events.append("sql:bind-command")
            result_hash = params[0]
            return _Cursor(
                {"status": "RESULT_CHECKPOINTED", "result_hash": result_hash}
                if self.binding_current and result_hash is not None
                else (
                    {"status": "EXECUTING", "result_hash": None}
                    if self.binding_current
                    else None
                )
            )
        raise AssertionError(f"unexpected SQL: {normalized}")


class _ConnectionContext:
    def __init__(self, connection: _Connection) -> None:
        self._connection = connection

    async def __aenter__(self) -> _Connection:
        return self._connection

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        return None


class _Pool:
    def __init__(self, connection: _Connection) -> None:
        self.connection_value = connection
        self.timeouts: list[float] = []

    def connection(self, *, timeout: float) -> _ConnectionContext:
        self.timeouts.append(timeout)
        return _ConnectionContext(self.connection_value)


class _Reader:
    async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple | None:
        return None

    async def alist(self, *args: Any, **kwargs: Any) -> AsyncIterator[CheckpointTuple]:
        if False:
            yield  # pragma: no cover

    def get_next_version(self, current: Any, channel: Any) -> int:
        return 1 if current is None else int(current) + 1


class _DirectSaver:
    def __init__(self, connection: _Connection) -> None:
        self.connection = connection
        self.put_calls: list[dict[str, Any]] = []
        self.write_calls: list[tuple[Any, ...]] = []

    async def aput(
        self,
        config: dict[str, Any],
        checkpoint: Any,
        metadata: dict[str, Any],
        new_versions: dict[str, Any],
    ) -> dict[str, Any]:
        self.connection.events.append("saver:put")
        self.put_calls.append(metadata)
        return {
            "configurable": {
                "thread_id": _fence().thread_id,
                "checkpoint_ns": "hearing",
                "checkpoint_id": "cp-1",
            }
        }

    async def aput_writes(
        self,
        config: dict[str, Any],
        writes: Any,
        task_id: str,
        task_path: str,
    ) -> None:
        self.connection.events.append("saver:writes")
        self.write_calls.append((config, writes, task_id, task_path))


def _saver(
    connection: _Connection,
    *,
    ledger: Any = None,
) -> tuple[FencedPostgresSaver, list[_DirectSaver]]:
    direct_savers: list[_DirectSaver] = []

    def factory(selected_connection: _Connection, serde: Any) -> _DirectSaver:
        saver = _DirectSaver(selected_connection)
        direct_savers.append(saver)
        return saver

    return (
        FencedPostgresSaver(
            _Pool(connection),  # type: ignore[arg-type]
            reader=_Reader(),  # type: ignore[arg-type]
            direct_saver_factory=factory,  # type: ignore[arg-type]
            ledger=ledger,
        ),
        direct_savers,
    )


class _TerminalLedger:
    def __init__(self, events: list[str], *, failure: Exception | None = None) -> None:
        self.events = events
        self.calls: list[tuple[Any, ...]] = []
        self.failure = failure

    async def store_terminal_result(
        self,
        connection: Any,
        *,
        fence: GraphFenceContext,
        result: ResultRecord,
        expected_result_schema_version: str,
    ) -> ResultRecord:
        self.events.append("ledger:store-result")
        self.calls.append((connection, fence, result, expected_result_schema_version))
        if self.failure is not None:
            raise self.failure
        return result


@pytest.mark.asyncio
async def test_checkpoint_write_locks_fence_and_uses_one_connection() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)

    saved = await saver.aput(_config(), {}, {}, {})  # type: ignore[arg-type]

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "transaction:commit",
    ]
    assert direct_savers[0].connection is connection
    assert direct_savers[0].put_calls == [_metadata()]
    assert saved["configurable"][FENCE_CONTEXT_KEY] == _fence()


@pytest.mark.asyncio
async def test_terminal_checkpoint_result_and_command_commit_on_one_connection() -> None:
    connection = _Connection()
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    config = bind_terminal_result_context(_config(), _result())

    saved = await saver.aput(config, {}, {}, {})  # type: ignore[arg-type]

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "ledger:store-result",
        "transaction:commit",
    ]
    assert ledger.calls[0][0] is connection
    assert ledger.calls[0][2] == _result()
    assert ledger.calls[0][3] == "room-graph-result.v1"
    terminal_fence = ledger.calls[0][1]
    assert terminal_fence.result_hash == SHA_B
    assert terminal_fence.result_ref == _result().result_ref
    assert direct_savers[0].put_calls == [
        _metadata(graph_result_hash=SHA_B, graph_result_ref=_result().result_ref)
    ]
    assert saved["configurable"][FENCE_CONTEXT_KEY] == terminal_fence
    assert TERMINAL_RESULT_CONTEXT_KEY not in saved["configurable"]


@pytest.mark.asyncio
async def test_terminal_result_checkpoint_mismatch_rolls_back_before_ledger_write() -> None:
    connection = _Connection()
    ledger = _TerminalLedger(connection.events)
    saver, _ = _saver(connection, ledger=ledger)
    config = bind_terminal_result_context(_config(), _result(checkpoint_id="cp-other"))

    with pytest.raises(GraphBindingError, match="saved checkpoint identity"):
        await saver.aput(config, {}, {}, {})  # type: ignore[arg-type]

    assert ledger.calls == []
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
async def test_terminal_result_failure_rolls_back_checkpoint_and_command_binding() -> None:
    connection = _Connection()
    ledger = _TerminalLedger(
        connection.events,
        failure=GraphBindingError("immutable result conflict"),
    )
    saver, _ = _saver(connection, ledger=ledger)
    config = bind_terminal_result_context(_config(), _result())

    with pytest.raises(GraphBindingError, match="immutable result conflict"):
        await saver.aput(config, {}, {}, {})  # type: ignore[arg-type]

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "ledger:store-result",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
async def test_forged_terminal_result_capability_is_rejected_before_database_access() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)
    config = _config()
    config["configurable"][TERMINAL_RESULT_CONTEXT_KEY] = {"result_hash": SHA_B}

    with pytest.raises(GraphBindingError, match="forged terminal result"):
        await saver.aput(config, {}, {}, {})  # type: ignore[arg-type]

    assert connection.events == []
    assert direct_savers == []


@pytest.mark.asyncio
async def test_stale_fence_rolls_back_before_the_saver_is_called() -> None:
    connection = _Connection(fence_current=False)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphFenceError, match="stale"):
        await saver.aput(_config(), {}, {}, {})  # type: ignore[arg-type]

    assert direct_savers == []
    assert connection.events == ["transaction:enter", "sql:fence", "transaction:rollback"]


@pytest.mark.asyncio
async def test_takeover_that_changes_the_fence_rejects_the_late_writer() -> None:
    connection = _Connection(fence_current=False)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphFenceError, match="stale"):
        await saver.aput_writes(
            _config(checkpoint=True),
            [("late-provider-output", "must-not-commit")],
            "task-late",
        )

    assert direct_savers == []
    assert connection.events == ["transaction:enter", "sql:fence", "transaction:rollback"]


@pytest.mark.asyncio
async def test_command_binding_conflict_rolls_back_the_same_connection_checkpoint() -> None:
    connection = _Connection(binding_current=False)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphBindingError, match="durable Graph command binding"):
        await saver.aput(_config(), {}, {}, {})  # type: ignore[arg-type]

    assert direct_savers[0].connection is connection
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
async def test_forged_mapping_is_not_a_trusted_fence_capability() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)
    forged = {
        "configurable": {
            "thread_id": _fence().thread_id,
            FENCE_CONTEXT_KEY: _fence().checkpoint_metadata(),
        }
    }

    with pytest.raises(GraphBindingError, match="trusted GraphFenceContext"):
        await saver.aput(forged, {}, {}, {})  # type: ignore[arg-type]

    assert direct_savers == []
    assert connection.events == []


@pytest.mark.asyncio
async def test_pending_writes_validate_the_parent_under_the_same_fence() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)

    await saver.aput_writes(_config(checkpoint=True), [("channel", "value")], "task-1")

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "sql:checkpoint-metadata",
        "saver:writes",
        "transaction:commit",
    ]
    assert direct_savers[0].connection is connection


@pytest.mark.asyncio
async def test_checkpoint_read_rejects_another_graph_binding() -> None:
    item = CheckpointTuple(
        config={"configurable": {"thread_id": _fence().thread_id}},
        checkpoint={},  # type: ignore[arg-type]
        metadata=_metadata(graph_key="intake_flow"),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=None,
    )

    class Reader(_Reader):
        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            return item

    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        reader=Reader(),  # type: ignore[arg-type]
    )

    with pytest.raises(GraphBindingError, match="graph_key"):
        await saver.aget_tuple(_config())


@pytest.mark.asyncio
async def test_replacement_saver_restores_a_durable_thread_without_process_state() -> None:
    item = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": _fence().thread_id,
                "checkpoint_ns": "hearing",
                "checkpoint_id": "cp-durable",
            }
        },
        checkpoint={"id": "cp-durable"},  # type: ignore[arg-type]
        metadata=_metadata(),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=None,
    )

    class DurableReader(_Reader):
        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            return item

    replacement = FencedPostgresSaver(
        _Pool(_Connection()),  # type: ignore[arg-type]
        reader=DurableReader(),  # type: ignore[arg-type]
    )

    restored = await replacement.aget_tuple(_config())

    assert restored is not None
    assert restored.checkpoint == {"id": "cp-durable"}
    assert restored.config["configurable"][FENCE_CONTEXT_KEY] == _fence()


@pytest.mark.asyncio
async def test_reconnected_saver_rechecks_fence_after_database_failover() -> None:
    before_failover = _Connection()
    active_saver, _ = _saver(before_failover)
    await active_saver.aput(_config(), {}, {}, {})  # type: ignore[arg-type]

    after_failover = _Connection(fence_current=False)
    stale_saver, direct_savers = _saver(after_failover)
    with pytest.raises(GraphFenceError, match="stale"):
        await stale_saver.aput(_config(), {}, {}, {})  # type: ignore[arg-type]

    assert direct_savers == []
    assert after_failover.events == [
        "transaction:enter",
        "sql:fence",
        "transaction:rollback",
    ]
