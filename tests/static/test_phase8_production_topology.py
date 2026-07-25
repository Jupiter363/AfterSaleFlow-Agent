from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
PHASE8 = ROOT / "deploy" / "production" / "phase8"


def _docs(path: Path) -> list[dict[str, Any]]:
    values = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    assert all(isinstance(value, dict) for value in values), path
    return values


def _named(path: Path, kind: str) -> dict[str, dict[str, Any]]:
    return {
        value["metadata"]["name"]: value
        for value in _docs(path)
        if value.get("kind") == kind
    }


def _env(deployment: dict[str, Any]) -> dict[str, str]:
    values = deployment["spec"]["template"]["spec"]["containers"][0]["env"]
    return {value["name"]: value["value"] for value in values}


def _config_yaml(config: dict[str, Any], key: str) -> dict[str, Any]:
    value = yaml.safe_load(config["data"][key])
    assert isinstance(value, dict), key
    return value


def test_kustomization_is_render_only_and_does_not_import_i4_assets() -> None:
    kustomization = _docs(PHASE8 / "kustomization.yaml")[0]
    resources = set(kustomization["resources"])

    assert kustomization["namespace"] == "after-sale-flow-phase8-render-only"
    assert kustomization["labels"] == [
        {
            "pairs": {
                "app.kubernetes.io/part-of": "after-sale-flow",
                "phase8.after-sale-flow.dev/render-only": "true",
            },
            "includeSelectors": True,
            "includeTemplates": True,
        }
    ]
    assert kustomization["commonAnnotations"][
        "phase8.after-sale-flow.dev/production-apply"
    ] == "forbidden"
    assert {
        "java-api.yaml",
        "java-control-worker.yaml",
        "java-agent-worker.yaml",
        "python-agent.yaml",
        "litellm.yaml",
        "pgbouncer.yaml",
        "postgres-read-service.yaml",
        "pdb.yaml",
        "hpa.yaml",
        "capacity-policy.yaml",
        "security/workload-identities.yaml",
        "security/rbac.yaml",
        "security/network-policies.yaml",
        "security/mtls-policies.yaml",
        "security/kms-vault-policy.yaml",
        "security/object-store-policy.yaml",
    } == resources
    assert kustomization["patches"] == [
        {
            "path": "topology-spread.yaml",
            "target": {"group": "apps", "version": "v1", "kind": "Deployment"},
        }
    ]
    assert all("observability" not in path and "../" not in path for path in resources)


def test_workloads_have_contract_minima_resources_and_reserved_images() -> None:
    expected = {
        "after-sale-java-api": (3, "2", "4Gi", "java-api", "domain-api-rw"),
        "after-sale-java-control-worker": (
            3,
            "2",
            "4Gi",
            "java-control-worker",
            "domain-control-rw",
        ),
        "after-sale-java-agent-worker": (
            3,
            "4",
            "8Gi",
            "java-agent-worker",
            "domain-agent-rw",
        ),
        "after-sale-python-agent": (
            4,
            "4",
            "8Gi",
            "python-agent",
            "graph-agent-rw",
        ),
        "after-sale-litellm": (3, "2", "4Gi", "litellm", "model-egress"),
    }
    deployments: dict[str, dict[str, Any]] = {}
    for filename in (
        "java-api.yaml",
        "java-control-worker.yaml",
        "java-agent-worker.yaml",
        "python-agent.yaml",
        "litellm.yaml",
    ):
        deployments.update(_named(PHASE8 / filename, "Deployment"))

    assert set(deployments) == set(expected)
    for name, (replicas, cpu, memory, workload_id, pool_id) in expected.items():
        deployment = deployments[name]
        labels = deployment["metadata"]["labels"]
        pod_spec = deployment["spec"]["template"]["spec"]
        container = pod_spec["containers"][0]
        assert deployment["spec"]["replicas"] == replicas
        assert container["resources"]["requests"] == {"cpu": cpu, "memory": memory}
        assert container["image"].startswith("registry.invalid/")
        assert "@sha256:" in container["image"]
        assert labels["phase8.after-sale-flow.dev/workload-id"] == workload_id
        assert labels["phase8.after-sale-flow.dev/pool-id"] == pool_id
        assert pod_spec["automountServiceAccountToken"] is False
        assert _env(deployment)["PHASE8_PRODUCTION_ACTIVATION"] == "FORBIDDEN"


