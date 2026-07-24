from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path, PureWindowsPath
from typing import Any, Sequence

import yaml

try:
    from scripts.generate_phase3_candidate_evidence import (
        EvidenceError,
        _assert_candidate,
        _sha256,
        _write_json,
        normalize_source_reports,
        parse_junit,
    )
    from scripts.generate_phase4_candidate_evidence import (
        _assert_execution_manifest_seal,
        _split_approved_command,
        assert_base_ancestor,
        assert_candidate_run_directory,
        assert_clean_detached_candidate,
        render_command_argv,
        seal_execution_manifest,
    )
    from scripts.run_phase4_candidate_checkpoint import (
        _run_shell,
        capture_environment as _capture_environment_host,
    )
except ModuleNotFoundError:  # Direct execution places scripts/ on sys.path.
    from generate_phase3_candidate_evidence import (  # type: ignore[no-redef]
        EvidenceError,
        _assert_candidate,
        _sha256,
        _write_json,
        normalize_source_reports,
        parse_junit,
    )
    from generate_phase4_candidate_evidence import (  # type: ignore[no-redef]
        _assert_execution_manifest_seal,
        _split_approved_command,
        assert_base_ancestor,
        assert_candidate_run_directory,
        assert_clean_detached_candidate,
        render_command_argv,
        seal_execution_manifest,
    )
    from run_phase4_candidate_checkpoint import (  # type: ignore[no-redef]
        _run_shell,
        capture_environment as _capture_environment_host,
    )


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-7-outcome-pilot-test-batches.yaml"
RUNNER_PATH = "scripts/run_phase7_candidate_checkpoint.py"
PHASE7_ENTRY_CANDIDATE = "0aa260f722fced0eba4314bd4793e415b5bf0b05"
PHASE7_ENTRY_EVIDENCE = "e29cefb3e028bb84f6a227e46fecdf5711eba48c"
V045_PATH = (
    "java-api-service/src/main/resources/db/migration/"
    "V045__outcome_operation_receipt_compensation.sql"
)
MANIFEST_NAME = "phase7-candidate-execution-manifest.json"
SCHEMA_VERSION = "phase7-candidate-execution-manifest.v1"
PLAN_SCHEMA_VERSION = "phase7-candidate-run-plan.v1"
GREEN_STATUS = "PHASE_7_ENGINEERING_SOURCES_GREEN_AWAITING_SEPARATE_EVIDENCE"
COMMAND_ORDER = (
    "static_phase7_candidate",
    "python_phase7_candidate",
    "java_phase7_candidate",
    "frontend_phase7_candidate",
)
SOURCE_REPORTS = {
    "static_phase7_candidate": "static-phase7-candidate.xml",
    "python_phase7_candidate": "python-phase7-candidate.xml",
    "java_phase7_candidate": "java-phase7-candidate.xml",
    "frontend_phase7_candidate": "frontend-phase7-candidate.xml",
}
SOURCE_ALIASES = {
    "static_phase7_candidate": "s",
    "python_phase7_candidate": "p",
    "java_phase7_candidate": "j",
    "frontend_phase7_candidate": "f",
}
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}
MAX_INFRA_RERUNS_PER_SOURCE = 1
MAX_WINDOWS_PROVENANCE_PATH = 240
MAX_RELATIVE_PROVENANCE_PATH = 96
MIGRATION_GATES = ("MIG-006", "MIG-007")

# These are conservative floors until one clean detached candidate run records the
# final measured minima. Keeping them in one map makes that update atomic.
FROZEN_MEASURED_MINIMA = {
    "static_phase7_candidate": 78,
    "python_phase7_candidate": 22,
    "java_phase7_candidate": 80,
    "frontend_phase7_candidate": 60,
}

STATIC_TESTS = (
    "tests/static/test_phase7_entry_checkpoint.py",
    "tests/static/test_phase7_entry_evidence.py",
    "tests/static/test_phase7_outcome_contracts.py",
    "tests/static/test_phase7_outcome_pilot_plan.py",
    "tests/static/test_phase7_p7_0_entry_checkpoint.py",
    "tests/static/test_phase7_router_contract.py",
    "tests/static/test_phase7_candidate_runner.py",
    "tests/static/test_phase7_candidate_evidence.py",
    "tests/static/test_temporal_refactor_traceability.py",
)
PYTHON_TESTS = (
    "tests/graphs/outcome",
    "tests/agents/test_review_copilot.py",
    "tests/test_evaluation.py",
)
JAVA_TESTS = (
    "OutcomeWireContractTest",
    "OutcomeProtocolCompatibilityTest",
    "OutcomeRoomWorkflowTest",
    "OutcomeRoomWorkflowTimerTest",
    "OutcomeRoomWorkflowReplayTest",
    "ReviewApplicationServiceV2Test",
    "FrozenReviewPacketTest",
    "ApprovalPolicyEngineTest",
    "ReviewControllerTest",
    "ReviewDecisionConcurrencyTest",
    "OutcomeV045MigrationContractTest",
    "JdbcOutcomeOperationLedgerTest",
    "OutcomeOperationLedgerIntegrationTest",
    "ToolActivityIdempotencyTest",
    "CompensationWorkflowTest",
    "CaseOutcomeServiceTest",
    "CaseOutcomeControllerTest",
    "CaseClosureServiceTest",
    "RestClientEvaluationAgentClientTest",
    "OutcomeClosureEvaluationOrderingTest",
    "OutcomeSyntheticNoopAssemblyTest",
    "ReviewTemporalCommandIntegrationTest",
    "OutcomeReliabilityHarnessTest",
    "OutcomeUnregisteredAssemblyGuardTest",
)
JAVA_TEST_SOURCE_PATHS = (
    "java-api-service/src/test/java/com/example/dispute/workflow/contract/outcome/v1/OutcomeWireContractTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/contract/outcome/v1/OutcomeProtocolCompatibilityTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/temporal/room/outcome/OutcomeRoomWorkflowTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/temporal/room/outcome/OutcomeRoomWorkflowTimerTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/temporal/room/outcome/OutcomeRoomWorkflowReplayTest.java",
    "java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceV2Test.java",
    "java-api-service/src/test/java/com/example/dispute/review/FrozenReviewPacketTest.java",
    "java-api-service/src/test/java/com/example/dispute/review/ApprovalPolicyEngineTest.java",
    "java-api-service/src/test/java/com/example/dispute/review/ReviewControllerTest.java",
    "java-api-service/src/test/java/com/example/dispute/review/ReviewDecisionConcurrencyTest.java",
    "java-api-service/src/test/java/com/example/dispute/database/OutcomeV045MigrationContractTest.java",
    "java-api-service/src/test/java/com/example/dispute/executor/persistence/JdbcOutcomeOperationLedgerTest.java",
    "java-api-service/src/test/java/com/example/dispute/executor/persistence/OutcomeOperationLedgerIntegrationTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/activity/tool/ToolActivityIdempotencyTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/activity/tool/CompensationWorkflowTest.java",
    "java-api-service/src/test/java/com/example/dispute/outcome/CaseOutcomeServiceTest.java",
    "java-api-service/src/test/java/com/example/dispute/outcome/CaseOutcomeControllerTest.java",
    "java-api-service/src/test/java/com/example/dispute/evaluation/CaseClosureServiceTest.java",
    "java-api-service/src/test/java/com/example/dispute/evaluation/RestClientEvaluationAgentClientTest.java",
    "java-api-service/src/test/java/com/example/dispute/evaluation/OutcomeClosureEvaluationOrderingTest.java",
    "java-api-service/src/test/java/com/example/dispute/executor/OutcomeSyntheticNoopAssemblyTest.java",
    "java-api-service/src/test/java/com/example/dispute/review/ReviewTemporalCommandIntegrationTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/temporal/room/outcome/OutcomeReliabilityHarnessTest.java",
    "java-api-service/src/test/java/com/example/dispute/workflow/integration/outcome/OutcomeUnregisteredAssemblyGuardTest.java",
)
JDBC_OUTCOME_OPERATION_LEDGER_PATH = (
    "java-api-service/src/main/java/com/example/dispute/executor/"
    "infrastructure/persistence/JdbcOutcomeOperationLedger.java"
)
DEPENDENCY_MANIFEST_PATHS = (
    "python-agent-service/pyproject.toml",
    "python-agent-service/requirements.lock",
    "java-api-service/pom.xml",
    "java-api-service/.mvn/wrapper/maven-wrapper.properties",
    "frontend/package.json",
    "frontend/pnpm-lock.yaml",
    "docker-compose.yml",
)
EXECUTION_SOURCE_PATHS = (
    "java-api-service/mvnw",
    "java-api-service/mvnw.cmd",
    RUNNER_PATH,
    "scripts/generate_phase7_candidate_evidence.py",
    MATRIX_PATH.relative_to(ROOT).as_posix(),
)
FRONTEND_TESTS = (
    "src/views/disputes/AdjudicationDraftView.test.js",
    "src/views/disputes/OutcomeView.test.js",
    "src/views/reviews/ReviewQueueView.test.js",
    "src/views/reviews/ReviewWorkbenchView.test.js",
    "src/views/ReviewWorkbenchView.test.js",
    "src/api/review.test.js",
)

