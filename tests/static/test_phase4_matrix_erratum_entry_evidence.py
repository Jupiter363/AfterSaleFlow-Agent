from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

import yaml

from scripts.generate_phase3_candidate_evidence import parse_junit


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "0740c9b73b7385249ed5645cf1dee10909173049"
RELEASE_ID = "phase-4-matrix-erratum-20260721-r1"
EVIDENCE = ROOT / "test-reports" / "temporal-first" / RELEASE_ID / "phase-4-entry"
CHECKPOINT = (
    ROOT / "docs" / "runbooks" / "temporal-first" / "phase-4-p4.0-entry-checkpoint.md"
)
EXPECTED_REPORTS = {
    "p4_entry_static": ("static-entry-junit.xml", 30),
    "p4_entry_python": ("python-entry-junit.xml", 70),
    "p4_entry_java": ("java-entry-junit.xml", 83),
    "p4_entry_frontend": ("frontend-entry-junit.xml", 120),
}


def _json(name: str) -> dict:
    return json.loads((EVIDENCE / name).read_text(encoding="utf-8"))


def _index_blob(path: Path) -> bytes:
    relative = path.relative_to(ROOT).as_posix()
    return subprocess.run(
        ["git", "show", f":{relative}"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def _candidate_blob(path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{CANDIDATE}:{path}"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def test_matrix_erratum_evidence_is_exact_candidate_bound_and_green() -> None:
    assert {path.name for path in EVIDENCE.iterdir()} == {
        "candidate-commit.txt",
        "failure-classification.json",
        "reauthentication-metrics.json",
        "static-entry-junit.xml",
        "python-entry-junit.xml",
        "java-entry-junit.xml",
        "frontend-entry-junit.xml",
    }
    assert (EVIDENCE / "candidate-commit.txt").read_text(encoding="utf-8") == (
        CANDIDATE + "\n"
    )
    subprocess.run(
        ["git", "cat-file", "-e", f"{CANDIDATE}^{{commit}}"],
        cwd=ROOT,
        check=True,
    )

    metrics = _json("reauthentication-metrics.json")
    assert metrics["result"] == "PASS"
    assert metrics["contract_candidate"] == {
        "commit": CANDIDATE,
        "worktree": ".codex-run/phase4-matrix-erratum-0740c9b7",
        "detached": True,
        "head_before": CANDIDATE,
        "head_after": CANDIDATE,
        "tracked_clean_before": True,
        "tracked_clean_after": True,
        "mixed_candidate_results": False,
    }
    assert metrics["totals"] == {
        "tests": 303,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
    assert metrics["independent_review"] == {
        "result": "PASS",
        "p0": 0,
        "p1": 0,
        "p2": 0,
        "reviewed_candidate": CANDIDATE,
    }

    matrix = yaml.safe_load(
        _candidate_blob("plans/phase-4-intake-pilot-test-batches.yaml")
    )
    matrix_commands = {
        source["id"]: source["command"]
        for source in matrix["batches"]["P4-BATCH-0"]["source_commands"]
    }
    suites = {item["command_id"]: item for item in metrics["source_suites"]}
    assert set(suites) == set(matrix_commands) == set(EXPECTED_REPORTS)

    total = 0
    for command_id, (filename, expected_tests) in EXPECTED_REPORTS.items():
        suite = suites[command_id]
        report_path = EVIDENCE / filename
        report = parse_junit(report_path)
        report_blob = _index_blob(report_path)
        assert b"\r" not in report_blob
        assert suite["matrix_command"] == matrix_commands[command_id]
        assert suite["exit_code"] == 0
        assert suite["junit"] == {
            "path": filename,
            "canonical_lf_bytes": len(report_blob),
            "canonical_lf_sha256": hashlib.sha256(report_blob).hexdigest(),
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }
        assert report.candidate_commit == CANDIDATE
        assert report.command_id == command_id
        assert report.totals == {
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "time": report.totals["time"],
        }
        assert not {
            child.tag.rsplit("}", 1)[-1]
            for case in report.cases
            for child in case.element.iter()
        } & {"system-out", "system-err"}
        total += expected_tests
    assert total == 303


def test_matrix_erratum_infra_classification_and_runtime_gate_remain_closed() -> None:
    failure = _json("failure-classification.json")
    assert failure["candidate_commit"] == CANDIDATE
    assert failure["product_failures"] == []
    assert {item["classification"] for item in failure["resolved_attempts"]} == {
        "INFRA"
    }
    assert not any(
        item["accepted_as_evidence"] for item in failure["resolved_attempts"]
    )

    environment = failure["environment_resolution"]
    assert environment["candidate_manifests_match_dependency_workspace"] is True
    assert environment["tracked_candidate_files_changed"] is False
    assert (
        environment["package_json_sha256"]
        == hashlib.sha256(_candidate_blob("frontend/package.json")).hexdigest()
    )
    assert (
        environment["pnpm_lock_sha256"]
        == hashlib.sha256(_candidate_blob("frontend/pnpm-lock.yaml")).hexdigest()
    )

    metrics = _json("reauthentication-metrics.json")
    assert metrics["runtime_gate"] == {
        "allowed_modes": ["DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
        "real_case_shadow": False,
        "temporal_intake_allocation": False,
        "formal_finalizer_runtime_wiring": False,
        "canary": False,
        "promotion": False,
        "MIG-003": "PENDING_PROMOTION",
        "MIG-004": "PENDING_PROMOTION",
    }
    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    assert f"PASS_AT_{CANDIDATE}" in checkpoint
    assert "AUTHORIZED_FOR_PHASE_4_ENGINEERING_ONLY" in checkpoint
    assert "MIG-003: PENDING_PROMOTION" in checkpoint
    assert "MIG-004: PENDING_PROMOTION" in checkpoint
