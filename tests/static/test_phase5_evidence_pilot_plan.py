from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
R2_AUTHORIZED_MIGRATION = (
    "java-api-service/src/main/resources/db/migration/"
    "V043_5__evidence_finalization_and_operational_recovery.sql"
)
EXECUTION_PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"
TEST_BATCHES = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
OWNER_BRIEFS = ROOT / "plans/phase-5-owner-briefs.yaml"
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
PRE_ENTRY_CORRECTION = (
    ROOT
    / "docs/architecture/adr/"
    "0013-phase-5-evidence-pre-entry-contract-correction.md"
)


def _batches() -> dict:
    return yaml.safe_load(TEST_BATCHES.read_text(encoding="utf-8"))


def _owner_briefs() -> dict:
    return yaml.safe_load(OWNER_BRIEFS.read_text(encoding="utf-8"))


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

    assert batches["document_status"] == "P5_0_PASS_ENGINEERING_ACTIVE"
    assert gate["required_entry"] == "PHASE_4_ENGINEERING_CHECKPOINT"
    assert gate["engineering_exception"] == "ADR_0012_ACCEPTED"
    assert gate["contract_gate_status"] == "PASS"
    assert gate["entry_decision"] == "ENGINEERING_ONLY"
    assert gate["accepted_entry_state"] == {
        "candidate_commit": "e70492a11e23307382ea762d0e8e7f57ab58870b",
        "evidence_commit": "e5f6019b71a90174c09aecdcba336bd12788b75b",
        "evidence_path": (
            "test-reports/temporal-first/phase-5-entry-20260723-e70492a1/"
            "phase-5-entry"
        ),
        "batch_0_result": "PASS",
        "tests": 396,
        "engineering_execution": (
            "ALLOWED_WITH_DISABLED_JAVA_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS"
        ),
        "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
        "phase_6_permission": "FORBIDDEN",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }
    assert gate["observed_entry_state"]["phase_4_engineering_checkpoint"] == "PASS"
    assert gate["observed_entry_state"]["next_phase_permission"] == (
        "PHASE_5_ENGINEERING_ONLY"
    )
    assert gate["observed_entry_state"]["evidence_v2_closed_contract_set"] == "FROZEN"
    assert gate["accepted_phase4_checkpoint"] == (
        "test-reports/temporal-first/phase-4-20260722-1ba6e17f/phase-4/"
        "phase-metrics.json"
    )
    assert set(gate["contract_candidate_allowed_when"]) == {
        "PHASE_4_ENGINEERING_CHECKPOINT_PASS",
        "PHASE_5_ENGINEERING_ONLY_PERMISSION_RECORDED",
        "ADR_0012_ACCEPTED",
        "EVIDENCE_V2_CLOSED_CONTRACT_SET_VALIDATED",
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
        "0013-phase-5-evidence-pre-entry-contract-correction.md",
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

    for index in range(11):
        gate = f"P5-G{index}"
        assert gate in inventory
        assert gate in closure
    assert "contract_gate: P5.0 NOT_RUN" in inventory
    assert "contract_gate: P5.0 NOT_RUN" in closure
    assert "review_status: CLOSED_WITH_BLOCKERS_CLASSIFIED" in closure
    assert "current_phase_4_engineering_checkpoint: PASS" in closure
    assert "engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE" in closure


def test_phase5_candidate_repairs_are_ledgered_without_inheriting_a_pass() -> None:
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")
    documents = (execution, contract, inventory, closure)

    for document in documents:
        assert (
            "candidate_scope_integrity: "
            "PRE_ENTRY_CONTRACT_CORRECTION_REQUIRES_FRESH_EXACT_SHA_BATCH_0"
        ) in document
        assert "P5.0 NOT_RUN" in document
        assert "EvidenceApiIntegrationTest" in document
        assert "EVIDENCE_OPEN" in document
        assert "b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a" in document
        assert "79b8c797522671aa46f2299198eab7ba6f651006" in document
        assert "occurred_at" in document
        assert "PRODUCT" in document
        assert "FIXTURE" in document

    for commit in (
        "99cdd435",
        "d76fde17",
        "24a705dc",
        "a3be6744",
        "c9e6c7ba",
        "e97e1341",
        "fb69bd4c",
    ):
        assert commit in execution
        assert commit in closure

    assert "SecurityConfiguration" in execution
    assert "SecurityConfiguration" in closure
    assert "withOffsetSameInstant(UTC)" in execution
    assert "withOffsetSameInstant(UTC)" in closure
    assert "27/27" in execution
    assert "27/27" in closure
    assert "source repair SHA" in execution
    assert "no exact-`b9201f0b` test execution is claimed" in execution
    assert "no exact-main test execution" in closure
    assert all("tree-equivalent" not in document for document in documents)
    assert "do not inherit any result" in execution
    assert "No diagnostic run from an earlier SHA is accepted" in closure


def test_phase5_pre_entry_contract_correction_is_accepted_and_atomic() -> None:
    adr = PRE_ENTRY_CORRECTION.read_text(encoding="utf-8")
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")

    assert "# ADR 0013: Phase 5 Evidence Pre-Entry Contract Correction" in adr
    assert "- Status: ACCEPTED" in adr
    assert "exactly one atomic, in-place correction" in adr
    assert "never been accepted" in adr
    assert "released" in adr and "consumed" in adr
    assert "After the first P5.0 acceptance, this exception expires" in adr

    governance_documents = (adr, execution, contract, inventory, closure)
    for document in governance_documents:
        normalized = " ".join(document.split())
        assert "authorization_proof_ref" in normalized
        assert "JOSE_P1363_BASE64URL" in normalized
        assert "ASCII_LOWERCASE_HEX_TEXT" in normalized
        assert "assessment_output_schema_version" in normalized
        assert "terminal_output_schema_version" in normalized
        assert "BEFORE_CHECKPOINT_MUTATION" in normalized
        assert "new schema version" in normalized
        assert "accepted ADR" in normalized

    normalized_adr = " ".join(adr.split())
    assert "not the decoded 32-byte digest" in normalized_adr
    assert "schema `x-signature` metadata" in normalized_adr
    assert "evidence-asset-capability.v1` uses the same signature input" in normalized_adr
    assert "item assessment, terminal proposal, and process projection" in normalized_adr
    assert "capability binds `profile_versions_hash`" in normalized_adr
    assert "finalization receipt" in normalized_adr
    assert "profile_versions" in normalized_adr
    assert "RoomGraphCommand.v1` has no such" in normalized_adr
    assert "current Graph lease fence" in normalized_adr
    assert "Java Finalizer revalidates the room fence" in normalized_adr


def test_phase5_quarantines_green_diagnostic_without_accepted_checkpoint() -> None:
    documents = (
        EXECUTION_PLAN.read_text(encoding="utf-8"),
        CONTRACT_PACK.read_text(encoding="utf-8"),
        BASELINE_INVENTORY.read_text(encoding="utf-8"),
        REVIEW_CLOSURE.read_text(encoding="utf-8"),
    )

    for document in documents:
        normalized = " ".join(document.split())
        assert "45d7f087eafe4f50be0d491b3d612446a3e1e94e" in normalized
        assert "static 122" in normalized
        assert "Python 61" in normalized
        assert "Java 67" in normalized
        assert "frontend 97" in normalized
        assert "347" in normalized
        assert "quarantined" in normalized
        assert "P5.0 NOT_RUN" in normalized
        assert "status=PASS" in normalized
        assert "batch_0=PASS" in normalized
        assert "accepted=true" in normalized
        assert "contract_gate=P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT" in normalized
        assert "Source artifacts exist locally" in normalized
        assert "no repository P5.0 entry-evidence" in normalized
        assert "assembled, committed, or accepted" in normalized
        assert "overrides" in normalized and "entry-gate purposes" in normalized
        assert (
            "candidate_scope_integrity: "
            "PRE_ENTRY_CONTRACT_CORRECTION_REQUIRES_FRESH_EXACT_SHA_BATCH_0"
        ) in normalized

    adr = PRE_ENTRY_CORRECTION.read_text(encoding="utf-8")
    assert "never been accepted" in adr
    assert "selected by a runtime epoch" in adr
    assert "consumed by a compatible reader" in " ".join(adr.split())
    assert "retroactively quarantines the complete run" in adr
    assert "After the first P5.0 acceptance, this exception expires" in adr
    assert "full from a new exact clean detached SHA" in adr


def test_phase5_contract_correction_does_not_relax_runtime_or_promotion() -> None:
    adr = PRE_ENTRY_CORRECTION.read_text(encoding="utf-8")
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    inventory = BASELINE_INVENTORY.read_text(encoding="utf-8")
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")

    assert "Runtime remains `DISABLED` or Java-signed synthetic `SHADOW`" in adr
    assert "`TEMPORAL` Evidence allocation" in adr
    assert "formal Graph sink" in adr
    assert "canary" in adr and "promotion remain forbidden" in adr
    assert "Java and Domain PostgreSQL remain the only formal Evidence" in adr
    assert "`MIG-004` and `MIG-005` remain `PENDING_PROMOTION`" in adr
    for document in (execution, contract, inventory, closure):
        normalized = " ".join(document.split())
        assert "P5.0 NOT_RUN" in normalized
        assert "DISABLED" in normalized
        assert "Java-signed synthetic `SHADOW`" in normalized
        assert "TEMPORAL" in normalized
        assert "formal" in normalized and "sink" in normalized
        assert "canary" in normalized and "promotion" in normalized
        assert "PENDING_PROMOTION" in normalized


def test_phase5_governance_documents_share_final_snapshot_and_output_contract() -> None:
    documents = (
        PRE_ENTRY_CORRECTION.read_text(encoding="utf-8"),
        EXECUTION_PLAN.read_text(encoding="utf-8"),
        CONTRACT_PACK.read_text(encoding="utf-8"),
        BASELINE_INVENTORY.read_text(encoding="utf-8"),
        REVIEW_CLOSURE.read_text(encoding="utf-8"),
    )
    final_contract = (
        "snapshot_payload_hash_scope: FULL_RFC8785_CANONICAL_SIGNED_MANIFEST_BYTES",
        "snapshot_payload_size_scope: EXACT_FULL_CANONICAL_SIGNED_MANIFEST_BYTES",
        "snapshot_payload_uri: IMMUTABLE_CONTENT_ADDRESSED_BY_SNAPSHOT_SHA256",
        "internal_manifest_hash_scope: RFC8785_OMIT_MANIFEST_HASH_AND_SIGNATURE",
        "snapshot_and_internal_hashes_interchangeable: false",
        "room_graph_command_output_schema_version: evidence-batch-proposal.v1",
        "graph_registry_output_schema_version: evidence-batch-proposal.v1",
        "item_lcel_parser_output_schema_version: evidence-item-assessment.v1",
        "java_room_fence_source: SIGNED_MANIFEST",
        "graph_lease_fence_source: CURRENT_GRAPH_LEASE",
        "fence_tokens_interchangeable: false",
        "engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE",
    )

    for document in documents:
        normalized = " ".join(document.split())
        for contract_line in final_contract:
            assert contract_line in normalized
        assert "P5.0 NOT_RUN" in normalized
        assert "BLOCKED" in normalized
        assert "45d7f087eafe4f50be0d491b3d612446a3e1e94e" in normalized


def test_phase5_review_keeps_d0_and_e0_independent_with_exact_path_closure() -> None:
    batches = _batches()
    closure = REVIEW_CLOSURE.read_text(encoding="utf-8")

    assert batches["task_contracts"]["P5-D0"]["depends_on"] == ["P5-0"]
    assert batches["task_contracts"]["P5-E0"]["depends_on"] == ["P5-0"]
    assert "tests/graphs/evidence/**" in closure
    assert "tests/static/test_phase5_*.py" in closure
    assert "exactly one editor" in closure
    assert "R retains shared plan/evidence gates" in closure


def test_phase5_owner_briefs_unlock_only_wave_a_after_entry_evidence() -> None:
    briefs = _owner_briefs()

    assert briefs["document_status"] == "P5_0_PASS_ENGINEERING_ACTIVE"
    assert briefs["entry_gate"]["status"] == "PASS"
    assert briefs["entry_gate"]["accepted_candidate_commit"] == (
        "e70492a11e23307382ea762d0e8e7f57ab58870b"
    )
    assert briefs["entry_gate"]["entry_evidence_commit"] == (
        "e5f6019b71a90174c09aecdcba336bd12788b75b"
    )
    assert briefs["entry_gate"]["engineering_permission"] == (
        "PHASE_5_ENGINEERING_ONLY"
    )
    assert briefs["entry_gate"]["phase_6_permission"] == "FORBIDDEN"
    assert briefs["entry_gate"]["required_before_dispatch"] == [
        "PHASE_4_ENGINEERING_CHECKPOINT_PASS",
        "PHASE_5_ENGINEERING_ONLY_PERMISSION_RECORDED",
        "P5_0_CONTRACT_CANDIDATE_COMMITTED",
        "P5_0_BATCH_0_PASSED_ON_EXACT_CONTRACT_CANDIDATE_SHA",
        "P5_0_ENTRY_EVIDENCE_COMMITTED",
    ]
    assert set(briefs["owners"]) == {"A", "B", "C", "D", "E"}
    assert briefs["wave_a_parallel_launch"]["status"] == "READY"
    assert briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"]["status"] == "OPEN"
    shared = briefs["shared_contract_owner"]
    assert shared["owner"] == "R"
    assert shared["delegated_owners_may_edit"] is False
    assert shared["contract_candidate_draft_owner"] == "A"
    assert shared["contract_candidate_integration_owner"] == "R"
    assert shared["contract_candidate_draft_gate"] == "P5_0_CONTRACT_CANDIDATE"
    assert shared["contract_candidate_draft_paths"] == [
        "contracts/agent-platform/evidence/v2/",
        "tests/static/test_phase5_evidence_contracts.py",
        "java-api-service/src/test/java/com/example/dispute/workflow/contract/v1/"
        "EvidenceV2ContractFixtureTest.java",
    ]


def test_phase5_owner_briefs_repeat_non_negotiable_scope_guards() -> None:
    briefs = _owner_briefs()

    for owner in briefs["owners"].values():
        guard = owner["scope_guard"]
        assert owner["status"] == "READY_FOR_WAVE_A_DISPATCH"
        assert guard["public_evidence_submission_max"] == 50
        assert guard["closed_synthetic_manifest_counts"] == [1, 8, 100]
        assert guard["closed_synthetic_100_is_public_contract"] is False
        assert guard["formal_evidence_sink_allowed"] is False
        assert guard["temporal_evidence_allocation_allowed"] is False
        assert guard["allowed_new_runtime_modes"] == [
            "DISABLED",
            "SIGNED_SYNTHETIC_SHADOW",
        ]
        assert guard["asset_mode"] == "JAVA_SIGNED_SYNTHETIC_CAPABILITY_ONLY"
        assert guard["hearing_supplement_max_per_party_unchanged"] == 50


def test_phase5_wave_a_initial_owner_write_sets_are_exact_and_disjoint() -> None:
    briefs = _owner_briefs()
    initial_tasks = briefs["wave_a_parallel_launch"]["simultaneously_active_tasks"]
    assert initial_tasks == ["P5-A1", "P5-B1", "P5-C1", "P5-D0", "P5-E0"]

    seen: dict[str, str] = {}
    for task_id in initial_tasks:
        owner_id = task_id.split("-")[1][0]
        task = briefs["owners"][owner_id]["tasks"][task_id]
        assert task["initial_parallel"] is True
        assert task["depends_on"] == ["P5-0"]
        assert task["owned_files"]
        for path in task["owned_files"]:
            assert not any(token in path for token in ("*", "?", "[", "]"))
            assert path not in seen, f"{path} is shared by {seen[path]} and {task_id}"
            seen[path] = task_id


def test_phase5_every_delegated_task_is_executable_and_path_bounded() -> None:
    briefs = _owner_briefs()
    token_classes = set(briefs["test_token_policy"]["required_resource_classes"])
    path_owners: dict[str, str] = {}
    e1_takeover_paths = set(
        next(
            item
            for item in briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"][
                "path_takeovers"
            ]
            if item["owner"] == "E"
        )["files"]
    )

    for owner_id, owner in briefs["owners"].items():
        assert owner["review_partner"] != owner_id
        assert owner["forbidden_path_prefixes"]
        assert owner["forbidden_files"]
        for forbidden in owner["forbidden_path_prefixes"] + owner["forbidden_files"]:
            assert not any(token in forbidden for token in ("*", "?", "[", "]"))
        for task_id, task in owner["tasks"].items():
            assert task_id.startswith(f"P5-{owner_id}")
            assert task["input_contracts"]
            assert task["output_contracts"]
            assert task["owned_files"]
            assert task["t0_commands"]
            assert len(task["commit_definition_of_done"]) >= 4
            for path in task["owned_files"]:
                assert not any(token in path for token in ("*", "?", "[", "]"))
                previous_owner = path_owners.setdefault(path, owner_id)
                assert previous_owner == owner_id or (
                    previous_owner == "A"
                    and owner_id == "E"
                    and task_id == "P5-E1"
                    and path in e1_takeover_paths
                ), (
                    f"{path} crosses delegated owners {previous_owner} and {owner_id}"
                )
            for command in task["t0_commands"]:
                assert command["workdir"]
                assert command["argv"]
                assert command["max_duration_seconds"] <= 180
                assert command["test_token_required"] == (
                    command["resource_class"] in token_classes
                )


