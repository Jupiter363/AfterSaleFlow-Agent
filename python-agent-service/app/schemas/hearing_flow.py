"""Explicit, independently persistable contracts for ``hearing_flow.v2``."""

from __future__ import annotations

import hashlib
import json
from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import Field, model_validator

from app.schemas.intake_case_matrix import FactCategory, FactMateriality, FactStance
from app.schemas.case_fact_matrix import CaseFactMatrixV2
from app.schemas.models import Confidence, Identifier, LongText, ShortText, StrictModel


CaseId = Annotated[str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
FactId = Annotated[str, Field(pattern=r"^FACT_[A-Za-z0-9_:-]{1,123}$")]
ContentHash = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
PartyRole = Literal["USER", "MERCHANT"]


class HearingFlowStageCode(StrEnum):
    INTAKE_QUESTIONS = "INTAKE_QUESTIONS"
    INTAKE_SYNTHESIS = "INTAKE_SYNTHESIS"
    EVIDENCE_REQUESTS = "EVIDENCE_REQUESTS"
    EVIDENCE_SYNTHESIS = "EVIDENCE_SYNTHESIS"
    JUDGE_V1 = "JUDGE_V1"
    JURY_REVIEW = "JURY_REVIEW"
    JUDGE_V2 = "JUDGE_V2"


class HearingFlowRequest(StrictModel):
    flow_schema_version: Literal["hearing_flow.v2"] = "hearing_flow.v2"
    case_id: CaseId
    workflow_id: Identifier
    stage_code: HearingFlowStageCode
    stage_sequence: Annotated[int, Field(ge=1)]
    stage_deadline_at: ShortText | None = None
    source_refs: Annotated[list[Identifier], Field(max_length=256)] = Field(default_factory=list)


class HearingPartyPerspectivePrompts(StrictModel):
    USER: LongText
    MERCHANT: LongText


class HearingIntakeQuestionDraft(StrictModel):
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=20)]
    issue_statement: LongText
    party_prompts: HearingPartyPerspectivePrompts

    @model_validator(mode="after")
    def unique_references(self) -> "HearingIntakeQuestionDraft":
        if len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("intake issue fact_ids must be unique")
        return self


class HearingIntakeQuestion(StrictModel):
    question_id: Identifier
    target_roles: Annotated[list[PartyRole], Field(min_length=1, max_length=2)]
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=20)]
    question_text: LongText
    issue_id: Identifier | None = None
    issue_statement: LongText | None = None
    party_prompts: HearingPartyPerspectivePrompts | None = None

    @model_validator(mode="after")
    def valid_references(self) -> "HearingIntakeQuestion":
        if len(self.target_roles) != len(set(self.target_roles)):
            raise ValueError("question target_roles must be unique")
        if len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("question fact_ids must be unique")
        enriched = (self.issue_id, self.issue_statement, self.party_prompts)
        if any(value is not None for value in enriched) and not all(
            value is not None for value in enriched
        ):
            raise ValueError("issue_id, issue_statement, and party_prompts must appear together")
        return self


class HearingIntakeQuestionsLlmOutput(StrictModel):
    questions: Annotated[list[HearingIntakeQuestionDraft], Field(min_length=1, max_length=5)]
    public_message: LongText


class HearingIntakeQuestionsRequest(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.INTAKE_QUESTIONS]
    case_fact_matrix: CaseFactMatrixV2
    max_questions: Annotated[int, Field(ge=1, le=5)] = 5


class HearingIntakeQuestionsResult(StrictModel):
    schema_version: Literal["hearing_intake_questions.v1"] = "hearing_intake_questions.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    speaker_role: Literal["INTAKE_OFFICER"] = "INTAKE_OFFICER"
    questions: Annotated[list[HearingIntakeQuestion], Field(min_length=1, max_length=5)]
    public_message: LongText


