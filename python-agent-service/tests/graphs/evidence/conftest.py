from __future__ import annotations

import base64
import hashlib
import json
from copy import deepcopy
from pathlib import Path

import jwt
import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from langchain_core.runnables import RunnableLambda

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.evidence.contracts import (
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceAdmissionRequest,
    EvidenceAdmissionVerifier,
    EvidenceGraphContext,
    EvidenceJavaSigningKeyPin,
    EvidenceJavaSigningTrustConfig,
    JsonObject,
    VerifiedEvidenceAdmission,
)
from app.security.jwks import JwksVerificationKeyResolver


ROOT = Path(__file__).resolve().parents[4]
CONTRACT_ROOT = ROOT / "contracts" / "agent-platform"
EVIDENCE_FIXTURES = CONTRACT_ROOT / "evidence" / "v2" / "fixtures" / "valid"
COMMAND_FIXTURE = (
    CONTRACT_ROOT / "v1" / "fixtures" / "valid" / "room-graph-command-evidence-valid.json"
)
SIGNING_KEY_ID = "KEY_P5_SYNTHETIC_ES256_1"
PRIVATE_KEY = ec.generate_private_key(ec.SECP256R1())


def service_jwks_resolver(
    *,
    private_key: ec.EllipticCurvePrivateKey = PRIVATE_KEY,
    signing_key_id: str = SIGNING_KEY_ID,
) -> JwksVerificationKeyResolver:
    public_jwk = jwt.algorithms.ECAlgorithm.to_jwk(
        private_key.public_key(),
        as_dict=True,
    )
    resolver = JwksVerificationKeyResolver()
    resolver.install(
        {
            "keys": [
                {
                    **public_jwk,
                    "kid": signing_key_id,
                    "use": "sig",
                    "alg": "ES256",
                }
            ]
        }
    )
    return resolver


def service_trust_config(
    *,
    private_key: ec.EllipticCurvePrivateKey = PRIVATE_KEY,
    signing_key_id: str = SIGNING_KEY_ID,
    version: str = "evidence-java-keys.synthetic.v1",
) -> EvidenceJavaSigningTrustConfig:
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return EvidenceJavaSigningTrustConfig(
        version=version,
        keys=(
            EvidenceJavaSigningKeyPin(
                signing_key_id=signing_key_id,
                public_key_sha256=hashlib.sha256(public_der).hexdigest(),
            ),
        ),
    )


def load_manifest(count: int) -> JsonObject:
    names = {
        1: "evidence-batch-manifest-synthetic-1-valid.json",
        8: "evidence-batch-manifest-synthetic-8-valid.json",
        100: "evidence-batch-manifest-synthetic-100-valid.json",
    }
    return json.loads((EVIDENCE_FIXTURES / names[count]).read_text(encoding="utf-8"))


def make_request(count: int = 1) -> EvidenceAdmissionRequest:
    manifest = load_manifest(count)
    command = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    binding = manifest["command_binding"]
    profiles = manifest["profile_versions"]
    for name in ("command_id", "logical_run_id", "attempt_id"):
        command[name] = binding[name]
    for name in ("tenant_surrogate", "case_id", "room_type", "room_epoch", "thread_id"):
        command[name] = manifest[name]
    command["deadline_at"] = binding["deadline_at"]
    command["graph_version"] = profiles["graph_version"]
    command["checkpoint_schema_version"] = profiles["checkpoint_schema_version"]
    command["invocation_context"].update(
        prompt_profile_id=profiles["prompt_version"],
        model_profile_id=profiles["model_profile_id"],
        output_schema_version=profiles["terminal_output_schema_version"],
        policy_version=profiles["policy_version"],
        guardrail_version=profiles["guardrail_version"],
    )
    return refresh_request(
        EvidenceAdmissionRequest(
            runtime_mode="SHADOW",
            room_graph_command=command,
            signed_manifest_payload=canonicalize(manifest),
            registry_output_schema_version=TERMINAL_OUTPUT_SCHEMA_VERSION,
            graph_lease_fencing_token=41,
        ),
        manifest=manifest,
        refresh_internal_manifest_hash=True,
        resign=True,
    )


def make_admission(count: int = 1) -> VerifiedEvidenceAdmission:
    return EvidenceAdmissionVerifier.from_service_jwks(
        service_jwks_resolver(),
        service_trust_config(),
    ).verify(make_request(count))


