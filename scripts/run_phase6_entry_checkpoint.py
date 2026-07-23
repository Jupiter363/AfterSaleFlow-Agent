from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
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
        assert_candidate_run_directory,
        assert_clean_detached_candidate,
        render_command_argv,
        seal_execution_manifest,
    )
    from scripts.run_phase4_candidate_checkpoint import (
        _run_shell,
        capture_environment,
    )
except ModuleNotFoundError:  # Direct script execution places scripts/ on sys.path.
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
        assert_candidate_run_directory,
        assert_clean_detached_candidate,
        render_command_argv,
        seal_execution_manifest,
    )
    from run_phase4_candidate_checkpoint import (  # type: ignore[no-redef]
        _run_shell,
        capture_environment,
    )


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-6-hearing-pilot-test-batches.yaml"
MANIFEST_NAME = "phase6-entry-execution-manifest.json"
SCHEMA_VERSION = "phase6-entry-execution-manifest.v1"
GREEN_STATUS = "SOURCE_SUITES_GREEN_AWAITING_SEPARATE_ENTRY_EVIDENCE"
COMMAND_ORDER = (
    "static_phase6_entry",
    "python_phase6_entry",
    "java_phase6_entry",
    "frontend_phase6_entry",
)
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def load_matrix() -> dict[str, Any]:
    matrix = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    if matrix.get("phase") != 6:
        raise EvidenceError("Phase 6 entry runner loaded a non-Phase-6 matrix")
    return matrix


def _entry_batch(matrix: dict[str, Any]) -> dict[str, Any]:
    try:
        return matrix["batches"]["batch_0_entry"]
    except (KeyError, TypeError) as exception:
        raise EvidenceError(
            "Phase 6 matrix has no Batch 0 entry contract"
        ) from exception


def _source_contracts(matrix: dict[str, Any]) -> dict[str, dict[str, Any]]:
    batch = _entry_batch(matrix)
    reports = batch.get("source_reports")
    commands = batch.get("source_commands")
    if not isinstance(reports, dict) or tuple(reports) != COMMAND_ORDER:
        raise EvidenceError("Phase 6 source report order or IDs drifted")
    if not isinstance(commands, list) or [
        item.get("id") for item in commands if isinstance(item, dict)
    ] != list(COMMAND_ORDER):
        raise EvidenceError("Phase 6 source command order or IDs drifted")
    result: dict[str, dict[str, Any]] = {}
    for item in commands:
        command_id = item["id"]
        if item.get("report") != reports[command_id]:
            raise EvidenceError(f"{command_id}: command/report mapping drifted")
        if not isinstance(item.get("cwd"), str) or not isinstance(
            item.get("command"), str
        ):
            raise EvidenceError(f"{command_id}: source contract is incomplete")
        result[command_id] = dict(item)
    return result


def _source_reports(matrix: dict[str, Any]) -> dict[str, str]:
    _source_contracts(matrix)
    return dict(_entry_batch(matrix)["source_reports"])


SOURCE_REPORTS = _source_reports(load_matrix())


def gate_blockers(matrix: dict[str, Any]) -> list[str]:
    batch = _entry_batch(matrix)
    execution_gate = batch.get("execution_gate", {})
    observed = matrix.get("gate", {}).get("observed_entry_state", {})
    blockers: list[str] = []
    checkpoint = observed.get("phase_5_engineering_checkpoint")
    phase6_exception = observed.get("phase_6_engineering_exception")
    phase5 = execution_gate.get("phase_5_authority", {})
    if checkpoint != phase5.get("checkpoint"):
        blockers.append("PHASE_5_ENGINEERING_CHECKPOINT_NOT_AUTHENTICATED")
    if phase6_exception != phase5.get("phase_6_exception"):
        blockers.append("ADR_0015_ENGINEERING_EXCEPTION_NOT_AUTHENTICATED")
    accepted_checkpoint = matrix.get("gate", {}).get(
        "accepted_phase_5_checkpoint_sha"
    )
    if accepted_checkpoint != phase5.get("accepted_checkpoint_commit"):
        blockers.append("PHASE_5_ACCEPTANCE_COMMIT_NOT_AUTHENTICATED")
    if observed.get("next_phase_permission") != execution_gate.get(
        "required_next_phase_permission"
    ):
        blockers.append("PHASE_6_ENGINEERING_ONLY_PERMISSION_NOT_AUTHENTICATED")
    if matrix.get("gate", {}).get("entry_decision") != execution_gate.get(
        "required_entry_decision"
    ):
        blockers.append("P6_CONTRACT_CANDIDATE_NOT_READY")
    if batch.get("status") != execution_gate.get("required_batch_status"):
        blockers.append("P6_BATCH_0_NOT_READY")
    constraints = matrix.get("gate", {}).get("traffic_constraints", {})
    if constraints.get("formal_hearing_graph_sink_allowed") is not False:
        blockers.append("FORMAL_HEARING_GRAPH_SINK_MUST_REMAIN_FORBIDDEN")
    if constraints.get("temporal_hearing_allocation_allowed") is not False:
        blockers.append("TEMPORAL_HEARING_ALLOCATION_MUST_REMAIN_FORBIDDEN")
    if constraints.get("real_case_shadow_allowed") is not False:
        blockers.append("REAL_CASE_SHADOW_MUST_REMAIN_FORBIDDEN")
    return blockers


