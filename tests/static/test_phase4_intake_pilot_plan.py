from __future__ import annotations

import copy
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

import jsonschema
import rfc8785
import yaml


ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "plans/phase-4-intake-pilot-execution.md"
MATRIX = ROOT / "plans/phase-4-intake-pilot-test-batches.yaml"
CONTRACT = ROOT / "docs/runbooks/temporal-first/phase-4-p4.0-contract-pack.md"
ADR = ROOT / "docs/architecture/adr/0011-phase-4-intake-engineering-exception.md"
CHECKLIST = ROOT / "docs/acceptance/temporal-first-agent-platform-verification-checklist.md"
BASELINE = ROOT / "docs/acceptance/current-room-function-baseline.md"
BASELINE_INVENTORY = (
    ROOT / "docs/runbooks/temporal-first/phase-4-p4.0-baseline-inventory.md"
)
CONTRACT_ROOT = ROOT / "contracts/agent-platform/intake/v2"

SCHEMA_BY_FIXTURE = {
    "room-epoch-selection": ("room-epoch-selection.schema.json", "selection_hash"),
    "graph-private-thread-registration": (
        "graph-private-thread-registration.schema.json",
        "registration_hash",
    ),
    "intake-domain-snapshot": ("intake-domain-snapshot.schema.json", "snapshot_hash"),
    "intake-turn-event": ("intake-turn-event.schema.json", "event_hash"),
    "intake-turn-proposal": ("intake-turn-proposal.schema.json", "proposal_hash"),
    "intake-finalization-receipt": (
        "intake-finalization-receipt.schema.json",
        "receipt_hash",
    ),
}


def load_matrix() -> dict:
    return yaml.safe_load(MATRIX.read_text(encoding="utf-8"))


def expected_baseline_ids() -> set[str]:
    return {
        *(f"INT-{number:03d}" for number in range(1, 11)),
        "OVR-003",
        *(f"CORE-{number:03d}" for number in range(4, 11)),
        *(f"SEC-{number:03d}" for number in range(1, 7)),
        "UI-001",
        "UI-003",
        "UI-004",
    }


def expected_check_ids() -> set[str]:
    return {
        *(f"ROOM-INTAKE-{number:03d}" for number in range(1, 5)),
        "GRAPH-007",
        "GRAPH-008",
        "GRAPH-020",
        "GRAPH-021",
        "GRAPH-022",
        *(f"TEMP-{number:03d}" for number in range(20, 24)),
        *(f"JAVA-{number:03d}" for number in range(7, 12)),
        "MIG-004",
    }


def test_phase4_gate_is_engineering_only_and_uses_two_commit_evidence() -> None:
    matrix = load_matrix()
    gate = matrix["gate"]

    assert gate["entry_decision"] == "ENGINEERING_ONLY"
    assert gate["runtime_modes_allowed"] == ["DISABLED", "SIGNED_SYNTHETIC_SHADOW"]
    assert gate["observed_entry_state"] == {
        "engineering_checkpoint": "PASS",
        "promotion_gate": "PENDING",
        "next_phase_permission": "PHASE_4_ENGINEERING_ONLY",
        "MIG-003": "PENDING_PROMOTION",
    }
    assert gate["traffic_constraints"] == {
        "formal_room_writer_allowed": False,
        "temporal_intake_writer_allowed": False,
        "real_case_shadow_allowed": False,
        "production_traffic_allowed": False,
        "canary_allowed": False,
        "promotion_allowed": False,
        "synthetic_fixtures_only": True,
    }
    assert {
        "P4_0_CONTRACT_GATE_COMMITTED",
        "P4_0_BATCH_0_PASSED_ON_EXACT_CONTRACT_CANDIDATE_SHA",
        "P4_0_ENTRY_EVIDENCE_COMMITTED",
    } <= set(gate["implementation_allowed_when"])

    plan_text = PLAN.read_text(encoding="utf-8")
    contract_text = CONTRACT.read_text(encoding="utf-8")
    for required in (
        "BLOCKED_UNTIL_P4_0_ENTRY_EVIDENCE_IS_COMMITTED",
        "clean detached worktree",
        "entry-evidence commit",
        "formal_intake_writer: FORBIDDEN_UNDER_CURRENT_GATE",
    ):
        assert required in plan_text
    assert "P4.0 uses two commits" in contract_text
    assert "real_case_shadow: FORBIDDEN" in contract_text


