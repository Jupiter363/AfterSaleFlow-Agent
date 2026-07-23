from __future__ import annotations

import json
import re
from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel
from langgraph.runtime import Runtime
from langgraph.types import Send

from app.graphs.hearing.contracts import HearingGraphIdentity, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.privacy import validate_hearing_scope_binding
from app.graphs.hearing.state import (
    MAX_HEARING_PROPOSAL_BYTES,
    MAX_HEARING_EVIDENCE_ITEMS,
    MAX_HEARING_EVIDENCE_SENDS,
    MAX_HEARING_WORK_RESULT_BYTES,
    HearingGraphInvocation,
    HearingGraphStateV1,
    request_hash,
    version_pins,
)


_BASE_STATE_FIELDS = frozenset(
    {
        "schema_version",
        "graph_identity",
        "version_pins",
        "operation",
        "case_id",
        "workflow_id",
        "stage_sequence",
        "request_schema_version",
        "request_hash",
        "status",
        "cognitive_revision",
        "command_binding",
        "scope_binding",
        "route",
        "ordered_work_item_keys",
        "next_dispatch_index",
        "current_wave_keys",
        "in_flight_keys",
        "work_results",
        "proposal_schema_version",
        "proposal",
    }
)
_STABLE_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_FORMAL_EFFECT_FIELDS = frozenset(
    {
        "formal_action",
        "trusted_business_decision",
        "stage_advanced",
        "artifact_committed",
        "review_opened",
        "hearing_closed",
    }
)


def validate_and_route(
    state: HearingGraphStateV1,
    runtime: Runtime[HearingGraphInvocation],
    *,
    identity: HearingGraphIdentity,
) -> dict[str, Any]:
    if set(state) - _BASE_STATE_FIELDS:
        raise HearingGraphContractError("HEARING_STATE_FIELDS_INVALID")
    if (
        state.get("schema_version") != identity.state_schema_version
        or state.get("graph_identity") != identity.identity
        or state.get("version_pins") != version_pins(identity)
        or state.get("status") != "PENDING"
        or state.get("proposal") is not None
    ):
        raise HearingGraphContractError("HEARING_GRAPH_BINDING_INVALID")
    validate_hearing_scope_binding(state)
    try:
        operation = HearingOperation(state.get("operation", ""))
    except ValueError as error:
        raise HearingGraphContractError("HEARING_OPERATION_UNKNOWN") from error
    if operation not in identity.operations:
        raise HearingGraphContractError("HEARING_OPERATION_GRAPH_MISMATCH")

    request = runtime.context.request
    if not isinstance(request, BaseModel) or not callable(runtime.context.execute):
        raise HearingGraphContractError("HEARING_INVOCATION_CONTEXT_INVALID")
    if (
        getattr(request, "case_id", None) != state.get("case_id")
        or getattr(request, "workflow_id", None) != state.get("workflow_id")
        or getattr(request, "stage_sequence", None) != state.get("stage_sequence")
        or getattr(request, "flow_schema_version", None) != state.get("request_schema_version")
        or request_hash(request) != state.get("request_hash")
    ):
        raise HearingGraphContractError("HEARING_REQUEST_BINDING_MISMATCH")
    return {"route": operation.value}


def plan_evidence_work(
    state: HearingGraphStateV1,
    runtime: Runtime[HearingGraphInvocation],
) -> dict[str, Any]:
    if state.get("operation") != HearingOperation.EVIDENCE_SYNTHESIS.value:
        raise HearingGraphContractError("HEARING_EVIDENCE_FANOUT_OPERATION_MISMATCH")
    planner = runtime.context.plan_work_items
    assessor = runtime.context.execute_work_item
    projector = runtime.context.execute_with_work_results
    if not callable(planner) or not callable(assessor) or not callable(projector):
        raise HearingGraphContractError("HEARING_EVIDENCE_FANOUT_CONTEXT_REQUIRED")
    keys = list(planner(runtime.context.request))
    if (
        len(keys) > MAX_HEARING_EVIDENCE_ITEMS
        or any(not isinstance(key, str) or _STABLE_KEY.fullmatch(key) is None for key in keys)
        or len(set(keys)) != len(keys)
    ):
        raise HearingGraphContractError("HEARING_EVIDENCE_WORK_KEYS_INVALID")
    ordered = sorted(keys)
    return {
        "ordered_work_item_keys": ordered,
        "next_dispatch_index": 0,
        "in_flight_keys": [],
        "work_results": {},
    }


def plan_evidence_wave(state: HearingGraphStateV1) -> dict[str, Any]:
    ordered = state.get("ordered_work_item_keys")
    index = state.get("next_dispatch_index")
    in_flight = state.get("in_flight_keys")
    if (
        not isinstance(ordered, list)
        or isinstance(index, bool)
        or not isinstance(index, int)
        or index < 0
        or index > len(ordered)
        or in_flight != []
    ):
        raise HearingGraphContractError("HEARING_EVIDENCE_SCHEDULER_STATE_INVALID")
    if index == len(ordered):
        return {"route": "evidence_complete", "current_wave_keys": []}
    wave = ordered[index : index + MAX_HEARING_EVIDENCE_SENDS]
    return {
        "route": "evidence_dispatch",
        "current_wave_keys": wave,
        "in_flight_keys": wave,
    }


def dispatch_evidence_wave(state: HearingGraphStateV1) -> list[Send] | str:
    if state.get("route") == "evidence_complete":
        return "complete_evidence_synthesis"
    wave = state.get("current_wave_keys")
    if (
        state.get("route") != "evidence_dispatch"
        or not isinstance(wave, list)
        or not wave
        or wave != state.get("in_flight_keys")
        or len(wave) > MAX_HEARING_EVIDENCE_SENDS
    ):
        raise HearingGraphContractError("HEARING_EVIDENCE_DISPATCH_INVALID")
    return [Send("assess_evidence_item", {"work_item_key": key}) for key in wave]


