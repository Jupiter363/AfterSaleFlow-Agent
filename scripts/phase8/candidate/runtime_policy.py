from __future__ import annotations

import copy
import gzip
import hashlib
import json
import os
import re
import stat
import tarfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, BinaryIO, Mapping

from . import command_contract


ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = ROOT / "infra-tests/phase8/runtime/runtime-policy.json"
SCHEMA_VERSION = "phase8-test-runtime-policy.v1"
AUTHORITY = "ENGINEERING_TEST_EXECUTION_ONLY"
BASE_IMAGE = (
    "docker.io/library/python@"
    "sha256:cdbd05fb6f457ca275ff51ce00d93d865ca0b6a25f5ffb08262d94f6835771e5"
)
BUILDX_DRIVER = "docker-container"
BUILDKIT_IMAGE = (
    "docker.io/moby/buildkit@"
    "sha256:2f5adac4ecd194d9f8c10b7b5d7bceb5186853db1b26e5abd3a657af0b7e26ec"
)
STATIC_BACKEND_KIND = "PINNED_TEST_CONTAINER"
SUPPORTED_COMMAND_IDS = ("wave_a_static", "wave_b_static_and_models")
STATIC_COMMAND_JOB_NAMES = {
    "wave_a_static": "phase8_wave_a_static",
    "wave_b_static_and_models": "phase8_wave_b_static_and_models",
}
COMMAND_JOB_NAMES = {
    "wave_a_static": "phase8_wave_a_static",
    "wave_a_java": "phase8_wave_a_java",
    "wave_b_static_and_models": "phase8_wave_b_static_and_models",
    "wave_b_java_unit": "phase8_wave_b_java_unit",
    "wave_b_postgresql_integration": "phase8_wave_b_postgresql_integration",
}
STATIC_COMMAND_ARTIFACT_PREFIXES = {
    "wave_a_static": "phase8-raw-000-wave_a_static",
    "wave_b_static_and_models": "phase8-raw-002-wave_b_static_and_models",
}
FORBIDDEN_COMMAND_IDS = (
    "wave_a_java",
    "wave_b_java_unit",
    "wave_b_postgresql_integration",
)
FORBIDDEN_EXECUTION_CLAIMS = ("HOST_EXECUTION", "JDK", "MAVEN", "TESTCONTAINERS")
EXPECTED_POLICY_SHA256 = (
    "dc1c396c804a89ade3b1d62b98e711938057d04052c9871cb3d5b424312a83fd"
)
ACCEPTED_A8 = "3c60bf5cc4e051a214e158cbf944fd6aba969f95"
MATERIALIZATION_RECEIPT_SCHEMA_VERSION = "phase8-materialization-receipt.v2"
RUNTIME_BUILD_RECEIPT_SCHEMA_VERSION = "phase8-runtime-build-receipt.v2"
BUILD_OBSERVATION_RECEIPT_SCHEMA_VERSION = "phase8-build-observation-receipt.v2"
ARTIFACT_TRANSPORT_RECEIPT_SCHEMA_VERSION = "phase8-artifact-transport-receipt.v2"
MATERIALIZATION_RECEIPT_KIND = "EXACT_GIT_BLOB_MATERIALIZATION"
RUNTIME_BUILD_RECEIPT_KIND = "PINNED_TEST_RUNTIME_BUILD"
BUILD_OBSERVATION_RECEIPT_KIND = "INDEPENDENT_OCI_RUNTIME_OBSERVATION"
ARTIFACT_TRANSPORT_RECEIPT_KIND = "GITHUB_ARTIFACT_JOB_TRANSPORT"
ARTIFACT_PAYLOAD_KIND = "COMMAND_JUNIT_FILE_INDEX_V1"
GITHUB_JOB_IDENTITY_SCHEMA_VERSION = "phase8-github-job-identity.v2"
GITHUB_REPOSITORY_ID = "1282437633"
CALLER_WORKFLOW_PATH = ".github/workflows/phase8-engineering-caller.yml"
TRUSTED_WORKFLOW_PATH = ".github/workflows/phase8-engineering-witness.yml"
FIXED_CALLER_WORKFLOW_REF = "refs/heads/codex/p8-production-hardening"
BUILD_JOB_NAME = "phase8_build_runtime"
OBSERVER_JOB_NAME = "phase8_observe_runtime"
EXECUTOR_JOB_NAMES = tuple(STATIC_COMMAND_JOB_NAMES.values())
SHARED_RUNTIME_DIRECTORY_NAME = "shared-runtime"
SHARED_RUNTIME_BUILD_RECEIPT_NAME = "runtime-build-receipt.json"
SHARED_RUNTIME_OBSERVATION_RECEIPT_NAME = "build-observation-receipt.json"
SHARED_RUNTIME_WHEELHOUSE_MANIFEST_NAME = "wheelhouse-manifest.json"
FULL_REPOSITORY = "FULL_REPOSITORY"
JAVA_SERVICE_ONLY = "JAVA_SERVICE_ONLY"
MATERIALIZATION_CLOSURE_KINDS = (FULL_REPOSITORY, JAVA_SERVICE_ONLY)
COMMAND_CLOSURE_KINDS = {
    "wave_a_static": FULL_REPOSITORY,
    "wave_a_java": JAVA_SERVICE_ONLY,
    "wave_b_static_and_models": FULL_REPOSITORY,
    "wave_b_java_unit": JAVA_SERVICE_ONLY,
    "wave_b_postgresql_integration": JAVA_SERVICE_ONLY,
}
BASE_IMAGE_ACQUISITION_NETWORK_PROFILE = (
    "DIGEST_PINNED_PUBLIC_REGISTRY_EGRESS_NO_CREDENTIALS"
)
WHEELHOUSE_ACQUISITION_NETWORK_PROFILE = "HASH_LOCKED_PUBLIC_PYPI_EGRESS_NO_CREDENTIALS"
BUILD_PARAMETERS = {
    "builder_driver": BUILDX_DRIVER,
    "builder_image": BUILDKIT_IMAGE,
    "compression": "uncompressed",
    "docker_build_run_network": "none",
    "export_formats": ["oci", "docker"],
    "oci_mediatypes": True,
    "platform": "linux/amd64",
    "provenance": False,
    "pull": False,
    "rewrite_timestamp": True,
    "source_date_epoch": "0",
}
REQUIRED_IMAGE_LABELS = {
    "com.aftersaleflow.authority": AUTHORITY,
    "org.opencontainers.image.base.digest": BASE_IMAGE.removeprefix(
        "docker.io/library/python@"
    ),
    "org.opencontainers.image.title": "AfterSaleFlow Phase 8 engineering test runtime",
}
FORBIDDEN_RUNTIME_ENVIRONMENT_KEYS = {
    "BASH_ENV",
    "ENV",
    "LD_AUDIT",
    "LD_LIBRARY_PATH",
    "LD_PRELOAD",
    "PYTEST_ADDOPTS",
    "PYTHONBREAKPOINT",
    "PYTHONINSPECT",
    "PYTHONSTARTUP",
}
MAX_POLICY_BYTES = 32 * 1024
MAX_RECEIPT_BYTES = 64 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_ENTRIES = 50_000
MAX_MATERIALIZED_BYTES = 8 * 1024 * 1024 * 1024
MAX_OCI_ARCHIVE_BYTES = 8 * 1024 * 1024 * 1024
MAX_DOCKER_ARCHIVE_BYTES = 8 * 1024 * 1024 * 1024
MAX_CANDIDATE_ARCHIVE_BYTES = 9 * 1024 * 1024 * 1024
MAX_JSON_DEPTH = 12
MAX_JSON_NODES = 512
MAX_SOURCE_IDENTITY_BYTES = 1024
IMAGE_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
NONCE = re.compile(r"^[0-9a-f]{64}$")
ARTIFACT_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
CANDIDATE_ARCHIVE_FORMAT = "USTAR_DETERMINISTIC_V1"
WINDOWS_DEVICE_COMPONENT = re.compile(
    r"^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$", re.IGNORECASE
)

FIXED_ENVIRONMENT = {
    "CI": "1",
    "HOME": "/tmp",
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "PIP_CONFIG_FILE": "/dev/null",
    "PIP_DISABLE_PIP_VERSION_CHECK": "1",
    "PIP_NO_CACHE_DIR": "1",
    "PIP_NO_INDEX": "1",
    "PIP_ONLY_BINARY": ":all:",
    "PYTHONDONTWRITEBYTECODE": "1",
    "PYTHONHASHSEED": "0",
    "PYTHONNOUSERSITE": "1",
    "PYTHONPATH": "/opt/phase8/site-packages",
    "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
    "TMPDIR": "/tmp",
    "TZ": "UTC",
}
REQUIRED_RUNTIME_FLAGS = (
    "--pull=never",
    "--platform=linux/amd64",
    "--network=none",
    "--read-only",
    "--cap-drop=ALL",
    "--security-opt=no-new-privileges:true",
    "--user=65532:65532",
    "--workdir=/workspace",
    "--pids-limit=256",
    "--memory=2147483648",
    "--memory-swap=2147483648",
    "--cpus=2.0",
    "--tmpfs=/tmp:rw,nosuid,nodev,noexec,size=268435456,mode=1777",
    "--tmpfs=/workspace:rw,nosuid,nodev,noexec,size=536870912,mode=0755",
    "--ipc=private",
    "--cgroupns=private",
)
MOUNT_POLICY = {
    "allowed": [],
    "forbidden_source_kinds": [
        "container-runtime-socket",
        "credential-store",
        "git-checkout",
        "git-worktree",
        "host-root",
        "user-home",
    ],
    "forbidden_targets": [
        "/.git",
        "/home",
        "/root",
        "/run/containerd/containerd.sock",
        "/run/secrets",
        "/var/run/docker.sock",
        "/workspace/.git",
    ],
    "maximum_count": 0,
}
CANDIDATE_SOURCE_POLICY = {
    "archive_format": CANDIDATE_ARCHIVE_FORMAT,
    "method": "VERIFIED_STDIN_TO_TRUSTED_EXTRACTOR_ON_TMPFS",
    "source_kind": "validated-content-addressed-candidate-archive-fd",
    "target": "/workspace",
}
CONTAINER_ID_TOKEN = "{validated_container_id}"
TRUSTED_SLEEPER_ARGV = (
    "/usr/local/bin/python",
    "-c",
    "import signal; signal.pause()",
)
TRUSTED_CANDIDATE_EXTRACTOR_SCRIPT = """\
import hashlib
import os
import pathlib
import sys
import tarfile

if len(sys.argv) != 4:
    raise SystemExit(40)
expected_bytes = int(sys.argv[1])
expected_sha256 = sys.argv[2]
expected_count = int(sys.argv[3])
staged = pathlib.Path("/tmp/phase8-candidate.tar")
root = pathlib.Path("/workspace")
if any(root.iterdir()):
    raise SystemExit(41)
digest = hashlib.sha256()
total = 0
with staged.open("xb") as sink:
    while True:
        chunk = sys.stdin.buffer.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > expected_bytes:
            raise SystemExit(42)
        sink.write(chunk)
        digest.update(chunk)
if total != expected_bytes or digest.hexdigest() != expected_sha256:
    raise SystemExit(43)
seen = set()
with tarfile.open(staged, mode="r:") as source:
    for member in source:
        pure = pathlib.PurePosixPath(member.name)
        if (
            not member.name
            or pure.is_absolute()
            or pure.as_posix() != member.name
            or any(part in ("", ".", "..") for part in pure.parts)
            or not member.isfile()
            or member.issym()
            or member.islnk()
            or member.isdev()
            or member.sparse is not None
            or member.name in seen
        ):
            raise SystemExit(44)
        seen.add(member.name)
        target = root.joinpath(*pure.parts)
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        extracted = source.extractfile(member)
        if extracted is None:
            raise SystemExit(45)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
        descriptor = os.open(target, flags, 0o600)
        written = 0
        with os.fdopen(descriptor, "wb") as sink:
            while True:
                chunk = extracted.read(1024 * 1024)
                if not chunk:
                    break
                sink.write(chunk)
                written += len(chunk)
        if written != member.size:
            raise SystemExit(46)
        os.chmod(
            target,
            0o755 if member.mode & 0o111 else 0o644,
            follow_symlinks=False,
        )
if len(seen) != expected_count:
    raise SystemExit(47)
staged.unlink()
"""
RUNTIME_RESOURCES = {
    "cpus": "2.0",
    "memory_bytes": 2147483648,
    "memory_swap_bytes": 2147483648,
    "pids_limit": 256,
    "timeout_seconds": 1800,
    "tmpfs": [
        "/tmp:rw,nosuid,nodev,noexec,size=268435456,mode=1777",
        "/workspace:rw,nosuid,nodev,noexec,size=536870912,mode=0755",
    ],
}


class RuntimePolicyValidationError(ValueError):
    """Raised when the pinned test-container policy or dispatch drifts."""


def _closed_const(value: Any) -> dict[str, Any]:
    return {"const": value}


RUNTIME_POLICY_SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "additionalProperties": False,
    "required": [
        "authority",
        "base_image",
        "build",
        "execution_scope",
        "runtime",
        "runtime_image_identity",
        "schema_version",
    ],
    "properties": {
        "authority": _closed_const(AUTHORITY),
        "base_image": _closed_const(BASE_IMAGE),
        "build": _closed_const(
            {
                "base_image_acquisition_network_profile": (
                    BASE_IMAGE_ACQUISITION_NETWORK_PROFILE
                ),
                "build_parameters": BUILD_PARAMETERS,
                "docker_build_run_network": "none",
                "requirements_lock": "infra-tests/phase8/runtime/requirements.lock",
                "wheelhouse_acquisition_network_profile": (
                    WHEELHOUSE_ACQUISITION_NETWORK_PROFILE
                ),
                "wheelhouse_context": "external-read-only-linux-amd64-wheels",
            }
        ),
        "execution_scope": _closed_const(
            {
                "forbidden_command_ids": list(FORBIDDEN_COMMAND_IDS),
                "forbidden_execution_claims": list(FORBIDDEN_EXECUTION_CLAIMS),
                "supported_backend_kind": STATIC_BACKEND_KIND,
                "supported_command_ids": list(SUPPORTED_COMMAND_IDS),
            }
        ),
        "runtime": {
            "type": "object",
            "additionalProperties": False,
            "required": [
                "cap_drop",
                "candidate_source",
                "credentials",
                "fixed_environment",
                "host_namespace_sharing",
                "mounts",
                "network",
                "platform",
                "privileged",
                "pull",
                "read_only_rootfs",
                "required_flags",
                "resources",
                "security_opt",
                "user",
                "workdir",
            ],
            "properties": {
                "cap_drop": _closed_const(["ALL"]),
                "candidate_source": _closed_const(CANDIDATE_SOURCE_POLICY),
                "credentials": _closed_const(
                    {
                        "forbidden_environment_patterns": [
                            "(?i).*authorization.*",
                            "(?i).*cookie.*",
                            "(?i).*credential.*",
                            "(?i).*password.*",
                            "(?i).*secret.*",
                            "(?i).*token.*",
                        ],
                        "host_environment_inherited": False,
                        "host_credentials_allowed": False,
                        "secrets": [],
                    }
                ),
                "fixed_environment": _closed_const(FIXED_ENVIRONMENT),
                "host_namespace_sharing": _closed_const(
                    {
                        "cgroup": False,
                        "ipc": False,
                        "network": False,
                        "pid": False,
                        "user": False,
                        "uts": False,
                    }
                ),
                "mounts": _closed_const(MOUNT_POLICY),
                "network": _closed_const("none"),
                "platform": _closed_const("linux/amd64"),
                "privileged": _closed_const(False),
                "pull": _closed_const("never"),
                "read_only_rootfs": _closed_const(True),
                "required_flags": _closed_const(list(REQUIRED_RUNTIME_FLAGS)),
                "resources": _closed_const(RUNTIME_RESOURCES),
                "security_opt": _closed_const(["no-new-privileges:true"]),
                "user": _closed_const("65532:65532"),
                "workdir": _closed_const("/workspace"),
            },
        },
        "runtime_image_identity": _closed_const(
            {
                "accepted_form": "sha256:<64-lowercase-hex-image-id>",
                "tag_reference_allowed": False,
            }
        ),
        "schema_version": _closed_const(SCHEMA_VERSION),
    },
}


def canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exception:
        raise RuntimePolicyValidationError(
            f"runtime policy value is not canonical JSON: {exception}"
        ) from exception


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _reject_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise RuntimePolicyValidationError(f"duplicate JSON key rejected: {key!r}")
        result[key] = value
    return result


def _assert_bounded_tree(
    value: Any, *, context: str, max_nodes: int = MAX_JSON_NODES
) -> None:
    nodes = 0
    stack = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > max_nodes:
            raise RuntimePolicyValidationError(f"{context} exceeds node limit")
        if depth > MAX_JSON_DEPTH:
            raise RuntimePolicyValidationError(f"{context} exceeds depth limit")
        if isinstance(current, dict):
            stack.extend((item, depth + 1) for item in current.values())
        elif isinstance(current, list):
            stack.extend((item, depth + 1) for item in current)


def _parse_strict_json_bytes(
    raw: bytes, *, max_bytes: int, context: str
) -> dict[str, Any]:
    if not isinstance(raw, bytes):
        raise RuntimePolicyValidationError(f"{context} input must be bytes")
    if not raw or len(raw) > max_bytes:
        raise RuntimePolicyValidationError(f"{context} byte length is out of bounds")
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise RuntimePolicyValidationError(f"{context} must be BOM-free UTF-8")
    try:
        document = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                RuntimePolicyValidationError(
                    f"non-finite JSON number rejected: {token}"
                )
            ),
        )
    except RuntimePolicyValidationError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise RuntimePolicyValidationError(
            f"{context} is not strict UTF-8 JSON"
        ) from exception
    if not isinstance(document, dict):
        raise RuntimePolicyValidationError(f"{context} root must be an object")
    _assert_bounded_tree(document, context=context)
    return document


def parse_bounded_json_bytes(raw: bytes) -> dict[str, Any]:
    return _parse_strict_json_bytes(
        raw, max_bytes=MAX_POLICY_BYTES, context="runtime policy"
    )


def parse_receipt_json_bytes(raw: bytes) -> dict[str, Any]:
    return _parse_strict_json_bytes(
        raw, max_bytes=MAX_RECEIPT_BYTES, context="runtime receipt"
    )


