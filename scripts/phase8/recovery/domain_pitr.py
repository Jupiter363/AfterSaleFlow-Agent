from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping, Sequence


SCHEMA_VERSION = "phase8-domain-pitr-fixture.v1"
RECOVERY_ORDER = (
    "DOMAIN",
    "TEMPORAL",
    "GRAPH",
    "OBJECT_STORE",
    "WORKERS",
    "PROJECTIONS",
)
BLOCKED = "BLOCKED"
FIXTURE_ACCEPTED = "FIXTURE_ACCEPTED"
PENDING_EXTERNAL = "PENDING_EXTERNAL"

_SHA1_PATTERN = r"^[0-9a-f]{40}$"
_SHA256_PATTERN = r"^[0-9a-f]{64}$"
_IDENTIFIER_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$"
_TIMESTAMP_PATTERN = (
    r"^\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])"
    r"T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,6})?Z$"
)
_SECRET_KEYS = frozenset(
    {
        "access_key",
        "access-key",
        "api_key",
        "api-key",
        "client_secret",
        "client-secret",
        "credential",
        "credentials",
        "password",
        "private_key",
        "private-key",
        "secret",
        "secret_value",
        "secret-value",
        "token",
    }
)
_CAPABILITIES = MappingProxyType(
    {
        "cloud_api": False,
        "database": False,
        "environment_credentials": False,
        "filesystem_mutation": False,
        "network": False,
        "production_operation": False,
        "subprocess": False,
        "temporal": False,
    }
)


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def canonical_sha256(value: object) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


@dataclass(frozen=True, slots=True)
class ValidationResult:
    scenario: str
    decision: str
    reason_codes: tuple[str, ...]
    document_sha256: str

    @property
    def accepted(self) -> bool:
        return self.decision == FIXTURE_ACCEPTED

    def to_dict(self) -> dict[str, object]:
        return {
            "accepted": self.accepted,
            "capabilities": dict(_CAPABILITIES),
            "decision": self.decision,
            "document_sha256": self.document_sha256,
            "production_checkpoint": PENDING_EXTERNAL,
            "reason_codes": list(self.reason_codes),
            "scenario": self.scenario,
        }


def _append(errors: list[str], condition: bool, code: str) -> None:
    if not condition and code not in errors:
        errors.append(code)