class HearingPartySubmission(StrictModel):
    participant_id: Identifier
    participant_role: PartyRole
    terminal_status: Literal["COMPLETED", "TIMED_OUT", "ABSENT"]
    submission_source: Literal["PARTY_ACTION", "AUTO_TIMEOUT", "SYSTEM_IMPORT"]
    source_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(default_factory=list)
    statement_text: LongText | None = None
    submission: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="before")
    @classmethod
    def promote_nested_participant_id(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data

        participant_id = data.get("participant_id")
        is_missing_or_blank = participant_id is None or (
            isinstance(participant_id, str) and not participant_id.strip()
        )
        if not is_missing_or_blank:
            return data

        submission = data.get("submission")
        nested_participant_id = (
            submission.get("participant_id") if isinstance(submission, dict) else None
        )
        promoted = data.copy()
        if nested_participant_id is None or (
            isinstance(nested_participant_id, str) and not nested_participant_id.strip()
        ):
            if isinstance(participant_id, str) and not participant_id.strip():
                promoted.pop("participant_id", None)
            return promoted

        promoted["participant_id"] = nested_participant_id
        return promoted


class HearingFactPositionDelta(StrictModel):
    stance: FactStance
    position_summary: LongText
    asserted_value: ShortText | None = None

    @model_validator(mode="after")
    def require_current_position(self) -> "HearingFactPositionDelta":
        if self.stance == FactStance.NOT_ADDRESSED:
            raise ValueError("omit an unchanged party position instead of using NOT_ADDRESSED")
        return self


class HearingFactPositionUpdates(StrictModel):
    USER: HearingFactPositionDelta | None = None
    MERCHANT: HearingFactPositionDelta | None = None

    @model_validator(mode="after")
    def require_an_update(self) -> "HearingFactPositionUpdates":
        if self.USER is None and self.MERCHANT is None:
            raise ValueError("a hearing fact delta requires at least one party update")
        return self


class HearingCaseFactDeltaRow(StrictModel):
    fact_key: Annotated[
        str,
        Field(pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$"),
    ]
    category: FactCategory
    fact_target: LongText
    materiality: FactMateriality
    positions: HearingFactPositionUpdates
    agreed_statement: LongText | None = None
    conflict_summary: LongText | None = None


HearingFactKey = Annotated[
    str,
    Field(pattern=r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|NEW_[A-Za-z0-9_:-]{1,123})$"),
]


class HearingCaseFactMatrixDelta(StrictModel):
    schema_version: Literal["hearing_case_fact_matrix.delta.v1"] = (
        "hearing_case_fact_matrix.delta.v1"
    )
    neutral_summary: LongText
    core_conflict: LongText
    fact_rows: Annotated[list[HearingCaseFactDeltaRow], Field(max_length=200)] = Field(
        default_factory=list
    )
    summary_source_fact_keys: Annotated[list[HearingFactKey], Field(min_length=1, max_length=200)]

    @model_validator(mode="after")
    def unique_keys(self) -> "HearingCaseFactMatrixDelta":
        keys = [row.fact_key for row in self.fact_rows]
        if len(keys) != len(set(keys)):
            raise ValueError("hearing fact delta keys must be unique")
        return self


class HearingDisputePointDraft(StrictModel):
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=50)]
    summary: LongText
    requires_resolution: bool


class HearingDisputePoint(HearingDisputePointDraft):
    dispute_point_id: Identifier


class HearingIssueCoverage(StrEnum):
    ADDRESSED = "ADDRESSED"
    PARTIALLY_ADDRESSED = "PARTIALLY_ADDRESSED"
    NOT_ADDRESSED = "NOT_ADDRESSED"


class HearingIssuePartyMappingDraft(StrictModel):
    coverage: HearingIssueCoverage
    position_summary: LongText | None = None

    @model_validator(mode="after")
    def summary_matches_coverage(self) -> "HearingIssuePartyMappingDraft":
        if self.coverage == HearingIssueCoverage.NOT_ADDRESSED:
            if self.position_summary is not None:
                raise ValueError("NOT_ADDRESSED issue mapping cannot include a position summary")
        elif self.position_summary is None:
            raise ValueError("an addressed issue mapping requires a position summary")
        return self


class HearingIssuePartyMappingsDraft(StrictModel):
    USER: HearingIssuePartyMappingDraft
    MERCHANT: HearingIssuePartyMappingDraft


class HearingIssueMappingDraft(StrictModel):
    issue_id: Identifier
    party_positions: HearingIssuePartyMappingsDraft


class HearingIssuePartyMapping(HearingIssuePartyMappingDraft):
    statement_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )


class HearingIssuePartyMappings(StrictModel):
    USER: HearingIssuePartyMapping
    MERCHANT: HearingIssuePartyMapping


class HearingIssueMapping(StrictModel):
    issue_id: Identifier
    issue_statement: LongText
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=20)]
    party_positions: HearingIssuePartyMappings


class HearingIntakeSynthesisLlmOutput(StrictModel):
    case_fact_matrix_delta: HearingCaseFactMatrixDelta
    issue_mappings: Annotated[list[HearingIssueMappingDraft], Field(max_length=5)]
    public_message: LongText


class HearingIntakeSynthesisRequest(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.INTAKE_SYNTHESIS]
    questions: Annotated[list[HearingIntakeQuestion], Field(max_length=5)] = Field(
        default_factory=list
    )
    party_submissions: Annotated[list[HearingPartySubmission], Field(min_length=2, max_length=2)]
    case_fact_matrix: CaseFactMatrixV2

    @model_validator(mode="after")
    def require_both_parties(self) -> "HearingIntakeSynthesisRequest":
        roles = [item.participant_role for item in self.party_submissions]
        if set(roles) != {"USER", "MERCHANT"} or len(roles) != len(set(roles)):
            raise ValueError("party_submissions must contain USER and MERCHANT once")
        participant_ids = [item.participant_id for item in self.party_submissions]
        if len(participant_ids) != len(set(participant_ids)):
            raise ValueError("party_submissions must contain two distinct participant_id values")
        return self


