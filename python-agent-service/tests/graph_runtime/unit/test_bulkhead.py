from __future__ import annotations

import asyncio
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any

import pytest
from hypothesis import given, settings, strategies as st

from app.graph_runtime.bulkhead import (
    GraphBulkheadConfig,
    GraphBulkheadScope,
    GraphFanoutBulkhead,
    GraphPermitFenceContext,
)
from app.graph_runtime.errors import (
    GraphBulkheadClosedError,
    GraphBulkheadDisabledError,
    GraphBulkheadSaturatedError,
    GraphBulkheadTimeoutError,
    GraphContractError,
)
from app.graph_runtime.postgres_bulkhead import (
    PostgresBulkheadConfig,
    PostgresBulkheadPermit,
    PostgresGraphFanoutBulkhead,
    PostgresPermitRecord,
    _default_request_id,
    _new_permit_owner_id,
)


SERVICE_ROOT = Path(__file__).resolve().parents[3]


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


def test_postgres_defaults_match_signed_synthetic_database_contract() -> None:
    config = PostgresBulkheadConfig.signed_synthetic_defaults()

    assert (
        config.global_limit,
        config.tenant_limit,
        config.room_limit,
    ) == (32, 16, 8)
    assert (
        config.global_queue_limit,
        config.tenant_queue_limit,
        config.room_queue_limit,
    ) == (256, 128, 100)
    assert config.permit_lease_seconds == 20
    assert 0 < config.renewal_interval_seconds < config.permit_lease_seconds


def test_default_permit_owner_is_distinct_from_graph_owner_and_each_acquire() -> None:
    first = _new_permit_owner_id()
    second = _new_permit_owner_id()

    assert first.startswith("permit-worker:")
    assert first != second
    assert first != "worker-1"
    assert len(first) <= 128


def test_weight_one_preserves_the_legacy_default_request_identity() -> None:
    scope = GraphBulkheadScope(
        tenant_key="tenant-request-id",
        room_key="case-request-id:INTAKE:1",
        item_key="frame-set-request-id",
    )
    fence = GraphPermitFenceContext(
        thread_id=f"grt.v1.{'7' * 32}",
        command_id="command-request-id",
        graph_lease_owner_id="graph-owner-request-id",
        graph_lease_fencing_token=3,
    )
    legacy_digest = sha256(
        "\x00".join(
            (
                fence.thread_id,
                fence.command_id,
                scope.tenant_key,
                scope.room_key,
                scope.item_key,
            )
        ).encode("ascii")
    ).hexdigest()

    assert _default_request_id(scope, fence) == f"permit:{legacy_digest}"
    assert _default_request_id(scope, fence, permit_count=1) == f"permit:{legacy_digest}"
    assert _default_request_id(scope, fence, permit_count=3) != f"permit:{legacy_digest}"


@pytest.mark.asyncio
async def test_postgres_group_permit_is_acquired_as_one_weighted_record() -> None:
    scope = GraphBulkheadScope(
        tenant_key="tenant-group",
        room_key="case-group:INTAKE:1",
        item_key="frame-set-group",
    )
    fence = GraphPermitFenceContext(
        thread_id=f"grt.v1.{'8' * 32}",
        command_id="command-group",
        graph_lease_owner_id="graph-owner-group",
        graph_lease_fencing_token=4,
    )

    class UnusedPool:
        def connection(self, *, timeout: float) -> None:
            raise AssertionError("group unit test overrides the database call")

    class GroupBulkhead(PostgresGraphFanoutBulkhead):
        def __init__(self) -> None:
            super().__init__(UnusedPool(), PostgresBulkheadConfig.signed_synthetic_defaults())
            self._opened = True  # noqa: SLF001 - isolate weighted acquire selection
            self.group_calls = 0

        async def _acquire_once(self, *_args: Any, **_kwargs: Any) -> PostgresPermitRecord:
            raise AssertionError("a weighted request cannot use the single-permit routine")

        async def _acquire_group_once(
            self,
            selected_scope: GraphBulkheadScope,
            selected_fence: GraphPermitFenceContext,
            *,
            request_id: str,
            owner_id: str,
            timeout_seconds: float,
            takeover: bool,
            permit_count: int,
        ) -> PostgresPermitRecord:
            self.group_calls += 1
            assert selected_scope == scope
            assert selected_fence == fence
            assert request_id == "permit-group"
            assert owner_id == "permit-owner-group"
            assert timeout_seconds == self.config.wait_timeout_seconds
            assert takeover
            assert permit_count == 3
            now = datetime.now(timezone.utc)
            return PostgresPermitRecord(
                request_id=request_id,
                scope=scope,
                fence=fence,
                permit_owner_id=owner_id,
                permit_fencing_token=1,
                status="GRANTED",
                enqueued_at=now,
                granted_at=now,
                renewed_at=now,
                lease_expires_at=now + timedelta(seconds=20),
                revision=1,
                permit_count=permit_count,
            )

    bulkhead = GroupBulkhead()
    permit = await bulkhead.acquire(
        scope,
        fence,
        request_id="permit-group",
        owner_id="permit-owner-group",
        takeover=True,
        permit_count=3,
    )

    assert permit.permit_count == 3
    assert bulkhead.group_calls == 1
    with pytest.raises(GraphContractError, match="between 1 and 8"):
        await bulkhead.acquire(scope, fence, permit_count=0)


