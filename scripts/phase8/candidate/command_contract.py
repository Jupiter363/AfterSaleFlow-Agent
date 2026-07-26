from __future__ import annotations

import copy
import hashlib
import json
import os
import stat
from pathlib import Path
from typing import Any, Mapping

MODULE_PATH = Path(__file__)
ROOT = MODULE_PATH.parents[3]
CONTRACT_PATH = (
    ROOT / "contracts/agent-platform/phase8/engineering-candidate-commands.json"
)
SCHEMA_VERSION = "phase8-engineering-command-contract.v1"
CONTRACT_KIND = "ENGINEERING_CANDIDATE_COMMANDS_ONLY"
AUTHORITY_CEILING = "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
STATIC_BACKEND_KIND = "PINNED_TEST_CONTAINER"
MAVEN_BACKEND_KIND = "GITHUB_HOSTED_MAVEN"
STATIC_NETWORK_PROFILE = "STATIC_EGRESS_DENIED"
MAVEN_NETWORK_PROFILE = "GITHUB_HOSTED_EPHEMERAL_DEPENDENCY_AND_DISPOSABLE_TEST_NETWORK"
STATIC_CREDENTIAL_PROFILE = "NO_CREDENTIALS_SECRETS_OR_ID_TOKEN"
MAVEN_CREDENTIAL_PROFILE = "NO_PRODUCTION_CREDENTIALS_SECRETS_OR_ID_TOKEN"
REQUIRED_BLOB_BINDING_MARKER = (
    "PHASE8_COMMAND_CONTRACT_VALIDATOR_REQUIRED_FOR_SCOPE_AND_WITNESS_V1"
)
REQUIRED_BLOB_BINDING_PATH = "scripts/phase8/candidate/command_contract.py"
COMMAND_ORDER = (
    "wave_a_static",
    "wave_a_java",
    "wave_b_static_and_models",
    "wave_b_java_unit",
    "wave_b_postgresql_integration",
)
EXPECTED_COMMAND_SHA256 = {
    "wave_a_static": "d344882f010f6a5cf085512339d7b95c81d642aad92933fb393d97849f411a81",
    "wave_a_java": "134e9d6ac2d5580e24543f0386ee89c77039ecf51f4fccad747f177441e04db5",
    "wave_b_static_and_models": "a6ef9bb1096a2ccbc4736cff975be236479d16f6dbe770047564b9a146af8ea5",
    "wave_b_java_unit": "6699f7c3cb181fd0a1fcece4ec188c4702cd08af2754ecf71ba87ffe93bde322",
    "wave_b_postgresql_integration": "e4405fa984275a92f589fa009c4b9c5945d3e3617e913967f4922bfb3d903544",
}
STATIC_COMMAND_IDS = ("wave_a_static", "wave_b_static_and_models")
STATIC_ARTIFACT_SPECS = {
    "wave_a_static": (
        "wave_a_static.xml",
        "p/000-wave_a_static-junit.xml",
        "pytest",
        88,
    ),
    "wave_b_static_and_models": (
        "wave_b_static_and_models.xml",
        "p/002-wave_b_static_and_models-junit.xml",
        "pytest",
        406,
    ),
}
MAVEN_SUITE_SPECS: dict[str, tuple[tuple[str, str, int], ...]] = {
    "wave_a_java": (
        (
            "TEST-com.example.dispute.agentstream.persistence.AgentRunV2MigrationIntegrationTest.xml",
            "com.example.dispute.agentstream.persistence.AgentRunV2MigrationIntegrationTest",
            1,
        ),
        (
            "TEST-com.example.dispute.agentstream.persistence.AgentRunStreamReplayIntegrationTest.xml",
            "com.example.dispute.agentstream.persistence.AgentRunStreamReplayIntegrationTest",
            1,
        ),
    ),
    "wave_b_java_unit": (
        (
            "TEST-com.example.dispute.agentstream.AgentRunRecoverySchedulerTest.xml",
            "com.example.dispute.agentstream.AgentRunRecoverySchedulerTest",
            4,
        ),
        (
            "TEST-com.example.dispute.workflow.config.AgentRunV2PropertiesTest.xml",
            "com.example.dispute.workflow.config.AgentRunV2PropertiesTest",
            3,
        ),
        (
            "TEST-com.example.dispute.hearing.HearingSchedulerModeTest.xml",
            "com.example.dispute.hearing.HearingSchedulerModeTest",
            5,
        ),
        (
            "TEST-com.example.dispute.workflow.recovery.hearing.JdbcHearingSchedulerDetectorTest.xml",
            "com.example.dispute.workflow.recovery.hearing.JdbcHearingSchedulerDetectorTest",
            5,
        ),
        (
            "TEST-com.example.dispute.agentstream.persistence.StreamBackfillCoordinatorTest.xml",
            "com.example.dispute.agentstream.persistence.StreamBackfillCoordinatorTest",
            3,
        ),
        (
            "TEST-com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifestTest.xml",
            "com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifestTest",
            5,
        ),
        (
            "TEST-com.example.dispute.agentstream.infrastructure.delivery.RedisAgentRunStreamFailoverTest.xml",
            "com.example.dispute.agentstream.infrastructure.delivery.RedisAgentRunStreamFailoverTest",
            5,
        ),
    ),
    "wave_b_postgresql_integration": (
        (
            "TEST-com.example.dispute.agentstream.persistence.AgentRunStreamReplayIntegrationTest.xml",
            "com.example.dispute.agentstream.persistence.AgentRunStreamReplayIntegrationTest",
            1,
        ),
    ),
}
MIGRATION_GATES = ("MIG-006", "MIG-007", "MIG-008")
MAX_CONTRACT_BYTES = 64 * 1024
MAX_VALIDATOR_BYTES = 256 * 1024
MAX_JSON_DEPTH = 12
MAX_JSON_NODES = 512
READ_CHUNK_BYTES = 64 * 1024
SELF_SEAL_CANONICALIZATION = "JSON_SORT_KEYS_COMPACT_UTF8_V1"
_DOT = "."
_EMPTY_BYTES = b""


