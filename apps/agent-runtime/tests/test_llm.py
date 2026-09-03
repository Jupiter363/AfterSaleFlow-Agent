# 文件作用：自动化测试文件，验证 test_llm 相关模块的行为、契约或页面布局。

import asyncio
import json
import threading
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from pydantic import BaseModel

import app.llm as llm_module
from app.llm import (
    AgentOutputSchemaError,
    AgentServiceUnavailable,
    GovernedProviderRequest,
    LiteLlmProxyClient,
)
from app.schemas.final_agents import EvidenceTurnLlmOutput
from app.streaming import AgentStreamObserver, VisibleFieldSpec, bind_stream_observer


class SimpleStructuredOutput(BaseModel):
    requires_supplemental_evidence: bool
    gaps: list[dict[str, object]]


class RegeneratedStreamOutput(BaseModel):
    room_utterance: str
    required_value: int


class _ChunkedByteStream(httpx.SyncByteStream):
    # 所属模块：Python 支撑模块 > test_llm；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self, *chunks: bytes) -> None:
        self._chunks = chunks

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`__iter__` 按协议增量产生或消费本阶段状态，维持顺序、限额和取消语义。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    def __iter__(self):
        yield from self._chunks


class _RejectSecondChunkBeforeFirstYield(httpx.SyncByteStream):
    """验证 SSE 首行不会为了固定传输块大小而等待后续数据。"""

    def __iter__(self):
        yield b'data: {"choices":[]}\n'
        raise AssertionError("the parser consumed a later chunk before yielding the first line")


def _structured_stream_body(
    content: str,
    *,
    finish_reason: str | None,
    include_done: bool = True,
    include_usage: bool = False,
) -> bytes:
    choice: dict[str, object] = {"delta": {"content": content}}
    if finish_reason is not None:
        choice["finish_reason"] = finish_reason
    events = [
        "data: "
        + json.dumps(
            {"model": "qwen3.7-plus", "choices": [choice]},
            ensure_ascii=False,
        )
        + "\n\n"
    ]
    if include_usage:
        events.append(
            "data: "
            + json.dumps(
                {
                    "model": "qwen3.7-plus",
                    "choices": [],
                    "usage": {
                        "prompt_tokens": 5,
                        "completion_tokens": 3,
                        "total_tokens": 8,
                    },
                }
            )
            + "\n\n"
        )
    if include_done:
        events.append("data: [DONE]\n\n")
    return "".join(events).encode()


def _governed_stream_request() -> GovernedProviderRequest:
    return GovernedProviderRequest(
        provider="litellm",
        model="qwen3.7-plus",
        temperature=0,
        max_output_tokens=6_144,
        response_format="STRICT_JSON_SCHEMA",
        tool_allowlist=(),
        deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
        provider_attempts_remaining=2,
        repairs_remaining=0,
    )


def test_stream_line_parser_yields_the_first_complete_upstream_line_immediately() -> None:
    response = httpx.Response(200, stream=_RejectSecondChunkBeforeFirstYield())
    lines = llm_module._iter_limited_lines(response, max_line_bytes=128 * 1024)

    assert next(lines) == b'data: {"choices":[]}'
    response.close()


# 所属模块：LLM 网关测试 > 接待节点单次调用预算与严格 Schema。
# 具体功能：验证 intake_turn_case_detail 恢复 `_1` 的受控输出预算、关闭 Thinking，并把本地测试 Schema 以 strict json_schema response_format 发给供应商。
# 上下游：上游直接调用 LiteLlmProxyClient 请求体构造器；下游断言请求预算与运行时治理配置一致，且 Schema 名称与正文正确。
# 系统意义：避免供应商把推理 Token 计入 completion_tokens 后，完整且合法的接待结果被后置治理误拒绝。
def test_intake_generation_uses_stable_strict_schema_request() -> None:
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-max-2026-06-08",
        "test-master-key",
    )

    body = client._completion_request_body(
        node_name="intake_turn_case_detail",
        output_type=SimpleStructuredOutput,
        system_prompt="system",
        user_prompt="user",
        user_content_parts=None,
        json_mode=True,
    )

    assert body["model"] == "qwen3.7-max-2026-06-08"
    assert body["max_tokens"] == 6_144
    assert body["enable_thinking"] is False
    assert "thinking_budget" not in body
    assert body["response_format"]["type"] == "json_schema"
    assert body["response_format"]["json_schema"]["strict"] is True
    assert body["response_format"]["json_schema"]["name"] == (
        "intake_turn_case_detail"
    )


@pytest.mark.parametrize(
    "node_name",
    [*sorted(llm_module._NODE_GENERATION_BUDGETS), "unregistered_business_node"],
)
def test_all_business_generation_requests_disable_thinking(node_name: str) -> None:
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
    )

    body = client._completion_request_body(
        node_name=node_name,
        output_type=SimpleStructuredOutput,
        system_prompt="system",
        user_prompt="user",
        user_content_parts=None,
        json_mode=True,
    )

    assert body["enable_thinking"] is False
    assert "thinking_budget" not in body


