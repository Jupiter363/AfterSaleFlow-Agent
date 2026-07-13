# 文件作用：编排法官单轮庭审 LangGraph，把冻结卷宗、双方陈述、证据与陪审 A2A 提示送入结构化模型，再按三轮程序确定性收敛。

from __future__ import annotations

import json
import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.unified_jury_review import UnifiedJuryReviewAgent
from app.harness.context_pack import build_context_pack
from app.harness.execution_tools import (
    ExecutionToolDeclaration,
    build_execution_tool_intention_section,
)
from app.harness.localization_policy import localize_internal_text
from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.schemas import HearingRoundTurnRequest, HearingRoundTurnResult
from app.streaming import current_stream_observer


LOGGER = logging.getLogger(__name__)


class HearingRoundTurnGraphState(TypedDict):
    """庭审法官一轮图状态；executed_nodes 用 reducer 追加，两个 NotRequired 槽由模型节点和护栏节点依次写入。"""

    request: dict[str, Any]
    executed_nodes: Annotated[list[str], operator.add]
    llm_output: NotRequired[HearingRoundTurnResult]
    result: NotRequired[HearingRoundTurnResult]


class HearingRoundTurnWorkflow:
    """主持法官单轮庭审发言，并在最终轮触发一次统一评审。

    这个工作流服务于“第 N 轮庭审”：根据当前轮次、双方陈述、证据卷宗和陪审团 A2A 记录，
    生成法官公开发言，再通过护栏强制保持“非最终裁决、需人工复核”的定位；
    第三轮封存后由独立统一评审 Agent 覆盖六项指标，报告随同一运行结果交给 Java 持久化。
    """

    # 所属模块：庭审法官 Agent > 单轮 LangGraph > 工作流实例初始化。
    # 具体功能：`__init__` 将 HarnessModelRunner 绑定进 LLM 闭包，并编译固定的“模型生成→法庭护栏”两节点图。
    # 上下游：上游是服务启动依赖装配；下游是 `run` 重用编译图处理开庭、普通结轮或第三轮收敛请求。
    # 系统意义：模型永远不能绕开 apply_court_guardrails 直接成为法官公开结果，三轮程序和人工复核标志由代码控制。
    def __init__(
        self,
        model_runner: Any | None = None,
        jury_review_agent: UnifiedJuryReviewAgent | None = None,
    ) -> None:
        self._graph = build_hearing_round_turn_graph(model_runner)
        self._jury_review_agent = jury_review_agent or (
            UnifiedJuryReviewAgent(model_runner) if model_runner is not None else None
        )

    # 所属模块：庭审法官 Agent > 单轮 LangGraph > Java 调用门面。
    # 具体功能：`run` 把 HearingRoundTurnRequest 序列化为初始 state，invoke 编译图，并对最终 `state["result"]` 再做 HearingRoundTurnResult 运行时验收。
    # 上下游：上游是 Java 冻结的本轮状态、双方 submissions 与 courtroom_context；下游是法官消息、下一轮问题、事件类型、最终草案触发和人工关注点。
    # 系统意义：TypedDict 不执行运行时校验，出口 Pydantic 校验保证残缺 graph state 不会作为有效庭审事件返回。
    def run(self, request: HearingRoundTurnRequest) -> HearingRoundTurnResult:
        state = self._graph.invoke(
            {
                "request": request.model_dump(mode="json"),
                "executed_nodes": [],
            }
        )
        result = HearingRoundTurnResult.model_validate(state["result"])
        final_round = request.final_round or request.round_no >= 3
        if final_round and request.party_submissions:
            if self._jury_review_agent is None:
                raise AgentServiceUnavailable(
                    "final hearing round requires the unified jury review agent"
                )
            report = self._jury_review_agent.review(request, result)
            result = result.model_copy(update={"jury_review_report": report})
        return result


# 所属模块：庭审法官 Agent > 单轮 LangGraph > 拓扑构建与编译。
# 具体功能：`build_hearing_round_turn_graph` 注册模型闭包与确定性法庭护栏，用 START→reason→guardrails→END 固定边连接并 compile。
# 上下游：上游是 HearingRoundTurnWorkflow 初始化；下游是 run.invoke 时两个节点的局部状态合并。
# 系统意义：这是单轮内部图，轮次之间由 Java/Temporal 持久化与调度；LangGraph 只负责编排本轮生成和验收，不自行开启下一轮。
def build_hearing_round_turn_graph(model_runner: Any | None = None):
    """组装庭审轮次图：LLM 生成 -> 法庭话术护栏。"""

    builder = StateGraph(HearingRoundTurnGraphState)
    builder.add_node("reason_with_llm", _reason_with_llm_node(model_runner))
    builder.add_node("apply_court_guardrails", _apply_court_guardrails)
    builder.add_edge(START, "reason_with_llm")
    builder.add_edge("reason_with_llm", "apply_court_guardrails")
    builder.add_edge("apply_court_guardrails", END)
    return builder.compile()


