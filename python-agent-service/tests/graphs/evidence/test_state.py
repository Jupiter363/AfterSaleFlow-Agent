from __future__ import annotations

import copy
import json
import pickle
from copy import deepcopy
from dataclasses import replace

import pytest

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence import (
    ASSESSMENT_OUTPUT_SCHEMA_VERSION,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContractError,
    VerifiedEvidenceAdmission,
    merge_evidence_assessments,
    new_evidence_graph_state,
    validate_verified_admission,
)


def _rehash_command(command: dict) -> None:
    preimage = dict(command)
    preimage.pop("request_hash", None)
    command["request_hash"] = canonical_sha256(preimage)


def test_verified_admission_accepts_full_payload_and_independent_pins(admission) -> None:
    command, manifest = validate_verified_admission(admission)

    assert command["domain_snapshot_ref"]["sha256"] == admission.snapshot_payload_sha256
    assert manifest["manifest_hash"] == (
        "cd6153b05b81e9362cced88872f596bea0cf8e456889cb27bb34fce290be04e3"
    )
    assert command["invocation_context"]["output_schema_version"] == (
        TERMINAL_OUTPUT_SCHEMA_VERSION
    )
    assert manifest["profile_versions"]["assessment_output_schema_version"] == (
        ASSESSMENT_OUTPUT_SCHEMA_VERSION
    )


def test_new_state_keeps_room_and_lease_fences_separate(admission) -> None:
    state = new_evidence_graph_state(admission=admission)

    assert state["manifest_binding"]["java_room_fencing_token"] == 7
    assert state["manifest_binding"]["graph_lease_fencing_token"] == 41
    assert state["version_pins"]["output_schema_version"] == (TERMINAL_OUTPUT_SCHEMA_VERSION)
    assert state["assessment_output_schema_version"] == (ASSESSMENT_OUTPUT_SCHEMA_VERSION)
    assert state["ordered_item_keys"] == ["EVIDENCE_SYNTH_001"]


@pytest.mark.parametrize(
    ("field", "value", "code"),
    [
        ("sha256", "0" * 64, "EVIDENCE_SNAPSHOT_PAYLOAD_HASH_MISMATCH"),
        ("size_bytes", 1, "EVIDENCE_SNAPSHOT_PAYLOAD_SIZE_MISMATCH"),
        (
            "uri",
            "s3://evidence-synthetic-manifests/not-content-addressed.json",
            "EVIDENCE_SNAPSHOT_URI_NOT_CONTENT_ADDRESSED",
        ),
    ],
)
def test_snapshot_reference_fails_before_parse_or_key_resolution(
    admission_request_factory,
    admission_verifier_factory,
    key_resolver_factory,
    field: str,
    value,
    code: str,
) -> None:
    request = admission_request_factory()
    command = deepcopy(dict(request.room_graph_command))
    command["domain_snapshot_ref"][field] = value
    _rehash_command(command)
    broken = replace(request, room_graph_command=command)
    resolver = key_resolver_factory()

    with pytest.raises(
        EvidenceGraphContractError,
        match=code,
    ):
        admission_verifier_factory(resolver).verify(broken)
    assert resolver.lookups == 0


def test_raw_byte_drift_is_rejected_before_json_parse_or_key_resolution(
    admission_request_factory,
    admission_verifier_factory,
    key_resolver_factory,
) -> None:
    request = admission_request_factory()
    drifted = bytearray(request.signed_manifest_payload)
    drifted[0] = ord("[")
    broken = replace(request, signed_manifest_payload=bytes(drifted))
    resolver = key_resolver_factory()

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_SNAPSHOT_PAYLOAD_HASH_MISMATCH",
    ):
        admission_verifier_factory(resolver).verify(broken)
    assert resolver.lookups == 0


def test_internal_manifest_hash_is_not_snapshot_payload_hash(
    admission_request_factory,
    admission_verifier_factory,
    admission_refresher,
    key_resolver_factory,
) -> None:
    request = admission_request_factory()
    manifest = json.loads(request.signed_manifest_payload)
    manifest["manifest_hash"] = "f" * 64
    broken = admission_refresher(request, manifest=manifest)
    resolver = key_resolver_factory()

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_MANIFEST_HASH_MISMATCH"):
        admission_verifier_factory(resolver).verify(broken)
    assert resolver.lookups == 0


