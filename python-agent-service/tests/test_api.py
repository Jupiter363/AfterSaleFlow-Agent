from fastapi.testclient import TestClient

from app.config import Settings
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    HearingAnalysisResult,
    EvaluationAnalysisResult,
    EvaluationFinding,
    EvaluationMetricScores,
    IntakeAnalysisOutput,
)
from app.main import create_app


class FakeWorkflow:
    def __init__(self) -> None:
        self.context = None

    def analyze(self, request, context):
        self.context = context
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="MANUAL_REVIEW_REQUIRED",
            executed_nodes=[],
            adjudication_draft=AdjudicationDraftOutput(
                draft=AdjudicationDraft(
                    recommended_outcome="UNDETERMINED",
                    reasoning_summary="Human analysis is required.",
                    issue_findings=[],
                    confidence=0,
                    risk_level="HIGH",
                    review_focus=["Review case"],
                )
            ),
            manual_review_reasons=["TEST"],
            prompt_version="hearing-v1",
            model="test-model",
        )


class FakeIntakeWorkflow:
    def analyze(self, request, context):
        return IntakeAnalysisOutput(
            case_type="DISPUTE",
            dispute_type="NON_RECEIPT",
            risk_level="HIGH",
            potential_dispute=True,
            missing_slots=[],
            title="Delivery dispute",
            normalized_description=request.description,
        )


class FakeEvaluationWorkflow:
    def analyze(self, request, context):
        return EvaluationAnalysisResult(
            case_id=request.case_id,
            evaluation_status="COMPLETED",
            metric_scores=EvaluationMetricScores(
                draft_approval_rate=1.0,
                reviewer_modification_rate=0.0,
                evidence_quality_score=0.8,
                policy_coverage_score=0.7,
                execution_quality_score=1.0,
                process_quality_score=0.9,
                overall_quality_score=0.88,
            ),
            findings=[
                EvaluationFinding(
                    category="POLICY_GAP",
                    severity="LOW",
                    summary="Policy could include another example.",
                    supporting_references=[],
                )
            ],
            rule_gap_suggestions=["Add an example."],
            improvement_suggestions=["Keep evidence structured."],
            automatic_changes_applied=False,
            online_case_mutated=False,
            evaluator_model="test-evaluation-model",
            prompt_version="evaluation-v1",
            latency_ms=5,
            token_usage=12,
        )


def settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


def request_payload() -> dict:
    return {
        "case_id": "CASE_api",
        "workflow_id": "WORKFLOW_api",
        "user_id": "USER_api",
        "claims": [
            {
                "claim_id": "CLAIM_api",
                "party_type": "USER",
                "statement": "The parcel was not received.",
            }
        ],
        "evidence": [],
        "policy_candidates": [],
    }


def test_hearing_api_requires_secret_and_propagates_correlation_ids() -> None:
    workflow = FakeWorkflow()
    client = TestClient(create_app(settings(), workflow))

    unauthorized = client.post(
        "/internal/agents/legacy/hearing/analyze",
        json=request_payload(),
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert unauthorized.status_code == 401

    response = client.post(
        "/internal/agents/legacy/hearing/analyze",
        json=request_payload(),
        headers={
            "X-Service-Secret": "test-agent-service-secret",
            "X-Trace-Id": "TRACE_api",
            "X-Request-Id": "REQ_api",
            "X-Role": "SYSTEM",
        },
    )

    assert response.status_code == 200
    assert response.json()["adjudication_draft"]["draft"]["is_final_decision"] is False
    assert response.headers["X-Trace-Id"] == "TRACE_api"
    assert response.headers["X-Request-Id"] == "REQ_api"
    assert workflow.context.trace_id == "TRACE_api"


def test_intake_api_matches_the_java_client_raw_response_contract() -> None:
    client = TestClient(
        create_app(settings(), FakeWorkflow(), FakeIntakeWorkflow())
    )

    response = client.post(
        "/internal/agents/legacy/intake/analyze",
        json={
            "order_id": "ORDER_api",
            "after_sale_id": None,
            "user_id": "USER_api",
            "merchant_id": "MERCHANT_api",
            "description": "Tracking says delivered but the user did not receive it.",
            "attachment_ids": [],
            "channel": "WEB",
        },
        headers={"X-Service-Secret": "test-agent-service-secret"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "case_type": "DISPUTE",
        "dispute_type": "NON_RECEIPT",
        "risk_level": "HIGH",
        "potential_dispute": True,
        "missing_slots": [],
        "title": "Delivery dispute",
        "normalized_description": (
            "Tracking says delivered but the user did not receive it."
        ),
    }


def test_evaluation_api_only_accepts_authenticated_closed_case_snapshots() -> None:
    client = TestClient(
        create_app(
            settings(),
            FakeWorkflow(),
            FakeIntakeWorkflow(),
            FakeEvaluationWorkflow(),
        )
    )
    payload = {
        "case_id": "CASE_evaluation",
        "case_status": "CLOSED",
        "route_type": "DISPUTE_HEARING",
        "risk_level": "HIGH",
        "approval_decision": "APPROVE",
        "adjudication_draft": {},
        "approved_plan": {},
        "action_records": [],
        "evidence_summary": {},
        "policy_summary": {},
    }

    unauthorized = client.post(
        "/internal/agents/evaluation/analyze",
        json=payload,
        headers={"X-Service-Secret": "wrong-secret"},
    )
    response = client.post(
        "/internal/agents/evaluation/analyze",
        json=payload,
        headers={"X-Service-Secret": "test-agent-service-secret"},
    )

    assert unauthorized.status_code == 401
    assert response.status_code == 200
    assert response.json()["evaluation_status"] == "COMPLETED"
    assert response.json()["online_case_mutated"] is False
