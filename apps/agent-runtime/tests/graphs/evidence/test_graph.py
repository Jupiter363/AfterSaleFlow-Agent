from __future__ import annotations

import asyncio
import json
import random
import threading
import time
from copy import deepcopy
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from langchain_core.runnables import RunnableLambda

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.bulkhead import GraphBulkheadScope, GraphPermitFenceContext
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graphs.evidence import (
    MAX_ACTIVE_ITEMS,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContext,
    EvidenceGraphContractError,
    build_evidence_v2_graph,
    compile_evidence_v2_graph,
    new_evidence_graph_state,
)
from app.graphs.evidence.nodes import dispatch_wave, plan_next_deterministic_wave


ROOT = Path(__file__).resolve().parents[5]


class _AsyncPermit:
    renewal_interval_seconds = 60.0

    async def validate_recovery(self) -> None:
        return None

    async def renew(self) -> None:
        return None

    async def release(self) -> None:
        return None


class _AsyncPostgresBulkhead(PostgresGraphFanoutBulkhead):
    def __init__(self) -> None:
        pass

    async def acquire(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        *,
        request_id: str | None = None,
        owner_id: str | None = None,
        timeout_seconds: float | None = None,
        takeover: bool = False,
    ) -> _AsyncPermit:
        del scope, request_id, timeout_seconds
        assert owner_id is not None and owner_id.startswith("permit-worker:")
        assert takeover is True
        return _AsyncPermit()


def _graph_fence(admission) -> GraphFenceContext:
    command = admission.room_graph_command
    return GraphFenceContext(
        thread_id=command["thread_id"],
        command_id=command["command_id"],
        owner_id="test-evidence-worker",
        fencing_token=admission.graph_lease_fencing_token,
        request_hash=command["request_hash"],
        room_epoch=command["room_epoch"],
        graph_key=command["graph_key"],
        graph_version=command["graph_version"],
        checkpoint_schema_version=command["checkpoint_schema_version"],
    )


def _compile(admission, item_assessor=None):
    return compile_evidence_v2_graph(
        item_assessor=item_assessor,
        bulkhead=_AsyncPostgresBulkhead(),
        graph_fence=_graph_fence(admission),
    )


def _invoke(graph, initial, *, context, recursion_limit=32):
    return asyncio.run(
        graph.ainvoke(
            initial,
            context=context,
            config={"recursion_limit": recursion_limit},
        )
    )


@pytest.mark.parametrize("count", [1, 8, 100])
def test_graph_processes_closed_synthetic_counts_in_deterministic_waves(
    count,
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(count)
    context = EvidenceGraphContext(
        admission=admission,
        completed_at="2026-07-22T12:05:00Z",
    )
    graph = _compile(admission, RunnableLambda(assessment_factory))
    initial = new_evidence_graph_state(admission=admission)
    waves: list[list[str]] = []
    final = None

    async def collect_updates() -> None:
        nonlocal final
        async for update in graph.astream(
            initial,
            context=context,
            config={"recursion_limit": 128},
            stream_mode="updates",
        ):
            planned = update.get("plan_next_deterministic_wave")
            if planned and planned.get("route") == "dispatch":
                waves.append(planned["current_wave_keys"])
            if "checkpoint_terminal" in update:
                final = update["checkpoint_terminal"]

    asyncio.run(collect_updates())

    assert final is not None
    assert [key for wave in waves for key in wave] == initial["ordered_item_keys"]
    assert all(1 <= len(wave) <= MAX_ACTIVE_ITEMS for wave in waves)
    assert len(waves) == (count + MAX_ACTIVE_ITEMS - 1) // MAX_ACTIVE_ITEMS
    result = final["result_json"]
    assert result["schema_version"] == TERMINAL_OUTPUT_SCHEMA_VERSION
    assert result["item_count"] == count
    assert [ref["evidence_id"] for ref in result["assessment_refs"]] == initial["ordered_item_keys"]


def test_graph_never_has_more_than_eight_active_item_assessments(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(100)
    lock = threading.Lock()
    active = 0
    maximum = 0
    calls = 0

    def tracking_assessor(work_item):
        nonlocal active, maximum, calls
        with lock:
            active += 1
            calls += 1
            maximum = max(maximum, active)
        time.sleep(0.005)
        try:
            return assessment_factory(work_item)
        finally:
            with lock:
                active -= 1

    graph = _compile(admission, RunnableLambda(tracking_assessor))
    _invoke(
        graph,
        new_evidence_graph_state(admission=admission),
        context=EvidenceGraphContext(
            admission=admission,
            completed_at="2026-07-22T12:05:00Z",
        ),
        recursion_limit=128,
    )

    assert calls == 100
    assert 1 <= maximum <= MAX_ACTIVE_ITEMS


def test_async_graph_execution_fails_closed_without_durable_permits(
    admission,
    context,
    assessor,
) -> None:
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_DURABLE_BULKHEAD_REQUIRED",
    ):
        compile_evidence_v2_graph(item_assessor=assessor)

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_GRAPH_LEASE_FENCE_REQUIRED",
    ):
        compile_evidence_v2_graph(
            item_assessor=assessor,
            bulkhead=_AsyncPostgresBulkhead(),
        )


