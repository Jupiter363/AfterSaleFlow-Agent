from __future__ import annotations

from fastapi.testclient import TestClient

from app.api.final_agents import FinalAgentServices
from app.config import Settings
from app.main import create_app
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    CriticReport,
    CriticSeverity,
    CriticStatus,
    CriticType,
    DeliberationReport,
    DisputeIntakeResult,
    EvaluationAnalysisResult,
    EvaluationMetricScores,
    EvidenceDossierResult,
    HearingAnalysisResult,
    HearingStageResult,
    IntakeAnalysisOutput,
    ReviewCopilotAnswer,
)


class _LegacyWorkflow:
    def analyze(self, request, _context):
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="COMPLETED",
            executed_nodes=["adjudication_draft_node"],
            adjudication_draft=AdjudicationDraftOutput(
                draft=AdjudicationDraft(
                    recommended_outcome="REQUEST_MORE_EVIDENCE",
                    reasoning_summary="More evidence is needed.",
                    issue_findings=[],
                    confidence=0.7,
                    risk_level="HIGH",
                    review_focus=["Check handover proof."],
                )
            ),
            prompt_version="hearing-v1",
            model="test-model",
        )


class _LegacyIntake:
    def analyze(self, request, _context):
        return IntakeAnalysisOutput(
            case_type="DISPUTE",
            dispute_type="SIGNED_NOT_RECEIVED",
            risk_level="HIGH",
            potential_dispute=True,
            missing_slots=[],
            title="Delivery dispute",
            normalized_description=request.description,
        )


class _LegacyEvaluation:
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
            findings=[],
            rule_gap_suggestions=[],
            improvement_suggestions=[],
            evaluator_model="test-model",
            prompt_version="evaluation-v1",
            latency_ms=1,
            token_usage=1,
        )


class _IntakeAgent:
    def analyze(self, request, context, *, case_state):
        return DisputeIntakeResult(
            admissible=True,
            admission_recommendation="ACCEPTED",
            dispute_type="SIGNED_NOT_RECEIVED",
            initiator_role=request.initiator_role,
            order_reference=request.order_reference,
            after_sales_reference=request.after_sales_reference,
            logistics_reference=request.logistics_reference,
            party_claims=[
                {
                    "party": request.initiator_role,
                    "claim_text": request.raw_text,
                    "source_ref": request.submission_id,
                }
            ],
            requested_outcome="UNKNOWN",
            confidence=0.8,
            next_step="BUILD_DOSSIER",
            room_utterance="The dispute intake recommendation is ready.",
        )


class _EvidenceAgent:
    def build(self, request):
        return EvidenceDossierResult(
            case_id=request.case_id,
            dossier_version=1,
        )


class _JudgeAgent:
    def run_stage(self, request, context, *, case_state):
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


class _PanelAgent:
    def run(self, request):
        fingerprint = "a" * 64
        return DeliberationReport(
            deliberation_id="DELIBERATION_api",
            panel_result="NO_MAJOR_OBJECTION",
            frozen_input_fingerprint=fingerprint,
            critic_reports=[
                CriticReport(
                    critic=critic,
                    scope=critic.name,
                    status=CriticStatus.COMPLETED,
                    severity=CriticSeverity.NONE,
                    frozen_input_fingerprint=fingerprint,
                )
                for critic in CriticType
            ],
            revision_required=False,
        )


class _CopilotAgent:
    def query(self, request):
        return ReviewCopilotAnswer(
            answer="The current packet shows an unresolved evidence gap.",
            statements=[
                {
                    "kind": "FACT",
                    "text": "The current packet contains an evidence gap.",
                    "refs": request.available_fact_refs,
                }
            ],
            fact_refs=request.available_fact_refs,
        )


class _EvaluationAgent:
    def analyze(self, request, context, *, offline):
        return _LegacyEvaluation().analyze(request, context)


def _settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


def _client() -> TestClient:
    legacy_hearing = _LegacyWorkflow()
    legacy_intake = _LegacyIntake()
    legacy_evaluation = _LegacyEvaluation()
    services = FinalAgentServices(
        intake=_IntakeAgent(),
        evidence=_EvidenceAgent(),
        hearing=_JudgeAgent(),
        deliberation=_PanelAgent(),
        review_copilot=_CopilotAgent(),
        evaluation=_EvaluationAgent(),
    )
    return TestClient(
        create_app(
            _settings(),
            legacy_hearing,
            legacy_intake,
            legacy_evaluation,
            final_agent_services=services,
        )
    )


