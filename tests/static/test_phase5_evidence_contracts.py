from __future__ import annotations

import copy
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import jsonschema
import pytest
import rfc8785
import yaml


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/evidence/v2"
VALID_ROOT = CONTRACT_ROOT / "fixtures/valid"
INVALID_ROOT = CONTRACT_ROOT / "fixtures/invalid"

SCHEMA_BY_PREFIX = {
    "evidence-asset-capability": (
        "evidence-asset-capability.schema.json",
        "capability_hash",
    ),
    "evidence-batch-manifest": (
        "evidence-batch-manifest.schema.json",
        "manifest_hash",
    ),
    "evidence-finalization-receipt": (
        "evidence-finalization-receipt.schema.json",
        "receipt_hash",
    ),
    "evidence-item-proposal": (
        "evidence-item-proposal.schema.json",
        "assessment_hash",
    ),
    "evidence-process-projection": (
        "evidence-process-projection.schema.json",
        "projection_hash",
    ),
    "evidence-terminal-proposal": (
        "evidence-terminal-proposal.schema.json",
        "proposal_hash",
    ),
}

OPERATION_KEY_FIELDS = {
    "MANIFEST_ISSUE": (
        "evidence.manifest.issue",
        ("submission_batch_id", "submission_revision"),
    ),
    "GRAPH_REQUEST": (
        "evidence.graph.request",
        ("manifest_hash", "logical_run_id"),
    ),
    "PARTY_COMPLETE": (
        "evidence.party.complete",
        ("participant_id", "completion_request_id"),
    ),
    "DEADLINE_WARN": ("evidence.deadline.warn", ("deadline_revision",)),
    "DEADLINE_EXPIRE": ("evidence.deadline.expire", ("deadline_revision",)),
    "BATCH_MERGE": (
        "evidence.batch.merge",
        ("manifest_hash", "dossier_target_version"),
    ),
    "DOSSIER_FREEZE": ("evidence.dossier.freeze", ("dossier_target_version",)),
    "HEARING_OPEN": ("evidence.hearing.open", ("freeze_receipt_hash",)),
}
EXPECTED_ROOT_FILES = {
    "compatibility-matrix.yaml",
    *(binding[0] for binding in SCHEMA_BY_PREFIX.values()),
}
EXPECTED_VALID_FIXTURES = {
    "evidence-asset-capability-valid.json",
    "evidence-batch-manifest-synthetic-1-valid.json",
    "evidence-batch-manifest-synthetic-8-valid.json",
    "evidence-batch-manifest-synthetic-100-valid.json",
    "evidence-finalization-receipt-valid.json",
    "evidence-item-proposal-valid.json",
    "evidence-process-projection-legacy-unavailable-valid.json",
    "evidence-process-projection-valid.json",
    "evidence-terminal-proposal-valid.json",
}
EXPECTED_INVALID_FIXTURES = {
    "evidence-asset-capability-credential.json",
    "evidence-batch-manifest-formal-action.json",
    "evidence-batch-manifest-legacy-output-pin.json",
    "evidence-batch-manifest-public-51.json",
    "evidence-batch-manifest-signature-algorithm.json",
    "evidence-batch-manifest-unsigned.json",
    "evidence-finalization-receipt-real-formal-write.json",
    "evidence-item-proposal-formal-action.json",
    "evidence-process-projection-temporal-real-shadow.json",
    "evidence-terminal-proposal-formal-action.json",
}
FORBIDDEN_AUTHORITY_KEYS = {
    "chain_of_thought",
    "complete_party",
    "credentials",
    "freeze_dossier",
    "hidden_reasoning",
    "merge_evidence",
    "open_hearing",
    "production_traffic",
    "real_case_shadow",
    "temporal_allocation",
    "tool_calls",
}
PROFILE_VERSION_FIELDS = (
    "graph_version",
    "checkpoint_schema_version",
    "state_schema_version",
    "prompt_version",
    "model_profile_id",
    "assessment_output_schema_version",
    "terminal_output_schema_version",
    "policy_version",
    "guardrail_version",
    "tool_policy_version",
)
INVALID_FIXTURE_FAILURE_VALIDATORS = {
    "evidence-asset-capability-credential.json": {"additionalProperties"},
    "evidence-batch-manifest-formal-action.json": {"additionalProperties"},
    "evidence-batch-manifest-legacy-output-pin.json": {"additionalProperties"},
    "evidence-batch-manifest-public-51.json": {"maximum", "maxItems"},
    "evidence-batch-manifest-signature-algorithm.json": {"const"},
    "evidence-batch-manifest-unsigned.json": {"required"},
    "evidence-finalization-receipt-real-formal-write.json": {"const"},
    "evidence-item-proposal-formal-action.json": {"additionalProperties"},
    "evidence-process-projection-temporal-real-shadow.json": {"const", "enum"},
    "evidence-terminal-proposal-formal-action.json": {"additionalProperties"},
}


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _schema(name: str) -> dict[str, Any]:
    return _load_json(CONTRACT_ROOT / name)


def _validator(name: str) -> jsonschema.Draft202012Validator:
    return jsonschema.Draft202012Validator(
        _schema(name),
        format_checker=jsonschema.Draft202012Validator.FORMAT_CHECKER,
    )


