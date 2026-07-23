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
    from scripts import run_phase5_wave_a_checkpoint as runner
    from scripts.generate_phase4_candidate_evidence import assert_base_ancestor
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase5_wave_a_checkpoint as runner  # type: ignore[no-redef]
    from generate_phase4_candidate_evidence import (  # type: ignore[no-redef]
        assert_base_ancestor,
    )


shared = runner.shared
ROOT = Path(__file__).resolve().parents[1]
METRICS_NAME = "wave-a-metrics.json"
CANDIDATE_NAME = "candidate-commit.txt"
HASH_INDEX_NAME = "artifact-sha256.json"
EVIDENCE_SCHEMA = "phase5-wave-a-evidence.v1"
HASH_INDEX_SCHEMA = "phase5-wave-a-artifact-index.v1"


def _json_lf_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def _write_json_lf(path: Path, value: Any) -> None:
    path.write_bytes(_json_lf_bytes(value))


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
        check=False,
        capture_output=True,
    )
    output = process.stdout.decode("ascii", errors="replace").strip()
    if process.returncode or not re.fullmatch(r"[0-9a-f]{40,64}", output):
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise shared.EvidenceError(
            f"cannot apply Git clean filter for Wave A evidence: {detail or output}"
        )
    return output


def _assert_git_clean_filter_stable(
    path: Path, *, release_id: str
) -> None:
    payload = path.read_bytes()
    if b"\r" in payload:
        raise shared.EvidenceError(
            f"Wave A evidence artifact {path.name} contains non-LF line endings"
        )
    logical_path = (
        Path("test-reports")
        / "temporal-first"
        / release_id
        / "phase-5-wave-a"
        / path.name
    ).as_posix()
    if _git_hash_object(payload) != _git_hash_object(payload, logical_path=logical_path):
        raise shared.EvidenceError(
            f"Wave A evidence artifact {path.name} changes under Git clean filters"
        )


def _copy_lf(source: Path, destination: Path, *, context: str) -> str:
    try:
        payload = source.read_bytes()
    except OSError as exception:
        raise shared.EvidenceError(f"cannot read {context}: {exception}") from exception
    if b"\r" in payload:
        raise shared.EvidenceError(f"{context} contains non-LF line endings")
    destination.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def _source_metrics(
    manifest: dict[str, Any], output_dir: Path
) -> tuple[list[dict[str, Any]], dict[str, int | float]]:
    suites: list[dict[str, Any]] = []
    totals: dict[str, int | float] = {
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.0,
    }
    records = {record["id"]: record for record in manifest["commands"]}
    identities: list[str] = []
    for command_id in runner.COMMAND_ORDER:
        filename = runner.SOURCE_REPORTS[command_id]
        report = shared.parse_junit(output_dir / filename)
        if report.candidate_commit != manifest["candidate_commit"]:
            raise shared.EvidenceError(f"{command_id}: evidence report candidate drifted")
        if report.command_id != command_id:
            raise shared.EvidenceError(f"{command_id}: evidence report command drifted")
        report_totals = report.totals
        if (
            report_totals["failures"]
            or report_totals["errors"]
            or report_totals["skipped"]
        ):
            raise shared.EvidenceError(f"{command_id}: evidence report is not all-pass")
        identities.extend(case.identity for case in report.cases)
        suites.append(
            {
                "id": command_id,
                "report": filename,
                "sha256": shared._sha256(output_dir / filename),
                "candidate_commit": report.candidate_commit,
                "environment_sha256": records[command_id]["environment_sha256"],
                **report_totals,
            }
        )
        for field in ("tests", "failures", "errors", "skipped"):
            totals[field] = int(totals[field]) + int(report_totals[field])
        totals["time"] = round(float(totals["time"]) + float(report_totals["time"]), 3)
    if len(identities) != len(set(identities)):
        raise shared.EvidenceError("Wave A reports contain duplicate cross-source test identities")
    return suites, totals


def _validate_bundle(
    *,
    output_dir: Path,
    release_id: str,
    expected_names: set[str],
    candidate: str,
) -> None:
    actual = {path.name for path in output_dir.iterdir() if path.is_file()}
    if actual != expected_names:
        raise shared.EvidenceError(
            f"Wave A evidence file set mismatch: missing={sorted(expected_names - actual)}, "
            f"unexpected={sorted(actual - expected_names)}"
        )
    for path in output_dir.iterdir():
        if path.is_file():
            _assert_git_clean_filter_stable(path, release_id=release_id)
    if (output_dir / CANDIDATE_NAME).read_text(encoding="ascii") != candidate + "\n":
        raise shared.EvidenceError("Wave A candidate file drifted")
    index = json.loads((output_dir / HASH_INDEX_NAME).read_text(encoding="utf-8"))
    if (
        index.get("schema_version") != HASH_INDEX_SCHEMA
        or index.get("candidate_commit") != candidate
    ):
        raise shared.EvidenceError("Wave A artifact index identity drifted")
    indexed = index.get("artifacts")
    if not isinstance(indexed, list) or {item.get("path") for item in indexed} != (
        expected_names - {HASH_INDEX_NAME}
    ):
        raise shared.EvidenceError("Wave A artifact index file set drifted")
    for item in indexed:
        path = output_dir / item["path"]
        if (
            item.get("sha256") != shared._sha256(path)
            or item.get("bytes") != path.stat().st_size
        ):
            raise shared.EvidenceError(f"Wave A artifact index drifted for {path.name}")