# 所属模块：庭审法官 Agent > 单轮 LangGraph > LLM 节点工厂。
# 具体功能：`_reason_with_llm_node` 用闭包固定 model_runner，返回符合 LangGraph `state -> partial state` 约定的 reason_with_llm callable。
# 上下游：上游是构图注册；下游是图运行时在 START 后调用内部执行器，依赖本身不进入 state/checkpoint。
# 系统意义：把服务能力与案件数据分离，客户端不能通过 request 注入模型运行器，测试又可替换受控 runner。
def _reason_with_llm_node(model_runner: Any | None):
    """创建庭审法官 LLM 节点。"""

    # 所属模块：庭审法官 Agent > 单轮 LangGraph > reason_with_llm 节点执行器。
    # 具体功能：`reason_with_llm` 验收请求，按 hearing context contract 组装当前轮、身份、冻结卷宗、双方陈述、证据、陪审 A2A、程序规则和只读工具意图，执行一次结构化模型调用并规范模型名。
    # 上下游：上游是初始 HearingRoundTurnGraphState；下游仅写强类型 llm_output，`_apply_court_guardrails` 再决定公开事件和流程状态。
    # 系统意义：模型只看到合同允许的冻结材料；工具声明只能做方案意图，陪审意见只做内部提示，异常不会回退成未审查的自由文本。
    def reason_with_llm(state: HearingRoundTurnGraphState) -> dict[str, Any]:
        request = HearingRoundTurnRequest.model_validate(state["request"])
        if model_runner is None:
            raise AgentServiceUnavailable(
                "hearing round model runner is not configured"
            )
        try:
            # 每个 source 都有独立 trust_level/required/priority；build_context_pack 不会接受 request 中未登记的其他字段。
            sources = {
                # sources 会被 build_context_pack 按 contract 变成 prompt sections。
                # 这比把整个 request 原封不动塞给模型更安全，也更容易控制 token。
                "current_turn": _current_turn_context(request),
                "case_identity": _case_identity_context(request),
                "canonical_case_dossier": _canonical_case_dossier(request),
                "hearing_round_submissions": [
                    item.model_dump(mode="json") for item in request.party_submissions
                ],
                "actor_visible_evidence": _actor_visible_evidence(request),
                "jury_a2a_notes": _jury_a2a_notes(request),
                "round_control_policy": _round_control_policy(request),
            }
            execution_tool_intentions = _execution_tool_intentions(request)
            if execution_tool_intentions is not None:
                sources["execution_tool_intentions"] = execution_tool_intentions
            context_pack = build_context_pack("hearing_round_turn", sources)
            generation = model_runner.invoke_structured(
                node_name="hearing_round_turn",
                case_data={
                    "case_id": request.case_id,
                    "workflow_id": request.workflow_id,
                    "round_no": request.round_no,
                    "final_round": request.final_round,
                    "risk_level": request.risk_level,
                },
                output_type=HearingRoundTurnResult,
                context_pack=context_pack,
            )
            # 二次 Pydantic 校验兼容测试替身，并确保下面 model_copy 操作基于完整强类型结果。
            output = HearingRoundTurnResult.model_validate(generation.value)
            # getattr(obj, "model", "") 是安全取属性：属性不存在时返回默认值。
            model = str(getattr(generation, "model", "") or output.model or "unknown")
            return {
                "llm_output": output.model_copy(update={"model": model}),
                "executed_nodes": ["reason_with_llm"],
            }
        except Exception as failure:
            LOGGER.warning(
                "hearing round judge turn degraded: case_id=%s round_no=%s error_type=%s error=%s",
                request.case_id,
                request.round_no,
                type(failure).__name__,
                failure,
                exc_info=True,
            )
            if isinstance(failure, (AgentOutputSchemaError, AgentServiceUnavailable)):
                raise
            raise AgentServiceUnavailable(
                "hearing round model generation failed"
            ) from failure

    return reason_with_llm


