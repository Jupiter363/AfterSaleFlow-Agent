from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

from scripts import run_phase4_candidate_checkpoint as shared
from scripts.generate_phase3_candidate_evidence import parse_junit


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "e70492a11e23307382ea762d0e8e7f57ab58870b"
EVIDENCE_COMMIT = "e5f6019b71a90174c09aecdcba336bd12788b75b"
RELEASE_ID = "phase-5-entry-20260723-e70492a1"
EVIDENCE_RELATIVE = Path("test-reports/temporal-first") / RELEASE_ID / "phase-5-entry"
EVIDENCE = ROOT / EVIDENCE_RELATIVE
CHECKPOINT = (
    ROOT / "docs/runbooks/temporal-first/phase-5-p5.0-entry-checkpoint.md"
)
REPORTS = {
    "p5_entry_static": ("static-phase5-entry-junit.xml", 145),
    "p5_entry_python": ("python-phase5-entry-junit.xml", 61),
    "p5_entry_java": ("java-phase5-entry-junit.xml", 93),
    "p5_entry_frontend": ("frontend-phase5-entry-junit.xml", 97),
}
EXPECTED_FILES = {
    "artifact-sha256.json",
    "candidate.txt",
    "entry-metrics.json",
    "frontend-phase5-entry-junit.xml",
    "java-phase5-entry-junit.xml",
    "phase5-entry-execution-manifest.json",
    "python-phase5-entry-junit.xml",
    "static-phase5-entry-junit.xml",
}


def _json(name: str) -> dict[str, Any]:
    value = json.loads((EVIDENCE / name).read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _git(*arguments: str, payload: bytes | None = None) -> str:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        input=payload,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return process.stdout.decode("utf-8").strip()


def test_p5_0_entry_evidence_is_a_separate_exact_candidate_bound_commit() -> None:
    assert _git("rev-parse", f"{EVIDENCE_COMMIT}^") == CANDIDATE
    _git("merge-base", "--is-ancestor", EVIDENCE_COMMIT, "HEAD")
    changed = set(
        _git(
            "diff-tree",
            "--root",
            "--no-commit-id",
            "--name-only",
            "-r",
            EVIDENCE_COMMIT,
        ).splitlines()
    )
    assert changed == {
        (EVIDENCE_RELATIVE / filename).as_posix() for filename in EXPECTED_FILES
    }
    assert {path.name for path in EVIDENCE.iterdir()} == EXPECTED_FILES
    assert (EVIDENCE / "candidate.txt").read_text(encoding="ascii") == (
        CANDIDATE + "\n"
    )


def test_p5_0_entry_bundle_hashes_reports_and_execution_seal_are_authentic() -> None:
    index = _json("artifact-sha256.json")
    assert index["schema_version"] == "phase5-entry-artifact-index.v1"
    assert index["candidate_commit"] == CANDIDATE
    indexed = {item["path"]: item for item in index["artifacts"]}
    assert set(indexed) == EXPECTED_FILES - {"artifact-sha256.json"}

    for path in EVIDENCE.iterdir():
        payload = path.read_bytes()
        assert b"\r" not in payload
        logical_path = (EVIDENCE_RELATIVE / path.name).as_posix()
        assert _git("hash-object", "--stdin", payload=payload) == _git(
            "hash-object", f"--path={logical_path}", "--stdin", payload=payload
        )
        if path.name in indexed:
            assert indexed[path.name] == {
                "path": path.name,
                "sha256": _sha256(path),
                "bytes": path.stat().st_size,
            }

    metrics = _json("entry-metrics.json")
    manifest = _json("phase5-entry-execution-manifest.json")
    shared._assert_execution_manifest_seal(manifest)
    assert metrics["execution_manifest"]["sha256"] == _sha256(
        EVIDENCE / "phase5-entry-execution-manifest.json"
    )
    assert metrics["execution_manifest"]["manifest_sha256"] == manifest[
        "manifest_sha256"
    ]
    assert manifest["status"] == "PASS"
    assert manifest["batch_0"] == "PASS"
    assert manifest["candidate_commit"] == CANDIDATE
    assert manifest["quarantined_attempts_reused"] is False
    assert manifest["quarantined_attempts"] == []

    source_metrics = {item["command_id"]: item for item in metrics["source_suites"]}
    for command_id, (filename, expected_tests) in REPORTS.items():
        report = parse_junit(EVIDENCE / filename)
        assert report.candidate_commit == CANDIDATE
        assert report.command_id == command_id
        assert report.totals["tests"] == expected_tests
        assert report.totals["failures"] == 0
        assert report.totals["errors"] == 0
        assert report.totals["skipped"] == 0
        assert source_metrics[command_id]["report"] == filename
        assert source_metrics[command_id]["report_sha256"] == _sha256(
            EVIDENCE / filename
        )

    assert metrics["result"] == "PASS_AWAITING_EVIDENCE_COMMIT"
    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["totals"] == {
        "tests": 396,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 73.653368,
    }
    assert metrics["entry_decision"] == {
        "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
        "entry_effect_after_commit": "P5_0_ENGINEERING_ENTRY_PASS",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "implementation_allowed_before_commit": False,
    }
    assert metrics["runtime_restrictions"] == {
        "allowed_modes": ["LEGACY", "DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
        "formal_evidence_sink": False,
        "temporal_evidence_allocation": False,
        "real_case_shadow": False,
        "production_traffic": False,
        "canary": False,
        "promotion": False,
        "public_submission_max": 50,
        "closed_synthetic_manifest_counts": [1, 8, 100],
    }


def test_p5_0_checkpoint_grants_only_wave_a_engineering() -> None:
    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    for required in (
        "P5.0: PASS",
        "contract_gate: P5.0 PASS",
        "engineering_execution: ALLOWED_WITH_DISABLED_JAVA_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS",
        "next_phase_permission: PHASE_5_ENGINEERING_ONLY",
        "phase_6_permission: FORBIDDEN",
        "promotion_gate: PENDING",
        "MIG-004: PENDING_PROMOTION",
        "MIG-005: PENDING_PROMOTION",
        "real_case_shadow: FORBIDDEN",
        "temporal_evidence_allocation: FORBIDDEN",
        "formal_graph_sink: FORBIDDEN",
        "canary: FORBIDDEN",
        "promotion: FORBIDDEN",
        "ADR_0013_CONSUMED_AND_EXPIRED",
        CANDIDATE,
        EVIDENCE_COMMIT,
        "P5-A1",
        "P5-B1",
        "P5-C1",
        "P5-D0",
        "P5-E0",
    ):
        assert required in checkpoint
    assert "does not close `GRAPH-016`" in " ".join(checkpoint.split())
    assert "PHASE_6_ENGINEERING_ONLY" not in checkpoint
