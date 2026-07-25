from __future__ import annotations

import ast
import copy
from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

from scripts.phase8.recovery.domain_pitr import (
    RECOVERY_ORDER,
    canonical_sha256,
    validate_domain_pitr,
)
from scripts.phase8.recovery.graph_object_restore import (
    validate_graph_object_restore,
)
from scripts.phase8.recovery.rotation_compatibility import (
    validate_rotation_compatibility,
)
from scripts.phase8.recovery.temporal_regional_dr import (
    validate_temporal_regional_dr,
)


ROOT = Path(__file__).resolve().parents[2]
RECOVERY = ROOT / "scripts" / "phase8" / "recovery"


def _context(scenario: str) -> tuple[dict[str, object], str]:
    context: dict[str, object] = {
        "attempt_lineage_id": "attempt-20260725-001",
        "candidate_sha": "a" * 40,
        "configuration_sha256": "b" * 64,
        "deployment_manifest_sha256": "c" * 64,
        "environment_id": "synthetic-phase8",
        "observed_at": "2026-07-25T12:00:00Z",
        "operator_id": "operator-fixture",
        "scenario_id": scenario,
    }
    return context, canonical_sha256(context)


def _authorization(
    context: dict[str, object], context_hash: str, scope: str, role: str
) -> dict[str, object]:
    value: dict[str, object] = {
        "authorizer_id": "independent-authorizer",
        "authorizer_role": role,
        "context_sha256": context_hash,
        "decision": "APPROVED",
        "immutable": True,
        "operator_id": context["operator_id"],
        "scope": scope,
        "self_approved": False,
        "signature_verified": True,
        "signer_authorized": True,
        "signer_expired": False,
        "signer_revoked": False,
    }
    value["receipt_sha256"] = canonical_sha256(value)
    return value


def _receipt(
    receipt_type: str, payload: object, context_hash: str
) -> dict[str, object]:
    value: dict[str, object] = {
        "context_sha256": context_hash,
        "immutable": True,
        "payload": payload,
        "payload_sha256": canonical_sha256(payload),
        "receipt_id": f"receipt-{receipt_type.lower().replace('_', '-')}",
        "receipt_type": receipt_type,
        "status": "VERIFIED",
    }
    value["receipt_sha256"] = canonical_sha256(value)
    return value


def _predecessors(
    context: dict[str, object], context_hash: str, stages: tuple[str, ...]
) -> list[dict[str, object]]:
    receipts: list[dict[str, object]] = []
    for stage in stages:
        receipt: dict[str, object] = {
            "context": dict(context),
            "context_sha256": context_hash,
            "decision": "FIXTURE_ACCEPTED",
            "document_sha256": canonical_sha256(
                {"context_sha256": context_hash, "stage": stage}
            ),
            "immutable": True,
            "receipt_id": f"stage-{stage.lower()}",
            "schema_version": "phase8-recovery-stage-receipt.v1",
            "stage": stage,
            "status": "VERIFIED",
        }
        receipt["receipt_sha256"] = canonical_sha256(receipt)
        receipts.append(receipt)
    return receipts


def _rollback() -> dict[str, object]:
    return {
        "automatic_execution": False,
        "available": True,
        "on_rpo_breach": True,
        "on_rto_breach": True,
        "preserves_formal_facts": True,
        "target_id": "last-known-good",
        "validated": True,
    }


def _external_effects() -> dict[str, object]:
    return {
        "blind_replay": False,
        "declared_effect_count": 1,
        "effects": [
            {
                "disposition": "CONFIRMED",
                "effect_id": "tool-operation-001",
                "original_receipt_sha256": "d" * 64,
                "replayed": False,
            }
        ],
        "policy": "RECEIPT_RECONCILE_COMPENSATE_OR_MANUAL",
        "receipt_inventory_complete": True,
        "replay_requested": False,
    }


def _objectives(rpo: float, rto: float) -> dict[str, object]:
    return {
        "approved_rpo_minutes": rpo,
        "approved_rto_minutes": rto,
        "observed_rpo_minutes": rpo,
        "observed_rto_minutes": rto,
        "objective_source": "EXTERNALLY_APPROVED_RELEASE_OBJECTIVE",
        "rollback_on_breach": True,
    }