def test_phase5_d1_e1_takeovers_require_the_wave_a_integration_barrier() -> None:
    briefs = _owner_briefs()
    barrier = briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"]

    assert barrier["status"] == "OPEN"
    assert "P5_BATCH_1_PASS_ON_MERGED_SHA" in barrier["prerequisites"]
    transfers = {item["owner"]: item for item in barrier["path_takeovers"]}
    assert set(transfers) == {"D", "E"}
    for owner_id, next_task in (("D", "P5-D1"), ("E", "P5-E1")):
        transfer = transfers[owner_id]
        task = briefs["owners"][owner_id]["tasks"][next_task]
        assert "P5-WAVE-A-INTEGRATED" in task["depends_on"]
        assert transfer["to_task"] == next_task
        assert set(transfer["files"]).issubset(task["owned_files"])
    assert transfers["E"]["from_tasks"] == ["P5-A1", "P5-A2", "P5-E0"]
    assert {
        "python-agent-service/app/graphs/evidence/runtime.py",
        "python-agent-service/app/graphs/evidence/nodes.py",
        "python-agent-service/app/graphs/evidence/graph.py",
    }.issubset(transfers["E"]["files"])


def test_phase5_owner_brief_maven_selectors_are_single_argv_tokens() -> None:
    briefs = _owner_briefs()

    for owner in briefs["owners"].values():
        for task in owner["tasks"].values():
            for command in task["t0_commands"]:
                argv = command["argv"]
                if argv[0] != "./mvnw.cmd":
                    continue
                selectors = [token for token in argv if token.startswith("-Dtest=")]
                assert len(selectors) == 1
                assert not [
                    token
                    for token in argv
                    if not token.startswith("-Dtest=") and token.endswith("Test")
                ]


