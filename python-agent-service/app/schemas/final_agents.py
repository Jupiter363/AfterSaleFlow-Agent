"""Strict contracts for the six final AI Native agent roles."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field

from app.schemas.models import (
    EvidenceItem,
    Identifier,
    LongText,
    PartyClaim,
    PolicyCandidate,
    ShortText,
    StrictModel,
)


class DisputeIntakeRequest(StrictModel):
    submission_id: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    raw_text: LongText
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    attachments: Annotated[list[Identifier], Field(max_length=30)] = Field(
        default_factory=list
    )
    channel: Identifier


class IntakeClaim(StrictModel):
    party: Literal["USER", "MERCHANT"]
    claim_text: LongText
    source_ref: Identifier


class DisputeIntakeResult(StrictModel):
    is_potential_dispute: bool
    admissibility_recommendation: Literal[
        "ACCEPTED",
        "NEED_MORE_INFO",
        "TRANSFERRED",
    ]
    dispute_type: Identifier | None = None
    initiator: Literal["USER", "MERCHANT"]
    claims: Annotated[list[IntakeClaim], Field(min_length=1, max_length=20)]
    requested_remedy: Literal[
        "REFUND",
        "REPLACEMENT",
        "RETURN",
        "REJECT_REFUND",
        "OTHER",
        "UNKNOWN",
    ]
    missing_initial_fields: list[Identifier] = Field(default_factory=list)
    risk_signals: list[Identifier] = Field(default_factory=list)
    confidence: Annotated[float, Field(ge=0, le=1)]
    next_step: Literal[
        "BUILD_DOSSIER",
        "REQUEST_MORE_INFO",
        "TRANSFER",
    ]
    liability_determined: Literal[False] = False
    remedy_promised: Literal[False] = False


class HearingStage(StrEnum):
    C1_ISSUE_FRAMING = "C1_ISSUE_FRAMING"
    C2_EVIDENCE_GAP = "C2_EVIDENCE_GAP"
    C3_EVIDENCE_REQUEST = "C3_EVIDENCE_REQUEST"
    C4_EVIDENCE_CROSS_CHECK = "C4_EVIDENCE_CROSS_CHECK"
    C5_RULE_APPLICATION = "C5_RULE_APPLICATION"
    C6_DRAFT_GENERATION = "C6_DRAFT_GENERATION"


class HearingStageRequest(StrictModel):
    case_id: Identifier
    workflow_id: Identifier
    user_id: Identifier | None = None
    stage: HearingStage
    dossier_version: Annotated[int, Field(ge=1)]
    claims: Annotated[list[PartyClaim], Field(min_length=1, max_length=50)]
    evidence: Annotated[list[EvidenceItem], Field(max_length=100)] = Field(
        default_factory=list
    )
    policy_candidates: Annotated[list[PolicyCandidate], Field(max_length=30)] = Field(
        default_factory=list
    )
    previous_stage_outputs: dict[str, object] = Field(default_factory=dict)
    evidence_timeout: bool = False


class HearingStageResult(StrictModel):
    case_id: Identifier
    workflow_id: Identifier
    stage: HearingStage
    dossier_version: Annotated[int, Field(ge=1)]
    output: dict[str, object]
    output_schema: Identifier
    prompt_version: Identifier
    model: Identifier
    non_final: Literal[True] = True
    requires_human_review: Literal[True] = True


class EvidenceBuildRequest(StrictModel):
    case_id: Identifier
    case_version: Annotated[int, Field(ge=1)]
    submission_version: Annotated[int, Field(ge=1)]
    current_dossier_version: Annotated[int, Field(ge=1)] | None = None
    party_claims: Annotated[list[PartyClaim], Field(max_length=50)] = Field(
        default_factory=list
    )
    evidence: Annotated[list["DossierEvidenceItem"], Field(max_length=200)] = Field(
        default_factory=list
    )


class DossierEvidenceItem(EvidenceItem):
    """Original evidence plus separately stored derived representations."""

    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    occurred_at: ShortText | None = None
    related_claim_ids: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )
    parser_warning: ShortText | None = None


class EvidenceCatalogEntry(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    original_ref: Identifier
    original_content: LongText
    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    is_party_statement: bool


class ClaimIssueEvidenceLink(StrictModel):
    claim_id: Identifier
    issue_id: Identifier | None = None
    evidence_refs: Annotated[list[Identifier], Field(max_length=100)] = Field(
        default_factory=list
    )


class EvidenceTimelineEvent(StrictModel):
    event_id: Identifier
    occurred_at: ShortText
    description: LongText
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class EvidenceDossierResult(StrictModel):
    case_id: Identifier
    dossier_version: Annotated[int, Field(ge=1)]
    timeline: list[EvidenceTimelineEvent] = Field(default_factory=list)
    party_claims: list[PartyClaim] = Field(default_factory=list)
    evidence_catalog: list[EvidenceCatalogEntry] = Field(default_factory=list)
    claim_issue_evidence_matrix: list[ClaimIssueEvidenceLink] = Field(
        default_factory=list
    )
    conflicts: list[ShortText] = Field(default_factory=list)
    gaps: list[ShortText] = Field(default_factory=list)
    duplicate_groups: list[list[Identifier]] = Field(default_factory=list)
    parser_warnings: list[ShortText] = Field(default_factory=list)
    policy_candidates: list[Identifier] = Field(default_factory=list)
    source_citations: list[Identifier] = Field(default_factory=list)
    liability_determined: Literal[False] = False
    remedy_recommended: Literal[False] = False


class CriticType(StrEnum):
    EVIDENCE = "EVIDENCE_CRITIC"
    RULE = "RULE_CRITIC"
    RISK = "RISK_CRITIC"
    REMEDY = "REMEDY_CRITIC"
    FAIRNESS = "FAIRNESS_CRITIC"


class CriticSeverity(StrEnum):
    NONE = "NONE"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    BLOCKER = "BLOCKER"


class CriticStatus(StrEnum):
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"


class FrozenDeliberationInput(StrictModel):
    """One immutable version tuple shared by every panel member."""

    case_id: Identifier
    case_snapshot_version: Annotated[int, Field(ge=1)]
    dossier_version: Annotated[int, Field(ge=1)]
    adjudication_draft_version: Annotated[int, Field(ge=1)]
    rule_version: Identifier
    remedy_plan_candidate_version: Annotated[int, Field(ge=1)] | None = None
    payload: dict[str, object]


class CriticDraft(StrictModel):
    severity: CriticSeverity
    findings: list[ShortText] = Field(default_factory=list)
    blocking_issues: list[Identifier] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    frozen_input_fingerprint: str | None = None


class CriticReport(StrictModel):
    critic: CriticType
    scope: Literal["EVIDENCE", "RULE", "RISK", "REMEDY", "FAIRNESS"]
    status: CriticStatus
    severity: CriticSeverity
    findings: list[ShortText] = Field(default_factory=list)
    blocking_issues: list[Identifier] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    frozen_input_fingerprint: str
    failure_reason: ShortText | None = None
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


class DeliberationRequest(StrictModel):
    frozen_input: FrozenDeliberationInput


class DeliberationReport(StrictModel):
    deliberation_id: Identifier
    report_version: Annotated[int, Field(ge=1)] = 1
    panel_result: Literal[
        "NO_MAJOR_OBJECTION",
        "REVISION_REQUIRED",
        "MANUAL_REVIEW_REQUIRED",
    ]
    frozen_input_fingerprint: str
    critic_reports: list[CriticReport]
    major_risks: list[Identifier] = Field(default_factory=list)
    consensus: list[ShortText] = Field(default_factory=list)
    disagreements: list[ShortText] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    reviewer_attention: list[ShortText] = Field(default_factory=list)
    revision_required: bool
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


class ReviewCopilotRequest(StrictModel):
    review_id: Identifier
    case_id: Identifier
    review_packet_version: Annotated[int, Field(ge=1)]
    reviewer_role: Literal["PLATFORM_REVIEWER"]
    question: LongText
    available_fact_refs: list[Identifier] = Field(default_factory=list)
    available_rule_refs: list[Identifier] = Field(default_factory=list)
    available_draft_refs: list[Identifier] = Field(default_factory=list)
    available_deliberation_refs: list[Identifier] = Field(default_factory=list)
    frozen_packet: dict[str, object] = Field(default_factory=dict)


class ReviewStatement(StrictModel):
    kind: Literal["FACT", "INFERENCE", "SUGGESTION"]
    text: LongText
    refs: list[Identifier] = Field(default_factory=list)


class ReviewCopilotAnswer(StrictModel):
    answer: LongText
    statements: Annotated[list[ReviewStatement], Field(min_length=1, max_length=50)]
    fact_refs: list[Identifier] = Field(default_factory=list)
    rule_refs: list[Identifier] = Field(default_factory=list)
    draft_refs: list[Identifier] = Field(default_factory=list)
    deliberation_refs: list[Identifier] = Field(default_factory=list)
    uncertainties: list[ShortText] = Field(default_factory=list)
    suggested_review_focus: list[ShortText] = Field(default_factory=list)
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False
