from __future__ import annotations

import pytest

from app.agents.critics import CriticAgent, build_default_critics
from app.agents.deliberation_panel import DeliberationPanel
from app.agents.dispute_intake_officer import DisputeIntakeOfficer
from app.agents.evaluation_agent import EvaluationAgent
from app.agents.evidence_clerk import EvidenceClerk
from app.agents.presiding_judge import PresidingJudge
from app.agents.profiles import final_agent_profiles
from app.agents.review_copilot import ReviewCopilot
from app.harness.guardrails import GuardrailViolation
from app.harness.validation import CitationValidationError
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    CriticDraft,
    CriticSeverity,
    CriticType,
    DeliberationRequest,
    DossierEvidenceItem,
    DisputeIntakeRequest,
    EvaluationAnalysisResult,
    EvaluationFinding,
    EvaluationMetricScores,
    EvidenceBuildRequest,
    FrozenDeliberationInput,
    HearingAnalysisResult,
    HearingStageRequest,
    HearingStageResult,
    ReviewCopilotAnswer,
    ReviewCopilotRequest,
    ReviewStatement,
)


def test_final_profiles_are_default_deny_and_cannot_approve_or_execute() -> None:
    profiles = final_agent_profiles()

    assert set(profiles) == {
        "dispute_intake_officer",
        "evidence_clerk",
        "presiding_judge",
        "evidence_critic",
        "rule_critic",
        "risk_critic",
        "remedy_critic",
        "fairness_critic",
        "review_copilot",
        "evaluation_agent",
    }
    for profile in profiles.values():
        assert not profile.authorizes_tool("review.approve")
        assert not profile.authorizes_tool("refund.execute")
        assert not profile.authorizes_tool("case.close")


def test_dispute_intake_officer_reuses_intake_analysis_without_deciding() -> None:
    class IntakeWorkflow:
        def analyze(self, request, _context):
            from app.schemas import IntakeAnalysisOutput

            return IntakeAnalysisOutput(
                case_type="DISPUTE",
                dispute_type="SIGNED_NOT_RECEIVED",
                risk_level="HIGH",
                potential_dispute=True,
                missing_slots=[],
                title="Signed but not received",
                normalized_description=request.description,
            )

    officer = DisputeIntakeOfficer(IntakeWorkflow())
    result = officer.analyze(
        DisputeIntakeRequest(
            submission_id="SUBMISSION_1",
            initiator_role="USER",
            raw_text=(
                "The parcel is marked delivered but was not received. "
                "I request a refund."
            ),
            order_reference="ORDER_1",
            channel="WEB",
        ),
        object(),
        case_state="SUBMITTED",
    )

    assert result.is_potential_dispute is True
    assert result.admissibility_recommendation == "ACCEPTED"
    assert result.initiator == "USER"
    assert result.claims[0].source_ref == "SUBMISSION_1"
    assert result.requested_remedy == "REFUND"
    assert result.liability_determined is False

    class NonDisputeWorkflow:
        def analyze(self, request, _context):
            from app.schemas import IntakeAnalysisOutput

            return IntakeAnalysisOutput(
                case_type="INQUIRY",
                risk_level="LOW",
                potential_dispute=False,
                missing_slots=[],
                title="Order status inquiry",
                normalized_description=request.description,
            )

    transferred = DisputeIntakeOfficer(NonDisputeWorkflow()).analyze(
        DisputeIntakeRequest(
            submission_id="SUBMISSION_2",
            initiator_role="USER",
            raw_text="Where can I see the tracking page?",
            order_reference="ORDER_2",
            channel="WEB",
        ),
        object(),
        case_state="SUBMITTED",
    )
    assert transferred.admissibility_recommendation == "TRANSFERRED"
    assert transferred.next_step == "TRANSFER"

    with pytest.raises(PermissionError):
        officer.analyze(
            DisputeIntakeRequest(
                submission_id="SUBMISSION_1",
                initiator_role="USER",
                raw_text="The parcel was not received.",
                order_reference="ORDER_1",
                channel="WEB",
            ),
            object(),
            case_state="CLOSED",
        )


