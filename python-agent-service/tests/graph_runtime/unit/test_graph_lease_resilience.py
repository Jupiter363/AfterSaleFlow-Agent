from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

import pytest
from psycopg import OperationalError
from psycopg_pool import PoolTimeout

from app.graph_runtime.errors import GraphLeaseLostError
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
    def __init__(self, value: Any, *, exit_error: BaseException | None = None) -> None:
        self._value = value
        self._exit_error = exit_error

    async def __aenter__(self) -> Any:
        return self._value

    async def __aexit__(self, _type: Any, _value: Any, _traceback: Any) -> None:
        if self._exit_error is not None:
            raise self._exit_error


class _Connection:
    def __init__(self, *, transaction_exit_error: BaseException | None = None) -> None:
        self._transaction_exit_error = transaction_exit_error

    def transaction(self) -> _AsyncContext:
        return _AsyncContext(self, exit_error=self._transaction_exit_error)


class _Pool:
    def __init__(
        self,
        *,
        connection_errors: list[BaseException | None] | None = None,
        transaction_exit_error: BaseException | None = None,
        transaction_exit_errors: list[BaseException | None] | None = None,
    ) -> None:
        self._connection_errors = list(connection_errors or [])
        self._transaction_exit_error = transaction_exit_error
        self._transaction_exit_errors = (
            None if transaction_exit_errors is None else list(transaction_exit_errors)
        )
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
        return _AsyncContext(_Connection(transaction_exit_error=transaction_exit_error))


@dataclass(frozen=True)
class _Attempt:
    provider_call_count: int = 0


class _Leases:
    def __init__(self, outcomes: list[LeaseRecord | BaseException]) -> None:
        self._outcomes = list(outcomes)
        self.calls = 0

    async def renew(self, _connection: Any, **_kwargs: Any) -> LeaseRecord:
        self.calls += 1
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


def _execution(lease: LeaseRecord) -> GatewayExecution:
    return GatewayExecution(
        admission=SimpleNamespace(),
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
