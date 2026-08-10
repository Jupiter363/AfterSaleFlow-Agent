from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

import pytest

import app.graph_runtime.checkpoint as checkpoint_module
from app.contracts.v1.models import ExecutionMetadata, Usage
from app.graph_runtime.checkpoint import (
    BIND_EXTERNAL_TERMINAL_METADATA_SQL,
    ExternalTerminalCommit,
    FENCE_CONTEXT_KEY,
    TERMINAL_RESULT_CONTEXT_KEY,
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
    bind_terminal_result_context,
)
from app.graph_runtime.errors import GraphTerminalBindingError
from app.graph_runtime.ledger import (
    CheckpointRestoreAuthority,
    CheckpointRestoreKind,
    CompletedStartCheckpoint,
    ResultRecord,
)
from app.graph_runtime.persistence_models import (
    GraphBindingError,
    GraphFenceContext,
    GraphFenceError,
    GraphGatewayMode,
    GraphPersistenceConfigurationError,
)
from app.graph_runtime.result import CompletedDraft, ResultBindings
from langgraph.checkpoint.base import CheckpointTuple


SHA_A = "a" * 64
SHA_B = "b" * 64
STATEMENT_TIMEOUT_MS = 5_000


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


def _completed_start_proof(
    fence: GraphFenceContext | None = None,
) -> CompletedStartCheckpoint:
    current = fence or _fence()
    candidate = current.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE
    return CompletedStartCheckpoint(
        command_id=("command-candidate-previous" if candidate else "command-previous"),
        request_hash="7" * 64 if candidate else SHA_B,
        fencing_token=3 if candidate else 2,
        execution_lane=current.execution_lane,
        activation_id=current.activation_id,
        room_fencing_token=current.room_fencing_token,
        command_hash="8" * 64 if candidate else None,
        command_envelope_hash="9" * 64 if candidate else None,
        checkpoint_ns="intake" if candidate else "hearing",
        checkpoint_id="cp-parent",
        cognitive_revision=5 if candidate else 2,
        execution_provider="openai" if candidate else None,
        execution_model="gpt-5.6" if candidate else None,
        proposal_hash="a" * 64 if candidate else None,
        result_envelope_hash="b" * 64 if candidate else None,
        result_hash="c" * 64,
        result_ref="urn:test:graph-result:previous",
    )


def _completed_start_metadata(
    fence: GraphFenceContext,
    proof: CompletedStartCheckpoint,
) -> dict[str, Any]:
    predecessor_fence = replace(
        fence,
        command_id=proof.command_id,
        request_hash=proof.request_hash,
        fencing_token=proof.fencing_token,
        command_hash=proof.command_hash,
        command_envelope_hash=proof.command_envelope_hash,
        execution_provider=proof.execution_provider,
        execution_model=proof.execution_model,
        proposal_hash=proof.proposal_hash,
        result_envelope_hash=proof.result_envelope_hash,
        result_hash=proof.result_hash,
        result_ref=proof.result_ref,
    )
    metadata = predecessor_fence.checkpoint_metadata()
    metadata["graph_cognitive_revision"] = proof.cognitive_revision
    return metadata


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
    def __init__(
        self,
        row: Any = None,
        rows: list[Any] | None = None,
        *,
        connection: _Connection | None = None,
    ) -> None:
        self._row = row
        self._rows = rows or []
        self._connection = connection

    async def __aenter__(self) -> _Cursor:
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        return None

    async def fetchone(self) -> Any:
        return self._row

    async def fetchall(self) -> list[Any]:
        return self._rows

    async def executemany(self, query: str, params: Any) -> None:
        if self._connection is None:
            raise AssertionError("batch cursor has no connection")
        batch = tuple(params)
        normalized = " ".join(query.split()).lower()
        if "checkpoint_blobs" in normalized:
            self._connection.events.append("sql:checkpoint-blob-batch")
        elif "checkpoint_writes" in normalized:
            self._connection.events.append("sql:pending-write-batch")
        else:
            raise AssertionError(f"unexpected batch SQL: {normalized}")
        self._connection.executemany_calls.append((query, batch))


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
        refresh_current: bool = True,
        refresh_fencing_token: int = 1,
        refresh_revision: int = 2,
        refresh_times_valid: bool = True,
    ) -> None:
        self.events: list[str] = []
        self.executed_queries: list[tuple[str, Any]] = []
        self.fence_current = fence_current
        self.binding_current = binding_current
        self.thread_current = thread_current
        self.checkpoint_revision = checkpoint_revision
        self.pending_write_checkpoint_unavailable_attempts = (
            pending_write_checkpoint_unavailable_attempts
        )
        self.pending_write_checkpoint_reads = 0
        self.terminal_metadata_current = terminal_metadata_current
        self.refresh_current = refresh_current
        self.refresh_fencing_token = refresh_fencing_token
        self.refresh_revision = refresh_revision
        self.refresh_times_valid = refresh_times_valid
        self.executemany_calls: list[tuple[str, tuple[Any, ...]]] = []
        self.suffix_idle_timeout_params: list[Any] = []
        self.checkpoint_metadata = _metadata(graph_cognitive_revision=checkpoint_revision)

    def transaction(self) -> _Transaction:
        return _Transaction(self.events)

    def cursor(self) -> _Cursor:
        return _Cursor(connection=self)

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        self.executed_queries.append((query, params))
        normalized = " ".join(query.split()).lower()
        if "idle_in_transaction_session_timeout" in normalized:
            self.suffix_idle_timeout_params.append(params)
            return _Cursor()
        if "update agent_graph_lease lease" in normalized:
            self.events.append("sql:refresh-lease")
            if not self.refresh_current:
                return _Cursor(None)
            renewed_at = datetime.now(timezone.utc)
            expires_at = (
                renewed_at + timedelta(seconds=30)
                if self.refresh_times_valid
                else renewed_at
            )
            return _Cursor(
                {
                    "fencing_token": self.refresh_fencing_token,
                    "lease_revision": self.refresh_revision,
                    "renewed_at": renewed_at,
                    "lease_expires_at": expires_at,
                }
            )
        if "from agent_graph_lease" in normalized:
            self.events.append("sql:fence")
            return _Cursor({"fencing_token": 1} if self.fence_current else None)
        if "insert into checkpoint_blobs" in normalized:
            self.events.append("sql:checkpoint-blob")
            return _Cursor()
        if "insert into checkpoints" in normalized:
            self.events.append("sql:checkpoint-write")
            return _Cursor()
        if "insert into checkpoint_writes" in normalized:
            self.events.append("sql:pending-write")
            return _Cursor()
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
    def __init__(
        self,
        *,
        revision: int = 0,
        checkpoint_ns: str | None = None,
        checkpoint_id: str | None = None,
    ) -> None:
        self.revision = revision
        self.checkpoint_ns = checkpoint_ns
        self.checkpoint_id = checkpoint_id

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
        if (
            self.revision == 0
            and self.checkpoint_ns is None
            and self.checkpoint_id is None
            and params[14] == 2
        ):
            allowed = True
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
            statement_timeout_ms=STATEMENT_TIMEOUT_MS,
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


