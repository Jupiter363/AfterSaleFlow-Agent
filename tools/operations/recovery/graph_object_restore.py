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


SCHEMA_VERSION = "phase8-graph-object-restore-fixture.v1"


def validate_graph_object_restore(document: object) -> ValidationResult:
    """Validate ordered Graph and immutable object restoration fixture evidence."""

    scenario = "GRAPH_OBJECT_RESTORE"
    errors: list[str] = []
    if not isinstance(document, Mapping):
        return _result(document, scenario, ("DOCUMENT_SCHEMA_INVALID",))
    expected = {
        "activation",
        "authorization",
        "context",
        "context_sha256",
        "external_effects",
        "graph_restore",
        "object_restore",
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
        expected_scope="GRAPH_OBJECT_RESTORE_EXTERNAL_DRILL",
        allowed_roles=frozenset({"DBA", "SRE"}),
        errors=errors,
    )
    _validate_stage_prefix(document, "OBJECT_STORE", errors)
    _validate_predecessor_receipts(
        document.get("predecessor_receipts"),
        required_stages=("DOMAIN", "TEMPORAL"),
        context=document.get("context"),
        context_hash=context_hash,
        errors=errors,
    )

    graph = document.get("graph_restore")
    graph_keys = {
        "checkpoint_after_sha256",
        "checkpoint_before_sha256",
        "checkpoint_version",
        "fences_reconciled",
        "internal_table_edits",
        "leases_reconciled",
        "old_checkpoint_readable",
        "supported_restore_interface",
    }
    if not _exact_keys(graph, graph_keys):
        errors.append("GRAPH_RESTORE_SCHEMA_INVALID")
    else:
        assert isinstance(graph, Mapping)
        _append(errors, graph["internal_table_edits"] is False, "GRAPH_INTERNAL_TABLE_EDIT_FORBIDDEN")
        _append(errors, graph["supported_restore_interface"] is True, "GRAPH_RESTORE_INTERFACE_INVALID")
        _append(errors, graph["leases_reconciled"] is True, "GRAPH_LEASES_UNRECONCILED")
        _append(errors, graph["fences_reconciled"] is True, "GRAPH_FENCES_UNRECONCILED")
        _append(errors, graph["old_checkpoint_readable"] is True, "OLD_GRAPH_CHECKPOINT_UNREADABLE")
        _append(errors, _is_identifier(graph["checkpoint_version"]), "GRAPH_CHECKPOINT_VERSION_INVALID")
        _append(errors, _is_sha256(graph["checkpoint_before_sha256"]), "GRAPH_CHECKPOINT_HASH_INVALID")
        _append(
            errors,
            graph["checkpoint_before_sha256"] == graph["checkpoint_after_sha256"],
            "GRAPH_CHECKPOINT_HASH_MISMATCH",
        )

    object_restore = document.get("object_restore")
    object_keys = {
        "hash_mismatches",
        "immutable",
        "objects_expected",
        "objects_restored",
        "old_object_versions_readable",
        "private",
        "restore_manifest_sha256",
        "versioned",
    }
    if not _exact_keys(object_restore, object_keys):
        errors.append("OBJECT_RESTORE_SCHEMA_INVALID")
    else:
        assert isinstance(object_restore, Mapping)
        for key in ("immutable", "old_object_versions_readable", "private", "versioned"):
            _append(errors, object_restore[key] is True, "OBJECT_STORAGE_CONTROL_INVALID")
        _append(errors, _is_sha256(object_restore["restore_manifest_sha256"]), "OBJECT_MANIFEST_HASH_INVALID")
        expected_count = object_restore["objects_expected"]
        restored_count = object_restore["objects_restored"]
        _append(
            errors,
            isinstance(expected_count, int)
            and not isinstance(expected_count, bool)
            and expected_count >= 0
            and restored_count == expected_count,
            "OBJECT_RESTORE_COUNT_MISMATCH",
        )
        _append(errors, object_restore["hash_mismatches"] == 0, "OBJECT_HASH_MISMATCH")

    activation = document.get("activation")
    activation_keys = {
        "graph_completed_before_object_store",
        "projections_started",
        "workers_started",
    }
    if not _exact_keys(activation, activation_keys):
        errors.append("ACTIVATION_SCHEMA_INVALID")
    else:
        assert isinstance(activation, Mapping)
        _append(errors, activation["graph_completed_before_object_store"] is True, "GRAPH_OBJECT_ORDER_UNPROVEN")
        _append(errors, activation["workers_started"] is False, "WORKERS_STARTED_OUT_OF_ORDER")
        _append(errors, activation["projections_started"] is False, "PROJECTIONS_STARTED_OUT_OF_ORDER")

    _validate_objectives(
        document.get("objectives"),
        maximum_rpo_minutes=5,
        maximum_rto_minutes=30,
        errors=errors,
    )
    _validate_rollback(document.get("rollback"), errors)
    _validate_external_effects(document.get("external_effects"), errors)
    required_payloads = {
        "ACTIVATION_FENCE": activation,
        "EXTERNAL_EFFECT_RECONCILIATION": document.get("external_effects"),
        "GRAPH_RESTORE": graph,
        "GRAPH_ROLLBACK": document.get("rollback"),
        "OBJECT_RESTORE": object_restore,
        "PREDECESSOR_CHAIN": document.get("predecessor_receipts"),
        "RPO_RTO_OBSERVATION": document.get("objectives"),
    }
    _validate_receipts(
        document.get("receipts"),
        context_hash=context_hash,
        required_payloads=required_payloads,
        errors=errors,
    )
    return _result(document, scenario, errors)


validate = validate_graph_object_restore


__all__ = [
    "RECOVERY_ORDER",
    "SCHEMA_VERSION",
    "validate",
    "validate_graph_object_restore",
]
