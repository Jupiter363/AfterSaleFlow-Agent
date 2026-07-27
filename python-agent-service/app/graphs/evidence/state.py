from __future__ import annotations

import re
from copy import deepcopy
from typing import Annotated, Literal, cast

from typing_extensions import NotRequired, TypedDict

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graph_runtime.reducers import merge_execution_receipts, merge_usage_by_invocation
from app.graph_runtime.state import (
    CommandBindingState,
    ExecutionReceiptState,
    UsageState,
    VersionPinsState,
)
from app.graphs.evidence.contracts import (
    ASSESSMENT_OUTPUT_SCHEMA_VERSION,
    EVIDENCE_STATE_SCHEMA_VERSION,
    EvidenceGraphContractError,
    JsonObject,
    JsonValue as _JsonValue,
    VerifiedEvidenceAdmission,
    validate_verified_admission,
)


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_CLEAR_RAW_OUTPUTS = "__clear_evidence_raw_outputs__"
# ``JsonObject`` has a recursive forward reference resolved by LangGraph here.
JsonValue = _JsonValue


class EvidenceSnapshotRef(TypedDict):
    artifact_id: str
    schema_version: str
    uri: str
    sha256: str
    size_bytes: int


class EvidenceManifestBinding(TypedDict):
    schema_version: Literal["evidence-manifest-binding.v1"]
    manifest_id: str
    manifest_hash: str
    snapshot_ref: EvidenceSnapshotRef
    actor_scope_hash: str
    java_room_fencing_token: int
    graph_lease_fencing_token: int