class _CompletedStartLedger:
    def __init__(
        self,
        proof: CompletedStartCheckpoint | None = None,
        *,
        failure: Exception | None = None,
    ) -> None:
        self.proof = proof
        self.failure = failure
        self.calls: list[tuple[Any, ...]] = []

    async def load_completed_start_checkpoint(
        self,
        connection: Any,
        *,
        fence: GraphFenceContext,
        checkpoint_ns: str,
        checkpoint_id: str,
        predecessor_command_id: str,
    ) -> CompletedStartCheckpoint:
        self.calls.append(
            (
                connection,
                fence,
                checkpoint_ns,
                checkpoint_id,
                predecessor_command_id,
            )
        )
        if self.failure is not None:
            raise self.failure
        if self.proof is None:
            raise GraphTerminalBindingError("completed start proof is absent")
        return self.proof


class _RestoreSelectionLedger(_CompletedStartLedger):
    def __init__(
        self,
        *,
        authority: Any = None,
        proof: CompletedStartCheckpoint | None = None,
        failure: Exception | None = None,
    ) -> None:
        super().__init__(proof, failure=failure)
        self.authority = authority
        self.authority_calls: list[tuple[Any, GraphFenceContext]] = []

    async def load_checkpoint_restore_authority(
        self,
        connection: Any,
        *,
        fence: GraphFenceContext,
    ) -> Any:
        self.authority_calls.append((connection, fence))
        return self.authority


@pytest.mark.parametrize("statement_timeout_ms", (True, 0, -1, 1.5))
def test_fenced_saver_rejects_invalid_statement_timeout(
    statement_timeout_ms: Any,
) -> None:
    with pytest.raises(
        GraphPersistenceConfigurationError,
        match="statement timeout must be a positive integer",
    ):
        FencedPostgresSaver(
            _Pool(_Connection()),  # type: ignore[arg-type]
            statement_timeout_ms=statement_timeout_ms,
            reader=_Reader(),  # type: ignore[arg-type]
        )


@pytest.mark.asyncio
async def test_fenced_saver_uses_actual_statement_timeout_and_rejects_unsafe_reserve() -> None:
    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=6_999,
        reader=_Reader(),  # type: ignore[arg-type]
    )

    await saver.avalidate_external_terminal_checkpoint(
        _config(checkpoint=True),
        cognitive_revision=1,
    )

    assert connection.suffix_idle_timeout_params == [("6999ms",)]
    with pytest.raises(
        GraphPersistenceConfigurationError,
        match="transaction completion reserve must remain below the lease horizon",
    ):
        FencedPostgresSaver(
            _Pool(_Connection()),  # type: ignore[arg-type]
            statement_timeout_ms=7_000,
            reader=_Reader(),  # type: ignore[arg-type]
        )


@pytest.mark.asyncio
async def test_checkpoint_write_uses_one_connection_and_fences_after_bulk_write() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)

    saved = await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert connection.events == [
        "transaction:enter",
        "saver:put",
        "sql:fence",
        "sql:bind-command",
        "sql:refresh-lease",
        "transaction:commit",
    ]
    assert direct_savers[0].connection is connection
    assert direct_savers[0].put_calls == [_metadata()]
    assert saved["configurable"][FENCE_CONTEXT_KEY] == _fence()


@pytest.mark.asyncio
async def test_native_checkpoint_serialization_finishes_before_fenced_transaction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
    )
    original_prepare = saver._prepare_checkpoint_write  # noqa: SLF001

    def recording_prepare(*args: Any, **kwargs: Any) -> Any:
        connection.events.append("prepare:checkpoint")
        return original_prepare(*args, **kwargs)

    monkeypatch.setattr(saver, "_prepare_checkpoint_write", recording_prepare)

    saved = await saver.aput(
        _config(checkpoint=True),
        _checkpoint(),  # type: ignore[arg-type]
        {},
        {},
    )

    assert connection.events == [
        "prepare:checkpoint",
        "transaction:enter",
        "sql:checkpoint-write",
        "sql:fence",
        "sql:bind-command",
        "sql:refresh-lease",
        "transaction:commit",
    ]
    assert saved["configurable"][FENCE_CONTEXT_KEY] == _fence()


