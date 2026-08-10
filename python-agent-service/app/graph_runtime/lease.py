"""Database-clock thread leases with monotonic fencing tokens."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum
from typing import Any, Final

from app.graph_runtime.errors import (
    GraphContractError,
    GraphLeaseLostError,
    GraphLeaseUnavailableError,
)
from app.graph_runtime.identity import THREAD_ID_PATTERN, _identifier


LEASE_DURATION: Final = timedelta(seconds=30)
LEASE_RENEWAL_INTERVAL: Final = timedelta(seconds=10)


class LeaseAcquisitionKind(StrEnum):
    FIRST = "FIRST"
    TAKEOVER = "TAKEOVER"
    IDEMPOTENT = "IDEMPOTENT"


@dataclass(frozen=True, slots=True)
class LeaseRecord:
    thread_id: str
    command_id: str
    owner_id: str
    fencing_token: int
    lease_expires_at: datetime
    acquired_at: datetime
    renewed_at: datetime
    released_at: datetime | None
    cancelled_at: datetime | None
    cancelled_by_command_id: str | None
    revision: int

    def __post_init__(self) -> None:
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("lease has an invalid opaque thread ID")
        _identifier(self.command_id, "command_id")
        _identifier(self.owner_id, "owner_id")
        if isinstance(self.fencing_token, bool) or self.fencing_token < 1:
            raise GraphContractError("fencing_token must start at one")
        if self.lease_expires_at <= self.renewed_at:
            raise GraphContractError("lease expiry must follow its database renewal time")
        if self.lease_expires_at - self.renewed_at > LEASE_DURATION:
            raise GraphContractError("lease window cannot exceed 30 seconds")
        if self.acquired_at > self.renewed_at:
            raise GraphContractError("lease acquisition cannot follow renewal")
        if (self.cancelled_at is None) != (self.cancelled_by_command_id is None):
            raise GraphContractError("lease cancellation fields must be paired")
        if self.released_at is not None and self.cancelled_at is not None:
            raise GraphContractError("lease cannot be both released and cancelled")
        if self.cancelled_by_command_id is not None:
            _identifier(self.cancelled_by_command_id, "cancelled_by_command_id")
        if self.revision < 0:
            raise GraphContractError("lease revision must be non-negative")

    @property
    def renewal_due_at(self) -> datetime:
        return self.renewed_at + LEASE_RENEWAL_INTERVAL


@dataclass(frozen=True, slots=True)
class LeaseDisplacement:
    command_id: str
    owner_id: str
    fencing_token: int

    def __post_init__(self) -> None:
        _identifier(self.command_id, "displaced_command_id")
        _identifier(self.owner_id, "displaced_owner_id")
        if isinstance(self.fencing_token, bool) or self.fencing_token < 1:
            raise GraphContractError("displaced_fencing_token must be positive")


@dataclass(frozen=True, slots=True)
class LeaseAcquisition:
    kind: LeaseAcquisitionKind
    lease: LeaseRecord
    displaced: LeaseDisplacement | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.kind, LeaseAcquisitionKind):
            raise GraphContractError("lease acquisition kind is invalid")
        if (self.kind is LeaseAcquisitionKind.TAKEOVER) != (self.displaced is not None):
            raise GraphContractError("only lease takeover may carry a displacement")
        if self.displaced is not None and (
            self.lease.fencing_token != self.displaced.fencing_token + 1
        ):
            raise GraphContractError("lease takeover must increment the displaced fence once")


@dataclass(frozen=True, slots=True)
class LeaseInspection:
    """One lease row locked against the exact database clock used for recovery."""

    lease: LeaseRecord
    database_now: datetime

    def __post_init__(self) -> None:
        if (
            not isinstance(self.database_now, datetime)
            or self.database_now.tzinfo is None
            or self.database_now.utcoffset() is None
        ):
            raise GraphContractError("lease inspection database clock is invalid")

    @property
    def active(self) -> bool:
        return (
            self.lease.released_at is None
            and self.lease.cancelled_at is None
            and self.lease.lease_expires_at > self.database_now
        )


LEASE_COLUMNS: Final[str] = """
thread_id, command_id, owner_id, fencing_token, lease_expires_at,
acquired_at, renewed_at, released_at, cancelled_at,
cancelled_by_command_id, lease_revision
"""

ACQUIRE_FIRST_SQL: Final[str] = f"""
with db_clock as materialized (select clock_timestamp() as now)
insert into agent_graph_lease (
    thread_id, command_id, owner_id, fencing_token, lease_expires_at,
    acquired_at, renewed_at, lease_revision
)
select %s, %s, %s, 1, now + interval '30 seconds', now, now, 0
  from db_clock