class HearingIntakeSynthesisResult(StrictModel):
    schema_version: Literal["hearing_intake_synthesis.v1"] = "hearing_intake_synthesis.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    case_fact_matrix: CaseFactMatrixV2
    dispute_points: Annotated[list[HearingDisputePoint], Field(max_length=30)]
    issue_mappings: Annotated[list[HearingIssueMapping], Field(max_length=5)]
    public_message: LongText


class FactEvidenceRelation(StrEnum):
    SUPPORTS = "SUPPORTS"
    OPPOSES = "OPPOSES"
    INCONCLUSIVE = "INCONCLUSIVE"


class FactEvidenceCoverageStatus(StrEnum):
    COVERED_BY_SUBMITTED_EVIDENCE = "COVERED_BY_SUBMITTED_EVIDENCE"
    COVERED_BY_FROZEN_DOSSIER = "COVERED_BY_FROZEN_DOSSIER"
    PARTIALLY_COVERED_BY_FROZEN_DOSSIER = "PARTIALLY_COVERED_BY_FROZEN_DOSSIER"
    NOT_COVERED_BY_FROZEN_DOSSIER = "NOT_COVERED_BY_FROZEN_DOSSIER"
    REQUIRES_HUMAN_REVIEW = "REQUIRES_HUMAN_REVIEW"


class FactEvidenceMatrixParentRefV2(StrictModel):
    matrix_id: Identifier
    matrix_version: Annotated[int, Field(ge=1)]
    content_hash: ContentHash


class FactEvidenceLinkV2(StrictModel):
    fact_id: FactId
    evidence_id: Identifier
    relation: FactEvidenceRelation
    reason: ShortText
    confidence: Confidence
    source_batch_id: Identifier | None = None


class FactEvidenceCoverageV2(StrictModel):
    fact_id: FactId
    coverage_status: FactEvidenceCoverageStatus
    evidence_ids: Annotated[list[Identifier], Field(max_length=200)] = Field(default_factory=list)
    note: ShortText


class FactEvidenceMatrixV2(StrictModel):
    schema_version: Literal["fact_evidence_matrix.v2"] = "fact_evidence_matrix.v2"
    case_id: CaseId
    matrix_id: Identifier
    matrix_version: Annotated[int, Field(ge=1)]
    matrix_status: Literal["WORKING", "FROZEN"]
    parent_ref: FactEvidenceMatrixParentRefV2 | None = None
    case_fact_matrix_id: Identifier
    case_fact_matrix_version: Annotated[int, Field(ge=1)]
    case_fact_matrix_hash: ContentHash
    content_hash: ContentHash
    source_refs: Annotated[list[Identifier], Field(max_length=256)] = Field(default_factory=list)
    links: Annotated[list[FactEvidenceLinkV2], Field(max_length=2_000)] = Field(
        default_factory=list
    )
    fact_coverage: Annotated[list[FactEvidenceCoverageV2], Field(max_length=200)] = Field(
        default_factory=list
    )

    @model_validator(mode="after")
    def validate_matrix(self) -> "FactEvidenceMatrixV2":
        link_keys = [(item.fact_id, item.evidence_id) for item in self.links]
        if len(link_keys) != len(set(link_keys)):
            raise ValueError("fact/evidence links must be unique")
        coverage_ids = [item.fact_id for item in self.fact_coverage]
        if len(coverage_ids) != len(set(coverage_ids)):
            raise ValueError("fact coverage rows must be unique")
        if self.content_hash != content_hash(self, hash_field="content_hash"):
            raise ValueError("fact_evidence_matrix.v2 content hash is invalid")
        return self


class HearingEvidenceDossier(StrictModel):
    dossier_id: Identifier
    dossier_version: Annotated[int, Field(ge=1)]
    dossier_status: Literal["WORKING", "FROZEN"]
    fact_evidence_matrix: FactEvidenceMatrixV2
    evidence_summary: dict[str, Any] = Field(default_factory=dict)
    evidence_gaps: Annotated[list[ShortText], Field(max_length=100)] = Field(default_factory=list)

    @model_validator(mode="after")
    def require_frozen_matrix(self) -> "HearingEvidenceDossier":
        if self.dossier_status != "FROZEN":
            raise ValueError("hearing evidence requests require a frozen dossier")
        if self.fact_evidence_matrix.matrix_status != "FROZEN":
            raise ValueError("hearing evidence requests require a frozen evidence matrix")
        return self


