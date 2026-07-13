# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Offline-only final Evaluation Agent."""

from __future__ import annotations

from typing import Any

from app.agents.profiles import final_agent_profiles
from app.schemas import EvaluationAnalysisResult


class EvaluationAgent:
    # 所属模块：Agent 角色能力 > evaluation_agent；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`final_agent_profiles`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `final_agent_profiles`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(self, workflow: Any) -> None:
        self.profile = final_agent_profiles()["evaluation_agent"]
        self._workflow = workflow

    # 所属模块：Agent 角色能力 > evaluation_agent；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`EvaluationAnalysisResult.model_validate`、`PermissionError`、`self.profile.authorizes_case_state`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `EvaluationAnalysisResult.model_validate`、`PermissionError`、`self.profile.authorizes_case_state`、`self._workflow.analyze`。
    # 系统意义：失败显式映射为 `PermissionError`，避免错误状态被当成成功结果。
    def analyze(
        self,
        request: Any,
        trace_context: Any,
        *,
        offline: bool,
    ) -> EvaluationAnalysisResult:
        if not offline:
            raise PermissionError("evaluation agent is offline-only")
        if not self.profile.authorizes_case_state(request.case_status):
            raise PermissionError("evaluation agent requires a closed case")
        result = EvaluationAnalysisResult.model_validate(
            self._workflow.analyze(request, trace_context)
        )
        if result.online_case_mutated or result.automatic_changes_applied:
            raise PermissionError("evaluation agent cannot mutate online state")
        return result
