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
    IntakeFrameModelInputV1,
    ParallelFrameType,
    PartyRole,
    Sha256,
)
from app.graphs.intake.parallel_outputs import (
    FRAME_OUTPUT_MODELS,
    DialoguePublicSegmentProposalV1,
    DossierPublicPatchProposalV1,
    ParallelFrameOutput,
    QualityPublicMetricProposalV1,
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
    "DIALOGUE_FRAME": 4,
    "DOSSIER_FRAME": 32,
    "QUALITY_FRAME": 6,
}

FRAME_PUBLIC_ITEM_MODELS: Mapping[ParallelFrameType, type[BaseModel]] = {
    "DIALOGUE_FRAME": DialoguePublicSegmentProposalV1,
    "DOSSIER_FRAME": DossierPublicPatchProposalV1,
    "QUALITY_FRAME": QualityPublicMetricProposalV1,
}

_MODEL_CONTEXT_SECTION_NAME = "parallel_frame_model_input"
_CHECKPOINT_SCHEMA_VERSION = "intake.parallel-frame-checkpoint.v1"
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
    model_input: IntakeFrameModelInputV1
    resume_generation: int | None = Field(default=None, ge=1)
    resume_frame_id: Identifier | None = None
    resume_local_index: int = Field(default=0, ge=0)
    emit_start: bool = True

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
        return await _invoke_frame_model(state, runtime.context, frame_type)

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
    builder.add_node("checkpoint_terminal", checkpoint_terminal)
    builder.add_edge(START, "authorize_input")
    builder.add_edge("authorize_input", "invoke_model")
    builder.add_edge("invoke_model", "checkpoint_terminal")
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
        if existing:
            if existing.get("status") != "COMPLETE":
                raise IntakeGraphContractError(
                    "INTAKE_PARALLEL_FRAME_CHECKPOINT_INCOMPLETE"
                )
            _require_checkpoint_authority(existing, request)
            _require_complete_state(existing, request.frame_type)
            state = dict(existing)
            replayed = True
            await _replay_checkpoint_prefix(request, state, event_sink)
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

        _require_status(state, "COMPLETE")
        _require_complete_state(state, request.frame_type)
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
        result = validate_parallel_frame_output(
            request.frame_type,
            cast(Mapping[str, Any], state["canonical_result"]),
        )
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
    context = runtime.agent_context
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
    return {"status": "AUTHORIZED"}


async def _invoke_frame_model(
    state: ParallelFrameGraphState,
    runtime: ParallelFrameGraphRuntime,
    expected_frame_type: ParallelFrameType,
) -> dict[str, Any]:
    _require_status(state, "AUTHORIZED")
    request = runtime.request
    frame_type = expected_frame_type
    generation = int(state["generation"])
    frame_id = str(state["frame_id"])
    provider_items: list[dict[str, Any]] = []
    canonical_items: list[dict[str, Any]] = []
    reset_count = 0
    generation_transition_events: list[dict[str, Any]] = []
    completed: HarnessStreamCompleted[Any] | None = None
    if request.emit_start:
        await runtime.event_sink.emit(
            _frame_started(request, generation=generation, frame_id=frame_id)
        )
    try:
        stream = runtime.model_runner.ainvoke_structured_stream(
            node_name=FRAME_NODE_NAMES[frame_type],
            case_data={
                "room_type": "INTAKE",
                "agent_key": "DISPUTE_INTAKE_OFFICER",
                "frame_type": frame_type,
            },
            output_type=FRAME_OUTPUT_MODELS[frame_type],
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
                        request.model_input.model_dump(mode="json"),
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
            agent_context=runtime.agent_context,
            prompt_profile_id=request.model_input.instruction_pack.prompt_profile_id,
        )
        async for update in stream:
            if isinstance(update, HarnessStreamDelta):
                if update.field != "public_projection_items":
                    raise IntakeGraphContractError(
                        "INTAKE_PARALLEL_FRAME_FOREIGN_VISIBLE_FIELD"
                    )
                provider_item = _decode_visible_item(update.delta)
                item_model = FRAME_PUBLIC_ITEM_MODELS[frame_type].model_validate(
                    provider_item
                )
                normalized_provider_item = item_model.model_dump(mode="json")
                slot_id = str(normalized_provider_item["provider_slot_id"])
                if slot_id in {
                    str(item["provider_slot_id"]) for item in provider_items
                }:
                    raise IntakeGraphContractError(
                        "INTAKE_PARALLEL_FRAME_PROJECTION_SLOT_REPEATED"
                    )
                canonical_item = canonical_parallel_public_projection(
                    frame_type,
                    item_model,
                )
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
                if reset_count != 0 or update.generation != 2:
                    raise IntakeGraphContractError(
                        "INTAKE_PARALLEL_FRAME_GENERATION_RESET_INVALID"
                    )
                old_generation = generation
                old_frame_id = frame_id
                # Java owns replacement-generation admission.  Its slot CAS accepts a
                # retry only after the current generation has durably entered FAILED or
                # AMBIGUOUS, so make the superseded provider generation explicit before
                # announcing the reset and replacement Frame identity.
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
                await runtime.event_sink.emit(interrupted)
                generation += 1
                frame_id = _replacement_frame_id(
                    request,
                    old_frame_id=old_frame_id,
                    generation=generation,
                )
                reset_count = 1
                provider_items.clear()
                canonical_items.clear()
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
                await runtime.event_sink.emit(generation_reset)
                generation_transition_events.extend(
                    (
                        interrupted.model_dump(mode="json"),
                        generation_reset.model_dump(mode="json"),
                    )
                )
                await runtime.event_sink.emit(
                    _frame_started(request, generation=generation, frame_id=frame_id)
                )
                continue
            if isinstance(update, HarnessStreamCompleted):
                if completed is not None:
                    raise IntakeGraphContractError(
                        "INTAKE_PARALLEL_FRAME_MULTIPLE_COMPLETIONS"
                    )
                completed = update
                continue
            raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_STREAM_EVENT_INVALID")
        if completed is None:
            raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_COMPLETION_MISSING")
        final = validate_parallel_frame_output(
            frame_type,
            completed.generation.value,
        )
        final_payload = final.model_dump(mode="json")
        if final_payload["public_projection_items"] != provider_items:
            raise IntakeGraphContractError(
                "INTAKE_PARALLEL_FRAME_VISIBLE_PREFIX_DIVERGED"
            )
        canonical_result_json = canonicalize(final_payload).decode("utf-8")
        result_sha256 = canonical_sha256(final_payload)
        public_projection_sha256 = canonical_sha256(canonical_items)
        usage = _provider_usage(
            completed,
            provider_call_count=1 + reset_count,
        )
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