def test_evidence_clerk_versions_and_preserves_evidence_without_deciding() -> None:
    clerk = EvidenceClerk()
    request = EvidenceBuildRequest(
        case_id="CASE_evidence",
        case_version=2,
        submission_version=3,
        current_dossier_version=4,
        party_claims=[
            {
                "claim_id": "CLAIM_receipt",
                "party_type": "USER",
                "statement": "The parcel was not received.",
            },
            {
                "claim_id": "CLAIM_damage",
                "party_type": "USER",
                "statement": "The parcel was damaged.",
            },
        ],
        evidence=[
            DossierEvidenceItem(
                evidence_id="EVIDENCE_tracking",
                evidence_type="LOGISTICS",
                source_type="PLATFORM",
                content="Carrier scan says delivered at 10:30.",
                parsed_text="Delivered scan: 10:30",
                agent_summary="Carrier recorded a delivery scan.",
                occurred_at="2026-07-01T10:30:00+08:00",
                related_claim_ids=["CLAIM_receipt"],
            ),
            DossierEvidenceItem(
                evidence_id="EVIDENCE_tracking_copy",
                evidence_type="LOGISTICS",
                source_type="PLATFORM",
                content="Carrier scan says delivered at 10:30.",
                related_claim_ids=["CLAIM_receipt"],
            ),
            DossierEvidenceItem(
                evidence_id="EVIDENCE_statement",
                evidence_type="PARTY_STATEMENT",
                source_type="USER",
                content="The parcel was not received.",
                related_claim_ids=["CLAIM_receipt"],
                parser_warning="Image quality prevented OCR verification.",
            ),
            DossierEvidenceItem(
                evidence_id="EVIDENCE_tracking_conflict",
                evidence_type="LOGISTICS",
                source_type="PLATFORM",
                content="Carrier later marked the delivery scan as cancelled.",
                related_claim_ids=["CLAIM_receipt"],
            ),
        ],
    )

    result = clerk.build(request)

    assert result.dossier_version == 5
    assert [item.original_ref for item in result.evidence_catalog] == [
        "EVIDENCE_tracking",
        "EVIDENCE_tracking_copy",
        "EVIDENCE_statement",
        "EVIDENCE_tracking_conflict",
    ]
    assert result.source_citations == [
        "EVIDENCE_tracking",
        "EVIDENCE_tracking_copy",
        "EVIDENCE_statement",
        "EVIDENCE_tracking_conflict",
    ]
    assert result.timeline[0].source_refs == ["EVIDENCE_tracking"]
    assert result.duplicate_groups == [
        ["EVIDENCE_tracking", "EVIDENCE_tracking_copy"]
    ]
    assert result.parser_warnings == [
        "EVIDENCE_statement: Image quality prevented OCR verification."
    ]
    assert result.claim_issue_evidence_matrix[0].evidence_refs == [
        "EVIDENCE_tracking",
        "EVIDENCE_tracking_copy",
        "EVIDENCE_statement",
        "EVIDENCE_tracking_conflict",
    ]
    assert result.conflicts == [
        "LOGISTICS evidence contains distinct source assertions"
    ]
    assert result.gaps == ["No evidence linked to claim CLAIM_damage"]
    assert result.liability_determined is False
    assert result.remedy_recommended is False


def _frozen_input() -> FrozenDeliberationInput:
    return FrozenDeliberationInput(
        case_id="CASE_panel",
        case_snapshot_version=7,
        dossier_version=4,
        adjudication_draft_version=2,
        rule_version="RULE_2026_01",
        remedy_plan_candidate_version=1,
        payload={"recommended_outcome": "REQUEST_MORE_EVIDENCE"},
    )


def test_all_critics_receive_the_same_frozen_input_and_blocker_is_preserved() -> None:
    fingerprints: list[str] = []

    def evaluator(
        critic_type: CriticType,
        frozen_input: FrozenDeliberationInput,
        fingerprint: str,
    ) -> CriticDraft:
        fingerprints.append(fingerprint)
        severity = (
            CriticSeverity.BLOCKER
            if critic_type is CriticType.EVIDENCE
            else CriticSeverity.NONE
        )
        return CriticDraft(
            severity=severity,
            findings=(
                ["The delivery conflict remains unresolved."]
                if severity is CriticSeverity.BLOCKER
                else []
            ),
            blocking_issues=(
                ["EVIDENCE_CONFLICT"]
                if severity is CriticSeverity.BLOCKER
                else []
            ),
            recommended_revision=(
                "REQUEST_MORE_EVIDENCE"
                if severity is CriticSeverity.BLOCKER
                else None
            ),
        )

    critics = build_default_critics(evaluator)
    report = DeliberationPanel(critics).run(
        DeliberationRequest(frozen_input=_frozen_input())
    )

    assert len(set(fingerprints)) == 1
    assert report.panel_result == "REVISION_REQUIRED"
    assert report.revision_required is True
    assert report.major_risks == ["EVIDENCE_CONFLICT"]
    assert report.critic_reports[0].severity is CriticSeverity.BLOCKER
    assert report.approval_performed is False
    assert report.execution_triggered is False


