from __future__ import annotations

import base64
import binascii
import copy
import hashlib
import re
import threading
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Sequence

from .evidence_schema import (
    ALLOWED_SIGNATURE_ALGORITHMS,
    EvidenceValidationError,
    canonical_json_bytes,
    canonical_sha256,
    decode_signature,
    parse_bounded_json_bytes,
    parse_rfc3339,
    public_key_fingerprint_sha256,
    verify_detached_signature,
)


RECEIPT_SCHEMA_VERSION = "phase8-external-control-receipt.v1"
CONTROL_EVIDENCE_SCHEMA_VERSION = "phase8-external-control-evidence.v1"
ATTEMPT_SCOPE_SCHEMA_VERSION = "phase8-external-attempt-scope.v1"
CERTIFICATE_SCHEMA_VERSION = "phase8-external-signing-certificate.v1"
SIGNER_AUTHORIZATION_SCHEMA_VERSION = "phase8-external-signer-authorization.v1"
OPERATOR_AUTHORIZATION_SCHEMA_VERSION = "phase8-external-operator-authorization.v1"
REVOCATION_SNAPSHOT_SCHEMA_VERSION = "phase8-caller-bound-revocation-snapshot.v1"
REVOCATION_SNAPSHOT_SOURCE = "CALLER_BOUND_UNVERIFIED"
PENDING_ARTIFACT_SCHEMA_VERSION = "phase8-external-gate-validated-pending.v1"
ARTIFACT_SELF_SEAL_PURPOSE = "LOCAL_CANONICAL_SELF_SEAL_NOT_AUTHORITY"
EVIDENCE_SOURCE = "CALLER_SIGNED_EXTERNAL_OBSERVATION_ASSERTION"
AUTHORIZATION_SCOPE = "PHASE8_EXTERNAL_SECURITY_PREFLIGHT"
SCENARIO_ID = "SECURITY_AND_ROTATION"
STEP_ID = "EXTERNAL_SECURITY_PREFLIGHT"
CHECKPOINT_ORDER = 3
EXTERNAL_AUTHORITY_CEILING = "EXTERNAL_EVIDENCE_SHAPE_ONLY_UNVERIFIED"
REPLAY_DURABILITY = "PROCESS_MEMORY_FIXTURE"
TRUST_ROOT_PROVENANCE = "CALLER_BOUND_PIN_CONSISTENCY_ONLY"
REVOCATION_STATUS = "UNVERIFIED_PENDING_EXTERNAL"
EVALUATION_TIME_PROVENANCE = "CALLER_SUPPLIED_UNVERIFIED"
CONTROL_OBSERVATION_PROVENANCE = "UNVERIFIED_PENDING_EXTERNAL"
MAX_RECEIPT_PAYLOAD_BYTES = 64 * 1024
MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES = 256 * 1024
MAX_EXTERNAL_INTAKE_BYTES = 4 * 1024 * 1024
MAX_JSON_NESTING_DEPTH = 32
MAX_JSON_TOKENS_PER_PAYLOAD = 20_000
MAX_JSON_STRING_BYTES = 64 * 1024
MAX_CANDIDATE_PATH_BLOBS = 4096
MAX_IMAGES = 128
MAX_DEPLOYMENT_INVENTORY_ITEMS = 4096
MAX_TRUST_ROOTS = 7
MAX_PUBLIC_KEY_PEM_BYTES = 16 * 1024
MAX_OPERATOR_AUTHORIZATIONS = 10
MAX_REVOCATION_ENTRIES = 4096
MAX_PENDING_ARTIFACT_BYTES = 16 * 1024 * 1024
MAX_PENDING_ARTIFACT_DEPTH = 48
MAX_PENDING_ARTIFACT_TOKENS = 500_000
MAX_PENDING_ARTIFACT_STRING_BYTES = 512 * 1024
MAX_PENDING_ARTIFACT_OBJECTS = 500_000
PENDING_ARTIFACT_FIELDS = frozenset(
    {
        "MIG-006",
        "MIG-007",
        "MIG-008",
        "accepted",
        "anti_replay_consumption",
        "authority_ceiling",
        "classification",
        "control_evidence_bindings",
        "control_observation_provenance",
        "cryptographic_shape_validated",
        "evaluation_time_provenance",
        "external_authenticity_verified",
        "external_security_preflight",
        "freshness_status",
        "local_integrity_is_authenticity",
        "production_checkpoint",
        "promotion_gate",
        "receipt_bindings",
        "receipt_set_preimage",
        "receipt_set_sha256",
        "replay_durability",
        "retry_allowed",
        "revocation_snapshot_sha256",
        "revocation_status",
        "schema_version",
        "self_seal_purpose",
        "status",
        "trust_anchor_set_sha256",
        "trust_chain_artifact",
        "trust_root_provenance",
        "validated_context",
        "validation_policy",
        "validation_artifact_sha256",
    }
)
CONTROL_ARTIFACT_BINDING_FIELDS = frozenset(
    {
        "canonical_bundle",
        "canonical_bundle_sha256",
        "control_id",
        "evidence_sha256",
        "nonce",
        "rollback_evidence_sha256",
        "source_payload_base64",
        "source_payload_sha256",
        "step_evidence_sha256",
        "stop_evidence_sha256",
    }
)
RECEIPT_ARTIFACT_BINDING_FIELDS = frozenset(
    {
        "canonical_signed_payload",
        "certificate_fingerprint_sha256",
        "certificate_serial",
        "control_id",
        "observed_at",
        "operator_authorization_reference",
        "operator_identity",
        "operator_trust_root_id",
        "receipt_sha256",
        "signature",
        "signed_payload_sha256",
        "signer_authorization_reference",
        "signer_identity",
        "signer_role",
        "signing_key_id",
        "source_payload_base64",
        "source_payload_sha256",
        "trust_root_id",
    }
)
REQUIRED_CONTROL_IDS = (
    "TEMPORAL_CLOUD_TLS_OR_MTLS_CREDENTIAL_ADAPTER_ACCEPTED",
    "TRUSTED_PROXY_OR_DIRECT_MTLS_ASGI_IDENTITY_BRIDGE_ACCEPTED",
    "REPORTING_READ_REPLICA_ROUTING_ACCEPTED",
    "OBJECT_STORE_WORKLOAD_IDENTITY_PROVIDER_CHAIN_ACCEPTED",
    "LANGFUSE_IDENTITY_PROMPT_OUTPUT_REDACTION_ACCEPTED",
    "ISTIO_SECURITY_IO_V1_CRD_READINESS_ACCEPTED",
    "ISTIO_DATAPLANE_INTERCEPTION_ACCEPTED",
    "ISTIO_STRICT_MTLS_ENFORCEMENT_ACCEPTED",
    "ISTIO_AUTHORIZATION_POLICY_ENFORCEMENT_ACCEPTED",
    "I3_I4_OTEL_NAMESPACE_LABEL_SERVICE_ACCOUNT_AND_PORT_BINDING_ACCEPTED",
)
RUNTIME_BLOCKER_CONTROLS = frozenset(REQUIRED_CONTROL_IDS[:5])
ISTIO_CRD_CONTROL = REQUIRED_CONTROL_IDS[5]
ISTIO_DATAPLANE_CONTROL = REQUIRED_CONTROL_IDS[6]
ISTIO_MTLS_CONTROL = REQUIRED_CONTROL_IDS[7]
ISTIO_AUTHZ_CONTROL = REQUIRED_CONTROL_IDS[8]
OTEL_BINDING_CONTROL = REQUIRED_CONTROL_IDS[9]
SIGNER_ROLE_ORDER = ("ARCHITECTURE", "JAVA", "PYTHON", "SRE", "SECURITY", "BUSINESS")
SIGNER_ROLES = frozenset(SIGNER_ROLE_ORDER)

# These tuples intentionally match security-and-rotation.yaml lines 101-102 exactly.
FROZEN_RECEIPT_REQUIRED_FIELDS = (
    "schema_version",
    "control_id",
    "scenario_id",
    "step_id",
    "checkpoint_order",
    "claimed_result",
    "status",
    "candidate_sha",
    "candidate_tree_sha",
    "configuration_sha256",
    "context_id",
    "context_sha256",
    "environment_identity",
    "deployment_manifest_sha256",
    "images",
    "attempt_id",
    "attempt_number",
    "checkpoint_id",
    "previous_attempt_id",
    "operator_identity",
    "authorization_reference",
    "signer_identity",
    "signer_role",
    "signature_algorithm",
    "signing_key_id",
    "trust_root_id",
    "observed_at",
    "step_evidence_sha256",
    "stop_condition_id",
    "stop_evidence_sha256",
    "rollback_disposition",
    "rollback_evidence_sha256",
    "evidence_sha256",
    "signed_payload_sha256",
    "signature",
    "receipt_sha256",
)
FROZEN_SIGNED_PAYLOAD_FIELDS = (
    "schema_version",
    "control_id",
    "scenario_id",
    "step_id",
    "checkpoint_order",
    "claimed_result",
    "status",
    "candidate_sha",
    "candidate_tree_sha",
    "configuration_sha256",
    "context_id",
    "context_sha256",
    "environment_identity",
    "deployment_manifest_sha256",
    "images",
    "attempt_id",
    "attempt_number",
    "checkpoint_id",
    "previous_attempt_id",
    "operator_identity",
    "authorization_reference",
    "signer_identity",
    "signer_role",
    "signature_algorithm",
    "signing_key_id",
    "trust_root_id",
    "observed_at",
    "step_evidence_sha256",
    "stop_condition_id",
    "stop_evidence_sha256",
    "rollback_disposition",
    "rollback_evidence_sha256",
    "evidence_sha256",
)
RECEIPT_FIELDS = frozenset(FROZEN_RECEIPT_REQUIRED_FIELDS)
SIGNED_PAYLOAD_FIELDS = FROZEN_SIGNED_PAYLOAD_FIELDS
CONTROL_EVIDENCE_FIELDS = frozenset(
    {
        "control_id",
        "evidence_manifest",
        "evidence_sha256",
        "metadata",
        "rollback_evidence",
        "rollback_evidence_sha256",
        "schema_version",
        "step_evidence",
        "step_evidence_sha256",
        "stop_evidence",
        "stop_evidence_sha256",
    }
)
CONTROL_METADATA_FIELDS = frozenset(
    {
        "attempt_id",
        "attempt_number",
        "authorization_edges",
        "authorization_reference",
        "authorization_scope",
        "candidate_author_identity",
        "candidate_path_blobs",
        "candidate_path_blobs_sha256",
        "candidate_sha",
        "candidate_tree_sha",
        "checkpoint_id",
        "cluster_id",
        "configuration_sha256",
        "context_id",
        "context_sha256",
        "deployment_generation",
        "deployment_manifest_sha256",
        "deployment_resources",
        "deployment_uid",
        "environment_class",
        "environment_identity",
        "evidence_author_identity",
        "evidence_source",
        "expires_at",
        "generator_identity",
        "images",
        "issued_at",
        "mtls_edges",
        "namespace",
        "nonce",
        "observed_at",
        "operator_identity",
        "previous_attempt_id",
        "region",
        "runner_identity",
        "scenario_id",
        "signer_authorizations",
        "step_id",
        "target_workload_identities",
    }
)
SIGNER_AUTHORIZATION_BINDING_FIELDS = frozenset(
    {
        "authorization_reference",
        "certificate_serial",
        "role",
        "signer_identity",
        "signing_key_id",
        "trust_root_id",
    }
)
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}$")
RELATIVE_PATH = re.compile(r"^(?!/)(?!.*(?:^|/)\.{1,2}(?:/|$))(?!.*[:\\])[ -~]+$")
NONCE = re.compile(r"^[A-Za-z0-9_-]{22,128}$")
FORBIDDEN_ENVIRONMENT_MARKERS = (
    "fixture",
    "synthetic",
    "engineering-local",
    "disposable",
    "localhost",
    "test-only",
)


class ExternalGateError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class CandidatePathBlob:
    path: str
    mode: str
    git_blob_sha: str
    sha256: str
    status: str

    def as_dict(self) -> dict[str, str]:
        return {
            "git_blob_sha": self.git_blob_sha,
            "mode": self.mode,
            "path": self.path,
            "sha256": self.sha256,
            "status": self.status,
        }


@dataclass(frozen=True)
class ImageBinding:
    name: str
    digest: str

    def as_dict(self) -> dict[str, str]:
        return {"digest": self.digest, "name": self.name}


@dataclass(frozen=True)
class PinnedTrustAnchor:
    trust_root_id: str
    algorithm: str
    public_key_pem: bytes
    fingerprint_sha256: str


@dataclass(frozen=True)
class ExpectedExternalContext:
    candidate_sha: str
    candidate_tree_sha: str
    candidate_path_blobs: tuple[CandidatePathBlob, ...]
    candidate_path_blobs_sha256: str
    configuration_sha256: str
    context_id: str
    context_sha256: str
    environment_identity: str
    environment_class: str
    namespace: str
    cluster_id: str
    region: str
    deployment_manifest_sha256: str
    deployment_uid: str
    deployment_generation: int
    target_workload_identities: tuple[str, ...]
    deployment_resources: tuple[str, ...]
    mtls_edges: tuple[str, ...]
    authorization_edges: tuple[str, ...]
    images: tuple[ImageBinding, ...]
    attempt_id: str
    attempt_number: int
    checkpoint_id: str
    previous_attempt_id: str | None
    runner_identity: str
    generator_identity: str
    candidate_author_identity: str
    evidence_author_identity: str
    pinned_trust_anchors: tuple[PinnedTrustAnchor, ...]


@dataclass(frozen=True)
class TrustRoot:
    trust_root_id: str
    algorithm: str
    public_key_pem: bytes
    fingerprint_sha256: str
    not_before: datetime
    not_after: datetime
    revoked: bool = False


@dataclass(frozen=True)
class SigningCertificate:
    certificate_serial: str
    signing_key_id: str
    trust_root_id: str
    algorithm: str
    public_key_pem: bytes
    public_key_fingerprint_sha256: str
    signer_identity: str
    signer_role: str
    not_before: datetime
    not_after: datetime
    issuer_signature: str
    revoked: bool = False


@dataclass(frozen=True)
class OperatorAuthorization:
    authorization_reference: str
    operator_identity: str
    trust_root_id: str
    scope: str
    candidate_sha: str
    configuration_sha256: str
    environment_identity: str
    namespace: str
    cluster_id: str
    region: str
    deployment_manifest_sha256: str
    deployment_uid: str
    attempt_scope_sha256: str
    attempt_id: str
    control_ids: frozenset[str]
    not_before: datetime
    expires_at: datetime
    issuer_signature: str
    revoked: bool = False


@dataclass(frozen=True)
class SignerAuthorization:
    authorization_reference: str
    signer_identity: str
    signer_role: str
    signing_key_id: str
    trust_root_id: str
    scope: str
    candidate_sha: str
    configuration_sha256: str
    environment_identity: str
    namespace: str
    cluster_id: str
    region: str
    deployment_manifest_sha256: str
    deployment_uid: str
    attempt_scope_sha256: str
    attempt_id: str
    control_ids: frozenset[str]
    not_before: datetime
    expires_at: datetime
    issuer_signature: str
    revoked: bool = False


