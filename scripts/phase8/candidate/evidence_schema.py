from __future__ import annotations

import base64
import binascii
import copy
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[3]
SCHEMA_PATH = (
    ROOT / "contracts/agent-platform/phase8/checkpoint-evidence.schema.json"
)
SCHEMA_VERSION = "phase8-checkpoint-evidence.v1"
ENGINEERING_LOCAL = "ENGINEERING_LOCAL"
EXTERNAL_SIGNED = "EXTERNAL_SIGNED"
ENGINEERING_AUTHORITY_CEILING = "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
EXTERNAL_SHAPE_AUTHORITY_CEILING = "EXTERNAL_EVIDENCE_SHAPE_ONLY_UNVERIFIED"
SELF_SEAL_PURPOSE = "BYTE_INTEGRITY_AND_DRIFT_DETECTION_ONLY"
SELF_SEAL_CANONICALIZATION = "JSON_SORT_KEYS_COMPACT_UTF8_V1"
MIGRATION_GATES = ("MIG-006", "MIG-007", "MIG-008")
REVIEW_LANES = ("authority", "data_migration", "security_privacy")
PHASE8_DEPLOYMENT_MANIFEST_PATHS = (
    "deploy/production/phase8/capacity-policy.yaml",
    "deploy/production/phase8/hpa.yaml",
    "deploy/production/phase8/java-agent-worker.yaml",
    "deploy/production/phase8/java-api.yaml",
    "deploy/production/phase8/java-control-worker.yaml",
    "deploy/production/phase8/kustomization.yaml",
    "deploy/production/phase8/litellm.yaml",
    "deploy/production/phase8/pdb.yaml",
    "deploy/production/phase8/pgbouncer.yaml",
    "deploy/production/phase8/postgres-read-service.yaml",
    "deploy/production/phase8/python-agent.yaml",
    "deploy/production/phase8/security/kms-vault-policy.yaml",
    "deploy/production/phase8/security/mtls-policies.yaml",
    "deploy/production/phase8/security/network-policies.yaml",
    "deploy/production/phase8/security/object-store-policy.yaml",
    "deploy/production/phase8/security/rbac.yaml",
    "deploy/production/phase8/security/workload-identities.yaml",
    "deploy/production/phase8/topology-spread.yaml",
)
PHASE8_CONFIGURATION_MANIFEST_PATHS = (
    "deploy/observability/phase8/alerts.yaml",
    "deploy/observability/phase8/dashboards/agentrun-stream.json",
    "deploy/observability/phase8/dashboards/command-outbox.json",
    "deploy/observability/phase8/dashboards/disaster-recovery.json",
    "deploy/observability/phase8/dashboards/graph-checkpoint-lease.json",
    "deploy/observability/phase8/dashboards/model-provider.json",
    "deploy/observability/phase8/dashboards/projection-reconciliation.json",
    "deploy/observability/phase8/dashboards/security.json",
    "deploy/observability/phase8/dashboards/temporal-queue-history.json",
    "deploy/observability/phase8/otel-collector.yaml",
    "deploy/observability/phase8/recording-rules.yaml",
)
PRODUCTION_CAPABILITY_KEYS = (
    "canary",
    "cloud_access",
    "database_access",
    "production_traffic",
    "promotion",
    "recovery_execution",
    "scheduler_off_activation",
    "secret_access",
    "temporal_access",
    "v046_production_apply",
    "v046_production_switch",
    "v047_cleanup",
)
FIXED_ENGINEERING_COMMANDS: dict[str, dict[str, Any]] = {
    "phase8_wave_a_static": {
        "cwd": ".",
        "argv": [
            "D:/miniconda/python.exe",
            "-m",
            "pytest",
            "tests/static/test_phase8_active_reference_audit.py",
            "tests/static/test_phase8_v046_migration.py",
            "tests/static/test_phase8_production_topology.py",
            "tests/static/test_phase8_security_manifests.py",
            "tests/static/test_phase8_observability_assets.py",
            "tests/static/test_phase8_candidate_runner.py",
            "-p",
            "no:cacheprovider",
            "--tb=short",
            "-q",
        ],
    },
    "phase8_wave_b_static_and_models": {
        "cwd": ".",
        "argv": [
            "D:/miniconda/python.exe",
            "-m",
            "pytest",
            "tests/static/test_phase8_scheduler_lifecycle.py",
            "tests/static/test_phase8_cleanup_eligibility.py",
            "tests/static/test_phase8_stream_compatibility.py",
            "tests/static/test_phase8_stream_retention.py",
            "tests/static/test_phase8_capacity_harness.py",
            "tests/static/test_phase8_recovery_rotation_tools.py",
            "tests/static/test_phase8_scenario_catalog.py",
            "tests/static/test_phase8_external_gate_intake.py",
            "-p",
            "no:cacheprovider",
            "--tb=short",
            "-q",
        ],
    },
}
FIXED_ENGINEERING_COMMAND_ORDER = tuple(FIXED_ENGINEERING_COMMANDS)


