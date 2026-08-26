from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Literal, Protocol, TypedDict, cast
from urllib.parse import quote

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graph_runtime.checkpoint import (
    TECHNICAL_CHILD_CHECKPOINT_CONTEXT_KEY,
    TechnicalChildCheckpointBinding,
)
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.parallel_contracts import (
    FRAME_OUTPUT_SCHEMA,
    FRAME_PROMPT_PROFILE,
    FRAME_TYPES,
    Identifier,
    IntakeFrameModelInputV2,
    IntakeModelContextViewV1,
    ParallelFrameType,
    PartyRole,
    Sha256,
)
from app.graphs.intake.parallel_outputs import (
    FRAME_OUTPUT_MODELS,
    DialoguePublicSegmentDraftV3,
    DossierPublicFactDraftV3,
    IntakeDialogueFrameV3,
    IntakeDossierFrameV3,
    IntakeQualityFrameV2,
    ParallelFrameOutput,
    QUALITY_DIMENSION_ORDER,
    QualityPublicGapDraftV2,
    QualityPublicMetricDraftV2,
    QualityPublicProjectionDraftV2,
    materialize_request_bound_frame_output,
    request_bound_dialogue_output_types,
    request_bound_dossier_output_types,
    request_bound_quality_output_types,
    validate_parallel_frame_output,
)
from app.harness.context_window import PromptSection
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import (
    HarnessStreamCompleted,
    HarnessStreamDelta,
    HarnessStreamReset,
)
from app.model_runtime.governed_chat_model import ModelStreamInterrupted
from app.streaming import VisibleFieldSpec


FRAME_NODE_NAMES: Mapping[ParallelFrameType, str] = {
    "DIALOGUE_FRAME": "intake_turn_dialogue_frame",
    "DOSSIER_FRAME": "intake_turn_dossier_frame",
    "QUALITY_FRAME": "intake_turn_quality_frame",
}

FRAME_PUBLIC_ITEM_LIMITS: Mapping[ParallelFrameType, int] = {
    "DIALOGUE_FRAME": 1,
    "DOSSIER_FRAME": 5,
    "QUALITY_FRAME": 12,
}

FRAME_PUBLIC_ITEM_MODELS: Mapping[ParallelFrameType, type[BaseModel]] = {
    "DIALOGUE_FRAME": DialoguePublicSegmentDraftV3,
    "DOSSIER_FRAME": DossierPublicFactDraftV3,
    "QUALITY_FRAME": QualityPublicProjectionDraftV2,
}

QUALITY_DIMENSION_MAXIMA: Mapping[str, int] = {
    "REFERENCES": 15,
    "EVENT_STORY": 20,
    "PARTY_POSITIONS": 20,
    "REQUESTED_RESOLUTION": 15,
    "RISK_AND_CONFLICTS": 15,
    "NEXT_ACTION_CLARITY": 15,
}

_MODEL_CONTEXT_SECTION_NAME = "parallel_frame_model_input"
_CHECKPOINT_SCHEMA_VERSION = "intake.parallel-frame-checkpoint.v2"
_LEGACY_CHECKPOINT_SCHEMA_VERSION = "intake.parallel-frame-checkpoint.v1"
_EVENT_SCHEMA_VERSION = "intake.parallel-frame-technical-event.v1"


class ParallelFrameModelRunner(Protocol):
    def ainvoke_structured_stream(self, **kwargs: Any) -> AsyncIterator[Any]: ...


class ParallelFrameTechnicalEventSink(Protocol):
    async def emit(self, event: "ParallelFrameTechnicalEvent") -> None: ...


class StrictParallelRuntimeModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ParallelFrameExecutionRequest(StrictParallelRuntimeModel):
    frame_set_id: Identifier
    run_id: Identifier
    attempt_id: Identifier
    command_id: Identifier
    command_request_sha256: Sha256
    case_id: Identifier
    actor_id: Identifier
    actor_role: PartyRole
    source_message_id: Identifier
    context_envelope_sha256: Sha256
    frame_type: ParallelFrameType
    generation: int = Field(ge=1)
    frame_id: Identifier
    model_input: IntakeFrameModelInputV2
    resume_generation: int | None = Field(default=None, ge=1)
    resume_frame_id: Identifier | None = None
    resume_local_index: int = Field(default=0, ge=0)
    emit_start: bool = True
    allow_generation_reset: bool = True

    @model_validator(mode="after")
    def validate_frame_authority(self) -> "ParallelFrameExecutionRequest":
        if self.model_input.frame_type != self.frame_type:
            raise ValueError("model input belongs to a foreign Frame")
        if (
            self.model_input.common_model_context.current_user_message.source_role
            != self.actor_role
        ):
            raise ValueError("model input belongs to a foreign actor role")
        if (self.resume_generation is None) != (self.resume_frame_id is None):
            raise ValueError("Frame resume generation and frame_id must be supplied together")
        if self.resume_generation is not None:
            if self.resume_generation not in {self.generation, self.generation + 1}:
                raise ValueError("Frame resume generation is outside the bounded lineage")
            if (
                self.resume_generation == self.generation
                and self.resume_frame_id != self.frame_id
            ):
                raise ValueError("initial Frame resume identity drifted")
            if (
                self.resume_generation > self.generation
                and self.resume_frame_id == self.frame_id
            ):
                raise ValueError("replacement Frame resume identity did not advance")
        if self.resume_local_index > FRAME_PUBLIC_ITEM_LIMITS[self.frame_type]:
            raise ValueError("Frame resume index exceeds the bounded public projection")
        if self.generation > 1 and self.allow_generation_reset:
            raise ValueError("replacement Frame cannot authorize another generation reset")
        return self

    def resume_position(self) -> tuple[int, str]:
        return (
            self.generation if self.resume_generation is None else self.resume_generation,
            self.frame_id if self.resume_frame_id is None else self.resume_frame_id,
        )

    def checkpoint_identity(self) -> dict[str, Any]:
        return {
            "frame_set_id": self.frame_set_id,
            "run_id": self.run_id,
            "attempt_id": self.attempt_id,
            "command_id": self.command_id,
            "command_request_sha256": self.command_request_sha256,
            "case_id": self.case_id,
            "actor_id": self.actor_id,
            "actor_role": self.actor_role,
            "source_message_id": self.source_message_id,
            "context_envelope_sha256": self.context_envelope_sha256,
            "frame_type": self.frame_type,
            "generation": self.generation,
            "frame_id": self.frame_id,
            "allow_generation_reset": self.allow_generation_reset,
            "frame_model_input_sha256": self.model_input.frame_model_input_sha256,
            "model_context_view_sha256": (
                self.model_input.common_model_context.model_context_view_sha256
            ),
        }


class FrameProviderUsage(StrictParallelRuntimeModel):
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)
    latency_ms: int = Field(ge=0)
    provider_call_count: int = Field(ge=1, le=2)
    model: Identifier

    @model_validator(mode="after")
    def validate_total(self) -> "FrameProviderUsage":
        if self.total_tokens != self.input_tokens + self.output_tokens:
            raise ValueError("total_tokens must equal input_tokens + output_tokens")
        return self


class CanonicalPublicProjectionItem(StrictParallelRuntimeModel):
    canonical_item_id: Identifier
    projection_kind: Identifier
    projection_path_id: Identifier
    value_kind: Literal["TEXT", "JSON_VALUE"]
    public_text: str | None = None
    canonical_value: Any | None = None

    @model_validator(mode="after")
    def validate_value_partition(self) -> "CanonicalPublicProjectionItem":
        if self.value_kind == "TEXT":
            if (
                self.public_text is None
                or "canonical_value" in self.model_fields_set
            ):
                raise ValueError("TEXT projection requires only public_text")
        elif (
            self.public_text is not None
            or "public_text" in self.model_fields_set
            or "canonical_value" not in self.model_fields_set
        ):
            raise ValueError("JSON_VALUE projection requires only canonical_value")
        return self

    @property
    def item_sha256(self) -> str:
        return canonical_sha256(self.model_dump(mode="json", exclude_none=True))