def refresh_request(
    request: EvidenceAdmissionRequest,
    *,
    command: JsonObject | None = None,
    manifest: JsonObject | None = None,
    refresh_internal_manifest_hash: bool = False,
    resign: bool = False,
    signing_key: ec.EllipticCurvePrivateKey = PRIVATE_KEY,
) -> EvidenceAdmissionRequest:
    updated_manifest = deepcopy(
        manifest
        if manifest is not None
        else json.loads(request.signed_manifest_payload.decode("utf-8"))
    )
    updated_command = deepcopy(command if command is not None else dict(request.room_graph_command))
    updated_manifest.setdefault("signing_key_id", SIGNING_KEY_ID)
    if refresh_internal_manifest_hash:
        preimage = dict(updated_manifest)
        preimage.pop("manifest_hash", None)
        preimage.pop("signature", None)
        updated_manifest["manifest_hash"] = canonical_sha256(preimage)
    if resign:
        der_signature = signing_key.sign(
            updated_manifest["manifest_hash"].encode("ascii"),
            ec.ECDSA(hashes.SHA256()),
        )
        r, s = decode_dss_signature(der_signature)
        p1363 = r.to_bytes(32, "big") + s.to_bytes(32, "big")
        updated_manifest["signature"] = base64.urlsafe_b64encode(p1363).rstrip(b"=").decode()
    payload = canonicalize(updated_manifest)
    payload_hash = canonical_sha256(updated_manifest)
    snapshot = updated_command["domain_snapshot_ref"]
    snapshot.update(
        artifact_id=updated_manifest["manifest_id"],
        schema_version=updated_manifest["schema_version"],
        sha256=payload_hash,
        size_bytes=len(payload),
        uri=(
            f"s3://evidence-synthetic-manifests/{updated_manifest['case_id']}/"
            f"epoch-{updated_manifest['room_epoch']}/{payload_hash}.json"
        ),
    )
    command_preimage = dict(updated_command)
    command_preimage.pop("request_hash", None)
    updated_command["request_hash"] = canonical_sha256(command_preimage)
    return EvidenceAdmissionRequest(
        runtime_mode="SHADOW",
        room_graph_command=updated_command,
        signed_manifest_payload=payload,
        registry_output_schema_version=request.registry_output_schema_version,
        graph_lease_fencing_token=request.graph_lease_fencing_token,
    )


def assessment_for_work_item(work_item: JsonObject) -> JsonObject:
    binding = work_item["command_binding"]
    item = work_item["item"]
    evidence_id = item["evidence_id"]
    assessment: JsonObject = {
        "schema_version": "evidence-item-assessment.v1",
        "execution_scope": "SIGNED_SYNTHETIC_ONLY",
        "formal_sink_eligible": False,
        "command_id": binding["command_id"],
        "logical_run_id": binding["logical_run_id"],
        "attempt_id": binding["attempt_id"],
        "thread_id": work_item["thread_id"],
        "manifest_id": work_item["manifest_id"],
        "manifest_hash": work_item["manifest_hash"],
        "evidence_id": evidence_id,
        "item_hash": item["item_hash"],
        "formal_evidence_revision": item["formal_evidence_revision"],
        "actor_scope_hash": work_item["actor_scope_hash"],
        "profile_versions": deepcopy(work_item["profile_versions"]),
        "assessment_status": "COMPLETED",
        "authenticity_score": 0.91,
        "authenticity_reason_codes": ["SOURCE_HASH_MATCHED"],
        "relevance_score": 0.86,
        "relevance_reason_codes": ["FACT_LINK_MATCHED"],
        "completeness_score": 0.8,
        "confidence": 0.84,
        "candidate_fact_links": [
            {"fact_id": "FACT_ORDER_DAMAGE", "source_refs": ["SOURCE_SYNTHETIC"]}
        ],
        "source_refs": ["SOURCE_SYNTHETIC"],
        "inspected_modalities": ["PDF_METADATA", "TEXT"],
        "asset_load_status": "LOADED",
        "asset_load_receipt_ref": f"ASSET_RECEIPT_{evidence_id}",
        "asset_load_receipt_hash": "2" * 64,
        "limitations": ["SYNTHETIC_FIXTURE_ONLY"],
        "review_reasons": [],
    }
    assessment["assessment_hash"] = canonical_sha256(assessment)
    return assessment


@pytest.fixture
def admission() -> VerifiedEvidenceAdmission:
    return make_admission(1)


@pytest.fixture
def context(admission: VerifiedEvidenceAdmission) -> EvidenceGraphContext:
    return EvidenceGraphContext(admission=admission, completed_at="2026-07-22T12:05:00Z")


@pytest.fixture
def assessor() -> RunnableLambda:
    return RunnableLambda(assessment_for_work_item)


@pytest.fixture
def admission_factory():
    return make_admission


@pytest.fixture
def admission_refresher():
    return refresh_request


@pytest.fixture
def admission_request_factory():
    return make_request


@pytest.fixture
def admission_verifier_factory():
    def factory(resolver=None, trust_config=None):
        return EvidenceAdmissionVerifier.from_service_jwks(
            resolver or service_jwks_resolver(),
            trust_config or service_trust_config(),
        )

    return factory


@pytest.fixture
def service_jwks_factory():
    return service_jwks_resolver


@pytest.fixture
def service_trust_config_factory():
    return service_trust_config


@pytest.fixture
def assessment_factory():
    return assessment_for_work_item
