from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import yaml

try:
    from scripts.generate_phase3_candidate_evidence import (
        EvidenceError,
        normalize_source_reports,
    )
except ModuleNotFoundError:  # Direct execution places scripts/ on sys.path.
    from generate_phase3_candidate_evidence import (  # type: ignore[no-redef]
        EvidenceError,
        normalize_source_reports,
    )


ROOT = Path(__file__).resolve().parents[1]
APPROVED_RUN_ROOT: Path | None = None
MATRIX_PATH = ROOT / "plans/phase-8-production-hardening-test-batches.yaml"
MANIFEST_NAME = "phase8-entry-execution-manifest.json"
ENVIRONMENT_NAME = "source-tree-environment.json"
NORMALIZED_REPORT_NAME = "static-phase8-entry.xml"
SCHEMA_VERSION = "phase8-entry-execution-manifest.v1"
GREEN_STATUS = "SOURCES_GREEN_AWAITING_SOLE_PARENT_E8_ENTRY_EVIDENCE"
SOURCE_ID = "static_phase8_entry"
MINIMUM_TESTS = 24

C7 = "4ddeeabb39ce7b7de41ecc4f44e17ece389d2840"
E7 = "f1c1ca16228641f1072eb358c6df9235dc239914"
A7 = "e3acedc64d161f0342c8db3d5c313c2f404ea462"

C8_ALLOWED_PATHS = (
    "plans/temporal-langgraph-room-refactor.md",
    "plans/phase-8-production-hardening-execution.md",
    "plans/phase-8-production-hardening-test-batches.yaml",
    "plans/phase-8-owner-briefs.yaml",
    "docs/runbooks/temporal-first/phase-8-p8.0-baseline-inventory.md",
    "docs/runbooks/temporal-first/phase-8-p8.0-contract-pack.md",
    "docs/runbooks/temporal-first/phase-8-p8.0-review-closure.md",
    "tests/static/test_phase8_production_hardening_plan.py",
    "scripts/run_phase8_entry_checkpoint.py",
    "scripts/generate_phase8_entry_evidence.py",
    "tests/static/test_phase8_entry_runner.py",
    "tests/static/test_phase8_entry_evidence.py",
)
EXPECTED_CHANGE_STATUS = {
    path: ("M" if path == "plans/temporal-langgraph-room-refactor.md" else "A")
    for path in C8_ALLOWED_PATHS
}
SELECTORS = (
    "tests/static/test_phase7_engineering_checkpoint.py",
    "tests/static/test_phase8_production_hardening_plan.py",
    "tests/static/test_phase8_entry_runner.py",
    "tests/static/test_phase8_entry_evidence.py",
    "tests/static/test_temporal_refactor_traceability.py",
)
ARGV_TEMPLATE = (
    "D:/miniconda/python.exe",
    "-m",
    "pytest",
    "-q",
    *SELECTORS,
    "--junitxml={absolute_raw_report}",
)
INVOCATION_TEMPLATE = (
    "D:/miniconda/python.exe",
    "scripts/run_phase8_entry_checkpoint.py",
    "--execute",
    "--candidate-sha",
    "{candidate_sha}",
    "--run-dir",
    "{absolute_fresh_run_dir}",
    "--environment-id",
    "{environment_id}",
)
DEPENDENCY_PATHS = tuple(dict.fromkeys((*C8_ALLOWED_PATHS, *SELECTORS)))
PHASE7_EVIDENCE_PREFIX = (
    "test-reports/temporal-first/phase-7-20260725-4ddeeabb/phase-7-candidate"
)
PHASE7_CHECKPOINT_PATHS = (
    "docs/runbooks/temporal-first/phase-7-engineering-checkpoint.md",
    "tests/static/test_phase7_engineering_checkpoint.py",
)
PRODUCTION_CAPABILITY_KEYS = (
    "production_or_external_access",
    "production_credentials",
    "production_scheduler_off_activation",
    "production_v046_apply_or_switch",
    "production_load_chaos_pitr_dr_rotation",
    "v047_or_destructive_cleanup",
    "real_case_or_party_data",
    "production_traffic",
    "canary",
    "promotion",
    "implementation_authorized",
)
ENVIRONMENT_ALLOWLIST = (
    "SYSTEMROOT",
    "WINDIR",
    "COMSPEC",
    "PATH",
    "PATHEXT",
    "TEMP",
    "TMP",
    "HOME",
    "USERPROFILE",
    "LOCALAPPDATA",
    "APPDATA",
)
FORBIDDEN_OUTPUT_MARKERS = (
    b"aws_access_key_id=",
    b"aws_secret_access_key=",
    b"aws_session_token=",
    b"azure_client_secret=",
    b"google_application_credentials=",
    b"kubeconfig=",
    b"database_url=",
    b"db_password=",
    b"temporal_address=",
    b"temporal_namespace=",
    b"authorization: bearer ",
    b"-----begin private key-----",
    b"-----begin rsa private key-----",
)
SYNTHETIC_ENVIRONMENT_ID = re.compile(
    r"^(?:local|synthetic)-[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
)
MANIFEST_KEYS = {
    "schema_version",
    "phase",
    "release",
    "candidate_sha",
    "candidate_commit",
    "accepted_phase_7_candidate_C7",
    "accepted_phase_7_evidence_E7",
    "accepted_phase_7_checkpoint_A7",
    "candidate_parent",
    "candidate_changed_paths",
    "candidate_diff",
    "candidate_tree_sha",
    "dependency_git_blobs",
    "accepted_phase_7_authority",
    "git_tree_clean_before",
    "git_tree_clean_after",
    "environment",
    "environment_file",
    "environment_sha256",
    "commands",
    "status",
    "contract_gate",
    "p8_0_contract_gate",
    "implementation_authorized",
    "implementation",
    "retry_count",
    "resume_used",
    "report_reuse_used",
    "quarantine_used",
    "MIG-006",
    "MIG-007",
    "MIG-008",
    "production_capabilities",
    "verification_started_at",
    "verification_finished_at",
    "manifest_sha256",
    "self_seal_trust",
    "local_threat_model",
    "production_attestation_requirement",
    "attempt_ledger",
}
COMMAND_KEYS = {
    "id",
    "argv",
    "argv_sha256",
    "cwd",
    "resource_class",
    "shell",
    "started_at",
    "ended_at",
    "duration_ms",
    "exit_code",
    "candidate_sha_before",
    "candidate_sha_after",
    "stdout_path",
    "stdout_sha256",
    "stderr_path",
    "stderr_sha256",
    "raw_report_path",
    "raw_report_sha256",
    "normalized_report_path",
    "normalized_report_sha256",
    "report_kind",
    "tests",
    "failures",
    "errors",
    "skipped",
    "accepted",
    "failure_classification",
}
ENVIRONMENT_KEYS = {
    "schema_version",
    "environment_id",
    "captured_at",
    "candidate_sha",
    "candidate_tree_sha",
    "os",
    "os_release",
    "architecture",
    "python_version",
    "python_implementation",
    "python_executable",
    "git_version",
    "timezone",
    "dependency_git_blobs",
    "source_git_blobs",
    "command_argv_sha256",
    "subprocess_environment_keys",
    "pytest_plugin_autoload_disabled",
    "snapshot_sha256",
    "environment_sha256",
}


def _canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _json_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_json_bytes(value)).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(
        _read_authenticated_bytes(path, f"hash input {path.name}")
    ).hexdigest()


def _write_json(path: Path, value: Any) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as target:
        target.write(
            json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2) + "\n"
        )


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _assert_sha(value: str, field: str = "candidate SHA") -> str:
    normalized = value.strip().lower()
    if len(normalized) != 40 or any(ch not in "0123456789abcdef" for ch in normalized):
        raise EvidenceError(f"{field} must be a full lowercase 40-character Git SHA")
    return normalized


