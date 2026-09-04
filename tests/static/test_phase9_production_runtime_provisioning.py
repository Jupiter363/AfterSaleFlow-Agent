from __future__ import annotations

import ast
import base64
import datetime as dt
import hashlib
import importlib
import ipaddress
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "tools" / "uat" / "production-runtime"
sys.path.insert(0, str(SCRIPTS))
common = importlib.import_module("common")
build_image_lock = importlib.import_module("build_image_lock")
batch4 = importlib.import_module("batch4")
ledger = importlib.import_module("ledger")
p9_gate = importlib.import_module("p9_gate")
provision = importlib.import_module("provision")
teardown = importlib.import_module("teardown")
LOCAL_SOURCE_PROVISIONER = ROOT / ".local-dev" / "provision-local-target.py"
LOCAL_SOURCE_LAUNCHER = ROOT / ".local-dev" / "launch-source.ps1"


def _read_optional_local_source(path: Path) -> str:
    if not path.is_file():
        pytest.skip("local source tooling is an operator artifact, not production source")
    return path.read_text(encoding="utf-8")


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


def _local_source_identity_namespace() -> dict[str, object]:
    source = _read_optional_local_source(LOCAL_SOURCE_PROVISIONER)
    tree = ast.parse(source)
    selected_names = {
        "CANDIDATE_SHA_PATTERN",
        "COMPILED_WORKTREE_BINDING_PATTERN",
    }
    selected_functions = {
        "component_digest",
        "require_compiled_worktree_binding",
        "sha256_bytes",
        "temporal_build_id",
    }
    selected: list[ast.stmt] = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id in selected_names
            for target in node.targets
        ):
            selected.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name in selected_functions:
            selected.append(node)
    namespace: dict[str, object] = {"hashlib": hashlib, "re": re}
    exec(
        compile(
            ast.Module(body=selected, type_ignores=[]),
            str(LOCAL_SOURCE_PROVISIONER),
            "exec",
        ),
        namespace,
    )
    return namespace


def _local_source_certificate_namespace() -> dict[str, object]:
    source = _read_optional_local_source(LOCAL_SOURCE_PROVISIONER)
    tree = ast.parse(source)
    selected_names = {"KEY_ID", "MTLS_PASSWORD"}
    selected_functions = {
        "certificate",
        "generate_key_material",
        "sha256_bytes",
        "write_bytes",
    }
    selected: list[ast.stmt] = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id in selected_names
            for target in node.targets
        ):
            selected.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name in selected_functions:
            selected.append(node)
    namespace: dict[str, object] = {
        "ExtendedKeyUsageOID": ExtendedKeyUsageOID,
        "NameOID": NameOID,
        "Path": Path,
        "datetime": dt.datetime,
        "ec": ec,
        "hashes": hashes,
        "hashlib": hashlib,
        "ipaddress": ipaddress,
        "pkcs12": pkcs12,
        "serialization": serialization,
        "shutil": shutil,
        "subprocess": subprocess,
        "timedelta": dt.timedelta,
        "x509": x509,
    }
    exec(
        compile(
            ast.Module(body=selected, type_ignores=[]),
            str(LOCAL_SOURCE_PROVISIONER),
            "exec",
        ),
        namespace,
    )
    return namespace