class HearingEvidenceRequestDraft(StrictModel):
    target_roles: Annotated[list[PartyRole], Field(min_length=1, max_length=2)]
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=50)]
    requested_material: LongText
    verification_goal: LongText
    required: bool = True

    @model_validator(mode="after")
    def unique_references(self) -> "HearingEvidenceRequestDraft":
        if len(self.target_roles) != len(set(self.target_roles)):
            raise ValueError("evidence request target_roles must be unique")
        if len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("evidence request fact_ids must be unique")
        return self


class HearingEvidenceRequest(HearingEvidenceRequestDraft):
    request_id: Identifier


class HearingEvidenceRequestsLlmOutput(StrictModel):
    requests: Annotated[list[HearingEvidenceRequestDraft], Field(max_length=10)] = Field(
        default_factory=list
    )
    public_message: LongText


class HearingEvidenceRequestsRequest(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.EVIDENCE_REQUESTS]
    case_fact_matrix: CaseFactMatrixV2
    evidence_dossier: HearingEvidenceDossier


class HearingEvidenceRequestsResult(StrictModel):
    schema_version: Literal["hearing_evidence_requests.v1"] = "hearing_evidence_requests.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    requests: Annotated[list[HearingEvidenceRequest], Field(max_length=10)]
    public_message: LongText


class HearingEvidenceFile(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    original_filename: ShortText | None = None
    content_type: ShortText | None = None
    file_hash: ShortText | None = None
    parsed_text: Annotated[str, Field(max_length=2_000_000)] | None = None
    claimed_fact: LongText | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class HearingEvidencePartyBatch(StrictModel):
    participant_role: PartyRole
    terminal_status: Literal["COMPLETED", "TIMED_OUT", "ABSENT"]
    submission_source: Literal["PARTY_ACTION", "AUTO_TIMEOUT"]
    batch_id: Identifier
    request_ids: Annotated[list[Identifier], Field(max_length=10)] = Field(default_factory=list)
    batch_note: Annotated[str, Field(max_length=1_000)] = ""
    source_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(default_factory=list)
    evidence: Annotated[list[HearingEvidenceFile], Field(max_length=50)] = Field(
        default_factory=list
    )

    @model_validator(mode="after")
    def validate_terminal_batch(self) -> "HearingEvidencePartyBatch":
        if len(self.request_ids) != len(set(self.request_ids)):
            raise ValueError("batch request_ids must be unique")
        if self.terminal_status == "COMPLETED":
            if self.submission_source != "PARTY_ACTION":
                raise ValueError("a completed evidence batch requires PARTY_ACTION")
        elif self.submission_source != "AUTO_TIMEOUT" or self.evidence:
            raise ValueError("a timed-out or absent evidence batch must be empty")
        return self


class HearingBatchEvidenceFactLink(StrictModel):
    fact_id: FactId
    relation: FactEvidenceRelation
    reason: ShortText
    confidence: Confidence


class HearingBatchEvidenceAssessment(StrictModel):
    evidence_id: Identifier
    fact_links: Annotated[list[HearingBatchEvidenceFactLink], Field(max_length=50)]
    summary: LongText
    requires_human_review: bool = False

    @model_validator(mode="after")
    def unique_fact_links(self) -> "HearingBatchEvidenceAssessment":
        fact_ids = [item.fact_id for item in self.fact_links]
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("one evidence assessment cannot repeat a fact link")
        return self


class HearingEvidenceFileAssessmentLlmOutput(StrictModel):
    fact_links: Annotated[list[HearingBatchEvidenceFactLink], Field(max_length=50)]
    summary: LongText
    requires_human_review: bool = False

    @model_validator(mode="after")
    def unique_fact_links(self) -> "HearingEvidenceFileAssessmentLlmOutput":
        fact_ids = [item.fact_id for item in self.fact_links]
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("one evidence assessment cannot repeat a fact link")
        return self


class HearingEvidenceSynthesisLlmOutput(StrictModel):
    evidence_summary: dict[str, Any] = Field(default_factory=dict)
    evidence_gaps: Annotated[list[ShortText], Field(max_length=100)] = Field(default_factory=list)
    public_message: LongText


class HearingEvidenceSynthesisRequest(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.EVIDENCE_SYNTHESIS]
    requests: Annotated[list[HearingEvidenceRequest], Field(max_length=10)] = Field(
        default_factory=list
    )
    party_batches: Annotated[list[HearingEvidencePartyBatch], Field(min_length=2, max_length=2)]
    case_fact_matrix: CaseFactMatrixV2
    prior_fact_evidence_matrix: FactEvidenceMatrixV2 | None = None

    @model_validator(mode="after")
    def require_both_batches(self) -> "HearingEvidenceSynthesisRequest":
        roles = [item.participant_role for item in self.party_batches]
        if set(roles) != {"USER", "MERCHANT"} or len(roles) != len(set(roles)):
            raise ValueError("party_batches must contain USER and MERCHANT once")
        ids = [item.evidence_id for batch in self.party_batches for item in batch.evidence]
        if len(ids) != len(set(ids)):
            raise ValueError("new evidence ids must be unique across the batch")
        requests_by_id = {item.request_id: item for item in self.requests}
        if len(requests_by_id) != len(self.requests):
            raise ValueError("hearing evidence request ids must be unique")
        for batch in self.party_batches:
            unknown = set(batch.request_ids) - set(requests_by_id)
            if unknown:
                raise ValueError(f"batch references unknown request ids: {sorted(unknown)}")
            wrong_role = [
                request_id
                for request_id in batch.request_ids
                if batch.participant_role not in requests_by_id[request_id].target_roles
            ]
            if wrong_role:
                raise ValueError(
                    f"batch references requests for another party: {sorted(wrong_role)}"
                )
        if (
            self.prior_fact_evidence_matrix is not None
            and self.prior_fact_evidence_matrix.matrix_status != "FROZEN"
        ):
            raise ValueError("prior hearing evidence matrix must be frozen")
        return self


class HearingEvidenceSynthesisResult(StrictModel):
    schema_version: Literal["hearing_evidence_synthesis.v1"] = "hearing_evidence_synthesis.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    fact_evidence_matrix: FactEvidenceMatrixV2
    evidence_summary: dict[str, Any] = Field(default_factory=dict)
    evidence_gaps: Annotated[list[ShortText], Field(max_length=100)] = Field(default_factory=list)
    public_message: LongText


class HearingAnswerItemV1(StrictModel):
    question_id: Identifier
    answer_text: LongText
    attachment_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )


