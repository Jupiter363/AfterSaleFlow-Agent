# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

from app.llm import StructuredLlmClient
from app.harness.prompt_composer import PromptRepository
from app.schemas import IntakeAnalysisOutput, IntakeAnalyzeRequest
from app.tracing import AgentTraceContext, AgentTracer, redacted_trace_input


class IntakeWorkflow:
    # 所属模块：Python 支撑模块 > intake；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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

    # 所属模块：Python 支撑模块 > intake；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`request.model_dump`、`self._prompts.render`、`IntakeAnalysisOutput.model_json_schema`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `request.model_dump`、`self._prompts.render`、`IntakeAnalysisOutput.model_json_schema`、`self._tracer.workflow`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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
