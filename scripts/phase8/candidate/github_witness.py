from __future__ import annotations

import argparse
import copy
import contextlib
import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence

try:
    from . import candidate_scope, command_contract, runtime_policy
except ImportError:  # Direct execution is required by the reusable workflow.
    _trusted_root = Path(__file__).resolve().parents[3]
    if str(_trusted_root) not in sys.path:
        sys.path.insert(0, str(_trusted_root))
    from scripts.phase8.candidate import (  # type: ignore[no-redef]
        candidate_scope,
        command_contract,
        runtime_policy,
    )


TRUSTED_ROOT = Path(__file__).resolve().parents[3]
ACCEPTED_A8 = "3c60bf5cc4e051a214e158cbf944fd6aba969f95"
SCOPE_PATH = "contracts/agent-platform/phase8/engineering-candidate-scope.json"
COMMAND_CONTRACT_PATH = (
    "contracts/agent-platform/phase8/engineering-candidate-commands.json"
)
DOCKERFILE_PATH = "infra-tests/phase8/runtime/Dockerfile"
REQUIREMENTS_LOCK_PATH = "infra-tests/phase8/runtime/requirements.lock"
RUNTIME_POLICY_PATH = "infra-tests/phase8/runtime/runtime-policy.json"
RAW_SCHEMA_VERSION = "phase8-isolated-command-result.v1"
WITNESS_SCHEMA_VERSION = "phase8-engineering-witness-manifest.v1"
SUMMARY_SCHEMA_VERSION = "phase8-engineering-witness-summary.v1"
STATUS_SCHEMA_VERSION = "phase8-engineering-witness-status.v1"
SUCCESS = "SOURCES_GREEN_AWAITING_SIGSTORE_AND_P0"
FAIL = "FAIL"
AUTHORITY = "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
RAW_AUTHORITY = "ENGINEERING_TEST_COMMAND_RESULT_ONLY"
ARCHIVE_NAME = "phase8-engineering-witness.tar"
SUMMARY_NAME = "phase8-engineering-witness-summary.json"
STATUS_NAME = "phase8-engineering-witness-status.json"
WITNESS_NAME = "manifest.json"

SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IMAGE_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
ATTEMPT = re.compile(r"^github-([1-9][0-9]{0,19})-([1-9][0-9]{0,9})$")
JOB = re.compile(
    r"^phase8_(wave_a_static|wave_a_java|wave_b_static_and_models|wave_b_java_unit|wave_b_postgresql_integration)$"
)
SAFE_REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
SAFE_SUMMARY = re.compile(r"^[\x09\x0a\x0d\x20-\x7e]{0,2048}$")
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")
WINDOWS_DEVICE = re.compile(
    r"^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$", re.IGNORECASE
)
SECRET = re.compile(
    r"(?i)(?:-----BEGIN [A-Z ]*PRIVATE KEY-----|"
    r"(?:authorization|cookie|credential|password|secret|access[_-]?token|"
    r"github[_-]?token)\s*[\"']?\s*[:=]\s*[\"']?\S+|"
    r"github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|"
    r"AKIA[0-9A-Z]{16})"
)

MAX_JSON_BYTES = 512 * 1024
MAX_JSON_DEPTH = 16
MAX_JSON_NODES = 4096
MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_REPORT_TOTAL_BYTES = 64 * 1024 * 1024
MAX_REPORT_FILES = 256
MAX_GIT_OUTPUT = 64 * 1024 * 1024
MAX_RAW_TREE_NODES = 2048
MAX_RAW_TREE_DEPTH = 4
MAX_OCI_ARCHIVE_BYTES = runtime_policy.MAX_OCI_ARCHIVE_BYTES
MAX_DOCKER_ARCHIVE_BYTES = runtime_policy.MAX_DOCKER_ARCHIVE_BYTES
MAX_CANDIDATE_ARCHIVE_TOTAL_BYTES = 256 * 1024 * 1024
MAX_RAW_TREE_BYTES = (
    (2 * MAX_OCI_ARCHIVE_BYTES)
    + (2 * MAX_DOCKER_ARCHIVE_BYTES)
    + MAX_CANDIDATE_ARCHIVE_TOTAL_BYTES
    + MAX_REPORT_TOTAL_BYTES
    + (4 * 1024 * 1024 * 1024)
)
EXPECTED_WHEELHOUSE_FILES = 15

EXPECTED_JOB = {
    command_id: f"phase8_{command_id}" for command_id in command_contract.COMMAND_ORDER
}
TRUSTED_INPUT_PATHS = (
    COMMAND_CONTRACT_PATH,
    DOCKERFILE_PATH,
    REQUIREMENTS_LOCK_PATH,
    RUNTIME_POLICY_PATH,
)
FIXED_REPOSITORY = "Jupiter363/AfterSaleFlow-Agent"
FIXED_REPOSITORY_ID = "1282437633"
CALLER_WORKFLOW_PATH = ".github/workflows/phase8-engineering-caller.yml"
TRUSTED_WORKFLOW_PATH = ".github/workflows/phase8-engineering-witness.yml"
CALLER_WORKFLOW_REF = (
    f"{FIXED_REPOSITORY}/{CALLER_WORKFLOW_PATH}"
    "@refs/heads/codex/p8-production-hardening"
)


class WitnessValidationError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        if not TOKEN.fullmatch(code):
            raise ValueError("witness error code must be an opaque token")
        super().__init__(message)
        self.code = code


def _resolve_git_executable() -> Path:
    discovered = shutil.which("git")
    if not discovered:
        raise WitnessValidationError(
            "GIT_UNAVAILABLE", "the fixed Git executable is unavailable"
        )
    try:
        executable = Path(discovered).resolve(strict=True)
        metadata = executable.stat()
    except OSError as exception:
        raise WitnessValidationError(
            "GIT_UNAVAILABLE", "the fixed Git executable cannot be inspected"
        ) from exception
    if not executable.is_absolute() or not stat.S_ISREG(metadata.st_mode):
        raise WitnessValidationError(
            "GIT_UNAVAILABLE", "the fixed Git executable is not a regular file"
        )
    return executable


def _git_executable_identity(path: Path) -> tuple[int, int, int, int]:
    metadata = path.stat()
    return (
        int(metadata.st_dev),
        int(metadata.st_ino),
        int(metadata.st_size),
        int(metadata.st_mtime_ns),
    )


GIT_EXECUTABLE = _resolve_git_executable()
GIT_EXECUTABLE_IDENTITY = _git_executable_identity(GIT_EXECUTABLE)


@dataclass(frozen=True)
class AuthenticatedFile:
    path: Path
    identity: tuple[int, int, int, int, int]
    payload: bytes
    sha256: str


@dataclass(frozen=True)
class AuthenticatedDigestFile:
    path: Path
    identity: tuple[int, int, int, int, int]
    bytes: int
    sha256: str


@dataclass(frozen=True)
class JunitFacts:
    tests: int
    failures: int
    errors: int
    skipped: int
    suite_ids: tuple[str, ...]
    testcase_ids: tuple[str, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "errors": self.errors,
            "failures": self.failures,
            "skipped": self.skipped,
            "suite_ids": list(self.suite_ids),
            "testcase_ids": list(self.testcase_ids),
            "tests": self.tests,
        }


@dataclass(frozen=True)
class SharedRuntimeEvidence:
    root: Path
    build_file: AuthenticatedFile
    build_receipt: dict[str, Any]
    producer_oci_archive_path: Path
    producer_docker_archive_path: Path
    wheelhouse_manifest_file: AuthenticatedFile
    wheelhouse_manifest: list[dict[str, Any]]
    wheel_files: tuple[AuthenticatedDigestFile, ...]
    observation_file: AuthenticatedFile
    observation_receipt: dict[str, Any]
    observer_oci_archive_path: Path
    observer_docker_archive_path: Path


AuthenticatedInput = AuthenticatedFile | AuthenticatedDigestFile


def _canonical_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exception:
        raise WitnessValidationError(
            "NON_CANONICAL_DATA", str(exception)
        ) from exception


def _canonical_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _git_blob_sha1(payload: bytes) -> str:
    return hashlib.sha1(f"blob {len(payload)}\0".encode("ascii") + payload).hexdigest()


def _exact_keys(value: Any, keys: Iterable[str], context: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise WitnessValidationError(
            "RAW_SHAPE_INVALID", f"{context} must be an object"
        )
    expected = set(keys)
    if set(value) != expected:
        raise WitnessValidationError(
            "RAW_SHAPE_INVALID", f"{context} fields differ from the fixed schema"
        )
    if any(not isinstance(key, str) for key in value):
        raise WitnessValidationError(
            "RAW_SHAPE_INVALID", f"{context} has non-string keys"
        )
    return value


def _strict_string(value: Any, context: str, *, limit: int = 4096) -> str:
    if not isinstance(value, str) or not value or len(value) > limit or "\x00" in value:
        raise WitnessValidationError(
            "RAW_SHAPE_INVALID", f"{context} is not bounded text"
        )
    return value


def _strict_int(value: Any, context: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise WitnessValidationError(
            "RAW_SHAPE_INVALID", f"{context} is not a valid integer"
        )
    return value


def _strict_bool(value: Any, context: str) -> bool:
    if not isinstance(value, bool):
        raise WitnessValidationError("RAW_SHAPE_INVALID", f"{context} must be boolean")
    return value


def _assert_sha1(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA1.fullmatch(value):
        raise WitnessValidationError(
            "IDENTITY_INVALID", f"{context} is not a full Git SHA"
        )
    return value


def _assert_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise WitnessValidationError("IDENTITY_INVALID", f"{context} is not SHA-256")
    return value


def _safe_relative_path(value: Any, context: str) -> str:
    text = _strict_string(value, context, limit=512)
    if unicodedata.normalize("NFC", text) != text or "\\" in text or ":" in text:
        raise WitnessValidationError("PATH_INVALID", f"{context} is not canonical")
    path = PurePosixPath(text)
    if (
        path.is_absolute()
        or not path.parts
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise WitnessValidationError("PATH_INVALID", f"{context} escapes its root")
    for part in path.parts:
        if part.endswith((" ", ".")) or WINDOWS_DEVICE.fullmatch(part):
            raise WitnessValidationError(
                "PATH_INVALID", f"{context} has an aliased component"
            )
        if any(ord(character) < 32 or ord(character) == 127 for character in part):
            raise WitnessValidationError(
                "PATH_INVALID", f"{context} contains control bytes"
            )
    return path.as_posix()


def _bounded_json(raw: bytes, context: str) -> dict[str, Any]:
    if not raw or len(raw) > MAX_JSON_BYTES:
        raise WitnessValidationError(
            "RAW_JSON_INVALID", f"{context} byte size is invalid"
        )
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise WitnessValidationError(
            "RAW_JSON_INVALID", f"{context} must be BOM-free UTF-8"
        )

    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise WitnessValidationError(
                    "RAW_JSON_INVALID", f"{context} contains duplicate key {key!r}"
                )
            result[key] = value
        return result

    try:
        parsed = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                WitnessValidationError(
                    "RAW_JSON_INVALID", f"{context} has non-finite number {token}"
                )
            ),
        )
    except WitnessValidationError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise WitnessValidationError(
            "RAW_JSON_INVALID", f"{context} is invalid JSON"
        ) from exception
    if not isinstance(parsed, dict):
        raise WitnessValidationError(
            "RAW_JSON_INVALID", f"{context} root must be object"
        )
    nodes = 0
    stack: list[tuple[Any, int]] = [(parsed, 1)]
    while stack:
        value, depth = stack.pop()
        nodes += 1
        if nodes > MAX_JSON_NODES or depth > MAX_JSON_DEPTH:
            raise WitnessValidationError(
                "RAW_JSON_INVALID", f"{context} is too complex"
            )
        if isinstance(value, dict):
            stack.extend((item, depth + 1) for item in value.values())
        elif isinstance(value, list):
            stack.extend((item, depth + 1) for item in value)
    return parsed


def _file_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_nlink,
    )


def _read_authenticated_file(path: Path, context: str, limit: int) -> AuthenticatedFile:
    try:
        before = path.lstat()
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_UNAVAILABLE", f"{context} cannot be inspected"
        ) from exception
    if path.is_symlink() or not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} is not a single-link file"
        )
    if before.st_size < 0 or before.st_size > limit:
        raise WitnessValidationError(
            "FILE_SIZE_INVALID", f"{context} exceeds its byte limit"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            opened = os.fstat(handle.fileno())
            if _file_identity(opened) != _file_identity(before):
                raise WitnessValidationError(
                    "FILE_SUBSTITUTED", f"{context} changed before read"
                )
            payload = handle.read(limit + 1)
            after = os.fstat(handle.fileno())
    except WitnessValidationError:
        raise
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_UNAVAILABLE", f"{context} cannot be read"
        ) from exception
    if len(payload) > limit or _file_identity(after) != _file_identity(before):
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} changed while read"
        )
    try:
        final = path.lstat()
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} disappeared"
        ) from exception
    if _file_identity(final) != _file_identity(before):
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} changed after read"
        )
    return AuthenticatedFile(path, _file_identity(before), payload, _sha256(payload))


