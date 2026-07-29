from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import replace
from typing import Any

import pytest

from app.contracts.v1.models import ExecutionMetadata, Usage
from app.graph_runtime.checkpoint import (
    ExternalTerminalCommit,
    FENCE_CONTEXT_KEY,
    PENDING_WRITE_CHECKPOINT_RETRY_ATTEMPTS,
    PENDING_WRITE_CHECKPOINT_RETRY_DELAY_SECONDS,
    TERMINAL_RESULT_CONTEXT_KEY,
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
    bind_terminal_result_context,
)
from app.graph_runtime.ledger import ResultRecord
from app.graph_runtime.persistence_models import (
    GraphBindingError,
    GraphFenceContext,
    GraphFenceError,
    GraphGatewayMode,
)
from app.graph_runtime.result import CompletedDraft, ResultBindings
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


def _candidate_fence() -> GraphFenceContext:
    return GraphFenceContext(
        thread_id=f"grt.v1.{'2' * 32}",
        command_id="command-candidate-1",
        owner_id="worker-1",
        fencing_token=4,
        request_hash=SHA_A,
        room_epoch=3,
        graph_key="intake.v2",
        graph_version="intake.v2.1",
        checkpoint_schema_version="intake-checkpoint.v2",
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=f"p9act.v1.{'3' * 32}",
        room_fencing_token=11,
        command_hash="4" * 64,
        command_envelope_hash="5" * 64,
        environment_id="target-e2e-test",
        environment_generation=7,
        tenant_surrogate="tenant-test",
        case_id="case-test",
        room_type="INTAKE",
        binding_hash="6" * 64,
        code_build_id="candidate-build-1",
    )


def _config(*, checkpoint: bool = False) -> dict[str, Any]:
    configurable: dict[str, Any] = {"thread_id": _fence().thread_id}
    if checkpoint:
        configurable.update({"checkpoint_ns": "hearing", "checkpoint_id": "cp-parent"})
    return bind_fence_context({"configurable": configurable}, _fence())


def _metadata(**overrides: Any) -> dict[str, Any]:
    values = _fence().checkpoint_metadata()
    values["graph_cognitive_revision"] = 1
    values.update(overrides)
    return values


def _checkpoint(revision: int = 1) -> dict[str, Any]:
    return {
        "id": "cp-1",
        "channel_values": {"cognitive_revision": revision},
        "channel_versions": {},
    }


def _materializer() -> TerminalResultMaterializer:
    return TerminalResultMaterializer(
        thread_id=_fence().thread_id,
        request_hash=_fence().request_hash,
        draft=CompletedDraft(status="COMPLETED"),
        bindings=ResultBindings(
            command_id=_fence().command_id,
            logical_run_id="run-1",
            attempt_id="attempt-1",
            graph_key=_fence().graph_key,
            graph_version=_fence().graph_version,
            checkpoint_id="pending",
            cognitive_revision=4,
            public_event_proposals=(),
            artifact_operations=(),
            usage=Usage(input_tokens=1, output_tokens=1, total_tokens=2),
            execution_metadata=ExecutionMetadata(
                prompt_version="prompt.v1",
                model_profile_id="model.v1",
                schema_version="output.v1",
                policy_version="policy.v1",
                guardrail_version="guardrail.v1",
            ),
        ),
    )


def _result(
    *,
    checkpoint_ns: str = "hearing",
    checkpoint_id: str = "cp-1",
) -> ResultRecord:
    return _materializer().materialize(checkpoint_ns, checkpoint_id)


def _terminal_checkpoint() -> dict[str, Any]:
    return {
        "id": "cp-1",
        "channel_values": {
            "cognitive_revision": 4,
            "terminal_draft": {"status": "COMPLETED"},
            "usage_by_invocation": {
                "invocation-1": {
                    "input_tokens": 1,
                    "output_tokens": 1,
                    "total_tokens": 2,
                }
            },
            "result_json": {"pending": True},
        },
        "channel_versions": {"result_json": "v-result-1"},
    }