def _fixture_schema(path: Path) -> tuple[str, str]:
    for prefix, binding in SCHEMA_BY_PREFIX.items():
        if path.name.startswith(prefix):
            return binding
    raise AssertionError(f"unmapped Evidence fixture: {path.name}")


def _canonical_hash(value: dict[str, Any], *omitted: str) -> str:
    preimage = copy.deepcopy(value)
    for field in omitted:
        preimage.pop(field)
    return hashlib.sha256(rfc8785.dumps(preimage)).hexdigest()


def _declared_hash_omissions(schema: dict[str, Any], hash_field: str) -> tuple[str, ...]:
    declaration = schema["x-self-hash"]
    assert declaration["field"] == hash_field
    omitted = declaration.get("omit_fields", [hash_field])
    assert isinstance(omitted, list) and omitted
    assert omitted[0] == hash_field
    assert len(omitted) == len(set(omitted))
    return tuple(omitted)


def _semantic_ids(schema: dict[str, Any]) -> set[str]:
    return {constraint["id"] for constraint in schema["x-semantic-constraints"]}


def _semantic_rule(schema: dict[str, Any], constraint_id: str) -> str:
    return next(
        constraint["rule"]
        for constraint in schema["x-semantic-constraints"]
        if constraint["id"] == constraint_id
    )


def _parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def _validate_manifest_semantics(value: dict[str, Any]) -> None:
    issued_at = _parse_time(value["issued_at"])
    not_before = _parse_time(value["not_before"])
    expires_at = _parse_time(value["expires_at"])
    if issued_at > not_before or not_before >= expires_at:
        raise ValueError("manifest expiry order is invalid")
    command = value["command_binding"]
    if _parse_time(command["submitted_at"]) >= _parse_time(command["deadline_at"]):
        raise ValueError("manifest command deadline order is invalid")
    items = value["items"]
    item_keys = [item["evidence_id"] for item in items]
    if value["item_count"] != len(items):
        raise ValueError("manifest item_count drifted")
    if value["ordered_item_keys"] != sorted(item_keys):
        raise ValueError("manifest ordered item membership drifted")
    if len(item_keys) != len(set(item_keys)):
        raise ValueError("manifest contains duplicate Evidence IDs")
    for item in items:
        if item["item_hash"] != _canonical_hash(item, "item_hash"):
            raise ValueError("manifest item self-hash drifted")
    if value["execution_scope"] == "SIGNED_SYNTHETIC_ONLY":
        if value["item_count"] not in {1, 8, 100}:
            raise ValueError("synthetic count is not admitted")
        for item in items:
            if not item["object_ref"].startswith("urn:synthetic-evidence:"):
                raise ValueError("synthetic manifest references a production object")
            parse_ref = item["parse_ref"]
            if parse_ref is not None and not parse_ref.startswith(
                "urn:synthetic-evidence-parse:"
            ):
                raise ValueError("synthetic manifest references a production parse")
    elif value["execution_scope"] == "PUBLIC_CONTRACT_ONLY":
        if not 1 <= value["item_count"] <= 50:
            raise ValueError("public manifest exceeds the approved 50-item contract")
        if value["writer_mode"] != "LEGACY" or value["graph_execution_allowed"]:
            raise ValueError("public manifest attempted Graph execution")
        if "synthetic_fixture_id" in value:
            raise ValueError("public manifest carries a synthetic fixture identity")
        for item in items:
            if (
                item["privacy_basis"] != "OWNER_CONSENT"
                or not item["object_ref"].startswith("urn:evidence-object:")
            ):
                raise ValueError("public manifest carries a synthetic Evidence item")
    else:
        raise ValueError("unknown manifest execution scope")


def _validate_capability_semantics(
    value: dict[str, Any],
    *,
    trusted_now: datetime,
    consumed_nonces: set[tuple[str, str]],
) -> None:
    issued_at = _parse_time(value["issued_at"])
    expires_at = _parse_time(value["expires_at"])
    if issued_at >= expires_at or trusted_now < issued_at or trusted_now >= expires_at:
        raise ValueError("asset capability is not currently valid")
    replay_key = (value["capability_id"], value["nonce"])
    if replay_key in consumed_nonces:
        raise ValueError("asset capability nonce replayed")
    consumed_nonces.add(replay_key)


def _expected_operation_key(value: dict[str, Any]) -> str:
    prefix, binding_fields = OPERATION_KEY_FIELDS[value["operation_type"]]
    parts = [value["case_id"], str(value["room_epoch"])]
    parts.extend(str(value["operation_binding"][field]) for field in binding_fields)
    return f"{prefix}:{':'.join(parts)}"


def _validate_receipt_semantics(value: dict[str, Any]) -> None:
    if value["operation_key"] != _expected_operation_key(value):
        raise ValueError("receipt semantic operation key drifted")
    if (
        value["commit_scope"] != "ISOLATED_SYNTHETIC_LEDGER"
        or value["formal_domain_write"]
        or value["formal_sink_eligible"]
        or value["merge_count"] != 0
        or value["domain_event_ids"]
        or value["outbox_ids"]
        or value["hearing_opened"]
    ):
        raise ValueError("engineering receipt contains a formal effect")