def test_all_java_roles_use_the_target_artifact_and_control_mounts_activation_material() -> None:
    compose = (ROOT / "infra/compose/production-runtime-uat.yml").read_text(encoding="utf-8")
    expected = {
        "java-api-service": ("production-runtime,api", "API", 'APP_TEMPORAL_WORKER_ENABLED: "false"'),
        "java-control-worker": (
            "production-runtime,control-worker",
            "CONTROL",
            'APP_TEMPORAL_WORKER_ENABLED: "true"',
        ),
        "java-agent-worker": (
            "production-runtime,agent-worker",
            "AGENT",
            'APP_TEMPORAL_WORKER_ENABLED: "true"',
        ),
    }

    for service, (profile, role, worker_enabled) in expected.items():
        definition = _compose_service(compose, service)
        assert 'command: ["-jar", "/home/app/app-production-runtime.jar"]' in definition
        assert f"SPRING_PROFILES_ACTIVE: {profile}" in definition
        assert f"TEMPORAL_WORKER_ROLE: {role}" in definition
        assert worker_enabled in definition
        assert 'APP_PRODUCTION_RUNTIME_ENABLED: "true"' in definition

    control = _compose_service(compose, "java-control-worker")
    for value in (
        "APP_PRODUCTION_RUNTIME_ACTIVATION_MANIFEST_PATH: /run/production-runtime/java-activation/activation.jws",
        "APP_PRODUCTION_RUNTIME_ACTIVATION_PUBLIC_KEYS: ${PRODUCTION_RUNTIME_ACTIVATION_KEY_ID:?}=/run/production-runtime/java-activation/activation.public.pem",
        "APP_PRODUCTION_RUNTIME_ISOLATION_ATTESTATION_PUBLIC_KEYS: ${PRODUCTION_RUNTIME_ISOLATION_ATTESTATION_KEY_ID:?}=/run/production-runtime/java-activation/isolation-attestation.public.pem",
        "APP_PRODUCTION_RUNTIME_MEASUREMENT_ISOLATION_ATTESTATION_PATH: /run/production-runtime/java-activation/isolation-attestation.jws",
        "source: ${PRODUCTION_RUNTIME_ACTIVATION_DIR:?}",
        "target: /run/production-runtime/java-activation",
        "read_only: true",
    ):
        assert value in control

    api = _compose_service(compose, "java-api-service")
    for value in (
        "APP_AGENT_RUN_V2_GRAPH_SIGNING_KEY_DIRECTORY: /run/production-runtime/graph-signing-keys",
        "APP_AGENT_RUN_V2_GRAPH_SIGNING_ACTIVE_KEY_ID: ${PRODUCTION_RUNTIME_GRAPH_SIGNING_KEY_ID:?}",
        "source: ${PRODUCTION_RUNTIME_SECRETS_DIR:?}/graph-signing-keys",
        "target: /run/production-runtime/graph-signing-keys",
        "read_only: true",
    ):
        assert value in api

    agent = _compose_service(compose, "java-agent-worker")
    assert (
        "APP_AGENT_RUN_V2_GRAPH_CLIENT_TLS_KEY_STORE_PASSWORD: "
        "${PRODUCTION_RUNTIME_MTLS_KEYSTORE_PASSWORD:?}"
    ) in agent
    assert (
        "APP_AGENT_RUN_V2_GRAPH_CLIENT_TLS_TRUST_STORE_PASSWORD: "
        "${PRODUCTION_RUNTIME_MTLS_TRUSTSTORE_PASSWORD:?}"
    ) in agent


def test_fixture_hash_uses_the_actual_canonical_fixture_bytes(tmp_path: Path) -> None:
    document, canonical, digest = provision._canonical_fixture(
        provision.SYNTHETIC_FIXTURE_SOURCE
    )

    assert canonical == common.canonical_bytes(document)
    assert digest == hashlib.sha256(canonical).hexdigest()
    assert digest == "81ee5f839e7550e8bf9b13bcf1a43f912cbcd2a6ebb7a592afa1b902f668d396"
    assert digest != hashlib.sha256(b"candidate:run:fixture-set").hexdigest()

    duplicate = tmp_path / "duplicate.json"
    duplicate.write_text('{"schemaVersion":"x","schemaVersion":"y"}', encoding="utf-8")
    try:
        provision._canonical_fixture(duplicate)
    except common.ProductionError as error:
        assert "duplicate JSON member" in str(error)
    else:
        raise AssertionError("duplicate fixture members must fail closed")


def test_image_builder_requires_exact_digest_pinned_base_inventory(tmp_path: Path) -> None:
    base_images = {
        key: f"registry.example/{key}@sha256:{index:064x}"
        for index, key in enumerate(sorted(build_image_lock.BASE_IMAGE_KEYS), start=1)
    }
    path = tmp_path / "base-images.json"
    path.write_text(json.dumps(base_images), encoding="utf-8")

    assert build_image_lock.load_base_images(path) == base_images

    incomplete = dict(base_images)
    incomplete.pop(next(iter(incomplete)))
    path.write_text(json.dumps(incomplete), encoding="utf-8")
    with pytest.raises(common.ProductionError, match="exact non-application inventory"):
        build_image_lock.load_base_images(path)

    path.write_text(json.dumps({**base_images, "postgres": "postgres:16-alpine"}), encoding="utf-8")
    with pytest.raises(common.ProductionError, match="immutable manifest"):
        build_image_lock.load_base_images(path)


