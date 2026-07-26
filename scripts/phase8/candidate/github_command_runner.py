from __future__ import annotations

import argparse
import copy
import contextlib
import hashlib
import io
import json
import os
import re
import secrets
import signal
import shutil
import stat
import subprocess
import sys
import tarfile
import threading
import time
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Iterator, Mapping, Sequence

from scripts.phase8.candidate import candidate_scope, command_contract, runtime_policy


MODULE_PATH = Path(__file__).resolve()
TRUSTED_ROOT = MODULE_PATH.parents[3]
FIXED_REPOSITORY = "Jupiter363/AfterSaleFlow-Agent"
FIXED_REPOSITORY_ID = "1282437633"
FIXED_BRANCH = "refs/heads/codex/p8-production-hardening"
CALLER_WORKFLOW_PATH = ".github/workflows/phase8-engineering-caller.yml"
TRUSTED_WORKFLOW_PATH = ".github/workflows/phase8-engineering-witness.yml"
SCOPE_PATH = "contracts/agent-platform/phase8/engineering-candidate-scope.json"
RAW_SCHEMA_VERSION = "phase8-isolated-command-result.v1"
RAW_AUTHORITY = "ENGINEERING_TEST_COMMAND_RESULT_ONLY"
BUILD_BUNDLE_SCHEMA_VERSION = "phase8-runtime-build-bundle.v1"
OBSERVATION_SCHEMA_VERSION = runtime_policy.BUILD_OBSERVATION_RECEIPT_SCHEMA_VERSION
MATERIALIZATION_DIRECTORY = "materialization"
MATERIALIZATION_MANIFEST_NAME = "manifest.json"
MATERIALIZATION_RECEIPT_NAME = "receipt.json"
RUNTIME_BUILD_RECEIPT_NAME = "runtime-build-receipt.json"
WHEELHOUSE_MANIFEST_NAME = "wheelhouse-manifest.json"
BUILD_OBSERVATION_NAME = "build-observation-receipt.json"
RESULT_NAME = "result.json"
REPORTS_DIRECTORY = "reports"

BUILD_JOB = "phase8_build_runtime"
OBSERVE_JOB = "phase8_observe_runtime"
COMMAND_JOBS = {
    command_id: f"phase8_{command_id}" for command_id in command_contract.COMMAND_ORDER
}
COMMAND_DIRECTORIES = {
    command_id: f"{order:03d}-{command_id}"
    for order, command_id in enumerate(command_contract.COMMAND_ORDER)
}
STATIC_COMMANDS = set(command_contract.STATIC_COMMAND_IDS)
MAVEN_COMMANDS = set(command_contract.MAVEN_SUITE_SPECS)
MAVEN_IMAGE_TAG = "maven:3.9.11-eclipse-temurin-21"
MAVEN_IMAGE_INDEX_DIGEST = (
    "sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237"
)
MAVEN_IMAGE_PLATFORM_DIGEST = (
    "sha256:463a1849665463254b2dd56e3a5b316f1596bc93d0571065c06ea05bb48ab8f4"
)
MAVEN_IMAGE = f"docker.io/library/maven@{MAVEN_IMAGE_PLATFORM_DIGEST}"
DIND_IMAGE_TAG = "docker:28.5.2-dind-rootless"
DIND_IMAGE_INDEX_DIGEST = (
    "sha256:7c3e797187e43738220462658f4586572cbd3bf009f728b21e34d9c5c06ce431"
)
DIND_IMAGE_PLATFORM_DIGEST = (
    "sha256:95813f7e06959c7cbd0e5a6e357cb76bf97c20db85ee2d16c57122c340ded385"
)
DIND_IMAGE = f"docker.io/library/docker@{DIND_IMAGE_PLATFORM_DIGEST}"
MAVEN_DIND_ALIAS = "phase8-dind"
MAVEN_DIND_HOST = f"tcp://{MAVEN_DIND_ALIAS}:2375"
MAVEN_DIND_LOCAL_HOST = "tcp://127.0.0.1:2375"
MAVEN_DIND_DATA_TMPFS = (
    "/home/rootless/.local/share/docker:rw,nosuid,nodev,size=4294967296,"
    "mode=0700,uid=1000,gid=1000"
)
MAVEN_JANSI_OPTS = "-Djansi.tmpdir=/home/phase8"
MAVEN_RYUK_DISABLED = "true"
DIND_READY_TIMEOUT_SECONDS = 180
MAVEN_CANDIDATE_EXTRACTOR_ARGV = (
    "/bin/tar",
    "--extract",
    "--file=-",
    "--directory=/workspace",
    "--no-same-owner",
    "--same-permissions",
    "--touch",
    "--no-overwrite-dir",
)

SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
POSITIVE_INTEGER = re.compile(r"^[1-9][0-9]{0,19}$")
SAFE_TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,255}$")
WINDOWS_DEVICE = re.compile(
    r"^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$", re.IGNORECASE
)
SECRET = re.compile(
    r"(?i)(?:-----BEGIN [A-Z ]*PRIVATE KEY-----|"
    r"(?:authorization|cookie|credential|password|secret|access[_-]?token|"
    r"github[_-]?token)\s*[:=]\s*\S+)"
)
FORBIDDEN_ENV_KEY = re.compile(
    r"(?i)(?:authorization|cookie|credential|password|secret|token)"
)

MAX_GIT_CONTROL_BYTES = 64 * 1024 * 1024
MAX_GIT_BLOB_BYTES = 512 * 1024 * 1024
MAX_TREE_FILES = 50_000
MAX_TREE_BYTES = 8 * 1024 * 1024 * 1024
MAX_PROCESS_OUTPUT_BYTES = 8 * 1024 * 1024
MAX_SUMMARY_BYTES = 2048
MAX_JUNIT_BYTES = 128 * 1024 * 1024
MAX_JUNIT_XML_DEPTH = 32
MAX_JUNIT_XML_NODES = 100_000
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_OUTPUT_FILES = 512
MAX_REPORT_DIRECTORY_BYTES = 256 * 1024 * 1024
TAR_BLOCK_BYTES = 512
TAR_RECORD_BYTES = 20 * TAR_BLOCK_BYTES
MAX_REPORT_STREAM_BYTES = (
    (
        MAX_REPORT_DIRECTORY_BYTES
        + (2 * MAX_OUTPUT_FILES + 3) * TAR_BLOCK_BYTES
        + TAR_RECORD_BYTES
        - 1
    )
    // TAR_RECORD_BYTES
) * TAR_RECORD_BYTES
REPORT_EXPORTER_ARGV = (
    "/bin/tar",
    "--create",
    "--file=-",
    "--format=ustar",
    "--check-links",
    "--directory",
)
USTAR_MEMBER_NAME_BYTES = 100
REPORT_TRANSPORT_ALIAS_PREFIX = "phase8-junit"
REPORT_TRANSPORT_QUARANTINE_PREFIX = "phase8-extra"
FIXED_JUNIT_REPORT_GLOB = "TEST-*.xml"


class CommandRunnerError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        if SAFE_TOKEN.fullmatch(code) is None:
            raise ValueError("runner error code must be a safe token")
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class StableFile:
    path: Path
    identity: tuple[int, int, int, int, int, int, int]
    payload: bytes
    sha256: str


@dataclass(frozen=True)
class HashedFile:
    path: Path
    identity: tuple[int, int, int, int, int, int, int]
    bytes: int
    sha256: str


@dataclass(frozen=True)
class ProcessResult:
    exit_code: int
    timed_out: bool
    output_limited: bool
    stdout: bytes
    stderr: bytes


@dataclass(frozen=True)
class GitHubIdentity:
    values: dict[str, str]

    @property
    def job_identity_sha256(self) -> str:
        return _canonical_sha256(self.values)

    @property
    def attempt_id(self) -> str:
        return f"github-{self.values['run_id']}-{self.values['run_attempt']}"


@dataclass(frozen=True)
class CandidateSnapshot:
    candidate_sha: str
    candidate_tree_sha: str
    scope_inventory_sha256: str
    scope: dict[str, Any]
    materialization_inventories: dict[str, dict[str, Any]]
    trusted_transition: dict[str, Any] | None = None
    trusted_transition_sha256: str | None = None


@dataclass(frozen=True)
class Materialization:
    manifest: list[dict[str, Any]]
    receipt: dict[str, Any]
    candidate_binding: dict[str, Any]
    scope_inventory: dict[str, Any]
    expected_run_binding: dict[str, Any]
    archive: HashedFile


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
        raise CommandRunnerError("NON_CANONICAL_DATA", str(exception)) from exception


def _canonical_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _git_blob_sha1(payload: bytes) -> str:
    return hashlib.sha1(f"blob {len(payload)}\0".encode("ascii") + payload).hexdigest()


def _identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int, int]:
    return (
        int(metadata.st_dev),
        int(metadata.st_ino),
        int(metadata.st_mode),
        int(metadata.st_nlink),
        int(metadata.st_size),
        int(metadata.st_mtime_ns),
        int(getattr(metadata, "st_file_attributes", 0)),
    )


def _is_alias(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def _assert_sha1(value: str, context: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        raise CommandRunnerError("IDENTITY_INVALID", f"{context} is not a full Git SHA")
    return value


def _assert_sha256(value: str, context: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise CommandRunnerError("IDENTITY_INVALID", f"{context} is not SHA-256")
    return value


def _safe_relative_path(value: str, context: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > 512
        or unicodedata.normalize("NFC", value) != value
        or "\\" in value
        or ":" in value
        or "\x00" in value
    ):
        raise CommandRunnerError("PATH_INVALID", f"{context} is not canonical")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or path.as_posix() != value
        or any(part in {"", ".", "..", ".git"} for part in path.parts)
    ):
        raise CommandRunnerError("PATH_INVALID", f"{context} escapes its root")
    for part in path.parts:
        if (
            part.endswith((" ", "."))
            or WINDOWS_DEVICE.fullmatch(part)
            or any(ord(character) < 32 or ord(character) == 127 for character in part)
        ):
            raise CommandRunnerError(
                "PATH_INVALID", f"{context} has an aliased component"
            )
    return path.as_posix()


def _assert_ustar_path(value: str) -> None:
    encoded = value.encode("utf-8", errors="strict")
    if len(encoded) <= 100:
        return
    for index, character in reversed(tuple(enumerate(encoded))):
        if character != ord("/"):
            continue
        prefix = encoded[:index]
        name = encoded[index + 1 :]
        if prefix and name and len(prefix) <= 155 and len(name) <= 100:
            return
    raise CommandRunnerError(
        "CANDIDATE_ARCHIVE_INVALID", "candidate path is not representable in USTAR"
    )


def _read_stable_file(path: Path, *, max_bytes: int, context: str) -> StableFile:
    try:
        before = os.lstat(path)
    except OSError as exception:
        raise CommandRunnerError(
            "FILE_UNAVAILABLE", f"{context} cannot be inspected"
        ) from exception
    if (
        _is_alias(before)
        or not stat.S_ISREG(before.st_mode)
        or before.st_nlink != 1
        or not 0 <= before.st_size <= max_bytes
    ):
        raise CommandRunnerError(
            "FILE_SUBSTITUTED", f"{context} is linked or oversized"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            opened = os.fstat(handle.fileno())
            if _identity(opened) != _identity(before):
                raise CommandRunnerError(
                    "FILE_SUBSTITUTED", f"{context} changed before read"
                )
            payload = handle.read(max_bytes + 1)
            after_open = os.fstat(handle.fileno())
    except CommandRunnerError:
        raise
    except OSError as exception:
        raise CommandRunnerError(
            "FILE_UNAVAILABLE", f"{context} cannot be read"
        ) from exception
    try:
        after_path = os.lstat(path)
    except OSError as exception:
        raise CommandRunnerError(
            "FILE_SUBSTITUTED", f"{context} disappeared"
        ) from exception
    if (
        len(payload) > max_bytes
        or _identity(after_open) != _identity(before)
        or _identity(after_path) != _identity(before)
    ):
        raise CommandRunnerError("FILE_SUBSTITUTED", f"{context} changed during read")
    return StableFile(path, _identity(before), payload, _sha256(payload))


def _revalidate_file(item: StableFile, *, max_bytes: int, context: str) -> None:
    observed = _read_stable_file(item.path, max_bytes=max_bytes, context=context)
    if observed.identity != item.identity or observed.sha256 != item.sha256:
        raise CommandRunnerError(
            "FILE_SUBSTITUTED", f"{context} changed after validation"
        )


def _hash_stable_file(path: Path, *, max_bytes: int, context: str) -> HashedFile:
    try:
        before = os.lstat(path)
    except OSError as exception:
        raise CommandRunnerError(
            "FILE_UNAVAILABLE", f"{context} cannot be inspected"
        ) from exception
    if (
        _is_alias(before)
        or not stat.S_ISREG(before.st_mode)
        or before.st_nlink != 1
        or not 1 <= before.st_size <= max_bytes
    ):
        raise CommandRunnerError(
            "FILE_SUBSTITUTED", f"{context} is linked or oversized"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    digest = hashlib.sha256()
    total = 0
    try:
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        if _identity(opened) != _identity(before):
            raise CommandRunnerError(
                "FILE_SUBSTITUTED", f"{context} changed before hashing"
            )
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                raise CommandRunnerError(
                    "FILE_SUBSTITUTED", f"{context} grew while hashing"
                )
            digest.update(chunk)
        after_open = os.fstat(descriptor)
    except CommandRunnerError:
        raise
    except OSError as exception:
        raise CommandRunnerError(
            "FILE_UNAVAILABLE", f"{context} cannot be hashed"
        ) from exception
    finally:
        with contextlib.suppress(UnboundLocalError, OSError):
            os.close(descriptor)
    try:
        after_path = os.lstat(path)
    except OSError as exception:
        raise CommandRunnerError(
            "FILE_SUBSTITUTED", f"{context} disappeared"
        ) from exception
    if (
        total != before.st_size
        or _identity(after_open) != _identity(before)
        or _identity(after_path) != _identity(before)
    ):
        raise CommandRunnerError("FILE_SUBSTITUTED", f"{context} changed while hashing")
    return HashedFile(path, _identity(before), total, digest.hexdigest())


def _revalidate_hashed_file(item: HashedFile, *, max_bytes: int, context: str) -> None:
    observed = _hash_stable_file(item.path, max_bytes=max_bytes, context=context)
    if (
        observed.identity != item.identity
        or observed.bytes != item.bytes
        or observed.sha256 != item.sha256
    ):
        raise CommandRunnerError(
            "FILE_SUBSTITUTED", f"{context} changed after validation"
        )


def _atomic_json(path: Path, value: Mapping[str, Any] | list[Any]) -> StableFile:
    payload = _canonical_bytes(value) + b"\n"
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(8)}.tmp")
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
    return _read_stable_file(
        path, max_bytes=max(len(payload), MAX_JSON_BYTES), context=path.name
    )


def _atomic_wheelhouse_manifest(
    path: Path, manifest: list[dict[str, Any]]
) -> StableFile:
    payload = runtime_policy.canonical_json_bytes(manifest)
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(8)}.tmp")
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
    return _read_stable_file(
        path,
        max_bytes=runtime_policy.MAX_MANIFEST_BYTES,
        context=path.name,
    )


def _write_stable_payload(path: Path, payload: bytes, *, context: str) -> StableFile:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "wb", closefd=True) as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(path, 0o644)
    return _read_stable_file(path, max_bytes=max(len(payload), 1), context=context)


def _file_reference(item: StableFile, logical_path: str) -> dict[str, Any]:
    return {
        "bytes": len(item.payload),
        "path": _safe_relative_path(logical_path, "artifact file reference"),
        "sha256": item.sha256,
    }


def _hashed_file_reference(item: HashedFile, logical_path: str) -> dict[str, Any]:
    return {
        "bytes": item.bytes,
        "path": _safe_relative_path(logical_path, "artifact file reference"),
        "sha256": item.sha256,
    }


def _fresh_output_directory(path: Path) -> Path:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise CommandRunnerError(
            "OUTPUT_NOT_FRESH", "output-dir must be fresh and absolute"
        )
    if os.environ.get("GITHUB_ACTIONS") == "true" and path.parent != Path("/tmp"):
        raise CommandRunnerError(
            "OUTPUT_NOT_EXTERNAL",
            "GitHub output-dir must be a direct child of /tmp",
        )
    try:
        parent = path.parent.resolve(strict=True)
        parent_metadata = os.lstat(parent)
    except OSError as exception:
        raise CommandRunnerError(
            "OUTPUT_NOT_FRESH", "output parent is unavailable"
        ) from exception
    if (
        parent != path.parent
        or _is_alias(parent_metadata)
        or not stat.S_ISDIR(parent_metadata.st_mode)
    ):
        raise CommandRunnerError("OUTPUT_NOT_FRESH", "output parent is aliased")
    path.mkdir(mode=0o700)
    return path


def _resolve_executable(name: str) -> Path:
    discovered = shutil.which(name)
    if not discovered:
        raise CommandRunnerError(
            "EXECUTABLE_UNAVAILABLE", f"required executable {name} is unavailable"
        )
    path = Path(discovered).resolve(strict=True)
    if not path.is_absolute() or not path.is_file():
        raise CommandRunnerError(
            "EXECUTABLE_UNAVAILABLE", f"required executable {name} is invalid"
        )
    return path


def _minimal_control_environment() -> dict[str, str]:
    environment: dict[str, str] = {}
    for key in ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "PATH"):
        value = os.environ.get(key)
        if value:
            environment[key] = value
    environment.update(
        {
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_KEY_0": "protocol.allow",
            "GIT_CONFIG_GLOBAL": "NUL" if os.name == "nt" else "/dev/null",
            "GIT_CONFIG_VALUE_0": "never",
            "GIT_NO_LAZY_FETCH": "1",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_TERMINAL_PROMPT": "0",
            "LANG": "C",
            "LC_ALL": "C",
        }
    )
    return environment


