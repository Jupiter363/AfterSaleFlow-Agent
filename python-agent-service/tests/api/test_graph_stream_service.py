from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import httpx
import anyio
import pytest
import app.api.graph_stream_service as graph_stream_service_module
from psycopg_pool import PoolTimeout
from pydantic import BaseModel

from app.api.graph_commands import (
    AgentStreamProtocolValidator,
    _encode_event,
    _stream_ndjson,
)
from app.api.graph_stream_service import (
    ExactShadowExecutorRegistry,
    GatewayBackedGraphCommandStreamService,
    GraphRetainedCleanupError,
    GraphStreamAdmissionGate,
    MAX_CANCEL_DRAIN_SECONDS,
    ProviderRuntimeBinding,
    ShadowExecutorRegistration,
)
from app.contracts.v1.codec import ContractCodec
from app.contracts.v1.models import AgentStreamEvent, AgentStreamPayload, RoomGraphCommand
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
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.recovery import RecoveryAction, RecoveryDecision
from app.graph_runtime.registry import RegistryRecord, RegistryState, VersionBinding
from app.llm import GovernedProviderRequest, LiteLlmProxyClient
from app.security.invocation_envelope import VerifiedInvocation


ROOT = Path(__file__).resolve().parents[3]
COMMAND_FIXTURE = ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-command-valid.json"
CONTRACT_ROOT = ROOT / "contracts/agent-platform/v1"
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


