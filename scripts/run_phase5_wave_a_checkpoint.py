from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Sequence

import yaml

try:
    from scripts import run_phase4_candidate_checkpoint as shared
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase4_candidate_checkpoint as shared  # type: ignore[no-redef]


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
BATCH_ID = "P5-BATCH-1"
MANIFEST_NAME = "phase5-wave-a-execution-manifest.json"
TASK_BINDINGS_NAME = "task-commit-bindings.json"
SCHEMA_VERSION = "phase5-wave-a-execution-manifest.v1"
TASK_BINDINGS_SCHEMA = "phase5-wave-a-task-bindings.v1"
COMMAND_ORDER = (
    "p5_wave_a_python",
    "p5_wave_a_java",
    "p5_wave_a_static",
)
SOURCE_REPORTS = {
    "p5_wave_a_python": "python-phase5-wave-a-junit.xml",
    "p5_wave_a_java": "java-phase5-wave-a-junit.xml",
    "p5_wave_a_static": "static-phase5-wave-a-junit.xml",
}
TASK_REQUIREMENTS = {
    "P5-A1": ("C", ("A1_PYTEST", "A1_CONTRACT_GATE")),
    "P5-A2": ("C", ("A2_PYTEST",)),
    "P5-B1": ("E", ("B1_MAVEN_TEST",)),
    "P5-B2": ("E", ("B2_MAVEN_TEST",)),
    "P5-C1": ("A", ("C1_MAVEN_TEST", "C1_CONTRACT_GATE")),
    "P5-C2": ("A", ("C2_MAVEN_TEST",)),
    "P5-D0": ("R", ("D0_MAVEN_TEST", "D0_VITEST")),
    "P5-E0": ("B", ("E0_STATIC", "E0_CONTRACT_GATE", "E0_MAVEN_TEST")),
}
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}


def load_matrix() -> dict[str, Any]:
    matrix = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    if not isinstance(matrix, dict):
        raise shared.EvidenceError("Phase 5 test matrix must be a YAML object")
    return matrix


def load_source_commands(
    matrix: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    matrix = matrix or load_matrix()
    records = matrix["batches"][BATCH_ID]["source_commands"]
    commands = {record["id"]: record for record in records}
    if tuple(commands) != COMMAND_ORDER:
        raise shared.EvidenceError("P5-BATCH-1 source command order drifted")
    for command_id, record in commands.items():
        if set(("cwd", "command", "report", "resource_class")) - set(record):
            raise shared.EvidenceError(f"{command_id}: incomplete source command")
        if record["report"] != SOURCE_REPORTS[command_id]:
            raise shared.EvidenceError(f"{command_id}: source report name drifted")
    java = commands["p5_wave_a_java"]["command"]
    if java.split().count("-DforkCount=1") != 1:
        raise shared.EvidenceError("P5-BATCH-1 Java must use exactly one forkCount=1 token")
    selectors = [token for token in java.split() if token.startswith("-Dtest=")]
    if len(selectors) != 1:
        raise shared.EvidenceError("P5-BATCH-1 Java must use one Maven selector token")
    forbidden = ("frontend", "playwright", "docker", "real provider", "formal sink")
    command_text = " ".join(item["command"] for item in commands.values()).lower()
    if any(value in command_text for value in forbidden):
        raise shared.EvidenceError("P5-BATCH-1 source commands crossed the T1 boundary")
    return commands


def _matrix_allows_batch1(matrix: dict[str, Any]) -> bool:
    return (
        matrix.get("document_status") == "P5_0_PASS_ENGINEERING_ACTIVE"
        and matrix.get("gate", {}).get("accepted_entry_state", {}).get("batch_0_result")
        == "PASS"
        and matrix.get("waves", {}).get("wave_a", {}).get("status") == "READY"
        and matrix.get("waves", {}).get("wave_b", {}).get("status")
        == "BLOCKED_ON_WAVE_A_INTEGRATION"
        and matrix.get("batches", {}).get(BATCH_ID, {}).get("completed_by_task")
        == "P5-R1"
    )


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    matrix = load_matrix()
    commands = load_source_commands(matrix)
    return {
        "schema_version": "phase5-wave-a-run-plan.v1",
        "phase": 5,
        "batch": BATCH_ID,
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
                "resource_class": commands[command_id]["resource_class"],
            }
            for command_id in COMMAND_ORDER
        ],
        "task_bindings": {
            "schema_version": TASK_BINDINGS_SCHEMA,
            "required_tasks": list(TASK_REQUIREMENTS),
            "external_input_required": True,
        },
        "concurrency": {
            "heavy": 1,
            "light": 2,
            "maven_tokens": 1,
            "maven_fork_count": 1,
            "runner_execution": "sequential",
        },
        "execution_gate": {
            "accepted_wave_a_base_commit": matrix["batches"][BATCH_ID]["execution"][
                "accepted_wave_a_base_commit"
            ],
            "wave_a": matrix["waves"]["wave_a"]["status"],
            "wave_b": matrix["waves"]["wave_b"]["status"],
            "execute_allowed": _matrix_allows_batch1(matrix),
        },
        "runtime_restrictions": {
            "frontend": "none",
            "browser_or_playwright": "none",
            "database": "none",
            "real_provider": "forbidden",
            "formal_evidence_sink": "forbidden",
            "temporal_evidence_allocation": "forbidden",
            "real_case_shadow": "forbidden",
            "promotion": "forbidden",
        },
    }


