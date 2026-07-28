from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Annotated, Literal, TypeAlias

from typing_extensions import NotRequired, TypedDict

from app.graph_runtime.reducers import (
    merge_execution_receipts,
    merge_keyed_json,
    merge_node_results,
    merge_usage_by_invocation,
)
from app.graph_runtime.state import (
    ExecutionReceiptState,
    JsonValue,
    UsageState,
    VersionPinsState,
)
from app.graphs.intake.errors import IntakeGraphContractError


JsonObject: TypeAlias = dict[str, JsonValue]


class IntakePrivateBindings(TypedDict):
    schema_version: Literal["intake-private-binding.v1"]
    tenant_surrogate: str
    case_id: str
    room_type: Literal["INTAKE"]
    room_epoch: int
    actor_scope_hash: str
    thread_id: str
    agent_session_id: str
    audience: Literal["USER", "MERCHANT"]


class IntakeCommandBindings(TypedDict):
    schema_version: Literal["intake-command-binding.v1"]
    command_id: str
    logical_run_id: str
    attempt_id: str


class IntakeGraphBindings(TypedDict):
    schema_version: Literal["intake-graph-bindings.v2"]
    private: IntakePrivateBindings
    command: IntakeCommandBindings


def merge_intake_bindings(
    left: IntakeGraphBindings | None,
    right: IntakeGraphBindings | None,
) -> IntakeGraphBindings:
    if right is None:
        if not isinstance(left, dict) or not left:
            raise IntakeGraphContractError("INTAKE_BINDINGS_MISSING")
        return deepcopy(left)
    if not isinstance(right, dict) or (left is not None and not isinstance(left, dict)):
        raise IntakeGraphContractError("INTAKE_BINDINGS_INVALID")
    if left and left.get("private") != right.get("private"):
        raise IntakeGraphContractError("INTAKE_PRIVATE_BINDING_IMMUTABLE")
    return deepcopy(right)


def merge_intake_version_pins(
    left: VersionPinsState | None,
    right: VersionPinsState | None,
) -> VersionPinsState:
    if right is None:
        if not isinstance(left, dict) or not left:
            raise IntakeGraphContractError("INTAKE_VERSION_PINS_INVALID")
        return deepcopy(left)
    if not isinstance(right, dict) or (left is not None and not isinstance(left, dict)):
        raise IntakeGraphContractError("INTAKE_VERSION_PINS_INVALID")
    if left and left != right:
        raise IntakeGraphContractError("INTAKE_VERSION_PINS_IMMUTABLE")
    return deepcopy(right)


class IntakeMessageState(TypedDict):
    message_id: str
    role: Literal["HUMAN", "AI"]
    audience: Literal["USER", "MERCHANT"]
    content: str
    sequence: int
    source_hash: str


class IntakeReadinessState(TypedDict):
    status: Literal["INCOMPLETE", "READY_TO_CONFIRM", "NEEDS_REVIEW"]
    evaluated_revision: int


def merge_intake_messages(
    left: dict[str, IntakeMessageState] | None,
    right: dict[str, IntakeMessageState] | None,
) -> dict[str, IntakeMessageState]:
    merged = merge_keyed_json(left, right, namespace="intake_messages")
    try:
        ordered = sorted(
            merged.items(),
            key=lambda item: (item[1]["sequence"], item[0]),
        )
    except (KeyError, TypeError) as error:
        raise IntakeGraphContractError("INTAKE_MESSAGE_REDUCER_INVALID") from error
    return dict(ordered[-6:])


class IntakeGraphStateV2(TypedDict):
    schema_version: Literal["intake-graph-state.v2"]
    bindings: Annotated[IntakeGraphBindings, merge_intake_bindings]
    version_pins: Annotated[VersionPinsState, merge_intake_version_pins]
    cognitive_revision: int
    messages: Annotated[dict[str, IntakeMessageState], merge_intake_messages]
    memory_summary: str
    dossier_draft: JsonObject
    readiness: IntakeReadinessState
    missing_fields: list[str]
    recommendation: Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
    node_results: Annotated[dict[str, JsonObject], merge_node_results]
    execution_receipts: Annotated[dict[str, ExecutionReceiptState], merge_execution_receipts]
    usage_by_invocation: Annotated[dict[str, UsageState], merge_usage_by_invocation]
    initial_snapshot_ref: NotRequired[str]
    initial_snapshot_hash: NotRequired[str]
    initial_domain_revision: NotRequired[int]
    last_event_ref: NotRequired[str]
    last_event_hash: NotRequired[str]
    last_event_sequence: NotRequired[int]
    route: NotRequired[Literal["initialize", "message", "replay"]]
    terminal_draft: NotRequired[JsonObject]
    result_json: NotRequired[JsonObject]


@dataclass(frozen=True, slots=True)
class IntakeTurnContext:
    ingress_kind: Literal["SNAPSHOT", "EVENT", "BOOTSTRAP_EVENT"]
    ingress_payload: JsonObject


def new_intake_graph_state(
    *,
    bindings: IntakeGraphBindings,
    version_pins: VersionPinsState,
) -> IntakeGraphStateV2:
    return {
        "schema_version": "intake-graph-state.v2",
        "bindings": merge_intake_bindings(None, bindings),
        "version_pins": merge_intake_version_pins(None, version_pins),
        "cognitive_revision": 0,
        "messages": {},
        "memory_summary": "",
        "dossier_draft": {},
        "readiness": {"status": "INCOMPLETE", "evaluated_revision": 0},
        "missing_fields": [],
        "recommendation": "NEED_MORE_INFO",
        "node_results": {},
        "execution_receipts": {},
        "usage_by_invocation": {},
    }
