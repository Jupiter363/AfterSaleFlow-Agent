from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Any, Sequence

try:
    from scripts import run_phase5_entry_checkpoint as runner
    from scripts.generate_phase4_candidate_evidence import assert_base_ancestor
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase5_entry_checkpoint as runner  # type: ignore[no-redef]
    from generate_phase4_candidate_evidence import (  # type: ignore[no-redef]
        assert_base_ancestor,
    )


shared = runner.shared
ROOT = Path(__file__).resolve().parents[1]
METRICS_NAME = "entry-metrics.json"
CANDIDATE_NAME = "candidate.txt"
HASH_INDEX_NAME = "artifact-sha256.json"
EVIDENCE_SCHEMA = "phase5-entry-evidence.v1"
HASH_INDEX_SCHEMA = "phase5-entry-artifact-index.v1"


def _release_id(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,79}", value):
        raise argparse.ArgumentTypeError(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _load_json(path: Path, context: str) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot read {context} {path}: {exception}") from exception
    if not isinstance(document, dict):
        raise shared.EvidenceError(f"{context} must be a JSON object")
    return document


def _archive_snapshot(
    source: Path,
    destination: Path,
    *,
    context: str,
    expected_sha256: str | None = None,
) -> tuple[bytes, str]:
    try:
        payload = source.read_bytes()
    except OSError as exception:
        raise shared.EvidenceError(f"cannot snapshot {context} {source}: {exception}") from exception
    digest = hashlib.sha256(payload).hexdigest()
    if expected_sha256 is not None and digest != expected_sha256:
        raise shared.EvidenceError(f"{context} changed after PASS authentication")
    destination.write_bytes(payload)
    return payload, digest


def load_pass_manifest(
    execution_manifest_path: Path, candidate_commit: str
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    path = execution_manifest_path.resolve()
    if path.name != runner.MANIFEST_NAME:
        raise shared.EvidenceError(
            f"execution manifest must be named {runner.MANIFEST_NAME}"
        )
    manifest = _load_json(path, "Phase 5 entry execution manifest")
    shared._assert_execution_manifest_seal(manifest)
    if (
        manifest.get("schema_version") != runner.SCHEMA_VERSION
        or manifest.get("phase") != 5
        or manifest.get("batch") != runner.BATCH_ID
    ):
        raise shared.EvidenceError("execution manifest is not P5-BATCH-0")
    if manifest.get("candidate_commit") != candidate:
        raise shared.EvidenceError("execution manifest candidate SHA drifted")
    if (
        manifest.get("status") != "PASS"
        or manifest.get("batch_0") != "PASS"
        or manifest.get("contract_gate") != "P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT"
        or manifest.get("engineering_execution") != "BLOCKED_UNTIL_ENTRY_EVIDENCE_COMMIT"
    ):
        raise shared.EvidenceError(
            "execution manifest is not a PASS awaiting the separate entry evidence commit"
        )
    if (
        manifest.get("pending_failure") is not None
        or manifest.get("quarantined_attempts_reused") is not False
        or manifest.get("promotion_gate") != "PENDING"
        or manifest.get("MIG-004") != "PENDING_PROMOTION"
        or manifest.get("MIG-005") != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError("execution manifest gate or recovery state drifted")
    phase4_handoff = runner._validate_embedded_handoff(
        manifest.get("upstream_phase4_checkpoint")
    )
    if manifest.get("environment", {}).get("upstream_phase4_checkpoint") != phase4_handoff:
        raise shared.EvidenceError("execution manifest Phase 4 environment binding drifted")
    live_handoff = runner.authenticate_phase4_handoff(
        runner.load_matrix(),
        ROOT / phase4_handoff["checkpoint_path"],
        candidate,
    )
    if live_handoff != phase4_handoff:
        raise shared.EvidenceError("execution manifest Phase 4 live authentication drifted")

    run_root = path.parent
    shared.assert_candidate_run_directory(run_root)
    if manifest.get("attempt_id") != run_root.name:
        raise shared.EvidenceError("execution manifest belongs to a different run directory")
    environment_sha256 = runner._validate_environment(manifest)
    verification_started = shared._timestamp(
        manifest.get("verification_started_at"), "verification_started_at"
    )
    verification_finished = shared._timestamp(
        manifest.get("verification_finished_at"), "verification_finished_at"
    )
    if verification_finished < verification_started:
        raise shared.EvidenceError("execution manifest verification interval is reversed")

    commands = runner.load_source_commands()
    records = manifest.get("commands")
    if not isinstance(records, list) or [
        record.get("id") if isinstance(record, dict) else None for record in records
    ] != list(runner.COMMAND_ORDER):
        raise shared.EvidenceError("execution manifest lacks the exact four-command PASS set")
    for record, command_id in zip(records, runner.COMMAND_ORDER, strict=True):
        runner._validate_record(
            record,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=environment_sha256,
            verification_started=verification_started,
            accepted=True,
        )
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise shared.EvidenceError("execution manifest quarantined attempts are invalid")
    for record in quarantined:
        command_id = record.get("id") if isinstance(record, dict) else None
        if command_id not in commands or record.get("failure_classification") != "INFRA":
            raise shared.EvidenceError(
                "only classified INFRA attempts may precede accepted source reports"
            )
        runner._validate_record(
            record,
            command_id=command_id,
            candidate=candidate,
            run_root=run_root,
            command=commands[command_id],
            environment_sha256=environment_sha256,
            verification_started=verification_started,
            accepted=False,
        )
    source_dir = run_root / "source"
    expected_reports = set(runner.SOURCE_REPORTS.values())
    if not source_dir.is_dir() or {
        path.name for path in source_dir.iterdir() if path.is_file()
    } != expected_reports:
        raise shared.EvidenceError("source directory is stale, incomplete, or contains extra reports")
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
        report = shared.parse_junit(report_path)
        report_totals = report.totals
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
                "report_sha256": shared._sha256(report_path),
                "report_bytes": report_path.stat().st_size,
                "junit": report_totals,
            }
        )
        for field in ("tests", "failures", "errors", "skipped"):
            totals[field] = int(totals[field]) + int(report_totals[field])
        totals["time"] = round(float(totals["time"]) + float(report_totals["time"]), 6)
    return rows, totals


def _validate_staged_bundle(
    *,
    output_dir: Path,
    candidate: str,
    manifest: dict[str, Any],
    execution_manifest_sha256: str,
    metrics: dict[str, Any],
    index: dict[str, Any],
    expected_names: set[str],
) -> None:
    artifacts = list(output_dir.iterdir())
    if {path.name for path in artifacts} != expected_names or any(
        path.is_symlink() or not path.is_file() for path in artifacts
    ):
        raise shared.EvidenceError("entry evidence output file set drifted")
    if (output_dir / CANDIDATE_NAME).read_bytes() != (candidate + "\n").encode():
        raise shared.EvidenceError("entry evidence candidate binding drifted")

    archived_manifest_path = output_dir / runner.MANIFEST_NAME
    if shared._sha256(archived_manifest_path) != execution_manifest_sha256:
        raise shared.EvidenceError(
            "Phase 5 entry execution manifest changed during evidence assembly"
        )
    archived_manifest = _load_json(
        archived_manifest_path, "archived Phase 5 entry execution manifest"
    )
    if archived_manifest != manifest:
        raise shared.EvidenceError(
            "Phase 5 entry execution manifest changed during evidence assembly"
        )
    shared._assert_execution_manifest_seal(archived_manifest)

    for record in manifest["commands"]:
        if shared._sha256(output_dir / record["report"]) != record["report_sha256"]:
            raise shared.EvidenceError(
                f"accepted source report {record['report']} changed during evidence assembly"
            )
    if _load_json(output_dir / METRICS_NAME, "Phase 5 entry metrics") != metrics:
        raise shared.EvidenceError("entry evidence metrics changed during assembly")
    if _load_json(output_dir / HASH_INDEX_NAME, "Phase 5 entry artifact index") != index:
        raise shared.EvidenceError("entry evidence artifact index changed during assembly")
    for artifact in index["artifacts"]:
        path = output_dir / artifact["path"]
        if (
            shared._sha256(path) != artifact["sha256"]
            or path.stat().st_size != artifact["bytes"]
        ):
            raise shared.EvidenceError(
                f"entry evidence artifact {artifact['path']} changed during assembly"
            )


def assemble_entry_evidence(
    *,
    manifest: dict[str, Any],
    execution_manifest_path: Path,
    output_dir: Path,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    base = shared._assert_candidate(base_commit, "base commit")
    run_root = execution_manifest_path.resolve().parent
    source_dir = run_root / "source"
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / CANDIDATE_NAME).write_bytes((candidate + "\n").encode("ascii"))
    manifest_payload, execution_manifest_sha256 = _archive_snapshot(
        execution_manifest_path,
        output_dir / runner.MANIFEST_NAME,
        context="Phase 5 entry execution manifest",
    )
    try:
        archived_manifest = json.loads(manifest_payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(
            "Phase 5 entry execution manifest changed after PASS authentication"
        ) from exception
    if archived_manifest != manifest:
        raise shared.EvidenceError(
            "Phase 5 entry execution manifest changed after PASS authentication"
        )
    shared._assert_execution_manifest_seal(archived_manifest)

    report_digests = {
        record["report"]: record["report_sha256"] for record in manifest["commands"]
    }
    for filename in runner.SOURCE_REPORTS.values():
        _archive_snapshot(
            source_dir / filename,
            output_dir / filename,
            context=f"accepted source report {filename}",
            expected_sha256=report_digests[filename],
        )

    source_suites, totals = _source_metrics(manifest, output_dir)
    metrics = {
        "schema_version": EVIDENCE_SCHEMA,
        "release_id": release_id,
        "gate": "P5.0",
        "batch": runner.BATCH_ID,
        "result": "PASS_AWAITING_EVIDENCE_COMMIT",
        "candidate_commit": candidate,
        "base_commit": base,
        "execution_manifest": {
            "path": runner.MANIFEST_NAME,
            "sha256": execution_manifest_sha256,
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
        "upstream_phase4_checkpoint": manifest["upstream_phase4_checkpoint"],
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
            "entry_effect_after_commit": "P5_0_ENGINEERING_ENTRY_PASS",
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
            "implementation_allowed_before_commit": False,
        },
        "runtime_restrictions": {
            "allowed_modes": ["LEGACY", "DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
            "formal_evidence_sink": False,
            "temporal_evidence_allocation": False,
            "real_case_shadow": False,
            "production_traffic": False,
            "canary": False,
            "promotion": False,
            "public_submission_max": 50,
            "closed_synthetic_manifest_counts": [1, 8, 100],
        },
    }
    shared._write_json(output_dir / METRICS_NAME, metrics)

    indexed_names = [
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
                "sha256": shared._sha256(output_dir / name),
                "bytes": (output_dir / name).stat().st_size,
            }
            for name in indexed_names
        ],
    }
    shared._write_json(output_dir / HASH_INDEX_NAME, index)
    expected = {HASH_INDEX_NAME, *indexed_names}
    _validate_staged_bundle(
        output_dir=output_dir,
        candidate=candidate,
        manifest=manifest,
        execution_manifest_sha256=execution_manifest_sha256,
        metrics=metrics,
        index=index,
        expected_names=expected,
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
    candidate = shared._assert_candidate(candidate_commit)
    manifest_path = execution_manifest_path.resolve()
    run_root = manifest_path.parent
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    shared.assert_candidate_run_directory(run_root)
    shared.assert_clean_detached_candidate(
        candidate, allowed_untracked_roots=(run_root,)
    )
    assert_base_ancestor(base_commit, candidate)
    if output.exists() or staging.exists():
        raise shared.EvidenceError(f"entry evidence output or staging path exists: {output}")
    manifest = load_pass_manifest(manifest_path, candidate)
    try:
        metrics = assemble_entry_evidence(
            manifest=manifest,
            execution_manifest_path=manifest_path,
            output_dir=staging,
            release_id=release_id,
            base_commit=base_commit,
            candidate_commit=candidate,
        )
        shared.assert_clean_detached_candidate(
            candidate, allowed_untracked_roots=(run_root, staging)
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
            "Atomically assemble the minimal P5.0 entry evidence bundle from one PASS "
            "P5-BATCH-0 execution manifest."
        )
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--execution-manifest", required=True, type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Defaults to test-reports/temporal-first/<release-id>/phase-5-entry.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    output_dir = (
        arguments.output_dir
        or ROOT
        / "test-reports/temporal-first"
        / arguments.release_id
        / "phase-5-entry"
    )
    try:
        metrics = generate_entry_evidence(
            release_id=arguments.release_id,
            candidate_commit=arguments.candidate_commit.strip().lower(),
            base_commit=arguments.base_commit.strip().lower(),
            execution_manifest_path=arguments.execution_manifest,
            output_dir=output_dir,
        )
    except (shared.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 entry evidence rejected: {exception}", file=sys.stderr)
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
                "evidence_dir": str(output_dir.resolve()),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
