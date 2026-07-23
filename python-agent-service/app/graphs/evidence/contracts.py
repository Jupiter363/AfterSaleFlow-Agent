from __future__ import annotations

import base64
import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Literal, Protocol, TypeAlias, cast
from weakref import WeakSet

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from app.contracts.v1.codec import canonical_sha256, canonicalize


JsonScalar: TypeAlias = str | int | float | bool | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]

ASSESSMENT_OUTPUT_SCHEMA_VERSION = "evidence-item-assessment.v1"
TERMINAL_OUTPUT_SCHEMA_VERSION = "evidence-batch-proposal.v1"
EVIDENCE_GRAPH_KEY = "evidence.v2"
EVIDENCE_GRAPH_VERSION = "evidence.v2.0.0"
EVIDENCE_STATE_SCHEMA_VERSION = "evidence-graph-state.v2"
MAX_MANIFEST_ITEMS = 100
MAX_ACTIVE_ITEMS = 8

VERIFIED_ADMISSION_STEPS = (
    "VERIFY_ROOM_GRAPH_COMMAND_SCHEMA_AND_REQUEST_HASH",
    "LOAD_EXACT_IMMUTABLE_MANIFEST_URI",
    "VERIFY_FULL_SNAPSHOT_PAYLOAD_SHA256_AND_SIZE",
    "VERIFY_INTERNAL_MANIFEST_RFC8785_SELF_HASH",
    "VERIFY_DIRECT_JAVA_ES256_MANIFEST_SIGNATURE",
    "DERIVE_AND_MATCH_RFC8785_ACTOR_SCOPE_HASH",
    "VERIFY_TRANSPORT_AND_REGISTRY_TERMINAL_OUTPUT_PIN",
    "VERIFY_INTERNAL_ITEM_ASSESSMENT_OUTPUT_PIN",
    "ENFORCE_DISTINCT_JAVA_ROOM_AND_GRAPH_LEASE_FENCES",
)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_P1363_BASE64URL = re.compile(r"^[A-Za-z0-9_-]{86}$")
_CONTENT_ADDRESSED_URI = re.compile(r"^.+/([0-9a-f]{64})\.json$")
_FORBIDDEN_AUTHORITY_KEYS = frozenset(
    {
        "authorization_proof_ref",
        "formal_merge",
        "formal_status",
        "dossier_freeze",
        "freeze_dossier",
        "hearing_open",
        "open_hearing",
        "phase_transition",
        "trusted_business_decision",
    }
)


class EvidenceGraphContractError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class EvidenceAdmissionRequest:
    runtime_mode: Literal["SHADOW"]
    room_graph_command: Mapping[str, Any]
    signed_manifest_payload: bytes
    registry_output_schema_version: str
    graph_lease_fencing_token: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "room_graph_command", deepcopy(dict(self.room_graph_command)))
        object.__setattr__(self, "signed_manifest_payload", bytes(self.signed_manifest_payload))


@dataclass(frozen=True, slots=True)
class ResolvedEvidenceVerificationKey:
    kid: str
    public_key: ec.EllipticCurvePublicKey
    algorithm: Literal["ES256"] = "ES256"
    curve: Literal["P-256"] = "P-256"
    use: Literal["sig"] = "sig"


class EvidenceVerificationKeyResolver(Protocol):
    def resolve(self, signing_key_id: str) -> ResolvedEvidenceVerificationKey: ...


_VERIFIED_ADMISSION_TOKEN = object()


