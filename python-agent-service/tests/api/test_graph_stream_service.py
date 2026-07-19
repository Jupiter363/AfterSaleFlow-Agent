from __future__ import annotations

import asyncio
import json
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import httpx
import pytest
from pydantic import BaseModel

from app.api.graph_stream_service import (
    ExactShadowExecutorRegistry,
    GatewayBackedGraphCommandStreamService,
    GraphStreamAdmissionGate,
    ProviderRuntimeBinding,
    ShadowExecutorRegistration,
)
from app.contracts.v1.models import AgentStreamEvent, AgentStreamPayload, RoomGraphCommand
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
)
from app.graph_runtime.identity import ActorScopeBinding, RoomType, ThreadIdentity
from app.graph_runtime.lease import LeaseRecord
from app.graph_runtime.ledger import (
    AttemptRecord,
    AttemptStatus,
    CommandBinding,
    CommandRecord,
    CommandStatus,
    ResultRecord,
)
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.recovery import RecoveryAction, RecoveryDecision
from app.graph_runtime.registry import RegistryRecord, RegistryState, VersionBinding
from app.llm import GovernedProviderRequest, LiteLlmProxyClient
from app.security.invocation_envelope import VerifiedInvocation


ROOT = Path(__file__).resolve().parents[3]
COMMAND_FIXTURE = (
    ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-command-valid.json"
)
NOW = datetime(2026, 7, 19, 9, 0, tzinfo=timezone.utc)


def _command() -> RoomGraphCommand:
    return RoomGraphCommand.model_validate(
        json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    )


def _version(command: RoomGraphCommand) -> VersionBinding:
    invocation = command.invocation_context
    return VersionBinding(
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        state_schema_version="state.v1",
        state_schema_hash="a" * 64,
        command_schema_version=command.schema_version,
        result_schema_version="room-graph-result.v1",
        prompt_version=invocation.prompt_profile_id,
        model_profile_id=invocation.model_profile_id,
        output_schema_version=invocation.output_schema_version,
        policy_version=invocation.policy_version,
        guardrail_version=invocation.guardrail_version,
        tool_policy_version="tools.none.v1",
        binding_hash="b" * 64,
        code_build_id="build.v1",
    )


def _provider_binding() -> ProviderRuntimeBinding:
    return ProviderRuntimeBinding(
        model_profile_id=_command().invocation_context.model_profile_id,
        provider="litellm",
        model="qwen3.7-plus",
        allowed_nodes=frozenset({"test_node"}),
    )


def _thread(command: RoomGraphCommand) -> ThreadIdentity:
    return ThreadIdentity(
        thread_id=command.thread_id,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType(command.room_type),
        room_epoch=command.room_epoch,
        actor_scope=ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json")),
        agent_session_id="trusted-session-1",
        shared_session=False,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )


def _admission(action: AdmissionAction) -> GatewayAdmission:
    command = _command()
    version = _version(command)
    binding = CommandBinding.from_command(
        command,
        tool_policy_version=version.tool_policy_version,
    )
    status = {
        AdmissionAction.ACQUIRE: CommandStatus.REGISTERED,
        AdmissionAction.OBSERVE_OR_TAKEOVER: CommandStatus.EXECUTING,
        AdmissionAction.RECONCILE: CommandStatus.RESULT_CHECKPOINTED,
        AdmissionAction.RETURN_CACHED: CommandStatus.COMPLETED,
        AdmissionAction.RETURN_CANCELLED: CommandStatus.CANCELLED,
        AdmissionAction.RETURN_ABORTED: CommandStatus.ABORTED,
    }[action]
    terminal = status in {CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED}
    record = CommandRecord(
        binding=binding,
        status=status,
        attempt_count=0 if status is CommandStatus.REGISTERED else 1,
        fencing_token=None if status is CommandStatus.REGISTERED else 1,
        start_checkpoint_ns=None,
        start_checkpoint_id=None,
        committed_checkpoint_ns="" if terminal else None,
        committed_checkpoint_id="checkpoint-1" if terminal else None,
        result_ref="s3://graph-results/result-1.json" if terminal else None,
        result_hash="c" * 64 if terminal else None,
        error_code=None,
        error_classification=None,
        revision=1,
    )
    return GatewayAdmission(
        command=command,
        binding=binding,
        thread=_thread(command),
        registry=RegistryRecord(version, RegistryState.SHADOW, True, 1),
        record=record,
        action=action,
        created=status is CommandStatus.REGISTERED,
    )