# 所属模块：庭审法官 Agent > 单轮 LangGraph > 三轮程序输出护栏节点。
# 具体功能：`_apply_court_guardrails` 重新验收 request/output，并由代码分为开庭、普通结轮、最终轮：覆盖事件类型、消息、问题、next_round_no、草案触发和 review_focus，最后强制 non_final/requires_human_review。
# 上下游：上游是 reason_with_llm 产出的模型候选；下游是 graph END 的 result 和 Java 庭审协调器。
# 系统意义：LLM 可建议内容但不能决定是否开新轮、是否继续追问、是否进入最终草案或是否跳过人工审核；第三轮必须确定性收敛。
def _apply_court_guardrails(state: HearingRoundTurnGraphState) -> dict[str, Any]:
    """法庭公开话术护栏。

    LLM 可能想继续追问、也可能误写成最终裁决；这里按轮次强制改写关键状态：
    - 开庭轮：生成开庭提示和双方问题；
    - 最终轮：进入非最终裁判草案路径，不再追问；
    - 普通轮：给出下一轮问题；
    - 所有轮次都标记 requires_human_review=True。
    """

    request = HearingRoundTurnRequest.model_validate(state["request"])
    output = HearingRoundTurnResult.model_validate(state["llm_output"])
    opening_turn = _is_opening_turn(request)
    final_round = request.final_round or request.round_no >= 3
    # 分支优先级很重要：开庭请求即使 round_no=3 也先发布开场；只有已有陈述的 final_round 才触发草案。
    if opening_turn:
        expected_event = "JUDGE_OPENING_READY"
        message_text = _opening_message(request)
        next_round_no = request.round_no
        questions_for_user = _opening_questions_for_user(request)
        questions_for_merchant = _opening_questions_for_merchant(request)
        round_summary = "法官已打开本轮庭审，等待用户和商家分别提交本轮说明。"
        proposed_resolution_direction = None
        final_proposed_resolution = None
    elif final_round:
        final_proposed_resolution = _sanitize_final_proposed_resolution(
            output.final_proposed_resolution
        )
        expected_event = "FINAL_DRAFT_REQUIRED"
        message_text = _sanitize_judge_message(
            output.message_text,
            final_round=True,
        )
        if final_proposed_resolution not in message_text:
            message_text = _append_streamed_public_contract(
                message_text,
                label="非最终拟处理方案：",
                value=final_proposed_resolution,
            )
        next_round_no = None
        questions_for_user = []
        questions_for_merchant = []
        round_summary = _sanitize_round_summary(output.round_summary)
        review_focus_signal = _review_focus_signal(request, output)
        proposed_resolution_direction = None
    else:
        expected_event = "JUDGE_NEXT_QUESTIONS_READY"
        message_text = _sanitize_judge_message(
            output.message_text,
            final_round=False,
        )
        next_round_no = min(3, request.round_no + 1)
        questions_for_user = output.questions_for_user
        questions_for_merchant = output.questions_for_merchant
        round_summary = _sanitize_round_summary(output.round_summary)
        review_focus_signal = []
        proposed_resolution_direction = None
        final_proposed_resolution = None
        if request.round_no == 2:
            proposed_resolution_direction = _sanitize_proposed_resolution_direction(
                output.proposed_resolution_direction
            )
            if proposed_resolution_direction not in message_text:
                message_text = _append_streamed_public_contract(
                    message_text,
                    label="非最终拟处理方向：",
                    value=proposed_resolution_direction,
                )
    if opening_turn:
        review_focus_signal = []
    result = output.model_copy(
        update={
            "speaker_role": "JUDGE",
            "message_text": message_text,
            "round_summary": round_summary,
            "questions_for_user": questions_for_user,
            "questions_for_merchant": questions_for_merchant,
            "court_event_type": expected_event,
            "round_no": request.round_no,
            "next_round_no": next_round_no,
            "final_draft_required": final_round and not opening_turn,
            "review_focus_signal": review_focus_signal,
            "proposed_resolution_direction": proposed_resolution_direction,
            "final_proposed_resolution": final_proposed_resolution,
            "non_final": True,
            "requires_human_review": True,
        }
    )
    return {
        "result": result,
        "executed_nodes": ["apply_court_guardrails"],
    }


def _append_streamed_public_contract(
    message_text: str,
    *,
    label: str,
    value: str,
) -> str:
    """Append a model-produced contract field and expose the suffix as a delta."""

    suffix = f"\n\n{label}{value}"
    observer = current_stream_observer()
    if observer is not None:
        observer.visible_delta(
            "hearing_round_turn",
            "message_text",
            suffix,
        )
    return message_text.rstrip() + suffix


# 所属模块：庭审法官 Agent > 最终轮交接 > 人工关注点选择。
# 具体功能：`_review_focus_signal` 清理并最多保留模型给出的 20 条关注点；模型未给出时才从冻结双方陈述调用确定性派生函数。
# 上下游：上游是最终轮 request 与已校验 output；下游是 result.review_focus_signal，随裁判草案进入人工审核。
# 系统意义：关注点是审核线索而非裁判理由；有模型结构化结果时尊重其提取，缺失时仍不丢双方最后异议。
def _review_focus_signal(
    request: HearingRoundTurnRequest,
    output: HearingRoundTurnResult,
) -> list[str]:
    """生成后续人工复核关注点，优先使用模型输出，缺失时从双方陈述推导。"""

    provided = [
        localize_internal_text(str(item or "").strip())
        for item in output.review_focus_signal
        if str(item or "").strip()
    ]
    if provided:
        return provided[:20]
    return _derived_review_focus_signal(request)