def _terminal_config(
    materializer: TerminalResultMaterializer | None = None,
) -> dict[str, Any]:
    return bind_terminal_result_context(
        _config(checkpoint=True),
        materializer or _materializer(),
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
        thread_current: bool = True,
        checkpoint_revision: int = 1,
        pending_write_checkpoint_unavailable_attempts: int = 0,
        terminal_metadata_current: bool = True,
    ) -> None:
        self.events: list[str] = []
        self.fence_current = fence_current
        self.binding_current = binding_current
        self.thread_current = thread_current
        self.checkpoint_revision = checkpoint_revision
        self.pending_write_checkpoint_unavailable_attempts = (
            pending_write_checkpoint_unavailable_attempts
        )
        self.pending_write_checkpoint_reads = 0
        self.terminal_metadata_current = terminal_metadata_current
        self.checkpoint_metadata = _metadata(graph_cognitive_revision=checkpoint_revision)

    def transaction(self) -> _Transaction:
        return _Transaction(self.events)

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        normalized = " ".join(query.split()).lower()
        if "from agent_graph_lease" in normalized:
            self.events.append("sql:fence")
            return _Cursor({"fencing_token": 1} if self.fence_current else None)
        if "from checkpoints" in normalized:
            self.events.append("sql:checkpoint-metadata")
            self.pending_write_checkpoint_reads += 1
            if (
                self.pending_write_checkpoint_reads
                <= self.pending_write_checkpoint_unavailable_attempts
            ):
                return _Cursor()
            return _Cursor({"metadata": self.checkpoint_metadata})
        if "update checkpoints" in normalized:
            self.events.append("sql:bind-terminal-metadata")
            if not self.terminal_metadata_current:
                return _Cursor(None)
            self.checkpoint_metadata = {
                **self.checkpoint_metadata,
                "graph_result_hash": params[0],
                "graph_result_ref": params[1],
                "graph_proposal_hash": params[2],
                "graph_result_envelope_hash": params[3],
            }
            return _Cursor({"metadata": self.checkpoint_metadata})
        if "update agent_graph_command" in normalized:
            self.events.append("sql:bind-command")
            result_hash = params[0]
            return _Cursor(
                {"status": "RESULT_CHECKPOINTED", "result_hash": result_hash}
                if self.binding_current and result_hash is not None
                else (
                    {"status": "EXECUTING", "result_hash": None} if self.binding_current else None
                )
            )
        if "update graph_thread_registry" in normalized:
            self.events.append("sql:advance-thread")
            if not self.thread_current:
                return _Cursor(None)
            return _Cursor(
                {
                    "cognitive_revision": params[0],
                    "last_checkpoint_ns": params[1],
                    "last_checkpoint_id": params[2],
                }
            )
        raise AssertionError(f"unexpected SQL: {normalized}")


class _StatefulThreadPointerConnection:
    def __init__(self) -> None:
        self.revision = 0
        self.checkpoint_ns: str | None = None
        self.checkpoint_id: str | None = None

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        normalized = " ".join(query.split()).lower()
        if "update graph_thread_registry" not in normalized:
            raise AssertionError(f"unexpected SQL: {normalized}")
        previous_revision = params[8]
        same_revision = params[9]
        allowed = self.revision == previous_revision
        if self.revision == same_revision:
            allowed = (self.checkpoint_ns, self.checkpoint_id) in {
                (params[10], params[11]),
                (params[12], params[13]),
            }
        if not allowed:
            return _Cursor(None)
        self.revision = params[0]
        self.checkpoint_ns = params[1]
        self.checkpoint_id = params[2]
        return _Cursor(
            {
                "cognitive_revision": self.revision,
                "last_checkpoint_ns": self.checkpoint_ns,
                "last_checkpoint_id": self.checkpoint_id,
            }
        )


class _CandidateFenceConnection:
    def __init__(self, *, room_current: bool = True) -> None:
        self.events: list[str] = []
        self.room_current = room_current

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        normalized = " ".join(query.split()).lower()
        if "update agent_graph_target_e2e_activation_lifecycle" in normalized:
            self.events.append("sql:drain-expired")
            return _Cursor()
        if "from agent_graph_target_e2e_room_authority" in normalized:
            self.events.append("sql:room-fence")
            return _Cursor({"room_fencing_token": 11} if self.room_current else None)
        if "from agent_graph_lease" in normalized:
            self.events.append("sql:graph-lease-fence")
            return _Cursor({"fencing_token": 4})
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
        self.connection_calls = 0

    def connection(self, *, timeout: float) -> _ConnectionContext:
        self.timeouts.append(timeout)
        self.connection_calls += 1
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
        self.checkpoints: list[dict[str, Any]] = []
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
        self.checkpoints.append(checkpoint)
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

    saved = await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "sql:advance-thread",
        "transaction:commit",
    ]
    assert direct_savers[0].connection is connection
    assert direct_savers[0].put_calls == [_metadata()]
    assert saved["configurable"][FENCE_CONTEXT_KEY] == _fence()


