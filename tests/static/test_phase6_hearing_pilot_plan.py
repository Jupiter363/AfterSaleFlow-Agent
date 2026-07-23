from __future__ import annotations

import ast
import re
from fnmatch import fnmatchcase
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "plans/phase-6-hearing-pilot-execution.md"
MATRIX = ROOT / "plans/phase-6-hearing-pilot-test-batches.yaml"
OWNER_BRIEFS = ROOT / "plans/phase-6-owner-briefs.yaml"
ENTRY_RUNNER = ROOT / "scripts/run_phase6_entry_checkpoint.py"
CONTRACT = (
    ROOT / "docs/runbooks/temporal-first/phase-6-p6.0-contract-pack.md"
)
BASELINE_INVENTORY = (
    ROOT / "docs/runbooks/temporal-first/phase-6-p6.0-baseline-inventory.md"
)
SOURCE_PLAN = ROOT / "plans/temporal-langgraph-room-refactor.md"
HEARING_CONTRACT = ROOT / "docs/contracts/hearing-flow-v2.md"
BASELINE = ROOT / "docs/acceptance/current-room-function-baseline.md"
CHECKLIST = (
    ROOT / "docs/acceptance/temporal-first-agent-platform-verification-checklist.md"
)
CHECK_MANIFEST = ROOT / "tests/acceptance/temporal-first-check-manifest.yaml"
BASELINE_MANIFEST = ROOT / "tests/baseline/current-room-baseline.yaml"
JAVA_STAGE = (
    ROOT
    / "java-api-service/src/main/java/com/example/dispute/hearing/domain/HearingFlowStage.java"
)
PYTHON_HEARING = ROOT / "python-agent-service/app/agents/hearing_flow.py"
MIGRATIONS = ROOT / "java-api-service/src/main/resources/db/migration"
V044 = "V044__hearing_temporal_projection.sql"
BASE_SHA = "d3ea271188be57adac49592879aaf3417e90c5c0"

STAGES = [
    "COURT_PREPARING",
    "CASE_INTRODUCTION",
    "EVIDENCE_INTRODUCTION",
    "INTAKE_QUESTIONS_GENERATING",
    "PARTY_ANSWERS_OPEN",
    "INTAKE_SYNTHESIZING",
    "EVIDENCE_REQUESTS_GENERATING",
    "PARTY_EVIDENCE_OPEN",
    "EVIDENCE_SYNTHESIZING",
    "DOSSIER_FREEZING",
    "JUDGE_V1_GENERATING",
    "JURY_REVIEWING",
    "JUDGE_V2_GENERATING",
    "HUMAN_REVIEW_OPEN",
    "CLOSED",
]

OPERATIONS = {
    "HEARING_INTAKE_QUESTIONS",
    "HEARING_INTAKE_SYNTHESIS",
    "HEARING_EVIDENCE_REQUESTS",
    "HEARING_EVIDENCE_SYNTHESIS",
    "HEARING_JUDGE_V1",
    "HEARING_JURY_REVIEW",
    "HEARING_JUDGE_V2",
}


def _matrix() -> dict:
    return yaml.safe_load(MATRIX.read_text(encoding="utf-8"))


def _owner_briefs() -> dict:
    return yaml.safe_load(OWNER_BRIEFS.read_text(encoding="utf-8"))


def _document_texts() -> tuple[str, str, str]:
    return (
        PLAN.read_text(encoding="utf-8"),
        CONTRACT.read_text(encoding="utf-8"),
        SOURCE_PLAN.read_text(encoding="utf-8"),
    )


def _java_stage_order() -> list[str]:
    text = JAVA_STAGE.read_text(encoding="utf-8")
    enum_body = text.split("public enum HearingFlowStage {", 1)[1].split(";", 1)[0]
    return re.findall(
        r"^\s*([A-Z][A-Z0-9_]*)\((?:true|false)\)\s*,?\s*$",
        enum_body,
        re.MULTILINE,
    )


def _contract_stage_order() -> list[str]:
    text = HEARING_CONTRACT.read_text(encoding="utf-8")
    candidates = re.findall(r"^\| `([A-Z][A-Z0-9_]*)` \|", text, re.MULTILINE)
    return [value for value in candidates if value in STAGES]


def _range(prefix: str, first: int, last: int) -> list[str]:
    return [f"{prefix}-{number:03d}" for number in range(first, last + 1)]