class _FrameEvent(StrictParallelRuntimeModel):
    schema_version: Literal["intake.parallel-frame-technical-event.v1"] = (
        _EVENT_SCHEMA_VERSION
    )
    frame_set_id: Identifier
    run_id: Identifier
    attempt_id: Identifier
    frame_type: ParallelFrameType
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class FrameStarted(_FrameEvent):
    event_kind: Literal["FRAME_STARTED"] = "FRAME_STARTED"
    generation: int = Field(ge=1)
    frame_id: Identifier
    frame_model_input_sha256: Sha256
    frame_prompt_sha256: Sha256
    context_envelope_sha256: Sha256
    model_context_view_sha256: Sha256


class FrameProjectionItem(_FrameEvent):
    event_kind: Literal["FRAME_PROJECTION_ITEM"] = "FRAME_PROJECTION_ITEM"
    generation: int = Field(ge=1)
    frame_id: Identifier
    local_index: int = Field(ge=0)
    next_local_index: int = Field(ge=1)
    item: CanonicalPublicProjectionItem
    item_sha256: Sha256

    @model_validator(mode="after")
    def validate_item_binding(self) -> "FrameProjectionItem":
        if self.next_local_index != self.local_index + 1:
            raise ValueError("next_local_index must equal local_index + 1")
        if self.item.item_sha256 != self.item_sha256:
            raise ValueError("item_sha256 does not bind the canonical item")
        return self


class FrameGenerationReset(_FrameEvent):
    event_kind: Literal["FRAME_GENERATION_RESET"] = "FRAME_GENERATION_RESET"
    old_generation: int = Field(ge=1)
    new_generation: int = Field(ge=2)
    old_frame_id: Identifier
    new_frame_id: Identifier
    reason_code: Literal["OUTPUT_SCHEMA_INVALID"]

    @model_validator(mode="after")
    def validate_generation_step(self) -> "FrameGenerationReset":
        if self.new_generation != self.old_generation + 1:
            raise ValueError("Frame generation must advance exactly once")
        if self.old_frame_id == self.new_frame_id:
            raise ValueError("Frame generation reset must replace frame_id")
        return self


class FrameInterrupted(_FrameEvent):
    event_kind: Literal["FRAME_INTERRUPTED"] = "FRAME_INTERRUPTED"
    generation: int = Field(ge=1)
    frame_id: Identifier
    error_code: Identifier
    retryable: bool


class FrameSealed(_FrameEvent):
    event_kind: Literal["FRAME_SEALED"] = "FRAME_SEALED"
    generation: int = Field(ge=1)
    frame_id: Identifier
    child_checkpoint_ref: str = Field(min_length=1, max_length=1024)
    child_checkpoint_sha256: Sha256
    context_envelope_sha256: Sha256
    model_context_view_sha256: Sha256
    canonical_result_json: str = Field(min_length=2, max_length=262_144)
    result_sha256: Sha256
    public_projection_sha256: Sha256
    next_local_index: int = Field(ge=0)
    usage: FrameProviderUsage
    completed_at: datetime


ParallelFrameTechnicalEvent = (
    FrameStarted
    | FrameProjectionItem
    | FrameGenerationReset
    | FrameInterrupted
    | FrameSealed
)


class ParallelFrameGraphState(TypedDict, total=False):
    checkpoint_schema_version: str
    authority: dict[str, Any]
    model_input: dict[str, Any]
    status: str
    generation: int
    frame_id: str
    generation_transition_events: list[dict[str, Any]]
    reset_provider_usage: dict[str, Any]
    provider_projection_items: list[dict[str, Any]]
    canonical_projection_items: list[dict[str, Any]]
    canonical_result: dict[str, Any]
    canonical_result_json: str
    result_sha256: str
    public_projection_sha256: str
    usage: dict[str, Any]
    completed_at: str


@dataclass(frozen=True)
class ParallelFrameGraphRuntime:
    request: ParallelFrameExecutionRequest
    agent_context: AgentInvocationContext
    model_runner: ParallelFrameModelRunner
    event_sink: ParallelFrameTechnicalEventSink


@dataclass(frozen=True)
class ParallelFrameExecutionResult:
    frame_type: ParallelFrameType
    generation: int
    frame_id: str
    result: ParallelFrameOutput
    result_sha256: str
    public_projection_sha256: str
    child_checkpoint_ref: str
    child_checkpoint_sha256: str
    replayed_from_checkpoint: bool


@dataclass(frozen=True)
class ParallelFrameFailure:
    frame_type: ParallelFrameType
    error_code: str


@dataclass(frozen=True)
class ParallelFrameBatchResult:
    completed: Mapping[ParallelFrameType, ParallelFrameExecutionResult]
    failed: Mapping[ParallelFrameType, ParallelFrameFailure]

    @property
    def all_succeeded(self) -> bool:
        return not self.failed


def new_parallel_frame_state(
    request: ParallelFrameExecutionRequest,
) -> ParallelFrameGraphState:
    return {
        "checkpoint_schema_version": _CHECKPOINT_SCHEMA_VERSION,
        "authority": request.checkpoint_identity(),
        "model_input": request.model_input.model_dump(mode="json"),
        "status": "PENDING",
        "generation": request.generation,
        "frame_id": request.frame_id,
        "generation_transition_events": [],
        "provider_projection_items": [],
        "canonical_projection_items": [],
    }


def build_parallel_frame_graph(frame_type: ParallelFrameType) -> StateGraph:
    if frame_type not in FRAME_TYPES:
        raise ValueError("unknown parallel Frame type")

    async def authorize_input(
        state: ParallelFrameGraphState,
        runtime: Runtime[ParallelFrameGraphRuntime],
    ) -> dict[str, Any]:
        return _authorize_input(state, runtime.context, frame_type)

    async def invoke_model(
        state: ParallelFrameGraphState,
        runtime: Runtime[ParallelFrameGraphRuntime],
    ) -> dict[str, Any]:
        return await _invoke_frame_model(
            state,
            runtime.context,
            frame_type,
            replacement=False,
        )

    async def emit_generation_transition(
        state: ParallelFrameGraphState,
        runtime: Runtime[ParallelFrameGraphRuntime],
    ) -> dict[str, Any]:
        return await _emit_frame_generation_transition(
            state,
            runtime.context,
            frame_type,
        )

    async def invoke_replacement_model(
        state: ParallelFrameGraphState,
        runtime: Runtime[ParallelFrameGraphRuntime],
    ) -> dict[str, Any]:
        return await _invoke_frame_model(
            state,
            runtime.context,
            frame_type,
            replacement=True,
        )

    def route_after_model(state: ParallelFrameGraphState) -> str:
        status = state.get("status")
        if status not in {"RESET_DETECTED", "MODEL_COMPLETED"}:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_MODEL_CHECKPOINT_INVALID"
            )
        return status

    async def checkpoint_terminal(
        state: ParallelFrameGraphState,
        runtime: Runtime[ParallelFrameGraphRuntime],
    ) -> dict[str, Any]:
        del runtime
        _require_status(state, "MODEL_COMPLETED")
        _require_complete_state(state, frame_type)
        return {"status": "COMPLETE"}

    builder = StateGraph(
        ParallelFrameGraphState,
        context_schema=ParallelFrameGraphRuntime,
    )
    builder.add_node("authorize_input", authorize_input)
    builder.add_node("invoke_model", invoke_model)
    builder.add_node("emit_generation_transition", emit_generation_transition)
    builder.add_node("invoke_replacement_model", invoke_replacement_model)
    builder.add_node("checkpoint_terminal", checkpoint_terminal)
    builder.add_edge(START, "authorize_input")
    builder.add_edge("authorize_input", "invoke_model")
    builder.add_conditional_edges(
        "invoke_model",
        route_after_model,
        {
            "RESET_DETECTED": "emit_generation_transition",
            "MODEL_COMPLETED": "checkpoint_terminal",
        },
    )
    builder.add_edge("emit_generation_transition", "invoke_replacement_model")
    builder.add_edge("invoke_replacement_model", "checkpoint_terminal")
    builder.add_edge("checkpoint_terminal", END)
    return builder


