from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
BRIEFS = ROOT / "plans/phase-5-owner-briefs.yaml"
PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"


def test_wave_a_tooling_does_not_prematurely_open_the_checkpoint() -> None:
    matrix = yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
    briefs = yaml.safe_load(BRIEFS.read_text(encoding="utf-8"))

    assert matrix["waves"]["wave_a"]["status"] == "READY"
    assert matrix["waves"]["wave_b"]["status"] == "BLOCKED_ON_WAVE_A_INTEGRATION"
    assert matrix["waves"]["candidate_wave"]["status"] == (
        "BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE"
    )
    barrier = briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"]
    assert barrier["status"] == "BLOCKED"
    assert "P5_BATCH_1_PASS_ON_MERGED_SHA" in barrier["prerequisites"]
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
