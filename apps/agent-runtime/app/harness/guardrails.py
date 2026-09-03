# 文件作用：在模型调用前识别明显 Prompt 注入，在结果提交前识别越权“最终裁决/直接执行”表述。

"""针对不可信证据文本和非最终 Agent 输出的轻量确定性护栏。"""

from __future__ import annotations

import re


class GuardrailViolation(ValueError):
    # 所属模块：Agent Harness > 文本护栏 > 结构化违规异常。
    # 具体功能：`__init__` 保存所有命中的 risk_flags，并把它们拼成标准异常消息供日志、人工中断策略和测试读取。
    # 上下游：上游是 `assert_safe_input`/`assert_safe_output` 的确定性规则命中；下游是工作流失败关闭或转人工，而不是继续模型/业务提交。
    # 系统意义：风险原因以机器可读 tuple 保留，避免调用方只能解析自然语言异常字符串。
    def __init__(self, risk_flags: tuple[str, ...]) -> None:
        self.risk_flags = risk_flags
        super().__init__(", ".join(risk_flags))


class GuardrailChecker:
    """在输入送模和输出提交两个边界检测明显的权限升级文本。"""

    _input_patterns = (
        re.compile(r"ignore\s+(all\s+)?previous\s+instructions", re.I),
        re.compile(r"system\s+prompt", re.I),
        re.compile(r"(refund|reship|review)\.execute", re.I),
        re.compile(r"review\.approve", re.I),
    )
    _final_output_patterns = (
        re.compile(r"\bfinal\s+(decision|ruling|judgment)\b", re.I),
        re.compile(r"\bmust\s+(refund|reship|reject|close)\b", re.I),
        re.compile(r"\bapproved\s+for\s+execution\b", re.I),
    )

    # 所属模块：Agent Harness > 文本护栏 > 模型输入注入检查。
    # 具体功能：`assert_safe_input` 用预编译正则检查忽略既有指令、索取 system prompt、伪造工具执行/审核命令等明显注入模式。
    # 上下游：上游是当事人消息、OCR 证据或其他不可信文本；下游安全时才可进入 Prompt，命中时抛 `GuardrailViolation(PROMPT_INJECTION)`。
    # 系统意义：正则不是完整安全方案，但可在 LLM 前确定性拦截高置信攻击；真正权限仍由 ContextContract 和 ToolGateway 执行。
    def assert_safe_input(self, text: str) -> None:
        flags = []
        if any(pattern.search(text) for pattern in self._input_patterns):
            flags.append("PROMPT_INJECTION")
        if flags:
            raise GuardrailViolation(tuple(flags))

    # 所属模块：Agent Harness > 文本护栏 > 非最终输出权限检查。
    # 具体功能：`assert_safe_output` 检查模型是否把建议写成 final judgment、must refund、approved for execution 等具有最终效力的表述。
    # 上下游：上游是完成 Schema 校验后的可展示文本；下游安全时才允许提交，命中时进入人工复核/失败链路。
    # 系统意义：模型可提出分析或草案，但不能通过措辞越过 Java 审批、人工审核和真实执行边界。
    def assert_safe_output(self, text: str) -> None:
        flags = []
        if any(
            pattern.search(text) for pattern in self._final_output_patterns
        ):
            flags.append("FINAL_DECISION_CLAIM")
        if flags:
            raise GuardrailViolation(tuple(flags))