def test_phase6_candidate_is_linked_and_remains_fail_closed() -> None:
    matrix = _matrix()
    plan, contract, source = _document_texts()

    assert matrix["schema_version"] == "phase-test-batches.v1"
    assert matrix["phase"] == 6
    assert matrix["document_status"] == "P6_0_CONTRACT_CANDIDATE"
    assert matrix["gate"]["contract_gate"] == "P6.0"
    assert matrix["gate"]["contract_gate_status"] == "NOT_RUN"
    assert matrix["gate"]["accepted_phase_5_checkpoint_sha"] == BASE_SHA
    assert matrix["gate"]["entry_decision"] == "CONTRACT_CANDIDATE_READY"

    observed = matrix["gate"]["observed_entry_state"]
    assert observed == {
        "phase_5_engineering_checkpoint": "PASS",
        "phase_6_engineering_exception": "ADR_0015_ACCEPTED_FOR_ENGINEERING_ONLY",
        "next_phase_permission": "PHASE_6_ENGINEERING_ONLY",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "P6.0": "NOT_RUN",
        "MIG-006": "PENDING_PROMOTION",
    }
    assert set(matrix["gate"]["runtime_modes_allowed_now"]) == {
        "LEGACY",
        "DISABLED",
        "SIGNED_SYNTHETIC_SHADOW",
    }
    assert set(matrix["gate"]["runtime_modes_forbidden_now"]) == {
        "REAL_CASE_SHADOW",
        "TEMPORAL",
    }

    for text in (plan, contract):
        assert BASE_SHA in text
        assert "engineering_execution: BLOCKED" in text
        assert "phase_5_engineering_checkpoint: PASS" in text
        assert "MIG-005: PENDING_PROMOTION" in text
        assert "MIG-006" in text
        assert "shadow: FORBIDDEN" in text

    for path in matrix["documents"].values():
        assert (ROOT / path).is_file(), path

    assert "phase-6-hearing-pilot-execution.md" in source
    assert "phase-6-hearing-pilot-test-batches.yaml" in source
    assert "phase-6-p6.0-contract-pack.md" in source


def test_phase6_uses_a_strict_two_commit_exact_sha_entry_gate() -> None:
    matrix = _matrix()
    gate = matrix["gate"]
    plan, contract, _ = _document_texts()

    assert set(gate["implementation_allowed_when"]) == {
        "PHASE_5_ENGINEERING_CHECKPOINT_PASS",
        "ADR_0015_ACCEPTED_FOR_ENGINEERING_ONLY",
        "PHASE_6_ENGINEERING_ONLY_PERMISSION_RECORDED",
        "P6_0_CONTRACT_CANDIDATE_COMMITTED",
        "P6_0_BATCH_0_PASSED_ON_EXACT_CONTRACT_CANDIDATE_SHA",
        "P6_0_ENTRY_EVIDENCE_COMMITTED",
    }
    two_commit = gate["two_commit_gate"]
    assert two_commit == {
        "commit_1": "P6_0_CONTRACT_CANDIDATE",
        "exact_sha_batch": "BATCH_0_ALL_FOUR_SUITES",
        "commit_2": "P6_0_ENTRY_EVIDENCE",
        "implementation_after": "COMMIT_2_RECORDS_P6_0_PASS",
        "mixed_product_implementation_in_commit_1": False,
        "reuse_reports_from_another_sha": False,
    }
    assert gate["traffic_constraints"]["temporal_hearing_allocation_allowed"] is False
    assert gate["traffic_constraints"]["formal_hearing_graph_sink_allowed"] is False
    assert gate["traffic_constraints"]["hearing_ui_behavior_change_allowed"] is False

    for text in (plan, contract):
        assert "two-commit gate" in text.lower()
        assert "contract-candidate commit" in text.lower()
        assert "entry-evidence commit" in text.lower()
        assert "clean detached" in text and "SHA" in text
        assert "authorize no product implementation" in text or (
            "does not authorize Phase 6 product" in text
        )

    exceptions = gate["exception_constraints"]
    assert "ADR_0015_CANNOT_MARK_MIG_004_MIG_005_OR_MIG_006_PASS" in exceptions
    assert (
        "AN_EXCEPTION_CANNOT_AUTHORIZE_REAL_DATA_FORMAL_SINK_OR_TEMPORAL_ALLOCATION"
        in exceptions
    )


