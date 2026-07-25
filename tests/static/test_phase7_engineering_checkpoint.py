from __future__ import annotations

import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from scripts import run_phase7_candidate_checkpoint as candidate_runner


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "4ddeeabb39ce7b7de41ecc4f44e17ece389d2840"
EVIDENCE_COMMIT = "f1c1ca16228641f1072eb358c6df9235dc239914"
RELEASE_ID = "phase-7-20260725-4ddeeabb"
EVIDENCE_PREFIX = (
    f"test-reports/temporal-first/{RELEASE_ID}/phase-7-candidate"
)
CHECKPOINT_PATH = "docs/runbooks/temporal-first/phase-7-engineering-checkpoint.md"
TEST_PATH = "tests/static/test_phase7_engineering_checkpoint.py"
INDEX_NAME = "artifact-sha256.json"
MANIFEST_NAME = "phase7-candidate-execution-manifest.json"
PROVENANCE_NAME = "provenance-manifest.json"
P0_REVIEW_NAME = "p0-review-disposition.json"
DECISION_NAME = "phase7-engineering-decision.json"
SOURCE_ENVIRONMENT_NAME = "source-tree-environment.json"
P0_REVIEW_SHA256 = "7a59830b1bd5148eb6a47ab5cc286d45be7caee93bd2dd81ea4d44544d5d7ef4"
COMMAND_RESULTS = (
    ("static_phase7_candidate", "static-phase7-candidate.xml", 149),
    ("python_phase7_candidate", "python-phase7-candidate.xml", 22),
    ("java_phase7_candidate", "java-phase7-candidate.xml", 276),
    ("frontend_phase7_candidate", "frontend-phase7-candidate.xml", 60),
)
P0_FINDING_IDS = [
    "P0-P7-CONFORMANCE-APPROVE-SNAPSHOT-010",
    "P0-P7-REVIEW-CAUSAL-REVISION-009",
    "P0-P7-TEMPORAL-AUTHORITY-TIME-001",
    "P0-P7-TEMPORAL-CAUSAL-LIMIT-002",
    "P0-P7-TEMPORAL-WIRE-BOUND-003",
    "P0-P7-TIC-ACTION-MULTISET-AUTHORITY-011",
    "P0-P7-TIC-CLOSURE-ATOMICITY-007",
    "P0-P7-TIC-EPOCH-RESERVATION-AUTHORITY-013",
    "P0-P7-TIC-EVALUATION-AUTHORITY-008",
    "P0-P7-TIC-PROJECTION-AUTHORITY-004",
    "P0-P7-TIC-RETRY-CLASS-PARITY-012",
    "P0-P7-TIC-RETRY-SAFETY-006",
    "P0-P7-TIC-TIMESTAMP-REPLAY-005",
]
RUNTIME_RESTRICTIONS = {
    "canary": False,
    "formal_outcome_sink": False,
    "formal_outcome_workflow": False,
    "production_traffic": False,
    "promotion": False,
    "real_case_or_party_data": False,
    "real_case_shadow": False,
    "real_tool_effect": False,
    "temporal_outcome_allocation": False,
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


def _blob(relative: str) -> bytes:
    return _git_bytes("show", f"{EVIDENCE_COMMIT}:{EVIDENCE_PREFIX}/{relative}")


def _json(relative: str) -> dict:
    value = json.loads(_blob(relative))
    assert isinstance(value, dict)
    return value


def _acceptance_commit() -> str:
    commits = _git(
        "log", "--diff-filter=A", "--format=%H", "--", CHECKPOINT_PATH
    ).splitlines()
    assert len(commits) == 1
    return commits[0]


def _evidence_tree_entries() -> dict[str, tuple[str, str]]:
    entries: dict[str, tuple[str, str]] = {}
    for record in _git_bytes(
        "ls-tree", "-r", "-z", EVIDENCE_COMMIT, "--", EVIDENCE_PREFIX
    ).split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, object_type, _object_id = metadata.decode("ascii").split(" ", 2)
        entries[raw_path.decode("utf-8")] = (mode, object_type)
    return entries


def test_phase7_candidate_evidence_and_acceptance_form_exact_chain() -> None:
    assert _git("rev-list", "--parents", "-n", "1", EVIDENCE_COMMIT).split() == [
        EVIDENCE_COMMIT,
        CANDIDATE,
    ]
    acceptance = _acceptance_commit()
    assert _git("rev-list", "--parents", "-n", "1", acceptance).split() == [
        acceptance,
        EVIDENCE_COMMIT,
    ]
    assert set(
        _git(
            "diff-tree",
            "--no-commit-id",
            "--name-only",
            "-r",
            "--no-renames",
            acceptance,
        ).splitlines()
    ) == {CHECKPOINT_PATH, TEST_PATH}

    evidence_records = [
        line.split("\t", 1)
        for line in _git(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "-r",
            "--no-renames",
            EVIDENCE_COMMIT,
        ).splitlines()
    ]
    assert len(evidence_records) == 47
    assert all(
        status == "A" and path.startswith(f"{EVIDENCE_PREFIX}/")
        for status, path in evidence_records
    )
    entries = _evidence_tree_entries()
    assert set(entries) == {path for _status, path in evidence_records}
    assert set(entries.values()) == {("100644", "blob")}


def test_phase7_artifact_index_binds_committed_bytes_and_modes() -> None:
    index = _json(INDEX_NAME)
    assert set(index) == {"artifacts", "candidate_commit", "schema_version"}
    assert index["schema_version"] == "phase7-candidate-artifact-index.v1"
    assert index["candidate_commit"] == CANDIDATE
    artifacts = index["artifacts"]
    assert isinstance(artifacts, list) and len(artifacts) == 46
    indexed_paths = [item["path"] for item in artifacts]
    assert len(indexed_paths) == len(set(indexed_paths))

    entries = _evidence_tree_entries()
    expected_indexed = {
        path.removeprefix(f"{EVIDENCE_PREFIX}/")
        for path in entries
        if path != f"{EVIDENCE_PREFIX}/{INDEX_NAME}"
    }
    assert set(indexed_paths) == expected_indexed
    assert entries[f"{EVIDENCE_PREFIX}/{INDEX_NAME}"] == ("100644", "blob")
    for artifact in artifacts:
        assert set(artifact) == {"bytes", "path", "sha256"}
        payload = _blob(artifact["path"])
        assert artifact["bytes"] == len(payload)
        assert artifact["sha256"] == hashlib.sha256(payload).hexdigest()
        assert entries[f"{EVIDENCE_PREFIX}/{artifact['path']}"] == (
            "100644",
            "blob",
        )


def test_phase7_committed_manifest_is_sealed_and_exactly_green() -> None:
    manifest = _json(MANIFEST_NAME)
    seal_validator = getattr(candidate_runner, "_assert_execution_manifest_seal", None)
    assert callable(seal_validator)
    seal_validator(manifest)
    assert [record["id"] for record in manifest["commands"]] == [
        command_id for command_id, _report, _tests in COMMAND_RESULTS
    ]
    assert manifest["status"] == candidate_runner.GREEN_STATUS
    assert manifest["quarantined_attempts"] == []
    assert manifest["quarantined_attempts_reused"] is False
    assert manifest["pending_failure"] is None
    assert manifest["MIG-006"] == manifest["MIG-007"] == "PENDING_PROMOTION"


def test_phase7_normalized_reports_are_candidate_bound_and_exactly_green() -> None:
    total = 0
    for command_id, report_name, expected_tests in COMMAND_RESULTS:
        root = ET.fromstring(_blob(report_name))
        assert root.tag == "testsuites"
        assert root.attrib["candidate_commit"] == CANDIDATE
        assert root.attrib["source_command_id"] == command_id
        assert int(root.attrib["tests"]) == expected_tests
        assert int(root.attrib["failures"]) == 0
        assert int(root.attrib["errors"]) == 0
        assert int(root.attrib["skipped"]) == 0
        total += expected_tests
    assert total == 507


def test_phase7_provenance_review_and_decision_bind_exact_objects() -> None:
    manifest_blob = _blob(MANIFEST_NAME)
    provenance_blob = _blob(PROVENANCE_NAME)
    review_blob = _blob(P0_REVIEW_NAME)
    environment_blob = _blob(SOURCE_ENVIRONMENT_NAME)
    manifest = json.loads(manifest_blob)
    provenance = json.loads(provenance_blob)
    review = json.loads(review_blob)
    decision = _json(DECISION_NAME)

    assert review == {
        "candidate_commit": CANDIDATE,
        "closed_finding_ids": P0_FINDING_IDS,
        "open_p0_count": 0,
        "review_scope": "CONSOLIDATED_POST_INTEGRATION_P0_ONLY",
        "reviewed_topics": [
            "TEMPORAL_DETERMINISM_AND_AUTHORITY",
            "TRANSACTION_IDEMPOTENCY_AND_COMPENSATION",
            "PRIVACY_TOOL_CAPABILITY_AND_CLIENT_AUTHORITY",
        ],
        "schema_version": "phase7-p0-review-disposition.v1",
        "status": "ALL_P0_CLOSED",
    }
    assert len(P0_FINDING_IDS) == 13 and P0_FINDING_IDS == sorted(P0_FINDING_IDS)
    assert hashlib.sha256(review_blob).hexdigest() == P0_REVIEW_SHA256

    assert provenance["candidate_commit"] == CANDIDATE
    assert provenance["schema_version"] == "phase7-candidate-provenance-manifest.v1"
    assert provenance["artifact_count"] == len(provenance["artifacts"]) == 35
    assert {item["record_scope"] for item in provenance["artifacts"]} == {"accepted"}
    for item in provenance["artifacts"]:
        payload = _blob(item["archive_path"])
        digest = hashlib.sha256(payload).hexdigest()
        assert item["bytes"] == len(payload)
        assert item["source_sha256"] == item["archive_sha256"] == digest

    assert decision["candidate_commit"] == CANDIDATE
    assert decision["release_id"] == RELEASE_ID
    assert decision["execution_manifest"] == {
        "manifest_sha256": manifest["manifest_sha256"],
        "path": MANIFEST_NAME,
        "schema_version": manifest["schema_version"],
        "sha256": hashlib.sha256(manifest_blob).hexdigest(),
    }
    assert decision["execution_provenance"] == {
        "artifact_count": 35,
        "manifest": PROVENANCE_NAME,
        "manifest_sha256": hashlib.sha256(provenance_blob).hexdigest(),
        "mixed_attempt_results": False,
        "quarantined_attempts_reused": False,
    }
    assert decision["p0_review"] == {
        "closed_finding_ids": P0_FINDING_IDS,
        "open_p0_count": 0,
        "path": P0_REVIEW_NAME,
        "sha256": P0_REVIEW_SHA256,
        "status": "ALL_P0_CLOSED",
    }
    assert decision["source_tree_environment"] == {
        "path": SOURCE_ENVIRONMENT_NAME,
        "sha256": hashlib.sha256(environment_blob).hexdigest(),
    }
    assert decision["totals"] == {
        "errors": 0,
        "failures": 0,
        "skipped": 0,
        "tests": 507,
        "time": 172.041549,
    }
    assert decision["runtime_restrictions"] == RUNTIME_RESTRICTIONS
    assert all(value is False for value in decision["runtime_restrictions"].values())
    assert decision["MIG-006"] == decision["MIG-007"] == "PENDING_PROMOTION"
    assert decision["next_phase_permission_after_commit"] == "PHASE_8_ENGINEERING_ONLY"


def test_phase7_checkpoint_records_only_engineering_permission() -> None:
    text = (ROOT / CHECKPOINT_PATH).read_text(encoding="utf-8")
    for claim in (
        CANDIDATE,
        EVIDENCE_COMMIT,
        RELEASE_ID,
        "engineering_checkpoint: PASS",
        "promotion_gate: PENDING",
        "next_phase_permission: PHASE_8_ENGINEERING_ONLY",
        "MIG-006: PENDING_PROMOTION",
        "MIG-007: PENDING_PROMOTION",
        "149 static, 22 Python, 276 Java, and 60 frontend",
        "507 tests with zero failures, errors, or skips",
        "ALL_P0_CLOSED",
        "open_p0_count: 0",
        "existing formal Java Outcome writer remains\nauthoritative",
        "DISABLED",
        "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
        "formal_outcome_activation: FORBIDDEN",
        "formal_outcome_workflow: FORBIDDEN",
        "formal_outcome_sink: FORBIDDEN",
        "temporal_outcome_allocation: FORBIDDEN",
        "real_case_or_party_data: FORBIDDEN",
        "real_data_shadow: FORBIDDEN",
        "real_case_shadow: FORBIDDEN",
        "real_tool_capability: FORBIDDEN",
        "real_tool_effect: FORBIDDEN",
        "production_traffic: FORBIDDEN",
        "canary: FORBIDDEN",
        "promotion: FORBIDDEN",
    ):
        assert claim in text
    assert all(f"`{finding_id}`" in text for finding_id in P0_FINDING_IDS)
