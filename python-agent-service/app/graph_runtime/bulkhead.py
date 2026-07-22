"""Fail-closed, hierarchical admission control for Graph fan-out work."""

from __future__ import annotations

import asyncio
from collections import defaultdict, deque
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from math import isfinite
import re
from time import monotonic

from app.graph_runtime.errors import (
    GraphBulkheadClosedError,
    GraphBulkheadDisabledError,
    GraphBulkheadSaturatedError,
    GraphBulkheadTimeoutError,
    GraphContractError,
)


_OPAQUE_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MAX_ROOM_CONCURRENCY = 8


@dataclass(frozen=True, slots=True)
class GraphBulkheadConfig:
    """Explicit process-local capacity. The zero-value config denies all admission."""

    enabled: bool = False
    global_limit: int = 0
    tenant_limit: int = 0
    room_limit: int = 0
    global_queue_limit: int = 0
    tenant_queue_limit: int = 0
    room_queue_limit: int = 0
    wait_timeout_seconds: float = 0.0

    def __post_init__(self) -> None:
        if type(self.enabled) is not bool:
            raise GraphContractError("bulkhead enabled flag must be boolean")
        integer_fields = (
            self.global_limit,
            self.tenant_limit,
            self.room_limit,
            self.global_queue_limit,
            self.tenant_queue_limit,
            self.room_queue_limit,
        )
        if any(type(value) is not int for value in integer_fields):
            raise GraphContractError("bulkhead limits must be integers")
        if not self.enabled:
            if any(integer_fields) or self.wait_timeout_seconds != 0:
                raise GraphContractError("disabled bulkhead configuration must have zero capacity")
            return
        if not 1 <= self.room_limit <= self.tenant_limit <= self.global_limit:
            raise GraphContractError("bulkhead concurrency requires 1 <= room <= tenant <= global")
        if self.room_limit > _MAX_ROOM_CONCURRENCY:
            raise GraphContractError("bulkhead room concurrency cannot exceed 8")
        if not (0 <= self.room_queue_limit <= self.tenant_queue_limit <= self.global_queue_limit):
            raise GraphContractError("bulkhead queues require 0 <= room <= tenant <= global")
        if (
            isinstance(self.wait_timeout_seconds, bool)
            or not isinstance(self.wait_timeout_seconds, (int, float))
            or not isfinite(float(self.wait_timeout_seconds))
            or self.wait_timeout_seconds <= 0
        ):
            raise GraphContractError("bulkhead wait timeout must be positive")


@dataclass(frozen=True, slots=True)
class GraphBulkheadScope:
    """Opaque bounded keys; callers must not pass raw tenant, case, or user data."""

    tenant_key: str
    room_key: str

    def __post_init__(self) -> None:
        if not _OPAQUE_KEY.fullmatch(self.tenant_key):
            raise GraphContractError("bulkhead tenant key must be a bounded opaque identifier")
        if not _OPAQUE_KEY.fullmatch(self.room_key):
            raise GraphContractError("bulkhead room key must be a bounded opaque identifier")


@dataclass(frozen=True, slots=True)
class GraphTenantBulkheadSnapshot:
    tenant_key: str
    active: int
    queued: int


@dataclass(frozen=True, slots=True)
class GraphRoomBulkheadSnapshot:
    tenant_key: str
    room_key: str
    active: int
    queued: int


@dataclass(frozen=True, slots=True)
class GraphBulkheadCounters:
    grants: int
    immediate_grants: int
    queued_grants: int
    releases: int
    saturated_rejections: int
    timeout_rejections: int
    disabled_rejections: int
    closed_rejections: int
    cancelled_waiters: int
    total_wait_seconds: float
    max_wait_seconds: float


@dataclass(frozen=True, slots=True)
class GraphBulkheadSnapshot:
    enabled: bool
    closed: bool
    global_limit: int
    tenant_limit: int
    room_limit: int
    global_queue_limit: int
    tenant_queue_limit: int
    room_queue_limit: int
    active_global: int
    queued_global: int
    oldest_wait_seconds: float
    tenants: tuple[GraphTenantBulkheadSnapshot, ...]
    rooms: tuple[GraphRoomBulkheadSnapshot, ...]
    counters: GraphBulkheadCounters