def test_phase6_stage_order_matches_java_contract_and_machine_schedule() -> None:
    matrix = _matrix()
    machine_stages = matrix["stage_contract"]

    assert [item["sequence"] for item in machine_stages] == list(range(1, 16))
    assert [item["code"] for item in machine_stages] == STAGES
    assert _java_stage_order() == STAGES
    assert _contract_stage_order() == STAGES
    assert [item["code"] for item in machine_stages if item["party_wait"]] == [
        "PARTY_ANSWERS_OPEN",
        "PARTY_EVIDENCE_OPEN",
    ]

    plan, contract, _ = _document_texts()
    for stage in STAGES:
        assert stage in plan
        assert stage in contract
    assert "There are exactly 14 adjacent transitions" in contract
    assert "20 minutes" in contract
    assert "three hours" in contract
    assert "FAILED` is a flow/stage execution status, not a sixteenth" in contract


def test_phase6_graph_families_cover_the_seven_existing_operations() -> None:
    matrix = _matrix()
    graph_families = matrix["graph_families"]
    expected_families = {
        "hearing.intake.v1",
        "hearing.evidence.v1",
        "hearing.judge.v1",
        "hearing.jury.v1",
    }
    assert expected_families <= set(graph_families)

    operations = {
        operation
        for family in expected_families
        for operation in graph_families[family]["operations"]
    }
    assert operations == OPERATIONS
    assert all(
        graph_families[family]["formal_effect"] == "NONE_PROPOSAL_ONLY"
        for family in expected_families
    )

    requirements = graph_families["shared_requirements"]
    assert requirements["topology"] == "EXPLICIT_STATE_GRAPH"
    assert requirements["object_flow"] == "PROMPT_PIPE_GOVERNED_MODEL_PIPE_PARSER"
    assert requirements["checkpoint"] == "POSTGRESQL"
    assert requirements["party_wait_interrupt"] == "FORBIDDEN"
    assert requirements["raw_private_transcript"] == "FORBIDDEN"
    assert set(requirements["version_pins"]) == {
        "graph_version",
        "prompt_version",
        "model_profile_id",
        "output_schema_version",
        "policy_version",
        "guardrail_version",
        "tool_policy_version",
    }

    tree = ast.parse(PYTHON_HEARING.read_text(encoding="utf-8"))
    hearing_class = next(
        node
        for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == "HearingFlowWorkflows"
    )
    public_methods = {
        node.name
        for node in hearing_class.body
        if isinstance(node, ast.FunctionDef) and not node.name.startswith("_")
    }
    assert public_methods == {
        "intake_questions",
        "intake_synthesis",
        "evidence_requests",
        "evidence_synthesis",
        "judge_v1",
        "jury_review",
        "judge_v2",
    }

    plan, contract, _ = _document_texts()
    assert "party waits never live in LangGraph" in contract
    assert "prompt | governed model | parser" in contract
    assert "Java-authorized" in plan and "Artifact" in plan
    assert "raw statements remain actor-private" in plan


def test_phase6_preserves_java_truth_and_reserves_v044_without_implementation() -> None:
    matrix = _matrix()
    reservation = matrix["persistence_reservation"]
    modes = matrix["mode_contract"]

    assert reservation["migration"] == V044
    assert reservation["file_must_exist_in_draft"] is False
    assert reservation["historical_flow_backfill_mode"] == "LEGACY"
    assert reservation["additive_only"] is True
    assert reservation["preserve_v035_append_only"] is True
    assert not (MIGRATIONS / V044).exists()
    assert (MIGRATIONS / "V035__hearing_flow_v2.sql").is_file()
    assert (MIGRATIONS / "V043_2__intake_shadow_comparisons.sql").is_file()
    assert (MIGRATIONS / "V043_3__intake_signed_synthetic_admission.sql").is_file()

    assert modes["LEGACY"]["formal_writer"] == "JAVA_DOMAIN_POSTGRESQL"
    assert modes["SHADOW"]["graph_sink"] == "ISOLATED_COMPARISON_LEDGER_ONLY"
    assert modes["TEMPORAL"]["process_owner"] == "HEARING_ROOM_WORKFLOW"
    assert modes["TEMPORAL"]["formal_writer"] == "JAVA_ACTIVITIES_AND_FINALIZERS"
    assert (
        modes["TEMPORAL"]["allocation_status"]
        == "FORBIDDEN_UNTIL_SEPARATE_MIG_006_PROMOTION"
    )

    plan, contract, _ = _document_texts()
    for text in (plan, contract):
        assert V044 in text
        assert "V035" in text
        assert "append-only" in text
        assert "Java" in text and "sole formal" in text
        assert "Temporal" in text and "process" in text
    assert "V044 is additive and reserved" in contract
    assert "no V044 file is created" in contract