def test_syntactically_valid_but_cryptographically_invalid_signature_is_rejected(
    admission_request_factory,
    admission_verifier_factory,
    admission_refresher,
    key_resolver_factory,
) -> None:
    request = admission_request_factory()
    manifest = json.loads(request.signed_manifest_payload)
    manifest["signature"] = "A" * 86
    broken = admission_refresher(request, manifest=manifest)
    resolver = key_resolver_factory()

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_DIRECT_JAVA_SIGNATURE_INVALID",
    ):
        admission_verifier_factory(resolver).verify(broken)
    assert resolver.lookups == 1


def test_verified_admission_cannot_be_minted_by_a_caller(admission) -> None:
    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_VERIFIED_ADMISSION_REQUIRED"):
        VerifiedEvidenceAdmission(
            runtime_mode="SHADOW",
            room_graph_command=admission.room_graph_command,
            manifest=admission.manifest,
            registry_output_schema_version=TERMINAL_OUTPUT_SCHEMA_VERSION,
            graph_lease_fencing_token=41,
            snapshot_payload_sha256=admission.snapshot_payload_sha256,
            _token=object(),
        )


def test_post_mint_nested_mutation_cannot_change_admission(admission) -> None:
    manifest = admission.manifest
    command = admission.room_graph_command
    manifest["profile_versions"]["terminal_output_schema_version"] = "tampered.v1"
    command["actor_scope"]["capabilities"].append("forged.write")

    verified_command, verified_manifest = validate_verified_admission(admission)

    assert verified_manifest["profile_versions"]["terminal_output_schema_version"] == (
        TERMINAL_OUTPUT_SCHEMA_VERSION
    )
    assert verified_command["actor_scope"]["capabilities"] == ["evidence_parser.read"]


def test_direct_slot_replacement_is_blocked(admission) -> None:
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_VERIFIED_ADMISSION_IMMUTABLE",
    ):
        admission._manifest_payload = b"{}"


def test_consume_revalidates_seal_after_low_level_slot_tamper(admission_factory) -> None:
    admission = admission_factory()
    object.__setattr__(admission, "_manifest_payload", b"{}")

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_VERIFIED_ADMISSION_SEAL_INVALID",
    ):
        validate_verified_admission(admission)


@pytest.mark.parametrize(
    ("operation", "code"),
    [
        (copy.copy, "EVIDENCE_VERIFIED_ADMISSION_COPY_FORBIDDEN"),
        (copy.deepcopy, "EVIDENCE_VERIFIED_ADMISSION_COPY_FORBIDDEN"),
        (pickle.dumps, "EVIDENCE_VERIFIED_ADMISSION_PICKLE_FORBIDDEN"),
    ],
)
def test_copy_deepcopy_and_pickle_are_forbidden(admission, operation, code: str) -> None:
    with pytest.raises(EvidenceGraphContractError, match=code):
        operation(admission)


def test_actor_scope_is_derived_from_verified_command(
    admission_request_factory,
    admission_verifier_factory,
    admission_refresher,
) -> None:
    request = admission_request_factory()
    manifest = json.loads(request.signed_manifest_payload)
    manifest["actor_scope_hash"] = "0" * 64
    broken = admission_refresher(
        request,
        manifest=manifest,
        refresh_internal_manifest_hash=True,
        resign=True,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_ACTOR_SCOPE_HASH_MISMATCH",
    ):
        admission_verifier_factory().verify(broken)


@pytest.mark.parametrize(
    ("target", "value", "code"),
    [
        ("command", "evidence-item-assessment.v1", "EVIDENCE_TERMINAL_OUTPUT_PIN_MISMATCH"),
        ("registry", "evidence-item-assessment.v1", "EVIDENCE_TERMINAL_OUTPUT_PIN_MISMATCH"),
        ("assessment", "evidence-batch-proposal.v1", "EVIDENCE_ASSESSMENT_OUTPUT_PIN_MISMATCH"),
    ],
)
def test_terminal_and_assessment_pins_cannot_be_substituted(
    admission_request_factory,
    admission_verifier_factory,
    admission_refresher,
    target: str,
    value: str,
    code: str,
) -> None:
    request = admission_request_factory()
    command = deepcopy(dict(request.room_graph_command))
    manifest = json.loads(request.signed_manifest_payload)
    registry = request.registry_output_schema_version
    refresh_internal = False
    resign = False
    if target == "command":
        command["invocation_context"]["output_schema_version"] = value
        _rehash_command(command)
    elif target == "registry":
        registry = value
    else:
        manifest["profile_versions"]["assessment_output_schema_version"] = value
        refresh_internal = True
        resign = True
    broken = replace(
        request,
        room_graph_command=command,
        registry_output_schema_version=registry,
    )
    if refresh_internal:
        broken = admission_refresher(
            broken,
            manifest=manifest,
            refresh_internal_manifest_hash=True,
            resign=resign,
        )

    with pytest.raises(EvidenceGraphContractError, match=code):
        admission_verifier_factory().verify(broken)