def test_phase4_uses_one_primary_and_five_unique_implementation_owners() -> None:
    matrix = load_matrix()
    resources = matrix["resources"]
    assert resources["active_primary_agents"] == 1
    assert resources["logical_child_owners"] == 5
    assert resources["max_active_child_agents"] == 3
    assert resources["max_active_agents_total"] == 4
    assert resources["execution_waves"] == 2

    agents = matrix["agents"]
    assert set(agents) == {"A", "B", "C", "D", "E", "R"}
    primary_ids = [
        check_id
        for owner, agent in agents.items()
        if owner != "R"
        for check_id in agent["primary_check_ids"]
    ]
    assert set(primary_ids) == expected_check_ids()
    assert not [item for item, count in Counter(primary_ids).items() if count != 1]

    tasks = [task for agent in agents.values() for task in agent["tasks"]]
    assert set(tasks) == set(matrix["task_contracts"])
    assert not [item for item, count in Counter(tasks).items() if count != 1]
    for task_id, contract in matrix["task_contracts"].items():
        assert task_id in agents[contract["owner"]]["tasks"]
        assert contract["objective"]
        assert contract["outputs"]
        assert contract["definition_of_done"]

    delegated_routes = [
        route
        for owner, agent in agents.items()
        if owner != "R"
        for route in agent["change_routes"]
    ]
    assert not [item for item, count in Counter(delegated_routes).items() if count != 1]


def test_phase4_baselines_and_check_ids_match_normative_documents() -> None:
    matrix = load_matrix()
    baseline_ids = {
        item
        for group in matrix["baseline_ids"].values()
        for item in group
    }
    check_ids = {
        item
        for group in matrix["check_ids"].values()
        for item in group
    }
    assert baseline_ids == expected_baseline_ids()
    assert check_ids == expected_check_ids()
    assert set(matrix["batches"]["P4-BATCH-0"]["coverage_required"]["baseline_ids"]) == expected_baseline_ids()

    baseline_text = BASELINE.read_text(encoding="utf-8")
    checklist_text = CHECKLIST.read_text(encoding="utf-8")
    assert all(f"[{item}]" in baseline_text for item in baseline_ids)
    assert all(item in checklist_text for item in check_ids)

    inventory_text = BASELINE_INVENTORY.read_text(encoding="utf-8")
    assert "baseline_result: NOT_RUN" in inventory_text
    assert "implementation_gate: BLOCKED" in inventory_text
    assert all(item in inventory_text for item in baseline_ids)

    policy = matrix["claim_status_policy"]
    assert set(policy["PARTIAL_ENGINEERING"]) == {"TEMP-020", "TEMP-023"}
    assert set(policy["PENDING_PROMOTION"]) == {"MIG-004"}


