from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence

import yaml

try:
    from .evidence_schema import (
        ENGINEERING_AUTHORITY_CEILING,
        ENGINEERING_LOCAL,
        FIXED_ENGINEERING_COMMANDS,
        FIXED_ENGINEERING_COMMAND_ORDER,
        PHASE8_CONFIGURATION_MANIFEST_PATHS,
        PHASE8_DEPLOYMENT_MANIFEST_PATHS,
        PRODUCTION_CAPABILITY_KEYS,
        SCHEMA_VERSION,
        EvidenceValidationError,
        canonical_sha256,
        parse_json_bytes,
        seal_evidence,
        validate_evidence,
    )
except ImportError:  # Permit direct execution without weakening the import contract.
    _repository_root = Path(__file__).resolve().parents[3]
    if str(_repository_root) not in sys.path:
        sys.path.insert(0, str(_repository_root))
    from scripts.phase8.candidate.evidence_schema import (
        ENGINEERING_AUTHORITY_CEILING,
        ENGINEERING_LOCAL,
        FIXED_ENGINEERING_COMMANDS,
        FIXED_ENGINEERING_COMMAND_ORDER,
        PHASE8_CONFIGURATION_MANIFEST_PATHS,
        PHASE8_DEPLOYMENT_MANIFEST_PATHS,
        PRODUCTION_CAPABILITY_KEYS,
        SCHEMA_VERSION,
        EvidenceValidationError,
        canonical_sha256,
        parse_json_bytes,
        seal_evidence,
        validate_evidence,
    )


ROOT = Path(__file__).resolve().parents[3]
ACCEPTED_A8 = "3c60bf5cc4e051a214e158cbf944fd6aba969f95"
CONTEXT_SCHEMA_VERSION = "phase8-engineering-candidate-context.v1"
PLAN_SCHEMA_VERSION = "phase8-engineering-candidate-plan.v1"
REPORT_SCHEMA_VERSION = "phase8-engineering-command-report.v1"
EVIDENCE_NAME = "phase8-checkpoint-evidence.json"
RUN_DIRECTORY_PREFIX = "phase8-candidate-"
COMMAND_TIMEOUT_SECONDS = 30 * 60
APPROVED_EVIDENCE_ROOT_NAME = ".codex-run/phase8-candidate"
REQUIRED_RUNNER_PATHS = (
    "contracts/agent-platform/phase8/checkpoint-evidence.schema.json",
    "scripts/phase8/candidate/__init__.py",
    "scripts/phase8/candidate/evidence_schema.py",
    "scripts/phase8/candidate/run_checkpoint.py",
)
V047_PATH = (
    "java-api-service/src/main/resources/db/migration/"
    "V047__remove_legacy_orchestration.sql"
)
REVIEW_LANES = ("authority", "data_migration", "security_privacy")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IMAGE_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{2,127}$")
LOCAL_ENVIRONMENT = re.compile(
    r"^(?:engineering-local|synthetic|disposable)[A-Za-z0-9._/-]{1,111}$"
)
FORBIDDEN_CONTEXT_KEY_PARTS = (
    "authorization",
    "case_id",
    "chain_of_thought",
    "cookie",
    "credential",
    "email",
    "hidden_reasoning",
    "party_id",
    "password",
    "phone",
    "prompt",
    "scratchpad",
    "secret",
)
SENSITIVE_OUTPUT_PATTERNS = (
    re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----", re.IGNORECASE),
    re.compile(rb"(?:authorization|cookie|password|secret|token)\s*[:=]", re.IGNORECASE),
    re.compile(rb"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE),
    re.compile(rb"(?:chain[-_ ]of[-_ ]thought|hidden[-_ ]reasoning|scratchpad)", re.IGNORECASE),
)
IMAGE_REFERENCE = re.compile(
    r"^(?P<name>[a-z0-9][a-z0-9._:/-]{2,254})@(?P<digest>sha256:[0-9a-f]{64})$"
)
WINDOWS_DEVICE_COMPONENT = re.compile(
    r"^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$", re.IGNORECASE
)
REPORT_KEYS = {
    "argv",
    "attempt_id",
    "candidate_sha",
    "candidate_tree_sha",
    "command_id",
    "configuration_sha256",
    "context_id",
    "cwd",
    "deployment_manifest_sha256",
    "exit_code",
    "finished_at",
    "output_retained",
    "schema_version",
    "sensitive_output_rejected",
    "shell",
    "started_at",
    "status",
    "stderr_bytes",
    "stderr_sha256",
    "stdout_bytes",
    "stdout_sha256",
}


class CandidateCheckpointError(EvidenceValidationError):
    """Raised when local candidate execution cannot be authenticated safely."""


@dataclass(frozen=True)
class AuthenticatedFile:
    path: Path
    identity: tuple[int, int, int, int, int]
    payload: bytes
    sha256: str


@dataclass(frozen=True)
class ProcessResult:
    returncode: int
    stdout: bytes
    stderr: bytes
    timed_out: bool = False


@dataclass(frozen=True)
class FixtureExecution:
    receipt_id: str
    results: tuple[ProcessResult, ...]


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _assert_sha1(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA1.fullmatch(value):
        raise CandidateCheckpointError(
            f"{context} must be an exact lowercase 40-character Git SHA"
        )
    return value


def _assert_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise CandidateCheckpointError(f"{context} must be a lowercase SHA-256 digest")
    return value


def _assert_token(value: Any, context: str) -> str:
    if not isinstance(value, str) or not TOKEN.fullmatch(value):
        raise CandidateCheckpointError(f"{context} is not a strict opaque token")
    return value


def _relative_git_path(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 512:
        raise CandidateCheckpointError(f"{context} is not a bounded relative path")
    if "\\" in value or ":" in value or "\x00" in value or any(ord(ch) < 32 for ch in value):
        raise CandidateCheckpointError(f"{context} contains an alias, ADS, or control byte")
    pure = PurePosixPath(value)
    if pure.is_absolute() or any(part in {"", ".", ".."} for part in pure.parts):
        raise CandidateCheckpointError(f"{context} escapes the repository")
    if pure.as_posix() != value:
        raise CandidateCheckpointError(f"{context} is not canonical")
    return value


def _minimal_host_environment() -> dict[str, str]:
    environment: dict[str, str] = {}
    for key in ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATH", "PATHEXT", "TEMP", "TMP"):
        value = os.environ.get(key)
        if value:
            environment[key] = value
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "NUL" if os.name == "nt" else "/dev/null",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "LANG": "C",
            "LC_ALL": "C",
        }
    )
    return environment


def _command_environment() -> dict[str, str]:
    environment = {
        key: value
        for key, value in _minimal_host_environment().items()
        if not key.startswith("GIT_")
    }
    environment.update(
        {
            "CI": "1",
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONHASHSEED": "0",
            "PYTHONNOUSERSITE": "1",
            "PYTHONPATH": "",
            "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
        }
    )
    return environment


def _git_process(arguments: Sequence[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        env=_minimal_host_environment(),
        shell=False,
        check=False,
        capture_output=True,
    )


def _git_bytes(*arguments: str) -> bytes:
    completed = _git_process(arguments)
    if completed.returncode:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise CandidateCheckpointError(
            f"fixed Git operation {' '.join(arguments)} failed: {message}"
        )
    return completed.stdout


def _git_text(*arguments: str) -> str:
    try:
        return _git_bytes(*arguments).decode("utf-8", errors="strict").strip()
    except UnicodeDecodeError as exception:
        raise CandidateCheckpointError("Git metadata was not strict UTF-8") from exception


def _is_link_or_reparse(path: Path) -> bool:
    metadata = os.lstat(path)
    return stat.S_ISLNK(metadata.st_mode) or bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def _assert_local_absolute_path(path: Path, context: str) -> None:
    raw = str(path)
    normalized = raw.replace("/", "\\")
    if (
        normalized.startswith("\\\\")
        or normalized.startswith("\\\\?\\")
        or normalized.startswith("\\\\.\\")
        or "GLOBALROOT" in normalized.upper()
    ):
        raise CandidateCheckpointError(
            f"{context} rejects UNC, device, extended, and non-local paths"
        )
    if not path.is_absolute():
        raise CandidateCheckpointError(f"{context} path must be explicit and absolute")
    components = path.parts[1:] if path.anchor else path.parts
    if any(WINDOWS_DEVICE_COMPONENT.fullmatch(part) for part in components):
        raise CandidateCheckpointError(f"{context} contains a Windows device component")
    if os.name == "nt":
        drive = path.drive
        if not re.fullmatch(r"[A-Za-z]:", drive):
            raise CandidateCheckpointError(f"{context} is not on a local drive")
        drive_type = ctypes.windll.kernel32.GetDriveTypeW(f"{drive}\\")
        if drive_type != 3:  # DRIVE_FIXED
            raise CandidateCheckpointError(f"{context} must be on a fixed local volume")


def _assert_no_ads(path: Path, context: str) -> None:
    parts = path.parts[1:] if path.anchor else path.parts
    if any(":" in part for part in parts):
        raise CandidateCheckpointError(f"{context} contains an NTFS alternate data stream")


def _assert_ancestry_has_no_alias(path: Path, context: str) -> None:
    current = path.absolute()
    while True:
        if not current.exists() or _is_link_or_reparse(current):
            raise CandidateCheckpointError(f"{context} ancestry is missing or aliased")
        if current.parent == current:
            return
        current = current.parent


def _file_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        getattr(metadata, "st_file_attributes", 0),
    )


def _read_authenticated_file(path: Path, context: str) -> AuthenticatedFile:
    _assert_local_absolute_path(path, context)
    _assert_no_ads(path, context)
    _assert_ancestry_has_no_alias(path.parent, context)
    if not path.exists() or _is_link_or_reparse(path):
        raise CandidateCheckpointError(f"{context} must be a regular non-link file")
    before = os.lstat(path)
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
        raise CandidateCheckpointError(f"{context} must be a single-link regular file")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise CandidateCheckpointError(f"cannot securely open {context}") from exception
    try:
        opened_before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened_before.st_mode)
            or opened_before.st_nlink != 1
            or _file_identity(opened_before) != _file_identity(before)
        ):
            raise CandidateCheckpointError(f"{context} identity changed before read")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        opened_after = os.fstat(descriptor)
        if _file_identity(opened_after) != _file_identity(opened_before):
            raise CandidateCheckpointError(f"{context} changed during read")
    finally:
        os.close(descriptor)
    after = os.lstat(path)
    if _file_identity(after) != _file_identity(before) or _is_link_or_reparse(path):
        raise CandidateCheckpointError(f"{context} path identity changed after read")
    payload = b"".join(chunks)
    return AuthenticatedFile(path, _file_identity(after), payload, _sha256(payload))