def assemble_wave_a_evidence(
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
    expected_base = shared._assert_candidate(
        runner.load_matrix()["batches"][runner.BATCH_ID]["execution"][
            "accepted_wave_a_base_commit"
        ],
        "accepted Wave A base commit",
    )
    if base != expected_base:
        raise shared.EvidenceError("Wave A base commit differs from the accepted entry base")
    run_root = execution_manifest_path.resolve().parent
    source_dir = run_root / "source"
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / CANDIDATE_NAME).write_bytes((candidate + "\n").encode("ascii"))

    task_binding_sha = _copy_lf(
        run_root / runner.TASK_BINDINGS_NAME,
        output_dir / runner.TASK_BINDINGS_NAME,
        context="Wave A task bindings",
    )
    if task_binding_sha != manifest["task_bindings"]["sha256"]:
        raise shared.EvidenceError("Wave A task binding hash drifted during assembly")

    archived_manifest = output_dir / runner.MANIFEST_NAME
    _write_json_lf(archived_manifest, manifest)
    shared._assert_execution_manifest_seal(
        json.loads(archived_manifest.read_text(encoding="utf-8"))
    )
    manifest_sha = shared._sha256(archived_manifest)

    record_digests = {
        record["report"]: record["report_sha256"] for record in manifest["commands"]
    }
    for filename in runner.SOURCE_REPORTS.values():
        digest = _copy_lf(
            source_dir / filename,
            output_dir / filename,
            context=f"Wave A source report {filename}",
        )
        if digest != record_digests[filename]:
            raise shared.EvidenceError(f"Wave A source report {filename} hash drifted")

    source_suites, totals = _source_metrics(manifest, output_dir)
    metrics = {
        "schema_version": EVIDENCE_SCHEMA,
        "release_id": release_id,
        "phase": 5,
        "batch": runner.BATCH_ID,
        "result": "PASS_AWAITING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE",
        "candidate_commit": candidate,
        "base_commit": base,
        "execution_manifest": {
            "path": runner.MANIFEST_NAME,
            "sha256": manifest_sha,
            "manifest_sha256": manifest["manifest_sha256"],
            "schema_version": manifest["schema_version"],
        },
        "task_bindings": {
            "path": runner.TASK_BINDINGS_NAME,
            "sha256": task_binding_sha,
            "tasks": manifest["task_bindings"]["tasks"],
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
                item["failure_classification"]
                for item in manifest["quarantined_attempts"]
            ],
            "unclassified_attempts": 0,
            "quarantined_attempts_reused": False,
        },
        "checkpoint_decision": {
            "wave_a_barrier": "BLOCKED_UNTIL_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE",
            "wave_b_execution": "BLOCKED",
            "evidence_commit_opens_wave_b": False,
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
        },
        "runtime_restrictions": {
            "allowed_modes": ["LEGACY", "DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
            "frontend_executed": False,
            "browser_executed": False,
            "database_executed": False,
            "real_provider": False,
            "formal_evidence_sink": False,
            "temporal_evidence_allocation": False,
            "real_case_shadow": False,
            "promotion": False,
        },
    }
    _write_json_lf(output_dir / METRICS_NAME, metrics)
    indexed_names = [
        CANDIDATE_NAME,
        runner.TASK_BINDINGS_NAME,
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
    _write_json_lf(output_dir / HASH_INDEX_NAME, index)
    expected = {HASH_INDEX_NAME, *indexed_names}
    _validate_bundle(
        output_dir=output_dir,
        release_id=release_id,
        expected_names=expected,
        candidate=candidate,
    )
    return metrics


def generate_wave_a_evidence(
    *,
    release_id: str,
    candidate_commit: str,
    base_commit: str,
    execution_manifest_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit)
    expected_base = runner.load_matrix()["batches"][runner.BATCH_ID]["execution"][
        "accepted_wave_a_base_commit"
    ]
    if base_commit.strip().lower() != expected_base:
        raise shared.EvidenceError("Wave A base commit differs from the accepted entry base")
    manifest_path = execution_manifest_path.resolve()
    run_root = manifest_path.parent
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    shared.assert_candidate_run_directory(run_root)
    shared.assert_clean_detached_candidate(candidate, allowed_untracked_roots=(run_root,))
    assert_base_ancestor(expected_base, candidate)
    if output.exists() or staging.exists():
        raise shared.EvidenceError("Wave A evidence output or staging path already exists")
    manifest = runner.load_pass_manifest(manifest_path, candidate)
    try:
        metrics = assemble_wave_a_evidence(
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
        description="Atomically assemble the eight-file P5-BATCH-1 Wave A evidence bundle."
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--execution-manifest", required=True, type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Defaults to test-reports/temporal-first/<release-id>/phase-5-wave-a.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    output_dir = (
        arguments.output_dir
        or ROOT
        / "test-reports"
        / "temporal-first"
        / arguments.release_id
        / "phase-5-wave-a"
    )
    try:
        metrics = generate_wave_a_evidence(
            release_id=arguments.release_id,
            candidate_commit=arguments.candidate_commit.strip().lower(),
            base_commit=arguments.base_commit.strip().lower(),
            execution_manifest_path=arguments.execution_manifest,
            output_dir=output_dir,
        )
    except (shared.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 Wave A evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": metrics["candidate_commit"],
                "result": metrics["result"],
                "wave_a_barrier": metrics["checkpoint_decision"]["wave_a_barrier"],
                "evidence_dir": str(output_dir.resolve()),
                "promotion_gate": metrics["checkpoint_decision"]["promotion_gate"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
