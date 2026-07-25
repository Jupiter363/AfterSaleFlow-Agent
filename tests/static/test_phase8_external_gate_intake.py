from __future__ import annotations

import ast
import base64
import copy
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError, dataclass, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

import pytest
import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, padding, rsa, utils

from scripts.phase8.candidate import evidence_schema
from scripts.phase8.candidate import external_gate_intake as gate
from scripts.phase8.candidate.evidence_schema import (
    canonical_json_bytes,
    canonical_sha256,
    public_key_fingerprint_sha256,
)


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "phase8" / "candidate" / "external_gate_intake.py"
EVIDENCE_SCHEMA_PATH = (
    ROOT / "scripts" / "phase8" / "candidate" / "evidence_schema.py"
)
SCENARIO_PATH = (
    ROOT / "infra-tests" / "phase8" / "scenarios" / "security-and-rotation.yaml"
)
NOW = datetime(2026, 7, 25, 12, 0, tzinfo=timezone.utc)
SHA_A = "a" * 40
SHA_B = "b" * 40
DIGEST_A = "a" * 64
DIGEST_B = "b" * 64
DIGEST_C = "c" * 64
DIGEST_D = "d" * 64
AUTHORS = {
    "runner_identity": "runner-independent",
    "generator_identity": "generator-independent",
    "candidate_author_identity": "candidate-author-independent",
    "evidence_author_identity": "evidence-author-independent",
}


@dataclass(frozen=True)
class SignedBundle:
    expected: gate.ExpectedExternalContext
    policy: gate.ExternalTrustPolicy
    control_evidence_documents: tuple[dict[str, Any], ...]
    control_evidence_payloads: tuple[bytes, ...]
    receipt_documents: tuple[dict[str, Any], ...]
    receipt_payloads: tuple[bytes, ...]
    root_private_key: ed25519.Ed25519PrivateKey
    signer_private_keys: dict[str, ed25519.Ed25519PrivateKey]


def _authoritative_scenario_contract() -> dict[str, Any]:
    document = yaml.safe_load(SCENARIO_PATH.read_text(encoding="utf-8"))
    assert isinstance(document, dict)
    return document


def _authoritative_controls() -> tuple[str, ...]:
    contract = _authoritative_scenario_contract()
    step = next(
        item
        for item in contract["ordered_steps"]
        if item["step_id"] == "EXTERNAL_SECURITY_PREFLIGHT"
    )
    return tuple(step["required_control_receipts"])


