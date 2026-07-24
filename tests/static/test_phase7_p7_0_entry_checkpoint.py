from __future__ import annotations

import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

import yaml

from scripts import run_phase7_entry_checkpoint as runner


ROOT = Path(__file__).resolve().parents[2]
A6 = "d18a1f130a925429e8c2dfd11352cea4ca8673a0"
C7 = "0aa260f722fced0eba4314bd4793e415b5bf0b05"
E7 = "e29cefb3e028bb84f6a227e46fecdf5711eba48c"
RELEASE_ID = "phase-7-entry-20260724-0aa260f7"
EVIDENCE_PREFIX = f"test-reports/temporal-first/{RELEASE_ID}/phase-7-entry"
CHECKPOINT_PATH = "docs/runbooks/temporal-first/phase-7-p7.0-entry-checkpoint.md"
BASELINE_PATH = "docs/runbooks/temporal-first/phase-7-p7.0-baseline-inventory.md"
MATRIX_PATH = ROOT / "plans/phase-7-outcome-pilot-test-batches.yaml"
BRIEFS_PATH = ROOT / "plans/phase-7-owner-briefs.yaml"
PLAN_PATH = ROOT / "plans/phase-7-outcome-pilot-execution.md"
REPORTS = {
    "static_phase7_entry": ("static-phase7-entry.xml", 78),
    "python_phase7_entry": ("python-phase7-entry.xml", 3),
    "java_phase7_entry": ("java-phase7-entry.xml", 18),
    "frontend_phase7_entry": ("frontend-phase7-entry.xml", 41),
}
ACCEPTANCE_PATHS = {
    CHECKPOINT_PATH,
    BASELINE_PATH,
    "plans/phase-7-outcome-pilot-execution.md",
    "plans/phase-7-outcome-pilot-test-batches.yaml",
    "plans/phase-7-owner-briefs.yaml",
    "tests/static/test_phase7_entry_checkpoint.py",
    "tests/static/test_phase7_outcome_pilot_plan.py",
    "tests/static/test_phase7_p7_0_entry_checkpoint.py",
}


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return process.stdout


def _git(*arguments: str) -> str:
    return _git_bytes(*arguments).decode("utf-8").strip()


def _acceptance_commit() -> str:
    commits = _git(
        "log", "--diff-filter=A", "--format=%H", "--", CHECKPOINT_PATH
    ).splitlines()
    assert commits, "the Phase 7 acceptance checkpoint has not been committed"
    return commits[0]


def _evidence_blob(relative: str) -> bytes:
    return _git_bytes("show", f"{E7}:{EVIDENCE_PREFIX}/{relative}")


def _evidence_json(relative: str) -> dict[str, Any]:
    value = json.loads(_evidence_blob(relative))
    assert isinstance(value, dict)
    return value


def _yaml(path: Path) -> dict[str, Any]:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def test_p7_0_acceptance_commit_is_an_exact_direct_child_of_e7() -> None:
    acceptance = _acceptance_commit()
    assert _git("rev-list", "--parents", "-n", "1", acceptance).split() == [
        acceptance,
        E7,
    ]
    _git("merge-base", "--is-ancestor", acceptance, "HEAD")
    records = _git(
        "diff-tree",
        "--no-commit-id",
        "--name-status",
        "-r",
        "--no-renames",
        acceptance,
    ).splitlines()
    changed = {}
    for record in records:
        status, path = record.split("\t")
        changed[path.replace("\\", "/")] = status
    assert set(changed) == ACCEPTANCE_PATHS
    assert changed[CHECKPOINT_PATH] == "A"
    assert changed["tests/static/test_phase7_p7_0_entry_checkpoint.py"] == "A"
    assert all(status in {"A", "M"} for status in changed.values())


