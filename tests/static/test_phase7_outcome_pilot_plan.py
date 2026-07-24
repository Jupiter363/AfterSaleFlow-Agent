from __future__ import annotations

from fnmatch import fnmatchcase
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
BASE = "d18a1f130a925429e8c2dfd11352cea4ca8673a0"
PLAN_PATH = ROOT / "plans/phase-7-outcome-pilot-execution.md"
MATRIX_PATH = ROOT / "plans/phase-7-outcome-pilot-test-batches.yaml"
BRIEFS_PATH = ROOT / "plans/phase-7-owner-briefs.yaml"
V045_PATH = (
    ROOT
    / "java-api-service/src/main/resources/db/migration/"
    "V045__outcome_operation_receipt_compensation.sql"
)

EXPECTED_REPORTS = {
    "static_phase7_entry": "static-phase7-entry.xml",
    "python_phase7_entry": "python-phase7-entry.xml",
    "java_phase7_entry": "java-phase7-entry.xml",
    "frontend_phase7_entry": "frontend-phase7-entry.xml",
}


def _yaml(path: Path) -> dict:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _overlaps(left: str, right: str) -> bool:
    return fnmatchcase(left, right) or fnmatchcase(right, left)


def _owner_paths(owner: dict) -> list[str]:
    if "owned_paths" in owner:
        return list(owner["owned_paths"])
    paths: list[str] = []
    for scope in ("python_scope", "frontend_scope"):
        paths.extend(owner[scope]["owned_paths"])
    return paths


def test_p7_plan_is_exact_a6_entry_and_all_implementation_remains_blocked() -> None:
    matrix = _yaml(MATRIX_PATH)
    briefs = _yaml(BRIEFS_PATH)
    plan = PLAN_PATH.read_text(encoding="utf-8")

    assert matrix["schema_version"] == "phase-test-batches.v1"
    assert matrix["phase"] == 7
    assert matrix["document_status"] == "P7_0_NOT_RUN_IMPLEMENTATION_BLOCKED"
    gate = matrix["gate"]
    assert gate["accepted_phase_6_checkpoint_A6"] == BASE
    assert gate["contract_gate"] == "P7.0"
    assert gate["contract_gate_status"] == "NOT_RUN"
    assert gate["entry_decision"] == "NOT_RUN"
    assert gate["entry_effect_after_green_evidence"] == (
        "P7_0_ENGINEERING_ENTRY_PASS"
    )
    assert gate["implementation_authorized"] is False
    assert gate["implementation_owners_state"] == "BLOCKED"
    assert gate["product_implementation"] == "BLOCKED"
    assert gate["V045"] == "FORBIDDEN_BEFORE_SEPARATE_ENTRY_EVIDENCE"
    assert not V045_PATH.exists()

    assert briefs["accepted_phase_6_checkpoint_A6"] == BASE
    assert briefs["gate"]["status"] == "NOT_RUN"
    assert briefs["gate"]["dispatch_state"] == "BLOCKED"
    assert briefs["gate"]["implementation_authorized"] is False
    assert briefs["first_wave"]["status"] == "BLOCKED_ON_P7_R2_PASS"
    assert "P7.0" in plan and "NOT_RUN" in plan
    assert "ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY" in plan
    assert "P7_0_ENGINEERING_ENTRY_PASS" in plan


def test_two_commit_gate_and_candidate_scope_are_closed() -> None:
    gate = _yaml(MATRIX_PATH)["gate"]
    assert gate["two_commit_gate"] == {
        "commit_1": "P7_0_CONTRACT_CANDIDATE",
        "exact_sha_batch": "BATCH_0_ALL_FOUR_SOURCES",
        "commit_2": "P7_0_ENTRY_EVIDENCE_DIRECT_CHILD",
        "implementation_after": "COMMIT_2_RECORDS_P7_0_PASS_FOR_EXACT_COMMIT_1_SHA",
        "mixed_product_implementation_in_commit_1": False,
        "reuse_reports_from_another_sha_or_attempt": False,
    }
    assert set(gate["contract_candidate_forbidden_content"]) == {
        "V045_MIGRATION",
        "PRODUCT_OR_RUNTIME_IMPLEMENTATION",
        "WORKER_REGISTRATION_OR_SELECTOR_ADMISSION",
        "REAL_OR_PRODUCTION_CONFIGURATION",
    }
    assert gate["runtime_modes_allowed_now"] == ["LEGACY", "DISABLED"]
    assert gate["runtime_mode_allowed_after_p7_0"] == (
        "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW"
    )
    assert gate["runtime_modes_forbidden"] == ["REAL_CASE_SHADOW", "TEMPORAL"]
    constraints = gate["traffic_constraints"]
    assert constraints["legacy_java_remains_formal"] is True
    for key, value in constraints.items():
        if key != "legacy_java_remains_formal":
            assert value is False, key