def _execution(admission: GatewayAdmission) -> GatewayExecution:
    command = admission.command
    attempt = AttemptRecord(
        attempt_id=command.attempt_id,
        thread_id=command.thread_id,
        command_id=command.command_id,
        attempt_no=1,
        owner_id="replica-1",
        fencing_token=1,
        status=AttemptStatus.EXECUTING,
        provider_call_count=0,
        error_code=None,
        error_classification=None,
    )
    lease = LeaseRecord(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="replica-1",
        fencing_token=1,
        lease_expires_at=NOW + timedelta(seconds=30),
        acquired_at=NOW,
        renewed_at=NOW,
        released_at=None,
        cancelled_at=None,
        cancelled_by_command_id=None,
        revision=0,
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="replica-1",
        fencing_token=1,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    return GatewayExecution(admission, attempt, lease, fence)


def _event(command: RoomGraphCommand, sequence: int, event_type: str) -> AgentStreamEvent:
    payload = (
        AgentStreamPayload(node="intake_node")
        if event_type == "attempt_started"
        else AgentStreamPayload(
            final_result_ref="s3://graph-results/result-1.json",
            final_result_hash="c" * 64,
        )
    )
    return AgentStreamEvent(
        schema_version="agent-stream.v2",
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        sequence_no=sequence,
        event_type=event_type,  # type: ignore[arg-type]
        audience=command.actor_scope.audience,
        occurred_at=NOW,
        payload=payload,
    )


class _Executor:
    def __init__(self, *, delay_seconds: float = 0, terminal: bool = True) -> None:
        self.delay_seconds = delay_seconds
        self.terminal = terminal
        self.calls = 0

    async def stream(self, execution: GatewayExecution):
        self.calls += 1
        yield _event(execution.admission.command, 0, "attempt_started")
        if self.delay_seconds:
            await asyncio.sleep(self.delay_seconds)
        if self.terminal:
            yield _event(execution.admission.command, 1, "final")


class _Answer(BaseModel):
    answer: str


class _ProviderCallingExecutor(_Executor):
    def __init__(self) -> None:
        super().__init__()
        self.http_calls = 0

    async def stream(self, execution: GatewayExecution):
        self.calls += 1
        yield _event(execution.admission.command, 0, "attempt_started")

        async def handler(request: httpx.Request) -> httpx.Response:
            self.http_calls += 1
            return httpx.Response(
                200,
                json={
                    "model": "qwen3.7-plus",
                    "choices": [{"message": {"content": '{"answer":"ok"}'}}],
                    "usage": {
                        "prompt_tokens": 1,
                        "completion_tokens": 1,
                        "total_tokens": 2,
                    },
                },
            )

        transport = httpx.MockTransport(handler)
        client = LiteLlmProxyClient(
            "http://litellm:4000",
            "qwen3.7-plus",
            "key",
            transport=transport,
            async_transport=transport,
        )
        result = await client.agenerate(
            node_name="test_node",
            system_prompt="system",
            user_prompt="human",
            output_type=_Answer,
            governed_request=GovernedProviderRequest(
                provider="litellm",
                model="qwen3.7-plus",
                temperature=0,
                max_output_tokens=32,
                response_format="STRICT_JSON_SCHEMA",
                tool_allowlist=(),
                deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
                provider_attempts_remaining=1,
                repairs_remaining=0,
                traceparent=execution.admission.command.traceparent,
            ),
        )
        assert result.value.answer == "ok"
        yield _event(execution.admission.command, 1, "final")


class _Gateway:
    def __init__(
        self,
        admission: GatewayAdmission,
        *,
        decision: Any | None = None,
    ) -> None:
        self.admission = admission
        self.decision = decision or RecoveryDecision(
            action=RecoveryAction.RESUME_BEFORE_MODEL,
            invoke_model=True,
            emit_attempt_reset=False,
            reason_code="NO_MODEL_CALL_DURABLY_STARTED",
        )
        self.acquired = 0
        self.reconciled = 0
        self.renewed = 0
        self.finished = 0
        self.provider_calls = 0

    async def admit(self, **kwargs: Any) -> GatewayAdmission:
        return self.admission

    async def inspect_recovery(self, admission: GatewayAdmission) -> Any:
        return self.decision

    async def acquire_execution(self, admission: GatewayAdmission, **kwargs: Any):
        self.acquired += 1
        return _execution(admission)

    async def execute_stream(self, *, execution: GatewayExecution, executor: Any):
        async for event in executor.stream(execution):
            yield event

    async def renew_execution(self, execution: GatewayExecution) -> LeaseRecord:
        self.renewed += 1
        return execution.lease

    async def record_provider_call(self, execution: GatewayExecution) -> GatewayExecution:
        self.provider_calls += 1
        return replace(
            execution,
            attempt=replace(
                execution.attempt,
                provider_call_count=execution.attempt.provider_call_count + 1,
            ),
        )

    async def finish_execution_attempt(self, execution: GatewayExecution, **kwargs: Any):
        self.finished += 1
        return execution

    async def reconcile_terminal(self, admission: GatewayAdmission, **kwargs: Any):
        self.reconciled += 1
        result = ResultRecord(
            result_id="result-1",
            thread_id=admission.command.thread_id,
            command_id=admission.command.command_id,
            request_hash=admission.command.request_hash,
            result_schema_version="room-graph-result.v1",
            checkpoint_ns="",
            checkpoint_id="checkpoint-1",
            cognitive_revision=1,
            terminal_status="COMPLETED",
            result_json={"output_hash": "c" * 64},
            result_ref="s3://graph-results/result-1.json",
            result_hash="c" * 64,
            usage_json={},
        )
        return admission.record, result


async def _service(
    gateway: _Gateway,
    executor: _Executor,
    *,
    renewal_seconds: float = 10,
) -> tuple[GatewayBackedGraphCommandStreamService, GraphStreamAdmissionGate]:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    registry = ExactShadowExecutorRegistry(
        [
            ShadowExecutorRegistration(
                gateway.admission.registry.binding,
                executor,
                _provider_binding(),
            )
        ]
    )
    return (
        GatewayBackedGraphCommandStreamService(
            gateway=cast(Any, gateway),
            executors=registry,
            owner_id="replica-1",
            admission_gate=gate,
            lease_renewal_seconds=renewal_seconds,
        ),
        gate,
    )


def test_executor_registry_requires_the_complete_immutable_binding() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    executor = _Executor()
    registry = ExactShadowExecutorRegistry(
        [
            ShadowExecutorRegistration(
                admission.registry.binding,
                executor,
                _provider_binding(),
            )
        ]
    )

    assert registry.resolve(admission.registry) is executor
    mismatched = RegistryRecord(
        replace(admission.registry.binding, code_build_id="build.v2"),
        RegistryState.SHADOW,
        True,
        2,
    )
    with pytest.raises(GraphVersionUnavailableError):
        registry.resolve(mismatched)
    with pytest.raises(GraphContractError, match="duplicate"):
        ExactShadowExecutorRegistry(
            [
                ShadowExecutorRegistration(
                    admission.registry.binding,
                    executor,
                    _provider_binding(),
                ),
                ShadowExecutorRegistration(
                    admission.registry.binding,
                    _Executor(),
                    _provider_binding(),
                ),
            ]
        )
    with pytest.raises(GraphContractError, match="provider profile binding"):
        ShadowExecutorRegistration(
            admission.registry.binding,
            executor,
            replace(_provider_binding(), model_profile_id="model.other.v1"),
        )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "action",
    [AdmissionAction.RECONCILE, AdmissionAction.RETURN_CACHED],
)
async def test_committed_terminal_is_reconciled_without_synthetic_stream_replay(
    action: AdmissionAction,
) -> None:
    admission = _admission(action)
    gateway = _Gateway(admission)
    executor = _Executor()
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphContractError, match="exact replay from the Java durable"):
        _ = [event async for event in stream]

    assert gateway.reconciled == 1
    assert gateway.acquired == 0
    assert executor.calls == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "action",
    [AdmissionAction.RETURN_CANCELLED, AdmissionAction.RETURN_ABORTED],
)
async def test_existing_nonresult_terminal_never_gets_synthetic_replay(
    action: AdmissionAction,
) -> None:
    admission = _admission(action)
    gateway = _Gateway(admission)
    executor = _Executor()
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphContractError, match="exact replay from the Java durable"):
        _ = [event async for event in stream]

    assert gateway.reconciled == 0
    assert gateway.acquired == 0
    assert executor.calls == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("action", "reconciled"),
    [
        (RecoveryAction.RECONCILE_TERMINAL, 1),
        (RecoveryAction.RETURN_CACHED, 1),
        (RecoveryAction.RETURN_CANCELLED, 0),
        (RecoveryAction.RETURN_ABORTED, 0),
    ],
)
async def test_recovery_terminal_decisions_require_java_durable_replay(
    action: RecoveryAction,
    reconciled: int,
) -> None:
    admission = _admission(AdmissionAction.OBSERVE_OR_TAKEOVER)
    decision = RecoveryDecision(
        action=action,
        invoke_model=False,
        emit_attempt_reset=False,
        reason_code="EXISTING_TERMINAL_COMMAND",
    )
    gateway = _Gateway(admission, decision=decision)
    executor = _Executor()
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphContractError, match="exact replay from the Java durable"):
        _ = [event async for event in stream]

    assert gateway.reconciled == reconciled
    assert gateway.acquired == 0
    assert executor.calls == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_started_command_requires_a_new_public_attempt_without_executor_replay() -> None:
    admission = _admission(AdmissionAction.OBSERVE_OR_TAKEOVER)
    decision = RecoveryDecision(
        action=RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT,
        invoke_model=False,
        emit_attempt_reset=False,
        reason_code="MODEL_RESPONSE_NOT_CHECKPOINTED",
    )
    gateway = _Gateway(admission, decision=decision)
    executor = _Executor()
    service, _ = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphNewAgentAttemptRequiredError):
        _ = [event async for event in stream]

    assert gateway.acquired == 0
    assert executor.calls == 0