def test_sync_graph_execution_cannot_bypass_durable_permits(
    admission,
    context,
    assessor,
) -> None:
    graph = _compile(admission, assessor)

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_DURABLE_PERMIT_ASYNC_REQUIRED",
    ):
        graph.invoke(
            new_evidence_graph_state(admission=admission),
            context=context,
            config={"recursion_limit": 32},
        )


def test_scheduler_rejects_more_than_eight_active_keys(admission_factory) -> None:
    admission = admission_factory(100)
    state = new_evidence_graph_state(admission=admission)
    state["in_flight_keys"] = state["ordered_item_keys"][:9]

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_SCHEDULER_STATE_INVALID"):
        plan_next_deterministic_wave(state)


def test_dispatch_rejects_unknown_routes_and_untracked_keys(admission_factory) -> None:
    state = new_evidence_graph_state(admission=admission_factory(8))
    state["route"] = "unknown"

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_SCHEDULER_ROUTE_INVALID"):
        dispatch_wave(state)


def test_graph_fails_closed_when_assessor_is_unconfigured(admission, context) -> None:
    graph = _compile(admission)

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_ITEM_ASSESSOR_NOT_CONFIGURED"):
        _invoke(
            graph,
            new_evidence_graph_state(admission=admission),
            context=context,
        )


def test_graph_rejects_assessment_with_process_authority(
    admission,
    context,
    assessment_factory,
) -> None:
    def forbidden_assessor(work_item):
        value = assessment_factory(work_item)
        value["hearing_open"] = True
        value.pop("assessment_hash")
        value["assessment_hash"] = canonical_sha256(value)
        return value

    graph = _compile(admission, RunnableLambda(forbidden_assessor))

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_ASSESSMENT_FIELDS_INVALID"):
        _invoke(
            graph,
            new_evidence_graph_state(admission=admission),
            context=context,
        )


def test_graph_rejects_missing_or_extra_manifest_coverage(
    admission,
    context,
    assessment_factory,
) -> None:
    def wrong_key_assessor(work_item):
        value = assessment_factory(work_item)
        value["evidence_id"] = "EVIDENCE_OUTSIDE_MANIFEST"
        value.pop("assessment_hash")
        value["assessment_hash"] = canonical_sha256(value)
        return value

    graph = _compile(admission, RunnableLambda(wrong_key_assessor))

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_ASSESSMENT_REDUCER_KEY_INVALID"):
        _invoke(
            graph,
            new_evidence_graph_state(admission=admission),
            context=context,
        )


