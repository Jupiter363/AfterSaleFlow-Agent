from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

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
        SOURCE_REPORTS,
        _assert_executed_command,
        _assert_execution_manifest_seal,
        _split_approved_command,
        assert_clean_detached_candidate,
        assert_candidate_run_directory,
        focused_commands,
        load_matrix,
        render_command_argv,
        seal_execution_manifest,
    )
except ModuleNotFoundError:  # Direct execution puts scripts/ on sys.path.
    from generate_phase3_candidate_evidence import (  # type: ignore[no-redef]
        EvidenceError,
        _assert_candidate,
        _sha256,
        _write_json,
        normalize_source_reports,
        parse_junit,
    )
    from generate_phase4_candidate_evidence import (  # type: ignore[no-redef]
        SOURCE_REPORTS,
        _assert_executed_command,
        _assert_execution_manifest_seal,
        _split_approved_command,
        assert_clean_detached_candidate,
        assert_candidate_run_directory,
        focused_commands,
        load_matrix,
        render_command_argv,
        seal_execution_manifest,
    )


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_NAME = "source-execution-manifest.json"
SCHEMA_VERSION = "phase4-source-execution-manifest.v1"
COMMAND_ORDER = tuple(SOURCE_REPORTS)
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}
DEPENDENCY_MANIFESTS = (
    "python-agent-service/pyproject.toml",
    "python-agent-service/requirements.lock",
    "java-api-service/pom.xml",
    "java-api-service/.mvn/wrapper/maven-wrapper.properties",
    "frontend/package.json",
    "frontend/pnpm-lock.yaml",
    "docker-compose.yml",
)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    seal_execution_manifest(manifest)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        _write_json(temporary, manifest)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def _tool_version(command: Sequence[str]) -> dict[str, Any]:
    try:
        process = subprocess.run(
            list(command),
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exception:
        return {"available": False, "detail": type(exception).__name__}
    output = (process.stdout or process.stderr).strip().splitlines()
    return {
        "available": process.returncode == 0,
        "exit_code": process.returncode,
        "version": output[0][:500] if output else "",
    }


def capture_environment(environment_id: str) -> dict[str, Any]:
    if not environment_id.strip():
        raise EvidenceError("environment ID must not be blank")
    dependency_manifests = []
    for name in DEPENDENCY_MANIFESTS:
        path = ROOT / name
        if path.is_file():
            dependency_manifests.append({"path": name, "sha256": _sha256(path)})
    snapshot: dict[str, Any] = {
        "environment_id": environment_id.strip(),
        "captured_at": _utc_now(),
        "host": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "tools": {
            "python": {
                "available": True,
                "version": platform.python_version(),
                "implementation": platform.python_implementation(),
            },
            "git": _tool_version(["git", "--version"]),
            "java": _tool_version(["java", "-version"]),
            "node": _tool_version(["node", "--version"]),
        },
        "dependency_manifests": dependency_manifests,
    }
    snapshot["snapshot_sha256"] = _json_sha256(snapshot)
    return snapshot


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    matrix = load_matrix()
    commands = focused_commands(matrix)
    return {
        "schema_version": "phase4-candidate-run-plan.v1",
        "phase": 4,
        "candidate_commit": candidate,
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
            }
            for command_id in COMMAND_ORDER
        ],
        "runtime_restrictions": {
            "real_provider": "forbidden",
            "formal_finalizer": "forbidden",
            "real_case_shadow": "forbidden",
            "promotion": "forbidden",
        },
    }


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


def _initial_manifest(
    *, candidate: str, environment_id: str, run_root: Path
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "phase": 4,
        "candidate_commit": candidate,
        "attempt_id": run_root.name,
        "status": "RUNNING",
        "verification_started_at": _utc_now(),
        "verification_finished_at": None,
        "environment": capture_environment(environment_id),
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
    }


