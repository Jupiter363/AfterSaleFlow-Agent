# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Read-only reviewer assistant constrained to one frozen ReviewPacket."""

from __future__ import annotations

from collections.abc import Callable

from app.agents.profiles import final_agent_profiles
from app.harness.guardrails import GuardrailChecker
from app.harness.validation import CitationValidationError
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


ReviewAnswerer = Callable[[ReviewCopilotRequest], ReviewCopilotAnswer]


class ReviewCopilot:
    # 所属模块：Agent 角色能力 > review_copilot；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`GuardrailChecker`、`final_agent_profiles`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `GuardrailChecker`、`final_agent_profiles`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(self, answerer: ReviewAnswerer) -> None:
        self.profile = final_agent_profiles()["review_copilot"]
        self._answerer = answerer
        self._guardrails = GuardrailChecker()

    # 所属模块：Agent 角色能力 > review_copilot；函数角色：类/闭包内部方法。
    # 具体功能：`query` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`self._guardrails.assert_safe_input`、`ReviewCopilotAnswer.model_validate`、`self._guardrails.assert_safe_output`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_validate_refs`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def query(self, request: ReviewCopilotRequest) -> ReviewCopilotAnswer:
        self._guardrails.assert_safe_input(request.question)
        answer = ReviewCopilotAnswer.model_validate(self._answerer(request))
        self._validate_refs(
            "fact",
            answer.fact_refs,
            request.available_fact_refs,
        )
        self._validate_refs(
            "rule",
            answer.rule_refs,
            request.available_rule_refs,
        )
        self._validate_refs(
            "draft",
            answer.draft_refs,
            request.available_draft_refs,
        )
        self._validate_refs(
            "deliberation",
            answer.deliberation_refs,
            request.available_deliberation_refs,
        )
        available_refs = (
            request.available_fact_refs
            + request.available_rule_refs
            + request.available_draft_refs
            + request.available_deliberation_refs
        )
        for statement in answer.statements:
            self._validate_refs(
                f"{statement.kind.lower()} statement",
                statement.refs,
                available_refs,
            )
            self._guardrails.assert_safe_output(statement.text)
        self._guardrails.assert_safe_output(answer.answer)
        return answer

    # 所属模块：Agent 角色能力 > review_copilot；函数角色：类/闭包内部方法。
    # 具体功能：`_validate_refs` 校验本阶段状态的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`CitationValidationError`。
    # 上下游：上游为 本文件的 `ReviewCopilot.query`；下游为 协作调用 `CitationValidationError`。
    # 系统意义：这是信任边界：服从角色权限、上下文范围和非最终结论边界。
    @staticmethod
    def _validate_refs(
        kind: str,
        actual: list[str],
        available: list[str],
    ) -> None:
        unknown = set(actual) - set(available)
        if unknown:
            raise CitationValidationError(
                f"unknown {kind} references: {sorted(unknown)}"
            )
