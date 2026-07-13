# 文件作用：定义 C1-C6 听证分析的 LangGraph 状态、六个结构化 LLM 节点、补证条件边和最终编译入口。

from __future__ import annotations

import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.adjudication_contract import (
    c6_output_type_for_request,
    trusted_c6_review_contract,
)
from app.llm import StructuredLlmClient
from app.harness.prompt_composer import PromptRepository
from app.schemas import (
    AdjudicationDraftOutput,
    EvidenceCrossCheckOutput,
    EvidenceGapOutput,
    IssueFramingOutput,
    PartyLiaisonOutput,
    RuleApplicationOutput,
)
from app.tracing import AgentTraceContext, AgentTracer, redacted_trace_input


class HearingGraphState(TypedDict):
    """听证阶段 LangGraph 的共享状态。

    TypedDict 是“字典形状”的类型提示：运行时它仍然是 dict，
    但编辑器/类型检查器知道里面应该有哪些 key。
    NotRequired 表示这个 key 不是初始状态必填项，会由后续节点逐步写入。
    Annotated[list[str], operator.add] 是 LangGraph 的状态合并规则：
    多个节点返回 executed_nodes 时，用 list 相加的方式累计执行轨迹。
    """

    request: dict[str, Any]
    trace_context: AgentTraceContext
    executed_nodes: Annotated[list[str], operator.add]
    issue_framing: NotRequired[dict[str, Any]]
    evidence_gap: NotRequired[dict[str, Any]]
    party_liaison: NotRequired[dict[str, Any]]
    evidence_cross_check: NotRequired[dict[str, Any]]
    rule_application: NotRequired[dict[str, Any]]
    adjudication_draft: NotRequired[dict[str, Any]]


OUTPUT_TYPES = {
    "issue_framing_node": IssueFramingOutput,
    "evidence_gap_request_node": EvidenceGapOutput,
    "party_liaison_node": PartyLiaisonOutput,
    "evidence_cross_check_node": EvidenceCrossCheckOutput,
    "rule_application_node": RuleApplicationOutput,
    "adjudication_draft_node": AdjudicationDraftOutput,
}

STATE_KEYS = {
    "issue_framing_node": "issue_framing",
    "evidence_gap_request_node": "evidence_gap",
    "party_liaison_node": "party_liaison",
    "evidence_cross_check_node": "evidence_cross_check",
    "rule_application_node": "rule_application",
    "adjudication_draft_node": "adjudication_draft",
}


