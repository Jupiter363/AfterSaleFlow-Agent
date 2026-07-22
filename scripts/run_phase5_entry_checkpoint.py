from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path, PureWindowsPath
from typing import Any, Sequence

import yaml

try:
    from scripts import run_phase4_candidate_checkpoint as shared
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase4_candidate_checkpoint as shared  # type: ignore[no-redef]


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
BATCH_ID = "P5-BATCH-0"
MANIFEST_NAME = "phase5-entry-execution-manifest.json"
SCHEMA_VERSION = "phase5-entry-execution-manifest.v1"
COMMAND_ORDER = (
    "p5_entry_static",
    "p5_entry_python",
    "p5_entry_java",
    "p5_entry_frontend",
)
FAILURE_CLASSIFICATIONS = {"PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"}
INITIAL_GATE_FIELDS = {
    "batch_0": "NOT_RUN",
    "contract_gate": "P5.0_NOT_PASSED",
    "engineering_execution": "BLOCKED_UNTIL_ENTRY_EVIDENCE_COMMIT",
    "promotion_gate": "PENDING",
    "MIG-004": "PENDING_PROMOTION",
    "MIG-005": "PENDING_PROMOTION",
}


def load_matrix() -> dict[str, Any]:
    matrix = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    if not isinstance(matrix, dict):
        raise shared.EvidenceError("Phase 5 test matrix must be a YAML object")
    return matrix


def load_source_commands(
    matrix: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    matrix = matrix or load_matrix()
    batch = matrix["batches"][BATCH_ID]
    records = batch["source_commands"]
    commands = {record["id"]: record for record in records}
    if tuple(commands) != COMMAND_ORDER:
        raise shared.EvidenceError("P5-BATCH-0 source command order drifted")
    for command_id, record in commands.items():
        if set(("cwd", "command", "report")) - set(record):
            raise shared.EvidenceError(f"{command_id}: incomplete source command")
        report = Path(record["report"])
        if report.name != record["report"] or report.suffix != ".xml":
            raise shared.EvidenceError(f"{command_id}: source report must be one XML filename")
    return commands


SOURCE_REPORTS = {
    command_id: record["report"]
    for command_id, record in load_source_commands().items()
}


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    matrix = load_matrix()
    commands = load_source_commands(matrix)
    gate = matrix["gate"]
    return {
        "schema_version": "phase5-entry-run-plan.v1",
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
                "preflight": commands[command_id].get("preflight"),
            }
            for command_id in COMMAND_ORDER
        ],
        "concurrency": {"heavy": 1, "light": 2, "runner_execution": "sequential"},
        "execution_gate": {
            "document_status": matrix["document_status"],
            "phase_4_engineering_checkpoint": gate["observed_entry_state"][
                "phase_4_engineering_checkpoint"
            ],
            "next_phase_permission": gate["observed_entry_state"][
                "next_phase_permission"
            ],
            "evidence_v2_closed_contract_set": gate["observed_entry_state"][
                "evidence_v2_closed_contract_set"
            ],
            "entry_decision": gate["entry_decision"],
            "execute_allowed": _matrix_allows_batch0(matrix),
        },
        "runtime_restrictions": {
            "formal_evidence_sink": "forbidden",
            "temporal_evidence_allocation": "forbidden",
            "real_case_shadow": "forbidden",
            "production_traffic": "forbidden",
            "promotion": "forbidden",
        },
    }


def _matrix_allows_batch0(matrix: dict[str, Any]) -> bool:
    gate = matrix.get("gate", {})
    observed = gate.get("observed_entry_state", {})
    checkpoint = gate.get("accepted_phase4_checkpoint")
    return (
        matrix.get("document_status")
        == "P5_0_CONTRACT_CANDIDATE_AWAITING_BATCH0"
        and observed.get("phase_4_engineering_checkpoint") == "PASS"
        and observed.get("next_phase_permission") == "PHASE_5_ENGINEERING_ONLY"
        and observed.get("evidence_v2_closed_contract_set") == "FROZEN"
        and gate.get("entry_decision") == "READY_FOR_P5_BATCH_0"
        and gate.get("contract_gate_status") == "NOT_RUN"
        and isinstance(checkpoint, str)
        and checkpoint.endswith("/phase-metrics.json")
    )