def _git(*arguments: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        shell=False,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=_git_environment(),
    )
    if check and completed.returncode:
        raise EvidenceError(
            f"git {' '.join(arguments)} failed: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def _git_bytes(*arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        shell=False,
        check=False,
        capture_output=True,
        env=_git_environment(),
    )
    if completed.returncode:
        raise EvidenceError(
            f"git {' '.join(arguments)} failed: "
            + completed.stderr.decode("utf-8", errors="replace").strip()
        )
    return completed.stdout


def _git_environment() -> dict[str, str]:
    environment = {
        key: os.environ[key]
        for key in ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATH", "PATHEXT", "TEMP", "TMP")
        if key in os.environ and os.environ[key]
    }
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "NUL" if os.name == "nt" else "/dev/null",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "LC_ALL": "C",
            "LANG": "C",
        }
    )
    return environment


def assert_no_git_object_rewrite_state() -> None:
    replacement_refs = _git(
        "for-each-ref", "--format=%(refname)", "refs/replace"
    ).splitlines()
    if replacement_refs:
        raise EvidenceError(
            "Git replacement refs are forbidden for exact-object Phase 8 validation"
        )
    graft_value = _git("rev-parse", "--git-path", "info/grafts")
    graft_path = Path(graft_value)
    if not graft_path.is_absolute():
        graft_path = ROOT / graft_path
    if graft_path.exists() or graft_path.is_symlink():
        raise EvidenceError(
            "legacy Git graft state is forbidden for exact-object Phase 8 validation"
        )


def load_matrix() -> dict[str, Any]:
    try:
        matrix = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exception:
        raise EvidenceError(
            f"cannot load Phase 8 test matrix: {exception}"
        ) from exception
    if not isinstance(matrix, dict) or matrix.get("phase") != 8:
        raise EvidenceError("entry runner loaded a non-Phase-8 matrix")
    if matrix.get("document_status") != "FROZEN_CONTRACT_CANDIDATE_AWAITING_BATCH_0":
        raise EvidenceError("Phase 8 matrix is not a frozen Batch 0 contract")
    return matrix


def _batch(matrix: dict[str, Any]) -> dict[str, Any]:
    try:
        batch = matrix["batches"]["batch_0_entry"]
    except (KeyError, TypeError) as exception:
        raise EvidenceError(
            "Phase 8 matrix has no Batch 0 entry contract"
        ) from exception
    if not isinstance(batch, dict):
        raise EvidenceError("Phase 8 Batch 0 contract is invalid")
    return batch


def source_contract(matrix: dict[str, Any]) -> dict[str, Any]:
    batch = _batch(matrix)
    gate = matrix.get("gate", {})
    topology = gate.get("candidate_topology", {})
    execution_gate = batch.get("execution_gate", {})
    commands = batch.get("source_commands")
    if gate.get("entry_decision") != "READY_FOR_EXACT_SHA_BATCH_0":
        raise EvidenceError("Phase 8 contract does not authorize exact-SHA Batch 0")
    if (
        gate.get("contract_gate_status") != "NOT_RUN"
        or gate.get("implementation_authorized") is not False
    ):
        raise EvidenceError("Phase 8 pre-entry authority ceiling drifted")
    trust = gate.get("local_engineering_trust_boundary", {})
    expected_trust = {
        "operator_threat_model": "NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR",
        "malicious_local_admin_resistance": "OUT_OF_SCOPE_FOR_P8_0",
        "sha256_self_seal_semantics": "BYTE_INTEGRITY_AND_DRIFT_DETECTION_ONLY",
        "self_seal_proves_source_or_execution_authenticity": False,
        "cryptographic_execution_attestation_present": False,
        "local_evidence_reusable_as_production_attestation": False,
        "production_cryptographic_execution_and_operator_attestation": "REQUIRED_EXTERNAL",
    }
    if any(trust.get(key) != value for key, value in expected_trust.items()):
        raise EvidenceError("Phase 8 local engineering trust boundary drifted")
    if tuple(topology.get("exact_allowed_paths", ())) != C8_ALLOWED_PATHS:
        raise EvidenceError("C8 exact path allowlist drifted")
    if execution_gate.get("required_candidate_sole_parent") != A7:
        raise EvidenceError("C8 required sole parent drifted from exact A7")
    if execution_gate.get("required_upstream_candidate_C7") != C7:
        raise EvidenceError("accepted Phase 7 C7 drifted")
    if execution_gate.get("required_upstream_evidence_E7") != E7:
        raise EvidenceError("accepted Phase 7 E7 drifted")
    if execution_gate.get("required_upstream_checkpoint_A7") != A7:
        raise EvidenceError("accepted Phase 7 A7 drifted")
    if batch.get("source_order") != [SOURCE_ID]:
        raise EvidenceError("Phase 8 source order drifted")
    invocation = batch.get("runner", {})
    if (
        invocation.get("shell") is not False
        or tuple(invocation.get("invocation_argv", ())) != INVOCATION_TEMPLATE
    ):
        raise EvidenceError("Phase 8 runner invocation argv drifted")
    if (
        not isinstance(commands, list)
        or len(commands) != 1
        or not isinstance(commands[0], dict)
    ):
        raise EvidenceError("Phase 8 Batch 0 must have exactly one source command")
    command = commands[0]
    expected = {
        "id": SOURCE_ID,
        "cwd": ".",
        "resource_class": "light",
        "report": NORMALIZED_REPORT_NAME,
        "report_kind": "PYTEST_JUNIT",
        "minimum_tests": MINIMUM_TESTS,
        "shell": False,
        "selectors": list(SELECTORS),
        "argv": list(ARGV_TEMPLATE),
    }
    for key, value in expected.items():
        if command.get(key) != value:
            raise EvidenceError(f"Phase 8 source command {key} drifted")
    evidence = batch.get("evidence_schema", {})
    if evidence.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError("Phase 8 execution manifest schema drifted")
    if evidence.get("filename") != MANIFEST_NAME:
        raise EvidenceError("Phase 8 execution manifest filename drifted")
    if evidence.get("terminal_green_status") != GREEN_STATUS:
        raise EvidenceError("Phase 8 terminal green status drifted")
    if (
        evidence.get("self_seal_is_cryptographic_identity_or_execution_attestation")
        is not False
        or evidence.get("self_seal_purpose")
        != "BYTE_INTEGRITY_AND_DRIFT_DETECTION_ONLY"
    ):
        raise EvidenceError("Phase 8 self-seal trust semantics drifted")
    retry = batch.get("retry_policy", {})
    if (
        retry.get("execution_attempt_limit_per_candidate") != 1
        or retry.get("retry_allowed") is not False
        or retry.get("same_sha_retry_allowed_only_for") != []
        or retry.get("quarantined_attempt_reports_reused") is not False
        or retry.get("mixed_attempt_or_candidate_reports_forbidden") is not True
    ):
        raise EvidenceError("Phase 8 compact Batch 0 retry policy drifted")
    return dict(command)


def _tree_entries(commit: str, prefix: str) -> dict[str, tuple[str, str, str]]:
    entries: dict[str, tuple[str, str, str]] = {}
    for line in _git("ls-tree", "-r", commit, "--", prefix).splitlines():
        metadata, separator, path = line.partition("\t")
        fields = metadata.split()
        normalized = path.replace("\\", "/")
        if not separator or len(fields) != 3 or normalized in entries:
            raise EvidenceError(f"malformed or duplicate Git tree entry under {prefix}")
        entries[normalized] = (fields[0], fields[1], fields[2])
    return entries