def test_phase5_wave_b_requires_durable_java_receipts_before_projection() -> None:
    briefs = _owner_briefs()
    batches = _batches()
    c3 = briefs["owners"]["C"]["tasks"]["P5-C3"]
    b3 = briefs["owners"]["B"]["tasks"]["P5-B3"]
    d1 = briefs["owners"]["D"]["tasks"]["P5-D1"]

    assert c3["depends_on"] == ["P5-C2", "P5-R2", "P5-WAVE-A-INTEGRATED"]
    assert b3["depends_on"] == ["P5-B2", "P5-C3", "P5-WAVE-A-INTEGRATED"]
    assert {
        "java-api-service/src/main/java/com/example/dispute/evidence/application/graph/"
        "EvidenceFinalizationReceiptLookup.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/evidence/"
        "EvidenceRoomActivities.java",
    }.issubset(c3["owned_files"] + b3["owned_files"])
    assert (
        "java-api-service/src/main/resources/db/migration/"
        "V043_4__evidence_graph_bindings.sql"
        in briefs["owners"]["C"]["forbidden_files"]
    )
    assert {"P5-C2", "P5-C3", "P5-B3", "P5-WAVE-A-INTEGRATED"}.issubset(
        d1["depends_on"]
    )
    c3_text = " ".join(c3["output_contracts"] + c3["commit_definition_of_done"])
    assert "trusted current-authority snapshot/lock" in c3_text
    assert "immutable actual-load receipts" in c3_text
    assert "atomically" in c3_text
    assert "stale/takeover races" in c3_text
    assert "same operationKey with a different requestHash conflict" in c3_text
    assert "before checking current authority or Graph lease" in c3_text
    assert "Temporal History" in c3_text
    assert "Temporal memory" in " ".join(b3["commit_definition_of_done"])
    assert batches["waves"]["wave_a"]["delegated_tasks"] == [
        "P5-A1",
        "P5-A2",
        "P5-B1",
        "P5-B2",
        "P5-C1",
        "P5-C2",
        "P5-D0",
        "P5-E0",
    ]


