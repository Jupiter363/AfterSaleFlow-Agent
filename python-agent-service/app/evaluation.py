from __future__ import annotations

from app.llm import StructuredLlmClient
from app.harness.prompt_composer import PromptRepository
from app.schemas import (
    EvaluationAgentOutput,
    EvaluationAnalysisResult,
    EvaluationAnalyzeRequest,
    EvaluationMetricScores,
)
from app.tracing import AgentTraceContext, AgentTracer, redacted_trace_input


class EvaluationWorkflow:
    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
        tracer: AgentTracer,
        prompt_version: str,
    ) -> None:
        self._llm = llm
        self._prompts = prompts
        self._tracer = tracer
        self._prompt_version = prompt_version

    def analyze(
        self,
        request: EvaluationAnalyzeRequest,
        trace_context: AgentTraceContext,
    ) -> EvaluationAnalysisResult:
        request_data = request.model_dump(mode="json")
        system_prompt, user_prompt = self._prompts.render(
            "evaluation_analyze",
            request_data,
            EvaluationAgentOutput.model_json_schema(),
        )
        with self._tracer.workflow(trace_context, request_data) as trace:
            generation = self._llm.generate(
                node_name="evaluation_analyze",
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=EvaluationAgentOutput,
            )
            output = EvaluationAgentOutput.model_validate(generation.value)
            scores = EvaluationMetricScores(
                draft_approval_rate=1.0,
                reviewer_modification_rate=(
                    1.0
                    if request.approval_decision == "MODIFY_AND_APPROVE"
                    else 0.0
                ),
                **output.qualitative_scores.model_dump(),
            )
            result = EvaluationAnalysisResult(
                case_id=request.case_id,
                evaluation_status="COMPLETED",
                metric_scores=scores,
                findings=output.findings,
                rule_gap_suggestions=output.rule_gap_suggestions,
                improvement_suggestions=output.improvement_suggestions,
                automatic_changes_applied=False,
                online_case_mutated=False,
                evaluator_model=generation.model,
                prompt_version=self._prompt_version,
                latency_ms=generation.latency_ms,
                token_usage=generation.token_usage.get("total", 0),
            )
            result_data = result.model_dump(mode="json")
            self._tracer.generation(
                trace_context,
                "evaluation_analyze",
                generation.model,
                redacted_trace_input(user_prompt),
                result_data,
                generation.latency_ms,
                generation.token_usage,
            )
            trace.complete(result_data)
            return result
