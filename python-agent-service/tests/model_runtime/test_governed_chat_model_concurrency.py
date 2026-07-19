from __future__ import annotations

import asyncio

import pytest
from langchain_core.messages import HumanMessage, SystemMessage

from app.model_runtime.governed_chat_model import GovernedChatModel
from tests.model_runtime.helpers import (
    Answer,
    RecordingTransport,
    invocation_policy,
    model_profile,
)


SYSTEM_PROMPT = "Trusted concurrency policy."


class ConcurrentAsyncTransport(RecordingTransport):
    def __init__(self) -> None:
        super().__init__()
        self.active = 0
        self.peak_active = 0

    async def agenerate(self, request):
        self.agenerate_calls += 1
        self.requests.append(request)
        self.active += 1
        self.peak_active = max(self.peak_active, self.active)
        try:
            await asyncio.sleep(0.01)
            return self.result
        finally:
            self.active -= 1


@pytest.mark.asyncio
async def test_100_concurrent_invocations_remain_on_the_native_async_path() -> None:
    transport = ConcurrentAsyncTransport()
    model = GovernedChatModel(
        transport=transport,
        output_type=Answer,
        profile=model_profile(),
        policy=invocation_policy(SYSTEM_PROMPT),
    )
    messages = [
        SystemMessage(SYSTEM_PROMPT),
        HumanMessage("untrusted concurrent input"),
    ]

    results = await asyncio.gather(*(model.ainvoke(messages) for _ in range(100)))

    assert len(results) == 100
    assert transport.agenerate_calls == 100
    assert transport.generate_calls == 0
    assert transport.peak_active > 1