def authenticate_phase7_handoff() -> dict[str, Any]:
    assert_no_git_object_rewrite_state()
    for name, commit in (("C7", C7), ("E7", E7), ("A7", A7)):
        if _git("cat-file", "-t", commit) != "commit":
            raise EvidenceError(f"accepted Phase 7 {name} is not a Git commit")
    if _git("rev-list", "--parents", "-n", "1", E7).split() != [E7, C7]:
        raise EvidenceError(
            "accepted E7 is not the sole-parent direct child of exact C7"
        )
    if _git("rev-list", "--parents", "-n", "1", A7).split() != [A7, E7]:
        raise EvidenceError(
            "accepted A7 is not the sole-parent direct child of exact E7"
        )

    evidence_diff = _git(
        "diff-tree", "--no-commit-id", "--name-status", "-r", "--no-renames", C7, E7
    ).splitlines()
    if len(evidence_diff) != 47:
        raise EvidenceError(
            "accepted E7 evidence scope must contain exactly 47 additions"
        )
    evidence_paths: list[str] = []
    for line in evidence_diff:
        fields = line.split("\t")
        if (
            len(fields) != 2
            or fields[0] != "A"
            or not fields[1].replace("\\", "/").startswith(f"{PHASE7_EVIDENCE_PREFIX}/")
        ):
            raise EvidenceError(
                "accepted E7 contains a non-evidence or non-addition path"
            )
        evidence_paths.append(fields[1].replace("\\", "/"))
    evidence_entries = _tree_entries(E7, PHASE7_EVIDENCE_PREFIX)
    if set(evidence_entries) != set(evidence_paths):
        raise EvidenceError("accepted E7 evidence diff/tree scope disagrees")
    if any(
        mode not in {"100644", "100755"} or kind != "blob"
        for mode, kind, _ in evidence_entries.values()
    ):
        raise EvidenceError("accepted E7 evidence contains a non-regular Git blob")

    index_path = f"{PHASE7_EVIDENCE_PREFIX}/artifact-sha256.json"
    try:
        index = json.loads(_git_bytes("show", f"{E7}:{index_path}"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exception:
        raise EvidenceError("accepted E7 artifact index is invalid") from exception
    artifacts = index.get("artifacts") if isinstance(index, dict) else None
    if (
        not isinstance(artifacts, list)
        or len(artifacts) != 46
        or index.get("candidate_commit") != C7
    ):
        raise EvidenceError("accepted E7 artifact index shape or C7 binding drifted")
    expected_relative = {
        path.removeprefix(f"{PHASE7_EVIDENCE_PREFIX}/")
        for path in evidence_paths
        if path != index_path
    }
    indexed: set[str] = set()
    for artifact in artifacts:
        if not isinstance(artifact, dict) or set(artifact) != {
            "bytes",
            "path",
            "sha256",
        }:
            raise EvidenceError("accepted E7 artifact index entry is invalid")
        relative = artifact.get("path")
        if (
            not isinstance(relative, str)
            or relative in indexed
            or relative not in expected_relative
        ):
            raise EvidenceError(
                "accepted E7 artifact index path is duplicate or out of scope"
            )
        indexed.add(relative)
        payload = _git_bytes("show", f"{E7}:{PHASE7_EVIDENCE_PREFIX}/{relative}")
        if (
            artifact.get("bytes") != len(payload)
            or artifact.get("sha256") != hashlib.sha256(payload).hexdigest()
        ):
            raise EvidenceError(
                "accepted E7 artifact index hash/length binding drifted"
            )
    if indexed != expected_relative:
        raise EvidenceError("accepted E7 artifact index coverage drifted")

    checkpoint_diff = _git(
        "diff-tree", "--no-commit-id", "--name-status", "-r", "--no-renames", E7, A7
    ).splitlines()
    if checkpoint_diff != [f"A\t{path}" for path in PHASE7_CHECKPOINT_PATHS]:
        raise EvidenceError("accepted A7 checkpoint scope drifted")
    checkpoint_entries = [_ls_tree_blob(A7, path) for path in PHASE7_CHECKPOINT_PATHS]
    checkpoint = _git_bytes("show", f"{A7}:{PHASE7_CHECKPOINT_PATHS[0]}").decode(
        "utf-8", errors="strict"
    )
    for marker in (
        f"Candidate `C7`: `{C7}`",
        f"Evidence commit `E7`: `{E7}`",
        "engineering_checkpoint: PASS",
        "next_phase_permission: PHASE_8_ENGINEERING_ONLY",
        "MIG-006: PENDING_PROMOTION",
        "MIG-007: PENDING_PROMOTION",
        "507 tests with zero failures, errors, or skips",
    ):
        if marker not in checkpoint:
            raise EvidenceError(
                f"accepted A7 checkpoint is missing authority marker: {marker}"
            )
    return {
        "candidate_C7": C7,
        "evidence_E7": E7,
        "checkpoint_A7": A7,
        "evidence_prefix": PHASE7_EVIDENCE_PREFIX,
        "evidence_regular_blob_count": 47,
        "evidence_indexed_blob_count": 46,
        "checkpoint_scope": checkpoint_entries,
        "checkpoint_document_sha256": hashlib.sha256(
            checkpoint.encode("utf-8")
        ).hexdigest(),
    }


def _assert_candidate_object(candidate_sha: str) -> str:
    assert_no_git_object_rewrite_state()
    candidate = _assert_sha(candidate_sha)
    if _git("cat-file", "-t", candidate) != "commit":
        raise EvidenceError("candidate SHA is not a Git commit")
    resolved = _git("rev-parse", f"{candidate}^{{commit}}").lower()
    if resolved != candidate:
        raise EvidenceError("candidate SHA did not resolve to itself")
    return candidate


def _ls_tree_blob(candidate: str, path: str) -> dict[str, str]:
    output = _git("ls-tree", candidate, "--", path)
    lines = output.splitlines()
    if len(lines) != 1:
        raise EvidenceError(f"candidate dependency is missing or ambiguous: {path}")
    metadata, separator, returned_path = lines[0].partition("\t")
    fields = metadata.split()
    if not separator or len(fields) != 3 or returned_path.replace("\\", "/") != path:
        raise EvidenceError(f"candidate tree record is malformed: {path}")
    mode, object_type, object_sha = fields
    if mode not in {"100644", "100755"} or object_type != "blob":
        raise EvidenceError(f"candidate path is not a regular Git blob: {path}")
    return {"path": path, "mode": mode, "type": object_type, "git_blob_sha": object_sha}


def assert_contract_candidate(candidate_sha: str) -> dict[str, Any]:
    candidate = _assert_candidate_object(candidate_sha)
    phase7_authority = authenticate_phase7_handoff()
    parents = _git("rev-list", "--parents", "-n", "1", candidate).split()
    if parents != [candidate, A7]:
        raise EvidenceError("C8 must be the sole-parent direct child of exact A7")
    records = _git(
        "diff-tree",
        "--no-commit-id",
        "--name-status",
        "-r",
        "--no-renames",
        A7,
        candidate,
    ).splitlines()
    observed: dict[str, str] = {}
    for line in records:
        fields = line.split("\t")
        if len(fields) != 2 or fields[0] not in {"A", "M"}:
            raise EvidenceError(f"C8 contains a forbidden diff record: {line}")
        path = fields[1].replace("\\", "/")
        if path in observed:
            raise EvidenceError(f"C8 contains a duplicate diff path: {path}")
        observed[path] = fields[0]
    if observed != EXPECTED_CHANGE_STATUS:
        missing = sorted(set(EXPECTED_CHANGE_STATUS) - set(observed))
        extra = sorted(set(observed) - set(EXPECTED_CHANGE_STATUS))
        drift = sorted(
            path
            for path in set(observed) & set(EXPECTED_CHANGE_STATUS)
            if observed[path] != EXPECTED_CHANGE_STATUS[path]
        )
        raise EvidenceError(
            f"C8 exact path/status diff drifted: missing={missing}, extra={extra}, status={drift}"
        )
    changed_records = [_ls_tree_blob(candidate, path) for path in C8_ALLOWED_PATHS]
    for record in changed_records:
        record["status"] = observed[record["path"]]
    dependency_blobs = [_ls_tree_blob(candidate, path) for path in DEPENDENCY_PATHS]
    return {
        "candidate_sha": candidate,
        "candidate_parent": A7,
        "candidate_changed_paths": list(C8_ALLOWED_PATHS),
        "candidate_diff": changed_records,
        "dependency_blobs": dependency_blobs,
        "candidate_tree_sha": _git("rev-parse", f"{candidate}^{{tree}}"),
        "phase7_authority": phase7_authority,
    }


def _git_status() -> str:
    return _git("status", "--porcelain=v1", "--untracked-files=all")


def assert_clean_detached_candidate(candidate: str) -> None:
    if _git("rev-parse", "HEAD").lower() != candidate:
        raise EvidenceError("worktree HEAD does not equal the exact C8 candidate")
    if _git("symbolic-ref", "-q", "HEAD", check=False):
        raise EvidenceError("Batch 0 execution requires a detached HEAD")
    if _git_status():
        raise EvidenceError("Batch 0 execution requires a clean worktree")


def _is_link_or_reparse(path: Path) -> bool:
    metadata = os.lstat(path)
    if stat.S_ISLNK(metadata.st_mode):
        return True
    return bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def _assert_regular_single_link(path: Path, context: str) -> None:
    if not path.is_file() or _is_link_or_reparse(path):
        raise EvidenceError(f"{context} must be a regular non-link file")
    if os.lstat(path).st_nlink != 1:
        raise EvidenceError(f"{context} must not be a hard-link alias")


def _file_identity(metadata: os.stat_result) -> tuple[int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def _read_authenticated_bytes(path: Path, context: str) -> bytes:
    _assert_regular_single_link(path, context)
    before = os.lstat(path)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise EvidenceError(
            f"cannot open {context} without following aliases"
        ) from exception
    try:
        opened_before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened_before.st_mode)
            or opened_before.st_nlink != 1
            or _file_identity(opened_before) != _file_identity(before)
        ):
            raise EvidenceError(f"{context} identity changed before authenticated read")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        opened_after = os.fstat(descriptor)
        if _file_identity(opened_after) != _file_identity(opened_before):
            raise EvidenceError(f"{context} changed during authenticated read")
    finally:
        os.close(descriptor)
    after = os.lstat(path)
    if _file_identity(after) != _file_identity(before) or _is_link_or_reparse(path):
        raise EvidenceError(f"{context} path identity changed after authenticated read")
    return b"".join(chunks)


def _read_authenticated_json(path: Path, context: str) -> Any:
    payload = _read_authenticated_bytes(path, context)
    try:
        return json.loads(payload)
    except (json.JSONDecodeError, UnicodeDecodeError) as exception:
        raise EvidenceError(f"{context} is not valid UTF-8 JSON") from exception


def _parse_authenticated_junit(path: Path, context: str) -> dict[str, Any]:
    payload = _read_authenticated_bytes(path, context)
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exception:
        raise EvidenceError(f"{context} is not valid JUnit XML") from exception
    if root.tag not in {"testsuite", "testsuites"}:
        raise EvidenceError(f"{context} has an unsupported JUnit root")
    cases = root.findall(".//testcase")
    totals = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
    for case in cases:
        outcomes = [
            outcome
            for outcome in ("failure", "error", "skipped")
            if case.find(outcome) is not None
        ]
        if len(outcomes) > 1:
            raise EvidenceError(f"{context} testcase has multiple terminal outcomes")
        if outcomes:
            totals[f"{outcomes[0]}s" if outcomes[0] != "skipped" else "skipped"] += 1

    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    if not suites:
        raise EvidenceError(f"{context} contains no JUnit suite")
    for suite in suites:
        suite_cases = suite.findall(".//testcase")
        actual = {
            "tests": len(suite_cases),
            "failures": sum(case.find("failure") is not None for case in suite_cases),
            "errors": sum(case.find("error") is not None for case in suite_cases),
            "skipped": sum(case.find("skipped") is not None for case in suite_cases),
        }
        try:
            declared = {key: int(suite.attrib[key]) for key in actual}
        except (KeyError, ValueError) as exception:
            raise EvidenceError(
                f"{context} suite totals are missing or invalid"
            ) from exception
        if declared != actual:
            raise EvidenceError(f"{context} declared suite totals drift from testcases")
    declared_root_keys = {key for key in totals if key in root.attrib}
    if declared_root_keys and declared_root_keys != set(totals):
        raise EvidenceError(f"{context} root totals are incomplete")
    if declared_root_keys:
        try:
            declared_root = {key: int(root.attrib[key]) for key in totals}
        except ValueError as exception:
            raise EvidenceError(f"{context} root totals are invalid") from exception
        if declared_root != totals:
            raise EvidenceError(f"{context} declared root totals drift from testcases")
    if totals["tests"] == 0:
        raise EvidenceError(f"{context} contains no testcases")
    return {
        **totals,
        "candidate_commit": root.attrib.get("candidate_commit"),
        "command_id": root.attrib.get("source_command_id"),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }


def _assert_ancestry_has_no_alias(path: Path) -> None:
    current = path
    while True:
        if not current.exists() or _is_link_or_reparse(current):
            raise EvidenceError(
                "run-directory ancestry is missing or contains an alias"
            )
        if current.parent == current:
            break
        current = current.parent


def _stable_approved_run_root() -> Path:
    if APPROVED_RUN_ROOT is not None:
        return APPROVED_RUN_ROOT
    common_git = Path(
        _git("rev-parse", "--path-format=absolute", "--git-common-dir")
    ).resolve(strict=True)
    repository_root = common_git.parent
    approved = repository_root.parent / ".codex-run"
    for worktree in (ROOT.resolve(strict=True), repository_root.resolve(strict=True)):
        try:
            approved.resolve(strict=False).relative_to(worktree)
        except ValueError:
            continue
        raise EvidenceError("derived approved run root is inside a Git worktree")
    return approved


def _approved_run_parent() -> Path:
    approved_run_root = _stable_approved_run_root()
    parent = approved_run_root.parent
    if not parent.is_dir():
        raise EvidenceError("approved external run-root parent is unavailable")
    _assert_ancestry_has_no_alias(parent.resolve(strict=True))
    if not approved_run_root.exists():
        approved_run_root.mkdir(exist_ok=False)
    if not approved_run_root.is_dir() or _is_link_or_reparse(approved_run_root):
        raise EvidenceError("approved external run root is not a regular directory")
    _assert_ancestry_has_no_alias(approved_run_root.resolve(strict=True))
    return approved_run_root.resolve(strict=True)


def _assert_existing_run_root(run_root: Path) -> Path:
    approved = _approved_run_parent()
    if (
        not run_root.is_absolute()
        or not run_root.is_dir()
        or _is_link_or_reparse(run_root)
    ):
        raise EvidenceError("run root is no longer an exact regular directory")
    resolved = run_root.resolve(strict=True)
    if resolved.parent != approved or resolved != approved / run_root.name:
        raise EvidenceError("run root escaped the exact approved external root")
    _assert_ancestry_has_no_alias(resolved)
    return resolved


def _attempt_ledger_directory() -> Path:
    approved = _approved_run_parent()
    ledger = approved / ".phase8-entry-attempts"
    if not ledger.exists():
        try:
            ledger.mkdir(exist_ok=False)
        except FileExistsError:
            pass
    if not ledger.is_dir() or _is_link_or_reparse(ledger):
        raise EvidenceError("Phase 8 attempt ledger is not a regular directory")
    _assert_ancestry_has_no_alias(ledger.resolve(strict=True))
    return ledger.resolve(strict=True)


def claim_candidate_attempt(
    candidate: str, run_root: Path, environment_id: str
) -> dict[str, Any]:
    ledger = _attempt_ledger_directory()
    marker = ledger / f"{candidate}.json"
    claim = {
        "schema_version": "phase8-entry-attempt-claim.v1",
        "candidate_sha": candidate,
        "attempt_number": 1,
        "run_dir": str(run_root.resolve(strict=False)),
        "environment_id": environment_id,
        "claimed_at": _utc_now(),
        "retry_allowed": False,
        "self_seal_trust": "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION",
    }
    try:
        _write_json(marker, claim)
    except FileExistsError as exception:
        raise EvidenceError(
            "exact C8 already has a durable Phase 8 Batch 0 attempt claim"
        ) from exception
    _assert_regular_single_link(marker, "Phase 8 candidate attempt marker")
    return {
        "path": str(marker),
        "sha256": _sha256(marker),
        "candidate_sha": candidate,
        "attempt_number": 1,
        "run_dir": str(run_root.resolve(strict=False)),
    }


def assert_fresh_external_run_directory(run_dir: Path) -> Path:
    if not run_dir.is_absolute():
        raise EvidenceError("--run-dir must be an absolute path")
    unresolved = run_dir
    if unresolved.exists() or unresolved.is_symlink():
        raise EvidenceError("Phase 8 Batch 0 run directory must be fresh")
    approved = _approved_run_parent()
    resolved_parent = unresolved.parent.resolve(strict=True)
    if resolved_parent != approved:
        raise EvidenceError(
            "run directory must be directly under the approved external root"
        )
    if not re.fullmatch(r"phase8-entry-[a-z0-9][a-z0-9-]{5,95}", unresolved.name):
        raise EvidenceError("run-directory name is not a strict Phase 8 entry token")
    candidate = approved / unresolved.name
    if candidate.resolve(strict=False) != candidate:
        raise EvidenceError("run directory contains a path alias")
    return candidate


def _capture_environment(
    environment_id: str, candidate: dict[str, Any], command_argv: Sequence[str]
) -> dict[str, Any]:
    normalized_id = _validate_environment_id(environment_id)

    git_version = _git("--version")
    timezone_name = datetime.now().astimezone().tzname() or "UNKNOWN"
    snapshot: dict[str, Any] = {
        "schema_version": "phase8-entry-source-tree-environment.v1",
        "environment_id": normalized_id,
        "captured_at": _utc_now(),
        "candidate_sha": candidate["candidate_sha"],
        "candidate_tree_sha": candidate["candidate_tree_sha"],
        "os": platform.system(),
        "os_release": platform.release(),
        "architecture": platform.machine(),
        "python_version": platform.python_version(),
        "python_implementation": platform.python_implementation(),
        "python_executable": command_argv[0],
        "git_version": git_version,
        "timezone": timezone_name,
        "dependency_git_blobs": candidate["dependency_blobs"],
        "source_git_blobs": [
            record
            for record in candidate["dependency_blobs"]
            if record["path"] in SELECTORS
        ],
        "command_argv_sha256": _json_sha256(list(command_argv)),
        "subprocess_environment_keys": sorted(_subprocess_environment()),
        "pytest_plugin_autoload_disabled": True,
    }
    digest = _json_sha256(snapshot)
    snapshot["snapshot_sha256"] = digest
    snapshot["environment_sha256"] = digest
    return snapshot


def _validate_environment_id(environment_id: str) -> str:
    normalized_id = environment_id.strip().lower()
    if not SYNTHETIC_ENVIRONMENT_ID.fullmatch(normalized_id) or any(
        forbidden in normalized_id
        for forbidden in (
            "prod",
            "stage",
            "tenant",
            "case",
            "party",
            "secret",
            "token",
            "key",
        )
    ):
        raise EvidenceError(
            "--environment-id must be a strict local/synthetic non-sensitive token"
        )
    return normalized_id


def _subprocess_environment(sandbox: Path | None = None) -> dict[str, str]:
    environment = {
        key: os.environ[key]
        for key in ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATH", "PATHEXT")
        if key in os.environ and os.environ[key]
    }
    sandbox = (
        sandbox or (_stable_approved_run_root() / "isolated-environment")
    ).resolve()
    home = sandbox / "home"
    temporary = sandbox / "tmp"
    environment.update(
        {
            "HOME": str(home),
            "USERPROFILE": str(home),
            "TEMP": str(temporary),
            "TMP": str(temporary),
            "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
            "PYTHONHASHSEED": "0",
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONIOENCODING": "utf-8",
        }
    )
    return environment


def _render_argv(command: dict[str, Any], raw_report: Path) -> list[str]:
    rendered = [
        argument.replace("{absolute_raw_report}", str(raw_report.resolve()))
        for argument in command["argv"]
    ]
    if any("{" in argument or "}" in argument for argument in rendered):
        raise EvidenceError("source argv contains an unresolved placeholder")
    if tuple(command["argv"]) != ARGV_TEMPLATE:
        raise EvidenceError("source argv drifted from the frozen allowlist")
    executable = Path(rendered[0])
    if not executable.is_absolute() or not executable.is_file():
        raise EvidenceError("frozen Python executable is unavailable")
    return rendered


def _run_source(
    argv: Sequence[str], stdout_path: Path, stderr_path: Path, sandbox: Path
) -> tuple[str, str, int, int]:
    started_at = _utc_now()
    started = time.perf_counter_ns()
    with (
        stdout_path.open("x", encoding="utf-8", newline="\n") as stdout,
        stderr_path.open("x", encoding="utf-8", newline="\n") as stderr,
    ):
        process = subprocess.run(
            list(argv),
            cwd=ROOT,
            shell=False,
            check=False,
            stdout=stdout,
            stderr=stderr,
            env=_subprocess_environment(sandbox),
        )
    duration_ms = max(0, (time.perf_counter_ns() - started) // 1_000_000)
    return started_at, _utc_now(), duration_ms, process.returncode


def _assert_no_sensitive_output(*paths: Path) -> None:
    for path in paths:
        payload = _read_authenticated_bytes(
            path, f"source artifact {path.name}"
        ).lower()
        for marker in FORBIDDEN_OUTPUT_MARKERS:
            if marker in payload:
                raise EvidenceError(
                    f"source artifact {path.name} contains a forbidden secret/config marker"
                )


def _seal_manifest(manifest: dict[str, Any]) -> str:
    unsigned = dict(manifest)
    unsigned.pop("manifest_sha256", None)
    digest = _json_sha256(unsigned)
    manifest["manifest_sha256"] = digest
    return digest


def _assert_manifest_seal(manifest: dict[str, Any]) -> None:
    recorded = manifest.get("manifest_sha256")
    unsigned = dict(manifest)
    unsigned.pop("manifest_sha256", None)
    if not isinstance(recorded, str) or recorded != _json_sha256(unsigned):
        raise EvidenceError("Phase 8 execution manifest seal is invalid")


def entry_plan(candidate_sha: str) -> dict[str, Any]:
    matrix = load_matrix()
    command = source_contract(matrix)
    candidate = assert_contract_candidate(candidate_sha)
    raw_placeholder = "{absolute_fresh_run_dir}/p/02-junit.xml"
    argv = [
        argument.replace("{absolute_raw_report}", raw_placeholder)
        for argument in command["argv"]
    ]
    return {
        "schema_version": "phase8-entry-run-plan.v1",
        "phase": 8,
        "candidate_sha": candidate["candidate_sha"],
        "candidate_parent": candidate["candidate_parent"],
        "candidate_changed_paths": candidate["candidate_changed_paths"],
        "execution_order": [SOURCE_ID],
        "commands": [
            {
                "id": SOURCE_ID,
                "argv": argv,
                "argv_sha256": _json_sha256(argv),
                "cwd": ".",
                "resource_class": "light",
                "shell": False,
                "minimum_tests": MINIMUM_TESTS,
                "report": NORMALIZED_REPORT_NAME,
            }
        ],
        "requires_clean_detached_exact_candidate": True,
        "requires_fresh_external_run_directory": True,
        "retry_allowed": False,
        "resume_allowed": False,
        "report_reuse_allowed": False,
        "contract_gate": "P8.0_NOT_RUN",
        "implementation_authorized": False,
        "green_result_ceiling": GREEN_STATUS,
        "self_seal_trust": "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION",
        "local_threat_model": "HOSTILE_LOCAL_ADMIN_OR_OPERATOR_OUT_OF_SCOPE",
        "production_attestation_requirement": (
            "EXTERNALLY_ATTESTED_CI_OIDC_KMS_OR_EQUIVALENT_SIGNED_EXECUTION_RECEIPT"
        ),
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "production_capabilities": {key: False for key in PRODUCTION_CAPABILITY_KEYS},
    }


def execute_checkpoint(
    *, candidate_sha: str, run_root: Path, environment_id: str
) -> dict[str, Any]:
    matrix = load_matrix()
    command = source_contract(matrix)
    candidate = assert_contract_candidate(candidate_sha)
    exact_sha = candidate["candidate_sha"]
    assert_clean_detached_candidate(exact_sha)
    run_root = assert_fresh_external_run_directory(run_root)
    normalized_environment_id = _validate_environment_id(environment_id)

    raw_dir = run_root / "p"
    sandbox = raw_dir / "sandbox"
    raw_report = raw_dir / "02-junit.xml"
    stdout_path = raw_dir / "00-stdout.log"
    stderr_path = raw_dir / "01-stderr.log"
    normalized_report = run_root / NORMALIZED_REPORT_NAME
    environment_path = run_root / ENVIRONMENT_NAME
    manifest_path = run_root / MANIFEST_NAME
    argv = _render_argv(command, raw_report)
    attempt_ledger = claim_candidate_attempt(
        exact_sha, run_root, normalized_environment_id
    )

    run_root.mkdir(parents=False, exist_ok=False)
    raw_dir.mkdir(exist_ok=False)
    (sandbox / "home").mkdir(parents=True, exist_ok=False)
    (sandbox / "tmp").mkdir(parents=True, exist_ok=False)
    run_root = _assert_existing_run_root(run_root)
    raw_dir = run_root / "p"
    raw_report = raw_dir / "02-junit.xml"
    stdout_path = raw_dir / "00-stdout.log"
    stderr_path = raw_dir / "01-stderr.log"
    normalized_report = run_root / NORMALIZED_REPORT_NAME
    environment_path = run_root / ENVIRONMENT_NAME
    manifest_path = run_root / MANIFEST_NAME
    if _is_link_or_reparse(raw_dir):
        raise EvidenceError("fresh run directory became a link or reparse point")

    environment = _capture_environment(normalized_environment_id, candidate, argv)
    _write_json(environment_path, environment)
    _assert_regular_single_link(environment_path, "source environment snapshot")
    candidate_sha_before = _git("rev-parse", "HEAD").lower()
    git_tree_clean_before = not bool(_git_status())
    if candidate_sha_before != exact_sha or not git_tree_clean_before:
        raise EvidenceError("candidate changed before source execution")

    started_at, ended_at, duration_ms, exit_code = _run_source(
        argv, stdout_path, stderr_path, sandbox
    )
    _assert_existing_run_root(run_root)
    candidate_sha_after = _git("rev-parse", "HEAD").lower()
    git_tree_clean_after = not bool(_git_status())
    if candidate_sha_after != exact_sha or not git_tree_clean_after:
        raise EvidenceError("candidate changed during source execution")
    if exit_code != 0:
        raise EvidenceError(f"{SOURCE_ID} exited with code {exit_code}")
    if not raw_report.is_file() or raw_report.is_symlink():
        raise EvidenceError("static source did not produce its required JUnit report")
    _assert_no_sensitive_output(stdout_path, stderr_path, raw_report)
    raw_totals = _parse_authenticated_junit(raw_report, "raw JUnit")
    if (
        raw_totals["tests"] < MINIMUM_TESTS
        or raw_totals["failures"]
        or raw_totals["errors"]
        or raw_totals["skipped"]
    ):
        raise EvidenceError(f"{SOURCE_ID} raw JUnit is not all-pass zero-skip")

    report = normalize_source_reports(
        [raw_report],
        normalized_report,
        candidate_commit=exact_sha,
        command_id=SOURCE_ID,
    )
    totals = report.totals
    if totals["tests"] < MINIMUM_TESTS:
        raise EvidenceError(
            f"{SOURCE_ID} produced {totals['tests']} tests; minimum is {MINIMUM_TESTS}"
        )
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise EvidenceError(f"{SOURCE_ID} is not all-pass zero-skip: {totals}")
    _assert_no_sensitive_output(normalized_report)
    _assert_existing_run_root(run_root)

    command_record = {
        "id": SOURCE_ID,
        "argv": argv,
        "argv_sha256": _json_sha256(argv),
        "cwd": ".",
        "resource_class": "light",
        "shell": False,
        "started_at": started_at,
        "ended_at": ended_at,
        "duration_ms": duration_ms,
        "exit_code": exit_code,
        "candidate_sha_before": candidate_sha_before,
        "candidate_sha_after": candidate_sha_after,
        "stdout_path": "p/00-stdout.log",
        "stdout_sha256": _sha256(stdout_path),
        "stderr_path": "p/01-stderr.log",
        "stderr_sha256": _sha256(stderr_path),
        "raw_report_path": "p/02-junit.xml",
        "raw_report_sha256": _sha256(raw_report),
        "normalized_report_path": NORMALIZED_REPORT_NAME,
        "normalized_report_sha256": _sha256(normalized_report),
        "report_kind": "PYTEST_JUNIT",
        "tests": totals["tests"],
        "failures": totals["failures"],
        "errors": totals["errors"],
        "skipped": totals["skipped"],
        "accepted": True,
        "failure_classification": "NONE",
    }
    release_date = (
        datetime.fromisoformat(started_at).astimezone(timezone.utc).strftime("%Y%m%d")
    )
    release = f"phase-8-entry-{release_date}-{exact_sha[:12]}"
    manifest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "phase": 8,
        "release": release,
        "candidate_sha": exact_sha,
        "candidate_commit": exact_sha,
        "accepted_phase_7_candidate_C7": C7,
        "accepted_phase_7_evidence_E7": E7,
        "accepted_phase_7_checkpoint_A7": A7,
        "candidate_parent": candidate["candidate_parent"],
        "candidate_changed_paths": candidate["candidate_changed_paths"],
        "candidate_diff": candidate["candidate_diff"],
        "candidate_tree_sha": candidate["candidate_tree_sha"],
        "dependency_git_blobs": candidate["dependency_blobs"],
        "accepted_phase_7_authority": candidate["phase7_authority"],
        "git_tree_clean_before": git_tree_clean_before,
        "git_tree_clean_after": git_tree_clean_after,
        "environment": environment,
        "environment_file": ENVIRONMENT_NAME,
        "environment_sha256": _sha256(environment_path),
        "commands": [command_record],
        "status": GREEN_STATUS,
        "contract_gate": "P8.0_NOT_RUN",
        "p8_0_contract_gate": "REMAINS_NOT_RUN_UNTIL_A8",
        "implementation_authorized": False,
        "implementation": "REMAINS_BLOCKED_UNTIL_A8",
        "retry_count": 0,
        "resume_used": False,
        "report_reuse_used": False,
        "quarantine_used": False,
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "production_capabilities": {key: False for key in PRODUCTION_CAPABILITY_KEYS},
        "verification_started_at": started_at,
        "verification_finished_at": ended_at,
        "self_seal_trust": "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION",
        "local_threat_model": "HOSTILE_LOCAL_ADMIN_OR_OPERATOR_OUT_OF_SCOPE",
        "production_attestation_requirement": (
            "EXTERNALLY_ATTESTED_CI_OIDC_KMS_OR_EQUIVALENT_SIGNED_EXECUTION_RECEIPT"
        ),
        "attempt_ledger": attempt_ledger,
    }
    _seal_manifest(manifest)
    _write_json(manifest_path, manifest)
    _assert_regular_single_link(manifest_path, "Phase 8 execution manifest")
    load_green_manifest(manifest_path, expected_candidate=exact_sha)
    assert_clean_detached_candidate(exact_sha)
    return manifest


def _bound_file(run_root: Path, relative: Any, expected_sha: Any, context: str) -> Path:
    if not isinstance(relative, str) or not isinstance(expected_sha, str):
        raise EvidenceError(f"{context} binding is incomplete")
    path = (run_root / relative).resolve()
    if not path.is_relative_to(run_root.resolve()):
        raise EvidenceError(f"{context} path escapes, is missing, or is not regular")
    payload = _read_authenticated_bytes(path, context)
    if hashlib.sha256(payload).hexdigest() != expected_sha:
        raise EvidenceError(f"{context} SHA-256 drifted")
    return path


def load_green_manifest(
    path_or_run_dir: Path, expected_candidate: str | None = None
) -> dict[str, Any]:
    supplied = Path(path_or_run_dir)
    path = supplied / MANIFEST_NAME if supplied.is_dir() else supplied
    if path.name != MANIFEST_NAME:
        raise EvidenceError("Phase 8 execution manifest has the wrong filename")
    run_root = _assert_existing_run_root(path.parent)
    path = run_root / MANIFEST_NAME
    try:
        manifest = _read_authenticated_json(path, "Phase 8 execution manifest")
    except OSError as exception:
        raise EvidenceError(
            f"cannot load Phase 8 green manifest: {exception}"
        ) from exception
    if (
        not isinstance(manifest, dict)
        or manifest.get("schema_version") != SCHEMA_VERSION
    ):
        raise EvidenceError("not a Phase 8 entry execution manifest")
    if set(manifest) != MANIFEST_KEYS:
        raise EvidenceError(
            "Phase 8 manifest top-level schema has missing or extra fields"
        )
    _assert_manifest_seal(manifest)
    if manifest.get("status") != GREEN_STATUS:
        raise EvidenceError("Phase 8 source manifest is not terminal green")
    if (
        manifest.get("self_seal_trust")
        != "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION"
        or manifest.get("local_threat_model")
        != "HOSTILE_LOCAL_ADMIN_OR_OPERATOR_OUT_OF_SCOPE"
        or manifest.get("production_attestation_requirement")
        != "EXTERNALLY_ATTESTED_CI_OIDC_KMS_OR_EQUIVALENT_SIGNED_EXECUTION_RECEIPT"
    ):
        raise EvidenceError("manifest local engineering trust ceiling drifted")
    candidate = _assert_sha(str(manifest.get("candidate_sha", "")))
    expected_release = re.fullmatch(
        rf"phase-8-entry-(\d{{8}})-{re.escape(candidate[:12])}",
        str(manifest.get("release", "")),
    )
    if expected_release is None:
        raise EvidenceError("manifest release ID is not bound to its candidate SHA")
    if manifest.get("candidate_commit") != candidate:
        raise EvidenceError("manifest candidate aliases disagree")
    if expected_candidate is not None and candidate != _assert_sha(expected_candidate):
        raise EvidenceError("manifest belongs to another candidate")
    if manifest.get("candidate_parent") != A7:
        raise EvidenceError("manifest candidate parent is not exact A7")
    current_candidate = assert_contract_candidate(candidate)
    exact_bindings = {
        "candidate_parent": current_candidate["candidate_parent"],
        "candidate_changed_paths": current_candidate["candidate_changed_paths"],
        "candidate_diff": current_candidate["candidate_diff"],
        "candidate_tree_sha": current_candidate["candidate_tree_sha"],
        "dependency_git_blobs": current_candidate["dependency_blobs"],
        "accepted_phase_7_authority": current_candidate["phase7_authority"],
    }
    for key, expected in exact_bindings.items():
        if manifest.get(key) != expected:
            raise EvidenceError(
                f"manifest {key} drifted from exact candidate Git objects"
            )
    if (
        manifest.get("accepted_phase_7_candidate_C7") != C7
        or manifest.get("accepted_phase_7_evidence_E7") != E7
        or manifest.get("accepted_phase_7_checkpoint_A7") != A7
    ):
        raise EvidenceError("manifest accepted Phase 7 chain drifted")
    if (
        manifest.get("git_tree_clean_before") is not True
        or manifest.get("git_tree_clean_after") is not True
    ):
        raise EvidenceError(
            "manifest does not prove a clean candidate before and after"
        )
    if (
        manifest.get("contract_gate") != "P8.0_NOT_RUN"
        or manifest.get("implementation_authorized") is not False
    ):
        raise EvidenceError(
            "manifest exceeded the P8.0 pre-checkpoint authority ceiling"
        )
    for migration in ("MIG-006", "MIG-007", "MIG-008"):
        if manifest.get(migration) != "PENDING_PROMOTION":
            raise EvidenceError(f"manifest illegally changed {migration}")
    capabilities = manifest.get("production_capabilities")
    if (
        not isinstance(capabilities, dict)
        or set(capabilities) != set(PRODUCTION_CAPABILITY_KEYS)
        or any(value is not False for value in capabilities.values())
    ):
        raise EvidenceError(
            "manifest exposes a production or implementation capability"
        )
    if any(
        (
            manifest.get("retry_count") != 0,
            manifest.get("resume_used") is not False,
            manifest.get("report_reuse_used") is not False,
            manifest.get("quarantine_used") is not False,
        )
    ):
        raise EvidenceError("manifest contains retry, resume, reuse, or quarantine")
    commands = manifest.get("commands")
    if not isinstance(commands, list) or len(commands) != 1:
        raise EvidenceError("manifest must contain exactly one source command")
    record = commands[0]
    if not isinstance(record, dict) or set(record) != COMMAND_KEYS:
        raise EvidenceError("manifest command schema has missing or extra fields")
    if (
        not isinstance(record, dict)
        or record.get("id") != SOURCE_ID
        or record.get("accepted") is not True
        or record.get("exit_code") != 0
        or record.get("candidate_sha_before") != candidate
        or record.get("candidate_sha_after") != candidate
        or record.get("shell") is not False
        or record.get("tests", 0) < MINIMUM_TESTS
        or any(record.get(key) != 0 for key in ("failures", "errors", "skipped"))
    ):
        raise EvidenceError(
            "manifest source command is not exact-SHA all-pass zero-skip"
        )
    command_contract = source_contract(load_matrix())
    expected_raw_path = run_root / "p/02-junit.xml"
    expected_argv = _render_argv(command_contract, expected_raw_path)
    exact_command_fields = {
        "argv": expected_argv,
        "argv_sha256": _json_sha256(expected_argv),
        "cwd": ".",
        "resource_class": "light",
        "shell": False,
        "report_kind": "PYTEST_JUNIT",
        "stdout_path": "p/00-stdout.log",
        "stderr_path": "p/01-stderr.log",
        "raw_report_path": "p/02-junit.xml",
        "normalized_report_path": NORMALIZED_REPORT_NAME,
        "failure_classification": "NONE",
    }
    for key, expected in exact_command_fields.items():
        if record.get(key) != expected:
            raise EvidenceError(f"manifest command {key} drifted from frozen Batch 0")
    if (
        not isinstance(record.get("started_at"), str)
        or not isinstance(record.get("ended_at"), str)
        or not isinstance(record.get("duration_ms"), int)
        or record["duration_ms"] < 0
    ):
        raise EvidenceError("manifest command timing fields are invalid")
    try:
        started = datetime.fromisoformat(record["started_at"])
        ended = datetime.fromisoformat(record["ended_at"])
    except ValueError as exception:
        raise EvidenceError("manifest command timestamps are invalid") from exception
    if (
        started.tzinfo is None
        or ended.tzinfo is None
        or ended < started
        or manifest.get("verification_started_at") != record["started_at"]
        or manifest.get("verification_finished_at") != record["ended_at"]
        or expected_release.group(1)
        != started.astimezone(timezone.utc).strftime("%Y%m%d")
    ):
        raise EvidenceError(
            "manifest command/release timestamp ordering or binding drifted"
        )
    environment_path = _bound_file(
        run_root,
        manifest.get("environment_file"),
        manifest.get("environment_sha256"),
        "environment",
    )
    environment = _read_authenticated_json(environment_path, "environment")
    if not isinstance(environment, dict) or set(environment) != ENVIRONMENT_KEYS:
        raise EvidenceError("environment schema has missing or extra fields")
    if environment != manifest.get("environment"):
        raise EvidenceError("manifest environment object drifted from its bound file")
    unsigned_environment = dict(environment)
    environment_seal = unsigned_environment.pop("snapshot_sha256", None)
    duplicate_environment_seal = unsigned_environment.pop("environment_sha256", None)
    calculated_environment_seal = _json_sha256(unsigned_environment)
    if (
        environment_seal != calculated_environment_seal
        or duplicate_environment_seal != calculated_environment_seal
    ):
        raise EvidenceError("environment snapshot seal is invalid")
    environment_expected = {
        "candidate_sha": candidate,
        "candidate_tree_sha": current_candidate["candidate_tree_sha"],
        "dependency_git_blobs": current_candidate["dependency_blobs"],
        "source_git_blobs": [
            item
            for item in current_candidate["dependency_blobs"]
            if item["path"] in SELECTORS
        ],
        "command_argv_sha256": _json_sha256(expected_argv),
        "subprocess_environment_keys": sorted(_subprocess_environment()),
        "pytest_plugin_autoload_disabled": True,
    }
    for key, expected in environment_expected.items():
        if environment.get(key) != expected:
            raise EvidenceError(
                f"environment {key} drifted from exact candidate/source"
            )
    environment_id = environment.get("environment_id")
    if not isinstance(environment_id, str) or not SYNTHETIC_ENVIRONMENT_ID.fullmatch(
        environment_id
    ):
        raise EvidenceError("environment ID is not a strict synthetic token")
    try:
        captured = datetime.fromisoformat(str(environment.get("captured_at", "")))
    except ValueError as exception:
        raise EvidenceError("environment captured_at is invalid") from exception
    if captured.tzinfo is None or captured > started:
        raise EvidenceError(
            "environment capture is not ordered before source execution"
        )
    for field in (
        "os",
        "os_release",
        "architecture",
        "python_version",
        "python_implementation",
        "python_executable",
        "git_version",
        "timezone",
    ):
        if not isinstance(environment.get(field), str) or not environment[field]:
            raise EvidenceError(f"environment {field} is missing")
    attempt = manifest.get("attempt_ledger")
    if not isinstance(attempt, dict) or set(attempt) != {
        "path",
        "sha256",
        "candidate_sha",
        "attempt_number",
        "run_dir",
    }:
        raise EvidenceError("manifest attempt-ledger binding is invalid")
    expected_marker = _attempt_ledger_directory() / f"{candidate}.json"
    if (
        attempt.get("path") != str(expected_marker)
        or attempt.get("candidate_sha") != candidate
        or attempt.get("attempt_number") != 1
        or attempt.get("run_dir") != str(run_root)
    ):
        raise EvidenceError("manifest attempt-ledger identity drifted")
    marker_payload = _read_authenticated_bytes(
        expected_marker, "Phase 8 candidate attempt marker"
    )
    if hashlib.sha256(marker_payload).hexdigest() != attempt.get("sha256"):
        raise EvidenceError("Phase 8 candidate attempt marker SHA-256 drifted")
    try:
        marker = json.loads(marker_payload)
    except (json.JSONDecodeError, UnicodeDecodeError) as exception:
        raise EvidenceError(
            "Phase 8 candidate attempt marker is invalid JSON"
        ) from exception
    if not isinstance(marker, dict) or set(marker) != {
        "schema_version",
        "candidate_sha",
        "attempt_number",
        "run_dir",
        "environment_id",
        "claimed_at",
        "retry_allowed",
        "self_seal_trust",
    }:
        raise EvidenceError("Phase 8 candidate attempt marker schema drifted")
    if (
        marker.get("schema_version") != "phase8-entry-attempt-claim.v1"
        or marker.get("candidate_sha") != candidate
        or marker.get("attempt_number") != 1
        or marker.get("run_dir") != str(run_root)
        or marker.get("environment_id") != environment_id
        or marker.get("retry_allowed") is not False
        or marker.get("self_seal_trust")
        != "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION"
    ):
        raise EvidenceError("Phase 8 candidate attempt marker authority drifted")
    try:
        claimed = datetime.fromisoformat(str(marker.get("claimed_at", "")))
    except ValueError as exception:
        raise EvidenceError(
            "Phase 8 candidate attempt timestamp is invalid"
        ) from exception
    if claimed.tzinfo is None or claimed > captured:
        raise EvidenceError("Phase 8 candidate attempt timestamp ordering drifted")
    reports: dict[str, Any] = {}
    for prefix in ("stdout", "stderr", "raw_report", "normalized_report"):
        artifact = _bound_file(
            run_root,
            record.get(f"{prefix}_path"),
            record.get(f"{prefix}_sha256"),
            prefix,
        )
        if prefix in {"raw_report", "normalized_report"}:
            reports[prefix] = _parse_authenticated_junit(artifact, prefix)
    _assert_no_sensitive_output(
        run_root / "p/00-stdout.log",
        run_root / "p/01-stderr.log",
        run_root / "p/02-junit.xml",
        run_root / NORMALIZED_REPORT_NAME,
    )
    for name, report in reports.items():
        if (
            report["tests"] != record["tests"]
            or report["failures"] != record["failures"]
            or report["errors"] != record["errors"]
            or report["skipped"] != record["skipped"]
        ):
            raise EvidenceError(f"{name} JUnit totals drifted from the manifest")
    normalized = reports["normalized_report"]
    if (
        normalized["candidate_commit"] != candidate
        or normalized["command_id"] != SOURCE_ID
    ):
        raise EvidenceError("normalized JUnit candidate/source binding drifted")
    _assert_existing_run_root(run_root)
    return manifest


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plan or execute the exact-SHA Phase 8 P8.0 Batch 0 source gate."
    )
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--environment-id")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _build_parser().parse_args(argv)
    try:
        if not arguments.execute:
            if arguments.run_dir is not None or arguments.environment_id is not None:
                raise EvidenceError("--run-dir and --environment-id require --execute")
            print(
                json.dumps(
                    entry_plan(arguments.candidate_sha), indent=2, sort_keys=True
                )
            )
            return 0
        if arguments.run_dir is None or arguments.environment_id is None:
            raise EvidenceError("--execute requires --run-dir and --environment-id")
        manifest = execute_checkpoint(
            candidate_sha=arguments.candidate_sha,
            run_root=arguments.run_dir,
            environment_id=arguments.environment_id,
        )
        print(
            json.dumps(
                {
                    "candidate_sha": manifest["candidate_sha"],
                    "status": manifest["status"],
                    "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
                    "contract_gate": "P8.0_NOT_RUN",
                    "implementation_authorized": False,
                    "MIG-006": "PENDING_PROMOTION",
                    "MIG-007": "PENDING_PROMOTION",
                    "MIG-008": "PENDING_PROMOTION",
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    except EvidenceError as exception:
        print(f"Phase 8 entry runner rejected execution: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