def _stages(completed_through: str) -> dict[str, str]:
    completed_index = RECOVERY_ORDER.index(completed_through)
    return {
        stage: "VERIFIED" if index <= completed_index else "NOT_STARTED"
        for index, stage in enumerate(RECOVERY_ORDER)
    }


def _seal(
    document: dict[str, object],
    *,
    scope: str,
    role: str,
    payload_names: tuple[str, ...],
) -> dict[str, object]:
    context = document["context"]
    context_hash = document["context_sha256"]
    assert isinstance(context, dict) and isinstance(context_hash, str)
    document["authorization"] = _authorization(context, context_hash, scope, role)
    document["receipts"] = [
        _receipt(name, document[key], context_hash)
        for name, key in (
            item.split("=", maxsplit=1) for item in payload_names
        )
    ]
    return document


def _domain_fixture() -> dict[str, object]:
    context, context_hash = _context("DOMAIN_PITR")
    document: dict[str, object] = {
        "backup": {
            "backup_id": "domain-backup-001",
            "backup_sha256": "1" * 64,
            "encrypted": True,
            "immutable": True,
            "latest_committed_sequence": 991,
            "restorable": True,
        },
        "context": context,
        "context_sha256": context_hash,
        "external_effects": _external_effects(),
        "objectives": _objectives(0, 20),
        "reconciliation": {
            "accepted_commands_missing": 0,
            "duplicate_formal_facts": 0,
            "formal_facts_preserved": True,
            "outbox_gaps": 0,
            "projection_rebuild_deferred": True,
        },
        "recovery_order": list(RECOVERY_ORDER),
        "restore": {
            "committed_transactions_lost": 0,
            "consistency_verified": True,
            "internal_table_edits": False,
            "restored_through_sequence": 991,
            "supported_restore_interface": True,
            "target_id": "domain-restore-fixture",
        },
        "rollback": _rollback(),
        "scenario": "DOMAIN_PITR",
        "schema_version": "phase8-domain-pitr-fixture.v1",
        "stage_states": _stages("DOMAIN"),
    }
    return _seal(
        document,
        scope="DOMAIN_PITR_EXTERNAL_DRILL",
        role="DBA",
        payload_names=(
            "DOMAIN_BACKUP=backup",
            "DOMAIN_RECONCILIATION=reconciliation",
            "DOMAIN_RESTORE=restore",
            "DOMAIN_ROLLBACK=rollback",
            "EXTERNAL_EFFECT_RECONCILIATION=external_effects",
            "RPO_RTO_OBSERVATION=objectives",
        ),
    )


def _temporal_fixture() -> dict[str, object]:
    context, context_hash = _context("TEMPORAL_REGIONAL_DR")
    document: dict[str, object] = {
        "compatibility": {
            "captured_history_replay_verified": True,
            "compatible_worker_available": True,
            "old_history_readable": True,
            "worker_build_ids": ["worker-current", "worker-compat"],
            "workers_started": False,
        },
        "context": context,
        "context_sha256": context_hash,
        "external_effects": _external_effects(),
        "namespace_restore": {
            "acknowledged_events_lost": 0,
            "acknowledged_signals_lost": 0,
            "acknowledged_timers_lost": 0,
            "acknowledged_updates_lost": 0,
            "history_after_sha256": "2" * 64,
            "history_before_sha256": "2" * 64,
            "internal_table_edits": False,
            "namespace_id": "temporal-namespace-fixture",
            "replication_consistent": True,
            "supported_control_plane": True,
        },
        "objectives": _objectives(5, 30),
        "predecessor_receipts": _predecessors(context, context_hash, ("DOMAIN",)),
        "recovery_order": list(RECOVERY_ORDER),
        "rollback": _rollback(),
        "scenario": "TEMPORAL_REGIONAL_DR",
        "schema_version": "phase8-temporal-regional-dr-fixture.v1",
        "stage_states": _stages("TEMPORAL"),
    }
    return _seal(
        document,
        scope="TEMPORAL_REGIONAL_DR_EXTERNAL_DRILL",
        role="TEMPORAL_PLATFORM",
        payload_names=(
            "EXTERNAL_EFFECT_RECONCILIATION=external_effects",
            "PREDECESSOR_CHAIN=predecessor_receipts",
            "RPO_RTO_OBSERVATION=objectives",
            "TEMPORAL_COMPATIBILITY=compatibility",
            "TEMPORAL_NAMESPACE_RESTORE=namespace_restore",
            "TEMPORAL_ROLLBACK=rollback",
        ),
    )


