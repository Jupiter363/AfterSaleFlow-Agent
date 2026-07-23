from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Sequence

try:
    from scripts import run_phase6_entry_checkpoint as runner
    from scripts.generate_phase4_candidate_evidence import assert_base_ancestor
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase6_entry_checkpoint as runner  # type: ignore[no-redef]
    from generate_phase4_candidate_evidence import (  # type: ignore[no-redef]
        assert_base_ancestor,
    )


ROOT = Path(__file__).resolve().parents[1]
METRICS_NAME = "entry-metrics.json"
CANDIDATE_NAME = "candidate.txt"
HASH_INDEX_NAME = "artifact-sha256.json"
EVIDENCE_SCHEMA = "phase6-entry-evidence.v1"
HASH_INDEX_SCHEMA = "phase6-entry-artifact-index.v1"


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
        command,
        cwd=ROOT,
        input=payload,
        capture_output=True,
        check=False,
    )
    output = process.stdout.decode("ascii", errors="replace").strip()
    if process.returncode or not re.fullmatch(r"[0-9a-f]{40,64}", output):
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise runner.EvidenceError(
            f"cannot apply Git clean filter for Phase 6 evidence: {error or output}"
        )
    return output


def _assert_git_clean_filter_stable(path: Path, *, release_id: str) -> None:
    payload = path.read_bytes()
    if b"\r" in payload:
        raise runner.EvidenceError(
            f"entry evidence artifact {path.name} contains non-LF line endings"
        )
    logical = (
        Path("test-reports")
        / "temporal-first"
        / release_id
        / "phase-6-entry"
        / path.name
    ).as_posix()
    if _git_hash_object(payload) != _git_hash_object(payload, logical_path=logical):
        raise runner.EvidenceError(
            f"entry evidence artifact {path.name} changes under Git clean filters"
        )


