from __future__ import annotations

import copy
import re
from pathlib import Path
from typing import Any, Iterator

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ROOT / "infra-tests" / "phase8" / "scenarios"
EXPECTED_FILES = {
    "unified-checkpoint.yaml",
    "load-and-burst.yaml",
    "chaos-and-failover.yaml",
    "security-and-rotation.yaml",
    "replay-and-dr.yaml",
    "soak.yaml",
}
COMMON_TOP_LEVEL_FIELDS = {
    "schema_version",
    "kind",
    "scenario_id",
    "gate",
    "definition_guard",
    "authorization_contract",
    "release_context_contract",
    "execution_contract",
    "prerequisites",
    "ordered_steps",
    "stop_conditions",
    "rollback_contract",
    "evidence_contract",
    "signature_contract",
}
RELEASE_CONTEXT_FIELDS = [
    "candidate_sha",
    "candidate_tree_sha",
    "configuration_sha256",
    "context_id",
    "context_sha256",
    "environment_identity",
    "deployment_manifest_sha256",
    "images",
    "attempt_lineage",
]
ATTEMPT_LINEAGE_FIELDS = [
    "attempt_id",
    "attempt_number",
    "checkpoint_id",
    "previous_attempt_id",
]
RECEIPT_FIELDS = [
    "schema_version",
    "control_id",
    "scenario_id",
    "step_id",
    "checkpoint_order",
    "claimed_result",
    "status",
    "candidate_sha",
    "candidate_tree_sha",
    "configuration_sha256",
    "context_id",
    "context_sha256",
    "environment_identity",
    "deployment_manifest_sha256",
    "images",
    "attempt_id",
    "attempt_number",
    "checkpoint_id",
    "previous_attempt_id",
    "operator_identity",
    "authorization_reference",
    "signer_identity",
    "signer_role",
    "signature_algorithm",
    "signing_key_id",
    "trust_root_id",
    "observed_at",
    "step_evidence_sha256",
    "stop_condition_id",
    "stop_evidence_sha256",
    "rollback_disposition",
    "rollback_evidence_sha256",
    "evidence_sha256",
    "signed_payload_sha256",
    "signature",
    "receipt_sha256",
]
SIGNED_PAYLOAD_FIELDS = [
    "schema_version",
    "control_id",
    "scenario_id",
    "step_id",
    "checkpoint_order",
    "claimed_result",
    "status",
    "candidate_sha",
    "candidate_tree_sha",
    "configuration_sha256",
    "context_id",
    "context_sha256",
    "environment_identity",
    "deployment_manifest_sha256",
    "images",
    "attempt_id",
    "attempt_number",
    "checkpoint_id",
    "previous_attempt_id",
    "operator_identity",
    "authorization_reference",
    "signer_identity",
    "signer_role",
    "signature_algorithm",
    "signing_key_id",
    "trust_root_id",
    "observed_at",
    "step_evidence_sha256",
    "stop_condition_id",
    "stop_evidence_sha256",
    "rollback_disposition",
    "rollback_evidence_sha256",
    "evidence_sha256",
]
HASH_FIELDS = {
    "configuration_sha256",
    "context_sha256",
    "deployment_manifest_sha256",
    "step_evidence_sha256",
    "stop_evidence_sha256",
    "rollback_evidence_sha256",
    "evidence_sha256",
    "signed_payload_sha256",
    "receipt_sha256",
}
SIGNATURE_ROLES = {
    "ARCHITECTURE",
    "JAVA",
    "PYTHON",
    "SRE",
    "SECURITY",
    "BUSINESS",
}
SIGNATURE_FIELDS = ["algorithm", "key_id", "role", "signature", "signer_identity"]
EXPECTED_OPERATIONS = {
    "load-and-burst.yaml": {"steady_load", "burst"},
    "chaos-and-failover.yaml": {"chaos", "failover"},
    "security-and-rotation.yaml": {"security_preflight", "security_fuzz", "rotation"},
    "replay-and-dr.yaml": {
        "replay",
        "domain_pitr",
        "graph_object_restore",
        "temporal_regional_dr",
    },
    "soak.yaml": {"soak", "six_role_signoff"},
}
EXPECTED_SCENARIO_IDS = {
    "unified-checkpoint.yaml": "UNIFIED_CHECKPOINT",
    "load-and-burst.yaml": "LOAD_AND_BURST",
    "chaos-and-failover.yaml": "CHAOS_AND_FAILOVER",
    "security-and-rotation.yaml": "SECURITY_AND_ROTATION",
    "replay-and-dr.yaml": "REPLAY_AND_DR",
    "soak.yaml": "SOAK",
}
EXPECTED_SPECIALIZED_STEPS = {
    3: "EXTERNAL_SECURITY_PREFLIGHT",
    5: "STEADY_LOAD",
    6: "BURST_AND_BOUNDED_RECOVERY",
    7: "LOAD_COUPLED_CHAOS_AND_FAILOVER",
    8: "WORKFLOW_GRAPH_ROLLBACK_AND_REPLAY",
    9: "CROSS_SCOPE_SECURITY_AND_ROTATION",
    10: "PITR_RESTORE_AND_REGIONAL_DR",
    11: "SOAK_AND_SIX_ROLE_SIGNOFF",
}
EXPECTED_DEFINITION_GUARD = {
    "mode": "DEFINITION_ONLY",
    "executable": False,
    "records_actual_results": False,
    "grants_external_authorization": False,
    "verifies_external_evidence": False,
}
EXPECTED_AUTHORIZATION_CONTRACT = {
    "status": "PENDING",
    "separate_external_authorization_required": True,
    "required_fields": ["authorization_reference", "operator_identity"],
    "exact_release_context_binding_required": True,
    "missing_invalid_expired_or_scope_mismatch_decision": "EXTERNAL_GATE",
}
EXPECTED_RELEASE_CONTEXT_CONTRACT = {
    "source": "EXTERNAL_SIGNED_EVIDENCE",
    "match_policy": "EXACT_SINGLE_CONTEXT",
    "required_fields": RELEASE_CONTEXT_FIELDS,
    "image_item_required_fields": ["name", "digest"],
    "image_set_policy": "EXACT_NAME_AND_OCI_DIGEST_SET",
    "attempt_lineage_required_fields": ATTEMPT_LINEAGE_FIELDS,
    "mixed_or_missing_context_decision": "EXTERNAL_GATE",
}
EXPECTED_EVIDENCE_CONTRACT = {
    "evidence_kind": "EXTERNAL_SIGNED",
    "authority_ceiling": "EXTERNAL_EVIDENCE_SHAPE_ONLY_UNVERIFIED",
    "verification_status": "UNVERIFIED_REQUIRES_P8_I5_3_TRUST_ROOT_VALIDATION",
    "immutable_receipt_required_fields": RECEIPT_FIELDS,
    "signed_payload_exact_fields": SIGNED_PAYLOAD_FIELDS,
    "required_hash_fields": [
        "configuration_sha256",
        "context_sha256",
        "deployment_manifest_sha256",
        "step_evidence_sha256",
        "stop_evidence_sha256",
        "rollback_evidence_sha256",
        "evidence_sha256",
        "signed_payload_sha256",
        "receipt_sha256",
    ],
    "mismatched_substituted_or_unverified_decision": "EXTERNAL_GATE",
}
EXPECTED_EXTERNAL_ENVELOPE_FIELDS = [
    "claimed_result",
    "signatures",
    "signed_payload_sha256",
    "trust_roots",
    "verification_status",
]
EXPECTED_SIGNATURE_CONTRACT = {
    "required_count": 6,
    "required_roles": [
        "ARCHITECTURE",
        "JAVA",
        "PYTHON",
        "SRE",
        "SECURITY",
        "BUSINESS",
    ],
    "required_fields": SIGNATURE_FIELDS,
    "distinct_roles_required": True,
    "distinct_signer_identities_required": True,
    "signer_independent_from_runner_generator_candidate_and_evidence_authors": True,
    "independent_trust_root_required": True,
    "signed_payload_exact_fields_source": (
        "evidence_contract.signed_payload_exact_fields"
    ),
    "each_signature_covers_exact_signed_payload": True,
    "signed_payload_role_field": "signer_role",
    "signature_role_must_equal_signed_payload_role": True,
    "role_relabel_decision": "EXTERNAL_GATE",
    "trust_root_validation_deferred_to": "P8-I5-3",
}
EXPECTED_SCENARIO_SECTIONS = yaml.safe_load(
    """
unified-checkpoint.yaml:
  gate:
    classification: EXTERNAL_GATE
    status: PENDING
    automatic: false
    engineering_lane_authority: FORBIDDEN
  execution_contract:
    ordered: true
    stop_on_first_failure: true
    preserve_every_attempt: true
    partial_rerun_may_replace_failed_attempt: false
    retry_requires_new_attempt_id: true
  prerequisites:
    - prerequisite_id: ENGINEERING_CANDIDATE_SEALED
      required_evidence: EXACT_CANDIDATE_ENGINEERING_CHECKPOINT_RECEIPT
      unresolved_decision: EXTERNAL_GATE
    - prerequisite_id: P0_REVIEW_CLOSED
      required_evidence: IMMUTABLE_REVIEW_DISPOSITION_HASH
      unresolved_decision: EXTERNAL_GATE
    - prerequisite_id: CURRENT_EVIDENCE_TABLES_AVAILABLE
      required_evidence: COMPLETE_279_CHECK_AND_99_BASELINE_TABLE_HASHES
      unresolved_decision: EXTERNAL_GATE
    - prerequisite_id: PRIOR_MIGRATION_AUTHORITY_ACCEPTED
      required_evidence: SIGNED_EXTERNAL_MIG_000_THROUGH_MIG_007_RECEIPTS
      unresolved_decision: EXTERNAL_GATE
    - prerequisite_id: V047_ABSENT
      required_evidence: EXACT_CANDIDATE_PATH_INVENTORY_HASH
      unresolved_decision: EXTERNAL_GATE
  ordered_steps:
    - {order: 1, step_id: EVIDENCE_TABLES_AND_PREREQUISITES, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 2, step_id: THREE_FAILURE_DOMAIN_DEPLOYMENT_BINDING, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 3, step_id: EXTERNAL_SECURITY_PREFLIGHT, scenario_file: infra-tests/phase8/scenarios/security-and-rotation.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 4, step_id: MULTI_ROLE_BOUNDARY_E2E_AND_BASELINES, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 5, step_id: STEADY_LOAD, scenario_file: infra-tests/phase8/scenarios/load-and-burst.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 6, step_id: BURST_AND_BOUNDED_RECOVERY, scenario_file: infra-tests/phase8/scenarios/load-and-burst.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 7, step_id: LOAD_COUPLED_CHAOS_AND_FAILOVER, scenario_file: infra-tests/phase8/scenarios/chaos-and-failover.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 8, step_id: WORKFLOW_GRAPH_ROLLBACK_AND_REPLAY, scenario_file: infra-tests/phase8/scenarios/replay-and-dr.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 9, step_id: CROSS_SCOPE_SECURITY_AND_ROTATION, scenario_file: infra-tests/phase8/scenarios/security-and-rotation.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 10, step_id: PITR_RESTORE_AND_REGIONAL_DR, scenario_file: infra-tests/phase8/scenarios/replay-and-dr.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
    - {order: 11, step_id: SOAK_AND_SIX_ROLE_SIGNOFF, scenario_file: infra-tests/phase8/scenarios/soak.yaml, gate: {classification: EXTERNAL_GATE, status: PENDING}}
  stop_conditions:
    - FIRST_STEP_FAILURE
    - AUTHORIZATION_MISSING_INVALID_EXPIRED_OR_OUT_OF_SCOPE
    - RELEASE_CONTEXT_MISMATCH
    - RECEIPT_MISSING_PARTIAL_STALE_UNSIGNED_OR_UNTRUSTED
    - DUPLICATE_FORMAL_MESSAGE_ARTIFACT_OR_EXTERNAL_EFFECT
    - ACCEPTED_COMMAND_OR_ACKNOWLEDGED_EVENT_LOSS
    - STALE_REVISION_OR_FENCE_OVERWRITE
    - PRIVATE_DATA_OR_HIDDEN_REASONING_DISCLOSURE
    - UNRECOVERABLE_CHECKPOINT_OR_SLO_BREACH
    - INCOMPLETE_REFERENCE_AUDIT_OR_PROVENANCE_MISMATCH
  rollback_contract:
    trigger: ANY_STOP_CONDITION
    authorization_required: true
    stop_new_traffic_first: true
    preserve_formal_facts_and_additive_stores: true
    preserve_attempt_and_receipt_hashes: true
    compatible_reader_and_worker_pinning_required: true
    direct_internal_table_edits_allowed: false
    blind_external_effect_replay_allowed: false
    rollback_evidence_required: SIGNED_SAME_CONTEXT_RECEIPT

load-and-burst.yaml:
  gate:
    classification: EXTERNAL_GATE
    status: PENDING
    operations: {steady_load: PENDING, burst: PENDING}
  execution_contract:
    ordered: true
    checkpoint_orders: [5, 6]
    stop_on_first_failure: true
    preserve_every_attempt: true
    partial_rerun_may_replace_failed_attempt: false
    retry_requires_new_attempt_id: true
  prerequisites:
    - {prerequisite_id: EVIDENCE_TABLES_AND_PREREQUISITES, required_receipt_order: 1, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: THREE_FAILURE_DOMAIN_DEPLOYMENT_BINDING, required_receipt_order: 2, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: EXTERNAL_SECURITY_PREFLIGHT, required_receipt_order: 3, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: MULTI_ROLE_BOUNDARY_E2E_AND_BASELINES, required_receipt_order: 4, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: SAME_CONTEXT_CAPACITY_BASELINE, required_evidence: SIGNED_EXTERNAL_EVIDENCE, unresolved_decision: EXTERNAL_GATE}
  ordered_steps:
    - order: 5
      step_id: STEADY_LOAD
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      target_definition:
        duration_minutes: 60
        concurrent_rooms: 1000
        concurrent_agent_runs: 250
        concurrent_sse_connections: 2500
        concurrent_model_requests: 100
      required_observations:
        - CONTROL_AND_AGENT_QUEUE_SLO_SUMMARY
        - STREAM_CONTINUITY_AND_RECONNECT_SUMMARY
        - MODEL_PROVIDER_AND_EXPORTER_HEALTH_SUMMARY
    - order: 6
      step_id: BURST_AND_BOUNDED_RECOVERY
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      target_definition:
        burst_duration_seconds: 30
        commands_per_second: 50
        agent_runs_per_second: 20
        model_requests_per_second: 200
        maximum_recovery_minutes: 30
      required_observations:
        - RECOVERY_WINDOW_SUMMARY
        - QUEUE_BACKLOG_AND_DRAIN_SUMMARY
        - STREAM_AND_MODEL_ERROR_BUDGET_SUMMARY
  stop_conditions:
    - ANY_PREREQUISITE_RECEIPT_UNACCEPTABLE
    - RELEASE_CONTEXT_MISMATCH
    - CONTROL_QUEUE_SLO_BREACH
    - DATA_LOSS_DUPLICATION_OR_ORDERING_VIOLATION
    - PRIVATE_DATA_OR_HIDDEN_REASONING_DISCLOSURE
    - RECOVERY_WINDOW_EXCEEDED
  rollback_contract:
    trigger: ANY_STOP_CONDITION
    authorization_required: true
    stop_new_load_first: true
    preserve_attempt_and_receipt_hashes: true
    preserve_formal_facts_and_additive_stores: true
    compatible_deployment_pinning_required: true
    rollback_evidence_required: SIGNED_SAME_CONTEXT_RECEIPT

chaos-and-failover.yaml:
  gate:
    classification: EXTERNAL_GATE
    status: PENDING
    operations: {chaos: PENDING, failover: PENDING}
  execution_contract:
    ordered: true
    checkpoint_orders: [7]
    stop_on_first_failure: true
    preserve_every_attempt: true
    partial_rerun_may_replace_failed_attempt: false
    retry_requires_new_attempt_id: true
  prerequisites:
    - {prerequisite_id: STEADY_LOAD, required_receipt_order: 5, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: BURST_AND_BOUNDED_RECOVERY, required_receipt_order: 6, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: SAME_CONTEXT_HEALTHY_LOAD_WINDOW, required_evidence: SIGNED_EXTERNAL_EVIDENCE, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: FAILOVER_ROLLBACK_AUTHORIZATION, required_evidence: SCOPED_EXTERNAL_AUTHORIZATION, unresolved_decision: EXTERNAL_GATE}
  ordered_steps:
    - order: 7
      step_id: LOAD_COUPLED_CHAOS_AND_FAILOVER
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      fault_classes:
        - DUPLICATE_DELIVERY
        - ORDER_PERTURBATION
        - DELAY_AND_TIMEOUT
        - PAYLOAD_HASH_MISMATCH
        - PROCESS_OR_DEPENDENCY_UNAVAILABILITY
        - DATABASE_PRIMARY_FAILOVER
      target_boundaries:
        - JAVA_CONTROL_AND_AGENT_WORKERS
        - PYTHON_AGENT_RUNTIME
        - REDIS_EPHEMERAL_CACHE
        - MODEL_GATEWAY
        - TEMPORAL_CONTROL_PLANE
        - DOMAIN_POSTGRESQL
        - GRAPH_POSTGRESQL
      required_observations:
        - FORMAL_LEDGER_INTEGRITY_SUMMARY
        - IDEMPOTENCY_AND_EXACT_HASH_CONFLICT_SUMMARY
        - QUEUE_RECOVERY_AND_FENCE_SUMMARY
        - DATABASE_FAILOVER_AND_RECONNECTION_SUMMARY
  stop_conditions:
    - ANY_PREREQUISITE_RECEIPT_UNACCEPTABLE
    - RELEASE_CONTEXT_MISMATCH
    - DUPLICATE_FORMAL_MESSAGE_ARTIFACT_OR_EXTERNAL_EFFECT
    - ACCEPTED_COMMAND_OR_ACKNOWLEDGED_EVENT_LOSS
    - STALE_REVISION_OR_FENCE_OVERWRITE
    - UNAUTHORIZED_OR_UNBOUNDED_FAULT
    - RECOVERY_SLO_BREACH
  rollback_contract:
    trigger: ANY_STOP_CONDITION
    authorization_required: true
    stop_fault_injection_first: true
    stop_new_traffic_before_recovery: true
    preserve_attempt_and_receipt_hashes: true
    preserve_formal_facts_and_additive_stores: true
    compatible_worker_graph_and_database_target_required: true
    direct_internal_table_edits_allowed: false
    rollback_evidence_required: SIGNED_SAME_CONTEXT_RECEIPT

security-and-rotation.yaml:
  gate:
    classification: EXTERNAL_GATE
    status: PENDING
    operations: {security_preflight: PENDING, security_fuzz: PENDING, rotation: PENDING}
  execution_contract:
    ordered: true
    checkpoint_orders: [3, 9]
    stop_on_first_failure: true
    preserve_every_attempt: true
    partial_rerun_may_replace_failed_attempt: false
    retry_requires_new_attempt_id: true
  prerequisites:
    - {prerequisite_id: EVIDENCE_TABLES_AND_PREREQUISITES, required_receipt_order: 1, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: THREE_FAILURE_DOMAIN_DEPLOYMENT_BINDING, required_receipt_order: 2, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: WORKFLOW_GRAPH_ROLLBACK_AND_REPLAY, required_receipt_order_for_rotation: 8, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: SECURITY_OPERATOR_AND_ROTATION_AUTHORITY, required_evidence: SCOPED_EXTERNAL_AUTHORIZATION, unresolved_decision: EXTERNAL_GATE}
  ordered_steps:
    - order: 3
      step_id: EXTERNAL_SECURITY_PREFLIGHT
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      required_control_receipts:
        - TEMPORAL_CLOUD_TLS_OR_MTLS_CREDENTIAL_ADAPTER_ACCEPTED
        - TRUSTED_PROXY_OR_DIRECT_MTLS_ASGI_IDENTITY_BRIDGE_ACCEPTED
        - REPORTING_READ_REPLICA_ROUTING_ACCEPTED
        - OBJECT_STORE_WORKLOAD_IDENTITY_PROVIDER_CHAIN_ACCEPTED
        - LANGFUSE_IDENTITY_PROMPT_OUTPUT_REDACTION_ACCEPTED
        - ISTIO_SECURITY_IO_V1_CRD_READINESS_ACCEPTED
        - ISTIO_DATAPLANE_INTERCEPTION_ACCEPTED
        - ISTIO_STRICT_MTLS_ENFORCEMENT_ACCEPTED
        - ISTIO_AUTHORIZATION_POLICY_ENFORCEMENT_ACCEPTED
        - I3_I4_OTEL_NAMESPACE_LABEL_SERVICE_ACCOUNT_AND_PORT_BINDING_ACCEPTED
    - order: 9
      step_id: CROSS_SCOPE_SECURITY_AND_ROTATION
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      target_definitions:
        - CROSS_TENANT_CASE_ACTOR_AND_ROLE_BOUNDARY_FUZZ
        - PII_PRIVATE_REASONING_AND_LOG_DISCLOSURE_SCAN
        - VERSIONED_KEY_REFERENCE_ROTATION_WITH_OVERLAP
        - CERTIFICATE_AND_WORKLOAD_CREDENTIAL_ROTATION
        - PAYLOAD_CODEC_ROTATION_WITH_OLD_VERSION_READABILITY
      sensitive_material_in_catalog_allowed: false
      required_observations:
        - IDENTITY_AND_AUTHORIZATION_BOUNDARY_SUMMARY
        - REDACTION_AND_CARDINALITY_SUMMARY
        - OLD_AND_NEW_VERSION_COMPATIBILITY_SUMMARY
        - ROTATION_ROLLBACK_SUMMARY
  stop_conditions:
    - REQUIRED_SECURITY_CONTROL_RECEIPT_UNACCEPTABLE
    - RELEASE_CONTEXT_MISMATCH
    - UNAUTHORIZED_IDENTITY_OR_CROSS_SCOPE_ACCESS
    - PII_PRIVATE_REASONING_OR_CREDENTIAL_DISCLOSURE
    - OLD_VERSION_READABILITY_LOSS
    - ROTATION_OVERLAP_OR_ROLLBACK_PROOF_MISSING
  rollback_contract:
    trigger: ANY_STOP_CONDITION
    authorization_required: true
    stop_new_traffic_before_rotation_rollback: true
    preserve_attempt_and_receipt_hashes: true
    preserve_old_version_readability: true
    preserve_formal_facts_and_additive_stores: true
    direct_internal_table_edits_allowed: false
    rollback_evidence_required: SIGNED_SAME_CONTEXT_RECEIPT

replay-and-dr.yaml:
  gate:
    classification: EXTERNAL_GATE
    status: PENDING
    operations: {replay: PENDING, domain_pitr: PENDING, graph_object_restore: PENDING, temporal_regional_dr: PENDING}
  execution_contract:
    ordered: true
    checkpoint_orders: [8, 10]
    stop_on_first_failure: true
    preserve_every_attempt: true
    partial_rerun_may_replace_failed_attempt: false
    retry_requires_new_attempt_id: true
  prerequisites:
    - {prerequisite_id: LOAD_COUPLED_CHAOS_AND_FAILOVER, required_receipt_order: 7, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: CROSS_SCOPE_SECURITY_AND_ROTATION, required_receipt_order_for_dr: 9, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: IMMUTABLE_BACKUP_HISTORY_CHECKPOINT_AND_OBJECT_MANIFESTS, required_evidence: SIGNED_EXTERNAL_EVIDENCE, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: RECOVERY_AND_DR_AUTHORIZATION, required_evidence: SCOPED_EXTERNAL_AUTHORIZATION, unresolved_decision: EXTERNAL_GATE}
  ordered_steps:
    - order: 8
      step_id: WORKFLOW_GRAPH_ROLLBACK_AND_REPLAY
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      target_definitions:
        - TEMPORAL_WORKER_ROLLOUT_AND_COMPATIBLE_ROLLBACK
        - GRAPH_VERSION_ROLLOUT_AND_COMPATIBLE_ROLLBACK
        - CAPTURED_HISTORY_REPLAY
        - CHECKPOINT_AND_LEASE_RECOVERY
        - COMPATIBLE_OLD_VERSION_PINNING
      required_observations:
        - HISTORY_REPLAY_AND_DETERMINISM_SUMMARY
        - CHECKPOINT_LEASE_AND_FENCE_SUMMARY
        - OLD_VERSION_READABILITY_SUMMARY
    - order: 10
      step_id: PITR_RESTORE_AND_REGIONAL_DR
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      target_definitions:
        - DOMAIN_POSTGRESQL_POINT_IN_TIME_RECOVERY
        - GRAPH_POSTGRESQL_AND_IMMUTABLE_OBJECT_RESTORE
        - TEMPORAL_REGIONAL_DISASTER_RECOVERY
        - FORMAL_LEDGER_RECONCILIATION
        - EXTERNAL_EFFECT_NO_BLIND_REPLAY
      required_observations:
        - RECOVERY_POINT_AND_RECOVERY_TIME_SUMMARY
        - OBJECT_VERSION_AND_HASH_READBACK_SUMMARY
        - DOMAIN_GRAPH_TEMPORAL_RECONCILIATION_SUMMARY
        - IDEMPOTENCY_AND_COMPENSATION_SUMMARY
  stop_conditions:
    - ANY_PREREQUISITE_RECEIPT_UNACCEPTABLE
    - RELEASE_CONTEXT_MISMATCH
    - HISTORY_OR_CHECKPOINT_INCOMPATIBILITY
    - FORMAL_LEDGER_DIVERGENCE
    - IMMUTABLE_OBJECT_VERSION_OR_HASH_MISMATCH
    - BLIND_OR_DUPLICATE_EXTERNAL_EFFECT_REPLAY
    - RECOVERY_OBJECTIVE_BREACH
  rollback_contract:
    trigger: ANY_STOP_CONDITION
    authorization_required: true
    stop_new_traffic_before_recovery: true
    preserve_attempt_and_receipt_hashes: true
    preserve_formal_facts_and_additive_stores: true
    use_public_supported_apis_and_ledgers_only: true
    compatible_worker_graph_codec_and_reader_pinning_required: true
    direct_internal_table_edits_allowed: false
    blind_external_effect_replay_allowed: false
    rollback_evidence_required: SIGNED_SAME_CONTEXT_RECEIPT

soak.yaml:
  gate:
    classification: EXTERNAL_GATE
    status: PENDING
    operations: {soak: PENDING, six_role_signoff: PENDING}
  execution_contract:
    ordered: true
    checkpoint_orders: [11]
    stop_on_first_failure: true
    preserve_every_attempt: true
    partial_rerun_may_replace_failed_attempt: false
    retry_requires_new_attempt_id: true
  prerequisites:
    - {prerequisite_id: EVIDENCE_TABLES_AND_PREREQUISITES, required_receipt_order: 1, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: THREE_FAILURE_DOMAIN_DEPLOYMENT_BINDING, required_receipt_order: 2, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: EXTERNAL_SECURITY_PREFLIGHT, required_receipt_order: 3, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: MULTI_ROLE_BOUNDARY_E2E_AND_BASELINES, required_receipt_order: 4, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: STEADY_LOAD, required_receipt_order: 5, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: BURST_AND_BOUNDED_RECOVERY, required_receipt_order: 6, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: LOAD_COUPLED_CHAOS_AND_FAILOVER, required_receipt_order: 7, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: WORKFLOW_GRAPH_ROLLBACK_AND_REPLAY, required_receipt_order: 8, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: CROSS_SCOPE_SECURITY_AND_ROTATION, required_receipt_order: 9, unresolved_decision: EXTERNAL_GATE}
    - {prerequisite_id: PITR_RESTORE_AND_REGIONAL_DR, required_receipt_order: 10, unresolved_decision: EXTERNAL_GATE}
  ordered_steps:
    - order: 11
      step_id: SOAK_AND_SIX_ROLE_SIGNOFF
      gate: {classification: EXTERNAL_GATE, status: PENDING}
      target_definition:
        duration_hours: 24
        uninterrupted_same_context_required: true
      required_observations:
        - SERVICE_LEVEL_AND_ERROR_BUDGET_SUMMARY
        - QUEUE_STREAM_AND_MODEL_STABILITY_SUMMARY
        - SECURITY_PRIVACY_AND_EXPORTER_HEALTH_SUMMARY
        - RECOVERY_READINESS_AND_REFERENCE_INVENTORY_SUMMARY
  stop_conditions:
    - ANY_PREREQUISITE_RECEIPT_UNACCEPTABLE
    - RELEASE_CONTEXT_MISMATCH
    - DEPLOYMENT_OR_IMAGE_SET_CHANGE
    - ATTEMPT_LINEAGE_BREAK
    - SLO_OR_ERROR_BUDGET_BREACH
    - SECURITY_PRIVACY_OR_DATA_INTEGRITY_BREACH
    - REQUIRED_SIGNER_MISSING_DUPLICATED_UNAUTHORIZED_OR_UNTRUSTED
  rollback_contract:
    trigger: ANY_STOP_CONDITION
    authorization_required: true
    stop_new_traffic_first: true
    preserve_attempt_and_receipt_hashes: true
    preserve_formal_facts_and_additive_stores: true
    compatible_deployment_pinning_required: true
    direct_internal_table_edits_allowed: false
    rollback_evidence_required: SIGNED_SAME_CONTEXT_RECEIPT
"""
)
assert isinstance(EXPECTED_SCENARIO_SECTIONS, dict)


