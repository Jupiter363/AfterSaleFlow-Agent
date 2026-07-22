"""Durable signed-synthetic executor for the exact ``intake.v2`` binding."""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from datetime import datetime, timezone
from typing import Any, Literal, Protocol, cast

from langchain_core.runnables import RunnableConfig

from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    ArtifactOperation,
    ArtifactPointer,
    ExecutionMetadata,
    Usage,
)
from app.graph_runtime.checkpoint import (
    FENCE_CONTEXT_KEY,
    TERMINAL_RESULT_CONTEXT_KEY,
    ExternalTerminalCommit,
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
)
from app.graph_runtime.compiled_executor import GraphPublicUpdate
from app.graph_runtime.errors import GraphContractError, GraphTerminalBindingError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.intake_binding import (
    IntakeInputLoader,
    IntakeProposalStore,
    build_governed_intake_runtime,
    build_intake_command_patch,
    build_intake_execution_state,
    canonical_intake_proposal,
    decode_authorized_intake_ingress,
)
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graphs.intake.runtime import IntakeRuntimeBundle
from app.graphs.intake.state import IntakeGraphStateV2, IntakeTurnContext
from app.graphs.intake.validators import validate_state
from app.model_runtime.transports import ModelTransport


class CompiledIntakeStateGraphPort(Protocol):
    checkpointer: object

    def astream(
        self,
        input: Mapping[str, Any] | None,
        config: RunnableConfig,
        *,
        context: IntakeTurnContext,
        stream_mode: str,
    ) -> AsyncIterator[Any]: ...

    async def aget_state(self, config: RunnableConfig) -> Any: ...