def compile_parallel_frame_graphs(*, checkpointer: Any) -> Mapping[ParallelFrameType, Any]:
    if checkpointer is None:
        raise ValueError("parallel Frame graphs require a checkpointer")
    selected = (
        {frame_type: checkpointer[frame_type] for frame_type in FRAME_TYPES}
        if isinstance(checkpointer, Mapping)
        else {frame_type: checkpointer for frame_type in FRAME_TYPES}
    )
    if any(value is None for value in selected.values()):
        raise ValueError("every parallel Frame graph requires a checkpointer")
    return {
        frame_type: build_parallel_frame_graph(frame_type).compile(
            checkpointer=selected[frame_type]
        )
        for frame_type in FRAME_TYPES
    }


class ParallelIntakeFrameOrchestrator:
    def __init__(self, graphs: Mapping[ParallelFrameType, Any]) -> None:
        if set(graphs) != set(FRAME_TYPES):
            raise ValueError("orchestrator requires exactly three physical Frame graphs")
        self._graphs = dict(graphs)

    async def execute(
        self,
        requests: Sequence[ParallelFrameExecutionRequest],
        *,
        agent_contexts: Mapping[ParallelFrameType, AgentInvocationContext],
        model_runner: ParallelFrameModelRunner,
        event_sink: ParallelFrameTechnicalEventSink,
        checkpoint_configs: Mapping[ParallelFrameType, Mapping[str, Any]] | None = None,
    ) -> ParallelFrameBatchResult:
        if not requests:
            raise ValueError("at least one Frame request is required")
        by_type = {request.frame_type: request for request in requests}
        if len(by_type) != len(requests):
            raise ValueError("a Frame batch cannot repeat a Frame type")
        if set(by_type) - set(FRAME_TYPES):
            raise ValueError("a Frame batch contains an unknown Frame type")
        if set(by_type) - set(agent_contexts):
            raise ValueError("every requested Frame requires an agent context")
        if checkpoint_configs is not None and set(by_type) - set(checkpoint_configs):
            raise ValueError("every requested Frame requires a checkpoint config")
        frame_set_ids = {request.frame_set_id for request in requests}
        run_ids = {request.run_id for request in requests}
        attempt_ids = {request.attempt_id for request in requests}
        context_hashes = {request.context_envelope_sha256 for request in requests}
        model_context_hashes = {
            request.model_input.common_model_context.model_context_view_sha256
            for request in requests
        }
        if any(
            len(values) != 1
            for values in (
                frame_set_ids,
                run_ids,
                attempt_ids,
                context_hashes,
                model_context_hashes,
            )
        ):
            raise ValueError("requested Frames do not share one immutable turn context")

        resolved_checkpoint_configs = {
            frame_type: _validated_graph_config(
                request,
                None
                if checkpoint_configs is None
                else checkpoint_configs[frame_type],
            )
            for frame_type, request in by_type.items()
        }
        checkpoint_locations = {
            _checkpoint_location(config)
            for config in resolved_checkpoint_configs.values()
        }
        if len(checkpoint_locations) != len(resolved_checkpoint_configs):
            raise ValueError("parallel Frames cannot share one checkpoint namespace")

        tasks = {
            frame_type: asyncio.create_task(
                self.execute_frame(
                    request,
                    agent_context=agent_contexts[frame_type],
                    model_runner=model_runner,
                    event_sink=event_sink,
                    checkpoint_config=resolved_checkpoint_configs[frame_type],
                ),
                name=f"intake-parallel-{frame_type.lower()}",
            )
            for frame_type, request in by_type.items()
        }
        completed: dict[ParallelFrameType, ParallelFrameExecutionResult] = {}
        failed: dict[ParallelFrameType, ParallelFrameFailure] = {}
        outcomes = await asyncio.gather(*tasks.values(), return_exceptions=True)
        for frame_type, outcome in zip(tasks, outcomes, strict=True):
            if isinstance(outcome, BaseException):
                failed[frame_type] = ParallelFrameFailure(
                    frame_type=frame_type,
                    error_code=_public_error_code(outcome),
                )
            else:
                completed[frame_type] = outcome
        return ParallelFrameBatchResult(
            completed=dict(completed),
            failed=dict(failed),
        )

    async def execute_frame(
        self,
        request: ParallelFrameExecutionRequest,
        *,
        agent_context: AgentInvocationContext,
        model_runner: ParallelFrameModelRunner,
        event_sink: ParallelFrameTechnicalEventSink,
        checkpoint_config: Mapping[str, Any] | None = None,
    ) -> ParallelFrameExecutionResult:
        graph = self._graphs[request.frame_type]
        config = _validated_graph_config(request, checkpoint_config)
        snapshot = await graph.aget_state(config)
        existing = cast(Mapping[str, Any], snapshot.values or {})
        _require_invocation_authority(request, agent_context)
        if existing:
            replacement_request = _require_checkpoint_authority(existing, request)
            if existing.get("status") == "COMPLETE":
                _require_complete_state(existing, request.frame_type)
                state = dict(existing)
                replayed = True
                await _replay_checkpoint_prefix(
                    request,
                    state,
                    event_sink,
                    replacement_request=replacement_request,
                )
            else:
                _require_resumable_checkpoint(snapshot, existing, request)
                runtime = ParallelFrameGraphRuntime(
                    request=request,
                    agent_context=agent_context,
                    model_runner=model_runner,
                    event_sink=event_sink,
                )
                state = await graph.ainvoke(
                    None,
                    config=config,
                    context=runtime,
                )
                replayed = False
        else:
            if request.resume_local_index != 0:
                raise IntakeGraphContractError(
                    "INTAKE_PARALLEL_FRAME_RESUME_WITHOUT_CHECKPOINT"
                )
            runtime = ParallelFrameGraphRuntime(
                request=request,
                agent_context=agent_context,
                model_runner=model_runner,
                event_sink=event_sink,
            )
            state = await graph.ainvoke(
                new_parallel_frame_state(request),
                config=config,
                context=runtime,
            )
            replayed = False

        terminal_event_emitted = False
        try:
            _require_status(state, "COMPLETE")
            _require_complete_state(state, request.frame_type)
            result = validate_parallel_frame_output(
                request.frame_type,
                cast(Mapping[str, Any], state["canonical_result"]),
            )
            terminal_snapshot = await graph.aget_state(config)
            checkpoint_ref = _checkpoint_ref(
                terminal_snapshot.config,
                request,
                expected_config=config,
            )
            checkpoint_sha256 = canonical_sha256(
                {
                    "checkpoint_ref": checkpoint_ref,
                    "terminal_state": state,
                }
            )
            sealed = _sealed_event(
                request,
                state,
                checkpoint_ref=checkpoint_ref,
                checkpoint_sha256=checkpoint_sha256,
            )
            await event_sink.emit(sealed)
            terminal_event_emitted = True
            return ParallelFrameExecutionResult(
                frame_type=request.frame_type,
                generation=int(state["generation"]),
                frame_id=str(state["frame_id"]),
                result=result,
                result_sha256=str(state["result_sha256"]),
                public_projection_sha256=str(state["public_projection_sha256"]),
                child_checkpoint_ref=checkpoint_ref,
                child_checkpoint_sha256=checkpoint_sha256,
                replayed_from_checkpoint=replayed,
            )
        except BaseException as error:
            if (
                not terminal_event_emitted
                and not isinstance(error, asyncio.CancelledError)
            ):
                try:
                    await event_sink.emit(
                        FrameInterrupted(
                            frame_set_id=request.frame_set_id,
                            run_id=request.run_id,
                            attempt_id=request.attempt_id,
                            frame_type=request.frame_type,
                            generation=int(state.get("generation", request.generation)),
                            frame_id=str(state.get("frame_id", request.frame_id)),
                            error_code=_public_error_code(error),
                            retryable=_is_retryable_frame_failure(error),
                        )
                    )
                except BaseException:
                    pass
            raise


def _authorize_input(
    state: ParallelFrameGraphState,
    runtime: ParallelFrameGraphRuntime,
    expected_frame_type: ParallelFrameType,
) -> dict[str, Any]:
    _require_status(state, "PENDING")
    request = runtime.request
    if request.frame_type != expected_frame_type:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_GRAPH_TYPE_MISMATCH")
    _require_checkpoint_authority(state, request)
    _require_invocation_authority(request, runtime.agent_context)
    return {"status": "AUTHORIZED"}


