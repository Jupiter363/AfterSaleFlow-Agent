from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Literal, TypeAlias, cast

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
class VerifiedEvidenceAdmission:
    """Trusted gateway output consumed by the graph; it is never a signing authority."""

    runtime_mode: Literal["SHADOW"]
    room_graph_command: Mapping[str, Any]
    manifest: Mapping[str, Any]
    registry_output_schema_version: str
    graph_lease_fencing_token: int
    validation_steps: tuple[str, ...]
    direct_java_es256_signature_verified: bool


@dataclass(frozen=True, slots=True)
class EvidenceGraphContext:
    admission: VerifiedEvidenceAdmission
    completed_at: str


def validate_verified_admission(
    admission: VerifiedEvidenceAdmission,
) -> tuple[JsonObject, JsonObject]:
    """Recheck immutable bindings in the same order used by the trusted gateway."""

    if type(admission) is not VerifiedEvidenceAdmission:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_REQUIRED")
    if admission.runtime_mode != "SHADOW":
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
    if admission.validation_steps != VERIFIED_ADMISSION_STEPS:
        raise EvidenceGraphContractError("EVIDENCE_ADMISSION_VALIDATION_ORDER_INVALID")
    command = _json_object(admission.room_graph_command, "EVIDENCE_COMMAND_INVALID")
    manifest = _json_object(admission.manifest, "EVIDENCE_MANIFEST_INVALID")
    if _contains_forbidden_authority(command) or _contains_forbidden_authority(manifest):
        raise EvidenceGraphContractError("EVIDENCE_FORMAL_AUTHORITY_FORBIDDEN")

    _verify_room_graph_command(command)
    snapshot_ref = _required_mapping(command, "domain_snapshot_ref")
    _verify_snapshot_reference(snapshot_ref, manifest)
    _verify_internal_manifest_hash(manifest)
    _verify_direct_java_signature_proof(admission, manifest)
    actor_scope_hash = _verify_actor_scope(command, manifest)
    _verify_output_pins(admission, command, manifest)
    _verify_distinct_fence_authorities(admission, manifest)
    _verify_command_manifest_bindings(command, manifest, actor_scope_hash)
    _verify_synthetic_shadow_scope(manifest)
    _verify_manifest_membership(manifest)
    return command, manifest


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


def _verify_snapshot_reference(snapshot: Mapping[str, Any], manifest: JsonObject) -> None:
    full_payload = canonicalize(manifest)
    full_hash = hashlib.sha256(full_payload).hexdigest()
    if snapshot.get("sha256") != full_hash:
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_PAYLOAD_HASH_MISMATCH")
    if snapshot.get("size_bytes") != len(full_payload):
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_PAYLOAD_SIZE_MISMATCH")
    uri = snapshot.get("uri")
    match = _CONTENT_ADDRESSED_URI.fullmatch(uri) if isinstance(uri, str) else None
    if match is None or match.group(1) != full_hash:
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_URI_NOT_CONTENT_ADDRESSED")
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


def _verify_direct_java_signature_proof(
    admission: VerifiedEvidenceAdmission,
    manifest: JsonObject,
) -> None:
    signature = manifest.get("signature")
    if (
        manifest.get("signature_algorithm") != "ES256"
        or not isinstance(signature, str)
        or not _P1363_BASE64URL.fullmatch(signature)
        or not admission.direct_java_es256_signature_verified
    ):
        raise EvidenceGraphContractError("EVIDENCE_DIRECT_JAVA_SIGNATURE_UNVERIFIED")


def _verify_actor_scope(command: JsonObject, manifest: JsonObject) -> str:
    actor_scope = _required_mapping(command, "actor_scope")
    actor_scope_hash = canonical_sha256(actor_scope)
    if manifest.get("actor_scope_hash") != actor_scope_hash:
        raise EvidenceGraphContractError("EVIDENCE_ACTOR_SCOPE_HASH_MISMATCH")
    return actor_scope_hash


def _verify_output_pins(
    admission: VerifiedEvidenceAdmission,
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
        or admission.registry_output_schema_version != TERMINAL_OUTPUT_SCHEMA_VERSION
    ):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_OUTPUT_PIN_MISMATCH")
    if assessment_pin != ASSESSMENT_OUTPUT_SCHEMA_VERSION:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_OUTPUT_PIN_MISMATCH")


def _verify_distinct_fence_authorities(
    admission: VerifiedEvidenceAdmission,
    manifest: JsonObject,
) -> None:
    java_room_fence = manifest.get("fencing_token")
    graph_lease_fence = admission.graph_lease_fencing_token
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
