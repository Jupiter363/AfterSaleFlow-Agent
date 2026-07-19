from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from pathlib import Path

import rfc8785
import yaml


ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "plans/phase-3-graph-lcel-execution.md"
MATRIX = ROOT / "plans/phase-3-graph-lcel-test-batches.yaml"
CONTRACT = ROOT / "docs/runbooks/temporal-first/phase-3-p3.0-contract-pack.md"
CHECKLIST = ROOT / "docs/acceptance/temporal-first-agent-platform-verification-checklist.md"
HASH_FIXTURES = ROOT / "contracts/agent-platform/v1/fixtures/canonical-hash"


def load_matrix() -> dict:
    return yaml.safe_load(MATRIX.read_text(encoding="utf-8"))


def expected_coverage_ids() -> set[str]:
    return {
        *(f"GRAPH-{number:03d}" for number in range(1, 23)),
        *(f"LCEL-{number:03d}" for number in range(1, 15)),
        *(f"CONTRACT-{number:03d}" for number in range(1, 14)),
        *(f"SEC-{number:03d}" for number in range(2, 11)),
        *(f"OBS-{number:03d}" for number in range(1, 5)),
        "HA-003",
        "HA-004",
        "HA-005",
        "HA-008",
        "HA-009",
        "HA-011",
        "MIG-003",
    }


def test_phase3_gate_and_resources_remain_restricted() -> None:
    matrix = load_matrix()

    assert matrix["schema_version"] == "phase-test-batches.v1"
    assert matrix["gate"] == {
        "required_entry": "PHASE_2_ENGINEERING_CHECKPOINT",
        "current_status": "APPROVED_DISABLED_SHADOW_DEVELOPMENT_EXCEPTION",
        "implementation_allowed_when": [
            "PHASE_2_ENGINEERING_CHECKPOINT_PASS",
            "ADR_0008_APPROVED",
        ],
        "runtime_modes_allowed": ["DISABLED", "SHADOW"],
        "formal_room_writer_allowed": False,
    }
    assert matrix["resources"]["active_primary_agents"] == 1
    assert matrix["resources"]["active_child_agents"] == 3
    assert matrix["resources"]["heavy_test_slots"] == 1
    assert matrix["resources"]["light_test_slots"] == 2
    assert matrix["test_token"]["max_heavy_tokens"] == 1
    assert matrix["batches"]["P3-UNIFIED-CHECKPOINT"]["automatic"] is False


def test_phase3_dependencies_are_direct_exact_and_hash_locked() -> None:
    dependencies = load_matrix()["dependency_pins"]

    assert dependencies["runtime"] == [
        "langgraph==1.2.6",
        "langchain-core==1.4.9",
        "langgraph-checkpoint-postgres==3.1.0",
        "psycopg[binary]==3.3.4",
        "psycopg-pool==3.3.1",
        "PyJWT[crypto]==2.13.0",
        "opentelemetry-api==1.44.0",
        "opentelemetry-sdk==1.44.0",
        "opentelemetry-exporter-otlp-proto-http==1.44.0",
    ]
    assert dependencies["development"] == [
        "hypothesis==6.156.9",
        "testcontainers[postgres]==4.14.2",
        "pip-tools==7.6.0",
    ]
    assert dependencies["deployment_lock"] == {
        "file": "python-agent-service/requirements.lock",
        "require_hashes": True,
    }


def test_phase3_tasks_paths_and_check_ids_have_one_owner() -> None:
    agents = load_matrix()["agents"]

    assert set(agents) == {"A", "B", "C", "R"}
    tasks = [task for agent in agents.values() for task in agent["tasks"]]
    assert set(tasks) == {
        "P3-A1",
        "P3-A2",
        "P3-B1",
        "P3-B2",
        "P3-C1",
        "P3-C2",
        "P3-0",
        "P3-R1",
        "P3-R2",
    }
    assert not [task for task, count in Counter(tasks).items() if count > 1]

    check_ids = [
        check_id for agent in agents.values() for check_id in agent["check_ids"]
    ]
    assert set(check_ids) == expected_coverage_ids()
    assert not [check_id for check_id, count in Counter(check_ids).items() if count > 1]

    authoritative_ids = set(
        re.findall(r"`([A-Z0-9]+-\d{3})`", CHECKLIST.read_text(encoding="utf-8"))
    )
    assert set(check_ids) <= authoritative_ids

    delegated_routes = [
        route
        for owner, agent in agents.items()
        if owner != "R"
        for route in agent["change_routes"]
    ]
    assert not [
        route for route, count in Counter(delegated_routes).items() if count > 1
    ]


