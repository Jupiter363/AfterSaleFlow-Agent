from __future__ import annotations

import json
import re
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
OBSERVABILITY = ROOT / "deploy" / "observability" / "phase8"
RUNBOOK = ROOT / "docs" / "runbooks" / "temporal-first" / "phase-8-alert-response.md"
DASHBOARD_NAMES = {
    "command-outbox.json",
    "temporal-queue-history.json",
    "agentrun-stream.json",
    "graph-checkpoint-lease.json",
    "model-provider.json",
    "projection-reconciliation.json",
    "security.json",
    "disaster-recovery.json",
}
REQUIRED_ALERT_CATEGORIES = {
    "burn_rate",
    "stuck_work",
    "heartbeat",
    "queue",
    "exporter",
    "security",
    "disaster_recovery",
}
FORBIDDEN_LABEL_KEYS = {
    "case",
    "case_id",
    "party",
    "party_id",
    "run",
    "run_id",
    "actor",
    "actor_id",
    "evidence",
    "evidence_id",
    "tenant",
    "tenant_id",
    "secret",
    "reasoning",
    "hidden_reasoning",
}
SAFE_PROMETHEUS_LABELS = {
    "category",
    "le",
    "owner",
    "service",
    "severity",
    "status",
}


def _documents(name: str) -> list[dict]:
    values = list(yaml.safe_load_all((OBSERVABILITY / name).read_text(encoding="utf-8")))
    assert values and all(isinstance(item, dict) for item in values)
    return values


def _prometheus_rule(name: str) -> dict:
    documents = _documents(name)
    assert len(documents) == 1
    value = documents[0]
    assert value["apiVersion"] == "monitoring.coreos.com/v1"
    assert value["kind"] == "PrometheusRule"
    return value


def _prometheus_labels(expression: str) -> set[str]:
    labels: set[str] = set()
    for selector in re.findall(r"\{([^{}]*)\}", expression):
        labels.update(re.findall(r"([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:=|!=|=~|!~)", selector))
    for aggregation in re.findall(r"\b(?:by|without)\s*\(([^()]*)\)", expression):
        labels.update(item.strip() for item in aggregation.split(",") if item.strip())
    return labels


def _collector_config(config_map: dict) -> dict:
    rendered = yaml.safe_load(config_map["data"]["collector.yaml"])
    assert isinstance(rendered, dict)
    return rendered


def test_collector_uses_the_shared_render_only_identity_and_otlp_ports() -> None:
    resources = _documents("otel-collector.yaml")
    assert {item["kind"] for item in resources} == {"ConfigMap", "Deployment", "Service"}
    for item in resources:
        labels = item["metadata"]["labels"]
        assert labels["app.kubernetes.io/part-of"] == "after-sale-flow"
        assert labels["app.kubernetes.io/name"] == "otel-collector"
        annotations = item["metadata"]["annotations"]
        assert annotations["phase8.after-sale-flow.dev/status"] == "UNOBSERVED_RENDER_ONLY"
        assert annotations["phase8.after-sale-flow.dev/deployable"] == "false"

    config_map = next(item for item in resources if item["kind"] == "ConfigMap")
    deployment = next(item for item in resources if item["kind"] == "Deployment")
    service = next(item for item in resources if item["kind"] == "Service")
    assert config_map["metadata"]["name"] == "otel-collector"
    assert deployment["metadata"]["name"] == "after-sale-otel-collector"
    assert service["metadata"]["name"] == "after-sale-otel-collector"
    assert deployment["spec"]["replicas"] >= 2
    pod_spec = deployment["spec"]["template"]["spec"]
    assert pod_spec["serviceAccountName"] == "after-sale-otel-collector"
    assert pod_spec["automountServiceAccountToken"] is False
    spread = pod_spec["topologySpreadConstraints"]
    assert len(spread) == 1
    assert spread[0] == {
        "maxSkew": 1,
        "minDomains": 3,
        "topologyKey": "topology.kubernetes.io/zone",
        "whenUnsatisfiable": "DoNotSchedule",
        "labelSelector": {
            "matchLabels": {
                "app.kubernetes.io/part-of": "after-sale-flow",
                "app.kubernetes.io/name": "otel-collector",
            }
        },
    }
    required_anti_affinity = pod_spec["affinity"]["podAntiAffinity"][
        "requiredDuringSchedulingIgnoredDuringExecution"
    ]
    assert required_anti_affinity == [
        {
            "topologyKey": "kubernetes.io/hostname",
            "labelSelector": {
                "matchLabels": {
                    "app.kubernetes.io/part-of": "after-sale-flow",
                    "app.kubernetes.io/name": "otel-collector",
                }
            },
        }
    ]
    container = pod_spec["containers"][0]
    image = container["image"]
    assert re.fullmatch(r"registry\.invalid/.+@sha256:[0-9a-f]{64}", image)
    assert image.endswith("0" * 64)
    assert container["resources"] == {
        "requests": {"cpu": "2", "memory": "4Gi"},
        "limits": {"cpu": "4", "memory": "8Gi"},
    }
    service_ports = {item["name"]: item["port"] for item in service["spec"]["ports"]}
    assert service_ports["otlp-grpc"] == 4317
    assert service_ports["otlp-http"] == 4318

    rendered = (OBSERVABILITY / "otel-collector.yaml").read_text(encoding="utf-8")
    assert "http://" not in rendered and "https://" not in rendered
    assert "secretKeyRef" not in rendered
    assert "kind: ServiceAccount" not in rendered
    assert "PRODUCTION_READY" not in rendered
    assert "PRODUCTION_CHECKPOINT_PASS" not in rendered