@pytest.mark.asyncio
async def test_native_terminal_materialization_and_bulk_precede_final_fence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection(checkpoint_revision=4)
    ledger = _TerminalLedger(connection.events)
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
        ledger=ledger,
    )
    original_materialize = TerminalResultMaterializer.materialize

    def recording_materialize(
        materializer: TerminalResultMaterializer,
        checkpoint_ns: str,
        checkpoint_id: str,
        *,
        fence: GraphFenceContext | None = None,
    ) -> ResultRecord:
        connection.events.append("terminal:materialize")
        return original_materialize(
            materializer,
            checkpoint_ns,
            checkpoint_id,
            fence=fence,
        )

    monkeypatch.setattr(
        TerminalResultMaterializer,
        "materialize",
        recording_materialize,
    )

    await saver.aput(
        _terminal_config(),
        _terminal_checkpoint(),  # type: ignore[arg-type]
        {},
        {"result_json": "v-result-1"},
    )

    assert connection.events == [
        "transaction:enter",
        "terminal:materialize",
        "sql:checkpoint-blob-batch",
        "sql:checkpoint-write",
        "sql:fence",
        "sql:bind-command",
        "sql:advance-thread",
        "ledger:store-result",
        "sql:refresh-lease",
        "transaction:commit",
    ]


@pytest.mark.asyncio
async def test_native_pending_write_serialization_finishes_before_fenced_transaction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
    )
    original_prepare = saver._prepare_pending_writes  # noqa: SLF001

    def recording_prepare(*args: Any, **kwargs: Any) -> Any:
        connection.events.append("prepare:writes")
        return original_prepare(*args, **kwargs)

    monkeypatch.setattr(saver, "_prepare_pending_writes", recording_prepare)

    await saver.aput_writes(
        _config(checkpoint=True),
        (("custom-channel", {"value": "prepared"}),),
        "task-1",
    )

    assert connection.events == [
        "prepare:writes",
        "transaction:enter",
        "sql:pending-write-batch",
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:refresh-lease",
        "transaction:commit",
    ]


