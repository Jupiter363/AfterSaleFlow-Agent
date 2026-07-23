from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

try:
    from scripts import generate_phase4_candidate_evidence as trusted
    from scripts import run_phase4_candidate_checkpoint as process_runner
    from scripts.generate_phase3_candidate_evidence import (
        EvidenceError,
        _assert_candidate,
        _sha256,
        _write_json,
        normalize_source_reports,
        parse_junit,
    )
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import generate_phase4_candidate_evidence as trusted  # type: ignore[no-redef]
    import run_phase4_candidate_checkpoint as process_runner  # type: ignore[no-redef]
    from generate_phase3_candidate_evidence import (  # type: ignore[no-redef]
        EvidenceError,
        _assert_candidate,
        _sha256,
        _write_json,
        normalize_source_reports,
        parse_junit,
    )


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
BATCH_ID = "P5-BATCH-3"
MANIFEST_NAME = "phase5-candidate-execution-manifest.json"
SCHEMA_VERSION = "phase5-candidate-execution-manifest.v1"
PLAN_SCHEMA_VERSION = "phase5-candidate-run-plan.v1"
COMMAND_ORDER = (
    "python_phase_5_deduplicated",
    "java_phase_5_deduplicated",
    "frontend_phase_5_deduplicated",
    "static_phase_5_deduplicated",
)
SOURCE_REPORTS = {
    "python_phase_5_deduplicated": "python-phase5-junit.xml",
    "java_phase_5_deduplicated": "java-phase5-junit.xml",
    "frontend_phase_5_deduplicated": "frontend-phase5-junit.xml",
    "static_phase_5_deduplicated": "static-phase5-junit.xml",
}
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}
MAX_INFRA_RERUNS_PER_SOURCE = 1
CANDIDATE_STATIC_TESTS = (
    "tests/static/test_phase5_candidate_runner.py",
    "tests/static/test_phase5_candidate_evidence.py",
)
DEPENDENCY_MANIFESTS = process_runner.DEPENDENCY_MANIFESTS