def _revalidate_file(item: AuthenticatedFile, context: str) -> None:
    observed = _read_authenticated_file(item.path, context, len(item.payload))
    if observed.identity != item.identity or observed.sha256 != item.sha256:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} changed after validation"
        )


def _read_authenticated_digest_file(
    path: Path, context: str, limit: int
) -> AuthenticatedDigestFile:
    try:
        before = path.lstat()
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_UNAVAILABLE", f"{context} cannot be inspected"
        ) from exception
    if path.is_symlink() or not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} is not a single-link file"
        )
    if before.st_size < 1 or before.st_size > limit:
        raise WitnessValidationError(
            "FILE_SIZE_INVALID", f"{context} exceeds its byte limit"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    digest = hashlib.sha256()
    total = 0
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            opened = os.fstat(handle.fileno())
            if _file_identity(opened) != _file_identity(before):
                raise WitnessValidationError(
                    "FILE_SUBSTITUTED", f"{context} changed before hashing"
                )
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > limit:
                    raise WitnessValidationError(
                        "FILE_SIZE_INVALID", f"{context} exceeds its byte limit"
                    )
                digest.update(chunk)
            after = os.fstat(handle.fileno())
    except WitnessValidationError:
        raise
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_UNAVAILABLE", f"{context} cannot be hashed"
        ) from exception
    if total != before.st_size or _file_identity(after) != _file_identity(before):
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} changed while hashing"
        )
    try:
        final = path.lstat()
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} disappeared after hashing"
        ) from exception
    if _file_identity(final) != _file_identity(before):
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} path changed after hashing"
        )
    return AuthenticatedDigestFile(
        path=path,
        identity=_file_identity(before),
        bytes=total,
        sha256=digest.hexdigest(),
    )


def _revalidate_digest_file(item: AuthenticatedDigestFile, context: str) -> None:
    observed = _read_authenticated_digest_file(item.path, context, item.bytes)
    if observed.identity != item.identity or observed.sha256 != item.sha256:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} changed after validation"
        )


def _revalidate_authenticated(item: AuthenticatedInput, context: str) -> None:
    if isinstance(item, AuthenticatedDigestFile):
        _revalidate_digest_file(item, context)
    else:
        _revalidate_file(item, context)


def _capture_verified_digest_file(
    path: Path, *, expected_bytes: int, expected_sha256: str, context: str
) -> AuthenticatedDigestFile:
    """Capture identity after a trusted verifier has hashed the exact path."""

    try:
        metadata = path.lstat()
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} disappeared after verification"
        ) from exception
    if (
        path.is_symlink()
        or not stat.S_ISREG(metadata.st_mode)
        or metadata.st_nlink != 1
        or metadata.st_size != expected_bytes
    ):
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", f"{context} identity differs after verification"
        )
    return AuthenticatedDigestFile(
        path=path,
        identity=_file_identity(metadata),
        bytes=expected_bytes,
        sha256=expected_sha256,
    )


def _assert_directory_tree(
    root: Path, context: str
) -> tuple[tuple[str, int, int, int, int, int, int], ...]:
    if not root.is_absolute():
        raise WitnessValidationError("PATH_INVALID", f"{context} must be absolute")
    try:
        metadata = root.lstat()
    except OSError as exception:
        raise WitnessValidationError(
            "PATH_INVALID", f"{context} cannot be inspected"
        ) from exception
    if root.is_symlink() or not stat.S_ISDIR(metadata.st_mode):
        raise WitnessValidationError(
            "PATH_INVALID", f"{context} is not a regular directory"
        )
    resolved = root.resolve(strict=True)
    if resolved != root:
        raise WitnessValidationError("PATH_INVALID", f"{context} is aliased")
    nodes = 0
    total_bytes = 0
    snapshot: list[tuple[str, int, int, int, int, int, int]] = []
    for path in root.rglob("*"):
        nodes += 1
        relative = path.relative_to(root)
        if nodes > MAX_RAW_TREE_NODES or len(relative.parts) > MAX_RAW_TREE_DEPTH:
            raise WitnessValidationError(
                "RAW_TOPOLOGY_INVALID", f"{context} exceeds its tree budget"
            )
        entry = path.lstat()
        snapshot.append(
            (
                relative.as_posix(),
                int(entry.st_dev),
                int(entry.st_ino),
                int(entry.st_mode),
                int(entry.st_nlink),
                int(entry.st_size),
                int(entry.st_mtime_ns),
            )
        )
        if path.is_symlink() or bool(getattr(entry, "st_file_attributes", 0) & 0x400):
            raise WitnessValidationError(
                "FILE_SUBSTITUTED", f"{context} contains a link"
            )
        if not (stat.S_ISDIR(entry.st_mode) or stat.S_ISREG(entry.st_mode)):
            raise WitnessValidationError(
                "FILE_SUBSTITUTED", f"{context} has a special file"
            )
        if stat.S_ISREG(entry.st_mode) and entry.st_nlink != 1:
            raise WitnessValidationError(
                "FILE_SUBSTITUTED", f"{context} has a hard link"
            )
        if stat.S_ISREG(entry.st_mode):
            total_bytes += entry.st_size
            if total_bytes > MAX_RAW_TREE_BYTES:
                raise WitnessValidationError(
                    "RAW_TOPOLOGY_INVALID", f"{context} exceeds its byte budget"
                )
    return tuple(sorted(snapshot))


def _resolve_input_directory(path: Path, context: str) -> Path:
    try:
        if path.is_symlink():
            raise WitnessValidationError(
                "PATH_INVALID", f"{context} cannot be a symlink"
            )
        resolved = path.resolve(strict=True)
        metadata = resolved.lstat()
    except WitnessValidationError:
        raise
    except OSError as exception:
        raise WitnessValidationError(
            "PATH_INVALID", f"{context} is unavailable"
        ) from exception
    if not stat.S_ISDIR(metadata.st_mode):
        raise WitnessValidationError("PATH_INVALID", f"{context} is not a directory")
    return resolved


def _git_environment() -> dict[str, str]:
    return {
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_OPTIONAL_LOCKS": "0",
        "GIT_TERMINAL_PROMPT": "0",
        "HOME": os.devnull,
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": os.fspath(GIT_EXECUTABLE.parent),
    }


def _assert_git_executable_unchanged() -> None:
    try:
        identity = _git_executable_identity(GIT_EXECUTABLE)
    except OSError as exception:
        raise WitnessValidationError(
            "GIT_UNAVAILABLE", "fixed Git executable is unavailable"
        ) from exception
    if identity != GIT_EXECUTABLE_IDENTITY:
        raise WitnessValidationError(
            "GIT_UNAVAILABLE", "fixed Git executable identity changed"
        )


def _git_query(
    repository: Path, arguments: Sequence[str], *, limit: int = MAX_GIT_OUTPUT
) -> bytes:
    """Run only fixed, read-only Git plumbing. No caller-selectable executable exists."""
    allowed = {
        "for-each-ref",
        "rev-parse",
        "status",
    }
    if not arguments or arguments[0] not in allowed:
        raise WitnessValidationError(
            "GIT_QUERY_FORBIDDEN", "non-read-only Git query rejected"
        )
    _assert_git_executable_unchanged()
    try:
        completed = subprocess.run(
            [
                os.fspath(GIT_EXECUTABLE),
                "--no-replace-objects",
                "-c",
                "protocol.allow=never",
                "-c",
                "core.fsmonitor=false",
                "-C",
                os.fspath(repository),
                *arguments,
            ],
            cwd=os.fspath(repository),
            env=_git_environment(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
            check=False,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError) as exception:
        raise WitnessValidationError(
            "GIT_QUERY_FAILED", "fixed Git query did not complete"
        ) from exception
    _assert_git_executable_unchanged()
    if len(completed.stdout) > limit or len(completed.stderr) > 64 * 1024:
        raise WitnessValidationError(
            "GIT_QUERY_FAILED", "fixed Git query output exceeded limit"
        )
    if completed.returncode != 0:
        raise WitnessValidationError(
            "GIT_QUERY_FAILED", "fixed Git query rejected candidate"
        )
    return completed.stdout


def _git_text(repository: Path, *arguments: str) -> str:
    try:
        return (
            _git_query(repository, arguments).decode("utf-8", errors="strict").strip()
        )
    except UnicodeDecodeError as exception:
        raise WitnessValidationError(
            "GIT_QUERY_FAILED", "Git metadata is not UTF-8"
        ) from exception


def _assert_git_metadata(repository: Path) -> None:
    if _git_text(repository, "for-each-ref", "--format=%(refname)", "refs/replace"):
        raise WitnessValidationError(
            "GIT_ALIAS_REJECTED", "Git replace refs are forbidden"
        )
    common = Path(
        _git_text(repository, "rev-parse", "--path-format=absolute", "--git-common-dir")
    )
    for relative in ("info/grafts", "objects/info/alternates"):
        path = common / relative
        if path.exists() or path.is_symlink():
            raise WitnessValidationError(
                "GIT_ALIAS_REJECTED", "Git grafts or alternates are forbidden"
            )


def _candidate_checkout_snapshot(
    repository: Path, candidate_sha: str
) -> dict[str, Any]:
    _assert_git_metadata(repository)
    head = _assert_sha1(_git_text(repository, "rev-parse", "HEAD"), "candidate HEAD")
    if head != candidate_sha:
        raise WitnessValidationError(
            "CANDIDATE_MISMATCH", "candidate checkout HEAD differs"
        )
    if _git_query(
        repository, ["status", "--porcelain=v1", "-z", "--untracked-files=all"]
    ):
        raise WitnessValidationError(
            "CANDIDATE_DIRTY", "candidate checkout is not clean"
        )
    candidate_common = Path(
        _git_text(repository, "rev-parse", "--path-format=absolute", "--git-common-dir")
    ).resolve(strict=True)
    trusted_common = Path(
        _git_text(
            TRUSTED_ROOT, "rev-parse", "--path-format=absolute", "--git-common-dir"
        )
    ).resolve(strict=True)
    if candidate_common != trusted_common:
        raise WitnessValidationError(
            "CANDIDATE_OBJECT_STORE_MISMATCH",
            "candidate checkout does not share the authenticated object store",
        )
    return {
        "candidate_sha": candidate_sha,
        "clean": True,
        "git_common_dir": os.fspath(candidate_common),
    }


def _trusted_snapshot(trusted_sha: str) -> dict[str, Any]:
    _assert_git_metadata(TRUSTED_ROOT)
    head = _assert_sha1(
        _git_text(TRUSTED_ROOT, "rev-parse", "HEAD"), "trusted code HEAD"
    )
    if head != trusted_sha:
        raise WitnessValidationError(
            "TRUSTED_CODE_MISMATCH", "trusted builder HEAD differs"
        )
    if _git_query(
        TRUSTED_ROOT, ["status", "--porcelain=v1", "-z", "--untracked-files=all"]
    ):
        raise WitnessValidationError(
            "TRUSTED_CODE_DIRTY", "trusted builder checkout is not clean"
        )
    tree = _assert_sha1(
        _git_text(TRUSTED_ROOT, "rev-parse", f"{trusted_sha}^{{tree}}"), "trusted tree"
    )
    return {"trusted_code_sha": trusted_sha, "trusted_tree_sha": tree}


def _github_identity(
    candidate_sha: str,
    attempt_id: str,
    *,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
) -> dict[str, str]:
    match = ATTEMPT.fullmatch(attempt_id)
    if match is None:
        raise WitnessValidationError(
            "ATTEMPT_INVALID", "attempt-id must bind GitHub run and attempt"
        )
    expected = {
        "actions": os.environ.get("GITHUB_ACTIONS", ""),
        "candidate_sha": os.environ.get("GITHUB_SHA", ""),
        "repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "repository_id": os.environ.get("GITHUB_REPOSITORY_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "runner_arch": os.environ.get("RUNNER_ARCH", ""),
        "runner_environment": os.environ.get("RUNNER_ENVIRONMENT", ""),
        "runner_os": os.environ.get("RUNNER_OS", ""),
        "server_url": os.environ.get("GITHUB_SERVER_URL", ""),
        "workflow_ref": os.environ.get("GITHUB_WORKFLOW_REF", ""),
        "workflow_sha": os.environ.get("GITHUB_WORKFLOW_SHA", ""),
    }
    if (
        expected["actions"] != "true"
        or expected["runner_environment"] != "github-hosted"
    ):
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "GitHub-hosted runner is required"
        )
    if expected["runner_os"] != "Linux" or expected["runner_arch"] != "X64":
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "fixed Linux X64 runner is required"
        )
    if (
        expected["candidate_sha"] != candidate_sha
        or expected["workflow_sha"] != candidate_sha
        or expected["workflow_ref"] != CALLER_WORKFLOW_REF
    ):
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "GitHub candidate/caller binding differs"
        )
    if expected["run_id"] != match.group(1) or expected["run_attempt"] != match.group(
        2
    ):
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "GitHub attempt binding differs"
        )
    if expected["server_url"] != "https://github.com":
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "public GitHub server is required"
        )
    if (
        not SAFE_REPOSITORY.fullmatch(expected["repository"])
        or expected["repository"] != FIXED_REPOSITORY
        or expected["repository_id"] != FIXED_REPOSITORY_ID
    ):
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "repository identity is invalid"
        )
    trusted_workflow_sha = _assert_sha1(trusted_workflow_sha, "trusted workflow SHA")
    expected_trusted_ref = (
        f"{FIXED_REPOSITORY}/{TRUSTED_WORKFLOW_PATH}@{trusted_workflow_sha}"
    )
    if (
        trusted_workflow_ref != expected_trusted_ref
        or trusted_workflow_repository != FIXED_REPOSITORY
        or trusted_workflow_file_path != TRUSTED_WORKFLOW_PATH
    ):
        raise WitnessValidationError(
            "BUILDER_IDENTITY_INVALID", "trusted reusable workflow binding differs"
        )
    expected.pop("actions")
    expected.update(
        {
            "job_workflow_file_path": trusted_workflow_file_path,
            "job_workflow_ref": trusted_workflow_ref,
            "job_workflow_repository": trusted_workflow_repository,
            "job_workflow_sha": trusted_workflow_sha,
        }
    )
    return expected