@pytest.mark.parametrize(
    ("node_name", "expected_max_tokens"),
    [
        ("intake_turn_dialogue_frame", 1_024),
        ("intake_turn_dossier_frame", 8_192),
        ("intake_turn_quality_frame", 2_048),
    ],
)
def test_parallel_intake_frames_use_small_independent_generation_budgets(
    node_name: str,
    expected_max_tokens: int,
) -> None:
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-max-2026-06-08",
        "test-master-key",
    )

    body = client._completion_request_body(
        node_name=node_name,
        output_type=SimpleStructuredOutput,
        system_prompt="system",
        user_prompt="user",
        user_content_parts=None,
        json_mode=True,
    )

    assert body["max_tokens"] == expected_max_tokens
    assert body["enable_thinking"] is False
    if node_name in {
        "intake_turn_dialogue_frame",
        "intake_turn_dossier_frame",
    }:
        assert body["stop"] == ["\n```"]
    else:
        assert "stop" not in body


def test_business_generation_uses_enabled_thinking_configuration() -> None:
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-max",
        "test-master-key",
        enable_thinking=True,
    )

    body = client._completion_request_body(
        node_name="intake_turn_case_detail",
        output_type=SimpleStructuredOutput,
        system_prompt="system",
        user_prompt="user",
        user_content_parts=None,
        json_mode=True,
    )

    assert body["enable_thinking"] is True
    assert "thinking_budget" not in body


def test_plain_json_mode_omits_provider_schema_and_keeps_local_validation() -> None:
    calls: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        assert "response_format" not in body
        assert body["max_tokens"] == 8_192
        return httpx.Response(
            200,
            json={
                "model": "qwen3.8-max",
                "choices": [
                    {
                        "message": {
                            "content": "结果如下：\n```json\n"
                            '{"requires_supplemental_evidence":false,"gaps":[]}'
                            "\n```"
                        }
                    }
                ],
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.8-max",
        "test-master-key",
        transport=httpx.MockTransport(handler),
        strict_json_schema_enabled=False,
    )

    result = client.generate(
        node_name="evidence_turn",
        system_prompt="只输出 JSON",
        user_prompt="测试",
        output_type=SimpleStructuredOutput,
    )

    assert len(calls) == 1
    assert result.value.requires_supplemental_evidence is False
    assert result.value.gaps == []


def test_plain_json_stream_omits_provider_schema() -> None:
    calls: list[dict[str, object]] = []
    content = json.dumps(
        {"requires_supplemental_evidence": False, "gaps": []},
        ensure_ascii=False,
    )

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        assert "response_format" not in body
        assert body["max_tokens"] == 8_192
        return httpx.Response(
            200,
            content=_structured_stream_body(content, finish_reason="stop"),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.8-max",
        "test-master-key",
        transport=httpx.MockTransport(handler),
        strict_json_schema_enabled=False,
    )

    updates = list(
        client.generate_stream(
            node_name="evidence_turn",
            system_prompt="只输出 JSON",
            user_prompt="测试",
            output_type=SimpleStructuredOutput,
        )
    )

    assert len(calls) == 1
    assert updates[-1].kind == "completed"
    assert updates[-1].generation.value.gaps == []


def test_model_health_probe_disables_thinking_and_reuses_recent_success() -> None:
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        assert body["enable_thinking"] is False
        assert body["max_tokens"] == 3
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [{"message": {"content": "ok"}}],
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    first = client.check_available()
    second = client.check_available()

    assert first["model"] == "qwen3.7-plus"
    assert second == first
    assert len(calls) == 1


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_contract_and_structured_response_validation` 验证结构化模型调用在固定案例中的输出、边界和失败行为；关键协作调用：`LiteLlmProxyClient`、`client.generate`、`json.loads`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `LiteLlmProxyClient`、`client.generate`、`json.loads`、`httpx.Response`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_contract_and_structured_response_validation() -> None:
    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`json.loads`、`httpx.Response`、`json.dumps`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`httpx.Response`、`json.dumps`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "http://litellm:4000/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-master-key"
        body = json.loads(request.content)
        assert body["model"] == "qwen3.7-plus"
        assert "max_tokens" not in body
        assert body["enable_thinking"] is False
        assert "thinking_budget" not in body
        assert body["response_format"]["type"] == "json_schema"
        assert body["response_format"]["json_schema"]["strict"] is True
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "requires_supplemental_evidence": False,
                                    "gaps": [],
                                }
                            )
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 4,
                    "total_tokens": 14,
                },
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    result = client.generate(
        node_name="evidence_turn",
        system_prompt="system",
        user_prompt="user",
        output_type=SimpleStructuredOutput,
    )

    assert result.value.requires_supplemental_evidence is False
    assert result.token_usage["total"] == 14


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_sends_inline_multimodal_evidence_parts` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`LiteLlmProxyClient`、`client.generate`、`json.loads`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `LiteLlmProxyClient`、`client.generate`、`json.loads`、`httpx.Response`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_sends_inline_multimodal_evidence_parts() -> None:
    data_url = (
        "data:image/png;base64,"
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8A"
        "AQUBAScY42YAAAAASUVORK5CYII="
    )

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`json.loads`、`httpx.Response`、`json.dumps`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`httpx.Response`、`json.dumps`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        user_content = body["messages"][1]["content"]
        assert user_content[0] == {"type": "text", "text": "inspect evidence"}
        assert user_content[1] == {
            "type": "text",
            "text": "Evidence EVIDENCE_image follows.",
        }
        assert user_content[2] == {
            "type": "image_url",
            "image_url": {"url": data_url, "detail": "high"},
        }
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "requires_supplemental_evidence": False,
                                    "gaps": [],
                                }
                            )
                        }
                    }
                ],
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )
    result = client.generate(
        node_name="evidence_multimodal_probe",
        system_prompt="system",
        user_prompt="inspect evidence",
        output_type=SimpleStructuredOutput,
        user_content_parts=[
            {"type": "text", "text": "Evidence EVIDENCE_image follows."},
            {
                "type": "image_url",
                "image_url": {"url": data_url, "detail": "high"},
            },
        ],
    )

    assert result.model == "qwen3.7-plus"


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_rejects_untrusted_multimodal_parts` 验证结构化模型调用在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.mark.parametrize`、`pytest.raises`、`LiteLlmProxyClient._validated_multimodal_parts`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `pytest.mark.parametrize`、`pytest.raises`、`LiteLlmProxyClient._validated_multimodal_parts`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
@pytest.mark.parametrize(
    "data_url",
    [
        "https://example.invalid/evidence.png",
        "data:image/gif;base64,R0lGODlhAQABAIAAAAUEBA==",
        "data:image/png,not-base64",
        "data:image/png;base64,not_base64!",
        "data:image/png;base64,/9j/",
    ],
)
def test_litellm_proxy_rejects_untrusted_multimodal_parts(data_url: str) -> None:
    with pytest.raises(ValueError):
        LiteLlmProxyClient._validated_multimodal_parts(
            [{"type": "image_url", "image_url": {"url": data_url}}]
        )


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_repairs_empty_structured_content_with_plain_json_retry` 验证结构化模型调用在固定案例中的输出、边界和失败行为；关键协作调用：`LiteLlmProxyClient`、`client.generate`、`json.loads`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `LiteLlmProxyClient`、`client.generate`、`json.loads`、`calls.append`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_repairs_empty_structured_content_with_plain_json_retry() -> None:
    calls: list[dict] = []

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`json.loads`、`calls.append`、`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`calls.append`、`httpx.Response`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            assert body["response_format"]["type"] == "json_schema"
            assert "max_tokens" not in body
            return httpx.Response(
                200,
                json={
                    "model": "qwen3.7-plus",
                    "choices": [{"message": {"content": ""}}],
                },
            )
        assert "response_format" not in body
        assert body["max_tokens"] == 8_192
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": (
                                "下面是 JSON："
                                '{"requires_supplemental_evidence": false, "gaps": []}'
                            )
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20,
                },
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    result = client.generate(
        node_name="evidence_turn",
        system_prompt="system",
        user_prompt="user",
        output_type=SimpleStructuredOutput,
    )

    assert len(calls) == 2
    assert result.value.requires_supplemental_evidence is False
    assert result.token_usage["total"] == 20