FROZEN_AUTHORITY_PATHS = (
    "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java",
    "java-api-service/src/main/java/com/example/dispute/workflow/application/epoch/ConfiguredRoomEpochSelector.java",
    "java-api-service/src/main/resources/application.yml",
    "java-api-service/src/main/resources/application-local.yml",
    "python-agent-service/app/graph_runtime/registry.py",
    "python-agent-service/app/main.py",
    "frontend/src/router/index.js",
    "java-api-service/src/main/java/com/example/dispute/executor/api/ExecutionController.java",
    "java-api-service/src/main/java/com/example/dispute/executor/application/ToolExecutorService.java",
    "java-api-service/src/main/java/com/example/dispute/tool/api/InternalToolCatalogController.java",
    "java-api-service/src/main/java/com/example/dispute/tool/application/ToolAdapter.java",
    "java-api-service/src/main/java/com/example/dispute/tool/application/ToolDefinition.java",
    "java-api-service/src/main/java/com/example/dispute/tool/application/ToolRegistry.java",
)

ALLOWED_CANDIDATE_PREFIXES = (
    "contracts/agent-platform/outcome/",
    "docs/runbooks/temporal-first/phase-7-",
    "java-api-service/src/main/java/com/example/dispute/evaluation/",
    "java-api-service/src/main/java/com/example/dispute/executor/application/Synthetic",
    "java-api-service/src/main/java/com/example/dispute/executor/domain/ledger/",
    "java-api-service/src/main/java/com/example/dispute/executor/infrastructure/persistence/",
    "java-api-service/src/main/java/com/example/dispute/outcome/",
    "java-api-service/src/main/java/com/example/dispute/review/",
    "java-api-service/src/main/java/com/example/dispute/workflow/activity/tool/Synthetic",
    "java-api-service/src/main/java/com/example/dispute/workflow/contract/outcome/",
    "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/outcome/",
    "java-api-service/src/test/java/com/example/dispute/database/OutcomeV045",
    "java-api-service/src/test/java/com/example/dispute/evaluation/",
    "java-api-service/src/test/java/com/example/dispute/executor/",
    "java-api-service/src/test/java/com/example/dispute/review/",
    "java-api-service/src/test/java/com/example/dispute/workflow/activity/tool/",
    "java-api-service/src/test/java/com/example/dispute/workflow/contract/outcome/",
    "java-api-service/src/test/java/com/example/dispute/workflow/integration/outcome/",
    "java-api-service/src/test/java/com/example/dispute/workflow/temporal/room/outcome/",
    "python-agent-service/app/agents/review_copilot.py",
    "python-agent-service/app/graphs/outcome/",
    "python-agent-service/tests/agents/test_review_copilot.py",
    "python-agent-service/tests/graphs/outcome/",
    "tests/static/test_phase7_",
)
ALLOWED_CANDIDATE_EXACT = {
    V045_PATH,
    "frontend/src/api/review.js",
    "frontend/src/api/review.test.js",
    "frontend/src/stores/review.js",
    "frontend/src/views/ReviewWorkbenchView.vue",
    "frontend/src/views/ReviewWorkbenchView.test.js",
    "frontend/src/views/disputes/AdjudicationDraftView.vue",
    "frontend/src/views/disputes/AdjudicationDraftView.test.js",
    "frontend/src/views/disputes/OutcomeView.vue",
    "frontend/src/views/disputes/OutcomeView.test.js",
    "frontend/src/views/reviews/ReviewQueueView.vue",
    "frontend/src/views/reviews/ReviewQueueView.test.js",
    "frontend/src/views/reviews/ReviewWorkbenchView.vue",
    "frontend/src/views/reviews/ReviewWorkbenchView.test.js",
    "plans/phase-7-outcome-pilot-execution.md",
    "plans/phase-7-outcome-pilot-test-batches.yaml",
    "plans/phase-7-owner-briefs.yaml",
    RUNNER_PATH,
    "scripts/generate_phase7_candidate_evidence.py",
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _json_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _git_bytes(commit: str, path: str) -> bytes:
    entry = _git_tree_entry(commit, path)
    process = subprocess.run(
        ["git", "cat-file", "blob", entry[2]],
        cwd=ROOT,
        capture_output=True,
        check=False,
    )
    if process.returncode:
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise EvidenceError(f"cannot authenticate {commit}:{path}: {error}")
    return process.stdout


def _git_tree_entries(
    commit: str, path: str, *, recursive: bool = False
) -> list[tuple[str, str, str, str]]:
    normalized = path.replace("\\", "/")
    if (
        not normalized
        or normalized.startswith("/")
        or "\0" in normalized
        or any(part in {"", ".", ".."} or ":" in part for part in normalized.split("/"))
    ):
        raise EvidenceError(f"candidate Git path is unsafe: {path}")
    arguments = ["git", "ls-tree", "-z"]
    if recursive:
        arguments.append("-r")
    arguments.extend((commit, "--", normalized))
    process = subprocess.run(arguments, cwd=ROOT, capture_output=True, check=False)
    if process.returncode:
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise EvidenceError(f"cannot authenticate candidate Git tree path {path}: {error}")
    entries: list[tuple[str, str, str, str]] = []
    for raw in process.stdout.split(b"\0"):
        if not raw:
            continue
        try:
            metadata, raw_path = raw.split(b"\t", 1)
            mode, object_type, object_id = metadata.decode("ascii").split(" ")
            entry_path = raw_path.decode("utf-8", errors="strict").replace("\\", "/")
        except (ValueError, UnicodeDecodeError) as exception:
            raise EvidenceError(
                f"cannot parse candidate Git tree entry for {path}"
            ) from exception
        entries.append((mode, object_type, object_id, entry_path))
    return entries


def _assert_regular_blob_entry(
    entry: tuple[str, str, str, str], *, expected_path: str | None = None
) -> tuple[str, str, str, str]:
    mode, object_type, _object_id, path = entry
    if (
        mode not in {"100644", "100755"}
        or object_type != "blob"
        or (expected_path is not None and path != expected_path.replace("\\", "/"))
    ):
        raise EvidenceError(
            f"candidate Git path must be an exact regular blob, not a symlink, tree, or submodule: "
            f"{mode} {object_type} {path}"
        )
    return entry


def _git_tree_entry(commit: str, path: str) -> tuple[str, str, str, str]:
    normalized = path.replace("\\", "/")
    entries = _git_tree_entries(commit, normalized)
    if len(entries) != 1:
        raise EvidenceError(f"candidate Git path is missing or ambiguous: {normalized}")
    return _assert_regular_blob_entry(entries[0], expected_path=normalized)


def _git_regular_blobs_under(commit: str, path: str) -> list[tuple[str, str, str, str]]:
    entries = _git_tree_entries(commit, path, recursive=True)
    if not entries:
        raise EvidenceError(f"candidate Git source selection is empty: {path}")
    return [_assert_regular_blob_entry(entry) for entry in entries]


def _git_text(commit: str, path: str) -> str:
    return _git_bytes(commit, path).decode("utf-8", errors="strict")


def _git_output(*arguments: str) -> str:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if process.returncode:
        raise EvidenceError(
            f"git {' '.join(arguments)} failed: {process.stderr.strip()}"
        )
    return process.stdout.strip()


def source_contracts(candidate: str | None = None) -> dict[str, dict[str, Any]]:
    del candidate  # The candidate cannot change the code-owned source contract.
    if tuple(Path(path).stem for path in JAVA_TEST_SOURCE_PATHS) != JAVA_TESTS:
        raise EvidenceError("Phase 7 Java selector-to-source binding drifted")
    java_tests = JAVA_TESTS
    python = r"D:\miniconda\python.exe"
    values: dict[str, dict[str, Any]] = {
        "static_phase7_candidate": {
            "cwd": ".",
            "resource_class": "light",
            "report": SOURCE_REPORTS["static_phase7_candidate"],
            "report_kind": "PYTEST_JUNIT",
            "expected_report_count": 1,
            "selected_test_file_count": len(STATIC_TESTS),
            "minimum_tests": FROZEN_MEASURED_MINIMA["static_phase7_candidate"],
            "command": " ".join(
                (python, "-m", "pytest", "-q", *STATIC_TESTS, "--junitxml={raw_report}")
            ),
        },
        "python_phase7_candidate": {
            "cwd": "python-agent-service",
            "resource_class": "light",
            "report": SOURCE_REPORTS["python_phase7_candidate"],
            "report_kind": "PYTEST_JUNIT",
            "expected_report_count": 1,
            "selected_test_file_count": len(PYTHON_TESTS),
            "minimum_tests": FROZEN_MEASURED_MINIMA["python_phase7_candidate"],
            "command": " ".join(
                (python, "-m", "pytest", "-q", *PYTHON_TESTS, "--junitxml={raw_report}")
            ),
        },
        "java_phase7_candidate": {
            "cwd": "java-api-service",
            "resource_class": "heavy",
            "report": SOURCE_REPORTS["java_phase7_candidate"],
            "report_kind": "SUREFIRE_GLOB",
            "raw_report_glob": "target/surefire-reports/TEST-*-{report_suffix}.xml",
            "expected_report_count": len(java_tests),
            "selected_test_file_count": len(java_tests),
            "minimum_tests": FROZEN_MEASURED_MINIMA["java_phase7_candidate"],
            "command": (
                r".\mvnw.cmd -q -DforkCount=1 "
                f"-Dtest={','.join(java_tests)} "
                "-Dsurefire.reportNameSuffix={report_suffix} test"
            ),
        },
        "frontend_phase7_candidate": {
            "cwd": "frontend",
            "resource_class": "light",
            "report": SOURCE_REPORTS["frontend_phase7_candidate"],
            "report_kind": "VITEST_JUNIT",
            "expected_report_count": 1,
            "selected_test_file_count": len(FRONTEND_TESTS),
            "minimum_tests": FROZEN_MEASURED_MINIMA["frontend_phase7_candidate"],
            "command": " ".join(
                (
                    "node",
                    "node_modules/vitest/vitest.mjs",
                    "run",
                    *FRONTEND_TESTS,
                    "--reporter=default",
                    "--reporter=junit",
                    "--outputFile.junit={raw_report}",
                )
            ),
        },
    }
    if tuple(values) != COMMAND_ORDER:
        raise EvidenceError("Phase 7 candidate source order drifted")
    return copy.deepcopy(values)


def _source_contract_sha256(candidate: str | None = None) -> str:
    return _json_sha256(source_contracts(candidate))


def load_matrix(candidate: str | None = None) -> dict[str, Any]:
    try:
        payload = (
            _git_text(candidate, MATRIX_PATH.relative_to(ROOT).as_posix())
            if candidate
            else MATRIX_PATH.read_text(encoding="utf-8")
        )
        matrix = yaml.safe_load(payload)
    except (OSError, UnicodeError, yaml.YAMLError) as exception:
        raise EvidenceError(f"cannot load Phase 7 matrix: {exception}") from exception
    if not isinstance(matrix, dict) or matrix.get("phase") != 7:
        raise EvidenceError("candidate does not contain an authentic Phase 7 matrix")
    return matrix


def gate_blockers(matrix: dict[str, Any]) -> list[str]:
    gate = matrix.get("gate", {})
    upstream = gate.get("accepted_upstream_state", {}) if isinstance(gate, dict) else {}
    batches = matrix.get("batches", {})
    entry = batches.get("batch_0_entry", {}) if isinstance(batches, dict) else {}
    foundation = (
        batches.get("batch_1_foundation", {}) if isinstance(batches, dict) else {}
    )
    integration = (
        batches.get("batch_2_integration", {}) if isinstance(batches, dict) else {}
    )
    candidate_batch = (
        batches.get("batch_3_engineering_candidate", {}) if isinstance(batches, dict) else {}
    )
    blockers: list[str] = []
    if gate.get("accepted_phase_7_candidate_C7") != PHASE7_ENTRY_CANDIDATE:
        blockers.append("P7_ENTRY_CANDIDATE_AUTHORITY_DRIFTED")
    if gate.get("accepted_phase_7_evidence_E7") != PHASE7_ENTRY_EVIDENCE:
        blockers.append("P7_ENTRY_EVIDENCE_AUTHORITY_DRIFTED")
    if upstream.get("P7.0") != "PASS" or gate.get("implementation_authorized") is not True:
        blockers.append("P7_0_ENGINEERING_ENTRY_NOT_ACCEPTED")
    if gate.get("entry_decision") != "ENTRY_EVIDENCE_ACCEPTED":
        blockers.append("P7_ENTRY_DECISION_NOT_ACCEPTED")
    if entry.get("status") != "PASS" or entry.get("accepted_candidate_commit") != PHASE7_ENTRY_CANDIDATE:
        blockers.append("P7_BATCH_0_AUTHORITY_DRIFTED")
    if entry.get("entry_evidence_commit") != PHASE7_ENTRY_EVIDENCE:
        blockers.append("P7_BATCH_0_EVIDENCE_DRIFTED")
    if gate.get("batch_1_status") != "PASS" or foundation.get("status") != "PASS":
        blockers.append("P7_BATCH_1_NOT_PASS")
    if gate.get("batch_2_status") != "PASS" or integration.get("status") != "PASS":
        blockers.append("P7_BATCH_2_NOT_PASS")
    if (
        candidate_batch.get("exact_clean_candidate_sha_required") is not True
        or candidate_batch.get("all_sources_rerun_from_same_sha") is not True
        or candidate_batch.get("evidence_commit_must_be_separate_direct_child") is not True
        or candidate_batch.get("decision_ceiling") != "PHASE_7_ENGINEERING_CHECKPOINT_ONLY"
    ):
        blockers.append("P7_BATCH_3_CONTRACT_DRIFTED")
    forbidden = set(candidate_batch.get("forbidden_claims", []))
    required_forbidden = {
        "MIG_006_PASS",
        "MIG_007_PASS",
        "TEMPORAL_OUTCOME_ALLOCATION",
        "FORMAL_WORKFLOW_ACTIVATION",
        "REAL_TOOL_EFFECT",
        "REAL_CASE_SHADOW",
        "CANARY",
        "PROMOTION",
    }
    if not required_forbidden.issubset(forbidden):
        blockers.append("P7_BATCH_3_FORBIDDEN_CLAIMS_DRIFTED")
    for migration in MIGRATION_GATES:
        if upstream.get(migration) != "PENDING_PROMOTION":
            blockers.append(f"{migration.replace('-', '_')}_MUST_REMAIN_PENDING")
    constraints = gate.get("traffic_constraints", {}) if isinstance(gate, dict) else {}
    for field in (
        "formal_outcome_workflow_activation_allowed",
        "temporal_outcome_allocation_allowed",
        "formal_outcome_graph_sink_allowed",
        "real_tool_effect_allowed",
        "real_or_party_data_shadow_allowed",
        "production_traffic_change_allowed",
        "canary_allowed",
        "promotion_allowed",
    ):
        if constraints.get(field) is not False:
            blockers.append(f"{field.upper()}_MUST_REMAIN_FALSE")
    return blockers


def _changed_path_records(candidate: str) -> list[dict[str, str]]:
    output = _git_output(
        "diff",
        "--name-status",
        "--no-renames",
        "--diff-filter=ACDMRTUXB",
        PHASE7_ENTRY_EVIDENCE,
        candidate,
        "--",
    )
    records: list[dict[str, str]] = []
    for line in output.splitlines():
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != 2:
            raise EvidenceError(f"cannot authenticate candidate path record: {line}")
        status, path = fields
        records.append({"status": status, "path": path.replace("\\", "/")})
    return records


def _path_allowed(path: str) -> bool:
    return path in ALLOWED_CANDIDATE_EXACT or any(
        path.startswith(prefix) for prefix in ALLOWED_CANDIDATE_PREFIXES
    )


def _assert_frozen_authority(candidate: str) -> None:
    for path in FROZEN_AUTHORITY_PATHS:
        base = _git_tree_entry(PHASE7_ENTRY_EVIDENCE, path)
        current = _git_tree_entry(candidate, path)
        if base[:3] != current[:3]:
            raise EvidenceError(
                f"Phase 7 candidate changed forbidden worker/selector/formal/effect authority: {path}"
            )


def _assert_prior_migrations(candidate: str) -> None:
    migration_root = "java-api-service/src/main/resources/db/migration"
    base = _git_regular_blobs_under(PHASE7_ENTRY_EVIDENCE, migration_root)
    current = [
        entry
        for entry in _git_regular_blobs_under(candidate, migration_root)
        if entry[3] != V045_PATH
    ]
    if base != current:
        raise EvidenceError("Phase 7 candidate changed prior migration blob identity or mode")


def _assert_candidate_source_inventory(candidate: str, records: Sequence[dict[str, str]]) -> None:
    exact_paths = {
        *(item["path"] for item in records),
        *EXECUTION_SOURCE_PATHS,
        *DEPENDENCY_MANIFEST_PATHS,
        *STATIC_TESTS,
        *JAVA_TEST_SOURCE_PATHS,
        *(f"frontend/{path}" for path in FRONTEND_TESTS),
        JDBC_OUTCOME_OPERATION_LEDGER_PATH,
        "python-agent-service/tests/agents/test_review_copilot.py",
        "python-agent-service/tests/test_evaluation.py",
    }
    for path in sorted(exact_paths):
        _git_tree_entry(candidate, path)
    _git_regular_blobs_under(candidate, "python-agent-service/tests/graphs/outcome")


def capture_source_tree(candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    assert_base_ancestor(PHASE7_ENTRY_EVIDENCE, candidate)
    records = _changed_path_records(candidate)
    if not records:
        raise EvidenceError("Phase 7 engineering candidate has no changes from accepted E7")
    v045 = [item for item in records if item["path"] == V045_PATH]
    if v045 != [{"status": "A", "path": V045_PATH}]:
        raise EvidenceError("Phase 7 candidate must add exactly the approved additive V045 migration")
    migration_root = "java-api-service/src/main/resources/db/migration/"
    for item in records:
        status = item["status"]
        path = item["path"]
        if status not in {"A", "M"}:
            raise EvidenceError(f"Phase 7 candidate has unsupported change {status} {path}")
        if path.startswith(migration_root) and path != V045_PATH:
            raise EvidenceError(f"Phase 7 candidate changed a prior migration: {path}")
        if path in FROZEN_AUTHORITY_PATHS:
            raise EvidenceError(f"Phase 7 candidate changed forbidden authority path: {path}")
        if not _path_allowed(path):
            raise EvidenceError(f"Phase 7 candidate contains an undeclared path: {path}")
    _assert_candidate_source_inventory(candidate, records)
    _assert_prior_migrations(candidate)
    _assert_frozen_authority(candidate)
    value: dict[str, Any] = {
        "candidate_commit": candidate,
        "base_commit": PHASE7_ENTRY_EVIDENCE,
        "candidate_tree": _git_output("rev-parse", f"{candidate}^{{tree}}"),
        "changed_paths": records,
        "v045": {"path": V045_PATH, "status": "ADDED_ONLY"},
        "prior_migrations_unchanged": True,
        "worker_selector_formal_effect_authority_unchanged": True,
    }
    value["snapshot_sha256"] = _json_sha256(value)
    return value


def capture_environment(environment_id: str, candidate: str) -> dict[str, Any]:
    snapshot = _capture_environment_host(environment_id)
    dependencies = snapshot.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise EvidenceError("environment dependency manifest inventory is missing")
    host_paths = [
        item.get("path") if isinstance(item, dict) else None for item in dependencies
    ]
    if host_paths != list(DEPENDENCY_MANIFEST_PATHS):
        raise EvidenceError("environment dependency manifest inventory drifted")
    commit_bound: list[dict[str, str]] = []
    for item in dependencies:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise EvidenceError("environment dependency manifest record is invalid")
        payload = _git_bytes(candidate, item["path"])
        commit_bound.append(
            {
                "path": item["path"],
                "sha256": hashlib.sha256(payload).hexdigest(),
                "byte_source": "CANDIDATE_GIT_BLOB",
            }
        )
    snapshot["candidate_commit"] = candidate
    snapshot["dependency_manifests"] = commit_bound
    snapshot["runner"] = {
        "path": RUNNER_PATH,
        "sha256": hashlib.sha256(_git_bytes(candidate, RUNNER_PATH)).hexdigest(),
        "byte_source": "CANDIDATE_GIT_BLOB",
    }
    snapshot["source_contract_sha256"] = _source_contract_sha256(candidate)
    snapshot.pop("snapshot_sha256", None)
    snapshot["snapshot_sha256"] = _json_sha256(snapshot)
    return snapshot


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    contracts = source_contracts(candidate)
    matrix = load_matrix(candidate)
    blockers = gate_blockers(matrix)
    return {
        "schema_version": PLAN_SCHEMA_VERSION,
        "phase": 7,
        "batch": "P7-BATCH-3",
        "candidate_commit": candidate,
        "execution_allowed": not blockers,
        "blocked_reasons": blockers,
        "executed_source_count": 0,
        "execution_order": list(COMMAND_ORDER),
        "commands": [
            {
                "id": command_id,
                **contracts[command_id],
                "frozen_command_sha256": hashlib.sha256(
                    contracts[command_id]["command"].encode("utf-8")
                ).hexdigest(),
            }
            for command_id in COMMAND_ORDER
        ],
        "source_contract_sha256": _source_contract_sha256(candidate),
        "concurrency": {
            "runner_execution": "sequential",
            "policy_maximum_heavy_processes": 2,
            "policy_maximum_light_processes": 2,
            "observed_maximum_source_processes": 1,
        },
        "decision_ceiling": "PHASE_7_ENGINEERING_CHECKPOINT_ONLY",
        "next_phase_permission_on_separate_evidence": "PHASE_8_ENGINEERING_ONLY",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "forbidden_claims": [
            "FORMAL_OUTCOME_WORKFLOW",
            "TEMPORAL_OUTCOME_ALLOCATION",
            "REAL_TOOL_EFFECT",
            "REAL_CASE_SHADOW",
            "CANARY",
            "PROMOTION",
            "MIG_006_PASS",
            "MIG_007_PASS",
        ],
    }


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    seal_execution_manifest(manifest)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        _write_json(temporary, manifest)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _allowed_untracked_root(run_root: Path) -> Path | None:
    try:
        run_root.resolve().relative_to(ROOT.resolve())
    except ValueError:
        return None
    return run_root.resolve()


def _assert_candidate_unchanged(candidate: str, run_root: Path) -> None:
    assert_clean_detached_candidate(
        candidate, allowed_untracked_root=_allowed_untracked_root(run_root)
    )


def _relative(path: Path, run_root: Path) -> str:
    value = path.resolve().relative_to(run_root.resolve()).as_posix()
    _assert_path_budget(path, run_root)
    return value


def _assert_path_budget(path: Path, run_root: Path) -> None:
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(run_root.resolve()).as_posix()
    except ValueError as exception:
        raise EvidenceError("provenance path escapes the run directory") from exception
    if len(relative) > MAX_RELATIVE_PROVENANCE_PATH:
        raise EvidenceError(f"provenance relative path exceeds budget: {relative}")
    if os.name == "nt" and len(str(resolved)) > MAX_WINDOWS_PROVENANCE_PATH:
        raise EvidenceError(f"provenance path exceeds Windows MAX_PATH safety budget: {resolved}")


def _assert_run_path_budget(run_root: Path) -> None:
    longest = run_root / "a" / "j-02" / "raw" / "j-999.xml"
    _assert_path_budget(longest, run_root)


def _freeze_file(path: Path) -> None:
    if not path.is_file():
        raise EvidenceError(f"cannot freeze missing provenance artifact: {path}")
    path.chmod(path.stat().st_mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH))


def _assert_bound_artifact(
    run_root: Path, relative: Any, expected_sha256: Any, context: str
) -> Path:
    if not isinstance(relative, str) or not isinstance(expected_sha256, str):
        raise EvidenceError(f"{context} binding is incomplete")
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise EvidenceError(f"{context} path escapes or is missing")
    _assert_path_budget(path, run_root)
    if _sha256(path) != expected_sha256:
        raise EvidenceError(f"{context} SHA-256 drifted")
    if path.stat().st_mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH):
        raise EvidenceError(f"{context} is not immutable")
    return path