def _candidate_execution(admission: GatewayAdmission) -> GatewayExecution:
    execution = _execution(admission)
    command = admission.command
    return replace(
        execution,
        fence=replace(
            execution.fence,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
            activation_id=f"p9act.v1.{'a' * 32}",
            room_fencing_token=7,
            command_hash="c" * 64,
            command_envelope_hash="d" * 64,
            environment_id="target-e2e-local",
            environment_generation=1,
            tenant_surrogate=command.tenant_surrogate,
            case_id=command.case_id,
            room_type=command.room_type,
            binding_hash="e" * 64,
            code_build_id="candidate-build-1",
        ),
    )


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
        self.finished_statuses: list[AttemptStatus] = []
        self.provider_calls = 0
        self.cleaned_execution_leases = 0

    async def admit(self, **kwargs: Any) -> GatewayAdmission:
        return self.admission

    async def inspect_recovery(self, admission: GatewayAdmission) -> Any:
        return self.decision

    async def acquire_execution(self, admission: GatewayAdmission, **kwargs: Any):
        self.acquired += 1
        return _execution(admission)

    async def execute_stream(
        self,
        *,
        execution: GatewayExecution,
        executor: Any,
        durable_terminal_signal: asyncio.Event | None = None,
        terminal_processing_started: asyncio.Event | None = None,
    ):
        async for event in executor.stream(execution):
            if (
                terminal_processing_started is not None
                and event.event_type in {"attempt_aborted", "final", "error"}
            ):
                terminal_processing_started.set()
            if (
                durable_terminal_signal is not None
                and event.event_type in {"attempt_aborted", "final", "error"}
            ):
                durable_terminal_signal.set()
            yield event

    async def renew_execution(self, execution: GatewayExecution) -> LeaseRecord:
        self.renewed += 1
        return execution.lease

    def cleanup_execution_lease(self, execution: GatewayExecution) -> None:
        del execution
        self.cleaned_execution_leases += 1

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
        self.finished_statuses.append(kwargs["status"])
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
    provider_binding: ProviderRuntimeBinding | None = None,
    room_provider_bindings: tuple[tuple[str, ProviderRuntimeBinding], ...] = (),
) -> tuple[GatewayBackedGraphCommandStreamService, GraphStreamAdmissionGate]:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    registry = ExactShadowExecutorRegistry(
        [
            ShadowExecutorRegistration(
                gateway.admission.registry.binding,
                executor,
                provider_binding or _provider_binding(),
                room_provider_bindings,
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


async def _collect_stream(stream: Any) -> list[Any]:
    return [event async for event in stream]


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


@pytest.mark.asyncio
async def test_candidate_execution_freezes_resolved_provider_identity_before_executor() -> None:
    shadow_admission = _admission(AdmissionAction.ACQUIRE)
    admission = replace(
        shadow_admission,
        registry=replace(
            shadow_admission.registry,
            state=RegistryState.ACTIVE_CANDIDATE,
        ),
    )

    class CandidateGateway(_Gateway):
        async def acquire_execution(self, admission: GatewayAdmission, **kwargs: Any):
            self.acquired += 1
            return _candidate_execution(admission)

    class CapturingExecutor(_Executor):
        def __init__(self) -> None:
            super().__init__()
            self.execution: GatewayExecution | None = None

        async def stream(self, execution: GatewayExecution):
            self.execution = execution
            async for event in super().stream(execution):
                yield event

    gateway = CandidateGateway(admission)
    executor = CapturingExecutor()
    service, gate = await _service(gateway, executor)

    events = [
        event
        async for event in await service.open_stream(
            command=admission.command,
            verified_invocation=cast(VerifiedInvocation, object()),
            expected_thread=admission.thread,
        )
    ]

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert executor.execution is not None
    assert executor.execution.fence.execution_provider == "litellm"
    assert executor.execution.fence.execution_model == "qwen3.7-plus"
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
async def test_stream_cancellation_persists_fence_before_stopping_provider() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    ordering: list[str] = []
    provider_waiting = asyncio.Event()

    class CancellationAwareExecutor:
        async def stream(self, execution: GatewayExecution):
            yield _event(execution.admission.command, 0, "attempt_started")
            try:
                provider_waiting.set()
                await asyncio.Event().wait()
            finally:
                ordering.append("provider_stopped")

    class OrderingGateway(_Gateway):
        async def finish_execution_attempt(
            self,
            execution: GatewayExecution,
            **kwargs: Any,
        ):
            ordering.append("durable_attempt_aborted")
            return await super().finish_execution_attempt(execution, **kwargs)

    gateway = OrderingGateway(admission)
    service, gate = await _service(gateway, cast(Any, CancellationAwareExecutor()))
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    pending = asyncio.create_task(anext(stream))
    await provider_waiting.wait()
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending

    assert ordering == ["durable_attempt_aborted", "provider_stopped"]
    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.CANCELLED]
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_stream_prefetches_one_source_event_before_downstream_resumes() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    gateway = _Gateway(admission)
    service, _ = await _service(gateway, _Executor())
    second_pull_started = asyncio.Event()
    release_terminal = asyncio.Event()

    class PrefetchTrackingSource:
        def __init__(self) -> None:
            self.calls = 0

        def __aiter__(self) -> PrefetchTrackingSource:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            self.calls += 1
            if self.calls == 1:
                return _event(admission.command, 0, "attempt_started")
            if self.calls == 2:
                second_pull_started.set()
                await release_terminal.wait()
                return _event(admission.command, 1, "final")
            raise StopAsyncIteration

        async def aclose(self) -> None:
            return None

    source = PrefetchTrackingSource()
    stream = service._renewing_stream(source, execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(second_pull_started.wait(), timeout=0.1)
    assert source.calls == 2

    release_terminal.set()
    assert (await anext(stream)).event_type == "final"
    with pytest.raises(StopAsyncIteration):
        await anext(stream)


@pytest.mark.asyncio
async def test_lease_heartbeat_renews_while_downstream_pauses_after_attempt_started() -> None:
    """A slow first model response cannot make renewal depend on another HTTP pull."""

    admission = _admission(AdmissionAction.ACQUIRE)
    model_started = asyncio.Event()
    release_terminal = asyncio.Event()
    three_renewals = asyncio.Event()

    class CountingRenewGateway(_Gateway):
        def __init__(self, admission: GatewayAdmission) -> None:
            super().__init__(admission)
            self.renewal_input_revisions: list[int] = []

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            self.renewal_input_revisions.append(current.lease.revision)
            self.renewed += 1
            if self.renewed >= 3:
                three_renewals.set()
            return replace(current.lease, revision=current.lease.revision + 1)

    class ModelWaitExecutor(_Executor):
        async def stream(self, execution: GatewayExecution):
            self.calls += 1
            yield _event(execution.admission.command, 0, "attempt_started")
            model_started.set()
            await release_terminal.wait()
            yield _event(execution.admission.command, 1, "final")

    gateway = CountingRenewGateway(admission)
    service, gate = await _service(
        gateway,
        ModelWaitExecutor(),
        renewal_seconds=0.001,
    )
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(model_started.wait(), timeout=0.1)
    # Do not pull the public stream again: wait for three independent shortened ticks
    # through the gateway-side event so the test never sleeps for a wall-clock duration.
    await asyncio.wait_for(three_renewals.wait(), timeout=0.2)
    assert gateway.renewed >= 3
    assert gateway.renewal_input_revisions == list(range(gateway.renewed))

    release_terminal.set()
    assert (await anext(stream)).event_type == "final"
    with pytest.raises(StopAsyncIteration):
        await anext(stream)
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_lease_heartbeat_stops_when_durable_terminal_arrives_between_ticks() -> None:
    """A delivery barrier cannot cause a fresh renewal after durable lease release."""

    admission = _admission(AdmissionAction.ACQUIRE)
    model_waiting = asyncio.Event()
    release_terminal = asyncio.Event()
    barrier_entered = asyncio.Event()
    release_barrier = asyncio.Event()
    first_renewal = asyncio.Event()
    second_renewal = asyncio.Event()

    class BarrierGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            async for event in executor.stream(execution):
                if event.event_type == "final":
                    assert durable_terminal_signal is not None
                    assert terminal_processing_started is not None
                    terminal_processing_started.set()
                    durable_terminal_signal.set()
                    barrier_entered.set()
                    await release_barrier.wait()
                yield event

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            self.renewed += 1
            if self.renewed == 1:
                first_renewal.set()
            else:
                second_renewal.set()
            return current.lease

    class DelayedTerminalExecutor(_Executor):
        async def stream(self, execution: GatewayExecution):
            self.calls += 1
            yield _event(execution.admission.command, 0, "attempt_started")
            model_waiting.set()
            await release_terminal.wait()
            yield _event(execution.admission.command, 1, "final")

    gateway = BarrierGateway(admission)
    service, gate = await _service(
        gateway,
        DelayedTerminalExecutor(),
        renewal_seconds=0.005,
    )
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(model_waiting.wait(), timeout=0.1)
    await asyncio.wait_for(first_renewal.wait(), timeout=0.1)
    # The first timed renewal returns into the next interval before this task resumes.
    # Set durable while that interval and the post-commit delivery barrier are both
    # pending, without reading the public stream again.
    release_terminal.set()
    await asyncio.wait_for(barrier_entered.wait(), timeout=0.1)
    with pytest.raises(TimeoutError):
        await asyncio.wait_for(second_renewal.wait(), timeout=0.05)
    assert gateway.renewed == 1

    release_barrier.set()
    assert (await anext(stream)).event_type == "final"
    with pytest.raises(StopAsyncIteration):
        await anext(stream)
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_prefetched_source_is_cancelled_and_closed_on_downstream_disconnect() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    gateway = _Gateway(admission)
    service, _ = await _service(gateway, _Executor())
    second_pull_started = asyncio.Event()
    second_pull_cancelled = asyncio.Event()
    source_closed = asyncio.Event()

    class DisconnectAwareSource:
        def __init__(self) -> None:
            self.calls = 0

        def __aiter__(self) -> DisconnectAwareSource:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            self.calls += 1
            if self.calls == 1:
                return _event(admission.command, 0, "attempt_started")
            second_pull_started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                second_pull_cancelled.set()
                raise
            raise AssertionError("prefetched source pull unexpectedly completed")

        async def aclose(self) -> None:
            source_closed.set()

    source = DisconnectAwareSource()
    stream = service._renewing_stream(source, execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(second_pull_started.wait(), timeout=0.1)
    await asyncio.wait_for(stream.aclose(), timeout=0.1)

    assert second_pull_cancelled.is_set()
    assert source_closed.is_set()
    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.CANCELLED]


@pytest.mark.asyncio
async def test_preterminal_cleanup_quiesces_source_heartbeat_and_close_before_abort() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    source_pull_started = asyncio.Event()
    source_cancelled = asyncio.Event()
    release_source = asyncio.Event()
    heartbeat_started = asyncio.Event()
    order: list[str] = []

    class OrderedGateway(_Gateway):
        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            heartbeat_started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                order.append("heartbeat_joined")
                raise
            return current.lease

        async def finish_execution_attempt(self, current: GatewayExecution, **kwargs: Any):
            assert order == ["source_joined", "heartbeat_joined", "source_closed"]
            order.append("abort")
            return await super().finish_execution_attempt(current, **kwargs)

    class CheckpointBlockedSource:
        def __init__(self) -> None:
            self.calls = 0

        def __aiter__(self) -> CheckpointBlockedSource:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            self.calls += 1
            if self.calls == 1:
                return _event(admission.command, 0, "attempt_started")
            source_pull_started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                source_cancelled.set()
                await release_source.wait()
                order.append("source_joined")
                raise
            raise AssertionError("blocked checkpoint source unexpectedly resumed")

        async def aclose(self) -> None:
            order.append("source_closed")

    gateway = OrderedGateway(admission)
    service, _ = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = service._renewing_stream(CheckpointBlockedSource(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(source_pull_started.wait(), timeout=0.1)
    await asyncio.wait_for(heartbeat_started.wait(), timeout=0.1)
    close_task = asyncio.create_task(stream.aclose())
    await asyncio.wait_for(source_cancelled.wait(), timeout=0.1)
    assert gateway.finished == 0

    release_source.set()
    await asyncio.wait_for(close_task, timeout=0.5)

    assert order == ["source_joined", "heartbeat_joined", "source_closed", "abort"]
    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.CANCELLED]


@pytest.mark.asyncio
async def test_quiesce_grace_timeout_retains_cleanup_until_exact_abort(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(graph_stream_service_module, "SOURCE_QUIESCE_MAX_SECONDS", 0.0)
    monkeypatch.setattr(
        graph_stream_service_module,
        "SOURCE_QUIESCE_POST_DEADLINE_GRACE_SECONDS",
        0.01,
    )
    monkeypatch.setattr(
        graph_stream_service_module,
        "_RETAINED_ABORT_RETRY_INITIAL_SECONDS",
        0.001,
    )
    admission = _admission(AdmissionAction.ACQUIRE)
    past_deadline = admission.command.model_copy(
        update={"deadline_at": datetime.now(timezone.utc) - timedelta(seconds=1)}
    )
    admission = replace(admission, command=past_deadline)
    execution = _execution(admission)
    release_source = asyncio.Event()
    source_cancelled = asyncio.Event()

    class SlowCheckpointSource:
        def __init__(self) -> None:
            self.calls = 0

        def __aiter__(self) -> SlowCheckpointSource:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            self.calls += 1
            if self.calls == 1:
                return _event(admission.command, 0, "attempt_started")
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                source_cancelled.set()
                await release_source.wait()
                raise
            raise AssertionError("slow checkpoint source unexpectedly resumed")

        async def aclose(self) -> None:
            return None

    gateway = _Gateway(admission)
    service, gate = await _service(gateway, _Executor(), renewal_seconds=1)
    stream = service._renewing_stream(SlowCheckpointSource(), execution)
    assert (await anext(stream)).event_type == "attempt_started"
    close_task = asyncio.create_task(stream.aclose())
    await asyncio.wait_for(source_cancelled.wait(), timeout=0.1)

    with pytest.raises(GraphContractError, match="cleanup grace"):
        await asyncio.wait_for(close_task, timeout=0.2)
    assert gateway.finished == 0
    assert gate.accepting is False

    release_source.set()
    for _ in range(100):
        if gateway.finished == 1 and gate.accepting:
            break
        await asyncio.sleep(0.001)

    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.CANCELLED]
    assert gate.accepting is True
    assert gate.cleanup_failure is None


@pytest.mark.asyncio
async def test_abort_failure_is_surfaced_while_retained_retry_terminalizes_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        graph_stream_service_module,
        "_RETAINED_ABORT_RETRY_INITIAL_SECONDS",
        0.001,
    )
    monkeypatch.setattr(
        graph_stream_service_module,
        "_RETAINED_ABORT_RETRY_MAX_SECONDS",
        0.001,
    )
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)

    class RetryAbortGateway(_Gateway):
        def __init__(self) -> None:
            super().__init__(admission)
            self.abort_calls = 0

        async def finish_execution_attempt(self, current: GatewayExecution, **kwargs: Any):
            self.abort_calls += 1
            if self.abort_calls == 1:
                raise PoolTimeout("abort control pool busy")
            return await super().finish_execution_attempt(current, **kwargs)

    async def source() -> AsyncIterator[AgentStreamEvent]:
        yield _event(admission.command, 0, "attempt_started")
        await asyncio.Event().wait()

    gateway = RetryAbortGateway()
    service, gate = await _service(gateway, _Executor(), renewal_seconds=1)
    stream = service._renewing_stream(source(), execution)
    assert (await anext(stream)).event_type == "attempt_started"

    with pytest.raises(PoolTimeout, match="abort control pool busy"):
        await stream.aclose()

    for _ in range(100):
        if gateway.finished == 1 and gate.accepting:
            break
        await asyncio.sleep(0.001)

    assert gateway.abort_calls == 2
    assert gateway.finished == 1
    assert gate.cleanup_failure is None


@pytest.mark.asyncio
async def test_nontransient_retained_abort_failure_closes_admission_fail_closed() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)

    class RejectingAbortGateway(_Gateway):
        async def finish_execution_attempt(self, current: GatewayExecution, **kwargs: Any):
            raise GraphContractError("abort binding mismatch")

    async def source() -> AsyncIterator[AgentStreamEvent]:
        yield _event(admission.command, 0, "attempt_started")
        await asyncio.Event().wait()

    gateway = RejectingAbortGateway(admission)
    service, gate = await _service(gateway, _Executor(), renewal_seconds=1)
    stream = service._renewing_stream(source(), execution)
    assert (await anext(stream)).event_type == "attempt_started"

    with pytest.raises(GraphContractError, match="abort binding mismatch"):
        await stream.aclose()
    for _ in range(100):
        if gate.cleanup_failure is not None:
            break
        await asyncio.sleep(0.001)

    assert isinstance(gate.cleanup_failure, GraphContractError)
    assert gate.accepting is False
    with pytest.raises(GraphRetainedCleanupError, match="retained Graph cleanup failed"):
        await gate.drain(0.05)


@pytest.mark.asyncio
async def test_prefetched_source_exception_is_propagated_on_the_next_read() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    gateway = _Gateway(admission)
    service, _ = await _service(gateway, _Executor())
    source_failed = asyncio.Event()

    class FailingPrefetchSource:
        def __init__(self) -> None:
            self.calls = 0

        def __aiter__(self) -> FailingPrefetchSource:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            self.calls += 1
            if self.calls == 1:
                return _event(admission.command, 0, "attempt_started")
            source_failed.set()
            raise RuntimeError("prefetched source failed")

        async def aclose(self) -> None:
            return None

    stream = service._renewing_stream(FailingPrefetchSource(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(source_failed.wait(), timeout=0.1)
    with pytest.raises(RuntimeError, match="prefetched source failed"):
        await anext(stream)

    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.FAILED]


@pytest.mark.asyncio
async def test_terminal_reconciliation_release_does_not_surface_a_renewal_lease_loss() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    release_terminal = asyncio.Event()
    source_waiting = asyncio.Event()
    terminal_reconciled = asyncio.Event()
    renewal_waiting = asyncio.Event()
    lease_lost = asyncio.Event()

    class LeaseLossGateway(_Gateway):
        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            del current
            self.renewed += 1
            renewal_waiting.set()
            await terminal_reconciled.wait()
            lease_lost.set()
            raise GraphLeaseLostError("lease was displaced")

    class ReconciledTerminalSource:
        def __init__(self) -> None:
            self.calls = 0

        def __aiter__(self) -> ReconciledTerminalSource:
            return self

        async def __anext__(self) -> AgentStreamEvent:
            self.calls += 1
            if self.calls == 1:
                return _event(admission.command, 0, "attempt_started")
            source_waiting.set()
            await release_terminal.wait()
            # This mirrors Gateway.execute_stream: it only yields ``final`` after
            # durable terminal reconciliation has released the exact lease.
            terminal_reconciled.set()
            await lease_lost.wait()
            return _event(admission.command, 1, "final")

        async def aclose(self) -> None:
            return None

    gateway = LeaseLossGateway(admission)
    service, _ = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = service._renewing_stream(ReconciledTerminalSource(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(source_waiting.wait(), timeout=0.1)
    await asyncio.wait_for(renewal_waiting.wait(), timeout=0.1)
    release_terminal.set()
    await asyncio.wait_for(terminal_reconciled.wait(), timeout=0.1)
    await asyncio.wait_for(lease_lost.wait(), timeout=0.1)
    await asyncio.sleep(0)

    assert (await anext(stream)).event_type == "final"
    with pytest.raises(StopAsyncIteration):
        await anext(stream)

    assert gateway.finished == 0


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "renewal_failure_type",
    (GraphLeaseLostError, RuntimeError),
)
async def test_durable_terminal_signal_suppresses_renewal_failure_while_prefetched_final_waits_barrier(
    renewal_failure_type: type[Exception],
) -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    barrier_entered = asyncio.Event()
    release_barrier = asyncio.Event()
    renewal_waiting = asyncio.Event()
    renewal_failed = asyncio.Event()

    class BarrierPendingGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            async for event in executor.stream(execution):
                if event.event_type == "final":
                    assert durable_terminal_signal is not None
                    assert terminal_processing_started is not None
                    terminal_processing_started.set()
                    # Make the overlap explicit: the in-flight renewal began while
                    # the lease was still active, so its post-release failure must be
                    # suppressed after the durable terminal signal.
                    await renewal_waiting.wait()
                    # Mirrors GraphCommandGateway: durable reconciliation/release is
                    # complete before the final delivery barrier is allowed to wait.
                    durable_terminal_signal.set()
                    barrier_entered.set()
                    await release_barrier.wait()
                yield event

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            del current
            self.renewed += 1
            renewal_waiting.set()
            await barrier_entered.wait()
            renewal_failed.set()
            raise renewal_failure_type("renewal failed after durable terminal")

    gateway = BarrierPendingGateway(admission)
    service, gate = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(barrier_entered.wait(), timeout=0.1)
    await asyncio.wait_for(renewal_waiting.wait(), timeout=0.1)
    await asyncio.wait_for(renewal_failed.wait(), timeout=0.1)

    pending_final = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    assert pending_final.done() is False
    assert gateway.finished == 0

    release_barrier.set()
    assert (await pending_final).event_type == "final"
    with pytest.raises(StopAsyncIteration):
        await anext(stream)
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_durable_terminal_signal_skips_abort_when_downstream_disconnects_during_barrier() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    barrier_entered = asyncio.Event()
    source_closed = asyncio.Event()

    class BarrierPendingGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            try:
                async for event in executor.stream(execution):
                    if event.event_type == "final":
                        assert durable_terminal_signal is not None
                        assert terminal_processing_started is not None
                        terminal_processing_started.set()
                        durable_terminal_signal.set()
                        barrier_entered.set()
                        await asyncio.Event().wait()
                    yield event
            finally:
                source_closed.set()

    gateway = BarrierPendingGateway(admission)
    service, gate = await _service(gateway, _Executor())
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(barrier_entered.wait(), timeout=0.1)
    await asyncio.wait_for(stream.aclose(), timeout=0.1)

    await asyncio.wait_for(source_closed.wait(), timeout=0.1)
    assert gateway.finished == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_durable_terminal_signal_propagates_barrier_failure_without_reaborting() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    barrier_entered = asyncio.Event()

    class BarrierFailureGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            async for event in executor.stream(execution):
                if event.event_type == "final":
                    assert durable_terminal_signal is not None
                    assert terminal_processing_started is not None
                    terminal_processing_started.set()
                    durable_terminal_signal.set()
                    barrier_entered.set()
                    raise RuntimeError("post-commit barrier failed")
                yield event

    gateway = BarrierFailureGateway(admission)
    service, gate = await _service(gateway, _Executor())
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(barrier_entered.wait(), timeout=0.1)
    with pytest.raises(RuntimeError, match="post-commit barrier failed"):
        await anext(stream)

    assert gateway.finished == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
@pytest.mark.parametrize("renewal_failure_type", (GraphLeaseLostError, RuntimeError))
async def test_terminal_processing_defers_any_renewal_failure_until_durable_final(
    renewal_failure_type: type[Exception],
) -> None:
    """A terminal commit's scheduler gap cannot let renewal cancel its source."""

    admission = _admission(AdmissionAction.ACQUIRE)
    processing_entered = asyncio.Event()
    release_durable_commit = asyncio.Event()
    renewal_failed = asyncio.Event()

    class ProcessingGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            async for event in executor.stream(execution):
                if event.event_type == "final":
                    assert durable_terminal_signal is not None
                    assert terminal_processing_started is not None
                    # This represents the instant after the envelope passed all
                    # structural checks but before reconciliation can commit.
                    terminal_processing_started.set()
                    processing_entered.set()
                    await release_durable_commit.wait()
                    durable_terminal_signal.set()
                yield event

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            del current
            self.renewed += 1
            await processing_entered.wait()
            renewal_failed.set()
            raise renewal_failure_type("renewal failed while final commit was in flight")

    gateway = ProcessingGateway(admission)
    service, gate = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(processing_entered.wait(), timeout=0.1)
    await asyncio.wait_for(renewal_failed.wait(), timeout=0.1)

    pending_final = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    assert pending_final.done() is False
    assert gateway.finished == 0

    release_durable_commit.set()
    assert (await pending_final).event_type == "final"
    with pytest.raises(StopAsyncIteration):
        await anext(stream)
    assert gateway.finished == 0
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_terminal_processing_source_failure_before_durable_completion_fails_closed() -> None:
    """The deferral fence does not turn a failed terminal operation into success."""

    admission = _admission(AdmissionAction.ACQUIRE)
    processing_entered = asyncio.Event()
    release_failed_reconciliation = asyncio.Event()
    renewal_failed = asyncio.Event()

    class ProcessingFailureGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            del durable_terminal_signal
            async for event in executor.stream(execution):
                if event.event_type == "final":
                    assert terminal_processing_started is not None
                    terminal_processing_started.set()
                    processing_entered.set()
                    await release_failed_reconciliation.wait()
                    raise RuntimeError("terminal reconciliation failed before commit")
                yield event

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            del current
            self.renewed += 1
            await processing_entered.wait()
            renewal_failed.set()
            raise GraphLeaseLostError("renewal failed while reconciliation was pending")

    gateway = ProcessingFailureGateway(admission)
    service, gate = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(processing_entered.wait(), timeout=0.1)
    await asyncio.wait_for(renewal_failed.wait(), timeout=0.1)

    pending_final = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    assert pending_final.done() is False

    release_failed_reconciliation.set()
    with pytest.raises(RuntimeError, match="reconciliation failed before commit"):
        await pending_final

    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.FAILED]
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_downstream_disconnect_bounds_pre_durable_terminal_processing() -> None:
    """Disconnects wait briefly for a valid terminal commit, then retain cancellation safety."""

    admission = _admission(AdmissionAction.ACQUIRE)
    processing_entered = asyncio.Event()
    source_closed = asyncio.Event()

    class HangingProcessingGateway(_Gateway):
        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            del durable_terminal_signal
            try:
                async for event in executor.stream(execution):
                    if event.event_type == "final":
                        assert terminal_processing_started is not None
                        terminal_processing_started.set()
                        processing_entered.set()
                        await asyncio.Event().wait()
                    yield event
            finally:
                source_closed.set()

    gateway = HangingProcessingGateway(admission)
    service, gate = await _service(gateway, _Executor())
    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(processing_entered.wait(), timeout=0.1)

    loop = asyncio.get_running_loop()
    started_at = loop.time()
    await asyncio.wait_for(stream.aclose(), timeout=MAX_CANCEL_DRAIN_SECONDS + 0.5)
    elapsed = loop.time() - started_at

    # The bounded drain protects a terminal commit already in the gateway, while the
    # timeout guarantees a stuck persistence operation cannot leak the HTTP attempt.
    assert elapsed >= MAX_CANCEL_DRAIN_SECONDS * 0.75
    assert elapsed < MAX_CANCEL_DRAIN_SECONDS + 0.5
    await asyncio.wait_for(source_closed.wait(), timeout=0.1)
    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.CANCELLED]
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_bounded_disconnect_cleanup_removes_a_late_heartbeat_lease_cache_entry() -> None:
    """A cancellation-resistant renew cannot repopulate the exact cache after teardown."""

    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    release_renew = asyncio.Event()

    class LateRenewGateway(_Gateway):
        def __init__(self) -> None:
            super().__init__(admission)
            self.renew_started = asyncio.Event()
            self.renew_cancelled = asyncio.Event()
            self.lease_cached = False
            self.cleaned_lease_revisions: list[int] = []

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            self.renewed += 1
            self.renew_started.set()
            try:
                await release_renew.wait()
            except asyncio.CancelledError:
                self.renew_cancelled.set()
                await release_renew.wait()
            self.lease_cached = True
            return replace(current.lease, revision=current.lease.revision + 1)

        def cleanup_execution_lease(self, current: GatewayExecution) -> None:
            self.cleaned_execution_leases += 1
            self.cleaned_lease_revisions.append(current.lease.revision)
            self.lease_cached = False

    async def source() -> AsyncIterator[AgentStreamEvent]:
        yield _event(admission.command, 0, "attempt_started")
        await asyncio.Event().wait()

    gateway = LateRenewGateway()
    service, _ = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = service._renewing_stream(source(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(gateway.renew_started.wait(), timeout=0.1)

    close_task = asyncio.create_task(stream.aclose())
    await asyncio.wait_for(gateway.renew_cancelled.wait(), timeout=0.1)
    await asyncio.wait_for(close_task, timeout=MAX_CANCEL_DRAIN_SECONDS + 0.3)

    # The heartbeat is still alive, so cleanup is deferred rather than allowing
    # its late successful renewal to reinsert the exact lease cache after teardown.
    assert gateway.cleaned_execution_leases == 0
    release_renew.set()
    await asyncio.sleep(0)
    await asyncio.sleep(0)

    assert gateway.lease_cached is False
    assert gateway.cleaned_execution_leases == 1
    assert gateway.cleaned_lease_revisions == [1]


@pytest.mark.asyncio
async def test_preterminal_lease_loss_remains_fail_closed_with_a_prefetched_source() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    release_lease_loss = asyncio.Event()
    source_waiting = asyncio.Event()
    renewal_waiting = asyncio.Event()

    class LeaseLossGateway(_Gateway):
        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            del current
            self.renewed += 1
            renewal_waiting.set()
            await release_lease_loss.wait()
            raise GraphLeaseLostError("lease was displaced before terminal")

    async def source() -> AsyncIterator[AgentStreamEvent]:
        yield _event(admission.command, 0, "attempt_started")
        source_waiting.set()
        await asyncio.Event().wait()
        raise AssertionError("preterminal source pull unexpectedly completed")

    gateway = LeaseLossGateway(admission)
    service, _ = await _service(gateway, _Executor(), renewal_seconds=0.001)
    stream = service._renewing_stream(source(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(source_waiting.wait(), timeout=0.1)
    await asyncio.wait_for(renewal_waiting.wait(), timeout=0.1)
    release_lease_loss.set()

    with pytest.raises(GraphLeaseLostError, match="before terminal"):
        await anext(stream)

    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.FAILED]


@pytest.mark.asyncio
@pytest.mark.parametrize("event_type", ("final", "error"))
async def test_downstream_close_does_not_cancel_a_prefetched_durable_terminal(
    event_type: str,
) -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    gateway = _Gateway(admission)
    service, _ = await _service(gateway, _Executor())
    terminal_prefetched = asyncio.Event()
    source_closed = asyncio.Event()

    async def source() -> AsyncIterator[AgentStreamEvent]:
        try:
            yield _event(admission.command, 0, "attempt_started")
            terminal_prefetched.set()
            yield cast(Any, SimpleNamespace(event_type=event_type))
        finally:
            source_closed.set()

    stream = service._renewing_stream(source(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(terminal_prefetched.wait(), timeout=0.1)
    await asyncio.wait_for(stream.aclose(), timeout=0.1)

    assert source_closed.is_set()
    assert gateway.finished == 0


@pytest.mark.asyncio
async def test_downstream_close_records_a_prefetched_source_exception_as_failed() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)
    gateway = _Gateway(admission)
    service, _ = await _service(gateway, _Executor())
    source_failed = asyncio.Event()
    source_closed = asyncio.Event()

    async def source() -> AsyncIterator[AgentStreamEvent]:
        try:
            yield _event(admission.command, 0, "attempt_started")
            source_failed.set()
            raise RuntimeError("prefetched source failed during downstream close")
        finally:
            source_closed.set()

    stream = service._renewing_stream(source(), execution)

    assert (await anext(stream)).event_type == "attempt_started"
    await asyncio.wait_for(source_failed.wait(), timeout=0.1)
    with pytest.raises(RuntimeError, match="during downstream close"):
        await asyncio.wait_for(stream.aclose(), timeout=0.1)

    assert source_closed.is_set()
    assert gateway.finished == 1
    assert gateway.finished_statuses == [AttemptStatus.FAILED]


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
async def test_candidate_room_binding_uses_the_exact_intake_provider_identity() -> None:
    shadow_admission = _admission(AdmissionAction.ACQUIRE)
    admission = replace(
        shadow_admission,
        registry=replace(
            shadow_admission.registry,
            state=RegistryState.ACTIVE_CANDIDATE,
        ),
    )

    class CandidateGateway(_Gateway):
        async def acquire_execution(self, admission: GatewayAdmission, **kwargs: Any):
            self.acquired += 1
            return _candidate_execution(admission)

    gateway = CandidateGateway(admission)
    executor = _ProviderCallingExecutor()
    composite_binding = ProviderRuntimeBinding(
        model_profile_id=admission.registry.binding.model_profile_id,
        provider="target-e2e-composite",
        model="room-provider-dispatch",
        allowed_nodes=frozenset({"INTAKE"}),
    )
    service, gate = await _service(
        gateway,
        executor,
        provider_binding=composite_binding,
        room_provider_bindings=(("INTAKE", _provider_binding()),),
    )

    events = [
        event
        async for event in await service.open_stream(
            command=admission.command,
            verified_invocation=cast(VerifiedInvocation, object()),
            expected_thread=admission.thread,
        )
    ]

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert gateway.provider_calls == 1
    assert executor.http_calls == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_provider_intent_mismatch_remains_a_graph_contract_error() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    gateway = _Gateway(admission)
    executor = _ProviderCallingExecutor()
    mismatched = replace(_provider_binding(), provider="another-provider")
    service, gate = await _service(
        gateway,
        executor,
        provider_binding=mismatched,
    )

    stream = await service.open_stream(
        command=admission.command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=admission.thread,
    )
    with pytest.raises(GraphContractError, match="provider call intent conflicts"):
        _ = [event async for event in stream]

    assert gateway.provider_calls == 0
    assert executor.http_calls == 0
    assert gateway.finished == 1
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
@pytest.mark.parametrize("event_type", ("final", "attempt_aborted", "error"))
async def test_terminal_stream_waits_for_inflight_renew_before_exact_cache_cleanup(
    event_type: str,
) -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)

    class DelayedRenewGateway(_Gateway):
        def __init__(self) -> None:
            super().__init__(admission)
            self.renew_started = asyncio.Event()
            self.renew_cancelled = asyncio.Event()
            self.release_renew = asyncio.Event()
            self.lease_cache: dict[tuple[str, str, str, int], LeaseRecord] = {}

        @staticmethod
        def _key(current: GatewayExecution) -> tuple[str, str, str, int]:
            return (
                current.fence.thread_id,
                current.fence.command_id,
                current.fence.owner_id,
                current.fence.fencing_token,
            )

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            self.renewed += 1
            self.renew_started.set()
            try:
                await self.release_renew.wait()
            except asyncio.CancelledError:
                self.renew_cancelled.set()
                await self.release_renew.wait()
            self.lease_cache[self._key(current)] = current.lease
            return current.lease

        def cleanup_execution_lease(self, current: GatewayExecution) -> None:
            super().cleanup_execution_lease(current)
            self.lease_cache.pop(self._key(current), None)

    gateway = DelayedRenewGateway()
    service, _ = await _service(gateway, _Executor(), renewal_seconds=0.001)

    async def source():
        await gateway.renew_started.wait()
        yield cast(Any, SimpleNamespace(event_type=event_type))

    task = asyncio.create_task(_collect_stream(service._renewing_stream(source(), execution)))
    await gateway.renew_cancelled.wait()
    gateway.release_renew.set()

    events = await task

    assert [event.event_type for event in events] == [event_type]
    assert gateway.lease_cache == {}
    assert gateway.cleaned_execution_leases == 1


@pytest.mark.asyncio
async def test_exceptional_stream_waits_for_inflight_renew_before_exact_cache_cleanup() -> None:
    admission = _admission(AdmissionAction.ACQUIRE)
    execution = _execution(admission)

    class DelayedRenewGateway(_Gateway):
        def __init__(self) -> None:
            super().__init__(admission)
            self.renew_started = asyncio.Event()
            self.renew_cancelled = asyncio.Event()
            self.release_renew = asyncio.Event()
            self.lease_cached = False

        async def renew_execution(self, current: GatewayExecution) -> LeaseRecord:
            self.renewed += 1
            self.renew_started.set()
            try:
                await self.release_renew.wait()
            except asyncio.CancelledError:
                self.renew_cancelled.set()
                await self.release_renew.wait()
            self.lease_cached = True
            return current.lease

        def cleanup_execution_lease(self, current: GatewayExecution) -> None:
            del current
            self.cleaned_execution_leases += 1
            self.lease_cached = False

    gateway = DelayedRenewGateway()
    service, _ = await _service(gateway, _Executor(), renewal_seconds=0.001)

    async def source():
        await gateway.renew_started.wait()
        raise RuntimeError("source failed")
        yield cast(Any, SimpleNamespace(event_type="final"))

    task = asyncio.create_task(_collect_stream(service._renewing_stream(source(), execution)))
    await gateway.renew_cancelled.wait()
    gateway.release_renew.set()

    with pytest.raises(RuntimeError, match="source failed"):
        await task

    assert gateway.lease_cached is False
    assert gateway.cleaned_execution_leases == 1


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

    with pytest.raises(GraphRetainedCleanupError, match="did not transfer or quiesce"):
        await asyncio.wait_for(gate.drain(0.01), timeout=0.1)
    assert task.done() is False
    release.set()
    await task


@pytest.mark.asyncio
async def test_admission_gate_never_cancels_retained_cleanup_and_keeps_timed_out_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        graph_stream_service_module,
        "RETAINED_CLEANUP_LIFECYCLE_SECONDS",
        0.01,
    )
    gate = GraphStreamAdmissionGate()
    await gate.start()
    retained_started = asyncio.Event()
    retained_cancelled = asyncio.Event()
    release_retained = asyncio.Event()

    async def retained_cleanup() -> None:
        retained_started.set()
        try:
            await release_retained.wait()
        except asyncio.CancelledError:
            retained_cancelled.set()
            raise

    retained_task = asyncio.create_task(retained_cleanup())
    await asyncio.wait_for(retained_started.wait(), timeout=0.1)
    await gate.retain_cleanup(retained_task)

    with pytest.raises(GraphRetainedCleanupError, match="lifecycle drain bound"):
        await asyncio.wait_for(gate.drain(0.001), timeout=0.1)

    assert retained_task.done() is False
    assert retained_cancelled.is_set() is False
    assert gate.accepting is False
    release_retained.set()
    await asyncio.wait_for(retained_task, timeout=0.1)
    assert await gate.drain(0.05) is True


@pytest.mark.asyncio
async def test_admission_gate_resnapshots_retained_transfer_before_drain_returns() -> None:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    request_entered = asyncio.Event()
    retained_transferred = asyncio.Event()
    retained_cancelled = asyncio.Event()
    release_retained = asyncio.Event()

    async def retained_cleanup() -> None:
        try:
            await release_retained.wait()
        except asyncio.CancelledError:
            retained_cancelled.set()
            raise

    async def ordinary_request() -> None:
        token = await gate.enter()
        request_entered.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            await gate.retain_cleanup(asyncio.create_task(retained_cleanup()))
            retained_transferred.set()
        finally:
            await gate.leave(token)

    request_task = asyncio.create_task(ordinary_request())
    await asyncio.wait_for(request_entered.wait(), timeout=0.1)
    drain_task = asyncio.create_task(gate.drain(0.01))
    await asyncio.wait_for(retained_transferred.wait(), timeout=0.1)
    await asyncio.sleep(0)

    assert request_task.done()
    assert drain_task.done() is False
    assert retained_cancelled.is_set() is False
    release_retained.set()
    assert await asyncio.wait_for(drain_task, timeout=0.1) is False


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


def test_http_disconnect_level_cancellation_durably_aborts_preterminal_target_stream() -> None:
    """The ASGI body's AnyIO cancellation must reach the durable graph cleanup path."""

    async def exercise_disconnect() -> None:
        admission = _admission(AdmissionAction.ACQUIRE)
        source_closed = asyncio.Event()
        source_waiting = asyncio.Event()

        class BlockingExecutor(_Executor):
            async def stream(self, execution: GatewayExecution):
                try:
                    yield _event(execution.admission.command, 0, "attempt_started")
                    source_waiting.set()
                    await asyncio.Event().wait()
                finally:
                    source_closed.set()

        gateway = _Gateway(admission)
        service, gate = await _service(gateway, BlockingExecutor())
        iterator = await service.open_stream(
            command=admission.command,
            verified_invocation=cast(VerifiedInvocation, object()),
            expected_thread=admission.thread,
        )
        first = await anext(iterator)
        await source_waiting.wait()
        validator = AgentStreamProtocolValidator(
            run_id=admission.command.logical_run_id,
            attempt_id=admission.command.attempt_id,
            audience=admission.command.actor_scope.audience,
        )
        codec = ContractCodec(CONTRACT_ROOT)
        body = _stream_ndjson(
            codec=codec,
            iterator=iterator,
            validator=validator,
            first_line=_encode_event(codec, validator, first),
        )

        assert await anext(body)
        with anyio.CancelScope() as scope:
            scope.cancel()
            await body.aclose()

        assert source_closed.is_set()
        assert gateway.finished == 1
        assert gateway.finished_statuses == [AttemptStatus.CANCELLED]
        assert gateway.cleaned_execution_leases == 1
        assert await gate.drain(0.01) is True

    anyio.run(exercise_disconnect)
