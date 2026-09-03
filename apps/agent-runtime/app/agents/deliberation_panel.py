# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Panel aggregation that preserves minority and unavailable-critic risks."""

from __future__ import annotations

from uuid import uuid4

from app.agents.critics import CriticAgent, frozen_input_fingerprint
from app.schemas import (
    CriticSeverity,
    CriticStatus,
    DeliberationReport,
    DeliberationRequest,
)


class DeliberationPanel:
    # 所属模块：合议评审 Agent；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`ValueError`。
    # 上下游：上游为 法官草案、证据分析、规则适用；下游为 协作调用 `ValueError`。
    # 系统意义：失败显式映射为 `ValueError`，避免错误状态被当成成功结果。
    def __init__(self, critics: list[CriticAgent]) -> None:
        if not critics:
            raise ValueError("deliberation panel requires at least one critic")
        self._critics = tuple(critics)

    # 所属模块：合议评审 Agent；函数角色：类/闭包内部方法。
    # 具体功能：`run` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`frozen_input_fingerprint`、`DeliberationReport`、`critic.review`。
    # 上下游：上游为 法官草案、证据分析、规则适用；下游为 协作调用 `frozen_input_fingerprint`、`DeliberationReport`、`critic.review`、`dict.fromkeys`。
    # 系统意义：该函数在系统中的业务边界是：只提出风险和分歧，不修改正式裁判。
    def run(self, request: DeliberationRequest) -> DeliberationReport:
        fingerprint = frozen_input_fingerprint(request.frozen_input)
        reports = [
            critic.review(request.frozen_input)
            for critic in self._critics
        ]
        unavailable = [
            item
            for item in reports
            if item.status is not CriticStatus.COMPLETED
        ]
        blockers = [
            item
            for item in reports
            if item.severity is CriticSeverity.BLOCKER
        ]
        high_risks = [
            item
            for item in reports
            if item.severity in {CriticSeverity.HIGH, CriticSeverity.BLOCKER}
        ]
        major_risks = list(
            dict.fromkeys(
                issue
                for report in high_risks
                for issue in report.blocking_issues
            )
        )
        if unavailable:
            panel_result = "MANUAL_REVIEW_REQUIRED"
        elif blockers:
            panel_result = "REVISION_REQUIRED"
        else:
            panel_result = "NO_MAJOR_OBJECTION"
        recommendations = [
            report.recommended_revision
            for report in high_risks
            if report.recommended_revision
        ]
        return DeliberationReport(
            deliberation_id=f"DELIBERATION_{uuid4().hex}",
            panel_result=panel_result,
            frozen_input_fingerprint=fingerprint,
            critic_reports=reports,
            trigger_reasons=request.trigger_reasons,
            major_risks=major_risks,
            consensus=[
                f"{report.critic.value}: {report.findings[0]}"
                for report in reports
                if report.status is CriticStatus.COMPLETED
                and report.severity
                in {CriticSeverity.NONE, CriticSeverity.LOW}
                and report.findings
            ],
            disagreements=[
                f"{report.critic.value}: {finding}"
                for report in high_risks
                for finding in report.findings
            ],
            recommended_revision="; ".join(recommendations) or None,
            reviewer_attention=[
                f"Review {report.critic.value} ({report.status.value})"
                for report in unavailable + high_risks
            ],
            revision_required=bool(blockers or unavailable),
        )