def _load_trusted_inputs(
    scope: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, AuthenticatedFile]]:
    files: dict[str, AuthenticatedFile] = {}
    inventory = {item["path"]: item for item in scope["derived_inventory"]}
    for relative in TRUSTED_INPUT_PATHS:
        trusted = _read_authenticated_file(
            TRUSTED_ROOT / relative, relative, MAX_JSON_BYTES
        )
        binding = inventory.get(relative)
        expected_binding = {
            "bytes": len(trusted.payload),
            "git_blob_sha": _git_blob_sha1(trusted.payload),
            "mode": "100644",
            "path": relative,
            "sha256": trusted.sha256,
        }
        if not isinstance(binding, Mapping) or any(
            binding.get(key) != value for key, value in expected_binding.items()
        ):
            raise WitnessValidationError(
                "TRUSTED_INPUT_MISMATCH",
                f"scope snapshot differs from trusted input: {relative}",
            )
        files[relative] = trusted
    try:
        contract_document = command_contract.validate_command_contract(
            command_contract.parse_bounded_json_bytes(
                files[COMMAND_CONTRACT_PATH].payload
            )
        )
    except command_contract.CommandContractValidationError as exception:
        raise WitnessValidationError(
            "COMMAND_CONTRACT_INVALID", str(exception)
        ) from exception
    return contract_document, files


def _caller_workflow_binding(
    candidate_dir: Path,
    scope: Mapping[str, Any],
    trusted_workflow_sha: str,
) -> tuple[dict[str, str], AuthenticatedFile]:
    caller = _read_authenticated_file(
        candidate_dir / CALLER_WORKFLOW_PATH,
        "caller workflow",
        16 * 1024,
    )
    expected_text = (
        "name: Phase 8 engineering caller\n"
        "\n"
        '"on":\n'
        "  push:\n"
        "    branches:\n"
        "      - codex/p8-production-hardening\n"
        "\n"
        "permissions: {}\n"
        "\n"
        "jobs:\n"
        "  witness:\n"
        "    permissions:\n"
        "      contents: read\n"
        "      id-token: write\n"
        "      attestations: write\n"
        "      artifact-metadata: write\n"
        "    uses: Jupiter363/AfterSaleFlow-Agent/.github/workflows/"
        f"phase8-engineering-witness.yml@{trusted_workflow_sha}\n"
    ).encode("ascii")
    if caller.payload != expected_text:
        raise WitnessValidationError(
            "CALLER_WORKFLOW_INVALID",
            "caller workflow differs from the canonical template",
        )
    derived = {item["path"]: item for item in scope["derived_inventory"]}.get(
        CALLER_WORKFLOW_PATH
    )
    expected_scope = {
        "bytes": len(caller.payload),
        "git_blob_sha": _git_blob_sha1(caller.payload),
        "mode": "100644",
        "path": CALLER_WORKFLOW_PATH,
        "sha256": caller.sha256,
    }
    if not isinstance(derived, Mapping) or any(
        derived.get(key) != value for key, value in expected_scope.items()
    ):
        raise WitnessValidationError(
            "CALLER_WORKFLOW_INVALID",
            "caller workflow differs from the authenticated scope snapshot",
        )
    binding = {
        "file_sha256": caller.sha256,
        "git_blob_sha1": _git_blob_sha1(caller.payload),
        "mode": "100644",
        "path": CALLER_WORKFLOW_PATH,
        "trusted_workflow_sha": trusted_workflow_sha,
    }
    return binding, caller


def _validate_scope(candidate_dir: Path, candidate_sha: str) -> dict[str, Any]:
    manifest = _read_authenticated_file(
        candidate_dir / SCOPE_PATH,
        "candidate scope manifest",
        MAX_JSON_BYTES,
    ).payload
    try:
        result = candidate_scope.validate(candidate_sha, manifest)
    except candidate_scope.CandidateScopeValidationError as exception:
        raise WitnessValidationError(
            "CANDIDATE_SCOPE_INVALID", str(exception)
        ) from exception
    required = {
        "accepted_entry_sha",
        "allowed_changes",
        "authority_ceiling",
        "candidate_parent_sha",
        "candidate_sha",
        "candidate_tree_sha",
        "derived_inventory",
        "derived_inventory_sha256",
        "materialization_inventories",
        "production_authority",
        "self_path",
    }
    if not isinstance(result, dict) or set(result) != required:
        raise WitnessValidationError(
            "CANDIDATE_SCOPE_INVALID", "scope result shape drifted"
        )
    if (
        result["accepted_entry_sha"] != ACCEPTED_A8
        or result["candidate_sha"] != candidate_sha
    ):
        raise WitnessValidationError(
            "CANDIDATE_SCOPE_INVALID", "scope result identity drifted"
        )
    return result


def _parse_junit(payload: bytes, context: str) -> JunitFacts:
    if not payload or len(payload) > MAX_REPORT_BYTES:
        raise WitnessValidationError("JUNIT_INVALID", f"{context} size is invalid")
    lowered = payload.lower()
    if b"<!doctype" in lowered or b"<!entity" in lowered:
        raise WitnessValidationError(
            "JUNIT_INVALID", f"{context} contains XML declarations"
        )
    if SECRET.search(payload.decode("utf-8", errors="ignore")):
        raise WitnessValidationError(
            "CREDENTIAL_LEAK", f"{context} contains credential material"
        )
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exception:
        raise WitnessValidationError(
            "JUNIT_INVALID", f"{context} is malformed XML"
        ) from exception
    if any("}" in element.tag or ":" in element.tag for element in root.iter()):
        raise WitnessValidationError(
            "JUNIT_INVALID", f"{context} contains XML namespaces"
        )
    if root.tag not in {"testsuite", "testsuites"}:
        raise WitnessValidationError("JUNIT_INVALID", f"{context} root is not JUnit")
    testcases = list(root.iter("testcase"))
    if len(testcases) > 100_000:
        raise WitnessValidationError(
            "JUNIT_INVALID", f"{context} has too many testcases"
        )
    ids: list[str] = []
    seen_ids: set[str] = set()
    failures = errors = skipped = 0
    for testcase in testcases:
        classname = _strict_string(
            testcase.attrib.get("classname"), f"{context} classname", limit=512
        )
        name = _strict_string(
            testcase.attrib.get("name"), f"{context} testcase", limit=512
        )
        identity = f"{classname}::{name}"
        if identity in seen_ids:
            raise WitnessValidationError(
                "JUNIT_INVALID", f"{context} duplicates a testcase"
            )
        seen_ids.add(identity)
        ids.append(identity)
        child_tags = {child.tag.casefold() for child in testcase}
        failures += int(bool(child_tags & {"failure", "flakyfailure", "rerunfailure"}))
        errors += int(bool(child_tags & {"error", "flakyerror", "rerunerror"}))
        skipped += int("skipped" in child_tags)
    suites = sorted(
        {
            _strict_string(suite.attrib.get("name"), f"{context} suite", limit=512)
            for suite in root.iter("testsuite")
        }
    )
    return JunitFacts(
        tests=len(testcases),
        failures=failures,
        errors=errors,
        skipped=skipped,
        suite_ids=tuple(suites),
        testcase_ids=tuple(sorted(ids)),
    )


def _sum_facts(items: Sequence[JunitFacts]) -> JunitFacts:
    return JunitFacts(
        tests=sum(item.tests for item in items),
        failures=sum(item.failures for item in items),
        errors=sum(item.errors for item in items),
        skipped=sum(item.skipped for item in items),
        suite_ids=tuple(
            sorted(identity for item in items for identity in item.suite_ids)
        ),
        testcase_ids=tuple(
            sorted(identity for item in items for identity in item.testcase_ids)
        ),
    )


def _assert_suite_selection(command: Mapping[str, Any], facts: JunitFacts) -> None:
    argv = command["argv"]
    if command["backend_kind"] == command_contract.STATIC_BACKEND_KIND:
        selected = [
            Path(item).stem
            for item in argv
            if item.startswith("tests/") and item.endswith(".py")
        ]
        for identity in facts.testcase_ids:
            if not any(test in identity for test in selected):
                raise WitnessValidationError(
                    "JUNIT_SUITE_MISMATCH", "pytest report contains an unselected suite"
                )
        if any(
            not any(test in identity for identity in facts.testcase_ids)
            for test in selected
        ):
            raise WitnessValidationError(
                "JUNIT_SUITE_MISMATCH", "pytest report omitted a selected suite"
            )
        return
    selectors = [
        item.split("=", 1)[1]
        for item in argv
        if item.startswith(("-Dtest=", "-Dit.test="))
    ]
    if len(selectors) != 1:
        raise WitnessValidationError(
            "JUNIT_SUITE_MISMATCH", "Maven selector is ambiguous"
        )
    allowed = set(selectors[0].split(","))
    observed = {
        identity.split("::", 1)[0].rsplit(".", 1)[-1] for identity in facts.testcase_ids
    }
    if observed != allowed:
        raise WitnessValidationError(
            "JUNIT_SUITE_MISMATCH", "Maven suite identities differ"
        )


def _validate_output_summary(value: Any, context: str) -> dict[str, Any]:
    data = _exact_keys(value, {"bytes", "sha256", "summary"}, context)
    count = _strict_int(data["bytes"], f"{context}.bytes")
    digest = _assert_sha256(data["sha256"], f"{context}.sha256")
    summary = data["summary"]
    if not isinstance(summary, str) or not SAFE_SUMMARY.fullmatch(summary):
        raise WitnessValidationError(
            "RAW_SHAPE_INVALID", f"{context}.summary is not bounded ASCII"
        )
    if SECRET.search(summary):
        raise WitnessValidationError(
            "CREDENTIAL_LEAK", f"{context}.summary contains credentials"
        )
    return {"bytes": count, "sha256": digest, "summary": summary}


def _validate_runtime_reference(
    value: Any, expected_path: str | None, context: str
) -> dict[str, Any]:
    reference = _exact_keys(value, {"bytes", "path", "sha256"}, context)
    path = _safe_relative_path(reference["path"], f"{context} path")
    if expected_path is not None and path != expected_path:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", f"{context} path differs"
        )
    byte_count = _strict_int(reference["bytes"], f"{context} bytes", minimum=1)
    digest = _assert_sha256(reference["sha256"], f"{context} sha256")
    return {"bytes": byte_count, "path": path, "sha256": digest}


