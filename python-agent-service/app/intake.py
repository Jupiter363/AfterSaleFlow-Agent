from __future__ import annotations

from app.llm import StructuredLlmClient
from app.prompts import PromptRepository
from app.schemas import IntakeAnalysisOutput, IntakeAnalyzeRequest
from app.tracing import AgentTraceContext, AgentTracer, redacted_trace_input


class IntakeWorkflow:
    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
        tracer: AgentTracer,
        model: str,
    ) -> None:
        self._llm = llm
        self._prompts = prompts
        self._tracer = tracer
        self._model = model

    def analyze(
        self,
        request: IntakeAnalyzeRequest,
        trace_context: AgentTraceContext,
    ) -> IntakeAnalysisOutput:
        request_data = request.model_dump(mode="json")
        system_prompt, user_prompt = self._prompts.render(
            "intake_analyze",
            {"request": request_data},
            IntakeAnalysisOutput.model_json_schema(),
        )
        with self._tracer.workflow(trace_context, request_data) as trace:
            generation = self._llm.generate(
                node_name="intake_analyze",
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=IntakeAnalysisOutput,
            )
            output = IntakeAnalysisOutput.model_validate(generation.value)
            output_data = output.model_dump(mode="json")
            self._tracer.generation(
                trace_context,
                "intake_analyze",
                generation.model,
                redacted_trace_input(user_prompt),
                output_data,
                generation.latency_ms,
                generation.token_usage,
            )
            trace.complete(output_data)
            return output
