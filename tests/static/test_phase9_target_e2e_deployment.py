from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from typing import Any

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
COMPOSE_PATH = ROOT / "docker-compose.target-e2e.yml"
DEPLOY = ROOT / "deploy" / "target-e2e"
SCRIPTS = ROOT / "scripts" / "target-e2e"


def _compose() -> dict[str, Any]:
    value = yaml.safe_load(COMPOSE_PATH.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _networks(service: dict[str, Any]) -> set[str]:
    value = service.get("networks", [])
    return set(value if isinstance(value, list) else value)


def _load_assertion_module() -> Any:
    sys.path.insert(0, str(SCRIPTS))
    spec = importlib.util.spec_from_file_location(
        "phase9_target_assert_evidence",
        SCRIPTS / "assert_evidence.py",
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_target_compose_has_run_scoped_project_networks_volumes_and_port() -> None:
    compose = _compose()
    assert compose["name"].startswith("aflow-target-e2e-${TARGET_E2E_RUN_ID:?")
    assert compose["services"]["gateway"]["ports"] == [
        "127.0.0.1:${TARGET_E2E_GATEWAY_PORT:-25180}:8080"
    ]
    assert all(
        not service.get("ports")
        for name, service in compose["services"].items()
        if name != "gateway"
    )
    for network in compose["networks"].values():
        assert network["name"].startswith("aflow_target_e2e_${TARGET_E2E_RUN_ID:?}_")
    for volume in compose["volumes"].values():
        assert volume["name"].startswith("aflow_target_e2e_${TARGET_E2E_RUN_ID:?}_")
    assert {5173, 8080, 18000, 18010, 18080}.isdisjoint({25180})


def test_every_image_and_worker_identity_is_immutable_and_candidate_bound() -> None:
    compose = _compose()
    for service in compose["services"].values():
        image = service["image"]
        assert image.startswith("${TARGET_E2E_")
        assert ":?immutable image digest required}" in image
        assert "latest" not in image.lower()
        labels = service["labels"]
        assert labels["target-e2e.after-sale-flow.dev/build-id"] == "${TARGET_E2E_BUILD_ID:?}"
        assert labels["target-e2e.after-sale-flow.dev/target-lane-runnable"] == "false"
    for name in ("java-control-worker", "java-agent-worker"):
        environment = compose["services"][name]["environment"]
        assert environment["TEMPORAL_WORKER_BUILD_ID"] == "${TARGET_E2E_BUILD_ID:?}"
        assert environment["TEMPORAL_WORKER_VERSIONING_MODE"] == "BUILD_ID"
    provision = (SCRIPTS / "provision.py").read_text(encoding="utf-8")
    assert "target-e2e-image-lock.v1" in (SCRIPTS / "common.py").read_text(encoding="utf-8")
    assert "image lock build_id is not the checked-out candidate commit" in provision


def test_domain_graph_and_temporal_storage_are_physically_separated() -> None:
    compose = _compose()
    services = compose["services"]
    assert {"domain-db", "graph-db", "temporal-db"} <= set(services)
    assert services["domain-db"]["volumes"][0] == "target_domain_data:/var/lib/postgresql/data"
    assert services["graph-db"]["volumes"][0] == "target_graph_data:/var/lib/postgresql/data"
    assert services["temporal-db"]["volumes"][0] == "target_temporal_data:/var/lib/postgresql/data"
    assert _networks(services["domain-db"]) == {"domain-data"}
    assert _networks(services["graph-db"]) == {"graph-data"}
    assert _networks(services["temporal-db"]) == {"temporal-data"}
    assert services["temporal-namespace-init"]["environment"][
        "TARGET_E2E_TEMPORAL_NAMESPACE"
    ] == "${TARGET_E2E_TEMPORAL_NAMESPACE:?}"
    assert "target-e2e-*" in (DEPLOY / "temporal" / "create-namespace.sh").read_text(encoding="utf-8")


def test_python_has_only_graph_database_authority_and_no_domain_network_path() -> None:
    compose = _compose()
    services = compose["services"]
    python = services["python-agent-service"]
    environment = python["environment"]
    assert environment["GRAPH_DATABASE_DSN"].startswith(
        "postgresql://graph_runtime:${TARGET_E2E_GRAPH_RUNTIME_PASSWORD:?}@graph-db:5432/target_graph"
    )
    assert not any(
        key.startswith(("POSTGRES_", "JAVA_DB_", "DOMAIN_DB_")) for key in environment
    )
    assert "domain-data" not in _networks(python)
    assert not (_networks(python) & _networks(services["domain-db"]))
    preflight = (SCRIPTS / "preflight.py").read_text(encoding="utf-8")
    readiness = (SCRIPTS / "readiness.py").read_text(encoding="utf-8")
    exporter = (SCRIPTS / "export_forensics.py").read_text(encoding="utf-8")
    assert "Python received Domain database credentials" in preflight
    assert "runtime inspection disproved Python/Domain isolation" in readiness
    assert "target-e2e-network-proof.v1" in exporter


def test_real_mtls_is_terminated_and_injected_only_across_two_member_adapter_network() -> None:
    compose = _compose()
    services = compose["services"]
    adapter_members = {
        name for name, service in services.items() if "graph-adapter" in _networks(service)
    }
    assert adapter_members == {"graph-mtls-proxy", "python-agent-service"}
    assert services["java-agent-worker"]["environment"][
        "APP_AGENT_RUN_V2_GRAPH_CLIENT_BASE_URI"
    ] == "https://graph-mtls-proxy:8443"
    assert services["java-agent-worker"]["environment"][
        "APP_AGENT_RUN_V2_GRAPH_CLIENT_ALLOW_PLAINTEXT_TRANSPORT"
    ] == "false"
    mtls = (DEPLOY / "nginx" / "mtls.conf").read_text(encoding="utf-8")
    assert "ssl_protocols TLSv1.3" in mtls
    assert "ssl_verify_client on" in mtls
    assert "$ssl_client_escaped_cert" in mtls
    adapter = (DEPLOY / "python" / "mtls_adapter.py").read_text(encoding="utf-8")
    assert '"after_sale_flow.mtls"' in adapter
    assert '"client_certificate_der"' in adapter
    assert "x-target-e2e-mtls-certificate" in adapter
    assert "request.headers" not in adapter
    provision = (SCRIPTS / "provision.py").read_text(encoding="utf-8")
    assert "prime256v1" in provision
    assert "spiffe://after-sale-flow/java-api-service" in provision
    assert "extendedKeyUsage=clientAuth" in provision


def test_separate_public_only_jwks_publisher_breaks_boot_cycle() -> None:
    compose = _compose()
    services = compose["services"]
    publisher = services["jwks-publisher"]
    assert publisher["environment"]["APP_GRAPH_JWKS_ENABLED"] == "true"
    assert publisher["environment"]["APP_AGENT_RUN_V2_GRAPH_CLIENT_MODE"] == "DISABLED"
    assert "python-agent-service" not in publisher["depends_on"]
    assert services["python-agent-service"]["depends_on"]["jwks-publisher"][
        "condition"
    ] == "service_healthy"
    assert services["python-agent-service"]["environment"]["GRAPH_JWKS_URL"] == (
        "http://jwks-publisher:8080/.well-known/graph-jwks.json"
    )
    assert "graph-public-keys" in publisher["volumes"][0]["source"]


def test_provisioning_and_teardown_keep_secrets_external_and_export_forensics() -> None:
    provision = (SCRIPTS / "provision.py").read_text(encoding="utf-8")
    assert "assert_external_runtime_path" in provision
    assert "target-e2e-short-lived-activation.v1" in provision
    assert "target-e2e-graph-registry-seed.v1" in provision
    assert "BLOCKING_UNTIL_CONSUMED_AND_RECEIPTED" in provision
    assert "activation.private.pem" in provision
    teardown = (SCRIPTS / "teardown.py").read_text(encoding="utf-8")
    assert "export_forensics.export_forensics" in teardown
    assert '"--volumes"' in teardown
    assert "target-e2e-teardown-receipt.v1" in teardown
    assert not (ROOT / ".runtime" / "target-e2e").exists()


def _valid_evidence() -> dict[str, Any]:
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
                "worker_lane": "candidate",
            }
        )
        rooms.append(
            {
                "room_type": room_type,
                "allocation": "TEMPORAL",
                "protocol": "V2",
                "execution_engine": "TEMPORAL_ACTIVITY",
                "worker_lane": "candidate",
                "temporal_run_id": run_id,
                "graph_checkpoint_id": f"checkpoint-{index}",
                "graph_checkpoint_hash": digest,
                "graph_result_hash": digest,
                "java_final_receipt_id": f"receipt-{index}",
                "java_final_receipt_hash": digest,
                "completed_at": f"2026-07-27T00:0{index}:00+00:00",
            }
        )
    return {
        "schema_version": "target-architecture-e2e-evidence.v1",
        "run_id": "p9-run001",
        "build_id": "b" * 40,
        "case_id": "case-target-001",
        "inventory_complete": True,
        "legacy_run_count": 0,
        "runs": runs,
        "rooms": rooms,
        "activation_receipt": {
            "activation_id": "activation-001",
            "consumed": True,
            "consumed_at": "2026-07-27T00:00:00+00:00",
            "registry_hash": digest,
        },
    }