def _load_resume_manifest(run_root: Path, candidate: str) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot resume Phase 4 manifest {path}: {exception}") from exception
    if manifest.get("schema_version") != SCHEMA_VERSION or manifest.get("phase") != 4:
        raise EvidenceError("resume manifest is not a Phase 4 source execution manifest")
    _assert_execution_manifest_seal(manifest)
    if manifest.get("candidate_commit") != candidate:
        raise EvidenceError("resume manifest belongs to a different candidate SHA")
    if manifest.get("status") == "PASS":
        raise EvidenceError("Phase 4 source execution manifest already passed")
    if manifest.get("status") == "CANDIDATE_BLOCKED":
        raise EvidenceError("classified PRODUCT, FIXTURE, or EXTERNAL_GATE blocked this candidate")
    _validate_resume_manifest(manifest, run_root, candidate)
    return manifest


def _timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise EvidenceError(f"resume manifest {field} is not an ISO-8601 timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise EvidenceError(
            f"resume manifest {field} is not an ISO-8601 timestamp"
        ) from exception
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise EvidenceError(f"resume manifest {field} must include a timezone")
    return parsed


def _artifact_path(
    run_root: Path,
    relative: Any,
    expected_sha256: Any,
    context: str,
) -> Path:
    if not isinstance(relative, str) or not relative:
        raise EvidenceError(f"{context} path is missing")
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise EvidenceError(f"{context} path is missing or escapes the run directory")
    if not isinstance(expected_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}", expected_sha256
    ):
        raise EvidenceError(f"{context} has no lowercase SHA-256")
    if _sha256(path) != expected_sha256:
        raise EvidenceError(f"{context} SHA-256 drifted")
    return path


def _validate_record_common(
    record: Any,
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    matrix_item: dict[str, str],
    environment_sha256: str,
    verification_started: datetime,
) -> tuple[set[Path], Path | None]:
    if not isinstance(record, dict) or record.get("id") != command_id:
        raise EvidenceError(f"resume manifest has an invalid {command_id} record")
    matrix_command = matrix_item["command"]
    if (
        record.get("candidate_commit") != candidate
        or record.get("cwd") != matrix_item["cwd"]
        or record.get("matrix_command") != matrix_command
        or record.get("matrix_command_sha256")
        != hashlib.sha256(matrix_command.encode("utf-8")).hexdigest()
        or record.get("environment_sha256") != environment_sha256
    ):
        raise EvidenceError(f"{command_id}: resume record binding drifted")
    executed = record.get("executed_command")
    junit_output = _assert_executed_command(
        command_id,
        matrix_command,
        executed,
        record.get("executed_argv"),
        run_root.resolve(),
    )
    if record.get("executed_command_sha256") != hashlib.sha256(
        executed.encode("utf-8")
    ).hexdigest():
        raise EvidenceError(f"{command_id}: executed command SHA-256 drifted")
    started = _timestamp(record.get("started_at"), f"{command_id}.started_at")
    finished = _timestamp(record.get("finished_at"), f"{command_id}.finished_at")
    duration = record.get("duration_seconds")
    if (
        started < verification_started
        or finished < started
        or not isinstance(duration, (int, float))
        or isinstance(duration, bool)
        or duration < 0
    ):
        raise EvidenceError(f"{command_id}: invalid resume record timeline")
    wall_duration = (finished - started).total_seconds()
    if abs(float(duration) - wall_duration) > max(5.0, wall_duration * 0.05):
        raise EvidenceError(f"{command_id}: duration is not bound to command timestamps")
    for stream in ("stdout", "stderr"):
        _artifact_path(
            run_root,
            record.get(f"{stream}_path"),
            record.get(f"{stream}_sha256"),
            f"{command_id} {stream}",
        )
    raw_reports = record.get("raw_reports")
    if not isinstance(raw_reports, list):
        raise EvidenceError(f"{command_id}: raw JUnit records are invalid")
    retained: set[Path] = set()
    for raw in raw_reports:
        if not isinstance(raw, dict):
            raise EvidenceError(f"{command_id}: raw JUnit record is invalid")
        retained.add(
            _artifact_path(
                run_root,
                raw.get("path"),
                raw.get("sha256"),
                f"{command_id} raw JUnit",
            )
        )
    return retained, junit_output


