from __future__ import annotations

from typing import Mapping

from tools.operations.recovery.domain_pitr import (
    RECOVERY_ORDER,
    ValidationResult,
    _append,
    _contains_secret_key,
    _exact_keys,
    _is_identifier,
    _is_sha256,
    _result,
    _validate_authorization,
    _validate_context,
    _validate_external_effects,
    _validate_objectives,
    _validate_predecessor_receipts,
    _validate_receipts,
    _validate_rollback,
    _validate_stage_prefix,
)


SCHEMA_VERSION = "phase8-temporal-regional-dr-fixture.v1"


def validate_temporal_regional_dr(document: object) -> ValidationResult:
    """Validate a fixture checkpoint for the Temporal stage of regional DR."""

    scenario = "TEMPORAL_REGIONAL_DR"
    errors: list[str] = []
    if not isinstance(document, Mapping):
        return _result(document, scenario, ("DOCUMENT_SCHEMA_INVALID",))
    expected = {
        "authorization",
        "compatibility",
        "context",
        "context_sha256",
        "external_effects",
        "namespace_restore",
        "objectives",
        "predecessor_receipts",
        "receipts",
        "recovery_order",
        "rollback",
        "scenario",
        "schema_version",
        "stage_states",
    }
    _append(errors, set(document) == expected, "DOCUMENT_SCHEMA_INVALID")
    _append(errors, document.get("schema_version") == SCHEMA_VERSION, "SCHEMA_VERSION_INVALID")
    _append(errors, document.get("scenario") == scenario, "SCENARIO_INVALID")
    _append(errors, not _contains_secret_key(document), "SECRET_BEARING_FIELD_FORBIDDEN")

    context_hash = _validate_context(document, scenario, errors)
    _validate_authorization(
        document.get("authorization"),
        context=document.get("context"),
        context_hash=context_hash,
        expected_scope="TEMPORAL_REGIONAL_DR_EXTERNAL_DRILL",
        allowed_roles=frozenset({"SRE", "TEMPORAL_PLATFORM"}),
        errors=errors,
    )
    _validate_stage_prefix(document, "TEMPORAL", errors)
    _validate_predecessor_receipts(
        document.get("predecessor_receipts"),
        required_stages=("DOMAIN",),
        context=document.get("context"),
        context_hash=context_hash,
        errors=errors,
    )

    restore = document.get("namespace_restore")
    restore_keys = {
        "acknowledged_events_lost",
        "acknowledged_signals_lost",
        "acknowledged_timers_lost",
        "acknowledged_updates_lost",
        "history_after_sha256",
        "history_before_sha256",
        "internal_table_edits",
        "namespace_id",
        "replication_consistent",
        "supported_control_plane",
    }
    if not _exact_keys(restore, restore_keys):
        errors.append("TEMPORAL_RESTORE_SCHEMA_INVALID")
    else:
        assert isinstance(restore, Mapping)
        _append(errors, _is_identifier(restore["namespace_id"]), "TEMPORAL_NAMESPACE_INVALID")
        _append(errors, restore["internal_table_edits"] is False, "TEMPORAL_INTERNAL_TABLE_EDIT_FORBIDDEN")
        _append(errors, restore["supported_control_plane"] is True, "TEMPORAL_CONTROL_PLANE_INVALID")
        _append(errors, restore["replication_consistent"] is True, "TEMPORAL_REPLICATION_INCONSISTENT")
        for key in (
            "acknowledged_events_lost",
            "acknowledged_signals_lost",
            "acknowledged_timers_lost",
            "acknowledged_updates_lost",
        ):
            _append(errors, restore[key] == 0, "ACKNOWLEDGED_TEMPORAL_STATE_LOSS")
        _append(errors, _is_sha256(restore["history_before_sha256"]), "TEMPORAL_HISTORY_HASH_INVALID")
        _append(
            errors,
            restore["history_before_sha256"] == restore["history_after_sha256"],
            "TEMPORAL_HISTORY_HASH_MISMATCH",
        )

    compatibility = document.get("compatibility")
    compatibility_keys = {
        "captured_history_replay_verified",
        "compatible_worker_available",
        "old_history_readable",
        "worker_build_ids",
        "workers_started",
    }
    if not _exact_keys(compatibility, compatibility_keys):
        errors.append("TEMPORAL_COMPATIBILITY_SCHEMA_INVALID")
    else:
        assert isinstance(compatibility, Mapping)
        _append(errors, compatibility["captured_history_replay_verified"] is True, "HISTORY_REPLAY_UNVERIFIED")
        _append(errors, compatibility["compatible_worker_available"] is True, "COMPATIBLE_WORKER_UNAVAILABLE")
        _append(errors, compatibility["old_history_readable"] is True, "OLD_HISTORY_UNREADABLE")
        build_ids = compatibility["worker_build_ids"]
        _append(
            errors,
            isinstance(build_ids, (list, tuple))
            and bool(build_ids)
            and all(_is_identifier(item) for item in build_ids)
            and len(set(build_ids)) == len(build_ids),
            "WORKER_BUILD_INVENTORY_INVALID",
        )
        _append(
            errors,
            compatibility["workers_started"] is False,
            "WORKERS_STARTED_BEFORE_LATER_RECOVERY_STAGES",
        )

    _validate_objectives(
        document.get("objectives"),
        maximum_rpo_minutes=5,
        maximum_rto_minutes=30,
        errors=errors,
    )
    _validate_rollback(document.get("rollback"), errors)
    _validate_external_effects(document.get("external_effects"), errors)
    required_payloads = {
        "EXTERNAL_EFFECT_RECONCILIATION": document.get("external_effects"),
        "PREDECESSOR_CHAIN": document.get("predecessor_receipts"),
        "RPO_RTO_OBSERVATION": document.get("objectives"),
        "TEMPORAL_COMPATIBILITY": compatibility,
        "TEMPORAL_NAMESPACE_RESTORE": restore,
        "TEMPORAL_ROLLBACK": document.get("rollback"),
    }
    _validate_receipts(
        document.get("receipts"),
        context_hash=context_hash,
        required_payloads=required_payloads,
        errors=errors,
    )
    return _result(document, scenario, errors)


validate = validate_temporal_regional_dr


__all__ = [
    "RECOVERY_ORDER",
    "SCHEMA_VERSION",
    "validate",
    "validate_temporal_regional_dr",
]