on conflict (thread_id) do nothing
returning {LEASE_COLUMNS}
"""

TAKEOVER_SQL: Final[str] = f"""
with db_clock as materialized (select clock_timestamp() as now),
displaced as materialized (
    select lease.thread_id as target_thread_id,
           lease.command_id as displaced_command_id,
           lease.owner_id as displaced_owner_id,
           lease.fencing_token as displaced_fencing_token
      from agent_graph_lease lease
     cross join db_clock
     where lease.thread_id = %s
       and (
           lease.lease_expires_at <= db_clock.now
           or lease.cancelled_at is not null
           or lease.released_at is not null
       )
       for update
)
update agent_graph_lease lease
   set command_id = %s,
       owner_id = %s,
       fencing_token = lease.fencing_token + 1,
       lease_expires_at = db_clock.now + interval '30 seconds',
       acquired_at = db_clock.now,
       renewed_at = db_clock.now,
       released_at = null,
       cancelled_at = null,
       cancelled_by_command_id = null,
       lease_revision = lease.lease_revision + 1
  from db_clock, displaced
 where lease.thread_id = displaced.target_thread_id
returning {LEASE_COLUMNS},
          displaced.displaced_command_id,
          displaced.displaced_owner_id,
          displaced.displaced_fencing_token
"""

OBSERVE_SQL: Final[str] = f"""
with db_clock as materialized (select clock_timestamp() as now)
select {LEASE_COLUMNS}, db_clock.now as database_now
  from agent_graph_lease lease
 cross join db_clock
 where lease.thread_id = %s
   and lease.released_at is null
   and lease.cancelled_at is null
   and lease.lease_expires_at > db_clock.now
"""

RENEW_SQL: Final[str] = f"""
with locked_lease as materialized (
    select lease.ctid as lease_ctid,
           command.deadline_at as command_deadline_at
      from agent_graph_lease lease
      join agent_graph_command command
        on command.thread_id = lease.thread_id
       and command.command_id = lease.command_id
     where lease.thread_id = %s
       and lease.command_id = %s
       and lease.owner_id = %s
       and lease.fencing_token = %s
       and lease.released_at is null
       and lease.cancelled_at is null
       and command.deadline_at = %s
     for update of lease
),
db_clock as materialized (
    -- This CTE depends on the locked row, so the authoritative clock is read
    -- after any checkpoint transaction releases the lease-row lock.  A renew
    -- that was admitted before the deadline but obtained the lock afterwards
    -- therefore cannot refresh the lease.
    select clock_timestamp() as now
      from locked_lease
)
update agent_graph_lease lease
   set renewed_at = db_clock.now,
       lease_expires_at = db_clock.now + interval '30 seconds',
       lease_revision = lease.lease_revision + 1
  from locked_lease, db_clock
 where lease.ctid = locked_lease.lease_ctid
   and lease.released_at is null
   and lease.cancelled_at is null
   and lease.lease_expires_at > db_clock.now
   and locked_lease.command_deadline_at > db_clock.now