@pytest.mark.asyncio
async def test_observed_execution_cannot_resume_the_same_public_attempt() -> None:
    admission = _admission(AdmissionAction.OBSERVE_OR_TAKEOVER)
    gateway = _Gateway(
        admission,
        decision=RecoveryDecision(
            action=RecoveryAction.RESUME_BEFORE_MODEL,
            invoke_model=True,
            emit_attempt_reset=False,
            reason_code="MODEL_NOT_DURABLY_STARTED",
        ),
    )
    executor = _Executor()
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphNewAgentAttemptRequiredError):
        _ = [event async for event in stream]

    assert gateway.acquired == 0
    assert executor.calls == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_stream_service_rejects_any_request_to_generate_attempt_reset() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    gateway = _Gateway(
        admission,
        decision=SimpleNamespace(
            action=RecoveryAction.RESUME_BEFORE_MODEL,
            invoke_model=True,
            emit_attempt_reset=True,
            reason_code="INVALID_PYTHON_RESET",
        ),
    )
    executor = _Executor()
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphContractError, match="cannot create.*attempt reset"):
        _ = [event async for event in stream]

    assert gateway.acquired == 0
    assert executor.calls == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_executor_setup_failure_durably_aborts_the_acquired_attempt() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    gateway = _Gateway(admission)

    class RaisingExecutor:
        def __init__(self) -> None:
            self.calls = 0

        def stream(self, execution: GatewayExecution) -> Any:
            self.calls += 1
            raise RuntimeError("executor setup failed")

    executor = RaisingExecutor()
    service, gate = await _service(gateway, cast(Any, executor))

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(RuntimeError, match="executor setup failed"):
        _ = [event async for event in stream]

    assert gateway.acquired == 1
    assert gateway.finished == 1
    assert executor.calls == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_truncated_gateway_stream_aborts_attempt_and_never_fakes_a_terminal() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    gateway = _Gateway(admission)
    executor = _Executor(terminal=False)
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphContractError, match="without a terminal"):
        _ = [event async for event in stream]

    assert gateway.finished == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_exact_executor_provider_http_is_ledgered_before_transport() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    gateway = _Gateway(admission)
    executor = _ProviderCallingExecutor()
    service, gate = await _service(gateway, executor)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    events = [event async for event in stream]

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert gateway.provider_calls == 1
    assert executor.http_calls == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_active_execution_renews_lease_before_the_30_second_window() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    gateway = _Gateway(admission)
    executor = _Executor(delay_seconds=0.04)
    service, _ = await _service(gateway, executor, renewal_seconds=0.01)

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    events = [event async for event in stream]

    assert events[-1].event_type == "final"
    assert gateway.renewed >= 2
    assert gateway.finished == 0


