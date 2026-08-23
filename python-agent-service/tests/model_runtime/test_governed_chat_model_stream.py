from __future__ import annotations

from datetime import timedelta
import time

import pytest
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.outputs import ChatGenerationChunk

from app.model_runtime.callbacks import GOVERNED_EVENTS_KEY, governed_events_from_chunk
from app.model_runtime.governed_chat_model import (
    GovernedChatModel,
    ModelDeadlineExceeded,
    ModelPolicyViolation,
    ModelStreamInterrupted,
)
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportGenerationReset,
    ModelTransportVisibleDelta,
    TransientModelTransportError,
)
from app.streaming import VisibleFieldSpec
from tests.model_runtime.helpers import (
    Answer,
    RecordingTransport,
    invocation_policy,
    model_profile,
    transport_result,
)


SYSTEM_PROMPT = "Trusted stream policy."


class EventHandler(BaseCallbackHandler):
    def __init__(self) -> None:
        self.events: list[dict[str, str]] = []

    def on_llm_new_token(self, token, *, chunk=None, **kwargs) -> None:
        del token, kwargs
        if isinstance(chunk, ChatGenerationChunk):
            self.events.extend(governed_events_from_chunk(chunk.message))


def _model(
    transport: RecordingTransport,
    *,
    attempts: int = 1,
    deadline_delta: timedelta = timedelta(minutes=1),
) -> GovernedChatModel:
    return GovernedChatModel(
        transport=transport,
        output_type=Answer,
        profile=model_profile(attempts=attempts),
        policy=invocation_policy(
            SYSTEM_PROMPT,
            attempts=attempts,
            deadline_delta=deadline_delta,
        ),
        visible_fields=(VisibleFieldSpec("answer", "answer"),),
    )


def _messages():
    return [SystemMessage(SYSTEM_PROMPT), HumanMessage("untrusted stream input")]


def test_stream_separates_public_callback_delta_from_one_parser_document() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[
        ModelTransportVisibleDelta(field="answer", delta="acc"),
        ModelTransportVisibleDelta(field="answer", delta="epted"),
        ModelTransportCompleted(result=transport_result()),
    ]]
    handler = EventHandler()
    chain = _model(transport) | PydanticOutputParser(pydantic_object=Answer)

    outputs = list(chain.stream(_messages(), {"callbacks": [handler]}))

    assert outputs == [Answer(answer="accepted")]
    assert transport.stream_calls == 1
    assert [event["delta"] for event in handler.events] == ["acc", "epted"]
    assert all("reasoning" not in str(event).lower() for event in handler.events)
    visible_chunks = [
        chunk
        for chunk in _model(transport).stream(_messages())
        if chunk.additional_kwargs.get(GOVERNED_EVENTS_KEY)
    ]
    assert all(chunk.content == "" for chunk in visible_chunks)
    assert all(GOVERNED_EVENTS_KEY in chunk.additional_kwargs for chunk in visible_chunks)


def test_stream_retries_only_before_any_visible_delta() -> None:
    before_visible = RecordingTransport()
    before_visible.stream_attempts = [
        TransientModelTransportError("connect"),
        [ModelTransportCompleted(result=transport_result())],
    ]
    assert list(_model(before_visible, attempts=2).stream(_messages()))
    assert before_visible.stream_calls == 2

    after_visible = RecordingTransport()
    after_visible.stream_attempts = [[
        ModelTransportVisibleDelta(field="answer", delta="partial"),
        # The generator helper cannot place a late exception in a list, so a dedicated
        # transport below covers the post-visible failure boundary.
    ]]

    class LateFailureTransport(RecordingTransport):
        def stream(self, request):
            self.stream_calls += 1
            yield ModelTransportVisibleDelta(field="answer", delta="partial")
            raise TransientModelTransportError("lost")

    late = LateFailureTransport()
    with pytest.raises(ModelStreamInterrupted) as failure:
        list(_model(late, attempts=2).stream(_messages()))
    assert failure.value.retryable is True
    assert failure.value.safe_code == "MODEL_PROVIDER_STREAM_INTERRUPTED"
    assert late.stream_calls == 1


