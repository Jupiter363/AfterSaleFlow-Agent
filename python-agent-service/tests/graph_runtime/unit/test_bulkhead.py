from __future__ import annotations

import asyncio
from collections import defaultdict

import pytest
from hypothesis import given, settings, strategies as st

from app.graph_runtime.bulkhead import (
    GraphBulkheadConfig,
    GraphBulkheadScope,
    GraphFanoutBulkhead,
)
from app.graph_runtime.errors import (
    GraphBulkheadClosedError,
    GraphBulkheadDisabledError,
    GraphBulkheadSaturatedError,
    GraphBulkheadTimeoutError,
    GraphContractError,
)


def _config(
    *,
    global_limit: int = 2,
    tenant_limit: int = 1,
    room_limit: int = 1,
    global_queue_limit: int = 20,
    tenant_queue_limit: int = 10,
    room_queue_limit: int = 5,
    wait_timeout_seconds: float = 1.0,
) -> GraphBulkheadConfig:
    return GraphBulkheadConfig(
        enabled=True,
        global_limit=global_limit,
        tenant_limit=tenant_limit,
        room_limit=room_limit,
        global_queue_limit=global_queue_limit,
        tenant_queue_limit=tenant_queue_limit,
        room_queue_limit=room_queue_limit,
        wait_timeout_seconds=wait_timeout_seconds,
    )


def _scope(tenant: str, room: str) -> GraphBulkheadScope:
    return GraphBulkheadScope(tenant_key=tenant, room_key=room)


@st.composite
def _hierarchical_limits(draw) -> tuple[int, int, int]:
    room_limit = draw(st.integers(min_value=1, max_value=2))
    tenant_limit = draw(st.integers(min_value=room_limit, max_value=4))
    global_limit = draw(st.integers(min_value=tenant_limit, max_value=8))
    return global_limit, tenant_limit, room_limit


async def _wait_for_queue(bulkhead: GraphFanoutBulkhead, expected: int) -> None:
    for _ in range(100):
        if (await bulkhead.snapshot()).queued_global == expected:
            return
        await asyncio.sleep(0)
    raise AssertionError(f"bulkhead queue did not reach {expected}")


def test_configuration_and_keys_fail_closed() -> None:
    assert not GraphBulkheadConfig().enabled

    with pytest.raises(GraphContractError, match="zero capacity"):
        GraphBulkheadConfig(global_limit=1)
    with pytest.raises(GraphContractError, match="room <= tenant <= global"):
        _config(global_limit=1, tenant_limit=2)
    with pytest.raises(GraphContractError, match="cannot exceed 8"):
        _config(global_limit=9, tenant_limit=9, room_limit=9)
    with pytest.raises(GraphContractError, match="queues"):
        _config(global_queue_limit=1, tenant_queue_limit=2)
    with pytest.raises(GraphContractError, match="timeout"):
        _config(wait_timeout_seconds=0)
    with pytest.raises(GraphContractError, match="timeout"):
        _config(wait_timeout_seconds=float("nan"))
    with pytest.raises(GraphContractError, match="boolean"):
        GraphBulkheadConfig(enabled=1)  # type: ignore[arg-type]
    with pytest.raises(GraphContractError, match="tenant key"):
        _scope("raw tenant with spaces", "room-1")


@pytest.mark.asyncio
async def test_default_bulkhead_denies_admission_and_reports_rejection() -> None:
    bulkhead = GraphFanoutBulkhead()

    with pytest.raises(GraphBulkheadDisabledError) as rejected:
        await bulkhead.acquire(_scope("tenant-1", "room-1"))

    snapshot = await bulkhead.snapshot()
    assert rejected.value.code == "GRAPH_BULKHEAD_DISABLED"
    assert not snapshot.enabled
    assert snapshot.active_global == 0
    assert snapshot.counters.disabled_rejections == 1


@pytest.mark.asyncio
async def test_per_call_timeout_can_only_tighten_the_configured_maximum() -> None:
    bulkhead = GraphFanoutBulkhead(_config(wait_timeout_seconds=1))

    with pytest.raises(GraphContractError, match="configured maximum"):
        await bulkhead.acquire(
            _scope("tenant-1", "room-1"),
            timeout_seconds=2,
        )


