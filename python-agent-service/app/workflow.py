from __future__ import annotations

from app.graph import OUTPUT_TYPES, build_hearing_graph
from app.llm import AgentOutputSchemaError, StructuredLlmClient
from app.prompts import PromptRepository
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    EvidenceCrossCheckOutput,
    EvidenceGapOutput,
    HearingAnalysisResult,
    HearingAnalyzeRequest,
    HearingStage,
    HearingStageRequest,
    HearingStageResult,
    IssueFramingOutput,
    PartyLiaisonOutput,
    RuleApplicationOutput,
)
from app.tracing import (
    AgentTraceContext,
    AgentTracer,
    redacted_trace_input,
)


STAGE_NODES = {
    HearingStage.C1_ISSUE_FRAMING: "issue_framing_node",
    HearingStage.C2_EVIDENCE_GAP: "evidence_gap_request_node",
    HearingStage.C3_EVIDENCE_REQUEST: "party_liaison_node",
    HearingStage.C4_EVIDENCE_CROSS_CHECK: "evidence_cross_check_node",
    HearingStage.C5_RULE_APPLICATION: "rule_application_node",
    HearingStage.C6_DRAFT_GENERATION: "adjudication_draft_node",
}


class HearingWorkflow:
    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
        tracer: AgentTracer,
        model: str,
        prompt_version: str,
    ) -> None:
        self._llm = llm
        self._prompts = prompts
        self._tracer = tracer
        self._model = model
        self._prompt_version = prompt_version
        self._graph = build_hearing_graph(llm, prompts, tracer)

    def run_stage(
        self,
        request: HearingStageRequest,
        trace_context: AgentTraceContext,
    ) -> HearingStageResult:
        """Run exactly one C1-C6 stage against a frozen dossier version."""

        node_name = STAGE_NODES[request.stage]
        output_type = OUTPUT_TYPES[node_name]
        request_data = request.model_dump(mode="json")
        case_data = {
            "request": {
                "case_id": request.case_id,
                "workflow_id": request.workflow_id,
                "user_id": request.user_id,
                "claims": [
                    claim.model_dump(mode="json") for claim in request.claims
                ],
                "evidence": [
                    item.model_dump(mode="json") for item in request.evidence
                ],
                "policy_candidates": [
                    item.model_dump(mode="json")
                    for item in request.policy_candidates
                ],
                "evidence_timeout": request.evidence_timeout,
                "dossier_version": request.dossier_version,
            },
            "prior_outputs": request.previous_stage_outputs,
        }
        system_prompt, user_prompt = self._prompts.render(
            node_name,
            case_data,
            output_type.model_json_schema(),
        )
        with self._tracer.workflow(trace_context, request_data) as trace:
            generation = self._llm.generate(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
            )
            output = output_type.model_validate(generation.value)
            output_data = output.model_dump(mode="json")
            self._tracer.generation(
                trace_context,
                node_name,
                generation.model,
                redacted_trace_input(user_prompt),
                output_data,
                generation.latency_ms,
                generation.token_usage,
            )
            result = HearingStageResult(
                case_id=request.case_id,
                workflow_id=request.workflow_id,
                stage=request.stage,
                dossier_version=request.dossier_version,
                output=output_data,
                output_schema=output_type.__name__,
                prompt_version=self._prompt_version,
                model=generation.model,
            )
            trace.complete(result.model_dump(mode="json"))
            return result

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
