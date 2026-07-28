from __future__ import annotations

import base64
import hmac
import hashlib
import json
import re
import secrets
from collections.abc import Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
from threading import RLock
from typing import Any, Literal, TypeAlias, cast
from weakref import WeakKeyDictionary

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.security.graph_runtime import (
    GraphSecurityRuntime,
    GraphSecurityRuntimeError,
    _resolve_graph_verification_key,
    _validate_graph_verification_snapshot,
)
from app.security.invocation_envelope import ResolvedVerificationKey


JsonScalar: TypeAlias = str | int | float | bool | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]
EvidenceExecutionScope: TypeAlias = Literal[
    "SIGNED_SYNTHETIC_ONLY",
    "TARGET_E2E_CANDIDATE",
]
EvidenceAdmissionMode: TypeAlias = Literal["SHADOW", "TARGET_E2E_CANDIDATE"]

ASSESSMENT_OUTPUT_SCHEMA_VERSION = "evidence-item-assessment.v1"
TERMINAL_OUTPUT_SCHEMA_VERSION = "evidence-batch-proposal.v1"
EVIDENCE_GRAPH_KEY = "evidence.v2"
EVIDENCE_GRAPH_VERSION = "evidence.v2.0.0"
EVIDENCE_STATE_SCHEMA_VERSION = "evidence-graph-state.v2"
TARGET_E2E_GRAPH_KEY = "all-rooms.target-e2e.v1"
TARGET_E2E_GRAPH_VERSION = "target-e2e-graph.2026-07-27.1"
TARGET_E2E_CHECKPOINT_SCHEMA_VERSION = "target-e2e-checkpoint.v1"
TARGET_E2E_OUTPUT_SCHEMA_VERSION = "target-e2e-room-proposal-source.v1"
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
_KEY_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_P1363_BASE64URL = re.compile(r"^[A-Za-z0-9_-]{86}$")
_CONTENT_ADDRESSED_URI = re.compile(r"^.+/([0-9a-f]{64})\.json$")
_THREAD_ID = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
_EVIDENCE_ID = re.compile(r"^EVIDENCE_[A-Za-z0-9][A-Za-z0-9._:-]{0,118}$")
_TRACEPARENT = re.compile(r"^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$")
_RFC3339_INSTANT = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$"
)
_SYNTHETIC_PARSE_REF = re.compile(r"^urn:synthetic-evidence-parse:[A-Za-z0-9._:/-]{1,470}$")
_TARGET_E2E_OBJECT_REF = re.compile(
    r"^urn:target-e2e:object:[A-Za-z0-9][A-Za-z0-9._:-]{0,127}:([0-9a-f]{64})$"
)
_COMMAND_FIELDS = frozenset(
    {
        "schema_version",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "graph_key",
        "graph_version",
        "checkpoint_schema_version",
        "thread_id",
        "actor_scope",
        "process_revision",
        "stage_code",
        "stage_sequence",
        "domain_snapshot_ref",
        "event_ref",
        "invocation_context",
        "retry_budget",
        "deadline_at",
        "traceparent",
        "request_hash",
    }
)
_COMMAND_REQUIRED_FIELDS = _COMMAND_FIELDS - {"event_ref"}
_ACTOR_SCOPE_FIELDS = frozenset({"actor_id", "actor_role", "audience", "capabilities"})
_SNAPSHOT_FIELDS = frozenset({"artifact_id", "schema_version", "uri", "sha256", "size_bytes"})
_INVOCATION_FIELDS = frozenset(
    {
        "agent_profile_id",
        "prompt_profile_id",
        "model_profile_id",
        "output_schema_version",
        "policy_version",
        "guardrail_version",
        "tool_capabilities",
        "envelope_key_id",
        "envelope_nonce",
    }
)
_RETRY_FIELDS = frozenset(
    {"provider_attempts_remaining", "activity_attempts_remaining", "repairs_remaining"}
)
_MANIFEST_FIELDS = frozenset(
    {
        "schema_version",
        "manifest_id",
        "manifest_hash",
        "execution_scope",
        "writer_mode",
        "formal_sink_eligible",
        "graph_execution_allowed",
        "synthetic_fixture_id",
        "registration_id",
        "tenant_surrogate",
        "case_id",
        "room_id",
        "room_type",
        "room_epoch",
        "fencing_token",
        "thread_id",
        "actor_id",
        "actor_role",
        "participant_id",
        "actor_scope_hash",
        "agent_session_id",
        "command_binding",
        "submission_batch_id",
        "submission_revision",
        "dossier_target_version",
        "profile_versions",
        "issued_at",
        "not_before",
        "expires_at",
        "item_count",
        "ordered_item_keys",
        "items",
        "signature_algorithm",
        "signing_key_id",
        "signature",
    }
)
_MANIFEST_COMMAND_FIELDS = frozenset(
    {
        "schema_version",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "command_type",
        "submitted_at",
        "deadline_at",
    }
)
_PROFILE_FIELDS = frozenset(
    {
        "graph_version",
        "checkpoint_schema_version",
        "state_schema_version",
        "prompt_version",
        "model_profile_id",
        "assessment_output_schema_version",
        "terminal_output_schema_version",
        "policy_version",
        "guardrail_version",
        "tool_policy_version",
    }
)
_ITEM_FIELDS = frozenset(
    {
        "schema_version",
        "evidence_id",
        "item_hash",
        "owner_participant_id",
        "owner_role",
        "visibility",
        "object_ref",
        "immutable_object_version",
        "object_sha256",
        "content_type",
        "byte_size",
        "original_filename",
        "parse_ref",
        "parse_hash",
        "parse_status",
        "privacy_basis",
        "permitted_modalities",
        "formal_evidence_revision",
        "display_order",
    }
)
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