def _catalog() -> dict[str, dict[str, Any]]:
    paths = sorted(SCENARIOS.glob("*.yaml"))
    assert {path.name for path in paths} == EXPECTED_FILES
    documents: dict[str, dict[str, Any]] = {}
    for path in paths:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
        assert isinstance(value, dict), path
        documents[path.name] = value
    return documents


def _walk(value: Any) -> Iterator[tuple[str | None, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from _walk(child)
    elif isinstance(value, list):
        for child in value:
            yield None, child
            yield from _walk(child)


def _expected_evidence_contract(name: str) -> dict[str, Any]:
    expected = copy.deepcopy(EXPECTED_EVIDENCE_CONTRACT)
    if name == "unified-checkpoint.yaml":
        expected["external_envelope_required_fields"] = EXPECTED_EXTERNAL_ENVELOPE_FIELDS
    return expected


def _assert_document_contract(name: str, document: dict[str, Any]) -> None:
    assert set(document) == COMMON_TOP_LEVEL_FIELDS
    assert document["schema_version"] == "phase8-external-scenario.v1"
    assert document["kind"] == "EXTERNAL_SCENARIO_DEFINITION"
    assert document["scenario_id"] == EXPECTED_SCENARIO_IDS[name]
    expected_sections = EXPECTED_SCENARIO_SECTIONS[name]
    assert document["gate"] == expected_sections["gate"]
    assert document["definition_guard"] == EXPECTED_DEFINITION_GUARD
    assert document["authorization_contract"] == EXPECTED_AUTHORIZATION_CONTRACT
    assert document["release_context_contract"] == EXPECTED_RELEASE_CONTEXT_CONTRACT
    assert document["execution_contract"] == expected_sections["execution_contract"]
    assert document["prerequisites"] == expected_sections["prerequisites"]
    assert document["ordered_steps"] == expected_sections["ordered_steps"]
    assert document["stop_conditions"] == expected_sections["stop_conditions"]
    assert document["rollback_contract"] == expected_sections["rollback_contract"]
    assert document["evidence_contract"] == _expected_evidence_contract(name)
    assert document["signature_contract"] == EXPECTED_SIGNATURE_CONTRACT


def _assert_role_binding(
    signature: dict[str, str], signed_payload: dict[str, str]
) -> None:
    assert set(signature) == set(SIGNATURE_FIELDS)
    assert signed_payload["signer_role"] in SIGNATURE_ROLES
    assert signature["role"] == signed_payload["signer_role"]


def _assert_complete_signed_binding(document: dict[str, Any]) -> None:
    context = document["release_context_contract"]
    assert context["required_fields"] == RELEASE_CONTEXT_FIELDS
    assert context["image_item_required_fields"] == ["name", "digest"]
    assert context["attempt_lineage_required_fields"] == ATTEMPT_LINEAGE_FIELDS
    evidence = document["evidence_contract"]
    assert evidence["immutable_receipt_required_fields"] == RECEIPT_FIELDS
    assert evidence["signed_payload_exact_fields"] == SIGNED_PAYLOAD_FIELDS
    signatures = document["signature_contract"]
    assert signatures["signed_payload_exact_fields_source"] == (
        "evidence_contract.signed_payload_exact_fields"
    )
    assert signatures["each_signature_covers_exact_signed_payload"] is True
    assert signatures["signed_payload_role_field"] == "signer_role"
    assert signatures["signature_role_must_equal_signed_payload_role"] is True
    assert signatures["role_relabel_decision"] == "EXTERNAL_GATE"


def _assert_step_order(document: dict[str, Any]) -> None:
    observed = {
        item["order"]: item["step_id"] for item in document["ordered_steps"]
    }
    if document["scenario_id"] == "UNIFIED_CHECKPOINT":
        assert list(observed) == list(range(1, 12))
        assert {
            order: step_id
            for order, step_id in observed.items()
            if order in EXPECTED_SPECIALIZED_STEPS
        } == EXPECTED_SPECIALIZED_STEPS
        return
    expected_orders = document["execution_contract"]["checkpoint_orders"]
    assert list(observed) == expected_orders
    assert observed == {
        order: EXPECTED_SPECIALIZED_STEPS[order] for order in expected_orders
    }


def test_catalog_is_closed_definition_only_and_externally_gated() -> None:
    catalog = _catalog()
    assert len({document["scenario_id"] for document in catalog.values()}) == len(catalog)
    for name, document in catalog.items():
        assert set(document) == COMMON_TOP_LEVEL_FIELDS, name
        assert document["schema_version"] == "phase8-external-scenario.v1"
        assert document["kind"] == "EXTERNAL_SCENARIO_DEFINITION"
        assert document["gate"]["classification"] == "EXTERNAL_GATE"
        assert document["gate"]["status"] == "PENDING"
        if name in EXPECTED_OPERATIONS:
            operations = document["gate"]["operations"]
            assert set(operations) == EXPECTED_OPERATIONS[name]
            assert set(operations.values()) == {"PENDING"}

        guard = document["definition_guard"]
        assert guard == {
            "mode": "DEFINITION_ONLY",
            "executable": False,
            "records_actual_results": False,
            "grants_external_authorization": False,
            "verifies_external_evidence": False,
        }


def test_all_nested_scenario_contracts_match_closed_world_definitions() -> None:
    for name, document in _catalog().items():
        _assert_document_contract(name, document)


def test_unknown_nested_approval_execution_and_skip_fields_fail_closed() -> None:
    for name, document in _catalog().items():
        mutations: list[dict[str, Any]] = []

        root_extra = copy.deepcopy(document)
        root_extra["self_approval"] = True
        mutations.append(root_extra)

        release_extra = copy.deepcopy(document)
        release_extra["release_context_contract"]["self_approval"] = True
        mutations.append(release_extra)

        for field in ("executable", "skip_order", "skip-order"):
            execution_extra = copy.deepcopy(document)
            execution_extra["execution_contract"][field] = True
            mutations.append(execution_extra)

        authority_extra = copy.deepcopy(document)
        authority_extra["authorization_contract"]["production_authority"] = True
        mutations.append(authority_extra)

        prerequisite_extra = copy.deepcopy(document)
        prerequisite_extra["prerequisites"][0]["production_approved"] = True
        mutations.append(prerequisite_extra)

        step_extra = copy.deepcopy(document)
        step_extra["ordered_steps"][0]["executable"] = True
        mutations.append(step_extra)

        rollback_extra = copy.deepcopy(document)
        rollback_extra["rollback_contract"]["production_approved"] = True
        mutations.append(rollback_extra)

        evidence_extra = copy.deepcopy(document)
        evidence_extra["evidence_contract"]["self_approval"] = True
        mutations.append(evidence_extra)

        signature_extra = copy.deepcopy(document)
        signature_extra["signature_contract"]["production_approved"] = True
        mutations.append(signature_extra)

        for mutated in mutations:
            with pytest.raises(AssertionError):
                _assert_document_contract(name, mutated)


def test_partial_nested_contracts_and_target_values_fail_closed() -> None:
    for name, document in _catalog().items():
        partials: list[dict[str, Any]] = []

        for section, field in (
            ("release_context_contract", "source"),
            ("execution_contract", "ordered"),
            ("rollback_contract", "trigger"),
            ("evidence_contract", "verification_status"),
            ("signature_contract", "required_count"),
        ):
            partial = copy.deepcopy(document)
            partial[section].pop(field)
            partials.append(partial)

        prerequisite_partial = copy.deepcopy(document)
        prerequisite_partial["prerequisites"][0].pop("unresolved_decision")
        partials.append(prerequisite_partial)

        step_partial = copy.deepcopy(document)
        step_partial["ordered_steps"][0].pop("step_id")
        partials.append(step_partial)

        stop_partial = copy.deepcopy(document)
        stop_partial["stop_conditions"].pop()
        partials.append(stop_partial)

        empty_context_value = copy.deepcopy(document)
        empty_context_value["release_context_contract"]["required_fields"] = []
        partials.append(empty_context_value)

        if name != "unified-checkpoint.yaml":
            target_partial = copy.deepcopy(document)
            target_found = False
            for step in target_partial["ordered_steps"]:
                for target_key in (
                    "target_definition",
                    "target_definitions",
                    "fault_classes",
                    "required_control_receipts",
                ):
                    target = step.get(target_key)
                    if isinstance(target, dict):
                        target.pop(next(iter(target)))
                        target_found = True
                        break
                    if isinstance(target, list):
                        target.pop()
                        target_found = True
                        break
                if target_found:
                    break
            assert target_found, name
            partials.append(target_partial)

        for partial in partials:
            with pytest.raises(AssertionError):
                _assert_document_contract(name, partial)


def test_every_scenario_requires_one_exact_release_context_and_authorization() -> None:
    for name, document in _catalog().items():
        authorization = document["authorization_contract"]
        assert authorization == {
            "status": "PENDING",
            "separate_external_authorization_required": True,
            "required_fields": ["authorization_reference", "operator_identity"],
            "exact_release_context_binding_required": True,
            "missing_invalid_expired_or_scope_mismatch_decision": "EXTERNAL_GATE",
        }, name

        context = document["release_context_contract"]
        assert context["source"] == "EXTERNAL_SIGNED_EVIDENCE"
        assert context["match_policy"] == "EXACT_SINGLE_CONTEXT"
        assert context["required_fields"] == RELEASE_CONTEXT_FIELDS
        assert context["image_item_required_fields"] == ["name", "digest"]
        assert context["image_set_policy"] == "EXACT_NAME_AND_OCI_DIGEST_SET"
        assert context["attempt_lineage_required_fields"] == ATTEMPT_LINEAGE_FIELDS
        assert context["mixed_or_missing_context_decision"] == "EXTERNAL_GATE"


def test_checkpoint_order_is_complete_stop_first_and_attempt_preserving() -> None:
    catalog = _catalog()
    unified = catalog["unified-checkpoint.yaml"]
    unified_steps = unified["ordered_steps"]
    assert [item["order"] for item in unified_steps] == list(range(1, 12))
    assert len({item["step_id"] for item in unified_steps}) == len(unified_steps)
    assert all(
        item["gate"] == {"classification": "EXTERNAL_GATE", "status": "PENDING"}
        for item in unified_steps
    )

    specialized_steps: dict[int, str] = {}
    for name, document in catalog.items():
        execution = document["execution_contract"]
        assert execution["ordered"] is True
        assert execution["stop_on_first_failure"] is True
        assert execution["preserve_every_attempt"] is True
        assert execution["partial_rerun_may_replace_failed_attempt"] is False
        assert execution["retry_requires_new_attempt_id"] is True
        assert document["prerequisites"], name
        prerequisite_ids = [
            item["prerequisite_id"] for item in document["prerequisites"]
        ]
        assert len(set(prerequisite_ids)) == len(prerequisite_ids)
        assert all(
            item["unresolved_decision"] == "EXTERNAL_GATE"
            for item in document["prerequisites"]
        )
        assert document["stop_conditions"], name
        if name == "unified-checkpoint.yaml":
            continue
        orders = [item["order"] for item in document["ordered_steps"]]
        assert execution["checkpoint_orders"] == orders
        for item in document["ordered_steps"]:
            assert item["gate"] == {"classification": "EXTERNAL_GATE", "status": "PENDING"}
            assert item["order"] not in specialized_steps
            specialized_steps[item["order"]] = item["step_id"]
    assert specialized_steps == EXPECTED_SPECIALIZED_STEPS


def test_rollback_is_explicit_non_destructive_and_evidence_preserving() -> None:
    for name, document in _catalog().items():
        rollback = document["rollback_contract"]
        assert rollback["trigger"] == "ANY_STOP_CONDITION"
        assert rollback["authorization_required"] is True
        assert rollback["preserve_attempt_and_receipt_hashes"] is True
        assert rollback["preserve_formal_facts_and_additive_stores"] is True
        assert rollback["rollback_evidence_required"] == "SIGNED_SAME_CONTEXT_RECEIPT"
        if "direct_internal_table_edits_allowed" in rollback:
            assert rollback["direct_internal_table_edits_allowed"] is False, name
        if "blind_external_effect_replay_allowed" in rollback:
            assert rollback["blind_external_effect_replay_allowed"] is False, name


def test_receipt_shape_matches_wave_one_vocabulary_without_claiming_verification() -> None:
    for name, document in _catalog().items():
        evidence = document["evidence_contract"]
        assert evidence["evidence_kind"] == "EXTERNAL_SIGNED"
        assert evidence["authority_ceiling"] == "EXTERNAL_EVIDENCE_SHAPE_ONLY_UNVERIFIED"
        assert evidence["verification_status"] == (
            "UNVERIFIED_REQUIRES_P8_I5_3_TRUST_ROOT_VALIDATION"
        )
        assert evidence["immutable_receipt_required_fields"] == RECEIPT_FIELDS, name
        assert evidence["signed_payload_exact_fields"] == SIGNED_PAYLOAD_FIELDS, name
        assert set(evidence["required_hash_fields"]) == HASH_FIELDS, name
        assert evidence["mismatched_substituted_or_unverified_decision"] == "EXTERNAL_GATE"


def test_six_roles_are_distinct_and_trust_validation_is_deferred() -> None:
    for name, document in _catalog().items():
        signatures = document["signature_contract"]
        assert signatures["required_count"] == 6
        assert set(signatures["required_roles"]) == SIGNATURE_ROLES, name
        assert len(signatures["required_roles"]) == 6
        assert signatures["required_fields"] == SIGNATURE_FIELDS
        assert signatures["distinct_roles_required"] is True
        assert signatures["distinct_signer_identities_required"] is True
        assert signatures[
            "signer_independent_from_runner_generator_candidate_and_evidence_authors"
        ] is True
        assert signatures["independent_trust_root_required"] is True
        assert signatures["signed_payload_exact_fields_source"] == (
            "evidence_contract.signed_payload_exact_fields"
        )
        assert signatures["each_signature_covers_exact_signed_payload"] is True
        assert signatures["trust_root_validation_deferred_to"] == "P8-I5-3"


def test_signed_signer_role_cannot_be_relabelled_after_signature() -> None:
    signature = {
        "algorithm": "Ed25519",
        "key_id": "synthetic-key-id",
        "role": "JAVA",
        "signature": "synthetic-signature",
        "signer_identity": "synthetic-signer",
    }
    signed_payload = {"signer_role": "JAVA"}
    _assert_role_binding(signature, signed_payload)

    relabelled_signature = {**signature, "role": "SRE"}
    with pytest.raises(AssertionError):
        _assert_role_binding(relabelled_signature, signed_payload)

    relabelled_payload = {"signer_role": "SRE"}
    with pytest.raises(AssertionError):
        _assert_role_binding(signature, relabelled_payload)

    for name, document in _catalog().items():
        unsigned_role = copy.deepcopy(document)
        unsigned_role["evidence_contract"]["signed_payload_exact_fields"].remove(
            "signer_role"
        )
        with pytest.raises(AssertionError):
            _assert_document_contract(name, unsigned_role)

        equality_disabled = copy.deepcopy(document)
        equality_disabled["signature_contract"][
            "signature_role_must_equal_signed_payload_role"
        ] = False
        with pytest.raises(AssertionError):
            _assert_document_contract(name, equality_disabled)


def test_missing_or_substituted_signed_binding_fields_fail_closed() -> None:
    for name, document in _catalog().items():
        _assert_complete_signed_binding(document)
        _assert_step_order(document)
        for section, field_list_name, required_fields in (
            ("release_context_contract", "required_fields", RELEASE_CONTEXT_FIELDS),
            (
                "release_context_contract",
                "attempt_lineage_required_fields",
                ATTEMPT_LINEAGE_FIELDS,
            ),
            ("release_context_contract", "image_item_required_fields", ["name", "digest"]),
            ("evidence_contract", "immutable_receipt_required_fields", RECEIPT_FIELDS),
            ("evidence_contract", "signed_payload_exact_fields", SIGNED_PAYLOAD_FIELDS),
        ):
            for index, field in enumerate(required_fields):
                omitted = copy.deepcopy(document)
                omitted[section][field_list_name].pop(index)
                with pytest.raises(AssertionError):
                    _assert_complete_signed_binding(omitted)

                substituted = copy.deepcopy(document)
                substituted[section][field_list_name][index] = f"SUBSTITUTED_{field}"
                with pytest.raises(AssertionError):
                    _assert_complete_signed_binding(substituted)

        orders = [item["order"] for item in document["ordered_steps"]]
        changed_order = copy.deepcopy(document)
        changed_order["ordered_steps"][0]["order"] = 999
        assert [item["order"] for item in changed_order["ordered_steps"]] != orders
        with pytest.raises(AssertionError):
            _assert_step_order(changed_order)


def test_catalog_contains_no_execution_material_results_or_concrete_evidence() -> None:
    forbidden_keys = {
        "actual_result",
        "argv",
        "command",
        "commands",
        "endpoint",
        "password",
        "script",
        "scripts",
        "shell",
        "token",
        "url",
        "uri",
    }
    forbidden_tool_words = re.compile(
        r"\b(?:curl|docker|helm|kubectl|mvnw?|powershell|pytest|terraform)\b",
        re.IGNORECASE,
    )
    concrete_hash = re.compile(r"(?<![A-Za-z0-9])[0-9a-f]{40}(?:[0-9a-f]{24})?(?![A-Za-z0-9])")
    result_claim = re.compile(r"\bPASS(?:ED)?\b")
    network_location = re.compile(r"(?:https?|tcp|postgres(?:ql)?|redis)://", re.IGNORECASE)

    for path in sorted(SCENARIOS.glob("*.yaml")):
        raw = path.read_text(encoding="utf-8")
        document = yaml.safe_load(raw)
        assert not result_claim.search(raw), path
        assert not forbidden_tool_words.search(raw), path
        assert not network_location.search(raw), path
        assert not concrete_hash.search(raw), path
        for key, value in _walk(document):
            assert key not in forbidden_keys, (path, key)
            if key == "signature":
                assert value == "signature"