def test_one_primary_five_disjoint_implementation_owners_and_support_lanes() -> None:
    matrix = _yaml(MATRIX_PATH)
    briefs = _yaml(BRIEFS_PATH)
    team = briefs["team"]
    assert team == {
        "primary_owner": "R",
        "delegated_implementation_owners": ["A", "B", "C", "D", "E"],
        "logical_p0_review_lanes": ["R1", "R2", "R3"],
        "logical_verification_lanes": ["V1", "V2"],
        "logical_lookahead_lane": "L",
        "max_active_primary": 1,
        "max_active_delegated": 11,
        "max_active_total": 12,
        "heavy_test_slots": 1,
        "light_test_slots": 2,
        "maven_fork_count": 1,
        "frontend_vitest_process_slots": 1,
        "frontend_cli_worker_override": "OMITTED_USE_REPOSITORY_POOL_CONFIG",
        "activation_policy": "DEPENDENCY_AWARE",
        "shared_writer_policy": "ONE_OWNER_PER_PATH",
        "implementation_release_state": "BLOCKED",
    }
    assert matrix["team"]["implementation_owners"] == ["A", "B", "C", "D", "E"]
    assert set(briefs["owners"]) == set("ABCDE")

    task_ids: list[str] = []
    paths_by_owner: dict[str, list[str]] = {}
    for owner_id, owner in briefs["owners"].items():
        assert owner["task_ids"] == [f"P7-{owner_id}1", f"P7-{owner_id}2"]
        assert owner["forbidden_paths"] if owner_id != "E" else (
            owner["python_scope"]["forbidden_paths"]
            and owner["frontend_scope"]["forbidden_paths"]
        )
        assert owner["focused_checks"] if owner_id != "E" else (
            owner["python_scope"]["focused_checks"]
            and owner["frontend_scope"]["focused_checks"]
        )
        task_ids.extend(owner["task_ids"])
        paths_by_owner[owner_id] = _owner_paths(owner)
    assert len(task_ids) == len(set(task_ids)) == 10

    owners = list(paths_by_owner)
    for index, left_owner in enumerate(owners):
        for right_owner in owners[index + 1 :]:
            for left in paths_by_owner[left_owner]:
                for right in paths_by_owner[right_owner]:
                    assert not _overlaps(left, right), (
                        f"owner path overlap {left_owner}/{right_owner}: {left} / {right}"
                    )


def test_primary_exclusively_owns_shared_integration_and_gate_paths() -> None:
    briefs = _yaml(BRIEFS_PATH)
    primary = briefs["primary_integration_owner"]
    assert primary["owner"] == "R"
    assert primary["task_ids"] == ["P7-R0", "P7-R1", "P7-R2", "P7-R3", "P7-R4"]
    expected_global = {
        "plans/phase-7-*",
        "docs/architecture/adr/0016-phase-7-*",
        "docs/runbooks/temporal-first/phase-7-*",
        "contracts/agent-platform/outcome/**",
        "tests/static/test_phase7_*.py",
        "scripts/run_phase7_*.py",
        "scripts/generate_phase7_*.py",
        "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/application/epoch/ConfiguredRoomEpochSelector.java",
        "python-agent-service/app/graph_runtime/registry.py",
        "python-agent-service/app/main.py",
        "frontend/src/router/index.js",
    }
    assert expected_global <= set(primary["owned_paths"])
    assert {
        "temporal_outcome_allocation",
        "formal_workflow_activation",
        "real_tool_or_case_data",
        "canary_or_promotion",
    } <= set(primary["forbidden_actions"])

    delegated = [
        path
        for owner in briefs["owners"].values()
        for path in _owner_paths(owner)
    ]
    for global_path in expected_global:
        assert all(not _overlaps(global_path, path) for path in delegated)


