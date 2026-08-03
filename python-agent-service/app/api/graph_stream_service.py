"""Gateway-backed Agent Stream v2 orchestration for synthetic SHADOW commands."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterable
from dataclasses import dataclass, replace
from types import MappingProxyType
from typing import Any, Protocol

import anyio

from app.contracts.v1.models import AgentStreamEvent, RoomGraphCommand
from app.graph_runtime.errors import (
    GraphContractError,
    GraphGatewayDisabledError,
    GraphLeaseLostError,
    GraphNewAgentAttemptRequiredError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.gateway import (
    AdmissionAction,
    GatewayAdmission,
    GatewayExecution,
    ShadowGraphExecutor,
)
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.ledger import AttemptStatus, ResultRecord
from app.graph_runtime.provider_intent import GatewayProviderCallIntentRecorder
from app.graph_runtime.recovery import RecoveryAction, RecoveryDecision
from app.graph_runtime.registry import RegistryRecord, RegistryState, VersionBinding
from app.llm import bind_provider_call_intent_recorder
from app.security.invocation_envelope import VerifiedInvocation


TERMINAL_STREAM_EVENTS = frozenset({"attempt_aborted", "final", "error"})
MAX_CANCEL_DRAIN_SECONDS = 1.0


class GraphGatewayPort(Protocol):
    async def admit(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
        expected_thread: ThreadIdentity,
    ) -> GatewayAdmission: ...

    async def inspect_recovery(
        self,
        admission: GatewayAdmission,
    ) -> RecoveryDecision: ...

    async def acquire_execution(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
        attempt_id: str,
    ) -> GatewayExecution: ...

    def execute_stream(
        self,
        *,
        execution: GatewayExecution,
        executor: ShadowGraphExecutor,
        durable_terminal_signal: asyncio.Event | None = None,
        terminal_processing_started: asyncio.Event | None = None,
    ) -> AsyncIterator[AgentStreamEvent]: ...

    async def renew_execution(self, execution: GatewayExecution) -> Any: ...

    def cleanup_execution_lease(self, execution: GatewayExecution) -> None: ...

    async def record_provider_call(
        self,
        execution: GatewayExecution,
    ) -> GatewayExecution: ...

    async def finish_execution_attempt(
        self,
        execution: GatewayExecution,
        *,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> GatewayExecution: ...

    async def reconcile_terminal(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
    ) -> tuple[Any, ResultRecord]: ...


@dataclass(frozen=True, slots=True)
class ProviderRuntimeBinding:
    model_profile_id: str
    provider: str
    model: str
    allowed_nodes: frozenset[str]

    def __post_init__(self) -> None:
        if (
            not self.model_profile_id
            or len(self.model_profile_id) > 128
            or not self.provider
            or len(self.provider) > 64
            or not self.model
            or len(self.model) > 128
            or not self.allowed_nodes
            or len(self.allowed_nodes) > 64
            or any(not node or len(node) > 128 for node in self.allowed_nodes)
        ):
            raise GraphContractError("provider runtime binding is invalid")


@dataclass(frozen=True, slots=True)
class ShadowExecutorRegistration:
    """One process-local executor pinned to the complete immutable registry binding."""

    binding: VersionBinding
    executor: ShadowGraphExecutor
    provider_binding: ProviderRuntimeBinding
    room_provider_bindings: tuple[tuple[str, ProviderRuntimeBinding], ...] = ()

    def __post_init__(self) -> None:
        if not callable(getattr(self.executor, "stream", None)):
            raise GraphContractError("Graph executor must expose an async stream method")
        if self.provider_binding.model_profile_id != self.binding.model_profile_id:
            raise GraphContractError("executor provider profile binding conflicts with registry")
        seen_rooms: set[str] = set()
        for room_type, provider_binding in self.room_provider_bindings:
            if (
                not isinstance(room_type, str)
                or not room_type
                or len(room_type) > 32
                or room_type in seen_rooms
                or not isinstance(provider_binding, ProviderRuntimeBinding)
            ):
                raise GraphContractError("room provider runtime binding is invalid")
            if provider_binding.model_profile_id != self.binding.model_profile_id:
                raise GraphContractError("room provider profile binding conflicts with registry")
            seen_rooms.add(room_type)

    def provider_binding_for(self, room_type: str) -> ProviderRuntimeBinding:
        for bound_room_type, provider_binding in self.room_provider_bindings:
            if bound_room_type == room_type:
                return provider_binding
        return self.provider_binding


class ExactShadowExecutorRegistry:
    """Resolve only exact build/profile bindings; user input never selects an implementation."""

    def __init__(self, registrations: Iterable[ShadowExecutorRegistration] = ()) -> None:
        entries: dict[tuple[str, str, str], ShadowExecutorRegistration] = {}
        for registration in registrations:
            key = self._key(registration.binding)
            if key in entries:
                raise GraphContractError("duplicate process-local Graph executor registration")
            entries[key] = registration
        self._entries = MappingProxyType(entries)

    @property
    def registration_count(self) -> int:
        return len(self._entries)

    def resolve(self, record: RegistryRecord) -> ShadowGraphExecutor:
        return self.resolve_registration(record).executor

    def resolve_registration(
        self,
        record: RegistryRecord,
    ) -> ShadowExecutorRegistration:
        if record.state is RegistryState.SHADOW:
            record.require_new_shadow_command()
        elif record.state is RegistryState.ACTIVE_CANDIDATE:
            record.require_new_candidate_command()
        else:
            raise GraphVersionUnavailableError()
        registration = self._entries.get(self._key(record.binding))
        if registration is None or registration.binding != record.binding:
            raise GraphVersionUnavailableError("GRAPH_EXECUTOR_BINDING_UNAVAILABLE")
        return registration

    @staticmethod
    def _key(binding: VersionBinding) -> tuple[str, str, str]:
        return (
            binding.graph_key,
            binding.graph_version,
            binding.checkpoint_schema_version,
        )


@dataclass(frozen=True, slots=True)
class _AdmissionToken:
    task: asyncio.Task[Any]
    serial: int


@dataclass(slots=True)
class _LeaseHeartbeatState:
    """Share the newest lease and a deferred heartbeat failure with the stream owner."""

    execution: GatewayExecution
    failure: BaseException | None = None


class GraphStreamAdmissionGate:
    """Reject new streams during shutdown and bound graceful draining."""

    def __init__(self) -> None:
        self._accepting = False
        self._condition = asyncio.Condition()
        self._tokens: set[_AdmissionToken] = set()
        self._next_serial = 0

    @property
    def accepting(self) -> bool:
        return self._accepting

    async def start(self) -> None:
        async with self._condition:
            self._accepting = True

    async def enter(self) -> _AdmissionToken:
        task = asyncio.current_task()
        if task is None:
            raise GraphGatewayDisabledError("GRAPH_STREAM_TASK_MISSING")
        async with self._condition:
            if not self._accepting:
                raise GraphGatewayDisabledError("GRAPH_GATEWAY_DRAINING")
            self._next_serial += 1
            token = _AdmissionToken(task, self._next_serial)
            self._tokens.add(token)
        return token

    async def leave(self, token: _AdmissionToken) -> None:
        async with self._condition:
            self._tokens.discard(token)
            self._condition.notify_all()

    async def drain(self, timeout_seconds: float) -> bool:
        if timeout_seconds <= 0:
            raise ValueError("Graph drain timeout must be positive")
        current = asyncio.current_task()
        async with self._condition:
            self._accepting = False
            try:
                async with asyncio.timeout(timeout_seconds):
                    await self._condition.wait_for(lambda: not self._tokens)
                    return True
            except TimeoutError:
                pending = tuple({token.task for token in self._tokens if token.task is not current})
        for task in pending:
            task.cancel()
        if pending:
            done, _ = await asyncio.wait(
                pending,
                timeout=min(timeout_seconds, MAX_CANCEL_DRAIN_SECONDS),
            )
            if done:
                await asyncio.gather(*done, return_exceptions=True)
        return False


class GatewayBackedGraphCommandStreamService:
    """Run one signed command through durable admission, recovery, lease, and stream ports."""

    def __init__(
        self,
        *,
        gateway: GraphGatewayPort,
        executors: ExactShadowExecutorRegistry,
        owner_id: str,
        admission_gate: GraphStreamAdmissionGate,
        lease_renewal_seconds: float = 10.0,
    ) -> None:
        if not owner_id or len(owner_id) > 128:
            raise ValueError("Graph owner_id must contain 1..128 characters")
        if lease_renewal_seconds <= 0 or lease_renewal_seconds >= 30:
            raise ValueError("lease renewal must be inside the 30-second lease window")
        self._gateway = gateway
        self._executors = executors
        self._owner_id = owner_id
        self._gate = admission_gate
        self._renewal_seconds = lease_renewal_seconds

    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
        expected_thread: ThreadIdentity,
    ) -> AsyncIterator[AgentStreamEvent]:
        token = await self._gate.enter()
        try:
            admission = await self._gateway.admit(
                command=command,
                verified_invocation=verified_invocation,
                expected_thread=expected_thread,
            )
            stream = self._stream_admission(admission)
        except BaseException:
            await self._gate.leave(token)
            raise
        return self._guarded(stream, token)

    async def _guarded(
        self,
        stream: AsyncIterator[AgentStreamEvent],
        token: _AdmissionToken,
    ) -> AsyncIterator[AgentStreamEvent]:
        try:
            async for event in stream:
                yield event
        finally:
            # Starlette cancels the response task in an AnyIO cancel scope when its
            # downstream client disconnects.  That is level cancellation, so every
            # await in this finally block would otherwise be cancelled again and can
            # leak both the durable execution and its admission token.
            with anyio.CancelScope(shield=True):
                try:
                    await _close_iterator_bounded(stream)
                finally:
                    await self._gate.leave(token)

    async def _stream_admission(
        self,
        admission: GatewayAdmission,
    ) -> AsyncIterator[AgentStreamEvent]:
        if admission.action in {AdmissionAction.RECONCILE, AdmissionAction.RETURN_CACHED}:
            await self._reconcile_without_synthetic_replay(admission)
        if admission.action in {
            AdmissionAction.RETURN_CANCELLED,
            AdmissionAction.RETURN_ABORTED,
        }:
            self._require_java_durable_replay()

        decision = await self._gateway.inspect_recovery(admission)
        if decision.emit_attempt_reset:
            raise GraphContractError(
                "Graph lease recovery cannot create a public AgentRun attempt reset"
            )
        if decision.action in {RecoveryAction.RECONCILE_TERMINAL, RecoveryAction.RETURN_CACHED}:
            await self._reconcile_without_synthetic_replay(admission)
        if decision.action in {RecoveryAction.RETURN_CANCELLED, RecoveryAction.RETURN_ABORTED}:
            self._require_java_durable_replay()
        if decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT:
            raise GraphNewAgentAttemptRequiredError(decision.reason_code)
        if admission.action is AdmissionAction.OBSERVE_OR_TAKEOVER:
            raise GraphNewAgentAttemptRequiredError("PUBLIC_ATTEMPT_EXECUTION_ALREADY_STARTED")
        if decision.action is not RecoveryAction.RESUME_BEFORE_MODEL or not decision.invoke_model:
            raise GraphContractError("unsupported durable Graph recovery decision")

        registration = self._executors.resolve_registration(admission.registry)
        execution = await self._gateway.acquire_execution(
            admission,
            owner_id=self._owner_id,
            attempt_id=admission.command.attempt_id,
        )
        source: AsyncIterator[AgentStreamEvent] | None = None
        try:
            provider_binding = registration.provider_binding_for(admission.command.room_type)
            execution = self._bind_execution_identity(provider_binding, execution)
            source = self._provider_bound_stream(
                registration.executor,
                provider_binding,
                execution,
            )
            durable_terminal_signal = asyncio.Event()
            terminal_processing_started = asyncio.Event()
            validated = self._gateway.execute_stream(
                execution=execution,
                executor=_Executor(source),
                durable_terminal_signal=durable_terminal_signal,
                terminal_processing_started=terminal_processing_started,
            )
        except BaseException as error:
            with anyio.CancelScope(shield=True):
                await self._best_effort_abort(execution, error)
                if source is not None:
                    await _close_iterator_safely(source)
            raise
        renewing_stream = self._renewing_stream(
            validated,
            execution,
            durable_terminal_signal=durable_terminal_signal,
            terminal_processing_started=terminal_processing_started,
        )
        try:
            async for event in renewing_stream:
                yield event
        finally:
            # ``async for`` does not itself await ``aclose`` on a suspended nested
            # async generator.  Make the ownership explicit so a public HTTP stream
            # close reaches the bounded terminal-processing drain in
            # ``_renewing_stream`` instead of orphaning its prefetched source task.
            with anyio.CancelScope(shield=True):
                await _close_iterator_bounded(renewing_stream)

    @staticmethod
    def _bind_execution_identity(
        provider_binding: ProviderRuntimeBinding,
        execution: GatewayExecution,
    ) -> GatewayExecution:
        """Freeze the resolved provider identity into candidate execution before streaming."""

        if execution.fence.execution_lane is GraphGatewayMode.SHADOW:
            return execution
        if execution.fence.execution_lane is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise GraphContractError("execution has an invalid Graph lane")
        expected = (
            provider_binding.provider,
            provider_binding.model,
        )
        existing = (
            execution.fence.execution_provider,
            execution.fence.execution_model,
        )
        if existing != (None, None) and existing != expected:
            raise GraphContractError("execution provider identity conflicts with registry binding")
        return replace(
            execution,
            fence=replace(
                execution.fence,
                execution_provider=expected[0],
                execution_model=expected[1],
            ),
        )

    def _provider_bound_stream(
        self,
        executor: ShadowGraphExecutor,
        provider_binding: ProviderRuntimeBinding,
        execution: GatewayExecution,
    ) -> AsyncIterator[AgentStreamEvent]:
        recorder = GatewayProviderCallIntentRecorder(
            gateway=self._gateway,
            execution=execution,
            provider=provider_binding.provider,
            model=provider_binding.model,
            allowed_nodes=provider_binding.allowed_nodes,
        )

        async def stream() -> AsyncIterator[AgentStreamEvent]:
            with bind_provider_call_intent_recorder(recorder):
                iterator = executor.stream(execution).__aiter__()
            try:
                while True:
                    try:
                        with bind_provider_call_intent_recorder(recorder):
                            event = await anext(iterator)
                    except StopAsyncIteration:
                        return
                    yield event
            finally:
                with bind_provider_call_intent_recorder(recorder):
                    await _close_iterator_safely(iterator)

        return stream()

    async def _reconcile_without_synthetic_replay(
        self,
        admission: GatewayAdmission,
    ) -> None:
        await self._gateway.reconcile_terminal(
            admission,
            owner_id=self._owner_id,
        )
        self._require_java_durable_replay()

    @staticmethod
    def _require_java_durable_replay() -> None:
        raise GraphContractError(
            "existing commands require exact replay from the Java durable Agent Stream ledger"
        )

    async def _renewing_stream(
        self,
        source: AsyncIterator[AgentStreamEvent],
        execution: GatewayExecution,
        *,
        durable_terminal_signal: asyncio.Event | None = None,
        terminal_processing_started: asyncio.Event | None = None,
    ) -> AsyncIterator[AgentStreamEvent]:
        iterator: AsyncIterator[AgentStreamEvent] | None = None
        next_event: asyncio.Task[AgentStreamEvent] | None = None
        heartbeat_stop = asyncio.Event()
        heartbeat_state = _LeaseHeartbeatState(execution)
        heartbeat: asyncio.Task[None] | None = None
        terminal_seen = False
        try:
            iterator = source.__aiter__()
            next_event = asyncio.create_task(anext(iterator))
            heartbeat = asyncio.create_task(
                self._run_lease_heartbeat(
                    heartbeat_state,
                    stop_signal=heartbeat_stop,
                    durable_terminal_signal=durable_terminal_signal,
                )
            )
            while next_event is not None:
                heartbeat_deferred = False
                if self._durable_terminal_reached(durable_terminal_signal):
                    execution, heartbeat_joined = await self._join_terminal_heartbeat(
                        heartbeat,
                        heartbeat_stop,
                        heartbeat_state,
                        suppress_all=True,
                    )
                    if heartbeat_joined:
                        heartbeat = None
                elif self._terminal_processing_inflight(
                    terminal_processing_started,
                    durable_terminal_signal,
                ):
                    # A validated terminal envelope has entered the gateway, but its
                    # durable transaction has not yet reported completion.  Do not
                    # let a renewal task preempt and cancel that source in the narrow
                    # transaction-commit -> Event.set scheduling window.  This is a
                    # deferral fence only: a source failure before the durable signal
                    # still falls through to the ordinary fail-closed abort path.
                    heartbeat_deferred = heartbeat is not None
                watched = {next_event}
                if heartbeat is not None and not heartbeat_deferred:
                    watched.add(heartbeat)
                done, _ = await asyncio.wait(watched, return_when=asyncio.FIRST_COMPLETED)
                if self._durable_terminal_reached(durable_terminal_signal):
                    execution, heartbeat_joined = await self._join_terminal_heartbeat(
                        heartbeat,
                        heartbeat_stop,
                        heartbeat_state,
                        suppress_all=True,
                    )
                    if heartbeat_joined:
                        heartbeat = None
                elif self._terminal_processing_inflight(
                    terminal_processing_started,
                    durable_terminal_signal,
                ):
                    heartbeat_deferred = heartbeat is not None
                if next_event in done:
                    try:
                        event = next_event.result()
                    except StopAsyncIteration:
                        next_event = None
                        break
                    terminal_seen = event.event_type in TERMINAL_STREAM_EVENTS
                    if terminal_seen:
                        # ``Gateway.execute_stream`` yields a terminal only after it
                        # has durably reconciled/terminated the command and released
                        # its exact lease.  A concurrent renewal can therefore lose
                        # that lease legitimately; suppress only that expected loss.
                        execution, heartbeat_joined = await self._join_terminal_heartbeat(
                            heartbeat,
                            heartbeat_stop,
                            heartbeat_state,
                            suppress_all=self._durable_terminal_reached(
                                durable_terminal_signal
                            ),
                        )
                        if heartbeat_joined:
                            heartbeat = None
                    else:
                        if self._durable_terminal_reached(durable_terminal_signal):
                            raise GraphContractError(
                                "durable terminal signal preceded a nonterminal stream event"
                            )
                        if self._terminal_processing_inflight(
                            terminal_processing_started,
                            durable_terminal_signal,
                        ):
                            raise GraphContractError(
                                "terminal processing preceded a nonterminal stream event"
                            )
                        if heartbeat is not None and heartbeat.done() and not heartbeat_deferred:
                            # Before a terminal is durably observed, every heartbeat
                            # failure remains authoritative and must fail closed.
                            self._raise_heartbeat_failure(heartbeat_state)
                        # Keep exactly one source pull in flight while the caller
                        # sends the current event.  Without this bounded prefetch,
                        # an ASGI/HTTP backpressure gap after ``attempt_started``
                        # prevents the executor from loading context or beginning
                        # provider work until the next downstream read.
                        next_event = asyncio.create_task(anext(iterator))
                    yield event
                    if terminal_seen:
                        next_event = None
                    continue
                if heartbeat is not None and heartbeat in done and not heartbeat_deferred:
                    self._raise_heartbeat_failure(heartbeat_state)
            if not terminal_seen:
                raise GraphContractError("gateway stream ended without a terminal event")
        except BaseException as error:
            failure = error
            # A disconnect reaches this generator as ``CancelledError``/``GeneratorExit``.
            # Shield the entire fail-closed sequence: AnyIO's level cancellation otherwise
            # interrupts the first cleanup await and leaves the command/attempt EXECUTING.
            with anyio.CancelScope(shield=True):
                if not terminal_seen:
                    if (
                        isinstance(error, (asyncio.CancelledError, GeneratorExit))
                        and self._terminal_processing_inflight(
                            terminal_processing_started,
                            durable_terminal_signal,
                        )
                    ):
                        # A downstream disconnect must not tear down a validated terminal
                        # transaction in-flight, but it also must never wait forever for a
                        # hung control-plane operation.  Keep the source alive only for the
                        # bounded drain window, then retain the regular cancellation fence.
                        await self._await_terminal_processing_boundary(
                            next_event,
                            durable_terminal_signal,
                        )
                    terminal_seen, prefetched_failure = self._completed_prefetch_outcome(
                        next_event
                    )
                    durable_terminal_reached = self._durable_terminal_reached(
                        durable_terminal_signal
                    )
                    if prefetched_failure is not None:
                        # A post-commit barrier/source failure must remain visible even
                        # though the command is already durably terminal.
                        failure = prefetched_failure
                    if terminal_seen or durable_terminal_reached:
                        execution, heartbeat_joined = await self._join_terminal_heartbeat(
                            heartbeat,
                            heartbeat_stop,
                            heartbeat_state,
                            suppress_all=durable_terminal_reached,
                        )
                        if heartbeat_joined:
                            heartbeat = None
                    else:
                        # The original source/cancellation failure is already the authority
                        # here.  In particular, a terminal source failure before durable
                        # completion must not be obscured by a deferred heartbeat failure.
                        execution = heartbeat_state.execution
                        # Persist the cancellation fence before touching provider tasks. A task
                        # that suppresses cancellation can no longer checkpoint with the old token.
                        await self._best_effort_abort(execution, failure)
                heartbeat_stop.set()
                await _cancel_task(next_event)
                next_event = None
                if iterator is not None:
                    await _close_iterator_safely(iterator)
                    iterator = None
            if failure is error:
                raise
            raise failure from error
        finally:
            with anyio.CancelScope(shield=True):
                heartbeat_stop.set()
                await _cancel_task(next_event)
                heartbeat_joined = await _cancel_task(heartbeat)
                execution = heartbeat_state.execution
                try:
                    if iterator is not None:
                        await _close_iterator_bounded(iterator)
                finally:
                    # A renewal task can commit just as a final/error path becomes
                    # terminal.  Join it and close the source first, then clear the
                    # exact fence once so a late renewal cannot reinsert the cache.
                    if heartbeat_joined:
                        self._gateway.cleanup_execution_lease(execution)
                    elif heartbeat is not None:
                        _cleanup_execution_lease_after_heartbeat(
                            heartbeat,
                            self._gateway,
                            heartbeat_state,
                        )

    @staticmethod
    async def _join_terminal_heartbeat(
        heartbeat: asyncio.Task[None] | None,
        stop_signal: asyncio.Event,
        state: _LeaseHeartbeatState,
        *,
        suppress_all: bool = False,
    ) -> tuple[GatewayExecution, bool]:
        """Join terminal cleanup without mistaking its lease release for a failure."""

        stop_signal.set()
        heartbeat_joined = await _cancel_task(heartbeat)
        if not heartbeat_joined:
            return state.execution, False
        failure = state.failure
        if suppress_all:
            return state.execution, True
        if isinstance(failure, GraphLeaseLostError):
            return state.execution, True
        if failure is not None:
            raise failure
        return state.execution, True

    @staticmethod
    def _raise_heartbeat_failure(state: _LeaseHeartbeatState) -> None:
        if state.failure is not None:
            raise state.failure
        raise GraphContractError("lease heartbeat stopped before terminal durability")

    @staticmethod
    def _durable_terminal_reached(signal: asyncio.Event | None) -> bool:
        return signal is not None and signal.is_set()

    @classmethod
    def _terminal_processing_inflight(
        cls,
        processing_started: asyncio.Event | None,
        durable_terminal_signal: asyncio.Event | None,
    ) -> bool:
        """Whether a validated terminal envelope still lacks durable completion."""

        return (
            processing_started is not None
            and processing_started.is_set()
            and not cls._durable_terminal_reached(durable_terminal_signal)
        )

    @staticmethod
    async def _await_terminal_processing_boundary(
        next_event: asyncio.Task[AgentStreamEvent] | None,
        durable_terminal_signal: asyncio.Event | None,
    ) -> None:
        """Bound a downstream-close drain without cancelling the terminal source early."""

        if (
            next_event is None
            or next_event.done()
            or (
                durable_terminal_signal is not None
                and durable_terminal_signal.is_set()
            )
        ):
            return
        signal_waiter: asyncio.Task[bool] | None = None
        watched: set[asyncio.Task[Any]] = {next_event}
        if durable_terminal_signal is not None:
            signal_waiter = asyncio.create_task(durable_terminal_signal.wait())
            watched.add(signal_waiter)
        try:
            await asyncio.wait(
                watched,
                timeout=MAX_CANCEL_DRAIN_SECONDS,
                return_when=asyncio.FIRST_COMPLETED,
            )
        finally:
            await _cancel_task(signal_waiter)

    @staticmethod
    def _completed_prefetch_outcome(
        next_event: asyncio.Task[AgentStreamEvent] | None,
    ) -> tuple[bool, BaseException | None]:
        """Classify a completed prefetched item while a downstream disconnect arrives."""

        if next_event is None or not next_event.done():
            return False, None
        try:
            event = next_event.result()
        except StopAsyncIteration:
            return False, GraphContractError("gateway stream ended without a terminal event")
        except BaseException as error:
            return False, error
        return event.event_type in TERMINAL_STREAM_EVENTS, None

    async def _run_lease_heartbeat(
        self,
        state: _LeaseHeartbeatState,
        *,
        stop_signal: asyncio.Event,
        durable_terminal_signal: asyncio.Event | None,
    ) -> None:
        """Renew independently of downstream reads until terminal durability is known."""

        try:
            while (
                not stop_signal.is_set()
                and not self._durable_terminal_reached(durable_terminal_signal)
            ):
                if not await self._await_heartbeat_tick(
                    stop_signal=stop_signal,
                    durable_terminal_signal=durable_terminal_signal,
                ):
                    return
                if (
                    stop_signal.is_set()
                    or self._durable_terminal_reached(durable_terminal_signal)
                ):
                    return
                lease = await self._gateway.renew_execution(state.execution)
                state.execution = replace(state.execution, lease=lease)
        except asyncio.CancelledError:
            raise
        except BaseException as error:
            state.failure = error

    async def _await_heartbeat_tick(
        self,
        *,
        stop_signal: asyncio.Event,
        durable_terminal_signal: asyncio.Event | None,
    ) -> bool:
        """Return only when a renewal interval elapses before terminal/stop signals."""

        if (
            stop_signal.is_set()
            or self._durable_terminal_reached(durable_terminal_signal)
        ):
            return False
        waiters: set[asyncio.Task[bool]] = {asyncio.create_task(stop_signal.wait())}
        if durable_terminal_signal is not None:
            waiters.add(asyncio.create_task(durable_terminal_signal.wait()))
        try:
            done, _ = await asyncio.wait(
                waiters,
                timeout=self._renewal_seconds,
                return_when=asyncio.FIRST_COMPLETED,
            )
            return not done
        finally:
            for waiter in waiters:
                waiter.cancel()
            await asyncio.gather(*waiters, return_exceptions=True)

    async def _best_effort_abort(
        self,
        execution: GatewayExecution,
        error: BaseException,
    ) -> None:
        cancelled = isinstance(error, (asyncio.CancelledError, GeneratorExit))
        code = "GRAPH_STREAM_CANCELLED" if cancelled else "GRAPH_STREAM_INTERRUPTED"
        try:
            await _await_cleanup_bounded(
                self._gateway.finish_execution_attempt(
                    execution,
                    status=(AttemptStatus.CANCELLED if cancelled else AttemptStatus.FAILED),
                    error_code=code,
                    error_classification="STREAM_INTERRUPTED",
                )
            )
        except BaseException:
            return


class _Executor:
    def __init__(self, source: AsyncIterator[AgentStreamEvent]) -> None:
        self._source = source

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        return self._source


async def _cancel_task(task: asyncio.Task[Any] | None) -> bool:
    if task is None:
        return True
    if not task.done() and task.cancelling() == 0:
        task.cancel()
    try:
        await _await_task_bounded(task)
    except TimeoutError:
        return False
    except BaseException:
        # A cancelled/failed child is fully joined.  Its failure belongs to the
        # stream path that initiated teardown, not to this cleanup join.
        return True
    return True


async def _close_iterator(iterator: AsyncIterator[Any]) -> None:
    close = getattr(iterator, "aclose", None)
    if close is not None:
        await close()


async def _close_iterator_bounded(iterator: AsyncIterator[Any]) -> None:
    close = getattr(iterator, "aclose", None)
    if close is not None:
        with anyio.CancelScope(shield=True):
            await _await_cleanup_bounded(close())


async def _close_iterator_safely(iterator: AsyncIterator[Any]) -> None:
    try:
        await _close_iterator_bounded(iterator)
    except BaseException:
        return


async def _await_cleanup_bounded(operation: Any) -> Any:
    """Run a new cleanup operation outside AnyIO level cancellation, with a hard bound."""

    with anyio.fail_after(MAX_CANCEL_DRAIN_SECONDS, shield=True):
        return await operation


async def _await_task_bounded(task: asyncio.Task[Any]) -> Any:
    """Await a cleanup task without letting a cancellation-resistant task stall teardown."""

    try:
        with anyio.fail_after(MAX_CANCEL_DRAIN_SECONDS, shield=True):
            return await asyncio.shield(task)
    except TimeoutError:
        task.add_done_callback(_consume_task_exception)
        raise


def _consume_task_exception(task: asyncio.Task[Any]) -> None:
    try:
        task.exception()
    except (asyncio.CancelledError, Exception):
        return


def _cleanup_execution_lease_after_heartbeat(
    heartbeat: asyncio.Task[Any],
    gateway: GraphGatewayPort,
    state: _LeaseHeartbeatState,
) -> None:
    """Clear the exact cache only after a cancellation-resistant renew has stopped.

    A bounded disconnect cleanup cannot wait indefinitely for a control-plane call
    that suppresses cancellation.  Deferring cache removal to the heartbeat's done
    callback keeps the same ordering as the normal joined path: any final successful
    renew can cache its lease first, and the cleanup that follows removes that exact
    fence.  The durable abort still fences the late renewal at the database.
    """

    def cleanup(_: asyncio.Task[Any]) -> None:
        try:
            gateway.cleanup_execution_lease(state.execution)
        except Exception:
            return

    heartbeat.add_done_callback(cleanup)
