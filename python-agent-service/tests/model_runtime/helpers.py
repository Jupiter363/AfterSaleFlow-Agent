from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterator
from datetime import datetime, timedelta, timezone
import time

from langchain_core.messages import BaseMessage
from pydantic import BaseModel

from app.model_runtime.profiles import (
    ModelInvocationPolicy,
    ModelProfile,
    system_prompt_sha256,
)
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportRequest,
    ModelTransportResult,
    ModelTransportStreamUpdate,
    TransientModelTransportError,
)


class Answer(BaseModel):
    answer: str


def model_profile(
    *,
    attempts: int = 1,
    provider: str = "test-provider",
    model: str = "test-model",
    temperature: float = 0.25,
    max_output_tokens: int = 1_024,
    tool_allowlist: tuple[str, ...] = ("case.lookup",),
) -> ModelProfile:
    return ModelProfile(
        profile_id="profile:test:v1",
        provider=provider,
        model=model,
        temperature=temperature,
        max_output_tokens=max_output_tokens,
        tool_allowlist=tool_allowlist,
        max_provider_attempts=attempts,
        retry_backoff_ms=0,
    )


def invocation_policy(
    system_prompt: str,
    *,
    attempts: int = 1,
    repairs: int = 0,
    deadline_delta: timedelta = timedelta(minutes=1),
) -> ModelInvocationPolicy:
    return ModelInvocationPolicy(
        invocation_id="invocation:test:1",
        node_name="test_node",
        deadline_at=datetime.now(timezone.utc) + deadline_delta,
        provider_attempts_remaining=attempts,
        repairs_remaining=repairs,
        prompt_version="prompt:test:v1",
        output_schema_version="answer:test:v1",
        policy_version="policy:test:v1",
        guardrail_version="guardrail:test:v1",
        trusted_system_sha256=system_prompt_sha256(system_prompt),
        traceparent="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    )


def transport_result(
    answer: str = "accepted",
    *,
    model: str = "test-model",
    latency_ms: int = 7,
    token_usage: dict[str, int] | None = None,
    provider_attempts_used: int = 1,
    repairs_used: int = 0,
) -> ModelTransportResult:
    return ModelTransportResult(
        json_document=Answer(answer=answer).model_dump_json(),
        model=model,
        latency_ms=latency_ms,
        token_usage=token_usage or {"input": 3, "output": 2, "total": 5},
        provider_attempts_used=provider_attempts_used,
        repairs_used=repairs_used,
    )


class RecordingTransport:
    def __init__(self) -> None:
        self.generate_calls = 0
        self.agenerate_calls = 0
        self.stream_calls = 0
        self.astream_calls = 0
        self.requests: list[ModelTransportRequest] = []
        self.generate_failures = 0
        self.stream_attempts: list[list[ModelTransportStreamUpdate] | BaseException] = []
        self.async_delay = 0.0
        self.sync_delay = 0.0
        self.result = transport_result()

    def generate(self, request: ModelTransportRequest) -> ModelTransportResult:
        self.generate_calls += 1
        self.requests.append(request)
        if self.sync_delay:
            time.sleep(self.sync_delay)
        if self.generate_calls <= self.generate_failures:
            raise TransientModelTransportError("transient")
        return self.result

    async def agenerate(self, request: ModelTransportRequest) -> ModelTransportResult:
        self.agenerate_calls += 1
        self.requests.append(request)
        if self.async_delay:
            await asyncio.sleep(self.async_delay)
        return self.result

    def stream(self, request: ModelTransportRequest) -> Iterator[ModelTransportStreamUpdate]:
        self.stream_calls += 1
        self.requests.append(request)
        attempt = self._stream_attempt(self.stream_calls)
        if isinstance(attempt, BaseException):
            raise attempt
        yield from attempt

    async def astream(
        self, request: ModelTransportRequest
    ) -> AsyncIterator[ModelTransportStreamUpdate]:
        self.astream_calls += 1
        self.requests.append(request)
        attempt = self._stream_attempt(self.astream_calls)
        if isinstance(attempt, BaseException):
            raise attempt
        for update in attempt:
            await asyncio.sleep(0)
            yield update

    def _stream_attempt(
        self, attempt_no: int
    ) -> list[ModelTransportStreamUpdate] | BaseException:
        if not self.stream_attempts:
            return [ModelTransportCompleted(result=transport_result())]
        index = min(attempt_no - 1, len(self.stream_attempts) - 1)
        return self.stream_attempts[index]


def message_text(messages: tuple[BaseMessage, ...], index: int) -> str:
    content = messages[index].content
    assert isinstance(content, str)
    return content