class CommandContractValidationError(ValueError):
    """Raised when the fixed Phase 8 engineering command contract drifts."""


def _metadata_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def _metadata_is_alias(metadata: os.stat_result) -> bool:
    if stat.S_ISLNK(metadata.st_mode):
        return True
    try:
        attributes = metadata.st_file_attributes
        reparse_flag = stat.FILE_ATTRIBUTE_REPARSE_POINT
    except AttributeError:
        return False
    return bool(attributes & reparse_flag)


def _absolute_path_without_alias_ancestry(path: Path, *, context: str) -> Path:
    absolute = Path(os.path.abspath(os.fspath(path)))
    if not absolute.is_absolute() or ".." in absolute.parts:
        raise CommandContractValidationError(
            f"{context} path is not absolute and normalized"
        )
    cursor = Path(absolute.anchor)
    try:
        for part in absolute.parts[1:]:
            cursor = cursor / part
            if _metadata_is_alias(os.lstat(cursor)):
                raise CommandContractValidationError(
                    f"{context} ancestry contains a symlink or reparse point"
                )
    except OSError as exception:
        raise CommandContractValidationError(
            f"{context} ancestry cannot be inspected"
        ) from exception
    return absolute


def _assert_regular_bounded_metadata(
    metadata: os.stat_result, *, maximum_bytes: int, context: str
) -> None:
    if (
        _metadata_is_alias(metadata)
        or not stat.S_ISREG(metadata.st_mode)
        or metadata.st_nlink != 1
        or metadata.st_size < 1
        or metadata.st_size > maximum_bytes
    ):
        raise CommandContractValidationError(
            f"{context} must be one bounded single-link regular file"
        )


