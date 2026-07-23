from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Sequence

try:
    from scripts import run_phase5_wave_a_acceptance as runner
except (ImportError, ModuleNotFoundError):
    import run_phase5_wave_a_acceptance as runner  # type: ignore[no-redef]


shared = runner.shared
ROOT = Path(__file__).resolve().parents[1]
SCHEMA_VERSION = "phase5-wave-a-acceptance.v1"
INDEX_SCHEMA = "phase5-wave-a-acceptance-artifact-index.v1"
ACCEPTANCE_NAME = "phase5-wave-a-acceptance.json"
CANDIDATE_NAME = "accepted-tooling-candidate.txt"
INDEX_NAME = "artifact-sha256.json"


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _write(path: Path, value: bytes) -> None:
    if b"\r" in value or not value.endswith(b"\n"):
        raise shared.EvidenceError(f"acceptance artifact {path.name} is not LF text")
    path.write_bytes(value)


def _git_hash_object(value: bytes, logical_path: str | None = None) -> str:
    command = ["git", "--no-replace-objects", "hash-object"]
    command.append("--no-filters" if logical_path is None else f"--path={logical_path}")
    command.append("--stdin")
    process = subprocess.run(
        command,
        cwd=ROOT,
        input=value,
        check=False,
        capture_output=True,
    )
    output = process.stdout.decode("ascii", errors="replace").strip()
    if process.returncode or not re.fullmatch(r"[0-9a-f]{40}", output):
        raise shared.EvidenceError("cannot authenticate acceptance Git clean filters")
    return output


def _assert_clean_filter_stable(path: Path) -> None:
    value = path.read_bytes()
    logical = f"{runner.EXPECTED_ACCEPTANCE_DIR}/{path.name}"
    if _git_hash_object(value) != _git_hash_object(value, logical):
        raise shared.EvidenceError(f"acceptance artifact {path.name} changes under Git filters")


def _assert_non_reparse_ancestors(path: Path) -> None:
    current = path.parent
    while current != current.parent:
        is_junction = getattr(current, "is_junction", lambda: False)
        if current.exists() and (current.is_symlink() or is_junction()):
            raise shared.EvidenceError("acceptance output uses a symlink or junction ancestor")
        current = current.parent


def generate_acceptance(
    *, candidate_commit: str, execution_manifest: Path, output_dir: Path
) -> dict[str, Any]:
    candidate = shared._assert_candidate(candidate_commit, "acceptance tooling candidate")
    run_root = execution_manifest.resolve().parent
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    shared.assert_candidate_run_directory(run_root)
    shared.assert_clean_detached_candidate(
        candidate, allowed_untracked_roots=(run_root,)
    )
    if output.as_posix() != (ROOT / runner.EXPECTED_ACCEPTANCE_DIR).resolve().as_posix():
        raise shared.EvidenceError("Wave A acceptance output path differs from the contract")
    _assert_non_reparse_ancestors(output)
    if output.exists() or staging.exists():
        raise shared.EvidenceError("Wave A acceptance output or staging path already exists")
    manifest = runner.load_pass_manifest(execution_manifest.resolve(), candidate)
    handoff = manifest["authenticated_handoff"]
    acceptance = {
        "schema_version": SCHEMA_VERSION,
        "phase": 5,
        "checkpoint": "P5-WAVE-A-INTEGRATED",
        "result": "PASS_AWAITING_STATE_TRANSITION_COMMIT",
        "tested_candidate_commit": handoff["tested_candidate_commit"],
        "accepted_base_commit": handoff["accepted_base_commit"],
        "evidence_commit": handoff["evidence_commit"],
        "evidence_tree_oid": handoff["evidence_tree_oid"],
        "artifact_index_sha256": handoff["artifact_index_sha256"],
        "artifact_index_blob_oid": handoff["artifact_index_blob_oid"],
        "acceptance_tooling_candidate_commit": candidate,
        "evidence_path": handoff["evidence_path"],
        "evidence_file_count": handoff["evidence_file_count"],
        "evidence_artifacts": handoff["artifacts"],
        "totals": handoff["totals"],
        "decision": {
            "P5-WAVE-A-INTEGRATED": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "wave_b": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "evidence_commit_alone_opens_wave_b": False,
            "state_transition_commit_required": True,
            "acceptance_commit_is_derived_from_git_history": True,
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
        },
        "runtime_restrictions": {
            "real_provider": False,
            "formal_evidence_sink": False,
            "temporal_evidence_allocation": False,
            "real_case_shadow": False,
            "canary": False,
            "promotion": False,
        },
    }
    try:
        staging.mkdir(parents=True)
        _write(staging / CANDIDATE_NAME, (candidate + "\n").encode("ascii"))
        _write(staging / ACCEPTANCE_NAME, _json_bytes(acceptance))
        index = {
            "schema_version": INDEX_SCHEMA,
            "acceptance_tooling_candidate_commit": candidate,
            "artifacts": [
                {
                    "path": name,
                    "sha256": _sha256((staging / name).read_bytes()),
                    "bytes": (staging / name).stat().st_size,
                }
                for name in (CANDIDATE_NAME, ACCEPTANCE_NAME)
            ],
        }
        _write(staging / INDEX_NAME, _json_bytes(index))
        if {path.name for path in staging.iterdir()} != set(runner.ACCEPTANCE_FILES):
            raise shared.EvidenceError("Wave A acceptance bundle file set drifted")
        for path in staging.iterdir():
            _assert_clean_filter_stable(path)
        shared.assert_clean_detached_candidate(
            candidate, allowed_untracked_roots=(run_root, staging)
        )
        os.replace(staging, output)
        for path in output.iterdir():
            _assert_clean_filter_stable(path)
        shared.assert_clean_detached_candidate(
            candidate, allowed_untracked_roots=(run_root, output)
        )
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise
    return acceptance


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate the separate three-file P5 Wave A acceptance bundle."
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execution-manifest", required=True, type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=ROOT / runner.EXPECTED_ACCEPTANCE_DIR,
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        acceptance = generate_acceptance(
            candidate_commit=arguments.candidate_commit,
            execution_manifest=arguments.execution_manifest,
            output_dir=arguments.output_dir,
        )
    except (shared.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 Wave A acceptance generation rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "result": acceptance["result"],
                "P5-WAVE-A-INTEGRATED": acceptance["decision"][
                    "P5-WAVE-A-INTEGRATED"
                ],
                "wave_b": acceptance["decision"]["wave_b"],
                "output": str(arguments.output_dir.resolve()),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
