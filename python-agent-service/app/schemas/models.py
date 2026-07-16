# 文件作用：Python Agent 数据契约文件，使用 Pydantic 定义请求、响应和模型输出结构。

"""Core final-compatible Agent request and output models."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


Identifier = Annotated[str, StringConstraints(min_length=3, max_length=128)]
ShortText = Annotated[str, StringConstraints(min_length=1, max_length=2_000)]
LongText = Annotated[str, StringConstraints(min_length=1, max_length=20_000)]
Confidence = Annotated[float, Field(ge=0.0, le=1.0)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PartyType(StrEnum):
    USER = "USER"
    MERCHANT = "MERCHANT"
    PLATFORM = "PLATFORM"


class RiskLevel(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class PartyClaim(StrictModel):
    claim_id: Identifier
    party_type: PartyType
    statement: LongText


class EvidenceItem(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    content: LongText


class PolicyCandidate(StrictModel):
    rule_code: Identifier
    rule_version: Annotated[int, Field(ge=1)]
    rule_text: LongText


class IntakeAnalyzeRequest(StrictModel):
    order_id: Identifier | None = None
    after_sale_id: Identifier | None = None
    user_id: Identifier
    merchant_id: Identifier
    description: LongText
    attachment_ids: Annotated[list[Identifier], Field(max_length=30)] = Field(
        default_factory=list
    )
    channel: Identifier


class IntakeAnalysisOutput(StrictModel):
    case_type: Identifier
    dispute_type: Identifier | None = None
    risk_level: RiskLevel
    potential_dispute: bool
    missing_slots: Annotated[list[Identifier], Field(max_length=20)]
    title: ShortText
    normalized_description: LongText


class EvaluationAnalyzeRequest(StrictModel):
    case_id: Annotated[str, StringConstraints(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
    case_status: Literal["CLOSED"]
    route_type: Identifier
    risk_level: RiskLevel
    approval_decision: Literal["APPROVE", "MODIFY_AND_APPROVE"]
    adjudication_draft: dict[str, object]
    approved_plan: dict[str, object]
    action_records: Annotated[list[dict[str, object]], Field(max_length=100)]
    evidence_summary: dict[str, object]
    policy_summary: dict[str, object]


class EvaluationFinding(StrictModel):
    category: Literal[
        "EVIDENCE_GAP",
        "POLICY_GAP",
        "PROCESS_GAP",
        "AGENT_QUALITY",
        "EXECUTION",
    ]
    severity: Literal["LOW", "MEDIUM", "HIGH"]
    summary: ShortText
    supporting_references: Annotated[list[Identifier], Field(max_length=50)]


class EvaluationQualitativeScores(StrictModel):
    evidence_quality_score: Confidence
    policy_coverage_score: Confidence
    execution_quality_score: Confidence
    process_quality_score: Confidence
    overall_quality_score: Confidence


class EvaluationMetricScores(EvaluationQualitativeScores):
    draft_approval_rate: Confidence
    reviewer_modification_rate: Confidence


class EvaluationAgentOutput(StrictModel):
    qualitative_scores: EvaluationQualitativeScores
    findings: Annotated[list[EvaluationFinding], Field(max_length=50)]
    rule_gap_suggestions: Annotated[list[ShortText], Field(max_length=30)]
    improvement_suggestions: Annotated[list[ShortText], Field(max_length=30)]
    automatic_changes_applied: Literal[False] = False
    online_case_mutated: Literal[False] = False


class EvaluationAnalysisResult(StrictModel):
    case_id: str
    evaluation_status: Literal["COMPLETED"]
    metric_scores: EvaluationMetricScores
    findings: list[EvaluationFinding]
    rule_gap_suggestions: list[str]
    improvement_suggestions: list[str]
    automatic_changes_applied: Literal[False] = False
    online_case_mutated: Literal[False] = False
    evaluator_model: str
    prompt_version: str
    latency_ms: Annotated[int, Field(ge=0)]
    token_usage: Annotated[int, Field(ge=0)]
