from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated, Literal, TypeAlias

from typing_extensions import NotRequired, TypedDict

from app.contracts.v1.codec import canonicalize
from app.graph_runtime.reducers import (
    merge_artifact_refs,
    merge_execution_receipts,
    merge_messages,
    merge_node_results,
    merge_usage_by_invocation,
    merge_work_items,
    merge_work_results,
)


JsonScalar: TypeAlias = str | int | float | bool | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]


class CommandBindingState(TypedDict):
    schema_version: Literal["graph-command-binding.v1"]
    command_id: str
    logical_run_id: str
    attempt_id: str
    tenant_surrogate: str
    case_id: str
    room_type: Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
    room_epoch: int
    actor_scope_hash: str
    thread_id: str


class VersionPinsState(TypedDict):
    schema_version: Literal["graph-version-pins.v1"]
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


class MessageState(TypedDict):
    message_id: str
    role: Literal["SYSTEM", "HUMAN", "AI", "TOOL"]
    audience: Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "SYSTEM"]
    content: str
    sequence: int


class WorkItemState(TypedDict):
    work_item_id: str
    kind: str
    payload: dict[str, JsonValue]


class WorkResultState(TypedDict):
    work_item_id: str
    status: Literal["COMPLETED", "FAILED"]
    payload: dict[str, JsonValue]


class ArtifactRefState(TypedDict):
    artifact_id: str
    schema_version: str
    uri: str
    sha256: str


class ExecutionReceiptState(TypedDict):
    invocation_id: str
    node_name: str
    output_hash: str


class UsageState(TypedDict):
    input_tokens: int
    output_tokens: int
    total_tokens: int


class CommonGraphState(TypedDict):
    bindings: CommandBindingState
    version_pins: VersionPinsState
    cognitive_revision: int
    messages: Annotated[dict[str, MessageState], merge_messages]
    work_items: Annotated[dict[str, WorkItemState], merge_work_items]
    work_results: Annotated[dict[str, WorkResultState], merge_work_results]
    artifact_refs: Annotated[dict[str, ArtifactRefState], merge_artifact_refs]
    node_results: Annotated[dict[str, dict[str, JsonValue]], merge_node_results]
    execution_receipts: Annotated[
        dict[str, ExecutionReceiptState], merge_execution_receipts
    ]
    usage_by_invocation: Annotated[dict[str, UsageState], merge_usage_by_invocation]
    memory_summary: NotRequired[str]
    route: NotRequired[str]
    terminal_draft: NotRequired[dict[str, JsonValue]]
    result_json: NotRequired[dict[str, JsonValue]]


@dataclass(frozen=True, slots=True)
class GraphStateLimits:
    checkpoint_bytes: int = 1_048_576
    patch_bytes: int = 262_144
    message_count: int = 32
    message_total_bytes: int = 65_536
    message_bytes: int = 8_192
    memory_summary_bytes: int = 16_384
    pending_work_items: int = 64
    artifact_refs: int = 100
    artifact_ref_bytes: int = 2_048
    send_items: int = 8
    warning_ratio: float = 0.8

    def __post_init__(self) -> None:
        numeric_limits = (
            self.checkpoint_bytes,
            self.patch_bytes,
            self.message_count,
            self.message_total_bytes,
            self.message_bytes,
            self.memory_summary_bytes,
            self.pending_work_items,
            self.artifact_refs,
            self.artifact_ref_bytes,
            self.send_items,
        )
        if min(numeric_limits) < 1 or not 0 < self.warning_ratio <= 1:
            raise ValueError("Graph state limits must be positive and warning ratio at most one")


@dataclass(frozen=True, slots=True)
class GraphStateUsage:
    checkpoint_bytes: int
    message_count: int
    message_total_bytes: int
    pending_work_items: int
    artifact_refs: int
    warning_fields: tuple[str, ...]