class EvidenceValidationError(ValueError):
    """Raised when checkpoint evidence exceeds its authority or drifts."""


ALLOWED_SIGNATURE_ALGORITHMS = (
    "Ed25519",
    "ECDSA_P256_SHA256",
    "RSA_PSS_SHA256",
)


def canonical_json_bytes(value: Any) -> bytes:
    """Return the one canonical byte representation used by local integrity seals."""

    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exception:
        raise EvidenceValidationError(
            f"evidence cannot be represented as canonical JSON: {exception}"
        ) from exception


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def parse_rfc3339(value: Any, *, context: str) -> datetime:
    if not isinstance(value, str) or not value or value.endswith("z"):
        raise EvidenceValidationError(f"{context} must be an explicit RFC 3339 timestamp")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exception:
        raise EvidenceValidationError(f"{context} is not RFC 3339") from exception
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise EvidenceValidationError(f"{context} must include a timezone offset")
    return parsed.astimezone(timezone.utc)


def decode_signature(value: Any, *, context: str = "signature") -> bytes:
    if not isinstance(value, str) or not value or len(value) > 16384:
        raise EvidenceValidationError(f"{context} is not a bounded base64 signature")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exception:
        raise EvidenceValidationError(f"{context} is not strict base64") from exception
    if not decoded:
        raise EvidenceValidationError(f"{context} decoded to an empty signature")
    return decoded


def public_key_fingerprint_sha256(public_key_pem: bytes) -> str:
    if not isinstance(public_key_pem, bytes) or not public_key_pem:
        raise EvidenceValidationError("public key must be non-empty PEM bytes")
    try:
        from cryptography.exceptions import UnsupportedAlgorithm
        from cryptography.hazmat.primitives import serialization

        public_key = serialization.load_pem_public_key(public_key_pem)
        canonical_pem = public_key.public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        if public_key_pem != canonical_pem:
            raise EvidenceValidationError("public key PEM is not one canonical block")
        encoded = public_key.public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    except EvidenceValidationError:
        raise
    except ImportError as exception:
        raise EvidenceValidationError("cryptographic verifier is unavailable") from exception
    except (TypeError, UnsupportedAlgorithm, ValueError) as exception:
        raise EvidenceValidationError("public key is not valid PEM") from exception
    return hashlib.sha256(encoded).hexdigest()