@dataclass(frozen=True)
class RevocationSnapshot:
    schema_version: str
    snapshot_id: str
    source: str
    generated_at: datetime
    expires_at: datetime
    trust_anchor_set_sha256: str
    revoked_root_fingerprints: frozenset[str]
    revoked_certificate_serials: frozenset[str]
    revoked_signing_key_fingerprints: frozenset[str]
    revoked_authorization_references: frozenset[str]
    snapshot_sha256: str


@dataclass(frozen=True)
class ReplayConsumption:
    attempt_id: str
    attempt_scope_sha256: str
    receipt_set_sha256: str
    receipt_hashes: tuple[str, ...]
    nonces: tuple[str, ...]
    sequence: int
    consumed_at: datetime


class ReplayLedger:
    """Thread-safe one-shot memory ledger; it is not an authenticity source."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._claimed_attempt_scopes: dict[str, str] = {}
        self._finalized_attempt_scopes: set[str] = set()
        self._claimed_attempt_ids: set[str] = set()
        self._finalized_attempt_ids: set[str] = set()
        self._receipt_hashes: set[str] = set()
        self._nonces: set[str] = set()
        self._receipt_set_hashes: set[str] = set()
        self._sequence = 0

    def claim_attempt(self, *, attempt_id: str, attempt_scope_sha256: str) -> None:
        attempt_key = _token(attempt_id, "attempt_id").casefold()
        scope_key = _sha(attempt_scope_sha256, "attempt_scope_sha256")
        with self._lock:
            if scope_key in self._claimed_attempt_scopes:
                _fail("ANTI_REPLAY_REJECTED", "checkpoint scope was already claimed")
            if attempt_key in self._claimed_attempt_ids:
                _fail("ANTI_REPLAY_REJECTED", "checkpoint attempt was already claimed")
            self._claimed_attempt_scopes[scope_key] = attempt_key
            self._claimed_attempt_ids.add(attempt_key)

    def consume_once(
        self,
        *,
        attempt_id: str,
        attempt_scope_sha256: str,
        receipt_set_sha256: str,
        receipt_hashes: tuple[str, ...],
        nonces: tuple[str, ...],
        consumed_at: datetime,
    ) -> ReplayConsumption:
        attempt_key = _token(attempt_id, "attempt_id").casefold()
        scope_key = _sha(attempt_scope_sha256, "attempt_scope_sha256")
        with self._lock:
            if (
                self._claimed_attempt_scopes.get(scope_key) != attempt_key
                or scope_key in self._finalized_attempt_scopes
                or attempt_key not in self._claimed_attempt_ids
                or attempt_key in self._finalized_attempt_ids
                or receipt_set_sha256 in self._receipt_set_hashes
                or any(item in self._receipt_hashes for item in receipt_hashes)
                or any(item in self._nonces for item in nonces)
            ):
                _fail(
                    "ANTI_REPLAY_REJECTED",
                    "attempt, receipt set, receipt hash, or nonce was already consumed",
                )
            self._finalized_attempt_scopes.add(scope_key)
            self._finalized_attempt_ids.add(attempt_key)
            self._receipt_set_hashes.add(receipt_set_sha256)
            self._receipt_hashes.update(receipt_hashes)
            self._nonces.update(nonces)
            self._sequence += 1
            return ReplayConsumption(
                attempt_id=attempt_id,
                attempt_scope_sha256=scope_key,
                receipt_set_sha256=receipt_set_sha256,
                receipt_hashes=receipt_hashes,
                nonces=nonces,
                sequence=self._sequence,
                consumed_at=consumed_at,
            )


@dataclass(frozen=True)
class ExternalTrustPolicy:
    trust_roots: tuple[TrustRoot, ...]
    signing_certificates: tuple[SigningCertificate, ...]
    operator_authorizations: tuple[OperatorAuthorization, ...]
    signer_authorizations: tuple[SignerAuthorization, ...]
    allowed_algorithms: frozenset[str]
    max_receipt_age_seconds: int
    revocation_snapshot: RevocationSnapshot
    replay_ledger: ReplayLedger


@dataclass(frozen=True)
class ValidatedControlReceipt:
    control_id: str
    signer_role: str
    signer_identity: str
    operator_identity: str
    operator_authorization_reference: str
    operator_trust_root_id: str
    signer_authorization_reference: str
    signing_key_id: str
    trust_root_id: str
    certificate_serial: str
    certificate_fingerprint_sha256: str
    signed_payload_sha256: str
    signature: str
    canonical_signed_payload: str
    source_payload_base64: str
    source_payload_sha256: str
    receipt_sha256: str
    observed_at: datetime


@dataclass(frozen=True)
class ValidatedControlEvidence:
    control_id: str
    evidence_sha256: str
    step_evidence_sha256: str
    stop_evidence_sha256: str
    rollback_evidence_sha256: str
    nonce: str
    canonical_bundle: str
    canonical_bundle_sha256: str
    source_payload_base64: str
    source_payload_sha256: str


@dataclass(frozen=True)
class ValidatedPendingExternalReceiptSet:
    context: ExpectedExternalContext
    control_evidence: tuple[ValidatedControlEvidence, ...]
    receipts: tuple[ValidatedControlReceipt, ...]
    receipt_set_sha256: str
    replay_consumption: ReplayConsumption
    trust_policy: ExternalTrustPolicy
    trust_anchor_set_sha256: str
    revocation_snapshot_sha256: str
    authority_ceiling: str = EXTERNAL_AUTHORITY_CEILING
    cryptographic_shape_validated: bool = True
    external_authenticity_verified: bool = False
    replay_durability: str = REPLAY_DURABILITY
    trust_root_provenance: str = TRUST_ROOT_PROVENANCE
    revocation_status: str = REVOCATION_STATUS
    evaluation_time_provenance: str = EVALUATION_TIME_PROVENANCE
    control_observation_provenance: str = CONTROL_OBSERVATION_PROVENANCE


def _fail(code: str, message: str) -> None:
    raise ExternalGateError(code, message)


def _exact_keys(value: Any, expected: set[str] | frozenset[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != set(expected):
        _fail("STRICT_SHAPE_REJECTED", f"{context} fields are missing, extra, or unknown")
    return value


def _strict_equal(actual: Any, expected: Any) -> bool:
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, dict):
        return set(actual) == set(expected) and all(
            _strict_equal(actual[key], value) for key, value in expected.items()
        )
    if isinstance(expected, (list, tuple)):
        return len(actual) == len(expected) and all(
            _strict_equal(left, right) for left, right in zip(actual, expected, strict=True)
        )
    return actual == expected


def _token(value: Any, context: str) -> str:
    if not isinstance(value, str) or not TOKEN.fullmatch(value):
        _fail("INVALID_IDENTITY_OR_TOKEN", f"{context} is not a strict opaque token")
    if "@" in value or any(character.isspace() for character in value):
        _fail("PII_OR_ALIAS_REJECTED", f"{context} must be opaque and non-PII")
    return value


def _sha(value: Any, context: str, *, git: bool = False) -> str:
    pattern = SHA1 if git else SHA256
    if not isinstance(value, str) or not pattern.fullmatch(value):
        _fail("INVALID_DIGEST", f"{context} is not an exact lowercase digest")
    return value


def _aware_utc(value: datetime, context: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        _fail("INVALID_TIME", f"{context} must be timezone-aware")
    return value.astimezone(timezone.utc)


def _timestamp(value: Any, context: str) -> datetime:
    try:
        return parse_rfc3339(value, context=context)
    except EvidenceValidationError as exception:
        raise ExternalGateError("INVALID_TIME", str(exception)) from exception


def _format_time(value: datetime) -> str:
    return _aware_utc(value, "signed timestamp").isoformat().replace("+00:00", "Z")


def _valid_at(not_before: datetime, not_after: datetime, *moments: datetime) -> bool:
    start = _aware_utc(not_before, "not_before")
    end = _aware_utc(not_after, "not_after")
    return start < end and all(start <= moment < end for moment in moments)


def certificate_signed_payload(certificate: SigningCertificate) -> dict[str, Any]:
    return {
        "algorithm": certificate.algorithm,
        "certificate_serial": certificate.certificate_serial,
        "not_after": _format_time(certificate.not_after),
        "not_before": _format_time(certificate.not_before),
        "public_key_fingerprint_sha256": certificate.public_key_fingerprint_sha256,
        "schema_version": CERTIFICATE_SCHEMA_VERSION,
        "signer_identity": certificate.signer_identity,
        "signer_role": certificate.signer_role,
        "signing_key_id": certificate.signing_key_id,
        "trust_root_id": certificate.trust_root_id,
    }


def operator_authorization_signed_payload(
    authorization: OperatorAuthorization,
) -> dict[str, Any]:
    return {
        "attempt_id": authorization.attempt_id,
        "attempt_scope_sha256": authorization.attempt_scope_sha256,
        "authorization_reference": authorization.authorization_reference,
        "candidate_sha": authorization.candidate_sha,
        "cluster_id": authorization.cluster_id,
        "configuration_sha256": authorization.configuration_sha256,
        "control_ids": sorted(authorization.control_ids),
        "deployment_manifest_sha256": authorization.deployment_manifest_sha256,
        "deployment_uid": authorization.deployment_uid,
        "environment_identity": authorization.environment_identity,
        "expires_at": _format_time(authorization.expires_at),
        "namespace": authorization.namespace,
        "not_before": _format_time(authorization.not_before),
        "operator_identity": authorization.operator_identity,
        "region": authorization.region,
        "schema_version": OPERATOR_AUTHORIZATION_SCHEMA_VERSION,
        "scope": authorization.scope,
        "trust_root_id": authorization.trust_root_id,
    }


def signer_authorization_signed_payload(
    authorization: SignerAuthorization,
) -> dict[str, Any]:
    return {
        "attempt_id": authorization.attempt_id,
        "attempt_scope_sha256": authorization.attempt_scope_sha256,
        "authorization_reference": authorization.authorization_reference,
        "candidate_sha": authorization.candidate_sha,
        "cluster_id": authorization.cluster_id,
        "configuration_sha256": authorization.configuration_sha256,
        "control_ids": sorted(authorization.control_ids),
        "deployment_manifest_sha256": authorization.deployment_manifest_sha256,
        "deployment_uid": authorization.deployment_uid,
        "environment_identity": authorization.environment_identity,
        "expires_at": _format_time(authorization.expires_at),
        "namespace": authorization.namespace,
        "not_before": _format_time(authorization.not_before),
        "region": authorization.region,
        "schema_version": SIGNER_AUTHORIZATION_SCHEMA_VERSION,
        "scope": authorization.scope,
        "signer_identity": authorization.signer_identity,
        "signer_role": authorization.signer_role,
        "signing_key_id": authorization.signing_key_id,
        "trust_root_id": authorization.trust_root_id,
    }


def trust_anchor_set_payload(
    anchors: Sequence[PinnedTrustAnchor],
) -> list[dict[str, str]]:
    return [
        {
            "algorithm": anchor.algorithm,
            "fingerprint_sha256": anchor.fingerprint_sha256,
            "trust_root_id": anchor.trust_root_id,
        }
        for anchor in anchors
    ]


def trust_anchor_set_sha256(anchors: Sequence[PinnedTrustAnchor]) -> str:
    return canonical_sha256(trust_anchor_set_payload(anchors))


def revocation_snapshot_payload(snapshot: RevocationSnapshot) -> dict[str, Any]:
    return {
        "expires_at": _format_time(snapshot.expires_at),
        "generated_at": _format_time(snapshot.generated_at),
        "revoked_authorization_references": sorted(
            snapshot.revoked_authorization_references
        ),
        "revoked_certificate_serials": sorted(snapshot.revoked_certificate_serials),
        "revoked_root_fingerprints": sorted(snapshot.revoked_root_fingerprints),
        "revoked_signing_key_fingerprints": sorted(
            snapshot.revoked_signing_key_fingerprints
        ),
        "schema_version": snapshot.schema_version,
        "snapshot_id": snapshot.snapshot_id,
        "source": snapshot.source,
        "trust_anchor_set_sha256": snapshot.trust_anchor_set_sha256,
    }


def revocation_snapshot_sha256(snapshot: RevocationSnapshot) -> str:
    return canonical_sha256(revocation_snapshot_payload(snapshot))


def receipt_signed_payload(receipt: Mapping[str, Any]) -> dict[str, Any]:
    return {field: receipt[field] for field in FROZEN_SIGNED_PAYLOAD_FIELDS}


def receipt_sha256(receipt: Mapping[str, Any]) -> str:
    value = dict(receipt)
    value.pop("receipt_sha256", None)
    return canonical_sha256(value)


def _snapshot_expected_context(expected: ExpectedExternalContext) -> ExpectedExternalContext:
    if type(expected) is not ExpectedExternalContext:
        _fail("STRICT_SHAPE_REJECTED", "expected context must be the frozen trusted type")
    tuple_fields = (
        "candidate_path_blobs",
        "target_workload_identities",
        "deployment_resources",
        "mtls_edges",
        "authorization_edges",
        "images",
        "pinned_trust_anchors",
    )
    if any(type(getattr(expected, field)) is not tuple for field in tuple_fields):
        _fail("STRICT_SHAPE_REJECTED", "expected context inventories must be exact tuples")
    if (
        not 1 <= len(expected.candidate_path_blobs) <= MAX_CANDIDATE_PATH_BLOBS
        or not 1 <= len(expected.images) <= MAX_IMAGES
        or not 1 <= len(expected.pinned_trust_anchors) <= MAX_TRUST_ROOTS
        or any(
            not 1 <= len(getattr(expected, field)) <= MAX_DEPLOYMENT_INVENTORY_ITEMS
            for field in (
                "target_workload_identities",
                "deployment_resources",
                "mtls_edges",
                "authorization_edges",
            )
        )
    ):
        _fail("RESOURCE_LIMIT_REJECTED", "expected context inventory exceeds its ceiling")
    if any(type(item) is not CandidatePathBlob for item in expected.candidate_path_blobs):
        _fail("STRICT_SHAPE_REJECTED", "candidate path inventory is not deeply frozen")
    if any(type(item) is not ImageBinding for item in expected.images):
        _fail("STRICT_SHAPE_REJECTED", "image inventory is not deeply frozen")
    if any(type(item) is not PinnedTrustAnchor for item in expected.pinned_trust_anchors):
        _fail("STRICT_SHAPE_REJECTED", "trust anchor pins are not deeply frozen")
    return replace(
        expected,
        candidate_path_blobs=tuple(replace(item) for item in expected.candidate_path_blobs),
        target_workload_identities=tuple(expected.target_workload_identities),
        deployment_resources=tuple(expected.deployment_resources),
        mtls_edges=tuple(expected.mtls_edges),
        authorization_edges=tuple(expected.authorization_edges),
        images=tuple(replace(item) for item in expected.images),
        pinned_trust_anchors=tuple(replace(item) for item in expected.pinned_trust_anchors),
    )


def _expected_context_document(
    expected: ExpectedExternalContext, normalized: Mapping[str, Any]
) -> dict[str, Any]:
    return {
        "attempt_lineage": {
            "attempt_id": expected.attempt_id,
            "attempt_number": expected.attempt_number,
            "checkpoint_id": expected.checkpoint_id,
            "previous_attempt_id": expected.previous_attempt_id,
        },
        "authors": {
            "candidate": expected.candidate_author_identity,
            "evidence": expected.evidence_author_identity,
            "generator": expected.generator_identity,
            "runner": expected.runner_identity,
        },
        "authorization_edges": normalized["authorization_edges"],
        "candidate_path_blobs": normalized["path_blobs"],
        "candidate_path_blobs_sha256": expected.candidate_path_blobs_sha256,
        "candidate_sha": expected.candidate_sha,
        "candidate_tree_sha": expected.candidate_tree_sha,
        "cluster_id": expected.cluster_id,
        "configuration_sha256": expected.configuration_sha256,
        "context_id": expected.context_id,
        "context_sha256": expected.context_sha256,
        "deployment_generation": expected.deployment_generation,
        "deployment_manifest_sha256": expected.deployment_manifest_sha256,
        "deployment_resources": normalized["deployment_resources"],
        "deployment_uid": expected.deployment_uid,
        "environment_class": expected.environment_class,
        "environment_identity": expected.environment_identity,
        "images": normalized["images"],
        "mtls_edges": normalized["mtls_edges"],
        "namespace": expected.namespace,
        "pinned_trust_anchors": normalized["trust_anchor_set"],
        "pinned_trust_anchors_sha256": normalized["trust_anchor_set_sha256"],
        "region": expected.region,
        "target_workload_identities": normalized["target_workload_identities"],
    }


def _attempt_scope_sha256(
    expected: ExpectedExternalContext, normalized: Mapping[str, Any]
) -> str:
    return canonical_sha256(
        {
            "schema_version": ATTEMPT_SCOPE_SCHEMA_VERSION,
            "candidate_sha": expected.candidate_sha,
            "candidate_tree_sha": expected.candidate_tree_sha,
            "candidate_path_blobs": normalized["path_blobs"],
            "candidate_path_blobs_sha256": expected.candidate_path_blobs_sha256,
            "configuration_sha256": expected.configuration_sha256,
            "context_id": expected.context_id,
            "context_sha256": expected.context_sha256,
            "environment_class": expected.environment_class,
            "environment_identity": expected.environment_identity,
            "namespace": expected.namespace,
            "cluster_id": expected.cluster_id,
            "region": expected.region,
            "deployment_manifest_sha256": expected.deployment_manifest_sha256,
            "deployment_uid": expected.deployment_uid,
            "deployment_generation": expected.deployment_generation,
            "deployment_resources": normalized["deployment_resources"],
            "images": normalized["images"],
            "target_workload_identities": normalized["target_workload_identities"],
            "mtls_edges": normalized["mtls_edges"],
            "authorization_edges": normalized["authorization_edges"],
            "checkpoint_id": expected.checkpoint_id,
        }
    )


def calculate_attempt_scope_sha256(expected: ExpectedExternalContext) -> str:
    """Return the canonical one-shot checkpoint scope for a trusted context."""
    snapshot = _snapshot_expected_context(expected)
    normalized = _validate_expected_context(snapshot)
    return _attempt_scope_sha256(snapshot, normalized)


def _validate_expected_context(expected: ExpectedExternalContext) -> dict[str, Any]:
    if type(expected) is not ExpectedExternalContext:
        _fail("STRICT_SHAPE_REJECTED", "expected context must be the frozen trusted type")
    _sha(expected.candidate_sha, "candidate_sha", git=True)
    _sha(expected.candidate_tree_sha, "candidate_tree_sha", git=True)
    _sha(expected.candidate_path_blobs_sha256, "candidate_path_blobs_sha256")
    _sha(expected.configuration_sha256, "configuration_sha256")
    _sha(expected.context_sha256, "context_sha256")
    _sha(expected.deployment_manifest_sha256, "deployment_manifest_sha256")
    for field in (
        "context_id",
        "environment_identity",
        "namespace",
        "cluster_id",
        "region",
        "deployment_uid",
        "attempt_id",
        "checkpoint_id",
        "runner_identity",
        "generator_identity",
        "candidate_author_identity",
        "evidence_author_identity",
    ):
        _token(getattr(expected, field), field)
    production_identity_values = (
        expected.context_id,
        expected.environment_identity,
        expected.namespace,
        expected.cluster_id,
        expected.region,
        expected.deployment_uid,
        expected.checkpoint_id,
    )
    if expected.environment_class != "PRODUCTION" or any(
        marker in value.casefold()
        for value in production_identity_values
        for marker in FORBIDDEN_ENVIRONMENT_MARKERS
    ):
        _fail("NON_PRODUCTION_CONTEXT", "fixture, synthetic, local, or test context rejected")
    if (
        not isinstance(expected.deployment_generation, int)
        or isinstance(expected.deployment_generation, bool)
        or expected.deployment_generation < 1
    ):
        _fail("INVALID_DEPLOYMENT_IDENTITY", "deployment_generation must be positive")
    if (
        not isinstance(expected.attempt_number, int)
        or isinstance(expected.attempt_number, bool)
        or expected.attempt_number != 1
        or expected.previous_attempt_id is not None
    ):
        _fail("ATTEMPT_RETRY_FORBIDDEN", "external checkpoint permits exactly attempt 1")
    authors = (
        expected.runner_identity,
        expected.generator_identity,
        expected.candidate_author_identity,
        expected.evidence_author_identity,
    )
    if len({item.casefold() for item in authors}) != len(authors):
        _fail("SELF_APPROVAL_REJECTED", "runner, generator, candidate, and evidence authors overlap")

    path_blobs = [blob.as_dict() for blob in expected.candidate_path_blobs]
    paths: list[str] = []
    for blob in expected.candidate_path_blobs:
        if (
            not isinstance(blob.path, str)
            or len(blob.path) > 4096
            or not RELATIVE_PATH.fullmatch(blob.path)
            or "\\" in blob.path
            or ":" in blob.path
            or blob.mode not in {"100644", "100755"}
            or blob.status not in {"ADDED", "MODIFIED", "BOUND_UNCHANGED"}
        ):
            _fail("INVALID_CANDIDATE_PATH", "candidate path blob is not canonical")
        _sha(blob.git_blob_sha, f"git blob {blob.path}", git=True)
        _sha(blob.sha256, f"path SHA-256 {blob.path}")
        paths.append(blob.path)
    if not paths or paths != sorted(paths) or len({item.casefold() for item in paths}) != len(paths):
        _fail("INVALID_CANDIDATE_PATH", "candidate path blobs are empty, unsorted, or collide")
    if canonical_sha256(path_blobs) != expected.candidate_path_blobs_sha256:
        _fail("MIXED_RELEASE_CONTEXT", "candidate path blob inventory digest drifted")

    images = [image.as_dict() for image in expected.images]
    image_names: list[str] = []
    for image in expected.images:
        _token(image.name, "image name")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image.digest):
            _fail("INVALID_IMAGE_DIGEST", "image is not pinned to an OCI digest")
        image_names.append(image.name)
    if not images or image_names != sorted(image_names) or len(
        {item.casefold() for item in image_names}
    ) != len(image_names):
        _fail("INVALID_IMAGE_DIGEST", "image inventory is empty, unsorted, or colliding")

    inventories: dict[str, list[str]] = {}
    for field in (
        "target_workload_identities",
        "deployment_resources",
        "mtls_edges",
        "authorization_edges",
    ):
        values = list(getattr(expected, field))
        for item in values:
            _token(item, field)
        if not values or values != sorted(values) or len(
            {item.casefold() for item in values}
        ) != len(values):
            _fail(
                "INVALID_DEPLOYMENT_IDENTITY",
                f"{field} must be a non-empty sorted exact inventory",
            )
        inventories[field] = values
    if any(
        marker in value.casefold()
        for value in (
            *image_names,
            *inventories["target_workload_identities"],
            *inventories["deployment_resources"],
            *inventories["mtls_edges"],
            *inventories["authorization_edges"],
        )
        for marker in FORBIDDEN_ENVIRONMENT_MARKERS
    ):
        _fail("NON_PRODUCTION_CONTEXT", "fixture, synthetic, local, or test asset rejected")
    anchor_ids: list[str] = []
    anchor_fingerprints: list[str] = []
    for anchor in expected.pinned_trust_anchors:
        anchor_ids.append(_token(anchor.trust_root_id, "pinned trust_root_id"))
        if anchor.algorithm not in ALLOWED_SIGNATURE_ALGORITHMS:
            _fail("UNTRUSTED_ALGORITHM", "pinned trust anchor algorithm is unsupported")
        if type(anchor.public_key_pem) is not bytes or not (
            1 <= len(anchor.public_key_pem) <= MAX_PUBLIC_KEY_PEM_BYTES
        ):
            _fail("RESOURCE_LIMIT_REJECTED", "pinned trust anchor PEM exceeds its ceiling")
        anchor_fingerprints.append(
            _sha(anchor.fingerprint_sha256, "pinned trust anchor fingerprint")
        )
    if (
        anchor_ids != sorted(anchor_ids)
        or len({item.casefold() for item in anchor_ids}) != len(anchor_ids)
        or len(set(anchor_fingerprints)) != len(anchor_fingerprints)
    ):
        _fail("UNTRUSTED_ROOT", "pinned trust anchors are unsorted or duplicate")
    anchor_payload = trust_anchor_set_payload(expected.pinned_trust_anchors)
    return {
        "images": images,
        "path_blobs": path_blobs,
        "trust_anchor_set": anchor_payload,
        "trust_anchor_set_sha256": canonical_sha256(anchor_payload),
        **inventories,
    }


def _verify_root_signature(
    root: TrustRoot, payload: Mapping[str, Any], signature: str, context: str
) -> None:
    try:
        verify_detached_signature(
            algorithm=root.algorithm,
            public_key_pem=root.public_key_pem,
            payload=canonical_json_bytes(payload),
            signature=decode_signature(signature, context=context),
        )
    except EvidenceValidationError as exception:
        raise ExternalGateError("UNTRUSTED_AUTHORIZATION", str(exception)) from exception


def _snapshot_policy_shape(policy: ExternalTrustPolicy) -> ExternalTrustPolicy:
    if type(policy) is not ExternalTrustPolicy or type(policy.replay_ledger) is not ReplayLedger:
        _fail("INVALID_TRUST_POLICY", "frozen trust policy and concrete replay ledger required")
    tuple_fields = (
        "trust_roots",
        "signing_certificates",
        "operator_authorizations",
        "signer_authorizations",
    )
    if any(type(getattr(policy, field)) is not tuple for field in tuple_fields):
        _fail("INVALID_TRUST_POLICY", "trust policy collections must be exact tuples")
    if (
        not 1 <= len(policy.trust_roots) <= MAX_TRUST_ROOTS
        or len(policy.signing_certificates) != len(SIGNER_ROLE_ORDER)
        or len(policy.signer_authorizations) != len(SIGNER_ROLE_ORDER)
        or len(policy.operator_authorizations) != 1
        or type(policy.allowed_algorithms) is not frozenset
        or not policy.allowed_algorithms
        or len(policy.allowed_algorithms) > len(ALLOWED_SIGNATURE_ALGORITHMS)
        or type(policy.revocation_snapshot) is not RevocationSnapshot
    ):
        _fail("INVALID_TRUST_POLICY", "trust policy cardinality or frozen shape drifted")
    typed_collections = (
        (policy.trust_roots, TrustRoot),
        (policy.signing_certificates, SigningCertificate),
        (policy.operator_authorizations, OperatorAuthorization),
        (policy.signer_authorizations, SignerAuthorization),
    )
    if any(type(item) is not item_type for items, item_type in typed_collections for item in items):
        _fail("INVALID_TRUST_POLICY", "trust policy contains a mutable or custom object")
    if any(
        type(item.public_key_pem) is not bytes
        or not 1 <= len(item.public_key_pem) <= MAX_PUBLIC_KEY_PEM_BYTES
        for item in (*policy.trust_roots, *policy.signing_certificates)
    ):
        _fail("RESOURCE_LIMIT_REJECTED", "trust policy public key exceeds its byte ceiling")
    if any(
        type(item.issuer_signature) is not str or not 1 <= len(item.issuer_signature) <= 16384
        for item in (
            *policy.signing_certificates,
            *policy.operator_authorizations,
            *policy.signer_authorizations,
        )
    ):
        _fail("RESOURCE_LIMIT_REJECTED", "trust policy signature exceeds its byte ceiling")
    if any(
        type(item.control_ids) is not frozenset
        for item in (*policy.operator_authorizations, *policy.signer_authorizations)
    ):
        _fail("INVALID_TRUST_POLICY", "authorization control sets must be frozensets")
    snapshot = policy.revocation_snapshot
    revocation_fields = (
        "revoked_root_fingerprints",
        "revoked_certificate_serials",
        "revoked_signing_key_fingerprints",
        "revoked_authorization_references",
    )
    if any(type(getattr(snapshot, field)) is not frozenset for field in revocation_fields) or any(
        len(getattr(snapshot, field)) > MAX_REVOCATION_ENTRIES for field in revocation_fields
    ):
        _fail("INVALID_TRUST_POLICY", "revocation snapshot is mutable or unbounded")
    return replace(
        policy,
        trust_roots=tuple(replace(item) for item in policy.trust_roots),
        signing_certificates=tuple(replace(item) for item in policy.signing_certificates),
        operator_authorizations=tuple(
            replace(item, control_ids=frozenset(item.control_ids))
            for item in policy.operator_authorizations
        ),
        signer_authorizations=tuple(
            replace(item, control_ids=frozenset(item.control_ids))
            for item in policy.signer_authorizations
        ),
        allowed_algorithms=frozenset(policy.allowed_algorithms),
        revocation_snapshot=replace(
            snapshot,
            revoked_root_fingerprints=frozenset(snapshot.revoked_root_fingerprints),
            revoked_certificate_serials=frozenset(snapshot.revoked_certificate_serials),
            revoked_signing_key_fingerprints=frozenset(
                snapshot.revoked_signing_key_fingerprints
            ),
            revoked_authorization_references=frozenset(
                snapshot.revoked_authorization_references
            ),
        ),
    )


def _validate_policy(
    policy: ExternalTrustPolicy,
    expected: ExpectedExternalContext,
    normalized: Mapping[str, Any],
    attempt_scope_sha256: str,
    now: datetime,
) -> tuple[
    dict[str, TrustRoot],
    dict[str, SigningCertificate],
    dict[str, OperatorAuthorization],
    dict[str, SignerAuthorization],
]:
    if type(policy) is not ExternalTrustPolicy or type(policy.replay_ledger) is not ReplayLedger:
        _fail("INVALID_TRUST_POLICY", "frozen trust policy and concrete replay ledger required")
    if (
        not isinstance(policy.max_receipt_age_seconds, int)
        or isinstance(policy.max_receipt_age_seconds, bool)
        or not 1 <= policy.max_receipt_age_seconds <= 86400
        or not policy.allowed_algorithms
        or not policy.allowed_algorithms.issubset(ALLOWED_SIGNATURE_ALGORITHMS)
    ):
        _fail("INVALID_TRUST_POLICY", "algorithm or freshness policy drifted")
    for anchor in expected.pinned_trust_anchors:
        if public_key_fingerprint_sha256(anchor.public_key_pem) != anchor.fingerprint_sha256:
            _fail("UNTRUSTED_KEY", "pinned trust anchor key or fingerprint drifted")
    snapshot = policy.revocation_snapshot
    if (
        snapshot.schema_version != REVOCATION_SNAPSHOT_SCHEMA_VERSION
        or snapshot.source != REVOCATION_SNAPSHOT_SOURCE
        or snapshot.trust_anchor_set_sha256 != normalized["trust_anchor_set_sha256"]
        or snapshot.snapshot_sha256 != revocation_snapshot_sha256(snapshot)
        or not _valid_at(snapshot.generated_at, snapshot.expires_at, now)
    ):
        _fail("INVALID_TRUST_POLICY", "caller-bound revocation snapshot is partial or stale")
    _token(snapshot.snapshot_id, "revocation snapshot_id")
    for value in snapshot.revoked_root_fingerprints:
        _sha(value, "revoked root fingerprint")
    for value in snapshot.revoked_certificate_serials:
        _token(value, "revoked certificate serial")
    for value in snapshot.revoked_signing_key_fingerprints:
        _sha(value, "revoked signing key fingerprint")
    for value in snapshot.revoked_authorization_references:
        _token(value, "revoked authorization reference")

    pins = {anchor.trust_root_id: anchor for anchor in expected.pinned_trust_anchors}
    if (
        [root.trust_root_id for root in policy.trust_roots] != list(pins)
        or [item.signer_role for item in policy.signing_certificates]
        != list(SIGNER_ROLE_ORDER)
        or [item.signer_role for item in policy.signer_authorizations]
        != list(SIGNER_ROLE_ORDER)
    ):
        _fail("UNTRUSTED_ROOT", "presented roots do not exactly match caller-bound pins")

    roots: dict[str, TrustRoot] = {}
    root_fingerprints: set[str] = set()
    for root in policy.trust_roots:
        root_id = _token(root.trust_root_id, "trust_root_id")
        pin = pins.get(root_id)
        if pin is not None and root.algorithm != pin.algorithm:
            _fail("UNTRUSTED_ALGORITHM", "presented root algorithm differs from its pin")
        if pin is not None and (
            root.public_key_pem != pin.public_key_pem
            or root.fingerprint_sha256 != pin.fingerprint_sha256
        ):
            _fail("UNTRUSTED_KEY", "presented root key differs from its pin")
        if (
            root_id in roots
            or pin is None
            or root.algorithm not in policy.allowed_algorithms
            or type(root.revoked) is not bool
            or root.revoked
        ):
            _fail("UNTRUSTED_ROOT", "trust root is duplicate, disallowed, or revoked")
        if not _valid_at(root.not_before, root.not_after, now):
            _fail("EXPIRED_TRUST", "trust root is not currently valid")
        fingerprint = public_key_fingerprint_sha256(root.public_key_pem)
        if (
            fingerprint != root.fingerprint_sha256
            or fingerprint in root_fingerprints
            or fingerprint in snapshot.revoked_root_fingerprints
        ):
            _fail("UNTRUSTED_KEY", "trust root fingerprint drifted or is reused")
        roots[root_id] = root
        root_fingerprints.add(fingerprint)

    certificates: dict[str, SigningCertificate] = {}
    certificate_fingerprints: set[str] = set()
    for certificate in policy.signing_certificates:
        key_id = _token(certificate.signing_key_id, "signing_key_id")
        root = roots.get(certificate.trust_root_id)
        if (
            key_id in certificates
            or root is None
            or type(certificate.revoked) is not bool
            or certificate.revoked
            or certificate.algorithm not in policy.allowed_algorithms
            or certificate.signer_role not in SIGNER_ROLES
        ):
            _fail("UNTRUSTED_CERTIFICATE", "certificate is duplicate, untrusted, or revoked")
        _token(certificate.certificate_serial, "certificate_serial")
        _token(certificate.signer_identity, "certificate signer_identity")
        if not _valid_at(certificate.not_before, certificate.not_after, now):
            _fail("EXPIRED_CERTIFICATE", "certificate is not currently valid")
        if (
            _aware_utc(certificate.not_before, "certificate.not_before")
            < _aware_utc(root.not_before, "root.not_before")
            or _aware_utc(certificate.not_after, "certificate.not_after")
            > _aware_utc(root.not_after, "root.not_after")
        ):
            _fail("UNTRUSTED_CERTIFICATE", "certificate validity escapes its trust root")
        fingerprint = public_key_fingerprint_sha256(certificate.public_key_pem)
        if (
            fingerprint != certificate.public_key_fingerprint_sha256
            or fingerprint in root_fingerprints
            or fingerprint in certificate_fingerprints
            or fingerprint in snapshot.revoked_signing_key_fingerprints
            or certificate.certificate_serial in snapshot.revoked_certificate_serials
        ):
            _fail("UNTRUSTED_KEY", "certificate fingerprint drifted or reuses a trusted key")
        _verify_root_signature(
            root,
            certificate_signed_payload(certificate),
            certificate.issuer_signature,
            "certificate issuer signature",
        )
        certificates[key_id] = certificate
        certificate_fingerprints.add(fingerprint)

    operator_authorizations: dict[str, OperatorAuthorization] = {}
    for authorization in policy.operator_authorizations:
        reference = _token(authorization.authorization_reference, "authorization_reference")
        root = roots.get(authorization.trust_root_id)
        if (
            reference in operator_authorizations
            or root is None
            or type(authorization.revoked) is not bool
            or authorization.revoked
            or reference in snapshot.revoked_authorization_references
        ):
            _fail("UNAUTHORIZED_OPERATOR", "operator authorization is duplicate, untrusted, or revoked")
        _validate_authorization_scope(authorization, expected, attempt_scope_sha256, now)
        _token(authorization.operator_identity, "authorized operator_identity")
        _verify_root_signature(
            root,
            operator_authorization_signed_payload(authorization),
            authorization.issuer_signature,
            "operator authorization issuer signature",
        )
        operator_authorizations[reference] = authorization

    signer_authorizations: dict[str, SignerAuthorization] = {}
    for authorization in policy.signer_authorizations:
        reference = _token(
            authorization.authorization_reference, "signer authorization_reference"
        )
        root = roots.get(authorization.trust_root_id)
        certificate = certificates.get(authorization.signing_key_id)
        if (
            reference in signer_authorizations
            or root is None
            or certificate is None
            or type(authorization.revoked) is not bool
            or authorization.revoked
            or reference in snapshot.revoked_authorization_references
            or authorization.signer_identity != certificate.signer_identity
            or authorization.signer_role != certificate.signer_role
            or authorization.trust_root_id != certificate.trust_root_id
        ):
            _fail("UNTRUSTED_SIGNER", "signer authorization is duplicate, mixed, or revoked")
        _validate_authorization_scope(authorization, expected, attempt_scope_sha256, now)
        _verify_root_signature(
            root,
            signer_authorization_signed_payload(authorization),
            authorization.issuer_signature,
            "signer authorization issuer signature",
        )
        signer_authorizations[reference] = authorization
    used_root_ids = {
        item.trust_root_id
        for item in (
            *policy.signing_certificates,
            *policy.operator_authorizations,
            *policy.signer_authorizations,
        )
    }
    if (
        set(roots) != used_root_ids
        or set(certificates)
        != {authorization.signing_key_id for authorization in policy.signer_authorizations}
        or not snapshot.revoked_root_fingerprints.issubset(root_fingerprints)
        or not snapshot.revoked_certificate_serials.issubset(
            {item.certificate_serial for item in policy.signing_certificates}
        )
        or not snapshot.revoked_signing_key_fingerprints.issubset(certificate_fingerprints)
        or not snapshot.revoked_authorization_references.issubset(
            {*operator_authorizations, *signer_authorizations}
        )
    ):
        _fail("INVALID_TRUST_POLICY", "trust policy or revocation inventory has unused extras")
    return roots, certificates, operator_authorizations, signer_authorizations


def _validate_authorization_scope(
    authorization: OperatorAuthorization | SignerAuthorization,
    expected: ExpectedExternalContext,
    attempt_scope_sha256: str,
    now: datetime,
) -> None:
    if (
        authorization.scope != AUTHORIZATION_SCOPE
        or authorization.control_ids != frozenset(REQUIRED_CONTROL_IDS)
        or not _valid_at(authorization.not_before, authorization.expires_at, now)
    ):
        _fail("AUTHORIZATION_SCOPE_REJECTED", "authorization scope, controls, or time drifted")
    expected_values = {
        "attempt_id": expected.attempt_id,
        "attempt_scope_sha256": attempt_scope_sha256,
        "candidate_sha": expected.candidate_sha,
        "cluster_id": expected.cluster_id,
        "configuration_sha256": expected.configuration_sha256,
        "deployment_manifest_sha256": expected.deployment_manifest_sha256,
        "deployment_uid": expected.deployment_uid,
        "environment_identity": expected.environment_identity,
        "namespace": expected.namespace,
        "region": expected.region,
    }
    if any(getattr(authorization, field) != value for field, value in expected_values.items()):
        _fail("AUTHORIZATION_SCOPE_REJECTED", "authorization release context is mixed")


def _validate_control_facts(control_id: str, value: Any, expected: ExpectedExternalContext) -> None:
    if control_id in RUNTIME_BLOCKER_CONTROLS:
        facts = _exact_keys(
            value,
            {"accepted", "actual_external", "control_id", "fact_kind"},
            "runtime blocker facts",
        )
        if not _strict_equal(
            facts,
            {
                "accepted": True,
                "actual_external": True,
                "control_id": control_id,
                "fact_kind": "RUNTIME_BLOCKER_ACCEPTANCE",
            },
        ):
            _fail("CONTROL_NOT_PASS", "runtime blocker evidence is not actual and accepted")
        return
    if control_id == ISTIO_CRD_CONTROL:
        facts = _exact_keys(
            value,
            {"actual_external", "api_version", "crd_ready", "fact_kind"},
            "Istio CRD facts",
        )
        if not _strict_equal(
            facts,
            {
                "actual_external": True,
                "api_version": "security.istio.io/v1",
                "crd_ready": True,
                "fact_kind": "ISTIO_CRD_READINESS",
            },
        ):
            _fail("CONTROL_NOT_PASS", "Istio security.io/v1 CRD is not actually ready")
        return
    if control_id == ISTIO_DATAPLANE_CONTROL:
        facts = _exact_keys(
            value,
            {
                "actual_external",
                "deployment_resources",
                "fact_kind",
                "intercepted_workloads",
                "target_workloads",
            },
            "Istio dataplane facts",
        )
        if not _strict_equal(
            facts,
            {
                "actual_external": True,
                "deployment_resources": list(expected.deployment_resources),
                "fact_kind": "ISTIO_DATAPLANE_INTERCEPTION",
                "intercepted_workloads": list(expected.target_workload_identities),
                "target_workloads": list(expected.target_workload_identities),
            },
        ):
            _fail("CONTROL_NOT_PASS", "Istio dataplane coverage is partial or mixed")
        return
    if control_id == ISTIO_MTLS_CONTROL:
        facts = _exact_keys(
            value,
            {"actual_external", "enforced_edges", "expected_edges", "fact_kind", "mode"},
            "Istio mTLS facts",
        )
        if not _strict_equal(
            facts,
            {
                "actual_external": True,
                "enforced_edges": list(expected.mtls_edges),
                "expected_edges": list(expected.mtls_edges),
                "fact_kind": "ISTIO_STRICT_MTLS_ENFORCEMENT",
                "mode": "STRICT",
            },
        ):
            _fail("CONTROL_NOT_PASS", "Istio strict mTLS edge coverage is partial or mixed")
        return
    if control_id == ISTIO_AUTHZ_CONTROL:
        facts = _exact_keys(
            value,
            {
                "actual_external",
                "allowed_probe_edges",
                "denied_probe_edges",
                "enforced_edges",
                "expected_edges",
                "fact_kind",
            },
            "Istio authorization facts",
        )
        expected_edges = list(expected.authorization_edges)
        if not _strict_equal(
            facts,
            {
                "actual_external": True,
                "allowed_probe_edges": expected_edges,
                "denied_probe_edges": expected_edges,
                "enforced_edges": expected_edges,
                "expected_edges": expected_edges,
                "fact_kind": "ISTIO_AUTHORIZATION_ENFORCEMENT",
            },
        ):
            _fail("CONTROL_NOT_PASS", "Istio authorization edge coverage is partial or mixed")
        return
    if control_id == OTEL_BINDING_CONTROL:
        facts = _exact_keys(
            value,
            {
                "actual_external",
                "binding_verified",
                "deployment_generation",
                "deployment_uid",
                "fact_kind",
                "labels",
                "namespace",
                "otlp_ports",
                "service_account",
            },
            "I3/I4 OTel facts",
        )
        labels = _exact_keys(
            facts["labels"],
            {"app.kubernetes.io/name", "app.kubernetes.io/part-of"},
            "OTel labels",
        )
        if (
            facts["actual_external"] is not True
            or facts["binding_verified"] is not True
            or not _strict_equal(
                facts["deployment_generation"], expected.deployment_generation
            )
            or facts["deployment_uid"] != expected.deployment_uid
            or facts["fact_kind"] != "I3_I4_OTEL_EXACT_BINDING"
            or labels
            != {
                "app.kubernetes.io/name": "otel-collector",
                "app.kubernetes.io/part-of": "after-sale-flow",
            }
            or facts["namespace"] != expected.namespace
            or not _strict_equal(facts["otlp_ports"], [4317, 4318])
            or facts["service_account"] != "after-sale-otel-collector"
        ):
            _fail("CONTROL_NOT_PASS", "I3/I4 OTel exact binding is not verified")
        return
    _fail("CONTROL_SET_REJECTED", "unknown or aliased control ID")


def _expected_metadata(
    expected: ExpectedExternalContext,
    normalized: Mapping[str, Any],
    *,
    operator_identity: str,
    authorization_reference: str,
    signer_bindings: list[dict[str, str]],
    observed_at: str,
    issued_at: str,
    expires_at: str,
    nonce: str,
) -> dict[str, Any]:
    return {
        "attempt_id": expected.attempt_id,
        "attempt_number": 1,
        "authorization_edges": normalized["authorization_edges"],
        "authorization_reference": authorization_reference,
        "authorization_scope": AUTHORIZATION_SCOPE,
        "candidate_author_identity": expected.candidate_author_identity,
        "candidate_path_blobs": normalized["path_blobs"],
        "candidate_path_blobs_sha256": expected.candidate_path_blobs_sha256,
        "candidate_sha": expected.candidate_sha,
        "candidate_tree_sha": expected.candidate_tree_sha,
        "checkpoint_id": expected.checkpoint_id,
        "cluster_id": expected.cluster_id,
        "configuration_sha256": expected.configuration_sha256,
        "context_id": expected.context_id,
        "context_sha256": expected.context_sha256,
        "deployment_generation": expected.deployment_generation,
        "deployment_manifest_sha256": expected.deployment_manifest_sha256,
        "deployment_resources": normalized["deployment_resources"],
        "deployment_uid": expected.deployment_uid,
        "environment_class": "PRODUCTION",
        "environment_identity": expected.environment_identity,
        "evidence_author_identity": expected.evidence_author_identity,
        "evidence_source": EVIDENCE_SOURCE,
        "expires_at": expires_at,
        "generator_identity": expected.generator_identity,
        "images": normalized["images"],
        "issued_at": issued_at,
        "mtls_edges": normalized["mtls_edges"],
        "namespace": expected.namespace,
        "nonce": nonce,
        "observed_at": observed_at,
        "operator_identity": operator_identity,
        "previous_attempt_id": None,
        "region": expected.region,
        "runner_identity": expected.runner_identity,
        "scenario_id": SCENARIO_ID,
        "signer_authorizations": signer_bindings,
        "step_id": STEP_ID,
        "target_workload_identities": normalized["target_workload_identities"],
    }


def _parse_control_evidence(
    payload: bytes,
    expected: ExpectedExternalContext,
    normalized: Mapping[str, Any],
    now: datetime,
    max_age_seconds: int,
) -> tuple[dict[str, Any], ValidatedControlEvidence]:
    try:
        bundle = parse_bounded_json_bytes(
            payload,
            context="external control evidence",
            max_bytes=MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES,
            max_depth=MAX_JSON_NESTING_DEPTH,
            max_tokens=MAX_JSON_TOKENS_PER_PAYLOAD,
            max_string_bytes=MAX_JSON_STRING_BYTES,
        )
    except EvidenceValidationError as exception:
        raise ExternalGateError("STRICT_SHAPE_REJECTED", str(exception)) from exception
    _exact_keys(bundle, CONTROL_EVIDENCE_FIELDS, "external control evidence")
    if bundle["schema_version"] != CONTROL_EVIDENCE_SCHEMA_VERSION:
        _fail("STRICT_SHAPE_REJECTED", "control evidence schema version drifted")
    control_id = bundle["control_id"]
    if control_id not in REQUIRED_CONTROL_IDS:
        _fail("CONTROL_SET_REJECTED", "control evidence ID is missing, extra, or aliased")
    metadata = _exact_keys(bundle["metadata"], CONTROL_METADATA_FIELDS, "control metadata")
    signer_bindings = metadata["signer_authorizations"]
    if not isinstance(signer_bindings, list) or len(signer_bindings) != len(SIGNER_ROLE_ORDER):
        _fail("UNTRUSTED_SIGNER", "control evidence requires six signer authorizations")
    normalized_bindings: list[dict[str, str]] = []
    for item in signer_bindings:
        binding = _exact_keys(
            item, SIGNER_AUTHORIZATION_BINDING_FIELDS, "signer authorization binding"
        )
        normalized_bindings.append(binding)
    if [item["role"] for item in normalized_bindings] != list(SIGNER_ROLE_ORDER):
        _fail("UNTRUSTED_SIGNER", "signer authorization roles are incomplete or reordered")
    if len({item["signer_identity"].casefold() for item in normalized_bindings}) != len(
        normalized_bindings
    ):
        _fail("SELF_APPROVAL_REJECTED", "six signer identities must be distinct")
    for item in normalized_bindings:
        for field in (
            "authorization_reference",
            "certificate_serial",
            "role",
            "signer_identity",
            "signing_key_id",
            "trust_root_id",
        ):
            _token(item[field], f"signer binding {field}")

    observed = _timestamp(metadata["observed_at"], "metadata.observed_at")
    issued = _timestamp(metadata["issued_at"], "metadata.issued_at")
    expires = _timestamp(metadata["expires_at"], "metadata.expires_at")
    nonce = metadata["nonce"]
    if not isinstance(nonce, str) or not NONCE.fullmatch(nonce):
        _fail("ANTI_REPLAY_REJECTED", "control evidence nonce is not strong and opaque")
    if (
        observed > issued
        or issued - now > timedelta(seconds=30)
        or now - observed > timedelta(seconds=max_age_seconds)
        or expires <= issued
        or now >= expires
    ):
        _fail("STALE_OR_EXPIRED_RECEIPT", "control evidence is stale, future, or expired")
    expected_metadata = _expected_metadata(
        expected,
        normalized,
        operator_identity=metadata["operator_identity"],
        authorization_reference=metadata["authorization_reference"],
        signer_bindings=normalized_bindings,
        observed_at=metadata["observed_at"],
        issued_at=metadata["issued_at"],
        expires_at=metadata["expires_at"],
        nonce=nonce,
    )
    if not _strict_equal(metadata, expected_metadata):
        _fail("MIXED_RELEASE_CONTEXT", "control metadata is partial, extra, or mixed")

    step = bundle["step_evidence"]
    _validate_control_facts(control_id, step, expected)
    stop = _exact_keys(
        bundle["stop_evidence"],
        {"status", "stop_condition_id"},
        "stop evidence",
    )
    if stop != {"status": "NOT_TRIGGERED", "stop_condition_id": "NONE"}:
        _fail("CONTROL_NOT_PASS", "control evidence contains a stop condition")
    rollback = _exact_keys(
        bundle["rollback_evidence"],
        {"rollback_disposition", "status"},
        "rollback evidence",
    )
    if rollback != {"rollback_disposition": "NOT_REQUIRED", "status": "NOT_REQUIRED"}:
        _fail("CONTROL_NOT_PASS", "control evidence requires rollback")
    step_hash = canonical_sha256(step)
    stop_hash = canonical_sha256(stop)
    rollback_hash = canonical_sha256(rollback)
    if (
        bundle["step_evidence_sha256"] != step_hash
        or bundle["stop_evidence_sha256"] != stop_hash
        or bundle["rollback_evidence_sha256"] != rollback_hash
    ):
        _fail("EVIDENCE_HASH_MISMATCH", "step, stop, or rollback evidence hash drifted")
    manifest = _exact_keys(
        bundle["evidence_manifest"],
        {
            "control_id",
            "metadata_sha256",
            "rollback_evidence_sha256",
            "schema_version",
            "step_evidence_sha256",
            "stop_evidence_sha256",
        },
        "evidence manifest",
    )
    expected_manifest = {
        "control_id": control_id,
        "metadata_sha256": canonical_sha256(metadata),
        "rollback_evidence_sha256": rollback_hash,
        "schema_version": "phase8-external-control-evidence-manifest.v1",
        "step_evidence_sha256": step_hash,
        "stop_evidence_sha256": stop_hash,
    }
    evidence_hash = canonical_sha256(manifest)
    if not _strict_equal(manifest, expected_manifest) or bundle["evidence_sha256"] != evidence_hash:
        _fail("EVIDENCE_HASH_MISMATCH", "control evidence manifest or hash drifted")
    return bundle, ValidatedControlEvidence(
        control_id=control_id,
        evidence_sha256=evidence_hash,
        step_evidence_sha256=step_hash,
        stop_evidence_sha256=stop_hash,
        rollback_evidence_sha256=rollback_hash,
        nonce=nonce,
        canonical_bundle=canonical_json_bytes(bundle).decode("utf-8"),
        canonical_bundle_sha256=canonical_sha256(bundle),
        source_payload_base64=base64.b64encode(payload).decode("ascii"),
        source_payload_sha256=hashlib.sha256(payload).hexdigest(),
    )


def _verify_receipt(
    payload: bytes,
    *,
    bundle: Mapping[str, Any],
    evidence: ValidatedControlEvidence,
    expected: ExpectedExternalContext,
    roots: Mapping[str, TrustRoot],
    certificates: Mapping[str, SigningCertificate],
    operator_authorizations: Mapping[str, OperatorAuthorization],
    signer_authorizations: Mapping[str, SignerAuthorization],
    now: datetime,
) -> ValidatedControlReceipt:
    try:
        receipt = parse_bounded_json_bytes(
            payload,
            context="external signed receipt",
            max_bytes=MAX_RECEIPT_PAYLOAD_BYTES,
            max_depth=MAX_JSON_NESTING_DEPTH,
            max_tokens=MAX_JSON_TOKENS_PER_PAYLOAD,
            max_string_bytes=MAX_JSON_STRING_BYTES,
        )
    except EvidenceValidationError as exception:
        raise ExternalGateError("STRICT_SHAPE_REJECTED", str(exception)) from exception
    _exact_keys(receipt, RECEIPT_FIELDS, "external signed receipt")
    metadata = bundle["metadata"]
    if (
        receipt["schema_version"] != RECEIPT_SCHEMA_VERSION
        or receipt["control_id"] != evidence.control_id
        or receipt["scenario_id"] != SCENARIO_ID
        or receipt["step_id"] != STEP_ID
        or not _strict_equal(receipt["checkpoint_order"], CHECKPOINT_ORDER)
        or receipt["claimed_result"] != "PASS"
        or receipt["status"] != "ACCEPTED"
        or receipt["candidate_sha"] != expected.candidate_sha
        or receipt["candidate_tree_sha"] != expected.candidate_tree_sha
        or receipt["configuration_sha256"] != expected.configuration_sha256
        or receipt["context_id"] != expected.context_id
        or receipt["context_sha256"] != expected.context_sha256
        or receipt["environment_identity"] != expected.environment_identity
        or receipt["deployment_manifest_sha256"] != expected.deployment_manifest_sha256
        or not _strict_equal(receipt["images"], [image.as_dict() for image in expected.images])
        or receipt["attempt_id"] != expected.attempt_id
        or not _strict_equal(receipt["attempt_number"], 1)
        or receipt["checkpoint_id"] != expected.checkpoint_id
        or receipt["previous_attempt_id"] is not None
        or receipt["operator_identity"] != metadata["operator_identity"]
        or receipt["authorization_reference"] != metadata["authorization_reference"]
        or receipt["observed_at"] != metadata["observed_at"]
        or receipt["step_evidence_sha256"] != evidence.step_evidence_sha256
        or receipt["stop_condition_id"] != "NONE"
        or receipt["stop_evidence_sha256"] != evidence.stop_evidence_sha256
        or receipt["rollback_disposition"] != "NOT_REQUIRED"
        or receipt["rollback_evidence_sha256"] != evidence.rollback_evidence_sha256
        or receipt["evidence_sha256"] != evidence.evidence_sha256
    ):
        _fail("MIXED_RELEASE_CONTEXT", "receipt frozen fields or evidence bindings drifted")

    role = receipt["signer_role"]
    if role not in SIGNER_ROLES:
        _fail("UNTRUSTED_SIGNER", "receipt signer role is missing or aliased")
    role_binding = next(
        (item for item in metadata["signer_authorizations"] if item["role"] == role), None
    )
    certificate = certificates.get(receipt["signing_key_id"])
    signer_authorization = (
        signer_authorizations.get(role_binding["authorization_reference"])
        if role_binding is not None
        else None
    )
    root = roots.get(receipt["trust_root_id"])
    if (
        role_binding is None
        or certificate is None
        or signer_authorization is None
        or root is None
        or receipt["signature_algorithm"] != certificate.algorithm
        or receipt["signer_identity"] != certificate.signer_identity
        or role != certificate.signer_role
        or receipt["trust_root_id"] != certificate.trust_root_id
        or role_binding
        != {
            "authorization_reference": signer_authorization.authorization_reference,
            "certificate_serial": certificate.certificate_serial,
            "role": role,
            "signer_identity": certificate.signer_identity,
            "signing_key_id": certificate.signing_key_id,
            "trust_root_id": certificate.trust_root_id,
        }
        or evidence.control_id not in signer_authorization.control_ids
    ):
        _fail("UNTRUSTED_SIGNER", "receipt signer key, role, root, or authorization drifted")

    operator_authorization = operator_authorizations.get(receipt["authorization_reference"])
    operator_root = (
        roots.get(operator_authorization.trust_root_id)
        if operator_authorization is not None
        else None
    )
    if (
        operator_authorization is None
        or operator_root is None
        or operator_authorization.operator_identity != receipt["operator_identity"]
        or evidence.control_id not in operator_authorization.control_ids
    ):
        _fail("UNAUTHORIZED_OPERATOR", "operator is not authorized for this control")
    observed = _timestamp(receipt["observed_at"], "receipt.observed_at")
    issued = _timestamp(metadata["issued_at"], "metadata.issued_at")
    if (
        not _valid_at(root.not_before, root.not_after, observed, issued, now)
        or not _valid_at(
            operator_root.not_before,
            operator_root.not_after,
            observed,
            issued,
            now,
        )
        or not _valid_at(certificate.not_before, certificate.not_after, observed, issued, now)
        or not _valid_at(
            operator_authorization.not_before,
            operator_authorization.expires_at,
            observed,
            issued,
            now,
        )
        or not _valid_at(
            signer_authorization.not_before,
            signer_authorization.expires_at,
            observed,
            issued,
            now,
        )
    ):
        _fail("EXPIRED_CREDENTIAL_OR_AUTHORIZATION", "trust was invalid at observation, issue, or intake")
    authors = {
        expected.runner_identity.casefold(),
        expected.generator_identity.casefold(),
        expected.candidate_author_identity.casefold(),
        expected.evidence_author_identity.casefold(),
    }
    signer = receipt["signer_identity"]
    operator = receipt["operator_identity"]
    if signer.casefold() in authors or operator.casefold() in authors or signer.casefold() == operator.casefold():
        _fail("SELF_APPROVAL_REJECTED", "signer/operator overlaps an author or each other")
    signed_payload = receipt_signed_payload(receipt)
    if canonical_sha256(signed_payload) != receipt["signed_payload_sha256"]:
        _fail("SIGNED_PAYLOAD_MISMATCH", "frozen signed payload hash drifted")
    if receipt_sha256(receipt) != receipt["receipt_sha256"]:
        _fail("RECEIPT_SUBSTITUTION", "receipt hash drifted")
    try:
        verify_detached_signature(
            algorithm=certificate.algorithm,
            public_key_pem=certificate.public_key_pem,
            payload=canonical_json_bytes(signed_payload),
            signature=decode_signature(receipt["signature"], context="receipt signature"),
        )
    except EvidenceValidationError as exception:
        raise ExternalGateError("INVALID_SIGNATURE", str(exception)) from exception
    return ValidatedControlReceipt(
        control_id=evidence.control_id,
        signer_role=role,
        signer_identity=signer,
        operator_identity=operator,
        operator_authorization_reference=operator_authorization.authorization_reference,
        operator_trust_root_id=operator_authorization.trust_root_id,
        signer_authorization_reference=signer_authorization.authorization_reference,
        signing_key_id=certificate.signing_key_id,
        trust_root_id=certificate.trust_root_id,
        certificate_serial=certificate.certificate_serial,
        certificate_fingerprint_sha256=certificate.public_key_fingerprint_sha256,
        signed_payload_sha256=receipt["signed_payload_sha256"],
        signature=receipt["signature"],
        canonical_signed_payload=canonical_json_bytes(signed_payload).decode("utf-8"),
        source_payload_base64=base64.b64encode(payload).decode("ascii"),
        source_payload_sha256=hashlib.sha256(payload).hexdigest(),
        receipt_sha256=receipt["receipt_sha256"],
        observed_at=observed,
    )


def verify_external_gate_receipts(
    receipt_payloads: Sequence[bytes],
    control_evidence_payloads: Sequence[bytes],
    expected: ExpectedExternalContext,
    trust_policy: ExternalTrustPolicy,
    *,
    now: datetime,
) -> ValidatedPendingExternalReceiptSet:
    try:
        now_utc = _aware_utc(now, "now")
        expected_snapshot = _snapshot_expected_context(expected)
        normalized = _validate_expected_context(expected_snapshot)
        attempt_scope_sha = _attempt_scope_sha256(expected_snapshot, normalized)
        if (
            type(trust_policy) is not ExternalTrustPolicy
            or type(trust_policy.replay_ledger) is not ReplayLedger
        ):
            _fail("INVALID_TRUST_POLICY", "frozen trust policy and replay ledger required")
        # The stable scope is tombstoned before any policy crypto or untrusted receipt parsing.
        trust_policy.replay_ledger.claim_attempt(
            attempt_id=expected_snapshot.attempt_id,
            attempt_scope_sha256=attempt_scope_sha,
        )
        if (
            type(receipt_payloads) is not tuple
            or len(receipt_payloads) != len(REQUIRED_CONTROL_IDS) * len(SIGNER_ROLE_ORDER)
            or any(type(item) is not bytes for item in receipt_payloads)
        ):
            _fail("SIX_ROLE_ENVELOPE_REJECTED", "exactly sixty immutable receipt bytes are required")
        if (
            type(control_evidence_payloads) is not tuple
            or len(control_evidence_payloads) != len(REQUIRED_CONTROL_IDS)
            or any(type(item) is not bytes for item in control_evidence_payloads)
        ):
            _fail("CONTROL_SET_REJECTED", "exactly ten immutable control evidence bundles are required")
        if (
            any(len(item) > MAX_RECEIPT_PAYLOAD_BYTES for item in receipt_payloads)
            or any(
                len(item) > MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES
                for item in control_evidence_payloads
            )
            or sum(map(len, receipt_payloads))
            + sum(map(len, control_evidence_payloads))
            > MAX_EXTERNAL_INTAKE_BYTES
        ):
            _fail("RESOURCE_LIMIT_REJECTED", "external intake bytes exceed the frozen ceiling")
        policy_snapshot = _snapshot_policy_shape(trust_policy)
        roots, certificates, operator_authorizations, signer_authorizations = _validate_policy(
            policy_snapshot,
            expected_snapshot,
            normalized,
            attempt_scope_sha,
            now_utc,
        )
        parsed_bundles: list[dict[str, Any]] = []
        validated_evidence: list[ValidatedControlEvidence] = []
        for payload in control_evidence_payloads:
            bundle, evidence = _parse_control_evidence(
                payload,
                expected_snapshot,
                normalized,
                now_utc,
                policy_snapshot.max_receipt_age_seconds,
            )
            parsed_bundles.append(bundle)
            validated_evidence.append(evidence)
        if [item.control_id for item in validated_evidence] != list(REQUIRED_CONTROL_IDS):
            _fail("CONTROL_SET_REJECTED", "control evidence set is missing, extra, aliased, or reordered")
        if len({item.nonce for item in validated_evidence}) != len(validated_evidence):
            _fail("ANTI_REPLAY_REJECTED", "control evidence nonce is reused")
        signer_mapping = parsed_bundles[0]["metadata"]["signer_authorizations"]
        if any(
            not _strict_equal(bundle["metadata"]["signer_authorizations"], signer_mapping)
            for bundle in parsed_bundles[1:]
        ):
            _fail(
                "UNTRUSTED_SIGNER",
                "role, identity, key, and fingerprint authorization mapping changed across controls",
            )

        receipts: list[ValidatedControlReceipt] = []
        for control_index, (bundle, evidence) in enumerate(
            zip(parsed_bundles, validated_evidence, strict=True)
        ):
            start = control_index * len(SIGNER_ROLE_ORDER)
            group_payloads = receipt_payloads[start : start + len(SIGNER_ROLE_ORDER)]
            group = [
                _verify_receipt(
                    payload,
                    bundle=bundle,
                    evidence=evidence,
                    expected=expected_snapshot,
                    roots=roots,
                    certificates=certificates,
                    operator_authorizations=operator_authorizations,
                    signer_authorizations=signer_authorizations,
                    now=now_utc,
                )
                for payload in group_payloads
            ]
            if [item.signer_role for item in group] != list(SIGNER_ROLE_ORDER) or len(
                {item.signer_identity.casefold() for item in group}
            ) != len(SIGNER_ROLE_ORDER):
                _fail("SIX_ROLE_ENVELOPE_REJECTED", "six roles/identities are incomplete or reordered")
            receipts.extend(group)
        if (
            {item.operator_authorization_reference for item in receipts}
            != set(operator_authorizations)
            or {item.signer_authorization_reference for item in receipts}
            != set(signer_authorizations)
            or {item.signing_key_id for item in receipts} != set(certificates)
            or {
                root_id
                for item in receipts
                for root_id in (item.trust_root_id, item.operator_trust_root_id)
            }
            != set(roots)
        ):
            _fail("INVALID_TRUST_POLICY", "trust policy contains an unused chain component")
        receipt_hashes = tuple(item.receipt_sha256 for item in receipts)
        context_document = _expected_context_document(expected_snapshot, normalized)
        receipt_set_sha = canonical_sha256(
            {
                "context": context_document,
                "control_evidence_hashes": [item.evidence_sha256 for item in validated_evidence],
                "receipt_hashes": list(receipt_hashes),
            }
        )
        consumption = policy_snapshot.replay_ledger.consume_once(
            attempt_id=expected_snapshot.attempt_id,
            attempt_scope_sha256=attempt_scope_sha,
            receipt_set_sha256=receipt_set_sha,
            receipt_hashes=receipt_hashes,
            nonces=tuple(item.nonce for item in validated_evidence),
            consumed_at=now_utc,
        )
        return ValidatedPendingExternalReceiptSet(
            context=expected_snapshot,
            control_evidence=tuple(validated_evidence),
            receipts=tuple(receipts),
            receipt_set_sha256=receipt_set_sha,
            replay_consumption=consumption,
            trust_policy=policy_snapshot,
            trust_anchor_set_sha256=normalized["trust_anchor_set_sha256"],
            revocation_snapshot_sha256=policy_snapshot.revocation_snapshot.snapshot_sha256,
        )
    except ExternalGateError:
        raise
    except EvidenceValidationError as exception:
        raise ExternalGateError("CRYPTO_OR_ENCODING_REJECTED", str(exception)) from exception
    except (
        AttributeError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
    ) as exception:
        raise ExternalGateError(
            "STRICT_SHAPE_REJECTED", "external receipt intake rejected malformed input"
        ) from exception


def _trust_chain_artifact(policy: ExternalTrustPolicy) -> dict[str, Any]:
    return {
        "allowed_algorithms": sorted(policy.allowed_algorithms),
        "max_receipt_age_seconds": policy.max_receipt_age_seconds,
        "operator_authorizations": [
            {
                "issuer_signature": item.issuer_signature,
                "revoked_local_flag": item.revoked,
                "signed_payload": operator_authorization_signed_payload(item),
            }
            for item in policy.operator_authorizations
        ],
        "revocation_snapshot": {
            "authority": REVOCATION_STATUS,
            "payload": revocation_snapshot_payload(policy.revocation_snapshot),
            "snapshot_sha256": policy.revocation_snapshot.snapshot_sha256,
        },
        "signer_authorizations": [
            {
                "issuer_signature": item.issuer_signature,
                "revoked_local_flag": item.revoked,
                "signed_payload": signer_authorization_signed_payload(item),
            }
            for item in policy.signer_authorizations
        ],
        "signing_certificates": [
            {
                "issuer_signature": item.issuer_signature,
                "public_key_pem_base64": base64.b64encode(item.public_key_pem).decode("ascii"),
                "revoked_local_flag": item.revoked,
                "signed_payload": certificate_signed_payload(item),
            }
            for item in policy.signing_certificates
        ],
        "trust_root_provenance": TRUST_ROOT_PROVENANCE,
        "trust_roots": [
            {
                "algorithm": item.algorithm,
                "fingerprint_sha256": item.fingerprint_sha256,
                "not_after": _format_time(item.not_after),
                "not_before": _format_time(item.not_before),
                "public_key_pem_base64": base64.b64encode(item.public_key_pem).decode("ascii"),
                "revoked_local_flag": item.revoked,
                "trust_root_id": item.trust_root_id,
            }
            for item in policy.trust_roots
        ],
    }


def _control_binding_artifact(item: ValidatedControlEvidence) -> dict[str, str]:
    return {
        "canonical_bundle": item.canonical_bundle,
        "canonical_bundle_sha256": item.canonical_bundle_sha256,
        "control_id": item.control_id,
        "evidence_sha256": item.evidence_sha256,
        "nonce": item.nonce,
        "rollback_evidence_sha256": item.rollback_evidence_sha256,
        "source_payload_base64": item.source_payload_base64,
        "source_payload_sha256": item.source_payload_sha256,
        "step_evidence_sha256": item.step_evidence_sha256,
        "stop_evidence_sha256": item.stop_evidence_sha256,
    }


def _receipt_binding_artifact(item: ValidatedControlReceipt) -> dict[str, str]:
    return {
        "canonical_signed_payload": item.canonical_signed_payload,
        "certificate_fingerprint_sha256": item.certificate_fingerprint_sha256,
        "certificate_serial": item.certificate_serial,
        "control_id": item.control_id,
        "observed_at": _format_time(item.observed_at),
        "operator_authorization_reference": item.operator_authorization_reference,
        "operator_identity": item.operator_identity,
        "operator_trust_root_id": item.operator_trust_root_id,
        "receipt_sha256": item.receipt_sha256,
        "signature": item.signature,
        "signed_payload_sha256": item.signed_payload_sha256,
        "signer_authorization_reference": item.signer_authorization_reference,
        "signer_identity": item.signer_identity,
        "signer_role": item.signer_role,
        "signing_key_id": item.signing_key_id,
        "source_payload_base64": item.source_payload_base64,
        "source_payload_sha256": item.source_payload_sha256,
        "trust_root_id": item.trust_root_id,
    }


def _replay_consumption_artifact(item: ReplayConsumption) -> dict[str, Any]:
    return {
        "attempt_id": item.attempt_id,
        "attempt_scope_sha256": item.attempt_scope_sha256,
        "consumed_at": _format_time(item.consumed_at),
        "nonces": list(item.nonces),
        "receipt_hashes": list(item.receipt_hashes),
        "receipt_set_sha256": item.receipt_set_sha256,
        "sequence": item.sequence,
    }


def _validation_policy_artifact(
    validated: ValidatedPendingExternalReceiptSet,
) -> dict[str, Any]:
    return {
        "evaluation_instant": _format_time(validated.replay_consumption.consumed_at),
        "evaluation_time_provenance": validated.evaluation_time_provenance,
        "freshness_status": "UNVERIFIED_PENDING_EXTERNAL",
        "max_control_evidence_payload_bytes": MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES,
        "max_external_intake_bytes": MAX_EXTERNAL_INTAKE_BYTES,
        "max_json_nesting_depth": MAX_JSON_NESTING_DEPTH,
        "max_json_string_bytes": MAX_JSON_STRING_BYTES,
        "max_json_tokens_per_payload": MAX_JSON_TOKENS_PER_PAYLOAD,
        "max_receipt_age_seconds": validated.trust_policy.max_receipt_age_seconds,
        "max_receipt_payload_bytes": MAX_RECEIPT_PAYLOAD_BYTES,
    }


def _decode_artifact_bytes(value: Any, *, context: str, max_bytes: int) -> bytes:
    if not isinstance(value, str) or len(value) > ((max_bytes + 2) // 3) * 4:
        _fail("RESOURCE_LIMIT_REJECTED", f"{context} exceeds its base64 ceiling")
    try:
        payload = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exception:
        raise ExternalGateError("STRICT_SHAPE_REJECTED", f"{context} is not strict base64") from exception
    if len(payload) > max_bytes:
        _fail("RESOURCE_LIMIT_REJECTED", f"{context} exceeds its byte ceiling")
    return payload


def _policy_from_artifact(
    value: Any, *, replay_ledger: ReplayLedger
) -> ExternalTrustPolicy:
    chain = _exact_keys(
        value,
        {
            "allowed_algorithms",
            "max_receipt_age_seconds",
            "operator_authorizations",
            "revocation_snapshot",
            "signer_authorizations",
            "signing_certificates",
            "trust_root_provenance",
            "trust_roots",
        },
        "trust chain artifact",
    )
    if chain["trust_root_provenance"] != TRUST_ROOT_PROVENANCE:
        _fail("AUTHORITY_ESCALATION_REJECTED", "trust provenance drifted")
    root_values = chain["trust_roots"]
    certificate_values = chain["signing_certificates"]
    operator_values = chain["operator_authorizations"]
    signer_values = chain["signer_authorizations"]
    if (
        not isinstance(root_values, list)
        or not 1 <= len(root_values) <= MAX_TRUST_ROOTS
        or not isinstance(certificate_values, list)
        or len(certificate_values) != len(SIGNER_ROLE_ORDER)
        or not isinstance(operator_values, list)
        or len(operator_values) != 1
        or not isinstance(signer_values, list)
        or len(signer_values) != len(SIGNER_ROLE_ORDER)
    ):
        _fail("STRICT_SHAPE_REJECTED", "trust chain cardinality drifted")
    roots: list[TrustRoot] = []
    for raw in root_values:
        item = _exact_keys(
            raw,
            {
                "algorithm",
                "fingerprint_sha256",
                "not_after",
                "not_before",
                "public_key_pem_base64",
                "revoked_local_flag",
                "trust_root_id",
            },
            "trust root artifact",
        )
        roots.append(
            TrustRoot(
                trust_root_id=item["trust_root_id"],
                algorithm=item["algorithm"],
                public_key_pem=_decode_artifact_bytes(
                    item["public_key_pem_base64"],
                    context="trust root public key",
                    max_bytes=MAX_PUBLIC_KEY_PEM_BYTES,
                ),
                fingerprint_sha256=item["fingerprint_sha256"],
                not_before=_timestamp(item["not_before"], "trust root not_before"),
                not_after=_timestamp(item["not_after"], "trust root not_after"),
                revoked=item["revoked_local_flag"],
            )
        )

    certificates: list[SigningCertificate] = []
    certificate_fields = {
        "algorithm",
        "certificate_serial",
        "not_after",
        "not_before",
        "public_key_fingerprint_sha256",
        "schema_version",
        "signer_identity",
        "signer_role",
        "signing_key_id",
        "trust_root_id",
    }
    for raw in certificate_values:
        item = _exact_keys(
            raw,
            {"issuer_signature", "public_key_pem_base64", "revoked_local_flag", "signed_payload"},
            "signing certificate artifact",
        )
        payload = _exact_keys(item["signed_payload"], certificate_fields, "certificate payload")
        certificate = SigningCertificate(
            certificate_serial=payload["certificate_serial"],
            signing_key_id=payload["signing_key_id"],
            trust_root_id=payload["trust_root_id"],
            algorithm=payload["algorithm"],
            public_key_pem=_decode_artifact_bytes(
                item["public_key_pem_base64"],
                context="certificate public key",
                max_bytes=MAX_PUBLIC_KEY_PEM_BYTES,
            ),
            public_key_fingerprint_sha256=payload["public_key_fingerprint_sha256"],
            signer_identity=payload["signer_identity"],
            signer_role=payload["signer_role"],
            not_before=_timestamp(payload["not_before"], "certificate not_before"),
            not_after=_timestamp(payload["not_after"], "certificate not_after"),
            issuer_signature=item["issuer_signature"],
            revoked=item["revoked_local_flag"],
        )
        if not _strict_equal(payload, certificate_signed_payload(certificate)):
            _fail("ARTIFACT_SUBSTITUTION", "certificate signed payload drifted")
        certificates.append(certificate)

    authorization_fields = {
        "attempt_id",
        "attempt_scope_sha256",
        "authorization_reference",
        "candidate_sha",
        "cluster_id",
        "configuration_sha256",
        "control_ids",
        "deployment_manifest_sha256",
        "deployment_uid",
        "environment_identity",
        "expires_at",
        "namespace",
        "not_before",
        "region",
        "schema_version",
        "scope",
        "trust_root_id",
    }
    operators: list[OperatorAuthorization] = []
    for raw in operator_values:
        item = _exact_keys(
            raw,
            {"issuer_signature", "revoked_local_flag", "signed_payload"},
            "operator authorization artifact",
        )
        payload = _exact_keys(
            item["signed_payload"],
            authorization_fields | {"operator_identity"},
            "operator authorization payload",
        )
        authorization = OperatorAuthorization(
            authorization_reference=payload["authorization_reference"],
            operator_identity=payload["operator_identity"],
            trust_root_id=payload["trust_root_id"],
            scope=payload["scope"],
            candidate_sha=payload["candidate_sha"],
            configuration_sha256=payload["configuration_sha256"],
            environment_identity=payload["environment_identity"],
            namespace=payload["namespace"],
            cluster_id=payload["cluster_id"],
            region=payload["region"],
            deployment_manifest_sha256=payload["deployment_manifest_sha256"],
            deployment_uid=payload["deployment_uid"],
            attempt_scope_sha256=payload["attempt_scope_sha256"],
            attempt_id=payload["attempt_id"],
            control_ids=frozenset(payload["control_ids"]),
            not_before=_timestamp(payload["not_before"], "operator authorization not_before"),
            expires_at=_timestamp(payload["expires_at"], "operator authorization expires_at"),
            issuer_signature=item["issuer_signature"],
            revoked=item["revoked_local_flag"],
        )
        if not _strict_equal(payload, operator_authorization_signed_payload(authorization)):
            _fail("ARTIFACT_SUBSTITUTION", "operator authorization payload drifted")
        operators.append(authorization)

    signers: list[SignerAuthorization] = []
    signer_fields = authorization_fields | {
        "signer_identity",
        "signer_role",
        "signing_key_id",
    }
    for raw in signer_values:
        item = _exact_keys(
            raw,
            {"issuer_signature", "revoked_local_flag", "signed_payload"},
            "signer authorization artifact",
        )
        payload = _exact_keys(item["signed_payload"], signer_fields, "signer authorization payload")
        authorization = SignerAuthorization(
            authorization_reference=payload["authorization_reference"],
            signer_identity=payload["signer_identity"],
            signer_role=payload["signer_role"],
            signing_key_id=payload["signing_key_id"],
            trust_root_id=payload["trust_root_id"],
            scope=payload["scope"],
            candidate_sha=payload["candidate_sha"],
            configuration_sha256=payload["configuration_sha256"],
            environment_identity=payload["environment_identity"],
            namespace=payload["namespace"],
            cluster_id=payload["cluster_id"],
            region=payload["region"],
            deployment_manifest_sha256=payload["deployment_manifest_sha256"],
            deployment_uid=payload["deployment_uid"],
            attempt_scope_sha256=payload["attempt_scope_sha256"],
            attempt_id=payload["attempt_id"],
            control_ids=frozenset(payload["control_ids"]),
            not_before=_timestamp(payload["not_before"], "signer authorization not_before"),
            expires_at=_timestamp(payload["expires_at"], "signer authorization expires_at"),
            issuer_signature=item["issuer_signature"],
            revoked=item["revoked_local_flag"],
        )
        if not _strict_equal(payload, signer_authorization_signed_payload(authorization)):
            _fail("ARTIFACT_SUBSTITUTION", "signer authorization payload drifted")
        signers.append(authorization)

    raw_revocation = _exact_keys(
        chain["revocation_snapshot"],
        {"authority", "payload", "snapshot_sha256"},
        "revocation artifact",
    )
    revocation_payload_value = _exact_keys(
        raw_revocation["payload"],
        {
            "expires_at",
            "generated_at",
            "revoked_authorization_references",
            "revoked_certificate_serials",
            "revoked_root_fingerprints",
            "revoked_signing_key_fingerprints",
            "schema_version",
            "snapshot_id",
            "source",
            "trust_anchor_set_sha256",
        },
        "revocation snapshot payload",
    )
    revocation = RevocationSnapshot(
        schema_version=revocation_payload_value["schema_version"],
        snapshot_id=revocation_payload_value["snapshot_id"],
        source=revocation_payload_value["source"],
        generated_at=_timestamp(revocation_payload_value["generated_at"], "revocation generated_at"),
        expires_at=_timestamp(revocation_payload_value["expires_at"], "revocation expires_at"),
        trust_anchor_set_sha256=revocation_payload_value["trust_anchor_set_sha256"],
        revoked_root_fingerprints=frozenset(
            revocation_payload_value["revoked_root_fingerprints"]
        ),
        revoked_certificate_serials=frozenset(
            revocation_payload_value["revoked_certificate_serials"]
        ),
        revoked_signing_key_fingerprints=frozenset(
            revocation_payload_value["revoked_signing_key_fingerprints"]
        ),
        revoked_authorization_references=frozenset(
            revocation_payload_value["revoked_authorization_references"]
        ),
        snapshot_sha256=raw_revocation["snapshot_sha256"],
    )
    if (
        raw_revocation["authority"] != REVOCATION_STATUS
        or not _strict_equal(revocation_payload_value, revocation_snapshot_payload(revocation))
    ):
        _fail("ARTIFACT_SUBSTITUTION", "revocation snapshot artifact drifted")
    allowed = chain["allowed_algorithms"]
    if not isinstance(allowed, list) or allowed != sorted(set(allowed)):
        _fail("STRICT_SHAPE_REJECTED", "allowed algorithm artifact drifted")
    return ExternalTrustPolicy(
        trust_roots=tuple(roots),
        signing_certificates=tuple(certificates),
        operator_authorizations=tuple(operators),
        signer_authorizations=tuple(signers),
        allowed_algorithms=frozenset(allowed),
        max_receipt_age_seconds=chain["max_receipt_age_seconds"],
        revocation_snapshot=revocation,
        replay_ledger=replay_ledger,
    )


def _expected_context_from_artifact(
    value: Any, *, trust_roots: Sequence[TrustRoot]
) -> ExpectedExternalContext:
    context = _exact_keys(
        value,
        {
            "attempt_lineage",
            "authors",
            "authorization_edges",
            "candidate_path_blobs",
            "candidate_path_blobs_sha256",
            "candidate_sha",
            "candidate_tree_sha",
            "cluster_id",
            "configuration_sha256",
            "context_id",
            "context_sha256",
            "deployment_generation",
            "deployment_manifest_sha256",
            "deployment_resources",
            "deployment_uid",
            "environment_class",
            "environment_identity",
            "images",
            "mtls_edges",
            "namespace",
            "pinned_trust_anchors",
            "pinned_trust_anchors_sha256",
            "region",
            "target_workload_identities",
        },
        "validated context artifact",
    )
    lineage = _exact_keys(
        context["attempt_lineage"],
        {"attempt_id", "attempt_number", "checkpoint_id", "previous_attempt_id"},
        "attempt lineage artifact",
    )
    authors = _exact_keys(
        context["authors"], {"candidate", "evidence", "generator", "runner"}, "author artifact"
    )
    blobs = tuple(
        CandidatePathBlob(**_exact_keys(item, {"git_blob_sha", "mode", "path", "sha256", "status"}, "path blob artifact"))
        for item in context["candidate_path_blobs"]
    )
    images = tuple(
        ImageBinding(**_exact_keys(item, {"digest", "name"}, "image artifact"))
        for item in context["images"]
    )
    roots = {item.trust_root_id: item for item in trust_roots}
    pins: list[PinnedTrustAnchor] = []
    for raw in context["pinned_trust_anchors"]:
        item = _exact_keys(
            raw,
            {"algorithm", "fingerprint_sha256", "trust_root_id"},
            "pinned trust anchor artifact",
        )
        root = roots.get(item["trust_root_id"])
        if root is None:
            _fail("UNTRUSTED_ROOT", "pinned trust root is missing from chain")
        pins.append(
            PinnedTrustAnchor(
                trust_root_id=item["trust_root_id"],
                algorithm=item["algorithm"],
                public_key_pem=root.public_key_pem,
                fingerprint_sha256=item["fingerprint_sha256"],
            )
        )
    expected = ExpectedExternalContext(
        candidate_sha=context["candidate_sha"],
        candidate_tree_sha=context["candidate_tree_sha"],
        candidate_path_blobs=blobs,
        candidate_path_blobs_sha256=context["candidate_path_blobs_sha256"],
        configuration_sha256=context["configuration_sha256"],
        context_id=context["context_id"],
        context_sha256=context["context_sha256"],
        environment_identity=context["environment_identity"],
        environment_class=context["environment_class"],
        namespace=context["namespace"],
        cluster_id=context["cluster_id"],
        region=context["region"],
        deployment_manifest_sha256=context["deployment_manifest_sha256"],
        deployment_uid=context["deployment_uid"],
        deployment_generation=context["deployment_generation"],
        target_workload_identities=tuple(context["target_workload_identities"]),
        deployment_resources=tuple(context["deployment_resources"]),
        mtls_edges=tuple(context["mtls_edges"]),
        authorization_edges=tuple(context["authorization_edges"]),
        images=images,
        attempt_id=lineage["attempt_id"],
        attempt_number=lineage["attempt_number"],
        checkpoint_id=lineage["checkpoint_id"],
        previous_attempt_id=lineage["previous_attempt_id"],
        runner_identity=authors["runner"],
        generator_identity=authors["generator"],
        candidate_author_identity=authors["candidate"],
        evidence_author_identity=authors["evidence"],
        pinned_trust_anchors=tuple(pins),
    )
    normalized = _validate_expected_context(_snapshot_expected_context(expected))
    if (
        context["pinned_trust_anchors_sha256"] != normalized["trust_anchor_set_sha256"]
        or not _strict_equal(context, _expected_context_document(expected, normalized))
    ):
        _fail("ARTIFACT_SUBSTITUTION", "validated context reconstruction drifted")
    return expected


def _validate_pending_external_artifact(artifact: Mapping[str, Any]) -> dict[str, Any]:
    if type(artifact) is not dict or set(artifact) != PENDING_ARTIFACT_FIELDS:
        _fail("STRICT_SHAPE_REJECTED", "pending validation artifact fields drifted")
    seal = _sha(artifact["validation_artifact_sha256"], "validation artifact SHA-256")
    unsigned = dict(artifact)
    unsigned.pop("validation_artifact_sha256")
    if canonical_sha256(unsigned) != seal:
        _fail("ARTIFACT_SUBSTITUTION", "pending validation artifact self-seal drifted")
    required_state = {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "accepted": False,
        "authority_ceiling": EXTERNAL_AUTHORITY_CEILING,
        "classification": "EXTERNAL_GATE",
        "control_observation_provenance": CONTROL_OBSERVATION_PROVENANCE,
        "cryptographic_shape_validated": True,
        "evaluation_time_provenance": EVALUATION_TIME_PROVENANCE,
        "external_authenticity_verified": False,
        "external_security_preflight": "EXTERNAL_GATE",
        "freshness_status": "UNVERIFIED_PENDING_EXTERNAL",
        "local_integrity_is_authenticity": False,
        "production_checkpoint": "PENDING_EXTERNAL",
        "promotion_gate": "PENDING",
        "replay_durability": REPLAY_DURABILITY,
        "retry_allowed": False,
        "revocation_status": REVOCATION_STATUS,
        "schema_version": PENDING_ARTIFACT_SCHEMA_VERSION,
        "self_seal_purpose": ARTIFACT_SELF_SEAL_PURPOSE,
        "status": "PREFLIGHT_VALIDATED_PENDING_EXTERNAL_TRUST_REVOCATION_AND_DURABLE_REPLAY",
        "trust_root_provenance": TRUST_ROOT_PROVENANCE,
    }
    if any(not _strict_equal(artifact[field], value) for field, value in required_state.items()):
        _fail("AUTHORITY_ESCALATION_REJECTED", "pending validation authority ceiling drifted")
    control_bindings = artifact["control_evidence_bindings"]
    receipt_bindings = artifact["receipt_bindings"]
    if (
        not isinstance(control_bindings, list)
        or len(control_bindings) != len(REQUIRED_CONTROL_IDS)
        or not isinstance(receipt_bindings, list)
        or len(receipt_bindings) != len(REQUIRED_CONTROL_IDS) * len(SIGNER_ROLE_ORDER)
    ):
        _fail("STRICT_SHAPE_REJECTED", "artifact payload cardinality drifted")

    control_payloads: list[bytes] = []
    for raw_binding in control_bindings:
        binding = _exact_keys(
            raw_binding, CONTROL_ARTIFACT_BINDING_FIELDS, "control evidence binding"
        )
        payload = _decode_artifact_bytes(
            binding["source_payload_base64"],
            context="control evidence source payload",
            max_bytes=MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES,
        )
        document = parse_bounded_json_bytes(
            payload,
            context="artifact control evidence",
            max_bytes=MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES,
            max_depth=MAX_JSON_NESTING_DEPTH,
            max_tokens=MAX_JSON_TOKENS_PER_PAYLOAD,
            max_string_bytes=MAX_JSON_STRING_BYTES,
        )
        if (
            hashlib.sha256(payload).hexdigest() != binding["source_payload_sha256"]
            or canonical_json_bytes(document).decode("utf-8") != binding["canonical_bundle"]
            or canonical_sha256(document) != binding["canonical_bundle_sha256"]
        ):
            _fail("ARTIFACT_SUBSTITUTION", "control evidence payload binding drifted")
        control_payloads.append(payload)

    receipt_payloads: list[bytes] = []
    for raw_binding in receipt_bindings:
        binding = _exact_keys(
            raw_binding, RECEIPT_ARTIFACT_BINDING_FIELDS, "receipt binding"
        )
        payload = _decode_artifact_bytes(
            binding["source_payload_base64"],
            context="receipt source payload",
            max_bytes=MAX_RECEIPT_PAYLOAD_BYTES,
        )
        receipt = parse_bounded_json_bytes(
            payload,
            context="artifact signed receipt",
            max_bytes=MAX_RECEIPT_PAYLOAD_BYTES,
            max_depth=MAX_JSON_NESTING_DEPTH,
            max_tokens=MAX_JSON_TOKENS_PER_PAYLOAD,
            max_string_bytes=MAX_JSON_STRING_BYTES,
        )
        signed_payload = receipt_signed_payload(receipt)
        if (
            hashlib.sha256(payload).hexdigest() != binding["source_payload_sha256"]
            or canonical_json_bytes(signed_payload).decode("utf-8")
            != binding["canonical_signed_payload"]
            or receipt.get("signed_payload_sha256") != binding["signed_payload_sha256"]
            or receipt.get("signature") != binding["signature"]
            or receipt.get("receipt_sha256") != binding["receipt_sha256"]
            or receipt_sha256(receipt) != binding["receipt_sha256"]
        ):
            _fail("ARTIFACT_SUBSTITUTION", "signed receipt payload binding drifted")
        receipt_payloads.append(payload)

    audit_ledger = ReplayLedger()
    policy = _policy_from_artifact(artifact["trust_chain_artifact"], replay_ledger=audit_ledger)
    expected = _expected_context_from_artifact(
        artifact["validated_context"], trust_roots=policy.trust_roots
    )
    validation_policy = artifact["validation_policy"]
    if not isinstance(validation_policy, dict):
        _fail("STRICT_SHAPE_REJECTED", "validation policy artifact is not an object")
    evaluated_at = _timestamp(
        validation_policy.get("evaluation_instant"), "artifact evaluation instant"
    )
    validated = verify_external_gate_receipts(
        tuple(receipt_payloads),
        tuple(control_payloads),
        expected,
        policy,
        now=evaluated_at,
    )
    expected_control_bindings = [
        _control_binding_artifact(item) for item in validated.control_evidence
    ]
    expected_receipt_bindings = [
        _receipt_binding_artifact(item) for item in validated.receipts
    ]
    normalized = _validate_expected_context(validated.context)
    expected_context = _expected_context_document(validated.context, normalized)
    expected_receipt_set_preimage = {
        "context": expected_context,
        "control_evidence_hashes": [
            item.evidence_sha256 for item in validated.control_evidence
        ],
        "receipt_hashes": [item.receipt_sha256 for item in validated.receipts],
    }
    replay = _exact_keys(
        artifact["anti_replay_consumption"],
        {
            "attempt_id",
            "attempt_scope_sha256",
            "consumed_at",
            "nonces",
            "receipt_hashes",
            "receipt_set_sha256",
            "sequence",
        },
        "replay consumption artifact",
    )
    if (
        not isinstance(replay["sequence"], int)
        or isinstance(replay["sequence"], bool)
        or replay["sequence"] < 1
    ):
        _fail("ARTIFACT_SUBSTITUTION", "replay sequence is invalid")
    expected_replay = _replay_consumption_artifact(validated.replay_consumption)
    expected_replay["sequence"] = replay["sequence"]
    if (
        not _strict_equal(control_bindings, expected_control_bindings)
        or not _strict_equal(receipt_bindings, expected_receipt_bindings)
        or not _strict_equal(artifact["validated_context"], expected_context)
        or not _strict_equal(artifact["receipt_set_preimage"], expected_receipt_set_preimage)
        or artifact["receipt_set_sha256"] != validated.receipt_set_sha256
        or not _strict_equal(replay, expected_replay)
        or not _strict_equal(validation_policy, _validation_policy_artifact(validated))
        or not _strict_equal(artifact["trust_chain_artifact"], _trust_chain_artifact(policy))
        or artifact["trust_anchor_set_sha256"] != validated.trust_anchor_set_sha256
        or artifact["revocation_snapshot_sha256"] != validated.revocation_snapshot_sha256
    ):
        _fail("ARTIFACT_SUBSTITUTION", "deep validation artifact reconstruction drifted")
    return dict(artifact)


def _assert_pending_artifact_object_bounds(artifact: dict[str, Any]) -> None:
    stack: list[tuple[Any, int]] = [(artifact, 1)]
    seen_containers: set[int] = set()
    object_count = 0
    total_string_bytes = 0
    while stack:
        value, depth = stack.pop()
        object_count += 1
        if object_count > MAX_PENDING_ARTIFACT_OBJECTS or depth > MAX_PENDING_ARTIFACT_DEPTH:
            _fail("RESOURCE_LIMIT_REJECTED", "pending artifact object graph exceeds its ceiling")
        if type(value) in (dict, list):
            identity = id(value)
            if identity in seen_containers:
                _fail("STRICT_SHAPE_REJECTED", "pending artifact contains a cycle or alias")
            seen_containers.add(identity)
            if isinstance(value, dict):
                if any(type(key) is not str for key in value):
                    _fail("STRICT_SHAPE_REJECTED", "pending artifact keys must be strings")
                children = list(value.values())
                total_string_bytes += sum(len(key.encode("utf-8")) for key in value)
            else:
                children = value
            stack.extend((child, depth + 1) for child in children)
        elif type(value) is str:
            size = len(value.encode("utf-8"))
            if size > MAX_PENDING_ARTIFACT_STRING_BYTES:
                _fail("RESOURCE_LIMIT_REJECTED", "pending artifact string exceeds its ceiling")
            total_string_bytes += size
        elif type(value) is int:
            if value.bit_length() > 128:
                _fail("RESOURCE_LIMIT_REJECTED", "pending artifact integer exceeds its ceiling")
        elif value is not None and type(value) is not bool:
            _fail("STRICT_SHAPE_REJECTED", "pending artifact contains a non-JSON scalar")
        if total_string_bytes > MAX_PENDING_ARTIFACT_BYTES:
            _fail("RESOURCE_LIMIT_REJECTED", "pending artifact strings exceed total ceiling")


def pending_external_artifact_bytes(artifact: Mapping[str, Any]) -> bytes:
    if type(artifact) is not dict or set(artifact) != PENDING_ARTIFACT_FIELDS:
        _fail("STRICT_SHAPE_REJECTED", "pending validation artifact fields drifted")
    _assert_pending_artifact_object_bounds(artifact)
    try:
        payload = canonical_json_bytes(artifact)
    except (EvidenceValidationError, RecursionError, UnicodeError, ValueError) as exception:
        raise ExternalGateError("STRICT_SHAPE_REJECTED", "pending artifact is not canonical JSON") from exception
    if len(payload) > MAX_PENDING_ARTIFACT_BYTES:
        _fail("RESOURCE_LIMIT_REJECTED", "pending artifact exceeds its byte ceiling")
    return payload


def validate_pending_external_artifact(payload: bytes) -> dict[str, Any]:
    try:
        artifact = parse_bounded_json_bytes(
            payload,
            context="pending external validation artifact",
            max_bytes=MAX_PENDING_ARTIFACT_BYTES,
            max_depth=MAX_PENDING_ARTIFACT_DEPTH,
            max_tokens=MAX_PENDING_ARTIFACT_TOKENS,
            max_string_bytes=MAX_PENDING_ARTIFACT_STRING_BYTES,
        )
    except EvidenceValidationError as exception:
        message = str(exception)
        code = (
            "RESOURCE_LIMIT_REJECTED"
            if "immutable byte ceiling" in message or "oversized JSON string" in message
            else "STRICT_SHAPE_REJECTED"
        )
        raise ExternalGateError(code, message) from exception
    except (RecursionError, UnicodeError, ValueError) as exception:
        raise ExternalGateError(
            "STRICT_SHAPE_REJECTED", "pending validation artifact bytes are malformed"
        ) from exception
    try:
        return _validate_pending_external_artifact(artifact)
    except ExternalGateError:
        raise
    except EvidenceValidationError as exception:
        raise ExternalGateError("ARTIFACT_SUBSTITUTION", str(exception)) from exception
    except (
        AttributeError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
    ) as exception:
        raise ExternalGateError(
            "STRICT_SHAPE_REJECTED", "pending validation artifact is malformed"
        ) from exception


def _pending_authority() -> dict[str, str]:
    return {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "production_checkpoint": "PENDING_EXTERNAL",
        "promotion_gate": "PENDING",
    }


def intake_external_gate(
    receipt_payloads: Sequence[bytes],
    control_evidence_payloads: Sequence[bytes],
    expected: ExpectedExternalContext,
    trust_policy: ExternalTrustPolicy,
    *,
    now: datetime,
) -> dict[str, Any]:
    try:
        validated = verify_external_gate_receipts(
            receipt_payloads,
            control_evidence_payloads,
            expected,
            trust_policy,
            now=now,
        )
    except ExternalGateError as exception:
        return {
            **_pending_authority(),
            "accepted": False,
            "authority_ceiling": EXTERNAL_AUTHORITY_CEILING,
            "classification": "EXTERNAL_GATE",
            "control_observation_provenance": CONTROL_OBSERVATION_PROVENANCE,
            "cryptographic_shape_validated": False,
            "evaluation_time_provenance": EVALUATION_TIME_PROVENANCE,
            "external_authenticity_verified": False,
            "external_security_preflight": "EXTERNAL_GATE",
            "freshness_status": "UNVERIFIED_PENDING_EXTERNAL",
            "local_integrity_is_authenticity": False,
            "replay_durability": REPLAY_DURABILITY,
            "reason_code": exception.code,
            "revocation_status": REVOCATION_STATUS,
            "retry_allowed": False,
            "status": "REJECTED",
            "trust_root_provenance": TRUST_ROOT_PROVENANCE,
        }
    normalized = _validate_expected_context(validated.context)
    context_document = _expected_context_document(validated.context, normalized)
    receipt_set_preimage = {
        "context": copy.deepcopy(context_document),
        "control_evidence_hashes": [
            item.evidence_sha256 for item in validated.control_evidence
        ],
        "receipt_hashes": [item.receipt_sha256 for item in validated.receipts],
    }
    result = {
        **_pending_authority(),
        "accepted": False,
        "anti_replay_consumption": _replay_consumption_artifact(
            validated.replay_consumption
        ),
        "authority_ceiling": EXTERNAL_AUTHORITY_CEILING,
        "classification": "EXTERNAL_GATE",
        "control_evidence_bindings": [
            _control_binding_artifact(item) for item in validated.control_evidence
        ],
        "control_observation_provenance": validated.control_observation_provenance,
        "cryptographic_shape_validated": validated.cryptographic_shape_validated,
        "evaluation_time_provenance": validated.evaluation_time_provenance,
        "external_authenticity_verified": validated.external_authenticity_verified,
        "external_security_preflight": "EXTERNAL_GATE",
        "freshness_status": "UNVERIFIED_PENDING_EXTERNAL",
        "local_integrity_is_authenticity": False,
        "receipt_bindings": [
            _receipt_binding_artifact(item) for item in validated.receipts
        ],
        "receipt_set_preimage": receipt_set_preimage,
        "receipt_set_sha256": validated.receipt_set_sha256,
        "replay_durability": validated.replay_durability,
        "revocation_snapshot_sha256": validated.revocation_snapshot_sha256,
        "revocation_status": validated.revocation_status,
        "retry_allowed": False,
        "status": "PREFLIGHT_VALIDATED_PENDING_EXTERNAL_TRUST_REVOCATION_AND_DURABLE_REPLAY",
        "trust_anchor_set_sha256": validated.trust_anchor_set_sha256,
        "trust_chain_artifact": _trust_chain_artifact(validated.trust_policy),
        "trust_root_provenance": validated.trust_root_provenance,
        "validation_policy": _validation_policy_artifact(validated),
        "validated_context": context_document,
        "schema_version": PENDING_ARTIFACT_SCHEMA_VERSION,
        "self_seal_purpose": ARTIFACT_SELF_SEAL_PURPOSE,
    }
    result["validation_artifact_sha256"] = canonical_sha256(result)
    return result


__all__ = [
    "ARTIFACT_SELF_SEAL_PURPOSE",
    "ATTEMPT_SCOPE_SCHEMA_VERSION",
    "AUTHORIZATION_SCOPE",
    "CERTIFICATE_SCHEMA_VERSION",
    "CHECKPOINT_ORDER",
    "CONTROL_EVIDENCE_FIELDS",
    "CONTROL_EVIDENCE_SCHEMA_VERSION",
    "CONTROL_OBSERVATION_PROVENANCE",
    "CandidatePathBlob",
    "EVIDENCE_SOURCE",
    "EVALUATION_TIME_PROVENANCE",
    "EXTERNAL_AUTHORITY_CEILING",
    "ExpectedExternalContext",
    "ExternalGateError",
    "ExternalTrustPolicy",
    "FROZEN_RECEIPT_REQUIRED_FIELDS",
    "FROZEN_SIGNED_PAYLOAD_FIELDS",
    "ImageBinding",
    "MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES",
    "MAX_EXTERNAL_INTAKE_BYTES",
    "MAX_JSON_NESTING_DEPTH",
    "MAX_JSON_STRING_BYTES",
    "MAX_JSON_TOKENS_PER_PAYLOAD",
    "MAX_PENDING_ARTIFACT_BYTES",
    "MAX_PENDING_ARTIFACT_DEPTH",
    "MAX_PENDING_ARTIFACT_STRING_BYTES",
    "MAX_PENDING_ARTIFACT_TOKENS",
    "MAX_RECEIPT_PAYLOAD_BYTES",
    "OPERATOR_AUTHORIZATION_SCHEMA_VERSION",
    "OperatorAuthorization",
    "PinnedTrustAnchor",
    "PENDING_ARTIFACT_FIELDS",
    "PENDING_ARTIFACT_SCHEMA_VERSION",
    "RECEIPT_FIELDS",
    "RECEIPT_SCHEMA_VERSION",
    "REQUIRED_CONTROL_IDS",
    "ReplayConsumption",
    "ReplayLedger",
    "REPLAY_DURABILITY",
    "REVOCATION_SNAPSHOT_SCHEMA_VERSION",
    "REVOCATION_SNAPSHOT_SOURCE",
    "REVOCATION_STATUS",
    "RevocationSnapshot",
    "SCENARIO_ID",
    "SIGNED_PAYLOAD_FIELDS",
    "SIGNER_AUTHORIZATION_SCHEMA_VERSION",
    "SIGNER_ROLE_ORDER",
    "STEP_ID",
    "SignerAuthorization",
    "SigningCertificate",
    "TrustRoot",
    "TRUST_ROOT_PROVENANCE",
    "ValidatedControlEvidence",
    "ValidatedControlReceipt",
    "ValidatedPendingExternalReceiptSet",
    "calculate_attempt_scope_sha256",
    "certificate_signed_payload",
    "intake_external_gate",
    "operator_authorization_signed_payload",
    "pending_external_artifact_bytes",
    "receipt_sha256",
    "receipt_signed_payload",
    "revocation_snapshot_payload",
    "revocation_snapshot_sha256",
    "signer_authorization_signed_payload",
    "trust_anchor_set_payload",
    "trust_anchor_set_sha256",
    "validate_pending_external_artifact",
    "verify_external_gate_receipts",
]
