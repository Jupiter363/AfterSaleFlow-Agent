from contextlib import contextmanager

import pytest

from app.llm import AgentOutputSchemaError, StructuredGeneration
from app.prompts import PromptRepository
from app.schemas import HearingAnalyzeRequest, HearingStageRequest
from app.tracing import AgentTraceContext
from app.workflow import HearingWorkflow


OUTPUTS = {
    "issue_framing_node": {
        "neutral_summary": "The parties disagree about delivery.",
        "issues": [
            {
                "issue_id": "ISSUE_delivery",
                "title": "Delivery status",
                "neutral_description": "Whether the order was delivered.",
                "related_claim_ids": ["CLAIM_user"],
                "confidence": 0.9,
            }
        ],
    },
    "evidence_gap_request_node": {
        "requires_supplemental_evidence": True,
        "gaps": [
            {
                "gap_id": "GAP_signature",
                "issue_id": "ISSUE_delivery",
                "required_evidence_type": "DELIVERY_SIGNATURE",
                "reason": "A recipient signature is material.",
                "requested_from": "PLATFORM",
            }
        ],
    },
    "party_liaison_node": {
        "messages": [
            {
                "party_type": "PLATFORM",
                "neutral_message": "Please provide the delivery signature.",
                "requested_evidence_types": ["DELIVERY_SIGNATURE"],
            }
        ]
    },
    "evidence_cross_check_node": {
        "findings": [
            {
                "issue_id": "ISSUE_delivery",
                "supported_by": ["EVIDENCE_tracking"],
                "contradicted_by": [],
                "missing_evidence": True,
                "neutral_analysis": "Tracking supports dispatch but not receipt.",
                "confidence": 0.7,
            }
        ],
        "unresolved_conflicts": ["Recipient confirmation remains absent"],
    },
    "rule_application_node": {
        "applications": [
            {
                "issue_id": "ISSUE_delivery",
                "rule_code": "DELIVERY_PROOF",
                "rule_version": 2,
                "applicable": True,
                "rationale": "The supplied rule addresses proof of delivery.",
                "limitations": ["Recipient signature is missing"],
            }
        ],
        "missing_policy": False,
    },
    "adjudication_draft_node": {
        "draft": {
            "recommended_outcome": "Human reviewer should verify delivery proof.",
            "reasoning_summary": "Tracking alone does not establish receipt.",
            "issue_findings": [
                {
                    "issue_id": "ISSUE_delivery",
                    "suggested_finding": "Receipt is not yet established.",
                    "evidence_basis": ["EVIDENCE_tracking"],
                    "policy_basis": ["DELIVERY_PROOF:2"],
                }
            ],
            "confidence": 0.65,
            "risk_level": "MEDIUM",
            "review_focus": ["Verify recipient signature"],
            "requires_human_review": True,
            "is_final_decision": False,
        }
    },
}


class StubLlm:
    def generate(self, *, node_name, system_prompt, user_prompt, output_type):
        return StructuredGeneration(
            value=output_type.model_validate(OUTPUTS[node_name]),
            model="test-model",
            latency_ms=5,
            token_usage={"input": 10, "output": 5, "total": 15},
        )


class RecordingTrace:
    def __init__(self) -> None:
        self.nodes: list[str] = []
        self.prompts: list[str] = []
        self.completed = None

    @contextmanager
    def workflow(self, context, payload):
        yield self

    def complete(self, output):
        self.completed = output

    def generation(
        self,
        context,
        node_name,
        model,
        prompt,
        output,
        latency_ms,
        token_usage,
    ):
        self.nodes.append(node_name)
        self.prompts.append(prompt)


