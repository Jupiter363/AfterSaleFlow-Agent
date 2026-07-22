from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
EXECUTION_PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"
TEST_BATCHES = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
CONTRACT_PACK = ROOT / "docs/runbooks/temporal-first/phase-5-p5.0-contract-pack.md"
BASELINE_INVENTORY = (
    ROOT
    / "docs/runbooks/temporal-first/phase-5-p5.0-baseline-inventory.md"
)
REVIEW_CLOSURE = (
    ROOT
    / "docs/runbooks/temporal-first/phase-5-p5.0-review-closure.md"
)
EVIDENCE_SUBMISSION_REQUEST = (
    ROOT
    / "java-api-service/src/main/java/com/example/dispute/evidence/api/"
    "EvidenceSubmissionRequest.java"
)
HEARING_EVIDENCE_BATCH_REQUEST = (
    ROOT
    / "java-api-service/src/main/java/com/example/dispute/hearing/api/"
    "HearingEvidenceBatchRequest.java"
)
SOURCE_PLAN = ROOT / "plans/temporal-langgraph-room-refactor.md"
ENGINEERING_EXCEPTION = (
    ROOT / "docs/architecture/adr/0012-phase-5-evidence-engineering-exception.md"
)


def _batches() -> dict:
    return yaml.safe_load(TEST_BATCHES.read_text(encoding="utf-8"))


def test_phase5_engineering_exception_is_accepted_and_cross_linked() -> None:
    adr = ENGINEERING_EXCEPTION.read_text(encoding="utf-8")
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    source_plan = SOURCE_PLAN.read_text(encoding="utf-8")
    batches = _batches()

    assert "# ADR 0012: Phase 5 Evidence Engineering Exception" in adr
    assert "- Status: ACCEPTED FOR DEVELOPMENT" in adr
    assert "- Approval: repository owner" in adr
    assert "MIG-004" in adr and "PENDING_PROMOTION" in adr
    assert "MIG-005" in adr and "PENDING_PROMOTION" in adr
    assert "GRAPH-016" in adr
    assert batches["documents"]["engineering_exception"] == (
        "docs/architecture/adr/0012-phase-5-evidence-engineering-exception.md"
    )

    for document in (execution, contract):
        assert "ADR 0012" in document
        assert "engineering lane" in document
        assert "promotion lane" in document
        assert "MIG-004" in document and "PENDING_PROMOTION" in document
        assert "MIG-005" in document and "PENDING_PROMOTION" in document

    phase5_section = source_plan.split("### 7.6 Phase 5", maxsplit=1)[1].split(
        "### 7.7 Phase 6", maxsplit=1
    )[0]
    assert "ADR 0012" in phase5_section
    assert "`GRAPH-016`" in phase5_section
    assert "mandatory engineering exit evidence, not a P5.0 prerequisite" in " ".join(
        phase5_section.split()
    )
    assert "public Evidence submission limit remains 50" in phase5_section


