"""Formal target-E2E Evidence turn execution over Java-frozen authority."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
from collections import deque
from collections.abc import AsyncIterator, Callable, Mapping
from contextlib import suppress
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Lock
from typing import Any, Protocol, TypedDict, cast

from langgraph.graph import END, START, StateGraph

from app.contracts.v1.codec import (
    canonical_sha256,
    canonical_sha256_omitting,
    canonicalize,
)
from app.agents.evidence_clerk.public_reply import (
    EVIDENCE_CANONICAL_OPENING,
    EVIDENCE_PUBLIC_FIELD,
    EVIDENCE_PUBLIC_NODE,
    EvidencePublicOutputPolicy,
    build_submission_observation_catalog,
    compose_evidence_opening_public_reply,
    require_relevant_parsed_observation_coverage,
    validate_public_observation_prefix,
)
from app.agents.evidence_clerk.v2_policy import EvidenceV2PublicOutputPolicy
from app.agents.evidence_clerk.v2_contracts import EvidenceTurnResultV2
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    ArtifactOperation,
    ArtifactPointer,
    ExecutionMetadata,
    SnapshotRef,
    Usage,
)
from app.graph_runtime.checkpoint import (
    ExternalTerminalCommit,
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graph_runtime.target_e2e import (
    TargetE2ERoomProposal,
    TargetE2ERoomProposalSource,
)
from app.harness.evidence_context_assembler import EvidenceContextAssembler
from app.schemas import (
    EvidenceTurnRequest,
    EvidenceTurnResult,
    PublicEvidenceObservationCoordinateProposalV1,
    PublicEvidenceObservationV1,
)
from app.streaming import (
    STREAM_EVENT_MAX_DELTA_CHARS,
    STREAM_MAX_VISIBLE_OUTPUT_CHARS,
    AgentStreamEvent as PublicStreamEvent,
    AgentStreamObserver,
    StreamVisibleDeltaEvent,
    bind_stream_observer,
)


_INVOCATION_SCHEMA = "target-e2e-evidence-turn-invocation.v2"
_PROPOSAL_PAYLOAD_SCHEMA = "target-e2e-evidence-turn-proposal.v1"
_PROPOSAL_PAYLOAD_SCHEMA_V2 = "target-e2e-evidence-turn-proposal.v2"
_OUTER_PROPOSAL_SCHEMA = "target-e2e-evidence-proposal.v1"
_OUTER_PROPOSAL_SCHEMA_V2 = "target-e2e-evidence-proposal.v2"
_PROPOSAL_SOURCE_SCHEMA = "target-e2e-room-proposal-source.v2"
_PROPOSAL_SOURCE_SCHEMA_V2 = "target-e2e-room-proposal-source.v2"
_STATE_SCHEMA = "target-e2e-evidence-turn-state.v1"
_STATE_SCHEMA_V2 = "target-e2e-evidence-turn-state.v2"
_NODE = "evidence_turn"
_MAX_VISIBLE_DELTA = 4096
_SUBMISSION_OBSERVATIONS_FIELD = "public_observations"


def _evidence_frame_id(
    *,
    command_id: str,
    attempt_id: str,
    frame_sequence: int,
    frame_type: str,
) -> str:
    """Derive the frame identity before any public text exists."""

    digest = canonical_sha256(
        {
            "command_id": command_id,
            "attempt_id": attempt_id,
            "frame_sequence": frame_sequence,
            "frame_type": frame_type,
        }
    )
    return "EFRM_" + digest[:24].upper()


def _evidence_frame_sha256(
    *,
    frame_id: str,
    frame_sequence: int,
    frame_type: str,
    header: Mapping[str, Any],
    header_sha256: str,
    public_text: str | None,
    public_text_sha256: str,
    public_text_length: int,
) -> str:
    return canonical_sha256(
        {
            "frame_id": frame_id,
            "frame_sequence": frame_sequence,
            "frame_type": frame_type,
            "header": dict(header),
            "header_sha256": header_sha256,
            "public_text": public_text,
            "public_text_sha256": public_text_sha256,
            "public_text_length": public_text_length,
        }
    )


_FORMAL_EVIDENCE_RESULT_FIELDS = frozenset(
    {
        "room_utterance",
        "memory_patch",
        "canvas_operations",
        "referenced_evidence_ids",
        "verification_suggestions",
        "authenticity_flags",
        "public_observations",
        "evidence_assessments",
        "fact_matrix_patch",
        "human_review_tasks",
        "internal_handoff",
        "liability_determined",
        "remedy_recommended",
        "knowledge_answer_mode",
        "confidence",
    }
)
_INTERNAL_EVIDENCE_RESULT_FIELDS = frozenset({"evidence_requests", "non_final"})
_PROVIDER_GOVERNED_AGENT_CONTEXT_FIELDS = (
    "prompt_profile_id",
    "model_profile_id",
    "output_schema_version",
    "policy_version",
    "guardrail_version",
    "tool_capabilities",
    "retry_budget",
    "deadline_at",
    "traceparent",
)
_INVOCATION_FIELDS = frozenset(
    {
        "schema_version",
        "logical_run_id",
        "tenant_surrogate",
        "case_id",
        "room_epoch",
        "fencing_token",
        "thread_id",
        "actor_id",
        "actor_role",
        "actor_scope_hash",
        "evidence_turn_request",
        "invocation_hash",
    }
)
_PROPOSAL_FIELDS = frozenset(
    {
        "schema_version",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "tenant_surrogate",
        "case_id",
        "room_epoch",
        "fencing_token",
        "thread_id",
        "actor_id",
        "actor_role",
        "actor_scope_hash",
        "input_hash",
        "evidence_turn_result",
        "room_utterance",
        "room_utterance_sha256",
        "usage",
        "completed_at",
        "proposal_hash",
    }
)
_STATE_FIELDS = frozenset(
    {
        "schema_version",
        "input_hash",
        "invocation_hash",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "evidence_turn_request",
        "cognitive_revision",
        "evidence_turn_result",
        "usage",
        "completed_at",
    }
)


class EvidenceTurnWorkflowPort(Protocol):
    async def arun(self, request: EvidenceTurnRequest) -> Any: ...


class EvidenceTurnObjectStore(Protocol):
    async def load(self, reference: SnapshotRef) -> bytes: ...

    async def put(
        self,
        *,
        execution: GatewayExecution,
        proposal_id: str,
        schema_version: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> str: ...


class _EvidenceTurnState(TypedDict, total=False):
    schema_version: str
    input_hash: str
    invocation_hash: str
    command_id: str
    logical_run_id: str
    attempt_id: str
    evidence_turn_request: dict[str, Any]
    cognitive_revision: int
    evidence_turn_result: dict[str, Any]
    usage: dict[str, int]
    completed_at: str


class _SubmissionObservationPublicOutputPolicy:
    """Release only complete, request-bound typed Evidence observations."""

    def __init__(self, request: EvidenceTurnRequest) -> None:
        envelope = request.context_envelope
        current_event = envelope.current_event
        if not current_event.attachment_refs:
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OBSERVATION_ATTACHMENT_SCOPE_INVALID"
            )
        self._evidence_content_authorities = tuple(
            envelope.evidence_content_authorities
        )
        self._visible_evidence = tuple(envelope.visible_evidence)
        self._attachment_refs = tuple(current_event.attachment_refs)
        self._allowed_fact_targets = tuple(
            EvidenceContextAssembler().assemble(request).working_set.allowed_fact_targets
        )
        self._case_id = envelope.case_snapshot.case_id
        self._actor_id = envelope.actor_snapshot.actor_id
        self._actor_role = envelope.actor_snapshot.actor_role
        self._authority_catalog = build_submission_observation_catalog(
            evidence_content_authorities=self._evidence_content_authorities,
            visible_evidence=self._visible_evidence,
            attachment_refs=self._attachment_refs,
            allowed_fact_targets=self._allowed_fact_targets,
            case_id=self._case_id,
            actor_id=self._actor_id,
            actor_role=self._actor_role,
        )
        self._accepted: list[PublicEvidenceObservationV1] = []
        self._visible_text = ""
        self._bootstrapped = False

    @property
    def source_observed(self) -> bool:
        return bool(self._accepted)

    @property
    def visible_text(self) -> str:
        return self._visible_text

    @property
    def accepted_observations(self) -> tuple[PublicEvidenceObservationV1, ...]:
        return tuple(self._accepted)

    def allows_node(self, operation: str, node_name: str) -> bool:
        return operation == EVIDENCE_PUBLIC_NODE and node_name == EVIDENCE_PUBLIC_NODE

    def begin(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
    ) -> tuple[str, ...]:
        self._require_terminal_field(operation, node_name, field_name)
        if self._bootstrapped:
            return ()
        self._bootstrapped = True
        self._visible_text = EVIDENCE_CANONICAL_OPENING
        return (EVIDENCE_CANONICAL_OPENING,)

    def accept(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        delta: str,
    ) -> tuple[str, ...]:
        self._require_node(operation, node_name)
        if field_name == EVIDENCE_PUBLIC_FIELD:
            # Submission terminal prose is a whole-result artifact. It cannot
            # authorize a live delta, even if a provider emits it early.
            return ()
        if field_name != _SUBMISSION_OBSERVATIONS_FIELD:
            raise GraphContractError("EVIDENCE_PUBLIC_OBSERVATION_STREAM_FIELD_INVALID")
        if not isinstance(delta, str) or not delta:
            raise GraphContractError("EVIDENCE_PUBLIC_OBSERVATION_STREAM_ITEM_INVALID")
        try:
            candidate = json.loads(delta)
        except (TypeError, ValueError) as error:
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OBSERVATION_STREAM_ITEM_INVALID"
            ) from error
        if not isinstance(candidate, Mapping):
            raise GraphContractError("EVIDENCE_PUBLIC_OBSERVATION_STREAM_ITEM_INVALID")
        try:
            proposal = PublicEvidenceObservationCoordinateProposalV1.model_validate(
                candidate
            )
            canonical = validate_public_observation_prefix(
                prior_accepted=self._accepted,
                candidate=proposal,
                evidence_content_authorities=self._evidence_content_authorities,
                visible_evidence=self._visible_evidence,
                attachment_refs=self._attachment_refs,
                allowed_fact_targets=self._allowed_fact_targets,
                case_id=self._case_id,
                actor_id=self._actor_id,
                actor_role=self._actor_role,
                authority_catalog=self._authority_catalog,
            )
        except ValueError as error:
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OBSERVATION_STREAM_ITEM_INVALID"
            ) from error
        public_text = canonical.public_text
        if (
            not isinstance(public_text, str)
            or not public_text
            or public_text != public_text.strip()
        ):
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OBSERVATION_CANONICAL_TEXT_INVALID"
            )
        self._accepted.append(canonical)
        self._visible_text += public_text
        return (public_text,)

    def finalize(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        final_text: str,
        allow_canonical_fallback: bool = False,
    ) -> tuple[str, ...]:
        self._require_terminal_field(operation, node_name, field_name)
        if not self._bootstrapped or not isinstance(final_text, str) or not final_text:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_TERMINAL_MISMATCH")
        if not final_text.startswith(self._visible_text):
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_TERMINAL_MISMATCH")
        suffix = final_text[len(self._visible_text) :]
        self._visible_text = final_text
        return (suffix,) if suffix else ()

    def require_terminal_reconciliation(self, result: EvidenceTurnResult) -> None:
        """Require the terminal result to retain exactly the streamed authority."""

        observations = self.accepted_observations
        if tuple(result.public_observations) != observations:
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OBSERVATION_TERMINAL_RECONCILIATION_INVALID"
            )
        require_relevant_parsed_observation_coverage(
            canonical_observations=observations,
            evidence_assessments=result.evidence_assessments,
            evidence_content_authorities=self._evidence_content_authorities,
            allowed_fact_targets=self._allowed_fact_targets,
        )
        by_id: dict[str, PublicEvidenceObservationV1] = {}
        for observation in observations:
            if observation.observation_id is None or observation.observation_id in by_id:
                raise GraphContractError(
                    "EVIDENCE_PUBLIC_OBSERVATION_TERMINAL_RECONCILIATION_INVALID"
                )
            by_id[observation.observation_id] = observation
        seen_ids: set[str] = set()
        for assessment in result.evidence_assessments:
            if assessment.public_observation_slots:
                raise GraphContractError(
                    "EVIDENCE_PUBLIC_OBSERVATION_TERMINAL_RECONCILIATION_INVALID"
                )
            fact_ids = {link.fact_id for link in assessment.fact_links}
            for observation_id in assessment.public_observation_ids:
                observation = by_id.get(observation_id)
                if (
                    observation is None
                    or observation_id in seen_ids
                    or observation.evidence_id != assessment.evidence_id
                    or observation.fact_id not in fact_ids
                ):
                    raise GraphContractError(
                        "EVIDENCE_PUBLIC_OBSERVATION_TERMINAL_RECONCILIATION_INVALID"
                    )
                seen_ids.add(observation_id)
        if seen_ids != set(by_id):
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OBSERVATION_TERMINAL_RECONCILIATION_INVALID"
            )

    @staticmethod
    def _require_node(operation: str, node_name: str) -> None:
        if operation != EVIDENCE_PUBLIC_NODE or node_name != EVIDENCE_PUBLIC_NODE:
            raise GraphContractError("EVIDENCE_PUBLIC_OBSERVATION_STREAM_NODE_INVALID")

    @classmethod
    def _require_terminal_field(
        cls,
        operation: str,
        node_name: str,
        field_name: str,
    ) -> None:
        cls._require_node(operation, node_name)
        if field_name != EVIDENCE_PUBLIC_FIELD:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_FIELD_INVALID")


@dataclass
class _EvidencePreviewEvent:
    event_type: str
    payload: AgentStreamPayload
    buffer_chars: int = 0

    @property
    def delta(self) -> str:
        return self.payload.delta or ""


@dataclass
class _EvidencePreviewBridge:
    provider_request: EvidenceTurnRequest
    command_id: str = ""
    attempt_id: str = ""
    policy: (
        EvidencePublicOutputPolicy
        | _SubmissionObservationPublicOutputPolicy
        | EvidenceV2PublicOutputPolicy
    ) = field(
        default_factory=EvidencePublicOutputPolicy
    )
    pending: deque[_EvidencePreviewEvent] = field(default_factory=deque)
    available: asyncio.Event = field(default_factory=asyncio.Event)
    loop: asyncio.AbstractEventLoop = field(default_factory=asyncio.get_running_loop)
    lock: Lock = field(default_factory=Lock)
    observer: AgentStreamObserver | None = None
    observed_usage: list[Any] = field(default_factory=list)
    frame_ids: dict[int, str] = field(default_factory=dict)
    frame_headers: dict[int, dict[str, Any]] = field(default_factory=dict)
    frame_delta_indexes: dict[int, int] = field(default_factory=dict)
    pending_chars: int = 0
    completed: bool = False
    wake_scheduled: bool = False

    def bind(self, observer: AgentStreamObserver) -> None:
        if self.observer is not None:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_OBSERVER_DUPLICATED")
        self.observer = observer

    def publish(self, event: PublicStreamEvent) -> None:
        if not isinstance(event, StreamVisibleDeltaEvent):
            return
        if isinstance(self.policy, EvidenceV2PublicOutputPolicy):
            projected = self._project_v3_event(event)
            with self.lock:
                next_pending_chars = self.pending_chars + len(event.delta)
                if next_pending_chars > STREAM_MAX_VISIBLE_OUTPUT_CHARS:
                    raise GraphContractError(
                        "EVIDENCE_PUBLIC_OUTPUT_BACKPRESSURE_EXCEEDED"
                    )
                self.pending.append(projected)
                self.pending_chars = next_pending_chars
                should_wake = not self.wake_scheduled
                self.wake_scheduled = True
            if should_wake:
                self._wake_consumer()
            return
        if (
            isinstance(self.policy, _SubmissionObservationPublicOutputPolicy)
            and event.field == _SUBMISSION_OBSERVATIONS_FIELD
        ):
            event = event.model_copy(update={"field": EVIDENCE_PUBLIC_FIELD})
        projected = _EvidencePreviewEvent(
            event_type="visible_delta",
            payload=AgentStreamPayload(
                node=event.node_name,
                field=event.field,
                delta=event.delta,
            ),
            buffer_chars=len(event.delta),
        )
        with self.lock:
            next_pending_chars = self.pending_chars + len(event.delta)
            if next_pending_chars > STREAM_MAX_VISIBLE_OUTPUT_CHARS:
                raise GraphContractError(
                    "EVIDENCE_PUBLIC_OUTPUT_BACKPRESSURE_EXCEEDED"
                )
            if self.pending:
                previous = self.pending[-1]
                previous_delta = previous.payload.delta or ""
                combined = previous_delta + event.delta
                if (
                    previous.event_type == "visible_delta"
                    and previous.payload.node == event.node_name
                    and previous.payload.field == event.field
                    and len(combined) <= STREAM_EVENT_MAX_DELTA_CHARS
                ):
                    self.pending[-1] = _EvidencePreviewEvent(
                        event_type="visible_delta",
                        payload=previous.payload.model_copy(
                            update={"delta": combined}
                        ),
                        buffer_chars=previous.buffer_chars + len(event.delta),
                    )
                else:
                    self.pending.append(projected)
            else:
                self.pending.append(projected)
            self.pending_chars = next_pending_chars
            should_wake = not self.wake_scheduled
            self.wake_scheduled = True
        if should_wake:
            self._wake_consumer()

    async def next_event(self) -> _EvidencePreviewEvent | None:
        while True:
            with self.lock:
                if self.pending:
                    event = self.pending.popleft()
                    self.pending_chars -= event.buffer_chars
                    return event
                if self.completed:
                    return None
                self.wake_scheduled = False
                self.available.clear()
            await self.available.wait()

    def finish(self) -> None:
        with self.lock:
            self.completed = True
            should_wake = not self.wake_scheduled
            self.wake_scheduled = True
        if should_wake:
            self._wake_consumer()

    def cancel(self) -> None:
        if self.observer is not None:
            self.observer.cancel()

    def _wake_consumer(self) -> None:
        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None
        if running_loop is self.loop:
            self.available.set()
            return
        try:
            self.loop.call_soon_threadsafe(self.available.set)
        except RuntimeError as error:
            raise GraphContractError(
                "EVIDENCE_PUBLIC_OUTPUT_LOOP_CLOSED"
            ) from error

    def _project_v3_event(
        self,
        event: StreamVisibleDeltaEvent,
    ) -> _EvidencePreviewEvent:
        parts = event.field.split(".")
        if (
            len(parts) != 3
            or parts[0] != "frame"
            or not parts[1].isascii()
            or not parts[1].isdigit()
        ):
            raise GraphContractError("EVIDENCE_V3_FRAME_EVENT_INVALID")
        sequence = int(parts[1])
        kind = parts[2]
        if kind == "header":
            if not self.command_id or not self.attempt_id:
                raise GraphContractError("EVIDENCE_V3_FRAME_IDENTITY_MISSING")
            try:
                header = json.loads(event.delta)
            except (TypeError, ValueError) as error:
                raise GraphContractError("EVIDENCE_V3_FRAME_HEADER_INVALID") from error
            if (
                not isinstance(header, dict)
                or header.get("frame_sequence") != sequence
                or not isinstance(header.get("frame_type"), str)
            ):
                raise GraphContractError("EVIDENCE_V3_FRAME_HEADER_INVALID")
            if sequence in self.frame_ids:
                raise GraphContractError("EVIDENCE_V3_FRAME_HEADER_DUPLICATED")
            frame_id = _evidence_frame_id(
                command_id=self.command_id,
                attempt_id=self.attempt_id,
                frame_sequence=sequence,
                frame_type=header["frame_type"],
            )
            self.frame_ids[sequence] = frame_id
            self.frame_headers[sequence] = header
            self.frame_delta_indexes[sequence] = 0
            return _EvidencePreviewEvent(
                event_type="public_frame_start",
                payload=AgentStreamPayload(
                    frame_id=frame_id,
                    frame_sequence=sequence,
                    frame_type=header["frame_type"],
                    public_header=header,
                ),
                buffer_chars=len(event.delta),
            )
        frame_id = self.frame_ids.get(sequence)
        header = self.frame_headers.get(sequence)
        if frame_id is None or header is None:
            raise GraphContractError("EVIDENCE_V3_FRAME_HEADER_MISSING")
        if kind == "public_text":
            delta_index = self.frame_delta_indexes[sequence]
            self.frame_delta_indexes[sequence] = delta_index + 1
            return _EvidencePreviewEvent(
                event_type="public_text_delta",
                payload=AgentStreamPayload(
                    frame_id=frame_id,
                    frame_sequence=sequence,
                    delta_index=delta_index,
                    delta=event.delta,
                ),
                buffer_chars=len(event.delta),
            )
        if kind != "end" or event.delta != "{}":
            raise GraphContractError("EVIDENCE_V3_FRAME_EVENT_INVALID")
        record = next(
            (
                item
                for item in self.policy.frame_records
                if item.get("frame_sequence") == sequence
            ),
            None,
        )
        if record is None or not record.get("ended"):
            raise GraphContractError("EVIDENCE_V3_FRAME_TERMINAL_INCOMPLETE")
        public_text = record.get("public_text")
        if not isinstance(public_text, str):
            raise GraphContractError("EVIDENCE_V3_FRAME_TEXT_INVALID")
        header_sha256 = canonical_sha256(header)
        public_text_sha256 = hashlib.sha256(public_text.encode("utf-8")).hexdigest()
        return _EvidencePreviewEvent(
            event_type="public_frame_committed",
            payload=AgentStreamPayload(
                frame_id=frame_id,
                frame_sequence=sequence,
                durable_cursor=f"v3:{self.attempt_id}:FRAME:{sequence}",
                header_sha256=header_sha256,
                public_text_sha256=public_text_sha256,
                frame_sha256=_evidence_frame_sha256(
                    frame_id=frame_id,
                    frame_sequence=sequence,
                    frame_type=str(header["frame_type"]),
                    header=header,
                    header_sha256=header_sha256,
                    public_text=public_text,
                    public_text_sha256=public_text_sha256,
                    public_text_length=len(public_text),
                ),
                public_text_chars=len(public_text),
            ),
            buffer_chars=len(event.delta),
        )


class CompiledEvidenceTurnExecutor:
    """Checkpoint one formal Clerk turn and publish only its guarded result."""

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        workflow: EvidenceTurnWorkflowPort,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if not callable(getattr(workflow, "arun", None)):
            raise GraphContractError("EVIDENCE_TURN_FORMAL_WORKFLOW_REQUIRED")
        if getattr(workflow, "protocol_version", None) != "evidence-turn-result.v3":
            raise GraphContractError("EVIDENCE_V2_FORMAL_WORKFLOW_REQUIRED")
        self._saver = saver
        self._workflow = workflow
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._preview_bridges: dict[str, _EvidencePreviewBridge] = {}
        graph = StateGraph(_EvidenceTurnState)
        graph.add_node(_NODE, self._run_formal_turn)
        graph.add_edge(START, _NODE)
        graph.add_edge(_NODE, END)
        self._graph = graph.compile(checkpointer=saver)

    async def stream(
        self,
        execution: GatewayExecution,
        *,
        store: EvidenceTurnObjectStore,
    ) -> AsyncIterator[AgentStreamEvent]:
        sequence = 0
        yield self._event(
            execution,
            sequence,
            "attempt_started",
            AgentStreamPayload(node="authorize_and_load_formal_evidence_turn"),
        )
        sequence += 1

        invocation, request, input_hash = await self._load_invocation(execution, store)
        v2_workflow = True
        initial = self._initial_state(
            execution,
            invocation=invocation,
            request=request,
            input_hash=input_hash,
            state_schema=_STATE_SCHEMA_V2 if v2_workflow else _STATE_SCHEMA,
        )
        provider_request = self._provider_governed_request(execution, request)
        logical_run_id = execution.admission.command.logical_run_id
        if logical_run_id in self._preview_bridges:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_RUN_ALREADY_ACTIVE")
        current_event = provider_request.context_envelope.current_event
        is_submission = (
            current_event.event_type == "PARTY_MESSAGE"
            and current_event.message_type == "PARTY_EVIDENCE_REFERENCE"
            and bool(current_event.attachment_refs)
        )
        bridge = _EvidencePreviewBridge(
            provider_request=provider_request,
            command_id=execution.admission.command.command_id,
            attempt_id=execution.admission.command.attempt_id,
            policy=(
                EvidenceV2PublicOutputPolicy()
                if v2_workflow
                else _SubmissionObservationPublicOutputPolicy(provider_request)
                if is_submission
                else EvidencePublicOutputPolicy()
            ),
        )
        self._preview_bridges[logical_run_id] = bridge

        def publish(event: PublicStreamEvent) -> None:
            if isinstance(event, StreamVisibleDeltaEvent):
                bridge.publish(event)
            else:
                bridge.observed_usage.append(event)

        observer = AgentStreamObserver(
            operation="evidence_turn",
            run_id=provider_request.agent_context.agent_invocation_id,
            publish=publish,
            public_output_policy=bridge.policy,
        )
        bridge.bind(observer)
        observer.begin_public_output(
            EVIDENCE_PUBLIC_NODE,
            "frames" if v2_workflow else EVIDENCE_PUBLIC_FIELD,
        )

        async def run_checkpointed_turn() -> tuple[_EvidenceTurnState, Mapping[str, Any]]:
            try:
                return await self._run_or_replay(execution, initial)
            finally:
                bridge.finish()

        run_task = asyncio.create_task(run_checkpointed_turn())
        try:
            while True:
                preview = await bridge.next_event()
                if preview is None:
                    break
                yield self._event(
                    execution,
                    sequence,
                    preview.event_type,
                    preview.payload,
                )
                sequence += 1
            state, config = await run_task
        finally:
            if not run_task.done():
                bridge.cancel()
                run_task.cancel()
                with suppress(asyncio.CancelledError):
                    await run_task
            if self._preview_bridges.get(logical_run_id) is bridge:
                self._preview_bridges.pop(logical_run_id, None)
        result: EvidenceTurnResult | EvidenceTurnResultV2
        if v2_workflow:
            result = EvidenceTurnResultV2.model_validate(state["evidence_turn_result"])
            result = self._bind_v2_frame_authority(execution, result)
        else:
            result = EvidenceTurnResult.model_validate(state["evidence_turn_result"])
        usage = self._usage(state["usage"])
        completed_at = cast(str, state["completed_at"])
        if bridge.policy.source_observed and bridge.policy.visible_text != result.room_utterance:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_TERMINAL_MISMATCH")
        proposal = (
            self._proposal_v2(
                execution=execution,
                invocation=invocation,
                input_hash=input_hash,
                result=result,
                usage=usage,
                completed_at=completed_at,
            )
            if v2_workflow
            else self._proposal(
                execution=execution,
                invocation=invocation,
                input_hash=input_hash,
                result=cast(EvidenceTurnResult, result),
                usage=usage,
                completed_at=completed_at,
            )
        )
        proposal_payload = canonicalize(proposal)
        proposal_object_hash = hashlib.sha256(proposal_payload).hexdigest()
        configurable = config.get("configurable")
        if not isinstance(configurable, Mapping):
            raise GraphContractError("EVIDENCE_TURN_CHECKPOINT_INVALID")
        checkpoint_ns = configurable.get("checkpoint_ns", "")
        checkpoint_id = configurable.get("checkpoint_id")
        if not isinstance(checkpoint_ns, str) or not isinstance(checkpoint_id, str):
            raise GraphContractError("EVIDENCE_TURN_CHECKPOINT_INVALID")
        cognitive_revision = cast(int, state["cognitive_revision"])
        await self._saver.avalidate_external_terminal_checkpoint(
            config,
            cognitive_revision=cognitive_revision,
        )
        payload_ref = await store.put(
            execution=execution,
            proposal_id=f"proposal.evidence-turn.{proposal_object_hash[:32]}",
            schema_version=(
                _PROPOSAL_PAYLOAD_SCHEMA_V2 if v2_workflow else _PROPOSAL_PAYLOAD_SCHEMA
            ),
            payload=proposal_payload,
            payload_hash=proposal_object_hash,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=cognitive_revision,
        )
        source = TargetE2ERoomProposalSource(
                schema_version=(
                    _PROPOSAL_SOURCE_SCHEMA_V2 if v2_workflow else _PROPOSAL_SOURCE_SCHEMA
                ),
                room_type="EVIDENCE",
                proposal=TargetE2ERoomProposal(
                schema_version=(
                    _OUTER_PROPOSAL_SCHEMA_V2 if v2_workflow else _OUTER_PROPOSAL_SCHEMA
                ),
                proposal_id=f"target-proposal.{proposal_object_hash[:32]}",
                command_id=execution.admission.command.command_id,
                logical_run_id=execution.admission.command.logical_run_id,
                attempt_id=execution.admission.command.attempt_id,
                payload_schema_version=(
                    _PROPOSAL_PAYLOAD_SCHEMA_V2 if v2_workflow else _PROPOSAL_PAYLOAD_SCHEMA
                ),
                payload_ref=payload_ref,
                payload_hash=proposal_object_hash,
                terminal_class="COMPLETED",
                formal_authority=False,
            ),
        )
        terminal = self._materialize_result(
            execution,
            source=source,
            artifact=ArtifactPointer(
                artifact_id=f"proposal.evidence-turn.{proposal_object_hash[:32]}",
                schema_version=(
                    _PROPOSAL_PAYLOAD_SCHEMA_V2 if v2_workflow else _PROPOSAL_PAYLOAD_SCHEMA
                ),
                uri=payload_ref,
                sha256=proposal_object_hash,
            ),
            usage=usage,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=cognitive_revision,
        )
        await self._saver.acommit_external_terminal(
            config,
            ExternalTerminalCommit(
                result=terminal,
                cognitive_revision=cognitive_revision,
            ),
        )

        if not bridge.policy.source_observed:
            if isinstance(result, EvidenceTurnResultV2):
                for replay_event in self._v2_replay_events(
                    result,
                    attempt_id=execution.admission.command.attempt_id,
                ):
                    yield self._event(
                        execution,
                        sequence,
                        replay_event.event_type,
                        replay_event.payload,
                    )
                    sequence += 1
            else:
                replay_deltas = bridge.policy.finalize(
                    operation=EVIDENCE_PUBLIC_NODE,
                    node_name=EVIDENCE_PUBLIC_NODE,
                    field_name=EVIDENCE_PUBLIC_FIELD,
                    final_text=result.room_utterance,
                    allow_canonical_fallback=True,
                )
                for replay_delta in replay_deltas:
                    for offset in range(0, len(replay_delta), _MAX_VISIBLE_DELTA):
                        yield self._event(
                            execution,
                            sequence,
                            "visible_delta",
                            AgentStreamPayload(
                                node=_NODE,
                                field="room_utterance",
                                delta=replay_delta[offset : offset + _MAX_VISIBLE_DELTA],
                            ),
                        )
                        sequence += 1
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
                final_result_ref=terminal.result_ref,
                final_result_hash=terminal.result_hash,
            ),
        )

    async def _load_invocation(
        self,
        execution: GatewayExecution,
        store: EvidenceTurnObjectStore,
    ) -> tuple[dict[str, Any], EvidenceTurnRequest, str]:
        command = execution.admission.command
        reference = command.domain_snapshot_ref
        if (
            command.room_type != "EVIDENCE"
            or command.event_ref is None
            or reference.schema_version != _INVOCATION_SCHEMA
            or execution.fence.execution_lane is not GraphGatewayMode.TARGET_E2E_CANDIDATE
        ):
            raise GraphContractError("EVIDENCE_TURN_FORMAL_INVOCATION_REQUIRED")
        payload = await store.load(reference)
        if (
            not isinstance(payload, bytes)
            or not payload
            or len(payload) != reference.size_bytes
            or hashlib.sha256(payload).hexdigest() != reference.sha256
        ):
            raise GraphContractError("EVIDENCE_TURN_INVOCATION_OBJECT_BINDING_INVALID")
        document = self._canonical_document(
            payload,
            code="EVIDENCE_TURN_INVOCATION_DOCUMENT_INVALID",
        )
        if set(document) != _INVOCATION_FIELDS or document.get("schema_version") != _INVOCATION_SCHEMA:
            raise GraphContractError("EVIDENCE_TURN_INVOCATION_DOCUMENT_INVALID")
        invocation_hash = document.get("invocation_hash")
        if not isinstance(invocation_hash, str) or not hmac.compare_digest(
            invocation_hash,
            canonical_sha256_omitting(document, "invocation_hash"),
        ):
            raise GraphContractError("EVIDENCE_TURN_INVOCATION_HASH_INVALID")
        request_document = document.get("evidence_turn_request")
        try:
            request = EvidenceTurnRequest.model_validate(request_document)
        except ValueError as error:
            raise GraphContractError("EVIDENCE_TURN_REQUEST_INVALID") from error
        self._require_invocation_binding(execution, document, request)
        return document, request, reference.sha256

    @staticmethod
    def _canonical_document(payload: bytes, *, code: str) -> dict[str, Any]:
        def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            value: dict[str, Any] = {}
            for key, item in pairs:
                if key in value:
                    raise ValueError("duplicate JSON key")
                value[key] = item
            return value

        try:
            decoded = json.loads(payload, object_pairs_hook=unique_object)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
            raise GraphContractError(code) from error
        if not isinstance(decoded, dict) or canonicalize(decoded) != payload:
            raise GraphContractError(code)
        return cast(dict[str, Any], decoded)

    @staticmethod
    def _require_invocation_binding(
        execution: GatewayExecution,
        invocation: Mapping[str, Any],
        request: EvidenceTurnRequest,
    ) -> None:
        command = execution.admission.command
        fence = execution.fence
        binding = execution.admission.binding
        actor_scope = command.actor_scope.model_dump(mode="json")
        expected = (
            command.logical_run_id,
            command.tenant_surrogate,
            command.case_id,
            command.room_epoch,
            getattr(binding, "room_fencing_token", None),
            command.thread_id,
            actor_scope["actor_id"],
            actor_scope["actor_role"],
            canonical_sha256(actor_scope),
        )
        actual = tuple(
            invocation.get(field)
            for field in (
                "logical_run_id",
                "tenant_surrogate",
                "case_id",
                "room_epoch",
                "fencing_token",
                "thread_id",
                "actor_id",
                "actor_role",
                "actor_scope_hash",
            )
        )
        envelope = request.context_envelope
        context = request.agent_context
        current_event = envelope.current_event
        capabilities = tuple(actor_scope["capabilities"])
        opening_capability = f"case:{command.case_id}:command:EVIDENCE_OPENING"
        submission_capability = f"case:{command.case_id}:command:EVIDENCE_SUBMIT"
        evidence_capabilities = (opening_capability, submission_capability)
        frozen_submission = envelope.frozen_submission
        exact_turn = capabilities == evidence_capabilities and (
            (
                current_event.event_type == "ROOM_OPENING"
                and current_event.message_type == "AGENT_MESSAGE"
                and not current_event.attachment_refs
                and frozen_submission is not None
                and frozen_submission.evidence_room_epoch == command.room_epoch
                and frozen_submission.evidence_fencing_token
                == getattr(binding, "room_fencing_token", None)
                and frozen_submission.authority.tenant_surrogate
                == command.tenant_surrogate
                and frozen_submission.authority.case_id == command.case_id
            )
            or (
                current_event.event_type == "PARTY_MESSAGE"
                and current_event.message_type == "PARTY_EVIDENCE_REFERENCE"
                and bool(current_event.attachment_refs)
            )
        )
        if (
            actual != expected
            or fence.thread_id != command.thread_id
            or fence.command_id != command.command_id
            or fence.request_hash != command.request_hash
            or fence.room_epoch != command.room_epoch
            or fence.room_fencing_token != invocation.get("fencing_token")
            or fence.tenant_surrogate != command.tenant_surrogate
            or fence.case_id != command.case_id
            or fence.room_type != "EVIDENCE"
            or context.case_id != command.case_id
            or context.room_type != "EVIDENCE"
            or context.actor_id != actor_scope["actor_id"]
            or context.actor_role != actor_scope["actor_role"]
            or envelope.case_snapshot.case_id != command.case_id
            or envelope.room_policy.room_type != "EVIDENCE"
            or envelope.actor_snapshot.actor_id != actor_scope["actor_id"]
            or envelope.actor_snapshot.actor_role != actor_scope["actor_role"]
            or not exact_turn
        ):
            raise GraphContractError("EVIDENCE_TURN_INVOCATION_BINDING_INVALID")

    @staticmethod
    def _initial_state(
        execution: GatewayExecution,
        *,
        invocation: Mapping[str, Any],
        request: EvidenceTurnRequest,
        input_hash: str,
        state_schema: str = _STATE_SCHEMA,
    ) -> _EvidenceTurnState:
        command = execution.admission.command
        return {
            "schema_version": state_schema,
            "input_hash": input_hash,
            "invocation_hash": cast(str, invocation["invocation_hash"]),
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "evidence_turn_request": request.model_dump(mode="json", by_alias=True),
            "cognitive_revision": 1,
        }

    @staticmethod
    def _provider_governed_request(
        execution: GatewayExecution,
        request: EvidenceTurnRequest,
    ) -> EvidenceTurnRequest:
        """Bind provider metadata from the current command without changing checkpoint authority."""

        command = execution.admission.command
        invocation = command.invocation_context
        context = request.agent_context
        context_document = context.model_dump(mode="python")
        context_document.update(
            {
                "prompt_profile_id": invocation.prompt_profile_id,
                "model_profile_id": invocation.model_profile_id,
                "output_schema_version": invocation.output_schema_version,
                "policy_version": invocation.policy_version,
                "guardrail_version": invocation.guardrail_version,
                "tool_capabilities": list(invocation.tool_capabilities),
                "retry_budget": command.retry_budget.model_dump(mode="python"),
                "deadline_at": command.deadline_at,
                "traceparent": command.traceparent,
            }
        )
        try:
            bound_context = type(context).model_validate(context_document)
        except ValueError as error:
            raise GraphContractError(
                "EVIDENCE_TURN_MODEL_INVOCATION_BINDING_INVALID"
            ) from error
        if any(
            field_name in context.model_fields_set
            and getattr(context, field_name) != getattr(bound_context, field_name)
            for field_name in _PROVIDER_GOVERNED_AGENT_CONTEXT_FIELDS
        ):
            raise GraphContractError(
                "EVIDENCE_TURN_MODEL_INVOCATION_BINDING_INVALID"
            )
        return request.model_copy(update={"agent_context": bound_context})

    async def _run_or_replay(
        self,
        execution: GatewayExecution,
        initial: _EvidenceTurnState,
    ) -> tuple[_EvidenceTurnState, Mapping[str, Any]]:
        config = bind_fence_context(
            {"configurable": {"thread_id": execution.fence.thread_id}},
            execution.fence,
        )
        snapshot = await self._graph.aget_state(config)
        current = getattr(snapshot, "values", None)
        if current:
            self._validate_state(cast(Mapping[str, Any], current), initial=initial)
            if current.get("evidence_turn_result") is None:
                await self._graph.ainvoke(None, config)
        else:
            await self._graph.ainvoke(initial, config)
        terminal = await self._graph.aget_state(config)
        values = getattr(terminal, "values", None)
        terminal_config = getattr(terminal, "config", None)
        if not isinstance(values, Mapping) or not isinstance(terminal_config, Mapping):
            raise GraphContractError("EVIDENCE_TURN_CHECKPOINT_INVALID")
        self._validate_state(values, initial=initial, terminal=True)
        return cast(_EvidenceTurnState, dict(values)), terminal_config

    @staticmethod
    def _validate_state(
        state: Mapping[str, Any],
        *,
        initial: Mapping[str, Any],
        terminal: bool = False,
    ) -> None:
        if set(state) - _STATE_FIELDS or any(
            state.get(field) != initial[field]
            for field in (
                "schema_version",
                "input_hash",
                "invocation_hash",
                "command_id",
                "logical_run_id",
                "attempt_id",
                "evidence_turn_request",
                "cognitive_revision",
            )
        ):
            raise GraphContractError("EVIDENCE_TURN_RECOVERY_BINDING_INVALID")
        completed = (
            state.get("evidence_turn_result"),
            state.get("usage"),
            state.get("completed_at"),
        )
        if any(value is not None for value in completed) and not all(
            value is not None for value in completed
        ):
            raise GraphContractError("EVIDENCE_TURN_RECOVERY_STATE_INVALID")
        if terminal and not all(value is not None for value in completed):
            raise GraphContractError("EVIDENCE_TURN_RECOVERY_STATE_INVALID")
        if completed[0] is not None:
            try:
                if initial.get("schema_version") == _STATE_SCHEMA_V2:
                    EvidenceTurnResultV2.model_validate(completed[0])
                else:
                    EvidenceTurnResult.model_validate(completed[0])
                CompiledEvidenceTurnExecutor._usage(completed[1])
            except ValueError as error:
                raise GraphContractError("EVIDENCE_TURN_RECOVERY_STATE_INVALID") from error
            if not isinstance(completed[2], str) or not completed[2]:
                raise GraphContractError("EVIDENCE_TURN_RECOVERY_STATE_INVALID")

    async def _run_formal_turn(self, state: _EvidenceTurnState) -> dict[str, Any]:
        request = EvidenceTurnRequest.model_validate(state["evidence_turn_request"])
        result, usage = await self._invoke_workflow(
            request,
            logical_run_id=cast(str, state["logical_run_id"]),
        )
        now = self._clock()
        if now.utcoffset() is None:
            raise GraphContractError("EVIDENCE_TURN_CLOCK_INVALID")
        completed_at = now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        return {
            "evidence_turn_result": result.model_dump(mode="json"),
            "usage": usage.model_dump(mode="json"),
            "completed_at": completed_at,
        }

    async def _invoke_workflow(
        self,
        request: EvidenceTurnRequest,
        *,
        logical_run_id: str,
    ) -> tuple[EvidenceTurnResult | EvidenceTurnResultV2, Usage]:
        bridge = self._preview_bridges.get(logical_run_id)
        if bridge is None:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_BRIDGE_UNAVAILABLE")
        provider_request = bridge.provider_request
        observer = bridge.observer
        if observer is None:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_OBSERVER_UNAVAILABLE")
        v2_workflow = getattr(self._workflow, "protocol_version", None) == "evidence-turn-result.v3"
        with bind_stream_observer(observer):
            raw_result = await self._workflow.arun(provider_request)
            result = (
                EvidenceTurnResultV2.model_validate(raw_result)
                if v2_workflow
                else EvidenceTurnResult.model_validate(raw_result)
            )
        if v2_workflow:
            if not isinstance(bridge.policy, EvidenceV2PublicOutputPolicy):
                raise GraphContractError("EVIDENCE_V2_PUBLIC_POLICY_REQUIRED")
            bridge.policy.finalize(
                operation=EVIDENCE_PUBLIC_NODE,
                node_name=EVIDENCE_PUBLIC_NODE,
                field_name="frames",
                final_text=result.room_utterance,
            )
        elif isinstance(bridge.policy, _SubmissionObservationPublicOutputPolicy):
            bridge.policy.require_terminal_reconciliation(result)
        elif (
            provider_request.context_envelope.current_event.event_type
            == "ROOM_OPENING"
            and bridge.policy.source_observed
        ):
            guarded_source_reply = bridge.policy.guarded_source_reply
            working_set = EvidenceContextAssembler().assemble(
                provider_request
            ).working_set
            expected_reply = compose_evidence_opening_public_reply(
                guarded_source_reply,
                fact_targets=working_set.allowed_fact_targets,
                evidence_requests=result.evidence_requests,
            )
            if result.room_utterance != expected_reply:
                raise GraphContractError(
                    "EVIDENCE_OPENING_PUBLIC_REPLY_BINDING_INVALID"
                )
            bridge.policy.authorize_terminal_extension(
                guarded_source_reply=guarded_source_reply,
                final_text=expected_reply,
            )
        observer.finalize_public_output(
            EVIDENCE_PUBLIC_NODE,
            "frames" if v2_workflow else EVIDENCE_PUBLIC_FIELD,
            result.room_utterance,
        )
        if observer.finalized_public_output != result.room_utterance:
            raise GraphContractError("EVIDENCE_PUBLIC_OUTPUT_TERMINAL_MISMATCH")
        observer.flush_deferred_usage()
        input_tokens = 0
        output_tokens = 0
        total_tokens = 0
        for event in bridge.observed_usage:
            if getattr(event, "type", None) != "usage":
                continue
            token_usage = getattr(event, "token_usage", None)
            if not isinstance(token_usage, Mapping):
                raise GraphContractError("EVIDENCE_TURN_USAGE_INVALID")
            current = self._observed_usage(token_usage)
            input_tokens += current.input_tokens
            output_tokens += current.output_tokens
            total_tokens += current.total_tokens
        usage = Usage(
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            total_tokens=total_tokens,
        )
        if usage.total_tokens != usage.input_tokens + usage.output_tokens:
            raise GraphContractError("EVIDENCE_TURN_USAGE_INVALID")
        return result, usage

    @staticmethod
    def _v2_replay_events(
        result: EvidenceTurnResultV2,
        *,
        attempt_id: str,
    ) -> tuple[_EvidencePreviewEvent, ...]:
        events: list[_EvidencePreviewEvent] = []
        for frame in result.frame_manifest:
            if frame.public_text is None:
                continue
            events.append(
                _EvidencePreviewEvent(
                    event_type="public_frame_start",
                    payload=AgentStreamPayload(
                        frame_id=frame.frame_id,
                        frame_sequence=frame.frame_sequence,
                        frame_type=frame.frame_type,
                        public_header=frame.header.model_dump(
                            mode="json",
                            exclude_none=True,
                            exclude_defaults=True,
                        ),
                    ),
                )
            )
            if frame.public_text:
                events.append(
                    _EvidencePreviewEvent(
                        event_type="active_frame_snapshot",
                        payload=AgentStreamPayload(
                            frame_id=frame.frame_id,
                            frame_sequence=frame.frame_sequence,
                            delta_index=0,
                            public_text=frame.public_text,
                        ),
                    )
                )
            events.append(
                _EvidencePreviewEvent(
                    event_type="public_frame_committed",
                    payload=AgentStreamPayload(
                        frame_id=frame.frame_id,
                        frame_sequence=frame.frame_sequence,
                        durable_cursor=(
                            f"v3:{attempt_id}:FRAME:{frame.frame_sequence}"
                        ),
                        header_sha256=frame.header_sha256,
                        public_text_sha256=frame.public_text_sha256,
                        frame_sha256=frame.frame_sha256,
                        public_text_chars=frame.public_text_length,
                    ),
                )
            )
        return tuple(events)

    @staticmethod
    def _observed_usage(value: Mapping[str, Any]) -> Usage:
        """Map the internal model-transport usage shape into the formal V2 contract."""

        if set(value) == {"input", "output", "total"}:
            value = {
                "input_tokens": value["input"],
                "output_tokens": value["output"],
                "total_tokens": value["total"],
            }
        elif set(value) != {"input_tokens", "output_tokens", "total_tokens"}:
            raise GraphContractError("EVIDENCE_TURN_USAGE_INVALID")
        return CompiledEvidenceTurnExecutor._usage(value)

    @staticmethod
    def _usage(value: Any) -> Usage:
        try:
            usage = Usage.model_validate(value)
        except ValueError as error:
            raise GraphContractError("EVIDENCE_TURN_USAGE_INVALID") from error
        if usage.total_tokens != usage.input_tokens + usage.output_tokens:
            raise GraphContractError("EVIDENCE_TURN_USAGE_INVALID")
        return usage

    @staticmethod
    def _bind_v2_frame_authority(
        execution: GatewayExecution,
        result: EvidenceTurnResultV2,
    ) -> EvidenceTurnResultV2:
        """Bind terminal manifest IDs to the same pre-text identity used live."""

        command = execution.admission.command
        manifest = []
        for frame in result.frame_manifest:
            frame_id = _evidence_frame_id(
                command_id=command.command_id,
                attempt_id=command.attempt_id,
                frame_sequence=frame.frame_sequence,
                frame_type=frame.frame_type,
            )
            header = frame.header.model_dump(
                mode="json",
                exclude_none=True,
                exclude_defaults=True,
            )
            manifest.append(
                frame.model_copy(
                    update={
                        "frame_id": frame_id,
                        "frame_sha256": _evidence_frame_sha256(
                            frame_id=frame_id,
                            frame_sequence=frame.frame_sequence,
                            frame_type=frame.frame_type,
                            header=header,
                            header_sha256=frame.header_sha256,
                            public_text=frame.public_text,
                            public_text_sha256=frame.public_text_sha256,
                            public_text_length=frame.public_text_length,
                        ),
                    }
                )
            )
        manifest_document = [frame.model_dump(mode="json") for frame in manifest]
        manifest_hash = canonical_sha256(manifest_document)
        return result.model_copy(
            update={
                "frame_manifest": manifest,
                "frame_manifest_sha256": manifest_hash,
            }
        )

    @staticmethod
    def _proposal(
        *,
        execution: GatewayExecution,
        invocation: Mapping[str, Any],
        input_hash: str,
        result: EvidenceTurnResult,
        usage: Usage,
        completed_at: str,
    ) -> dict[str, Any]:
        command = execution.admission.command
        result_document = result.model_dump(mode="json", by_alias=True)
        expected_internal_fields = (
            _FORMAL_EVIDENCE_RESULT_FIELDS - {"knowledge_answer_mode"}
        ) | _INTERNAL_EVIDENCE_RESULT_FIELDS
        if set(result_document) != expected_internal_fields:
            raise GraphContractError("EVIDENCE_TURN_RESULT_PROJECTION_INVALID")
        for field_name in _INTERNAL_EVIDENCE_RESULT_FIELDS:
            result_document.pop(field_name)
        result_document["knowledge_answer_mode"] = "NONE"
        room_utterance = result.room_utterance
        proposal = {
            "schema_version": _PROPOSAL_PAYLOAD_SCHEMA,
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "tenant_surrogate": invocation["tenant_surrogate"],
            "case_id": invocation["case_id"],
            "room_epoch": invocation["room_epoch"],
            "fencing_token": invocation["fencing_token"],
            "thread_id": invocation["thread_id"],
            "actor_id": invocation["actor_id"],
            "actor_role": invocation["actor_role"],
            "actor_scope_hash": invocation["actor_scope_hash"],
            "input_hash": input_hash,
            "evidence_turn_result": result_document,
            "room_utterance": room_utterance,
            "room_utterance_sha256": hashlib.sha256(
                room_utterance.encode("utf-8")
            ).hexdigest(),
            "usage": usage.model_dump(mode="json"),
            "completed_at": completed_at,
            "proposal_hash": "0" * 64,
        }
        proposal["proposal_hash"] = canonical_sha256_omitting(
            proposal,
            "proposal_hash",
        )
        if set(proposal) != _PROPOSAL_FIELDS:
            raise GraphContractError("EVIDENCE_TURN_PROPOSAL_INVALID")
        return proposal

    @staticmethod
    def _proposal_v2(
        *,
        execution: GatewayExecution,
        invocation: Mapping[str, Any],
        input_hash: str,
        result: EvidenceTurnResult | EvidenceTurnResultV2,
        usage: Usage,
        completed_at: str,
    ) -> dict[str, Any]:
        if not isinstance(result, EvidenceTurnResultV2):
            raise GraphContractError("EVIDENCE_V2_RESULT_REQUIRED")
        command = execution.admission.command
        result_document = result.model_dump(mode="json")
        proposal: dict[str, Any] = {
            "schema_version": _PROPOSAL_PAYLOAD_SCHEMA_V2,
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "tenant_surrogate": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_epoch": command.room_epoch,
            "fencing_token": execution.fence.room_fencing_token,
            "thread_id": command.thread_id,
            "actor_id": command.actor_scope.actor_id,
            "actor_role": command.actor_scope.actor_role,
            "actor_scope_hash": canonical_sha256(
                command.actor_scope.model_dump(mode="json")
            ),
            "input_hash": input_hash,
            "evidence_turn_result": result_document,
            "frame_manifest": result_document["frame_manifest"],
            "frame_manifest_sha256": result.frame_manifest_sha256,
            "room_utterance": result.room_utterance,
            "room_utterance_sha256": hashlib.sha256(
                result.room_utterance.encode("utf-8")
            ).hexdigest(),
            "usage": usage.model_dump(mode="json"),
            "completed_at": completed_at,
            "proposal_hash": "0" * 64,
        }
        proposal["proposal_hash"] = canonical_sha256_omitting(
            proposal,
            "proposal_hash",
        )
        return proposal

    @staticmethod
    def _materialize_result(
        execution: GatewayExecution,
        *,
        source: TargetE2ERoomProposalSource,
        artifact: ArtifactPointer,
        usage: Usage,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> Any:
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
            target_proposal_source=source,
        ).materialize(checkpoint_ns, checkpoint_id, fence=execution.fence)

    def _event(
        self,
        execution: GatewayExecution,
        sequence: int,
        event_type: str,
        payload: AgentStreamPayload,
    ) -> AgentStreamEvent:
        command = execution.admission.command
        return AgentStreamEvent(
            schema_version="agent-stream.v3",
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            sequence_no=sequence,
            event_type=event_type,
            audience=command.actor_scope.audience,
            occurred_at=self._clock(),
            payload=payload,
        )


__all__ = [
    "CompiledEvidenceTurnExecutor",
    "EvidenceTurnObjectStore",
    "EvidenceTurnWorkflowPort",
]