def test_p7_0_candidate_scope_and_evidence_commit_topology_are_exact() -> None:
    assert _git("merge-base", "--is-ancestor", A6, C7) == ""
    candidate_paths = runner.assert_contract_only_candidate(C7)
    assert len(candidate_paths) == 17
    assert set(candidate_paths) == set(
        _evidence_json("entry-metrics.json")["candidate_scope"]["changed_paths"]
    )

    assert _git("rev-list", "--parents", "-n", "1", E7).split() == [E7, C7]
    records = _git(
        "diff-tree",
        "--no-commit-id",
        "--name-status",
        "-r",
        "--no-renames",
        E7,
    ).splitlines()
    evidence_paths = []
    for record in records:
        status, path = record.split("\t")
        assert status == "A"
        assert path.startswith(EVIDENCE_PREFIX + "/")
        evidence_paths.append(path)
    assert len(evidence_paths) == len(set(evidence_paths)) == 28
    assert _evidence_blob("candidate.txt") == (C7 + "\n").encode("ascii")


def test_p7_0_evidence_index_and_provenance_are_closed_over_git_blobs() -> None:
    evidence_names = {
        path.removeprefix(EVIDENCE_PREFIX + "/")
        for path in _git(
            "diff-tree", "--no-commit-id", "--name-only", "-r", E7
        ).splitlines()
    }
    index = _evidence_json("artifact-sha256.json")
    assert index["schema_version"] == "phase7-entry-artifact-index.v1"
    assert index["candidate_commit"] == C7
    indexed = {item["path"]: item for item in index["artifacts"]}
    assert set(indexed) == evidence_names - {"artifact-sha256.json"}
    for relative, item in indexed.items():
        payload = _evidence_blob(relative)
        assert item == {
            "path": relative,
            "sha256": hashlib.sha256(payload).hexdigest(),
            "bytes": len(payload),
        }

    manifest = _evidence_json("phase7-entry-execution-manifest.json")
    runner._assert_execution_manifest_seal(manifest)
    assert manifest["candidate_commit"] == C7
    assert manifest["quarantined_attempts"] == []
    assert manifest["pending_failure"] is None
    assert manifest["quarantined_attempts_reused"] is False

    provenance = _evidence_json("provenance-manifest.json")
    artifacts = provenance["artifacts"]
    assert provenance == {
        "schema_version": "phase7-entry-provenance-manifest.v1",
        "candidate_commit": C7,
        "artifact_count": 18,
        "artifacts": artifacts,
    }
    source_paths = [item["source_path"] for item in artifacts]
    archive_paths = [item["archive_path"] for item in artifacts]
    assert len(source_paths) == len(set(source_paths)) == 18
    assert len(archive_paths) == len(set(archive_paths)) == 18
    assert all(path.startswith("attempts/") and ".." not in path for path in source_paths)
    assert all(path.startswith("p/") and ".." not in path for path in archive_paths)
    for item in artifacts:
        payload = _evidence_blob(item["archive_path"])
        digest = hashlib.sha256(payload).hexdigest()
        assert item["source_sha256"] == item["archive_sha256"] == digest
        assert item["bytes"] == len(payload)
        assert item["archive_path"] in indexed

    metrics = _evidence_json("entry-metrics.json")
    assert metrics["execution_provenance"] == {
        "archived": True,
        "artifact_count": 18,
        "mapping_manifest": "provenance-manifest.json",
        "mapping_manifest_sha256": hashlib.sha256(
            _evidence_blob("provenance-manifest.json")
        ).hexdigest(),
        "source_paths": source_paths,
        "archive_paths": archive_paths,
    }


def test_p7_0_reports_counts_and_engineering_permission_are_authentic() -> None:
    metrics = _evidence_json("entry-metrics.json")
    source_metrics = {item["command_id"]: item for item in metrics["source_suites"]}
    assert set(source_metrics) == set(REPORTS)
    for command_id, (filename, expected_tests) in REPORTS.items():
        payload = _evidence_blob(filename)
        root = ET.fromstring(payload)
        totals = {
            field: int(root.attrib[field])
            for field in ("tests", "failures", "errors", "skipped")
        }
        assert root.attrib["candidate_commit"] == C7
        assert root.attrib["source_command_id"] == command_id
        assert totals == {
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }
        assert source_metrics[command_id]["junit"] | {"time": 0} == totals | {
            "time": 0
        }
        assert source_metrics[command_id]["report_sha256"] == hashlib.sha256(
            payload
        ).hexdigest()

    assert metrics["totals"] | {"time": 0} == {
        "tests": 140,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0,
    }
    assert metrics["entry_decision"] == {
        "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
        "entry_effect_after_commit": "P7_0_ENGINEERING_ENTRY_PASS",
        "next_phase_permission_after_commit": "PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS",
        "engineering_implementation_after_commit": "ALLOWED_UNDER_ADR_0016_ONLY",
        "evidence_commit_requirement": "DIRECT_CHILD_OF_CANDIDATE",
        "implementation_allowed_before_commit": False,
        "promotion_gate": "PENDING",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
    }
    assert metrics["runtime_restrictions"] == {
        "allowed_new_runtime_modes_after_commit": [
            "DISABLED",
            "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
        ],
        "legacy_formal_java_path_preserved": True,
        "formal_outcome_workflow": False,
        "temporal_outcome_allocation": False,
        "formal_outcome_graph_sink": False,
        "real_tool_effects": False,
        "real_or_party_data_shadow": False,
        "production_traffic": False,
        "canary": False,
        "promotion": False,
    }