async def _replay_checkpoint_prefix(
    request: ParallelFrameExecutionRequest,
    state: Mapping[str, Any],
    event_sink: ParallelFrameTechnicalEventSink,
) -> None:
    canonical_items = list(state["canonical_projection_items"])
    generation = int(state["generation"])
    frame_id = str(state["frame_id"])
    transitions = _validated_generation_transition_events(state, request.frame_type)
    resume_generation, resume_frame_id = request.resume_position()
    initial_position = (request.generation, request.frame_id)
    terminal_position = (generation, frame_id)
    if (resume_generation, resume_frame_id) == initial_position:
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
) -> CanonicalPublicProjectionItem:
    if frame_type == "DIALOGUE_FRAME":
        dialogue = DialoguePublicSegmentProposalV1.model_validate(item)
        return CanonicalPublicProjectionItem(
            canonical_item_id=dialogue.provider_slot_id,
            projection_kind=dialogue.segment_kind,
            projection_path_id="intake.dialogue.public_segments",
            value_kind="TEXT",
            public_text=dialogue.candidate_text,
        )
    if frame_type == "DOSSIER_FRAME":
        dossier = DossierPublicPatchProposalV1.model_validate(item)
        return CanonicalPublicProjectionItem(
            canonical_item_id=dossier.provider_slot_id,
            projection_kind=dossier.projection_kind,
            projection_path_id=dossier.projection_path_id,
            value_kind="JSON_VALUE",
            canonical_value=dossier.candidate_value,
        )
    quality = QualityPublicMetricProposalV1.model_validate(item)
    return CanonicalPublicProjectionItem(
        canonical_item_id=quality.provider_slot_id,
        projection_kind=quality.projection_kind,
        projection_path_id=f"intake.quality.scores.{quality.dimension.lower()}",
        value_kind="JSON_VALUE",
        canonical_value=quality.candidate_score,
    )


def _provider_usage(
    completed: HarnessStreamCompleted[Any],
    *,
    provider_call_count: int,
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
    return FrameProviderUsage(
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        total_tokens=total_tokens,
        latency_ms=int(completed.generation.latency_ms),
        provider_call_count=provider_call_count,
        model=completed.generation.model,
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
) -> None:
    if state.get("checkpoint_schema_version") != _CHECKPOINT_SCHEMA_VERSION:
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_SCHEMA_INVALID")
    if state.get("authority") != request.checkpoint_identity():
        raise IntakeGraphContractError("INTAKE_PARALLEL_FRAME_CHECKPOINT_AUTHORITY_DRIFT")
    raw_model_input = state.get("model_input")
    try:
        persisted_model_input = IntakeFrameModelInputV1.model_validate(raw_model_input)
    except Exception as error:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_MODEL_INPUT_INVALID"
        ) from error
    if persisted_model_input != request.model_input:
        raise IntakeGraphContractError(
            "INTAKE_PARALLEL_FRAME_CHECKPOINT_MODEL_INPUT_DRIFT"
        )


def _require_complete_state(
    state: Mapping[str, Any],
    frame_type: ParallelFrameType,
) -> None:
    try:
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
        FrameProviderUsage.model_validate(state["usage"])
        datetime.fromisoformat(str(state["completed_at"]))
        _validated_generation_transition_events(state, frame_type)
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