@pytest.mark.asyncio
async def test_candidate_checkpoint_locks_graph_lease_before_java_room_fence() -> None:
    connection = _CandidateFenceConnection()
    saver, _ = _saver(connection)  # type: ignore[arg-type]

    await saver._lock_fence(connection, _candidate_fence())  # noqa: SLF001

    assert connection.events == [
        "sql:drain-expired",
        "sql:graph-lease-fence",
        "sql:room-fence",
    ]


@pytest.mark.asyncio
async def test_stale_java_room_fence_rejects_after_graph_lease_lock() -> None:
    connection = _CandidateFenceConnection(room_current=False)
    saver, _ = _saver(connection)  # type: ignore[arg-type]

    with pytest.raises(GraphFenceError, match="Java room authority fence is stale"):
        await saver._lock_fence(connection, _candidate_fence())  # noqa: SLF001

    assert connection.events == [
        "sql:drain-expired",
        "sql:graph-lease-fence",
        "sql:room-fence",
    ]


@pytest.mark.asyncio
async def test_langgraph_bootstrap_checkpoint_is_fenced_without_advancing_thread_state() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)
    checkpoint = {
        "id": "cp-bootstrap",
        "channel_values": {"__start__": {"cognitive_revision": 1}},
        "channel_versions": {},
    }

    await saver.aput(_config(), checkpoint, {}, {})  # type: ignore[arg-type]

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "transaction:commit",
    ]
    assert direct_savers[0].put_calls == [_metadata()]


@pytest.mark.asyncio
async def test_thread_revision_conflict_rolls_back_the_checkpoint_transaction() -> None:
    connection = _Connection(thread_current=False)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphBindingError, match="advance the durable thread revision"):
        await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert len(direct_savers) == 1
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "sql:advance-thread",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
async def test_active_fence_can_repoint_multiple_checkpoints_at_one_cognitive_revision() -> None:
    saver, _ = _saver(_Connection())
    connection = _StatefulThreadPointerConnection()

    await saver._advance_thread_checkpoint(
        connection,
        _fence(),
        cognitive_revision=1,
        checkpoint_ns="intake",
        checkpoint_id="cp-intake-authorize",
        parent_checkpoint_ns="intake",
        parent_checkpoint_id=None,
    )
    await saver._advance_thread_checkpoint(
        connection,
        _fence(),
        cognitive_revision=1,
        checkpoint_ns="intake",
        checkpoint_id="cp-intake-route",
        parent_checkpoint_ns="intake",
        parent_checkpoint_id="cp-intake-authorize",
    )

    assert connection.revision == 1
    assert connection.checkpoint_ns == "intake"
    assert connection.checkpoint_id == "cp-intake-route"
    with pytest.raises(GraphBindingError, match="advance the durable thread revision"):
        await saver._advance_thread_checkpoint(
            connection,
            _fence(),
            cognitive_revision=1,
            checkpoint_ns="intake",
            checkpoint_id="cp-intake-authorize",
            parent_checkpoint_ns="intake",
            parent_checkpoint_id=None,
        )
    assert connection.checkpoint_id == "cp-intake-route"
    with pytest.raises(GraphBindingError, match="advance the durable thread revision"):
        await saver._advance_thread_checkpoint(
            connection,
            _fence(),
            cognitive_revision=3,
            checkpoint_ns="intake",
            checkpoint_id="cp-intake-invalid-jump",
            parent_checkpoint_ns="intake",
            parent_checkpoint_id="cp-intake-route",
        )