def _validate_phase4_checkpoint_document(document: Any) -> dict[str, Any]:
    if not isinstance(document, dict):
        raise shared.EvidenceError("Phase 4 checkpoint must be a JSON object")
    status = document.get("status")
    verification = document.get("candidate_verification")
    if (
        document.get("schema_version") != "temporal-first-phase-metrics.v1"
        or document.get("phase") != 4
        or not isinstance(status, dict)
        or status.get("engineering_checkpoint") != "PASS"
        or status.get("next_phase_permission") != "PHASE_5_ENGINEERING_ONLY"
        or status.get("promotion_gate") != "PENDING"
        or status.get("MIG-004") != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError(
            "Phase 4 checkpoint does not grant PHASE_5_ENGINEERING_ONLY"
        )
    if (
        not isinstance(verification, dict)
        or verification.get("failures") != 0
        or verification.get("errors") != 0
        or verification.get("skipped") != 0
        or verification.get("mixed_candidate_results") is not False
        or verification.get("quarantined_attempts_reused") is not False
    ):
        raise shared.EvidenceError("Phase 4 checkpoint verification is not an accepted PASS")
    phase4_candidate = shared._assert_candidate(
        document.get("candidate_commit", ""), "Phase 4 candidate commit"
    )
    source_manifest = document.get("source_execution_manifest")
    if (
        not isinstance(source_manifest, dict)
        or source_manifest.get("name") != "source-execution-manifest.json"
        or not re.fullmatch(r"[0-9a-f]{64}", str(source_manifest.get("sha256", "")))
    ):
        raise shared.EvidenceError("Phase 4 checkpoint source manifest binding is invalid")
    return {
        "phase4_candidate_commit": phase4_candidate,
        "engineering_checkpoint": "PASS",
        "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "source_execution_manifest_sha256": source_manifest["sha256"],
    }


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if process.returncode:
        raise shared.EvidenceError(
            f"Git checkpoint authentication failed: {process.stderr.decode(errors='replace').strip()}"
        )
    return process.stdout


def _assert_ancestor(ancestor: str, candidate: str, context: str) -> None:
    process = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, candidate],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if process.returncode:
        raise shared.EvidenceError(f"{context} is not an ancestor of the P5 candidate")


def _canonical_text_bytes(path: Path) -> bytes:
    raw = path.read_bytes()
    canonical = raw.replace(b"\r\n", b"\n")
    if b"\r" in canonical:
        raise shared.EvidenceError(
            f"Phase 4 bundle file contains unsupported carriage returns: {path}"
        )
    return canonical


def _bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _assert_git_blob(commit: str, relative: str, expected: bytes, context: str) -> None:
    if _git_bytes("show", f"{commit}:{relative}") != expected:
        raise shared.EvidenceError(f"{context} is not the authenticated Git blob")


