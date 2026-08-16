from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
import logging
from types import SimpleNamespace
from typing import Any

import pytest
import app.graph_runtime.gateway as gateway_module
from psycopg import OperationalError
from psycopg.errors import LockNotAvailable
from psycopg_pool import PoolTimeout

from app.graph_runtime.errors import (
    GraphCommandDeadlineError,
    GraphCommandStateError,
    GraphContractError,
    GraphLeaseLostError,
)
from app.graph_runtime.gateway import (
    AdmissionAction,
    GatewayAdmission,
    GatewayExecution,
    GraphCommandGateway,
)
from app.graph_runtime.lease import LeaseRecord
from app.graph_runtime.ledger import AttemptStatus, CommandStatus
from app.graph_runtime.persistence_models import GraphGatewayMode


THREAD_ID = f"grt.v1.{'a' * 32}"


class _AsyncContext:
    def __init__(
        self,
        value: Any,
        *,
        exit_error: BaseException | None = None,
        suppress_error: bool = False,
    ) -> None:
        self._value = value
        self._exit_error = exit_error
        self._suppress_error = suppress_error

    async def __aenter__(self) -> Any:
        return self._value

    async def __aexit__(self, _type: Any, _value: Any, _traceback: Any) -> bool:
        if self._exit_error is not None:
            raise self._exit_error
        return self._suppress_error


class _Connection:
    def __init__(
        self,
        *,
        transaction_exit_error: BaseException | None = None,
        transaction_suppresses_error: bool = False,
    ) -> None:
        self._transaction_exit_error = transaction_exit_error
        self._transaction_suppresses_error = transaction_suppresses_error

    def transaction(self) -> _AsyncContext:
        return _AsyncContext(
            self,
            exit_error=self._transaction_exit_error,
            suppress_error=self._transaction_suppresses_error,
        )


class _Pool:
    def __init__(
        self,
        *,
        connection_errors: list[BaseException | None] | None = None,
        connection_exit_error: BaseException | None = None,
        transaction_exit_error: BaseException | None = None,
        transaction_exit_errors: list[BaseException | None] | None = None,
        transaction_suppresses_error: bool = False,
    ) -> None:
        self._connection_errors = list(connection_errors or [])
        self._connection_exit_error = connection_exit_error
        self._transaction_exit_error = transaction_exit_error
        self._transaction_exit_errors = (
            None if transaction_exit_errors is None else list(transaction_exit_errors)
        )
        self._transaction_suppresses_error = transaction_suppresses_error
        self.connection_calls = 0

    def connection(self, *, timeout: float) -> _AsyncContext:
        del timeout
        self.connection_calls += 1
        if self._connection_errors:
            error = self._connection_errors.pop(0)
            if error is not None:
                raise error
        transaction_exit_error = self._transaction_exit_error
        if self._transaction_exit_errors is not None:
            transaction_exit_error = self._transaction_exit_errors.pop(0)
        return _AsyncContext(
            _Connection(
                transaction_exit_error=transaction_exit_error,
                transaction_suppresses_error=self._transaction_suppresses_error,
            ),
            exit_error=self._connection_exit_error,
        )


@dataclass(frozen=True)
class _Attempt:
    provider_call_count: int = 0


class _Leases:
    def __init__(self, outcomes: list[LeaseRecord | BaseException]) -> None:
        self._outcomes = list(outcomes)
        self.calls = 0
        self.renew_kwargs: list[dict[str, Any]] = []

    async def renew(self, _connection: Any, **kwargs: Any) -> LeaseRecord:
        self.calls += 1
        self.renew_kwargs.append(kwargs)
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome

    async def cancel(self, _connection: Any, **_kwargs: Any) -> LeaseRecord:
        self.calls += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


class _Ledger:
    def __init__(self, outcomes: list[_Attempt | BaseException]) -> None:
        self._outcomes = list(outcomes)
        self.provider_calls = 0

    async def record_provider_call(self, _connection: Any, _attempt: _Attempt) -> _Attempt:
        self.provider_calls += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


def _durable_attempt(
    *,
    status: AttemptStatus = AttemptStatus.EXECUTING,
    error_code: str | None = None,
    error_classification: str | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        attempt_id="attempt-1",
        thread_id=THREAD_ID,
        command_id="command-1",
        owner_id="owner-1",
        fencing_token=1,
        status=status,
        error_code=error_code,
        error_classification=error_classification,
    )