@pytest.mark.asyncio
async def test_governed_async_schema_invalid_strict_response_retries_plain_once() -> None:
    calls: list[dict] = []
    invalid_sentinel = "INVALID_FIRST_STRUCTURED_RESPONSE"
    repair_begin = "<<<SERVER_OWNED_JSON_SCHEMA_REPAIR_V1>>>"
    schema_marker = "SCHEMA_JSON_COMPACT:"
    repair_end = "<<<END_SERVER_OWNED_JSON_SCHEMA_REPAIR_V1>>>"

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            assert body["response_format"]["type"] == "json_schema"
            return httpx.Response(
                200,
                json={
                    "model": "governed-model",
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "gaps": [],
                                        "invalid_marker": invalid_sentinel,
                                    }
                                )
                            }
                        }
                    ],
                    "usage": {
                        "prompt_tokens": 7,
                        "completion_tokens": 3,
                        "total_tokens": 10,
                    },
                },
            )
        assert "response_format" not in body
        return httpx.Response(
            200,
            json={
                "model": "governed-model",
                "choices": [
                    {
                        "message": {
                            "content": (
                                "validated: "
                                + json.dumps(
                                    {
                                        "requires_supplemental_evidence": False,
                                        "gaps": [],
                                    }
                                )
                            )
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 5,
                    "total_tokens": 16,
                },
            },
        )

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "configured-model-must-not-win",
        "test-master-key",
        transport=mock,
        async_transport=mock,
    )
    governed_request = GovernedProviderRequest(
        provider="litellm",
        model="governed-model",
        temperature=0.2,
        max_output_tokens=1_024,
        response_format="STRICT_JSON_SCHEMA",
        tool_allowlist=(),
        deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
        provider_attempts_remaining=2,
        repairs_remaining=1,
        traceparent="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    )

    published = []
    observer = AgentStreamObserver(
        operation="test_governed_schema_retry",
        run_id="AGENT_RUN_GOVERNED_SCHEMA_RETRY",
        publish=published.append,
    )
    with bind_stream_observer(observer):
        result = await client.agenerate(
            node_name="test_governed_schema_retry",
            system_prompt="system",
            user_prompt="user",
            output_type=SimpleStructuredOutput,
            governed_request=governed_request,
        )

    assert len(calls) == 2
    assert "response_format" not in calls[1]
    assert len(calls[1]["messages"]) == 2
    assert calls[1]["messages"][1] == calls[0]["messages"][1]
    first_system = calls[0]["messages"][0]
    repaired_system = calls[1]["messages"][0]
    assert repaired_system["role"] == first_system["role"] == "system"
    assert repaired_system["content"].startswith(
        first_system["content"] + "\n\n" + repair_begin + "\n"
    )
    assert repaired_system["content"].count(repair_begin) == 1
    assert repaired_system["content"].count(schema_marker) == 1
    assert repaired_system["content"].count(repair_end) == 1
    schema_section = repaired_system["content"].split(schema_marker, 1)[1].split(
        repair_end,
        1,
    )[0]
    assert schema_section.endswith("\n")
    schema_text = schema_section.removesuffix("\n")
    assert schema_text == json.dumps(
        SimpleStructuredOutput.model_json_schema(),
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    )
    assert json.loads(schema_text) == SimpleStructuredOutput.model_json_schema()
    assert invalid_sentinel not in json.dumps(calls[1], ensure_ascii=False)
    assert result.provider_attempts_used == 2
    assert result.repairs_used == 1
    assert result.model == "governed-model"
    assert result.token_usage == {"input": 11, "output": 5, "total": 16}
    assert result.value.model_dump(mode="json") == {
        "requires_supplemental_evidence": False,
        "gaps": [],
    }
    assert invalid_sentinel not in result.value.model_dump_json()
    assert all(
        invalid_sentinel not in str(getattr(event, "delta", ""))
        for event in published
    )


def test_hearing_judge_v2_buffers_until_valid_and_repairs_a_truncated_response() -> None:
    class JudgeV2Output(BaseModel):
        public_message: str

    calls: list[dict] = []
    valid_output = {"public_message": "完整终稿通过结构校验后再公开。"}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            return httpx.Response(
                200,
                json={
                    "model": "qwen3.7-plus",
                    "choices": [
                        {
                            "message": {
                                "content": '{"public_message":"截断内容'
                            }
                        }
                    ],
                },
            )
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": "validated: "
                            + json.dumps(valid_output, ensure_ascii=False)
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20,
                },
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )
    published = []
    observer = AgentStreamObserver(
        operation="hearing_judge_v2",
        run_id="AGENT_RUN_BUFFERED_DRAFT",
        publish=published.append,
    )

    with bind_stream_observer(observer):
        result = client.generate(
            node_name="hearing_judge_v2",
            system_prompt="system",
            user_prompt="user",
            output_type=JudgeV2Output,
        )

    assert len(calls) == 2
    assert all("stream" not in body for body in calls)
    assert "response_format" in calls[0]
    assert "response_format" not in calls[1]
    assert result.value.model_dump(mode="json") == valid_output
    assert [event.type for event in published] == [
        "visible_delta",
        "usage",
    ]
    assert "".join(
        event.delta for event in published if event.type == "visible_delta"
    ) == "完整终稿通过结构校验后再公开。"
    assert all(
        "截断内容" not in event.delta
        for event in published
        if event.type == "visible_delta"
    )


