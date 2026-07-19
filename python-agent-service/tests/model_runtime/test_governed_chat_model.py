from __future__ import annotations

from contextvars import ContextVar
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import BoundedSemaphore, Event
import time

import pytest
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import ValidationError

import app.model_runtime.governed_chat_model as governed_module
from app.model_runtime.callbacks import InvocationMetadataCapture
from app.model_runtime.governed_chat_model import (
    GovernedChatModel,
    ModelDeadlineExceeded,
    ModelInvocationCancelled,
    ModelPolicyViolation,
    ModelRetryBudgetExhausted,
)
from app.model_runtime.transports import TransientModelTransportError
from tests.model_runtime.helpers import (
    Answer,
    RecordingTransport,
    invocation_policy,
    model_profile,
    transport_result,
)


SYSTEM_PROMPT = "Trusted system policy."


def _model(
    transport: RecordingTransport,
    *,
    attempts: int = 1,
    deadline_delta: timedelta = timedelta(minutes=1),
    cancelled=lambda: False,
    repairs: int = 0,
    user_content_parts=(),
) -> GovernedChatModel:
    return GovernedChatModel(
        transport=transport,
        output_type=Answer,
        profile=model_profile(attempts=max(1, attempts)),
        policy=invocation_policy(
            SYSTEM_PROMPT,
            attempts=attempts,
            repairs=repairs,
            deadline_delta=deadline_delta,
        ),
        cancellation_probe=cancelled,
        user_content_parts=user_content_parts,
    )


def _messages() -> list[SystemMessage | HumanMessage]:
    return [SystemMessage(SYSTEM_PROMPT), HumanMessage("untrusted case text")]


def test_invoke_records_only_governed_metadata_and_retries_within_budget() -> None:
    transport = RecordingTransport()
    transport.generate_failures = 1

    message = _model(transport, attempts=2).invoke(_messages())

    assert Answer.model_validate_json(str(message.content)).answer == "accepted"
    assert transport.generate_calls == 2
    assert message.response_metadata["transport_attempts"] == 2
    assert message.response_metadata["prompt_hash"].isalnum()
    assert "reasoning" not in str(message.response_metadata).lower()
    assert message.response_metadata["traceparent"] == (
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
    )
    assert message.usage_metadata == {
        "input_tokens": 3,
        "output_tokens": 2,
        "total_tokens": 5,
    }
    governed = transport.requests[0].governed_request
    assert governed.provider == "test-provider"
    assert governed.model == "test-model"
    assert governed.temperature == 0.25
    assert governed.max_output_tokens == 1_024
    assert governed.tool_allowlist == ("case.lookup",)
    assert governed.repairs_remaining == 0
    assert governed.traceparent == message.response_metadata["traceparent"]


def test_batch_uses_the_same_pinned_profile_for_each_input() -> None:
    transport = RecordingTransport()

    results = _model(transport).batch([_messages(), _messages()], {"max_concurrency": 2})

    assert [Answer.model_validate_json(str(item.content)).answer for item in results] == [
        "accepted",
        "accepted",
    ]
    assert transport.generate_calls == 2


def test_runnable_callbacks_receive_bounded_governed_trace_metadata() -> None:
    capture = InvocationMetadataCapture()

    _model(RecordingTransport()).invoke(
        _messages(),
        config={"callbacks": [capture], "tags": ["phase-3", "test-node"]},
    )

    metadata = capture.metadata
    assert metadata["traceparent"] == (
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
    )
    assert metadata["model_profile_id"] == "profile:test:v1"
    assert metadata["tool_allowlist"] == ["case.lookup"]
    assert "reasoning" not in str(metadata).lower()