def _archive_manifest(
    source: Path, destination: Path, expected_manifest: dict[str, Any]
) -> str:
    current = _load_json(source, "Phase 6 entry execution manifest")
    if current != expected_manifest:
        raise runner.EvidenceError(
            "Phase 6 entry execution manifest changed after green authentication"
        )
    runner._assert_execution_manifest_seal(current)
    payload = _json_lf_bytes(current)
    destination.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def load_green_manifest(
    execution_manifest_path: Path, candidate_commit: str
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    path = execution_manifest_path.resolve()
    if path.name != runner.MANIFEST_NAME:
        raise runner.EvidenceError(
            f"execution manifest must be named {runner.MANIFEST_NAME}"
        )
    manifest = _load_json(path, "Phase 6 entry execution manifest")
    runner._assert_execution_manifest_seal(manifest)
    if manifest.get("schema_version") != runner.SCHEMA_VERSION or manifest.get("phase") != 6:
        raise runner.EvidenceError("execution manifest is not Phase 6 Batch 0")
    if manifest.get("candidate_commit") != candidate:
        raise runner.EvidenceError("execution manifest candidate SHA drifted")
    if (
        manifest.get("status") != runner.GREEN_STATUS
        or manifest.get("contract_gate") != "P6.0_NOT_RUN"
        or manifest.get("implementation_authorized") is not False
    ):
        raise runner.EvidenceError(
            "execution manifest is not green awaiting the separate entry evidence commit"
        )
    if any(
        manifest.get(migration) != "PENDING_PROMOTION"
        for migration in ("MIG-004", "MIG-005", "MIG-006")
    ):
        raise runner.EvidenceError("execution manifest migration gate drifted")
    if (
        manifest.get("pending_failure") is not None
        or manifest.get("quarantined_attempts_reused") is not False
    ):
        raise runner.EvidenceError("execution manifest recovery state drifted")

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
        junit = report.totals
        rows.append(
            {
                "command_id": record["id"],
                "candidate_commit": record["candidate_commit"],
                "environment_sha256": record["environment_sha256"],
                "cwd": record["cwd"],
                "matrix_command_sha256": record["matrix_command_sha256"],
                "executed_command_sha256": record["executed_command_sha256"],
                "started_at": record["started_at"],
                "finished_at": record["finished_at"],
                "duration_seconds": record["duration_seconds"],
                "exit_code": record["exit_code"],
                "failure_classification": record["failure_classification"],
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
        CANDIDATE_NAME,
        runner.MANIFEST_NAME,
        METRICS_NAME,
        *runner.SOURCE_REPORTS.values(),
    }
    artifacts = list(output_dir.iterdir())
    if {item.name for item in artifacts} != expected or any(
        item.is_symlink() or not item.is_file() for item in artifacts
    ):
        raise runner.EvidenceError("entry evidence output file set drifted")
    for item in artifacts:
        _assert_git_clean_filter_stable(item, release_id=release_id)
    if (output_dir / CANDIDATE_NAME).read_bytes() != (candidate + "\n").encode("ascii"):
        raise runner.EvidenceError("entry evidence candidate binding drifted")
    archived = _load_json(output_dir / runner.MANIFEST_NAME, "archived manifest")
    if archived != manifest:
        raise runner.EvidenceError("archived execution manifest changed")
    runner._assert_execution_manifest_seal(archived)
    if _load_json(output_dir / METRICS_NAME, "entry metrics") != metrics:
        raise runner.EvidenceError("entry metrics changed during assembly")
    if _load_json(output_dir / HASH_INDEX_NAME, "artifact index") != index:
        raise runner.EvidenceError("artifact index changed during assembly")
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
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    base = runner._assert_candidate(base_commit, "base commit")
    run_root = execution_manifest_path.resolve().parent
    source_dir = run_root / "source"
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / CANDIDATE_NAME).write_bytes((candidate + "\n").encode("ascii"))
    archived_manifest_sha256 = _archive_manifest(
        execution_manifest_path,
        output_dir / runner.MANIFEST_NAME,
        manifest,
    )
    for record in manifest["commands"]:
        source = source_dir / record["report"]
        if runner._sha256(source) != record["report_sha256"]:
            raise runner.EvidenceError(f"accepted report {record['report']} drifted")
        shutil.copyfile(source, output_dir / record["report"])

    source_suites, totals = _source_metrics(manifest, output_dir)
    metrics = {
        "schema_version": EVIDENCE_SCHEMA,
        "release_id": release_id,
        "gate": "P6.0",
        "result": "PASS_AWAITING_EVIDENCE_COMMIT",
        "candidate_commit": candidate,
        "base_commit": base,
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
            "source_reports_reused_from_other_run": False,
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
        "entry_decision": {
            "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
            "entry_effect_after_commit": "P6_0_ENGINEERING_ENTRY_PASS",
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
            "MIG-006": "PENDING_PROMOTION",
            "implementation_allowed_before_commit": False,
        },
        "runtime_restrictions": {
            "allowed_new_runtime_modes_after_commit": [
                "DISABLED",
                "JAVA_SIGNED_SYNTHETIC_SHADOW",
            ],
            "legacy_formal_java_path_preserved": True,
            "formal_hearing_graph_sink": False,
            "temporal_hearing_allocation": False,
            "real_or_party_data_shadow": False,
            "production_traffic": False,
            "canary": False,
            "promotion": False,
        },
    }
    _write_json_lf(output_dir / METRICS_NAME, metrics)
    indexed = [
        CANDIDATE_NAME,
        runner.MANIFEST_NAME,
        *runner.SOURCE_REPORTS.values(),
        METRICS_NAME,
    ]
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


def generate_entry_evidence(
    *,
    release_id: str,
    candidate_commit: str,
    base_commit: str,
    execution_manifest_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    run_root = execution_manifest_path.resolve().parent
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    runner.assert_candidate_run_directory(run_root)
    runner.assert_clean_detached_candidate(candidate, allowed_untracked_roots=(run_root,))
    assert_base_ancestor(base_commit, candidate)
    if output.exists() or staging.exists():
        raise runner.EvidenceError(f"entry evidence output or staging path exists: {output}")
    manifest = load_green_manifest(execution_manifest_path, candidate)
    try:
        metrics = assemble_entry_evidence(
            manifest=manifest,
            execution_manifest_path=execution_manifest_path,
            output_dir=staging,
            release_id=release_id,
            base_commit=base_commit,
            candidate_commit=candidate,
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
        description="Assemble immutable P6.0 entry evidence from one green Batch 0 manifest."
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--execution-manifest", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    output = (
        arguments.output_dir
        or ROOT
        / "test-reports/temporal-first"
        / arguments.release_id
        / "phase-6-entry"
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
        print(f"Phase 6 entry evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": metrics["candidate_commit"],
                "result": metrics["result"],
                "engineering_execution": metrics["entry_decision"][
                    "engineering_execution"
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
