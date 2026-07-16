# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Composition root for the non-hearing internal Agent APIs."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


class AnalyzeAgent(Protocol):
    # 所属模块：Python Agent 服务边界 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 同步/流式 Agent 响应、标准错误。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    def analyze(self, *args: Any, **kwargs: Any) -> Any: ...


class EvidenceAgent(Protocol):
    # 所属模块：Python Agent 服务边界 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`build` 把上游材料组装为本阶段可消费的本阶段状态。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 同步/流式 Agent 响应、标准错误。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    def build(self, *args: Any, **kwargs: Any) -> Any: ...


class DeliberationAgent(Protocol):
    # 所属模块：Python Agent 服务边界 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`run` 驱动本阶段状态对应的业务步骤并返回阶段结果。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 同步/流式 Agent 响应、标准错误。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    def run(self, *args: Any, **kwargs: Any) -> Any: ...


class ReviewCopilotAgent(Protocol):
    # 所属模块：Python Agent 服务边界 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`query` 读取并按案件、角色或会话范围筛选本阶段状态。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 同步/流式 Agent 响应、标准错误。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    def query(self, *args: Any, **kwargs: Any) -> Any: ...


@dataclass(frozen=True)
class FinalAgentServices:
    intake: AnalyzeAgent
    evidence: EvidenceAgent
    deliberation: DeliberationAgent
    review_copilot: ReviewCopilotAgent
    evaluation: AnalyzeAgent