@pytest.mark.asyncio
async def test_prepared_checkpoint_blobs_and_pending_writes_use_one_batch_each() -> None:
    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
    )
    prepared_checkpoint = SimpleNamespace(
        blob_parameters=(("blob-1",), ("blob-2",), ("blob-3",)),
        checkpoint_parameters=("checkpoint",),
    )
    prepared_writes = SimpleNamespace(
        query="insert into checkpoint_writes values (%s)",
        parameters=(("write-1",), ("write-2",)),
    )

    await saver._write_prepared_checkpoint(connection, prepared_checkpoint)  # noqa: SLF001
    await saver._write_prepared_pending_writes(connection, prepared_writes)  # noqa: SLF001

    assert [len(batch) for _, batch in connection.executemany_calls] == [3, 2]
    assert connection.events == [
        "sql:checkpoint-blob-batch",
        "sql:checkpoint-write",
        "sql:pending-write-batch",
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("operation", ("checkpoint", "pending_writes"))
async def test_bulk_sql_precedes_final_fence_so_inflight_renewal_is_not_blocked(
    operation: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
    )
    bulk_started = asyncio.Event()
    release_bulk = asyncio.Event()

    if operation == "checkpoint":
        original_write = saver._write_prepared_checkpoint  # noqa: SLF001

        async def blocked_checkpoint_write(selected: Any, prepared: Any) -> None:
            connection.events.append("bulk:blocked")
            bulk_started.set()
            await release_bulk.wait()
            await original_write(selected, prepared)
            connection.events.append("bulk:finished")

        monkeypatch.setattr(
            saver,
            "_write_prepared_checkpoint",
            blocked_checkpoint_write,
        )
        write_task = asyncio.create_task(
            saver.aput(_config(checkpoint=True), _checkpoint(), {}, {})  # type: ignore[arg-type]
        )
        expected = [
            "transaction:enter",
            "bulk:blocked",
            "renew:complete",
            "sql:checkpoint-write",
            "bulk:finished",
            "sql:fence",
            "sql:bind-command",
            "sql:refresh-lease",
            "transaction:commit",
        ]
    else:
        original_write = saver._write_prepared_pending_writes  # noqa: SLF001

        async def blocked_pending_write(selected: Any, prepared: Any) -> None:
            connection.events.append("bulk:blocked")
            bulk_started.set()
            await release_bulk.wait()
            await original_write(selected, prepared)
            connection.events.append("bulk:finished")

        monkeypatch.setattr(
            saver,
            "_write_prepared_pending_writes",
            blocked_pending_write,
        )
        write_task = asyncio.create_task(
            saver.aput_writes(
                _config(checkpoint=True),
                (("custom-channel", {"value": "pending"}),),
                "task-bulk-renew",
            )
        )
        expected = [
            "transaction:enter",
            "bulk:blocked",
            "renew:complete",
            "sql:pending-write-batch",
            "bulk:finished",
            "sql:checkpoint-metadata",
            "sql:fence",
            "sql:refresh-lease",
            "transaction:commit",
        ]

    await asyncio.wait_for(bulk_started.wait(), timeout=0.1)
    try:
        assert "sql:fence" not in connection.events
        connection.events.append("renew:complete")
    finally:
        release_bulk.set()
        await write_task

    assert connection.events == expected


@pytest.mark.asyncio
@pytest.mark.parametrize("operation", ("checkpoint", "pending_writes"))
async def test_stale_final_fence_rolls_back_uncommitted_bulk_mutation(
    operation: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
    )
    bulk_started = asyncio.Event()
    release_bulk = asyncio.Event()

    if operation == "checkpoint":
        original_write = saver._write_prepared_checkpoint  # noqa: SLF001

        async def blocked_checkpoint_write(selected: Any, prepared: Any) -> None:
            connection.events.append("bulk:blocked")
            bulk_started.set()
            await release_bulk.wait()
            await original_write(selected, prepared)
            connection.events.append("bulk:finished")

        monkeypatch.setattr(
            saver,
            "_write_prepared_checkpoint",
            blocked_checkpoint_write,
        )
        write_task = asyncio.create_task(
            saver.aput(_config(checkpoint=True), _checkpoint(), {}, {})  # type: ignore[arg-type]
        )
        expected = [
            "transaction:enter",
            "bulk:blocked",
            "sql:checkpoint-write",
            "bulk:finished",
            "sql:fence",
            "transaction:rollback",
        ]
    else:
        original_write = saver._write_prepared_pending_writes  # noqa: SLF001

        async def blocked_pending_write(selected: Any, prepared: Any) -> None:
            connection.events.append("bulk:blocked")
            bulk_started.set()
            await release_bulk.wait()
            await original_write(selected, prepared)
            connection.events.append("bulk:finished")

        monkeypatch.setattr(
            saver,
            "_write_prepared_pending_writes",
            blocked_pending_write,
        )
        write_task = asyncio.create_task(
            saver.aput_writes(
                _config(checkpoint=True),
                (("custom-channel", {"value": "pending"}),),
                "task-stale-fence",
            )
        )
        expected = [
            "transaction:enter",
            "bulk:blocked",
            "sql:pending-write-batch",
            "bulk:finished",
            "sql:checkpoint-metadata",
            "sql:fence",
            "transaction:rollback",
        ]

    await asyncio.wait_for(bulk_started.wait(), timeout=0.1)
    connection.fence_current = False
    release_bulk.set()
    with pytest.raises(
        GraphFenceError,
        match="Graph lease is stale, expired, released, or cancelled",
    ):
        await write_task

    assert connection.events == expected
    assert "sql:bind-command" not in connection.events
    assert "sql:refresh-lease" not in connection.events
    assert "transaction:commit" not in connection.events


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "operation",
    ("checkpoint", "pending_writes", "validate_terminal", "commit_terminal"),
)
async def test_every_successful_fenced_transaction_refreshes_exact_lease_last(
    operation: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection(checkpoint_revision=(4 if operation == "commit_terminal" else 1))
    saver, _ = _saver(connection, ledger=_TerminalLedger(connection.events))
    original_suffix = saver._bounded_fenced_lease_suffix  # noqa: SLF001

    @asynccontextmanager
    async def observe_suffix(selected_connection: Any) -> AsyncIterator[None]:
        connection.events.append("suffix:enter")
        async with original_suffix(selected_connection):
            yield
        connection.events.append("suffix:exit")

    monkeypatch.setattr(saver, "_bounded_fenced_lease_suffix", observe_suffix)

    if operation == "checkpoint":
        await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]
    elif operation == "pending_writes":
        await saver.aput_writes(
            _config(checkpoint=True),
            [("channel", "value")],
            "task-refresh",
        )
    elif operation == "validate_terminal":
        await saver.avalidate_external_terminal_checkpoint(
            _config(checkpoint=True),
            cognitive_revision=1,
        )
    else:
        await saver.acommit_external_terminal(
            _config(checkpoint=True),
            ExternalTerminalCommit(
                result=_result(checkpoint_id="cp-parent"),
                cognitive_revision=4,
            ),
        )

    assert connection.events.count("suffix:enter") == 1
    assert connection.events.count("suffix:exit") == 1
    assert connection.suffix_idle_timeout_params == [("5000ms",)]
    assert connection.events[-3:] == [
        "sql:refresh-lease",
        "suffix:exit",
        "transaction:commit",
    ]
    assert connection.events.index("suffix:enter") < connection.events.index("sql:fence")
    assert connection.events.index("sql:fence") < connection.events.index(
        "sql:refresh-lease"
    )
    assert connection.events.index("sql:refresh-lease") < connection.events.index(
        "suffix:exit"
    )
    assert connection.events.index("suffix:exit") < connection.events.index(
        "transaction:commit"
    )
    if operation in {"pending_writes", "validate_terminal", "commit_terminal"}:
        assert connection.events.index("sql:checkpoint-metadata") < connection.events.index(
            "sql:fence"
        )
    refresh_query, refresh_params = next(
        (query, params)
        for query, params in reversed(connection.executed_queries)
        if "update agent_graph_lease lease" in " ".join(query.split()).lower()
    )
    assert "cancelled_at is null" in refresh_query
    assert "released_at is null" in refresh_query
    assert refresh_params == (
        _fence().thread_id,
        _fence().command_id,
        _fence().owner_id,
        _fence().fencing_token,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "operation",
    ("checkpoint", "pending_writes", "validate_terminal", "commit_terminal"),
)
async def test_fenced_suffix_timeout_rolls_back_without_late_mutation(
    operation: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        checkpoint_module,
        "FENCED_LEASE_SUFFIX_BODY_TIMEOUT_SECONDS",
        0.01,
    )
    connection = _Connection(checkpoint_revision=(4 if operation == "commit_terminal" else 1))
    saver, _ = _saver(connection, ledger=_TerminalLedger(connection.events))
    lock_cancelled = asyncio.Event()

    async def blocked_lock(_connection: Any, _fence: GraphFenceContext) -> None:
        connection.events.append("suffix:lock-blocked")
        try:
            await asyncio.Event().wait()
        finally:
            lock_cancelled.set()

    monkeypatch.setattr(saver, "_lock_fence", blocked_lock)

    with pytest.raises(
        GraphFenceError,
        match="transaction suffix exceeded its safety horizon",
    ):
        if operation == "checkpoint":
            await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]
        elif operation == "pending_writes":
            await saver.aput_writes(
                _config(checkpoint=True),
                [("channel", "value")],
                "task-timeout",
            )
        elif operation == "validate_terminal":
            await saver.avalidate_external_terminal_checkpoint(
                _config(checkpoint=True),
                cognitive_revision=1,
            )
        else:
            await saver.acommit_external_terminal(
                _config(checkpoint=True),
                ExternalTerminalCommit(
                    result=_result(checkpoint_id="cp-parent"),
                    cognitive_revision=4,
                ),
            )

    assert lock_cancelled.is_set()
    assert connection.suffix_idle_timeout_params == [("5000ms",)]
    assert connection.events[-1] == "transaction:rollback"
    assert "sql:refresh-lease" not in connection.events
    assert "transaction:commit" not in connection.events
    events_after_rollback = list(connection.events)
    await asyncio.sleep(0.02)
    assert connection.events == events_after_rollback


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "connection",
    (
        _Connection(refresh_current=False),
        _Connection(refresh_fencing_token=2),
        _Connection(refresh_revision=0),
        _Connection(refresh_times_valid=False),
    ),
)
async def test_locked_lease_refresh_failure_rolls_back_checkpoint_atomically(
    connection: _Connection,
) -> None:
    saver, _ = _saver(connection)

    with pytest.raises(GraphFenceError, match="changed|released|cancelled"):
        await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert connection.events[-2:] == ["sql:refresh-lease", "transaction:rollback"]
    assert "transaction:commit" not in connection.events


