# 文件作用：为 Java 提供 C1-C6 单阶段调用与完整 LangGraph 分析两种门面，并把最终共享状态验收成正式响应/人工复核结果。

from __future__ import annotations

from app.graph import OUTPUT_TYPES, build_hearing_graph
from app.llm import AgentOutputSchemaError, StructuredLlmClient
from app.harness.prompt_composer import PromptRepository
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
    # HearingStage 是业务枚举；右侧字符串必须和 app.graph.OUTPUT_TYPES / prompt 模板一致。
    HearingStage.C1_ISSUE_FRAMING: "issue_framing_node",
    HearingStage.C2_EVIDENCE_GAP: "evidence_gap_request_node",
    HearingStage.C3_EVIDENCE_REQUEST: "party_liaison_node",
    HearingStage.C4_EVIDENCE_CROSS_CHECK: "evidence_cross_check_node",
    HearingStage.C5_RULE_APPLICATION: "rule_application_node",
    HearingStage.C6_DRAFT_GENERATION: "adjudication_draft_node",
}


class HearingWorkflow:
    """听证主工作流门面。

    这里包装了两种调用方式：
    - run_stage：只运行 C1-C6 中指定的一个阶段，适合 Java 分阶段编排；
    - analyze：一次性运行完整 LangGraph，适合 legacy/整体分析接口。
    """

    # 所属模块：听证主链路 > C1-C6 门面 > 依赖与编译图初始化。
    # 具体功能：`__init__` 保存 LLM、Prompt、Tracer 及响应版本元数据，并调用 `build_hearing_graph` 一次性编译完整图供后续 analyze 复用。
    # 上下游：上游是 FastAPI 服务启动依赖装配；下游是 `run_stage` 的直接单节点执行和 `analyze` 的编译图 invoke。
    # 系统意义：构图拓扑不会随单个请求内容改变；同一进程所有案件沿相同 C1-C6 规则执行，模型/Prompt 版本可随响应审计。
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
        # 完整图在初始化时编译一次，后续请求直接 invoke，避免重复构图。
        self._graph = build_hearing_graph(llm, prompts, tracer)

    # 所属模块：听证主链路 > C1-C6 门面 > 指定阶段独立执行。
    # 具体功能：`run_stage` 将 HearingStage 映射到与图一致的 node_name/output_type，以冻结请求+previous_stage_outputs 渲染 Prompt，调用一次模型并产出带 trace、版本和 C6 审核辅助字段的 HearingStageResult。
    # 上下游：上游是 Java/Temporal 按阶段持久化后传入 HearingStageRequest；下游是下一阶段的 previous_stage_outputs 或 C6 草案人工审核包。
    # 系统意义：支持外部编排逐阶段重试而不重跑全图；仍复用同一 Prompt/Schema 合同，避免“单阶段接口”和完整图产生不同业务语义。
    def run_stage(
        self,
        request: HearingStageRequest,
        trace_context: AgentTraceContext,
    ) -> HearingStageResult:
        """Run exactly one C1-C6 stage against a frozen dossier version."""

        node_name = STAGE_NODES[request.stage]
        output_type = OUTPUT_TYPES[node_name]
        request_data = request.model_dump(mode="json")
        # run_stage 手动构造和 graph 节点一致的 prompt 输入；
        # previous_stage_outputs 让单阶段调用也能看到前置阶段结果。
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
                "round_no": request.round_no,
                "stop_reason": request.stop_reason,
                "deadline_expired": request.deadline_expired,
                "round_limit_reached": request.round_limit_reached,
                "latest_frozen_dossier_version": (
                    request.latest_frozen_dossier_version
                ),
                "party_absence_flags": request.party_absence_flags,
                "current_settlement_version": (
                    request.current_settlement_version
                ),
            },
            "prior_outputs": request.previous_stage_outputs,
        }
        system_prompt, user_prompt = self._prompts.render(
            node_name,
            case_data,
            output_type.model_json_schema(),
        )
        with self._tracer.workflow(trace_context, request_data) as trace:
            # with 是上下文管理器语法：进入时开始 trace，退出时自动收尾/清理。
            generation = self._llm.generate(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
            )
            # 即使 StructuredLlmClient 已按 output_type 解析，这里再次在业务边界验收，防止测试替身/未来适配器返回错型对象。
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
                evidence_gaps=_c6_evidence_gaps(request),
                uncertainties=_c6_uncertainties(request),
                reviewer_attention=_c6_reviewer_attention(request),
                recommended_draft=(
                    # 三元表达式：条件成立返回 output_data.get("draft")，否则返回 None。
                    output_data.get("draft")
                    if request.stage is HearingStage.C6_DRAFT_GENERATION
                    else None
                ),
            )
            trace.complete(result.model_dump(mode="json"))
            return result

    # 所属模块：听证主链路 > C1-C6 门面 > 完整图执行入口。
    # 具体功能：`analyze` 把 Pydantic 请求转成可序列化初始 state，在一个 workflow trace 中调用编译图，随后由 `_completed` 对所有必需阶段槽做最终验收。
    # 上下游：上游是 legacy/整体分析 API 的 HearingAnalyzeRequest；下游是 LangGraph 六阶段状态、HearingAnalysisResult 和 trace.complete。
    # 系统意义：图内每个节点保留 generation trace，图外再保留整次 workflow trace；真实 executed_nodes 能说明条件分支是否执行了补证联络。
    def analyze(
        self,
        request: HearingAnalyzeRequest,
        trace_context: AgentTraceContext,
    ) -> HearingAnalysisResult:
        """运行完整 C1-C6 图。"""

        request_data = request.model_dump(mode="json")
        with self._tracer.workflow(trace_context, request_data) as trace:
            try:
                state = self._graph.invoke(
                    # 初始状态只放 request、trace_context、executed_nodes；
                    # 每个图节点会逐步把 issue_framing/evidence_gap 等结果写回 state。
                    {
                        "request": request_data,
                        "trace_context": trace_context,
                        "executed_nodes": [],
                    }
                )
                result = self._completed(request, state)
            except AgentOutputSchemaError as exception:
                hearing_context = request.hearing_context
                if not (
                    hearing_context.final_convergence
                    or hearing_context.must_produce_final_plan
                ):
                    raise
                result = self._manual_review(request, exception)
            trace.complete(result.model_dump(mode="json"))
            return result

    # 所属模块：听证主链路 > C1-C6 门面 > 最终 LangGraph 状态验收。
    # 具体功能：`_completed` 将 state 中每个阶段 dict 重新校验成对应 Pydantic 模型；party_liaison 是唯一条件可选槽，其余必需槽缺失或错型都会失败。
    # 上下游：上游是 `_graph.invoke` 返回的最终共享状态；下游是 API 返回的强类型 HearingAnalysisResult。
    # 系统意义：LangGraph 的 TypedDict 主要帮助开发期类型检查，运行时最终 Pydantic 验收才阻止残缺/污染状态被标记 COMPLETED。
    def _completed(
        self, request: HearingAnalyzeRequest, state: dict
    ) -> HearingAnalysisResult:
        """把 LangGraph 最终 state 转成正式响应模型。"""

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

    # 所属模块：听证主链路 > C1-C6 门面 > 最终收敛 Schema 失败人工兜底构造器。
    # 具体功能：`_manual_review` 可在 AgentOutputSchemaError 时构造零置信、HIGH 风险、UNDETERMINED 的非裁决占位草案，并记录出错 node_name 与固定人工原因码。
    # 上下游：上游是最终收敛时完整图节点输出无法通过结构化校验的异常路径；下游是 MANUAL_REVIEW_REQUIRED 响应和人工工作台。
    # 系统意义：最终轮必须确定性结束，但不得从损坏自由文本猜结论；普通非最终轮仍保持严格失败。
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