async def _invoke_frame_model(
    state: ParallelFrameGraphState,
    runtime: ParallelFrameGraphRuntime,
    expected_frame_type: ParallelFrameType,
    *,
    replacement: bool,
) -> dict[str, Any]:
    _require_status(state, "RETRY_AUTHORIZED" if replacement else "AUTHORIZED")
    request = runtime.request
    frame_type = expected_frame_type
    generation = int(state["generation"])
    frame_id = str(state["frame_id"])
    provider_items: list[dict[str, Any]] = []
    canonical_items: list[dict[str, Any]] = []
    pending_projection_error: BaseException | None = None
    generation_transition_events = list(state.get("generation_transition_events") or [])
    prior_usage = (
        FrameProviderUsage.model_validate(state.get("reset_provider_usage"))
        if replacement
        else None
    )
    completed: HarnessStreamCompleted[Any] | None = None
    # The transport service pre-emits only the request generation so that all
    # three initial starts are visible before any provider call.  A lane-local
    # replacement is a new generation and therefore owns a new start even when
    # the initial request start was suppressed inside the child graph.
    if request.emit_start or (replacement and generation != request.generation):
        await runtime.event_sink.emit(
            _frame_started(request, generation=generation, frame_id=frame_id)
        )
    try:
        output_type, public_item_type = _request_bound_frame_types(
            frame_type,
            request.model_input.common_model_context,
        )
        invocation_context = (
            _single_attempt_context(runtime.agent_context)
            if replacement
            else runtime.agent_context
        )
        stream = runtime.model_runner.ainvoke_structured_stream(
            node_name=FRAME_NODE_NAMES[frame_type],
            case_data={
                "room_type": "INTAKE",
                "agent_key": "DISPUTE_INTAKE_OFFICER",
                "frame_type": frame_type,
            },
            output_type=output_type,
            visible_fields=(
                VisibleFieldSpec(
                    "public_projection_items",
                    "public_projection_items",
                    "json_array_items",
                    max_array_items=FRAME_PUBLIC_ITEM_LIMITS[frame_type],
                    max_array_item_bytes=32 * 1024,
                    max_array_bytes=128 * 1024,
                ),
            ),
            context_sections=[
                PromptSection(
                    name=_MODEL_CONTEXT_SECTION_NAME,
                    content=json.dumps(
                        request.model_input.provider_payload(),
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    priority=100,
                    required=True,
                    trust_level="java_filtered_parallel_context",
                    prompt_order=1,
                )
            ],
            max_input_tokens=20_000,
            agent_context=invocation_context,
            prompt_profile_id=request.model_input.instruction_pack.prompt_profile_id,
            semantic_validator=_request_bound_frame_semantic_validator(
                frame_type,
                actor_role=request.actor_role,
                model_context=request.model_input.common_model_context,
            ),
        )
        try:
            async for update in stream:
                if isinstance(update, HarnessStreamDelta):
                    if update.field != "public_projection_items":
                        raise IntakeGraphContractError(
                            "INTAKE_PARALLEL_FRAME_FOREIGN_VISIBLE_FIELD"
                        )
                    # Cross-item rules are not representable in the provider's item-local
                    # JSON Schema. Keep the first violation sticky for this generation.
                    if pending_projection_error is not None:
                        continue
                    try:
                        provider_item = _decode_visible_item(update.delta)
                        item_model = public_item_type.model_validate(provider_item)
                        normalized_provider_item = item_model.model_dump(mode="json")
                        _validate_public_projection_prefix(
                            frame_type,
                            provider_items,
                            normalized_provider_item,
                            actor_role=request.actor_role,
                        )
                        slot_id = _provider_item_identity(
                            frame_type,
                            normalized_provider_item,
                        )
                        if slot_id in {
                            _provider_item_identity(frame_type, item)
                            for item in provider_items
                        }:
                            raise IntakeGraphContractError(
                                "INTAKE_PARALLEL_FRAME_PROJECTION_SLOT_REPEATED"
                            )
                        canonical_item = canonical_parallel_public_projection(
                            frame_type,
                            item_model,
                            actor_role=request.actor_role,
                        )
                    except (IntakeGraphContractError, TypeError, ValueError) as error:
                        pending_projection_error = error
                        continue
                    local_index = len(provider_items)
                    provider_items.append(normalized_provider_item)
                    canonical_items.append(
                        canonical_item.model_dump(mode="json", exclude_none=True)
                    )
                    await runtime.event_sink.emit(
                        _projection_event(
                            request,
                            generation=generation,
                            frame_id=frame_id,
                            local_index=local_index,
                            item=canonical_item,
                        )
                    )
                    continue
                if isinstance(update, HarnessStreamReset):
                    if replacement or not request.allow_generation_reset:
                        raise IntakeGraphContractError(
                            "INTAKE_PARALLEL_FRAME_RETRY_EXHAUSTED"
                        )
                    if update.generation != generation + 1:
                        raise IntakeGraphContractError(
                            "INTAKE_PARALLEL_FRAME_GENERATION_RESET_INVALID"
                        )
                    old_generation = generation
                    old_frame_id = frame_id
                    generation = update.generation
                    frame_id = _replacement_frame_id(
                        request,
                        old_frame_id=old_frame_id,
                        generation=generation,
                    )
                    interrupted = FrameInterrupted(
                        frame_set_id=request.frame_set_id,
                        run_id=request.run_id,
                        attempt_id=request.attempt_id,
                        frame_type=frame_type,
                        generation=old_generation,
                        frame_id=old_frame_id,
                        error_code=update.reason_code,
                        retryable=True,
                    )
                    generation_reset = FrameGenerationReset(
                        frame_set_id=request.frame_set_id,
                        run_id=request.run_id,
                        attempt_id=request.attempt_id,
                        frame_type=frame_type,
                        old_generation=old_generation,
                        new_generation=generation,
                        old_frame_id=old_frame_id,
                        new_frame_id=frame_id,
                        reason_code=update.reason_code,
                    )
                    return {
                        "status": "RESET_DETECTED",
                        "generation": generation,
                        "frame_id": frame_id,
                        "generation_transition_events": [
                            interrupted.model_dump(mode="json"),
                            generation_reset.model_dump(mode="json"),
                        ],
                        "reset_provider_usage": _reset_provider_usage(update).model_dump(
                            mode="json"
                        ),
                        "provider_projection_items": [],
                        "canonical_projection_items": [],
                    }
                if isinstance(update, HarnessStreamCompleted):
                    if completed is not None:
                        raise IntakeGraphContractError(
                            "INTAKE_PARALLEL_FRAME_MULTIPLE_COMPLETIONS"
                        )
                    if pending_projection_error is not None:
                        raise pending_projection_error
                    completed = update
                    continue
                raise IntakeGraphContractError(
                    "INTAKE_PARALLEL_FRAME_STREAM_EVENT_INVALID"
                )
        finally:
            close = getattr(stream, "aclose", None)
            if callable(close):
                await close()
        if completed is None:
            raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_COMPLETION_MISSING")
        final = materialize_request_bound_frame_output(
            frame_type,
            completed.generation.value,
            persisted_phase=(
                request.model_input.common_model_context.previous_state.persisted_phase
            ),
            respondent_capacity=(
                request.model_input.common_model_context.source_capacity.litigation_capacity
                == "RESPONDENT"
            ),
        )
        final_payload = final.model_dump(mode="json")
        if final_payload["public_projection_items"] != provider_items:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_VISIBLE_PREFIX_DIVERGED"
            )
        canonical_result_json = canonicalize(final_payload).decode("utf-8")
        result_sha256 = canonical_sha256(final_payload)
        public_projection_sha256 = canonical_sha256(canonical_items)
        usage = _provider_usage(completed, prior_usage=prior_usage)
        completed_at = datetime.now(timezone.utc).isoformat()
        return {
            "status": "MODEL_COMPLETED",
            "generation": generation,
            "frame_id": frame_id,
            "generation_transition_events": generation_transition_events,
            "provider_projection_items": provider_items,
            "canonical_projection_items": canonical_items,
            "canonical_result": final_payload,
            "canonical_result_json": canonical_result_json,
            "result_sha256": result_sha256,
            "public_projection_sha256": public_projection_sha256,
            "usage": usage.model_dump(mode="json"),
            "completed_at": completed_at,
        }
    except BaseException as error:
        if not isinstance(error, asyncio.CancelledError):
            try:
                await runtime.event_sink.emit(
                    FrameInterrupted(
                        frame_set_id=request.frame_set_id,
                        run_id=request.run_id,
                        attempt_id=request.attempt_id,
                        frame_type=frame_type,
                        generation=generation,
                        frame_id=frame_id,
                        error_code=_public_error_code(error),
                        retryable=_is_retryable_frame_failure(error),
                    )
                )
            except BaseException:
                pass
        raise