def test_evidence_turn_buffers_until_valid_and_repairs_a_truncated_response() -> None:
    calls: list[dict] = []
    valid_output = {
        "room_utterance": "已核验本批证据，并把促销条件关联到对应事实。",
        "evidence_requests": [
            {
                "question_id": "QUESTION_PROMOTION_RULE",
                "target_evidence_id": "EVIDENCE_PROMOTION_PAGE",
                "question": "请补充促销页面形成时间及支付条件的原始记录。",
                "reason": "用于核对页面条件与直播口头承诺是否一致。",
            }
        ],
        "verification_suggestions": [
            {
                "evidence_id": "EVIDENCE_PROMOTION_PAGE",
                "suggestion": "比对平台后台页面版本和订单支付方式。",
                "confidence_score": 0.9,
            }
        ],
        "evidence_assessments": [
            {
                "evidence_id": "EVIDENCE_PROMOTION_PAGE",
                "analysis_method": "TEXT_ONLY",
                "inspected_modalities": ["PARSED_TEXT"],
                "fact_links": [
                    {
                        "fact_id": "FACT_PROMOTION_CONDITION",
                        "relation": "SUPPORTS",
                        "reason": "页面文本载明指定支付方式。",
                        "confidence": 0.9,
                    }
                ],
                "authenticity_score": 0.8,
                "relevance_score": 0.95,
                "completeness_score": 0.7,
                "assessment_confidence": 0.85,
                "source_basis": ["页面解析文本与订单记录"],
                "supported_fact_ids": ["FACT_PROMOTION_CONDITION"],
                "formation_time_assessment": "形成时间仍需平台版本记录复核。",
                "limitations": ["当前仅有提交方提供的页面副本。"],
                "recommendation": "PLAUSIBLE",
                "asset_audit": {"parsed_text_loaded": True},
                "summary": "证据与指定支付条件相关，但形成时间仍待复核。",
            }
        ],
        "internal_handoff": {
            "evidence_change_summary": "新增一份促销规则页面评估。",
            "matrix_change_summary": "关联 FACT_PROMOTION_CONDITION。",
            "remaining_conflicts": ["直播口头承诺是否披露支付限制。"],
            "uncovered_fact_ids": ["FACT_LIVE_PROMISE"],
            "judge_attention_points": ["页面规则与口头承诺的披露差异。"],
        },
        "confidence": 0.85,
    }

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            truncated = json.dumps(valid_output, ensure_ascii=False)[:-80]
            return httpx.Response(
                200,
                json={
                    "model": "qwen3.7-plus",
                    "choices": [{"message": {"content": truncated}}],
                },
            )
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": "validated: "
                            + json.dumps(valid_output, ensure_ascii=False)
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 80,
                    "total_tokens": 200,
                },
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )
    published = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_BUFFERED_EVIDENCE",
        publish=published.append,
    )

    with bind_stream_observer(observer):
        result = client.generate(
            node_name="evidence_turn",
            system_prompt="system",
            user_prompt="user",
            output_type=EvidenceTurnLlmOutput,
        )

    assert len(calls) == 2
    assert all("stream" not in body for body in calls)
    assert "response_format" in calls[0]
    assert "response_format" not in calls[1]
    assert result.value.model_dump(mode="json", exclude_unset=True) == valid_output
    assert [event.type for event in published] == ["usage"]
    assert not any(event.type == "visible_delta" for event in published)
    assert result.token_usage["total"] == 200