def test_test_budget_is_one_heavy_two_light_and_unified_work_is_deferred() -> None:
    matrix = _yaml(MATRIX_PATH)
    resources = matrix["resources"]
    assert resources["heavy_test_slots"] == 1
    assert resources["light_test_slots"] == 2
    assert resources["maven_fork_count"] == 1
    assert matrix["batches"]["batch_0_entry"]["max_parallel_heavy_sources"] == 1
    assert matrix["batches"]["batch_0_entry"]["max_parallel_light_sources"] == 2

    briefs = _yaml(BRIEFS_PATH)
    policy = briefs["test_policy"]
    assert policy["max_maven_or_testcontainers_processes"] == 1
    assert policy["max_light_processes"] == 2
    assert policy["full_or_e2e_at_unified_checkpoint_only"] is True
    assert matrix["batches"]["unified_or_promotion"] == {
        "status": "DEFERRED",
        "automatic": False,
        "requires_explicit_separate_authorization": True,
        "includes": [
            "full_regression",
            "browser_e2e",
            "docker",
            "load",
            "soak",
            "chaos",
            "dr",
            "real_shadow",
            "canary",
            "promotion",
        ],
    }


def test_batch_zero_has_exact_four_ids_reports_and_commands() -> None:
    entry = _yaml(MATRIX_PATH)["batches"]["batch_0_entry"]
    assert entry["status"] == "NOT_RUN"
    assert entry["accepted_candidate_commit"] is None
    assert entry["entry_evidence_commit"] is None
    assert entry["source_test_counts"] is None
    assert entry["all_four_sources_required"] is True
    assert entry["source_reports"] == EXPECTED_REPORTS
    commands = {item["id"]: item for item in entry["source_commands"]}
    assert list(commands) == list(EXPECTED_REPORTS)
    assert {key: value["report"] for key, value in commands.items()} == EXPECTED_REPORTS

    static = commands["static_phase7_entry"]["command"]
    assert commands["static_phase7_entry"]["expected_report_count"] == 1
    assert commands["static_phase7_entry"]["selected_test_file_count"] == 5
    assert commands["static_phase7_entry"]["minimum_tests"] == 65
    for path in (
        "tests/static/test_phase7_outcome_contracts.py",
        "tests/static/test_phase7_outcome_pilot_plan.py",
        "tests/static/test_phase7_entry_checkpoint.py",
        "tests/static/test_phase7_entry_evidence.py",
        "tests/static/test_temporal_refactor_traceability.py",
    ):
        assert static.count(path) == 1
    assert commands["python_phase7_entry"]["command"] == (
        "D:\\miniconda\\python.exe -m pytest -q tests/test_evaluation.py "
        "--junitxml={raw_report}"
    )
    assert commands["python_phase7_entry"]["expected_report_count"] == 1
    assert commands["python_phase7_entry"]["selected_test_file_count"] == 1
    assert commands["python_phase7_entry"]["minimum_tests"] == 3
    java = commands["java_phase7_entry"]["command"]
    assert java.startswith(".\\mvnw.cmd -DforkCount=1 ")
    assert commands["java_phase7_entry"]["expected_report_count"] == 7
    assert commands["java_phase7_entry"]["selected_test_file_count"] == 7
    assert commands["java_phase7_entry"]["minimum_tests"] == 18
    for test_class in (
        "ReviewApplicationServiceV2Test",
        "FrozenReviewPacketTest",
        "ApprovalPolicyEngineTest",
        "ReviewControllerTest",
        "CaseOutcomeServiceTest",
        "CaseOutcomeControllerTest",
        "RestClientEvaluationAgentClientTest",
    ):
        assert java.count(test_class) == 1
    frontend = commands["frontend_phase7_entry"]["command"]
    assert commands["frontend_phase7_entry"]["expected_report_count"] == 1
    assert commands["frontend_phase7_entry"]["selected_test_file_count"] == 6
    assert commands["frontend_phase7_entry"]["minimum_tests"] == 41
    assert "--maxWorkers" not in frontend
    for test_path in (
        "src/views/disputes/OutcomeView.test.js",
        "src/views/disputes/AdjudicationDraftView.test.js",
        "src/views/reviews/ReviewQueueView.test.js",
        "src/views/reviews/ReviewWorkbenchView.test.js",
        "src/views/ReviewWorkbenchView.test.js",
        "src/api/review.test.js",
    ):
        assert frontend.count(test_path) == 1