def verify_detached_signature(
    *, algorithm: str, public_key_pem: bytes, payload: bytes, signature: bytes
) -> None:
    if algorithm not in ALLOWED_SIGNATURE_ALGORITHMS:
        raise EvidenceValidationError(f"signature algorithm is not allowlisted: {algorithm!r}")
    if not isinstance(payload, bytes) or not isinstance(signature, bytes):
        raise EvidenceValidationError("signature payload and signature must be bytes")
    try:
        from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec, ed25519, padding, rsa, utils

        public_key = serialization.load_pem_public_key(public_key_pem)
        if algorithm == "Ed25519":
            if not isinstance(public_key, ed25519.Ed25519PublicKey):
                raise EvidenceValidationError("Ed25519 key type does not match algorithm")
            if len(signature) != 64:
                raise EvidenceValidationError("Ed25519 signature must be exactly 64 bytes")
            public_key.verify(signature, payload)
        elif algorithm == "ECDSA_P256_SHA256":
            if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(
                public_key.curve, ec.SECP256R1
            ):
                raise EvidenceValidationError("ECDSA key is not P-256")
            if not 8 <= len(signature) <= 72:
                raise EvidenceValidationError("P-256 ECDSA signature length is invalid")
            if utils.encode_dss_signature(*utils.decode_dss_signature(signature)) != signature:
                raise EvidenceValidationError("P-256 ECDSA signature is not canonical DER")
            public_key.verify(signature, payload, ec.ECDSA(hashes.SHA256()))
        else:
            if (
                not isinstance(public_key, rsa.RSAPublicKey)
                or not 3072 <= public_key.key_size <= 8192
            ):
                raise EvidenceValidationError("RSA-PSS key must be between 3072 and 8192 bits")
            if len(signature) != (public_key.key_size + 7) // 8:
                raise EvidenceValidationError("RSA-PSS signature length does not match its key")
            public_key.verify(
                signature,
                payload,
                padding.PSS(
                    mgf=padding.MGF1(hashes.SHA256()),
                    salt_length=hashes.SHA256().digest_size,
                ),
                hashes.SHA256(),
            )
    except EvidenceValidationError:
        raise
    except ImportError as exception:
        raise EvidenceValidationError("cryptographic verifier is unavailable") from exception
    except InvalidSignature as exception:
        raise EvidenceValidationError("detached signature verification failed") from exception
    except (TypeError, UnsupportedAlgorithm, ValueError) as exception:
        raise EvidenceValidationError("signature key or encoding is invalid") from exception


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise EvidenceValidationError(f"duplicate JSON property rejected: {key}")
        value[key] = item
    return value


def _assert_json_resource_bounds(
    payload: bytes,
    *,
    context: str,
    max_bytes: int,
    max_depth: int,
    max_tokens: int,
    max_string_bytes: int,
) -> None:
    limits = (max_bytes, max_depth, max_tokens, max_string_bytes)
    if any(not isinstance(limit, int) or isinstance(limit, bool) or limit < 1 for limit in limits):
        raise EvidenceValidationError("JSON resource limits must be positive integers")
    if type(payload) is not bytes or len(payload) > max_bytes:
        raise EvidenceValidationError(f"{context} exceeds its immutable byte ceiling")

    depth = 0
    tokens = 0
    string_bytes = 0
    in_string = False
    escaped = False
    in_scalar = False
    for byte in payload:
        if in_string:
            if escaped:
                escaped = False
            elif byte == 0x5C:  # backslash
                escaped = True
            elif byte == 0x22:  # quote
                in_string = False
                string_bytes = 0
                continue
            string_bytes += 1
            if string_bytes > max_string_bytes:
                raise EvidenceValidationError(f"{context} contains an oversized JSON string")
            continue

        if byte == 0x22:
            in_string = True
            in_scalar = False
            tokens += 1
        elif byte in (0x7B, 0x5B):  # { [
            depth += 1
            in_scalar = False
            tokens += 1
            if depth > max_depth:
                raise EvidenceValidationError(f"{context} exceeds its JSON nesting ceiling")
        elif byte in (0x7D, 0x5D):  # } ]
            depth -= 1
            in_scalar = False
            tokens += 1
            if depth < 0:
                raise EvidenceValidationError(f"{context} has invalid JSON nesting")
        elif byte in (0x2C, 0x3A):  # , :
            in_scalar = False
            tokens += 1
        elif byte in (0x20, 0x09, 0x0A, 0x0D):
            in_scalar = False
        elif not in_scalar:
            in_scalar = True
            tokens += 1
        if tokens > max_tokens:
            raise EvidenceValidationError(f"{context} exceeds its JSON token ceiling")