_VERIFIED_ADMISSION_TOKEN = object()
_EVIDENCE_VERIFIER_TOKEN = object()
_ADMISSION_SEAL_KEY = secrets.token_bytes(32)
_EVIDENCE_VERIFIER_SEAL_KEY = secrets.token_bytes(32)
_EVIDENCE_REGISTRY_LOCK = RLock()


class VerifiedEvidenceAdmission:
    """Opaque result issued only after raw payload and ES256 verification."""

    __slots__ = (
        "_runtime_mode",
        "_room_graph_command_payload",
        "_manifest_payload",
        "_registry_output_schema_version",
        "_graph_lease_fencing_token",
        "_snapshot_payload_sha256",
        "_seal",
        "_token",
        "__weakref__",
    )

    def __init__(
        self,
        *,
        runtime_mode: EvidenceAdmissionMode,
        room_graph_command: JsonObject,
        manifest: JsonObject,
        registry_output_schema_version: str,
        graph_lease_fencing_token: int,
        snapshot_payload_sha256: str,
        _token: object,
    ) -> None:
        if _token is not _VERIFIED_ADMISSION_TOKEN:
            raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_REQUIRED")
        command_payload = canonicalize(room_graph_command)
        manifest_payload = canonicalize(manifest)
        object.__setattr__(self, "_runtime_mode", runtime_mode)
        object.__setattr__(self, "_room_graph_command_payload", command_payload)
        object.__setattr__(self, "_manifest_payload", manifest_payload)
        object.__setattr__(
            self,
            "_registry_output_schema_version",
            registry_output_schema_version,
        )
        object.__setattr__(
            self,
            "_graph_lease_fencing_token",
            graph_lease_fencing_token,
        )
        object.__setattr__(self, "_snapshot_payload_sha256", snapshot_payload_sha256)
        object.__setattr__(self, "_token", _token)
        nonce = secrets.token_bytes(32)
        object.__setattr__(self, "_seal", _admission_seal(self, nonce))
        with _EVIDENCE_REGISTRY_LOCK:
            _VERIFIED_ADMISSIONS[self] = nonce

    def __setattr__(self, name: str, value: object) -> None:
        del name, value
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_IMMUTABLE")

    def __copy__(self) -> VerifiedEvidenceAdmission:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_COPY_FORBIDDEN")

    def __deepcopy__(self, memo: dict[int, object]) -> VerifiedEvidenceAdmission:
        del memo
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_COPY_FORBIDDEN")

    def __reduce_ex__(self, protocol: int) -> object:
        del protocol
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_PICKLE_FORBIDDEN")

    @property
    def runtime_mode(self) -> EvidenceAdmissionMode:
        return self._runtime_mode

    @property
    def room_graph_command(self) -> JsonObject:
        return _decode_admission_payload(
            self._room_graph_command_payload,
            "EVIDENCE_COMMAND_INVALID",
        )

    @property
    def manifest(self) -> JsonObject:
        return _decode_admission_payload(
            self._manifest_payload,
            "EVIDENCE_MANIFEST_INVALID",
        )

    @property
    def registry_output_schema_version(self) -> str:
        return self._registry_output_schema_version

    @property
    def graph_lease_fencing_token(self) -> int:
        return self._graph_lease_fencing_token

    @property
    def snapshot_payload_sha256(self) -> str:
        return self._snapshot_payload_sha256


_VERIFIED_ADMISSIONS: WeakKeyDictionary[VerifiedEvidenceAdmission, bytes] = WeakKeyDictionary()