def test_phase5_machine_gate_requires_only_engineering_entry_authority() -> None:
    batches = _batches()
    gate = batches["gate"]

    assert gate["required_entry"] == "PHASE_4_ENGINEERING_CHECKPOINT"
    assert gate["engineering_exception"] == "ADR_0012_ACCEPTED"
    assert gate["entry_decision"] == "BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT"
    assert set(gate["contract_candidate_allowed_when"]) == {
        "PHASE_4_ENGINEERING_CHECKPOINT_PASS",
        "PHASE_5_ENGINEERING_ONLY_PERMISSION_RECORDED",
        "ADR_0012_ACCEPTED",
    }
    assert set(gate["implementation_allowed_when"]) == {
        "PHASE_4_ENGINEERING_CHECKPOINT_PASS",
        "PHASE_5_ENGINEERING_ONLY_PERMISSION_RECORDED",
        "ADR_0012_ACCEPTED",
        "P5_0_CONTRACT_CANDIDATE_COMMITTED",
        "P5_0_BATCH_0_PASSED_ON_EXACT_CONTRACT_CANDIDATE_SHA",
        "P5_0_ENTRY_EVIDENCE_COMMITTED",
    }

    forbidden_entry_conditions = {
        "MIG_004_PASS_RECORDED",
        "EVIDENCE_100_FILE_PRODUCT_API_UI_APPROVAL_RECORDED",
        "GRAPH_016_ROOM_TENANT_GLOBAL_BULKHEAD_PASS_RECORDED",
        "AUTHORIZED_EVIDENCE_ASSET_BOUNDARY_PASS_RECORDED",
    }
    assert forbidden_entry_conditions.isdisjoint(gate["implementation_allowed_when"])

    external = gate["external_promotion_gates"]
    assert external == {
        "MIG-004": "PENDING_PROMOTION",
        "evidence_100_file_product_api_ui_approval": "PENDING_EXTERNAL_APPROVAL",
        "production_asset_authorization": "PENDING_EXTERNAL_APPROVAL",
        "real_shadow_and_canary": "FORBIDDEN",
    }
    scope = gate["engineering_scope"]
    assert scope["public_submission_limit"] == 50
    assert scope["synthetic_manifest_item_counts"] == [1, 8, 100]
    assert scope["asset_loading"] == "JAVA_SIGNED_SYNTHETIC_CAPABILITY_ONLY"
    assert batches["task_contracts"]["P5-D2"]["output"] == (
        "disabled_signed_synthetic_evidence_100_card_frontend_behavior"
    )


def test_graph_016_is_phase5_exit_evidence_not_a_phase5_entry_dependency() -> None:
    batches = _batches()
    task_contracts = batches["task_contracts"]
    entry_task = task_contracts["P5-0"]
    bulkhead_task = task_contracts["P5-E1"]
    batch_0 = batches["batches"]["P5-BATCH-0"]

    assert set(entry_task["depends_on"]) == {
        "PHASE_4_ENGINEERING_CHECKPOINT_PASS",
        "ADR_0012_ACCEPTED",
    }
    assert "P5-0" in bulkhead_task["depends_on"]
    assert bulkhead_task["closes_engineering_checks"] == ["GRAPH-016"]
    assert "GRAPH-016" in batches["claim_policy"]["engineering_pass_required"]

    entry_text = "\n".join(
        [
            *batches["gate"]["implementation_allowed_when"],
            *entry_task["depends_on"],
            *batch_0["requires"],
        ]
    ).upper()
    assert "GRAPH_016_COMPLETE" not in entry_text
    assert "GRAPH_ROOM_TENANT_GLOBAL_BULKHEAD_PASS" not in entry_text
    assert "MIG_004_PASS" not in entry_text
    assert "PRODUCT_API_UI_APPROVAL_RECORDED" not in entry_text
    assert "ASSET_BOUNDARY_PASS" not in entry_text


def test_phase5_task_dependency_graph_is_acyclic() -> None:
    task_contracts = _batches()["task_contracts"]
    task_ids = set(task_contracts)
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(task_id: str) -> None:
        assert task_id not in visiting, f"Phase 5 dependency cycle reaches {task_id}"
        if task_id in visited:
            return
        visiting.add(task_id)
        for dependency in task_contracts[task_id].get("depends_on", []):
            if dependency in task_ids:
                visit(dependency)
        visiting.remove(task_id)
        visited.add(task_id)

    for task_id in task_ids:
        visit(task_id)


def test_phase5_uses_one_primary_and_five_active_implementation_owners() -> None:
    batches = _batches()
    resources = batches["resources"]

    assert resources["active_primary_agents"] == 1
    assert resources["logical_child_owners"] == 5
    assert resources["max_active_child_agents"] == 5
    assert resources["max_active_agents_total"] == 6
    assert batches["waves"]["wave_a"]["active_roles"] == [
        "R",
        "A",
        "B",
        "C",
        "D",
        "E",
    ]
    assert "P5-D0" in batches["owners"]["D"]["tasks"]
    assert "P5-E0" in batches["owners"]["E"]["tasks"]
    assert batches["task_contracts"]["P5-D0"]["depends_on"] == ["P5-0"]
    assert batches["task_contracts"]["P5-E0"]["depends_on"] == ["P5-0"]


