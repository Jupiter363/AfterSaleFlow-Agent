# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Final AI Presiding Judge identity over hearing workflows."""

from __future__ import annotations

from typing import Any

from app.agents.profiles import final_agent_profiles
from app.schemas import (
    HearingAnalysisResult,
    HearingStage,
    HearingStageResult,
)


class PresidingJudge:
    # 所属模块：庭审法官 Agent > 结构化听证 LangGraph；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`final_agent_profiles`。
    # 上下游：上游为 冻结卷宗、双方陈述、可见证据、陪审团 A2A 提示；下游为 协作调用 `final_agent_profiles`。
    # 系统意义：该函数在系统中的业务边界是：遵守三轮程序，AI 意见必须进入人工审核。
    def __init__(self, workflow: Any) -> None:
        self.profile = final_agent_profiles()["presiding_judge"]
        self._workflow = workflow

    # 所属模块：庭审法官 Agent > 结构化听证 LangGraph；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`HearingAnalysisResult.model_validate`、`self.profile.authorizes_case_state`、`PermissionError`。
    # 上下游：上游为 冻结卷宗、双方陈述、可见证据、陪审团 A2A 提示；下游为 协作调用 `HearingAnalysisResult.model_validate`、`self.profile.authorizes_case_state`、`PermissionError`、`self._workflow.analyze`。
    # 系统意义：失败显式映射为 `PermissionError`，避免错误状态被当成成功结果。
    def analyze(
        self,
        request: Any,
        trace_context: Any,
        *,
        case_state: str,
    ) -> HearingAnalysisResult:
        if not self.profile.authorizes_case_state(case_state):
            raise PermissionError(
                f"presiding judge cannot run in case state {case_state}"
            )
        result = HearingAnalysisResult.model_validate(
            self._workflow.analyze(request, trace_context)
        )
        draft = result.adjudication_draft.draft
        if draft.is_final_decision or not draft.requires_human_review:
            raise PermissionError("presiding judge output bypassed human review")
        return result

    # 所属模块：庭审法官 Agent > 结构化听证 LangGraph；函数角色：类/闭包内部方法。
    # 具体功能：`run_stage` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`HearingStageResult.model_validate`、`self.profile.authorizes_case_state`、`PermissionError`。
    # 上下游：上游为 冻结卷宗、双方陈述、可见证据、陪审团 A2A 提示；下游为 协作调用 `HearingStageResult.model_validate`、`self.profile.authorizes_case_state`、`PermissionError`、`self._workflow.run_stage`。
    # 系统意义：失败显式映射为 `PermissionError`，避免错误状态被当成成功结果。
    def run_stage(
        self,
        request: Any,
        trace_context: Any,
        *,
        case_state: str,
    ) -> HearingStageResult:
        if not self.profile.authorizes_case_state(case_state):
            raise PermissionError(
                f"presiding judge cannot run in case state {case_state}"
            )
        result = HearingStageResult.model_validate(
            self._workflow.run_stage(request, trace_context)
        )
        if result.stage is HearingStage.C6_DRAFT_GENERATION and (
            result.recommended_draft is None or not result.reviewer_attention
        ):
            raise PermissionError(
                "C6 must return a non-final draft and reviewer attention"
            )
        return result


__all__ = ["PresidingJudge"]