def _public_pem(private_key: ed25519.Ed25519PrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _rfc3339(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _root_signature(
    private_key: ed25519.Ed25519PrivateKey, payload: dict[str, Any]
) -> str:
    return _b64(private_key.sign(canonical_json_bytes(payload)))


def _attempt_scope_or_dummy(expected: gate.ExpectedExternalContext) -> str:
    try:
        return gate.calculate_attempt_scope_sha256(expected)
    except gate.ExternalGateError:
        return DIGEST_D


def _expected_context(**changes: Any) -> gate.ExpectedExternalContext:
    blobs = (
        gate.CandidatePathBlob(
            path="deploy/production/phase8/capacity-policy.yaml",
            mode="100644",
            git_blob_sha="c" * 40,
            sha256=DIGEST_C,
            status="MODIFIED",
        ),
        gate.CandidatePathBlob(
            path="deploy/production/phase8/security/workload-identities.yaml",
            mode="100644",
            git_blob_sha="d" * 40,
            sha256=DIGEST_D,
            status="ADDED",
        ),
    )
    values: dict[str, Any] = {
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "candidate_path_blobs": blobs,
        "candidate_path_blobs_sha256": canonical_sha256(
            [blob.as_dict() for blob in blobs]
        ),
        "configuration_sha256": DIGEST_A,
        "context_id": "phase8-release-context-001",
        "context_sha256": DIGEST_B,
        "environment_identity": "prod-primary-environment",
        "environment_class": "PRODUCTION",
        "namespace": "after-sale-flow-prod",
        "cluster_id": "cluster-primary-001",
        "region": "region-primary-001",
        "deployment_manifest_sha256": DIGEST_C,
        "deployment_uid": "deployment-uid-001",
        "deployment_generation": 7,
        "target_workload_identities": (
            "workload-java-api",
            "workload-otel-collector",
            "workload-python-agent",
        ),
        "deployment_resources": (
            "deployment-java-api",
            "deployment-otel-collector",
            "deployment-python-agent",
        ),
        "mtls_edges": (
            "edge-java-api_to_domain",
            "edge-python-agent_to_model-gateway",
        ),
        "authorization_edges": (
            "edge-java-api_to_domain",
            "edge-python-agent_to_model-gateway",
        ),
        "images": (
            gate.ImageBinding("after-sale-flow/java-api", f"sha256:{DIGEST_A}"),
            gate.ImageBinding("after-sale-flow/python-agent", f"sha256:{DIGEST_B}"),
        ),
        "attempt_id": "phase8-external-attempt-001",
        "attempt_number": 1,
        "checkpoint_id": "phase8-unified-checkpoint-001",
        "previous_attempt_id": None,
        **AUTHORS,
        "pinned_trust_anchors": (),
    }
    values.update(changes)
    return gate.ExpectedExternalContext(**values)


def _control_facts(
    control_id: str, expected: gate.ExpectedExternalContext
) -> dict[str, Any]:
    if control_id in gate.RUNTIME_BLOCKER_CONTROLS:
        return {
            "accepted": True,
            "actual_external": True,
            "control_id": control_id,
            "fact_kind": "RUNTIME_BLOCKER_ACCEPTANCE",
        }
    if control_id == gate.ISTIO_CRD_CONTROL:
        return {
            "actual_external": True,
            "api_version": "security.istio.io/v1",
            "crd_ready": True,
            "fact_kind": "ISTIO_CRD_READINESS",
        }
    if control_id == gate.ISTIO_DATAPLANE_CONTROL:
        return {
            "actual_external": True,
            "deployment_resources": list(expected.deployment_resources),
            "fact_kind": "ISTIO_DATAPLANE_INTERCEPTION",
            "intercepted_workloads": list(expected.target_workload_identities),
            "target_workloads": list(expected.target_workload_identities),
        }
    if control_id == gate.ISTIO_MTLS_CONTROL:
        return {
            "actual_external": True,
            "enforced_edges": list(expected.mtls_edges),
            "expected_edges": list(expected.mtls_edges),
            "fact_kind": "ISTIO_STRICT_MTLS_ENFORCEMENT",
            "mode": "STRICT",
        }
    if control_id == gate.ISTIO_AUTHZ_CONTROL:
        edges = list(expected.authorization_edges)
        return {
            "actual_external": True,
            "allowed_probe_edges": edges,
            "denied_probe_edges": edges,
            "enforced_edges": edges,
            "expected_edges": edges,
            "fact_kind": "ISTIO_AUTHORIZATION_ENFORCEMENT",
        }
    assert control_id == gate.OTEL_BINDING_CONTROL
    return {
        "actual_external": True,
        "binding_verified": True,
        "deployment_generation": expected.deployment_generation,
        "deployment_uid": expected.deployment_uid,
        "fact_kind": "I3_I4_OTEL_EXACT_BINDING",
        "labels": {
            "app.kubernetes.io/name": "otel-collector",
            "app.kubernetes.io/part-of": "after-sale-flow",
        },
        "namespace": expected.namespace,
        "otlp_ports": [4317, 4318],
        "service_account": "after-sale-otel-collector",
    }


def _issue_certificate(
    root_private: ed25519.Ed25519PrivateKey,
    signer_private: ed25519.Ed25519PrivateKey,
    *,
    role: str,
    index: int,
    signer_identity: str,
) -> gate.SigningCertificate:
    signer_pem = _public_pem(signer_private)
    unsigned = gate.SigningCertificate(
        certificate_serial=f"certificate-serial-{index:02d}",
        signing_key_id=f"signing-key-{index:02d}",
        trust_root_id="trust-root-001",
        algorithm="Ed25519",
        public_key_pem=signer_pem,
        public_key_fingerprint_sha256=public_key_fingerprint_sha256(signer_pem),
        signer_identity=signer_identity,
        signer_role=role,
        not_before=NOW - timedelta(days=1),
        not_after=NOW + timedelta(days=1),
        issuer_signature="pending",
    )
    return replace(
        unsigned,
        issuer_signature=_root_signature(
            root_private, gate.certificate_signed_payload(unsigned)
        ),
    )


def _issue_operator_authorization(
    root_private: ed25519.Ed25519PrivateKey,
    expected: gate.ExpectedExternalContext,
    operator_identity: str,
) -> gate.OperatorAuthorization:
    unsigned = gate.OperatorAuthorization(
        authorization_reference="operator-authorization-001",
        operator_identity=operator_identity,
        trust_root_id="trust-root-001",
        scope=gate.AUTHORIZATION_SCOPE,
        candidate_sha=expected.candidate_sha,
        configuration_sha256=expected.configuration_sha256,
        environment_identity=expected.environment_identity,
        namespace=expected.namespace,
        cluster_id=expected.cluster_id,
        region=expected.region,
        deployment_manifest_sha256=expected.deployment_manifest_sha256,
        deployment_uid=expected.deployment_uid,
        attempt_scope_sha256=_attempt_scope_or_dummy(expected),
        attempt_id=expected.attempt_id,
        control_ids=frozenset(gate.REQUIRED_CONTROL_IDS),
        not_before=NOW - timedelta(hours=1),
        expires_at=NOW + timedelta(hours=1),
        issuer_signature="pending",
    )
    return replace(
        unsigned,
        issuer_signature=_root_signature(
            root_private, gate.operator_authorization_signed_payload(unsigned)
        ),
    )


def _issue_signer_authorization(
    root_private: ed25519.Ed25519PrivateKey,
    expected: gate.ExpectedExternalContext,
    certificate: gate.SigningCertificate,
    *,
    index: int,
) -> gate.SignerAuthorization:
    unsigned = gate.SignerAuthorization(
        authorization_reference=f"signer-authorization-{index:02d}",
        signer_identity=certificate.signer_identity,
        signer_role=certificate.signer_role,
        signing_key_id=certificate.signing_key_id,
        trust_root_id=certificate.trust_root_id,
        scope=gate.AUTHORIZATION_SCOPE,
        candidate_sha=expected.candidate_sha,
        configuration_sha256=expected.configuration_sha256,
        environment_identity=expected.environment_identity,
        namespace=expected.namespace,
        cluster_id=expected.cluster_id,
        region=expected.region,
        deployment_manifest_sha256=expected.deployment_manifest_sha256,
        deployment_uid=expected.deployment_uid,
        attempt_scope_sha256=_attempt_scope_or_dummy(expected),
        attempt_id=expected.attempt_id,
        control_ids=frozenset(gate.REQUIRED_CONTROL_IDS),
        not_before=NOW - timedelta(hours=1),
        expires_at=NOW + timedelta(hours=1),
        issuer_signature="pending",
    )
    return replace(
        unsigned,
        issuer_signature=_root_signature(
            root_private, gate.signer_authorization_signed_payload(unsigned)
        ),
    )


def _revocation_snapshot(
    anchors: tuple[gate.PinnedTrustAnchor, ...],
) -> gate.RevocationSnapshot:
    unsigned = gate.RevocationSnapshot(
        schema_version=gate.REVOCATION_SNAPSHOT_SCHEMA_VERSION,
        snapshot_id="caller-revocation-snapshot-001",
        source=gate.REVOCATION_SNAPSHOT_SOURCE,
        generated_at=NOW - timedelta(minutes=2),
        expires_at=NOW + timedelta(minutes=30),
        trust_anchor_set_sha256=gate.trust_anchor_set_sha256(anchors),
        revoked_root_fingerprints=frozenset(),
        revoked_certificate_serials=frozenset(),
        revoked_signing_key_fingerprints=frozenset(),
        revoked_authorization_references=frozenset(),
        snapshot_sha256="pending",
    )
    return replace(
        unsigned,
        snapshot_sha256=gate.revocation_snapshot_sha256(unsigned),
    )


def _reseal_revocation_snapshot(
    snapshot: gate.RevocationSnapshot,
    **changes: Any,
) -> gate.RevocationSnapshot:
    changed = replace(snapshot, **changes, snapshot_sha256="pending")
    return replace(
        changed,
        snapshot_sha256=gate.revocation_snapshot_sha256(changed),
    )


def _signer_bindings(
    certificates: tuple[gate.SigningCertificate, ...],
    authorizations: tuple[gate.SignerAuthorization, ...],
) -> list[dict[str, str]]:
    return [
        {
            "authorization_reference": authorization.authorization_reference,
            "certificate_serial": certificate.certificate_serial,
            "role": certificate.signer_role,
            "signer_identity": certificate.signer_identity,
            "signing_key_id": certificate.signing_key_id,
            "trust_root_id": certificate.trust_root_id,
        }
        for certificate, authorization in zip(
            certificates, authorizations, strict=True
        )
    ]


def _metadata(
    control_index: int,
    expected: gate.ExpectedExternalContext,
    operator: gate.OperatorAuthorization,
    signer_bindings: list[dict[str, str]],
) -> dict[str, Any]:
    return {
        "attempt_id": expected.attempt_id,
        "attempt_number": expected.attempt_number,
        "authorization_edges": list(expected.authorization_edges),
        "authorization_reference": operator.authorization_reference,
        "authorization_scope": gate.AUTHORIZATION_SCOPE,
        "candidate_author_identity": expected.candidate_author_identity,
        "candidate_path_blobs": [
            item.as_dict() for item in expected.candidate_path_blobs
        ],
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
        "deployment_resources": list(expected.deployment_resources),
        "deployment_uid": expected.deployment_uid,
        "environment_class": expected.environment_class,
        "environment_identity": expected.environment_identity,
        "evidence_author_identity": expected.evidence_author_identity,
        "evidence_source": gate.EVIDENCE_SOURCE,
        "expires_at": _rfc3339(NOW + timedelta(minutes=30)),
        "generator_identity": expected.generator_identity,
        "images": [item.as_dict() for item in expected.images],
        "issued_at": _rfc3339(NOW - timedelta(seconds=30)),
        "mtls_edges": list(expected.mtls_edges),
        "namespace": expected.namespace,
        "nonce": (
            f"control-evidence-nonce-{control_index:02d}-{expected.attempt_id}-"
            + "N" * 24
        ),
        "observed_at": _rfc3339(NOW - timedelta(minutes=1)),
        "operator_identity": operator.operator_identity,
        "previous_attempt_id": expected.previous_attempt_id,
        "region": expected.region,
        "runner_identity": expected.runner_identity,
        "scenario_id": gate.SCENARIO_ID,
        "signer_authorizations": copy.deepcopy(signer_bindings),
        "step_id": gate.STEP_ID,
        "target_workload_identities": list(expected.target_workload_identities),
    }


def _control_evidence(
    control_id: str,
    control_index: int,
    expected: gate.ExpectedExternalContext,
    operator: gate.OperatorAuthorization,
    signer_bindings: list[dict[str, str]],
) -> dict[str, Any]:
    metadata = _metadata(control_index, expected, operator, signer_bindings)
    step = _control_facts(control_id, expected)
    stop = {"status": "NOT_TRIGGERED", "stop_condition_id": "NONE"}
    rollback = {
        "rollback_disposition": "NOT_REQUIRED",
        "status": "NOT_REQUIRED",
    }
    step_hash = canonical_sha256(step)
    stop_hash = canonical_sha256(stop)
    rollback_hash = canonical_sha256(rollback)
    manifest = {
        "control_id": control_id,
        "metadata_sha256": canonical_sha256(metadata),
        "rollback_evidence_sha256": rollback_hash,
        "schema_version": "phase8-external-control-evidence-manifest.v1",
        "step_evidence_sha256": step_hash,
        "stop_evidence_sha256": stop_hash,
    }
    return {
        "control_id": control_id,
        "evidence_manifest": manifest,
        "evidence_sha256": canonical_sha256(manifest),
        "metadata": metadata,
        "rollback_evidence": rollback,
        "rollback_evidence_sha256": rollback_hash,
        "schema_version": gate.CONTROL_EVIDENCE_SCHEMA_VERSION,
        "step_evidence": step,
        "step_evidence_sha256": step_hash,
        "stop_evidence": stop,
        "stop_evidence_sha256": stop_hash,
    }


def _seal_receipt(
    receipt: dict[str, Any], signer_private: ed25519.Ed25519PrivateKey
) -> dict[str, Any]:
    sealed = copy.deepcopy(receipt)
    sealed.pop("signed_payload_sha256", None)
    sealed.pop("signature", None)
    sealed.pop("receipt_sha256", None)
    signed_payload = gate.receipt_signed_payload(sealed)
    sealed["signed_payload_sha256"] = canonical_sha256(signed_payload)
    sealed["signature"] = _b64(
        signer_private.sign(canonical_json_bytes(signed_payload))
    )
    sealed["receipt_sha256"] = gate.receipt_sha256(sealed)
    return sealed


def _receipt(
    control_id: str,
    evidence: dict[str, Any],
    expected: gate.ExpectedExternalContext,
    operator: gate.OperatorAuthorization,
    certificate: gate.SigningCertificate,
    signer_private: ed25519.Ed25519PrivateKey,
) -> dict[str, Any]:
    metadata = evidence["metadata"]
    receipt: dict[str, Any] = {
        "schema_version": gate.RECEIPT_SCHEMA_VERSION,
        "control_id": control_id,
        "scenario_id": gate.SCENARIO_ID,
        "step_id": gate.STEP_ID,
        "checkpoint_order": gate.CHECKPOINT_ORDER,
        "claimed_result": "PASS",
        "status": "ACCEPTED",
        "candidate_sha": expected.candidate_sha,
        "candidate_tree_sha": expected.candidate_tree_sha,
        "configuration_sha256": expected.configuration_sha256,
        "context_id": expected.context_id,
        "context_sha256": expected.context_sha256,
        "environment_identity": expected.environment_identity,
        "deployment_manifest_sha256": expected.deployment_manifest_sha256,
        "images": [item.as_dict() for item in expected.images],
        "attempt_id": expected.attempt_id,
        "attempt_number": expected.attempt_number,
        "checkpoint_id": expected.checkpoint_id,
        "previous_attempt_id": expected.previous_attempt_id,
        "operator_identity": operator.operator_identity,
        "authorization_reference": operator.authorization_reference,
        "signer_identity": certificate.signer_identity,
        "signer_role": certificate.signer_role,
        "signature_algorithm": certificate.algorithm,
        "signing_key_id": certificate.signing_key_id,
        "trust_root_id": certificate.trust_root_id,
        "observed_at": metadata["observed_at"],
        "step_evidence_sha256": evidence["step_evidence_sha256"],
        "stop_condition_id": "NONE",
        "stop_evidence_sha256": evidence["stop_evidence_sha256"],
        "rollback_disposition": "NOT_REQUIRED",
        "rollback_evidence_sha256": evidence["rollback_evidence_sha256"],
        "evidence_sha256": evidence["evidence_sha256"],
    }
    return _seal_receipt(receipt, signer_private)


def _bundle(
    *,
    expected_changes: dict[str, Any] | None = None,
    signer_identities: tuple[str, ...] | None = None,
    operator_identity: str = "security-operator-independent",
) -> SignedBundle:
    root_private = ed25519.Ed25519PrivateKey.generate()
    root_pem = _public_pem(root_private)
    root = gate.TrustRoot(
        trust_root_id="trust-root-001",
        algorithm="Ed25519",
        public_key_pem=root_pem,
        fingerprint_sha256=public_key_fingerprint_sha256(root_pem),
        not_before=NOW - timedelta(days=2),
        not_after=NOW + timedelta(days=2),
    )
    anchor = gate.PinnedTrustAnchor(
        trust_root_id=root.trust_root_id,
        algorithm=root.algorithm,
        public_key_pem=root.public_key_pem,
        fingerprint_sha256=root.fingerprint_sha256,
    )
    context_changes = dict(expected_changes or {})
    context_changes.setdefault("pinned_trust_anchors", (anchor,))
    expected = _expected_context(**context_changes)
    identities = signer_identities or tuple(
        f"signer-{role.lower()}-independent" for role in gate.SIGNER_ROLE_ORDER
    )
    signer_private_keys: dict[str, ed25519.Ed25519PrivateKey] = {}
    certificates: list[gate.SigningCertificate] = []
    for index, (role, identity) in enumerate(
        zip(gate.SIGNER_ROLE_ORDER, identities, strict=True)
    ):
        private = ed25519.Ed25519PrivateKey.generate()
        signer_private_keys[role] = private
        certificates.append(
            _issue_certificate(
                root_private,
                private,
                role=role,
                index=index,
                signer_identity=identity,
            )
        )
    certificate_tuple = tuple(certificates)
    operator = _issue_operator_authorization(
        root_private, expected, operator_identity
    )
    signer_authorizations = tuple(
        _issue_signer_authorization(
            root_private,
            expected,
            certificate,
            index=index,
        )
        for index, certificate in enumerate(certificate_tuple)
    )
    bindings = _signer_bindings(certificate_tuple, signer_authorizations)
    evidence_documents = tuple(
        _control_evidence(
            control_id,
            index,
            expected,
            operator,
            bindings,
        )
        for index, control_id in enumerate(gate.REQUIRED_CONTROL_IDS)
    )
    receipt_documents = tuple(
        _receipt(
            control_id,
            evidence,
            expected,
            operator,
            certificate,
            signer_private_keys[certificate.signer_role],
        )
        for control_id, evidence in zip(
            gate.REQUIRED_CONTROL_IDS, evidence_documents, strict=True
        )
        for certificate in certificate_tuple
    )
    return SignedBundle(
        expected=expected,
        policy=gate.ExternalTrustPolicy(
            trust_roots=(root,),
            signing_certificates=certificate_tuple,
            operator_authorizations=(operator,),
            signer_authorizations=signer_authorizations,
            allowed_algorithms=frozenset({"Ed25519"}),
            max_receipt_age_seconds=300,
            revocation_snapshot=_revocation_snapshot(
                expected.pinned_trust_anchors
            ),
            replay_ledger=gate.ReplayLedger(),
        ),
        control_evidence_documents=evidence_documents,
        control_evidence_payloads=tuple(
            canonical_json_bytes(item) for item in evidence_documents
        ),
        receipt_documents=receipt_documents,
        receipt_payloads=tuple(canonical_json_bytes(item) for item in receipt_documents),
        root_private_key=root_private,
        signer_private_keys=signer_private_keys,
    )


def _bundle_reusing_authority(
    authority: SignedBundle,
    *,
    expected_changes: dict[str, Any],
) -> SignedBundle:
    context_changes = {
        "pinned_trust_anchors": authority.expected.pinned_trust_anchors,
        **expected_changes,
    }
    expected = _expected_context(**context_changes)
    certificates = authority.policy.signing_certificates
    operator = authority.policy.operator_authorizations[0]
    signer_authorizations = authority.policy.signer_authorizations
    bindings = _signer_bindings(certificates, signer_authorizations)
    evidence_documents = tuple(
        _control_evidence(
            control_id,
            index,
            expected,
            operator,
            bindings,
        )
        for index, control_id in enumerate(gate.REQUIRED_CONTROL_IDS)
    )
    receipt_documents = tuple(
        _receipt(
            control_id,
            evidence,
            expected,
            operator,
            certificate,
            authority.signer_private_keys[certificate.signer_role],
        )
        for control_id, evidence in zip(
            gate.REQUIRED_CONTROL_IDS, evidence_documents, strict=True
        )
        for certificate in certificates
    )
    return SignedBundle(
        expected=expected,
        policy=replace(authority.policy, replay_ledger=gate.ReplayLedger()),
        control_evidence_documents=evidence_documents,
        control_evidence_payloads=tuple(
            canonical_json_bytes(item) for item in evidence_documents
        ),
        receipt_documents=receipt_documents,
        receipt_payloads=tuple(canonical_json_bytes(item) for item in receipt_documents),
        root_private_key=authority.root_private_key,
        signer_private_keys=authority.signer_private_keys,
    )


def _replace_payload(
    payloads: tuple[bytes, ...], index: int, document: dict[str, Any]
) -> tuple[bytes, ...]:
    changed = list(payloads)
    changed[index] = canonical_json_bytes(document)
    return tuple(changed)


def _mutated_receipts(
    bundle: SignedBundle,
    mutation: Callable[[dict[str, Any]], None],
    *,
    index: int = 0,
    reseal: bool = False,
) -> tuple[bytes, ...]:
    document = copy.deepcopy(bundle.receipt_documents[index])
    mutation(document)
    if reseal:
        role = bundle.receipt_documents[index]["signer_role"]
        document = _seal_receipt(document, bundle.signer_private_keys[role])
    return _replace_payload(bundle.receipt_payloads, index, document)


def _mutated_evidence(
    bundle: SignedBundle,
    mutation: Callable[[dict[str, Any]], None],
    *,
    index: int = 0,
) -> tuple[bytes, ...]:
    document = copy.deepcopy(bundle.control_evidence_documents[index])
    mutation(document)
    return _replace_payload(bundle.control_evidence_payloads, index, document)


def _reseal_evidence(document: dict[str, Any]) -> dict[str, Any]:
    sealed = copy.deepcopy(document)
    sealed["step_evidence_sha256"] = canonical_sha256(sealed["step_evidence"])
    sealed["stop_evidence_sha256"] = canonical_sha256(sealed["stop_evidence"])
    sealed["rollback_evidence_sha256"] = canonical_sha256(
        sealed["rollback_evidence"]
    )
    manifest = {
        "control_id": sealed["control_id"],
        "metadata_sha256": canonical_sha256(sealed["metadata"]),
        "rollback_evidence_sha256": sealed["rollback_evidence_sha256"],
        "schema_version": "phase8-external-control-evidence-manifest.v1",
        "step_evidence_sha256": sealed["step_evidence_sha256"],
        "stop_evidence_sha256": sealed["stop_evidence_sha256"],
    }
    sealed["evidence_manifest"] = manifest
    sealed["evidence_sha256"] = canonical_sha256(manifest)
    return sealed


def _rebind_control_group(
    bundle: SignedBundle,
    control_index: int,
    evidence_document: dict[str, Any],
    *,
    receipt_overrides: dict[str, Any] | None = None,
) -> tuple[tuple[bytes, ...], tuple[bytes, ...]]:
    evidence_document = _reseal_evidence(evidence_document)
    evidence_payloads = _replace_payload(
        bundle.control_evidence_payloads,
        control_index,
        evidence_document,
    )
    receipt_payloads = list(bundle.receipt_payloads)
    start = control_index * len(gate.SIGNER_ROLE_ORDER)
    for role_index, role in enumerate(gate.SIGNER_ROLE_ORDER):
        receipt_index = start + role_index
        receipt = copy.deepcopy(bundle.receipt_documents[receipt_index])
        receipt.update(
            {
                "step_evidence_sha256": evidence_document[
                    "step_evidence_sha256"
                ],
                "stop_evidence_sha256": evidence_document[
                    "stop_evidence_sha256"
                ],
                "rollback_evidence_sha256": evidence_document[
                    "rollback_evidence_sha256"
                ],
                "evidence_sha256": evidence_document["evidence_sha256"],
                **(receipt_overrides or {}),
            }
        )
        receipt_payloads[receipt_index] = canonical_json_bytes(
            _seal_receipt(receipt, bundle.signer_private_keys[role])
        )
    return tuple(receipt_payloads), evidence_payloads


def _assert_external_gate(
    receipt_payloads: tuple[bytes, ...] | list[bytes],
    evidence_payloads: tuple[bytes, ...] | list[bytes],
    expected: gate.ExpectedExternalContext,
    policy: gate.ExternalTrustPolicy,
    *,
    expected_code: str | None = None,
) -> None:
    verification_policy = replace(policy, replay_ledger=gate.ReplayLedger())
    with pytest.raises(gate.ExternalGateError) as direct_error:
        gate.verify_external_gate_receipts(
            receipt_payloads,
            evidence_payloads,
            expected,
            verification_policy,
            now=NOW,
        )
    intake_policy = replace(policy, replay_ledger=gate.ReplayLedger())
    result = gate.intake_external_gate(
        receipt_payloads,
        evidence_payloads,
        expected,
        intake_policy,
        now=NOW,
    )
    assert result["accepted"] is False
    assert result["classification"] == "EXTERNAL_GATE"
    assert result["external_security_preflight"] == "EXTERNAL_GATE"
    assert result["status"] == "REJECTED"
    assert result["reason_code"] == direct_error.value.code
    if expected_code is None:
        assert direct_error.value.code != "ANTI_REPLAY_REJECTED"
    else:
        assert direct_error.value.code == expected_code
    assert result["retry_allowed"] is False
    assert result["production_checkpoint"] == "PENDING_EXTERNAL"
    assert result["promotion_gate"] == "PENDING"
    assert result["MIG-006"] == "PENDING_PROMOTION"
    assert result["MIG-007"] == "PENDING_PROMOTION"
    assert result["MIG-008"] == "PENDING_PROMOTION"


def _resign_operator(
    bundle: SignedBundle, authorization: gate.OperatorAuthorization
) -> gate.OperatorAuthorization:
    return replace(
        authorization,
        issuer_signature=_root_signature(
            bundle.root_private_key,
            gate.operator_authorization_signed_payload(authorization),
        ),
    )


def _resign_signer(
    bundle: SignedBundle, authorization: gate.SignerAuthorization
) -> gate.SignerAuthorization:
    return replace(
        authorization,
        issuer_signature=_root_signature(
            bundle.root_private_key,
            gate.signer_authorization_signed_payload(authorization),
        ),
    )


def _reseal_pending_artifact(artifact: dict[str, Any]) -> dict[str, Any]:
    artifact.pop("validation_artifact_sha256", None)
    artifact["validation_artifact_sha256"] = canonical_sha256(artifact)
    return artifact


def _assert_pending_artifact_rejected(artifact: dict[str, Any]) -> None:
    payload = canonical_json_bytes(artifact)
    with pytest.raises(gate.ExternalGateError) as failure:
        gate.validate_pending_external_artifact(payload)
    assert failure.value.code in {
        "ANTI_REPLAY_REJECTED",
        "ARTIFACT_SUBSTITUTION",
        "AUTHORIZATION_SCOPE_REJECTED",
        "AUTHORITY_ESCALATION_REJECTED",
        "CONTROL_SET_REJECTED",
        "CRYPTO_OR_ENCODING_REJECTED",
        "EVIDENCE_HASH_MISMATCH",
        "INVALID_SIGNATURE",
        "INVALID_TRUST_POLICY",
        "MIXED_RELEASE_CONTEXT",
        "SIX_ROLE_ENVELOPE_REJECTED",
        "STRICT_SHAPE_REJECTED",
        "UNTRUSTED_AUTHORIZATION",
        "UNTRUSTED_KEY",
        "UNTRUSTED_ROOT",
        "UNTRUSTED_SIGNER",
    }


@pytest.fixture(scope="module")
def pending_artifact_pair() -> tuple[dict[str, Any], dict[str, Any]]:
    first = _bundle()
    second = _bundle(
        expected_changes={
            "attempt_id": "phase8-external-attempt-artifact-002",
            "candidate_sha": "e" * 40,
            "context_id": "phase8-release-context-002",
        }
    )
    second_policy = replace(second.policy, max_receipt_age_seconds=301)
    first_artifact = gate.intake_external_gate(
        first.receipt_payloads,
        first.control_evidence_payloads,
        first.expected,
        first.policy,
        now=NOW,
    )
    second_artifact = gate.intake_external_gate(
        second.receipt_payloads,
        second.control_evidence_payloads,
        second.expected,
        second_policy,
        now=NOW,
    )
    assert first_artifact["cryptographic_shape_validated"] is True
    assert second_artifact["cryptographic_shape_validated"] is True
    return first_artifact, second_artifact


def test_top_receipt_shape_is_exactly_the_frozen_scenario_contract() -> None:
    contract = _authoritative_scenario_contract()
    evidence = contract["evidence_contract"]
    receipt_fields = evidence["immutable_receipt_required_fields"]
    signed_fields = evidence["signed_payload_exact_fields"]
    assert len(receipt_fields) == 36
    assert len(signed_fields) == 33
    assert tuple(gate.FROZEN_RECEIPT_REQUIRED_FIELDS) == tuple(receipt_fields)
    assert tuple(gate.FROZEN_SIGNED_PAYLOAD_FIELDS) == tuple(signed_fields)
    assert set(gate.RECEIPT_FIELDS) == set(receipt_fields)
    assert set(gate.SIGNED_PAYLOAD_FIELDS) == set(signed_fields)
    assert contract["signature_contract"]["required_count"] == 6
    assert tuple(contract["signature_contract"]["required_roles"]) == (
        gate.SIGNER_ROLE_ORDER
    )
    assert _authoritative_controls() == gate.REQUIRED_CONTROL_IDS
    assert len(_authoritative_controls()) == 10


def test_real_ed25519_six_role_envelope_verifies_preflight_only() -> None:
    bundle = _bundle()
    verified = gate.verify_external_gate_receipts(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        now=NOW,
    )
    assert isinstance(verified, gate.ValidatedPendingExternalReceiptSet)
    assert verified.context == bundle.expected
    assert verified.context is not bundle.expected
    assert verified.trust_policy is not bundle.policy
    assert verified.authority_ceiling == gate.EXTERNAL_AUTHORITY_CEILING
    assert verified.cryptographic_shape_validated is True
    assert verified.external_authenticity_verified is False
    assert verified.replay_durability == gate.REPLAY_DURABILITY
    assert verified.trust_root_provenance == gate.TRUST_ROOT_PROVENANCE
    assert verified.revocation_status == gate.REVOCATION_STATUS
    assert verified.evaluation_time_provenance == gate.EVALUATION_TIME_PROVENANCE
    assert (
        verified.control_observation_provenance
        == gate.CONTROL_OBSERVATION_PROVENANCE
    )
    assert verified.trust_anchor_set_sha256 == gate.trust_anchor_set_sha256(
        bundle.expected.pinned_trust_anchors
    )
    assert verified.revocation_snapshot_sha256 == (
        bundle.policy.revocation_snapshot.snapshot_sha256
    )
    assert len(verified.receipts) == 60
    assert len(verified.control_evidence) == 10
    assert [item.control_id for item in verified.control_evidence] == list(
        gate.REQUIRED_CONTROL_IDS
    )
    for index, control_id in enumerate(gate.REQUIRED_CONTROL_IDS):
        group = verified.receipts[index * 6 : (index + 1) * 6]
        source_group = bundle.receipt_documents[index * 6 : (index + 1) * 6]
        assert [item.control_id for item in group] == [control_id] * 6
        assert [item.signer_role for item in group] == list(gate.SIGNER_ROLE_ORDER)
        assert len({item.signer_identity.casefold() for item in group}) == 6
        assert len(
            {
                (
                    item["step_evidence_sha256"],
                    item["stop_evidence_sha256"],
                    item["rollback_evidence_sha256"],
                    item["evidence_sha256"],
                )
                for item in source_group
            }
        ) == 1
    assert len(verified.receipt_set_sha256) == 64
    assert verified.replay_consumption.sequence == 1
    assert verified.replay_consumption.attempt_id == bundle.expected.attempt_id

    intake_bundle = _bundle()
    result = gate.intake_external_gate(
        intake_bundle.receipt_payloads,
        intake_bundle.control_evidence_payloads,
        intake_bundle.expected,
        intake_bundle.policy,
        now=NOW,
    )
    assert result["accepted"] is False
    assert result["classification"] == "EXTERNAL_GATE"
    assert result["external_security_preflight"] == "EXTERNAL_GATE"
    assert result["status"] == (
        "PREFLIGHT_VALIDATED_PENDING_EXTERNAL_TRUST_REVOCATION_AND_DURABLE_REPLAY"
    )
    assert set(result) == set(gate.PENDING_ARTIFACT_FIELDS)
    assert result["schema_version"] == gate.PENDING_ARTIFACT_SCHEMA_VERSION
    assert result["self_seal_purpose"] == gate.ARTIFACT_SELF_SEAL_PURPOSE
    unsigned_artifact = dict(result)
    seal = unsigned_artifact.pop("validation_artifact_sha256")
    assert canonical_sha256(unsigned_artifact) == seal
    validated_artifact = gate.validate_pending_external_artifact(
        gate.pending_external_artifact_bytes(copy.deepcopy(result))
    )
    assert validated_artifact == result
    assert validated_artifact is not result
    assert result["retry_allowed"] is False
    assert result["authority_ceiling"] == gate.EXTERNAL_AUTHORITY_CEILING
    assert result["cryptographic_shape_validated"] is True
    assert result["external_authenticity_verified"] is False
    assert result["replay_durability"] == gate.REPLAY_DURABILITY
    assert result["trust_root_provenance"] == gate.TRUST_ROOT_PROVENANCE
    assert result["revocation_status"] == gate.REVOCATION_STATUS
    assert result["freshness_status"] == "UNVERIFIED_PENDING_EXTERNAL"
    assert result["evaluation_time_provenance"] == gate.EVALUATION_TIME_PROVENANCE
    assert (
        result["control_observation_provenance"]
        == gate.CONTROL_OBSERVATION_PROVENANCE
    )
    assert result["production_checkpoint"] == "PENDING_EXTERNAL"
    assert result["promotion_gate"] == "PENDING"
    assert result["MIG-006"] == "PENDING_PROMOTION"
    assert result["MIG-007"] == "PENDING_PROMOTION"
    assert result["MIG-008"] == "PENDING_PROMOTION"
    assert result["local_integrity_is_authenticity"] is False
    context = result["validated_context"]
    assert context["candidate_sha"] == intake_bundle.expected.candidate_sha
    assert context["candidate_tree_sha"] == intake_bundle.expected.candidate_tree_sha
    assert context["configuration_sha256"] == intake_bundle.expected.configuration_sha256
    assert context["context_id"] == intake_bundle.expected.context_id
    assert context["context_sha256"] == intake_bundle.expected.context_sha256
    assert context["environment_identity"] == intake_bundle.expected.environment_identity
    assert context["deployment_manifest_sha256"] == (
        intake_bundle.expected.deployment_manifest_sha256
    )
    assert context["deployment_uid"] == intake_bundle.expected.deployment_uid
    assert context["deployment_generation"] == intake_bundle.expected.deployment_generation
    assert context["images"] == [item.as_dict() for item in intake_bundle.expected.images]
    assert context["attempt_lineage"] == {
        "attempt_id": intake_bundle.expected.attempt_id,
        "attempt_number": 1,
        "checkpoint_id": intake_bundle.expected.checkpoint_id,
        "previous_attempt_id": None,
    }
    assert context["pinned_trust_anchors"] == [
        {
            "algorithm": anchor.algorithm,
            "fingerprint_sha256": anchor.fingerprint_sha256,
            "trust_root_id": anchor.trust_root_id,
        }
        for anchor in intake_bundle.expected.pinned_trust_anchors
    ]
    assert context["pinned_trust_anchors_sha256"] == (
        gate.trust_anchor_set_sha256(intake_bundle.expected.pinned_trust_anchors)
    )

    evidence_bindings = result["control_evidence_bindings"]
    assert len(evidence_bindings) == 10
    assert [item["control_id"] for item in evidence_bindings] == list(
        gate.REQUIRED_CONTROL_IDS
    )
    assert all(
        set(item)
        == {
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
        for item in evidence_bindings
    )
    for source_payload, source_document, binding in zip(
        intake_bundle.control_evidence_payloads,
        intake_bundle.control_evidence_documents,
        evidence_bindings,
        strict=True,
    ):
        canonical_bundle = json.loads(binding["canonical_bundle"])
        assert canonical_bundle == source_document
        assert canonical_json_bytes(canonical_bundle).decode("utf-8") == (
            binding["canonical_bundle"]
        )
        assert canonical_sha256(canonical_bundle["step_evidence"]) == (
            binding["step_evidence_sha256"]
        )
        assert canonical_sha256(canonical_bundle["stop_evidence"]) == (
            binding["stop_evidence_sha256"]
        )
        assert canonical_sha256(canonical_bundle["rollback_evidence"]) == (
            binding["rollback_evidence_sha256"]
        )
        manifest = canonical_bundle["evidence_manifest"]
        assert manifest["step_evidence_sha256"] == binding["step_evidence_sha256"]
        assert manifest["stop_evidence_sha256"] == binding["stop_evidence_sha256"]
        assert manifest["rollback_evidence_sha256"] == (
            binding["rollback_evidence_sha256"]
        )
        assert canonical_sha256(manifest) == binding["evidence_sha256"]
        assert canonical_sha256(canonical_bundle) == binding["canonical_bundle_sha256"]
        assert hashlib.sha256(source_payload).hexdigest() == (
            binding["source_payload_sha256"]
        )
        assert base64.b64decode(
            binding["source_payload_base64"], validate=True
        ) == source_payload

    assert len(result["receipt_bindings"]) == 60
    for source_payload, source_document, binding in zip(
        intake_bundle.receipt_payloads,
        intake_bundle.receipt_documents,
        result["receipt_bindings"],
        strict=True,
    ):
        assert base64.b64decode(
            binding["source_payload_base64"], validate=True
        ) == source_payload
        assert hashlib.sha256(source_payload).hexdigest() == (
            binding["source_payload_sha256"]
        )
        assert json.loads(source_payload) == source_document
        signed_payload = json.loads(binding["canonical_signed_payload"])
        assert canonical_json_bytes(signed_payload).decode("utf-8") == (
            binding["canonical_signed_payload"]
        )
        assert canonical_sha256(signed_payload) == binding["signed_payload_sha256"]
        receipt_preimage = {
            **signed_payload,
            "signature": binding["signature"],
            "signed_payload_sha256": binding["signed_payload_sha256"],
        }
        assert canonical_sha256(receipt_preimage) == binding["receipt_sha256"]
        intake_bundle.signer_private_keys[binding["signer_role"]].public_key().verify(
            base64.b64decode(binding["signature"]),
            canonical_json_bytes(signed_payload),
        )

    expected_receipt_set_preimage = {
        "context": context,
        "control_evidence_hashes": [
            item["evidence_sha256"] for item in evidence_bindings
        ],
        "receipt_hashes": [
            item["receipt_sha256"] for item in result["receipt_bindings"]
        ],
    }
    assert result["receipt_set_preimage"] == expected_receipt_set_preimage
    assert canonical_sha256(result["receipt_set_preimage"]) == (
        result["receipt_set_sha256"]
    )
    assert result["receipt_set_sha256"] == result["anti_replay_consumption"][
        "receipt_set_sha256"
    ]
    assert result["anti_replay_consumption"]["attempt_scope_sha256"] == (
        gate.calculate_attempt_scope_sha256(intake_bundle.expected)
    )
    assert result["anti_replay_consumption"]["consumed_at"] == _rfc3339(NOW)

    validation_policy = result["validation_policy"]
    assert validation_policy == {
        "evaluation_instant": _rfc3339(NOW),
        "evaluation_time_provenance": gate.EVALUATION_TIME_PROVENANCE,
        "freshness_status": "UNVERIFIED_PENDING_EXTERNAL",
        "max_control_evidence_payload_bytes": gate.MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES,
        "max_external_intake_bytes": gate.MAX_EXTERNAL_INTAKE_BYTES,
        "max_json_nesting_depth": gate.MAX_JSON_NESTING_DEPTH,
        "max_json_string_bytes": gate.MAX_JSON_STRING_BYTES,
        "max_json_tokens_per_payload": gate.MAX_JSON_TOKENS_PER_PAYLOAD,
        "max_receipt_age_seconds": intake_bundle.policy.max_receipt_age_seconds,
        "max_receipt_payload_bytes": gate.MAX_RECEIPT_PAYLOAD_BYTES,
    }

    trust_artifact = result["trust_chain_artifact"]
    assert len(trust_artifact["trust_roots"]) == 1
    assert len(trust_artifact["signing_certificates"]) == 6
    assert len(trust_artifact["operator_authorizations"]) == 1
    assert len(trust_artifact["signer_authorizations"]) == 6
    assert trust_artifact["trust_root_provenance"] == gate.TRUST_ROOT_PROVENANCE
    assert trust_artifact["allowed_algorithms"] == ["Ed25519"]
    assert trust_artifact["max_receipt_age_seconds"] == (
        intake_bundle.policy.max_receipt_age_seconds
    )
    assert trust_artifact["revocation_snapshot"] == {
        "authority": gate.REVOCATION_STATUS,
        "payload": gate.revocation_snapshot_payload(
            intake_bundle.policy.revocation_snapshot
        ),
        "snapshot_sha256": result["revocation_snapshot_sha256"],
    }
    for collection in (
        "signing_certificates",
        "operator_authorizations",
        "signer_authorizations",
    ):
        for authority in trust_artifact[collection]:
            intake_bundle.root_private_key.public_key().verify(
                base64.b64decode(authority["issuer_signature"]),
                canonical_json_bytes(authority["signed_payload"]),
            )
    for root in trust_artifact["trust_roots"]:
        public_key_pem = base64.b64decode(
            root["public_key_pem_base64"], validate=True
        )
        assert public_key_fingerprint_sha256(public_key_pem) == (
            root["fingerprint_sha256"]
        )
        assert serialization.load_pem_public_key(public_key_pem).public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ) == public_key_pem
    for certificate in trust_artifact["signing_certificates"]:
        public_key_pem = base64.b64decode(
            certificate["public_key_pem_base64"], validate=True
        )
        assert public_key_fingerprint_sha256(public_key_pem) == (
            certificate["signed_payload"]["public_key_fingerprint_sha256"]
        )
        assert serialization.load_pem_public_key(public_key_pem).public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ) == public_key_pem
    assert result["trust_anchor_set_sha256"] == (
        gate.trust_anchor_set_sha256(intake_bundle.expected.pinned_trust_anchors)
    )
    assert result["revocation_snapshot_sha256"] == (
        intake_bundle.policy.revocation_snapshot.snapshot_sha256
    )
    assert all(
        value != "PASS"
        for value in result.values()
        if isinstance(value, str)
    )


def test_strict_json_rejects_duplicate_unknown_partial_and_nonfinite_values() -> None:
    bundle = _bundle()
    duplicate = b'{"control_id":"aliased-control",' + bundle.receipt_payloads[0][1:]
    receipts = list(bundle.receipt_payloads)
    receipts[0] = duplicate
    _assert_external_gate(
        receipts, bundle.control_evidence_payloads, bundle.expected, bundle.policy
    )
    for mutation in (
        lambda value: value.__setitem__("unknown", True),
        lambda value: value.pop("candidate_sha"),
    ):
        _assert_external_gate(
            _mutated_receipts(bundle, mutation),
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
        )
    evidence_duplicate = (
        b'{"control_id":"aliased-control",' + bundle.control_evidence_payloads[0][1:]
    )
    evidence_payloads = list(bundle.control_evidence_payloads)
    evidence_payloads[0] = evidence_duplicate
    _assert_external_gate(
        bundle.receipt_payloads, evidence_payloads, bundle.expected, bundle.policy
    )
    nonfinite = copy.deepcopy(bundle.control_evidence_documents[0])
    nonfinite["metadata"]["attempt_number"] = float("nan")
    evidence_payloads = list(bundle.control_evidence_payloads)
    evidence_payloads[0] = json.dumps(nonfinite, allow_nan=True).encode("utf-8")
    _assert_external_gate(
        bundle.receipt_payloads, evidence_payloads, bundle.expected, bundle.policy
    )


@pytest.mark.parametrize("payload_kind", ["receipt", "control_evidence"])
def test_individual_payload_byte_ceilings_are_fail_closed(payload_kind: str) -> None:
    bundle = _bundle()
    receipts = bundle.receipt_payloads
    evidence = bundle.control_evidence_payloads
    if payload_kind == "receipt":
        receipts = (
            b" " * (gate.MAX_RECEIPT_PAYLOAD_BYTES + 1),
            *receipts[1:],
        )
    else:
        evidence = (
            b" " * (gate.MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES + 1),
            *evidence[1:],
        )
    _assert_external_gate(
        receipts,
        evidence,
        bundle.expected,
        bundle.policy,
        expected_code="RESOURCE_LIMIT_REJECTED",
    )


def test_aggregate_external_intake_byte_ceiling_is_fail_closed() -> None:
    bundle = _bundle()
    receipts = tuple(
        payload
        + b" " * (gate.MAX_RECEIPT_PAYLOAD_BYTES - len(payload))
        for payload in bundle.receipt_payloads
    )
    evidence = tuple(
        payload
        + b" " * (gate.MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES - len(payload))
        for payload in bundle.control_evidence_payloads
    )
    assert all(len(item) == gate.MAX_RECEIPT_PAYLOAD_BYTES for item in receipts)
    assert all(
        len(item) == gate.MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES for item in evidence
    )
    assert sum(map(len, receipts)) + sum(map(len, evidence)) > (
        gate.MAX_EXTERNAL_INTAKE_BYTES
    )
    _assert_external_gate(
        receipts,
        evidence,
        bundle.expected,
        bundle.policy,
        expected_code="RESOURCE_LIMIT_REJECTED",
    )


@pytest.mark.parametrize("container", ["list", "object"])
def test_json_nesting_ceiling_rejects_deep_containers(container: str) -> None:
    bundle = _bundle()
    nesting = gate.MAX_JSON_NESTING_DEPTH + 1
    if container == "list":
        payload = b"[" * nesting + b"0" + b"]" * nesting
    else:
        payload = b'{"x":' * nesting + b"0" + b"}" * nesting
    assert len(payload) < gate.MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES
    evidence = (payload, *bundle.control_evidence_payloads[1:])
    _assert_external_gate(
        bundle.receipt_payloads,
        evidence,
        bundle.expected,
        bundle.policy,
        expected_code="STRICT_SHAPE_REJECTED",
    )


def test_json_string_and_token_ceilings_reject_parser_bombs() -> None:
    bundle = _bundle()
    oversized_string = (
        b'{"x":"'
        + b"a" * (gate.MAX_JSON_STRING_BYTES + 1)
        + b'"}'
    )
    token_bomb = (
        b"["
        + b",".join(b"0" for _ in range(gate.MAX_JSON_TOKENS_PER_PAYLOAD + 1))
        + b"]"
    )
    for payload in (oversized_string, token_bomb):
        assert len(payload) < gate.MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES
        _assert_external_gate(
            bundle.receipt_payloads,
            (payload, *bundle.control_evidence_payloads[1:]),
            bundle.expected,
            bundle.policy,
            expected_code="STRICT_SHAPE_REJECTED",
        )


def test_parser_recursion_error_is_translated_to_external_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bundle = _bundle()

    def raise_recursion(*args: Any, **kwargs: Any) -> dict[str, Any]:
        raise RecursionError("simulated bounded-parser recursion boundary")

    monkeypatch.setattr(gate, "parse_bounded_json_bytes", raise_recursion)
    with pytest.raises(gate.ExternalGateError) as failure:
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
            now=NOW,
        )
    assert failure.value.code == "STRICT_SHAPE_REJECTED"

    intake_bundle = _bundle()
    result = gate.intake_external_gate(
        intake_bundle.receipt_payloads,
        intake_bundle.control_evidence_payloads,
        intake_bundle.expected,
        intake_bundle.policy,
        now=NOW,
    )
    assert result["accepted"] is False
    assert result["classification"] == "EXTERNAL_GATE"
    assert result["reason_code"] == "STRICT_SHAPE_REJECTED"