def _assert_snapshot_unchanged(snapshot: AuthenticatedFile, context: str) -> None:
    current = _read_authenticated_file(snapshot.path, context)
    if current.identity != snapshot.identity or current.sha256 != snapshot.sha256:
        raise CandidateCheckpointError(f"{context} changed after authentication")


def assert_no_git_object_rewrite_state() -> None:
    forbidden_environment = (
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_OBJECT_DIRECTORY",
        "GIT_REPLACE_REF_BASE",
    )
    present = [key for key in forbidden_environment if os.environ.get(key)]
    if present:
        raise CandidateCheckpointError(
            "Git object rewrite/alternate environment is forbidden: " + ", ".join(present)
        )
    replacements = _git_text(
        "for-each-ref", "--format=%(refname)", "refs/replace"
    ).splitlines()
    if replacements:
        raise CandidateCheckpointError("Git replace refs are forbidden")
    git_dirs = {
        Path(_git_text("rev-parse", "--path-format=absolute", "--git-dir")),
        Path(_git_text("rev-parse", "--path-format=absolute", "--git-common-dir")),
    }
    for git_dir in git_dirs:
        for relative in (Path("info/grafts"), Path("objects/info/alternates")):
            candidate = git_dir / relative
            if candidate.exists() or candidate.is_symlink():
                raise CandidateCheckpointError(
                    f"Git graft/alternate state is forbidden: {candidate}"
                )


def _git_protected_roots() -> tuple[Path, ...]:
    roots: set[Path] = {
        Path(
            _git_text("rev-parse", "--path-format=absolute", "--git-common-dir")
        ).resolve(strict=True),
        Path(_git_text("rev-parse", "--show-toplevel")).resolve(strict=True),
    }
    output = _git_text("worktree", "list", "--porcelain")
    for line in output.splitlines():
        if line.startswith("worktree "):
            root = Path(line.removeprefix("worktree "))
            _assert_local_absolute_path(root, "Git worktree root")
            roots.add(root.resolve(strict=True))
    return tuple(sorted(roots, key=lambda item: str(item).casefold()))


def _assert_external_to_git(path: Path, context: str) -> None:
    resolved = path.resolve(strict=True)
    for protected in _git_protected_roots():
        if _path_is_within(resolved, protected):
            raise CandidateCheckpointError(
                f"{context} must be external to every Git directory and worktree"
            )