def test_three_domain_spread_anti_affinity_and_pdb_contracts_are_explicit() -> None:
    patch = yaml.safe_load((PHASE8 / "topology-spread.yaml").read_text(encoding="utf-8"))
    assert patch[0]["path"] == "/spec/template/spec/topologySpreadConstraints"
    spread = patch[0]["value"][0]
    assert spread == {
        "maxSkew": 1,
        "minDomains": 3,
        "topologyKey": "topology.kubernetes.io/zone",
        "whenUnsatisfiable": "DoNotSchedule",
        "labelSelector": {
            "matchLabels": {"app.kubernetes.io/part-of": "after-sale-flow"}
        },
        "matchLabelKeys": ["phase8.after-sale-flow.dev/workload-id"],
    }
    anti_affinity = patch[1]["value"]["podAntiAffinity"]
    assert anti_affinity["preferredDuringSchedulingIgnoredDuringExecution"][0][
        "podAffinityTerm"
    ]["topologyKey"] == "kubernetes.io/hostname"

    pdbs = _named(PHASE8 / "pdb.yaml", "PodDisruptionBudget")
    assert pdbs["after-sale-python-agent"]["spec"]["minAvailable"] == 3
    assert pdbs["after-sale-otel-collector"]["spec"]["minAvailable"] == 1
    for name in (
        "after-sale-java-api",
        "after-sale-java-control-worker",
        "after-sale-java-agent-worker",
        "after-sale-litellm",
        "after-sale-pgbouncer-domain-api",
        "after-sale-pgbouncer-domain-control",
        "after-sale-pgbouncer-domain-agent",
        "after-sale-pgbouncer-graph-agent",
        "after-sale-pgbouncer-reporting-read",
    ):
        assert pdbs[name]["spec"]["minAvailable"] == 2


def test_hpa_uses_service_signals_and_never_cpu_alone() -> None:
    hpas = _named(PHASE8 / "hpa.yaml", "HorizontalPodAutoscaler")
    assert {name: value["spec"]["minReplicas"] for name, value in hpas.items()} == {
        "after-sale-java-api": 3,
        "after-sale-java-control-worker": 3,
        "after-sale-java-agent-worker": 3,
        "after-sale-python-agent": 4,
        "after-sale-litellm": 3,
        "after-sale-otel-collector": 2,
    }
    metric_names: dict[str, set[str]] = {}
    for name, hpa in hpas.items():
        metrics = hpa["spec"]["metrics"]
        assert metrics
        assert any(metric["type"] in {"Pods", "External"} for metric in metrics)
        assert not any(
            metric["type"] == "Resource"
            and metric["resource"]["name"] == "cpu"
            for metric in metrics
        )
        metric_names[name] = {
            metric[metric["type"].lower()]["metric"]["name"]
            if metric["type"] in {"Pods", "External"}
            else metric["resource"]["name"]
            for metric in metrics
        }
        assert hpa["spec"]["behavior"]["scaleDown"][
            "stabilizationWindowSeconds"
        ] == 300

    assert metric_names["after-sale-java-api"] == {
        "http_inflight_requests",
        "sse_active_connections",
    }
    assert metric_names["after-sale-java-agent-worker"] == {
        "agent_activity_inflight",
        "agent_activity_heartbeat_delay_seconds",
    }
    assert metric_names["after-sale-litellm"] == {
        "provider_request_latency_seconds",
        "provider_open_connections",
    }


def test_machine_readable_capacity_ids_and_target_ceiling_are_stable() -> None:
    config = _docs(PHASE8 / "capacity-policy.yaml")[0]
    assert config["immutable"] is True
    assert config["data"]["engineering-mode"] == "RENDER_ONLY_NONDEPLOYABLE"
    assert _config_yaml(config, "failure-domains.yaml") == {
        "count": 3,
        "topology_key": "topology.kubernetes.io/zone",
        "enforcement": "REQUIRED_EXTERNAL",
    }
    assert set(_config_yaml(config, "workload-ids.yaml").values()) == {
        "java-api",
        "java-control-worker",
        "java-agent-worker",
        "python-agent",
        "litellm",
        "otel-collector",
    }
    pools = _config_yaml(config, "pool-ids.yaml")
    assert {pools[key] for key in (
        "domain_api_rw",
        "domain_control_rw",
        "domain_agent_rw",
        "graph_agent_rw",
        "reporting_ro",
        "model_egress",
    )} == {
        "domain-api-rw",
        "domain-control-rw",
        "domain-agent-rw",
        "graph-agent-rw",
        "reporting-ro",
        "model-egress",
    }
    assert pools["peak_utilization_percent_lt"] == 80
    assert set(_config_yaml(config, "control-ids.yaml").values()) == {
        "case-control",
        "room-control",
        "agent-execution",
        "notification-and-tools",
    }
    admissions = _config_yaml(config, "admission-ids.yaml")
    assert admissions["room_commands"] == {
        "id": "room-command",
        "steady_per_second": 20,
        "burst_per_second": 50,
        "burst_duration_seconds": 30,
    }
    assert admissions["agent_runs"]["burst_total"] == 250
    assert admissions["model_calls"]["sustained_concurrency"] == 100
    assert admissions["model_calls"]["burst_concurrency"] == 200
    assert _config_yaml(config, "sse-contract.yaml")["target_clients"] == 2500
    room = _config_yaml(config, "room-contract.yaml")
    assert room == {
        "target_active_rooms": 1000,
        "durable_timer_wait_percent_gte": 70,
        "target_is_measurement_evidence": False,
    }


