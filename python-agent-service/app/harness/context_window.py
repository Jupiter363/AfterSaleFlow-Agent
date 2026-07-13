# 文件作用：在模型输入上限内按“必需性+业务优先级”选择 Prompt 段，并记录哪些可选上下文因超预算被省略。

from __future__ import annotations

import json
from dataclasses import dataclass


@dataclass(frozen=True)
class PromptSection:
    """准备放入 prompt 的一段上下文。

    priority 越高越优先保留；required=True 表示超预算也不能静默丢弃；
    prompt_included=False 表示只给 UI/审计展示，不进入模型 prompt。
    """

    name: str
    content: str
    priority: int = 50
    required: bool = False
    trust_level: str = "untrusted"
    prompt_included: bool = True

    # 所属模块：Agent Harness > Token 窗口 > PromptSection 成本估算。
    # 具体功能：`estimated_tokens` 把段名和正文长度按约 4 字符/Token 向上估算；display-only 或空段计为 0，因为它们不会发给模型。
    # 上下游：上游是 `ContextWindowManager.assemble` 的逐段预算判断和响应审计展示；下游是保留/省略决定与总估算值。
    # 系统意义：这是防止请求超过模型上下文窗口的确定性保护，不依赖某个供应商 tokenizer，因而切换模型时仍可工作。
    def estimated_tokens(self) -> int:
        """粗略估算 token 数。

        这里用字符数 / 4 的经验值，不追求和模型 tokenizer 完全一致，
        目标是避免超长上下文进入模型请求。
        """

        if not self.content or not self.prompt_included:
            return 0
        return max(1, (len(self.name) + len(self.content) + 3) // 4)


@dataclass(frozen=True)
class AssembledPromptContext:
    """在 token 预算下筛选后的上下文集合。"""

    sections: tuple[PromptSection, ...]
    estimated_tokens: int
    omitted_section_names: tuple[str, ...]

    # 所属模块：Agent Harness > Token 窗口 > 已选上下文的模型载荷投影。
    # 具体功能：`as_prompt_payload` 把不可变 dataclass 转成 JSON 友好字典，同时公开每段的信任级、优先级、必需标记、估算成本及省略清单。
    # 上下游：上游是 `ContextWindowManager.assemble` 的选择结果；下游是 `HarnessModelRunner` 注入到 untrusted_case_data 的 `harness_context`。
    # 系统意义：模型输入和审计记录共享同一份选择结果，能解释某次回答为何没有看到低优先级历史，而不是把裁剪伪装成“数据不存在”。
    def as_prompt_payload(self) -> dict[str, object]:
        """转成可以塞进 user prompt 的结构化 payload。"""

        return {
            "sections": [
                {
                    "name": section.name,
                    "content": _structured_section_content(section.content),
                    "estimated_tokens": section.estimated_tokens(),
                    "priority": section.priority,
                    "required": section.required,
                    "trust_level": section.trust_level,
                }
                for section in self.sections
            ],
            "estimated_tokens": self.estimated_tokens,
            "omitted_section_names": list(self.omitted_section_names),
        }


# 所属模块：Agent Harness > Token 窗口 > 段内容结构恢复。
# 具体功能：`_structured_section_content` 尝试把 ContextPack 中的 JSON 字符串还原成 dict/list；普通自然语言或不完整 JSON 则保持字符串。
# 上下游：上游是 `AssembledPromptContext.as_prompt_payload`；下游是 Prompt 的结构化 `sections[].content`。
# 系统意义：避免“JSON 套 JSON”产生大量反斜杠并浪费 Token，也让模型明确看到字段层级；解析失败回退文本保证兼容纯文本段。
def _structured_section_content(content: str) -> object:
    """Keep JSON context as JSON instead of embedding escaped JSON strings."""

    try:
        return json.loads(content)
    except (TypeError, json.JSONDecodeError):
        return content


class ContextWindowManager:
    """上下文窗口管理器：按优先级在 token 预算内选择 prompt sections。"""

    # 所属模块：Agent Harness > Token 窗口 > 默认预算配置。
    # 具体功能：`__init__` 校验并保存所有节点共用的默认输入预算；单次调用仍可通过 `max_input_tokens` 覆盖。
    # 上下游：上游是服务启动时创建 `HarnessModelRunner`；下游是每次 `assemble` 未显式传预算时的上限。
    # 系统意义：在任何模型请求发生前拒绝零值/负值配置，避免错误配置表现为随机丢上下文。
    def __init__(self, default_max_input_tokens: int = 32_000) -> None:
        if default_max_input_tokens < 1:
            raise ValueError("default_max_input_tokens must be positive")
        self._default_max_input_tokens = default_max_input_tokens

    # 所属模块：Agent Harness > Token 窗口 > 模型输入裁剪主算法。
    # 具体功能：`assemble` 先排除空段和 display-only 段，再按 required、priority、name 稳定排序，逐段装入预算并记录被省略的可选段。
    # 上下游：上游是 `ContextPack.prompt_sections()` 或调用方提供的 PromptSection；下游是 `AssembledPromptContext`，随后由模型运行器渲染 Prompt。
    # 系统意义：必需段超限会立即失败，不能让模型在缺失案件身份/当前轮次时继续猜测；可选段才允许按优先级降级。
    def assemble(
        self,
        sections: list[PromptSection],
        *,
        max_input_tokens: int | None = None,
    ) -> AssembledPromptContext:
        """选择最终进入模型的上下文段。

        排序规则：
        1. required 段优先；
        2. priority 高的优先；
        3. 名称排序保证结果稳定，方便测试。
        """

        budget = max_input_tokens or self._default_max_input_tokens
        if budget < 1:
            raise ValueError("max_input_tokens must be positive")

        selected: list[PromptSection] = []
        omitted: list[str] = []
        used = 0
        # 列表推导式先过滤不进入模型的段；排序 tuple 中 False 小于 True，故 required=True 会排在最前。
        ordered = sorted(
            [
                section
                for section in sections
                if section.content and section.prompt_included
            ],
            key=lambda section: (
                not section.required,
                -section.priority,
                section.name,
            ),
        )
        # 逐段装入而不截断内容，避免把 JSON、引用或一段当事人陈述切成语义不完整的半段。
        for section in ordered:
            cost = section.estimated_tokens()
            if used + cost <= budget:
                selected.append(section)
                used += cost
                continue
            if section.required:
                raise ValueError(
                    f"required context section {section.name} exceeds token budget"
                )
            omitted.append(section.name)

        return AssembledPromptContext(
            sections=tuple(selected),
            estimated_tokens=used,
            omitted_section_names=tuple(omitted),
        )