# 所属模块：庭审法官 Agent > 最终轮交接 > 双方异议确定性提取。
# 具体功能：`_derived_review_focus_signal` 仅在第三轮/显式最终轮遍历 frozen party_submissions，解析正文、按参与方改写第三人称并最多返回 20 条。
# 上下游：上游是 fallback 或模型未提供关注点的最终请求；下游是人工 review_focus_signal。
# 系统意义：前两轮材料仍可能补充，不提前固化；最终轮把每方立场带给审核员，但不会把一方主张转换为平台事实。
def _derived_review_focus_signal(request: HearingRoundTurnRequest) -> list[str]:
    """从双方本轮陈述中提取给审核员的关注点。

    只有第三轮或显式最终轮才生成该列表，因为此前轮次的陈述仍可能被下一轮补充；
    最终轮的列表会随裁判草案进入人工审核，而不是直接变成平台结论。
    """

    if request.round_no < 3 and not request.final_round:
        return []
    signals: list[str] = []
    for submission in request.party_submissions:
        statement = _submission_statement(submission.submission_json)
        signal = _normalize_review_focus(submission.participant_role, statement)
        if signal:
            signals.append(signal)
    return signals[:20]


# 所属模块：庭审法官 Agent > 最终轮交接 > 冻结陈述正文读取。
# 具体功能：`_submission_statement` 先尝试解析 submission_json，对 dict 按 statement/content/message/text 兼容顺序取首个非空正文；非 JSON 则把原字符串作为旧版正文。
# 上下游：上游是 HearingRoundSubmission.submission_json；下游是 `_normalize_review_focus`。
# 系统意义：兼容历史持久化格式但不扫描任意嵌套字段，避免把附件元数据或内部控制字段误当当事人陈述。
def _submission_statement(submission_json: str) -> str:
    """兼容结构化和纯文本两种当事人陈述存储格式，取出可用于庭审分析的正文。"""

    try:
        payload = json.loads(submission_json or "{}")
    except json.JSONDecodeError:
        return localize_internal_text(submission_json.strip())
    if isinstance(payload, dict):
        for key in ("statement", "content", "message", "text"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return localize_internal_text(value.strip())
    return ""


# 所属模块：庭审法官 Agent > 最终轮交接 > 角色化关注点规范化。
# 具体功能：`_normalize_review_focus` 按 USER/MERCHANT 选择主语，对两类常见关键异议给出稳定摘要，其余交给通用第三人称改写；空文本返回空。
# 上下游：上游是解析后的 submission statement 与 participant_role；下游是 `_third_person_review_focus` 或最终关注点列表。
# 系统意义：把“我方”明确归属到用户/商家，避免人工工作台把单方主张读成法官判断；特殊摘要只重述立场不判断真伪。
def _normalize_review_focus(participant_role: str, statement: str) -> str:
    """把“我方/我们”等第一人称陈述改写为可供审核员阅读的第三人称关注点。"""

    text = localize_internal_text(statement).strip()
    if not text:
        return ""
    role = participant_role.upper()
    if role == "USER":
        if "认可" in text and "退款" in text and "签收人身份" in text:
            return "用户认可退款方向，但要求复核签收人身份是否已核验清楚。"
        return _third_person_review_focus("用户", text)
    if role == "MERCHANT":
        if "不同意退款" in text and "物流签收" in text:
            return "商家不同意退款，主张物流签收记录足以证明已履约。"
        return _third_person_review_focus("商家", text)
    return _third_person_review_focus("当事人", text)


# 所属模块：庭审法官 Agent > 最终轮交接 > 通用第三人称改写。
# 具体功能：`_third_person_review_focus` 将我方/我们/我替换成 role_label，必要时添加“某方提出”，截到 180 字并规范句号。
# 上下游：上游是未命中特殊规则的用户/商家/未知角色陈述；下游是人工关注点列表。
# 系统意义：只改变叙述视角和长度，不改写事实内容或作可信度判断，保留“来源于哪一方”的审计语义。
def _third_person_review_focus(role_label: str, text: str) -> str:
    """保留当事人原意，只转换叙述视角，避免审核意见把一方主张误写成既定事实。"""

    normalized = text
    replacements = {
        "我方": role_label,
        "我们": role_label,
        "我": role_label,
    }
    for source, target in replacements.items():
        normalized = normalized.replace(source, target)
    normalized = normalized.strip("。；; ")
    if not normalized:
        return ""
    if not normalized.startswith(role_label):
        normalized = f"{role_label}提出：{normalized}"
    return normalized[:180].rstrip("，,；; ") + "。"


# 所属模块：庭审法官 Agent > Prompt 上下文 > 当前轮程序控制段。
# 具体功能：`_current_turn_context` 用确定性 `_is_opening_turn` 生成 ROUND_OPENED/CLOSED，并投影轮号、final 标志、状态、停止原因、轮摘要及冻结上下文存在性。
# 上下游：上游是 HearingRoundTurnRequest 程序字段；下游是 context contract 中必需 current_turn 段。
# 系统意义：模型能理解当前任务是开场还是封存后总结，但实际分支仍由 `_apply_court_guardrails` 重算，不能信任模型自行解释轮次。
def _current_turn_context(request: HearingRoundTurnRequest) -> dict[str, Any]:
    """提炼本轮庭审控制信息，告诉模型当前是开庭、结轮还是被强制收敛。"""

    return {
        "turn_source": "ROUND_OPENED" if _is_opening_turn(request) else "ROUND_CLOSED",
        "round_no": request.round_no,
        "final_round": request.final_round,
        "round_status": request.round_status,
        "stop_reason": request.stop_reason,
        "round_summary_json": request.round_summary_json,
        "has_frozen_courtroom_context": bool(request.courtroom_context),
    }


# 所属模块：庭审法官 Agent > Prompt 上下文 > 稳定案件身份段。
# 具体功能：`_case_identity_context` 只提供 case/workflow、订单/售后/物流引用、争议类型、风险和冻结卷宗版本，不混入双方自由文本。
# 上下游：上游是 Java HearingRoundTurnRequest；下游是 context contract 中必需 case_identity 段。
# 系统意义：身份与材料分段可防止对话内容覆盖案件关联键，也便于审计某次法官调用使用的确切 dossier_version。
def _case_identity_context(request: HearingRoundTurnRequest) -> dict[str, Any]:
    """提供案件编号、订单引用和风险等级等稳定身份字段，不混入可变的对话内容。"""

    return {
        "case_id": request.case_id,
        "workflow_id": request.workflow_id,
        "order_reference": request.order_id,
        "after_sales_reference": request.after_sale_id,
        "logistics_reference": request.logistics_id,
        "dispute_type": request.dispute_type,
        "risk_level": request.risk_level,
        "dossier_version": request.dossier_version,
    }


# 所属模块：庭审法官 Agent > Prompt 上下文 > 冻结接待卷宗选择。
# 具体功能：`_canonical_case_dossier` 优先返回 hearing bootstrap 时冻结的 intake_dossier、身份与开庭消息；仅为旧请求回退标题/描述/争议类型/风险。
# 上下游：上游是 request.courtroom_context 与兼容基础字段；下游是必需 canonical_case_dossier 段。
# 系统意义：庭审期间案情基线必须冻结，不能因接待室后续编辑而漂移；fallback 明确只为旧合同兼容，不提升为新版冻结来源。
def _canonical_case_dossier(request: HearingRoundTurnRequest) -> dict[str, Any]:
    """优先使用开庭时冻结的接待卷宗。

    庭审法官应围绕冻结卷宗审理；只有兼容旧请求时才退回标题和案件描述，避免把
    未经接待室确认的内容当成庭审事实。
    """

    intake_dossier = request.courtroom_context.get("intake_dossier")
    if isinstance(intake_dossier, dict) and intake_dossier:
        return {
            "source": "hearing_bootstrap_context",
            "intake_dossier": intake_dossier,
            "case_identity": request.courtroom_context.get("case_identity") or {},
            "courtroom_opening_messages": request.courtroom_context.get(
                "courtroom_opening_messages"
            )
            or [],
        }
    return {
        "case_story": {
            "title": request.title,
            "one_sentence_summary": request.case_description,
        },
        "dispute_focus": {
            "core_issue": request.dispute_type or "履约争议",
            "risk_level": request.risk_level,
        },
    }


# 所属模块：庭审法官 Agent > Prompt 上下文 > 冻结证据卷宗投影。
# 具体功能：`_actor_visible_evidence` 从 courtroom_context 读取开庭时冻结的 evidence_dossier，并连同事实矩阵、总体置信和交接备注投影；缺失时显式返回 not_available 空矩阵。
# 上下游：上游是 Java hearing bootstrap 权威快照；下游是 actor_visible_evidence 可选 ContextSection。
# 系统意义：法官只能引用已进入庭审冻结包的证据，不能回查某方私有证据室或把“当前数据库最新材料”混入已开始的庭审。
def _actor_visible_evidence(request: HearingRoundTurnRequest) -> dict[str, Any]:
    """构造法官可引用的证据视图，优先读取开庭上下文中的冻结证据卷宗和事实矩阵。"""

    evidence_dossier = request.courtroom_context.get("evidence_dossier")
    if isinstance(evidence_dossier, dict) and evidence_dossier:
        return {
            "source": "hearing_bootstrap_context",
            "evidence_dossier": evidence_dossier,
            "fact_evidence_matrix": evidence_dossier.get("fact_evidence_matrix") or [],
            "overall_confidence_score": evidence_dossier.get("overall_confidence_score"),
            "handoff_notes": evidence_dossier.get("handoff_notes"),
        }
    return {
        "source": "not_available",
        "fact_evidence_matrix": [],
    }


# 所属模块：庭审法官 Agent > Agent-to-Agent 协作 > 陪审内部提示投影。
# 具体功能：`_jury_a2a_notes` 只在列表非空时注入 notes，并附 SYSTEM_AUDIT_ONLY 可见性及“仅提示风险/遗漏/一致性，法官仍唯一产草案”的 usage_rule。
# 上下游：上游是 Java 持久化并放入 courtroom_context 的陪审 A2A 消息；下游是 jury_a2a_notes ContextSection，仅供法官模型参考。
# 系统意义：陪审 Agent 不直接面向当事人、不独立裁决，也不能通过 A2A 覆盖冻结证据或三轮程序；其意见必须在法官与人工审核链路中被重新权衡。
def _jury_a2a_notes(request: HearingRoundTurnRequest) -> dict[str, Any]:
    """注入陪审团 Agent 的内部风险提示，但明确其只辅助法官，不产生独立裁判。"""

    notes = request.courtroom_context.get("jury_a2a_notes")
    if isinstance(notes, list) and notes:
        return {
            "source": "agent_a2a_message",
            "visibility": "SYSTEM_AUDIT_ONLY",
            "usage_rule": "陪审团仅通过 A2A 给法官提供风险、遗漏证据和一致性关注点；法官仍是唯一裁决草案生成主体。",
            "notes": notes,
        }
    return {
        "source": "not_available",
        "notes": [],
    }


# 所属模块：庭审法官 Agent > Prompt 上下文 > 三轮程序硬规则说明。
# 具体功能：`_round_control_policy` 将三轮名称、第三轮方案确认含义、禁止自由辩论、最终轮必出草案、AI 非最终和人工最终决定编码成必需上下文。
# 上下游：上游是 request.round_no/final_round；下游是模型 round_control_policy 段，护栏节点再以同一规则确定性执行。
# 系统意义：第三轮是对拟处理方向确认/异议收集，不等于和解协议；Prompt 与代码双层约束防止模型无限追问或宣告最终判决。
def _round_control_policy(request: HearingRoundTurnRequest) -> dict[str, Any]:
    """把三轮庭审的固定程序转成提示词上下文，限制模型临时改成自由辩论或最终裁决。"""

    return {
        "structure": "法官主持的三轮结构化庭审",
        "round_no": request.round_no,
        "round_names": {
            "1": "事实陈述轮",
            "2": "证据解释/定向回应轮",
            "3": "方案确认轮：确认法官拟处理方向或说明异议",
        },
        "third_round_confirmation_policy": {
            "purpose": "双方对法官拟处理方向确认或说明异议",
            "parties_aligned_label": "双方一致",
            "not_settlement_agreement": "方案确认不是和解协议，也不是双方自行提出一致方案",
            "disagreement_capture": "任一方异议时，提取异议理由、待补信息和后续确认关注点",
        },
        "final_round_must_generate_draft": request.final_round or request.round_no >= 3,
        "free_debate_allowed": False,
        "non_final_ai_advice": True,
        "human_reviewer_final_decision": True,
    }


# 所属模块：庭审法官 Agent > Prompt 上下文 > Java 履约工具意图适配。
# 具体功能：`_execution_tool_intentions` 校验 courtroom_context 中每条声明，忽略非 dict；任一声明损坏时记录日志并整段舍弃，成功时复用 Harness proposal-only section 并还原为 dict。
# 上下游：上游是 Java ToolRegistry 暴露的只读 execution_tool_declarations；下游是可选 execution_tool_intentions Prompt 段。
# 系统意义：法官模型只能在草案中提议动作，不能调用 operation；格式异常采用整段失败关闭，避免只保留部分工具造成不一致方案。
def _execution_tool_intentions(request: HearingRoundTurnRequest) -> dict[str, Any] | None:
    """将已声明的履约工具意图提供给法官参考，不在庭审 Agent 内直接触发真实执行。"""

    raw_declarations = request.courtroom_context.get("execution_tool_declarations")
    if not isinstance(raw_declarations, list) or not raw_declarations:
        return None
    try:
        declarations = [
            ExecutionToolDeclaration.model_validate(item)
            for item in raw_declarations
            if isinstance(item, dict)
        ]
    except Exception as failure:
        LOGGER.warning(
            "execution tool declarations ignored: case_id=%s error_type=%s error=%s",
            request.case_id,
            type(failure).__name__,
            failure,
        )
        return None
    if not declarations:
        return None
    section = build_execution_tool_intention_section(declarations)
    return json.loads(section.content)


# 所属模块：庭审法官 Agent > 公开输出护栏 > 法官消息收敛与去终审化。
# 具体功能：`_sanitize_judge_message` 拒绝空消息；最终轮若仍像追问则替换为封存/草案提示，并把“最终裁决/审核员终审”等越权措辞改成非最终草案与后续确认。
# 上下游：上游是模型 message_text 与确定性 final_round 标志；下游是当事人可见 result.message_text。
# 系统意义：模型不能靠自然语言绕过状态字段；最终轮必须停止追问，所有轮次都要明确 AI 意见非最终且仍按平台流程确认。
def _sanitize_judge_message(text: str, *, final_round: bool) -> str:
    """清洗法官公开消息，避免出现“最终裁决”等越权表述。"""

    localized = localize_internal_text(text.strip())
    if not localized:
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "hearing round model returned an empty public message",
        )
    if final_round and _looks_like_more_questions(localized):
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "final hearing round cannot ask the parties another question",
        )
    if "最终裁决" in localized and "非最终裁决" not in localized:
        localized = localized.replace("最终裁决", "非最终裁决草案")
    localized = (
        localized.replace("并提交平台审核员终审", "并进入裁决草案与后续确认路径")
        .replace("提交平台审核员终审", "进入裁决草案与后续确认路径")
        .replace("由平台审核员终审", "进入后续确认")
        .replace("平台审核员终审", "后续确认")
        .replace("审核员终审", "后续确认")
        .replace("人类终审", "后续确认")
    )
    if final_round and "非最终" not in localized:
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "final-round public message must state that the proposal is non-final",
        )
    if final_round and "评审" not in localized:
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "final-round public message must state that the proposal enters AI review",
        )
    return localized


