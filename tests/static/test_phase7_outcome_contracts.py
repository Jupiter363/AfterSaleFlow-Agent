from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

import jsonschema
import yaml


ROOT = Path(__file__).resolve().parents[2]
BASE = "d18a1f130a925429e8c2dfd11352cea4ca8673a0"
C7 = "0aa260f722fced0eba4314bd4793e415b5bf0b05"
PHASE_7_INTENTIONALLY_MUTATED_SNAPSHOT_PATHS = frozenset(
    {
        "java-api-service/src/main/java/com/example/dispute/review/application/ReviewApplicationService.java",
        "python-agent-service/app/agents/review_copilot.py",
    }
)
MATRIX_PATH = ROOT / "contracts/agent-platform/outcome/v1/compatibility-matrix.yaml"
SCHEMA_PATH = (
    ROOT
    / "contracts/agent-platform/outcome/v1/outcome-synthetic-noop-receipt.schema.json"
)
SEMANTIC_MANIFEST_PATH = (
    ROOT
    / "contracts/agent-platform/outcome/v1/outcome-semantic-conformance.v1.json"
)
REVIEW_DECISION_SCHEMA_PATH = (
    ROOT
    / "contracts/agent-platform/outcome/v1/outcome-reviewer-decision-receipt.schema.json"
)
OUTCOME_FIXTURE_ROOT = ROOT / "contracts/agent-platform/outcome/v1/fixtures"
ADR_PATH = ROOT / "docs/architecture/adr/0016-phase-7-outcome-engineering-exception.md"
CHECKPOINT_PATH = (
    ROOT / "docs/runbooks/temporal-first/phase-6-engineering-checkpoint.md"
)
CONTRACT_PACK_PATH = (
    ROOT / "docs/runbooks/temporal-first/phase-7-p7.0-contract-pack.md"
)
MIGRATION_DIRECTORY = "java-api-service/src/main/resources/db/migration"
MIGRATION_RELATIVE = (
    f"{MIGRATION_DIRECTORY}/V045__outcome_operation_receipt_compensation.sql"
)
MIGRATION_PATH = ROOT / MIGRATION_RELATIVE

DECISIONS = {
    "APPROVE",
    "MODIFY_AND_APPROVE",
    "REQUEST_MORE_EVIDENCE",
    "REJECT",
    "ESCALATE_MANUAL",
}


def _matrix() -> dict[str, Any]:
    value = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _git_blob(revision: str, path: str) -> bytes:
    completed = subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return completed.stdout


def _git_tree_paths(revision: str, path: str) -> set[str]:
    completed = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", revision, "--", path],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return set(completed.stdout.splitlines())


def test_source_snapshot_is_bound_to_exact_accepted_a6_git_blobs() -> None:
    matrix = _matrix()
    assert matrix["accepted_base"] == BASE
    snapshot = matrix["source_snapshot"]
    assert snapshot["hash_scope"] == "raw_git_blob_at_accepted_base"
    assert snapshot["digest"] == "SHA_256"
    assert len(snapshot["files"]) == 9

    pins_by_path = {pin["path"]: pin for pin in snapshot["files"]}
    assert len(pins_by_path) == len(snapshot["files"])
    assert PHASE_7_INTENTIONALLY_MUTATED_SNAPSHOT_PATHS <= set(pins_by_path)

    accepted_by_path: dict[str, bytes] = {}
    for path, pin in pins_by_path.items():
        accepted = _git_blob(BASE, path)
        assert hashlib.sha256(accepted).hexdigest() == pin["sha256"]
        accepted_by_path[path] = accepted

    changed_pinned_paths = {
        path
        for path, accepted in accepted_by_path.items()
        if _git_blob("HEAD", path) != accepted
    }
    assert changed_pinned_paths == (
        PHASE_7_INTENTIONALLY_MUTATED_SNAPSHOT_PATHS
    )
    for path, accepted in accepted_by_path.items():
        if path not in PHASE_7_INTENTIONALLY_MUTATED_SNAPSHOT_PATHS:
            assert _git_blob("HEAD", path) == accepted

    checkpoint = snapshot["accepted_checkpoint"]
    accepted_checkpoint = _git_blob(BASE, checkpoint["path"])
    assert hashlib.sha256(accepted_checkpoint).hexdigest() == checkpoint["sha256"]
    assert _git_blob("HEAD", checkpoint["path"]) == accepted_checkpoint


