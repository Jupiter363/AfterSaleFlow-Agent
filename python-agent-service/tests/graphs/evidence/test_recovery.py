from __future__ import annotations

import asyncio
import random
import time
from collections.abc import AsyncIterator
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from dataclasses import replace
from typing import Any

import pytest
from langchain_core.runnables import RunnableLambda
from langchain_core.runnables.config import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
)
from langgraph.checkpoint.memory import InMemorySaver

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.bulkhead import GraphBulkheadScope, GraphPermitFenceContext
from app.graph_runtime.checkpoint import FencedPostgresSaver, bind_fence_context
from app.graph_runtime.persistence_models import GraphFenceContext, GraphFenceError
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graphs.evidence.contracts import EvidenceGraphContext, EvidenceGraphContractError
from app.graphs.evidence.graph import compile_evidence_v2_graph
from app.graphs.evidence.runtime import (
    build_evidence_runtime_bundle,
    extract_evidence_terminal_proposal,
    validate_evidence_recovery_state,
)
from app.graphs.evidence.state import new_evidence_graph_state


COMPLETED_AT = "2026-07-22T12:05:00Z"


class _MemoryFencedSaver(FencedPostgresSaver):
    """In-memory test double preserving the production fence protocol."""

    def __init__(self, fence: GraphFenceContext) -> None:
        BaseCheckpointSaver.__init__(self)
        self._memory = InMemorySaver()
        self.active_fence = fence

    def _guard(self, config: RunnableConfig) -> GraphFenceContext:
        fence = self._require_fence(config)
        if fence != self.active_fence:
            raise GraphFenceError("Graph lease is stale, expired, released, or cancelled")
        return fence

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        fence = self._guard(config)
        found = await self._memory.aget_tuple(config)
        if found is None:
            return None
        self._validate_checkpoint_tuple(found, fence)
        return self._bind_tuple(found, fence)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        if config is None:
            raise GraphFenceError("trusted Graph fence required")
        fence = self._guard(config)
        async for item in self._memory.alist(
            config,
            filter=filter,
            before=before,
            limit=limit,
        ):
            self._validate_checkpoint_tuple(item, fence)
            yield self._bind_tuple(item, fence)

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        fence = self._guard(config)
        revision = self._checkpoint_revision(checkpoint)
        bound = self._bind_metadata(metadata, fence, revision)
        saved = await self._memory.aput(config, checkpoint, bound, new_versions)
        return bind_fence_context(saved, fence)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes,
        task_id: str,
        task_path: str = "",
    ) -> None:
        self._guard(config)
        await self._memory.aput_writes(config, writes, task_id, task_path)

    def get_next_version(self, current, channel):
        return self._memory.get_next_version(current, channel)


class _MemoryPostgresPermit:
    def __init__(
        self,
        bulkhead: _MemoryPostgresBulkhead,
        *,
        request_id: str,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        owner_id: str,
    ) -> None:
        self._bulkhead = bulkhead
        self.request_id = request_id
        self.scope = scope
        self.fence = fence
        self.owner_id = owner_id
        self.permit_fencing_token = len(bulkhead.acquisitions) + 1
        self.lease_expires_at = datetime.now(timezone.utc) + timedelta(seconds=30)
        self.renewal_due_at = datetime.now(timezone.utc) + timedelta(
            seconds=bulkhead.renewal_interval_seconds
        )
        self.renewal_interval_seconds = bulkhead.renewal_interval_seconds
        self.wait_seconds = 0.0
        self.validation_count = 0
        self.released = False

    async def validate_recovery(self) -> None:
        self.validation_count += 1
        if self.released or self.request_id not in self._bulkhead.active:
            raise RuntimeError("durable permit missing")
        if self.fence != self._bulkhead.active_fence:
            raise RuntimeError("stale graph lease")
        if self._bulkhead.drop_permit_at_validation == self.validation_count:
            self._bulkhead.active.pop(self.request_id, None)
            raise RuntimeError("durable permit missing")

    async def renew(self) -> datetime:
        if self._bulkhead.renew_failure is not None:
            raise self._bulkhead.renew_failure
        await self.validate_recovery()
        self._bulkhead.renewals.append(self.request_id)
        self.lease_expires_at = datetime.now(timezone.utc) + timedelta(seconds=30)
        self.renewal_due_at = datetime.now(timezone.utc) + timedelta(
            seconds=self.renewal_interval_seconds
        )
        return self.lease_expires_at

    async def release(self) -> None:
        if not self.released:
            self.released = True
            self._bulkhead.active.pop(self.request_id, None)
            self._bulkhead.releases.append(self.request_id)


