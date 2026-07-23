from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

import yaml

from scripts import run_phase6_entry_checkpoint as runner


ROOT = Path(__file__).resolve().parents[2]
BASE = "d3ea271188be57adac49592879aaf3417e90c5c0"
CANDIDATE = "f338eb5df0c37d40a7b7293a1ae999dc8ea18b0c"
EVIDENCE_COMMIT = "07ec856ff23fb166b73aae72895dad8b2fd13264"
RELEASE_ID = "phase-6-entry-20260724-f338eb5d"
EVIDENCE_RELATIVE = Path("test-reports/temporal-first") / RELEASE_ID / "phase-6-entry"
EVIDENCE = ROOT / EVIDENCE_RELATIVE
CHECKPOINT = ROOT / "docs/runbooks/temporal-first/phase-6-p6.0-entry-checkpoint.md"
PLAN = ROOT / "plans/phase-6-hearing-pilot-execution.md"
BATCHES = ROOT / "plans/phase-6-hearing-pilot-test-batches.yaml"
BRIEFS = ROOT / "plans/phase-6-owner-briefs.yaml"
REPORTS = {
    "static_phase6_entry": ("static-phase6-entry.xml", 37),
    "python_phase6_entry": ("python-phase6-entry.xml", 23),
    "java_phase6_entry": ("java-phase6-entry.xml", 18),
    "frontend_phase6_entry": ("frontend-phase6-entry.xml", 70),
}
EXPECTED_FILES = {
    "artifact-sha256.json",
    "candidate.txt",
    "entry-metrics.json",
    "frontend-phase6-entry.xml",
    "java-phase6-entry.xml",
    "phase6-entry-execution-manifest.json",
    "python-phase6-entry.xml",
    "static-phase6-entry.xml",
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


def test_p6_0_evidence_is_a_direct_exact_candidate_bound_commit() -> None:
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


def test_p6_0_candidate_contains_only_entry_contract_scope() -> None:
    _git("merge-base", "--is-ancestor", BASE, CANDIDATE)
    changed = _git("diff", "--name-only", f"{BASE}..{CANDIDATE}").splitlines()
    assert changed
    allowed_prefixes = (
        "contracts/agent-platform/hearing/v2/",
        "docs/architecture/adr/0015-",
        "docs/runbooks/temporal-first/phase-6-",
        "plans/phase-6-",
        "scripts/generate_phase6_entry_evidence.py",
        "scripts/run_phase6_entry_checkpoint.py",
        "tests/static/test_phase6_",
    )
    allowed_exact = {"plans/temporal-langgraph-room-refactor.md"}
    assert all(path.startswith(allowed_prefixes) or path in allowed_exact for path in changed)
    assert not any(path.startswith("java-api-service/src/main/") for path in changed)
    assert not any(path.startswith("python-agent-service/app/") for path in changed)
    assert not any(path.startswith("frontend/src/") for path in changed)
    assert not any("V044__" in path for path in changed)


def test_p6_0_bundle_hashes_reports_and_execution_seal_are_authentic() -> None:
    index = _json("artifact-sha256.json")
    assert index["schema_version"] == "phase6-entry-artifact-index.v1"
    assert index["candidate_commit"] == CANDIDATE
    indexed = {item["path"]: item for item in index["artifacts"]}
    assert set(indexed) == EXPECTED_FILES - {"artifact-sha256.json"}

    for path in EVIDENCE.iterdir():
        payload = path.read_bytes()
        assert b"\r" not in payload
        logical = (EVIDENCE_RELATIVE / path.name).as_posix()
        assert _git("hash-object", "--stdin", payload=payload) == _git(
            "hash-object", f"--path={logical}", "--stdin", payload=payload
        )
        if path.name in indexed:
            assert indexed[path.name] == {
                "path": path.name,
                "sha256": _sha256(path),
                "bytes": path.stat().st_size,
            }

    metrics = _json("entry-metrics.json")
    manifest = _json("phase6-entry-execution-manifest.json")
    runner._assert_execution_manifest_seal(manifest)
    assert metrics["execution_manifest"]["sha256"] == _sha256(
        EVIDENCE / "phase6-entry-execution-manifest.json"
    )
    assert metrics["execution_manifest"]["manifest_sha256"] == manifest[
        "manifest_sha256"
    ]
    assert manifest["status"] == runner.GREEN_STATUS
    assert manifest["candidate_commit"] == CANDIDATE
    assert manifest["quarantined_attempts_reused"] is False
    assert manifest["quarantined_attempts"] == []
    assert manifest["pending_failure"] is None

    source_metrics = {item["command_id"]: item for item in metrics["source_suites"]}
    for command_id, (filename, expected_tests) in REPORTS.items():
        report = runner.parse_junit(EVIDENCE / filename)
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
    assert metrics["totals"]["tests"] == 148
    assert metrics["totals"]["failures"] == 0
    assert metrics["totals"]["errors"] == 0
    assert metrics["totals"]["skipped"] == 0
    assert metrics["entry_decision"] == {
        "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
        "entry_effect_after_commit": "P6_0_ENGINEERING_ENTRY_PASS",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "MIG-006": "PENDING_PROMOTION",
        "implementation_allowed_before_commit": False,
    }
    assert metrics["runtime_restrictions"] == {
        "allowed_new_runtime_modes_after_commit": [
            "DISABLED",
            "JAVA_SIGNED_SYNTHETIC_SHADOW",
        ],
        "legacy_formal_java_path_preserved": True,
        "formal_hearing_graph_sink": False,
        "temporal_hearing_allocation": False,
        "real_or_party_data_shadow": False,
        "production_traffic": False,
        "canary": False,
        "promotion": False,
    }


def test_p6_0_checkpoint_releases_only_restricted_engineering() -> None:
    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    for required in (
        "P6.0: PASS",
        "contract_gate: P6.0 PASS",
        "next_phase_permission: PHASE_6_ENGINEERING_ONLY",
        "promotion_gate: PENDING",
        "MIG-004: PENDING_PROMOTION",
        "MIG-005: PENDING_PROMOTION",
        "MIG-006: PENDING_PROMOTION",
        "real_or_party_data_shadow: FORBIDDEN",
        "temporal_hearing_allocation: FORBIDDEN",
        "formal_graph_sink: FORBIDDEN",
        "java_hearing_business_writer: SOLE_FORMAL_WRITER",
        CANDIDATE,
        EVIDENCE_COMMIT,
        "P6-A1",
        "P6-B1",
        "P6-C1",
        "P6-D1",
        "P6-E1",
    ):
        assert required in checkpoint

    plan = PLAN.read_text(encoding="utf-8")
    assert "contract_gate: P6.0 PASS" in plan
    assert "next_phase_permission: PHASE_6_ENGINEERING_ONLY" in plan
    assert CANDIDATE in plan and EVIDENCE_COMMIT in plan
    batches = yaml.safe_load(BATCHES.read_text(encoding="utf-8"))
    assert batches["document_status"] == "P6_0_PASS_ENGINEERING_ACTIVE"
    assert batches["gate"]["contract_gate_status"] == "PASS"
    assert batches["gate"]["accepted_phase_6_candidate_sha"] == CANDIDATE
    assert batches["gate"]["phase_6_entry_evidence_sha"] == EVIDENCE_COMMIT
    assert batches["batches"]["batch_0_entry"]["status"] == "PASS"
    briefs = yaml.safe_load(BRIEFS.read_text(encoding="utf-8"))
    assert briefs["document_status"] == "P6_0_PASS_ENGINEERING_ACTIVE"
    assert briefs["gate"]["dispatch_state"] == "READY_FOR_FIRST_WAVE"
    assert briefs["gate"]["implementation_authorized"] is True
    assert briefs["gate"]["blocked_reasons"] == []

