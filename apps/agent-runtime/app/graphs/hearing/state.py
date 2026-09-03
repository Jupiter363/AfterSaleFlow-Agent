from __future__ import annotations

import hashlib
import json
from collections.abc import Awaitable, Callable, Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
from typing import Annotated, Any, Literal

from pydantic import BaseModel
from typing_extensions import NotRequired, TypedDict

from app.graphs.hearing.contracts import HearingGraphIdentity, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.reducers import merge_keyed_hearing_results
from app.harness.invocation_context import AgentInvocationContext


MAX_HEARING_PROPOSAL_BYTES = 2_000_000
MAX_HEARING_WORK_RESULT_BYTES = 128_000
MAX_HEARING_EVIDENCE_ITEMS = 100
MAX_HEARING_EVIDENCE_SENDS = 8


class HearingCommandBindingV1(TypedDict):
    schema_version: Literal["hearing-command-binding.v1"]
    command_id: str
    operation_key: str
    command_request_hash: str
    thread_id: str
    tenant_surrogate: str
    room_epoch: int
    process_revision: int
    java_room_fencing_token: int
    graph_lease_owner_id: str
    graph_lease_fencing_token: int


class HearingArtifactRefV1(TypedDict):
    artifact_id: str
    schema_version: str
    uri: str
    sha256: str


class HearingScopeBindingV1(TypedDict):
    schema_version: Literal["hearing-scope-binding.v1"]
    state_scope: Literal["ACTOR_PRIVATE", "SHARED"]
    actor_scope_hash: str
    authorized_artifact_refs: list[HearingArtifactRefV1]
    shared_barrier_receipt_hash: str | None


class HearingGraphStateV1(TypedDict):
    schema_version: str
    graph_identity: str
    version_pins: "HearingGraphVersionPins"
    operation: str
    case_id: str
    workflow_id: str
    stage_sequence: int
    request_schema_version: str
    request_hash: str
    status: Literal["PENDING", "PROPOSED"]
    cognitive_revision: NotRequired[int]
    command_binding: NotRequired[HearingCommandBindingV1]
    scope_binding: NotRequired[HearingScopeBindingV1]
    route: NotRequired[str]
    ordered_work_item_keys: NotRequired[list[str]]
    next_dispatch_index: NotRequired[int]
    current_wave_keys: NotRequired[list[str]]
    in_flight_keys: NotRequired[list[str]]
    work_results: NotRequired[
        Annotated[dict[str, dict[str, Any]], merge_keyed_hearing_results]
    ]
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
    execute: Callable[[BaseModel], BaseModel | Awaitable[BaseModel]]
    agent_context: AgentInvocationContext | None = None
    plan_work_items: Callable[[BaseModel], Sequence[str]] | None = None
    execute_work_item: (
        Callable[[BaseModel, str], BaseModel | Awaitable[BaseModel]] | None
    ) = None
    execute_with_work_results: (
        Callable[
            [BaseModel, Mapping[str, Mapping[str, Any]]],
            BaseModel | Awaitable[BaseModel],
        ]
        | None
    ) = None


def request_hash(request: BaseModel) -> str:
    try:
        encoded = json.dumps(
            request.model_dump(mode="json"),
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise HearingGraphContractError("HEARING_REQUEST_NOT_SERIALIZABLE") from error
    return hashlib.sha256(encoded).hexdigest()


def new_hearing_graph_state(
    *,
    identity: HearingGraphIdentity,
    operation: HearingOperation,
    request: BaseModel,
    command_binding: HearingCommandBindingV1 | None = None,
    scope_binding: HearingScopeBindingV1 | None = None,
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
    state: HearingGraphStateV1 = {
        "schema_version": identity.state_schema_version,
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
    if (command_binding is None) != (scope_binding is None):
        raise HearingGraphContractError("HEARING_RUNTIME_BINDINGS_INCOMPLETE")
    if command_binding is not None and scope_binding is not None:
        state["command_binding"] = deepcopy(command_binding)
        state["scope_binding"] = deepcopy(scope_binding)
        state["cognitive_revision"] = 1
    return state


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