def test_resource_limit_failure_tombstones_scope_before_correction() -> None:
    bundle = _bundle()
    oversized = (
        b" " * (gate.MAX_RECEIPT_PAYLOAD_BYTES + 1),
        *bundle.receipt_payloads[1:],
    )
    with pytest.raises(gate.ExternalGateError) as rejected:
        gate.verify_external_gate_receipts(
            oversized,
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
            now=NOW,
        )
    assert rejected.value.code == "RESOURCE_LIMIT_REJECTED"
    with pytest.raises(gate.ExternalGateError) as corrected:
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
            now=NOW,
        )
    assert corrected.value.code == "ANTI_REPLAY_REJECTED"


@pytest.mark.parametrize(
    "field",
    [
        "candidate_path_blobs",
        "target_workload_identities",
        "deployment_resources",
        "mtls_edges",
        "authorization_edges",
        "images",
        "pinned_trust_anchors",
    ],
)
def test_expected_context_requires_exact_tuple_inventories(field: str) -> None:
    bundle = _bundle()
    expected = replace(bundle.expected, **{field: list(getattr(bundle.expected, field))})
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        expected,
        bundle.policy,
        expected_code="STRICT_SHAPE_REJECTED",
    )


def test_context_and_policy_reject_deep_mutable_or_subclassed_items() -> None:
    bundle = _bundle()

    class CandidatePathBlobSubclass(gate.CandidatePathBlob):
        pass

    class ImageBindingSubclass(gate.ImageBinding):
        pass

    class PinnedTrustAnchorSubclass(gate.PinnedTrustAnchor):
        pass

    blob = bundle.expected.candidate_path_blobs[0]
    image = bundle.expected.images[0]
    anchor = bundle.expected.pinned_trust_anchors[0]
    expected_variants = (
        replace(
            bundle.expected,
            candidate_path_blobs=(
                CandidatePathBlobSubclass(**blob.__dict__),
                *bundle.expected.candidate_path_blobs[1:],
            ),
        ),
        replace(
            bundle.expected,
            images=(ImageBindingSubclass(**image.__dict__), *bundle.expected.images[1:]),
        ),
        replace(
            bundle.expected,
            pinned_trust_anchors=(PinnedTrustAnchorSubclass(**anchor.__dict__),),
        ),
    )
    for expected in expected_variants:
        _assert_external_gate(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            expected,
            bundle.policy,
            expected_code="STRICT_SHAPE_REJECTED",
        )

    policy_variants = (
        replace(bundle.policy, trust_roots=list(bundle.policy.trust_roots)),
        replace(
            bundle.policy,
            signing_certificates=list(bundle.policy.signing_certificates),
        ),
        replace(
            bundle.policy,
            operator_authorizations=list(bundle.policy.operator_authorizations),
        ),
        replace(
            bundle.policy,
            signer_authorizations=list(bundle.policy.signer_authorizations),
        ),
        replace(bundle.policy, allowed_algorithms={"Ed25519"}),
        replace(
            bundle.policy,
            operator_authorizations=(
                replace(
                    bundle.policy.operator_authorizations[0],
                    control_ids=set(gate.REQUIRED_CONTROL_IDS),
                ),
            ),
        ),
        replace(
            bundle.policy,
            revocation_snapshot=replace(
                bundle.policy.revocation_snapshot,
                revoked_certificate_serials=set(),
            ),
        ),
    )
    for policy in policy_variants:
        _assert_external_gate(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            policy,
            expected_code="INVALID_TRUST_POLICY",
        )


