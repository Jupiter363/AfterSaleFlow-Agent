from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Any, Mapping

import yaml


POLICY_DATA_KEYS = {
    "failure-domains",
    "workload-ids",
    "pool-ids",
    "control-ids",
    "admission-ids",
    "sse-contract",
    "room-contract",
    "external-gates",
}
WORKLOAD_KEYS = {
    "java_api",
    "java_control_worker",
    "java_agent_worker",
    "python_agent",
    "litellm",
    "otel_collector",
}
POOL_KEYS = {
    "domain_api_rw",
    "domain_control_rw",
    "domain_agent_rw",
    "graph_agent_rw",
    "reporting_ro",
    "model_egress",
}
CONTROL_KEYS = {
    "case_control",
    "room_control",
    "agent_execution",
    "notification_and_tools",
}
QUEUE_KEYS = CONTROL_KEYS


class ContractViolation(ValueError):
    """Raised when the synthetic capacity inputs fail closed."""


@dataclass(frozen=True)
class CapacityContract:
    policy: dict[str, Any]
    scenario: dict[str, Any]
    normalized: dict[str, Any]
    policy_sha256: str
    scenario_sha256: str


def _fail(message: str) -> None:
    raise ContractViolation(message)


def _mapping(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        _fail(f"{name} must be a string-keyed mapping")
    return value


def _sequence(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        _fail(f"{name} must be a list")
    return value


def _string(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _fail(f"{name} must be a non-empty string")
    return value


def _integer(value: Any, name: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        _fail(f"{name} must be an integer >= {minimum}")
    return value


def _expect(value: Any, expected: Any, name: str) -> None:
    if value != expected:
        _fail(f"{name} must be {expected!r}, got {value!r}")


def _exact_keys(values: Mapping[str, Any], expected: set[str], name: str) -> None:
    actual = set(values)
    if actual != expected:
        missing = sorted(expected - actual)
        unknown = sorted(actual - expected)
        _fail(f"{name} keys are not exact; missing={missing}, unknown={unknown}")


def _unique_string_values(values: Mapping[str, Any], keys: set[str], name: str) -> dict[str, str]:
    _exact_keys(values, keys, name)
    result = {key: _string(values[key], f"{name}.{key}") for key in sorted(keys)}
    if len(set(result.values())) != len(result):
        _fail(f"{name} identifiers must be unique")
    return result


def _load_yaml_mapping(path: Path, name: str) -> tuple[dict[str, Any], str]:
    payload = path.read_bytes()
    try:
        value = yaml.safe_load(payload.decode("utf-8"))
    except (UnicodeDecodeError, yaml.YAMLError) as exc:
        raise ContractViolation(f"{name} is not valid UTF-8 YAML: {exc}") from exc
    return _mapping(value, name), sha256(payload).hexdigest()


def _embedded_policy(config: dict[str, Any]) -> dict[str, dict[str, Any]]:
    data = _mapping(config.get("data"), "policy.data")
    _exact_keys(
        data,
        {"contract-version", "engineering-mode"} | {f"{key}.yaml" for key in POLICY_DATA_KEYS},
        "policy.data",
    )

    result: dict[str, dict[str, Any]] = {}
    for key in sorted(POLICY_DATA_KEYS):
        raw = _string(data[f"{key}.yaml"], f"policy.data.{key}.yaml")
        try:
            result[key] = _mapping(yaml.safe_load(raw), f"policy.data.{key}.yaml")
        except yaml.YAMLError as exc:
            raise ContractViolation(f"policy.data.{key}.yaml is invalid YAML: {exc}") from exc
    return result


def _resolve_ref(embedded: Mapping[str, Any], ref: Any, name: str) -> Any:
    path = _string(ref, name).split(".")
    if len(path) < 2 or path[0] not in POLICY_DATA_KEYS:
        _fail(f"{name} is not an allowed Wave 1 policy reference")
    current: Any = embedded[path[0]]
    for part in path[1:]:
        if not isinstance(current, dict) or part not in current:
            _fail(f"{name} points to missing policy value {ref!r}")
        current = current[part]
    if isinstance(current, (dict, list)) or current is None:
        _fail(f"{name} must resolve to a scalar policy value")
    return current


def _resolved_int(
    values: Mapping[str, Any],
    field: str,
    embedded: Mapping[str, Any],
    name: str,
    *,
    minimum: int = 0,
) -> int:
    literal_present = field in values
    ref_field = f"{field}_ref"
    ref_present = ref_field in values
    if literal_present == ref_present:
        _fail(f"{name} must define exactly one of {field} or {ref_field}")
    value = values[field] if literal_present else _resolve_ref(embedded, values[ref_field], f"{name}.{ref_field}")
    return _integer(value, f"{name}.{field}", minimum=minimum)


def _validate_policy(config: dict[str, Any]) -> dict[str, dict[str, Any]]:
    _exact_keys(config, {"apiVersion", "kind", "metadata", "immutable", "data"}, "policy")
    _expect(config.get("apiVersion"), "v1", "policy.apiVersion")
    _expect(config.get("kind"), "ConfigMap", "policy.kind")
    _expect(config.get("immutable"), True, "policy.immutable")

    metadata = _mapping(config.get("metadata"), "policy.metadata")
    _exact_keys(metadata, {"name", "labels", "annotations"}, "policy.metadata")
    _expect(metadata.get("name"), "after-sale-phase8-capacity-policy", "policy.metadata.name")
    labels = _mapping(metadata.get("labels"), "policy.metadata.labels")
    _exact_keys(labels, {"app.kubernetes.io/name"}, "policy.metadata.labels")
    _expect(labels.get("app.kubernetes.io/name"), "phase8-capacity-policy", "policy label")
    annotations = _mapping(metadata.get("annotations"), "policy.metadata.annotations")
    _exact_keys(
        annotations,
        {
            "phase8.after-sale-flow.dev/evidence-class",
            "phase8.after-sale-flow.dev/real-load-proof",
            "phase8.after-sale-flow.dev/capacity-model",
        },
        "policy.metadata.annotations",
    )
    _expect(annotations.get("phase8.after-sale-flow.dev/evidence-class"), "target-only", "policy evidence class")
    _expect(annotations.get("phase8.after-sale-flow.dev/real-load-proof"), "absent", "policy real-load proof")
    _expect(annotations.get("phase8.after-sale-flow.dev/capacity-model"), "synthetic-only", "policy capacity model")

    data = _mapping(config.get("data"), "policy.data")
    _expect(data.get("contract-version"), "phase8.capacity.v1", "policy contract version")
    _expect(data.get("engineering-mode"), "RENDER_ONLY_NONDEPLOYABLE", "policy engineering mode")
    embedded = _embedded_policy(config)

    _unique_string_values(embedded["workload-ids"], WORKLOAD_KEYS, "workload-ids")
    failure_domains = embedded["failure-domains"]
    _exact_keys(failure_domains, {"count", "topology_key", "enforcement"}, "failure-domains")
    _expect(failure_domains.get("count"), 3, "failure-domains.count")
    _expect(failure_domains.get("topology_key"), "topology.kubernetes.io/zone", "failure-domains.topology_key")
    _expect(failure_domains.get("enforcement"), "REQUIRED_EXTERNAL", "failure-domains.enforcement")

    pool_control_keys = {
        "control_and_agent_pool_sharing",
        "graph_and_domain_pool_sharing",
        "reporting_primary_pool_sharing",
        "peak_utilization_percent_lt",
    }
    _exact_keys(embedded["pool-ids"], POOL_KEYS | pool_control_keys, "pool-ids")
    pool_ids = {key: _string(embedded["pool-ids"][key], f"pool-ids.{key}") for key in sorted(POOL_KEYS)}
    if len(set(pool_ids.values())) != len(pool_ids):
        _fail("pool-ids identifiers must be unique")
    _unique_string_values(embedded["control-ids"], CONTROL_KEYS, "control-ids")

    pools = embedded["pool-ids"]
    for key in (
        "control_and_agent_pool_sharing",
        "graph_and_domain_pool_sharing",
        "reporting_primary_pool_sharing",
    ):
        _expect(pools.get(key), "forbidden", f"pool-ids.{key}")
    _integer(pools.get("peak_utilization_percent_lt"), "pool-ids.peak_utilization_percent_lt", minimum=1)
    if pools["peak_utilization_percent_lt"] > 100:
        _fail("pool-ids.peak_utilization_percent_lt must be <= 100")

    admissions = embedded["admission-ids"]
    _exact_keys(admissions, {"room_commands", "agent_runs", "model_calls"}, "admission-ids")
    for key in ("room_commands", "agent_runs", "model_calls"):
        _mapping(admissions.get(key), f"admission-ids.{key}")
    _exact_keys(
        admissions["room_commands"],
        {"id", "steady_per_second", "burst_per_second", "burst_duration_seconds"},
        "admission-ids.room_commands",
    )
    _exact_keys(
        admissions["agent_runs"],
        {"id", "steady_per_second", "burst_per_second", "burst_duration_seconds", "burst_total", "queue_behavior"},
        "admission-ids.agent_runs",
    )
    _exact_keys(
        admissions["model_calls"],
        {"id", "sustained_concurrency", "burst_concurrency", "queue_behavior"},
        "admission-ids.model_calls",
    )
    admission_ids = {
        key: _string(admissions[key].get("id"), f"admission-ids.{key}.id")
        for key in ("room_commands", "agent_runs", "model_calls")
    }
    if len(set(admission_ids.values())) != 3:
        _fail("admission identifiers must be unique")
    for key in ("room_commands", "agent_runs"):
        item = admissions[key]
        steady = _integer(item.get("steady_per_second"), f"admission-ids.{key}.steady_per_second", minimum=1)
        burst = _integer(item.get("burst_per_second"), f"admission-ids.{key}.burst_per_second", minimum=1)
        if burst < steady:
            _fail(f"admission-ids.{key} burst must not be below steady")
        _integer(item.get("burst_duration_seconds"), f"admission-ids.{key}.burst_duration_seconds", minimum=1)
    _expect(admissions["agent_runs"].get("queue_behavior"), "bounded", "admission-ids.agent_runs.queue_behavior")
    _integer(admissions["agent_runs"].get("burst_total"), "admission-ids.agent_runs.burst_total", minimum=1)
    sustained = _integer(admissions["model_calls"].get("sustained_concurrency"), "model sustained concurrency", minimum=1)
    burst = _integer(admissions["model_calls"].get("burst_concurrency"), "model burst concurrency", minimum=1)
    if burst < sustained:
        _fail("model burst concurrency must not be below sustained concurrency")
    _expect(admissions["model_calls"].get("queue_behavior"), "bounded", "model queue behavior")

    sse = embedded["sse-contract"]
    _exact_keys(sse, {"id", "target_clients", "buffer", "overload_behavior"}, "sse-contract")
    _string(sse.get("id"), "sse-contract.id")
    _integer(sse.get("target_clients"), "sse-contract.target_clients", minimum=1)
    _expect(sse.get("buffer"), "bounded", "sse-contract.buffer")
    _expect(
        sse.get("overload_behavior"),
        "disconnect_and_replay_from_domain_db_cursor",
        "sse-contract.overload_behavior",
    )

    rooms = embedded["room-contract"]
    _exact_keys(
        rooms,
        {"target_active_rooms", "durable_timer_wait_percent_gte", "target_is_measurement_evidence"},
        "room-contract",
    )
    _integer(rooms.get("target_active_rooms"), "room-contract.target_active_rooms", minimum=1)
    wait_percent = _integer(
        rooms.get("durable_timer_wait_percent_gte"),
        "room-contract.durable_timer_wait_percent_gte",
        minimum=1,
    )
    if wait_percent > 100:
        _fail("room-contract.durable_timer_wait_percent_gte must be <= 100")
    _expect(rooms.get("target_is_measurement_evidence"), False, "room target evidence flag")

    external = embedded["external-gates"]
    _exact_keys(
        external,
        {
            "production_apply",
            "load_and_soak",
            "capacity_measurement",
            "hpa_metric_adapter",
            "three_domain_placement_receipt",
            "burst_recovery_receipt",
            "slo_receipt",
            "production_checkpoint",
            "promotion_gate",
        },
        "external-gates",
    )
    _expect(external.get("production_apply"), "forbidden", "external production apply")
    for key in (
        "load_and_soak",
        "capacity_measurement",
        "hpa_metric_adapter",
        "three_domain_placement_receipt",
        "burst_recovery_receipt",
        "slo_receipt",
    ):
        _expect(external.get(key), "REQUIRED_EXTERNAL", f"external-gates.{key}")
    _expect(external.get("production_checkpoint"), "PENDING_EXTERNAL", "external production checkpoint")
    _expect(external.get("promotion_gate"), "PENDING", "external promotion gate")
    return embedded


def _validate_scenario(scenario: dict[str, Any], embedded: dict[str, dict[str, Any]]) -> dict[str, Any]:
    _exact_keys(
        scenario,
        {
            "schema_version",
            "scenario_id",
            "classification",
            "policy_contract_version",
            "authority",
            "execution",
            "workload_registry_refs",
            "room_model",
            "queue_models",
            "model_admission",
            "sse_model",
            "pool_models",
        },
        "scenario",
    )
    _expect(scenario.get("schema_version"), "phase8.capacity.scenario.v1", "scenario schema version")
    _expect(scenario.get("classification"), "SYNTHETIC_MODEL_ONLY", "scenario classification")
    _expect(scenario.get("policy_contract_version"), "phase8.capacity.v1", "scenario policy version")
    scenario_id = _string(scenario.get("scenario_id"), "scenario id")

    authority = _mapping(scenario.get("authority"), "scenario.authority")
    expected_authority = {
        "engineering_mode": "RENDER_ONLY_NONDEPLOYABLE",
        "evidence_class": "TARGET_MODEL_NOT_MEASUREMENT",
        "production_checkpoint": "PENDING_EXTERNAL",
        "promotion_gate": "PENDING",
        "observed_production": False,
        "permits_production_pass_claim": False,
        "permits_slo_pass_claim": False,
        "permits_burst_recovery_claim": False,
        "permits_three_domain_claim": False,
        "permits_soak_claim": False,
    }
    _exact_keys(authority, set(expected_authority), "scenario.authority")
    for key, expected in expected_authority.items():
        _expect(authority.get(key), expected, f"scenario.authority.{key}")

    execution = _mapping(scenario.get("execution"), "scenario.execution")
    _exact_keys(
        execution,
        {
            "clock",
            "tick_seconds",
            "duration_seconds",
            "burst_start_second",
            "randomness",
            "subprocess",
            "network",
            "cloud",
            "database",
            "temporal",
            "production_credentials",
            "real_case_or_party_data",
        },
        "scenario.execution",
    )
    _expect(execution.get("clock"), "DETERMINISTIC_INTEGER_TICKS", "execution.clock")
    tick_seconds = _integer(execution.get("tick_seconds"), "execution.tick_seconds", minimum=1)
    _expect(tick_seconds, 1, "execution.tick_seconds")
    duration_seconds = _integer(execution.get("duration_seconds"), "execution.duration_seconds", minimum=1)
    burst_start = _integer(execution.get("burst_start_second"), "execution.burst_start_second")
    if burst_start >= duration_seconds:
        _fail("execution burst must start before the scenario ends")
    for key in (
        "randomness",
        "subprocess",
        "network",
        "cloud",
        "database",
        "temporal",
        "production_credentials",
        "real_case_or_party_data",
    ):
        _expect(execution.get(key), "forbidden", f"execution.{key}")

    workload_refs = _sequence(scenario.get("workload_registry_refs"), "workload_registry_refs")
    workload_ref_values = [
        _string(ref, f"workload_registry_refs[{index}]")
        for index, ref in enumerate(workload_refs)
    ]
    expected_workload_refs = {f"workload-ids.{key}" for key in WORKLOAD_KEYS}
    if set(workload_ref_values) != expected_workload_refs or len(workload_ref_values) != len(expected_workload_refs):
        _fail("workload_registry_refs must reference each Wave 1 workload path exactly once")
    workloads = [_string(_resolve_ref(embedded, ref, f"workload_registry_refs[{index}]"), "resolved workload") for index, ref in enumerate(workload_ref_values)]
    policy_workloads = set(_unique_string_values(embedded["workload-ids"], WORKLOAD_KEYS, "workload-ids").values())
    if len(workloads) != len(set(workloads)) or set(workloads) != policy_workloads:
        _fail("workload_registry_refs must resolve exactly all Wave 1 workload identifiers")

    room_model = _mapping(scenario.get("room_model"), "room_model")
    _exact_keys(
        room_model,
        {
            "target_rooms_ref",
            "minimum_wait_percent_ref",
            "measurement_evidence_ref",
            "durable_timer_waiting_rooms",
            "non_waiting_rooms",
        },
        "room_model",
    )
    _expect(room_model["target_rooms_ref"], "room-contract.target_active_rooms", "room_model.target_rooms_ref")
    _expect(
        room_model["minimum_wait_percent_ref"],
        "room-contract.durable_timer_wait_percent_gte",
        "room_model.minimum_wait_percent_ref",
    )
    _expect(
        room_model["measurement_evidence_ref"],
        "room-contract.target_is_measurement_evidence",
        "room_model.measurement_evidence_ref",
    )
    target_rooms = _integer(_resolve_ref(embedded, room_model.get("target_rooms_ref"), "room_model.target_rooms_ref"), "resolved target rooms", minimum=1)
    minimum_wait_percent = _integer(
        _resolve_ref(embedded, room_model.get("minimum_wait_percent_ref"), "room_model.minimum_wait_percent_ref"),
        "resolved waiting percent",
        minimum=1,
    )
    measurement_evidence = _resolve_ref(embedded, room_model.get("measurement_evidence_ref"), "room_model.measurement_evidence_ref")
    _expect(measurement_evidence, False, "room model measurement authority")
    waiting_rooms = _integer(room_model.get("durable_timer_waiting_rooms"), "room_model.durable_timer_waiting_rooms")
    non_waiting_rooms = _integer(room_model.get("non_waiting_rooms"), "room_model.non_waiting_rooms")
    if waiting_rooms + non_waiting_rooms != target_rooms:
        _fail("room model counts must sum to the policy target")
    if waiting_rooms * 100 < target_rooms * minimum_wait_percent:
        _fail("room model durable Timer waiting ratio is below the policy minimum")

    queue_models = _mapping(scenario.get("queue_models"), "queue_models")
    if set(queue_models) != QUEUE_KEYS:
        _fail(f"queue_models must define exactly {sorted(QUEUE_KEYS)}")
    queues: dict[str, Any] = {}
    resolved_control_ids: set[str] = set()
    expected_queue_routes = {
        "case_control": {
            "control_id_ref": "control-ids.case_control",
            "workload_id_refs": ["workload-ids.java_control_worker"],
            "pool_id_ref": "pool-ids.domain_control_rw",
            "admission_id_ref": None,
        },
        "room_control": {
            "control_id_ref": "control-ids.room_control",
            "workload_id_refs": ["workload-ids.java_api", "workload-ids.java_control_worker"],
            "pool_id_ref": "pool-ids.domain_control_rw",
            "admission_id_ref": "admission-ids.room_commands.id",
        },
        "agent_execution": {
            "control_id_ref": "control-ids.agent_execution",
            "workload_id_refs": ["workload-ids.java_agent_worker", "workload-ids.python_agent"],
            "pool_id_ref": "pool-ids.domain_agent_rw",
            "admission_id_ref": "admission-ids.agent_runs.id",
        },
        "notification_and_tools": {
            "control_id_ref": "control-ids.notification_and_tools",
            "workload_id_refs": ["workload-ids.java_api"],
            "pool_id_ref": "pool-ids.domain_api_rw",
            "admission_id_ref": None,
        },
    }
    for queue_key in sorted(QUEUE_KEYS):
        item = _mapping(queue_models[queue_key], f"queue_models.{queue_key}")
        queue_item_keys = {
            "control_id_ref",
            "workload_id_refs",
            "pool_id_ref",
            "queue_limit",
            "service_per_second",
            "arrivals",
        }
        if queue_key in {"room_control", "agent_execution"}:
            queue_item_keys.add("admission_id_ref")
        _exact_keys(item, queue_item_keys, f"queue_models.{queue_key}")
        expected_route = expected_queue_routes[queue_key]
        for ref_key in ("control_id_ref", "workload_id_refs", "pool_id_ref"):
            _expect(item[ref_key], expected_route[ref_key], f"queue_models.{queue_key}.{ref_key}")
        if expected_route["admission_id_ref"] is not None:
            _expect(
                item["admission_id_ref"],
                expected_route["admission_id_ref"],
                f"queue_models.{queue_key}.admission_id_ref",
            )
        control_id = _string(
            _resolve_ref(embedded, item.get("control_id_ref"), f"queue_models.{queue_key}.control_id_ref"),
            f"queue_models.{queue_key}.control_id",
        )
        if control_id in resolved_control_ids:
            _fail("each control route must have an isolated queue identifier")
        resolved_control_ids.add(control_id)

        queue_workload_refs = _sequence(item.get("workload_id_refs"), f"queue_models.{queue_key}.workload_id_refs")
        queue_workloads = [
            _string(_resolve_ref(embedded, ref, f"queue_models.{queue_key}.workload_id_refs[{index}]"), "resolved workload id")
            for index, ref in enumerate(queue_workload_refs)
        ]
        if not queue_workloads or len(queue_workloads) != len(set(queue_workloads)):
            _fail(f"queue_models.{queue_key}.workload_id_refs must be non-empty and unique")
        pool_id = _string(
            _resolve_ref(embedded, item.get("pool_id_ref"), f"queue_models.{queue_key}.pool_id_ref"),
            f"queue_models.{queue_key}.pool_id",
        )
        admission_id = None
        if queue_key in {"room_control", "agent_execution"}:
            admission_id = _string(
                _resolve_ref(embedded, item.get("admission_id_ref"), f"queue_models.{queue_key}.admission_id_ref"),
                f"queue_models.{queue_key}.admission_id",
            )
        elif "admission_id_ref" in item:
            _fail(f"queue_models.{queue_key} must not bind an admission policy")

        arrivals = _mapping(item.get("arrivals"), f"queue_models.{queue_key}.arrivals")
        allowed_arrival_keys = {
            field if field in arrivals else f"{field}_ref"
            for field in (
                "steady_per_second",
                "burst_per_second",
                "burst_duration_seconds",
                "pulse_at_burst_start",
            )
        }
        _exact_keys(arrivals, allowed_arrival_keys, f"queue_models.{queue_key}.arrivals")
        if queue_key == "room_control":
            expected_arrival_refs = {
                "steady_per_second_ref": "admission-ids.room_commands.steady_per_second",
                "burst_per_second_ref": "admission-ids.room_commands.burst_per_second",
                "burst_duration_seconds_ref": "admission-ids.room_commands.burst_duration_seconds",
            }
            for ref_key, expected_ref in expected_arrival_refs.items():
                _expect(arrivals.get(ref_key), expected_ref, f"queue_models.{queue_key}.arrivals.{ref_key}")
        elif queue_key == "agent_execution":
            expected_arrival_refs = {
                "steady_per_second_ref": "admission-ids.agent_runs.steady_per_second",
                "burst_per_second_ref": "admission-ids.agent_runs.burst_per_second",
                "burst_duration_seconds_ref": "admission-ids.agent_runs.burst_duration_seconds",
                "pulse_at_burst_start_ref": "admission-ids.agent_runs.burst_total",
            }
            for ref_key, expected_ref in expected_arrival_refs.items():
                _expect(arrivals.get(ref_key), expected_ref, f"queue_models.{queue_key}.arrivals.{ref_key}")
        normalized_arrivals = {
            field: _resolved_int(arrivals, field, embedded, f"queue_models.{queue_key}.arrivals")
            for field in (
                "steady_per_second",
                "burst_per_second",
                "burst_duration_seconds",
                "pulse_at_burst_start",
            )
        }
        if normalized_arrivals["burst_per_second"] < normalized_arrivals["steady_per_second"]:
            _fail(f"queue_models.{queue_key} burst arrivals must not be below steady")
        if burst_start + normalized_arrivals["burst_duration_seconds"] > duration_seconds:
            _fail(f"queue_models.{queue_key} burst must fit in the deterministic window")
        queues[queue_key] = {
            "queue_id": control_id,
            "control_id": control_id,
            "admission_id": admission_id,
            "workload_ids": queue_workloads,
            "pool_id": pool_id,
            "queue_limit": _integer(item.get("queue_limit"), f"queue_models.{queue_key}.queue_limit", minimum=1),
            "service_per_second": _integer(item.get("service_per_second"), f"queue_models.{queue_key}.service_per_second", minimum=1),
            "arrivals": normalized_arrivals,
        }

    control_values = set(_unique_string_values(embedded["control-ids"], CONTROL_KEYS, "control-ids").values())
    if resolved_control_ids != control_values:
        _fail("queue models must consume exactly all Wave 1 control identifiers")
    if queues["agent_execution"]["pool_id"] in {
        queues["case_control"]["pool_id"],
        queues["room_control"]["pool_id"],
    }:
        _fail("agent execution pool must be isolated from case and room control")

    model = _mapping(scenario.get("model_admission"), "model_admission")
    _exact_keys(
        model,
        {
            "admission_id_ref",
            "workload_id_refs",
            "pool_id_ref",
            "sustained_concurrency_ref",
            "burst_concurrency_ref",
            "active_limit",
            "queue_limit",
            "overload_probe_concurrency",
        },
        "model_admission",
    )
    expected_model_refs = {
        "admission_id_ref": "admission-ids.model_calls.id",
        "workload_id_refs": ["workload-ids.python_agent", "workload-ids.litellm"],
        "pool_id_ref": "pool-ids.model_egress",
        "sustained_concurrency_ref": "admission-ids.model_calls.sustained_concurrency",
        "burst_concurrency_ref": "admission-ids.model_calls.burst_concurrency",
    }
    for ref_key, expected_ref in expected_model_refs.items():
        _expect(model[ref_key], expected_ref, f"model_admission.{ref_key}")
    model_workload_refs = _sequence(model.get("workload_id_refs"), "model_admission.workload_id_refs")
    model_workloads = [
        _string(_resolve_ref(embedded, ref, f"model_admission.workload_id_refs[{index}]"), "resolved model workload")
        for index, ref in enumerate(model_workload_refs)
    ]
    sustained_concurrency = _integer(
        _resolve_ref(embedded, model.get("sustained_concurrency_ref"), "model_admission.sustained_concurrency_ref"),
        "resolved model sustained concurrency",
        minimum=1,
    )
    burst_concurrency = _integer(
        _resolve_ref(embedded, model.get("burst_concurrency_ref"), "model_admission.burst_concurrency_ref"),
        "resolved model burst concurrency",
        minimum=1,
    )
    active_limit = _integer(model.get("active_limit"), "model_admission.active_limit", minimum=1)
    model_queue_limit = _integer(model.get("queue_limit"), "model_admission.queue_limit", minimum=1)
    overload_probe = _integer(model.get("overload_probe_concurrency"), "model_admission.overload_probe_concurrency", minimum=1)
    if sustained_concurrency > active_limit:
        _fail("model active limit must accommodate sustained concurrency")
    if burst_concurrency > active_limit + model_queue_limit:
        _fail("model active plus queue limits must accommodate the policy burst target")
    if overload_probe <= active_limit + model_queue_limit:
        _fail("model overload probe must exceed the bounded admission capacity")
    normalized_model = {
        "admission_id": _string(_resolve_ref(embedded, model.get("admission_id_ref"), "model_admission.admission_id_ref"), "resolved model admission id"),
        "workload_ids": model_workloads,
        "pool_id": _string(_resolve_ref(embedded, model.get("pool_id_ref"), "model_admission.pool_id_ref"), "resolved model pool id"),
        "sustained_concurrency": sustained_concurrency,
        "burst_concurrency": burst_concurrency,
        "active_limit": active_limit,
        "queue_limit": model_queue_limit,
        "overload_probe_concurrency": overload_probe,
    }

    sse = _mapping(scenario.get("sse_model"), "sse_model")
    _exact_keys(
        sse,
        {
            "stream_id_ref",
            "target_connections_ref",
            "buffer_behavior_ref",
            "overload_behavior_ref",
            "buffer_limit_per_connection",
            "baseline_buffered_events_per_connection",
            "overload_events_per_connection",
        },
        "sse_model",
    )
    expected_sse_refs = {
        "stream_id_ref": "sse-contract.id",
        "target_connections_ref": "sse-contract.target_clients",
        "buffer_behavior_ref": "sse-contract.buffer",
        "overload_behavior_ref": "sse-contract.overload_behavior",
    }
    for ref_key, expected_ref in expected_sse_refs.items():
        _expect(sse[ref_key], expected_ref, f"sse_model.{ref_key}")
    buffer_limit = _integer(sse.get("buffer_limit_per_connection"), "sse_model.buffer_limit_per_connection", minimum=1)
    baseline_buffer = _integer(sse.get("baseline_buffered_events_per_connection"), "sse_model.baseline_buffered_events_per_connection")
    overload_events = _integer(sse.get("overload_events_per_connection"), "sse_model.overload_events_per_connection", minimum=1)
    if baseline_buffer > buffer_limit:
        _fail("SSE baseline buffer must fit its bound")
    if overload_events <= buffer_limit:
        _fail("SSE overload probe must exceed the buffer bound")
    normalized_sse = {
        "stream_id": _string(_resolve_ref(embedded, sse.get("stream_id_ref"), "sse_model.stream_id_ref"), "resolved SSE id"),
        "target_connections": _integer(_resolve_ref(embedded, sse.get("target_connections_ref"), "sse_model.target_connections_ref"), "resolved SSE target", minimum=1),
        "buffer_behavior": _string(_resolve_ref(embedded, sse.get("buffer_behavior_ref"), "sse_model.buffer_behavior_ref"), "resolved SSE buffer behavior"),
        "overload_behavior": _string(_resolve_ref(embedded, sse.get("overload_behavior_ref"), "sse_model.overload_behavior_ref"), "resolved SSE overload behavior"),
        "buffer_limit_per_connection": buffer_limit,
        "baseline_buffered_events_per_connection": baseline_buffer,
        "overload_events_per_connection": overload_events,
    }

    pool_models = _mapping(scenario.get("pool_models"), "pool_models")
    if set(pool_models) != POOL_KEYS:
        _fail(f"pool_models must define exactly {sorted(POOL_KEYS)}")
    pool_threshold = _integer(embedded["pool-ids"]["peak_utilization_percent_lt"], "pool threshold", minimum=1)
    normalized_pools: dict[str, Any] = {}
    resolved_pool_ids: set[str] = set()
    for pool_key in sorted(POOL_KEYS):
        item = _mapping(pool_models[pool_key], f"pool_models.{pool_key}")
        _exact_keys(
            item,
            {"pool_id_ref", "capacity_units", "peak_demand_units"},
            f"pool_models.{pool_key}",
        )
        _expect(item["pool_id_ref"], f"pool-ids.{pool_key}", f"pool_models.{pool_key}.pool_id_ref")
        pool_id = _string(_resolve_ref(embedded, item.get("pool_id_ref"), f"pool_models.{pool_key}.pool_id_ref"), "resolved pool id")
        capacity = _integer(item.get("capacity_units"), f"pool_models.{pool_key}.capacity_units", minimum=1)
        peak = _integer(item.get("peak_demand_units"), f"pool_models.{pool_key}.peak_demand_units", minimum=1)
        if peak * 100 >= capacity * pool_threshold:
            _fail(f"pool_models.{pool_key} does not preserve strict target headroom")
        resolved_pool_ids.add(pool_id)
        normalized_pools[pool_key] = {
            "pool_id": pool_id,
            "capacity_units": capacity,
            "peak_demand_units": peak,
            "utilization_basis_points": peak * 10_000 // capacity,
            "threshold_percent_lt": pool_threshold,
        }
    policy_pool_values = {embedded["pool-ids"][key] for key in POOL_KEYS}
    if resolved_pool_ids != policy_pool_values:
        _fail("pool models must consume exactly all Wave 1 pool identifiers")

    return {
        "scenario_id": scenario_id,
        "classification": "SYNTHETIC_MODEL_ONLY",
        "authority": expected_authority,
        "execution": {
            "tick_seconds": tick_seconds,
            "duration_seconds": duration_seconds,
            "burst_start_second": burst_start,
        },
        "workload_ids": sorted(workloads),
        "rooms": {
            "target_rooms": target_rooms,
            "durable_timer_waiting_rooms": waiting_rooms,
            "non_waiting_rooms": non_waiting_rooms,
            "minimum_wait_percent": minimum_wait_percent,
            "target_is_measurement_evidence": False,
        },
        "queues": queues,
        "model_admission": normalized_model,
        "sse": normalized_sse,
        "pools": normalized_pools,
    }


def validate_capacity_contract(
    policy: dict[str, Any],
    scenario: dict[str, Any],
    *,
    policy_sha256: str = "",
    scenario_sha256: str = "",
) -> CapacityContract:
    embedded = _validate_policy(policy)
    normalized = _validate_scenario(scenario, embedded)
    return CapacityContract(
        policy=policy,
        scenario=scenario,
        normalized=normalized,
        policy_sha256=policy_sha256,
        scenario_sha256=scenario_sha256,
    )


def load_capacity_contract(policy_path: Path | str, scenario_path: Path | str) -> CapacityContract:
    policy, policy_hash = _load_yaml_mapping(Path(policy_path), "capacity policy")
    scenario, scenario_hash = _load_yaml_mapping(Path(scenario_path), "capacity scenario")
    return validate_capacity_contract(
        policy,
        scenario,
        policy_sha256=policy_hash,
        scenario_sha256=scenario_hash,
    )