def test_durable_scope_uses_database_reconstructable_room_identity() -> None:
    scope = GraphBulkheadScope.from_graph_identity(
        tenant_surrogate="tenant-opaque",
        case_id="case-opaque",
        room_type="EVIDENCE",
        room_epoch=7,
        item_key="EVIDENCE_001",
    )

    assert scope == GraphBulkheadScope(
        tenant_key="tenant-opaque",
        room_key="case-opaque:EVIDENCE:7",
        item_key="EVIDENCE_001",
    )


def test_durable_scope_accepts_first_zero_based_room_epoch() -> None:
    scope = GraphBulkheadScope.from_graph_identity(
        tenant_surrogate="tenant-opaque",
        case_id="case-opaque",
        room_type="INTAKE",
        room_epoch=0,
        item_key="IFS_first-turn",
    )

    assert scope.room_key == "case-opaque:INTAKE:0"
    with pytest.raises(GraphContractError, match="must not be negative"):
        GraphBulkheadScope.from_graph_identity(
            tenant_surrogate="tenant-opaque",
            case_id="case-opaque",
            room_type="INTAKE",
            room_epoch=-1,
            item_key="IFS_invalid",
        )


def test_durable_migration_enforces_authoritative_scope_and_starvation_free_fifo() -> None:
    base_path = (
        SERVICE_ROOT / "migrations" / "graph" / "G004_graph_fanout_bulkhead.sql"
    )
    hardening_path = (
        SERVICE_ROOT
        / "migrations"
        / "graph"
        / "G005_graph_fanout_fairness_and_cancellation.sql"
    )
    base = " ".join(base_path.read_text(encoding="utf-8").split()).lower()
    hardening = " ".join(hardening_path.read_text(encoding="utf-8").split()).lower()

    assert sha256(base_path.read_bytes()).hexdigest() == (
        "f1b631cd6eb8a704c4a48b36fcfc422f22ea1efc349b4aabc72ca53e61c1a551"
    )
    assert "thread.tenant_surrogate = selected_tenant_key" in base
    assert "concat(thread.case_id, ':', thread.room_type, ':', thread.room_epoch)" in base
    assert "message = 'graph_fanout_scope_forged'" in base
    assert "order by permit.queue_sequence" in base
    assert "agent_graph_fanout_turn_sequence" not in base
    assert "pg_advisory_xact_lock" in base
    assert "for update of permit, lease skip locked" in base
    assert "uq_agent_graph_fanout_logical_active" in base
    assert "where status in ('queued', 'granted')" in base
    assert "'expired', 'released', 'timed_out', 'orphaned'" in base
    assert "existing.status in ('expired', 'released', 'timed_out', 'orphaned')" in base
    assert "set status = 'orphaned'" in base
    base_finish = base.split("create function agent_graph_finish_fanout_permit", 1)[1]
    base_finish = base_finish.split(
        "create function agent_graph_cancel_queued_fanout_permit", 1
    )[0]
    assert "agent_graph_assert_current_fanout_lease" in base_finish
    assert "lease_expires_at > clock_timestamp()" in base_finish

    assert "create sequence agent_graph_fanout_turn_sequence" in hardening
    assert "create function agent_graph_register_fanout_tenant_turn" in hardening
    assert "order by first_queue_sequence, tenant_key" in hardening
    assert "perform agent_graph_register_fanout_tenant_turn" in hardening
    assert "trg_agent_graph_register_fanout_tenant_turn" not in hardening
    assert "join agent_graph_fanout_tenant_turn tenant_turn" in hardening
    assert "order by tenant_turn.last_granted_sequence, permit.queue_sequence" in hardening
    assert "coalesce(tenant_turn.last_granted_sequence, 0)" not in hardening
    assert "nextval('agent_graph_fanout_turn_sequence')" in hardening
    assert "earlier.room_key = permit.room_key" in hardening
    assert "earlier.queue_sequence < permit.queue_sequence" in hardening

    acquire = hardening.split(
        "create or replace function agent_graph_acquire_fanout_permit", 1
    )[1]
    acquire = acquire.split(
        "create or replace function agent_graph_finish_fanout_permit", 1
    )[0]
    assert "from agent_graph_fanout_permit_owner_generation owner_generation" in acquire
    assert "owner_generation.permit_owner_id = selected_permit_owner_id" in acquire
    assert "message = 'graph_fanout_takeover_owner_reused'" in acquire

    finish = hardening.split(
        "create or replace function agent_graph_finish_fanout_permit", 1
    )[1]
    finish = finish.split(
        "create function agent_graph_cancel_or_release_fanout_permit", 1
    )[0]
    assert "agent_graph_assert_current_fanout_lease" not in finish
    assert "lease_expires_at > clock_timestamp()" not in finish
    for exact_binding in (
        "permit_fencing_token = selected_permit_fence",
        "permit_owner_id = selected_permit_owner_id",
        "thread_id = selected_thread_id and command_id = selected_command_id",
        "graph_lease_owner_id = selected_graph_owner_id",
        "graph_lease_fencing_token = selected_graph_fence",
    ):
        assert exact_binding in finish

    cleanup = hardening.split(
        "create function agent_graph_cancel_or_release_fanout_permit", 1
    )[1]
    assert "status in ('queued', 'granted')" in cleanup
    assert "when status = 'queued' then 'cancelled' else 'released'" in cleanup
    assert "agent_graph_assert_current_fanout_lease" not in cleanup
    for exact_binding in (
        "request_id = selected_request_id",
        "permit_owner_id = selected_permit_owner_id",
        "thread_id = selected_thread_id and command_id = selected_command_id",
        "graph_lease_owner_id = selected_graph_owner_id",
        "graph_lease_fencing_token = selected_graph_fence",
    ):
        assert exact_binding in cleanup