class EvidenceAdmissionVerifier:
    __slots__ = (
        "_security_runtime",
        "_trust_snapshot",
        "_seal",
        "_token",
        "__weakref__",
    )

    def __init__(
        self,
        security_runtime: object = None,
        *,
        trust_snapshot: object = None,
        _token: object = None,
    ) -> None:
        if _token is not _EVIDENCE_VERIFIER_TOKEN:
            raise EvidenceGraphContractError("EVIDENCE_VERIFIER_BOOTSTRAP_REQUIRED")
        try:
            trust_binding = _validate_graph_verification_snapshot(
                security_runtime,
                trust_snapshot,
            )
        except GraphSecurityRuntimeError as error:
            raise EvidenceGraphContractError("EVIDENCE_VERIFIER_TRUST_INVALID") from error
        object.__setattr__(self, "_security_runtime", security_runtime)
        object.__setattr__(self, "_trust_snapshot", trust_snapshot)
        object.__setattr__(self, "_token", _token)
        nonce = secrets.token_bytes(32)
        object.__setattr__(
            self,
            "_seal",
            _evidence_verifier_seal(self, nonce, trust_binding),
        )
        with _EVIDENCE_REGISTRY_LOCK:
            _EVIDENCE_VERIFIERS[self] = nonce

    def __setattr__(self, name: str, value: object) -> None:
        del name, value
        raise EvidenceGraphContractError("EVIDENCE_VERIFIER_IMMUTABLE")

    @classmethod
    def from_security_runtime(
        cls,
        runtime: GraphSecurityRuntime,
    ) -> EvidenceAdmissionVerifier:
        if cls is not EvidenceAdmissionVerifier:
            raise EvidenceGraphContractError("EVIDENCE_VERIFIER_TYPE_INVALID")
        if type(runtime) is not GraphSecurityRuntime:
            raise EvidenceGraphContractError("EVIDENCE_SECURITY_RUNTIME_REQUIRED")
        try:
            snapshot = runtime.capture_verification_snapshot()
        except GraphSecurityRuntimeError as error:
            raise EvidenceGraphContractError("EVIDENCE_SECURITY_RUNTIME_REQUIRED") from error
        return EvidenceAdmissionVerifier(
            runtime,
            trust_snapshot=snapshot,
            _token=_EVIDENCE_VERIFIER_TOKEN,
        )

    def verify(self, request: EvidenceAdmissionRequest) -> VerifiedEvidenceAdmission:
        return self._verify(request, target_candidate=False)

    def _verify_target_candidate(
        self,
        request: EvidenceAdmissionRequest,
    ) -> VerifiedEvidenceAdmission:
        """Package-private entry used after the shared target gateway has admitted a command."""

        return self._verify(request, target_candidate=True)

    def _verify(
        self,
        request: EvidenceAdmissionRequest,
        *,
        target_candidate: bool,
    ) -> VerifiedEvidenceAdmission:
        security_runtime, trust_snapshot = _validate_evidence_verifier(self)
        if type(request) is not EvidenceAdmissionRequest:
            raise EvidenceGraphContractError("EVIDENCE_ADMISSION_REQUEST_REQUIRED")
        if request.runtime_mode != "SHADOW":
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
        command = _json_object(request.room_graph_command, "EVIDENCE_COMMAND_INVALID")
        if _contains_forbidden_authority(command):
            raise EvidenceGraphContractError("EVIDENCE_FORMAL_AUTHORITY_FORBIDDEN")
        _verify_room_graph_command(command, target_candidate=target_candidate)
        snapshot_ref = _required_mapping(command, "domain_snapshot_ref")
        payload = bytes(request.signed_manifest_payload)
        payload_hash = _verify_raw_snapshot_reference(
            snapshot_ref, payload, target_candidate=target_candidate
        )
        manifest = _parse_canonical_manifest(payload)
        _verify_snapshot_identity(snapshot_ref, manifest)
        if _contains_forbidden_authority(manifest):
            raise EvidenceGraphContractError("EVIDENCE_FORMAL_AUTHORITY_FORBIDDEN")
        _verify_manifest_schema_shape(manifest, target_candidate=target_candidate)
        _verify_internal_manifest_hash(manifest)
        _verify_direct_java_signature(
            manifest,
            security_runtime,
            trust_snapshot,
        )
        actor_scope_hash = _verify_actor_scope(command, manifest)
        _verify_output_pins(
            request.registry_output_schema_version,
            command,
            manifest,
            target_candidate=target_candidate,
        )
        _verify_distinct_fence_authorities(
            request.graph_lease_fencing_token,
            manifest,
        )
        _verify_command_manifest_bindings(command, manifest, actor_scope_hash)
        if target_candidate:
            _verify_target_candidate_scope(manifest)
        else:
            _verify_synthetic_shadow_scope(manifest)
        _verify_manifest_membership(manifest, target_candidate=target_candidate)
        admission = VerifiedEvidenceAdmission(
            runtime_mode=("TARGET_E2E_CANDIDATE" if target_candidate else "SHADOW"),
            room_graph_command=command,
            manifest=manifest,
            registry_output_schema_version=request.registry_output_schema_version,
            graph_lease_fencing_token=request.graph_lease_fencing_token,
            snapshot_payload_sha256=payload_hash,
            _token=_VERIFIED_ADMISSION_TOKEN,
        )
        return admission


_EVIDENCE_VERIFIERS: WeakKeyDictionary[EvidenceAdmissionVerifier, bytes] = WeakKeyDictionary()