@pytest.mark.asyncio
async def test_room_tenant_and_global_limits_are_granted_atomically() -> None:
    bulkhead = GraphFanoutBulkhead(_config(global_limit=3, tenant_limit=2, room_limit=1))
    room_one = await bulkhead.acquire(_scope("tenant-a", "room-1"))
    room_two = await bulkhead.acquire(_scope("tenant-a", "room-2"))
    other_tenant = await bulkhead.acquire(_scope("tenant-b", "room-1"))

    same_room = asyncio.create_task(bulkhead.acquire(_scope("tenant-a", "room-1")))
    tenant_blocked = asyncio.create_task(bulkhead.acquire(_scope("tenant-a", "room-3")))
    global_blocked = asyncio.create_task(bulkhead.acquire(_scope("tenant-b", "room-2")))
    await _wait_for_queue(bulkhead, 3)

    snapshot = await bulkhead.snapshot()
    assert snapshot.active_global == 3
    assert snapshot.queued_global == 3
    assert [(item.tenant_key, item.active, item.queued) for item in snapshot.tenants] == [
        ("tenant-a", 2, 2),
        ("tenant-b", 1, 1),
    ]

    await room_one.release()
    same_room_permit = await same_room
    assert not tenant_blocked.done()
    assert not global_blocked.done()

    await other_tenant.release()
    global_permit = await global_blocked
    assert not tenant_blocked.done()

    await room_two.release()
    tenant_permit = await tenant_blocked
    for permit in (same_room_permit, global_permit, tenant_permit):
        await permit.release()

    final = await bulkhead.snapshot()
    assert final.active_global == 0
    assert final.queued_global == 0
    assert final.counters.grants == final.counters.releases == 6


@pytest.mark.asyncio
async def test_waiting_tenants_are_round_robin_fair() -> None:
    bulkhead = GraphFanoutBulkhead(_config(global_limit=1, tenant_limit=1, room_limit=1))
    blocker = await bulkhead.acquire(_scope("tenant-z", "room-0"))
    completion_order: list[str] = []

    async def wait_and_record(tenant: str, room: str, label: str) -> None:
        permit = await bulkhead.acquire(_scope(tenant, room))
        completion_order.append(label)
        await permit.release()

    tasks = [
        asyncio.create_task(wait_and_record("tenant-a", "room-1", "a1")),
        asyncio.create_task(wait_and_record("tenant-a", "room-2", "a2")),
        asyncio.create_task(wait_and_record("tenant-b", "room-1", "b1")),
    ]
    await _wait_for_queue(bulkhead, 3)

    await blocker.release()
    await asyncio.gather(*tasks)

    assert completion_order == ["a1", "b1", "a2"]


@pytest.mark.asyncio
async def test_bounded_queues_reject_without_starting_untracked_work() -> None:
    bulkhead = GraphFanoutBulkhead(
        _config(
            global_limit=1,
            tenant_limit=1,
            room_limit=1,
            global_queue_limit=2,
            tenant_queue_limit=1,
            room_queue_limit=1,
        )
    )
    active = await bulkhead.acquire(_scope("tenant-a", "room-1"))
    tenant_waiter = asyncio.create_task(bulkhead.acquire(_scope("tenant-a", "room-2")))
    await _wait_for_queue(bulkhead, 1)

    with pytest.raises(GraphBulkheadSaturatedError) as tenant_rejected:
        await bulkhead.acquire(_scope("tenant-a", "room-3"))
    assert tenant_rejected.value.scope == "tenant"
    assert tenant_rejected.value.retryable

    global_waiter = asyncio.create_task(bulkhead.acquire(_scope("tenant-b", "room-1")))
    await _wait_for_queue(bulkhead, 2)
    with pytest.raises(GraphBulkheadSaturatedError) as global_rejected:
        await bulkhead.acquire(_scope("tenant-c", "room-1"))
    assert global_rejected.value.scope == "global"

    await active.release()
    first = await tenant_waiter
    await first.release()
    second = await global_waiter
    await second.release()

    snapshot = await bulkhead.snapshot()
    assert snapshot.counters.saturated_rejections == 2
    assert snapshot.counters.grants == snapshot.counters.releases == 3


