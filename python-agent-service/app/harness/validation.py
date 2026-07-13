# 文件作用：先用 Pydantic 校验模型输出结构，再把输出中的证据/规则引用限制在本轮冻结上下文白名单内。

"""结构化 Agent 输出及来源引用白名单校验。"""

from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel


OutputT = TypeVar("OutputT", bound=BaseModel)


class CitationValidationError(ValueError):
    """Raised when an Agent cites evidence or rules outside frozen input."""


class StructuredOutputValidator(Generic[OutputT]):
    """先校验 Schema，再对照冻结上下文检查证据与规则引用。"""

    # 所属模块：Agent Harness > 结构化输出 > Schema 与引用白名单初始化。
    # 具体功能：`__init__` 固定本节点期望的 Pydantic 类型，以及冻结输入中实际可用的 evidence_ref/rule_ref 集合；None 被规范成空集合。
    # 上下游：上游是工作流根据 node_name 和冻结卷宗创建验证器；下游是 `validate` 对单次模型结果执行两阶段校验。
    # 系统意义：可引用集合来自系统事实而非模型自报，模型无法通过输出新增一条不存在或不可见的证据/规则来源。
    def __init__(
        self,
        output_model: type[OutputT],
        *,
        available_evidence_refs: set[str] | None = None,
        available_rule_refs: set[str] | None = None,
    ) -> None:
        self._output_model = output_model
        self._evidence_refs = available_evidence_refs or set()
        self._rule_refs = available_rule_refs or set()

    # 所属模块：Agent Harness > 结构化输出 > 两阶段结果验收入口。
    # 具体功能：`validate` 先用 output_model 校验字段、类型、枚举和 extra 规则，再提取 evidence_refs/rule_refs 分别执行集合白名单检查。
    # 上下游：上游是 LLM 解析得到的 raw 对象；下游只有全部通过才返回强类型 OutputT，否则抛 Pydantic 或 CitationValidationError。
    # 系统意义：Prompt 中要求 JSON 只是软约束；这里的运行时校验才是模型输出进入业务状态前的确定性信任边界。
    def validate(self, raw: object) -> OutputT:
        output = self._output_model.model_validate(raw)
        data = output.model_dump()
        self._validate_refs(
            "evidence",
            set(data.get("evidence_refs", [])),
            self._evidence_refs,
        )
        self._validate_refs(
            "rule",
            set(data.get("rule_refs", [])),
            self._rule_refs,
        )
        return output

    # 所属模块：Agent Harness > 结构化输出 > 单类引用集合校验。
    # 具体功能：`_validate_refs` 用集合差集 `actual - available` 找出所有未知引用，并一次性按排序结果报告，便于审计和稳定测试。
    # 上下游：上游是 `validate` 分别传入 evidence/rule 引用；下游是成功返回或阻止结果提交的 CitationValidationError。
    # 系统意义：不仅检查引用格式，还检查本轮是否真正提供该来源，防止模型幻觉引用和跨参与方证据泄露。
    @staticmethod
    def _validate_refs(
        kind: str, actual: set[str], available: set[str]
    ) -> None:
        unknown = actual - available
        if unknown:
            raise CitationValidationError(
                f"unknown {kind} references: {sorted(unknown)}"
            )
