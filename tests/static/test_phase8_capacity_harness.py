from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
CAPACITY = ROOT / "tests" / "infrastructure" / "capacity"
POLICY = ROOT / "infra" / "kubernetes" / "production" / "capacity-policy.yaml"
SCENARIO = CAPACITY / "scenario.yaml"
sys.path.insert(0, str(CAPACITY))

from admission_contract import ContractViolation, load_capacity_contract, validate_capacity_contract  # noqa: E402
from run_capacity_model import build_capacity_report, render_report  # noqa: E402


def _documents() -> tuple[dict, dict]:
    return (
        yaml.safe_load(POLICY.read_text(encoding="utf-8")),
        yaml.safe_load(SCENARIO.read_text(encoding="utf-8")),
    )


def test_capacity_report_is_deterministic_and_has_no_external_authority() -> None:
    contract = load_capacity_contract(POLICY, SCENARIO)
    first = build_capacity_report(contract)
    second = build_capacity_report(contract)

    assert render_report(first) == render_report(second)
    assert first["classification"] == "SYNTHETIC_MODEL_ONLY"
    assert first["outcome"] == "SYNTHETIC_INVARIANTS_HOLD"
    assert first["authority"]["engineering_mode"] == "RENDER_ONLY_NONDEPLOYABLE"
    assert first["authority"]["production_checkpoint"] == "PENDING_EXTERNAL"
    assert first["authority"]["promotion_gate"] == "PENDING"
    assert not first["authority"]["observed_production"]
    assert not first["authority"]["permits_production_pass_claim"]
    assert not first["authority"]["permits_slo_pass_claim"]
    assert not first["authority"]["permits_burst_recovery_claim"]
    assert not first["authority"]["permits_three_domain_claim"]
    assert not first["authority"]["permits_soak_claim"]
    assert all(first["invariants"].values())


def test_capacity_model_covers_room_command_agent_model_sse_and_backpressure() -> None:
    report = build_capacity_report(load_capacity_contract(POLICY, SCENARIO))

    assert report["rooms"] == {
        "target_rooms": 1000,
        "durable_timer_waiting_rooms": 700,
        "non_waiting_rooms": 300,
        "minimum_wait_percent": 70,
        "target_is_measurement_evidence": False,
        "durable_timer_waiting_ratio_basis_points": 7000,
        "minimum_wait_ratio_basis_points": 7000,
        "ratio_satisfied": True,
    }
    assert report["queues"]["room_control"]["peak_depth"] == 300
    assert report["queues"]["room_control"]["target_profile_rejected"] == 0
    assert report["queues"]["agent_execution"]["peak_depth"] == 246
    assert report["queues"]["agent_execution"]["target_profile_rejected"] == 0
    assert all(
        queue["overload_probe"]["rejected"] == 1
        for queue in report["queues"].values()
    )
    assert report["model_admission"]["sustained"] == {
        "offered": 100,
        "active": 100,
        "queued": 0,
        "rejected": 0,
    }
    assert report["model_admission"]["burst"] == {
        "offered": 200,
        "active": 160,
        "queued": 40,
        "rejected": 0,
    }
    assert report["model_admission"]["overload_probe"]["rejected"] == 1
    assert report["sse"]["target_connections"] == 2500
    assert report["sse"]["bounded_total_buffer_slots"] == 160_000
    assert report["sse"]["overload_disconnects"] == 2500
    assert report["sse"]["domain_db_cursor_replay_requests"] == 2500


def test_control_routes_and_pool_targets_remain_isolated_and_bounded() -> None:
    report = build_capacity_report(load_capacity_contract(POLICY, SCENARIO))

    assert report["control_isolation"]["isolated_queue_count"] == 4
    assert report["control_isolation"]["agent_execution_pool_isolated_from_case_and_room_control"]
    assert report["control_isolation"]["agent_burst_does_not_consume_case_or_room_queue_bounds"]
    assert set(report["pools"]) == {
        "domain-api-rw",
        "domain-control-rw",
        "domain-agent-rw",
        "graph-agent-rw",
        "reporting-ro",
        "model-egress",
    }
    assert all(
        pool["headroom_preserved"]
        and pool["utilization_basis_points"] < pool["threshold_basis_points_lt"]
        for pool in report["pools"].values()
    )


@pytest.mark.parametrize(
    ("scope", "field", "value"),
    [
        ("top", "production_pass", True),
        ("authority", "production_pass", True),
        ("execution", "production_apply", "allowed"),
        ("sse_model", "observed_reconnect_p95_ms", 100),
    ],
)
def test_closed_world_schema_rejects_unknown_claims(scope: str, field: str, value: object) -> None:
    policy, scenario = _documents()
    target = scenario if scope == "top" else scenario[scope]
    target[field] = value

    with pytest.raises(ContractViolation, match="keys are not exact"):
        validate_capacity_contract(policy, scenario)


def test_closed_world_schema_rejects_partial_nested_and_embedded_policy_maps() -> None:
    policy, scenario = _documents()
    partial_scenario = deepcopy(scenario)
    partial_scenario["model_admission"].pop("queue_limit")
    with pytest.raises(ContractViolation, match="model_admission keys are not exact"):
        validate_capacity_contract(policy, partial_scenario)

    partial_policy = deepcopy(policy)
    controls = yaml.safe_load(partial_policy["data"]["control-ids.yaml"])
    controls.pop("case_control")
    partial_policy["data"]["control-ids.yaml"] = yaml.safe_dump(controls, sort_keys=False)
    with pytest.raises(ContractViolation, match="control-ids keys are not exact"):
        validate_capacity_contract(partial_policy, scenario)


def test_model_module_contains_no_external_execution_primitives() -> None:
    sources = "\n".join(
        (CAPACITY / name).read_text(encoding="utf-8")
        for name in ("admission_contract.py", "run_capacity_model.py")
    )
    forbidden = (
        "import requests",
        "import socket",
        "import subprocess",
        "urllib.request",
        "psycopg",
        "temporalio.client",
        "os.system",
    )
    assert not any(token in sources for token in forbidden)