def test_phase5_engineering_lane_cannot_activate_formal_traffic() -> None:
    batches = _batches()
    constraints = batches["gate"]["traffic_constraints"]

    for key in (
        "formal_evidence_graph_sink_allowed",
        "temporal_evidence_allocation_allowed",
        "real_case_shadow_allowed",
        "production_traffic_allowed",
        "canary_allowed",
        "promotion_allowed",
    ):
        assert constraints[key] is False
    assert constraints["synthetic_fixtures_only"] is True
    assert batches["gate"]["runtime_modes_allowed"] == [
        "LEGACY",
        "DISABLED",
        "SIGNED_SYNTHETIC_SHADOW",
    ]

    batch_0 = batches["batches"]["P5-BATCH-0"]
    assert batch_0["asserts_pending_external_gates"] == [
        "MIG-004",
        "EVIDENCE_100_FILE_PRODUCT_API_UI_APPROVAL",
        "PRODUCTION_ASSET_AUTHORIZATION",
    ]
    assert batch_0["execution"]["real_provider"] == "forbidden"
    assert batch_0["execution"]["formal_finalizer"] == "forbidden"


def test_phase5_baseline_and_independent_review_are_cross_referenced() -> None:
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")

    for authority in (
        "phase-5-evidence-pilot-execution.md",
        "phase-5-evidence-pilot-test-batches.yaml",
        "phase-5-p5.0-contract-pack.md",
        "0012-phase-5-evidence-engineering-exception.md",
    ):
        assert authority in inventory
    assert "phase-5-p5.0-review-closure.md" in inventory
    assert "phase-5-p5.0-baseline-inventory.md" in closure
    assert "d6f66d6d8634aac20b77b9b66a22cbb77370c4fe" in inventory
    assert "d6f66d6d8634aac20b77b9b66a22cbb77370c4fe" in closure


def test_phase5_baseline_preserves_public_and_hearing_limits() -> None:
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")
    public_request = EVIDENCE_SUBMISSION_REQUEST.read_text(encoding="utf-8")
    hearing_request = HEARING_EVIDENCE_BATCH_REQUEST.read_text(encoding="utf-8")
    scope = _batches()["gate"]["engineering_scope"]

    assert "@Size(min = 1, max = 50)" in public_request
    assert "@Size(max = 50)" in hearing_request
    assert scope["public_submission_limit"] == 50
    assert scope["synthetic_manifest_item_counts"] == [1, 8, 100]
    assert any(
        "Hearing supplementation remains unchanged and capped at 50 files per party."
        == invariant
        for invariant in _batches()["gate"]["invariants"]
    )
    for document in (inventory, closure):
        assert "1/8/100" in document
        assert "Hearing" in document and "50" in document
        assert "PENDING_PROMOTION" in document


def test_phase5_baseline_maps_every_entry_gap_without_claiming_entry() -> None:
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")

    for index in range(10):
        gate = f"P5-G{index}"
        assert gate in inventory
        assert gate in closure
    assert "contract_gate: P5.0 NOT_RUN" in inventory
    assert "contract_gate: P5.0 NOT_RUN" in closure
    assert "review_status: CLOSED_WITH_BLOCKERS_CLASSIFIED" in closure
    assert "engineering_execution: BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT" in closure


def test_phase5_review_keeps_d0_and_e0_independent_with_exact_path_closure() -> None:
    batches = _batches()
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")

    assert batches["task_contracts"]["P5-D0"]["depends_on"] == ["P5-0"]
    assert batches["task_contracts"]["P5-E0"]["depends_on"] == ["P5-0"]
    assert "tests/graphs/evidence/**" in closure
    assert "tests/static/test_phase5_*.py" in closure
    assert "exactly one editor" in closure
    assert "R retains shared plan/evidence gates" in closure