def test_phase5_d1_projection_route_is_private_and_controller_bound() -> None:
    briefs = _owner_briefs()
    d1 = briefs["owners"]["D"]["tasks"]["P5-D1"]
    text = " ".join(d1["output_contracts"] + d1["commit_definition_of_done"])
    selector = next(
        command["argv"]
        for command in d1["t0_commands"]
        if command["id"] == "D1_MAVEN_TEST"
    )

    assert (
        "java-api-service/src/main/java/com/example/dispute/workflow/projection/evidence/"
        "EvidenceProcessProjectionQuery.java" in d1["owned_files"]
    )
    assert "java-api-service/src/main/java/com/example/dispute/evidence/api/EvidenceController.java" in d1[
        "owned_files"
    ]
    assert "EvidenceRoomControllerTest" in selector[2]
    assert "authenticated" in text and "private" in text and "no-store" in text
    assert (
        "java-api-service/src/main/java/com/example/dispute/evidence/api/"
        "InternalEvidenceController.java"
        in briefs["owners"]["D"]["forbidden_files"]
    )


def test_phase5_e1_is_durable_graph_permit_work_not_java_bulkhead_authority() -> None:
    briefs = _owner_briefs()
    batches = _batches()
    e1 = briefs["owners"]["E"]["tasks"]["P5-E1"]
    paths = set(e1["owned_files"])
    text = " ".join(e1["output_contracts"] + e1["commit_definition_of_done"])
    maven = next(
        command["argv"]
        for command in e1["t0_commands"]
        if command["id"] == "E1_MAVEN_TEST"
    )

    assert {
        "python-agent-service/migrations/graph/G004_graph_fanout_bulkhead.sql",
        "python-agent-service/app/graph_runtime/postgres_bulkhead.py",
        "python-agent-service/app/graph_runtime/bulkhead.py",
        "python-agent-service/app/graph_runtime/errors.py",
        "python-agent-service/app/graph_runtime/migrations.py",
        "python-agent-service/app/graph_runtime/readiness.py",
        "python-agent-service/app/graph_runtime/restore_validation.py",
        "python-agent-service/app/api/graph_lifecycle.py",
        "python-agent-service/app/graph_runtime/production_bindings.py",
        "python-agent-service/app/graphs/evidence/runtime.py",
        "python-agent-service/app/graphs/evidence/nodes.py",
        "python-agent-service/app/graphs/evidence/graph.py",
    }.issubset(paths)
    assert "database-owned fair queue" in text
    assert "room tenant and global atomic permits" in text
    assert "current Graph lease" in text
    assert "no fallback" in text
    assert "bounded labels" in text
    assert "local signed-synthetic parity" in text
    assert e1["depends_on"] == batches["task_contracts"]["P5-E1"]["depends_on"]
    assert all(
        name in maven[2]
        for name in (
            "EvidenceBulkheadPolicyTest",
            "EvidenceNoFormalSinkGuardTest",
            "EvidenceBulkheadIntegrationTest",
            "EvidenceShadowParityServiceTest",
        )
    )
    batch_2 = batches["batches"]["P5-BATCH-2"]
    assert batch_2["requires_tasks"] == [
        "P5-R2",
        "P5-B3",
        "P5-C3",
        "P5-D1",
        "P5-D2",
        "P5-E1",
        "P5-E2",
    ]
    assert "python-agent-service/tests/graphs/evidence/test_recovery.py" in batch_2[
        "planned_python_tests"
    ]
    assert "frontend/src/stores/evidence.test.js" in batch_2["frontend_tests"]
    assert "tests/static/test_phase5_evidence_selector.py" in batch_2[
        "planned_static_tests"
    ]
    assert "python-agent-service/tests/graph_runtime/unit/test_checkpoint_migrations.py" in batch_2[
        "planned_python_tests"
    ]
    assert {
        "python-agent-service/tests/graph_runtime/unit/test_gateway_recovery.py",
        "python-agent-service/tests/graph_runtime/unit/test_production_bindings.py",
    }.issubset(batch_2["planned_python_tests"])
    assert {
        "EvidenceProcessProjectionAdapterTest",
        "EvidenceRoomControllerTest",
        "EvidenceBulkheadPolicyTest",
        "EvidenceNoFormalSinkGuardTest",
    }.issubset(batch_2["planned_java_test_classes"])