@dataclass(frozen=True, slots=True)
class EvidenceGraphContext:
    admission: VerifiedEvidenceAdmission
    completed_at: str


def validate_verified_admission(
    admission: VerifiedEvidenceAdmission,
) -> tuple[JsonObject, JsonObject]:
    """Consume only an opaque result minted by ``EvidenceAdmissionVerifier``."""

    if type(admission) is not VerifiedEvidenceAdmission:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_REQUIRED")
    with _EVIDENCE_REGISTRY_LOCK:
        nonce = _VERIFIED_ADMISSIONS.get(admission)
    if nonce is None or admission._token is not _VERIFIED_ADMISSION_TOKEN:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_REQUIRED")
    expected_seal = _admission_seal(admission, nonce)
    if not hmac.compare_digest(admission._seal, expected_seal):
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_SEAL_INVALID")
    if hashlib.sha256(admission._manifest_payload).hexdigest() != (
        admission._snapshot_payload_sha256
    ):
        raise EvidenceGraphContractError("EVIDENCE_VERIFIED_ADMISSION_SEAL_INVALID")
    return admission.room_graph_command, admission.manifest


def manifest_items_by_key(manifest: Mapping[str, Any]) -> dict[str, JsonObject]:
    items = cast(list[Mapping[str, Any]], manifest["items"])
    return {
        cast(str, item["evidence_id"]): _json_object(item, "EVIDENCE_ITEM_INVALID")
        for item in items
    }


def evidence_execution_scope(
    admission: VerifiedEvidenceAdmission,
) -> EvidenceExecutionScope:
    validate_verified_admission(admission)
    if admission.runtime_mode == "TARGET_E2E_CANDIDATE":
        return "TARGET_E2E_CANDIDATE"
    return "SIGNED_SYNTHETIC_ONLY"


def _verify_room_graph_command(
    command: JsonObject,
    *,
    target_candidate: bool,
) -> None:
    _require_exact_fields(
        command,
        allowed=_COMMAND_FIELDS,
        required=_COMMAND_REQUIRED_FIELDS,
        code="EVIDENCE_COMMAND_FIELDS_INVALID",
    )
    if command.get("schema_version") != "room-graph-command.v1":
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_SCHEMA_INVALID")
    if command.get("room_type") != "EVIDENCE":
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_ROOM_TYPE_INVALID")
    expected_graph_key = TARGET_E2E_GRAPH_KEY if target_candidate else EVIDENCE_GRAPH_KEY
    expected_graph_version = (
        TARGET_E2E_GRAPH_VERSION if target_candidate else EVIDENCE_GRAPH_VERSION
    )
    expected_checkpoint = (
        TARGET_E2E_CHECKPOINT_SCHEMA_VERSION if target_candidate else None
    )
    if command.get("graph_key") != expected_graph_key:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_GRAPH_KEY_INVALID")
    if command.get("graph_version") != expected_graph_version:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_GRAPH_VERSION_INVALID")
    if expected_checkpoint is not None and command.get("checkpoint_schema_version") != (
        expected_checkpoint
    ):
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_CHECKPOINT_VERSION_INVALID")
    if "fencing_token" in command:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_ROOM_FENCE_FORBIDDEN")
    for name in (
        "command_id",
        "logical_run_id",
        "attempt_id",
        "tenant_surrogate",
        "case_id",
        "graph_version",
        "checkpoint_schema_version",
        "stage_code",
    ):
        _require_identifier(command.get(name), "EVIDENCE_COMMAND_IDENTIFIER_INVALID")
    if not isinstance(command.get("thread_id"), str) or not _THREAD_ID.fullmatch(
        cast(str, command["thread_id"])
    ):
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_THREAD_INVALID")
    for name in ("room_epoch", "process_revision", "stage_sequence"):
        _require_integer(command.get(name), minimum=0, maximum=9_007_199_254_740_991)
    actor_scope = _required_mapping(command, "actor_scope")
    _require_exact_fields(
        actor_scope,
        allowed=_ACTOR_SCOPE_FIELDS,
        required=_ACTOR_SCOPE_FIELDS,
        code="EVIDENCE_ACTOR_SCOPE_FIELDS_INVALID",
    )
    _require_identifier(actor_scope.get("actor_id"), "EVIDENCE_ACTOR_SCOPE_INVALID")
    if actor_scope.get("actor_role") not in {
        "USER",
        "MERCHANT",
        "PLATFORM_REVIEWER",
        "ADMIN",
        "SYSTEM",
    } or actor_scope.get("audience") not in {
        "USER",
        "MERCHANT",
        "PLATFORM_REVIEWER",
        "SYSTEM",
    }:
        raise EvidenceGraphContractError("EVIDENCE_ACTOR_SCOPE_INVALID")
    _require_identifier_list(
        actor_scope.get("capabilities"),
        maximum=32,
        code="EVIDENCE_ACTOR_SCOPE_INVALID",
    )
    _verify_snapshot_shape(_required_mapping(command, "domain_snapshot_ref"))
    if "event_ref" in command:
        _verify_snapshot_shape(_required_mapping(command, "event_ref"))
    invocation = _required_mapping(command, "invocation_context")
    _require_exact_fields(
        invocation,
        allowed=_INVOCATION_FIELDS,
        required=_INVOCATION_FIELDS,
        code="EVIDENCE_INVOCATION_FIELDS_INVALID",
    )
    for name in _INVOCATION_FIELDS - {"tool_capabilities"}:
        _require_identifier(invocation.get(name), "EVIDENCE_INVOCATION_INVALID")
    _require_identifier_list(
        invocation.get("tool_capabilities"),
        maximum=32,
        code="EVIDENCE_INVOCATION_INVALID",
    )
    retry = _required_mapping(command, "retry_budget")
    _require_exact_fields(
        retry,
        allowed=_RETRY_FIELDS,
        required=_RETRY_FIELDS,
        code="EVIDENCE_RETRY_BUDGET_INVALID",
    )
    for name, maximum in (
        ("provider_attempts_remaining", 2),
        ("activity_attempts_remaining", 3),
        ("repairs_remaining", 1),
    ):
        _require_integer(retry.get(name), minimum=0, maximum=maximum)
    _require_instant(command.get("deadline_at"), "EVIDENCE_COMMAND_DEADLINE_INVALID")
    if not isinstance(command.get("traceparent"), str) or not _TRACEPARENT.fullmatch(
        cast(str, command["traceparent"])
    ):
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_TRACEPARENT_INVALID")
    request_hash = command.get("request_hash")
    if not isinstance(request_hash, str) or not _SHA256.fullmatch(request_hash):
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_REQUEST_HASH_INVALID")
    preimage = dict(command)
    del preimage["request_hash"]
    if canonical_sha256(preimage) != request_hash:
        raise EvidenceGraphContractError("EVIDENCE_COMMAND_REQUEST_HASH_MISMATCH")