def test_java_is_the_sole_formal_authority_for_all_five_human_decisions() -> None:
    matrix = _matrix()
    authority = matrix["authority_boundaries"]
    java = authority["java_postgresql"]
    assert java["authority"] == "SOLE_FORMAL_BUSINESS_TRUTH_AND_ONLY_FORMAL_WRITER"
    assert {
        "frozen_review_packet",
        "human_decision",
        "approved_action_snapshot",
        "operation_ledger",
        "external_receipt_ledger",
        "compensation_ledger",
        "closed_snapshot",
        "evaluation_ledger",
    } <= set(java["owns"])
    assert java["model_or_workflow_may_substitute_formal_fact"] is False

    review = matrix["human_review"]
    assert set(review["frozen_decision_vocabulary"]) == DECISIONS
    assert set(review["decision_semantics"]) == DECISIONS
    assert review["owner"] == "AUTHORIZED_PLATFORM_REVIEWER"
    assert review["decision_authority"] == "AUTHORIZED_PLATFORM_REVIEWER_ONLY"
    assert review["system_may_emit_or_impersonate_human_decision"] is False
    assert review["decision_semantics"]["ESCALATE_MANUAL"]["origin"] == (
        "REVIEWER_SUBMITTED"
    )
    assert review["approval_binding"]["model_proposal_is_human_decision"] is False
    assert review["approval_binding"]["parent_substitution"] == "reject"
    assert review["immutability"]["committed_decision_rewrite"] == "forbidden"


def test_approve_snapshot_identity_is_manifest_dispatched_and_frozen() -> None:
    manifest = json.loads(SEMANTIC_MANIFEST_PATH.read_text(encoding="utf-8"))
    rules = {rule["rule_id"]: rule for rule in manifest["rules"]}
    assert set(rules) == {
        "review-window-order.v1",
        "causal-revision-adjacency.v1",
        "approve-action-snapshot-ref-equality.v1",
        "approve-action-snapshot-hash-equality.v1",
        "terminal-success-count-equality.v1",
        "compensation-receipt-id-equality.v1",
        "compensation-receipt-hash-equality.v1",
    }

    expected = {
        "approve-action-snapshot-ref-equality.v1": (
            "action_snapshot_ref",
            "approved_action_snapshot_ref",
            "fixtures/invalid/outcome-reviewer-decision-approve-snapshot-ref-mismatch.json",
        ),
        "approve-action-snapshot-hash-equality.v1": (
            "action_snapshot_hash",
            "approved_action_snapshot_hash",
            "fixtures/invalid/outcome-reviewer-decision-approve-snapshot-hash-mismatch.json",
        ),
    }
    for rule_id, (left, right, fixture) in expected.items():
        assert rules[rule_id] == {
            "rule_id": rule_id,
            "operator": "TEXT_EQUALS",
            "left_field": left,
            "right_field": right,
            "condition": {
                "field": "decision",
                "operator": "IN",
                "values": ["APPROVE"],
            },
            "schema_files": ["outcome-reviewer-decision-receipt.schema.json"],
            "negative_fixtures": [
                {
                    "schema_file": "outcome-reviewer-decision-receipt.schema.json",
                    "fixture": fixture,
                }
            ],
        }

    schema = json.loads(REVIEW_DECISION_SCHEMA_PATH.read_text(encoding="utf-8"))
    declaration = schema["x-semantic-conformance"]
    assert declaration["required_rules"] == [
        "causal-revision-adjacency.v1",
        "approve-action-snapshot-ref-equality.v1",
        "approve-action-snapshot-hash-equality.v1",
    ]
    validator = jsonschema.validators.validator_for(schema)(schema)

    valid = json.loads(
        (OUTCOME_FIXTURE_ROOT / "valid/outcome-reviewer-decision-receipt-valid.json")
        .read_text(encoding="utf-8")
    )
    assert not list(validator.iter_errors(valid))
    assert valid["decision"] == "APPROVE"
    assert valid["approved_action_snapshot_ref"] == valid["action_snapshot_ref"]
    assert valid["approved_action_snapshot_hash"] == valid["action_snapshot_hash"]

    for rule_id, (left, right, fixture) in expected.items():
        invalid = json.loads(
            (OUTCOME_FIXTURE_ROOT.parent / fixture).read_text(encoding="utf-8")
        )
        assert not list(validator.iter_errors(invalid))
        assert invalid["decision"] == "APPROVE"
        assert invalid[left] != invalid[right], rule_id

    invariants = _matrix()["wire_protocol"]["invariants"]
    assert invariants["approve_action_snapshot_identity"] == (
        "APPROVED_REF_AND_HASH_EXACTLY_MATCH_ORIGINAL_ACTION_SNAPSHOT"
    )