def _graph_fixture() -> dict[str, object]:
    context, context_hash = _context("GRAPH_OBJECT_RESTORE")
    document: dict[str, object] = {
        "activation": {
            "graph_completed_before_object_store": True,
            "projections_started": False,
            "workers_started": False,
        },
        "context": context,
        "context_sha256": context_hash,
        "external_effects": _external_effects(),
        "graph_restore": {
            "checkpoint_after_sha256": "3" * 64,
            "checkpoint_before_sha256": "3" * 64,
            "checkpoint_version": "graph-v7",
            "fences_reconciled": True,
            "internal_table_edits": False,
            "leases_reconciled": True,
            "old_checkpoint_readable": True,
            "supported_restore_interface": True,
        },
        "object_restore": {
            "hash_mismatches": 0,
            "immutable": True,
            "objects_expected": 18,
            "objects_restored": 18,
            "old_object_versions_readable": True,
            "private": True,
            "restore_manifest_sha256": "4" * 64,
            "versioned": True,
        },
        "objectives": _objectives(5, 30),
        "predecessor_receipts": _predecessors(
            context, context_hash, ("DOMAIN", "TEMPORAL")
        ),
        "recovery_order": list(RECOVERY_ORDER),
        "rollback": _rollback(),
        "scenario": "GRAPH_OBJECT_RESTORE",
        "schema_version": "phase8-graph-object-restore-fixture.v1",
        "stage_states": _stages("OBJECT_STORE"),
    }
    return _seal(
        document,
        scope="GRAPH_OBJECT_RESTORE_EXTERNAL_DRILL",
        role="SRE",
        payload_names=(
            "ACTIVATION_FENCE=activation",
            "EXTERNAL_EFFECT_RECONCILIATION=external_effects",
            "GRAPH_RESTORE=graph_restore",
            "GRAPH_ROLLBACK=rollback",
            "OBJECT_RESTORE=object_restore",
            "PREDECESSOR_CHAIN=predecessor_receipts",
            "RPO_RTO_OBSERVATION=objectives",
        ),
    )


def _rotation_fixture() -> dict[str, object]:
    context, context_hash = _context("ROTATION_COMPATIBILITY")
    samples = [
        {
            "artifact_id": f"artifact-{index}",
            "artifact_sha256": str(index) * 64,
            "artifact_type": artifact_type,
            "codec_id": "codec-old",
            "key_id": "key-old",
            "readable": True,
            "readback_sha256": str(index) * 64,
        }
        for index, artifact_type in enumerate(
            ("TEMPORAL_HISTORY", "GRAPH_CHECKPOINT", "CODEC_PAYLOAD"), start=5
        )
    ]
    document: dict[str, object] = {
        "compatibility": {
            "active_old_reference_count": 3,
            "new_material_readable": True,
            "old_checkpoint_readable": True,
            "old_codec_readable": True,
            "old_history_readable": True,
            "old_key_readable": True,
            "reference_inventory_complete": True,
            "retirement_decision": "RETAIN_OLD_READ_PATH",
        },
        "context": context,
        "context_sha256": context_hash,
        "external_effects": _external_effects(),
        "readability_samples": samples,
        "rollback": _rollback(),
        "rotation": {
            "external_execution_performed": False,
            "new_material_id": "codec-key-v2",
            "new_writes_active": True,
            "old_material_id": "codec-key-v1",
            "old_material_retired": False,
            "overlap_enabled": True,
            "rotation_id": "rotation-fixture-001",
            "rotation_type": "CODEC_KEY",
        },
        "scenario": "ROTATION_COMPATIBILITY",
        "schema_version": "phase8-rotation-compatibility-fixture.v1",
    }
    return _seal(
        document,
        scope="ROTATION_COMPATIBILITY_EXTERNAL_DRILL",
        role="SECURITY",
        payload_names=(
            "EXTERNAL_EFFECT_RECONCILIATION=external_effects",
            "READABILITY_COMPATIBILITY=readability_samples",
            "ROTATION_COMPATIBILITY=compatibility",
            "ROTATION_PLAN=rotation",
            "ROTATION_ROLLBACK=rollback",
        ),
    )