def _verify_raw_snapshot_reference(
    snapshot: Mapping[str, Any],
    payload: bytes,
    *,
    target_candidate: bool,
) -> str:
    full_hash = hashlib.sha256(payload).hexdigest()
    if snapshot.get("sha256") != full_hash:
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_PAYLOAD_HASH_MISMATCH")
    if snapshot.get("size_bytes") != len(payload):
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_PAYLOAD_SIZE_MISMATCH")
    uri = snapshot.get("uri")
    match = _CONTENT_ADDRESSED_URI.fullmatch(uri) if isinstance(uri, str) else None
    target_match = _TARGET_E2E_OBJECT_REF.fullmatch(uri) if isinstance(uri, str) else None
    if (
        match is None
        or match.group(1) != full_hash
    ) and (
        not target_candidate
        or target_match is None
        or target_match.group(1) != full_hash
    ):
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


def _verify_manifest_schema_shape(
    manifest: JsonObject,
    *,
    target_candidate: bool,
) -> None:
    _require_exact_fields(
        manifest,
        allowed=_MANIFEST_FIELDS,
        required=_MANIFEST_FIELDS,
        code="EVIDENCE_MANIFEST_FIELDS_INVALID",
    )
    for name in (
        "manifest_id",
        "synthetic_fixture_id",
        "registration_id",
        "tenant_surrogate",
        "case_id",
        "room_id",
        "actor_id",
        "participant_id",
        "agent_session_id",
        "submission_batch_id",
        "signing_key_id",
    ):
        _require_identifier(manifest.get(name), "EVIDENCE_MANIFEST_IDENTIFIER_INVALID")
    for name in ("manifest_hash", "actor_scope_hash"):
        _require_sha256(manifest.get(name), "EVIDENCE_MANIFEST_HASH_INVALID")
    if not isinstance(manifest.get("thread_id"), str) or not _THREAD_ID.fullmatch(
        cast(str, manifest["thread_id"])
    ):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_THREAD_INVALID")
    if manifest.get("actor_role") not in {"USER", "MERCHANT"}:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_ACTOR_ROLE_INVALID")
    for name, minimum in (
        ("room_epoch", 0),
        ("fencing_token", 1),
        ("submission_revision", 1),
        ("dossier_target_version", 1),
    ):
        _require_integer(
            manifest.get(name),
            minimum=minimum,
            maximum=9_007_199_254_740_991,
        )
    if (
        type(manifest.get("formal_sink_eligible")) is not bool
        or type(manifest.get("graph_execution_allowed")) is not bool
    ):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_BOOLEAN_INVALID")
    issued_at = _require_instant(manifest.get("issued_at"), "EVIDENCE_MANIFEST_TIME_INVALID")
    not_before = _require_instant(
        manifest.get("not_before"),
        "EVIDENCE_MANIFEST_TIME_INVALID",
    )
    expires_at = _require_instant(
        manifest.get("expires_at"),
        "EVIDENCE_MANIFEST_TIME_INVALID",
    )
    if issued_at > not_before or not_before >= expires_at:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_TIME_INVALID")

    command_binding = _required_mapping(manifest, "command_binding")
    _require_exact_fields(
        command_binding,
        allowed=_MANIFEST_COMMAND_FIELDS,
        required=_MANIFEST_COMMAND_FIELDS,
        code="EVIDENCE_MANIFEST_COMMAND_FIELDS_INVALID",
    )
    if (
        command_binding.get("schema_version") != "evidence-room-command.v1"
        or command_binding.get("command_type") != "EVIDENCE_ASSESS_BATCH"
    ):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_COMMAND_INVALID")
    for name in ("command_id", "logical_run_id", "attempt_id"):
        _require_identifier(
            command_binding.get(name),
            "EVIDENCE_MANIFEST_COMMAND_INVALID",
        )
    submitted_at = _require_instant(
        command_binding.get("submitted_at"),
        "EVIDENCE_MANIFEST_COMMAND_INVALID",
    )
    deadline_at = _require_instant(
        command_binding.get("deadline_at"),
        "EVIDENCE_MANIFEST_COMMAND_INVALID",
    )
    if deadline_at <= submitted_at:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_COMMAND_INVALID")

    profiles = _required_mapping(manifest, "profile_versions")
    _require_exact_fields(
        profiles,
        allowed=_PROFILE_FIELDS,
        required=_PROFILE_FIELDS,
        code="EVIDENCE_PROFILE_FIELDS_INVALID",
    )
    for name in _PROFILE_FIELDS:
        _require_identifier(profiles.get(name), "EVIDENCE_PROFILE_INVALID")
    if (
        profiles.get("graph_version")
        != (TARGET_E2E_GRAPH_VERSION if target_candidate else EVIDENCE_GRAPH_VERSION)
        or profiles.get("state_schema_version") != EVIDENCE_STATE_SCHEMA_VERSION
    ):
        raise EvidenceGraphContractError("EVIDENCE_PROFILE_INVALID")

    if (
        manifest.get("schema_version") != "evidence-batch-manifest.v1"
        or manifest.get("room_type") != "EVIDENCE"
        or manifest.get("signature_algorithm") != "ES256"
        or not isinstance(manifest.get("signature"), str)
        or not _P1363_BASE64URL.fullmatch(cast(str, manifest["signature"]))
    ):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_SCHEMA_INVALID")


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
    security_runtime: GraphSecurityRuntime,
    trust_snapshot: object,
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
        resolved = _resolve_graph_verification_key(
            security_runtime,
            trust_snapshot,
            signing_key_id,
        )
    except GraphSecurityRuntimeError as error:
        raise EvidenceGraphContractError("EVIDENCE_SIGNING_KEY_UNAVAILABLE") from error
    if (
        type(resolved) is not ResolvedVerificationKey
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
    *,
    target_candidate: bool,
) -> None:
    invocation = _required_mapping(command, "invocation_context")
    profiles = _required_mapping(manifest, "profile_versions")
    terminal_pin = profiles.get("terminal_output_schema_version")
    assessment_pin = profiles.get("assessment_output_schema_version")
    transport_output = (
        TARGET_E2E_OUTPUT_SCHEMA_VERSION
        if target_candidate
        else TERMINAL_OUTPUT_SCHEMA_VERSION
    )
    if (
        terminal_pin != TERMINAL_OUTPUT_SCHEMA_VERSION
        or invocation.get("output_schema_version") != transport_output
        or registry_output_schema_version != transport_output
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


def _verify_target_candidate_scope(manifest: JsonObject) -> None:
    if (
        manifest.get("execution_scope") != "TARGET_E2E_CANDIDATE"
        or manifest.get("writer_mode") != "PROPOSAL_ONLY"
        or manifest.get("formal_sink_eligible") is not False
        or manifest.get("graph_execution_allowed") is not True
    ):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_E2E_SCOPE_REQUIRED")


def _verify_manifest_membership(manifest: JsonObject, *, target_candidate: bool) -> None:
    item_count = manifest.get("item_count")
    ordered = manifest.get("ordered_item_keys")
    items = manifest.get("items")
    if (
        not _is_nonnegative_int(item_count)
        or cast(int, item_count) < 1
        or cast(int, item_count) > MAX_MANIFEST_ITEMS
        or cast(int, item_count) not in {1, 8, 100}
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
        if not isinstance(item, dict):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_INVALID")
        _require_exact_fields(
            item,
            allowed=_ITEM_FIELDS,
            required=_ITEM_FIELDS,
            code="EVIDENCE_ITEM_FIELDS_INVALID",
        )
        if item.get("schema_version") != "evidence-item-manifest.v1":
            raise EvidenceGraphContractError("EVIDENCE_ITEM_INVALID")
        evidence_id = item.get("evidence_id")
        if not isinstance(evidence_id, str) or not _EVIDENCE_ID.fullmatch(evidence_id):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_INVALID")
        for name in ("owner_participant_id", "immutable_object_version"):
            _require_identifier(item.get(name), "EVIDENCE_ITEM_INVALID")
        for name in ("item_hash", "object_sha256"):
            _require_sha256(item.get(name), "EVIDENCE_ITEM_INVALID")
        item_preimage = dict(item)
        item_hash = cast(str, item_preimage.pop("item_hash"))
        if not hmac.compare_digest(item_hash, canonical_sha256(item_preimage)):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_HASH_MISMATCH")
        if item.get("owner_role") not in {"USER", "MERCHANT"} or item.get("visibility") not in {
            "PRIVATE",
            "PARTIES",
            "PLATFORM_REVIEWER",
        }:
            raise EvidenceGraphContractError("EVIDENCE_ITEM_ACCESS_SCOPE_INVALID")
        object_ref = item.get("object_ref")
        if (
            not isinstance(object_ref, str)
            or len(object_ref) > 512
            or not object_ref.startswith("urn:synthetic-evidence:")
        ):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_OBJECT_REF_INVALID")
        if item.get("content_type") not in {
            "application/pdf",
            "image/jpeg",
            "image/png",
            "text/plain",
        }:
            raise EvidenceGraphContractError("EVIDENCE_ITEM_CONTENT_TYPE_INVALID")
        _require_integer(item.get("byte_size"), minimum=1, maximum=10_485_760)
        filename = item.get("original_filename")
        if (
            not isinstance(filename, str)
            or not filename
            or len(filename) > 255
            or any(character in filename for character in ("/", "\\", "\x00"))
            or any(ord(character) < 32 for character in filename)
        ):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_FILENAME_INVALID")
        _verify_item_parse_binding(item, target_candidate=target_candidate)
        if item.get("privacy_basis") != "SIGNED_SYNTHETIC_FIXTURE":
            raise EvidenceGraphContractError("EVIDENCE_ITEM_PRIVACY_BASIS_INVALID")
        modalities = item.get("permitted_modalities")
        if (
            not isinstance(modalities, list)
            or not 1 <= len(modalities) <= 4
            or not all(isinstance(modality, str) for modality in modalities)
            or len(modalities) != len(set(cast(list[str], modalities)))
            or not set(cast(list[str], modalities))
            <= {"TEXT", "IMAGE_PIXELS", "PDF_METADATA", "OCR"}
        ):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_MODALITIES_INVALID")
        _require_integer(
            item.get("formal_evidence_revision"),
            minimum=1,
            maximum=9_007_199_254_740_991,
        )
        _require_integer(
            item.get("display_order"),
            minimum=0,
            maximum=9_007_199_254_740_991,
        )
        item_keys.append(evidence_id)
    if item_keys != ordered:
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_ITEM_ORDER_MISMATCH")


def _require_exact_fields(
    value: Mapping[str, Any],
    *,
    allowed: frozenset[str],
    required: frozenset[str],
    code: str,
) -> None:
    fields = frozenset(value)
    if not required <= fields or not fields <= allowed:
        raise EvidenceGraphContractError(code)


def _require_identifier(value: Any, code: str) -> str:
    if not isinstance(value, str) or not _KEY_ID.fullmatch(value):
        raise EvidenceGraphContractError(code)
    return value


def _require_sha256(value: Any, code: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise EvidenceGraphContractError(code)
    return value


def _require_integer(value: Any, *, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise EvidenceGraphContractError("EVIDENCE_INTEGER_INVALID")
    return value


def _require_identifier_list(value: Any, *, maximum: int, code: str) -> list[str]:
    if not isinstance(value, list) or len(value) > maximum:
        raise EvidenceGraphContractError(code)
    if any(not isinstance(member, str) or not _KEY_ID.fullmatch(member) for member in value):
        raise EvidenceGraphContractError(code)
    if len(value) != len(set(cast(list[str], value))):
        raise EvidenceGraphContractError(code)
    return cast(list[str], value)


def _require_instant(value: Any, code: str) -> datetime:
    if not isinstance(value, str) or not _RFC3339_INSTANT.fullmatch(value):
        raise EvidenceGraphContractError(code)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise EvidenceGraphContractError(code) from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise EvidenceGraphContractError(code)
    return parsed


def _verify_snapshot_shape(snapshot: Mapping[str, Any]) -> None:
    _require_exact_fields(
        snapshot,
        allowed=_SNAPSHOT_FIELDS,
        required=_SNAPSHOT_FIELDS,
        code="EVIDENCE_SNAPSHOT_FIELDS_INVALID",
    )
    _require_identifier(snapshot.get("artifact_id"), "EVIDENCE_SNAPSHOT_INVALID")
    _require_identifier(snapshot.get("schema_version"), "EVIDENCE_SNAPSHOT_INVALID")
    uri = snapshot.get("uri")
    if (
        not isinstance(uri, str)
        or not 1 <= len(uri) <= 1024
        or not uri.startswith(("s3:", "minio:", "urn:"))
        or len(uri.split(":", 1)[1]) == 0
        or any(character.isspace() for character in uri)
    ):
        raise EvidenceGraphContractError("EVIDENCE_SNAPSHOT_INVALID")
    _require_sha256(snapshot.get("sha256"), "EVIDENCE_SNAPSHOT_INVALID")
    _require_integer(snapshot.get("size_bytes"), minimum=0, maximum=1_073_741_824)


def _verify_item_parse_binding(item: Mapping[str, Any], *, target_candidate: bool) -> None:
    status = item.get("parse_status")
    parse_ref = item.get("parse_ref")
    parse_hash = item.get("parse_hash")
    if status == "AVAILABLE":
        target_match = (
            _TARGET_E2E_OBJECT_REF.fullmatch(parse_ref)
            if isinstance(parse_ref, str)
            else None
        )
        if (
            not isinstance(parse_ref, str)
            or len(parse_ref) > 512
            or not (
                _SYNTHETIC_PARSE_REF.fullmatch(parse_ref)
                if not target_candidate
                else _TARGET_E2E_OBJECT_REF.fullmatch(parse_ref)
            )
        ):
            raise EvidenceGraphContractError("EVIDENCE_ITEM_PARSE_BINDING_INVALID")
        _require_sha256(parse_hash, "EVIDENCE_ITEM_PARSE_BINDING_INVALID")
        if target_candidate and target_match is not None and target_match.group(1) != parse_hash:
            raise EvidenceGraphContractError("EVIDENCE_ITEM_PARSE_BINDING_INVALID")
        return
    if status in {"NOT_REQUESTED", "FAILED"} and parse_ref is None and parse_hash is None:
        return
    raise EvidenceGraphContractError("EVIDENCE_ITEM_PARSE_BINDING_INVALID")


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


def _decode_admission_payload(payload: bytes, code: str) -> JsonObject:
    try:
        value = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_json_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise EvidenceGraphContractError(code) from error
    if not isinstance(value, dict) or payload != canonicalize(value):
        raise EvidenceGraphContractError(code)
    return _json_object(value, code)


def _validate_evidence_verifier(
    verifier: object,
) -> tuple[GraphSecurityRuntime, object]:
    if type(verifier) is not EvidenceAdmissionVerifier:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIER_INVALID")
    with _EVIDENCE_REGISTRY_LOCK:
        nonce = _EVIDENCE_VERIFIERS.get(verifier)
    if nonce is None or verifier._token is not _EVIDENCE_VERIFIER_TOKEN:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIER_INVALID")
    try:
        trust_binding = _validate_graph_verification_snapshot(
            verifier._security_runtime,
            verifier._trust_snapshot,
        )
    except GraphSecurityRuntimeError as error:
        raise EvidenceGraphContractError("EVIDENCE_VERIFIER_TRUST_INVALID") from error
    expected = _evidence_verifier_seal(verifier, nonce, trust_binding)
    if not hmac.compare_digest(verifier._seal, expected):
        raise EvidenceGraphContractError("EVIDENCE_VERIFIER_INVALID")
    return verifier._security_runtime, verifier._trust_snapshot


def _evidence_verifier_seal(
    verifier: EvidenceAdmissionVerifier,
    nonce: bytes,
    trust_binding: str,
) -> str:
    preimage = canonicalize(
        {
            "schema_version": "evidence-admission-verifier-seal.v1",
            "registry_nonce": nonce.hex(),
            "verifier_identity": id(verifier),
            "security_runtime_identity": id(verifier._security_runtime),
            "trust_snapshot_identity": id(verifier._trust_snapshot),
            "trust_binding": trust_binding,
        }
    )
    return hmac.new(_EVIDENCE_VERIFIER_SEAL_KEY, preimage, hashlib.sha256).hexdigest()


def _admission_seal(admission: VerifiedEvidenceAdmission, nonce: bytes) -> str:
    preimage = canonicalize(
        {
            "schema_version": "evidence-verified-admission-seal.v1",
            "registry_nonce": nonce.hex(),
            "admission_identity": id(admission),
            "runtime_mode": admission._runtime_mode,
            "room_graph_command_sha256": hashlib.sha256(
                admission._room_graph_command_payload
            ).hexdigest(),
            "manifest_payload_sha256": hashlib.sha256(admission._manifest_payload).hexdigest(),
            "registry_output_schema_version": admission._registry_output_schema_version,
            "graph_lease_fencing_token": admission._graph_lease_fencing_token,
            "snapshot_payload_sha256": admission._snapshot_payload_sha256,
        }
    )
    return hmac.new(_ADMISSION_SEAL_KEY, preimage, hashlib.sha256).hexdigest()


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
