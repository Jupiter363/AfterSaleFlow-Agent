from __future__ import annotations

import json

import httpx

from app.harness.execution_tools import (
    JavaExecutionToolCatalogClient,
    build_execution_tool_intention_section,
)


def test_java_execution_tool_catalog_client_reads_internal_tool_declarations() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/internal/tools/execution"
        assert request.headers["x-service-identity"] == "python-agent-service"
        return httpx.Response(
            200,
            json={
                "success": True,
                "data": [
                    {
                        "action_type": "REFUND",
                        "tool_name": "after_sale_tool",
                        "operation": "refund",
                        "display_name": "模拟退款",
                        "description": "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                        "risk_level": "HIGH",
                        "simulated": True,
                        "requires_approved_plan": True,
                    }
                ],
            },
        )

    client = JavaExecutionToolCatalogClient(
        base_url="http://java-api-service:8080",
        service_identity="python-agent-service",
        transport=httpx.MockTransport(handler),
    )

    declarations = client.fetch()

    assert len(declarations) == 1
    assert declarations[0].action_type == "REFUND"
    assert declarations[0].display_name == "模拟退款"
    assert declarations[0].requires_approved_plan is True


def test_execution_tool_intention_section_is_prompt_safe_and_non_executable() -> None:
    client = JavaExecutionToolCatalogClient(
        base_url="http://unused",
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json={
                    "success": True,
                    "data": [
                        {
                            "action_type": "REFUND",
                            "tool_name": "after_sale_tool",
                            "operation": "refund",
                            "display_name": "模拟退款",
                            "description": "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                            "risk_level": "HIGH",
                            "simulated": True,
                            "requires_approved_plan": True,
                        }
                    ],
                },
            )
        ),
    )

    section = build_execution_tool_intention_section(client.fetch())
    content = json.loads(section.content)

    assert section.name == "execution_tool_intentions"
    assert section.trust_level == "java_tool_catalog"
    assert content["allowed_use"] == "ONLY_PROPOSE_EXECUTION_INTENT"
    assert "不得直接执行" in content["governance_note"]
    assert content["tools"][0]["action_type"] == "REFUND"
    assert content["tools"][0]["display_name"] == "模拟退款"
    assert "tool_name" not in content["tools"][0]
    assert "operation" not in content["tools"][0]
