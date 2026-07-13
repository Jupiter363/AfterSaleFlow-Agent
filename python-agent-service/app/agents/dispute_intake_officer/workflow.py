# 文件作用：编排接待室单轮 LangGraph：提取当前私聊上下文、结构化理解案情、确定性生成卷宗，并校验能否交接证据室。

from __future__ import annotations

import copy
import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
    SUBJECTIVE_RESPONDENT_SOURCE,
    _question_targets_resolved_intake_field,
)
from app.harness.context_pack import build_context_pack
from app.harness.narrative_policy import rewrite_platform_narrative
from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.schemas import IntakeTurnRequest, IntakeTurnResult


LOGGER = logging.getLogger(__name__)


class IntakeTurnGraphState(TypedDict):
    """接待室单轮对话图的状态。

    这是用户/商家每说一句话后，接待官 Agent 在图中传递的“工作台”。
    `Annotated[list[str], operator.add]` 告诉 LangGraph 合并节点局部更新时追加执行轨迹；
    `NotRequired` 表示字段由后续节点写入，初始 state 不必提供。
    """

    request: dict[str, Any]
    executed_nodes: Annotated[list[str], operator.add]
    source_text: str
    actor_role: str
    memory_frame: dict[str, Any]
    llm_output: NotRequired[IntakeCaseDetailLlmOutput]
    room_utterance: NotRequired[str]
    dossier_patch: NotRequired[dict[str, Any]]
    scroll_snapshot: NotRequired[dict[str, Any]]
    canvas_operations: NotRequired[list[dict[str, Any]]]
    admission_recommendation: NotRequired[str]
    missing_fields: NotRequired[list[str]]
    knowledge_query_intent: NotRequired[bool]
    knowledge_answer_mode: NotRequired[str]
    confidence: NotRequired[float]


class IntakeTurnWorkflow:
    """争议接待官的 LangGraph 工作流。

    run(...) 是 Java 服务调用的入口：
    Java 传入 IntakeTurnRequest -> 图执行 -> 返回 IntakeTurnResult。
    """

    # 所属模块：接待室 Agent > 单轮 LangGraph > 工作流实例初始化。
    # 具体功能：`__init__` 把 HarnessModelRunner 交给节点工厂并立即编译固定四节点图；model_runner 会被闭包捕获，不放进可序列化 graph state。
    # 上下游：上游是 FastAPI/Agent 服务依赖装配；下游是 `run` 对同一编译图重复 invoke。
    # 系统意义：接待室拓扑固定为“加载→理解→卷宗→就绪校验”，模型不能跳过确定性卷宗 Skill 或自行进入证据/裁判职责。
    def __init__(self, model_runner: Any | None = None) -> None:
        self._graph = build_intake_turn_graph(model_runner)

    # 所属模块：接待室 Agent > 单轮 LangGraph > Java 调用门面。
    # 具体功能：`run` 把 IntakeTurnRequest 转成仅含基础 Python 类型的初始 state，调用编译图，再从最终状态逐字段构造严格 IntakeTurnResult。
    # 上下游：上游是 Java 为当前参与方私聊回合提供的表单、消息、最近对话、旧卷宗与可信 agent_context；下游是房间话术、卷宗 patch/快照/画布操作及可提交建议。
    # 系统意义：LangGraph 内部工作字段不会整包泄露给 Java；返回面只包含接待职责产物，不含正式证据判断、责任结论或履约承诺。
    def run(self, request: IntakeTurnRequest) -> IntakeTurnResult:
        # model_dump(mode="json") 会把 Pydantic 模型转成普通 dict/list/str/int，
        # 这样 LangGraph 状态里不会混入复杂对象，便于序列化和测试。
        initial_state: IntakeTurnGraphState = {
            "request": request.model_dump(mode="json"),
            "executed_nodes": [],
            "source_text": "",
            "actor_role": "USER",
            "memory_frame": {},
        }
        result = self._graph.invoke(initial_state)
        return IntakeTurnResult(
            room_utterance=result["room_utterance"],
            dossier_patch=result["dossier_patch"],
            scroll_snapshot=result["scroll_snapshot"],
            canvas_operations=result["canvas_operations"],
            memory_frame=result["memory_frame"],
            admission_recommendation=result["admission_recommendation"],  # type: ignore[arg-type]
            missing_fields=result["missing_fields"],
            knowledge_query_intent=bool(result.get("knowledge_query_intent", False)),
            knowledge_answer_mode=result.get("knowledge_answer_mode", "NONE"),  # type: ignore[arg-type]
            confidence=float(result["confidence"]),
        )


