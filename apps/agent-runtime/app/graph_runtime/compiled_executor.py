"""Concrete async LangGraph executor with fenced terminal result publication."""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Literal, Protocol, cast

from langchain_core.runnables import RunnableConfig

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import AgentStreamEvent, AgentStreamPayload, Usage
from app.graph_runtime.checkpoint import (
    FENCE_CONTEXT_KEY,
    FencedPostgresSaver,
    TERMINAL_RESULT_CONTEXT_KEY,
    TerminalResultMaterializer,
    bind_fence_context,
    bind_terminal_result_context,
)
from app.graph_runtime.errors import GraphContractError, GraphTerminalBindingError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.ledger import ResultRecord
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.result import TERMINAL_DRAFT_ADAPTER, ResultBindings, TerminalDraft
from app.graph_runtime.state import validate_graph_state
from app.graph_runtime.target_e2e import TargetE2ERoomProposalSource


class CompiledStateGraphPort(Protocol):
    checkpointer: object

    def astream(
        self,
        input: Mapping[str, Any],
        config: RunnableConfig,
        *,
        stream_mode: str,
    ) -> AsyncIterator[Any]: ...

    async def aget_state(self, config: RunnableConfig) -> Any: ...

    async def aupdate_state(
        self,
        config: RunnableConfig,
        values: Mapping[str, Any],
        *,
        as_node: str,
    ) -> RunnableConfig: ...


@dataclass(frozen=True, slots=True)
class GraphPublicUpdate:
    event_type: Literal["visible_delta", "usage"]
    payload: AgentStreamPayload

    def __post_init__(self) -> None:
        present = frozenset(self.payload.model_dump(exclude_none=True))
        expected = {
            "visible_delta": frozenset({"node", "field", "delta"}),
            "usage": frozenset({"usage"}),
        }[self.event_type]
        if present != expected:
            raise GraphContractError("Graph public update payload is invalid")
        usage = self.payload.usage
        if usage is not None and usage.total_tokens != usage.input_tokens + usage.output_tokens:
            raise GraphContractError("Graph public usage update is inconsistent")

    @classmethod
    def visible_delta(
        cls,
        *,
        node: str,
        field: str,
        delta: str,
    ) -> GraphPublicUpdate:
        return cls(
            "visible_delta",
            AgentStreamPayload(node=node, field=field, delta=delta),
        )

    @classmethod
    def usage(cls, usage: Usage) -> GraphPublicUpdate:
        return cls("usage", AgentStreamPayload(usage=usage))


@dataclass(frozen=True, slots=True)
class TerminalResultPlan:
    draft: TerminalDraft | dict[str, object]
    bindings: ResultBindings
    target_proposal_source: TargetE2ERoomProposalSource | None = None

    def materialize(
        self,
        execution: GatewayExecution,
        checkpoint_ns: str,
        checkpoint_id: str,
    ) -> ResultRecord:
        return self.to_materializer(execution).materialize(
            checkpoint_ns,
            checkpoint_id,
            fence=execution.fence,
        )

    def to_materializer(
        self,
        execution: GatewayExecution,
    ) -> TerminalResultMaterializer:
        command = execution.admission.command
        invocation = command.invocation_context
        bindings = self.bindings
        metadata = bindings.execution_metadata
        if (
            bindings.command_id != command.command_id
            or bindings.logical_run_id != command.logical_run_id
            or bindings.attempt_id != command.attempt_id
            or bindings.graph_key != command.graph_key
            or bindings.graph_version != command.graph_version
            or metadata.prompt_version != invocation.prompt_profile_id
            or metadata.model_profile_id != invocation.model_profile_id
            or metadata.schema_version != invocation.output_schema_version
            or metadata.policy_version != invocation.policy_version
            or metadata.guardrail_version != invocation.guardrail_version
        ):
            raise GraphTerminalBindingError(
                "terminal result plan conflicts with the signed command"
            )
        usage = bindings.usage
        if usage.total_tokens != usage.input_tokens + usage.output_tokens:
            raise GraphTerminalBindingError("terminal result usage is inconsistent")
        try:
            draft = TERMINAL_DRAFT_ADAPTER.validate_python(self.draft)
        except ValueError as error:
            raise GraphTerminalBindingError("terminal result draft is invalid") from error
        return TerminalResultMaterializer(
            thread_id=execution.fence.thread_id,
            request_hash=command.request_hash,
            draft=draft,
            bindings=bindings,
            target_proposal_source=self.target_proposal_source,
        )