def test_authorization_proof_ref_is_rejected_everywhere(
    admission_request_factory,
    admission_verifier_factory,
    admission_refresher,
) -> None:
    request = admission_request_factory()
    manifest = json.loads(request.signed_manifest_payload)
    manifest["authorization_proof_ref"] = "legacy-proof"
    broken = admission_refresher(
        request,
        manifest=manifest,
        refresh_internal_manifest_hash=True,
        resign=True,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_FORMAL_AUTHORITY_FORBIDDEN",
    ):
        admission_verifier_factory().verify(broken)


def test_graph_lease_fence_is_independently_required(
    admission_request_factory,
    admission_verifier_factory,
) -> None:
    broken = replace(admission_request_factory(), graph_lease_fencing_token=-1)

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_FENCE_BINDING_INVALID"):
        admission_verifier_factory().verify(broken)


def test_runtime_rejects_disabled_or_non_synthetic_execution(
    admission_request_factory,
    admission_verifier_factory,
) -> None:
    disabled = replace(admission_request_factory(), runtime_mode="DISABLED")

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_RUNTIME_MODE_FORBIDDEN"):
        admission_verifier_factory().verify(disabled)


def test_keyed_reducer_is_order_independent_and_idempotent(assessment_factory) -> None:
    def value(evidence_id: str) -> dict:
        work_item = {
            "command_binding": {
                "command_id": "COMMAND",
                "logical_run_id": "RUN",
                "attempt_id": "ATTEMPT",
            },
            "thread_id": "grt.v1." + "1" * 32,
            "manifest_id": "MANIFEST",
            "manifest_hash": "a" * 64,
            "actor_scope_hash": "b" * 64,
            "profile_versions": {},
            "item": {
                "evidence_id": evidence_id,
                "item_hash": "c" * 64,
                "formal_evidence_revision": 1,
            },
        }
        return assessment_factory(work_item)

    first = value("EVIDENCE_002")
    second = value("EVIDENCE_001")
    left = merge_evidence_assessments({"EVIDENCE_002": first}, {"EVIDENCE_001": second})
    right = merge_evidence_assessments({"EVIDENCE_001": second}, {"EVIDENCE_002": first})

    assert left == right
    assert list(left) == ["EVIDENCE_001", "EVIDENCE_002"]
    assert merge_evidence_assessments(left, {"EVIDENCE_001": second}) == left


def test_keyed_reducer_rejects_same_key_with_another_payload(assessment_factory) -> None:
    work_item = {
        "command_binding": {
            "command_id": "COMMAND",
            "logical_run_id": "RUN",
            "attempt_id": "ATTEMPT",
        },
        "thread_id": "grt.v1." + "1" * 32,
        "manifest_id": "MANIFEST",
        "manifest_hash": "a" * 64,
        "actor_scope_hash": "b" * 64,
        "profile_versions": {},
        "item": {
            "evidence_id": "EVIDENCE_001",
            "item_hash": "c" * 64,
            "formal_evidence_revision": 1,
        },
    }
    original = assessment_factory(work_item)
    conflict = deepcopy(original)
    conflict["confidence"] = 0.1
    conflict.pop("assessment_hash")
    conflict["assessment_hash"] = canonical_sha256(conflict)

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_ASSESSMENT_REDUCER_CONFLICT",
    ):
        merge_evidence_assessments(
            {"EVIDENCE_001": original},
            {"EVIDENCE_001": conflict},
        )


def test_state_has_no_process_transition_or_trusted_decision_fields(admission) -> None:
    state = new_evidence_graph_state(admission=admission)
    forbidden = {
        "formal_merge",
        "freeze_dossier",
        "hearing_open",
        "phase_transition",
        "trusted_business_decision",
        "memory_frame",
    }

    def keys(value):
        if isinstance(value, dict):
            return set(value).union(*(keys(member) for member in value.values()))
        if isinstance(value, list):
            return set().union(*(keys(member) for member in value)) if value else set()
        return set()

    assert not forbidden & keys(state)