@pytest.mark.asyncio
async def test_same_thread_fenced_transactions_are_serialized_before_pool_acquisition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = _Connection()
    pool = _Pool(connection)
    saver = FencedPostgresSaver(
        pool,  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=_Reader(),  # type: ignore[arg-type]
    )
    first_locked = asyncio.Event()
    release_first = asyncio.Event()
    lock_calls = 0
    original_lock_fence = saver._lock_fence  # noqa: SLF001

    async def blocking_lock_fence(
        selected_connection: Any,
        fence: GraphFenceContext,
    ) -> None:
        nonlocal lock_calls
        await original_lock_fence(selected_connection, fence)
        lock_calls += 1
        if lock_calls == 1:
            first_locked.set()
            await release_first.wait()

    monkeypatch.setattr(saver, "_lock_fence", blocking_lock_fence)
    first = asyncio.create_task(
        saver.avalidate_external_terminal_checkpoint(
            _config(checkpoint=True),
            cognitive_revision=1,
        )
    )
    await first_locked.wait()
    second = asyncio.create_task(
        saver.avalidate_external_terminal_checkpoint(
            _config(checkpoint=True),
            cognitive_revision=1,
        )
    )
    await asyncio.sleep(0)

    assert pool.connection_calls == 1
    assert lock_calls == 1

    release_first.set()
    await asyncio.gather(first, second)

    assert pool.connection_calls == 2
    assert lock_calls == 2
    assert saver._thread_write_locks == {}  # noqa: SLF001


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
async def test_failed_command_nonterminal_checkpoint_does_not_advance_next_pointer() -> None:
    connection = _Connection(thread_current=False)
    saver, direct_savers = _saver(connection)

    await saver.aput(_config(), _checkpoint(), {}, {})  # type: ignore[arg-type]

    assert len(direct_savers) == 1
    assert connection.events == [
        "transaction:enter",
        "sql:fence",
        "saver:put",
        "sql:bind-command",
        "transaction:commit",
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
async def test_fresh_thread_can_bootstrap_terminal_checkpoint_at_revision_two() -> None:
    saver, _ = _saver(_Connection())
    connection = _StatefulThreadPointerConnection()

    await saver._advance_thread_checkpoint(
        connection,
        _fence(),
        cognitive_revision=2,
        checkpoint_ns="intake",
        checkpoint_id="cp-intake-terminal",
        parent_checkpoint_ns="",
        parent_checkpoint_id=None,
    )

    assert connection.revision == 2
    assert connection.checkpoint_ns == "intake"
    assert connection.checkpoint_id == "cp-intake-terminal"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    (
        "revision",
        "checkpoint_ns",
        "checkpoint_id",
        "target_revision",
        "target_checkpoint_id",
        "parent_checkpoint_id",
    ),
    (
        (0, None, None, 3, "cp-intake-jump", None),
        (0, "intake", "cp-existing", 2, "cp-intake-terminal", "cp-existing"),
        (0, "intake", None, 2, "cp-intake-terminal", None),
        (1, "intake", "cp-intake-one", 3, "cp-intake-jump", "cp-intake-one"),
    ),
    ids=(
        "fresh-thread-cannot-jump-to-three",
        "pointer-at-zero-is-not-fresh",
        "partial-pointer-at-zero-is-not-fresh",
        "one-cannot-jump-to-three",
    ),
)
async def test_thread_checkpoint_bootstrap_exception_rejects_non_fresh_jumps(
    revision: int,
    checkpoint_ns: str | None,
    checkpoint_id: str | None,
    target_revision: int,
    target_checkpoint_id: str,
    parent_checkpoint_id: str | None,
) -> None:
    saver, _ = _saver(_Connection())
    connection = _StatefulThreadPointerConnection(
        revision=revision,
        checkpoint_ns=checkpoint_ns,
        checkpoint_id=checkpoint_id,
    )

    with pytest.raises(GraphBindingError, match="advance the durable thread revision"):
        await saver._advance_thread_checkpoint(
            connection,
            _fence(),
            cognitive_revision=target_revision,
            checkpoint_ns="intake",
            checkpoint_id=target_checkpoint_id,
            parent_checkpoint_ns="intake",
            parent_checkpoint_id=parent_checkpoint_id,
        )

    assert (connection.revision, connection.checkpoint_ns, connection.checkpoint_id) == (
        revision,
        checkpoint_ns,
        checkpoint_id,
    )