# 所属模块：庭审法官 Agent > 公开输出护栏 > 轮次摘要本地化。
# 具体功能：`_sanitize_round_summary` 把 None/空值规范成字符串、去首尾空白，并用共享码表替换内部英文枚举；不修改机器事件字段。
# 上下游：上游是普通/最终轮模型 round_summary；下游是 result.round_summary 和 Java 庭审记录。
# 系统意义：摘要面向业务人员可读，但它仍是非最终过程记录；事件类型、轮号和草案触发由独立确定性字段表达。
def _sanitize_round_summary(text: str) -> str:
    return localize_internal_text(str(text or "").strip())


def _sanitize_proposed_resolution_direction(text: str | None) -> str:
    localized = localize_internal_text(str(text or "").strip()).rstrip("。")
    if not localized or any(
        marker in localized for marker in ("将提出", "待确认", "后续再", "暂不提出")
    ):
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "round two must provide a concrete proposed resolution direction",
        )
    localized = localized.replace("最终裁决", "非最终拟处理方向")
    return localized


def _sanitize_final_proposed_resolution(text: str | None) -> str:
    """验收第三轮法官提交给统一评审员的非最终拟处理方案 V1。"""

    localized = localize_internal_text(str(text or "").strip()).rstrip("。")
    if not localized or any(
        marker in localized
        for marker in ("将提出", "待确认", "后续再", "暂不提出", "无法判断")
    ):
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "final round must provide a concrete final proposed resolution V1",
        )
    if "非最终" not in localized:
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "final proposed resolution V1 must explicitly be non-final",
        )
    if "最终裁决" in localized and "非最终裁决" not in localized:
        raise AgentOutputSchemaError(
            "hearing_round_turn",
            "final proposed resolution V1 cannot present itself as a final decision",
        )
    return localized