def _headers() -> dict[str, str]:
    return {"X-Service-Secret": "test-agent-service-secret"}


def test_all_final_internal_agent_routes_are_authenticated() -> None:
    client = _client()
    paths = {
        route.path
        for route in client.app.routes
        if route.path.startswith("/internal/agents/")
        and "/legacy/" not in route.path
    }
    assert paths == {
        "/internal/agents/intake/analyze",
        "/internal/agents/intake/turn",
        "/internal/agents/evidence/build",
        "/internal/agents/evidence/turn",
            "/internal/agents/hearing/run-stage",
            "/internal/agents/hearing/round-turn",
            "/internal/agents/deliberation/run",
        "/internal/agents/review-copilot/query",
        "/internal/agents/evaluation/analyze",
        "/internal/agents/external-import/simulate",
    }

    unauthorized = client.post(
        "/internal/agents/evidence/build",
        json={
            "case_id": "CASE_api",
            "case_version": 1,
            "submission_version": 1,
        },
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert unauthorized.status_code == 401


def test_final_internal_agent_routes_return_strict_non_final_outputs() -> None:
    client = _client()
    intake = client.post(
        "/internal/agents/intake/analyze",
        json={
            "submission_id": "SUBMISSION_api",
            "initiator_role": "USER",
            "order_reference": "ORDER_api",
            "raw_text": "Marked delivered but not received.",
            "channel": "WEB",
        },
        headers=_headers(),
    )
    evidence = client.post(
        "/internal/agents/evidence/build",
        json={
            "case_id": "CASE_api",
            "case_version": 1,
            "submission_version": 1,
        },
        headers=_headers(),
    )
    hearing = client.post(
        "/internal/agents/hearing/run-stage",
        json={
            "case_id": "CASE_api",
            "workflow_id": "WORKFLOW_api",
            "stage": "C1_ISSUE_FRAMING",
            "dossier_version": 1,
            "claims": [
                {
                    "claim_id": "CLAIM_api",
                    "party_type": "USER",
                    "statement": "Parcel not received.",
                }
            ],
        },
        headers=_headers(),
    )
    panel = client.post(
        "/internal/agents/deliberation/run",
        json={
            "frozen_input": {
                "case_id": "CASE_api",
                "case_snapshot_version": 1,
                "dossier_version": 1,
                "adjudication_draft_version": 1,
                "rule_version": "RULE_1",
                "frozen_dossier_snapshot": {},
                "frozen_draft_snapshot": {},
                "frozen_at_event_sequence": 1,
            },
            "trigger_reasons": ["HIGH_RISK"],
        },
        headers=_headers(),
    )
    copilot = client.post(
        "/internal/agents/review-copilot/query",
        json={
            "review_id": "REVIEW_api",
            "case_id": "CASE_api",
            "review_packet_version": 1,
            "reviewer_role": "PLATFORM_REVIEWER",
            "question": "Why is more evidence needed?",
            "available_fact_refs": ["EVIDENCE_api"],
        },
        headers=_headers(),
    )
    evaluation = client.post(
        "/internal/agents/evaluation/analyze",
        json={
            "case_id": "CASE_api",
            "case_status": "CLOSED",
            "route_type": "FULL_HEARING",
            "risk_level": "HIGH",
            "approval_decision": "APPROVE",
            "adjudication_draft": {},
            "approved_plan": {},
            "action_records": [],
            "evidence_summary": {},
            "policy_summary": {},
        },
        headers=_headers(),
    )

    assert all(
        response.status_code == 200
        for response in [intake, evidence, hearing, panel, copilot, evaluation]
    )
    assert intake.json()["admission_recommendation"] == "ACCEPTED"
    assert evidence.json()["liability_determined"] is False
    assert hearing.json()["non_final"] is True
    assert panel.json()["approval_performed"] is False
    assert copilot.json()["execution_triggered"] is False
    assert evaluation.json()["online_case_mutated"] is False
