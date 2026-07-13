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


class HearingContext(StrictModel):
    completed_statement_rounds: Annotated[int, Field(ge=0)] = 0
    max_statement_rounds: Annotated[int, Field(ge=0)] = 0
    final_convergence: bool = False
    must_produce_final_plan: bool = False
    allow_supplemental_request: bool = True
    courtroom_context: dict[str, object] = Field(default_factory=dict)
    sealed_rounds: Annotated[list[dict[str, object]], Field(max_length=20)] = Field(
        default_factory=list
    )


class HearingAnalyzeRequest(StrictModel):
    case_id: Annotated[str, StringConstraints(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
    workflow_id: Identifier
    user_id: Identifier | None = None
    claims: Annotated[list[PartyClaim], Field(min_length=1, max_length=50)]
    evidence: Annotated[list[EvidenceItem], Field(max_length=100)] = Field(
        default_factory=list
    )
    policy_candidates: Annotated[list[PolicyCandidate], Field(max_length=30)] = Field(
        default_factory=list
    )
    evidence_timeout: bool = False
    hearing_context: HearingContext = Field(default_factory=HearingContext)


class FramedIssue(StrictModel):
    issue_id: Identifier
    title: ShortText
    neutral_description: LongText
    related_claim_ids: Annotated[list[Identifier], Field(min_length=1, max_length=50)]
    confidence: Confidence


class IssueFramingOutput(StrictModel):
    neutral_summary: LongText
    issues: Annotated[list[FramedIssue], Field(min_length=1, max_length=20)]


class EvidenceGap(StrictModel):
    gap_id: Identifier
    issue_id: Identifier
    required_evidence_type: Identifier
    reason: LongText
    requested_from: PartyType


class EvidenceGapOutput(StrictModel):
    requires_supplemental_evidence: bool
    gaps: Annotated[list[EvidenceGap], Field(max_length=30)]


class LiaisonMessage(StrictModel):
    party_type: PartyType
    neutral_message: LongText
    requested_evidence_types: Annotated[list[Identifier], Field(max_length=20)]


class PartyLiaisonOutput(StrictModel):
    messages: Annotated[list[LiaisonMessage], Field(min_length=1, max_length=10)]


class CrossCheckFinding(StrictModel):
    issue_id: Identifier
    supported_by: Annotated[list[Identifier], Field(max_length=100)]
    contradicted_by: Annotated[list[Identifier], Field(max_length=100)]
    missing_evidence: bool
    neutral_analysis: LongText
    confidence: Confidence


class EvidenceCrossCheckOutput(StrictModel):
    findings: Annotated[list[CrossCheckFinding], Field(min_length=1, max_length=20)]
    unresolved_conflicts: Annotated[list[ShortText], Field(max_length=30)]


class RuleApplication(StrictModel):
    issue_id: Identifier
    rule_code: Identifier
    rule_version: Annotated[int, Field(ge=1)]
    applicable: bool
    rationale: LongText
    limitations: Annotated[list[ShortText], Field(max_length=20)]


class RuleApplicationOutput(StrictModel):
    applications: Annotated[list[RuleApplication], Field(max_length=30)]
    missing_policy: bool


class DraftIssueFinding(StrictModel):
    issue_id: Identifier
    suggested_finding: LongText
    evidence_basis: Annotated[list[Identifier], Field(max_length=100)]
    policy_basis: Annotated[list[Identifier], Field(max_length=30)]


class JuryReviewResponse(StrictModel):
    dimension: Literal[
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    ]
    severity: Literal["NONE", "LOW", "MEDIUM", "HIGH", "BLOCKER"]
    disposition: Literal["ACCEPTED", "PARTIALLY_ACCEPTED", "NOT_ACCEPTED"]
    response: LongText
    basis: Annotated[list[ShortText], Field(min_length=1, max_length=6)]


class AdjudicationDraft(StrictModel):
    draft_status: Literal["PENDING_HUMAN_REVIEW"] = "PENDING_HUMAN_REVIEW"
    recommended_outcome: ShortText
    reasoning_summary: LongText
    issue_findings: Annotated[list[DraftIssueFinding], Field(max_length=20)]
    confidence: Confidence
    risk_level: RiskLevel
    review_focus: Annotated[list[ShortText], Field(min_length=1, max_length=30)]
    jury_review_responses: Annotated[
        list[JuryReviewResponse],
        Field(max_length=6),
    ] = Field(default_factory=list)
    requires_human_review: Literal[True] = True
    is_final_decision: Literal[False] = False


class AdjudicationDraftOutput(StrictModel):
    draft: AdjudicationDraft


class HearingAnalysisResult(StrictModel):
    case_id: str
    workflow_id: str
    workflow_status: Literal["COMPLETED", "MANUAL_REVIEW_REQUIRED"]
    executed_nodes: list[str]
    issue_framing: IssueFramingOutput | None = None
    evidence_gap: EvidenceGapOutput | None = None
    party_liaison: PartyLiaisonOutput | None = None
    evidence_cross_check: EvidenceCrossCheckOutput | None = None
    rule_application: RuleApplicationOutput | None = None
    adjudication_draft: AdjudicationDraftOutput
    manual_review_reasons: list[str] = Field(default_factory=list)
    prompt_version: str
    model: str


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
