"""Durable signed-synthetic executor for the exact ``intake.v2`` binding."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from datetime import datetime, timezone
from typing import Any, Literal, Protocol, cast

from langchain_core.messages import AIMessageChunk
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
    StoredIntakeProposal,
    build_governed_intake_runtime,
    build_intake_command_patch,
    build_intake_execution_state,
    canonical_intake_proposal,
    decode_authorized_intake_ingress,
)
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graph_runtime.target_e2e import (
    TargetE2ERoomProposal,
    TargetE2ERoomProposalSource,
)
from app.graphs.intake.lcel import (
    _TARGET_INTAKE_VISIBLE_FIELDS,
    _contains_forbidden_evidence_request,
    _is_evidence_material_gap,
)
from app.graphs.intake.runtime import IntakeRuntimeBundle
from app.graphs.intake.state import IntakeGraphStateV2, IntakeTurnContext
from app.graphs.intake.validators import validate_state
from app.model_runtime.callbacks import governed_events_from_chunk
from app.model_runtime.transports import ModelTransport


_INTAKE_VISIBLE_FIELDS = frozenset(spec.field for spec in _TARGET_INTAKE_VISIBLE_FIELDS)


class CompiledIntakeStateGraphPort(Protocol):
    checkpointer: object

    def astream(
        self,
        input: Mapping[str, Any] | None,
        config: RunnableConfig,
        *,
        context: IntakeTurnContext,
        stream_mode: str | list[str],
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
        runtime_execution_projector: (Callable[[GatewayExecution], GatewayExecution] | None) = None,
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
        self._runtime_execution_projector = runtime_execution_projector

    async def stream(
        self,
        execution: GatewayExecution,
    ) -> AsyncIterator[AgentStreamEvent]:
        runtime_execution = (
            self._runtime_execution_projector(execution)
            if self._runtime_execution_projector is not None
            else execution
        )
        if not isinstance(runtime_execution, GatewayExecution):
            raise GraphContractError("Intake runtime execution projector is invalid")
        sequence = 0
        yield self._event(
            execution,
            sequence,
            "attempt_started",
            AgentStreamPayload(node="authorize_and_load"),
        )
        sequence += 1

        context = await self._load_context(execution, runtime_execution)
        bundle = build_governed_intake_runtime(
            execution=runtime_execution,
            transport=self._transport,
            provider=self._provider,
            model=self._model,
            checkpointer=self._saver,
        )
        graph = cast(CompiledIntakeStateGraphPort, bundle.graph)
        if graph.checkpointer is not self._saver:
            raise GraphContractError("compiled Intake Graph lost the process fenced saver")
        graph_input = self._graph_input(runtime_execution)
        config = self._graph_config(runtime_execution)
        emitted_usage: list[Usage] = []
        pending_usage_update: GraphPublicUpdate | None = None
        room_utterance_emitted = False
        source = graph.astream(
            graph_input,
            config,
            context=context,
            stream_mode=["messages", "custom"],
        )
        close = getattr(source, "aclose", None)
        if not callable(close):
            raise GraphContractError("compiled Intake Graph stream is not closable")
        try:
            async for candidate in source:
                for update in self._public_updates(candidate):
                    self._validate_public_update(update)
                    if update.event_type == "usage":
                        if pending_usage_update is not None:
                            raise GraphContractError("INTAKE_USAGE_STREAM_DUPLICATE")
                        usage_update = update.payload.usage
                        assert usage_update is not None
                        emitted_usage.append(usage_update)
                        pending_usage_update = update
                        continue
                    if update.payload.field == "room_utterance":
                        if room_utterance_emitted:
                            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_DUPLICATE")
                        safe_update = self._validated_room_utterance_update(update)
                        yield self._event(
                            execution,
                            sequence,
                            safe_update.event_type,
                            safe_update.payload,
                        )
                        room_utterance_emitted = True
                        sequence += 1
                        continue
                    if not update.payload.field.startswith("case_detail."):
                        raise GraphContractError(
                            "compiled Intake Graph emitted an unsupported visible field"
                        )
                    # The frontend treats streamed dossier sections as a provisional
                    # view and discards them on ERROR, attempt reset, workspace change,
                    # or failed formal-readiness reconciliation.  Publish each complete
                    # governed JSON section as soon as it is available so the board can
                    # evolve alongside the utterance; the durable proposal and formal
                    # dossier remain authoritative only after the fenced terminal commit
                    # below succeeds.
                    yield self._event(
                        execution,
                        sequence,
                        update.event_type,
                        update.payload,
                    )
                    sequence += 1
        finally:
            await cast(Callable[[], Awaitable[None]], close)()

        snapshot = await graph.aget_state(config)
        state, final_config = self._snapshot(snapshot, runtime_execution)
        proposal = IntakeRuntimeBundle.terminal_proposal(state)
        if state.get("terminal_draft") != state.get("result_json"):
            raise GraphTerminalBindingError(
                "Intake terminal draft differs from its durable proposal"
            )
        canonical = canonical_intake_proposal(proposal)
        usage = self._command_usage(state, runtime_execution, emitted_usage)
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
            target_proposal_source=self._target_proposal_source(execution, stored),
        ).materialize(checkpoint_ns, checkpoint_id, fence=execution.fence)
        saved = await self._saver.acommit_external_terminal(
            final_config,
            ExternalTerminalCommit(result=result, cognitive_revision=revision),
        )
        saved_fence = (saved.get("configurable") or {}).get(FENCE_CONTEXT_KEY)
        if (
            not isinstance(saved_fence, GraphFenceContext)
            or saved_fence.result_ref != result.result_ref
            or saved_fence.result_hash != result.result_hash
            or saved_fence.proposal_hash != result.proposal_hash
            or saved_fence.result_envelope_hash != result.result_envelope_hash
        ):
            raise GraphTerminalBindingError(
                "Intake generic result was not bound to the terminal fence"
            )
        if pending_usage_update is not None:
            yield self._event(
                execution,
                sequence,
                pending_usage_update.event_type,
                pending_usage_update.payload,
            )
            sequence += 1
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
    def _public_updates(candidate: Any) -> tuple[GraphPublicUpdate, ...]:
        if isinstance(candidate, GraphPublicUpdate):
            # A graph-local object is not evidence that a user-visible field came
            # from the governed model callback.  Intake must never accept a
            # bare update because doing so lets a node bypass the callback
            # provenance boundary below.
            raise GraphContractError("INTAKE_PUBLIC_UPDATE_BYPASS_FORBIDDEN")
        if not isinstance(candidate, tuple) or len(candidate) != 2:
            raise GraphContractError("compiled Intake Graph emitted an untyped update")
        mode, payload = candidate
        if mode == "custom":
            if not isinstance(payload, GraphPublicUpdate):
                raise GraphContractError("compiled Intake Graph emitted an untyped custom update")
            # ``custom`` is retained only for the bounded usage telemetry that
            # LangGraph may expose outside the message stream.  Visible content
            # has a stricter provenance requirement: it must originate in an
            # AIMessageChunk's governed callback event.
            if payload.event_type != "usage":
                raise GraphContractError("INTAKE_CUSTOM_VISIBLE_DELTA_FORBIDDEN")
            return (payload,)
        if mode != "messages":
            raise GraphContractError("compiled Intake Graph emitted an unsupported stream mode")
        if not isinstance(payload, tuple) or len(payload) != 2:
            raise GraphContractError("compiled Intake Graph emitted an invalid message update")
        chunk, metadata = payload
        if not isinstance(chunk, AIMessageChunk):
            return ()
        events = governed_events_from_chunk(chunk)
        if not events:
            # Raw model tokens, completion JSON, reasoning, and metadata are private.
            return ()
        if not isinstance(metadata, Mapping):
            raise GraphContractError("compiled Intake Graph message metadata is invalid")
        updates: list[GraphPublicUpdate] = []
        for event in events:
            if (
                event.get("schema_version") != "governed-model-event.v1"
                or event.get("node_name") != "intake_lcel"
                or event.get("field") not in _INTAKE_VISIBLE_FIELDS
            ):
                raise GraphContractError("compiled Intake Graph governed event is invalid")
            updates.append(
                GraphPublicUpdate.visible_delta(
                    node=event["node_name"],
                    field=event["field"],
                    delta=event["delta"],
                )
            )
        return tuple(updates)

    @staticmethod
    def _validate_public_update(update: GraphPublicUpdate) -> None:
        if update.event_type != "visible_delta":
            if update.event_type != "usage":
                raise GraphContractError("compiled Intake Graph public update is invalid")
            return
        field = update.payload.field
        delta = update.payload.delta
        if field not in _INTAKE_VISIBLE_FIELDS or not isinstance(delta, str) or not delta:
            raise GraphContractError("compiled Intake Graph visible update is invalid")
        # room_utterance is intentionally a complete JSON string.  Its decoded
        # text is validated by _validated_room_utterance_update immediately before
        # publication; do not inspect the quoted transport representation here.
        if field == "room_utterance":
            return
        if _contains_forbidden_evidence_request(delta) or (
            field == "case_detail.missing_information" and _is_evidence_material_gap(delta)
        ):
            raise GraphContractError("INTAKE_EVIDENCE_REQUEST_FORBIDDEN")

    @staticmethod
    def _validated_room_utterance_update(
        update: GraphPublicUpdate,
    ) -> GraphPublicUpdate:
        payload = update.payload
        if (
            update.event_type != "visible_delta"
            or payload.node != "intake_lcel"
            or payload.field != "room_utterance"
            or not isinstance(payload.delta, str)
        ):
            raise GraphContractError("compiled Intake Graph room utterance is invalid")
        try:
            room_utterance = json.loads(payload.delta)
        except (TypeError, json.JSONDecodeError) as error:
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID") from error
        if not isinstance(room_utterance, str) or not room_utterance.strip():
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
        if _contains_forbidden_evidence_request(room_utterance):
            raise GraphContractError("INTAKE_EVIDENCE_REQUEST_FORBIDDEN")
        return GraphPublicUpdate.visible_delta(
            node=payload.node,
            field=payload.field,
            delta=room_utterance,
        )

    @staticmethod
    def _graph_input(execution: GatewayExecution) -> Mapping[str, Any]:
        record = execution.thread_record
        if record.last_checkpoint_id is None:
            return build_intake_execution_state(execution)
        return build_intake_command_patch(execution)

    async def _load_context(
        self,
        execution: GatewayExecution,
        runtime_execution: GatewayExecution,
    ) -> IntakeTurnContext:
        command = runtime_execution.admission.command
        fresh = runtime_execution.thread_record.last_checkpoint_id is None
        if fresh and command.domain_snapshot_ref is not None and command.event_ref is not None:
            snapshot_ref = command.domain_snapshot_ref
            event_ref = command.event_ref
            # The bootstrap snapshot and event are immutable, independently
            # authorized Java-exchange reads.  Start both reads before awaiting
            # either one to avoid serial pre-model latency, but retain the legacy
            # validation/error order below: snapshot load/decode always wins over
            # event load/decode when both are invalid.  Failure or outer
            # cancellation also cancels the independent peer request.
            snapshot_task = asyncio.create_task(
                self._input_loader.load(execution, object_ref=snapshot_ref)
            )
            event_task = asyncio.create_task(
                self._input_loader.load(execution, object_ref=event_ref)
            )
            try:
                loaded_snapshot = await snapshot_task
                snapshot = decode_authorized_intake_ingress(
                    command=command,
                    loaded=loaded_snapshot,
                    object_ref=snapshot_ref,
                )
                loaded_event = await event_task
                event = decode_authorized_intake_ingress(
                    command=command,
                    loaded=loaded_event,
                    object_ref=event_ref,
                )
            except BaseException:
                # Preserve the snapshot-first contract without leaving the
                # independent peer request running after an early failure or
                # outer cancellation.
                for task in (snapshot_task, event_task):
                    if not task.done():
                        task.cancel()
                await asyncio.gather(snapshot_task, event_task, return_exceptions=True)
                raise
            if snapshot.ingress_kind != "SNAPSHOT" or event.ingress_kind != "EVENT":
                raise GraphContractError(
                    "fresh Intake command did not load its exact bootstrap inputs"
                )
            return IntakeTurnContext(
                "BOOTSTRAP_EVENT",
                {"snapshot": snapshot.ingress_payload, "event": event.ingress_payload},
            )
        loaded = await self._input_loader.load(execution)
        return decode_authorized_intake_ingress(command=command, loaded=loaded)

    @staticmethod
    def _graph_config(execution: GatewayExecution) -> RunnableConfig:
        record = execution.thread_record
        checkpoint_ns = record.last_checkpoint_ns
        checkpoint_id = record.last_checkpoint_id
        if (checkpoint_ns is None) != (checkpoint_id is None):
            raise GraphContractError(
                "durable Intake checkpoint namespace and ID must be present together"
            )
        if checkpoint_ns is not None and (
            not isinstance(checkpoint_ns, str)
            or len(checkpoint_ns) > 128
            or not isinstance(checkpoint_id, str)
            or not checkpoint_id
            or len(checkpoint_id) > 128
        ):
            raise GraphContractError("durable Intake checkpoint pointer is invalid")
        configurable: dict[str, Any] = {
            "thread_id": execution.fence.thread_id,
            "checkpoint_ns": checkpoint_ns or "",
        }
        if checkpoint_id is not None:
            configurable["checkpoint_id"] = checkpoint_id
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
        target_proposal_source: TargetE2ERoomProposalSource | None,
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
            target_proposal_source=target_proposal_source,
        )

    @staticmethod
    def _target_proposal_source(
        execution: GatewayExecution,
        stored: StoredIntakeProposal,
    ) -> TargetE2ERoomProposalSource | None:
        if execution.fence.execution_lane is GraphGatewayMode.SHADOW:
            return None
        command = execution.admission.command
        return TargetE2ERoomProposalSource(
            schema_version="target-e2e-room-proposal-source.v1",
            room_type="INTAKE",
            proposal=TargetE2ERoomProposal(
                schema_version="target-e2e-intake-proposal.v1",
                proposal_id=f"target-proposal.{stored.sha256[:32]}",
                command_id=command.command_id,
                logical_run_id=command.logical_run_id,
                attempt_id=command.attempt_id,
                payload_schema_version=stored.schema_version,
                payload_ref=f"urn:target-e2e:proposal:intake:{stored.sha256}",
                payload_hash=stored.sha256,
                terminal_class="COMPLETED",
                formal_authority=False,
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