class VerifiedEvidenceAdmission:
    """Opaque result issued only after raw payload and ES256 verification."""

    __slots__ = (
        "_runtime_mode",
        "_room_graph_command",
        "_manifest",
        "_registry_output_schema_version",
        "_graph_lease_fencing_token",
        "_snapshot_payload_sha256",
        "_token",
        "__weakref__",
    )

    def __init__(
        self,
        *,
        runtime_mode: Literal["SHADOW"],
        room_graph_command: JsonObject,
        manifest: JsonObject,
        registry_output_schema_version: str,
        graph_lease_fencing_token: int,
        snapshot_payload_sha256: str,
        _token: object,
    ) -> None:
        if _token is not _VERIFIED_ADMISSION_TOKEN:
            raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_REQUIRED")
        self._runtime_mode = runtime_mode
        self._room_graph_command = deepcopy(room_graph_command)
        self._manifest = deepcopy(manifest)
        self._registry_output_schema_version = registry_output_schema_version
        self._graph_lease_fencing_token = graph_lease_fencing_token
        self._snapshot_payload_sha256 = snapshot_payload_sha256
        self._token = _token

    @property
    def runtime_mode(self) -> Literal["SHADOW"]:
        return self._runtime_mode

    @property
    def room_graph_command(self) -> JsonObject:
        return deepcopy(self._room_graph_command)

    @property
    def manifest(self) -> JsonObject:
        return deepcopy(self._manifest)

    @property
    def registry_output_schema_version(self) -> str:
        return self._registry_output_schema_version

    @property
    def graph_lease_fencing_token(self) -> int:
        return self._graph_lease_fencing_token

    @property
    def snapshot_payload_sha256(self) -> str:
        return self._snapshot_payload_sha256


_VERIFIED_ADMISSIONS: WeakSet[VerifiedEvidenceAdmission] = WeakSet()


class EvidenceAdmissionVerifier:
    def __init__(self, key_resolver: EvidenceVerificationKeyResolver) -> None:
        self._key_resolver = key_resolver

    def verify(self, request: EvidenceAdmissionRequest) -> VerifiedEvidenceAdmission:
        if type(request) is not EvidenceAdmissionRequest:
            raise EvidenceGraphContractError("EVIDENCE_ADMISSION_REQUEST_REQUIRED")
        if request.runtime_mode != "SHADOW":
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
        command = _json_object(request.room_graph_command, "EVIDENCE_COMMAND_INVALID")
        _verify_room_graph_command(command)
        snapshot_ref = _required_mapping(command, "domain_snapshot_ref")
        payload = bytes(request.signed_manifest_payload)
        payload_hash = _verify_raw_snapshot_reference(snapshot_ref, payload)
        manifest = _parse_canonical_manifest(payload)
        _verify_snapshot_identity(snapshot_ref, manifest)
        if _contains_forbidden_authority(command) or _contains_forbidden_authority(manifest):
            raise EvidenceGraphContractError("EVIDENCE_FORMAL_AUTHORITY_FORBIDDEN")
        _verify_internal_manifest_hash(manifest)
        _verify_direct_java_signature(manifest, self._key_resolver)
        actor_scope_hash = _verify_actor_scope(command, manifest)
        _verify_output_pins(
            request.registry_output_schema_version,
            command,
            manifest,
        )
        _verify_distinct_fence_authorities(
            request.graph_lease_fencing_token,
            manifest,
        )
        _verify_command_manifest_bindings(command, manifest, actor_scope_hash)
        _verify_synthetic_shadow_scope(manifest)
        _verify_manifest_membership(manifest)
        admission = VerifiedEvidenceAdmission(
            runtime_mode=request.runtime_mode,
            room_graph_command=command,
            manifest=manifest,
            registry_output_schema_version=request.registry_output_schema_version,
            graph_lease_fencing_token=request.graph_lease_fencing_token,
            snapshot_payload_sha256=payload_hash,
            _token=_VERIFIED_ADMISSION_TOKEN,
        )
        _VERIFIED_ADMISSIONS.add(admission)
        return admission


@dataclass(frozen=True, slots=True)
class EvidenceGraphContext:
    admission: VerifiedEvidenceAdmission
    completed_at: str


def validate_verified_admission(
    admission: VerifiedEvidenceAdmission,
) -> tuple[JsonObject, JsonObject]:
    """Consume only an opaque result minted by ``EvidenceAdmissionVerifier``."""

    if (
        type(admission) is not VerifiedEvidenceAdmission
        or admission not in _VERIFIED_ADMISSIONS
        or admission._token is not _VERIFIED_ADMISSION_TOKEN
    ):
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_REQUIRED")
    return admission.room_graph_command, admission.manifest