@pytest.mark.asyncio
async def test_room_queue_has_an_independent_bound() -> None:
    bulkhead = GraphFanoutBulkhead(
        _config(
            global_limit=2,
            tenant_limit=2,
            room_limit=1,
            global_queue_limit=3,
            tenant_queue_limit=3,
            room_queue_limit=1,
        )
    )
    active = await bulkhead.acquire(_scope("tenant-a", "room-1"))
    waiter = asyncio.create_task(bulkhead.acquire(_scope("tenant-a", "room-1")))
    await _wait_for_queue(bulkhead, 1)

    with pytest.raises(GraphBulkheadSaturatedError) as rejected:
        await bulkhead.acquire(_scope("tenant-a", "room-1"))
    assert rejected.value.scope == "room"

    await active.release()
    queued = await waiter
    await queued.release()


@pytest.mark.asyncio
async def test_cancellation_timeout_and_exception_release_all_capacity() -> None:
    bulkhead = GraphFanoutBulkhead(_config(global_limit=1, tenant_limit=1, room_limit=1))
    active = await bulkhead.acquire(_scope("tenant-a", "room-1"))
    cancelled = asyncio.create_task(bulkhead.acquire(_scope("tenant-b", "room-1")))
    await _wait_for_queue(bulkhead, 1)
    cancelled.cancel()
    with pytest.raises(asyncio.CancelledError):
        await cancelled
    await _wait_for_queue(bulkhead, 0)

    with pytest.raises(GraphBulkheadTimeoutError) as timed_out:
        await bulkhead.acquire(
            _scope("tenant-b", "room-1"),
            timeout_seconds=0.01,
        )
    assert timed_out.value.retryable
    await active.release()

    class ExpectedFailure(Exception):
        pass

    with pytest.raises(ExpectedFailure):
        async with bulkhead.slot(_scope("tenant-a", "room-1")):
            raise ExpectedFailure

    snapshot = await bulkhead.snapshot()
    assert snapshot.active_global == snapshot.queued_global == 0
    assert snapshot.counters.cancelled_waiters == 1
    assert snapshot.counters.timeout_rejections == 1
    assert snapshot.counters.grants == snapshot.counters.releases == 2


@pytest.mark.asyncio
async def test_cancellation_after_grant_reclaims_the_undelivered_permit() -> None:
    bulkhead = GraphFanoutBulkhead(_config(global_limit=1, tenant_limit=1, room_limit=1))
    active = await bulkhead.acquire(_scope("tenant-a", "room-1"))
    waiter = asyncio.create_task(bulkhead.acquire(_scope("tenant-b", "room-1")))
    await _wait_for_queue(bulkhead, 1)

    await active.release()
    waiter.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiter

    snapshot = await bulkhead.snapshot()
    assert snapshot.active_global == snapshot.queued_global == 0
    assert snapshot.counters.cancelled_waiters == 1
    assert snapshot.counters.grants == snapshot.counters.releases == 2


@pytest.mark.asyncio
async def test_cancelling_active_slot_releases_all_capacity_levels() -> None:
    bulkhead = GraphFanoutBulkhead(_config(global_limit=1, tenant_limit=1, room_limit=1))
    entered = asyncio.Event()

    async def hold_slot() -> None:
        async with bulkhead.slot(_scope("tenant-a", "room-1")):
            entered.set()
            await asyncio.Event().wait()

    task = asyncio.create_task(hold_slot())
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    snapshot = await bulkhead.snapshot()
    assert snapshot.active_global == snapshot.queued_global == 0
    assert snapshot.tenants == snapshot.rooms == ()
    assert snapshot.counters.grants == snapshot.counters.releases == 1


