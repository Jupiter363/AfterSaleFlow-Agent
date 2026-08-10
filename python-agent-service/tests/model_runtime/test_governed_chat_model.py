from __future__ import annotations

from contextvars import ContextVar
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import json
from threading import BoundedSemaphore, Event
import time

import httpx
import pytest
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, ValidationError

import app.model_runtime.governed_chat_model as governed_module
from app.llm import LiteLlmProxyClient
from app.model_runtime.callbacks import InvocationMetadataCapture
from app.model_runtime.governed_chat_model import (
    GovernedChatModel,
    ModelDeadlineExceeded,
    ModelInvocationCancelled,
    ModelPolicyViolation,
    ModelRetryBudgetExhausted,
)
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportVisibleDelta,
    StructuredClientTransport,
    TransientModelTransportError,
)
from app.streaming import AgentStreamObserver, VisibleFieldSpec, bind_stream_observer
from tests.model_runtime.helpers import (
    Answer,
    RecordingTransport,
    invocation_policy,
    model_profile,
    transport_result,
)


SYSTEM_PROMPT = "Trusted system policy."


class IntakeVisibleOutput(BaseModel):
    one_sentence_summary: str


def _model(
    transport: RecordingTransport,
    *,
    attempts: int = 1,
    deadline_delta: timedelta = timedelta(minutes=1),
    cancelled=lambda: False,
    node_name: str = "test_node",
    repairs: int = 0,
    user_content_parts=(),
    visible: bool = False,
) -> GovernedChatModel:
    policy = invocation_policy(
        SYSTEM_PROMPT,
        attempts=attempts,
        repairs=repairs,
        deadline_delta=deadline_delta,
    ).model_copy(update={"node_name": node_name})
    return GovernedChatModel(
        transport=transport,
        output_type=Answer,
        profile=model_profile(attempts=max(1, attempts)),
        policy=policy,
        cancellation_probe=cancelled,
        user_content_parts=user_content_parts,
        visible_fields=(VisibleFieldSpec("answer", "answer"),) if visible else (),
    )


def _observer_backed_intake_model(
    *,
    provider_model: str = "test-model",
    token_usage: dict[str, int] | None = None,
) -> tuple[GovernedChatModel, AgentStreamObserver, list, list[dict]]:
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "model": provider_model,
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"one_sentence_summary": "validated observer output"}
                            )
                        }
                    }
                ],
                "usage": token_usage
                or {
                    "prompt_tokens": 3,
                    "completion_tokens": 2,
                    "total_tokens": 5,
                },
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "test-model",
        "test-master-key",
        transport=mock,
        async_transport=mock,
    )
    policy = invocation_policy(SYSTEM_PROMPT).model_copy(
        update={"node_name": "intake_turn_case_detail"}
    )
    model = GovernedChatModel(
        transport=StructuredClientTransport(client),
        output_type=IntakeVisibleOutput,
        profile=model_profile(provider="litellm", model="test-model"),
        policy=policy,
        visible_fields=(
            VisibleFieldSpec(
                "one_sentence_summary",
                "case_detail.case_story.one_sentence_summary",
            ),
        ),
    )
    published = []
    observer = AgentStreamObserver(
        operation="intake_turn",
        run_id="AGENT_RUN_INTAKE_OBSERVER_POLICY",
        publish=published.append,
    )
    return model, observer, published, calls


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


@pytest.mark.parametrize(
    "invalid_result",
    [
        pytest.param(
            transport_result(model="unexpected-model"),
            id="wrong-pinned-model",
        ),
        pytest.param(
            transport_result(
                token_usage={"input": 3, "output": 1_025, "total": 1_028}
            ),
            id="output-usage-over-profile-budget",
        ),
    ],
)
@pytest.mark.asyncio
async def test_intake_async_stream_keeps_provisional_visible_on_terminal_policy_rejection(
    invalid_result,
) -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [
        [
            ModelTransportVisibleDelta(field="answer", delta="must stay buffered"),
            ModelTransportCompleted(result=invalid_result),
        ]
    ]
    model = _model(
        transport,
        node_name="intake_turn_case_detail",
        visible=True,
    )
    chunks = []

    with pytest.raises(ModelPolicyViolation):
        async for chunk in model._astream(_messages()):
            chunks.append(chunk)

    assert [chunk.generation_info for chunk in chunks] == [
        {"governed_event": "visible_delta"}
    ]
    assert "must stay buffered" in str(chunks)


