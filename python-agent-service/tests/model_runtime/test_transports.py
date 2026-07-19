from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from langchain_core.messages import HumanMessage, SystemMessage

from app.llm import (
    AgentOutputSchemaError,
    AgentServiceUnavailable,
    GovernedProviderRequest,
    LiteLlmProxyClient,
    ProviderCallIntent,
    StructuredGeneration,
    StructuredStreamCompleted,
    StructuredStreamDelta,
    bind_provider_call_intent_recorder,
)
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportError,
    ModelTransportOutputError,
    ModelTransportRequest,
    ModelTransportVisibleDelta,
    NativeAsyncTransportRequired,
    StructuredClientTransport,
)
from app.streaming import VisibleFieldSpec
from tests.model_runtime.helpers import Answer


class _ProviderCallRecorder:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.intents: list[ProviderCallIntent] = []

    def record_provider_call(self, intent: ProviderCallIntent) -> None:
        self.events.append("intent")
        self.intents.append(intent)

    async def arecord_provider_call(self, intent: ProviderCallIntent) -> None:
        self.events.append("intent")
        self.intents.append(intent)


def _request(
    *,
    visible: bool = False,
    model: str = "legacy-model",
    repairs: int = 0,
    attempts: int = 1,
) -> ModelTransportRequest:
    return ModelTransportRequest(
        node_name="test_node",
        messages=(SystemMessage("system"), HumanMessage("human")),
        output_type=Answer,
        governed_request=GovernedProviderRequest(
            provider="litellm",
            model=model,
            temperature=0.4,
            max_output_tokens=321,
            response_format="STRICT_JSON_SCHEMA",
            tool_allowlist=("case.lookup",),
            deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
            provider_attempts_remaining=attempts,
            repairs_remaining=repairs,
            traceparent="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        ),
        visible_fields=(VisibleFieldSpec("answer", "answer"),) if visible else (),
    )


class LegacyClient:
    def __init__(self) -> None:
        self.calls = 0

    def generate(self, **kwargs):
        self.calls += 1
        return StructuredGeneration(
            value=kwargs["output_type"](answer="legacy"),
            model="legacy-model",
            latency_ms=4,
            token_usage={"input": 1, "output": 1, "total": 2},
        )

    def generate_stream(self, **kwargs):
        self.calls += 1
        yield StructuredStreamDelta(kind="visible_delta", field="answer", delta="legacy")
        yield StructuredStreamCompleted(
            kind="completed",
            generation=StructuredGeneration(
                value=kwargs["output_type"](answer="legacy"),
                model="legacy-model",
                latency_ms=4,
                token_usage={"input": 1, "output": 1, "total": 2},
            ),
        )


def test_legacy_adapter_serializes_one_validated_document_without_second_call() -> None:
    client = LegacyClient()
    transport = StructuredClientTransport(client)

    updates = list(transport.stream(_request(visible=True)))

    assert client.calls == 1
    assert isinstance(updates[0], ModelTransportVisibleDelta)
    assert isinstance(updates[1], ModelTransportCompleted)
    assert json.loads(updates[1].result.json_document) == {"answer": "legacy"}


def test_governed_adapter_rejects_provider_binding_mismatch_before_call() -> None:
    class GovernedClient(LegacyClient):
        supports_governed_provider_request = True
        governed_provider = "bound-provider"
        governed_model = "legacy-model"

    client = GovernedClient()

    with pytest.raises(ModelTransportError):
        StructuredClientTransport(client).generate(_request())

    assert client.calls == 0


@pytest.mark.asyncio
async def test_legacy_adapter_refuses_thread_pool_async_fallback() -> None:
    transport = StructuredClientTransport(LegacyClient())

    with pytest.raises(NativeAsyncTransportRequired):
        await transport.agenerate(_request())


@pytest.mark.asyncio
async def test_litellm_native_async_nonstream_and_stream_use_bounded_http() -> None:
    calls: list[dict] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        assert request.headers["traceparent"] == (
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
        )
        assert body["model"] == "native-model"
        assert body["temperature"] == 0.4
        assert body["max_tokens"] == 321
        if body.get("stream"):
            payload = {
                "model": "native-model",
                "choices": [
                    {
                        "delta": {
                            "content": '{"answer":"native"}',
                            "reasoning_content": "must-not-be-read",
                        },
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 1,
                    "total_tokens": 3,
                },
            }
            content = f"data: {json.dumps(payload)}\n\ndata: [DONE]\n\n".encode()
            return httpx.Response(200, content=content)
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": '{"answer":"native"}'}}],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 1,
                    "total_tokens": 3,
                },
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=mock,
        async_transport=mock,
    )
    transport = StructuredClientTransport(client)

    result = await transport.agenerate(_request(model="native-model"))
    streamed = [
        update
        async for update in transport.astream(
            _request(visible=True, model="native-model")
        )
    ]

    assert json.loads(result.json_document) == {"answer": "native"}
    assert len(calls) == 2
    assert sum(bool(call.get("stream")) for call in calls) == 1
    assert isinstance(streamed[-1], ModelTransportCompleted)
    assert "reasoning" not in streamed[-1].result.json_document