def test_application_image_uses_the_buildx_digest_without_repulling_a_mutable_tag(
    tmp_path: Path, monkeypatch
) -> None:
    digest = "sha256:" + "7" * 64
    candidate = "a" * 40
    context = tmp_path / "context"
    context.mkdir()
    dockerfile = context / "Dockerfile"
    dockerfile.write_text("FROM scratch\n", encoding="ascii")
    commands: list[list[str]] = []

    def run(arguments: list[str], *, timeout: int = 3600):
        commands.append(arguments)
        if "--metadata-file" in arguments:
            metadata = Path(arguments[arguments.index("--metadata-file") + 1])
            metadata.write_text(
                json.dumps({"containerimage.digest": digest}), encoding="utf-8"
            )
        return subprocess.CompletedProcess(arguments, 0, "", "")

    reference = f"registry.example/candidate/java@{digest}"
    monkeypatch.setattr(build_image_lock, "_run", run)
    monkeypatch.setattr(
        build_image_lock,
        "_inspect_image",
        lambda _docker, requested: {
            "Id": "sha256:" + "8" * 64,
            "RepoDigests": [reference],
            "RootFS": {"Layers": ["sha256:" + "9" * 64]},
            "Config": {
                "Labels": {
                    build_image_lock.OCI_REVISION_LABEL: candidate,
                    build_image_lock.OCI_VERSION_LABEL: "build-01",
                    build_image_lock.TARGET_BUILD_LABEL: "build-01",
                }
            },
        }
        if requested == reference
        else (_ for _ in ()).throw(AssertionError("mutable tag was inspected")),
    )

    record = build_image_lock._build_application_image(
        docker="docker",
        candidate=candidate,
        build_id="build-01",
        repository_prefix="registry.example/candidate",
        key="java",
        specification=build_image_lock.ApplicationImage(
            repository="java", context=context, dockerfile=dockerfile
        ),
        metadata_directory=tmp_path,
    )

    pulls = [command for command in commands if command[:2] == ["docker", "pull"]]
    assert pulls == [["docker", "pull", reference]]
    assert record["reference"] == reference


def test_image_builder_emits_valid_v2_lock_and_bound_build_attestation(
    tmp_path: Path, monkeypatch
) -> None:
    candidate = "a" * 40
    base_images = {
        key: f"registry.example/base/{key}@sha256:{index:064x}"
        for index, key in enumerate(sorted(build_image_lock.BASE_IMAGE_KEYS), start=1)
    }
    monkeypatch.setattr(build_image_lock, "_repository_state", lambda value: value)
    monkeypatch.setattr(
        build_image_lock, "_source_tree_digest", lambda _value: "sha256:" + "b" * 64
    )
    monkeypatch.setattr(build_image_lock.shutil, "which", lambda value: f"/tools/{value}")
    monkeypatch.setattr(
        build_image_lock,
        "_run",
        lambda arguments, timeout=3600: subprocess.CompletedProcess(arguments, 0, "", ""),
    )

    def inspection(reference: str) -> dict[str, object]:
        repository, digest = reference.rsplit("@", 1)
        return {
            "Id": "sha256:" + "c" * 64,
            "RepoDigests": [f"{repository}@{digest}"],
            "RootFS": {"Layers": ["sha256:" + "d" * 64]},
        }

    monkeypatch.setattr(
        build_image_lock,
        "_inspect_image",
        lambda _docker, reference: inspection(reference),
    )

    def application(**kwargs):
        key = kwargs["key"]
        digest = hashlib.sha256(key.encode("ascii")).hexdigest()
        return {
            "reference": f"registry.example/candidate/{key}@sha256:{digest}",
            "manifest_digest": f"sha256:{digest}",
            "config_digest": "sha256:" + "e" * 64,
            "layer_digests": ["sha256:" + "f" * 64],
            "source_revision": candidate,
            "build_id": kwargs["build_id"],
        }

    monkeypatch.setattr(build_image_lock, "_build_application_image", application)
    output = tmp_path / "image-build"
    lock_path, attestation_path = build_image_lock.build_lock(
        candidate=candidate,
        base_images=base_images,
        repository_prefix="registry.example/candidate",
        output_directory=output,
        invocation_id="p9-build-invocation-0001",
        builder_id="preprod-builder-01",
        built_at=dt.datetime(2026, 7, 29, 12, 0, tzinfo=dt.timezone.utc),
    )

    loaded_candidate, images, lock = common.load_image_lock(lock_path)
    attestation = common.load_json(attestation_path)
    common.verify_self_hash(attestation, "build attestation")
    assert loaded_candidate == candidate
    assert set(images) == set(common.IMAGE_KEYS)
    assert lock["build_provenance"]["attestation_digest"] == (
        "sha256:" + common.canonical_sha256(attestation)
    )
    assert attestation["source_tree_sha256"] == "sha256:" + "b" * 64