def _validate_accepted_record(
    record: Any,
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    matrix_item: dict[str, str],
    environment_sha256: str,
    verification_started: datetime,
) -> None:
    retained, junit_output = _validate_record_common(
        record,
        command_id=command_id,
        candidate=candidate,
        run_root=run_root,
        matrix_item=matrix_item,
        environment_sha256=environment_sha256,
        verification_started=verification_started,
    )
    if (
        record.get("exit_code") != 0
        or record.get("accepted") is not True
        or record.get("failure_classification") != "NONE"
        or not retained
        or (junit_output is not None and junit_output not in retained)
    ):
        raise EvidenceError(f"{command_id}: resume source command was not accepted")
    expected_report = SOURCE_REPORTS[command_id]
    if (
        record.get("report") != expected_report
        or record.get("report_path") != f"source/{expected_report}"
    ):
        raise EvidenceError(f"{command_id}: accepted report path drifted")
    report_path = _artifact_path(
        run_root,
        record.get("report_path"),
        record.get("report_sha256"),
        f"{command_id} accepted report",
    )
    report = parse_junit(report_path)
    if report.candidate_commit != candidate or report.command_id != command_id:
        raise EvidenceError(f"{command_id}: accepted report binding drifted")
    totals = report.totals
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise EvidenceError(f"{command_id}: accepted report is not all-pass zero-skip")
    for field in ("tests", "failures", "errors", "skipped"):
        if record.get(field) != totals[field]:
            raise EvidenceError(f"{command_id}: accepted report totals drifted")


def _validate_failed_record(
    record: Any,
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    matrix_item: dict[str, str],
    environment_sha256: str,
    verification_started: datetime,
    classification: str,
) -> None:
    _validate_record_common(
        record,
        command_id=command_id,
        candidate=candidate,
        run_root=run_root,
        matrix_item=matrix_item,
        environment_sha256=environment_sha256,
        verification_started=verification_started,
    )
    if (
        record.get("exit_code") == 0
        or record.get("accepted") is not False
        or record.get("failure_classification") != classification
    ):
        raise EvidenceError(f"{command_id}: failed resume record is invalid")