@pytest.mark.parametrize(
    ("fixture_factory", "validator"),
    (
        (_domain_fixture, validate_domain_pitr),
        (_temporal_fixture, validate_temporal_regional_dr),
        (_graph_fixture, validate_graph_object_restore),
        (_rotation_fixture, validate_rotation_compatibility),
    ),
)
def test_valid_fixture_is_deterministic_immutable_and_never_operational(
    fixture_factory: object, validator: object
) -> None:
    fixture = fixture_factory()
    before = copy.deepcopy(fixture)
    first = validator(fixture)
    second = validator(fixture)
    assert first == second
    assert first.accepted is True
    assert fixture == before
    report = first.to_dict()
    assert report["decision"] == "FIXTURE_ACCEPTED"
    assert report["production_checkpoint"] == "PENDING_EXTERNAL"
    assert report["capabilities"] and not any(report["capabilities"].values())
    with pytest.raises(FrozenInstanceError):
        first.decision = "PASS"


def test_domain_pitr_blocks_order_loss_objective_rollback_and_blind_replay() -> None:
    cases = []
    order = _domain_fixture()
    order["recovery_order"][0:2] = ["TEMPORAL", "DOMAIN"]
    cases.append((order, "RECOVERY_ORDER_INVALID"))
    loss = _domain_fixture()
    loss["restore"]["committed_transactions_lost"] = 1
    cases.append((loss, "COMMITTED_TRANSACTION_LOSS"))
    rpo = _domain_fixture()
    rpo["objectives"]["approved_rpo_minutes"] = 1
    cases.append((rpo, "APPROVED_RPO_EXCEEDS_CONTRACT"))
    rollback = _domain_fixture()
    rollback["rollback"]["available"] = False
    cases.append((rollback, "ROLLBACK_UNAVAILABLE"))
    replay = _domain_fixture()
    replay["external_effects"]["blind_replay"] = True
    cases.append((replay, "BLIND_EXTERNAL_EFFECT_REPLAY"))
    for fixture, reason in cases:
        result = validate_domain_pitr(fixture)
        assert result.accepted is False
        assert reason in result.reason_codes


def test_temporal_dr_blocks_acknowledged_loss_history_drift_and_early_workers() -> None:
    lost = _temporal_fixture()
    lost["namespace_restore"]["acknowledged_updates_lost"] = 1
    assert "ACKNOWLEDGED_TEMPORAL_STATE_LOSS" in validate_temporal_regional_dr(lost).reason_codes
    history = _temporal_fixture()
    history["namespace_restore"]["history_after_sha256"] = "9" * 64
    assert "TEMPORAL_HISTORY_HASH_MISMATCH" in validate_temporal_regional_dr(history).reason_codes
    worker = _temporal_fixture()
    worker["compatibility"]["workers_started"] = True
    assert "WORKERS_STARTED_BEFORE_LATER_RECOVERY_STAGES" in validate_temporal_regional_dr(worker).reason_codes


def test_later_stages_require_exact_same_context_predecessor_receipt_chain() -> None:
    missing_domain = _temporal_fixture()
    missing_domain["predecessor_receipts"] = []
    assert "PREDECESSOR_RECEIPT_CHAIN_INVALID" in validate_temporal_regional_dr(missing_domain).reason_codes

    mixed_candidate = _graph_fixture()
    mixed_candidate["predecessor_receipts"][0]["context"]["candidate_sha"] = "f" * 40
    mixed_result = validate_graph_object_restore(mixed_candidate)
    assert "PREDECESSOR_CONTEXT_MISMATCH" in mixed_result.reason_codes
    assert "PREDECESSOR_RECEIPT_HASH_MISMATCH" in mixed_result.reason_codes

    mixed_deployment = _graph_fixture()
    mixed_deployment["predecessor_receipts"][1]["context"]["deployment_manifest_sha256"] = "f" * 64
    assert "PREDECESSOR_CONTEXT_MISMATCH" in validate_graph_object_restore(mixed_deployment).reason_codes

    reordered = _graph_fixture()
    reordered["predecessor_receipts"][0:2] = reversed(
        reordered["predecessor_receipts"][0:2]
    )
    assert "PREDECESSOR_RECEIPT_CHAIN_INVALID" in validate_graph_object_restore(reordered).reason_codes