# 所属模块：听证主链路 > C1-C6 LangGraph > 图构建与编译入口。
# 具体功能：`build_hearing_graph` 用 HearingGraphState 建图，为六个 node_name 绑定各自 Pydantic 输出模型/状态键，注册固定边和一条补证条件边，最后 compile 成可 invoke 图。
# 上下游：上游是 HearingWorkflow 初始化时注入的 LLM、PromptRepository、Tracer；下游是 `HearingWorkflow.analyze` 传入冻结请求和 trace_context 执行完整图。
# 系统意义：业务阶段顺序写在确定性图结构而非交给 LLM 自由选择；每个节点只写自己的结构化状态，执行轨迹由 reducer 可审计累积。
def build_hearing_graph(
    llm: StructuredLlmClient,
    prompts: PromptRepository,
    tracer: AgentTracer,
):
    """构建完整听证分析图。

    LangGraph 的基本模式：
    1. StateGraph(HearingGraphState) 声明“图里流动的状态长什么样”；
    2. add_node 注册节点函数；
    3. add_edge / add_conditional_edges 描述节点顺序和分支；
    4. compile() 得到可 invoke 的图对象。

    本图把一个案件依次交给多个 LLM 节点：
    争点归纳 -> 证据缺口 -> 当事人联络/交叉核验 -> 规则适用 -> 裁判草案。
    """

    # 所属模块：听证主链路 > C1-C6 LangGraph > 同构节点工厂。
    # 具体功能：`node` 根据 node_name 从 OUTPUT_TYPES/STATE_KEYS 绑定该阶段输出 Schema 与写回键，并返回真正接受 LangGraph state 的 execute 闭包。
    # 上下游：上游是本函数注册六个节点时逐个调用；下游是 LangGraph 在运行到对应节点时调用内部 `execute(state)`。
    # 系统意义：六个阶段共享“渲染、一次模型调用、校验、trace、局部写回”骨架，但 Schema/状态槽不会靠字符串临时猜测，减少节点实现漂移。
    def node(node_name: str):
        # 闭包语法说明：node(...) 返回 execute 函数。
        # 每个 node_name 都会绑定不同的 output_type 和 state_key，
        # 这样可以用同一段 execute 逻辑创建多个 LangGraph 节点。
        default_output_type = OUTPUT_TYPES[node_name]
        state_key = STATE_KEYS[node_name]

        # 所属模块：听证主链路 > C1-C6 LangGraph > 单阶段节点执行器。
        # 具体功能：`execute` 从共享 state 读取原始请求和已存在的前序输出，按本节点 Schema 渲染 Prompt、执行唯一一次 LLM 调用、序列化强类型结果并记录脱敏 generation trace。
        # 上下游：上游是 START/固定边/条件边传入的 HearingGraphState；下游返回 `{本阶段状态键: output, executed_nodes:[node_name]}`，由 LangGraph 合并后流向下一节点。
        # 系统意义：节点返回的是局部更新而非完整 state；executed_nodes 的 operator.add reducer 保留真实路径，Pydantic 结构化生成阻止自由文本直接污染后续阶段。
        def execute(state: HearingGraphState) -> dict[str, Any]:
            output_type = (
                c6_output_type_for_request(state["request"])
                if node_name == "adjudication_draft_node"
                else default_output_type
            )
            # case_data 会进入不可信 user prompt。字典推导式只收集“已经写入 state”的阶段，
            # 因而跳过条件分支未执行的 party_liaison，而不会制造一个假空结果。
            case_data = {
                "request": state["request"],
                "prior_outputs": {
                    key: state[key]
                    for key in STATE_KEYS.values()
                    if key in state
                },
            }
            if node_name == "adjudication_draft_node":
                # Repeat the compact manifest at the end of the user payload so
                # it remains adjacent to the response contract even when the
                # frozen courtroom dossier is large. The authoritative copy is
                # still the system-level trusted_agent_context below.
                case_data["c6_response_contract"] = trusted_c6_review_contract(
                    state["request"]
                )
            system_prompt, user_prompt = prompts.render(
                node_name,
                case_data,
                output_type.model_json_schema(),
                trusted_agent_context=(
                    trusted_c6_review_contract(state["request"])
                    if node_name == "adjudication_draft_node"
                    else None
                ),
            )
            # 这里是本节点唯一一次 LLM 调用；output_type 强制模型输出符合指定 Pydantic schema。
            generation = llm.generate(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
            )
            output = generation.value.model_dump(mode="json")
            # tracing 只记录脱敏后的 prompt 输入和结构化输出，用于排查模型质量和延迟。
            tracer.generation(
                state["trace_context"],
                node_name,
                generation.model,
                redacted_trace_input(user_prompt),
                output,
                generation.latency_ms,
                generation.token_usage,
            )
            # LangGraph 会把该局部 dict 合并回共享状态：普通键覆盖对应槽，executed_nodes 按 reducer 追加。
            return {state_key: output, "executed_nodes": [node_name]}

        # 闭包原名都叫 execute；改 __name__ 便于 LangGraph 调试、trace 和报错显示真实业务节点名。
        execute.__name__ = node_name
        return execute

    # StateGraph 只在构图阶段描述状态与拓扑；compile() 后得到的对象才提供 invoke/stream 等执行接口。
    builder = StateGraph(HearingGraphState)
    for node_name in OUTPUT_TYPES:
        builder.add_node(node_name, node(node_name))
    builder.add_edge(START, "issue_framing_node")
    builder.add_edge("issue_framing_node", "evidence_gap_request_node")
    # 条件函数返回的是标签，不是节点名；第三个参数把有限标签映射到合法目标节点，阻止任意跳转。
    builder.add_conditional_edges(
        "evidence_gap_request_node",
        _after_evidence_gap,
        {
            "request_evidence": "party_liaison_node",
            "cross_check": "evidence_cross_check_node",
        },
    )
    builder.add_edge("party_liaison_node", "evidence_cross_check_node")
    builder.add_edge("evidence_cross_check_node", "rule_application_node")
    builder.add_edge("rule_application_node", "adjudication_draft_node")
    builder.add_edge("adjudication_draft_node", END)
    return builder.compile()


# 所属模块：听证主链路 > C1-C6 LangGraph > C2 后确定性路由。
# 具体功能：`_after_evidence_gap` 先执行系统级强制收敛/禁止补证规则，只有仍允许补证时才读取 C2 结构化字段 requires_supplemental_evidence，返回有限标签 request_evidence 或 cross_check。
# 上下游：上游是 evidence_gap_request_node 已写入的 state 与请求 hearing_context；下游由 add_conditional_edges 跳到 party_liaison_node 或直接 evidence_cross_check_node。
# 系统意义：LLM 只能报告“认为需要补证”，不能推翻截止时间、轮次上限或 must_produce_final_plan；硬业务规则优先于模型建议。
def _after_evidence_gap(state: HearingGraphState) -> str:
    """证据缺口节点后的分支函数。

    LangGraph 要求条件边函数返回一个字符串标签，再根据映射表跳到下一个节点。
    这里的业务含义是：如果还允许补证且模型认为需要补证，就先走 party_liaison_node；
    如果必须产出方案或不允许补证，则直接交叉核验证据。
    """

    hearing_context = state["request"].get("hearing_context") or {}
    if (
        hearing_context.get("must_produce_final_plan")
        or not hearing_context.get("allow_supplemental_request", True)
    ):
        return "cross_check"
    if state["evidence_gap"]["requires_supplemental_evidence"]:
        return "request_evidence"
    return "cross_check"