def test_phase6_uses_one_primary_five_owners_and_six_support_lanes() -> None:
    matrix = _matrix()
    resources = matrix["resources"]
    owners = matrix["owners"]

    assert resources["active_primary_agents"] == 1
    assert resources["logical_child_owners"] == 5
    assert resources["logical_p0_review_lanes"] == 3
    assert resources["logical_verification_lanes"] == 2
    assert resources["logical_lookahead_lanes"] == 1
    assert resources["max_active_child_agents"] == 11
    assert resources["max_active_agents_total"] == 12
    assert resources["simultaneous_implementation_owners"] == ["A", "B", "C", "D", "E"]
    assert resources["heavy_test_slots"] == 1
    assert resources["candidate_checkpoint_owner"] == "R"
    assert set(owners) == {"A", "B", "C", "D", "E", "R"}

    delegated_tasks: list[str] = []
    delegated_routes: list[str] = []
    for owner_id in "ABCDE":
        owner = owners[owner_id]
        assert owner["tasks"]
        assert owner["owned_routes"]
        assert owner["forbidden_routes"]
        assert owner["focused_checks"]
        delegated_tasks.extend(owner["tasks"])
        delegated_routes.extend(owner["owned_routes"])
    assert len(delegated_tasks) == len(set(delegated_tasks))
    assert len(delegated_routes) == len(set(delegated_routes))
    assert set(delegated_tasks) <= set(matrix["task_dag"])

    task_dag = matrix["task_dag"]
    for first_task in ("P6-A1", "P6-B1", "P6-C1", "P6-D1", "P6-E1"):
        assert task_dag[first_task]["requires"] == ["P6-R2_PASS"]
    assert set(task_dag["P6-R3"]["requires"]) == {
        "P6-A2",
        "P6-B2",
        "P6-C2",
        "P6-D2",
        "P6-E2",
    }