# 所属模块：接待室 Agent > 单轮 LangGraph > 拓扑构建与编译。
# 具体功能：`build_intake_turn_graph` 以 IntakeTurnGraphState 注册四个节点和 START/END 固定边，并用 `_reason_with_llm_node` 将运行器绑定成 LangGraph 可调用节点。
# 上下游：上游是 `IntakeTurnWorkflow.__init__`；下游是 compile 后提供 invoke 的图对象，节点局部 dict 更新由 LangGraph 合并。
# 系统意义：模型推理被夹在上下文准备与确定性后处理之间；任何回合都必须经过 readiness 校验，不能让 LLM 直接决定流程流转。
def build_intake_turn_graph(model_runner: Any | None = None):
    """组装接待室图：加载上下文 -> LLM 理解 -> 渲染卷宗 -> 校验是否可流转。"""

    # add_node 只注册名字与 Python callable；add_edge 才定义执行顺序，compile 后拓扑不可被请求临时修改。
    builder = StateGraph(IntakeTurnGraphState)
    builder.add_node("load_context", _load_context)
    builder.add_node("reason_with_llm", _reason_with_llm_node(model_runner))
    builder.add_node("render_case_detail_dossier", _render_case_detail_dossier)
    builder.add_node("validate_readiness", _validate_readiness)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "reason_with_llm")
    builder.add_edge("reason_with_llm", "render_case_detail_dossier")
    builder.add_edge("render_case_detail_dossier", "validate_readiness")
    builder.add_edge("validate_readiness", END)
    return builder.compile()


# 所属模块：接待室 Agent > 单轮 LangGraph > load_context 节点。
# 具体功能：`_load_context` 优先取当前消息、首轮才回退表单描述；actor_role 优先取可信 agent_context，再生成只描述窗口数量/顺序的 memory_frame 元数据。
# 上下游：上游是初始 state.request；下游把 source_text/actor_role/memory_frame 局部写回，供 LLM 节点、知识问句判断和最终响应使用。
# 系统意义：当前参与方身份不由消息正文自报；这里不把另一参与方私聊或无限历史复制进状态，落实接待室会话隔离。
def _load_context(state: IntakeTurnGraphState) -> dict[str, Any]:
    """从请求里抽取本轮最重要的文本、角色和记忆摘要。"""

    request = state["request"]
    agent_context = request["agent_context"]
    current = request.get("current_user_message") or {}
    initial_facts = request.get("initial_case_facts") or {}
    source_text = str(
        current.get("text") or initial_facts.get("form_description") or ""
    )
    actor_role = str(
        agent_context.get("actor_role")
        or current.get("role")
        or (request.get("initial_case_facts") or {}).get("initiator_role")
        or "USER"
    )
    recent_messages = request.get("recent_dialogue_messages") or []
    memory_frame = {
        "context_contract": "intake_turn_context.v2",
        "dialogue_window": "3_ROUNDS_6_MESSAGES",
        "dialogue_order": "AGENT_THEN_PARTY",
        "recent_dialogue_count": len(recent_messages),
        "dialogue_message_count": len(recent_messages) + (1 if current else 0),
        "current_message_sequence": current.get("sequence_no"),
    }
    return {
        "source_text": source_text,
        "actor_role": actor_role,
        "memory_frame": memory_frame,
        "executed_nodes": ["load_context"],
    }