async def _emit_frame_generation_transition(
    state: ParallelFrameGraphState,
    runtime: ParallelFrameGraphRuntime,
    expected_frame_type: ParallelFrameType,
) -> dict[str, Any]:
    _require_status(state, "RESET_DETECTED")
    if runtime.request.frame_type != expected_frame_type:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_GRAPH_TYPE_MISMATCH")
    _require_checkpoint_authority(state, runtime.request)
    transitions = _validated_generation_transition_events(state, expected_frame_type)
    if len(transitions) != 2:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_GENERATION_RESET_INVALID"
        )
    FrameProviderUsage.model_validate(state.get("reset_provider_usage"))
    for event in transitions:
        await runtime.event_sink.emit(event)
    return {"status": "RETRY_AUTHORIZED"}


async def _replay_checkpoint_prefix(
    request: ParallelFrameExecutionRequest,
    state: Mapping[str, Any],
    event_sink: ParallelFrameTechnicalEventSink,
    *,
    replacement_request: bool,
) -> None:
    canonical_items = list(state["canonical_projection_items"])
    generation = int(state["generation"])
    frame_id = str(state["frame_id"])
    transitions = _validated_generation_transition_events(state, request.frame_type)
    resume_generation, resume_frame_id = request.resume_position()
    authority = cast(Mapping[str, Any], state["authority"])
    initial_position = (int(authority["generation"]), str(authority["frame_id"]))
    terminal_position = (generation, frame_id)
    if replacement_request:
        if (resume_generation, resume_frame_id) != terminal_position or not transitions:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_RESUME_GENERATION_OUT_OF_RANGE"
            )
        if request.emit_start:
            await event_sink.emit(
                _frame_started(request, generation=generation, frame_id=frame_id)
            )
        resume_local_index = request.resume_local_index
    elif (resume_generation, resume_frame_id) == initial_position:
        if request.emit_start:
            await event_sink.emit(
                _frame_started(
                    request,
                    generation=request.generation,
                    frame_id=request.frame_id,
                )
            )
        for event in transitions:
            await event_sink.emit(event)
        if transitions:
            await event_sink.emit(
                _frame_started(request, generation=generation, frame_id=frame_id)
            )
        resume_local_index = 0 if transitions else request.resume_local_index
    elif (resume_generation, resume_frame_id) == terminal_position:
        if request.emit_start:
            await event_sink.emit(
                _frame_started(request, generation=generation, frame_id=frame_id)
            )
        resume_local_index = request.resume_local_index
    else:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_RESUME_GENERATION_OUT_OF_RANGE"
        )
    if resume_local_index > len(canonical_items):
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_RESUME_INDEX_OUT_OF_RANGE"
        )
    for local_index, raw_item in enumerate(
        canonical_items[resume_local_index:],
        start=resume_local_index,
    ):
        item = CanonicalPublicProjectionItem.model_validate(raw_item)
        await event_sink.emit(
            _projection_event(
                request,
                generation=generation,
                frame_id=frame_id,
                local_index=local_index,
                item=item,
            )
        )


def _frame_started(
    request: ParallelFrameExecutionRequest,
    *,
    generation: int,
    frame_id: str,
) -> FrameStarted:
    return FrameStarted(
        frame_set_id=request.frame_set_id,
        run_id=request.run_id,
        attempt_id=request.attempt_id,
        frame_type=request.frame_type,
        generation=generation,
        frame_id=frame_id,
        frame_model_input_sha256=request.model_input.frame_model_input_sha256,
        frame_prompt_sha256=request.model_input.instruction_pack.frame_prompt_sha256,
        context_envelope_sha256=request.context_envelope_sha256,
        model_context_view_sha256=(
            request.model_input.common_model_context.model_context_view_sha256
        ),
    )


def _projection_event(
    request: ParallelFrameExecutionRequest,
    *,
    generation: int,
    frame_id: str,
    local_index: int,
    item: CanonicalPublicProjectionItem,
) -> FrameProjectionItem:
    return FrameProjectionItem(
        frame_set_id=request.frame_set_id,
        run_id=request.run_id,
        attempt_id=request.attempt_id,
        frame_type=request.frame_type,
        generation=generation,
        frame_id=frame_id,
        local_index=local_index,
        next_local_index=local_index + 1,
        item=item,
        item_sha256=item.item_sha256,
    )


def _sealed_event(
    request: ParallelFrameExecutionRequest,
    state: Mapping[str, Any],
    *,
    checkpoint_ref: str,
    checkpoint_sha256: str,
) -> FrameSealed:
    completed_at = datetime.fromisoformat(str(state["completed_at"]))
    return FrameSealed(
        frame_set_id=request.frame_set_id,
        run_id=request.run_id,
        attempt_id=request.attempt_id,
        frame_type=request.frame_type,
        generation=int(state["generation"]),
        frame_id=str(state["frame_id"]),
        child_checkpoint_ref=checkpoint_ref,
        child_checkpoint_sha256=checkpoint_sha256,
        context_envelope_sha256=request.context_envelope_sha256,
        model_context_view_sha256=(
            request.model_input.common_model_context.model_context_view_sha256
        ),
        canonical_result_json=str(state["canonical_result_json"]),
        result_sha256=str(state["result_sha256"]),
        public_projection_sha256=str(state["public_projection_sha256"]),
        next_local_index=len(state["canonical_projection_items"]),
        usage=FrameProviderUsage.model_validate(state["usage"]),
        completed_at=completed_at,
        occurred_at=completed_at,
    )


def canonical_parallel_public_projection(
    frame_type: ParallelFrameType,
    item: BaseModel,
    *,
    actor_role: PartyRole,
) -> CanonicalPublicProjectionItem:
    # Request-bound item classes are intentionally distinct Pydantic types.
    # Normalize their trusted values before validating against the stable base
    # contract; passing one RootModel subclass to another is not a value cast.
    payload = item.model_dump(mode="json")
    if frame_type == "DIALOGUE_FRAME":
        dialogue = DialoguePublicSegmentDraftV3.model_validate(payload)
        return CanonicalPublicProjectionItem(
            # Dialogue v3 is intentionally a single-item Provider contract.
            # The slot is protocol authority, not model semantics.
            canonical_item_id="DSEG_01",
            projection_kind=dialogue.segment_kind,
            projection_path_id="intake.dialogue.public_segments",
            value_kind="TEXT",
            public_text=dialogue.candidate_text,
        )
    if frame_type == "DOSSIER_FRAME":
        dossier = DossierPublicFactDraftV3.model_validate(payload)
        return CanonicalPublicProjectionItem(
            canonical_item_id=dossier.source_row.fact_key,
            projection_kind="CURRENT_FACT",
            projection_path_id="case_story.one_sentence_summary",
            value_kind="JSON_VALUE",
            canonical_value=dossier.source_row.position_summary,
        )
    quality = QualityPublicProjectionDraftV2.model_validate(payload).root
    if isinstance(quality, QualityPublicGapDraftV2):
        return CanonicalPublicProjectionItem(
            canonical_item_id=f"QGAP_{quality.dimension}",
            projection_kind=quality.projection_kind,
            projection_path_id=f"intake.quality.gaps.{quality.dimension.lower()}",
            value_kind="JSON_VALUE",
            canonical_value={
                "dimension": quality.dimension,
                "question": quality.question,
                "source_role": actor_role,
                "linked_fact_keys": list(quality.linked_fact_keys),
            },
        )
    if not isinstance(quality, QualityPublicMetricDraftV2):
        raise ParallelFrameStreamProtocolError(
            "Quality public projection type is invalid"
        )
    return CanonicalPublicProjectionItem(
        canonical_item_id=f"QSCORE_{quality.dimension}",
        projection_kind=quality.projection_kind,
        projection_path_id=f"intake.quality.scores.{quality.dimension.lower()}",
        value_kind="JSON_VALUE",
        canonical_value=quality.candidate_score,
    )