returning {LEASE_COLUMNS}
"""

RELEASE_SQL: Final[str] = f"""
with db_clock as materialized (select clock_timestamp() as now)
update agent_graph_lease lease
   set released_at = db_clock.now,
       lease_revision = lease.lease_revision + 1
  from db_clock
 where lease.thread_id = %s
   and lease.command_id = %s
   and lease.owner_id = %s
   and lease.fencing_token = %s
   and lease.released_at is null
   and lease.cancelled_at is null
   and lease.lease_expires_at > db_clock.now
returning {LEASE_COLUMNS}
"""

LOCK_CURRENT_SQL: Final[str] = f"""
select {LEASE_COLUMNS}
  from agent_graph_lease
 where thread_id = %s
   and command_id = %s
   and owner_id = %s
   and fencing_token = %s
   and released_at is null
   and cancelled_at is null
   and lease_expires_at > clock_timestamp()
 for update
"""

LOCK_FOR_RECOVERY_SQL: Final[str] = f"""
with db_clock as materialized (select clock_timestamp() as now)
select {LEASE_COLUMNS}, db_clock.now as database_now
  from agent_graph_lease lease
 cross join db_clock
 where lease.thread_id = %s
 for update of lease
"""

CANCEL_SQL: Final[str] = f"""
with db_clock as materialized (select clock_timestamp() as now)
update agent_graph_lease lease
   set fencing_token = lease.fencing_token + 1,
       cancelled_at = db_clock.now,
       cancelled_by_command_id = %s,
       lease_revision = lease.lease_revision + 1
  from db_clock
 where lease.thread_id = %s
   and lease.command_id = %s
   and lease.fencing_token = %s
   and lease.released_at is null
   and lease.cancelled_at is null
