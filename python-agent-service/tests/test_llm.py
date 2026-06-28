import json

import httpx

from app.llm import LiteLlmProxyClient
from app.schemas import EvidenceGapOutput


def test_litellm_proxy_contract_and_structured_response_validation() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "http://litellm:4000/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-master-key"
        body = json.loads(request.content)
        assert body["model"] == "deepseek-v4-flash"
        assert body["response_format"] == {"type": "json_object"}
        return httpx.Response(
            200,
            json={
                "model": "deepseek-v4-flash",
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
        "deepseek-v4-flash",
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
