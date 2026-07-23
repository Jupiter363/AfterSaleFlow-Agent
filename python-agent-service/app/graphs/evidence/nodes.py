from __future__ import annotations

import math
from collections.abc import Mapping
from copy import deepcopy
from typing import Any, cast

from langchain_core.runnables import Runnable, RunnableConfig
from langgraph.runtime import Runtime
from langgraph.types import Send

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence.contracts import (
    ASSESSMENT_OUTPUT_SCHEMA_VERSION,
    MAX_ACTIVE_ITEMS,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContext,
    EvidenceGraphContractError,
    JsonObject,
    manifest_items_by_key,
    validate_verified_admission,
)
from app.graphs.evidence.state import (
    EvidenceGraphStateV2,
    clear_raw_evidence_assessments,
    new_evidence_graph_state,
)


_ASSESSMENT_FIELDS = frozenset(
    {
        "schema_version",
        "assessment_hash",
        "execution_scope",
        "formal_sink_eligible",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "thread_id",
        "manifest_id",
        "manifest_hash",
        "evidence_id",
        "item_hash",
        "formal_evidence_revision",
        "actor_scope_hash",
        "profile_versions",
        "assessment_status",
        "authenticity_score",
        "authenticity_reason_codes",
        "relevance_score",
        "relevance_reason_codes",
        "completeness_score",
        "confidence",
        "candidate_fact_links",
        "source_refs",
        "inspected_modalities",
        "asset_load_status",
        "asset_load_receipt_ref",
        "asset_load_receipt_hash",
        "limitations",
        "review_reasons",
    }
)


def authorize_registration_and_manifest(
    state: EvidenceGraphStateV2,
    runtime: Runtime[EvidenceGraphContext],
) -> dict[str, Any]:
    validate_verified_admission(runtime.context.admission)
    expected = new_evidence_graph_state(admission=runtime.context.admission)
    for field in (
        "schema_version",
        "bindings",
        "version_pins",
        "assessment_output_schema_version",
        "manifest_binding",
        "ordered_item_keys",
    ):
        if state.get(field) != expected[field]:
            raise EvidenceGraphContractError("EVIDENCE_STATE_ADMISSION_BINDING_MISMATCH")
    _validate_scheduler_state(state)
    return {}


def plan_next_deterministic_wave(state: EvidenceGraphStateV2) -> dict[str, Any]:
    _validate_scheduler_state(state)
    if state["in_flight_keys"]:
        raise EvidenceGraphContractError("EVIDENCE_WAVE_ALREADY_IN_FLIGHT")
    start = state["next_dispatch_index"]
    ordered = state["ordered_item_keys"]
    if start == len(ordered):
        return {"route": "complete", "current_wave_keys": []}
    wave = ordered[start : start + MAX_ACTIVE_ITEMS]
    if not wave or len(wave) > MAX_ACTIVE_ITEMS:
        raise EvidenceGraphContractError("EVIDENCE_DISPATCH_WAVE_INVALID")
    return {
        "route": "dispatch",
        "current_wave_keys": list(wave),
        "in_flight_keys": list(wave),
    }


def dispatch_wave(state: EvidenceGraphStateV2) -> list[Send] | str:
    route = state.get("route")
    if route == "complete":
        return "require_complete_valid_coverage"
    if route != "dispatch":
        raise EvidenceGraphContractError("EVIDENCE_SCHEDULER_ROUTE_INVALID")
    wave = state.get("current_wave_keys")
    if (
        not isinstance(wave, list)
        or not wave
        or wave != state["in_flight_keys"]
        or len(wave) > MAX_ACTIVE_ITEMS
    ):
        raise EvidenceGraphContractError("EVIDENCE_DISPATCH_WAVE_INVALID")
    return [Send("assess_evidence_item", {"work_item_key": key}) for key in wave]