def test_phase3_contract_has_no_open_implementation_decision() -> None:
    text = CONTRACT.read_text(encoding="utf-8")
    normalized = " ".join(text.split())

    for required in (
        "`REGISTERED`, `EXECUTING`, `RESULT_CHECKPOINTED`, `COMPLETED`, `CANCELLED`, and `ABORTED`",
        "top-level `request_hash` member omitted",
        "top-level `output_hash` member omitted",
        "wire form `grt.v1.<32-lowercase-hex-digits>`",
        "lease duration is 30 seconds",
        "renews every 10 seconds",
        "1 MiB per serialized checkpoint state",
        "`alg=ES256`",
        "at most 60 seconds",
        "clock skew is at most 5 seconds",
        "retained for 24 hours",
        "Rows expire after 30 days by default",
        "## Frozen P3.0 Decisions",
    ):
        assert required in normalized

    assert "## Blocking Decisions" not in text
    assert "remain unresolved" not in text


def test_phase3_self_hash_vectors_freeze_omit_member_rule() -> None:
    expected = {
        "room-graph-command-self-hash.json": "request_hash",
        "room-graph-result-self-hash.json": "output_hash",
    }

    for filename, hash_field in expected.items():
        fixture = json.loads((HASH_FIXTURES / filename).read_text(encoding="utf-8"))
        assert fixture["hash_field"] == hash_field
        assert fixture["preimage_rule"] == f"omit top-level {hash_field}"
        assert hash_field not in fixture["input"]
        canonical = rfc8785.dumps(fixture["input"])
        assert canonical.decode("utf-8") == fixture["canonical_utf8"]
        assert hashlib.sha256(canonical).hexdigest() == fixture["sha256"]


def test_phase3_plan_is_linked_from_repository_instructions() -> None:
    plan_text = PLAN.read_text(encoding="utf-8")
    agents_text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    master_text = (ROOT / "plans/temporal-langgraph-room-refactor.md").read_text(
        encoding="utf-8"
    )

    for required in (
        "engineering_execution: ALLOWED_WITH_DISABLED_SHADOW_RESTRICTIONS",
        "primary + 3 delegated implementation agents",
        "P3-BATCH-1",
        "P3-BATCH-2",
        "P3-BATCH-3",
        "TEST_REQUEST",
        "TEST_TOKEN",
        "lens | prompt | model | parser | guardrail",
    ):
        assert required in plan_text

    assert PLAN.name in agents_text
    assert MATRIX.name in agents_text
    assert PLAN.name in master_text
    assert MATRIX.name in master_text


def test_phase3_candidate_batch_covers_cross_language_execution_boundaries() -> None:
    batch = load_matrix()["batches"]["P3-BATCH-3"]
    commands = {item["id"]: item["command"] for item in batch["focused_commands"]}

    assert set(commands) == {"python_phase_3", "root_phase_3_static", "java_phase_3"}
    for required in (
        "tests/static/test_phase3_candidate_evidence.py",
        "tests/api/test_graph_commands.py",
        "tests/api/test_graph_reconciliation.py",
        "tests/api/test_graph_reconciliation_service.py",
        "tests/api/test_graph_stream_service.py",
        "tests/api/test_graph_lifecycle.py",
        "tests/harness/test_model_runner.py",
        "tests/model_runtime",
        "tests/graph_runtime",
    ):
        selected_command = (
            commands["root_phase_3_static"]
            if required.startswith("tests/static/")
            else commands["python_phase_3"]
        )
        assert required in selected_command

    for required in (
        "HttpAgentGraphCommandClientTest",
        "HttpAgentGraphReconciliationClientTest",
        "JdkGraphCommandHttpTransportTest",
        "JdkGraphReconciliationHttpTransportTest",
        "TrustedGraphTransportFactoryTest",
        "Es256GraphCommandEnvelopeSignerTest",
        "Es256GraphReconciliationEnvelopeSignerTest",
        "MountedPemGraphEnvelopeKeySetTest",
        "DurableAgentRunExecutionGatewayTest",
        "GraphCommandClientConfigurationTest",
        "GraphShadowRegistryPropertiesTest",
        "GraphTransportConfigurationTest",
        "GraphShadowAssemblyTest",
        "AgentPlatformContractV1Test",
    ):
        assert required in commands["java_phase_3"]

    execution = batch["execution"]
    assert execution["strategy"] == (
        "deduplicated_source_suites_then_derived_batch_views"
    )
    assert execution["heavy_parallelism"] == 1
    assert execution["database_workers"] == 1
    assert set(execution["source_reports"]) == {
        "python-phase3-junit.xml",
        "static-phase3-junit.xml",
        "java-phase3-junit.xml",
    }