def manifest_items_by_key(manifest: Mapping[str, Any]) -> dict[str, JsonObject]:
    items = cast(list[Mapping[str, Any]], manifest["items"])
    return {
        cast(str, item["evidence_id"]): _json_object(item, "EVIDENCE_ITEM_INVALID")
        for item in items
    }


def _verify_room_graph_command(command: JsonObject) -> None:
    if command.get("schema_version") != "room-graph-command.v1":
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_SCHEMA_INVALID")
    if command.get("room_type") != "EVIDENCE":
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_ROOM_TYPE_INVALID")
    if command.get("graph_key") != EVIDENCE_GRAPH_KEY:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_GRAPH_KEY_INVALID")
    if command.get("graph_version") != EVIDENCE_GRAPH_VERSION:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_GRAPH_VERSION_INVALID")
    if "fencing_token" in command:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_ROOM_FENCE_FORBIDDEN")
    request_hash = command.get("request_hash")
    if not isinstance(request_hash, str) or not _SHA256.fullmatch(request_hash):
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_REQUEST_HASH_INVALID")
    preimage = dict(command)
    del preimage["request_hash"]
    if canonical_sha256(preimage) != request_hash:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_REQUEST_HASH_MISMATCH")


def _verify_raw_snapshot_reference(snapshot: Mapping[str, Any], payload: bytes) -> str:
    full_hash = hashlib.sha256(payload).hexdigest()
    if snapshot.get("sha256") != full_hash:
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_PAYLOAD_HASH_MISMATCH")
    if snapshot.get("size_bytes") != len(payload):
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_PAYLOAD_SIZE_MISMATCH")
    uri = snapshot.get("uri")
    match = _CONTENT_ADDRESSED_URI.fullmatch(uri) if isinstance(uri, str) else None
    if match is None or match.group(1) != full_hash:
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_URI_NOT_CONTENT_ADDRESSED")
    return full_hash