def test_collector_drops_sensitive_attributes_before_every_export() -> None:
    config_map = next(item for item in _documents("otel-collector.yaml") if item["kind"] == "ConfigMap")
    config = _collector_config(config_map)
    processors = config["processors"]
    assert "transform/drop_sensitive_attributes" in processors
    assert "resource/drop_sensitive_attributes" in processors

    transform_text = json.dumps(processors["transform/drop_sensitive_attributes"])
    resource_keys = {
        item["key"] for item in processors["resource/drop_sensitive_attributes"]["attributes"]
    }
    for key in (
        "case.id",
        "party.id",
        "run.id",
        "actor.id",
        "evidence.id",
        "tenant.id",
        "user.id",
        "workflow.id",
        "secret",
        "prompt.raw",
        "output.raw",
        "reasoning.hidden",
        "hidden_reasoning",
    ):
        assert key in transform_text
        assert key in resource_keys

    pipelines = config["service"]["pipelines"]
    assert set(pipelines) == {"traces", "metrics", "logs"}
    for pipeline in pipelines.values():
        assert pipeline["processors"] == [
            "memory_limiter",
            "transform/drop_sensitive_attributes",
            "resource/drop_sensitive_attributes",
            "batch",
        ]
    assert set(config["exporters"]) == {"prometheus", "nop"}


def test_recording_rules_are_low_cardinality_and_cover_all_dashboard_groups() -> None:
    rule = _prometheus_rule("recording-rules.yaml")
    groups = rule["spec"]["groups"]
    names = {item["name"] for item in groups}
    assert {
        "phase8.command-slo",
        "phase8.temporal-queue-history",
        "phase8.agentrun-stream",
        "phase8.graph-checkpoint-lease",
        "phase8.model-provider",
        "phase8.projection-reconciliation",
        "phase8.security",
        "phase8.disaster-recovery",
        "phase8.collector-exporter",
        "phase8.source-freshness",
    } == names

    records: set[str] = set()
    for group in groups:
        assert re.fullmatch(r"phase8\.[a-z0-9-]+", group["name"])
        for item in group["rules"]:
            assert set(item) == {"record", "expr"}
            records.add(item["record"])
            labels = _prometheus_labels(item["expr"])
            assert labels <= SAFE_PROMETHEUS_LABELS
            assert not labels & FORBIDDEN_LABEL_KEYS
    assert len(records) >= 36


def test_source_health_never_turns_absence_into_a_healthy_zero() -> None:
    rule = _prometheus_rule("recording-rules.yaml")
    records = {
        item["record"]: item["expr"]
        for group in rule["spec"]["groups"]
        for item in group["rules"]
    }
    exporter_records = {
        "after_sale_flow:otel_export_failures:rate5m",
        "after_sale_flow:otel_refused_items:rate5m",
    }
    for name, expression in records.items():
        if name in exporter_records:
            assert expression.count("or vector(0)") == 3
        else:
            assert "vector(0)" not in expression
    assert "by (service)" in records[
        "after_sale_flow:telemetry_source_age_seconds:max"
    ]