InitialStateFactory = Callable[[GatewayExecution], Mapping[str, Any]]
TerminalPlanFactory = Callable[[GatewayExecution, Mapping[str, Any]], TerminalResultPlan]


class CompiledGraphShadowExecutor:
    """Drive one exact compiled StateGraph and publish only checkpointed terminal output."""

    def __init__(
        self,
        *,
        graph: CompiledStateGraphPort,
        saver: FencedPostgresSaver,
        initial_state: InitialStateFactory,
        terminal_plan: TerminalPlanFactory,
        terminal_node: str = "project_result",
        start_node: str = "graph_execution",
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if graph.checkpointer is not saver:
            raise GraphContractError("compiled Graph does not use the process fenced saver")
        if not terminal_node or not start_node:
            raise ValueError("compiled Graph executor binding is invalid")
        self._graph = graph
        self._saver = saver
        self._initial_state = initial_state
        self._terminal_plan = terminal_plan
        self._checkpoint_ns = ""
        self._terminal_node = terminal_node
        self._start_node = start_node
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    async def stream(
        self,
        execution: GatewayExecution,
    ) -> AsyncIterator[AgentStreamEvent]:
        sequence = 0
        yield self._event(
            execution,
            sequence,
            "attempt_started",
            AgentStreamPayload(node=self._start_node),
        )
        sequence += 1

        initial = dict(self._initial_state(execution))
        validate_graph_state(initial)
        self._require_state_identity(initial, execution)
        config = bind_fence_context(
            {
                "configurable": {
                    "thread_id": execution.fence.thread_id,
                    "checkpoint_ns": self._checkpoint_ns,
                }
            },
            execution.fence,
        )
        emitted_usage: list[Usage] = []
        source = self._graph.astream(
            initial,
            config,
            stream_mode="custom",
        )
        close = getattr(source, "aclose", None)
        if not callable(close):
            raise GraphContractError("compiled Graph stream is not deterministically closable")
        try:
            async for candidate in source:
                if not isinstance(candidate, GraphPublicUpdate):
                    raise GraphContractError("compiled Graph emitted an untyped public update")
                if candidate.payload.usage is not None:
                    emitted_usage.append(candidate.payload.usage)
                yield self._event(
                    execution,
                    sequence,
                    candidate.event_type,
                    candidate.payload,
                )
                sequence += 1
        finally:
            await cast(Callable[[], Awaitable[None]], close)()

        snapshot = await self._graph.aget_state(config)
        state, checkpoint_config = self._snapshot(snapshot, execution)
        validate_graph_state(state)
        self._require_state_identity(state, execution)
        plan = self._terminal_plan(execution, state)
        if not isinstance(plan, TerminalResultPlan):
            raise GraphContractError("terminal plan factory returned an invalid type")
        self._require_terminal_state_binding(plan, state, emitted_usage)
        terminal_plan = self._terminal_checkpoint_plan(plan)
        materializer = terminal_plan.to_materializer(execution)
        terminal_config = bind_terminal_result_context(checkpoint_config, materializer)
        saved = await self._graph.aupdate_state(
            terminal_config,
            {
                "cognitive_revision": terminal_plan.bindings.cognitive_revision,
                "result_json": {"status": "PENDING_TERMINAL_COMMIT"},
            },
            as_node=self._terminal_node,
        )
        final_snapshot = await self._graph.aget_state(saved)
        final_state, final_config = self._snapshot(final_snapshot, execution)
        validate_graph_state(final_state)
        self._require_state_identity(final_state, execution)
        self._require_terminal_state_binding(terminal_plan, final_state, emitted_usage)
        result_json = final_state.get("result_json")
        if not isinstance(result_json, Mapping):
            raise GraphTerminalBindingError("terminal checkpoint has no result JSON")
        configurable = final_config.get("configurable") or {}
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        checkpoint_id = str(configurable.get("checkpoint_id") or "")
        expected = materializer.materialize(
            checkpoint_ns,
            checkpoint_id,
            fence=execution.fence,
        )
        final_fence = configurable.get(FENCE_CONTEXT_KEY)
        if (
            not isinstance(final_fence, GraphFenceContext)
            or final_fence.result_hash != expected.result_hash
            or final_fence.result_ref != expected.result_ref
            or final_fence.proposal_hash != expected.proposal_hash
            or final_fence.result_envelope_hash != expected.result_envelope_hash
            or dict(result_json) != dict(expected.result_json)
        ):
            raise GraphTerminalBindingError(
                "terminal checkpoint result differs from its immutable ledger row"
            )
        yield self._event(
            execution,
            sequence,
            "final",
            AgentStreamPayload(
                final_result_ref=expected.result_ref,
                final_result_hash=expected.result_hash,
            ),
        )

    def _snapshot(
        self,
        snapshot: Any,
        execution: GatewayExecution,
    ) -> tuple[dict[str, Any], RunnableConfig]:
        values = getattr(snapshot, "values", None)
        config = getattr(snapshot, "config", None)
        next_nodes = getattr(snapshot, "next", None)
        tasks = getattr(snapshot, "tasks", None)
        interrupts = getattr(snapshot, "interrupts", None)
        if (
            not isinstance(values, Mapping)
            or not isinstance(config, Mapping)
            or not isinstance(next_nodes, tuple)
            or not isinstance(tasks, tuple)
            or not isinstance(interrupts, tuple)
        ):
            raise GraphContractError("compiled Graph returned an invalid state snapshot")
        if next_nodes or tasks or interrupts:
            raise GraphContractError(
                "compiled Graph stopped before reaching a quiescent terminal state"
            )
        configurable = config.get("configurable") or {}
        fence = configurable.get(FENCE_CONTEXT_KEY)
        checkpoint_ns = configurable.get("checkpoint_ns", "")
        checkpoint_id = configurable.get("checkpoint_id")
        if (
            configurable.get("thread_id") != execution.fence.thread_id
            or checkpoint_ns != self._checkpoint_ns
            or not isinstance(checkpoint_id, str)
            or not checkpoint_id
            or len(checkpoint_id) > 128
            or not isinstance(fence, GraphFenceContext)
            or (
                fence.thread_id,
                fence.command_id,
                fence.owner_id,
                fence.fencing_token,
                fence.request_hash,
                fence.room_epoch,
                fence.graph_key,
                fence.graph_version,
                fence.checkpoint_schema_version,
            )
            != (
                execution.fence.thread_id,
                execution.fence.command_id,
                execution.fence.owner_id,
                execution.fence.fencing_token,
                execution.fence.request_hash,
                execution.fence.room_epoch,
                execution.fence.graph_key,
                execution.fence.graph_version,
                execution.fence.checkpoint_schema_version,
            )
            or TERMINAL_RESULT_CONTEXT_KEY in configurable
        ):
            raise GraphContractError("compiled Graph snapshot lost its trusted fence binding")
        return dict(values), dict(config)  # type: ignore[return-value]

    @staticmethod
    def _require_state_identity(
        state: Mapping[str, Any],
        execution: GatewayExecution,
    ) -> None:
        command = execution.admission.command
        registry = getattr(execution.admission.registry, "binding", None)
        if registry is None:
            raise GraphContractError("compiled Graph has no trusted registry binding")
        expected_bindings = {
            "schema_version": "graph-command-binding.v1",
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "tenant_surrogate": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_type": command.room_type,
            "room_epoch": command.room_epoch,
            "actor_scope_hash": canonical_sha256(command.actor_scope.model_dump(mode="json")),
            "thread_id": command.thread_id,
        }
        invocation = command.invocation_context
        expected_versions = {
            "schema_version": "graph-version-pins.v1",
            "graph_key": command.graph_key,
            "graph_version": command.graph_version,
            "checkpoint_schema_version": command.checkpoint_schema_version,
            "state_schema_version": getattr(registry, "state_schema_version", None),
            "prompt_version": invocation.prompt_profile_id,
            "model_profile_id": invocation.model_profile_id,
            "output_schema_version": invocation.output_schema_version,
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_policy_version": getattr(registry, "tool_policy_version", None),
        }
        if state.get("bindings") != expected_bindings:
            raise GraphContractError("Graph state command binding is missing or inconsistent")
        if (
            expected_versions["state_schema_version"] is None
            or expected_versions["tool_policy_version"] is None
            or state.get("version_pins") != expected_versions
        ):
            raise GraphContractError("Graph state version pins are missing or inconsistent")

    @staticmethod
    def _require_terminal_state_binding(
        plan: TerminalResultPlan,
        state: Mapping[str, Any],
        emitted_usage: list[Usage],
    ) -> None:
        revision = state.get("cognitive_revision")
        if (
            not isinstance(revision, int)
            or isinstance(revision, bool)
            or plan.bindings.cognitive_revision != revision
        ):
            raise GraphTerminalBindingError(
                "terminal result revision differs from the durable Graph state"
            )
        try:
            state_draft = TERMINAL_DRAFT_ADAPTER.validate_python(state.get("terminal_draft"))
            plan_draft = TERMINAL_DRAFT_ADAPTER.validate_python(plan.draft)
        except ValueError as error:
            raise GraphTerminalBindingError(
                "terminal result draft is missing or invalid in the durable Graph state"
            ) from error
        if state_draft != plan_draft:
            raise GraphTerminalBindingError(
                "terminal result draft differs from the durable Graph state"
            )
        state_usage = CompiledGraphShadowExecutor._aggregate_usage(
            state.get("usage_by_invocation"),
            source="durable Graph state",
        )
        public_usage = CompiledGraphShadowExecutor._aggregate_usage(
            {str(index): usage for index, usage in enumerate(emitted_usage)},
            source="public Graph updates",
        )
        if plan.bindings.usage != state_usage or public_usage != state_usage:
            raise GraphTerminalBindingError(
                "terminal result usage differs from its state or public updates"
            )

    @staticmethod
    def _terminal_checkpoint_plan(plan: TerminalResultPlan) -> TerminalResultPlan:
        revision = plan.bindings.cognitive_revision
        if revision >= (1 << 63) - 1:
            raise GraphTerminalBindingError("terminal result revision is exhausted")
        return TerminalResultPlan(
            draft=plan.draft,
            bindings=plan.bindings.model_copy(
                update={"cognitive_revision": revision + 1}
            ),
            target_proposal_source=plan.target_proposal_source,
        )

    @staticmethod
    def _aggregate_usage(value: Any, *, source: str) -> Usage:
        if not isinstance(value, Mapping):
            raise GraphTerminalBindingError(f"{source} usage is not a mapping")
        input_tokens = 0
        output_tokens = 0
        total_tokens = 0
        for candidate in value.values():
            try:
                usage = (
                    candidate if isinstance(candidate, Usage) else Usage.model_validate(candidate)
                )
            except ValueError as error:
                raise GraphTerminalBindingError(f"{source} usage is invalid") from error
            if usage.total_tokens != usage.input_tokens + usage.output_tokens:
                raise GraphTerminalBindingError(f"{source} usage is inconsistent")
            input_tokens += usage.input_tokens
            output_tokens += usage.output_tokens
            total_tokens += usage.total_tokens
        return Usage(
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            total_tokens=total_tokens,
        )

    def _event(
        self,
        execution: GatewayExecution,
        sequence: int,
        event_type: str,
        payload: AgentStreamPayload,
    ) -> AgentStreamEvent:
        occurred_at = self._clock()
        if occurred_at.utcoffset() is None:
            raise GraphContractError("compiled Graph clock must be timezone-aware")
        command = execution.admission.command
        return AgentStreamEvent(
            schema_version="agent-stream.v3",
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            sequence_no=sequence,
            event_type=event_type,  # type: ignore[arg-type]
            audience=command.actor_scope.audience,
            occurred_at=occurred_at,
            payload=payload,
        )