def _durable_command(
    *,
    status: CommandStatus = CommandStatus.EXECUTING,
    error_code: str | None = None,
    error_classification: str | None = None,
    checkpoint_ns: str | None = None,
    checkpoint_id: str | None = None,
    result_ref: str | None = None,
    result_hash: str | None = None,
) -> SimpleNamespace:
    binding = SimpleNamespace(thread_id=THREAD_ID, command_id="command-1")
    return SimpleNamespace(
        binding=binding,
        status=status,
        terminal=status
        in {
            CommandStatus.CANCELLED,
            CommandStatus.ABORTED,
            CommandStatus.RESULT_CHECKPOINTED,
            CommandStatus.COMPLETED,
        },
        fencing_token=1,
        committed_checkpoint_ns=checkpoint_ns,
        committed_checkpoint_id=checkpoint_id,
        result_ref=result_ref,
        result_hash=result_hash,
        error_code=error_code,
        error_classification=error_classification,
    )


class _FinishLedger:
    def __init__(self) -> None:
        self.command = _durable_command()
        self.attempt = _durable_attempt()
        self.terminate_calls = 0
        self.finish_calls = 0

    async def load(self, _connection: Any, **_kwargs: Any) -> SimpleNamespace:
        return self.command

    async def latest_attempt(self, _connection: Any, **_kwargs: Any) -> SimpleNamespace:
        return self.attempt

    def require_same_binding(self, actual: Any, expected: Any) -> None:
        assert (actual.thread_id, actual.command_id) == (
            expected.thread_id,
            expected.command_id,
        )

    async def terminate(
        self,
        _connection: Any,
        *,
        binding: Any,
        status: CommandStatus,
        error_code: str,
        error_classification: str,
    ) -> SimpleNamespace:
        self.terminate_calls += 1
        assert binding is self.command.binding
        self.command = _durable_command(
            status=status,
            error_code=error_code,
            error_classification=error_classification,
        )
        return self.command

    async def finish_attempt(
        self,
        _connection: Any,
        attempt: Any,
        *,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> SimpleNamespace:
        self.finish_calls += 1
        assert attempt.attempt_id == self.attempt.attempt_id
        self.attempt = _durable_attempt(
            status=status,
            error_code=error_code,
            error_classification=error_classification,
        )
        return self.attempt


def _lease(
    *,
    renewed_at: datetime | None = None,
    revision: int = 1,
) -> LeaseRecord:
    renewal = renewed_at or datetime.now(timezone.utc)
    return LeaseRecord(
        thread_id=THREAD_ID,
        command_id="command-1",
        owner_id="owner-1",
        fencing_token=1,
        lease_expires_at=renewal + timedelta(seconds=30),
        acquired_at=renewal,
        renewed_at=renewal,
        released_at=None,
        cancelled_at=None,
        cancelled_by_command_id=None,
        revision=revision,
    )


def _execution(
    lease: LeaseRecord,
    *,
    deadline_at: datetime | None = None,
) -> GatewayExecution:
    return GatewayExecution(
        admission=SimpleNamespace(
            command=SimpleNamespace(
                deadline_at=deadline_at
                or datetime.now(timezone.utc) + timedelta(minutes=1)
            )
        ),
        attempt=_Attempt(),  # type: ignore[arg-type]
        lease=lease,
        fence=SimpleNamespace(
            thread_id=THREAD_ID,
            command_id="command-1",
            owner_id="owner-1",
            fencing_token=1,
        ),
    )


def _gateway(
    *,
    pool: _Pool,
    leases: _Leases,
    ledger: _Ledger,
) -> GraphCommandGateway:
    return GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        leases=leases,  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
        input_authorizer=object(),  # type: ignore[arg-type]
        acquire_timeout_seconds=0.01,
    )


def _finish_execution(
    command: SimpleNamespace,
    attempt: SimpleNamespace,
) -> GatewayExecution:
    admission = GatewayAdmission(
        command=SimpleNamespace(
            deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1)
        ),  # type: ignore[arg-type]
        binding=command.binding,  # type: ignore[arg-type]
        thread=SimpleNamespace(),  # type: ignore[arg-type]
        registry=SimpleNamespace(),  # type: ignore[arg-type]
        record=command,  # type: ignore[arg-type]
        action=AdmissionAction.ACQUIRE,
        created=True,
    )
    return replace(_execution(_lease()), admission=admission, attempt=attempt)


@pytest.mark.asyncio
async def test_transient_renewal_recovers_on_control_pool() -> None:
    initial = _lease()
    renewed = _lease(renewed_at=initial.renewed_at + timedelta(seconds=10), revision=2)
    leases = _Leases([PoolTimeout("pool busy"), renewed])
    ledger = _Ledger([])
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=ledger)

    result = await gateway.renew_execution(_execution(initial))

    assert result is renewed
    assert leases.calls == 2
    assert all(call["command_deadline_at"] for call in leases.renew_kwargs)


def _lease_observability_messages(
    caplog: pytest.LogCaptureFixture,
    *,
    logger_name: str,
) -> list[str]:
    return [
        record.getMessage()
        for record in caplog.records
        if record.name == logger_name and record.getMessage().startswith("graph_lease_")
    ]


