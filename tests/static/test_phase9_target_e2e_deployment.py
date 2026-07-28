from __future__ import annotations

import copy
import datetime as dt
import importlib
import json
import sys
from pathlib import Path
from typing import Any

import pytest
import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


ROOT = Path(__file__).resolve().parents[2]
COMPOSE_PATH = ROOT / "docker-compose.target-e2e.yml"
DEPLOY = ROOT / "deploy" / "target-e2e"
SCRIPTS = ROOT / "scripts" / "target-e2e"
sys.path.insert(0, str(SCRIPTS))
common = importlib.import_module("common")
ledger = importlib.import_module("ledger")
assertion = importlib.import_module("assert_evidence")
readiness = importlib.import_module("readiness")


def _compose() -> dict[str, Any]:
    value = yaml.safe_load(COMPOSE_PATH.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _networks(service: dict[str, Any]) -> set[str]:
    value = service.get("networks", [])
    return set(value if isinstance(value, list) else value)


def _volume_sources(service: dict[str, Any]) -> set[str]:
    return {
        str(value.get("source"))
        for value in service.get("volumes", [])
        if isinstance(value, dict)
    }


def _write_key_pair(directory: Path, name: str) -> tuple[Any, Any, Path, Path]:
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key()
    private_path = directory / f"{name}.private.pem"
    public_path = directory / f"{name}.public.pem"
    private_path.write_bytes(
        private.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    public_path.write_bytes(
        public.public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return private, public, private_path, public_path


def _image_lock(candidate: str = "b" * 40) -> dict[str, Any]:
    images: dict[str, Any] = {}
    for index, key in enumerate(common.IMAGE_KEYS, 1):
        manifest = f"sha256:{index:064x}"
        images[key] = {
            "reference": f"registry.invalid/target-e2e/{key}@{manifest}",
            "manifest_digest": manifest,
            "config_digest": f"sha256:{index + 100:064x}",
            "layer_digests": [f"sha256:{index + 200:064x}"],
            "source_revision": candidate
            if key in common.APPLICATION_IMAGE_KEYS
            else f"upstream-{index}",
            "build_id": f"build-{key}-{index}",
        }
    return common.seal_self_hash(
        {
            "schema_version": "target-e2e-image-lock.v2",
            "candidate_sha": candidate,
            "source_revision": candidate,
            "build_provenance": {
                "builder_id": "trusted-builder-01",
                "invocation_id": "build-invocation-01",
                "source_tree_sha256": "sha256:" + "c" * 64,
                "built_at": "2026-07-27T10:00:00+00:00",
                "attestation_type": "SLSA_PROVENANCE_V1",
                "attestation_digest": "sha256:" + "d" * 64,
            },
            "images": images,
        }
    )


def _run_context(now: dt.datetime) -> dict[str, Any]:
    return common.seal_self_hash(
        {
            "schema_version": "target-e2e-run-context.v2",
            "runtime_projection": {
                "schemaVersion": "graph-target-e2e-runtime-context.v1",
                "executionLane": "TARGET_E2E_CANDIDATE",
                "activationId": "p9act.v1." + "a" * 32,
                "environmentId": "p9-isolated-run001",
                "environmentGeneration": 7,
                "candidateSha": "b" * 40,
                "issuedAt": (now - dt.timedelta(minutes=1)).isoformat(),
                "expiresAt": (now + dt.timedelta(minutes=20)).isoformat(),
                "runNonce": "p9-nonce-" + "c" * 32,
                "tenantSurrogate": "tenant-run001",
                "caseScope": {
                    "mode": "ISOLATED_SYNTHETIC_NEW_CASES",
                    "caseIdPrefix": "CASE_P9_SYNTHETIC_",
                    "maxCases": 4,
                    "fixtureSetId": "p9-synthetic-all-rooms-001",
                    "fixtureSetHash": "d" * 64,
                    "containsRealCaseOrPartyData": False,
                    "externalEffectsAllowed": False,
                },
                "allowedRoomTypes": ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"],
                "composeProject": "aflow-target-e2e-p9-run001",
                "temporalNamespace": "after-sale-flow-p9-p9-run001",
                "buildBindings": {
                    "caseBuildId": "p9-case-bbbbbbbb",
                    "controlBuildId": "p9-control-bbbbbbbb",
                    "agentBuildId": "p9-agent-bbbbbbbb",
                },
                "imageDigests": {
                    "javaApi": "sha256:" + "1" * 64,
                    "temporalControlWorker": "sha256:" + "2" * 64,
                    "temporalAgentWorker": "sha256:" + "3" * 64,
                    "pythonAgent": "sha256:" + "4" * 64,
                    "frontend": "sha256:" + "5" * 64,
                },
                "databaseIdentities": {
                    "domain": {
                        "service": "domain-db",
                        "database": "target_domain",
                        "schema": "public",
                        "expectedUser": "domain_app",
                    },
                    "graph": {
                        "service": "graph-db",
                        "database": "target_graph",
                        "schema": "graph_runtime",
                        "runtimeUser": "graph_runtime",
                        "environmentGeneration": 7,
                        "restoreVerificationHash": "e" * 64,
                    },
                },
                "trustedSigningKeyIds": ["p9-java-finalizer-01"],
                "perCommandManifestAllowed": False,
            },
            "executor_bindings": [
                {
                    "graph_key": "all-rooms.target-e2e.v1",
                    "graph_version": "target-e2e-graph.2026-07-27.1",
                    "checkpoint_schema_version": "target-e2e-checkpoint.v1",
                    "state_schema_version": "target-e2e-graph-state.v1",
                    "state_schema_hash": "7" * 64,
                    "command_schema_version": "room-graph-command.v1",
                    "result_schema_version": "room-graph-result.v1",
                    "agent_profile_id": "all-rooms-agent.target-e2e.v1",
                    "prompt_version": "all-rooms-prompt.target-e2e.v1",
                    "model_profile_id": "target-e2e.contract-blocked",
                    "output_schema_version": "target-e2e-room-proposal-source.v1",
                    "policy_version": "all-rooms-policy.target-e2e.v1",
                    "guardrail_version": "all-rooms-guardrail.target-e2e.v1",
                    "tool_policy_version": "tools.none.v1",
                    "binding_hash": "f" * 64,
                    "code_build_id": "p9-graph-bbbbbbbb",
                    "allowed_room_types": [
                        "INTAKE",
                        "EVIDENCE",
                        "HEARING",
                        "REVIEW",
                    ],
                    "allowed_stage_codes": [
                        "INTAKE_MESSAGE",
                        "EVIDENCE_SEAL",
                        "HEARING_DELIBERATION",
                        "REVIEW_OUTCOME",
                    ],
                }
            ],
            "current_shadow_binding": {},
            "activation_manifest_hash": "a" * 64,
            "image_lock_hash": "1" * 64,
            "image_lock_path": "/external/image-lock.snapshot.json",
            "resources": common.expected_resource_names("p9-run001"),
            "mtls": {
                "ca_certificate_sha256": "2" * 64,
                "client_certificate_sha256": "3" * 64,
                "expected_spiffe_id": "spiffe://after-sale-flow/java-api-service",
            },
            "jwks_sha256": "4" * 64,
            "ledger_public_key_sha256": "5" * 64,
            "lock_nonce": "6" * 64,
        }
    )


def _valid_evidence(
    run_context: dict[str, Any], measurement_hash: str
) -> dict[str, Any]:
    projection = run_context["runtime_projection"]
    digest = "a" * 64
    rooms = []
    runs = []
    for index, room_type in enumerate(("INTAKE", "EVIDENCE", "HEARING", "REVIEW"), 1):
        run_id = f"temporal-run-{index}"
        runs.append(
            {
                "run_id": run_id,
                "room_type": room_type,
                "allocation": "TEMPORAL",
                "protocol": "V2",
                "execution_engine": "TEMPORAL_ACTIVITY",
                "execution_lane": "TARGET_E2E_CANDIDATE",
                "shadow": False,
            }
        )
        rooms.append(
            {
                "room_type": room_type,
                "allocation": "TEMPORAL",
                "protocol": "V2",
                "execution_engine": "TEMPORAL_ACTIVITY",
                "execution_lane": "TARGET_E2E_CANDIDATE",
                "temporal_run_id": run_id,
                "room_fencing_token": index,
                "graph_checkpoint_id": f"checkpoint-{index}",
                "graph_checkpoint_hash": digest,
                "graph_result_hash": digest,
                "proposal_hash": digest,
                "result_envelope_hash": digest,
                "graph_output_authority": "PROPOSAL_ONLY",
                "agent_run_manifest_hash": digest,
                "isolated_domain_db_binding_hash": digest,
                "java_final_receipt_id": f"receipt-{index}",
                "java_final_receipt_hash": digest,
                "java_writer": "JAVA_FINALIZER_ONLY",
                "domain_commit_status": "COMMITTED",
                "completed_at": f"2026-07-27T10:0{index}:00+00:00",
            }
        )
    return {
        "schema_version": "target-architecture-e2e-evidence.v2",
        "candidate_sha": projection["candidateSha"],
        "activation_id": projection["activationId"],
        "environment_generation": projection["environmentGeneration"],
        "compose_project": projection["composeProject"],
        "temporal_namespace": projection["temporalNamespace"],
        "database_identities": projection["databaseIdentities"],
        "case_id": "CASE_P9_SYNTHETIC_0001",
        "run_nonce": projection["runNonce"],
        "run_context_hash": run_context["self_hash"],
        "runtime_measurement_hash": measurement_hash,
        "inventory_complete": True,
        "legacy_run_count": 0,
        "shadow_run_count": 0,
        "infra_only": False,
        "runs": runs,
        "rooms": rooms,
        "activation_receipt": {
            "activation_id": projection["activationId"],
            "state": "ACTIVE",
            "consumed_at": "2026-07-27T10:00:00+00:00",
            "manifest_hash": digest,
        },
    }


def _java_jws(
    private: Any,
    run_context: dict[str, Any],
    evidence: dict[str, Any],
    now: dt.datetime,
) -> str:
    epoch = int(now.timestamp())
    projection = run_context["runtime_projection"]
    return ledger.sign_compact_jws(
        {
            "iss": "java-finalizer",
            "aud": "target-e2e-evidence-harness",
            "iat": epoch - 1,
            "exp": epoch + 120,
            "jti": f"{projection['runNonce']}:CASE_P9_SYNTHETIC_0001",
            "schema_version": "target-e2e-java-evidence-attestation.v1",
            "evidence": evidence,
        },
        private,
        key_id="p9-java-finalizer-01",
        typ="target-e2e-final-evidence+jwt",
    )


def test_compose_is_host_locked_run_scoped_and_has_no_baseline_port_overlap() -> None:
    compose = _compose()
    assert (
        compose["name"]
        == "${TARGET_E2E_PROJECT_NAME:?provision a host-locked target E2E run first}"
    )
    assert compose["services"]["gateway"]["ports"] == [
        "127.0.0.1:${TARGET_E2E_GATEWAY_PORT:-25180}:8080"
    ]
    assert all(
        not service.get("ports")
        for name, service in compose["services"].items()
        if name != "gateway"
    )
    assert set(compose["services"]) == set(common.EXPECTED_SERVICES)
    for service in compose["services"].values():
        labels = service["labels"]
        assert (
            labels["target-e2e.after-sale-flow.dev/lock-nonce"]
            == "${TARGET_E2E_LOCK_NONCE:?}"
        )
        assert labels["target-e2e.after-sale-flow.dev/target-lane-runnable"] == "false"
    for collection in (compose["networks"], compose["volumes"]):
        for resource in collection.values():
            assert (
                resource["labels"]["target-e2e.after-sale-flow.dev/lock-nonce"]
                == "${TARGET_E2E_LOCK_NONCE:?}"
            )


def test_image_lock_binds_manifest_config_layers_source_and_provenance(
    tmp_path: Path,
) -> None:
    path = tmp_path / "images.json"
    document = _image_lock()
    common.write_json(path, document)
    candidate, images, validated = common.load_image_lock(path)
    assert candidate == "b" * 40
    assert validated["self_hash"] == document["self_hash"]
    assert all(
        images[key]["reference"].endswith(images[key]["manifest_digest"])
        for key in images
    )

    for mutation in ("self_hash", "config", "layers", "source"):
        changed = copy.deepcopy(document)
        if mutation == "self_hash":
            changed["self_hash"] = "0" * 64
        elif mutation == "config":
            changed["images"]["java"]["config_digest"] = "not-a-digest"
            changed = common.seal_self_hash(changed)
        elif mutation == "layers":
            changed["images"]["python"]["layer_digests"] = []
            changed = common.seal_self_hash(changed)
        else:
            changed["images"]["frontend"]["source_revision"] = "wrong-source"
            changed = common.seal_self_hash(changed)
        common.write_json(path, changed)
        with pytest.raises(common.TargetE2EError):
            common.load_image_lock(path)


def test_domain_graph_temporal_and_python_authority_are_physically_separated() -> None:
    compose = _compose()
    services = compose["services"]
    assert _networks(services["domain-db"]) == {"domain-data"}
    assert _networks(services["graph-db"]) == {"graph-data", "python-egress"}
    assert _networks(services["temporal-db"]) == {"temporal-data"}
    python = services["python-agent-service"]
    assert _networks(python) == {"python-egress"}
    assert "domain-data" not in _networks(python)
    assert not any(
        key.startswith(("POSTGRES_", "JAVA_DB_", "DOMAIN_DB_"))
        for key in python["environment"]
    )
    assert not any("activation" in value.lower() for value in _volume_sources(python))
    assert not any(
        part in key
        for key in python["environment"]
        for part in ("ACTIVATION_JWS", "ACTIVATION_DIRECTORY", "ACTIVATION_PATH")
    )


def test_python_has_uds_only_inbound_and_mtls_bypass_is_rejected() -> None:
    compose = _compose()
    services = compose["services"]
    python = services["python-agent-service"]
    command = " ".join(python["command"])
    assert "--uds /run/target-e2e/python/agent.sock" in command
    assert "--host" not in command and "--port" not in command
    assert not python.get("ports")
    proxy = services["graph-mtls-proxy"]
    assert _networks(proxy) == {"graph-mtls-client"}
    assert _volume_sources(proxy) & _volume_sources(python) == {
        "${TARGET_E2E_SOCKET_DIR:?}"
    }
    mtls = (DEPLOY / "nginx" / "mtls.conf").read_text(encoding="utf-8")
    assert "server unix:/run/target-e2e/python/agent.sock" in mtls
    assert "ssl_verify_client on" in mtls and "ssl_protocols TLSv1.3" in mtls
    for inbound in (
        "X-Client-Cert",
        "X-SSL-Client-Cert",
        "X-Forwarded-Client-Cert",
        "X-Service-Identity",
    ):
        assert f'proxy_set_header {inbound} ""' in mtls
    adapter = (DEPLOY / "python" / "mtls_adapter.py").read_text(encoding="utf-8")
    for proof in (
        "expected_ca_sha256",
        "expected_client_sha256",
        "certificate.issuer",
        "CLIENT_AUTH",
        "uri_names != {self._expected_spiffe_id}",
        "ca_key.verify",
    ):
        assert proof in adapter
    readiness = (SCRIPTS / "readiness.py").read_text(encoding="utf-8")
    assert "tcp_bypass_listener_present" in readiness
    assert "/proc/net/tcp" in readiness


@pytest.mark.parametrize(
    ("listener", "owned_inodes", "resolver_addresses", "expected_unexpected"),
    (
        (
            {"family": "ipv4", "address": "127.0.0.11", "uid": "0", "inode": "1"},
            set(),
            {"127.0.0.11"},
            [],
        ),
        (
            {"family": "ipv4", "address": "0.0.0.0", "uid": "0", "inode": "2"},
            set(),
            {"127.0.0.11"},
            ["2"],
        ),
        (
            {"family": "ipv4", "address": "0.0.0.0", "uid": "0", "inode": "6"},
            set(),
            {"0.0.0.0"},
            ["6"],
        ),
        (
            {"family": "ipv4", "address": "172.28.0.5", "uid": "0", "inode": "3"},
            set(),
            {"127.0.0.11"},
            ["3"],
        ),
        (
            {"family": "ipv4", "address": "127.0.0.11", "uid": "0", "inode": "4"},
            {"4"},
            {"127.0.0.11"},
            ["4"],
        ),
        (
            {"family": "ipv4", "address": "127.0.0.1", "uid": "0", "inode": "5"},
            set(),
            {"127.0.0.11"},
            ["5"],
        ),
    ),
    ids=(
        "docker-dns-ownerless-listener-is-allowed",
        "wildcard-listener-is-rejected",
        "wildcard-resolver-cannot-exempt-listener",
        "container-ip-listener-is-rejected",
        "process-owned-dns-listener-is-rejected",
        "unexpected-ownerless-listener-is-rejected",
    ),
)
def test_readiness_tcp_listener_exception_is_exact(
    listener: dict[str, str],
    owned_inodes: set[str],
    resolver_addresses: set[str],
    expected_unexpected: list[str],
) -> None:
    unexpected = readiness._unexpected_tcp_listeners(
        [listener], owned_inodes, resolver_addresses
    )
    assert [item["inode"] for item in unexpected] == expected_unexpected


def test_readiness_tcp_listener_ownership_scan_fails_closed() -> None:
    class InaccessibleProc:
        def iterdir(self):
            raise PermissionError("ownership scan denied")

    with pytest.raises(PermissionError, match="ownership scan denied"):
        readiness._owned_socket_inodes(InaccessibleProc())


def test_readiness_tcp_listener_probe_reads_visible_process_ownership_and_resolver() -> (
    None
):
    probe = readiness._tcp_listener_probe()
    assert "Path('/proc/net/tcp6')" in probe
    assert "(process / \"fd\").iterdir()" in probe
    assert 'listener["inode"] in owned_socket_inodes' in probe
    assert 'listener["family"] == "ipv4"' in probe
    assert 'listener["address"] == "127.0.0.11"' in probe
    assert '"127.0.0.11" in resolver_ipv4_addresses' in probe


def test_jwks_is_static_public_only_and_has_no_java_business_surface() -> None:
    compose = _compose()
    services = compose["services"]
    assert "jwks-publisher" not in services
    jwks = services["jwks-server"]
    assert jwks["image"] == "${TARGET_E2E_NGINX_IMAGE:?immutable image digest required}"
    assert not jwks.get("environment")
    assert _networks(jwks) == {"python-egress"}
    assert services["python-agent-service"]["environment"]["GRAPH_JWKS_URL"] == (
        "http://jwks-server:8080/.well-known/graph-jwks.json"
    )
    config = (DEPLOY / "nginx" / "jwks.conf").read_text(encoding="utf-8")
    assert "location = /.well-known/graph-jwks.json" in config
    assert "location /" in config and "return 404" in config
    assert "HeaderAuthenticationFilter" not in config
    python_members = {
        name
        for name, service in services.items()
        if "python-egress" in _networks(service)
    }
    assert python_members == {
        "python-agent-service",
        "graph-db",
        "minio",
        "jwks-server",
    }
    assert not any(name.startswith("java-") for name in python_members)


def test_activation_grant_is_java_control_only_and_python_gets_strict_projection() -> (
    None
):
    compose = _compose()
    services = compose["services"]
    activation_consumers = {
        name
        for name, service in services.items()
        if any(
            "TARGET_E2E_ACTIVATION_DIR" in source for source in _volume_sources(service)
        )
    }
    assert activation_consumers == {"java-control-worker"}
    python_env = services["python-agent-service"]["environment"]
    assert (
        python_env["GRAPH_TARGET_E2E_RUNTIME_CONTEXT"]
        == "${GRAPH_TARGET_E2E_RUNTIME_CONTEXT:?}"
    )
    assert python_env["GRAPH_TARGET_E2E_BINDINGS"] == "${GRAPH_TARGET_E2E_BINDINGS:?}"
    provision = (SCRIPTS / "provision.py").read_text(encoding="utf-8")
    for field in (
        '"perCommandManifestAllowed": False',
        '"databaseIdentities"',
        '"trustedSigningKeyIds"',
        'typ="target-e2e-activation+jwt"',
    ):
        assert field in provision


def test_append_only_ledger_uses_real_p256_chain_and_detects_tamper(
    tmp_path: Path,
) -> None:
    private, public, _private_path, _public_path = _write_key_pair(tmp_path, "harness")
    now = dt.datetime.now(dt.timezone.utc)
    run_context = _run_context(now)
    context = common.ledger_context_from_run_context(run_context)
    path = tmp_path / "ledger.jsonl"
    first = ledger.append_record(
        path,
        private,
        public,
        key_id="harness-01",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="unit-harness",
        case_id=None,
        payload_type="PROVISIONED_RUN_CONTEXT",
        payload=run_context,
    )
    second = ledger.append_record(
        path,
        private,
        public,
        key_id="harness-01",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="unit-runtime",
        case_id=None,
        payload_type="RUNTIME_MEASUREMENT",
        payload={"measurement": "direct"},
    )
    records = ledger.verify_ledger(
        path,
        public,
        expected_public_key_sha256=ledger.public_key_sha256(public),
        expected_context=context,
        require_fresh_last=True,
    )
    assert first["previous_record_hash"] == ledger.ZERO_SHA256
    assert second["previous_record_hash"] == first["record_hash"]
    assert len(records) == 2

    tampered = path.read_text(encoding="utf-8").replace('"direct"', '"forged"')
    path.write_text(tampered, encoding="utf-8")
    with pytest.raises(common.TargetE2EError):
        ledger.verify_ledger(
            path,
            public,
            expected_public_key_sha256=ledger.public_key_sha256(public),
            expected_context=context,
        )


def test_java_evidence_requires_real_jws_and_rejects_legacy_shadow_infra_and_stale() -> (
    None
):
    now = dt.datetime.now(dt.timezone.utc)
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key()
    run_context = _run_context(now)
    measurement_hash = "9" * 64
    evidence = _valid_evidence(run_context, measurement_hash)
    compact = _java_jws(private, run_context, evidence, now)
    result, key_id, verified = assertion.verify_java_attestation(
        compact,
        run_context=run_context,
        runtime_measurement_hash=measurement_hash,
        case_id="CASE_P9_SYNTHETIC_0001",
        trusted_public_keys={"p9-java-finalizer-01": public},
        now=now,
    )
    assert result["status"] == "PASS"
    assert key_id == "p9-java-finalizer-01"
    assert verified == evidence

    for field, value in (
        ("legacy_run_count", 1),
        ("shadow_run_count", 1),
        ("infra_only", True),
        ("inventory_complete", False),
    ):
        changed = copy.deepcopy(evidence)
        changed[field] = value
        signed = _java_jws(private, run_context, changed, now)
        with pytest.raises(common.TargetE2EError):
            assertion.verify_java_attestation(
                signed,
                run_context=run_context,
                runtime_measurement_hash=measurement_hash,
                case_id="CASE_P9_SYNTHETIC_0001",
                trusted_public_keys={"p9-java-finalizer-01": public},
                now=now,
            )

    shadow = copy.deepcopy(evidence)
    shadow["runs"][0]["shadow"] = True
    signed_shadow = _java_jws(private, run_context, shadow, now)
    with pytest.raises(common.TargetE2EError):
        assertion.verify_java_attestation(
            signed_shadow,
            run_context=run_context,
            runtime_measurement_hash=measurement_hash,
            case_id="CASE_P9_SYNTHETIC_0001",
            trusted_public_keys={"p9-java-finalizer-01": public},
            now=now,
        )

    wrong_activation = copy.deepcopy(evidence)
    wrong_activation["activation_receipt"]["manifest_hash"] = "0" * 64
    signed_wrong_activation = _java_jws(
        private, run_context, wrong_activation, now
    )
    with pytest.raises(common.TargetE2EError, match="activation manifest"):
        assertion.verify_java_attestation(
            signed_wrong_activation,
            run_context=run_context,
            runtime_measurement_hash=measurement_hash,
            case_id="CASE_P9_SYNTHETIC_0001",
            trusted_public_keys={"p9-java-finalizer-01": public},
            now=now,
        )

    with pytest.raises(common.TargetE2EError):
        assertion.verify_java_attestation(
            compact[:-1] + ("A" if compact[-1] != "A" else "B"),
            run_context=run_context,
            runtime_measurement_hash=measurement_hash,
            case_id="CASE_P9_SYNTHETIC_0001",
            trusted_public_keys={"p9-java-finalizer-01": public},
            now=now,
        )
    with pytest.raises(common.TargetE2EError):
        assertion.verify_java_attestation(
            compact,
            run_context=run_context,
            runtime_measurement_hash=measurement_hash,
            case_id="CASE_P9_SYNTHETIC_0001",
            trusted_public_keys={"p9-java-finalizer-01": public},
            now=now + dt.timedelta(minutes=10),
        )


def test_assertion_has_fixed_run_local_source_and_no_arbitrary_url_or_path() -> None:
    source = (SCRIPTS / "assert_evidence.py").read_text(encoding="utf-8")
    assert "--source" not in source
    assert "urlopen" not in source
    assert 'evidence_dir / "inbox" / f"{case_id}.java-evidence.jws"' in source
    assert 'source_kind="JAVA_SIGNED"' in source
    assert "require_fresh_last=True" in source


def test_attested_receipt_has_self_hash_and_real_signature(tmp_path: Path) -> None:
    private, public, _private_path, _public_path = _write_key_pair(tmp_path, "receipt")
    receipt = ledger.attest_document(
        {"schema_version": "receipt.v1", "status": "PASS"},
        private,
        public,
        key_id="receipt-key-01",
    )
    ledger.verify_attested_document(
        receipt,
        public,
        expected_key_sha256=ledger.public_key_sha256(public),
        context="unit receipt",
    )
    changed = copy.deepcopy(receipt)
    changed["status"] = "FORGED"
    with pytest.raises(common.TargetE2EError):
        ledger.verify_attested_document(
            changed,
            public,
            expected_key_sha256=ledger.public_key_sha256(public),
            context="unit receipt",
        )


def test_env_cannot_redirect_paths_outside_the_host_locked_run(tmp_path: Path) -> None:
    run_id = "p9-lock01"
    project = f"aflow-target-e2e-{run_id}"
    runtime_root = tmp_path / "target-e2e"
    run_directory = runtime_root / run_id
    locks = runtime_root / ".locks"
    run_directory.mkdir(parents=True)
    locks.mkdir()
    port_lock_path = locks / "gateway-25180.lock.json"
    port_lock = common.seal_self_hash(
        {
            "schema_version": "target-e2e-port-lock.v1",
            "state": "ACTIVE",
            "gateway_port": 25180,
            "project_name": project,
            "run_id": run_id,
            "lock_nonce": "a" * 64,
            "owner": common.current_owner(),
            "created_at": "2026-07-27T00:00:00+00:00",
            "released_at": None,
        }
    )
    common.write_json(port_lock_path, port_lock)
    lock_path = locks / f"{project}.lock.json"
    host_lock = common.seal_self_hash(
        {
            "schema_version": "target-e2e-host-lock.v1",
            "state": "ACTIVE",
            "project_name": project,
            "run_id": run_id,
            "runtime_root": runtime_root.as_posix(),
            "run_directory": run_directory.as_posix(),
            "env_file": (run_directory / "target-e2e.env").as_posix(),
            "lock_nonce": "a" * 64,
            "owner": common.current_owner(),
            "candidate_sha": "b" * 40,
            "image_lock_hash": "c" * 64,
            "gateway_port": 25180,
            "port_lock": port_lock_path.as_posix(),
            "resources": common.expected_resource_names(run_id),
            "ledger_public_key_sha256": "d" * 64,
            "created_at": "2026-07-27T00:00:00+00:00",
            "released_at": None,
        }
    )
    common.write_json(lock_path, host_lock)
    values = {
        "TARGET_E2E_LOCK_PATH": lock_path.as_posix(),
        "TARGET_E2E_RUN_ID": run_id,
        "TARGET_E2E_PROJECT_NAME": project,
        "TARGET_E2E_LOCK_NONCE": "a" * 64,
        "TARGET_E2E_BUILD_ID": "b" * 40,
        "TARGET_E2E_SOURCE_COMMIT": "b" * 40,
        "TARGET_E2E_IMAGE_LOCK_HASH": "c" * 64,
        "TARGET_E2E_GATEWAY_PORT": "25180",
        "TARGET_E2E_IMAGE_LOCK_PATH": (
            run_directory / "image-lock.snapshot.json"
        ).as_posix(),
        "TARGET_E2E_RUN_CONTEXT_PATH": (run_directory / "run-context.json").as_posix(),
        "TARGET_E2E_SECRETS_DIR": (run_directory / "secrets").as_posix(),
        "TARGET_E2E_PUBLIC_DIR": (run_directory / "public").as_posix(),
        "TARGET_E2E_ACTIVATION_DIR": (run_directory / "java-activation").as_posix(),
        "TARGET_E2E_EVIDENCE_DIR": (run_directory / "evidence").as_posix(),
        "TARGET_E2E_SOCKET_DIR": (run_directory / "python-socket").as_posix(),
    }
    env_path = run_directory / "target-e2e.env"
    env_path.write_text(
        "\n".join(f"{key}={common.env_quote(value)}" for key, value in values.items())
        + "\n",
        encoding="ascii",
    )
    common.validate_env_lock(env_path)
    values["TARGET_E2E_PUBLIC_DIR"] = (tmp_path / "attacker-public").as_posix()
    env_path.write_text(
        "\n".join(f"{key}={common.env_quote(value)}" for key, value in values.items())
        + "\n",
        encoding="ascii",
    )
    with pytest.raises(common.TargetE2EError, match="redirects locked runtime path"):
        common.validate_env_lock(env_path)


def test_host_lock_and_teardown_are_exact_and_never_use_broad_compose_down() -> None:
    provision = (SCRIPTS / "provision.py").read_text(encoding="utf-8")
    assert "provision.coordinator" in provision
    assert "O_EXCL" in provision
    assert "target-e2e-host-lock.v1" in provision
    assert "gateway-" in provision and "target-e2e-port-lock.v1" in provision
    teardown = (SCRIPTS / "teardown.py").read_text(encoding="utf-8")
    assert '"docker", "rm", "--force"' in teardown
    assert '"docker", "network", "rm"' in teardown
    assert '"docker", "volume", "rm"' in teardown
    assert '"--remove-orphans"' not in teardown
    assert '"down"' not in teardown
    assert "_validate_labels" in teardown and "_release_port_lock" in teardown


def test_application_contract_gates_keep_infrastructure_only_from_claiming_pass() -> (
    None
):
    gates = json.loads(
        (DEPLOY / "application-contract-gates.json").read_text(encoding="utf-8")
    )
    assert all(gate["required"] is True for gate in gates["gates"])
    assert all(
        gate["status"] == "BLOCKING_UNTIL_RUNTIME_PROVES" for gate in gates["gates"]
    )
    readiness_source = (SCRIPTS / "readiness.py").read_text(encoding="utf-8")
    assert '"status": "INFRASTRUCTURE_READY_ONLY"' in readiness_source
    assert '"target_lane_runnable": False' in readiness_source
    assert "runtime_measurement_hash" in readiness_source