def _format_command(
    command_id: str,
    item: dict[str, Any],
    raw_path: Path,
    report_suffix: str,
    cwd: Path,
) -> list[str]:
    arguments = _split_approved_command(item["command"])
    rendered = [
        argument.replace("{raw_report}", str(raw_path.resolve())).replace(
            "{report_suffix}", report_suffix
        )
        for argument in arguments
    ]
    if any("{" in argument or "}" in argument for argument in rendered):
        raise EvidenceError(f"{command_id}: command placeholder drifted")
    if command_id == "java_phase7_candidate":
        wrapper_name = PureWindowsPath(rendered[0]).name.lower()
        if wrapper_name not in {"mvnw", "mvnw.cmd"}:
            raise EvidenceError("java source must invoke the checked-in Maven wrapper")
        wrapper = (cwd / ("mvnw.cmd" if os.name == "nt" else "mvnw")).resolve()
        if not wrapper.is_file() or not wrapper.is_relative_to(ROOT.resolve()):
            raise EvidenceError("candidate Maven wrapper is missing or escapes the worktree")
        rendered[0] = str(wrapper)
    return rendered


def _raw_reports(
    item: dict[str, Any], raw_path: Path, report_suffix: str, cwd: Path
) -> list[Path]:
    if item["report_kind"] != "SUREFIRE_GLOB":
        return [raw_path] if raw_path.is_file() else []
    return sorted(
        path
        for path in cwd.glob(item["raw_report_glob"].format(report_suffix=report_suffix))
        if path.is_file()
    )


