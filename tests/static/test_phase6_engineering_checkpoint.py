from __future__ import annotations

import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "ea046eae2792cd5afb9929bca40da8fb8c77a9bd"
EVIDENCE_COMMIT = "e674263e9026e3fec46ec295767d432807f5ab44"
RELEASE_ID = "phase-6-20260724-ea046eae"
EVIDENCE_RELATIVE = Path("test-reports/temporal-first") / RELEASE_ID / "phase-6"
EVIDENCE = ROOT / EVIDENCE_RELATIVE
CHECKPOINT_RELATIVE = Path(
    "docs/runbooks/temporal-first/phase-6-engineering-checkpoint.md"
)
TEST_RELATIVE = Path("tests/static/test_phase6_engineering_checkpoint.py")
REPORTS = {
    "java-phase6-junit.xml": ("java", 143),
    "python-phase6-junit.xml": ("python", 60),
    "frontend-phase6-junit.xml": ("frontend", 74),
    "static-phase6-junit.xml": ("static", 46),
}
EXPECTED_STATUS = {
    "engineering_checkpoint": "PASS",
    "promotion_gate": "PENDING",
    "next_phase_permission": "PHASE_7_ENGINEERING_ONLY",
    "MIG-004": "PENDING_PROMOTION",
    "MIG-005": "PENDING_PROMOTION",
    "MIG-006": "PENDING_PROMOTION",
}


def _git(*arguments: str) -> str:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return process.stdout.strip()


def _git_bytes(*arguments: str) -> bytes:
    return subprocess.run(
        ["git", *arguments], cwd=ROOT, check=True, capture_output=True
    ).stdout


def _json(name: str) -> dict:
    value = json.loads((EVIDENCE / name).read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _acceptance_commit() -> str:
    values = _git(
        "log",
        "--diff-filter=A",
        "--format=%H",
        "--",
        CHECKPOINT_RELATIVE.as_posix(),
    ).splitlines()
    assert len(values) == 1
    return values[0]


def test_phase6_candidate_evidence_and_acceptance_form_an_exact_chain() -> None:
    assert _git("rev-parse", f"{EVIDENCE_COMMIT}^") == CANDIDATE
    acceptance = _acceptance_commit()
    assert _git("rev-parse", f"{acceptance}^") == EVIDENCE_COMMIT
    assert set(
        _git("diff-tree", "--no-commit-id", "--name-only", "-r", acceptance).splitlines()
    ) == {CHECKPOINT_RELATIVE.as_posix(), TEST_RELATIVE.as_posix()}

    evidence_paths = set(
        _git(
            "diff-tree", "--no-commit-id", "--name-only", "-r", EVIDENCE_COMMIT
        ).splitlines()
    )
    assert len(evidence_paths) == 20
    assert all(value.startswith(f"{EVIDENCE_RELATIVE.as_posix()}/") for value in evidence_paths)


def test_phase6_evidence_index_binds_exact_git_blobs() -> None:
    index = _json("artifact-sha256.json")
    assert index["schema_version"] == "phase6-candidate-artifact-index.v1"
    assert index["candidate_commit"] == CANDIDATE
    assert len(index["artifacts"]) == 19
    for relative, expected in index["artifacts"].items():
        blob = _git_bytes(
            "show",
            f"{EVIDENCE_COMMIT}:{(EVIDENCE_RELATIVE / relative).as_posix()}",
        )
        assert hashlib.sha256(blob).hexdigest() == expected


def test_phase6_source_reports_are_candidate_bound_and_green() -> None:
    total = 0
    for name, (command_id, expected_tests) in REPORTS.items():
        root = ET.parse(EVIDENCE / name).getroot()
        assert root.tag == "testsuites"
        assert root.attrib["candidate_commit"] == CANDIDATE
        assert root.attrib["source_command_id"] == command_id
        assert int(root.attrib["tests"]) == expected_tests
        assert int(root.attrib["failures"]) == 0
        assert int(root.attrib["errors"]) == 0
        assert int(root.attrib["skipped"]) == 0
        total += expected_tests
    assert total == 323


def test_phase6_manifest_and_summary_keep_promotion_closed() -> None:
    manifest = _json("phase6-candidate-execution-manifest.json")
    summary = _json("phase6-verification-summary.json")
    assert manifest["candidate_commit"] == summary["candidate_commit"] == CANDIDATE
    assert manifest["status"] == "PASS"
    assert [item["id"] for item in manifest["commands"]] == [
        "java",
        "python",
        "frontend",
        "static",
    ]
    assert summary["total_tests"] == 323
    for key, expected in EXPECTED_STATUS.items():
        assert manifest[key] == summary[key] == expected
    for value in manifest["commands"]:
        assert value["exit_code"] == 0
        assert value["failure_classification"] == "NONE"
        assert value["report"]["failures"] == 0
        assert value["report"]["errors"] == 0
        assert value["report"]["skipped"] == 0
    restrictions = manifest["runtime_restrictions"]
    assert restrictions["temporal_hearing_allocation"] == "forbidden"
    assert restrictions["formal_graph_sink"] == "forbidden"
    assert restrictions["real_case_shadow"] == "forbidden"
    assert restrictions["canary"] == restrictions["promotion"] == "forbidden"


def test_phase6_checkpoint_states_engineering_only_permission() -> None:
    text = (ROOT / CHECKPOINT_RELATIVE).read_text(encoding="utf-8")
    assert CANDIDATE in text and EVIDENCE_COMMIT in text
    assert "engineering_checkpoint: PASS" in text
    assert "next_phase_permission: PHASE_7_ENGINEERING_ONLY" in text
    assert "MIG-006: PENDING_PROMOTION" in text
    assert "temporal_hearing_allocation: FORBIDDEN" in text
    assert "formal_graph_sink: FORBIDDEN" in text
    assert "real_case_shadow: FORBIDDEN" in text
    assert "promotion: FORBIDDEN" in text