# 所属模块：庭审法官 Agent > 三轮状态机 > 开庭事件判定。
# 具体功能：`_is_opening_turn` 同时要求 round_status=OPEN、尚无 party_submissions、也无 stop_reason，三项都满足才视为开场。
# 上下游：上游是 Java 持久化的轮次状态；下游是当前轮 Prompt、护栏分支、fallback 和开场消息/问题。
# 系统意义：仅凭“没有陈述”不足以重发开场；已有停止原因的轮次必须进入收敛/异常处理，避免重复法官消息。
def _is_opening_turn(request: HearingRoundTurnRequest) -> bool:
    """判断本轮是否尚未收到双方陈述；开庭轮必须先发布统一开场和提问。"""

    return (
        request.round_status.upper() == "OPEN"
        and not request.party_submissions
        and not request.stop_reason
    )


# 所属模块：庭审法官 Agent > 三轮状态机 > 开场消息生成。
# 具体功能：`_opening_message` 优先复用 Java bootstrap 法官消息；否则按轮号选择主题，加入案件简述、双方并行陈述、禁止自由辩论和五分钟封存规则。
# 上下游：上游是 opening guardrail/fallback 与冻结 request；下游是 JUDGE_OPENING_READY 的公开 message_text。
# 系统意义：开场规则不由 LLM临时发挥，前后端看到并持久化同一套轮次程序；案件简述只作议题背景，不是事实认定。
def _opening_message(request: HearingRoundTurnRequest) -> str:
    """生成法官开庭发言，说明本轮主题、双方提交规则和材料封存条件。"""

    bootstrap_message = _bootstrap_judge_opening_message(request)
    if bootstrap_message:
        return bootstrap_message
    round_name = {
        1: "事实陈述轮",
        2: "证据解释与定向回应轮",
        3: "方案确认轮",
    }.get(request.round_no, "庭审陈述轮")
    case_brief = localize_internal_text(request.case_description).strip()
    focus = f"本案案情要点是：{case_brief}" if case_brief else "请双方围绕接待室卷宗和证据室材料说明关键事实。"
    return (
        f"小法庭现在开庭。第 {request.round_no} 轮是{round_name}。{focus}"
        "请用户和商家分别提交本轮说明；双方在本轮内并行陈述，不进行自由辩论。"
        "本轮双方都提交或 5 分钟时效届满后，系统会自动封存本轮材料。"
    )