def _read_stable_no_follow_file(
    path: Path, *, maximum_bytes: int, context: str
) -> tuple[bytes, tuple[int, int, int, int, int]]:
    absolute = _absolute_path_without_alias_ancestry(path, context=context)
    descriptor = -1
    try:
        before_path = os.lstat(absolute)
        _assert_regular_bounded_metadata(
            before_path, maximum_bytes=maximum_bytes, context=context
        )
        flags = os.O_RDONLY
        try:
            flags |= os.O_BINARY
        except AttributeError:
            pass
        try:
            flags |= os.O_CLOEXEC
        except AttributeError:
            pass
        try:
            flags |= os.O_NOFOLLOW
        except AttributeError:
            pass
        descriptor = os.open(os.fspath(absolute), flags)
        before_fd = os.fstat(descriptor)
        _assert_regular_bounded_metadata(
            before_fd, maximum_bytes=maximum_bytes, context=context
        )
        if _metadata_identity(before_path) != _metadata_identity(before_fd):
            raise CommandContractValidationError(
                f"{context} changed while being opened"
            )

        chunks: list[bytes] = []
        total = 0
        while True:
            remaining = maximum_bytes + 1 - total
            if remaining <= 0:
                raise CommandContractValidationError(
                    f"{context} exceeded its byte limit"
                )
            chunk = os.read(descriptor, min(READ_CHUNK_BYTES, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
        payload = _EMPTY_BYTES.join(chunks)
        after_fd = os.fstat(descriptor)
        _assert_regular_bounded_metadata(
            after_fd, maximum_bytes=maximum_bytes, context=context
        )
        after_path = os.lstat(absolute)
        _assert_regular_bounded_metadata(
            after_path, maximum_bytes=maximum_bytes, context=context
        )
        _absolute_path_without_alias_ancestry(absolute, context=context)
        expected_identity = _metadata_identity(before_fd)
        if (
            _metadata_identity(before_path) != expected_identity
            or _metadata_identity(after_fd) != expected_identity
            or _metadata_identity(after_path) != expected_identity
            or len(payload) != before_fd.st_size
        ):
            raise CommandContractValidationError(f"{context} changed while being read")
        return payload, expected_identity
    except CommandContractValidationError:
        raise
    except OSError as exception:
        raise CommandContractValidationError(
            f"{context} cannot be read safely"
        ) from exception
    finally:
        if descriptor >= 0:
            try:
                os.close(descriptor)
            except OSError as exception:
                raise CommandContractValidationError(
                    f"{context} descriptor could not be closed safely"
                ) from exception


def _git_blob_sha1(payload: bytes) -> str:
    header_text = f"blob {len(payload)}\0"
    header = header_text.encode("ascii")
    digest = hashlib.sha1(header + payload)
    return digest.hexdigest()


_IMPORTED_MODULE_BYTES, _IMPORTED_MODULE_IDENTITY = _read_stable_no_follow_file(
    MODULE_PATH,
    maximum_bytes=MAX_VALIDATOR_BYTES,
    context="command validator module",
)
_IMPORTED_MODULE_DIGEST = hashlib.sha256(_IMPORTED_MODULE_BYTES)
_IMPORTED_MODULE_FILE_SHA256 = _IMPORTED_MODULE_DIGEST.hexdigest()
_IMPORTED_MODULE_GIT_BLOB_SHA1 = _git_blob_sha1(_IMPORTED_MODULE_BYTES)


_STRING = {"type": "string", "minLength": 1, "maxLength": 4096}
_PYTEST_ENV_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "CI",
        "PYTHONDONTWRITEBYTECODE",
        "PYTHONHASHSEED",
        "PYTHONNOUSERSITE",
        "PYTEST_DISABLE_PLUGIN_AUTOLOAD",
    ],
    "properties": {
        "CI": {"const": "1"},
        "PYTHONDONTWRITEBYTECODE": {"const": "1"},
        "PYTHONHASHSEED": {"const": "0"},
        "PYTHONNOUSERSITE": {"const": "1"},
        "PYTEST_DISABLE_PLUGIN_AUTOLOAD": {"const": "1"},
    },
}
_MAVEN_ENV_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["CI", "MAVEN_OPTS"],
    "properties": {
        "CI": {"const": "1"},
        "MAVEN_OPTS": {"const": "-Djava.awt.headless=true"},
    },
}
_STATIC_ARTIFACT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "archive_path",
        "filename",
        "format",
        "suite_name",
        "test_count",
    ],
    "properties": {
        "archive_path": _STRING,
        "filename": _STRING,
        "format": {"const": "JUNIT_XML"},
        "suite_name": {"const": "pytest"},
        "test_count": {"type": "integer", "minimum": 1, "maximum": 1000},
    },
}
_MAVEN_ARTIFACT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "not": {
        "properties": {"suite_name": {"const": "pytest"}},
        "required": ["suite_name"],
    },
    "required": ["archive_path", "filename", "format", "suite_name", "test_count"],
    "properties": {
        "archive_path": _STRING,
        "filename": _STRING,
        "format": {"const": "JUNIT_XML"},
        "suite_name": _STRING,
        "test_count": {"type": "integer", "minimum": 1, "maximum": 1000},
    },
}
_REPORT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "archive_prefix",
        "artifact_set_policy",
        "expected_artifacts",
        "glob",
        "source_root",
    ],
    "properties": {
        "archive_prefix": _STRING,
        "artifact_set_policy": {"const": "EXACT_NO_MISSING_EXTRA_OR_DUPLICATE"},
        "expected_artifacts": {
            "type": "array",
            "minItems": 1,
            "maxItems": 16,
            "items": {"oneOf": [_STATIC_ARTIFACT_SCHEMA, _MAVEN_ARTIFACT_SCHEMA]},
        },
        "glob": _STRING,
        "source_root": _STRING,
    },
}
_COMMAND_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "argv",
        "backend_kind",
        "credential_profile",
        "cwd",
        "environment",
        "external_egress_denied",
        "fresh_materialization",
        "fresh_runner",
        "id",
        "network_profile",
        "report",
        "shell",
        "stop_first",
        "timeout_seconds",
    ],
    "properties": {
        "argv": {
            "type": "array",
            "minItems": 1,
            "maxItems": 32,
            "items": _STRING,
        },
        "backend_kind": {"enum": [STATIC_BACKEND_KIND, MAVEN_BACKEND_KIND]},
        "credential_profile": {
            "enum": [STATIC_CREDENTIAL_PROFILE, MAVEN_CREDENTIAL_PROFILE]
        },
        "cwd": _STRING,
        "environment": {"oneOf": [_PYTEST_ENV_SCHEMA, _MAVEN_ENV_SCHEMA]},
        "executable_mode": {"const": "100755"},
        "executable_path": {"const": "java-api-service/mvnw"},
        "external_egress_denied": {"type": "boolean"},
        "fresh_materialization": {"const": True},
        "fresh_runner": {"const": True},
        "id": {"enum": list(COMMAND_ORDER)},
        "network_profile": {"enum": [STATIC_NETWORK_PROFILE, MAVEN_NETWORK_PROFILE]},
        "report": _REPORT_SCHEMA,
        "shell": {"const": False},
        "stop_first": {"const": True},
        "timeout_seconds": {"type": "integer", "minimum": 1, "maximum": 3600},
    },
}
_AUTHORITY_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "MIG-006",
        "MIG-007",
        "MIG-008",
        "authority_ceiling",
        "cloud_authority",
        "id_token_present",
        "maven_external_egress_denied",
        "maven_network_profile",
        "model_authority",
        "production_access",
        "production_actions",
        "production_authority",
        "production_credentials_present",
        "production_secrets_present",
        "production_traffic",
        "static_egress",
        "temporal_authority",
    ],
    "properties": {
        "MIG-006": {"const": "PENDING_PROMOTION"},
        "MIG-007": {"const": "PENDING_PROMOTION"},
        "MIG-008": {"const": "PENDING_PROMOTION"},
        "authority_ceiling": {"const": AUTHORITY_CEILING},
        "cloud_authority": {"const": "FORBIDDEN"},
        "id_token_present": {"const": False},
        "maven_external_egress_denied": {"const": False},
        "maven_network_profile": {"const": MAVEN_NETWORK_PROFILE},
        "model_authority": {"const": "FORBIDDEN"},
        "production_access": {"const": "FORBIDDEN"},
        "production_actions": {"const": "FORBIDDEN"},
        "production_authority": {"const": "FORBIDDEN"},
        "production_credentials_present": {"const": False},
        "production_secrets_present": {"const": False},
        "production_traffic": {"const": "FORBIDDEN"},
        "static_egress": {"const": "DENIED"},
        "temporal_authority": {"const": "FORBIDDEN"},
    },
}
_REQUIRED_BLOB_BINDING_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["file_sha256", "git_blob_sha1", "marker", "mode", "path"],
    "properties": {
        "file_sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "git_blob_sha1": {"type": "string", "pattern": "^[0-9a-f]{40}$"},
        "marker": {"const": REQUIRED_BLOB_BINDING_MARKER},
        "mode": {"const": "100644"},
        "path": {"const": REQUIRED_BLOB_BINDING_PATH},
    },
}
_SELF_SEAL_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["algorithm", "canonicalization", "payload_sha256"],
    "properties": {
        "algorithm": {"const": "SHA-256"},
        "canonicalization": {"const": SELF_SEAL_CANONICALIZATION},
        "payload_sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
    },
}
CONTRACT_SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "additionalProperties": False,
    "required": [
        "additional_fields",
        "authority",
        "command_order",
        "commands",
        "contract_kind",
        "phase",
        "required_blob_binding",
        "schema_version",
        "self_seal",
        "stop_policy",
    ],
    "properties": {
        "additional_fields": {"const": "DENY"},
        "authority": _AUTHORITY_SCHEMA,
        "command_order": {"const": list(COMMAND_ORDER)},
        "commands": {
            "type": "array",
            "minItems": len(COMMAND_ORDER),
            "maxItems": len(COMMAND_ORDER),
            "items": _COMMAND_SCHEMA,
        },
        "contract_kind": {"const": CONTRACT_KIND},
        "phase": {"const": 8},
        "required_blob_binding": _REQUIRED_BLOB_BINDING_SCHEMA,
        "schema_version": {"const": SCHEMA_VERSION},
        "self_seal": _SELF_SEAL_SCHEMA,
        "stop_policy": {"const": "STOP_ON_FIRST_FAILURE"},
    },
}
_ROOT_KEYS = {
    "additional_fields",
    "authority",
    "command_order",
    "commands",
    "contract_kind",
    "phase",
    "required_blob_binding",
    "schema_version",
    "self_seal",
    "stop_policy",
}
_AUTHORITY_KEYS = {
    "MIG-006",
    "MIG-007",
    "MIG-008",
    "authority_ceiling",
    "cloud_authority",
    "id_token_present",
    "maven_external_egress_denied",
    "maven_network_profile",
    "model_authority",
    "production_access",
    "production_actions",
    "production_authority",
    "production_credentials_present",
    "production_secrets_present",
    "production_traffic",
    "static_egress",
    "temporal_authority",
}
_COMMON_COMMAND_KEYS = {
    "argv",
    "backend_kind",
    "credential_profile",
    "cwd",
    "environment",
    "external_egress_denied",
    "fresh_materialization",
    "fresh_runner",
    "id",
    "network_profile",
    "report",
    "shell",
    "stop_first",
    "timeout_seconds",
}
_MAVEN_COMMAND_KEYS = _COMMON_COMMAND_KEYS | {"executable_mode", "executable_path"}
_PYTEST_ENV_KEYS = {
    "CI",
    "PYTHONDONTWRITEBYTECODE",
    "PYTHONHASHSEED",
    "PYTHONNOUSERSITE",
    "PYTEST_DISABLE_PLUGIN_AUTOLOAD",
}
_MAVEN_ENV_KEYS = {"CI", "MAVEN_OPTS"}
_REPORT_KEYS = {
    "archive_prefix",
    "artifact_set_policy",
    "expected_artifacts",
    "glob",
    "source_root",
}
_ARTIFACT_KEYS = {"archive_path", "filename", "format", "suite_name", "test_count"}
_BLOB_BINDING_KEYS = {"file_sha256", "git_blob_sha1", "marker", "mode", "path"}
_SELF_SEAL_KEYS = {"algorithm", "canonicalization", "payload_sha256"}
_LOWER_HEX = "0123456789abcdef"