def test_frozen_inputs_and_validated_snapshots_resist_mutation() -> None:
    bundle = _bundle()
    with pytest.raises(FrozenInstanceError):
        bundle.expected.attempt_id = "mutated"  # type: ignore[misc]
    with pytest.raises(FrozenInstanceError):
        bundle.policy.trust_roots[0].trust_root_id = "mutated"  # type: ignore[misc]

    validated = gate.verify_external_gate_receipts(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        now=NOW,
    )
    assert validated.context is not bundle.expected
    assert validated.context.candidate_path_blobs is not (
        bundle.expected.candidate_path_blobs
    )
    assert validated.context.candidate_path_blobs[0] is not (
        bundle.expected.candidate_path_blobs[0]
    )
    assert validated.trust_policy is not bundle.policy
    assert validated.trust_policy.trust_roots[0] is not bundle.policy.trust_roots[0]


def test_payload_collections_require_exact_tuples_and_exact_bytes() -> None:
    bundle = _bundle()

    class BytesSubclass(bytes):
        pass

    _assert_external_gate(
        list(bundle.receipt_payloads),
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        expected_code="SIX_ROLE_ENVELOPE_REJECTED",
    )
    _assert_external_gate(
        bundle.receipt_payloads,
        list(bundle.control_evidence_payloads),
        bundle.expected,
        bundle.policy,
        expected_code="CONTROL_SET_REJECTED",
    )
    _assert_external_gate(
        (BytesSubclass(bundle.receipt_payloads[0]), *bundle.receipt_payloads[1:]),
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        expected_code="SIX_ROLE_ENVELOPE_REJECTED",
    )


def test_context_and_policy_cardinality_limits_run_before_crypto(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bundle = _bundle()
    crypto_called = False

    def forbidden_crypto(value: bytes) -> str:
        nonlocal crypto_called
        crypto_called = True
        raise AssertionError("crypto must not run before resource/cardinality guards")

    monkeypatch.setattr(gate, "public_key_fingerprint_sha256", forbidden_crypto)
    oversized_inventory = replace(
        bundle.expected,
        target_workload_identities=tuple(
            f"workload-{index:05d}"
            for index in range(gate.MAX_DEPLOYMENT_INVENTORY_ITEMS + 1)
        ),
    )
    with pytest.raises(gate.ExternalGateError) as inventory_failure:
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            oversized_inventory,
            replace(bundle.policy, replay_ledger=gate.ReplayLedger()),
            now=NOW,
        )
    assert inventory_failure.value.code == "RESOURCE_LIMIT_REJECTED"
    assert crypto_called is False

    oversized_policy = replace(
        bundle.policy,
        trust_roots=tuple(
            bundle.policy.trust_roots[0]
            for _ in range(gate.MAX_TRUST_ROOTS + 1)
        ),
        replay_ledger=gate.ReplayLedger(),
    )
    with pytest.raises(gate.ExternalGateError) as policy_failure:
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            oversized_policy,
            now=NOW,
        )
    assert policy_failure.value.code == "INVALID_TRUST_POLICY"
    assert crypto_called is False


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("trust_roots", ()),
        ("signing_certificates", ()),
        ("operator_authorizations", ()),
        ("signer_authorizations", ()),
    ],
)
def test_policy_requires_exact_authority_cardinalities(
    field: str, value: tuple[Any, ...]
) -> None:
    bundle = _bundle()
    policy = replace(bundle.policy, **{field: value})
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
        expected_code="INVALID_TRUST_POLICY",
    )


def test_public_key_pem_byte_ceiling_precedes_key_parsing() -> None:
    bundle = _bundle()
    oversized_pem = b"P" * (gate.MAX_PUBLIC_KEY_PEM_BYTES + 1)
    root = replace(bundle.policy.trust_roots[0], public_key_pem=oversized_pem)
    policy = replace(bundle.policy, trust_roots=(root,))
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
        expected_code="RESOURCE_LIMIT_REJECTED",
    )


@pytest.mark.parametrize("variant", ["missing", "extra", "duplicate", "reordered"])
def test_sixty_receipt_six_role_envelope_is_exact(variant: str) -> None:
    bundle = _bundle()
    receipts = list(bundle.receipt_payloads)
    if variant == "missing":
        receipts.pop()
    elif variant == "extra":
        receipts.append(receipts[-1])
    elif variant == "duplicate":
        receipts[1] = receipts[0]
    else:
        receipts[0], receipts[1] = receipts[1], receipts[0]
    _assert_external_gate(
        receipts, bundle.control_evidence_payloads, bundle.expected, bundle.policy
    )


@pytest.mark.parametrize("variant", ["missing", "extra", "duplicate", "alias", "reordered"])
def test_ten_control_evidence_set_is_exact(variant: str) -> None:
    bundle = _bundle()
    evidence = list(bundle.control_evidence_payloads)
    if variant == "missing":
        evidence.pop()
    elif variant == "extra":
        evidence.append(evidence[-1])
    elif variant == "duplicate":
        evidence[1] = evidence[0]
    elif variant == "alias":
        evidence = list(
            _mutated_evidence(
                bundle,
                lambda value: value.__setitem__(
                    "control_id", "ISTIO_MTLS_ACCEPTED"
                ),
            )
        )
    else:
        evidence[0], evidence[1] = evidence[1], evidence[0]
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


@pytest.mark.parametrize("field", ["claimed_result", "status"])
@pytest.mark.parametrize("value", ["FAIL", "PENDING", "PARTIAL", "UNKNOWN"])
def test_every_non_accepted_result_is_external_gate(field: str, value: str) -> None:
    bundle = _bundle()
    receipts = _mutated_receipts(
        bundle,
        lambda receipt: receipt.__setitem__(field, value),
        reseal=True,
    )
    _assert_external_gate(
        receipts, bundle.control_evidence_payloads, bundle.expected, bundle.policy
    )


@pytest.mark.parametrize(
    ("field", "replacement"),
    [
        ("candidate_sha", "e" * 40),
        ("candidate_tree_sha", "e" * 40),
        ("configuration_sha256", "e" * 64),
        ("context_id", "mixed-context-id"),
        ("context_sha256", "e" * 64),
        ("environment_identity", "different-production-environment"),
        ("deployment_manifest_sha256", "e" * 64),
        ("images", [{"digest": f"sha256:{DIGEST_D}", "name": "mixed/image"}]),
        ("attempt_id", "different-attempt-id"),
        ("attempt_number", 2),
        ("checkpoint_id", "different-checkpoint-id"),
        ("previous_attempt_id", "previous-attempt-id"),
    ],
)
def test_signed_receipt_context_mixing_is_rejected(field: str, replacement: Any) -> None:
    bundle = _bundle()
    receipts = _mutated_receipts(
        bundle,
        lambda receipt: receipt.__setitem__(field, replacement),
        reseal=True,
    )
    _assert_external_gate(
        receipts, bundle.control_evidence_payloads, bundle.expected, bundle.policy
    )


@pytest.mark.parametrize(
    "field",
    [
        "candidate_sha",
        "candidate_tree_sha",
        "candidate_path_blobs",
        "candidate_path_blobs_sha256",
        "configuration_sha256",
        "context_id",
        "context_sha256",
        "images",
        "environment_identity",
        "namespace",
        "cluster_id",
        "region",
        "deployment_manifest_sha256",
        "deployment_uid",
        "deployment_generation",
        "attempt_id",
        "attempt_number",
        "checkpoint_id",
        "target_workload_identities",
        "deployment_resources",
        "mtls_edges",
        "authorization_edges",
    ],
)
def test_control_metadata_context_mixing_is_rejected(field: str) -> None:
    bundle = _bundle()

    def mutate(value: dict[str, Any]) -> None:
        current = value["metadata"][field]
        if isinstance(current, list):
            value["metadata"][field] = current[:-1]
        elif isinstance(current, int):
            value["metadata"][field] = current + 1
        elif current is None:
            value["metadata"][field] = "unexpected-previous-attempt"
        elif field in {"candidate_sha", "candidate_tree_sha"}:
            value["metadata"][field] = "e" * 40
        elif field.endswith("sha256"):
            value["metadata"][field] = "e" * 64
        else:
            value["metadata"][field] = f"mixed-{current}"

    evidence = _mutated_evidence(bundle, mutate)
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


def test_evidence_hashes_are_distinct_bound_and_not_cross_control_mixable() -> None:
    bundle = _bundle()
    first = bundle.control_evidence_documents[0]
    hashes = {
        first["step_evidence_sha256"],
        first["stop_evidence_sha256"],
        first["rollback_evidence_sha256"],
        first["evidence_sha256"],
    }
    assert len(hashes) == 4

    single_hash = _mutated_evidence(
        bundle,
        lambda value: value.update(
            {
                "step_evidence_sha256": DIGEST_A,
                "stop_evidence_sha256": DIGEST_A,
                "rollback_evidence_sha256": DIGEST_A,
                "evidence_sha256": DIGEST_A,
            }
        ),
    )
    _assert_external_gate(
        bundle.receipt_payloads, single_hash, bundle.expected, bundle.policy
    )

    second = bundle.control_evidence_documents[1]
    receipts = _mutated_receipts(
        bundle,
        lambda value: value.update(
            {
                field: second[field]
                for field in (
                    "step_evidence_sha256",
                    "stop_evidence_sha256",
                    "rollback_evidence_sha256",
                    "evidence_sha256",
                )
            }
        ),
        index=4,
        reseal=True,
    )
    _assert_external_gate(
        receipts, bundle.control_evidence_payloads, bundle.expected, bundle.policy
    )