PENDING_GATES = {
    "promotion_gate": "PENDING",
    "MIG-004": "PENDING_PROMOTION",
    "MIG-005": "PENDING_PROMOTION",
}
RUNTIME_RESTRICTIONS = {
    "allowed_modes": ["LEGACY", "DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
    "formal_evidence_graph_sink": "forbidden",
    "temporal_evidence_allocation": "forbidden",
    "real_case_shadow": "forbidden",
    "canary": "forbidden",
    "promotion": "forbidden",
    "t3_unified_checkpoint": "not_executed",
}


def load_matrix(path: Path = MATRIX_PATH) -> dict[str, Any]:
    matrix = trusted._load_yaml(path)
    if matrix.get("schema_version") != "phase-test-batches.v1" or matrix.get("phase") != 5:
        raise EvidenceError(f"{path}: not the Phase 5 test matrix")
    batch = matrix.get("batches", {}).get(BATCH_ID, {})
    if batch.get("source_groups") != list(COMMAND_ORDER):
        raise EvidenceError(f"{path}: Phase 5 candidate source groups drifted")
    if batch.get("execution", {}).get("strategy") != (
        "deduplicated_source_suites_then_derived_batch_views"
    ):
        raise EvidenceError(f"{path}: Phase 5 candidate execution strategy drifted")
    policy = batch.get("candidate_policy", {})
    required_policy = {
        "accepted_candidate_count": 1,
        "candidate_sha_immutable_during_run": True,
        "source_suites_execute_once_per_accepted_candidate": True,
        "mixed_commit_results_forbidden": True,
        "quarantined_attempts_not_reused": True,
        "code_change_invalidates_entire_candidate_checkpoint": True,
        "infra_retry_same_sha_only": True,
    }
    if policy != required_policy:
        raise EvidenceError(f"{path}: Phase 5 candidate policy drifted")
    return matrix


def _source_argv(item: dict[str, Any]) -> list[str]:
    argv = item.get("argv")
    if isinstance(argv, list) and argv and all(isinstance(value, str) and value for value in argv):
        return list(argv)
    command = item.get("command")
    if isinstance(command, str):
        return trusted._split_approved_command(command)
    raise EvidenceError(f"source command {item.get('id')!r} has no safe argv")


def _source_commands(matrix: dict[str, Any]) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    for batch_id in ("P5-BATCH-0", "P5-BATCH-1", "P5-BATCH-2"):
        commands = matrix.get("batches", {}).get(batch_id, {}).get("source_commands")
        if not isinstance(commands, list) or not commands:
            raise EvidenceError(f"{batch_id}: source commands are missing")
        values.extend(commands)
    return values


def _append_unique(target: list[str], seen: set[str], values: Sequence[str]) -> None:
    for value in values:
        normalized = value.replace("\\", "/")
        if normalized not in seen:
            seen.add(normalized)
            target.append(normalized)


def _append_pytest_unique(target: list[str], values: Sequence[str]) -> None:
    for value in values:
        normalized = value.replace("\\", "/").rstrip("/")
        if any(normalized == current or normalized.startswith(f"{current}/") for current in target):
            continue
        covered = [current for current in target if current.startswith(f"{normalized}/")]
        if covered:
            insert_at = min(target.index(current) for current in covered)
            target[:] = [current for current in target if current not in covered]
            target.insert(insert_at, normalized)
        else:
            target.append(normalized)


def _pytest_selectors(argv: Sequence[str]) -> list[str]:
    return [value.replace("\\", "/") for value in argv if value.replace("\\", "/").startswith("tests/")]


def _frontend_selectors(argv: Sequence[str]) -> list[str]:
    return [
        value.replace("\\", "/")
        for value in argv
        if value.replace("\\", "/").startswith("src/")
        and ".test." in value
    ]


def _java_classes(argv: Sequence[str]) -> list[str]:
    values: list[str] = []
    for value in argv:
        if value.startswith("-Dtest="):
            values.extend(item for item in value.removeprefix("-Dtest=").split(",") if item)
    return values


def focused_commands(matrix: dict[str, Any] | None = None) -> dict[str, dict[str, Any]]:
    matrix = matrix or load_matrix()
    python: list[str] = []
    java: list[str] = []
    frontend: list[str] = []
    static: list[str] = []
    seen = {"python": set(), "java": set(), "frontend": set(), "static": set()}
    python_executable = "D:/miniconda/python.exe"
    java_executable = ".\\mvnw.cmd" if os.name == "nt" else "./mvnw"
    frontend_prefix = ["node", "node_modules/vitest/vitest.mjs", "run"]

    for item in _source_commands(matrix):
        command_id = str(item.get("id", ""))
        argv = _source_argv(item)
        if command_id.endswith("_java"):
            if argv[0].replace("\\", "/") not in {
                "./mvnw",
                "./mvnw.cmd",
                "mvnw.cmd",
            }:
                raise EvidenceError("Phase 5 Java source uses an untrusted Maven launcher")
            _append_unique(java, seen["java"], _java_classes(argv))
        elif command_id.endswith("_frontend"):
            frontend_prefix = list(argv[:3])
            _append_unique(frontend, seen["frontend"], _frontend_selectors(argv))
        elif command_id.endswith("_static"):
            python_executable = argv[0]
            _append_pytest_unique(static, _pytest_selectors(argv))
        elif command_id.endswith("_python") or command_id.endswith("_postgresql"):
            python_executable = argv[0]
            _append_pytest_unique(python, _pytest_selectors(argv))

    _append_pytest_unique(static, CANDIDATE_STATIC_TESTS)
    if not all((python, java, frontend, static)):
        raise EvidenceError("Phase 5 deduplicated source groups must all be non-empty")

    argv_by_id = {
        "python_phase_5_deduplicated": [python_executable, "-m", "pytest", *python, "-q"],
        "java_phase_5_deduplicated": [
            java_executable,
            "-q",
            "-DforkCount=1",
            f"-Dtest={','.join(java)}",
            "test",
        ],
        "frontend_phase_5_deduplicated": [
            *frontend_prefix,
            *frontend,
            "--minWorkers=1",
            "--maxWorkers=2",
        ],
        "static_phase_5_deduplicated": [python_executable, "-m", "pytest", *static, "-q"],
    }
    cwd_by_id = {
        "python_phase_5_deduplicated": "python-agent-service",
        "java_phase_5_deduplicated": "java-api-service",
        "frontend_phase_5_deduplicated": "frontend",
        "static_phase_5_deduplicated": ".",
    }
    return {
        command_id: {
            "id": command_id,
            "cwd": cwd_by_id[command_id],
            "argv": argv_by_id[command_id],
            "command": trusted.render_command_argv(argv_by_id[command_id]),
            "report": SOURCE_REPORTS[command_id],
        }
        for command_id in COMMAND_ORDER
    }


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    commands = focused_commands()
    return {
        "schema_version": PLAN_SCHEMA_VERSION,
        "phase": 5,
        "batch": BATCH_ID,
        "candidate_commit": candidate,
        "candidate_policy": {
            "clean_detached": True,
            "fixed_before_first_command": True,
            "mixed_commit_results": False,
            "source_suites_execute_once": True,
            "same_sha_infra_reruns_per_source": MAX_INFRA_RERUNS_PER_SOURCE,
            "unconditional_rerun": False,
        },
        "execution_order": list(COMMAND_ORDER),
        "runner_execution": "sequential",
        "commands": [
            {
                "id": command_id,
                "cwd": commands[command_id]["cwd"],
                "argv": commands[command_id]["argv"],
                "matrix_command": commands[command_id]["command"],
                "matrix_command_sha256": hashlib.sha256(
                    commands[command_id]["command"].encode("utf-8")
                ).hexdigest(),
                "report": SOURCE_REPORTS[command_id],
            }
            for command_id in COMMAND_ORDER
        ],
        "runtime_restrictions": RUNTIME_RESTRICTIONS,
        **PENDING_GATES,
    }


def _json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def seal_execution_manifest(manifest: dict[str, Any]) -> str:
    manifest.pop("manifest_sha256", None)
    digest = _json_sha256(manifest)
    manifest["manifest_sha256"] = digest
    return digest


def _assert_manifest_seal(manifest: dict[str, Any]) -> None:
    expected = manifest.get("manifest_sha256")
    unsigned = dict(manifest)
    unsigned.pop("manifest_sha256", None)
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise EvidenceError("Phase 5 candidate manifest has no lowercase SHA-256 seal")
    if _json_sha256(unsigned) != expected:
        raise EvidenceError("Phase 5 candidate manifest SHA-256 drifted")


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    seal_execution_manifest(manifest)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_bytes(
            (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode(
                "utf-8"
            )
        )
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _allowed_run_root(run_root: Path) -> tuple[Path, ...]:
    return (run_root.resolve(),) if run_root.resolve().is_relative_to(ROOT.resolve()) else ()


def _assert_candidate_unchanged(candidate: str, run_root: Path) -> None:
    trusted.assert_clean_detached_candidate(
        candidate,
        allowed_untracked_roots=_allowed_run_root(run_root),
    )


def _capture_environment(environment_id: str) -> dict[str, Any]:
    snapshot = process_runner.capture_environment(environment_id)
    dependencies = []
    for name in DEPENDENCY_MANIFESTS:
        path = ROOT / name
        if path.is_file():
            dependencies.append({"path": name, "sha256": _sha256(path)})
    snapshot["dependency_manifests"] = dependencies
    snapshot.pop("snapshot_sha256", None)
    snapshot["snapshot_sha256"] = _json_sha256(snapshot)
    return snapshot


def _initial_manifest(candidate: str, run_root: Path, environment_id: str) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "phase": 5,
        "batch": BATCH_ID,
        "candidate_commit": candidate,
        "attempt_id": run_root.name,
        "status": "RUNNING",
        "verification_started_at": process_runner._utc_now(),
        "verification_finished_at": None,
        "environment": _capture_environment(environment_id),
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
        "runtime_restrictions": RUNTIME_RESTRICTIONS,
        **PENDING_GATES,
    }


def _artifact_path(run_root: Path, value: Any, digest: Any, context: str) -> Path:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{context} path is missing")
    path = (run_root / value).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise EvidenceError(f"{context} path is missing or escapes the run directory")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise EvidenceError(f"{context} has no lowercase SHA-256")
    if _sha256(path) != digest:
        raise EvidenceError(f"{context} SHA-256 drifted")
    return path


def _assert_executed_argv(
    *,
    command_id: str,
    approved_argv: Sequence[str],
    executed_argv: Sequence[str],
    attempt_dir: Path,
) -> tuple[Path | None, str | None]:
    approved = list(approved_argv)
    executed = list(executed_argv)
    if command_id in {"python_phase_5_deduplicated", "static_phase_5_deduplicated"}:
        if len(executed) != len(approved) + 1 or executed[: len(approved)] != approved:
            raise EvidenceError(f"{command_id}: executed argv differs from the approved source")
        token = executed[-1]
        prefix = "--junitxml="
        report_suffix = None
    elif command_id == "frontend_phase_5_deduplicated":
        if (
            len(executed) != len(approved) + 2
            or executed[: len(approved)] != approved
            or executed[-2] != "--reporter=junit"
        ):
            raise EvidenceError(f"{command_id}: executed argv differs from the approved source")
        token = executed[-1]
        prefix = "--outputFile="
        report_suffix = None
    else:
        if approved[-1:] != ["test"] or len(executed) != len(approved) + 1:
            raise EvidenceError("Phase 5 Java approved or executed argv is invalid")
        suffix_token = executed[-2]
        prefix = "-Dsurefire.reportNameSuffix="
        if (
            executed[:-2] != approved[:-1]
            or executed[-1] != "test"
            or not suffix_token.startswith(prefix)
        ):
            raise EvidenceError(f"{command_id}: executed argv differs from the approved source")
        report_suffix = suffix_token.removeprefix(prefix)
        if not re.fullmatch(r"p5-[0-9a-f]{12}-[0-9a-f]{8}", report_suffix):
            raise EvidenceError("Phase 5 Java executed report suffix is invalid")
        return None, report_suffix
    if not token.startswith(prefix):
        raise EvidenceError(f"{command_id}: executed argv lacks its unique JUnit output")
    value = token.removeprefix(prefix)
    if not value or any(item.startswith(prefix) for item in executed[:-1]):
        raise EvidenceError(f"{command_id}: executed argv has ambiguous JUnit output")
    output = Path(value).resolve()
    if output != attempt_dir.resolve() / "raw-junit.xml":
        raise EvidenceError(f"{command_id}: JUnit output is not bound to its exact attempt")
    return output, report_suffix


def _validate_record(
    record: Any,
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    command: dict[str, Any],
    environment_sha256: str,
    verification_started: Any,
    verification_finished: Any,
    accepted: bool,
    classification: str,
) -> None:
    if not isinstance(record, dict) or record.get("id") != command_id:
        raise EvidenceError(f"{command_id}: invalid execution record")
    matrix_command = command["command"]
    if (
        record.get("candidate_commit") != candidate
        or record.get("cwd") != command["cwd"]
        or record.get("matrix_command") != matrix_command
        or record.get("matrix_command_sha256")
        != hashlib.sha256(matrix_command.encode("utf-8")).hexdigest()
        or record.get("environment_sha256") != environment_sha256
    ):
        raise EvidenceError(f"{command_id}: execution record binding drifted")
    stdout_path = _artifact_path(
        run_root, record.get("stdout_path"), record.get("stdout_sha256"), f"{command_id} stdout"
    )
    stderr_path = _artifact_path(
        run_root, record.get("stderr_path"), record.get("stderr_sha256"), f"{command_id} stderr"
    )
    attempt_dir = stdout_path.parent
    if stderr_path.parent != attempt_dir or attempt_dir.parent != (run_root / "attempts").resolve():
        raise EvidenceError(f"{command_id}: stream artifacts are not bound to one exact attempt")
    argv = record.get("executed_argv")
    rendered = record.get("executed_command")
    if not isinstance(argv, list) or not all(isinstance(value, str) for value in argv):
        raise EvidenceError(f"{command_id}: executed argv is invalid")
    if rendered != trusted.render_command_argv(argv) or record.get(
        "executed_command_sha256"
    ) != hashlib.sha256(str(rendered).encode("utf-8")).hexdigest():
        raise EvidenceError(f"{command_id}: executed command binding drifted")
    raw_output, report_suffix = _assert_executed_argv(
        command_id=command_id,
        approved_argv=command["argv"],
        executed_argv=argv,
        attempt_dir=attempt_dir,
    )
    verification_start = process_runner._timestamp(
        verification_started, "verification_started_at"
    )
    started = process_runner._timestamp(record.get("started_at"), f"{command_id}.started_at")
    finished = process_runner._timestamp(record.get("finished_at"), f"{command_id}.finished_at")
    duration = record.get("duration_seconds")
    if (
        started < verification_start
        or finished < started
        or not isinstance(duration, (int, float))
        or isinstance(duration, bool)
        or duration < 0
    ):
        raise EvidenceError(f"{command_id}: invalid execution timeline")
    wall_duration = (finished - started).total_seconds()
    if abs(float(duration) - wall_duration) > max(5.0, wall_duration * 0.05):
        raise EvidenceError(f"{command_id}: duration is not bound to command timestamps")
    if verification_finished is not None:
        finish_limit = process_runner._timestamp(
            verification_finished, "verification_finished_at"
        )
        if finished > finish_limit:
            raise EvidenceError(f"{command_id}: command finished after verification finish")
    raw = record.get("raw_reports")
    if not isinstance(raw, list):
        raise EvidenceError(f"{command_id}: raw report records are invalid")
    raw_paths: set[Path] = set()
    retained_raw_paths: list[Path] = []
    for item in raw:
        if not isinstance(item, dict):
            raise EvidenceError(f"{command_id}: raw report record is invalid")
        retained = _artifact_path(
            run_root, item.get("path"), item.get("sha256"), f"{command_id} raw JUnit"
        )
        if retained in raw_paths:
            raise EvidenceError(f"{command_id}: raw JUnit path is duplicated")
        raw_paths.add(retained)
        retained_raw_paths.append(retained)
        if report_suffix is None and (
            retained != raw_output or retained != attempt_dir / "raw-junit.xml"
        ):
            raise EvidenceError(f"{command_id}: raw JUnit is not the injected output path")
        if report_suffix is not None and (
            retained.parent != attempt_dir / "raw-surefire"
            or not retained.name.startswith("TEST-")
            or not retained.name.endswith(f"-{report_suffix}.xml")
        ):
            raise EvidenceError(f"{command_id}: retained Surefire report escaped its suffix")
    if accepted:
        filename = SOURCE_REPORTS[command_id]
        if (
            record.get("accepted") is not True
            or record.get("failure_classification") != "NONE"
            or record.get("exit_code") != 0
            or record.get("report") != filename
            or record.get("report_path") != f"source/{filename}"
            or not raw
        ):
            raise EvidenceError(f"{command_id}: accepted record is invalid")
        report_path = _artifact_path(
            run_root, record.get("report_path"), record.get("report_sha256"), f"{command_id} report"
        )
        report = parse_junit(report_path)
        with tempfile.TemporaryDirectory(prefix="phase5-junit-auth-") as temporary:
            rebuilt_path = Path(temporary) / filename
            try:
                normalize_source_reports(
                    retained_raw_paths,
                    rebuilt_path,
                    candidate_commit=candidate,
                    command_id=command_id,
                )
            except EvidenceError as exception:
                raise EvidenceError(
                    f"{command_id}: cannot produce deterministic raw JUnit normalization: {exception}"
                ) from exception
            if rebuilt_path.read_bytes() != report_path.read_bytes():
                raise EvidenceError(
                    f"{command_id}: accepted report is not the deterministic raw JUnit normalization"
                )
        if report.candidate_commit != candidate or report.command_id != command_id:
            raise EvidenceError(f"{command_id}: accepted report binding drifted")
        totals = report.totals
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise EvidenceError(f"{command_id}: accepted report is not all-pass zero-skip")
        for field in ("tests", "failures", "errors", "skipped"):
            if record.get(field) != totals[field]:
                raise EvidenceError(f"{command_id}: accepted report totals drifted")
    elif (
        record.get("accepted") is not False
        or record.get("exit_code") == 0
        or record.get("failure_classification") != classification
    ):
        raise EvidenceError(f"{command_id}: failed record is invalid")


def _validate_attempt_inventory(
    *,
    manifest: dict[str, Any],
    run_root: Path,
    pending: dict[str, Any] | None,
) -> None:
    attempts_root = run_root / "attempts"
    records: list[tuple[str, dict[str, Any]]] = []
    records.extend(("accepted", item) for item in manifest["commands"])
    records.extend(("INFRA", item) for item in manifest["quarantined_attempts"])
    if pending is not None:
        records.append(("pending", pending))

    expected_dirs: set[Path] = set()
    expected_files: set[Path] = set()
    per_source: dict[str, list[tuple[int, str]]] = {item: [] for item in COMMAND_ORDER}
    for state, record in records:
        stdout = (run_root / record["stdout_path"]).resolve()
        stderr = (run_root / record["stderr_path"]).resolve()
        attempt_dir = stdout.parent
        if (
            stderr.parent != attempt_dir
            or attempt_dir.parent != attempts_root.resolve()
            or not attempt_dir.name.startswith(f"{record['id']}-")
        ):
            raise EvidenceError(f"{record['id']}: attempt artifact directory binding drifted")
        suffix = attempt_dir.name.removeprefix(f"{record['id']}-")
        if not re.fullmatch(r"[0-9]{2}", suffix):
            raise EvidenceError(f"{record['id']}: attempt directory sequence is invalid")
        sequence = int(suffix)
        expected_dirs.add(attempt_dir)
        expected_files.update((stdout, stderr))
        for raw in record["raw_reports"]:
            expected_files.add((run_root / raw["path"]).resolve())
        per_source[record["id"]].append((sequence, state))

    if len(expected_dirs) != len(records):
        raise EvidenceError("Phase 5 manifest reuses one attempt directory across records")
    actual_dirs = (
        {path.resolve() for path in attempts_root.iterdir() if path.is_dir()}
        if attempts_root.is_dir()
        else set()
    )
    if actual_dirs != expected_dirs:
        raise EvidenceError("Phase 5 attempt directory set contains hidden or missing executions")
    actual_files = (
        {path.resolve() for path in attempts_root.rglob("*") if path.is_file()}
        if attempts_root.is_dir()
        else set()
    )
    if actual_files != expected_files:
        raise EvidenceError("Phase 5 attempt directory contains unreferenced or missing artifacts")

    for command_id, attempts in per_source.items():
        attempts.sort()
        if [number for number, _ in attempts] != list(range(1, len(attempts) + 1)):
            raise EvidenceError(f"{command_id}: attempt sequence is not contiguous")
        states = [state for _, state in attempts]
        if states not in (
            [],
            ["accepted"],
            ["pending"],
            ["INFRA"],
            ["INFRA", "accepted"],
            ["INFRA", "pending"],
        ):
            raise EvidenceError(f"{command_id}: source was executed outside its bounded retry policy")


def _validate_manifest(
    manifest: dict[str, Any], run_root: Path, candidate: str, *, require_pass: bool
) -> dict[str, Any]:
    _assert_manifest_seal(manifest)
    if (
        manifest.get("schema_version") != SCHEMA_VERSION
        or manifest.get("phase") != 5
        or manifest.get("batch") != BATCH_ID
        or manifest.get("candidate_commit") != candidate
        or manifest.get("attempt_id") != run_root.name
        or manifest.get("quarantined_attempts_reused") is not False
        or manifest.get("runtime_restrictions") != RUNTIME_RESTRICTIONS
        or any(manifest.get(key) != value for key, value in PENDING_GATES.items())
    ):
        raise EvidenceError("Phase 5 candidate manifest identity or gate binding drifted")
    allowed_status = {"PASS"} if require_pass else {"RUNNING", "REQUIRES_CLASSIFICATION"}
    if manifest.get("status") not in allowed_status:
        raise EvidenceError("Phase 5 candidate manifest is not in the required state")
    verification_started = process_runner._timestamp(
        manifest.get("verification_started_at"), "verification_started_at"
    )
    if require_pass:
        verification_finished = process_runner._timestamp(
            manifest.get("verification_finished_at"), "verification_finished_at"
        )
        if verification_finished < verification_started:
            raise EvidenceError("Phase 5 verification finish predates its start")
    else:
        if manifest.get("verification_finished_at") is not None:
            raise EvidenceError("resumable Phase 5 manifest already has a finish timestamp")
        verification_finished = None
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise EvidenceError("Phase 5 candidate manifest lacks its environment")
    snapshot_sha = environment.get("snapshot_sha256")
    unsigned_environment = dict(environment)
    unsigned_environment.pop("snapshot_sha256", None)
    if snapshot_sha != _json_sha256(unsigned_environment):
        raise EvidenceError("Phase 5 candidate environment SHA-256 drifted")
    environment_captured = process_runner._timestamp(
        environment.get("captured_at"), "environment.captured_at"
    )
    if environment_captured < verification_started or (
        verification_finished is not None and environment_captured > verification_finished
    ):
        raise EvidenceError("Phase 5 environment capture is outside the verification timeline")
    dependencies = environment.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise EvidenceError("Phase 5 candidate environment lacks dependency hashes")
    expected_dependency_paths = {
        name for name in DEPENDENCY_MANIFESTS if (ROOT / name).is_file()
    }
    observed_dependency_paths = {
        item.get("path") for item in dependencies if isinstance(item, dict)
    }
    if observed_dependency_paths != expected_dependency_paths or len(dependencies) != len(
        expected_dependency_paths
    ):
        raise EvidenceError("Phase 5 dependency manifest set drifted")
    for item in dependencies:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise EvidenceError("Phase 5 dependency record is invalid")
        path = (ROOT / item["path"]).resolve()
        if not path.is_relative_to(ROOT.resolve()) or item.get("sha256") != _sha256(path):
            raise EvidenceError("Phase 5 dependency manifest SHA-256 drifted")

    commands = focused_commands()
    records = manifest.get("commands")
    if not isinstance(records, list) or len(records) > len(COMMAND_ORDER):
        raise EvidenceError("Phase 5 accepted source records are invalid")
    ids = [record.get("id") if isinstance(record, dict) else None for record in records]
    if ids != list(COMMAND_ORDER[: len(ids)]):
        raise EvidenceError("Phase 5 accepted sources are not an ordered prefix")
    for record, command_id in zip(records, ids, strict=True):
        _validate_record(
            record,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=snapshot_sha,
            verification_started=manifest.get("verification_started_at"),
            verification_finished=manifest.get("verification_finished_at"),
            accepted=True,
            classification="NONE",
        )
    observed = (
        {path.name for path in (run_root / "source").iterdir() if path.is_file()}
        if (run_root / "source").is_dir()
        else set()
    )
    expected = {SOURCE_REPORTS[command_id] for command_id in ids}
    if observed != expected:
        raise EvidenceError("Phase 5 source report set contains old or unbound reports")

    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise EvidenceError("Phase 5 quarantined attempts are invalid")
    for attempt in quarantined:
        command_id = attempt.get("id") if isinstance(attempt, dict) else None
        if command_id not in SOURCE_REPORTS:
            raise EvidenceError("Phase 5 quarantined attempt names an unknown source")
        _validate_record(
            attempt,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=snapshot_sha,
            verification_started=manifest.get("verification_started_at"),
            verification_finished=manifest.get("verification_finished_at"),
            accepted=False,
            classification="INFRA",
        )
    counts = {command_id: 0 for command_id in COMMAND_ORDER}
    for attempt in quarantined:
        counts[attempt["id"]] += 1
    if any(value > MAX_INFRA_RERUNS_PER_SOURCE for value in counts.values()):
        raise EvidenceError("Phase 5 candidate manifest exceeds its bounded INFRA rerun limit")

    pending = manifest.get("pending_failure")
    if manifest["status"] == "REQUIRES_CLASSIFICATION":
        next_id = COMMAND_ORDER[len(records)] if len(records) < len(COMMAND_ORDER) else None
        if pending is None or next_id is None:
            raise EvidenceError("Phase 5 classification state lacks a pending source failure")
        _validate_record(
            pending,
            command_id=next_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[next_id],
            environment_sha256=snapshot_sha,
            verification_started=manifest.get("verification_started_at"),
            verification_finished=manifest.get("verification_finished_at"),
            accepted=False,
            classification="UNCLASSIFIED",
        )
    elif pending is not None:
        raise EvidenceError("Phase 5 manifest retains a pending failure in a non-classification state")
    _validate_attempt_inventory(manifest=manifest, run_root=run_root, pending=pending)
    if require_pass:
        if len(records) != len(COMMAND_ORDER) or quarantined and any(
            item.get("failure_classification") != "INFRA" for item in quarantined
        ):
            raise EvidenceError("Phase 5 PASS manifest lacks its complete accepted source chain")
    return manifest


def load_pass_manifest(path: Path, candidate_commit: str) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    path = path.resolve()
    trusted.assert_candidate_run_directory(path.parent)
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot read Phase 5 candidate manifest: {exception}") from exception
    return _validate_manifest(manifest, path.parent, candidate, require_pass=True)


def _load_resume_manifest(run_root: Path, candidate: str) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot resume Phase 5 candidate manifest: {exception}") from exception
    if manifest.get("status") == "PASS":
        raise EvidenceError("Phase 5 candidate source suites already passed; rerun is forbidden")
    if manifest.get("status") == "CANDIDATE_BLOCKED":
        raise EvidenceError("Phase 5 candidate was blocked by its classified failure")
    return _validate_manifest(manifest, run_root, candidate, require_pass=False)


def _classification_map(values: Sequence[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        command_id, separator, classification = value.partition("=")
        classification = classification.upper()
        if (
            not separator
            or command_id not in SOURCE_REPORTS
            or classification not in FAILURE_CLASSIFICATIONS
            or command_id in result
        ):
            raise EvidenceError(
                "failure classification must be unique COMMAND_ID="
                + "|".join(sorted(FAILURE_CLASSIFICATIONS))
            )
        result[command_id] = classification
    return result


def _classify_pending_failure(
    manifest: dict[str, Any], classifications: dict[str, str]
) -> bool:
    pending = manifest.get("pending_failure")
    if pending is None:
        if classifications:
            raise EvidenceError("no pending Phase 5 source failure accepts a classification")
        return True
    command_id = pending["id"]
    classification = classifications.get(command_id)
    if classification is None:
        return False
    if set(classifications) != {command_id}:
        raise EvidenceError("classification names a source without the pending failure")
    pending["failure_classification"] = classification
    manifest["pending_failure"] = None
    if classification != "INFRA":
        manifest["quarantined_attempts"].append(pending)
        manifest["status"] = "CANDIDATE_BLOCKED"
        manifest["verification_finished_at"] = process_runner._utc_now()
        return False
    prior = sum(1 for item in manifest["quarantined_attempts"] if item["id"] == command_id)
    if prior >= MAX_INFRA_RERUNS_PER_SOURCE:
        pending["failure_classification"] = "INFRA"
        pending["failure_reason"] = "bounded same-SHA INFRA rerun exhausted"
        manifest["quarantined_attempts"].append(pending)
        manifest["status"] = "CANDIDATE_BLOCKED"
        manifest["verification_finished_at"] = process_runner._utc_now()
        return False
    manifest["quarantined_attempts"].append(pending)
    manifest["status"] = "RUNNING"
    return True


def _command_argv_for_source(
    command_id: str, argv: Sequence[str], raw_path: Path, *, report_suffix: str
) -> list[str]:
    arguments = list(argv)
    if command_id in {"python_phase_5_deduplicated", "static_phase_5_deduplicated"}:
        return [*arguments, f"--junitxml={raw_path.resolve()}"]
    if command_id == "frontend_phase_5_deduplicated":
        return [*arguments, "--reporter=junit", f"--outputFile={raw_path.resolve()}"]
    if arguments[-1:] != ["test"]:
        raise EvidenceError("Phase 5 Java source command must end in Maven test")
    if not re.fullmatch(r"p5-[0-9a-f]{12}-[0-9a-f]{8}", report_suffix):
        raise EvidenceError("Phase 5 Java report suffix is invalid")
    return [*arguments[:-1], f"-Dsurefire.reportNameSuffix={report_suffix}", "test"]


def _raw_reports(command_id: str, raw_path: Path, report_suffix: str, cwd: Path) -> list[Path]:
    if command_id != "java_phase_5_deduplicated":
        return [raw_path] if raw_path.is_file() else []
    return sorted((cwd / "target/surefire-reports").glob(f"TEST-*-{report_suffix}.xml"))


def _record_source(
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    command: dict[str, Any],
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
        f"p5-{candidate[:12]}-"
        f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    cwd = (ROOT / command["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise EvidenceError(f"{command_id}: cwd escapes the candidate worktree")
    if _raw_reports(command_id, raw_path, report_suffix, cwd):
        raise EvidenceError(f"{command_id}: candidate-specific raw report already exists")
    executed_argv = _command_argv_for_source(
        command_id, command["argv"], raw_path, report_suffix=report_suffix
    )
    executed_command = trusted.render_command_argv(executed_argv)
    started_at, finished_at, duration, exit_code = process_runner._run_shell(
        executed_argv, cwd, stdout_path, stderr_path
    )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(command_id, raw_path, report_suffix, cwd)
    if command_id == "java_phase_5_deduplicated" and raw_reports:
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
        "cwd": command["cwd"],
        "matrix_command": command["command"],
        "matrix_command_sha256": hashlib.sha256(command["command"].encode("utf-8")).hexdigest(),
        "executed_command": executed_command,
        "executed_argv": executed_argv,
        "executed_command_sha256": hashlib.sha256(executed_command.encode("utf-8")).hexdigest(),
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_seconds": duration,
        "exit_code": exit_code,
        "environment_sha256": environment_sha256,
        "stdout_path": process_runner._relative(stdout_path, run_root),
        "stdout_sha256": _sha256(stdout_path),
        "stderr_path": process_runner._relative(stderr_path, run_root),
        "stderr_sha256": _sha256(stderr_path),
        "raw_reports": [
            {"path": process_runner._relative(path, run_root), "sha256": _sha256(path)}
            for path in raw_reports
        ],
        "accepted": False,
        "failure_classification": "NONE" if exit_code == 0 else "UNCLASSIFIED",
    }
    if exit_code != 0:
        return record, False
    if not raw_reports:
        record.update(exit_code=2, failure_classification="UNCLASSIFIED")
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
        record.update(exit_code=2, failure_classification="UNCLASSIFIED")
        record["failure_reason"] = f"JUnit normalization rejected: {exception}"
        return record, False
    totals = report.totals
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        destination.unlink(missing_ok=True)
        record.update(exit_code=2, failure_classification="UNCLASSIFIED")
        record["failure_reason"] = f"source JUnit is not all-pass zero-skip: {totals}"
        return record, False
    record.update(
        accepted=True,
        report=SOURCE_REPORTS[command_id],
        report_path=process_runner._relative(destination, run_root),
        report_sha256=_sha256(destination),
        tests=totals["tests"],
        failures=totals["failures"],
        errors=totals["errors"],
        skipped=totals["skipped"],
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
    trusted.assert_candidate_run_directory(run_root)
    if resume:
        if not run_root.is_dir():
            raise EvidenceError(f"resume run directory does not exist: {run_root}")
        manifest = _load_resume_manifest(run_root, candidate)
    else:
        if classifications:
            raise EvidenceError("failure classification requires --resume")
        trusted.assert_clean_detached_candidate(candidate)
        if run_root.exists():
            raise EvidenceError(f"candidate run directory already exists: {run_root}")
        run_root.mkdir(parents=True)
        manifest = _initial_manifest(candidate, run_root, environment_id)
        _write_manifest(run_root / MANIFEST_NAME, manifest)
    _assert_candidate_unchanged(candidate, run_root)
    if not _classify_pending_failure(manifest, _classification_map(classifications)):
        _write_manifest(run_root / MANIFEST_NAME, manifest)
        return manifest

    commands = focused_commands()
    accepted = {record["id"] for record in manifest["commands"]}
    environment_sha = manifest["environment"]["snapshot_sha256"]
    for command_id in COMMAND_ORDER:
        if command_id in accepted:
            continue
        record, passed = _record_source(
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=environment_sha,
        )
        if not passed:
            manifest["pending_failure"] = record
            manifest["status"] = "REQUIRES_CLASSIFICATION"
            _write_manifest(run_root / MANIFEST_NAME, manifest)
            return manifest
        manifest["commands"].append(record)
        _write_manifest(run_root / MANIFEST_NAME, manifest)

    manifest["status"] = "PASS"
    manifest["verification_finished_at"] = process_runner._utc_now()
    _assert_candidate_unchanged(candidate, run_root)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan or execute the four deduplicated Phase 5 source groups from one clean "
            "detached candidate SHA."
        )
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id", default="local-phase5-candidate")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--failure-classification",
        action="append",
        default=[],
        metavar="COMMAND_ID=CLASS",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if not arguments.execute:
            if arguments.resume or arguments.failure_classification:
                raise EvidenceError("--resume and classification require --execute")
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
    except (EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 candidate execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                **PENDING_GATES,
            },
            sort_keys=True,
        )
    )
    return 0 if manifest["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