def _report_count_error(item: dict[str, Any], reports: Sequence[Path]) -> str | None:
    expected = item["expected_report_count"]
    if len(reports) != expected:
        return f"expected {expected} raw JUnit report(s), found {len(reports)}"
    return None


def _initial_manifest(
    candidate: str, run_root: Path, environment_id: str, source_tree: dict[str, Any]
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "phase": 7,
        "batch": "P7-BATCH-3",
        "candidate_commit": candidate,
        "accepted_phase_7_candidate_C7": PHASE7_ENTRY_CANDIDATE,
        "accepted_phase_7_evidence_E7": PHASE7_ENTRY_EVIDENCE,
        "attempt_id": run_root.name,
        "run_root": str(run_root.resolve()),
        "status": "RUNNING",
        "decision_ceiling": "PHASE_7_ENGINEERING_CHECKPOINT_ONLY",
        "next_phase_permission": "PENDING_SEPARATE_EVIDENCE",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "verification_started_at": _utc_now(),
        "verification_finished_at": None,
        "source_tree": source_tree,
        "environment": capture_environment(environment_id, candidate),
        "source_contract_sha256": _source_contract_sha256(candidate),
        "concurrency": {
            "runner_execution": "sequential",
            "policy_maximum_heavy_processes": 2,
            "policy_maximum_light_processes": 2,
            "observed_maximum_source_processes": 1,
        },
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
    }