def parse_materialization_manifest_bytes(raw: bytes) -> list[dict[str, Any]]:
    if not isinstance(raw, bytes):
        raise RuntimePolicyValidationError(
            "materialization manifest input must be bytes"
        )
    if not raw or len(raw) > MAX_MANIFEST_BYTES:
        raise RuntimePolicyValidationError(
            "materialization manifest byte length is out of bounds"
        )
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise RuntimePolicyValidationError(
            "materialization manifest must be BOM-free UTF-8"
        )
    try:
        document = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                RuntimePolicyValidationError(
                    f"non-finite JSON number rejected: {token}"
                )
            ),
        )
    except RuntimePolicyValidationError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise RuntimePolicyValidationError(
            "materialization manifest is not strict UTF-8 JSON"
        ) from exception
    if not isinstance(document, list):
        raise RuntimePolicyValidationError(
            "materialization manifest root must be an array"
        )
    _assert_bounded_tree(
        document,
        context="materialization manifest",
        max_nodes=MAX_MANIFEST_ENTRIES * 8 + 1,
    )
    return document


def _expected_runtime_policy() -> dict[str, Any]:
    properties = RUNTIME_POLICY_SCHEMA["properties"]
    runtime_properties = properties["runtime"]["properties"]
    return {
        "authority": copy.deepcopy(properties["authority"]["const"]),
        "base_image": copy.deepcopy(properties["base_image"]["const"]),
        "build": copy.deepcopy(properties["build"]["const"]),
        "execution_scope": copy.deepcopy(properties["execution_scope"]["const"]),
        "runtime": {
            key: copy.deepcopy(value["const"])
            for key, value in runtime_properties.items()
        },
        "runtime_image_identity": copy.deepcopy(
            properties["runtime_image_identity"]["const"]
        ),
        "schema_version": copy.deepcopy(properties["schema_version"]["const"]),
    }


def _assert_policy_invariants(document: Mapping[str, Any]) -> None:
    scope = document["execution_scope"]
    runtime = document["runtime"]
    if tuple(scope["supported_command_ids"]) != SUPPORTED_COMMAND_IDS:
        raise RuntimePolicyValidationError("supported static command IDs drifted")
    if tuple(scope["forbidden_command_ids"]) != FORBIDDEN_COMMAND_IDS:
        raise RuntimePolicyValidationError("forbidden Maven command IDs drifted")
    if set(SUPPORTED_COMMAND_IDS).intersection(FORBIDDEN_COMMAND_IDS):
        raise RuntimePolicyValidationError("command authority sets overlap")
    if set((*SUPPORTED_COMMAND_IDS, *FORBIDDEN_COMMAND_IDS)) != set(
        command_contract.COMMAND_ORDER
    ):
        raise RuntimePolicyValidationError(
            "runtime policy does not classify every command"
        )
    if runtime["network"] != "none" or runtime["read_only_rootfs"] is not True:
        raise RuntimePolicyValidationError("network or root filesystem policy drifted")
    if runtime["cap_drop"] != ["ALL"] or runtime["privileged"] is not False:
        raise RuntimePolicyValidationError("container privilege policy drifted")
    if runtime["required_flags"] != list(REQUIRED_RUNTIME_FLAGS):
        raise RuntimePolicyValidationError("effective Docker flags drifted")
    if runtime["fixed_environment"] != FIXED_ENVIRONMENT:
        raise RuntimePolicyValidationError("fixed runtime environment drifted")
    if runtime["mounts"] != MOUNT_POLICY:
        raise RuntimePolicyValidationError("zero-mount runtime policy drifted")
    if runtime["candidate_source"] != CANDIDATE_SOURCE_POLICY:
        raise RuntimePolicyValidationError("candidate archive copy policy drifted")
    if document["runtime_image_identity"]["tag_reference_allowed"] is not False:
        raise RuntimePolicyValidationError("tag-based runtime images are forbidden")


def validate_runtime_policy(document: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(document, Mapping):
        raise RuntimePolicyValidationError("runtime policy must be a mapping")
    candidate = copy.deepcopy(dict(document))
    if candidate != _expected_runtime_policy():
        raise RuntimePolicyValidationError(
            "runtime policy fields, values, or closed-world types drifted"
        )
    _assert_policy_invariants(candidate)
    policy_sha256 = canonical_sha256(candidate)
    if policy_sha256 != EXPECTED_POLICY_SHA256:
        raise RuntimePolicyValidationError(
            f"runtime policy differs from the pinned payload: {policy_sha256}"
        )
    return candidate


def load_runtime_policy(path: Path = POLICY_PATH) -> dict[str, Any]:
    if path != POLICY_PATH:
        raise RuntimePolicyValidationError(
            "only the repository runtime policy may be loaded"
        )
    try:
        metadata = path.lstat()
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "runtime policy cannot be inspected"
        ) from exception
    if path.is_symlink() or not path.is_file() or metadata.st_size > MAX_POLICY_BYTES:
        raise RuntimePolicyValidationError(
            "runtime policy must be one bounded regular file"
        )
    return validate_runtime_policy(parse_bounded_json_bytes(path.read_bytes()))