def test_evidence_assertions_require_all_target_architecture_proofs() -> None:
    assertion = _load_assertion_module()
    valid = _valid_evidence()
    receipt = assertion.validate_target_evidence(valid, "p9-run001", "b" * 40, "a" * 64)
    assert receipt["status"] == "PASS"
    assert receipt["legacy_run_count"] == 0
    assert receipt["rooms"] == ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]

    mutations = (
        ("legacy_run_count", 1),
        ("inventory_complete", False),
    )
    for key, value in mutations:
        changed = _valid_evidence()
        changed[key] = value
        with pytest.raises(assertion.common.TargetE2EError):
            assertion.validate_target_evidence(changed, "p9-run001", "b" * 40, "a" * 64)

    for field, value in (
        ("allocation", "LEGACY"),
        ("protocol", "V1"),
        ("execution_engine", "EXECUTOR"),
        ("worker_lane", "default"),
        ("graph_checkpoint_hash", ""),
        ("graph_result_hash", ""),
        ("java_final_receipt_hash", ""),
    ):
        changed = _valid_evidence()
        changed["rooms"][0][field] = value
        with pytest.raises(assertion.common.TargetE2EError):
            assertion.validate_target_evidence(changed, "p9-run001", "b" * 40, "a" * 64)


def test_missing_application_evidence_contract_is_an_explicit_blocker() -> None:
    source = (SCRIPTS / "assert_evidence.py").read_text(encoding="utf-8")
    assert "BLOCKING_APPLICATION_CONTRACT" in source
    assert "pytest.skip" not in source
    for required in (
        "TEMPORAL",
        "V2",
        "TEMPORAL_ACTIVITY",
        "candidate",
        "graph_checkpoint_hash",
        "graph_result_hash",
        "java_final_receipt_hash",
        "LEGACY",
    ):
        assert required in source
    gates = __import__("json").loads(
        (DEPLOY / "application-contract-gates.json").read_text(encoding="utf-8")
    )
    assert gates["schema_version"] == "target-e2e-application-contract-gates.v1"
    assert len(gates["gates"]) == 6
    assert all(gate["required"] is True for gate in gates["gates"])
    assert all(gate["status"] == "BLOCKING_UNTIL_RUNTIME_PROVES" for gate in gates["gates"])
    readiness = (SCRIPTS / "readiness.py").read_text(encoding="utf-8")
    assert '"status": "INFRASTRUCTURE_READY_ONLY"' in readiness
    assert '"target_lane_runnable": False' in readiness