def test_batch4_receipt_requires_every_hard_assertion_and_external_gate_ceiling(
    tmp_path: Path,
) -> None:
    candidate = "a" * 40
    activation = "p9act.v1." + "b" * 32
    run_context = {
        "runtime_projection": {
            "activationId": activation,
            "environmentGeneration": 17,
        }
    }
    run_context_path = tmp_path / "run-context.json"
    common.write_json(run_context_path, run_context)
    env = {"PRODUCTION_RUNTIME_RUN_CONTEXT_PATH": str(run_context_path)}
    lock = {"run_id": "p9-run001", "candidate_sha": candidate}
    receipt = common.seal_self_hash(
        {
            "schema_version": "production-runtime-batch-4-scenario.v1",
            "status": "PASS",
            "run_id": lock["run_id"],
            "candidate_sha": candidate,
            "activation_id": activation,
            "environment_generation": 17,
            "case_id": "CASE_P9_SYNTHETIC_0001",
            "assertions": {
                assertion: "PASS" for assertion in batch4.REQUIRED_SCENARIO_ASSERTIONS
            },
            "engineering_checkpoint": p9_gate.P9_ENGINEERING_RESULT,
            **p9_gate.P9_EXTERNAL_CEILING,
        }
    )

    batch4.validate_scenario_receipt(
        receipt,
        env=env,
        lock=lock,
        case_id="CASE_P9_SYNTHETIC_0001",
    )

    failed = dict(receipt)
    failed["assertions"] = dict(receipt["assertions"])
    failed["assertions"].pop(next(iter(batch4.REQUIRED_SCENARIO_ASSERTIONS)))
    failed = common.seal_self_hash(failed)
    with pytest.raises(common.ProductionError, match="every hard gate"):
        batch4.validate_scenario_receipt(
            failed,
            env=env,
            lock=lock,
            case_id="CASE_P9_SYNTHETIC_0001",
        )

    overreach = common.seal_self_hash({**receipt, "promotion_gate": "PASS"})
    with pytest.raises(common.ProductionError, match="every hard gate"):
        batch4.validate_scenario_receipt(
            overreach,
            env=env,
            lock=lock,
            case_id="CASE_P9_SYNTHETIC_0001",
        )


def test_p9_evidence_entry_cannot_bypass_the_strict_batch4_receipt_gate(
    tmp_path: Path, monkeypatch
) -> None:
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    for name in (
        "target-assertion.json",
        "forensic-manifest.json",
        "batch-4-scenario.json",
    ):
        common.write_json(evidence_dir / name, {})
    monkeypatch.setattr(
        p9_gate,
        "_verified_run_material",
        lambda _env_file: (
            {"PRODUCTION_RUNTIME_EVIDENCE_DIR": str(evidence_dir)},
            {},
            None,
            [],
        ),
    )
    monkeypatch.setattr(
        p9_gate,
        "validate_scenario_receipt",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            common.ProductionError("strict scenario gate")
        ),
    )

    with pytest.raises(common.ProductionError, match="strict scenario gate"):
        p9_gate.create_evidence(tmp_path / "target.env", "CASE_P9_SYNTHETIC_0001")


def _write_key_pair(directory: Path, stem: str, key) -> tuple[Path, Path]:
    private_path = directory / f"{stem}.private.pem"
    public_path = directory / f"{stem}.public.pem"
    private_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    public_path.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return private_path, public_path


