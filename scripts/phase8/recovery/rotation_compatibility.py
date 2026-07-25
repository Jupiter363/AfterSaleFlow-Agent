from __future__ import annotations

from typing import Mapping

from scripts.phase8.recovery.domain_pitr import (
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
    _validate_receipts,
    _validate_rollback,
)


SCHEMA_VERSION = "phase8-rotation-compatibility-fixture.v1"
ROTATION_TYPES = frozenset(
    {"CERTIFICATE", "CODEC_KEY", "DATABASE_CREDENTIAL", "SERVICE_CREDENTIAL"}
)
ARTIFACT_TYPES = ("TEMPORAL_HISTORY", "GRAPH_CHECKPOINT", "CODEC_PAYLOAD")


def validate_rotation_compatibility(document: object) -> ValidationResult:
    """Validate compatibility evidence for a proposed rotation using fixture data only."""

    scenario = "ROTATION_COMPATIBILITY"
    errors: list[str] = []
    if not isinstance(document, Mapping):
        return _result(document, scenario, ("DOCUMENT_SCHEMA_INVALID",))
    expected = {
        "authorization",
        "compatibility",
        "context",
        "context_sha256",
        "external_effects",
        "readability_samples",
        "receipts",
        "rollback",
        "rotation",
        "scenario",
        "schema_version",
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
        expected_scope="ROTATION_COMPATIBILITY_EXTERNAL_DRILL",
        allowed_roles=frozenset({"SECURITY", "SRE"}),
        errors=errors,
    )

    rotation = document.get("rotation")
    rotation_keys = {
        "external_execution_performed",
        "new_material_id",
        "new_writes_active",
        "old_material_id",
        "old_material_retired",
        "overlap_enabled",
        "rotation_id",
        "rotation_type",
    }
    if not _exact_keys(rotation, rotation_keys):
        errors.append("ROTATION_SCHEMA_INVALID")
    else:
        assert isinstance(rotation, Mapping)
        for key in ("rotation_id", "old_material_id", "new_material_id"):
            _append(errors, _is_identifier(rotation[key]), "ROTATION_IDENTIFIER_INVALID")
        _append(errors, rotation["old_material_id"] != rotation["new_material_id"], "ROTATION_MATERIAL_IDS_EQUAL")
        _append(errors, rotation["rotation_type"] in ROTATION_TYPES, "ROTATION_TYPE_INVALID")
        _append(errors, rotation["overlap_enabled"] is True, "ROTATION_OVERLAP_MISSING")
        _append(errors, rotation["new_writes_active"] is True, "ROTATION_NEW_WRITES_INACTIVE")
        _append(errors, rotation["old_material_retired"] is False, "OLD_MATERIAL_RETIRED_EARLY")
        _append(errors, rotation["external_execution_performed"] is False, "REAL_ROTATION_EXECUTION_FORBIDDEN")

    compatibility = document.get("compatibility")
    compatibility_keys = {
        "active_old_reference_count",
        "new_material_readable",
        "old_checkpoint_readable",
        "old_codec_readable",
        "old_history_readable",
        "old_key_readable",
        "reference_inventory_complete",
        "retirement_decision",
    }
    if not _exact_keys(compatibility, compatibility_keys):
        errors.append("ROTATION_COMPATIBILITY_SCHEMA_INVALID")
    else:
        assert isinstance(compatibility, Mapping)
        for key in (
            "new_material_readable",
            "old_checkpoint_readable",
            "old_codec_readable",
            "old_history_readable",
            "old_key_readable",
            "reference_inventory_complete",
        ):
            _append(errors, compatibility[key] is True, "ROTATION_READ_COMPATIBILITY_MISSING")
        active_count = compatibility["active_old_reference_count"]
        _append(
            errors,
            isinstance(active_count, int)
            and not isinstance(active_count, bool)
            and active_count > 0,
            "ACTIVE_OLD_REFERENCE_COUNT_INVALID",
        )
        _append(
            errors,
            compatibility["retirement_decision"] == "RETAIN_OLD_READ_PATH",
            "OLD_READ_PATH_RETIREMENT_FORBIDDEN",
        )

    samples = document.get("readability_samples")
    if not isinstance(samples, (list, tuple)):
        errors.append("READABILITY_SAMPLE_SCHEMA_INVALID")
    else:
        sample_keys = {
            "artifact_id",
            "artifact_sha256",
            "artifact_type",
            "codec_id",
            "key_id",
            "readable",
            "readback_sha256",
        }
        found_types: set[str] = set()
        found_ids: set[str] = set()
        for sample in samples:
            if not _exact_keys(sample, sample_keys):
                _append(errors, False, "READABILITY_SAMPLE_SCHEMA_INVALID")
                continue
            assert isinstance(sample, Mapping)
            artifact_id = sample["artifact_id"]
            _append(
                errors,
                _is_identifier(artifact_id) and artifact_id not in found_ids,
                "READABILITY_SAMPLE_ID_INVALID",
            )
            if isinstance(artifact_id, str):
                found_ids.add(artifact_id)
            _append(errors, sample["artifact_type"] in ARTIFACT_TYPES, "READABILITY_ARTIFACT_TYPE_INVALID")
            if isinstance(sample["artifact_type"], str):
                found_types.add(sample["artifact_type"])
            _append(errors, _is_identifier(sample["codec_id"]), "READABILITY_CODEC_ID_INVALID")
            _append(errors, _is_identifier(sample["key_id"]), "READABILITY_KEY_ID_INVALID")
            _append(errors, _is_sha256(sample["artifact_sha256"]), "READABILITY_HASH_INVALID")
            _append(
                errors,
                sample["artifact_sha256"] == sample["readback_sha256"],
                "READABILITY_HASH_MISMATCH",
            )
            _append(errors, sample["readable"] is True, "OLD_ARTIFACT_UNREADABLE")
        _append(errors, found_types == set(ARTIFACT_TYPES), "READABILITY_ARTIFACT_COVERAGE_INCOMPLETE")

    _validate_rollback(document.get("rollback"), errors)
    _validate_external_effects(document.get("external_effects"), errors)
    required_payloads = {
        "EXTERNAL_EFFECT_RECONCILIATION": document.get("external_effects"),
        "READABILITY_COMPATIBILITY": samples,
        "ROTATION_COMPATIBILITY": compatibility,
        "ROTATION_PLAN": rotation,
        "ROTATION_ROLLBACK": document.get("rollback"),
    }
    _validate_receipts(
        document.get("receipts"),
        context_hash=context_hash,
        required_payloads=required_payloads,
        errors=errors,
    )
    return _result(document, scenario, errors)


validate = validate_rotation_compatibility


__all__ = [
    "ARTIFACT_TYPES",
    "ROTATION_TYPES",
    "SCHEMA_VERSION",
    "validate",
    "validate_rotation_compatibility",
]