def _assert_safe_lease_observability(messages: list[str]) -> None:
    assert messages
    joined = "\n".join(messages)
    for forbidden in (
        "PRIVATE_EXCEPTION_DETAIL",
        "postgresql://",
        "SELECT ",
        "request_hash=",
        "result_hash=",
        "payload=",
        "model_text=",
    ):
        assert forbidden not in joined
    assert all("monotonic_elapsed_ms=" in message for message in messages)
    assert all("exception_class=" in message for message in messages)
    allowed_fields = {
        "operation_stage",
        "thread_id",
        "command_id",
        "owner_id",
        "fencing_token",
        "input_lease_revision",
        "input_lease_renewed_at",
        "input_lease_expires_at",
        "output_lease_revision",
        "output_lease_renewed_at",
        "output_lease_expires_at",
        "monotonic_elapsed_ms",
        "exception_class",
    }
    for message in messages:
        event_name, *fields = message.split()
        assert event_name in {
            "graph_lease_renewal_stage_started",
            "graph_lease_renewal_stage_succeeded",
            "graph_lease_renewal_stage_failed",
            "graph_lease_renewal_stage_cancelled",
        }
        assert {field.split("=", 1)[0] for field in fields} == allowed_fields


@pytest.mark.asyncio
async def test_renewal_observability_records_successful_blocking_stages(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    initial = _lease(revision=3)
    renewed = _lease(
        renewed_at=initial.renewed_at + timedelta(seconds=10),
        revision=4,
    )
    gateway = _gateway(pool=_Pool(), leases=_Leases([renewed]), ledger=_Ledger([]))

    assert await gateway.renew_execution(_execution(initial)) is renewed

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    for stage in (
        "OPERATION",
        "CONTROL_POOL_ACQUIRE",
        "TRANSACTION_ENTER",
        "LEASE_SQL",
        "TRANSACTION_COMMIT",
        "CONTROL_POOL_RELEASE",
    ):
        assert f"operation_stage={stage}" in joined
    assert "input_lease_revision=3" in joined
    assert "output_lease_revision=4" in joined
    assert "graph_lease_renewal_stage_succeeded operation_stage=OPERATION" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_identifies_control_pool_acquire_failure(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    gateway = _gateway(
        pool=_Pool(connection_errors=[RuntimeError("PRIVATE_EXCEPTION_DETAIL")]),
        leases=_Leases([]),
        ledger=_Ledger([]),
    )

    with pytest.raises(RuntimeError, match="PRIVATE_EXCEPTION_DETAIL"):
        await gateway.renew_execution(_execution(_lease()))

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert (
        "graph_lease_renewal_stage_failed "
        "operation_stage=CONTROL_POOL_ACQUIRE" in joined
    )
    assert "exception_class=RuntimeError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_identifies_lease_sql_failure(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    gateway = _gateway(
        pool=_Pool(),
        leases=_Leases([GraphLeaseLostError("PRIVATE_EXCEPTION_DETAIL")]),
        ledger=_Ledger([]),
    )

    with pytest.raises(GraphLeaseLostError, match="PRIVATE_EXCEPTION_DETAIL"):
        await gateway.renew_execution(_execution(_lease()))

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert "graph_lease_renewal_stage_started operation_stage=LEASE_SQL" in joined
    assert "graph_lease_renewal_stage_failed operation_stage=LEASE_SQL" in joined
    assert "graph_lease_renewal_stage_started operation_stage=TRANSACTION_ROLLBACK" in joined
    assert (
        "graph_lease_renewal_stage_succeeded "
        "operation_stage=TRANSACTION_ROLLBACK" in joined
    )
    assert (
        "graph_lease_renewal_stage_succeeded "
        "operation_stage=CONTROL_POOL_RELEASE" in joined
    )
    assert "exception_class=GraphLeaseLostError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_identifies_transaction_commit_failure(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    renewed = _lease(revision=2)
    gateway = _gateway(
        pool=_Pool(
            transaction_exit_error=RuntimeError("PRIVATE_EXCEPTION_DETAIL")
        ),
        leases=_Leases([renewed]),
        ledger=_Ledger([]),
    )

    with pytest.raises(RuntimeError, match="PRIVATE_EXCEPTION_DETAIL"):
        await gateway.renew_execution(_execution(_lease()))

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert (
        "graph_lease_renewal_stage_failed "
        "operation_stage=TRANSACTION_COMMIT" in joined
    )
    assert "exception_class=RuntimeError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_distinguishes_rollback_failure(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    gateway = _gateway(
        pool=_Pool(
            transaction_exit_error=RuntimeError("PRIVATE_EXCEPTION_DETAIL")
        ),
        leases=_Leases([GraphLeaseLostError("PRIVATE_SQL_DETAIL")]),
        ledger=_Ledger([]),
    )

    with pytest.raises(RuntimeError, match="PRIVATE_EXCEPTION_DETAIL"):
        await gateway.renew_execution(_execution(_lease()))

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert "graph_lease_renewal_stage_failed operation_stage=LEASE_SQL" in joined
    assert (
        "graph_lease_renewal_stage_failed "
        "operation_stage=TRANSACTION_ROLLBACK" in joined
    )
    assert "exception_class=RuntimeError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_distinguishes_control_pool_release_failure(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    renewed = _lease(revision=2)
    gateway = _gateway(
        pool=_Pool(connection_exit_error=RuntimeError("PRIVATE_EXCEPTION_DETAIL")),
        leases=_Leases([renewed]),
        ledger=_Ledger([]),
    )

    with pytest.raises(RuntimeError, match="PRIVATE_EXCEPTION_DETAIL"):
        await gateway.renew_execution(_execution(_lease()))

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert (
        "graph_lease_renewal_stage_succeeded "
        "operation_stage=TRANSACTION_COMMIT" in joined
    )
    assert (
        "graph_lease_renewal_stage_failed "
        "operation_stage=CONTROL_POOL_RELEASE" in joined
    )
    assert "exception_class=RuntimeError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_keeps_missing_lease_fail_closed_after_suppression(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    gateway = _gateway(
        pool=_Pool(transaction_suppresses_error=True),
        leases=_Leases([GraphLeaseLostError("PRIVATE_SQL_DETAIL")]),
        ledger=_Ledger([]),
    )

    with pytest.raises(GraphContractError, match="lease renewal returned no lease"):
        await gateway.renew_execution(_execution(_lease()))

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert "graph_lease_renewal_stage_failed operation_stage=LEASE_SQL" in joined
    assert (
        "graph_lease_renewal_stage_succeeded "
        "operation_stage=CONTROL_POOL_RELEASE" in joined
    )
    assert "graph_lease_renewal_stage_failed operation_stage=OPERATION" in joined
    assert "exception_class=GraphContractError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_observability_marks_blocked_sql_cancellation(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger=gateway_module.__name__)
    renew_started = asyncio.Event()

    class BlockedRenewLeases(_Leases):
        def __init__(self) -> None:
            super().__init__([])

        async def renew(self, _connection: Any, **kwargs: Any) -> LeaseRecord:
            self.calls += 1
            self.renew_kwargs.append(kwargs)
            renew_started.set()
            await asyncio.Event().wait()
            raise AssertionError("blocked renewal unexpectedly resumed")

    gateway = _gateway(
        pool=_Pool(),
        leases=BlockedRenewLeases(),
        ledger=_Ledger([]),
    )
    task = asyncio.create_task(gateway.renew_execution(_execution(_lease())))
    await asyncio.wait_for(renew_started.wait(), timeout=0.1)

    messages_before_cancel = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    assert messages_before_cancel[-1].startswith(
        "graph_lease_renewal_stage_started operation_stage=LEASE_SQL"
    )
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    messages = _lease_observability_messages(
        caplog,
        logger_name=gateway_module.__name__,
    )
    joined = "\n".join(messages)
    assert (
        "graph_lease_renewal_stage_started "
        "operation_stage=TRANSACTION_ROLLBACK" in joined
    )
    assert (
        "graph_lease_renewal_stage_cancelled operation_stage=LEASE_SQL" in joined
    )
    assert (
        "graph_lease_renewal_stage_succeeded "
        "operation_stage=TRANSACTION_ROLLBACK" in joined
    )
    assert (
        "graph_lease_renewal_stage_succeeded "
        "operation_stage=CONTROL_POOL_RELEASE" in joined
    )
    assert "graph_lease_renewal_stage_cancelled operation_stage=OPERATION" in joined
    assert "exception_class=CancelledError" in joined
    _assert_safe_lease_observability(messages)


