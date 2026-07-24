from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Sequence

try:
    from scripts import run_phase7_entry_checkpoint as runner
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase7_entry_checkpoint as runner  # type: ignore[no-redef]


ROOT = Path(__file__).resolve().parents[1]
METRICS_NAME = "entry-metrics.json"
CANDIDATE_NAME = "candidate.txt"
HASH_INDEX_NAME = "artifact-sha256.json"
ATTRIBUTES_NAME = ".gitattributes"
EVIDENCE_SCHEMA = "phase7-entry-evidence.v1"
HASH_INDEX_SCHEMA = "phase7-entry-artifact-index.v1"
ENTRY_EFFECT = "P7_0_ENGINEERING_ENTRY_PASS"
POST_EVIDENCE_PERMISSION = "PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS"
ATTRIBUTES_BYTES = b"* -text\n**/* -text\n"


def _json_lf_bytes(document: Any) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def _write_json_lf(path: Path, document: Any) -> None:
    path.write_bytes(_json_lf_bytes(document))


def _load_json(path: Path, context: str) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise runner.EvidenceError(f"cannot read {context} {path}: {exception}") from exception
    if not isinstance(document, dict):
        raise runner.EvidenceError(f"{context} must be a JSON object")
    return document


def _release_id(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,79}", value):
        raise argparse.ArgumentTypeError(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _git_hash_object(payload: bytes, *, logical_path: str | None = None) -> str:
    command = ["git", "hash-object"]
    command.append("--no-filters" if logical_path is None else f"--path={logical_path}")
    command.append("--stdin")
    process = subprocess.run(
        command, cwd=ROOT, input=payload, capture_output=True, check=False
    )
    output = process.stdout.decode("ascii", errors="replace").strip()
    if process.returncode or not re.fullmatch(r"[0-9a-f]{40,64}", output):
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise runner.EvidenceError(
            f"cannot apply Git clean filter for Phase 7 evidence: {error or output}"
        )
    return output


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", *arguments], cwd=ROOT, capture_output=True, check=False
    )
    if process.returncode:
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise runner.EvidenceError(f"git {' '.join(arguments)} failed: {error}")
    return process.stdout


def _git_text(*arguments: str) -> str:
    return _git_bytes(*arguments).decode("utf-8", errors="strict")


def _assert_git_clean_filter_stable(
    path: Path, *, release_id: str, output_dir: Path, require_lf: bool
) -> None:
    payload = path.read_bytes()
    if require_lf and b"\r" in payload:
        raise runner.EvidenceError(
            f"Phase 7 entry evidence artifact {path.name} contains non-LF line endings"
        )
    try:
        logical = path.resolve().relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        # External test output is not a Git path; the committed path is rechecked by
        # verify_evidence_commit and the bundle still carries its byte-preserving rules.
        return
    if _git_hash_object(payload) != _git_hash_object(payload, logical_path=logical):
        raise runner.EvidenceError(
            f"Phase 7 entry artifact {path.name} changes under Git clean filters"
        )


def assert_contract_only_candidate(base_commit: str, candidate_commit: str) -> list[str]:
    base = runner._assert_candidate(base_commit, "base commit")
    if base != runner.PHASE6_ACCEPTANCE_COMMIT:
        raise runner.EvidenceError("Phase 7 contract candidate base must be the exact accepted A6")
    return runner.assert_contract_only_candidate(candidate_commit)


def _archive_manifest(
    source: Path, destination: Path, expected_manifest: dict[str, Any]
) -> str:
    current = _load_json(source, "Phase 7 entry execution manifest")
    if current != expected_manifest:
        raise runner.EvidenceError(
            "Phase 7 entry execution manifest changed after green authentication"
        )
    runner._assert_execution_manifest_seal(current)
    payload = _json_lf_bytes(current)
    destination.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def _provenance_paths(manifest: dict[str, Any]) -> list[str]:
    values: list[str] = []
    commands = manifest.get("commands")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(commands, list) or not isinstance(quarantined, list):
        raise runner.EvidenceError("execution provenance command lists are invalid")
    records = [*commands, *quarantined]
    for record in records:
        if not isinstance(record, dict):
            raise runner.EvidenceError("execution provenance record must be an object")
        for field in ("stdout_path", "stderr_path"):
            value = record.get(field)
            if not isinstance(value, str):
                raise runner.EvidenceError(f"execution provenance {field} is missing")
            values.append(value)
        raw_reports = record.get("raw_reports")
        if not isinstance(raw_reports, list):
            raise runner.EvidenceError("execution provenance raw_reports is invalid")
        for raw in raw_reports:
            if not isinstance(raw, dict) or not isinstance(raw.get("path"), str):
                raise runner.EvidenceError("execution provenance raw report path is invalid")
            values.append(raw["path"])
    normalized: list[str] = []
    seen: set[str] = set()
    for value in values:
        path = Path(value.replace("\\", "/"))
        normalized_value = path.as_posix()
        if (
            path.is_absolute()
            or ".." in path.parts
            or not normalized_value.startswith("attempts/")
            or normalized_value in seen
        ):
            raise runner.EvidenceError(
                f"execution provenance path escapes, duplicates, or is not attempt-bound: {value}"
            )
        seen.add(normalized_value)
        normalized.append(normalized_value)
    return normalized


def _indexed_names(manifest: dict[str, Any]) -> list[str]:
    return [
        ATTRIBUTES_NAME,
        CANDIDATE_NAME,
        runner.MANIFEST_NAME,
        *runner.SOURCE_REPORTS.values(),
        METRICS_NAME,
        *_provenance_paths(manifest),
    ]


