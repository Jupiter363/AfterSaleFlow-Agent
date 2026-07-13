# 文件作用：自动化测试文件，验证 test_streaming 相关模块的行为、契约或页面布局。

from __future__ import annotations

import asyncio
import json
from threading import Event, Lock

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel, ConfigDict

from app.llm import (
    AgentOutputSchemaError,
    LiteLlmProxyClient,
    StructuredStreamCompleted,
    StructuredStreamDelta,
)
from app.streaming import (
    AgentStreamLimitExceeded,
    AgentStreamObserver,
    IncrementalVisibleJsonProjector,
    STREAM_EVENT_MAX_DELTA_CHARS,
    STREAM_EVENT_QUEUE_MAXSIZE,
    STREAM_MAX_MODEL_DOCUMENT_CHARS,
    STREAM_MAX_VISIBLE_OUTPUT_CHARS,
    VisibleFieldSpec,
    current_stream_observer,
    workflow_ndjson_response,
)


class _Reply(BaseModel):
    model_config = ConfigDict(extra="forbid")

    room_utterance: str
    internal_note: str = ""


# 所属模块：Python 支撑模块 > test_streaming；函数角色：模块私有业务函数。
# 具体功能：`_sse` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`join`、`lines.append`、`json.dumps`。
# 上下游：上游为 本文件的 `test_real_provider_stream_projects_answer_and_ignores_reasoning_channel`、`test_real_provider_stream_projects_answer_and_ignores_reasoning_channel.handler`、`test_invalid_streamed_schema_fails_closed_without_second_model_call`、`test_invalid_streamed_schema_fails_closed_without_second_model_call.handler`；下游为 协作调用 `join`、`lines.append`、`json.dumps`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def _sse(*payloads: dict[str, object] | str) -> str:
    lines: list[str] = []
    for payload in payloads:
        if isinstance(payload, str):
            lines.append(f"data: {payload}\n\n")
        else:
            lines.append(
                "data: "
                + json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                + "\n\n"
            )
    return "".join(lines)


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_incremental_projector_only_emits_new_decoded_string_prefix` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`IncrementalVisibleJsonProjector`、`projector.feed`、`VisibleFieldSpec`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `IncrementalVisibleJsonProjector`、`projector.feed`、`VisibleFieldSpec`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_incremental_projector_only_emits_new_decoded_string_prefix() -> None:
    projector = IncrementalVisibleJsonProjector(
        (VisibleFieldSpec("room_utterance", "room_utterance"),)
    )

    assert projector.feed('{"internal_note":"不要展示","room_utterance":"你') == [
        ("room_utterance", "你")
    ]
    assert projector.feed('好\\n第\\u4e8c') == [
        ("room_utterance", "好\n第二")
    ]
    assert projector.feed('行","other":"ignored"}') == [
        ("room_utterance", "行")
    ]


def test_incremental_projector_streams_completed_case_detail_sections() -> None:
    projector = IncrementalVisibleJsonProjector(
        (
            VisibleFieldSpec(
                "case_story",
                "case_detail.case_story",
                "json_value",
            ),
        )
    )

    assert projector.feed('{"case_detail":{"case_story":{"title":"安装') == []
    assert projector.feed('收费","one_sentence_summary":"用户要求退款"}') == [
        (
            "case_detail.case_story",
            '{"title":"安装收费","one_sentence_summary":"用户要求退款"}',
        )
    ]
    assert projector.feed(',"risk_assessment":{}}}') == []


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_real_provider_stream_projects_answer_and_ignores_reasoning_channel` 按协议增量产生或消费Agent 流事件，维持顺序、限额和取消语义；关键协作调用：`LiteLlmProxyClient`、`join`、`next`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_real_provider_stream_projects_answer_and_ignores_reasoning_channel() -> None:
    requests: list[httpx.Request] = []

    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`requests.append`、`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        body = _sse(
            {
                "model": "qwen3.7-plus",
                "choices": [
                    {"delta": {"reasoning_content": "不得出现在公开流中"}}
                ],
            },
            {
                "model": "qwen3.7-plus",
                "choices": [
                    {"delta": {"content": '{"room_utterance":"你'}}
                ],
            },
            {
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "delta": {
                            "content": '好","internal_note":"内部说明"}'
                        }
                    }
                ],
            },
            {
                "model": "qwen3.7-plus",
                "choices": [],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20,
                },
            },
            "[DONE]",
        )
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            text=body,
        )

    client = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        transport=httpx.MockTransport(handler),
    )
    updates = list(
        client.generate_stream(
            node_name="intake_turn_case_detail",
            system_prompt="system",
            user_prompt="user",
            output_type=_Reply,
            visible_fields=(
                VisibleFieldSpec("room_utterance", "room_utterance"),
            ),
        )
    )

    visible = "".join(
        update.delta
        for update in updates
        if isinstance(update, StructuredStreamDelta)
    )
    completed = next(
        update
        for update in updates
        if isinstance(update, StructuredStreamCompleted)
    )
    assert visible == "你好"
    assert "不得出现在公开流中" not in visible
    assert completed.generation.value.room_utterance == "你好"
    assert completed.generation.token_usage == {
        "input": 12,
        "output": 8,
        "total": 20,
    }
    assert len(requests) == 1
    sent = json.loads(requests[0].content)
    assert sent["stream"] is True
    assert sent["stream_options"] == {"include_usage": True}


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_invalid_streamed_schema_fails_closed_without_second_model_call` 按协议增量产生或消费模型状态，维持顺序、限额和取消语义；关键协作调用：`LiteLlmProxyClient`、`httpx.Response`、`pytest.raises`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_invalid_streamed_schema_fails_closed_without_second_model_call() -> None:
    request_count = 0

    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal request_count
        request_count += 1
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            text=_sse(
                {
                    "choices": [
                        {"delta": {"content": '{"room_utterance":12}'}}
                    ]
                },
                "[DONE]",
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentOutputSchemaError):
        list(
            client.generate_stream(
                node_name="intake_turn_case_detail",
                system_prompt="system",
                user_prompt="user",
                output_type=_Reply,
                visible_fields=(
                    VisibleFieldSpec("room_utterance", "room_utterance"),
                ),
            )
        )

    assert request_count == 1


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_ndjson_endpoint_uses_one_versioned_terminal_contract` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`FastAPI`、`app.post`、`workflow_ndjson_response`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `FastAPI`、`app.post`、`workflow_ndjson_response`、`TestClient`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_ndjson_endpoint_uses_one_versioned_terminal_contract() -> None:
    app = FastAPI()

    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`stream` 按协议增量产生或消费Agent 流事件，维持顺序、限额和取消语义；关键协作调用：`app.post`、`workflow_ndjson_response`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `app.post`、`workflow_ndjson_response`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/stream")
    async def stream():
        return workflow_ndjson_response(
            operation="test_operation",
            run_id="AGENT_RUN_test",
            invoke=lambda: {"answer": "完成"},
        )

    with TestClient(app) as client:
        response = client.post("/stream")

    assert response.status_code == 200
    assert response.headers["x-agent-run-id"] == "AGENT_RUN_test"
    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert [event["type"] for event in events] == ["start", "final"]
    assert [event["sequence"] for event in events] == [0, 1]
    assert all(event["schema_version"] == "agent_stream.v1" for event in events)
    assert events[-1]["response"] == {"answer": "完成"}


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_observer_splits_large_deltas_and_rejects_unbounded_visible_output` 按协议增量产生或消费结构化输出，维持顺序、限额和取消语义；关键协作调用：`AgentStreamObserver`、`observer.visible_delta`、`join`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `AgentStreamObserver`、`observer.visible_delta`、`join`、`pytest.raises`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_observer_splits_large_deltas_and_rejects_unbounded_visible_output() -> None:
    published = []
    observer = AgentStreamObserver(
        operation="intake_turn",
        run_id="AGENT_RUN_limits",
        publish=published.append,
    )
    large_delta = "x" * (STREAM_EVENT_MAX_DELTA_CHARS * 2 + 7)

    observer.visible_delta(
        "intake_turn_case_detail",
        "room_utterance",
        large_delta,
    )

    assert "".join(event.delta for event in published) == large_delta
    assert len(published) == 3
    assert all(
        len(event.delta) <= STREAM_EVENT_MAX_DELTA_CHARS for event in published
    )
    with pytest.raises(AgentStreamLimitExceeded):
        observer.visible_delta(
            "intake_turn_case_detail",
            "room_utterance",
            "y" * (STREAM_MAX_VISIBLE_OUTPUT_CHARS - len(large_delta) + 1),
        )


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_projector_rejects_model_document_over_hard_limit` 验证模型状态在固定案例中的输出、边界和失败行为；关键协作调用：`IncrementalVisibleJsonProjector`、`pytest.raises`、`projector.feed`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `IncrementalVisibleJsonProjector`、`pytest.raises`、`projector.feed`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_projector_rejects_model_document_over_hard_limit() -> None:
    projector = IncrementalVisibleJsonProjector(())

    with pytest.raises(AgentStreamLimitExceeded):
        projector.feed("x" * (STREAM_MAX_MODEL_DOCUMENT_CHARS + 1))


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_ndjson_slow_consumer_applies_bounded_backpressure` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`asyncio.run`、`Lock`、`workflow_ndjson_response`；返回/更新字段：`answer`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `scenario`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_ndjson_slow_consumer_applies_bounded_backpressure() -> None:
    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`scenario` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Lock`、`workflow_ndjson_response`、`json.loads`；返回/更新字段：`answer`。
    # 上下游：上游为 本文件的 `test_ndjson_slow_consumer_applies_bounded_backpressure`、`test_ndjson_disconnect_cancels_a_blocked_publisher`；下游为 协作调用 `Lock`、`workflow_ndjson_response`、`json.loads`、`current_stream_observer`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    async def scenario() -> None:
        produced = 0
        produced_lock = Lock()
        delta_count = STREAM_EVENT_QUEUE_MAXSIZE * 2

        # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
        # 具体功能：`invoke` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`current_stream_observer`、`observer.visible_delta`；返回/更新字段：`answer`。
        # 上下游：上游为 相邻模块输入；下游为 协作调用 `current_stream_observer`、`observer.visible_delta`。
        # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
        def invoke() -> dict[str, str]:
            nonlocal produced
            observer = current_stream_observer()
            assert observer is not None
            for _ in range(delta_count):
                observer.visible_delta(
                    "intake_turn_case_detail",
                    "room_utterance",
                    "x",
                )
                with produced_lock:
                    produced += 1
            return {"answer": "done"}

        response = workflow_ndjson_response(
            operation="intake_turn",
            run_id="AGENT_RUN_backpressure",
            invoke=invoke,
        )
        iterator = response.body_iterator
        start = json.loads((await anext(iterator)).strip())
        assert start["type"] == "start"

        for _ in range(500):
            with produced_lock:
                current_produced = produced
            if current_produced == STREAM_EVENT_QUEUE_MAXSIZE:
                break
            await asyncio.sleep(0.01)
        assert current_produced == STREAM_EVENT_QUEUE_MAXSIZE

        # With consumption paused, the producer cannot enqueue beyond the
        # fixed queue capacity regardless of how many deltas it wants to emit.
        await asyncio.sleep(0.1)
        with produced_lock:
            assert produced == STREAM_EVENT_QUEUE_MAXSIZE

        types = [start["type"]]
        async for line in iterator:
            types.append(json.loads(line.strip())["type"])
        assert types.count("visible_delta") == delta_count
        assert types[-1] == "final"

    asyncio.run(scenario())


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_ndjson_disconnect_cancels_a_blocked_publisher` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`asyncio.run`、`Event`、`Lock`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `scenario`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_ndjson_disconnect_cancels_a_blocked_publisher() -> None:
    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`scenario` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Event`、`Lock`、`workflow_ndjson_response`。
    # 上下游：上游为 本文件的 `test_ndjson_slow_consumer_applies_bounded_backpressure`、`test_ndjson_disconnect_cancels_a_blocked_publisher`；下游为 协作调用 `Event`、`Lock`、`workflow_ndjson_response`、`current_stream_observer`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    async def scenario() -> None:
        finished = Event()
        published = 0
        published_lock = Lock()

        # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
        # 具体功能：`invoke` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`current_stream_observer`、`observer.visible_delta`。
        # 上下游：上游为 相邻模块输入；下游为 协作调用 `current_stream_observer`、`observer.visible_delta`。
        # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
        def invoke() -> None:
            nonlocal published
            observer = current_stream_observer()
            assert observer is not None
            try:
                while True:
                    observer.visible_delta(
                        "intake_turn_case_detail",
                        "room_utterance",
                        "x",
                    )
                    with published_lock:
                        published += 1
            finally:
                finished.set()

        response = workflow_ndjson_response(
            operation="intake_turn",
            run_id="AGENT_RUN_cancel",
            invoke=invoke,
        )
        iterator = response.body_iterator
        assert json.loads((await anext(iterator)).strip())["type"] == "start"

        for _ in range(500):
            with published_lock:
                current_published = published
            if current_published == STREAM_EVENT_QUEUE_MAXSIZE:
                break
            await asyncio.sleep(0.01)
        assert current_published == STREAM_EVENT_QUEUE_MAXSIZE

        await iterator.aclose()
        assert await asyncio.to_thread(finished.wait, 1.0)
        with published_lock:
            count_after_disconnect = published
        await asyncio.sleep(0.1)
        with published_lock:
            assert published == count_after_disconnect

    asyncio.run(scenario())