def _validate_raw_result(
    raw: Mapping[str, Any],
    *,
    command: Mapping[str, Any],
    command_contract_payload_sha256: str,
    raw_directory_name: str,
    order: int,
    attempt_id: str,
    candidate: Mapping[str, Any],
    scope: Mapping[str, Any],
    github: Mapping[str, str],
    trusted_sha: str,
    trusted_transition: Mapping[str, Any],
    trusted_transition_sha256: str,
) -> dict[str, Any]:
    result = _exact_keys(
        raw,
        {
            "attempt_id",
            "authority",
            "candidate",
            "command",
            "execution",
            "github",
            "materialization",
            "reports",
            "runtime",
            "schema_version",
        },
        "raw result",
    )
    if (
        result["schema_version"] != RAW_SCHEMA_VERSION
        or result["authority"] != RAW_AUTHORITY
    ):
        raise WitnessValidationError(
            "RAW_AUTHORITY_INVALID", "raw result authority drifted"
        )
    if result["attempt_id"] != attempt_id:
        raise WitnessValidationError(
            "RAW_BINDING_INVALID", "raw attempt binding differs"
        )

    candidate_binding = _exact_keys(
        result["candidate"],
        {
            "candidate_sha",
            "candidate_tree_sha",
            "scope_inventory_sha256",
            "trusted_transition",
            "trusted_transition_sha256",
        },
        "raw candidate",
    )
    expected_candidate = {
        "candidate_sha": candidate["candidate_sha"],
        "candidate_tree_sha": candidate["candidate_tree_sha"],
        "scope_inventory_sha256": scope["derived_inventory_sha256"],
        "trusted_transition": copy.deepcopy(trusted_transition),
        "trusted_transition_sha256": trusted_transition_sha256,
    }
    if dict(candidate_binding) != expected_candidate:
        raise WitnessValidationError(
            "RAW_BINDING_INVALID", "raw candidate binding differs"
        )

    raw_command = _exact_keys(
        result["command"],
        {*command.keys(), "contract_payload_sha256", "order"},
        "raw command",
    )
    expected_command = {
        **command,
        "contract_payload_sha256": command_contract_payload_sha256,
        "order": order,
    }
    if dict(raw_command) != expected_command:
        raise WitnessValidationError(
            "RAW_COMMAND_INVALID", "actual command differs from contract"
        )

    raw_github = _exact_keys(
        result["github"],
        {
            "candidate_sha",
            "job_workflow_file_path",
            "job_workflow_ref",
            "job_workflow_repository",
            "job_workflow_sha",
            "job",
            "repository",
            "repository_id",
            "run_attempt",
            "run_id",
            "runner_arch",
            "runner_environment",
            "runner_os",
            "server_url",
            "trusted_code_sha",
            "workflow_ref",
            "workflow_sha",
        },
        "raw github",
    )
    expected_github = dict(github)
    expected_github["job"] = EXPECTED_JOB[command["id"]]
    expected_github["trusted_code_sha"] = trusted_sha
    if dict(raw_github) != expected_github:
        raise WitnessValidationError(
            "RAW_BINDING_INVALID", "raw GitHub job binding differs"
        )

    materialization = _exact_keys(
        result["materialization"],
        {"candidate_archive_ref", "manifest_ref", "receipt_ref"},
        "raw materialization",
    )
    normalized_materialization = {
        "manifest_ref": _validate_runtime_reference(
            materialization["manifest_ref"],
            f"commands/{raw_directory_name}/materialization/manifest.json",
            "materialization manifest",
        ),
        "receipt_ref": _validate_runtime_reference(
            materialization["receipt_ref"],
            f"commands/{raw_directory_name}/materialization/receipt.json",
            "materialization receipt",
        ),
    }
    candidate_archive_ref = _validate_runtime_reference(
        materialization["candidate_archive_ref"],
        None,
        "materialization candidate archive",
    )
    archive_match = re.fullmatch(
        (
            rf"commands/{re.escape(raw_directory_name)}/materialization/"
            r"candidate-sha256-([0-9a-f]{64})\.tar"
        ),
        candidate_archive_ref["path"],
    )
    if (
        archive_match is None
        or archive_match.group(1) != candidate_archive_ref["sha256"]
    ):
        raise WitnessValidationError(
            "MATERIALIZATION_INVALID",
            "candidate archive path is not content addressed",
        )
    normalized_materialization["candidate_archive_ref"] = candidate_archive_ref

    execution = _exact_keys(
        result["execution"],
        {
            "exit_code",
            "output_limited",
            "report_totals",
            "status",
            "stderr",
            "stdout",
            "timed_out",
        },
        "raw execution",
    )
    status_value = execution["status"]
    if status_value not in {"PASSED", "FAILED"}:
        raise WitnessValidationError(
            "RAW_EXECUTION_INVALID", "execution status is invalid"
        )
    exit_code = _strict_int(execution["exit_code"], "execution.exit_code")
    timed_out = _strict_bool(execution["timed_out"], "execution.timed_out")
    output_limited = _strict_bool(
        execution["output_limited"], "execution.output_limited"
    )
    if (status_value == "PASSED") != (
        exit_code == 0 and not timed_out and not output_limited
    ):
        raise WitnessValidationError(
            "RAW_EXECUTION_INVALID", "execution terminal fields disagree"
        )
    stdout = _validate_output_summary(execution["stdout"], "execution.stdout")
    stderr = _validate_output_summary(execution["stderr"], "execution.stderr")

    reports = result["reports"]
    if not isinstance(reports, list) or len(reports) > MAX_REPORT_FILES:
        raise WitnessValidationError("RAW_REPORT_INVALID", "raw report list is invalid")
    normalized_reports: list[dict[str, Any]] = []
    seen_paths: set[str] = set()
    for index, report in enumerate(reports):
        item = _exact_keys(
            report, {"bytes", "format", "path", "sha256"}, f"report {index}"
        )
        path = _safe_relative_path(item["path"], f"report {index} path")
        path_parts = PurePosixPath(path).parts
        if (
            len(path_parts) != 2
            or path_parts[0] != "reports"
            or not SAFE_FILENAME.fullmatch(path_parts[1])
            or path.casefold() in seen_paths
        ):
            raise WitnessValidationError(
                "RAW_REPORT_INVALID", "report path topology differs"
            )
        seen_paths.add(path.casefold())
        if item["format"] != "JUNIT_XML":
            raise WitnessValidationError("RAW_REPORT_INVALID", "report format differs")
        normalized_reports.append(
            {
                "bytes": _strict_int(item["bytes"], f"report {index} bytes", minimum=1),
                "format": "JUNIT_XML",
                "path": path,
                "sha256": _assert_sha256(item["sha256"], f"report {index} sha256"),
            }
        )
    if status_value == "PASSED" and not normalized_reports:
        raise WitnessValidationError(
            "RAW_REPORT_INVALID", "passing command omitted JUnit"
        )
    expected_artifacts = command["report"]["expected_artifacts"]
    expected_filenames = [item["filename"] for item in expected_artifacts]
    actual_filenames = [PurePosixPath(item["path"]).name for item in normalized_reports]
    if status_value == "PASSED" and actual_filenames != expected_filenames:
        raise WitnessValidationError(
            "RAW_REPORT_INVALID",
            "passing command artifact set differs from contract",
        )
    if status_value == "FAILED" and any(
        filename not in expected_filenames for filename in actual_filenames
    ):
        raise WitnessValidationError(
            "RAW_REPORT_INVALID", "failed command supplied an undeclared artifact"
        )

    totals = _exact_keys(
        execution["report_totals"],
        {"errors", "failures", "skipped", "suite_ids", "testcase_ids", "tests"},
        "report totals",
    )
    normalized_totals = {
        "errors": _strict_int(totals["errors"], "report totals errors"),
        "failures": _strict_int(totals["failures"], "report totals failures"),
        "skipped": _strict_int(totals["skipped"], "report totals skipped"),
        "suite_ids": totals["suite_ids"],
        "testcase_ids": totals["testcase_ids"],
        "tests": _strict_int(totals["tests"], "report totals tests"),
    }
    for field in ("suite_ids", "testcase_ids"):
        values = normalized_totals[field]
        if (
            not isinstance(values, list)
            or any(not isinstance(value, str) or not value for value in values)
            or values != sorted(set(values))
        ):
            raise WitnessValidationError(
                "RAW_REPORT_INVALID", f"{field} is not a sorted unique list"
            )
    if len(normalized_totals["testcase_ids"]) != normalized_totals["tests"]:
        raise WitnessValidationError(
            "RAW_REPORT_INVALID", "testcase identity count differs from report total"
        )
    if status_value == "PASSED":
        expected_test_count = sum(
            item["test_count"] for item in command["report"]["expected_artifacts"]
        )
        expected_suites = sorted(
            {item["suite_name"] for item in command["report"]["expected_artifacts"]}
        )
        if (
            normalized_totals["tests"] != expected_test_count
            or normalized_totals["suite_ids"] != expected_suites
        ):
            raise WitnessValidationError(
                "JUNIT_SUITE_MISMATCH",
                "aggregate JUnit identity or test count differs from contract",
            )

    runtime = result["runtime"]
    if command["backend_kind"] == command_contract.STATIC_BACKEND_KIND:
        runtime_data = _exact_keys(
            runtime,
            {
                "artifact_transport_receipt_ref",
                "build_observation_receipt_ref",
                "dispatch_ref",
                "observer_docker_archive_ref",
                "observer_oci_archive_ref",
                "producer_docker_archive_ref",
                "producer_oci_archive_ref",
                "runtime_build_receipt_ref",
                "wheelhouse_manifest_ref",
            },
            "raw runtime",
        )
        expected_reference_paths = {
            "artifact_transport_receipt_ref": (
                f"commands/{raw_directory_name}/runtime/artifact-transport-receipt.json"
            ),
            "build_observation_receipt_ref": (
                "shared-runtime/observer/build-observation-receipt.json"
            ),
            "dispatch_ref": f"commands/{raw_directory_name}/runtime/dispatch.json",
            "runtime_build_receipt_ref": (
                "shared-runtime/producer/runtime-build-receipt.json"
            ),
            "wheelhouse_manifest_ref": (
                "shared-runtime/producer/wheelhouse-manifest.json"
            ),
        }
        normalized_runtime = {}
        for name, expected_path in expected_reference_paths.items():
            normalized_runtime[name] = _validate_runtime_reference(
                runtime_data[name], expected_path, name
            )
        for name, partition, archive_kind in (
            ("producer_oci_archive_ref", "producer", "oci"),
            ("observer_oci_archive_ref", "observer", "oci"),
            ("producer_docker_archive_ref", "producer", "docker"),
            ("observer_docker_archive_ref", "observer", "docker"),
        ):
            archive_reference = _validate_runtime_reference(
                runtime_data[name], None, name
            )
            match = re.fullmatch(
                rf"shared-runtime/{partition}/{archive_kind}/"
                r"sha256-([0-9a-f]{64})\.tar",
                archive_reference["path"],
            )
            if match is None or match.group(1) != archive_reference["sha256"]:
                raise WitnessValidationError(
                    "RUNTIME_RECEIPT_INVALID",
                    f"{partition} {archive_kind} archive path is not content addressed",
                )
            normalized_runtime[name] = archive_reference
    else:
        if runtime is not None:
            raise WitnessValidationError(
                "RUNTIME_RECEIPT_INVALID", "Maven command has runtime receipt"
            )
        normalized_runtime = None

    return {
        "attempt_id": attempt_id,
        "authority": RAW_AUTHORITY,
        "candidate": expected_candidate,
        "command": expected_command,
        "execution": {
            "exit_code": exit_code,
            "output_limited": output_limited,
            "report_totals": normalized_totals,
            "status": status_value,
            "stderr": stderr,
            "stdout": stdout,
            "timed_out": timed_out,
        },
        "github": dict(raw_github),
        "materialization": normalized_materialization,
        "reports": normalized_reports,
        "runtime": normalized_runtime,
        "schema_version": RAW_SCHEMA_VERSION,
    }


def _assert_reference_matches_file(
    reference: Mapping[str, Any],
    authenticated: AuthenticatedFile | AuthenticatedDigestFile,
    context: str,
) -> None:
    byte_count = (
        authenticated.bytes
        if isinstance(authenticated, AuthenticatedDigestFile)
        else len(authenticated.payload)
    )
    if reference["bytes"] != byte_count or reference["sha256"] != authenticated.sha256:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", f"{context} reference differs from file"
        )


def _parse_runtime_receipt_bytes(payload: bytes, context: str) -> dict[str, Any]:
    try:
        return runtime_policy.parse_receipt_json_bytes(payload)
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", f"{context}: {exception}"
        ) from exception


def _parse_materialization_manifest_bytes(
    payload: bytes, context: str
) -> list[dict[str, Any]]:
    try:
        return runtime_policy.parse_materialization_manifest_bytes(payload)
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise WitnessValidationError(
            "MATERIALIZATION_INVALID", f"{context}: {exception}"
        ) from exception


