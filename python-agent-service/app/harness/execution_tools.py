# 文件作用：只读拉取 Java 所拥有的履约工具声明/执行事件，并把它们包装成“仅可提议或观察”的 Prompt 上下文。

"""Read-only execution tool declarations for Agent planning.

The Python harness may present these declarations as allowed execution
intentions, but it must not execute business tools. Java remains the authority
for approval validation, idempotency, audit records, and simulated/real adapter
selection.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.harness.context_window import PromptSection


class ExecutionToolDeclaration(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    action_type: str = Field(min_length=1, max_length=128)
    tool_name: str = Field(min_length=1, max_length=128)
    operation: str = Field(min_length=1, max_length=128)
    display_name: str = Field(min_length=1, max_length=128)
    description: str = Field(min_length=1, max_length=2_000)
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    simulated: bool
    requires_approved_plan: bool


class ExecutionToolEventObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    action_type: str = Field(min_length=1, max_length=128)
    execution_status: str = Field(min_length=1, max_length=64)
    reference_id: str | None = Field(default=None, max_length=256)
    simulated: bool = False
    observed_at: str | None = Field(default=None, max_length=128)


class _JavaCatalogResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    success: bool
    data: list[ExecutionToolDeclaration]


class JavaExecutionToolCatalogClient:
    """Fetches Java-owned execution tool declarations without executing them."""

    # 所属模块：Agent Harness > Java 履约目录 > 内部只读客户端初始化。
    # 具体功能：`__init__` 固定 Java 基地址、服务身份、超时与可测试 transport；去除尾部斜杠避免请求路径出现双斜杠。
    # 上下游：上游是服务配置；下游是 `fetch` 对固定 internal endpoint 的 GET 请求。
    # 系统意义：该客户端只读取能力声明，不持有审批令牌或执行接口，因此 Python Agent 无法借此直接退款、补发或关闭案件。
    def __init__(
        self,
        *,
        base_url: str,
        service_identity: str = "python-agent-service",
        timeout_seconds: float = 10.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._service_identity = service_identity
        self._timeout_seconds = timeout_seconds
        self._transport = transport

    # 所属模块：Agent Harness > Java 履约目录 > 工具声明读取。
    # 具体功能：`fetch` 以服务身份 GET 固定目录接口，先检查 HTTP 状态，再用 `_JavaCatalogResponse` 校验 success 与每条 ExecutionToolDeclaration。
    # 上下游：上游是法官/草案上下文准备；下游是 `build_execution_tool_intention_section`，不会调用 declaration.operation。
    # 系统意义：接口失败或响应结构异常不应伪装成空工具目录；Java 继续拥有真实执行、幂等、审计和模拟/生产适配器选择权。
    def fetch(self) -> list[ExecutionToolDeclaration]:
        with httpx.Client(
            base_url=self._base_url,
            timeout=self._timeout_seconds,
            transport=self._transport,
        ) as client:
            response = client.get(
                "/internal/tools/execution",
                headers={"X-Service-Identity": self._service_identity},
            )
            response.raise_for_status()
        payload = _JavaCatalogResponse.model_validate(response.json())
        if not payload.success:
            raise RuntimeError("java execution tool catalog returned failure")
        return list(payload.data)


# 所属模块：Agent Harness > Java 履约目录 > “仅提议动作”Prompt 投影。
# 具体功能：`build_execution_tool_intention_section` 按 action_type 稳定排序，只暴露动作名称、描述、风险、模拟标志与审批要求，不把真实 operation/handler 变成可调用工具。
# 上下游：上游是 Java 返回并经 Pydantic 校验的声明；下游是庭审/裁判草案 ContextPack 的 execution_tool_intentions 段。
# 系统意义：LLM 只能提出结构化执行意图；最终方案批准后仍必须回到 Java ToolRegistry、幂等锁和审计链路执行。
def build_execution_tool_intention_section(
    declarations: Iterable[ExecutionToolDeclaration],
) -> PromptSection:
    """Render Java tool declarations as proposal-only Agent context."""

    tools = [
        {
            "action_type": declaration.action_type,
            "display_name": declaration.display_name,
            "description": declaration.description,
            "risk_level": declaration.risk_level,
            "simulated": declaration.simulated,
            "requires_approved_plan": declaration.requires_approved_plan,
        }
        for declaration in sorted(declarations, key=lambda item: item.action_type)
    ]
    content = {
        "allowed_use": "ONLY_PROPOSE_EXECUTION_INTENT",
        "governance_note": (
            "这些工具只能作为裁决草案或执行计划的动作意图提出，"
            "不得直接执行；最终执行必须回到 Java 审核、幂等、审计和 ToolRegistry 链路。"
        ),
        "tools": tools,
    }
    return PromptSection(
        name="execution_tool_intentions",
        content=json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        priority=60,
        required=False,
        trust_level="java_tool_catalog",
        prompt_included=True,
    )


# 所属模块：Agent Harness > Java 履约目录 > “只读执行结果”Prompt 投影。
# 具体功能：`build_execution_tool_event_observation_section` 把 Java 已发生的动作、状态、引用号、模拟标志和时间编码成只读观察段，不提供重试或执行入口。
# 上下游：上游是 Java 持久化的 ExecutionToolEventObservation；下游是后续 Agent 用于说明执行进度的 ContextPack 段。
# 系统意义：观察到失败/成功不等于 Python 可再次执行；治理说明明确禁止绕过 Java 审批、幂等和审计。
def build_execution_tool_event_observation_section(
    events: Iterable[ExecutionToolEventObservation],
) -> PromptSection:
    """Render Java execution events as read-only Agent observations."""

    event_items = [
        {
            "action_type": event.action_type,
            "execution_status": event.execution_status,
            "reference_id": event.reference_id,
            "simulated": event.simulated,
            "observed_at": event.observed_at,
        }
        for event in events
    ]
    content = {
        "allowed_use": "OBSERVE_EXECUTION_EVENTS_ONLY",
        "governance_note": (
            "这些事件只能作为 Java 执行业务工具后的只读观察记录，"
            "不得直接执行、不得重试、不得绕过 Java 审核、幂等和审计链路。"
        ),
        "events": event_items,
    }
    return PromptSection(
        name="execution_tool_event_observations",
        content=json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        priority=55,
        required=False,
        trust_level="java_execution_events",
        prompt_included=True,
    )