class HearingAnswerBundleV1(StrictModel):
    schema_version: Literal["hearing_answer_bundle.v1"]
    question_set_id: Identifier
    participant_id: Identifier | None = None
    participant_role: PartyRole
    submission_status: Literal["SUBMITTED", "AUTO_TIMEOUT"]
    submitted_at: ShortText | None = None
    answers: Annotated[list[HearingAnswerItemV1], Field(max_length=5)] = Field(
        default_factory=list
    )
    source_message_ids: Annotated[list[Identifier], Field(max_length=100)] = Field(
        default_factory=list
    )

    @model_validator(mode="after")
    def validate_terminal_answer(self) -> "HearingAnswerBundleV1":
        question_ids = [answer.question_id for answer in self.answers]
        if len(question_ids) != len(set(question_ids)):
            raise ValueError("answer bundle question_ids must be unique")
        if len(self.source_message_ids) != len(set(self.source_message_ids)):
            raise ValueError("answer bundle source_message_ids must be unique")
        if self.submission_status == "AUTO_TIMEOUT" and self.answers:
            raise ValueError("an automatically timed-out answer bundle must be empty")
        return self


class HearingPartyStatementV1(StrictModel):
    schema_version: Literal["hearing_party_statement.v1"]
    issue_set_id: Identifier
    question_set_id: Identifier
    participant_id: Identifier
    participant_role: PartyRole
    submission_status: Literal["SUBMITTED", "AUTO_TIMEOUT"]
    submitted_at: ShortText
    statement_text: LongText | None
    source_message_ids: Annotated[list[Identifier], Field(max_length=100)] = Field(
        default_factory=list
    )

    @model_validator(mode="after")
    def validate_terminal_statement(self) -> "HearingPartyStatementV1":
        if len(self.source_message_ids) != len(set(self.source_message_ids)):
            raise ValueError("party statement source_message_ids must be unique")
        if self.submission_status == "SUBMITTED" and self.statement_text is None:
            raise ValueError("a submitted party statement requires statement_text")
        if self.submission_status == "AUTO_TIMEOUT" and self.statement_text is not None:
            raise ValueError("an automatically timed-out party statement cannot contain text")
        return self


HearingTrialAnswerSubmission = Annotated[
    HearingAnswerBundleV1 | HearingPartyStatementV1,
    Field(discriminator="schema_version"),
]


class HearingPolicyRuleSnapshot(StrictModel):
    policy_id: Identifier
    rule_code: Identifier
    rule_version: Annotated[int, Field(ge=1)]
    rule_name: ShortText
    rule_scope: ShortText
    rule_status: Literal["ACTIVE"] = "ACTIVE"
    effective_from: ShortText
    effective_to: ShortText | None = None
    priority: int
    conditions: dict[str, Any]
    outcome: dict[str, Any]
    source_document: dict[str, Any]


