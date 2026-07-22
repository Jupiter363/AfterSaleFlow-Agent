from __future__ import annotations

import ast
import fnmatch
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "plans/phase-4-intake-pilot-test-batches.yaml"
POLICY = ROOT / "docs/runbooks/temporal-first/phase-4-engineering-evidence-policy.yaml"
JAVA_TEST_ROOT = ROOT / "java-api-service/src/test/java"
PYTHON_TEST_ROOT = ROOT / "python-agent-service"
SOURCE_REPORTS = {
    "python-phase4-junit.xml",
    "java-phase4-junit.xml",
    "frontend-phase4-junit.xml",
    "static-phase4-junit.xml",
}


def _load(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _matrix_check_ids(matrix: dict) -> set[str]:
    return {
        check_id for check_ids in matrix["check_ids"].values() for check_id in check_ids
    }


def _matrix_baseline_ids(matrix: dict) -> set[str]:
    return {
        baseline_id
        for baseline_ids in matrix["baseline_ids"].values()
        for baseline_id in baseline_ids
    }


def _expected_statuses(matrix: dict) -> dict[str, str]:
    statuses = {check_id: "PASS_ENGINEERING" for check_id in _matrix_check_ids(matrix)}
    for status in ("PARTIAL_ENGINEERING", "PENDING_PROMOTION"):
        values = matrix["claim_status_policy"][status]
        for check_id in values:
            statuses[check_id] = status
    return statuses


def _merged_mapping(policy: dict, check_id: str) -> dict:
    prefix = check_id.split("-", 1)[0]
    return {**policy["defaults"][prefix], **policy["overrides"][check_id]}


def _python_tests(path: Path) -> set[str]:
    module = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    return {
        node.name
        for node in module.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name.startswith("test_")
    }


def _java_test_source(class_pattern: str) -> Path:
    matches = [
        path
        for path in JAVA_TEST_ROOT.rglob("*.java")
        if fnmatch.fnmatch(path.stem, class_pattern)
    ]
    assert len(matches) == 1, (class_pattern, matches)
    return matches[0]


def _selector_evidence(selector: str) -> str:
    if selector.startswith("tests.static."):
        return "static-phase4-junit.xml"
    if selector.startswith("tests."):
        return "python-phase4-junit.xml"
    if selector.startswith("frontend/"):
        return "frontend-phase4-junit.xml"
    return "java-phase4-junit.xml"


def _assert_selector_exists(owner_id: str, selector: str) -> None:
    class_pattern, separator, test_pattern = selector.partition("#")
    assert separator, (owner_id, selector)
    if class_pattern.startswith("tests."):
        service_root = (
            ROOT if class_pattern.startswith("tests.static.") else PYTHON_TEST_ROOT
        )
        path = service_root / Path(*class_pattern.split(".")).with_suffix(".py")
        assert path.is_file(), (owner_id, selector, path)
        assert any(
            fnmatch.fnmatch(test_name, test_pattern)
            for test_name in _python_tests(path)
        ), (owner_id, selector)
        return
    if class_pattern.startswith("frontend/"):
        path = ROOT / class_pattern
        assert path.is_file(), (owner_id, selector, path)
        assert f'it("{test_pattern}"' in path.read_text(encoding="utf-8"), (
            owner_id,
            selector,
        )
        return

    path = _java_test_source(class_pattern)
    source = path.read_text(encoding="utf-8")
    assert f"void {test_pattern}(" in source, (owner_id, selector, path)


def test_policy_maps_every_phase4_check_id_to_the_matrix_status() -> None:
    matrix = _load(MATRIX)
    policy = _load(POLICY)
    check_ids = _matrix_check_ids(matrix)

    assert policy["schema_version"] == "phase4-engineering-evidence-policy.v1"
    assert set(policy["overrides"]) == check_ids
    expected_statuses = _expected_statuses(matrix)
    for check_id in check_ids:
        mapping = _merged_mapping(policy, check_id)
        assert mapping["status"] == expected_statuses[check_id]
        assert mapping["test_selectors"], check_id
        assert set(mapping["evidence"]) <= SOURCE_REPORTS


def test_policy_maps_every_phase4_baseline_without_blanket_passes() -> None:
    matrix = _load(MATRIX)
    policy = _load(POLICY)
    baseline_ids = _matrix_baseline_ids(matrix)

    assert set(policy["baseline_overrides"]) == baseline_ids
    partial_ids = {"SEC-004", "SEC-006", "UI-001", "UI-003", "UI-004"}
    for baseline_id, mapping in policy["baseline_overrides"].items():
        expected = (
            "PARTIAL_ENGINEERING" if baseline_id in partial_ids else "PASS_ENGINEERING"
        )
        assert mapping["status"] == expected
        assert mapping["test_selectors"], baseline_id
        assert set(mapping["evidence"]) <= SOURCE_REPORTS
        if mapping["status"] == "PARTIAL_ENGINEERING":
            assert mapping.get("note"), baseline_id


def test_policy_is_synthetic_only_and_cannot_promote_migrations() -> None:
    policy = _load(POLICY)

    assert policy["scope"] == "SIGNED_SYNTHETIC_SHADOW_ENGINEERING_ONLY"
    assert policy["runtime_modes_allowed"] == [
        "DISABLED",
        "SIGNED_SYNTHETIC_SHADOW",
    ]
    assert policy["formal_writer_allowed"] is False
    assert policy["real_case_shadow_allowed"] is False
    assert policy["temporal_intake_allocation_allowed"] is False
    assert policy["canary_allowed"] is False
    assert policy["defaults"]["MIG"]["status"] == "PENDING_PROMOTION"
    assert policy["promotion_gates"] == {
        "MIG-003": {
            "status": "PENDING_PROMOTION",
            "note": "Phase 3 production promotion remains independently gated.",
        },
        "MIG-004": {
            "status": "PENDING_PROMOTION",
            "depends_on": ["MIG-003"],
            "note": "Phase 4 engineering PASS never implies Intake promotion PASS.",
        },
    }
    assert all(
        gate["status"] == "PENDING_EXTERNAL" for gate in policy["external_gates"]
    )


def test_signed_synthetic_chain_is_required_by_the_candidate_without_promotion() -> None:
    matrix = _load(MATRIX)
    policy = _load(POLICY)
    chain = policy["signed_synthetic_chain_evidence"]
    required = set(chain["required_java_test_classes"])

    assert chain["claim_ceiling"] == "ENGINEERING_ONLY"
    assert chain["evidence"] == "java-phase4-junit.xml"
    assert chain["promotion_effect"] == "NONE"
    assert required == {
        "SignedSyntheticIntakeDriverTest",
        "SignedSyntheticIntakeIngressServiceTest",
        "SignedSyntheticIntakeBridgeReadPortDecoratorTest",
        "IntakeSyntheticAdmissionTrustPropertiesTest",
        "Es256IntakeSyntheticAdmissionVerifierTest",
        "JdbcIntakeSignedSyntheticAdmissionPortIntegrationTest",
        "JdbcIntakeSyntheticRuntimeSourceTest",
        "IntakeSyntheticRuntimeAdaptersTest",
        "IntakeExchangeP0Test",
        "IntakeChildBridgeAuthorityAdapterTest",
        "IntakeChildBridgeActivitiesTest",
        "IntakeSyntheticShadowConfigurationTest",
        "TemporalWorkerConfigurationTest",
    }
    assert all(_java_test_source(class_name).is_file() for class_name in required)

    batch_2 = matrix["batches"]["P4-BATCH-2"]
    assert required <= set(batch_2["java_test_classes"])
    java_candidate = next(
        command
        for command in matrix["batches"]["P4-BATCH-3"]["source_commands"]
        if command["id"] == "java_phase_4"
    )
    assert "P4-BATCH-2" in java_candidate["inherits_java_test_classes_from"]
    assert matrix["gate"]["traffic_constraints"]["promotion_allowed"] is False
    assert matrix["external_gates"]["current_engineering_result_must_report"] == {
        "promotion_gate": "PENDING",
        "MIG-003": "PENDING_PROMOTION",
        "MIG-004": "PENDING_PROMOTION",
    }


def test_policy_selectors_resolve_to_concrete_candidate_tests() -> None:
    policy = _load(POLICY)

    mappings = {**policy["overrides"], **policy["baseline_overrides"]}
    for owner_id, mapping in mappings.items():
        resolved = (
            _merged_mapping(policy, owner_id)
            if owner_id in policy["overrides"]
            else mapping
        )
        for selector in mapping["test_selectors"]:
            _assert_selector_exists(owner_id, selector)
            assert _selector_evidence(selector) in resolved["evidence"]


def test_migration_mapping_names_all_recovery_and_rollback_boundaries() -> None:
    policy = _load(POLICY)
    selectors = set(policy["overrides"]["MIG-004"]["test_selectors"])

    assert selectors == {
        "tests.graphs.intake.test_recovery#test_resume_from_pre_model_checkpoint_invokes_cognition_once",
        "tests.graphs.intake.test_recovery#test_replacement_graph_resumes_after_model_without_a_second_invocation",
        "tests.graphs.intake.test_recovery#test_projected_proposal_survives_a_crash_before_terminal_checkpoint",
        "tests.graphs.intake.test_recovery#test_terminal_checkpoint_is_reused_after_response_loss",
        "*IntakeCutoverRollbackTest#preTerminalRollbackCreatesHigherFencedLegacyEpochAndRejectsStaleWriter",
        "*IntakeCutoverRollbackTest#postInitiatorRollbackPreservesEffectsAndResumesRespondentOnly",
        "*IntakeCutoverRollbackTest#postEvidenceRollbackReusesReceiptAndNeverReopensIntake",
        "*IntakeCutoverRollbackTest#everyRollbackBoundaryRetainsExactlyOneActiveWriter",
    }


def test_service_free_rollback_harness_is_model_evidence_only() -> None:
    policy = _load(POLICY)
    graph_model = policy["model_evidence"]["IntakeGraphRecoveryTest"]
    model = policy["model_evidence"]["IntakeCutoverRollbackTest"]

    assert graph_model["status"] == "PARTIAL_ENGINEERING"
    assert graph_model["evidence_kind"] == "SERVICE_FREE_CHECKPOINT_MODEL"
    assert set(graph_model["does_not_prove"]) == {
        "postgresql_checkpoint_durability",
        "operating_system_process_replacement",
        "stale_database_lease_fencing",
    }
    assert model["status"] == "PARTIAL_ENGINEERING"
    assert model["evidence_kind"] == "SERVICE_FREE_MODEL"
    assert set(model["does_not_prove"]) == {
        "persisted_higher_epoch_allocation",
        "database_or_authority_race_linearization",
        "temporal_or_worker_recovery",
        "production_external_effect_reconciliation",
        "deployment_rollback_rehearsal",
    }
    external_ids = {gate["id"] for gate in policy["external_gates"]}
    assert {
        "EXT-P4-PERSISTED-ROLLBACK",
        "EXT-P4-ROLLBACK-RACE",
        "EXT-P4-RECOVERY-TOPOLOGY",
    } <= external_ids
    pass_mappings = {
        check_id
        for check_id in policy["overrides"]
        if _merged_mapping(policy, check_id)["status"] == "PASS_ENGINEERING"
    }
    for check_id in pass_mappings:
        assert all(
            "IntakeCutoverRollbackTest" not in selector
            for selector in policy["overrides"][check_id]["test_selectors"]
        )


def test_candidate_and_failure_rules_preserve_attempt_provenance() -> None:
    policy = _load(POLICY)

    assert policy["candidate_policy"] == {
        "accepted_candidate_count": 1,
        "candidate_sha_immutable_during_run": True,
        "source_suites_execute_once_per_candidate": True,
        "derived_views_reference_source_reports": True,
        "mixed_candidate_results_forbidden": True,
        "quarantined_attempts_not_reused": True,
        "code_change_invalidates_candidate_checkpoint": True,
        "evidence_commit_must_be_later_than_tested_sha": True,
    }
    assert set(policy["failure_classification"]) == {
        "PRODUCT",
        "FIXTURE",
        "INFRA",
        "EXTERNAL_GATE",
    }