class _MemoryPostgresBulkhead(PostgresGraphFanoutBulkhead):
    """Production-authority test double; it never falls back to a local bulkhead."""

    def __init__(
        self,
        *,
        active_fence: GraphPermitFenceContext | None = None,
        renewal_interval_seconds: float = 60.0,
    ) -> None:
        self.active_fence = active_fence
        self.renewal_interval_seconds = renewal_interval_seconds
        self.drop_permit_at_validation: int | None = None
        self.renew_failure: BaseException | None = None
        self.acquisitions: list[_MemoryPostgresPermit] = []
        self.renewals: list[str] = []
        self.releases: list[str] = []
        self.active: dict[str, _MemoryPostgresPermit] = {}
        self.expired_requests: set[str] = set()
        self.acquire_attempts: list[tuple[str, str]] = []

    async def acquire(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        request_id: str,
        owner_id: str,
        timeout_seconds: float | None = None,
        takeover: bool = False,
    ) -> _MemoryPostgresPermit:
        del timeout_seconds
        if not owner_id.startswith("permit-worker:"):
            raise RuntimeError("invalid permit execution owner")
        self.acquire_attempts.append((request_id, owner_id))
        if self.active_fence is None:
            self.active_fence = fence
        if fence != self.active_fence:
            raise RuntimeError("stale graph lease")
        if request_id in self.active:
            if request_id not in self.expired_requests:
                raise RuntimeError("durable permit binding conflict")
            if not takeover:
                raise RuntimeError("durable permit takeover required")
            self.expired_requests.remove(request_id)
        permit = _MemoryPostgresPermit(
            self,
            request_id=request_id,
            scope=scope,
            fence=fence,
            owner_id=owner_id,
        )
        self.acquisitions.append(permit)
        self.active[request_id] = permit
        return permit


def _fence(admission, *, owner_id="worker-a", fencing_token=None) -> GraphFenceContext:
    command = admission.room_graph_command
    return GraphFenceContext(
        thread_id=command["thread_id"],
        command_id=command["command_id"],
        owner_id=owner_id,
        fencing_token=fencing_token or admission.graph_lease_fencing_token,
        request_hash=command["request_hash"],
        room_epoch=command["room_epoch"],
        graph_key=command["graph_key"],
        graph_version=command["graph_version"],
        checkpoint_schema_version=command["checkpoint_schema_version"],
    )


def _bundle(
    *,
    admission,
    assessment,
    saver=None,
    bulkhead=None,
    fence=None,
    completed_at=COMPLETED_AT,
    mode=None,
):
    selected_fence = fence or _fence(admission)
    selected_saver = saver or _MemoryFencedSaver(selected_fence)
    selected_bulkhead = bulkhead or _MemoryPostgresBulkhead()
    return build_evidence_runtime_bundle(
        item_assessor=RunnableLambda(assessment),
        admission=admission,
        completed_at=completed_at,
        checkpointer=selected_saver,
        bulkhead=selected_bulkhead,
        fence=selected_fence,
        runtime_mode=mode or "SIGNED_SYNTHETIC_SHADOW",
    )


def _run(awaitable):
    return asyncio.run(awaitable)