def _validate_resume_manifest(
    manifest: dict[str, Any], run_root: Path, candidate: str
) -> None:
    if manifest.get("attempt_id") != run_root.name:
        raise EvidenceError("resume manifest attempt ID does not match its run directory")
    if manifest.get("status") not in {"RUNNING", "REQUIRES_CLASSIFICATION"}:
        raise EvidenceError("resume manifest is not in a resumable state")
    if manifest.get("verification_finished_at") is not None:
        raise EvidenceError("resumable manifest already has a verification finish time")
    if manifest.get("quarantined_attempts_reused") is not False:
        raise EvidenceError("resume manifest reused a quarantined attempt")
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise EvidenceError("resume manifest lacks its execution environment")
    snapshot_sha256 = environment.get("snapshot_sha256")
    unsigned_environment = dict(environment)
    unsigned_environment.pop("snapshot_sha256", None)
    if snapshot_sha256 != _json_sha256(unsigned_environment):
        raise EvidenceError("resume environment snapshot SHA-256 drifted")
    dependencies = environment.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise EvidenceError("resume environment lacks dependency manifest hashes")
    for dependency in dependencies:
        if not isinstance(dependency, dict) or not isinstance(dependency.get("path"), str):
            raise EvidenceError("resume environment dependency record is invalid")
        path = (ROOT / dependency["path"]).resolve()
        if not path.is_relative_to(ROOT.resolve()) or not path.is_file():
            raise EvidenceError("resume environment dependency path escapes the candidate")
        if dependency.get("sha256") != _sha256(path):
            raise EvidenceError("resume environment dependency SHA-256 drifted")
    verification_started = _timestamp(
        manifest.get("verification_started_at"), "verification_started_at"
    )
    commands = focused_commands(load_matrix())
    records = manifest.get("commands")
    if not isinstance(records, list) or len(records) > len(COMMAND_ORDER):
        raise EvidenceError("resume manifest accepted command list is invalid")
    record_ids = [record.get("id") if isinstance(record, dict) else None for record in records]
    if record_ids != list(COMMAND_ORDER[: len(records)]):
        raise EvidenceError("resume manifest accepted commands are not an ordered source prefix")
    for record, command_id in zip(
        records, COMMAND_ORDER[: len(records)], strict=True
    ):
        _validate_accepted_record(
            record,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            matrix_item=commands[command_id],
            environment_sha256=snapshot_sha256,
            verification_started=verification_started,
        )
    source_dir = run_root / "source"
    observed_sources = (
        {path.name for path in source_dir.iterdir() if path.is_file()}
        if source_dir.is_dir()
        else set()
    )
    expected_sources = {SOURCE_REPORTS[command_id] for command_id in record_ids}
    if observed_sources != expected_sources:
        raise EvidenceError("resume source report set drifted from accepted commands")
    pending = manifest.get("pending_failure")
    next_command = COMMAND_ORDER[len(records)] if len(records) < len(COMMAND_ORDER) else None
    if manifest["status"] == "REQUIRES_CLASSIFICATION":
        if pending is None or next_command is None:
            raise EvidenceError("classification state has no next source failure")
        _validate_failed_record(
            pending,
            command_id=next_command,
            candidate=candidate,
            run_root=run_root,
            matrix_item=commands[next_command],
            environment_sha256=snapshot_sha256,
            verification_started=verification_started,
            classification="UNCLASSIFIED",
        )
    elif pending is not None:
        raise EvidenceError("RUNNING resume manifest retains an unclassified failure")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise EvidenceError("resume manifest quarantined attempts are invalid")
    for attempt in quarantined:
        command_id = attempt.get("id") if isinstance(attempt, dict) else None
        if command_id not in SOURCE_REPORTS:
            raise EvidenceError("resume manifest contains an unknown quarantined source")
        _validate_failed_record(
            attempt,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            matrix_item=commands[command_id],
            environment_sha256=snapshot_sha256,
            verification_started=verification_started,
            classification="INFRA",
        )


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


def _command_for_source(
    command_id: str,
    matrix_command: str,
    raw_path: Path,
    *,
    report_suffix: str,
) -> str:
    return render_command_argv(
        _command_argv_for_source(
            command_id,
            matrix_command,
            raw_path,
            report_suffix=report_suffix,
        )
    )


def _command_argv_for_source(
    command_id: str,
    matrix_command: str,
    raw_path: Path,
    *,
    report_suffix: str,
) -> list[str]:
    arguments = _split_approved_command(matrix_command)
    if command_id in {"python_phase_4", "static_phase_4"}:
        return [*arguments, f"--junitxml={raw_path.resolve()}"]
    if command_id == "frontend_phase_4":
        return [*arguments, "--reporter=junit", f"--outputFile={raw_path.resolve()}"]
    if arguments[-1:] != ["test"]:
        raise EvidenceError("java_phase_4 command must end in the Maven test goal")
    if not re.fullmatch(r"p4-[0-9a-f]{12}-[0-9a-f]{8}", report_suffix):
        raise EvidenceError("java_phase_4 report suffix is invalid")
    return [
        *arguments[:-1],
        f"-Dsurefire.reportNameSuffix={report_suffix}",
        "test",
    ]