def test_generate_stream_rejects_premature_eof_before_done() -> None:
    valid_content = json.dumps(
        {"requires_supplemental_evidence": False, "gaps": []}
    )

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            stream=_ChunkedByteStream(
                _structured_stream_body(
                    valid_content,
                    finish_reason="stop",
                    include_done=False,
                )
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentServiceUnavailable, match=r"before \[DONE\]"):
        list(
            client.generate_stream(
                node_name="intake_turn_case_detail",
                system_prompt="system",
                user_prompt="user",
                output_type=SimpleStructuredOutput,
            )
        )


def test_generate_stream_requires_provider_finish_reason() -> None:
    valid_content = json.dumps(
        {"requires_supplemental_evidence": False, "gaps": []}
    )

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            stream=_ChunkedByteStream(
                _structured_stream_body(valid_content, finish_reason=None)
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentServiceUnavailable, match="without finish_reason"):
        list(
            client.generate_stream(
                node_name="intake_turn_case_detail",
                system_prompt="system",
                user_prompt="user",
                output_type=SimpleStructuredOutput,
            )
        )


@pytest.mark.parametrize(
    ("finish_reason", "message"),
    [
        ("content_filter", "content filter"),
        ("tool_calls", "provider finish_reason 'tool_calls'"),
    ],
)
def test_generate_stream_classifies_non_successful_provider_termination(
    finish_reason: str,
    message: str,
) -> None:
    invalid_content = '{"requires_supplemental_evidence":false'

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            stream=_ChunkedByteStream(
                _structured_stream_body(
                    invalid_content,
                    finish_reason=finish_reason,
                )
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentServiceUnavailable, match=message):
        list(
            client.generate_stream(
                node_name="intake_turn_case_detail",
                system_prompt="system",
                user_prompt="user",
                output_type=SimpleStructuredOutput,
            )
        )