@pytest.mark.asyncio
async def test_renewal_waits_past_old_retry_limit_and_cached_expiry_until_command_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(gateway_module, "_CONTROL_PLANE_RETRY_INITIAL_SECONDS", 0.001)
    monkeypatch.setattr(gateway_module, "_CONTROL_PLANE_RETRY_MAX_SECONDS", 0.001)
    stale_cached = _lease(
        renewed_at=datetime.now(timezone.utc) - timedelta(seconds=31),
    )
    renewed = _lease(revision=7)
    leases = _Leases(
        [
            LockNotAvailable("checkpoint row lock 1"),
            LockNotAvailable("checkpoint row lock 2"),
            LockNotAvailable("checkpoint row lock 3"),
            renewed,
        ]
    )
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=_Ledger([]))

    result = await gateway.renew_execution(
        _execution(
            stale_cached,
            deadline_at=datetime.now(timezone.utc) + timedelta(seconds=1),
        )
    )

    assert result is renewed
    assert leases.calls == 4


@pytest.mark.asyncio
async def test_renewal_contention_stops_at_command_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(gateway_module, "_CONTROL_PLANE_RETRY_INITIAL_SECONDS", 0.01)
    leases = _Leases([LockNotAvailable("checkpoint row remains locked")])
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=_Ledger([]))

    with pytest.raises(GraphCommandDeadlineError):
        await gateway.renew_execution(
            _execution(
                _lease(),
                deadline_at=datetime.now(timezone.utc) + timedelta(milliseconds=1),
            )
        )

    assert leases.calls == 1