def test_phase5_r2_is_the_only_pre_c3_migration_authorization_gate() -> None:
    briefs = _owner_briefs()
    batches = _batches()
    r2 = batches["task_contracts"]["P5-R2"]
    r3 = batches["task_contracts"]["P5-R3"]
    gate = briefs["primary_integration_only"]["post_wave_a_migration_contract_gate"]
    c3 = briefs["owners"]["C"]["tasks"]["P5-C3"]

    assert r2["depends_on"] == ["P5-R1", "P5-WAVE-A-INTEGRATED"]
    assert r2["authorized_migration_path"] == R2_AUTHORIZED_MIGRATION
    assert r3["depends_on"] == ["P5-D2", "P5-E2"]
    assert batches["waves"]["candidate_wave"]["tasks"] == ["P5-R3"]
    assert gate["status"] == "ACCEPTED"
    assert gate["authorized_migration_path"] == R2_AUTHORIZED_MIGRATION
    assert gate["accepted_candidate_commit"] == (
        "c2c6e51c3f099ecbe867679b75a44a5b6ffb736e"
    )
    assert "P5-R2" in c3["depends_on"]
    assert R2_AUTHORIZED_MIGRATION in c3["owned_files"]
    assert (
        "java-api-service/src/main/resources/db/migration/"
        "V043_4__evidence_graph_bindings.sql"
        in gate["forbidden_paths"]
    )
    assert gate["candidate_and_evidence_paths"] == r2["exact_paths"]
    assert gate["t0_commands"] == [r2["source_command"]]
    assert "separate_evidence_commit_records_candidate_sha_concrete_filename_checksums_and_artifact_sha256" in r2[
        "definition_of_done"
    ]