def _record_source(
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    contract: dict[str, Any],
    environment_sha256: str,
    runner_blob_sha256: str,
) -> tuple[dict[str, Any], bool]:
    alias = SOURCE_ALIASES[command_id]
    attempt_number = 1 + sum(
        1 for path in (run_root / "a").glob(f"{alias}-*") if path.is_dir()
    )
    attempt_dir = run_root / "a" / f"{alias}-{attempt_number:02d}"
    attempt_dir.mkdir(parents=True, exist_ok=False)
    raw_path = attempt_dir / "junit.xml"
    stdout_path = attempt_dir / "stdout.log"
    stderr_path = attempt_dir / "stderr.log"
    run_token = hashlib.sha256(str(run_root.resolve()).encode("utf-8")).hexdigest()[:6]
    report_suffix = f"p7c-{candidate[:10]}-{run_token}-{alias}{attempt_number:02d}"
    cwd = (ROOT / contract["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise EvidenceError(f"{command_id}: cwd escapes the candidate worktree")
    if _raw_reports(contract, raw_path, report_suffix, cwd):
        raise EvidenceError(f"{command_id}: candidate-attempt raw report already exists")
    executed_argv = _format_command(command_id, contract, raw_path, report_suffix, cwd)
    executed_command = render_command_argv(executed_argv)
    _assert_candidate_unchanged(candidate, run_root)
    started_at, finished_at, duration, exit_code = _run_shell(
        executed_argv, cwd, stdout_path, stderr_path
    )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(contract, raw_path, report_suffix, cwd)
    raw_sources: list[dict[str, str]] = []
    if contract["report_kind"] == "SUREFIRE_GLOB" and raw_reports:
        retained_dir = attempt_dir / "raw"
        retained_dir.mkdir()
        retained: list[Path] = []
        for index, source in enumerate(raw_reports, start=1):
            destination = retained_dir / f"j-{index:03d}.xml"
            shutil.copyfile(source, destination)
            retained.append(destination)
            raw_sources.append(
                {
                    "path": _relative(destination, run_root),
                    "sha256": _sha256(destination),
                }
            )
        raw_reports = retained
    else:
        raw_sources = [
            {"path": _relative(path, run_root), "sha256": _sha256(path)}
            for path in raw_reports
        ]
    for path in (stdout_path, stderr_path, *raw_reports):
        _freeze_file(path)
    record: dict[str, Any] = {
        "id": command_id,
        "candidate_commit": candidate,
        "cwd": contract["cwd"],
        "resource_class": contract["resource_class"],
        "expected_report_count": contract["expected_report_count"],
        "selected_test_file_count": contract["selected_test_file_count"],
        "minimum_tests": contract["minimum_tests"],
        "frozen_command": contract["command"],
        "frozen_command_sha256": hashlib.sha256(contract["command"].encode("utf-8")).hexdigest(),
        "executed_argv": executed_argv,
        "executed_argv_sha256": _json_sha256(executed_argv),
        "executed_command": executed_command,
        "executed_command_sha256": hashlib.sha256(executed_command.encode("utf-8")).hexdigest(),
        "command_contract_blob_sha256": runner_blob_sha256,
        "report_suffix": report_suffix,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_seconds": duration,
        "exit_code": exit_code,
        "environment_sha256": environment_sha256,
        "stdout_path": _relative(stdout_path, run_root),
        "stdout_sha256": _sha256(stdout_path),
        "stderr_path": _relative(stderr_path, run_root),
        "stderr_sha256": _sha256(stderr_path),
        "raw_report_count": len(raw_reports),
        "raw_reports": raw_sources,
        "failure_classification": "NONE" if exit_code == 0 else "UNCLASSIFIED",
        "accepted": False,
    }
    if exit_code != 0:
        return record, False
    count_error = _report_count_error(contract, raw_reports)
    if count_error:
        record.update(exit_code=2, failure_classification="UNCLASSIFIED", failure_reason=count_error)
        return record, False
    report_dir = run_root / "r"
    report_dir.mkdir(exist_ok=True)
    destination = report_dir / SOURCE_REPORTS[command_id]
    if destination.exists():
        raise EvidenceError(f"{command_id}: normalized report already exists")
    try:
        report = normalize_source_reports(
            raw_reports,
            destination,
            candidate_commit=candidate,
            command_id=command_id,
        )
    except EvidenceError as exception:
        destination.unlink(missing_ok=True)
        record.update(
            exit_code=2,
            failure_classification="UNCLASSIFIED",
            failure_reason=f"JUnit normalization rejected: {exception}",
        )
        return record, False
    totals = report.totals
    if (
        totals["tests"] < contract["minimum_tests"]
        or totals["failures"]
        or totals["errors"]
        or totals["skipped"]
    ):
        destination.unlink(missing_ok=True)
        record.update(
            exit_code=2,
            failure_classification="UNCLASSIFIED",
            failure_reason=f"source JUnit rejected by frozen all-green minimum: {totals}",
        )
        return record, False
    _freeze_file(destination)
    record.update(
        accepted=True,
        report=SOURCE_REPORTS[command_id],
        report_path=_relative(destination, run_root),
        report_sha256=_sha256(destination),
        **totals,
    )
    return record, True


def _validate_snapshot(value: Any, name: str) -> str:
    if not isinstance(value, dict):
        raise EvidenceError(f"execution manifest lacks {name}")
    digest = value.get("snapshot_sha256")
    unsigned = dict(value)
    unsigned.pop("snapshot_sha256", None)
    if not isinstance(digest, str) or _json_sha256(unsigned) != digest:
        raise EvidenceError(f"{name} snapshot SHA-256 drifted")
    return digest


def _validate_environment(manifest: dict[str, Any], candidate: str) -> str:
    environment = manifest.get("environment")
    digest = _validate_snapshot(environment, "environment")
    assert isinstance(environment, dict)
    if environment.get("candidate_commit") != candidate:
        raise EvidenceError("environment candidate binding drifted")
    if environment.get("source_contract_sha256") != _source_contract_sha256(candidate):
        raise EvidenceError("environment source contract SHA-256 drifted")
    runner = environment.get("runner")
    if (
        not isinstance(runner, dict)
        or runner.get("path") != RUNNER_PATH
        or runner.get("byte_source") != "CANDIDATE_GIT_BLOB"
        or runner.get("sha256") != hashlib.sha256(_git_bytes(candidate, RUNNER_PATH)).hexdigest()
    ):
        raise EvidenceError("environment runner Git-blob SHA-256 drifted")
    dependencies = environment.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise EvidenceError("environment dependency Git-blob inventory is missing")
    for item in dependencies:
        if (
            not isinstance(item, dict)
            or not isinstance(item.get("path"), str)
            or item.get("byte_source") != "CANDIDATE_GIT_BLOB"
            or item.get("sha256")
            != hashlib.sha256(_git_bytes(candidate, item["path"])).hexdigest()
        ):
            raise EvidenceError("environment dependency Git-blob SHA-256 drifted")
    return digest


def _validate_source_tree(manifest: dict[str, Any], candidate: str) -> None:
    source_tree = manifest.get("source_tree")
    _validate_snapshot(source_tree, "source tree")
    if source_tree != capture_source_tree(candidate):
        raise EvidenceError("source tree snapshot drifted from exact candidate SHA")


def _validate_record(
    record: dict[str, Any],
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    contract: dict[str, Any],
    environment_sha256: str,
    runner_blob_sha256: str,
    accepted: bool,
) -> Path:
    stdout = _assert_bound_artifact(
        run_root, record.get("stdout_path"), record.get("stdout_sha256"), "source stdout"
    )
    stderr = _assert_bound_artifact(
        run_root, record.get("stderr_path"), record.get("stderr_sha256"), "source stderr"
    )
    attempt_dir = stdout.parent
    if stderr != attempt_dir / "stderr.log" or stdout.name != "stdout.log":
        raise EvidenceError("source stdout/stderr attempt binding drifted")
    match = re.fullmatch(rf"{SOURCE_ALIASES[command_id]}-([0-9]{{2}})", attempt_dir.name)
    if attempt_dir.parent != run_root / "a" or not match:
        raise EvidenceError("source attempt path drifted from compact provenance contract")
    attempt_number = int(match.group(1))
    run_token = hashlib.sha256(str(run_root.resolve()).encode("utf-8")).hexdigest()[:6]
    suffix = f"p7c-{candidate[:10]}-{run_token}-{SOURCE_ALIASES[command_id]}{attempt_number:02d}"
    expected_argv = _format_command(
        command_id, contract, attempt_dir / "junit.xml", suffix, (ROOT / contract["cwd"]).resolve()
    )
    frozen_sha = hashlib.sha256(contract["command"].encode("utf-8")).hexdigest()
    executed = render_command_argv(expected_argv)
    if (
        record.get("id") != command_id
        or record.get("candidate_commit") != candidate
        or record.get("cwd") != contract["cwd"]
        or record.get("resource_class") != contract["resource_class"]
        or record.get("expected_report_count") != contract["expected_report_count"]
        or record.get("selected_test_file_count") != contract["selected_test_file_count"]
        or record.get("minimum_tests") != contract["minimum_tests"]
        or record.get("frozen_command") != contract["command"]
        or record.get("frozen_command_sha256") != frozen_sha
        or record.get("executed_argv") != expected_argv
        or record.get("executed_argv_sha256") != _json_sha256(expected_argv)
        or record.get("executed_command") != executed
        or record.get("executed_command_sha256") != hashlib.sha256(executed.encode("utf-8")).hexdigest()
        or record.get("command_contract_blob_sha256") != runner_blob_sha256
        or record.get("report_suffix") != suffix
        or record.get("environment_sha256") != environment_sha256
    ):
        raise EvidenceError(f"{command_id}: argv, contract, environment, or SHA binding drifted")
    raw = record.get("raw_reports")
    if not isinstance(raw, list) or record.get("raw_report_count") != len(raw):
        raise EvidenceError(f"{command_id}: raw report inventory drifted")
    raw_paths = [
        _assert_bound_artifact(run_root, item.get("path"), item.get("sha256"), "raw JUnit")
        for item in raw
        if isinstance(item, dict)
    ]
    if len(raw_paths) != len(raw):
        raise EvidenceError(f"{command_id}: raw report inventory contains an invalid binding")
    if contract["report_kind"] == "SUREFIRE_GLOB":
        expected_paths = [attempt_dir / "raw" / f"j-{index:03d}.xml" for index in range(1, len(raw) + 1)]
        if raw_paths != expected_paths:
            raise EvidenceError(f"{command_id}: compact Surefire provenance path drifted")
    elif raw_paths != [attempt_dir / "junit.xml"]:
        raise EvidenceError(f"{command_id}: raw JUnit path drifted")
    expected_attempt_files = {stdout.resolve(), stderr.resolve(), *(path.resolve() for path in raw_paths)}
    actual_attempt_files = {
        path.resolve() for path in attempt_dir.rglob("*") if path.is_file()
    }
    if actual_attempt_files != expected_attempt_files:
        raise EvidenceError(f"{command_id}: attempt contains hidden or reused output")
    expected_subdirectories = (
        {(attempt_dir / "raw").resolve()}
        if contract["report_kind"] == "SUREFIRE_GLOB" and raw_paths
        else set()
    )
    actual_subdirectories = {
        path.resolve() for path in attempt_dir.rglob("*") if path.is_dir()
    }
    if actual_subdirectories != expected_subdirectories:
        raise EvidenceError(f"{command_id}: attempt directory inventory drifted")
    if not accepted:
        if record.get("accepted") is not False or record.get("failure_classification") == "NONE":
            raise EvidenceError("failed source record classification binding drifted")
        return attempt_dir
    if _report_count_error(contract, raw_paths):
        raise EvidenceError(f"{command_id}: accepted raw report count drifted")
    if (
        record.get("accepted") is not True
        or record.get("failure_classification") != "NONE"
        or record.get("exit_code") != 0
        or record.get("report") != SOURCE_REPORTS[command_id]
        or record.get("report_path") != f"r/{SOURCE_REPORTS[command_id]}"
    ):
        raise EvidenceError("accepted source status/report binding drifted")
    report_path = _assert_bound_artifact(
        run_root, record.get("report_path"), record.get("report_sha256"), "normalized JUnit"
    )
    report = parse_junit(report_path)
    totals = report.totals
    if (
        report.candidate_commit != candidate
        or report.command_id != command_id
        or totals["tests"] < contract["minimum_tests"]
        or totals["failures"]
        or totals["errors"]
        or totals["skipped"]
        or any(record.get(field) != totals[field] for field in totals)
    ):
        raise EvidenceError("accepted JUnit candidate, totals, or frozen minimum drifted")
    return attempt_dir


def _validate_manifest(manifest: dict[str, Any], run_root: Path, candidate: str) -> None:
    if (
        manifest.get("schema_version") != SCHEMA_VERSION
        or manifest.get("phase") != 7
        or manifest.get("batch") != "P7-BATCH-3"
        or manifest.get("candidate_commit") != candidate
        or manifest.get("accepted_phase_7_candidate_C7") != PHASE7_ENTRY_CANDIDATE
        or manifest.get("accepted_phase_7_evidence_E7") != PHASE7_ENTRY_EVIDENCE
        or manifest.get("decision_ceiling") != "PHASE_7_ENGINEERING_CHECKPOINT_ONLY"
        or any(manifest.get(item) != "PENDING_PROMOTION" for item in MIGRATION_GATES)
        or manifest.get("run_root") != str(run_root.resolve())
        or manifest.get("attempt_id") != run_root.name
        or manifest.get("quarantined_attempts_reused") is not False
    ):
        raise EvidenceError("Phase 7 candidate manifest authority or ceiling drifted")
    _validate_source_tree(manifest, candidate)
    environment_sha256 = _validate_environment(manifest, candidate)
    contracts = source_contracts(candidate)
    contract_sha = _source_contract_sha256(candidate)
    if manifest.get("source_contract_sha256") != contract_sha:
        raise EvidenceError("manifest source contract SHA-256 drifted")
    runner_blob_sha = manifest["environment"]["runner"]["sha256"]
    commands = manifest.get("commands")
    if not isinstance(commands, list):
        raise EvidenceError("manifest accepted commands are invalid")
    pending = manifest.get("pending_failure")
    status = manifest.get("status")
    if status not in {"RUNNING", "REQUIRES_CLASSIFICATION", GREEN_STATUS}:
        raise EvidenceError("candidate manifest has an invalid execution state")
    if (status == "REQUIRES_CLASSIFICATION") != (pending is not None):
        raise EvidenceError("candidate manifest failure state is inconsistent")
    ids = [item.get("id") for item in commands if isinstance(item, dict)]
    if ids != list(COMMAND_ORDER[: len(ids)]):
        raise EvidenceError("resume commands are not the ordered green source prefix")
    seen_attempts: set[Path] = set()
    for record in commands:
        if not isinstance(record, dict):
            raise EvidenceError("accepted source record is invalid")
        command_id = record["id"]
        seen_attempts.add(
            _validate_record(
                record,
                command_id=command_id,
                candidate=candidate,
                run_root=run_root,
                contract=contracts[command_id],
                environment_sha256=environment_sha256,
                runner_blob_sha256=runner_blob_sha,
                accepted=True,
            )
        )
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise EvidenceError("quarantined attempt inventory is invalid")
    retry_counts: dict[str, int] = {}
    for record in quarantined:
        if not isinstance(record, dict) or record.get("failure_classification") != "INFRA":
            raise EvidenceError("only classified same-SHA INFRA attempts may be quarantined")
        command_id = record.get("id")
        if command_id not in contracts:
            raise EvidenceError("quarantined attempt names an unknown source")
        retry_counts[command_id] = retry_counts.get(command_id, 0) + 1
        if retry_counts[command_id] > MAX_INFRA_RERUNS_PER_SOURCE:
            raise EvidenceError("source exceeded the classified INFRA retry ceiling")
        seen_attempts.add(
            _validate_record(
                record,
                command_id=command_id,
                candidate=candidate,
                run_root=run_root,
                contract=contracts[command_id],
                environment_sha256=environment_sha256,
                runner_blob_sha256=runner_blob_sha,
                accepted=False,
            )
        )
    if pending is not None:
        if not isinstance(pending, dict) or len(commands) >= len(COMMAND_ORDER):
            raise EvidenceError("pending source failure is invalid")
        expected_id = COMMAND_ORDER[len(commands)]
        if pending.get("id") != expected_id or pending.get("failure_classification") != "UNCLASSIFIED":
            raise EvidenceError("pending failure is not the next ordered source")
        seen_attempts.add(
            _validate_record(
                pending,
                command_id=expected_id,
                candidate=candidate,
                run_root=run_root,
                contract=contracts[expected_id],
                environment_sha256=environment_sha256,
                runner_blob_sha256=runner_blob_sha,
                accepted=False,
            )
        )
    if status == GREEN_STATUS and len(commands) != len(COMMAND_ORDER):
        raise EvidenceError("green manifest is not the complete ordered source set")
    attempt_root = run_root / "a"
    actual_attempts = (
        {path.resolve() for path in attempt_root.iterdir() if path.is_dir()}
        if attempt_root.is_dir()
        else set()
    )
    if actual_attempts != {path.resolve() for path in seen_attempts}:
        raise EvidenceError("attempt inventory contains hidden, stale, or reused output")
    if attempt_root.is_dir() and any(path.is_file() for path in attempt_root.iterdir()):
        raise EvidenceError("attempt inventory contains an undeclared file")
    expected_reports = {f"r/{SOURCE_REPORTS[item['id']]}" for item in commands}
    actual_reports = {
        _relative(path, run_root) for path in (run_root / "r").glob("*.xml") if path.is_file()
    }
    if actual_reports != expected_reports:
        raise EvidenceError("normalized report inventory is not the ordered green prefix")
    allowed_root_names = {MANIFEST_NAME, "a", "r"}
    if any(path.name not in allowed_root_names for path in run_root.iterdir()):
        raise EvidenceError("run directory contains undeclared or reused output")


def _load_resume_manifest(run_root: Path, candidate: str) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot resume Phase 7 candidate manifest: {exception}") from exception
    if not isinstance(manifest, dict):
        raise EvidenceError("resume manifest must be a JSON object")
    _assert_execution_manifest_seal(manifest)
    if manifest.get("candidate_commit") != candidate:
        raise EvidenceError("resume manifest belongs to another candidate SHA")
    if manifest.get("status") == GREEN_STATUS:
        raise EvidenceError("Phase 7 candidate source suites are already green")
    if manifest.get("status") == "CANDIDATE_BLOCKED":
        raise EvidenceError("classified failure blocked this Phase 7 candidate")
    if manifest.get("status") not in {"RUNNING", "REQUIRES_CLASSIFICATION"}:
        raise EvidenceError("Phase 7 candidate manifest is not in a resumable state")
    _validate_manifest(manifest, run_root, candidate)
    return manifest


def validate_pass_manifest(
    manifest: dict[str, Any], run_root: Path, candidate_commit: str
) -> None:
    candidate = _assert_candidate(candidate_commit)
    if manifest.get("status") != GREEN_STATUS:
        raise EvidenceError("Phase 7 candidate execution manifest is not green")
    if manifest.get("next_phase_permission") != "PENDING_SEPARATE_EVIDENCE":
        raise EvidenceError("runner must not grant Phase 8 permission directly")
    if manifest.get("pending_failure") is not None:
        raise EvidenceError("green manifest retains a pending failure")
    if len(manifest.get("commands", [])) != len(COMMAND_ORDER):
        raise EvidenceError("green manifest does not bind all four sources")
    _validate_manifest(manifest, run_root.resolve(), candidate)


def load_pass_manifest(path: Path, candidate_commit: str) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot load Phase 7 candidate manifest: {exception}") from exception
    if not isinstance(manifest, dict):
        raise EvidenceError("Phase 7 candidate manifest must be a JSON object")
    _assert_execution_manifest_seal(manifest)
    validate_pass_manifest(manifest, path.parent, candidate_commit)
    return manifest


def _classification_map(values: Sequence[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        command_id, separator, classification = value.partition("=")
        classification = classification.upper()
        if (
            not separator
            or command_id not in SOURCE_REPORTS
            or classification not in FAILURE_CLASSIFICATIONS
        ):
            raise EvidenceError(
                "failure classification must be COMMAND_ID="
                + "|".join(sorted(FAILURE_CLASSIFICATIONS))
            )
        if command_id in result:
            raise EvidenceError(f"duplicate failure classification for {command_id}")
        result[command_id] = classification
    return result


def _classify_pending_failure(
    manifest: dict[str, Any], classifications: dict[str, str]
) -> bool:
    pending = manifest.get("pending_failure")
    if not pending:
        if classifications:
            raise EvidenceError("no pending source failure accepts a classification")
        return True
    command_id = pending["id"]
    classification = classifications.get(command_id)
    if not classification:
        manifest["status"] = "REQUIRES_CLASSIFICATION"
        return False
    if set(classifications) != {command_id}:
        raise EvidenceError("classification names a source without the pending failure")
    pending["failure_classification"] = classification
    manifest["quarantined_attempts"].append(pending)
    manifest["pending_failure"] = None
    if classification != "INFRA":
        manifest["status"] = "CANDIDATE_BLOCKED"
        manifest["verification_finished_at"] = _utc_now()
        return False
    prior = sum(
        1
        for item in manifest["quarantined_attempts"]
        if item.get("id") == command_id and item.get("failure_classification") == "INFRA"
    )
    if prior > MAX_INFRA_RERUNS_PER_SOURCE:
        manifest["status"] = "CANDIDATE_BLOCKED"
        manifest["verification_finished_at"] = _utc_now()
        return False
    manifest["status"] = "RUNNING"
    return True


def execute_checkpoint(
    *,
    candidate_commit: str,
    run_root: Path,
    environment_id: str,
    resume: bool,
    classifications: Sequence[str],
) -> dict[str, Any]:
    if classifications and not resume:
        raise EvidenceError("failure classification requires --resume")
    candidate = _assert_candidate(candidate_commit)
    matrix = load_matrix(candidate)
    blockers = gate_blockers(matrix)
    if blockers:
        raise EvidenceError(
            "Phase 7 candidate execution is blocked before source execution: "
            + ", ".join(blockers)
        )
    source_tree = capture_source_tree(candidate)
    run_root = run_root.resolve()
    assert_candidate_run_directory(run_root)
    _assert_run_path_budget(run_root)
    if resume:
        if not run_root.is_dir():
            raise EvidenceError(f"resume run directory does not exist: {run_root}")
        _assert_candidate_unchanged(candidate, run_root)
        manifest = _load_resume_manifest(run_root, candidate)
    else:
        assert_clean_detached_candidate(candidate)
        if run_root.exists():
            raise EvidenceError(f"Phase 7 candidate run directory already exists: {run_root}")
        run_root.mkdir(parents=True)
        manifest = _initial_manifest(candidate, run_root, environment_id, source_tree)
        _write_manifest(run_root / MANIFEST_NAME, manifest)
    _assert_candidate_unchanged(candidate, run_root)
    classification_map = _classification_map(classifications)
    if not _classify_pending_failure(manifest, classification_map):
        _write_manifest(run_root / MANIFEST_NAME, manifest)
        return manifest
    contracts = source_contracts(candidate)
    accepted = {item["id"] for item in manifest["commands"]}
    environment_sha = manifest["environment"]["snapshot_sha256"]
    runner_blob_sha = manifest["environment"]["runner"]["sha256"]
    for command_id in COMMAND_ORDER:
        if command_id in accepted:
            continue
        _assert_candidate_unchanged(candidate, run_root)
        record, passed = _record_source(
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            contract=contracts[command_id],
            environment_sha256=environment_sha,
            runner_blob_sha256=runner_blob_sha,
        )
        if not passed:
            manifest["pending_failure"] = record
            manifest["status"] = "REQUIRES_CLASSIFICATION"
            _write_manifest(run_root / MANIFEST_NAME, manifest)
            return manifest
        manifest["commands"].append(record)
        _write_manifest(run_root / MANIFEST_NAME, manifest)
    manifest["status"] = GREEN_STATUS
    manifest["verification_finished_at"] = _utc_now()
    manifest["next_phase_permission"] = "PENDING_SEPARATE_EVIDENCE"
    manifest["MIG-006"] = "PENDING_PROMOTION"
    manifest["MIG-007"] = "PENDING_PROMOTION"
    _assert_candidate_unchanged(candidate, run_root)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    persisted = load_pass_manifest(run_root / MANIFEST_NAME, candidate)
    return persisted


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plan or execute the Phase 7 Batch 3 exact-SHA engineering candidate checkpoint."
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id", default="local-phase7-candidate")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--failure-classification",
        action="append",
        default=[],
        metavar="COMMAND_ID=CLASS",
        help="Only one classified same-SHA INFRA retry is permitted per source.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        candidate = arguments.candidate_commit.strip().lower()
        if not arguments.execute:
            if arguments.resume or arguments.failure_classification or arguments.run_dir:
                raise EvidenceError(
                    "--run-dir, --resume and --failure-classification require --execute"
                )
            print(json.dumps(candidate_plan(candidate), indent=2))
            return 0
        if arguments.run_dir is None:
            raise EvidenceError("--run-dir is required with --execute")
        manifest = execute_checkpoint(
            candidate_commit=candidate,
            run_root=arguments.run_dir,
            environment_id=arguments.environment_id,
            resume=arguments.resume,
            classifications=arguments.failure_classification,
        )
    except (EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 7 candidate execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                "decision_ceiling": "PHASE_7_ENGINEERING_CHECKPOINT_ONLY",
                "next_phase_permission": "PENDING_SEPARATE_EVIDENCE",
                "MIG-006": "PENDING_PROMOTION",
                "MIG-007": "PENDING_PROMOTION",
            },
            sort_keys=True,
        )
    )
    return 0 if manifest["status"] == GREEN_STATUS else 2


if __name__ == "__main__":
    raise SystemExit(main())