@pytest.mark.parametrize("runtime_index", range(5))
def test_each_runtime_blocker_requires_actual_external_acceptance(
    runtime_index: int,
) -> None:
    bundle = _bundle()
    evidence = _mutated_evidence(
        bundle,
        lambda value: value["step_evidence"].__setitem__("actual_external", False),
        index=runtime_index,
    )
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


@pytest.mark.parametrize(
    ("control_id", "mutation"),
    [
        (gate.ISTIO_CRD_CONTROL, lambda facts: facts.__setitem__("crd_ready", False)),
        (
            gate.ISTIO_DATAPLANE_CONTROL,
            lambda facts: facts.__setitem__(
                "target_workloads", facts["target_workloads"][:1]
            ),
        ),
        (
            gate.ISTIO_MTLS_CONTROL,
            lambda facts: facts.__setitem__(
                "enforced_edges", facts["enforced_edges"][:1]
            ),
        ),
        (
            gate.ISTIO_AUTHZ_CONTROL,
            lambda facts: facts.__setitem__(
                "denied_probe_edges", facts["denied_probe_edges"][:1]
            ),
        ),
    ],
)
def test_each_istio_actual_enforcement_fact_is_exact(
    control_id: str, mutation: Callable[[dict[str, Any]], None]
) -> None:
    bundle = _bundle()
    index = gate.REQUIRED_CONTROL_IDS.index(control_id)
    evidence = _mutated_evidence(
        bundle,
        lambda value: mutation(value["step_evidence"]),
        index=index,
    )
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


@pytest.mark.parametrize(
    ("field", "replacement"),
    [
        ("namespace", "mixed-namespace"),
        ("deployment_uid", "mixed-deployment-uid"),
        ("deployment_generation", 8),
        ("service_account", "different-service-account"),
        ("otlp_ports", [4317]),
        ("labels", {"app.kubernetes.io/name": "wrong", "app.kubernetes.io/part-of": "after-sale-flow"}),
        ("binding_verified", False),
    ],
)
def test_otel_i3_i4_exact_binding_facts_are_not_substitutable(
    field: str, replacement: Any
) -> None:
    bundle = _bundle()
    index = gate.REQUIRED_CONTROL_IDS.index(gate.OTEL_BINDING_CONTROL)
    evidence = _mutated_evidence(
        bundle,
        lambda value: value["step_evidence"].__setitem__(field, replacement),
        index=index,
    )
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


def test_fixture_synthetic_local_and_desired_state_only_inputs_are_rejected() -> None:
    for changes in (
        {"environment_class": "SYNTHETIC"},
        {"environment_identity": "engineering-local-environment"},
        {"environment_identity": "fixture-production-lookalike"},
        {"context_id": "fixture-context-id"},
        {"namespace": "test-only-namespace"},
        {"cluster_id": "localhost-cluster"},
        {"checkpoint_id": "synthetic-checkpoint-id"},
        {"deployment_uid": "fixture-deployment-uid"},
        {"target_workload_identities": ("fixture-workload",)},
        {"deployment_resources": ("synthetic-deployment-resource",)},
        {"mtls_edges": ("edge-fixture_to_prod",)},
        {"authorization_edges": ("edge-localhost_to_prod",)},
        {
            "images": (
                gate.ImageBinding(
                    "test-only/image", f"sha256:{DIGEST_A}"
                ),
            )
        },
    ):
        bundle = _bundle(expected_changes=changes)
        _assert_external_gate(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
        )
    bundle = _bundle()
    evidence = _mutated_evidence(
        bundle,
        lambda value: value["metadata"].__setitem__(
            "evidence_source", "DESIRED_STATE_ONLY"
        ),
    )
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


def test_payload_digest_signature_algorithm_key_root_and_schema_tampering_fails() -> None:
    bundle = _bundle()
    mutations = (
        lambda value: value.__setitem__("signed_payload_sha256", DIGEST_D),
        lambda value: value.__setitem__("signature_algorithm", "RSA_PSS_SHA256"),
        lambda value: value.__setitem__("signing_key_id", "unknown-signing-key"),
        lambda value: value.__setitem__("trust_root_id", "unknown-trust-root"),
        lambda value: value.__setitem__("schema_version", "replacement-schema.v1"),
        lambda value: value.__setitem__("legacy_receipt_id", "substitute-shape"),
    )
    for mutation in mutations:
        _assert_external_gate(
            _mutated_receipts(bundle, mutation),
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
        )

    invalid_signature = copy.deepcopy(bundle.receipt_documents[0])
    invalid_signature["signature"] = _b64(b"not-the-valid-signature")
    invalid_signature["receipt_sha256"] = gate.receipt_sha256(invalid_signature)
    receipts = _replace_payload(bundle.receipt_payloads, 0, invalid_signature)
    _assert_external_gate(
        receipts, bundle.control_evidence_payloads, bundle.expected, bundle.policy
    )


@pytest.mark.parametrize("signature_length", [63, 65])
def test_ed25519_signature_length_is_exact(signature_length: int) -> None:
    private = ed25519.Ed25519PrivateKey.generate()
    with pytest.raises(evidence_schema.EvidenceValidationError, match="exactly 64"):
        evidence_schema.verify_detached_signature(
            algorithm="Ed25519",
            public_key_pem=_public_pem(private),
            payload=b"phase8-ed25519-length-boundary",
            signature=b"s" * signature_length,
        )


def test_p256_signature_must_be_canonical_der() -> None:
    private = ec.generate_private_key(ec.SECP256R1())
    payload = b"phase8-p256-canonical-der"
    public_pem = private.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    def canonical_integer(value: int) -> bytes:
        encoded = value.to_bytes((value.bit_length() + 7) // 8, "big")
        if encoded[0] & 0x80:
            encoded = b"\x00" + encoded
        return encoded

    for _ in range(32):
        signature = private.sign(payload, ec.ECDSA(hashes.SHA256()))
        r, s = utils.decode_dss_signature(signature)
        r_bytes = b"\x00" + canonical_integer(r)
        s_bytes = canonical_integer(s)
        sequence = (
            b"\x02"
            + bytes([len(r_bytes)])
            + r_bytes
            + b"\x02"
            + bytes([len(s_bytes)])
            + s_bytes
        )
        noncanonical = b"\x30" + bytes([len(sequence)]) + sequence
        if len(noncanonical) <= 72:
            break
    else:
        pytest.fail("could not construct bounded non-canonical P-256 DER")
    evidence_schema.verify_detached_signature(
        algorithm="ECDSA_P256_SHA256",
        public_key_pem=public_pem,
        payload=payload,
        signature=signature,
    )
    with pytest.raises(evidence_schema.EvidenceValidationError):
        evidence_schema.verify_detached_signature(
            algorithm="ECDSA_P256_SHA256",
            public_key_pem=public_pem,
            payload=payload,
            signature=noncanonical,
        )


def test_rsa_pss_signature_length_matches_key_and_key_size_has_upper_guard() -> None:
    private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    payload = b"phase8-rsa-pss-length-boundary"
    signature = private.sign(
        payload,
        padding.PSS(
            mgf=padding.MGF1(hashes.SHA256()),
            salt_length=hashes.SHA256().digest_size,
        ),
        hashes.SHA256(),
    )
    public_pem = private.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    evidence_schema.verify_detached_signature(
        algorithm="RSA_PSS_SHA256",
        public_key_pem=public_pem,
        payload=payload,
        signature=signature,
    )
    with pytest.raises(
        evidence_schema.EvidenceValidationError,
        match="signature length does not match",
    ):
        evidence_schema.verify_detached_signature(
            algorithm="RSA_PSS_SHA256",
            public_key_pem=public_pem,
            payload=payload,
            signature=signature[:-1],
        )

    source = EVIDENCE_SCHEMA_PATH.read_text(encoding="utf-8")
    assert "not 3072 <= public_key.key_size <= 8192" in source
    assert "len(signature) != (public_key.key_size + 7) // 8" in source


def test_policy_signature_text_is_bounded_before_signature_decoding() -> None:
    bundle = _bundle()
    certificate = replace(
        bundle.policy.signing_certificates[0],
        issuer_signature="A" * 16_385,
    )
    policy = replace(
        bundle.policy,
        signing_certificates=(
            certificate,
            *bundle.policy.signing_certificates[1:],
        ),
    )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
        expected_code="RESOURCE_LIMIT_REJECTED",
    )


@pytest.mark.parametrize("field", ["candidate_sha", "configuration_sha256", "environment_identity", "namespace", "cluster_id", "region", "deployment_manifest_sha256", "deployment_uid", "attempt_id"])
def test_operator_authorization_scope_is_exact(field: str) -> None:
    bundle = _bundle()
    authorization = bundle.policy.operator_authorizations[0]
    current = getattr(authorization, field)
    replacement = "e" * (40 if field == "candidate_sha" else 64) if field.endswith("sha256") or field == "candidate_sha" else f"mixed-{current}"
    changed = _resign_operator(bundle, replace(authorization, **{field: replacement}))
    policy = replace(bundle.policy, operator_authorizations=(changed,))
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize("field", ["candidate_sha", "configuration_sha256", "environment_identity", "namespace", "cluster_id", "region", "deployment_manifest_sha256", "deployment_uid", "attempt_id"])
def test_signer_authorization_scope_is_exact(field: str) -> None:
    bundle = _bundle()
    authorization = bundle.policy.signer_authorizations[0]
    current = getattr(authorization, field)
    replacement = "e" * (40 if field == "candidate_sha" else 64) if field.endswith("sha256") or field == "candidate_sha" else f"mixed-{current}"
    changed = _resign_signer(bundle, replace(authorization, **{field: replacement}))
    authorizations = (changed, *bundle.policy.signer_authorizations[1:])
    policy = replace(bundle.policy, signer_authorizations=authorizations)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize("kind", ["operator", "signer"])
@pytest.mark.parametrize("mutation", ["scope", "partial_controls"])
def test_operator_and_signer_control_scope_cannot_be_partial_or_aliased(
    kind: str, mutation: str
) -> None:
    bundle = _bundle()
    if kind == "operator":
        authorization = bundle.policy.operator_authorizations[0]
        changed = (
            replace(authorization, scope="ALIASED_SCOPE")
            if mutation == "scope"
            else replace(
                authorization,
                control_ids=frozenset(gate.REQUIRED_CONTROL_IDS[:-1]),
            )
        )
        changed = _resign_operator(bundle, changed)
        policy = replace(bundle.policy, operator_authorizations=(changed,))
    else:
        authorization = bundle.policy.signer_authorizations[0]
        changed = (
            replace(authorization, scope="ALIASED_SCOPE")
            if mutation == "scope"
            else replace(
                authorization,
                control_ids=frozenset(gate.REQUIRED_CONTROL_IDS[:-1]),
            )
        )
        changed = _resign_signer(bundle, changed)
        policy = replace(
            bundle.policy,
            signer_authorizations=(
                changed,
                *bundle.policy.signer_authorizations[1:],
            ),
        )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize("kind", ["root", "certificate", "operator", "signer"])
def test_trust_and_authorization_signatures_or_validity_cannot_be_forged(
    kind: str,
) -> None:
    bundle = _bundle()
    policy = bundle.policy
    if kind == "root":
        policy = replace(
            policy,
            trust_roots=(replace(policy.trust_roots[0], not_after=NOW),),
        )
    elif kind == "certificate":
        certificate = replace(
            policy.signing_certificates[0],
            issuer_signature=_b64(b"forged-certificate-signature"),
        )
        policy = replace(
            policy,
            signing_certificates=(certificate, *policy.signing_certificates[1:]),
        )
    elif kind == "operator":
        authorization = replace(
            policy.operator_authorizations[0],
            issuer_signature=_b64(b"forged-operator-authorization"),
        )
        policy = replace(policy, operator_authorizations=(authorization,))
    else:
        authorization = replace(
            policy.signer_authorizations[0],
            issuer_signature=_b64(b"forged-signer-authorization"),
        )
        policy = replace(
            policy,
            signer_authorizations=(
                authorization,
                *policy.signer_authorizations[1:],
            ),
        )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize(
    ("variant", "expected_code"),
    [
        ("missing", "RESOURCE_LIMIT_REJECTED"),
        ("duplicate_fingerprint", "UNTRUSTED_ROOT"),
        ("reordered", "UNTRUSTED_ROOT"),
        ("key_mismatch", "UNTRUSTED_KEY"),
        ("fingerprint_mismatch", "UNTRUSTED_KEY"),
        ("algorithm_mismatch", "UNTRUSTED_ALGORITHM"),
        ("unused_extra", "INVALID_TRUST_POLICY"),
    ],
)
def test_pinned_trust_anchor_inventory_is_exact_and_key_bound(
    variant: str, expected_code: str
) -> None:
    bundle = _bundle()
    anchor = bundle.expected.pinned_trust_anchors[0]
    second_private = ed25519.Ed25519PrivateKey.generate()
    second_pem = _public_pem(second_private)
    second = gate.PinnedTrustAnchor(
        trust_root_id="trust-root-002",
        algorithm="Ed25519",
        public_key_pem=second_pem,
        fingerprint_sha256=public_key_fingerprint_sha256(second_pem),
    )
    if variant == "missing":
        anchors: tuple[gate.PinnedTrustAnchor, ...] = ()
    elif variant == "duplicate_fingerprint":
        anchors = (
            anchor,
            replace(anchor, trust_root_id="trust-root-002"),
        )
    elif variant == "reordered":
        anchors = (second, anchor)
    elif variant == "key_mismatch":
        anchors = (replace(anchor, public_key_pem=second_pem),)
    elif variant == "fingerprint_mismatch":
        anchors = (replace(anchor, fingerprint_sha256=DIGEST_D),)
    elif variant == "algorithm_mismatch":
        anchors = (replace(anchor, algorithm="UNSUPPORTED"),)
    else:
        anchors = (anchor, second)
    expected = replace(bundle.expected, pinned_trust_anchors=anchors)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        expected,
        bundle.policy,
        expected_code=expected_code,
    )


@pytest.mark.parametrize(
    ("field", "value", "reseal"),
    [
        ("schema_version", "replacement-revocation.v1", True),
        ("source", "LOCAL_ASSERTION", True),
        ("trust_anchor_set_sha256", DIGEST_D, True),
        ("snapshot_sha256", DIGEST_D, False),
        ("generated_at", NOW + timedelta(seconds=1), True),
        ("expires_at", NOW, True),
    ],
)
def test_revocation_snapshot_contract_is_exact_and_current(
    field: str, value: Any, reseal: bool
) -> None:
    bundle = _bundle()
    snapshot = bundle.policy.revocation_snapshot
    if reseal:
        snapshot = _reseal_revocation_snapshot(snapshot, **{field: value})
    else:
        snapshot = replace(snapshot, **{field: value})
    policy = replace(bundle.policy, revocation_snapshot=snapshot)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
        expected_code="INVALID_TRUST_POLICY",
    )