returning {LEASE_COLUMNS}
"""


class PostgresLeaseRepository:
    """All changes are CAS operations; callers own the surrounding transaction."""

    async def acquire(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        owner_id: str,
    ) -> LeaseAcquisition:
        self._validate_key(thread_id, command_id, owner_id)
        row = await (
            await connection.execute(
                ACQUIRE_FIRST_SQL,
                (thread_id, command_id, owner_id),
            )
        ).fetchone()
        if row is not None:
            return LeaseAcquisition(LeaseAcquisitionKind.FIRST, self._from_row(row))

        row = await (
            await connection.execute(
                TAKEOVER_SQL,
                (thread_id, command_id, owner_id),
            )
        ).fetchone()
        if row is not None:
            return LeaseAcquisition(
                LeaseAcquisitionKind.TAKEOVER,
                self._from_row(row),
                LeaseDisplacement(
                    command_id=row["displaced_command_id"],
                    owner_id=row["displaced_owner_id"],
                    fencing_token=row["displaced_fencing_token"],
                ),
            )

        observed = await self.observe(connection, thread_id=thread_id)
        if observed is not None and (
            observed.thread_id,
            observed.command_id,
            observed.owner_id,
        ) == (thread_id, command_id, owner_id):
            return LeaseAcquisition(LeaseAcquisitionKind.IDEMPOTENT, observed)
        raise GraphLeaseUnavailableError()

    async def renew(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        owner_id: str,
        fencing_token: int,
        command_deadline_at: datetime,
    ) -> LeaseRecord:
        self._validate_key(thread_id, command_id, owner_id)
        self._validate_fence(fencing_token)
        if (
            not isinstance(command_deadline_at, datetime)
            or command_deadline_at.tzinfo is None
            or command_deadline_at.utcoffset() is None
        ):
            raise GraphContractError(
                "lease renewal requires an authoritative timezone-aware command deadline"
            )
        row = await (
            await connection.execute(
                RENEW_SQL,
                (
                    thread_id,
                    command_id,
                    owner_id,
                    fencing_token,
                    command_deadline_at,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphLeaseLostError()
        return self._from_row(row)

    async def lock_current(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        owner_id: str,
        fencing_token: int,
    ) -> LeaseRecord:
        self._validate_key(thread_id, command_id, owner_id)
        self._validate_fence(fencing_token)
        row = await (
            await connection.execute(
                LOCK_CURRENT_SQL,
                (thread_id, command_id, owner_id, fencing_token),
            )
        ).fetchone()
        if row is None:
            raise GraphLeaseLostError()
        return self._from_row(row)

    async def release(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        owner_id: str,
        fencing_token: int,
    ) -> LeaseRecord:
        self._validate_key(thread_id, command_id, owner_id)
        self._validate_fence(fencing_token)
        row = await (
            await connection.execute(
                RELEASE_SQL,
                (thread_id, command_id, owner_id, fencing_token),
            )
        ).fetchone()
        if row is None:
            raise GraphLeaseLostError()
        return self._from_row(row)

    async def lock_for_recovery(
        self,
        connection: Any,
        *,
        thread_id: str,
    ) -> LeaseInspection | None:
        """Lock the thread lease before recovery locks command and attempt rows."""

        if THREAD_ID_PATTERN.fullmatch(thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 ID")
        row = await (
            await connection.execute(LOCK_FOR_RECOVERY_SQL, (thread_id,))
        ).fetchone()
        if row is None:
            return None
        try:
            return LeaseInspection(
                lease=self._from_row(row),
                database_now=row["database_now"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphLeaseLostError("persisted recovery lease is invalid") from error

    async def cancel(
        self,
        connection: Any,
        *,
        thread_id: str,
        active_command_id: str,
        expected_fencing_token: int,
        cancellation_command_id: str,
    ) -> LeaseRecord:
        self._validate_key(thread_id, active_command_id, cancellation_command_id)
        self._validate_fence(expected_fencing_token)
        row = await (
            await connection.execute(
                CANCEL_SQL,
                (
                    cancellation_command_id,
                    thread_id,
                    active_command_id,
                    expected_fencing_token,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphLeaseLostError()
        return self._from_row(row)

    async def observe(self, connection: Any, *, thread_id: str) -> LeaseRecord | None:
        if THREAD_ID_PATTERN.fullmatch(thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 ID")
        row = await (
            await connection.execute(OBSERVE_SQL, (thread_id,))
        ).fetchone()
        return None if row is None else self._from_observed_row(row)

    @staticmethod
    def _validate_key(thread_id: str, command_id: str, owner_id: str) -> None:
        if THREAD_ID_PATTERN.fullmatch(thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 ID")
        _identifier(command_id, "command_id")
        _identifier(owner_id, "owner_id")

    @staticmethod
    def _validate_fence(fencing_token: int) -> None:
        if isinstance(fencing_token, bool) or fencing_token < 1:
            raise GraphContractError("fencing_token must be a positive integer")

    @staticmethod
    def _from_row(row: Mapping[str, Any]) -> LeaseRecord:
        try:
            return LeaseRecord(
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                owner_id=row["owner_id"],
                fencing_token=row["fencing_token"],
                lease_expires_at=row["lease_expires_at"],
                acquired_at=row["acquired_at"],
                renewed_at=row["renewed_at"],
                released_at=row["released_at"],
                cancelled_at=row["cancelled_at"],
                cancelled_by_command_id=row["cancelled_by_command_id"],
                revision=row["lease_revision"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphLeaseLostError("persisted lease is invalid") from error

    @classmethod
    def _from_observed_row(cls, row: Mapping[str, Any]) -> LeaseRecord:
        """Recheck activity against the exact database instant used by ``OBSERVE_SQL``."""

        try:
            database_now = row["database_now"]
            if (
                not isinstance(database_now, datetime)
                or database_now.tzinfo is None
                or database_now.utcoffset() is None
            ):
                raise TypeError("database_now must be timezone-aware")
            lease = cls._from_row(row)
            if (
                lease.released_at is not None
                or lease.cancelled_at is not None
                or lease.lease_expires_at <= database_now
            ):
                raise ValueError("observed lease is not active at the database clock")
            return lease
        except (GraphLeaseLostError, KeyError, TypeError, ValueError) as error:
            raise GraphLeaseLostError("observed lease is not database-clock active") from error