def test_atomic_group_migration_counts_capacity_by_permit_weight() -> None:
    migration_path = (
        SERVICE_ROOT
        / "migrations"
        / "graph"
        / "G013_graph_fanout_atomic_groups.sql"
    )
    migration = " ".join(migration_path.read_text(encoding="utf-8").split()).lower()

    assert "add column permit_count integer not null default 1" in migration
    assert "check (permit_count between 1 and 8)" in migration
    assert "create function agent_graph_acquire_fanout_permit_group" in migration
    assert "existing.permit_count" in migration
    assert "selected_permit_count" in migration
    assert "sum(active.permit_count)" in migration
    assert "+ permit.permit_count <= config.global_limit" in migration
    assert "+ permit.permit_count <= config.tenant_limit" in migration
    assert "+ permit.permit_count <= config.room_limit" in migration
    assert "from agent_graph_acquire_fanout_permit_group(" in migration


def test_postgres_composite_routines_are_evaluated_once() -> None:
    source = (
        SERVICE_ROOT / "app" / "graph_runtime" / "postgres_bulkhead.py"
    ).read_text(encoding="utf-8")

    assert "select (agent_graph_" not in source
    for routine in (
        "agent_graph_acquire_fanout_permit",
        "agent_graph_acquire_fanout_permit_group",
        "agent_graph_renew_fanout_permit",
        "agent_graph_finish_fanout_permit",
        "agent_graph_cancel_or_release_fanout_permit",
        "agent_graph_validate_fanout_recovery",
    ):
        assert f"select result.* from {routine}(" in source


def test_parallel_failure_terminalizer_preserves_fanout_lock_order() -> None:
    source = (
        SERVICE_ROOT / "app" / "graph_runtime" / "postgres_bulkhead.py"
    ).read_text(encoding="utf-8")
    terminalizer = source[source.index("async def terminalize_command_permits") :]

    advisory = terminalizer.index("pg_advisory_xact_lock")
    row_lock = terminalizer.index("for update")
    release_routine = terminalizer.index(
        "from agent_graph_cancel_or_release_fanout_permit("
    )
    assert advisory < row_lock < release_routine