def authenticate_phase4_handoff(
    matrix: dict[str, Any], checkpoint_path: Path, candidate_commit: str
) -> dict[str, Any]:
    if not _matrix_allows_batch0(matrix):
        raise shared.EvidenceError(
            "P5 matrix remains BLOCKED/NOT_RECORDED; P5-BATCH-0 execute is forbidden"
        )
    candidate = shared._assert_candidate(candidate_commit)
    checkpoint_input = (
        checkpoint_path if checkpoint_path.is_absolute() else ROOT / checkpoint_path
    ).absolute()
    if checkpoint_input.is_symlink() or not checkpoint_input.is_file():
        raise shared.EvidenceError(
            "Phase 4 checkpoint must be a regular non-symlink file"
        )
    checkpoint = checkpoint_input.resolve()
    if not checkpoint.is_relative_to(ROOT.resolve()) or checkpoint.name != "phase-metrics.json":
        raise shared.EvidenceError(
            "Phase 4 checkpoint must be a repository phase-metrics.json"
        )
    relative = checkpoint.relative_to(ROOT.resolve()).as_posix()
    if relative != matrix["gate"]["accepted_phase4_checkpoint"]:
        raise shared.EvidenceError(
            "Phase 4 checkpoint path differs from the frozen P5 contract candidate"
        )
    try:
        document = json.loads(checkpoint.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot read Phase 4 checkpoint: {exception}") from exception
    authenticated = _validate_phase4_checkpoint_document(document)
    expected_phase4_candidate = shared._assert_candidate(
        matrix["gate"].get("accepted_phase4_candidate", ""),
        "P5 matrix accepted Phase 4 candidate",
    )
    if authenticated["phase4_candidate_commit"] != expected_phase4_candidate:
        raise shared.EvidenceError(
            "Phase 4 checkpoint candidate differs from the frozen P5 contract candidate"
        )
    candidate_file = checkpoint.parent / "candidate-commit.txt"
    source_manifest = checkpoint.parent / "source-execution-manifest.json"
    bundle_paths = (checkpoint, candidate_file, source_manifest)
    if any(path.is_symlink() or not path.is_file() for path in bundle_paths):
        raise shared.EvidenceError(
            "Phase 4 checkpoint bundle must contain regular non-symlink files"
        )
    candidate_bytes = _canonical_text_bytes(candidate_file)
    if candidate_bytes.decode("utf-8") != authenticated["phase4_candidate_commit"] + "\n":
        raise shared.EvidenceError("Phase 4 candidate-commit.txt drifted")
    source_raw = source_manifest.read_bytes()
    source_canonical = _canonical_text_bytes(source_manifest)
    source_crlf = source_canonical.replace(b"\n", b"\r\n")
    recorded_source_hash = authenticated["source_execution_manifest_sha256"]
    if recorded_source_hash not in {
        _bytes_sha256(source_raw),
        _bytes_sha256(source_canonical),
        _bytes_sha256(source_crlf),
    }:
        raise shared.EvidenceError("Phase 4 source execution manifest SHA-256 drifted")
    bundle = {
        relative: _canonical_text_bytes(checkpoint),
        candidate_file.relative_to(ROOT.resolve()).as_posix(): candidate_bytes,
        source_manifest.relative_to(ROOT.resolve()).as_posix(): source_canonical,
    }
    for bundle_path, expected in bundle.items():
        _assert_git_blob(
            candidate,
            bundle_path,
            expected,
            f"Phase 4 bundle file {bundle_path} in the P5 candidate",
        )
    evidence_commit = (
        _git_bytes("log", "-1", "--format=%H", candidate, "--", relative)
        .decode("ascii")
        .strip()
    )
    evidence_commit = shared._assert_candidate(evidence_commit, "Phase 4 evidence commit")
    expected_evidence_commit = shared._assert_candidate(
        matrix["gate"].get("accepted_phase4_evidence_commit", ""),
        "P5 matrix accepted Phase 4 evidence commit",
    )
    if evidence_commit != expected_evidence_commit:
        raise shared.EvidenceError(
            "Phase 4 evidence commit differs from the frozen P5 contract candidate"
        )
    if evidence_commit == authenticated["phase4_candidate_commit"]:
        raise shared.EvidenceError(
            "Phase 4 candidate and evidence commit must be separate commits"
        )
    for bundle_path, expected in bundle.items():
        _assert_git_blob(
            evidence_commit,
            bundle_path,
            expected,
            f"Phase 4 bundle file {bundle_path} in the evidence commit",
        )
    _assert_ancestor(
        authenticated["phase4_candidate_commit"],
        evidence_commit,
        "Phase 4 candidate before its evidence commit",
    )
    _assert_ancestor(evidence_commit, candidate, "Phase 4 evidence commit")
    return {
        "checkpoint_path": relative,
        "checkpoint_sha256": _bytes_sha256(bundle[relative]),
        "candidate_commit_file_sha256": _bytes_sha256(candidate_bytes),
        "source_execution_manifest_git_sha256": _bytes_sha256(source_canonical),
        "evidence_commit": evidence_commit,
        **authenticated,
    }


def _validate_embedded_handoff(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise shared.EvidenceError("manifest lacks its authenticated Phase 4 checkpoint")
    required = {
        "checkpoint_path",
        "checkpoint_sha256",
        "candidate_commit_file_sha256",
        "evidence_commit",
        "phase4_candidate_commit",
        "engineering_checkpoint",
        "next_phase_permission",
        "promotion_gate",
        "MIG-004",
        "source_execution_manifest_sha256",
        "source_execution_manifest_git_sha256",
    }
    if set(value) != required:
        raise shared.EvidenceError("manifest Phase 4 checkpoint fields drifted")
    for field in (
        "checkpoint_sha256",
        "candidate_commit_file_sha256",
        "source_execution_manifest_sha256",
        "source_execution_manifest_git_sha256",
    ):
        if not re.fullmatch(r"[0-9a-f]{64}", str(value.get(field, ""))):
            raise shared.EvidenceError(f"manifest Phase 4 {field} is invalid")
    shared._assert_candidate(value.get("evidence_commit", ""), "Phase 4 evidence commit")
    shared._assert_candidate(
        value.get("phase4_candidate_commit", ""), "Phase 4 candidate commit"
    )
    if (
        value.get("engineering_checkpoint") != "PASS"
        or value.get("next_phase_permission") != "PHASE_5_ENGINEERING_ONLY"
        or value.get("promotion_gate") != "PENDING"
        or value.get("MIG-004") != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError("manifest Phase 4 permission drifted")
    return value


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    shared.seal_execution_manifest(manifest)
    payload = (
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_bytes(payload)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _assert_candidate_unchanged(candidate: str, run_root: Path) -> None:
    shared.assert_clean_detached_candidate(
        candidate,
        allowed_untracked_root=shared._allowed_untracked_root(run_root),
    )


def _initial_manifest(
    *,
    candidate: str,
    environment_id: str,
    run_root: Path,
    phase4_handoff: dict[str, Any],
) -> dict[str, Any]:
    authenticated_handoff = dict(_validate_embedded_handoff(phase4_handoff))
    environment = shared.capture_environment(environment_id)
    environment["upstream_phase4_checkpoint"] = authenticated_handoff
    environment.pop("snapshot_sha256", None)
    environment["snapshot_sha256"] = shared._json_sha256(environment)
    return {
        "schema_version": SCHEMA_VERSION,
        "phase": 5,
        "batch": BATCH_ID,
        "candidate_commit": candidate,
        "attempt_id": run_root.name,
        "status": "RUNNING",
        "verification_started_at": shared._utc_now(),
        "verification_finished_at": None,
        "environment": environment,
        "upstream_phase4_checkpoint": authenticated_handoff,
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
        **INITIAL_GATE_FIELDS,
    }


def _validate_environment(manifest: dict[str, Any]) -> str:
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise shared.EvidenceError("resume manifest lacks its execution environment")
    snapshot_sha256 = environment.get("snapshot_sha256")
    unsigned = dict(environment)
    unsigned.pop("snapshot_sha256", None)
    if snapshot_sha256 != shared._json_sha256(unsigned):
        raise shared.EvidenceError("resume environment snapshot SHA-256 drifted")
    dependencies = environment.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise shared.EvidenceError("resume environment lacks dependency manifest hashes")
    for dependency in dependencies:
        if not isinstance(dependency, dict) or not isinstance(dependency.get("path"), str):
            raise shared.EvidenceError("resume dependency record is invalid")
        path = (ROOT / dependency["path"]).resolve()
        if not path.is_relative_to(ROOT.resolve()) or not path.is_file():
            raise shared.EvidenceError("resume dependency path escapes the candidate")
        if dependency.get("sha256") != shared._sha256(path):
            raise shared.EvidenceError("resume dependency SHA-256 drifted")
    if not isinstance(snapshot_sha256, str):
        raise shared.EvidenceError("resume environment SHA-256 is missing")
    return snapshot_sha256


def _validate_executed_argv(
    record: dict[str, Any],
    *,
    command_id: str,
    matrix_command: str,
    run_root: Path,
) -> Path | None:
    executed_argv = record.get("executed_argv")
    if not isinstance(executed_argv, list) or not all(
        isinstance(value, str) for value in executed_argv
    ):
        raise shared.EvidenceError(f"{command_id}: executed argv is invalid")
    matrix_argv = shared._split_approved_command(matrix_command)
    junit_output: Path | None = None
    if command_id in {"p5_entry_static", "p5_entry_python"}:
        if executed_argv[:-1] != matrix_argv or not executed_argv[-1].startswith(
            "--junitxml="
        ):
            raise shared.EvidenceError(f"{command_id}: executed argv drifted")
        junit_output = Path(executed_argv[-1].partition("=")[2]).resolve()
    elif command_id == "p5_entry_frontend":
        if executed_argv[:-2] != matrix_argv or executed_argv[-2] != "--reporter=junit":
            raise shared.EvidenceError(f"{command_id}: executed argv drifted")
        if not executed_argv[-1].startswith("--outputFile="):
            raise shared.EvidenceError(f"{command_id}: JUnit output argv drifted")
        junit_output = Path(executed_argv[-1].partition("=")[2]).resolve()
    elif command_id == "p5_entry_java":
        if executed_argv[:-2] != matrix_argv[:-1] or executed_argv[-1] != "test":
            raise shared.EvidenceError(f"{command_id}: executed argv drifted")
        suffix = executed_argv[-2]
        if not re.fullmatch(
            r"-Dsurefire\.reportNameSuffix=p5-entry-[0-9a-f]{12}-[0-9a-f]{8}",
            suffix,
        ):
            raise shared.EvidenceError(f"{command_id}: Surefire suffix drifted")
    else:
        raise shared.EvidenceError(f"unknown Phase 5 entry source {command_id}")
    if junit_output is not None:
        if (
            not junit_output.is_relative_to(run_root.resolve())
            or junit_output.name != "raw-junit.xml"
            or not junit_output.parent.name.startswith(f"{command_id}-")
        ):
            raise shared.EvidenceError(f"{command_id}: JUnit output escapes its attempt")
    executed = record.get("executed_command")
    if not isinstance(executed, str) or executed != shared.render_command_argv(
        executed_argv
    ):
        raise shared.EvidenceError(f"{command_id}: executed command drifted")
    if record.get("executed_command_sha256") != hashlib.sha256(
        executed.encode("utf-8")
    ).hexdigest():
        raise shared.EvidenceError(f"{command_id}: executed command SHA-256 drifted")
    return junit_output


def _portable_report_name_key(name: str) -> bytes:
    return name.encode("utf-8")


def _validate_record(
    record: Any,
    *,
    command_id: str,
    candidate: str,
    run_root: Path,
    command: dict[str, Any],
    environment_sha256: str,
    verification_started: Any,
    accepted: bool,
) -> None:
    expected_command = command["command"]
    if not isinstance(record, dict) or record.get("id") != command_id:
        raise shared.EvidenceError(f"{command_id}: resume record identity drifted")
    if (
        record.get("candidate_commit") != candidate
        or record.get("cwd") != command["cwd"]
        or record.get("matrix_command") != expected_command
        or record.get("matrix_command_sha256")
        != hashlib.sha256(expected_command.encode("utf-8")).hexdigest()
        or record.get("environment_sha256") != environment_sha256
    ):
        raise shared.EvidenceError(f"{command_id}: resume record binding drifted")
    junit_output = _validate_executed_argv(
        record,
        command_id=command_id,
        matrix_command=expected_command,
        run_root=run_root,
    )
    started = shared._timestamp(record.get("started_at"), f"{command_id}.started_at")
    finished = shared._timestamp(record.get("finished_at"), f"{command_id}.finished_at")
    duration = record.get("duration_seconds")
    if (
        started < verification_started
        or finished < started
        or not isinstance(duration, (int, float))
        or isinstance(duration, bool)
        or duration < 0
    ):
        raise shared.EvidenceError(f"{command_id}: invalid resume record timeline")
    wall_duration = (finished - started).total_seconds()
    if abs(float(duration) - wall_duration) > max(5.0, wall_duration * 0.05):
        raise shared.EvidenceError(f"{command_id}: duration is not bound to timestamps")
    stream_paths = {}
    for stream in ("stdout", "stderr"):
        stream_paths[stream] = shared._artifact_path(
            run_root,
            record.get(f"{stream}_path"),
            record.get(f"{stream}_sha256"),
            f"{command_id} {stream}",
        )
    raw_reports = record.get("raw_reports")
    if not isinstance(raw_reports, list):
        raise shared.EvidenceError(f"{command_id}: raw JUnit records are invalid")
    retained: set[Path] = set()
    retained_order: list[Path] = []
    has_original_names: list[bool] = []
    for raw in raw_reports:
        if not isinstance(raw, dict):
            raise shared.EvidenceError(f"{command_id}: raw JUnit record is invalid")
        has_original_name = "original_name" in raw
        if command_id != "p5_entry_java" and has_original_name:
            raise shared.EvidenceError(
                f"{command_id}: non-Java raw JUnit has an original name"
            )
        expected_raw_fields = {"path", "sha256"}
        if command_id == "p5_entry_java" and has_original_name:
            expected_raw_fields.add("original_name")
        if set(raw) != expected_raw_fields:
            raise shared.EvidenceError(
                f"{command_id}: raw JUnit fields drifted"
            )
        has_original_names.append(has_original_name)
        retained_path = shared._artifact_path(
            run_root,
            raw.get("path"),
            raw.get("sha256"),
            f"{command_id} raw JUnit",
        )
        if retained_path in retained:
            raise shared.EvidenceError(f"{command_id}: duplicate raw JUnit path")
        retained.add(retained_path)
        retained_order.append(retained_path)
    strict_java_raw = command_id == "p5_entry_java"
    if strict_java_raw:
        attempt_dir = stream_paths["stdout"].parent
        if (
            stream_paths["stderr"].parent != attempt_dir
            or not re.fullmatch(r"p5_entry_java-[0-9]{2,}", attempt_dir.name)
        ):
            raise shared.EvidenceError(
                "p5_entry_java: retained stream attempt directory drifted"
            )
        executed_argv = record["executed_argv"]
        report_suffix = executed_argv[-2].partition("=")[2]
        expected_suffix = (
            f"p5-entry-{candidate[:12]}-"
            f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
        )
        if report_suffix != expected_suffix:
            raise shared.EvidenceError(
                "p5_entry_java: current Surefire report suffix drifted"
            )
        if raw_reports and not all(has_original_names):
            raise shared.EvidenceError(
                "p5_entry_java: retained Surefire original name is missing"
            )
        original_names = [raw.get("original_name") for raw in raw_reports]
        if (
            any(not isinstance(name, str) for name in original_names)
            or len(set(original_names)) != len(original_names)
            or original_names
            != sorted(original_names, key=_portable_report_name_key)
        ):
            raise shared.EvidenceError(
                "p5_entry_java: retained Surefire original names are not unique and sorted"
            )
        retained_dir = attempt_dir / "raw-surefire"
        for sequence, (raw, retained_path) in enumerate(
            zip(raw_reports, retained_order, strict=True), start=1
        ):
            original_name = raw["original_name"]
            if (
                "/" in original_name
                or "\\" in original_name
                or not original_name.startswith("TEST-")
                or not original_name.endswith(f"-{report_suffix}.xml")
            ):
                raise shared.EvidenceError(
                    "p5_entry_java: retained Surefire original name drifted"
                )
            expected_name = f"{sequence:04d}-{raw['sha256'][:16]}.xml"
            if retained_path.parent != retained_dir or retained_path.name != expected_name:
                raise shared.EvidenceError(
                    "p5_entry_java: retained Surefire short path drifted"
                )
    if accepted:
        if (
            record.get("accepted") is not True
            or record.get("exit_code") != 0
            or record.get("failure_classification") != "NONE"
            or record.get("report") != SOURCE_REPORTS[command_id]
            or not retained
            or (junit_output is not None and junit_output not in retained)
        ):
            raise shared.EvidenceError(f"{command_id}: accepted resume record is invalid")
        if record.get("report_path") != f"source/{SOURCE_REPORTS[command_id]}":
            raise shared.EvidenceError(f"{command_id}: accepted report path drifted")
        report_path = shared._artifact_path(
            run_root,
            record.get("report_path"),
            record.get("report_sha256"),
            f"{command_id} accepted report",
        )
        report = shared.parse_junit(report_path)
        if report.candidate_commit != candidate or report.command_id != command_id:
            raise shared.EvidenceError(f"{command_id}: accepted report binding drifted")
        totals = report.totals
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise shared.EvidenceError(f"{command_id}: accepted report is not all-pass")
        for field in ("tests", "failures", "errors", "skipped"):
            if record.get(field) != totals[field]:
                raise shared.EvidenceError(f"{command_id}: accepted totals drifted")
    elif (
        record.get("accepted") is not False
        or not isinstance(record.get("exit_code"), int)
        or record["exit_code"] == 0
    ):
        raise shared.EvidenceError(f"{command_id}: failed resume record is invalid")


def _validate_resume_manifest(
    manifest: dict[str, Any],
    run_root: Path,
    candidate: str,
    phase4_handoff: dict[str, Any],
) -> None:
    if (
        manifest.get("schema_version") != SCHEMA_VERSION
        or manifest.get("phase") != 5
        or manifest.get("batch") != BATCH_ID
    ):
        raise shared.EvidenceError("resume manifest is not a Phase 5 entry manifest")
    if manifest.get("candidate_commit") != candidate:
        raise shared.EvidenceError("resume manifest belongs to a different candidate SHA")
    if manifest.get("attempt_id") != run_root.name:
        raise shared.EvidenceError("resume manifest attempt ID drifted")
    if manifest.get("status") not in {"RUNNING", "REQUIRES_CLASSIFICATION"}:
        raise shared.EvidenceError("resume manifest is not resumable")
    if manifest.get("verification_finished_at") is not None:
        raise shared.EvidenceError("resumable manifest already has a finish time")
    if manifest.get("quarantined_attempts_reused") is not False:
        raise shared.EvidenceError("resume manifest reused a quarantined attempt")
    for field, expected in INITIAL_GATE_FIELDS.items():
        if manifest.get(field) != expected:
            raise shared.EvidenceError(f"resume manifest pre-finish {field} drifted")
    embedded_handoff = _validate_embedded_handoff(
        manifest.get("upstream_phase4_checkpoint")
    )
    if embedded_handoff != phase4_handoff:
        raise shared.EvidenceError("resume Phase 4 checkpoint binding drifted")
    environment_sha256 = _validate_environment(manifest)
    if manifest["environment"].get("upstream_phase4_checkpoint") != phase4_handoff:
        raise shared.EvidenceError("resume environment Phase 4 checkpoint drifted")
    verification_started = shared._timestamp(
        manifest.get("verification_started_at"), "verification_started_at"
    )
    commands = load_source_commands()
    accepted_records = manifest.get("commands")
    if not isinstance(accepted_records, list) or len(accepted_records) > len(COMMAND_ORDER):
        raise shared.EvidenceError("resume accepted command list is invalid")
    record_ids = [record.get("id") if isinstance(record, dict) else None for record in accepted_records]
    if record_ids != list(COMMAND_ORDER[: len(record_ids)]):
        raise shared.EvidenceError("resume commands are not the ordered source prefix")
    for record, command_id in zip(accepted_records, record_ids, strict=True):
        _validate_record(
            record,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=environment_sha256,
            verification_started=verification_started,
            accepted=True,
        )
    pending = manifest.get("pending_failure")
    if manifest["status"] == "REQUIRES_CLASSIFICATION":
        if len(accepted_records) >= len(COMMAND_ORDER):
            raise shared.EvidenceError(
                "resume manifest cannot retain a failure after every source passed"
            )
        next_id = COMMAND_ORDER[len(accepted_records)]
        _validate_record(
            pending,
            command_id=next_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[next_id],
            environment_sha256=environment_sha256,
            verification_started=verification_started,
            accepted=False,
        )
        if pending.get("failure_classification") != "UNCLASSIFIED":
            raise shared.EvidenceError("pending failure was already classified")
    elif pending is not None:
        raise shared.EvidenceError("RUNNING resume manifest retains a pending failure")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise shared.EvidenceError("resume quarantined attempts are invalid")
    for record in quarantined:
        command_id = record.get("id") if isinstance(record, dict) else None
        if command_id not in commands or record.get("failure_classification") != "INFRA":
            raise shared.EvidenceError("resume quarantined attempt is not classified INFRA")
        _validate_record(
            record,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=environment_sha256,
            verification_started=verification_started,
            accepted=False,
        )


def _load_resume_manifest(
    run_root: Path, candidate: str, phase4_handoff: dict[str, Any]
) -> dict[str, Any]:
    path = run_root / MANIFEST_NAME
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot resume Phase 5 entry manifest: {exception}") from exception
    shared._assert_execution_manifest_seal(manifest)
    _validate_resume_manifest(manifest, run_root, candidate, phase4_handoff)
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
            raise shared.EvidenceError(
                "failure classification must be COMMAND_ID="
                + "|".join(sorted(FAILURE_CLASSIFICATIONS))
            )
        if command_id in result:
            raise shared.EvidenceError(f"duplicate failure classification for {command_id}")
        result[command_id] = classification
    return result


def _classify_pending_failure(
    manifest: dict[str, Any], classifications: dict[str, str]
) -> bool:
    pending = manifest.get("pending_failure")
    if not pending:
        if classifications:
            raise shared.EvidenceError("no pending source failure accepts a classification")
        return True
    command_id = pending["id"]
    classification = classifications.get(command_id)
    if not classification:
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


def _command_argv_for_source(
    command_id: str,
    matrix_command: str,
    raw_path: Path,
    *,
    report_suffix: str,
) -> list[str]:
    arguments = shared._split_approved_command(matrix_command)
    if command_id in {"p5_entry_static", "p5_entry_python"}:
        return [*arguments, f"--junitxml={raw_path.resolve()}"]
    if command_id == "p5_entry_frontend":
        return [*arguments, "--reporter=junit", f"--outputFile={raw_path.resolve()}"]
    if command_id != "p5_entry_java" or arguments[-1:] != ["test"]:
        raise shared.EvidenceError("P5 entry Java command must end in the Maven test goal")
    if not re.fullmatch(r"p5-entry-[0-9a-f]{12}-[0-9a-f]{8}", report_suffix):
        raise shared.EvidenceError("P5 entry Java report suffix is invalid")
    return [
        *arguments[:-1],
        f"-Dsurefire.reportNameSuffix={report_suffix}",
        "test",
    ]


def _preflight_failure(command_id: str, command: dict[str, Any]) -> str | None:
    preflight = command.get("preflight")
    if not preflight:
        return None
    if preflight.get("missing_classification") != "INFRA":
        raise shared.EvidenceError(f"{command_id}: preflight must fail as INFRA")
    required_value = preflight.get("required_path")
    if not isinstance(required_value, str) or not required_value:
        raise shared.EvidenceError(f"{command_id}: preflight path is invalid")
    required_path = Path(required_value)
    windows_path = PureWindowsPath(required_value)
    if (
        required_path.is_absolute()
        or required_path.drive
        or required_path.root
        or windows_path.is_absolute()
        or windows_path.drive
        or windows_path.root
        or windows_path.anchor
        or not required_path.parts
        or ".." in required_path.parts
        or ".." in windows_path.parts
    ):
        raise shared.EvidenceError(f"{command_id}: preflight path escapes the candidate")
    # Keep the path lexical here. Windows dependency junctions intentionally resolve
    # outside the detached worktree, while the authenticated matrix still controls the
    # candidate-relative location that may be checked.
    path = ROOT / required_path
    if not path.is_file():
        return (
            f"missing required path {required_value}; classify INFRA, preserve "
            "this attempt, restore the exact lockfile dependency tree, then resume the same SHA"
        )
    return None


def _raw_reports(
    command_id: str, raw_path: Path, report_suffix: str, cwd: Path
) -> list[Path]:
    if command_id != "p5_entry_java":
        return [raw_path] if raw_path.is_file() else []
    return sorted(
        (cwd / "target/surefire-reports").glob(f"TEST-*-{report_suffix}.xml"),
        key=lambda report: _portable_report_name_key(report.name),
    )


def _retain_java_reports(
    reports: Sequence[Path], retained_dir: Path
) -> tuple[list[Path], dict[Path, str], str | None]:
    try:
        retained_dir.mkdir()
    except OSError as exception:
        return [], {}, f"cannot create retained Surefire directory: {exception}"
    retained_reports: list[Path] = []
    original_names: dict[Path, str] = {}
    for sequence, report in enumerate(reports, start=1):
        retained: Path | None = None
        try:
            payload = report.read_bytes()
            digest = hashlib.sha256(payload).hexdigest()
            retained = retained_dir / f"{sequence:04d}-{digest[:16]}.xml"
            with retained.open("xb") as stream:
                stream.write(payload)
        except OSError as exception:
            if retained is not None:
                try:
                    retained.unlink(missing_ok=True)
                except OSError:
                    pass
            return (
                retained_reports,
                original_names,
                f"cannot retain Surefire report {report.name}: {exception}",
            )
        retained_reports.append(retained)
        original_names[retained] = report.name
    return retained_reports, original_names, None


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
        f"p5-entry-{candidate[:12]}-"
        f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    cwd = (ROOT / matrix_item["cwd"]).resolve()
    if not cwd.is_dir() or not cwd.is_relative_to(ROOT.resolve()):
        raise shared.EvidenceError(f"{command_id}: matrix cwd escapes the candidate")
    if command_id == "p5_entry_java" and _raw_reports(
        command_id, raw_path, report_suffix, cwd
    ):
        raise shared.EvidenceError(
            "candidate-specific Surefire report suffix is not unique"
        )
    executed_argv = _command_argv_for_source(
        command_id,
        matrix_item["command"],
        raw_path,
        report_suffix=report_suffix,
    )
    executed_command = shared.render_command_argv(executed_argv)
    preflight_failure = _preflight_failure(command_id, matrix_item)
    if preflight_failure:
        started_at = finished_at = shared._utc_now()
        duration = 0.0
        exit_code = 3
        stdout_path.write_text("", encoding="utf-8")
        stderr_path.write_text(preflight_failure + "\n", encoding="utf-8")
    else:
        started_at, finished_at, duration, exit_code = shared._run_shell(
            executed_argv, cwd, stdout_path, stderr_path
        )
    _assert_candidate_unchanged(candidate, run_root)
    raw_reports = _raw_reports(command_id, raw_path, report_suffix, cwd)
    raw_report_original_names: dict[Path, str] = {}
    retention_failure: str | None = None
    if command_id == "p5_entry_java" and raw_reports:
        raw_reports, raw_report_original_names, retention_failure = (
            _retain_java_reports(raw_reports, attempt_dir / "raw-surefire")
        )
    raw_report_records = []
    for path in raw_reports:
        raw_report = {
            "path": shared._relative(path, run_root),
            "sha256": shared._sha256(path),
        }
        original_name = raw_report_original_names.get(path)
        if original_name is not None:
            raw_report["original_name"] = original_name
        raw_report_records.append(raw_report)
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
        "raw_reports": raw_report_records,
        "failure_classification": "NONE" if exit_code == 0 else "UNCLASSIFIED",
        "accepted": False,
    }
    if preflight_failure:
        record["failure_reason"] = preflight_failure
    if retention_failure:
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = retention_failure
        return record, False
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
        report = shared.normalize_source_reports(
            raw_reports,
            destination,
            candidate_commit=candidate,
            command_id=command_id,
        )
    except shared.EvidenceError as exception:
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = f"JUnit normalization rejected: {exception}"
        return record, False
    totals = report.totals
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        destination.unlink(missing_ok=True)
        record["exit_code"] = 2
        record["failure_classification"] = "UNCLASSIFIED"
        record["failure_reason"] = f"source JUnit is not all-pass zero-skip: {totals}"
        return record, False
    record.update(
        {
            "accepted": True,
            "report": SOURCE_REPORTS[command_id],
            "report_path": shared._relative(destination, run_root),
            "report_sha256": shared._sha256(destination),
            **totals,
        }
    )
    return record, True


def execute_checkpoint(
    *,
    candidate_commit: str,
    run_root: Path,
    environment_id: str,
    phase4_checkpoint_path: Path,
    resume: bool,
    classifications: Sequence[str],
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    matrix = load_matrix()
    phase4_handoff = authenticate_phase4_handoff(
        matrix, phase4_checkpoint_path, candidate
    )
    run_root = run_root.resolve()
    shared.assert_candidate_run_directory(run_root)
    if resume:
        if not run_root.is_dir():
            raise shared.EvidenceError(f"resume run directory does not exist: {run_root}")
        manifest = _load_resume_manifest(run_root, candidate, phase4_handoff)
    else:
        shared.assert_clean_detached_candidate(candidate)
        if run_root.exists():
            raise shared.EvidenceError(f"candidate run directory already exists: {run_root}")
        run_root.mkdir(parents=True)
        manifest = _initial_manifest(
            candidate=candidate,
            environment_id=environment_id,
            run_root=run_root,
            phase4_handoff=phase4_handoff,
        )
        _write_manifest(run_root / MANIFEST_NAME, manifest)
    _assert_candidate_unchanged(candidate, run_root)
    if not _classify_pending_failure(manifest, _classification_map(classifications)):
        _write_manifest(run_root / MANIFEST_NAME, manifest)
        return manifest

    commands = load_source_commands(matrix)
    accepted = {entry["id"] for entry in manifest["commands"]}
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
        {
            "status": "PASS",
            "batch_0": "PASS",
            "contract_gate": "P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT",
            "engineering_execution": "BLOCKED_UNTIL_ENTRY_EVIDENCE_COMMIT",
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
        }
    )
    manifest["verification_finished_at"] = shared._utc_now()
    _assert_candidate_unchanged(candidate, run_root)
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan or execute P5-BATCH-0 from one exact clean detached contract-candidate SHA."
        )
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id", default="local-phase5-entry")
    parser.add_argument(
        "--phase4-checkpoint",
        type=Path,
        help="Tracked accepted Phase 4 phase-metrics.json; required with --execute.",
    )
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
        if arguments.run_dir is None:
            raise shared.EvidenceError("--run-dir is required with --execute")
        if arguments.phase4_checkpoint is None:
            raise shared.EvidenceError("--phase4-checkpoint is required with --execute")
        manifest = execute_checkpoint(
            candidate_commit=arguments.candidate_commit,
            run_root=arguments.run_dir,
            environment_id=arguments.environment_id,
            phase4_checkpoint_path=arguments.phase4_checkpoint,
            resume=arguments.resume,
            classifications=arguments.failure_classification,
        )
    except (shared.EvidenceError, OSError, KeyError, TypeError) as exception:
        print(f"Phase 5 entry execution rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": manifest["candidate_commit"],
                "status": manifest["status"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                "contract_gate": manifest.get("contract_gate", "P5.0_NOT_PASSED"),
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
