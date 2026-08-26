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
    ParallelFrameAdmissionReceipt,
    ParallelFrameFailureTerminationReceipt,
    ParallelFrameStreamAuthority,
    ParallelFrameStreamProtocolError,
    ParallelFrameStreamProtocolValidator,
    parallel_frame_authority_sha256,
)
from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.bulkhead import GraphBulkheadScope, GraphPermitFenceContext
from app.graph_runtime.errors import (
    GraphContractError,
    GraphNewAgentAttemptRequiredError,
    GraphRuntimeError,
)
from app.graph_runtime.gateway import (
    AdmissionAction,
    GatewayAdmission,
    GatewayExecution,
    ParallelUncommittedFailureTerminal,
)
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.intake_binding import decode_authorized_intake_ingress
from app.graph_runtime.intake_exchange import LoadedIntakePayload
from app.graph_runtime.intake_parallel_bundle import (
    ParallelIntakeProductionBundle,
    build_parallel_intake_prepared_bundle,
    build_parallel_intake_production_bundle,
)
from app.graph_runtime.intake_parallel_runtime import (
    build_parallel_checkpoint_configs,
    build_parallel_technical_completion,
)
from app.graph_runtime.lease import LEASE_DURATION_SECONDS
from app.graph_runtime.ledger import (
    AttemptStatus,
    ParallelReceiptCycleRecord,
    TechnicalCompletionRecord,
)
from app.graph_runtime.provider_intent import GatewayProviderCallIntentRecorder
from app.graph_runtime.recovery import RecoveryAction
from app.graph_runtime.target_e2e import VerifiedTargetE2EInvocation
from app.graphs.intake.parallel_contracts import FRAME_TYPES, ParallelFrameType
from app.graphs.intake.parallel_graph import (
    FRAME_NODE_NAMES,
    FrameInterrupted,
    FrameSealed,
    FrameStarted,
    ParallelFrameBatchResult,
    ParallelFrameExecutionRequest,
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
_SUBSET_TECHNICAL_COMPLETION_SCHEMA = "intake-parallel-technical-completion.v2"


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

    async def resume_parallel_technical_execution(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
        attempt_id: str,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
        admission_receipt: Mapping[str, Any],
    ) -> GatewayExecution: ...

    async def bind_parallel_receipt_execution(
        self,
        execution: GatewayExecution,
        *,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
    ) -> GatewayExecution: ...

    async def load_parallel_receipt_cycle(
        self,
        admission: GatewayAdmission,
        *,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
    ) -> ParallelReceiptCycleRecord | None: ...

    async def complete_parallel_receipt_cycle(
        self,
        execution: GatewayExecution,
        *,
        cycle: ParallelReceiptCycleRecord,
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

    async def terminalize_parallel_uncommitted_failure(
        self,
        admission: GatewayAdmission,
        *,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
        failure_code: str,
    ) -> ParallelUncommittedFailureTerminal: ...

    def cleanup_execution_lease(self, execution: GatewayExecution) -> None: ...


class _ParallelInputLoader(Protocol):
    async def load(
        self,
        execution: GatewayExecution,
        *,
        object_ref: Any | None = None,
    ) -> LoadedIntakePayload: ...

    async def load_bound(
        self,
        command: RoomGraphCommand,
        *,
        thread: ThreadIdentity,
        object_ref: Any | None = None,
    ) -> LoadedIntakePayload: ...


class _ProviderGroupPermit(Protocol):
    @property
    def renewal_interval_seconds(self) -> float: ...

    @property
    def released(self) -> bool: ...

    async def renew(self) -> Any: ...

    async def release(self) -> bool: ...


class _ProviderGroupBulkhead(Protocol):
    async def acquire(
        self,
        scope: GraphBulkheadScope,
        fence: GraphPermitFenceContext,
        *,
        request_id: str,
        owner_id: str,
        takeover: bool,
        permit_count: int,
    ) -> _ProviderGroupPermit: ...

    async def terminalize_command_permits(
        self,
        *,
        thread_id: str,
        command_id: str,
        frame_set_id: str,
    ) -> tuple[Any, ...]: ...


class _StreamingTechnicalEventSink:
    """Validate and expose each lane independently while retaining its replay log."""

    def __init__(
        self,
        *,
        validator: ParallelFrameStreamProtocolValidator,
        event_log: list[ParallelFrameTechnicalEvent],
        queue: "_FairFrameMergeQueue",
    ) -> None:
        self._validator = validator
        self._event_log = event_log
        self._queue = queue
        self._seals: dict[ParallelFrameType, FrameSealed] = {}

    @property
    def seals(self) -> Mapping[ParallelFrameType, FrameSealed]:
        return dict(self._seals)

    async def emit(self, event: ParallelFrameTechnicalEvent) -> None:
        typed_event = _EVENT_ADAPTER.validate_python(event)
        self._validator.accept(typed_event)
        if isinstance(typed_event, FrameSealed):
            if typed_event.frame_type in self._seals:
                raise ParallelFrameStreamProtocolError("parallel Frame seal is duplicated")
            self._seals[typed_event.frame_type] = typed_event
        self._event_log.append(typed_event)
        await self._queue.put(typed_event)


class _FairFrameMergeQueue:
    """Bound each lane independently and drain active lanes round-robin.

    A single shared FIFO lets a fast Dialogue or Dossier provider consume every
    buffered slot before either sibling gets one. Each Frame therefore owns the
    same bounded quota while one cursor selects the next non-empty lane. The
    transport remains single-writer, but producer skew cannot starve a sibling.
    """

    def __init__(self, *, per_frame_capacity: int) -> None:
        if per_frame_capacity < 1:
            raise ValueError("parallel Intake per-Frame queue capacity is invalid")
        self._per_frame_capacity = per_frame_capacity
        self._lanes: dict[
            ParallelFrameType, asyncio.Queue[ParallelFrameTechnicalEvent]
        ] = {
            frame_type: asyncio.Queue(maxsize=per_frame_capacity)
            for frame_type in FRAME_TYPES
        }
        self._available = asyncio.Event()
        self._closed = False
        self._next_lane = 0

    async def put(self, event: ParallelFrameTechnicalEvent) -> None:
        if self._closed:
            raise GraphContractError("parallel Intake stream queue is closed")
        lane = self._lanes[event.frame_type]
        await lane.put(event)
        if self._closed:
            raise GraphContractError("parallel Intake stream queue closed during publish")
        self._available.set()

    def close(self) -> None:
        self._closed = True
        self._available.set()

    async def get(self) -> ParallelFrameTechnicalEvent | object:
        while True:
            for offset in range(len(FRAME_TYPES)):
                lane_index = (self._next_lane + offset) % len(FRAME_TYPES)
                frame_type = FRAME_TYPES[lane_index]
                lane = self._lanes[frame_type]
                if not lane.empty():
                    self._next_lane = (lane_index + 1) % len(FRAME_TYPES)
                    return lane.get_nowait()
            if self._closed:
                return _RUNNER_TERMINAL
            self._available.clear()
            await self._available.wait()


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
        provider_bulkhead: _ProviderGroupBulkhead,
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
        self._provider_bulkhead = provider_bulkhead
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
        admission_receipt: ParallelFrameAdmissionReceipt,
    ) -> OpenedParallelFrameStream:
        if (
            verified_invocation.claims.parallel_phase != "EXECUTE"
            or verified_invocation.claims.parallel_admission_receipt_sha256
            != admission_receipt.receipt_sha256
        ):
            raise GraphContractError(
                "parallel Intake receipt differs from its verified invocation"
            )
        token = await self._gate.enter()
        try:
            prepared_bundle = await self._load_prepared_bundle(
                command,
                expected_thread,
                room_fencing_token=verified_invocation.room_fencing_token,
            )
            prepared_authority = _authority_from_command_bundle(
                command,
                prepared_bundle,
            )
            authority = admission_receipt.require_authority(
                command=command,
                authority=prepared_authority,
            )
            active_frame_types = tuple(
                lane.frame_type
                for lane in admission_receipt.lanes
                if lane.action != "SKIP_SEALED"
            )
            if not active_frame_types:
                raise ParallelFrameStreamProtocolError(
                    "all-sealed execution must be completed from Java durable state"
                )
            admission = await self._gateway.admit(
                command=command,
                verified_invocation=verified_invocation,
                expected_thread=expected_thread,
            )
            receipt_cycle = await self._gateway.load_parallel_receipt_cycle(
                admission,
                frame_set_id=admission_receipt.frame_set_id,
                receipt_sha256=admission_receipt.receipt_sha256,
                authority_sha256=admission_receipt.authority_sha256,
            )
            if receipt_cycle is not None:
                events = _decode_parallel_receipt_cycle(
                    receipt_cycle,
                    authority=authority,
                    admission_receipt=admission_receipt,
                    active_frame_types=active_frame_types,
                )
                stream = _replay_failed_receipt_cycle(
                    events,
                    error_code=receipt_cycle.terminal_error_code,
                )
            elif admission.action is AdmissionAction.RETURN_TECHNICAL_CACHED:
                completion = await self._gateway.load_technical_completion(admission)
                events = _decode_cached_completion(
                    completion,
                    authority=authority,
                    admission_receipt=admission_receipt,
                    active_frame_types=active_frame_types,
                )
                stream = _replay_events(events)
            else:
                execution = await self._acquire_new_execution(
                    admission,
                    authority=authority,
                    admission_receipt=admission_receipt,
                )
                try:
                    bundle = await self._load_bundle(execution)
                    if _authority_from_bundle(execution, bundle) != prepared_authority:
                        raise ParallelFrameStreamProtocolError(
                            "parallel live bundle differs from its prepared authority"
                        )
                    selected_requests = _select_execution_requests(
                        bundle,
                        admission_receipt,
                    )
                except BaseException as error:
                    with anyio.CancelScope(shield=True):
                        await self._finish_failed_execution(execution, error)
                    raise
                stream = self._execute_live(
                    execution=execution,
                    bundle=bundle,
                    authority=authority,
                    admission_receipt=admission_receipt,
                    selected_requests=selected_requests,
                )
        except BaseException:
            await self._gate.leave(token)
            raise
        return OpenedParallelFrameStream(
            authority=authority,
            active_frame_types=active_frame_types,
            events=self._guarded(stream, token),
        )

    async def prepare(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
    ) -> ParallelFrameStreamAuthority:
        if verified_invocation.request_hash != command.request_hash:
            raise GraphContractError("parallel Intake preparation crossed invocation authority")
        token = await self._gate.enter()
        try:
            bundle = await self._load_prepared_bundle(
                command,
                expected_thread,
                room_fencing_token=verified_invocation.room_fencing_token,
            )
            return _authority_from_command_bundle(command, bundle)
        finally:
            await self._gate.leave(token)

    async def terminate_uncommitted_failure(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
        admission_receipt: ParallelFrameAdmissionReceipt,
        failure_code: str,
    ) -> ParallelFrameFailureTerminationReceipt:
        if (
            verified_invocation.claims.parallel_phase != "TERMINATE"
            or verified_invocation.claims.parallel_admission_receipt_sha256
            != admission_receipt.receipt_sha256
            or verified_invocation.claims.parallel_failure_code != failure_code
            or _SAFE_CODE.fullmatch(failure_code) is None
        ):
            raise GraphContractError(
                "parallel failure termination differs from its verified invocation"
            )
        token = await self._gate.enter()
        try:
            prepared_bundle = await self._load_prepared_bundle(
                command,
                expected_thread,
                room_fencing_token=verified_invocation.room_fencing_token,
            )
            authority = admission_receipt.require_authority(
                command=command,
                authority=_authority_from_command_bundle(command, prepared_bundle),
            )
            admission = await self._gateway.admit(
                command=command,
                verified_invocation=verified_invocation,
                expected_thread=expected_thread,
            )
            terminal = await self._gateway.terminalize_parallel_uncommitted_failure(
                admission,
                frame_set_id=authority.frame_set_id,
                receipt_sha256=admission_receipt.receipt_sha256,
                authority_sha256=admission_receipt.authority_sha256,
                failure_code=failure_code,
            )
            permits = await self._provider_bulkhead.terminalize_command_permits(
                thread_id=command.thread_id,
                command_id=command.command_id,
                frame_set_id=authority.frame_set_id,
            )
            permit_statuses = tuple(sorted(str(permit.status) for permit in permits))
            return ParallelFrameFailureTerminationReceipt.create(
                request_hash=command.request_hash,
                frame_set_id=authority.frame_set_id,
                run_id=command.logical_run_id,
                attempt_id=command.attempt_id,
                command_id=command.command_id,
                admission_receipt_sha256=admission_receipt.receipt_sha256,
                requested_failure_code=failure_code,
                graph_command_status=cast(Any, terminal.command_status.value),
                graph_attempt_status=cast(
                    Any,
                    "ABSENT"
                    if terminal.attempt_status is None
                    else terminal.attempt_status.value,
                ),
                graph_error_code=terminal.error_code,
                graph_error_classification=terminal.error_classification,
                provider_permit_statuses=permit_statuses,
            )
        finally:
            await self._gate.leave(token)

    async def _acquire_new_execution(
        self,
        admission: GatewayAdmission,
        *,
        authority: ParallelFrameStreamAuthority,
        admission_receipt: ParallelFrameAdmissionReceipt,
    ) -> GatewayExecution:
        if admission.action is AdmissionAction.OBSERVE_OR_TAKEOVER:
            return await self._gateway.resume_parallel_technical_execution(
                admission,
                owner_id=self._owner_id,
                attempt_id=admission.command.attempt_id,
                frame_set_id=authority.frame_set_id,
                receipt_sha256=admission_receipt.receipt_sha256,
                authority_sha256=admission_receipt.authority_sha256,
                admission_receipt=admission_receipt.canonical_document(),
            )
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
        execution = await self._gateway.acquire_execution(
            admission,
            owner_id=self._owner_id,
            attempt_id=admission.command.attempt_id,
        )
        return await self._gateway.bind_parallel_receipt_execution(
            execution,
            frame_set_id=authority.frame_set_id,
            receipt_sha256=admission_receipt.receipt_sha256,
            authority_sha256=admission_receipt.authority_sha256,
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

    async def _load_prepared_bundle(
        self,
        command: RoomGraphCommand,
        expected_thread: ThreadIdentity,
        *,
        room_fencing_token: int,
    ) -> ParallelIntakeProductionBundle:
        if command.domain_snapshot_ref is None or command.event_ref is None:
            raise GraphContractError("parallel Intake command has no exact input pair")
        snapshot_task = asyncio.create_task(
            self._input_loader.load_bound(
                command,
                thread=expected_thread,
                object_ref=command.domain_snapshot_ref,
            ),
            name="intake-parallel-prepare-snapshot",
        )
        event_task = asyncio.create_task(
            self._input_loader.load_bound(
                command,
                thread=expected_thread,
                object_ref=command.event_ref,
            ),
            name="intake-parallel-prepare-event",
        )
        snapshot_result, event_result = await asyncio.gather(
            snapshot_task,
            event_task,
            return_exceptions=True,
        )
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
        return build_parallel_intake_prepared_bundle(
            command,
            thread=expected_thread,
            room_fencing_token=room_fencing_token,
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
        admission_receipt: ParallelFrameAdmissionReceipt,
        selected_requests: tuple[ParallelFrameExecutionRequest, ...],
    ) -> AsyncIterator[ParallelFrameTechnicalEvent]:
        heartbeat_stop = asyncio.Event()
        heartbeat: asyncio.Task[None] | None = None
        provider_permit: _ProviderGroupPermit | None = None
        provider_permit_heartbeat: asyncio.Task[None] | None = None
        provider_permit_acquire: asyncio.Task[_ProviderGroupPermit] | None = None
        runner: asyncio.Task[ParallelFrameBatchResult] | None = None
        durable_terminal = False
        try:
            requests = tuple(
                request.model_copy(update={"emit_start": False})
                for request in selected_requests
            )
            checkpoint_configs = build_parallel_checkpoint_configs(execution, requests)
            event_log: list[ParallelFrameTechnicalEvent] = []
            active_frame_types = tuple(request.frame_type for request in requests)
            validator = ParallelFrameStreamProtocolValidator(
                authority,
                active_frame_types,
            )
            queue = _FairFrameMergeQueue(
                # This is a per-Frame quota. Total memory remains bounded by the
                # fixed exact-three topology while one noisy lane cannot consume a
                # sibling's capacity. Producers wait when their own lane is full;
                # a valid maximum-cardinality Frame is never rejected for scheduler skew.
                per_frame_capacity=self._queue_capacity
            )
            sink = _StreamingTechnicalEventSink(
                validator=validator,
                event_log=event_log,
                queue=queue,
            )
            recorder = GatewayProviderCallIntentRecorder(
                gateway=self._gateway,
                execution=execution,
                provider=self._provider,
                model=self._model,
                allowed_nodes=frozenset(FRAME_NODE_NAMES.values()),
            )
            provider_scope = _provider_group_scope(execution, bundle.frame_set_id)
            provider_fence = _provider_group_fence(execution)
            provider_request_id = _provider_group_request_id(
                execution,
                authority=authority,
                admission_receipt=admission_receipt,
                requests=requests,
            )
            provider_owner_id = _provider_group_owner_id(
                execution,
                service_owner_id=self._owner_id,
                admission_receipt=admission_receipt,
            )
            heartbeat = asyncio.create_task(
                self._run_heartbeat(recorder, heartbeat_stop),
                name="intake-parallel-lease-heartbeat",
            )
            provider_permit_acquire = asyncio.create_task(
                self._provider_bulkhead.acquire(
                    provider_scope,
                    provider_fence,
                    request_id=provider_request_id,
                    owner_id=provider_owner_id,
                    takeover=True,
                    permit_count=len(requests),
                ),
                name="intake-parallel-provider-group-acquire",
            )
            acquired, _ = await asyncio.wait(
                {provider_permit_acquire, heartbeat},
                return_when=asyncio.FIRST_COMPLETED,
            )
            if heartbeat in acquired:
                if (
                    provider_permit_acquire.done()
                    and not provider_permit_acquire.cancelled()
                    and provider_permit_acquire.exception() is None
                ):
                    provider_permit = provider_permit_acquire.result()
                heartbeat.result()
                raise GraphContractError(
                    "parallel Intake heartbeat stopped before provider admission"
                )
            provider_permit = provider_permit_acquire.result()
            provider_permit_heartbeat = asyncio.create_task(
                self._run_provider_permit_heartbeat(
                    provider_permit,
                    heartbeat_stop,
                ),
                name="intake-parallel-provider-group-heartbeat",
            )
            heartbeats = (heartbeat, provider_permit_heartbeat)

            # These three control frames are the public prefix.  The async generator
            # does not start the provider task until the caller resumes after all three.
            for request in requests:
                _raise_if_heartbeat_stopped(heartbeats)
                started = FrameStarted(
                    frame_set_id=request.frame_set_id,
                    run_id=request.run_id,
                    attempt_id=request.attempt_id,
                    frame_type=request.frame_type,
                    generation=request.generation,
                    frame_id=request.frame_id,
                    frame_model_input_sha256=request.model_input.frame_model_input_sha256,
                    frame_prompt_sha256=(
                        request.model_input.instruction_pack.frame_prompt_sha256
                    ),
                    context_envelope_sha256=request.context_envelope_sha256,
                    model_context_view_sha256=(
                        request.model_input.common_model_context.model_context_view_sha256
                    ),
                )
                validator.accept(started)
                event_log.append(started)
                yield started

            _raise_if_heartbeat_stopped(heartbeats)

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
                    queue.close()

            runner = asyncio.create_task(
                execute_frames(),
                name="intake-parallel-active-subset",
            )
            async for event in self._drain_live_queue(queue, heartbeats):
                yield event
            batch_result = await runner
            _raise_if_heartbeat_stopped(heartbeats)
            heartbeat_stop.set()
            await heartbeat
            heartbeat = None

            if batch_result.all_succeeded and set(batch_result.completed) == set(
                active_frame_types
            ):
                if set(sink.seals) != set(active_frame_types):
                    raise ParallelFrameStreamProtocolError(
                        "parallel Frame batch is not active-subset sealed"
                    )
                validator.finish()
                if set(active_frame_types) == set(FRAME_TYPES):
                    completion = build_parallel_technical_completion(
                        recorder.execution,
                        frame_set_id=bundle.frame_set_id,
                        events=event_log,
                        batch_result=batch_result,
                    )
                else:
                    completion = _build_subset_technical_completion(
                        recorder.execution,
                        authority=authority,
                        admission_receipt=admission_receipt,
                        events=event_log,
                        batch_result=batch_result,
                    )
                await self._gateway.complete_technical_execution(
                    recorder.execution,
                    completion=completion,
                )
                durable_terminal = True
            else:
                if _batch_failure_is_retryable(
                    batch_result,
                    event_log,
                    active_frame_types=active_frame_types,
                ):
                    validator.finish()
                    cycle = ParallelReceiptCycleRecord.create(
                        thread_id=recorder.execution.fence.thread_id,
                        command_id=recorder.execution.fence.command_id,
                        request_hash=recorder.execution.fence.request_hash,
                        attempt_id=recorder.execution.attempt.attempt_id,
                        frame_set_id=authority.frame_set_id,
                        receipt_sha256=admission_receipt.receipt_sha256,
                        authority_sha256=admission_receipt.authority_sha256,
                        admission_receipt=admission_receipt.canonical_document(),
                        canonical_events=tuple(
                            _EVENT_ADAPTER.dump_python(event, mode="json")
                            for event in event_log
                        ),
                        terminal_error_code="INTAKE_PARALLEL_FRAME_BATCH_FAILED",
                        terminal_retryable=True,
                        provider_call_count_before=execution.attempt.provider_call_count,
                        provider_call_count_after=(
                            recorder.execution.attempt.provider_call_count
                        ),
                        owner_id=recorder.execution.fence.owner_id,
                        fencing_token=recorder.execution.fence.fencing_token,
                    )
                    await self._gateway.complete_parallel_receipt_cycle(
                        recorder.execution,
                        cycle=cycle,
                    )
                else:
                    await self._gateway.finish_execution_attempt(
                        recorder.execution,
                        status=AttemptStatus.FAILED,
                        error_code="INTAKE_PARALLEL_FRAME_BATCH_FAILED",
                        error_classification="TECHNICAL_FRAME_FAILURE",
                    )
                durable_terminal = True
                raise GraphContractError("INTAKE_PARALLEL_FRAME_BATCH_FAILED")
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
            if provider_permit_acquire is not None:
                if not provider_permit_acquire.done():
                    provider_permit_acquire.cancel()
                acquire_result = (
                    await asyncio.gather(provider_permit_acquire, return_exceptions=True)
                )[0]
                if provider_permit is None and not isinstance(acquire_result, BaseException):
                    provider_permit = cast(_ProviderGroupPermit, acquire_result)
            if heartbeat is not None:
                heartbeat.cancel()
                await asyncio.gather(heartbeat, return_exceptions=True)
            if provider_permit_heartbeat is not None:
                provider_permit_heartbeat.cancel()
                await asyncio.gather(
                    provider_permit_heartbeat,
                    return_exceptions=True,
                )
            if provider_permit is not None and not provider_permit.released:
                with anyio.CancelScope(shield=True):
                    await provider_permit.release()

    async def _drain_live_queue(
        self,
        queue: _FairFrameMergeQueue,
        heartbeats: tuple[asyncio.Task[None], ...],
    ) -> AsyncIterator[ParallelFrameTechnicalEvent]:
        next_event: asyncio.Task[ParallelFrameTechnicalEvent | object] | None = None
        try:
            while True:
                next_event = asyncio.create_task(
                    queue.get(),
                    name="intake-parallel-next-event",
                )
                done, _ = await asyncio.wait(
                    {next_event, *heartbeats},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                stopped = tuple(heartbeat for heartbeat in heartbeats if heartbeat in done)
                if stopped:
                    for heartbeat in stopped:
                        heartbeat.result()
                    raise GraphContractError(
                        "parallel Intake heartbeat stopped unexpectedly"
                    )
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

    async def _run_provider_permit_heartbeat(
        self,
        permit: _ProviderGroupPermit,
        stop: asyncio.Event,
    ) -> None:
        while True:
            try:
                async with asyncio.timeout(permit.renewal_interval_seconds):
                    await stop.wait()
                return
            except TimeoutError:
                await permit.renew()

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
    return _authority_from_command_bundle(execution.admission.command, bundle)


def _authority_from_command_bundle(
    command: RoomGraphCommand,
    bundle: ParallelIntakeProductionBundle,
) -> ParallelFrameStreamAuthority:
    frames = tuple(
        ExpectedParallelFrame(
            frame_type=request.frame_type,
            generation=request.generation,
            frame_id=request.frame_id,
            frame_model_input_sha256=request.model_input.frame_model_input_sha256,
            frame_prompt_sha256=(
                request.model_input.instruction_pack.frame_prompt_sha256
            ),
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


def _select_execution_requests(
    bundle: ParallelIntakeProductionBundle,
    admission_receipt: ParallelFrameAdmissionReceipt,
) -> tuple[ParallelFrameExecutionRequest, ...]:
    requests = {request.frame_type: request for request in bundle.requests}
    if set(requests) != set(FRAME_TYPES):
        raise ParallelFrameStreamProtocolError(
            "parallel execution bundle is not exact-three"
        )
    selected: list[ParallelFrameExecutionRequest] = []
    for lane in admission_receipt.lanes:
        prepared = requests[lane.frame_type]
        if lane.action == "SKIP_SEALED":
            continue
        if lane.next_local_index != 0:
            raise ParallelFrameStreamProtocolError(
                "parallel execution plan cannot resume a partial public projection"
            )
        selected.append(
            ParallelFrameExecutionRequest.model_validate(
                {
                    **prepared.model_dump(mode="python"),
                    "generation": lane.generation,
                    "frame_id": lane.frame_id,
                    "resume_generation": None,
                    "resume_frame_id": None,
                    "resume_local_index": 0,
                    "allow_generation_reset": lane.action != "RUN_RETRY",
                }
            )
        )
    if not selected:
        raise ParallelFrameStreamProtocolError(
            "all-sealed execution must be completed from Java durable state"
        )
    return tuple(selected)


def _provider_group_scope(
    execution: GatewayExecution,
    frame_set_id: str,
) -> GraphBulkheadScope:
    command = execution.admission.command
    return GraphBulkheadScope.from_graph_identity(
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=str(command.room_type),
        room_epoch=command.room_epoch,
        item_key=frame_set_id,
    )


def _provider_group_fence(execution: GatewayExecution) -> GraphPermitFenceContext:
    return GraphPermitFenceContext(
        thread_id=execution.fence.thread_id,
        command_id=execution.fence.command_id,
        graph_lease_owner_id=execution.fence.owner_id,
        graph_lease_fencing_token=execution.fence.fencing_token,
    )


def _provider_group_request_id(
    execution: GatewayExecution,
    *,
    authority: ParallelFrameStreamAuthority,
    admission_receipt: ParallelFrameAdmissionReceipt,
    requests: Sequence[ParallelFrameExecutionRequest],
) -> str:
    digest = canonical_sha256(
        {
            "schema_version": "intake.parallel-provider-group.v1",
            "thread_id": execution.fence.thread_id,
            "command_id": execution.fence.command_id,
            "request_hash": execution.fence.request_hash,
            "attempt_id": execution.attempt.attempt_id,
            "frame_set_id": authority.frame_set_id,
            "authority_sha256": parallel_frame_authority_sha256(authority),
            "admission_receipt_sha256": admission_receipt.receipt_sha256,
            "frames": [
                {
                    "frame_type": request.frame_type,
                    "generation": request.generation,
                    "frame_id": request.frame_id,
                    "frame_model_input_sha256": (
                        request.model_input.frame_model_input_sha256
                    ),
                }
                for request in sorted(
                    requests,
                    key=lambda candidate: FRAME_TYPES.index(candidate.frame_type),
                )
            ],
        }
    )
    return f"provider-group:{digest}"


def _provider_group_owner_id(
    execution: GatewayExecution,
    *,
    service_owner_id: str,
    admission_receipt: ParallelFrameAdmissionReceipt,
) -> str:
    digest = canonical_sha256(
        {
            "schema_version": "intake.parallel-provider-group-owner.v1",
            "service_owner_id": service_owner_id,
            "thread_id": execution.fence.thread_id,
            "command_id": execution.fence.command_id,
            "graph_lease_owner_id": execution.fence.owner_id,
            "graph_lease_fencing_token": execution.fence.fencing_token,
            "admission_receipt_sha256": admission_receipt.receipt_sha256,
        }
    )
    return f"provider-worker:{digest}"


def _raise_if_heartbeat_stopped(
    heartbeats: tuple[asyncio.Task[None], ...],
) -> None:
    stopped = tuple(heartbeat for heartbeat in heartbeats if heartbeat.done())
    if not stopped:
        return
    for heartbeat in stopped:
        heartbeat.result()
    raise GraphContractError("parallel Intake heartbeat stopped unexpectedly")


def _build_subset_technical_completion(
    execution: GatewayExecution,
    *,
    authority: ParallelFrameStreamAuthority,
    admission_receipt: ParallelFrameAdmissionReceipt,
    events: Sequence[ParallelFrameTechnicalEvent],
    batch_result: ParallelFrameBatchResult,
) -> TechnicalCompletionRecord:
    active_frame_types = tuple(
        frame_type for frame_type in FRAME_TYPES if frame_type in batch_result.completed
    )
    if (
        not batch_result.all_succeeded
        or not active_frame_types
        or set(active_frame_types) == set(FRAME_TYPES)
        or not events
    ):
        raise ParallelFrameStreamProtocolError(
            "parallel subset completion requires a proper successful Frame subset"
        )
    command = execution.admission.command
    sealed: dict[ParallelFrameType, FrameSealed] = {}
    event_documents: list[dict[str, Any]] = []
    for event in events:
        if (
            event.frame_type not in active_frame_types
            or event.frame_set_id != authority.frame_set_id
            or event.run_id != authority.run_id
            or event.attempt_id != authority.attempt_id
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel subset event crossed its execution receipt"
            )
        if isinstance(event, FrameSealed):
            if event.frame_type in sealed:
                raise ParallelFrameStreamProtocolError(
                    "parallel subset completion repeats a Frame seal"
                )
            sealed[event.frame_type] = event
        event_documents.append(event.model_dump(mode="json", exclude_none=True))
    if set(sealed) != set(active_frame_types):
        raise ParallelFrameStreamProtocolError(
            "parallel subset completion is not sealed"
        )

    frames: list[dict[str, Any]] = []
    for lane in admission_receipt.lanes:
        if lane.action == "SKIP_SEALED":
            result_sha256 = lane.result_sha256
            public_projection_sha256 = lane.public_projection_sha256
        else:
            result = batch_result.completed.get(lane.frame_type)
            frame_seal = sealed.get(lane.frame_type)
            if (
                result is None
                or frame_seal is None
                or result.generation != lane.generation
                or result.frame_id != lane.frame_id
                or result.result_sha256 != frame_seal.result_sha256
                or result.public_projection_sha256
                != frame_seal.public_projection_sha256
            ):
                raise ParallelFrameStreamProtocolError(
                    "parallel subset result differs from its sealed Frame"
                )
            result_sha256 = result.result_sha256
            public_projection_sha256 = result.public_projection_sha256
        if result_sha256 is None or public_projection_sha256 is None:
            raise ParallelFrameStreamProtocolError(
                "parallel subset completion lost a sibling result proof"
            )
        frames.append(
            {
                "frame_type": lane.frame_type,
                "generation": lane.generation,
                "frame_id": lane.frame_id,
                "action": lane.action,
                "result_sha256": result_sha256,
                "public_projection_sha256": public_projection_sha256,
            }
        )

    completion_id = "IPTC_" + canonical_sha256(
        {
            "contract_version": _SUBSET_TECHNICAL_COMPLETION_SCHEMA,
            "thread_id": execution.fence.thread_id,
            "command_id": execution.fence.command_id,
            "attempt_id": execution.attempt.attempt_id,
            "admission_receipt_sha256": admission_receipt.receipt_sha256,
        }
    )[:32]
    document: dict[str, Any] = {
        "schema_version": _SUBSET_TECHNICAL_COMPLETION_SCHEMA,
        "completion_id": completion_id,
        "thread_id": execution.fence.thread_id,
        "command_id": execution.fence.command_id,
        "request_hash": execution.fence.request_hash,
        "attempt_id": execution.attempt.attempt_id,
        "fencing_token": execution.fence.fencing_token,
        "frame_set_id": authority.frame_set_id,
        "authority_sha256": parallel_frame_authority_sha256(authority),
        "admission_receipt_sha256": admission_receipt.receipt_sha256,
        "active_frame_types": list(active_frame_types),
        "events": event_documents,
        "frames": frames,
    }
    completion_hash = canonical_sha256(document)
    document["completion_hash"] = completion_hash
    return TechnicalCompletionRecord(
        completion_id=completion_id,
        thread_id=execution.fence.thread_id,
        command_id=execution.fence.command_id,
        request_hash=execution.fence.request_hash,
        attempt_id=execution.attempt.attempt_id,
        fencing_token=execution.fence.fencing_token,
        completion_schema_version=_SUBSET_TECHNICAL_COMPLETION_SCHEMA,
        completion_json=document,
        completion_hash=completion_hash,
    )


def _batch_failure_is_retryable(
    batch_result: ParallelFrameBatchResult,
    events: Sequence[ParallelFrameTechnicalEvent],
    *,
    active_frame_types: tuple[ParallelFrameType, ...],
) -> bool:
    completed = set(batch_result.completed)
    failed = set(batch_result.failed)
    active = set(active_frame_types)
    if (
        not failed
        or completed & failed
        or completed | failed != active
    ):
        return False
    terminals: dict[ParallelFrameType, FrameSealed | FrameInterrupted] = {}
    for event in events:
        if isinstance(event, (FrameSealed, FrameInterrupted)):
            terminals[event.frame_type] = event
    if set(terminals) != active:
        return False
    for frame_type in completed:
        terminal = terminals[frame_type]
        result = batch_result.completed[frame_type]
        if (
            not isinstance(terminal, FrameSealed)
            or getattr(result, "frame_type", None) != frame_type
            or getattr(result, "generation", terminal.generation) != terminal.generation
            or getattr(result, "frame_id", terminal.frame_id) != terminal.frame_id
            or getattr(result, "result_sha256", terminal.result_sha256)
            != terminal.result_sha256
            or getattr(
                result,
                "public_projection_sha256",
                terminal.public_projection_sha256,
            )
            != terminal.public_projection_sha256
        ):
            return False
    return all(
        isinstance(terminals[frame_type], FrameInterrupted)
        and terminals[frame_type].retryable
        and terminals[frame_type].generation < 2
        and terminals[frame_type].error_code
        == batch_result.failed[frame_type].error_code
        and batch_result.failed[frame_type].frame_type == frame_type
        for frame_type in failed
    )


def _decode_parallel_receipt_cycle(
    cycle: ParallelReceiptCycleRecord,
    *,
    authority: ParallelFrameStreamAuthority,
    admission_receipt: ParallelFrameAdmissionReceipt,
    active_frame_types: tuple[ParallelFrameType, ...],
) -> tuple[ParallelFrameTechnicalEvent, ...]:
    if (
        cycle.frame_set_id != authority.frame_set_id
        or cycle.receipt_sha256 != admission_receipt.receipt_sha256
        or cycle.authority_sha256 != admission_receipt.authority_sha256
        or cycle.admission_receipt != admission_receipt.canonical_document()
    ):
        raise ParallelFrameStreamProtocolError(
            "parallel receipt cycle differs from its current authority"
        )
    events = tuple(
        _EVENT_ADAPTER.validate_python(dict(event))
        for event in cycle.canonical_events
    )
    validator = ParallelFrameStreamProtocolValidator(authority, active_frame_types)
    for event in events:
        validator.accept(event)
    validator.finish()
    return events


def _decode_cached_completion(
    completion: TechnicalCompletionRecord,
    *,
    authority: ParallelFrameStreamAuthority,
    admission_receipt: ParallelFrameAdmissionReceipt,
    active_frame_types: tuple[ParallelFrameType, ...],
) -> tuple[ParallelFrameTechnicalEvent, ...]:
    document = completion.completion_json
    raw_events = document.get("events")
    schema_version = document.get("schema_version")
    if not isinstance(raw_events, list) or not raw_events:
        raise ParallelFrameStreamProtocolError("cached parallel completion is invalid")
    events = tuple(_EVENT_ADAPTER.validate_python(item) for item in raw_events)
    if schema_version == "intake-parallel-technical-completion.v1":
        if set(active_frame_types) != set(FRAME_TYPES):
            raise ParallelFrameStreamProtocolError(
                "exact-three cached completion cannot satisfy a subset receipt"
            )
        seals = {
            event.frame_type: event
            for event in events
            if isinstance(event, FrameSealed)
        }
        if set(seals) != set(FRAME_TYPES):
            raise ParallelFrameStreamProtocolError(
                "cached exact-three completion is not sealed"
            )
    elif schema_version == _SUBSET_TECHNICAL_COMPLETION_SCHEMA:
        expected_fields = {
            "schema_version",
            "completion_id",
            "thread_id",
            "command_id",
            "request_hash",
            "attempt_id",
            "fencing_token",
            "frame_set_id",
            "authority_sha256",
            "admission_receipt_sha256",
            "active_frame_types",
            "events",
            "frames",
            "completion_hash",
        }
        raw_frames = document.get("frames")
        if (
            set(document) != expected_fields
            or document.get("frame_set_id") != authority.frame_set_id
            or document.get("authority_sha256")
            != parallel_frame_authority_sha256(authority)
            or document.get("admission_receipt_sha256")
            != admission_receipt.receipt_sha256
            or document.get("active_frame_types") != list(active_frame_types)
            or not isinstance(raw_frames, list)
            or len(raw_frames) != len(FRAME_TYPES)
        ):
            raise ParallelFrameStreamProtocolError(
                "cached parallel subset completion differs from its receipt"
            )
        seals = {
            event.frame_type: event
            for event in events
            if isinstance(event, FrameSealed)
        }
        if set(seals) != set(active_frame_types):
            raise ParallelFrameStreamProtocolError(
                "cached parallel subset completion is not sealed"
            )
        frame_fields = {
            "frame_type",
            "generation",
            "frame_id",
            "action",
            "result_sha256",
            "public_projection_sha256",
        }
        for raw_frame, lane in zip(
            raw_frames,
            admission_receipt.lanes,
            strict=True,
        ):
            if (
                not isinstance(raw_frame, dict)
                or set(raw_frame) != frame_fields
                or raw_frame.get("frame_type") != lane.frame_type
                or raw_frame.get("generation") != lane.generation
                or raw_frame.get("frame_id") != lane.frame_id
                or raw_frame.get("action") != lane.action
            ):
                raise ParallelFrameStreamProtocolError(
                    "cached parallel subset Frame proof drifted"
                )
            if lane.action == "SKIP_SEALED":
                expected_hashes = (
                    lane.result_sha256,
                    lane.public_projection_sha256,
                )
            else:
                frame_seal = seals.get(lane.frame_type)
                if frame_seal is None:
                    raise ParallelFrameStreamProtocolError(
                        "cached parallel subset lost an active seal"
                    )
                expected_hashes = (
                    frame_seal.result_sha256,
                    frame_seal.public_projection_sha256,
                )
            if (
                raw_frame.get("result_sha256"),
                raw_frame.get("public_projection_sha256"),
            ) != expected_hashes:
                raise ParallelFrameStreamProtocolError(
                    "cached parallel subset result proof drifted"
                )
    else:
        raise ParallelFrameStreamProtocolError(
            "cached parallel completion schema is unsupported"
        )
    validator = ParallelFrameStreamProtocolValidator(authority, active_frame_types)
    for event in events:
        validator.accept(event)
    validator.finish()
    return events


async def _replay_events(
    events: Sequence[ParallelFrameTechnicalEvent],
) -> AsyncIterator[ParallelFrameTechnicalEvent]:
    for event in events:
        yield event


async def _replay_failed_receipt_cycle(
    events: Sequence[ParallelFrameTechnicalEvent],
    *,
    error_code: str,
) -> AsyncIterator[ParallelFrameTechnicalEvent]:
    for event in events:
        yield event
    raise GraphContractError(error_code)


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