def test_c1_to_c6_graph_executes_structured_nodes_and_records_trace() -> None:
    tracer = RecordingTrace()
    workflow = HearingWorkflow(
        StubLlm(), PromptRepository(), tracer, "test-model", "hearing-v1"
    )
    request = HearingAnalyzeRequest(
        case_id="CASE_graph",
        workflow_id="WORKFLOW_graph",
        user_id="USER_graph",
        claims=[
            {
                "claim_id": "CLAIM_user",
                "party_type": "USER",
                "statement": "The parcel was not received.",
            }
        ],
        evidence=[
            {
                "evidence_id": "EVIDENCE_tracking",
                "evidence_type": "LOGISTICS_TRACKING",
                "source_type": "PLATFORM",
                "content": "Dispatched and marked delivered.",
            }
        ],
        policy_candidates=[
            {
                "rule_code": "DELIVERY_PROOF",
                "rule_version": 2,
                "rule_text": "Receipt requires reliable delivery proof.",
            }
        ],
    )
    context = AgentTraceContext(
        trace_id="TRACE_graph",
        request_id="REQ_graph",
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        user_id=request.user_id,
        role="SYSTEM",
        prompt_version="hearing-v1",
    )

    result = workflow.analyze(request, context)

    assert result.workflow_status == "COMPLETED"
    assert result.executed_nodes == [
        "issue_framing_node",
        "evidence_gap_request_node",
        "party_liaison_node",
        "evidence_cross_check_node",
        "rule_application_node",
        "adjudication_draft_node",
    ]
    assert tracer.nodes == result.executed_nodes
    assert all(prompt.startswith("[REDACTED_INPUT sha256=") for prompt in tracer.prompts)
    assert all("Dispatched and marked delivered" not in prompt for prompt in tracer.prompts)
    assert tracer.completed["adjudication_draft"]["draft"]["is_final_decision"] is False


def test_c3_is_skipped_when_no_supplemental_evidence_is_required() -> None:
    class NoGapLlm(StubLlm):
        def generate(self, *, node_name, system_prompt, user_prompt, output_type):
            if node_name == "evidence_gap_request_node":
                return StructuredGeneration(
                    value=output_type.model_validate(
                        {
                            "requires_supplemental_evidence": False,
                            "gaps": [],
                        }
                    ),
                    model="test-model",
                    latency_ms=1,
                    token_usage={"input": 1, "output": 1, "total": 2},
                )
            return super().generate(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
            )

    tracer = RecordingTrace()
    workflow = HearingWorkflow(
        NoGapLlm(), PromptRepository(), tracer, "test-model", "hearing-v1"
    )
    request = HearingAnalyzeRequest(
        case_id="CASE_no_gap",
        workflow_id="WORKFLOW_no_gap",
        claims=[
            {
                "claim_id": "CLAIM_no_gap",
                "party_type": "USER",
                "statement": "Delivery status needs review.",
            }
        ],
    )
    context = AgentTraceContext(
        trace_id="TRACE_no_gap",
        request_id="REQ_no_gap",
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        user_id=None,
        role="SYSTEM",
        prompt_version="hearing-v1",
    )

    result = workflow.analyze(request, context)

    assert result.workflow_status == "COMPLETED"
    assert "party_liaison_node" not in result.executed_nodes
    assert result.party_liaison is None


def test_final_convergence_skips_supplemental_request_even_when_gap_is_detected() -> None:
    tracer = RecordingTrace()
    workflow = HearingWorkflow(
        StubLlm(), PromptRepository(), tracer, "test-model", "hearing-v1"
    )
    request = HearingAnalyzeRequest(
        case_id="CASE_final_convergence",
        workflow_id="WORKFLOW_final_convergence",
        claims=[
            {
                "claim_id": "CLAIM_final_convergence",
                "party_type": "USER",
                "statement": "The third hearing statement round is complete.",
            }
        ],
        hearing_context={
            "completed_statement_rounds": 3,
            "max_statement_rounds": 3,
            "final_convergence": True,
            "must_produce_final_plan": True,
            "allow_supplemental_request": False,
        },
    )
    context = AgentTraceContext(
        trace_id="TRACE_final_convergence",
        request_id="REQ_final_convergence",
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        user_id=None,
        role="SYSTEM",
        prompt_version="hearing-v1",
    )

    result = workflow.analyze(request, context)

    assert result.workflow_status == "COMPLETED"
    assert result.executed_nodes == [
        "issue_framing_node",
        "evidence_gap_request_node",
        "evidence_cross_check_node",
        "rule_application_node",
        "adjudication_draft_node",
    ]
    assert result.party_liaison is None