def _copy_provenance_artifact(
    *, run_root: Path, output_dir: Path, relative: str
) -> None:
    normalized = relative.replace("\\", "/")
    lexical = PurePosixPath(normalized)
    if (
        not normalized
        or lexical.is_absolute()
        or lexical.anchor
        or any(part in {"", ".", ".."} or ":" in part for part in lexical.parts)
    ):
        raise runner.EvidenceError(
            f"execution provenance path is not a safe lexical relative path: {relative}"
        )

    def lstat_no_reparse(path: Path, *, context: str) -> os.stat_result:
        try:
            metadata = path.lstat()
        except OSError as exception:
            raise runner.EvidenceError(f"cannot lstat {context} {path}: {exception}") from exception
        attributes = int(getattr(metadata, "st_file_attributes", 0))
        reparse_flag = int(getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
        junction_check = getattr(path, "is_junction", None)
        try:
            junction = bool(junction_check()) if callable(junction_check) else False
        except OSError as exception:
            raise runner.EvidenceError(
                f"cannot authenticate junction state for {context} {path}: {exception}"
            ) from exception
        if stat.S_ISLNK(metadata.st_mode) or junction or attributes & reparse_flag:
            raise runner.EvidenceError(f"{context} is a link, junction, or reparse point: {path}")
        return metadata

    source_root_lexical = run_root.absolute()
    source_root_metadata = lstat_no_reparse(
        source_root_lexical, context="provenance source root"
    )
    if not stat.S_ISDIR(source_root_metadata.st_mode):
        raise runner.EvidenceError("execution provenance source root is not a directory")
    source_root = source_root_lexical.resolve(strict=True)
    source_lexical = source_root_lexical
    for part in lexical.parts[:-1]:
        source_lexical /= part
        metadata = lstat_no_reparse(source_lexical, context="provenance source parent")
        if not stat.S_ISDIR(metadata.st_mode):
            raise runner.EvidenceError(
                f"execution provenance source parent is not a directory: {source_lexical}"
            )
    source_lexical /= lexical.parts[-1]
    source_metadata = lstat_no_reparse(
        source_lexical, context="provenance source artifact"
    )
    if not stat.S_ISREG(source_metadata.st_mode):
        raise runner.EvidenceError(
            f"execution provenance source is not a regular file: {relative}"
        )
    source = source_lexical.resolve(strict=True)
    if not source.is_relative_to(source_root):
        raise runner.EvidenceError(f"execution provenance source escapes run root: {relative}")

    destination_root_lexical = output_dir.absolute()
    destination_root_metadata = lstat_no_reparse(
        destination_root_lexical, context="provenance destination root"
    )
    if not stat.S_ISDIR(destination_root_metadata.st_mode):
        raise runner.EvidenceError("execution provenance destination root is not a directory")
    destination_root = destination_root_lexical.resolve(strict=True)
    destination_parent = destination_root_lexical
    for part in lexical.parts[:-1]:
        destination_parent /= part
        try:
            destination_parent.mkdir()
        except FileExistsError:
            pass
        metadata = lstat_no_reparse(
            destination_parent, context="provenance destination parent"
        )
        if not stat.S_ISDIR(metadata.st_mode):
            raise runner.EvidenceError(
                f"execution provenance destination parent is not a directory: {destination_parent}"
            )
    if not destination_parent.resolve(strict=True).is_relative_to(destination_root):
        raise runner.EvidenceError(
            f"execution provenance destination parent escapes output root: {relative}"
        )
    destination = destination_parent / lexical.parts[-1]
    try:
        destination.lstat()
    except FileNotFoundError:
        pass
    except OSError as exception:
        raise runner.EvidenceError(
            f"cannot authenticate provenance destination {relative}: {exception}"
        ) from exception
    else:
        raise runner.EvidenceError(
            f"execution provenance destination already exists: {relative}"
        )

    read_flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    write_flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        source_descriptor = os.open(source_lexical, read_flags)
    except OSError as exception:
        raise runner.EvidenceError(
            f"cannot open provenance source without following links {relative}: {exception}"
        ) from exception
    try:
        opened_source = os.fstat(source_descriptor)
        if (
            not stat.S_ISREG(opened_source.st_mode)
            or (opened_source.st_dev, opened_source.st_ino)
            != (source_metadata.st_dev, source_metadata.st_ino)
        ):
            raise runner.EvidenceError(
                f"execution provenance source changed during authentication: {relative}"
            )
        try:
            destination_descriptor = os.open(destination, write_flags, 0o600)
        except OSError as exception:
            raise runner.EvidenceError(
                f"cannot exclusively create provenance destination {relative}: {exception}"
            ) from exception
        try:
            opened_destination = os.fstat(destination_descriptor)
            if not stat.S_ISREG(opened_destination.st_mode):
                raise runner.EvidenceError(
                    f"execution provenance destination is not regular: {relative}"
                )
            with os.fdopen(source_descriptor, "rb", closefd=False) as source_stream:
                with os.fdopen(destination_descriptor, "wb", closefd=False) as destination_stream:
                    shutil.copyfileobj(source_stream, destination_stream, length=1024 * 1024)
                    destination_stream.flush()
                    os.fsync(destination_descriptor)
        finally:
            os.close(destination_descriptor)
    finally:
        os.close(source_descriptor)
    destination_metadata = lstat_no_reparse(
        destination, context="provenance destination artifact"
    )
    if not stat.S_ISREG(destination_metadata.st_mode):
        raise runner.EvidenceError(
            f"execution provenance destination is not a regular file: {relative}"
        )


def load_green_manifest(
    execution_manifest_path: Path, candidate_commit: str
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    path = execution_manifest_path.resolve()
    if path.name != runner.MANIFEST_NAME:
        raise runner.EvidenceError(
            f"execution manifest must be named {runner.MANIFEST_NAME}"
        )
    manifest = _load_json(path, "Phase 7 entry execution manifest")
    runner._assert_execution_manifest_seal(manifest)
    if manifest.get("schema_version") != runner.SCHEMA_VERSION or manifest.get("phase") != 7:
        raise runner.EvidenceError("execution manifest is not Phase 7 Batch 0")
    if manifest.get("candidate_commit") != candidate:
        raise runner.EvidenceError("execution manifest candidate SHA drifted")
    if (
        manifest.get("status") != runner.GREEN_STATUS
        or manifest.get("contract_gate") != "P7.0_NOT_RUN"
        or manifest.get("implementation_authorized") is not False
    ):
        raise runner.EvidenceError(
            "execution manifest is not green awaiting the separate entry evidence commit"
        )
    if (
        manifest.get("accepted_phase_6_checkpoint_sha") != runner.PHASE6_ACCEPTANCE_COMMIT
        or manifest.get("engineering_exception") != runner.ADR_TOKEN
        or any(
            manifest.get(migration) != "PENDING_PROMOTION"
            for migration in runner.MIGRATION_GATES
        )
    ):
        raise runner.EvidenceError("execution manifest authority or migration gate drifted")
    if (
        manifest.get("pending_failure") is not None
        or manifest.get("quarantined_attempts_reused") is not False
    ):
        raise runner.EvidenceError("execution manifest recovery state drifted")
    concurrency = manifest.get("concurrency")
    if concurrency != {
        "runner_execution": "sequential",
        "maximum_heavy_processes": 1,
        "maximum_light_processes": 2,
    }:
        raise runner.EvidenceError("execution manifest concurrency controls drifted")

    run_root = path.parent
    runner.assert_candidate_run_directory(run_root)
    if manifest.get("attempt_id") != run_root.name:
        raise runner.EvidenceError("execution manifest belongs to another run directory")
    records = manifest.get("commands")
    if not isinstance(records, list) or [
        record.get("id") if isinstance(record, dict) else None for record in records
    ] != list(runner.COMMAND_ORDER):
        raise runner.EvidenceError("execution manifest lacks the exact four-command green set")
    runner._validate_resume_manifest(manifest, run_root, candidate)
    for record in records:
        if (
            record.get("exit_code") != 0
            or record.get("failure_classification") != "NONE"
            or record.get("accepted") is not True
        ):
            raise runner.EvidenceError("accepted source record state drifted")

    source_dir = run_root / "source"
    expected_reports = set(runner.SOURCE_REPORTS.values())
    if not source_dir.is_dir() or {
        item.name for item in source_dir.iterdir() if item.is_file()
    } != expected_reports:
        raise runner.EvidenceError("source report directory is incomplete or contains extras")
    if any(not item.is_file() or item.is_symlink() for item in source_dir.iterdir()):
        raise runner.EvidenceError("source report directory contains a non-regular artifact")
    return manifest


def _source_metrics(
    manifest: dict[str, Any], source_dir: Path
) -> tuple[list[dict[str, Any]], dict[str, int | float]]:
    rows: list[dict[str, Any]] = []
    totals: dict[str, int | float] = {
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.0,
    }
    for record in manifest["commands"]:
        report_path = source_dir / record["report"]
        report = runner.parse_junit(report_path)
        if (
            report.candidate_commit != manifest["candidate_commit"]
            or report.command_id != record["id"]
        ):
            raise runner.EvidenceError(f"{record['id']}: normalized report binding drifted")
        junit = report.totals
        if not junit["tests"] or junit["failures"] or junit["errors"] or junit["skipped"]:
            raise runner.EvidenceError(f"{record['id']}: normalized report is not zero-skip green")
        rows.append(
            {
                "command_id": record["id"],
                "candidate_commit": record["candidate_commit"],
                "environment_sha256": record["environment_sha256"],
                "cwd": record["cwd"],
                "resource_class": record["resource_class"],
                "matrix_command_sha256": record["matrix_command_sha256"],
                "executed_command_sha256": record["executed_command_sha256"],
                "started_at": record["started_at"],
                "finished_at": record["finished_at"],
                "duration_seconds": record["duration_seconds"],
                "exit_code": record["exit_code"],
                "failure_classification": record["failure_classification"],
                "raw_report_count": record["raw_report_count"],
                "expected_report_count": record["expected_report_count"],
                "selected_test_file_count": record["selected_test_file_count"],
                "minimum_tests": record["minimum_tests"],
                "report": record["report"],
                "report_sha256": runner._sha256(report_path),
                "report_bytes": report_path.stat().st_size,
                "junit": junit,
            }
        )
        for field in ("tests", "failures", "errors", "skipped"):
            totals[field] = int(totals[field]) + int(junit[field])
        totals["time"] = round(float(totals["time"]) + float(junit["time"]), 6)
    return rows, totals


def _validate_bundle(
    *,
    output_dir: Path,
    candidate: str,
    manifest: dict[str, Any],
    metrics: dict[str, Any],
    index: dict[str, Any],
    release_id: str,
) -> None:
    expected = {
        HASH_INDEX_NAME,
        *_indexed_names(manifest),
    }
    entries = list(output_dir.rglob("*"))
    artifacts = [item for item in entries if item.is_file()]
    relatives = {item.relative_to(output_dir).as_posix() for item in artifacts}
    if relatives != expected or any(item.is_symlink() for item in entries):
        raise runner.EvidenceError("Phase 7 entry evidence output file set drifted")
    for item in artifacts:
        relative = item.relative_to(output_dir).as_posix()
        _assert_git_clean_filter_stable(
            item,
            release_id=release_id,
            output_dir=output_dir,
            require_lf=relative
            in {
                ATTRIBUTES_NAME,
                HASH_INDEX_NAME,
                CANDIDATE_NAME,
                runner.MANIFEST_NAME,
                METRICS_NAME,
                *runner.SOURCE_REPORTS.values(),
            },
        )
    if (output_dir / ATTRIBUTES_NAME).read_bytes() != ATTRIBUTES_BYTES:
        raise runner.EvidenceError("Phase 7 evidence byte-preservation attributes drifted")
    if (output_dir / CANDIDATE_NAME).read_bytes() != (candidate + "\n").encode("ascii"):
        raise runner.EvidenceError("Phase 7 entry candidate binding drifted")
    archived = _load_json(output_dir / runner.MANIFEST_NAME, "archived manifest")
    if archived != manifest:
        raise runner.EvidenceError("archived execution manifest changed")
    runner._assert_execution_manifest_seal(archived)
    if _load_json(output_dir / METRICS_NAME, "entry metrics") != metrics:
        raise runner.EvidenceError("entry metrics changed during assembly")
    if _load_json(output_dir / HASH_INDEX_NAME, "artifact index") != index:
        raise runner.EvidenceError("artifact index changed during assembly")
    indexed_names = [artifact.get("path") for artifact in index.get("artifacts", [])]
    if indexed_names != _indexed_names(manifest):
        raise runner.EvidenceError("Phase 7 artifact index order or coverage drifted")
    for artifact in index["artifacts"]:
        path = output_dir / artifact["path"]
        if (
            runner._sha256(path) != artifact["sha256"]
            or path.stat().st_size != artifact["bytes"]
        ):
            raise runner.EvidenceError(f"entry artifact {artifact['path']} drifted")


def assemble_entry_evidence(
    *,
    manifest: dict[str, Any],
    execution_manifest_path: Path,
    output_dir: Path,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    changed_paths: Sequence[str],
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    base = runner._assert_candidate(base_commit, "base commit")
    run_root = execution_manifest_path.resolve().parent
    source_dir = run_root / "source"
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / ATTRIBUTES_NAME).write_bytes(ATTRIBUTES_BYTES)
    (output_dir / CANDIDATE_NAME).write_bytes((candidate + "\n").encode("ascii"))
    archived_manifest_sha256 = _archive_manifest(
        execution_manifest_path, output_dir / runner.MANIFEST_NAME, manifest
    )
    for record in manifest["commands"]:
        source = source_dir / record["report"]
        if runner._sha256(source) != record["report_sha256"]:
            raise runner.EvidenceError(f"accepted report {record['report']} drifted")
        shutil.copyfile(source, output_dir / record["report"])
    for relative in _provenance_paths(manifest):
        _copy_provenance_artifact(
            run_root=run_root,
            output_dir=output_dir,
            relative=relative,
        )

    source_suites, totals = _source_metrics(manifest, output_dir)
    metrics = {
        "schema_version": EVIDENCE_SCHEMA,
        "release_id": release_id,
        "gate": "P7.0",
        "result": "PASS_AWAITING_EVIDENCE_COMMIT",
        "candidate_commit": candidate,
        "base_commit": base,
        "candidate_scope": {
            "classification": "CONTRACT_ONLY",
            "changed_path_count": len(changed_paths),
            "changed_paths": list(changed_paths),
        },
        "execution_manifest": {
            "path": runner.MANIFEST_NAME,
            "sha256": archived_manifest_sha256,
            "manifest_sha256": manifest["manifest_sha256"],
            "schema_version": manifest["schema_version"],
        },
        "execution_environment": {
            "environment_id": manifest["environment"]["environment_id"],
            "snapshot_sha256": manifest["environment"]["snapshot_sha256"],
        },
        "verification": {
            "started_at": manifest["verification_started_at"],
            "finished_at": manifest["verification_finished_at"],
            "clean_detached_candidate_required": True,
            "mixed_candidate_results": False,
            "mixed_attempt_results": False,
            "source_reports_reused_from_other_run": False,
            "heavy_processes_at_most": 1,
            "light_processes_at_most": 2,
        },
        "source_suites": source_suites,
        "totals": totals,
        "recovery": {
            "quarantined_attempt_count": len(manifest["quarantined_attempts"]),
            "classifications": [
                record["failure_classification"]
                for record in manifest["quarantined_attempts"]
            ],
            "unclassified_attempts": 0,
            "quarantined_attempts_reused": False,
        },
        "execution_provenance": {
            "archived": True,
            "artifact_count": len(_provenance_paths(manifest)),
            "paths": _provenance_paths(manifest),
        },
        "entry_decision": {
            "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
            "entry_effect_after_commit": ENTRY_EFFECT,
            "next_phase_permission_after_commit": POST_EVIDENCE_PERMISSION,
            "engineering_implementation_after_commit": "ALLOWED_UNDER_ADR_0016_ONLY",
            "evidence_commit_requirement": "DIRECT_CHILD_OF_CANDIDATE",
            "implementation_allowed_before_commit": False,
            "promotion_gate": "PENDING",
            "MIG-006": "PENDING_PROMOTION",
            "MIG-007": "PENDING_PROMOTION",
        },
        "runtime_restrictions": {
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
        },
    }
    _write_json_lf(output_dir / METRICS_NAME, metrics)
    indexed = _indexed_names(manifest)
    index = {
        "schema_version": HASH_INDEX_SCHEMA,
        "candidate_commit": candidate,
        "artifacts": [
            {
                "path": name,
                "sha256": runner._sha256(output_dir / name),
                "bytes": (output_dir / name).stat().st_size,
            }
            for name in indexed
        ],
    }
    _write_json_lf(output_dir / HASH_INDEX_NAME, index)
    _validate_bundle(
        output_dir=output_dir,
        candidate=candidate,
        manifest=manifest,
        metrics=metrics,
        index=index,
        release_id=release_id,
    )
    return metrics


def _committed_json(evidence_commit: str, path: str, context: str) -> dict[str, Any]:
    try:
        document = json.loads(_git_bytes("show", f"{evidence_commit}:{path}"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise runner.EvidenceError(f"cannot decode committed {context}: {exception}") from exception
    if not isinstance(document, dict):
        raise runner.EvidenceError(f"committed {context} must be a JSON object")
    return document


def _junit_summary(payload: bytes, context: str) -> tuple[list[tuple[str, str, str, float]], dict[str, int | float]]:
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exception:
        raise runner.EvidenceError(f"{context}: committed JUnit is invalid: {exception}") from exception
    local = lambda tag: tag.rsplit("}", 1)[-1]
    if local(root.tag) not in {"testsuite", "testsuites"}:
        raise runner.EvidenceError(f"{context}: committed JUnit root is invalid")
    fingerprints: list[tuple[str, str, str, float]] = []
    seen_identities: set[tuple[str, str]] = set()
    failures = errors = skipped = 0
    for case in (element for element in root.iter() if local(element.tag) == "testcase"):
        classname = case.get("classname")
        name = case.get("name")
        if not classname or not name:
            raise runner.EvidenceError(f"{context}: committed JUnit testcase identity is missing")
        identity = (classname, name)
        if identity in seen_identities:
            raise runner.EvidenceError(f"{context}: committed JUnit duplicates a testcase")
        seen_identities.add(identity)
        child_names = {local(child.tag) for child in case}
        if child_names & {"flakyFailure", "flakyError", "rerunFailure", "rerunError"}:
            raise runner.EvidenceError(f"{context}: committed JUnit contains retry/flake outcomes")
        status = "passed"
        if "failure" in child_names:
            failures += 1
            status = "failure"
        elif "error" in child_names:
            errors += 1
            status = "error"
        elif "skipped" in child_names:
            skipped += 1
            status = "skipped"
        try:
            duration = float(case.get("time", "0") or "0")
        except ValueError as exception:
            raise runner.EvidenceError(f"{context}: committed JUnit time is invalid") from exception
        if duration < 0 or not math.isfinite(duration):
            raise runner.EvidenceError(f"{context}: committed JUnit time is negative or non-finite")
        fingerprints.append((classname, name, status, duration))
    if not fingerprints:
        raise runner.EvidenceError(f"{context}: committed JUnit is empty")
    totals: dict[str, int | float] = {
        "tests": len(fingerprints),
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "time": round(sum(item[3] for item in fingerprints), 6),
    }
    for field in ("tests", "failures", "errors", "skipped"):
        declared = root.get(field)
        if declared is not None:
            try:
                if int(declared) != totals[field]:
                    raise runner.EvidenceError(
                        f"{context}: committed JUnit declared {field} drifted"
                    )
            except ValueError as exception:
                raise runner.EvidenceError(
                    f"{context}: committed JUnit declared {field} is invalid"
                ) from exception
    return fingerprints, totals


def _committed_junit_totals(
    payload: bytes, *, candidate: str, command_id: str, minimum_tests: int
) -> dict[str, int | float]:
    _, totals = _junit_summary(payload, command_id)
    root = ET.fromstring(payload)
    if (
        root.attrib.get("candidate_commit") != candidate
        or root.attrib.get("source_command_id") != command_id
    ):
        raise runner.EvidenceError(f"{command_id}: committed JUnit binding drifted")
    if (
        totals["tests"] < minimum_tests
        or totals["failures"]
        or totals["errors"]
        or totals["skipped"]
    ):
        raise runner.EvidenceError(f"{command_id}: committed JUnit is not zero-skip green")
    return totals


def _assert_approved_argv(
    *,
    command_id: str,
    contract: dict[str, Any],
    executed_argv: Any,
    expected_suffix: str,
    raw_records: Sequence[dict[str, Any]],
    raw_output_relative: str | None = None,
) -> None:
    if (
        not isinstance(executed_argv, list)
        or any(not isinstance(argument, str) or not argument for argument in executed_argv)
    ):
        raise runner.EvidenceError(f"{command_id}: committed argv is invalid")
    approved_argv = runner._split_approved_command(contract["command"])
    if len(approved_argv) != len(executed_argv):
        raise runner.EvidenceError(f"{command_id}: committed argv length drifted")
    raw_argument_value: str | None = None
    for position, approved in enumerate(approved_argv):
        actual = executed_argv[position]
        if "{raw_report}" in approved:
            before, after = approved.split("{raw_report}")
            if not actual.startswith(before) or (after and not actual.endswith(after)):
                raise runner.EvidenceError(f"{command_id}: committed raw report argv drifted")
            raw_argument_value = actual[
                len(before) : len(actual) - len(after) if after else None
            ]
        elif "{report_suffix}" in approved:
            if actual != approved.replace("{report_suffix}", expected_suffix):
                raise runner.EvidenceError(f"{command_id}: committed report suffix argv drifted")
        elif position == 0 and command_id == "java_phase7_entry":
            wrapper = PureWindowsPath(actual)
            if (
                not wrapper.is_absolute()
                or wrapper.name.lower() != "mvnw.cmd"
                or wrapper.parent.name != "java-api-service"
            ):
                raise runner.EvidenceError(f"{command_id}: committed Maven wrapper argv drifted")
        elif actual != approved:
            raise runner.EvidenceError(f"{command_id}: committed argv drifted")
    if raw_argument_value is not None:
        expected_value = raw_output_relative
        if expected_value is None and len(raw_records) == 1:
            value = raw_records[0].get("path")
            expected_value = value if isinstance(value, str) else None
        if expected_value is None:
            raise runner.EvidenceError(f"{command_id}: committed raw output binding drifted")
        expected_raw = expected_value.replace("/", "\\").lower()
        normalized_actual = raw_argument_value.replace("/", "\\").lower()
        if not normalized_actual.endswith(expected_raw):
            raise runner.EvidenceError(f"{command_id}: committed raw output path drifted")


def verify_evidence_commit(
    *, evidence_commit: str, candidate_commit: str, release_id: str
) -> dict[str, Any]:
    evidence = runner._assert_candidate(evidence_commit, "evidence commit")
    candidate = runner._assert_candidate(candidate_commit)
    runner._assert_upstream_authority(candidate)
    candidate_paths = assert_contract_only_candidate(
        runner.PHASE6_ACCEPTANCE_COMMIT, candidate
    )
    parent_record = _git_text("rev-list", "--parents", "-n", "1", evidence).strip().split()
    if len(parent_record) != 2 or parent_record != [evidence, candidate]:
        raise runner.EvidenceError(
            "Phase 7 evidence commit must have the candidate as its sole parent"
        )

    prefix = f"test-reports/temporal-first/{release_id}/phase-7-entry"
    manifest = _committed_json(
        evidence, f"{prefix}/{runner.MANIFEST_NAME}", "execution manifest"
    )
    runner._assert_execution_manifest_seal(manifest)
    expected_names = {HASH_INDEX_NAME, *_indexed_names(manifest)}
    expected_paths = {f"{prefix}/{name}" for name in expected_names}
    records = [
        line
        for line in _git_text(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "-r",
            "--no-renames",
            evidence,
        ).splitlines()
        if line.strip()
    ]
    changed_paths: set[str] = set()
    for record in records:
        fields = record.split("\t")
        if len(fields) != 2 or fields[0] != "A":
            raise runner.EvidenceError(
                "Phase 7 evidence commit may only add its immutable evidence bundle"
            )
        changed_paths.add(fields[1].replace("\\", "/"))
    if changed_paths != expected_paths:
        missing = sorted(expected_paths - changed_paths)
        extra = sorted(changed_paths - expected_paths)
        raise runner.EvidenceError(
            f"Phase 7 evidence commit file set drifted; missing={missing}, extra={extra}"
        )

    blobs = {
        name: _git_bytes("show", f"{evidence}:{prefix}/{name}")
        for name in expected_names
    }
    if blobs[CANDIDATE_NAME] != (candidate + "\n").encode("ascii"):
        raise runner.EvidenceError("committed Phase 7 candidate.txt binding drifted")
    if blobs[ATTRIBUTES_NAME] != ATTRIBUTES_BYTES:
        raise runner.EvidenceError("committed evidence byte-preservation attributes drifted")
    primary_lf = {
        ATTRIBUTES_NAME,
        HASH_INDEX_NAME,
        CANDIDATE_NAME,
        runner.MANIFEST_NAME,
        METRICS_NAME,
        *runner.SOURCE_REPORTS.values(),
    }
    if any(b"\r" in blobs[name] for name in primary_lf):
        raise runner.EvidenceError("committed primary Phase 7 evidence contains non-LF bytes")
    expected_manifest_keys = {
        "schema_version",
        "phase",
        "candidate_commit",
        "accepted_phase_6_checkpoint_sha",
        "engineering_exception",
        "attempt_id",
        "run_root",
        "status",
        "contract_gate",
        "implementation_authorized",
        "MIG-006",
        "MIG-007",
        "verification_started_at",
        "verification_finished_at",
        "environment",
        "concurrency",
        "commands",
        "quarantined_attempts",
        "pending_failure",
        "quarantined_attempts_reused",
        "manifest_sha256",
    }
    if set(manifest) != expected_manifest_keys:
        raise runner.EvidenceError("committed execution manifest field set drifted")
    if (
        manifest.get("schema_version") != runner.SCHEMA_VERSION
        or manifest.get("phase") != 7
        or manifest.get("candidate_commit") != candidate
        or manifest.get("status") != runner.GREEN_STATUS
        or manifest.get("contract_gate") != "P7.0_NOT_RUN"
        or manifest.get("implementation_authorized") is not False
        or manifest.get("accepted_phase_6_checkpoint_sha")
        != runner.PHASE6_ACCEPTANCE_COMMIT
        or manifest.get("engineering_exception") != runner.ADR_TOKEN
        or manifest.get("pending_failure") is not None
        or manifest.get("quarantined_attempts_reused") is not False
        or manifest.get("concurrency")
        != {
            "runner_execution": "sequential",
            "maximum_heavy_processes": 1,
            "maximum_light_processes": 2,
        }
        or any(
            manifest.get(migration) != "PENDING_PROMOTION"
            for migration in runner.MIGRATION_GATES
        )
    ):
        raise runner.EvidenceError("committed Phase 7 execution manifest gate drifted")
    run_root_value = manifest.get("run_root")
    if not isinstance(run_root_value, str) or not Path(run_root_value).is_absolute():
        raise runner.EvidenceError("committed execution manifest run_root is not absolute")
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise runner.EvidenceError("committed execution environment is missing")
    environment_digest = environment.get("snapshot_sha256")
    unsigned_environment = dict(environment)
    unsigned_environment.pop("snapshot_sha256", None)
    if environment_digest != runner._json_sha256(unsigned_environment):
        raise runner.EvidenceError("committed execution environment seal drifted")
    dependencies = environment.get("dependency_manifests")
    expected_dependencies = [
        "python-agent-service/pyproject.toml",
        "python-agent-service/requirements.lock",
        "java-api-service/pom.xml",
        "java-api-service/.mvn/wrapper/maven-wrapper.properties",
        "frontend/package.json",
        "frontend/pnpm-lock.yaml",
        "docker-compose.yml",
    ]
    if (
        not isinstance(dependencies, list)
        or [item.get("path") if isinstance(item, dict) else None for item in dependencies]
        != expected_dependencies
    ):
        raise runner.EvidenceError("committed execution dependencies are missing")
    for dependency in dependencies:
        if not isinstance(dependency, dict) or not isinstance(dependency.get("path"), str):
            raise runner.EvidenceError("committed execution dependency record is invalid")
        payload = _git_bytes("show", f"{candidate}:{dependency['path']}")
        if (
            set(dependency) != {"path", "sha256", "byte_source"}
            or dependency.get("byte_source") != "CANDIDATE_GIT_BLOB"
            or dependency.get("sha256") != hashlib.sha256(payload).hexdigest()
        ):
            raise runner.EvidenceError(
                f"committed dependency {dependency['path']} binding drifted"
            )
    command_ids = [
        record.get("id") if isinstance(record, dict) else None
        for record in manifest.get("commands", [])
    ]
    if command_ids != list(runner.COMMAND_ORDER):
        raise runner.EvidenceError("committed execution manifest source order drifted")
    candidate_matrix = runner._matrix_at_candidate(candidate)
    contracts = runner._source_contracts(candidate_matrix)
    accepted_record_keys = {
        "id",
        "candidate_commit",
        "cwd",
        "resource_class",
        "expected_report_count",
        "selected_test_file_count",
        "minimum_tests",
        "matrix_command",
        "matrix_command_sha256",
        "executed_command",
        "executed_argv",
        "executed_command_sha256",
        "report_suffix",
        "started_at",
        "finished_at",
        "duration_seconds",
        "exit_code",
        "environment_sha256",
        "stdout_path",
        "stdout_sha256",
        "stderr_path",
        "stderr_sha256",
        "raw_report_count",
        "raw_reports",
        "failure_classification",
        "accepted",
        "report",
        "report_path",
        "report_sha256",
        "tests",
        "failures",
        "errors",
        "skipped",
    }
    source_rows: list[dict[str, Any]] = []
    aggregate_totals: dict[str, int | float] = {
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.0,
    }
    for record in manifest["commands"]:
        if not isinstance(record, dict) or set(record) != accepted_record_keys:
            raise runner.EvidenceError("committed accepted command field set drifted")
        command_id = record["id"]
        contract = contracts[command_id]
        report_name = runner.SOURCE_REPORTS[command_id]
        payload = blobs[report_name]
        expected_matrix_hash = hashlib.sha256(contract["command"].encode("utf-8")).hexdigest()
        stdout_relative = record.get("stdout_path")
        if not isinstance(stdout_relative, str):
            raise runner.EvidenceError(f"{command_id}: committed stdout path is invalid")
        expected_suffix = (
            f"p7-{candidate[:12]}-"
            f"{hashlib.sha256(str(Path(run_root_value) / Path(stdout_relative).parent).encode('utf-8')).hexdigest()[:8]}"
        )
        executed_argv = record.get("executed_argv")
        executed_command = record.get("executed_command")
        if (
            record.get("report") != report_name
            or record.get("report_sha256") != hashlib.sha256(payload).hexdigest()
            or record.get("candidate_commit") != candidate
            or record.get("cwd") != contract["cwd"]
            or record.get("resource_class") != contract["resource_class"]
            or record.get("expected_report_count") != contract["expected_report_count"]
            or record.get("selected_test_file_count") != contract["selected_test_file_count"]
            or record.get("minimum_tests") != contract["minimum_tests"]
            or record.get("matrix_command") != contract["command"]
            or record.get("matrix_command_sha256") != expected_matrix_hash
            or record.get("report_suffix") != expected_suffix
            or record.get("environment_sha256") != environment_digest
            or not isinstance(executed_argv, list)
            or any(not isinstance(argument, str) or not argument for argument in executed_argv)
            or executed_command != runner.render_command_argv(executed_argv)
            or not isinstance(record.get("duration_seconds"), (int, float))
            or isinstance(record.get("duration_seconds"), bool)
            or not math.isfinite(float(record["duration_seconds"]))
            or float(record["duration_seconds"]) < 0
            or record.get("executed_command_sha256")
            != hashlib.sha256(str(executed_command).encode("utf-8")).hexdigest()
            or record.get("accepted") is not True
            or record.get("exit_code") != 0
            or record.get("failure_classification") != "NONE"
        ):
            raise runner.EvidenceError(f"{command_id}: committed execution record drifted")
        for stream in ("stdout", "stderr"):
            relative = record.get(f"{stream}_path")
            if not isinstance(relative, str) or relative not in blobs:
                raise runner.EvidenceError(f"{command_id}: committed {stream} provenance is missing")
            if record.get(f"{stream}_sha256") != hashlib.sha256(blobs[relative]).hexdigest():
                raise runner.EvidenceError(f"{command_id}: committed {stream} hash drifted")
        raw_records = record.get("raw_reports")
        if not isinstance(raw_records, list) or len(raw_records) != contract["expected_report_count"]:
            raise runner.EvidenceError(f"{command_id}: committed raw report count drifted")
        _assert_approved_argv(
            command_id=command_id,
            contract=contract,
            executed_argv=executed_argv,
            expected_suffix=expected_suffix,
            raw_records=raw_records,
            raw_output_relative=(
                Path(stdout_relative).parent / "raw-junit.xml"
            ).as_posix(),
        )
        if record.get("raw_report_count") != len(raw_records):
            raise runner.EvidenceError(f"{command_id}: raw report count record drifted")
        raw_fingerprints: list[tuple[str, str, str, float]] = []
        raw_totals: dict[str, int | float] = {
            "tests": 0,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "time": 0.0,
        }
        for raw in raw_records:
            if (
                not isinstance(raw, dict)
                or set(raw) != {"path", "sha256"}
                or raw.get("path") not in blobs
            ):
                raise runner.EvidenceError(f"{command_id}: committed raw provenance is missing")
            raw_payload = blobs[raw["path"]]
            if raw.get("sha256") != hashlib.sha256(raw_payload).hexdigest():
                raise runner.EvidenceError(f"{command_id}: committed raw report hash drifted")
            fingerprints, raw_summary = _junit_summary(raw_payload, f"{command_id} raw")
            raw_fingerprints.extend(fingerprints)
            for field in ("tests", "failures", "errors", "skipped"):
                raw_totals[field] = int(raw_totals[field]) + int(raw_summary[field])
            raw_totals["time"] = round(
                float(raw_totals["time"]) + float(raw_summary["time"]), 6
            )
        raw_identities = [(item[0], item[1]) for item in raw_fingerprints]
        if len(raw_identities) != len(set(raw_identities)):
            raise runner.EvidenceError(f"{command_id}: raw JUnit sources duplicate a testcase")
        normalized_fingerprints, _ = _junit_summary(payload, command_id)
        if sorted(raw_fingerprints) != sorted(normalized_fingerprints):
            raise runner.EvidenceError(f"{command_id}: normalized JUnit does not match raw provenance")
        junit = _committed_junit_totals(
            payload,
            candidate=candidate,
            command_id=command_id,
            minimum_tests=contract["minimum_tests"],
        )
        if any(record.get(field) != junit[field] for field in ("tests", "failures", "errors", "skipped")):
            raise runner.EvidenceError(f"{command_id}: committed JUnit totals record drifted")
        source_rows.append(
            {
                "command_id": command_id,
                "candidate_commit": candidate,
                "environment_sha256": environment_digest,
                "cwd": record["cwd"],
                "resource_class": record["resource_class"],
                "matrix_command_sha256": record["matrix_command_sha256"],
                "executed_command_sha256": record["executed_command_sha256"],
                "started_at": record["started_at"],
                "finished_at": record["finished_at"],
                "duration_seconds": record["duration_seconds"],
                "exit_code": 0,
                "failure_classification": "NONE",
                "raw_report_count": record["raw_report_count"],
                "expected_report_count": record["expected_report_count"],
                "selected_test_file_count": record["selected_test_file_count"],
                "minimum_tests": record["minimum_tests"],
                "report": report_name,
                "report_sha256": hashlib.sha256(payload).hexdigest(),
                "report_bytes": len(payload),
                "junit": junit,
            }
        )
        for field in ("tests", "failures", "errors", "skipped"):
            aggregate_totals[field] = int(aggregate_totals[field]) + int(junit[field])
        aggregate_totals["time"] = round(
            float(aggregate_totals["time"]) + float(junit["time"]), 6
        )
    quarantine_base_keys = accepted_record_keys - {
        "report",
        "report_path",
        "report_sha256",
        "tests",
        "failures",
        "errors",
        "skipped",
    }
    for record in manifest["quarantined_attempts"]:
        if (
            not isinstance(record, dict)
            or frozenset(record)
            not in {
                frozenset(quarantine_base_keys),
                frozenset(quarantine_base_keys | {"failure_reason"}),
            }
            or record.get("candidate_commit") != candidate
            or record.get("failure_classification") != "INFRA"
            or record.get("id") not in contracts
            or record.get("accepted") is not False
        ):
            raise runner.EvidenceError("committed quarantined INFRA record drifted")
        command_id = record["id"]
        contract = contracts[command_id]
        stdout_relative = record.get("stdout_path")
        if not isinstance(stdout_relative, str):
            raise runner.EvidenceError("committed quarantined stdout path is invalid")
        expected_suffix = (
            f"p7-{candidate[:12]}-"
            f"{hashlib.sha256(str(Path(run_root_value) / Path(stdout_relative).parent).encode('utf-8')).hexdigest()[:8]}"
        )
        executed_argv = record.get("executed_argv")
        executed_command = record.get("executed_command")
        if (
            record.get("cwd") != contract["cwd"]
            or record.get("resource_class") != contract["resource_class"]
            or record.get("expected_report_count") != contract["expected_report_count"]
            or record.get("selected_test_file_count") != contract["selected_test_file_count"]
            or record.get("minimum_tests") != contract["minimum_tests"]
            or record.get("matrix_command") != contract["command"]
            or record.get("matrix_command_sha256")
            != hashlib.sha256(contract["command"].encode("utf-8")).hexdigest()
            or record.get("report_suffix") != expected_suffix
            or record.get("environment_sha256") != environment_digest
            or not isinstance(executed_argv, list)
            or any(not isinstance(argument, str) or not argument for argument in executed_argv)
            or executed_command != runner.render_command_argv(executed_argv)
            or record.get("executed_command_sha256")
            != hashlib.sha256(str(executed_command).encode("utf-8")).hexdigest()
            or record.get("exit_code") == 0
        ):
            raise runner.EvidenceError("committed quarantined command provenance drifted")
        for field in ("stdout", "stderr"):
            relative = record.get(f"{field}_path")
            if not isinstance(relative, str) or relative not in blobs:
                raise runner.EvidenceError("committed quarantined stream is missing")
            if record.get(f"{field}_sha256") != hashlib.sha256(blobs[relative]).hexdigest():
                raise runner.EvidenceError("committed quarantined stream hash drifted")
        raw_records = record.get("raw_reports")
        if not isinstance(raw_records, list) or record.get("raw_report_count") != len(raw_records):
            raise runner.EvidenceError("committed quarantined raw report count drifted")
        _assert_approved_argv(
            command_id=command_id,
            contract=contract,
            executed_argv=executed_argv,
            expected_suffix=expected_suffix,
            raw_records=raw_records,
            raw_output_relative=(
                Path(stdout_relative).parent / "raw-junit.xml"
            ).as_posix(),
        )
        for raw in raw_records:
            if not isinstance(raw, dict) or raw.get("path") not in blobs:
                raise runner.EvidenceError("committed quarantined raw report is missing")
            if raw.get("sha256") != hashlib.sha256(blobs[raw["path"]]).hexdigest():
                raise runner.EvidenceError("committed quarantined raw report hash drifted")

    metrics = _committed_json(evidence, f"{prefix}/{METRICS_NAME}", "entry metrics")
    decision = metrics.get("entry_decision")
    restrictions = metrics.get("runtime_restrictions")
    expected_decision = {
        "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
        "entry_effect_after_commit": ENTRY_EFFECT,
        "next_phase_permission_after_commit": POST_EVIDENCE_PERMISSION,
        "engineering_implementation_after_commit": "ALLOWED_UNDER_ADR_0016_ONLY",
        "evidence_commit_requirement": "DIRECT_CHILD_OF_CANDIDATE",
        "implementation_allowed_before_commit": False,
        "promotion_gate": "PENDING",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
    }
    expected_restrictions = {
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
    expected_metric_keys = {
        "schema_version",
        "release_id",
        "gate",
        "result",
        "candidate_commit",
        "base_commit",
        "candidate_scope",
        "execution_manifest",
        "execution_environment",
        "verification",
        "source_suites",
        "totals",
        "recovery",
        "execution_provenance",
        "entry_decision",
        "runtime_restrictions",
    }
    if (
        set(metrics) != expected_metric_keys
        or
        metrics.get("schema_version") != EVIDENCE_SCHEMA
        or metrics.get("release_id") != release_id
        or metrics.get("gate") != "P7.0"
        or metrics.get("candidate_commit") != candidate
        or metrics.get("base_commit") != runner.PHASE6_ACCEPTANCE_COMMIT
        or metrics.get("result") != "PASS_AWAITING_EVIDENCE_COMMIT"
        or metrics.get("candidate_scope")
        != {
            "classification": "CONTRACT_ONLY",
            "changed_path_count": len(candidate_paths),
            "changed_paths": candidate_paths,
        }
        or metrics.get("execution_manifest")
        != {
            "path": runner.MANIFEST_NAME,
            "sha256": hashlib.sha256(blobs[runner.MANIFEST_NAME]).hexdigest(),
            "manifest_sha256": manifest["manifest_sha256"],
            "schema_version": manifest["schema_version"],
        }
        or metrics.get("execution_environment")
        != {
            "environment_id": environment["environment_id"],
            "snapshot_sha256": environment_digest,
        }
        or metrics.get("verification")
        != {
            "started_at": manifest["verification_started_at"],
            "finished_at": manifest["verification_finished_at"],
            "clean_detached_candidate_required": True,
            "mixed_candidate_results": False,
            "mixed_attempt_results": False,
            "source_reports_reused_from_other_run": False,
            "heavy_processes_at_most": 1,
            "light_processes_at_most": 2,
        }
        or metrics.get("source_suites") != source_rows
        or metrics.get("totals") != aggregate_totals
        or metrics.get("recovery")
        != {
            "quarantined_attempt_count": len(manifest["quarantined_attempts"]),
            "classifications": [
                record["failure_classification"]
                for record in manifest["quarantined_attempts"]
            ],
            "unclassified_attempts": 0,
            "quarantined_attempts_reused": False,
        }
        or metrics.get("execution_provenance")
        != {
            "archived": True,
            "artifact_count": len(_provenance_paths(manifest)),
            "paths": _provenance_paths(manifest),
        }
        or decision != expected_decision
        or restrictions != expected_restrictions
    ):
        raise runner.EvidenceError("committed Phase 7 entry metrics relaxed an engineering gate")

    index = _committed_json(evidence, f"{prefix}/{HASH_INDEX_NAME}", "artifact index")
    expected_indexed = _indexed_names(manifest)
    if (
        index.get("schema_version") != HASH_INDEX_SCHEMA
        or index.get("candidate_commit") != candidate
        or [item.get("path") for item in index.get("artifacts", [])] != expected_indexed
    ):
        raise runner.EvidenceError("committed Phase 7 artifact index coverage drifted")
    for item in index["artifacts"]:
        payload = blobs[item["path"]]
        if (
            item.get("sha256") != hashlib.sha256(payload).hexdigest()
            or item.get("bytes") != len(payload)
        ):
            raise runner.EvidenceError(f"committed artifact {item['path']} hash drifted")
    return {
        "schema_version": "phase7-entry-evidence-commit-verification.v1",
        "status": "EVIDENCE_COMMIT_VERIFIED",
        "evidence_commit": evidence,
        "candidate_commit": candidate,
        "sole_parent_verified": True,
        "artifact_count": len(expected_names),
        "entry_effect": ENTRY_EFFECT,
        "next_phase_permission": POST_EVIDENCE_PERMISSION,
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
    }


def generate_entry_evidence(
    *,
    release_id: str,
    candidate_commit: str,
    base_commit: str,
    execution_manifest_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    base = runner._assert_candidate(base_commit, "base commit")
    run_root = execution_manifest_path.resolve().parent
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    runner.assert_candidate_run_directory(run_root)
    runner.assert_clean_detached_candidate(candidate, allowed_untracked_roots=(run_root,))
    runner._assert_upstream_authority(candidate)
    changed_paths = assert_contract_only_candidate(base, candidate)
    if output.exists() or staging.exists():
        raise runner.EvidenceError(f"entry evidence output or staging path exists: {output}")
    manifest = load_green_manifest(execution_manifest_path, candidate)
    try:
        metrics = assemble_entry_evidence(
            manifest=manifest,
            execution_manifest_path=execution_manifest_path,
            output_dir=staging,
            release_id=release_id,
            base_commit=base,
            candidate_commit=candidate,
            changed_paths=changed_paths,
        )
        runner.assert_clean_detached_candidate(
            candidate, allowed_untracked_roots=(run_root, staging)
        )
        index = _load_json(staging / HASH_INDEX_NAME, "staged artifact index")
        _validate_bundle(
            output_dir=staging,
            candidate=candidate,
            manifest=manifest,
            metrics=metrics,
            index=index,
            release_id=release_id,
        )
        staging.rename(output)
        return metrics
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Assemble immutable P7.0 entry evidence from one exact-SHA green Batch 0 manifest."
        )
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument(
        "--base-commit",
        default=runner.PHASE6_ACCEPTANCE_COMMIT,
        help=(
            "Phase 7 contract base; defaults to and must remain the exact accepted "
            f"A6 {runner.PHASE6_ACCEPTANCE_COMMIT}."
        ),
    )
    parser.add_argument("--execution-manifest", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument(
        "--verify-evidence-commit",
        help=(
            "Verify a committed E7 bundle whose sole parent is --candidate-commit; "
            "generation arguments are forbidden in this mode."
        ),
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if arguments.verify_evidence_commit:
            if (
                arguments.base_commit != runner.PHASE6_ACCEPTANCE_COMMIT
                or arguments.execution_manifest
                or arguments.output_dir
            ):
                raise runner.EvidenceError(
                    "--verify-evidence-commit cannot be combined with generation arguments"
                )
            verification = verify_evidence_commit(
                evidence_commit=arguments.verify_evidence_commit.strip().lower(),
                candidate_commit=arguments.candidate_commit.strip().lower(),
                release_id=arguments.release_id,
            )
            print(json.dumps(verification, sort_keys=True))
            return 0
        if arguments.execution_manifest is None:
            raise runner.EvidenceError(
                "generation requires --execution-manifest"
            )
    except (runner.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 7 entry evidence rejected: {exception}", file=sys.stderr)
        return 2
    output = (
        arguments.output_dir
        or ROOT
        / "test-reports/temporal-first"
        / arguments.release_id
        / "phase-7-entry"
    )
    try:
        metrics = generate_entry_evidence(
            release_id=arguments.release_id,
            candidate_commit=arguments.candidate_commit.strip().lower(),
            base_commit=arguments.base_commit.strip().lower(),
            execution_manifest_path=arguments.execution_manifest,
            output_dir=output,
        )
    except (runner.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 7 entry evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": metrics["candidate_commit"],
                "result": metrics["result"],
                "engineering_execution": metrics["entry_decision"]["engineering_execution"],
                "entry_effect_after_commit": metrics["entry_decision"][
                    "entry_effect_after_commit"
                ],
                "promotion_gate": metrics["entry_decision"]["promotion_gate"],
                "evidence_dir": str(output.resolve()),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
