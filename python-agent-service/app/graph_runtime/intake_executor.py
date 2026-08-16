"""Durable signed-synthetic executor for the exact ``intake.v2`` binding."""

from __future__ import annotations

import asyncio
import hashlib
import json
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from contextlib import aclosing
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
from app.graph_runtime.errors import (
    GraphContractError,
    GraphTerminalBindingError,
    IntakeExecutorDiagnosticError,
    IntakeExecutorDiagnosticStage,
    STABLE_INTAKE_GRAPH_CONTRACT_ERROR_CODES,
)
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
    INTAKE_ACTION_GATE_ACTION_STATUSES,
    INTAKE_ACTION_GATE_KEY_PREFIX,
    INTAKE_ACTION_GATE_KIND,
    INTAKE_ACTION_GATE_SCHEMA_VERSION,
    _ABSENT_RESPONDENT_ATTITUDES,
    _SUBSTANTIVE_RESPONDENT_ATTITUDES,
    _normalized_intake_room_utterance,
    _respondent_attitude_discriminator,
)
from app.graphs.intake.baseline import BASELINE_INTAKE_NODE_NAME
from app.graphs.intake.contracts import IntakeTurnProposal, RESPONDENT_OPENING_MARKER
from app.graphs.intake.runtime import IntakeRuntimeBundle
from app.graphs.intake.state import IntakeGraphStateV2, IntakeTurnContext
from app.graphs.intake.validators import validate_state
from app.model_runtime.callbacks import governed_events_from_chunk
from app.model_runtime.transports import ModelTransport
from app.streaming import (
    IncrementalVisibleJsonProjector,
    TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS,
)


_INTAKE_REPLY_FIRST_VISIBLE_FIELD_MODES = {
    spec.field: spec.value_mode for spec in TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
}
_INTAKE_ROOM_UTTERANCE_FIELD = "room_utterance"
_INTAKE_MODEL_VISIBLE_FIELD_MODES = {
    field: value_mode
    for field, value_mode in _INTAKE_REPLY_FIRST_VISIBLE_FIELD_MODES.items()
    if field != _INTAKE_ROOM_UTTERANCE_FIELD
}
_INTAKE_MODEL_VISIBLE_FIELDS = frozenset(_INTAKE_MODEL_VISIBLE_FIELD_MODES)
_INTAKE_VISIBLE_FIELDS = frozenset(_INTAKE_REPLY_FIRST_VISIBLE_FIELD_MODES)
_INTAKE_TERMINAL_DOSSIER_FIELDS = tuple(
    dict.fromkeys(
        spec.field
        for spec in TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
        if spec.value_mode == "json_value" and spec.field.count(".") == 1
    )
)
_AGENT_STREAM_DELTA_MAX_LENGTH = 4096
# Replaying a terminal, baseline-finalized Intake proposal is deliberately much
# smaller than the protocol maximum.  Python slices are code-point safe and the
# projector retains incomplete JSON escape sequences between slices, so a
# multi-byte UTF-8 character can never be split for a downstream consumer.
_TARGET_INTAKE_CANONICAL_REPLAY_SOURCE_CHUNK_LENGTH = 64
_SHA256_HEX_LENGTH = 64