@pytest.mark.asyncio
async def test_postgres_cancellation_after_grant_releases_undelivered_permit() -> None:
    scope = GraphBulkheadScope(
        tenant_key="tenant-race",
        room_key="case-race:EVIDENCE:1",
        item_key="item-race",
    )
    fence = GraphPermitFenceContext(
        thread_id=f"grt.v1.{'3' * 32}",
        command_id="command-race",
        graph_lease_owner_id="graph-owner-race",
        graph_lease_fencing_token=9,
    )
    request_id = "permit-race"
    permit_owner_id = "permit-owner-race"

    class UnusedPool:
        def connection(self, *, timeout: float) -> None:
            raise AssertionError("race test overrides all database calls")

    class RaceBulkhead(PostgresGraphFanoutBulkhead):
        def __init__(self) -> None:
            super().__init__(UnusedPool(), PostgresBulkheadConfig.signed_synthetic_defaults())
            self._opened = True  # noqa: SLF001 - isolate the acquire cancellation contract
            self.database_granted = asyncio.Event()
            self.database_status = "QUEUED"
            self.cleanup_task: asyncio.Task[Any] | None = None
            self.cleanup_params: tuple[Any, ...] | None = None

        async def _acquire_once(
            self,
            selected_scope: GraphBulkheadScope,
            selected_fence: GraphPermitFenceContext,
            *,
            request_id: str,
            owner_id: str,
            timeout_seconds: float,
            takeover: bool,
        ) -> PostgresPermitRecord:
            assert selected_scope == scope
            assert selected_fence == fence
            assert request_id == "permit-race"
            assert owner_id == "permit-owner-race"
            assert timeout_seconds == self.config.wait_timeout_seconds
            assert not takeover
            self.database_status = "GRANTED"
            self.database_granted.set()
            await asyncio.Event().wait()
            raise AssertionError("the granted row must not be returned after cancellation")

        async def _call_record(
            self, query: str, params: tuple[Any, ...]
        ) -> PostgresPermitRecord:
            assert "agent_graph_cancel_or_release_fanout_permit" in query
            assert self.database_status == "GRANTED"
            self.cleanup_task = asyncio.current_task()
            self.cleanup_params = params
            self.database_status = "RELEASED"
            now = datetime.now(timezone.utc)
            return PostgresPermitRecord(
                request_id=request_id,
                scope=scope,
                fence=fence,
                permit_owner_id=permit_owner_id,
                permit_fencing_token=1,
                status="RELEASED",
                enqueued_at=now - timedelta(seconds=1),
                granted_at=now,
                renewed_at=now,
                lease_expires_at=now + timedelta(seconds=20),
                revision=2,
            )

    bulkhead = RaceBulkhead()
    acquire_task = asyncio.create_task(
        bulkhead.acquire(
            scope,
            fence,
            request_id=request_id,
            owner_id=permit_owner_id,
        )
    )
    await bulkhead.database_granted.wait()
    acquire_task.cancel()

    with pytest.raises(asyncio.CancelledError):
        await acquire_task

    assert bulkhead.database_status == "RELEASED"
    assert bulkhead.cleanup_task is not None
    assert bulkhead.cleanup_task is not acquire_task
    assert bulkhead.cleanup_params == (
        request_id,
        fence.thread_id,
        fence.command_id,
        fence.graph_lease_owner_id,
        fence.graph_lease_fencing_token,
        permit_owner_id,
    )


def test_permit_fence_is_named_and_validated_separately_from_graph_lease() -> None:
    fence = GraphPermitFenceContext(
        thread_id=f"grt.v1.{'1' * 32}",
        command_id="command-1",
        graph_lease_owner_id="worker-1",
        graph_lease_fencing_token=3,
    )

    assert fence.graph_lease_fencing_token == 3
    with pytest.raises(GraphContractError, match="graph lease fencing token"):
        GraphPermitFenceContext(
            thread_id=fence.thread_id,
            command_id=fence.command_id,
            graph_lease_owner_id=fence.graph_lease_owner_id,
            graph_lease_fencing_token=0,
        )


def test_permit_heartbeat_uses_actual_short_graph_lease_window() -> None:
    now = datetime.now(timezone.utc)
    fence = GraphPermitFenceContext(
        thread_id=f"grt.v1.{'2' * 32}",
        command_id="command-short-lease",
        graph_lease_owner_id="worker-short-lease",
        graph_lease_fencing_token=1,
    )
    record = PostgresPermitRecord(
        request_id="permit-short-lease",
        scope=GraphBulkheadScope("tenant-1", "case-1:EVIDENCE:1", "item-1"),
        fence=fence,
        permit_owner_id="worker-short-lease",
        permit_fencing_token=1,
        status="GRANTED",
        enqueued_at=now - timedelta(seconds=1),
        granted_at=now,
        renewed_at=now,
        lease_expires_at=now + timedelta(seconds=3),
        revision=1,
    )
    bulkhead = object.__new__(PostgresGraphFanoutBulkhead)
    bulkhead._config = PostgresBulkheadConfig.signed_synthetic_defaults()  # noqa: SLF001
    permit = PostgresBulkheadPermit(bulkhead, record, wait_seconds=0.0)

    assert permit.renewal_interval_seconds == pytest.approx(1.0)
    assert permit.renewal_due_at == now + timedelta(seconds=1)