# 所属模块：接待室 Agent > 单轮 LangGraph > LLM 节点工厂。
# 具体功能：`_reason_with_llm_node` 通过闭包把可选 model_runner 固定到 `reason_with_llm(state)`，使依赖不进入 TypedDict/检查点，仅业务 state 在图中流动。
# 上下游：上游是构图函数注册 reason_with_llm 节点；下游是 LangGraph 运行时调用内部函数并接收 llm_output 局部更新。
# 系统意义：闭包分离“服务依赖”和“案件状态”，避免客户端数据替换运行器，也便于测试注入受控模型替身。
def _reason_with_llm_node(model_runner: Any | None):
    """创建 LLM 推理节点。

    这里返回内部函数 reason_with_llm，是 Python 闭包写法：
    外层把 model_runner 固定住，内层函数交给 LangGraph 在运行时调用。
    """

    # 所属模块：接待室 Agent > 单轮 LangGraph > reason_with_llm 节点执行器。
    # 具体功能：`reason_with_llm` 清除无主观来源的“对方态度”，按 intake context contract 组装最小段，使用可信 Profile 选择模板并要求一次调用输出 IntakeCaseDetailLlmOutput。
    # 上下游：上游是 load_context 后的 state 与 Java 请求；下游仅写 `llm_output`，后续 `_render_case_detail_dossier` 才决定可持久化卷宗形态。
    # 系统意义：LLM 负责自然语言理解但不能直接写库；配置缺失、Schema 错误和未知异常都失败关闭，且日志用 invocation_id 关联而不降级成伪造结论。
    def reason_with_llm(state: IntakeTurnGraphState) -> dict[str, Any]:
        request = state["request"]
        agent_context = request["agent_context"]
        if model_runner is None:
            raise AgentServiceUnavailable("intake turn model runner is not configured")
        try:
            prompt_initial_facts = _subjective_only_initial_case_facts(
                request.get("initial_case_facts") or {}
            )
            prompt_previous_detail = _subjective_only_snapshot(
                request.get("previous_case_detail") or {}
            )
            # 先建立最小必需身份/最近消息；可选段只在请求确实携带时加入，避免用空对象覆盖合同语义。
            context_sources = {
                "case_identity": _case_identity_context(request, state),
                "recent_dialogue_messages": _compact_dialogue_window(
                    request.get("recent_dialogue_messages") or []
                ),
            }
            # context_pack 会按 prompt_contracts 的配置筛选上下文：
            # 哪些必填、哪些只展示不进 prompt、哪些优先级更高，都在 contract 中约束。
            if request.get("current_user_message") is not None:
                context_sources["current_user_message"] = request[
                    "current_user_message"
                ]
            if prompt_previous_detail:
                context_sources["previous_case_detail"] = prompt_previous_detail
            if request.get("initial_case_facts") is not None:
                context_sources["initial_case_facts"] = prompt_initial_facts
            context_pack = build_context_pack(
                "intake_turn_case_detail",
                context_sources,
                actor_role=state["actor_role"],
            )
            generation = model_runner.invoke_structured(
                node_name="intake_turn_case_detail",
                case_data={
                    "context_contract": "intake_turn_context.v2",
                },
                output_type=IntakeCaseDetailLlmOutput,
                agent_context=agent_context,
                prompt_profile_id=agent_context.get("prompt_profile_id"),
                context_pack=context_pack,
            )
            return {
                "llm_output": generation.value,
                "executed_nodes": ["reason_with_llm"],
            }
        # 统一记录上下文后再分类异常：已知服务/Schema 错误保留类型，其余包装为服务不可用供 API 稳定映射。
        except Exception as failure:
            LOGGER.warning(
                "intake turn LLM reasoning failed closed: case_id=%s turn_source=%s "
                "agent_invocation_id=%s error_type=%s error=%s",
                request.get("case_id"),
                request.get("turn_source"),
                agent_context.get("agent_invocation_id"),
                type(failure).__name__,
                failure,
                exc_info=True,
            )
            if isinstance(failure, (AgentOutputSchemaError, AgentServiceUnavailable)):
                raise
            raise AgentServiceUnavailable("intake turn LLM request failed") from failure

    return reason_with_llm


# 所属模块：接待室 Agent > 单轮 LangGraph > 首轮表单态度隔离。
# 具体功能：`_subjective_only_initial_case_facts` 深拷贝表单 seed，仅在 respondent_attitude_seed 明确标记发起方主观来源时保留，否则删除该字段。
# 上下游：上游是 reason_with_llm 的 initial_case_facts；下游是 ContextPack 的 initial_case_facts 段。
# 系统意义：旧接口可能把正式答辩状态混入首轮表单；接待私聊只能记录发起方“认为对方怎样”，不能冒充对方已正式表态。
def _subjective_only_initial_case_facts(seed: dict[str, Any]) -> dict[str, Any]:
    """Remove response-state seeds that were not derived from initiator text."""

    sanitized = copy.deepcopy(seed)
    # deepcopy 是深拷贝：复制嵌套 dict/list，避免清理 prompt 输入时改到原始请求对象。
    attitude = sanitized.get("respondent_attitude_seed")
    if not _has_subjective_source(attitude):
        sanitized.pop("respondent_attitude_seed", None)
    return sanitized