def test_generate_stream_classifies_output_token_limit_as_non_retryable_output_error(
) -> None:
    invalid_content = '{"requires_supplemental_evidence":false'

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            stream=_ChunkedByteStream(
                _structured_stream_body(
                    invalid_content,
                    finish_reason="length",
                )
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentOutputSchemaError) as captured:
        list(
            client.generate_stream(
                node_name="intake_turn_dossier_frame",
                system_prompt="system",
                user_prompt="user",
                output_type=SimpleStructuredOutput,
            )
        )

    assert captured.value.safe_code == "AGENT_OUTPUT_TOKEN_LIMIT_EXCEEDED"


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_retries_without_json_mode_when_provider_rejects_response_format` 把结构化模型调用转换为稳定的接口、提示词或页面表达；关键协作调用：`LiteLlmProxyClient`、`client.generate`、`json.loads`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `LiteLlmProxyClient`、`client.generate`、`json.loads`、`calls.append`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_retries_without_json_mode_when_provider_rejects_response_format() -> None:
    calls: list[dict] = []

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`json.loads`、`calls.append`、`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`calls.append`、`httpx.Response`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            assert body["response_format"]["type"] == "json_schema"
            return httpx.Response(
                400,
                json={"error": {"message": "response_format is not supported"}},
            )
        assert "response_format" not in body
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": (
                                "已改用普通文本输出："
                                '{"requires_supplemental_evidence": false, "gaps": []}'
                            )
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 7,
                    "total_tokens": 18,
                },
            },
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    result = client.generate(
        node_name="evidence_turn",
        system_prompt="system",
        user_prompt="user",
        output_type=SimpleStructuredOutput,
    )

    assert len(calls) == 2
    assert result.value.requires_supplemental_evidence is False
    assert result.token_usage["total"] == 18


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_dashscope_v1_base_url_does_not_duplicate_version_segment` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`LiteLlmProxyClient`、`client.generate`、`json.loads`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `LiteLlmProxyClient`、`client.generate`、`json.loads`、`httpx.Response`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_dashscope_v1_base_url_does_not_duplicate_version_segment() -> None:
    endpoint = (
        "https://ws-veazvl2fycrurdmv.cn-beijing.maas.aliyuncs.com/"
        "compatible-mode/v1/chat/completions"
    )

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`json.loads`、`httpx.Response`、`json.dumps`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`httpx.Response`、`json.dumps`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == endpoint
        body = json.loads(request.content)
        assert body["model"] == "qwen3.7-plus"
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "requires_supplemental_evidence": False,
                                    "gaps": [],
                                }
                            )
                        }
                    }
                ],
            },
        )

    client = LiteLlmProxyClient(
        endpoint.removesuffix("/chat/completions"),
        "qwen3.7-plus",
        "test-dashscope-key",
        transport=httpx.MockTransport(handler),
    )

    result = client.generate(
        node_name="evidence_turn",
        system_prompt="system",
        user_prompt="user",
        output_type=SimpleStructuredOutput,
    )

    assert result.model == "qwen3.7-plus"


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_rejects_oversized_non_stream_response` 按协议增量产生或消费结构化模型调用，维持顺序、限额和取消语义；关键协作调用：`LiteLlmProxyClient`、`httpx.Response`、`pytest.raises`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `LiteLlmProxyClient`、`httpx.Response`、`pytest.raises`、`client.generate`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_rejects_oversized_non_stream_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(llm_module, "_MAX_MODEL_RESPONSE_BYTES", 32)

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`httpx.Response`、`_ChunkedByteStream`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `httpx.Response`、`_ChunkedByteStream`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Accept-Encoding"] == "identity"
        return httpx.Response(
            200,
            stream=_ChunkedByteStream(b"x" * 20, b"x" * 20),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentServiceUnavailable, match="size limit"):
        client.generate(
            node_name="evidence_turn",
            system_prompt="system",
            user_prompt="user",
            output_type=SimpleStructuredOutput,
        )


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_rejects_streamed_output_above_cumulative_byte_limit` 按协议增量产生或消费结构化模型调用，维持顺序、限额和取消语义；关键协作调用：`encode`、`LiteLlmProxyClient`、`json.loads`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `encode`、`LiteLlmProxyClient`、`json.loads`、`httpx.Response`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_rejects_streamed_output_above_cumulative_byte_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(llm_module, "_MAX_MODEL_RESPONSE_BYTES", 32)
    stream_body = (
        "data: "
        + json.dumps(
            {"choices": [{"delta": {"content": "x" * 33}}]},
            ensure_ascii=False,
        )
        + "\n\ndata: [DONE]\n\n"
    ).encode()

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`json.loads`、`httpx.Response`、`_ChunkedByteStream`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`httpx.Response`、`_ChunkedByteStream`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert "max_tokens" not in body
        assert body["enable_thinking"] is False
        assert "thinking_budget" not in body
        assert request.headers["Accept-Encoding"] == "identity"
        return httpx.Response(200, stream=_ChunkedByteStream(stream_body))

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentOutputSchemaError, match="above the size limit"):
        list(
            client.generate_stream(
                node_name="evidence_turn",
                system_prompt="system",
                user_prompt="user",
                output_type=SimpleStructuredOutput,
            )
        )


# 所属模块：Python 支撑模块 > test_llm；函数角色：回归测试用例。
# 具体功能：`test_litellm_proxy_rejects_an_oversized_single_stream_delta` 按协议增量产生或消费结构化模型调用，维持顺序、限额和取消语义；关键协作调用：`encode`、`LiteLlmProxyClient`、`httpx.Response`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `encode`、`LiteLlmProxyClient`、`httpx.Response`、`pytest.raises`。
# 系统意义：固定“Python 支撑模块 > test_llm”的可观察契约，防止后续重构改变业务结果。
def test_litellm_proxy_rejects_an_oversized_single_stream_delta(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(llm_module, "_MAX_STREAM_DELTA_BYTES", 4)
    stream_body = (
        "data: "
        + json.dumps({"choices": [{"delta": {"content": "12345"}}]})
        + "\n\n"
    ).encode()

    # 所属模块：Python 支撑模块 > test_llm；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`httpx.Response`、`_ChunkedByteStream`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `httpx.Response`、`_ChunkedByteStream`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, stream=_ChunkedByteStream(stream_body))

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentOutputSchemaError, match="delta above the size limit"):
        list(
            client.generate_stream(
                node_name="evidence_turn",
                system_prompt="system",
                user_prompt="user",
                output_type=SimpleStructuredOutput,
            )
        )


def test_async_stream_reuses_one_client_for_consecutive_provider_calls(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    valid_content = json.dumps({"requires_supplemental_evidence": False, "gaps": []})
    provider_calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        provider_calls.append(request)
        return httpx.Response(
            200,
            content=_structured_stream_body(valid_content, finish_reason="stop"),
        )

    transport = httpx.MockTransport(handler)
    real_async_client = httpx.AsyncClient
    client_constructions: list[dict[str, object]] = []

    def build_async_client(**kwargs: object) -> httpx.AsyncClient:
        client_constructions.append(dict(kwargs))
        return real_async_client(**kwargs)

    monkeypatch.setattr(llm_module.httpx, "AsyncClient", build_async_client)
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        async_transport=transport,
    )

    async def scenario() -> None:
        await asyncio.gather(client.aopen(), client.aopen(), client.aopen())
        assert len(client_constructions) == 1
        for _ in range(2):
            updates = [
                update
                async for update in client.agenerate_stream(
                    node_name="intake_turn_case_detail",
                    system_prompt="system",
                    user_prompt="user",
                    output_type=SimpleStructuredOutput,
                )
            ]
            assert updates[-1].kind == "completed"
        await client.aclose()

    asyncio.run(scenario())

    assert len(provider_calls) == 2
    assert len(client_constructions) == 1
    assert client_constructions[0]["transport"] is transport
    assert "trust_env" not in client_constructions[0]
    assert "verify" not in client_constructions[0]


def test_async_stream_valid_output_does_not_regenerate() -> None:
    provider_calls: list[dict[str, object]] = []
    valid_content = json.dumps(
        {"room_utterance": "一次通过", "required_value": 1},
        ensure_ascii=False,
    )

    def handler(request: httpx.Request) -> httpx.Response:
        provider_calls.append(json.loads(request.content))
        return httpx.Response(
            200,
            content=_structured_stream_body(
                valid_content,
                finish_reason="stop",
                include_usage=True,
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        async_transport=httpx.MockTransport(handler),
    )

    async def scenario() -> list[object]:
        try:
            return [
                update
                async for update in client.agenerate_stream(
                    node_name="intake_turn_case_detail",
                    system_prompt="same-system",
                    user_prompt="same-user",
                    output_type=RegeneratedStreamOutput,
                    visible_fields=(
                        VisibleFieldSpec(
                            property_name="room_utterance",
                            field="room_utterance",
                        ),
                    ),
                    governed_request=_governed_stream_request(),
                )
            ]
        finally:
            await client.aclose()

    updates = asyncio.run(scenario())

    assert len(provider_calls) == 1
    assert [getattr(update, "kind") for update in updates] == [
        "visible_delta",
        "completed",
    ]
    assert updates[-1].generation.provider_attempts_used == 1


def test_async_stream_schema_failure_regenerates_same_request_once() -> None:
    provider_calls: list[dict[str, object]] = []
    responses = iter(
        (
            json.dumps({"room_utterance": "第一代"}, ensure_ascii=False),
            json.dumps(
                {"room_utterance": "第二代", "required_value": 2},
                ensure_ascii=False,
            ),
        )
    )

    def handler(request: httpx.Request) -> httpx.Response:
        provider_calls.append(json.loads(request.content))
        return httpx.Response(
            200,
            content=_structured_stream_body(
                next(responses),
                finish_reason="stop",
                include_usage=True,
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        async_transport=httpx.MockTransport(handler),
    )

    async def scenario() -> list[object]:
        try:
            return [
                update
                async for update in client.agenerate_stream(
                    node_name="intake_turn_case_detail",
                    system_prompt="same-system",
                    user_prompt="same-user",
                    output_type=RegeneratedStreamOutput,
                    visible_fields=(
                        VisibleFieldSpec(
                            property_name="room_utterance",
                            field="room_utterance",
                        ),
                    ),
                    governed_request=_governed_stream_request(),
                )
            ]
        finally:
            await client.aclose()

    updates = asyncio.run(scenario())

    assert len(provider_calls) == 2
    assert provider_calls[0] == provider_calls[1]
    assert [getattr(update, "kind") for update in updates] == [
        "visible_delta",
        "generation_reset",
        "visible_delta",
        "completed",
    ]
    reset = updates[1]
    assert reset.generation == 2
    assert reset.reason_code == "OUTPUT_SCHEMA_INVALID"
    completed = updates[-1].generation
    assert completed.provider_attempts_used == 2
    assert completed.repairs_used == 0
    assert completed.value.room_utterance == "第二代"


def test_async_stream_projection_does_not_starve_event_loop(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Long structured projection must not delay Graph lease heartbeat scheduling."""

    valid_content = json.dumps(
        {"room_utterance": "保持流式顺序", "required_value": 1},
        ensure_ascii=False,
    )

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=_structured_stream_body(valid_content, finish_reason="stop"),
        )

    projection_entered = threading.Event()
    release_projection = threading.Event()
    original_accept = llm_module._AsyncStructuredStreamState.accept

    def held_projection(
        state: llm_module._AsyncStructuredStreamState,
        payload: object,
    ) -> list[object]:
        projection_entered.set()
        if not release_projection.wait(timeout=2.0):
            raise AssertionError("structured projection was not released")
        return original_accept(state, payload)

    monkeypatch.setattr(
        llm_module._AsyncStructuredStreamState,
        "accept",
        held_projection,
    )
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        async_transport=httpx.MockTransport(handler),
    )

    async def consume() -> list[object]:
        return [
            update
            async for update in client.agenerate_stream(
                node_name="intake_turn_case_detail",
                system_prompt="system",
                user_prompt="user",
                output_type=RegeneratedStreamOutput,
            )
        ]

    async def scenario() -> list[object]:
        consume_task = asyncio.create_task(consume())
        try:
            for _ in range(100):
                if projection_entered.is_set():
                    break
                await asyncio.sleep(0.001)
            assert projection_entered.is_set()

            # This scheduling point represents the independent ten-second Graph
            # heartbeat.  Before projection was offloaded, the event loop could not
            # reach it until the synchronous projector returned.
            await asyncio.wait_for(asyncio.sleep(0), timeout=0.1)
            assert not release_projection.is_set()
            release_projection.set()
            return await asyncio.wait_for(consume_task, timeout=2.0)
        finally:
            release_projection.set()
            await client.aclose()

    updates = asyncio.run(scenario())

    assert updates[-1].kind == "completed"
    assert updates[-1].generation.value.room_utterance == "保持流式顺序"


def test_async_client_close_is_concurrent_idempotent_and_rejects_reuse() -> None:
    class CountingAsyncTransport(httpx.AsyncBaseTransport):
        def __init__(self) -> None:
            self.close_calls = 0

        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, request=request, content=b"")

        async def aclose(self) -> None:
            self.close_calls += 1

    transport = CountingAsyncTransport()
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        async_transport=transport,
    )

    async def scenario() -> None:
        async with client._lease_async_client() as leased:
            assert leased is not None
        await asyncio.gather(client.aclose(), client.aclose(), client.aclose())
        await client.aclose()
        with pytest.raises(AgentServiceUnavailable, match="has been closed"):
            async with client._lease_async_client():
                pass
        with pytest.raises(AgentServiceUnavailable, match="has been closed"):
            await client.aopen()

    asyncio.run(scenario())

    assert transport.close_calls == 1


def test_async_client_preserves_environment_and_secure_defaults(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    constructions: list[dict[str, object]] = []

    class StubAsyncClient:
        async def aclose(self) -> None:
            return None

    def build_async_client(**kwargs: object) -> StubAsyncClient:
        constructions.append(dict(kwargs))
        return StubAsyncClient()

    monkeypatch.setattr(llm_module.httpx, "AsyncClient", build_async_client)

    async def scenario() -> None:
        for base_url in ("http://litellm:4000", "https://litellm.example"):
            client = LiteLlmProxyClient(
                base_url,
                "qwen3.7-plus",
                "test-master-key",
            )
            async with client._lease_async_client():
                pass
            await client.aclose()

    asyncio.run(scenario())

    assert len(constructions) == 2
    assert all("trust_env" not in item for item in constructions)
    assert all("verify" not in item for item in constructions)


def test_cancelled_active_stream_cannot_strand_concurrent_close() -> None:
    class BlockingAsyncTransport(httpx.AsyncBaseTransport):
        def __init__(self) -> None:
            self.started = asyncio.Event()
            self.never_respond = asyncio.Event()
            self.close_calls = 0

        async def handle_async_request(
            self, request: httpx.Request
        ) -> httpx.Response:
            self.started.set()
            await self.never_respond.wait()
            raise AssertionError("the cancelled request unexpectedly resumed")

        async def aclose(self) -> None:
            self.close_calls += 1

    transport = BlockingAsyncTransport()
    client = LiteLlmProxyClient(
        "http://litellm:4000",
        "qwen3.7-plus",
        "test-master-key",
        async_transport=transport,
    )

    async def consume_stream() -> None:
        async for _ in client.agenerate_stream(
            node_name="intake_turn_case_detail",
            system_prompt="system",
            user_prompt="user",
            output_type=SimpleStructuredOutput,
        ):
            pass

    async def scenario() -> None:
        stream_task = asyncio.create_task(consume_stream())
        await asyncio.wait_for(transport.started.wait(), timeout=1.0)

        # Hold the lifecycle lock while cancellation unwinds. The former lease
        # cleanup awaited this lock, so a second cancellation could skip its
        # decrement and strand every concurrent aclose waiter.
        await client._async_client_lock.acquire()
        close_tasks = [asyncio.create_task(client.aclose()) for _ in range(3)]
        stream_task.cancel()
        await asyncio.sleep(0)
        stream_task.cancel()
        client._async_client_lock.release()

        with pytest.raises(asyncio.CancelledError):
            await stream_task
        await asyncio.wait_for(asyncio.gather(*close_tasks), timeout=1.0)

    asyncio.run(scenario())

    assert transport.close_calls == 1
