from __future__ import annotations

import json
import random
import threading
import time
from copy import deepcopy
from dataclasses import replace
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from langchain_core.runnables import RunnableLambda

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence import (
    MAX_ACTIVE_ITEMS,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContext,
    EvidenceGraphContractError,
    build_evidence_v2_graph,
    compile_evidence_v2_graph,
    new_evidence_graph_state,
    validate_verified_admission,
)
from app.graphs.evidence.nodes import dispatch_wave, plan_next_deterministic_wave


ROOT = Path(__file__).resolve().parents[4]


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
    graph = compile_evidence_v2_graph(item_assessor=RunnableLambda(assessment_factory))
    initial = new_evidence_graph_state(admission=admission)
    waves: list[list[str]] = []
    final = None

    for update in graph.stream(
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

    graph = compile_evidence_v2_graph(item_assessor=RunnableLambda(tracking_assessor))
    graph.invoke(
        new_evidence_graph_state(admission=admission),
        context=EvidenceGraphContext(
            admission=admission,
            completed_at="2026-07-22T12:05:00Z",
        ),
        config={"recursion_limit": 128},
    )

    assert calls == 100
    assert 1 <= maximum <= MAX_ACTIVE_ITEMS


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
    graph = compile_evidence_v2_graph()

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_ITEM_ASSESSOR_NOT_CONFIGURED"):
        graph.invoke(
            new_evidence_graph_state(admission=admission),
            context=context,
            config={"recursion_limit": 32},
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

    graph = compile_evidence_v2_graph(item_assessor=RunnableLambda(forbidden_assessor))

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_ASSESSMENT_FIELDS_INVALID"):
        graph.invoke(
            new_evidence_graph_state(admission=admission),
            context=context,
            config={"recursion_limit": 32},
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

    graph = compile_evidence_v2_graph(item_assessor=RunnableLambda(wrong_key_assessor))

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_ASSESSMENT_REDUCER_KEY_INVALID"):
        graph.invoke(
            new_evidence_graph_state(admission=admission),
            context=context,
            config={"recursion_limit": 32},
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

        graph = compile_evidence_v2_graph(item_assessor=RunnableLambda(delayed))
        state = graph.invoke(
            new_evidence_graph_state(admission=admission),
            context=EvidenceGraphContext(
                admission=admission,
                completed_at="2026-07-22T12:05:00Z",
            ),
            config={"recursion_limit": 32},
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

    graph = compile_evidence_v2_graph(item_assessor=RunnableLambda(review_assessor))
    state = graph.invoke(
        new_evidence_graph_state(admission=admission),
        context=context,
        config={"recursion_limit": 32},
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
    graph = compile_evidence_v2_graph(item_assessor=assessor)
    state = graph.invoke(
        new_evidence_graph_state(admission=admission),
        context=context,
        config={"recursion_limit": 32},
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


def test_graph_topology_is_closed_and_contains_no_process_transition_nodes() -> None:
    builder = build_evidence_v2_graph()

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
    admission_factory,
    admission_refresher,
) -> None:
    admission = admission_factory(100)
    manifest = deepcopy(dict(admission.manifest))
    extra = deepcopy(manifest["items"][-1])
    extra["evidence_id"] = "EVIDENCE_SYNTH_101"
    extra["display_order"] = 100
    manifest["items"].append(extra)
    manifest["ordered_item_keys"].append(extra["evidence_id"])
    manifest["item_count"] = 101
    broken = admission_refresher(
        replace(admission, manifest=manifest),
        refresh_internal_manifest_hash=True,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_MANIFEST_MEMBERSHIP_INVALID",
    ):
        validate_verified_admission(broken)