def _validated_contract_command(
    command: Mapping[str, Any], validated_command_contract: Mapping[str, Any] | None
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(command, Mapping):
        raise RuntimePolicyValidationError("command must be a mapping")
    command_id = command.get("id")
    if command_id not in command_contract.COMMAND_ORDER:
        raise RuntimePolicyValidationError(
            "command ID is not in the validated contract"
        )
    contract = (
        command_contract.load_command_contract()
        if validated_command_contract is None
        else command_contract.validate_command_contract(validated_command_contract)
    )
    expected = next(item for item in contract["commands"] if item["id"] == command_id)
    candidate = copy.deepcopy(dict(command))
    if candidate != expected:
        raise RuntimePolicyValidationError(
            "command argv, environment, cwd, report, or execution controls drifted"
        )
    return copy.deepcopy(expected), contract


def assert_command_authorized(
    command: Mapping[str, Any],
    policy: Mapping[str, Any],
    *,
    validated_command_contract: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    validated_policy = validate_runtime_policy(policy)
    validated_command, _ = _validated_contract_command(
        command, validated_command_contract
    )
    scope = validated_policy["execution_scope"]
    if validated_command["backend_kind"] != scope["supported_backend_kind"]:
        raise RuntimePolicyValidationError(
            "command backend is not the pinned test container"
        )
    if validated_command["id"] not in scope["supported_command_ids"]:
        raise RuntimePolicyValidationError(
            "test container has no authority for this command ID"
        )
    fixed_environment = validated_policy["runtime"]["fixed_environment"]
    if any(
        fixed_environment.get(key) != value
        for key, value in validated_command["environment"].items()
    ):
        raise RuntimePolicyValidationError(
            "contract environment exceeds the fixed runtime"
        )
    return validated_command


def _assert_exact_keys(
    value: Any, expected_keys: set[str], *, context: str
) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise RuntimePolicyValidationError(f"{context} must be a mapping")
    candidate = copy.deepcopy(dict(value))
    if set(candidate) != expected_keys:
        raise RuntimePolicyValidationError(f"{context} fields drifted")
    return candidate


def _assert_sha1(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        raise RuntimePolicyValidationError(f"{context} must be a lowercase Git SHA")
    return value


def _assert_sha256(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise RuntimePolicyValidationError(f"{context} must be a lowercase SHA-256")
    return value


def _assert_nonce(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or NONCE.fullmatch(value) is None:
        raise RuntimePolicyValidationError(f"{context} must be a 256-bit nonce")
    return value


def canonical_receipt_sha256(receipt: Mapping[str, Any]) -> str:
    payload = copy.deepcopy(dict(receipt))
    payload.pop("receipt_sha256", None)
    return canonical_sha256(payload)


def canonical_junit_file_index_sha256(entries: Any) -> str:
    if not isinstance(entries, list) or not 1 <= len(entries) <= 32:
        raise RuntimePolicyValidationError("JUnit file index size is out of bounds")
    validated: list[dict[str, Any]] = []
    for entry in entries:
        candidate = _assert_exact_keys(
            entry, {"archive_path", "bytes", "sha256"}, context="JUnit file index entry"
        )
        archive_path = candidate["archive_path"]
        if (
            not isinstance(archive_path, str)
            or not archive_path.startswith("p/")
            or not archive_path.endswith(".xml")
            or "\\" in archive_path
            or ".." in PurePosixPath(archive_path).parts
            or len(archive_path) > 256
        ):
            raise RuntimePolicyValidationError("JUnit archive path is not canonical")
        byte_count = candidate["bytes"]
        if type(byte_count) is not int or not 1 <= byte_count <= 128 * 1024 * 1024:
            raise RuntimePolicyValidationError(
                "JUnit artifact byte count is out of bounds"
            )
        _assert_sha256(candidate["sha256"], context="JUnit artifact")
        validated.append(candidate)
    if [entry["archive_path"] for entry in validated] != sorted(
        entry["archive_path"] for entry in validated
    ):
        raise RuntimePolicyValidationError(
            "JUnit file index is not canonically ordered"
        )
    if len({entry["archive_path"] for entry in validated}) != len(validated):
        raise RuntimePolicyValidationError("JUnit file index contains duplicate paths")
    return canonical_sha256(validated)


_RUN_BINDING_KEYS = {
    "caller_workflow_ref",
    "caller_workflow_sha",
    "repository",
    "repository_id",
    "run_attempt",
    "run_id",
    "runner_arch",
    "runner_environment",
    "runner_os",
    "trusted_workflow_path",
    "trusted_workflow_ref",
    "trusted_workflow_repository",
    "trusted_workflow_sha",
}
_JOB_IDENTITY_KEYS = _RUN_BINDING_KEYS | {"job_name", "schema_version"}


def validate_expected_run_binding(value: Any) -> tuple[dict[str, Any], str]:
    binding = _assert_exact_keys(
        value, _RUN_BINDING_KEYS, context="trusted GitHub run binding"
    )
    repository = binding["repository"]
    if (
        not isinstance(repository, str)
        or re.fullmatch(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}", repository)
        is None
    ):
        raise RuntimePolicyValidationError("GitHub repository identity is malformed")
    if binding["repository_id"] != GITHUB_REPOSITORY_ID:
        raise RuntimePolicyValidationError("GitHub immutable repository ID drifted")
    run_id = binding["run_id"]
    if not isinstance(run_id, str) or re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is None:
        raise RuntimePolicyValidationError("GitHub job run ID is malformed")
    run_attempt = binding["run_attempt"]
    if type(run_attempt) is not int or not 1 <= run_attempt <= 1000:
        raise RuntimePolicyValidationError("GitHub job run attempt is malformed")
    if (
        binding["runner_os"] != "Linux"
        or binding["runner_arch"] != "X64"
        or binding["runner_environment"] != "github-hosted"
    ):
        raise RuntimePolicyValidationError("GitHub runner class drifted")
    caller_sha = _assert_sha1(
        binding["caller_workflow_sha"], context="GitHub caller workflow SHA"
    )
    trusted_sha = _assert_sha1(
        binding["trusted_workflow_sha"], context="trusted workflow SHA"
    )
    if binding["caller_workflow_ref"] != (
        f"{repository}/{CALLER_WORKFLOW_PATH}@{FIXED_CALLER_WORKFLOW_REF}"
    ):
        raise RuntimePolicyValidationError("GitHub caller workflow ref drifted")
    if (
        binding["trusted_workflow_repository"] != repository
        or binding["trusted_workflow_path"] != TRUSTED_WORKFLOW_PATH
        or binding["trusted_workflow_ref"]
        != f"{repository}/{TRUSTED_WORKFLOW_PATH}@{trusted_sha}"
    ):
        raise RuntimePolicyValidationError("trusted reusable workflow binding drifted")
    del caller_sha
    return binding, canonical_sha256(binding)


def validate_github_job_identity(
    value: Any,
    *,
    allowed_job_names: tuple[str, ...],
    expected_run_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], str]:
    expected, _ = validate_expected_run_binding(expected_run_binding)
    identity = _assert_exact_keys(
        value, _JOB_IDENTITY_KEYS, context="GitHub job identity"
    )
    if identity["schema_version"] != GITHUB_JOB_IDENTITY_SCHEMA_VERSION:
        raise RuntimePolicyValidationError("GitHub job identity schema drifted")
    if identity["job_name"] not in allowed_job_names:
        raise RuntimePolicyValidationError("GitHub job name is not authorized")
    projection = {key: identity[key] for key in _RUN_BINDING_KEYS}
    if projection != expected:
        raise RuntimePolicyValidationError("GitHub job belongs to another trusted run")
    return identity, canonical_sha256(identity)


def _git_blob_sha1(payload: bytes) -> str:
    header = f"blob {len(payload)}\0".encode("ascii")
    return hashlib.sha1(header + payload).hexdigest()


def _lstat_identity(metadata: os.stat_result) -> list[int]:
    return [
        int(metadata.st_dev),
        int(metadata.st_ino),
        int(metadata.st_mode),
        int(metadata.st_nlink),
        int(metadata.st_size),
        int(metadata.st_mtime_ns),
        int(getattr(metadata, "st_file_attributes", 0)),
    ]


def _is_link_or_reparse(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def _assert_ustar_path_representable(path: str) -> None:
    try:
        encoded = path.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exception:
        raise RuntimePolicyValidationError(
            "materialization path is not deterministic UTF-8"
        ) from exception
    if len(encoded) <= 100:
        return
    for separator in range(len(encoded) - 1, -1, -1):
        if encoded[separator : separator + 1] != b"/":
            continue
        prefix = encoded[:separator]
        name = encoded[separator + 1 :]
        if prefix and name and len(prefix) <= 155 and len(name) <= 100:
            return
    raise RuntimePolicyValidationError(
        "materialization path cannot be represented by deterministic USTAR"
    )


def validate_materialization_manifest(
    manifest: Any,
) -> tuple[list[dict[str, Any]], str, int, int]:
    if not isinstance(manifest, list) or len(manifest) > MAX_MANIFEST_ENTRIES:
        raise RuntimePolicyValidationError(
            "materialization manifest size is out of bounds"
        )
    validated: list[dict[str, Any]] = []
    total_bytes = 0
    previous_path: str | None = None
    for value in manifest:
        entry = _assert_exact_keys(
            value,
            {"git_blob_sha", "mode", "path", "sha256", "size", "type"},
            context="materialization manifest entry",
        )
        path = entry["path"]
        if (
            not isinstance(path, str)
            or not path
            or len(path) > 512
            or "\\" in path
            or ":" in path
            or "\x00" in path
            or any(ord(character) < 32 for character in path)
        ):
            raise RuntimePolicyValidationError(
                "materialization manifest path is unsafe"
            )
        pure = PurePosixPath(path)
        if (
            pure.is_absolute()
            or pure.as_posix() != path
            or any(part in {"", ".", "..", ".git"} for part in pure.parts)
        ):
            raise RuntimePolicyValidationError(
                "materialization manifest path is not canonical"
            )
        _assert_ustar_path_representable(path)
        if previous_path is not None and path <= previous_path:
            raise RuntimePolicyValidationError(
                "materialization manifest paths are not strictly ordered"
            )
        previous_path = path
        if entry["type"] != "blob" or entry["mode"] not in {"100644", "100755"}:
            raise RuntimePolicyValidationError(
                "materialization manifest type or mode drifted"
            )
        size = entry["size"]
        if type(size) is not int or not 0 <= size <= MAX_MATERIALIZED_BYTES:
            raise RuntimePolicyValidationError(
                "materialization manifest file size is invalid"
            )
        total_bytes += size
        if total_bytes > MAX_MATERIALIZED_BYTES:
            raise RuntimePolicyValidationError(
                "materialized tree byte total is out of bounds"
            )
        _assert_sha256(entry["sha256"], context="materialized blob")
        _assert_sha1(entry["git_blob_sha"], context="materialized Git blob")
        validated.append(entry)
    return validated, canonical_sha256(validated), len(validated), total_bytes


def _hash_regular_file_no_follow(
    path: Path, *, max_bytes: int
) -> tuple[int, str, str, list[int]]:
    try:
        before = os.lstat(path)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "bounded file cannot be inspected"
        ) from exception
    if (
        _is_link_or_reparse(before)
        or not stat.S_ISREG(before.st_mode)
        or before.st_nlink != 1
        or not 0 <= before.st_size <= max_bytes
    ):
        raise RuntimePolicyValidationError(
            "bounded file is linked, aliased, non-regular, or oversized"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "bounded file cannot be opened no-follow"
        ) from exception
    sha256 = hashlib.sha256()
    git_blob = hashlib.sha1(f"blob {before.st_size}\0".encode("ascii"))
    total = 0
    try:
        opened = os.fstat(descriptor)
        if _lstat_identity(opened) != _lstat_identity(before):
            raise RuntimePolicyValidationError(
                "bounded file identity changed before hashing"
            )
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                raise RuntimePolicyValidationError("bounded file grew while hashing")
            sha256.update(chunk)
            git_blob.update(chunk)
        after_open = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    try:
        after_path = os.lstat(path)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "bounded file vanished after hashing"
        ) from exception
    if (
        total != before.st_size
        or _lstat_identity(after_open) != _lstat_identity(before)
        or _lstat_identity(after_path) != _lstat_identity(before)
    ):
        raise RuntimePolicyValidationError("bounded file changed while hashing")
    return total, sha256.hexdigest(), git_blob.hexdigest(), _lstat_identity(before)


_CANDIDATE_ARCHIVE_HANDLE_SEAL = object()


class ValidatedCandidateArchive:
    __slots__ = (
        "_closed",
        "_consumed",
        "_evidence",
        "_fd",
        "_identity",
        "_seal",
        "bytes",
        "sha256",
    )

    def __init__(
        self,
        *,
        seal: object,
        descriptor: int,
        byte_count: int,
        entry_count: int,
        normalized_path: str,
        physical_identity: list[int],
        sha256: str,
    ) -> None:
        if seal is not _CANDIDATE_ARCHIVE_HANDLE_SEAL:
            raise RuntimePolicyValidationError(
                "candidate archive handle is not trusted"
            )
        self._seal = seal
        self._fd = descriptor
        self._closed = False
        self._consumed = False
        self._identity = tuple(physical_identity)
        self.bytes = byte_count
        self.sha256 = sha256
        identity_kind = (
            "DEVICE_INODE"
            if physical_identity[0] > 0 and physical_identity[1] > 0
            else "CANONICAL_PATH_SINGLE_LINK"
        )
        self._evidence = {
            "archive_bytes": byte_count,
            "archive_entry_count": entry_count,
            "archive_format": CANDIDATE_ARCHIVE_FORMAT,
            "archive_path": normalized_path,
            "archive_physical_identity": physical_identity,
            "archive_sha256": sha256,
            "physical_identity_kind": identity_kind,
        }

    def __enter__(self) -> ValidatedCandidateArchive:
        if self._closed:
            raise RuntimePolicyValidationError("candidate archive handle is closed")
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def evidence(self) -> dict[str, Any]:
        if self._seal is not _CANDIDATE_ARCHIVE_HANDLE_SEAL:
            raise RuntimePolicyValidationError(
                "candidate archive handle is not trusted"
            )
        return copy.deepcopy(self._evidence)

    def stream_into(self, sink: BinaryIO) -> dict[str, Any]:
        if (
            self._closed
            or self._consumed
            or self._seal is not _CANDIDATE_ARCHIVE_HANDLE_SEAL
        ):
            raise RuntimePolicyValidationError(
                "candidate archive handle cannot be reused"
            )
        if not hasattr(sink, "write"):
            raise RuntimePolicyValidationError("candidate archive sink is not writable")
        self._consumed = True
        os.lseek(self._fd, 0, os.SEEK_SET)
        before = os.fstat(self._fd)
        if tuple(_lstat_identity(before)) != self._identity:
            raise RuntimePolicyValidationError(
                "candidate archive identity changed before streaming"
            )
        digest = hashlib.sha256()
        total = 0
        try:
            while True:
                chunk = os.read(self._fd, 1024 * 1024)
                if not chunk:
                    break
                view = memoryview(chunk)
                while view:
                    written = sink.write(view)
                    if type(written) is not int or written <= 0 or written > len(view):
                        raise RuntimePolicyValidationError(
                            "candidate archive sink did not accept the complete stream"
                        )
                    view = view[written:]
                total += len(chunk)
                if total > self.bytes:
                    raise RuntimePolicyValidationError(
                        "candidate archive grew while streaming"
                    )
                digest.update(chunk)
            if hasattr(sink, "flush"):
                sink.flush()
            after = os.fstat(self._fd)
            if (
                tuple(_lstat_identity(after)) != self._identity
                or total != self.bytes
                or digest.hexdigest() != self.sha256
            ):
                raise RuntimePolicyValidationError(
                    "candidate archive changed between validation and consumption"
                )
            return {
                **self.evidence(),
                "consumption_method": "VALIDATED_FD_STREAM_V1",
            }
        finally:
            self.close()

    def close(self) -> None:
        if not getattr(self, "_closed", True):
            os.close(self._fd)
            self._closed = True

    def __del__(self) -> None:
        try:
            self.close()
        except (AttributeError, OSError):
            pass


def _open_validated_candidate_archive(
    path_value: str | os.PathLike[str],
    manifest: list[dict[str, Any]],
    *,
    expected_bytes: int,
    expected_sha256: str,
    expected_entry_count: int,
) -> ValidatedCandidateArchive:
    try:
        path_text = os.fspath(path_value)
    except TypeError as exception:
        raise RuntimePolicyValidationError(
            "candidate archive path must be path-like"
        ) from exception
    if (
        not isinstance(path_text, str)
        or not path_text
        or len(path_text) > MAX_SOURCE_IDENTITY_BYTES
        or path_text.startswith(("\\\\", "//", "\\\\?\\", "\\\\.\\"))
        or any(part in {".", ".."} for part in re.split(r"[\\/]", path_text) if part)
    ):
        raise RuntimePolicyValidationError("candidate archive path is unsafe")
    path = Path(path_text)
    if not path.is_absolute():
        raise RuntimePolicyValidationError("candidate archive path must be absolute")
    normalized_path = os.path.normcase(os.path.abspath(path_text))
    try:
        before_path = os.lstat(path)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "candidate archive cannot be inspected"
        ) from exception
    if _is_link_or_reparse(before_path):
        raise RuntimePolicyValidationError(
            "candidate archive cannot be a link or reparse point"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "candidate archive cannot be opened no-follow"
        ) from exception
    try:
        before = os.fstat(descriptor)
        if (
            _is_link_or_reparse(before)
            or not stat.S_ISREG(before.st_mode)
            or before.st_nlink != 1
            or not 1 <= before.st_size <= MAX_CANDIDATE_ARCHIVE_BYTES
            or _lstat_identity(before) != _lstat_identity(before_path)
        ):
            raise RuntimePolicyValidationError(
                "candidate archive is linked or non-regular"
            )
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_CANDIDATE_ARCHIVE_BYTES:
                raise RuntimePolicyValidationError(
                    "candidate archive exceeds its byte limit"
                )
            digest.update(chunk)
        archive_sha = digest.hexdigest()
        if total != expected_bytes or archive_sha != expected_sha256:
            raise RuntimePolicyValidationError(
                "candidate archive bytes or digest drifted"
            )
        if expected_entry_count != len(manifest):
            raise RuntimePolicyValidationError("candidate archive entry count drifted")
        os.lseek(descriptor, 0, os.SEEK_SET)
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            with tarfile.open(fileobj=stream, mode="r:") as archive:
                members = archive.getmembers()
                if len(members) != len(manifest):
                    raise RuntimePolicyValidationError(
                        "candidate archive path set drifted"
                    )
                expected_offset = 0
                for member, expected in zip(members, manifest, strict=True):
                    stream.seek(member.offset)
                    raw_header = stream.read(512)
                    raw_name = raw_header[0:100].split(b"\0", 1)[0]
                    raw_prefix = raw_header[345:500].split(b"\0", 1)[0]
                    encoded_member_name = (
                        raw_prefix + b"/" + raw_name if raw_prefix else raw_name
                    )
                    if (
                        member.name != expected["path"]
                        or encoded_member_name
                        != expected["path"].encode("utf-8", errors="strict")
                        or member.offset != expected_offset
                        or member.offset_data != member.offset + 512
                        or len(raw_header) != 512
                        or raw_header[257:263] != b"ustar\0"
                        or raw_header[263:265] != b"00"
                        or raw_header[156:157] not in {b"\0", b"0"}
                        or not member.isfile()
                        or member.issym()
                        or member.islnk()
                        or member.isdev()
                        or member.sparse is not None
                        or member.pax_headers
                        or member.uid != 0
                        or member.gid != 0
                        or member.uname not in {"", None}
                        or member.gname not in {"", None}
                        or member.mtime != 0
                        or member.size != expected["size"]
                        or member.mode & 0o7777
                        != (0o755 if expected["mode"] == "100755" else 0o644)
                    ):
                        raise RuntimePolicyValidationError(
                            "candidate archive metadata differs from the exact manifest"
                        )
                    extracted = archive.extractfile(member)
                    if extracted is None:
                        raise RuntimePolicyValidationError(
                            "candidate archive member is unreadable"
                        )
                    sha256 = hashlib.sha256()
                    git_blob = hashlib.sha1(f"blob {member.size}\0".encode("ascii"))
                    member_bytes = 0
                    while True:
                        chunk = extracted.read(1024 * 1024)
                        if not chunk:
                            break
                        member_bytes += len(chunk)
                        sha256.update(chunk)
                        git_blob.update(chunk)
                    if (
                        member_bytes != expected["size"]
                        or sha256.hexdigest() != expected["sha256"]
                        or git_blob.hexdigest() != expected["git_blob_sha"]
                    ):
                        raise RuntimePolicyValidationError(
                            "candidate archive content differs from the exact Git blobs"
                        )
                    expected_offset = (
                        member.offset_data + ((member.size + 511) // 512) * 512
                    )
                stream.seek(expected_offset)
                trailer = stream.read()
                if len(trailer) < 1024 or len(trailer) % 512 != 0 or any(trailer):
                    raise RuntimePolicyValidationError(
                        "candidate archive has a non-canonical USTAR trailer"
                    )
        after = os.fstat(descriptor)
        after_path = os.lstat(path)
        if _lstat_identity(after) != _lstat_identity(before) or _lstat_identity(
            after_path
        ) != _lstat_identity(before):
            raise RuntimePolicyValidationError(
                "candidate archive changed during validation"
            )
        os.lseek(descriptor, 0, os.SEEK_SET)
        return ValidatedCandidateArchive(
            seal=_CANDIDATE_ARCHIVE_HANDLE_SEAL,
            descriptor=descriptor,
            byte_count=total,
            entry_count=expected_entry_count,
            normalized_path=normalized_path,
            physical_identity=_lstat_identity(before),
            sha256=archive_sha,
        )
    except Exception:
        os.close(descriptor)
        raise


_MATERIALIZATION_RECEIPT_KEYS = {
    "accepted_a8",
    "candidate_sha",
    "candidate_tree_sha",
    "candidate_archive_bytes",
    "candidate_archive_entry_count",
    "candidate_archive_format",
    "candidate_archive_sha256",
    "closure_kind",
    "command_id",
    "created_nonce",
    "exact_git_blobs",
    "manifest_file_count",
    "manifest_sha256",
    "manifest_total_bytes",
    "producer_job_identity",
    "producer_job_identity_sha256",
    "receipt_kind",
    "receipt_sha256",
    "schema_version",
    "scope_inventory_sha256",
    "verified_nonce",
}
_EXPECTED_CANDIDATE_BINDING_KEYS = {
    "accepted_entry_sha",
    "candidate_sha",
    "candidate_tree_sha",
    "candidate_archive_bytes",
    "candidate_archive_entry_count",
    "candidate_archive_format",
    "candidate_archive_sha256",
    "closure_kind",
    "derived_inventory_sha256",
    "manifest_file_count",
    "manifest_sha256",
    "manifest_total_bytes",
}
_SCOPE_INVENTORY_KEYS = {"entries", "file_count", "manifest_sha256", "total_bytes"}


def validate_materialization_scope_inventory(
    scope_inventory: Mapping[str, Any], *, closure_kind: str
) -> tuple[dict[str, Any], list[dict[str, Any]], str]:
    if closure_kind not in MATERIALIZATION_CLOSURE_KINDS:
        raise RuntimePolicyValidationError("materialization closure kind is not fixed")
    candidate = _assert_exact_keys(
        scope_inventory,
        _SCOPE_INVENTORY_KEYS,
        context="trusted materialization scope inventory",
    )
    entries, _, file_count, total_bytes = validate_materialization_manifest(
        candidate["entries"]
    )
    if not entries:
        raise RuntimePolicyValidationError("trusted materialization scope is empty")
    if closure_kind == JAVA_SERVICE_ONLY and any(
        not entry["path"].startswith("java-api-service/") for entry in entries
    ):
        raise RuntimePolicyValidationError(
            "Java-only materialization contains a path outside java-api-service"
        )
    if candidate["file_count"] != file_count or candidate["total_bytes"] != total_bytes:
        raise RuntimePolicyValidationError(
            "trusted materialization scope summary differs from its entries"
        )
    manifest_sha256 = _assert_sha256(
        candidate["manifest_sha256"], context="trusted materialization manifest"
    )
    expected_manifest_sha256 = canonical_sha256(
        {
            "entries": entries,
            "file_count": file_count,
            "inventory_kind": closure_kind,
            "total_bytes": total_bytes,
        }
    )
    if manifest_sha256 != expected_manifest_sha256:
        raise RuntimePolicyValidationError(
            "trusted materialization manifest hash differs from its closed inventory"
        )
    return candidate, entries, canonical_sha256(candidate)


def _validate_materialization_receipt(
    receipt: Mapping[str, Any],
    materialization_manifest: Any,
    expected_candidate_binding: Mapping[str, Any],
    expected_scope_inventory: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    candidate_archive_path: str | os.PathLike[str],
) -> tuple[dict[str, Any], dict[str, Any], ValidatedCandidateArchive]:
    expected = _assert_exact_keys(
        expected_candidate_binding,
        _EXPECTED_CANDIDATE_BINDING_KEYS,
        context="trusted candidate materialization binding",
    )
    if expected["accepted_entry_sha"] != ACCEPTED_A8:
        raise RuntimePolicyValidationError("trusted materialization A8 binding drifted")
    _assert_sha1(expected["candidate_sha"], context="trusted candidate SHA")
    _assert_sha1(expected["candidate_tree_sha"], context="trusted candidate tree SHA")
    _assert_sha256(
        expected["derived_inventory_sha256"], context="trusted derived inventory"
    )
    _assert_sha256(
        expected["candidate_archive_sha256"], context="trusted candidate archive"
    )
    if (
        expected["candidate_archive_format"] != CANDIDATE_ARCHIVE_FORMAT
        or type(expected["candidate_archive_bytes"]) is not int
        or not 1 <= expected["candidate_archive_bytes"] <= MAX_CANDIDATE_ARCHIVE_BYTES
        or type(expected["candidate_archive_entry_count"]) is not int
        or expected["candidate_archive_entry_count"] < 1
    ):
        raise RuntimePolicyValidationError("trusted candidate archive summary drifted")
    closure_kind = expected["closure_kind"]
    scope_inventory, scope_entries, _ = validate_materialization_scope_inventory(
        expected_scope_inventory, closure_kind=closure_kind
    )
    expected_scope_summary = {
        "manifest_file_count": scope_inventory["file_count"],
        "manifest_sha256": scope_inventory["manifest_sha256"],
        "manifest_total_bytes": scope_inventory["total_bytes"],
    }
    if any(expected[key] != value for key, value in expected_scope_summary.items()):
        raise RuntimePolicyValidationError(
            "trusted candidate binding differs from its selected scope inventory"
        )
    if expected["candidate_archive_entry_count"] != scope_inventory["file_count"]:
        raise RuntimePolicyValidationError(
            "candidate archive entry count differs from scope"
        )
    candidate = _assert_exact_keys(
        receipt, _MATERIALIZATION_RECEIPT_KEYS, context="materialization receipt"
    )
    if candidate["schema_version"] != MATERIALIZATION_RECEIPT_SCHEMA_VERSION:
        raise RuntimePolicyValidationError("materialization receipt schema drifted")
    if candidate["receipt_kind"] != MATERIALIZATION_RECEIPT_KIND:
        raise RuntimePolicyValidationError("materialization receipt kind drifted")
    command_id = candidate["command_id"]
    if command_id not in COMMAND_JOB_NAMES:
        raise RuntimePolicyValidationError("materialization command is not frozen")
    if candidate["closure_kind"] != COMMAND_CLOSURE_KINDS[command_id]:
        raise RuntimePolicyValidationError(
            "materialization closure differs from its command"
        )
    _, producer_identity_sha = validate_github_job_identity(
        candidate["producer_job_identity"],
        allowed_job_names=(COMMAND_JOB_NAMES[command_id],),
        expected_run_binding=expected_run_binding,
    )
    if candidate["producer_job_identity_sha256"] != producer_identity_sha:
        raise RuntimePolicyValidationError(
            "materialization producer identity hash drifted"
        )
    _assert_sha1(candidate["candidate_sha"], context="candidate SHA")
    _assert_sha1(candidate["candidate_tree_sha"], context="candidate tree SHA")
    if candidate["accepted_a8"] != ACCEPTED_A8:
        raise RuntimePolicyValidationError(
            "materialization receipt accepted A8 drifted"
        )
    _assert_sha256(candidate["scope_inventory_sha256"], context="scope inventory")
    receipt_candidate_projection = {
        "accepted_entry_sha": candidate["accepted_a8"],
        "candidate_archive_bytes": candidate["candidate_archive_bytes"],
        "candidate_archive_entry_count": candidate["candidate_archive_entry_count"],
        "candidate_archive_format": candidate["candidate_archive_format"],
        "candidate_archive_sha256": candidate["candidate_archive_sha256"],
        "candidate_sha": candidate["candidate_sha"],
        "candidate_tree_sha": candidate["candidate_tree_sha"],
        "closure_kind": candidate["closure_kind"],
        "derived_inventory_sha256": candidate["scope_inventory_sha256"],
        "manifest_file_count": candidate["manifest_file_count"],
        "manifest_sha256": candidate["manifest_sha256"],
        "manifest_total_bytes": candidate["manifest_total_bytes"],
    }
    if receipt_candidate_projection != expected:
        raise RuntimePolicyValidationError(
            "materialization receipt differs from the trusted candidate binding"
        )
    created_nonce = _assert_nonce(candidate["created_nonce"], context="created nonce")
    verified_nonce = _assert_nonce(
        candidate["verified_nonce"], context="verified nonce"
    )
    if created_nonce == verified_nonce:
        raise RuntimePolicyValidationError(
            "receipt creation and verification nonces must differ"
        )
    if candidate["exact_git_blobs"] is not True:
        raise RuntimePolicyValidationError("materialization Git blob authority drifted")
    manifest, _, manifest_file_count, manifest_total_bytes = (
        validate_materialization_manifest(materialization_manifest)
    )
    if manifest != scope_entries:
        raise RuntimePolicyValidationError(
            "materialization manifest differs from the trusted scope closure"
        )
    manifest_summary = {
        "manifest_file_count": manifest_file_count,
        "manifest_sha256": scope_inventory["manifest_sha256"],
        "manifest_total_bytes": manifest_total_bytes,
    }
    if any(candidate[key] != value for key, value in manifest_summary.items()):
        raise RuntimePolicyValidationError("materialization manifest summary drifted")
    receipt_sha256 = _assert_sha256(
        candidate["receipt_sha256"], context="materialization receipt"
    )
    if receipt_sha256 != canonical_receipt_sha256(candidate):
        raise RuntimePolicyValidationError(
            "materialization receipt canonical hash drifted"
        )
    binding = copy.deepcopy(receipt_candidate_projection)
    archive_handle = _open_validated_candidate_archive(
        candidate_archive_path,
        manifest,
        expected_bytes=candidate["candidate_archive_bytes"],
        expected_sha256=candidate["candidate_archive_sha256"],
        expected_entry_count=candidate["candidate_archive_entry_count"],
    )
    return candidate, binding, archive_handle


def assert_materialization_authorized_live(
    receipt: Mapping[str, Any],
    materialization_manifest: Any,
    expected_candidate_binding: Mapping[str, Any],
    expected_scope_inventory: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    candidate_archive_path: str | os.PathLike[str],
) -> tuple[dict[str, Any], dict[str, Any], ValidatedCandidateArchive]:
    return _validate_materialization_receipt(
        receipt,
        materialization_manifest,
        expected_candidate_binding,
        expected_scope_inventory,
        expected_run_binding,
        candidate_archive_path,
    )


def verify_materialization_receipt_offline(
    receipt: Mapping[str, Any],
    materialization_manifest: Any,
    expected_candidate_binding: Mapping[str, Any],
    expected_scope_inventory: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    candidate_archive_path: str | os.PathLike[str],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    candidate, binding, archive_handle = _validate_materialization_receipt(
        receipt,
        materialization_manifest,
        expected_candidate_binding,
        expected_scope_inventory,
        expected_run_binding,
        candidate_archive_path,
    )
    with archive_handle:
        evidence = archive_handle.evidence()
    return candidate, binding, evidence


_RUNTIME_BUILD_RECEIPT_KEYS = {
    "base_image",
    "base_image_acquisition_network_profile",
    "build_parameters",
    "build_parameters_sha256",
    "build_nonce",
    "builder_job_identity",
    "builder_job_identity_sha256",
    "code_sha",
    "code_tree_sha",
    "command_contract_sha256",
    "config_digest",
    "dockerfile_git_blob",
    "dockerfile_sha256",
    "docker_build_run_network",
    "docker_archive_bytes",
    "docker_archive_sha256",
    "image_id",
    "image_inspect_projection",
    "image_inspect_projection_sha256",
    "oci_archive_bytes",
    "oci_archive_sha256",
    "platform",
    "receipt_kind",
    "receipt_sha256",
    "requirements_lock_git_blob",
    "requirements_lock_sha256",
    "rootfs_digest",
    "runtime_policy_sha256",
    "schema_version",
    "verified_nonce",
    "wheelhouse_manifest",
    "wheelhouse_manifest_sha256",
    "wheelhouse_acquisition_network_profile",
}
_BUILD_BINDING_KEYS = _RUNTIME_BUILD_RECEIPT_KEYS - {
    "build_nonce",
    "receipt_kind",
    "receipt_sha256",
    "schema_version",
    "verified_nonce",
}


def _assert_repository_build_inputs(receipt: Mapping[str, Any]) -> None:
    dockerfile_path = ROOT / "infra-tests/phase8/runtime/Dockerfile"
    lock_path = ROOT / "infra-tests/phase8/runtime/requirements.lock"
    try:
        dockerfile = dockerfile_path.read_bytes()
        requirements_lock = lock_path.read_bytes()
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "runtime build inputs cannot be read"
        ) from exception
    expected = {
        "dockerfile_git_blob": _git_blob_sha1(dockerfile),
        "dockerfile_sha256": hashlib.sha256(dockerfile).hexdigest(),
        "requirements_lock_git_blob": _git_blob_sha1(requirements_lock),
        "requirements_lock_sha256": hashlib.sha256(requirements_lock).hexdigest(),
    }
    if any(receipt[key] != value for key, value in expected.items()):
        raise RuntimePolicyValidationError("runtime build input blob identity drifted")


def _validate_runtime_build_receipt(
    receipt: Mapping[str, Any],
    policy: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    expected_builder_job_identity: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    candidate = _assert_exact_keys(
        receipt, _RUNTIME_BUILD_RECEIPT_KEYS, context="runtime build receipt"
    )
    if candidate["schema_version"] != RUNTIME_BUILD_RECEIPT_SCHEMA_VERSION:
        raise RuntimePolicyValidationError("runtime build receipt schema drifted")
    if candidate["receipt_kind"] != RUNTIME_BUILD_RECEIPT_KIND:
        raise RuntimePolicyValidationError("runtime build receipt kind drifted")
    if candidate["platform"] != "linux/amd64" or candidate["base_image"] != BASE_IMAGE:
        raise RuntimePolicyValidationError(
            "runtime build platform or base digest drifted"
        )
    if candidate["docker_build_run_network"] != "none":
        raise RuntimePolicyValidationError("Docker build RUN network was not denied")
    if (
        candidate["base_image_acquisition_network_profile"]
        != BASE_IMAGE_ACQUISITION_NETWORK_PROFILE
        or candidate["wheelhouse_acquisition_network_profile"]
        != WHEELHOUSE_ACQUISITION_NETWORK_PROFILE
    ):
        raise RuntimePolicyValidationError(
            "runtime build acquisition network profile drifted"
        )
    expected_builder, expected_builder_sha = validate_github_job_identity(
        expected_builder_job_identity,
        allowed_job_names=(BUILD_JOB_NAME,),
        expected_run_binding=expected_run_binding,
    )
    builder_identity, builder_identity_sha = validate_github_job_identity(
        candidate["builder_job_identity"],
        allowed_job_names=(BUILD_JOB_NAME,),
        expected_run_binding=expected_run_binding,
    )
    if (
        builder_identity != expected_builder
        or builder_identity_sha != expected_builder_sha
        or candidate["builder_job_identity_sha256"] != builder_identity_sha
    ):
        raise RuntimePolicyValidationError("builder GitHub job identity hash drifted")
    _assert_sha1(candidate["code_sha"], context="build code SHA")
    _assert_sha1(candidate["code_tree_sha"], context="build code tree SHA")
    image_id = candidate["image_id"]
    if not isinstance(image_id, str) or IMAGE_ID.fullmatch(image_id) is None:
        raise RuntimePolicyValidationError("runtime build image ID is not immutable")
    if candidate["config_digest"] != image_id:
        raise RuntimePolicyValidationError(
            "runtime image ID differs from config digest"
        )
    build_parameters = _assert_exact_keys(
        candidate["build_parameters"],
        set(BUILD_PARAMETERS),
        context="runtime build parameters",
    )
    if build_parameters != BUILD_PARAMETERS:
        raise RuntimePolicyValidationError(
            "runtime deterministic build parameters drifted"
        )
    if candidate["build_parameters_sha256"] != canonical_sha256(build_parameters):
        raise RuntimePolicyValidationError("runtime build parameters hash drifted")
    image_projection, image_projection_sha = _validate_image_inspect_projection(
        candidate["image_inspect_projection"]
    )
    if (
        candidate["image_inspect_projection_sha256"] != image_projection_sha
        or image_projection["image_id"] != image_id
        or candidate["rootfs_digest"]
        != canonical_sha256(image_projection["rootfs_layers"])
    ):
        raise RuntimePolicyValidationError("builder image projection drifted")
    wheelhouse_manifest, wheelhouse_manifest_sha = _validate_wheelhouse_manifest(
        candidate["wheelhouse_manifest"]
    )
    if candidate["wheelhouse_manifest_sha256"] != wheelhouse_manifest_sha:
        raise RuntimePolicyValidationError("builder wheelhouse manifest hash drifted")
    del wheelhouse_manifest
    for key in (
        "command_contract_sha256",
        "build_parameters_sha256",
        "dockerfile_sha256",
        "docker_archive_sha256",
        "image_inspect_projection_sha256",
        "oci_archive_sha256",
        "requirements_lock_sha256",
        "rootfs_digest",
        "runtime_policy_sha256",
        "wheelhouse_manifest_sha256",
    ):
        _assert_sha256(candidate[key], context=key)
    oci_archive_bytes = candidate["oci_archive_bytes"]
    if (
        type(oci_archive_bytes) is not int
        or not 1 <= oci_archive_bytes <= MAX_OCI_ARCHIVE_BYTES
    ):
        raise RuntimePolicyValidationError("OCI archive byte count is out of bounds")
    docker_archive_bytes = candidate["docker_archive_bytes"]
    if (
        type(docker_archive_bytes) is not int
        or not 1 <= docker_archive_bytes <= MAX_DOCKER_ARCHIVE_BYTES
    ):
        raise RuntimePolicyValidationError("Docker archive byte count is out of bounds")
    _assert_sha1(candidate["dockerfile_git_blob"], context="Dockerfile Git blob")
    _assert_sha1(
        candidate["requirements_lock_git_blob"], context="requirements lock Git blob"
    )
    build_nonce = _assert_nonce(candidate["build_nonce"], context="build nonce")
    verified_nonce = _assert_nonce(
        candidate["verified_nonce"], context="verified nonce"
    )
    if build_nonce == verified_nonce:
        raise RuntimePolicyValidationError("build and verification nonces must differ")
    validated_policy = validate_runtime_policy(policy)
    validated_contract = command_contract.validate_command_contract(
        validated_command_contract
    )
    if candidate["runtime_policy_sha256"] != canonical_sha256(validated_policy):
        raise RuntimePolicyValidationError("build receipt runtime policy hash drifted")
    if candidate["command_contract_sha256"] != command_contract.canonical_sha256(
        validated_contract
    ):
        raise RuntimePolicyValidationError(
            "build receipt command contract hash drifted"
        )
    _assert_repository_build_inputs(candidate)
    receipt_sha256 = _assert_sha256(
        candidate["receipt_sha256"], context="runtime build receipt"
    )
    if receipt_sha256 != canonical_receipt_sha256(candidate):
        raise RuntimePolicyValidationError(
            "runtime build receipt canonical hash drifted"
        )
    binding = {key: copy.deepcopy(candidate[key]) for key in _BUILD_BINDING_KEYS}
    return candidate, binding


_IMAGE_INSPECT_KEYS = {
    "architecture",
    "cmd",
    "config_digest",
    "entrypoint",
    "environment",
    "exposed_ports",
    "healthcheck",
    "image_id",
    "labels",
    "onbuild",
    "os",
    "rootfs_layers",
    "shell",
    "stop_signal",
    "user",
    "volumes",
    "workdir",
}
_BASE_IMAGE_INSPECT_KEYS = _IMAGE_INSPECT_KEYS | {"reference"}


def _validate_environment(value: Any, *, require_runtime: bool) -> list[str]:
    if not isinstance(value, list) or not 1 <= len(value) <= 128:
        raise RuntimePolicyValidationError("image environment size is out of bounds")
    environment: list[str] = []
    environment_map: dict[str, str] = {}
    for item in value:
        if (
            not isinstance(item, str)
            or not 3 <= len(item) <= 4096
            or "=" not in item
            or any(ord(character) < 32 for character in item)
        ):
            raise RuntimePolicyValidationError("image environment item is malformed")
        key, item_value = item.split("=", 1)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", key):
            raise RuntimePolicyValidationError("image environment key is malformed")
        if key in environment_map:
            raise RuntimePolicyValidationError(
                "image environment contains duplicate keys"
            )
        environment_map[key] = item_value
        environment.append(item)
    if require_runtime:
        if any(
            environment_map.get(key) != expected
            for key, expected in FIXED_ENVIRONMENT.items()
        ):
            raise RuntimePolicyValidationError(
                "runtime image fixed environment drifted"
            )
        if FORBIDDEN_RUNTIME_ENVIRONMENT_KEYS & set(environment_map):
            raise RuntimePolicyValidationError(
                "runtime image contains a dangerous extra environment key"
            )
    return environment


def _environment_mapping(value: list[str]) -> dict[str, str]:
    return {
        key: item_value for key, item_value in (item.split("=", 1) for item in value)
    }


def _validate_labels(value: Any, *, require_runtime: bool) -> dict[str, str]:
    if not isinstance(value, Mapping) or len(value) > 128:
        raise RuntimePolicyValidationError("image labels are malformed")
    labels = copy.deepcopy(dict(value))
    for key, item in labels.items():
        if (
            not isinstance(key, str)
            or not isinstance(item, str)
            or not key
            or len(key) > 256
            or len(item) > 4096
            or any(ord(character) < 32 for character in key + item)
        ):
            raise RuntimePolicyValidationError("image label entry is malformed")
    if require_runtime and any(
        labels.get(key) != item for key, item in REQUIRED_IMAGE_LABELS.items()
    ):
        raise RuntimePolicyValidationError("runtime image required labels drifted")
    return labels


def _validate_image_projection(
    value: Any, *, context: str, require_runtime: bool
) -> tuple[dict[str, Any], str]:
    projection = _assert_exact_keys(value, _IMAGE_INSPECT_KEYS, context=context)
    image_id = projection["image_id"]
    if not isinstance(image_id, str) or IMAGE_ID.fullmatch(image_id) is None:
        raise RuntimePolicyValidationError("observed image ID is not immutable")
    if projection["config_digest"] != image_id:
        raise RuntimePolicyValidationError(
            "observed image ID differs from config digest"
        )
    if projection["os"] != "linux" or projection["architecture"] != "amd64":
        raise RuntimePolicyValidationError("observed image platform drifted")
    projection["environment"] = _validate_environment(
        projection["environment"], require_runtime=require_runtime
    )
    projection["labels"] = _validate_labels(
        projection["labels"], require_runtime=require_runtime
    )
    for key in ("exposed_ports", "onbuild", "shell", "volumes"):
        items = projection[key]
        if (
            not isinstance(items, list)
            or len(items) > 128
            or any(
                not isinstance(item, str) or not item or len(item) > 1024
                for item in items
            )
        ):
            raise RuntimePolicyValidationError(f"observed image {key} is malformed")
    if projection["healthcheck"] is not None and not isinstance(
        projection["healthcheck"], Mapping
    ):
        raise RuntimePolicyValidationError("observed image healthcheck is malformed")
    if projection["stop_signal"] is not None and not isinstance(
        projection["stop_signal"], str
    ):
        raise RuntimePolicyValidationError("observed image stop signal is malformed")
    if require_runtime:
        if projection["user"] != "65532:65532" or projection["workdir"] != "/workspace":
            raise RuntimePolicyValidationError(
                "observed image process identity drifted"
            )
        if projection["cmd"] != ["python"] or projection["entrypoint"] is not None:
            raise RuntimePolicyValidationError("observed image command drifted")
        if any(
            projection[key] not in ([], None)
            for key in (
                "exposed_ports",
                "healthcheck",
                "onbuild",
                "shell",
                "stop_signal",
                "volumes",
            )
        ):
            raise RuntimePolicyValidationError(
                "runtime image adds an unapproved execution surface"
            )
    layers = projection["rootfs_layers"]
    if not isinstance(layers, list) or not 1 <= len(layers) <= 128:
        raise RuntimePolicyValidationError(
            "observed rootfs layer count is out of bounds"
        )
    for layer in layers:
        if (
            not isinstance(layer, str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", layer) is None
        ):
            raise RuntimePolicyValidationError(
                "observed rootfs layer digest is invalid"
            )
    return projection, canonical_sha256(projection)


_BUILD_PROVENANCE_KEYS = {
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
}
_BUILD_OBSERVATION_RECEIPT_KEYS = {
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
    "observer_nonce",
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
    "receipt_kind",
    "receipt_sha256",
    "schema_version",
    "source_build_nonce",
    "source_build_receipt_sha256",
    "wheelhouse_manifest",
    "wheelhouse_manifest_sha256",
}
_BUILD_OBSERVATION_BINDING_KEYS = {
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
}


def _validate_image_inspect_projection(value: Any) -> tuple[dict[str, Any], str]:
    return _validate_image_projection(
        value, context="raw runtime image inspect projection", require_runtime=True
    )


def _validate_base_image_inspect_projection(value: Any) -> tuple[dict[str, Any], str]:
    candidate = _assert_exact_keys(
        value, _BASE_IMAGE_INSPECT_KEYS, context="pinned base image inspect projection"
    )
    if candidate.pop("reference") != BASE_IMAGE:
        raise RuntimePolicyValidationError("observer inspected a different base image")
    projection, _ = _validate_image_projection(
        candidate, context="pinned base image config projection", require_runtime=False
    )
    with_reference = {"reference": BASE_IMAGE, **projection}
    return with_reference, canonical_sha256(with_reference)


def _requirements_lock_records() -> dict[str, tuple[str, str]]:
    lock_path = ROOT / "infra-tests/phase8/runtime/requirements.lock"
    try:
        payload = lock_path.read_text(encoding="ascii")
    except (OSError, UnicodeDecodeError) as exception:
        raise RuntimePolicyValidationError(
            "requirements lock cannot be read"
        ) from exception
    pattern = re.compile(
        r"(?m)^([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+-]+) \\\r?\n"
        r"    --hash=sha256:([0-9a-f]{64})$"
    )
    records: dict[str, tuple[str, str]] = {}
    for match in pattern.finditer(payload):
        name = re.sub(r"[-_.]+", "-", match.group(1)).lower()
        if name in records:
            raise RuntimePolicyValidationError("requirements lock contains duplicates")
        records[name] = (match.group(2), match.group(3))
    residue = pattern.sub("", payload)
    residue = re.sub(r"(?m)^#.*$", "", residue)
    if residue.strip() or len(records) != 15:
        raise RuntimePolicyValidationError(
            "requirements lock wheel hash closure drifted"
        )
    return records


def _wheel_distribution_and_version(filename: str) -> tuple[str, str]:
    parts = filename.removesuffix(".whl").split("-")
    if len(parts) not in {5, 6}:
        raise RuntimePolicyValidationError(
            "wheel filename does not follow PEP 427 shape"
        )
    distribution = re.sub(r"[-_.]+", "-", parts[0]).lower()
    version = parts[1].replace("_", "-")
    if not distribution or not version:
        raise RuntimePolicyValidationError("wheel distribution or version is empty")
    return distribution, version


def _validate_wheelhouse_manifest(value: Any) -> tuple[list[dict[str, Any]], str]:
    locked = _requirements_lock_records()
    if not isinstance(value, list) or len(value) != len(locked):
        raise RuntimePolicyValidationError("wheelhouse manifest size is out of bounds")
    manifest: list[dict[str, Any]] = []
    previous_filename: str | None = None
    seen_distributions: set[str] = set()
    for item in value:
        entry = _assert_exact_keys(
            item, {"bytes", "filename", "sha256"}, context="wheelhouse manifest entry"
        )
        filename = entry["filename"]
        if (
            not isinstance(filename, str)
            or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{2,255}\.whl", filename) is None
            or (previous_filename is not None and filename <= previous_filename)
        ):
            raise RuntimePolicyValidationError(
                "wheelhouse filename is unsafe or unordered"
            )
        previous_filename = filename
        byte_count = entry["bytes"]
        if type(byte_count) is not int or not 1 <= byte_count <= 256 * 1024 * 1024:
            raise RuntimePolicyValidationError("wheel byte count is out of bounds")
        _assert_sha256(entry["sha256"], context="wheel")
        distribution, version = _wheel_distribution_and_version(filename)
        locked_record = locked.get(distribution)
        if (
            distribution in seen_distributions
            or locked_record is None
            or locked_record != (version, entry["sha256"])
        ):
            raise RuntimePolicyValidationError(
                "wheelhouse distribution, version, or hash differs from the lock"
            )
        seen_distributions.add(distribution)
        manifest.append(entry)
    if seen_distributions != set(locked):
        raise RuntimePolicyValidationError(
            "wheelhouse differs from the hashed lock closure"
        )
    return manifest, canonical_sha256(manifest)


def parse_wheelhouse_manifest_bytes(raw: bytes) -> list[dict[str, Any]]:
    if not isinstance(raw, bytes) or not raw or len(raw) > MAX_MANIFEST_BYTES:
        raise RuntimePolicyValidationError(
            "wheelhouse manifest byte length is out of bounds"
        )
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise RuntimePolicyValidationError("wheelhouse manifest must be BOM-free UTF-8")
    try:
        document = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                RuntimePolicyValidationError(
                    f"non-finite JSON number rejected: {token}"
                )
            ),
        )
    except RuntimePolicyValidationError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise RuntimePolicyValidationError(
            "wheelhouse manifest is not strict UTF-8 JSON"
        ) from exception
    _assert_bounded_tree(document, context="wheelhouse manifest", max_nodes=64)
    manifest, _ = _validate_wheelhouse_manifest(document)
    if raw != canonical_json_bytes(manifest):
        raise RuntimePolicyValidationError(
            "wheelhouse manifest bytes are not canonical JSON"
        )
    return manifest


def validate_wheelhouse_directory(
    root_value: str | os.PathLike[str], expected_manifest: Any
) -> tuple[list[dict[str, Any]], str]:
    expected, expected_sha = _validate_wheelhouse_manifest(expected_manifest)
    try:
        root_text = os.fspath(root_value)
    except TypeError as exception:
        raise RuntimePolicyValidationError(
            "wheelhouse root must be path-like"
        ) from exception
    if (
        not isinstance(root_text, str)
        or not root_text
        or len(root_text) > MAX_SOURCE_IDENTITY_BYTES
    ):
        raise RuntimePolicyValidationError("wheelhouse root path is invalid")
    if root_text.startswith(("\\\\", "//", "\\\\?\\", "\\\\.\\")) or any(
        part in {".", ".."} for part in re.split(r"[\\/]", root_text) if part
    ):
        raise RuntimePolicyValidationError("wheelhouse root path is unsafe")
    root = Path(root_text)
    if not root.is_absolute():
        raise RuntimePolicyValidationError("wheelhouse root must be absolute")
    try:
        before = os.lstat(root)
        children = sorted(os.scandir(root), key=lambda item: item.name)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "wheelhouse cannot be inspected"
        ) from exception
    if _is_link_or_reparse(before) or not stat.S_ISDIR(before.st_mode):
        raise RuntimePolicyValidationError("wheelhouse root is linked or non-directory")
    if len(children) != len(expected):
        raise RuntimePolicyValidationError(
            "wheelhouse file count differs from the lock"
        )
    actual: list[dict[str, Any]] = []
    for child in children:
        if child.name not in {entry["filename"] for entry in expected}:
            raise RuntimePolicyValidationError("wheelhouse contains an unexpected file")
        size, digest, _, _ = _hash_regular_file_no_follow(
            Path(child.path), max_bytes=256 * 1024 * 1024
        )
        actual.append({"bytes": size, "filename": child.name, "sha256": digest})
    try:
        after = os.lstat(root)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "wheelhouse vanished after hashing"
        ) from exception
    if _lstat_identity(after) != _lstat_identity(before) or actual != expected:
        raise RuntimePolicyValidationError(
            "wheelhouse bytes differ from the exact manifest"
        )
    return actual, expected_sha


def _parse_oci_json(payload: bytes, *, context: str) -> dict[str, Any]:
    if not payload or len(payload) > 4 * 1024 * 1024:
        raise RuntimePolicyValidationError(f"{context} JSON size is out of bounds")
    return _parse_strict_json_bytes(payload, max_bytes=4 * 1024 * 1024, context=context)


def _gzip_oci_layer_diff_ids(
    path: Path, *, expected_identity: list[int], layer_names: set[str]
) -> dict[str, str]:
    if not layer_names:
        return {}
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "OCI archive cannot be reopened for gzip validation"
        ) from exception
    diff_ids: dict[str, str] = {}
    total_uncompressed_bytes = 0
    try:
        opened = os.fstat(descriptor)
        if _lstat_identity(opened) != expected_identity:
            raise RuntimePolicyValidationError(
                "OCI archive identity changed before gzip validation"
            )
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            with tarfile.open(fileobj=stream, mode="r:*") as archive:
                for member in archive.getmembers():
                    if member.name not in layer_names:
                        continue
                    extracted = archive.extractfile(member)
                    if extracted is None:
                        raise RuntimePolicyValidationError(
                            "compressed OCI layer cannot be read"
                        )
                    digest = hashlib.sha256()
                    try:
                        with gzip.GzipFile(fileobj=extracted, mode="rb") as decoded:
                            while True:
                                chunk = decoded.read(1024 * 1024)
                                if not chunk:
                                    break
                                total_uncompressed_bytes += len(chunk)
                                if total_uncompressed_bytes > MAX_OCI_ARCHIVE_BYTES:
                                    raise RuntimePolicyValidationError(
                                        "compressed OCI layers expand beyond bounds"
                                    )
                                digest.update(chunk)
                    except (EOFError, OSError) as exception:
                        raise RuntimePolicyValidationError(
                            "compressed OCI layer is not valid gzip"
                        ) from exception
                    diff_ids[member.name] = f"sha256:{digest.hexdigest()}"
        after_open = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    try:
        after_path = os.lstat(path)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "OCI archive vanished after gzip validation"
        ) from exception
    if (
        _lstat_identity(after_open) != expected_identity
        or _lstat_identity(after_path) != expected_identity
        or set(diff_ids) != layer_names
    ):
        raise RuntimePolicyValidationError(
            "compressed OCI layer identity or membership drifted"
        )
    return diff_ids