def _approved_evidence_root() -> Path:
    common_git = Path(
        _git_text("rev-parse", "--path-format=absolute", "--git-common-dir")
    ).resolve(strict=True)
    repository_root = common_git.parent
    approved = repository_root.parent.joinpath(*APPROVED_EVIDENCE_ROOT_NAME.split("/"))
    _assert_local_absolute_path(approved, "approved evidence root")
    _assert_no_ads(approved, "approved evidence root")
    ancestor = approved.parent
    while not ancestor.exists():
        ancestor = ancestor.parent
    _assert_ancestry_has_no_alias(ancestor, "approved evidence root")
    approved.parent.mkdir(exist_ok=True)
    if not approved.exists():
        approved.mkdir(exist_ok=False)
    if not approved.is_dir() or _is_link_or_reparse(approved):
        raise CandidateCheckpointError("approved evidence root is not a regular directory")
    resolved = approved.resolve(strict=True)
    _assert_ancestry_has_no_alias(resolved, "approved evidence root")
    _assert_external_to_git(resolved, "approved evidence root")
    return resolved


def _assert_candidate_object(candidate_sha: str) -> str:
    candidate = _assert_sha1(candidate_sha, "candidate commit")
    assert_no_git_object_rewrite_state()
    if _git_text("cat-file", "-t", candidate) != "commit":
        raise CandidateCheckpointError("candidate SHA is not a commit object")
    if _git_text("rev-parse", f"{candidate}^{{commit}}").lower() != candidate:
        raise CandidateCheckpointError("candidate commit did not resolve to itself")
    ancestry = _git_process(["merge-base", "--is-ancestor", ACCEPTED_A8, candidate])
    if ancestry.returncode != 0:
        raise CandidateCheckpointError("candidate is not descended from accepted exact A8'")
    return candidate


def _candidate_tree(candidate: str) -> tuple[str, str]:
    parents = _git_text("rev-list", "--parents", "-n", "1", candidate).split()
    if len(parents) != 2 or parents[0] != candidate:
        raise CandidateCheckpointError("candidate must have exactly one immutable parent")
    tree = _assert_sha1(
        _git_text("rev-parse", f"{candidate}^{{tree}}").lower(), "candidate tree"
    )
    return tree, _assert_sha1(parents[1], "candidate parent")


def _tree_inventory(candidate: str) -> dict[str, dict[str, str]]:
    raw = _git_bytes("ls-tree", "-r", "-z", candidate)
    inventory: dict[str, dict[str, str]] = {}
    casefolded: dict[str, str] = {}
    for record in raw.split(b"\0"):
        if not record:
            continue
        try:
            metadata, encoded_path = record.split(b"\t", 1)
            mode, object_type, object_sha = metadata.decode("ascii").split(" ")
            path = encoded_path.decode("utf-8", errors="strict")
        except (UnicodeDecodeError, ValueError) as exception:
            raise CandidateCheckpointError("candidate tree contains malformed metadata") from exception
        path = _relative_git_path(path, "candidate tree path")
        folded = path.casefold()
        if path in inventory or (folded in casefolded and casefolded[folded] != path):
            raise CandidateCheckpointError(f"candidate tree path collision: {path}")
        if mode not in {"100644", "100755"} or object_type != "blob":
            raise CandidateCheckpointError(
                f"candidate tree rejects symlink, submodule, or non-blob path: {path}"
            )
        inventory[path] = {
            "git_blob_sha": _assert_sha1(object_sha, f"Git blob for {path}"),
            "mode": mode,
        }
        casefolded[folded] = path
    if V047_PATH in inventory:
        raise CandidateCheckpointError("V047 is forbidden in a Phase 8 engineering candidate")
    return inventory


def _changed_paths(candidate: str) -> dict[str, str]:
    raw = _git_bytes(
        "diff-tree",
        "--no-commit-id",
        "--name-status",
        "-r",
        "-z",
        "--no-renames",
        ACCEPTED_A8,
        candidate,
    )
    fields = [field for field in raw.split(b"\0") if field]
    if len(fields) % 2:
        raise CandidateCheckpointError("candidate changed-path stream is malformed")
    changed: dict[str, str] = {}
    for index in range(0, len(fields), 2):
        try:
            status = fields[index].decode("ascii", errors="strict")
            path = fields[index + 1].decode("utf-8", errors="strict")
        except UnicodeDecodeError as exception:
            raise CandidateCheckpointError("candidate diff is not strict UTF-8") from exception
        path = _relative_git_path(path, "candidate changed path")
        if status not in {"A", "M"}:
            raise CandidateCheckpointError(
                f"candidate contains destructive, renamed, or type-changed path: {status} {path}"
            )
        if path in changed:
            raise CandidateCheckpointError(f"candidate changed path is duplicated: {path}")
        changed[path] = "ADDED" if status == "A" else "MODIFIED"
    return changed


def _command_selector_paths() -> tuple[str, ...]:
    paths: list[str] = []
    for contract in FIXED_ENGINEERING_COMMANDS.values():
        for argument in contract["argv"]:
            if isinstance(argument, str) and argument.startswith("tests/"):
                paths.append(_relative_git_path(argument, "fixed command selector"))
    return tuple(dict.fromkeys(paths))


def _capture_candidate(candidate_sha: str, required_paths: Sequence[str]) -> dict[str, Any]:
    candidate = _assert_candidate_object(candidate_sha)
    tree_sha, parent_sha = _candidate_tree(candidate)
    inventory = _tree_inventory(candidate)
    changed = _changed_paths(candidate)
    requested = set(changed)
    requested.update(_relative_git_path(path, "required candidate path") for path in required_paths)
    missing = sorted(requested - set(inventory))
    if missing:
        raise CandidateCheckpointError(f"candidate path blob is missing: {missing}")
    blobs: list[dict[str, str]] = []
    for path in sorted(requested):
        metadata = inventory[path]
        payload = _git_bytes("cat-file", "blob", metadata["git_blob_sha"])
        blobs.append(
            {
                "git_blob_sha": metadata["git_blob_sha"],
                "mode": metadata["mode"],
                "path": path,
                "sha256": _sha256(payload),
                "status": changed.get(path, "BOUND_UNCHANGED"),
            }
        )
    return {
        "accepted_entry_sha": ACCEPTED_A8,
        "commit_sha": candidate,
        "parent_sha": parent_sha,
        "path_blobs": blobs,
        "path_blobs_sha256": canonical_sha256(blobs),
        "tree_sha": tree_sha,
    }


def _blob_by_path(candidate: Mapping[str, Any], path: str) -> dict[str, str]:
    matches = [item for item in candidate["path_blobs"] if item["path"] == path]
    if len(matches) != 1:
        raise CandidateCheckpointError(f"candidate blob binding is ambiguous: {path}")
    item = matches[0]
    return {
        "git_blob_sha": item["git_blob_sha"],
        "path": item["path"],
        "sha256": item["sha256"],
    }