def _read_shared_runtime(root: Path) -> SharedRuntimeEvidence:
    if sorted(path.name for path in root.iterdir()) != ["observer", "producer"]:
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "shared runtime partitions differ"
        )
    producer_dir = root / "producer"
    observer_dir = root / "observer"
    if any(
        not path.is_dir() or path.is_symlink() for path in (producer_dir, observer_dir)
    ):
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "shared runtime partition is invalid"
        )

    producer_names = sorted(path.name for path in producer_dir.iterdir())
    observer_names = sorted(path.name for path in observer_dir.iterdir())
    if producer_names != [
        "docker",
        "oci",
        "runtime-build-receipt.json",
        "wheelhouse",
        "wheelhouse-manifest.json",
    ] or observer_names != ["build-observation-receipt.json", "docker", "oci"]:
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "shared runtime file set differs"
        )
    producer_oci_dir = producer_dir / "oci"
    producer_docker_dir = producer_dir / "docker"
    observer_oci_dir = observer_dir / "oci"
    observer_docker_dir = observer_dir / "docker"
    wheelhouse_dir = producer_dir / "wheelhouse"
    if any(
        not path.is_dir() or path.is_symlink()
        for path in (
            producer_oci_dir,
            producer_docker_dir,
            observer_oci_dir,
            observer_docker_dir,
            wheelhouse_dir,
        )
    ):
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "shared runtime content directory is invalid"
        )

    def runtime_archive(directory: Path, context: str) -> Path:
        names = sorted(path.name for path in directory.iterdir())
        if (
            len(names) != 1
            or re.fullmatch(r"sha256-[0-9a-f]{64}\.tar", names[0]) is None
        ):
            raise WitnessValidationError(
                "RAW_TOPOLOGY_INVALID", f"{context} archive set differs"
            )
        return directory / names[0]

    producer_oci_archive_path = runtime_archive(producer_oci_dir, "producer OCI")
    producer_docker_archive_path = runtime_archive(
        producer_docker_dir, "producer Docker"
    )
    observer_oci_archive_path = runtime_archive(observer_oci_dir, "observer OCI")
    observer_docker_archive_path = runtime_archive(
        observer_docker_dir, "observer Docker"
    )
    build_file = _read_authenticated_file(
        producer_dir / "runtime-build-receipt.json",
        "runtime build receipt",
        runtime_policy.MAX_RECEIPT_BYTES,
    )
    observation_file = _read_authenticated_file(
        observer_dir / "build-observation-receipt.json",
        "build observation receipt",
        runtime_policy.MAX_RECEIPT_BYTES,
    )
    wheelhouse_manifest_file = _read_authenticated_file(
        producer_dir / "wheelhouse-manifest.json",
        "wheelhouse manifest",
        runtime_policy.MAX_MANIFEST_BYTES,
    )
    try:
        wheelhouse_manifest = runtime_policy.parse_wheelhouse_manifest_bytes(
            wheelhouse_manifest_file.payload
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", f"wheelhouse manifest: {exception}"
        ) from exception
    if len(wheelhouse_manifest) != EXPECTED_WHEELHOUSE_FILES:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", "wheelhouse manifest count differs"
        )
    wheel_names = sorted(path.name for path in wheelhouse_dir.iterdir())
    if wheel_names != sorted(entry["filename"] for entry in wheelhouse_manifest):
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "wheelhouse file set differs from its manifest"
        )
    wheel_files = tuple(
        _capture_verified_digest_file(
            wheelhouse_dir / entry["filename"],
            expected_bytes=entry["bytes"],
            expected_sha256=entry["sha256"],
            context=f"wheelhouse {entry['filename']}",
        )
        for entry in wheelhouse_manifest
    )
    return SharedRuntimeEvidence(
        root=root,
        build_file=build_file,
        build_receipt=_parse_runtime_receipt_bytes(
            build_file.payload, "runtime build receipt"
        ),
        producer_oci_archive_path=producer_oci_archive_path,
        producer_docker_archive_path=producer_docker_archive_path,
        wheelhouse_manifest_file=wheelhouse_manifest_file,
        wheelhouse_manifest=wheelhouse_manifest,
        wheel_files=wheel_files,
        observation_file=observation_file,
        observation_receipt=_parse_runtime_receipt_bytes(
            observation_file.payload, "build observation receipt"
        ),
        observer_oci_archive_path=observer_oci_archive_path,
        observer_docker_archive_path=observer_docker_archive_path,
    )


def _capture_shared_runtime_archives(
    shared: SharedRuntimeEvidence,
) -> dict[str, AuthenticatedDigestFile]:
    specifications = {
        "producer_oci": (
            shared.producer_oci_archive_path,
            "producer runtime OCI archive",
        ),
        "producer_docker": (
            shared.producer_docker_archive_path,
            "producer runtime Docker archive",
        ),
        "observer_oci": (
            shared.observer_oci_archive_path,
            "observer runtime OCI archive",
        ),
        "observer_docker": (
            shared.observer_docker_archive_path,
            "observer runtime Docker archive",
        ),
    }
    archives: dict[str, AuthenticatedDigestFile] = {}
    identities: set[tuple[int, int, int, int, int]] = set()
    for name, (path, context) in specifications.items():
        expected_sha256 = shared.observation_receipt[f"{name}_archive_sha256"]
        if path.name != f"sha256-{expected_sha256}.tar":
            raise WitnessValidationError(
                "RUNTIME_RECEIPT_INVALID",
                f"{context} digest differs from its content-addressed path",
            )
        archive = _capture_verified_digest_file(
            path,
            expected_bytes=shared.observation_receipt[f"{name}_archive_bytes"],
            expected_sha256=expected_sha256,
            context=context,
        )
        if archive.identity in identities:
            raise WitnessValidationError(
                "FILE_ALIAS_INVALID", "runtime archives are physically aliased"
            )
        identities.add(archive.identity)
        archives[name] = archive
    return archives


def _expected_materialization_binding(
    candidate: Mapping[str, Any],
    scope: Mapping[str, Any],
    closure_kind: str,
    candidate_archive_ref: Mapping[str, Any],
) -> tuple[dict[str, Any], Mapping[str, Any]]:
    inventories = scope["materialization_inventories"]
    inventory = inventories[closure_kind]
    return (
        {
            "accepted_entry_sha": candidate["accepted_entry_sha"],
            "candidate_archive_bytes": candidate_archive_ref["bytes"],
            "candidate_archive_entry_count": inventory["file_count"],
            "candidate_archive_format": runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
            "candidate_archive_sha256": candidate_archive_ref["sha256"],
            "candidate_sha": candidate["candidate_sha"],
            "candidate_tree_sha": candidate["candidate_tree_sha"],
            "closure_kind": closure_kind,
            "derived_inventory_sha256": scope["derived_inventory_sha256"],
            "manifest_file_count": inventory["file_count"],
            "manifest_sha256": inventory["manifest_sha256"],
            "manifest_total_bytes": inventory["total_bytes"],
        },
        inventory,
    )


def _assert_distinct_materialization(
    receipt: Mapping[str, Any],
    archive: Mapping[str, Any],
    seen: Mapping[str, set[Any]],
    closure_summaries: dict[str, tuple[Any, ...]],
) -> None:
    archive_path = os.path.normcase(os.path.normpath(archive["archive_path"]))
    archive_identity = tuple(archive["archive_physical_identity"])
    values: dict[str, Any] = {
        "archive_path": archive_path,
        "receipt_sha256": receipt["receipt_sha256"],
    }
    identity_kind = archive["physical_identity_kind"]
    if identity_kind == "DEVICE_INODE":
        values["archive_device_inode"] = archive_identity
    elif identity_kind == "CANONICAL_PATH_SINGLE_LINK":
        values["archive_fallback_identity"] = (archive_path, archive_identity)
    else:
        raise WitnessValidationError(
            "MATERIALIZATION_INVALID", "candidate archive identity kind differs"
        )
    created_nonce = receipt["created_nonce"]
    verified_nonce = receipt["verified_nonce"]
    if created_nonce == verified_nonce:
        raise WitnessValidationError(
            "MATERIALIZATION_INVALID", "materialization nonces are not distinct"
        )
    for field, value in values.items():
        if value in seen[field]:
            raise WitnessValidationError(
                "MATERIALIZATION_INVALID",
                f"materialization {field} was reused",
            )
    if created_nonce in seen["nonce"] or verified_nonce in seen["nonce"]:
        raise WitnessValidationError(
            "MATERIALIZATION_INVALID", "materialization nonce was reused"
        )
    closure_kind = receipt["closure_kind"]
    summary = (
        archive["archive_sha256"],
        archive["archive_bytes"],
        archive["archive_entry_count"],
        archive["archive_format"],
    )
    existing = closure_summaries.setdefault(closure_kind, summary)
    if existing != summary:
        raise WitnessValidationError(
            "MATERIALIZATION_INVALID",
            "candidate archives for one closure differ",
        )
    for field, value in values.items():
        seen[field].add(value)
    seen["nonce"].update((created_nonce, verified_nonce))


def _expected_run_binding(github: Mapping[str, str]) -> dict[str, Any]:
    return {
        "caller_workflow_ref": github["workflow_ref"],
        "caller_workflow_sha": github["workflow_sha"],
        "repository": github["repository"],
        "repository_id": github["repository_id"],
        "run_attempt": int(github["run_attempt"]),
        "run_id": github["run_id"],
        "runner_arch": github["runner_arch"],
        "runner_environment": github["runner_environment"],
        "runner_os": github["runner_os"],
        "trusted_workflow_path": github["job_workflow_file_path"],
        "trusted_workflow_ref": github["job_workflow_ref"],
        "trusted_workflow_repository": github["job_workflow_repository"],
        "trusted_workflow_sha": github["job_workflow_sha"],
    }


def _github_job_identity(github: Mapping[str, str], job_name: str) -> dict[str, Any]:
    return {
        **_expected_run_binding(github),
        "job_name": job_name,
        "schema_version": runtime_policy.GITHUB_JOB_IDENTITY_SCHEMA_VERSION,
    }


def _expected_observation_binding(
    shared: SharedRuntimeEvidence, github: Mapping[str, str]
) -> dict[str, Any]:
    observation = shared.observation_receipt
    observer_identity = _github_job_identity(github, runtime_policy.OBSERVER_JOB_NAME)
    try:
        archive_facts = {
            "producer_oci": shared.producer_oci_archive_path.lstat().st_size,
            "producer_docker": shared.producer_docker_archive_path.lstat().st_size,
            "observer_oci": shared.observer_oci_archive_path.lstat().st_size,
            "observer_docker": shared.observer_docker_archive_path.lstat().st_size,
        }
    except OSError as exception:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", "runtime archive disappeared before validation"
        ) from exception
    expected = {
        key: copy.deepcopy(observation[key])
        for key in (
            "base_image_inspect_projection",
            "base_image_inspect_projection_sha256",
            "build_provenance",
            "build_provenance_sha256",
            "observer_build_parameters",
            "observer_build_parameters_sha256",
            "observer_image_inspect_projection",
            "observer_image_inspect_projection_sha256",
            "producer_image_inspect_projection",
            "producer_image_inspect_projection_sha256",
        )
    }
    expected.update(
        {
            "observer_job_identity": observer_identity,
            "observer_job_identity_sha256": runtime_policy.canonical_sha256(
                observer_identity
            ),
            **{
                f"{name}_archive_bytes": byte_count
                for name, byte_count in archive_facts.items()
            },
            **{
                f"{name}_archive_sha256": getattr(shared, f"{name}_archive_path")
                .name.removeprefix("sha256-")
                .removesuffix(".tar")
                for name in archive_facts
            },
            "source_build_receipt_sha256": runtime_policy.canonical_receipt_sha256(
                shared.build_receipt
            ),
            "wheelhouse_manifest": copy.deepcopy(shared.wheelhouse_manifest),
            "wheelhouse_manifest_sha256": runtime_policy.canonical_sha256(
                shared.wheelhouse_manifest
            ),
        }
    )
    return expected


def _verify_shared_runtime(
    shared: SharedRuntimeEvidence,
    *,
    github: Mapping[str, str],
    policy: Mapping[str, Any],
    contract: Mapping[str, Any],
) -> object:
    expected_builder = _github_job_identity(github, runtime_policy.BUILD_JOB_NAME)
    if shared.build_receipt.get("builder_job_identity") != expected_builder:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", "runtime builder GitHub identity differs"
        )
    if shared.observation_receipt.get("observer_job_identity") != _github_job_identity(
        github, runtime_policy.OBSERVER_JOB_NAME
    ):
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", "runtime observer GitHub identity differs"
        )
    try:
        expected_observation = _expected_observation_binding(shared, github)
        return runtime_policy.verify_shared_runtime_receipts(
            shared.build_receipt,
            shared.observation_receipt,
            expected_observation,
            expected_run_binding=_expected_run_binding(github),
            expected_builder_job_identity=expected_builder,
            producer_oci_archive_path=shared.producer_oci_archive_path,
            producer_docker_archive_path=shared.producer_docker_archive_path,
            observer_oci_archive_path=shared.observer_oci_archive_path,
            observer_docker_archive_path=shared.observer_docker_archive_path,
            wheelhouse_root=shared.root / "producer" / "wheelhouse",
            policy=policy,
            validated_command_contract=contract,
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", f"shared runtime: {exception}"
        ) from exception
    except (KeyError, TypeError, ValueError) as exception:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", "shared runtime receipt shape differs"
        ) from exception