@pytest.mark.asyncio
async def test_one_blocked_renew_is_cancelled_at_the_single_command_deadline_without_retry() -> (
    None
):
    renew_started = asyncio.Event()
    renew_cancelled = asyncio.Event()

    class BlockedRenewLeases(_Leases):
        def __init__(self) -> None:
            super().__init__([])

        async def renew(self, _connection: Any, **kwargs: Any) -> LeaseRecord:
            self.calls += 1
            self.renew_kwargs.append(kwargs)
            renew_started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                renew_cancelled.set()
                raise
            raise AssertionError("blocked renewal unexpectedly resumed")

    leases = BlockedRenewLeases()
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=_Ledger([]))
    command_deadline_at = datetime.now(timezone.utc) + timedelta(milliseconds=30)

    with pytest.raises(GraphCommandDeadlineError):
        await gateway.renew_execution(
            _execution(_lease(), deadline_at=command_deadline_at)
        )

    assert renew_started.is_set()
    assert renew_cancelled.is_set()
    assert leases.calls == 1
    assert leases.renew_kwargs == [
        {
            "thread_id": THREAD_ID,
            "command_id": "command-1",
            "owner_id": "owner-1",
            "fencing_token": 1,
            "command_deadline_at": command_deadline_at,
        }
    ]


@pytest.mark.asyncio
async def test_external_cancellation_of_blocked_renew_propagates_without_retry() -> None:
    renew_started = asyncio.Event()
    renew_cancelled = asyncio.Event()

    class BlockedRenewLeases(_Leases):
        def __init__(self) -> None:
            super().__init__([])

        async def renew(self, _connection: Any, **kwargs: Any) -> LeaseRecord:
            self.calls += 1
            self.renew_kwargs.append(kwargs)
            renew_started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                renew_cancelled.set()
                raise
            raise AssertionError("cancelled renewal unexpectedly resumed")

    leases = BlockedRenewLeases()
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=_Ledger([]))
    renew_task = asyncio.create_task(gateway.renew_execution(_execution(_lease())))
    await asyncio.wait_for(renew_started.wait(), timeout=0.1)

    renew_task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await renew_task

    assert renew_cancelled.is_set()
    assert leases.calls == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    (GraphLeaseLostError("exact fence displaced"), asyncio.CancelledError()),
)
async def test_renewal_does_not_retry_takeover_or_cancellation(
    failure: BaseException,
) -> None:
    leases = _Leases([failure])
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=_Ledger([]))

    with pytest.raises(type(failure)):
        await gateway.renew_execution(_execution(_lease()))

    assert leases.calls == 1


@pytest.mark.asyncio
async def test_renew_commit_ambiguity_is_safe_at_least_once_with_a_fixed_fence_window() -> None:
    initial = _lease(revision=0)
    first_renewal = _lease(
        renewed_at=initial.renewed_at + timedelta(seconds=10),
        revision=1,
    )
    second_renewal = _lease(
        renewed_at=initial.renewed_at + timedelta(seconds=20),
        revision=2,
    )
    leases = _Leases([first_renewal, second_renewal])
    gateway = _gateway(
        pool=_Pool(
            transaction_exit_errors=[
                OperationalError("connection lost after renew commit"),
                None,
            ]
        ),
        leases=leases,
        ledger=_Ledger([]),
    )

    result = await gateway.renew_execution(_execution(initial))

    assert leases.calls == 2
    assert result is second_renewal
    assert result.revision == 2
    assert result.fencing_token == initial.fencing_token
    assert result.lease_expires_at - result.renewed_at == timedelta(seconds=30)


@pytest.mark.asyncio
async def test_finish_retries_pre_mutation_lock_contention_then_aborts_exactly_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(gateway_module, "_CONTROL_PLANE_RETRY_INITIAL_SECONDS", 0.001)
    ledger = _FinishLedger()
    leases = _Leases([LockNotAvailable("lease row locked"), _lease(revision=2)])
    gateway = _gateway(pool=_Pool(), leases=leases, ledger=ledger)  # type: ignore[arg-type]
    execution = _finish_execution(ledger.command, ledger.attempt)

    result = await gateway.finish_execution_attempt(
        execution,
        status=AttemptStatus.FAILED,
        error_code="GRAPH_STREAM_INTERRUPTED",
        error_classification="STREAM_INTERRUPTED",
    )

    assert leases.calls == 2
    assert ledger.terminate_calls == 1
    assert ledger.finish_calls == 1
    assert result.admission.record.status is CommandStatus.ABORTED
    assert result.attempt.status is AttemptStatus.FAILED


