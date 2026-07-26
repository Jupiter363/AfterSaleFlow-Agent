from __future__ import annotations

import argparse
import ctypes
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import sys
import tarfile
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from ctypes import wintypes
from datetime import datetime, timedelta, timezone
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any, Iterator, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = ROOT / "contracts/agent-platform/phase8/github-attestation-policy.json"
SCHEMA_VERSION = "phase8-github-attestation-policy.v1"
EXPECTED_POLICY_SHA256 = (
    "e2495d399317618ac509b9bd6feb3bb6ce1a3b30d34da76b3086cf04f804c866"
)
RECEIPT_SCHEMA_VERSION = "phase8-github-attestation-receipt.v1"
AUTHORITY_CEILING = "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
PREDICATE_AUTHORITY = "BUILDER_CONTROLLED_UNTRUSTED_UNTIL_LOCAL_RECOMPUTE"
LEDGER_DURABILITY = "EXTERNAL_RUN_DIRECTORY_PLACEHOLDER_NOT_GLOBAL_REPLAY_AUTHORITY"
REPOSITORY = "Jupiter363/AfterSaleFlow-Agent"
REPOSITORY_ID = "1282437633"
REPOSITORY_NODE_ID = "R_kgDOTHByAQ"
BRANCH = "refs/heads/codex/p8-production-hardening"
EVENT = "push"
SIGNER_WORKFLOW = ".github/workflows/phase8-engineering-witness.yml"
CALLER_WORKFLOW = ".github/workflows/phase8-engineering-caller.yml"
CALLER_WORKFLOW_NAME = "Phase 8 engineering caller"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
PREDICATE_TYPE = "https://slsa.dev/provenance/v1"
SIGSTORE_INSTANCE = "public-good"
RUNNER_ENVIRONMENT = "github-hosted"
WINDOWS_SYSTEM_ROOT = Path(r"C:\Windows")
SUBJECT_FILENAME = "phase8-engineering-witness.tar"
WITNESS_MANIFEST_NAME = "phase8-engineering-witness-manifest.json"
WITNESS_MANIFEST_SCHEMA_VERSION = "phase8-engineering-witness-manifest.v1"
ACCEPTED_A8 = "3c60bf5cc4e051a214e158cbf944fd6aba969f95"
TRUSTED_CODE_TO_WORKFLOW_PATHS = (
    ".github/workflows/phase8-engineering-witness.yml",
    "tests/static/test_phase8_engineering_witness_workflow.py",
)
TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS = (
    ".github/workflows/phase8-engineering-caller.yml",
    "contracts/agent-platform/phase8/engineering-candidate-scope.json",
)
ARTIFACT_NAME_TEMPLATE = "phase8-engineering-witness-{run_id}-{run_attempt}"
RAW_ARTIFACT_NAME_TEMPLATES = (
    "phase8-raw-000-wave_a_static-{run_id}-{run_attempt}",
    "phase8-raw-001-wave_a_java-{run_id}-{run_attempt}",
    "phase8-raw-002-wave_b_static_and_models-{run_id}-{run_attempt}",
    "phase8-raw-003-wave_b_java_unit-{run_id}-{run_attempt}",
    "phase8-raw-004-wave_b_postgresql_integration-{run_id}-{run_attempt}",
)
OBSERVATION_ARTIFACT_NAME_TEMPLATE = "phase8-runtime-observation-{run_id}-{run_attempt}"
RUNTIME_IMAGE_ARTIFACT_NAME_TEMPLATE = (
    "phase8-runtime-image-{run_id}-{run_attempt}-sha256-{archive_sha256}"
)
EXPECTED_JOB_NAMES = (
    "witness / phase8_build_runtime",
    "witness / phase8_observe_runtime",
    "witness / phase8_wave_a_static",
    "witness / phase8_wave_a_java",
    "witness / phase8_wave_b_static_and_models",
    "witness / phase8_wave_b_java_unit",
    "witness / phase8_wave_b_postgresql_integration",
    "witness / aggregate",
    "witness / attest",
    "witness / gate",
)
MAX_POLICY_BYTES = 32 * 1024
MAX_GH_JSON_BYTES = 4 * 1024 * 1024
MAX_SUBJECT_BYTES = 512 * 1024 * 1024
MAX_WITNESS_MEMBER_BYTES = 64 * 1024 * 1024
MAX_WITNESS_MANIFEST_BYTES = 256 * 1024
MAX_WITNESS_MEMBERS = 4096
MAX_JSON_DEPTH = 32
MAX_JSON_NODES = 100_000
MAX_JSON_STRING_BYTES = 256 * 1024
GH_TIMEOUT_SECONDS = 300
GH_PREFLIGHT_TIMEOUT_SECONDS = 30
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
ARTIFACT_NODE_ID = re.compile(r"^[A-Za-z0-9_-]{8,254}={0,2}$")
REQUIRED_RUNTIME_MEMBERS = frozenset(
    {
        "runtime/execution-set.json",
        "runtime/shared/archive-index.json",
        "runtime/shared/build-observation.json",
        "runtime/shared/runtime-build-receipt.json",
    }
)
WITNESS_MEMBER = re.compile(
    r"^(?:commands/[0-9]{3}-[a-z0-9][a-z0-9_-]{0,95}/(?:report\.json|junit/[A-Za-z0-9][A-Za-z0-9._-]{0,159})|runtime/(?:execution-set\.json|[0-9]{3}-[a-z0-9][a-z0-9_-]{0,95}/receipt\.json|shared/(?:archive-index|build-observation|runtime-build-receipt)\.json))$"
)
EXPECTED_SOURCES_STATUS = {
    "candidate_scope": "PASS",
    "command_contract": "PASS",
    "command_execution": "PASS",
    "runtime_supply_chain": "PASS",
}
RFC3339_UTC = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$"
)


EXPECTED_POLICY: dict[str, Any] = {
    "additional_fields": "DENY",
    "artifact": {
        "archive_days": 90,
        "count": 8,
        "max_bytes": MAX_SUBJECT_BYTES,
        "name_template": ARTIFACT_NAME_TEMPLATE,
        "observation_name_template": OBSERVATION_ARTIFACT_NAME_TEMPLATE,
        "raw_name_templates": list(RAW_ARTIFACT_NAME_TEMPLATES),
        "runtime_image_name_template": RUNTIME_IMAGE_ARTIFACT_NAME_TEMPLATE,
        "subject_filename": SUBJECT_FILENAME,
    },
    "attestation": {
        "count": 1,
        "oidc_issuer": OIDC_ISSUER,
        "predicate_type": PREDICATE_TYPE,
        "sigstore_instance": SIGSTORE_INSTANCE,
    },
    "authority": {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "authority_ceiling": AUTHORITY_CEILING,
        "production_authority": False,
        "production_promotion": "FORBIDDEN",
    },
    "branch": BRANCH,
    "caller_workflow": CALLER_WORKFLOW,
    "event": EVENT,
    "freshness_days": 90,
    "github_cli": {
        "authenticode_inspector": {
            "executable": r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
            "sha256": "7600ffe12da441fe89d035b13801e8e91d064bc544a27b19a5cf49f6ab8b18f5",
        },
        "capability_gates": [
            {"argv": ["api", "--help"], "required_tokens": ["--method", "--raw-field"]},
            {
                "argv": ["run", "list", "--help"],
                "required_tokens": [
                    "--branch",
                    "--commit",
                    "--event",
                    "--json",
                    "--status",
                    "--workflow",
                ],
            },
            {
                "argv": ["run", "view", "--help"],
                "required_tokens": ["--attempt", "--json"],
            },
            {
                "argv": ["attestation", "download", "--help"],
                "required_tokens": ["--limit", "--predicate-type", "--repo"],
            },
            {
                "argv": ["attestation", "trusted-root", "--help"],
                "required_tokens": ["--verify-only"],
            },
            {
                "argv": ["attestation", "verify", "--help"],
                "required_tokens": [
                    "--bundle",
                    "--custom-trusted-root",
                    "--deny-self-hosted-runners",
                    "--format",
                    "--limit",
                    "--predicate-type",
                    "--repo",
                    "--signer-digest",
                    "--signer-workflow",
                    "--source-digest",
                    "--source-ref",
                ],
            },
        ],
        "platforms": {
            "win32": {
                "authenticode_publisher": (
                    'CN="GitHub, Inc.", O="GitHub, Inc.", L=San Francisco, '
                    "S=California, C=US"
                ),
                "executable": r"C:\Program Files\GitHub CLI\gh.exe",
                "sha256": "4cb5ff2afa351c890ae55b2f1fbf4f4a43f6a1e0ab20dfb0567a593bf9cee9ff",
                "system_root": str(WINDOWS_SYSTEM_ROOT),
            }
        },
        "version": "2.93.0",
    },
    "repository": REPOSITORY,
    "repository_identity": {
        "id": REPOSITORY_ID,
        "name": REPOSITORY,
        "node_id": REPOSITORY_NODE_ID,
        "visibility": "public",
    },
    "run": {
        "artifact_count": 8,
        "attempt": 1,
        "job_names": list(EXPECTED_JOB_NAMES),
        "successful_run_count": 1,
        "total_run_count": 1,
    },
    "runner_environment": RUNNER_ENVIRONMENT,
    "schema_version": SCHEMA_VERSION,
    "signer_workflow": SIGNER_WORKFLOW,
    "trusted_sha_roles": {
        "candidate_sha": "SIGSTORE_SOURCE_DIGEST_AND_CALLER_WORKFLOW_BLOB",
        "trusted_code_sha": "WITNESS_MANIFEST_EXACT_BINDING",
        "trusted_workflow_sha": "SIGSTORE_SIGNER_DIGEST",
    },
}


class GitHubAttestationError(ValueError):
    """Raised when GitHub/Sigstore witness evidence is not uniquely trustworthy."""


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: bytes
    stderr: bytes


@dataclass(frozen=True)
class StableFile:
    path: Path
    identity: tuple[int, int, int, int, int]
    payload: bytes
    sha256: str


@dataclass(frozen=True)
class DirectoryComponent:
    path: Path
    identity: tuple[int, int, int, int, int]
    security_sha256: str


@dataclass(frozen=True)
class DirectoryAnchor:
    path: Path
    components: tuple[DirectoryComponent, ...]


@dataclass(frozen=True)
class RunDirectory:
    path: Path
    identity: tuple[int, int, int, int, int]
    ledger: StableFile
    anchor: DirectoryAnchor | None = None


@dataclass(frozen=True)
class TrustedExecutable:
    path: Path
    identity: tuple[int, int, int, int, int]
    sha256: str
    version: str
    single_link_required: bool = True
    state_home: Path | None = None


def _fail(code: str, message: str) -> None:
    raise GitHubAttestationError(f"{code}: {message}")


def _canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exception:
        _fail("NON_CANONICAL_JSON", str(exception))


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _git_blob_sha1(payload: bytes) -> str:
    return hashlib.sha1(f"blob {len(payload)}\0".encode("ascii") + payload).hexdigest()


def _caller_workflow_payload(trusted_workflow_sha: str) -> bytes:
    _assert_sha(trusted_workflow_sha, "trusted_workflow_sha")
    return (
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
        f"    uses: {REPOSITORY}/{SIGNER_WORKFLOW}@{trusted_workflow_sha}\n"
    ).encode("ascii")


def _expected_caller_workflow_binding(trusted_workflow_sha: str) -> dict[str, str]:
    payload = _caller_workflow_payload(trusted_workflow_sha)
    return {
        "file_sha256": _sha256(payload),
        "git_blob_sha1": _git_blob_sha1(payload),
        "mode": "100644",
        "path": CALLER_WORKFLOW,
        "trusted_workflow_sha": trusted_workflow_sha,
    }