def _static_artifact_name(command_id: str, github: Mapping[str, str]) -> str:
    try:
        prefix = runtime_policy.STATIC_COMMAND_ARTIFACT_PREFIXES[command_id]
    except KeyError as exception:
        raise WitnessValidationError(
            "RUNTIME_RECEIPT_INVALID", "runtime artifact command is not static"
        ) from exception
    return f"{prefix}-{github['run_id']}-{github['run_attempt']}"


def _read_raw_prefix(
    root: Path,
    *,
    contract: Mapping[str, Any],
    attempt_id: str,
    candidate: Mapping[str, Any],
    scope: Mapping[str, Any],
    github: Mapping[str, str],
    trusted_sha: str,
    trusted_transition: Mapping[str, Any],
    trusted_transition_sha256: str,
    policy: Mapping[str, Any],
) -> tuple[list[dict[str, Any]], list[AuthenticatedInput], dict[str, bytes]]:
    initial_tree = _assert_directory_tree(root, "raw artifacts directory")
    if sorted(path.name for path in root.iterdir()) != ["commands", "shared-runtime"]:
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "raw artifact root fields differ"
        )
    command_root = root / "commands"
    shared_runtime_root = root / "shared-runtime"
    if (
        not command_root.is_dir()
        or command_root.is_symlink()
        or not shared_runtime_root.is_dir()
        or shared_runtime_root.is_symlink()
    ):
        raise WitnessValidationError(
            "RAW_TOPOLOGY_INVALID", "raw root partitions are invalid"
        )
    children = sorted(path.name for path in command_root.iterdir())
    expected_names = [
        f"{index:03d}-{command_id}"
        for index, command_id in enumerate(command_contract.COMMAND_ORDER)
    ]
    if not children or children != expected_names[: len(children)]:
        raise WitnessValidationError(
            "STOP_PREFIX_INVALID", "raw command directories are not an exact prefix"
        )
    shared_runtime = _read_shared_runtime(shared_runtime_root)
    validated_shared_runtime = _verify_shared_runtime(
        shared_runtime, github=github, policy=policy, contract=contract
    )
    runtime_archives = _capture_shared_runtime_archives(shared_runtime)
    producer_oci_file = runtime_archives["producer_oci"]
    producer_docker_file = runtime_archives["producer_docker"]
    observer_oci_file = runtime_archives["observer_oci"]
    observer_docker_file = runtime_archives["observer_docker"]

    authenticated: list[AuthenticatedInput] = [
        shared_runtime.build_file,
        shared_runtime.observation_file,
        shared_runtime.wheelhouse_manifest_file,
        *shared_runtime.wheel_files,
        producer_oci_file,
        producer_docker_file,
        observer_oci_file,
        observer_docker_file,
    ]
    archive_files: dict[str, bytes] = {}
    archive_files["runtime/shared/runtime-build-receipt.json"] = (
        _canonical_bytes(shared_runtime.build_receipt) + b"\n"
    )
    archive_files["runtime/shared/build-observation.json"] = (
        _canonical_bytes(shared_runtime.observation_receipt) + b"\n"
    )
    runtime_archive_files = {
        "producer_oci_archive": producer_oci_file,
        "producer_docker_archive": producer_docker_file,
        "observer_oci_archive": observer_oci_file,
        "observer_docker_archive": observer_docker_file,
    }
    archive_files["runtime/shared/archive-index.json"] = (
        _canonical_bytes(
            {
                "archives": {
                    name: {
                        "bytes": item.bytes,
                        "path": (
                            f"shared-runtime/{name.split('_', 1)[0]}/"
                            f"{name.split('_', 2)[1]}/sha256-{item.sha256}.tar"
                        ),
                        "physical_identity": list(item.identity),
                        "sha256": item.sha256,
                    }
                    for name, item in runtime_archive_files.items()
                },
                "schema_version": "phase8-runtime-archive-index.v1",
            }
        )
        + b"\n"
    )
    results: list[dict[str, Any]] = []
    materialization_seen: dict[str, set[Any]] = {
        "archive_device_inode": set(),
        "archive_fallback_identity": set(),
        "archive_path": set(),
        "nonce": set(),
        "receipt_sha256": set(),
    }
    closure_archive_summaries: dict[str, tuple[Any, ...]] = {}
    java_materialization_executions: list[dict[str, Any]] = []
    static_executions: list[dict[str, Any]] = []
    candidate_archive_total_bytes = 0
    total_report_bytes = 0
    expected_run_binding = _expected_run_binding(github)
    commands_by_id = {command["id"]: command for command in contract["commands"]}
    for order, directory_name in enumerate(children):
        command_id = command_contract.COMMAND_ORDER[order]
        command_dir = command_root / directory_name
        if not command_dir.is_dir() or command_dir.is_symlink():
            raise WitnessValidationError(
                "RAW_TOPOLOGY_INVALID", "command artifact is not a directory"
            )
        result_file = _read_authenticated_file(
            command_dir / "result.json", "raw result", MAX_JSON_BYTES
        )
        authenticated.append(result_file)
        parsed = _bounded_json(result_file.payload, "raw result")
        normalized = _validate_raw_result(
            parsed,
            command=commands_by_id[command_id],
            command_contract_payload_sha256=contract["self_seal"]["payload_sha256"],
            raw_directory_name=directory_name,
            order=order,
            attempt_id=attempt_id,
            candidate=candidate,
            scope=scope,
            github=github,
            trusted_sha=trusted_sha,
            trusted_transition=trusted_transition,
            trusted_transition_sha256=trusted_transition_sha256,
        )
        materialization_refs = normalized["materialization"]
        materialization_dir = command_dir / "materialization"
        candidate_archive_ref = materialization_refs["candidate_archive_ref"]
        candidate_archive_total_bytes += candidate_archive_ref["bytes"]
        if candidate_archive_total_bytes > MAX_CANDIDATE_ARCHIVE_TOTAL_BYTES:
            raise WitnessValidationError(
                "MATERIALIZATION_INVALID",
                "candidate archive aggregate exceeds its byte limit",
            )
        candidate_archive_path = root.joinpath(
            *PurePosixPath(candidate_archive_ref["path"]).parts
        )
        manifest_file = _read_authenticated_file(
            materialization_dir / "manifest.json",
            f"{command_id} materialization manifest",
            runtime_policy.MAX_MANIFEST_BYTES,
        )
        receipt_file = _read_authenticated_file(
            materialization_dir / "receipt.json",
            f"{command_id} materialization receipt",
            runtime_policy.MAX_RECEIPT_BYTES,
        )
        _assert_reference_matches_file(
            normalized["materialization"]["manifest_ref"],
            manifest_file,
            f"{command_id} materialization manifest",
        )
        _assert_reference_matches_file(
            normalized["materialization"]["receipt_ref"],
            receipt_file,
            f"{command_id} materialization receipt",
        )
        materialization_manifest = _parse_materialization_manifest_bytes(
            manifest_file.payload, f"{command_id} materialization manifest"
        )
        materialization_receipt = _parse_runtime_receipt_bytes(
            receipt_file.payload, f"{command_id} materialization receipt"
        )
        authenticated.extend((manifest_file, receipt_file))
        closure_kind = (
            candidate_scope.FULL_REPOSITORY
            if commands_by_id[command_id]["backend_kind"]
            == command_contract.STATIC_BACKEND_KIND
            else candidate_scope.JAVA_SERVICE_ONLY
        )
        expected_materialization, scope_inventory = _expected_materialization_binding(
            candidate, scope, closure_kind, candidate_archive_ref
        )
        try:
            validated_materialization, _, archive_evidence = (
                runtime_policy.verify_materialization_receipt_offline(
                    materialization_receipt,
                    materialization_manifest,
                    expected_materialization,
                    scope_inventory,
                    expected_run_binding,
                    candidate_archive_path,
                )
            )
        except runtime_policy.RuntimePolicyValidationError as exception:
            raise WitnessValidationError(
                "MATERIALIZATION_INVALID", f"{command_id}: {exception}"
            ) from exception
        if (
            archive_evidence["archive_path"] != os.fspath(candidate_archive_path)
            or archive_evidence["archive_format"]
            != runtime_policy.CANDIDATE_ARCHIVE_FORMAT
            or archive_evidence["archive_entry_count"] != scope_inventory["file_count"]
        ):
            raise WitnessValidationError(
                "MATERIALIZATION_INVALID",
                "candidate archive evidence differs from its fixed path or scope",
            )
        candidate_archive_file = _capture_verified_digest_file(
            candidate_archive_path,
            expected_bytes=archive_evidence["archive_bytes"],
            expected_sha256=archive_evidence["archive_sha256"],
            context=f"{command_id} candidate archive",
        )
        _assert_reference_matches_file(
            candidate_archive_ref,
            candidate_archive_file,
            f"{command_id} candidate archive",
        )
        authenticated.append(candidate_archive_file)
        _assert_distinct_materialization(
            validated_materialization,
            archive_evidence,
            materialization_seen,
            closure_archive_summaries,
        )
        materialization_execution = {
            "candidate_archive_path": candidate_archive_path,
            "expected_candidate_binding": expected_materialization,
            "expected_scope_inventory": scope_inventory,
            "materialization_manifest": materialization_manifest,
            "materialization_receipt": validated_materialization,
        }
        normalized["materialization"] = {
            "candidate_archive": archive_evidence,
            "closure_kind": closure_kind,
            "manifest": {
                "file_count": validated_materialization["manifest_file_count"],
                "inventory_sha256": validated_materialization["manifest_sha256"],
                "payload_bytes": len(manifest_file.payload),
                "payload_sha256": manifest_file.sha256,
                "total_bytes": validated_materialization["manifest_total_bytes"],
            },
            "receipt": validated_materialization,
        }
        runtime_refs = normalized["runtime"]
        if runtime_refs is None:
            java_materialization_executions.append(materialization_execution)
        artifact_transport_receipt: dict[str, Any] | None = None
        dispatch: dict[str, Any] | None = None
        report_facts: list[JunitFacts] = []
        observed_report_inventory: list[dict[str, Any]] = []
        archive_junit_members: list[dict[str, Any]] = []
        artifact_specs = {
            item["filename"]: item
            for item in commands_by_id[command_id]["report"]["expected_artifacts"]
        }
        declared_paths = {item["path"] for item in normalized["reports"]}
        runtime_paths: set[str] = set()
        command_prefix = f"commands/{directory_name}/"
        runtime_paths.update(
            reference["path"].removeprefix(command_prefix)
            for reference in materialization_refs.values()
        )
        if normalized["runtime"] is not None:
            runtime_paths.update(
                reference["path"].removeprefix(command_prefix)
                for key, reference in normalized["runtime"].items()
                if key
                in {
                    "artifact_transport_receipt_ref",
                    "dispatch_ref",
                }
                and reference["path"].startswith(command_prefix)
            )
        actual_relative = {
            path.relative_to(command_dir).as_posix()
            for path in command_dir.rglob("*")
            if path.is_file()
        }
        if actual_relative != {"result.json", *declared_paths, *runtime_paths}:
            raise WitnessValidationError(
                "RAW_TOPOLOGY_INVALID", "raw artifact file set differs"
            )
        actual_directories = {
            path.relative_to(command_dir).as_posix()
            for path in command_dir.rglob("*")
            if path.is_dir()
        }
        expected_directories = {"materialization"}
        if declared_paths:
            expected_directories.add("reports")
        if normalized["runtime"] is not None:
            expected_directories.add("runtime")
        if actual_directories != expected_directories:
            raise WitnessValidationError(
                "RAW_TOPOLOGY_INVALID", "raw artifact directory set differs"
            )
        if runtime_refs is not None:
            runtime_dir = command_dir / "runtime"
            transport_file = _read_authenticated_file(
                runtime_dir / "artifact-transport-receipt.json",
                f"{command_id} artifact transport receipt",
                runtime_policy.MAX_RECEIPT_BYTES,
            )
            dispatch_file = _read_authenticated_file(
                runtime_dir / "dispatch.json",
                f"{command_id} runtime dispatch",
                MAX_JSON_BYTES,
            )
            _assert_reference_matches_file(
                runtime_refs["artifact_transport_receipt_ref"],
                transport_file,
                f"{command_id} artifact transport receipt",
            )
            _assert_reference_matches_file(
                runtime_refs["dispatch_ref"],
                dispatch_file,
                f"{command_id} runtime dispatch",
            )
            _assert_reference_matches_file(
                runtime_refs["runtime_build_receipt_ref"],
                shared_runtime.build_file,
                "shared runtime build receipt",
            )
            _assert_reference_matches_file(
                runtime_refs["build_observation_receipt_ref"],
                shared_runtime.observation_file,
                "shared build observation receipt",
            )
            _assert_reference_matches_file(
                runtime_refs["wheelhouse_manifest_ref"],
                shared_runtime.wheelhouse_manifest_file,
                "shared wheelhouse manifest",
            )
            for name, authenticated_archive in (
                ("producer_oci_archive_ref", producer_oci_file),
                ("producer_docker_archive_ref", producer_docker_file),
                ("observer_oci_archive_ref", observer_oci_file),
                ("observer_docker_archive_ref", observer_docker_file),
            ):
                _assert_reference_matches_file(
                    runtime_refs[name], authenticated_archive, name
                )
            artifact_transport_receipt = _parse_runtime_receipt_bytes(
                transport_file.payload, f"{command_id} artifact transport receipt"
            )
            dispatch = _bounded_json(
                dispatch_file.payload, f"{command_id} runtime dispatch"
            )
            authenticated.extend((transport_file, dispatch_file))
        for report_order, report in enumerate(normalized["reports"]):
            report_file = _read_authenticated_file(
                command_dir / report["path"], f"JUnit {command_id}", MAX_REPORT_BYTES
            )
            if (
                report_file.sha256 != report["sha256"]
                or len(report_file.payload) != report["bytes"]
            ):
                raise WitnessValidationError(
                    "RAW_REPORT_INVALID", "JUnit binding differs"
                )
            authenticated.append(report_file)
            total_report_bytes += len(report_file.payload)
            if total_report_bytes > MAX_REPORT_TOTAL_BYTES:
                raise WitnessValidationError(
                    "RAW_REPORT_INVALID", "aggregate JUnit size exceeded"
                )
            report_facts.append(
                _parse_junit(report_file.payload, f"JUnit {command_id}")
            )
            filename = PurePosixPath(report["path"]).name
            artifact_spec = artifact_specs[filename]
            observed_facts = report_facts[-1]
            if observed_facts.tests != artifact_spec[
                "test_count"
            ] or observed_facts.suite_ids != (artifact_spec["suite_name"],):
                raise WitnessValidationError(
                    "JUNIT_SUITE_MISMATCH",
                    "JUnit suite identity or test count differs from the fixed contract",
                )
            observed_report_inventory.append(
                {
                    "archive_path": artifact_spec["archive_path"],
                    "filename": filename,
                    "format": "JUNIT_XML",
                    "suite_name": observed_facts.suite_ids[0],
                    "test_count": observed_facts.tests,
                }
            )
            if not SAFE_FILENAME.fullmatch(filename):
                raise WitnessValidationError(
                    "RAW_REPORT_INVALID", "JUnit filename is not safe ASCII"
                )
            archive_path = f"commands/{directory_name}/junit/{report_order:03d}.xml"
            if archive_path in archive_files:
                raise WitnessValidationError(
                    "RAW_REPORT_INVALID", "JUnit archive name collides"
                )
            archive_files[archive_path] = report_file.payload
            archive_junit_members.append(
                {
                    "archive_path": artifact_spec["archive_path"],
                    "bytes": len(report_file.payload),
                    "filename": filename,
                    "member_path": archive_path,
                    "sha256": report_file.sha256,
                }
            )
        if normalized["execution"]["status"] == "PASSED":
            try:
                command_contract.validate_report_inventory(
                    command_id, observed_report_inventory
                )
            except command_contract.CommandContractValidationError as exception:
                raise WitnessValidationError(
                    "JUNIT_SUITE_MISMATCH", str(exception)
                ) from exception
        actual_facts = _sum_facts(report_facts)
        if actual_facts.as_dict() != normalized["execution"]["report_totals"]:
            raise WitnessValidationError(
                "RAW_REPORT_INVALID", "JUnit-derived totals differ"
            )
        if actual_facts.tests:
            _assert_suite_selection(commands_by_id[command_id], actual_facts)
        if normalized["execution"]["status"] == "PASSED" and (
            actual_facts.tests == 0
            or actual_facts.failures
            or actual_facts.errors
            or actual_facts.skipped
        ):
            raise WitnessValidationError(
                "RAW_EXECUTION_INVALID", "passing source command is not green"
            )
        if runtime_refs is not None:
            if artifact_transport_receipt is None or dispatch is None:
                raise WitnessValidationError(
                    "RUNTIME_RECEIPT_INVALID", "static runtime evidence is incomplete"
                )
            producer_job_name = runtime_policy.STATIC_COMMAND_JOB_NAMES[command_id]
            producer_identity = _github_job_identity(github, producer_job_name)
            junit_file_index = sorted(
                (
                    {
                        "archive_path": item["archive_path"],
                        "bytes": item["bytes"],
                        "sha256": item["sha256"],
                    }
                    for item in archive_junit_members
                ),
                key=lambda item: item["archive_path"],
            )
            dispatch_sha256 = runtime_policy.canonical_sha256(dispatch)
            expected_transport_binding = {
                "artifact_name": _static_artifact_name(command_id, github),
                "artifact_payload_kind": runtime_policy.ARTIFACT_PAYLOAD_KIND,
                "artifact_payload_sha256": (
                    runtime_policy.canonical_junit_file_index_sha256(junit_file_index)
                ),
                "build_observation_receipt_sha256": (
                    runtime_policy.canonical_receipt_sha256(
                        shared_runtime.observation_receipt
                    )
                ),
                "command_id": command_id,
                "dispatch_sha256": dispatch_sha256,
                "manifest_sha256": validated_materialization["manifest_sha256"],
                "materialization_receipt_sha256": validated_materialization[
                    "receipt_sha256"
                ],
                "oci_archive_sha256": runtime_refs["producer_oci_archive_ref"][
                    "sha256"
                ],
                "producer_job_identity": producer_identity,
                "producer_job_identity_sha256": runtime_policy.canonical_sha256(
                    producer_identity
                ),
                "runtime_build_receipt_sha256": (
                    runtime_policy.canonical_receipt_sha256(
                        shared_runtime.build_receipt
                    )
                ),
            }
            try:
                authorization_sha256, static_archive_evidence = (
                    runtime_policy.verify_static_dispatch_receipts(
                        commands_by_id[command_id],
                        dispatch,
                        policy,
                        materialization_receipt=validated_materialization,
                        materialization_manifest=materialization_manifest,
                        expected_candidate_binding=expected_materialization,
                        expected_scope_inventory=scope_inventory,
                        expected_run_binding=expected_run_binding,
                        candidate_archive_path=candidate_archive_path,
                        validated_command_contract=contract,
                        validated_shared_runtime=validated_shared_runtime,
                        artifact_transport_receipt=artifact_transport_receipt,
                        expected_transport_binding=expected_transport_binding,
                    )
                )
            except runtime_policy.RuntimePolicyValidationError as exception:
                raise WitnessValidationError(
                    "RUNTIME_RECEIPT_INVALID", f"{command_id}: {exception}"
                ) from exception
            if authorization_sha256 != dispatch_sha256:
                raise WitnessValidationError(
                    "RUNTIME_RECEIPT_INVALID", "runtime authorization hash differs"
                )
            if static_archive_evidence != archive_evidence:
                raise WitnessValidationError(
                    "RUNTIME_RECEIPT_INVALID",
                    "static archive evidence differs from materialization validation",
                )
            static_executions.append(
                {
                    "artifact_transport_receipt": artifact_transport_receipt,
                    "candidate_archive_path": candidate_archive_path,
                    "contract_command": commands_by_id[command_id],
                    "dispatch": dispatch,
                    "expected_candidate_binding": expected_materialization,
                    "expected_scope_inventory": scope_inventory,
                    "expected_transport_binding": expected_transport_binding,
                    "materialization_manifest": materialization_manifest,
                    "materialization_receipt": validated_materialization,
                }
            )
            normalized["runtime"] = {
                "artifact_transport_receipt": artifact_transport_receipt,
                "authorization_sha256": authorization_sha256,
                "build_observation_receipt_sha256": (
                    expected_transport_binding["build_observation_receipt_sha256"]
                ),
                "dispatch": dispatch,
                "observer_docker_archive": runtime_refs["observer_docker_archive_ref"],
                "observer_oci_archive": runtime_refs["observer_oci_archive_ref"],
                "producer_docker_archive": runtime_refs["producer_docker_archive_ref"],
                "producer_oci_archive": runtime_refs["producer_oci_archive_ref"],
                "runtime_build_receipt_sha256": expected_transport_binding[
                    "runtime_build_receipt_sha256"
                ],
                "wheelhouse_manifest": runtime_refs["wheelhouse_manifest_ref"],
            }
        if results and results[-1]["execution"]["status"] != "PASSED":
            raise WitnessValidationError(
                "STOP_PREFIX_INVALID", "command exists after terminal failure"
            )
        results.append(normalized)
        archived_report = {
            "command_id": command_id,
            "junit_members": archive_junit_members,
            "order": order,
            "raw_result": normalized,
            "schema_version": "phase8-command-archive-report.v1",
        }
        archive_files[f"commands/{directory_name}/report.json"] = (
            _canonical_bytes(archived_report) + b"\n"
        )
        if normalized["runtime"] is not None:
            archive_files[f"runtime/{directory_name}/receipt.json"] = (
                _canonical_bytes(normalized["runtime"]) + b"\n"
            )

    all_passed = all(result["execution"]["status"] == "PASSED" for result in results)
    if all_passed and len(results) != len(command_contract.COMMAND_ORDER):
        raise WitnessValidationError(
            "STOP_PREFIX_INVALID", "passing prefix omitted the next command"
        )
    if all_passed:
        try:
            validated_static_topology, _, _ = runtime_policy.verify_static_topology_c(
                static_executions,
                expected_run_binding=expected_run_binding,
                policy=policy,
                validated_command_contract=contract,
                validated_shared_runtime=validated_shared_runtime,
            )
            execution_set, execution_set_sha256 = (
                runtime_policy.verify_engineering_materialization_set(
                    java_materialization_executions,
                    expected_run_binding=expected_run_binding,
                    validated_command_contract=contract,
                    validated_static_topology=validated_static_topology,
                )
            )
            execution_set_bytes = runtime_policy.canonical_json_bytes(execution_set)
        except runtime_policy.RuntimePolicyValidationError as exception:
            raise WitnessValidationError(
                "RUNTIME_RECEIPT_INVALID", f"engineering execution set: {exception}"
            ) from exception
        if _sha256(execution_set_bytes) != execution_set_sha256:
            raise WitnessValidationError(
                "RUNTIME_RECEIPT_INVALID",
                "engineering execution set canonical hash differs",
            )
        archive_files["runtime/execution-set.json"] = execution_set_bytes
    if _assert_directory_tree(root, "raw artifacts directory") != initial_tree:
        raise WitnessValidationError(
            "FILE_SUBSTITUTED", "raw artifact tree changed during aggregation"
        )
    return results, authenticated, archive_files