def test_phase4_batch_policy_defers_heavy_recovery_and_freezes_one_candidate() -> None:
    batches = load_matrix()["batches"]
    batch_1 = batches["P4-BATCH-1"]
    assert batch_1["execution"]["database"] == "none"
    assert {
        "PostgreSQL checkpoint persistence",
        "four Graph crash boundaries",
        "cross-process cached terminal recovery",
        "Java transactional Finalizer recovery",
    } == set(batch_1["execution"]["claims_deferred_to_P4_BATCH_2"])

    candidate = batches["P4-BATCH-3"]
    assert candidate["candidate_policy"]["accepted_candidate_count"] == 1
    assert candidate["candidate_policy"]["candidate_sha_immutable_during_run"] is True
    assert candidate["candidate_policy"]["mixed_candidate_results_forbidden"] is True
    assert candidate["execution"]["heavy_parallelism"] == 1
    assert candidate["execution"]["database_workers"] == 1
    assert batches["P4-UNIFIED-CHECKPOINT"]["automatic"] is False

    entry_sources = {
        item["id"]: item
        for item in batches["P4-BATCH-0"]["source_commands"]
    }
    entry_commands = {
        source_id: source["command"]
        for source_id, source in entry_sources.items()
    }
    assert set(entry_commands) == {
        "p4_entry_static",
        "p4_entry_python",
        "p4_entry_java",
        "p4_entry_frontend",
    }
    for required in (
        "test_phase4_intake_pilot_plan.py",
        "test_phase4_matrix_authority_erratum.py",
        "test_intake_turn.py",
        "test_streaming_v2.py",
        "test_graph_security_runtime.py",
        "test_runnable_factory.py",
        "IntakeRoomServiceIntegrationTest",
        "AgentConversationSessionResolverTest",
        "RoomTurnMemoryQueryServiceTest",
        "AgentRunStreamEventServiceTest",
        "RoomShell.test.js",
        "agentStream.test.js",
    ):
        assert any(required in command for command in entry_commands.values())

    expected_frontend_command = " ".join(
        (
            "node node_modules/vitest/vitest.mjs run",
            "src/views/disputes/IntakeRoomView.test.js",
            "src/views/disputes/DisputeOverviewView.test.js",
            "src/components/room/RoomShell.test.js",
            "src/api/agentStream.test.js",
            "src/stores/agentStream.test.js",
            "src/stores/room.test.js",
            "--minWorkers=1",
            "--maxWorkers=2",
        )
    )
    assert entry_commands["p4_entry_frontend"] == expected_frontend_command
    assert entry_sources["p4_entry_frontend"]["cwd"] == "frontend"

    candidate_sources = {
        item["id"]: item
        for item in candidate["source_commands"]
        if "command" in item
    }
    candidate_commands = {
        source_id: source["command"]
        for source_id, source in candidate_sources.items()
    }
    assert candidate_commands["frontend_phase_4"] == expected_frontend_command
    assert candidate_sources["frontend_phase_4"]["cwd"] == "frontend"


def test_phase4_schemas_validate_positive_and_reject_negative_fixtures() -> None:
    valid_root = CONTRACT_ROOT / "fixtures/valid"
    invalid_root = CONTRACT_ROOT / "fixtures/invalid"

    for prefix, (schema_name, _) in SCHEMA_BY_FIXTURE.items():
        schema = json.loads((CONTRACT_ROOT / schema_name).read_text(encoding="utf-8"))
        jsonschema.Draft202012Validator.check_schema(schema)
        validator = jsonschema.Draft202012Validator(
            schema,
            format_checker=jsonschema.Draft202012Validator.FORMAT_CHECKER,
        )
        valid = json.loads(
            next(valid_root.glob(f"{prefix}-valid.json")).read_text(encoding="utf-8")
        )
        validator.validate(valid)
        for invalid_path in invalid_root.glob(f"{prefix}-*.json"):
            invalid = json.loads(invalid_path.read_text(encoding="utf-8"))
            assert list(validator.iter_errors(invalid)), invalid_path.name