@pytest.mark.asyncio
async def test_provider_intent_is_recorded_before_async_nonstream_and_stream_http() -> None:
    events: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        events.append("http")
        body = json.loads(request.content)
        if body.get("stream"):
            payload = {
                "model": "native-model",
                "choices": [
                    {
                        "delta": {"content": '{"answer":"native"}'},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 1,
                    "total_tokens": 3,
                },
            }
            return httpx.Response(
                200,
                content=f"data: {json.dumps(payload)}\n\ndata: [DONE]\n\n".encode(),
            )
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": '{"answer":"native"}'}}],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 1,
                    "total_tokens": 3,
                },
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=mock,
        async_transport=mock,
    )
    transport = StructuredClientTransport(client)
    recorder = _ProviderCallRecorder(events)

    with bind_provider_call_intent_recorder(recorder):
        await transport.agenerate(_request(model="native-model"))
        assert [update async for update in transport.astream(
            _request(visible=True, model="native-model")
        )]

    assert events == ["intent", "http", "intent", "http"]
    assert [intent.node_name for intent in recorder.intents] == ["test_node", "test_node"]
    assert all(intent.provider == "litellm" for intent in recorder.intents)
    assert all(intent.model == "native-model" for intent in recorder.intents)


def test_each_governed_sync_repair_http_has_its_own_provider_intent() -> None:
    events: list[str] = []
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        events.append("http")
        calls += 1
        if calls == 1:
            return httpx.Response(
                400,
                json={"error": {"message": "response_format is not supported"}},
            )
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": '{"answer":"repaired"}'}}],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 1,
                    "total_tokens": 3,
                },
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=httpx.MockTransport(handler),
    )
    recorder = _ProviderCallRecorder(events)

    with bind_provider_call_intent_recorder(recorder):
        result = client.generate(
            node_name="test_node",
            system_prompt="system",
            user_prompt="human",
            output_type=Answer,
            governed_request=_request(
                model="native-model",
                repairs=1,
                attempts=2,
            ).governed_request,
        )

    assert result.value.answer == "repaired"
    assert events == ["intent", "http", "intent", "http"]
    assert len(recorder.intents) == 2


@pytest.mark.asyncio
async def test_governed_schema_repair_uses_the_same_raw_document_once() -> None:
    calls: list[dict] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": 'prefix {"answer":"repaired"}'}}],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 1,
                    "total_tokens": 3,
                },
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "configured-model-must-not-win",
        "key",
        transport=mock,
        async_transport=mock,
    )

    result = await client.agenerate(
        node_name="test_node",
        system_prompt="system",
        user_prompt="human",
        output_type=Answer,
        governed_request=_request(
            model="native-model",
            repairs=1,
        ).governed_request,
    )

    assert result.value.answer == "repaired"
    assert len(calls) == 1
    assert calls[0]["model"] == "native-model"


