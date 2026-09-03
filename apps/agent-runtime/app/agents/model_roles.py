# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""LiteLLM-backed evaluators for deliberation and review assistance."""

from __future__ import annotations

from app.llm import StructuredLlmClient
from app.harness.prompt_composer import PromptRepository
from app.schemas import (
    CriticDraft,
    CriticType,
    FrozenDeliberationInput,
    ReviewCopilotAnswer,
    ReviewCopilotRequest,
)


class ModelCriticEvaluator:
    """Run each critic with an independent prompt and a shared frozen payload."""

    # 所属模块：Agent 角色能力 > model_roles；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 符合 Schema 的角色分析结果。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
    ) -> None:
        self._llm = llm
        self._prompts = prompts

    # 所属模块：Agent 角色能力 > model_roles；函数角色：类/闭包内部方法。
    # 具体功能：`__call__` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`critic_type.value.lower`、`self._prompts.render`、`self._llm.generate`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `critic_type.value.lower`、`self._prompts.render`、`self._llm.generate`、`CriticDraft.model_validate`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __call__(
        self,
        critic_type: CriticType,
        frozen_input: FrozenDeliberationInput,
        fingerprint: str,
    ) -> CriticDraft:
        node_name = critic_type.value.lower()
        system_prompt, user_prompt = self._prompts.render(
            node_name,
            {
                "frozen_input_fingerprint": fingerprint,
                "frozen_input": frozen_input.model_dump(mode="json"),
            },
            CriticDraft.model_json_schema(),
        )
        generation = self._llm.generate(
            node_name=node_name,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            output_type=CriticDraft,
        )
        return CriticDraft.model_validate(generation.value)


class ModelReviewAnswerer:
    """Answer only from the versioned ReviewPacket embedded in the request."""

    # 所属模块：Agent 角色能力 > model_roles；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 符合 Schema 的角色分析结果。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
    ) -> None:
        self._llm = llm
        self._prompts = prompts

    # 所属模块：Agent 角色能力 > model_roles；函数角色：类/闭包内部方法。
    # 具体功能：`__call__` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self._prompts.render`、`self._llm.generate`、`ReviewCopilotAnswer.model_validate`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `self._prompts.render`、`self._llm.generate`、`ReviewCopilotAnswer.model_validate`、`request.model_dump`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __call__(
        self,
        request: ReviewCopilotRequest,
    ) -> ReviewCopilotAnswer:
        system_prompt, user_prompt = self._prompts.render(
            "review_copilot",
            request.model_dump(mode="json"),
            ReviewCopilotAnswer.model_json_schema(),
        )
        generation = self._llm.generate(
            node_name="review_copilot",
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            output_type=ReviewCopilotAnswer,
        )
        return ReviewCopilotAnswer.model_validate(generation.value)