def _validate_terminal_semantics(value: dict[str, Any]) -> None:
    keys = value["ordered_item_keys"]
    assessment_keys = [item["evidence_id"] for item in value["assessment_refs"]]
    if value["item_count"] != len(keys) or assessment_keys != keys:
        raise ValueError("terminal proposal coverage drifted")
    if len(assessment_keys) != len(set(assessment_keys)):
        raise ValueError("terminal proposal contains duplicate assessment keys")
    admitted = set(keys)
    if any(
        evidence_id not in admitted
        for link in value["proposed_fact_links"]
        for evidence_id in link["evidence_ids"]
    ):
        raise ValueError("terminal proposal links an unadmitted Evidence ID")
    if any(item["evidence_id"] not in admitted for item in value["proposed_review_items"]):
        raise ValueError("terminal proposal reviews an unadmitted Evidence ID")


def _validate_projection_semantics(value: dict[str, Any]) -> None:
    if value["audience"] != value["viewer_actor_role"]:
        raise ValueError("projection audience differs from the authenticated viewer")
    if value["warning_sent"] != (value["warning_sent_at"] is not None):
        raise ValueError("projection warning timestamp drifted")
    completion = value["party_completion"]
    for party in ("initiator", "respondent"):
        completed = completion[f"{party}_completed"]
        if (completion[f"{party}_receipt_ref"] is not None) != completed:
            raise ValueError("projection completion receipt ref drifted")
        if (completion[f"{party}_receipt_hash"] is not None) != completed:
            raise ValueError("projection completion receipt hash drifted")
    if value["graph_runtime_mode"] == "DISABLED" and value["active_graph_run"] is not None:
        raise ValueError("disabled projection exposes an active Graph run")
    if value["graph_runtime_mode"] == "SIGNED_SYNTHETIC_SHADOW":
        if (
            value["writer_mode"] != "SHADOW"
            or not value["tenant_surrogate"].startswith("TENANT_P5_SYNTHETIC_")
            or not value["case_id"].startswith("CASE_P5_SYNTHETIC_")
        ):
            raise ValueError("synthetic projection escaped its closed identity scope")
    if value["history_mode"] and (
        value["active_graph_run"] is not None
        or value["pending_operation_key"] is not None
    ):
        raise ValueError("history projection retains an active operation")
    if value["projection_state"] == "FAILED" and (
        value["pending_state"] != "FAILED" or value["recovery"]["state"] != "FAILED"
    ):
        raise ValueError("failed projection lacks a failed recovery state")
    if value["room_phase"] == "COMPLETED" and (
        value["terminal_reason"] is None
        or value["pending_state"] != "NONE"
        or value["active_graph_run"] is not None
    ):
        raise ValueError("completed projection retains nonterminal state")
    if value["room_phase"] != "COMPLETED" and value["terminal_reason"] is not None:
        raise ValueError("nonterminal projection carries a terminal reason")
    counts = value["assessment_counts"]
    actual = sum(
        counts[field]
        for field in (
            "completed_count",
            "needs_review_count",
            "failed_count",
            "pending_count",
        )
    )
    if actual != counts["manifest_item_count"]:
        raise ValueError("projection assessment counts drifted")
    if value["pending_state"] == "AGENT_RUNNING":
        run = value["active_graph_run"]
        expected = (
            f"evidence.graph.request:{value['case_id']}:{value['room_epoch']}:"
            f"{run['manifest_hash']}:{run['logical_run_id']}"
        )
        if value["pending_operation_key"] != expected:
            raise ValueError("projection active Graph operation key drifted")
    if value["room_phase"] == "READY_TO_FREEZE":
        if (
            value["terminal_proposal"] is None
            or counts["pending_count"] != 0
            or counts["failed_count"] != 0
            or counts["completed_count"] + counts["needs_review_count"]
            != counts["manifest_item_count"]
        ):
            raise ValueError("projection is not ready to freeze")


def _public_manifest(count: int) -> dict[str, Any]:
    value = copy.deepcopy(
        _load_json(VALID_ROOT / "evidence-batch-manifest-synthetic-100-valid.json")
    )
    value["execution_scope"] = "PUBLIC_CONTRACT_ONLY"
    value["writer_mode"] = "LEGACY"
    value["graph_execution_allowed"] = False
    value.pop("synthetic_fixture_id")
    value["tenant_surrogate"] = "TENANT_PUBLIC_1"
    value["case_id"] = "CASE_PUBLIC_1"
    value["item_count"] = count
    value["ordered_item_keys"] = value["ordered_item_keys"][:count]
    value["items"] = value["items"][:count]
    for index, item in enumerate(value["items"], start=1):
        item["object_ref"] = f"urn:evidence-object:case-public/item-{index:03d}"
        if item["parse_ref"] is not None:
            item["parse_ref"] = f"urn:evidence-parse:case-public/item-{index:03d}"
        item["privacy_basis"] = "OWNER_CONSENT"
        item["item_hash"] = _canonical_hash(item, "item_hash")
    value["manifest_hash"] = _canonical_hash(value, "manifest_hash")
    return value


def test_contract_tree_has_the_exact_p5_0_file_set() -> None:
    assert {path.name for path in CONTRACT_ROOT.iterdir() if path.is_file()} == (
        EXPECTED_ROOT_FILES
    )
    assert {path.name for path in VALID_ROOT.iterdir() if path.is_file()} == (
        EXPECTED_VALID_FIXTURES
    )
    assert {path.name for path in INVALID_ROOT.iterdir() if path.is_file()} == (
        EXPECTED_INVALID_FIXTURES
    )