class CompiledIntakeGraphShadowExecutor:
    """Run the governed Intake graph and publish its proposal through an immutable pointer."""

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        transport: ModelTransport,
        provider: str,
        model: str,
        input_loader: IntakeInputLoader,
        proposal_store: IntakeProposalStore,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if not provider or len(provider) > 64 or not model or len(model) > 128:
            raise ValueError("Intake provider binding is invalid")
        if not callable(getattr(input_loader, "load", None)) or not callable(
            getattr(proposal_store, "put", None)
        ):
            raise ValueError("Intake immutable exchange ports are incomplete")
        self._saver = saver
        self._transport = transport
        self._provider = provider
        self._model = model
        self._input_loader = input_loader
        self._proposal_store = proposal_store
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
            AgentStreamPayload(node="authorize_and_load"),
        )
        sequence += 1

        loaded = await self._input_loader.load(execution)
        context = decode_authorized_intake_ingress(
            command=execution.admission.command,
            loaded=loaded,
        )
        bundle = build_governed_intake_runtime(
            execution=execution,
            transport=self._transport,
            provider=self._provider,
            model=self._model,
            checkpointer=self._saver,
        )
        graph = cast(CompiledIntakeStateGraphPort, bundle.graph)
        if graph.checkpointer is not self._saver:
            raise GraphContractError("compiled Intake Graph lost the process fenced saver")
        graph_input = self._graph_input(execution)
        config = self._graph_config(execution)
        emitted_usage: list[Usage] = []
        source = graph.astream(
            graph_input,
            config,
            context=context,
            stream_mode="custom",
        )
        close = getattr(source, "aclose", None)
        if not callable(close):
            raise GraphContractError("compiled Intake Graph stream is not closable")
        try:
            async for candidate in source:
                if not isinstance(candidate, GraphPublicUpdate):
                    raise GraphContractError("compiled Intake Graph emitted an untyped update")
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

        snapshot = await graph.aget_state(config)
        state, final_config = self._snapshot(snapshot, execution)
        proposal = IntakeRuntimeBundle.terminal_proposal(state)
        if state.get("terminal_draft") != state.get("result_json"):
            raise GraphTerminalBindingError(
                "Intake terminal draft differs from its durable proposal"
            )
        canonical = canonical_intake_proposal(proposal)
        usage = self._command_usage(state, execution, emitted_usage)
        configurable = final_config.get("configurable") or {}
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        checkpoint_id = str(configurable.get("checkpoint_id") or "")
        revision = state["cognitive_revision"]

        await self._saver.avalidate_external_terminal_checkpoint(
            final_config,
            cognitive_revision=revision,
        )
        stored = await self._proposal_store.put(
            execution,
            proposal=canonical,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
        )
        if (
            stored.artifact_id != canonical.artifact_id
            or stored.schema_version != canonical.schema_version
            or stored.sha256 != canonical.sha256
            or stored.size_bytes != canonical.size_bytes
        ):
            raise GraphTerminalBindingError(
                "stored Intake proposal differs from the checkpointed proposal"
            )
        result = self._materializer(
            execution,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
            usage=usage,
            artifact=ArtifactPointer(
                artifact_id=stored.artifact_id,
                schema_version=stored.schema_version,
                uri=stored.uri,
                sha256=stored.sha256,
            ),
        ).materialize(checkpoint_ns, checkpoint_id)
        saved = await self._saver.acommit_external_terminal(
            final_config,
            ExternalTerminalCommit(result=result, cognitive_revision=revision),
        )
        saved_fence = (saved.get("configurable") or {}).get(FENCE_CONTEXT_KEY)
        if (
            not isinstance(saved_fence, GraphFenceContext)
            or saved_fence.result_ref != result.result_ref
            or saved_fence.result_hash != result.result_hash
        ):
            raise GraphTerminalBindingError(
                "Intake generic result was not bound to the terminal fence"
            )
        if not emitted_usage and usage.total_tokens > 0:
            yield self._event(
                execution,
                sequence,
                "usage",
                AgentStreamPayload(usage=usage),
            )
            sequence += 1
        yield self._event(
            execution,
            sequence,
            "final",
            AgentStreamPayload(
                final_result_ref=result.result_ref,
                final_result_hash=result.result_hash,
            ),
        )

    @staticmethod
    def _graph_input(execution: GatewayExecution) -> Mapping[str, Any]:
        record = execution.thread_record
        if record.last_checkpoint_id is None:
            return build_intake_execution_state(execution)
        return build_intake_command_patch(execution)

    @staticmethod
    def _graph_config(execution: GatewayExecution) -> RunnableConfig:
        record = execution.thread_record
        configurable: dict[str, Any] = {
            "thread_id": execution.fence.thread_id,
            "checkpoint_ns": record.last_checkpoint_ns or "",
        }
        return bind_fence_context({"configurable": configurable}, execution.fence)

    @staticmethod
    def _snapshot(
        snapshot: Any,
        execution: GatewayExecution,
    ) -> tuple[IntakeGraphStateV2, RunnableConfig]:
        values = getattr(snapshot, "values", None)
        config = getattr(snapshot, "config", None)
        next_nodes = getattr(snapshot, "next", None)
        tasks = getattr(snapshot, "tasks", None)
        interrupts = getattr(snapshot, "interrupts", None)
        if (
            not isinstance(values, dict)
            or not isinstance(config, Mapping)
            or not isinstance(next_nodes, tuple)
            or not isinstance(tasks, tuple)
            or not isinstance(interrupts, tuple)
            or next_nodes
            or tasks
            or interrupts
        ):
            raise GraphContractError(
                "compiled Intake Graph did not reach a quiescent terminal checkpoint"
            )
        validate_state(cast(IntakeGraphStateV2, values))
        configurable = config.get("configurable") or {}
        fence = configurable.get(FENCE_CONTEXT_KEY)
        checkpoint_ns = configurable.get("checkpoint_ns", "")
        checkpoint_id = configurable.get("checkpoint_id")
        if (
            fence != execution.fence
            or configurable.get("thread_id") != execution.fence.thread_id
            or not isinstance(checkpoint_ns, str)
            or len(checkpoint_ns) > 128
            or not isinstance(checkpoint_id, str)
            or not checkpoint_id
            or len(checkpoint_id) > 128
            or TERMINAL_RESULT_CONTEXT_KEY in configurable
        ):
            raise GraphContractError("compiled Intake Graph snapshot lost its exact terminal fence")
        CompiledIntakeGraphShadowExecutor._require_state_authority(values, execution)
        return cast(IntakeGraphStateV2, dict(values)), cast(RunnableConfig, dict(config))

    @staticmethod
    def _require_state_authority(
        state: Mapping[str, Any],
        execution: GatewayExecution,
    ) -> None:
        command = execution.admission.command
        record = execution.thread_record
        registry = execution.admission.registry.binding
        invocation = command.invocation_context
        private = state.get("bindings", {}).get("private")
        command_binding = state.get("bindings", {}).get("command")
        expected_private = {
            "schema_version": "intake-private-binding.v1",
            "tenant_surrogate": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_type": "INTAKE",
            "room_epoch": command.room_epoch,
            "actor_scope_hash": record.identity.actor_scope_hash,
            "thread_id": command.thread_id,
            "agent_session_id": record.identity.agent_session_id,
            "audience": command.actor_scope.audience,
        }
        expected_command = {
            "schema_version": "intake-command-binding.v1",
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
        }
        expected_versions = {
            "schema_version": "graph-version-pins.v1",
            "graph_key": command.graph_key,
            "graph_version": command.graph_version,
            "checkpoint_schema_version": command.checkpoint_schema_version,
            "state_schema_version": registry.state_schema_version,
            "prompt_version": invocation.prompt_profile_id,
            "model_profile_id": invocation.model_profile_id,
            "output_schema_version": invocation.output_schema_version,
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_policy_version": registry.tool_policy_version,
        }
        if (
            private != expected_private
            or command_binding != expected_command
            or state.get("version_pins") != expected_versions
        ):
            raise GraphContractError("Intake terminal state lost its signed authority binding")

    @staticmethod
    def _command_usage(
        state: Mapping[str, Any],
        execution: GatewayExecution,
        emitted_usage: list[Usage],
    ) -> Usage:
        usage_by_invocation = state.get("usage_by_invocation")
        if not isinstance(usage_by_invocation, Mapping):
            raise GraphTerminalBindingError("Intake terminal usage is not a mapping")
        candidate = usage_by_invocation.get(execution.admission.command.attempt_id)
        if candidate is None:
            usage = Usage(input_tokens=0, output_tokens=0, total_tokens=0)
        else:
            try:
                usage = Usage.model_validate(candidate)
            except ValueError as error:
                raise GraphTerminalBindingError("Intake terminal usage is invalid") from error
        if usage.total_tokens != usage.input_tokens + usage.output_tokens:
            raise GraphTerminalBindingError("Intake terminal usage is inconsistent")
        if emitted_usage:
            public = Usage(
                input_tokens=sum(value.input_tokens for value in emitted_usage),
                output_tokens=sum(value.output_tokens for value in emitted_usage),
                total_tokens=sum(value.total_tokens for value in emitted_usage),
            )
            if public != usage:
                raise GraphTerminalBindingError("Intake terminal usage differs from public updates")
        return usage

    @staticmethod
    def _materializer(
        execution: GatewayExecution,
        *,
        checkpoint_id: str,
        cognitive_revision: int,
        usage: Usage,
        artifact: ArtifactPointer,
    ) -> TerminalResultMaterializer:
        command = execution.admission.command
        invocation = command.invocation_context
        return TerminalResultMaterializer(
            thread_id=execution.fence.thread_id,
            request_hash=command.request_hash,
            draft=CompletedDraft(status="COMPLETED"),
            bindings=ResultBindings(
                command_id=command.command_id,
                logical_run_id=command.logical_run_id,
                attempt_id=command.attempt_id,
                graph_key=command.graph_key,
                graph_version=command.graph_version,
                checkpoint_id=checkpoint_id,
                cognitive_revision=cognitive_revision,
                public_event_proposals=(),
                artifact_operations=(
                    ArtifactOperation(operation="PROPOSE_PATCH", artifact=artifact),
                ),
                usage=usage,
                execution_metadata=ExecutionMetadata(
                    prompt_version=invocation.prompt_profile_id,
                    model_profile_id=invocation.model_profile_id,
                    schema_version=invocation.output_schema_version,
                    policy_version=invocation.policy_version,
                    guardrail_version=invocation.guardrail_version,
                ),
            ),
        )

    def _event(
        self,
        execution: GatewayExecution,
        sequence: int,
        event_type: Literal["attempt_started", "visible_delta", "usage", "final"],
        payload: AgentStreamPayload,
    ) -> AgentStreamEvent:
        occurred_at = self._clock()
        if occurred_at.utcoffset() is None:
            raise GraphContractError("Intake executor clock must be timezone-aware")
        command = execution.admission.command
        return AgentStreamEvent(
            schema_version="agent-stream.v2",
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            sequence_no=sequence,
            event_type=event_type,
            audience=command.actor_scope.audience,
            occurred_at=occurred_at,
            payload=payload,
        )


__all__ = ["CompiledIntakeGraphShadowExecutor"]