def test_sla_expiration_is_a_distinct_system_fact_not_a_human_decision() -> None:
    matrix = _matrix()
    expiration = matrix["human_review"]["sla_expiration"]
    assert expiration == {
        "actor": "SYSTEM",
        "fact_type": "SYSTEM_SLA_ESCALATION",
        "receipt_type": "SYSTEM_SLA_ESCALATION",
        "persisted_by": "JAVA_POSTGRESQL",
        "terminal_wait_outcome": "SYSTEM_SLA_ESCALATION",
        "next_process_state": "MANUAL_ESCALATION",
        "human_decision": "NONE",
        "approval_record_creation": "forbidden",
        "reviewer_impersonation": "forbidden",
        "auto_approve": "forbidden",
        "approved_snapshot_creation": "forbidden",
        "execution_handoff": "forbidden",
    }
    races = matrix["review_wait_and_races"]
    assert races["human_terminal_decisions_per_review_epoch"] == (
        "AT_MOST_ONE_REVIEWER_SUBMITTED"
    )
    assert races["terminal_wait_outcomes"] == [
        "HUMAN_DECISION_COMMITTED",
        "SYSTEM_SLA_ESCALATION",
    ]
    assert races["decision_vs_sla_expiration"] == {
        "arbitration": "deterministic_workflow_event_order_plus_committed_java_revision",
        "decision_committed_first": (
            "return_persisted_human_decision_and_cancel_sla_branch"
        ),
        "sla_expiration_committed_first": (
            "persist_SYSTEM_SLA_ESCALATION_receipt_without_ApprovalRecord_and_"
            "reject_late_epoch_decision"
        ),
    }


def test_draft_and_outcome_remain_projections_not_room_types() -> None:
    identity = _matrix()["flow_identity"]
    assert identity["projections"] == ["DRAFT", "OUTCOME"]
    assert identity["room_types"] == ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
    assert identity["draft_or_outcome_as_room_type"] == "forbidden"
    assert identity["existing_route_and_api_compatibility"] == "required"
    assert identity["selector"] == "LEGACY"
    assert identity["current_formal_workflow"] == "forbidden"
    assert identity["current_temporal_allocation"] == "forbidden"


def test_private_review_graph_is_bounded_read_only_and_has_no_tools() -> None:
    matrix = _matrix()
    boundary = matrix["authority_boundaries"]["langgraph"]
    assert boundary == {
        "authority": "PRIVATE_READ_ONLY_REVIEW_COGNITION_ONLY",
        "graph_key": "outcome/review.v1",
        "formal_sink": "forbidden",
        "domain_mutation": "forbidden",
        "process_mutation": "forbidden",
        "tool_capability": "NONE",
    }
    graph = matrix["review_graph"]
    assert graph["checkpoint_scope"] == "private_reviewer_and_frozen_packet"
    assert graph["input_material"]["raw_packet_in_workflow_envelope"] == "forbidden"
    assert graph["input_material"]["live_case_or_party_lookup"] == "forbidden"
    assert graph["output"]["approval_or_execution_authority"] is False
    assert {"tool_calls", "tool_parameters", "credentials", "formal_decision"} <= set(
        graph["forbidden"]
    )

    envelope = matrix["envelope_contract"]
    assert envelope["maximum_encoded_bytes"] == 32768
    assert envelope["required_coordination_fields"] == ["epoch", "revision", "fence"]
    assert "tool_parameters" in envelope["raw_sensitive_or_effect_material_forbidden"]
    assert envelope["unknown_fields"] == "reject"