class TrialDossierV1(StrictModel):
    schema_version: Literal["trial_dossier.v1"] = "trial_dossier.v1"
    trial_dossier_id: Identifier
    case_id: CaseId
    frozen_at: ShortText
    case_matrix_version: Annotated[int, Field(ge=1)]
    case_matrix_hash: ContentHash
    case_fact_matrix: CaseFactMatrixV2
    evidence_matrix_version: Annotated[int, Field(ge=1)]
    evidence_matrix_hash: ContentHash
    fact_evidence_matrix: FactEvidenceMatrixV2
    question_set_id: Identifier
    question_set: dict[str, Any]
    answer_bundles: Annotated[
        list[HearingTrialAnswerSubmission], Field(min_length=2, max_length=2)
    ]
    request_set_id: Identifier
    evidence_request_set: dict[str, Any]
    evidence_batches: Annotated[list[dict[str, Any]], Field(min_length=2, max_length=2)]
    policy_rules: Annotated[list[HearingPolicyRuleSnapshot], Field(min_length=1, max_length=100)]
    content_hash: ContentHash

    @model_validator(mode="after")
    def validate_dossier(self) -> "TrialDossierV1":
        if self.case_id != self.case_fact_matrix.case_id:
            raise ValueError("trial dossier case matrix belongs to another case")
        if (
            self.case_matrix_version != self.case_fact_matrix.matrix_version
            or self.case_matrix_hash != self.case_fact_matrix.content_hash
        ):
            raise ValueError("trial dossier case matrix version/hash binding is invalid")
        if self.case_fact_matrix.content_hash != content_hash(
            self.case_fact_matrix, hash_field="content_hash"
        ):
            raise ValueError("trial dossier case matrix content hash is invalid")
        evidence = self.fact_evidence_matrix
        if evidence.case_id != self.case_id or evidence.matrix_status != "FROZEN":
            raise ValueError("trial dossier requires the frozen evidence matrix")
        if (
            self.evidence_matrix_version != evidence.matrix_version
            or self.evidence_matrix_hash != evidence.content_hash
        ):
            raise ValueError("trial dossier evidence matrix version/hash binding is invalid")
        if (
            evidence.case_fact_matrix_id != self.case_fact_matrix.matrix_id
            or evidence.case_fact_matrix_version != self.case_fact_matrix.matrix_version
            or evidence.case_fact_matrix_hash != self.case_fact_matrix.content_hash
        ):
            raise ValueError("trial dossier matrices are not bound to each other")
        question_set = self.question_set
        question_parent = self.case_fact_matrix.parent_ref
        if (
            question_parent is None
            or question_parent.matrix_version + 1 != self.case_matrix_version
            or question_set.get("schema_version") != "hearing_question_set.v1"
            or question_set.get("question_set_id") != self.question_set_id
            or question_set.get("case_id") != self.case_id
            or question_set.get("case_matrix_version") != question_parent.matrix_version
            or question_set.get("case_matrix_hash") != question_parent.content_hash
        ):
            raise ValueError("trial dossier question set binding is invalid")
        expected_issue_set_id = question_set.get("issue_set_id") or self.question_set_id
        answer_roles: set[str] = set()
        participant_ids: list[str] = []
        for value in self.answer_bundles:
            if value.question_set_id != self.question_set_id:
                raise ValueError("trial dossier answer submission question set binding is invalid")
            if (
                isinstance(value, HearingPartyStatementV1)
                and value.issue_set_id != expected_issue_set_id
            ):
                raise ValueError("trial dossier party statement issue set binding is invalid")
            answer_roles.add(value.participant_role)
            if value.participant_id is not None:
                participant_ids.append(value.participant_id)
        if answer_roles != {"USER", "MERCHANT"}:
            raise ValueError(
                "trial dossier requires bound USER and MERCHANT answer submissions"
            )
        if len(participant_ids) != len(set(participant_ids)):
            raise ValueError("trial dossier answer participant_ids must be distinct")
        request_set = self.evidence_request_set
        if (
            request_set.get("schema_version") != "hearing_evidence_request_set.v1"
            or request_set.get("request_set_id") != self.request_set_id
            or request_set.get("case_matrix_version") != self.case_matrix_version
            or request_set.get("case_matrix_hash") != self.case_matrix_hash
        ):
            raise ValueError("trial dossier evidence request set binding is invalid")
        batch_roles = {
            value.get("participant_role")
            for value in self.evidence_batches
            if value.get("schema_version") == "hearing_evidence_batch.v1"
            and value.get("request_set_id") == self.request_set_id
        }
        if batch_roles != {"USER", "MERCHANT"}:
            raise ValueError("trial dossier requires bound USER and MERCHANT evidence batches")
        policy_refs = [(item.rule_code, item.rule_version) for item in self.policy_rules]
        if len(policy_refs) != len(set(policy_refs)):
            raise ValueError("trial dossier policy rules must be unique by rule version")
        hash_payload = self.model_dump(mode="json")
        hash_payload["answer_bundles"] = [
            value.model_dump(mode="json", exclude_unset=True)
            for value in self.answer_bundles
        ]
        if self.content_hash != content_hash(hash_payload, hash_field="content_hash"):
            raise ValueError("trial_dossier.v1 content hash is invalid")
        return self