# 所属模块：接待室 Agent > 单轮 LangGraph > 旧卷宗态度隔离。
# 具体功能：`_subjective_only_snapshot` 深拷贝 previous_case_detail，只保留带 SUBJECTIVE_RESPONDENT_SOURCE 的 respondent_attitude，移除旧流程正式响应状态。
# 上下游：上游是 reason_with_llm 的上一版卷宗；下游是 previous_case_detail ContextSection。
# 系统意义：后续接待回合不能因历史字段污染而向发起方泄露/确认另一方正式答辩，也不能据此提前判断责任。
def _subjective_only_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Do not let legacy formal response state contaminate private-room reasoning."""

    sanitized = copy.deepcopy(snapshot)
    attitude = sanitized.get("respondent_attitude")
    if not _has_subjective_source(attitude):
        sanitized.pop("respondent_attitude", None)
    return _compact_case_detail_snapshot(sanitized)


# 所属模块：接待室 Agent > Prompt 压缩 > 最近对话窗口投影。
# 具体功能：`_compact_dialogue_window` 仅查看最后 5 条消息，跳过非 dict 项，只保留 role/text/sequence_no，删除 message_id、source 等传输元数据。
# 上下游：上游是 reason_with_llm 收到的 recent_dialogue_messages；下游是 intake ContextPack 的 recent_dialogue_messages 段。
# 系统意义：接待房间按 Agent 开始的三轮窗口提供连续语义即可，重复 ID/来源不帮助模型理解，却会增加 Token 并扩大可见元数据面。
def _compact_dialogue_window(messages: list[Any]) -> list[dict[str, Any]]:
    """Keep the three system-started turns while dropping transport metadata."""

    compact: list[dict[str, Any]] = []
    for message in messages[-5:]:
        if not isinstance(message, dict):
            continue
        compact.append(
            {
                "role": message.get("role"),
                "text": message.get("text"),
                "sequence_no": message.get("sequence_no"),
            }
        )
    return compact


# 所属模块：接待室 Agent > Prompt 压缩 > 上一版案件看板最小投影。
# 具体功能：`_compact_case_detail_snapshot` 从完整旧卷宗白名单选取身份引用、故事、双方立场、诉求、争议、风险、缺口、质量、受理和交接字段，并分别限制时间线/文本列表数量、删除空值及重复旧别名。
# 上下游：上游是已深拷贝且清除越界 respondent_attitude 的 previous_case_detail；下游是 LLM 的 previous_case_detail Prompt 段，模型 patch 之后仍由 DossierSkill 合并回完整持久化看板。
# 系统意义：Prompt 投影不等于删除数据库字段；它去掉原话 provenance、展示字段和重复解释，降低单轮 Token/延迟，同时完整旧卷宗仍作为确定性合并基线。
def _compact_case_detail_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Project the board to the facts needed for one incremental model turn.

    The persisted board remains complete.  The prompt projection deliberately
    drops legacy aliases, raw-statement provenance, duplicated explanations and
    presentation-only fields.  The dossier skill merges the model's patch back
    into the complete previous board after the single model call.
    """

    if not isinstance(snapshot, dict):
        return {}

    compact: dict[str, Any] = {}
    _copy_mapping_fields(compact, snapshot, "references")

    story = snapshot.get("case_story")
    if isinstance(story, dict):
        compact["case_story"] = _non_empty_mapping(
            {
                "title": story.get("title"),
                "one_sentence_summary": story.get("one_sentence_summary"),
            }
        )

    positions = snapshot.get("party_positions")
    if isinstance(positions, dict):
        compact["party_positions"] = _non_empty_mapping(
            {
                "initiator_position": positions.get("initiator_position"),
                "respondent_position": positions.get("respondent_position"),
                "user_claim": positions.get("user_claim"),
                "merchant_claim": positions.get("merchant_claim"),
                "platform_observation": positions.get("platform_observation"),
            }
        )

    claim = snapshot.get("claim_resolution")
    if isinstance(claim, dict):
        compact["claim_resolution"] = _non_empty_mapping(
            {
                "initiator_role": claim.get("initiator_role"),
                "requested_resolution": claim.get("requested_resolution"),
                "requested_amount": claim.get("requested_amount"),
                "requested_items": claim.get("requested_items"),
                "request_reason": claim.get("request_reason"),
                "normalized_statement": claim.get("normalized_statement"),
            }
        )

    _copy_mapping_fields(compact, snapshot, "respondent_attitude")

    core = snapshot.get("dispute_core_state")
    if isinstance(core, dict):
        compact["dispute_core_state"] = _non_empty_mapping(
            {
                "core_conflict": core.get("core_conflict"),
                "conflict_type": core.get("conflict_type"),
                "facts_in_dispute": _unique_strings(
                    core.get("facts_in_dispute") or [], limit=6
                ),
                "next_verification_focus": _unique_strings(
                    _fact_verification_items(
                        core.get("next_verification_focus") or []
                    ),
                    limit=4,
                ),
            }
        )

    risk = snapshot.get("risk_assessment")
    if isinstance(risk, dict):
        compact["risk_assessment"] = _non_empty_mapping(
            {
                "case_grade": risk.get("case_grade") or risk.get("risk_level"),
                "risk_signals": _unique_strings(
                    risk.get("risk_signals") or [], limit=6
                ),
                "reason": risk.get("reason") or risk.get("reasoning"),
            }
        )

    missing = snapshot.get("missing_information")
    if isinstance(missing, dict):
        compact["missing_information"] = {
            "blocking_gaps": _unique_strings(
                missing.get("blocking_gaps") or [], limit=6
            ),
            "nice_to_have_gaps": _unique_strings(
                missing.get("nice_to_have_gaps")
                or missing.get("non_blocking_supplements")
                or [],
                limit=4,
            ),
            "next_questions": _unique_strings(
                missing.get("next_questions") or [], limit=10
            ),
        }

    quality = snapshot.get("intake_quality")
    if isinstance(quality, dict):
        compact["intake_quality"] = _non_empty_mapping(
            {
                "score": quality.get("score"),
                "ready_for_next_step": quality.get("ready_for_next_step"),
            }
        )

    admission = snapshot.get("admission")
    if isinstance(admission, dict):
        compact["admission"] = _non_empty_mapping(
            {
                "recommendation": admission.get("recommendation"),
                "reason": admission.get("reason") or admission.get("reasoning"),
            }
        )

    handoff = snapshot.get("handoff_notes")
    if isinstance(handoff, dict):
        compact["handoff_notes"] = _non_empty_mapping(
            {
                "remark_status": handoff.get("remark_status"),
                "latest_remark": handoff.get("latest_remark"),
            }
        )
    # 最外层再次删除空分区；False 和 0 不在右侧元组中，因此合法布尔/评分不会被误删。
    return {key: value for key, value in compact.items() if value not in ({}, [], "", None)}