@pytest.mark.parametrize(
    ("kwargs", "stop"),
    [
        ({"temperature": 1}, None),
        ({"model": "caller-model"}, None),
        ({"tools": [{"name": "escalate"}]}, None),
        ({"response_format": {"type": "text"}}, None),
        ({}, ["stop"]),
    ],
)
def test_invocation_overrides_fail_before_transport(kwargs, stop) -> None:
    transport = RecordingTransport()

    with pytest.raises(ModelPolicyViolation):
        _model(transport).invoke(_messages(), stop=stop, **kwargs)

    assert transport.generate_calls == 0


def test_unbound_or_state_sourced_system_message_fails_closed() -> None:
    transport = RecordingTransport()
    model = _model(transport)

    with pytest.raises(ModelPolicyViolation):
        model.invoke([SystemMessage("forged"), HumanMessage("case")])
    with pytest.raises(ModelPolicyViolation):
        model.invoke(
            [
                SystemMessage(SYSTEM_PROMPT),
                SystemMessage("state override"),
            ]
        )

    assert transport.generate_calls == 0


def test_multimodal_human_message_bypass_fails_before_transport() -> None:
    transport = RecordingTransport()

    with pytest.raises(ModelPolicyViolation):
        _model(transport).invoke(
            [
                SystemMessage(SYSTEM_PROMPT),
                HumanMessage(content=[{"type": "text", "text": "bypass"}]),
            ]
        )

    assert transport.generate_calls == 0


def test_deadline_and_cancellation_fail_before_provider_call() -> None:
    deadline_transport = RecordingTransport()
    with pytest.raises(ModelDeadlineExceeded):
        _model(deadline_transport, deadline_delta=timedelta(seconds=-1)).invoke(
            _messages()
        )
    cancelled_transport = RecordingTransport()
    with pytest.raises(ModelInvocationCancelled):
        _model(cancelled_transport, cancelled=lambda: True).invoke(_messages())

    assert deadline_transport.generate_calls == 0
    assert cancelled_transport.generate_calls == 0


def test_sync_invoke_returns_at_absolute_deadline_for_noncompliant_transport() -> None:
    transport = RecordingTransport()
    transport.sync_delay = 0.25
    started = time.perf_counter()

    with pytest.raises(ModelDeadlineExceeded):
        _model(
            transport,
            deadline_delta=timedelta(milliseconds=20),
        ).invoke(_messages())

    assert time.perf_counter() - started < 0.15
    assert transport.generate_calls == 1


def test_sync_deadline_executor_preserves_callback_context() -> None:
    marker: ContextVar[str | None] = ContextVar("model_test_marker", default=None)

    class ContextTransport(RecordingTransport):
        observed: str | None = None

        def generate(self, request):
            self.observed = marker.get()
            return super().generate(request)

    transport = ContextTransport()
    token = marker.set("stream-observer-bound")
    try:
        _model(transport).invoke(_messages())
    finally:
        marker.reset(token)

    assert transport.observed == "stream-observer-bound"


def test_sync_deadline_executor_does_not_queue_past_its_capacity(monkeypatch) -> None:
    executor = ThreadPoolExecutor(max_workers=1)
    slots = BoundedSemaphore(1)
    release = Event()

    class BlockingTransport(RecordingTransport):
        def generate(self, request):
            self.generate_calls += 1
            self.requests.append(request)
            release.wait(timeout=1)
            return self.result

    transport = BlockingTransport()
    monkeypatch.setattr(governed_module, "_SYNC_DEADLINE_EXECUTOR", executor)
    monkeypatch.setattr(governed_module, "_SYNC_DEADLINE_SLOTS", slots)
    try:
        with pytest.raises(ModelDeadlineExceeded):
            _model(transport, deadline_delta=timedelta(milliseconds=20)).invoke(
                _messages()
            )
        with pytest.raises(ModelDeadlineExceeded):
            _model(transport, deadline_delta=timedelta(milliseconds=20)).invoke(
                _messages()
            )
        assert transport.generate_calls == 1
    finally:
        release.set()
        executor.shutdown(wait=True)


