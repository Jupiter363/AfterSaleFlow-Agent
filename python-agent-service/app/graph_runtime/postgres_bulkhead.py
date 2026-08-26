"""PostgreSQL-owned durable fair admission for Graph fan-out work."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta
from hashlib import sha256
from math import isfinite
from typing import Any, Final
from uuid import uuid4

from app.graph_runtime.bulkhead import GraphBulkheadScope, GraphPermitFenceContext
from app.graph_runtime.errors import (
    GraphBulkheadClosedError,
    GraphBulkheadConfigurationError,
    GraphBulkheadSaturatedError,
    GraphBulkheadTimeoutError,
    GraphContractError,
    GraphLeaseLostError,
    GraphPermitBindingError,
    GraphPermitLostError,
    GraphPermitUnavailableError,
)
from app.graph_runtime.identity import THREAD_ID_PATTERN, _identifier
from app.graph_runtime.transaction_boundary import run_postgres_transaction


_MAX_WAIT_SECONDS: Final = 30.0
_PERMIT_STATUSES: Final = frozenset(
    {
        "QUEUED", "GRANTED", "RELEASED", "CANCELLED",
        "EXPIRED", "TIMED_OUT", "ORPHANED",
    }
)


@dataclass(frozen=True, slots=True)
class PostgresBulkheadConfig:
    global_limit: int
    tenant_limit: int
    room_limit: int
    global_queue_limit: int
    tenant_queue_limit: int
    room_queue_limit: int
    permit_lease_seconds: int
    wait_timeout_seconds: float
    poll_interval_seconds: float = 0.05
    acquire_timeout_seconds: float = 5.0

    def __post_init__(self) -> None:
        integers = (
            self.global_limit,
            self.tenant_limit,
            self.room_limit,
            self.global_queue_limit,
            self.tenant_queue_limit,
            self.room_queue_limit,
            self.permit_lease_seconds,
        )
        if any(not isinstance(value, int) or isinstance(value, bool) for value in integers):
            raise GraphContractError("PostgreSQL bulkhead limits must be integers")
        if not 1 <= self.room_limit <= self.tenant_limit <= self.global_limit:
            raise GraphContractError("PostgreSQL bulkhead concurrency limits are invalid")
        if self.room_limit > 8:
            raise GraphContractError("PostgreSQL room concurrency cannot exceed 8")
        if not 1 <= self.room_queue_limit <= self.tenant_queue_limit <= self.global_queue_limit:
            raise GraphContractError("PostgreSQL bulkhead queue limits are invalid")
        if not 5 <= self.permit_lease_seconds <= 30:
            raise GraphContractError("PostgreSQL permit lease must be between 5 and 30 seconds")
        for name, value in (
            ("wait_timeout_seconds", self.wait_timeout_seconds),
            ("poll_interval_seconds", self.poll_interval_seconds),
            ("acquire_timeout_seconds", self.acquire_timeout_seconds),
        ):
            if (
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not isfinite(float(value))
                or value <= 0
            ):
                raise GraphContractError(f"{name} must be a positive finite number")
        if self.wait_timeout_seconds > _MAX_WAIT_SECONDS:
            raise GraphContractError("PostgreSQL bulkhead wait cannot exceed 30 seconds")
        if self.poll_interval_seconds > self.wait_timeout_seconds:
            raise GraphContractError("PostgreSQL bulkhead poll interval exceeds wait timeout")

    @classmethod
    def signed_synthetic_defaults(cls) -> PostgresBulkheadConfig:
        return cls(
            global_limit=32,
            tenant_limit=16,
            room_limit=8,
            global_queue_limit=256,
            tenant_queue_limit=128,
            room_queue_limit=100,
            permit_lease_seconds=20,
            wait_timeout_seconds=5.0,
        )

    @property
    def renewal_interval_seconds(self) -> float:
        return max(1.0, self.permit_lease_seconds / 3.0)


@dataclass(frozen=True, slots=True)
class PostgresBulkheadReadinessReport:
    ready: bool
    code: str
    checks: Mapping[str, bool]


@dataclass(frozen=True, slots=True)
class PostgresBulkheadSnapshot:
    active_global: int
    queued_global: int
    active_tenants: int
    active_rooms: int
    oldest_wait_seconds: float
    status_counts: Mapping[str, int]


@dataclass(frozen=True, slots=True)
class PostgresPermitRecord:
    request_id: str
    scope: GraphBulkheadScope
    fence: GraphPermitFenceContext
    permit_owner_id: str
    permit_fencing_token: int
    status: str
    enqueued_at: datetime
    granted_at: datetime | None
    renewed_at: datetime | None
    lease_expires_at: datetime | None
    revision: int
    permit_count: int = 1

    def __post_init__(self) -> None:
        _identifier(self.request_id, "permit_request_id")
        _identifier(self.permit_owner_id, "permit_owner_id")
        if self.status not in _PERMIT_STATUSES:
            raise GraphContractError("durable permit status is invalid")
        if (
            not isinstance(self.permit_fencing_token, int)
            or isinstance(self.permit_fencing_token, bool)
            or self.permit_fencing_token < 0
        ):
            raise GraphContractError("permit fencing token is invalid")
        if self.status == "GRANTED" and (
            self.permit_fencing_token < 1
            or self.granted_at is None
            or self.renewed_at is None
            or self.lease_expires_at is None
        ):
            raise GraphContractError("granted permit lease is incomplete")
        if self.revision < 0:
            raise GraphContractError("permit revision is invalid")
        if (
            not isinstance(self.permit_count, int)
            or isinstance(self.permit_count, bool)
            or not 1 <= self.permit_count <= 8
        ):
            raise GraphContractError("permit count must be between 1 and 8")


class PostgresBulkheadPermit:
    """One durable permit with an independent, monotonically increasing fence."""

    def __init__(
        self,
        bulkhead: PostgresGraphFanoutBulkhead,
        record: PostgresPermitRecord,
        *,
        wait_seconds: float,
    ) -> None:
        if record.status != "GRANTED":
            raise GraphContractError("cannot construct an ungranted durable permit")
        self._bulkhead = bulkhead
        self._record = record
        self._released = False
        self.wait_seconds = wait_seconds

    @property
    def request_id(self) -> str:
        return self._record.request_id

    @property
    def scope(self) -> GraphBulkheadScope:
        return self._record.scope

    @property
    def fence(self) -> GraphPermitFenceContext:
        return self._record.fence

    @property
    def permit_fencing_token(self) -> int:
        return self._record.permit_fencing_token

    @property
    def permit_count(self) -> int:
        return self._record.permit_count

    @property
    def lease_expires_at(self) -> datetime:
        assert self._record.lease_expires_at is not None
        return self._record.lease_expires_at

    @property
    def renewal_due_at(self) -> datetime:
        assert self._record.renewed_at is not None
        return self._record.renewed_at + timedelta(seconds=self.renewal_interval_seconds)

    @property
    def renewal_interval_seconds(self) -> float:
        assert self._record.renewed_at is not None
        assert self._record.lease_expires_at is not None
        actual_window = max(
            0.0,
            (self._record.lease_expires_at - self._record.renewed_at).total_seconds(),
        )
        return max(
            0.001,
            min(self._bulkhead.config.renewal_interval_seconds, actual_window / 3.0),
        )

    @property
    def released(self) -> bool:
        return self._released

    async def renew(self) -> datetime:
        if self._released:
            raise GraphPermitLostError()
        self._record = await self._bulkhead._renew(self._record)
        return self.lease_expires_at

    async def validate_recovery(self) -> PostgresPermitRecord:
        if self._released:
            raise GraphPermitLostError()
        self._record = await self._bulkhead.validate_recovery(
            self.request_id,
            self.permit_fencing_token,
            self.fence,
            owner_id=self._record.permit_owner_id,
        )
        return self._record

    async def release(self) -> bool:
        if self._released:
            return False
        await self._bulkhead._finish(self._record, cancel=False)
        self._released = True
        return True

    async def cancel(self) -> bool:
        if self._released:
            return False
        await self._bulkhead._finish(self._record, cancel=True)
        self._released = True
        return True


class PostgresGraphFanoutBulkhead:
    """Cross-replica bulkhead whose queue, counters, leases, and fairness live in PostgreSQL."""

    def __init__(self, pool: Any, config: PostgresBulkheadConfig) -> None:
        if pool is None or not callable(getattr(pool, "connection", None)):
            raise GraphBulkheadConfigurationError("PostgreSQL bulkhead requires a pool")
        if not isinstance(config, PostgresBulkheadConfig):
            raise GraphBulkheadConfigurationError("PostgreSQL bulkhead config is required")
        self._pool = pool
        self._config = config
        self._opened = False
        self._draining = False
        self._closed = False
        self._operation_lock = asyncio.Lock()

    @property
    def config(self) -> PostgresBulkheadConfig:
        return self._config

    @property
    def accepting(self) -> bool:
        return self._opened and not self._draining and not self._closed

    async def open(self) -> PostgresBulkheadReadinessReport:
        async with self._operation_lock:
            if self._closed:
                raise GraphBulkheadClosedError()
            report = await self.check_readiness()
            if not report.ready:
                raise GraphBulkheadConfigurationError(report.code)
            self._opened = True
            return report

    async def check_readiness(self) -> PostgresBulkheadReadinessReport:
        checks: dict[str, bool] = {}
        try:
            async with asyncio.timeout(self._config.acquire_timeout_seconds):
                async def readiness_transaction(
                    connection: Any,
                ) -> PostgresBulkheadReadinessReport | None:
                    await connection.execute("set transaction read only")
                    config = await self._fetchone(
                        connection,
                        """
                        select enabled, room_limit, tenant_limit, global_limit,
                               room_queue_limit, tenant_queue_limit, global_queue_limit,
                               permit_lease_seconds
                          from agent_graph_fanout_config
                         where config_key = 'signed-synthetic'
                        """,
                    )
                    checks["configuration"] = bool(
                        config
                        and config["enabled"]
                        and (
                            config["room_limit"],
                            config["tenant_limit"],
                            config["global_limit"],
                            config["room_queue_limit"],
                            config["tenant_queue_limit"],
                            config["global_queue_limit"],
                            config["permit_lease_seconds"],
                        )
                        == (
                            self._config.room_limit,
                            self._config.tenant_limit,
                            self._config.global_limit,
                            self._config.room_queue_limit,
                            self._config.tenant_queue_limit,
                            self._config.global_queue_limit,
                            self._config.permit_lease_seconds,
                        )
                    )
                    if not checks["configuration"]:
                        return PostgresBulkheadReadinessReport(
                            False, "GRAPH_BULKHEAD_CONFIGURATION_MISMATCH", checks
                        )
                    routines = await self._fetchone(
                        connection,
                        """
                        select count(*) = 6 as complete
                          from pg_proc procedure
                          join pg_namespace namespace on namespace.oid = procedure.pronamespace
                         where namespace.nspname = current_schema()
                           and procedure.proname = any(%s::text[])
                        """,
                        (
                            [
                                "agent_graph_acquire_fanout_permit",
                                "agent_graph_acquire_fanout_permit_group",
                                "agent_graph_renew_fanout_permit",
                                "agent_graph_finish_fanout_permit",
                                "agent_graph_cancel_or_release_fanout_permit",
                                "agent_graph_validate_fanout_recovery",
                            ],
                        ),
                    )
                    checks["routines"] = bool(routines and routines["complete"])
                    if not checks["routines"]:
                        return PostgresBulkheadReadinessReport(
                            False, "GRAPH_BULKHEAD_ROUTINE_MISSING", checks
                        )
                    consistency = await self._fetchone(
                        connection,
                        """
                        select not exists (
                            select 1 from agent_graph_fanout_permit permit
                             where permit.status = 'GRANTED'
                               and permit.lease_expires_at > clock_timestamp()
                               and not exists (
                                   select 1 from agent_graph_lease lease
                                    where lease.thread_id = permit.thread_id
                                      and lease.command_id = permit.command_id
                                      and lease.owner_id = permit.graph_lease_owner_id
                                      and lease.fencing_token
                                          = permit.graph_lease_fencing_token
                                      and lease.released_at is null
                                      and lease.cancelled_at is null
                                      and lease.lease_expires_at
                                          >= permit.lease_expires_at
                               )
                        ) and not exists (
                            select 1 from agent_graph_fanout_permit permit
                             where permit.status = 'QUEUED'
                               and permit.wait_deadline_at > clock_timestamp()
                               and not exists (
                                   select 1 from agent_graph_fanout_tenant_turn tenant_turn
                                    where tenant_turn.tenant_key = permit.tenant_key
                               )
                        ) as consistent
                        """,
                    )
                    checks["active_graph_leases"] = bool(
                        consistency and consistency["consistent"]
                    )
                    if not checks["active_graph_leases"]:
                        return PostgresBulkheadReadinessReport(
                            False, "GRAPH_BULKHEAD_FENCE_INCONSISTENT", checks
                        )
                    return None

                result = await run_postgres_transaction(
                    self._pool,
                    timeout=self._config.acquire_timeout_seconds,
                    operation=readiness_transaction,
                    operation_name="bulkhead readiness",
                )
                if result is not None:
                    return result
        except TimeoutError:
            return PostgresBulkheadReadinessReport(False, "GRAPH_BULKHEAD_READINESS_TIMEOUT", checks)
        except Exception:
            return PostgresBulkheadReadinessReport(False, "GRAPH_BULKHEAD_READINESS_FAILED", checks)
        return PostgresBulkheadReadinessReport(True, "GRAPH_BULKHEAD_READY", checks)

    async def acquire(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        *,
        request_id: str | None = None,
        owner_id: str | None = None,
        timeout_seconds: float | None = None,
        takeover: bool = False,
        permit_count: int = 1,
    ) -> PostgresBulkheadPermit:
        self._require_accepting()
        if not isinstance(scope, GraphBulkheadScope):
            raise GraphContractError("durable bulkhead scope is required")
        if not isinstance(fence, GraphPermitFenceContext):
            raise GraphContractError("durable bulkhead Graph lease fence is required")
        selected_permit_count = _permit_count(permit_count)
        selected_request_id = request_id or _default_request_id(
            scope,
            fence,
            permit_count=selected_permit_count,
        )
        selected_owner_id = owner_id or _new_permit_owner_id()
        _identifier(selected_request_id, "permit_request_id")
        _identifier(selected_owner_id, "permit_owner_id")
        timeout = self._validated_timeout(timeout_seconds)
        loop = asyncio.get_running_loop()
        started = loop.time()
        try:
            while True:
                if selected_permit_count == 1:
                    record = await self._acquire_once(
                        scope,
                        fence,
                        request_id=selected_request_id,
                        owner_id=selected_owner_id,
                        timeout_seconds=timeout,
                        takeover=takeover,
                    )
                else:
                    record = await self._acquire_group_once(
                        scope,
                        fence,
                        request_id=selected_request_id,
                        owner_id=selected_owner_id,
                        timeout_seconds=timeout,
                        takeover=takeover,
                        permit_count=selected_permit_count,
                    )
                takeover = False
                if record.status == "GRANTED":
                    return PostgresBulkheadPermit(
                        self, record, wait_seconds=max(0.0, loop.time() - started)
                    )
                remaining = timeout - (loop.time() - started)
                if remaining <= 0 or record.status == "TIMED_OUT":
                    await self._cancel_or_release_best_effort(
                        selected_request_id, fence, selected_owner_id
                    )
                    raise GraphBulkheadTimeoutError()
                if record.status != "QUEUED":
                    raise GraphPermitUnavailableError()
                await asyncio.sleep(min(self._config.poll_interval_seconds, remaining))
        except asyncio.CancelledError:
            await asyncio.shield(
                self._cancel_or_release_best_effort(
                    selected_request_id, fence, selected_owner_id
                )
            )
            raise

    async def takeover(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        *,
        request_id: str,
        owner_id: str | None = None,
        timeout_seconds: float | None = None,
        permit_count: int = 1,
    ) -> PostgresBulkheadPermit:
        return await self.acquire(
            scope,
            fence,
            request_id=request_id,
            owner_id=owner_id,
            timeout_seconds=timeout_seconds,
            takeover=True,
            permit_count=permit_count,
        )

    @asynccontextmanager
    async def slot(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        **kwargs: Any,
    ) -> AsyncIterator[PostgresBulkheadPermit]:
        permit = await self.acquire(scope, fence, **kwargs)
        try:
            yield permit
        finally:
            await permit.release()

    async def validate_recovery(
        self,
        request_id: str,
        permit_fencing_token: int,
        fence: GraphPermitFenceContext,
        *,
        owner_id: str | None = None,
    ) -> PostgresPermitRecord:
        self._require_open()
        if owner_id is None:
            raise GraphContractError("durable permit recovery owner is required")
        selected_owner_id = owner_id
        return await self._call_record(
            """
            select result.* from agent_graph_validate_fanout_recovery(
                %s, %s, %s, %s, %s, %s, %s
            ) as result
            """,
            (
                request_id,
                permit_fencing_token,
                fence.thread_id,
                fence.command_id,
                fence.graph_lease_owner_id,
                fence.graph_lease_fencing_token,
                selected_owner_id,
            ),
        )

    async def snapshot(self) -> PostgresBulkheadSnapshot:
        self._require_open()
        async with self._pool.connection(
            timeout=self._config.acquire_timeout_seconds
        ) as connection:
            row = await self._fetchone(
                connection,
                """
                select coalesce(sum(permit_count) filter (
                           where status = 'GRANTED' and lease_expires_at > clock_timestamp()
                       ), 0) as active_global,
                       count(*) filter (
                           where status = 'QUEUED' and wait_deadline_at > clock_timestamp()
                       ) as queued_global,
                       count(distinct tenant_key) filter (
                           where status = 'GRANTED' and lease_expires_at > clock_timestamp()
                       ) as active_tenants,
                       count(distinct (tenant_key, room_key)) filter (
                           where status = 'GRANTED' and lease_expires_at > clock_timestamp()
                       ) as active_rooms,
                       coalesce(extract(epoch from clock_timestamp() - min(enqueued_at) filter (
                           where status = 'QUEUED' and wait_deadline_at > clock_timestamp()
                       )), 0.0) as oldest_wait_seconds
                  from agent_graph_fanout_permit
                """,
            )
            counts_cursor = await connection.execute(
                """
                select status, count(*) as count
                  from agent_graph_fanout_permit
                 group by status order by status
                """
            )
            counts = {item["status"]: item["count"] for item in await counts_cursor.fetchall()}
        if row is None:
            raise GraphPermitUnavailableError()
        return PostgresBulkheadSnapshot(
            active_global=row["active_global"],
            queued_global=row["queued_global"],
            active_tenants=row["active_tenants"],
            active_rooms=row["active_rooms"],
            oldest_wait_seconds=float(row["oldest_wait_seconds"]),
            status_counts=counts,
        )

    async def terminalize_command_permits(
        self,
        *,
        thread_id: str,
        command_id: str,
        frame_set_id: str,
    ) -> tuple[PostgresPermitRecord, ...]:
        """Release every exact provider-group permit before issuing a terminal receipt."""

        self._require_open()
        if THREAD_ID_PATTERN.fullmatch(thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 ID")
        _identifier(command_id, "command_id")
        _identifier(frame_set_id, "frame_set_id")
        terminal_statuses = {"RELEASED", "CANCELLED", "EXPIRED", "TIMED_OUT", "ORPHANED"}

        async def terminalize_transaction(
            connection: Any,
        ) -> tuple[PostgresPermitRecord, ...]:
            # Match every normal acquire/release path's lock order: global fanout advisory
            # lock first, then permit rows.  Reversing that order here would deadlock with
            # agent_graph_cancel_or_release_fanout_permit(), which takes the same advisory
            # lock before updating its row.
            await connection.execute(
                "select pg_advisory_xact_lock(hashtextextended(%s, 0))",
                ("agent-graph-fanout-admission",),
            )
            cursor = await connection.execute(
                """
                select *
                  from agent_graph_fanout_permit
                 where thread_id = %s
                   and command_id = %s
                   and item_key = %s
                 order by request_id
                 for update
                """,
                (thread_id, command_id, frame_set_id),
            )
            rows = await cursor.fetchall()
            if len(rows) > 32:
                raise GraphPermitBindingError(
                    "parallel command retained too many provider permits"
                )
            terminal: list[PostgresPermitRecord] = []
            for row in rows:
                current = _record_from_row(row)
                if current.status in {"QUEUED", "GRANTED"}:
                    updated = await (
                        await connection.execute(
                            """
                            select result.*
                              from agent_graph_cancel_or_release_fanout_permit(
                                  %s, %s, %s, %s, %s, %s
                              ) as result
                            """,
                            (
                                current.request_id,
                                current.fence.thread_id,
                                current.fence.command_id,
                                current.fence.graph_lease_owner_id,
                                current.fence.graph_lease_fencing_token,
                                current.permit_owner_id,
                            ),
                        )
                    ).fetchone()
                    if updated is None:
                        raise GraphPermitLostError()
                    current = _record_from_row(updated)
                if current.status not in terminal_statuses:
                    raise GraphPermitBindingError(
                        "parallel command provider permit is not terminal"
                    )
                terminal.append(current)
            return tuple(terminal)

        try:
            return await run_postgres_transaction(
                self._pool,
                timeout=self._config.acquire_timeout_seconds,
                operation=terminalize_transaction,
                operation_name="terminalize command permits",
            )
        except Exception as error:
            _raise_mapped_database_error(error)
            raise AssertionError("unreachable")

    async def drain(self) -> None:
        async with self._operation_lock:
            self._draining = True

    async def close(self) -> bool:
        async with self._operation_lock:
            if self._closed:
                return False
            self._draining = True
            self._closed = True
            self._opened = False
            return True

    async def _acquire_once(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        *,
        request_id: str,
        owner_id: str,
        timeout_seconds: float,
        takeover: bool,
    ) -> PostgresPermitRecord:
        return await self._call_record(
            """
            select result.* from agent_graph_acquire_fanout_permit(
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
            ) as result
            """,
            (
                request_id,
                scope.tenant_key,
                scope.room_key,
                scope.item_key,
                fence.thread_id,
                fence.command_id,
                fence.graph_lease_owner_id,
                fence.graph_lease_fencing_token,
                owner_id,
                timeout_seconds,
                takeover,
            ),
        )

    async def _acquire_group_once(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        *,
        request_id: str,
        owner_id: str,
        timeout_seconds: float,
        takeover: bool,
        permit_count: int,
    ) -> PostgresPermitRecord:
        return await self._call_record(
            """
            select result.* from agent_graph_acquire_fanout_permit_group(
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
            ) as result
            """,
            (
                request_id,
                scope.tenant_key,
                scope.room_key,
                scope.item_key,
                permit_count,
                fence.thread_id,
                fence.command_id,
                fence.graph_lease_owner_id,
                fence.graph_lease_fencing_token,
                owner_id,
                timeout_seconds,
                takeover,
            ),
        )

    async def _renew(self, record: PostgresPermitRecord) -> PostgresPermitRecord:
        return await self._call_record(
            """
            select result.* from agent_graph_renew_fanout_permit(
                %s, %s, %s, %s, %s, %s, %s
            ) as result
            """,
            _record_params(record),
        )

    async def _finish(self, record: PostgresPermitRecord, *, cancel: bool) -> None:
        await self._call_record(
            """
            select result.* from agent_graph_finish_fanout_permit(
                %s, %s, %s, %s, %s, %s, %s, %s
            ) as result
            """,
            (*_record_params(record), cancel),
        )

    async def _cancel_or_release_best_effort(
        self,
        request_id: str,
        fence: GraphPermitFenceContext,
        owner_id: str,
    ) -> None:
        try:
            await self._call_record(
                """
                select result.* from agent_graph_cancel_or_release_fanout_permit(
                    %s, %s, %s, %s, %s, %s
                ) as result
                """,
                (
                    request_id,
                    fence.thread_id,
                    fence.command_id,
                    fence.graph_lease_owner_id,
                    fence.graph_lease_fencing_token,
                    owner_id,
                ),
            )
        except (GraphPermitLostError, GraphLeaseLostError):
            pass

    async def _call_record(self, query: str, params: tuple[Any, ...]) -> PostgresPermitRecord:
        try:
            async def record_transaction(connection: Any) -> Mapping[str, Any] | None:
                return await self._fetchone(connection, query, params)

            row = await run_postgres_transaction(
                self._pool,
                timeout=self._config.acquire_timeout_seconds,
                operation=record_transaction,
                operation_name="bulkhead record",
            )
        except Exception as error:
            _raise_mapped_database_error(error)
        if row is None:
            raise GraphPermitUnavailableError()
        return _record_from_row(row)

    def _validated_timeout(self, timeout_seconds: float | None) -> float:
        selected = self._config.wait_timeout_seconds if timeout_seconds is None else timeout_seconds
        if (
            not isinstance(selected, (int, float))
            or isinstance(selected, bool)
            or not isfinite(float(selected))
            or selected <= 0
            or selected > self._config.wait_timeout_seconds
        ):
            raise GraphContractError("durable permit wait timeout is invalid")
        return float(selected)

    def _require_open(self) -> None:
        if not self._opened or self._closed:
            raise GraphBulkheadClosedError()

    def _require_accepting(self) -> None:
        self._require_open()
        if self._draining:
            raise GraphBulkheadClosedError()

    @staticmethod
    async def _fetchone(
        connection: Any,
        query: str,
        params: tuple[Any, ...] | None = None,
    ) -> Mapping[str, Any] | None:
        cursor = await connection.execute(query, params)
        return await cursor.fetchone()


def _default_request_id(
    scope: GraphBulkheadScope,
    fence: GraphPermitFenceContext,
    *,
    permit_count: int = 1,
) -> str:
    identity = [
        fence.thread_id,
        fence.command_id,
        scope.tenant_key,
        scope.room_key,
        scope.item_key,
    ]
    if permit_count != 1:
        identity.append(str(permit_count))
    digest = sha256(
        "\x00".join(identity).encode("ascii")
    ).hexdigest()
    return f"permit:{digest}"


def _new_permit_owner_id() -> str:
    return f"permit-worker:{uuid4().hex}"


def _record_params(record: PostgresPermitRecord) -> tuple[Any, ...]:
    return (
        record.request_id,
        record.permit_fencing_token,
        record.fence.thread_id,
        record.fence.command_id,
        record.fence.graph_lease_owner_id,
        record.fence.graph_lease_fencing_token,
        record.permit_owner_id,
    )


def _record_from_row(row: Mapping[str, Any]) -> PostgresPermitRecord:
    return PostgresPermitRecord(
        request_id=row["request_id"],
        scope=GraphBulkheadScope(
            tenant_key=row["tenant_key"],
            room_key=row["room_key"],
            item_key=row["item_key"],
        ),
        fence=GraphPermitFenceContext(
            thread_id=row["thread_id"],
            command_id=row["command_id"],
            graph_lease_owner_id=row["graph_lease_owner_id"],
            graph_lease_fencing_token=row["graph_lease_fencing_token"],
        ),
        permit_owner_id=row["permit_owner_id"],
        permit_fencing_token=row["permit_fencing_token"],
        status=row["status"],
        enqueued_at=row["enqueued_at"],
        granted_at=row["granted_at"],
        renewed_at=row["renewed_at"],
        lease_expires_at=row["lease_expires_at"],
        revision=row["revision"],
        permit_count=row.get("permit_count", 1),
    )


def _permit_count(value: int) -> int:
    if (
        not isinstance(value, int)
        or isinstance(value, bool)
        or not 1 <= value <= 8
    ):
        raise GraphContractError("permit count must be between 1 and 8")
    return value


def _raise_mapped_database_error(error: Exception) -> None:
    diagnostic = getattr(error, "diag", None)
    message = getattr(diagnostic, "message_primary", None) or str(error)
    if "GRAPH_FANOUT_GRAPH_LEASE_LOST" in message:
        raise GraphLeaseLostError() from error
    if "GRAPH_FANOUT_PERMIT_LOST" in message:
        raise GraphPermitLostError() from error
    if "GRAPH_FANOUT_BINDING_CONFLICT" in message:
        raise GraphPermitBindingError() from error
    if "GRAPH_FANOUT_TAKEOVER_OWNER_REUSED" in message:
        raise GraphPermitBindingError() from error
    if "GRAPH_FANOUT_SCOPE_FORGED" in message:
        raise GraphPermitBindingError() from error
    if "uq_agent_graph_fanout_logical_active" in message:
        raise GraphPermitBindingError() from error
    if "GRAPH_FANOUT_QUEUE_GLOBAL" in message:
        raise GraphBulkheadSaturatedError("global") from error
    if "GRAPH_FANOUT_QUEUE_TENANT" in message:
        raise GraphBulkheadSaturatedError("tenant") from error
    if "GRAPH_FANOUT_QUEUE_ROOM" in message:
        raise GraphBulkheadSaturatedError("room") from error
    if "GRAPH_FANOUT_PERMIT_TERMINAL" in message:
        raise GraphPermitUnavailableError() from error
    raise error


__all__ = [
    "PostgresBulkheadConfig",
    "PostgresBulkheadPermit",
    "PostgresBulkheadReadinessReport",
    "PostgresBulkheadSnapshot",
    "PostgresGraphFanoutBulkhead",
    "PostgresPermitRecord",
]