def parse_json_bytes(payload: bytes, *, context: str = "evidence") -> dict[str, Any]:
    try:
        value = json.loads(
            payload.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=lambda item: (_ for _ in ()).throw(
                EvidenceValidationError(f"non-finite JSON number rejected: {item}")
            ),
        )
    except EvidenceValidationError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError, ValueError) as exception:
        raise EvidenceValidationError(f"{context} is not strict UTF-8 JSON") from exception
    if not isinstance(value, dict):
        raise EvidenceValidationError(f"{context} must be a JSON object")
    return value


def parse_bounded_json_bytes(
    payload: bytes,
    *,
    context: str = "evidence",
    max_bytes: int,
    max_depth: int,
    max_tokens: int,
    max_string_bytes: int,
) -> dict[str, Any]:
    _assert_json_resource_bounds(
        payload,
        context=context,
        max_bytes=max_bytes,
        max_depth=max_depth,
        max_tokens=max_tokens,
        max_string_bytes=max_string_bytes,
    )
    return parse_json_bytes(payload, context=context)


def load_schema(path: Path = SCHEMA_PATH) -> dict[str, Any]:
    try:
        schema = parse_json_bytes(path.read_bytes(), context="checkpoint evidence schema")
    except OSError as exception:
        raise EvidenceValidationError(
            f"cannot read checkpoint evidence schema: {exception}"
        ) from exception
    try:
        Draft202012Validator.check_schema(schema)
    except Exception as exception:
        raise EvidenceValidationError(
            f"checkpoint evidence schema is not valid Draft 2020-12: {exception}"
        ) from exception
    return schema


def self_seal_for(document: Mapping[str, Any]) -> dict[str, Any]:
    unsigned = copy.deepcopy(dict(document))
    unsigned.pop("self_seal", None)
    return {
        "algorithm": "SHA-256",
        "canonicalization": SELF_SEAL_CANONICALIZATION,
        "digest": canonical_sha256(unsigned),
        "proves_execution_authenticity": False,
        "proves_operator_identity": False,
        "proves_source_authenticity": False,
        "purpose": SELF_SEAL_PURPOSE,
    }


def seal_evidence(document: Mapping[str, Any]) -> dict[str, Any]:
    sealed = copy.deepcopy(dict(document))
    sealed.pop("self_seal", None)
    sealed["self_seal"] = self_seal_for(sealed)
    return sealed


def _schema_error(document: Mapping[str, Any]) -> str | None:
    validator = Draft202012Validator(load_schema(), format_checker=FormatChecker())
    errors = sorted(
        validator.iter_errors(document),
        key=lambda error: tuple(str(part) for part in error.absolute_path),
    )
    if not errors:
        return None
    error = errors[0]
    location = ".".join(str(part) for part in error.absolute_path) or "<root>"
    if error.validator == "oneOf" and error.context:
        leaf = min(error.context, key=lambda item: len(item.context))
        leaf_location = ".".join(str(part) for part in leaf.absolute_path)
        if leaf_location:
            location = leaf_location
        return f"schema validation failed at {location}: {leaf.message}"
    return f"schema validation failed at {location}: {error.message}"