def _parse_canonical_manifest(payload: bytes) -> JsonObject:
    try:
        decoded = payload.decode("utf-8")
        parsed = json.loads(decoded, object_pairs_hook=_unique_json_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_PAYLOAD_INVALID") from error
    if not isinstance(parsed, dict):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_INVALID")
    manifest = _json_object(parsed, "EVIDENCE_MANIFEST_INVALID")
    if payload != canonicalize(manifest):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_PAYLOAD_NOT_CANONICAL")
    return manifest


def _verify_snapshot_identity(snapshot: Mapping[str, Any], manifest: JsonObject) -> None:
    if snapshot.get("artifact_id") != manifest.get("manifest_id") or snapshot.get(
        "schema_version"
    ) != manifest.get("schema_version"):
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_IDENTITY_MISMATCH")


def _verify_internal_manifest_hash(manifest: JsonObject) -> None:
    manifest_hash = manifest.get("manifest_hash")
    if not isinstance(manifest_hash, str) or not _SHA256.fullmatch(manifest_hash):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_HASH_INVALID")
    preimage = dict(manifest)
    if "signature" not in preimage:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_SIGNATURE_MISSING")
    del preimage["manifest_hash"]
    del preimage["signature"]
    if canonical_sha256(preimage) != manifest_hash:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_HASH_MISMATCH")


def _verify_direct_java_signature(
    manifest: JsonObject,
    key_resolver: EvidenceVerificationKeyResolver,
) -> None:
    signature = manifest.get("signature")
    if (
        manifest.get("signature_algorithm") != "ES256"
        or not isinstance(signature, str)
        or not _P1363_BASE64URL.fullmatch(signature)
    ):
        raise EvidenceGraphContractError("EVIDENCE_DIRECT_JAVA_SIGNATURE_UNVERIFIED")
    signing_key_id = manifest.get("signing_key_id")
    manifest_hash = manifest.get("manifest_hash")
    if (
        not isinstance(signing_key_id, str)
        or not signing_key_id
        or not isinstance(manifest_hash, str)
    ):
        raise EvidenceGraphContractError("EVIDENCE_DIRECT_JAVA_SIGNATURE_UNVERIFIED")
    try:
        resolved = key_resolver.resolve(signing_key_id)
    except Exception as error:
        raise EvidenceGraphContractError("EVIDENCE_SIGNING_KEY_UNAVAILABLE") from error
    if (
        type(resolved) is not ResolvedEvidenceVerificationKey
        or resolved.kid != signing_key_id
        or resolved.algorithm != "ES256"
        or resolved.curve != "P-256"
        or resolved.use != "sig"
        or not isinstance(resolved.public_key, ec.EllipticCurvePublicKey)
        or not isinstance(resolved.public_key.curve, ec.SECP256R1)
    ):
        raise EvidenceGraphContractError("EVIDENCE_SIGNING_KEY_INVALID")
    try:
        raw_signature = base64.urlsafe_b64decode(signature + "==")
    except (ValueError, TypeError) as error:
        raise EvidenceGraphContractError("EVIDENCE_SIGNATURE_ENCODING_INVALID") from error
    if len(raw_signature) != 64:
        raise EvidenceGraphContractError("EVIDENCE_SIGNATURE_ENCODING_INVALID")
    r = int.from_bytes(raw_signature[:32], "big")
    s = int.from_bytes(raw_signature[32:], "big")
    try:
        resolved.public_key.verify(
            encode_dss_signature(r, s),
            manifest_hash.encode("ascii"),
            ec.ECDSA(hashes.SHA256()),
        )
    except (InvalidSignature, ValueError) as error:
        raise EvidenceGraphContractError("EVIDENCE_DIRECT_JAVA_SIGNATURE_INVALID") from error


def _verify_actor_scope(command: JsonObject, manifest: JsonObject) -> str:
    actor_scope = _required_mapping(command, "actor_scope")
    actor_scope_hash = canonical_sha256(actor_scope)
    if manifest.get("actor_scope_hash") != actor_scope_hash:
        raise EvidenceGraphContractError("EVIDENCE_ACTOR_SCOPE_HASH_MISMATCH")
    return actor_scope_hash


def _verify_output_pins(
    registry_output_schema_version: str,
    command: JsonObject,
    manifest: JsonObject,
) -> None:
    invocation = _required_mapping(command, "invocation_context")
    profiles = _required_mapping(manifest, "profile_versions")
    terminal_pin = profiles.get("terminal_output_schema_version")
    assessment_pin = profiles.get("assessment_output_schema_version")
    if (
        terminal_pin != TERMINAL_OUTPUT_SCHEMA_VERSION
        or invocation.get("output_schema_version") != TERMINAL_OUTPUT_SCHEMA_VERSION
        or registry_output_schema_version != TERMINAL_OUTPUT_SCHEMA_VERSION
    ):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_OUTPUT_PIN_MISMATCH")
    if assessment_pin != ASSESSMENT_OUTPUT_SCHEMA_VERSION:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_OUTPUT_PIN_MISMATCH")


def _verify_distinct_fence_authorities(
    graph_lease_fence: int,
    manifest: JsonObject,
) -> None:
    java_room_fence = manifest.get("fencing_token")
    if not _is_nonnegative_int(java_room_fence) or not _is_nonnegative_int(graph_lease_fence):
        raise EvidenceGraphContractError("EVIDENCE_FENCE_BINDING_INVALID")
    # Numeric equality is allowed by chance; the named sources remain independent.
    if "graph_lease_fencing_token" in manifest:
        raise EvidenceGraphContractError("EVIDENCE_FENCE_AUTHORITIES_CONFLATED")


def _verify_command_manifest_bindings(
    command: JsonObject,
    manifest: JsonObject,
    actor_scope_hash: str,
) -> None:
    command_binding = _required_mapping(manifest, "command_binding")
    for name in ("command_id", "logical_run_id", "attempt_id"):
        if command.get(name) != command_binding.get(name):
            raise EvidenceGraphContractError("EVIDENCE_COMMAND_BINDING_MISMATCH")
    for name in (
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "thread_id",
    ):
        if command.get(name) != manifest.get(name):
            raise EvidenceGraphContractError("EVIDENCE_PRIVATE_BINDING_MISMATCH")
    if command.get("deadline_at") != command_binding.get("deadline_at"):
        raise EvidenceGraphContractError("EVIDENCE_DEADLINE_BINDING_MISMATCH")
    if manifest.get("actor_scope_hash") != actor_scope_hash:
        raise EvidenceGraphContractError("EVIDENCE_ACTOR_SCOPE_HASH_MISMATCH")
    profiles = _required_mapping(manifest, "profile_versions")
    invocation = _required_mapping(command, "invocation_context")
    pin_pairs = {
        "graph_version": command.get("graph_version"),
        "checkpoint_schema_version": command.get("checkpoint_schema_version"),
        "prompt_version": invocation.get("prompt_profile_id"),
        "model_profile_id": invocation.get("model_profile_id"),
        "policy_version": invocation.get("policy_version"),
        "guardrail_version": invocation.get("guardrail_version"),
    }
    if any(profiles.get(name) != value for name, value in pin_pairs.items()):
        raise EvidenceGraphContractError("EVIDENCE_PROFILE_BINDING_MISMATCH")


def _verify_synthetic_shadow_scope(manifest: JsonObject) -> None:
    if (
        manifest.get("execution_scope") != "SIGNED_SYNTHETIC_ONLY"
        or manifest.get("writer_mode") != "SHADOW"
        or manifest.get("formal_sink_eligible") is not False
        or manifest.get("graph_execution_allowed") is not True
        or not isinstance(manifest.get("synthetic_fixture_id"), str)
    ):
        raise EvidenceGraphContractError("EVIDENCE_SYNTHETIC_SHADOW_SCOPE_REQUIRED")


def _verify_manifest_membership(manifest: JsonObject) -> None:
    item_count = manifest.get("item_count")
    ordered = manifest.get("ordered_item_keys")
    items = manifest.get("items")
    if (
        not _is_nonnegative_int(item_count)
        or cast(int, item_count) < 1
        or cast(int, item_count) > MAX_MANIFEST_ITEMS
        or not isinstance(ordered, list)
        or not isinstance(items, list)
        or len(ordered) != item_count
        or len(items) != item_count
        or not all(isinstance(key, str) and key for key in ordered)
        or ordered != sorted(set(ordered))
    ):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_MEMBERSHIP_INVALID")
    item_keys: list[str] = []
    for item in items:
        if not isinstance(item, dict) or item.get("schema_version") != "evidence-item-manifest.v1":
            raise EvidenceGraphContractError("EVIDENCE_ITEM_INVALID")
        evidence_id = item.get("evidence_id")
        if not isinstance(evidence_id, str) or not evidence_id:
            raise EvidenceGraphContractError("EVIDENCE_ITEM_INVALID")
        item_keys.append(evidence_id)
    if item_keys != ordered:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_ITEM_ORDER_MISMATCH")


def _required_mapping(value: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    member = value.get(name)
    if not isinstance(member, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_REQUIRED_BINDING_MISSING")
    return member


def _json_object(value: Mapping[str, Any], code: str) -> JsonObject:
    if not isinstance(value, Mapping) or not all(isinstance(key, str) for key in value):
        raise EvidenceGraphContractError(code)
    try:
        canonicalize(value)
    except (TypeError, ValueError) as error:
        raise EvidenceGraphContractError(code) from error
    return cast(JsonObject, deepcopy(dict(value)))


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = member
    return value


def _contains_forbidden_authority(value: Any) -> bool:
    if isinstance(value, Mapping):
        return any(
            key in _FORBIDDEN_AUTHORITY_KEYS or _contains_forbidden_authority(member)
            for key, member in value.items()
        )
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return any(_contains_forbidden_authority(member) for member in value)
    return False


def _is_nonnegative_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0