def _atomic_json(path: Path, value: Mapping[str, Any]) -> None:
    payload = _canonical_bytes(value) + b"\n"
    temporary = path.with_name(f".{path.name}.tmp")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    descriptor = os.open(temporary, flags, 0o600)
    try:
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o644)
    finally:
        with contextlib.suppress(FileNotFoundError):
            temporary.unlink()


def _deterministic_tar(path: Path, files: Mapping[str, bytes]) -> str:
    if path.exists() or path.is_symlink():
        raise WitnessValidationError(
            "OUTPUT_NOT_FRESH", "witness archive already exists"
        )
    normalized: dict[str, bytes] = {}
    for name, payload in files.items():
        safe = _safe_relative_path(name, "archive member")
        try:
            encoded_name = safe.encode("ascii", errors="strict")
        except UnicodeEncodeError as exception:
            raise WitnessValidationError(
                "ARCHIVE_INVALID", "archive member name must be ASCII"
            ) from exception
        if len(encoded_name) > 100:
            raise WitnessValidationError(
                "ARCHIVE_INVALID", "archive member name exceeds USTAR short-name limit"
            )
        if safe.casefold() in {item.casefold() for item in normalized}:
            raise WitnessValidationError("ARCHIVE_INVALID", "archive member collision")
        if not isinstance(payload, bytes):
            raise WitnessValidationError(
                "ARCHIVE_INVALID", "archive payload must be bytes"
            )
        if SECRET.search(payload.decode("utf-8", errors="ignore")):
            raise WitnessValidationError(
                "CREDENTIAL_LEAK", "witness archive member contains credentials"
            )
        normalized[safe] = payload
    with (
        path.open("xb") as handle,
        tarfile.open(fileobj=handle, mode="w", format=tarfile.USTAR_FORMAT) as archive,
    ):
        for name in sorted(normalized):
            payload = normalized[name]
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = 0o644
            info.mtime = 0
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            archive.addfile(info, io.BytesIO(payload))
    os.chmod(path, 0o644)
    return _read_authenticated_file(path, "witness archive", 512 * 1024 * 1024).sha256


def _parse_runtime_policy(payload: bytes) -> dict[str, Any]:
    try:
        parsed = runtime_policy.parse_bounded_json_bytes(payload)
        return runtime_policy.validate_runtime_policy(parsed)
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise WitnessValidationError(
            "RUNTIME_POLICY_INVALID", str(exception)
        ) from exception


