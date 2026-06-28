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


class AdjudicationDraft(StrictModel):
    draft_status: Literal["PENDING_HUMAN_REVIEW"] = "PENDING_HUMAN_REVIEW"
    recommended_outcome: ShortText
    reasoning_summary: LongText
    issue_findings: Annotated[list[DraftIssueFinding], Field(max_length=20)]
    confidence: Confidence
    risk_level: RiskLevel
    review_focus: Annotated[list[ShortText], Field(min_length=1, max_length=30)]
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
