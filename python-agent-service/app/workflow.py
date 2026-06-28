from __future__ import annotations

from app.graph import build_hearing_graph
from app.llm import AgentOutputSchemaError, StructuredLlmClient
from app.prompts import PromptRepository
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    EvidenceCrossCheckOutput,
    EvidenceGapOutput,
    HearingAnalysisResult,
    HearingAnalyzeRequest,
    IssueFramingOutput,
    PartyLiaisonOutput,
    RuleApplicationOutput,
)
from app.tracing import AgentTraceContext, AgentTracer


class HearingWorkflow:
    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
        tracer: AgentTracer,
        model: str,
        prompt_version: str,
    ) -> None:
        self._tracer = tracer
        self._model = model
        self._prompt_version = prompt_version
        self._graph = build_hearing_graph(llm, prompts, tracer)

    def analyze(
        self,
        request: HearingAnalyzeRequest,
        trace_context: AgentTraceContext,
    ) -> HearingAnalysisResult:
        request_data = request.model_dump(mode="json")
        with self._tracer.workflow(trace_context, request_data) as trace:
            try:
                state = self._graph.invoke(
                    {
                        "request": request_data,
                        "trace_context": trace_context,
                        "executed_nodes": [],
                    }
                )
                result = self._completed(request, state)
            except AgentOutputSchemaError as exception:
                result = self._manual_review(request, exception)
            trace.complete(result.model_dump(mode="json"))
            return result

    def _completed(
        self, request: HearingAnalyzeRequest, state: dict
    ) -> HearingAnalysisResult:
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="COMPLETED",
            executed_nodes=state["executed_nodes"],
            issue_framing=IssueFramingOutput.model_validate(state["issue_framing"]),
            evidence_gap=EvidenceGapOutput.model_validate(state["evidence_gap"]),
            party_liaison=(
                PartyLiaisonOutput.model_validate(state["party_liaison"])
                if state.get("party_liaison")
                else None
            ),
            evidence_cross_check=EvidenceCrossCheckOutput.model_validate(
                state["evidence_cross_check"]
            ),
            rule_application=RuleApplicationOutput.model_validate(
                state["rule_application"]
            ),
            adjudication_draft=AdjudicationDraftOutput.model_validate(
                state["adjudication_draft"]
            ),
            prompt_version=self._prompt_version,
            model=self._model,
        )

    def _manual_review(
        self, request: HearingAnalyzeRequest, exception: AgentOutputSchemaError
    ) -> HearingAnalysisResult:
        fallback = AdjudicationDraftOutput(
            draft=AdjudicationDraft(
                recommended_outcome="UNDETERMINED",
                reasoning_summary=(
                    "Structured agent output could not be validated. "
                    "No automated finding was accepted."
                ),
                issue_findings=[],
                confidence=0,
                risk_level="HIGH",
                review_focus=[
                    f"Review invalid structured output from {exception.node_name}"
                ],
            )
        )
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="MANUAL_REVIEW_REQUIRED",
            executed_nodes=[],
            adjudication_draft=fallback,
            manual_review_reasons=["AGENT_OUTPUT_SCHEMA_INVALID"],
            prompt_version=self._prompt_version,
            model=self._model,
        )
