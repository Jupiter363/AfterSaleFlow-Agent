from __future__ import annotations

import argparse
import copy
import fnmatch
import hashlib
import json
import re
import shutil
import subprocess
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Sequence

try:
    from scripts.generate_phase3_candidate_evidence import (
        ALLOWED_CHECK_STATUSES,
        EvidenceError,
        JUnitReport,
        TestCase,
        _assert_candidate,
        _assert_timestamp,
        _change_summary,
        _load_yaml,
        _sha256,
        _totals,
        _write_json,
        parse_junit,
        write_junit,
    )
except ModuleNotFoundError:  # Direct execution puts scripts/ on sys.path.
    from generate_phase3_candidate_evidence import (  # type: ignore[no-redef]
        ALLOWED_CHECK_STATUSES,
        EvidenceError,
        JUnitReport,
        TestCase,
        _assert_candidate,
        _assert_timestamp,
        _change_summary,
        _load_yaml,
        _sha256,
        _totals,
        _write_json,
        parse_junit,
        write_junit,
    )


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-4-intake-pilot-test-batches.yaml"
POLICY_PATH = (
    ROOT
    / "docs/runbooks/temporal-first/phase-4-engineering-evidence-policy.yaml"
)

SOURCE_REPORTS = {
    "python_phase_4": "python-phase4-junit.xml",
    "java_phase_4": "java-phase4-junit.xml",
    "frontend_phase_4": "frontend-phase4-junit.xml",
    "static_phase_4": "static-phase4-junit.xml",
}
DERIVED_REPORTS = {
    "P4-BATCH-0": "batch-0-junit.xml",
    "P4-BATCH-1": "batch-1-junit.xml",
    "P4-BATCH-2": "batch-2-junit.xml",
    "P4-BATCH-3": "batch-3-junit.xml",
}
EXPECTED_FILES = {
    "phase-metrics.json",
    "baseline-id-coverage.json",
    "check-id-coverage.json",
    "failure-classification.json",
    "external-gates.json",
    "candidate-commit.txt",
    *SOURCE_REPORTS.values(),
    *DERIVED_REPORTS.values(),
}

PYTEST_PATH_PATTERN = re.compile(r"(?<!\S)(tests/[A-Za-z0-9_./*?-]+)")
FRONTEND_PATH_PATTERN = re.compile(r"(?<!\S)(src/[A-Za-z0-9_./*?-]+\.test\.[cm]?[jt]sx?)")
JAVA_PLACEHOLDER = "<deduplicated_phase_4_intake_test_classes>"
PROMOTION_STATUSES = {
    "MIG-003": "PENDING_PROMOTION",
    "MIG-004": "PENDING_PROMOTION",
}


def load_matrix(path: Path = MATRIX_PATH) -> dict[str, Any]:
    matrix = _load_yaml(path)
    if matrix.get("schema_version") != "phase-test-batches.v1" or matrix.get("phase") != 4:
        raise EvidenceError(f"{path}: not the Phase 4 test matrix")
    batch = matrix.get("batches", {}).get("P4-BATCH-3", {})
    execution = batch.get("execution", {})
    if execution.get("strategy") != "deduplicated_source_suites_then_derived_batch_views":
        raise EvidenceError(f"{path}: unexpected Phase 4 evidence strategy")
    if set(execution.get("source_reports", [])) != set(SOURCE_REPORTS.values()):
        raise EvidenceError(f"{path}: source report contract drift")
    if set(execution.get("derived_views", [])) != set(DERIVED_REPORTS.values()):
        raise EvidenceError(f"{path}: derived report contract drift")
    required = set(batch.get("evidence", {}).get("required_files", []))
    if required != EXPECTED_FILES:
        raise EvidenceError(f"{path}: required Phase 4 evidence file set drift")
    return matrix


def _deduplicated_java_classes(matrix: dict[str, Any]) -> list[str]:
    values: list[str] = []
    seen: set[str] = set()
    for batch_id in ("P4-BATCH-1", "P4-BATCH-2"):
        for classname in matrix["batches"][batch_id]["java_test_classes"]:
            if classname not in seen:
                seen.add(classname)
                values.append(classname)
    if not values:
        raise EvidenceError("Phase 4 Java source suite has no test classes")
    return values