def _assert_no_sensitive_context(value: Any, location: str = "context") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            normalized = str(key).casefold().replace("-", "_")
            if any(part in normalized for part in FORBIDDEN_CONTEXT_KEY_PARTS):
                raise CandidateCheckpointError(
                    f"{location} contains a forbidden secret, PII, or reasoning field: {key}"
                )
            _assert_no_sensitive_context(item, f"{location}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _assert_no_sensitive_context(item, f"{location}[{index}]")
    elif isinstance(value, str):
        encoded = value.encode("utf-8", errors="strict")
        if any(pattern.search(encoded) for pattern in SENSITIVE_OUTPUT_PATTERNS):
            raise CandidateCheckpointError(
                f"{location} contains secret, PII, or hidden-reasoning material"
            )


def _exact_keys(value: Any, expected: set[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise CandidateCheckpointError(f"{context} fields drifted")
    return value


def _validate_context(value: Mapping[str, Any], snapshot_sha256: str) -> dict[str, Any]:
    context = _exact_keys(
        dict(value),
        {
            "attempt_lineage",
            "context_id",
            "environment_identity",
            "evidence_kind",
            "review",
            "schema_version",
        },
        "engineering context",
    )
    _assert_no_sensitive_context(context)
    if context["schema_version"] != CONTEXT_SCHEMA_VERSION:
        raise CandidateCheckpointError("engineering context schema version drifted")
    if context["evidence_kind"] != ENGINEERING_LOCAL:
        raise CandidateCheckpointError("local runner accepts ENGINEERING_LOCAL context only")
    context_id = _assert_token(context["context_id"], "context_id")
    environment = context["environment_identity"]
    if not isinstance(environment, str) or not LOCAL_ENVIRONMENT.fullmatch(environment):
        raise CandidateCheckpointError(
            "environment identity must be explicitly engineering-local, synthetic, or disposable"
        )

    lineage = _exact_keys(
        context["attempt_lineage"],
        {"attempt_id", "attempt_number", "checkpoint_id", "previous_attempt_id"},
        "attempt lineage",
    )
    attempt_id = _assert_token(lineage["attempt_id"], "attempt_id")
    checkpoint_id = _assert_token(lineage["checkpoint_id"], "checkpoint_id")
    number = lineage["attempt_number"]
    previous = lineage["previous_attempt_id"]
    if not isinstance(number, int) or isinstance(number, bool) or number < 1:
        raise CandidateCheckpointError("attempt_number must be a positive integer")
    if previous is not None:
        previous = _assert_token(previous, "previous_attempt_id")
    if (number == 1) != (previous is None):
        raise CandidateCheckpointError("attempt lineage predecessor is inconsistent")
    if previous is not None and previous.casefold() == attempt_id.casefold():
        raise CandidateCheckpointError("attempt lineage is self-referential")

    review = _exact_keys(
        context["review"], {"producer_identity", "reviewers", "self_approved"}, "review"
    )
    if review["self_approved"] is not False:
        raise CandidateCheckpointError("self-signoff is forbidden")
    producer = _assert_token(review["producer_identity"], "producer identity")
    reviewers = review["reviewers"]
    if not isinstance(reviewers, list) or len(reviewers) != 3:
        raise CandidateCheckpointError("exactly three independent P0 reviewers are required")
    normalized_reviewers: list[dict[str, str]] = []
    reviewer_ids: set[str] = set()
    lanes: set[str] = set()
    for item in reviewers:
        reviewer = _exact_keys(item, {"identity", "lane"}, "reviewer")
        identity = _assert_token(reviewer["identity"], "reviewer identity")
        lane = reviewer["lane"]
        if lane not in REVIEW_LANES:
            raise CandidateCheckpointError("reviewer lane is not a fixed P0 lane")
        if identity.casefold() == producer.casefold():
            raise CandidateCheckpointError("evidence producer cannot self-review")
        if identity.casefold() in reviewer_ids or lane in lanes:
            raise CandidateCheckpointError("review identities and lanes must be unique")
        reviewer_ids.add(identity.casefold())
        lanes.add(lane)
        normalized_reviewers.append({"identity": identity, "lane": lane})
    if lanes != set(REVIEW_LANES):
        raise CandidateCheckpointError("all fixed P0 review lanes are required")
    normalized_reviewers.sort(key=lambda item: REVIEW_LANES.index(item["lane"]))

    return {
        "attempt_lineage": {
            "attempt_id": attempt_id,
            "attempt_number": number,
            "checkpoint_id": checkpoint_id,
            "previous_attempt_id": previous,
        },
        "context_id": context_id,
        "context_sha256": _assert_sha256(snapshot_sha256, "context file digest"),
        "environment_identity": environment,
        "review": {
            "producer_identity": producer,
            "reviewers": normalized_reviewers,
            "self_approved": False,
        },
    }


def load_context(path: Path) -> tuple[dict[str, Any], AuthenticatedFile]:
    snapshot = _read_authenticated_file(path, "engineering context")
    document = parse_json_bytes(snapshot.payload, context="engineering context")
    return _validate_context(document, snapshot.sha256), snapshot


def _path_is_within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _prepare_run_directory(run_dir: Path, attempt_id: str) -> Path:
    _assert_local_absolute_path(run_dir, "run directory")
    _assert_no_ads(run_dir, "run directory")
    expected_name = f"{RUN_DIRECTORY_PREFIX}{attempt_id}"
    if run_dir.name != expected_name:
        raise CandidateCheckpointError(
            f"run directory name must bind the attempt exactly as {expected_name}"
        )
    if run_dir.exists() or run_dir.is_symlink():
        raise CandidateCheckpointError("run directory must be fresh and cannot be resumed")
    approved = _approved_evidence_root()
    parent = run_dir.parent.resolve(strict=True)
    if parent != approved:
        raise CandidateCheckpointError(
            "run directory must be a direct child of the approved local evidence root"
        )
    _assert_ancestry_has_no_alias(parent, "run directory")
    candidate = parent / run_dir.name
    if candidate.resolve(strict=False) != candidate:
        raise CandidateCheckpointError("run directory contains an alias or path escape")
    for protected in _git_protected_roots():
        if _path_is_within(candidate, protected):
            raise CandidateCheckpointError(
                "run directory must be outside every Git directory and worktree"
            )
    candidate.mkdir(exist_ok=False)
    if not candidate.is_dir() or _is_link_or_reparse(candidate):
        raise CandidateCheckpointError("created run directory is not a regular directory")
    reports = candidate / "reports"
    reports.mkdir(exist_ok=False)
    if not reports.is_dir() or _is_link_or_reparse(reports):
        raise CandidateCheckpointError("created report directory is not regular")
    return candidate


def _assert_clean_detached_candidate(candidate: str) -> None:
    assert_no_git_object_rewrite_state()
    if _git_text("rev-parse", "HEAD").lower() != candidate:
        raise CandidateCheckpointError("worktree HEAD does not equal the exact candidate")
    if _git_process(["symbolic-ref", "-q", "HEAD"]).returncode == 0:
        raise CandidateCheckpointError("candidate execution requires detached HEAD")
    if _git_text("status", "--porcelain=v1", "--untracked-files=all"):
        raise CandidateCheckpointError("candidate execution requires a clean worktree")


def _write_exclusive_json(path: Path, document: Mapping[str, Any], context: str) -> bytes:
    if path.exists() or path.is_symlink():
        raise CandidateCheckpointError(f"{context} path already exists")
    payload = json.dumps(
        document,
        allow_nan=False,
        ensure_ascii=True,
        indent=2,
        sort_keys=True,
    ).encode("utf-8") + b"\n"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    descriptor = os.open(path, flags, 0o600)
    try:
        offset = 0
        while offset < len(payload):
            offset += os.write(descriptor, payload[offset:])
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    authenticated = _read_authenticated_file(path.resolve(strict=True), context)
    if authenticated.payload != payload:
        raise CandidateCheckpointError(f"{context} changed after exclusive write")
    return payload


def _fixture_receipt(fixture: FixtureExecution) -> dict[str, Any]:
    if type(fixture) is not FixtureExecution:
        raise CandidateCheckpointError("fixture execution must be immutable in-memory data")
    if not fixture.results or len(fixture.results) > len(FIXED_ENGINEERING_COMMAND_ORDER):
        raise CandidateCheckpointError("fixture result count is outside the fixed command order")
    if any(type(result) is not ProcessResult for result in fixture.results):
        raise CandidateCheckpointError("fixture contains a non-data execution result")
    return {
        "authority": "TEST_LIFECYCLE_ONLY_NO_CHECKPOINT_PASS",
        "backend_kind": "FIXTURE_ONLY",
        "fixture_only": True,
        "independently_verified": False,
        "network_denial_verified": False,
        "receipt_authenticated": False,
        "receipt_id": _assert_token(fixture.receipt_id, "fixture receipt_id"),
    }


def _write_exclusive_bytes(path: Path, payload: bytes, mode: str) -> None:
    if path.exists() or path.is_symlink():
        raise CandidateCheckpointError(f"materialized candidate path already exists: {path}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    descriptor = os.open(path, flags, 0o700 if mode == "100755" else 0o600)
    try:
        offset = 0
        while offset < len(payload):
            offset += os.write(descriptor, payload[offset:])
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _materialize_candidate_tree(candidate_sha: str, destination: Path) -> dict[str, dict[str, str]]:
    if destination.exists() or destination.is_symlink():
        raise CandidateCheckpointError("candidate materialization directory must be fresh")
    inventory = _tree_inventory(candidate_sha)
    destination.mkdir(exist_ok=False)
    for relative, metadata in inventory.items():
        path = destination.joinpath(*PurePosixPath(relative).parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        resolved_parent = path.parent.resolve(strict=True)
        if not _path_is_within(resolved_parent, destination.resolve(strict=True)):
            raise CandidateCheckpointError("candidate materialization path escaped")
        _assert_ancestry_has_no_alias(resolved_parent, "materialized candidate")
        payload = _git_bytes("cat-file", "blob", metadata["git_blob_sha"])
        _write_exclusive_bytes(path, payload, metadata["mode"])
        os.chmod(path, stat.S_IREAD | (stat.S_IEXEC if metadata["mode"] == "100755" else 0))
    _verify_materialized_tree(candidate_sha, destination, inventory)
    return inventory


def _verify_materialized_tree(
    candidate_sha: str,
    destination: Path,
    expected_inventory: Mapping[str, Mapping[str, str]],
) -> None:
    _assert_candidate_object(candidate_sha)
    if not destination.is_dir() or _is_link_or_reparse(destination):
        raise CandidateCheckpointError("materialized candidate root was substituted")
    entries = list(destination.rglob("*"))
    if any(_is_link_or_reparse(entry) for entry in entries):
        raise CandidateCheckpointError(
            "materialized candidate contains a symlink, junction, or reparse point"
        )
    files = {entry.relative_to(destination).as_posix(): entry for entry in entries if entry.is_file()}
    expected_directories = {
        parent.as_posix()
        for relative in expected_inventory
        for parent in PurePosixPath(relative).parents
        if parent != PurePosixPath(".")
    }
    actual_directories = {
        entry.relative_to(destination).as_posix() for entry in entries if entry.is_dir()
    }
    if set(files) != set(expected_inventory) or actual_directories != expected_directories:
        raise CandidateCheckpointError(
            "materialized candidate contains ignored, untracked, omitted, or extra content"
        )
    if len({path.casefold() for path in files}) != len(files):
        raise CandidateCheckpointError("materialized candidate contains a case collision")
    for relative, path in files.items():
        snapshot = _read_authenticated_file(
            path.resolve(strict=True), f"materialized candidate blob {relative}"
        )
        expected = expected_inventory[relative]
        git_payload = _git_bytes("cat-file", "blob", expected["git_blob_sha"])
        if snapshot.sha256 != _sha256(git_payload):
            raise CandidateCheckpointError(
                f"materialized candidate blob changed during execution: {relative}"
            )


def _remove_verified_materialization(destination: Path) -> None:
    if not destination.is_dir() or _is_link_or_reparse(destination):
        raise CandidateCheckpointError("refusing to remove substituted materialized tree")

    def _make_writable_and_retry(function: Any, path: str, _error: Any) -> None:
        os.chmod(path, stat.S_IWRITE | stat.S_IREAD | stat.S_IEXEC)
        function(path)

    shutil.rmtree(destination, onerror=_make_writable_and_retry)


def _fixture_result(fixture: FixtureExecution, order: int) -> ProcessResult:
    if order > len(fixture.results):
        raise CandidateCheckpointError("fixture omitted the next fixed command result")
    result = fixture.results[order - 1]
    if not isinstance(result.stdout, bytes) or not isinstance(result.stderr, bytes):
        raise CandidateCheckpointError("fixture output must be bounded bytes")
    return result


def _contains_sensitive_output(*payloads: bytes) -> bool:
    return any(pattern.search(payload) for payload in payloads for pattern in SENSITIVE_OUTPUT_PATTERNS)


def _command_report(
    *,
    command_id: str,
    contract: Mapping[str, Any],
    candidate: Mapping[str, Any],
    context: Mapping[str, Any],
    release_context: Mapping[str, Any],
    result: ProcessResult,
    started_at: str,
    finished_at: str,
) -> dict[str, Any]:
    sensitive = _contains_sensitive_output(result.stdout, result.stderr)
    stdout = b"[REJECTED SENSITIVE OUTPUT]\n" if sensitive else result.stdout
    stderr = b"[REJECTED SENSITIVE OUTPUT]\n" if sensitive else result.stderr
    passed = result.returncode == 0 and not sensitive and not result.timed_out
    return {
        "argv": list(contract["argv"]),
        "attempt_id": context["attempt_lineage"]["attempt_id"],
        "candidate_sha": candidate["commit_sha"],
        "candidate_tree_sha": candidate["tree_sha"],
        "command_id": command_id,
        "configuration_sha256": release_context["configuration"]["sha256"],
        "context_id": context["context_id"],
        "cwd": contract["cwd"],
        "deployment_manifest_sha256": release_context["deployment_manifest"]["sha256"],
        "exit_code": result.returncode if not sensitive else 125,
        "finished_at": finished_at,
        "output_retained": False,
        "schema_version": REPORT_SCHEMA_VERSION,
        "sensitive_output_rejected": sensitive,
        "shell": False,
        "started_at": started_at,
        "status": "PASSED" if passed else "FAILED",
        "stderr_bytes": len(stderr),
        "stderr_sha256": _sha256(stderr),
        "stdout_bytes": len(stdout),
        "stdout_sha256": _sha256(stdout),
    }


def _assert_report_document(
    report: Mapping[str, Any], command: Mapping[str, Any]
) -> None:
    if set(report) != REPORT_KEYS or report.get("schema_version") != REPORT_SCHEMA_VERSION:
        raise CandidateCheckpointError("command report shape drifted")
    for key in (
        "argv",
        "attempt_id",
        "candidate_sha",
        "candidate_tree_sha",
        "configuration_sha256",
        "context_id",
        "cwd",
        "deployment_manifest_sha256",
        "exit_code",
        "finished_at",
        "shell",
        "started_at",
        "status",
    ):
        if report.get(key) != command.get(key):
            raise CandidateCheckpointError(
                f"command report binding drifted for {command.get('id')}"
            )
    if report.get("command_id") != command.get("id") or report.get("output_retained") is not False:
        raise CandidateCheckpointError("command report identity or output policy drifted")


def _blob_payload(candidate: Mapping[str, Any], path: str) -> bytes:
    binding = _blob_by_path(candidate, path)
    payload = _git_bytes("cat-file", "blob", binding["git_blob_sha"])
    if _sha256(payload) != binding["sha256"]:
        raise CandidateCheckpointError(f"candidate Git blob changed while reading: {path}")
    return payload


class _NoDuplicateSafeLoader(yaml.SafeLoader):
    pass


def _construct_unique_mapping(
    loader: _NoDuplicateSafeLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise CandidateCheckpointError(f"duplicate manifest property rejected: {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_NoDuplicateSafeLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping
)


def _manifest_documents(path: str, payload: bytes) -> list[Any]:
    try:
        text = payload.decode("utf-8", errors="strict")
        if path.endswith(".json"):
            return [parse_json_bytes(payload, context=f"candidate manifest {path}")]
        return list(yaml.load_all(text, Loader=_NoDuplicateSafeLoader))
    except (UnicodeDecodeError, yaml.YAMLError) as exception:
        raise CandidateCheckpointError(f"candidate manifest is invalid: {path}") from exception


def _collect_image_values(value: Any, destination: list[str]) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "image":
                if not isinstance(item, str):
                    raise CandidateCheckpointError("manifest image field must be a string")
                destination.append(item)
            _collect_image_values(item, destination)
    elif isinstance(value, list):
        for item in value:
            _collect_image_values(item, destination)


def _derive_images(candidate: Mapping[str, Any], paths: Sequence[str]) -> list[dict[str, str]]:
    image_values: list[str] = []
    for path in paths:
        for document in _manifest_documents(path, _blob_payload(candidate, path)):
            _collect_image_values(document, image_values)
    if not image_values:
        raise CandidateCheckpointError("exact Phase 8 manifests contain no image digests")
    images: dict[str, str] = {}
    for value in image_values:
        match = IMAGE_REFERENCE.fullmatch(value)
        if match is None:
            raise CandidateCheckpointError(
                f"manifest image is not pinned by exact SHA-256 digest: {value}"
            )
        name = match.group("name")
        digest = match.group("digest")
        existing = images.get(name.casefold())
        if existing is not None and existing != digest:
            raise CandidateCheckpointError(
                f"manifest image name has mixed digests: {name}"
            )
        images[name.casefold()] = digest
    return [
        {"digest": images[key], "name": key}
        for key in sorted(images)
    ]


def _blob_bundle(
    candidate: Mapping[str, Any], exact_paths: Sequence[str]
) -> dict[str, Any]:
    blobs = [_blob_by_path(candidate, path) for path in exact_paths]
    if [item["path"] for item in blobs] != list(exact_paths):
        raise CandidateCheckpointError("Phase 8 manifest allowlist order drifted")
    return {"blobs": blobs, "sha256": canonical_sha256(blobs)}


def _release_context(
    context: Mapping[str, Any], candidate: Mapping[str, Any]
) -> dict[str, Any]:
    configuration = _blob_bundle(candidate, PHASE8_CONFIGURATION_MANIFEST_PATHS)
    deployment = _blob_bundle(candidate, PHASE8_DEPLOYMENT_MANIFEST_PATHS)
    all_paths = (
        *PHASE8_CONFIGURATION_MANIFEST_PATHS,
        *PHASE8_DEPLOYMENT_MANIFEST_PATHS,
    )
    return {
        "configuration": configuration,
        "context_id": context["context_id"],
        "context_sha256": context["context_sha256"],
        "deployment_manifest": deployment,
        "environment_identity": context["environment_identity"],
        "images": _derive_images(candidate, all_paths),
    }


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _capture_candidate(candidate_commit, ())
    return {
        "schema_version": PLAN_SCHEMA_VERSION,
        "phase": 8,
        "mode": "PLAN_ONLY",
        "execution_available": False,
        "execution_blocker": "SANDBOX_UNAVAILABLE",
        "candidate": candidate,
        "command_order": list(FIXED_ENGINEERING_COMMAND_ORDER),
        "commands": [
            {
                "argv": list(FIXED_ENGINEERING_COMMANDS[command_id]["argv"]),
                "cwd": FIXED_ENGINEERING_COMMANDS[command_id]["cwd"],
                "id": command_id,
                "shell": False,
            }
            for command_id in FIXED_ENGINEERING_COMMAND_ORDER
        ],
        "execution_requires": [
            "--candidate-commit=<full-sha>",
            "--run-dir=<absolute-fresh-attempt-bound-path>",
            "--context-file=<absolute-authenticated-json>",
            "FIXED_AUTHENTICATED_SANDBOX_BACKEND_NOT_CONFIGURED",
        ],
        "authority_ceiling": ENGINEERING_AUTHORITY_CEILING,
        "engineering_checkpoint": "NOT_RUN",
        "production_checkpoint": "PENDING_EXTERNAL",
        "promotion_gate": "PENDING",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "production_capabilities": {
            key: False for key in PRODUCTION_CAPABILITY_KEYS
        },
    }


def _expected_candidate_paths() -> tuple[str, ...]:
    return tuple(
        dict.fromkeys(
            (
                *REQUIRED_RUNNER_PATHS,
                *_command_selector_paths(),
                *PHASE8_CONFIGURATION_MANIFEST_PATHS,
                *PHASE8_DEPLOYMENT_MANIFEST_PATHS,
            )
        )
    )


def process_fixture_lifecycle(
    *,
    candidate_commit: str,
    run_dir: Path,
    context_file: Path,
    fixture: FixtureExecution,
) -> dict[str, Any]:
    candidate_sha = _assert_candidate_object(candidate_commit)
    context, context_snapshot = load_context(context_file)
    _assert_external_to_git(context_snapshot.path, "execution context")
    sandbox_receipt = _fixture_receipt(fixture)
    _assert_clean_detached_candidate(candidate_sha)
    candidate = _capture_candidate(candidate_sha, _expected_candidate_paths())
    release_context = _release_context(context, candidate)
    run_root = _prepare_run_directory(
        run_dir, context["attempt_lineage"]["attempt_id"]
    )
    materialized_root = run_root / "candidate-tree"
    materialized_inventory = _materialize_candidate_tree(
        candidate_sha, materialized_root
    )
    command_records: list[dict[str, Any]] = []
    report_bindings: list[dict[str, Any]] = []
    for order, command_id in enumerate(FIXED_ENGINEERING_COMMAND_ORDER, start=1):
        _assert_clean_detached_candidate(candidate_sha)
        _assert_snapshot_unchanged(context_snapshot, "engineering context")
        contract = FIXED_ENGINEERING_COMMANDS[command_id]
        started_at = _utc_now()
        # Fixture results are frozen data. No callback, process, socket, or plugin is invoked.
        result = _fixture_result(fixture, order)
        finished_at = _utc_now()
        _verify_materialized_tree(candidate_sha, materialized_root, materialized_inventory)
        _assert_clean_detached_candidate(candidate_sha)
        _assert_snapshot_unchanged(context_snapshot, "engineering context")
        report = _command_report(
            command_id=command_id,
            contract=contract,
            candidate=candidate,
            context=context,
            release_context=release_context,
            result=result,
            started_at=started_at,
            finished_at=finished_at,
        )
        relative_report = f"reports/{order:03d}-{command_id}.json"
        report_path = run_root / PurePosixPath(relative_report)
        report_payload = _write_exclusive_json(
            report_path, report, f"report for {command_id}"
        )
        report_sha256 = _sha256(report_payload)
        command_record = {
            "argv": list(contract["argv"]),
            "attempt_id": context["attempt_lineage"]["attempt_id"],
            "candidate_sha": candidate["commit_sha"],
            "candidate_tree_sha": candidate["tree_sha"],
            "configuration_sha256": release_context["configuration"]["sha256"],
            "context_id": context["context_id"],
            "cwd": contract["cwd"],
            "deployment_manifest_sha256": release_context["deployment_manifest"]["sha256"],
            "exit_code": report["exit_code"],
            "finished_at": finished_at,
            "id": command_id,
            "order": order,
            "report_path": relative_report,
            "report_sha256": report_sha256,
            "shell": False,
            "started_at": started_at,
            "status": report["status"],
        }
        command_records.append(command_record)
        report_bindings.append(
            {
                "attempt_id": context["attempt_lineage"]["attempt_id"],
                "bytes": len(report_payload),
                "candidate_sha": candidate["commit_sha"],
                "candidate_tree_sha": candidate["tree_sha"],
                "command_id": command_id,
                "configuration_sha256": release_context["configuration"]["sha256"],
                "context_id": context["context_id"],
                "deployment_manifest_sha256": release_context["deployment_manifest"]["sha256"],
                "path": relative_report,
                "sha256": report_sha256,
            }
        )
        if report["status"] != "PASSED":
            break

    if len(command_records) != len(fixture.results):
        raise CandidateCheckpointError(
            "fixture contains an ignored result after stop-first execution"
        )

    _verify_materialized_tree(candidate_sha, materialized_root, materialized_inventory)
    _remove_verified_materialization(materialized_root)

    passed = len(command_records) == len(FIXED_ENGINEERING_COMMAND_ORDER) and all(
        item["status"] == "PASSED" for item in command_records
    )
    if passed and sandbox_receipt["backend_kind"] == "FIXTURE_ONLY":
        raise CandidateCheckpointError(
            "SANDBOX_UNAVAILABLE: fixture-only execution completed but cannot yield engineering PASS"
        )
    evidence = seal_evidence(
        {
            "MIG-006": "PENDING_PROMOTION",
            "MIG-007": "PENDING_PROMOTION",
            "MIG-008": "PENDING_PROMOTION",
            "attempt_lineage": context["attempt_lineage"],
            "authority_ceiling": ENGINEERING_AUTHORITY_CEILING,
            "candidate": candidate,
            "command_order": list(FIXED_ENGINEERING_COMMAND_ORDER),
            "commands": command_records,
            "engineering_checkpoint": "PASS" if passed else "FAIL",
            "engineering_trust_boundary": {
                "cryptographic_production_attestation": False,
                "threat_model": "NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR",
            },
            "evidence_kind": ENGINEERING_LOCAL,
            "execution_sandbox": sandbox_receipt,
            "next_phase_permission": (
                "EXTERNAL_PRODUCTION_CHECKPOINT_ONLY" if passed else "BLOCKED"
            ),
            "phase": 8,
            "production_capabilities": {
                key: False for key in PRODUCTION_CAPABILITY_KEYS
            },
            "production_checkpoint": "PENDING_EXTERNAL",
            "promotion_gate": "PENDING" if passed else "FAIL",
            "release_context": release_context,
            "reports": report_bindings,
            "review": context["review"],
            "schema_version": SCHEMA_VERSION,
            "sensitive_data": {
                "contains_hidden_reasoning": False,
                "contains_pii": False,
                "contains_secrets": False,
            },
            "trust_root_verified": False,
        }
    )
    validate_evidence(evidence)
    _write_exclusive_json(run_root / EVIDENCE_NAME, evidence, "checkpoint evidence")
    _assert_snapshot_unchanged(context_snapshot, "engineering context")
    _assert_clean_detached_candidate(candidate_sha)
    return validate_run_evidence(run_root / EVIDENCE_NAME)


def execute_checkpoint(
    *,
    candidate_commit: str,
    run_dir: Path,
    context_file: Path,
    executor: Any | None = None,
) -> dict[str, Any]:
    del candidate_commit, run_dir, context_file, executor
    raise CandidateCheckpointError(
        "SANDBOX_UNAVAILABLE: no fixed authenticated execution backend is configured"
    )


def _assert_run_inventory(run_root: Path, expected_files: set[Path]) -> None:
    entries = list(run_root.rglob("*"))
    for entry in entries:
        if _is_link_or_reparse(entry):
            raise CandidateCheckpointError("run output contains a symlink, junction, or reparse point")
    actual_files = {entry.resolve(strict=True) for entry in entries if entry.is_file()}
    if actual_files != {path.resolve(strict=True) for path in expected_files}:
        raise CandidateCheckpointError("run output contains hidden, stale, or substituted files")
    relative_names = [path.relative_to(run_root).as_posix() for path in actual_files]
    if len({name.casefold() for name in relative_names}) != len(relative_names):
        raise CandidateCheckpointError("run output contains a case collision")
    expected_directories = {(run_root / "reports").resolve(strict=True)}
    actual_directories = {entry.resolve(strict=True) for entry in entries if entry.is_dir()}
    if actual_directories != expected_directories:
        raise CandidateCheckpointError("run output directory topology drifted")


def validate_run_evidence(evidence_path: Path) -> dict[str, Any]:
    snapshot = _read_authenticated_file(evidence_path, "checkpoint evidence")
    if snapshot.path.name != EVIDENCE_NAME:
        raise CandidateCheckpointError("checkpoint evidence filename drifted")
    evidence = validate_evidence(
        parse_json_bytes(snapshot.payload, context="checkpoint evidence")
    )
    if evidence["evidence_kind"] != ENGINEERING_LOCAL:
        raise CandidateCheckpointError("local run validator cannot accept external evidence")
    run_root = snapshot.path.parent.resolve(strict=True)
    if run_root.parent != _approved_evidence_root():
        raise CandidateCheckpointError(
            "checkpoint evidence is outside the approved local evidence root"
        )
    _assert_external_to_git(run_root, "checkpoint evidence")
    _assert_ancestry_has_no_alias(run_root, "run evidence")

    expected_files = {snapshot.path.resolve(strict=True)}
    for command, binding in zip(evidence["commands"], evidence["reports"], strict=True):
        relative = _relative_git_path(binding["path"], "report binding path")
        report_path = run_root.joinpath(*PurePosixPath(relative).parts)
        if report_path.resolve(strict=True).parent != (run_root / "reports").resolve(strict=True):
            raise CandidateCheckpointError("report binding escaped its fixed directory")
        report_snapshot = _read_authenticated_file(
            report_path.resolve(strict=True), f"report for {command['id']}"
        )
        if (
            report_snapshot.sha256 != binding["sha256"]
            or len(report_snapshot.payload) != binding["bytes"]
        ):
            raise CandidateCheckpointError(f"substituted report rejected: {command['id']}")
        report = parse_json_bytes(
            report_snapshot.payload, context=f"report for {command['id']}"
        )
        _assert_report_document(report, command)
        expected_files.add(report_path.resolve(strict=True))
    _assert_run_inventory(run_root, expected_files)

    required_paths = [item["path"] for item in evidence["candidate"]["path_blobs"]]
    observed_candidate = _capture_candidate(
        evidence["candidate"]["commit_sha"], required_paths
    )
    if observed_candidate != evidence["candidate"]:
        raise CandidateCheckpointError("candidate Git object/path binding drifted")
    release = evidence["release_context"]
    expected_configuration = _blob_bundle(
        observed_candidate, PHASE8_CONFIGURATION_MANIFEST_PATHS
    )
    expected_deployment = _blob_bundle(
        observed_candidate, PHASE8_DEPLOYMENT_MANIFEST_PATHS
    )
    expected_images = _derive_images(
        observed_candidate,
        (*PHASE8_CONFIGURATION_MANIFEST_PATHS, *PHASE8_DEPLOYMENT_MANIFEST_PATHS),
    )
    if release["configuration"] != expected_configuration:
        raise CandidateCheckpointError("configuration Git bundle drifted")
    if release["deployment_manifest"] != expected_deployment:
        raise CandidateCheckpointError("deployment Git bundle drifted")
    if release["images"] != expected_images:
        raise CandidateCheckpointError("manifest-derived image digest inventory drifted")
    return evidence


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan, or explicitly execute, the fixed local Phase 8 engineering candidate "
            "checkpoint. This command has no production or external-gate authority."
        )
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--context-file", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        candidate = _assert_sha1(arguments.candidate_commit, "candidate commit")
        if not arguments.execute:
            if arguments.run_dir is not None or arguments.context_file is not None:
                raise CandidateCheckpointError(
                    "--run-dir and --context-file require explicit --execute"
                )
            print(json.dumps(candidate_plan(candidate), indent=2, sort_keys=True))
            return 0
        if arguments.run_dir is None or arguments.context_file is None:
            raise CandidateCheckpointError(
                "--execute requires explicit --run-dir and --context-file"
            )
        evidence = execute_checkpoint(
            candidate_commit=candidate,
            run_dir=arguments.run_dir,
            context_file=arguments.context_file,
        )
    except (
        CandidateCheckpointError,
        EvidenceValidationError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exception:
        print(f"Phase 8 candidate checkpoint rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "MIG-006": evidence["MIG-006"],
                "MIG-007": evidence["MIG-007"],
                "MIG-008": evidence["MIG-008"],
                "authority_ceiling": evidence["authority_ceiling"],
                "candidate_commit": evidence["candidate"]["commit_sha"],
                "engineering_checkpoint": evidence["engineering_checkpoint"],
                "evidence": str((arguments.run_dir / EVIDENCE_NAME).resolve()),
                "production_checkpoint": evidence["production_checkpoint"],
                "promotion_gate": evidence["promotion_gate"],
            },
            sort_keys=True,
        )
    )
    return 0 if evidence["engineering_checkpoint"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = [
    "ACCEPTED_A8",
    "CONTEXT_SCHEMA_VERSION",
    "CandidateCheckpointError",
    "EVIDENCE_NAME",
    "FixtureExecution",
    "candidate_plan",
    "execute_checkpoint",
    "load_context",
    "main",
    "process_fixture_lifecycle",
    "validate_run_evidence",
]
