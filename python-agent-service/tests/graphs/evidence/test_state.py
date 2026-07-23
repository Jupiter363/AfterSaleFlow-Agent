from __future__ import annotations

from copy import deepcopy
from dataclasses import replace

import pytest

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence import (
    ASSESSMENT_OUTPUT_SCHEMA_VERSION,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContractError,
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

    assert command["domain_snapshot_ref"]["sha256"] == (
        "80ed42b9e360d2433c51a91651237f36ae7a7f20b2d9a8846d82145dc0de793f"
    )
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


def test_snapshot_hash_fails_before_manifest_admission(admission) -> None:
    command = deepcopy(dict(admission.room_graph_command))
    command["domain_snapshot_ref"]["sha256"] = "0" * 64
    _rehash_command(command)
    broken = replace(admission, room_graph_command=command)

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_SNAPSHOT_PAYLOAD_HASH_MISMATCH",
    ):
        new_evidence_graph_state(admission=broken)


def test_internal_manifest_hash_is_not_snapshot_payload_hash(
    admission,
    admission_refresher,
) -> None:
    manifest = deepcopy(dict(admission.manifest))
    manifest["manifest_hash"] = "f" * 64
    broken = admission_refresher(replace(admission, manifest=manifest))

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_MANIFEST_HASH_MISMATCH"):
        validate_verified_admission(broken)


def test_direct_java_signature_verification_proof_is_required(admission) -> None:
    broken = replace(admission, direct_java_es256_signature_verified=False)

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_DIRECT_JAVA_SIGNATURE_UNVERIFIED",
    ):
        validate_verified_admission(broken)


def test_actor_scope_is_derived_from_verified_command(
    admission,
    admission_refresher,
) -> None:
    manifest = deepcopy(dict(admission.manifest))
    manifest["actor_scope_hash"] = "0" * 64
    broken = admission_refresher(
        replace(admission, manifest=manifest),
        refresh_internal_manifest_hash=True,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_ACTOR_SCOPE_HASH_MISMATCH",
    ):
        validate_verified_admission(broken)


@pytest.mark.parametrize(
    ("target", "value", "code"),
    [
        ("command", "evidence-item-assessment.v1", "EVIDENCE_TERMINAL_OUTPUT_PIN_MISMATCH"),
        ("registry", "evidence-item-assessment.v1", "EVIDENCE_TERMINAL_OUTPUT_PIN_MISMATCH"),
        ("assessment", "evidence-batch-proposal.v1", "EVIDENCE_ASSESSMENT_OUTPUT_PIN_MISMATCH"),
    ],
)
def test_terminal_and_assessment_pins_cannot_be_substituted(
    admission,
    admission_refresher,
    target: str,
    value: str,
    code: str,
) -> None:
    command = deepcopy(dict(admission.room_graph_command))
    manifest = deepcopy(dict(admission.manifest))
    registry = admission.registry_output_schema_version
    refresh_internal = False
    if target == "command":
        command["invocation_context"]["output_schema_version"] = value
        _rehash_command(command)
    elif target == "registry":
        registry = value
    else:
        manifest["profile_versions"]["assessment_output_schema_version"] = value
        refresh_internal = True
    broken = replace(
        admission,
        room_graph_command=command,
        manifest=manifest,
        registry_output_schema_version=registry,
    )
    if refresh_internal:
        broken = admission_refresher(broken, refresh_internal_manifest_hash=True)

    with pytest.raises(EvidenceGraphContractError, match=code):
        validate_verified_admission(broken)


def test_authorization_proof_ref_is_rejected_everywhere(
    admission,
    admission_refresher,
) -> None:
    manifest = deepcopy(dict(admission.manifest))
    manifest["authorization_proof_ref"] = "legacy-proof"
    broken = admission_refresher(
        replace(admission, manifest=manifest),
        refresh_internal_manifest_hash=True,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_FORMAL_AUTHORITY_FORBIDDEN",
    ):
        validate_verified_admission(broken)


def test_graph_lease_fence_is_independently_required(admission) -> None:
    broken = replace(admission, graph_lease_fencing_token=-1)

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_FENCE_BINDING_INVALID"):
        validate_verified_admission(broken)


def test_runtime_rejects_disabled_or_non_synthetic_execution(admission) -> None:
    disabled = replace(admission, runtime_mode="DISABLED")

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_RUNTIME_MODE_FORBIDDEN"):
        validate_verified_admission(disabled)


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