@pytest.mark.asyncio
async def test_terminal_checkpoint_result_and_command_commit_on_one_connection() -> None:
    connection = _Connection()
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    config = _terminal_config()

    saved = await saver.aput(
        config,
        _terminal_checkpoint(),  # type: ignore[arg-type]
        {},
        {"result_json": "v-result-1"},
    )

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "sql:advance-thread",
        "ledger:store-result",
        "transaction:commit",
    ]
    assert ledger.calls[0][0] is connection
    assert ledger.calls[0][2] == _result()
    assert ledger.calls[0][3] == "room-graph-result.v1"
    terminal_fence = ledger.calls[0][1]
    assert terminal_fence.result_hash == _result().result_hash
    assert terminal_fence.result_ref == _result().result_ref
    assert direct_savers[0].put_calls == [
        _metadata(
            graph_cognitive_revision=4,
            graph_result_hash=_result().result_hash,
            graph_result_ref=_result().result_ref,
        )
    ]
    assert direct_savers[0].checkpoints[0]["channel_values"]["result_json"] == dict(
        _result().result_json
    )
    assert saved["configurable"][FENCE_CONTEXT_KEY] == terminal_fence
    assert TERMINAL_RESULT_CONTEXT_KEY not in saved["configurable"]


@pytest.mark.asyncio
async def test_external_terminal_commit_binds_terminal_metadata_without_rewriting_state() -> None:
    connection = _Connection(checkpoint_revision=4)
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    result = _result(checkpoint_id="cp-parent")

    saved = await saver.acommit_external_terminal(
        _config(checkpoint=True),
        ExternalTerminalCommit(result=result, cognitive_revision=4),
    )

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "sql:checkpoint-metadata",
        "sql:bind-terminal-metadata",
        "sql:bind-command",
        "sql:advance-thread",
        "ledger:store-result",
        "transaction:commit",
    ]
    assert direct_savers == []
    assert ledger.calls[0][2] == result
    assert saved["configurable"][FENCE_CONTEXT_KEY].result_hash == result.result_hash
    assert connection.checkpoint_metadata == _metadata(
        graph_cognitive_revision=4,
        graph_result_hash=result.result_hash,
        graph_result_ref=result.result_ref,
        graph_proposal_hash=result.proposal_hash,
        graph_result_envelope_hash=result.result_envelope_hash,
    )


@pytest.mark.asyncio
async def test_external_terminal_commit_is_idempotent_after_terminal_metadata_is_bound() -> None:
    connection = _Connection(checkpoint_revision=4)
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    commit = ExternalTerminalCommit(result=_result(checkpoint_id="cp-parent"), cognitive_revision=4)

    await saver.acommit_external_terminal(_config(checkpoint=True), commit)
    saved = await saver.acommit_external_terminal(_config(checkpoint=True), commit)

    assert connection.events.count("sql:bind-terminal-metadata") == 1
    assert direct_savers == []
    assert len(ledger.calls) == 2
    assert saved["configurable"][FENCE_CONTEXT_KEY].result_hash == commit.result.result_hash


def test_external_terminal_metadata_requires_the_exact_candidate_fence_projection() -> None:
    initial_fence = _candidate_fence()
    terminal_fence = replace(
        initial_fence,
        result_hash=SHA_A,
        result_ref="urn:after-sale-flow:graph-result:" + SHA_A,
        proposal_hash=SHA_A,
        result_envelope_hash=SHA_B,
    )
    metadata = initial_fence.checkpoint_metadata()
    metadata["graph_cognitive_revision"] = 4

    assert not FencedPostgresSaver._validate_external_terminal_checkpoint_metadata(  # noqa: SLF001
        metadata,
        initial_fence,
        terminal_fence,
        cognitive_revision=4,
    )

    metadata.update(
        {
            "graph_result_hash": terminal_fence.result_hash,
            "graph_result_ref": terminal_fence.result_ref,
            "graph_proposal_hash": terminal_fence.proposal_hash,
            "graph_result_envelope_hash": terminal_fence.result_envelope_hash,
        }
    )
    assert FencedPostgresSaver._validate_external_terminal_checkpoint_metadata(  # noqa: SLF001
        metadata,
        initial_fence,
        terminal_fence,
        cognitive_revision=4,
    )

    metadata["graph_result_envelope_hash"] = SHA_A
    with pytest.raises(GraphBindingError, match="conflicts with its result fence"):
        FencedPostgresSaver._validate_external_terminal_checkpoint_metadata(  # noqa: SLF001
            metadata,
            initial_fence,
            terminal_fence,
            cognitive_revision=4,
        )