def test_failed_or_timed_out_critic_requires_human_review() -> None:
    frozen_input = _frozen_input()

    def evaluator(
        critic_type: CriticType,
        _frozen_input: FrozenDeliberationInput,
        _fingerprint: str,
    ) -> CriticDraft:
        if critic_type is CriticType.RULE:
            raise TimeoutError("critic deadline exceeded")
        return CriticDraft(severity=CriticSeverity.NONE)

    report = DeliberationPanel(build_default_critics(evaluator)).run(
        DeliberationRequest(frozen_input=frozen_input)
    )

    rule_report = next(
        item for item in report.critic_reports if item.critic is CriticType.RULE
    )
    assert rule_report.status == "TIMED_OUT"
    assert report.panel_result == "MANUAL_REVIEW_REQUIRED"
    assert "RULE_CRITIC_UNAVAILABLE" in report.major_risks


def test_critic_rejects_output_for_another_frozen_snapshot() -> None:
    def wrong_snapshot(
        _critic_type: CriticType,
        _frozen_input: FrozenDeliberationInput,
        _fingerprint: str,
    ) -> CriticDraft:
        return CriticDraft(
            severity=CriticSeverity.NONE,
            frozen_input_fingerprint="wrong",
        )

    critic = CriticAgent(CriticType.EVIDENCE, wrong_snapshot)
    report = critic.review(_frozen_input())

    assert report.status == "FAILED"
    assert report.blocking_issues == ["FROZEN_INPUT_MISMATCH"]


def test_review_copilot_validates_frozen_refs_and_cannot_issue_a_decision() -> None:
    safe_answer = ReviewCopilotAnswer(
        answer="The draft relies on the carrier scan, while receipt remains disputed.",
        fact_refs=["EVIDENCE_tracking"],
        rule_refs=["RULE_2026_01"],
        draft_refs=["DRAFT_2"],
        deliberation_refs=["DELIBERATION_1"],
        uncertainties=["Actual receipt is unresolved."],
        suggested_review_focus=["Check proof of handover."],
        statements=[
            ReviewStatement(
                kind="FACT",
                text="The carrier recorded a delivery scan.",
                refs=["EVIDENCE_tracking"],
            ),
            ReviewStatement(
                kind="INFERENCE",
                text="The scan alone may not prove handover.",
                refs=["EVIDENCE_tracking", "RULE_2026_01"],
            ),
            ReviewStatement(
                kind="SUGGESTION",
                text="The reviewer can inspect proof of handover.",
                refs=["DRAFT_2"],
            ),
        ],
    )
    copilot = ReviewCopilot(lambda _request: safe_answer)
    request = ReviewCopilotRequest(
        review_id="REVIEW_1",
        case_id="CASE_review",
        review_packet_version=2,
        reviewer_role="PLATFORM_REVIEWER",
        question="Why is more evidence recommended?",
        available_fact_refs=["EVIDENCE_tracking"],
        available_rule_refs=["RULE_2026_01"],
        available_draft_refs=["DRAFT_2"],
        available_deliberation_refs=["DELIBERATION_1"],
    )

    answer = copilot.query(request)

    assert answer.approval_performed is False
    assert answer.execution_triggered is False
    assert [item.kind for item in answer.statements] == [
        "FACT",
        "INFERENCE",
        "SUGGESTION",
    ]

    bad_refs = safe_answer.model_copy(
        update={"fact_refs": ["EVIDENCE_not_in_packet"]}
    )
    with pytest.raises(CitationValidationError):
        ReviewCopilot(lambda _request: bad_refs).query(request)

    unsafe = safe_answer.model_copy(
        update={"answer": "This is the final decision: refund immediately."}
    )
    with pytest.raises(GuardrailViolation):
        ReviewCopilot(lambda _request: unsafe).query(request)