class AssessEvidenceItemNode:
    def __init__(self, item_assessor: Runnable[JsonObject, Mapping[str, Any]]) -> None:
        self._item_assessor = item_assessor

    def __call__(
        self,
        state: Mapping[str, Any],
        runtime: Runtime[EvidenceGraphContext],
        config: RunnableConfig,
    ) -> dict[str, Any]:
        evidence_id = state.get("work_item_key")
        if not isinstance(evidence_id, str):
            raise EvidenceGraphContractError("EVIDENCE_SEND_KEY_INVALID")
        command, manifest = validate_verified_admission(runtime.context.admission)
        items = manifest_items_by_key(manifest)
        item = items.get(evidence_id)
        if item is None:
            raise EvidenceGraphContractError("EVIDENCE_SEND_KEY_NOT_IN_MANIFEST")
        work_item: JsonObject = {
            "schema_version": "evidence-assessment-work-item.v1",
            "command_binding": {
                "command_id": cast(str, command["command_id"]),
                "logical_run_id": cast(str, command["logical_run_id"]),
                "attempt_id": cast(str, command["attempt_id"]),
            },
            "thread_id": cast(str, manifest["thread_id"]),
            "manifest_id": cast(str, manifest["manifest_id"]),
            "manifest_hash": cast(str, manifest["manifest_hash"]),
            "actor_scope_hash": cast(str, manifest["actor_scope_hash"]),
            "profile_versions": deepcopy(cast(JsonObject, manifest["profile_versions"])),
            "item": deepcopy(item),
        }
        result = self._item_assessor.invoke(work_item, config=config)
        if not isinstance(result, Mapping):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSOR_OUTPUT_INVALID")
        assessment = cast(JsonObject, deepcopy(dict(result)))
        return {"raw_outputs": {evidence_id: assessment}}


def validate_item_assessment(
    state: EvidenceGraphStateV2,
    runtime: Runtime[EvidenceGraphContext],
) -> dict[str, Any]:
    _, manifest = validate_verified_admission(runtime.context.admission)
    items = manifest_items_by_key(manifest)
    wave = state.get("current_wave_keys")
    if not isinstance(wave, list) or not wave or len(wave) > MAX_ACTIVE_ITEMS:
        raise EvidenceGraphContractError("EVIDENCE_VALIDATION_WAVE_INVALID")
    validated: dict[str, JsonObject] = {}
    for evidence_id in wave:
        assessment = state["raw_outputs"].get(evidence_id)
        item = items.get(evidence_id)
        if assessment is None or item is None:
            raise EvidenceGraphContractError("EVIDENCE_WAVE_OUTPUT_MISSING")
        _validate_assessment(state, manifest, item, assessment)
        validated[evidence_id] = deepcopy(assessment)
    return {
        "raw_outputs": clear_raw_evidence_assessments(),
        "validated_outputs": validated,
    }


def keyed_fan_in(state: EvidenceGraphStateV2) -> dict[str, Any]:
    wave = state.get("current_wave_keys")
    if not isinstance(wave, list) or wave != state["in_flight_keys"]:
        raise EvidenceGraphContractError("EVIDENCE_FAN_IN_WAVE_INVALID")
    if not set(wave) <= set(state["validated_outputs"]):
        raise EvidenceGraphContractError("EVIDENCE_FAN_IN_INCOMPLETE")
    return {
        "next_dispatch_index": state["next_dispatch_index"] + len(wave),
        "in_flight_keys": [],
        "current_wave_keys": [],
    }


def route_after_fan_in(state: EvidenceGraphStateV2) -> str:
    _validate_scheduler_state(state)
    if state["next_dispatch_index"] < len(state["ordered_item_keys"]):
        return "plan_next_deterministic_wave"
    return "require_complete_valid_coverage"


def require_complete_valid_coverage(state: EvidenceGraphStateV2) -> dict[str, Any]:
    ordered = state["ordered_item_keys"]
    if (
        state["next_dispatch_index"] != len(ordered)
        or state["in_flight_keys"]
        or set(state["validated_outputs"]) != set(ordered)
        or any(
            item.get("assessment_status") not in {"COMPLETED", "NEEDS_REVIEW"}
            for item in state["validated_outputs"].values()
        )
    ):
        raise EvidenceGraphContractError("EVIDENCE_COMPLETE_COVERAGE_REQUIRED")
    return {}


def build_matrix_and_review_proposal(state: EvidenceGraphStateV2) -> dict[str, Any]:
    fact_links: dict[str, dict[str, set[str]]] = {}
    reviews: list[JsonObject] = []
    for evidence_id in state["ordered_item_keys"]:
        assessment = state["validated_outputs"][evidence_id]
        for link in cast(list[dict[str, Any]], assessment["candidate_fact_links"]):
            grouped = fact_links.setdefault(
                cast(str, link["fact_id"]),
                {"evidence_ids": set(), "source_refs": set()},
            )
            grouped["evidence_ids"].add(evidence_id)
            grouped["source_refs"].update(cast(list[str], link["source_refs"]))
        if assessment["assessment_status"] == "NEEDS_REVIEW":
            reasons = cast(list[str], assessment["review_reasons"])
            if not reasons:
                raise EvidenceGraphContractError("EVIDENCE_REVIEW_REASONS_REQUIRED")
            reviews.append(
                {
                    "review_key": f"REVIEW_{evidence_id}",
                    "evidence_id": evidence_id,
                    "reason_codes": list(reasons),
                    "priority": "MEDIUM",
                }
            )
    matrix_patch: list[JsonObject] = [
        {
            "fact_id": fact_id,
            "evidence_ids": sorted(values["evidence_ids"]),
            "source_refs": sorted(values["source_refs"]),
        }
        for fact_id, values in sorted(fact_links.items())
    ]
    if len(matrix_patch) > 100 or any(
        len(cast(list[str], link["source_refs"])) > 64 for link in matrix_patch
    ):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_FACT_LINK_LIMIT_EXCEEDED")
    return {
        "proposed_fact_matrix_patch": matrix_patch,
        "proposed_review_items": reviews,
    }