@pytest.mark.asyncio
async def test_external_terminal_commit_rolls_back_when_metadata_bind_is_not_durable() -> None:
    connection = _Connection(checkpoint_revision=4, terminal_metadata_current=False)
    ledger = _TerminalLedger(connection.events)
    saver, _ = _saver(connection, ledger=ledger)

    with pytest.raises(GraphBindingError, match="metadata was not durably bound"):
        await saver.acommit_external_terminal(
            _config(checkpoint=True),
            ExternalTerminalCommit(result=_result(checkpoint_id="cp-parent"), cognitive_revision=4),
        )

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "sql:checkpoint-metadata",
        "sql:bind-terminal-metadata",
        "transaction:rollback",
    ]
    assert ledger.calls == []


@pytest.mark.asyncio
async def test_external_terminal_preflight_rejects_stale_fence_before_store_boundary() -> None:
    connection = _Connection(fence_current=False)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphFenceError, match="stale"):
        await saver.avalidate_external_terminal_checkpoint(
            _config(checkpoint=True), cognitive_revision=4
        )

    assert direct_savers == []
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
async def test_terminal_materializer_uses_langgraph_checkpoint_id_after_fence_lock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    calls: list[tuple[str, str]] = []
    expected = _result()

    original = TerminalResultMaterializer.materialize

    def materialize(
        materializer: TerminalResultMaterializer,
        checkpoint_ns: str,
        checkpoint_id: str,
        *,
        fence: GraphFenceContext | None = None,
    ) -> ResultRecord:
        connection.events.append("terminal:materialize")
        calls.append((checkpoint_ns, checkpoint_id))
        return original(
            materializer,
            checkpoint_ns,
            checkpoint_id,
            fence=fence,
        )

    monkeypatch.setattr(TerminalResultMaterializer, "materialize", materialize)
    config = _terminal_config()

    await saver.aput(
        config,
        _terminal_checkpoint(),  # type: ignore[arg-type]
        {},
        {"result_json": "v-result-1"},
    )

    assert calls == [("hearing", "cp-1")]
    assert connection.events[:3] == [
        "transaction:enter",
        "sql:fence",
        "terminal:materialize",
    ]
    assert direct_savers[0].checkpoints[0]["channel_values"]["result_json"] == dict(
        expected.result_json
    )
    assert ledger.calls[0][2] == expected


@pytest.mark.asyncio
async def test_terminal_materializer_requires_a_versioned_result_channel() -> None:
    connection = _Connection()
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    config = _terminal_config()

    with pytest.raises(GraphBindingError, match="versioned result_json"):
        await saver.aput(
            config,
            {
                "id": "cp-1",
                "channel_values": {
                    "cognitive_revision": 4,
                    "result_json": {"pending": True},
                },
                "channel_versions": {"result_json": "v-result-1"},
            },  # type: ignore[arg-type]
            {},
            {},
        )

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "transaction:rollback",
    ]
    assert direct_savers == []
    assert ledger.calls == []


@pytest.mark.asyncio
async def test_terminal_materializer_failure_rolls_back_after_fence_before_write(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)

    def fail(
        materializer: TerminalResultMaterializer,
        checkpoint_ns: str,
        checkpoint_id: str,
        *,
        fence: GraphFenceContext | None = None,
    ) -> ResultRecord:
        connection.events.append("terminal:materialize")
        raise GraphBindingError("terminal projection failed")

    monkeypatch.setattr(TerminalResultMaterializer, "materialize", fail)
    with pytest.raises(GraphBindingError, match="terminal projection failed"):
        await saver.aput(
            _terminal_config(),
            _terminal_checkpoint(),  # type: ignore[arg-type]
            {},
            {"result_json": "v-result-1"},
        )

    assert direct_savers == []
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "terminal:materialize",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("cognitive_revision", 5, "revision"),
        ("terminal_draft", {"status": "NEEDS_INPUT"}, "draft"),
        (
            "usage_by_invocation",
            {
                "invocation-1": {
                    "input_tokens": 2,
                    "output_tokens": 1,
                    "total_tokens": 3,
                }
            },
            "usage",
        ),
    ],
)
async def test_terminal_result_must_match_the_checkpoint_state(
    field: str,
    value: Any,
    message: str,
) -> None:
    connection = _Connection()
    ledger = _TerminalLedger(connection.events)
    saver, direct_savers = _saver(connection, ledger=ledger)
    checkpoint = _terminal_checkpoint()
    checkpoint["channel_values"][field] = value

    with pytest.raises(GraphBindingError, match=message):
        await saver.aput(
            _terminal_config(),
            checkpoint,  # type: ignore[arg-type]
            {},
            {"result_json": "v-result-1"},
        )

    assert direct_savers == []
    assert ledger.calls == []
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
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
    config = _terminal_config()

    with pytest.raises(GraphBindingError, match="immutable result conflict"):
        await saver.aput(
            config,
            _terminal_checkpoint(),  # type: ignore[arg-type]
            {},
            {"result_json": "v-result-1"},
        )

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "sql:advance-thread",
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


