from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

import pytest

import app.llm as llm_module
from app.api.graph_stream_service import GraphStreamAdmissionGate
from app.api.intake_parallel_stream import (
    ExpectedParallelFrame,
    ParallelFrameAdmissionLane,
    ParallelFrameAdmissionReceipt,
    ParallelFrameStreamAuthority,
    parallel_frame_authority_sha256,
)
from app.api.intake_parallel_stream_service import (
    GatewayBackedParallelIntakeFrameStreamService,
    _batch_failure_is_retryable,
)
from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.gateway import AdmissionAction
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.ledger import AttemptStatus
from app.graph_runtime.recovery import RecoveryAction
from app.graphs.intake.parallel_contracts import FRAME_TYPES, ParallelFrameType
from app.graphs.intake.parallel_graph import (
    FRAME_NODE_NAMES,
    FrameProviderUsage,
    FrameInterrupted,
    FrameSealed,
    FrameStarted,
    ParallelFrameBatchResult,
    ParallelFrameFailure,
)
from app.llm import ProviderCallIntent


FRAME_SET_ID = "IFS_test"
RUN_ID = "run_test"
ATTEMPT_ID = "attempt_test"
CONTEXT_HASH = "a" * 64
MODEL_CONTEXT_HASH = "b" * 64


@dataclass(frozen=True)
class _FakeModelContext:
    model_context_view_sha256: str = MODEL_CONTEXT_HASH


@dataclass(frozen=True)
class _FakeInstructionPack:
    frame_prompt_sha256: str = "c" * 64


@dataclass(frozen=True)
class _FakeModelInput:
    frame_model_input_sha256: str
    common_model_context: _FakeModelContext = _FakeModelContext()
    instruction_pack: _FakeInstructionPack = _FakeInstructionPack()


@dataclass(frozen=True)
class _FakeRequest:
    frame_set_id: str
    run_id: str
    attempt_id: str
    frame_type: ParallelFrameType
    generation: int
    frame_id: str
    context_envelope_sha256: str
    model_input: _FakeModelInput
    emit_start: bool = True

    def model_copy(self, *, update: dict[str, Any]) -> "_FakeRequest":
        return replace(self, **update)


class _FakeValidator:
    def __init__(
        self,
        authority: ParallelFrameStreamAuthority,
        _active_frame_types: tuple[ParallelFrameType, ...] = FRAME_TYPES,
    ) -> None:
        self.authority = authority
        self.events: list[Any] = []

    def accept(self, event: Any) -> None:
        self.events.append(event)

    def finish(self) -> None:
        return None