def _assert_review_is_independent(document: Mapping[str, Any]) -> None:
    review = document["review"]
    producer = review["producer_identity"].casefold()
    reviewers = review["reviewers"]
    identities = [item["identity"].casefold() for item in reviewers]
    lanes = [item["lane"] for item in reviewers]
    if review["self_approved"] is not False:
        raise EvidenceValidationError("self-signoff is forbidden")
    if producer in identities:
        raise EvidenceValidationError("evidence producer cannot review its own evidence")
    if len(identities) != len(set(identities)):
        raise EvidenceValidationError("reviewer identities must be independent and unique")
    if sorted(lanes) != sorted(REVIEW_LANES):
        raise EvidenceValidationError("all fixed P0 review lanes are required exactly once")


def _assert_canonical_inventory(document: Mapping[str, Any]) -> None:
    candidate = document["candidate"]
    blobs = candidate["path_blobs"]
    paths = [item["path"] for item in blobs]
    if paths != sorted(paths):
        raise EvidenceValidationError("candidate path blob inventory is not canonical")
    if len({path.casefold() for path in paths}) != len(paths):
        raise EvidenceValidationError("candidate path blob inventory has a case collision")
    if candidate["path_blobs_sha256"] != canonical_sha256(blobs):
        raise EvidenceValidationError("candidate path blob inventory seal drifted")

    images = document["release_context"]["images"]
    image_names = [item["name"] for item in images]
    if image_names != sorted(image_names):
        raise EvidenceValidationError("image digest inventory is not canonical")
    if len({name.casefold() for name in image_names}) != len(image_names):
        raise EvidenceValidationError("image digest inventory has a case collision")

    path_blob_mapping = {item["path"]: item for item in blobs}
    release = document["release_context"]
    for field, exact_paths in (
        ("configuration", PHASE8_CONFIGURATION_MANIFEST_PATHS),
        ("deployment_manifest", PHASE8_DEPLOYMENT_MANIFEST_PATHS),
    ):
        bundle = release[field]
        bound_blobs = bundle["blobs"]
        bundle_paths = [item["path"] for item in bound_blobs]
        if bundle_paths != list(exact_paths):
            raise EvidenceValidationError(
                f"{field} inventory is not the exact Phase 8 allowlist"
            )
        if bundle["sha256"] != canonical_sha256(bound_blobs):
            raise EvidenceValidationError(f"{field} bundle digest drifted")
        for item in bound_blobs:
            candidate_item = path_blob_mapping.get(item["path"])
            if candidate_item is None or any(
                candidate_item[key] != item[key]
                for key in ("git_blob_sha", "path", "sha256")
            ):
                raise EvidenceValidationError(
                    f"{field} contains a substituted candidate blob: {item['path']}"
                )


def _assert_same_context_bindings(document: Mapping[str, Any]) -> None:
    candidate = document["candidate"]
    context = document["release_context"]
    lineage = document["attempt_lineage"]
    expected = {
        "attempt_id": lineage["attempt_id"],
        "candidate_sha": candidate["commit_sha"],
        "candidate_tree_sha": candidate["tree_sha"],
        "configuration_sha256": context["configuration"]["sha256"],
        "context_id": context["context_id"],
        "deployment_manifest_sha256": context["deployment_manifest"]["sha256"],
    }
    command_order = document["command_order"]
    commands = document["commands"]
    reports = document["reports"]
    if document["evidence_kind"] == ENGINEERING_LOCAL:
        if command_order != list(FIXED_ENGINEERING_COMMAND_ORDER):
            raise EvidenceValidationError("engineering command order is not the fixed allowlist")
        for command in commands:
            contract = FIXED_ENGINEERING_COMMANDS.get(command["id"])
            if (
                contract is None
                or command["argv"] != contract["argv"]
                or command["cwd"] != contract["cwd"]
                or command["shell"] is not False
            ):
                raise EvidenceValidationError(
                    f"arbitrary or drifted command rejected: {command['id']}"
                )
    if [item["id"] for item in commands] != command_order[: len(commands)]:
        raise EvidenceValidationError("commands are not the fixed ordered prefix")
    if [item["order"] for item in commands] != list(range(1, len(commands) + 1)):
        raise EvidenceValidationError("command order indexes are not contiguous")
    if len(commands) != len(reports):
        raise EvidenceValidationError("each executed command must bind exactly one report")
    if document["engineering_checkpoint"] == "PASS" and len(commands) != len(command_order):
        raise EvidenceValidationError("engineering PASS requires the complete command order")

    for command, report in zip(commands, reports, strict=True):
        for key, expected_value in expected.items():
            if command[key] != expected_value or report[key] != expected_value:
                raise EvidenceValidationError(
                    f"mixed candidate, deployment, context, or attempt at {command['id']}"
                )
        if (
            report["command_id"] != command["id"]
            or report["path"] != command["report_path"]
            or report["sha256"] != command["report_sha256"]
        ):
            raise EvidenceValidationError(
                f"report substitution detected for command {command['id']}"
            )
        if (command["exit_code"] == 0) != (command["status"] == "PASSED"):
            raise EvidenceValidationError(
                f"command status and exit code disagree for {command['id']}"
            )

    if document["engineering_checkpoint"] == "PASS":
        if any(command["status"] != "PASSED" for command in commands):
            raise EvidenceValidationError("engineering PASS contains a failed command")
    else:
        if not commands or commands[-1]["status"] != "FAILED":
            raise EvidenceValidationError("engineering FAIL must stop on its first failed command")
        if any(command["status"] != "PASSED" for command in commands[:-1]):
            raise EvidenceValidationError("engineering FAIL contains an earlier ignored failure")