def test_phase4_snapshot_and_proposal_reject_forbidden_keys_at_any_depth() -> None:
    valid_root = CONTRACT_ROOT / "fixtures/valid"
    cases = [
        (
            "intake-domain-snapshot.schema.json",
            "intake-domain-snapshot-valid.json",
            lambda value: value["current_dossier"].update(
                {"nested": {"memory_frame": {"secret": True}}}
            ),
        ),
        (
            "intake-turn-proposal.schema.json",
            "intake-turn-proposal-valid.json",
            lambda value: value["dossier_patch"]["case_story"].update(
                {"nested": {"execute_tool": "REFUND"}}
            ),
        ),
    ]
    for schema_name, fixture_name, mutate in cases:
        schema = json.loads((CONTRACT_ROOT / schema_name).read_text(encoding="utf-8"))
        validator = jsonschema.Draft202012Validator(schema)
        value = copy.deepcopy(
            json.loads((valid_root / fixture_name).read_text(encoding="utf-8"))
        )
        mutate(value)
        assert list(validator.iter_errors(value)), fixture_name


def test_phase4_valid_fixtures_have_exact_rfc8785_self_hashes() -> None:
    valid_root = CONTRACT_ROOT / "fixtures/valid"
    for prefix, (_, hash_field) in SCHEMA_BY_FIXTURE.items():
        fixture = json.loads(
            next(valid_root.glob(f"{prefix}-valid.json")).read_text(encoding="utf-8")
        )
        expected = fixture.pop(hash_field)
        actual = hashlib.sha256(rfc8785.dumps(fixture)).hexdigest()
        assert actual == expected, prefix

    registration = json.loads(
        (valid_root / "graph-private-thread-registration-valid.json").read_text(
            encoding="utf-8"
        )
    )
    actor_hash = hashlib.sha256(rfc8785.dumps(registration["actor_scope"])).hexdigest()
    assert actor_hash == registration["actor_scope_hash"]


def test_phase4_valid_contracts_exclude_memory_and_formal_authority() -> None:
    valid_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((CONTRACT_ROOT / "fixtures/valid").glob("*.json"))
    )
    forbidden = {
        "memory_frame",
        "open_evidence",
        "complete_party",
        "send_summons",
        "execute_tool",
        "hidden_reasoning",
    }
    assert not (forbidden & set(re.findall(r'"([a-z_]+)"\s*:', valid_text)))


def test_phase4_finalization_key_capacity_covers_the_exact_frozen_formula() -> None:
    schema = json.loads(
        (CONTRACT_ROOT / "intake-finalization-receipt.schema.json").read_text(
            encoding="utf-8"
        )
    )
    operation_key = schema["$defs"]["operation_key"]
    maximum_formula_length = (
        len("intake.turn.finalize:") + 128 + 1 + 19 + 1 + 39 + 1 + 128 + 1 + 64
    )

    assert maximum_formula_length == 403
    assert operation_key["maxLength"] >= maximum_formula_length
    assert operation_key["pattern"].endswith("{0,511}$")


def test_phase4_runtime_defaults_require_two_nonlegacy_activation_locks() -> None:
    properties = (
        ROOT
        / "java-api-service/src/main/java/com/example/dispute/workflow/config/OrchestrationCutoverProperties.java"
    ).read_text(encoding="utf-8")
    selector = (
        ROOT
        / "java-api-service/src/main/java/com/example/dispute/workflow/application/epoch/ConfiguredRoomEpochSelector.java"
    ).read_text(encoding="utf-8")
    application = (
        ROOT / "java-api-service/src/main/resources/application.yml"
    ).read_text(encoding="utf-8")
    environment = (ROOT / ".env.example").read_text(encoding="utf-8")

    assert '@DefaultValue("LEGACY") WriterMode newEpochMode' in properties
    assert '@DefaultValue("false") boolean nonLegacyEpochAllocationEnabled' in properties
    assert '@DefaultValue("false") boolean temporalWriterEnabled' in properties
    assert "nonLegacyEpochAllocationEnabled()" in selector
    assert "temporalWriterEnabled()" in selector
    assert "non-LEGACY room epoch allocation is disabled" in selector
    assert "TEMPORAL room writer activation is disabled" in selector
    assert "APP_ORCHESTRATION_NON_LEGACY_EPOCH_ALLOCATION_ENABLED:false" in application
    assert "APP_ORCHESTRATION_TEMPORAL_WRITER_ENABLED:false" in application
    assert "APP_ORCHESTRATION_NON_LEGACY_EPOCH_ALLOCATION_ENABLED=false" in environment
    assert "APP_ORCHESTRATION_TEMPORAL_WRITER_ENABLED=false" in environment