def project_evidence_batch_proposal(
    state: EvidenceGraphStateV2,
    runtime: Runtime[EvidenceGraphContext],
) -> dict[str, Any]:
    _, manifest = validate_verified_admission(runtime.context.admission)
    command_binding = cast(dict[str, Any], manifest["command_binding"])
    ordered = state["ordered_item_keys"]
    proposal: JsonObject = {
        "schema_version": TERMINAL_OUTPUT_SCHEMA_VERSION,
        "execution_scope": "SIGNED_SYNTHETIC_ONLY",
        "writer_mode": "PROPOSAL_ONLY",
        "formal_sink_eligible": False,
        "command_id": cast(str, command_binding["command_id"]),
        "logical_run_id": cast(str, command_binding["logical_run_id"]),
        "attempt_id": cast(str, command_binding["attempt_id"]),
        "thread_id": cast(str, manifest["thread_id"]),
        "manifest_id": cast(str, manifest["manifest_id"]),
        "manifest_hash": cast(str, manifest["manifest_hash"]),
        "item_count": len(ordered),
        "ordered_item_keys": list(ordered),
        "assessment_refs": [
            {
                "evidence_id": evidence_id,
                "assessment_status": state["validated_outputs"][evidence_id]["assessment_status"],
                "assessment_hash": state["validated_outputs"][evidence_id]["assessment_hash"],
            }
            for evidence_id in ordered
        ],
        "coverage_status": "COMPLETE",
        "proposed_fact_links": deepcopy(state["proposed_fact_matrix_patch"]),
        "proposed_review_items": deepcopy(state["proposed_review_items"]),
        "profile_versions": deepcopy(cast(JsonObject, manifest["profile_versions"])),
        "completed_at": runtime.context.completed_at,
    }
    proposal["proposal_hash"] = canonical_sha256(proposal)
    return {"terminal_draft": proposal}


def checkpoint_terminal(state: EvidenceGraphStateV2) -> dict[str, Any]:
    proposal = state.get("terminal_draft")
    if not isinstance(proposal, dict):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROPOSAL_MISSING")
    expected_hash = proposal.get("proposal_hash")
    preimage = dict(proposal)
    preimage.pop("proposal_hash", None)
    if expected_hash != canonical_sha256(preimage):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROPOSAL_HASH_MISMATCH")
    if (
        proposal.get("schema_version") != TERMINAL_OUTPUT_SCHEMA_VERSION
        or proposal.get("writer_mode") != "PROPOSAL_ONLY"
        or proposal.get("formal_sink_eligible") is not False
        or proposal.get("coverage_status") != "COMPLETE"
    ):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROPOSAL_INVALID")
    return {
        "cognitive_revision": state["cognitive_revision"] + 1,
        "result_json": deepcopy(proposal),
    }


def _validate_scheduler_state(state: EvidenceGraphStateV2) -> None:
    ordered = state.get("ordered_item_keys")
    index = state.get("next_dispatch_index")
    in_flight = state.get("in_flight_keys")
    if (
        not isinstance(ordered, list)
        or not isinstance(index, int)
        or isinstance(index, bool)
        or index < 0
        or index > len(ordered)
        or not isinstance(in_flight, list)
        or len(in_flight) > MAX_ACTIVE_ITEMS
        or any(key not in ordered for key in in_flight)
        or not set(state.get("validated_outputs", {})) <= set(ordered)
    ):
        raise EvidenceGraphContractError("EVIDENCE_SCHEDULER_STATE_INVALID")