def _assert_attempt_lineage(document: Mapping[str, Any]) -> None:
    lineage = document["attempt_lineage"]
    previous = lineage["previous_attempt_id"]
    number = lineage["attempt_number"]
    if (number == 1) != (previous is None):
        raise EvidenceValidationError(
            "attempt 1 must have no predecessor and later attempts must bind one predecessor"
        )
    if previous is not None and previous.casefold() == lineage["attempt_id"].casefold():
        raise EvidenceValidationError("an attempt cannot name itself as its predecessor")


def _assert_authority_ceiling(document: Mapping[str, Any]) -> None:
    if any(document[gate] != "PENDING_PROMOTION" for gate in MIGRATION_GATES):
        raise EvidenceValidationError("Phase 8 evidence cannot promote MIG-006 through MIG-008")
    if any(document["production_capabilities"].values()):
        raise EvidenceValidationError("Phase 8 candidate evidence grants production capability")
    if document["trust_root_verified"] is not False:
        raise EvidenceValidationError("P8-I5-1 cannot verify an external trust root")

    kind = document["evidence_kind"]
    if kind == ENGINEERING_LOCAL:
        if document["authority_ceiling"] != ENGINEERING_AUTHORITY_CEILING:
            raise EvidenceValidationError("engineering authority ceiling drifted")
        if document["production_checkpoint"] != "PENDING_EXTERNAL":
            raise EvidenceValidationError("local engineering evidence cannot decide production")
        sandbox = document["execution_sandbox"]
        if document["engineering_checkpoint"] == "PASS":
            if sandbox["backend_kind"] != "AUTHENTICATED_FIXED_BACKEND":
                raise EvidenceValidationError(
                    "fixture or self-asserted sandbox evidence cannot make engineering PASS"
                )
            expected_bindings = {
                "attempt_id": document["attempt_lineage"]["attempt_id"],
                "candidate_sha": document["candidate"]["commit_sha"],
                "candidate_tree_sha": document["candidate"]["tree_sha"],
                "configuration_sha256": document["release_context"]["configuration"][
                    "sha256"
                ],
                "context_sha256": document["release_context"]["context_sha256"],
                "deployment_manifest_sha256": document["release_context"][
                    "deployment_manifest"
                ]["sha256"],
                "exact_argv_sha256": canonical_sha256(
                    [
                        {
                            "argv": command["argv"],
                            "cwd": command["cwd"],
                            "id": command["id"],
                            "shell": command["shell"],
                        }
                        for command in document["commands"]
                    ]
                ),
            }
            for field, expected in expected_bindings.items():
                if sandbox[field] != expected:
                    raise EvidenceValidationError(
                        f"authenticated sandbox receipt binding drifted: {field}"
                    )
        elif sandbox["backend_kind"] == "FIXTURE_ONLY":
            if sandbox["authority"] != "TEST_LIFECYCLE_ONLY_NO_CHECKPOINT_PASS":
                raise EvidenceValidationError("fixture sandbox authority drifted")
    elif kind == EXTERNAL_SIGNED:
        if document["authority_ceiling"] != EXTERNAL_SHAPE_AUTHORITY_CEILING:
            raise EvidenceValidationError("external shape authority ceiling drifted")
        envelope = document["external_signature_envelope"]
        if envelope["verification_status"] != (
            "UNVERIFIED_REQUIRES_P8_I5_3_TRUST_ROOT_VALIDATION"
        ):
            raise EvidenceValidationError("external signature shape claims trust verification")
        signer_ids = [
            item["signer_identity"].casefold() for item in envelope["signatures"]
        ]
        roles = [item["role"] for item in envelope["signatures"]]
        if len(set(signer_ids)) != len(signer_ids) or len(set(roles)) != len(roles):
            raise EvidenceValidationError("external signatures are not six independent roles")
        producer = document["review"]["producer_identity"].casefold()
        if producer in signer_ids:
            raise EvidenceValidationError("external evidence producer cannot self-sign")
        if document["production_checkpoint"] != "PENDING_EXTERNAL":
            raise EvidenceValidationError(
                "shape-only external evidence cannot make production PASS"
            )
    else:  # Defensive even though the discriminator schema is closed.
        raise EvidenceValidationError(f"unknown evidence kind: {kind!r}")


