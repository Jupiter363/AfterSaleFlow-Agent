from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel
from typing_extensions import NotRequired, TypedDict

from app.graphs.hearing.contracts import HearingGraphIdentity, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError


MAX_HEARING_PROPOSAL_BYTES = 2_000_000


class HearingGraphStateV1(TypedDict):
    schema_version: Literal["hearing.graph-state.v1"]
    graph_identity: str
    version_pins: "HearingGraphVersionPins"
    operation: str
    case_id: str
    workflow_id: str
    stage_sequence: int
    request_schema_version: str
    request_hash: str
    status: Literal["PENDING", "PROPOSED"]
    route: NotRequired[str]
    proposal_schema_version: NotRequired[str]
    proposal: NotRequired[dict[str, Any]]


class HearingGraphVersionPins(TypedDict):
    graph_key: str
    graph_version: str
    checkpoint_schema_version: str
    state_schema_version: str
    prompt_version: str
    model_profile_id: str
    output_schema_version: str
    policy_version: str
    guardrail_version: str
    tool_policy_version: str


@dataclass(frozen=True, slots=True)
class HearingGraphInvocation:
    """Ephemeral input; request bodies and injected executors never enter graph state."""

    request: BaseModel
    execute: Callable[[BaseModel], BaseModel]


def request_hash(request: BaseModel) -> str:
    encoded = json.dumps(
        request.model_dump(mode="json"),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def new_hearing_graph_state(
    *,
    identity: HearingGraphIdentity,
    operation: HearingOperation,
    request: BaseModel,
) -> HearingGraphStateV1:
    if operation not in identity.operations:
        raise HearingGraphContractError("HEARING_OPERATION_GRAPH_MISMATCH")
    case_id = getattr(request, "case_id", None)
    workflow_id = getattr(request, "workflow_id", None)
    stage_sequence = getattr(request, "stage_sequence", None)
    flow_schema_version = getattr(request, "flow_schema_version", None)
    if (
        not isinstance(case_id, str)
        or not isinstance(workflow_id, str)
        or isinstance(stage_sequence, bool)
        or not isinstance(stage_sequence, int)
        or stage_sequence < 1
        or flow_schema_version != "hearing_flow.v2"
    ):
        raise HearingGraphContractError("HEARING_REQUEST_BINDING_INVALID")
    return {
        "schema_version": "hearing.graph-state.v1",
        "graph_identity": identity.identity,
        "version_pins": version_pins(identity),
        "operation": operation.value,
        "case_id": case_id,
        "workflow_id": workflow_id,
        "stage_sequence": stage_sequence,
        "request_schema_version": flow_schema_version,
        "request_hash": request_hash(request),
        "status": "PENDING",
    }


def version_pins(identity: HearingGraphIdentity) -> HearingGraphVersionPins:
    return {
        "graph_key": identity.graph_key,
        "graph_version": identity.graph_version,
        "checkpoint_schema_version": identity.checkpoint_schema_version,
        "state_schema_version": identity.state_schema_version,
        "prompt_version": identity.prompt_version,
        "model_profile_id": identity.model_profile_id,
        "output_schema_version": identity.output_schema_version,
        "policy_version": identity.policy_version,
        "guardrail_version": identity.guardrail_version,
        "tool_policy_version": identity.tool_policy_version,
    }