@pytest.mark.asyncio
async def test_finish_ambiguous_commit_adopts_exact_abort_without_second_mutation() -> None:
    ledger = _FinishLedger()
    leases = _Leases([_lease(revision=2)])
    gateway = _gateway(
        pool=_Pool(
            transaction_exit_errors=[
                OperationalError("connection lost after exact abort commit"),
                None,
            ]
        ),
        leases=leases,
        ledger=ledger,  # type: ignore[arg-type]
    )
    execution = _finish_execution(ledger.command, ledger.attempt)

    result = await gateway.finish_execution_attempt(
        execution,
        status=AttemptStatus.FAILED,
        error_code="GRAPH_STREAM_INTERRUPTED",
        error_classification="STREAM_INTERRUPTED",
    )

    assert leases.calls == 1
    assert ledger.terminate_calls == 1
    assert ledger.finish_calls == 1
    assert result.admission.record is ledger.command
    assert result.attempt is ledger.attempt


@pytest.mark.parametrize("authority", ("requested_abort", "takeover", "result"))
def test_finish_adopts_only_exact_durable_terminal_authority(authority: str) -> None:
    if authority == "requested_abort":
        command = _durable_command(
            status=CommandStatus.ABORTED,
            error_code="GRAPH_STREAM_INTERRUPTED",
            error_classification="STREAM_INTERRUPTED",
        )
        attempt = _durable_attempt(
            status=AttemptStatus.FAILED,
            error_code="GRAPH_STREAM_INTERRUPTED",
            error_classification="STREAM_INTERRUPTED",
        )
    elif authority == "takeover":
        command = _durable_command(
            status=CommandStatus.ABORTED,
            error_code="GRAPH_LEASE_DISPLACED",
            error_classification="LEASE_EXPIRED_TAKEOVER",
        )
        attempt = _durable_attempt(
            status=AttemptStatus.LEASE_LOST,
            error_code="GRAPH_LEASE_DISPLACED",
            error_classification="LEASE_EXPIRED_TAKEOVER",
        )
    else:
        command = _durable_command(
            status=CommandStatus.RESULT_CHECKPOINTED,
            checkpoint_ns="evidence",
            checkpoint_id="checkpoint-terminal",
            result_ref="urn:test:result",
            result_hash="a" * 64,
        )
        attempt = _durable_attempt(status=AttemptStatus.COMPLETED)
    execution = _finish_execution(command, attempt)

    adopted = GraphCommandGateway._completed_attempt_abort_adoption(  # noqa: SLF001
        execution,
        command=command,
        attempt=attempt,
        status=AttemptStatus.FAILED,
        error_code="GRAPH_STREAM_INTERRUPTED",
        error_classification="STREAM_INTERRUPTED",
    )

    assert adopted == (command, attempt)


@pytest.mark.parametrize("mismatch", ("attempt_fence", "result_binding"))
def test_finish_rejects_malformed_or_mismatched_durable_terminal_authority(
    mismatch: str,
) -> None:
    command = _durable_command(
        status=CommandStatus.RESULT_CHECKPOINTED,
        checkpoint_ns="evidence",
        checkpoint_id="checkpoint-terminal",
        result_ref="urn:test:result",
        result_hash=(None if mismatch == "result_binding" else "a" * 64),
    )
    attempt = _durable_attempt(status=AttemptStatus.COMPLETED)
    if mismatch == "attempt_fence":
        attempt.fencing_token = 2
    execution = _finish_execution(command, attempt)

    with pytest.raises(GraphCommandStateError, match="attempt fence|incomplete terminal binding"):
        GraphCommandGateway._completed_attempt_abort_adoption(  # noqa: SLF001
            execution,
            command=command,
            attempt=attempt,
            status=AttemptStatus.FAILED,
            error_code="GRAPH_STREAM_INTERRUPTED",
            error_classification="STREAM_INTERRUPTED",
        )


@pytest.mark.asyncio
async def test_provider_intent_retries_only_before_durable_mutation_and_invokes_provider_once() -> (
    None
):
    initial = _lease()
    ledger = _Ledger([_Attempt(provider_call_count=1)])
    gateway = _gateway(
        pool=_Pool(connection_errors=[PoolTimeout("control pool busy"), None]),
        leases=_Leases([]),
        ledger=ledger,
    )
    provider_calls = 0

    async def provider_request() -> None:
        nonlocal provider_calls
        await gateway.record_provider_call(_execution(initial))
        provider_calls += 1

    await provider_request()

    assert ledger.provider_calls == 1
    assert provider_calls == 1