@pytest.mark.parametrize(
    ("field", "revoked_value"),
    [
        (
            "revoked_root_fingerprints",
            lambda bundle: bundle.policy.trust_roots[0].fingerprint_sha256,
        ),
        (
            "revoked_certificate_serials",
            lambda bundle: bundle.policy.signing_certificates[0].certificate_serial,
        ),
        (
            "revoked_signing_key_fingerprints",
            lambda bundle: bundle.policy.signing_certificates[
                0
            ].public_key_fingerprint_sha256,
        ),
        (
            "revoked_authorization_references",
            lambda bundle: bundle.policy.operator_authorizations[
                0
            ].authorization_reference,
        ),
        (
            "revoked_authorization_references",
            lambda bundle: bundle.policy.signer_authorizations[
                4
            ].authorization_reference,
        ),
    ],
)
def test_each_revocation_inventory_is_enforced(
    field: str, revoked_value: Callable[[SignedBundle], str]
) -> None:
    bundle = _bundle()
    snapshot = _reseal_revocation_snapshot(
        bundle.policy.revocation_snapshot,
        **{field: frozenset({revoked_value(bundle)})},
    )
    policy = replace(bundle.policy, revocation_snapshot=snapshot)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize("field", [
    "revoked_root_fingerprints",
    "revoked_certificate_serials",
    "revoked_signing_key_fingerprints",
    "revoked_authorization_references",
])
def test_revocation_snapshot_sets_must_be_deeply_frozen(field: str) -> None:
    bundle = _bundle()
    snapshot = replace(
        bundle.policy.revocation_snapshot,
        **{field: set()},
    )
    policy = replace(bundle.policy, revocation_snapshot=snapshot)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
        expected_code="INVALID_TRUST_POLICY",
    )


@pytest.mark.parametrize("suffix", [b"trailing-bytes", b"\xff"])
def test_noncanonical_public_key_pem_is_rejected_and_tombstones_scope(
    suffix: bytes,
) -> None:
    bundle = _bundle()
    root = replace(
        bundle.policy.trust_roots[0],
        public_key_pem=bundle.policy.trust_roots[0].public_key_pem + suffix,
    )
    invalid_policy = replace(bundle.policy, trust_roots=(root,))
    with pytest.raises(gate.ExternalGateError):
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            invalid_policy,
            now=NOW,
        )
    with pytest.raises(gate.ExternalGateError) as corrected:
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
            now=NOW,
        )
    assert corrected.value.code == "ANTI_REPLAY_REJECTED"


def test_distinct_authority_roots_are_valid_at_observation_and_have_unique_keys() -> None:
    bundle = _bundle()
    root_one = bundle.policy.trust_roots[0]
    root_two_private = ed25519.Ed25519PrivateKey.generate()
    root_two_pem = _public_pem(root_two_private)
    root_two = gate.TrustRoot(
        trust_root_id="trust-root-002",
        algorithm="Ed25519",
        public_key_pem=root_two_pem,
        fingerprint_sha256=public_key_fingerprint_sha256(root_two_pem),
        not_before=NOW - timedelta(seconds=45),
        not_after=NOW + timedelta(days=1),
    )
    operator = replace(
        bundle.policy.operator_authorizations[0],
        trust_root_id=root_two.trust_root_id,
    )
    operator = replace(
        operator,
        issuer_signature=_root_signature(
            root_two_private,
            gate.operator_authorization_signed_payload(operator),
        ),
    )
    policy = replace(
        bundle.policy,
        trust_roots=(root_one, root_two),
        operator_authorizations=(operator,),
    )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )

    duplicate_root = replace(
        root_one,
        trust_root_id="trust-root-duplicate-key",
    )
    duplicate_policy = replace(
        bundle.policy,
        trust_roots=(root_one, duplicate_root),
        replay_ledger=gate.ReplayLedger(),
    )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        duplicate_policy,
    )

    valid_root_two = replace(
        root_two,
        not_before=NOW - timedelta(days=2),
    )
    root_one_pem = root_one.public_key_pem
    certificate = replace(
        bundle.policy.signing_certificates[0],
        trust_root_id=valid_root_two.trust_root_id,
        public_key_pem=root_one_pem,
        public_key_fingerprint_sha256=public_key_fingerprint_sha256(root_one_pem),
    )
    certificate = replace(
        certificate,
        issuer_signature=_root_signature(
            root_two_private,
            gate.certificate_signed_payload(certificate),
        ),
    )
    leaf_policy = replace(
        bundle.policy,
        trust_roots=(root_one, valid_root_two),
        signing_certificates=(
            certificate,
            *bundle.policy.signing_certificates[1:],
        ),
        replay_ledger=gate.ReplayLedger(),
    )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        leaf_policy,
    )


@pytest.mark.parametrize("kind", ["root", "certificate", "operator", "signer"])
def test_revocation_and_expiry_are_fail_closed(kind: str) -> None:
    bundle = _bundle()
    policy = bundle.policy
    if kind == "root":
        policy = replace(policy, trust_roots=(replace(policy.trust_roots[0], revoked=True),))
    elif kind == "certificate":
        certificates = (
            replace(policy.signing_certificates[0], revoked=True),
            *policy.signing_certificates[1:],
        )
        policy = replace(policy, signing_certificates=certificates)
    elif kind == "operator":
        policy = replace(
            policy,
            operator_authorizations=(
                replace(policy.operator_authorizations[0], revoked=True),
            ),
        )
    else:
        authorizations = (
            replace(policy.signer_authorizations[0], revoked=True),
            *policy.signer_authorizations[1:],
        )
        policy = replace(policy, signer_authorizations=authorizations)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize("kind", ["certificate", "operator", "signer"])
def test_credential_and_authorization_must_be_valid_at_observation_and_now(
    kind: str,
) -> None:
    bundle = _bundle()
    policy = bundle.policy
    not_before = NOW - timedelta(seconds=45)
    if kind == "certificate":
        certificate = replace(policy.signing_certificates[0], not_before=not_before)
        certificate = replace(
            certificate,
            issuer_signature=_root_signature(
                bundle.root_private_key,
                gate.certificate_signed_payload(certificate),
            ),
        )
        policy = replace(
            policy,
            signing_certificates=(certificate, *policy.signing_certificates[1:]),
        )
    elif kind == "operator":
        authorization = _resign_operator(
            bundle,
            replace(policy.operator_authorizations[0], not_before=not_before),
        )
        policy = replace(policy, operator_authorizations=(authorization,))
    else:
        authorization = _resign_signer(
            bundle,
            replace(policy.signer_authorizations[0], not_before=not_before),
        )
        policy = replace(
            policy,
            signer_authorizations=(authorization, *policy.signer_authorizations[1:]),
        )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


@pytest.mark.parametrize("author_field", tuple(AUTHORS))
def test_signer_and_operator_cannot_casefold_overlap_any_author(
    author_field: str,
) -> None:
    author = AUTHORS[author_field]
    signer_identities = (
        author.swapcase(),
        *tuple(f"signer-{role.lower()}-independent" for role in gate.SIGNER_ROLE_ORDER[1:]),
    )
    signer_bundle = _bundle(signer_identities=signer_identities)
    _assert_external_gate(
        signer_bundle.receipt_payloads,
        signer_bundle.control_evidence_payloads,
        signer_bundle.expected,
        signer_bundle.policy,
    )
    operator_bundle = _bundle(operator_identity=author.swapcase())
    _assert_external_gate(
        operator_bundle.receipt_payloads,
        operator_bundle.control_evidence_payloads,
        operator_bundle.expected,
        operator_bundle.policy,
    )


def test_authors_and_signer_operator_identities_are_pairwise_independent() -> None:
    author_collision = _bundle(
        expected_changes={
            "generator_identity": AUTHORS["runner_identity"].swapcase()
        }
    )
    _assert_external_gate(
        author_collision.receipt_payloads,
        author_collision.control_evidence_payloads,
        author_collision.expected,
        author_collision.policy,
    )
    shared_identity = "shared-signer-operator-identity"
    signer_identities = (
        shared_identity,
        *tuple(
            f"signer-{role.lower()}-independent"
            for role in gate.SIGNER_ROLE_ORDER[1:]
        ),
    )
    signer_operator_collision = _bundle(
        signer_identities=signer_identities,
        operator_identity=shared_identity.swapcase(),
    )
    _assert_external_gate(
        signer_operator_collision.receipt_payloads,
        signer_operator_collision.control_evidence_payloads,
        signer_operator_collision.expected,
        signer_operator_collision.policy,
    )


def test_six_signer_roles_and_identities_cannot_be_missing_or_collide() -> None:
    bundle = _bundle()
    policy = replace(
        bundle.policy,
        signer_authorizations=bundle.policy.signer_authorizations[:-1],
    )
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )
    evidence = _mutated_evidence(
        bundle,
        lambda value: value["metadata"]["signer_authorizations"][1].update(
            copy.deepcopy(value["metadata"]["signer_authorizations"][0])
        ),
    )
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


def test_signer_authorization_mapping_is_stable_across_all_ten_controls() -> None:
    bundle = _bundle()
    document = copy.deepcopy(bundle.control_evidence_documents[1])
    document["metadata"]["signer_authorizations"][0][
        "signer_identity"
    ] = "cross-control-substituted-signer"
    document = _reseal_evidence(document)
    evidence = _replace_payload(bundle.control_evidence_payloads, 1, document)
    _assert_external_gate(bundle.receipt_payloads, evidence, bundle.expected, bundle.policy)


def test_control_evidence_freshness_expiry_and_nonce_reuse_are_rejected() -> None:
    for field, value in (
        ("observed_at", _rfc3339(NOW - timedelta(minutes=10))),
        ("issued_at", _rfc3339(NOW + timedelta(minutes=2))),
        ("expires_at", _rfc3339(NOW)),
    ):
        bundle = _bundle()
        evidence = _mutated_evidence(
            bundle,
            lambda document, key=field, replacement=value: document["metadata"].__setitem__(
                key, replacement
            ),
        )
        _assert_external_gate(
            bundle.receipt_payloads, evidence, bundle.expected, bundle.policy
        )

    bundle = _bundle()
    duplicate_nonce = copy.deepcopy(bundle.control_evidence_documents[1])
    duplicate_nonce["metadata"]["nonce"] = bundle.control_evidence_documents[0][
        "metadata"
    ]["nonce"]
    duplicate_nonce = _reseal_evidence(duplicate_nonce)
    evidence = _replace_payload(bundle.control_evidence_payloads, 1, duplicate_nonce)
    _assert_external_gate(
        bundle.receipt_payloads,
        evidence,
        bundle.expected,
        bundle.policy,
        expected_code="ANTI_REPLAY_REJECTED",
    )


def test_backdated_caller_clock_never_upgrades_freshness_or_authenticity() -> None:
    bundle = _bundle()
    caller_now = NOW - timedelta(seconds=15)
    expires_at = _rfc3339(NOW - timedelta(seconds=5))
    evidence_documents: list[dict[str, Any]] = []
    receipt_payloads = list(bundle.receipt_payloads)
    for control_index, source in enumerate(bundle.control_evidence_documents):
        evidence = copy.deepcopy(source)
        evidence["metadata"]["expires_at"] = expires_at
        evidence = _reseal_evidence(evidence)
        evidence_documents.append(evidence)
        start = control_index * len(gate.SIGNER_ROLE_ORDER)
        for role_index, role in enumerate(gate.SIGNER_ROLE_ORDER):
            receipt_index = start + role_index
            receipt = copy.deepcopy(bundle.receipt_documents[receipt_index])
            receipt.update(
                {
                    "step_evidence_sha256": evidence["step_evidence_sha256"],
                    "stop_evidence_sha256": evidence["stop_evidence_sha256"],
                    "rollback_evidence_sha256": evidence[
                        "rollback_evidence_sha256"
                    ],
                    "evidence_sha256": evidence["evidence_sha256"],
                }
            )
            receipt_payloads[receipt_index] = canonical_json_bytes(
                _seal_receipt(receipt, bundle.signer_private_keys[role])
            )
    result = gate.intake_external_gate(
        tuple(receipt_payloads),
        tuple(canonical_json_bytes(item) for item in evidence_documents),
        bundle.expected,
        bundle.policy,
        now=caller_now,
    )
    assert result["accepted"] is False
    assert result["cryptographic_shape_validated"] is True
    assert result["external_authenticity_verified"] is False
    assert result["external_security_preflight"] == "EXTERNAL_GATE"
    assert result["freshness_status"] == "UNVERIFIED_PENDING_EXTERNAL"
    assert result["evaluation_time_provenance"] == gate.EVALUATION_TIME_PROVENANCE
    assert result["validation_policy"]["evaluation_instant"] == _rfc3339(caller_now)
    assert result["validation_policy"]["freshness_status"] == (
        "UNVERIFIED_PENDING_EXTERNAL"
    )


def test_root_signed_authority_from_context_a_cannot_authorize_context_b() -> None:
    authority = _bundle()
    changed_blob = replace(
        authority.expected.candidate_path_blobs[0],
        git_blob_sha="e" * 40,
        sha256="e" * 64,
    )
    changed_blobs = (
        changed_blob,
        *authority.expected.candidate_path_blobs[1:],
    )
    variants: list[dict[str, Any]] = [
        {"candidate_tree_sha": "e" * 40},
        {
            "candidate_path_blobs": changed_blobs,
            "candidate_path_blobs_sha256": canonical_sha256(
                [item.as_dict() for item in changed_blobs]
            ),
        },
        {"context_id": "phase8-release-context-002"},
        {"context_sha256": "e" * 64},
        {"deployment_generation": 8},
        {
            "images": (
                gate.ImageBinding(
                    "after-sale-flow/java-api", f"sha256:{DIGEST_D}"
                ),
                gate.ImageBinding(
                    "after-sale-flow/python-agent", f"sha256:{DIGEST_B}"
                ),
            )
        },
        {"checkpoint_id": "phase8-unified-checkpoint-002"},
        {
            "target_workload_identities": (
                *authority.expected.target_workload_identities,
                "workload-zeta",
            )
        },
        {
            "deployment_resources": (
                *authority.expected.deployment_resources,
                "deployment-zeta",
            )
        },
        {
            "mtls_edges": (
                *authority.expected.mtls_edges,
                "edge-zeta_to_zulu",
            )
        },
        {
            "authorization_edges": (
                *authority.expected.authorization_edges,
                "edge-zeta_to_zulu",
            )
        },
    ]
    for changes in variants:
        substituted = _bundle_reusing_authority(
            authority,
            expected_changes=changes,
        )
        _assert_external_gate(
            substituted.receipt_payloads,
            substituted.control_evidence_payloads,
            substituted.expected,
            substituted.policy,
        )


@pytest.mark.parametrize(
    ("control_id", "field"),
    [
        (gate.REQUIRED_CONTROL_IDS[0], "accepted"),
        (gate.REQUIRED_CONTROL_IDS[0], "actual_external"),
        (gate.ISTIO_CRD_CONTROL, "actual_external"),
        (gate.ISTIO_CRD_CONTROL, "crd_ready"),
        (gate.ISTIO_DATAPLANE_CONTROL, "actual_external"),
        (gate.ISTIO_MTLS_CONTROL, "actual_external"),
        (gate.ISTIO_AUTHZ_CONTROL, "actual_external"),
        (gate.OTEL_BINDING_CONTROL, "actual_external"),
        (gate.OTEL_BINDING_CONTROL, "binding_verified"),
    ],
)
@pytest.mark.parametrize("alias", [1, 1.0])
def test_resealed_integer_aliases_cannot_substitute_boolean_control_facts(
    control_id: str, field: str, alias: int | float
) -> None:
    bundle = _bundle()
    control_index = gate.REQUIRED_CONTROL_IDS.index(control_id)
    evidence = copy.deepcopy(bundle.control_evidence_documents[control_index])
    evidence["step_evidence"][field] = alias
    receipts, evidence_payloads = _rebind_control_group(
        bundle,
        control_index,
        evidence,
    )
    _assert_external_gate(receipts, evidence_payloads, bundle.expected, bundle.policy)