# 所属模块：庭审法官 Agent > 三轮状态机 > Java 开场消息复用。
# 具体功能：`_bootstrap_judge_opening_message` 遍历 courtroom_opening_messages，返回第一条 sender_role=JUDGE 且非空的本地化内容，忽略损坏项和其他角色消息。
# 上下游：上游是 hearing bootstrap 冻结上下文；下游是 `_opening_message` 的最高优先级结果。
# 系统意义：避免 Python 重新生成与 Java 已入库开场不一致的第二版本，也不会把当事人消息误用为法官指令。
def _bootstrap_judge_opening_message(request: HearingRoundTurnRequest) -> str:
    """若 Java 开庭引导已写入法官消息，复用该消息以保持前后端和审理记录一致。"""

    messages = request.courtroom_context.get("courtroom_opening_messages")
    if not isinstance(messages, list):
        return ""
    for message in messages:
        if not isinstance(message, dict):
            continue
        sender_role = str(message.get("sender_role") or "").upper()
        content = localize_internal_text(str(message.get("content") or "").strip())
        if sender_role == "JUDGE" and content:
            return content
    return ""


# 所属模块：庭审法官 Agent > 三轮状态机 > 用户固定开场问题。
# 具体功能：`_opening_questions_for_user` 按第一轮事实经过、第二轮证据来源/真实性、第三轮拟方向最后确认返回一条角色化问题。
# 上下游：上游是 opening guardrail/fallback 的 round_no；下游是 questions_for_user，等待 Java 收集用户本轮 submission。
# 系统意义：问题与三轮目的严格对应，不要求用户作法律判断，也不会在第三轮重新开启新的证据调查。
def _opening_questions_for_user(request: HearingRoundTurnRequest) -> list[str]:
    """按轮次向用户索取事实、证据或最终确认信息，不要求用户作法律判断。"""

    if request.round_no >= 3:
        return ["请用户说明对当前证据、履约事实和拟处理方向是否还有最后确认意见。"]
    if request.round_no == 2:
        return ["请用户围绕证据来源、形成时间、真实性、完整性以及与争议事实的关联性补充说明。"]
    return ["请用户说明争议发生经过、签收或验货情况，以及希望平台优先核验的事实。"]


