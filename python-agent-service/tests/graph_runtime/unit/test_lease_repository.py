from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.graph_runtime.errors import (
    GraphContractError,
    GraphLeaseLostError,
    GraphLeaseUnavailableError,
)
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
    displaced_command_id: str | None = None,
    displaced_owner_id: str | None = None,
    displaced_fencing_token: int | None = None,
) -> dict[str, Any]:
    row = {
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
    if displaced_command_id is not None:
        row.update(
            {
                "displaced_command_id": displaced_command_id,
                "displaced_owner_id": displaced_owner_id,
                "displaced_fencing_token": displaced_fencing_token,
            }
        )
    return row


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
    connection = _Connection(
        [
            None,
            _row(
                command_id="command-2",
                owner_id="worker-2",
                fencing_token=2,
                displaced_command_id="command-1",
                displaced_owner_id="worker-1",
                displaced_fencing_token=1,
            ),
        ]
    )

    acquisition = await PostgresLeaseRepository().acquire(
        connection,
        thread_id=THREAD,
        command_id="command-2",
        owner_id="worker-2",
    )

    assert acquisition.kind is LeaseAcquisitionKind.TAKEOVER
    assert acquisition.lease.fencing_token == 2
    assert acquisition.displaced is not None
    assert acquisition.displaced.command_id == "command-1"
    assert acquisition.displaced.owner_id == "worker-1"
    assert acquisition.displaced.fencing_token == 1
    takeover_sql = connection.calls[1][0]
    assert "for update" in takeover_sql
    assert "fencing_token = lease.fencing_token + 1" in takeover_sql
    assert "lease.lease_expires_at <= db_clock.now" in takeover_sql
    assert "lease.cancelled_at is not null" in takeover_sql
    assert "lease.released_at is not null" in takeover_sql
    assert connection.calls[1][1] == (THREAD, "command-2", "worker-2")


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
async def test_renewal_requires_exact_command_deadline_after_lock_and_before_database_clock() -> (
    None
):
    connection = _Connection([_row()])
    command_deadline_at = NOW + timedelta(minutes=2)

    renewed = await PostgresLeaseRepository().renew(
        connection,
        thread_id=THREAD,
        command_id="command-1",
        owner_id="worker-1",
        fencing_token=1,
        command_deadline_at=command_deadline_at,
    )

    assert renewed.fencing_token == 1
    sql = connection.calls[0][0]
    assert "join agent_graph_command command" in sql
    assert "command.thread_id = lease.thread_id" in sql
    assert "command.command_id = lease.command_id" in sql
    assert "command.deadline_at = %s" in sql
    assert "for update of lease" in sql
    assert "from locked_lease" in sql
    assert sql.index("for update of lease") < sql.index("clock_timestamp()")
    assert "lease.fencing_token = %s" in sql
    assert "lease.lease_expires_at > db_clock.now" in sql
    assert "locked_lease.command_deadline_at > db_clock.now" in sql
    assert "lease.cancelled_at is null" in sql
    assert connection.calls[0][1] == (
        THREAD,
        "command-1",
        "worker-1",
        1,
        command_deadline_at,
    )


@pytest.mark.asyncio
async def test_after_deadline_zero_row_is_immediate_lease_loss() -> None:
    connection = _Connection([None])
    command_deadline_at = NOW - timedelta(microseconds=1)

    with pytest.raises(GraphLeaseLostError):
        await PostgresLeaseRepository().renew(
            connection,
            thread_id=THREAD,
            command_id="command-1",
            owner_id="worker-stale",
            fencing_token=1,
            command_deadline_at=command_deadline_at,
        )

    assert len(connection.calls) == 1
    assert connection.calls[0][1][-1] is command_deadline_at


@pytest.mark.asyncio
async def test_renewal_omitting_authoritative_deadline_fails_at_signature() -> None:
    connection = _Connection([_row()])

    with pytest.raises(TypeError, match="command_deadline_at"):
        await PostgresLeaseRepository().renew(  # type: ignore[call-arg]
            connection,
            thread_id=THREAD,
            command_id="command-1",
            owner_id="worker-1",
            fencing_token=1,
        )

    assert connection.calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid_deadline", (None, NOW.replace(tzinfo=None), "2026-07-19"))
async def test_renewal_rejects_missing_or_non_authoritative_deadline_before_sql(
    invalid_deadline: Any,
) -> None:
    connection = _Connection([_row()])

    with pytest.raises(GraphContractError, match="timezone-aware command deadline"):
        await PostgresLeaseRepository().renew(
            connection,
            thread_id=THREAD,
            command_id="command-1",
            owner_id="worker-1",
            fencing_token=1,
            command_deadline_at=invalid_deadline,
        )

    assert connection.calls == []


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


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("row", "active"),
    [
        (_row(), True),
        (
            _row(
                expires_at=NOW + timedelta(seconds=1),
                database_now=NOW + timedelta(seconds=2),
            ),
            False,
        ),
        (_row(cancelled=True), False),
        (_row(released=True), False),
    ],
)
async def test_recovery_inspection_locks_even_inactive_lease_at_database_clock(
    row: dict[str, Any],
    active: bool,
) -> None:
    connection = _Connection([row])

    inspection = await PostgresLeaseRepository().lock_for_recovery(
        connection,
        thread_id=THREAD,
    )

    assert inspection is not None
    assert inspection.active is active
    assert inspection.database_now == row["database_now"]
    assert "for update of lease" in connection.calls[0][0]


@pytest.mark.asyncio
async def test_recovery_inspection_allows_thread_without_a_lease() -> None:
    connection = _Connection([None])

    inspection = await PostgresLeaseRepository().lock_for_recovery(
        connection,
        thread_id=THREAD,
    )

    assert inspection is None