def test_p9_acceptance_requires_an_independent_signer(tmp_path: Path, monkeypatch) -> None:
    harness_key = ec.generate_private_key(ec.SECP256R1())
    acceptance_key = ec.generate_private_key(ec.SECP256R1())
    harness_private, harness_public_path = _write_key_pair(tmp_path, "harness", harness_key)
    acceptance_private, acceptance_public = _write_key_pair(
        tmp_path, "acceptance", acceptance_key
    )
    harness_public = ledger.load_public_key(harness_public_path)
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    evidence = ledger.attest_document(
        {
            "schema_version": "phase-9-p9.0-evidence.v1",
            "status": "PASS_AWAITING_ACCEPTANCE",
            "run_id": "p9-run001",
            "candidate_sha": "a" * 40,
            "activation_id": "p9act.v1." + "b" * 32,
            "environment_generation": 17,
            "case_id": "CASE_P9_SYNTHETIC_0001",
            "image_lock_hash": "c" * 64,
            "target_assertion_hash": "d" * 64,
            "forensic_manifest_hash": "e" * 64,
            "batch_4_scenario_hash": "f" * 64,
            "ledger_head_hash": "1" * 64,
            "recorded_at": "2026-07-29T12:00:00.000+00:00",
            "engineering_checkpoint": p9_gate.P9_ENGINEERING_RESULT,
            **p9_gate.P9_EXTERNAL_CEILING,
        },
        harness_key,
        harness_public,
        key_id="p9-harness-aaaaaaaaaaaa",
    )
    common.write_json(evidence_dir / "p9.0-evidence.json", evidence)
    run_context_path = tmp_path / "run-context.json"
    common.write_json(
        run_context_path,
        {
            "runtime_projection": {
                "activationId": "p9act.v1." + "b" * 32,
                "environmentGeneration": 17,
            }
        },
    )
    env = {
        "PRODUCTION_RUNTIME_EVIDENCE_DIR": str(evidence_dir),
        "PRODUCTION_RUNTIME_RUN_CONTEXT_PATH": str(run_context_path),
    }
    lock = {
        "run_id": "p9-run001",
        "candidate_sha": "a" * 40,
        "image_lock_hash": "c" * 64,
        "ledger_public_key_sha256": ledger.public_key_sha256(harness_public),
    }
    monkeypatch.setattr(
        p9_gate,
        "_verified_run_material",
        lambda _env_file: (env, lock, harness_public, []),
    )

    with pytest.raises(common.ProductionError, match="independent key"):
        p9_gate.accept_evidence(
            tmp_path / "target.env",
            acceptance_private_key=harness_private,
            acceptance_public_key=harness_public_path,
            acceptance_key_id="p9-acceptance-reviewer-01",
        )

    accepted = p9_gate.accept_evidence(
        tmp_path / "target.env",
        acceptance_private_key=acceptance_private,
        acceptance_public_key=acceptance_public,
        acceptance_key_id="p9-acceptance-reviewer-01",
    )
    assert accepted["status"] == "PASS"
    assert accepted["p9_0"] == "PASS"
    assert accepted["promotion_gate"] == "PENDING"

    with pytest.raises(common.ProductionError, match="already exists"):
        p9_gate.accept_evidence(
            tmp_path / "target.env",
            acceptance_private_key=acceptance_private,
            acceptance_public_key=acceptance_public,
            acceptance_key_id="p9-acceptance-reviewer-01",
        )


def test_activation_graph_hash_is_distinct_from_executor_registry_hash() -> None:
    target, registry_hash = provision._target_binding("a" * 40)
    activation, activation_hash = provision._activation_graph_binding(target)

    assert target["graph_version"] == "production-runtime-graph.2026-08-18.3"
    assert target["graph_version"] != "production-runtime-graph.2026-08-18.2"
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
        typ="production-runtime-measurement+jwt",
    )
    protected_segment, payload_segment, signature_segment = compact.split(".")
    assert json.loads(_decode(protected_segment)) == {
        "alg": "ES256",
        "kid": "p9-isolation-attestation-aaaaaaaaaaaa",
        "typ": "production-runtime-measurement+jwt",
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
    expected = b"sealed production runtime jar bytes"

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
        call[1:3] == ["cp", "a" * 64 + ":/home/app/app-production-runtime.jar"]
        for call in calls
    )
    assert calls[-1] == ["docker.exe", "rm", "--force", "a" * 64]


def test_openssl_discovers_conda_config_instead_of_using_a_missing_compiled_default(
    tmp_path: Path, monkeypatch
) -> None:
    inherited = tmp_path / "missing-conda-openssl.cnf"
    monkeypatch.setenv("OPENSSL_CONF", str(inherited))
    openssl = tmp_path / "miniconda" / "Library" / "bin" / "openssl.exe"
    openssl.parent.mkdir(parents=True)
    openssl.write_bytes(b"executable")
    discovered = tmp_path / "miniconda" / "Library" / "ssl" / "openssl.cnf"
    discovered.parent.mkdir(parents=True)
    discovered.write_text("[ req ]\ndistinguished_name = dn\n[ dn ]\n", encoding="ascii")
    calls: list[tuple[list[str], dict[str, str] | None]] = []

    def fake_run(
        arguments: list[str], *, environment: dict[str, str] | None = None
    ) -> None:
        calls.append((arguments, environment))
        assert environment is not None
        config = Path(environment["OPENSSL_CONF"])
        assert config.is_file()
        assert config == discovered.resolve()
        output = Path(arguments[arguments.index("-out") + 1])
        output.write_text("generated", encoding="ascii")

    monkeypatch.setattr(provision, "_run", fake_run)
    private_key = tmp_path / "keys" / "private.pem"
    public_key = tmp_path / "public" / "public.pem"

    provision._generate_p256_key_pair(str(openssl), private_key, public_key)

    assert [call[0][1] for call in calls] == ["genpkey", "pkey"]
    configs = {call[1]["OPENSSL_CONF"] for call in calls if call[1] is not None}
    assert configs == {str(discovered.resolve())}