# 所属模块：接待室 Agent > Prompt 压缩 > 完整映射字段按需复制。
# 具体功能：`_copy_mapping_fields` 仅当 source[key] 是非空 dict 时写入 target，用于保留 references/respondent_attitude 等已具备稳定子合同的分区。
# 上下游：上游是 `_compact_case_detail_snapshot` 的白名单字段选择；下游是压缩后的 previous_case_detail。
# 系统意义：不对未知标量做宽松包装；调用链已对 snapshot 深拷贝，因此这里共享子 dict 不会修改持久化原对象。
def _copy_mapping_fields(target: dict[str, Any], source: dict[str, Any], key: str) -> None:
    value = source.get(key)
    if isinstance(value, dict) and value:
        target[key] = value


# 所属模块：接待室 Agent > Prompt 压缩 > 子对象空值清理。
# 具体功能：`_non_empty_mapping` 用字典推导式删除 None、空串、空 list、空 dict，保留数值 0 与布尔 False 等有业务含义的值。
# 上下游：上游是故事、立场、诉求、风险、质量等显式字段投影；下游是紧凑卷宗各子对象。
# 系统意义：减少无意义 JSON 键和 Token，同时避免 Python 普通 `if item` 误删 score=0 或 ready_for_next_step=False。
def _non_empty_mapping(value: dict[str, Any]) -> dict[str, Any]:
    return {
        key: item
        for key, item in value.items()
        if item not in (None, "", [], {})
    }