def test_stream_relays_one_generation_reset_between_provisional_outputs() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[
        ModelTransportVisibleDelta(field="answer", delta="invalid-first"),
        ModelTransportGenerationReset(
            generation=2,
            reason_code="OUTPUT_SCHEMA_INVALID",
        ),
        ModelTransportVisibleDelta(field="answer", delta="accepted"),
        ModelTransportCompleted(
            result=transport_result(provider_attempts_used=2),
        ),
    ]]

    chunks = list(_model(transport, attempts=2).stream(_messages()))
    events = [
        event
        for chunk in chunks
        for event in governed_events_from_chunk(chunk)
    ]

    assert [event["event_type"] for event in events] == [
        "visible_delta",
        "generation_reset",
        "visible_delta",
    ]
    assert events[1]["generation"] == 2
    assert events[1]["reason_code"] == "OUTPUT_SCHEMA_INVALID"
    assert [chunk.content for chunk in chunks if chunk.content] == [
        Answer(answer="accepted").model_dump_json()
    ]


@pytest.mark.asyncio
async def test_astream_uses_native_async_and_preserves_event_channel() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[
        ModelTransportVisibleDelta(field="answer", delta="accepted"),
        ModelTransportCompleted(result=transport_result()),
    ]]
    chunks = []

    async for chunk in _model(transport).astream(_messages()):
        chunks.append(chunk)

    assert transport.astream_calls == 1
    assert transport.stream_calls == 0
    assert any(governed_events_from_chunk(chunk) for chunk in chunks)
    documents = [chunk.content for chunk in chunks if chunk.content]
    assert documents == [Answer(answer="accepted").model_dump_json()]


@pytest.mark.asyncio
async def test_astream_marks_post_visible_provider_failure_for_outer_attempt() -> None:
    class AsyncLateFailureTransport(RecordingTransport):
        async def astream(self, request):
            self.astream_calls += 1
            self.requests.append(request)
            yield ModelTransportVisibleDelta(field="answer", delta="partial")
            raise TransientModelTransportError("provider connection closed")

    transport = AsyncLateFailureTransport()

    with pytest.raises(ModelStreamInterrupted) as failure:
        async for _ in _model(transport, attempts=2).astream(_messages()):
            pass

    assert failure.value.retryable is True
    assert failure.value.safe_code == "MODEL_PROVIDER_STREAM_INTERRUPTED"
    assert transport.astream_calls == 1
    assert transport.stream_calls == 0


def test_stream_rejects_a_completed_result_from_an_unpinned_model() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[
        ModelTransportCompleted(result=transport_result(model="unexpected-model")),
    ]]

    with pytest.raises(ModelPolicyViolation):
        list(_model(transport).stream(_messages()))


def test_stream_rejects_transport_deltas_outside_the_public_allowlist() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[
        ModelTransportVisibleDelta(field="hidden_reasoning", delta="private"),
        ModelTransportCompleted(result=transport_result()),
    ]]

    with pytest.raises(ModelPolicyViolation):
        list(_model(transport).stream(_messages()))


def test_stream_withholds_completion_until_transport_reaches_clean_eof() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[
        ModelTransportCompleted(result=transport_result()),
        ModelTransportVisibleDelta(field="answer", delta="trailing"),
    ]]

    with pytest.raises(ModelStreamInterrupted):
        list(_model(transport).stream(_messages()))


def test_empty_stream_consumes_the_bounded_provider_attempt_budget() -> None:
    transport = RecordingTransport()
    transport.stream_attempts = [[], []]

    with pytest.raises(ModelStreamInterrupted):
        list(_model(transport, attempts=2).stream(_messages()))

    assert transport.stream_calls == 2


def test_sync_stream_is_bounded_while_waiting_for_the_next_provider_update() -> None:
    class SlowStreamTransport(RecordingTransport):
        def stream(self, request):
            self.stream_calls += 1
            self.requests.append(request)
            time.sleep(0.25)
            yield ModelTransportCompleted(result=transport_result())

    transport = SlowStreamTransport()
    started = time.perf_counter()

    with pytest.raises(ModelDeadlineExceeded):
        list(
            _model(
                transport,
                deadline_delta=timedelta(milliseconds=20),
            ).stream(_messages())
        )

    assert time.perf_counter() - started < 0.15
    assert transport.stream_calls == 1