@pytest.mark.asyncio
async def test_intake_async_stream_emits_visible_then_validated_final() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [
        [
            ModelTransportVisibleDelta(field="answer", delta="validated answer"),
            ModelTransportCompleted(result=transport_result(answer="validated answer")),
        ]
    ]
    model = _model(
        transport,
        node_name="intake_turn_case_detail",
        visible=True,
    )

    chunks = [chunk async for chunk in model._astream(_messages())]

    assert [chunk.generation_info for chunk in chunks] == [
        {"governed_event": "visible_delta"},
        {"governed_event": "completed"},
    ]
    assert Answer.model_validate_json(str(chunks[-1].message.content)).answer == (
        "validated answer"
    )


def test_intake_sync_stream_keeps_provisional_visible_on_terminal_policy_rejection() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [
        [
            ModelTransportVisibleDelta(field="answer", delta="must stay buffered"),
            ModelTransportCompleted(
                result=transport_result(model="unexpected-model")
            ),
        ]
    ]
    model = _model(
        transport,
        node_name="intake_turn_case_detail",
        visible=True,
    )
    chunks = []

    with pytest.raises(ModelPolicyViolation):
        for chunk in model._stream(_messages()):
            chunks.append(chunk)

    assert [chunk.generation_info for chunk in chunks] == [
        {"governed_event": "visible_delta"}
    ]
    assert "must stay buffered" in str(chunks)


@pytest.mark.asyncio
async def test_non_intake_async_stream_retains_live_visible_behavior() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [
        [
            ModelTransportVisibleDelta(field="answer", delta="live non-intake"),
            ModelTransportCompleted(
                result=transport_result(model="unexpected-model")
            ),
        ]
    ]
    model = _model(transport, visible=True)
    chunks = []

    with pytest.raises(ModelPolicyViolation):
        async for chunk in model._astream(_messages()):
            chunks.append(chunk)

    assert [chunk.generation_info for chunk in chunks] == [
        {"governed_event": "visible_delta"}
    ]


@pytest.mark.asyncio
async def test_intake_stream_does_not_retry_after_provisional_visible_output() -> None:
    class RetryTransport(RecordingTransport):
        async def astream(self, request):
            self.astream_calls += 1
            self.requests.append(request)
            yield ModelTransportVisibleDelta(
                field="answer",
                delta="published provisional output",
            )
            raise TransientModelTransportError(
                "retry is forbidden after public release",
                provider_attempts_used=1,
            )

    transport = RetryTransport()
    model = _model(
        transport,
        attempts=2,
        node_name="intake_turn_case_detail",
        visible=True,
    )

    chunks = []
    with pytest.raises(governed_module.ModelStreamInterrupted):
        async for chunk in model._astream(_messages()):
            chunks.append(chunk)

    assert transport.astream_calls == 1
    assert [chunk.generation_info for chunk in chunks] == [
        {"governed_event": "visible_delta"}
    ]
    assert "published provisional output" in str(chunks)


@pytest.mark.asyncio
async def test_intake_stream_retries_before_any_provisional_visible_output() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [
        TransientModelTransportError("connect", provider_attempts_used=1),
        [
            ModelTransportVisibleDelta(field="answer", delta="fresh retry"),
            ModelTransportCompleted(result=transport_result(answer="fresh retry")),
        ],
    ]
    model = _model(
        transport,
        attempts=2,
        node_name="intake_turn_case_detail",
        visible=True,
    )

    chunks = [chunk async for chunk in model._astream(_messages())]

    assert transport.astream_calls == 2
    assert [chunk.generation_info for chunk in chunks] == [
        {"governed_event": "visible_delta"},
        {"governed_event": "completed"},
    ]
    assert "fresh retry" in str(chunks)


@pytest.mark.parametrize(
    ("provider_model", "token_usage"),
    [
        pytest.param("unexpected-model", None, id="wrong-pinned-model"),
        pytest.param(
            "test-model",
            {"prompt_tokens": 3, "completion_tokens": 1_025, "total_tokens": 1_028},
            id="output-usage-over-profile-budget",
        ),
    ],
)
def test_intake_invoke_withholds_observer_events_until_provider_policy_validation(
    provider_model,
    token_usage,
) -> None:
    model, observer, published, calls = _observer_backed_intake_model(
        provider_model=provider_model,
        token_usage=token_usage,
    )

    with bind_stream_observer(observer), pytest.raises(ModelPolicyViolation):
        model.invoke(_messages())

    assert len(calls) == 1
    assert published == []
    assert observer.visible_output_emitted is False


def test_intake_invoke_publishes_validated_observer_events_in_order() -> None:
    model, observer, published, calls = _observer_backed_intake_model()

    with bind_stream_observer(observer):
        result = model.invoke(_messages())

    assert len(calls) == 1
    assert (
        IntakeVisibleOutput.model_validate_json(
            str(result.content)
        ).one_sentence_summary
        == "validated observer output"
    )
    assert [event.type for event in published] == ["visible_delta", "usage"]
    assert observer.visible_output_emitted is True


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