def test_resealed_boolean_aliases_cannot_substitute_integer_bindings() -> None:
    for alias in (True, 1.0):
        try:
            trusted_alias = _bundle(expected_changes={"attempt_number": alias})
        except gate.ExternalGateError:
            trusted_alias = None
        if trusted_alias is None:
            continue
        _assert_external_gate(
            trusted_alias.receipt_payloads,
            trusted_alias.control_evidence_payloads,
            trusted_alias.expected,
            trusted_alias.policy,
        )

    bundle = _bundle()
    for alias in (True, 1.0):
        attempt_evidence = copy.deepcopy(bundle.control_evidence_documents[0])
        attempt_evidence["metadata"]["attempt_number"] = alias
        attempt_receipts, attempt_payloads = _rebind_control_group(
            bundle,
            0,
            attempt_evidence,
            receipt_overrides={"attempt_number": alias},
        )
        _assert_external_gate(
            attempt_receipts,
            attempt_payloads,
            bundle.expected,
            bundle.policy,
        )

    otel_index = gate.REQUIRED_CONTROL_IDS.index(gate.OTEL_BINDING_CONTROL)
    generation_evidence = copy.deepcopy(
        bundle.control_evidence_documents[otel_index]
    )
    generation_evidence["metadata"]["deployment_generation"] = True
    generation_evidence["step_evidence"]["deployment_generation"] = True
    generation_receipts, generation_payloads = _rebind_control_group(
        bundle,
        otel_index,
        generation_evidence,
    )
    _assert_external_gate(
        generation_receipts,
        generation_payloads,
        bundle.expected,
        bundle.policy,
    )

    checkpoint_receipts, checkpoint_payloads = _rebind_control_group(
        bundle,
        0,
        bundle.control_evidence_documents[0],
        receipt_overrides={"checkpoint_order": True},
    )
    _assert_external_gate(
        checkpoint_receipts,
        checkpoint_payloads,
        bundle.expected,
        bundle.policy,
    )

    float_generation_evidence = copy.deepcopy(
        bundle.control_evidence_documents[otel_index]
    )
    float_generation_evidence["metadata"]["deployment_generation"] = 7.0
    float_generation_evidence["step_evidence"]["deployment_generation"] = 7.0
    float_generation_receipts, float_generation_payloads = _rebind_control_group(
        bundle,
        otel_index,
        float_generation_evidence,
    )
    _assert_external_gate(
        float_generation_receipts,
        float_generation_payloads,
        bundle.expected,
        bundle.policy,
    )

    float_checkpoint_receipts, float_checkpoint_payloads = _rebind_control_group(
        bundle,
        0,
        bundle.control_evidence_documents[0],
        receipt_overrides={"checkpoint_order": 3.0},
    )
    _assert_external_gate(
        float_checkpoint_receipts,
        float_checkpoint_payloads,
        bundle.expected,
        bundle.policy,
    )

    float_ports_evidence = copy.deepcopy(
        bundle.control_evidence_documents[otel_index]
    )
    float_ports_evidence["step_evidence"]["otlp_ports"] = [4317.0, 4318.0]
    float_ports_receipts, float_ports_payloads = _rebind_control_group(
        bundle,
        otel_index,
        float_ports_evidence,
    )
    _assert_external_gate(
        float_ports_receipts,
        float_ports_payloads,
        bundle.expected,
        bundle.policy,
    )


def test_replay_ledger_rejects_second_intake_and_case_variants() -> None:
    bundle = _bundle()
    first = gate.verify_external_gate_receipts(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        now=NOW,
    )
    assert first.replay_consumption.sequence == 1
    with pytest.raises(gate.ExternalGateError) as replayed:
        gate.verify_external_gate_receipts(
            bundle.receipt_payloads,
            bundle.control_evidence_payloads,
            bundle.expected,
            bundle.policy,
            now=NOW,
        )
    assert replayed.value.code == "ANTI_REPLAY_REJECTED"

    ledger = gate.ReplayLedger()
    ledger.claim_attempt(
        attempt_id="Case-Sensitive-Attempt",
        attempt_scope_sha256=DIGEST_C,
    )
    ledger.consume_once(
        attempt_id="Case-Sensitive-Attempt",
        attempt_scope_sha256=DIGEST_C,
        receipt_set_sha256=DIGEST_A,
        receipt_hashes=(DIGEST_B,),
        nonces=("Case-Sensitive-Nonce-0001",),
        consumed_at=NOW,
    )
    with pytest.raises(gate.ExternalGateError):
        ledger.claim_attempt(
            attempt_id="case-sensitive-attempt",
            attempt_scope_sha256=DIGEST_D,
        )


def test_rejected_attempt_is_tombstoned_before_corrected_resubmission() -> None:
    bundle = _bundle()
    malformed = list(bundle.control_evidence_payloads[:-1])
    rejected = gate.intake_external_gate(
        bundle.receipt_payloads,
        malformed,
        bundle.expected,
        bundle.policy,
        now=NOW,
    )
    assert rejected["status"] == "REJECTED"
    assert rejected["retry_allowed"] is False

    corrected = gate.intake_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        now=NOW,
    )
    assert corrected["status"] == "REJECTED"
    assert corrected["reason_code"] == "ANTI_REPLAY_REJECTED"
    assert corrected["classification"] == "EXTERNAL_GATE"
    assert corrected["retry_allowed"] is False

    fresh_attempt_same_scope = _bundle(
        expected_changes={"attempt_id": "phase8-external-attempt-002"}
    )
    same_scope_policy = replace(
        fresh_attempt_same_scope.policy,
        replay_ledger=bundle.policy.replay_ledger,
    )
    with pytest.raises(gate.ExternalGateError) as scope_replay:
        gate.verify_external_gate_receipts(
            fresh_attempt_same_scope.receipt_payloads,
            fresh_attempt_same_scope.control_evidence_payloads,
            fresh_attempt_same_scope.expected,
            same_scope_policy,
            now=NOW,
        )
    assert scope_replay.value.code == "ANTI_REPLAY_REJECTED"


@pytest.mark.parametrize("failure", ["revoked", "expired", "missing"])
def test_policy_rejection_also_tombstones_scope_before_correction(
    failure: str,
) -> None:
    bundle = _bundle()
    signer_authorizations = bundle.policy.signer_authorizations
    if failure == "revoked":
        invalid_authorizations = (
            replace(signer_authorizations[0], revoked=True),
            *signer_authorizations[1:],
        )
    elif failure == "expired":
        expired = replace(signer_authorizations[0], expires_at=NOW)
        expired = _resign_signer(bundle, expired)
        invalid_authorizations = (expired, *signer_authorizations[1:])
    else:
        invalid_authorizations = signer_authorizations[:-1]
    invalid_policy = replace(
        bundle.policy,
        signer_authorizations=invalid_authorizations,
    )
    rejected = gate.intake_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        invalid_policy,
        now=NOW,
    )
    assert rejected["status"] == "REJECTED"
    assert rejected["retry_allowed"] is False

    corrected = gate.intake_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
        now=NOW,
    )
    assert corrected["status"] == "REJECTED"
    assert corrected["reason_code"] == "ANTI_REPLAY_REJECTED"
    assert corrected["retry_allowed"] is False


def test_stable_scope_allows_one_attempt_but_distinct_scope_is_independent() -> None:
    attempt_a = _bundle()
    first = gate.verify_external_gate_receipts(
        attempt_a.receipt_payloads,
        attempt_a.control_evidence_payloads,
        attempt_a.expected,
        attempt_a.policy,
        now=NOW,
    )
    assert first.replay_consumption.sequence == 1

    attempt_b = _bundle(
        expected_changes={"attempt_id": "phase8-external-attempt-002"}
    )
    same_scope_policy = replace(
        attempt_b.policy,
        replay_ledger=attempt_a.policy.replay_ledger,
    )
    with pytest.raises(gate.ExternalGateError) as same_scope:
        gate.verify_external_gate_receipts(
            attempt_b.receipt_payloads,
            attempt_b.control_evidence_payloads,
            attempt_b.expected,
            same_scope_policy,
            now=NOW,
        )
    assert same_scope.value.code == "ANTI_REPLAY_REJECTED"

    different_scope = _bundle(
        expected_changes={
            "attempt_id": "phase8-external-attempt-003",
            "candidate_sha": "e" * 40,
        }
    )
    different_scope_policy = replace(
        different_scope.policy,
        replay_ledger=attempt_a.policy.replay_ledger,
    )
    accepted = gate.verify_external_gate_receipts(
        different_scope.receipt_payloads,
        different_scope.control_evidence_payloads,
        different_scope.expected,
        different_scope_policy,
        now=NOW,
    )
    assert accepted.replay_consumption.sequence == 2
    assert accepted.replay_consumption.attempt_scope_sha256 != (
        first.replay_consumption.attempt_scope_sha256
    )


def test_six_distinct_certificate_ids_cannot_share_one_leaf_public_key() -> None:
    bundle = _bundle()
    shared_private = bundle.signer_private_keys[gate.SIGNER_ROLE_ORDER[0]]
    shared_pem = _public_pem(shared_private)
    shared_fingerprint = public_key_fingerprint_sha256(shared_pem)
    certificates: list[gate.SigningCertificate] = []
    for certificate in bundle.policy.signing_certificates:
        changed = replace(
            certificate,
            public_key_pem=shared_pem,
            public_key_fingerprint_sha256=shared_fingerprint,
        )
        certificates.append(
            replace(
                changed,
                issuer_signature=_root_signature(
                    bundle.root_private_key,
                    gate.certificate_signed_payload(changed),
                ),
            )
        )
    assert len({item.signing_key_id for item in certificates}) == 6
    assert len({item.signer_identity for item in certificates}) == 6
    assert len({item.signer_role for item in certificates}) == 6
    assert len({item.public_key_fingerprint_sha256 for item in certificates}) == 1

    shared_receipts = tuple(
        canonical_json_bytes(_seal_receipt(document, shared_private))
        for document in bundle.receipt_documents
    )
    policy = replace(bundle.policy, signing_certificates=tuple(certificates))
    _assert_external_gate(
        shared_receipts,
        bundle.control_evidence_payloads,
        bundle.expected,
        policy,
    )


def test_concurrent_identical_intake_has_exactly_one_winner() -> None:
    bundle = _bundle()

    def verify() -> str:
        try:
            gate.verify_external_gate_receipts(
                bundle.receipt_payloads,
                bundle.control_evidence_payloads,
                bundle.expected,
                bundle.policy,
                now=NOW,
            )
            return "VERIFIED"
        except gate.ExternalGateError as exception:
            return exception.code

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(lambda _: verify(), range(8)))
    assert outcomes.count("VERIFIED") == 1
    assert outcomes.count("ANTI_REPLAY_REJECTED") == 7


@pytest.mark.parametrize(
    "changes",
    [
        {"attempt_number": 2, "previous_attempt_id": "previous-attempt-id"},
        {"attempt_number": 1, "previous_attempt_id": "previous-attempt-id"},
    ],
)
def test_attempt_retry_and_predecessor_reuse_are_forbidden(changes: dict[str, Any]) -> None:
    bundle = _bundle(expected_changes=changes)
    _assert_external_gate(
        bundle.receipt_payloads,
        bundle.control_evidence_payloads,
        bundle.expected,
        bundle.policy,
    )


