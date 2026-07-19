"""Gateway-backed Agent Stream v2 orchestration for synthetic SHADOW commands."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterable
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Protocol

from app.contracts.v1.models import AgentStreamEvent, RoomGraphCommand
from app.graph_runtime.errors import (
    GraphContractError,
    GraphGatewayDisabledError,
    GraphNewAgentAttemptRequiredError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.gateway import (
    AdmissionAction,
    GatewayAdmission,
    GatewayExecution,
    ShadowGraphExecutor,
)
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.ledger import AttemptStatus, ResultRecord
from app.graph_runtime.provider_intent import GatewayProviderCallIntentRecorder
from app.graph_runtime.recovery import RecoveryAction, RecoveryDecision
from app.graph_runtime.registry import RegistryRecord, VersionBinding
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
    ) -> AsyncIterator[AgentStreamEvent]: ...

    async def renew_execution(self, execution: GatewayExecution) -> Any: ...

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

    def __post_init__(self) -> None:
        if not callable(getattr(self.executor, "stream", None)):
            raise GraphContractError("Graph executor must expose an async stream method")
        if self.provider_binding.model_profile_id != self.binding.model_profile_id:
            raise GraphContractError("executor provider profile binding conflicts with registry")


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
        record.require_new_shadow_command()
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
                pending = tuple(
                    {
                        token.task
                        for token in self._tokens
                        if token.task is not current
                    }
                )
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
            try:
                await _close_iterator(stream)
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
            raise GraphNewAgentAttemptRequiredError(
                "PUBLIC_ATTEMPT_EXECUTION_ALREADY_STARTED"
            )
        if (
            decision.action is not RecoveryAction.RESUME_BEFORE_MODEL
            or not decision.invoke_model
        ):
            raise GraphContractError("unsupported durable Graph recovery decision")

        registration = self._executors.resolve_registration(admission.registry)
        execution = await self._gateway.acquire_execution(
            admission,
            owner_id=self._owner_id,
            attempt_id=admission.command.attempt_id,
        )
        source: AsyncIterator[AgentStreamEvent] | None = None
        try:
            source = self._provider_bound_stream(registration, execution)
            validated = self._gateway.execute_stream(
                execution=execution,
                executor=_Executor(source),
            )
        except BaseException as error:
            try:
                if source is not None:
                    await _close_iterator_safely(source)
            finally:
                await self._best_effort_abort(execution, error)
            raise
        async for event in self._renewing_stream(validated, execution):
            yield event

    def _provider_bound_stream(
        self,
        registration: ShadowExecutorRegistration,
        execution: GatewayExecution,
    ) -> AsyncIterator[AgentStreamEvent]:
        recorder = GatewayProviderCallIntentRecorder(
            gateway=self._gateway,
            execution=execution,
            provider=registration.provider_binding.provider,
            model=registration.provider_binding.model,
            allowed_nodes=registration.provider_binding.allowed_nodes,
        )

        async def stream() -> AsyncIterator[AgentStreamEvent]:
            with bind_provider_call_intent_recorder(recorder):
                iterator = registration.executor.stream(execution).__aiter__()
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
    ) -> AsyncIterator[AgentStreamEvent]:
        iterator: AsyncIterator[AgentStreamEvent] | None = None
        next_event: asyncio.Task[AgentStreamEvent] | None = None
        renewal: asyncio.Task[Any] | None = None
        terminal_seen = False
        try:
            iterator = source.__aiter__()
            next_event = asyncio.create_task(anext(iterator))
            renewal = asyncio.create_task(self._renew_after(execution))
            while next_event is not None:
                watched = {next_event}
                if renewal is not None:
                    watched.add(renewal)
                done, _ = await asyncio.wait(watched, return_when=asyncio.FIRST_COMPLETED)
                if next_event in done:
                    try:
                        event = next_event.result()
                    except StopAsyncIteration:
                        next_event = None
                        break
                    terminal_seen = event.event_type in TERMINAL_STREAM_EVENTS
                    if terminal_seen:
                        await _cancel_task(renewal)
                        renewal = None
                    elif renewal is not None and renewal in done:
                        renewal.result()
                        renewal = asyncio.create_task(self._renew_after(execution))
                    yield event
                    next_event = asyncio.create_task(anext(iterator))
                    continue
                if renewal is not None and renewal in done:
                    renewal.result()
                    renewal = asyncio.create_task(self._renew_after(execution))
            if not terminal_seen:
                raise GraphContractError("gateway stream ended without a terminal event")
        except BaseException as error:
            if not terminal_seen:
                await self._best_effort_abort(execution, error)
            raise
        finally:
            await _cancel_task(next_event)
            await _cancel_task(renewal)
            if iterator is not None:
                await _close_iterator(iterator)

    async def _renew_after(self, execution: GatewayExecution) -> None:
        await asyncio.sleep(self._renewal_seconds)
        await self._gateway.renew_execution(execution)

    async def _best_effort_abort(
        self,
        execution: GatewayExecution,
        error: BaseException,
    ) -> None:
        code = "GRAPH_STREAM_CANCELLED" if isinstance(error, asyncio.CancelledError) else (
            "GRAPH_STREAM_INTERRUPTED"
        )
        try:
            await self._gateway.finish_execution_attempt(
                execution,
                status=AttemptStatus.FAILED,
                error_code=code,
                error_classification="STREAM_INTERRUPTED",
            )
        except BaseException:
            return


class _Executor:
    def __init__(self, source: AsyncIterator[AgentStreamEvent]) -> None:
        self._source = source

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        return self._source


async def _cancel_task(task: asyncio.Task[Any] | None) -> None:
    if task is None:
        return
    if not task.done():
        task.cancel()
    await asyncio.gather(task, return_exceptions=True)


async def _close_iterator(iterator: AsyncIterator[Any]) -> None:
    close = getattr(iterator, "aclose", None)
    if close is not None:
        await close()


async def _close_iterator_safely(iterator: AsyncIterator[Any]) -> None:
    try:
        await _close_iterator(iterator)
    except Exception:
        return
