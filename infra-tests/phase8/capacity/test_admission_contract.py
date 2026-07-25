from __future__ import annotations

from copy import deepcopy
from pathlib import Path

import pytest
import yaml

from admission_contract import ContractViolation, load_capacity_contract, validate_capacity_contract


ROOT = Path(__file__).resolve().parents[3]
POLICY = ROOT / "deploy" / "production" / "phase8" / "capacity-policy.yaml"
SCENARIO = Path(__file__).with_name("scenario.yaml")


def _documents() -> tuple[dict, dict]:
    return (
        yaml.safe_load(POLICY.read_text(encoding="utf-8")),
        yaml.safe_load(SCENARIO.read_text(encoding="utf-8")),
    )


def test_contract_resolves_all_wave1_machine_identifiers() -> None:
    contract = load_capacity_contract(POLICY, SCENARIO)

    assert contract.normalized["workload_ids"] == [
        "java-agent-worker",
        "java-api",
        "java-control-worker",
        "litellm",
        "otel-collector",
        "python-agent",
    ]
    assert {item["queue_id"] for item in contract.normalized["queues"].values()} == {
        "case-control",
        "room-control",
        "agent-execution",
        "notification-and-tools",
    }
    assert contract.normalized["queues"]["room_control"]["admission_id"] == "room-command"
    assert contract.normalized["queues"]["agent_execution"]["admission_id"] == "agent-run"
    assert contract.normalized["model_admission"]["admission_id"] == "model-call"
    assert contract.normalized["sse"]["stream_id"] == "agent-stream-v2"


@pytest.mark.parametrize(
    "mutate",
    [
        lambda policy, scenario: scenario.update({"production_pass": True}),
        lambda policy, scenario: scenario["authority"].update({"production_pass": True}),
        lambda policy, scenario: scenario["execution"].update({"production_apply": "allowed"}),
        lambda policy, scenario: scenario["pool_models"]["domain_api_rw"].update({"observed": True}),
        lambda policy, scenario: policy.update({"status": "PASS"}),
        lambda policy, scenario: policy["metadata"]["annotations"].update({"production-pass": "true"}),
    ],
)
def test_contract_rejects_unknown_or_contradictory_fields(mutate) -> None:
    policy, scenario = _documents()
    mutate(policy, scenario)

    with pytest.raises(ContractViolation, match="keys are not exact"):
        validate_capacity_contract(policy, scenario)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda policy, scenario: scenario["authority"].pop("permits_soak_claim"),
        lambda policy, scenario: scenario["execution"].pop("network"),
        lambda policy, scenario: scenario["queue_models"]["room_control"]["arrivals"].pop("burst_per_second_ref"),
        lambda policy, scenario: scenario["sse_model"].pop("overload_behavior_ref"),
        lambda policy, scenario: policy["data"].pop("control-ids.yaml"),
    ],
)
def test_contract_rejects_partial_maps(mutate) -> None:
    policy, scenario = _documents()
    mutate(policy, scenario)

    with pytest.raises(ContractViolation):
        validate_capacity_contract(policy, scenario)


def test_contract_rejects_ambiguous_literal_and_reference() -> None:
    policy, scenario = _documents()
    arrivals = scenario["queue_models"]["room_control"]["arrivals"]
    arrivals["steady_per_second"] = 20

    with pytest.raises(ContractViolation, match="keys are not exact"):
        validate_capacity_contract(policy, scenario)


def test_contract_rejects_unknown_embedded_policy_field() -> None:
    policy, scenario = _documents()
    admissions = yaml.safe_load(policy["data"]["admission-ids.yaml"])
    admissions["model_calls"]["production_pass"] = True
    policy["data"]["admission-ids.yaml"] = yaml.safe_dump(admissions, sort_keys=False)

    with pytest.raises(ContractViolation, match="admission-ids.model_calls keys are not exact"):
        validate_capacity_contract(policy, scenario)


def test_contract_rejects_semantically_permuted_policy_references() -> None:
    policy, scenario = _documents()
    scenario["queue_models"]["room_control"]["admission_id_ref"] = "admission-ids.agent_runs.id"

    with pytest.raises(ContractViolation, match="room_control.admission_id_ref"):
        validate_capacity_contract(policy, scenario)


def test_contract_rejects_pool_sharing_and_measurement_authority() -> None:
    policy, scenario = _documents()
    unsafe_policy = deepcopy(policy)
    pools = yaml.safe_load(unsafe_policy["data"]["pool-ids.yaml"])
    pools["control_and_agent_pool_sharing"] = "allowed"
    unsafe_policy["data"]["pool-ids.yaml"] = yaml.safe_dump(pools, sort_keys=False)
    with pytest.raises(ContractViolation, match="control_and_agent_pool_sharing"):
        validate_capacity_contract(unsafe_policy, scenario)

    unsafe_scenario = deepcopy(scenario)
    unsafe_scenario["authority"]["observed_production"] = True
    with pytest.raises(ContractViolation, match="observed_production"):
        validate_capacity_contract(policy, unsafe_scenario)