def test_batch_zero_gate_blocks_before_sources_and_never_claims_pass() -> None:
    matrix = _yaml(MATRIX_PATH)
    gate = matrix["gate"]
    entry = matrix["batches"]["batch_0_entry"]
    execution_gate = entry["execution_gate"]
    assert execution_gate == {
        "required_candidate_state": "P7_0_CONTRACT_CANDIDATE_COMMITTED",
        "required_upstream_checkpoint": BASE,
        "required_next_phase_permission": "PHASE_7_ENGINEERING_ONLY",
        "required_exception_state": "ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY",
        "required_batch_status": "READY_FOR_EXACT_SHA_BATCH_0",
        "required_entry_decision": "CONTRACT_CANDIDATE_READY",
        "reject_before_source_execution": True,
    }
    assert entry["execution_manifest"]["terminal_green_status"] == (
        "SOURCE_SUITES_GREEN_AWAITING_SEPARATE_ENTRY_EVIDENCE"
    )
    assert entry["execution_manifest"]["forbidden_statuses"] == [
        "P7_0_PASS",
        "IMPLEMENTATION_AUTHORIZED",
        "MIG_006_PASS",
        "MIG_007_PASS",
    ]
    assert entry["required_result"] == (
        "P7_0_ENGINEERING_ENTRY_PASS_FROM_SEPARATE_DIRECT_CHILD_EVIDENCE_COMMIT"
    )
    assert gate["gate_change_policy"][2] == (
        "No owner A through E may write product code before the separate evidence commit."
    )


def test_task_dag_releases_every_owner_only_after_p7_r2_pass() -> None:
    matrix = _yaml(MATRIX_PATH)
    dag = matrix["task_dag"]
    for task_id in ("P7-A1", "P7-B1", "P7-C1", "P7-D1", "P7-E1"):
        assert dag[task_id]["requires"] == ["P7-R2_PASS"]
    assert set(dag["P7-R3"]["requires"]) == {
        "P7-A1",
        "P7-B1",
        "P7-C2",
        "P7-D2",
        "P7-E2",
    }
    assignments = _yaml(BRIEFS_PATH)["first_wave"]["assignments"]
    assert [(item["owner"], item["task_id"]) for item in assignments] == [
        ("A", "P7-A1"),
        ("B", "P7-B1"),
        ("C", "P7-C1"),
        ("D", "P7-D1"),
        ("E", "P7-E1"),
    ]


def test_documents_resolve_and_release_ceiling_remains_engineering_only() -> None:
    matrix = _yaml(MATRIX_PATH)
    entry_checkpoint = matrix["documents"]["entry_checkpoint"]
    assert not (ROOT / entry_checkpoint).exists()
    for name, path in matrix["documents"].items():
        if name == "entry_checkpoint":
            continue
        assert (ROOT / path).is_file(), path
    for path in _yaml(BRIEFS_PATH)["sources"].values():
        assert (ROOT / path).is_file(), path

    ceiling = matrix["promotion_boundary"]
    assert ceiling["engineering_checkpoint_may_grant"] == "PHASE_8_ENGINEERING_ONLY"
    assert set(ceiling["engineering_checkpoint_cannot_grant"]) == {
        "MIG_006_PASS",
        "MIG_007_PASS",
        "TEMPORAL_OUTCOME_ALLOCATION",
        "FORMAL_OUTCOME_WORKFLOW",
        "REAL_TOOL_CAPABILITY",
        "REAL_DATA_SHADOW",
        "PRODUCTION_TRAFFIC",
        "CANARY",
        "PROMOTION",
    }