def test_openssl_config_discovery_fails_closed_when_no_config_exists(
    tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.delenv("OPENSSL_CONF", raising=False)
    openssl = tmp_path / "isolated" / "bin" / "openssl.exe"
    openssl.parent.mkdir(parents=True)
    openssl.write_bytes(b"executable")

    with pytest.raises(common.ProductionError, match="no bounded readable OpenSSL config"):
        provision._openssl_environment(str(openssl))


def test_every_mtls_openssl_call_receives_the_discovered_config(
    tmp_path: Path, monkeypatch
) -> None:
    openssl = tmp_path / "miniconda" / "Library" / "bin" / "openssl.exe"
    openssl.parent.mkdir(parents=True)
    openssl.write_bytes(b"executable")
    config = tmp_path / "miniconda" / "Library" / "ssl" / "openssl.cnf"
    config.parent.mkdir(parents=True)
    config.write_text("[ req ]\ndistinguished_name = dn\n[ dn ]\n", encoding="ascii")
    calls: list[tuple[list[str], dict[str, str] | None]] = []

    def fake_run(
        arguments: list[str], *, environment: dict[str, str] | None = None
    ) -> None:
        calls.append((arguments, environment))
        if "-out" in arguments:
            Path(arguments[arguments.index("-out") + 1]).write_bytes(b"generated")
        if "-keystore" in arguments:
            Path(arguments[arguments.index("-keystore") + 1]).write_bytes(b"generated")

    monkeypatch.setattr(provision, "_run", fake_run)
    provision._generate_mtls(
        str(openssl),
        "keytool.exe",
        tmp_path / "mtls",
        "key-password",
        "trust-password",
    )

    openssl_calls = [call for call in calls if call[0][0] == str(openssl)]
    keytool_calls = [call for call in calls if call[0][0] == "keytool.exe"]
    assert len(openssl_calls) == 9
    assert all(call[1] is not None for call in openssl_calls)
    assert {call[1]["OPENSSL_CONF"] for call in openssl_calls if call[1]} == {
        str(config.resolve())
    }
    assert len(keytool_calls) == 1 and keytool_calls[0][1] is None


def test_database_identity_measurement_is_strict_and_runtime_role_bound() -> None:
    measured = json.dumps(
        {
            "clusterIdentity": "pg-system-id/7253171847231731",
            "databaseIdentity": "pg-database-oid/16385",
            "runtimePrincipalIdentity": "pg-role-oid/16384",
            "databaseName": "production_domain",
            "roleName": "domain_app",
        }
    )

    assert provision._parse_database_identity(
        measured,
        expected_database="production_domain",
        expected_role="domain_app",
    ) == {
        "clusterIdentity": "pg-system-id/7253171847231731",
        "databaseIdentity": "pg-database-oid/16385",
        "runtimePrincipalIdentity": "pg-role-oid/16384",
    }
    with pytest.raises(common.ProductionError, match="exactly one row"):
        provision._parse_database_identity(
            measured + "\n" + measured,
            expected_database="production_domain",
            expected_role="domain_app",
        )
    with pytest.raises(common.ProductionError, match="wrong database or runtime role"):
        provision._parse_database_identity(
            measured,
            expected_database="production_domain",
            expected_role="graph_runtime",
        )
    swapped = json.loads(measured)
    swapped["clusterIdentity"] = "pg-role-oid/16384"
    with pytest.raises(common.ProductionError, match="malformed"):
        provision._parse_database_identity(
            json.dumps(swapped),
            expected_database="production_domain",
            expected_role="domain_app",
        )


def test_database_bootstrap_starts_only_final_databases_and_measures_both(
    tmp_path: Path, monkeypatch
) -> None:
    calls: list[list[str]] = []
    checked: list[dict[str, object]] = []
    lock: dict[str, object] = {"project_name": "aflow-production-runtime-p9-run001"}

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
            identity(1001, 2001, 3001, "production_domain", "domain_app")
            if service == "domain-db"
            else identity(1002, 2002, 3002, "production_graph", "graph_runtime")
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
        "PRODUCTION_RUNTIME_DOMAIN_ADMIN_PASSWORD",
        "PRODUCTION_RUNTIME_DOMAIN_APP_PASSWORD",
        "PRODUCTION_RUNTIME_GRAPH_ADMIN_PASSWORD",
        "PRODUCTION_RUNTIME_GRAPH_MIGRATOR_PASSWORD",
        "PRODUCTION_RUNTIME_GRAPH_RUNTIME_PASSWORD",
        "PRODUCTION_RUNTIME_GRAPH_RETENTION_PASSWORD",
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
    assert "PRODUCTION_RUNTIME_ISOLATION_ATTESTATION_KEY_ID" in source
    assert "PRODUCTION_RUNTIME_SYNTHETIC_FIXTURE_SHA256" in source
    assert "PRODUCTION_RUNTIME_GRAPH_ACTIVATION_BINDING_HASH" in source


def test_local_source_build_identity_is_exact_deterministic_and_binding_sensitive() -> (
    None
):
    namespace = _local_source_identity_namespace()
    temporal_build_id = namespace["temporal_build_id"]
    component_digest = namespace["component_digest"]
    assert callable(temporal_build_id)
    assert callable(component_digest)
    candidate = "a" * 40
    first_binding = "b" * 64
    second_binding = "c" * 64
    shared_prefix_first_binding = "d" * 16 + "e" * 48
    shared_prefix_second_binding = "d" * 16 + "f" * 48

    first_control = temporal_build_id(candidate, first_binding, "control")
    first_agent = temporal_build_id(candidate, first_binding, "agent")
    assert first_control == temporal_build_id(candidate, first_binding, "control")
    assert first_agent == temporal_build_id(candidate, first_binding, "agent")
    assert first_control != first_agent
    assert first_binding in first_control
    assert first_binding in first_agent
    assert first_control != temporal_build_id(candidate, second_binding, "control")
    assert first_agent != temporal_build_id(candidate, second_binding, "agent")
    for changed_index in range(len(first_binding)):
        changed_binding = (
            first_binding[:changed_index]
            + "c"
            + first_binding[changed_index + 1 :]
        )
        assert temporal_build_id(candidate, changed_binding, "control") != first_control
        assert temporal_build_id(candidate, changed_binding, "agent") != first_agent
    shared_prefix_first_control = temporal_build_id(
        candidate, shared_prefix_first_binding, "control"
    )
    shared_prefix_second_control = temporal_build_id(
        candidate, shared_prefix_second_binding, "control"
    )
    shared_prefix_first_agent = temporal_build_id(
        candidate, shared_prefix_first_binding, "agent"
    )
    shared_prefix_second_agent = temporal_build_id(
        candidate, shared_prefix_second_binding, "agent"
    )
    assert shared_prefix_first_control != shared_prefix_second_control
    assert shared_prefix_first_agent != shared_prefix_second_agent
    assert shared_prefix_first_binding in shared_prefix_first_control
    assert shared_prefix_first_binding in shared_prefix_first_agent

    provisioner_source = _read_optional_local_source(LOCAL_SOURCE_PROVISIONER)
    launcher_source = _read_optional_local_source(LOCAL_SOURCE_LAUNCHER)
    assert (
        re.search(
            r"\b[A-Za-z_][A-Za-z0-9_]*binding[A-Za-z0-9_]*\s*"
            r"\[\s*(?:0\s*)?:\s*16\s*\]",
            provisioner_source,
            re.IGNORECASE,
        )
        is None
    )
    assert (
        re.search(
            r"\$[A-Za-z_][A-Za-z0-9_]*binding[A-Za-z0-9_]*\s*"
            r"\.Substring\(\s*0\s*,\s*16\s*\)",
            launcher_source,
            re.IGNORECASE,
        )
        is None
    )

    for component in (
        "java-api",
        "java-control",
        "java-agent",
        "python-agent",
        "frontend",
    ):
        first_digest = component_digest(candidate, first_binding, component)
        assert first_digest == component_digest(candidate, first_binding, component)
        assert first_digest != component_digest(candidate, second_binding, component)
        assert re.fullmatch(r"sha256:[0-9a-f]{64}", first_digest)


@pytest.mark.parametrize(
    "binding",
    ["", "a" * 63, "a" * 65, "A" * 64, "g" * 64],
)
def test_local_source_build_identity_rejects_malformed_binding(binding: str) -> None:
    namespace = _local_source_identity_namespace()
    require_binding = namespace["require_compiled_worktree_binding"]
    assert callable(require_binding)
    with pytest.raises(ValueError, match="lowercase SHA-256"):
        require_binding(binding)

    source = _read_optional_local_source(LOCAL_SOURCE_PROVISIONER)
    assert 'parser.add_argument("--compiled-worktree-binding", required=True)' in source


def test_local_source_graph_code_identity_remains_independent() -> None:
    source = _read_optional_local_source(LOCAL_SOURCE_PROVISIONER)
    assert "target_binding, registry_hash = provision._target_binding(candidate)" in source
    assert "provision._target_binding(compiled_worktree_binding)" not in source
    assert '"compiledWorktreeBinding": compiled_worktree_binding' in source
    assert '"compiled_worktree_binding": compiled_worktree_binding' in source


def test_local_source_activation_rotation_preserves_graph_authority_and_runtime_receipt() -> None:
    source = _read_optional_local_source(LOCAL_SOURCE_PROVISIONER)

    assert "reset_local_graph_candidate_state" not in source
    assert "TRUNCATE TABLE" not in source
    assert "DISABLE TRIGGER USER" not in source
    assert "await seed_production_runtime_registry(" in source
    assert 'archived_runtime_path = key_root / "target-runtime.json"' in source
    assert "retained_state != serialized_state" in source
    assert 'STATE_DIR / "target-runtime.json"' in source


def test_local_source_mtls_certificates_cover_the_activation_lifetime(
    tmp_path: Path,
) -> None:
    namespace = _local_source_certificate_namespace()
    generate_key_material = namespace["generate_key_material"]
    assert callable(generate_key_material)
    issued_at = dt.datetime(2026, 9, 1, 12, 0, tzinfo=dt.timezone.utc)
    expires_at = issued_at + dt.timedelta(days=30)

    state = generate_key_material(tmp_path / "valid", issued_at, expires_at)
    mtls = Path(state["mtls_directory"])
    ca = x509.load_pem_x509_certificate((mtls / "ca.crt").read_bytes())
    server = x509.load_pem_x509_certificate((mtls / "server.crt").read_bytes())
    client = x509.load_pem_x509_certificate((mtls / "client.crt").read_bytes())

    assert ca.not_valid_after_utc == expires_at + dt.timedelta(days=1)
    assert server.not_valid_after_utc == expires_at
    assert client.not_valid_after_utc == expires_at
    assert server.not_valid_before_utc <= issued_at
    assert client.not_valid_before_utc <= issued_at

    with pytest.raises(ValueError, match="expiry must follow"):
        generate_key_material(tmp_path / "invalid", issued_at, issued_at)


def test_graph_patch_release_domain_authority_preserves_predecessor() -> None:
    migration = (
        ROOT
        / "apps/domain-service"
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration"
        / "V093__production_runtime_graph_patch_release_identity.sql"
    ).read_text(encoding="utf-8")

    assert "ck_production_runtime_activation_bindings" in migration
    assert "ck_r15_selection_constants" in migration
    assert "ck_intake_graph_thread_constants" in migration
    assert "create or replace function enforce_production_runtime_intake_selection()" in migration
    assert migration.count("'production-runtime-graph.2026-08-18.1'") == 4
    assert migration.count("'production-runtime-graph.2026-08-18.2'") == 4
    assert "'production-runtime-graph.2026-07-27.1'" in migration
    assert "'production-runtime-room-proposal-source.v1'" in migration
    assert "activation_row.graph_version is distinct from new.graph_version" in migration


def test_current_graph_patch_release_preserves_both_predecessors() -> None:
    migration = (
        ROOT
        / "apps/domain-service"
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration"
        / "V094__production_runtime_graph_patch_release_identity.sql"
    ).read_text(encoding="utf-8")

    assert "ck_production_runtime_activation_bindings" in migration
    assert "ck_r15_selection_constants" in migration
    assert "ck_intake_graph_thread_constants" in migration
    assert "create or replace function enforce_production_runtime_intake_selection()" in migration
    assert migration.count("'production-runtime-graph.2026-08-18.1'") == 4
    assert migration.count("'production-runtime-graph.2026-08-18.2'") == 4
    assert migration.count("'production-runtime-graph.2026-08-18.3'") == 4
    assert "'production-runtime-graph.2026-07-27.1'" in migration
    assert "'production-runtime-room-proposal-source.v1'" in migration
    assert "activation_row.graph_version is distinct from new.graph_version" in migration