class JudgeV1Draft(StrictModel):
    proposal_text: LongText
    recommended_decision: ShortText
    reasoning_summary: LongText
    review_focus: Annotated[list[ShortText], Field(min_length=1, max_length=30)]
    public_message: LongText


class HearingJudgeV1Request(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.JUDGE_V1]
    trial_dossier: TrialDossierV1


class HearingJudgeV1Result(StrictModel):
    schema_version: Literal["hearing_judge_v1.v1"] = "hearing_judge_v1.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    trial_dossier_id: Identifier
    trial_dossier_hash: ContentHash
    proposal_id: Identifier
    proposal_hash: ContentHash
    proposal_text: LongText
    recommended_decision: ShortText
    reasoning_summary: LongText
    review_focus: Annotated[list[ShortText], Field(min_length=1, max_length=30)]
    public_message: LongText
    is_final_decision: Literal[False] = False

    @model_validator(mode="after")
    def validate_proposal_hash(self) -> "HearingJudgeV1Result":
        if self.proposal_hash != content_hash(self, hash_field="proposal_hash"):
            raise ValueError("judge V1 proposal hash is invalid")
        return self


class HearingJuryFinding(StrictModel):
    dimension: Literal[
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    ]
    severity: Literal["NONE", "LOW", "MEDIUM", "HIGH", "BLOCKER"]
    assessment: LongText
    basis: Annotated[list[ShortText], Field(min_length=1, max_length=10)]
    requires_revision: bool = False


class HearingJuryReviewLlmOutput(StrictModel):
    findings: Annotated[list[HearingJuryFinding], Field(min_length=1, max_length=12)]
    mandatory_revisions: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )
    public_message: LongText


class HearingJuryReviewDraft(StrictModel):
    findings: Annotated[list[HearingJuryFinding], Field(min_length=6, max_length=6)]
    mandatory_revisions: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )
    public_message: LongText

    @model_validator(mode="after")
    def cover_all_dimensions(self) -> "HearingJuryReviewDraft":
        dimensions = [item.dimension for item in self.findings]
        if len(dimensions) != len(set(dimensions)):
            raise ValueError("jury findings must cover each dimension once")
        required = set(HearingJuryFinding.model_fields["dimension"].annotation.__args__)
        if set(dimensions) != required:
            raise ValueError("jury review must cover all six dimensions")
        if (
            any(
                item.requires_revision or item.severity in {"HIGH", "BLOCKER"}
                for item in self.findings
            )
            and not self.mandatory_revisions
        ):
            raise ValueError("revision findings require mandatory revisions")
        return self


class HearingJuryReviewRequest(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.JURY_REVIEW]
    trial_dossier: TrialDossierV1
    judge_v1: HearingJudgeV1Result


class HearingJuryReviewResult(StrictModel):
    schema_version: Literal["hearing_jury_review.v1"] = "hearing_jury_review.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    trial_dossier_id: Identifier
    trial_dossier_hash: ContentHash
    review_id: Identifier
    review_hash: ContentHash
    reviewed_proposal_id: Identifier
    reviewed_proposal_hash: ContentHash
    findings: Annotated[list[HearingJuryFinding], Field(min_length=6, max_length=6)]
    mandatory_revisions: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )
    public_message: LongText
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False

    @model_validator(mode="after")
    def validate_review_hash(self) -> "HearingJuryReviewResult":
        if self.review_hash != content_hash(self, hash_field="review_hash"):
            raise ValueError("jury review hash is invalid")
        return self


class HearingJudgeFactFinding(StrictModel):
    fact_id: FactId
    finding: LongText
    evidence_ids: Annotated[list[Identifier], Field(max_length=100)] = Field(default_factory=list)
    evidence_gap: ShortText | None = None
    confidence: Confidence

    @model_validator(mode="after")
    def unique_evidence_ids(self) -> "HearingJudgeFactFinding":
        if len(self.evidence_ids) != len(set(self.evidence_ids)):
            raise ValueError("fact finding evidence_ids must be unique")
        if not self.evidence_ids and self.evidence_gap is None:
            raise ValueError("fact finding without evidence requires an evidence_gap")
        return self