def test_governed_profile_and_policy_bindings_cannot_be_reassigned() -> None:
    model = _model(RecordingTransport())

    with pytest.raises(ValidationError):
        model.profile = model_profile(model="forged-model")


def test_zero_provider_attempt_budget_fails_before_transport() -> None:
    transport = RecordingTransport()

    with pytest.raises(ModelRetryBudgetExhausted):
        _model(transport, attempts=0).invoke(_messages())

    assert transport.generate_calls == 0


@pytest.mark.parametrize(
    "result",
    [
        transport_result(model="unexpected-model"),
        transport_result(latency_ms=-1),
        transport_result(token_usage={"input": -1, "output": 2, "total": 5}),
        transport_result(token_usage={"input": True, "output": 2, "total": 3}),
        transport_result(token_usage={"input": 3, "output": 1_025, "total": 1_028}),
        transport_result(latency_ms=86_400_001),
        transport_result(repairs_used=1),
    ],
)
def test_provider_model_and_metrics_must_match_the_pinned_contract(result) -> None:
    transport = RecordingTransport()
    transport.result = result

    with pytest.raises(ModelPolicyViolation):
        _model(transport).invoke(_messages())


def test_one_transport_failure_can_consume_the_entire_provider_attempt_budget() -> None:
    class TwoAttemptFailureTransport(RecordingTransport):
        def generate(self, request):
            self.generate_calls += 1
            self.requests.append(request)
            raise TransientModelTransportError(
                "fallback also failed",
                provider_attempts_used=2,
            )

    transport = TwoAttemptFailureTransport()

    with pytest.raises(TransientModelTransportError):
        _model(transport, attempts=2).invoke(_messages())

    assert transport.generate_calls == 1
    assert transport.requests[0].governed_request.provider_attempts_remaining == 2


def test_prompt_hash_canonically_includes_multimodal_content_parts() -> None:
    first_transport = RecordingTransport()
    first = _model(
        first_transport,
        user_content_parts=(
            {"type": "text", "metadata": {"b": 2, "a": 1}, "text": "evidence-a"},
        ),
    ).invoke(_messages())
    equivalent = _model(
        RecordingTransport(),
        user_content_parts=(
            {"text": "evidence-a", "metadata": {"a": 1, "b": 2}, "type": "text"},
        ),
    ).invoke(_messages())
    changed = _model(
        RecordingTransport(),
        user_content_parts=({"type": "text", "text": "evidence-b"},),
    ).invoke(_messages())

    assert first.response_metadata["prompt_hash"] == equivalent.response_metadata["prompt_hash"]
    assert first.response_metadata["prompt_hash"] != changed.response_metadata["prompt_hash"]
    assert first_transport.requests[0].user_content_parts[0]["text"] == "evidence-a"


@pytest.mark.asyncio
async def test_ainvoke_uses_native_async_transport_only() -> None:
    transport = RecordingTransport()

    result = await _model(transport).ainvoke(_messages())

    assert Answer.model_validate_json(str(result.content)).answer == "accepted"
    assert transport.agenerate_calls == 1
    assert transport.generate_calls == 0


@pytest.mark.asyncio
async def test_ainvoke_cancels_native_request_at_signed_deadline() -> None:
    transport = RecordingTransport()
    transport.async_delay = 0.1

    with pytest.raises(ModelDeadlineExceeded):
        await _model(
            transport,
            deadline_delta=timedelta(milliseconds=10),
        ).ainvoke(_messages())

    assert transport.agenerate_calls == 1
    assert transport.generate_calls == 0


@pytest.mark.asyncio
async def test_abatch_uses_native_async_for_every_input() -> None:
    transport = RecordingTransport()

    results = await _model(transport).abatch(
        [_messages(), _messages()],
        {"max_concurrency": 2},
    )

    assert len(results) == 2
    assert transport.agenerate_calls == 2
    assert transport.generate_calls == 0