def canonical_json_bytes(value: Any) -> bytes:
    try:
        serialized = json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        return serialized.encode("utf-8")
    except (TypeError, ValueError) as exception:
        raise CommandContractValidationError(
            f"command contract is not canonical JSON: {exception}"
        ) from exception


def canonical_sha256(value: Any) -> str:
    digest = hashlib.sha256(canonical_json_bytes(value))
    return digest.hexdigest()


def contract_payload_sha256(document: Mapping[str, Any]) -> str:
    payload = copy.deepcopy(dict(document))
    payload.pop("self_seal", None)
    return canonical_sha256(payload)


def validator_blob_binding() -> dict[str, str]:
    expected_path = Path(os.path.abspath(os.fspath(ROOT / REQUIRED_BLOB_BINDING_PATH)))
    module_path = Path(os.path.abspath(os.fspath(MODULE_PATH)))
    if module_path != expected_path:
        raise CommandContractValidationError("command validator path identity drifted")
    current_bytes, current_identity = _read_stable_no_follow_file(
        module_path,
        maximum_bytes=MAX_VALIDATOR_BYTES,
        context="command validator module",
    )
    if (
        current_identity != _IMPORTED_MODULE_IDENTITY
        or current_bytes != _IMPORTED_MODULE_BYTES
    ):
        raise CommandContractValidationError(
            "command validator bytes differ from the import-time trusted snapshot"
        )
    return {
        "file_sha256": _IMPORTED_MODULE_FILE_SHA256,
        "git_blob_sha1": _IMPORTED_MODULE_GIT_BLOB_SHA1,
        "marker": REQUIRED_BLOB_BINDING_MARKER,
        "mode": "100644",
        "path": REQUIRED_BLOB_BINDING_PATH,
    }