def test_phase5_batch2_is_serialized_and_executes_each_wave_b_owner_scope() -> None:
    batches = _batches()
    batch_2 = batches["batches"]["P5-BATCH-2"]
    commands = {item["id"]: item for item in batch_2["source_commands"]}

    assert batch_2["execution"]["runner_execution"] == "serial"
    assert batch_2["execution"]["heavy_parallelism"] == 1
    assert batch_2["execution"]["light_parallelism"] == 2
    assert batch_2["execution"]["command_order"] == list(commands)
    assert set(commands) == {
        "p5_wave_b_python",
        "p5_wave_b_postgresql",
        "p5_wave_b_java",
        "p5_wave_b_frontend",
        "p5_wave_b_static",
    }
    for command in commands.values():
        assert command["workdir"]
        assert command["argv"]
        assert command["max_duration_seconds"] <= 180
        assert command["test_token_required"] == (
            command["resource_class"]
            in _owner_briefs()["test_token_policy"]["required_resource_classes"]
        )
    assert commands["p5_wave_b_postgresql"]["purpose"] == (
        "direct_postgresql_g004_graph_fanout_bulkhead_queue_integration"
    )
    java_selector = next(
        token
        for token in commands["p5_wave_b_java"]["argv"]
        if token.startswith("-Dtest=")
    )
    for test_name in (
        "EvidenceFinalizationReceiptLookupTest",
        "EvidenceRoomActivitiesReconciliationTest",
        "EvidenceProcessProjectionAdapterTest",
        "EvidenceRoomControllerTest",
        "EvidenceBulkheadPolicyTest",
        "EvidenceNoFormalSinkGuardTest",
        "EvidenceTemporalCutoverIntegrationTest",
        "EvidenceCutoverRollbackTest",
    ):
        assert test_name in java_selector