def _canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def _assert_ancestor(ancestor: str, candidate: str, context: str) -> None:
    process = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, candidate],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if process.returncode:
        raise shared.EvidenceError(f"{context} is not an ancestor of the merged candidate")


def load_task_bindings(
    path: Path,
    candidate_commit: str,
    *,
    check_ancestry: bool = True,
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    resolved = path.resolve()
    if resolved.is_relative_to(ROOT.resolve()):
        raise shared.EvidenceError("task bindings input must remain outside the candidate worktree")
    try:
        document = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot read Wave A task bindings: {exception}") from exception
    _validate_task_bindings_document(document, candidate, check_ancestry=check_ancestry)
    return document


def _validate_task_bindings_document(
    document: Any, candidate: str, *, check_ancestry: bool
) -> None:
    if (
        not isinstance(document, dict)
        or document.get("schema_version") != TASK_BINDINGS_SCHEMA
        or document.get("candidate_commit") != candidate
    ):
        raise shared.EvidenceError("Wave A task bindings schema or candidate drifted")
    tasks = document.get("tasks")
    if not isinstance(tasks, list) or [item.get("id") for item in tasks] != list(
        TASK_REQUIREMENTS
    ):
        raise shared.EvidenceError("Wave A task bindings must contain the exact ordered task set")
    for task in tasks:
        task_id = task["id"]
        reviewer, command_ids = TASK_REQUIREMENTS[task_id]
        commit = shared._assert_candidate(task.get("commit", ""), f"{task_id} commit")
        t0 = task.get("t0")
        if (
            task.get("review_partner") != reviewer
            or task.get("p0_review") != "PASS"
            or not isinstance(t0, dict)
            or t0.get("result") != "PASS"
            or tuple(t0.get("command_ids", ())) != command_ids
        ):
            raise shared.EvidenceError(f"{task_id}: review or T0 binding is incomplete")
        if check_ancestry:
            _assert_ancestor(commit, candidate, f"{task_id} commit {commit}")


def _write_task_bindings(path: Path, document: dict[str, Any]) -> str:
    payload = _canonical_json_bytes(document)
    path.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def _allowed_untracked_root(run_root: Path) -> Path | None:
    return shared._allowed_untracked_root(run_root)


def _assert_candidate_unchanged(candidate: str, run_root: Path) -> None:
    shared.assert_clean_detached_candidate(
        candidate,
        allowed_untracked_root=_allowed_untracked_root(run_root),
    )


def _initial_manifest(
    *,
    candidate: str,
    environment_id: str,
    run_root: Path,
    task_bindings: dict[str, Any],
    task_bindings_sha256: str,
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "phase": 5,
        "batch": BATCH_ID,
        "candidate_commit": candidate,
        "attempt_id": run_root.name,
        "status": "RUNNING",
        "batch_1": "NOT_RUN",
        "wave_a_barrier": "BLOCKED_PENDING_BATCH_1_EVIDENCE_AND_CHECKPOINT",
        "verification_started_at": shared._utc_now(),
        "verification_finished_at": None,
        "environment": shared.capture_environment(environment_id),
        "task_bindings": {
            "path": TASK_BINDINGS_NAME,
            "sha256": task_bindings_sha256,
            "schema_version": TASK_BINDINGS_SCHEMA,
            "tasks": task_bindings["tasks"],
        },
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    shared._write_manifest(path, manifest)


def _command_argv_for_source(
    command_id: str,
    matrix_command: str,
    raw_path: Path,
    *,
    report_suffix: str,
) -> list[str]:
    arguments = shared._split_approved_command(matrix_command)
    if command_id in {"p5_wave_a_python", "p5_wave_a_static"}:
        return [*arguments, f"--junitxml={raw_path.resolve()}"]
    if command_id != "p5_wave_a_java" or arguments[-1:] != ["test"]:
        raise shared.EvidenceError("unknown P5-BATCH-1 source command")
    if not re.fullmatch(r"p5-wa-[0-9a-f]{12}-[0-9a-f]{8}", report_suffix):
        raise shared.EvidenceError("P5-BATCH-1 Surefire report suffix is invalid")
    return [
        *arguments[:-1],
        f"-Dsurefire.reportNameSuffix={report_suffix}",
        "test",
    ]


def _raw_reports(
    command_id: str, raw_path: Path, report_suffix: str, cwd: Path
) -> list[Path]:
    if command_id != "p5_wave_a_java":
        return [raw_path] if raw_path.is_file() else []
    return sorted((cwd / "target/surefire-reports").glob(f"TEST-*-{report_suffix}.xml"))


def _retain_java_reports(
    reports: Sequence[Path], retained_dir: Path
) -> tuple[list[Path], dict[Path, str]]:
    retained_dir.mkdir()
    retained_reports: list[Path] = []
    original_names: dict[Path, str] = {}
    for sequence, report in enumerate(reports, start=1):
        payload = report.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        retained = retained_dir / f"{sequence:04d}-{digest[:16]}.xml"
        retained.write_bytes(payload)
        retained_reports.append(retained)
        original_names[retained] = report.name
    return retained_reports, original_names


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
        f"p5-wa-{candidate[:12]}-"
        f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    cwd = (ROOT / matrix_item["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise shared.EvidenceError(f"{command_id}: matrix cwd escapes the candidate")
    if command_id == "p5_wave_a_java" and _raw_reports(
        command_id, raw_path, report_suffix, cwd
    ):
        raise shared.EvidenceError("candidate-specific Surefire report suffix is not unique")
    executed_argv = _command_argv_for_source(
        command_id,
        matrix_item["command"],
        raw_path,
        report_suffix=report_suffix,
    )
    executed_command = shared.render_command_argv(executed_argv)
    started_at, finished_at, duration, exit_code = shared._run_shell(
        executed_argv, cwd, stdout_path, stderr_path
    )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(command_id, raw_path, report_suffix, cwd)
    original_names: dict[Path, str] = {}
    if command_id == "p5_wave_a_java" and raw_reports:
        raw_reports, original_names = _retain_java_reports(
            raw_reports, attempt_dir / "raw-surefire"
        )
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
        "stdout_path": shared._relative(stdout_path, run_root),
        "stdout_sha256": shared._sha256(stdout_path),
        "stderr_path": shared._relative(stderr_path, run_root),
        "stderr_sha256": shared._sha256(stderr_path),
        "raw_reports": [
            {
                "path": shared._relative(path, run_root),
                "sha256": shared._sha256(path),
                **(
                    {"original_name": original_names[path]}
                    if path in original_names
                    else {}
                ),
            }
            for path in raw_reports
        ],
        "failure_classification": "NONE" if exit_code == 0 else "UNCLASSIFIED",
        "accepted": False,
    }
    if exit_code != 0:
        return record, False
    if not raw_reports:
        record.update(
            exit_code=2,
            failure_classification="UNCLASSIFIED",
            failure_reason="source command produced no JUnit report",
        )
        return record, False
    source_dir = run_root / "source"
    source_dir.mkdir(exist_ok=True)
    destination = source_dir / SOURCE_REPORTS[command_id]
    try:
        report = shared.normalize_source_reports(
            raw_reports,
            destination,
            candidate_commit=candidate,
            command_id=command_id,
        )
    except shared.EvidenceError as exception:
        record.update(
            exit_code=2,
            failure_classification="UNCLASSIFIED",
            failure_reason=f"JUnit normalization rejected: {exception}",
        )
        return record, False
    totals = report.totals
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        destination.unlink(missing_ok=True)
        record.update(
            exit_code=2,
            failure_classification="UNCLASSIFIED",
            failure_reason=f"source JUnit is not all-pass zero-skip: {totals}",
        )
        return record, False
    record.update(
        accepted=True,
        report=SOURCE_REPORTS[command_id],
        report_path=shared._relative(destination, run_root),
        report_sha256=shared._sha256(destination),
        **{field: totals[field] for field in ("tests", "failures", "errors", "skipped")},
    )
    return record, True


def _artifact_path(
    run_root: Path, relative: Any, digest: Any, context: str
) -> Path:
    if not isinstance(relative, str) or not relative:
        raise shared.EvidenceError(f"{context} path is missing")
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()) or not path.is_file():
        raise shared.EvidenceError(f"{context} path is missing or escapes the run directory")
    if not isinstance(digest, str) or shared._sha256(path) != digest:
        raise shared.EvidenceError(f"{context} SHA-256 drifted")
    return path


def _validate_accepted_record(
    record: Any,
    command_id: str,
    candidate: str,
    run_root: Path,
    environment_sha256: str,
) -> None:
    commands = load_source_commands()
    if not isinstance(record, dict) or record.get("id") != command_id:
        raise shared.EvidenceError(f"{command_id}: execution record is invalid")
    matrix_command = commands[command_id]["command"]
    if (
        record.get("candidate_commit") != candidate
        or record.get("cwd") != commands[command_id]["cwd"]
        or record.get("matrix_command") != matrix_command
        or record.get("matrix_command_sha256")
        != hashlib.sha256(matrix_command.encode("utf-8")).hexdigest()
        or record.get("environment_sha256") != environment_sha256
        or record.get("accepted") is not True
        or record.get("exit_code") != 0
        or record.get("failure_classification") != "NONE"
        or record.get("report") != SOURCE_REPORTS[command_id]
        or record.get("report_path") != f"source/{SOURCE_REPORTS[command_id]}"
    ):
        raise shared.EvidenceError(f"{command_id}: accepted execution binding drifted")
    executed_argv = record.get("executed_argv")
    if not isinstance(executed_argv, list) or not all(
        isinstance(item, str) for item in executed_argv
    ):
        raise shared.EvidenceError(f"{command_id}: executed argv is invalid")
    executed_command = shared.render_command_argv(executed_argv)
    if (
        record.get("executed_command") != executed_command
        or record.get("executed_command_sha256")
        != hashlib.sha256(executed_command.encode("utf-8")).hexdigest()
    ):
        raise shared.EvidenceError(f"{command_id}: executed argv binding drifted")
    approved = shared._split_approved_command(matrix_command)
    if command_id in {"p5_wave_a_python", "p5_wave_a_static"}:
        if (
            executed_argv[:-1] != approved
            or not executed_argv[-1].startswith("--junitxml=")
        ):
            raise shared.EvidenceError(f"{command_id}: JUnit injection drifted")
    elif (
        executed_argv[:-2] != approved[:-1]
        or executed_argv[-1] != "test"
        or not re.fullmatch(
            r"-Dsurefire\.reportNameSuffix=p5-wa-[0-9a-f]{12}-[0-9a-f]{8}",
            executed_argv[-2],
        )
    ):
        raise shared.EvidenceError("p5_wave_a_java: Surefire injection drifted")
    for stream in ("stdout", "stderr"):
        _artifact_path(
            run_root,
            record.get(f"{stream}_path"),
            record.get(f"{stream}_sha256"),
            f"{command_id} {stream}",
        )
    raw_reports = record.get("raw_reports")
    if not isinstance(raw_reports, list) or not raw_reports:
        raise shared.EvidenceError(f"{command_id}: raw JUnit records are missing")
    retained: list[Path] = []
    for raw in raw_reports:
        if not isinstance(raw, dict):
            raise shared.EvidenceError(f"{command_id}: raw JUnit record is invalid")
        retained.append(
            _artifact_path(
                run_root,
                raw.get("path"),
                raw.get("sha256"),
                f"{command_id} raw JUnit",
            )
        )
        if command_id == "p5_wave_a_java" and not re.fullmatch(
            r"TEST-.*-p5-wa-[0-9a-f]{12}-[0-9a-f]{8}\.xml",
            str(raw.get("original_name", "")),
        ):
            raise shared.EvidenceError("p5_wave_a_java: original Surefire name drifted")
    if command_id in {"p5_wave_a_python", "p5_wave_a_static"}:
        junit_path = Path(executed_argv[-1].partition("=")[2]).resolve()
        if retained != [junit_path]:
            raise shared.EvidenceError(f"{command_id}: raw JUnit path drifted")
    report_path = _artifact_path(
        run_root,
        record.get("report_path"),
        record.get("report_sha256"),
        f"{command_id} accepted report",
    )
    report = shared.parse_junit(report_path)
    if report.candidate_commit != candidate or report.command_id != command_id:
        raise shared.EvidenceError(f"{command_id}: normalized JUnit binding drifted")
    totals = report.totals
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise shared.EvidenceError(f"{command_id}: normalized JUnit is not all-pass")
    if any(record.get(field) != totals[field] for field in totals if field != "time"):
        raise shared.EvidenceError(f"{command_id}: normalized JUnit totals drifted")


def _validate_failed_record(
    record: Any,
    command_id: str,
    candidate: str,
    run_root: Path,
    environment_sha256: str,
    classification: str,
) -> None:
    commands = load_source_commands()
    if (
        not isinstance(record, dict)
        or record.get("id") != command_id
        or record.get("candidate_commit") != candidate
        or record.get("cwd") != commands[command_id]["cwd"]
        or record.get("matrix_command") != commands[command_id]["command"]
        or record.get("environment_sha256") != environment_sha256
        or record.get("accepted") is not False
        or not isinstance(record.get("exit_code"), int)
        or record["exit_code"] == 0
        or record.get("failure_classification") != classification
    ):
        raise shared.EvidenceError(f"{command_id}: failed execution binding drifted")
    for stream in ("stdout", "stderr"):
        _artifact_path(
            run_root,
            record.get(f"{stream}_path"),
            record.get(f"{stream}_sha256"),
            f"{command_id} failed {stream}",
        )
    raw_reports = record.get("raw_reports")
    if not isinstance(raw_reports, list):
        raise shared.EvidenceError(f"{command_id}: failed raw JUnit records are invalid")
    for raw in raw_reports:
        _artifact_path(
            run_root,
            raw.get("path"),
            raw.get("sha256"),
            f"{command_id} failed raw JUnit",
        )


def _validate_environment(manifest: dict[str, Any]) -> str:
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise shared.EvidenceError("Wave A manifest lacks its environment")
    digest = environment.get("snapshot_sha256")
    unsigned = dict(environment)
    unsigned.pop("snapshot_sha256", None)
    if digest != shared._json_sha256(unsigned):
        raise shared.EvidenceError("Wave A environment seal drifted")
    for dependency in environment.get("dependency_manifests", []):
        path = (ROOT / dependency["path"]).resolve()
        if not path.is_relative_to(ROOT.resolve()) or not path.is_file():
            raise shared.EvidenceError("Wave A dependency path escapes the candidate")
        if dependency.get("sha256") != shared._sha256(path):
            raise shared.EvidenceError("Wave A dependency hash drifted")
    return digest


def _validate_task_binding_archive(
    manifest: dict[str, Any], run_root: Path, candidate: str
) -> None:
    binding = manifest.get("task_bindings")
    if not isinstance(binding, dict) or binding.get("path") != TASK_BINDINGS_NAME:
        raise shared.EvidenceError("Wave A manifest task binding reference drifted")
    path = _artifact_path(
        run_root,
        binding["path"],
        binding.get("sha256"),
        "Wave A task bindings",
    )
    document = json.loads(path.read_text(encoding="utf-8"))
    if binding.get("tasks") != document.get("tasks"):
        raise shared.EvidenceError("Wave A inline task bindings drifted")
    _validate_task_bindings_document(document, candidate, check_ancestry=True)


def _validate_manifest_common(
    manifest: dict[str, Any], run_root: Path, candidate: str
) -> None:
    shared._assert_execution_manifest_seal(manifest)
    if (
        manifest.get("schema_version") != SCHEMA_VERSION
        or manifest.get("phase") != 5
        or manifest.get("batch") != BATCH_ID
        or manifest.get("candidate_commit") != candidate
        or manifest.get("attempt_id") != run_root.name
        or manifest.get("quarantined_attempts_reused") is not False
        or manifest.get("promotion_gate") != "PENDING"
        or manifest.get("MIG-004") != "PENDING_PROMOTION"
        or manifest.get("MIG-005") != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError("Wave A execution manifest identity or gate drifted")
    _validate_task_binding_archive(manifest, run_root, candidate)
    environment_sha256 = _validate_environment(manifest)
    records = manifest.get("commands")
    if not isinstance(records, list) or [item.get("id") for item in records] != list(
        COMMAND_ORDER[: len(records)]
    ):
        raise shared.EvidenceError("Wave A accepted commands are not an ordered prefix")
    for record, command_id in zip(records, COMMAND_ORDER, strict=False):
        _validate_accepted_record(
            record, command_id, candidate, run_root, environment_sha256
        )
    expected_reports = {SOURCE_REPORTS[item["id"]] for item in records}
    source_dir = run_root / "source"
    actual_reports = (
        {path.name for path in source_dir.iterdir() if path.is_file()}
        if source_dir.is_dir()
        else set()
    )
    if actual_reports != expected_reports:
        raise shared.EvidenceError("Wave A accepted source report set drifted")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise shared.EvidenceError("Wave A quarantined attempts are invalid")
    for attempt in quarantined:
        command_id = attempt.get("id") if isinstance(attempt, dict) else None
        if command_id not in SOURCE_REPORTS:
            raise shared.EvidenceError("Wave A quarantine contains an unknown source")
        _validate_failed_record(
            attempt,
            command_id,
            candidate,
            run_root,
            environment_sha256,
            "INFRA",
        )
    pending = manifest.get("pending_failure")
    next_command = (
        COMMAND_ORDER[len(records)] if len(records) < len(COMMAND_ORDER) else None
    )
    if manifest.get("status") == "REQUIRES_CLASSIFICATION":
        if pending is None or next_command is None:
            raise shared.EvidenceError("Wave A classification state lacks its next failure")
        _validate_failed_record(
            pending,
            next_command,
            candidate,
            run_root,
            environment_sha256,
            "UNCLASSIFIED",
        )
    elif pending is not None:
        raise shared.EvidenceError("Wave A manifest retains an unexpected pending failure")


def load_pass_manifest(path: Path, candidate_commit: str) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    resolved = path.resolve()
    if resolved.name != MANIFEST_NAME:
        raise shared.EvidenceError(f"execution manifest must be named {MANIFEST_NAME}")
    try:
        manifest = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot read Wave A manifest: {exception}") from exception
    _validate_manifest_common(manifest, resolved.parent, candidate)
    if (
        manifest.get("status") != "PASS"
        or manifest.get("batch_1") != "PASS_AWAITING_EVIDENCE_COMMIT"
        or manifest.get("wave_a_barrier")
        != "BLOCKED_PENDING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE"
        or manifest.get("verification_finished_at") is None
        or len(manifest["commands"]) != len(COMMAND_ORDER)
        or manifest.get("pending_failure") is not None
    ):
        raise shared.EvidenceError("Wave A manifest is not an evidence-ready PASS")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list) or any(
        item.get("failure_classification") != "INFRA" for item in quarantined
    ):
        raise shared.EvidenceError("Wave A manifest contains a non-INFRA retry")
    return manifest


def _load_resume_manifest(run_root: Path, candidate: str) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot resume Wave A manifest: {exception}") from exception
    _validate_manifest_common(manifest, run_root, candidate)
    if manifest.get("status") not in {"RUNNING", "REQUIRES_CLASSIFICATION"}:
        raise shared.EvidenceError("Wave A manifest is not resumable")
    if manifest.get("verification_finished_at") is not None:
        raise shared.EvidenceError("resumable Wave A manifest is already finished")
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
            or command_id in result
        ):
            raise shared.EvidenceError(
                "failure classification must be one unique COMMAND_ID="
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
            raise shared.EvidenceError("no pending Wave A failure accepts a classification")
        return True
    command_id = pending["id"]
    classification = classifications.get(command_id)
    if classification is None:
        manifest["status"] = "REQUIRES_CLASSIFICATION"
        return False
    if set(classifications) != {command_id}:
        raise shared.EvidenceError("classification names a source without the pending failure")
    pending["failure_classification"] = classification
    manifest["quarantined_attempts"].append(pending)
    manifest["pending_failure"] = None
    if classification != "INFRA":
        manifest["status"] = "CANDIDATE_BLOCKED"
        manifest["verification_finished_at"] = shared._utc_now()
        return False
    manifest["status"] = "RUNNING"
    return True


def execute_checkpoint(
    *,
    candidate_commit: str,
    run_root: Path,
    task_bindings_path: Path,
    environment_id: str,
    resume: bool,
    classifications: Sequence[str],
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    matrix = load_matrix()
    if not _matrix_allows_batch1(matrix):
        raise shared.EvidenceError("P5-BATCH-1 matrix state is not execution-ready")
    _assert_ancestor(
        shared._assert_candidate(
            matrix["batches"][BATCH_ID]["execution"]["accepted_wave_a_base_commit"],
            "accepted Wave A base commit",
        ),
        candidate,
        "accepted Wave A base commit",
    )
    task_bindings = load_task_bindings(task_bindings_path, candidate)
    run_root = run_root.resolve()
    shared.assert_candidate_run_directory(run_root)
    if resume:
        if not run_root.is_dir():
            raise shared.EvidenceError("Wave A resume directory does not exist")
        manifest = _load_resume_manifest(run_root, candidate)
        archived = json.loads((run_root / TASK_BINDINGS_NAME).read_text(encoding="utf-8"))
        if archived != task_bindings:
            raise shared.EvidenceError("resume task bindings differ from the archived binding")
    else:
        shared.assert_clean_detached_candidate(candidate)
        if run_root.exists():
            raise shared.EvidenceError("Wave A run directory already exists")
        run_root.mkdir(parents=True)
        binding_sha256 = _write_task_bindings(
            run_root / TASK_BINDINGS_NAME, task_bindings
        )
        manifest = _initial_manifest(
            candidate=candidate,
            environment_id=environment_id,
            run_root=run_root,
            task_bindings=task_bindings,
            task_bindings_sha256=binding_sha256,
        )
        _write_manifest(run_root / MANIFEST_NAME, manifest)
    _assert_candidate_unchanged(candidate, run_root)
    if not _classify_pending_failure(manifest, _classification_map(classifications)):
        _write_manifest(run_root / MANIFEST_NAME, manifest)
        return manifest
    commands = load_source_commands(matrix)
    accepted = {record["id"] for record in manifest["commands"]}
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
    manifest.update(
        status="PASS",
        batch_1="PASS_AWAITING_EVIDENCE_COMMIT",
        wave_a_barrier="BLOCKED_PENDING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE",
        verification_finished_at=shared._utc_now(),
    )
    _assert_candidate_unchanged(candidate, run_root)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plan or execute P5-BATCH-1 from one clean detached merged SHA."
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--task-bindings", type=Path)
    parser.add_argument("--environment-id", default="local-phase5-wave-a")
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
                raise shared.EvidenceError("--resume and classification require --execute")
            print(json.dumps(candidate_plan(arguments.candidate_commit), indent=2))
            return 0
        if arguments.run_dir is None or arguments.task_bindings is None:
            raise shared.EvidenceError(
                "--run-dir and --task-bindings are required with --execute"
            )
        manifest = execute_checkpoint(
            candidate_commit=arguments.candidate_commit,
            run_root=arguments.run_dir,
            task_bindings_path=arguments.task_bindings,
            environment_id=arguments.environment_id,
            resume=arguments.resume,
            classifications=arguments.failure_classification,
        )
    except (shared.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 Wave A execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "batch_1": manifest["batch_1"],
                "wave_a_barrier": manifest["wave_a_barrier"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                "promotion_gate": manifest["promotion_gate"],
                "MIG-004": manifest["MIG-004"],
                "MIG-005": manifest["MIG-005"],
            },
            sort_keys=True,
        )
    )
    return 0 if manifest["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