@pytest.mark.asyncio
async def test_expired_lease_does_not_retry_or_start_provider() -> None:
    expired = _lease(renewed_at=datetime.now(timezone.utc) - timedelta(seconds=31))
    ledger = _Ledger([])
    gateway = _gateway(
        pool=_Pool(connection_errors=[PoolTimeout("control pool busy")]),
        leases=_Leases([]),
        ledger=ledger,
    )
    provider_calls = 0

    async def provider_request() -> None:
        nonlocal provider_calls
        await gateway.record_provider_call(_execution(expired))
        provider_calls += 1

    with pytest.raises(PoolTimeout):
        await provider_request()

    assert ledger.provider_calls == 0
    assert provider_calls == 0


@pytest.mark.asyncio
async def test_renewed_lease_keeps_old_provider_recorder_snapshot_retryable() -> None:
    old = _lease(renewed_at=datetime.now(timezone.utc) - timedelta(seconds=31))
    renewed = _lease(revision=2)
    leases = _Leases([renewed])
    ledger = _Ledger([_Attempt(provider_call_count=1)])
    gateway = _gateway(
        pool=_Pool(
            connection_errors=[
                None,
                PoolTimeout("control pool busy"),
                None,
            ]
        ),
        leases=leases,
        ledger=ledger,
    )
    execution = _execution(old)

    assert await gateway.renew_execution(execution) is renewed
    result = await gateway.record_provider_call(execution)

    assert ledger.provider_calls == 1
    assert result.lease is renewed


@pytest.mark.asyncio
async def test_ambiguous_commit_does_not_repeat_provider_intent_or_start_provider() -> None:
    initial = _lease()
    ledger = _Ledger([_Attempt(provider_call_count=1)])
    gateway = _gateway(
        pool=_Pool(transaction_exit_error=OperationalError("connection lost after commit")),
        leases=_Leases([]),
        ledger=ledger,
    )
    provider_calls = 0

    async def provider_request() -> None:
        nonlocal provider_calls
        await gateway.record_provider_call(_execution(initial))
        provider_calls += 1

    with pytest.raises(OperationalError):
        await provider_request()

    assert ledger.provider_calls == 1
    assert provider_calls == 0


@pytest.mark.asyncio
async def test_control_pool_remains_usable_while_checkpoint_pool_is_occupied() -> None:
    initial = _lease()
    renewed = _lease(renewed_at=initial.renewed_at + timedelta(seconds=10), revision=2)
    checkpoint_pool = _Pool()
    control_pool = _Pool()
    runtime = SimpleNamespace(pool=checkpoint_pool, control_pool=control_pool)
    gateway = _gateway(
        pool=runtime.control_pool,
        leases=_Leases([renewed]),
        ledger=_Ledger([]),
    )

    async with checkpoint_pool.connection(timeout=0.01):
        assert await gateway.renew_execution(_execution(initial)) is renewed

    assert checkpoint_pool.connection_calls == 1
    assert control_pool.connection_calls == 1


@pytest.mark.asyncio
async def test_finish_execution_clears_latest_lease_cache() -> None:
    lease = _lease()
    binding = SimpleNamespace(thread_id=THREAD_ID, command_id="command-1")
    command = SimpleNamespace()
    record = SimpleNamespace(binding=binding, status=CommandStatus.COMPLETED)
    admission = GatewayAdmission(
        command=command,  # type: ignore[arg-type]
        binding=binding,  # type: ignore[arg-type]
        thread=SimpleNamespace(),  # type: ignore[arg-type]
        registry=SimpleNamespace(),  # type: ignore[arg-type]
        record=record,  # type: ignore[arg-type]
        action=AdmissionAction.ACQUIRE,
        created=True,
    )
    execution = replace(_execution(lease), admission=admission)

    class _FinishLedger(_Ledger):
        async def load(self, _connection: Any, **_kwargs: Any) -> Any:
            return record

        def require_same_binding(self, _actual: Any, _expected: Any) -> None:
            return None

    gateway = _gateway(
        pool=_Pool(),
        leases=_Leases([lease]),
        ledger=_FinishLedger([]),
    )
    gateway._remember_lease(execution, lease)

    await gateway.finish_execution_attempt(
        execution,
        status=AttemptStatus.FAILED,
        error_code="STREAM_INTERRUPTED",
        error_classification="TEST",
    )

    assert gateway._latest_leases == {}


@pytest.mark.asyncio
async def test_real_lease_loss_does_not_start_provider() -> None:
    initial = _lease()
    ledger = _Ledger([GraphLeaseLostError()])
    gateway = _gateway(pool=_Pool(), leases=_Leases([]), ledger=ledger)
    provider_calls = 0

    async def provider_request() -> None:
        nonlocal provider_calls
        await gateway.record_provider_call(_execution(initial))
        provider_calls += 1

    with pytest.raises(GraphLeaseLostError):
        await provider_request()

    assert ledger.provider_calls == 1
    assert provider_calls == 0