class _FakeHearingWorkflow:
    def analyze(self, request, _context):
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="COMPLETED",
            executed_nodes=["issue_framing_node", "adjudication_draft_node"],
            adjudication_draft=AdjudicationDraftOutput(
                draft=AdjudicationDraft(
                    recommended_outcome="REQUEST_MORE_EVIDENCE",
                    reasoning_summary="Receipt remains unresolved.",
                    issue_findings=[],
                    confidence=0.7,
                    risk_level="HIGH",
                    review_focus=["Check handover evidence."],
                )
            ),
            prompt_version="hearing-v1",
            model="test-model",
        )


def test_presiding_judge_only_runs_in_hearing_states_and_stays_non_final() -> None:
    judge = PresidingJudge(_FakeHearingWorkflow())
    request = type(
        "Request",
        (),
        {"case_id": "CASE_judge", "workflow_id": "WORKFLOW_judge"},
    )()

    with pytest.raises(PermissionError):
        judge.analyze(request, object(), case_state="SIMPLE_HEARING")

    result = judge.analyze(request, object(), case_state="FULL_HEARING")
    assert result.adjudication_draft.draft.is_final_decision is False
    assert result.adjudication_draft.draft.requires_human_review is True


def test_presiding_judge_runs_one_explicit_c1_c6_stage() -> None:
    class StageWorkflow(_FakeHearingWorkflow):
        def run_stage(self, request, _context):
            return HearingStageResult(
                case_id=request.case_id,
                workflow_id=request.workflow_id,
                stage=request.stage,
                dossier_version=request.dossier_version,
                output={"neutral_summary": "Receipt is disputed.", "issues": []},
                output_schema="IssueFramingOutput",
                prompt_version="hearing-v1",
                model="test-model",
            )

    judge = PresidingJudge(StageWorkflow())
    request = HearingStageRequest(
        case_id="CASE_stage",
        workflow_id="WORKFLOW_stage",
        stage="C1_ISSUE_FRAMING",
        dossier_version=1,
        claims=[
            {
                "claim_id": "CLAIM_stage",
                "party_type": "USER",
                "statement": "Parcel not received.",
            }
        ],
    )

    result = judge.run_stage(
        request,
        object(),
        case_state="FULL_HEARING",
    )

    assert result.stage == "C1_ISSUE_FRAMING"
    assert result.non_final is True
    assert result.requires_human_review is True


class _FakeEvaluationWorkflow:
    def analyze(self, request, _context):
        return EvaluationAnalysisResult(
            case_id=request.case_id,
            evaluation_status="COMPLETED",
            metric_scores=EvaluationMetricScores(
                draft_approval_rate=1,
                reviewer_modification_rate=0,
                evidence_quality_score=0.8,
                policy_coverage_score=0.8,
                execution_quality_score=0.8,
                process_quality_score=0.8,
                overall_quality_score=0.8,
            ),
            findings=[
                EvaluationFinding(
                    category="PROCESS_GAP",
                    severity="LOW",
                    summary="Test finding.",
                    supporting_references=[],
                )
            ],
            rule_gap_suggestions=[],
            improvement_suggestions=[],
            evaluator_model="test-model",
            prompt_version="evaluation-v1",
            latency_ms=1,
            token_usage=1,
        )


def test_evaluation_agent_is_closed_case_offline_only() -> None:
    agent = EvaluationAgent(_FakeEvaluationWorkflow())
    closed_request = type(
        "Request",
        (),
        {"case_id": "CASE_closed", "case_status": "CLOSED"},
    )()

    with pytest.raises(PermissionError):
        agent.analyze(closed_request, object(), offline=False)

    online_request = type(
        "Request",
        (),
        {"case_id": "CASE_open", "case_status": "IN_REVIEW"},
    )()
    with pytest.raises(PermissionError):
        agent.analyze(online_request, object(), offline=True)

    result = agent.analyze(closed_request, object(), offline=True)
    assert result.online_case_mutated is False
    assert result.automatic_changes_applied is False
