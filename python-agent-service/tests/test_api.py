from fastapi.testclient import TestClient

from app.config import Settings
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    HearingAnalysisResult,
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
        "/agent-api/v1/hearings/analyze",
        json=request_payload(),
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert unauthorized.status_code == 401

    response = client.post(
        "/agent-api/v1/hearings/analyze",
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
        "/agent-api/v1/intake/analyze",
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