def test_alerts_cover_required_failure_modes_and_link_owned_runbook_sections() -> None:
    rule = _prometheus_rule("alerts.yaml")
    alerts = [item for group in rule["spec"]["groups"] for item in group["rules"]]
    runbook = RUNBOOK.read_text(encoding="utf-8")
    categories: set[str] = set()
    names: set[str] = set()
    for item in alerts:
        assert set(item) == {"alert", "expr", "for", "labels", "annotations"}
        assert item["alert"] not in names
        names.add(item["alert"])
        assert item["labels"]["owner"]
        assert item["labels"]["severity"] in {"warning", "critical"}
        categories.add(item["labels"]["category"])
        assert set(item["labels"]) <= SAFE_PROMETHEUS_LABELS
        assert not set(item["labels"]) & FORBIDDEN_LABEL_KEYS
        assert _prometheus_labels(item["expr"]) <= SAFE_PROMETHEUS_LABELS
        runbook_path = item["annotations"]["runbook_url"]
        assert runbook_path.startswith("docs/runbooks/temporal-first/phase-8-alert-response.md#")
        heading = f"### {item['alert']}"
        assert heading in runbook
    assert len(alerts) >= 14
    assert REQUIRED_ALERT_CATEGORIES <= categories

    alert_map = {item["alert"]: item for item in alerts}
    collector_service = next(
        item
        for item in _documents("otel-collector.yaml")
        if item["kind"] == "Service"
    )
    collector_service_name = collector_service["metadata"]["name"]
    assert alert_map["Phase8TelemetryCollectorMissing"]["expr"] == (
        f'absent(up{{service="{collector_service_name}"}} == 1)'
    )
    missing = alert_map["Phase8RequiredTelemetryMissing"]["expr"]
    assert missing.count("absent(") >= 10
    assert "label_replace" in missing and '"service"' in missing
    assert "vector(0)" not in missing
    stale = alert_map["Phase8RequiredTelemetryStale"]["expr"]
    assert stale == "after_sale_flow:telemetry_source_age_seconds:max > 120"


def test_eight_machine_lintable_dashboards_use_only_recorded_aggregate_series() -> None:
    paths = sorted((OBSERVABILITY / "dashboards").glob("*.json"))
    assert {item.name for item in paths} == DASHBOARD_NAMES
    uids: set[str] = set()
    titles: set[str] = set()
    for path in paths:
        dashboard = json.loads(path.read_text(encoding="utf-8"))
        assert dashboard["schemaVersion"] >= 39
        assert dashboard["editable"] is False
        assert {"phase8", "after-sale-flow", "render-only"} <= set(dashboard["tags"])
        assert dashboard["uid"] not in uids
        assert dashboard["title"] not in titles
        uids.add(dashboard["uid"])
        titles.add(dashboard["title"])
        assert len(dashboard["panels"]) >= 4
        assert dashboard["templating"]["list"] == []
        for panel in dashboard["panels"]:
            assert panel["title"] and panel["targets"]
            for target in panel["targets"]:
                assert "after_sale_flow:" in target["expr"]
                assert _prometheus_labels(target["expr"]) <= SAFE_PROMETHEUS_LABELS


def test_runbook_keeps_runtime_privacy_and_external_release_gaps_open() -> None:
    runbook = RUNBOOK.read_text(encoding="utf-8")
    for marker in (
        "artifact_scope: RENDER_ONLY_ENGINEERING",
        "production_checkpoint: PENDING_EXTERNAL",
        "python_runtime_otel_setup: RELEASE_BLOCKER_UNRESOLVED",
        "langfuse_metadata_and_payload_redaction: RELEASE_BLOCKER_UNRESOLVED",
        "Python runtime OpenTelemetry initialization and export are not implemented and tested",
        "Current Langfuse case/user/workflow metadata and raw prompt/output export redaction are not",
        "not closed by these static assets",
        "I3 owner controls the service account, RBAC, network, and mTLS policies",
    ):
        assert marker in runbook
