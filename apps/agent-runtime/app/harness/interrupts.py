# 文件作用：把风险等级、模型置信度、校验失败、评议异议与权限异常转换成明确的人工中断原因。

"""风险与治理失败的确定性人工中断策略。"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class InterruptReason(StrEnum):
    HIGH_RISK = "HIGH_RISK"
    LOW_CONFIDENCE = "LOW_CONFIDENCE"
    OUTPUT_VALIDATION_FAILED = "OUTPUT_VALIDATION_FAILED"
    MAJOR_DELIBERATION_OBJECTION = "MAJOR_DELIBERATION_OBJECTION"
    PERMISSION_ANOMALY = "PERMISSION_ANOMALY"


class RiskAssessment(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    risk_level: str
    confidence: float = Field(ge=0, le=1)
    validation_failures: int = Field(ge=0)
    major_objections: int = Field(ge=0)
    permission_anomaly: bool = False


class HumanInterrupt(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    reasons: tuple[InterruptReason, ...]
    requires_human: bool = True


class HumanInterruptPolicy:
    """把多个风险信号聚合成可被持久化工作流识别的 HumanInterrupt。"""

    # 所属模块：Agent Harness > 人工中断 > 低置信度阈值配置。
    # 具体功能：`__init__` 校验阈值属于闭区间 0..1 并保存，供所有风险评估使用同一标准。
    # 上下游：上游是服务配置；下游是 `evaluate` 的 LOW_CONFIDENCE 判定。
    # 系统意义：配置越界在启动/构造时即失败，避免把百分数 65 错当概率导致所有案件被错误中断。
    def __init__(self, confidence_threshold: float = 0.65) -> None:
        if not 0 <= confidence_threshold <= 1:
            raise ValueError("confidence_threshold must be between 0 and 1")
        self._confidence_threshold = confidence_threshold

    # 所属模块：Agent Harness > 人工中断 > 多信号聚合决策。
    # 具体功能：`evaluate` 独立检查高风险、低置信、输出校验失败、重大评议异议和权限异常，可同时保留多个 InterruptReason；无风险时返回 None。
    # 上下游：上游是模型/护栏/评议面板形成的 `RiskAssessment`；下游是 Temporal/业务工作流暂停并创建人工审核任务。
    # 系统意义：是否转人工由确定性策略决定而非让 LLM 自报；保留全部原因能支持审核排序和事后解释。
    def evaluate(
        self, assessment: RiskAssessment
    ) -> HumanInterrupt | None:
        reasons: list[InterruptReason] = []
        if assessment.risk_level in {"HIGH", "CRITICAL"}:
            reasons.append(InterruptReason.HIGH_RISK)
        if assessment.confidence < self._confidence_threshold:
            reasons.append(InterruptReason.LOW_CONFIDENCE)
        if assessment.validation_failures > 0:
            reasons.append(InterruptReason.OUTPUT_VALIDATION_FAILED)
        if assessment.major_objections > 0:
            reasons.append(InterruptReason.MAJOR_DELIBERATION_OBJECTION)
        if assessment.permission_anomaly:
            reasons.append(InterruptReason.PERMISSION_ANOMALY)
        if not reasons:
            return None
        return HumanInterrupt(reasons=tuple(reasons))