def _is_identifier(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(_IDENTIFIER_PATTERN, value) is not None


def _is_number(value: object) -> bool:
    return (
        not isinstance(value, bool)
        and isinstance(value, (int, float))
        and math.isfinite(value)
        and value >= 0
    )


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(_SHA256_PATTERN, value) is not None


def _contains_secret_key(value: object) -> bool:
    if isinstance(value, Mapping):
        for key, child in value.items():
            normalized = str(key).lower()
            if normalized in _SECRET_KEYS or _contains_secret_key(child):
                return True
    elif isinstance(value, (list, tuple)):
        return any(_contains_secret_key(child) for child in value)
    return False


def _exact_keys(value: object, expected: set[str]) -> bool:
    return isinstance(value, Mapping) and set(value) == expected


def _validate_context(
    document: Mapping[str, object], scenario: str, errors: list[str]
) -> str | None:
    context = document.get("context")
    expected = {
        "attempt_lineage_id",
        "candidate_sha",
        "configuration_sha256",
        "deployment_manifest_sha256",
        "environment_id",
        "observed_at",
        "operator_id",
        "scenario_id",
    }
    if not _exact_keys(context, expected):
        errors.append("CONTEXT_SCHEMA_INVALID")
        return None
    assert isinstance(context, Mapping)
    _append(
        errors,
        isinstance(context["candidate_sha"], str)
        and re.fullmatch(_SHA1_PATTERN, context["candidate_sha"]) is not None,
        "CANDIDATE_SHA_INVALID",
    )
    for key in ("configuration_sha256", "deployment_manifest_sha256"):
        _append(errors, _is_sha256(context[key]), "CONTEXT_HASH_INVALID")
    for key in (
        "attempt_lineage_id",
        "environment_id",
        "operator_id",
        "scenario_id",
    ):
        _append(errors, _is_identifier(context[key]), "CONTEXT_IDENTIFIER_INVALID")
    _append(errors, context["scenario_id"] == scenario, "SCENARIO_CONTEXT_MISMATCH")
    _append(
        errors,
        isinstance(context["observed_at"], str)
        and re.fullmatch(_TIMESTAMP_PATTERN, context["observed_at"]) is not None,
        "CONTEXT_TIMESTAMP_INVALID",
    )
    try:
        context_hash = canonical_sha256(context)
    except (TypeError, ValueError):
        errors.append("CONTEXT_NOT_CANONICAL_JSON")
        return None
    _append(
        errors,
        document.get("context_sha256") == context_hash,
        "CONTEXT_HASH_MISMATCH",
    )
    return context_hash


def _validate_authorization(
    authorization: object,
    *,
    context: object,
    context_hash: str | None,
    expected_scope: str,
    allowed_roles: frozenset[str],
    errors: list[str],
) -> None:
    expected = {
        "authorizer_id",
        "authorizer_role",
        "context_sha256",
        "decision",
        "immutable",
        "operator_id",
        "receipt_sha256",
        "scope",
        "self_approved",
        "signature_verified",
        "signer_authorized",
        "signer_expired",
        "signer_revoked",
    }
    if not _exact_keys(authorization, expected):
        errors.append("AUTHORIZATION_SCHEMA_INVALID")
        return
    assert isinstance(authorization, Mapping)
    _append(errors, authorization["decision"] == "APPROVED", "AUTHORIZATION_MISSING")
    _append(errors, authorization["scope"] == expected_scope, "AUTHORIZATION_SCOPE_MISMATCH")
    _append(errors, authorization["immutable"] is True, "AUTHORIZATION_NOT_IMMUTABLE")
    _append(errors, authorization["signature_verified"] is True, "AUTHORIZATION_SIGNATURE_INVALID")
    _append(errors, authorization["signer_authorized"] is True, "AUTHORIZATION_SIGNER_UNAUTHORIZED")
    _append(errors, authorization["signer_expired"] is False, "AUTHORIZATION_SIGNER_EXPIRED")
    _append(errors, authorization["signer_revoked"] is False, "AUTHORIZATION_SIGNER_REVOKED")
    _append(errors, authorization["self_approved"] is False, "AUTHORIZATION_SELF_APPROVED")
    _append(
        errors,
        authorization["authorizer_role"] in allowed_roles,
        "AUTHORIZATION_ROLE_INVALID",
    )
    operator_id = context.get("operator_id") if isinstance(context, Mapping) else None
    _append(errors, authorization["operator_id"] == operator_id, "AUTHORIZATION_OPERATOR_MISMATCH")
    _append(
        errors,
        _is_identifier(authorization["authorizer_id"])
        and authorization["authorizer_id"] != operator_id,
        "AUTHORIZATION_NOT_INDEPENDENT",
    )
    _append(
        errors,
        context_hash is not None and authorization["context_sha256"] == context_hash,
        "AUTHORIZATION_CONTEXT_MISMATCH",
    )
    unsigned = dict(authorization)
    receipt_hash = unsigned.pop("receipt_sha256")
    try:
        expected_hash = canonical_sha256(unsigned)
    except (TypeError, ValueError):
        expected_hash = None
    _append(errors, _is_sha256(receipt_hash) and receipt_hash == expected_hash, "AUTHORIZATION_RECEIPT_HASH_MISMATCH")


def _validate_receipts(
    receipts: object,
    *,
    context_hash: str | None,
    required_payloads: Mapping[str, object],
    errors: list[str],
) -> None:
    if not isinstance(receipts, (list, tuple)):
        errors.append("RECEIPTS_SCHEMA_INVALID")
        return
    found: dict[str, Mapping[str, object]] = {}
    receipt_ids: set[str] = set()
    expected = {
        "context_sha256",
        "immutable",
        "payload",
        "payload_sha256",
        "receipt_id",
        "receipt_sha256",
        "receipt_type",
        "status",
    }
    for receipt in receipts:
        if not _exact_keys(receipt, expected):
            _append(errors, False, "RECEIPT_SCHEMA_INVALID")
            continue
        assert isinstance(receipt, Mapping)
        receipt_type = receipt["receipt_type"]
        if (
            not _is_identifier(receipt_type)
            or not _is_identifier(receipt["receipt_id"])
            or receipt_type in found
            or receipt["receipt_id"] in receipt_ids
        ):
            _append(errors, False, "RECEIPT_TYPE_INVALID_OR_DUPLICATE")
            continue
        found[receipt_type] = receipt
        receipt_ids.add(receipt["receipt_id"])
        _append(errors, receipt["immutable"] is True, "RECEIPT_NOT_IMMUTABLE")
        _append(errors, receipt["status"] == "VERIFIED", "RECEIPT_STATUS_INVALID")
        _append(
            errors,
            context_hash is not None and receipt["context_sha256"] == context_hash,
            "RECEIPT_CONTEXT_MISMATCH",
        )
        try:
            expected_payload_hash = canonical_sha256(receipt["payload"])
        except (TypeError, ValueError):
            expected_payload_hash = None
        _append(
            errors,
            _is_sha256(receipt["payload_sha256"])
            and receipt["payload_sha256"] == expected_payload_hash,
            "RECEIPT_PAYLOAD_HASH_MISMATCH",
        )
        unsigned = dict(receipt)
        receipt_hash = unsigned.pop("receipt_sha256")
        try:
            expected_receipt_hash = canonical_sha256(unsigned)
        except (TypeError, ValueError):
            expected_receipt_hash = None
        _append(
            errors,
            _is_sha256(receipt_hash) and receipt_hash == expected_receipt_hash,
            "RECEIPT_HASH_MISMATCH",
        )
    _append(errors, set(found) == set(required_payloads), "RECEIPT_SET_INCOMPLETE")
    for receipt_type, payload in required_payloads.items():
        receipt = found.get(receipt_type)
        if receipt is not None:
            _append(
                errors,
                receipt["payload"] == payload,
                "RECEIPT_PAYLOAD_BINDING_MISMATCH",
            )


def _validate_predecessor_receipts(
    value: object,
    *,
    required_stages: tuple[str, ...],
    context: object,
    context_hash: str | None,
    errors: list[str],
) -> None:
    if not isinstance(value, (list, tuple)):
        errors.append("PREDECESSOR_RECEIPT_CHAIN_INVALID")
        return
    if not isinstance(context, Mapping):
        errors.append("PREDECESSOR_CONTEXT_INVALID")
        return
    entry_keys = {
        "context_sha256",
        "context",
        "decision",
        "document_sha256",
        "immutable",
        "receipt_id",
        "receipt_sha256",
        "schema_version",
        "stage",
        "status",
    }
    stages: list[object] = []
    receipt_ids: set[str] = set()
    receipt_hashes: set[str] = set()
    for entry in value:
        if not _exact_keys(entry, entry_keys):
            _append(errors, False, "PREDECESSOR_RECEIPT_SCHEMA_INVALID")
            continue
        assert isinstance(entry, Mapping)
        stages.append(entry["stage"])
        receipt_id = entry["receipt_id"]
        receipt_hash = entry["receipt_sha256"]
        _append(
            errors,
            entry["schema_version"] == "phase8-recovery-stage-receipt.v1",
            "PREDECESSOR_RECEIPT_VERSION_INVALID",
        )
        _append(errors, entry["immutable"] is True, "PREDECESSOR_RECEIPT_MUTABLE")
        _append(
            errors,
            entry["decision"] == FIXTURE_ACCEPTED,
            "PREDECESSOR_DECISION_NOT_ACCEPTED",
        )
        _append(
            errors,
            entry["status"] == "VERIFIED",
            "PREDECESSOR_STATUS_NOT_VERIFIED",
        )
        _append(
            errors,
            _is_identifier(receipt_id) and receipt_id not in receipt_ids,
            "PREDECESSOR_RECEIPT_ID_INVALID",
        )
        _append(
            errors,
            _is_sha256(receipt_hash) and receipt_hash not in receipt_hashes,
            "PREDECESSOR_RECEIPT_HASH_INVALID",
        )
        if isinstance(receipt_id, str):
            receipt_ids.add(receipt_id)
        if isinstance(receipt_hash, str):
            receipt_hashes.add(receipt_hash)
        embedded_context = entry["context"]
        try:
            embedded_context_hash = canonical_sha256(embedded_context)
        except (TypeError, ValueError):
            embedded_context_hash = None
        _append(
            errors,
            isinstance(embedded_context, Mapping)
            and dict(embedded_context) == dict(context),
            "PREDECESSOR_CONTEXT_MISMATCH",
        )
        _append(
            errors,
            context_hash is not None
            and embedded_context_hash == context_hash
            and entry["context_sha256"] == context_hash,
            "PREDECESSOR_CONTEXT_HASH_MISMATCH",
        )
        _append(
            errors,
            _is_sha256(entry["document_sha256"]),
            "PREDECESSOR_DOCUMENT_HASH_INVALID",
        )
        unsigned = dict(entry)
        unsigned.pop("receipt_sha256")
        try:
            expected_receipt_hash = canonical_sha256(unsigned)
        except (TypeError, ValueError):
            expected_receipt_hash = None
        _append(
            errors,
            _is_sha256(receipt_hash) and receipt_hash == expected_receipt_hash,
            "PREDECESSOR_RECEIPT_HASH_MISMATCH",
        )
    _append(
        errors,
        tuple(stages) == required_stages,
        "PREDECESSOR_RECEIPT_CHAIN_INVALID",
    )


def _validate_stage_prefix(
    document: Mapping[str, object], completed_through: str, errors: list[str]
) -> None:
    order = document.get("recovery_order")
    _append(
        errors,
        isinstance(order, (list, tuple)) and tuple(order) == RECOVERY_ORDER,
        "RECOVERY_ORDER_INVALID",
    )
    states = document.get("stage_states")
    if not _exact_keys(states, set(RECOVERY_ORDER)):
        errors.append("RECOVERY_STAGE_SCHEMA_INVALID")
        return
    assert isinstance(states, Mapping)
    completed_index = RECOVERY_ORDER.index(completed_through)
    for index, stage in enumerate(RECOVERY_ORDER):
        expected = "VERIFIED" if index <= completed_index else "NOT_STARTED"
        _append(errors, states[stage] == expected, "RECOVERY_STAGE_OUT_OF_ORDER")


def _validate_objectives(
    objectives: object,
    *,
    maximum_rpo_minutes: float | None,
    maximum_rto_minutes: float | None,
    errors: list[str],
) -> None:
    expected = {
        "approved_rpo_minutes",
        "approved_rto_minutes",
        "observed_rpo_minutes",
        "observed_rto_minutes",
        "objective_source",
        "rollback_on_breach",
    }
    if not _exact_keys(objectives, expected):
        errors.append("OBJECTIVE_SCHEMA_INVALID")
        return
    assert isinstance(objectives, Mapping)
    for key in (
        "approved_rpo_minutes",
        "approved_rto_minutes",
        "observed_rpo_minutes",
        "observed_rto_minutes",
    ):
        _append(errors, _is_number(objectives[key]), "OBJECTIVE_VALUE_INVALID")
    _append(
        errors,
        objectives["objective_source"] == "EXTERNALLY_APPROVED_RELEASE_OBJECTIVE",
        "OBJECTIVE_SOURCE_INVALID",
    )
    _append(errors, objectives["rollback_on_breach"] is True, "OBJECTIVE_ROLLBACK_MISSING")
    if all(_is_number(objectives[key]) for key in expected if key.endswith("minutes")):
        _append(
            errors,
            objectives["observed_rpo_minutes"] <= objectives["approved_rpo_minutes"],
            "RPO_OBJECTIVE_BREACHED",
        )
        _append(
            errors,
            objectives["observed_rto_minutes"] <= objectives["approved_rto_minutes"],
            "RTO_OBJECTIVE_BREACHED",
        )
        if maximum_rpo_minutes is not None:
            _append(
                errors,
                objectives["approved_rpo_minutes"] <= maximum_rpo_minutes,
                "APPROVED_RPO_EXCEEDS_CONTRACT",
            )
        if maximum_rto_minutes is not None:
            _append(
                errors,
                objectives["approved_rto_minutes"] <= maximum_rto_minutes,
                "APPROVED_RTO_EXCEEDS_CONTRACT",
            )


def _validate_rollback(rollback: object, errors: list[str]) -> None:
    expected = {
        "automatic_execution",
        "available",
        "on_rpo_breach",
        "on_rto_breach",
        "preserves_formal_facts",
        "target_id",
        "validated",
    }
    if not _exact_keys(rollback, expected):
        errors.append("ROLLBACK_SCHEMA_INVALID")
        return
    assert isinstance(rollback, Mapping)
    _append(errors, rollback["available"] is True, "ROLLBACK_UNAVAILABLE")
    _append(errors, rollback["validated"] is True, "ROLLBACK_NOT_VALIDATED")
    _append(errors, rollback["automatic_execution"] is False, "ROLLBACK_EXECUTION_FORBIDDEN")
    _append(errors, rollback["on_rpo_breach"] is True, "ROLLBACK_RPO_GUARD_MISSING")
    _append(errors, rollback["on_rto_breach"] is True, "ROLLBACK_RTO_GUARD_MISSING")
    _append(errors, rollback["preserves_formal_facts"] is True, "ROLLBACK_FACT_LOSS")
    _append(errors, _is_identifier(rollback["target_id"]), "ROLLBACK_TARGET_INVALID")


def _validate_external_effects(value: object, errors: list[str]) -> None:
    expected = {
        "blind_replay",
        "declared_effect_count",
        "effects",
        "policy",
        "receipt_inventory_complete",
        "replay_requested",
    }
    if not _exact_keys(value, expected):
        errors.append("EXTERNAL_EFFECT_SCHEMA_INVALID")
        return
    assert isinstance(value, Mapping)
    effects = value["effects"]
    _append(errors, value["blind_replay"] is False, "BLIND_EXTERNAL_EFFECT_REPLAY")
    _append(errors, value["replay_requested"] is False, "EXTERNAL_EFFECT_REPLAY_REQUESTED")
    _append(errors, value["receipt_inventory_complete"] is True, "EXTERNAL_EFFECT_INVENTORY_INCOMPLETE")
    _append(
        errors,
        value["policy"] == "RECEIPT_RECONCILE_COMPENSATE_OR_MANUAL",
        "EXTERNAL_EFFECT_POLICY_INVALID",
    )
    if not isinstance(effects, (list, tuple)):
        errors.append("EXTERNAL_EFFECT_LIST_INVALID")
        return
    _append(
        errors,
        isinstance(value["declared_effect_count"], int)
        and not isinstance(value["declared_effect_count"], bool)
        and value["declared_effect_count"] == len(effects),
        "EXTERNAL_EFFECT_COUNT_MISMATCH",
    )
    seen: set[str] = set()
    effect_keys = {
        "disposition",
        "effect_id",
        "original_receipt_sha256",
        "replayed",
    }
    for effect in effects:
        if not _exact_keys(effect, effect_keys):
            _append(errors, False, "EXTERNAL_EFFECT_ENTRY_INVALID")
            continue
        assert isinstance(effect, Mapping)
        effect_id = effect["effect_id"]
        _append(errors, _is_identifier(effect_id) and effect_id not in seen, "EXTERNAL_EFFECT_ID_INVALID")
        if isinstance(effect_id, str):
            seen.add(effect_id)
        _append(errors, _is_sha256(effect["original_receipt_sha256"]), "EXTERNAL_EFFECT_RECEIPT_INVALID")
        _append(errors, effect["replayed"] is False, "EXTERNAL_EFFECT_WAS_REPLAYED")
        _append(
            errors,
            effect["disposition"] in {"CONFIRMED", "COMPENSATED", "MANUAL_REVIEW"},
            "EXTERNAL_EFFECT_DISPOSITION_INVALID",
        )


def _result(document: object, scenario: str, errors: Sequence[str]) -> ValidationResult:
    try:
        document_hash = canonical_sha256(document)
    except (TypeError, ValueError):
        document_hash = "0" * 64
        errors = (*errors, "DOCUMENT_NOT_CANONICAL_JSON")
    reasons = tuple(dict.fromkeys(errors))
    return ValidationResult(
        scenario=scenario,
        decision=FIXTURE_ACCEPTED if not reasons else BLOCKED,
        reason_codes=reasons,
        document_sha256=document_hash,
    )


def validate_domain_pitr(document: object) -> ValidationResult:
    """Validate an explicit Domain PITR evidence fixture without performing I/O."""

    scenario = "DOMAIN_PITR"
    errors: list[str] = []
    if not isinstance(document, Mapping):
        return _result(document, scenario, ("DOCUMENT_SCHEMA_INVALID",))
    expected = {
        "authorization",
        "backup",
        "context",
        "context_sha256",
        "external_effects",
        "objectives",
        "receipts",
        "reconciliation",
        "recovery_order",
        "restore",
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
        expected_scope="DOMAIN_PITR_EXTERNAL_DRILL",
        allowed_roles=frozenset({"DBA", "SRE"}),
        errors=errors,
    )
    _validate_stage_prefix(document, "DOMAIN", errors)

    backup = document.get("backup")
    backup_keys = {
        "backup_id",
        "backup_sha256",
        "encrypted",
        "immutable",
        "latest_committed_sequence",
        "restorable",
    }
    if not _exact_keys(backup, backup_keys):
        errors.append("BACKUP_SCHEMA_INVALID")
    else:
        assert isinstance(backup, Mapping)
        _append(errors, _is_identifier(backup["backup_id"]), "BACKUP_ID_INVALID")
        _append(errors, _is_sha256(backup["backup_sha256"]), "BACKUP_HASH_INVALID")
        _append(errors, backup["encrypted"] is True, "BACKUP_NOT_ENCRYPTED")
        _append(errors, backup["immutable"] is True, "BACKUP_NOT_IMMUTABLE")
        _append(errors, backup["restorable"] is True, "BACKUP_NOT_RESTORABLE")
        _append(
            errors,
            isinstance(backup["latest_committed_sequence"], int)
            and not isinstance(backup["latest_committed_sequence"], bool)
            and backup["latest_committed_sequence"] >= 0,
            "BACKUP_COMMIT_BOUNDARY_INVALID",
        )

    restore = document.get("restore")
    restore_keys = {
        "committed_transactions_lost",
        "consistency_verified",
        "internal_table_edits",
        "restored_through_sequence",
        "supported_restore_interface",
        "target_id",
    }
    if not _exact_keys(restore, restore_keys):
        errors.append("RESTORE_SCHEMA_INVALID")
    else:
        assert isinstance(restore, Mapping)
        _append(errors, restore["internal_table_edits"] is False, "INTERNAL_TABLE_EDIT_FORBIDDEN")
        _append(errors, restore["supported_restore_interface"] is True, "UNSUPPORTED_RESTORE_INTERFACE")
        _append(errors, restore["consistency_verified"] is True, "RESTORE_CONSISTENCY_UNVERIFIED")
        _append(errors, restore["committed_transactions_lost"] == 0, "COMMITTED_TRANSACTION_LOSS")
        _append(errors, _is_identifier(restore["target_id"]), "RESTORE_TARGET_INVALID")
        if isinstance(backup, Mapping):
            restored_sequence = restore["restored_through_sequence"]
            committed_sequence = backup.get("latest_committed_sequence")
            _append(
                errors,
                restored_sequence >= committed_sequence
                if isinstance(restored_sequence, int)
                and not isinstance(restored_sequence, bool)
                and isinstance(committed_sequence, int)
                and not isinstance(committed_sequence, bool)
                else False,
                "RESTORE_BEHIND_COMMIT_BOUNDARY",
            )

    reconciliation = document.get("reconciliation")
    reconciliation_keys = {
        "accepted_commands_missing",
        "duplicate_formal_facts",
        "formal_facts_preserved",
        "outbox_gaps",
        "projection_rebuild_deferred",
    }
    if not _exact_keys(reconciliation, reconciliation_keys):
        errors.append("RECONCILIATION_SCHEMA_INVALID")
    else:
        assert isinstance(reconciliation, Mapping)
        _append(errors, reconciliation["accepted_commands_missing"] == 0, "ACCEPTED_COMMAND_LOSS")
        _append(errors, reconciliation["duplicate_formal_facts"] == 0, "DUPLICATE_FORMAL_FACT")
        _append(errors, reconciliation["outbox_gaps"] == 0, "OUTBOX_GAP")
        _append(errors, reconciliation["formal_facts_preserved"] is True, "FORMAL_FACTS_NOT_PRESERVED")
        _append(errors, reconciliation["projection_rebuild_deferred"] is True, "PROJECTION_REBUILT_OUT_OF_ORDER")

    _validate_objectives(
        document.get("objectives"),
        maximum_rpo_minutes=0,
        maximum_rto_minutes=None,
        errors=errors,
    )
    _validate_rollback(document.get("rollback"), errors)
    _validate_external_effects(document.get("external_effects"), errors)
    required_payloads = {
        "DOMAIN_BACKUP": backup,
        "DOMAIN_RECONCILIATION": reconciliation,
        "DOMAIN_RESTORE": restore,
        "DOMAIN_ROLLBACK": document.get("rollback"),
        "EXTERNAL_EFFECT_RECONCILIATION": document.get("external_effects"),
        "RPO_RTO_OBSERVATION": document.get("objectives"),
    }
    _validate_receipts(
        document.get("receipts"),
        context_hash=context_hash,
        required_payloads=required_payloads,
        errors=errors,
    )
    return _result(document, scenario, errors)


validate = validate_domain_pitr


__all__ = [
    "BLOCKED",
    "FIXTURE_ACCEPTED",
    "PENDING_EXTERNAL",
    "RECOVERY_ORDER",
    "SCHEMA_VERSION",
    "ValidationResult",
    "canonical_json_bytes",
    "canonical_sha256",
    "validate",
    "validate_domain_pitr",
]
