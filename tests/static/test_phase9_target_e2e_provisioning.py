from __future__ import annotations

import base64
import datetime as dt
import hashlib
import importlib
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts" / "target-e2e"
sys.path.insert(0, str(SCRIPTS))
common = importlib.import_module("common")
ledger = importlib.import_module("ledger")
provision = importlib.import_module("provision")
teardown = importlib.import_module("teardown")


def _decode(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def _compose_service(compose: str, service: str) -> str:
    match = re.search(
        rf"^  {re.escape(service)}:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:|\Z)",
        compose,
        flags=re.MULTILINE | re.DOTALL,
    )
    assert match is not None, f"missing Compose service: {service}"
    return match.group("body")


def test_all_java_roles_use_the_target_artifact_and_control_mounts_activation_material() -> None:
    compose = (ROOT / "docker-compose.target-e2e.yml").read_text(encoding="utf-8")
    expected = {
        "java-api-service": ("target-e2e,api", "API", 'APP_TEMPORAL_WORKER_ENABLED: "false"'),
        "java-control-worker": (
            "target-e2e,control-worker",
            "CONTROL",
            'APP_TEMPORAL_WORKER_ENABLED: "true"',
        ),
        "java-agent-worker": (
            "target-e2e,agent-worker",
            "AGENT",
            'APP_TEMPORAL_WORKER_ENABLED: "true"',
        ),
    }

    for service, (profile, role, worker_enabled) in expected.items():
        definition = _compose_service(compose, service)
        assert 'command: ["-jar", "/home/app/app-target-e2e.jar"]' in definition
        assert f"SPRING_PROFILES_ACTIVE: {profile}" in definition
        assert f"TEMPORAL_WORKER_ROLE: {role}" in definition
        assert worker_enabled in definition
        assert 'APP_TARGET_E2E_ENABLED: "true"' in definition

    control = _compose_service(compose, "java-control-worker")
    for value in (
        "APP_TARGET_E2E_ACTIVATION_MANIFEST_PATH: /run/target-e2e/java-activation/activation.jws",
        "APP_TARGET_E2E_ACTIVATION_PUBLIC_KEYS: ${TARGET_E2E_ACTIVATION_KEY_ID:?}=/run/target-e2e/java-activation/activation.public.pem",
        "APP_TARGET_E2E_ISOLATION_ATTESTATION_PUBLIC_KEYS: ${TARGET_E2E_ISOLATION_ATTESTATION_KEY_ID:?}=/run/target-e2e/java-activation/isolation-attestation.public.pem",
        "APP_TARGET_E2E_MEASUREMENT_ISOLATION_ATTESTATION_PATH: /run/target-e2e/java-activation/isolation-attestation.jws",
        "source: ${TARGET_E2E_ACTIVATION_DIR:?}",
        "target: /run/target-e2e/java-activation",
        "read_only: true",
    ):
        assert value in control

    api = _compose_service(compose, "java-api-service")
    for value in (
        "APP_AGENT_RUN_V2_GRAPH_SIGNING_KEY_DIRECTORY: /run/target-e2e/graph-signing-keys",
        "APP_AGENT_RUN_V2_GRAPH_SIGNING_ACTIVE_KEY_ID: ${TARGET_E2E_GRAPH_SIGNING_KEY_ID:?}",
        "source: ${TARGET_E2E_SECRETS_DIR:?}/graph-signing-keys",
        "target: /run/target-e2e/graph-signing-keys",
        "read_only: true",
    ):
        assert value in api


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


def test_activation_graph_hash_is_distinct_from_executor_registry_hash() -> None:
    target, registry_hash = provision._target_binding("a" * 40)
    activation, activation_hash = provision._activation_graph_binding(target)

    preimage = dict(activation)
    preimage.pop("bindingHash")
    assert activation_hash == common.canonical_sha256(preimage)
    assert activation["bindingHash"] == activation_hash
    assert activation_hash != registry_hash
    assert activation == {
        "key": target["graph_key"],
        "version": target["graph_version"],
        "checkpointSchemaVersion": target["checkpoint_schema_version"],
        "codeBuildId": target["code_build_id"],
        "bindingHash": activation_hash,
    }


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


def test_database_identity_measurement_is_strict_and_runtime_role_bound() -> None:
    measured = json.dumps(
        {
            "clusterIdentity": "pg-system-id/7253171847231731",
            "databaseIdentity": "pg-database-oid/16385",
            "runtimePrincipalIdentity": "pg-role-oid/16384",
            "databaseName": "target_domain",
            "roleName": "domain_app",
        }
    )

    assert provision._parse_database_identity(
        measured,
        expected_database="target_domain",
        expected_role="domain_app",
    ) == {
        "clusterIdentity": "pg-system-id/7253171847231731",
        "databaseIdentity": "pg-database-oid/16385",
        "runtimePrincipalIdentity": "pg-role-oid/16384",
    }
    with pytest.raises(common.TargetE2EError, match="exactly one row"):
        provision._parse_database_identity(
            measured + "\n" + measured,
            expected_database="target_domain",
            expected_role="domain_app",
        )
    with pytest.raises(common.TargetE2EError, match="wrong database or runtime role"):
        provision._parse_database_identity(
            measured,
            expected_database="target_domain",
            expected_role="graph_runtime",
        )
    swapped = json.loads(measured)
    swapped["clusterIdentity"] = "pg-role-oid/16384"
    with pytest.raises(common.TargetE2EError, match="malformed"):
        provision._parse_database_identity(
            json.dumps(swapped),
            expected_database="target_domain",
            expected_role="domain_app",
        )


def test_database_bootstrap_starts_only_final_databases_and_measures_both(
    tmp_path: Path, monkeypatch
) -> None:
    calls: list[list[str]] = []
    checked: list[dict[str, object]] = []
    lock: dict[str, object] = {"project_name": "aflow-target-e2e-p9-run001"}

    def identity(system_id: int, database_oid: int, role_oid: int, db: str, role: str):
        return json.dumps(
            {
                "clusterIdentity": f"pg-system-id/{system_id}",
                "databaseIdentity": f"pg-database-oid/{database_oid}",
                "runtimePrincipalIdentity": f"pg-role-oid/{role_oid}",
                "databaseName": db,
                "roleName": role,
            }
        )

    def fake_run(arguments: list[str], **_kwargs):
        calls.append(arguments)
        if "exec" not in arguments:
            return subprocess.CompletedProcess(arguments, 0, stdout="", stderr="")
        service = arguments[arguments.index("exec") + 2]
        stdout = (
            identity(1001, 2001, 3001, "target_domain", "domain_app")
            if service == "domain-db"
            else identity(1002, 2002, 3002, "target_graph", "graph_runtime")
        )
        return subprocess.CompletedProcess(arguments, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(
        teardown, "assert_no_locked_resources", lambda value: checked.append(value)
    )
    monkeypatch.setattr(common, "run_command", fake_run)

    domain, graph = provision._bootstrap_database_identities(
        tmp_path / ".bootstrap.env", lock
    )

    assert checked == [lock]
    assert domain["clusterIdentity"] == "pg-system-id/1001"
    assert graph["clusterIdentity"] == "pg-system-id/1002"
    up = calls[0]
    assert up[-2:] == ["domain-db", "graph-db"]
    assert ["--pull", "never"] == up[up.index("--pull") : up.index("--pull") + 2]
    assert sum("exec" in call for call in calls) == 2


def test_exact_cleanup_removes_only_validated_locked_inventory(monkeypatch) -> None:
    calls: list[list[str]] = []
    inventory = (
        ["domain-container", "graph-container"],
        ["domain-network", "graph-network"],
        ["domain-volume", "graph-volume"],
    )
    monkeypatch.setattr(teardown, "_locked_resources", lambda _lock: inventory)
    monkeypatch.setattr(
        common,
        "run_command",
        lambda arguments, **_kwargs: calls.append(arguments)
        or subprocess.CompletedProcess(arguments, 0, stdout="", stderr=""),
    )

    assert teardown.remove_exact_locked_resources({"run_id": "p9-run001"}) == inventory
    assert calls == [
        ["docker", "rm", "--force", "domain-container", "graph-container"],
        ["docker", "network", "rm", "domain-network"],
        ["docker", "network", "rm", "graph-network"],
        ["docker", "volume", "rm", "domain-volume"],
        ["docker", "volume", "rm", "graph-volume"],
    ]


def test_incomplete_provision_cleanup_releases_lock_only_after_exact_removal(
    tmp_path: Path, monkeypatch
) -> None:
    bootstrap = tmp_path / ".bootstrap.env"
    bootstrap.write_text("secret=value\n", encoding="ascii")
    lock_path = tmp_path / "run.lock.json"
    lock = {
        "state": "PROVISIONING",
        "run_id": "p9-run001",
        "run_directory": tmp_path.as_posix(),
    }
    events: list[object] = []
    written: list[dict[str, object]] = []
    monkeypatch.setattr(
        common, "load_run_lock", lambda path, require_active: lock
    )
    monkeypatch.setattr(
        teardown,
        "remove_exact_locked_resources",
        lambda value: events.append(("remove", value)) or (["c1"], ["n1"], ["v1"]),
    )
    monkeypatch.setattr(
        teardown,
        "_release_port_lock",
        lambda value, released: events.append(("release", value, released)),
    )
    monkeypatch.setattr(
        common,
        "write_json",
        lambda path, value: written.append(value),
    )

    receipt = teardown.cleanup_incomplete_provision(lock_path)

    assert [event[0] for event in events] == ["remove", "release"]
    assert written[-1]["state"] == "FAILED"
    assert receipt["removed_volumes"] == ["v1"]
    assert not bootstrap.exists()


def test_database_credentials_are_generated_once_and_reused_for_bootstrap() -> None:
    source = (SCRIPTS / "provision.py").read_text(encoding="utf-8")
    credential_keys = (
        "TARGET_E2E_DOMAIN_ADMIN_PASSWORD",
        "TARGET_E2E_DOMAIN_APP_PASSWORD",
        "TARGET_E2E_GRAPH_ADMIN_PASSWORD",
        "TARGET_E2E_GRAPH_MIGRATOR_PASSWORD",
        "TARGET_E2E_GRAPH_RUNTIME_PASSWORD",
        "TARGET_E2E_GRAPH_RETENTION_PASSWORD",
    )
    for key in credential_keys:
        assert source.count(f'"{key}": _secret()') == 1
    assert source.index("credentials = {") < source.index(
        "domain_identity, graph_identity = _bootstrap_database_identities("
    )
    assert "environment = {" in source and "**credentials" in source


def test_provisioning_outputs_only_public_runtime_material_to_activation_mount() -> (
    None
):
    source = (SCRIPTS / "provision.py").read_text(encoding="utf-8")

    assert 'activation_dir / "isolation-attestation.public.pem"' in source
    assert 'activation_dir / "isolation-attestation.jws"' in source
    assert 'activation_dir / "isolation-attestation.json"' in source
    assert '_write_public_bytes(\n            activation_dir / "activation.jws"' in source
    assert 'activation_dir / "synthetic-fixtures"' in source
    assert '"isolation-attestation-signing"' in source
    assert 'fixture-set".encode' not in source
    assert "TARGET_E2E_ISOLATION_ATTESTATION_KEY_ID" in source
    assert "TARGET_E2E_SYNTHETIC_FIXTURE_SHA256" in source
    assert "TARGET_E2E_GRAPH_ACTIVATION_BINDING_HASH" in source