def _validate_assessment(
    state: EvidenceGraphStateV2,
    manifest: JsonObject,
    item: JsonObject,
    assessment: JsonObject,
) -> None:
    if set(assessment) != _ASSESSMENT_FIELDS:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FIELDS_INVALID")
    if (
        assessment.get("schema_version") != ASSESSMENT_OUTPUT_SCHEMA_VERSION
        or assessment.get("execution_scope") != "SIGNED_SYNTHETIC_ONLY"
        or assessment.get("formal_sink_eligible") is not False
        or assessment.get("assessment_status") not in {"COMPLETED", "NEEDS_REVIEW"}
    ):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_SCOPE_INVALID")
    command_binding = cast(dict[str, Any], manifest["command_binding"])
    exact = {
        "command_id": command_binding["command_id"],
        "logical_run_id": command_binding["logical_run_id"],
        "attempt_id": command_binding["attempt_id"],
        "thread_id": manifest["thread_id"],
        "manifest_id": manifest["manifest_id"],
        "manifest_hash": manifest["manifest_hash"],
        "evidence_id": item["evidence_id"],
        "item_hash": item["item_hash"],
        "formal_evidence_revision": item["formal_evidence_revision"],
        "actor_scope_hash": manifest["actor_scope_hash"],
        "profile_versions": manifest["profile_versions"],
    }
    if any(assessment.get(name) != value for name, value in exact.items()):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_BINDING_MISMATCH")
    if state["assessment_output_schema_version"] != ASSESSMENT_OUTPUT_SCHEMA_VERSION:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_OUTPUT_PIN_MISMATCH")
    for name in (
        "authenticity_score",
        "relevance_score",
        "completeness_score",
        "confidence",
    ):
        value = assessment.get(name)
        if (
            not isinstance(value, (int, float))
            or isinstance(value, bool)
            or not math.isfinite(value)
            or not 0 <= value <= 1
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_SCORE_INVALID")
    for name in (
        "authenticity_reason_codes",
        "relevance_reason_codes",
        "source_refs",
        "inspected_modalities",
        "limitations",
        "review_reasons",
    ):
        values = assessment.get(name)
        if (
            not isinstance(values, list)
            or not all(isinstance(value, str) and value for value in values)
            or values != sorted(set(values))
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_LIST_INVALID")
    list_limits = {
        "authenticity_reason_codes": 16,
        "relevance_reason_codes": 16,
        "source_refs": 64,
        "inspected_modalities": 4,
        "limitations": 16,
        "review_reasons": 16,
    }
    if any(
        len(cast(list[Any], assessment[name])) > maximum for name, maximum in list_limits.items()
    ):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_LIST_INVALID")
    links = assessment.get("candidate_fact_links")
    if not isinstance(links, list):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FACT_LINK_INVALID")
    for link in links:
        if (
            not isinstance(link, dict)
            or set(link) != {"fact_id", "source_refs"}
            or not isinstance(link.get("fact_id"), str)
            or not cast(str, link["fact_id"]).startswith("FACT_")
            or not isinstance(link.get("source_refs"), list)
            or link["source_refs"] != sorted(set(link["source_refs"]))
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FACT_LINK_INVALID")
    if [link["fact_id"] for link in links] != sorted(
        {cast(str, link["fact_id"]) for link in links}
    ):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FACT_LINK_INVALID")
    if len(links) > 64 or any(
        not set(cast(list[str], link["source_refs"]))
        <= set(cast(list[str], assessment["source_refs"]))
        for link in links
    ):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FACT_LINK_INVALID")
    load_status = assessment.get("asset_load_status")
    receipt_ref = assessment.get("asset_load_receipt_ref")
    receipt_hash = assessment.get("asset_load_receipt_hash")
    inspected = cast(list[str], assessment["inspected_modalities"])
    if load_status == "LOADED":
        if (
            not isinstance(receipt_ref, str)
            or not receipt_ref
            or not isinstance(receipt_hash, str)
            or len(receipt_hash) != 64
            or not inspected
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSET_RECEIPT_BINDING_INVALID")
    elif load_status == "METADATA_ONLY":
        if receipt_ref is not None or receipt_hash is not None or set(inspected) > {"PDF_METADATA"}:
            raise EvidenceGraphContractError("EVIDENCE_ASSET_RECEIPT_BINDING_INVALID")
    elif load_status in {"NOT_REQUIRED", "REJECTED"}:
        if receipt_ref is not None or receipt_hash is not None or inspected:
            raise EvidenceGraphContractError("EVIDENCE_ASSET_RECEIPT_BINDING_INVALID")
    else:
        raise EvidenceGraphContractError("EVIDENCE_ASSET_RECEIPT_BINDING_INVALID")
    if assessment["assessment_status"] == "NEEDS_REVIEW" and not assessment["review_reasons"]:
        raise EvidenceGraphContractError("EVIDENCE_REVIEW_REASONS_REQUIRED")