def test_p7_0_checkpoint_releases_a_to_g_but_no_formal_or_real_authority() -> None:
    checkpoint = (ROOT / CHECKPOINT_PATH).read_text(encoding="utf-8")
    for token in (
        "P7.0: PASS",
        "P7_0_ENGINEERING_ENTRY_PASS",
        C7,
        E7,
        RELEASE_ID,
        "Static Phase 7 contract, plan, evidence, and traceability gates | 78",
        "Python Evaluation baseline | 3",
        "Java Review, Outcome, policy, frozen packet, and Evaluation client baseline | 18",
        "Frontend Draft, Review, Outcome, and review API baseline | 41",
        "**Total** | **140**",
        "MIG-006: PENDING_PROMOTION",
        "MIG-007: PENDING_PROMOTION",
    ):
        assert token in checkpoint

    matrix = _yaml(MATRIX_PATH)
    gate = matrix["gate"]
    assert matrix["document_status"] == "P7_0_PASS_ENGINEERING_ACTIVE"
    assert gate["contract_gate_status"] == "PASS"
    assert gate["implementation_authorized"] is True
    assert gate["implementation_owners_state"] == "READY"
    assert gate["accepted_phase_7_candidate_C7"] == C7
    assert gate["accepted_phase_7_evidence_E7"] == E7
    assert matrix["batches"]["batch_0_entry"]["source_test_counts"] == {
        "static": 78,
        "python": 3,
        "java": 18,
        "frontend": 41,
        "total": 140,
    }
    assert matrix["team"]["implementation_owners"] == list("ABCDEFG")
    assert matrix["team"]["logical_p0_review_lanes"] == ["R1"]
    assert matrix["resources"]["heavy_test_slots"] == 2
    assert matrix["resources"]["light_test_slots"] == 2
    constraints = gate["traffic_constraints"]
    assert constraints["legacy_java_remains_formal"] is True
    for field, value in constraints.items():
        if field != "legacy_java_remains_formal":
            assert value is False, field

    briefs = _yaml(BRIEFS_PATH)
    assert briefs["gate"]["dispatch_state"] == "READY_FOR_FIRST_WAVE"
    assert briefs["gate"]["implementation_authorized"] is True
    assert briefs["gate"]["blocked_reasons"] == []
    assert briefs["team"]["delegated_implementation_owners"] == list("ABCDEFG")
    assert briefs["team"]["logical_p0_review_lanes"] == ["R1"]
    assert briefs["team"]["heavy_test_slots"] == 2
    assert briefs["team"]["light_test_slots"] == 2
    for field in (
        "temporal_outcome_allocation_allowed",
        "formal_outcome_workflow_activation_allowed",
        "real_tool_effect_allowed",
        "real_or_party_data_allowed",
        "canary_or_promotion_allowed",
    ):
        assert briefs["gate"][field] is False
    assert briefs["gate"]["MIG-006"] == "PENDING_PROMOTION"
    assert briefs["gate"]["MIG-007"] == "PENDING_PROMOTION"

    plan = PLAN_PATH.read_text(encoding="utf-8")
    assert "contract_gate: P7.0 PASS" in plan
    assert "formal_outcome_workflow_activation: FORBIDDEN" in plan
    assert "outcome_temporal_allocation: FORBIDDEN" in plan
    assert "promotion: FORBIDDEN" in plan