def focused_commands(matrix: dict[str, Any]) -> dict[str, dict[str, str]]:
    values = matrix["batches"]["P4-BATCH-3"]["source_commands"]
    commands = {item["id"]: dict(item) for item in values}
    if set(commands) != set(SOURCE_REPORTS):
        raise EvidenceError("P4-BATCH-3 source command IDs drifted")
    java = commands["java_phase_4"]
    template = java.get("command_template")
    if not isinstance(template, str) or JAVA_PLACEHOLDER not in template:
        raise EvidenceError("java_phase_4 command template lacks the class placeholder")
    java["command"] = template.replace(
        JAVA_PLACEHOLDER, ",".join(_deduplicated_java_classes(matrix))
    )
    for command_id, item in commands.items():
        if command_id != "java_phase_4" and not isinstance(item.get("command"), str):
            raise EvidenceError(f"{command_id} has no source command")
    return commands


def _path_classname(selector: str, *, frontend: bool = False) -> str:
    normalized = selector.replace("\\", "/").removeprefix("./")
    normalized = normalized.removeprefix("python-agent-service/")
    if frontend:
        normalized = normalized.removeprefix("frontend/")
        return normalized
    if normalized.endswith(".py"):
        normalized = normalized[:-3]
    return normalized.strip("/").replace("/", ".")


def _select_path_cases(
    report: JUnitReport,
    selectors: Sequence[str],
    *,
    context: str,
    frontend: bool = False,
) -> tuple[TestCase, ...]:
    selected: dict[tuple[str, str, str], TestCase] = {}
    missing: list[str] = []
    for selector in selectors:
        prefix = _path_classname(selector, frontend=frontend)
        matches = [
            case
            for case in report.cases
            if case.classname == prefix
            or (not frontend and case.classname.startswith(f"{prefix}."))
        ]
        if not matches:
            missing.append(selector)
        for case in matches:
            selected[case.identity] = case
    if missing:
        raise EvidenceError(f"{context}: selectors did not run: {', '.join(missing)}")
    return tuple(selected.values())


def _select_java_cases(
    report: JUnitReport, classes: Sequence[str], *, context: str
) -> tuple[TestCase, ...]:
    expected = set(classes)
    observed: dict[str, list[TestCase]] = {}
    for case in report.cases:
        observed.setdefault(case.classname.rsplit(".", 1)[-1], []).append(case)
    missing = sorted(expected - set(observed))
    if missing:
        raise EvidenceError(f"{context}: requested test classes did not run: {', '.join(missing)}")
    return tuple(case for classname in classes for case in observed[classname])


def _assert_source_scope(
    command_id: str,
    report: JUnitReport,
    matrix: dict[str, Any],
) -> None:
    command = focused_commands(matrix)[command_id]["command"]
    if command_id in {"python_phase_4", "static_phase_4"}:
        selectors = PYTEST_PATH_PATTERN.findall(command)
        if not selectors:
            raise EvidenceError(f"{command_id} command has no Pytest selectors")
        _select_path_cases(report, selectors, context=command_id)
    elif command_id == "frontend_phase_4":
        selectors = FRONTEND_PATH_PATTERN.findall(command)
        if not selectors:
            raise EvidenceError("frontend_phase_4 command has no Vitest selectors")
        _select_path_cases(report, selectors, context=command_id, frontend=True)
    else:
        _select_java_cases(
            report,
            _deduplicated_java_classes(matrix),
            context="java_phase_4",
        )


def _without_output_nodes(case: TestCase) -> TestCase:
    element = copy.deepcopy(case.element)
    removed = False
    for child in list(element):
        if child.tag.rsplit("}", 1)[-1] in {"system-out", "system-err"}:
            element.remove(child)
            removed = True
    if not removed:
        return case
    return TestCase(
        source=case.source,
        suite=case.suite,
        classname=case.classname,
        name=case.name,
        duration=case.duration,
        status=case.status,
        element=element,
    )