def test_random_completion_order_produces_one_stable_terminal_hash(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(8)

    def execute(seed: int) -> tuple[str, dict]:
        randomizer = random.Random(seed)
        delays = {
            key: randomizer.random() / 1000
            for key in admission.manifest["ordered_item_keys"]
        }

        def delayed(work_item):
            time.sleep(delays[work_item["item"]["evidence_id"]])
            return assessment_factory(work_item)

        bundle = _bundle(admission=admission, assessment=delayed)
        state = _run(bundle.astart())
        proposal = bundle.terminal_proposal(state)
        replay = _run(bundle.aresume())
        return proposal["proposal_hash"], bundle.terminal_proposal(replay)

    first_hash, replay = execute(7)
    second_hash, _ = execute(91)

    assert first_hash == second_hash == replay["proposal_hash"]
    assert replay["coverage_status"] == "COMPLETE"
    assert replay["formal_sink_eligible"] is False
    assert replay["writer_mode"] == "PROPOSAL_ONLY"


@pytest.mark.parametrize("count", [1, 8, 100])
def test_crash_recovery_resumes_same_manifest_and_replays_identical_proposal(
    admission_factory,
    assessment_factory,
    count: int,
) -> None:
    admission = admission_factory(count)
    target = admission.manifest["ordered_item_keys"][-1]
    fence = _fence(admission)
    saver = _MemoryFencedSaver(fence)
    bulkhead = _MemoryPostgresBulkhead()
    crashed = False

    def crash_once(work_item):
        nonlocal crashed
        if work_item["item"]["evidence_id"] == target and not crashed:
            crashed = True
            raise RuntimeError("synthetic assessment crash")
        return assessment_factory(work_item)

    crashing = _bundle(
        admission=admission,
        assessment=crash_once,
        saver=saver,
        bulkhead=bulkhead,
        fence=fence,
    )
    with pytest.raises(RuntimeError, match="synthetic assessment crash"):
        _run(crashing.astart())

    recovered = _bundle(
        admission=admission,
        assessment=assessment_factory,
        saver=saver,
        bulkhead=bulkhead,
        fence=fence,
    )
    recovered_state = _run(recovered.aresume())
    recovered_proposal = recovered.terminal_proposal(recovered_state)

    clean = _bundle(
        admission=admission,
        assessment=assessment_factory,
    )
    clean_proposal = clean.terminal_proposal(_run(clean.astart()))

    assert recovered_proposal == clean_proposal
    target_requests = [
        permit.request_id
        for permit in bulkhead.acquisitions
        if permit.scope.item_key == target
    ]
    assert len(target_requests) == 2
    assert len(set(target_requests)) == 1
    assert len(bulkhead.releases) == len(bulkhead.acquisitions)
    assert not bulkhead.active


def test_recovery_rejects_another_graph_lease_fence_on_the_same_java_manifest(
    admission_request_factory,
    admission_verifier_factory,
    assessment_factory,
) -> None:
    request = admission_request_factory(1)
    original = admission_verifier_factory().verify(request)
    fence = _fence(original)
    saver = _MemoryFencedSaver(fence)
    completed = _bundle(
        admission=original,
        assessment=assessment_factory,
        saver=saver,
        fence=fence,
    )
    _run(completed.astart())

    replacement_request = replace(
        request,
        graph_lease_fencing_token=request.graph_lease_fencing_token + 1,
    )
    replacement_admission = admission_verifier_factory().verify(replacement_request)
    replacement = _bundle(
        admission=replacement_admission,
        assessment=assessment_factory,
        saver=saver,
        fence=_fence(replacement_admission),
    )
    saver.active_fence = replacement.fence

    assert original.manifest["fencing_token"] == replacement_admission.manifest["fencing_token"]
    assert original.graph_lease_fencing_token != (
        replacement_admission.graph_lease_fencing_token
    )
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH",
    ):
        _run(replacement.aresume())


def test_recovery_rejects_changed_runtime_binding_and_formal_authority(
    admission,
    assessment_factory,
) -> None:
    fence = _fence(admission)
    saver = _MemoryFencedSaver(fence)
    original = _bundle(
        admission=admission,
        assessment=assessment_factory,
        saver=saver,
        fence=fence,
    )
    terminal = _run(original.astart())
    changed_time = _bundle(
        admission=admission,
        assessment=assessment_factory,
        saver=saver,
        fence=fence,
        completed_at="2026-07-22T12:06:00Z",
    )
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH",
    ):
        _run(changed_time.aresume())

    poisoned = deepcopy(terminal)
    poisoned["trusted_business_decision"] = True
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RECOVERY_STATE_FIELDS_INVALID",
    ):
        validate_evidence_recovery_state(poisoned, admission=admission)


def test_terminal_extraction_fails_before_exact_manifest_coverage(
    admission_factory,
) -> None:
    admission = admission_factory(8)
    partial = new_evidence_graph_state(admission=admission)
    partial["cognitive_revision"] = 1

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_COMPLETE_COVERAGE_REQUIRED"):
        extract_evidence_terminal_proposal(
            partial,
            admission=admission,
            completed_at=COMPLETED_AT,
        )


