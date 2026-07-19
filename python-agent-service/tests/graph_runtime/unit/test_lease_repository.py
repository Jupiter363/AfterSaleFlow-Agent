from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.graph_runtime.errors import GraphLeaseLostError, GraphLeaseUnavailableError
from app.graph_runtime.lease import (
    LEASE_DURATION,
    LEASE_RENEWAL_INTERVAL,
    LeaseAcquisitionKind,
    PostgresLeaseRepository,
)


NOW = datetime(2026, 7, 19, 8, 0, tzinfo=timezone.utc)
THREAD = f"grt.v1.{'2' * 32}"


def _row(
    *,
    command_id: str = "command-1",
    owner_id: str = "worker-1",
    fencing_token: int = 1,
    cancelled: bool = False,
    released: bool = False,
    expires_at: datetime | None = None,
    database_now: datetime = NOW,
) -> dict[str, Any]:
    return {
        "thread_id": THREAD,
        "command_id": command_id,
        "owner_id": owner_id,
        "fencing_token": fencing_token,
        "lease_expires_at": expires_at or NOW + timedelta(seconds=30),
        "acquired_at": NOW,
        "renewed_at": NOW,
        "released_at": NOW if released else None,
        "cancelled_at": NOW if cancelled else None,
        "cancelled_by_command_id": "cancel-command" if cancelled else None,
        "lease_revision": fencing_token - 1,
        "database_now": database_now,
    }


class _Cursor:
    def __init__(self, row: Any) -> None:
        self.row = row

    async def fetchone(self) -> Any:
        return self.row


class _Connection:
    def __init__(self, responses: list[Any]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, Any]] = []

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        self.calls.append((" ".join(query.split()).lower(), params))
        return _Cursor(self.responses.pop(0))


@pytest.mark.asyncio
async def test_first_lease_uses_database_clock_and_token_one() -> None:
    connection = _Connection([_row()])

    acquisition = await PostgresLeaseRepository().acquire(
        connection,
        thread_id=THREAD,
        command_id="command-1",
        owner_id="worker-1",
    )

    assert acquisition.kind is LeaseAcquisitionKind.FIRST
    assert acquisition.lease.fencing_token == 1
    assert acquisition.lease.lease_expires_at - acquisition.lease.renewed_at == LEASE_DURATION
    assert acquisition.lease.renewal_due_at == NOW + LEASE_RENEWAL_INTERVAL
    assert "clock_timestamp()" in connection.calls[0][0]
    assert "interval '30 seconds'" in connection.calls[0][0]


@pytest.mark.asyncio
async def test_expired_lease_takeover_is_database_cas_and_increments_fence() -> None:
    connection = _Connection([None, _row(command_id="command-2", owner_id="worker-2", fencing_token=2)])

    acquisition = await PostgresLeaseRepository().acquire(
        connection,
        thread_id=THREAD,
        command_id="command-2",
        owner_id="worker-2",
    )

    assert acquisition.kind is LeaseAcquisitionKind.TAKEOVER
    assert acquisition.lease.fencing_token == 2
    takeover_sql = connection.calls[1][0]
    assert "fencing_token = lease.fencing_token + 1" in takeover_sql
    assert "lease.lease_expires_at <= db_clock.now" in takeover_sql
    assert "lease.cancelled_at is not null" in takeover_sql
    assert "lease.released_at is not null" in takeover_sql


@pytest.mark.asyncio
async def test_active_same_owner_acquire_is_idempotent_without_new_token() -> None:
    connection = _Connection([None, None, _row()])

    acquisition = await PostgresLeaseRepository().acquire(
        connection,
        thread_id=THREAD,
        command_id="command-1",
        owner_id="worker-1",
    )

    assert acquisition.kind is LeaseAcquisitionKind.IDEMPOTENT
    assert acquisition.lease.fencing_token == 1
    observe_sql = connection.calls[2][0]
    assert "lease.released_at is null" in observe_sql
    assert "lease.cancelled_at is null" in observe_sql
    assert "lease.lease_expires_at > db_clock.now" in observe_sql


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "row",
    [
        _row(released=True),
        _row(cancelled=True),
        _row(expires_at=NOW),
    ],
)
async def test_idempotent_observation_rechecks_activity_at_database_clock(
    row: dict[str, Any],
) -> None:
    connection = _Connection([None, None, row])

    with pytest.raises(GraphLeaseLostError, match="database-clock active"):
        await PostgresLeaseRepository().acquire(
            connection,
            thread_id=THREAD,
            command_id="command-1",
            owner_id="worker-1",
        )


@pytest.mark.asyncio
async def test_active_other_owner_is_not_stealable() -> None:
    connection = _Connection([None, None, _row(owner_id="worker-current")])

    with pytest.raises(GraphLeaseUnavailableError):
        await PostgresLeaseRepository().acquire(
            connection,
            thread_id=THREAD,
            command_id="command-1",
            owner_id="worker-new",
        )


@pytest.mark.asyncio
async def test_renewal_requires_same_owner_token_and_unexpired_database_lease() -> None:
    connection = _Connection([_row()])

    renewed = await PostgresLeaseRepository().renew(
        connection,
        thread_id=THREAD,
        command_id="command-1",
        owner_id="worker-1",
        fencing_token=1,
    )

    assert renewed.fencing_token == 1
    sql = connection.calls[0][0]
    assert "lease.fencing_token = %s" in sql
    assert "lease.lease_expires_at > db_clock.now" in sql
    assert "lease.cancelled_at is null" in sql


@pytest.mark.asyncio
async def test_failed_renewal_is_immediate_lease_loss() -> None:
    connection = _Connection([None])

    with pytest.raises(GraphLeaseLostError):
        await PostgresLeaseRepository().renew(
            connection,
            thread_id=THREAD,
            command_id="command-1",
            owner_id="worker-stale",
            fencing_token=1,
        )


@pytest.mark.asyncio
async def test_release_is_owner_fence_cas_and_uses_database_clock() -> None:
    connection = _Connection([_row(released=True)])

    released = await PostgresLeaseRepository().release(
        connection,
        thread_id=THREAD,
        command_id="command-1",
        owner_id="worker-1",
        fencing_token=1,
    )

    assert released.released_at == NOW
    sql = connection.calls[0][0]
    assert "released_at = db_clock.now" in sql
    assert "lease.owner_id = %s" in sql
    assert "lease.fencing_token = %s" in sql
    assert "lease.lease_expires_at > db_clock.now" in sql


@pytest.mark.asyncio
async def test_cancellation_increments_fence_in_database() -> None:
    connection = _Connection([_row(fencing_token=2, cancelled=True)])

    cancelled = await PostgresLeaseRepository().cancel(
        connection,
        thread_id=THREAD,
        active_command_id="command-1",
        expected_fencing_token=1,
        cancellation_command_id="cancel-command",
    )

    assert cancelled.fencing_token == 2
    assert cancelled.cancelled_by_command_id == "cancel-command"
    assert "fencing_token = lease.fencing_token + 1" in connection.calls[0][0]


@pytest.mark.asyncio
async def test_fence_lock_is_same_transaction_ready_and_uses_db_expiry() -> None:
    connection = _Connection([_row()])

    locked = await PostgresLeaseRepository().lock_current(
        connection,
        thread_id=THREAD,
        command_id="command-1",
        owner_id="worker-1",
        fencing_token=1,
    )

    assert locked.fencing_token == 1
    assert "for update" in connection.calls[0][0]
    assert "lease_expires_at > clock_timestamp()" in connection.calls[0][0]