def test_v045_was_absent_at_c7_and_is_now_an_additive_append_only_ledger() -> None:
    matrix = _matrix()
    gate = matrix["gate_state"]
    contract = matrix["v045_ledger_contract"]
    assert gate["v045_status"] == "RESERVED_NOT_IMPLEMENTED"
    assert contract["migration"] == "V045"
    assert contract["status"] == "RESERVED_NOT_IMPLEMENTED"
    assert contract["migration_shape"] == "ADDITIVE_ONLY"
    assert contract["destructive_rewrite_of_existing_approval_or_action_history"] == (
        "forbidden"
    )
    assert MIGRATION_RELATIVE not in _git_tree_paths(C7, MIGRATION_DIRECTORY)
    assert MIGRATION_PATH.is_file()

    sql = MIGRATION_PATH.read_text(encoding="utf-8").lower()
    for table in (
        "outcome_operation",
        "outcome_operation_attempt_observation",
        "outcome_operation_receipt",
        "outcome_compensation_parent_binding",
    ):
        assert f"create table {table} (" in sql
        assert f"before update or truncate on {table}" in sql
        assert f"before delete on {table}" in sql
    for destructive_statement in (
        "alter table ",
        "drop table ",
        "truncate table ",
        "delete from ",
    ):
        assert not any(
            line.lstrip().startswith(destructive_statement)
            for line in sql.splitlines()
        )

    operation = contract["logical_records"]["operation"]
    receipt = contract["logical_records"]["receipt"]
    observation = contract["logical_records"]["attempt_observation"]
    compensation = contract["logical_records"]["compensation"]
    assert operation["immutable_identity"] == ["case_id", "outcome_epoch", "operation_id"]
    assert operation["unique_keys"] == ["operation_key"]
    assert operation["terminal_statuses"] == ["SUCCEEDED", "FAILED"]
    assert "AMBIGUOUS" not in operation["terminal_statuses"]
    assert operation["ambiguous_as_operation_terminal_status"] == "forbidden"
    assert operation["operation_key_reuse_different_request_hash"] == "reject"
    assert receipt["immutable_parent"] == "operation_id"
    assert receipt["terminal_statuses"] == ["SUCCEEDED", "FAILED"]
    assert "AMBIGUOUS" not in receipt["terminal_statuses"]
    assert receipt["ambiguous_as_authoritative_receipt_status"] == "forbidden"
    assert receipt["mutable_overwrite"] == "forbidden"
    assert observation == {
        "record_type": "EXECUTION_ATTEMPT_OBSERVATION",
        "immutable_parent": "operation_id",
        "lost_response_status": "AMBIGUOUS",
        "status_is_terminal_operation_fact": False,
        "status_is_authoritative_external_receipt": False,
        "operation_status_while_unresolved": "RECONCILING",
        "external_effect_truth_while_unresolved": "UNKNOWN",
        "blocks_closure": True,
        "blocks_compensation_decision": True,
        "blocks_blind_retry": True,
        "resolution": "authoritative_receipt_query_or_manual_reconciliation",
    }
    assert compensation["compensate_without_original_terminal_success_receipt"] == (
        "forbidden"
    )
    assert compensation[
        "compensation_decision_while_ambiguous_observation_unresolved"
    ] == "forbidden"
    assert compensation["parent_substitution"] == "reject"


def test_effect_protocol_requires_query_before_retry_and_append_only_compensation() -> None:
    matrix = _matrix()
    effects = matrix["external_effect_protocol"]
    assert effects["invocation_owner"] == "JAVA_TOOL_ACTIVITY_FUTURE_ONLY"
    assert effects["agent_or_graph_direct_invocation"] == "forbidden"
    assert effects["pre_dispatch_ledger_commit_required"] is True
    assert effects["receipt_commit_required"] is True
    assert effects["timeout_after_possible_dispatch"] == {
        "classification": "AMBIGUOUS",
        "ledger_record_type": "EXECUTION_ATTEMPT_OBSERVATION",
        "operation_status": "RECONCILING",
        "authoritative_terminal_receipt_created": False,
        "blind_retry": "forbidden",
        "closure": "blocked",
        "compensation_decision": "blocked",
        "required_next_step": "query_external_status_with_same_idempotency_identity",
    }
    assert effects["tool_without_idempotency_or_query_capability"] == {
        "automatic_retry_after_ambiguous_dispatch": "forbidden",
        "recovery": "manual_only",
    }

    retry = matrix["retry_and_compensation"]
    assert retry["compensation_order"] == "reverse_successful_effect_order"
    assert retry["compensation_failure"] == (
        "persist_failure_and_enter_manual_recovery"
    )
    assert retry["workflow_completion_while_compensation_in_flight"] == "forbidden"
    assert retry["redis_lock_authority"] == "NONE"

    for path in (ADR_PATH, CONTRACT_PACK_PATH):
        text = path.read_text(encoding="utf-8")
        lowered = text.lower()
        assert "AMBIGUOUS" in text
        assert "nonterminal" in lowered or "non-terminal" in lowered
        assert "closure" in lowered and ("block" in lowered or "blocking" in lowered)
        assert "blind retry" in lowered
        assert "compensation" in lowered
        assert "query" in lowered or "reconciliation" in lowered


