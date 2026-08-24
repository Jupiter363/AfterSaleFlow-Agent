"""Production owner for one exact-three parallel Intake technical stream."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Mapping, Sequence
import re
from typing import Any, Protocol, cast

import anyio
from pydantic import TypeAdapter

from app.api.graph_stream_service import GraphStreamAdmissionGate
from app.api.intake_parallel_stream import (
    ExpectedParallelFrame,
    OpenedParallelFrameStream,
    ParallelFrameStreamAuthority,
    ParallelFrameStreamProtocolError,
    ParallelFrameStreamProtocolValidator,
)
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.errors import (
    GraphContractError,
    GraphNewAgentAttemptRequiredError,
    GraphRuntimeError,
)
from app.graph_runtime.gateway import (
    AdmissionAction,
    GatewayAdmission,
    GatewayExecution,
)
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.intake_binding import decode_authorized_intake_ingress
from app.graph_runtime.intake_exchange import LoadedIntakePayload
from app.graph_runtime.intake_parallel_bundle import (
    ParallelIntakeProductionBundle,
    build_parallel_intake_production_bundle,
)
from app.graph_runtime.intake_parallel_runtime import (
    build_parallel_checkpoint_configs,
    build_parallel_technical_completion,
)
from app.graph_runtime.lease import LEASE_DURATION_SECONDS
from app.graph_runtime.ledger import AttemptStatus, TechnicalCompletionRecord
from app.graph_runtime.provider_intent import GatewayProviderCallIntentRecorder
from app.graph_runtime.recovery import RecoveryAction
from app.graph_runtime.target_e2e import VerifiedTargetE2EInvocation
from app.graphs.intake.parallel_contracts import FRAME_TYPES, ParallelFrameType
from app.graphs.intake.parallel_graph import (
    FRAME_NODE_NAMES,
    FrameSealed,
    FrameStarted,
    ParallelFrameBatchResult,
    ParallelFrameTechnicalEvent,
    ParallelIntakeFrameOrchestrator,
)
from app.harness.model_runner import HarnessModelRunner
from app.harness.prompt_composer import PromptRepository
from app.llm import bind_provider_call_intent_recorder


_EVENT_ADAPTER = TypeAdapter(ParallelFrameTechnicalEvent)
_SAFE_CODE = re.compile(r"^[A-Z][A-Z0-9_]{2,127}$")
_QUEUE_CAPACITY = 32
_RUNNER_TERMINAL = object()


class _ParallelGateway(Protocol):
    async def admit(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
    ) -> GatewayAdmission: ...

    async def inspect_recovery(self, admission: GatewayAdmission) -> Any: ...

    async def acquire_execution(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
        attempt_id: str,
    ) -> GatewayExecution: ...

    async def renew_execution(self, execution: GatewayExecution) -> Any: ...

    async def record_provider_call(
        self,
        execution: GatewayExecution,
    ) -> GatewayExecution: ...

    async def load_technical_completion(
        self,
        admission: GatewayAdmission,
    ) -> TechnicalCompletionRecord: ...

    async def complete_technical_execution(
        self,
        execution: GatewayExecution,
        *,
        completion: TechnicalCompletionRecord,
    ) -> GatewayExecution: ...

    async def finish_execution_attempt(
        self,
        execution: GatewayExecution,
        *,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> GatewayExecution: ...

    def cleanup_execution_lease(self, execution: GatewayExecution) -> None: ...


class _ParallelInputLoader(Protocol):
    async def load(
        self,
        execution: GatewayExecution,
        *,
        object_ref: Any | None = None,
    ) -> LoadedIntakePayload: ...


class _StreamingTechnicalEventSink:
    """Validate and expose each lane independently while retaining its replay log."""

    def __init__(
        self,
        *,
        validator: ParallelFrameStreamProtocolValidator,
        event_log: list[ParallelFrameTechnicalEvent],
        queue: asyncio.Queue[ParallelFrameTechnicalEvent | object],
        event_capacity: int,
    ) -> None:
        self._validator = validator
        self._event_log = event_log
        self._queue = queue
        self._event_capacity = event_capacity
        self._seals: dict[ParallelFrameType, FrameSealed] = {}

    @property
    def seals(self) -> Mapping[ParallelFrameType, FrameSealed]:
        return dict(self._seals)

    async def emit(self, event: ParallelFrameTechnicalEvent) -> None:
        if self._queue.qsize() >= self._event_capacity:
            raise GraphContractError("parallel Intake stream queue is saturated")
        typed_event = _EVENT_ADAPTER.validate_python(event)
        self._validator.accept(typed_event)
        if isinstance(typed_event, FrameSealed):
            if typed_event.frame_type in self._seals:
                raise ParallelFrameStreamProtocolError("parallel Frame seal is duplicated")
            self._seals[typed_event.frame_type] = typed_event
        self._event_log.append(typed_event)
        self._queue.put_nowait(typed_event)


class GatewayBackedParallelIntakeFrameStreamService:
    """Run the three physical Frame graphs without creating a business Graph result.

    The service owns only Python technical execution.  It emits the three deterministic
    starts before any provider call, streams lane updates independently, and exposes every
    successful lane seal immediately so Java can durably stage it without waiting for its
    siblings.  Java remains the sole owner of V4 sequencing, Frame staging, exact-three
    assembly, FINAL, RESULT_READY, and the formal Intake transaction.
    """

    def __init__(
        self,
        *,
        gateway: _ParallelGateway,
        input_loader: _ParallelInputLoader,
        prompts: PromptRepository,
        orchestrator: ParallelIntakeFrameOrchestrator,
        model_runner: HarnessModelRunner,
        provider: str,
        model: str,
        owner_id: str,
        admission_gate: GraphStreamAdmissionGate,
        lease_renewal_seconds: float = 10.0,
        queue_capacity: int = _QUEUE_CAPACITY,
    ) -> None:
        if not provider or not model:
            raise ValueError("parallel Intake provider binding must be complete")
        if not owner_id or len(owner_id) > 128:
            raise ValueError("parallel Intake owner_id must contain 1..128 characters")
        if lease_renewal_seconds <= 0 or lease_renewal_seconds >= LEASE_DURATION_SECONDS:
            raise ValueError("parallel Intake lease renewal must fit the lease window")
        if isinstance(queue_capacity, bool) or queue_capacity < 3 or queue_capacity > 256:
            raise ValueError("parallel Intake stream queue capacity is invalid")
        self._gateway = gateway
        self._input_loader = input_loader
        self._prompts = prompts
        self._orchestrator = orchestrator
        self._model_runner = model_runner
        self._provider = provider
        self._model = model
        self._owner_id = owner_id
        self._gate = admission_gate
        self._renewal_seconds = lease_renewal_seconds
        self._queue_capacity = queue_capacity

    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
    ) -> OpenedParallelFrameStream:
        token = await self._gate.enter()
        try:
            admission = await self._gateway.admit(
                command=command,
                verified_invocation=verified_invocation,
                expected_thread=expected_thread,
            )
            if admission.action is AdmissionAction.RETURN_TECHNICAL_CACHED:
                completion = await self._gateway.load_technical_completion(admission)
                authority, events = _decode_cached_completion(completion)
                stream = _replay_events(events)
            else:
                execution = await self._acquire_new_execution(admission)
                try:
                    bundle = await self._load_bundle(execution)
                except BaseException as error:
                    with anyio.CancelScope(shield=True):
                        await self._finish_failed_execution(execution, error)
                    raise
                authority = _authority_from_bundle(execution, bundle)
                stream = self._execute_live(
                    execution=execution,
                    bundle=bundle,
                    authority=authority,
                )
        except BaseException:
            await self._gate.leave(token)
            raise
        return OpenedParallelFrameStream(
            authority=authority,
            events=self._guarded(stream, token),
        )

    async def _acquire_new_execution(
        self,
        admission: GatewayAdmission,
    ) -> GatewayExecution:
        if admission.action is not AdmissionAction.ACQUIRE:
            raise GraphNewAgentAttemptRequiredError(
                "PARALLEL_INTAKE_SAME_ATTEMPT_RECOVERY_REQUIRED"
            )
        decision = await self._gateway.inspect_recovery(admission)
        if (
            decision.emit_attempt_reset
            or decision.action is not RecoveryAction.RESUME_BEFORE_MODEL
            or not decision.invoke_model
        ):
            raise GraphNewAgentAttemptRequiredError(
                "PARALLEL_INTAKE_SAME_ATTEMPT_RECOVERY_REQUIRED"
            )
        return await self._gateway.acquire_execution(
            admission,
            owner_id=self._owner_id,
            attempt_id=admission.command.attempt_id,
        )

    async def _load_bundle(
        self,
        execution: GatewayExecution,
    ) -> ParallelIntakeProductionBundle:
        command = execution.admission.command
        if command.domain_snapshot_ref is None or command.event_ref is None:
            raise GraphContractError("parallel Intake command has no exact input pair")
        snapshot_task = asyncio.create_task(
            self._input_loader.load(
                execution,
                object_ref=command.domain_snapshot_ref,
            ),
            name="intake-parallel-load-snapshot",
        )
        event_task = asyncio.create_task(
            self._input_loader.load(
                execution,
                object_ref=command.event_ref,
            ),
            name="intake-parallel-load-event",
        )
        snapshot_result, event_result = await asyncio.gather(
            snapshot_task,
            event_task,
            return_exceptions=True,
        )
        # Snapshot is the previous-state authority.  Preserve its deterministic
        # error priority even when concurrent HTTP calls finish in the other order.
        if isinstance(snapshot_result, BaseException):
            raise snapshot_result
        if isinstance(event_result, BaseException):
            raise event_result
        snapshot_context = decode_authorized_intake_ingress(
            command=command,
            loaded=snapshot_result,
            object_ref=command.domain_snapshot_ref,
        )
        event_context = decode_authorized_intake_ingress(
            command=command,
            loaded=event_result,
            object_ref=command.event_ref,
        )
        return build_parallel_intake_production_bundle(
            execution,
            snapshot_context=snapshot_context,
            event_context=event_context,
            prompts=self._prompts,
        )

    async def _execute_live(
        self,
        *,
        execution: GatewayExecution,
        bundle: ParallelIntakeProductionBundle,
        authority: ParallelFrameStreamAuthority,
    ) -> AsyncIterator[ParallelFrameTechnicalEvent]:
        requests = tuple(
            request.model_copy(update={"emit_start": False}) for request in bundle.requests
        )
        checkpoint_configs = build_parallel_checkpoint_configs(execution, requests)
        event_log: list[ParallelFrameTechnicalEvent] = []
        validator = ParallelFrameStreamProtocolValidator(authority)
        queue: asyncio.Queue[ParallelFrameTechnicalEvent | object] = asyncio.Queue(
            # Reserve one private slot for the runner sentinel.  Public events
            # remain bounded by ``_queue_capacity`` and fail closed on saturation.
            maxsize=self._queue_capacity + 1
        )
        sink = _StreamingTechnicalEventSink(
            validator=validator,
            event_log=event_log,
            queue=queue,
            event_capacity=self._queue_capacity,
        )
        recorder = GatewayProviderCallIntentRecorder(
            gateway=self._gateway,
            execution=execution,
            provider=self._provider,
            model=self._model,
            allowed_nodes=frozenset(FRAME_NODE_NAMES.values()),
        )
        heartbeat_stop = asyncio.Event()
        heartbeat = asyncio.create_task(
            self._run_heartbeat(recorder, heartbeat_stop),
            name="intake-parallel-lease-heartbeat",
        )
        runner: asyncio.Task[ParallelFrameBatchResult] | None = None
        durable_terminal = False
        try:
            # These three control frames are the public prefix.  The async generator
            # does not start the provider task until the caller resumes after all three.
            for request in requests:
                started = FrameStarted(
                    frame_set_id=request.frame_set_id,
                    run_id=request.run_id,
                    attempt_id=request.attempt_id,
                    frame_type=request.frame_type,
                    generation=request.generation,
                    frame_id=request.frame_id,
                    frame_model_input_sha256=request.model_input.frame_model_input_sha256,
                )
                validator.accept(started)
                event_log.append(started)
                yield started

            async def execute_frames() -> ParallelFrameBatchResult:
                try:
                    with bind_provider_call_intent_recorder(recorder):
                        return await self._orchestrator.execute(
                            requests,
                            agent_contexts=bundle.agent_contexts,
                            model_runner=self._model_runner,
                            event_sink=sink,
                            checkpoint_configs=checkpoint_configs,
                        )
                finally:
                    # A dedicated queue slot makes this non-blocking even if the
                    # Java reader is momentarily behind every public event.
                    queue.put_nowait(_RUNNER_TERMINAL)

            runner = asyncio.create_task(
                execute_frames(),
                name="intake-parallel-exact-three",
            )
            async for event in self._drain_live_queue(queue, heartbeat):
                yield event
            batch_result = await runner
            heartbeat_stop.set()
            await heartbeat
            heartbeat = None

            if batch_result.all_succeeded and set(batch_result.completed) == set(FRAME_TYPES):
                if set(sink.seals) != set(FRAME_TYPES):
                    raise ParallelFrameStreamProtocolError(
                        "parallel Frame batch is not exact-three sealed"
                    )
                completion = build_parallel_technical_completion(
                    recorder.execution,
                    frame_set_id=bundle.frame_set_id,
                    events=event_log,
                    batch_result=batch_result,
                )
                await self._gateway.complete_technical_execution(
                    recorder.execution,
                    completion=completion,
                )
                durable_terminal = True
            else:
                await self._gateway.finish_execution_attempt(
                    recorder.execution,
                    status=AttemptStatus.FAILED,
                    error_code="INTAKE_PARALLEL_FRAME_BATCH_FAILED",
                    error_classification="TECHNICAL_FRAME_FAILURE",
                )
                durable_terminal = True
            validator.finish()
        except BaseException as error:
            if runner is not None and not runner.done():
                runner.cancel()
                await asyncio.gather(runner, return_exceptions=True)
            if not durable_terminal:
                with anyio.CancelScope(shield=True):
                    await self._finish_failed_execution(recorder.execution, error)
            raise
        finally:
            heartbeat_stop.set()
            if heartbeat is not None:
                heartbeat.cancel()
                await asyncio.gather(heartbeat, return_exceptions=True)

    async def _drain_live_queue(
        self,
        queue: asyncio.Queue[ParallelFrameTechnicalEvent | object],
        heartbeat: asyncio.Task[None],
    ) -> AsyncIterator[ParallelFrameTechnicalEvent]:
        next_event: asyncio.Task[ParallelFrameTechnicalEvent | object] | None = None
        try:
            while True:
                next_event = asyncio.create_task(
                    queue.get(),
                    name="intake-parallel-next-event",
                )
                done, _ = await asyncio.wait(
                    {next_event, heartbeat},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if heartbeat in done:
                    heartbeat.result()
                    raise GraphContractError("parallel Intake heartbeat stopped unexpectedly")
                event = next_event.result()
                next_event = None
                if event is _RUNNER_TERMINAL:
                    return
                yield cast(ParallelFrameTechnicalEvent, event)
        finally:
            if next_event is not None:
                next_event.cancel()
                await asyncio.gather(next_event, return_exceptions=True)

    async def _run_heartbeat(
        self,
        recorder: GatewayProviderCallIntentRecorder,
        stop: asyncio.Event,
    ) -> None:
        while True:
            try:
                async with asyncio.timeout(self._renewal_seconds):
                    await stop.wait()
                return
            except TimeoutError:
                await self._gateway.renew_execution(recorder.execution)

    async def _finish_failed_execution(
        self,
        execution: GatewayExecution,
        error: BaseException,
    ) -> None:
        status = (
            AttemptStatus.CANCELLED
            if isinstance(error, (asyncio.CancelledError, GeneratorExit))
            else AttemptStatus.FAILED
        )
        await self._gateway.finish_execution_attempt(
            execution,
            status=status,
            error_code=_public_error_code(error),
            error_classification=(
                "CLIENT_STREAM_CANCELLED"
                if status is AttemptStatus.CANCELLED
                else "TECHNICAL_EXECUTION_FAILED"
            ),
        )

    async def _guarded(
        self,
        stream: AsyncIterator[ParallelFrameTechnicalEvent],
        token: Any,
    ) -> AsyncIterator[ParallelFrameTechnicalEvent]:
        try:
            async for event in stream:
                yield event
        finally:
            with anyio.CancelScope(shield=True):
                try:
                    await _close_iterator(stream)
                finally:
                    await self._gate.leave(token)


def _authority_from_bundle(
    execution: GatewayExecution,
    bundle: ParallelIntakeProductionBundle,
) -> ParallelFrameStreamAuthority:
    command = execution.admission.command
    frames = tuple(
        ExpectedParallelFrame(
            frame_type=request.frame_type,
            generation=request.generation,
            frame_id=request.frame_id,
            frame_model_input_sha256=request.model_input.frame_model_input_sha256,
            context_envelope_sha256=request.context_envelope_sha256,
            model_context_view_sha256=(
                request.model_input.common_model_context.model_context_view_sha256
            ),
        )
        for request in bundle.requests
    )
    return ParallelFrameStreamAuthority(
        frame_set_id=bundle.frame_set_id,
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        frames=cast(
            tuple[ExpectedParallelFrame, ExpectedParallelFrame, ExpectedParallelFrame],
            frames,
        ),
    )


def _decode_cached_completion(
    completion: TechnicalCompletionRecord,
) -> tuple[
    ParallelFrameStreamAuthority,
    tuple[ParallelFrameTechnicalEvent, ...],
]:
    document = completion.completion_json
    raw_events = document.get("events")
    if (
        document.get("schema_version") != "intake-parallel-technical-completion.v1"
        or not isinstance(raw_events, list)
        or not raw_events
    ):
        raise ParallelFrameStreamProtocolError("cached parallel completion is invalid")
    events = tuple(_EVENT_ADAPTER.validate_python(item) for item in raw_events)
    starts: dict[ParallelFrameType, FrameStarted] = {}
    seals: dict[ParallelFrameType, FrameSealed] = {}
    for event in events:
        if isinstance(event, FrameStarted) and event.frame_type not in starts:
            starts[event.frame_type] = event
        if isinstance(event, FrameSealed):
            seals[event.frame_type] = event
    if set(starts) != set(FRAME_TYPES) or set(seals) != set(FRAME_TYPES):
        raise ParallelFrameStreamProtocolError("cached parallel completion is not exact-three")
    authority = ParallelFrameStreamAuthority(
        frame_set_id=str(document.get("frame_set_id", "")),
        run_id=events[0].run_id,
        attempt_id=events[0].attempt_id,
        frames=cast(
            tuple[ExpectedParallelFrame, ExpectedParallelFrame, ExpectedParallelFrame],
            tuple(
                ExpectedParallelFrame(
                    frame_type=frame_type,
                    generation=starts[frame_type].generation,
                    frame_id=starts[frame_type].frame_id,
                    frame_model_input_sha256=(starts[frame_type].frame_model_input_sha256),
                    context_envelope_sha256=(seals[frame_type].context_envelope_sha256),
                    model_context_view_sha256=(seals[frame_type].model_context_view_sha256),
                )
                for frame_type in FRAME_TYPES
            ),
        ),
    )
    validator = ParallelFrameStreamProtocolValidator(authority)
    for event in events:
        validator.accept(event)
    validator.finish()
    return authority, events


async def _replay_events(
    events: Sequence[ParallelFrameTechnicalEvent],
) -> AsyncIterator[ParallelFrameTechnicalEvent]:
    for event in events:
        yield event


def _public_error_code(error: BaseException) -> str:
    code = getattr(error, "code", None)
    if isinstance(code, str) and _SAFE_CODE.fullmatch(code):
        return code
    if isinstance(error, GraphRuntimeError):
        return error.code
    return "INTAKE_PARALLEL_TECHNICAL_EXECUTION_FAILED"


async def _close_iterator(iterator: AsyncIterator[Any]) -> None:
    close = getattr(iterator, "aclose", None)
    if callable(close):
        await close()


__all__ = ["GatewayBackedParallelIntakeFrameStreamService"]