def entry_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    matrix = load_matrix()
    commands = _source_contracts(matrix)
    blockers = gate_blockers(matrix)
    return {
        "schema_version": "phase6-entry-run-plan.v1",
        "phase": 6,
        "candidate_commit": candidate,
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
            }
            for command_id in COMMAND_ORDER
        ],
        "runtime_restrictions": {
            "real_case_data": "forbidden",
            "real_case_shadow": "forbidden",
            "temporal_hearing_allocation": "forbidden",
            "formal_hearing_graph_sink": "forbidden",
            "canary_or_promotion": "forbidden",
        },
        "green_result_ceiling": GREEN_STATUS,
        "contract_gate": "P6.0_NOT_RUN",
        "implementation_authorized": False,
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "MIG-006": "PENDING_PROMOTION",
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
        "phase": 6,
        "candidate_commit": candidate,
        "attempt_id": run_root.name,
        "status": "RUNNING",
        "contract_gate": "P6.0_NOT_RUN",
        "implementation_authorized": False,
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "MIG-006": "PENDING_PROMOTION",
        "verification_started_at": _utc_now(),
        "verification_finished_at": None,
        "environment": capture_environment(environment_id),
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
        candidate,
        allowed_untracked_root=_allowed_untracked_root(run_root),
    )


def _validate_resume_manifest(
    manifest: dict[str, Any], run_root: Path, candidate: str
) -> None:
    commands = manifest.get("commands")
    if not isinstance(commands, list):
        raise EvidenceError("resume manifest commands are invalid")
    ids = [item.get("id") for item in commands if isinstance(item, dict)]
    if ids != list(COMMAND_ORDER[: len(ids)]):
        raise EvidenceError("resume commands are not the ordered source prefix")
    for record in commands:
        command_id = record.get("id")
        if command_id not in SOURCE_REPORTS:
            raise EvidenceError("resume record names an unknown source")
        if (
            record.get("candidate_commit") != candidate
            or record.get("accepted") is not True
        ):
            raise EvidenceError("resume record binding drifted")
        report_path = record.get("report_path")
        report_sha256 = record.get("report_sha256")
        if not isinstance(report_path, str) or not isinstance(report_sha256, str):
            raise EvidenceError("resume accepted report binding is incomplete")
        path = _assert_bound_artifact(
            run_root, report_path, report_sha256, "resume accepted report"
        )
        report = parse_junit(path)
        if report.candidate_commit != candidate or report.command_id != command_id:
            raise EvidenceError(
                "resume accepted JUnit candidate/command binding drifted"
            )
        totals = report.totals
        if (
            not totals["tests"]
            or totals["failures"]
            or totals["errors"]
            or totals["skipped"]
        ):
            raise EvidenceError("resume accepted JUnit is not all-pass zero-skip")
    if manifest.get("quarantined_attempts_reused") is not False:
        raise EvidenceError("resume manifest reused quarantined attempts")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise EvidenceError("resume quarantined attempts are invalid")
    for record in quarantined:
        if (
            record.get("candidate_commit") != candidate
            or record.get("failure_classification") != "INFRA"
        ):
            raise EvidenceError("only same-SHA INFRA attempts may be quarantined")
        if record.get("id") not in SOURCE_REPORTS:
            raise EvidenceError("quarantined INFRA attempt names an unknown source")
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


def _load_resume_manifest(run_root: Path, candidate: str) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(
            f"cannot resume Phase 6 manifest {path}: {exception}"
        ) from exception
    if manifest.get("schema_version") != SCHEMA_VERSION or manifest.get("phase") != 6:
        raise EvidenceError("resume manifest is not a Phase 6 entry execution manifest")
    _assert_execution_manifest_seal(manifest)
    if manifest.get("candidate_commit") != candidate:
        raise EvidenceError("resume manifest belongs to another candidate SHA")
    if manifest.get("status") == GREEN_STATUS:
        raise EvidenceError("Phase 6 entry source suites are already green")
    if manifest.get("status") == "CANDIDATE_BLOCKED":
        raise EvidenceError("classified failure blocked this Phase 6 candidate")
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
    item: dict[str, Any], raw_path: Path, report_suffix: str
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
    return rendered