def test_prebuilt_result_cannot_bypass_terminal_checkpoint_materialization() -> None:
    with pytest.raises(GraphBindingError, match="invalid type"):
        bind_terminal_result_context(_config(), _result())  # type: ignore[arg-type]


@pytest.mark.asyncio
async def test_stale_fence_rolls_back_before_the_saver_is_called() -> None:
    connection = _Connection(fence_current=False)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphFenceError, match="stale"):
        await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert direct_savers == []
    assert connection.events == ["transaction:enter", "sql:fence", "transaction:rollback"]


@pytest.mark.asyncio
async def test_stale_fence_rejects_terminal_write_before_materialization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection(fence_current=False)
    saver, direct_savers = _saver(connection)
    calls: list[tuple[str, str]] = []

    original = TerminalResultMaterializer.materialize

    def materialize(
        materializer: TerminalResultMaterializer,
        checkpoint_ns: str,
        checkpoint_id: str,
    ) -> ResultRecord:
        calls.append((checkpoint_ns, checkpoint_id))
        return original(materializer, checkpoint_ns, checkpoint_id)

    monkeypatch.setattr(TerminalResultMaterializer, "materialize", materialize)
    with pytest.raises(GraphFenceError, match="stale"):
        await saver.aput(
            _terminal_config(),
            _terminal_checkpoint(),  # type: ignore[arg-type]
            {},
            {"result_json": "v-result-1"},
        )

    assert calls == []
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
        await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

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
async def test_pending_writes_retry_after_releasing_the_lease_until_aput_commits(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection(pending_write_checkpoint_unavailable_attempts=1)
    saver, direct_savers = _saver(connection)
    delays: list[float] = []

    async def sleep(delay: float) -> None:
        delays.append(delay)

    monkeypatch.setattr("app.graph_runtime.checkpoint.asyncio.sleep", sleep)

    await saver.aput_writes(_config(checkpoint=True), [("channel", "value")], "task-1")

    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "sql:checkpoint-metadata",
        "transaction:rollback",
        "transaction:enter",
        "sql:fence",
        "sql:checkpoint-metadata",
        "saver:writes",
        "transaction:commit",
    ]
    assert delays == [PENDING_WRITE_CHECKPOINT_RETRY_DELAY_SECONDS]
    assert saver._pool.connection_calls == 2  # noqa: SLF001
    assert direct_savers[0].connection is connection


@pytest.mark.asyncio
async def test_pending_writes_fail_closed_after_bounded_checkpoint_retries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection(
        pending_write_checkpoint_unavailable_attempts=PENDING_WRITE_CHECKPOINT_RETRY_ATTEMPTS
    )
    saver, direct_savers = _saver(connection)
    delays: list[float] = []

    async def sleep(delay: float) -> None:
        delays.append(delay)

    monkeypatch.setattr("app.graph_runtime.checkpoint.asyncio.sleep", sleep)

    with pytest.raises(GraphBindingError, match="pending-write checkpoint does not exist"):
        await saver.aput_writes(_config(checkpoint=True), [("channel", "value")], "task-1")

    assert connection.events == [
        event
        for _ in range(PENDING_WRITE_CHECKPOINT_RETRY_ATTEMPTS)
        for event in (
            "transaction:enter",
            "sql:fence",
            "sql:checkpoint-metadata",
            "transaction:rollback",
        )
    ]
    assert delays == [
        PENDING_WRITE_CHECKPOINT_RETRY_DELAY_SECONDS * attempt
        for attempt in range(1, PENDING_WRITE_CHECKPOINT_RETRY_ATTEMPTS)
    ]
    assert direct_savers == []


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
    await active_saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    after_failover = _Connection(fence_current=False)
    stale_saver, direct_savers = _saver(after_failover)
    with pytest.raises(GraphFenceError, match="stale"):
        await stale_saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert direct_savers == []
    assert after_failover.events == [
        "transaction:enter",
        "sql:fence",
        "transaction:rollback",
    ]