@pytest.mark.asyncio
async def test_fresh_bootstrap_rejects_stale_same_revision_pointer_rollback() -> None:
    saver, _ = _saver(_Connection())
    connection = _StatefulThreadPointerConnection()

    await saver._advance_thread_checkpoint(
        connection,
        _fence(),
        cognitive_revision=2,
        checkpoint_ns="intake",
        checkpoint_id="cp-intake-terminal",
        parent_checkpoint_ns="",
        parent_checkpoint_id=None,
    )

    with pytest.raises(GraphBindingError, match="advance the durable thread revision"):
        await saver._advance_thread_checkpoint(
            connection,
            _fence(),
            cognitive_revision=2,
            checkpoint_ns="intake",
            checkpoint_id="cp-intake-bootstrap",
            parent_checkpoint_ns="",
            parent_checkpoint_id=None,
        )

    assert (connection.revision, connection.checkpoint_ns, connection.checkpoint_id) == (
        2,
        "intake",
        "cp-intake-terminal",
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
    assert connection.events.count("sql:advance-thread") == 1
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
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:bind-terminal-metadata",
        "sql:bind-command",
        "sql:advance-thread",
        "ledger:store-result",
        "sql:refresh-lease",
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
    bound_query, bound_params = next(
        (query, params)
        for query, params in connection.executed_queries
        if query == BIND_EXTERNAL_TERMINAL_METADATA_SQL
    )
    normalized_bound_query = " ".join(bound_query.split())
    assert normalized_bound_query == (
        "update checkpoints set metadata = metadata || jsonb_build_object( "
        "'graph_result_hash', %s::text, "
        "'graph_result_ref', %s::text, "
        "'graph_proposal_hash', %s::text, "
        "'graph_result_envelope_hash', %s::text ) "
        "where thread_id = %s and checkpoint_ns = %s and checkpoint_id = %s "
        "returning metadata"
    )
    assert bound_params[:4] == (
        result.result_hash,
        result.result_ref,
        result.proposal_hash,
        result.result_envelope_hash,
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
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:bind-terminal-metadata",
        "transaction:rollback",
    ]
    assert ledger.calls == []


@pytest.mark.asyncio
async def test_external_terminal_preflight_rejects_stale_fence_before_store_boundary() -> None:
    connection = _Connection(fence_current=False, checkpoint_revision=4)
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphFenceError, match="stale"):
        await saver.avalidate_external_terminal_checkpoint(
            _config(checkpoint=True), cognitive_revision=4
        )

    assert direct_savers == []
    assert connection.events == [
        "transaction:enter",
        "sql:checkpoint-metadata",
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

    assert len(direct_savers) == 1
    assert connection.events == [
        "transaction:enter",
        "saver:writes",
        "sql:checkpoint-metadata",
        "sql:fence",
        "transaction:rollback",
    ]


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
        "saver:writes",
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:refresh-lease",
        "transaction:commit",
    ]
    assert direct_savers[0].connection is connection


@pytest.mark.asyncio
async def test_first_pending_writes_accept_only_the_completed_start_checkpoint() -> None:
    fence = _fence()
    proof = _completed_start_proof(fence)
    connection = _Connection()
    connection.checkpoint_metadata = _completed_start_metadata(fence, proof)
    ledger = _CompletedStartLedger(proof)
    saver, direct_savers = _saver(connection, ledger=ledger)

    await saver.aput_writes(
        _config(checkpoint=True),
        [("channel", "value")],
        "task-first",
    )

    assert connection.events == [
        "transaction:enter",
        "saver:writes",
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:refresh-lease",
        "transaction:commit",
    ]
    assert ledger.calls == [
        (
            connection,
            fence,
            proof.checkpoint_ns,
            proof.checkpoint_id,
            proof.command_id,
        )
    ]
    assert direct_savers[0].connection is connection


@pytest.mark.asyncio
async def test_pending_writes_are_owned_by_the_exact_command_attempt() -> None:
    connection = _Connection()
    saver, direct_savers = _saver(connection)

    await saver.aput_writes(
        _config(checkpoint=True),
        [("channel", "value")],
        "task-current",
    )

    stored_task_id = direct_savers[0].write_calls[0][2]
    assert stored_task_id != "task-current"
    assert (
        saver._restore_pending_write_task_id(stored_task_id, _fence())  # noqa: SLF001
        == "task-current"
    )
    assert saver._restore_pending_write_task_id(  # noqa: SLF001
        stored_task_id,
        replace(_fence(), fencing_token=2),
    ) is None