def test_phase6_owner_briefs_are_blocked_disjoint_and_execution_ready() -> None:
    briefs = _owner_briefs()
    matrix = _matrix()

    assert briefs["schema_version"] == "phase-owner-briefs.v1"
    assert briefs["phase"] == 6
    assert briefs["document_status"] == "P6_0_CONTRACT_CANDIDATE"
    assert briefs["accepted_phase_5_checkpoint_sha"] == BASE_SHA
    gate = briefs["gate"]
    assert gate["dispatch_state"] == "BLOCKED_PENDING_P6_0_ENTRY_EVIDENCE"
    assert gate["implementation_authorized"] is False
    assert gate["required_release_receipt"] == "P6-R2_PASS"
    assert gate["runtime_modes_allowed"] == [
        "LEGACY",
        "DISABLED",
        "SIGNED_SYNTHETIC_SHADOW",
    ]
    assert gate["runtime_modes_forbidden"] == [
        "REAL_CASE_SHADOW",
        "TEMPORAL",
    ]
    assert gate["formal_hearing_graph_sink_allowed"] is False
    assert gate["temporal_hearing_allocation_allowed"] is False
    assert gate["real_case_data_allowed"] is False

    team = briefs["team"]
    assert team["primary_owner"] == "R"
    assert team["delegated_owners"] == ["A", "B", "C", "D", "E"]
    assert team["max_active_primary"] == 1
    assert team["max_active_delegated"] == 11
    assert team["max_active_total"] == 12
    assert team["logical_p0_review_lanes"] == ["R1", "R2", "R3"]
    assert team["logical_verification_lanes"] == ["V1", "V2"]
    assert team["logical_lookahead_lanes"] == ["L"]
    assert team["light_test_slots"] == 2
    assert team["heavy_test_slots"] == 1

    expected = {
        "A": ("PYTHON_HEARING_GRAPHS_AND_LCEL", ["P6-A1", "P6-A2"]),
        "B": ("TEMPORAL_HEARING_15_STAGE_AND_TIMERS", ["P6-B1", "P6-B2"]),
        "C": ("JAVA_HEARING_AUTHORITY_FINALIZERS_AND_V044", ["P6-C1", "P6-C2"]),
        "D": ("HEARING_PROJECTION_AND_FRONTEND_COMPATIBILITY", ["P6-D1", "P6-D2"]),
        "E": ("HEARING_SELECTOR_SHADOW_AND_RECOVERY", ["P6-E1", "P6-E2"]),
    }
    source_matrix_owners = {owner: owner for owner in "ABCDE"}
    mappings = briefs["delegation_mapping"]["mappings"]
    owners = briefs["owners"]
    assert set(owners) == set(expected)
    delegated_paths: list[str] = []
    source_tasks: list[str] = []
    for owner_id, (role, tasks) in expected.items():
        owner = owners[owner_id]
        assert owner["role"] == role
        assert owner["source_task_ids"] == tasks
        assert owner["owned_paths"]
        assert owner["forbidden_paths"]
        assert owner["t0_checks"]
        assert owner["dependencies"]
        assert owner["review"]
        assert owner["definition_of_done"]
        assert owner["first_wave"]["status"] == "BLOCKED_ON_P6_R2_PASS"
        assert mappings[owner_id] == {
            "source_matrix_owner": source_matrix_owners[owner_id],
            "source_task_ids": tasks,
        }
        delegated_paths.extend(owner["owned_paths"])
        source_tasks.extend(tasks)
    assert len(delegated_paths) == len(set(delegated_paths))
    for index, left in enumerate(delegated_paths):
        for right in delegated_paths[index + 1 :]:
            assert not fnmatchcase(left, right), (
                f"delegated path overlap: {left} / {right}"
            )
            assert not fnmatchcase(right, left), (
                f"delegated path overlap: {left} / {right}"
            )
    assert len(source_tasks) == len(set(source_tasks)) == 10
    assert set(source_tasks) == {
        task_id
        for matrix_owner in source_matrix_owners.values()
        for task_id in matrix["owners"][matrix_owner]["tasks"]
    }

    first_wave = briefs["first_wave"]
    assert first_wave["status"] == "BLOCKED_ON_P6_R2_PASS"
    assert first_wave["simultaneously_active"] == ["A", "B", "C", "D", "E"]
    assert first_wave["shared_path_writes_allowed"] is False
    assert first_wave["heavy_tests_allowed_for_delegated_owners"] is False
    first_tasks = [item["task_id"] for item in first_wave["assignments"]]
    assert first_tasks == ["P6-A1", "P6-B1", "P6-C1", "P6-D1", "P6-E1"]
    assert all(item["requires"] == ["P6-R2_PASS"] for item in first_wave["assignments"])
    second_wave = {
        item["task_id"]: item for item in briefs["second_wave"]["assignments"]
    }
    assert second_wave == {
        "P6-A2": {
            "owner": "A",
            "task_id": "P6-A2",
            "requires": ["P6-A1", "P6-C1", "C_AUTHORITY_CONTRACT_PUBLISHED"],
        },
        "P6-B2": {
            "owner": "B",
            "task_id": "P6-B2",
            "requires": ["P6-B1", "P6-C1", "C_AUTHORITY_CONTRACT_PUBLISHED"],
        },
        "P6-C2": {"owner": "C", "task_id": "P6-C2", "requires": ["P6-C1"]},
        "P6-D2": {
            "owner": "D",
            "task_id": "P6-D2",
            "requires": ["P6-D1", "P6-C2"],
        },
        "P6-E2": {
            "owner": "E",
            "task_id": "P6-E2",
            "requires": ["P6-E1", "P6-A2", "P6-B2", "P6-C2"],
        },
    }

    governance = briefs["shared_path_governance"]
    assert governance["owner"] == "R"
    assert governance["direct_delegated_edits_forbidden"] is True
    assert governance["primary_owned_paths"]
    assert governance["barriers"]
    assert set(governance["primary_owned_paths"]).isdisjoint(delegated_paths)
    for shared in governance["primary_owned_paths"]:
        for delegated in delegated_paths:
            assert not fnmatchcase(shared, delegated), (
                f"primary/delegated path overlap: {shared} / {delegated}"
            )
            assert not fnmatchcase(delegated, shared), (
                f"primary/delegated path overlap: {shared} / {delegated}"
            )
    for barrier in governance["barriers"]:
        assert barrier["producer"] in "ABCDE"
        assert barrier["integrator"] == "R"
        assert barrier["status"] == "BLOCKED"
        assert barrier["shared_paths"]
        assert set(barrier["shared_paths"]) <= set(governance["primary_owned_paths"])

    safety = briefs["safety_invariants"]
    assert "NO_REAL_CASE_SHADOW" in safety
    assert "NO_TEMPORAL_HEARING_ALLOCATION" in safety
    assert "NO_FORMAL_HEARING_GRAPH_SINK" in safety
    assert "JAVA_DOMAIN_POSTGRESQL_REMAINS_SOLE_FORMAL_WRITER" in safety
    assert "MIG_006_REMAINS_PENDING_PROMOTION" in safety