# 所属模块：接待室 Agent > Prompt 压缩 > 文本列表语义去重与限额。
# 具体功能：`_unique_strings` 只处理 list，将每项转字符串并 trim，以“删除全部空白后的文本”作为去重键，保留首次原格式，达到 limit 即停止。
# 上下游：上游是旧卷宗事实、核验重点、风险、缺口和 prior questions；下游是压缩 ContextSection 中稳定、有限的文本数组。
# 系统意义：可识别仅空格/换行不同的重复问题，减少模型重复追问；不使用 set 输出，因而保持业务优先顺序可预测。
def _unique_strings(value: Any, *, limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    seen: set[str] = set()
    for item in value:
        text = str(item or "").strip()
        normalized = "".join(text.split())
        if not text or normalized in seen:
            continue
        seen.add(normalized)
        result.append(text)
        if len(result) >= limit:
            break
    return result


def _fact_verification_items(value: Any) -> list[str]:
    """Remove workflow/status prose before a previous board reaches the LLM."""

    if not isinstance(value, list):
        return []
    process_markers = (
        "信息完整度",
        "提交阈值",
        "可以提交",
        "等待接待官",
        "案件详情整理",
        "进入下一步",
        "后续流程",
        "ready_for_next_step",
        "WAITING_FOR_REMARK",
        "NOT_READY",
    )
    return [
        str(item).strip()
        for item in value
        if str(item or "").strip()
        and not any(marker in str(item) for marker in process_markers)
    ]


# 所属模块：接待室 Agent > 单轮 LangGraph > 态度来源精确判定。
# 具体功能：`_has_subjective_source` 只接受 dict 且 source 去空白后精确等于 SUBJECTIVE_RESPONDENT_SOURCE；不按字段内容猜测来源。
# 上下游：上游是两类卷宗清洗函数；下游决定保留或删除 respondent_attitude 字段。
# 系统意义：来源标签是信任边界，缺失/未知来源一律保守删除，避免单方主张升级成正式案件状态。
def _has_subjective_source(value: Any) -> bool:
    """确认“对方态度”是否确实来自发起方的主观陈述，而非旧流程遗留的正式状态。"""

    return (
        isinstance(value, dict)
        and str(value.get("source") or "").strip()
        == SUBJECTIVE_RESPONDENT_SOURCE
    )


# 所属模块：接待室 Agent > 单轮 LangGraph > 确定性卷宗渲染节点。
# 具体功能：`_render_case_detail_dossier` 重新验收原请求，将 LLM 各字段交给 CaseDetailDossierSkill 做合并、补全、裁剪与评分，再过滤越界证据提问并归一化知识问句模式。
# 上下游：上游是 reason_with_llm 写入的 IntakeCaseDetailLlmOutput；下游写回房间话术、dossier_patch、完整 scroll_snapshot、画布操作、缺失项和置信度。
# 系统意义：模型输出不是可直接持久化卷宗；Skill 才是接待业务 Schema 和旧卷宗合并规则的权威，确保模型不能删除历史事实或伪造就绪状态。
def _render_case_detail_dossier(state: IntakeTurnGraphState) -> dict[str, Any]:
    """把 LLM 的原始结构化输出交给 deterministic skill 做业务归一化。

    设计重点：LLM 负责理解自然语言；CaseDetailDossierSkill 负责兜底、裁剪、
    字段补全和安全边界，避免把模型原样输出直接写入案件卷宗。
    """

    request = IntakeTurnRequest.model_validate(state["request"])
    output = state["llm_output"]
    rendered = CaseDetailDossierSkill().render(
        request=request,
        room_utterance=output.room_utterance,
        llm_case_detail=output.case_detail,
        llm_dossier_patch=output.dossier_patch,
        llm_scroll_snapshot=output.scroll_snapshot,
        llm_canvas_operations=output.canvas_operations,
        llm_admission_recommendation=output.admission_recommendation,
        llm_missing_fields=output.missing_fields,
        llm_confidence=output.confidence,
    )
    room_utterance = _enforce_intake_question_boundary(
        output.room_utterance,
        rendered.scroll_snapshot,
    )
    return {
        "room_utterance": room_utterance,
        "dossier_patch": rendered.dossier_patch,
        "scroll_snapshot": rendered.scroll_snapshot,
        "canvas_operations": rendered.canvas_operations,
        "admission_recommendation": rendered.admission_recommendation,
        "missing_fields": rendered.missing_fields,
        "knowledge_query_intent": (
            output.knowledge_query_intent or _is_knowledge_query(state["source_text"])
        ),
        "knowledge_answer_mode": (
            "STUB"
            if output.knowledge_query_intent
            or _is_knowledge_query(state["source_text"])
            else output.knowledge_answer_mode
        ),
        "confidence": rendered.confidence,
        "executed_nodes": ["render_case_detail_dossier"],
    }


# 所属模块：接待室 Agent > 单轮 LangGraph > 房间职责话术护栏。
# 具体功能：`_enforce_intake_question_boundary` 在卷宗已就绪时只询问交接备注；未就绪时检测并移除“上传截图/视频/凭证”等证据室问题，优先改用 Skill 生成的安全案情问题。
# 上下游：上游是模型 room_utterance 与确定性 scroll_snapshot；下游是用户在接待室实际看到的回复。
# 系统意义：材料必须在证据室按可见性和附件协议提交；接待 Agent 不能诱导用户在私聊文本中绕开正式证据链路，也不能继续阻塞已可提交案件。
def _enforce_intake_question_boundary(
    utterance: str,
    case_detail: dict[str, Any],
) -> str:
    """限制接待室话术只收集案情，不抢占证据室职责。

    当卷宗已满足提交条件时，接待官只询问是否还有交接备注；未满足时也不会把“上传
    证据”作为接待室追问，避免用户在错误房间提交材料而绕过证据可见性流程。
    """

    quality = case_detail.get("intake_quality")
    ready = isinstance(quality, dict) and quality.get("ready_for_next_step") is True
    if ready:
        handoff_notes = case_detail.get("handoff_notes")
        remark_status = (
            str(handoff_notes.get("remark_status") or "")
            if isinstance(handoff_notes, dict)
            else ""
        )
        if remark_status in {"HAS_REMARKS", "NO_EXTRA_REMARKS"}:
            return "已收到备注，当前案情信息已经可以提交。"
        return (
            "我已了解大致案情，当前信息已经可以提交。"
            "请问还有没有需要备注给证据书记官或后续审理环节的案情内容？"
        )

    evidence_markers = (
        "截图",
        "照片",
        "视频",
        "聊天记录",
        "沟通记录",
        "录音",
        "凭证",
        "证明材料",
        "证据材料",
        "上传",
        "补交",
        "提供证据",
        "提供材料",
        "提交材料",
    )
    repeats_resolved_field = _question_targets_resolved_intake_field(
        utterance,
        case_detail,
    )
    if (
        not repeats_resolved_field
        and not any(marker in utterance for marker in evidence_markers)
    ):
        return utterance

    missing = case_detail.get("missing_information")
    questions = missing.get("next_questions") if isinstance(missing, dict) else []
    safe_questions = [
        str(question)
        for question in questions or []
        if question and not any(marker in str(question) for marker in evidence_markers)
        and not _question_targets_resolved_intake_field(question, case_detail)
    ]
    if safe_questions:
        return "我已记录本轮补充。为了继续梳理案情，请补充：" + " ".join(
            safe_questions[:3]
        )
    return (
        "我已记录本轮补充。为了继续梳理案情，请说明事情发生的时间、经过、"
        "当前处理状态、你的诉求以及你所了解的对方态度。"
    )


# 所属模块：接待室 Agent > 单轮 LangGraph > 最终就绪一致性节点。
# 具体功能：`_validate_readiness` 仅信任当前 CaseDetailDossierSkill.schema_version 的 intake_quality；ready 时补齐“已收到备注/可以提交/可补交接备注”等必要话术，旧版快照走兼容轨迹不改文案。
# 上下游：上游是卷宗渲染后的 scroll_snapshot 与 room_utterance；下游是 END 前最后一次局部更新和最终 IntakeTurnResult。
# 系统意义：结构化 ready 标志与用户可见提示必须一致，避免后台允许提交但 Agent 仍无限追问，或话术声称可提交而权威评分未达阈值。
def _validate_readiness(state: IntakeTurnGraphState) -> dict[str, Any]:
    """最后一道可流转性校验。

    如果卷宗质量已经达到 ready_for_next_step，就确保对话话术明确告知可提交；
    否则保持上游问题继续收集信息。
    """

    snapshot = state["scroll_snapshot"]
    if snapshot.get("schema_version") != CaseDetailDossierSkill.schema_version:
        return {"executed_nodes": ["validate_legacy_readiness"]}
    quality = snapshot.get("intake_quality")
    ready = isinstance(quality, dict) and quality.get("ready_for_next_step") is True
    if ready:
        additions: list[str] = []
        utterance = state["room_utterance"]
        handoff_notes = snapshot.get("handoff_notes")
        remark_status = (
            str(handoff_notes.get("remark_status") or "")
            if isinstance(handoff_notes, dict)
            else ""
        )
        if remark_status in {"HAS_REMARKS", "NO_EXTRA_REMARKS"}:
            if "收到备注" not in utterance and "已收到" not in utterance:
                additions.append("已收到备注，我会把这部分一起交接给证据书记官。")
            if not additions:
                return {"executed_nodes": ["validate_readiness"]}
            return {
                "room_utterance": utterance + " " + " ".join(additions),
                "executed_nodes": ["validate_readiness"],
            }
        if "已了解大致案情" not in utterance:
            additions.append("我已了解大致案情。")
        if "可以提交" not in utterance and "可提交" not in utterance:
            additions.append("当前信息已经可以提交。")
        if "备注" not in utterance:
            additions.append(
                "请问还有没有需要备注给证据书记官或后续审理环节的内容？"
                "如果没有，可以直接回复“没有补充”。"
            )
        if not additions:
            return {"executed_nodes": ["validate_readiness"]}
        return {
            "room_utterance": utterance + " " + " ".join(additions),
            "executed_nodes": ["validate_readiness"],
        }
    return {"executed_nodes": ["validate_readiness"]}


# 所属模块：接待室 Agent > 单轮 LangGraph > 显式降级结果构造器。
# 具体功能：`_fallback_output` 仅记录原话/平台转述、最小引用和待补字段，用关键词保守识别诉求，固定低分/低置信/NEED_MORE_INFO，不生成对方态度或事实认定。
# 上下游：上游是测试或显式降级策略传入的已加载 state（主 reason 节点当前对服务错误失败关闭）；下游仍需经过 CaseDetailDossierSkill 和 readiness 护栏才可返回。
# 系统意义：依赖故障时也不能编造一个“完整卷宗”；低质量标记确保案件留在补充/人工路径而不是自动流转。
def _fallback_output(state: IntakeTurnGraphState) -> IntakeCaseDetailLlmOutput:
    """在 LLM 不可用时生成保守的接待结果。

    该结果只记录当事人的原始表述和待补信息，不伪造对方态度、事实认定或受理结论，
    让后续回合或人工处理能够安全补全案件。
    """

    request = state["request"]
    source_text = state["source_text"]
    platform_text = rewrite_platform_narrative(
        source_text,
        actor_role=state["actor_role"],
    )
    seed = request.get("initial_case_facts") or {}
    requested_outcome = seed.get("requested_outcome_hint") or _requested_outcome_from_text(
        source_text
    )
    knowledge_query = _is_knowledge_query(source_text)
    if request.get("current_user_message") is None:
        utterance = (
            "我已根据发起表单建立案件详情。"
            "为了继续梳理，请先补充表单中仍不清楚的事情经过、当前状态、处理诉求或对方态度。"
        )
    else:
        utterance = (
            "我已先把你的补充安全记录下来，并整理为右侧案件详情。"
            "为了继续推进，请补充仍不清楚的事情经过、当前状态、处理诉求或对方态度。"
        )
    if knowledge_query:
        utterance += (
            " 关于平台规则和处理时效，我先按通用流程解释；"
            "真实知识库插件后续接入后会给出更精确的规则引用。"
        )
    return IntakeCaseDetailLlmOutput(
        room_utterance=utterance,
        case_detail={
            "schema_version": CaseDetailDossierSkill.schema_version,
            "case_story": {
                "title": "待完善履约争议",
                "one_sentence_summary": platform_text,
            },
            "references": {
                "order_reference": seed.get("order_reference") or "",
                "after_sales_reference": seed.get("after_sales_reference") or "",
                "logistics_reference": seed.get("logistics_reference") or "",
            },
            "party_positions": {
                "user_claim": platform_text if state["actor_role"] != "MERCHANT" else "",
                "merchant_claim": platform_text if state["actor_role"] == "MERCHANT" else "",
                "raw_statement": (
                    source_text if request.get("current_user_message") is not None else ""
                ),
                "platform_observation": "",
            },
            "dispute_focus": {
                "core_issue": "UNKNOWN",
                "key_conflicts": [],
                "facts_to_verify": [],
            },
            "requested_resolution": {
                "requested_outcome": requested_outcome,
                "expected_resolution_text": "",
            },
            "risk_assessment": {
                "case_grade": "LOW",
                "risk_signals": [],
                "reasoning": "",
            },
            "missing_information": {
                "blocking_gaps": [],
                "nice_to_have_gaps": [],
                "next_questions": [],
            },
            "intake_quality": {
                "score": 45,
                "threshold": 85,
                "ready_for_next_step": False,
                "score_breakdown": {
                    "references": 5,
                    "event_story": 10,
                    "party_positions": 10,
                    "requested_resolution": 5,
                    "risk_and_conflicts": 5,
                    "next_action_clarity": 10,
                },
                "improvement_reason": "模型暂时降级，等待继续补充并重新整理。",
            },
            "admission": {
                "recommendation": "NEED_MORE_INFO",
                "reasoning": "信息仍需补充。",
                "confidence": 0.45,
            },
        },
        knowledge_query_intent=knowledge_query,
        knowledge_answer_mode="STUB" if knowledge_query else "NONE",
        confidence=0.45,
    )


# 所属模块：接待室 Agent > 单轮 LangGraph > 规则问句保守识别。
# 具体功能：`_is_knowledge_query` 对中英文文本做 casefold 后匹配规则、时效、流程、赔付等有限关键词，返回是否需要知识回答模式。
# 上下游：上游是 source_text 与模型 knowledge_query_intent；下游是 STUB/NONE 模式和房间话术，不直接查询规则或作裁决。
# 系统意义：当前知识库未接入时明确使用 STUB，避免模型凭参数常识编造平台规则、时效或赔付标准。
def _is_knowledge_query(text: str) -> bool:
    """识别用户是否在问平台规则/时效，而不是补充本案事实。"""

    normalized = (text or "").casefold()
    return any(
        term in normalized
        for term in (
            "规则",
            "时效",
            "多久",
            "流程",
            "怎么处理",
            "标准",
            "赔付",
            "判断",
            "平台规定",
            "policy",
            "rule",
            "process",
            "how long",
        )
    )


# 所属模块：接待室 Agent > 单轮 LangGraph > 降级诉求枚举识别。
# 具体功能：`_requested_outcome_from_text` 仅为 fallback 按退款、补发/换货、退货的固定顺序匹配中英文关键词；无法确定返回 UNKNOWN。
# 上下游：上游是当事人原始 source_text；下游是低置信降级卷宗 requested_outcome，不触发任何履约工具。
# 系统意义：这是记录“当事人想要什么”而非判定“平台应当做什么”；UNKNOWN 比错误猜测更适合后续追问。
def _requested_outcome_from_text(text: str) -> str:
    """从当事人自然语言中粗略识别退款、补发、退货等诉求；无法确认时保持 UNKNOWN。"""

    normalized = (text or "").casefold()
    if any(term in normalized for term in ("退款", "退钱", "refund")):
        return "REFUND"
    if any(term in normalized for term in ("补发", "重发", "换货", "replacement", "reship")):
        return "REPLACEMENT"
    if any(term in normalized for term in ("退货", "return")):
        return "RETURN"
    return "UNKNOWN"


# 所属模块：接待室 Agent > 单轮 LangGraph > 最小案件身份上下文。
# 具体功能：`_case_identity_context` 只投影 case/room/actor、三类业务引用、发起角色和风险等级；引用优先取初始事实，缺失才回退旧卷宗。
# 上下游：上游是 reason_with_llm 的请求、load_context state 与 previous_case_detail；下游是 context contract 中必需的 case_identity 段。
# 系统意义：模型获得稳定关联键但看不到完整内部请求、权限列表或无关状态；actor_role 来自可信 state，不能由案件文本覆盖。
def _case_identity_context(
    request: dict[str, Any],
    state: IntakeTurnGraphState,
) -> dict[str, Any]:
    """构造模型可见的案件身份摘要，避免把完整内部案件对象直接放入接待提示词。"""

    seed = request.get("initial_case_facts") or {}
    previous = request.get("previous_case_detail") or {}
    references = previous.get("references") or {}
    claim = previous.get("claim_resolution") or {}
    return {
        "case_id": request.get("case_id"),
        "room_type": request.get("room_type"),
        "actor_role": state["actor_role"],
        "order_reference": (
            seed.get("order_reference") or references.get("order_reference") or ""
        ),
        "after_sales_reference": (
            seed.get("after_sales_reference")
            or references.get("after_sales_reference")
            or ""
        ),
        "logistics_reference": (
            seed.get("logistics_reference")
            or references.get("logistics_reference")
            or ""
        ),
        "initiator_role": (
            seed.get("initiator_role")
            or claim.get("initiator_role")
            or state["actor_role"]
        ),
        "risk_level": seed.get("risk_level") or "",
    }
