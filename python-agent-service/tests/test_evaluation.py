from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.evaluation import EvaluationWorkflow
from app.llm import StructuredGeneration
from app.prompts import PromptRepository
from app.schemas import (
    EvaluationAgentOutput,
    EvaluationAnalyzeRequest,
    EvaluationFinding,
    EvaluationQualitativeScores,
)
from app.tracing import AgentTraceContext, NoOpAgentTracer


def request(decision: str = "MODIFY_AND_APPROVE") -> EvaluationAnalyzeRequest:
    return EvaluationAnalyzeRequest(
        case_id="CASE_evaluation",
        case_status="CLOSED",
        route_type="DISPUTE_HEARING",
        risk_level="HIGH",
        approval_decision=decision,
        adjudication_draft={"confidence": 0.72},
        approved_plan={"actions": [{"action_type": "REFUND"}]},
        action_records=[{"action_type": "REFUND", "execution_status": "SUCCEEDED"}],
        evidence_summary={"evidence_count": 4},
        policy_summary={"applied_rule_count": 1},
    )


def output() -> EvaluationAgentOutput:
    return EvaluationAgentOutput(
        qualitative_scores=EvaluationQualitativeScores(
            evidence_quality_score=0.78,
            policy_coverage_score=0.66,
            execution_quality_score=1.0,
            process_quality_score=0.82,
            overall_quality_score=0.81,
        ),
        findings=[
            EvaluationFinding(
                category="POLICY_GAP",
                severity="MEDIUM",
                summary="Delivery exception guidance could be more explicit.",
                supporting_references=["RULE_DELIVERY_1"],
            )
        ],
        rule_gap_suggestions=["Clarify carrier exception handling."],
        improvement_suggestions=["Request carrier scan evidence earlier."],
        automatic_changes_applied=False,
        online_case_mutated=False,
    )


class FakeLlm:
    def generate(self, **kwargs):
        assert kwargs["node_name"] == "evaluation_analyze"
        assert kwargs["output_type"] is EvaluationAgentOutput
        return StructuredGeneration(
            value=output(),
            model="evaluation-model",
            latency_ms=17,
            token_usage={"input": 20, "output": 10, "total": 30},
        )


def test_evaluation_schema_rejects_any_online_case_state() -> None:
    payload = request().model_dump()
    payload["case_status"] = "EXECUTING"

    with pytest.raises(ValidationError):
        EvaluationAnalyzeRequest.model_validate(payload)

    unsafe_output = output().model_dump()
    unsafe_output["automatic_changes_applied"] = True
    with pytest.raises(ValidationError):
        EvaluationAgentOutput.model_validate(unsafe_output)


def test_closed_case_evaluation_reports_deterministic_approval_metrics() -> None:
    workflow = EvaluationWorkflow(
        FakeLlm(),
        PromptRepository(),
        NoOpAgentTracer(),
        "evaluation-v1",
    )
    context = AgentTraceContext(
        trace_id="TRACE_evaluation",
        request_id="REQ_evaluation",
        case_id="CASE_evaluation",
        workflow_id="EVALUATION_CASE_evaluation",
        user_id=None,
        role="SYSTEM",
        prompt_version="evaluation-v1",
    )

    modified = workflow.analyze(request(), context)
    approved = workflow.analyze(request("APPROVE"), context)

    assert modified.metric_scores.draft_approval_rate == 1.0
    assert modified.metric_scores.reviewer_modification_rate == 1.0
    assert approved.metric_scores.draft_approval_rate == 1.0
    assert approved.metric_scores.reviewer_modification_rate == 0.0
    assert modified.automatic_changes_applied is False
    assert modified.online_case_mutated is False
    assert modified.evaluator_model == "evaluation-model"
    assert modified.token_usage == 30


def test_evaluation_prompt_is_offline_read_only_and_cannot_mutate_rules() -> None:
    system_prompt, _ = PromptRepository().render(
        "evaluation_analyze",
        request().model_dump(mode="json"),
        EvaluationAgentOutput.model_json_schema(),
    )

    assert "closed case" in system_prompt
    assert "offline" in system_prompt
    assert "Never modify" in system_prompt
    assert "online case" in system_prompt
