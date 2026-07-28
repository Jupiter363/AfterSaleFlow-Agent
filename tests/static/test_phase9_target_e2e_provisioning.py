from __future__ import annotations

import base64
import datetime as dt
import hashlib
import importlib
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts" / "target-e2e"
sys.path.insert(0, str(SCRIPTS))
common = importlib.import_module("common")
ledger = importlib.import_module("ledger")
provision = importlib.import_module("provision")


def _decode(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def test_fixture_hash_uses_the_actual_canonical_fixture_bytes(tmp_path: Path) -> None:
    document, canonical, digest = provision._canonical_fixture(
        provision.SYNTHETIC_FIXTURE_SOURCE
    )

    assert canonical == common.canonical_bytes(document)
    assert digest == hashlib.sha256(canonical).hexdigest()
    assert digest == "ca73ad73f54d19354e593f544db67dd658397661e893b2401d726dc32a37d1aa"
    assert digest != hashlib.sha256(b"candidate:run:fixture-set").hexdigest()

    duplicate = tmp_path / "duplicate.json"
    duplicate.write_text('{"schemaVersion":"x","schemaVersion":"y"}', encoding="utf-8")
    try:
        provision._canonical_fixture(duplicate)
    except common.TargetE2EError as error:
        assert "duplicate JSON member" in str(error)
    else:
        raise AssertionError("duplicate fixture members must fail closed")


def test_isolation_attestation_is_canonical_hash_bound_and_es256_signed() -> None:
    now = dt.datetime(2026, 7, 28, 4, 0, tzinfo=dt.timezone.utc)
    images = {
        "javaApi": "sha256:" + "1" * 64,
        "temporalControlWorker": "sha256:" + "1" * 64,
        "temporalAgentWorker": "sha256:" + "1" * 64,
        "pythonAgent": "sha256:" + "2" * 64,
        "frontend": "sha256:" + "3" * 64,
    }
    domain = {
        "clusterIdentity": "domain-cluster",
        "databaseIdentity": "domain-db",
        "runtimePrincipalIdentity": "domain-runtime",
    }
    graph = {
        "clusterIdentity": "graph-cluster",
        "databaseIdentity": "graph-db",
        "runtimePrincipalIdentity": "graph-runtime",
    }
    payload = provision._isolation_attestation_payload(
        now=now,
        environment_id="p9-isolated-run001",
        environment_generation=17,
        candidate="a" * 40,
        artifact_digest="b" * 64,
        image_digests=images,
        domain_identity=domain,
        graph_identity=graph,
        attestation_nonce="p9-isolation-nonce-0000000000000000",
    )

    claimed_hash = payload["attestationHash"]
    unsigned = dict(payload)
    unsigned.pop("attestationHash")
    assert claimed_hash == common.canonical_sha256(unsigned)
    assert payload["networkIsolationEnforced"] is True
    assert payload["graphDomainCredentialsPresent"] is False
    assert payload["graphDomainPrivilegesPresent"] is False
    assert payload["externalEffectEndpointsEnabled"] is False
    assert dt.datetime.fromisoformat(payload["expiresAt"]) - dt.datetime.fromisoformat(
        payload["issuedAt"]
    ) == dt.timedelta(minutes=15)

    private_key = ec.generate_private_key(ec.SECP256R1())
    compact = ledger.sign_compact_jws(
        payload,
        private_key,
        key_id="p9-isolation-attestation-aaaaaaaaaaaa",
        typ="target-e2e-runtime-measurement+jwt",
    )
    protected_segment, payload_segment, signature_segment = compact.split(".")
    assert json.loads(_decode(protected_segment)) == {
        "alg": "ES256",
        "kid": "p9-isolation-attestation-aaaaaaaaaaaa",
        "typ": "target-e2e-runtime-measurement+jwt",
    }
    assert _decode(payload_segment) == common.canonical_bytes(payload)
    raw_signature = _decode(signature_segment)
    assert len(raw_signature) == 64
    private_key.public_key().verify(
        encode_dss_signature(
            int.from_bytes(raw_signature[:32], "big"),
            int.from_bytes(raw_signature[32:], "big"),
        ),
        f"{protected_segment}.{payload_segment}".encode("ascii"),
        ec.ECDSA(hashes.SHA256()),
    )


def test_java_artifact_digest_is_measured_from_locked_image_bytes(
    tmp_path: Path, monkeypatch
) -> None:
    calls: list[list[str]] = []
    expected = b"sealed target E2E jar bytes"

    def fake_output(arguments: list[str]) -> str:
        calls.append(arguments)
        return "a" * 64

    def fake_run(arguments: list[str]) -> None:
        calls.append(arguments)
        if arguments[1] == "cp":
            Path(arguments[-1]).write_bytes(expected)

    monkeypatch.setattr(provision, "_run_output", fake_output)
    monkeypatch.setattr(provision, "_run", fake_run)

    digest = provision._java_artifact_digest(
        "docker.exe",
        "registry.example/after-sale-java@sha256:" + "c" * 64,
        tmp_path,
    )

    assert digest == hashlib.sha256(expected).hexdigest()
    assert calls[0][1:4] == ["create", "--entrypoint", "/bin/true"]
    assert any(
        call[1:3] == ["cp", "a" * 64 + ":/home/app/app-target-e2e.jar"]
        for call in calls
    )
    assert calls[-1] == ["docker.exe", "rm", "--force", "a" * 64]


def test_provisioning_outputs_only_public_runtime_material_to_activation_mount() -> (
    None
):
    source = (SCRIPTS / "provision.py").read_text(encoding="utf-8")

    assert 'activation_dir / "isolation-attestation.public.pem"' in source
    assert 'activation_dir / "isolation-attestation.jws"' in source
    assert 'activation_dir / "isolation-attestation.json"' in source
    assert 'activation_dir / "synthetic-fixtures"' in source
    assert '"isolation-attestation-signing"' in source
    assert 'fixture-set".encode' not in source
    assert "TARGET_E2E_ISOLATION_ATTESTATION_KEY_ID" in source
    assert "TARGET_E2E_SYNTHETIC_FIXTURE_SHA256" in source