def _read_oci_archive_projection(
    path: Path, *, expected_identity: list[int]
) -> dict[str, Any]:
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "OCI archive cannot be opened no-follow"
        ) from exception
    files: dict[str, tuple[int, str, bytes | None]] = {}
    directories: set[str] = set()
    total_member_bytes = 0
    try:
        opened = os.fstat(descriptor)
        if _lstat_identity(opened) != expected_identity:
            raise RuntimePolicyValidationError(
                "OCI archive identity changed before parsing"
            )
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            with tarfile.open(fileobj=stream, mode="r:*") as archive:
                members = archive.getmembers()
                if not 4 <= len(members) <= 1024:
                    raise RuntimePolicyValidationError(
                        "OCI archive member count is out of bounds"
                    )
                for member in members:
                    name = member.name
                    pure = PurePosixPath(name)
                    if (
                        not name
                        or len(name) > 256
                        or pure.is_absolute()
                        or pure.as_posix() != name
                        or any(part in {"", ".", ".."} for part in pure.parts)
                    ):
                        raise RuntimePolicyValidationError(
                            "OCI archive member path is unsafe"
                        )
                    if member.issym() or member.islnk() or member.isdev():
                        raise RuntimePolicyValidationError(
                            "OCI archive contains a linked or device member"
                        )
                    if member.isdir():
                        directories.add(name.rstrip("/"))
                        continue
                    if not member.isfile() or name in files:
                        raise RuntimePolicyValidationError(
                            "OCI archive member type or identity drifted"
                        )
                    total_member_bytes += member.size
                    if total_member_bytes > MAX_OCI_ARCHIVE_BYTES:
                        raise RuntimePolicyValidationError(
                            "OCI archive expanded bytes are out of bounds"
                        )
                    extracted = archive.extractfile(member)
                    if extracted is None:
                        raise RuntimePolicyValidationError(
                            "OCI archive member cannot be read"
                        )
                    digest = hashlib.sha256()
                    captured = bytearray() if member.size <= 4 * 1024 * 1024 else None
                    read_bytes = 0
                    while True:
                        chunk = extracted.read(1024 * 1024)
                        if not chunk:
                            break
                        read_bytes += len(chunk)
                        digest.update(chunk)
                        if captured is not None:
                            captured.extend(chunk)
                    if read_bytes != member.size:
                        raise RuntimePolicyValidationError(
                            "OCI archive member size drifted"
                        )
                    files[name] = (
                        read_bytes,
                        digest.hexdigest(),
                        bytes(captured) if captured is not None else None,
                    )
        after_open = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    try:
        after_path = os.lstat(path)
    except OSError as exception:
        raise RuntimePolicyValidationError(
            "OCI archive vanished after parsing"
        ) from exception
    if (
        _lstat_identity(after_open) != expected_identity
        or _lstat_identity(after_path) != expected_identity
    ):
        raise RuntimePolicyValidationError("OCI archive changed while parsing")
    if directories - {"blobs", "blobs/sha256"}:
        raise RuntimePolicyValidationError(
            "OCI archive contains unexpected directories"
        )
    for name, (_, digest, _) in files.items():
        if name.startswith("blobs/sha256/"):
            expected_digest = name.removeprefix("blobs/sha256/")
            if SHA256.fullmatch(expected_digest) is None or digest != expected_digest:
                raise RuntimePolicyValidationError(
                    "OCI content-addressed blob digest drifted"
                )
        elif name not in {"index.json", "oci-layout"}:
            raise RuntimePolicyValidationError(
                "OCI archive contains an unexpected file"
            )

    def payload(name: str) -> bytes:
        item = files.get(name)
        if item is None or item[2] is None:
            raise RuntimePolicyValidationError(f"OCI metadata blob is missing: {name}")
        return item[2]

    layout = _parse_oci_json(payload("oci-layout"), context="OCI layout")
    if layout != {"imageLayoutVersion": "1.0.0"}:
        raise RuntimePolicyValidationError("OCI layout version drifted")
    index = _parse_oci_json(payload("index.json"), context="OCI index")
    if (
        set(index) != {"manifests", "mediaType", "schemaVersion"}
        or index["schemaVersion"] != 2
        or index["mediaType"] != "application/vnd.oci.image.index.v1+json"
    ):
        raise RuntimePolicyValidationError("OCI index fields drifted")
    manifests = index["manifests"]
    if not isinstance(manifests, list) or len(manifests) != 1:
        raise RuntimePolicyValidationError("OCI index must contain one image manifest")
    manifest_descriptor = _assert_exact_keys(
        manifests[0],
        {"annotations", "digest", "mediaType", "platform", "size"},
        context="OCI manifest descriptor",
    )
    if (
        manifest_descriptor["mediaType"] != "application/vnd.oci.image.manifest.v1+json"
        or manifest_descriptor["annotations"]
        != {"org.opencontainers.image.created": "1970-01-01T00:00:00Z"}
        or manifest_descriptor["platform"] != {"architecture": "amd64", "os": "linux"}
    ):
        raise RuntimePolicyValidationError("OCI image manifest media type drifted")
    manifest_digest = manifest_descriptor["digest"]
    if (
        not isinstance(manifest_digest, str)
        or re.fullmatch(r"sha256:[0-9a-f]{64}", manifest_digest) is None
    ):
        raise RuntimePolicyValidationError("OCI manifest digest is invalid")
    manifest_name = f"blobs/sha256/{manifest_digest.removeprefix('sha256:')}"
    manifest_bytes = payload(manifest_name)
    if manifest_descriptor["size"] != len(manifest_bytes):
        raise RuntimePolicyValidationError("OCI manifest descriptor size drifted")
    manifest = _parse_oci_json(manifest_bytes, context="OCI image manifest")
    if (
        set(manifest) != {"config", "layers", "mediaType", "schemaVersion"}
        or manifest["schemaVersion"] != 2
        or manifest["mediaType"] != "application/vnd.oci.image.manifest.v1+json"
    ):
        raise RuntimePolicyValidationError("OCI image manifest fields drifted")
    config_descriptor = _assert_exact_keys(
        manifest["config"],
        {"digest", "mediaType", "size"},
        context="OCI config descriptor",
    )
    if config_descriptor["mediaType"] != "application/vnd.oci.image.config.v1+json":
        raise RuntimePolicyValidationError("OCI config media type drifted")
    config_digest = config_descriptor["digest"]
    if (
        not isinstance(config_digest, str)
        or re.fullmatch(r"sha256:[0-9a-f]{64}", config_digest) is None
    ):
        raise RuntimePolicyValidationError("OCI config digest is invalid")
    config_name = f"blobs/sha256/{config_digest.removeprefix('sha256:')}"
    config_bytes = payload(config_name)
    if config_descriptor["size"] != len(config_bytes):
        raise RuntimePolicyValidationError("OCI config descriptor size drifted")
    layers = manifest["layers"]
    if not isinstance(layers, list) or not 1 <= len(layers) <= 128:
        raise RuntimePolicyValidationError("OCI image layer count is out of bounds")
    referenced_files = {"index.json", "oci-layout", manifest_name, config_name}
    layer_descriptors: list[tuple[str, str, str]] = []
    for layer in layers:
        if not isinstance(layer, dict):
            raise RuntimePolicyValidationError("OCI layer descriptor drifted")
        media_type = layer.get("mediaType")
        if media_type == "application/vnd.oci.image.layer.v1.tar+gzip":
            descriptor_keys = {"digest", "mediaType", "size"}
        elif media_type == "application/vnd.oci.image.layer.v1.tar":
            descriptor_keys = {"annotations", "digest", "mediaType", "size"}
        else:
            raise RuntimePolicyValidationError("OCI layer descriptor drifted")
        descriptor_value = _assert_exact_keys(
            layer, descriptor_keys, context="OCI layer descriptor"
        )
        digest_value = descriptor_value["digest"]
        if (
            not isinstance(digest_value, str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", digest_value) is None
        ):
            raise RuntimePolicyValidationError("OCI layer descriptor drifted")
        if media_type == "application/vnd.oci.image.layer.v1.tar" and descriptor_value[
            "annotations"
        ] != {"buildkit/rewritten-timestamp": "0"}:
            raise RuntimePolicyValidationError("OCI layer annotations drifted")
        layer_name = f"blobs/sha256/{digest_value.removeprefix('sha256:')}"
        item = files.get(layer_name)
        if item is None or descriptor_value["size"] != item[0]:
            raise RuntimePolicyValidationError("OCI layer content or size drifted")
        referenced_files.add(layer_name)
        layer_descriptors.append((media_type, digest_value, layer_name))
    if set(files) != referenced_files:
        raise RuntimePolicyValidationError("OCI archive contains unreferenced content")
    config_document = _parse_oci_json(config_bytes, context="OCI image config")
    if not {"architecture", "config", "os", "rootfs"}.issubset(config_document):
        raise RuntimePolicyValidationError(
            "OCI image config required fields are missing"
        )
    if set(config_document) - {
        "architecture",
        "config",
        "created",
        "history",
        "os",
        "rootfs",
    }:
        raise RuntimePolicyValidationError("OCI image config contains unknown fields")
    config = config_document["config"]
    if not isinstance(config, dict):
        raise RuntimePolicyValidationError("OCI process config is malformed")
    if set(config) - {
        "ArgsEscaped",
        "Cmd",
        "Entrypoint",
        "Env",
        "ExposedPorts",
        "Healthcheck",
        "Labels",
        "OnBuild",
        "Shell",
        "StopSignal",
        "User",
        "Volumes",
        "WorkingDir",
    }:
        raise RuntimePolicyValidationError("OCI process config contains unknown fields")
    if config.get("ArgsEscaped") is not True:
        raise RuntimePolicyValidationError("OCI process ArgsEscaped field drifted")
    environment = config.get("Env")
    labels = config.get("Labels") or {}

    def projected_mapping_keys(field: str) -> list[str]:
        mapping = config.get(field) or {}
        if not isinstance(mapping, dict) or any(
            not isinstance(key, str) or value not in ({}, None)
            for key, value in mapping.items()
        ):
            raise RuntimePolicyValidationError(f"OCI image {field} is malformed")
        return sorted(mapping)

    rootfs = config_document["rootfs"]
    if not isinstance(rootfs, dict) or set(rootfs) != {"diff_ids", "type"}:
        raise RuntimePolicyValidationError("OCI image rootfs config is malformed")
    diff_ids = rootfs["diff_ids"]
    if (
        rootfs["type"] != "layers"
        or not isinstance(diff_ids, list)
        or len(diff_ids) != len(layers)
        or any(
            not isinstance(item, str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", item) is None
            for item in diff_ids
        )
    ):
        raise RuntimePolicyValidationError("OCI image rootfs diff IDs drifted")
    compressed_names = {
        layer_name
        for media_type, _, layer_name in layer_descriptors
        if media_type == "application/vnd.oci.image.layer.v1.tar+gzip"
    }
    compressed_diff_ids = _gzip_oci_layer_diff_ids(
        path, expected_identity=expected_identity, layer_names=compressed_names
    )
    for diff_id, (media_type, descriptor_digest, layer_name) in zip(
        diff_ids, layer_descriptors, strict=True
    ):
        expected_diff_id = (
            compressed_diff_ids[layer_name]
            if media_type == "application/vnd.oci.image.layer.v1.tar+gzip"
            else descriptor_digest
        )
        if diff_id != expected_diff_id:
            raise RuntimePolicyValidationError(
                "OCI rootfs diff ID differs from the actual layer content"
            )
    return {
        "architecture": config_document["architecture"],
        "cmd": config.get("Cmd"),
        "config_digest": config_digest,
        "entrypoint": config.get("Entrypoint"),
        "environment": environment,
        "exposed_ports": projected_mapping_keys("ExposedPorts"),
        "healthcheck": config.get("Healthcheck"),
        "image_id": config_digest,
        "labels": labels,
        "onbuild": config.get("OnBuild") or [],
        "os": config_document["os"],
        "rootfs_layers": diff_ids,
        "shell": config.get("Shell") or [],
        "stop_signal": config.get("StopSignal"),
        "user": config.get("User"),
        "volumes": projected_mapping_keys("Volumes"),
        "workdir": config.get("WorkingDir"),
    }


def _assert_oci_archive_with_evidence(
    path_value: Any, *, expected_bytes: int, expected_sha256: str
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(path_value, (str, Path)):
        raise RuntimePolicyValidationError("OCI archive path must be explicit")
    path_text = str(path_value)
    if (
        not path_text
        or len(path_text) > MAX_SOURCE_IDENTITY_BYTES
        or path_text.startswith(("\\\\", "//", "\\\\?\\", "\\\\.\\"))
        or any(part in {".", ".."} for part in re.split(r"[\\/]", path_text) if part)
    ):
        raise RuntimePolicyValidationError("OCI archive path is unsafe")
    windows_path = PureWindowsPath(path_text)
    posix_path = PurePosixPath(path_text)
    if not windows_path.is_absolute() and not posix_path.is_absolute():
        raise RuntimePolicyValidationError("OCI archive path must be absolute")
    size, sha256, _, identity = _hash_regular_file_no_follow(
        Path(path_text), max_bytes=MAX_OCI_ARCHIVE_BYTES
    )
    if size != expected_bytes or sha256 != expected_sha256:
        raise RuntimePolicyValidationError(
            "downloaded OCI archive bytes or digest drifted"
        )
    projection = _read_oci_archive_projection(
        Path(path_text), expected_identity=identity
    )
    return projection, {
        "archive_bytes": size,
        "archive_path": os.path.normcase(os.path.abspath(path_text)),
        "archive_physical_identity": identity,
        "archive_sha256": sha256,
    }


def _assert_oci_archive(
    path_value: Any, *, expected_bytes: int, expected_sha256: str
) -> dict[str, Any]:
    projection, _ = _assert_oci_archive_with_evidence(
        path_value,
        expected_bytes=expected_bytes,
        expected_sha256=expected_sha256,
    )
    return projection


def _assert_docker_archive_with_evidence(
    path_value: Any, *, expected_bytes: int, expected_sha256: str
) -> dict[str, Any]:
    if not isinstance(path_value, (str, Path)):
        raise RuntimePolicyValidationError("Docker archive path must be explicit")
    path_text = str(path_value)
    if (
        not path_text
        or len(path_text) > MAX_SOURCE_IDENTITY_BYTES
        or path_text.startswith(("\\\\", "//", "\\\\?\\", "\\\\.\\"))
        or any(part in {".", ".."} for part in re.split(r"[\\/]", path_text) if part)
    ):
        raise RuntimePolicyValidationError("Docker archive path is unsafe")
    windows_path = PureWindowsPath(path_text)
    posix_path = PurePosixPath(path_text)
    if not windows_path.is_absolute() and not posix_path.is_absolute():
        raise RuntimePolicyValidationError("Docker archive path must be absolute")
    size, sha256, _, identity = _hash_regular_file_no_follow(
        Path(path_text), max_bytes=MAX_DOCKER_ARCHIVE_BYTES
    )
    if size != expected_bytes or sha256 != expected_sha256:
        raise RuntimePolicyValidationError(
            "downloaded Docker archive bytes or digest drifted"
        )
    return {
        "archive_bytes": size,
        "archive_path": os.path.normcase(os.path.abspath(path_text)),
        "archive_physical_identity": identity,
        "archive_sha256": sha256,
    }


def validate_build_observation_receipt(
    receipt: Mapping[str, Any],
    expected_binding: Mapping[str, Any],
    *,
    runtime_build_receipt: Mapping[str, Any],
    runtime_build_binding: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    policy: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    observation = _assert_exact_keys(
        receipt,
        _BUILD_OBSERVATION_RECEIPT_KEYS,
        context="independent build observation receipt",
    )
    if observation["schema_version"] != BUILD_OBSERVATION_RECEIPT_SCHEMA_VERSION:
        raise RuntimePolicyValidationError("build observation schema drifted")
    if observation["receipt_kind"] != BUILD_OBSERVATION_RECEIPT_KIND:
        raise RuntimePolicyValidationError("build observation kind drifted")
    _, observer_identity_sha = validate_github_job_identity(
        observation["observer_job_identity"],
        allowed_job_names=(OBSERVER_JOB_NAME,),
        expected_run_binding=expected_run_binding,
    )
    if observation["observer_job_identity_sha256"] != observer_identity_sha:
        raise RuntimePolicyValidationError("observer GitHub job identity hash drifted")
    if observer_identity_sha == runtime_build_receipt["builder_job_identity_sha256"]:
        raise RuntimePolicyValidationError(
            "builder and independent observer jobs must differ"
        )
    observer_nonce = _assert_nonce(
        observation["observer_nonce"], context="observer nonce"
    )
    if observer_nonce in {
        runtime_build_receipt["build_nonce"],
        runtime_build_receipt["verified_nonce"],
    }:
        raise RuntimePolicyValidationError("builder and observer nonces must differ")
    if observation["source_build_nonce"] != runtime_build_receipt["build_nonce"]:
        raise RuntimePolicyValidationError("observation source build nonce drifted")
    _assert_nonce(observation["source_build_nonce"], context="source build nonce")
    if (
        observation["source_build_receipt_sha256"]
        != runtime_build_receipt["receipt_sha256"]
    ):
        raise RuntimePolicyValidationError(
            "observation belongs to a different build receipt"
        )

    base_projection, base_projection_sha = _validate_base_image_inspect_projection(
        observation["base_image_inspect_projection"]
    )
    producer_projection, producer_projection_sha = _validate_image_inspect_projection(
        observation["producer_image_inspect_projection"]
    )
    observer_projection, observer_projection_sha = _validate_image_inspect_projection(
        observation["observer_image_inspect_projection"]
    )
    if (
        observation["base_image_inspect_projection_sha256"] != base_projection_sha
        or observation["producer_image_inspect_projection_sha256"]
        != producer_projection_sha
        or observation["observer_image_inspect_projection_sha256"]
        != observer_projection_sha
    ):
        raise RuntimePolicyValidationError("raw image projection hash drifted")
    if producer_projection != observer_projection:
        raise RuntimePolicyValidationError(
            "independent runtime build projection differs"
        )
    expected_environment = _environment_mapping(base_projection["environment"])
    expected_environment.update(FIXED_ENVIRONMENT)
    if _environment_mapping(producer_projection["environment"]) != expected_environment:
        raise RuntimePolicyValidationError(
            "runtime environment is not the exact pinned base plus Dockerfile overrides"
        )
    expected_labels = copy.deepcopy(base_projection["labels"])
    expected_labels.update(REQUIRED_IMAGE_LABELS)
    if producer_projection["labels"] != expected_labels:
        raise RuntimePolicyValidationError(
            "runtime labels are not the exact pinned base plus Dockerfile overrides"
        )
    base_layers = base_projection["rootfs_layers"]
    final_layers = producer_projection["rootfs_layers"]
    if (
        len(final_layers) <= len(base_layers)
        or final_layers[: len(base_layers)] != base_layers
    ):
        raise RuntimePolicyValidationError(
            "runtime rootfs is not derived from the pinned base"
        )

    observer_parameters = _assert_exact_keys(
        observation["observer_build_parameters"],
        set(BUILD_PARAMETERS),
        context="observer deterministic build parameters",
    )
    if observer_parameters != BUILD_PARAMETERS or observation[
        "observer_build_parameters_sha256"
    ] != canonical_sha256(observer_parameters):
        raise RuntimePolicyValidationError("observer build parameters drifted")
    wheel_manifest, wheel_manifest_sha = _validate_wheelhouse_manifest(
        observation["wheelhouse_manifest"]
    )
    if observation["wheelhouse_manifest_sha256"] != wheel_manifest_sha:
        raise RuntimePolicyValidationError("observed wheelhouse manifest hash drifted")
    if (
        wheel_manifest != runtime_build_receipt["wheelhouse_manifest"]
        or wheel_manifest_sha != runtime_build_receipt["wheelhouse_manifest_sha256"]
    ):
        raise RuntimePolicyValidationError("observer used a different wheelhouse")

    provenance = _assert_exact_keys(
        observation["build_provenance"],
        _BUILD_PROVENANCE_KEYS,
        context="raw build provenance",
    )
    if observation["build_provenance_sha256"] != canonical_sha256(provenance):
        raise RuntimePolicyValidationError("raw build provenance hash drifted")
    expected_provenance = {
        key: copy.deepcopy(runtime_build_binding[key]) for key in _BUILD_PROVENANCE_KEYS
    }
    if provenance != expected_provenance:
        raise RuntimePolicyValidationError(
            "independent build provenance differs from build"
        )
    if (
        producer_projection != runtime_build_receipt["image_inspect_projection"]
        or producer_projection_sha
        != runtime_build_receipt["image_inspect_projection_sha256"]
        or observation["producer_docker_archive_sha256"]
        != runtime_build_receipt["docker_archive_sha256"]
        or observation["producer_docker_archive_bytes"]
        != runtime_build_receipt["docker_archive_bytes"]
        or observation["producer_oci_archive_sha256"]
        != runtime_build_receipt["oci_archive_sha256"]
        or observation["producer_oci_archive_bytes"]
        != runtime_build_receipt["oci_archive_bytes"]
    ):
        raise RuntimePolicyValidationError(
            "observer inspected a different producer runtime"
        )
    for key in (
        "base_image_inspect_projection_sha256",
        "build_provenance_sha256",
        "observer_build_parameters_sha256",
        "observer_docker_archive_sha256",
        "observer_image_inspect_projection_sha256",
        "observer_oci_archive_sha256",
        "producer_image_inspect_projection_sha256",
        "producer_docker_archive_sha256",
        "producer_oci_archive_sha256",
        "source_build_receipt_sha256",
        "wheelhouse_manifest_sha256",
    ):
        _assert_sha256(observation[key], context=key)
    for key in ("observer_oci_archive_bytes", "producer_oci_archive_bytes"):
        byte_count = observation[key]
        if type(byte_count) is not int or not 1 <= byte_count <= MAX_OCI_ARCHIVE_BYTES:
            raise RuntimePolicyValidationError(
                "observed OCI archive byte count is invalid"
            )
    for key in ("observer_docker_archive_bytes", "producer_docker_archive_bytes"):
        byte_count = observation[key]
        if (
            type(byte_count) is not int
            or not 1 <= byte_count <= MAX_DOCKER_ARCHIVE_BYTES
        ):
            raise RuntimePolicyValidationError(
                "observed Docker archive byte count is invalid"
            )
    receipt_sha = _assert_sha256(
        observation["receipt_sha256"], context="build observation receipt"
    )
    if receipt_sha != canonical_receipt_sha256(observation):
        raise RuntimePolicyValidationError(
            "build observation canonical receipt hash drifted"
        )
    binding = {
        key: copy.deepcopy(observation[key]) for key in _BUILD_OBSERVATION_BINDING_KEYS
    }
    expected_observation = _assert_exact_keys(
        expected_binding,
        _BUILD_OBSERVATION_BINDING_KEYS,
        context="independently derived build observation binding",
    )
    if binding != expected_observation:
        raise RuntimePolicyValidationError(
            "build observation differs from independently hashed job artifacts"
        )
    validated_policy = validate_runtime_policy(policy)
    if provenance["runtime_policy_sha256"] != canonical_sha256(validated_policy):
        raise RuntimePolicyValidationError("observed build policy hash drifted")
    return observation, binding


def validate_runtime_build_receipt(
    receipt: Mapping[str, Any],
    build_observation_receipt: Mapping[str, Any],
    expected_observation_binding: Mapping[str, Any],
    *,
    expected_run_binding: Mapping[str, Any],
    expected_builder_job_identity: Mapping[str, Any],
    producer_oci_archive_path: str | Path,
    producer_docker_archive_path: str | Path,
    policy: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    build, build_binding = _validate_runtime_build_receipt(
        receipt,
        policy,
        validated_command_contract,
        expected_run_binding,
        expected_builder_job_identity,
    )
    observation, _ = validate_build_observation_receipt(
        build_observation_receipt,
        expected_observation_binding,
        runtime_build_receipt=build,
        runtime_build_binding=build_binding,
        expected_run_binding=expected_run_binding,
        policy=policy,
    )
    producer_projection = _assert_oci_archive(
        producer_oci_archive_path,
        expected_bytes=observation["producer_oci_archive_bytes"],
        expected_sha256=observation["producer_oci_archive_sha256"],
    )
    producer_projection, _ = _validate_image_inspect_projection(producer_projection)
    if producer_projection != observation["producer_image_inspect_projection"]:
        raise RuntimePolicyValidationError(
            "producer OCI bytes differ from the independently observed projection"
        )
    _assert_docker_archive_with_evidence(
        producer_docker_archive_path,
        expected_bytes=observation["producer_docker_archive_bytes"],
        expected_sha256=observation["producer_docker_archive_sha256"],
    )
    return build, build_binding, observation


_SHARED_RUNTIME_VALIDATION_SEAL = object()


@dataclass(frozen=True)
class _ValidatedSharedRuntimeReceipts:
    seal: object
    policy_sha256: str
    command_contract_sha256: str
    expected_builder_job_identity_sha256: str
    expected_run_binding_sha256: str
    build_json: bytes
    build_binding_json: bytes
    observation_json: bytes
    observer_docker_evidence_json: bytes
    observer_oci_evidence_json: bytes
    producer_docker_evidence_json: bytes
    producer_oci_evidence_json: bytes
    wheelhouse_manifest_json: bytes


def verify_shared_runtime_receipts(
    runtime_build_receipt: Mapping[str, Any],
    build_observation_receipt: Mapping[str, Any],
    expected_observation_binding: Mapping[str, Any],
    *,
    expected_run_binding: Mapping[str, Any],
    expected_builder_job_identity: Mapping[str, Any],
    producer_oci_archive_path: str | Path,
    observer_oci_archive_path: str | Path,
    producer_docker_archive_path: str | Path,
    observer_docker_archive_path: str | Path,
    wheelhouse_root: str | os.PathLike[str],
    policy: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
) -> _ValidatedSharedRuntimeReceipts:
    """Authenticate the shared runtime build/observation bundle exactly once."""

    validated_policy = validate_runtime_policy(policy)
    validated_contract = command_contract.validate_command_contract(
        validated_command_contract
    )
    expected_run, expected_run_sha = validate_expected_run_binding(expected_run_binding)
    _, expected_builder_sha = validate_github_job_identity(
        expected_builder_job_identity,
        allowed_job_names=(BUILD_JOB_NAME,),
        expected_run_binding=expected_run,
    )
    build, build_binding, observation = validate_runtime_build_receipt(
        runtime_build_receipt,
        build_observation_receipt,
        expected_observation_binding,
        expected_run_binding=expected_run,
        expected_builder_job_identity=expected_builder_job_identity,
        producer_oci_archive_path=producer_oci_archive_path,
        producer_docker_archive_path=producer_docker_archive_path,
        policy=validated_policy,
        validated_command_contract=validated_contract,
    )
    producer_projection, producer_evidence = _assert_oci_archive_with_evidence(
        producer_oci_archive_path,
        expected_bytes=observation["producer_oci_archive_bytes"],
        expected_sha256=observation["producer_oci_archive_sha256"],
    )
    observer_projection, observer_evidence = _assert_oci_archive_with_evidence(
        observer_oci_archive_path,
        expected_bytes=observation["observer_oci_archive_bytes"],
        expected_sha256=observation["observer_oci_archive_sha256"],
    )
    producer_docker_evidence = _assert_docker_archive_with_evidence(
        producer_docker_archive_path,
        expected_bytes=observation["producer_docker_archive_bytes"],
        expected_sha256=observation["producer_docker_archive_sha256"],
    )
    observer_docker_evidence = _assert_docker_archive_with_evidence(
        observer_docker_archive_path,
        expected_bytes=observation["observer_docker_archive_bytes"],
        expected_sha256=observation["observer_docker_archive_sha256"],
    )
    producer_projection, _ = _validate_image_inspect_projection(producer_projection)
    observer_projection, _ = _validate_image_inspect_projection(observer_projection)
    if (
        producer_projection != observation["producer_image_inspect_projection"]
        or observer_projection != observation["observer_image_inspect_projection"]
        or producer_projection != observer_projection
    ):
        raise RuntimePolicyValidationError(
            "producer and observer OCI bytes do not prove the same runtime"
        )
    _require_distinct_archive_evidence(
        [
            producer_evidence,
            observer_evidence,
            producer_docker_evidence,
            observer_docker_evidence,
        ],
        context="shared producer/observer OCI and Docker topology",
    )
    wheelhouse_manifest, wheelhouse_manifest_sha = validate_wheelhouse_directory(
        wheelhouse_root, observation["wheelhouse_manifest"]
    )
    if wheelhouse_manifest_sha != observation["wheelhouse_manifest_sha256"]:
        raise RuntimePolicyValidationError("validated wheelhouse manifest hash drifted")
    return _ValidatedSharedRuntimeReceipts(
        seal=_SHARED_RUNTIME_VALIDATION_SEAL,
        policy_sha256=canonical_sha256(validated_policy),
        command_contract_sha256=command_contract.canonical_sha256(validated_contract),
        expected_builder_job_identity_sha256=expected_builder_sha,
        expected_run_binding_sha256=expected_run_sha,
        build_json=canonical_json_bytes(build),
        build_binding_json=canonical_json_bytes(build_binding),
        observation_json=canonical_json_bytes(observation),
        observer_docker_evidence_json=canonical_json_bytes(observer_docker_evidence),
        observer_oci_evidence_json=canonical_json_bytes(observer_evidence),
        producer_docker_evidence_json=canonical_json_bytes(producer_docker_evidence),
        producer_oci_evidence_json=canonical_json_bytes(producer_evidence),
        wheelhouse_manifest_json=canonical_json_bytes(wheelhouse_manifest),
    )


def _consume_shared_runtime_receipts(
    bundle: object,
    *,
    expected_run_binding: Mapping[str, Any],
    policy: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    if (
        type(bundle) is not _ValidatedSharedRuntimeReceipts
        or bundle.seal is not _SHARED_RUNTIME_VALIDATION_SEAL
    ):
        raise RuntimePolicyValidationError(
            "shared runtime receipts were not produced by the trusted verifier"
        )
    validated_policy = validate_runtime_policy(policy)
    validated_contract = command_contract.validate_command_contract(
        validated_command_contract
    )
    expected_run, expected_run_sha = validate_expected_run_binding(expected_run_binding)
    if bundle.policy_sha256 != canonical_sha256(validated_policy):
        raise RuntimePolicyValidationError("shared runtime policy binding drifted")
    if bundle.command_contract_sha256 != command_contract.canonical_sha256(
        validated_contract
    ):
        raise RuntimePolicyValidationError(
            "shared runtime command contract binding drifted"
        )
    if bundle.expected_run_binding_sha256 != expected_run_sha:
        raise RuntimePolicyValidationError("shared runtime trusted run binding drifted")
    build = _parse_strict_json_bytes(
        bundle.build_json,
        max_bytes=MAX_RECEIPT_BYTES,
        context="validated shared runtime build receipt",
    )
    build_binding = _parse_strict_json_bytes(
        bundle.build_binding_json,
        max_bytes=MAX_RECEIPT_BYTES,
        context="validated shared runtime build binding",
    )
    observation = _parse_strict_json_bytes(
        bundle.observation_json,
        max_bytes=MAX_RECEIPT_BYTES,
        context="validated shared runtime observation receipt",
    )
    if build["receipt_sha256"] != canonical_receipt_sha256(build):
        raise RuntimePolicyValidationError("shared runtime build receipt seal drifted")
    if observation["receipt_sha256"] != canonical_receipt_sha256(observation):
        raise RuntimePolicyValidationError(
            "shared runtime observation receipt seal drifted"
        )
    if build_binding != {key: copy.deepcopy(build[key]) for key in _BUILD_BINDING_KEYS}:
        raise RuntimePolicyValidationError("shared runtime build binding drifted")
    if observation["source_build_receipt_sha256"] != build["receipt_sha256"]:
        raise RuntimePolicyValidationError(
            "shared runtime observation/build link drifted"
        )
    _, builder_sha = validate_github_job_identity(
        build["builder_job_identity"],
        allowed_job_names=(BUILD_JOB_NAME,),
        expected_run_binding=expected_run,
    )
    if builder_sha != bundle.expected_builder_job_identity_sha256:
        raise RuntimePolicyValidationError("shared runtime builder authority drifted")
    return build, build_binding, observation


_ARTIFACT_TRANSPORT_RECEIPT_KEYS = {
    "artifact_name",
    "artifact_payload_kind",
    "artifact_payload_sha256",
    "build_observation_receipt_sha256",
    "command_id",
    "dispatch_sha256",
    "materialization_receipt_sha256",
    "manifest_sha256",
    "oci_archive_sha256",
    "producer_job_identity",
    "producer_job_identity_sha256",
    "receipt_kind",
    "receipt_sha256",
    "runtime_build_receipt_sha256",
    "schema_version",
    "transport_nonce",
}
_ARTIFACT_TRANSPORT_BINDING_KEYS = _ARTIFACT_TRANSPORT_RECEIPT_KEYS - {
    "receipt_kind",
    "receipt_sha256",
    "schema_version",
    "transport_nonce",
}


def validate_artifact_transport_receipt(
    receipt: Mapping[str, Any],
    expected_binding: Mapping[str, Any],
    *,
    expected_run_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    candidate = _assert_exact_keys(
        receipt,
        _ARTIFACT_TRANSPORT_RECEIPT_KEYS,
        context="artifact transport receipt",
    )
    if candidate["schema_version"] != ARTIFACT_TRANSPORT_RECEIPT_SCHEMA_VERSION:
        raise RuntimePolicyValidationError("artifact transport receipt schema drifted")
    if candidate["receipt_kind"] != ARTIFACT_TRANSPORT_RECEIPT_KIND:
        raise RuntimePolicyValidationError("artifact transport receipt kind drifted")
    artifact_name = candidate["artifact_name"]
    if (
        not isinstance(artifact_name, str)
        or ARTIFACT_NAME.fullmatch(artifact_name) is None
    ):
        raise RuntimePolicyValidationError(
            "artifact transport name is not a strict token"
        )
    if candidate["artifact_payload_kind"] != ARTIFACT_PAYLOAD_KIND:
        raise RuntimePolicyValidationError("artifact payload hash definition drifted")
    if candidate["command_id"] not in SUPPORTED_COMMAND_IDS:
        raise RuntimePolicyValidationError("artifact transport command is not static")
    for key in (
        "artifact_payload_sha256",
        "build_observation_receipt_sha256",
        "dispatch_sha256",
        "materialization_receipt_sha256",
        "manifest_sha256",
        "oci_archive_sha256",
        "producer_job_identity_sha256",
        "runtime_build_receipt_sha256",
    ):
        _assert_sha256(candidate[key], context=key)
    expected_job_name = STATIC_COMMAND_JOB_NAMES[candidate["command_id"]]
    producer_identity, producer_identity_sha = validate_github_job_identity(
        candidate["producer_job_identity"],
        allowed_job_names=(expected_job_name,),
        expected_run_binding=expected_run_binding,
    )
    if candidate["producer_job_identity_sha256"] != producer_identity_sha:
        raise RuntimePolicyValidationError(
            "static producer GitHub job identity hash drifted"
        )
    expected_artifact_name = (
        f"{STATIC_COMMAND_ARTIFACT_PREFIXES[candidate['command_id']]}-"
        f"{producer_identity['run_id']}-{producer_identity['run_attempt']}"
    )
    if artifact_name != expected_artifact_name:
        raise RuntimePolicyValidationError(
            "artifact transport name differs from its exact GitHub producer identity"
        )
    _assert_nonce(candidate["transport_nonce"], context="artifact transport nonce")
    receipt_sha256 = _assert_sha256(
        candidate["receipt_sha256"], context="artifact transport receipt"
    )
    if receipt_sha256 != canonical_receipt_sha256(candidate):
        raise RuntimePolicyValidationError("artifact transport canonical hash drifted")
    binding = {
        key: copy.deepcopy(candidate[key]) for key in _ARTIFACT_TRANSPORT_BINDING_KEYS
    }
    expected = _assert_exact_keys(
        expected_binding,
        _ARTIFACT_TRANSPORT_BINDING_KEYS,
        context="expected artifact transport binding",
    )
    if binding != expected:
        raise RuntimePolicyValidationError(
            "artifact transport differs from independently derived GitHub job binding"
        )
    return candidate, binding


def _expected_dispatch(
    command: Mapping[str, Any],
    policy: Mapping[str, Any],
    materialization_receipt: Mapping[str, Any],
    candidate_binding: Mapping[str, Any],
    build_receipt: Mapping[str, Any],
    build_binding: Mapping[str, Any],
    build_observation_receipt: Mapping[str, Any],
    archive_evidence: Mapping[str, Any],
) -> dict[str, Any]:
    image_id = build_receipt["image_id"]
    candidate_source = {
        **copy.deepcopy(policy["runtime"]["candidate_source"]),
        "archive_bytes": archive_evidence["archive_bytes"],
        "archive_entry_count": archive_evidence["archive_entry_count"],
        "archive_sha256": archive_evidence["archive_sha256"],
        "materialization_receipt_sha256": materialization_receipt["receipt_sha256"],
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
        image_id,
        *TRUSTED_SLEEPER_ARGV,
    ]
    candidate_copy_argv = [
        "docker",
        "exec",
        "--interactive",
        "--user=0:0",
        CONTAINER_ID_TOKEN,
        "/usr/local/bin/python",
        "-c",
        TRUSTED_CANDIDATE_EXTRACTOR_SCRIPT,
        str(archive_evidence["archive_bytes"]),
        archive_evidence["archive_sha256"],
        str(archive_evidence["archive_entry_count"]),
    ]
    start_argv = ["docker", "start", CONTAINER_ID_TOKEN]
    exec_argv = [
        "docker",
        "exec",
        "--workdir=/workspace",
        CONTAINER_ID_TOKEN,
        *command["argv"],
    ]
    return {
        "accepted_a8": candidate_binding["accepted_entry_sha"],
        "backend_kind": STATIC_BACKEND_KIND,
        "build_observation_receipt_sha256": build_observation_receipt["receipt_sha256"],
        "build_identity_sha256": canonical_sha256(build_binding),
        "candidate_binding_sha256": canonical_sha256(candidate_binding),
        "candidate_copy_argv": candidate_copy_argv,
        "candidate_source": candidate_source,
        "candidate_sha": candidate_binding["candidate_sha"],
        "candidate_tree_sha": candidate_binding["candidate_tree_sha"],
        "closure_kind": candidate_binding["closure_kind"],
        "command_id": command["id"],
        "cwd": command["cwd"],
        "container_id_source": "STDOUT_OF_CREATE_ARGV",
        "container_id_token": CONTAINER_ID_TOKEN,
        "create_argv": create_argv,
        "exec_argv": exec_argv,
        "fixed_env": fixed_environment,
        "image_id": image_id,
        "inner_argv": copy.deepcopy(command["argv"]),
        "materialization_receipt_sha256": materialization_receipt["receipt_sha256"],
        "manifest_file_count": candidate_binding["manifest_file_count"],
        "manifest_sha256": candidate_binding["manifest_sha256"],
        "manifest_total_bytes": candidate_binding["manifest_total_bytes"],
        "network": policy["runtime"]["network"],
        "oci_archive_sha256": build_binding["oci_archive_sha256"],
        "report": copy.deepcopy(command["report"]),
        "resources": copy.deepcopy(policy["runtime"]["resources"]),
        "runtime_build_receipt_sha256": build_receipt["receipt_sha256"],
        "scope_inventory_sha256": candidate_binding["derived_inventory_sha256"],
        "start_argv": start_argv,
        "timeout_seconds": command["timeout_seconds"],
        "tmpfs": copy.deepcopy(policy["runtime"]["resources"]["tmpfs"]),
        "user": policy["runtime"]["user"],
    }


def assert_static_dispatch_authorized(
    contract_command: Mapping[str, Any],
    dispatch: Mapping[str, Any],
    policy: Mapping[str, Any],
    *,
    materialization_receipt: Mapping[str, Any],
    materialization_manifest: Any,
    expected_candidate_binding: Mapping[str, Any],
    expected_scope_inventory: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    candidate_archive_path: str | os.PathLike[str],
    validated_command_contract: Mapping[str, Any],
    validated_shared_runtime: object,
) -> tuple[str, ValidatedCandidateArchive]:
    validated_policy = validate_runtime_policy(policy)
    validated_command = assert_command_authorized(
        contract_command,
        validated_policy,
        validated_command_contract=validated_command_contract,
    )
    materialization, candidate_binding, archive_handle = (
        assert_materialization_authorized_live(
            materialization_receipt,
            materialization_manifest,
            expected_candidate_binding,
            expected_scope_inventory,
            expected_run_binding,
            candidate_archive_path,
        )
    )
    build, build_binding, build_observation = _consume_shared_runtime_receipts(
        validated_shared_runtime,
        expected_run_binding=expected_run_binding,
        policy=validated_policy,
        validated_command_contract=validated_command_contract,
    )
    if materialization["command_id"] != validated_command["id"]:
        archive_handle.close()
        raise RuntimePolicyValidationError(
            "materialization receipt belongs to a different static command"
        )
    candidate_build_projection = {
        "code_sha": materialization["candidate_sha"],
        "code_tree_sha": materialization["candidate_tree_sha"],
    }
    if any(build[key] != value for key, value in candidate_build_projection.items()):
        archive_handle.close()
        raise RuntimePolicyValidationError(
            "runtime build receipt belongs to a different materialized candidate"
        )
    if not isinstance(dispatch, Mapping):
        archive_handle.close()
        raise RuntimePolicyValidationError("static dispatch must be a mapping")
    candidate = copy.deepcopy(dict(dispatch))
    expected = _expected_dispatch(
        validated_command,
        validated_policy,
        materialization_receipt,
        candidate_binding,
        build,
        build_binding,
        build_observation,
        archive_handle.evidence(),
    )
    if candidate != expected:
        archive_handle.close()
        raise RuntimePolicyValidationError(
            "effective Docker dispatch differs from immutable receipts and trusted bindings"
        )
    return canonical_sha256(expected), archive_handle


def verify_static_dispatch_receipts(
    contract_command: Mapping[str, Any],
    dispatch: Mapping[str, Any],
    policy: Mapping[str, Any],
    *,
    materialization_receipt: Mapping[str, Any],
    materialization_manifest: Any,
    expected_candidate_binding: Mapping[str, Any],
    expected_scope_inventory: Mapping[str, Any],
    expected_run_binding: Mapping[str, Any],
    candidate_archive_path: str | os.PathLike[str],
    validated_command_contract: Mapping[str, Any],
    validated_shared_runtime: object,
    artifact_transport_receipt: Mapping[str, Any],
    expected_transport_binding: Mapping[str, Any],
) -> tuple[str, dict[str, Any]]:
    validated_policy = validate_runtime_policy(policy)
    validated_command = assert_command_authorized(
        contract_command,
        validated_policy,
        validated_command_contract=validated_command_contract,
    )
    materialization, candidate_binding, archive_evidence = (
        verify_materialization_receipt_offline(
            materialization_receipt,
            materialization_manifest,
            expected_candidate_binding,
            expected_scope_inventory,
            expected_run_binding,
            candidate_archive_path,
        )
    )
    if materialization["command_id"] != validated_command["id"]:
        raise RuntimePolicyValidationError(
            "offline materialization belongs to a different static command"
        )
    build, build_binding, build_observation = _consume_shared_runtime_receipts(
        validated_shared_runtime,
        expected_run_binding=expected_run_binding,
        policy=validated_policy,
        validated_command_contract=validated_command_contract,
    )
    candidate_build_projection = {
        "code_sha": materialization["candidate_sha"],
        "code_tree_sha": materialization["candidate_tree_sha"],
    }
    if any(build[key] != value for key, value in candidate_build_projection.items()):
        raise RuntimePolicyValidationError(
            "offline build receipt belongs to a different materialized candidate"
        )
    if not isinstance(dispatch, Mapping):
        raise RuntimePolicyValidationError("offline static dispatch must be a mapping")
    candidate = copy.deepcopy(dict(dispatch))
    expected_dispatch = _expected_dispatch(
        validated_command,
        validated_policy,
        materialization_receipt,
        candidate_binding,
        build,
        build_binding,
        build_observation,
        archive_evidence,
    )
    if candidate != expected_dispatch:
        raise RuntimePolicyValidationError(
            "offline dispatch differs from immutable receipts and trusted bindings"
        )
    dispatch_sha256 = canonical_sha256(expected_dispatch)
    transport, _ = validate_artifact_transport_receipt(
        artifact_transport_receipt,
        expected_transport_binding,
        expected_run_binding=expected_run_binding,
    )
    expected_transport_projection = {
        "command_id": validated_command["id"],
        "dispatch_sha256": dispatch_sha256,
        "materialization_receipt_sha256": materialization["receipt_sha256"],
        "build_observation_receipt_sha256": build_observation["receipt_sha256"],
        "manifest_sha256": materialization["manifest_sha256"],
        "oci_archive_sha256": build["oci_archive_sha256"],
        "runtime_build_receipt_sha256": build["receipt_sha256"],
    }
    if any(
        transport[key] != value for key, value in expected_transport_projection.items()
    ):
        raise RuntimePolicyValidationError(
            "artifact transport belongs to a different command or receipt bundle"
        )
    if (
        transport["producer_job_identity"] != materialization["producer_job_identity"]
        or transport["producer_job_identity_sha256"]
        != materialization["producer_job_identity_sha256"]
    ):
        raise RuntimePolicyValidationError(
            "transport and materialization require one exact executor job identity"
        )
    return dispatch_sha256, archive_evidence


_OCI_ARCHIVE_EVIDENCE_KEYS = {
    "archive_bytes",
    "archive_path",
    "archive_physical_identity",
    "archive_sha256",
}


def _validated_archive_evidence(
    raw: bytes, *, expected_bytes: int, expected_sha256: str, context: str
) -> dict[str, Any]:
    evidence = _assert_exact_keys(
        _parse_strict_json_bytes(raw, max_bytes=MAX_RECEIPT_BYTES, context=context),
        _OCI_ARCHIVE_EVIDENCE_KEYS,
        context=context,
    )
    if (
        evidence["archive_bytes"] != expected_bytes
        or evidence["archive_sha256"] != expected_sha256
    ):
        raise RuntimePolicyValidationError(f"{context} receipt summary drifted")
    path = evidence["archive_path"]
    identity = evidence["archive_physical_identity"]
    if (
        not isinstance(path, str)
        or not Path(path).is_absolute()
        or not isinstance(identity, list)
        or len(identity) != 7
        or any(type(item) is not int or item < 0 for item in identity)
        or identity[3] != 1
    ):
        raise RuntimePolicyValidationError(f"{context} physical identity is malformed")
    _assert_sha256(evidence["archive_sha256"], context=context)
    return evidence


def _shared_runtime_projection(
    bundle: object,
    *,
    expected_run_binding: Mapping[str, Any],
    policy: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
) -> dict[str, Any]:
    build, _, observation = _consume_shared_runtime_receipts(
        bundle,
        expected_run_binding=expected_run_binding,
        policy=policy,
        validated_command_contract=validated_command_contract,
    )
    assert isinstance(bundle, _ValidatedSharedRuntimeReceipts)
    producer_evidence = _validated_archive_evidence(
        bundle.producer_oci_evidence_json,
        expected_bytes=observation["producer_oci_archive_bytes"],
        expected_sha256=observation["producer_oci_archive_sha256"],
        context="validated producer OCI archive evidence",
    )
    observer_evidence = _validated_archive_evidence(
        bundle.observer_oci_evidence_json,
        expected_bytes=observation["observer_oci_archive_bytes"],
        expected_sha256=observation["observer_oci_archive_sha256"],
        context="validated observer OCI archive evidence",
    )
    producer_docker_evidence = _validated_archive_evidence(
        bundle.producer_docker_evidence_json,
        expected_bytes=observation["producer_docker_archive_bytes"],
        expected_sha256=observation["producer_docker_archive_sha256"],
        context="validated producer Docker archive evidence",
    )
    observer_docker_evidence = _validated_archive_evidence(
        bundle.observer_docker_evidence_json,
        expected_bytes=observation["observer_docker_archive_bytes"],
        expected_sha256=observation["observer_docker_archive_sha256"],
        context="validated observer Docker archive evidence",
    )
    if bundle.wheelhouse_manifest_json != canonical_json_bytes(
        observation["wheelhouse_manifest"]
    ):
        raise RuntimePolicyValidationError("validated wheelhouse evidence drifted")
    _require_distinct_archive_evidence(
        [
            producer_evidence,
            observer_evidence,
            producer_docker_evidence,
            observer_docker_evidence,
        ],
        context="shared producer/observer OCI and Docker topology",
    )
    producer_evidence = {
        **producer_evidence,
        "artifact_source_job_identity_sha256": build["builder_job_identity_sha256"],
    }
    observer_evidence = {
        **observer_evidence,
        "artifact_source_job_identity_sha256": observation[
            "observer_job_identity_sha256"
        ],
    }
    producer_docker_evidence = {
        **producer_docker_evidence,
        "artifact_source_job_identity_sha256": build["builder_job_identity_sha256"],
    }
    observer_docker_evidence = {
        **observer_docker_evidence,
        "artifact_source_job_identity_sha256": observation[
            "observer_job_identity_sha256"
        ],
    }
    return {
        "build_nonce": build["build_nonce"],
        "build_observation_receipt_sha256": observation["receipt_sha256"],
        "builder_job_identity_sha256": build["builder_job_identity_sha256"],
        "observer_job_identity_sha256": observation["observer_job_identity_sha256"],
        "observer_nonce": observation["observer_nonce"],
        "observer_docker_archive": observer_docker_evidence,
        "observer_oci_archive": observer_evidence,
        "producer_docker_archive": producer_docker_evidence,
        "producer_oci_archive": producer_evidence,
        "runtime_build_receipt_sha256": build["receipt_sha256"],
        "verified_nonce": build["verified_nonce"],
        "wheelhouse_manifest_sha256": observation["wheelhouse_manifest_sha256"],
    }


def _require_distinct_archive_evidence(
    evidences: list[Mapping[str, Any]], *, context: str
) -> None:
    paths: set[str] = set()
    physical: set[tuple[Any, ...]] = set()
    for evidence in evidences:
        identity = evidence["archive_physical_identity"]
        identity_kind = evidence.get("physical_identity_kind")
        if identity_kind is None:
            identity_kind = (
                "DEVICE_INODE"
                if identity[0] > 0 and identity[1] > 0
                else "CANONICAL_PATH_SINGLE_LINK"
            )
        if identity_kind == "DEVICE_INODE":
            key: tuple[Any, ...] = ("DEVICE_INODE", identity[0], identity[1])
        elif identity_kind == "CANONICAL_PATH_SINGLE_LINK":
            key = ("SINGLE_LINK_METADATA", *identity[2:])
        else:
            raise RuntimePolicyValidationError(
                f"{context} archive identity kind is malformed"
            )
        if evidence["archive_path"] in paths or key in physical:
            raise RuntimePolicyValidationError(
                f"{context} reuses one candidate archive materialization"
            )
        paths.add(evidence["archive_path"])
        physical.add(key)


_STATIC_EXECUTION_BUNDLE_KEYS = {
    "artifact_transport_receipt",
    "candidate_archive_path",
    "contract_command",
    "dispatch",
    "expected_candidate_binding",
    "expected_scope_inventory",
    "expected_transport_binding",
    "materialization_manifest",
    "materialization_receipt",
}
_MATERIALIZATION_SET_BUNDLE_KEYS = {
    "candidate_archive_path",
    "expected_candidate_binding",
    "expected_scope_inventory",
    "materialization_manifest",
    "materialization_receipt",
}
_STATIC_TOPOLOGY_SEAL = object()


@dataclass(frozen=True)
class _ValidatedStaticTopology:
    seal: object
    command_contract_sha256: str
    expected_run_binding_sha256: str
    policy_sha256: str
    projection_json: bytes
    projection_sha256: str


def verify_static_topology_c(
    executions: Any,
    *,
    expected_run_binding: Mapping[str, Any],
    policy: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
    validated_shared_runtime: object,
) -> tuple[_ValidatedStaticTopology, dict[str, Any], str]:
    if not isinstance(executions, list) or len(executions) != len(
        SUPPORTED_COMMAND_IDS
    ):
        raise RuntimePolicyValidationError(
            "static topology requires exactly two command executions"
        )
    validated_policy = validate_runtime_policy(policy)
    validated_contract = command_contract.validate_command_contract(
        validated_command_contract
    )
    expected_run, expected_run_sha = validate_expected_run_binding(expected_run_binding)
    runtime_projection = _shared_runtime_projection(
        validated_shared_runtime,
        expected_run_binding=expected_run,
        policy=validated_policy,
        validated_command_contract=validated_contract,
    )
    execution_projections: list[dict[str, Any]] = []
    archive_evidences: list[dict[str, Any]] = []
    command_ids: list[str] = []
    for value in executions:
        item = _assert_exact_keys(
            value, _STATIC_EXECUTION_BUNDLE_KEYS, context="static execution bundle"
        )
        dispatch_sha, archive_evidence = verify_static_dispatch_receipts(
            item["contract_command"],
            item["dispatch"],
            validated_policy,
            materialization_receipt=item["materialization_receipt"],
            materialization_manifest=item["materialization_manifest"],
            expected_candidate_binding=item["expected_candidate_binding"],
            expected_scope_inventory=item["expected_scope_inventory"],
            expected_run_binding=expected_run,
            candidate_archive_path=item["candidate_archive_path"],
            validated_command_contract=validated_contract,
            validated_shared_runtime=validated_shared_runtime,
            artifact_transport_receipt=item["artifact_transport_receipt"],
            expected_transport_binding=item["expected_transport_binding"],
        )
        materialization = item["materialization_receipt"]
        transport = item["artifact_transport_receipt"]
        command_id = materialization["command_id"]
        command_ids.append(command_id)
        archive_evidences.append(archive_evidence)
        execution_projections.append(
            {
                "artifact_name": transport["artifact_name"],
                "artifact_transport_receipt_sha256": transport["receipt_sha256"],
                "candidate_archive": archive_evidence,
                "closure_kind": materialization["closure_kind"],
                "command_id": command_id,
                "created_nonce": materialization["created_nonce"],
                "dispatch_sha256": dispatch_sha,
                "materialization_receipt_sha256": materialization["receipt_sha256"],
                "producer_job_identity_sha256": materialization[
                    "producer_job_identity_sha256"
                ],
                "transport_nonce": transport["transport_nonce"],
                "verified_nonce": materialization["verified_nonce"],
            }
        )
    if tuple(command_ids) != SUPPORTED_COMMAND_IDS:
        raise RuntimePolicyValidationError(
            "static topology command order or membership drifted"
        )
    _require_distinct_archive_evidence(archive_evidences, context="static topology")
    candidate_pairs = {
        (
            item["materialization_receipt"]["candidate_sha"],
            item["materialization_receipt"]["candidate_tree_sha"],
        )
        for item in executions
    }
    if len(candidate_pairs) != 1:
        raise RuntimePolicyValidationError("static topology spans multiple candidates")
    distinct_fields = (
        "artifact_name",
        "artifact_transport_receipt_sha256",
        "materialization_receipt_sha256",
        "producer_job_identity_sha256",
        "transport_nonce",
    )
    for field in distinct_fields:
        if len({item[field] for item in execution_projections}) != len(
            execution_projections
        ):
            raise RuntimePolicyValidationError(f"static topology reuses {field}")
    nonces = [
        runtime_projection["build_nonce"],
        runtime_projection["verified_nonce"],
        runtime_projection["observer_nonce"],
        *[
            item[key]
            for item in execution_projections
            for key in ("created_nonce", "verified_nonce", "transport_nonce")
        ],
    ]
    if len(nonces) != len(set(nonces)):
        raise RuntimePolicyValidationError("static topology reuses a receipt nonce")
    candidate_sha, candidate_tree_sha = next(iter(candidate_pairs))
    projection = {
        "accepted_a8": ACCEPTED_A8,
        "candidate_sha": candidate_sha,
        "candidate_tree_sha": candidate_tree_sha,
        "command_order": list(SUPPORTED_COMMAND_IDS),
        "executions": execution_projections,
        "schema_version": "phase8-static-topology-c.v1",
        "shared_runtime": runtime_projection,
    }
    projection_sha = canonical_sha256(projection)
    topology = _ValidatedStaticTopology(
        seal=_STATIC_TOPOLOGY_SEAL,
        command_contract_sha256=command_contract.canonical_sha256(validated_contract),
        expected_run_binding_sha256=expected_run_sha,
        policy_sha256=canonical_sha256(validated_policy),
        projection_json=canonical_json_bytes(projection),
        projection_sha256=projection_sha,
    )
    return topology, projection, projection_sha


def _consume_static_topology(
    topology: object,
    *,
    expected_run_binding: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
) -> dict[str, Any]:
    if (
        type(topology) is not _ValidatedStaticTopology
        or topology.seal is not _STATIC_TOPOLOGY_SEAL
    ):
        raise RuntimePolicyValidationError(
            "static topology was not produced by the trusted verifier"
        )
    _, expected_run_sha = validate_expected_run_binding(expected_run_binding)
    validated_contract = command_contract.validate_command_contract(
        validated_command_contract
    )
    if (
        topology.expected_run_binding_sha256 != expected_run_sha
        or topology.command_contract_sha256
        != command_contract.canonical_sha256(validated_contract)
    ):
        raise RuntimePolicyValidationError("static topology authority binding drifted")
    projection = _parse_strict_json_bytes(
        topology.projection_json,
        max_bytes=MAX_MANIFEST_BYTES,
        context="validated static topology projection",
    )
    if canonical_sha256(projection) != topology.projection_sha256:
        raise RuntimePolicyValidationError("static topology projection seal drifted")
    return projection


def verify_engineering_materialization_set(
    java_materializations: Any,
    *,
    expected_run_binding: Mapping[str, Any],
    validated_command_contract: Mapping[str, Any],
    validated_static_topology: object,
) -> tuple[dict[str, Any], str]:
    expected_java_commands = tuple(
        command_id
        for command_id in command_contract.COMMAND_ORDER
        if command_id not in SUPPORTED_COMMAND_IDS
    )
    if not isinstance(java_materializations, list) or len(java_materializations) != len(
        expected_java_commands
    ):
        raise RuntimePolicyValidationError(
            "engineering candidate requires exactly three Java materializations"
        )
    expected_run, _ = validate_expected_run_binding(expected_run_binding)
    validated_contract = command_contract.validate_command_contract(
        validated_command_contract
    )
    static_projection = _consume_static_topology(
        validated_static_topology,
        expected_run_binding=expected_run,
        validated_command_contract=validated_contract,
    )
    projections: list[dict[str, Any]] = [
        {
            key: copy.deepcopy(item[key])
            for key in (
                "candidate_archive",
                "closure_kind",
                "command_id",
                "created_nonce",
                "materialization_receipt_sha256",
                "producer_job_identity_sha256",
                "verified_nonce",
            )
        }
        for item in static_projection["executions"]
    ]
    archive_evidences: list[dict[str, Any]] = [
        item["candidate_archive"] for item in projections
    ]
    candidate_pairs: set[tuple[str, str]] = {
        (
            static_projection["candidate_sha"],
            static_projection["candidate_tree_sha"],
        )
    }
    java_command_ids: list[str] = []
    for value in java_materializations:
        item = _assert_exact_keys(
            value,
            _MATERIALIZATION_SET_BUNDLE_KEYS,
            context="engineering materialization bundle",
        )
        receipt, _, archive_evidence = verify_materialization_receipt_offline(
            item["materialization_receipt"],
            item["materialization_manifest"],
            item["expected_candidate_binding"],
            item["expected_scope_inventory"],
            expected_run,
            item["candidate_archive_path"],
        )
        archive_evidences.append(archive_evidence)
        candidate_pairs.add((receipt["candidate_sha"], receipt["candidate_tree_sha"]))
        java_command_ids.append(receipt["command_id"])
        projections.append(
            {
                "candidate_archive": archive_evidence,
                "closure_kind": receipt["closure_kind"],
                "command_id": receipt["command_id"],
                "created_nonce": receipt["created_nonce"],
                "materialization_receipt_sha256": receipt["receipt_sha256"],
                "producer_job_identity_sha256": receipt["producer_job_identity_sha256"],
                "verified_nonce": receipt["verified_nonce"],
            }
        )
    if tuple(java_command_ids) != expected_java_commands:
        raise RuntimePolicyValidationError(
            "Java materialization command order or membership drifted"
        )
    by_command = {item["command_id"]: item for item in projections}
    if len(by_command) != len(command_contract.COMMAND_ORDER):
        raise RuntimePolicyValidationError(
            "engineering materialization command membership drifted"
        )
    projections = [
        by_command[command_id] for command_id in command_contract.COMMAND_ORDER
    ]
    if any(
        item["closure_kind"] != COMMAND_CLOSURE_KINDS[item["command_id"]]
        for item in projections
    ):
        raise RuntimePolicyValidationError("engineering closure assignment drifted")
    if [item["closure_kind"] for item in projections].count(FULL_REPOSITORY) != 2:
        raise RuntimePolicyValidationError(
            "engineering candidate requires exactly two full closures"
        )
    if [item["closure_kind"] for item in projections].count(JAVA_SERVICE_ONLY) != 3:
        raise RuntimePolicyValidationError(
            "engineering candidate requires exactly three Java closures"
        )
    if len(candidate_pairs) != 1:
        raise RuntimePolicyValidationError(
            "engineering materializations span multiple candidates"
        )
    _require_distinct_archive_evidence(
        archive_evidences, context="engineering materialization set"
    )
    for field in (
        "materialization_receipt_sha256",
        "producer_job_identity_sha256",
        "created_nonce",
        "verified_nonce",
    ):
        if len({item[field] for item in projections}) != len(projections):
            raise RuntimePolicyValidationError(
                f"engineering materialization set reuses {field}"
            )
    runtime = static_projection["shared_runtime"]
    global_nonces = [
        runtime["build_nonce"],
        runtime["verified_nonce"],
        runtime["observer_nonce"],
        *[
            nonce
            for item in projections
            for nonce in (item["created_nonce"], item["verified_nonce"])
        ],
        *[item["transport_nonce"] for item in static_projection["executions"]],
    ]
    if len(global_nonces) != len(set(global_nonces)):
        raise RuntimePolicyValidationError(
            "engineering evidence topology reuses a nonce across jobs"
        )
    producer_identities = [
        runtime["builder_job_identity_sha256"],
        runtime["observer_job_identity_sha256"],
        *[item["producer_job_identity_sha256"] for item in projections],
    ]
    if len(producer_identities) != len(set(producer_identities)):
        raise RuntimePolicyValidationError(
            "engineering evidence topology reuses a producer identity"
        )
    candidate_sha, candidate_tree_sha = next(iter(candidate_pairs))
    projection = {
        "accepted_a8": ACCEPTED_A8,
        "candidate_sha": candidate_sha,
        "candidate_tree_sha": candidate_tree_sha,
        "closure_counts": {FULL_REPOSITORY: 2, JAVA_SERVICE_ONLY: 3},
        "command_order": list(command_contract.COMMAND_ORDER),
        "materializations": projections,
        "schema_version": "phase8-engineering-materialization-set.v1",
        "static_topology_sha256": validated_static_topology.projection_sha256,
    }
    return projection, canonical_sha256(projection)


__all__ = [
    "ACCEPTED_A8",
    "ARTIFACT_PAYLOAD_KIND",
    "ARTIFACT_TRANSPORT_RECEIPT_KIND",
    "ARTIFACT_TRANSPORT_RECEIPT_SCHEMA_VERSION",
    "AUTHORITY",
    "BASE_IMAGE",
    "BASE_IMAGE_ACQUISITION_NETWORK_PROFILE",
    "BUILDKIT_IMAGE",
    "BUILDX_DRIVER",
    "BUILD_JOB_NAME",
    "BUILD_PARAMETERS",
    "BUILD_OBSERVATION_RECEIPT_KIND",
    "BUILD_OBSERVATION_RECEIPT_SCHEMA_VERSION",
    "EXPECTED_POLICY_SHA256",
    "FIXED_ENVIRONMENT",
    "FORBIDDEN_COMMAND_IDS",
    "FORBIDDEN_EXECUTION_CLAIMS",
    "FULL_REPOSITORY",
    "GITHUB_JOB_IDENTITY_SCHEMA_VERSION",
    "GITHUB_REPOSITORY_ID",
    "JAVA_SERVICE_ONLY",
    "MAX_JSON_DEPTH",
    "MAX_JSON_NODES",
    "MAX_DOCKER_ARCHIVE_BYTES",
    "MAX_POLICY_BYTES",
    "MAX_RECEIPT_BYTES",
    "MATERIALIZATION_RECEIPT_KIND",
    "MATERIALIZATION_RECEIPT_SCHEMA_VERSION",
    "MATERIALIZATION_CLOSURE_KINDS",
    "MOUNT_POLICY",
    "OBSERVER_JOB_NAME",
    "POLICY_PATH",
    "REQUIRED_RUNTIME_FLAGS",
    "RUNTIME_POLICY_SCHEMA",
    "RUNTIME_BUILD_RECEIPT_KIND",
    "RUNTIME_BUILD_RECEIPT_SCHEMA_VERSION",
    "RUNTIME_RESOURCES",
    "RuntimePolicyValidationError",
    "SCHEMA_VERSION",
    "STATIC_BACKEND_KIND",
    "STATIC_COMMAND_ARTIFACT_PREFIXES",
    "STATIC_COMMAND_JOB_NAMES",
    "SUPPORTED_COMMAND_IDS",
    "CANDIDATE_ARCHIVE_FORMAT",
    "CANDIDATE_SOURCE_POLICY",
    "COMMAND_JOB_NAMES",
    "EXECUTOR_JOB_NAMES",
    "ValidatedCandidateArchive",
    "WHEELHOUSE_ACQUISITION_NETWORK_PROFILE",
    "assert_command_authorized",
    "assert_materialization_authorized_live",
    "assert_static_dispatch_authorized",
    "canonical_junit_file_index_sha256",
    "canonical_json_bytes",
    "canonical_receipt_sha256",
    "canonical_sha256",
    "load_runtime_policy",
    "parse_bounded_json_bytes",
    "parse_materialization_manifest_bytes",
    "parse_receipt_json_bytes",
    "parse_wheelhouse_manifest_bytes",
    "validate_materialization_scope_inventory",
    "validate_artifact_transport_receipt",
    "validate_build_observation_receipt",
    "validate_runtime_build_receipt",
    "validate_runtime_policy",
    "validate_expected_run_binding",
    "validate_github_job_identity",
    "validate_wheelhouse_directory",
    "verify_engineering_materialization_set",
    "verify_materialization_receipt_offline",
    "verify_shared_runtime_receipts",
    "verify_static_dispatch_receipts",
    "verify_static_topology_c",
]
