import json

import httpx
import pytest

from app.llm import LiteLlmProxyClient
from app.schemas import EvidenceGapOutput


def test_litellm_proxy_contract_and_structured_response_validation() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "http://litellm:4000/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-master-key"
        body = json.loads(request.content)
        assert body["model"] == "qwen3.7-plus"
        assert body["response_format"] == {"type": "json_object"}
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
        node_name="evidence_gap_request_node",
        system_prompt="system",
        user_prompt="user",
        output_type=EvidenceGapOutput,
    )

    assert result.value.requires_supplemental_evidence is False
    assert result.token_usage["total"] == 14


def test_litellm_proxy_sends_inline_multimodal_evidence_parts() -> None:
    data_url = (
        "data:image/png;base64,"
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8A"
        "AQUBAScY42YAAAAASUVORK5CYII="
    )

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
        output_type=EvidenceGapOutput,
        user_content_parts=[
            {"type": "text", "text": "Evidence EVIDENCE_image follows."},
            {
                "type": "image_url",
                "image_url": {"url": data_url, "detail": "high"},
            },
        ],
    )

    assert result.model == "qwen3.7-plus"


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


def test_litellm_proxy_repairs_empty_structured_content_with_plain_json_retry() -> None:
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            assert body["response_format"] == {"type": "json_object"}
            return httpx.Response(
                200,
                json={
                    "model": "qwen3.7-plus",
                    "choices": [{"message": {"content": ""}}],
                },
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
        node_name="evidence_gap_request_node",
        system_prompt="system",
        user_prompt="user",
        output_type=EvidenceGapOutput,
    )

    assert len(calls) == 2
    assert result.value.requires_supplemental_evidence is False
    assert result.token_usage["total"] == 20


def test_litellm_proxy_retries_without_json_mode_when_provider_rejects_response_format() -> None:
    calls: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(body)
        if len(calls) == 1:
            assert body["response_format"] == {"type": "json_object"}
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
        node_name="evidence_gap_request_node",
        system_prompt="system",
        user_prompt="user",
        output_type=EvidenceGapOutput,
    )

    assert len(calls) == 2
    assert result.value.requires_supplemental_evidence is False
    assert result.token_usage["total"] == 18


def test_dashscope_v1_base_url_does_not_duplicate_version_segment() -> None:
    endpoint = (
        "https://ws-veazvl2fycrurdmv.cn-beijing.maas.aliyuncs.com/"
        "compatible-mode/v1/chat/completions"
    )

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
        node_name="evidence_gap_request_node",
        system_prompt="system",
        user_prompt="user",
        output_type=EvidenceGapOutput,
    )

    assert result.model == "qwen3.7-plus"