def _intake_executor_diagnostic_error(
    source: GraphContractError,
    *,
    stage: IntakeExecutorDiagnosticStage,
) -> GraphContractError:
    """Bind a generic failure to one reviewed executor boundary without exposing text."""

    if (
        len(source.args) == 1
        and isinstance(source.args[0], str)
        and source.args[0] in STABLE_INTAKE_GRAPH_CONTRACT_ERROR_CODES
    ):
        return source
    return IntakeExecutorDiagnosticError(source, stage=stage)


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
        diagnostic_stage = [IntakeExecutorDiagnosticStage.GRAPH_STREAM_ADVANCE]
        source = self._stream(execution, diagnostic_stage=diagnostic_stage)
        try:
            async with aclosing(source):
                async for event in source:
                    yield event
        except GraphContractError as error:
            if type(error) is not GraphContractError:
                raise
            raise _intake_executor_diagnostic_error(
                error,
                stage=diagnostic_stage[0],
            ) from None

    async def _stream(
        self,
        execution: GatewayExecution,
        *,
        diagnostic_stage: list[IntakeExecutorDiagnosticStage],
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
        streamed_room_utterance_parts: list[str] = []
        target_visible_updates: list[GraphPublicUpdate] = []
        target_action_gate: Mapping[str, Any] | None = None
        target_gate_update_observed = False
        target_room_synthesized_from_gate = False
        room_utterance_received = False
        target_room_released = False
        target_opening_without_action_gate = context.ingress_kind in {
            "EVENT",
            "BOOTSTRAP_EVENT",
        } and self._context_is_respondent_opening(context)
        room_utterance_completed = False
        case_detail_seen_before_room_utterance = False
        target_reply_then_board = self._uses_target_reply_then_board_boundary(execution)
        target_source_turn_hash = (
            self._context_source_turn_hash(context)
            if target_reply_then_board and not target_opening_without_action_gate
            else None
        )
        stream_modes = ["messages", "custom"]
        if target_reply_then_board:
            stream_modes.append("updates")
        source = graph.astream(
            graph_input,
            config,
            context=context,
            stream_mode=stream_modes,
        )
        close = getattr(source, "aclose", None)
        if not callable(close):
            raise GraphContractError("compiled Intake Graph stream is not closable")
        try:
            async for candidate in source:
                if target_reply_then_board:
                    (
                        is_update,
                        observed_gate,
                        gated_room_utterance,
                    ) = self._target_action_gate_update(
                        candidate,
                        expected_source_turn_hash=target_source_turn_hash,
                    )
                    if is_update:
                        if observed_gate is None:
                            continue
                        target_gate_update_observed = True
                        if not observed_gate:
                            if not target_opening_without_action_gate:
                                raise GraphContractError("INTAKE_ACTION_GATE_MISSING")
                            continue
                        if target_action_gate is not None:
                            raise GraphContractError("INTAKE_ACTION_GATE_DUPLICATE")
                        target_action_gate = observed_gate
                        if room_utterance_received:
                            self._require_action_gate_matches_room(
                                gate=observed_gate,
                                room_utterance="".join(streamed_room_utterance_parts),
                            )
                        else:
                            self._require_action_gate_matches_room(
                                gate=observed_gate,
                                room_utterance=gated_room_utterance or "",
                            )
                            for room_update in self._authoritative_room_utterance_updates(
                                gated_room_utterance or ""
                            ):
                                self._validate_public_update(room_update)
                                yield self._event(
                                    execution,
                                    sequence,
                                    room_update.event_type,
                                    room_update.payload,
                                )
                                sequence += 1
                            streamed_room_utterance_parts.append(
                                gated_room_utterance or ""
                            )
                            room_utterance_received = True
                            target_room_released = True
                            target_room_synthesized_from_gate = True
                        continue
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
                    field = update.payload.field
                    if field == _INTAKE_ROOM_UTTERANCE_FIELD:
                        if (
                            room_utterance_completed
                            or case_detail_seen_before_room_utterance
                            or (target_reply_then_board and target_action_gate is not None)
                        ):
                            raise GraphContractError(
                                "INTAKE_ROOM_UTTERANCE_STREAM_ORDER_INVALID"
                            )
                        room_delta = self._streamed_room_utterance_delta(update)
                        if target_reply_then_board:
                            candidate_room_utterance = "".join(
                                (*streamed_room_utterance_parts, room_delta)
                            )
                            # A Target-visible room prefix must already be its
                            # cumulative governed form. Checking it before it reaches
                            # the room prevents a later formal proposal from silently
                            # replacing text that the user already saw.
                            if (
                                _normalized_intake_room_utterance(candidate_room_utterance)
                                != candidate_room_utterance
                            ):
                                raise GraphContractError(
                                    "INTAKE_ROOM_UTTERANCE_STREAM_NORMALIZATION_DIVERGED"
                                )
                            projected_room_utterance = room_delta
                        else:
                            candidate_room_utterance = "".join(
                                (*streamed_room_utterance_parts, room_delta)
                            )
                            # A legacy model prefix must remain identical to its
                            # normalized terminal text.
                            if (
                                _normalized_intake_room_utterance(candidate_room_utterance)
                                != candidate_room_utterance
                            ):
                                raise GraphContractError(
                                    "INTAKE_ROOM_UTTERANCE_STREAM_NORMALIZATION_DIVERGED"
                                )
                            projected_room_utterance = room_delta
                        if projected_room_utterance:
                            for room_update in self._streamed_room_utterance_updates(
                                node=update.payload.node,
                                delta=projected_room_utterance,
                            ):
                                self._validate_public_update(room_update)
                                if target_reply_then_board:
                                    # Governed room text is provisional SSE state and
                                    # may be reset by the attempt lifecycle. Publish it
                                    # immediately so provider TTFT is not coupled to the
                                    # terminal dossier/action-gate projection. The exact
                                    # full text remains gate/hash/terminal-bound below.
                                    yield self._event(
                                        execution,
                                        sequence,
                                        room_update.event_type,
                                        room_update.payload,
                                    )
                                    sequence += 1
                                    target_room_released = True
                                else:
                                    yield self._event(
                                        execution,
                                        sequence,
                                        room_update.event_type,
                                        room_update.payload,
                                    )
                                    sequence += 1
                            streamed_room_utterance_parts.append(projected_room_utterance)
                        room_utterance_received = True
                        continue
                    if not field.startswith("case_detail."):
                        raise GraphContractError(
                            "compiled Intake Graph emitted an unsupported visible field"
                        )
                    if not room_utterance_received:
                        # The Target projector uses the same root completion gate as
                        # the baseline: it cannot expose a board field until the full
                        # root ``room_utterance`` property closes.  Keep the defensive
                        # compatibility behavior here as well: suppress a malformed
                        # / legacy board-first source and let the terminal canonical
                        # fallback publish reply then board.  If the source later
                        # tries to append a room preview, the order breach below is
                        # rejected rather than silently reordering visible content.
                        case_detail_seen_before_room_utterance = True
                        continue
                    room_utterance_completed = True
                    if self._should_suppress_respondent_attitude_update(update):
                        continue
                    if update.payload.field.startswith("case_detail.dispute_core_state"):
                        # The provider-facing branch is intentionally open for
                        # incremental generation and may contain legacy aliases.
                        # Publish it only from the normalized terminal proposal.
                        continue
                    self._validate_public_update(update)
                    # The upstream Target projector opens this path only after the
                    # complete root ``room_utterance`` property closes.  Every board
                    # update remains provisional: the frontend discards it on ERROR,
                    # attempt reset, workspace change, or failed formal-readiness
                    # reconciliation.  Publish each complete governed JSON section as
                    # soon as it arrives instead of holding the board until the graph
                    # and terminal proposal finish.  String leaves arrive as real
                    # prefixes; structured sections arrive once their JSON value closes.
                    # The durable proposal and formal dossier remain authoritative only
                    # after the fenced terminal commit below succeeds.
                    if target_reply_then_board:
                        target_visible_updates.append(update)
                    else:
                        yield self._event(
                            execution,
                            sequence,
                            update.event_type,
                            update.payload,
                        )
                        sequence += 1
        except BaseException:
            diagnostic_stage[0] = IntakeExecutorDiagnosticStage.GRAPH_STREAM_ADVANCE
            try:
                await cast(Callable[[], Awaitable[None]], close)()
            except BaseException:
                diagnostic_stage[0] = IntakeExecutorDiagnosticStage.GRAPH_STREAM_CLOSE
                raise
            raise
        else:
            diagnostic_stage[0] = IntakeExecutorDiagnosticStage.GRAPH_STREAM_CLOSE
            await cast(Callable[[], Awaitable[None]], close)()

        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.TERMINAL_STATE_REHYDRATE
        if target_reply_then_board and not target_gate_update_observed:
            raise GraphContractError("INTAKE_ACTION_GATE_MISSING")
        if (
            target_reply_then_board
            and target_opening_without_action_gate
            and target_action_gate is not None
        ):
            raise GraphContractError("INTAKE_RESPONDENT_OPENING_ACTION_GATE_FORBIDDEN")
        snapshot = await graph.aget_state(self._latest_checkpoint_config(config))
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

        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.TERMINAL_PUBLIC_BINDING
        if target_reply_then_board:
            # Target's baseline-finalized proposal is the only formal room text.
            # It must already satisfy the public normalizer as an identity;
            # otherwise Target could show a different reply from the proposal it
            # is about to make durable.
            terminal_room_utterance = self._authoritative_terminal_room_utterance(
                proposal.room_utterance
            )
            if target_opening_without_action_gate:
                if state.get("route") != "respondent_opening":
                    raise GraphTerminalBindingError(
                        "Intake respondent opening lost its terminal route"
                    )
            else:
                if target_action_gate is None:
                    raise GraphTerminalBindingError("Intake Target action gate is missing")
                if room_utterance_received:
                    self._require_terminal_action_gate(
                        state=state,
                        proposal=proposal,
                        gate=target_action_gate,
                        terminal_room_utterance=terminal_room_utterance,
                    )
        else:
            terminal_room_utterance = self._normalized_terminal_room_utterance(
                proposal.room_utterance
            )
        if target_reply_then_board and room_utterance_received:
            streamed_room_utterance = "".join(streamed_room_utterance_parts)
            self._require_streamed_room_utterance_matches_terminal(
                streamed=streamed_room_utterance,
                terminal=terminal_room_utterance,
            )
            if not target_room_released and not target_opening_without_action_gate:
                raise GraphTerminalBindingError(
                    "Intake Target room utterance was not released by its action gate"
                )
            if target_room_synthesized_from_gate:
                canonical_updates = self._target_canonical_replay_updates(proposal)
                canonical_room = "".join(
                    update.payload.delta or ""
                    for update in canonical_updates
                    if update.payload.field == _INTAKE_ROOM_UTTERANCE_FIELD
                )
                self._require_streamed_room_utterance_matches_terminal(
                    streamed=canonical_room,
                    terminal=streamed_room_utterance,
                )
                target_visible_updates.extend(
                    update
                    for update in canonical_updates
                    if update.payload.field != _INTAKE_ROOM_UTTERANCE_FIELD
                )
            # The action-gated room is already public. Board updates remain
            # private until terminal equality succeeds, then release in their
            # original governed order.
            for update in target_visible_updates:
                self._validate_public_update(update)
                yield self._event(
                    execution,
                    sequence,
                    update.event_type,
                    update.payload,
                )
                sequence += 1
        elif room_utterance_received:
            self._require_streamed_room_utterance_matches_terminal(
                streamed="".join(streamed_room_utterance_parts),
                terminal=terminal_room_utterance,
            )
        elif not target_reply_then_board:
            # Compatibility for a graph/parser that does not expose model reply
            # deltas. It intentionally keeps the former terminal chunk behavior,
            # while still preserving the room-before-dossier publication order.
            for room_update in self._room_utterance_updates(terminal_room_utterance):
                self._validate_public_update(room_update)
                yield self._event(
                    execution,
                    sequence,
                    room_update.event_type,
                    room_update.payload,
                )
                sequence += 1

        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.CHECKPOINT_PREFLIGHT
        try:
            await self._saver.avalidate_external_terminal_checkpoint(
                final_config,
                cognitive_revision=revision,
            )
        except GraphContractError as error:
            if type(error) is not GraphContractError:
                raise
            raise _intake_executor_diagnostic_error(
                error,
                stage=IntakeExecutorDiagnosticStage.CHECKPOINT_PREFLIGHT,
            ) from None
        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.PROPOSAL_STORE_PUT
        try:
            stored = await self._proposal_store.put(
                execution,
                proposal=canonical,
                checkpoint_ns=checkpoint_ns,
                checkpoint_id=checkpoint_id,
                cognitive_revision=revision,
            )
        except GraphContractError as error:
            if type(error) is not GraphContractError:
                raise
            raise _intake_executor_diagnostic_error(
                error,
                stage=IntakeExecutorDiagnosticStage.PROPOSAL_STORE_PUT,
            ) from None
        if (
            stored.artifact_id != canonical.artifact_id
            or stored.schema_version != canonical.schema_version
            or stored.sha256 != canonical.sha256
            or stored.size_bytes != canonical.size_bytes
        ):
            raise GraphTerminalBindingError(
                "stored Intake proposal differs from the checkpointed proposal"
            )
        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.RESULT_MATERIALIZE
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
        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.FORMAL_COMMIT
        try:
            saved = await self._saver.acommit_external_terminal(
                final_config,
                ExternalTerminalCommit(result=result, cognitive_revision=revision),
            )
        except GraphContractError as error:
            if type(error) is not GraphContractError:
                raise
            raise _intake_executor_diagnostic_error(
                error,
                stage=IntakeExecutorDiagnosticStage.FORMAL_COMMIT,
            ) from None
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
        diagnostic_stage[0] = IntakeExecutorDiagnosticStage.TERMINAL_PUBLIC_REPLAY
        if target_reply_then_board and not room_utterance_received:
            # A parser / provider that never exposed a governed preview retains the
            # durable canonical fallback.  It is intentionally reply-first and runs
            # only after immutable proposal storage plus the fenced terminal commit.
            # When a room stream did arrive, its exact equality with the formal
            # proposal was verified above; do not append the same room content a
            # second time. The normal final-result rehydrate remains authoritative
            # for the durable dossier.
            terminal_updates = self._target_canonical_replay_updates(proposal)
        elif target_reply_then_board:
            terminal_updates = ()
        else:
            terminal_updates = self._terminal_normalized_dossier_updates(proposal)
        for update in terminal_updates:
            self._validate_public_update(update)
            yield self._event(
                execution,
                sequence,
                update.event_type,
                update.payload,
            )
            sequence += 1
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
                or event.get("node_name") != BASELINE_INTAKE_NODE_NAME
                or event.get("field") not in _INTAKE_VISIBLE_FIELDS
            ):
                raise GraphContractError("compiled Intake Graph governed event is invalid")
            field = event["field"]
            delta = event["delta"]
            if field == _INTAKE_ROOM_UTTERANCE_FIELD:
                if not delta:
                    raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
                updates.extend(
                    CompiledIntakeGraphShadowExecutor._streamed_room_utterance_updates(
                        node=event["node_name"],
                        delta=delta,
                    )
                )
                continue
            # A structured root snapshot must be a complete JSON document for the
            # frontend to parse it.  Splitting it would publish invalid fragments,
            # while allowing it through would violate AgentStreamV2's 4096-char
            # bound and abort an otherwise durable execution.  The terminal
            # projection remains authoritative and the room refreshes from it.
            if CompiledIntakeGraphShadowExecutor._is_oversized_root_dossier_snapshot(
                field,
                delta,
            ):
                continue
            if (
                _INTAKE_MODEL_VISIBLE_FIELD_MODES[field] == "string_prefix"
                and isinstance(delta, str)
                and delta
            ):
                updates.extend(
                    CompiledIntakeGraphShadowExecutor._string_prefix_dossier_updates(
                        node=event["node_name"],
                        field=field,
                        delta=delta,
                    )
                )
                continue
            updates.append(
                GraphPublicUpdate.visible_delta(
                    node=event["node_name"],
                    field=field,
                    delta=delta,
                )
            )
        return tuple(updates)

    @staticmethod
    def _target_action_gate_update(
        candidate: Any,
        *,
        expected_source_turn_hash: str | None,
    ) -> tuple[bool, Mapping[str, Any] | None, str | None]:
        """Read only the private ``intake_lcel`` completion update as a gate.

        The same atomic node patch owns both the reducer/text gate and the exact
        finalized draft text.  Returning them together lets a no-preview source
        publish its Agent-authored reply before graph terminal processing without
        trusting any later state reconstruction.
        """

        if not isinstance(candidate, tuple) or len(candidate) != 2:
            return False, None, None
        mode, payload = candidate
        if mode != "updates":
            return False, None, None
        if not isinstance(payload, Mapping):
            raise GraphContractError("INTAKE_ACTION_GATE_UPDATE_INVALID")
        node_patch = payload.get("intake_lcel")
        if node_patch is None:
            return True, None, None
        if not isinstance(node_patch, Mapping):
            raise GraphContractError("INTAKE_ACTION_GATE_UPDATE_INVALID")
        results = node_patch.get("node_results")
        if results is None:
            return True, {}, None
        if not isinstance(results, Mapping) or len(results) != 1:
            raise GraphContractError("INTAKE_ACTION_GATE_UPDATE_INVALID")
        key, gate = next(iter(results.items()))
        if (
            not isinstance(key, str)
            or not key.startswith(INTAKE_ACTION_GATE_KEY_PREFIX)
            or not isinstance(gate, Mapping)
        ):
            raise GraphContractError("INTAKE_ACTION_GATE_UPDATE_INVALID")
        CompiledIntakeGraphShadowExecutor._validate_action_gate(
            key=key,
            gate=gate,
        )
        terminal_draft = node_patch.get("terminal_draft")
        room_utterance = (
            terminal_draft.get("room_utterance")
            if isinstance(terminal_draft, Mapping)
            else None
        )
        conversation_action = (
            terminal_draft.get("conversation_action")
            if isinstance(terminal_draft, Mapping)
            else None
        )
        if (
            not isinstance(room_utterance, str)
            or not room_utterance
            or conversation_action not in INTAKE_ACTION_GATE_ACTION_STATUSES
        ):
            raise GraphContractError("INTAKE_ACTION_GATE_UPDATE_INVALID")
        if gate.get("source_turn_hash") != expected_source_turn_hash:
            raise GraphContractError("INTAKE_ACTION_GATE_SOURCE_MISMATCH")
        if gate.get("conversation_action") != conversation_action:
            raise GraphContractError("INTAKE_ACTION_GATE_ACTION_MISMATCH")
        return True, gate, room_utterance

    @staticmethod
    def _validate_action_gate(*, key: str, gate: Mapping[str, Any]) -> None:
        expected_fields = {
            "schema_version",
            "kind",
            "conversation_action",
            "reducer_status",
            "room_utterance_sha256",
            "source_turn_hash",
        }
        action = gate.get("conversation_action")
        source_turn_hash = gate.get("source_turn_hash")
        room_sha256 = gate.get("room_utterance_sha256")
        if (
            set(gate) != expected_fields
            or gate.get("schema_version") != INTAKE_ACTION_GATE_SCHEMA_VERSION
            or gate.get("kind") != INTAKE_ACTION_GATE_KIND
            or action not in INTAKE_ACTION_GATE_ACTION_STATUSES
            or gate.get("reducer_status")
            not in INTAKE_ACTION_GATE_ACTION_STATUSES.get(action, frozenset())
            or not CompiledIntakeGraphShadowExecutor._is_sha256(source_turn_hash)
            or not CompiledIntakeGraphShadowExecutor._is_sha256(room_sha256)
            or key != INTAKE_ACTION_GATE_KEY_PREFIX + cast(str, source_turn_hash)
        ):
            raise GraphContractError("INTAKE_ACTION_GATE_INVALID")

    @staticmethod
    def _require_action_gate_matches_room(
        *,
        gate: Mapping[str, Any],
        room_utterance: str,
    ) -> None:
        if (
            not room_utterance
            or hashlib.sha256(room_utterance.encode("utf-8")).hexdigest()
            != gate.get("room_utterance_sha256")
        ):
            raise GraphContractError("INTAKE_ACTION_GATE_ROOM_MISMATCH")

    @classmethod
    def _require_terminal_action_gate(
        cls,
        *,
        state: Mapping[str, Any],
        proposal: IntakeTurnProposal,
        gate: Mapping[str, Any] | None,
        terminal_room_utterance: str,
    ) -> None:
        if gate is None:
            raise GraphTerminalBindingError("Intake Target action gate is missing")
        source_turn_hash = state.get("last_event_hash") or state.get(
            "initial_snapshot_hash"
        )
        if (
            gate.get("source_turn_hash") != source_turn_hash
            or gate.get("conversation_action") != proposal.conversation_action
            or gate.get("room_utterance_sha256")
            != hashlib.sha256(terminal_room_utterance.encode("utf-8")).hexdigest()
        ):
            raise GraphTerminalBindingError(
                "Intake Target action gate differs from terminal authority"
            )
        action = gate.get("conversation_action")
        dossier = proposal.dossier_patch.model_dump(
            mode="python",
            exclude_none=True,
            exclude_unset=True,
        )
        actor = state.get("bindings", {}).get("private", {}).get("audience")
        party_state = dossier.get("party_intake_state")
        entry = party_state.get(actor) if isinstance(party_state, Mapping) else None
        notes = entry.get("handoff_notes") if isinstance(entry, Mapping) else None
        reducer_status = notes.get("remark_status") if isinstance(notes, Mapping) else None
        if reducer_status not in INTAKE_ACTION_GATE_ACTION_STATUSES.get(
            action,
            frozenset(),
        ):
            raise GraphTerminalBindingError(
                "Intake Target action gate differs from terminal reducer state"
            )

    @staticmethod
    def _is_sha256(value: object) -> bool:
        return (
            isinstance(value, str)
            and len(value) == _SHA256_HEX_LENGTH
            and all(character in "0123456789abcdef" for character in value)
        )

    @staticmethod
    def _validate_public_update(
        update: GraphPublicUpdate,
    ) -> None:
        if update.event_type != "visible_delta":
            if update.event_type != "usage":
                raise GraphContractError("compiled Intake Graph public update is invalid")
            return
        field = update.payload.field
        delta = update.payload.delta
        if field not in _INTAKE_VISIBLE_FIELDS or not isinstance(delta, str) or not delta:
            raise GraphContractError("compiled Intake Graph visible update is invalid")
        # Every live room reply is cumulatively checked against its governed
        # terminal proposal before any durable commit.  Target candidates retain
        # their reply-first stream, but cannot commit a different formal reply.
        if field == "room_utterance":
            return

    @staticmethod
    def _validated_room_utterance_update(
        update: GraphPublicUpdate,
    ) -> GraphPublicUpdate:
        payload = update.payload
        if (
            update.event_type != "visible_delta"
            or payload.node != BASELINE_INTAKE_NODE_NAME
            or payload.field != "room_utterance"
            or not isinstance(payload.delta, str)
        ):
            raise GraphContractError("compiled Intake Graph room utterance is invalid")
        room_utterance = payload.delta
        if not isinstance(room_utterance, str) or not room_utterance.strip():
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
        room_utterance = _normalized_intake_room_utterance(room_utterance)
        return GraphPublicUpdate.visible_delta(
            node=payload.node,
            field=payload.field,
            delta=room_utterance,
        )

    @staticmethod
    def _streamed_room_utterance_delta(update: GraphPublicUpdate) -> str:
        """Validate a model reply prefix without rewriting its incremental text.

        Rewriting one chunk independently would make a later terminal equality
        check meaningless.  The caller instead normalizes the accumulated prefix
        and fail-closes if a replacement would be needed.
        """

        payload = update.payload
        if (
            update.event_type != "visible_delta"
            or payload.node != BASELINE_INTAKE_NODE_NAME
            or payload.field != _INTAKE_ROOM_UTTERANCE_FIELD
            or not isinstance(payload.delta, str)
            or not payload.delta
        ):
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
        return payload.delta

    @staticmethod
    def _streamed_room_utterance_updates(
        *,
        node: str,
        delta: str,
    ) -> tuple[GraphPublicUpdate, ...]:
        """Bound a raw model reply prefix without normalizing it per chunk."""

        if node != BASELINE_INTAKE_NODE_NAME or not isinstance(delta, str) or not delta:
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
        return tuple(
            GraphPublicUpdate.visible_delta(
                node=node,
                field=_INTAKE_ROOM_UTTERANCE_FIELD,
                # Python slices operate on Unicode code points, so no UTF-8 byte
                # sequence can be cut in half. The terminal comparison covers the
                # exact concatenated string, not just each fragment.
                delta=delta[offset : offset + _AGENT_STREAM_DELTA_MAX_LENGTH],
            )
            for offset in range(0, len(delta), _AGENT_STREAM_DELTA_MAX_LENGTH)
        )

    @staticmethod
    def _normalized_terminal_room_utterance(room_utterance: object) -> str:
        if not isinstance(room_utterance, str) or not room_utterance.strip():
            raise GraphTerminalBindingError("Intake terminal room utterance is invalid")
        return _normalized_intake_room_utterance(room_utterance)

    @staticmethod
    def _uses_target_reply_then_board_boundary(execution: GatewayExecution) -> bool:
        """Select Target's reply-first stream and canonical no-preview fallback."""

        return execution.fence.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE

    @staticmethod
    def _context_is_respondent_opening(context: IntakeTurnContext) -> bool:
        payload = context.ingress_payload
        event = payload.get("event") if context.ingress_kind == "BOOTSTRAP_EVENT" else payload
        return (
            isinstance(event, Mapping)
            and event.get("source_type") == RESPONDENT_OPENING_MARKER
            and event.get("text") == RESPONDENT_OPENING_MARKER
        )

    @staticmethod
    def _context_source_turn_hash(context: IntakeTurnContext) -> str:
        payload = context.ingress_payload
        if context.ingress_kind == "SNAPSHOT":
            source_turn_hash = payload.get("snapshot_hash")
        else:
            event = payload.get("event") if context.ingress_kind == "BOOTSTRAP_EVENT" else payload
            source_turn_hash = event.get("event_hash") if isinstance(event, Mapping) else None
        if not CompiledIntakeGraphShadowExecutor._is_sha256(source_turn_hash):
            raise GraphContractError("INTAKE_ACTION_GATE_SOURCE_INVALID")
        return cast(str, source_turn_hash)

    @staticmethod
    def _authoritative_terminal_room_utterance(room_utterance: object) -> str:
        """Validate, but never rewrite, a baseline-finalized Target reply."""

        if not isinstance(room_utterance, str) or not room_utterance.strip():
            raise GraphTerminalBindingError("Intake terminal room utterance is invalid")
        if _normalized_intake_room_utterance(room_utterance) != room_utterance:
            raise GraphTerminalBindingError(
                "Intake terminal room utterance requires normalization"
            )
        return room_utterance

    @staticmethod
    def _require_streamed_room_utterance_matches_terminal(
        *,
        streamed: str,
        terminal: str,
    ) -> None:
        if not streamed or streamed != terminal:
            raise GraphTerminalBindingError(
                "Intake streamed room utterance differs from normalized terminal proposal"
            )

    @staticmethod
    def _room_utterance_updates(room_utterance: str) -> tuple[GraphPublicUpdate, ...]:
        """Publish the already-guarded baseline reply in bounded Unicode chunks."""

        if not isinstance(room_utterance, str) or not room_utterance.strip():
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
        normalized = _normalized_intake_room_utterance(room_utterance)
        return tuple(
            GraphPublicUpdate.visible_delta(
                node=BASELINE_INTAKE_NODE_NAME,
                field="room_utterance",
                # Python slices operate on Unicode code points, so no UTF-8 byte
                # sequence can be cut in half.  The frontend reassembles these
                # ordered deltas before applying its baseline typewriter pacing.
                delta=normalized[offset : offset + _AGENT_STREAM_DELTA_MAX_LENGTH],
            )
            for offset in range(0, len(normalized), _AGENT_STREAM_DELTA_MAX_LENGTH)
        )

    @staticmethod
    def _authoritative_room_utterance_updates(
        room_utterance: str,
    ) -> tuple[GraphPublicUpdate, ...]:
        """Chunk a durable target reply exactly as it was finalized by baseline."""

        # The complete terminal reply is checked as nonblank before replay.  A
        # later source slice may legitimately contain only whitespace, and must
        # still be published verbatim to preserve the finalizer's exact text.
        if not isinstance(room_utterance, str) or not room_utterance:
            raise GraphContractError("INTAKE_ROOM_UTTERANCE_STREAM_INVALID")
        return tuple(
            GraphPublicUpdate.visible_delta(
                node=BASELINE_INTAKE_NODE_NAME,
                field="room_utterance",
                # Every chunk stays within AgentStreamV2's delta bound while their
                # concatenation remains byte-for-character equal to the durable
                # terminal proposal.
                delta=room_utterance[offset : offset + _AGENT_STREAM_DELTA_MAX_LENGTH],
            )
            for offset in range(0, len(room_utterance), _AGENT_STREAM_DELTA_MAX_LENGTH)
        )

    @classmethod
    def _target_canonical_replay_updates(
        cls,
        proposal: IntakeTurnProposal,
    ) -> tuple[GraphPublicUpdate, ...]:
        """Replay a committed proposal only when Target had no governed preview.

        The normal Target path exposes a governed reply-first stream whose
        concatenated room text must exactly match the terminal proposal before it
        can commit. This fallback is reserved for parsers/providers that produced
        no room text at all; after immutable storage and the fenced commit, it
        projects the baseline-finalized proposal in the same reply-first shape so
        legacy source gaps cannot leave the room blank.
        """

        document = cls._target_canonical_replay_document(proposal)
        projector = IncrementalVisibleJsonProjector(
            TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
        )
        updates: list[GraphPublicUpdate] = []
        replayed_room_parts: list[str] = []
        terminal_room_utterance = cls._authoritative_terminal_room_utterance(
            proposal.room_utterance
        )

        for offset in range(
            0,
            len(document),
            _TARGET_INTAKE_CANONICAL_REPLAY_SOURCE_CHUNK_LENGTH,
        ):
            source_chunk = document[
                offset : offset + _TARGET_INTAKE_CANONICAL_REPLAY_SOURCE_CHUNK_LENGTH
            ]
            for field, delta in projector.feed(source_chunk):
                if field == _INTAKE_ROOM_UTTERANCE_FIELD:
                    room_updates = cls._authoritative_room_utterance_updates(delta)
                    updates.extend(room_updates)
                    replayed_room_parts.append(delta)
                    continue
                # The projector's root gate must never disclose a dossier value
                # until the complete, exact finalizer reply has been replayed.
                if "".join(replayed_room_parts) != terminal_room_utterance:
                    raise GraphContractError(
                        "INTAKE_TARGET_REPLY_FIRST_REPLAY_ORDER_INVALID"
                    )
                updates.extend(
                    cls._target_canonical_dossier_field_updates(field=field, delta=delta)
                )

        if "".join(replayed_room_parts) != terminal_room_utterance:
            raise GraphTerminalBindingError(
                "Intake terminal room utterance was not fully replayed"
            )
        return tuple(updates)

    @staticmethod
    def _target_canonical_replay_document(proposal: IntakeTurnProposal) -> str:
        """Serialize a room-first canonical public object without changing values."""

        room_utterance = (
            CompiledIntakeGraphShadowExecutor._authoritative_terminal_room_utterance(
                proposal.room_utterance
            )
        )
        try:
            dossier = proposal.dossier_patch.model_dump(
                mode="json",
                exclude_none=True,
                exclude_unset=True,
            )
        except (AttributeError, TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "Intake terminal dossier patch is invalid"
            ) from error
        if not isinstance(dossier, Mapping):
            raise GraphTerminalBindingError("Intake terminal dossier patch is invalid")

        # Keep the object shape deterministic and root-gate friendly.  The outer
        # key order is intentionally not sorted: ``room_utterance`` must remain
        # first.  ``DossierPatch.model_dump`` preserves the baseline schema field
        # order, which is also the historical board section order; re-sorting it
        # alphabetically would alter that presentation contract.
        try:
            room_json = json.dumps(
                room_utterance,
                ensure_ascii=False,
                separators=(",", ":"),
            )
            dossier_json = json.dumps(
                dict(dossier),
                ensure_ascii=False,
                separators=(",", ":"),
            )
        except (TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "Intake terminal dossier patch is not JSON serializable"
            ) from error
        return f'{{"room_utterance":{room_json},"case_detail":{dossier_json}}}'

    @staticmethod
    def _target_canonical_dossier_field_updates(
        *,
        field: str,
        delta: str,
    ) -> tuple[GraphPublicUpdate, ...]:
        """Translate projector deltas without allowing a huge root snapshot."""

        value_mode = _INTAKE_REPLY_FIRST_VISIBLE_FIELD_MODES.get(field)
        if value_mode is None or field == _INTAKE_ROOM_UTTERANCE_FIELD:
            raise GraphContractError("INTAKE_TARGET_CANONICAL_REPLAY_FIELD_INVALID")
        if value_mode == "string_prefix":
            return CompiledIntakeGraphShadowExecutor._string_prefix_dossier_updates(
                node=BASELINE_INTAKE_NODE_NAME,
                field=field,
                delta=delta,
            )
        if value_mode != "json_value":
            raise GraphContractError("INTAKE_TARGET_CANONICAL_REPLAY_FIELD_INVALID")
        if CompiledIntakeGraphShadowExecutor._is_oversized_root_dossier_snapshot(
            field,
            delta,
        ):
            # A root JSON snapshot is only meaningful as one complete document.
            # Retain previously replayed leaf prefixes; the normal terminal
            # result refresh remains the authority for this oversized branch.
            return ()
        return (
            GraphPublicUpdate.visible_delta(
                node=BASELINE_INTAKE_NODE_NAME,
                field=field,
                delta=delta,
            ),
        )

    @staticmethod
    def _string_prefix_dossier_updates(
        *,
        node: str,
        field: str,
        delta: str,
    ) -> tuple[GraphPublicUpdate, ...]:
        """Keep a streamable dossier leaf within AgentStreamV2's text bound."""

        return tuple(
            GraphPublicUpdate.visible_delta(
                node=node,
                field=field,
                # This is a textual prefix, unlike a root JSON snapshot, so the
                # client can safely append each Unicode-code-point chunk.
                delta=delta[offset : offset + _AGENT_STREAM_DELTA_MAX_LENGTH],
            )
            for offset in range(0, len(delta), _AGENT_STREAM_DELTA_MAX_LENGTH)
        )

    @staticmethod
    def _is_oversized_root_dossier_snapshot(field: object, delta: object) -> bool:
        return (
            isinstance(field, str)
            and isinstance(delta, str)
            and field.startswith("case_detail.")
            and field.count(".") == 1
            and _INTAKE_MODEL_VISIBLE_FIELD_MODES.get(field) == "json_value"
            and len(delta) > _AGENT_STREAM_DELTA_MAX_LENGTH
        )

    @staticmethod
    def _should_suppress_respondent_attitude_update(update: GraphPublicUpdate) -> bool:
        payload = update.payload
        if payload.field.startswith("case_detail.respondent_attitude."):
            return True
        if payload.field != "case_detail.respondent_attitude":
            return False
        try:
            attitude = json.loads(payload.delta or "")
        except (TypeError, json.JSONDecodeError) as error:
            raise GraphContractError("INTAKE_RESPONDENT_ATTITUDE_STREAM_INVALID") from error
        if not isinstance(attitude, Mapping):
            raise GraphContractError("INTAKE_RESPONDENT_ATTITUDE_STREAM_INVALID")
        proposed = _respondent_attitude_discriminator(attitude)
        if proposed is None or proposed not in (
            _ABSENT_RESPONDENT_ATTITUDES | _SUBSTANTIVE_RESPONDENT_ATTITUDES
        ):
            raise GraphContractError("INTAKE_RESPONDENT_ATTITUDE_STREAM_INVALID")
        # This branch depends on source attribution and therefore cannot be
        # trusted from a partial model stream.  Silence aliases never belong in
        # the dossier, while a substantive attitude becomes visible only after
        # the terminal source gate and formal Java commit succeed.
        return True

    @staticmethod
    def _terminal_normalized_dossier_updates(
        proposal: IntakeTurnProposal,
    ) -> tuple[GraphPublicUpdate, ...]:
        dossier = proposal.dossier_patch.model_dump(
            mode="json",
            exclude_none=True,
            exclude_unset=True,
        )
        updates: list[GraphPublicUpdate] = []
        for field in _INTAKE_TERMINAL_DOSSIER_FIELDS:
            branch_name = field.removeprefix("case_detail.")
            branch = dossier.get(branch_name)
            if not isinstance(branch, Mapping):
                continue
            delta = json.dumps(
                branch,
                ensure_ascii=False,
                separators=(",", ":"),
            )
            if len(delta) > _AGENT_STREAM_DELTA_MAX_LENGTH:
                # Root JSON cannot be emitted piecemeal because the client parses
                # every visible delta independently.  Do not let an oversized
                # optional live snapshot turn a successful terminal commit into a
                # failed stream; the authoritative terminal dossier is reloaded by
                # the normal final-result refresh instead.
                continue
            updates.append(
                GraphPublicUpdate.visible_delta(
                    node=BASELINE_INTAKE_NODE_NAME,
                    field=field,
                    delta=delta,
                )
            )
        return tuple(updates)

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
    def _latest_checkpoint_config(config: RunnableConfig) -> RunnableConfig:
        """Query the post-stream checkpoint head without changing the stream pointer."""

        configurable = dict(config["configurable"])
        configurable.pop("checkpoint_id", None)
        return cast(RunnableConfig, {**config, "configurable": configurable})

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