def test_phase6_centralizes_heavy_tests_and_defers_unified_suites() -> None:
    matrix = _matrix()
    batches = matrix["batches"]

    assert batches["draft_validation"]["status"] == "RUNNABLE_ON_CONTRACT_CANDIDATE"
    assert (
        batches["batch_0_entry"]["status"]
        == "READY_FOR_EXACT_SHA_BATCH_0"
    )
    entry = batches["batch_0_entry"]
    sources = entry["source_commands"]
    assert {source["id"] for source in sources} == {
        "static_phase6_entry",
        "python_phase6_entry",
        "java_phase6_entry",
        "frontend_phase6_entry",
    }
    assert entry["source_reports"] == {
        "static_phase6_entry": "static-phase6-entry.xml",
        "python_phase6_entry": "python-phase6-entry.xml",
        "java_phase6_entry": "java-phase6-entry.xml",
        "frontend_phase6_entry": "frontend-phase6-entry.xml",
    }
    assert {source["id"]: source["report"] for source in sources} == entry[
        "source_reports"
    ]
    assert entry["all_four_suites_required"] is True
    assert entry["exact_clean_detached_sha_required"] is True
    assert entry["fresh_run_directory_required"] is True
    assert entry["sealed_execution_manifest_required"] is True
    assert entry["execution_gate"]["reject_before_source_execution"] is True
    static_command = next(
        source["command"]
        for source in sources
        if source["id"] == "static_phase6_entry"
    )
    assert "tests/static/test_phase6_entry_checkpoint.py" in static_command
    assert "tests/static/test_phase6_entry_evidence.py" in static_command
    assert "tests/static/test_phase6_entry_checkpoint.py" in batches[
        "draft_validation"
    ]["commands"][0]["command"]
    assert "tests/static/test_phase6_entry_evidence.py" in batches[
        "draft_validation"
    ]["commands"][0]["command"]
    assert "tests/static/test_phase6_entry_checkpoint.py" in batches[
        "batch_1_foundation"
    ]["static_test_paths"]
    assert entry["retry_policy"] == {
        "classify_before_resume": True,
        "classifications": ["PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"],
        "same_sha_retry_allowed_only_for": "INFRA",
        "quarantined_attempts_reused": False,
        "product_fixture_external_gate_block_candidate": True,
        "mixed_candidate_reports_forbidden": True,
    }
    assert entry["execution_manifest"]["terminal_green_status"] == (
        "SOURCE_SUITES_GREEN_AWAITING_SEPARATE_ENTRY_EVIDENCE"
    )
    assert ENTRY_RUNNER.is_file()
    assert (ROOT / matrix["documents"]["entry_evidence_generator"]).is_file()

    assert batches["batch_1_foundation"]["owner"] == "R"
    assert batches["batch_2_integration"]["owner"] == "R"
    assert batches["batch_3_candidate"]["owner"] == "R"
    assert batches["batch_3_candidate"]["report_hashes_required"] is True
    assert batches["batch_4_promotion"]["status"] == "EXTERNAL_AND_NOT_AUTHORIZED"
    assert matrix["tiers"]["T4"]["automatic"] is False
    assert matrix["test_token"]["max_heavy_tokens"] == 1

    forbidden = set(matrix["resources"]["child_forbidden_commands"])
    assert {
        "java_full_suite",
        "python_full_suite",
        "docker_compose_up_or_build",
        "frontend_production_build",
        "browser_or_playwright_suite",
        "repository_full_regression",
        "load_soak_chaos_or_dr",
    } == forbidden


def test_phase6_check_and_baseline_coverage_matches_normative_manifests() -> None:
    matrix = _matrix()
    checks = matrix["checks"]
    check_manifest = yaml.safe_load(CHECK_MANIFEST.read_text(encoding="utf-8"))
    baseline_manifest = yaml.safe_load(BASELINE_MANIFEST.read_text(encoding="utf-8"))
    known_checks = {item["id"] for item in check_manifest["checks"]}
    known_baselines = {item["id"] for item in baseline_manifest["behaviors"]}

    expected_primary = {
        *_range("ROOM-HEARING", 1, 7),
        *_range("TEMP", 13, 18),
        *_range("TEMP", 20, 29),
        *_range("RUN", 1, 9),
        "GRAPH-009",
        *_range("GRAPH", 11, 20),
        *_range("LCEL", 1, 14),
        *_range("JAVA", 4, 10),
        "E2E-008",
        "E2E-009",
        "MIG-006",
    }
    actual_primary = {
        value
        for values in checks["primary"].values()
        for value in values
    }
    assert actual_primary == expected_primary
    assert actual_primary <= known_checks

    expected_baseline = {
        *_range("HRG", 1, 19),
        *_range("DRF", 1, 4),
        *_range("UI", 2, 5),
        *_range("CORE", 1, 10),
        *_range("SEC", 1, 6),
        "EVD-002",
        "EVD-012",
    }
    actual_baseline = {
        value
        for values in checks["baseline"].values()
        for value in values
    }
    assert actual_baseline == expected_baseline
    assert actual_baseline <= known_baselines
    assert checks["claim_policy"]["MIG-006"] == "PENDING_PROMOTION_UNTIL_SEPARATE_REAL_SHADOW_CANARY"
    assert checks["claim_policy"]["synthetic_can_substitute_for_promotion"] is False

    baseline_text = BASELINE.read_text(encoding="utf-8")
    checklist_text = CHECKLIST.read_text(encoding="utf-8")
    for baseline_id in _range("HRG", 1, 19):
        assert f"[{baseline_id}]" in baseline_text
    for check_id in _range("ROOM-HEARING", 1, 7):
        assert f"`{check_id}`" in checklist_text