@pytest.mark.asyncio
async def test_admission_gate_rejects_new_work_and_cancels_after_bounded_drain() -> None:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    entered = asyncio.Event()

    async def active_request() -> None:
        token = await gate.enter()
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            await gate.leave(token)

    task = asyncio.create_task(active_request())
    await entered.wait()

    assert await gate.drain(0.01) is False
    assert task.cancelled()
    assert gate.accepting is False
    with pytest.raises(GraphGatewayDisabledError):
        await gate.enter()


@pytest.mark.asyncio
async def test_admission_gate_drain_remains_bounded_when_cancellation_is_suppressed() -> None:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    entered = asyncio.Event()
    release = asyncio.Event()

    async def cancellation_resistant_request() -> None:
        token = await gate.enter()
        entered.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            await release.wait()
        finally:
            await gate.leave(token)

    task = asyncio.create_task(cancellation_resistant_request())
    await entered.wait()

    assert await asyncio.wait_for(gate.drain(0.01), timeout=0.1) is False
    assert task.done() is False
    release.set()
    await task


@pytest.mark.asyncio
async def test_admission_tokens_are_distinct_inside_one_request_task() -> None:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    first = await gate.enter()
    second = await gate.enter()

    await gate.leave(first)
    assert await gate.drain(0.01) is False

    await gate.leave(second)


@pytest.mark.asyncio
async def test_stream_cleanup_failure_cannot_leak_an_admission_token() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    service, gate = await _service(_Gateway(admission), _Executor())
    token = await gate.enter()

    class CloseFailingStream:
        def __init__(self) -> None:
            self.sent = False

        def __aiter__(self) -> CloseFailingStream:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            if not self.sent:
                self.sent = True
                return _event(admission.command, 0, "attempt_started")
            await asyncio.Event().wait()
            raise StopAsyncIteration

        async def aclose(self) -> None:
            raise RuntimeError("cleanup failed")

    guarded = service._guarded(CloseFailingStream(), token)
    assert (await anext(guarded)).event_type == "attempt_started"
    with pytest.raises(RuntimeError, match="cleanup failed"):
        await guarded.aclose()

    assert await gate.drain(0.01) is True