class GraphStateLimitError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def validate_graph_state(
    state: dict[str, object],
    *,
    limits: GraphStateLimits | None = None,
) -> GraphStateUsage:
    selected = limits or GraphStateLimits()
    try:
        checkpoint_bytes = len(canonicalize(state))
    except (TypeError, ValueError) as error:
        raise GraphStateLimitError("GRAPH_STATE_NOT_CANONICAL_JSON") from error
    _require_at_most(checkpoint_bytes, selected.checkpoint_bytes, "GRAPH_STATE_TOO_LARGE")

    messages = _require_mapping(state.get("messages", {}), "GRAPH_STATE_MESSAGES_INVALID")
    _require_at_most(len(messages), selected.message_count, "GRAPH_STATE_MESSAGES_TOO_MANY")
    message_total_bytes = 0
    for message in messages.values():
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            raise GraphStateLimitError("GRAPH_STATE_MESSAGE_INVALID")
        size = len(message["content"].encode("utf-8"))
        _require_at_most(size, selected.message_bytes, "GRAPH_STATE_MESSAGE_TOO_LARGE")
        message_total_bytes += size
    _require_at_most(
        message_total_bytes,
        selected.message_total_bytes,
        "GRAPH_STATE_MESSAGE_WINDOW_TOO_LARGE",
    )

    summary = state.get("memory_summary", "")
    if not isinstance(summary, str):
        raise GraphStateLimitError("GRAPH_STATE_SUMMARY_INVALID")
    _require_at_most(
        len(summary.encode("utf-8")),
        selected.memory_summary_bytes,
        "GRAPH_STATE_SUMMARY_TOO_LARGE",
    )

    work_items = _require_mapping(state.get("work_items", {}), "GRAPH_STATE_WORK_INVALID")
    work_results = _require_mapping(
        state.get("work_results", {}), "GRAPH_STATE_RESULTS_INVALID"
    )
    pending = len(set(work_items) - set(work_results))
    _require_at_most(
        pending,
        selected.pending_work_items,
        "GRAPH_STATE_PENDING_WORK_TOO_LARGE",
    )

    artifacts = _require_mapping(
        state.get("artifact_refs", {}), "GRAPH_STATE_ARTIFACTS_INVALID"
    )
    _require_at_most(
        len(artifacts), selected.artifact_refs, "GRAPH_STATE_ARTIFACTS_TOO_MANY"
    )
    for artifact in artifacts.values():
        _require_at_most(
            len(canonicalize(artifact)),
            selected.artifact_ref_bytes,
            "GRAPH_STATE_ARTIFACT_REF_TOO_LARGE",
        )

    values = {
        "checkpoint_bytes": (checkpoint_bytes, selected.checkpoint_bytes),
        "message_count": (len(messages), selected.message_count),
        "message_total_bytes": (message_total_bytes, selected.message_total_bytes),
        "pending_work_items": (pending, selected.pending_work_items),
        "artifact_refs": (len(artifacts), selected.artifact_refs),
    }
    warnings = tuple(
        name
        for name, (used, maximum) in values.items()
        if used >= maximum * selected.warning_ratio
    )
    return GraphStateUsage(
        checkpoint_bytes=checkpoint_bytes,
        message_count=len(messages),
        message_total_bytes=message_total_bytes,
        pending_work_items=pending,
        artifact_refs=len(artifacts),
        warning_fields=warnings,
    )


def validate_graph_patch(
    patch: dict[str, object],
    *,
    limits: GraphStateLimits | None = None,
) -> int:
    selected = limits or GraphStateLimits()
    try:
        size = len(canonicalize(patch))
    except (TypeError, ValueError) as error:
        raise GraphStateLimitError("GRAPH_PATCH_NOT_CANONICAL_JSON") from error
    _require_at_most(size, selected.patch_bytes, "GRAPH_PATCH_TOO_LARGE")
    return size


def _require_mapping(value: object, code: str) -> dict[str, object]:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise GraphStateLimitError(code)
    return value


def _require_at_most(value: int, maximum: int, code: str) -> None:
    if value > maximum:
        raise GraphStateLimitError(code)