@pytest.mark.asyncio
async def test_governed_provider_must_report_complete_usage() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": '{"answer":"accepted"}'}}],
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=mock,
        async_transport=mock,
    )

    with pytest.raises(ModelTransportOutputError, match="contract invalid"):
        await StructuredClientTransport(client).agenerate(
            _request(model="native-model")
        )


@pytest.mark.asyncio
async def test_zero_repair_budget_never_performs_a_repair_or_format_fallback() -> None:
    invalid_calls = 0

    async def invalid_handler(request: httpx.Request) -> httpx.Response:
        nonlocal invalid_calls
        invalid_calls += 1
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": 'prefix {"answer":"blocked"}'}}],
            },
        )

    invalid_mock = httpx.MockTransport(invalid_handler)
    invalid_client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=invalid_mock,
        async_transport=invalid_mock,
    )
    with pytest.raises(AgentOutputSchemaError):
        await invalid_client.agenerate(
            node_name="test_node",
            system_prompt="system",
            user_prompt="human",
            output_type=Answer,
            governed_request=_request(
                model="native-model",
                repairs=0,
                attempts=2,
            ).governed_request,
        )
    assert invalid_calls == 1

    rejection_calls = 0

    async def rejection_handler(request: httpx.Request) -> httpx.Response:
        nonlocal rejection_calls
        rejection_calls += 1
        return httpx.Response(400, text="response_format unsupported")

    rejection_mock = httpx.MockTransport(rejection_handler)
    rejection_client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=rejection_mock,
        async_transport=rejection_mock,
    )
    with pytest.raises(AgentServiceUnavailable):
        await rejection_client.agenerate(
            node_name="test_node",
            system_prompt="system",
            user_prompt="human",
            output_type=Answer,
            governed_request=_request(
                model="native-model",
                repairs=0,
                attempts=2,
            ).governed_request,
        )
    assert rejection_calls == 1


@pytest.mark.asyncio
async def test_failed_format_fallback_reports_both_consumed_provider_attempts() -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(400, text="response_format unsupported")
        return httpx.Response(500, text="provider unavailable")

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=mock,
        async_transport=mock,
    )

    with pytest.raises(AgentServiceUnavailable) as failure:
        await client.agenerate(
            node_name="test_node",
            system_prompt="system",
            user_prompt="human",
            output_type=Answer,
            governed_request=_request(
                model="native-model",
                repairs=1,
                attempts=2,
            ).governed_request,
        )

    assert calls == 2
    assert failure.value.provider_attempts_used == 2


def test_structured_transport_rejects_invalid_provider_metrics() -> None:
    class InvalidMetricsClient(LegacyClient):
        def generate(self, **kwargs):
            generation = super().generate(**kwargs)
            return StructuredGeneration(
                value=generation.value,
                model=generation.model,
                latency_ms=-1,
                token_usage={"input": 1, "output": -1, "total": 0},
            )

    with pytest.raises(ModelTransportOutputError):
        StructuredClientTransport(InvalidMetricsClient()).generate(_request())


@pytest.mark.asyncio
async def test_litellm_async_response_format_fallback_is_explicit_and_once() -> None:
    calls: list[dict] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            return httpx.Response(400, text="response_format unsupported")
        return httpx.Response(
            200,
            json={
                "model": "native-model",
                "choices": [{"message": {"content": 'prefix {"answer":"fallback"}'}}],
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "native-model",
        "key",
        transport=mock,
        async_transport=mock,
    )

    result = await client.agenerate(
        node_name="test_node",
        system_prompt="system",
        user_prompt="human",
        output_type=Answer,
    )

    assert result.value.answer == "fallback"
    assert result.provider_attempts_used == 2
    assert result.repairs_used == 1
    assert len(calls) == 2
    assert "response_format" in calls[0]
    assert "response_format" not in calls[1]