def _exact_keys(value: Any, expected: set[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        _fail(
            "JSON_SHAPE_MISMATCH", f"{context} must contain exactly {sorted(expected)}"
        )
    return value


def _required_keys(value: Any, required: set[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or not required.issubset(value):
        _fail(
            "JSON_SHAPE_MISMATCH",
            f"{context} must contain required keys {sorted(required)}",
        )
    return {key: value[key] for key in required}


def _assert_bounded_tree(value: Any) -> None:
    nodes = 0
    stack = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > MAX_JSON_NODES or depth > MAX_JSON_DEPTH:
            _fail("JSON_LIMIT_EXCEEDED", "JSON structure exceeds the fixed bound")
        if (
            isinstance(current, str)
            and len(current.encode("utf-8")) > MAX_JSON_STRING_BYTES
        ):
            _fail("JSON_LIMIT_EXCEEDED", "JSON string exceeds the fixed bound")
        if isinstance(current, dict):
            stack.extend((item, depth + 1) for item in current.values())
        elif isinstance(current, list):
            stack.extend((item, depth + 1) for item in current)


def _duplicate_rejecting_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _fail("DUPLICATE_JSON_KEY", f"duplicate key {key!r}")
        result[key] = value
    return result


def _parse_json(raw: bytes, context: str, *, max_bytes: int = MAX_GH_JSON_BYTES) -> Any:
    if not isinstance(raw, bytes) or not raw or len(raw) > max_bytes:
        _fail("JSON_SIZE_INVALID", f"{context} byte length is outside the fixed bound")
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        _fail("JSON_ENCODING_INVALID", f"{context} must be BOM-free UTF-8")
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_duplicate_rejecting_object,
            parse_constant=lambda token: (_ for _ in ()).throw(
                GitHubAttestationError(f"NON_FINITE_JSON: {token}")
            ),
        )
    except GitHubAttestationError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        _fail("JSON_INVALID", f"{context} is not strict JSON: {exception}")
    _assert_bounded_tree(value)
    return value


def _assert_sha(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA1.fullmatch(value):
        _fail("SHA_INVALID", f"{context} must be a lowercase 40-character Git SHA")
    return value


def _assert_digest(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        _fail("DIGEST_INVALID", f"{context} must be a lowercase SHA-256 digest")
    return value


def _assert_positive_int(value: Any, context: str) -> int:
    if (
        not isinstance(value, int)
        or isinstance(value, bool)
        or not 1 <= value <= 2**63 - 1
    ):
        _fail("INTEGER_INVALID", f"{context} must be a bounded positive integer")
    return value


def _parse_time(value: Any, context: str) -> datetime:
    if not isinstance(value, str) or not RFC3339_UTC.fullmatch(value):
        _fail(
            "TIMESTAMP_INVALID", f"{context} must be an explicit UTC RFC3339 timestamp"
        )
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        _fail("TIMESTAMP_INVALID", f"{context}: {exception}")
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        _fail("TIMESTAMP_INVALID", f"{context} must carry an explicit offset")
    return parsed.astimezone(timezone.utc)


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def load_policy() -> dict[str, Any]:
    try:
        raw = POLICY_PATH.read_bytes()
    except OSError as exception:
        _fail("POLICY_UNREADABLE", str(exception))
    if _sha256(raw) != EXPECTED_POLICY_SHA256:
        _fail("POLICY_DRIFT", "repository attestation policy seal differs")
    policy = _parse_json(raw, "policy", max_bytes=MAX_POLICY_BYTES)
    if policy != EXPECTED_POLICY:
        _fail(
            "POLICY_DRIFT",
            "repository attestation policy differs from the frozen policy",
        )
    return policy


def _is_reparse_or_symlink(path: Path, metadata: os.stat_result) -> bool:
    if stat.S_ISLNK(metadata.st_mode):
        return True
    attributes = getattr(metadata, "st_file_attributes", 0)
    return bool(attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0))


def _identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    mode = int(metadata.st_mode)
    if os.name == "nt":
        # CPython derives execute bits from the path suffix for lstat(), while
        # fstat() on the same Windows handle reports only the underlying ACL bits.
        mode &= ~0o111
    return (
        int(metadata.st_dev),
        int(metadata.st_ino),
        mode,
        int(metadata.st_size),
        int(metadata.st_nlink),
    )


def _directory_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (int(metadata.st_dev), int(metadata.st_ino), int(metadata.st_mode), 0, 0)


def _directory_security_sha256(path: Path, metadata: os.stat_result) -> str:
    if os.name != "nt":
        return _sha256(
            _canonical_json_bytes(
                {
                    "gid": int(metadata.st_gid),
                    "mode": int(metadata.st_mode),
                    "uid": int(metadata.st_uid),
                }
            )
        )
    owner_security_information = 0x00000001
    group_security_information = 0x00000002
    dacl_security_information = 0x00000004
    information = (
        owner_security_information
        | group_security_information
        | dacl_security_information
    )
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    get_file_security = advapi32.GetFileSecurityW
    get_file_security.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
    )
    get_file_security.restype = wintypes.BOOL
    required = wintypes.DWORD(0)
    get_file_security(str(path), information, None, 0, ctypes.byref(required))
    if required.value <= 0 or ctypes.get_last_error() != 122:
        _fail(
            "DIRECTORY_SECURITY_UNREADABLE",
            f"cannot authenticate directory security for {path}",
        )
    buffer = ctypes.create_string_buffer(required.value)
    if not get_file_security(
        str(path), information, buffer, required.value, ctypes.byref(required)
    ):
        _fail(
            "DIRECTORY_SECURITY_UNREADABLE",
            f"cannot authenticate directory security for {path}",
        )
    return _sha256(buffer.raw[: required.value])


def _capture_directory_anchor(path: Path, context: str) -> DirectoryAnchor:
    if not path.is_absolute():
        _fail("DIRECTORY_CHAIN_INVALID", f"{context} must be absolute")
    components: list[DirectoryComponent] = []
    current = path.absolute()
    while True:
        try:
            metadata = current.lstat()
            resolved = current.resolve(strict=True)
        except OSError as exception:
            _fail("DIRECTORY_CHAIN_INVALID", f"{context}: {exception}")
        if (
            _is_reparse_or_symlink(current, metadata)
            or not stat.S_ISDIR(metadata.st_mode)
            or os.path.normcase(str(resolved)) != os.path.normcase(str(current))
        ):
            _fail(
                "DIRECTORY_CHAIN_UNTRUSTED",
                f"{context} contains an unsafe component: {current}",
            )
        components.append(
            DirectoryComponent(
                current,
                _directory_identity(metadata),
                _directory_security_sha256(current, metadata),
            )
        )
        if current.parent == current:
            break
        current = current.parent
    components.reverse()
    return DirectoryAnchor(path.absolute(), tuple(components))


def _assert_directory_anchor(anchor: DirectoryAnchor, context: str) -> None:
    current = _capture_directory_anchor(anchor.path, context)
    if current != anchor:
        _fail(
            "DIRECTORY_CHAIN_CHANGED",
            f"{context} identity or security descriptor changed",
        )


@contextmanager
def _hold_directory_anchors(
    anchors: Sequence[DirectoryAnchor],
) -> Iterator[None]:
    if os.name != "nt":
        yield
        return
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = (wintypes.HANDLE,)
    close_handle.restype = wintypes.BOOL
    file_read_attributes = 0x00000080
    read_control = 0x00020000
    file_share_read = 0x00000001
    file_share_write = 0x00000002
    open_existing = 3
    file_flag_backup_semantics = 0x02000000
    file_flag_open_reparse_point = 0x00200000
    invalid_handle = wintypes.HANDLE(-1).value
    handles: list[int] = []
    seen: set[str] = set()
    try:
        for anchor in anchors:
            for component in anchor.components:
                normalized = os.path.normcase(str(component.path))
                if normalized in seen:
                    continue
                seen.add(normalized)
                handle = create_file(
                    str(component.path),
                    file_read_attributes | read_control,
                    file_share_read | file_share_write,
                    None,
                    open_existing,
                    file_flag_backup_semantics | file_flag_open_reparse_point,
                    None,
                )
                if handle == invalid_handle:
                    _fail(
                        "DIRECTORY_CHAIN_LOCK_FAILED",
                        f"cannot lock authenticated directory {component.path}",
                    )
                handles.append(handle)
        for anchor in anchors:
            _assert_directory_anchor(anchor, "locked process directory")
        yield
    finally:
        for handle in reversed(handles):
            close_handle(handle)


def _assert_external_parent(parent: Path) -> tuple[Path, DirectoryAnchor]:
    original = parent.absolute()
    current = original
    while True:
        try:
            metadata = current.lstat()
        except OSError as exception:
            _fail("RUN_DIRECTORY_PARENT_INVALID", str(exception))
        if _is_reparse_or_symlink(current, metadata) or not stat.S_ISDIR(
            metadata.st_mode
        ):
            _fail(
                "RUN_DIRECTORY_PARENT_UNTRUSTED", f"unsafe parent component: {current}"
            )
        if current.parent == current:
            break
        current = current.parent
    try:
        resolved = parent.resolve(strict=True)
    except OSError as exception:
        _fail("RUN_DIRECTORY_PARENT_INVALID", str(exception))
    repository = ROOT.resolve(strict=True)
    if resolved == repository or repository in resolved.parents:
        _fail("RUN_DIRECTORY_INSIDE_REPOSITORY", "run directory must be external")
    anchor = _capture_directory_anchor(resolved, "run directory parent")
    return resolved, anchor


def _read_stable_file(
    path: Path,
    *,
    max_bytes: int,
    require_single_link: bool = True,
) -> StableFile:
    try:
        before = path.lstat()
    except OSError as exception:
        _fail("FILE_UNREADABLE", f"{path}: {exception}")
    if (
        _is_reparse_or_symlink(path, before)
        or not stat.S_ISREG(before.st_mode)
        or (require_single_link and before.st_nlink != 1)
        or before.st_size <= 0
        or before.st_size > max_bytes
    ):
        _fail("FILE_UNSAFE", f"{path} must be a bounded, single-link regular file")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        try:
            opened = os.fstat(descriptor)
            if _identity(opened) != _identity(before):
                _fail("FILE_CHANGED", f"{path} changed before open")
            payload = bytearray()
            while len(payload) <= max_bytes:
                chunk = os.read(
                    descriptor, min(1024 * 1024, max_bytes + 1 - len(payload))
                )
                if not chunk:
                    break
                payload.extend(chunk)
        finally:
            os.close(descriptor)
    except OSError as exception:
        _fail("FILE_UNREADABLE", f"{path}: {exception}")
    if not payload or len(payload) > max_bytes:
        _fail("FILE_SIZE_INVALID", f"{path} is empty or oversized")
    try:
        after = path.lstat()
    except OSError as exception:
        _fail("FILE_CHANGED", f"{path}: {exception}")
    if _identity(after) != _identity(before):
        _fail("FILE_CHANGED", f"{path} changed while read")
    content = bytes(payload)
    return StableFile(path, _identity(before), content, _sha256(content))


def _assert_real_path_components(path: Path, context: str) -> None:
    current = path.absolute()
    while True:
        try:
            metadata = current.lstat()
        except OSError as exception:
            _fail("GH_EXECUTABLE_INVALID", f"{context}: {exception}")
        if _is_reparse_or_symlink(current, metadata):
            _fail(
                "GH_EXECUTABLE_UNTRUSTED",
                f"{context} contains a link/reparse point: {current}",
            )
        if current != path and not stat.S_ISDIR(metadata.st_mode):
            _fail(
                "GH_EXECUTABLE_UNTRUSTED",
                f"{context} parent is not a directory: {current}",
            )
        if current.parent == current:
            return
        current = current.parent


def _load_trusted_executable(
    specification: Mapping[str, Any],
    *,
    context: str,
    version: str = "",
    require_single_link: bool = True,
) -> TrustedExecutable:
    if set(specification) < {"executable", "sha256"}:
        _fail("GH_POLICY_INVALID", f"{context} executable policy is incomplete")
    configured = specification["executable"]
    expected_sha256 = specification["sha256"]
    if not isinstance(configured, str) or "\x00" in configured:
        _fail("GH_POLICY_INVALID", f"{context} executable path is invalid")
    _assert_digest(expected_sha256, f"{context}.sha256")
    path = Path(configured)
    if not path.is_absolute():
        _fail("GH_EXECUTABLE_INVALID", f"{context} path must be absolute")
    _assert_real_path_components(path, context)
    try:
        resolved = path.resolve(strict=True)
        repository = ROOT.resolve(strict=True)
    except OSError as exception:
        _fail("GH_EXECUTABLE_INVALID", f"{context}: {exception}")
    if os.path.normcase(str(resolved)) != os.path.normcase(str(path)):
        _fail("GH_EXECUTABLE_UNTRUSTED", f"{context} path is not canonical")
    if resolved == repository or repository in resolved.parents:
        _fail(
            "GH_EXECUTABLE_UNTRUSTED", f"{context} must be external to the repository"
        )
    stable = _read_stable_file(
        resolved,
        max_bytes=256 * 1024 * 1024,
        require_single_link=require_single_link,
    )
    if stable.sha256 != expected_sha256:
        _fail("GH_EXECUTABLE_DIGEST_MISMATCH", f"{context} SHA-256 is not approved")
    return TrustedExecutable(
        stable.path,
        stable.identity,
        stable.sha256,
        version,
        require_single_link,
    )


def _assert_trusted_executable(executable: TrustedExecutable) -> None:
    _assert_real_path_components(executable.path, "trusted executable")
    current = _read_stable_file(
        executable.path,
        max_bytes=256 * 1024 * 1024,
        require_single_link=executable.single_link_required,
    )
    if current.identity != executable.identity or current.sha256 != executable.sha256:
        _fail(
            "GH_EXECUTABLE_CHANGED", f"{executable.path} changed after authentication"
        )


def _assert_gh_state_home(state_home: Path) -> DirectoryAnchor:
    if not state_home.is_absolute():
        _fail("GH_STATE_HOME_INVALID", "XDG state home must be absolute")
    try:
        metadata = state_home.lstat()
        resolved = state_home.resolve(strict=True)
        repository = ROOT.resolve(strict=True)
    except OSError as exception:
        _fail("GH_STATE_HOME_INVALID", str(exception))
    if (
        _is_reparse_or_symlink(state_home, metadata)
        or not stat.S_ISDIR(metadata.st_mode)
        or resolved == repository
        or repository in resolved.parents
    ):
        _fail(
            "GH_STATE_HOME_UNTRUSTED",
            "XDG state home must be a real external directory",
        )
    return _capture_directory_anchor(resolved, "XDG state home")


def _create_gh_state_home(run: RunDirectory) -> Path:
    _assert_run_directory(run)
    state_home = run.path / "gh-state"
    try:
        os.mkdir(state_home, 0o700)
    except OSError as exception:
        _fail("GH_STATE_HOME_CREATE_FAILED", str(exception))
    _assert_gh_state_home(state_home)
    return state_home


def _trusted_process_environment(
    *,
    include_token: bool,
    state_home: Path,
) -> tuple[dict[str, str], tuple[DirectoryAnchor, ...]]:
    anchors = [_assert_gh_state_home(state_home)]
    allowed = ("APPDATA", "LOCALAPPDATA", "USERPROFILE")
    environment: dict[str, str] = {}
    for key in allowed:
        if key not in os.environ:
            continue
        directory = Path(os.environ[key])
        if not directory.is_absolute():
            _fail("GH_CREDENTIAL_HOME_INVALID", f"{key} must be absolute")
        _assert_real_path_components(directory, key)
        try:
            metadata = directory.lstat()
            resolved = directory.resolve(strict=True)
            repository = ROOT.resolve(strict=True)
        except OSError as exception:
            _fail("GH_CREDENTIAL_HOME_INVALID", f"{key}: {exception}")
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or resolved == repository
            or repository in resolved.parents
        ):
            _fail(
                "GH_CREDENTIAL_HOME_INVALID",
                f"{key} must be an external real directory",
            )
        anchors.append(_capture_directory_anchor(resolved, key))
        environment[key] = str(resolved)
    if sys.platform == "win32":
        _assert_real_path_components(WINDOWS_SYSTEM_ROOT, "Windows system root")
        try:
            metadata = WINDOWS_SYSTEM_ROOT.lstat()
            resolved = WINDOWS_SYSTEM_ROOT.resolve(strict=True)
        except OSError as exception:
            _fail("GH_SYSTEM_ROOT_INVALID", str(exception))
        if not stat.S_ISDIR(metadata.st_mode) or os.path.normcase(
            str(resolved)
        ) != os.path.normcase(str(WINDOWS_SYSTEM_ROOT)):
            _fail("GH_SYSTEM_ROOT_INVALID", "Windows system root is not canonical")
        environment.update(
            {"SystemRoot": str(WINDOWS_SYSTEM_ROOT), "WINDIR": str(WINDOWS_SYSTEM_ROOT)}
        )
    if include_token and "GH_TOKEN" in os.environ:
        environment["GH_TOKEN"] = os.environ["GH_TOKEN"]
    environment.update(
        {"GH_PAGER": "cat", "NO_COLOR": "1", "XDG_STATE_HOME": str(state_home)}
    )
    return environment, tuple(anchors)


def _run_trusted_process(
    executable: TrustedExecutable,
    argv: tuple[str, ...],
    *,
    cwd: Path | None,
    include_token: bool,
    timeout: int,
    state_home: Path,
    max_stdout_bytes: int = MAX_GH_JSON_BYTES,
) -> CommandResult:
    _assert_trusted_executable(executable)
    environment, directory_anchors = _trusted_process_environment(
        include_token=include_token,
        state_home=state_home,
    )
    if cwd is not None:
        directory_anchors = (
            *directory_anchors,
            _capture_directory_anchor(cwd, "trusted process cwd"),
        )
    for anchor in directory_anchors:
        _assert_directory_anchor(anchor, "trusted process directory")
    failure: OSError | subprocess.SubprocessError | None = None
    result: subprocess.CompletedProcess[bytes] | None = None
    with _hold_directory_anchors(directory_anchors):
        try:
            result = subprocess.run(
                (str(executable.path), *argv),
                shell=False,
                check=False,
                capture_output=True,
                cwd=cwd,
                env=environment,
                timeout=timeout,
            )
        except (OSError, subprocess.SubprocessError) as exception:
            failure = exception
        finally:
            _assert_trusted_executable(executable)
            for anchor in directory_anchors:
                _assert_directory_anchor(anchor, "trusted process directory")
    if failure is not None:
        _fail("GH_EXECUTION_FAILED", str(failure))
    if result is None:  # pragma: no cover - defensive exhaustiveness
        _fail("GH_EXECUTION_FAILED", "trusted process returned no result")
    stdout = bytes(result.stdout)
    stderr = bytes(result.stderr)
    if len(stdout) > max_stdout_bytes or len(stderr) > 64 * 1024:
        _fail("GH_OUTPUT_OVERSIZED", "trusted process output exceeded the fixed bound")
    return CommandResult(result.returncode, stdout, stderr)


def _verify_authenticode(
    executable: TrustedExecutable,
    inspector: TrustedExecutable,
    expected_publisher: str,
    state_home: Path,
) -> None:
    if sys.platform != "win32":
        _fail("GH_PLATFORM_UNSUPPORTED", "Authenticode verification requires Windows")
    target = str(executable.path).replace("'", "''")
    script = (
        "$ErrorActionPreference='Stop';"
        f"$s=Get-AuthenticodeSignature -LiteralPath '{target}';"
        "[ordered]@{status=$s.Status.ToString();"
        "publisher=$s.SignerCertificate.Subject}|ConvertTo-Json -Compress"
    )
    result = _run_trusted_process(
        inspector,
        ("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script),
        cwd=None,
        include_token=False,
        timeout=GH_PREFLIGHT_TIMEOUT_SECONDS,
        state_home=state_home,
    )
    if result.returncode != 0:
        _fail("GH_AUTHENTICODE_INVALID", "Authenticode inspector failed")
    signature = _parse_json(
        result.stdout, "gh Authenticode result", max_bytes=16 * 1024
    )
    if signature != {"status": "Valid", "publisher": expected_publisher}:
        _fail("GH_AUTHENTICODE_INVALID", "gh signature or publisher is not approved")
    _assert_trusted_executable(executable)


def _preflight_gh(policy: Mapping[str, Any], state_home: Path) -> TrustedExecutable:
    cli_policy = policy.get("github_cli")
    if not isinstance(cli_policy, dict) or set(cli_policy) != {
        "authenticode_inspector",
        "capability_gates",
        "platforms",
        "version",
    }:
        _fail("GH_POLICY_INVALID", "github_cli policy shape is invalid")
    platform_policy = cli_policy["platforms"].get(sys.platform)
    if not isinstance(platform_policy, dict) or set(platform_policy) != {
        "authenticode_publisher",
        "executable",
        "sha256",
        "system_root",
    }:
        _fail("GH_PLATFORM_UNSUPPORTED", f"no approved gh binary for {sys.platform}")
    version = cli_policy["version"]
    publisher = platform_policy["authenticode_publisher"]
    if not isinstance(version, str) or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        _fail("GH_POLICY_INVALID", "approved gh version is invalid")
    if not isinstance(publisher, str) or not publisher:
        _fail("GH_POLICY_INVALID", "approved Authenticode publisher is invalid")
    if platform_policy["system_root"] != str(WINDOWS_SYSTEM_ROOT):
        _fail("GH_POLICY_INVALID", "Windows system root differs from policy")
    inspector_policy = cli_policy["authenticode_inspector"]
    if not isinstance(inspector_policy, dict) or set(inspector_policy) != {
        "executable",
        "sha256",
    }:
        _fail("GH_POLICY_INVALID", "Authenticode inspector policy shape is invalid")
    inspector = _load_trusted_executable(
        inspector_policy,
        context="Authenticode inspector",
        require_single_link=False,
    )
    executable = _load_trusted_executable(
        platform_policy, context="gh", version=version
    )
    _verify_authenticode(executable, inspector, publisher, state_home)
    version_result = _run_trusted_process(
        executable,
        ("--version",),
        cwd=None,
        include_token=False,
        timeout=GH_PREFLIGHT_TIMEOUT_SECONDS,
        state_home=state_home,
    )
    try:
        version_lines = version_result.stdout.decode(
            "utf-8", errors="strict"
        ).splitlines()
    except UnicodeDecodeError:
        _fail("GH_VERSION_MISMATCH", "gh version output is not UTF-8")
    if (
        version_result.returncode != 0
        or len(version_lines) != 2
        or re.fullmatch(
            rf"gh version {re.escape(version)} \(\d{{4}}-\d{{2}}-\d{{2}}\)",
            version_lines[0],
        )
        is None
        or version_lines[1] != f"https://github.com/cli/cli/releases/tag/v{version}"
    ):
        _fail("GH_VERSION_MISMATCH", f"gh must be exactly version {version}")
    gates = cli_policy["capability_gates"]
    if not isinstance(gates, list) or not gates:
        _fail("GH_POLICY_INVALID", "capability gates must be non-empty")
    for gate in gates:
        if not isinstance(gate, dict) or set(gate) != {"argv", "required_tokens"}:
            _fail("GH_POLICY_INVALID", "capability gate shape is invalid")
        argv = gate["argv"]
        tokens = gate["required_tokens"]
        if (
            not isinstance(argv, list)
            or not argv
            or not all(
                isinstance(item, str) and item and "\x00" not in item for item in argv
            )
            or not isinstance(tokens, list)
            or not tokens
            or not all(
                isinstance(item, str) and item.startswith("--") for item in tokens
            )
        ):
            _fail("GH_POLICY_INVALID", "capability gate values are invalid")
        result = _run_trusted_process(
            executable,
            tuple(argv),
            cwd=None,
            include_token=False,
            timeout=GH_PREFLIGHT_TIMEOUT_SECONDS,
            state_home=state_home,
        )
        try:
            output = (result.stdout + b"\n" + result.stderr).decode(
                "utf-8", errors="strict"
            )
        except UnicodeDecodeError:
            _fail("GH_CAPABILITY_MISSING", f"{' '.join(argv)} help is not UTF-8")
        missing = [
            token
            for token in tokens
            if re.search(
                rf"(?m)^\s+(?:-[A-Za-z0-9],\s+)?{re.escape(token)}(?:\s|$)",
                output,
            )
            is None
        ]
        if result.returncode != 0 or missing:
            _fail("GH_CAPABILITY_MISSING", f"{' '.join(argv)} lacks {missing}")
    return TrustedExecutable(
        executable.path,
        executable.identity,
        executable.sha256,
        executable.version,
        executable.single_link_required,
        state_home,
    )


def _write_new_file(path: Path, payload: bytes) -> StableFile:
    flags = (
        os.O_CREAT
        | os.O_EXCL
        | os.O_WRONLY
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        descriptor = os.open(path, flags, 0o600)
        try:
            view = memoryview(payload)
            while view:
                written = os.write(descriptor, view)
                if written <= 0:
                    _fail("FILE_WRITE_FAILED", str(path))
                view = view[written:]
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    except OSError as exception:
        _fail("FILE_WRITE_FAILED", f"{path}: {exception}")
    return _read_stable_file(path, max_bytes=max(len(payload), 1))


def _append_ledger(run: RunDirectory, record: Mapping[str, Any]) -> RunDirectory:
    _assert_run_directory(run)
    current = _read_stable_file(run.ledger.path, max_bytes=64 * 1024)
    if current.identity != run.ledger.identity or current.payload != run.ledger.payload:
        _fail("LEDGER_CHANGED", "append-only ledger was replaced or modified")
    line = _canonical_json_bytes(record) + b"\n"
    flags = (
        os.O_WRONLY
        | os.O_APPEND
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        descriptor = os.open(run.ledger.path, flags)
        try:
            opened = os.fstat(descriptor)
            if _identity(opened) != current.identity:
                _fail("LEDGER_CHANGED", "ledger changed before append")
            written = os.write(descriptor, line)
            if written != len(line):
                _fail("LEDGER_WRITE_FAILED", "partial ledger append")
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    except OSError as exception:
        _fail("LEDGER_WRITE_FAILED", str(exception))
    updated = _read_stable_file(run.ledger.path, max_bytes=64 * 1024)
    if updated.payload != current.payload + line:
        _fail("LEDGER_CHANGED", "ledger append is not exact")
    return RunDirectory(run.path, run.identity, updated, run.anchor)


def _prepare_run_directory(
    path: Path,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
) -> RunDirectory:
    if not path.is_absolute():
        _fail("RUN_DIRECTORY_NOT_ABSOLUTE", "run directory must be absolute")
    if path.exists() or path.is_symlink():
        _fail("RUN_DIRECTORY_NOT_FRESH", "run directory must not already exist")
    parent, parent_anchor = _assert_external_parent(path.parent)
    target = parent / path.name
    if target != path:
        _fail("RUN_DIRECTORY_AMBIGUOUS", "run directory path is not canonical")
    try:
        _assert_directory_anchor(parent_anchor, "run directory parent")
        os.mkdir(target, 0o700)
        metadata = target.lstat()
    except OSError as exception:
        _fail("RUN_DIRECTORY_CREATE_FAILED", str(exception))
    if _is_reparse_or_symlink(target, metadata) or not stat.S_ISDIR(metadata.st_mode):
        _fail("RUN_DIRECTORY_UNSAFE", "created run directory is not a real directory")
    _assert_directory_anchor(parent_anchor, "run directory parent")
    claim = {
        "candidate_sha": candidate_sha,
        "record": "CLAIMED",
        "schema_version": "phase8-github-attestation-ledger.v1",
        "trusted_code_sha": trusted_code_sha,
        "trusted_workflow_sha": trusted_workflow_sha,
    }
    ledger = _write_new_file(
        target / "acceptance-ledger.jsonl", _canonical_json_bytes(claim) + b"\n"
    )
    return RunDirectory(
        target,
        _directory_identity(metadata),
        ledger,
        _capture_directory_anchor(target, "run directory"),
    )


def _assert_run_directory(run: RunDirectory) -> None:
    if run.anchor is None:
        _fail("RUN_DIRECTORY_CHANGED", "run directory chain was not authenticated")
    _assert_directory_anchor(run.anchor, "run directory")
    try:
        metadata = run.path.lstat()
    except OSError as exception:
        _fail("RUN_DIRECTORY_CHANGED", str(exception))
    if _is_reparse_or_symlink(run.path, metadata) or not stat.S_ISDIR(metadata.st_mode):
        _fail("RUN_DIRECTORY_CHANGED", "run directory became unsafe")
    if _directory_identity(metadata) != run.identity:
        _fail("RUN_DIRECTORY_CHANGED", "run directory identity changed")
    try:
        ledger = _read_stable_file(run.ledger.path, max_bytes=64 * 1024)
    except GitHubAttestationError as exception:
        _fail("LEDGER_CHANGED", str(exception))
    if ledger.identity != run.ledger.identity or ledger.payload != run.ledger.payload:
        _fail("LEDGER_CHANGED", "ledger was deleted, replaced, or modified")


def _execute_gh(
    argv: tuple[str, ...],
    cwd: Path | None = None,
    *,
    executable: TrustedExecutable,
    max_stdout_bytes: int = MAX_GH_JSON_BYTES,
) -> CommandResult:
    if (
        not isinstance(argv, tuple)
        or not argv
        or any(
            not isinstance(item, str) or not item or "\x00" in item or len(item) > 4096
            for item in argv
        )
    ):
        _fail("COMMAND_INVALID", "gh argv must be a bounded tuple of strings")
    if executable.state_home is None:
        _fail(
            "GH_PREFLIGHT_REQUIRED", "gh executable lacks an authenticated state home"
        )
    return _run_trusted_process(
        executable,
        argv,
        cwd=cwd,
        include_token=True,
        timeout=GH_TIMEOUT_SECONDS,
        state_home=executable.state_home,
        max_stdout_bytes=max_stdout_bytes,
    )


def _call_gh(
    run: RunDirectory,
    executable: TrustedExecutable,
    argv: tuple[str, ...],
    context: str,
    *,
    cwd: Path | None = None,
    max_stdout_bytes: int = MAX_GH_JSON_BYTES,
) -> tuple[RunDirectory, bytes]:
    _assert_run_directory(run)
    result = _execute_gh(
        argv,
        cwd if cwd is not None else run.path,
        executable=executable,
        max_stdout_bytes=max_stdout_bytes,
    )
    _assert_run_directory(run)
    if result.returncode != 0:
        _fail("GH_COMMAND_FAILED", f"{context} exited {result.returncode}")
    return run, result.stdout


def build_run_list_argv(candidate_sha: str) -> tuple[str, ...]:
    _assert_sha(candidate_sha, "candidate_sha")
    return (
        "run",
        "list",
        "--repo",
        REPOSITORY,
        "--workflow",
        CALLER_WORKFLOW,
        "--branch",
        BRANCH.removeprefix("refs/heads/"),
        "--event",
        EVENT,
        "--commit",
        candidate_sha,
        "--limit",
        "2",
        "--json",
        "attempt,conclusion,createdAt,databaseId,event,headBranch,headSha,status,updatedAt,workflowName,workflowDatabaseId",
    )


def build_run_view_argv(run_id: int, attempt: int) -> tuple[str, ...]:
    _assert_positive_int(run_id, "run_id")
    _assert_positive_int(attempt, "run_attempt")
    return (
        "run",
        "view",
        str(run_id),
        "--attempt",
        str(attempt),
        "--repo",
        REPOSITORY,
        "--json",
        "attempt,conclusion,createdAt,databaseId,event,headBranch,headSha,jobs,status,updatedAt,workflowName,workflowDatabaseId",
    )


def build_attempt_jobs_argv(run_id: int, attempt: int, page: int) -> tuple[str, ...]:
    _assert_positive_int(run_id, "run_id")
    _assert_positive_int(attempt, "run_attempt")
    selected_page = _assert_positive_int(page, "attempt_jobs_page")
    if selected_page not in {1, 2}:
        _fail("INTEGER_INVALID", "attempt_jobs_page must be exactly 1 or 2")
    return (
        "api",
        "--method",
        "GET",
        f"repos/{REPOSITORY}/actions/runs/{run_id}/attempts/{attempt}/jobs",
        "-H",
        "X-GitHub-Api-Version: 2022-11-28",
        "-f",
        "filter=latest",
        "-f",
        f"per_page={len(EXPECTED_JOB_NAMES)}",
        "-f",
        f"page={selected_page}",
    )


def build_artifact_list_argv(run_id: int) -> tuple[str, ...]:
    _assert_positive_int(run_id, "run_id")
    return (
        "api",
        "--method",
        "GET",
        f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts",
        "-f",
        "per_page=9",
    )


def build_artifact_download_argv(artifact_id: int) -> tuple[str, ...]:
    selected_id = _assert_positive_int(artifact_id, "artifact_id")
    return (
        "api",
        "--method",
        "GET",
        f"repos/{REPOSITORY}/actions/artifacts/{selected_id}/zip",
    )


def _verification_flags(
    candidate_sha: str, trusted_workflow_sha: str
) -> tuple[str, ...]:
    return (
        "--repo",
        REPOSITORY,
        "--signer-workflow",
        f"{REPOSITORY}/{SIGNER_WORKFLOW}",
        "--signer-digest",
        trusted_workflow_sha,
        "--source-digest",
        candidate_sha,
        "--source-ref",
        BRANCH,
        "--cert-oidc-issuer",
        OIDC_ISSUER,
        "--deny-self-hosted-runners",
        "--predicate-type",
        PREDICATE_TYPE,
        "--limit",
        "2",
        "--format",
        "json",
    )


def build_online_verify_argv(
    subject: Path, candidate_sha: str, trusted_workflow_sha: str
) -> tuple[str, ...]:
    _assert_sha(candidate_sha, "candidate_sha")
    _assert_sha(trusted_workflow_sha, "trusted_workflow_sha")
    return (
        "attestation",
        "verify",
        str(subject),
        *_verification_flags(candidate_sha, trusted_workflow_sha),
    )


def build_offline_verify_argv(
    subject: Path,
    bundle: Path,
    trusted_root: Path,
    candidate_sha: str,
    trusted_workflow_sha: str,
) -> tuple[str, ...]:
    return (
        "attestation",
        "verify",
        str(subject),
        *_verification_flags(candidate_sha, trusted_workflow_sha),
        "--bundle",
        str(bundle),
        "--custom-trusted-root",
        str(trusted_root),
    )


def calculate_attestation_composite_sha256(
    *,
    candidate_sha: str,
    candidate_tree_sha: str,
    accepted_a8_sha: str,
    scope_inventory_sha256: str,
    command_contract_payload_sha256: str,
    artifact_subject_sha256: str,
    caller_workflow_file_sha256: str,
    caller_workflow_git_blob_sha1: str,
    command_artifact_set_sha256: str,
    trusted_code_sha: str,
    trusted_code_tree_sha: str,
    trusted_transition_sha256: str,
    trusted_workflow_sha: str,
    trusted_workflow_tree_sha: str,
    run_id: int,
    run_attempt: int,
) -> str:
    payload = {
        "accepted_a8_sha": _assert_sha(accepted_a8_sha, "composite.accepted_a8_sha"),
        "artifact_subject_sha256": _assert_digest(
            artifact_subject_sha256, "composite.artifact_subject_sha256"
        ),
        "candidate_sha": _assert_sha(candidate_sha, "composite.candidate_sha"),
        "candidate_tree_sha": _assert_sha(
            candidate_tree_sha, "composite.candidate_tree_sha"
        ),
        "caller_workflow_file_sha256": _assert_digest(
            caller_workflow_file_sha256,
            "composite.caller_workflow_file_sha256",
        ),
        "caller_workflow_git_blob_sha1": _assert_sha(
            caller_workflow_git_blob_sha1,
            "composite.caller_workflow_git_blob_sha1",
        ),
        "command_artifact_set_sha256": _assert_digest(
            command_artifact_set_sha256,
            "composite.command_artifact_set_sha256",
        ),
        "command_contract_payload_sha256": _assert_digest(
            command_contract_payload_sha256,
            "composite.command_contract_payload_sha256",
        ),
        "github_run_attempt": _assert_positive_int(
            run_attempt, "composite.github_run_attempt"
        ),
        "github_run_id": _assert_positive_int(run_id, "composite.github_run_id"),
        "schema_version": "phase8-attestation-composite.v1",
        "repository": REPOSITORY,
        "repository_id": REPOSITORY_ID,
        "scope_inventory_sha256": _assert_digest(
            scope_inventory_sha256, "composite.scope_inventory_sha256"
        ),
        "signer_workflow": f"{REPOSITORY}/{SIGNER_WORKFLOW}",
        "source_ref": BRANCH,
        "trusted_code_sha": _assert_sha(trusted_code_sha, "composite.trusted_code_sha"),
        "trusted_code_tree_sha": _assert_sha(
            trusted_code_tree_sha, "composite.trusted_code_tree_sha"
        ),
        "trusted_transition_sha256": _assert_digest(
            trusted_transition_sha256, "composite.trusted_transition_sha256"
        ),
        "trusted_workflow_sha": _assert_sha(
            trusted_workflow_sha, "composite.trusted_workflow_sha"
        ),
        "trusted_workflow_tree_sha": _assert_sha(
            trusted_workflow_tree_sha, "composite.trusted_workflow_tree_sha"
        ),
    }
    return _sha256(_canonical_json_bytes(payload))


def _validate_run(raw: bytes, candidate_sha: str, now: datetime) -> dict[str, Any]:
    runs = _parse_json(raw, "run list")
    if not isinstance(runs, list) or len(runs) != 1:
        _fail("RUN_CARDINALITY_INVALID", "exactly one successful run is required")
    keys = {
        "attempt",
        "conclusion",
        "createdAt",
        "databaseId",
        "event",
        "headBranch",
        "headSha",
        "status",
        "updatedAt",
        "workflowName",
        "workflowDatabaseId",
    }
    run = _exact_keys(runs[0], keys, "run")
    run_id = _assert_positive_int(run["databaseId"], "run.databaseId")
    attempt = _assert_positive_int(run["attempt"], "run.attempt")
    if attempt != 1:
        _fail("RUN_ATTEMPT_INVALID", "exactly the first run attempt is accepted")
    expected = {
        "conclusion": "success",
        "event": EVENT,
        "headBranch": BRANCH.removeprefix("refs/heads/"),
        "headSha": candidate_sha,
        "status": "completed",
        "workflowName": CALLER_WORKFLOW_NAME,
    }
    for field, value in expected.items():
        if run[field] != value:
            _fail("RUN_BINDING_MISMATCH", f"run.{field} does not match policy")
    _assert_positive_int(run["workflowDatabaseId"], "run.workflowDatabaseId")
    created = _parse_time(run["createdAt"], "run.createdAt")
    updated = _parse_time(run["updatedAt"], "run.updatedAt")
    if (
        updated < created
        or updated > now + timedelta(minutes=5)
        or now - updated > timedelta(days=90)
    ):
        _fail(
            "RUN_FRESHNESS_INVALID",
            "successful run is stale or has impossible timestamps",
        )
    return {**run, "databaseId": run_id, "attempt": attempt}


def _validate_run_view_steps(value: Any, context: str) -> None:
    if not isinstance(value, list) or not value or len(value) > 128:
        _fail("JOB_STEP_INVALID", f"{context} step list is invalid")
    keys = {"completedAt", "conclusion", "name", "number", "startedAt", "status"}
    numbers: set[int] = set()
    for index, raw_step in enumerate(value):
        item = _exact_keys(raw_step, keys, f"{context} step {index}")
        number = _assert_positive_int(item["number"], f"{context} step number")
        if number in numbers:
            _fail("JOB_STEP_INVALID", f"{context} step numbers are not unique")
        numbers.add(number)
        if (
            not isinstance(item["name"], str)
            or not item["name"]
            or not isinstance(item["status"], str)
            or not item["status"]
            or not isinstance(item["conclusion"], str)
            or not item["conclusion"]
        ):
            _fail("JOB_STEP_INVALID", f"{context} step fields are invalid")
        if _parse_time(item["completedAt"], f"{context} step completedAt") < _parse_time(
            item["startedAt"], f"{context} step startedAt"
        ):
            _fail("JOB_STEP_INVALID", f"{context} step completion precedes start")


def _validate_rest_steps(value: Any, context: str) -> None:
    if not isinstance(value, list) or not value or len(value) > 128:
        _fail("JOB_STEP_INVALID", f"{context} REST step list is invalid")
    keys = {"completed_at", "conclusion", "name", "number", "started_at", "status"}
    numbers: set[int] = set()
    for index, raw_step in enumerate(value):
        item = _exact_keys(raw_step, keys, f"{context} REST step {index}")
        number = _assert_positive_int(item["number"], f"{context} REST step number")
        if number in numbers:
            _fail("JOB_STEP_INVALID", f"{context} REST step numbers are not unique")
        numbers.add(number)
        if (
            not isinstance(item["name"], str)
            or not item["name"]
            or not isinstance(item["status"], str)
            or not item["status"]
            or not isinstance(item["conclusion"], str)
            or not item["conclusion"]
        ):
            _fail("JOB_STEP_INVALID", f"{context} REST step fields are invalid")
        started = item["started_at"]
        completed = item["completed_at"]
        if started is not None and completed is not None and _parse_time(
            completed, f"{context} REST step completed_at"
        ) < _parse_time(started, f"{context} REST step started_at"):
            _fail("JOB_STEP_INVALID", f"{context} REST step completion precedes start")


def _validate_run_view(
    raw: bytes, expected: Mapping[str, Any]
) -> dict[int, dict[str, Any]]:
    keys = {
        "attempt",
        "conclusion",
        "createdAt",
        "databaseId",
        "event",
        "headBranch",
        "headSha",
        "jobs",
        "status",
        "updatedAt",
        "workflowName",
        "workflowDatabaseId",
    }
    view = _exact_keys(_parse_json(raw, "run view"), keys, "run view")
    for field in keys - {"jobs"}:
        if view[field] != expected[field]:
            _fail("RUN_VIEW_MISMATCH", f"run view {field} drifted from run list")
    jobs = view["jobs"]
    if not isinstance(jobs, list) or len(jobs) != len(EXPECTED_JOB_NAMES):
        _fail("JOB_CARDINALITY_INVALID", "witness workflow job cardinality is invalid")
    job_keys = {
        "completedAt",
        "conclusion",
        "databaseId",
        "name",
        "startedAt",
        "status",
        "steps",
        "url",
    }
    if {job.get("name") for job in jobs if isinstance(job, dict)} != set(
        EXPECTED_JOB_NAMES
    ):
        _fail("JOB_SET_INVALID", "caller reusable witness job set is invalid")
    job_ids: set[int] = set()
    validated: dict[int, dict[str, Any]] = {}
    for job in jobs:
        item = _exact_keys(job, job_keys, "job")
        job_id = _assert_positive_int(item["databaseId"], "job.databaseId")
        if job_id in job_ids:
            _fail("JOB_SET_INVALID", "caller reusable witness job IDs are not unique")
        job_ids.add(job_id)
        if (
            item["conclusion"] != "success"
            or item["status"] != "completed"
        ):
            _fail("JOB_STATUS_INVALID", "every witness job must complete successfully")
        started = _parse_time(item["startedAt"], "job.startedAt")
        completed = _parse_time(item["completedAt"], "job.completedAt")
        if completed < started:
            _fail("JOB_TIMESTAMP_INVALID", "job completion precedes start")
        expected_url = (
            f"https://github.com/{REPOSITORY}/actions/runs/"
            f"{expected['databaseId']}/job/{job_id}"
        )
        if item["url"] != expected_url:
            _fail("JOB_BINDING_INVALID", "run view job URL differs")
        _validate_run_view_steps(item["steps"], f"run view job {job_id}")
        validated[job_id] = {
            "completed": completed,
            "conclusion": item["conclusion"],
            "name": item["name"],
            "started": started,
            "status": item["status"],
            "url": item["url"],
        }
    return validated


def _validate_attempt_jobs(
    first_raw: bytes,
    sentinel_raw: bytes,
    selected: Mapping[str, Any],
    run_view_jobs: Mapping[int, Mapping[str, Any]],
) -> None:
    roots = (
        _exact_keys(_parse_json(first_raw, "attempt jobs page 1"), {"jobs", "total_count"}, "attempt jobs page 1"),
        _exact_keys(_parse_json(sentinel_raw, "attempt jobs page 2"), {"jobs", "total_count"}, "attempt jobs page 2"),
    )
    expected_count = len(EXPECTED_JOB_NAMES)
    first_jobs = roots[0]["jobs"]
    sentinel_jobs = roots[1]["jobs"]
    if (
        type(roots[0]["total_count"]) is not int
        or type(roots[1]["total_count"]) is not int
        or roots[0]["total_count"] != expected_count
        or roots[1]["total_count"] != expected_count
        or not isinstance(first_jobs, list)
        or len(first_jobs) != expected_count
        or not isinstance(sentinel_jobs, list)
        or sentinel_jobs
    ):
        _fail("JOB_CARDINALITY_INVALID", "attempt job pagination is not exact")

    required = {
        "check_run_url",
        "completed_at",
        "conclusion",
        "created_at",
        "head_branch",
        "head_sha",
        "html_url",
        "id",
        "labels",
        "name",
        "node_id",
        "run_attempt",
        "run_id",
        "run_url",
        "runner_group_id",
        "runner_group_name",
        "runner_id",
        "runner_name",
        "started_at",
        "status",
        "steps",
        "url",
        "workflow_name",
    }
    run_id = _assert_positive_int(selected["databaseId"], "selected run ID")
    attempt = _assert_positive_int(selected["attempt"], "selected run attempt")
    run_created = _parse_time(selected["createdAt"], "selected run createdAt")
    run_updated = _parse_time(selected["updatedAt"], "selected run updatedAt")
    observed_ids: set[int] = set()
    observed_names: set[str] = set()
    for index, raw_job in enumerate(first_jobs):
        item = _required_keys(raw_job, required, f"attempt job {index}")
        job_id = _assert_positive_int(item["id"], "attempt job id")
        name = item["name"]
        if job_id in observed_ids or not isinstance(name, str) or name in observed_names:
            _fail("JOB_SET_INVALID", "attempt job IDs and names must be unique")
        observed_ids.add(job_id)
        observed_names.add(name)
        if (
            item["run_id"] != run_id
            or item["run_attempt"] != attempt
            or item["workflow_name"] != CALLER_WORKFLOW_NAME
            or item["head_branch"] != BRANCH.removeprefix("refs/heads/")
            or item["head_sha"] != selected["headSha"]
        ):
            _fail("JOB_BINDING_INVALID", "attempt job belongs to another run")
        if item["status"] != "completed" or item["conclusion"] != "success":
            _fail("JOB_STATUS_INVALID", "attempt job did not complete successfully")
        created = _parse_time(item["created_at"], "attempt job created_at")
        started = _parse_time(item["started_at"], "attempt job started_at")
        completed = _parse_time(item["completed_at"], "attempt job completed_at")
        if not run_created <= created <= started <= completed <= run_updated:
            _fail("JOB_TIMESTAMP_INVALID", "attempt job timestamps escape the run")
        runner_id = _assert_positive_int(item["runner_id"], "attempt job runner_id")
        if (
            type(item["runner_group_id"]) is not int
            or item["runner_group_id"] != 0
            or item["runner_group_name"] != "GitHub Actions"
            or item["runner_name"] != f"GitHub Actions {runner_id}"
            or item["labels"] != ["ubuntu-24.04"]
        ):
            _fail(
                "RUNNER_ENVIRONMENT_INVALID",
                "every job must use the fixed GitHub-hosted runner class",
            )
        expected_run_url = f"https://api.github.com/repos/{REPOSITORY}/actions/runs/{run_id}"
        expected_job_url = f"https://api.github.com/repos/{REPOSITORY}/actions/jobs/{job_id}"
        expected_html_url = (
            f"https://github.com/{REPOSITORY}/actions/runs/{run_id}/job/{job_id}"
        )
        expected_check_url = f"https://api.github.com/repos/{REPOSITORY}/check-runs/{job_id}"
        if (
            item["run_url"] != expected_run_url
            or item["url"] != expected_job_url
            or item["html_url"] != expected_html_url
            or item["check_run_url"] != expected_check_url
            or not isinstance(item["node_id"], str)
            or not item["node_id"]
        ):
            _fail("JOB_BINDING_INVALID", "attempt job URL or node identity differs")
        _validate_rest_steps(item["steps"], f"attempt job {job_id}")
        view = run_view_jobs.get(job_id)
        if view is None or {
            "completed": completed,
            "conclusion": item["conclusion"],
            "name": name,
            "started": started,
            "status": item["status"],
            "url": item["html_url"],
        } != dict(view):
            _fail("JOB_CROSS_BINDING_INVALID", "REST job differs from run view")
    if observed_names != set(EXPECTED_JOB_NAMES) or observed_ids != set(run_view_jobs):
        _fail("JOB_SET_INVALID", "attempt job set differs from run view")


def _validate_artifact(
    raw: bytes, run: Mapping[str, Any], now: datetime
) -> dict[str, Any]:
    root = _exact_keys(
        _parse_json(raw, "artifact list"), {"artifacts", "total_count"}, "artifact list"
    )
    artifacts = root["artifacts"]
    if (
        type(root["total_count"]) is not int
        or root["total_count"] != 8
        or not isinstance(artifacts, list)
        or len(artifacts) != 8
    ):
        _fail(
            "ARTIFACT_CARDINALITY_INVALID", "exactly eight run artifacts are required"
        )
    keys = {
        "archive_download_url",
        "created_at",
        "digest",
        "expired",
        "expires_at",
        "id",
        "name",
        "node_id",
        "size_in_bytes",
        "updated_at",
        "url",
        "workflow_run",
    }
    formatting = {"run_id": run["databaseId"], "run_attempt": run["attempt"]}
    final_name = ARTIFACT_NAME_TEMPLATE.format(**formatting)
    fixed_names = {
        *(template.format(**formatting) for template in RAW_ARTIFACT_NAME_TEMPLATES),
        OBSERVATION_ARTIFACT_NAME_TEMPLATE.format(**formatting),
        final_name,
    }
    image_prefix = RUNTIME_IMAGE_ARTIFACT_NAME_TEMPLATE.format(
        **formatting, archive_sha256=""
    )
    normalized: list[dict[str, Any]] = []
    observed_ids: set[int] = set()
    observed_names: set[str] = set()
    observed_node_ids: set[str] = set()
    image_seen = False
    for raw_artifact in artifacts:
        artifact = _exact_keys(raw_artifact, keys, "artifact")
        artifact_id = _assert_positive_int(artifact["id"], "artifact.id")
        name = artifact["name"]
        node_id = artifact["node_id"]
        if (
            not isinstance(name, str)
            or name in observed_names
            or artifact_id in observed_ids
            or not isinstance(node_id, str)
            or ARTIFACT_NODE_ID.fullmatch(node_id) is None
            or node_id in observed_node_ids
        ):
            _fail(
                "ARTIFACT_SET_INVALID",
                "artifact names, database IDs, and bounded node IDs must be unique",
            )
        observed_names.add(name)
        observed_ids.add(artifact_id)
        observed_node_ids.add(node_id)
        if name not in fixed_names:
            if (
                image_seen
                or not name.startswith(image_prefix)
                or SHA256.fullmatch(name.removeprefix(image_prefix)) is None
            ):
                _fail(
                    "ARTIFACT_SET_INVALID", "runtime image artifact identity is invalid"
                )
            image_seen = True
        if artifact["expired"] is not False:
            _fail("ARTIFACT_BINDING_MISMATCH", "artifact expiry state is invalid")
        size = _assert_positive_int(artifact["size_in_bytes"], "artifact.size_in_bytes")
        if size > MAX_SUBJECT_BYTES:
            _fail("ARTIFACT_SIZE_INVALID", "artifact exceeds the policy ceiling")
        artifact_url = (
            f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/{artifact_id}"
        )
        if (
            artifact["url"] != artifact_url
            or artifact["archive_download_url"] != f"{artifact_url}/zip"
        ):
            _fail(
                "ARTIFACT_URL_INVALID",
                "artifact URLs are not exactly repository/ID-bound",
            )
        digest = artifact["digest"]
        if (
            not isinstance(digest, str)
            or not digest.startswith("sha256:")
            or SHA256.fullmatch(digest.removeprefix("sha256:")) is None
        ):
            _fail(
                "ARTIFACT_DIGEST_INVALID",
                "artifact digest must be one canonical lowercase SHA-256 value",
            )
        workflow_run = _exact_keys(
            artifact["workflow_run"],
            {
                "head_branch",
                "head_repository_id",
                "head_sha",
                "id",
                "repository_id",
            },
            "artifact.workflow_run",
        )
        expected_workflow_run = {
            "head_branch": run["headBranch"],
            "head_repository_id": int(REPOSITORY_ID),
            "head_sha": run["headSha"],
            "id": run["databaseId"],
            "repository_id": int(REPOSITORY_ID),
        }
        if workflow_run != expected_workflow_run:
            _fail("ARTIFACT_RUN_MISMATCH", "artifact is not bound to the selected run")
        created = _parse_time(artifact["created_at"], "artifact.created_at")
        updated = _parse_time(artifact["updated_at"], "artifact.updated_at")
        expires = _parse_time(artifact["expires_at"], "artifact.expires_at")
        if (
            updated < created
            or expires < created + timedelta(days=89)
            or expires > created + timedelta(days=91)
            or now > expires
        ):
            _fail(
                "ARTIFACT_RETENTION_INVALID",
                "artifact does not provide the fixed 90-day archive",
            )
        normalized.append(artifact)
    if not fixed_names.issubset(observed_names) or not image_seen:
        _fail(
            "ARTIFACT_SET_INVALID", "run artifact set differs from the frozen topology"
        )
    final_matches = [
        artifact for artifact in normalized if artifact["name"] == final_name
    ]
    if len(final_matches) != 1:
        _fail("ARTIFACT_SET_INVALID", "exactly one final witness artifact is required")
    return final_matches[0]


def _validate_verification(
    raw: bytes,
    *,
    candidate_sha: str,
    trusted_workflow_sha: str,
    run_id: int,
    attempt: int,
    subject_digest: str,
    now: datetime,
) -> dict[str, Any]:
    records = _parse_json(raw, "attestation verification")
    if not isinstance(records, list) or len(records) != 1:
        _fail("ATTESTATION_CARDINALITY_INVALID", "exactly one attestation is required")
    item = _exact_keys(records[0], {"attestation", "verificationResult"}, "attestation")
    if not isinstance(item["attestation"], dict) or not item["attestation"]:
        _fail(
            "ATTESTATION_BUNDLE_INVALID",
            "verified attestation bundle must be an object",
        )
    result = _exact_keys(
        item["verificationResult"],
        {
            "mediaType",
            "signature",
            "statement",
            "verifiedIdentity",
            "verifiedTimestamps",
        },
        "verificationResult",
    )
    if (
        result["mediaType"]
        != "application/vnd.dev.sigstore.verificationresult+json;version=0.1"
    ):
        _fail(
            "VERIFICATION_MEDIA_TYPE_INVALID",
            "Sigstore verification result media type drifted",
        )
    statement = _exact_keys(
        result["statement"],
        {"_type", "predicate", "predicateType", "subject"},
        "statement",
    )
    if (
        statement["_type"] != "https://in-toto.io/Statement/v1"
        or statement["predicateType"] != PREDICATE_TYPE
    ):
        _fail("PREDICATE_TYPE_INVALID", "attestation is not SLSA provenance v1")
    subjects = statement["subject"]
    if not isinstance(subjects, list) or len(subjects) != 1:
        _fail("SUBJECT_CARDINALITY_INVALID", "exactly one attested subject is required")
    subject = _exact_keys(subjects[0], {"digest", "name"}, "subject")
    digest = _exact_keys(subject["digest"], {"sha256"}, "subject.digest")
    if subject["name"] != SUBJECT_FILENAME or digest["sha256"] != subject_digest:
        _fail(
            "SUBJECT_BINDING_MISMATCH",
            "attested subject does not match downloaded witness",
        )
    signature = _exact_keys(result["signature"], {"certificate"}, "signature")
    certificate = _exact_keys(
        signature["certificate"],
        {
            "buildConfigDigest",
            "buildConfigURI",
            "buildSignerDigest",
            "buildSignerURI",
            "buildTrigger",
            "certificateIssuer",
            "githubWorkflowName",
            "githubWorkflowRef",
            "githubWorkflowRepository",
            "githubWorkflowSHA",
            "githubWorkflowTrigger",
            "issuer",
            "runInvocationURI",
            "runnerEnvironment",
            "sourceRepositoryDigest",
            "sourceRepositoryIdentifier",
            "sourceRepositoryOwnerIdentifier",
            "sourceRepositoryOwnerURI",
            "sourceRepositoryRef",
            "sourceRepositoryURI",
            "sourceRepositoryVisibilityAtSigning",
            "subjectAlternativeName",
        },
        "certificate",
    )
    expected_fields = {
        "buildConfigDigest": candidate_sha,
        "buildConfigURI": f"https://github.com/{REPOSITORY}/{CALLER_WORKFLOW}@{BRANCH}",
        "buildSignerDigest": trusted_workflow_sha,
        "buildTrigger": EVENT,
        "certificateIssuer": "CN=sigstore-intermediate,O=sigstore.dev",
        "githubWorkflowName": CALLER_WORKFLOW_NAME,
        "githubWorkflowRef": BRANCH,
        "githubWorkflowRepository": REPOSITORY,
        "githubWorkflowSHA": candidate_sha,
        "githubWorkflowTrigger": EVENT,
        "issuer": OIDC_ISSUER,
        "runInvocationURI": f"https://github.com/{REPOSITORY}/actions/runs/{run_id}/attempts/{attempt}",
        "runnerEnvironment": RUNNER_ENVIRONMENT,
        "sourceRepositoryDigest": candidate_sha,
        "sourceRepositoryIdentifier": REPOSITORY_ID,
        "sourceRepositoryOwnerURI": f"https://github.com/{REPOSITORY.split('/', 1)[0]}",
        "sourceRepositoryRef": BRANCH,
        "sourceRepositoryURI": f"https://github.com/{REPOSITORY}",
        "sourceRepositoryVisibilityAtSigning": "public",
    }
    for field, expected in expected_fields.items():
        if certificate[field] != expected:
            _fail(
                "CERTIFICATE_BINDING_MISMATCH",
                f"certificate.{field} does not match the frozen context",
            )
    signer_prefix = f"https://github.com/{REPOSITORY}/{SIGNER_WORKFLOW}@"
    if (
        not isinstance(certificate["buildSignerURI"], str)
        or not certificate["buildSignerURI"].startswith(signer_prefix)
        or certificate["subjectAlternativeName"] != certificate["buildSignerURI"]
    ):
        _fail(
            "CERTIFICATE_BINDING_MISMATCH",
            "certificate signer workflow identity drifted",
        )
    owner_identifier = certificate["sourceRepositoryOwnerIdentifier"]
    if (
        not isinstance(owner_identifier, str)
        or not owner_identifier.isascii()
        or not owner_identifier.isdigit()
        or int(owner_identifier) <= 0
    ):
        _fail(
            "CERTIFICATE_BINDING_MISMATCH",
            "certificate.sourceRepositoryOwnerIdentifier is invalid",
        )
    if (
        not isinstance(result["verifiedIdentity"], dict)
        or not result["verifiedIdentity"]
    ):
        _fail(
            "VERIFIED_IDENTITY_MISMATCH",
            "gh must report the identity policy it enforced",
        )
    timestamps = result["verifiedTimestamps"]
    if not isinstance(timestamps, list) or not timestamps or len(timestamps) > 8:
        _fail("VERIFIED_TIMESTAMP_INVALID", "verified timestamp evidence is required")
    timestamp_keys = {"timestamp", "type", "uri"}
    normalized_timestamps: list[dict[str, str]] = []
    for timestamp in timestamps:
        entry = _exact_keys(timestamp, timestamp_keys, "verified timestamp")
        if entry["type"] != "Tlog" or entry["uri"] != "https://rekor.sigstore.dev":
            _fail("VERIFIED_TIMESTAMP_INVALID", "timestamp type is invalid")
        observed = _parse_time(entry["timestamp"], "verified timestamp")
        if observed > now + timedelta(minutes=5) or now - observed > timedelta(days=90):
            _fail(
                "VERIFIED_TIMESTAMP_INVALID",
                "verified timestamp is stale or in the future",
            )
        normalized_timestamps.append(entry)
    predicate = statement["predicate"]
    if not isinstance(predicate, dict):
        _fail("PREDICATE_INVALID", "builder predicate must be an object")
    return {
        "certificate_sha256": _sha256(_canonical_json_bytes(certificate)),
        "predicate_sha256": _sha256(_canonical_json_bytes(predicate)),
        "subject": subject,
        "timestamps": normalized_timestamps,
    }


def _extract_exact_artifact(
    archive_payload: bytes,
    directory: Path,
    expected_archive_sha256: str,
) -> StableFile:
    if (
        not isinstance(archive_payload, bytes)
        or not archive_payload
        or len(archive_payload) > MAX_SUBJECT_BYTES
        or _sha256(archive_payload) != expected_archive_sha256
    ):
        _fail(
            "ARTIFACT_ARCHIVE_DIGEST_MISMATCH",
            "selected artifact archive does not match its REST digest",
        )
    try:
        with zipfile.ZipFile(io.BytesIO(archive_payload), mode="r") as archive:
            members = archive.infolist()
            if len(members) != 1:
                _fail(
                    "DOWNLOAD_AMBIGUOUS",
                    "artifact ZIP must contain exactly one witness subject",
                )
            member = members[0]
            if (
                member.filename != SUBJECT_FILENAME
                or member.is_dir()
                or member.flag_bits & 0x1
                or member.file_size <= 0
                or member.file_size > MAX_SUBJECT_BYTES
                or member.compress_size <= 0
                or member.compress_size > MAX_SUBJECT_BYTES
            ):
                _fail(
                    "DOWNLOAD_AMBIGUOUS",
                    "artifact ZIP member is not the exact bounded witness subject",
                )
            with archive.open(member, mode="r") as stream:
                subject_payload = stream.read(MAX_SUBJECT_BYTES + 1)
            if len(subject_payload) != member.file_size:
                _fail(
                    "DOWNLOAD_AMBIGUOUS",
                    "artifact ZIP member size differs from its central directory",
                )
    except GitHubAttestationError:
        raise
    except (OSError, EOFError, zipfile.BadZipFile, RuntimeError) as exception:
        _fail("DOWNLOAD_AMBIGUOUS", f"artifact ZIP is invalid: {exception}")
    return _write_new_file(directory / SUBJECT_FILENAME, subject_payload)


def _expect_exact_bundle_tree(directory: Path, subject_digest: str) -> StableFile:
    entries = list(directory.iterdir())
    expected_names = {
        f"sha256-{subject_digest}.jsonl",
        f"sha256:{subject_digest}.jsonl",
    }
    if len(entries) != 1 or entries[0].name not in expected_names:
        _fail(
            "BUNDLE_DOWNLOAD_AMBIGUOUS",
            "bundle download must create exactly one digest-named JSONL file",
        )
    bundle = _read_stable_file(entries[0], max_bytes=MAX_GH_JSON_BYTES)
    lines = bundle.payload.splitlines()
    if len(lines) != 1 or not isinstance(
        _parse_json(lines[0], "attestation bundle"), dict
    ):
        _fail(
            "ATTESTATION_CARDINALITY_INVALID",
            "downloaded bundle must contain exactly one attestation",
        )
    return bundle


def _validate_transition_additions(
    value: Any,
    expected_paths: tuple[str, ...],
    context: str,
) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) != len(expected_paths):
        _fail(
            "WITNESS_TRANSITION_INVALID",
            f"{context} must contain exactly the fixed additions",
        )
    additions: list[dict[str, Any]] = []
    for index, expected_path in enumerate(expected_paths):
        addition = _exact_keys(
            value[index],
            {"bytes", "git_blob_sha", "mode", "path", "sha256", "status"},
            f"{context}[{index}]",
        )
        if (
            addition["path"] != expected_path
            or addition["mode"] != "100644"
            or addition["status"] != "A"
            or type(addition["bytes"]) is not int
            or not 1 <= addition["bytes"] <= MAX_WITNESS_MEMBER_BYTES
        ):
            _fail(
                "WITNESS_TRANSITION_INVALID",
                f"{context}[{index}] is not the exact regular-file addition",
            )
        _assert_sha(addition["git_blob_sha"], f"{context}[{index}].git_blob_sha")
        _assert_digest(addition["sha256"], f"{context}[{index}].sha256")
        additions.append(addition)
    return additions


def _validate_trusted_transition(
    value: Any,
    *,
    candidate_sha: str,
    candidate_tree_sha: str,
    trusted_code_sha: str,
    trusted_code_tree_sha: str,
    trusted_workflow_sha: str,
    trusted_workflow_tree_sha: str,
    expected_sha256: str,
) -> dict[str, Any]:
    transition = _exact_keys(
        value,
        {
            "candidate_sha",
            "candidate_tree_sha",
            "trusted_code_sha",
            "trusted_code_to_workflow_additions",
            "trusted_code_tree_sha",
            "trusted_workflow_sha",
            "trusted_workflow_to_candidate_additions",
            "trusted_workflow_tree_sha",
        },
        "manifest.trusted_transition",
    )
    expected_bindings = {
        "candidate_sha": candidate_sha,
        "candidate_tree_sha": candidate_tree_sha,
        "trusted_code_sha": trusted_code_sha,
        "trusted_code_tree_sha": trusted_code_tree_sha,
        "trusted_workflow_sha": trusted_workflow_sha,
        "trusted_workflow_tree_sha": trusted_workflow_tree_sha,
    }
    for field, expected in expected_bindings.items():
        if transition[field] != expected:
            _fail(
                "WITNESS_MANIFEST_BINDING_MISMATCH",
                f"manifest.trusted_transition.{field} does not match",
            )
    _validate_transition_additions(
        transition["trusted_code_to_workflow_additions"],
        TRUSTED_CODE_TO_WORKFLOW_PATHS,
        "manifest.trusted_transition.trusted_code_to_workflow_additions",
    )
    _validate_transition_additions(
        transition["trusted_workflow_to_candidate_additions"],
        TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS,
        "manifest.trusted_transition.trusted_workflow_to_candidate_additions",
    )
    _assert_digest(expected_sha256, "manifest.trusted_transition_sha256")
    if _sha256(_canonical_json_bytes(transition)) != expected_sha256:
        _fail(
            "WITNESS_MANIFEST_BINDING_MISMATCH",
            "trusted transition canonical digest does not match",
        )
    return transition


def _validate_witness_archive(
    subject: StableFile,
    *,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
) -> dict[str, str]:
    try:
        with tarfile.open(fileobj=io.BytesIO(subject.payload), mode="r:") as archive:
            if archive.pax_headers:
                _fail("WITNESS_TAR_INVALID", "global PAX headers are forbidden")
            members = archive.getmembers()
            if not 2 <= len(members) <= MAX_WITNESS_MEMBERS:
                _fail(
                    "WITNESS_TAR_INVALID",
                    "witness member count is outside the fixed bound",
                )
            names = [member.name for member in members]
            if (
                names != sorted(names)
                or len(names) != len(set(names))
                or names.count("manifest.json") != 1
            ):
                _fail(
                    "WITNESS_TAR_INVALID",
                    "witness members must be unique, sorted, and contain one root manifest",
                )
            observed: dict[str, dict[str, Any]] = {}
            manifest_raw: bytes | None = None
            total_bytes = 0
            for member in members:
                posix = PurePosixPath(member.name)
                if (
                    member.name != posix.as_posix()
                    or posix.is_absolute()
                    or not posix.parts
                    or any(part in {"", ".", ".."} for part in posix.parts)
                    or "\\" in member.name
                    or ":" in member.name
                    or not member.name.isascii()
                    or member.pax_headers
                    or not member.isreg()
                    or member.mode != 0o644
                    or member.size <= 0
                    or member.size > MAX_WITNESS_MEMBER_BYTES
                ):
                    _fail(
                        "WITNESS_TAR_INVALID", f"unsafe witness member: {member.name!r}"
                    )
                if (
                    member.name != "manifest.json"
                    and WITNESS_MEMBER.fullmatch(member.name) is None
                ):
                    _fail(
                        "WITNESS_TAR_INVALID",
                        f"unexpected witness member path: {member.name!r}",
                    )
                total_bytes += member.size
                if total_bytes > MAX_SUBJECT_BYTES:
                    _fail(
                        "WITNESS_TAR_INVALID",
                        "declared member bytes exceed the fixed bound",
                    )
                stream = archive.extractfile(member)
                if stream is None:
                    _fail(
                        "WITNESS_TAR_INVALID",
                        f"unreadable witness member: {member.name!r}",
                    )
                payload = stream.read(member.size + 1)
                if len(payload) != member.size:
                    _fail(
                        "WITNESS_TAR_INVALID",
                        f"witness member size mismatch: {member.name!r}",
                    )
                if member.name == "manifest.json":
                    if len(payload) > MAX_WITNESS_MANIFEST_BYTES:
                        _fail(
                            "WITNESS_MANIFEST_INVALID",
                            "manifest exceeds the fixed bound",
                        )
                    manifest_raw = payload
                else:
                    observed[member.name] = {
                        "bytes": len(payload),
                        "path": member.name,
                        "sha256": _sha256(payload),
                    }
    except GitHubAttestationError:
        raise
    except (tarfile.TarError, OSError, EOFError) as exception:
        _fail("WITNESS_TAR_INVALID", str(exception))
    if manifest_raw is None:
        _fail("WITNESS_MANIFEST_INVALID", "root manifest is absent")
    if not REQUIRED_RUNTIME_MEMBERS.issubset(observed):
        _fail(
            "WITNESS_SHARED_RUNTIME_INVALID",
            "accepted witness must contain the complete runtime evidence set",
        )
    manifest = _parse_json(
        manifest_raw, "witness manifest", max_bytes=MAX_WITNESS_MANIFEST_BYTES
    )
    if not isinstance(manifest, dict):
        _fail("WITNESS_MANIFEST_INVALID", "witness manifest must be an object")
    required = {
        "accepted_a8_sha",
        "authority_ceiling",
        "caller_workflow_binding",
        "caller_workflow_path",
        "caller_workflow_ref",
        "caller_workflow_sha",
        "candidate_sha",
        "candidate_tree_sha",
        "command_artifact_set_sha256",
        "command_contract_payload_sha256",
        "member_index",
        "schema_version",
        "scope_inventory_sha256",
        "sources_status",
        "trusted_code_sha",
        "trusted_code_tree_sha",
        "trusted_transition",
        "trusted_transition_sha256",
        "trusted_workflow_file_path",
        "trusted_workflow_ref",
        "trusted_workflow_repository",
        "trusted_workflow_sha",
        "trusted_workflow_tree_sha",
    }
    if set(manifest) != required:
        _fail(
            "WITNESS_MANIFEST_INVALID",
            "witness manifest fields do not match the frozen schema",
        )
    exact_values = {
        "accepted_a8_sha": ACCEPTED_A8,
        "authority_ceiling": AUTHORITY_CEILING,
        "candidate_sha": candidate_sha,
        "caller_workflow_path": CALLER_WORKFLOW,
        "caller_workflow_ref": f"{REPOSITORY}/{CALLER_WORKFLOW}@{BRANCH}",
        "caller_workflow_sha": candidate_sha,
        "schema_version": WITNESS_MANIFEST_SCHEMA_VERSION,
        "trusted_code_sha": trusted_code_sha,
        "trusted_workflow_file_path": SIGNER_WORKFLOW,
        "trusted_workflow_ref": (
            f"{REPOSITORY}/{SIGNER_WORKFLOW}@{trusted_workflow_sha}"
        ),
        "trusted_workflow_repository": REPOSITORY,
        "trusted_workflow_sha": trusted_workflow_sha,
    }
    for field, expected in exact_values.items():
        if manifest[field] != expected:
            _fail(
                "WITNESS_MANIFEST_BINDING_MISMATCH", f"manifest.{field} does not match"
            )
    candidate_tree_sha = _assert_sha(
        manifest["candidate_tree_sha"], "manifest.candidate_tree_sha"
    )
    trusted_code_tree_sha = _assert_sha(
        manifest["trusted_code_tree_sha"], "manifest.trusted_code_tree_sha"
    )
    trusted_workflow_tree_sha = _assert_sha(
        manifest["trusted_workflow_tree_sha"],
        "manifest.trusted_workflow_tree_sha",
    )
    _validate_trusted_transition(
        manifest["trusted_transition"],
        candidate_sha=candidate_sha,
        candidate_tree_sha=candidate_tree_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_code_tree_sha=trusted_code_tree_sha,
        trusted_workflow_sha=trusted_workflow_sha,
        trusted_workflow_tree_sha=trusted_workflow_tree_sha,
        expected_sha256=manifest["trusted_transition_sha256"],
    )
    caller_binding = _exact_keys(
        manifest["caller_workflow_binding"],
        {"file_sha256", "git_blob_sha1", "mode", "path", "trusted_workflow_sha"},
        "manifest.caller_workflow_binding",
    )
    if caller_binding != _expected_caller_workflow_binding(trusted_workflow_sha):
        _fail(
            "WITNESS_MANIFEST_BINDING_MISMATCH",
            "caller workflow blob does not exactly pin the trusted workflow",
        )
    for field in (
        "command_artifact_set_sha256",
        "command_contract_payload_sha256",
        "scope_inventory_sha256",
    ):
        _assert_digest(manifest[field], f"manifest.{field}")
    if manifest["sources_status"] != EXPECTED_SOURCES_STATUS:
        _fail(
            "WITNESS_SOURCE_STATUS_INVALID", "every bounded witness source must be PASS"
        )
    index = manifest["member_index"]
    if not isinstance(index, list) or len(index) != len(observed):
        _fail("WITNESS_INDEX_INVALID", "manifest member index cardinality drifted")
    indexed: dict[str, dict[str, Any]] = {}
    for entry in index:
        item = _exact_keys(
            entry, {"bytes", "path", "sha256"}, "manifest member index entry"
        )
        path = item["path"]
        if (
            not isinstance(path, str)
            or path in indexed
            or path == "manifest.json"
            or WITNESS_MEMBER.fullmatch(path) is None
            or not isinstance(item["bytes"], int)
            or isinstance(item["bytes"], bool)
            or item["bytes"] <= 0
        ):
            _fail("WITNESS_INDEX_INVALID", "manifest member index entry is invalid")
        _assert_digest(item["sha256"], "manifest.member_index.sha256")
        indexed[path] = item
    if [entry["path"] for entry in index] != sorted(indexed) or indexed != observed:
        _fail(
            "WITNESS_INDEX_INVALID",
            "manifest index does not exactly bind archive member bytes",
        )
    if manifest["command_artifact_set_sha256"] != _sha256(_canonical_json_bytes(index)):
        _fail(
            "WITNESS_INDEX_INVALID",
            "command artifact set digest does not bind the canonical index",
        )
    return {
        "candidate_tree_sha": candidate_tree_sha,
        "caller_workflow_file_sha256": caller_binding["file_sha256"],
        "caller_workflow_git_blob_sha1": caller_binding["git_blob_sha1"],
        "command_artifact_set_sha256": manifest["command_artifact_set_sha256"],
        "command_contract_payload_sha256": manifest["command_contract_payload_sha256"],
        "scope_inventory_sha256": manifest["scope_inventory_sha256"],
        "trusted_code_tree_sha": trusted_code_tree_sha,
        "trusted_transition_sha256": manifest["trusted_transition_sha256"],
        "trusted_workflow_sha": trusted_workflow_sha,
        "trusted_workflow_tree_sha": trusted_workflow_tree_sha,
    }


def verify_github_attestation(
    *,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
    artifact: str,
    run_dir: Path,
) -> dict[str, Any]:
    _assert_sha(candidate_sha, "candidate_sha")
    _assert_sha(trusted_code_sha, "trusted_code_sha")
    _assert_sha(trusted_workflow_sha, "trusted_workflow_sha")
    if artifact != SUBJECT_FILENAME:
        _fail("ARTIFACT_INPUT_INVALID", f"artifact must be exactly {SUBJECT_FILENAME}")
    policy = load_policy()
    now = _utc_now()
    run = _prepare_run_directory(
        run_dir,
        candidate_sha,
        trusted_code_sha,
        trusted_workflow_sha,
    )
    executable = _preflight_gh(policy, _create_gh_state_home(run))

    run, run_list_raw = _call_gh(
        run, executable, build_run_list_argv(candidate_sha), "run list"
    )
    selected = _validate_run(run_list_raw, candidate_sha, now)
    run_id = selected["databaseId"]
    attempt = selected["attempt"]

    run, view_raw = _call_gh(
        run, executable, build_run_view_argv(run_id, attempt), "run view"
    )
    run_view_jobs = _validate_run_view(view_raw, selected)
    run, attempt_jobs_first = _call_gh(
        run,
        executable,
        build_attempt_jobs_argv(run_id, attempt, 1),
        "attempt jobs page 1",
    )
    run, attempt_jobs_sentinel = _call_gh(
        run,
        executable,
        build_attempt_jobs_argv(run_id, attempt, 2),
        "attempt jobs page 2",
    )
    _validate_attempt_jobs(
        attempt_jobs_first,
        attempt_jobs_sentinel,
        selected,
        run_view_jobs,
    )
    run, artifact_raw = _call_gh(
        run, executable, build_artifact_list_argv(run_id), "artifact list"
    )
    artifact_record = _validate_artifact(artifact_raw, selected, now)

    download_dir = run.path / "download"
    try:
        os.mkdir(download_dir, 0o700)
    except OSError as exception:
        _fail("DOWNLOAD_DIRECTORY_FAILED", str(exception))
    artifact_name = artifact_record["name"]
    artifact_id = artifact_record["id"]
    run, archive_payload = _call_gh(
        run,
        executable,
        build_artifact_download_argv(artifact_id),
        "artifact download",
        max_stdout_bytes=MAX_SUBJECT_BYTES,
    )
    subject = _extract_exact_artifact(
        archive_payload,
        download_dir,
        artifact_record["digest"].removeprefix("sha256:"),
    )
    witness_manifest = _validate_witness_archive(
        subject,
        candidate_sha=candidate_sha,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=trusted_workflow_sha,
    )

    bundle_dir = run.path / "bundle"
    try:
        os.mkdir(bundle_dir, 0o700)
    except OSError as exception:
        _fail("BUNDLE_DIRECTORY_FAILED", str(exception))
    run, _ = _call_gh(
        run,
        executable,
        (
            "attestation",
            "download",
            str(subject.path),
            "--repo",
            REPOSITORY,
            "--predicate-type",
            PREDICATE_TYPE,
            "--limit",
            "2",
        ),
        "attestation bundle download",
        cwd=bundle_dir,
    )
    bundle = _expect_exact_bundle_tree(bundle_dir, subject.sha256)
    run, root_raw = _call_gh(
        run,
        executable,
        ("attestation", "trusted-root"),
        "trusted root download",
    )
    trusted_root = _write_new_file(run.path / "sigstore-trusted-root.json", root_raw)

    run, online_raw = _call_gh(
        run,
        executable,
        build_online_verify_argv(subject.path, candidate_sha, trusted_workflow_sha),
        "online attestation verification",
    )
    online = _validate_verification(
        online_raw,
        candidate_sha=candidate_sha,
        trusted_workflow_sha=trusted_workflow_sha,
        run_id=run_id,
        attempt=attempt,
        subject_digest=subject.sha256,
        now=now,
    )
    run, offline_raw = _call_gh(
        run,
        executable,
        build_offline_verify_argv(
            subject.path,
            bundle.path,
            trusted_root.path,
            candidate_sha,
            trusted_workflow_sha,
        ),
        "offline attestation verification",
    )
    offline = _validate_verification(
        offline_raw,
        candidate_sha=candidate_sha,
        trusted_workflow_sha=trusted_workflow_sha,
        run_id=run_id,
        attempt=attempt,
        subject_digest=subject.sha256,
        now=now,
    )
    if online != offline:
        _fail(
            "ONLINE_OFFLINE_MISMATCH", "online and offline verification results differ"
        )

    for stable, maximum in (
        (subject, MAX_SUBJECT_BYTES),
        (bundle, MAX_GH_JSON_BYTES),
        (trusted_root, MAX_GH_JSON_BYTES),
    ):
        current = _read_stable_file(stable.path, max_bytes=maximum)
        if current.identity != stable.identity or current.sha256 != stable.sha256:
            _fail(
                "VERIFICATION_INPUT_CHANGED",
                f"{stable.path.name} changed during verification",
            )

    attestation_composite_sha256 = calculate_attestation_composite_sha256(
        candidate_sha=candidate_sha,
        candidate_tree_sha=witness_manifest["candidate_tree_sha"],
        accepted_a8_sha=ACCEPTED_A8,
        scope_inventory_sha256=witness_manifest["scope_inventory_sha256"],
        command_contract_payload_sha256=witness_manifest[
            "command_contract_payload_sha256"
        ],
        artifact_subject_sha256=subject.sha256,
        caller_workflow_file_sha256=witness_manifest["caller_workflow_file_sha256"],
        caller_workflow_git_blob_sha1=witness_manifest["caller_workflow_git_blob_sha1"],
        command_artifact_set_sha256=witness_manifest["command_artifact_set_sha256"],
        trusted_code_sha=trusted_code_sha,
        trusted_code_tree_sha=witness_manifest["trusted_code_tree_sha"],
        trusted_transition_sha256=witness_manifest["trusted_transition_sha256"],
        trusted_workflow_sha=trusted_workflow_sha,
        trusted_workflow_tree_sha=witness_manifest["trusted_workflow_tree_sha"],
        run_id=run_id,
        run_attempt=attempt,
    )
    acceptance_key = _sha256(
        (
            f"{candidate_sha}|{trusted_code_sha}|{trusted_workflow_sha}|"
            f"{run_id}|{attempt}|{subject.sha256}|{attestation_composite_sha256}"
        ).encode("ascii")
    )
    receipt = {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "acceptance_key": acceptance_key,
        "accepted": True,
        "accepted_a8_sha": ACCEPTED_A8,
        "artifact": {
            "id": artifact_record["id"],
            "name": artifact_name,
            "sha256": subject.sha256,
            "subject_filename": SUBJECT_FILENAME,
        },
        "attestation": {
            "bundle_sha256": bundle.sha256,
            "certificate_sha256": online["certificate_sha256"],
            "offline_verified": True,
            "online_verified": True,
            "predicate_authority": PREDICATE_AUTHORITY,
            "predicate_sha256": online["predicate_sha256"],
            "trusted_root_sha256": trusted_root.sha256,
            "verified_timestamps": online["timestamps"],
        },
        "attestation_composite_sha256": attestation_composite_sha256,
        "authority_ceiling": AUTHORITY_CEILING,
        "candidate_sha": candidate_sha,
        "candidate_tree_sha": witness_manifest["candidate_tree_sha"],
        "caller_workflow_binding": {
            "file_sha256": witness_manifest["caller_workflow_file_sha256"],
            "git_blob_sha1": witness_manifest["caller_workflow_git_blob_sha1"],
            "mode": "100644",
            "path": CALLER_WORKFLOW,
            "trusted_workflow_sha": trusted_workflow_sha,
        },
        "command_artifact_set_sha256": witness_manifest["command_artifact_set_sha256"],
        "command_contract_payload_sha256": witness_manifest[
            "command_contract_payload_sha256"
        ],
        "event": EVENT,
        "ledger_durability": LEDGER_DURABILITY,
        "production_authority": False,
        "production_promotion": "FORBIDDEN",
        "repository": REPOSITORY,
        "repository_id": REPOSITORY_ID,
        "run_attempt": attempt,
        "run_id": run_id,
        "schema_version": RECEIPT_SCHEMA_VERSION,
        "signer_workflow": f"{REPOSITORY}/{SIGNER_WORKFLOW}",
        "source_ref": BRANCH,
        "scope_inventory_sha256": witness_manifest["scope_inventory_sha256"],
        "trusted_code_sha": trusted_code_sha,
        "trusted_code_tree_sha": witness_manifest["trusted_code_tree_sha"],
        "trusted_transition_sha256": witness_manifest["trusted_transition_sha256"],
        "trusted_workflow_sha": trusted_workflow_sha,
        "trusted_workflow_tree_sha": witness_manifest["trusted_workflow_tree_sha"],
    }
    run = _append_ledger(
        run,
        {
            "acceptance_key": acceptance_key,
            "attestation_composite_sha256": attestation_composite_sha256,
            "candidate_sha": candidate_sha,
            "record": "ACCEPTED_ENGINEERING_ONLY",
            "run_attempt": attempt,
            "run_id": run_id,
            "repository_id": REPOSITORY_ID,
            "subject_sha256": subject.sha256,
            "trusted_code_sha": trusted_code_sha,
            "trusted_workflow_sha": trusted_workflow_sha,
        },
    )
    _assert_run_directory(run)
    return receipt


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify the Phase 8 GitHub/Sigstore engineering witness"
    )
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--trusted-code-sha", required=True)
    parser.add_argument("--trusted-workflow-sha", required=True)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--run-dir", required=True, type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        receipt = verify_github_attestation(
            candidate_sha=arguments.candidate_sha,
            trusted_code_sha=arguments.trusted_code_sha,
            trusted_workflow_sha=arguments.trusted_workflow_sha,
            artifact=arguments.artifact,
            run_dir=arguments.run_dir,
        )
    except GitHubAttestationError as exception:
        print(str(exception), file=sys.stderr)
        return 1
    print(_canonical_json_bytes(receipt).decode("ascii"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = [
    "GitHubAttestationError",
    "build_artifact_list_argv",
    "build_offline_verify_argv",
    "build_online_verify_argv",
    "build_artifact_download_argv",
    "build_run_list_argv",
    "build_run_view_argv",
    "calculate_attestation_composite_sha256",
    "load_policy",
    "main",
    "verify_github_attestation",
]