def test_pending_artifact_exact_schema_self_seal_and_public_validation(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    assert set(artifact) == set(gate.PENDING_ARTIFACT_FIELDS)
    assert artifact["schema_version"] == gate.PENDING_ARTIFACT_SCHEMA_VERSION
    assert artifact["self_seal_purpose"] == gate.ARTIFACT_SELF_SEAL_PURPOSE
    assert artifact["accepted"] is False
    assert artifact["external_authenticity_verified"] is False
    assert artifact["local_integrity_is_authenticity"] is False
    unsigned = dict(artifact)
    seal = unsigned.pop("validation_artifact_sha256")
    assert canonical_sha256(unsigned) == seal
    payload = gate.pending_external_artifact_bytes(artifact)
    assert type(payload) is bytes
    assert payload == canonical_json_bytes(artifact)
    assert gate.validate_pending_external_artifact(payload) == artifact


@pytest.mark.parametrize("invalid_type", ["dict", "list", "bytearray"])
def test_pending_artifact_validator_requires_exact_bytes(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    invalid_type: str,
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    payload = gate.pending_external_artifact_bytes(artifact)
    if invalid_type == "dict":
        invalid: Any = artifact
    elif invalid_type == "list":
        invalid = [artifact]
    else:
        invalid = bytearray(payload)
    with pytest.raises(gate.ExternalGateError):
        gate.validate_pending_external_artifact(invalid)


def test_pending_artifact_bytes_are_bounded_before_self_seal_validation() -> None:
    payload = b" " * (gate.MAX_PENDING_ARTIFACT_BYTES + 1)
    with pytest.raises(gate.ExternalGateError) as failure:
        gate.validate_pending_external_artifact(payload)
    assert failure.value.code == "RESOURCE_LIMIT_REJECTED"


@pytest.mark.parametrize("location", ["canonical_bundle", "chain_key"])
def test_pending_artifact_oversized_nested_strings_reject_before_crypto(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    location: str,
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    oversized = "x" * (gate.MAX_PENDING_ARTIFACT_STRING_BYTES + 1)
    if location == "canonical_bundle":
        artifact["control_evidence_bindings"][0]["canonical_bundle"] = oversized
    else:
        artifact["trust_chain_artifact"]["trust_roots"][0][
            "public_key_pem_base64"
        ] = oversized
    payload = canonical_json_bytes(artifact)
    assert len(payload) < gate.MAX_PENDING_ARTIFACT_BYTES
    with pytest.raises(gate.ExternalGateError) as failure:
        gate.validate_pending_external_artifact(payload)
    assert failure.value.code == "RESOURCE_LIMIT_REJECTED"


def test_pending_artifact_deep_value_rejects_without_recursion_escape(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    nested: Any = "leaf"
    for _ in range(gate.MAX_PENDING_ARTIFACT_DEPTH + 1):
        nested = [nested]
    artifact["validation_policy"]["freshness_status"] = nested
    payload = canonical_json_bytes(artifact)
    with pytest.raises(gate.ExternalGateError) as failure:
        gate.validate_pending_external_artifact(payload)
    assert failure.value.code == "STRICT_SHAPE_REJECTED"


def test_pending_artifact_token_bomb_rejects_before_self_seal() -> None:
    token_count = gate.MAX_PENDING_ARTIFACT_TOKENS // 2 + 1
    payload = b'{"x":[' + b",".join(b"0" for _ in range(token_count)) + b"]}"
    assert len(payload) < gate.MAX_PENDING_ARTIFACT_BYTES
    with pytest.raises(gate.ExternalGateError) as failure:
        gate.validate_pending_external_artifact(payload)
    assert failure.value.code == "STRICT_SHAPE_REJECTED"


@pytest.mark.parametrize(
    "component",
    [
        "receipt_set",
        "trust_chain",
        "revocation",
        "validation_policy",
        "replay",
        "context",
        "control_bindings",
        "receipt_bindings",
    ],
)
def test_unsealed_sibling_artifact_substitution_is_rejected(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    component: str,
) -> None:
    first, second = pending_artifact_pair
    artifact = copy.deepcopy(first)
    if component == "receipt_set":
        artifact["receipt_set_preimage"] = copy.deepcopy(
            second["receipt_set_preimage"]
        )
        artifact["receipt_set_sha256"] = second["receipt_set_sha256"]
    elif component == "trust_chain":
        artifact["trust_chain_artifact"] = copy.deepcopy(
            second["trust_chain_artifact"]
        )
    elif component == "revocation":
        artifact["revocation_snapshot_sha256"] = second[
            "revocation_snapshot_sha256"
        ]
        artifact["trust_chain_artifact"]["revocation_snapshot"] = copy.deepcopy(
            second["trust_chain_artifact"]["revocation_snapshot"]
        )
    elif component == "validation_policy":
        artifact["validation_policy"] = copy.deepcopy(second["validation_policy"])
    elif component == "replay":
        artifact["anti_replay_consumption"] = copy.deepcopy(
            second["anti_replay_consumption"]
        )
    elif component == "context":
        artifact["validated_context"] = copy.deepcopy(second["validated_context"])
    elif component == "control_bindings":
        artifact["control_evidence_bindings"] = copy.deepcopy(
            second["control_evidence_bindings"]
        )
    else:
        artifact["receipt_bindings"] = copy.deepcopy(second["receipt_bindings"])
    _assert_pending_artifact_rejected(artifact)


@pytest.mark.parametrize(
    "field",
    [
        "canonical_bundle",
        "canonical_bundle_sha256",
        "nonce",
        "step_evidence_sha256",
        "stop_evidence_sha256",
        "rollback_evidence_sha256",
        "evidence_sha256",
        "source_payload_base64",
        "source_payload_sha256",
    ],
)
def test_resealed_control_binding_substitutions_are_rejected(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    field: str,
) -> None:
    first, _ = pending_artifact_pair
    artifact = copy.deepcopy(first)
    replacement_by_field: dict[str, Any] = {
        "canonical_bundle": "{}",
        "canonical_bundle_sha256": DIGEST_D,
        "nonce": "f" * 64,
        "step_evidence_sha256": DIGEST_D,
        "stop_evidence_sha256": DIGEST_D,
        "rollback_evidence_sha256": DIGEST_D,
        "evidence_sha256": DIGEST_D,
        "source_payload_base64": _b64(b"{}"),
        "source_payload_sha256": DIGEST_D,
    }
    replacement = replacement_by_field[field]
    assert artifact["control_evidence_bindings"][0][field] != replacement
    artifact["control_evidence_bindings"][0][field] = replacement
    _assert_pending_artifact_rejected(_reseal_pending_artifact(artifact))


@pytest.mark.parametrize(
    "field",
    [
        "canonical_signed_payload",
        "signer_identity",
        "signer_role",
        "certificate_serial",
        "certificate_fingerprint_sha256",
        "signature",
        "signed_payload_sha256",
        "source_payload_base64",
        "source_payload_sha256",
    ],
)
def test_resealed_nonfirst_role_receipt_substitutions_keep_old_hash_and_reject(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    field: str,
) -> None:
    first, second = pending_artifact_pair
    artifact = copy.deepcopy(first)
    receipt_index = 4
    original_receipt_hash = artifact["receipt_bindings"][receipt_index][
        "receipt_sha256"
    ]
    replacement_by_field: dict[str, Any] = {
        "canonical_signed_payload": second["receipt_bindings"][receipt_index][
            "canonical_signed_payload"
        ],
        "signer_identity": "substituted-signer-independent",
        "signer_role": gate.SIGNER_ROLE_ORDER[5],
        "certificate_serial": "certificate-serial-substituted",
        "certificate_fingerprint_sha256": DIGEST_D,
        "signature": second["receipt_bindings"][receipt_index]["signature"],
        "signed_payload_sha256": second["receipt_bindings"][receipt_index][
            "signed_payload_sha256"
        ],
        "source_payload_base64": second["receipt_bindings"][receipt_index][
            "source_payload_base64"
        ],
        "source_payload_sha256": second["receipt_bindings"][receipt_index][
            "source_payload_sha256"
        ],
    }
    replacement = replacement_by_field[field]
    assert artifact["receipt_bindings"][receipt_index][field] != replacement
    artifact["receipt_bindings"][receipt_index][field] = replacement
    assert artifact["receipt_bindings"][receipt_index]["receipt_sha256"] == (
        original_receipt_hash
    )
    _assert_pending_artifact_rejected(_reseal_pending_artifact(artifact))


@pytest.mark.parametrize(
    ("collection", "operation", "index"),
    [
        ("trust_roots", "delete", 0),
        ("trust_roots", "swap", 0),
        ("signing_certificates", "delete", 4),
        ("signing_certificates", "swap", 4),
        ("operator_authorizations", "delete", 0),
        ("operator_authorizations", "swap", 0),
        ("signer_authorizations", "delete", 4),
        ("signer_authorizations", "swap", 4),
    ],
)
def test_resealed_trust_chain_component_deletion_or_swap_is_rejected(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    collection: str,
    operation: str,
    index: int,
) -> None:
    first, second = pending_artifact_pair
    artifact = copy.deepcopy(first)
    values = artifact["trust_chain_artifact"][collection]
    if operation == "delete":
        values.pop(index)
    else:
        values[index] = copy.deepcopy(
            second["trust_chain_artifact"][collection][index]
        )
    _assert_pending_artifact_rejected(_reseal_pending_artifact(artifact))


@pytest.mark.parametrize(
    "component",
    [
        "revocation",
        "validation_policy",
        "replay_scope",
        "replay_nonces",
        "context",
        "receipt_set",
    ],
)
def test_resealed_cross_artifact_policy_replay_and_context_swaps_are_rejected(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    component: str,
) -> None:
    first, second = pending_artifact_pair
    artifact = copy.deepcopy(first)
    if component == "revocation":
        artifact["trust_chain_artifact"]["revocation_snapshot"] = copy.deepcopy(
            second["trust_chain_artifact"]["revocation_snapshot"]
        )
        artifact["revocation_snapshot_sha256"] = second[
            "revocation_snapshot_sha256"
        ]
    elif component == "validation_policy":
        artifact["validation_policy"] = copy.deepcopy(second["validation_policy"])
    elif component == "replay_scope":
        artifact["anti_replay_consumption"]["attempt_scope_sha256"] = second[
            "anti_replay_consumption"
        ]["attempt_scope_sha256"]
    elif component == "replay_nonces":
        artifact["anti_replay_consumption"]["nonces"] = copy.deepcopy(
            second["anti_replay_consumption"]["nonces"]
        )
    elif component == "context":
        artifact["validated_context"] = copy.deepcopy(second["validated_context"])
    else:
        artifact["receipt_set_preimage"] = copy.deepcopy(
            second["receipt_set_preimage"]
        )
        artifact["receipt_set_sha256"] = second["receipt_set_sha256"]
    _assert_pending_artifact_rejected(_reseal_pending_artifact(artifact))


@pytest.mark.parametrize(
    ("binding_collection", "mutation"),
    [
        ("control_evidence_bindings", "missing_preimage"),
        ("control_evidence_bindings", "missing_raw_payload"),
        ("control_evidence_bindings", "unknown_field"),
        ("receipt_bindings", "missing_preimage"),
        ("receipt_bindings", "missing_raw_payload"),
        ("receipt_bindings", "unknown_field"),
    ],
)
def test_resealed_artifact_binding_schema_requires_full_preimages(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    binding_collection: str,
    mutation: str,
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    binding = artifact[binding_collection][0]
    if mutation == "missing_preimage":
        field = (
            "canonical_bundle"
            if binding_collection == "control_evidence_bindings"
            else "canonical_signed_payload"
        )
        binding.pop(field)
    elif mutation == "missing_raw_payload":
        binding.pop("source_payload_base64")
    else:
        binding["unknown"] = "not-frozen"
    _assert_pending_artifact_rejected(_reseal_pending_artifact(artifact))


@pytest.mark.parametrize(
    "mutation",
    [
        "control_reordered",
        "control_missing",
        "receipt_role_reordered",
        "receipt_missing",
        "receipt_duplicate_role",
        "certificate_role_reordered",
        "signer_authorization_role_reordered",
    ],
)
def test_resealed_artifact_cardinality_order_and_six_role_shape_are_exact(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    mutation: str,
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    if mutation == "control_reordered":
        artifact["control_evidence_bindings"][0:2] = reversed(
            artifact["control_evidence_bindings"][0:2]
        )
    elif mutation == "control_missing":
        artifact["control_evidence_bindings"].pop()
    elif mutation == "receipt_role_reordered":
        artifact["receipt_bindings"][4:6] = reversed(
            artifact["receipt_bindings"][4:6]
        )
    elif mutation == "receipt_missing":
        artifact["receipt_bindings"].pop()
    elif mutation == "receipt_duplicate_role":
        artifact["receipt_bindings"][5] = copy.deepcopy(
            artifact["receipt_bindings"][4]
        )
    elif mutation == "certificate_role_reordered":
        certificates = artifact["trust_chain_artifact"]["signing_certificates"]
        certificates[4:6] = reversed(certificates[4:6])
    else:
        authorizations = artifact["trust_chain_artifact"]["signer_authorizations"]
        authorizations[4:6] = reversed(authorizations[4:6])
    _assert_pending_artifact_rejected(_reseal_pending_artifact(artifact))


@pytest.mark.parametrize("mutation", ["missing", "unknown", "authority_escalation"])
def test_pending_artifact_top_level_schema_and_authority_are_exact(
    pending_artifact_pair: tuple[dict[str, Any], dict[str, Any]],
    mutation: str,
) -> None:
    artifact = copy.deepcopy(pending_artifact_pair[0])
    if mutation == "missing":
        artifact.pop("validated_context")
    elif mutation == "unknown":
        artifact["unknown"] = "not-frozen"
    else:
        artifact["accepted"] = True
        _reseal_pending_artifact(artifact)
    _assert_pending_artifact_rejected(artifact)


def test_module_imports_exports_and_calls_match_exact_capability_allowlists() -> None:
    source = MODULE_PATH.read_text(encoding="utf-8")
    tree = ast.parse(source)
    observed_imports: list[tuple[Any, ...]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            observed_imports.extend(
                ("import", alias.name, alias.asname) for alias in node.names
            )
        elif isinstance(node, ast.ImportFrom):
            observed_imports.extend(
                ("from", node.level, node.module, alias.name, alias.asname)
                for alias in node.names
            )
    assert tuple(observed_imports) == (
        ("from", 0, "__future__", "annotations", None),
        ("import", "base64", None),
        ("import", "binascii", None),
        ("import", "copy", None),
        ("import", "hashlib", None),
        ("import", "re", None),
        ("import", "threading", None),
        ("from", 0, "dataclasses", "dataclass", None),
        ("from", 0, "dataclasses", "replace", None),
        ("from", 0, "datetime", "datetime", None),
        ("from", 0, "datetime", "timedelta", None),
        ("from", 0, "datetime", "timezone", None),
        ("from", 0, "typing", "Any", None),
        ("from", 0, "typing", "Mapping", None),
        ("from", 0, "typing", "Sequence", None),
        ("from", 1, "evidence_schema", "ALLOWED_SIGNATURE_ALGORITHMS", None),
        ("from", 1, "evidence_schema", "EvidenceValidationError", None),
        ("from", 1, "evidence_schema", "canonical_json_bytes", None),
        ("from", 1, "evidence_schema", "canonical_sha256", None),
        ("from", 1, "evidence_schema", "decode_signature", None),
        ("from", 1, "evidence_schema", "parse_bounded_json_bytes", None),
        ("from", 1, "evidence_schema", "parse_rfc3339", None),
        (
            "from",
            1,
            "evidence_schema",
            "public_key_fingerprint_sha256",
            None,
        ),
        ("from", 1, "evidence_schema", "verify_detached_signature", None),
    )

    expected_exports = set(
        """
        ARTIFACT_SELF_SEAL_PURPOSE ATTEMPT_SCOPE_SCHEMA_VERSION AUTHORIZATION_SCOPE
        CERTIFICATE_SCHEMA_VERSION CHECKPOINT_ORDER CONTROL_EVIDENCE_FIELDS
        CONTROL_EVIDENCE_SCHEMA_VERSION CONTROL_OBSERVATION_PROVENANCE CandidatePathBlob
        EVIDENCE_SOURCE EVALUATION_TIME_PROVENANCE EXTERNAL_AUTHORITY_CEILING
        ExpectedExternalContext ExternalGateError ExternalTrustPolicy
        FROZEN_RECEIPT_REQUIRED_FIELDS FROZEN_SIGNED_PAYLOAD_FIELDS ImageBinding
        MAX_CONTROL_EVIDENCE_PAYLOAD_BYTES MAX_EXTERNAL_INTAKE_BYTES
        MAX_JSON_NESTING_DEPTH MAX_JSON_STRING_BYTES MAX_JSON_TOKENS_PER_PAYLOAD
        MAX_PENDING_ARTIFACT_BYTES MAX_PENDING_ARTIFACT_DEPTH
        MAX_PENDING_ARTIFACT_STRING_BYTES MAX_PENDING_ARTIFACT_TOKENS
        MAX_RECEIPT_PAYLOAD_BYTES OPERATOR_AUTHORIZATION_SCHEMA_VERSION
        OperatorAuthorization PinnedTrustAnchor PENDING_ARTIFACT_FIELDS
        PENDING_ARTIFACT_SCHEMA_VERSION RECEIPT_FIELDS RECEIPT_SCHEMA_VERSION
        REQUIRED_CONTROL_IDS ReplayConsumption ReplayLedger REPLAY_DURABILITY
        REVOCATION_SNAPSHOT_SCHEMA_VERSION REVOCATION_SNAPSHOT_SOURCE REVOCATION_STATUS
        RevocationSnapshot SCENARIO_ID SIGNED_PAYLOAD_FIELDS
        SIGNER_AUTHORIZATION_SCHEMA_VERSION SIGNER_ROLE_ORDER STEP_ID
        SignerAuthorization SigningCertificate TrustRoot TRUST_ROOT_PROVENANCE
        ValidatedControlEvidence ValidatedControlReceipt
        ValidatedPendingExternalReceiptSet calculate_attempt_scope_sha256
        certificate_signed_payload intake_external_gate
        operator_authorization_signed_payload pending_external_artifact_bytes
        receipt_sha256 receipt_signed_payload revocation_snapshot_payload
        revocation_snapshot_sha256 signer_authorization_signed_payload
        trust_anchor_set_payload trust_anchor_set_sha256
        validate_pending_external_artifact verify_external_gate_receipts
        """.split()
    )
    assert set(gate.__all__) == expected_exports
    assert len(gate.__all__) == len(expected_exports)

    observed_name_calls: set[str] = set()
    observed_attribute_calls: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        if isinstance(node.func, ast.Name):
            observed_name_calls.add(node.func.id)
        elif isinstance(node.func, ast.Attribute):
            observed_attribute_calls.add(node.func.attr)
        else:
            pytest.fail(
                "dynamic, subscripted, lambda, or returned callable target is forbidden"
            )
    expected_name_calls = set(
        """
        CandidatePathBlob ExpectedExternalContext ExternalGateError ExternalTrustPolicy
        ImageBinding OperatorAuthorization PinnedTrustAnchor ReplayConsumption ReplayLedger
        RevocationSnapshot SignerAuthorization SigningCertificate TrustRoot
        ValidatedControlEvidence ValidatedControlReceipt ValidatedPendingExternalReceiptSet
        _assert_pending_artifact_object_bounds _attempt_scope_sha256 _aware_utc
        _control_binding_artifact _decode_artifact_bytes _exact_keys
        _expected_context_document _expected_context_from_artifact _expected_metadata
        _fail _format_time _parse_control_evidence _pending_authority
        _policy_from_artifact _receipt_binding_artifact _replay_consumption_artifact
        _sha _snapshot_expected_context _snapshot_policy_shape _strict_equal _timestamp
        _token _trust_chain_artifact _valid_at _validate_authorization_scope
        _validate_control_facts _validate_expected_context
        _validate_pending_external_artifact _validate_policy _validation_policy_artifact
        _verify_receipt _verify_root_signature all any canonical_json_bytes canonical_sha256
        certificate_signed_payload dataclass decode_signature dict enumerate frozenset
        getattr id isinstance len list map next operator_authorization_signed_payload
        parse_bounded_json_bytes parse_rfc3339 public_key_fingerprint_sha256
        receipt_sha256 receipt_signed_payload replace revocation_snapshot_payload
        revocation_snapshot_sha256 set signer_authorization_signed_payload sorted str sum
        super timedelta trust_anchor_set_payload tuple type verify_detached_signature
        verify_external_gate_receipts zip
        """.split()
    )
    expected_attribute_calls = set(
        """
        Lock __init__ add append as_dict astimezone b64decode b64encode bit_length
        casefold claim_attempt compile consume_once decode deepcopy encode extend fullmatch
        get hexdigest isoformat isspace issubset items pop replace sha256 update utcoffset
        values
        """.split()
    )
    assert observed_name_calls == expected_name_calls
    assert observed_attribute_calls == expected_attribute_calls
    assert not observed_name_calls & {
        "__import__",
        "eval",
        "exec",
        "open",
    }
    assert not {
        "builtins",
        "ctypes",
        "importlib",
        "io",
        "pathlib",
    } & {item[1] for item in observed_imports if item[0] == "import"}
