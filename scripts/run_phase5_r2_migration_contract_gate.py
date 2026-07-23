from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Sequence

import yaml


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_VERSION = "phase5-r2-migration-contract-gate.v1"
MANIFEST_NAME = "phase5-r2-migration-contract-gate.json"
SOURCE_COMMAND = {
    "id": "p5_r2_static_migration_contract_gate",
    "workdir": ".",
    "argv": [
        "D:/miniconda/python.exe",
        "-m",
        "pytest",
        "tests/static/test_phase5_r2_migration_contract_gate.py",
        "tests/static/test_phase5_evidence_pilot_plan.py",
        "-q",
    ],
}
AUTHORIZED_MIGRATION = (
    "java-api-service/src/main/resources/db/migration/"
    "V043_5__evidence_finalization_and_operational_recovery.sql"
)
FORBIDDEN_MIGRATION = (
    "java-api-service/src/main/resources/db/migration/"
    "V043_4__evidence_graph_bindings.sql"
)
FORBIDDEN_MIGRATION_SHA256 = (
    "f2872430c63db6b8f561ef982ea4b3329d04bd7ecde744aaa625880c02399cb0"
)
CONTRACT_PATH = (
    ROOT / "docs/architecture/contracts/phase-5-evidence-migration-contract-erratum.yaml"
)
ADR_PATH = ROOT / "docs/architecture/adr/0014-phase-5-evidence-migration-contract-erratum.md"


class GateError(RuntimeError):
    pass


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _git_text(*args: str) -> str:
    result = subprocess.run(
        ["git", "--no-replace-objects", *args],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        raise GateError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout.decode("utf-8", errors="strict").strip()


def _assert_clean(candidate: str) -> None:
    head = _git_text("rev-parse", "HEAD")
    if head != candidate:
        raise GateError("candidate commit must be checked out exactly")
    status = _git_text("status", "--short")
    if status:
        raise GateError(f"candidate repository is not clean:\n{status}")


def _load_contract() -> dict[str, Any]:
    document = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise GateError("R2 contract must be a YAML object")
    return document


def authenticate(candidate: str) -> dict[str, Any]:
    if len(candidate) != 40 or any(ch not in "0123456789abcdef" for ch in candidate):
        raise GateError("candidate commit must be a full lowercase SHA-1")
    _assert_clean(candidate)
    contract = _load_contract()
    forbidden_bytes = (ROOT / FORBIDDEN_MIGRATION).read_bytes()
    adr = ADR_PATH.read_text(encoding="utf-8")
    if contract.get("authorized_migration_path") != AUTHORIZED_MIGRATION:
        raise GateError("authorized migration path drifted")
    if contract.get("forbidden_migration_path") != FORBIDDEN_MIGRATION:
        raise GateError("forbidden migration path drifted")
    if contract.get("forbidden_migration_sha256") != FORBIDDEN_MIGRATION_SHA256:
        raise GateError("forbidden migration hash contract drifted")
    if _sha256(forbidden_bytes) != FORBIDDEN_MIGRATION_SHA256:
        raise GateError("V043_4 hash drifted")
    if (ROOT / AUTHORIZED_MIGRATION).exists():
        raise GateError("authorized migration must not exist before R2 evidence")
    restrictions = contract.get("runtime_restrictions")
    if not isinstance(restrictions, dict) or any(value is not False for value in restrictions.values()):
        raise GateError("runtime restrictions drifted")
    for token in (
        "V043_5__evidence_finalization_and_operational_recovery.sql",
        "V043_4__evidence_graph_bindings.sql",
        "formal Evidence Finalizer sink",
        "`TEMPORAL` Evidence allocation",
    ):
        if token not in adr:
            raise GateError(f"ADR is missing {token}")
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "PASS",
        "candidate_commit": candidate,
        "authorized_migration_path": AUTHORIZED_MIGRATION,
        "forbidden_migration_path": FORBIDDEN_MIGRATION,
        "forbidden_migration_sha256": FORBIDDEN_MIGRATION_SHA256,
        "contract_sha256": _sha256(CONTRACT_PATH.read_bytes()),
        "adr_sha256": _sha256(ADR_PATH.read_bytes()),
        "runtime_restrictions": restrictions,
    }


def execute(candidate: str, run_dir: Path) -> dict[str, Any]:
    if run_dir.exists():
        raise GateError("run directory already exists")
    _assert_clean(candidate)
    started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    result = subprocess.run(
        SOURCE_COMMAND["argv"],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=environment,
    )
    ended = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    command_result = {
        "id": SOURCE_COMMAND["id"],
        "workdir": SOURCE_COMMAND["workdir"],
        "argv": SOURCE_COMMAND["argv"],
        "started_at": started,
        "ended_at": ended,
        "exit_code": result.returncode,
        "status": "PASS" if result.returncode == 0 else "FAIL",
        "stdout_sha256": _sha256(result.stdout),
        "stderr_sha256": _sha256(result.stderr),
    }
    if result.returncode:
        raise GateError(
            "source command failed: "
            + result.stderr.decode("utf-8", errors="replace").strip()
        )
    manifest = authenticate(candidate)
    manifest["source_command"] = command_result
    run_dir.mkdir(parents=True)
    manifest_path = run_dir / MANIFEST_NAME
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.execute:
            if args.run_dir is None:
                raise GateError("--run-dir is required with --execute")
            manifest = execute(args.candidate_commit, args.run_dir)
        else:
            if args.run_dir is not None:
                raise GateError("--run-dir requires --execute")
            manifest = authenticate(args.candidate_commit)
    except (GateError, OSError, yaml.YAMLError) as exc:
        print(f"Phase 5 R2 migration contract rejected: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