def _run_shell(
    command: Sequence[str],
    cwd: Path,
    stdout_path: Path,
    stderr_path: Path,
) -> tuple[str, str, float, int]:
    started_at = _utc_now()
    started = time.perf_counter()
    with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout, stderr_path.open(
        "w", encoding="utf-8", errors="replace"
    ) as stderr:
        invocation = list(command)
        if os.name == "nt" and invocation[0].lower().endswith((".cmd", ".bat")):
            _assert_cmd_exe_safe(invocation)
            invocation = [
                "cmd.exe",
                "/d",
                "/v:off",
                "/s",
                "/c",
                subprocess.list2cmdline(invocation),
            ]
        process = subprocess.run(
            invocation,
            cwd=cwd,
            shell=False,
            check=False,
            stdout=stdout,
            stderr=stderr,
        )
    duration = round(time.perf_counter() - started, 3)
    return started_at, _utc_now(), duration, process.returncode


def _assert_cmd_exe_safe(invocation: Sequence[str]) -> None:
    if not invocation:
        raise EvidenceError("source command argv is empty")
    for argument in invocation:
        if re.search(r"[&|<>^%!\r\n]", argument):
            raise EvidenceError(
                "Windows cmd wrapper rejected shell control characters in source argv"
            )


def _raw_reports(
    command_id: str, raw_path: Path, report_suffix: str, cwd: Path
) -> list[Path]:
    if command_id != "java_phase_4":
        return [raw_path] if raw_path.is_file() else []
    report_dir = cwd / "target/surefire-reports"
    return sorted(report_dir.glob(f"TEST-*-{report_suffix}.xml"))


def _record_source(
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    matrix_item: dict[str, str],
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
        f"p4-{candidate[:12]}-{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    cwd = (ROOT / matrix_item["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise EvidenceError(f"{command_id}: matrix cwd escapes the candidate worktree")
    if command_id == "java_phase_4":
        stale = _raw_reports(command_id, raw_path, report_suffix, cwd)
        if stale:
            raise EvidenceError("candidate-specific Surefire report suffix is not unique")
    executed_argv = _command_argv_for_source(
        command_id,
        matrix_item["command"],
        raw_path,
        report_suffix=report_suffix,
    )
    executed_command = render_command_argv(executed_argv)
    started_at, finished_at, duration, exit_code = _run_shell(
        executed_argv, cwd, stdout_path, stderr_path
    )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(command_id, raw_path, report_suffix, cwd)
    if command_id == "java_phase_4" and raw_reports:
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
    if totals["failures"] or totals["errors"] or totals["skipped"]:
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
            raise EvidenceError(f"candidate run directory already exists: {run_root}")
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

    matrix = load_matrix()
    commands = focused_commands(matrix)
    accepted = {entry["id"]: entry for entry in manifest["commands"]}
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

    manifest["status"] = "PASS"
    manifest["verification_finished_at"] = _utc_now()
    manifest["promotion_gate"] = "PENDING"
    manifest["MIG-003"] = "PENDING_PROMOTION"
    manifest["MIG-004"] = "PENDING_PROMOTION"
    _assert_candidate_unchanged(candidate, run_root)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan or execute the four candidate-bound Phase 4 source suites from an "
            "exact clean detached Git SHA."
        )
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Execute suites. Without this flag the command only prints the immutable run plan.",
    )
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id", default="local-phase4-candidate")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--failure-classification",
        action="append",
        default=[],
        metavar="COMMAND_ID=CLASS",
        help="Classify the single pending failure before resume; only INFRA permits same-SHA retry.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if not arguments.execute:
            if arguments.resume or arguments.failure_classification:
                raise EvidenceError("--resume and --failure-classification require --execute")
            print(json.dumps(candidate_plan(arguments.candidate_commit), indent=2))
            return 0
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
        print(f"Phase 4 candidate execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                "promotion_gate": manifest.get("promotion_gate", "PENDING"),
                "MIG-003": manifest.get("MIG-003", "PENDING_PROMOTION"),
                "MIG-004": manifest.get("MIG-004", "PENDING_PROMOTION"),
            },
            sort_keys=True,
        )
    )
    return 0 if manifest["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