def assess_evidence_item(
    state: Mapping[str, Any],
    runtime: Runtime[HearingGraphInvocation],
) -> dict[str, Any]:
    key = state.get("work_item_key")
    if not isinstance(key, str) or _STABLE_KEY.fullmatch(key) is None:
        raise HearingGraphContractError("HEARING_EVIDENCE_SEND_KEY_INVALID")
    assessor = runtime.context.execute_work_item
    if not callable(assessor):
        raise HearingGraphContractError("HEARING_EVIDENCE_ASSESSOR_REQUIRED")
    result = assessor(runtime.context.request, key)
    if not isinstance(result, BaseModel):
        raise HearingGraphContractError("HEARING_EVIDENCE_RESULT_NOT_TYPED")
    payload = result.model_dump(mode="json")
    if payload.get("evidence_id") != key:
        raise HearingGraphContractError("HEARING_EVIDENCE_RESULT_KEY_MISMATCH")
    encoded = _canonical_json_bytes(payload)
    if len(encoded) > MAX_HEARING_WORK_RESULT_BYTES:
        raise HearingGraphContractError("HEARING_EVIDENCE_RESULT_TOO_LARGE")
    return {"work_results": {key: payload}}


def keyed_evidence_fan_in(state: HearingGraphStateV1) -> dict[str, Any]:
    wave = state.get("in_flight_keys")
    results = state.get("work_results")
    index = state.get("next_dispatch_index")
    if (
        not isinstance(wave, list)
        or not wave
        or not isinstance(results, dict)
        or not all(key in results for key in wave)
        or isinstance(index, bool)
        or not isinstance(index, int)
    ):
        raise HearingGraphContractError("HEARING_EVIDENCE_WAVE_INCOMPLETE")
    return {
        "next_dispatch_index": index + len(wave),
        "in_flight_keys": [],
        "current_wave_keys": [],
    }


def complete_evidence_synthesis(
    state: HearingGraphStateV1,
    runtime: Runtime[HearingGraphInvocation],
) -> dict[str, Any]:
    ordered = state.get("ordered_work_item_keys")
    results = state.get("work_results")
    if (
        not isinstance(ordered, list)
        or not isinstance(results, dict)
        or list(results) != ordered
        or state.get("next_dispatch_index") != len(ordered)
        or state.get("in_flight_keys") != []
    ):
        raise HearingGraphContractError("HEARING_EVIDENCE_COVERAGE_INCOMPLETE")
    projector = runtime.context.execute_with_work_results
    if not callable(projector):
        raise HearingGraphContractError("HEARING_EVIDENCE_PROJECTOR_REQUIRED")
    result = projector(runtime.context.request, results)
    return _proposal_update(result)


def execute_operation(
    state: HearingGraphStateV1,
    runtime: Runtime[HearingGraphInvocation],
    *,
    expected_operation: HearingOperation,
) -> dict[str, Any]:
    if state.get("operation") != expected_operation.value or state.get("route") != (
        expected_operation.value
    ):
        raise HearingGraphContractError("HEARING_ROUTED_OPERATION_MISMATCH")
    result = runtime.context.execute(runtime.context.request)
    return _proposal_update(result)


def _proposal_update(result: BaseModel) -> dict[str, Any]:
    if not isinstance(result, BaseModel):
        raise HearingGraphContractError("HEARING_PROPOSAL_NOT_TYPED")
    proposal = result.model_dump(mode="json")
    _validate_proposal_only(proposal)
    encoded = _canonical_json_bytes(proposal)
    if len(encoded) > MAX_HEARING_PROPOSAL_BYTES:
        raise HearingGraphContractError("HEARING_PROPOSAL_TOO_LARGE")
    schema_version = proposal.get("schema_version")
    if not isinstance(schema_version, str) or not schema_version:
        raise HearingGraphContractError("HEARING_PROPOSAL_SCHEMA_VERSION_MISSING")
    return {
        "status": "PROPOSED",
        "proposal_schema_version": schema_version,
        "proposal": proposal,
    }


def _canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise HearingGraphContractError("HEARING_JSON_NOT_SERIALIZABLE") from error


def _validate_proposal_only(value: Any) -> None:
    if isinstance(value, Mapping):
        if any(key in value for key in _FORMAL_EFFECT_FIELDS):
            raise HearingGraphContractError("HEARING_FORMAL_EFFECT_FORBIDDEN")
        formal_sink = value.get("formal_sink_eligible")
        if formal_sink is not None and formal_sink is not False:
            raise HearingGraphContractError("HEARING_FORMAL_EFFECT_FORBIDDEN")
        writer_mode = value.get("writer_mode")
        if writer_mode is not None and writer_mode != "PROPOSAL_ONLY":
            raise HearingGraphContractError("HEARING_FORMAL_EFFECT_FORBIDDEN")
        for child in value.values():
            _validate_proposal_only(child)
    elif isinstance(value, list | tuple):
        for child in value:
            _validate_proposal_only(child)


def project_proposal(state: HearingGraphStateV1) -> dict[str, Any]:
    if (
        state.get("status") != "PROPOSED"
        or not isinstance(state.get("proposal"), dict)
        or not isinstance(state.get("proposal_schema_version"), str)
    ):
        raise HearingGraphContractError("HEARING_PROPOSAL_INCOMPLETE")
    return {}