def _validate_public_projection_prefix(
    frame_type: ParallelFrameType,
    previous_items: list[dict[str, Any]],
    current_item: dict[str, Any],
    *,
    actor_role: PartyRole,
) -> None:
    if frame_type == "QUALITY_FRAME":
        items = (*previous_items, current_item)
        if len(items) > FRAME_PUBLIC_ITEM_LIMITS[frame_type]:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_PUBLIC_ITEM_LIMIT"
            )
        index = len(items) - 1
        if index < len(QUALITY_DIMENSION_ORDER):
            if (
                current_item.get("projection_kind") != "DIMENSION_SCORE"
                or current_item.get("dimension") != QUALITY_DIMENSION_ORDER[index]
            ):
                raise IntakeGraphContractError(
                    "INTAKE_PARALLEL_QUALITY_SCORE_ORDER_INVALID"
                )
            return
        if current_item.get("projection_kind") != "BLOCKING_GAP":
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_QUALITY_GAP_ORDER_INVALID"
            )
        dimension = str(current_item.get("dimension", ""))
        gap_dimensions = {
            str(item.get("dimension", ""))
            for item in previous_items[len(QUALITY_DIMENSION_ORDER) :]
        }
        if dimension in gap_dimensions:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_QUALITY_GAP_REPEATED"
            )
        score_by_dimension = {
            str(item.get("dimension", "")): item.get("candidate_score")
            for item in previous_items[: len(QUALITY_DIMENSION_ORDER)]
        }
        if score_by_dimension.get(dimension) == QUALITY_DIMENSION_MAXIMA.get(
            dimension
        ):
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_QUALITY_FULL_SCORE_GAP"
            )
        return
    if frame_type != "DOSSIER_FRAME":
        return
    all_items = (*previous_items, current_item)
    fact_keys = tuple(str(item["source_row"]["fact_key"]) for item in all_items)
    if len(fact_keys) != len(set(fact_keys)):
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_DOSSIER_FACT_KEY_REPEATED"
        )
    candidates = tuple(
        str(item["source_row"]["position_summary"])
        for item in all_items
    )
    if len(candidates) > FRAME_PUBLIC_ITEM_LIMITS[frame_type]:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_PUBLIC_ITEM_LIMIT")
    if len("；".join(candidates)) > 20_000:
        raise IntakeGraphContractError("INTAKE_PARALLEL_DOSSIER_SUMMARY_LIMIT")


def _request_bound_frame_semantic_validator(
    frame_type: ParallelFrameType,
    *,
    actor_role: PartyRole,
    model_context: IntakeModelContextViewV1,
) -> Callable[[Any], Any]:
    """Attach request authority without changing the provider-visible JSON Schema."""

    def validate(value: Any) -> Any:
        frame = materialize_request_bound_frame_output(
            frame_type,
            value,
            persisted_phase=model_context.previous_state.persisted_phase,
            respondent_capacity=(
                model_context.source_capacity.litigation_capacity == "RESPONDENT"
            ),
        )
        if isinstance(frame, IntakeDialogueFrameV3):
            phase = model_context.previous_state.persisted_phase
            disposition = frame.dialogue.remark_disposition
            if (phase == "WAITING_FOR_REMARK") != (disposition is not None):
                raise ValueError(
                    "Dialogue remark disposition does not match the persisted phase"
                )
        elif isinstance(frame, IntakeQualityFrameV2):
            allowed_fact_keys = set(
                model_context.fact_key_authority.existing_fact_keys
            )
            gap_fact_keys = {
                fact_key
                for wrapped in frame.public_projection_items[
                    len(QUALITY_DIMENSION_ORDER) :
                ]
                if isinstance(wrapped.root, QualityPublicGapDraftV2)
                for fact_key in wrapped.root.linked_fact_keys
            }
            if not gap_fact_keys.issubset(allowed_fact_keys):
                raise ValueError(
                    "Quality gap references a fact outside the frozen matrix authority"
                )
        elif isinstance(frame, IntakeDossierFrameV3):
            # This is an aggregate persisted-field bound and is intentionally not
            # weakened to the per-item string limit exposed in JSON Schema.
            frame.materialized_dossier_patch()
            _validate_dossier_frame_authority(frame, model_context)
        return value

    return validate


def _request_bound_frame_types(
    frame_type: ParallelFrameType,
    model_context: IntakeModelContextViewV1,
) -> tuple[type[BaseModel], type[BaseModel]]:
    if frame_type == "DIALOGUE_FRAME":
        return request_bound_dialogue_output_types(
            persisted_phase=model_context.previous_state.persisted_phase,
        )
    if frame_type == "QUALITY_FRAME":
        return request_bound_quality_output_types(
            existing_fact_keys=model_context.fact_key_authority.existing_fact_keys,
        )
    if frame_type != "DOSSIER_FRAME":
        return FRAME_OUTPUT_MODELS[frame_type], FRAME_PUBLIC_ITEM_MODELS[frame_type]
    output_type, item_type = request_bound_dossier_output_types(
        existing_fact_keys=model_context.fact_key_authority.existing_fact_keys,
        new_fact_key_prefix=model_context.fact_key_authority.new_fact_key_prefix,
        respondent_capacity=(
            model_context.source_capacity.litigation_capacity == "RESPONDENT"
        ),
    )
    return output_type, item_type


def _validate_dossier_frame_authority(
    frame: IntakeDossierFrameV3,
    model_context: IntakeModelContextViewV1,
) -> None:
    if (
        frame.dossier_delta.respondent_claim is not None
        and model_context.source_capacity.litigation_capacity != "RESPONDENT"
    ):
        raise ValueError("respondent_claim requires authenticated respondent capacity")

    frozen_rows = model_context.frozen_case_matrix.payload.get("fact_rows")
    if not isinstance(frozen_rows, list):
        raise ValueError("frozen matrix fact rows are absent")
    existing: dict[str, Mapping[str, Any]] = {}
    for candidate in frozen_rows:
        if not isinstance(candidate, Mapping):
            raise ValueError("frozen matrix fact row is invalid")
        fact_id = candidate.get("fact_id")
        if not isinstance(fact_id, str) or fact_id in existing:
            raise ValueError("frozen matrix fact authority is invalid")
        existing[fact_id] = candidate

    prefix = model_context.fact_key_authority.new_fact_key_prefix
    for proposal in frame.public_projection_items:
        row = proposal.source_row
        if row.fact_key.startswith("FACT_"):
            prior = existing.get(row.fact_key)
            if prior is None:
                raise ValueError("Dossier fact references an unknown formal FACT_ key")
            if (
                prior.get("category") != row.category
                or prior.get("fact_target") != row.fact_target
                or prior.get("materiality") != row.materiality
            ):
                raise ValueError("Dossier fact changes a frozen formal binding")
        elif row.fact_key.startswith(prefix):
            pass
        else:
            raise ValueError("Dossier NEW_ fact is outside the issued namespace")


def _provider_item_identity(
    frame_type: ParallelFrameType,
    item: Mapping[str, Any],
) -> str:
    if frame_type == "DOSSIER_FRAME":
        source_row = item.get("source_row")
        if not isinstance(source_row, Mapping):
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_DOSSIER_SOURCE_ROW_INVALID"
            )
        return str(source_row.get("fact_key", ""))
    if frame_type == "DIALOGUE_FRAME":
        return "DSEG_01"
    dimension = str(item.get("dimension", ""))
    kind = str(item.get("projection_kind", ""))
    if not dimension or kind not in {"DIMENSION_SCORE", "BLOCKING_GAP"}:
        return ""
    return ("QSCORE_" if kind == "DIMENSION_SCORE" else "QGAP_") + dimension


