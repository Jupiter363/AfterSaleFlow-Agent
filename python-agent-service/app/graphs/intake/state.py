from __future__ import annotations

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


JsonObject: TypeAlias = dict[str, JsonValue]


class IntakeCommandBindings(TypedDict):
    schema_version: Literal["graph-command-binding.v1"]
    command_id: str
    logical_run_id: str
    attempt_id: str
    tenant_surrogate: str
    case_id: str
    room_type: Literal["INTAKE"]
    room_epoch: int
    actor_scope_hash: str
    thread_id: str
    agent_session_id: str
    audience: Literal["USER", "MERCHANT"]


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
    ordered = sorted(
        merged.items(),
        key=lambda item: (item[1]["sequence"], item[0]),
    )
    return dict(ordered[-6:])


class IntakeGraphStateV2(TypedDict):
    schema_version: Literal["intake-graph-state.v2"]
    bindings: IntakeCommandBindings
    version_pins: VersionPinsState
    cognitive_revision: int
    messages: Annotated[dict[str, IntakeMessageState], merge_intake_messages]
    memory_summary: str
    dossier_draft: JsonObject
    readiness: IntakeReadinessState
    missing_fields: list[str]
    recommendation: Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
    node_results: Annotated[dict[str, JsonObject], merge_node_results]
    execution_receipts: Annotated[
        dict[str, ExecutionReceiptState], merge_execution_receipts
    ]
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
    ingress_kind: Literal["SNAPSHOT", "EVENT"]
    ingress_payload: JsonObject


def new_intake_graph_state(
    *,
    bindings: IntakeCommandBindings,
    version_pins: VersionPinsState,
) -> IntakeGraphStateV2:
    return {
        "schema_version": "intake-graph-state.v2",
        "bindings": bindings,
        "version_pins": version_pins,
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