@pytest.mark.asyncio
async def test_hearing_cold_start_flushes_attempt_before_prefetch_and_reaches_one_terminal() -> None:
    from app.api.graph_stream_service import (
        ExactShadowExecutorRegistry,
        GatewayBackedGraphCommandStreamService,
        GraphStreamAdmissionGate,
    )

    class StreamGateway:
        def __init__(self, *, renewal_failure: BaseException | None = None) -> None:
            self.renewal_failure = renewal_failure
            self.renew_started = asyncio.Event()
            self.finished = 0
            self.cleaned = 0

        async def renew_execution(self, execution: GatewayExecution) -> LeaseRecord:
            self.renew_started.set()
            if self.renewal_failure is not None:
                raise self.renewal_failure
            renewed_at = datetime.now(timezone.utc)
            return replace(
                execution.lease,
                renewed_at=renewed_at,
                lease_expires_at=renewed_at + timedelta(seconds=30),
                revision=execution.lease.revision + 1,
            )

        async def finish_execution_attempt(
            self,
            execution: GatewayExecution,
            **_kwargs: Any,
        ) -> GatewayExecution:
            self.finished += 1
            return execution

        def cleanup_execution_lease(self, _execution: GatewayExecution) -> None:
            self.cleaned += 1

    async def service(gateway: StreamGateway) -> GatewayBackedGraphCommandStreamService:
        gate = GraphStreamAdmissionGate()
        await gate.start()
        return GatewayBackedGraphCommandStreamService(
            gateway=gateway,  # type: ignore[arg-type]
            executors=ExactShadowExecutorRegistry(),
            owner_id="hearing-cold-start-owner",
            admission_gate=gate,
            lease_renewal_seconds=0.001,
        )

    execution = _execution(_lease())
    checkpoints: list[str] = []
    durable_results: dict[str, str] = {}
    provider_calls = 0
    command_id = execution.fence.command_id
    result_hash = "7" * 64

    async def collect_once(
        stream_service: GatewayBackedGraphCommandStreamService,
        gateway: StreamGateway,
    ) -> list[str]:
        first_frame_flushed = asyncio.Event()

        async def hearing_no_provider_source():
            nonlocal provider_calls
            yield SimpleNamespace(event_type="attempt_started")
            if not first_frame_flushed.is_set():
                raise GraphLeaseLostError(
                    "disconnect cleanup won before attempt_started was observable"
                )
            # Scale the observed ten-second cold compile while preserving its ordering:
            # no provider call, then the first heartbeat and the deterministic graph.
            import time

            time.sleep(0.01)
            await asyncio.wait_for(gateway.renew_started.wait(), timeout=0.1)
            checkpoints.extend(
                [
                    "input",
                    "step:0:proposal",
                    "step:1:branch:to:project_proposal",
                    "step:2:terminal",
                ]
            )
            durable_results.setdefault(command_id, result_hash)
            yield SimpleNamespace(event_type="final", result_hash=result_hash)

        stream = stream_service._renewing_stream(
            hearing_no_provider_source(),
            execution,
        )
        observed = [(await anext(stream)).event_type]

        async def mark_first_frame_flushed() -> None:
            await asyncio.sleep(0)
            first_frame_flushed.set()

        await asyncio.create_task(mark_first_frame_flushed())
        observed.extend([event.event_type async for event in stream])
        return observed

    healthy_gateway = StreamGateway()
    healthy_service = await service(healthy_gateway)
    assert await collect_once(healthy_service, healthy_gateway) == [
        "attempt_started",
        "final",
    ]
    assert await collect_once(healthy_service, healthy_gateway) == [
        "attempt_started",
        "final",
    ]
    assert checkpoints == [
        "input",
        "step:0:proposal",
        "step:1:branch:to:project_proposal",
        "step:2:terminal",
    ] * 2
    assert durable_results == {command_id: result_hash}
    assert provider_calls == 0
    assert healthy_gateway.finished == 0

    displaced_gateway = StreamGateway(
        renewal_failure=GraphLeaseLostError("lease was displaced before terminal")
    )
    displaced_service = await service(displaced_gateway)

    async def blocked_source():
        yield SimpleNamespace(event_type="attempt_started")
        await asyncio.Event().wait()

    displaced_stream = displaced_service._renewing_stream(
        blocked_source(),
        execution,
    )
    assert (await anext(displaced_stream)).event_type == "attempt_started"
    with pytest.raises(GraphLeaseLostError, match="displaced before terminal"):
        await anext(displaced_stream)
    assert displaced_gateway.finished == 1

    failing_gateway = StreamGateway()
    failing_service = await service(failing_gateway)

    async def failing_source():
        yield SimpleNamespace(event_type="attempt_started")
        raise RuntimeError("arbitrary Hearing source failure")

    failing_stream = failing_service._renewing_stream(failing_source(), execution)
    assert (await anext(failing_stream)).event_type == "attempt_started"
    with pytest.raises(RuntimeError, match="arbitrary Hearing source failure"):
        await anext(failing_stream)
    assert failing_gateway.finished == 1
    assert durable_results == {command_id: result_hash}
    assert provider_calls == 0