def test_bare_fabricated_hash_and_mutated_predecessor_payload_are_blocked() -> None:
    fabricated = _temporal_fixture()
    fabricated["predecessor_receipts"] = [
        {
            "context_sha256": fabricated["context_sha256"],
            "receipt_id": "stage-domain",
            "receipt_sha256": "f" * 64,
            "stage": "DOMAIN",
        }
    ]
    assert "PREDECESSOR_RECEIPT_SCHEMA_INVALID" in validate_temporal_regional_dr(fabricated).reason_codes

    mutated = _graph_fixture()
    mutated["predecessor_receipts"][0]["decision"] = "BLOCKED"
    result = validate_graph_object_restore(mutated)
    assert "PREDECESSOR_DECISION_NOT_ACCEPTED" in result.reason_codes
    assert "PREDECESSOR_RECEIPT_HASH_MISMATCH" in result.reason_codes


def test_graph_object_restore_blocks_checkpoint_object_and_activation_drift() -> None:
    checkpoint = _graph_fixture()
    checkpoint["graph_restore"]["old_checkpoint_readable"] = False
    assert "OLD_GRAPH_CHECKPOINT_UNREADABLE" in validate_graph_object_restore(checkpoint).reason_codes
    objects = _graph_fixture()
    objects["object_restore"]["hash_mismatches"] = 1
    assert "OBJECT_HASH_MISMATCH" in validate_graph_object_restore(objects).reason_codes
    activation = _graph_fixture()
    activation["activation"]["workers_started"] = True
    assert "WORKERS_STARTED_OUT_OF_ORDER" in validate_graph_object_restore(activation).reason_codes


def test_rotation_blocks_old_read_loss_early_retirement_and_sample_tamper() -> None:
    old_history = _rotation_fixture()
    old_history["compatibility"]["old_history_readable"] = False
    assert "ROTATION_READ_COMPATIBILITY_MISSING" in validate_rotation_compatibility(old_history).reason_codes
    retired = _rotation_fixture()
    retired["rotation"]["old_material_retired"] = True
    assert "OLD_MATERIAL_RETIRED_EARLY" in validate_rotation_compatibility(retired).reason_codes
    sample = _rotation_fixture()
    sample["readability_samples"][0]["readback_sha256"] = "0" * 64
    assert "READABILITY_HASH_MISMATCH" in validate_rotation_compatibility(sample).reason_codes


@pytest.mark.parametrize(
    ("fixture_factory", "validator"),
    (
        (_domain_fixture, validate_domain_pitr),
        (_temporal_fixture, validate_temporal_regional_dr),
        (_graph_fixture, validate_graph_object_restore),
        (_rotation_fixture, validate_rotation_compatibility),
    ),
)
def test_mixed_context_unsigned_mutation_and_secret_bearing_fields_fail_closed(
    fixture_factory: object, validator: object
) -> None:
    mixed = fixture_factory()
    mixed["receipts"][0]["context_sha256"] = "f" * 64
    assert "RECEIPT_CONTEXT_MISMATCH" in validator(mixed).reason_codes

    authorization = fixture_factory()
    authorization["authorization"]["self_approved"] = True
    assert "AUTHORIZATION_SELF_APPROVED" in validator(authorization).reason_codes

    secret = fixture_factory()
    secret["context"]["password"] = "fixture-value"
    assert "SECRET_BEARING_FIELD_FORBIDDEN" in validator(secret).reason_codes


def test_recovery_modules_have_no_operating_capability_imports_or_calls() -> None:
    forbidden_import_roots = {
        "asyncio",
        "boto3",
        "httpx",
        "os",
        "pathlib",
        "psycopg",
        "requests",
        "shutil",
        "socket",
        "subprocess",
        "temporalio",
        "urllib",
    }
    forbidden_calls = {
        "compile",
        "connect",
        "eval",
        "exec",
        "open",
        "popen",
        "remove",
        "replace",
        "run",
        "unlink",
    }
    paths = sorted(RECOVERY.glob("*.py"))
    assert {path.name for path in paths} == {
        "domain_pitr.py",
        "graph_object_restore.py",
        "rotation_compatibility.py",
        "temporal_regional_dr.py",
    }
    for path in paths:
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                assert not {name.name.split(".")[0] for name in node.names} & forbidden_import_roots
            elif isinstance(node, ast.ImportFrom) and node.module:
                assert node.module.split(".")[0] not in forbidden_import_roots
            elif isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name):
                    assert node.func.id.lower() not in forbidden_calls
                elif isinstance(node.func, ast.Attribute):
                    assert node.func.attr.lower() not in forbidden_calls