def _build_result(
    *,
    candidate_dir: Path,
    candidate_sha: str,
    raw_artifacts_dir: Path,
    attempt_id: str,
    trusted_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
) -> tuple[dict[str, Any], list[AuthenticatedInput], dict[str, bytes], dict[str, int]]:
    github = _github_identity(
        candidate_sha,
        attempt_id,
        trusted_workflow_sha=trusted_workflow_sha,
        trusted_workflow_ref=trusted_workflow_ref,
        trusted_workflow_repository=trusted_workflow_repository,
        trusted_workflow_file_path=trusted_workflow_file_path,
    )
    trusted = _trusted_snapshot(trusted_sha)
    checkout = _candidate_checkout_snapshot(candidate_dir, candidate_sha)
    scope = _validate_scope(candidate_dir, candidate_sha)
    try:
        immutable_transition, trusted_transition_sha256 = (
            candidate_scope.validate_trusted_transition(
                candidate_sha=candidate_sha,
                trusted_code_sha=trusted_sha,
                trusted_workflow_sha=trusted_workflow_sha,
            )
        )
        trusted_transition = json.loads(
            candidate_scope.canonical_json_bytes(immutable_transition)
        )
    except (
        candidate_scope.CandidateScopeValidationError,
        UnicodeDecodeError,
        json.JSONDecodeError,
    ) as exception:
        raise WitnessValidationError(
            "SOURCE_TRANSITION_INVALID", f"trusted candidate transition: {exception}"
        ) from exception
    if (
        trusted_transition["candidate_sha"] != candidate_sha
        or trusted_transition["candidate_tree_sha"] != scope["candidate_tree_sha"]
        or trusted_transition["trusted_code_sha"] != trusted_sha
        or trusted_transition["trusted_code_tree_sha"] != trusted["trusted_tree_sha"]
        or trusted_transition["trusted_workflow_sha"] != trusted_workflow_sha
        or candidate_scope.canonical_sha256(trusted_transition)
        != trusted_transition_sha256
    ):
        raise WitnessValidationError(
            "SOURCE_TRANSITION_INVALID",
            "trusted transition differs from candidate, scope, or trusted code",
        )
    candidate = {
        "accepted_entry_sha": scope["accepted_entry_sha"],
        "candidate_sha": scope["candidate_sha"],
        "candidate_tree_sha": scope["candidate_tree_sha"],
    }
    caller_binding, caller_file = _caller_workflow_binding(
        candidate_dir, scope, trusted_workflow_sha
    )
    contract, trusted_inputs = _load_trusted_inputs(scope)
    policy = _parse_runtime_policy(trusted_inputs[RUNTIME_POLICY_PATH].payload)
    results, raw_files, archive_files = _read_raw_prefix(
        raw_artifacts_dir,
        contract=contract,
        attempt_id=attempt_id,
        candidate=candidate,
        scope=scope,
        github=github,
        trusted_sha=trusted_sha,
        trusted_transition=trusted_transition,
        trusted_transition_sha256=trusted_transition_sha256,
        policy=policy,
    )
    if len(results) != len(command_contract.COMMAND_ORDER) or any(
        result["execution"]["status"] != "PASSED" for result in results
    ):
        raise WitnessValidationError(
            "SOURCES_NOT_GREEN", "isolated command prefix is not fully green"
        )
    final_checkout = _candidate_checkout_snapshot(candidate_dir, candidate_sha)
    final_trusted = _trusted_snapshot(trusted_sha)
    if final_checkout != checkout or final_trusted != trusted:
        raise WitnessValidationError("SOURCE_TOCTOU", "Git source identity changed")
    for relative, item in trusted_inputs.items():
        _revalidate_file(item, relative)
    for item in raw_files:
        _revalidate_authenticated(item, "raw artifact")
    totals = _sum_facts(
        [
            JunitFacts(
                tests=result["execution"]["report_totals"]["tests"],
                failures=result["execution"]["report_totals"]["failures"],
                errors=result["execution"]["report_totals"]["errors"],
                skipped=result["execution"]["report_totals"]["skipped"],
                suite_ids=tuple(result["execution"]["report_totals"]["suite_ids"]),
                testcase_ids=tuple(
                    result["execution"]["report_totals"]["testcase_ids"]
                ),
            )
            for result in results
        ]
    )
    member_index = [
        {"bytes": len(payload), "path": path, "sha256": _sha256(payload)}
        for path, payload in sorted(archive_files.items())
    ]
    witness = {
        "accepted_a8_sha": ACCEPTED_A8,
        "authority_ceiling": AUTHORITY,
        "caller_workflow_binding": caller_binding,
        "caller_workflow_path": CALLER_WORKFLOW_PATH,
        "caller_workflow_ref": github["workflow_ref"],
        "caller_workflow_sha": github["workflow_sha"],
        "candidate_sha": candidate_sha,
        "candidate_tree_sha": candidate["candidate_tree_sha"],
        "command_artifact_set_sha256": _canonical_sha256(member_index),
        "command_contract_payload_sha256": contract["self_seal"]["payload_sha256"],
        "member_index": member_index,
        "schema_version": WITNESS_SCHEMA_VERSION,
        "scope_inventory_sha256": scope["derived_inventory_sha256"],
        "sources_status": {
            "candidate_scope": "PASS",
            "command_contract": "PASS",
            "command_execution": "PASS",
            "runtime_supply_chain": "PASS",
        },
        "trusted_code_sha": trusted_sha,
        "trusted_code_tree_sha": trusted["trusted_tree_sha"],
        "trusted_transition": trusted_transition,
        "trusted_transition_sha256": trusted_transition_sha256,
        "trusted_workflow_file_path": github["job_workflow_file_path"],
        "trusted_workflow_ref": github["job_workflow_ref"],
        "trusted_workflow_repository": github["job_workflow_repository"],
        "trusted_workflow_sha": github["job_workflow_sha"],
        "trusted_workflow_tree_sha": trusted_transition["trusted_workflow_tree_sha"],
    }
    archive_files[WITNESS_NAME] = _canonical_bytes(witness) + b"\n"
    metrics = {
        "commands": len(results),
        "errors": totals.errors,
        "failures": totals.failures,
        "skipped": totals.skipped,
        "tests": totals.tests,
    }
    return (
        witness,
        [caller_file, *trusted_inputs.values(), *raw_files],
        archive_files,
        metrics,
    )


def _failure_witness(
    *,
    attempt_id: str,
    candidate_sha: str,
    trusted_sha: str,
    error: WitnessValidationError,
) -> dict[str, Any]:
    message = str(error)
    if len(message) > 512:
        message = message[:512]
    if SECRET.search(message):
        message = "sensitive failure detail suppressed"
    return {
        "attempt_id": attempt_id,
        "authority": {
            "authority_ceiling": AUTHORITY,
            "authenticated_checkpoint": False,
            "production": "FORBIDDEN",
            "p0_disposition": "PENDING",
            "sigstore_attestation": "PENDING",
        },
        "candidate_sha": candidate_sha,
        "error": {"code": error.code, "message": message},
        "schema_version": "phase8-engineering-witness-failure.v1",
        "state": FAIL,
        "trusted_code_sha": trusted_sha,
    }


def aggregate_witness(
    *,
    candidate_dir: Path,
    candidate_sha: str,
    raw_artifacts_dir: Path,
    output_dir: Path,
    attempt_id: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
) -> tuple[dict[str, Any], int]:
    candidate_sha = _assert_sha1(candidate_sha, "candidate SHA")
    trusted_code_sha = _assert_sha1(trusted_code_sha, "trusted code SHA")
    trusted_workflow_sha = _assert_sha1(trusted_workflow_sha, "trusted workflow SHA")
    if not output_dir.is_absolute() or output_dir.exists() or output_dir.is_symlink():
        raise WitnessValidationError(
            "OUTPUT_NOT_FRESH", "output-dir must be fresh and absolute"
        )
    try:
        resolved_output_parent = output_dir.parent.resolve(strict=True)
    except OSError as exception:
        raise WitnessValidationError(
            "OUTPUT_NOT_FRESH", "output-dir parent is unavailable"
        ) from exception
    if output_dir.parent != resolved_output_parent or output_dir.parent.is_symlink():
        raise WitnessValidationError("OUTPUT_NOT_FRESH", "output-dir parent is aliased")
    output_dir.mkdir(mode=0o700)

    witness: dict[str, Any]
    archive_files: dict[str, bytes]
    metrics = {"commands": 0, "errors": 0, "failures": 0, "skipped": 0, "tests": 0}
    exit_code = 1
    try:
        candidate_dir = _resolve_input_directory(candidate_dir, "candidate-dir")
        raw_artifacts_dir = _resolve_input_directory(
            raw_artifacts_dir, "raw-artifacts-dir"
        )
        for left, right in (
            (candidate_dir, raw_artifacts_dir),
            (candidate_dir, output_dir),
            (raw_artifacts_dir, output_dir),
            (TRUSTED_ROOT, raw_artifacts_dir),
            (TRUSTED_ROOT, output_dir),
        ):
            if left == right or left in right.parents or right in left.parents:
                raise WitnessValidationError(
                    "PATH_INVALID", "witness input and output roots overlap"
                )
        witness, authenticated, archive_files, metrics = _build_result(
            candidate_dir=candidate_dir,
            candidate_sha=candidate_sha,
            raw_artifacts_dir=raw_artifacts_dir,
            attempt_id=attempt_id,
            trusted_sha=trusted_code_sha,
            trusted_workflow_sha=trusted_workflow_sha,
            trusted_workflow_ref=trusted_workflow_ref,
            trusted_workflow_repository=trusted_workflow_repository,
            trusted_workflow_file_path=trusted_workflow_file_path,
        )
        for item in authenticated:
            _revalidate_authenticated(item, "authenticated witness input")
        exit_code = 0
    except WitnessValidationError as error:
        witness = _failure_witness(
            attempt_id=attempt_id,
            candidate_sha=candidate_sha,
            trusted_sha=trusted_code_sha,
            error=error,
        )
        archive_files = {WITNESS_NAME: _canonical_bytes(witness) + b"\n"}
    except Exception:
        error = WitnessValidationError(
            "AGGREGATOR_INFRASTRUCTURE_FAILURE",
            "trusted aggregation failed before a green terminal state",
        )
        witness = _failure_witness(
            attempt_id=attempt_id,
            candidate_sha=candidate_sha,
            trusted_sha=trusted_code_sha,
            error=error,
        )
        archive_files = {WITNESS_NAME: _canonical_bytes(witness) + b"\n"}

    archive_sha = _deterministic_tar(output_dir / ARCHIVE_NAME, archive_files)
    terminal_state = SUCCESS if exit_code == 0 else FAIL
    summary = {
        "archive_sha256": archive_sha,
        "attempt_id": attempt_id,
        "candidate_sha": candidate_sha,
        "command_count": metrics["commands"],
        "schema_version": SUMMARY_SCHEMA_VERSION,
        "state": terminal_state,
        "tests": metrics["tests"],
        "trusted_code_sha": trusted_code_sha,
    }
    status = {
        "archive": ARCHIVE_NAME,
        "archive_sha256": archive_sha,
        "authority_ceiling": AUTHORITY,
        "schema_version": STATUS_SCHEMA_VERSION,
        "state": terminal_state,
    }
    _atomic_json(output_dir / SUMMARY_NAME, summary)
    _atomic_json(output_dir / STATUS_NAME, status)
    return status, exit_code


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Aggregate isolated Phase 8 command receipts without executing candidate code."
    )
    parser.add_argument("--candidate-dir", required=True, type=Path)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--raw-artifacts-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--attempt-id", required=True)
    parser.add_argument("--trusted-code-sha", required=True)
    parser.add_argument("--trusted-workflow-sha", required=True)
    parser.add_argument("--trusted-workflow-ref", required=True)
    parser.add_argument("--trusted-workflow-repository", required=True)
    parser.add_argument("--trusted-workflow-file-path", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        status, exit_code = aggregate_witness(
            candidate_dir=arguments.candidate_dir,
            candidate_sha=arguments.candidate_sha,
            raw_artifacts_dir=arguments.raw_artifacts_dir,
            output_dir=arguments.output_dir,
            attempt_id=arguments.attempt_id,
            trusted_code_sha=arguments.trusted_code_sha,
            trusted_workflow_sha=arguments.trusted_workflow_sha,
            trusted_workflow_ref=arguments.trusted_workflow_ref,
            trusted_workflow_repository=arguments.trusted_workflow_repository,
            trusted_workflow_file_path=arguments.trusted_workflow_file_path,
        )
    except WitnessValidationError as exception:
        print(
            f"Phase 8 witness rejected before output creation: {exception}",
            file=sys.stderr,
        )
        return 2
    print(json.dumps(status, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