def _raw_reports(
    item: dict[str, Any], raw_path: Path, report_suffix: str, cwd: Path
) -> list[Path]:
    if item["report_kind"] != "SUREFIRE_GLOB":
        return [raw_path] if raw_path.is_file() else []
    pattern = item.get("raw_report_glob")
    if not isinstance(pattern, str):
        raise EvidenceError(f"{item['id']}: Surefire report glob is missing")
    rendered = pattern.format(report_suffix=report_suffix)
    return sorted(cwd.glob(rendered))


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
        f"p6-{candidate[:12]}-"
        f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    cwd = (ROOT / matrix_item["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise EvidenceError(f"{command_id}: matrix cwd escapes the candidate worktree")
    if _raw_reports(matrix_item, raw_path, report_suffix, cwd):
        raise EvidenceError(
            f"{command_id}: candidate-specific raw report already exists"
        )
    executed_argv = _format_command(matrix_item, raw_path, report_suffix)
    executed_command = render_command_argv(executed_argv)
    started_at, finished_at, duration, exit_code = _run_shell(
        executed_argv, cwd, stdout_path, stderr_path
    )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(matrix_item, raw_path, report_suffix, cwd)
    if matrix_item["report_kind"] == "SUREFIRE_GLOB" and raw_reports:
        retained_dir = attempt_dir / "raw-surefire"
        retained_dir.mkdir()
        retained_reports = []
        for report in raw_reports:
            retained = retained_dir / report.name
            shutil.copy2(report, retained)
            retained_reports.append(retained)
        raw_reports = retained_reports
    record: dict[str, Any] = {
        "id": command_id,
        "candidate_commit": candidate,
        "cwd": matrix_item["cwd"],
        "matrix_command": matrix_item["command"],
        "matrix_command_sha256": hashlib.sha256(
            matrix_item["command"].encode("utf-8")
        ).hexdigest(),
        "executed_command": executed_command,
        "executed_argv": executed_argv,
        "executed_command_sha256": hashlib.sha256(
            executed_command.encode("utf-8")
        ).hexdigest(),
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_seconds": duration,
        "exit_code": exit_code,
        "environment_sha256": environment_sha256,
        "stdout_path": _relative(stdout_path, run_root),
        "stdout_sha256": _sha256(stdout_path),
        "stderr_path": _relative(stderr_path, run_root),
        "stderr_sha256": _sha256(stderr_path),
        "raw_reports": [
            {"path": _relative(path, run_root), "sha256": _sha256(path)}
            for path in raw_reports
        ],
        "failure_classification": "NONE" if exit_code == 0 else "UNCLASSIFIED",
        "accepted": False,
    }
    if exit_code != 0:
        return record, False
    if not raw_reports:
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = "source command produced no JUnit report"
        return record, False
    source_dir = run_root / "source"
    source_dir.mkdir(exist_ok=True)
    destination = source_dir / SOURCE_REPORTS[command_id]
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
        return record, False
    totals = report.totals
    if (
        not totals["tests"]
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
            "Phase 6 entry execution is blocked before all source execution: "
            + ", ".join(blockers)
        )
    candidate = _assert_candidate(candidate_commit)
    run_root = run_root.resolve()
    assert_candidate_run_directory(run_root)
    if resume:
        if not run_root.is_dir():
            raise EvidenceError(f"resume run directory does not exist: {run_root}")
        manifest = _load_resume_manifest(run_root, candidate)
    else:
        assert_clean_detached_candidate(candidate)
        if run_root.exists():
            raise EvidenceError(
                f"Phase 6 entry run directory already exists: {run_root}"
            )
        run_root.mkdir(parents=True)
        manifest = _initial_manifest(
            candidate=candidate,
            environment_id=environment_id,
            run_root=run_root,
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
    manifest["contract_gate"] = "P6.0_NOT_RUN"
    manifest["implementation_authorized"] = False
    manifest["MIG-004"] = "PENDING_PROMOTION"
    manifest["MIG-005"] = "PENDING_PROMOTION"
    manifest["MIG-006"] = "PENDING_PROMOTION"
    _assert_candidate_unchanged(candidate, run_root)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan or, only after authenticated Phase 5 handoff and Phase 6 permission, "
            "execute the four exact-SHA Phase 6 entry source suites."
        )
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Execute only when the checked-in P6 gate is ready; blocked state rejects first.",
    )
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id", default="local-phase6-entry")
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
            if (
                arguments.resume
                or arguments.failure_classification
                or arguments.run_dir
            ):
                raise EvidenceError(
                    "--run-dir, --resume and --failure-classification require --execute"
                )
            print(json.dumps(entry_plan(arguments.candidate_commit), indent=2))
            return 0
        matrix = load_matrix()
        blockers = gate_blockers(matrix)
        if blockers:
            raise EvidenceError(
                "Phase 6 entry execution is blocked before all source execution: "
                + ", ".join(blockers)
            )
        if arguments.run_dir is None:
            raise EvidenceError("--run-dir is required with --execute")
        manifest = execute_checkpoint(
            candidate_commit=arguments.candidate_commit,
            run_root=arguments.run_dir,
            environment_id=arguments.environment_id,
            resume=arguments.resume,
            classifications=arguments.failure_classification,
        )
    except (EvidenceError, OSError) as exception:
        print(f"Phase 6 entry execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                "contract_gate": manifest["contract_gate"],
                "implementation_authorized": False,
                "MIG-004": "PENDING_PROMOTION",
                "MIG-005": "PENDING_PROMOTION",
                "MIG-006": "PENDING_PROMOTION",
            },
            sort_keys=True,
        )
    )
    return 0 if manifest["status"] == GREEN_STATUS else 2


if __name__ == "__main__":
    raise SystemExit(main())