def consume_source_reports(
    *,
    source_dir: Path,
    output_dir: Path,
    candidate_commit: str,
    matrix: dict[str, Any],
) -> dict[str, JUnitReport]:
    if source_dir.resolve() == output_dir.resolve():
        raise EvidenceError("source and output directories must be different")
    if not source_dir.is_dir():
        raise EvidenceError(f"source report directory does not exist: {source_dir}")
    source_files = {path.name for path in source_dir.iterdir() if path.is_file()}
    if source_files != set(SOURCE_REPORTS.values()):
        raise EvidenceError(
            "source report file set mismatch: "
            f"missing={sorted(set(SOURCE_REPORTS.values()) - source_files)}, "
            f"unexpected={sorted(source_files - set(SOURCE_REPORTS.values()))}"
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    reports: dict[str, JUnitReport] = {}
    for command_id, filename in SOURCE_REPORTS.items():
        source_path = source_dir / filename
        report = parse_junit(source_path)
        if report.candidate_commit != candidate_commit:
            raise EvidenceError(
                f"{filename}: candidate binding {report.candidate_commit!r} "
                f"does not match {candidate_commit}"
            )
        if report.command_id != command_id:
            raise EvidenceError(
                f"{filename}: command binding {report.command_id!r} "
                f"does not match {command_id}"
            )
        totals = report.totals
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise EvidenceError(
                f"{command_id} is not an all-pass, zero-skip source report: {totals}"
            )
        _assert_source_scope(command_id, report, matrix)
        reports[command_id] = write_junit(
            output_dir / filename,
            name=filename.removesuffix(".xml"),
            cases=[_without_output_nodes(case) for case in report.cases],
            candidate_commit=candidate_commit,
            command_id=command_id,
        )
    return reports


def _batch_cases(
    batch_id: str,
    matrix: dict[str, Any],
    reports: dict[str, JUnitReport],
) -> tuple[TestCase, ...]:
    batch = matrix["batches"][batch_id]
    cases: list[TestCase] = []
    if batch_id == "P4-BATCH-0":
        suites = batch["baseline_suites"]
        cases.extend(
            _select_path_cases(
                reports["python_phase_4"],
                suites["python"],
                context=f"{batch_id} python baseline",
            )
        )
        cases.extend(
            _select_java_cases(
                reports["java_phase_4"],
                suites["java"],
                context=f"{batch_id} Java baseline",
            )
        )
        cases.extend(
            _select_path_cases(
                reports["frontend_phase_4"],
                suites["frontend"],
                context=f"{batch_id} frontend baseline",
                frontend=True,
            )
        )
    else:
        if batch.get("python_tests"):
            cases.extend(
                _select_path_cases(
                    reports["python_phase_4"],
                    batch["python_tests"],
                    context=f"{batch_id} python tests",
                )
            )
        if batch.get("java_test_classes"):
            cases.extend(
                _select_java_cases(
                    reports["java_phase_4"],
                    batch["java_test_classes"],
                    context=f"{batch_id} Java tests",
                )
            )
        if batch.get("frontend_tests"):
            cases.extend(
                _select_path_cases(
                    reports["frontend_phase_4"],
                    batch["frontend_tests"],
                    context=f"{batch_id} frontend tests",
                    frontend=True,
                )
            )
    if batch.get("static_tests"):
        cases.extend(
            _select_path_cases(
                reports["static_phase_4"],
                batch["static_tests"],
                context=f"{batch_id} static tests",
            )
        )
    identities = [case.identity for case in cases]
    if len(identities) != len(set(identities)):
        raise EvidenceError(f"{batch_id}: derived view contains duplicate testcases")
    return tuple(cases)


def write_derived_reports(
    *,
    matrix: dict[str, Any],
    reports: dict[str, JUnitReport],
    output_dir: Path,
    candidate_commit: str,
) -> dict[str, JUnitReport]:
    source_hashes = {
        report.path.name: _sha256(report.path) for report in reports.values()
    }
    derived: dict[str, JUnitReport] = {}
    for batch_id, filename in DERIVED_REPORTS.items():
        if batch_id == "P4-BATCH-3":
            cases = tuple(
                case
                for command_id in SOURCE_REPORTS
                for case in reports[command_id].cases
            )
        else:
            cases = _batch_cases(batch_id, matrix, reports)
        identities = [case.identity for case in cases]
        if len(identities) != len(set(identities)):
            raise EvidenceError(f"{batch_id}: derived view contains duplicate testcases")
        derived[batch_id] = write_junit(
            output_dir / filename,
            name=filename.removesuffix(".xml"),
            cases=cases,
            candidate_commit=candidate_commit,
            source_hashes=source_hashes,
        )
    return derived


def _flatten_ids(groups: dict[str, Sequence[str]], label: str) -> set[str]:
    values = [item for group in groups.values() for item in group]
    duplicates = sorted(item for item, count in Counter(values).items() if count > 1)
    if duplicates:
        raise EvidenceError(f"{label} contains duplicate IDs: {', '.join(duplicates)}")
    return set(values)


def _check_owners(matrix: dict[str, Any]) -> dict[str, str]:
    owners: dict[str, str] = {}
    for owner, agent in matrix["agents"].items():
        for check_id in agent.get("primary_check_ids", []):
            if check_id in owners:
                raise EvidenceError(f"check ID {check_id} has multiple owners")
            owners[check_id] = owner
    expected = _flatten_ids(matrix["check_ids"], "Phase 4 check groups")
    if set(owners) != expected:
        raise EvidenceError(
            "Phase 4 check ownership mismatch: "
            f"missing={sorted(expected - set(owners))}, "
            f"unexpected={sorted(set(owners) - expected)}"
        )
    return owners


def _baseline_owners(matrix: dict[str, Any]) -> dict[str, list[str]]:
    expected = _flatten_ids(matrix["baseline_ids"], "Phase 4 baseline groups")
    owners = {
        baseline_id: sorted(
            owner
            for owner, agent in matrix["agents"].items()
            if baseline_id in agent.get("supporting_baseline_ids", [])
        )
        for baseline_id in expected
    }
    missing = sorted(item for item, values in owners.items() if not values)
    if missing:
        raise EvidenceError("baseline IDs have no supporting owner: " + ", ".join(missing))
    return owners


def _selector_matches(report_name: str, case: TestCase, selector: str) -> bool:
    class_pattern, separator, name_pattern = selector.partition("#")
    if not separator or not class_pattern or not name_pattern:
        raise EvidenceError(f"invalid evidence selector {selector!r}")
    if report_name == "frontend-phase4-junit.xml":
        class_pattern = class_pattern.removeprefix("frontend/")
    return fnmatch.fnmatch(case.classname, class_pattern) and fnmatch.fnmatch(
        case.name, name_pattern
    )


def _resolve_mapping(
    *,
    item_id: str,
    mapping: dict[str, Any],
    reports_by_name: dict[str, JUnitReport],
) -> dict[str, Any]:
    status = mapping.get("status")
    evidence_names = mapping.get("evidence")
    selectors = mapping.get("test_selectors")
    note = mapping.get("note")
    if status not in ALLOWED_CHECK_STATUSES:
        raise EvidenceError(f"{item_id}: unsupported status {status!r}")
    if not isinstance(evidence_names, list) or not evidence_names:
        raise EvidenceError(f"{item_id}: evidence must name source reports")
    if any(name not in reports_by_name for name in evidence_names):
        raise EvidenceError(f"{item_id}: evidence must name source reports only")
    if not isinstance(selectors, list) or not selectors:
        raise EvidenceError(f"{item_id}: explicit test selectors are required")
    if status != "PASS_ENGINEERING" and not note:
        raise EvidenceError(f"{item_id}: {status} requires a note")

    bindings = []
    for selector in selectors:
        matches = [
            {"report": report_name, "testcase": case.node_id}
            for report_name in evidence_names
            for case in reports_by_name[report_name].cases
            if _selector_matches(report_name, case, selector)
        ]
        if not matches:
            raise EvidenceError(f"{item_id}: evidence selector did not run: {selector}")
        bindings.append({"selector": selector, "matches": matches})
    result: dict[str, Any] = {
        "id": item_id,
        "status": status,
        "evidence": evidence_names,
        "test_selectors": selectors,
        "bindings": bindings,
    }
    if note:
        result["note"] = note
    return result


def _status_summary(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
    values = tuple(rows)
    counts = Counter(row["status"] for row in values)
    return {
        **{status: counts[status] for status in sorted(ALLOWED_CHECK_STATUSES)},
        "total": len(values),
    }


def build_check_coverage(
    *,
    matrix: dict[str, Any],
    policy: dict[str, Any],
    candidate_commit: str,
    reports: dict[str, JUnitReport],
) -> dict[str, Any]:
    if policy.get("schema_version") != "phase4-engineering-evidence-policy.v1":
        raise EvidenceError(f"{POLICY_PATH}: unsupported schema_version")
    owners = _check_owners(matrix)
    overrides = policy.get("overrides", {})
    if set(overrides) != set(owners):
        raise EvidenceError(
            "Phase 4 check mappings mismatch: "
            f"missing={sorted(set(owners) - set(overrides))}, "
            f"unexpected={sorted(set(overrides) - set(owners))}"
        )
    reports_by_name = {report.path.name: report for report in reports.values()}
    rows = []
    for check_id in sorted(owners):
        prefix = check_id.split("-", 1)[0]
        default = policy.get("defaults", {}).get(prefix)
        if not isinstance(default, dict):
            raise EvidenceError(f"evidence policy has no default for {prefix}")
        row = _resolve_mapping(
            item_id=check_id,
            mapping={**default, **overrides[check_id]},
            reports_by_name=reports_by_name,
        )
        row["owner"] = owners[check_id]
        if check_id == "MIG-004" and row["status"] != "PENDING_PROMOTION":
            raise EvidenceError("MIG-004 must remain PENDING_PROMOTION")
        rows.append(row)
    return {
        "schema_version": "temporal-first-check-id-coverage.v1",
        "phase": 4,
        "candidate_commit": candidate_commit,
        "scope": policy["scope"],
        "summary": _status_summary(rows),
        "checks": rows,
    }


def build_baseline_coverage(
    *,
    matrix: dict[str, Any],
    policy: dict[str, Any],
    candidate_commit: str,
    reports: dict[str, JUnitReport],
) -> dict[str, Any]:
    owners = _baseline_owners(matrix)
    overrides = policy.get("baseline_overrides", {})
    if set(overrides) != set(owners):
        raise EvidenceError(
            "Phase 4 baseline mappings mismatch: "
            f"missing={sorted(set(owners) - set(overrides))}, "
            f"unexpected={sorted(set(overrides) - set(owners))}"
        )
    reports_by_name = {report.path.name: report for report in reports.values()}
    rows = []
    for baseline_id in sorted(owners):
        row = _resolve_mapping(
            item_id=baseline_id,
            mapping=overrides[baseline_id],
            reports_by_name=reports_by_name,
        )
        row["supporting_owners"] = owners[baseline_id]
        rows.append(row)
    return {
        "schema_version": "temporal-first-baseline-id-coverage.v1",
        "phase": 4,
        "candidate_commit": candidate_commit,
        "scope": policy["scope"],
        "summary": _status_summary(rows),
        "baselines": rows,
    }


def build_external_gates(
    *, policy: dict[str, Any], candidate_commit: str
) -> dict[str, Any]:
    if policy.get("promotion_gates") != {
        "MIG-003": {
            "status": "PENDING_PROMOTION",
            "note": "Phase 3 production promotion remains independently gated.",
        },
        "MIG-004": {
            "status": "PENDING_PROMOTION",
            "depends_on": ["MIG-003"],
            "note": "Phase 4 engineering PASS never implies Intake promotion PASS.",
        },
    }:
        raise EvidenceError("Phase 4 promotion gates must preserve MIG-003 and MIG-004")
    gates = policy.get("external_gates")
    if not isinstance(gates, list) or not gates:
        raise EvidenceError("Phase 4 external gates are missing")
    for gate in gates:
        if gate.get("status") != "PENDING_EXTERNAL" or not gate.get("note"):
            raise EvidenceError(f"external gate {gate.get('id')!r} must remain documented and pending")
    runtime = {
        "runtime_modes_allowed": policy.get("runtime_modes_allowed"),
        "formal_writer_allowed": policy.get("formal_writer_allowed"),
        "real_case_shadow_allowed": policy.get("real_case_shadow_allowed"),
        "temporal_intake_allocation_allowed": policy.get(
            "temporal_intake_allocation_allowed"
        ),
        "canary_allowed": policy.get("canary_allowed"),
    }
    expected_runtime = {
        "runtime_modes_allowed": ["DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
        "formal_writer_allowed": False,
        "real_case_shadow_allowed": False,
        "temporal_intake_allocation_allowed": False,
        "canary_allowed": False,
    }
    if runtime != expected_runtime:
        raise EvidenceError("Phase 4 runtime or traffic restrictions were relaxed")
    return {
        "schema_version": "temporal-first-external-gates.v1",
        "phase": 4,
        "candidate_commit": candidate_commit,
        "promotion_gate": "PENDING",
        "promotion_gates": policy["promotion_gates"],
        "runtime_restrictions": runtime,
        "external_gates": gates,
    }


def build_failure_classification(
    *, matrix: dict[str, Any], policy: dict[str, Any], candidate_commit: str
) -> dict[str, Any]:
    matrix_policy = matrix.get("failure_classification", {})
    required = matrix_policy.get("required_values")
    classifications = policy.get("failure_classification")
    if required != ["PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"]:
        raise EvidenceError("Phase 4 failure classification values drifted")
    if not isinstance(classifications, dict) or set(classifications) != set(required):
        raise EvidenceError("Phase 4 evidence policy lacks exact failure classifications")
    return {
        "schema_version": "temporal-first-failure-classification.v1",
        "phase": 4,
        "candidate_commit": candidate_commit,
        "classifications": classifications,
        "accepted_source_suite_failures": [],
        "open_product_failures": [],
        "quarantined_attempts_reused": False,
        "decision": {
            "engineering_checkpoint": "PASS",
            "promotion_gate": "PENDING",
            "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
            **PROMOTION_STATUSES,
        },
    }


def build_phase_metrics(
    *,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    engineering_started_at: str,
    verification_started_at: str,
    verification_finished_at: str,
    matrix: dict[str, Any],
    reports: dict[str, JUnitReport],
    derived: dict[str, JUnitReport],
) -> dict[str, Any]:
    commands = focused_commands(matrix)
    source_entries = []
    all_cases: list[TestCase] = []
    counts: dict[str, int] = {}
    for command_id, filename in SOURCE_REPORTS.items():
        report = reports[command_id]
        totals = report.totals
        all_cases.extend(report.cases)
        counts[command_id] = int(totals["tests"])
        command = commands[command_id]["command"]
        source_entries.append(
            {
                "name": filename,
                "command_id": command_id,
                "matrix_command_sha256": hashlib.sha256(command.encode("utf-8")).hexdigest(),
                **{
                    field: totals[field]
                    for field in ("tests", "failures", "errors", "skipped")
                },
                "sha256": _sha256(report.path),
            }
        )
    identities = [case.identity for case in all_cases]
    if len(identities) != len(set(identities)):
        raise EvidenceError("source reports contain duplicate cross-source testcase identities")
    totals = _totals(all_cases)
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise EvidenceError("cannot create PASS metrics from non-green source reports")

    batch_entries = []
    for batch_id, filename in DERIVED_REPORTS.items():
        report = derived[batch_id]
        batch_totals = report.totals
        batch_entries.append(
            {
                "id": batch_id,
                **{
                    field: batch_totals[field]
                    for field in ("tests", "failures", "errors", "skipped")
                },
                "report": filename,
                "sha256": _sha256(report.path),
            }
        )
    started_text = _assert_timestamp(verification_started_at, "verification_started_at")
    finished_text = _assert_timestamp(verification_finished_at, "verification_finished_at")
    started = datetime.fromisoformat(started_text.replace("Z", "+00:00"))
    finished = datetime.fromisoformat(finished_text.replace("Z", "+00:00"))
    if finished < started:
        raise EvidenceError("verification_finished_at predates verification_started_at")
    return {
        "schema_version": "temporal-first-phase-metrics.v1",
        "release_id": release_id,
        "phase": 4,
        "name": "intake-pilot-engineering-shadow",
        "scope": "SIGNED_SYNTHETIC_SHADOW_ENGINEERING_ONLY",
        "base_commit": _assert_candidate(base_commit, "base commit"),
        "candidate_commit": candidate_commit,
        "engineering_started_at": _assert_timestamp(
            engineering_started_at, "engineering_started_at"
        ),
        "verification_started_at": started_text,
        "verification_finished_at": finished_text,
        "verification_wall_clock_seconds": round((finished - started).total_seconds(), 3),
        "change_summary": _change_summary(base_commit, candidate_commit),
        "candidate_verification": {
            "source_execution_mode": "PREGENERATED_CANDIDATE_BOUND_JUNIT",
            "deduplicated_execution": True,
            "mixed_candidate_results": False,
            "quarantined_attempts_reused": False,
            "distinct_tests": totals["tests"],
            "python_tests": counts["python_phase_4"],
            "java_tests": counts["java_phase_4"],
            "frontend_tests": counts["frontend_phase_4"],
            "static_tests": counts["static_phase_4"],
            "failures": totals["failures"],
            "errors": totals["errors"],
            "skipped": totals["skipped"],
        },
        "source_reports": source_entries,
        "batch_views": batch_entries,
        "status": {
            "engineering_checkpoint": "PASS",
            "promotion_gate": "PENDING",
            "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
            **PROMOTION_STATUSES,
        },
    }


def assemble_evidence(
    *,
    matrix: dict[str, Any],
    policy: dict[str, Any],
    source_dir: Path,
    output_dir: Path,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    engineering_started_at: str,
    verification_started_at: str,
    verification_finished_at: str,
) -> dict[str, Any]:
    candidate_commit = _assert_candidate(candidate_commit)
    output_dir.mkdir(parents=True, exist_ok=False)
    reports = consume_source_reports(
        source_dir=source_dir,
        output_dir=output_dir,
        candidate_commit=candidate_commit,
        matrix=matrix,
    )
    derived = write_derived_reports(
        matrix=matrix,
        reports=reports,
        output_dir=output_dir,
        candidate_commit=candidate_commit,
    )
    check_coverage = build_check_coverage(
        matrix=matrix,
        policy=policy,
        candidate_commit=candidate_commit,
        reports=reports,
    )
    baseline_coverage = build_baseline_coverage(
        matrix=matrix,
        policy=policy,
        candidate_commit=candidate_commit,
        reports=reports,
    )
    external_gates = build_external_gates(
        policy=policy, candidate_commit=candidate_commit
    )
    failures = build_failure_classification(
        matrix=matrix,
        policy=policy,
        candidate_commit=candidate_commit,
    )
    metrics = build_phase_metrics(
        release_id=release_id,
        base_commit=base_commit,
        candidate_commit=candidate_commit,
        engineering_started_at=engineering_started_at,
        verification_started_at=verification_started_at,
        verification_finished_at=verification_finished_at,
        matrix=matrix,
        reports=reports,
        derived=derived,
    )
    _write_json(output_dir / "phase-metrics.json", metrics)
    _write_json(output_dir / "baseline-id-coverage.json", baseline_coverage)
    _write_json(output_dir / "check-id-coverage.json", check_coverage)
    _write_json(output_dir / "failure-classification.json", failures)
    _write_json(output_dir / "external-gates.json", external_gates)
    (output_dir / "candidate-commit.txt").write_text(
        candidate_commit + "\n", encoding="utf-8"
    )
    required = set(
        matrix["batches"]["P4-BATCH-3"]["evidence"]["required_files"]
    )
    actual = {path.name for path in output_dir.iterdir() if path.is_file()}
    if actual != required:
        raise EvidenceError(
            f"Phase 4 evidence file set mismatch: missing={sorted(required - actual)}, "
            f"unexpected={sorted(actual - required)}"
        )
    return metrics


def _git_result(*arguments: str, repository: Path = ROOT) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def assert_clean_detached_candidate(
    candidate_commit: str,
    repository: Path = ROOT,
    *,
    allowed_untracked_root: Path | None = None,
) -> None:
    expected = _assert_candidate(candidate_commit)
    head = _git_result("rev-parse", "HEAD", repository=repository)
    if head.returncode:
        raise EvidenceError(f"cannot resolve candidate HEAD: {head.stderr.strip()}")
    actual = _assert_candidate(head.stdout.strip(), "candidate HEAD")
    if actual != expected:
        raise EvidenceError(f"candidate HEAD {actual} does not match fixed SHA {expected}")
    status = _git_result(
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
        repository=repository,
    )
    if status.returncode:
        raise EvidenceError(f"cannot inspect candidate worktree: {status.stderr.strip()}")
    entries = [entry for entry in status.stdout.split("\0") if entry]
    unexpected: list[str] = []
    allowed_root = allowed_untracked_root.resolve() if allowed_untracked_root else None
    for entry in entries:
        if not entry.startswith("?? ") or allowed_root is None:
            unexpected.append(entry)
            continue
        path = (repository / entry[3:]).resolve()
        if not path.is_relative_to(allowed_root):
            unexpected.append(entry)
    if unexpected:
        raise EvidenceError(
            "candidate repository is not clean:\n" + "\n".join(unexpected)
        )
    symbolic = _git_result("symbolic-ref", "-q", "HEAD", repository=repository)
    if symbolic.returncode == 0:
        raise EvidenceError("candidate worktree must be detached")
    if symbolic.returncode != 1:
        raise EvidenceError(f"cannot authenticate detached HEAD: {symbolic.stderr.strip()}")


def assert_base_ancestor(
    base_commit: str, candidate_commit: str, repository: Path = ROOT
) -> None:
    base = _assert_candidate(base_commit, "base commit")
    process = _git_result(
        "merge-base", "--is-ancestor", base, candidate_commit, repository=repository
    )
    if process.returncode:
        raise EvidenceError(
            f"base commit {base} is not an ancestor of candidate {candidate_commit}"
        )


def _release_id(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,79}", value):
        raise argparse.ArgumentTypeError(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Assemble commit-bound Phase 4 engineering evidence from four existing "
            "candidate-bound JUnit reports."
        )
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--engineering-started-at", required=True)
    parser.add_argument("--verification-started-at", required=True)
    parser.add_argument("--verification-finished-at", required=True)
    parser.add_argument(
        "--source-dir",
        required=True,
        type=Path,
        help="Directory containing the four candidate-bound P4-BATCH-3 source reports.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Defaults to test-reports/temporal-first/<release-id>/phase-4.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    candidate_commit = arguments.candidate_commit.strip().lower()
    output_dir = (
        arguments.output_dir
        or ROOT / "test-reports/temporal-first" / arguments.release_id / "phase-4"
    ).resolve()
    staging = output_dir.with_name(f".{output_dir.name}.assembling")
    try:
        matrix = load_matrix()
        policy = _load_yaml(POLICY_PATH)
        assert_clean_detached_candidate(candidate_commit)
        assert_base_ancestor(arguments.base_commit, candidate_commit)
        if output_dir.exists() or staging.exists():
            raise EvidenceError(f"evidence output or staging path already exists: {output_dir}")
        metrics = assemble_evidence(
            matrix=matrix,
            policy=policy,
            source_dir=arguments.source_dir.resolve(),
            output_dir=staging,
            release_id=arguments.release_id,
            base_commit=arguments.base_commit,
            candidate_commit=candidate_commit,
            engineering_started_at=arguments.engineering_started_at,
            verification_started_at=arguments.verification_started_at,
            verification_finished_at=arguments.verification_finished_at,
        )
        assert_clean_detached_candidate(
            candidate_commit, allowed_untracked_root=staging
        )
        staging.rename(output_dir)
    except (EvidenceError, OSError) as exception:
        if staging.exists():
            shutil.rmtree(staging)
        print(f"Phase 4 evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": metrics["candidate_commit"],
                "engineering_checkpoint": "PASS",
                "promotion_gate": "PENDING",
                "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
                "evidence_dir": str(output_dir),
                **PROMOTION_STATUSES,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