def test_proposal_hash_is_completion_order_independent(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(8)

    def run(seed: int) -> dict:
        randomizer = random.Random(seed)
        delays = {
            key: randomizer.random() / 1000 for key in admission.manifest["ordered_item_keys"]
        }

        def delayed(work_item):
            time.sleep(delays[work_item["item"]["evidence_id"]])
            return assessment_factory(work_item)

        graph = _compile(admission, RunnableLambda(delayed))
        state = _invoke(
            graph,
            new_evidence_graph_state(admission=admission),
            context=EvidenceGraphContext(
                admission=admission,
                completed_at="2026-07-22T12:05:00Z",
            ),
        )
        return state["result_json"]

    assert run(1) == run(2)


def test_needs_review_remains_a_proposal_not_a_business_decision(
    admission,
    context,
    assessment_factory,
) -> None:
    def review_assessor(work_item):
        value = assessment_factory(work_item)
        value["assessment_status"] = "NEEDS_REVIEW"
        value["review_reasons"] = ["LOW_CONFIDENCE"]
        value.pop("assessment_hash")
        value["assessment_hash"] = canonical_sha256(value)
        return value

    graph = _compile(admission, RunnableLambda(review_assessor))
    state = _invoke(
        graph,
        new_evidence_graph_state(admission=admission),
        context=context,
    )

    assert state["result_json"]["proposed_review_items"] == [
        {
            "review_key": "REVIEW_EVIDENCE_SYNTH_001",
            "evidence_id": "EVIDENCE_SYNTH_001",
            "reason_codes": ["LOW_CONFIDENCE"],
            "priority": "MEDIUM",
        }
    ]
    assert state["result_json"]["writer_mode"] == "PROPOSAL_ONLY"
    assert state["result_json"]["formal_sink_eligible"] is False


def test_terminal_proposal_matches_frozen_wire_schema(
    admission,
    context,
    assessor,
) -> None:
    graph = _compile(admission, assessor)
    state = _invoke(
        graph,
        new_evidence_graph_state(admission=admission),
        context=context,
    )
    schema = json.loads(
        (
            ROOT
            / "contracts"
            / "agent-platform"
            / "evidence"
            / "v2"
            / "evidence-terminal-proposal.schema.json"
        ).read_text(encoding="utf-8")
    )

    assert state["raw_outputs"] == {}
    assert not list(Draft202012Validator(schema).iter_errors(state["result_json"]))


def test_graph_topology_is_closed_and_contains_no_process_transition_nodes(admission) -> None:
    builder = build_evidence_v2_graph(
        bulkhead=_AsyncPostgresBulkhead(),
        graph_fence=_graph_fence(admission),
    )

    assert set(builder.nodes) == {
        "authorize_registration_and_manifest",
        "plan_next_deterministic_wave",
        "assess_evidence_item",
        "validate_item_assessment",
        "keyed_fan_in",
        "require_complete_valid_coverage",
        "build_matrix_and_review_proposal",
        "project_evidence_batch_proposal",
        "checkpoint_terminal",
    }
    assert not {
        "party_wait",
        "deadline_timer",
        "formal_merge",
        "dossier_freeze",
        "hearing_open",
    } & set(builder.nodes)


def test_manifest_scheduler_rejects_count_above_one_hundred(
    admission_request_factory,
    admission_verifier_factory,
    admission_refresher,
) -> None:
    request = admission_request_factory(100)
    manifest = json.loads(request.signed_manifest_payload)
    extra = deepcopy(manifest["items"][-1])
    extra["evidence_id"] = "EVIDENCE_SYNTH_101"
    extra["display_order"] = 100
    manifest["items"].append(extra)
    manifest["ordered_item_keys"].append(extra["evidence_id"])
    manifest["item_count"] = 101
    broken = admission_refresher(
        request,
        manifest=manifest,
        refresh_internal_manifest_hash=True,
        resign=True,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_MANIFEST_MEMBERSHIP_INVALID",
    ):
        admission_verifier_factory().verify(broken)
