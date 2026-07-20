from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

import yaml

from scripts.generate_phase3_candidate_evidence import parse_junit


ROOT = Path(__file__).resolve().parents[2]
RELEASE_ID = "phase-4-entry-20260720-r3"
CANDIDATE = "cf1ae3533bf2525ee43574e81c45621f29e338a0"
EVIDENCE = (
    ROOT
    / "test-reports"
    / "temporal-first"
    / RELEASE_ID
    / "phase-4-entry"
)
CHECKPOINT = (
    ROOT
    / "docs"
    / "runbooks"
    / "temporal-first"
    / "phase-4-p4.0-entry-checkpoint.md"
)

REPORTS = {
    "p4_entry_static": ("static-entry-junit.xml", 24),
    "p4_entry_python": ("python-entry-junit.xml", 70),
    "p4_entry_java": ("java-entry-junit.xml", 83),
    "p4_entry_frontend": ("frontend-entry-junit.xml", 120),
}

EXPECTED_CLASSNAMES = {
    "p4_entry_static": {
        "tests.static.test_agent_platform_schema_contracts",
        "tests.static.test_graph_import_boundaries",
        "tests.static.test_phase4_intake_pilot_plan",
        "tests.static.test_temporal_refactor_traceability",
    },
    "p4_entry_python": {
        "tests.agents.test_intake_case_detail_dossier",
        "tests.agents.test_intake_prompt_compaction",
        "tests.agents.test_intake_resource_bounds",
        "tests.agents.test_intake_turn",
        "tests.model_runtime.test_runnable_factory",
        "tests.security.test_graph_security_runtime",
        "tests.test_streaming",
        "tests.test_streaming_v2",
    },
    "p4_entry_java": {
        "com.example.dispute.agentstream.AgentRunStreamEventServiceTest",
        "com.example.dispute.room.AgentConversationSessionResolverTest",
        "com.example.dispute.room.IntakeAgentTurnServiceTest",
        "com.example.dispute.room.IntakeProgressServiceTest",
        "com.example.dispute.room.IntakeRoomControllerTest",
        "com.example.dispute.room.IntakeRoomServiceIntegrationTest",
        "com.example.dispute.room.IntakeRoomServiceTest",
        "com.example.dispute.room.IntakeSequentialWorkflowTest",
        "com.example.dispute.room.RoomMessageAndEventServiceTest",
        "com.example.dispute.room.RoomTurnMemoryQueryServiceTest",
        "com.example.dispute.workflow.api.mig001.Mig001ScenarioServiceTest",
        "com.example.dispute.workflow.application.ConfiguredRoomEpochSelectorTest",
        "com.example.dispute.workflow.config.OrchestrationCutoverPropertiesTest",
    },
    "p4_entry_frontend": {
        "src/api/agentStream.test.js",
        "src/components/room/RoomShell.test.js",
        "src/stores/agentStream.test.js",
        "src/stores/room.test.js",
        "src/views/disputes/DisputeOverviewView.test.js",
        "src/views/disputes/IntakeRoomView.test.js",
    },
}