@pytest.mark.asyncio
async def test_close_rejects_waiters_and_future_admission_but_not_active_release() -> None:
    bulkhead = GraphFanoutBulkhead(_config(global_limit=1, tenant_limit=1, room_limit=1))
    active = await bulkhead.acquire(_scope("tenant-a", "room-1"))
    waiter = asyncio.create_task(bulkhead.acquire(_scope("tenant-b", "room-1")))
    await _wait_for_queue(bulkhead, 1)

    await bulkhead.close()
    with pytest.raises(GraphBulkheadClosedError):
        await waiter
    with pytest.raises(GraphBulkheadClosedError):
        await bulkhead.acquire(_scope("tenant-c", "room-1"))
    assert await active.release()
    assert not await active.release()

    snapshot = await bulkhead.snapshot()
    assert snapshot.closed
    assert snapshot.active_global == snapshot.queued_global == 0
    assert snapshot.counters.closed_rejections == 2


@pytest.mark.asyncio
async def test_concurrent_stress_never_exceeds_any_hierarchical_limit() -> None:
    bulkhead = GraphFanoutBulkhead(
        _config(
            global_limit=10,
            tenant_limit=2,
            room_limit=1,
            global_queue_limit=100,
            tenant_queue_limit=25,
            room_queue_limit=10,
            wait_timeout_seconds=5,
        )
    )
    active_global = 0
    active_by_tenant: dict[str, int] = defaultdict(int)
    active_by_room: dict[tuple[str, str], int] = defaultdict(int)
    maxima = {"global": 0, "tenant": 0, "room": 0}

    async def work(index: int) -> None:
        nonlocal active_global
        tenant = f"tenant-{index % 5}"
        room = f"room-{index % 15}"
        room_key = (tenant, room)
        async with bulkhead.slot(_scope(tenant, room)):
            active_global += 1
            active_by_tenant[tenant] += 1
            active_by_room[room_key] += 1
            maxima["global"] = max(maxima["global"], active_global)
            maxima["tenant"] = max(maxima["tenant"], active_by_tenant[tenant])
            maxima["room"] = max(maxima["room"], active_by_room[room_key])
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            active_global -= 1
            active_by_tenant[tenant] -= 1
            active_by_room[room_key] -= 1

    await asyncio.gather(*(work(index) for index in range(100)))

    snapshot = await bulkhead.snapshot()
    assert maxima == {"global": 10, "tenant": 2, "room": 1}
    assert snapshot.active_global == snapshot.queued_global == 0
    assert snapshot.counters.grants == snapshot.counters.releases == 100
    assert snapshot.counters.queued_grants > 0
    assert snapshot.counters.max_wait_seconds >= 0


@pytest.mark.asyncio
@settings(max_examples=20, deadline=None)
@given(limits=_hierarchical_limits())
async def test_generated_hierarchical_limits_never_oversubscribe(
    limits: tuple[int, int, int],
) -> None:
    global_limit, tenant_limit, room_limit = limits
    bulkhead = GraphFanoutBulkhead(
        _config(
            global_limit=global_limit,
            tenant_limit=tenant_limit,
            room_limit=room_limit,
            global_queue_limit=30,
            tenant_queue_limit=30,
            room_queue_limit=30,
            wait_timeout_seconds=5,
        )
    )
    active_global = 0
    active_by_tenant: dict[str, int] = defaultdict(int)
    active_by_room: dict[tuple[str, str], int] = defaultdict(int)

    async def work(index: int) -> None:
        nonlocal active_global
        tenant = f"tenant-{index % 3}"
        room = f"room-{index % 9}"
        room_key = (tenant, room)
        async with bulkhead.slot(_scope(tenant, room)):
            active_global += 1
            active_by_tenant[tenant] += 1
            active_by_room[room_key] += 1
            assert active_global <= global_limit
            assert active_by_tenant[tenant] <= tenant_limit
            assert active_by_room[room_key] <= room_limit
            await asyncio.sleep(0)
            active_global -= 1
            active_by_tenant[tenant] -= 1
            active_by_room[room_key] -= 1

    await asyncio.gather(*(work(index) for index in range(30)))

    snapshot = await bulkhead.snapshot()
    assert snapshot.active_global == snapshot.queued_global == 0
    assert snapshot.counters.grants == snapshot.counters.releases == 30