class HearingJudgeEvidenceAssessment(StrictModel):
    assessment_type: Literal["EVIDENCE", "EVIDENCE_GAP"]
    evidence_id: Identifier | None = None
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=100)]
    assessment: LongText
    weight: Literal["NONE", "LOW", "MEDIUM", "HIGH"]
    confidence: Confidence
    limitations: Annotated[list[ShortText], Field(max_length=20)] = Field(default_factory=list)

    @model_validator(mode="after")
    def unique_fact_ids(self) -> "HearingJudgeEvidenceAssessment":
        if len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("evidence assessment fact_ids must be unique")
        if self.assessment_type == "EVIDENCE" and (
            self.evidence_id is None or self.weight == "NONE"
        ):
            raise ValueError("an EVIDENCE assessment requires evidence_id and a non-NONE weight")
        if self.assessment_type == "EVIDENCE_GAP" and (
            self.evidence_id is not None or self.weight != "NONE"
        ):
            raise ValueError("an EVIDENCE_GAP assessment requires null evidence_id and NONE weight")
        return self


class HearingJudgePolicyApplication(StrictModel):
    rule_code: Identifier
    rule_version: Annotated[int, Field(ge=1)]
    rule_name: ShortText
    fact_ids: Annotated[list[FactId], Field(min_length=1, max_length=100)]
    applicable: bool
    rationale: LongText
    limitations: Annotated[list[ShortText], Field(max_length=20)] = Field(default_factory=list)

    @model_validator(mode="after")
    def unique_fact_ids(self) -> "HearingJudgePolicyApplication":
        if len(self.fact_ids) != len(set(self.fact_ids)):
            raise ValueError("policy application fact_ids must be unique")
        return self


class HearingJudgeV2DraftContent(StrictModel):
    recommended_decision: ShortText
    confidence: Confidence
    draft_text: LongText
    fact_findings: Annotated[list[HearingJudgeFactFinding], Field(min_length=1, max_length=200)]
    evidence_assessment: Annotated[
        list[HearingJudgeEvidenceAssessment], Field(min_length=1, max_length=200)
    ]
    policy_application: Annotated[
        list[HearingJudgePolicyApplication], Field(min_length=1, max_length=100)
    ]
    reviewer_attention: Annotated[list[ShortText], Field(min_length=1, max_length=100)]
    draft_status: Literal["PENDING_HUMAN_REVIEW"] = "PENDING_HUMAN_REVIEW"
    requires_human_review: Literal[True] = True
    is_final_decision: Literal[False] = False

    @model_validator(mode="after")
    def unique_fact_findings(self) -> "HearingJudgeV2DraftContent":
        fact_ids = [item.fact_id for item in self.fact_findings]
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("judge V2 fact_findings must be unique by fact_id")
        evidence_keys = [
            (
                item.assessment_type,
                item.evidence_id or "\u001f".join(sorted(item.fact_ids)),
            )
            for item in self.evidence_assessment
        ]
        if len(evidence_keys) != len(set(evidence_keys)):
            raise ValueError("judge V2 evidence_assessment entries must be unique")
        policy_refs = [
            (item.rule_code, item.rule_version) for item in self.policy_application
        ]
        if len(policy_refs) != len(set(policy_refs)):
            raise ValueError("judge V2 policy_application must be unique by rule version")
        return self


class HearingJudgeV2Draft(StrictModel):
    draft: HearingJudgeV2DraftContent
    public_message: LongText

    @model_validator(mode="after")
    def displayed_text_is_persisted_text(self) -> "HearingJudgeV2Draft":
        if self.public_message != self.draft.draft_text:
            raise ValueError("public_message must exactly equal draft.draft_text")
        return self


class HearingJudgeV2Request(HearingFlowRequest):
    stage_code: Literal[HearingFlowStageCode.JUDGE_V2]
    trial_dossier: TrialDossierV1
    judge_v1: HearingJudgeV1Result
    jury_review: HearingJuryReviewResult


class HearingJudgeV2Result(StrictModel):
    schema_version: Literal["hearing_judge_v2.v1"] = "hearing_judge_v2.v1"
    case_id: CaseId
    workflow_id: Identifier
    stage_sequence: Annotated[int, Field(ge=1)]
    trial_dossier_id: Identifier
    trial_dossier_hash: ContentHash
    judge_v2_id: Identifier
    judge_v2_hash: ContentHash
    parent_proposal_id: Identifier
    parent_proposal_hash: ContentHash
    jury_review_id: Identifier
    jury_review_hash: ContentHash
    draft: HearingJudgeV2DraftContent
    public_message: LongText

    @model_validator(mode="after")
    def validate_v2(self) -> "HearingJudgeV2Result":
        if self.public_message != self.draft.draft_text:
            raise ValueError("persisted V2 text must exactly equal displayed text")
        if self.judge_v2_hash != content_hash(self, hash_field="judge_v2_hash"):
            raise ValueError("judge V2 hash is invalid")
        return self


def content_hash(value: StrictModel | dict[str, Any], *, hash_field: str) -> str:
    payload = value.model_dump(mode="json") if isinstance(value, StrictModel) else dict(value)
    payload.pop(hash_field, None)
    return hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