def test_phase5_batch2_planned_selectors_equal_declared_source_commands() -> None:
    batch_2 = _batches()["batches"]["P5-BATCH-2"]
    commands = {item["id"]: item for item in batch_2["source_commands"]}

    python_selectors = {
        token
        for command_id in ("p5_wave_b_python", "p5_wave_b_postgresql")
        for token in commands[command_id]["argv"]
        if isinstance(token, str) and token.startswith("tests/")
    }
    assert python_selectors == {
        path.removeprefix("python-agent-service/")
        for path in batch_2["planned_python_tests"]
    }

    java_selector = next(
        token
        for token in commands["p5_wave_b_java"]["argv"]
        if token.startswith("-Dtest=")
    )
    assert set(java_selector.removeprefix("-Dtest=").split(",")) == set(
        batch_2["planned_java_test_classes"]
    )

    frontend_selectors = {
        token
        for token in commands["p5_wave_b_frontend"]["argv"]
        if isinstance(token, str) and token.startswith("src/") and token.endswith(".test.js")
    }
    assert frontend_selectors == {
        path.removeprefix("frontend/") for path in batch_2["frontend_tests"]
    }

    static_selectors = {
        token
        for token in commands["p5_wave_b_static"]["argv"]
        if isinstance(token, str) and token.startswith("tests/")
    }
    assert static_selectors == set(batch_2["planned_static_tests"])


