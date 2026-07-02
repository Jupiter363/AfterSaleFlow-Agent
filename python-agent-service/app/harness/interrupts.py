"""Human-interrupt policy for risk and governance failures."""

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
    """Converts risk signals into an explicit durable workflow interrupt."""

    def __init__(self, confidence_threshold: float = 0.65) -> None:
        if not 0 <= confidence_threshold <= 1:
            raise ValueError("confidence_threshold must be between 0 and 1")
        self._confidence_threshold = confidence_threshold

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