def test_recovery_rejects_self_hashed_assessment_from_another_manifest(
    admission_factory,
    assessment_factory,
) -> None:
    source = admission_factory(8)
    target = admission_factory(1)
    source_manifest = source.manifest
    transplanted = assessment_factory(
        {
            "command_binding": source_manifest["command_binding"],
            "thread_id": source_manifest["thread_id"],
            "manifest_id": source_manifest["manifest_id"],
            "manifest_hash": source_manifest["manifest_hash"],
            "actor_scope_hash": source_manifest["actor_scope_hash"],
            "profile_versions": source_manifest["profile_versions"],
            "item": source_manifest["items"][0],
        }
    )
    state = new_evidence_graph_state(admission=target)
    state["cognitive_revision"] = 1
    state["next_dispatch_index"] = 1
    state["validated_outputs"] = {transplanted["evidence_id"]: transplanted}

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_ASSESSMENT_BINDING_MISMATCH",
    ):
        validate_evidence_recovery_state(state, admission=target)


def test_nested_formal_action_is_rejected_even_with_recomputed_proposal_hash(
    admission,
    assessment_factory,
) -> None:
    bundle = _bundle(admission=admission, assessment=assessment_factory)
    poisoned = deepcopy(_run(bundle.astart()))
    poisoned["proposed_fact_matrix_patch"][0]["formal_action"] = "FREEZE_DOSSIER"
    proposal = deepcopy(poisoned["result_json"])
    proposal["proposed_fact_links"] = deepcopy(poisoned["proposed_fact_matrix_patch"])
    proposal.pop("proposal_hash")
    proposal["proposal_hash"] = canonical_sha256(proposal)
    poisoned["result_json"] = proposal
    poisoned["terminal_draft"] = deepcopy(proposal)

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_TERMINAL_PROJECTION_INVALID",
    ):
        bundle.terminal_proposal(poisoned)


def test_runtime_rejects_generic_checkpoint_saver(
    admission,
    assessment_factory,
) -> None:
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RUNTIME_FENCED_CHECKPOINTER_REQUIRED",
    ):
        build_evidence_runtime_bundle(
            item_assessor=RunnableLambda(assessment_factory),
            admission=admission,
            completed_at=COMPLETED_AT,
            checkpointer=InMemorySaver(),  # type: ignore[arg-type]
            bulkhead=_MemoryPostgresBulkhead(),
            fence=_fence(admission),
            runtime_mode="SIGNED_SYNTHETIC_SHADOW",
        )


def test_runtime_rejects_missing_postgres_bulkhead(
    admission,
    assessment_factory,
) -> None:
    fence = _fence(admission)
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RUNTIME_POSTGRES_BULKHEAD_REQUIRED",
    ):
        build_evidence_runtime_bundle(
            item_assessor=RunnableLambda(assessment_factory),
            admission=admission,
            completed_at=COMPLETED_AT,
            checkpointer=_MemoryFencedSaver(fence),
            bulkhead=object(),  # type: ignore[arg-type]
            fence=fence,
            runtime_mode="SIGNED_SYNTHETIC_SHADOW",
        )