def test_phase5_owner_briefs_reserve_shared_paths_for_primary_integration() -> None:
    briefs = _owner_briefs()
    primary = briefs["primary_integration_only"]
    primary_paths = set(primary["exact_paths"])
    delegated_paths = {
        path
        for owner in briefs["owners"].values()
        for task in owner["tasks"].values()
        for path in task["owned_files"]
    }

    assert primary_paths.isdisjoint(delegated_paths)
    assert "tests/static/test_phase5_evidence_pilot_plan.py" in primary_paths
    assert (
        "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java"
        in primary_paths
    )
    foundation = primary["prebuilt_foundation_candidates"]
    assert foundation == [
        {
            "commit": "09d65875ff6edfbc76d0d2a0e42610690e500bfd",
            "reviewed_chain": [
                "ca18e53e6f051004d20c6f8879f6ed440ab0dc20",
                "09d65875ff6edfbc76d0d2a0e42610690e500bfd",
            ],
            "purpose": (
                "Process-local hierarchical fanout primitives with the Evidence "
                "room cap fixed at eight."
            ),
            "integration_gate": (
                "SATISFIED_BY_e5f6019b71a90174c09aecdcba336bd12788b75b"
            ),
            "runtime_effect": "NONE_UNTIL_EXPLICIT_EVIDENCE_WIRING",
            "GRAPH-016": "PARTIAL_ENGINEERING_PROCESS_LOCAL_ONLY",
            "cross_replica_tenant_global_closure": "PENDING_P5_E1",
            "exact_paths": [
                "python-agent-service/app/graph_runtime/bulkhead.py",
                "python-agent-service/app/graph_runtime/errors.py",
                "python-agent-service/tests/graph_runtime/unit/test_bulkhead.py",
            ],
            "required_checks": [
                "P0_REVIEW_PASS",
                "GRAPH_RUNTIME_UNIT_PASS",
                "STATIC_IMPORT_BOUNDARY_PASS",
            ],
        }
    ]
    e1_paths = set(briefs["owners"]["E"]["tasks"]["P5-E1"]["owned_files"])
    assert set(foundation[0]["exact_paths"]).issubset(e1_paths)
    assert _batches()["waves"]["wave_a"]["status"] == "INTEGRATED"
    assert _batches()["waves"]["wave_b"]["status"] == "READY"


def test_phase5_batch0_source_commands_execute_every_declared_baseline_suite() -> None:
    batch = _batches()["batches"]["P5-BATCH-0"]
    commands = {item["id"]: item for item in batch["source_commands"]}

    assert list(commands) == [
        "p5_entry_static",
        "p5_entry_python",
        "p5_entry_java",
        "p5_entry_frontend",
    ]
    assert [item["report"] for item in commands.values()] == batch["execution"][
        "source_reports"
    ]
    for path in batch["static_tests"]:
        assert path in commands["p5_entry_static"]["command"]
    for path in batch["baseline_suites"]["python"]:
        relative = path.removeprefix("python-agent-service/")
        assert relative in commands["p5_entry_python"]["command"]
    for path in batch["baseline_suites"]["frontend"]:
        relative = path.removeprefix("frontend/")
        assert relative in commands["p5_entry_frontend"]["command"]
    for class_name in batch["baseline_suites"]["java"]:
        assert class_name in commands["p5_entry_java"]["command"]


def test_phase5_batch0_java_selectors_are_exact_and_deduplicated() -> None:
    batch = _batches()["batches"]["P5-BATCH-0"]
    java = next(item for item in batch["source_commands"] if item["id"] == "p5_entry_java")
    selector = next(
        token for token in java["command"].split() if token.startswith("-Dtest=")
    )
    classes = selector.removeprefix("-Dtest=").split(",")

    assert len(classes) == len(set(classes))
    assert set(classes) == set(batch["baseline_suites"]["java"])


def test_phase5_batch0_runner_is_candidate_bound_and_frontend_missing_is_infra() -> None:
    batch = _batches()["batches"]["P5-BATCH-0"]
    execution = batch["execution"]
    frontend = next(
        item for item in batch["source_commands"] if item["id"] == "p5_entry_frontend"
    )

    assert execution["runner"] == "scripts/run_phase5_entry_checkpoint.py"
    assert execution["fresh_run_directory_required"] is True
    assert execution["sealed_manifest_required"] is True
    assert execution["exact_candidate_sha_required"] is True
    assert execution["candidate_sha_must_be_clean_and_detached"] is True
    assert execution["same_sha_retry_allowed_only_for"] == "INFRA"
    assert execution["phase4_checkpoint_argument"].startswith("--phase4-checkpoint=")
    assert execution["execute_ready_matrix_state"] == {
        "document_status": "P5_0_CONTRACT_CANDIDATE_AWAITING_BATCH0",
        "phase_4_engineering_checkpoint": "PASS",
        "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
        "entry_decision": "READY_FOR_P5_BATCH_0",
    }
    assert execution["execute_while_matrix_blocked_or_not_recorded"] == (
        "forbidden_before_source_invocation"
    )
    assert execution["heavy_parallelism"] == 1
    assert execution["light_parallelism"] == 2
    assert frontend["preflight"]["required_path"] == (
        "frontend/node_modules/vitest/vitest.mjs"
    )
    assert frontend["preflight"]["missing_classification"] == "INFRA"
    assert "pnpm-lock.yaml" in frontend["preflight"]["action"]
    assert "pnpm install --frozen-lockfile" in frontend["preflight"]["action"]
    assert "weaken" in frontend["preflight"]["forbidden_action"]