def test_closure_precedes_evaluation_and_evaluation_cannot_reopen_or_mutate() -> None:
    contract = _matrix()["closure_and_evaluation"]
    gate = contract["closure_gate"]
    assert contract["closure_authority"] == "JAVA_POSTGRESQL"
    assert gate["approval_decision_in"] == ["APPROVE", "MODIFY_AND_APPROVE"]
    assert gate["every_required_operation_has_terminal_success_receipt"] == "required"
    assert gate["unresolved_ambiguous_attempt_observation_count"] == 0
    assert gate["failed_required_receipt_count"] == 0
    assert gate["in_flight_operation_count"] == 0
    assert gate["in_flight_compensation_count"] == 0
    assert contract["closed_snapshot"]["creation"] == (
        "atomic_with_java_closed_transition"
    )
    assert contract["closed_snapshot"]["immutable"] is True

    evaluation = contract["evaluation"]
    assert evaluation["starts_after_closed_snapshot_commit"] is True
    assert evaluation["input"] == "immutable_closed_snapshot_ref_and_hash_only"
    assert evaluation["retries_may_not_reopen_case"] is True
    assert {"case_status", "process_revision", "human_decision", "policy", "prompt"} <= set(
        evaluation["may_not_mutate"]
    )


def test_synthetic_noop_schema_accepts_only_effect_free_java_signed_shape() -> None:
    matrix = _matrix()
    noop = matrix["synthetic_noop_shadow"]
    assert noop["allowed_mode"] == "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW"
    assert noop["signer"] == "JAVA_CONTROL_PLANE"
    assert noop["real_tool_adapter_reachability"] == "forbidden"
    assert noop["formal_operation_or_receipt_satisfaction"] == "forbidden"
    assert noop["closure_gate_satisfaction"] == "forbidden"
    assert noop["exact_literals"] == {
        "schema_version": "outcome-synthetic-noop-receipt.v1",
        "marker": "JAVA_SIGNED_SYNTHETIC_NOOP_V1",
        "runtime_mode": "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
        "traffic_source": "SIGNED_SYNTHETIC",
        "output_sink": "ISOLATED_COMPARISON_LEDGER",
        "synthetic_only": True,
        "contains_real_case_or_party_data": False,
        "tool_invoked": False,
        "external_effect_created": False,
        "formal_business_write_created": False,
        "projection_only": True,
    }

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    validator_class = jsonschema.validators.validator_for(schema)
    validator_class.check_schema(schema)
    validator = validator_class(schema)
    for relative in noop["fixture_paths"]["valid"]:
        fixture = json.loads((SCHEMA_PATH.parent / relative).read_text(encoding="utf-8"))
        assert not list(validator.iter_errors(fixture))
    for relative in noop["fixture_paths"]["invalid"]:
        fixture = json.loads((SCHEMA_PATH.parent / relative).read_text(encoding="utf-8"))
        assert list(validator.iter_errors(fixture))


def test_engineering_gate_is_exactly_not_run_and_grants_no_runtime_authority() -> None:
    matrix = _matrix()
    gate = matrix["gate_state"]
    assert gate["upstream_engineering_checkpoint"] == "PASS"
    assert gate["upstream_promotion_gate"] == "PENDING"
    assert gate["permission"] == "PHASE_7_ENGINEERING_ONLY"
    assert gate["engineering_exception_token"] == (
        "ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY"
    )
    assert gate["p7_0_entry_gate"] == "NOT_RUN"
    assert gate["contract_candidate_state"] == "CONTRACT_CANDIDATE_READY"
    assert gate["entry_evidence_effect"] == "P7_0_ENGINEERING_ENTRY_PASS"
    assert gate["entry_evidence_recorded"] is False
    assert gate["phase_7_implementation_allowed"] is False
    assert gate["formal_outcome_selector"] == "LEGACY"
    assert gate["allowed_runtime_modes"] == [
        "DISABLED",
        "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
    ]
    for field in (
        "real_case_or_party_data_allowed",
        "real_tool_invocation_allowed",
        "real_external_effect_allowed",
        "real_shadow_allowed",
        "formal_outcome_workflow_allowed",
        "temporal_outcome_allocation_allowed",
        "formal_graph_sink_allowed",
        "production_traffic_allowed",
        "canary_allowed",
        "promotion_allowed",
    ):
        assert gate[field] is False
    assert gate["migrations"] == {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
    }
    assert matrix["entry_acceptance"]["current_result"] == "NOT_RUN"
    assert matrix["entry_acceptance"]["implementation_must_remain_blocked"] is True
    assert ADR_PATH.is_file()
    assert CHECKPOINT_PATH.is_file()