def _reject_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CommandContractValidationError(
                f"duplicate JSON key rejected: {key!r}"
            )
        result[key] = value
    return result


def _reject_json_constant(token: str) -> None:
    raise CommandContractValidationError(f"non-finite JSON number rejected: {token}")


def _assert_bounded_tree(value: Any) -> None:
    nodes = 0
    stack = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > MAX_JSON_NODES:
            raise CommandContractValidationError("command contract exceeds node limit")
        if depth > MAX_JSON_DEPTH:
            raise CommandContractValidationError("command contract exceeds depth limit")
        if isinstance(current, dict):
            stack.extend((item, depth + 1) for item in current.values())
        elif isinstance(current, list):
            stack.extend((item, depth + 1) for item in current)


def parse_bounded_json_bytes(raw: bytes) -> dict[str, Any]:
    if not isinstance(raw, bytes):
        raise CommandContractValidationError("command contract input must be bytes")
    if not raw or len(raw) > MAX_CONTRACT_BYTES:
        raise CommandContractValidationError(
            "command contract byte length is out of bounds"
        )
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise CommandContractValidationError("command contract must be BOM-free UTF-8")
    try:
        text = raw.decode("utf-8", errors="strict")
        parsed = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=_reject_json_constant,
        )
    except CommandContractValidationError:
        raise
    except (RecursionError, UnicodeDecodeError, ValueError) as exception:
        raise CommandContractValidationError(
            "command contract is not strict UTF-8 JSON"
        ) from exception
    if not isinstance(parsed, dict):
        raise CommandContractValidationError("command contract root must be an object")
    _assert_bounded_tree(parsed)
    return parsed


def _assert_exact_keys(value: Any, expected: set[str], *, context: str) -> None:
    if not isinstance(value, dict) or set(value) != expected:
        raise CommandContractValidationError(f"{context} keys or object type drifted")


def _is_lower_hex(value: Any, *, length: int) -> bool:
    return (
        isinstance(value, str)
        and len(value) == length
        and all(character in _LOWER_HEX for character in value)
    )


