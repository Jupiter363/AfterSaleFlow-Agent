from __future__ import annotations

import re
from collections import Counter
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "plans/phase-2-agent-run-v2-execution.md"
MATRIX = ROOT / "plans/phase-2-agent-run-v2-test-batches.yaml"
CHECKLIST = ROOT / "docs/acceptance/temporal-first-agent-platform-verification-checklist.md"
BASELINE = ROOT / "docs/acceptance/current-room-function-baseline.md"


def load_matrix() -> dict:
    return yaml.safe_load(MATRIX.read_text(encoding="utf-8"))


def expected_coverage_ids() -> set[str]:
    return {
        *(f"RUN-{number:03d}" for number in range(1, 10)),
        *(f"STREAM-{number:03d}" for number in range(1, 14)),
        *(f"JAVA-{number:03d}" for number in range(7, 11)),
        *(f"E2E-{number:03d}" for number in range(4, 7)),
        "HA-001",
        "HA-002",
        "HA-007",
        "MIG-002",
        *(f"CORE-{number:03d}" for number in range(4, 10)),
        "SEC-001",
        "SEC-002",
        "SEC-004",
        "UI-004",
    }


def test_phase2_plan_stays_blocked_and_cpu_bounded() -> None:
    matrix = load_matrix()

    assert matrix["schema_version"] == "phase-test-batches.v1"
    assert matrix["gate"] == {
        "required_entry": "MIG-001",
        "current_status": "BLOCKED",
        "implementation_allowed_when": [
            "MIG-001_PASS",
            "APPROVED_OFF_SHADOW_DEVELOPMENT_EXCEPTION",
        ],
    }
    assert matrix["resources"]["active_primary_agents"] == 1
    assert matrix["resources"]["active_child_agents"] == 3
    assert matrix["resources"]["heavy_test_slots"] == 1
    assert matrix["resources"]["playwright_workers"] == 1
    assert matrix["test_token"]["max_heavy_tokens"] == 1
    assert matrix["batches"]["P2-UNIFIED-CHECKPOINT"]["automatic"] is False


def test_phase2_tasks_and_coverage_have_one_declared_plan() -> None:
    matrix = load_matrix()
    agents = matrix["agents"]

    assert set(agents) == {"A", "B", "C", "R"}
    task_ids = [task for agent in agents.values() for task in agent["tasks"]]
    assert not [task for task, count in Counter(task_ids).items() if count > 1]
    assert set(task_ids) == {
        "P2-A1",
        "P2-A2",
        "P2-B1",
        "P2-B2",
        "P2-C1",
        "P2-C2",
        "P2-0",
        "P2-R1",
        "P2-R2",
    }

    assigned_ids = {
        check_id for agent in agents.values() for check_id in agent["check_ids"]
    }
    assert assigned_ids == expected_coverage_ids()

    authoritative_text = "\n".join(
        [
            CHECKLIST.read_text(encoding="utf-8"),
            BASELINE.read_text(encoding="utf-8"),
        ]
    )
    authoritative_ids = set(
        re.findall(r"`\[?([A-Z0-9]+-\d{3})\]?`", authoritative_text)
    )
    assert assigned_ids <= authoritative_ids


def test_phase2_plan_is_linked_and_owned_paths_do_not_duplicate() -> None:
    matrix = load_matrix()
    plan_text = PLAN.read_text(encoding="utf-8")
    agents_file = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    master_plan = (ROOT / "plans/temporal-langgraph-room-refactor.md").read_text(
        encoding="utf-8"
    )

    for required in (
        "engineering_execution: BLOCKED",
        "primary + 3 delegated implementation agents",
        "TEST_REQUEST",
        "TEST_TOKEN",
        "P2-BATCH-1",
        "P2-BATCH-2",
        "P2-BATCH-3",
    ):
        assert required in plan_text

    assert PLAN.name in agents_file
    assert MATRIX.name in agents_file
    assert PLAN.name in master_plan
    assert MATRIX.name in master_plan

    child_routes = [
        route
        for name, agent in matrix["agents"].items()
        if name != "R"
        for route in agent.get("change_routes", [])
    ]
    duplicates = [route for route, count in Counter(child_routes).items() if count > 1]
    assert not duplicates, f"duplicate delegated owned paths: {duplicates}"
