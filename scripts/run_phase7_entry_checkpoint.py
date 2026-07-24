from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import shutil
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
OUTCOME_CONTRACT_PATH = ROOT / "contracts/agent-platform/outcome/v1/compatibility-matrix.yaml"
ADR_PATH = ROOT / "docs/architecture/adr/0016-phase-7-outcome-engineering-exception.md"
PHASE6_ACCEPTANCE_COMMIT = "d18a1f130a925429e8c2dfd11352cea4ca8673a0"
PHASE6_CHECKPOINT_PATH = "docs/runbooks/temporal-first/phase-6-engineering-checkpoint.md"
ADR_TOKEN = "ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY"
MANIFEST_NAME = "phase7-entry-execution-manifest.json"
SCHEMA_VERSION = "phase7-entry-execution-manifest.v1"
GREEN_STATUS = "SOURCE_SUITES_GREEN_AWAITING_SEPARATE_ENTRY_EVIDENCE"
COMMAND_ORDER = (
    "static_phase7_entry",
    "python_phase7_entry",
    "java_phase7_entry",
    "frontend_phase7_entry",
)
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}
MIGRATION_GATES = ("MIG-006", "MIG-007")
ALLOWED_REPORT_KINDS = {"PYTEST_JUNIT", "VITEST_JUNIT", "SUREFIRE_GLOB"}
FROZEN_CONTRACT_CANDIDATE_ALLOWLIST = (
    "plans/phase-7-outcome-pilot-execution.md",
    "plans/phase-7-outcome-pilot-test-batches.yaml",
    "plans/phase-7-owner-briefs.yaml",
    "docs/architecture/adr/0016-phase-7-outcome-engineering-exception.md",
    "docs/runbooks/temporal-first/phase-7-*",
    "contracts/agent-platform/outcome/**",
    "tests/static/test_phase7_*.py",
    "scripts/run_phase7_entry_checkpoint.py",
    "scripts/generate_phase7_entry_evidence.py",
)
FORBIDDEN_PRODUCT_PREFIXES = (
    "java-api-service/src/main/",
    "python-agent-service/app/",
    "frontend/src/",
)
FORBIDDEN_RUNTIME_PATH_PARTS = {
    "config",
    "configs",
    "migration",
    "migrations",
    "runtime",
    "runtimes",
    "selector",
    "selectors",
    "worker",
    "workers",
}
FROZEN_SOURCE_CONTRACTS: dict[str, dict[str, Any]] = {
    "static_phase7_entry": {
        "cwd": ".",
        "resource_class": "light",
        "report": "static-phase7-entry.xml",
        "report_kind": "PYTEST_JUNIT",
        "expected_report_count": 1,
        "selected_test_file_count": 5,
        "minimum_tests": 65,
        "command": (
            r"D:\miniconda\python.exe -m pytest -q "
            "tests/static/test_phase7_outcome_contracts.py "
            "tests/static/test_phase7_outcome_pilot_plan.py "
            "tests/static/test_phase7_entry_checkpoint.py "
            "tests/static/test_phase7_entry_evidence.py "
            "tests/static/test_temporal_refactor_traceability.py "
            "--junitxml={raw_report}"
        ),
    },
    "python_phase7_entry": {
        "cwd": "python-agent-service",
        "resource_class": "light",
        "report": "python-phase7-entry.xml",
        "report_kind": "PYTEST_JUNIT",
        "expected_report_count": 1,
        "selected_test_file_count": 1,
        "minimum_tests": 3,
        "command": (
            r"D:\miniconda\python.exe -m pytest -q tests/test_evaluation.py "
            "--junitxml={raw_report}"
        ),
    },
    "java_phase7_entry": {
        "cwd": "java-api-service",
        "resource_class": "heavy",
        "report": "java-phase7-entry.xml",
        "report_kind": "SUREFIRE_GLOB",
        "raw_report_glob": "target/surefire-reports/TEST-*-{report_suffix}.xml",
        "expected_report_count": 7,
        "selected_test_file_count": 7,
        "minimum_tests": 18,
        "command": (
            r".\mvnw.cmd -DforkCount=1 "
            "-Dtest=ReviewApplicationServiceV2Test,FrozenReviewPacketTest,"
            "ApprovalPolicyEngineTest,ReviewControllerTest,CaseOutcomeServiceTest,"
            "CaseOutcomeControllerTest,RestClientEvaluationAgentClientTest "
            "-Dsurefire.reportNameSuffix={report_suffix} test"
        ),
    },
    "frontend_phase7_entry": {
        "cwd": "frontend",
        "resource_class": "light",
        "report": "frontend-phase7-entry.xml",
        "report_kind": "VITEST_JUNIT",
        "expected_report_count": 1,
        "selected_test_file_count": 6,
        "minimum_tests": 41,
        "command": (
            "node node_modules/vitest/vitest.mjs run "
            "src/views/disputes/OutcomeView.test.js "
            "src/views/disputes/AdjudicationDraftView.test.js "
            "src/views/reviews/ReviewQueueView.test.js "
            "src/views/reviews/ReviewWorkbenchView.test.js "
            "src/views/ReviewWorkbenchView.test.js src/api/review.test.js "
            "--reporter=default --reporter=junit --outputFile.junit={raw_report}"
        ),
    },
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _json_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def capture_environment(environment_id: str) -> dict[str, Any]:
    snapshot = _capture_environment_host(environment_id)
    dependencies = snapshot.get("dependency_manifests")
    if not isinstance(dependencies, list):
        raise EvidenceError("environment dependency manifest inventory is missing")
    commit_bound: list[dict[str, str]] = []
    for item in dependencies:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise EvidenceError("environment dependency manifest record is invalid")
        process = subprocess.run(
            ["git", "show", f"HEAD:{item['path']}"],
            cwd=ROOT,
            capture_output=True,
            check=False,
        )
        if process.returncode:
            error = process.stderr.decode("utf-8", errors="replace").strip()
            raise EvidenceError(
                f"cannot bind dependency manifest {item['path']} to candidate blob: {error}"
            )
        commit_bound.append(
            {
                "path": item["path"],
                "sha256": hashlib.sha256(process.stdout).hexdigest(),
                "byte_source": "CANDIDATE_GIT_BLOB",
            }
        )
    snapshot["dependency_manifests"] = commit_bound
    snapshot.pop("snapshot_sha256", None)
    snapshot["snapshot_sha256"] = _json_sha256(snapshot)
    return snapshot


def load_matrix() -> dict[str, Any]:
    try:
        matrix = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exception:
        raise EvidenceError(f"cannot load Phase 7 entry matrix: {exception}") from exception
    if not isinstance(matrix, dict):
        raise EvidenceError("Phase 7 entry matrix must be a YAML object")
    if matrix.get("schema_version") != "phase-test-batches.v1" or matrix.get("phase") != 7:
        raise EvidenceError("Phase 7 entry runner loaded a non-Phase-7 matrix")
    return matrix


def _entry_batch(matrix: dict[str, Any]) -> dict[str, Any]:
    try:
        batch = matrix["batches"]["batch_0_entry"]
    except (KeyError, TypeError) as exception:
        raise EvidenceError("Phase 7 matrix has no batch_0_entry contract") from exception
    if not isinstance(batch, dict):
        raise EvidenceError("Phase 7 batch_0_entry contract must be an object")
    return batch


def _source_contracts(matrix: dict[str, Any]) -> dict[str, dict[str, Any]]:
    batch = _entry_batch(matrix)
    reports = batch.get("source_reports")
    commands = batch.get("source_commands")
    if not isinstance(reports, dict) or tuple(reports) != COMMAND_ORDER:
        raise EvidenceError("Phase 7 source report order or IDs drifted")
    if not isinstance(commands, list) or [
        item.get("id") for item in commands if isinstance(item, dict)
    ] != list(COMMAND_ORDER):
        raise EvidenceError("Phase 7 source command order or IDs drifted")

    result: dict[str, dict[str, Any]] = {}
    seen_reports: set[str] = set()
    for item in commands:
        command_id = item["id"]
        frozen = FROZEN_SOURCE_CONTRACTS.get(command_id)
        if frozen is None:
            raise EvidenceError(f"{command_id}: source command is not frozen by the runner")
        report = reports.get(command_id)
        if item.get("report") != report:
            raise EvidenceError(f"{command_id}: command/report mapping drifted")
        if (
            not isinstance(report, str)
            or Path(report).name != report
            or Path(report).suffix.lower() != ".xml"
            or report in seen_reports
        ):
            raise EvidenceError(f"{command_id}: report must be one unique XML filename")
        if not isinstance(item.get("cwd"), str) or not isinstance(item.get("command"), str):
            raise EvidenceError(f"{command_id}: source contract is incomplete")
        if item.get("report_kind") not in ALLOWED_REPORT_KINDS:
            raise EvidenceError(f"{command_id}: unsupported report kind")
        if item["report_kind"] == "SUREFIRE_GLOB" and not isinstance(
            item.get("raw_report_glob"), str
        ):
            raise EvidenceError(f"{command_id}: Surefire report glob is missing")
        resource_class = item.get("resource_class")
        expected_resource = "heavy" if command_id == "java_phase7_entry" else "light"
        if resource_class not in {expected_resource, expected_resource.upper()}:
            raise EvidenceError(f"{command_id}: resource class must be {expected_resource}")
        expected_count = item.get("expected_report_count")
        if (
            not isinstance(expected_count, int) or isinstance(expected_count, bool) or expected_count < 1
        ):
            raise EvidenceError(f"{command_id}: expected_report_count must be positive")
        selected_count = item.get("selected_test_file_count")
        if (
            not isinstance(selected_count, int)
            or isinstance(selected_count, bool)
            or selected_count < 1
        ):
            raise EvidenceError(f"{command_id}: selected_test_file_count must be positive")
        minimum_tests = item.get("minimum_tests")
        if (
            not isinstance(minimum_tests, int) or isinstance(minimum_tests, bool) or minimum_tests < 1
        ):
            raise EvidenceError(f"{command_id}: minimum_tests must be positive")
        for field in ("cwd", "resource_class", "report", "report_kind", "command"):
            actual = item.get(field)
            if field == "resource_class" and isinstance(actual, str):
                actual = actual.lower()
            if actual != frozen[field]:
                raise EvidenceError(
                    f"{command_id}: {field} drifted from the immutable source contract"
                )
        if item.get("raw_report_glob") != frozen.get("raw_report_glob"):
            raise EvidenceError(
                f"{command_id}: raw_report_glob drifted from the immutable source contract"
            )
        if expected_count != frozen["expected_report_count"]:
            raise EvidenceError(
                f"{command_id}: expected_report_count drifted from the immutable source contract"
            )
        if selected_count != frozen["selected_test_file_count"]:
            raise EvidenceError(
                f"{command_id}: selected_test_file_count drifted from the immutable source contract"
            )
        if minimum_tests != frozen["minimum_tests"]:
            raise EvidenceError(
                f"{command_id}: minimum_tests drifted from the immutable source contract"
            )
        arguments = _split_approved_command(item["command"])
        if command_id == "java_phase7_entry":
            selectors = next(
                (
                    argument.removeprefix("-Dtest=").split(",")
                    for argument in arguments
                    if argument.startswith("-Dtest=")
                ),
                [],
            )
        elif command_id == "frontend_phase7_entry":
            selectors = [argument for argument in arguments if argument.endswith(".test.js")]
        else:
            selectors = [argument for argument in arguments if argument.endswith(".py")]
        if (
            len(selectors) != frozen["selected_test_file_count"]
            or len(selectors) != len(set(selectors))
        ):
            raise EvidenceError(
                f"{command_id}: selected test selector set drifted from the immutable source contract"
            )
        seen_reports.add(report)
        result[command_id] = {
            **item,
            "expected_report_count": frozen["expected_report_count"],
            "selected_test_file_count": frozen["selected_test_file_count"],
            "minimum_tests": frozen["minimum_tests"],
        }
    return result


def _source_reports(matrix: dict[str, Any]) -> dict[str, str]:
    _source_contracts(matrix)
    return dict(_entry_batch(matrix)["source_reports"])


def _observed_p7_status(matrix: dict[str, Any]) -> Any:
    gate = matrix.get("gate", {})
    observed = gate.get("observed_entry_state", {}) if isinstance(gate, dict) else {}
    if "P7.0" in observed:
        return observed["P7.0"]
    if "p7_0_entry_gate" in observed:
        return observed["p7_0_entry_gate"]
    return gate.get("contract_gate_status") if isinstance(gate, dict) else None


def _outcome_gate_state() -> dict[str, Any]:
    try:
        contract = yaml.safe_load(OUTCOME_CONTRACT_PATH.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exception:
        raise EvidenceError(f"cannot load Outcome compatibility contract: {exception}") from exception
    if (
        not isinstance(contract, dict)
        or contract.get("schema_version") != "outcome-contract-compatibility.v1"
        or contract.get("contract") != "outcome_flow.v1"
        or contract.get("accepted_base") != PHASE6_ACCEPTANCE_COMMIT
    ):
        raise EvidenceError("Outcome compatibility contract authority or accepted base drifted")
    state = contract.get("gate_state")
    if not isinstance(state, dict):
        raise EvidenceError("Outcome compatibility contract has no gate_state")
    return state


def gate_blockers(matrix: dict[str, Any]) -> list[str]:
    batch = _entry_batch(matrix)
    execution_gate = batch.get("execution_gate", {})
    gate = matrix.get("gate", {})
    observed = gate.get("accepted_upstream_state", {}) if isinstance(gate, dict) else {}
    outcome = _outcome_gate_state()
    blockers: list[str] = []

    if observed.get("phase_6_engineering_checkpoint") != "PASS" or outcome.get(
        "upstream_engineering_checkpoint"
    ) != "PASS":
        blockers.append("PHASE_6_ENGINEERING_CHECKPOINT_NOT_AUTHENTICATED")
    if (
        observed.get("phase_7_engineering_exception") != ADR_TOKEN
        or execution_gate.get("required_exception_state") != ADR_TOKEN
        or outcome.get("engineering_exception_token") != ADR_TOKEN
    ):
        blockers.append("ADR_0016_ENGINEERING_EXCEPTION_NOT_AUTHENTICATED")
    accepted_checkpoint = gate.get("accepted_phase_6_checkpoint_A6")
    if (
        accepted_checkpoint != PHASE6_ACCEPTANCE_COMMIT
        or execution_gate.get("required_upstream_checkpoint") != PHASE6_ACCEPTANCE_COMMIT
    ):
        blockers.append("PHASE_6_ACCEPTANCE_COMMIT_NOT_AUTHENTICATED")
    if (
        observed.get("next_phase_permission") != "PHASE_7_ENGINEERING_ONLY"
        or execution_gate.get("required_next_phase_permission") != "PHASE_7_ENGINEERING_ONLY"
        or outcome.get("permission") != "PHASE_7_ENGINEERING_ONLY"
    ):
        blockers.append("PHASE_7_ENGINEERING_ONLY_PERMISSION_NOT_AUTHENTICATED")
    if (
        gate.get("entry_decision") != "NOT_RUN"
        or execution_gate.get("required_entry_decision") != "CONTRACT_CANDIDATE_READY"
        or execution_gate.get("required_candidate_state") != "P7_0_CONTRACT_CANDIDATE_COMMITTED"
        or outcome.get("contract_candidate_state") != "CONTRACT_CANDIDATE_READY"
    ):
        blockers.append("P7_CONTRACT_CANDIDATE_NOT_READY")
    if (
        batch.get("status") != "NOT_RUN"
        or execution_gate.get("required_batch_status") != "READY_FOR_EXACT_SHA_BATCH_0"
        or execution_gate.get("reject_before_source_execution") is not True
    ):
        blockers.append("P7_BATCH_0_NOT_READY")
    if (
        _observed_p7_status(matrix) not in {"NOT_RUN", "P7.0_NOT_RUN"}
        or outcome.get("p7_0_entry_gate") != "NOT_RUN"
        or outcome.get("entry_evidence_recorded") is not False
        or outcome.get("phase_7_implementation_allowed") is not False
        or gate.get("implementation_authorized") is not False
    ):
        blockers.append("P7_0_MUST_REMAIN_NOT_RUN")
    for migration in MIGRATION_GATES:
        if (
            observed.get(migration) != "PENDING_PROMOTION"
            or outcome.get("migrations", {}).get(migration) != "PENDING_PROMOTION"
        ):
            blockers.append(f"{migration.replace('-', '_')}_MUST_REMAIN_PENDING")

    constraints = gate.get("traffic_constraints", {}) if isinstance(gate, dict) else {}
    required_false = {
        "formal_outcome_workflow_activation_allowed": "FORMAL_OUTCOME_WORKFLOW_MUST_REMAIN_FORBIDDEN",
        "temporal_outcome_allocation_allowed": "TEMPORAL_OUTCOME_ALLOCATION_MUST_REMAIN_FORBIDDEN",
        "formal_outcome_graph_sink_allowed": "FORMAL_OUTCOME_GRAPH_SINK_MUST_REMAIN_FORBIDDEN",
        "real_tool_effect_allowed": "REAL_TOOL_EFFECTS_MUST_REMAIN_FORBIDDEN",
        "real_or_party_data_shadow_allowed": "REAL_CASE_SHADOW_MUST_REMAIN_FORBIDDEN",
        "production_traffic_change_allowed": "PRODUCTION_TRAFFIC_MUST_REMAIN_FORBIDDEN",
        "canary_allowed": "CANARY_MUST_REMAIN_FORBIDDEN",
        "promotion_allowed": "PROMOTION_MUST_REMAIN_FORBIDDEN",
    }
    for field, blocker in required_false.items():
        if constraints.get(field) is not False:
            blockers.append(blocker)
    return blockers


SOURCE_REPORTS = _source_reports(load_matrix())


def _git_show(commit: str, path: str) -> str:
    process = subprocess.run(
        ["git", "show", f"{commit}:{path}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if process.returncode:
        raise EvidenceError(f"cannot authenticate {commit}:{path}: {process.stderr.strip()}")
    return process.stdout


def _allowed_candidate_patterns(matrix: dict[str, Any] | None = None) -> tuple[str, ...]:
    matrix = matrix or load_matrix()
    values = matrix.get("gate", {}).get("contract_candidate_allowed_paths")
    if (
        not isinstance(values, list)
        or not values
        or any(not isinstance(value, str) or not value.strip() for value in values)
    ):
        raise EvidenceError("Phase 7 contract candidate allowlist is missing")
    patterns = tuple(value.replace("\\", "/") for value in values)
    if patterns != FROZEN_CONTRACT_CANDIDATE_ALLOWLIST:
        raise EvidenceError(
            "Phase 7 matrix allowlist drifted from the runner's immutable contract-only scope"
        )
    return patterns


def _matrix_at_candidate(candidate: str) -> dict[str, Any]:
    try:
        matrix = yaml.safe_load(
            _git_show(candidate, MATRIX_PATH.relative_to(ROOT).as_posix())
        )
    except yaml.YAMLError as exception:
        raise EvidenceError(f"candidate Phase 7 matrix is invalid: {exception}") from exception
    if (
        not isinstance(matrix, dict)
        or matrix.get("schema_version") != "phase-test-batches.v1"
        or matrix.get("phase") != 7
    ):
        raise EvidenceError("candidate does not contain an authentic Phase 7 matrix")
    return matrix


def _matches_frozen_candidate_scope(path: str) -> bool:
    exact = {
        "plans/phase-7-outcome-pilot-execution.md",
        "plans/phase-7-outcome-pilot-test-batches.yaml",
        "plans/phase-7-owner-briefs.yaml",
        "docs/architecture/adr/0016-phase-7-outcome-engineering-exception.md",
        "scripts/run_phase7_entry_checkpoint.py",
        "scripts/generate_phase7_entry_evidence.py",
    }
    if path in exact:
        return True
    if path.startswith("docs/runbooks/temporal-first/phase-7-"):
        return len(path) > len("docs/runbooks/temporal-first/phase-7-")
    if path.startswith("contracts/agent-platform/outcome/"):
        return len(path) > len("contracts/agent-platform/outcome/")
    return (
        path.startswith("tests/static/test_phase7_")
        and path.endswith(".py")
        and "/" not in path[len("tests/static/") :]
    )


def _is_forbidden_product_or_runtime_path(path: str) -> bool:
    lowered = path.lower()
    if any(lowered.startswith(prefix) for prefix in FORBIDDEN_PRODUCT_PREFIXES):
        return True
    parts = tuple(part for part in lowered.split("/") if part)
    name = parts[-1] if parts else ""
    stem = name.rsplit(".", 1)[0]
    if any(part in FORBIDDEN_RUNTIME_PATH_PARTS for part in parts):
        return True
    if (
        "v045" in stem
        or "migration" in stem
        or "selector" in stem
        or "worker" in stem
        or "runtime-config" in stem
        or stem.startswith("application-")
        or name in {"application.yml", "application.yaml", "application.properties"}
    ):
        return True
    return False


def assert_contract_only_candidate(
    candidate_commit: str, matrix: dict[str, Any] | None = None
) -> list[str]:
    candidate = _assert_candidate(candidate_commit)
    assert_base_ancestor(PHASE6_ACCEPTANCE_COMMIT, candidate)
    matrix = matrix or _matrix_at_candidate(candidate)
    process = subprocess.run(
        [
            "git",
            "diff",
            "--name-status",
            "--no-renames",
            "--diff-filter=ACDMRTUXB",
            PHASE6_ACCEPTANCE_COMMIT,
            candidate,
            "--",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if process.returncode:
        raise EvidenceError(f"cannot authenticate Phase 7 candidate scope: {process.stderr.strip()}")
    records = [line for line in process.stdout.splitlines() if line.strip()]
    if not records:
        raise EvidenceError("Phase 7 contract candidate has no changes from A6")
    _allowed_candidate_patterns(matrix)
    changed: list[str] = []
    for record in records:
        fields = record.split("\t")
        if len(fields) != 2:
            raise EvidenceError(f"cannot authenticate candidate path record: {record}")
        status, path = fields
        normalized = path.replace("\\", "/")
        if status.startswith("D"):
            raise EvidenceError(f"Phase 7 contract candidate may not delete {normalized}")
        if status[:1] not in {"A", "C", "M", "T", "U", "X", "B"}:
            raise EvidenceError(f"unsupported candidate change status {status}")
        if _is_forbidden_product_or_runtime_path(normalized):
            raise EvidenceError(
                f"Phase 7 candidate contains forbidden product/runtime path: {normalized}"
            )
        if not _matches_frozen_candidate_scope(normalized):
            raise EvidenceError(
                f"Phase 7 candidate contains product/runtime or undeclared path: {normalized}"
            )
        changed.append(normalized)
    return changed


def _assert_upstream_authority(candidate: str) -> None:
    assert_base_ancestor(PHASE6_ACCEPTANCE_COMMIT, candidate)
    checkpoint = _git_show(PHASE6_ACCEPTANCE_COMMIT, PHASE6_CHECKPOINT_PATH)
    required_checkpoint_tokens = (
        "engineering_checkpoint: PASS",
        "next_phase_permission: PHASE_7_ENGINEERING_ONLY",
        "MIG-006: PENDING_PROMOTION",
    )
    if any(token not in checkpoint for token in required_checkpoint_tokens):
        raise EvidenceError("fixed Phase 6 acceptance commit does not authenticate Phase 7 engineering")
    adr = _git_show(
        candidate,
        ADR_PATH.relative_to(ROOT).as_posix(),
    )
    if ADR_TOKEN not in adr or "Status: ACCEPTED FOR ENGINEERING ONLY" not in adr:
        raise EvidenceError("ADR 0016 engineering exception token/status is not authenticated")


def entry_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    matrix = load_matrix()
    commands = _source_contracts(matrix)
    blockers = gate_blockers(matrix)
    return {
        "schema_version": "phase7-entry-run-plan.v1",
        "phase": 7,
        "candidate_commit": candidate,
        "accepted_phase_6_checkpoint_sha": PHASE6_ACCEPTANCE_COMMIT,
        "engineering_exception": ADR_TOKEN,
        "execution_allowed": not blockers,
        "blocked_reasons": blockers,
        "executed_source_count": 0,
        "execution_order": list(COMMAND_ORDER),
        "commands": [
            {
                "id": command_id,
                "cwd": commands[command_id]["cwd"],
                "matrix_command": commands[command_id]["command"],
                "matrix_command_sha256": hashlib.sha256(
                    commands[command_id]["command"].encode("utf-8")
                ).hexdigest(),
                "report": SOURCE_REPORTS[command_id],
                "report_kind": commands[command_id]["report_kind"],
                "resource_class": commands[command_id]["resource_class"].lower(),
            }
            for command_id in COMMAND_ORDER
        ],
        "concurrency": {
            "runner_execution": "sequential",
            "maximum_heavy_processes": 1,
            "maximum_light_processes": 2,
        },
        "runtime_restrictions": {
            "formal_outcome_workflow": "forbidden",
            "temporal_outcome_allocation": "forbidden",
            "real_tool_effects": "forbidden",
            "real_case_shadow": "forbidden",
            "production_traffic": "forbidden",
            "canary_or_promotion": "forbidden",
        },
        "green_result_ceiling": GREEN_STATUS,
        "contract_gate": "P7.0_NOT_RUN",
        "implementation_authorized": False,
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
    }


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    seal_execution_manifest(manifest)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        _write_json(temporary, manifest)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _initial_manifest(
    *, candidate: str, environment_id: str, run_root: Path
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "phase": 7,
        "candidate_commit": candidate,
        "accepted_phase_6_checkpoint_sha": PHASE6_ACCEPTANCE_COMMIT,
        "engineering_exception": ADR_TOKEN,
        "attempt_id": run_root.name,
        "run_root": str(run_root.resolve()),
        "status": "RUNNING",
        "contract_gate": "P7.0_NOT_RUN",
        "implementation_authorized": False,
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "verification_started_at": _utc_now(),
        "verification_finished_at": None,
        "environment": capture_environment(environment_id),
        "concurrency": {
            "runner_execution": "sequential",
            "maximum_heavy_processes": 1,
            "maximum_light_processes": 2,
        },
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
    }


def _relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


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


def _assert_bound_artifact(
    run_root: Path, relative: Any, expected_sha256: Any, context: str
) -> Path:
    if not isinstance(relative, str) or not isinstance(expected_sha256, str):
        raise EvidenceError(f"{context} binding is incomplete")
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise EvidenceError(f"{context} path escapes or is missing")
    if _sha256(path) != expected_sha256:
        raise EvidenceError(f"{context} SHA-256 drifted")
    return path


def _validate_environment(manifest: dict[str, Any]) -> str:
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise EvidenceError("execution manifest lacks an environment snapshot")
    digest = environment.get("snapshot_sha256")
    unsigned = dict(environment)
    unsigned.pop("snapshot_sha256", None)
    if not isinstance(digest, str) or _json_sha256(unsigned) != digest:
        raise EvidenceError("execution environment snapshot SHA-256 drifted")
    if not str(environment.get("environment_id", "")).strip():
        raise EvidenceError("execution environment identity is missing")
    dependencies = environment.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise EvidenceError("execution environment dependency manifests are missing")
    for item in dependencies:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise EvidenceError("execution environment dependency record is invalid")
        if item.get("byte_source") != "CANDIDATE_GIT_BLOB":
            raise EvidenceError("execution environment dependency byte source drifted")
        process = subprocess.run(
            ["git", "show", f"HEAD:{item['path']}"],
            cwd=ROOT,
            capture_output=True,
            check=False,
        )
        if (
            process.returncode
            or hashlib.sha256(process.stdout).hexdigest() != item.get("sha256")
        ):
            raise EvidenceError(f"dependency manifest {item['path']} SHA-256 drifted")
    return digest


def _validate_resume_manifest(
    manifest: dict[str, Any], run_root: Path, candidate: str
) -> None:
    if (
        manifest.get("accepted_phase_6_checkpoint_sha") != PHASE6_ACCEPTANCE_COMMIT
        or manifest.get("engineering_exception") != ADR_TOKEN
        or manifest.get("contract_gate") != "P7.0_NOT_RUN"
        or manifest.get("implementation_authorized") is not False
        or any(manifest.get(item) != "PENDING_PROMOTION" for item in MIGRATION_GATES)
    ):
        raise EvidenceError("resume manifest relaxed a Phase 7 entry gate")
    if manifest.get("run_root") != str(run_root.resolve()):
        raise EvidenceError("resume manifest run_root binding drifted")
    environment_sha256 = _validate_environment(manifest)
    commands = manifest.get("commands")
    if not isinstance(commands, list):
        raise EvidenceError("resume manifest commands are invalid")
    ids = [item.get("id") for item in commands if isinstance(item, dict)]
    if ids != list(COMMAND_ORDER[: len(ids)]):
        raise EvidenceError("resume commands are not the ordered source prefix")
    contracts = _source_contracts(load_matrix())
    for record in commands:
        command_id = record.get("id")
        contract = contracts.get(command_id)
        if contract is None:
            raise EvidenceError("resume record names an unknown source")
        expected_matrix_sha = hashlib.sha256(contract["command"].encode("utf-8")).hexdigest()
        executed_argv = record.get("executed_argv")
        executed_command = record.get("executed_command")
        if (
            record.get("candidate_commit") != candidate
            or record.get("accepted") is not True
            or record.get("failure_classification") != "NONE"
            or record.get("exit_code") != 0
            or record.get("cwd") != contract["cwd"]
            or record.get("resource_class") != contract["resource_class"].lower()
            or record.get("expected_report_count") != contract["expected_report_count"]
            or record.get("selected_test_file_count") != contract["selected_test_file_count"]
            or record.get("minimum_tests") != contract["minimum_tests"]
            or record.get("matrix_command") != contract["command"]
            or record.get("matrix_command_sha256") != expected_matrix_sha
            or record.get("environment_sha256") != environment_sha256
            or not isinstance(executed_argv, list)
            or executed_command != render_command_argv(executed_argv)
            or record.get("executed_command_sha256")
            != hashlib.sha256(str(executed_command).encode("utf-8")).hexdigest()
        ):
            raise EvidenceError("resume accepted source binding drifted")
        bound_streams: dict[str, Path] = {}
        for stream in ("stdout", "stderr"):
            bound_streams[stream] = _assert_bound_artifact(
                run_root,
                record.get(f"{stream}_path"),
                record.get(f"{stream}_sha256"),
                f"resume accepted {stream}",
            )
        attempt_dir = bound_streams["stdout"].parent
        if (
            bound_streams["stdout"].name != "stdout.log"
            or bound_streams["stderr"] != attempt_dir / "stderr.log"
            or attempt_dir.parent != run_root / "attempts"
            or not re.fullmatch(rf"{re.escape(command_id)}-[0-9]{{2,}}", attempt_dir.name)
        ):
            raise EvidenceError("resume accepted source attempt path drifted")
        report_suffix = record.get("report_suffix")
        expected_suffix = (
            f"p7-{candidate[:12]}-"
            f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
        )
        if report_suffix != expected_suffix:
            raise EvidenceError("resume accepted source report suffix drifted")
        cwd = (ROOT / contract["cwd"]).resolve()
        expected_argv = _format_command(
            contract, attempt_dir / "raw-junit.xml", expected_suffix, cwd
        )
        if executed_argv != expected_argv:
            raise EvidenceError(f"{command_id}: executed argv drifted from approved matrix command")
        raw_report_records = record.get("raw_reports")
        if not isinstance(raw_report_records, list):
            raise EvidenceError("resume accepted raw report bindings are invalid")
        raw_report_paths: list[Path] = []
        for raw_report in raw_report_records:
            if not isinstance(raw_report, dict):
                raise EvidenceError("resume accepted raw report binding is invalid")
            raw_report_paths.append(
                _assert_bound_artifact(
                    run_root,
                    raw_report.get("path"),
                    raw_report.get("sha256"),
                    "resume accepted raw report",
                )
            )
        if record.get("raw_report_count") != len(raw_report_paths):
            raise EvidenceError("resume accepted raw report count drifted")
        count_error = _report_count_error(contract, raw_report_paths)
        if count_error:
            raise EvidenceError(f"resume accepted raw report count rejected: {count_error}")
        if contract["report_kind"] == "SUREFIRE_GLOB":
            expected_name = Path(
                contract["raw_report_glob"].format(report_suffix=expected_suffix)
            ).name
            if any(
                path.parent != attempt_dir / "raw-surefire"
                or not fnmatch.fnmatchcase(path.name, expected_name)
                for path in raw_report_paths
            ):
                raise EvidenceError("resume accepted Surefire report set drifted")
        elif raw_report_paths != [attempt_dir / "raw-junit.xml"]:
            raise EvidenceError("resume accepted JUnit output path drifted")
        report_path = _assert_bound_artifact(
            run_root,
            record.get("report_path"),
            record.get("report_sha256"),
            "resume accepted report",
        )
        if (
            record.get("report") != SOURCE_REPORTS[command_id]
            or record.get("report_path") != f"source/{SOURCE_REPORTS[command_id]}"
        ):
            raise EvidenceError("resume accepted normalized report mapping drifted")
        report = parse_junit(report_path)
        if report.candidate_commit != candidate or report.command_id != command_id:
            raise EvidenceError("resume accepted JUnit candidate/command binding drifted")
        totals = report.totals
        if not totals["tests"] or totals["failures"] or totals["errors"] or totals["skipped"]:
            raise EvidenceError("resume accepted JUnit is not all-pass zero-skip")
        if any(record.get(field) != totals[field] for field in ("tests", "failures", "errors", "skipped")):
            raise EvidenceError("resume accepted JUnit totals record drifted")
    if manifest.get("quarantined_attempts_reused") is not False:
        raise EvidenceError("resume manifest reused quarantined attempts")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise EvidenceError("resume quarantined attempts are invalid")
    for record in quarantined:
        if (
            not isinstance(record, dict)
            or record.get("candidate_commit") != candidate
            or record.get("failure_classification") != "INFRA"
            or record.get("id") not in SOURCE_REPORTS
        ):
            raise EvidenceError("only same-SHA INFRA attempts may be quarantined")
        for stream in ("stdout", "stderr"):
            _assert_bound_artifact(
                run_root,
                record.get(f"{stream}_path"),
                record.get(f"{stream}_sha256"),
                f"quarantined INFRA {stream}",
            )
        raw_reports = record.get("raw_reports")
        if not isinstance(raw_reports, list):
            raise EvidenceError("quarantined INFRA raw reports are invalid")
        for raw_report in raw_reports:
            if not isinstance(raw_report, dict):
                raise EvidenceError("quarantined INFRA raw report is invalid")
            _assert_bound_artifact(
                run_root,
                raw_report.get("path"),
                raw_report.get("sha256"),
                "quarantined INFRA raw report",
            )


def _load_resume_manifest(run_root: Path, candidate: str) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot resume Phase 7 manifest {path}: {exception}") from exception
    if not isinstance(manifest, dict):
        raise EvidenceError("resume manifest must be a JSON object")
    if manifest.get("schema_version") != SCHEMA_VERSION or manifest.get("phase") != 7:
        raise EvidenceError("resume manifest is not a Phase 7 entry execution manifest")
    _assert_execution_manifest_seal(manifest)
    if manifest.get("candidate_commit") != candidate:
        raise EvidenceError("resume manifest belongs to another candidate SHA")
    if manifest.get("attempt_id") != run_root.name:
        raise EvidenceError("resume manifest belongs to another run directory")
    if manifest.get("status") == GREEN_STATUS:
        raise EvidenceError("Phase 7 entry source suites are already green")
    if manifest.get("status") == "CANDIDATE_BLOCKED":
        raise EvidenceError("classified failure blocked this Phase 7 candidate")
    _validate_resume_manifest(manifest, run_root, candidate)
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
    manifest["status"] = "RUNNING"
    return True


def _format_command(
    item: dict[str, Any], raw_path: Path, report_suffix: str, cwd: Path
) -> list[str]:
    arguments = _split_approved_command(item["command"])
    rendered = [
        argument.replace("{raw_report}", str(raw_path.resolve())).replace(
            "{report_suffix}", report_suffix
        )
        for argument in arguments
    ]
    if any("{" in argument or "}" in argument for argument in rendered):
        raise EvidenceError(f"{item['id']}: command placeholder drifted")
    if os.name == "nt" and item["id"] == "java_phase7_entry":
        wrapper_name = PureWindowsPath(rendered[0]).name.lower()
        if wrapper_name not in {"mvnw", "mvnw.cmd"}:
            raise EvidenceError("java_phase7_entry must invoke the checked-in Maven wrapper")
        wrapper = (cwd / "mvnw.cmd").resolve()
        if not wrapper.is_file() or not wrapper.is_relative_to(ROOT.resolve()):
            raise EvidenceError("java_phase7_entry Maven wrapper is missing or escapes the worktree")
        rendered[0] = str(wrapper)
    return rendered


def _raw_reports(
    item: dict[str, Any], raw_path: Path, report_suffix: str, cwd: Path
) -> list[Path]:
    if item["report_kind"] != "SUREFIRE_GLOB":
        return [raw_path] if raw_path.is_file() else []
    rendered = item["raw_report_glob"].format(report_suffix=report_suffix)
    return sorted(path for path in cwd.glob(rendered) if path.is_file())


def _report_count_error(item: dict[str, Any], reports: Sequence[Path]) -> str | None:
    if item["report_kind"] != "SUREFIRE_GLOB" and len(reports) != 1:
        return f"expected one raw JUnit report, found {len(reports)}"
    if (
        item["report_kind"] == "SUREFIRE_GLOB"
        and len(reports) != item["expected_report_count"]
    ):
        return (
            f"expected {item['expected_report_count']} Surefire JUnit reports, "
            f"found {len(reports)}"
        )
    return None


def _record_source(
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    matrix_item: dict[str, Any],
    environment_sha256: str,
) -> tuple[dict[str, Any], bool]:
    attempt_number = 1 + sum(
        1 for path in (run_root / "attempts").glob(f"{command_id}-*") if path.is_dir()
    )
    attempt_dir = run_root / "attempts" / f"{command_id}-{attempt_number:02d}"
    attempt_dir.mkdir(parents=True, exist_ok=False)
    raw_path = attempt_dir / "raw-junit.xml"
    stdout_path = attempt_dir / "stdout.log"
    stderr_path = attempt_dir / "stderr.log"
    report_suffix = (
        f"p7-{candidate[:12]}-"
        f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    cwd = (ROOT / matrix_item["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise EvidenceError(f"{command_id}: matrix cwd escapes the candidate worktree")
    if _raw_reports(matrix_item, raw_path, report_suffix, cwd):
        raise EvidenceError(f"{command_id}: candidate-specific raw report already exists")
    executed_argv = _format_command(matrix_item, raw_path, report_suffix, cwd)
    executed_command = render_command_argv(executed_argv)
    started_at, finished_at, duration, exit_code = _run_shell(
        executed_argv, cwd, stdout_path, stderr_path
    )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(matrix_item, raw_path, report_suffix, cwd)
    if matrix_item["report_kind"] == "SUREFIRE_GLOB" and raw_reports:
        retained_dir = attempt_dir / "raw-surefire"
        retained_dir.mkdir()
        retained_reports: list[Path] = []
        for report in raw_reports:
            retained = retained_dir / report.name
            shutil.copy2(report, retained)
            retained_reports.append(retained)
        raw_reports = retained_reports
    record: dict[str, Any] = {
        "id": command_id,
        "candidate_commit": candidate,
        "cwd": matrix_item["cwd"],
        "resource_class": matrix_item["resource_class"].lower(),
        "expected_report_count": matrix_item["expected_report_count"],
        "selected_test_file_count": matrix_item["selected_test_file_count"],
        "minimum_tests": matrix_item["minimum_tests"],
        "matrix_command": matrix_item["command"],
        "matrix_command_sha256": hashlib.sha256(
            matrix_item["command"].encode("utf-8")
        ).hexdigest(),
        "executed_command": executed_command,
        "executed_argv": executed_argv,
        "executed_command_sha256": hashlib.sha256(executed_command.encode("utf-8")).hexdigest(),
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
        "raw_reports": [
            {"path": _relative(path, run_root), "sha256": _sha256(path)}
            for path in raw_reports
        ],
        "failure_classification": "NONE" if exit_code == 0 else "UNCLASSIFIED",
        "accepted": False,
    }
    if exit_code != 0:
        return record, False
    count_error = _report_count_error(matrix_item, raw_reports)
    if count_error:
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = count_error
        return record, False
    source_dir = run_root / "source"
    source_dir.mkdir(exist_ok=True)
    destination = source_dir / SOURCE_REPORTS[command_id]
    if destination.exists():
        raise EvidenceError(f"{command_id}: normalized source report already exists")
    try:
        report = normalize_source_reports(
            raw_reports,
            destination,
            candidate_commit=candidate,
            command_id=command_id,
        )
    except EvidenceError as exception:
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = f"JUnit normalization rejected: {exception}"
        destination.unlink(missing_ok=True)
        return record, False
    totals = report.totals
    minimum_tests = matrix_item.get("minimum_tests", 1)
    if (
        totals["tests"] < minimum_tests
        or totals["failures"]
        or totals["errors"]
        or totals["skipped"]
    ):
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = f"source JUnit is not all-pass zero-skip: {totals}"
        destination.unlink(missing_ok=True)
        return record, False
    record.update(
        {
            "accepted": True,
            "report": SOURCE_REPORTS[command_id],
            "report_path": _relative(destination, run_root),
            "report_sha256": _sha256(destination),
            "tests": totals["tests"],
            "failures": totals["failures"],
            "errors": totals["errors"],
            "skipped": totals["skipped"],
        }
    )
    return record, True


def execute_checkpoint(
    *,
    candidate_commit: str,
    run_root: Path,
    environment_id: str,
    resume: bool,
    classifications: Sequence[str],
) -> dict[str, Any]:
    matrix = load_matrix()
    blockers = gate_blockers(matrix)
    if blockers:
        raise EvidenceError(
            "Phase 7 entry execution is blocked before all source execution: "
            + ", ".join(blockers)
        )
    candidate = _assert_candidate(candidate_commit)
    _assert_upstream_authority(candidate)
    assert_contract_only_candidate(candidate, matrix)
    run_root = run_root.resolve()
    assert_candidate_run_directory(run_root)
    if resume:
        if not run_root.is_dir():
            raise EvidenceError(f"resume run directory does not exist: {run_root}")
        manifest = _load_resume_manifest(run_root, candidate)
    else:
        assert_clean_detached_candidate(candidate)
        if run_root.exists():
            raise EvidenceError(f"Phase 7 entry run directory already exists: {run_root}")
        run_root.mkdir(parents=True)
        manifest = _initial_manifest(
            candidate=candidate, environment_id=environment_id, run_root=run_root
        )
        _write_manifest(run_root / MANIFEST_NAME, manifest)
    _assert_candidate_unchanged(candidate, run_root)
    classification_map = _classification_map(classifications)
    if not _classify_pending_failure(manifest, classification_map):
        _write_manifest(run_root / MANIFEST_NAME, manifest)
        return manifest

    commands = _source_contracts(matrix)
    accepted = {item["id"] for item in manifest["commands"]}
    environment_sha256 = manifest["environment"]["snapshot_sha256"]
    for command_id in COMMAND_ORDER:
        if command_id in accepted:
            continue
        record, passed = _record_source(
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            matrix_item=commands[command_id],
            environment_sha256=environment_sha256,
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
    manifest["contract_gate"] = "P7.0_NOT_RUN"
    manifest["implementation_authorized"] = False
    manifest["MIG-006"] = "PENDING_PROMOTION"
    manifest["MIG-007"] = "PENDING_PROMOTION"
    _assert_candidate_unchanged(candidate, run_root)
    _validate_resume_manifest(manifest, run_root, candidate)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan or execute the four exact-SHA P7.0 source suites only after the "
            "authenticated Phase 6 checkpoint and ADR 0016 engineering permission."
        )
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id", default="local-phase7-entry")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--failure-classification",
        action="append",
        default=[],
        metavar="COMMAND_ID=CLASS",
        help="Only a classified same-SHA INFRA failure may resume.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if not arguments.execute:
            if arguments.resume or arguments.failure_classification or arguments.run_dir:
                raise EvidenceError(
                    "--run-dir, --resume and --failure-classification require --execute"
                )
            print(json.dumps(entry_plan(arguments.candidate_commit), indent=2))
            return 0
        if arguments.run_dir is None:
            raise EvidenceError("--run-dir is required with --execute")
        manifest = execute_checkpoint(
            candidate_commit=arguments.candidate_commit.strip().lower(),
            run_root=arguments.run_dir,
            environment_id=arguments.environment_id,
            resume=arguments.resume,
            classifications=arguments.failure_classification,
        )
    except (EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 7 entry execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                "contract_gate": manifest["contract_gate"],
                "implementation_authorized": False,
                "MIG-006": "PENDING_PROMOTION",
                "MIG-007": "PENDING_PROMOTION",
            },
            sort_keys=True,
        )
    )
    return 0 if manifest["status"] == GREEN_STATUS else 2


if __name__ == "__main__":
    raise SystemExit(main())
