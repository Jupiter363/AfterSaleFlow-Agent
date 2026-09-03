# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

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
    # 所属模块：Python 支撑模块 > evaluation；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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

    # 所属模块：Python 支撑模块 > evaluation；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`request.model_dump`、`self._prompts.render`、`EvaluationAgentOutput.model_json_schema`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `request.model_dump`、`self._prompts.render`、`EvaluationAgentOutput.model_json_schema`、`self._tracer.workflow`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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