def _json(name: str) -> dict:
    return json.loads((EVIDENCE / name).read_text(encoding="utf-8"))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _candidate_blob(path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{CANDIDATE}:{path}"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def _expected_baseline_ids() -> set[str]:
    return {
        *(f"INT-{number:03d}" for number in range(1, 11)),
        "OVR-003",
        *(f"CORE-{number:03d}" for number in range(4, 11)),
        *(f"SEC-{number:03d}" for number in range(1, 7)),
        "UI-001",
        "UI-003",
        "UI-004",
    }


def test_p4_0_entry_bundle_is_exact_and_candidate_bound() -> None:
    entries = {path.name: path for path in EVIDENCE.iterdir()}
    assert set(entries) == {
        "candidate-commit.txt",
        "static-entry-junit.xml",
        "python-entry-junit.xml",
        "java-entry-junit.xml",
        "frontend-entry-junit.xml",
        "entry-metrics.json",
        "baseline-id-coverage.json",
        "failure-classification.json",
    }
    assert all(path.is_file() for path in entries.values())
    assert (EVIDENCE / "candidate-commit.txt").read_text(encoding="utf-8") == (
        CANDIDATE + "\n"
    )
    subprocess.run(
        ["git", "cat-file", "-e", f"{CANDIDATE}^{{commit}}"],
        cwd=ROOT,
        check=True,
    )

    total = 0
    for command_id, (filename, expected_tests) in REPORTS.items():
        report = parse_junit(EVIDENCE / filename)
        assert report.candidate_commit == CANDIDATE
        assert report.command_id == command_id
        assert report.totals["tests"] == expected_tests
        assert report.totals["failures"] == 0
        assert report.totals["errors"] == 0
        assert report.totals["skipped"] == 0
        assert not {
            child.tag.rsplit("}", 1)[-1]
            for case in report.cases
            for child in case.element.iter()
        } & {"system-out", "system-err"}
        assert {case.classname for case in report.cases} == EXPECTED_CLASSNAMES[
            command_id
        ]
        total += expected_tests
    assert total == 297


def test_p4_0_metrics_authenticate_commands_reports_and_environment() -> None:
    metrics = _json("entry-metrics.json")
    assert metrics["schema_version"] == "phase-4-entry-evidence.v1"
    assert metrics["release_id"] == RELEASE_ID
    assert metrics["gate"] == "P4.0"
    assert metrics["result"] == "PASS"
    assert metrics["contract_candidate"]["commit"] == CANDIDATE
    assert metrics["contract_candidate"]["detached"] is True
    assert metrics["phase_3_prerequisite"] == {
        "candidate_commit": "9351a9d65230ce5bfc332bc59ec567ecb8a964c5",
        "evidence_commit": "ffa24bba9848e7492b9946c68e5e56977f9494ce",
        "engineering_checkpoint": "PASS",
        "promotion_gate": "PENDING",
        "next_phase_permission": "PHASE_4_ENGINEERING_ONLY",
        "MIG-003": "PENDING_PROMOTION",
    }

    verification = metrics["verification"]
    assert verification["candidate_head_before"] == CANDIDATE
    assert verification["candidate_head_after"] == CANDIDATE
    assert verification["tracked_worktree_clean_before"] is True
    assert verification["tracked_worktree_clean_after"] is True
    assert verification["index_clean_before"] is True
    assert verification["index_clean_after"] is True
    assert verification["mixed_candidate_results"] is False
    assert verification["quarantined_reports_reused"] is False
    assert verification["archive_normalization"] == {
        "policy": (
            "Remove system-out and system-err nodes; preserve testcase identity, "
            "status, and duration."
        ),
        "output_nodes_removed": 8,
        "archived_output_nodes": 0,
    }

    source_suites = {
        item["command_id"]: item for item in metrics["source_suites"]
    }
    matrix = yaml.safe_load(
        _candidate_blob("plans/phase-4-intake-pilot-test-batches.yaml")
    )
    matrix_commands = {
        item["id"]: item["command"]
        for item in matrix["batches"]["P4-BATCH-0"]["source_commands"]
    }
    assert set(source_suites) == set(REPORTS)
    assert set(matrix_commands) == set(REPORTS)
    for command_id, (filename, expected_tests) in REPORTS.items():
        item = source_suites[command_id]
        assert item["exit_code"] == 0
        assert item["command_sha256"] == hashlib.sha256(
            item["command"].encode("utf-8")
        ).hexdigest()
        matrix_command = matrix_commands[command_id]
        assert item["matrix_command_sha256"] == hashlib.sha256(
            matrix_command.encode("utf-8")
        ).hexdigest()
        report_path = (
            metrics["contract_candidate"]["worktree"]
            + "\\test-reports\\temporal-first\\"
            + RELEASE_ID
            + "\\phase-4-entry\\"
            + filename
        )
        if command_id in {"p4_entry_static", "p4_entry_python"}:
            assert item["reporting_instrumentation"] == (
                "--junitxml=<absolute-report-path>"
            )
            assert item["command"] == matrix_command + " --junitxml=" + report_path
        elif command_id == "p4_entry_frontend":
            assert item["reporting_instrumentation"] == (
                "--reporter=junit --outputFile=<absolute-report-path>"
            )
            assert item["command"] == (
                matrix_command + " --reporter=junit --outputFile=" + report_path
            )
        else:
            suffix = '"-Dsurefire.reportNameSuffix=phase4-entry-cf1ae353-r3"'
            assert item["reporting_instrumentation"] == suffix.strip('"')
            assert matrix_command.endswith(" test")
            assert item["command"] == matrix_command[:-5] + " " + suffix + " test"
        junit = item["junit"]
        assert junit["path"] == filename
        assert junit["sha256"] == _sha256(EVIDENCE / filename)
        assert junit["bytes"] == (EVIDENCE / filename).stat().st_size
        assert junit["tests"] == expected_tests
        assert junit["failures"] == 0
        assert junit["errors"] == 0
        assert junit["skipped"] == 0

    assert metrics["totals"] == {
        "tests": 297,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "junit_time_seconds": 18.193415,
    }
    assert metrics["protected_worktree_exception"] == {
        "path": "docs/api/README.md",
        "state": "deleted_unstaged",
        "touched_by_phase_4": False,
        "staged_in_evidence_commit": False,
    }

    for manifest in metrics["dependency_manifests"]:
        assert manifest["git_blob_sha256"] == hashlib.sha256(
            _candidate_blob(manifest["path"])
        ).hexdigest()


def test_p4_0_baseline_coverage_resolves_to_real_junit_classnames() -> None:
    coverage = _json("baseline-id-coverage.json")
    assert coverage["candidate_commit"] == CANDIDATE
    assert coverage["release_id"] == RELEASE_ID
    assert coverage["result"] == "PASS"
    assert coverage["expected_id_count"] == 27
    assert coverage["covered_id_count"] == 27
    assert coverage["missing_ids"] == []
    assert coverage["duplicate_ids"] == []

    rows = coverage["coverage"]
    ids = [row["id"] for row in rows]
    assert set(ids) == _expected_baseline_ids()
    assert len(ids) == len(set(ids)) == 27
    assert all(row["status"] == "PASS" for row in rows)

    report_classnames = {
        filename: {
            case.classname for case in parse_junit(EVIDENCE / filename).cases
        }
        for filename, _ in REPORTS.values()
    }
    evidence_sets = coverage["evidence_sets"]
    for row in rows:
        assert row["evidence_set"] in evidence_sets
    for evidence in evidence_sets.values():
        assert evidence
        for binding in evidence:
            assert binding["report"] in report_classnames
            assert set(binding["selectors"]) <= report_classnames[binding["report"]]


def test_p4_0_failures_are_closed_without_expanding_authority() -> None:
    failures = _json("failure-classification.json")
    assert failures["accepted_candidate_commit"] == CANDIDATE
    assert failures["accepted_release_id"] == RELEASE_ID
    assert failures["open_product_failures"] == []
    assert failures["accepted_source_suite_failures"] == []
    assert {
        item["candidate_commit"] for item in failures["quarantined_attempts"]
    } == {
        "fd8d1a1b99ff982ad60bc694fff94e840ec432f7",
        "e123d3e33447481e2190295e1a6b931f8cdf105f",
    }
    assert all(
        item["classification"] == "FIXTURE"
        and item["reports_reused"] is False
        for item in failures["quarantined_attempts"]
    )
    assert all(
        item["tests_executed"] == 0 and item["report_reused"] is False
        for item in failures["accepted_candidate_preflight_events"]
    )
    assert failures["decision"] == {
        "P4.0": "PASS",
        "implementation_blocked_by_product_failure": False,
        "historical_or_preflight_reports_mixed_into_accepted_evidence": False,
    }

    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    for required in (
        "P4.0: PASS",
        "engineering_execution: ALLOWED_WITH_DISABLED_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS",
        "promotion_gate: PENDING",
        "MIG-003: PENDING_PROMOTION",
        "MIG-004: PENDING_PROMOTION",
        "formal_intake_writer: FORBIDDEN",
    ):
        assert required in checkpoint