def _provider_usage(
    completed: HarnessStreamCompleted[Any],
    *,
    prior_usage: FrameProviderUsage | None,
) -> FrameProviderUsage:
    raw = completed.generation.token_usage
    try:
        input_tokens = int(raw["input"])
        output_tokens = int(raw["output"])
        total_tokens = int(raw["total"])
    except (KeyError, TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_USAGE_INVALID"
        ) from error
    current = FrameProviderUsage(
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        total_tokens=total_tokens,
        latency_ms=int(completed.generation.latency_ms),
        provider_call_count=1,
        model=completed.generation.model,
    )
    if prior_usage is None:
        return current
    if prior_usage.provider_call_count != 1 or prior_usage.model != current.model:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_USAGE_INVALID")
    return FrameProviderUsage(
        input_tokens=prior_usage.input_tokens + current.input_tokens,
        output_tokens=prior_usage.output_tokens + current.output_tokens,
        total_tokens=prior_usage.total_tokens + current.total_tokens,
        latency_ms=prior_usage.latency_ms + current.latency_ms,
        provider_call_count=2,
        model=current.model,
    )


def _reset_provider_usage(update: HarnessStreamReset) -> FrameProviderUsage:
    raw = update.failed_token_usage
    try:
        input_tokens = int(raw["input"])
        output_tokens = int(raw["output"])
        total_tokens = int(raw["total"])
    except (KeyError, TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_RESET_USAGE_INVALID"
        ) from error
    try:
        usage = FrameProviderUsage(
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            total_tokens=total_tokens,
            latency_ms=int(update.failed_latency_ms),
            provider_call_count=1,
            model=update.failed_model,
        )
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_RESET_USAGE_INVALID"
        ) from error
    if usage.total_tokens != usage.input_tokens + usage.output_tokens:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_RESET_USAGE_INVALID")
    return usage


def _single_attempt_context(context: AgentInvocationContext) -> AgentInvocationContext:
    budget = context.retry_budget
    if budget is None or budget.provider_attempts_remaining < 1:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_REPLACEMENT_BUDGET_MISSING"
        )
    return context.model_copy(
        update={
            "retry_budget": budget.model_copy(
                update={"provider_attempts_remaining": 1}
            )
        }
    )


def _decode_visible_item(delta: str) -> dict[str, Any]:
    try:
        value = json.loads(delta, object_pairs_hook=_unique_json_object)
    except (TypeError, json.JSONDecodeError, ValueError) as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_VISIBLE_ITEM_INVALID"
        ) from error
    if not isinstance(value, dict):
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_VISIBLE_ITEM_INVALID")
    return value


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = member
    return value


def _replacement_frame_id(
    request: ParallelFrameExecutionRequest,
    *,
    old_frame_id: str,
    generation: int,
) -> str:
    digest = canonical_sha256(
        {
            "frame_set_id": request.frame_set_id,
            "frame_type": request.frame_type,
            "old_frame_id": old_frame_id,
            "generation": generation,
            "frame_model_input_sha256": request.model_input.frame_model_input_sha256,
        }
    )
    return f"intake.frame.{digest[:32]}"


def _graph_config(request: ParallelFrameExecutionRequest) -> dict[str, Any]:
    digest = canonical_sha256(request.checkpoint_identity())
    return {
        "configurable": {
            "thread_id": f"intake.parallel.{digest[:32]}",
        }
    }


def _validated_graph_config(
    request: ParallelFrameExecutionRequest,
    config: Mapping[str, Any] | None,
) -> dict[str, Any]:
    selected = _graph_config(request) if config is None else dict(config)
    configurable = dict(selected.get("configurable") or {})
    thread_id = configurable.get("thread_id")
    checkpoint_ns = configurable.get("checkpoint_ns", "")
    checkpoint_id = configurable.get("checkpoint_id")
    if (
        not isinstance(thread_id, str)
        or not thread_id
        or len(thread_id) > 128
        or checkpoint_ns not in {None, ""}
        or (
            checkpoint_id is not None
            and (
                not isinstance(checkpoint_id, str)
                or not checkpoint_id
                or len(checkpoint_id) > 128
            )
        )
    ):
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_CONFIG_INVALID")
    selected["configurable"] = configurable
    return selected


def _checkpoint_location(config: Mapping[str, Any]) -> tuple[str, str]:
    configurable = dict(config.get("configurable") or {})
    return (
        str(configurable.get("thread_id") or ""),
        _logical_checkpoint_namespace(config),
    )


def _logical_checkpoint_namespace(config: Mapping[str, Any]) -> str:
    configurable = dict(config.get("configurable") or {})
    binding = configurable.get(TECHNICAL_CHILD_CHECKPOINT_CONTEXT_KEY)
    if binding is None:
        return str(configurable.get("checkpoint_ns") or "")
    if not isinstance(binding, TechnicalChildCheckpointBinding):
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_CONFIG_INVALID"
        )
    return binding.checkpoint_ns


def _checkpoint_ref(
    config: Mapping[str, Any] | None,
    request: ParallelFrameExecutionRequest,
    *,
    expected_config: Mapping[str, Any],
) -> str:
    configurable = dict((config or {}).get("configurable", {}))
    checkpoint_id = configurable.get("checkpoint_id")
    thread_id = configurable.get("thread_id")
    checkpoint_ns = configurable.get("checkpoint_ns", "")
    if not all(
        isinstance(value, str) and value
        for value in (
            checkpoint_id,
            thread_id,
        )
    ) or checkpoint_ns not in {None, ""}:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_PROOF_MISSING")
    expected = _validated_graph_config(request, expected_config)["configurable"]
    if (
        thread_id != expected["thread_id"]
        or (checkpoint_ns or "") != (expected.get("checkpoint_ns") or "")
    ):
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_PROOF_DRIFT")
    logical_namespace = _logical_checkpoint_namespace(expected_config)
    if not logical_namespace:
        logical_namespace = f"intake.parallel.{request.frame_type.lower()}"
    return (
        f"langgraph://{logical_namespace}/{quote(thread_id, safe='')}"
        f"/{quote(checkpoint_ns, safe='')}/{quote(checkpoint_id, safe='')}"
    )


def _require_checkpoint_authority(
    state: Mapping[str, Any],
    request: ParallelFrameExecutionRequest,
) -> bool:
    checkpoint_schema_version = state.get("checkpoint_schema_version")
    if checkpoint_schema_version not in {
        _CHECKPOINT_SCHEMA_VERSION,
        _LEGACY_CHECKPOINT_SCHEMA_VERSION,
    }:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_SCHEMA_INVALID")
    if (
        checkpoint_schema_version == _LEGACY_CHECKPOINT_SCHEMA_VERSION
        and state.get("status") != "COMPLETE"
    ):
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_INCOMPLETE")
    raw_model_input = state.get("model_input")
    try:
        persisted_model_input = IntakeFrameModelInputV2.model_validate(raw_model_input)
    except Exception as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_MODEL_INPUT_INVALID"
        ) from error
    if persisted_model_input != request.model_input:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_MODEL_INPUT_DRIFT"
        )
    raw_authority = state.get("authority")
    if not isinstance(raw_authority, Mapping):
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_AUTHORITY_DRIFT"
        )
    authority = dict(raw_authority)
    request_authority = request.checkpoint_identity()
    if authority == request_authority:
        return False
    try:
        transitions = _validated_generation_transition_events(state, request.frame_type)
    except Exception as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_AUTHORITY_DRIFT"
        ) from error
    if len(transitions) != 2:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_AUTHORITY_DRIFT"
        )
    generation_reset = transitions[1]
    expected_successor = dict(authority)
    expected_successor.update(
        {
            "generation": generation_reset.new_generation,
            "frame_id": generation_reset.new_frame_id,
            "allow_generation_reset": False,
        }
    )
    if (
        request_authority != expected_successor
        or request.generation != generation_reset.new_generation
        or request.frame_id != generation_reset.new_frame_id
        or request.allow_generation_reset
    ):
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_AUTHORITY_DRIFT"
        )
    return True


