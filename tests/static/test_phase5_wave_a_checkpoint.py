from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
BRIEFS = ROOT / "plans/phase-5-owner-briefs.yaml"
PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"


def test_wave_a_checkpoint_records_authenticated_acceptance_without_promotion() -> None:
    matrix = yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
    briefs = yaml.safe_load(BRIEFS.read_text(encoding="utf-8"))
    accepted_bindings = {
        "tested_candidate_commit": "edfd54952dcc5a07d87a90fdb094c01b1a7df79b",
        "evidence_commit": "0292321fdb376c3392c86daf6cf98365bfee7c4a",
        "acceptance_tooling_candidate_commit": (
            "ffc1409709046f8859deafc8917481f99f94659a"
        ),
        "acceptance_evidence_commit": "c6f9d7dbdd8d9322b219cef866a812a12004f539",
    }

    assert matrix["waves"]["wave_a"]["status"] == "INTEGRATED"
    assert matrix["waves"]["wave_b"]["status"] == "READY"
    assert matrix["waves"]["candidate_wave"]["status"] == (
        "BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE"
    )
    acceptance = matrix["batches"]["P5-BATCH-1"]["acceptance"]
    assert acceptance["status"] == "ACCEPTED_BY_STATE_TRANSITION"
    assert acceptance["accepted_bindings"] == accepted_bindings
    assert acceptance["evidence_commit_alone_opens_wave_b"] is False
    assert acceptance["acceptance_evidence_commit_alone_opens_wave_b"] is False

    barrier = briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"]
    assert barrier["status"] == "OPEN"
    assert barrier["accepted_bindings"] == accepted_bindings
    assert "P5_BATCH_1_PASS_ON_MERGED_SHA" in barrier["prerequisites"]

    gate = matrix["gate"]
    assert gate["runtime_modes_allowed"] == [
        "LEGACY",
        "DISABLED",
        "SIGNED_SYNTHETIC_SHADOW",
    ]
    assert gate["traffic_constraints"] == {
        "formal_evidence_graph_sink_allowed": False,
        "temporal_evidence_allocation_allowed": False,
        "real_case_shadow_allowed": False,
        "production_traffic_allowed": False,
        "canary_allowed": False,
        "promotion_allowed": False,
        "synthetic_fixtures_only": True,
    }
    execution = matrix["batches"]["P5-BATCH-1"]["execution"]
    assert execution["real_provider"] == "forbidden"
    assert execution["formal_finalizer_runtime_wiring"] == "forbidden"
    assert matrix["task_contracts"]["P5-R1"]["depends_on"] == [
        "P5-A2",
        "P5-B2",
        "P5-C2",
    ]


def test_checkpoint_contract_requires_evidence_then_separate_acceptance() -> None:
    matrix = yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
    execution = matrix["batches"]["P5-BATCH-1"]["execution"]
    plan = " ".join(PLAN.read_text(encoding="utf-8").split())

    assert execution["checkpoint_acceptance_required_to_open_wave_b"] is True
    assert execution["evidence_commit_must_be_later_than_tested_sha"] is True
    assert execution["accepted_wave_a_base_commit"] == (
        "496d0d459b97000f62742fe064d8ef70956ea419"
    )
    assert execution["evidence_required_files"] == [
        "candidate-commit.txt",
        "task-commit-bindings.json",
        "phase5-wave-a-execution-manifest.json",
        "python-phase5-wave-a-junit.xml",
        "java-phase5-wave-a-junit.xml",
        "static-phase5-wave-a-junit.xml",
        "wave-a-metrics.json",
        "artifact-sha256.json",
    ]
    assert "That bundle alone does not open Wave B" in plan
    assert "P5-R1` is completed by this" in plan


def test_primary_owns_all_wave_a_checkpoint_tooling_paths() -> None:
    briefs = yaml.safe_load(BRIEFS.read_text(encoding="utf-8"))
    primary = set(briefs["primary_integration_only"]["exact_paths"])

    assert {
        "scripts/run_phase5_wave_a_checkpoint.py",
        "scripts/generate_phase5_wave_a_evidence.py",
        "tests/static/test_phase5_wave_a_runner.py",
        "tests/static/test_phase5_wave_a_evidence.py",
        "tests/static/test_phase5_wave_a_checkpoint.py",
    } <= primary
