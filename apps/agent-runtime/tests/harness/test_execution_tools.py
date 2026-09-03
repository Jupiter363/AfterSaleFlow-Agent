# 文件作用：自动化测试文件，验证 test_execution_tools 相关模块的行为、契约或页面布局。

from __future__ import annotations

import json

import httpx

from app.harness.execution_tools import (
    ExecutionToolEventObservation,
    JavaExecutionToolCatalogClient,
    build_execution_tool_event_observation_section,
    build_execution_tool_intention_section,
)


# 所属模块：Agent Harness > test_execution_tools；函数角色：回归测试用例。
# 具体功能：`test_java_execution_tool_catalog_client_reads_internal_tool_declarations` 读取并按案件、角色或会话范围筛选履约执行动作；关键协作调用：`JavaExecutionToolCatalogClient`、`client.fetch`、`httpx.Response`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `JavaExecutionToolCatalogClient`、`client.fetch`、`httpx.Response`、`httpx.MockTransport`。
# 系统意义：固定“Agent Harness > test_execution_tools”的可观察契约，防止后续重构改变业务结果。
def test_java_execution_tool_catalog_client_reads_internal_tool_declarations() -> None:
    # 所属模块：Agent Harness > test_execution_tools；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`httpx.Response`。
    # 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `httpx.Response`。
    # 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
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


# 所属模块：Agent Harness > test_execution_tools；函数角色：回归测试用例。
# 具体功能：`test_execution_tool_intention_section_is_prompt_safe_and_non_executable` 验证模型提示词在固定案例中的输出、边界和失败行为；关键协作调用：`JavaExecutionToolCatalogClient`、`build_execution_tool_intention_section`、`json.loads`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `JavaExecutionToolCatalogClient`、`build_execution_tool_intention_section`、`json.loads`、`client.fetch`。
# 系统意义：固定“Agent Harness > test_execution_tools”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：Agent Harness > test_execution_tools；函数角色：回归测试用例。
# 具体功能：`test_execution_tool_event_observation_section_is_read_only` 读取并按案件、角色或会话范围筛选履约执行动作；关键协作调用：`build_execution_tool_event_observation_section`、`json.loads`、`ExecutionToolEventObservation`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `build_execution_tool_event_observation_section`、`json.loads`、`ExecutionToolEventObservation`。
# 系统意义：固定“Agent Harness > test_execution_tools”的可观察契约，防止后续重构改变业务结果。
def test_execution_tool_event_observation_section_is_read_only() -> None:
    section = build_execution_tool_event_observation_section(
        [
            ExecutionToolEventObservation(
                action_type="REFUND",
                execution_status="SUCCEEDED",
                reference_id="SIM_REFUND_1",
                simulated=True,
                observed_at="2026-07-10T02:00:00+08:00",
            )
        ]
    )
    content = json.loads(section.content)

    assert section.name == "execution_tool_event_observations"
    assert section.trust_level == "java_execution_events"
    assert content["allowed_use"] == "OBSERVE_EXECUTION_EVENTS_ONLY"
    assert "不得直接执行" in content["governance_note"]
    assert "不得重试" in content["governance_note"]
    assert content["events"] == [
        {
            "action_type": "REFUND",
            "execution_status": "SUCCEEDED",
            "reference_id": "SIM_REFUND_1",
            "simulated": True,
            "observed_at": "2026-07-10T02:00:00+08:00",
        }
    ]