def test_pgbouncer_pools_and_reporting_read_binding_are_isolated() -> None:
    deployments = _named(PHASE8 / "pgbouncer.yaml", "Deployment")
    services = _named(PHASE8 / "pgbouncer.yaml", "Service")
    expected = {
        "after-sale-pgbouncer-domain-api": "domain-api-rw",
        "after-sale-pgbouncer-domain-control": "domain-control-rw",
        "after-sale-pgbouncer-domain-agent": "domain-agent-rw",
        "after-sale-pgbouncer-graph-agent": "graph-agent-rw",
        "after-sale-pgbouncer-reporting-read": "reporting-ro",
    }
    assert set(deployments) == set(services) == set(expected)
    for name, pool_id in expected.items():
        deployment = deployments[name]
        assert deployment["spec"]["replicas"] == 3
        assert deployment["metadata"]["labels"][
            "phase8.after-sale-flow.dev/pool-id"
        ] == pool_id
        assert _env(deployment)["POOL_ID"] == pool_id
        assert deployment["spec"]["template"]["spec"]["containers"][0][
            "image"
        ].startswith("registry.invalid/")

    reporting_env = _env(deployments["after-sale-pgbouncer-reporting-read"])
    assert reporting_env["UPSTREAM_ENDPOINT"] == "after-sale-postgres-reporting-read:5432"
    assert reporting_env["DEFAULT_TRANSACTION_READ_ONLY"] == "on"
    read_service = _docs(PHASE8 / "postgres-read-service.yaml")[0]
    assert read_service["spec"] == {
        "type": "ExternalName",
        "externalName": "reporting-read-replica.invalid",
        "ports": [{"name": "postgres", "port": 5432, "targetPort": 5432}],
    }
    java_api = _named(PHASE8 / "java-api.yaml", "Deployment")[
        "after-sale-java-api"
    ]
    assert _env(java_api)["REPORTING_DATABASE_ENDPOINT"] == (
        "after-sale-pgbouncer-reporting-read:6432"
    )


def test_i3_i4_otel_resource_label_identity_and_port_join_is_exact() -> None:
    kustomization = _docs(PHASE8 / "kustomization.yaml")[0]
    assert all("observability" not in path for path in kustomization["resources"])

    hpa = _named(PHASE8 / "hpa.yaml", "HorizontalPodAutoscaler")[
        "after-sale-otel-collector"
    ]
    pdb = _named(PHASE8 / "pdb.yaml", "PodDisruptionBudget")[
        "after-sale-otel-collector"
    ]
    assert hpa["spec"]["scaleTargetRef"]["name"] == "after-sale-otel-collector"
    assert pdb["spec"]["selector"]["matchLabels"] == {
        "app.kubernetes.io/name": "otel-collector"
    }

    identities = _named(
        PHASE8 / "security" / "workload-identities.yaml", "ServiceAccount"
    )
    otel = identities["after-sale-otel-collector"]
    assert otel["metadata"]["labels"]["app.kubernetes.io/part-of"] == (
        "after-sale-flow"
    )
    assert otel["metadata"]["labels"]["app.kubernetes.io/name"] == "otel-collector"

    for filename in (
        "java-api.yaml",
        "java-control-worker.yaml",
        "java-agent-worker.yaml",
        "python-agent.yaml",
        "litellm.yaml",
    ):
        deployment = next(iter(_named(PHASE8 / filename, "Deployment").values()))
        assert _env(deployment)["OTEL_EXPORTER_OTLP_ENDPOINT"] == (
            "http://after-sale-otel-collector:4317"
        )

    network = _named(
        PHASE8 / "security" / "network-policies.yaml", "NetworkPolicy"
    )["otel-ingress-from-phase8-workloads"]
    assert network["spec"]["podSelector"]["matchLabels"] == {
        "app.kubernetes.io/part-of": "after-sale-flow",
        "app.kubernetes.io/name": "otel-collector",
    }
    assert {port["port"] for port in network["spec"]["ingress"][0]["ports"]} == {
        4317,
        4318,
    }
