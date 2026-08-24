from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.graph_stream_service import GraphStreamAdmissionGate
from app.api.intake_parallel_stream import (
    ExpectedParallelFrame,
    ParallelFrameStreamAuthority,
)
from app.api.intake_parallel_stream_service import (
    GatewayBackedParallelIntakeFrameStreamService,
)
from app.graph_runtime.gateway import AdmissionAction
from app.graph_runtime.ledger import AttemptStatus
from app.graph_runtime.recovery import RecoveryAction
from app.graphs.intake.parallel_contracts import FRAME_TYPES, ParallelFrameType
from app.graphs.intake.parallel_graph import (
    FrameProviderUsage,
    FrameSealed,
    FrameStarted,
    ParallelFrameBatchResult,
)


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
    def __init__(self, authority: ParallelFrameStreamAuthority) -> None:
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

    async def renew_execution(self, _execution: Any) -> Any:
        return SimpleNamespace()

    async def record_provider_call(self, execution: Any) -> Any:
        return execution

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

    async def execute(self, _requests: Any, *, event_sink: Any, **_kwargs: Any) -> Any:
        self.calls += 1
        self.started.set()
        completed: dict[ParallelFrameType, Any] = {}
        first, *remaining = self.requests
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

    service._load_bundle = load_bundle  # type: ignore[method-assign]
    opened = await service.open_stream(
        command=gateway.execution.admission.command,
        verified_invocation=object(),
        expected_thread=object(),
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
        lambda _completion: (authority, cached_events),
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

    opened = await service.open_stream(
        command=gateway.execution.admission.command,
        verified_invocation=object(),
        expected_thread=object(),
    )

    assert [event async for event in opened.events] == list(cached_events)
    assert gateway.load_calls == 1
    assert gateway.acquire_calls == 0
    assert orchestrator.calls == 0
    assert gateway.completed == []


def _execution() -> Any:
    command = SimpleNamespace(
        logical_run_id=RUN_ID,
        attempt_id=ATTEMPT_ID,
    )
    return SimpleNamespace(
        admission=SimpleNamespace(command=command),
        attempt=SimpleNamespace(attempt_id=ATTEMPT_ID),
        fence=SimpleNamespace(),
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