def test_phase4_compose_keeps_intake_runtime_fail_closed_for_java_services() -> None:
    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    java_environment = compose.split("x-java-environment:", 1)[1].split(
        "x-java-core-depends-on:", 1
    )[0]
    agent_worker = compose.split("java-agent-worker:", 1)[1].split(
        "frontend:", 1
    )[0]

    for required in (
        "APP_ORCHESTRATION_NON_LEGACY_EPOCH_ALLOCATION_ENABLED: "
        "${APP_ORCHESTRATION_NON_LEGACY_EPOCH_ALLOCATION_ENABLED:-false}",
        "APP_ORCHESTRATION_TEMPORAL_WRITER_ENABLED: "
        "${APP_ORCHESTRATION_TEMPORAL_WRITER_ENABLED:-false}",
        "APP_ORCHESTRATION_INTAKE_EPOCH_SELECTION_MODE: "
        "${APP_ORCHESTRATION_INTAKE_EPOCH_SELECTION_MODE:-LEGACY}",
        "APP_ORCHESTRATION_INTAKE_SHADOW_COHORT_BASIS_POINTS: "
        "${APP_ORCHESTRATION_INTAKE_SHADOW_COHORT_BASIS_POINTS:-0}",
        "APP_ORCHESTRATION_INTAKE_COHORT_POLICY_VERSION: "
        "${APP_ORCHESTRATION_INTAKE_COHORT_POLICY_VERSION:-}",
        "APP_ORCHESTRATION_INTAKE_SIGNED_SYNTHETIC_SHADOW_ENABLED: "
        "${APP_ORCHESTRATION_INTAKE_SIGNED_SYNTHETIC_SHADOW_ENABLED:-false}",
    ):
        assert required in java_environment

    assert "APP_ORCHESTRATION_INTAKE_EPOCH_SELECTION_MODE:-TEMPORAL" not in compose
    assert "APP_ORCHESTRATION_TEMPORAL_WRITER_ENABLED:-true" not in compose
    assert "APP_AGENT_RUN_V2_GRAPH_CLIENT_MODE: ${APP_AGENT_RUN_V2_GRAPH_CLIENT_MODE:-DISABLED}" in agent_worker
    assert "APP_AGENT_RUN_V2_GRAPH_SIGNING_KEY_DIRECTORY: /run/secrets/graph-signing-keys" in agent_worker
    assert "APP_AGENT_RUN_V2_GRAPH_TLS_KEY_STORE_PATH: /run/secrets/graph-mtls/client.p12" in agent_worker
    assert "APP_AGENT_RUN_V2_GRAPH_TLS_TRUST_STORE_PATH: /run/secrets/graph-mtls/trust.p12" in agent_worker


def test_phase4_plan_is_linked_and_forbids_implicit_respondent_timeout() -> None:
    plan_text = PLAN.read_text(encoding="utf-8")
    matrix_text = MATRIX.read_text(encoding="utf-8")
    contract_text = CONTRACT.read_text(encoding="utf-8")
    adr_text = ADR.read_text(encoding="utf-8")
    agents_text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    master_text = (ROOT / "plans/temporal-langgraph-room-refactor.md").read_text(
        encoding="utf-8"
    )

    for filename in (PLAN.name, MATRIX.name, CONTRACT.name, ADR.name):
        assert filename in agents_text or filename in master_text
    assert "one primary plus five delegated implementation owners" in adr_text
    assert "No new Intake business deadline or automatic respondent timeout" in plan_text
    assert "respondent_timeout" not in matrix_text
    assert "respondent_wait_and_submission_replay_without_implicit_timeout" in matrix_text