# 所属模块：听证主链路 > C6 审核包 > 证据缺口提取。
# 具体功能：`_c6_evidence_gaps` 仅在 C6 从冻结 previous_stage_outputs.C2_EVIDENCE_GAP 读取 gaps[].reason；结构缺失/损坏时返回明确英文诊断而非假装没有缺口。
# 上下游：上游是 Java 持久化的 C2 结构化产物；下游是 HearingStageResult.evidence_gaps 和人工审核界面。
# 系统意义：裁判草案必须把早期识别的证据缺口带到审核终点，防止阶段切换后只展示结论而隐藏不确定基础。
def _c6_evidence_gaps(request: HearingStageRequest) -> list[str]:
    """C6 草案阶段给人工复核展示的证据缺口摘要。"""

    if request.stage is not HearingStage.C6_DRAFT_GENERATION:
        return []
    gap_output = request.previous_stage_outputs.get("C2_EVIDENCE_GAP", {})
    if not isinstance(gap_output, dict):
        return ["Prior evidence-gap output was unavailable."]
    gaps = gap_output.get("gaps", [])
    if not isinstance(gaps, list):
        return ["Prior evidence-gap output was malformed."]
    rendered: list[str] = []
    for gap in gaps:
        if isinstance(gap, dict):
            reason = gap.get("reason")
            if isinstance(reason, str) and reason:
                rendered.append(reason)
    return rendered


# 所属模块：听证主链路 > C6 审核包 > 程序性不确定因素提取。
# 具体功能：`_c6_uncertainties` 仅在 C6 汇总截止时间到期、三轮上限和每个缺席角色，描述为何流程在材料未自然收敛时仍进入草案。
# 上下游：上游是 HearingStageRequest 的确定性流程控制字段；下游是 HearingStageResult.uncertainties。
# 系统意义：这些信号不是 LLM 推理，人工审核必须知道草案是否在超时、缺席或强制轮次上限下形成。
def _c6_uncertainties(request: HearingStageRequest) -> list[str]:
    """C6 草案阶段的不确定性来源。"""

    if request.stage is not HearingStage.C6_DRAFT_GENERATION:
        return []
    values: list[str] = []
    if request.deadline_expired:
        values.append("The hearing deadline expired before voluntary convergence.")
    if request.round_limit_reached:
        values.append("The three-round hearing limit was reached.")
    for role, absent in request.party_absence_flags.items():
        if absent:
            values.append(f"{role} was absent from the hearing.")
    return values


# 所属模块：听证主链路 > C6 审核包 > 冻结版本关注点生成。
# 具体功能：`_c6_reviewer_attention` 仅在 C6 提醒审核员核对强制收敛原因、唯一允许使用的 frozen dossier version，并在存在时附加 settlement proposal version。
# 上下游：上游是 HearingStageRequest 的 stop_reason 与版本字段；下游是 HearingStageResult.reviewer_attention。
# 系统意义：审核不能混用庭审后又变化的卷宗/和解版本；版本钉死保证草案输入、人工看到的材料和最终审计一致。
def _c6_reviewer_attention(request: HearingStageRequest) -> list[str]:
    """C6 草案阶段需要审核员特别关注的事项。"""

    if request.stage is not HearingStage.C6_DRAFT_GENERATION:
        return []
    attention = [
        f"Review forced-convergence reason: {request.stop_reason}.",
        (
            "Review frozen dossier version "
            f"{request.latest_frozen_dossier_version} only."
        ),
    ]
    if request.current_settlement_version is not None:
        attention.append(
            "Review settlement proposal version "
            f"{request.current_settlement_version}."
        )
    return attention