def _require_invocation_authority(
    request: ParallelFrameExecutionRequest,
    context: AgentInvocationContext,
) -> None:
    instruction = request.model_input.instruction_pack
    if (
        context.room_type != "INTAKE"
        or context.case_id != request.case_id
        or context.actor_id != request.actor_id
        or context.actor_role != request.actor_role
        or context.actor_role not in {"USER", "MERCHANT"}
        or context.prompt_profile_id != FRAME_PROMPT_PROFILE[request.frame_type]
        or context.prompt_profile_id != instruction.prompt_profile_id
        or context.output_schema_version != FRAME_OUTPUT_SCHEMA[request.frame_type]
        or context.output_schema_version != instruction.output_schema_id
    ):
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_INVOCATION_UNAUTHORIZED")


def _require_resumable_checkpoint(
    snapshot: Any,
    state: Mapping[str, Any],
    request: ParallelFrameExecutionRequest,
) -> None:
    status = state.get("status")
    expected_next = {
        "PENDING": ("authorize_input",),
        "AUTHORIZED": ("invoke_model",),
        "RESET_DETECTED": ("emit_generation_transition",),
        "RETRY_AUTHORIZED": ("invoke_replacement_model",),
        "MODEL_COMPLETED": ("checkpoint_terminal",),
    }.get(status)
    actual_next = tuple(getattr(snapshot, "next", ()) or ())
    if (
        expected_next is None
        or actual_next != expected_next
        or request.resume_local_index != 0
    ):
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_INCOMPLETE"
        )
    if status in {"RESET_DETECTED", "RETRY_AUTHORIZED"}:
        try:
            transitions = _validated_generation_transition_events(
                state, request.frame_type
            )
            reset_usage = FrameProviderUsage.model_validate(
                state.get("reset_provider_usage")
            )
        except Exception as error:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_CHECKPOINT_INCOMPLETE"
            ) from error
        if len(transitions) != 2 or reset_usage.provider_call_count != 1:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_CHECKPOINT_INCOMPLETE"
            )
    elif status == "MODEL_COMPLETED":
        _require_complete_state(state, request.frame_type)


def _require_complete_state(
    state: Mapping[str, Any],
    frame_type: ParallelFrameType,
) -> None:
    try:
        checkpoint_schema_version = state.get("checkpoint_schema_version")
        if checkpoint_schema_version == _LEGACY_CHECKPOINT_SCHEMA_VERSION:
            if state.get("status") != "COMPLETE":
                raise ValueError("legacy checkpoint is not terminal")
        elif checkpoint_schema_version != _CHECKPOINT_SCHEMA_VERSION:
            raise ValueError("checkpoint schema is invalid")
        result = validate_parallel_frame_output(
            frame_type,
            cast(Mapping[str, Any], state["canonical_result"]),
        )
        canonical_result = result.model_dump(mode="json")
        canonical_items = [
            CanonicalPublicProjectionItem.model_validate(item).model_dump(
                mode="json", exclude_none=True
            )
            for item in state["canonical_projection_items"]
        ]
        if canonical_sha256(canonical_result) != state["result_sha256"]:
            raise ValueError("result hash drift")
        if canonical_sha256(canonical_items) != state["public_projection_sha256"]:
            raise ValueError("public projection hash drift")
        if len(canonical_items) != len(canonical_result["public_projection_items"]):
            raise ValueError("projection cardinality drift")
        usage = FrameProviderUsage.model_validate(state["usage"])
        datetime.fromisoformat(str(state["completed_at"]))
        transitions = _validated_generation_transition_events(state, frame_type)
        if bool(transitions) != (usage.provider_call_count == 2):
            raise ValueError("provider usage disagrees with generation lineage")
        if transitions:
            if checkpoint_schema_version == _LEGACY_CHECKPOINT_SCHEMA_VERSION:
                if state.get("reset_provider_usage") is not None:
                    raise ValueError("legacy checkpoint carries a v2 usage split")
            else:
                reset_usage = FrameProviderUsage.model_validate(
                    state.get("reset_provider_usage")
                )
                if (
                    reset_usage.provider_call_count != 1
                    or reset_usage.model != usage.model
                ):
                    raise ValueError("reset provider usage drifted")
        elif state.get("reset_provider_usage") is not None:
            raise ValueError("reset provider usage exists without a reset")
    except Exception as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_TERMINAL_INVALID"
        ) from error


def _validated_generation_transition_events(
    state: Mapping[str, Any],
    frame_type: ParallelFrameType,
) -> tuple[FrameInterrupted, FrameGenerationReset] | tuple[()]:
    authority = state.get("authority")
    if not isinstance(authority, Mapping):
        raise ValueError("Frame checkpoint authority is absent")
    initial_generation = int(authority["generation"])
    initial_frame_id = str(authority["frame_id"])
    terminal_generation = int(state["generation"])
    terminal_frame_id = str(state["frame_id"])
    raw_events = state.get("generation_transition_events")
    if not isinstance(raw_events, list):
        raise ValueError("Frame generation transition proof is absent")
    if terminal_generation == initial_generation:
        if raw_events or terminal_frame_id != initial_frame_id:
            raise ValueError("Frame generation changed without a transition proof")
        return ()
    if terminal_generation != initial_generation + 1 or len(raw_events) != 2:
        raise ValueError("Frame generation transition proof is incomplete")
    interrupted = FrameInterrupted.model_validate(raw_events[0])
    generation_reset = FrameGenerationReset.model_validate(raw_events[1])
    common_authority = (
        str(authority["frame_set_id"]),
        str(authority["run_id"]),
        str(authority["attempt_id"]),
        frame_type,
    )
    if (
        (
            interrupted.frame_set_id,
            interrupted.run_id,
            interrupted.attempt_id,
            interrupted.frame_type,
        )
        != common_authority
        or (
            generation_reset.frame_set_id,
            generation_reset.run_id,
            generation_reset.attempt_id,
            generation_reset.frame_type,
        )
        != common_authority
        or interrupted.generation != initial_generation
        or interrupted.frame_id != initial_frame_id
        or not interrupted.retryable
        or interrupted.error_code != generation_reset.reason_code
        or generation_reset.old_generation != initial_generation
        or generation_reset.new_generation != terminal_generation
        or generation_reset.old_frame_id != initial_frame_id
        or generation_reset.new_frame_id != terminal_frame_id
    ):
        raise ValueError("Frame generation transition proof drifted")
    return interrupted, generation_reset


def _require_status(state: Mapping[str, Any], expected: str) -> None:
    if state.get("status") != expected:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_STATE_TRANSITION_INVALID")


def _public_error_code(error: BaseException) -> str:
    if isinstance(error, IntakeGraphContractError):
        return error.code
    if (
        type(error) is ModelStreamInterrupted
        and error.retryable is True
        and error.safe_code == "MODEL_PROVIDER_STREAM_INTERRUPTED"
    ):
        return "GRAPH_PROVIDER_STREAM_INTERRUPTED"
    return "INTAKE_PARALLEL_FRAME_EXECUTION_FAILED"


def _is_retryable_frame_failure(error: BaseException) -> bool:
    """Grant out-of-band lane retry only to the reviewed transient provider failure.

    Schema repair is already represented inside one invocation as the explicit
    interrupted -> generation-reset -> replacement-start sequence.  Every other
    exception escaping the Frame graph is deterministic or unclassified and must
    fail closed instead of authorizing Java to replay provider work.
    """

    return (
        type(error) is ModelStreamInterrupted
        and error.retryable is True
        and error.safe_code == "MODEL_PROVIDER_STREAM_INTERRUPTED"
    )


__all__ = [
    "CanonicalPublicProjectionItem",
    "FrameGenerationReset",
    "FrameInterrupted",
    "FrameProjectionItem",
    "FrameProviderUsage",
    "FrameSealed",
    "FrameStarted",
    "ParallelFrameBatchResult",
    "ParallelFrameExecutionRequest",
    "ParallelFrameExecutionResult",
    "ParallelFrameFailure",
    "ParallelFrameGraphRuntime",
    "ParallelFrameTechnicalEvent",
    "ParallelIntakeFrameOrchestrator",
    "build_parallel_frame_graph",
    "canonical_parallel_public_projection",
    "compile_parallel_frame_graphs",
    "new_parallel_frame_state",
]