def merge_evidence_assessments(
    left: dict[str, JsonObject] | None,
    right: dict[str, JsonObject] | None,
) -> dict[str, JsonObject]:
    merged: dict[str, JsonObject] = deepcopy(left or {})
    if right is None:
        return merged
    if not isinstance(right, dict):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_INPUT_INVALID")
    for evidence_id, assessment in right.items():
        if (
            not isinstance(evidence_id, str)
            or not isinstance(assessment, dict)
            or assessment.get("evidence_id") != evidence_id
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_KEY_INVALID")
        try:
            canonicalize(assessment)
        except (TypeError, ValueError) as error:
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_NOT_CANONICAL_JSON") from error
        assessment_hash = assessment.get("assessment_hash")
        if not isinstance(assessment_hash, str) or not _SHA256.fullmatch(assessment_hash):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_HASH_INVALID")
        preimage = dict(assessment)
        del preimage["assessment_hash"]
        if canonical_sha256(preimage) != assessment_hash:
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_HASH_MISMATCH")
        previous = merged.get(evidence_id)
        if previous is not None and previous != assessment:
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_CONFLICT")
        merged[evidence_id] = deepcopy(assessment)
    return dict(sorted(merged.items()))


def merge_raw_evidence_assessments(
    left: dict[str, JsonObject] | None,
    right: dict[str, JsonObject] | None,
) -> dict[str, JsonObject]:
    if right == {_CLEAR_RAW_OUTPUTS: {}}:
        return {}
    return merge_evidence_assessments(left, right)


def clear_raw_evidence_assessments() -> dict[str, JsonObject]:
    return {_CLEAR_RAW_OUTPUTS: {}}


class EvidenceGraphStateV2(TypedDict):
    schema_version: Literal["evidence-graph-state.v2"]
    bindings: CommandBindingState
    version_pins: VersionPinsState
    assessment_output_schema_version: Literal["evidence-item-assessment.v1"]
    manifest_binding: EvidenceManifestBinding
    ordered_item_keys: list[str]
    next_dispatch_index: int
    in_flight_keys: list[str]
    raw_outputs: Annotated[dict[str, JsonObject], merge_raw_evidence_assessments]
    validated_outputs: Annotated[dict[str, JsonObject], merge_evidence_assessments]
    execution_receipts: Annotated[dict[str, ExecutionReceiptState], merge_execution_receipts]
    usage_by_invocation: Annotated[dict[str, UsageState], merge_usage_by_invocation]
    cognitive_revision: int
    proposed_fact_matrix_patch: list[JsonObject]
    proposed_review_items: list[JsonObject]
    route: NotRequired[Literal["dispatch", "complete"]]
    current_wave_keys: NotRequired[list[str]]
    terminal_draft: NotRequired[JsonObject]
    result_json: NotRequired[JsonObject]


def new_evidence_graph_state(
    *,
    admission: VerifiedEvidenceAdmission,
) -> EvidenceGraphStateV2:
    command, manifest = validate_verified_admission(admission)
    profiles = cast(dict[str, object], manifest["profile_versions"])
    snapshot_ref = cast(dict[str, object], command["domain_snapshot_ref"])
    command_binding = cast(dict[str, object], manifest["command_binding"])
    bindings: CommandBindingState = {
        "schema_version": "graph-command-binding.v1",
        "command_id": cast(str, command_binding["command_id"]),
        "logical_run_id": cast(str, command_binding["logical_run_id"]),
        "attempt_id": cast(str, command_binding["attempt_id"]),
        "tenant_surrogate": cast(str, manifest["tenant_surrogate"]),
        "case_id": cast(str, manifest["case_id"]),
        "room_type": "EVIDENCE",
        "room_epoch": cast(int, manifest["room_epoch"]),
        "actor_scope_hash": cast(str, manifest["actor_scope_hash"]),
        "thread_id": cast(str, manifest["thread_id"]),
    }
    version_pins: VersionPinsState = {
        "schema_version": "graph-version-pins.v1",
        "graph_key": cast(str, command["graph_key"]),
        "graph_version": cast(str, profiles["graph_version"]),
        "checkpoint_schema_version": cast(str, profiles["checkpoint_schema_version"]),
        "state_schema_version": cast(str, profiles["state_schema_version"]),
        "prompt_version": cast(str, profiles["prompt_version"]),
        "model_profile_id": cast(str, profiles["model_profile_id"]),
        "output_schema_version": cast(
            str,
            cast(dict[str, object], command["invocation_context"])[
                "output_schema_version"
            ],
        ),
        "policy_version": cast(str, profiles["policy_version"]),
        "guardrail_version": cast(str, profiles["guardrail_version"]),
        "tool_policy_version": cast(str, profiles["tool_policy_version"]),
    }
    manifest_binding: EvidenceManifestBinding = {
        "schema_version": "evidence-manifest-binding.v1",
        "manifest_id": cast(str, manifest["manifest_id"]),
        "manifest_hash": cast(str, manifest["manifest_hash"]),
        "snapshot_ref": {
            "artifact_id": cast(str, snapshot_ref["artifact_id"]),
            "schema_version": cast(str, snapshot_ref["schema_version"]),
            "uri": cast(str, snapshot_ref["uri"]),
            "sha256": cast(str, snapshot_ref["sha256"]),
            "size_bytes": cast(int, snapshot_ref["size_bytes"]),
        },
        "actor_scope_hash": cast(str, manifest["actor_scope_hash"]),
        "java_room_fencing_token": cast(int, manifest["fencing_token"]),
        "graph_lease_fencing_token": admission.graph_lease_fencing_token,
    }
    return {
        "schema_version": EVIDENCE_STATE_SCHEMA_VERSION,
        "bindings": bindings,
        "version_pins": version_pins,
        "assessment_output_schema_version": ASSESSMENT_OUTPUT_SCHEMA_VERSION,
        "manifest_binding": manifest_binding,
        "ordered_item_keys": list(cast(list[str], manifest["ordered_item_keys"])),
        "next_dispatch_index": 0,
        "in_flight_keys": [],
        "raw_outputs": {},
        "validated_outputs": {},
        "execution_receipts": {},
        "usage_by_invocation": {},
        "cognitive_revision": 0,
        "proposed_fact_matrix_patch": [],
        "proposed_review_items": [],
    }