def test_contract_matrix_and_all_nested_object_schemas_are_closed() -> None:
    matrix = yaml.safe_load(
        (CONTRACT_ROOT / "compatibility-matrix.yaml").read_text(encoding="utf-8")
    )
    expected = {
        "evidence-batch-manifest.v1",
        "evidence-asset-capability.v1",
        "evidence-item-assessment.v1",
        "evidence-batch-proposal.v1",
        "evidence-finalization-receipt.v1",
        "evidence-process-projection.v1",
    }
    assert set(matrix["contracts"]) == expected
    assert matrix["runtime_gate"] == {
        "graph_runtime_default": "DISABLED",
        "allowed_modes": ["DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
        "public_submission_max": 50,
        "signed_synthetic_counts": [1, 8, 100],
        "synthetic_100_formal_sink_eligible": False,
        "real_case_shadow_allowed": False,
        "temporal_evidence_allocation_allowed": False,
        "formal_graph_sink_allowed": False,
        "promotion_allowed": False,
    }
    capability_matrix = matrix["contracts"]["evidence-asset-capability.v1"]
    assert capability_matrix["self_hash_omits"] == ["capability_hash", "signature"]
    assert capability_matrix["signature_covers"] == "capability_hash"
    manifest_matrix = matrix["contracts"]["evidence-batch-manifest.v1"]
    assert manifest_matrix["self_hash_omits"] == ["manifest_hash", "signature"]
    assert manifest_matrix["signature_covers"] == "manifest_hash"

    for contract_name, contract in matrix["contracts"].items():
        schema = _schema(contract["file"])
        jsonschema.Draft202012Validator.check_schema(schema)
        assert schema["additionalProperties"] is False
        assert contract["accepts"] == [contract_name]
        assert contract_name in contract["responsibilities"]

        def assert_closed(node: Any, path: str = "$") -> None:
            if isinstance(node, dict):
                if node.get("type") == "object":
                    assert node.get("additionalProperties") is False, path
                for key, child in node.items():
                    assert_closed(child, f"{path}/{key}")
            elif isinstance(node, list):
                for index, child in enumerate(node):
                    assert_closed(child, f"{path}/{index}")

        assert_closed(schema)


def test_valid_fixtures_validate_fit_size_and_have_exact_self_hashes() -> None:
    observed_counts: set[int] = set()
    for path in sorted(VALID_ROOT.glob("*.json")):
        schema_name, hash_field = _fixture_schema(path)
        schema = _schema(schema_name)
        value = _load_json(path)
        assert not list(_validator(schema_name).iter_errors(value)), path.name
        assert len(rfc8785.dumps(value)) <= schema["x-max-encoded-bytes"], path.name
        omitted = _declared_hash_omissions(schema, hash_field)
        assert value[hash_field] == _canonical_hash(value, *omitted), path.name
        if value["schema_version"] == "evidence-batch-manifest.v1":
            _validate_manifest_semantics(value)
            observed_counts.add(value["item_count"])
        if value["schema_version"] == "evidence-finalization-receipt.v1":
            _validate_receipt_semantics(value)
        if value["schema_version"] == "evidence-batch-proposal.v1":
            _validate_terminal_semantics(value)
        if value["schema_version"] == "evidence-process-projection.v1":
            _validate_projection_semantics(value)
    assert observed_counts == {1, 8, 100}


def test_invalid_fixtures_are_rejected_by_their_closed_schema() -> None:
    paths = sorted(INVALID_ROOT.glob("*.json"))
    assert {path.name for path in paths} == set(INVALID_FIXTURE_FAILURE_VALIDATORS)
    for path in paths:
        schema_name, hash_field = _fixture_schema(path)
        schema = _schema(schema_name)
        value = _load_json(path)
        assert value[hash_field] == _canonical_hash(
            value, *_declared_hash_omissions(schema, hash_field)
        ), f"{path.name}: invalid fixture has a stale canonical hash"
        errors = list(_validator(schema_name).iter_errors(value))
        assert errors, path.name
        assert any(
            error.validator in INVALID_FIXTURE_FAILURE_VALIDATORS[path.name]
            for error in errors
        ), f"{path.name}: rejection no longer proves its named failure reason"


def test_manifest_uses_direct_java_signature_before_graph_or_checkpoint_mutation() -> None:
    schema = _schema("evidence-batch-manifest.schema.json")
    manifest = _load_json(VALID_ROOT / "evidence-batch-manifest-synthetic-1-valid.json")

    assert schema["x-self-hash"] == {
        "algorithm": "SHA-256",
        "field": "manifest_hash",
        "preimage": "omit_top_level_fields",
        "omit_fields": ["manifest_hash", "signature"],
    }
    assert schema["x-signature"] == {
        "algorithm": "ES256",
        "covers": "manifest_hash",
        "encoding": "JOSE_P1363_BASE64URL",
    }
    assert {
        "JAVA_SIGNATURE_REQUIRED",
        "GATEWAY_COMMAND_EXACT_BINDING",
        "INITIAL_ADMISSION_AND_RECOVERY",
    } <= _semantic_ids(schema)
    assert {
        "issued_at",
        "not_before",
        "expires_at",
        "signature_algorithm",
        "signing_key_id",
        "signature",
    } <= set(schema["required"])
    assert manifest["signature_algorithm"] == "ES256"
    assert len(manifest["signature"]) == 86
    assert manifest["manifest_hash"] == _canonical_hash(
        manifest, "manifest_hash", "signature"
    )

    self_hash_rule = _semantic_rule(schema, "SELF_HASH").lower()
    assert "manifest_hash" in self_hash_rule and "signature" in self_hash_rule
    signature_rule = _semantic_rule(schema, "JAVA_SIGNATURE_REQUIRED").lower()
    assert "java" in signature_rule and "es256" in signature_rule
    assert "never mints" in signature_rule or "never signs" in signature_rule

    gateway = schema["x-gateway-cross-binding"]
    assert gateway == {
        "source_schema_version": "room-graph-command.v1",
        "manifest_ref_field": "domain_snapshot_ref",
        "requires_verified_java_envelope": True,
        "failure": "BEFORE_CHECKPOINT_MUTATION",
        "room_fence_is_graph_lease_fence": False,
    }
    binding_rule = _semantic_rule(schema, "GATEWAY_COMMAND_EXACT_BINDING").lower()
    for required_text in (
        "before graph or checkpoint mutation",
        "room-graph-command.v1",
        "domain_snapshot_ref",
        "id/schema/uri/hash/size",
        "registry/profile pins",
        "room fence",
    ):
        assert required_text in binding_rule


def test_detached_authorization_proof_refs_are_forbidden_from_closed_contracts() -> None:
    def assert_absent(value: Any, path: str) -> None:
        if isinstance(value, dict):
            assert "authorization_proof_ref" not in value, path
            for key, child in value.items():
                assert_absent(child, f"{path}/{key}")
        elif isinstance(value, list):
            for index, child in enumerate(value):
                assert_absent(child, f"{path}/{index}")

    for path in sorted(CONTRACT_ROOT.glob("*.schema.json")):
        assert_absent(_load_json(path), path.name)
    for fixture_root in (VALID_ROOT, INVALID_ROOT):
        for path in sorted(fixture_root.glob("*.json")):
            assert_absent(_load_json(path), path.name)


def test_dual_output_pins_are_exact_and_propagated_across_contracts() -> None:
    manifest = _load_json(VALID_ROOT / "evidence-batch-manifest-synthetic-1-valid.json")
    item = _load_json(VALID_ROOT / "evidence-item-proposal-valid.json")
    terminal = _load_json(VALID_ROOT / "evidence-terminal-proposal-valid.json")
    projection = _load_json(VALID_ROOT / "evidence-process-projection-valid.json")
    capability = _load_json(VALID_ROOT / "evidence-asset-capability-valid.json")
    pins = manifest["profile_versions"]

    assert set(pins) == set(PROFILE_VERSION_FIELDS)
    assert pins["assessment_output_schema_version"] == "evidence-item-assessment.v1"
    assert pins["terminal_output_schema_version"] == "evidence-batch-proposal.v1"
    assert item["profile_versions"] == pins
    assert terminal["profile_versions"] == pins
    assert {field: projection["version_pins"][field] for field in PROFILE_VERSION_FIELDS} == pins
    assert capability["profile_versions_hash"] == hashlib.sha256(
        rfc8785.dumps(pins)
    ).hexdigest()


def test_legacy_or_drifted_output_pins_are_rejected_everywhere() -> None:
    cases = (
        (
            "evidence-batch-manifest.schema.json",
            "evidence-batch-manifest-synthetic-1-valid.json",
            "profile_versions",
        ),
        (
            "evidence-item-proposal.schema.json",
            "evidence-item-proposal-valid.json",
            "profile_versions",
        ),
        (
            "evidence-terminal-proposal.schema.json",
            "evidence-terminal-proposal-valid.json",
            "profile_versions",
        ),
        (
            "evidence-process-projection.schema.json",
            "evidence-process-projection-valid.json",
            "version_pins",
        ),
    )
    for schema_name, fixture_name, pin_field in cases:
        validator = _validator(schema_name)
        value = _load_json(VALID_ROOT / fixture_name)
        assert "output_schema_version" not in value[pin_field]

        legacy = copy.deepcopy(value)
        legacy[pin_field]["output_schema_version"] = "evidence-batch-proposal.v1"
        assert list(validator.iter_errors(legacy)), fixture_name

        wrong_assessment = copy.deepcopy(value)
        wrong_assessment[pin_field]["assessment_output_schema_version"] = (
            "evidence-batch-proposal.v1"
        )
        assert list(validator.iter_errors(wrong_assessment)), fixture_name

        wrong_terminal = copy.deepcopy(value)
        wrong_terminal[pin_field]["terminal_output_schema_version"] = (
            "evidence-item-assessment.v1"
        )
        assert list(validator.iter_errors(wrong_terminal)), fixture_name


def test_public_50_and_closed_synthetic_1_8_100_are_structurally_isolated() -> None:
    validator = _validator("evidence-batch-manifest.schema.json")
    for count in (1, 8, 100):
        synthetic = _load_json(
            VALID_ROOT / f"evidence-batch-manifest-synthetic-{count}-valid.json"
        )
        assert not list(validator.iter_errors(synthetic))
        _validate_manifest_semantics(synthetic)

    public_50 = _public_manifest(50)
    assert not list(validator.iter_errors(public_50))
    _validate_manifest_semantics(public_50)

    public_51 = _public_manifest(51)
    assert list(validator.iter_errors(public_51))

    disguised_public_100 = _public_manifest(50)
    disguised_public_100["items"] = copy.deepcopy(
        _load_json(
            VALID_ROOT / "evidence-batch-manifest-synthetic-100-valid.json"
        )["items"]
    )
    assert list(validator.iter_errors(disguised_public_100))

    synthetic_object_in_public = _public_manifest(1)
    synthetic_object_in_public["items"][0].update(
        object_ref="urn:synthetic-evidence:fixture/item-001",
        privacy_basis="SIGNED_SYNTHETIC_FIXTURE",
    )
    assert list(validator.iter_errors(synthetic_object_in_public))


def test_asset_capability_hash_signature_body_expiry_and_nonce_are_bound() -> None:
    schema = _schema("evidence-asset-capability.schema.json")
    capability = _load_json(VALID_ROOT / "evidence-asset-capability-valid.json")
    manifest = _load_json(VALID_ROOT / "evidence-batch-manifest-synthetic-1-valid.json")
    item = manifest["items"][0]

    assert schema["x-self-hash"] == {
        "algorithm": "SHA-256",
        "field": "capability_hash",
        "preimage": "omit_top_level_fields",
        "omit_fields": ["capability_hash", "signature"],
    }
    assert schema["x-signature"] == {"algorithm": "ES256", "covers": "capability_hash"}
    assert {
        "SELF_HASH",
        "EXPIRY_ORDER",
        "SIGNATURE_SCOPE",
        "MANIFEST_ITEM_BODY_LOOKUP",
        "CURRENT_TIME_EXPIRY",
        "SINGLE_USE_NONCE",
    } <= _semantic_ids(schema)
    assert capability["capability_hash"] == _canonical_hash(
        capability, "capability_hash", "signature"
    )
    changed_signature = copy.deepcopy(capability)
    changed_signature["signature"] = "c2lnbmF0dXJlLWNhbi1yb3RhdGU"
    assert _canonical_hash(
        changed_signature, "capability_hash", "signature"
    ) == capability["capability_hash"]
    changed_nonce = copy.deepcopy(capability)
    changed_nonce["nonce"] += "_OTHER"
    assert _canonical_hash(
        changed_nonce, "capability_hash", "signature"
    ) != capability["capability_hash"]
    assert capability["profile_versions_hash"] == hashlib.sha256(
        rfc8785.dumps(manifest["profile_versions"])
    ).hexdigest()
    for field in (
        "manifest_id",
        "manifest_hash",
        "tenant_surrogate",
        "case_id",
        "room_epoch",
        "fencing_token",
        "thread_id",
        "actor_scope_hash",
        "agent_session_id",
    ):
        assert capability[field] == manifest[field]
    for field in (
        "item_hash",
        "evidence_id",
        "owner_participant_id",
        "owner_role",
        "visibility",
        "object_ref",
        "immutable_object_version",
        "object_sha256",
        "content_type",
        "byte_size",
        "privacy_basis",
        "parse_ref",
        "parse_hash",
        "parse_status",
        "permitted_modalities",
    ):
        assert capability[field] == item[field]

    consumed: set[tuple[str, str]] = set()
    _validate_capability_semantics(
        capability,
        trusted_now=datetime(2026, 7, 22, 12, 5, tzinfo=timezone.utc),
        consumed_nonces=consumed,
    )
    with pytest.raises(ValueError, match="replayed"):
        _validate_capability_semantics(
            capability,
            trusted_now=datetime(2026, 7, 22, 12, 6, tzinfo=timezone.utc),
            consumed_nonces=consumed,
        )
    for rejected_time in (
        datetime(2026, 7, 22, 11, 59, tzinfo=timezone.utc),
        datetime(2026, 7, 22, 12, 15, tzinfo=timezone.utc),
    ):
        with pytest.raises(ValueError, match="currently valid"):
            _validate_capability_semantics(
                capability, trusted_now=rejected_time, consumed_nonces=set()
            )


def test_schema_rejects_real_production_formal_nested_and_version_confusion() -> None:
    cases: list[tuple[str, str, Any]] = [
        (
            "evidence-asset-capability.schema.json",
            "evidence-asset-capability-valid.json",
            lambda value: value.update(case_id="CASE_REAL_1"),
        ),
        (
            "evidence-asset-capability.schema.json",
            "evidence-asset-capability-valid.json",
            lambda value: value.update(object_ref="urn:evidence-object:production/1"),
        ),
        (
            "evidence-asset-capability.schema.json",
            "evidence-asset-capability-valid.json",
            lambda value: value.update(permission={"formal_sink": True}),
        ),
        (
            "evidence-batch-manifest.schema.json",
            "evidence-batch-manifest-synthetic-1-valid.json",
            lambda value: value["items"][0].update(parse_ref="urn:evidence-parse:prod/1"),
        ),
        (
            "evidence-batch-manifest.schema.json",
            "evidence-batch-manifest-synthetic-1-valid.json",
            lambda value: value["command_binding"].update(open_hearing=True),
        ),
        (
            "evidence-batch-manifest.schema.json",
            "evidence-batch-manifest-synthetic-1-valid.json",
            lambda value: value["profile_versions"].update(
                assessment_output_schema_version="evidence-batch-proposal.v1"
            ),
        ),
        (
            "evidence-item-proposal.schema.json",
            "evidence-item-proposal-valid.json",
            lambda value: value["profile_versions"].update(
                terminal_output_schema_version="evidence-item-assessment.v1"
            ),
        ),
        (
            "evidence-item-proposal.schema.json",
            "evidence-item-proposal-valid.json",
            lambda value: value.update(
                asset_load_status="METADATA_ONLY",
                asset_load_receipt_ref=None,
                asset_load_receipt_hash=None,
                inspected_modalities=["IMAGE_PIXELS"],
            ),
        ),
        (
            "evidence-item-proposal.schema.json",
            "evidence-item-proposal-valid.json",
            lambda value: value["candidate_fact_links"][0].update(
                formal_action="DOSSIER_FREEZE"
            ),
        ),
        (
            "evidence-terminal-proposal.schema.json",
            "evidence-terminal-proposal-valid.json",
            lambda value: value["profile_versions"].update(
                assessment_output_schema_version="evidence-batch-proposal.v1"
            ),
        ),
        (
            "evidence-finalization-receipt.schema.json",
            "evidence-finalization-receipt-valid.json",
            lambda value: value.update(formal_domain_write=True),
        ),
        (
            "evidence-process-projection.schema.json",
            "evidence-process-projection-valid.json",
            lambda value: value["version_pins"].update(
                terminal_output_schema_version="evidence-item-assessment.v1"
            ),
        ),
        (
            "evidence-process-projection.schema.json",
            "evidence-process-projection-valid.json",
            lambda value: value.update(
                writer_mode="TEMPORAL", temporal_evidence_allocation_allowed=True
            ),
        ),
    ]
    for schema_name, fixture_name, mutate in cases:
        value = copy.deepcopy(_load_json(VALID_ROOT / fixture_name))
        mutate(value)
        assert list(_validator(schema_name).iter_errors(value)), fixture_name


def test_manifest_terminal_and_projection_semantic_drift_fails_closed() -> None:
    manifest = _load_json(VALID_ROOT / "evidence-batch-manifest-synthetic-8-valid.json")
    terminal = _load_json(VALID_ROOT / "evidence-terminal-proposal-valid.json")
    projection = _load_json(VALID_ROOT / "evidence-process-projection-valid.json")

    manifest_mutations = (
        lambda value: value.update(item_count=1),
        lambda value: value["ordered_item_keys"].reverse(),
        lambda value: value["items"].append(copy.deepcopy(value["items"][0])),
        lambda value: value["items"][0].update(item_hash="0" * 64),
        lambda value: value.update(expires_at=value["issued_at"]),
        lambda value: value["command_binding"].update(
            deadline_at=value["command_binding"]["submitted_at"]
        ),
    )
    for mutate in manifest_mutations:
        invalid = copy.deepcopy(manifest)
        mutate(invalid)
        with pytest.raises(ValueError):
            _validate_manifest_semantics(invalid)

    terminal_mutations = (
        lambda value: value.update(item_count=8),
        lambda value: value["assessment_refs"].append(
            copy.deepcopy(value["assessment_refs"][0])
        ),
        lambda value: value["assessment_refs"][0].update(
            evidence_id="EVIDENCE_SYNTH_999"
        ),
        lambda value: value["proposed_fact_links"][0]["evidence_ids"].append(
            "EVIDENCE_SYNTH_999"
        ),
    )
    for mutate in terminal_mutations:
        invalid = copy.deepcopy(terminal)
        mutate(invalid)
        with pytest.raises(ValueError):
            _validate_terminal_semantics(invalid)

    projection_mutations = (
        lambda value: value["assessment_counts"].update(pending_count=0),
        lambda value: value.update(pending_operation_key="evidence.graph.request:wrong"),
    )
    for mutate in projection_mutations:
        invalid = copy.deepcopy(projection)
        mutate(invalid)
        with pytest.raises(ValueError):
            _validate_projection_semantics(invalid)


def test_all_eight_receipt_operations_have_exact_semantic_keys() -> None:
    schema = _schema("evidence-finalization-receipt.schema.json")
    assert set(schema["properties"]["operation_type"]["enum"]) == set(
        OPERATION_KEY_FIELDS
    )
    assert "SEMANTIC_OPERATION_KEY" in _semantic_ids(schema)
    base = _load_json(VALID_ROOT / "evidence-finalization-receipt-valid.json")
    bindings = {
        "MANIFEST_ISSUE": {
            "submission_batch_id": "SUBMISSION_P5_ONE",
            "submission_revision": 1,
            "manifest_id": "MANIFEST_P5_ONE",
            "manifest_hash": "a" * 64,
        },
        "GRAPH_REQUEST": {
            "manifest_hash": "a" * 64,
            "logical_run_id": "RUN_P5_ONE",
            "command_id": "COMMAND_P5_ONE",
            "attempt_id": "ATTEMPT_P5_ONE",
            "thread_id": "grt.v1." + "1" * 32,
        },
        "PARTY_COMPLETE": {
            "participant_id": "PARTICIPANT_P5_USER",
            "completion_request_id": "COMPLETION_P5_ONE",
        },
        "DEADLINE_WARN": {"deadline_revision": 1},
        "DEADLINE_EXPIRE": {"deadline_revision": 1},
        "BATCH_MERGE": {
            "manifest_hash": "a" * 64,
            "dossier_target_version": 2,
            "proposal_hash": "b" * 64,
            "logical_run_id": "RUN_P5_ONE",
            "command_id": "COMMAND_P5_ONE",
            "attempt_id": "ATTEMPT_P5_ONE",
            "thread_id": "grt.v1." + "1" * 32,
        },
        "DOSSIER_FREEZE": {"dossier_target_version": 2},
        "HEARING_OPEN": {"freeze_receipt_hash": "c" * 64},
    }
    validator = _validator("evidence-finalization-receipt.schema.json")
    for operation_type, operation_binding in bindings.items():
        receipt = copy.deepcopy(base)
        receipt["operation_type"] = operation_type
        receipt["operation_binding"] = operation_binding
        receipt["operation_key"] = _expected_operation_key(receipt)
        assert not list(validator.iter_errors(receipt)), operation_type
        _validate_receipt_semantics(receipt)
        receipt["operation_key"] += ":drift"
        with pytest.raises(ValueError, match="operation key"):
            _validate_receipt_semantics(receipt)


def test_projection_party_receipts_and_ready_coverage_fail_closed() -> None:
    validator = _validator("evidence-process-projection.schema.json")
    processing = _load_json(VALID_ROOT / "evidence-process-projection-valid.json")

    completed_without_receipt = copy.deepcopy(processing)
    completed_without_receipt["party_completion"]["initiator_completed"] = True
    assert list(validator.iter_errors(completed_without_receipt))

    receipt_without_completion = copy.deepcopy(processing)
    receipt_without_completion["party_completion"].update(
        initiator_receipt_ref="RECEIPT_P5_INITIATOR",
        initiator_receipt_hash="a" * 64,
    )
    assert list(validator.iter_errors(receipt_without_completion))

    ready = copy.deepcopy(processing)
    ready.update(
        room_phase="READY_TO_FREEZE",
        pending_state="REVIEW_PENDING",
        pending_operation_key=None,
        terminal_proposal={"proposal_ref": "PROPOSAL_P5_ONE", "proposal_hash": "b" * 64},
    )
    ready["assessment_counts"].update(
        completed_count=1,
        needs_review_count=0,
        failed_count=0,
        pending_count=0,
    )
    ready["active_graph_run"]["status"] = "COMPLETED"
    assert not list(validator.iter_errors(ready))
    _validate_projection_semantics(ready)

    for field in ("pending_count", "failed_count"):
        invalid = copy.deepcopy(ready)
        invalid["assessment_counts"][field] = 1
        with pytest.raises(ValueError, match="counts drifted|ready to freeze"):
            _validate_projection_semantics(invalid)
    missing_proposal = copy.deepcopy(ready)
    missing_proposal["terminal_proposal"] = None
    with pytest.raises(ValueError, match="ready to freeze"):
        _validate_projection_semantics(missing_proposal)


def test_projection_privacy_history_terminal_and_recovery_states_fail_closed() -> None:
    validator = _validator("evidence-process-projection.schema.json")
    processing = _load_json(VALID_ROOT / "evidence-process-projection-valid.json")
    legacy = _load_json(
        VALID_ROOT / "evidence-process-projection-legacy-unavailable-valid.json"
    )
    assert legacy["room_epoch"] == 0
    assert legacy["fencing_token"] == 0
    assert legacy["room_id"] is None
    assert not list(validator.iter_errors(legacy))

    wrong_audience = copy.deepcopy(processing)
    wrong_audience["audience"] = "MERCHANT"
    assert list(validator.iter_errors(wrong_audience))

    history_with_active_run = copy.deepcopy(processing)
    history_with_active_run["history_mode"] = True
    assert list(validator.iter_errors(history_with_active_run))

    failed_without_failed_recovery = copy.deepcopy(processing)
    failed_without_failed_recovery.update(
        projection_state="FAILED",
        pending_state="FAILED",
        pending_operation_key=None,
        active_graph_run=None,
    )
    assert failed_without_failed_recovery["recovery"]["state"] == "NONE"
    assert list(validator.iter_errors(failed_without_failed_recovery))

    completed_with_active_run = copy.deepcopy(processing)
    completed_with_active_run.update(
        room_phase="COMPLETED",
        terminal_reason="DEADLINE_EXPIRED",
        pending_state="NONE",
        pending_operation_key=None,
    )
    assert list(validator.iter_errors(completed_with_active_run))


@pytest.mark.parametrize("forbidden", sorted(FORBIDDEN_AUTHORITY_KEYS))
def test_forbidden_authority_keys_are_rejected_at_contract_roots(forbidden: str) -> None:
    value = _load_json(
        VALID_ROOT / "evidence-batch-manifest-synthetic-1-valid.json"
    )
    value[forbidden] = True
    assert list(_validator("evidence-batch-manifest.schema.json").iter_errors(value))


def test_receipt_replay_requires_the_same_request_hash() -> None:
    receipt = _load_json(VALID_ROOT / "evidence-finalization-receipt-valid.json")
    ledger = {receipt["operation_key"]: receipt["request_hash"]}
    assert ledger[receipt["operation_key"]] == receipt["request_hash"]
    replay = copy.deepcopy(receipt)
    assert ledger[replay["operation_key"]] == replay["request_hash"]
    replay["request_hash"] = "f" * 64
    assert ledger[replay["operation_key"]] != replay["request_hash"]