def test_each_async_item_uses_and_releases_a_distinct_durable_permit(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(8)
    bulkhead = _MemoryPostgresBulkhead()
    bundle = _bundle(
        admission=admission,
        assessment=assessment_factory,
        bulkhead=bulkhead,
    )

    state = _run(bundle.astart())

    assert state["result_json"]["writer_mode"] == "PROPOSAL_ONLY"
    assert len(bulkhead.acquisitions) == 8
    assert len({permit.request_id for permit in bulkhead.acquisitions}) == 8
    assert len(bulkhead.releases) == 8
    assert not bulkhead.active
    manifest = admission.manifest
    command = admission.room_graph_command
    for permit in bulkhead.acquisitions:
        assert permit.scope.tenant_key == manifest["tenant_surrogate"]
        assert permit.scope.room_key == (
            f"{command['case_id']}:{command['room_type']}:{command['room_epoch']}"
        )
        assert permit.scope.item_key in manifest["ordered_item_keys"]
        assert permit.owner_id.startswith("permit-worker:")
        assert permit.fence.graph_lease_fencing_token == (
            admission.graph_lease_fencing_token
        )
        assert permit.fence.graph_lease_fencing_token != manifest["fencing_token"]
        assert permit.validation_count == 2


def test_post_assessment_permit_loss_fails_closed_and_releases(
    admission,
    assessment_factory,
) -> None:
    bulkhead = _MemoryPostgresBulkhead()
    bulkhead.drop_permit_at_validation = 2
    bundle = _bundle(
        admission=admission,
        assessment=assessment_factory,
        bulkhead=bulkhead,
    )

    with pytest.raises(RuntimeError, match="durable permit missing"):
        _run(bundle.astart())

    assert len(bulkhead.acquisitions) == 1
    assert bulkhead.releases == [bulkhead.acquisitions[0].request_id]
    assert not bulkhead.active


def test_renewal_failure_cancels_assessor_and_releases_permit(
    admission,
) -> None:
    started = asyncio.Event()
    cancelled = asyncio.Event()

    async def blocked_assessor(_work_item):
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    bulkhead = _MemoryPostgresBulkhead(renewal_interval_seconds=0.001)
    bulkhead.renew_failure = RuntimeError("permit lease lost")
    bundle = _bundle(
        admission=admission,
        assessment=blocked_assessor,
        bulkhead=bulkhead,
    )

    async def scenario() -> None:
        with pytest.raises(RuntimeError, match="permit lease lost"):
            await bundle.astart()
        assert started.is_set()
        assert cancelled.is_set()

    _run(scenario())
    assert len(bulkhead.releases) == 1
    assert not bulkhead.active


def test_heartbeat_renews_before_accepting_assessment(
    admission,
    assessment_factory,
) -> None:
    async def delayed_assessor(work_item):
        await asyncio.sleep(0.01)
        return assessment_factory(work_item)

    bulkhead = _MemoryPostgresBulkhead(renewal_interval_seconds=0.001)
    bundle = _bundle(
        admission=admission,
        assessment=delayed_assessor,
        bulkhead=bulkhead,
    )

    state = _run(bundle.astart())

    assert state["result_json"]["coverage_status"] == "COMPLETE"
    assert bulkhead.renewals
    assert len(bulkhead.releases) == 1
    assert not bulkhead.active


def test_runtime_cancellation_releases_the_durable_permit(
    admission,
) -> None:
    started = asyncio.Event()
    cancelled = asyncio.Event()

    async def blocked_assessor(_work_item):
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    bulkhead = _MemoryPostgresBulkhead()
    bundle = _bundle(
        admission=admission,
        assessment=blocked_assessor,
        bulkhead=bulkhead,
    )

    async def scenario() -> None:
        task = asyncio.create_task(bundle.astart())
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert cancelled.is_set()

    _run(scenario())
    assert len(bulkhead.releases) == 1
    assert not bulkhead.active


def test_higher_graph_lease_takes_over_and_fences_old_item_output(
    admission_request_factory,
    admission_verifier_factory,
    assessment_factory,
) -> None:
    request = admission_request_factory(1)
    original = admission_verifier_factory().verify(request)
    replacement = admission_verifier_factory().verify(
        replace(
            request,
            graph_lease_fencing_token=request.graph_lease_fencing_token + 1,
        )
    )
    original_fence = _fence(original, owner_id="worker-old")
    replacement_fence = _fence(replacement, owner_id="worker-new")
    bulkhead = _MemoryPostgresBulkhead()
    started = asyncio.Event()
    complete_old = asyncio.Event()

    async def old_assessor(work_item):
        started.set()
        await complete_old.wait()
        return assessment_factory(work_item)

    old_graph = compile_evidence_v2_graph(
        item_assessor=RunnableLambda(old_assessor),
        bulkhead=bulkhead,
        graph_fence=original_fence,
    )
    new_graph = compile_evidence_v2_graph(
        item_assessor=RunnableLambda(assessment_factory),
        bulkhead=bulkhead,
        graph_fence=replacement_fence,
    )

    async def scenario() -> dict[str, Any]:
        old_task = asyncio.create_task(
            old_graph.ainvoke(
                new_evidence_graph_state(admission=original),
                context=EvidenceGraphContext(
                    admission=original,
                    completed_at=COMPLETED_AT,
                ),
                config={"recursion_limit": 32},
            )
        )
        await started.wait()
        old_permit = bulkhead.acquisitions[0]
        bulkhead.expired_requests.add(old_permit.request_id)
        bulkhead.active_fence = GraphPermitFenceContext(
            thread_id=replacement_fence.thread_id,
            command_id=replacement_fence.command_id,
            graph_lease_owner_id=replacement_fence.owner_id,
            graph_lease_fencing_token=replacement_fence.fencing_token,
        )
        replacement_state = await new_graph.ainvoke(
            new_evidence_graph_state(admission=replacement),
            context=EvidenceGraphContext(
                admission=replacement,
                completed_at=COMPLETED_AT,
            ),
            config={"recursion_limit": 32},
        )
        new_permit = bulkhead.acquisitions[1]
        complete_old.set()
        with pytest.raises(RuntimeError, match="durable permit missing|stale graph lease"):
            await old_task
        assert new_permit.request_id == old_permit.request_id
        assert new_permit.permit_fencing_token > old_permit.permit_fencing_token
        return replacement_state

    state = _run(scenario())
    assert state["result_json"]["coverage_status"] == "COMPLETE"
    assert state["result_json"]["formal_sink_eligible"] is False


def test_concurrent_duplicate_request_binding_conflicts_before_assessment(
    admission,
    assessment_factory,
) -> None:
    fence = _fence(admission)
    bulkhead = _MemoryPostgresBulkhead()
    started = asyncio.Event()
    complete_first = asyncio.Event()
    duplicate_assessed = False

    async def first_assessor(work_item):
        started.set()
        await complete_first.wait()
        return assessment_factory(work_item)

    def duplicate_assessor(work_item):
        nonlocal duplicate_assessed
        duplicate_assessed = True
        return assessment_factory(work_item)

    first_graph = compile_evidence_v2_graph(
        item_assessor=RunnableLambda(first_assessor),
        bulkhead=bulkhead,
        graph_fence=fence,
    )
    duplicate_graph = compile_evidence_v2_graph(
        item_assessor=RunnableLambda(duplicate_assessor),
        bulkhead=bulkhead,
        graph_fence=fence,
    )
    context = EvidenceGraphContext(admission=admission, completed_at=COMPLETED_AT)

    async def scenario() -> dict[str, Any]:
        first = asyncio.create_task(
            first_graph.ainvoke(
                new_evidence_graph_state(admission=admission),
                context=context,
                config={"recursion_limit": 32},
            )
        )
        await started.wait()
        with pytest.raises(RuntimeError, match="durable permit binding conflict"):
            await duplicate_graph.ainvoke(
                new_evidence_graph_state(admission=admission),
                context=context,
                config={"recursion_limit": 32},
            )
        complete_first.set()
        return await first

    state = _run(scenario())
    assert state["result_json"]["coverage_status"] == "COMPLETE"
    assert duplicate_assessed is False
    assert len(bulkhead.acquire_attempts) == 2
    assert bulkhead.acquire_attempts[0][0] == bulkhead.acquire_attempts[1][0]
    assert bulkhead.acquire_attempts[0][1] != bulkhead.acquire_attempts[1][1]


def test_runtime_rejects_transplanted_and_stale_graph_fences(
    admission,
    assessment_factory,
) -> None:
    current = _fence(admission)
    transplanted = replace(
        current,
        owner_id="worker-b",
        fencing_token=current.fencing_token + 1,
    )
    saver = _MemoryFencedSaver(current)
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RUNTIME_FENCE_BINDING_MISMATCH",
    ):
        _bundle(
            admission=admission,
            assessment=assessment_factory,
            saver=saver,
            fence=transplanted,
        )

    bundle = _bundle(
        admission=admission,
        assessment=assessment_factory,
        saver=saver,
        fence=current,
    )
    _run(bundle.astart())
    saver.active_fence = transplanted
    with pytest.raises(GraphFenceError, match="stale"):
        _run(bundle.aresume())


@pytest.mark.parametrize(
    ("mode", "code"),
    [
        ("DISABLED", "EVIDENCE_RUNTIME_DISABLED"),
        ("TEMPORAL", "EVIDENCE_RUNTIME_MODE_FORBIDDEN"),
        ("REAL_SHADOW", "EVIDENCE_RUNTIME_MODE_FORBIDDEN"),
    ],
)
def test_runtime_allows_only_java_signed_synthetic_shadow(
    admission,
    assessment_factory,
    mode: str,
    code: str,
) -> None:
    with pytest.raises(EvidenceGraphContractError, match=code):
        _bundle(
            admission=admission,
            assessment=assessment_factory,
            mode=mode,
        )