# 所属模块：庭审法官 Agent > 三轮状态机 > 商家固定开场问题。
# 具体功能：`_opening_questions_for_merchant` 按第一轮履约事实、第二轮记录来源与差异、第三轮拟方向最后确认返回一条商家问题。
# 上下游：上游是 opening guardrail/fallback 的 round_no；下游是 questions_for_merchant，等待 Java 收集商家本轮 submission。
# 系统意义：双方分别并行提交，不形成自由辩论；商家问题聚焦其可说明的履约链路，不预设用户主张错误。
def _opening_questions_for_merchant(request: HearingRoundTurnRequest) -> list[str]:
    """按轮次向商家索取履约记录、证据来源或最终确认信息。"""

    if request.round_no >= 3:
        return ["请商家说明对当前证据、履约事实和拟处理方向是否还有最后确认意见。"]
    if request.round_no == 2:
        return ["请商家围绕履约记录、证据来源、形成时间、真实性和与用户主张的差异补充说明。"]
    return ["请商家说明履约记录、发货或物流交接情况，以及与用户主张不一致的事实。"]


# 所属模块：庭审法官 Agent > 公开输出护栏 > 最终轮继续追问检测。
# 具体功能：`_looks_like_more_questions` 用有限中文标记识别“继续补充/下一轮/请某方补充”等延期措辞，返回布尔值供最终轮消息强制替换。
# 上下游：上游是本地化后的模型 message_text；下游是 `_sanitize_judge_message` 的第三轮收敛分支。
# 系统意义：模型可能因不确定而本能继续提问，但业务三轮上限必须由代码执行，随后把不确定性转交裁判草案与人工审核。
def _looks_like_more_questions(text: str) -> bool:
    """识别最终轮中不应继续追问的话术，防止模型拖延进入裁判草案和人工复核。"""

    return any(
        marker in text
        for marker in (
            "继续补充",
            "下一轮",
            "下轮",
            "请双方继续",
            "请用户补充",
            "请商家补充",
        )
    )