def test_invalid_node_schema_enters_manual_review_without_a_final_decision() -> None:
    class InvalidLlm(StubLlm):
        def generate(self, *, node_name, system_prompt, user_prompt, output_type):
            if node_name == "evidence_cross_check_node":
                raise AgentOutputSchemaError(node_name, "invalid output")
            return super().generate(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
            )

    tracer = RecordingTrace()
    workflow = HearingWorkflow(
        InvalidLlm(), PromptRepository(), tracer, "test-model", "hearing-v1"
    )
    request = HearingAnalyzeRequest(
        case_id="CASE_invalid",
        workflow_id="WORKFLOW_invalid",
        claims=[
            {
                "claim_id": "CLAIM_invalid",
                "party_type": "USER",
                "statement": "The evidence needs human review.",
            }
        ],
    )
    context = AgentTraceContext(
        trace_id="TRACE_invalid",
        request_id="REQ_invalid",
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        user_id=None,
        role="SYSTEM",
        prompt_version="hearing-v1",
    )

    result = workflow.analyze(request, context)

    assert result.workflow_status == "MANUAL_REVIEW_REQUIRED"
    assert result.manual_review_reasons == ["AGENT_OUTPUT_SCHEMA_INVALID"]
    assert result.adjudication_draft.draft.is_final_decision is False
    assert result.adjudication_draft.draft.requires_human_review is True


@pytest.mark.parametrize(
    ("stage", "expected_node"),
    [
        ("C1_ISSUE_FRAMING", "issue_framing_node"),
        ("C2_EVIDENCE_GAP", "evidence_gap_request_node"),
        ("C3_EVIDENCE_REQUEST", "party_liaison_node"),
        ("C4_EVIDENCE_CROSS_CHECK", "evidence_cross_check_node"),
        ("C5_RULE_APPLICATION", "rule_application_node"),
        ("C6_DRAFT_GENERATION", "adjudication_draft_node"),
    ],
)
def test_presiding_judge_stage_runner_executes_exactly_one_node(
    stage: str,
    expected_node: str,
) -> None:
    tracer = RecordingTrace()
    workflow = HearingWorkflow(
        StubLlm(), PromptRepository(), tracer, "test-model", "hearing-v1"
    )
    request = HearingStageRequest(
        case_id="CASE_stage",
        workflow_id="WORKFLOW_stage",
        stage=stage,
        dossier_version=3,
        claims=[
            {
                "claim_id": "CLAIM_stage",
                "party_type": "USER",
                "statement": "The parcel was not received.",
            }
        ],
        **(
            {
                "stop_reason": "FACTS_SUFFICIENT",
                "latest_frozen_dossier_version": 3,
                "party_absence_flags": {
                    "USER": False,
                    "MERCHANT": False,
                },
            }
            if stage == "C6_DRAFT_GENERATION"
            else {}
        ),
    )
    context = AgentTraceContext(
        trace_id="TRACE_stage",
        request_id="REQ_stage",
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        user_id=None,
        role="SYSTEM",
        prompt_version="hearing-v1",
    )

    result = workflow.run_stage(request, context)

    assert tracer.nodes == [expected_node]
    assert result.stage == stage
    assert result.dossier_version == 3
    assert result.non_final is True
    assert result.requires_human_review is True
    if stage == "C6_DRAFT_GENERATION":
        assert result.recommended_draft is not None
        assert result.reviewer_attention
        assert "version 3 only" in result.reviewer_attention[1]