class _Gateway:
    def __init__(self, *, cached: bool = False) -> None:
        self.cached = cached
        self.execution = _execution()
        self.completed: list[Any] = []
        self.finished: list[tuple[AttemptStatus, str]] = []
        self.acquire_calls = 0
        self.load_calls = 0
        self.receipt_cycle: Any | None = None
        self.completed_cycles: list[Any] = []

    async def admit(self, **_kwargs: Any) -> Any:
        return SimpleNamespace(
            action=(
                AdmissionAction.RETURN_TECHNICAL_CACHED if self.cached else AdmissionAction.ACQUIRE
            ),
            command=self.execution.admission.command,
        )

    async def inspect_recovery(self, _admission: Any) -> Any:
        return SimpleNamespace(
            action=RecoveryAction.RESUME_BEFORE_MODEL,
            emit_attempt_reset=False,
            invoke_model=True,
        )

    async def acquire_execution(self, _admission: Any, **_kwargs: Any) -> Any:
        self.acquire_calls += 1
        return self.execution

    async def resume_parallel_technical_execution(
        self,
        _admission: Any,
        **_kwargs: Any,
    ) -> Any:
        self.acquire_calls += 1
        return self.execution

    async def bind_parallel_receipt_execution(
        self,
        execution: Any,
        **_kwargs: Any,
    ) -> Any:
        return execution

    async def load_parallel_receipt_cycle(
        self,
        _admission: Any,
        **_kwargs: Any,
    ) -> Any | None:
        return self.receipt_cycle

    async def complete_parallel_receipt_cycle(
        self,
        execution: Any,
        *,
        cycle: Any,
    ) -> Any:
        self.completed_cycles.append(cycle)
        return execution

    async def renew_execution(self, _execution: Any) -> Any:
        return SimpleNamespace()

    async def record_provider_call(self, execution: Any) -> Any:
        return SimpleNamespace(
            **{
                **vars(execution),
                "attempt": SimpleNamespace(
                    **{
                        **vars(execution.attempt),
                        "provider_call_count": (
                            execution.attempt.provider_call_count + 1
                        ),
                    }
                ),
            }
        )

    async def complete_technical_execution(
        self,
        execution: Any,
        *,
        completion: Any,
    ) -> Any:
        self.completed.append(completion)
        return execution

    async def finish_execution_attempt(
        self,
        execution: Any,
        *,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> Any:
        del error_classification
        self.finished.append((status, error_code))
        return execution

    async def load_technical_completion(self, _admission: Any) -> Any:
        self.load_calls += 1
        return SimpleNamespace(completion_json={"cached": True})

    def cleanup_execution_lease(self, _execution: Any) -> None:
        return None


class _SuccessfulOrchestrator:
    def __init__(self, requests: tuple[_FakeRequest, ...]) -> None:
        self.requests = requests
        self.started = asyncio.Event()
        self.first_seal_emitted = asyncio.Event()
        self.continue_remaining = asyncio.Event()
        self.calls = 0

    async def execute(self, requests: Any, *, event_sink: Any, **_kwargs: Any) -> Any:
        self.calls += 1
        self.started.set()
        completed: dict[ParallelFrameType, Any] = {}
        first, *remaining = tuple(requests)
        sealed = _seal(first)
        await event_sink.emit(sealed)
        completed[first.frame_type] = SimpleNamespace(frame_type=first.frame_type)
        self.first_seal_emitted.set()
        await self.continue_remaining.wait()
        for request in reversed(remaining):
            sealed = _seal(request)
            await event_sink.emit(sealed)
            completed[request.frame_type] = SimpleNamespace(frame_type=request.frame_type)
        return ParallelFrameBatchResult(completed=completed, failed={})


class _RetryableFailureOrchestrator:
    def __init__(self) -> None:
        self.calls = 0

    async def execute(self, requests: Any, *, event_sink: Any, **_kwargs: Any) -> Any:
        self.calls += 1
        first, second, third = tuple(requests)
        recorder = llm_module._ACTIVE_PROVIDER_CALL_RECORDER.get()  # noqa: SLF001
        assert recorder is not None
        for request in (first, second, third):
            await recorder.arecord_provider_call(
                ProviderCallIntent(
                    node_name=FRAME_NODE_NAMES[request.frame_type],
                    provider="litellm",
                    model="qwen3.7-max-2026-06-08",
                    traceparent=None,
                )
            )
        await event_sink.emit(_seal(first))
        failures: dict[ParallelFrameType, ParallelFrameFailure] = {}
        for request in (second, third):
            interrupted = FrameInterrupted(
                frame_set_id=request.frame_set_id,
                run_id=request.run_id,
                attempt_id=request.attempt_id,
                frame_type=request.frame_type,
                generation=request.generation,
                frame_id=request.frame_id,
                error_code="OUTPUT_SCHEMA_INVALID",
                retryable=True,
            )
            await event_sink.emit(interrupted)
            failures[request.frame_type] = ParallelFrameFailure(
                frame_type=request.frame_type,
                error_code="OUTPUT_SCHEMA_INVALID",
            )
        return ParallelFrameBatchResult(
            completed={first.frame_type: SimpleNamespace(frame_type=first.frame_type)},
            failed=failures,
        )


def test_generation_two_failure_cannot_authorize_another_lane_retry() -> None:
    interrupted = FrameInterrupted(
        frame_set_id=FRAME_SET_ID,
        run_id=RUN_ID,
        attempt_id=ATTEMPT_ID,
        frame_type="QUALITY_FRAME",
        generation=2,
        frame_id="frame.quality.retry",
        error_code="MODEL_PROVIDER_STREAM_INTERRUPTED",
        retryable=True,
    )
    batch = ParallelFrameBatchResult(
        completed={},
        failed={
            "QUALITY_FRAME": ParallelFrameFailure(
                frame_type="QUALITY_FRAME",
                error_code="MODEL_PROVIDER_STREAM_INTERRUPTED",
            )
        },
    )

    assert not _batch_failure_is_retryable(
        batch,
        (interrupted,),
        active_frame_types=("QUALITY_FRAME",),
    )


@pytest.mark.asyncio
async def test_live_stream_emits_three_starts_then_each_seal_without_waiting_for_siblings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import app.api.intake_parallel_stream_service as service_module

    requests = _requests()
    bundle = SimpleNamespace(
        frame_set_id=FRAME_SET_ID,
        requests=requests,
        agent_contexts={frame_type: object() for frame_type in FRAME_TYPES},
    )
    gateway = _Gateway()
    gate = GraphStreamAdmissionGate()
    await gate.start()
    orchestrator = _SuccessfulOrchestrator(requests)
    completion = object()
    monkeypatch.setattr(service_module, "ParallelFrameStreamProtocolValidator", _FakeValidator)
    monkeypatch.setattr(
        service_module,
        "build_parallel_checkpoint_configs",
        lambda _execution, _requests: {frame_type: {} for frame_type in FRAME_TYPES},
    )
    monkeypatch.setattr(
        service_module,
        "build_parallel_technical_completion",
        lambda *_args, **_kwargs: completion,
    )
    monkeypatch.setattr(
        service_module,
        "_select_execution_requests",
        lambda candidate, _receipt: candidate.requests,
    )
    service = GatewayBackedParallelIntakeFrameStreamService(
        gateway=gateway,
        input_loader=object(),
        prompts=object(),
        orchestrator=orchestrator,
        model_runner=object(),
        provider="litellm",
        model="qwen3.7-max-2026-06-08",
        owner_id="python:test",
        admission_gate=gate,
    )

    async def load_bundle(_execution: Any) -> Any:
        return bundle

    async def load_prepared_bundle(
        _command: Any,
        _expected_thread: Any,
        **_kwargs: Any,
    ) -> Any:
        return bundle

    service._load_bundle = load_bundle  # type: ignore[method-assign]
    service._load_prepared_bundle = load_prepared_bundle  # type: ignore[method-assign]
    opened = await service.open_stream(
        command=gateway.execution.admission.command,
        verified_invocation=_verified(_admission_receipt(gateway.execution.admission.command)),
        expected_thread=object(),
        admission_receipt=_admission_receipt(gateway.execution.admission.command),
    )
    iterator = opened.events

    starts = [await anext(iterator) for _ in FRAME_TYPES]
    assert [event.frame_type for event in starts] == list(FRAME_TYPES)
    assert all(isinstance(event, FrameStarted) for event in starts)
    assert orchestrator.calls == 0
    assert gateway.completed == []

    first_seal_task = asyncio.create_task(anext(iterator))
    await asyncio.wait_for(orchestrator.started.wait(), timeout=1)
    await asyncio.wait_for(orchestrator.first_seal_emitted.wait(), timeout=1)
    first_seal = await asyncio.wait_for(first_seal_task, timeout=1)

    assert isinstance(first_seal, FrameSealed)
    assert first_seal.frame_type == FRAME_TYPES[0]
    assert gateway.completed == []
    orchestrator.continue_remaining.set()
    remaining = [event async for event in iterator]
    assert [first_seal.frame_type, *(event.frame_type for event in remaining)] == [
        FRAME_TYPES[0],
        FRAME_TYPES[2],
        FRAME_TYPES[1],
    ]
    assert gateway.completed == [completion]
    assert gateway.finished == []
    assert gate.accepting is True


@pytest.mark.asyncio
async def test_cached_technical_completion_replays_without_acquire_or_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import app.api.intake_parallel_stream_service as service_module

    authority = _authority()
    cached_events = tuple(
        FrameStarted(
            frame_set_id=FRAME_SET_ID,
            run_id=RUN_ID,
            attempt_id=ATTEMPT_ID,
            frame_type=expected.frame_type,
            generation=1,
            frame_id=expected.frame_id,
            frame_model_input_sha256=expected.frame_model_input_sha256,
            frame_prompt_sha256=expected.frame_prompt_sha256,
            context_envelope_sha256=expected.context_envelope_sha256,
            model_context_view_sha256=expected.model_context_view_sha256,
        )
        for expected in authority.frames
    )
    gateway = _Gateway(cached=True)
    gate = GraphStreamAdmissionGate()
    await gate.start()
    orchestrator = _SuccessfulOrchestrator(_requests())
    monkeypatch.setattr(
        service_module,
        "_decode_cached_completion",
        lambda _completion, **_kwargs: cached_events,
    )
    service = GatewayBackedParallelIntakeFrameStreamService(
        gateway=gateway,
        input_loader=object(),
        prompts=object(),
        orchestrator=orchestrator,
        model_runner=object(),
        provider="litellm",
        model="qwen3.7-max-2026-06-08",
        owner_id="python:test",
        admission_gate=gate,
    )

    async def load_prepared_bundle(
        _command: Any,
        _expected_thread: Any,
        **_kwargs: Any,
    ) -> Any:
        return SimpleNamespace(
            frame_set_id=FRAME_SET_ID,
            requests=_requests(),
            agent_contexts={frame_type: object() for frame_type in FRAME_TYPES},
        )

    service._load_prepared_bundle = load_prepared_bundle  # type: ignore[method-assign]

    opened = await service.open_stream(
        command=gateway.execution.admission.command,
        verified_invocation=_verified(_admission_receipt(gateway.execution.admission.command)),
        expected_thread=object(),
        admission_receipt=_admission_receipt(gateway.execution.admission.command),
    )

    assert [event async for event in opened.events] == list(cached_events)
    assert gateway.load_calls == 1
    assert gateway.acquire_calls == 0
    assert orchestrator.calls == 0
    assert gateway.completed == []


@pytest.mark.asyncio
async def test_retryable_failed_receipt_is_durable_and_same_receipt_replays_without_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import app.api.intake_parallel_stream_service as service_module

    requests = _requests()
    bundle = SimpleNamespace(
        frame_set_id=FRAME_SET_ID,
        requests=requests,
        agent_contexts={frame_type: object() for frame_type in FRAME_TYPES},
    )
    gateway = _Gateway()
    gate = GraphStreamAdmissionGate()
    await gate.start()
    orchestrator = _RetryableFailureOrchestrator()
    monkeypatch.setattr(service_module, "ParallelFrameStreamProtocolValidator", _FakeValidator)
    monkeypatch.setattr(
        service_module,
        "build_parallel_checkpoint_configs",
        lambda _execution, _requests: {frame_type: {} for frame_type in FRAME_TYPES},
    )
    monkeypatch.setattr(
        service_module,
        "_select_execution_requests",
        lambda candidate, _receipt: candidate.requests,
    )
    service = GatewayBackedParallelIntakeFrameStreamService(
        gateway=gateway,
        input_loader=object(),
        prompts=object(),
        orchestrator=orchestrator,
        model_runner=object(),
        provider="litellm",
        model="qwen3.7-max-2026-06-08",
        owner_id="python:test",
        admission_gate=gate,
    )

    async def load_bundle(_execution: Any) -> Any:
        return bundle

    async def load_prepared_bundle(
        _command: Any,
        _expected_thread: Any,
        **_kwargs: Any,
    ) -> Any:
        return bundle

    service._load_bundle = load_bundle  # type: ignore[method-assign]
    service._load_prepared_bundle = load_prepared_bundle  # type: ignore[method-assign]
    receipt = _admission_receipt(gateway.execution.admission.command)
    opened = await service.open_stream(
        command=gateway.execution.admission.command,
        verified_invocation=_verified(receipt),
        expected_thread=object(),
        admission_receipt=receipt,
    )
    first_events: list[Any] = []
    with pytest.raises(GraphContractError, match="INTAKE_PARALLEL_FRAME_BATCH_FAILED"):
        async for event in opened.events:
            first_events.append(event)

    assert orchestrator.calls == 1
    assert len(gateway.completed_cycles) == 1
    gateway.receipt_cycle = gateway.completed_cycles[0]
    replayed = await service.open_stream(
        command=gateway.execution.admission.command,
        verified_invocation=_verified(receipt),
        expected_thread=object(),
        admission_receipt=receipt,
    )

    replay_events: list[Any] = []
    with pytest.raises(GraphContractError, match="INTAKE_PARALLEL_FRAME_BATCH_FAILED"):
        async for event in replayed.events:
            replay_events.append(event)
    assert replay_events == first_events
    assert orchestrator.calls == 1
    assert gateway.acquire_calls == 1


def _execution() -> Any:
    command = SimpleNamespace(
        logical_run_id=RUN_ID,
        attempt_id=ATTEMPT_ID,
        request_hash="f" * 64,
        traceparent=None,
    )
    return SimpleNamespace(
        admission=SimpleNamespace(command=command),
        attempt=SimpleNamespace(attempt_id=ATTEMPT_ID, provider_call_count=0),
        fence=SimpleNamespace(
            thread_id="grt.v1." + "1" * 32,
            command_id="command_test",
            request_hash="f" * 64,
            owner_id="python:test",
            fencing_token=1,
        ),
    )


def _requests() -> tuple[_FakeRequest, _FakeRequest, _FakeRequest]:
    requests = tuple(
        _FakeRequest(
            frame_set_id=FRAME_SET_ID,
            run_id=RUN_ID,
            attempt_id=ATTEMPT_ID,
            frame_type=frame_type,
            generation=1,
            frame_id=f"frame.{frame_type.lower()}",
            context_envelope_sha256=CONTEXT_HASH,
            model_input=_FakeModelInput(str(index + 1) * 64),
        )
        for index, frame_type in enumerate(FRAME_TYPES)
    )
    return requests  # type: ignore[return-value]


def _authority() -> ParallelFrameStreamAuthority:
    return ParallelFrameStreamAuthority(
        frame_set_id=FRAME_SET_ID,
        run_id=RUN_ID,
        attempt_id=ATTEMPT_ID,
        frames=tuple(
            ExpectedParallelFrame(
                frame_type=request.frame_type,
                generation=request.generation,
                frame_id=request.frame_id,
                frame_model_input_sha256=request.model_input.frame_model_input_sha256,
                frame_prompt_sha256=request.model_input.instruction_pack.frame_prompt_sha256,
                context_envelope_sha256=CONTEXT_HASH,
                model_context_view_sha256=MODEL_CONTEXT_HASH,
            )
            for request in _requests()
        ),
    )


def _admission_receipt(command: Any) -> ParallelFrameAdmissionReceipt:
    authority = _authority()
    lanes = tuple(
        ParallelFrameAdmissionLane(
            frame_type=frame.frame_type,
            generation=frame.generation,
            frame_id=frame.frame_id,
            slot_state="ADMITTED",
            action="RUN_CURRENT",
            next_local_index=0,
            slot_version=0,
            result_id=None,
            result_sha256=None,
            public_projection_sha256=None,
            predecessor_failure_code=None,
        )
        for frame in authority.frames
    )
    unsigned = {
        "schema_version": "intake.parallel-admission-receipt.v1",
        "request_hash": command.request_hash,
        "frame_set_id": authority.frame_set_id,
        "run_id": authority.run_id,
        "attempt_id": authority.attempt_id,
        "java_receipt_id": "FRAME_SET_RECEIPT_V4_1",
        "authority_sha256": parallel_frame_authority_sha256(authority),
        "lanes": [
            {
                "frame_type": lane.frame_type,
                "generation": lane.generation,
                "frame_id": lane.frame_id,
                "slot_state": lane.slot_state,
                "action": lane.action,
                "next_local_index": lane.next_local_index,
                "slot_version": lane.slot_version,
                "result_id": lane.result_id,
                "result_sha256": lane.result_sha256,
                "public_projection_sha256": lane.public_projection_sha256,
                "predecessor_failure_code": lane.predecessor_failure_code,
            }
            for lane in lanes
        ],
    }
    return ParallelFrameAdmissionReceipt(
        request_hash=command.request_hash,
        frame_set_id=authority.frame_set_id,
        run_id=authority.run_id,
        attempt_id=authority.attempt_id,
        java_receipt_id="FRAME_SET_RECEIPT_V4_1",
        authority_sha256=parallel_frame_authority_sha256(authority),
        lanes=lanes,  # type: ignore[arg-type]
        receipt_sha256=canonical_sha256(unsigned),
    )


def _verified(receipt: ParallelFrameAdmissionReceipt) -> Any:
    return SimpleNamespace(
        room_fencing_token=17,
        claims=SimpleNamespace(
            parallel_phase="EXECUTE",
            parallel_admission_receipt_sha256=receipt.receipt_sha256,
        )
    )


def _seal(request: _FakeRequest) -> FrameSealed:
    return FrameSealed(
        frame_set_id=request.frame_set_id,
        run_id=request.run_id,
        attempt_id=request.attempt_id,
        frame_type=request.frame_type,
        generation=request.generation,
        frame_id=request.frame_id,
        child_checkpoint_ref=f"checkpoint://{request.frame_type.lower()}",
        child_checkpoint_sha256="c" * 64,
        context_envelope_sha256=CONTEXT_HASH,
        model_context_view_sha256=MODEL_CONTEXT_HASH,
        canonical_result_json="{}",
        result_sha256="d" * 64,
        public_projection_sha256="e" * 64,
        next_local_index=0,
        usage=FrameProviderUsage(
            input_tokens=1,
            output_tokens=1,
            total_tokens=2,
            latency_ms=1,
            provider_call_count=1,
            model="qwen3.7-max-2026-06-08",
        ),
        completed_at=datetime.now(timezone.utc),
    )