def test_phase6_preserves_ui_privacy_and_release_boundaries() -> None:
    matrix = _matrix()
    plan, contract, _ = _document_texts()
    invariants = "\n".join(matrix["gate"]["invariants"])

    assert "Existing Hearing API and UI baseline is preserved" in invariants
    for text in (plan, contract):
        assert "six progress groups" in text
        assert "settlement" in text.lower()
        assert "side-effect free" in text
        assert "shared" in text and "Artifact" in text
        assert "private" in text
        assert "MIG-006=PASS" in text

    promotion = matrix["batches"]["batch_4_promotion"]
    assert "AUTHORIZED_ACTIVE_CASE_SHADOW_PARITY" in promotion["required_but_not_runnable_from_this_plan"]
    assert "NEW_EPOCH_TEMPORAL_COHORT_AND_CANARY" in promotion["required_but_not_runnable_from_this_plan"]
    assert matrix["exit_report_shape"]["MIG-006"] == "PENDING_PROMOTION"


def test_phase6_baseline_inventory_is_factual_blocked_and_complete() -> None:
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")

    assert "inventory_status: BASELINE_ONLY" in inventory
    assert f"observed_commit: {BASE_SHA}" in inventory
    assert "contract_gate: P6.0 NOT_RUN" in inventory
    assert "engineering_execution: BLOCKED" in inventory
    assert "implementation_authorization: BLOCKED_PENDING_P6_0_ENTRY_EVIDENCE" in inventory
    assert "closes no Phase 6 implementation gap" in inventory

    expected_counts = {
        "java_hearing_main_files": 39,
        "java_hearing_entities": 7,
        "java_hearing_repositories": 7,
        "java_hearing_test_classes": 5,
        "java_hearing_test_methods": 21,
        "java_hearing_controller_mappings": 8,
        "java_temporal_hearing_workflow_classes": 0,
        "v035_tables": 5,
        "v035_triggers": 7,
        "python_hearing_external_operations": 7,
        "python_hearing_http_routes": 14,
        "python_hearing_prompt_templates": 8,
        "python_hearing_flow_tests": 23,
        "frontend_hearing_stages": 15,
        "frontend_hearing_groups": 6,
        "frontend_hearing_view_tests": 63,
        "frontend_hearing_utility_tests": 4,
        "frontend_hearing_api_tests": 3,
        "opt_in_live_hearing_e2e_tests": 1,
    }
    for key, value in expected_counts.items():
        assert f"{key}: {value}" in inventory

    for stage in STAGES:
        assert f"`{stage}`" in inventory
    for operation in OPERATIONS:
        assert f"`{operation}`" in inventory
    for number in range(10):
        assert f"`P6-G{number}`" in inventory