def _assert_exact_contract_shape(document: Mapping[str, Any]) -> None:
    _assert_exact_keys(document, _ROOT_KEYS, context="command contract root")
    if (
        document["additional_fields"] != "DENY"
        or document["contract_kind"] != CONTRACT_KIND
        or type(document["phase"]) is not int
        or document["phase"] != 8
        or document["schema_version"] != SCHEMA_VERSION
        or document["stop_policy"] != "STOP_ON_FIRST_FAILURE"
        or document["command_order"] != list(COMMAND_ORDER)
    ):
        raise CommandContractValidationError("command contract root values drifted")

    authority = document["authority"]
    _assert_exact_keys(authority, _AUTHORITY_KEYS, context="authority")
    commands = document["commands"]
    if not isinstance(commands, list) or len(commands) != len(COMMAND_ORDER):
        raise CommandContractValidationError("commands must be the exact bounded list")
    for order, expected_id in enumerate(COMMAND_ORDER):
        command = commands[order]
        if not isinstance(command, dict) or command.get("id") != expected_id:
            raise CommandContractValidationError(
                "command identity or object type drifted"
            )
        expected_keys = (
            _COMMON_COMMAND_KEYS
            if expected_id in STATIC_COMMAND_IDS
            else _MAVEN_COMMAND_KEYS
        )
        _assert_exact_keys(command, expected_keys, context=f"command {expected_id}")
        if not isinstance(command["argv"], list):
            raise CommandContractValidationError(
                f"command {expected_id} argv type drifted"
            )
        environment_keys = (
            _PYTEST_ENV_KEYS if expected_id in STATIC_COMMAND_IDS else _MAVEN_ENV_KEYS
        )
        _assert_exact_keys(
            command["environment"],
            environment_keys,
            context=f"command {expected_id} environment",
        )
        report = command["report"]
        _assert_exact_keys(
            report, _REPORT_KEYS, context=f"command {expected_id} report"
        )
        artifacts = report["expected_artifacts"]
        if not isinstance(artifacts, list) or not artifacts or len(artifacts) > 16:
            raise CommandContractValidationError(
                f"command {expected_id} report artifact list drifted"
            )
        for artifact in artifacts:
            _assert_exact_keys(
                artifact,
                _ARTIFACT_KEYS,
                context=f"command {expected_id} report artifact",
            )
        if canonical_sha256(command) != EXPECTED_COMMAND_SHA256[expected_id]:
            raise CommandContractValidationError(
                f"command {expected_id} payload drifted"
            )

    binding = document["required_blob_binding"]
    _assert_exact_keys(binding, _BLOB_BINDING_KEYS, context="validator blob binding")
    if (
        not _is_lower_hex(binding["file_sha256"], length=64)
        or not _is_lower_hex(binding["git_blob_sha1"], length=40)
        or binding["marker"] != REQUIRED_BLOB_BINDING_MARKER
        or binding["mode"] != "100644"
        or binding["path"] != REQUIRED_BLOB_BINDING_PATH
    ):
        raise CommandContractValidationError("validator blob binding shape drifted")

    seal = document["self_seal"]
    _assert_exact_keys(seal, _SELF_SEAL_KEYS, context="self seal")
    if (
        seal["algorithm"] != "SHA-256"
        or seal["canonicalization"] != SELF_SEAL_CANONICALIZATION
        or not _is_lower_hex(seal["payload_sha256"], length=64)
    ):
        raise CommandContractValidationError("self seal shape drifted")