def _assert_no_oidc_capability() -> None:
    if any(
        key in os.environ
        for key in ("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "ACTIONS_ID_TOKEN_REQUEST_URL")
    ):
        raise CommandRunnerError("OIDC_PRESENT", "job unexpectedly has OIDC capability")


def _candidate_environment(command: Mapping[str, Any], home: Path) -> dict[str, str]:
    _assert_no_oidc_capability()
    environment: dict[str, str] = {
        "CI": "1",
        "HOME": str(home),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
    }
    for key in (
        "JAVA_HOME",
        "PATH",
        "SYSTEMROOT",
        "WINDIR",
        "COMSPEC",
        "PATHEXT",
        "TEMP",
        "TMP",
    ):
        value = os.environ.get(key)
        if value and FORBIDDEN_ENV_KEY.search(key) is None:
            environment[key] = value
    for key, value in command["environment"].items():
        if FORBIDDEN_ENV_KEY.search(key):
            raise CommandRunnerError(
                "ENVIRONMENT_FORBIDDEN", "contract contains a credential-shaped key"
            )
        environment[key] = value
    if any(FORBIDDEN_ENV_KEY.search(key) for key in environment):
        raise CommandRunnerError(
            "ENVIRONMENT_FORBIDDEN", "candidate environment contains credentials"
        )
    return environment


def _network_tool_environment(home: Path) -> dict[str, str]:
    _assert_no_oidc_capability()
    environment: dict[str, str] = {
        "CI": "1",
        "HOME": str(home),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PIP_CONFIG_FILE": "/dev/null",
        "PIP_DISABLE_PIP_VERSION_CHECK": "1",
        "PIP_NO_CACHE_DIR": "1",
        "PYTHONDONTWRITEBYTECODE": "1",
        "PYTHONHASHSEED": "0",
        "PYTHONNOUSERSITE": "1",
    }
    for key in ("PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP"):
        value = os.environ.get(key)
        if value:
            environment[key] = value
    if any(FORBIDDEN_ENV_KEY.search(key) for key in environment):
        raise CommandRunnerError(
            "ENVIRONMENT_FORBIDDEN", "tool environment contains credentials"
        )
    return environment


def _capture_pipe(
    stream: Any,
    buffer: bytearray,
    state: dict[str, bool],
    lock: threading.Lock,
) -> None:
    while True:
        chunk = stream.read(64 * 1024)
        if not chunk:
            return
        with lock:
            remaining = MAX_PROCESS_OUTPUT_BYTES - len(buffer)
            if remaining > 0:
                buffer.extend(chunk[:remaining])
            if len(chunk) > remaining:
                state["limited"] = True


def _run_bounded(
    argv: Sequence[str],
    *,
    cwd: Path,
    env: Mapping[str, str],
    timeout_seconds: int,
) -> ProcessResult:
    return _run_bounded_process(
        argv,
        cwd=cwd,
        env=env,
        timeout_seconds=timeout_seconds,
        stdin=subprocess.DEVNULL,
    )


def _capture_pipe_to_file(
    stream: Any,
    descriptor: int,
    state: dict[str, Any],
    lock: threading.Lock,
    maximum_bytes: int,
) -> None:
    digest = hashlib.sha256()
    total = 0
    try:
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            while True:
                chunk = stream.read(64 * 1024)
                if not chunk:
                    break
                remaining = maximum_bytes - total
                if remaining <= 0:
                    with lock:
                        state["limited"] = True
                    break
                accepted = chunk[:remaining]
                handle.write(accepted)
                digest.update(accepted)
                total += len(accepted)
                if len(accepted) != len(chunk):
                    with lock:
                        state["limited"] = True
                    break
            handle.flush()
            os.fsync(handle.fileno())
    except Exception as exception:
        with lock:
            state["stream_error"] = exception
    finally:
        with contextlib.suppress(OSError):
            stream.close()
        with lock:
            state["stream_bytes"] = total
            state["stream_sha256"] = digest.hexdigest()
            state["stream_done"] = True


def _run_bounded_stdout_to_file(
    argv: Sequence[str],
    *,
    cwd: Path,
    env: Mapping[str, str],
    timeout_seconds: int,
    target: Path,
    maximum_bytes: int,
) -> tuple[ProcessResult, HashedFile]:
    if target.exists() or target.is_symlink():
        raise CommandRunnerError("FILE_SUBSTITUTED", "stream target is not fresh")
    if maximum_bytes <= 0:
        raise CommandRunnerError("FILE_SUBSTITUTED", "stream byte bound is invalid")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    descriptor = os.open(target, flags, 0o600)
    try:
        if (
            not isinstance(argv, (list, tuple))
            or not argv
            or any(
                not isinstance(item, str) or not item or "\x00" in item for item in argv
            )
        ):
            raise CommandRunnerError(
                "ARGV_INVALID", "process argv is not a bounded string vector"
            )
        if timeout_seconds <= 0 or timeout_seconds > 3600:
            raise CommandRunnerError(
                "TIMEOUT_INVALID", "process timeout is out of bounds"
            )
        if any(FORBIDDEN_ENV_KEY.search(key) for key in env):
            raise CommandRunnerError(
                "ENVIRONMENT_FORBIDDEN", "child environment contains a credential key"
            )
        try:
            process = subprocess.Popen(
                list(argv),
                cwd=cwd,
                env=dict(env),
                shell=False,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=(os.name != "nt"),
            )
        except OSError as exception:
            raise CommandRunnerError(
                "PROCESS_START_FAILED", str(exception)
            ) from exception
        if process.stdout is None or process.stderr is None:
            process.kill()
            raise CommandRunnerError(
                "PROCESS_PIPE_FAILED", "process output pipe is unavailable"
            )
        stderr = bytearray()
        state: dict[str, Any] = {
            "limited": False,
            "stream_bytes": 0,
            "stream_done": False,
            "stream_error": None,
            "stream_sha256": None,
        }
        lock = threading.Lock()
        threads = [
            threading.Thread(
                target=_capture_pipe_to_file,
                args=(process.stdout, descriptor, state, lock, maximum_bytes),
                daemon=True,
            ),
            threading.Thread(
                target=_capture_pipe,
                args=(process.stderr, stderr, state, lock),
                daemon=True,
            ),
        ]
        descriptor = -1
        for thread in threads:
            thread.start()
        deadline = time.monotonic() + timeout_seconds
        timed_out = False
        while process.poll() is None:
            with lock:
                failed = state["stream_error"] is not None
                limited = bool(state["limited"])
            if failed or limited:
                _terminate_process(process)
                break
            if time.monotonic() >= deadline:
                timed_out = True
                _terminate_process(process)
                break
            time.sleep(0.02)
        with contextlib.suppress(subprocess.TimeoutExpired):
            process.wait(timeout=5)
        if process.poll() is None:
            _terminate_process(process)
            process.wait()
        for thread in threads:
            thread.join(timeout=5)
        with lock:
            stream_done = bool(state["stream_done"])
            stream_error = state["stream_error"]
            stream_bytes = int(state["stream_bytes"])
            stream_sha256 = state["stream_sha256"]
            output_limited = bool(state["limited"])
        if not stream_done or stream_error is not None:
            raise CommandRunnerError(
                "STREAM_INVALID", "bounded process stream was not written completely"
            ) from stream_error
        exit_code = (
            process.returncode
            if process.returncode is not None and process.returncode >= 0
            else 125
        )
        if timed_out:
            exit_code = 124
        elif output_limited:
            exit_code = 125
        streamed = _hash_stable_file(
            target, max_bytes=maximum_bytes, context="bounded process stream"
        )
        if streamed.bytes != stream_bytes or streamed.sha256 != stream_sha256:
            raise CommandRunnerError(
                "STREAM_INVALID", "bounded process stream changed after capture"
            )
        return (
            ProcessResult(exit_code, timed_out, output_limited, b"", bytes(stderr)),
            streamed,
        )
    finally:
        if descriptor >= 0:
            with contextlib.suppress(OSError):
                os.close(descriptor)


def _run_bounded_with_verified_archive(
    argv: Sequence[str],
    *,
    cwd: Path,
    env: Mapping[str, str],
    timeout_seconds: int,
    archive: runtime_policy.ValidatedCandidateArchive,
) -> tuple[ProcessResult, dict[str, Any]]:
    if (
        not isinstance(argv, (list, tuple))
        or not argv
        or any(not isinstance(item, str) or not item or "\x00" in item for item in argv)
    ):
        raise CommandRunnerError(
            "ARGV_INVALID", "process argv is not a bounded string vector"
        )
    if timeout_seconds <= 0 or timeout_seconds > 3600:
        raise CommandRunnerError("TIMEOUT_INVALID", "process timeout is out of bounds")
    if any(FORBIDDEN_ENV_KEY.search(key) for key in env):
        raise CommandRunnerError(
            "ENVIRONMENT_FORBIDDEN", "child environment contains credentials"
        )
    expected_evidence = {
        **archive.evidence(),
        "consumption_method": "VALIDATED_FD_STREAM_V1",
    }
    try:
        process = subprocess.Popen(
            list(argv),
            cwd=cwd,
            env=dict(env),
            shell=False,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=(os.name != "nt"),
        )
    except OSError as exception:
        raise CommandRunnerError("PROCESS_START_FAILED", str(exception)) from exception
    if process.stdin is None or process.stdout is None or process.stderr is None:
        process.kill()
        raise CommandRunnerError("PROCESS_PIPE_FAILED", "process pipe is unavailable")
    stdout = bytearray()
    stderr = bytearray()
    state: dict[str, Any] = {
        "evidence": None,
        "error": None,
        "limited": False,
        "producer_done": False,
    }
    lock = threading.Lock()

    def produce_archive() -> None:
        try:
            evidence = archive.stream_into(process.stdin)
            with lock:
                state["evidence"] = evidence
        except Exception as exception:
            with lock:
                state["error"] = exception
        finally:
            with contextlib.suppress(OSError):
                process.stdin.close()
            with lock:
                state["producer_done"] = True

    threads = [
        threading.Thread(target=produce_archive, daemon=True),
        threading.Thread(
            target=_capture_pipe,
            args=(process.stdout, stdout, state, lock),
            daemon=True,
        ),
        threading.Thread(
            target=_capture_pipe,
            args=(process.stderr, stderr, state, lock),
            daemon=True,
        ),
    ]
    for thread in threads:
        thread.start()
    deadline = time.monotonic() + timeout_seconds
    timed_out = False
    while process.poll() is None:
        with lock:
            producer_failed = state["error"] is not None
            output_limited = bool(state["limited"])
        if producer_failed or output_limited:
            _terminate_process(process)
            break
        if time.monotonic() >= deadline:
            timed_out = True
            _terminate_process(process)
            break
        time.sleep(0.02)
    with contextlib.suppress(OSError):
        process.stdin.close()
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.wait(timeout=5)
    if process.poll() is None:
        _terminate_process(process)
        process.wait()
    for thread in threads:
        thread.join(timeout=5)
    with lock:
        producer_done = bool(state["producer_done"])
        producer_error = state["error"]
        evidence = state["evidence"]
        output_limited = bool(state["limited"])
    if not producer_done or producer_error is not None:
        raise CommandRunnerError(
            "CANDIDATE_ARCHIVE_INVALID", "candidate archive stream was not consumed"
        ) from producer_error
    if evidence != expected_evidence:
        raise CommandRunnerError(
            "CANDIDATE_ARCHIVE_INVALID",
            "candidate archive consumption evidence drifted",
        )
    exit_code = (
        process.returncode
        if process.returncode is not None and process.returncode >= 0
        else 125
    )
    if timed_out:
        exit_code = 124
    elif output_limited:
        exit_code = 125
    return (
        ProcessResult(
            exit_code,
            timed_out,
            output_limited,
            bytes(stdout),
            bytes(stderr),
        ),
        evidence,
    )


def _run_bounded_process(
    argv: Sequence[str],
    *,
    cwd: Path,
    env: Mapping[str, str],
    timeout_seconds: int,
    stdin: Any,
) -> ProcessResult:
    if (
        not isinstance(argv, (list, tuple))
        or not argv
        or any(not isinstance(item, str) or not item or "\x00" in item for item in argv)
    ):
        raise CommandRunnerError(
            "ARGV_INVALID", "process argv is not a bounded string vector"
        )
    if timeout_seconds <= 0 or timeout_seconds > 3600:
        raise CommandRunnerError("TIMEOUT_INVALID", "process timeout is out of bounds")
    if any(FORBIDDEN_ENV_KEY.search(key) for key in env):
        raise CommandRunnerError(
            "ENVIRONMENT_FORBIDDEN", "child environment contains a credential key"
        )
    try:
        process = subprocess.Popen(
            list(argv),
            cwd=cwd,
            env=dict(env),
            shell=False,
            stdin=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=(os.name != "nt"),
        )
    except OSError as exception:
        raise CommandRunnerError("PROCESS_START_FAILED", str(exception)) from exception
    if process.stdout is None or process.stderr is None:
        process.kill()
        raise CommandRunnerError(
            "PROCESS_PIPE_FAILED", "process output pipe is unavailable"
        )
    stdout = bytearray()
    stderr = bytearray()
    state = {"limited": False}
    lock = threading.Lock()
    threads = [
        threading.Thread(
            target=_capture_pipe,
            args=(process.stdout, stdout, state, lock),
            daemon=True,
        ),
        threading.Thread(
            target=_capture_pipe,
            args=(process.stderr, stderr, state, lock),
            daemon=True,
        ),
    ]
    for thread in threads:
        thread.start()
    deadline = time.monotonic() + timeout_seconds
    timed_out = False
    while process.poll() is None:
        if state["limited"]:
            _terminate_process(process)
            break
        if time.monotonic() >= deadline:
            timed_out = True
            _terminate_process(process)
            break
        time.sleep(0.02)
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.wait(timeout=5)
    if process.poll() is None:
        _terminate_process(process)
        process.wait()
    for thread in threads:
        thread.join(timeout=5)
    exit_code = (
        process.returncode
        if process.returncode is not None and process.returncode >= 0
        else 125
    )
    if timed_out:
        exit_code = 124
    elif state["limited"]:
        exit_code = 125
    return ProcessResult(
        exit_code, timed_out, state["limited"], bytes(stdout), bytes(stderr)
    )


def _terminate_process(process: subprocess.Popen[bytes]) -> None:
    with contextlib.suppress(OSError, ProcessLookupError):
        if os.name == "nt":
            process.kill()
        else:
            os.killpg(process.pid, signal.SIGKILL)


def _require_success(
    result: ProcessResult, context: str, *, code: str = "TOOL_FAILED"
) -> None:
    if result.exit_code != 0 or result.timed_out or result.output_limited:
        raise CommandRunnerError(code, f"{context} did not complete successfully")


def _git_bytes(
    arguments: Sequence[str], *, max_bytes: int = MAX_GIT_CONTROL_BYTES
) -> bytes:
    git = _resolve_executable("git")
    try:
        result = subprocess.run(
            (str(git), *arguments),
            cwd=TRUSTED_ROOT,
            env=_minimal_control_environment(),
            shell=False,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError) as exception:
        raise CommandRunnerError("GIT_FAILED", str(exception)) from exception
    if (
        result.returncode != 0
        or len(result.stdout) > max_bytes
        or len(result.stderr) > 64 * 1024
    ):
        raise CommandRunnerError(
            "GIT_FAILED", "fixed Git query failed or exceeded its output bound"
        )
    return bytes(result.stdout)


def _git_text(*arguments: str) -> str:
    try:
        return _git_bytes(arguments).decode("ascii", errors="strict").strip()
    except UnicodeDecodeError as exception:
        raise CommandRunnerError(
            "GIT_FAILED", "fixed Git output is not ASCII"
        ) from exception


def _git_blob(
    candidate_sha: str, path: str, *, max_bytes: int = MAX_GIT_BLOB_BYTES
) -> bytes:
    safe = _safe_relative_path(path, "Git blob path")
    return _git_bytes(("show", f"{candidate_sha}:{safe}"), max_bytes=max_bytes)


def _trusted_snapshot(trusted_code_sha: str) -> tuple[str, str]:
    trusted_code_sha = _assert_sha1(trusted_code_sha, "trusted code SHA")
    if _git_text("rev-parse", "HEAD") != trusted_code_sha:
        raise CommandRunnerError(
            "TRUSTED_CODE_MISMATCH", "trusted checkout HEAD differs"
        )
    if _git_bytes(("status", "--porcelain=v1", "-z", "--untracked-files=all")):
        raise CommandRunnerError("TRUSTED_CODE_DIRTY", "trusted checkout is not clean")
    tree = _assert_sha1(
        _git_text("rev-parse", f"{trusted_code_sha}^{{tree}}"), "trusted tree"
    )
    return trusted_code_sha, tree


def _runtime_run_binding(identity: GitHubIdentity) -> dict[str, Any]:
    return {
        "caller_workflow_ref": identity.values["workflow_ref"],
        "caller_workflow_sha": identity.values["workflow_sha"],
        "repository": identity.values["repository"],
        "repository_id": identity.values["repository_id"],
        "run_attempt": int(identity.values["run_attempt"]),
        "run_id": identity.values["run_id"],
        "runner_arch": identity.values["runner_arch"],
        "runner_environment": identity.values["runner_environment"],
        "runner_os": identity.values["runner_os"],
        "trusted_workflow_path": identity.values["job_workflow_file_path"],
        "trusted_workflow_ref": identity.values["job_workflow_ref"],
        "trusted_workflow_repository": identity.values["job_workflow_repository"],
        "trusted_workflow_sha": identity.values["job_workflow_sha"],
    }


def _runtime_job_identity(identity: GitHubIdentity) -> dict[str, Any]:
    return {
        **_runtime_run_binding(identity),
        "job_name": identity.values["job"],
        "schema_version": runtime_policy.GITHUB_JOB_IDENTITY_SCHEMA_VERSION,
    }


def _assert_runtime_job_matches(
    value: Mapping[str, Any], identity: GitHubIdentity, *, expected_job: str
) -> None:
    expected = _runtime_job_identity(identity)
    expected["job_name"] = expected_job
    if dict(value) != expected:
        raise CommandRunnerError(
            "GITHUB_IDENTITY_INVALID", "runtime job identity differs"
        )


def _github_identity(
    *,
    expected_job: str,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
) -> GitHubIdentity:
    candidate_sha = _assert_sha1(candidate_sha, "candidate SHA")
    trusted_code_sha = _assert_sha1(trusted_code_sha, "trusted code SHA")
    trusted_workflow_sha = _assert_sha1(trusted_workflow_sha, "trusted workflow SHA")
    expected_caller_ref = f"{FIXED_REPOSITORY}/{CALLER_WORKFLOW_PATH}@{FIXED_BRANCH}"
    expected_job_ref = (
        f"{FIXED_REPOSITORY}/{TRUSTED_WORKFLOW_PATH}@{trusted_workflow_sha}"
    )
    observed = {
        "candidate_sha": os.environ.get("GITHUB_SHA", ""),
        "job": os.environ.get("GITHUB_JOB", ""),
        "job_workflow_file_path": trusted_workflow_file_path,
        "job_workflow_ref": trusted_workflow_ref,
        "job_workflow_repository": trusted_workflow_repository,
        "job_workflow_sha": trusted_workflow_sha,
        "repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "repository_id": os.environ.get("GITHUB_REPOSITORY_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "runner_arch": os.environ.get("RUNNER_ARCH", ""),
        "runner_environment": os.environ.get("RUNNER_ENVIRONMENT", ""),
        "runner_os": os.environ.get("RUNNER_OS", ""),
        "server_url": os.environ.get("GITHUB_SERVER_URL", ""),
        "trusted_code_sha": trusted_code_sha,
        "workflow_ref": os.environ.get("GITHUB_WORKFLOW_REF", ""),
        "workflow_sha": os.environ.get("GITHUB_WORKFLOW_SHA", ""),
    }
    expected = {
        "candidate_sha": candidate_sha,
        "job": expected_job,
        "job_workflow_file_path": TRUSTED_WORKFLOW_PATH,
        "job_workflow_ref": expected_job_ref,
        "job_workflow_repository": FIXED_REPOSITORY,
        "job_workflow_sha": trusted_workflow_sha,
        "repository": FIXED_REPOSITORY,
        "repository_id": FIXED_REPOSITORY_ID,
        "runner_arch": "X64",
        "runner_environment": "github-hosted",
        "runner_os": "Linux",
        "server_url": "https://github.com",
        "trusted_code_sha": trusted_code_sha,
        "workflow_ref": expected_caller_ref,
        "workflow_sha": candidate_sha,
    }
    for key, value in expected.items():
        if observed[key] != value:
            raise CommandRunnerError("GITHUB_IDENTITY_INVALID", f"GitHub {key} differs")
    if (
        os.environ.get("GITHUB_ACTIONS") != "true"
        or POSITIVE_INTEGER.fullmatch(observed["run_id"]) is None
        or POSITIVE_INTEGER.fullmatch(observed["run_attempt"]) is None
    ):
        raise CommandRunnerError(
            "GITHUB_IDENTITY_INVALID", "GitHub run identity differs"
        )
    return GitHubIdentity(observed)


def _candidate_snapshot(
    candidate_sha: str,
    *,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
) -> CandidateSnapshot:
    candidate_sha = _assert_sha1(candidate_sha, "candidate SHA")
    tree = _assert_sha1(
        _git_text("rev-parse", f"{candidate_sha}^{{tree}}"), "candidate tree"
    )
    scope_bytes = _git_blob(candidate_sha, SCOPE_PATH, max_bytes=256 * 1024)
    try:
        scope = candidate_scope.validate(candidate_sha, scope_bytes)
    except candidate_scope.CandidateScopeValidationError as exception:
        raise CommandRunnerError(
            "CANDIDATE_SCOPE_INVALID", str(exception)
        ) from exception
    if (
        scope.get("candidate_sha") != candidate_sha
        or scope.get("candidate_tree_sha") != tree
    ):
        raise CommandRunnerError(
            "CANDIDATE_SCOPE_INVALID", "candidate scope identity differs"
        )
    digest = scope.get("derived_inventory_sha256")
    _assert_sha256(digest, "candidate scope inventory")
    inventories = scope.get("materialization_inventories")
    if not isinstance(inventories, dict) or set(inventories) != {
        candidate_scope.FULL_REPOSITORY,
        candidate_scope.JAVA_SERVICE_ONLY,
    }:
        raise CommandRunnerError(
            "CANDIDATE_SCOPE_INVALID", "materialization inventories are absent"
        )
    try:
        transition, transition_sha256 = candidate_scope.validate_trusted_transition(
            candidate_sha=candidate_sha,
            trusted_code_sha=trusted_code_sha,
            trusted_workflow_sha=trusted_workflow_sha,
        )
        transition_plain = json.loads(
            candidate_scope.canonical_json_bytes(transition).decode("ascii")
        )
    except (
        candidate_scope.CandidateScopeValidationError,
        UnicodeDecodeError,
    ) as exception:
        raise CommandRunnerError(
            "TRUSTED_TRANSITION_INVALID", str(exception)
        ) from exception
    if (
        not isinstance(transition_plain, dict)
        or _canonical_sha256(transition_plain) != transition_sha256
    ):
        raise CommandRunnerError(
            "TRUSTED_TRANSITION_INVALID", "trusted transition projection hash differs"
        )
    return CandidateSnapshot(
        candidate_sha,
        tree,
        digest,
        scope,
        inventories,
        transition_plain,
        transition_sha256,
    )


def _materialize_candidate(
    snapshot: CandidateSnapshot,
    *,
    identity: GitHubIdentity,
    output_dir: Path,
    command_id: str,
    closure: str,
) -> Materialization:
    artifact_directory = output_dir / MATERIALIZATION_DIRECTORY
    artifact_directory.mkdir(mode=0o700)
    archive_temporary = artifact_directory / ".candidate.tar.tmp"
    expected_run_binding = _runtime_run_binding(identity)
    producer_identity = _runtime_job_identity(identity)
    inventory = snapshot.materialization_inventories.get(closure)
    if not isinstance(inventory, dict) or set(inventory) != {
        "entries",
        "file_count",
        "manifest_sha256",
        "total_bytes",
    }:
        raise CommandRunnerError(
            "CANDIDATE_SCOPE_INVALID", "materialization inventory shape differs"
        )
    entries = inventory["entries"]
    if not isinstance(entries, list) or not entries:
        raise CommandRunnerError(
            "CANDIDATE_SCOPE_INVALID", "materialization inventory is empty"
        )
    for entry in entries:
        _assert_ustar_path(entry["path"])
    validated, _runtime_manifest_sha, file_count, total_bytes = (
        runtime_policy.validate_materialization_manifest(entries)
    )
    scope_inventory_payload = {
        "entries": entries,
        "file_count": file_count,
        "inventory_kind": closure,
        "total_bytes": total_bytes,
    }
    if (
        validated != entries
        or inventory["file_count"] != file_count
        or inventory["total_bytes"] != total_bytes
        or inventory["manifest_sha256"] != _canonical_sha256(scope_inventory_payload)
    ):
        raise CommandRunnerError(
            "CANDIDATE_SCOPE_INVALID", "materialization inventory summary differs"
        )
    manifest: list[dict[str, Any]] = []
    try:
        with archive_temporary.open("xb") as archive_stream:
            with tarfile.open(
                fileobj=archive_stream, mode="w", format=tarfile.USTAR_FORMAT
            ) as candidate_archive:
                for expected in entries:
                    path = expected["path"]
                    mode = expected["mode"]
                    object_id = expected["git_blob_sha"]
                    payload = _git_bytes(
                        ("cat-file", "blob", object_id),
                        max_bytes=MAX_GIT_BLOB_BYTES,
                    )
                    if (
                        _git_blob_sha1(payload) != object_id
                        or len(payload) != expected["size"]
                        or _sha256(payload) != expected["sha256"]
                    ):
                        raise CommandRunnerError(
                            "OBJECT_SUBSTITUTED", "candidate blob hash differs"
                        )
                    manifest.append(copy.deepcopy(expected))
                    member = tarfile.TarInfo(path)
                    member.size = len(payload)
                    member.mode = 0o755 if mode == "100755" else 0o644
                    member.mtime = 0
                    member.uid = 0
                    member.gid = 0
                    member.uname = ""
                    member.gname = ""
                    candidate_archive.addfile(member, io.BytesIO(payload))
            archive_stream.flush()
            os.fsync(archive_stream.fileno())
        os.chmod(archive_temporary, 0o644)
        temporary_archive = _hash_stable_file(
            archive_temporary,
            max_bytes=runtime_policy.MAX_CANDIDATE_ARCHIVE_BYTES,
            context="candidate execution archive",
        )
        archive_path = artifact_directory / (
            f"candidate-sha256-{temporary_archive.sha256}.tar"
        )
        os.replace(archive_temporary, archive_path)
        os.chmod(archive_path, 0o644)
        archive = _hash_stable_file(
            archive_path,
            max_bytes=runtime_policy.MAX_CANDIDATE_ARCHIVE_BYTES,
            context="sealed candidate execution archive",
        )
        if (
            archive.bytes != temporary_archive.bytes
            or archive.sha256 != temporary_archive.sha256
        ):
            raise CommandRunnerError(
                "CANDIDATE_ARCHIVE_INVALID", "candidate archive changed during seal"
            )
        manifest.sort(key=lambda item: item["path"])
        validated_manifest, _manifest_list_sha, file_count, total_bytes = (
            runtime_policy.validate_materialization_manifest(manifest)
        )
        if validated_manifest != manifest or manifest != entries:
            raise CommandRunnerError(
                "MATERIALIZATION_INVALID", "manifest normalization drifted"
            )
        receipt = {
            "accepted_a8": candidate_scope.ACCEPTED_A8,
            "candidate_sha": snapshot.candidate_sha,
            "candidate_tree_sha": snapshot.candidate_tree_sha,
            "candidate_archive_bytes": archive.bytes,
            "candidate_archive_entry_count": len(manifest),
            "candidate_archive_format": runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
            "candidate_archive_sha256": archive.sha256,
            "closure_kind": closure,
            "command_id": command_id,
            "created_nonce": secrets.token_hex(32),
            "exact_git_blobs": True,
            "manifest_file_count": file_count,
            "manifest_sha256": inventory["manifest_sha256"],
            "manifest_total_bytes": total_bytes,
            "producer_job_identity": producer_identity,
            "producer_job_identity_sha256": runtime_policy.canonical_sha256(
                producer_identity
            ),
            "receipt_kind": runtime_policy.MATERIALIZATION_RECEIPT_KIND,
            "receipt_sha256": "",
            "schema_version": runtime_policy.MATERIALIZATION_RECEIPT_SCHEMA_VERSION,
            "scope_inventory_sha256": snapshot.scope_inventory_sha256,
            "verified_nonce": secrets.token_hex(32),
        }
        receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
        candidate_binding = {
            "accepted_entry_sha": candidate_scope.ACCEPTED_A8,
            "candidate_archive_bytes": archive.bytes,
            "candidate_archive_entry_count": len(manifest),
            "candidate_archive_format": runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
            "candidate_archive_sha256": archive.sha256,
            "candidate_sha": snapshot.candidate_sha,
            "candidate_tree_sha": snapshot.candidate_tree_sha,
            "closure_kind": closure,
            "derived_inventory_sha256": snapshot.scope_inventory_sha256,
            "manifest_file_count": inventory["file_count"],
            "manifest_sha256": inventory["manifest_sha256"],
            "manifest_total_bytes": inventory["total_bytes"],
        }
        validated = runtime_policy.assert_materialization_authorized_live(
            receipt,
            manifest,
            candidate_binding,
            inventory,
            expected_run_binding,
            archive.path,
        )
        if len(validated) != 3:
            raise CommandRunnerError(
                "CANDIDATE_ARCHIVE_INVALID", "runtime did not return an archive handle"
            )
        validated_archive = validated[2]
        validated_archive.close()
        return Materialization(
            manifest,
            receipt,
            candidate_binding,
            inventory,
            expected_run_binding,
            archive,
        )
    except Exception:
        with contextlib.suppress(OSError):
            archive_temporary.unlink()
        with contextlib.suppress(NameError, OSError):
            archive_path.unlink()
        raise


def _parse_junit(payload: bytes, context: str) -> JunitFacts:
    if not payload or len(payload) > MAX_JUNIT_BYTES:
        raise CommandRunnerError("JUNIT_INVALID", f"{context} size is invalid")
    if re.search(rb"<!\s*(?:doctype|entity)\b", payload, flags=re.IGNORECASE):
        raise CommandRunnerError("JUNIT_INVALID", f"{context} contains XML entities")
    if SECRET.search(payload.decode("utf-8", errors="ignore")):
        raise CommandRunnerError("CREDENTIAL_LEAK", f"{context} contains credentials")
    parser = ET.XMLPullParser(events=("start", "end"))
    testcase_ids: list[str] = []
    testcase_set: set[str] = set()
    suite_ids: set[str] = set()
    failures = errors = skipped = 0
    depth = nodes = open_testcases = 0
    root_tag: str | None = None

    def consume_events() -> None:
        nonlocal depth, errors, failures, nodes, open_testcases, root_tag, skipped
        for event, element in parser.read_events():
            tag = element.tag
            if not isinstance(tag, str) or "{" in tag or "}" in tag:
                raise CommandRunnerError(
                    "JUNIT_INVALID", f"{context} contains a namespaced XML node"
                )
            if event == "start":
                depth += 1
                nodes += 1
                if depth > MAX_JUNIT_XML_DEPTH or nodes > MAX_JUNIT_XML_NODES:
                    raise CommandRunnerError(
                        "JUNIT_INVALID", f"{context} exceeds its XML complexity budget"
                    )
                if root_tag is None:
                    root_tag = tag
                    if tag not in {"testsuite", "testsuites"}:
                        raise CommandRunnerError(
                            "JUNIT_INVALID", f"{context} root is not JUnit"
                        )
                if tag == "testsuite":
                    suite_name = element.attrib.get("name")
                    if suite_name:
                        if len(suite_name) > 512:
                            raise CommandRunnerError(
                                "JUNIT_INVALID", f"{context} has invalid suite identity"
                            )
                        suite_ids.add(suite_name)
                elif tag == "testcase":
                    classname = element.attrib.get("classname")
                    name = element.attrib.get("name")
                    if (
                        not classname
                        or not name
                        or len(classname) > 512
                        or len(name) > 512
                    ):
                        raise CommandRunnerError(
                            "JUNIT_INVALID", f"{context} has invalid testcase identity"
                        )
                    identity = f"{classname}::{name}"
                    if identity in testcase_set:
                        raise CommandRunnerError(
                            "JUNIT_INVALID", f"{context} duplicates a testcase"
                        )
                    testcase_set.add(identity)
                    testcase_ids.append(identity)
                    open_testcases += 1
                elif tag in {"failure", "error", "skipped"}:
                    if open_testcases <= 0:
                        raise CommandRunnerError(
                            "JUNIT_INVALID", f"{context} has an unbound testcase result"
                        )
                    failures += int(tag == "failure")
                    errors += int(tag == "error")
                    skipped += int(tag == "skipped")
            else:
                if tag == "testcase":
                    open_testcases -= 1
                    if open_testcases < 0:
                        raise CommandRunnerError(
                            "JUNIT_INVALID", f"{context} testcase nesting is invalid"
                        )
                element.clear()
                depth -= 1

    try:
        for offset in range(0, len(payload), 64 * 1024):
            parser.feed(payload[offset : offset + 64 * 1024])
            consume_events()
        parser.close()
        consume_events()
    except CommandRunnerError:
        raise
    except (ET.ParseError, RecursionError) as exception:
        raise CommandRunnerError(
            "JUNIT_INVALID", f"{context} is malformed"
        ) from exception
    if depth != 0 or open_testcases != 0 or not testcase_ids or not suite_ids:
        raise CommandRunnerError("JUNIT_INVALID", f"{context} is empty")
    return JunitFacts(
        len(testcase_ids),
        failures,
        errors,
        skipped,
        tuple(sorted(suite_ids)),
        tuple(sorted(testcase_ids)),
    )


def _sum_facts(facts: Iterable[JunitFacts]) -> JunitFacts:
    values = list(facts)
    return JunitFacts(
        sum(value.tests for value in values),
        sum(value.failures for value in values),
        sum(value.errors for value in values),
        sum(value.skipped for value in values),
        tuple(sorted({item for value in values for item in value.suite_ids})),
        tuple(sorted({item for value in values for item in value.testcase_ids})),
    )


def _output_summary(payload: bytes) -> dict[str, Any]:
    digest = _sha256(payload)
    text = payload.decode("utf-8", errors="replace")
    if SECRET.search(text):
        summary = "sensitive output suppressed"
    else:
        summary = "".join(
            character if character in "\t\r\n" or 32 <= ord(character) <= 126 else "?"
            for character in text
        )
        summary = summary[-MAX_SUMMARY_BYTES:]
    return {"bytes": len(payload), "sha256": digest, "summary": summary}


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CommandRunnerError("JSON_INVALID", f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _parse_json_bytes(payload: bytes, *, max_bytes: int, context: str) -> Any:
    if (
        not payload
        or len(payload) > max_bytes
        or payload.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff"))
    ):
        raise CommandRunnerError("JSON_INVALID", f"{context} byte shape is invalid")
    try:
        return json.loads(
            payload.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                CommandRunnerError("JSON_INVALID", f"{context} contains {token}")
            ),
        )
    except CommandRunnerError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise CommandRunnerError(
            "JSON_INVALID", f"{context} is not strict JSON"
        ) from exception


def _wheelhouse_manifest(wheelhouse: Path) -> list[dict[str, Any]]:
    entries: list[Path] = []
    try:
        with os.scandir(wheelhouse) as observed:
            for index, entry in enumerate(observed):
                if index >= 256:
                    raise CommandRunnerError(
                        "WHEELHOUSE_INVALID", "wheelhouse cardinality is invalid"
                    )
                metadata = entry.stat(follow_symlinks=False)
                if (
                    entry.is_symlink()
                    or not entry.is_file(follow_symlinks=False)
                    or _is_alias(metadata)
                ):
                    raise CommandRunnerError(
                        "WHEELHOUSE_INVALID", "wheelhouse contains a non-regular file"
                    )
                entries.append(Path(entry.path))
    except CommandRunnerError:
        raise
    except OSError as exception:
        raise CommandRunnerError(
            "WHEELHOUSE_INVALID", "wheelhouse cannot be enumerated"
        ) from exception
    entries.sort(key=lambda path: path.name)
    if not entries:
        raise CommandRunnerError(
            "WHEELHOUSE_INVALID", "wheelhouse cardinality is invalid"
        )
    manifest: list[dict[str, Any]] = []
    for path in entries:
        if SAFE_FILENAME.fullmatch(path.name) is None or not path.name.endswith(".whl"):
            raise CommandRunnerError(
                "WHEELHOUSE_INVALID", "wheelhouse contains a non-wheel"
            )
        item = _hash_stable_file(
            path, max_bytes=256 * 1024 * 1024, context=f"wheel {path.name}"
        )
        manifest.append(
            {"bytes": item.bytes, "filename": path.name, "sha256": item.sha256}
        )
    return manifest


def _docker_environment(home: Path) -> dict[str, str]:
    environment = _network_tool_environment(home)
    docker_config = home / "docker-config"
    docker_config.mkdir(mode=0o700, exist_ok=True)
    environment["DOCKER_CONFIG"] = str(docker_config)
    environment["DOCKER_BUILDKIT"] = "1"
    return environment


@contextlib.contextmanager
def _ephemeral_buildkit_builder(
    *, docker: Path, cwd: Path, environment: Mapping[str, str]
) -> Iterator[str]:
    buildkit_pull = _run_bounded(
        [
            str(docker),
            "image",
            "pull",
            "--platform=linux/amd64",
            runtime_policy.BUILDKIT_IMAGE,
        ],
        cwd=cwd,
        env=environment,
        timeout_seconds=600,
    )
    _require_success(
        buildkit_pull,
        "pinned BuildKit image acquisition",
        code="BUILDKIT_IMAGE_PULL_FAILED",
    )
    builder_name = f"phase8-buildkit-{secrets.token_hex(16)}"
    builder_creation_attempted = False
    try:
        builder_creation_attempted = True
        bootstrap = _run_bounded(
            [
                str(docker),
                "buildx",
                "create",
                "--name",
                builder_name,
                f"--driver={runtime_policy.BUILDX_DRIVER}",
                f"--driver-opt=image={runtime_policy.BUILDKIT_IMAGE}",
                "--platform=linux/amd64",
                "--bootstrap",
            ],
            cwd=cwd,
            env=environment,
            timeout_seconds=600,
        )
        _require_success(
            bootstrap,
            "private BuildKit builder bootstrap",
            code="BUILDER_BOOTSTRAP_FAILED",
        )
        yield builder_name
    finally:
        if builder_creation_attempted:
            primary_failure = sys.exc_info()[0] is not None
            try:
                cleanup = _run_bounded(
                    [str(docker), "buildx", "rm", "--force", builder_name],
                    cwd=cwd,
                    env=environment,
                    timeout_seconds=120,
                )
                if not primary_failure:
                    _require_success(
                        cleanup,
                        "private BuildKit builder cleanup",
                        code="BUILDER_CLEANUP_FAILED",
                    )
            except Exception as exception:
                if not primary_failure:
                    if isinstance(exception, CommandRunnerError):
                        raise
                    raise CommandRunnerError(
                        "BUILDER_CLEANUP_FAILED",
                        "private BuildKit builder cleanup did not complete successfully",
                    ) from exception


def _docker_load_and_inspect(
    archive: Path,
    *,
    expected_image_id: str | None,
    home: Path,
) -> dict[str, Any]:
    docker = _resolve_executable("docker")
    environment = _docker_environment(home)
    loaded = _run_bounded(
        [str(docker), "image", "load", "--input", str(archive)],
        cwd=home,
        env=environment,
        timeout_seconds=600,
    )
    _require_success(loaded, "Docker execution image load", code="IMAGE_LOAD_FAILED")
    candidates = re.findall(
        rb"sha256:[0-9a-f]{64}", loaded.stdout + b"\n" + loaded.stderr
    )
    image_ids = sorted({value.decode("ascii") for value in candidates})
    if expected_image_id is not None:
        _assert_sha256(expected_image_id.removeprefix("sha256:"), "expected image ID")
        image_id = expected_image_id
        if image_ids and image_id not in image_ids:
            raise CommandRunnerError(
                "IMAGE_IDENTITY_INVALID", "docker loaded another image"
            )
    elif len(image_ids) == 1:
        image_id = image_ids[0]
    else:
        raise CommandRunnerError(
            "IMAGE_IDENTITY_INVALID", "docker load image ID is ambiguous"
        )
    return _docker_inspect_projection(image_id, home=home)


def _docker_inspect_projection(image_reference: str, *, home: Path) -> dict[str, Any]:
    docker = _resolve_executable("docker")
    environment = _docker_environment(home)
    inspected = _run_bounded(
        [str(docker), "image", "inspect", image_reference],
        cwd=home,
        env=environment,
        timeout_seconds=120,
    )
    _require_success(inspected, "OCI image inspect")
    document = _parse_json_bytes(
        inspected.stdout, max_bytes=4 * 1024 * 1024, context="docker image inspect"
    )
    if (
        not isinstance(document, list)
        or len(document) != 1
        or not isinstance(document[0], dict)
    ):
        raise CommandRunnerError(
            "IMAGE_INSPECT_INVALID", "docker image inspect shape differs"
        )
    item = document[0]
    config = item.get("Config")
    rootfs = item.get("RootFS")
    if not isinstance(config, dict) or not isinstance(rootfs, dict):
        raise CommandRunnerError(
            "IMAGE_INSPECT_INVALID", "docker image config is absent"
        )
    values = config.get("Env")
    if not isinstance(values, list):
        raise CommandRunnerError(
            "IMAGE_INSPECT_INVALID", "docker image environment is absent"
        )
    exposed_ports = config.get("ExposedPorts")
    volumes = config.get("Volumes")
    if exposed_ports is not None and not isinstance(exposed_ports, dict):
        raise CommandRunnerError(
            "IMAGE_INSPECT_INVALID", "docker exposed ports are invalid"
        )
    if volumes is not None and not isinstance(volumes, dict):
        raise CommandRunnerError("IMAGE_INSPECT_INVALID", "docker volumes are invalid")
    layers = rootfs.get("Layers")
    if not isinstance(layers, list):
        raise CommandRunnerError(
            "IMAGE_INSPECT_INVALID", "docker rootfs layers are absent"
        )
    projection = {
        "architecture": item.get("Architecture"),
        "cmd": config.get("Cmd"),
        "config_digest": item.get("Id"),
        "entrypoint": config.get("Entrypoint"),
        "environment": values,
        "exposed_ports": sorted((exposed_ports or {}).keys()),
        "healthcheck": config.get("Healthcheck"),
        "image_id": item.get("Id"),
        "labels": config.get("Labels") or {},
        "onbuild": config.get("OnBuild") or [],
        "os": item.get("Os"),
        "rootfs_layers": layers,
        "shell": config.get("Shell") or [],
        "stop_signal": config.get("StopSignal"),
        "user": config.get("User"),
        "volumes": sorted((volumes or {}).keys()),
        "workdir": config.get("WorkingDir"),
    }
    if (
        image_reference.startswith("sha256:")
        and projection["image_id"] != image_reference
    ):
        raise CommandRunnerError(
            "IMAGE_IDENTITY_INVALID", "docker inspect image ID differs"
        )
    return projection


def _runtime_input_files() -> tuple[StableFile, StableFile]:
    dockerfile = _read_stable_file(
        TRUSTED_ROOT / "infra-tests" / "phase8" / "runtime" / "Dockerfile",
        max_bytes=256 * 1024,
        context="trusted Dockerfile",
    )
    lock = _read_stable_file(
        TRUSTED_ROOT / "infra-tests" / "phase8" / "runtime" / "requirements.lock",
        max_bytes=1024 * 1024,
        context="trusted requirements lock",
    )
    return dockerfile, lock


def _container_id_from_create(result: ProcessResult) -> str:
    _require_success(result, "container creation")
    try:
        container_id = result.stdout.decode("ascii", errors="strict").strip()
    except UnicodeDecodeError as exception:
        raise CommandRunnerError(
            "CONTAINER_ID_INVALID", "docker create returned a non-ASCII identity"
        ) from exception
    if SHA256.fullmatch(container_id) is None:
        raise CommandRunnerError(
            "CONTAINER_ID_INVALID", "docker create returned an ambiguous identity"
        )
    return container_id


def _run_created_container(
    *,
    docker: Path,
    create_argv: Sequence[str],
    cwd: Path,
    environment: Mapping[str, str],
    timeout_seconds: int,
) -> ProcessResult:
    created = _run_bounded(
        create_argv,
        cwd=cwd,
        env=environment,
        timeout_seconds=120,
    )
    container_id = _container_id_from_create(created)
    try:
        return _run_bounded(
            [str(docker), "start", "--attach", container_id],
            cwd=cwd,
            env=environment,
            timeout_seconds=timeout_seconds,
        )
    finally:
        active_exception = sys.exc_info()[0] is not None
        try:
            removed = _run_bounded(
                [str(docker), "rm", "--force", container_id],
                cwd=cwd,
                env=environment,
                timeout_seconds=120,
            )
            if not active_exception:
                _require_success(removed, "container removal")
        except CommandRunnerError:
            if not active_exception:
                raise


def _authorize_materialization_archive(
    materialization: Materialization,
) -> runtime_policy.ValidatedCandidateArchive:
    try:
        validated = runtime_policy.assert_materialization_authorized_live(
            materialization.receipt,
            materialization.manifest,
            materialization.candidate_binding,
            materialization.scope_inventory,
            materialization.expected_run_binding,
            materialization.archive.path,
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError(
            "CANDIDATE_ARCHIVE_INVALID", str(exception)
        ) from exception
    if len(validated) != 3:
        raise CommandRunnerError(
            "CANDIDATE_ARCHIVE_INVALID", "runtime did not return an archive handle"
        )
    return validated[2]


def _prove_rootless_dind(
    *,
    docker: Path,
    container_id: str,
    cwd: Path,
    environment: Mapping[str, str],
) -> None:
    deadline = time.monotonic() + DIND_READY_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        observed = _run_bounded(
            [
                str(docker),
                "exec",
                container_id,
                "docker",
                f"--host={MAVEN_DIND_LOCAL_HOST}",
                "info",
                "--format={{json .SecurityOptions}}",
            ],
            cwd=cwd,
            env=environment,
            timeout_seconds=10,
        )
        if (
            observed.exit_code == 0
            and not observed.timed_out
            and not observed.output_limited
        ):
            try:
                security_options = observed.stdout.decode("ascii", errors="strict")
            except UnicodeDecodeError as exception:
                raise CommandRunnerError(
                    "DIND_IDENTITY_INVALID", "DinD security options are not ASCII"
                ) from exception
            if '"name=rootless"' not in security_options:
                raise CommandRunnerError(
                    "DIND_IDENTITY_INVALID", "nested Docker daemon is not rootless"
                )
            user = _run_bounded(
                [str(docker), "exec", container_id, "id", "-u"],
                cwd=cwd,
                env=environment,
                timeout_seconds=10,
            )
            _require_success(user, "rootless DinD user proof")
            if user.stdout.strip() != b"1000":
                raise CommandRunnerError(
                    "DIND_IDENTITY_INVALID", "rootless DinD does not run as uid 1000"
                )
            return
        time.sleep(0.5)
    raise CommandRunnerError(
        _classify_dind_start_failure(
            docker=docker,
            container_id=container_id,
            cwd=cwd,
            environment=environment,
        ),
        "rootless DinD did not become ready",
    )


def _classify_dind_start_failure(
    *,
    docker: Path,
    container_id: str,
    cwd: Path,
    environment: Mapping[str, str],
) -> str:
    inspected = _run_bounded(
        [
            str(docker),
            "inspect",
            "--format={{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}",
            container_id,
        ],
        cwd=cwd,
        env=environment,
        timeout_seconds=10,
    )
    logged = _run_bounded(
        [str(docker), "logs", "--tail=200", container_id],
        cwd=cwd,
        env=environment,
        timeout_seconds=10,
    )
    diagnostics = (logged.stdout + b"\n" + logged.stderr).lower()
    signatures = (
        (b"need writable home", "DIND_HOME_UNWRITABLE"),
        (b"failed to start the child", "DIND_USER_NAMESPACE_DENIED"),
        (b"operation not permitted", "DIND_OPERATION_NOT_PERMITTED"),
        (b"permission denied", "DIND_PERMISSION_DENIED"),
        (b"no space left on device", "DIND_STORAGE_EXHAUSTED"),
        (b"address already in use", "DIND_PORT_CONFLICT"),
    )
    for signature, error_code in signatures:
        if signature in diagnostics:
            return error_code
    if inspected.exit_code != 0 or inspected.timed_out or inspected.output_limited:
        return "DIND_STATE_UNKNOWN"
    state = inspected.stdout.strip().lower()
    if state.endswith(b"|true"):
        return "DIND_OOM_KILLED"
    if state.startswith(b"exited|"):
        return "DIND_EXITED"
    if state.startswith(b"dead|"):
        return "DIND_DEAD"
    if state.startswith(b"running|"):
        return "DIND_PROBE_FAILED"
    return "DIND_UNAVAILABLE"


def _execute_maven_container(
    command: Mapping[str, Any],
    materialization: Materialization,
    home: Path,
    output_dir: Path,
) -> tuple[ProcessResult, list[dict[str, Any]], JunitFacts]:
    docker = _resolve_executable("docker")
    environment = _docker_environment(home)
    for image, context in (
        (MAVEN_IMAGE, "pinned Maven image acquisition"),
        (DIND_IMAGE, "pinned rootless DinD image acquisition"),
    ):
        pulled = _run_bounded(
            [str(docker), "image", "pull", "--platform=linux/amd64", image],
            cwd=home,
            env=environment,
            timeout_seconds=600,
        )
        _require_success(pulled, context)

    isolation_token = secrets.token_hex(16)
    network_name = f"phase8-maven-{isolation_token}"
    dind_name = f"phase8-dind-{isolation_token}"
    network_created = False
    dind_id: str | None = None
    container_id: str | None = None
    cleanup_error: CommandRunnerError | None = None
    candidate_environment = {
        **command["environment"],
        "DOCKER_HOST": MAVEN_DIND_HOST,
        "HOME": "/home/phase8",
        "MAVEN_USER_HOME": "/home/phase8/.m2",
        "TESTCONTAINERS_HOST_OVERRIDE": MAVEN_DIND_ALIAS,
        "TESTCONTAINERS_RYUK_DISABLED": MAVEN_RYUK_DISABLED,
    }
    maven_opts = candidate_environment.get("MAVEN_OPTS", "")
    if MAVEN_JANSI_OPTS not in maven_opts.split():
        candidate_environment["MAVEN_OPTS"] = f"{maven_opts} {MAVEN_JANSI_OPTS}".strip()
    if any(FORBIDDEN_ENV_KEY.search(key) for key in candidate_environment):
        raise CommandRunnerError(
            "ENVIRONMENT_FORBIDDEN", "Maven container environment contains credentials"
        )
    try:
        created_network = _run_bounded(
            [str(docker), "network", "create", "--driver=bridge", network_name],
            cwd=home,
            env=environment,
            timeout_seconds=120,
        )
        _require_success(created_network, "dedicated Maven network creation")
        network_created = True
        created_dind = _run_bounded(
            [
                str(docker),
                "create",
                "--pull=never",
                "--platform=linux/amd64",
                f"--name={dind_name}",
                f"--network={network_name}",
                f"--network-alias={MAVEN_DIND_ALIAS}",
                "--privileged",
                "--pids-limit=512",
                "--memory=4294967296",
                "--memory-swap=4294967296",
                "--cpus=2.0",
                f"--tmpfs={MAVEN_DIND_DATA_TMPFS}",
                "--env=DOCKER_TLS_CERTDIR=",
                DIND_IMAGE,
                "--host=tcp://0.0.0.0:2375",
                "--tls=false",
            ],
            cwd=home,
            env=environment,
            timeout_seconds=120,
        )
        dind_id = _container_id_from_create(created_dind)
        dind_started = _run_bounded(
            [str(docker), "start", dind_id],
            cwd=home,
            env=environment,
            timeout_seconds=120,
        )
        _require_success(dind_started, "rootless DinD start")
        _prove_rootless_dind(
            docker=docker,
            container_id=dind_id,
            cwd=home,
            environment=environment,
        )

        create_argv = [
            str(docker),
            "create",
            "--pull=never",
            "--platform=linux/amd64",
            f"--network={network_name}",
            "--read-only",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges:true",
            "--user=65532:65532",
            "--workdir=/workspace/java-api-service",
            "--pids-limit=512",
            "--memory=6442450944",
            "--memory-swap=6442450944",
            "--cpus=2.0",
            "--tmpfs=/tmp:rw,nosuid,nodev,noexec,size=536870912,mode=1777",
            "--tmpfs=/workspace:rw,nosuid,nodev,exec,size=2147483648,mode=0755",
            (
                "--tmpfs=/home/phase8:rw,nosuid,nodev,exec,size=2147483648,"
                "mode=0700,uid=65532,gid=65532"
            ),
            *(
                f"--env={key}={value}"
                for key, value in sorted(candidate_environment.items())
            ),
            MAVEN_IMAGE,
            "/bin/sleep",
            "3600",
        ]
        created = _run_bounded(
            create_argv,
            cwd=home,
            env=environment,
            timeout_seconds=120,
        )
        container_id = _container_id_from_create(created)
        started = _run_bounded(
            [str(docker), "start", container_id],
            cwd=home,
            env=environment,
            timeout_seconds=120,
        )
        _require_success(started, "Maven isolation container start")
        with _authorize_materialization_archive(materialization) as candidate_archive:
            seeded, _consumption_evidence = _run_bounded_with_verified_archive(
                [
                    str(docker),
                    "exec",
                    "--interactive",
                    "--user=0:0",
                    container_id,
                    *MAVEN_CANDIDATE_EXTRACTOR_ARGV,
                ],
                cwd=home,
                env=environment,
                timeout_seconds=600,
                archive=candidate_archive,
            )
        _require_success(seeded, "Maven candidate archive injection")
        prepared = _run_bounded(
            [
                str(docker),
                "exec",
                "--user=0:0",
                container_id,
                "/bin/mkdir",
                "-p",
                "-m",
                "0777",
                "/workspace/java-api-service/target",
            ],
            cwd=home,
            env=environment,
            timeout_seconds=120,
        )
        _require_success(prepared, "Maven target creation")
        process = _run_bounded(
            [
                str(docker),
                "exec",
                "--workdir=/workspace/java-api-service",
                container_id,
                *command["argv"],
            ],
            cwd=home,
            env=environment,
            timeout_seconds=command["timeout_seconds"],
        )
        source_root = (
            PurePosixPath("/workspace")
            / command["cwd"]
            / command["report"]["source_root"]
        ).as_posix()
        reports, facts = _copy_container_reports(
            docker=docker,
            docker_environment=environment,
            container_id=container_id,
            command=command,
            source_root=source_root,
            output_dir=output_dir,
            staging_root=home / "container-reports",
        )
        return process, reports, facts
    finally:
        active_exception = sys.exc_info()[0] is not None
        cleanup_commands: list[tuple[list[str], str]] = []
        if container_id is not None:
            cleanup_commands.append(
                (
                    [str(docker), "rm", "--force", container_id],
                    "Maven container removal",
                )
            )
        if dind_id is not None:
            cleanup_commands.append(
                ([str(docker), "rm", "--force", dind_id], "rootless DinD removal")
            )
        if network_created:
            cleanup_commands.append(
                ([str(docker), "network", "rm", network_name], "Maven network removal")
            )
        for argv, context in cleanup_commands:
            try:
                removed = _run_bounded(
                    argv, cwd=home, env=environment, timeout_seconds=120
                )
                _require_success(removed, context)
            except CommandRunnerError as exception:
                if cleanup_error is None:
                    cleanup_error = exception
        if not active_exception and cleanup_error is not None:
            raise cleanup_error


def _revalidate_materialization(materialization: Materialization) -> None:
    validated = runtime_policy.assert_materialization_authorized_live(
        materialization.receipt,
        materialization.manifest,
        materialization.candidate_binding,
        materialization.scope_inventory,
        materialization.expected_run_binding,
        materialization.archive.path,
    )
    if len(validated) != 3:
        raise CommandRunnerError(
            "CANDIDATE_ARCHIVE_INVALID", "runtime did not return an archive handle"
        )
    validated[2].close()


def _write_raw_result(
    *,
    output_dir: Path,
    identity: GitHubIdentity,
    snapshot: CandidateSnapshot,
    command: Mapping[str, Any],
    order: int,
    materialization: Materialization,
    process: ProcessResult,
    reports: list[dict[str, Any]],
    facts: JunitFacts,
    runtime: Mapping[str, Any] | None,
    contract_payload_sha256: str,
) -> dict[str, Any]:
    if (
        snapshot.trusted_transition is None
        or snapshot.trusted_transition_sha256 is None
    ):
        raise CommandRunnerError(
            "TRUSTED_TRANSITION_INVALID", "trusted transition projection is absent"
        )
    passed = (
        process.exit_code == 0
        and not process.timed_out
        and not process.output_limited
        and facts.tests > 0
        and facts.failures == 0
        and facts.errors == 0
        and facts.skipped == 0
    )
    result = {
        "attempt_id": identity.attempt_id,
        "authority": RAW_AUTHORITY,
        "candidate": {
            "candidate_sha": snapshot.candidate_sha,
            "candidate_tree_sha": snapshot.candidate_tree_sha,
            "scope_inventory_sha256": snapshot.scope_inventory_sha256,
            "trusted_transition": copy.deepcopy(snapshot.trusted_transition),
            "trusted_transition_sha256": snapshot.trusted_transition_sha256,
        },
        "command": {
            **command,
            "contract_payload_sha256": contract_payload_sha256,
            "order": order,
        },
        "execution": {
            "exit_code": process.exit_code,
            "output_limited": process.output_limited,
            "report_totals": facts.as_dict(),
            "status": "PASSED" if passed else "FAILED",
            "stderr": _output_summary(process.stderr),
            "stdout": _output_summary(process.stdout),
            "timed_out": process.timed_out,
        },
        "github": identity.values,
        "materialization": {},
        "reports": reports,
        "runtime": dict(runtime) if runtime is not None else None,
        "schema_version": RAW_SCHEMA_VERSION,
    }
    command_directory = COMMAND_DIRECTORIES[command["id"]]
    materialization_dir = output_dir / MATERIALIZATION_DIRECTORY
    materialization_dir.mkdir(mode=0o700, exist_ok=True)
    manifest_file = _atomic_json(
        materialization_dir / MATERIALIZATION_MANIFEST_NAME,
        materialization.manifest,
    )
    receipt_file = _atomic_json(
        materialization_dir / MATERIALIZATION_RECEIPT_NAME,
        materialization.receipt,
    )
    result["materialization"] = {
        "candidate_archive_ref": _hashed_file_reference(
            materialization.archive,
            f"commands/{command_directory}/materialization/"
            f"{materialization.archive.path.name}",
        ),
        "manifest_ref": _file_reference(
            manifest_file,
            f"commands/{command_directory}/materialization/{MATERIALIZATION_MANIFEST_NAME}",
        ),
        "receipt_ref": _file_reference(
            receipt_file,
            f"commands/{command_directory}/materialization/{MATERIALIZATION_RECEIPT_NAME}",
        ),
    }
    _atomic_json(output_dir / RESULT_NAME, result)
    return result


def _load_command(command_id: str) -> tuple[dict[str, Any], int, str]:
    if command_id not in command_contract.COMMAND_ORDER:
        raise CommandRunnerError(
            "COMMAND_INVALID", "command ID is not in the frozen order"
        )
    contract = command_contract.load_command_contract()
    order = list(command_contract.COMMAND_ORDER).index(command_id)
    command = contract["commands"][order]
    if command["id"] != command_id:
        raise CommandRunnerError("COMMAND_INVALID", "command order drifted")
    return command, order, contract["self_seal"]["payload_sha256"]


def _destroy_trusted_checkout_before_maven() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    workspace_text = os.environ.get("GITHUB_WORKSPACE", "")
    if not workspace_text:
        raise CommandRunnerError("ISOLATION_FAILED", "GitHub workspace is unavailable")
    workspace = Path(workspace_text).resolve(strict=True)
    trusted = TRUSTED_ROOT.resolve(strict=True)
    if trusted.parent != workspace or trusted.name != "trusted-code":
        raise CommandRunnerError(
            "ISOLATION_FAILED", "trusted checkout is not the fixed workspace child"
        )
    os.chdir(workspace)
    shutil.rmtree(trusted)
    if trusted.exists() or trusted.is_symlink():
        raise CommandRunnerError(
            "ISOLATION_FAILED", "trusted checkout remained reachable"
        )


def execute_command(
    *,
    command_id: str,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
    output_dir: Path,
    image_archive: Path | None = None,
    execution_image_archive: Path | None = None,
    observer_image_archive: Path | None = None,
    observer_execution_image_archive: Path | None = None,
    runtime_build_receipt_path: Path | None = None,
    build_observation_receipt_path: Path | None = None,
    wheelhouse_root: Path | None = None,
) -> dict[str, Any]:
    expected_job = COMMAND_JOBS.get(command_id)
    if expected_job is None:
        raise CommandRunnerError("COMMAND_INVALID", "command job is not frozen")
    identity = _github_identity(
        expected_job=expected_job,
        candidate_sha=candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
        trusted_workflow_ref=trusted_workflow_ref,
        trusted_workflow_repository=trusted_workflow_repository,
        trusted_workflow_file_path=trusted_workflow_file_path,
    )
    _trusted_snapshot(trusted_code_sha)
    snapshot = _candidate_snapshot(
        candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
    )
    command, order, contract_payload_sha256 = _load_command(command_id)
    output_dir = _fresh_output_directory(output_dir)
    closure = (
        candidate_scope.FULL_REPOSITORY
        if command_id in STATIC_COMMANDS
        else candidate_scope.JAVA_SERVICE_ONLY
    )
    materialization = _materialize_candidate(
        snapshot,
        identity=identity,
        output_dir=output_dir,
        command_id=command_id,
        closure=closure,
    )
    home = output_dir.parent / f"phase8-home-{command_id}-{secrets.token_hex(16)}"
    home.mkdir(mode=0o700)
    runtime_result: Mapping[str, Any] | None = None
    try:
        if command_id in STATIC_COMMANDS:
            if None in (
                image_archive,
                execution_image_archive,
                observer_image_archive,
                observer_execution_image_archive,
                runtime_build_receipt_path,
                build_observation_receipt_path,
                wheelhouse_root,
            ):
                raise CommandRunnerError(
                    "RUNTIME_INPUT_MISSING",
                    "static command lacks observed runtime inputs",
                )
            process, runtime_result, reports, facts = _execute_static(
                command=command,
                identity=identity,
                snapshot=snapshot,
                materialization=materialization,
                output_dir=output_dir,
                home=home,
                image_archive=image_archive,
                execution_image_archive=execution_image_archive,
                observer_image_archive=observer_image_archive,
                observer_execution_image_archive=observer_execution_image_archive,
                runtime_build_receipt_path=runtime_build_receipt_path,
                build_observation_receipt_path=build_observation_receipt_path,
                wheelhouse_root=wheelhouse_root,
            )
        else:
            if any(
                value is not None
                for value in (
                    image_archive,
                    execution_image_archive,
                    observer_image_archive,
                    observer_execution_image_archive,
                    runtime_build_receipt_path,
                    build_observation_receipt_path,
                    wheelhouse_root,
                )
            ):
                raise CommandRunnerError(
                    "RUNTIME_INPUT_FORBIDDEN",
                    "Maven command received static runtime inputs",
                )
            executable_entry = next(
                (
                    item
                    for item in materialization.manifest
                    if item["path"] == command["executable_path"]
                ),
                None,
            )
            if (
                executable_entry is None
                or executable_entry["mode"] != command["executable_mode"]
            ):
                raise CommandRunnerError(
                    "MAVEN_WRAPPER_INVALID", "Maven wrapper is absent from the archive"
                )
            if ["./mvnw", *command["argv"][1:]] != command["argv"]:
                raise CommandRunnerError(
                    "COMMAND_INVALID", "Maven argv differs from contract"
                )
            _assert_no_oidc_capability()
            _destroy_trusted_checkout_before_maven()
            process, reports, facts = _execute_maven_container(
                command, materialization, home, output_dir
            )
        _revalidate_materialization(materialization)
        return _write_raw_result(
            output_dir=output_dir,
            identity=identity,
            snapshot=snapshot,
            command=command,
            order=order,
            materialization=materialization,
            process=process,
            reports=reports,
            facts=facts,
            runtime=runtime_result,
            contract_payload_sha256=contract_payload_sha256,
        )
    finally:
        with contextlib.suppress(OSError):
            shutil.rmtree(home)


def _execute_static(
    *,
    command: Mapping[str, Any],
    identity: GitHubIdentity,
    snapshot: CandidateSnapshot,
    materialization: Materialization,
    output_dir: Path,
    home: Path,
    image_archive: Path | None,
    execution_image_archive: Path | None,
    observer_image_archive: Path | None,
    observer_execution_image_archive: Path | None,
    runtime_build_receipt_path: Path | None,
    build_observation_receipt_path: Path | None,
    wheelhouse_root: Path | None,
) -> tuple[ProcessResult, Mapping[str, Any], list[dict[str, Any]], JunitFacts]:
    if None in (
        image_archive,
        execution_image_archive,
        observer_image_archive,
        observer_execution_image_archive,
        runtime_build_receipt_path,
        build_observation_receipt_path,
        wheelhouse_root,
    ):
        raise CommandRunnerError(
            "RUNTIME_INPUT_MISSING", "static command lacks observed runtime inputs"
        )
    assert image_archive is not None
    assert execution_image_archive is not None
    assert observer_image_archive is not None
    assert observer_execution_image_archive is not None
    assert runtime_build_receipt_path is not None
    assert build_observation_receipt_path is not None
    assert wheelhouse_root is not None

    contract = command_contract.load_command_contract()
    policy = runtime_policy.load_runtime_policy()
    build_file = _read_stable_file(
        runtime_build_receipt_path,
        max_bytes=runtime_policy.MAX_RECEIPT_BYTES,
        context="runtime build receipt",
    )
    observation_file = _read_stable_file(
        build_observation_receipt_path,
        max_bytes=runtime_policy.MAX_RECEIPT_BYTES,
        context="build observation receipt",
    )
    wheel_manifest_file = _read_stable_file(
        wheelhouse_root.parent / WHEELHOUSE_MANIFEST_NAME,
        max_bytes=MAX_JSON_BYTES,
        context="shared wheelhouse manifest",
    )
    archive = _hash_stable_file(
        image_archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="observed OCI archive",
    )
    observer_archive = _hash_stable_file(
        observer_image_archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="independent observer OCI archive",
    )
    docker_archive = _hash_stable_file(
        execution_image_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="observed producer Docker execution archive",
    )
    observer_docker_archive = _hash_stable_file(
        observer_execution_image_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="independent observer Docker execution archive",
    )
    try:
        build_receipt = runtime_policy.parse_receipt_json_bytes(build_file.payload)
        build_observation = runtime_policy.parse_receipt_json_bytes(
            observation_file.payload
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID", str(exception)
        ) from exception
    for receipt, key, expected_job in (
        (build_receipt, "builder_job_identity", BUILD_JOB),
        (build_observation, "observer_job_identity", OBSERVE_JOB),
    ):
        runtime_identity = receipt.get(key)
        if not isinstance(runtime_identity, dict):
            raise CommandRunnerError(
                "RUNTIME_BINDING_INVALID", f"{key} is absent from the runtime receipt"
            )
        _assert_runtime_job_matches(
            runtime_identity, identity, expected_job=expected_job
        )

    observation_binding_keys = (
        "base_image_inspect_projection",
        "base_image_inspect_projection_sha256",
        "build_provenance",
        "build_provenance_sha256",
        "observer_build_parameters",
        "observer_build_parameters_sha256",
        "observer_image_inspect_projection",
        "observer_image_inspect_projection_sha256",
        "observer_job_identity",
        "observer_job_identity_sha256",
        "observer_docker_archive_bytes",
        "observer_docker_archive_sha256",
        "observer_oci_archive_bytes",
        "observer_oci_archive_sha256",
        "producer_image_inspect_projection",
        "producer_image_inspect_projection_sha256",
        "producer_docker_archive_bytes",
        "producer_docker_archive_sha256",
        "producer_oci_archive_bytes",
        "producer_oci_archive_sha256",
        "source_build_receipt_sha256",
        "wheelhouse_manifest",
        "wheelhouse_manifest_sha256",
    )
    if any(key not in build_observation for key in observation_binding_keys):
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID", "build observation binding is incomplete"
        )
    expected_observation_binding = {
        key: copy.deepcopy(build_observation[key]) for key in observation_binding_keys
    }
    try:
        validated_materialization, candidate_binding, materialization_archive = (
            runtime_policy.assert_materialization_authorized_live(
                materialization.receipt,
                materialization.manifest,
                materialization.candidate_binding,
                materialization.scope_inventory,
                materialization.expected_run_binding,
                materialization.archive.path,
            )
        )
        materialization_archive.close()
        expected_builder_identity = _runtime_job_identity(identity)
        expected_builder_identity["job_name"] = BUILD_JOB
        validated_build, build_binding, validated_observation = (
            runtime_policy.validate_runtime_build_receipt(
                build_receipt,
                build_observation,
                expected_observation_binding,
                expected_run_binding=materialization.expected_run_binding,
                expected_builder_job_identity=expected_builder_identity,
                producer_oci_archive_path=image_archive,
                producer_docker_archive_path=execution_image_archive,
                policy=policy,
                validated_command_contract=contract,
            )
        )
        validated_shared_runtime = runtime_policy.verify_shared_runtime_receipts(
            build_receipt,
            build_observation,
            expected_observation_binding,
            expected_run_binding=materialization.expected_run_binding,
            expected_builder_job_identity=expected_builder_identity,
            producer_oci_archive_path=image_archive,
            observer_oci_archive_path=observer_image_archive,
            producer_docker_archive_path=execution_image_archive,
            observer_docker_archive_path=observer_execution_image_archive,
            wheelhouse_root=wheelhouse_root,
            policy=policy,
            validated_command_contract=contract,
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID", str(exception)
        ) from exception
    if (
        validated_build["code_sha"] != snapshot.candidate_sha
        or validated_build["code_tree_sha"] != snapshot.candidate_tree_sha
    ):
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID", "shared runtime belongs to another candidate"
        )

    projection = _docker_load_and_inspect(
        execution_image_archive,
        expected_image_id=validated_build["image_id"],
        home=home,
    )
    if (
        projection != validated_observation["producer_image_inspect_projection"]
        or projection != validated_observation["observer_image_inspect_projection"]
    ):
        raise CommandRunnerError(
            "OCI_SUBSTITUTED", "loaded image differs from the independent observation"
        )

    candidate_source = {
        **copy.deepcopy(policy["runtime"]["candidate_source"]),
        "archive_bytes": materialization.archive.bytes,
        "archive_entry_count": len(materialization.manifest),
        "archive_sha256": materialization.archive.sha256,
        "materialization_receipt_sha256": validated_materialization["receipt_sha256"],
    }
    fixed_environment = copy.deepcopy(policy["runtime"]["fixed_environment"])
    environment_arguments = [
        f"--env={key}={value}" for key, value in sorted(fixed_environment.items())
    ]
    create_argv = [
        "docker",
        "create",
        *policy["runtime"]["required_flags"],
        *environment_arguments,
        validated_build["image_id"],
        *runtime_policy.TRUSTED_SLEEPER_ARGV,
    ]
    candidate_copy_argv = [
        "docker",
        "exec",
        "--interactive",
        "--user=0:0",
        runtime_policy.CONTAINER_ID_TOKEN,
        "/usr/local/bin/python",
        "-c",
        runtime_policy.TRUSTED_CANDIDATE_EXTRACTOR_SCRIPT,
        str(materialization.archive.bytes),
        materialization.archive.sha256,
        str(len(materialization.manifest)),
    ]
    start_argv = ["docker", "start", runtime_policy.CONTAINER_ID_TOKEN]
    exec_argv = [
        "docker",
        "exec",
        "--workdir=/workspace",
        runtime_policy.CONTAINER_ID_TOKEN,
        *command["argv"],
    ]
    dispatch = {
        "accepted_a8": candidate_binding["accepted_entry_sha"],
        "backend_kind": runtime_policy.STATIC_BACKEND_KIND,
        "build_observation_receipt_sha256": validated_observation["receipt_sha256"],
        "build_identity_sha256": runtime_policy.canonical_sha256(build_binding),
        "candidate_binding_sha256": runtime_policy.canonical_sha256(candidate_binding),
        "candidate_source": candidate_source,
        "candidate_sha": candidate_binding["candidate_sha"],
        "candidate_tree_sha": candidate_binding["candidate_tree_sha"],
        "closure_kind": candidate_binding["closure_kind"],
        "command_id": command["id"],
        "cwd": command["cwd"],
        "candidate_copy_argv": candidate_copy_argv,
        "container_id_source": "STDOUT_OF_CREATE_ARGV",
        "container_id_token": runtime_policy.CONTAINER_ID_TOKEN,
        "create_argv": create_argv,
        "exec_argv": exec_argv,
        "fixed_env": fixed_environment,
        "image_id": validated_build["image_id"],
        "inner_argv": copy.deepcopy(command["argv"]),
        "materialization_receipt_sha256": validated_materialization["receipt_sha256"],
        "manifest_file_count": candidate_binding["manifest_file_count"],
        "manifest_sha256": candidate_binding["manifest_sha256"],
        "manifest_total_bytes": candidate_binding["manifest_total_bytes"],
        "network": policy["runtime"]["network"],
        "oci_archive_sha256": build_binding["oci_archive_sha256"],
        "report": copy.deepcopy(command["report"]),
        "resources": copy.deepcopy(policy["runtime"]["resources"]),
        "runtime_build_receipt_sha256": validated_build["receipt_sha256"],
        "scope_inventory_sha256": candidate_binding["derived_inventory_sha256"],
        "start_argv": start_argv,
        "timeout_seconds": command["timeout_seconds"],
        "tmpfs": copy.deepcopy(policy["runtime"]["resources"]["tmpfs"]),
        "user": policy["runtime"]["user"],
    }
    try:
        authorization = runtime_policy.assert_static_dispatch_authorized(
            command,
            dispatch,
            policy,
            materialization_receipt=materialization.receipt,
            materialization_manifest=materialization.manifest,
            expected_candidate_binding=materialization.candidate_binding,
            expected_scope_inventory=materialization.scope_inventory,
            expected_run_binding=materialization.expected_run_binding,
            candidate_archive_path=materialization.archive.path,
            validated_command_contract=contract,
            validated_shared_runtime=validated_shared_runtime,
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError(
            "STATIC_DISPATCH_INVALID", str(exception)
        ) from exception
    if not isinstance(authorization, tuple) or len(authorization) != 2:
        raise CommandRunnerError(
            "STATIC_DISPATCH_INVALID",
            "runtime authorization did not return an archive handle",
        )
    dispatch_sha256, authorized_archive = authorization

    runtime_dir = output_dir / "runtime"
    runtime_dir.mkdir(mode=0o700)
    dispatch_file = _atomic_json(runtime_dir / "dispatch.json", dispatch)
    docker = _resolve_executable("docker")
    docker_environment = _docker_environment(home)
    create_argv = [str(docker), *dispatch["create_argv"][1:]]
    try:
        created = _run_bounded(
            create_argv,
            cwd=home,
            env=docker_environment,
            timeout_seconds=120,
        )
        _require_success(created, "static container creation")
        container_id = _container_id_from_create(created)
    except Exception:
        authorized_archive.close()
        raise

    process: ProcessResult
    reports: list[dict[str, Any]]
    facts: JunitFacts
    try:
        start_argv = [
            str(docker),
            *(
                container_id if value == runtime_policy.CONTAINER_ID_TOKEN else value
                for value in dispatch["start_argv"][1:]
            ),
        ]
        started = _run_bounded(
            start_argv,
            cwd=home,
            env=docker_environment,
            timeout_seconds=120,
        )
        _require_success(started, "static isolation container start")
        with authorized_archive:
            candidate_copy_argv = [
                str(docker),
                *(
                    container_id
                    if value == runtime_policy.CONTAINER_ID_TOKEN
                    else value
                    for value in dispatch["candidate_copy_argv"][1:]
                ),
            ]
            candidate_copy_argv[-1] = candidate_copy_argv[-1].replace(
                runtime_policy.CONTAINER_ID_TOKEN, container_id
            )
            seeded, _consumption_evidence = _run_bounded_with_verified_archive(
                candidate_copy_argv,
                cwd=home,
                env=docker_environment,
                timeout_seconds=600,
                archive=authorized_archive,
            )
        _require_success(seeded, "static candidate archive injection")
        exec_argv = [
            str(docker),
            *(
                container_id if value == runtime_policy.CONTAINER_ID_TOKEN else value
                for value in dispatch["exec_argv"][1:]
            ),
        ]
        process = _run_bounded(
            exec_argv,
            cwd=home,
            env=docker_environment,
            timeout_seconds=command["timeout_seconds"],
        )
        reports, facts = _copy_container_reports(
            docker=docker,
            docker_environment=docker_environment,
            container_id=container_id,
            command=command,
            source_root=command["report"]["source_root"],
            output_dir=output_dir,
            staging_root=home / "container-reports",
        )
    finally:
        active_exception = sys.exc_info()[0] is not None
        try:
            removed = _run_bounded(
                [str(docker), "rm", "--force", container_id],
                cwd=home,
                env=docker_environment,
                timeout_seconds=120,
            )
            if not active_exception:
                _require_success(removed, "static container removal")
        except CommandRunnerError:
            if not active_exception:
                raise

    report_by_name = {Path(item["path"]).name: item for item in reports}
    junit_index = sorted(
        (
            {
                "archive_path": artifact["archive_path"],
                "bytes": report_by_name[artifact["filename"]]["bytes"],
                "sha256": report_by_name[artifact["filename"]]["sha256"],
            }
            for artifact in command["report"]["expected_artifacts"]
        ),
        key=lambda item: item["archive_path"],
    )
    producer_identity = _runtime_job_identity(identity)
    artifact_name = (
        f"{runtime_policy.STATIC_COMMAND_ARTIFACT_PREFIXES[command['id']]}-"
        f"{identity.values['run_id']}-{identity.values['run_attempt']}"
    )
    transport_receipt = {
        "artifact_name": artifact_name,
        "artifact_payload_kind": runtime_policy.ARTIFACT_PAYLOAD_KIND,
        "artifact_payload_sha256": (
            runtime_policy.canonical_junit_file_index_sha256(junit_index)
        ),
        "build_observation_receipt_sha256": validated_observation["receipt_sha256"],
        "command_id": command["id"],
        "dispatch_sha256": dispatch_sha256,
        "materialization_receipt_sha256": validated_materialization["receipt_sha256"],
        "manifest_sha256": validated_materialization["manifest_sha256"],
        "oci_archive_sha256": archive.sha256,
        "producer_job_identity": producer_identity,
        "producer_job_identity_sha256": runtime_policy.canonical_sha256(
            producer_identity
        ),
        "receipt_kind": runtime_policy.ARTIFACT_TRANSPORT_RECEIPT_KIND,
        "receipt_sha256": "",
        "runtime_build_receipt_sha256": validated_build["receipt_sha256"],
        "schema_version": runtime_policy.ARTIFACT_TRANSPORT_RECEIPT_SCHEMA_VERSION,
        "transport_nonce": secrets.token_hex(32),
    }
    transport_receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(
        transport_receipt
    )
    transport_binding = {
        key: copy.deepcopy(value)
        for key, value in transport_receipt.items()
        if key
        not in {"receipt_kind", "receipt_sha256", "schema_version", "transport_nonce"}
    }
    try:
        runtime_policy.validate_artifact_transport_receipt(
            transport_receipt,
            transport_binding,
            expected_run_binding=materialization.expected_run_binding,
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError(
            "ARTIFACT_TRANSPORT_INVALID", str(exception)
        ) from exception
    transport_file = _atomic_json(
        runtime_dir / "artifact-transport-receipt.json", transport_receipt
    )

    _revalidate_file(
        build_file,
        max_bytes=runtime_policy.MAX_RECEIPT_BYTES,
        context="runtime build receipt",
    )
    _revalidate_file(
        observation_file,
        max_bytes=runtime_policy.MAX_RECEIPT_BYTES,
        context="build observation receipt",
    )
    _revalidate_file(
        wheel_manifest_file,
        max_bytes=MAX_JSON_BYTES,
        context="shared wheelhouse manifest",
    )
    _revalidate_hashed_file(
        archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="observed OCI archive",
    )
    _revalidate_hashed_file(
        observer_archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="independent observer OCI archive",
    )
    _revalidate_hashed_file(
        docker_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="producer Docker execution archive",
    )
    _revalidate_hashed_file(
        observer_docker_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="observer Docker execution archive",
    )
    command_directory = COMMAND_DIRECTORIES[command["id"]]
    runtime_result = {
        "artifact_transport_receipt_ref": _file_reference(
            transport_file,
            f"commands/{command_directory}/runtime/artifact-transport-receipt.json",
        ),
        "build_observation_receipt_ref": _file_reference(
            observation_file,
            "shared-runtime/observer/build-observation-receipt.json",
        ),
        "dispatch_ref": _file_reference(
            dispatch_file, f"commands/{command_directory}/runtime/dispatch.json"
        ),
        "observer_oci_archive_ref": _hashed_file_reference(
            observer_archive,
            f"shared-runtime/observer/oci/sha256-{observer_archive.sha256}.tar",
        ),
        "observer_docker_archive_ref": _hashed_file_reference(
            observer_docker_archive,
            "shared-runtime/observer/docker/"
            f"sha256-{observer_docker_archive.sha256}.tar",
        ),
        "producer_docker_archive_ref": _hashed_file_reference(
            docker_archive,
            f"shared-runtime/producer/docker/sha256-{docker_archive.sha256}.tar",
        ),
        "producer_oci_archive_ref": _hashed_file_reference(
            archive,
            f"shared-runtime/producer/oci/sha256-{archive.sha256}.tar",
        ),
        "runtime_build_receipt_ref": _file_reference(
            build_file, "shared-runtime/producer/runtime-build-receipt.json"
        ),
        "wheelhouse_manifest_ref": _file_reference(
            wheel_manifest_file, "shared-runtime/producer/wheelhouse-manifest.json"
        ),
    }
    return process, runtime_result, reports, facts


def _validate_report_transport_aliases(
    report_aliases: Sequence[tuple[str, str]],
) -> tuple[tuple[str, str], ...]:
    validated: list[tuple[str, str]] = []
    logical_names: set[str] = set()
    alias_names: set[str] = set()
    quarantine_names: set[str] = set()
    for pair in report_aliases:
        if (
            not isinstance(pair, (list, tuple))
            or len(pair) != 2
            or not all(isinstance(value, str) for value in pair)
        ):
            raise CommandRunnerError(
                "JUNIT_INVALID", "report transport alias is unsafe"
            )
        logical_name, alias_name = pair
        if (
            SAFE_FILENAME.fullmatch(logical_name) is None
            or SAFE_FILENAME.fullmatch(alias_name) is None
            or "/" in logical_name
            or "\\" in logical_name
            or "/" in alias_name
            or "\\" in alias_name
            or len(f"./{alias_name}".encode("utf-8")) > USTAR_MEMBER_NAME_BYTES
        ):
            raise CommandRunnerError(
                "JUNIT_INVALID", "report transport alias is unsafe"
            )
        logical_key = logical_name.casefold()
        alias_key = alias_name.casefold()
        quarantine_name = _report_quarantine_name(len(validated), alias_name)
        quarantine_key = quarantine_name.casefold()
        if (
            SAFE_FILENAME.fullmatch(quarantine_name) is None
            or len(f"./{quarantine_name}".encode("utf-8"))
            > USTAR_MEMBER_NAME_BYTES
        ):
            raise CommandRunnerError(
                "JUNIT_INVALID", "report transport alias is unsafe"
            )
        if logical_key in logical_names or alias_key in alias_names:
            raise CommandRunnerError(
                "JUNIT_INVALID", "report transport alias is duplicate"
            )
        logical_names.add(logical_key)
        alias_names.add(alias_key)
        quarantine_names.add(quarantine_key)
        validated.append((logical_name, alias_name))
    if (
        logical_names & alias_names
        or logical_names & quarantine_names
        or alias_names & quarantine_names
        or len(quarantine_names) != len(validated)
    ):
        raise CommandRunnerError(
            "JUNIT_INVALID", "report transport alias collides with a logical filename"
        )
    return tuple(validated)


def _report_transport_aliases(
    command: Mapping[str, Any],
) -> tuple[tuple[str, str], ...]:
    aliases: list[tuple[str, str]] = []
    for index, artifact in enumerate(command["report"]["expected_artifacts"]):
        logical_name = artifact["filename"]
        if not isinstance(logical_name, str):
            raise CommandRunnerError(
                "JUNIT_INVALID", "contract JUnit filename is unsafe"
            )
        digest = hashlib.sha256(logical_name.encode("utf-8")).hexdigest()[:16]
        alias_name = f"{REPORT_TRANSPORT_ALIAS_PREFIX}-{index:03d}-{digest}.xml"
        aliases.append((logical_name, alias_name))
    return _validate_report_transport_aliases(aliases)


def _report_transform_expression(logical_name: str, alias_name: str) -> str:
    # SAFE_FILENAME excludes every BRE metacharacter except '.', which must be literal.
    escaped_logical = logical_name.replace(".", r"\.")
    return f"s|^\\./{escaped_logical}$|./{alias_name}|"


def _report_quarantine_name(index: int, alias_name: str) -> str:
    digest = hashlib.sha256(alias_name.encode("utf-8")).hexdigest()[:16]
    return f"{REPORT_TRANSPORT_QUARANTINE_PREFIX}-{index:03d}-{digest}.xml"


def _report_transform_argv(
    report_aliases: Sequence[tuple[str, str]],
) -> list[str]:
    aliases = _validate_report_transport_aliases(report_aliases)
    quarantine = [
        f"--transform={_report_transform_expression(alias_name, _report_quarantine_name(index, alias_name))}"
        for index, (_logical_name, alias_name) in enumerate(aliases)
    ]
    expected = [
        f"--transform={_report_transform_expression(logical_name, alias_name)}"
        for logical_name, alias_name in aliases
    ]
    return [*quarantine, *expected]


def _validated_report_glob(
    command: Mapping[str, Any], runtime_source_root: str
) -> str:
    report = command.get("report")
    if not isinstance(report, Mapping):
        raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    contract_source_root = report.get("source_root")
    report_glob = report.get("glob")
    if (
        not isinstance(contract_source_root, str)
        or not isinstance(report_glob, str)
        or not contract_source_root
        or not report_glob
        or "\\" in contract_source_root
        or "\\" in report_glob
    ):
        raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    contract_source = PurePosixPath(contract_source_root)
    glob_path = PurePosixPath(report_glob)
    runtime_source = PurePosixPath(runtime_source_root)
    if (
        contract_source.as_posix() != contract_source_root
        or glob_path.as_posix() != report_glob
        or not contract_source.parts
        or any(part in {"", ".", ".."} for part in contract_source.parts)
        or any(part in {"", ".", ".."} for part in glob_path.parts)
        or glob_path.parent != contract_source
    ):
        raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    glob_basename = glob_path.name
    expected_artifacts = report.get("expected_artifacts")
    if not isinstance(expected_artifacts, list):
        raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    if contract_source.name in {"surefire-reports", "failsafe-reports"}:
        if glob_basename != FIXED_JUNIT_REPORT_GLOB:
            raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    elif (
        len(expected_artifacts) != 1
        or not isinstance(expected_artifacts[0], Mapping)
        or glob_basename != expected_artifacts[0].get("filename")
        or SAFE_FILENAME.fullmatch(glob_basename) is None
    ):
        raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    if contract_source.is_absolute():
        source_matches = runtime_source == contract_source
    else:
        command_cwd = command.get("cwd")
        if (
            not isinstance(command_cwd, str)
            or not command_cwd
            or "\\" in command_cwd
        ):
            raise CommandRunnerError(
                "JUNIT_INVALID", "runtime report root differs from the report contract"
            )
        cwd = PurePosixPath(command_cwd)
        if (
            cwd.is_absolute()
            or cwd.as_posix() != command_cwd
            or any(part in {"", ".", ".."} for part in cwd.parts)
        ):
            raise CommandRunnerError(
                "JUNIT_INVALID", "runtime report root differs from the report contract"
            )
        source_matches = runtime_source == (
            PurePosixPath("/workspace") / cwd / contract_source
        )
    if not source_matches:
        raise CommandRunnerError(
            "JUNIT_INVALID", "runtime report root differs from the report contract"
        )
    return glob_basename


def _matches_fixed_junit_glob(filename: str, report_glob: str) -> bool:
    if report_glob == FIXED_JUNIT_REPORT_GLOB:
        return filename.startswith("TEST-") and filename.endswith(".xml")
    if SAFE_FILENAME.fullmatch(report_glob) is None:
        raise CommandRunnerError("JUNIT_INVALID", "report glob contract is invalid")
    return filename == report_glob


def _is_reserved_report_transport_name(filename: str) -> bool:
    folded = filename.casefold()
    return folded.startswith(REPORT_TRANSPORT_ALIAS_PREFIX.casefold()) or folded.startswith(
        REPORT_TRANSPORT_QUARANTINE_PREFIX.casefold()
    )


def _stream_container_report_archive(
    *,
    docker: Path,
    docker_environment: Mapping[str, str],
    container_id: str,
    source_root: str,
    archive_path: Path,
    report_aliases: Sequence[tuple[str, str]] = (),
) -> HashedFile:
    aliases = _validate_report_transport_aliases(report_aliases)
    transform_argv = _report_transform_argv(aliases)
    result, archive = _run_bounded_stdout_to_file(
        [
            str(docker),
            "exec",
            "--workdir=/",
            "--user=0:0",
            container_id,
            *REPORT_EXPORTER_ARGV[:-1],
            *transform_argv,
            REPORT_EXPORTER_ARGV[-1],
            source_root,
            ".",
        ],
        cwd=archive_path.parent,
        env=docker_environment,
        timeout_seconds=120,
        target=archive_path,
        maximum_bytes=MAX_REPORT_STREAM_BYTES,
    )
    _require_success(result, "complete JUnit directory export")
    return archive


def _extract_container_report_archive(
    archive: HashedFile,
    staging_root: Path,
    report_aliases: Sequence[tuple[str, str]] = (),
) -> None:
    aliases = _validate_report_transport_aliases(report_aliases)
    alias_to_logical = {
        alias_name: logical_name for logical_name, alias_name in aliases
    }
    if staging_root.exists() or staging_root.is_symlink():
        raise CommandRunnerError(
            "JUNIT_INVALID", "static report staging directory is not fresh"
        )
    staging_root.mkdir(mode=0o700)
    _revalidate_hashed_file(
        archive, max_bytes=MAX_REPORT_STREAM_BYTES, context="container report stream"
    )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = -1
    expected_offset = 0
    root_seen = False
    file_count = 0
    total_bytes = 0
    names: set[str] = set()
    try:
        descriptor = os.open(archive.path, flags)
        opened = os.fstat(descriptor)
        if _identity(opened) != archive.identity:
            raise CommandRunnerError(
                "JUNIT_INVALID", "container report stream changed before extraction"
            )
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            descriptor = -1
            try:
                with tarfile.open(
                    fileobj=handle,
                    mode="r:",
                    encoding="utf-8",
                    errors="strict",
                ) as report_tar:
                    for member_index, member in enumerate(report_tar):
                        if member_index > MAX_OUTPUT_FILES:
                            raise CommandRunnerError(
                                "JUNIT_INVALID",
                                "report archive exceeds its file bound",
                            )
                        if (
                            member.offset != expected_offset
                            or member.offset_data != member.offset + TAR_BLOCK_BYTES
                            or member.pax_headers
                        ):
                            raise CommandRunnerError(
                                "JUNIT_INVALID",
                                "report archive layout is not canonical USTAR",
                            )
                        padded_size = (
                            (member.size + TAR_BLOCK_BYTES - 1) // TAR_BLOCK_BYTES
                        ) * TAR_BLOCK_BYTES
                        expected_offset = member.offset_data + padded_size
                        if member_index == 0:
                            if (
                                member.name != "."
                                or not member.isdir()
                                or member.size != 0
                            ):
                                raise CommandRunnerError(
                                    "JUNIT_INVALID",
                                    "report archive root entry is invalid",
                                )
                            root_seen = True
                            continue
                        if not root_seen:
                            raise CommandRunnerError(
                                "JUNIT_INVALID", "report archive root is absent"
                            )
                        if (
                            member.type != tarfile.REGTYPE
                            or member.linkname
                            or not member.name.startswith("./")
                            or member.size < 0
                        ):
                            raise CommandRunnerError(
                                "JUNIT_INVALID",
                                "report archive contains a non-regular file",
                            )
                        transport_filename = member.name[2:]
                        if (
                            SAFE_FILENAME.fullmatch(transport_filename) is None
                            or "/" in transport_filename
                            or "\\" in transport_filename
                        ):
                            raise CommandRunnerError(
                                "JUNIT_INVALID",
                                "report archive contains an unsafe or duplicate path",
                            )
                        filename = alias_to_logical.get(
                            transport_filename, transport_filename
                        )
                        if filename.casefold() in names:
                            raise CommandRunnerError(
                                "JUNIT_INVALID",
                                "report archive contains an unsafe or duplicate path",
                            )
                        names.add(filename.casefold())
                        file_count += 1
                        total_bytes += member.size
                        if (
                            file_count > MAX_OUTPUT_FILES
                            or total_bytes > MAX_REPORT_DIRECTORY_BYTES
                        ):
                            raise CommandRunnerError(
                                "JUNIT_INVALID",
                                "report archive exceeds its content bound",
                            )
                        source = report_tar.extractfile(member)
                        if source is None:
                            raise CommandRunnerError(
                                "JUNIT_INVALID", "report archive payload is absent"
                            )
                        target = staging_root / filename
                        target_flags = (
                            os.O_WRONLY
                            | os.O_CREAT
                            | os.O_EXCL
                            | getattr(os, "O_BINARY", 0)
                            | getattr(os, "O_NOFOLLOW", 0)
                        )
                        target_descriptor = os.open(target, target_flags, 0o600)
                        remaining = member.size
                        with os.fdopen(
                            target_descriptor, "wb", closefd=True
                        ) as target_handle:
                            while remaining:
                                chunk = source.read(min(64 * 1024, remaining))
                                if not chunk:
                                    raise CommandRunnerError(
                                        "JUNIT_INVALID",
                                        "report archive payload is truncated",
                                    )
                                target_handle.write(chunk)
                                remaining -= len(chunk)
                            if source.read(1):
                                raise CommandRunnerError(
                                    "JUNIT_INVALID",
                                    "report archive payload exceeds its header",
                                )
                            target_handle.flush()
                            os.fsync(target_handle.fileno())
                if not root_seen or archive.bytes % TAR_RECORD_BYTES != 0:
                    raise CommandRunnerError(
                        "JUNIT_INVALID", "report archive framing is incomplete"
                    )
                if archive.bytes < expected_offset + 2 * TAR_BLOCK_BYTES:
                    raise CommandRunnerError(
                        "JUNIT_INVALID", "report archive end marker is absent"
                    )
                handle.seek(expected_offset)
                while True:
                    chunk = handle.read(64 * 1024)
                    if not chunk:
                        break
                    if any(chunk):
                        raise CommandRunnerError(
                            "JUNIT_INVALID", "report archive has trailing data"
                        )
                after_open = os.fstat(handle.fileno())
                if _identity(after_open) != archive.identity:
                    raise CommandRunnerError(
                        "JUNIT_INVALID", "report archive changed during extraction"
                    )
            except CommandRunnerError:
                raise
            except (
                EOFError,
                OSError,
                UnicodeError,
                ValueError,
                tarfile.TarError,
            ) as exception:
                raise CommandRunnerError(
                    "JUNIT_INVALID", "report archive is malformed"
                ) from exception
    finally:
        if descriptor >= 0:
            with contextlib.suppress(OSError):
                os.close(descriptor)
    _revalidate_hashed_file(
        archive, max_bytes=MAX_REPORT_STREAM_BYTES, context="container report stream"
    )


def _copy_container_reports(
    *,
    docker: Path,
    docker_environment: Mapping[str, str],
    container_id: str,
    command: Mapping[str, Any],
    source_root: str,
    output_dir: Path,
    staging_root: Path,
) -> tuple[list[dict[str, Any]], JunitFacts]:
    source = PurePosixPath(source_root)
    if (
        not source.is_absolute()
        or "\\" in source_root
        or any(part in {"", ".", ".."} for part in source.parts[1:])
    ):
        raise CommandRunnerError("JUNIT_INVALID", "static report source root is unsafe")
    report_glob = _validated_report_glob(command, source_root)
    report_aliases = _report_transport_aliases(command)
    expected = {logical_name for logical_name, _alias_name in report_aliases}
    stream_path = staging_root.parent / (
        f".{staging_root.name}.{secrets.token_hex(16)}.ustar"
    )
    try:
        archive = _stream_container_report_archive(
            docker=docker,
            docker_environment=docker_environment,
            container_id=container_id,
            source_root=source_root,
            archive_path=stream_path,
            report_aliases=report_aliases,
        )
        _extract_container_report_archive(archive, staging_root, report_aliases)
    finally:
        with contextlib.suppress(FileNotFoundError):
            stream_path.unlink()
    actual_junit: set[str] = set()
    total_bytes = 0
    with os.scandir(staging_root) as scan:
        entries = sorted(scan, key=lambda item: item.name)
        for index, entry in enumerate(entries):
            if index >= MAX_OUTPUT_FILES:
                raise CommandRunnerError(
                    "JUNIT_INVALID", "report directory exceeds its file bound"
                )
            metadata = os.lstat(entry.path)
            if (
                entry.is_symlink()
                or not entry.is_file(follow_symlinks=False)
                or _is_alias(metadata)
                or SAFE_FILENAME.fullmatch(entry.name) is None
                or metadata.st_nlink != 1
            ):
                raise CommandRunnerError(
                    "JUNIT_INVALID", "report directory contains a non-regular file"
                )
            total_bytes += metadata.st_size
            if total_bytes > MAX_REPORT_DIRECTORY_BYTES:
                raise CommandRunnerError(
                    "JUNIT_INVALID", "report directory exceeds its byte bound"
                )
            if _is_reserved_report_transport_name(entry.name):
                raise CommandRunnerError(
                    "JUNIT_INVALID", "report directory contains a reserved transport name"
                )
            if _matches_fixed_junit_glob(entry.name, report_glob):
                actual_junit.add(entry.name)
    if actual_junit != expected:
        raise CommandRunnerError(
            "JUNIT_INVALID",
            "JUnit report directory differs from the exact artifact set",
        )

    reports_dir = output_dir / REPORTS_DIRECTORY
    reports_dir.mkdir(mode=0o700)
    reports: list[dict[str, Any]] = []
    facts: list[JunitFacts] = []
    for artifact in command["report"]["expected_artifacts"]:
        filename = artifact["filename"]
        if SAFE_FILENAME.fullmatch(filename) is None:
            raise CommandRunnerError(
                "JUNIT_INVALID", "contract JUnit filename is unsafe"
            )
        source_file = _read_stable_file(
            staging_root / filename,
            max_bytes=MAX_JUNIT_BYTES,
            context=f"static JUnit {filename}",
        )
        parsed = _parse_junit(source_file.payload, filename)
        if parsed.tests != artifact["test_count"] or parsed.suite_ids != (
            artifact["suite_name"],
        ):
            raise CommandRunnerError(
                "JUNIT_INVALID", "JUnit suite or test count differs"
            )
        target = reports_dir / filename
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        descriptor = os.open(target, flags, 0o600)
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            handle.write(source_file.payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(target, 0o644)
        reports.append(
            {
                "bytes": len(source_file.payload),
                "format": "JUNIT_XML",
                "path": f"reports/{filename}",
                "sha256": source_file.sha256,
            }
        )
        facts.append(parsed)
    return reports, _sum_facts(facts)


def build_runtime(
    *,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
    output_dir: Path,
) -> dict[str, Any]:
    identity = _github_identity(
        expected_job=BUILD_JOB,
        candidate_sha=candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
        trusted_workflow_ref=trusted_workflow_ref,
        trusted_workflow_repository=trusted_workflow_repository,
        trusted_workflow_file_path=trusted_workflow_file_path,
    )
    _trusted_snapshot(trusted_code_sha)
    snapshot = _candidate_snapshot(
        candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
    )
    contract = command_contract.load_command_contract()
    policy = runtime_policy.load_runtime_policy()
    dockerfile, requirements_lock = _runtime_input_files()
    output_dir = _fresh_output_directory(output_dir)
    temporary = output_dir.parent / f"phase8-runtime-build-{secrets.token_hex(16)}"
    temporary.mkdir(mode=0o700)
    wheelhouse = output_dir / "wheelhouse"
    wheelhouse.mkdir(mode=0o700)
    inputs = temporary / "inputs"
    inputs.mkdir(mode=0o700)
    dockerfile_copy = _write_stable_payload(
        inputs / "Dockerfile", dockerfile.payload, context="artifact Dockerfile"
    )
    requirements_copy = _write_stable_payload(
        inputs / "requirements.lock",
        requirements_lock.payload,
        context="artifact requirements lock",
    )
    home = temporary / "home"
    home.mkdir(mode=0o700)
    archive_temporary = output_dir / ".image.oci.tmp"
    docker_archive_temporary = output_dir / ".image.docker.tmp"
    metadata_path = output_dir / ".build-metadata.tmp"
    archive_directory = output_dir / "oci"
    archive_directory.mkdir(mode=0o700)
    docker_archive_directory = output_dir / "docker"
    docker_archive_directory.mkdir(mode=0o700)
    archive: Path | None = None
    docker_archive: Path | None = None
    try:
        pip_result = _run_bounded(
            [
                sys.executable,
                "-m",
                "pip",
                "download",
                "--disable-pip-version-check",
                "--no-deps",
                "--only-binary=:all:",
                "--require-hashes",
                "--dest",
                str(wheelhouse),
                "--requirement",
                str(requirements_lock.path),
            ],
            cwd=temporary,
            env=_network_tool_environment(home),
            timeout_seconds=600,
        )
        _require_success(
            pip_result,
            "hash-locked wheel acquisition",
            code="WHEEL_ACQUISITION_FAILED",
        )
        wheel_manifest = _wheelhouse_manifest(wheelhouse)
        docker = _resolve_executable("docker")
        docker_environment = _docker_environment(home)
        pull = _run_bounded(
            [
                str(docker),
                "image",
                "pull",
                "--platform=linux/amd64",
                runtime_policy.BASE_IMAGE,
            ],
            cwd=temporary,
            env=docker_environment,
            timeout_seconds=600,
        )
        _require_success(
            pull, "pinned base image acquisition", code="BASE_IMAGE_PULL_FAILED"
        )
        with _ephemeral_buildkit_builder(
            docker=docker, cwd=temporary, environment=docker_environment
        ) as builder_name:
            build = _run_bounded(
                [
                    str(docker),
                    "buildx",
                    "build",
                    f"--builder={builder_name}",
                    "--pull=false",
                    "--network=none",
                    "--platform=linux/amd64",
                    "--provenance=false",
                    "--build-arg=SOURCE_DATE_EPOCH=0",
                    f"--metadata-file={metadata_path}",
                    f"--build-context=wheelhouse={wheelhouse}",
                    "--file",
                    str(dockerfile_copy.path),
                    f"--output=type=oci,dest={archive_temporary},compression=uncompressed,"
                    "oci-mediatypes=true,rewrite-timestamp=true",
                    f"--output=type=docker,dest={docker_archive_temporary},"
                    "compression=uncompressed,oci-mediatypes=true,rewrite-timestamp=true",
                    str(inputs),
                ],
                cwd=temporary,
                env=docker_environment,
                timeout_seconds=1800,
            )
            _require_success(
                build, "network-denied runtime build", code="RUNTIME_BUILD_FAILED"
            )
        metadata_file = _read_stable_file(
            metadata_path, max_bytes=4 * 1024 * 1024, context="BuildKit metadata"
        )
        metadata_document = _parse_json_bytes(
            metadata_file.payload,
            max_bytes=4 * 1024 * 1024,
            context="BuildKit metadata",
        )
        if not isinstance(metadata_document, dict):
            raise CommandRunnerError(
                "IMAGE_IDENTITY_INVALID", "BuildKit metadata is invalid"
            )
        config_digest = metadata_document.get("containerimage.config.digest")
        if not isinstance(config_digest, str) or not config_digest.startswith(
            "sha256:"
        ):
            raise CommandRunnerError(
                "IMAGE_IDENTITY_INVALID", "BuildKit config digest is absent"
            )
        _assert_sha256(config_digest.removeprefix("sha256:"), "BuildKit config digest")
        metadata_path.unlink()
        os.chmod(archive_temporary, 0o644)
        temporary_archive = _hash_stable_file(
            archive_temporary,
            max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
            context="built OCI archive",
        )
        archive = archive_directory / f"sha256-{temporary_archive.sha256}.tar"
        os.replace(archive_temporary, archive)
        os.chmod(archive, 0o644)
        archive_file = _hash_stable_file(
            archive,
            max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
            context="sealed OCI archive",
        )
        if (
            archive_file.bytes != temporary_archive.bytes
            or archive_file.sha256 != temporary_archive.sha256
        ):
            raise CommandRunnerError(
                "OCI_SUBSTITUTED", "OCI archive changed during seal"
            )
        os.chmod(docker_archive_temporary, 0o644)
        temporary_docker_archive = _hash_stable_file(
            docker_archive_temporary,
            max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
            context="built Docker execution archive",
        )
        docker_archive = (
            docker_archive_directory / f"sha256-{temporary_docker_archive.sha256}.tar"
        )
        os.replace(docker_archive_temporary, docker_archive)
        os.chmod(docker_archive, 0o644)
        docker_archive_file = _hash_stable_file(
            docker_archive,
            max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
            context="sealed Docker execution archive",
        )
        if (
            docker_archive_file.bytes != temporary_docker_archive.bytes
            or docker_archive_file.sha256 != temporary_docker_archive.sha256
        ):
            raise CommandRunnerError(
                "DOCKER_ARCHIVE_SUBSTITUTED",
                "Docker execution archive changed during seal",
            )
        projection = _docker_load_and_inspect(
            docker_archive, expected_image_id=config_digest, home=home
        )
        runtime_identity = _runtime_job_identity(identity)
        build_receipt = {
            "base_image": runtime_policy.BASE_IMAGE,
            "base_image_acquisition_network_profile": (
                runtime_policy.BASE_IMAGE_ACQUISITION_NETWORK_PROFILE
            ),
            "build_parameters": copy.deepcopy(runtime_policy.BUILD_PARAMETERS),
            "build_parameters_sha256": runtime_policy.canonical_sha256(
                runtime_policy.BUILD_PARAMETERS
            ),
            "build_nonce": secrets.token_hex(32),
            "builder_job_identity": runtime_identity,
            "builder_job_identity_sha256": runtime_policy.canonical_sha256(
                runtime_identity
            ),
            "code_sha": snapshot.candidate_sha,
            "code_tree_sha": snapshot.candidate_tree_sha,
            "command_contract_sha256": command_contract.canonical_sha256(contract),
            "config_digest": projection["config_digest"],
            "dockerfile_git_blob": _git_blob_sha1(dockerfile.payload),
            "dockerfile_sha256": dockerfile.sha256,
            "docker_build_run_network": "none",
            "docker_archive_bytes": docker_archive_file.bytes,
            "docker_archive_sha256": docker_archive_file.sha256,
            "image_id": projection["image_id"],
            "image_inspect_projection": projection,
            "image_inspect_projection_sha256": runtime_policy.canonical_sha256(
                projection
            ),
            "oci_archive_bytes": archive_file.bytes,
            "oci_archive_sha256": archive_file.sha256,
            "platform": "linux/amd64",
            "receipt_kind": runtime_policy.RUNTIME_BUILD_RECEIPT_KIND,
            "receipt_sha256": "",
            "requirements_lock_git_blob": _git_blob_sha1(requirements_lock.payload),
            "requirements_lock_sha256": requirements_lock.sha256,
            "rootfs_digest": runtime_policy.canonical_sha256(
                projection["rootfs_layers"]
            ),
            "runtime_policy_sha256": runtime_policy.canonical_sha256(policy),
            "schema_version": runtime_policy.RUNTIME_BUILD_RECEIPT_SCHEMA_VERSION,
            "verified_nonce": secrets.token_hex(32),
            "wheelhouse_manifest": wheel_manifest,
            "wheelhouse_manifest_sha256": runtime_policy.canonical_sha256(
                wheel_manifest
            ),
            "wheelhouse_acquisition_network_profile": (
                runtime_policy.WHEELHOUSE_ACQUISITION_NETWORK_PROFILE
            ),
        }
        if build_receipt["build_nonce"] == build_receipt["verified_nonce"]:
            raise CommandRunnerError("NONCE_COLLISION", "runtime build nonces collided")
        build_receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(
            build_receipt
        )
        receipt_file = _atomic_json(
            output_dir / RUNTIME_BUILD_RECEIPT_NAME, build_receipt
        )
        wheel_file = _atomic_wheelhouse_manifest(
            output_dir / WHEELHOUSE_MANIFEST_NAME, wheel_manifest
        )
        _revalidate_file(dockerfile, max_bytes=256 * 1024, context="trusted Dockerfile")
        _revalidate_file(
            requirements_lock,
            max_bytes=1024 * 1024,
            context="trusted requirements lock",
        )
        _revalidate_file(
            dockerfile_copy, max_bytes=256 * 1024, context="artifact Dockerfile"
        )
        _revalidate_file(
            requirements_copy,
            max_bytes=1024 * 1024,
            context="artifact requirements lock",
        )
        if _wheelhouse_manifest(wheelhouse) != wheel_manifest:
            raise CommandRunnerError(
                "WHEELHOUSE_INVALID", "wheelhouse changed after the runtime build"
            )
        wheel_references = [
            _hashed_file_reference(
                _hash_stable_file(
                    wheelhouse / item["filename"],
                    max_bytes=256 * 1024 * 1024,
                    context=f"artifact wheel {item['filename']}",
                ),
                f"shared-runtime/producer/wheelhouse/{item['filename']}",
            )
            for item in wheel_manifest
        ]
        return {
            "artifact_name": (
                f"phase8-runtime-image-{identity.values['run_id']}-"
                f"{identity.values['run_attempt']}-sha256-{archive_file.sha256}"
            ),
            "authority": "ENGINEERING_TEST_RUNTIME_BUILD_ONLY",
            "image_archive_ref": _hashed_file_reference(
                archive_file,
                f"shared-runtime/producer/oci/sha256-{archive_file.sha256}.tar",
            ),
            "execution_image_archive_ref": _hashed_file_reference(
                docker_archive_file,
                f"shared-runtime/producer/docker/sha256-{docker_archive_file.sha256}.tar",
            ),
            "runtime_build_receipt_ref": _file_reference(
                receipt_file, "shared-runtime/producer/runtime-build-receipt.json"
            ),
            "schema_version": BUILD_BUNDLE_SCHEMA_VERSION,
            "wheel_refs": wheel_references,
            "wheelhouse_manifest_ref": _file_reference(
                wheel_file, "shared-runtime/producer/wheelhouse-manifest.json"
            ),
        }
    finally:
        for temporary_archive_path in (archive_temporary, docker_archive_temporary):
            with contextlib.suppress(OSError):
                temporary_archive_path.unlink()
        with contextlib.suppress(OSError):
            shutil.rmtree(temporary)


def _rebuild_observer_runtime(
    *,
    image_archive: Path,
    execution_image_archive: Path,
    producer_root: Path,
    build_receipt: Mapping[str, Any],
    wheel_manifest: list[dict[str, Any]],
    output_dir: Path,
    home: Path,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], HashedFile, HashedFile]:
    trusted_dockerfile, trusted_lock = _runtime_input_files()
    if (
        build_receipt.get("dockerfile_git_blob")
        != _git_blob_sha1(trusted_dockerfile.payload)
        or build_receipt.get("dockerfile_sha256") != trusted_dockerfile.sha256
        or build_receipt.get("requirements_lock_git_blob")
        != _git_blob_sha1(trusted_lock.payload)
        or build_receipt.get("requirements_lock_sha256") != trusted_lock.sha256
    ):
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID",
            "producer build input binding differs from trusted Git",
        )
    wheelhouse = producer_root / "wheelhouse"
    try:
        validated_wheels, validated_wheels_sha256 = (
            runtime_policy.validate_wheelhouse_directory(wheelhouse, wheel_manifest)
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError("WHEELHOUSE_INVALID", str(exception)) from exception
    if (
        validated_wheels != wheel_manifest
        or validated_wheels_sha256 != build_receipt.get("wheelhouse_manifest_sha256")
    ):
        raise CommandRunnerError(
            "WHEELHOUSE_INVALID", "observer wheelhouse differs from producer receipt"
        )

    docker = _resolve_executable("docker")
    docker_environment = _docker_environment(home)
    pulled = _run_bounded(
        [
            str(docker),
            "image",
            "pull",
            "--platform=linux/amd64",
            runtime_policy.BASE_IMAGE,
        ],
        cwd=home,
        env=docker_environment,
        timeout_seconds=600,
    )
    _require_success(
        pulled,
        "observer pinned base image acquisition",
        code="BASE_IMAGE_PULL_FAILED",
    )
    base_projection = {
        "reference": runtime_policy.BASE_IMAGE,
        **_docker_inspect_projection(runtime_policy.BASE_IMAGE, home=home),
    }
    producer_projection = _docker_load_and_inspect(
        execution_image_archive,
        expected_image_id=build_receipt.get("image_id"),
        home=home,
    )

    build_root = home / "observer-build"
    build_root.mkdir(mode=0o700)
    inputs = build_root / "inputs"
    inputs.mkdir(mode=0o700)
    observer_dockerfile = _write_stable_payload(
        inputs / "Dockerfile", trusted_dockerfile.payload, context="observer Dockerfile"
    )
    observer_lock = _write_stable_payload(
        inputs / "requirements.lock",
        trusted_lock.payload,
        context="observer requirements lock",
    )
    metadata_path = build_root / "metadata.json"
    archive_temporary = output_dir / ".observer-image.oci.tmp"
    docker_archive_temporary = output_dir / ".observer-image.docker.tmp"
    observer_directory = output_dir / "oci"
    observer_directory.mkdir(mode=0o700)
    observer_docker_directory = output_dir / "docker"
    observer_docker_directory.mkdir(mode=0o700)
    observer_path: Path | None = None
    observer_docker_path: Path | None = None
    try:
        with _ephemeral_buildkit_builder(
            docker=docker, cwd=build_root, environment=docker_environment
        ) as builder_name:
            build = _run_bounded(
                [
                    str(docker),
                    "buildx",
                    "build",
                    f"--builder={builder_name}",
                    "--pull=false",
                    "--network=none",
                    "--platform=linux/amd64",
                    "--provenance=false",
                    "--build-arg=SOURCE_DATE_EPOCH=0",
                    f"--metadata-file={metadata_path}",
                    f"--build-context=wheelhouse={wheelhouse}",
                    "--file",
                    str(observer_dockerfile.path),
                    f"--output=type=oci,dest={archive_temporary},"
                    "compression=uncompressed,oci-mediatypes=true,rewrite-timestamp=true",
                    f"--output=type=docker,dest={docker_archive_temporary},"
                    "compression=uncompressed,oci-mediatypes=true,rewrite-timestamp=true",
                    str(inputs),
                ],
                cwd=build_root,
                env=docker_environment,
                timeout_seconds=1800,
            )
            _require_success(
                build,
                "independent network-denied observer runtime build",
                code="RUNTIME_BUILD_FAILED",
            )
        metadata_file = _read_stable_file(
            metadata_path,
            max_bytes=4 * 1024 * 1024,
            context="observer BuildKit metadata",
        )
        metadata = _parse_json_bytes(
            metadata_file.payload,
            max_bytes=4 * 1024 * 1024,
            context="observer BuildKit metadata",
        )
        expected_image_id = build_receipt.get("image_id")
        if (
            not isinstance(metadata, dict)
            or metadata.get("containerimage.config.digest") != expected_image_id
        ):
            raise CommandRunnerError(
                "IMAGE_IDENTITY_INVALID", "observer BuildKit config digest differs"
            )
        metadata_path.unlink()
        os.chmod(archive_temporary, 0o644)
        temporary_archive = _hash_stable_file(
            archive_temporary,
            max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
            context="observer OCI archive",
        )
        observer_path = observer_directory / f"sha256-{temporary_archive.sha256}.tar"
        os.replace(archive_temporary, observer_path)
        os.chmod(docker_archive_temporary, 0o644)
        temporary_docker_archive = _hash_stable_file(
            docker_archive_temporary,
            max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
            context="observer Docker execution archive",
        )
        observer_docker_path = (
            observer_docker_directory / f"sha256-{temporary_docker_archive.sha256}.tar"
        )
        os.replace(docker_archive_temporary, observer_docker_path)
    except Exception:
        for temporary_archive_path in (archive_temporary, docker_archive_temporary):
            with contextlib.suppress(OSError):
                temporary_archive_path.unlink()
        raise
    assert observer_path is not None
    assert observer_docker_path is not None
    os.chmod(observer_path, 0o644)
    observer_archive = _hash_stable_file(
        observer_path,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="sealed observer OCI archive",
    )
    if (
        observer_archive.bytes != temporary_archive.bytes
        or observer_archive.sha256 != temporary_archive.sha256
    ):
        raise CommandRunnerError("OCI_SUBSTITUTED", "observer OCI changed during seal")
    os.chmod(observer_docker_path, 0o644)
    observer_docker_archive = _hash_stable_file(
        observer_docker_path,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="sealed observer Docker execution archive",
    )
    if (
        observer_docker_archive.bytes != temporary_docker_archive.bytes
        or observer_docker_archive.sha256 != temporary_docker_archive.sha256
    ):
        raise CommandRunnerError(
            "DOCKER_ARCHIVE_SUBSTITUTED",
            "observer Docker execution archive changed during seal",
        )
    observer_projection = _docker_load_and_inspect(
        observer_docker_path,
        expected_image_id=expected_image_id,
        home=home,
    )
    if producer_projection != observer_projection:
        raise CommandRunnerError(
            "RUNTIME_OBSERVATION_INVALID",
            "independent observer runtime projection differs",
        )
    for item, max_bytes, context in (
        (trusted_dockerfile, 256 * 1024, "trusted Dockerfile"),
        (trusted_lock, 1024 * 1024, "trusted requirements lock"),
        (observer_dockerfile, 256 * 1024, "observer Dockerfile"),
        (observer_lock, 1024 * 1024, "observer requirements lock"),
    ):
        _revalidate_file(item, max_bytes=max_bytes, context=context)
    try:
        observed_wheels, observed_wheels_sha256 = (
            runtime_policy.validate_wheelhouse_directory(wheelhouse, wheel_manifest)
        )
    except runtime_policy.RuntimePolicyValidationError as exception:
        raise CommandRunnerError("WHEELHOUSE_INVALID", str(exception)) from exception
    if (
        observed_wheels != validated_wheels
        or observed_wheels_sha256 != validated_wheels_sha256
    ):
        raise CommandRunnerError(
            "WHEELHOUSE_INVALID", "wheelhouse changed during rebuild"
        )
    return (
        base_projection,
        producer_projection,
        observer_projection,
        observer_archive,
        observer_docker_archive,
    )


def observe_runtime(
    *,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_ref: str,
    trusted_workflow_repository: str,
    trusted_workflow_file_path: str,
    image_archive: Path,
    execution_image_archive: Path,
    producer_receipt: Path,
    output_dir: Path,
) -> dict[str, Any]:
    identity = _github_identity(
        expected_job=OBSERVE_JOB,
        candidate_sha=candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
        trusted_workflow_ref=trusted_workflow_ref,
        trusted_workflow_repository=trusted_workflow_repository,
        trusted_workflow_file_path=trusted_workflow_file_path,
    )
    _trusted_snapshot(trusted_code_sha)
    snapshot = _candidate_snapshot(
        candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
    )
    contract = command_contract.load_command_contract()
    policy = runtime_policy.load_runtime_policy()
    output_dir = _fresh_output_directory(output_dir)
    archive = _hash_stable_file(
        image_archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="downloaded OCI archive",
    )
    docker_archive = _hash_stable_file(
        execution_image_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="downloaded Docker execution archive",
    )
    receipt_file = _read_stable_file(
        producer_receipt,
        max_bytes=runtime_policy.MAX_RECEIPT_BYTES,
        context="runtime build receipt",
    )
    build_receipt = runtime_policy.parse_receipt_json_bytes(receipt_file.payload)
    wheel_file = _read_stable_file(
        producer_receipt.parent / WHEELHOUSE_MANIFEST_NAME,
        max_bytes=MAX_JSON_BYTES,
        context="wheelhouse manifest",
    )
    wheel_manifest = _parse_json_bytes(
        wheel_file.payload, max_bytes=MAX_JSON_BYTES, context="wheelhouse manifest"
    )
    if not isinstance(wheel_manifest, list):
        raise CommandRunnerError(
            "WHEELHOUSE_INVALID", "wheelhouse manifest is not a list"
        )
    if (
        build_receipt.get("code_sha") != snapshot.candidate_sha
        or build_receipt.get("code_tree_sha") != snapshot.candidate_tree_sha
        or build_receipt.get("oci_archive_sha256") != archive.sha256
        or build_receipt.get("oci_archive_bytes") != archive.bytes
        or build_receipt.get("docker_archive_sha256") != docker_archive.sha256
        or build_receipt.get("docker_archive_bytes") != docker_archive.bytes
    ):
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID", "runtime build input differs"
        )
    builder_identity = build_receipt.get("builder_job_identity")
    if not isinstance(builder_identity, dict):
        raise CommandRunnerError(
            "RUNTIME_BINDING_INVALID", "builder identity is absent"
        )
    _assert_runtime_job_matches(builder_identity, identity, expected_job=BUILD_JOB)
    home = output_dir.parent / f"phase8-observer-home-{secrets.token_hex(16)}"
    home.mkdir(mode=0o700)
    try:
        (
            base_projection,
            producer_projection,
            observer_projection,
            observer_archive,
            observer_docker_archive,
        ) = _rebuild_observer_runtime(
            image_archive=image_archive,
            execution_image_archive=execution_image_archive,
            producer_root=producer_receipt.parent,
            build_receipt=build_receipt,
            wheel_manifest=wheel_manifest,
            output_dir=output_dir,
            home=home,
        )
        provenance_fields = (
            "base_image",
            "base_image_acquisition_network_profile",
            "build_parameters",
            "build_parameters_sha256",
            "builder_job_identity",
            "builder_job_identity_sha256",
            "code_sha",
            "code_tree_sha",
            "command_contract_sha256",
            "dockerfile_git_blob",
            "dockerfile_sha256",
            "docker_build_run_network",
            "docker_archive_bytes",
            "docker_archive_sha256",
            "image_inspect_projection_sha256",
            "oci_archive_bytes",
            "oci_archive_sha256",
            "platform",
            "requirements_lock_git_blob",
            "requirements_lock_sha256",
            "runtime_policy_sha256",
            "wheelhouse_manifest_sha256",
            "wheelhouse_acquisition_network_profile",
        )
        if any(field not in build_receipt for field in provenance_fields):
            raise CommandRunnerError(
                "RUNTIME_BINDING_INVALID", "build provenance is incomplete"
            )
        provenance = {
            field: copy.deepcopy(build_receipt[field]) for field in provenance_fields
        }
        observer_identity = _runtime_job_identity(identity)
        observation = {
            "base_image_inspect_projection": base_projection,
            "base_image_inspect_projection_sha256": runtime_policy.canonical_sha256(
                base_projection
            ),
            "build_provenance": provenance,
            "build_provenance_sha256": runtime_policy.canonical_sha256(provenance),
            "observer_build_parameters": copy.deepcopy(runtime_policy.BUILD_PARAMETERS),
            "observer_build_parameters_sha256": runtime_policy.canonical_sha256(
                runtime_policy.BUILD_PARAMETERS
            ),
            "observer_image_inspect_projection": observer_projection,
            "observer_image_inspect_projection_sha256": runtime_policy.canonical_sha256(
                observer_projection
            ),
            "observer_job_identity": observer_identity,
            "observer_job_identity_sha256": runtime_policy.canonical_sha256(
                observer_identity
            ),
            "observer_nonce": secrets.token_hex(32),
            "observer_docker_archive_bytes": observer_docker_archive.bytes,
            "observer_docker_archive_sha256": observer_docker_archive.sha256,
            "observer_oci_archive_bytes": observer_archive.bytes,
            "observer_oci_archive_sha256": observer_archive.sha256,
            "producer_image_inspect_projection": producer_projection,
            "producer_image_inspect_projection_sha256": runtime_policy.canonical_sha256(
                producer_projection
            ),
            "producer_docker_archive_bytes": docker_archive.bytes,
            "producer_docker_archive_sha256": docker_archive.sha256,
            "producer_oci_archive_bytes": archive.bytes,
            "producer_oci_archive_sha256": archive.sha256,
            "receipt_kind": runtime_policy.BUILD_OBSERVATION_RECEIPT_KIND,
            "receipt_sha256": "",
            "schema_version": runtime_policy.BUILD_OBSERVATION_RECEIPT_SCHEMA_VERSION,
            "source_build_nonce": build_receipt["build_nonce"],
            "source_build_receipt_sha256": build_receipt["receipt_sha256"],
            "wheelhouse_manifest": copy.deepcopy(wheel_manifest),
            "wheelhouse_manifest_sha256": runtime_policy.canonical_sha256(
                wheel_manifest
            ),
        }
        observation["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(
            observation
        )
        observation_binding_keys = (
            "base_image_inspect_projection",
            "base_image_inspect_projection_sha256",
            "build_provenance",
            "build_provenance_sha256",
            "observer_build_parameters",
            "observer_build_parameters_sha256",
            "observer_image_inspect_projection",
            "observer_image_inspect_projection_sha256",
            "observer_job_identity",
            "observer_job_identity_sha256",
            "observer_docker_archive_bytes",
            "observer_docker_archive_sha256",
            "observer_oci_archive_bytes",
            "observer_oci_archive_sha256",
            "producer_image_inspect_projection",
            "producer_image_inspect_projection_sha256",
            "producer_docker_archive_bytes",
            "producer_docker_archive_sha256",
            "producer_oci_archive_bytes",
            "producer_oci_archive_sha256",
            "source_build_receipt_sha256",
            "wheelhouse_manifest",
            "wheelhouse_manifest_sha256",
        )
        expected_observation_binding = {
            key: copy.deepcopy(observation[key]) for key in observation_binding_keys
        }
        expected_run_binding = _runtime_run_binding(identity)
        try:
            runtime_policy.validate_runtime_build_receipt(
                build_receipt,
                observation,
                expected_observation_binding,
                expected_run_binding=expected_run_binding,
                expected_builder_job_identity=builder_identity,
                producer_oci_archive_path=image_archive,
                producer_docker_archive_path=execution_image_archive,
                policy=policy,
                validated_command_contract=contract,
            )
            runtime_policy.verify_shared_runtime_receipts(
                build_receipt,
                observation,
                expected_observation_binding,
                expected_run_binding=expected_run_binding,
                expected_builder_job_identity=builder_identity,
                producer_oci_archive_path=image_archive,
                observer_oci_archive_path=observer_archive.path,
                producer_docker_archive_path=execution_image_archive,
                observer_docker_archive_path=observer_docker_archive.path,
                wheelhouse_root=producer_receipt.parent / "wheelhouse",
                policy=policy,
                validated_command_contract=contract,
            )
        except runtime_policy.RuntimePolicyValidationError as exception:
            raise CommandRunnerError(
                "RUNTIME_OBSERVATION_INVALID", str(exception)
            ) from exception
    finally:
        with contextlib.suppress(OSError):
            shutil.rmtree(home)
    _revalidate_file(
        receipt_file,
        max_bytes=runtime_policy.MAX_RECEIPT_BYTES,
        context="runtime build receipt",
    )
    _revalidate_file(
        wheel_file, max_bytes=MAX_JSON_BYTES, context="wheelhouse manifest"
    )
    _revalidate_hashed_file(
        archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="producer OCI archive",
    )
    _revalidate_hashed_file(
        docker_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="producer Docker execution archive",
    )
    _revalidate_hashed_file(
        observer_archive,
        max_bytes=runtime_policy.MAX_OCI_ARCHIVE_BYTES,
        context="observer OCI archive",
    )
    _revalidate_hashed_file(
        observer_docker_archive,
        max_bytes=runtime_policy.MAX_DOCKER_ARCHIVE_BYTES,
        context="observer Docker execution archive",
    )
    observed_file = _atomic_json(output_dir / BUILD_OBSERVATION_NAME, observation)
    return {
        "artifact_name": (
            f"phase8-runtime-observation-{identity.values['run_id']}-"
            f"{identity.values['run_attempt']}"
        ),
        "authority": "ENGINEERING_TEST_RUNTIME_OBSERVATION_ONLY",
        "build_observation_receipt_ref": _file_reference(
            observed_file, "shared-runtime/observer/build-observation-receipt.json"
        ),
        "observer_image_archive_ref": _hashed_file_reference(
            observer_archive,
            f"shared-runtime/observer/oci/sha256-{observer_archive.sha256}.tar",
        ),
        "observer_execution_image_archive_ref": _hashed_file_reference(
            observer_docker_archive,
            "shared-runtime/observer/docker/"
            f"sha256-{observer_docker_archive.sha256}.tar",
        ),
        "schema_version": OBSERVATION_SCHEMA_VERSION,
    }


def _add_identity_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--trusted-code-sha", required=True)
    parser.add_argument("--trusted-workflow-sha", required=True)
    parser.add_argument("--trusted-workflow-ref", required=True)
    parser.add_argument("--trusted-workflow-repository", required=True)
    parser.add_argument("--trusted-workflow-file-path", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run fixed Phase 8 GitHub command and runtime witness stages."
    )
    subparsers = parser.add_subparsers(dest="operation", required=True)
    build = subparsers.add_parser("build-runtime")
    _add_identity_arguments(build)
    observe = subparsers.add_parser("observe-runtime")
    _add_identity_arguments(observe)
    observe.add_argument("--image-archive", required=True, type=Path)
    observe.add_argument("--execution-image-archive", required=True, type=Path)
    observe.add_argument("--producer-receipt", required=True, type=Path)
    execute = subparsers.add_parser("execute-command")
    _add_identity_arguments(execute)
    execute.add_argument(
        "--command-id", required=True, choices=command_contract.COMMAND_ORDER
    )
    execute.add_argument("--image-archive", type=Path)
    execute.add_argument("--execution-image-archive", type=Path)
    execute.add_argument("--observer-image-archive", type=Path)
    execute.add_argument("--observer-execution-image-archive", type=Path)
    execute.add_argument("--runtime-build-receipt", type=Path)
    execute.add_argument("--build-observation-receipt", type=Path)
    execute.add_argument("--wheelhouse-root", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    common = {
        "candidate_sha": arguments.candidate_sha,
        "trusted_code_sha": arguments.trusted_code_sha,
        "trusted_workflow_sha": arguments.trusted_workflow_sha,
        "trusted_workflow_ref": arguments.trusted_workflow_ref,
        "trusted_workflow_repository": arguments.trusted_workflow_repository,
        "trusted_workflow_file_path": arguments.trusted_workflow_file_path,
        "output_dir": arguments.output_dir,
    }
    try:
        if arguments.operation == "build-runtime":
            result = build_runtime(**common)
        elif arguments.operation == "observe-runtime":
            result = observe_runtime(
                **common,
                image_archive=arguments.image_archive,
                execution_image_archive=arguments.execution_image_archive,
                producer_receipt=arguments.producer_receipt,
            )
        else:
            result = execute_command(
                **common,
                command_id=arguments.command_id,
                image_archive=arguments.image_archive,
                execution_image_archive=arguments.execution_image_archive,
                observer_image_archive=arguments.observer_image_archive,
                observer_execution_image_archive=(
                    arguments.observer_execution_image_archive
                ),
                runtime_build_receipt_path=arguments.runtime_build_receipt,
                build_observation_receipt_path=arguments.build_observation_receipt,
                wheelhouse_root=arguments.wheelhouse_root,
            )
    except CommandRunnerError as exception:
        print(f"Phase 8 command runner rejected: {exception.code}", file=sys.stderr)
        return 2
    print(json.dumps(result, allow_nan=False, sort_keys=True))
    if arguments.operation == "execute-command":
        return 0 if result["execution"]["status"] == "PASSED" else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