def test_phase6_baseline_inventory_counts_match_checked_in_sources() -> None:
    java_root = ROOT / "java-api-service/src/main/java/com/example/dispute/hearing"
    java_test_root = ROOT / "java-api-service/src/test/java/com/example/dispute/hearing"
    controller = java_root / "api/HearingFlowController.java"
    v035 = MIGRATIONS / "V035__hearing_flow_v2.sql"
    python_main = ROOT / "python-agent-service/app/main.py"
    prompt_root = ROOT / "python-agent-service/app/agents/prompts"
    python_test = ROOT / "python-agent-service/tests/agents/test_hearing_flow_v2.py"
    frontend_flow = ROOT / "frontend/src/utils/hearingFlow.js"

    java_main_files = list(java_root.rglob("*.java"))
    java_test_files = list(java_test_root.glob("*.java"))
    assert len(java_main_files) == 39
    assert len(list((java_root / "infrastructure/persistence/entity").glob("*.java"))) == 7
    assert len(list((java_root / "infrastructure/persistence/repository").glob("*.java"))) == 7
    assert len(java_test_files) == 5
    assert sum(
        len(re.findall(r"^\s*@Test\b", path.read_text(encoding="utf-8"), re.MULTILINE))
        for path in java_test_files
    ) == 21
    assert len(
        re.findall(
            r"^\s*@(Get|Post|Put|Delete)Mapping\b",
            controller.read_text(encoding="utf-8"),
            re.MULTILINE,
        )
    ) == 8
    assert not (
        ROOT
        / "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/hearing"
    ).exists()

    v035_text = v035.read_text(encoding="utf-8")
    assert len(re.findall(r"^create table ", v035_text, re.MULTILINE)) == 5
    assert len(re.findall(r"^create trigger ", v035_text, re.MULTILINE)) == 7
    assert not (MIGRATIONS / V044).exists()

    assert len(list(prompt_root.rglob("hearing_*.md"))) == 8
    python_main_text = python_main.read_text(encoding="utf-8")
    hearing_routes = re.findall(
        r'@app\.post\(\s*"(/internal/agents/hearing-flow/[^\"]+)"',
        python_main_text,
        re.MULTILINE,
    )
    assert len(hearing_routes) == 14
    assert len(hearing_routes) == len(set(hearing_routes))
    assert len(re.findall(r"^def test_", python_test.read_text(encoding="utf-8"), re.MULTILINE)) == 23

    frontend_text = frontend_flow.read_text(encoding="utf-8")
    frontend_stage_body = frontend_text.split(
        "export const HEARING_FLOW_STAGES = Object.freeze([", 1
    )[1].split("]);", 1)[0]
    assert re.findall(r'code: "([A-Z][A-Z0-9_]*)"', frontend_stage_body) == STAGES
    group_body = frontend_text.split(
        "export const HEARING_FLOW_GROUPS = Object.freeze([", 1
    )[1].split("]);", 1)[0]
    assert len(re.findall(r'^\s*"[^\"]+",?\s*$', group_body, re.MULTILINE)) == 6

    javascript_test_counts = {
        ROOT / "frontend/src/views/disputes/HearingCourtView.test.js": 63,
        ROOT / "frontend/src/utils/hearingFlow.test.js": 4,
        ROOT / "frontend/src/api/hearing.test.js": 3,
    }
    for path, expected in javascript_test_counts.items():
        assert len(re.findall(r"^\s*it\(", path.read_text(encoding="utf-8"), re.MULTILINE)) == expected
    live_e2e = ROOT / "tests/e2e/test_hearing_flow_v2_live.py"
    assert len(re.findall(r"^def test_", live_e2e.read_text(encoding="utf-8"), re.MULTILINE)) == 1


def test_phase6_baseline_inventory_records_current_side_effect_and_test_gaps() -> None:
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    required_paths = [
        "java-api-service/src/main/java/com/example/dispute/hearing/api/HearingFlowController.java",
        "java-api-service/src/main/java/com/example/dispute/hearing/application/HearingFlowRuntimeService.java",
        "java-api-service/src/main/java/com/example/dispute/hearing/application/HearingFlowDeadlineScheduler.java",
        "java-api-service/src/main/java/com/example/dispute/hearing/application/HearingReviewHandoffService.java",
        "java-api-service/src/main/java/com/example/dispute/hearing/application/HearingReviewHandoffRecoveryScheduler.java",
        "python-agent-service/app/agents/hearing_flow.py",
        "python-agent-service/app/schemas/hearing_flow.py",
        "python-agent-service/app/main.py",
        "frontend/src/views/disputes/HearingCourtView.vue",
        "frontend/src/utils/hearingFlow.js",
        "frontend/src/stores/hearing.js",
        "frontend/src/api/hearing.js",
        "V035__hearing_flow_v2.sql",
        "V037__key_hearing_party_actions_by_participant_id.sql",
    ]
    for path in required_paths:
        assert path in inventory

    assert "GET behavior is a P6 cutover gap" in inventory
    assert "create `AUTO_TIMEOUT` actions" in inventory
    assert "every `PT15S`" in inventory
    assert "every `PT30S`" in inventory
    assert "ThreadPoolExecutor(max_workers=len(pending))" in inventory
    assert "no `HearingRoomWorkflowTest`" in inventory
    assert "no scheduler `EXECUTOR/DETECTOR/OFF` mode" in inventory
    assert "GET remains side-effecting gap" in inventory