@pytest.mark.asyncio
async def test_completed_predecessor_hides_pending_writes_from_other_commands() -> None:
    fence = _fence()
    proof = _completed_start_proof(fence)
    predecessor_fence = replace(
        fence,
        command_id=proof.command_id,
        request_hash=proof.request_hash,
        fencing_token=proof.fencing_token,
    )
    item = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": fence.thread_id,
                "checkpoint_ns": proof.checkpoint_ns,
                "checkpoint_id": proof.checkpoint_id,
            }
        },
        checkpoint={"id": proof.checkpoint_id},  # type: ignore[arg-type]
        metadata=_completed_start_metadata(fence, proof),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=[
            (
                FencedPostgresSaver._encode_pending_write_task_id(  # noqa: SLF001
                    "task-current",
                    fence,
                ),
                "current-channel",
                {"source": "current"},
            ),
            (
                FencedPostgresSaver._encode_pending_write_task_id(  # noqa: SLF001
                    "task-predecessor",
                    predecessor_fence,
                ),
                "predecessor-channel",
                {"source": "predecessor"},
            ),
            (
                FencedPostgresSaver._encode_pending_write_task_id(  # noqa: SLF001
                    "task-stale-attempt",
                    replace(fence, fencing_token=2),
                ),
                "stale-channel",
                {"source": "stale-attempt"},
            ),
            ("legacy-unowned-task", "legacy-channel", {"source": "legacy"}),
        ],
    )

    class CompletedStartReader(_Reader):
        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            return item

    ledger = _CompletedStartLedger(proof)
    saver = FencedPostgresSaver(
        _Pool(_Connection()),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=CompletedStartReader(),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
    )

    restored = await saver.aget_tuple(_config(checkpoint=True))

    assert restored is not None
    assert restored.pending_writes == [
        ("task-current", "current-channel", {"source": "current"})
    ]


@pytest.mark.asyncio
async def test_first_pending_writes_reject_start_checkpoint_without_terminal_proof() -> None:
    fence = _fence()
    proof = _completed_start_proof(fence)
    connection = _Connection()
    connection.checkpoint_metadata = _completed_start_metadata(fence, proof)
    ledger = _CompletedStartLedger(
        failure=GraphTerminalBindingError("completed start proof is absent")
    )
    saver, direct_savers = _saver(connection, ledger=ledger)

    with pytest.raises(GraphBindingError, match="completed start predecessor"):
        await saver.aput_writes(
            _config(checkpoint=True),
            [("channel", "value")],
            "task-first",
        )

    assert connection.events == [
        "transaction:enter",
        "saver:writes",
        "sql:checkpoint-metadata",
        "transaction:rollback",
    ]
    assert len(direct_savers) == 1


@pytest.mark.asyncio
async def test_pending_writes_allow_langgraph_precheckpoint_ordering() -> None:
    connection = _Connection(pending_write_checkpoint_unavailable_attempts=1)
    saver, direct_savers = _saver(connection)

    await saver.aput_writes(_config(checkpoint=True), [("channel", "value")], "task-1")

    assert connection.events == [
        "transaction:enter",
        "saver:writes",
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:refresh-lease",
        "transaction:commit",
    ]
    assert saver._pool.connection_calls == 1  # noqa: SLF001
    assert direct_savers[0].connection is connection


@pytest.mark.asyncio
async def test_pending_writes_allow_a_checkpoint_that_has_not_been_created_yet() -> None:
    connection = _Connection(
        pending_write_checkpoint_unavailable_attempts=99
    )
    saver, direct_savers = _saver(connection)
    await saver.aput_writes(_config(checkpoint=True), [("channel", "value")], "task-1")

    assert connection.events == [
        "transaction:enter",
        "saver:writes",
        "sql:checkpoint-metadata",
        "sql:fence",
        "sql:refresh-lease",
        "transaction:commit",
    ]
    assert direct_savers[0].connection is connection


@pytest.mark.asyncio
async def test_pending_writes_reject_an_existing_checkpoint_from_another_fence() -> None:
    connection = _Connection()
    connection.checkpoint_metadata["graph_key"] = "outcome_flow"
    saver, direct_savers = _saver(connection)

    with pytest.raises(GraphBindingError, match="graph_key"):
        await saver.aput_writes(_config(checkpoint=True), [("channel", "value")], "task-1")

    assert connection.events == [
        "transaction:enter",
        "saver:writes",
        "sql:checkpoint-metadata",
        "transaction:rollback",
    ]
    assert len(direct_savers) == 1


@pytest.mark.asyncio
async def test_checkpoint_read_with_null_current_authority_does_not_consume_thread_latest() -> None:
    foreign = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": _fence().thread_id,
                "checkpoint_ns": "hearing",
                "checkpoint_id": "cp-aborted-predecessor",
            }
        },
        checkpoint={"id": "cp-aborted-predecessor"},  # type: ignore[arg-type]
        metadata=_metadata(
            graph_command_id="command-aborted-predecessor",
            graph_request_hash=SHA_B,
            graph_fencing_token=2,
        ),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=None,
    )

    class ThreadLatestReader(_Reader):
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            self.calls.append(dict(config.get("configurable") or {}))
            return foreign

    connection = _Connection()
    reader = ThreadLatestReader()
    ledger = _RestoreSelectionLedger()
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=reader,  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
    )

    restored = await saver.aget_tuple(_config())

    assert restored is None
    assert reader.calls == []
    assert ledger.authority_calls == [(connection, _fence())]