def assert_self_seal(document: Mapping[str, Any]) -> None:
    if document.get("self_seal") != self_seal_for(document):
        raise EvidenceValidationError(
            "canonical self-seal drifted; the seal is integrity-only, not an attestation"
        )


def validate_evidence(document: Mapping[str, Any]) -> dict[str, Any]:
    """Validate strict shape, cross-record bindings, authority, and local integrity."""

    candidate = copy.deepcopy(dict(document))
    error = _schema_error(candidate)
    if error:
        raise EvidenceValidationError(error)
    _assert_review_is_independent(candidate)
    _assert_canonical_inventory(candidate)
    _assert_attempt_lineage(candidate)
    _assert_same_context_bindings(candidate)
    _assert_authority_ceiling(candidate)
    assert_self_seal(candidate)
    return candidate


__all__ = [
    "ALLOWED_SIGNATURE_ALGORITHMS",
    "ENGINEERING_AUTHORITY_CEILING",
    "ENGINEERING_LOCAL",
    "EXTERNAL_SHAPE_AUTHORITY_CEILING",
    "EXTERNAL_SIGNED",
    "EvidenceValidationError",
    "FIXED_ENGINEERING_COMMANDS",
    "FIXED_ENGINEERING_COMMAND_ORDER",
    "MIGRATION_GATES",
    "PHASE8_CONFIGURATION_MANIFEST_PATHS",
    "PHASE8_DEPLOYMENT_MANIFEST_PATHS",
    "PRODUCTION_CAPABILITY_KEYS",
    "SCHEMA_PATH",
    "SCHEMA_VERSION",
    "SELF_SEAL_PURPOSE",
    "assert_self_seal",
    "canonical_json_bytes",
    "canonical_sha256",
    "decode_signature",
    "load_schema",
    "parse_bounded_json_bytes",
    "parse_json_bytes",
    "parse_rfc3339",
    "public_key_fingerprint_sha256",
    "seal_evidence",
    "self_seal_for",
    "validate_evidence",
    "verify_detached_signature",
]