def _assert_exact_invariants(document: Mapping[str, Any]) -> None:
    commands = document["commands"]
    if [command["id"] for command in commands] != list(COMMAND_ORDER):
        raise CommandContractValidationError("command order drifted")
    if {
        command["id"]: canonical_sha256(command) for command in commands
    } != EXPECTED_COMMAND_SHA256:
        raise CommandContractValidationError("fixed command payload drifted")
    if any(command["shell"] is not False for command in commands):
        raise CommandContractValidationError("shell execution is forbidden")
    if any(
        command["fresh_runner"] is not True
        or command["fresh_materialization"] is not True
        for command in commands
    ):
        raise CommandContractValidationError(
            "every command requires a fresh runner and materialization"
        )
    expected_backends = [
        STATIC_BACKEND_KIND,
        MAVEN_BACKEND_KIND,
        STATIC_BACKEND_KIND,
        MAVEN_BACKEND_KIND,
        MAVEN_BACKEND_KIND,
    ]
    if [command["backend_kind"] for command in commands] != expected_backends:
        raise CommandContractValidationError("fixed backend kinds drifted")
    expected_networks = [
        STATIC_NETWORK_PROFILE,
        MAVEN_NETWORK_PROFILE,
        STATIC_NETWORK_PROFILE,
        MAVEN_NETWORK_PROFILE,
        MAVEN_NETWORK_PROFILE,
    ]
    if [command["network_profile"] for command in commands] != expected_networks:
        raise CommandContractValidationError("network profiles drifted")
    expected_credentials = [
        STATIC_CREDENTIAL_PROFILE,
        MAVEN_CREDENTIAL_PROFILE,
        STATIC_CREDENTIAL_PROFILE,
        MAVEN_CREDENTIAL_PROFILE,
        MAVEN_CREDENTIAL_PROFILE,
    ]
    if [command["credential_profile"] for command in commands] != expected_credentials:
        raise CommandContractValidationError("credential profiles drifted")
    if [command["external_egress_denied"] for command in commands] != [
        True,
        False,
        True,
        False,
        False,
    ]:
        raise CommandContractValidationError("external egress claims drifted")
    if any(command["stop_first"] is not True for command in commands):
        raise CommandContractValidationError("every command must stop on first failure")

    expected_artifact_counts = (1, 2, 1, 7, 1)
    all_archive_paths: list[str] = []
    for order, command in enumerate(commands):
        cwd = command["cwd"]
        report = command["report"]
        if cwd not in {".", "java-api-service"} or ".." in Path(cwd).parts:
            raise CommandContractValidationError(
                "command cwd escaped the candidate root"
            )
        report_glob = report["glob"]
        if not report_glob.startswith(("target/", "/tmp/phase8-artifacts/")):
            raise CommandContractValidationError("report glob escaped fixed roots")
        if ".." in Path(report_glob).parts or ".." in Path(report["source_root"]).parts:
            raise CommandContractValidationError("report path contains traversal")
        expected_prefix = f"p/{order:03d}-{command['id']}"
        if report["archive_prefix"] != expected_prefix:
            raise CommandContractValidationError("report archive prefix drifted")
        artifacts = report["expected_artifacts"]
        if len(artifacts) != expected_artifact_counts[order]:
            raise CommandContractValidationError(
                "report artifact set is missing or extra"
            )
        filenames = [artifact["filename"] for artifact in artifacts]
        archive_paths = [artifact["archive_path"] for artifact in artifacts]
        if len({item.casefold() for item in filenames}) != len(filenames):
            raise CommandContractValidationError("duplicate report filename rejected")
        if len({item.casefold() for item in archive_paths}) != len(archive_paths):
            raise CommandContractValidationError(
                "duplicate report archive path rejected"
            )
        for filename, archive_path in zip(filenames, archive_paths, strict=True):
            if "/" in filename or "\\" in filename or filename in {".", ".."}:
                raise CommandContractValidationError(
                    "report filename is not one basename"
                )
            archive = Path(archive_path)
            if (
                not archive_path.startswith(f"{expected_prefix}-")
                or archive.is_absolute()
                or ".." in archive.parts
            ):
                raise CommandContractValidationError(
                    "report archive path escaped its prefix"
                )
        all_archive_paths.extend(archive_paths)
    if len({item.casefold() for item in all_archive_paths}) != len(all_archive_paths):
        raise CommandContractValidationError(
            "duplicate cross-command archive path rejected"
        )

    static_commands = (commands[0], commands[2])
    for command in static_commands:
        if command["argv"][:3] != ["/usr/local/bin/python", "-m", "pytest"]:
            raise CommandContractValidationError("static Python executable drifted")
        environment = command["environment"]
        argv = command["argv"]
        if environment["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] != "1":
            raise CommandContractValidationError(
                "pytest plugin autoload is not disabled"
            )
        if argv.count("no:cacheprovider") != 1:
            raise CommandContractValidationError("pytest cache provider policy drifted")
        junit_args = [arg for arg in command["argv"] if arg.startswith("--junitxml=")]
        if junit_args != [f"--junitxml={command['report']['glob']}"]:
            raise CommandContractValidationError("pytest JUnit target drifted")
        artifact = command["report"]["expected_artifacts"][0]
        if (
            set(artifact)
            != {
                "archive_path",
                "filename",
                "format",
                "suite_name",
                "test_count",
            }
            or (
                artifact["filename"],
                artifact["archive_path"],
                artifact["suite_name"],
                artifact["test_count"],
            )
            != STATIC_ARTIFACT_SPECS[command["id"]]
        ):
            raise CommandContractValidationError(
                "static JUnit identity or count drifted"
            )

    for command in (commands[1], commands[3], commands[4]):
        if command["argv"][:4] != ["./mvnw", "-B", "-ntp", "-DforkCount=1"]:
            raise CommandContractValidationError("Maven fixed argv prefix drifted")
        if command["executable_path"] != "java-api-service/mvnw":
            raise CommandContractValidationError("Maven executable path drifted")
        if command["executable_mode"] != "100755":
            raise CommandContractValidationError("Maven executable mode drifted")
        artifacts = command["report"]["expected_artifacts"]
        actual_specs = tuple(
            (artifact["filename"], artifact["suite_name"], artifact["test_count"])
            for artifact in artifacts
        )
        if actual_specs != MAVEN_SUITE_SPECS[command["id"]]:
            raise CommandContractValidationError(
                "Maven JUnit suite artifact set drifted"
            )
    if commands[4]["argv"][-3:] != [
        "-Pintegration-test",
        "-Dit.test=AgentRunStreamReplayIntegrationTest",
        "verify",
    ]:
        raise CommandContractValidationError("PostgreSQL integration selector drifted")

    authority = document["authority"]
    if any(authority[gate] != "PENDING_PROMOTION" for gate in MIGRATION_GATES):
        raise CommandContractValidationError("migration authority exceeded")
    if authority["static_egress"] != "DENIED":
        raise CommandContractValidationError("static egress authority exceeded")
    if (
        authority["maven_network_profile"] != MAVEN_NETWORK_PROFILE
        or authority["maven_external_egress_denied"] is not False
    ):
        raise CommandContractValidationError(
            "Maven network boundary was misrepresented"
        )
    if any(
        authority[field] != "FORBIDDEN"
        for field in (
            "cloud_authority",
            "model_authority",
            "production_access",
            "production_actions",
            "production_authority",
            "production_traffic",
            "temporal_authority",
        )
    ):
        raise CommandContractValidationError("production or service authority exceeded")
    if any(
        authority[field] is not False
        for field in (
            "id_token_present",
            "production_credentials_present",
            "production_secrets_present",
        )
    ):
        raise CommandContractValidationError(
            "credential or id-token authority exceeded"
        )
    if document["required_blob_binding"] != validator_blob_binding():
        raise CommandContractValidationError("required validator blob binding drifted")


def validate_command_contract(document: Mapping[str, Any]) -> dict[str, Any]:
    try:
        candidate = copy.deepcopy(dict(document))
        _assert_exact_contract_shape(candidate)
        _assert_exact_invariants(candidate)
        payload_sha256 = contract_payload_sha256(candidate)
        if candidate["self_seal"]["payload_sha256"] != payload_sha256:
            raise CommandContractValidationError(
                "canonical command contract self-seal drifted"
            )
        return candidate
    except CommandContractValidationError:
        raise
    except (
        AttributeError,
        IndexError,
        KeyError,
        RecursionError,
        TypeError,
        ValueError,
    ) as exception:
        raise CommandContractValidationError(
            "command contract validation failed closed"
        ) from exception


def validate_report_inventory(
    command_id: str, artifacts: list[Mapping[str, Any]]
) -> list[dict[str, Any]]:
    """Validate XML-derived report metadata against one exact command artifact set."""

    if command_id not in COMMAND_ORDER:
        raise CommandContractValidationError("unknown report command id")
    if not isinstance(artifacts, list) or len(artifacts) > 16:
        raise CommandContractValidationError("report inventory is not a bounded list")
    required_fields = {"archive_path", "filename", "format", "suite_name", "test_count"}
    normalized: list[dict[str, Any]] = []
    for artifact in artifacts:
        if not isinstance(artifact, Mapping) or set(artifact) != required_fields:
            raise CommandContractValidationError("report inventory fields drifted")
        item = copy.deepcopy(dict(artifact))
        if (
            not isinstance(item["archive_path"], str)
            or not isinstance(item["filename"], str)
            or item["format"] != "JUNIT_XML"
            or not isinstance(item["suite_name"], str)
            or not item["suite_name"]
            or isinstance(item["test_count"], bool)
            or not isinstance(item["test_count"], int)
            or item["test_count"] < 0
        ):
            raise CommandContractValidationError("report inventory metadata is invalid")
        normalized.append(item)

    filenames = [item["filename"] for item in normalized]
    archive_paths = [item["archive_path"] for item in normalized]
    if len({item.casefold() for item in filenames}) != len(filenames):
        raise CommandContractValidationError(
            "duplicate observed report filename rejected"
        )
    if len({item.casefold() for item in archive_paths}) != len(archive_paths):
        raise CommandContractValidationError("duplicate observed archive path rejected")

    if command_id in STATIC_COMMAND_IDS:
        expected_filename, expected_archive, expected_suite, expected_count = (
            STATIC_ARTIFACT_SPECS[command_id]
        )
        if len(normalized) != 1 or (
            normalized[0]["filename"],
            normalized[0]["archive_path"],
            normalized[0]["suite_name"],
            normalized[0]["test_count"],
        ) != (expected_filename, expected_archive, expected_suite, expected_count):
            raise CommandContractValidationError(
                "static report identity or count drifted"
            )
        return normalized

    expected = MAVEN_SUITE_SPECS[command_id]
    expected_by_filename = {
        filename: (suite_name, test_count)
        for filename, suite_name, test_count in expected
    }
    actual_by_filename = {item["filename"]: item for item in normalized}
    if set(actual_by_filename) != set(expected_by_filename):
        raise CommandContractValidationError(
            "Maven report inventory is missing or extra"
        )
    archive_prefix = f"p/{COMMAND_ORDER.index(command_id):03d}-{command_id}"
    for filename, (suite_name, test_count) in expected_by_filename.items():
        item = actual_by_filename[filename]
        expected_archive = f"{archive_prefix}-{filename}"
        if (
            item["suite_name"] != suite_name
            or item["test_count"] != test_count
            or item["archive_path"] != expected_archive
        ):
            raise CommandContractValidationError("Maven report suite metadata drifted")
    return [actual_by_filename[filename] for filename, _, _ in expected]


def load_command_contract(path: Path = CONTRACT_PATH) -> dict[str, Any]:
    if path != CONTRACT_PATH:
        raise CommandContractValidationError(
            "only the repository command contract may be loaded"
        )
    payload, _identity = _read_stable_no_follow_file(
        path,
        maximum_bytes=MAX_CONTRACT_BYTES,
        context="command contract",
    )
    return validate_command_contract(parse_bounded_json_bytes(payload))


__all__ = [
    "AUTHORITY_CEILING",
    "COMMAND_ORDER",
    "CONTRACT_KIND",
    "CONTRACT_PATH",
    "CONTRACT_SCHEMA",
    "CommandContractValidationError",
    "MAX_CONTRACT_BYTES",
    "MAX_JSON_DEPTH",
    "MAX_JSON_NODES",
    "MAVEN_BACKEND_KIND",
    "MAVEN_CREDENTIAL_PROFILE",
    "MAVEN_NETWORK_PROFILE",
    "MAVEN_SUITE_SPECS",
    "MIGRATION_GATES",
    "REQUIRED_BLOB_BINDING_MARKER",
    "REQUIRED_BLOB_BINDING_PATH",
    "SCHEMA_VERSION",
    "STATIC_ARTIFACT_SPECS",
    "STATIC_BACKEND_KIND",
    "STATIC_COMMAND_IDS",
    "STATIC_CREDENTIAL_PROFILE",
    "STATIC_NETWORK_PROFILE",
    "canonical_json_bytes",
    "canonical_sha256",
    "contract_payload_sha256",
    "load_command_contract",
    "parse_bounded_json_bytes",
    "validate_command_contract",
    "validate_report_inventory",
    "validator_blob_binding",
]
