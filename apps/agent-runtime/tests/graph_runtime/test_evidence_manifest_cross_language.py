from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.graph_runtime.target_e2e_room_adapters import TargetE2EObjectEvidenceAssetLoader
from app.graphs.evidence.contracts import (
    EvidenceAdmissionRequest,
    EvidenceAdmissionVerifier,
)
from app.security.graph_runtime import _open_for_lifecycle


_FIXTURE_ENV = "TARGET_E2E_JAVA_EVIDENCE_FIXTURE"


class _FixtureObjectStore:
    def __init__(self, payloads: dict[str, bytes]) -> None:
        self._payloads = payloads
        self.references = []

    async def load(self, reference):  # type: ignore[no-untyped-def]
        self.references.append(reference)
        return self._payloads[reference.uri]


def _fixture_path() -> Path:
    value = os.environ.get(_FIXTURE_ENV)
    if not value:
        pytest.skip(f"{_FIXTURE_ENV} is provided by the focused Java contract test")
    return Path(value)


async def _empty_referenced_keys() -> tuple[str, ...]:
    return ()


def _runtime(jwk: dict[str, str]):
    document = {"keys": [jwk]}

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(
            200,
            json=document,
            headers={"content-type": "application/jwk-set+json"},
        )

    runner = asyncio.Runner()
    runtime = runner.run(
        _open_for_lifecycle(
            jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
            timeout_seconds=1,
            refresh_interval_seconds=3600,
            referenced_key_ids=_empty_referenced_keys,
            transport=httpx.MockTransport(handler),
        )
    )
    return runner, runtime


def test_java_published_evidence_manifest_is_admitted_and_asset_is_exactly_loaded() -> None:
    fixture = json.loads(_fixture_path().read_text(encoding="utf-8"))
    manifest_payload = base64.b64decode(fixture["manifest_payload_b64"])
    asset_payload = base64.b64decode(fixture["asset_payload_b64"])
    manifest = json.loads(manifest_payload)
    asset = json.loads(asset_payload)

    # Reconstruct the Java public JWK from its P-256 affine coordinates.
    public_key = ec.EllipticCurvePublicNumbers(
        int.from_bytes(base64.urlsafe_b64decode(fixture["jwk"]["x"] + "=="), "big"),
        int.from_bytes(base64.urlsafe_b64decode(fixture["jwk"]["y"] + "=="), "big"),
        ec.SECP256R1(),
    ).public_key()
    assert jwt.algorithms.ECAlgorithm.from_jwk(json.dumps(fixture["jwk"])).public_numbers() == public_key.public_numbers()

    runner, runtime = _runtime(fixture["jwk"])
    try:
        verifier = EvidenceAdmissionVerifier.from_security_runtime(runtime)
        admission = verifier._verify_target_candidate(  # noqa: SLF001
            EvidenceAdmissionRequest(
                runtime_mode="SHADOW",
                room_graph_command=fixture["command"],
                signed_manifest_payload=manifest_payload,
                registry_output_schema_version="target-e2e-room-proposal-source.v1",
                graph_lease_fencing_token=fixture["graph_lease_fencing_token"],
            )
        )
        assert admission.manifest["manifest_hash"] == fixture["manifest_hash"]
        assert hashlib.sha256(manifest_payload).hexdigest() == fixture["command"]["domain_snapshot_ref"]["sha256"]
        assert len(manifest_payload) == fixture["command"]["domain_snapshot_ref"]["size_bytes"]

        item = manifest["items"][0]
        store = _FixtureObjectStore({item["parse_ref"]: asset_payload})
        loaded = runner.run(TargetE2EObjectEvidenceAssetLoader(store).load(item))

        assert loaded.content == asset["content"]
        assert store.references[0].uri == item["parse_ref"]
        assert store.references[0].sha256 == item["parse_hash"]
        assert hashlib.sha256(asset_payload).hexdigest() == item["parse_hash"]
    finally:
        runner.run(runtime.close())
        runner.close()