@pytest.mark.asyncio
async def test_checkpoint_read_synthesizes_current_committed_pointer_before_reader() -> None:
    item = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": _fence().thread_id,
                "checkpoint_ns": "hearing",
                "checkpoint_id": "cp-current-committed",
            }
        },
        checkpoint={"id": "cp-current-committed"},  # type: ignore[arg-type]
        metadata=_metadata(),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=None,
    )

    class ExplicitReader(_Reader):
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            configurable = dict(config.get("configurable") or {})
            self.calls.append(configurable)
            return item

    connection = _Connection()
    reader = ExplicitReader()
    ledger = _RestoreSelectionLedger(
        authority=CheckpointRestoreAuthority(
            kind=CheckpointRestoreKind.CURRENT_COMMITTED,
            checkpoint_ns="hearing",
            checkpoint_id="cp-current-committed",
        )
    )
    saver = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=reader,  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
    )

    restored = await saver.aget_tuple(_config())

    assert restored is not None
    assert restored.checkpoint == {"id": "cp-current-committed"}
    assert reader.calls == [
        {
            "thread_id": _fence().thread_id,
            "checkpoint_ns": "hearing",
            "checkpoint_id": "cp-current-committed",
            FENCE_CONTEXT_KEY: _fence(),
        }
    ]
    assert ledger.authority_calls == [(connection, _fence())]


@pytest.mark.asyncio
async def test_checkpoint_read_rejects_another_graph_binding() -> None:
    item = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": _fence().thread_id,
                "checkpoint_ns": "hearing",
                "checkpoint_id": "cp-parent",
            }
        },
        checkpoint={"id": "cp-parent"},  # type: ignore[arg-type]
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
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=Reader(),  # type: ignore[arg-type]
    )

    with pytest.raises(GraphBindingError, match="graph_key"):
        await saver.aget_tuple(_config(checkpoint=True))


@pytest.mark.asyncio
async def test_candidate_read_accepts_exact_completed_start_checkpoint() -> None:
    fence = _candidate_fence()
    proof = _completed_start_proof(fence)
    item = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": fence.thread_id,
                "checkpoint_ns": proof.checkpoint_ns,
                "checkpoint_id": proof.checkpoint_id,
            }
        },
        checkpoint={"id": proof.checkpoint_id},  # type: ignore[arg-type]
        metadata=_completed_start_metadata(fence, proof),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=None,
    )

    class CompletedStartReader(_Reader):
        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            return item

    connection = _Connection()
    pool = _Pool(connection)
    ledger = _RestoreSelectionLedger(
        authority=CheckpointRestoreAuthority(
            kind=CheckpointRestoreKind.COMPLETED_START,
            checkpoint_ns=proof.checkpoint_ns,
            checkpoint_id=proof.checkpoint_id,
        ),
        proof=proof,
    )
    saver = FencedPostgresSaver(
        pool,  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=CompletedStartReader(),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
    )
    config = bind_fence_context(
        {"configurable": {"thread_id": fence.thread_id}},
        fence,
    )

    restored = await saver.aget_tuple(config)

    assert restored is not None
    assert restored.checkpoint == {"id": proof.checkpoint_id}
    assert restored.config["configurable"][FENCE_CONTEXT_KEY] == fence
    assert ledger.calls == [
        (
            connection,
            fence,
            proof.checkpoint_ns,
            proof.checkpoint_id,
            proof.command_id,
        )
    ]
    assert pool.connection_calls == 2
    assert ledger.authority_calls == [(connection, fence)]


@pytest.mark.asyncio
async def test_candidate_read_rejects_start_checkpoint_without_terminal_proof() -> None:
    fence = _candidate_fence()
    proof = _completed_start_proof(fence)
    item = CheckpointTuple(
        config={
            "configurable": {
                "thread_id": fence.thread_id,
                "checkpoint_ns": proof.checkpoint_ns,
                "checkpoint_id": proof.checkpoint_id,
            }
        },
        checkpoint={"id": proof.checkpoint_id},  # type: ignore[arg-type]
        metadata=_completed_start_metadata(fence, proof),  # type: ignore[arg-type]
        parent_config=None,
        pending_writes=None,
    )

    class CompletedStartReader(_Reader):
        async def aget_tuple(self, config: dict[str, Any]) -> CheckpointTuple:
            return item

    ledger = _RestoreSelectionLedger(
        authority=CheckpointRestoreAuthority(
            kind=CheckpointRestoreKind.COMPLETED_START,
            checkpoint_ns=proof.checkpoint_ns,
            checkpoint_id=proof.checkpoint_id,
        ),
        failure=GraphTerminalBindingError("completed start proof is absent")
    )
    saver = FencedPostgresSaver(
        _Pool(_Connection()),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=CompletedStartReader(),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
    )
    config = bind_fence_context(
        {"configurable": {"thread_id": fence.thread_id}},
        fence,
    )

    with pytest.raises(GraphBindingError, match="completed start predecessor"):
        await saver.aget_tuple(config)


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

    connection = _Connection()
    ledger = _RestoreSelectionLedger(
        authority=CheckpointRestoreAuthority(
            kind=CheckpointRestoreKind.CURRENT_COMMITTED,
            checkpoint_ns="hearing",
            checkpoint_id="cp-durable",
        )
    )
    replacement = FencedPostgresSaver(
        _Pool(connection),  # type: ignore[arg-type]
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        reader=DurableReader(),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
    )

    restored = await replacement.aget_tuple(_config())

    assert restored is not None
    assert restored.checkpoint == {"id": "cp-durable"}
    assert restored.config["configurable"][FENCE_CONTEXT_KEY] == _fence()
    assert ledger.authority_calls == [(connection, _fence())]


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
