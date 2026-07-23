from __future__ import annotations

import json
from typing import Any

from pydantic import BaseModel
from langgraph.runtime import Runtime

from app.graphs.hearing.contracts import HearingGraphIdentity, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.state import (
    MAX_HEARING_PROPOSAL_BYTES,
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
        "route",
        "proposal_schema_version",
        "proposal",
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
    if not isinstance(result, BaseModel):
        raise HearingGraphContractError("HEARING_PROPOSAL_NOT_TYPED")
    proposal = result.model_dump(mode="json")
    encoded = json.dumps(
        proposal,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
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


def project_proposal(state: HearingGraphStateV1) -> dict[str, Any]:
    if (
        state.get("status") != "PROPOSED"
        or not isinstance(state.get("proposal"), dict)
        or not isinstance(state.get("proposal_schema_version"), str)
    ):
        raise HearingGraphContractError("HEARING_PROPOSAL_INCOMPLETE")
    return {}
