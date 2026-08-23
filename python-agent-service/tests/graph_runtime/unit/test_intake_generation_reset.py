from __future__ import annotations

from collections.abc import AsyncIterator

import pytest
from langchain_core.messages import AIMessageChunk

from app.graph_runtime.intake_executor import (
    _IntakeLiveGenerationReset,
    _IntakeLiveVisibleDelta,
    _live_target_intake_graph_stream,
)
from app.model_runtime.callbacks import (
    GOVERNED_EVENTS_KEY,
    generation_reset_event,
    publish_governed_generation_reset,
    publish_governed_visible_delta,
    visible_delta_event,
)


@pytest.mark.asyncio
async def test_live_intake_bridge_orders_reset_after_first_generation_delta() -> None:
    async def source() -> AsyncIterator[object]:
        visible = visible_delta_event(
            node_name="intake_turn_case_detail",
            field="room_utterance",
            delta="第一代",
        )
        publish_governed_visible_delta(
            node_name="intake_turn_case_detail",
            field="room_utterance",
            delta="第一代",
        )
        yield (
            "messages",
            (AIMessageChunk(content="", additional_kwargs={GOVERNED_EVENTS_KEY: [visible]}), {}),
        )

        reset = generation_reset_event(
            node_name="intake_turn_case_detail",
            generation=2,
            reason_code="OUTPUT_SCHEMA_INVALID",
        )
        publish_governed_generation_reset(
            node_name="intake_turn_case_detail",
            generation=2,
            reason_code="OUTPUT_SCHEMA_INVALID",
        )
        yield (
            "messages",
            (AIMessageChunk(content="", additional_kwargs={GOVERNED_EVENTS_KEY: [reset]}), {}),
        )

    observed = [item async for item in _live_target_intake_graph_stream(source())]

    assert observed == [
        _IntakeLiveVisibleDelta(
            node_name="intake_turn_case_detail",
            field="room_utterance",
            delta="第一代",
        ),
        _IntakeLiveGenerationReset(
            node_name="intake_turn_case_detail",
            generation=2,
            reason_code="OUTPUT_SCHEMA_INVALID",
        ),
    ]