@dataclass(slots=True)
class _MutableCounters:
    grants: int = 0
    immediate_grants: int = 0
    queued_grants: int = 0
    releases: int = 0
    saturated_rejections: int = 0
    timeout_rejections: int = 0
    disabled_rejections: int = 0
    closed_rejections: int = 0
    cancelled_waiters: int = 0
    total_wait_seconds: float = 0.0
    max_wait_seconds: float = 0.0

    def snapshot(self) -> GraphBulkheadCounters:
        return GraphBulkheadCounters(
            grants=self.grants,
            immediate_grants=self.immediate_grants,
            queued_grants=self.queued_grants,
            releases=self.releases,
            saturated_rejections=self.saturated_rejections,
            timeout_rejections=self.timeout_rejections,
            disabled_rejections=self.disabled_rejections,
            closed_rejections=self.closed_rejections,
            cancelled_waiters=self.cancelled_waiters,
            total_wait_seconds=self.total_wait_seconds,
            max_wait_seconds=self.max_wait_seconds,
        )


@dataclass(slots=True)
class _Waiter:
    sequence: int
    scope: GraphBulkheadScope
    enqueued_at: float
    future: asyncio.Future[GraphBulkheadPermit]


class GraphBulkheadPermit:
    """One idempotently releasable room/tenant/global capacity grant."""

    __slots__ = ("_bulkhead", "_released", "_token", "scope", "wait_seconds")

    def __init__(
        self,
        bulkhead: GraphFanoutBulkhead,
        *,
        token: int,
        scope: GraphBulkheadScope,
        wait_seconds: float,
    ) -> None:
        self._bulkhead = bulkhead
        self._token = token
        self._released = False
        self.scope = scope
        self.wait_seconds = wait_seconds

    @property
    def released(self) -> bool:
        return self._released

    async def release(self) -> bool:
        if self._released:
            return False
        released = await self._bulkhead._release(self._token)
        self._released = True
        return released


class GraphFanoutBulkhead:
    """Fair, bounded admission for independent Graph node work.

    All three capacity levels are granted atomically under one lock. Waiting tenants rotate after
    every grant, and each tenant admits its earliest waiter whose room currently has capacity.
    """

    def __init__(self, config: GraphBulkheadConfig | None = None) -> None:
        self._config = config or GraphBulkheadConfig()
        self._lock = asyncio.Lock()
        self._closed = False
        self._active_global = 0
        self._active_by_tenant: dict[str, int] = defaultdict(int)
        self._active_by_room: dict[tuple[str, str], int] = defaultdict(int)
        self._queued_global = 0
        self._queued_by_tenant: dict[str, int] = defaultdict(int)
        self._queued_by_room: dict[tuple[str, str], int] = defaultdict(int)
        self._tenant_waiters: dict[str, deque[_Waiter]] = {}
        self._tenant_rotation: deque[str] = deque()
        self._active_tokens: dict[int, GraphBulkheadScope] = {}
        self._next_sequence = 0
        self._next_token = 0
        self._counters = _MutableCounters()

    @property
    def config(self) -> GraphBulkheadConfig:
        return self._config

    async def acquire(
        self,
        scope: GraphBulkheadScope,
        *,
        timeout_seconds: float | None = None,
    ) -> GraphBulkheadPermit:
        loop = asyncio.get_running_loop()
        async with self._lock:
            if not self._config.enabled:
                self._counters.disabled_rejections += 1
                raise GraphBulkheadDisabledError()
            if self._closed:
                self._counters.closed_rejections += 1
                raise GraphBulkheadClosedError()
            if not isinstance(scope, GraphBulkheadScope):
                raise GraphContractError("bulkhead scope must be a validated scope")
            timeout = (
                self._config.wait_timeout_seconds if timeout_seconds is None else timeout_seconds
            )
            if (
                isinstance(timeout, bool)
                or not isinstance(timeout, (int, float))
                or not isfinite(float(timeout))
                or timeout <= 0
            ):
                raise GraphContractError("bulkhead acquire timeout must be positive")
            if timeout > self._config.wait_timeout_seconds:
                raise GraphContractError(
                    "bulkhead acquire timeout cannot exceed the configured maximum"
                )

            self._dispatch_locked()
            if self._can_grant_locked(scope):
                return self._new_permit_locked(scope, enqueued_at=None)

            saturated_scope = self._saturated_scope_locked(scope)
            if saturated_scope is not None:
                self._counters.saturated_rejections += 1
                raise GraphBulkheadSaturatedError(saturated_scope)

            self._next_sequence += 1
            waiter = _Waiter(
                sequence=self._next_sequence,
                scope=scope,
                enqueued_at=monotonic(),
                future=loop.create_future(),
            )
            self._enqueue_locked(waiter)

        try:
            async with asyncio.timeout(float(timeout)):
                return await asyncio.shield(waiter.future)
        except TimeoutError as error:
            await self._abandon_waiter(waiter, timed_out=True)
            raise GraphBulkheadTimeoutError() from error
        except asyncio.CancelledError:
            await self._abandon_waiter(waiter, timed_out=False)
            raise

    @asynccontextmanager
    async def slot(
        self,
        scope: GraphBulkheadScope,
        *,
        timeout_seconds: float | None = None,
    ) -> AsyncIterator[GraphBulkheadPermit]:
        permit = await self.acquire(scope, timeout_seconds=timeout_seconds)
        try:
            yield permit
        finally:
            await permit.release()

    async def close(self) -> None:
        async with self._lock:
            if self._closed:
                return
            self._closed = True
            for queue in self._tenant_waiters.values():
                for waiter in queue:
                    if not waiter.future.done():
                        waiter.future.set_exception(GraphBulkheadClosedError())
                        self._counters.closed_rejections += 1
            self._tenant_waiters.clear()
            self._tenant_rotation.clear()
            self._queued_global = 0
            self._queued_by_tenant.clear()
            self._queued_by_room.clear()

    async def snapshot(self) -> GraphBulkheadSnapshot:
        async with self._lock:
            now = monotonic()
            waiting_since = [
                waiter.enqueued_at for queue in self._tenant_waiters.values() for waiter in queue
            ]
            tenant_keys = sorted(set(self._active_by_tenant) | set(self._queued_by_tenant))
            room_keys = sorted(set(self._active_by_room) | set(self._queued_by_room))
            return GraphBulkheadSnapshot(
                enabled=self._config.enabled,
                closed=self._closed,
                global_limit=self._config.global_limit,
                tenant_limit=self._config.tenant_limit,
                room_limit=self._config.room_limit,
                global_queue_limit=self._config.global_queue_limit,
                tenant_queue_limit=self._config.tenant_queue_limit,
                room_queue_limit=self._config.room_queue_limit,
                active_global=self._active_global,
                queued_global=self._queued_global,
                oldest_wait_seconds=(max(0.0, now - min(waiting_since)) if waiting_since else 0.0),
                tenants=tuple(
                    GraphTenantBulkheadSnapshot(
                        tenant_key=tenant_key,
                        active=self._active_by_tenant.get(tenant_key, 0),
                        queued=self._queued_by_tenant.get(tenant_key, 0),
                    )
                    for tenant_key in tenant_keys
                    if self._active_by_tenant.get(tenant_key, 0)
                    or self._queued_by_tenant.get(tenant_key, 0)
                ),
                rooms=tuple(
                    GraphRoomBulkheadSnapshot(
                        tenant_key=room_key[0],
                        room_key=room_key[1],
                        active=self._active_by_room.get(room_key, 0),
                        queued=self._queued_by_room.get(room_key, 0),
                    )
                    for room_key in room_keys
                    if self._active_by_room.get(room_key, 0)
                    or self._queued_by_room.get(room_key, 0)
                ),
                counters=self._counters.snapshot(),
            )

    async def _release(self, token: int) -> bool:
        async with self._lock:
            released = self._release_token_locked(token)
            if released:
                self._dispatch_locked()
            return released

    async def _abandon_waiter(self, waiter: _Waiter, *, timed_out: bool) -> None:
        async with self._lock:
            removed = self._remove_waiter_locked(waiter)
            if not removed and waiter.future.done() and not waiter.future.cancelled():
                try:
                    permit = waiter.future.result()
                except Exception:
                    pass
                else:
                    self._release_token_locked(permit._token)
            if timed_out:
                self._counters.timeout_rejections += 1
            else:
                self._counters.cancelled_waiters += 1
            self._dispatch_locked()

    def _enqueue_locked(self, waiter: _Waiter) -> None:
        tenant_key = waiter.scope.tenant_key
        room_key = (tenant_key, waiter.scope.room_key)
        queue = self._tenant_waiters.get(tenant_key)
        if queue is None:
            queue = deque()
            self._tenant_waiters[tenant_key] = queue
            self._tenant_rotation.append(tenant_key)
        queue.append(waiter)
        self._queued_global += 1
        self._queued_by_tenant[tenant_key] += 1
        self._queued_by_room[room_key] += 1

    def _remove_waiter_locked(self, waiter: _Waiter) -> bool:
        tenant_key = waiter.scope.tenant_key
        queue = self._tenant_waiters.get(tenant_key)
        if queue is None:
            return False
        try:
            queue.remove(waiter)
        except ValueError:
            return False
        self._decrement_queued_locked(waiter.scope)
        if not queue:
            del self._tenant_waiters[tenant_key]
            self._remove_tenant_from_rotation_locked(tenant_key)
        return True

    def _dispatch_locked(self) -> None:
        while self._active_global < self._config.global_limit and self._tenant_rotation:
            granted_in_cycle = False
            cycle_size = len(self._tenant_rotation)
            for _ in range(cycle_size):
                tenant_key = self._tenant_rotation.popleft()
                queue = self._tenant_waiters[tenant_key]
                waiter = self._first_eligible_waiter_locked(tenant_key, queue)
                if waiter is not None:
                    queue.remove(waiter)
                    self._decrement_queued_locked(waiter.scope)
                    permit = self._new_permit_locked(
                        waiter.scope,
                        enqueued_at=waiter.enqueued_at,
                    )
                    waiter.future.set_result(permit)
                    granted_in_cycle = True
                if queue:
                    self._tenant_rotation.append(tenant_key)
                else:
                    del self._tenant_waiters[tenant_key]
                if self._active_global >= self._config.global_limit:
                    break
            if not granted_in_cycle:
                break

    def _first_eligible_waiter_locked(
        self,
        tenant_key: str,
        queue: deque[_Waiter],
    ) -> _Waiter | None:
        if self._active_by_tenant.get(tenant_key, 0) >= self._config.tenant_limit:
            return None
        for waiter in queue:
            room_key = (tenant_key, waiter.scope.room_key)
            if self._active_by_room.get(room_key, 0) < self._config.room_limit:
                return waiter
        return None

    def _can_grant_locked(self, scope: GraphBulkheadScope) -> bool:
        room_key = (scope.tenant_key, scope.room_key)
        return (
            self._active_global < self._config.global_limit
            and self._active_by_tenant.get(scope.tenant_key, 0) < self._config.tenant_limit
            and self._active_by_room.get(room_key, 0) < self._config.room_limit
        )

    def _new_permit_locked(
        self,
        scope: GraphBulkheadScope,
        *,
        enqueued_at: float | None,
    ) -> GraphBulkheadPermit:
        self._next_token += 1
        token = self._next_token
        self._active_tokens[token] = scope
        self._active_global += 1
        self._active_by_tenant[scope.tenant_key] += 1
        self._active_by_room[(scope.tenant_key, scope.room_key)] += 1
        wait_seconds = 0.0 if enqueued_at is None else max(0.0, monotonic() - enqueued_at)
        self._counters.grants += 1
        if enqueued_at is None:
            self._counters.immediate_grants += 1
        else:
            self._counters.queued_grants += 1
            self._counters.total_wait_seconds += wait_seconds
            self._counters.max_wait_seconds = max(
                self._counters.max_wait_seconds,
                wait_seconds,
            )
        return GraphBulkheadPermit(
            self,
            token=token,
            scope=scope,
            wait_seconds=wait_seconds,
        )

    def _release_token_locked(self, token: int) -> bool:
        scope = self._active_tokens.pop(token, None)
        if scope is None:
            return False
        self._active_global -= 1
        self._decrement_count(self._active_by_tenant, scope.tenant_key)
        self._decrement_count(
            self._active_by_room,
            (scope.tenant_key, scope.room_key),
        )
        self._counters.releases += 1
        return True

    def _decrement_queued_locked(self, scope: GraphBulkheadScope) -> None:
        self._queued_global -= 1
        self._decrement_count(self._queued_by_tenant, scope.tenant_key)
        self._decrement_count(
            self._queued_by_room,
            (scope.tenant_key, scope.room_key),
        )

    def _saturated_scope_locked(self, scope: GraphBulkheadScope) -> str | None:
        if self._queued_global >= self._config.global_queue_limit:
            return "global"
        if self._queued_by_tenant.get(scope.tenant_key, 0) >= self._config.tenant_queue_limit:
            return "tenant"
        room_key = (scope.tenant_key, scope.room_key)
        if self._queued_by_room.get(room_key, 0) >= self._config.room_queue_limit:
            return "room"
        return None

    def _remove_tenant_from_rotation_locked(self, tenant_key: str) -> None:
        try:
            self._tenant_rotation.remove(tenant_key)
        except ValueError:
            pass

    @staticmethod
    def _decrement_count(counts: dict, key) -> None:
        remaining = counts[key] - 1
        if remaining:
            counts[key] = remaining
        else:
            del counts[key]
