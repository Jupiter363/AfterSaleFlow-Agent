# 文件作用：编排接待室单轮 LangGraph：提取当前私聊上下文、结构化理解案情、确定性生成卷宗，并校验能否交接证据室。

from __future__ import annotations

import copy
import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.dispute_intake_officer.schemas import (
    IntakeCaseDetailLlmOutput,
    IntakeConversationAction,
    MaterializedIntakeRoomLlmOutputV3,
    intake_case_detail_output_type,
    is_exact_fresh_form_opening,
    materialize_intake_case_detail_output,
)
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
    party_intake_prompt_mirror,
)
from app.harness.context_pack import build_context_pack
from app.harness.invocation_context import AgentInvocationContext
from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.schemas import IntakeTurnRequest, IntakeTurnResult
from app.streaming import current_stream_observer


LOGGER = logging.getLogger(__name__)
INTAKE_CONTEXT_SECTION_TOKEN_BUDGET = 20_000


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
    conversation_action: NotRequired[IntakeConversationAction]
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
        response = IntakeTurnResult(
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
        observer = current_stream_observer()
        if observer is not None and observer.operation == "intake_turn":
            # 模型原始 room_utterance 可能被案情边界/readiness 节点改写。
            # 只在所有确定性护栏完成后发布最终文本，保证 visible_delta 拼接值
            # 与随后持久化的 final.room_utterance 完全一致。
            observer.visible_delta(
                "intake_turn_case_detail",
                "room_utterance",
                response.room_utterance,
            )
        return response


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
    agent_context = AgentInvocationContext.model_validate(request["agent_context"])
    current = request.get("current_user_message") or {}
    initial_facts = request.get("initial_case_facts") or {}
    source_text = str(
        current.get("text") or initial_facts.get("form_description") or ""
    )
    actor_role = str(agent_context.actor_role or "").upper()
    if actor_role not in {"USER", "MERCHANT"}:
        raise AgentOutputSchemaError(
            "intake_turn_case_detail",
            "party-scoped Intake requires an exact USER or MERCHANT actor",
            safe_code="INTAKE_PARTY_STATE_ACTOR_INVALID",
        )
    recent_messages = request.get("recent_dialogue_messages") or []
    memory_frame = {
        "context_contract": "intake_turn_context.v3",
        "dialogue_window": "LAST_5_MESSAGES",
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
        agent_context = AgentInvocationContext.model_validate(
            request["agent_context"]
        )
        if model_runner is None:
            raise AgentServiceUnavailable("intake turn model runner is not configured")
        try:
            validated_request = IntakeTurnRequest.model_validate(request)
            context_pack = build_intake_turn_context_pack(
                validated_request,
                actor_role=state["actor_role"],
            )
            output_type = intake_case_detail_output_type(validated_request)
            generation = model_runner.invoke_structured(
                node_name="intake_turn_case_detail",
                case_data={
                    "context_contract": "intake_turn_context.v3",
                },
                output_type=output_type,
                agent_context=agent_context,
                prompt_profile_id=agent_context.prompt_profile_id,
                context_pack=context_pack,
                max_input_tokens=INTAKE_CONTEXT_SECTION_TOKEN_BUDGET,
            )
            return {
                "llm_output": materialize_intake_case_detail_output(
                    validated_request,
                    generation.value,
                ),
                "executed_nodes": ["reason_with_llm"],
            }
        # 统一记录上下文后再分类异常：已知服务/Schema 错误保留类型，其余包装为服务不可用供 API 稳定映射。
        except Exception as failure:
            LOGGER.warning(
                "intake turn LLM reasoning failed closed: case_id=%s turn_source=%s "
                "agent_invocation_id=%s error_type=%s error=%s",
                request.get("case_id"),
                request.get("turn_source"),
                agent_context.agent_invocation_id,
                type(failure).__name__,
                failure,
                exc_info=True,
            )
            if isinstance(failure, (AgentOutputSchemaError, AgentServiceUnavailable)):
                raise
            raise AgentServiceUnavailable("intake turn LLM request failed") from failure

    return reason_with_llm


def build_intake_turn_context_pack(
    request: IntakeTurnRequest,
    *,
    actor_role: str | None = None,
):
    """Build the sole production ContextPack used by every Intake graph path."""

    validated = IntakeTurnRequest.model_validate(request)
    request_json = validated.model_dump(mode="json")
    resolved_actor_role = str(actor_role or validated.agent_context.actor_role)
    state: IntakeTurnGraphState = {
        "request": request_json,
        "executed_nodes": [],
        "source_text": "",
        "actor_role": resolved_actor_role,
        "memory_frame": {},
    }
    prompt_initial_facts = _subjective_only_initial_case_facts(
        request_json.get("initial_case_facts") or {}
    )
    previous_detail = request_json.get("previous_case_detail") or {}
    prompt_previous_detail = _subjective_only_snapshot(
        previous_detail,
        actor_role=resolved_actor_role,
    )
    frozen_case_matrix = _respondent_matrix_prompt_projection(
        previous_detail.get("case_fact_matrix")
        if isinstance(previous_detail, dict)
        else None
    )
    previous_dispute_outline = copy.deepcopy(prompt_previous_detail)
    previous_dispute_outline.pop("case_fact_matrix", None)
    context_sources = {
        "case_identity": _case_identity_context(request_json, state),
        "recent_dialogue_messages": _compact_dialogue_window(
            request_json.get("recent_dialogue_messages") or []
        ),
    }
    if is_exact_fresh_form_opening(validated):
        context_sources["case_identity"]["intake_turn_authority"] = {
            "turn_source": validated.turn_source,
            "previous_phase": "NOT_READY",
            "allowed_conversation_actions": ["ASK_SUBSTANTIVE"],
        }
    required_sections = {"case_identity"}
    if request_json.get("current_user_message") is not None:
        context_sources["current_user_message"] = request_json["current_user_message"]
        required_sections.add("current_user_message")
    if frozen_case_matrix:
        context_sources["frozen_case_matrix"] = frozen_case_matrix
        if _is_respondent_matrix_view(
            previous_detail.get("case_fact_matrix"),
            actor_role=resolved_actor_role,
        ):
            required_sections.add("frozen_case_matrix")
    if previous_dispute_outline:
        context_sources["previous_dispute_outline"] = previous_dispute_outline
    if request_json.get("initial_case_facts") is not None:
        context_sources["initial_case_facts"] = prompt_initial_facts
        required_sections.add("initial_case_facts")
    return build_context_pack(
        "intake_turn_case_detail",
        context_sources,
        actor_role=resolved_actor_role,
        required_section_names=frozenset(required_sections),
    )


# 所属模块：接待室 Agent > 单轮 LangGraph > 首轮表单态度隔离。
# 具体功能：`_subjective_only_initial_case_facts` 深拷贝表单 seed，并无条件删除对方态度 seed。
# 上下游：上游是 reason_with_llm 的 initial_case_facts；下游是 ContextPack 的 initial_case_facts 段。
# 系统意义：发起方 Provider 只生成本方观点与诉求，不接收或转述任何对方观点。
def _subjective_only_initial_case_facts(seed: dict[str, Any]) -> dict[str, Any]:
    """Remove every counterparty-attitude seed from the initiator prompt."""

    sanitized = copy.deepcopy(seed)
    # deepcopy 是深拷贝：复制嵌套 dict/list，避免清理 prompt 输入时改到原始请求对象。
    sanitized.pop("respondent_attitude_seed", None)
    return sanitized


# 所属模块：接待室 Agent > 单轮 LangGraph > 旧卷宗态度隔离。
# 具体功能：`_subjective_only_snapshot` 对发起方删除所有对方观点字段，对被发起方只投影冻结 case_fact_matrix.v2 的结构化允许字段。
# 上下游：上游是 reason_with_llm 的上一版卷宗；下游是 previous_case_detail ContextSection。
# 系统意义：双方私聊原文和参与方私有展板都不能跨角色进入模型上下文；跨方只允许冻结事实矩阵的中性结构化投影。
def _subjective_only_snapshot(
    snapshot: dict[str, Any],
    *,
    actor_role: str,
) -> dict[str, Any]:
    """Do not let legacy formal response state contaminate private-room reasoning."""

    sanitized = copy.deepcopy(snapshot)
    current_actor_mirror = party_intake_prompt_mirror(
        sanitized,
        actor_role=actor_role,
    )
    remark_partition = sanitized.get("handoff_remark_partition")
    current_actor_partition = _current_actor_remark_prompt_partition(
        remark_partition,
        actor_role=actor_role,
    )
    matrix = sanitized.get("case_fact_matrix")
    if _is_respondent_matrix_view(matrix, actor_role=actor_role):
        projected_matrix = _respondent_matrix_prompt_projection(matrix)
        projected = (
            {"case_fact_matrix": projected_matrix} if projected_matrix else {}
        )
        projected.update(copy.deepcopy(current_actor_mirror))
        if current_actor_partition:
            projected["handoff_remark_partition"] = current_actor_partition
        return projected
    sanitized.pop("party_intake_state", None)
    sanitized.update(copy.deepcopy(current_actor_mirror))
    if current_actor_partition:
        sanitized["handoff_remark_partition"] = current_actor_partition
    else:
        sanitized.pop("handoff_remark_partition", None)
    sanitized.pop("respondent_attitude", None)
    positions = sanitized.get("party_positions")
    if isinstance(positions, dict):
        own_claim_key = "user_claim" if actor_role == "USER" else "merchant_claim"
        sanitized["party_positions"] = _non_empty_mapping(
            {
                own_claim_key: positions.get(own_claim_key),
                "initiator_position": positions.get("initiator_position"),
                "platform_observation": positions.get("platform_observation"),
            }
        )
    return _compact_case_detail_snapshot(sanitized)


def _current_actor_remark_prompt_partition(
    value: Any,
    *,
    actor_role: str,
) -> dict[str, Any]:
    """Expose only the current party's remark phase and append-only texts."""

    if not isinstance(value, dict):
        return {}
    parties = value.get("parties")
    actor = parties.get(actor_role) if isinstance(parties, dict) else None
    if not isinstance(actor, dict):
        return {}
    remarks = actor.get("remarks")
    return _non_empty_mapping(
        {
            "schema_version": value.get("schema_version"),
            "party_role": actor.get("party_role"),
            "remark_status": actor.get("remark_status"),
            "latest_remark": actor.get("latest_remark"),
            "remarks": [
                {
                    "text": item.get("text"),
                    "source_message_id": item.get("source_message_id"),
                }
                for item in remarks
                if isinstance(item, dict)
            ]
            if isinstance(remarks, list)
            else [],
        }
    )


def _is_respondent_matrix_view(value: Any, *, actor_role: str) -> bool:
    if not isinstance(value, dict) or value.get("schema_version") != "case_fact_matrix.v2":
        return False
    party_map = value.get("party_map")
    if not isinstance(party_map, dict):
        return False
    return str(party_map.get("respondent_role") or "").upper() == str(
        actor_role or ""
    ).upper()


def _respondent_matrix_prompt_projection(value: Any) -> dict[str, Any]:
    """Expose only the frozen matrix semantics to the respondent's private prompt."""

    if not isinstance(value, dict) or value.get("schema_version") != "case_fact_matrix.v2":
        return {}

    claims = value.get("claims")
    projected_claims: dict[str, Any] = {}
    if isinstance(claims, dict):
        initiator_claim = claims.get("initiator_claim")
        if isinstance(initiator_claim, dict):
            projected_claims["initiator_claim"] = _non_empty_mapping(
                {
                    "initiator_role": initiator_claim.get("initiator_role"),
                    "requested_resolution": initiator_claim.get("requested_resolution"),
                    "requested_amount": initiator_claim.get("requested_amount"),
                    "requested_items": initiator_claim.get("requested_items"),
                    "reason_summary": initiator_claim.get("reason_summary"),
                    "position_summary": initiator_claim.get("position_summary"),
                }
            )
        for key in ("respondent_direct",):
            position = claims.get(key)
            if not isinstance(position, dict):
                continue
            projected_claims[key] = _non_empty_mapping(
                {
                    "respondent_role": position.get("respondent_role"),
                    "attitude": position.get("attitude"),
                    "position_summary": position.get("position_summary"),
                    "alternative_proposal": position.get("alternative_proposal"),
                    "source_type": position.get("source_type"),
                }
            )
        if claims.get("claim_conflict"):
            projected_claims["claim_conflict"] = claims.get("claim_conflict")

    projected_rows: list[dict[str, Any]] = []
    fact_rows = value.get("fact_rows")
    for row in fact_rows[:100] if isinstance(fact_rows, list) else []:
        if not isinstance(row, dict):
            continue
        positions = row.get("positions")
        projected_positions: dict[str, Any] = {}
        if isinstance(positions, dict):
            for role in ("USER", "MERCHANT"):
                position = positions.get(role)
                if not isinstance(position, dict):
                    continue
                projected_positions[role] = _non_empty_mapping(
                    {
                        "stance": position.get("stance"),
                        "position_summary": position.get("position_summary"),
                        "asserted_value": position.get("asserted_value"),
                    }
                )
        projected_rows.append(
            _non_empty_mapping(
                {
                    "fact_id": row.get("fact_id"),
                    "category": row.get("category"),
                    "fact_target": row.get("fact_target"),
                    "materiality": row.get("materiality"),
                    "positions": projected_positions,
                    "party_alignment": row.get("party_alignment"),
                    "requires_resolution": row.get("requires_resolution"),
                    "truth_status": row.get("truth_status"),
                }
            )
        )

    return _non_empty_mapping(
        {
            "schema_version": value.get("schema_version"),
            "matrix_id": value.get("matrix_id"),
            "matrix_version": value.get("matrix_version"),
            "matrix_kind": value.get("matrix_kind"),
            "content_hash": value.get("content_hash"),
            "party_map": value.get("party_map"),
            "case_overview": value.get("case_overview"),
            "claims": projected_claims,
            "fact_rows": projected_rows,
            "fact_indexes": value.get("fact_indexes"),
        }
    )


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

    remark_partition = snapshot.get("handoff_remark_partition")
    if isinstance(remark_partition, dict):
        compact["handoff_remark_partition"] = copy.deepcopy(remark_partition)

    case_matrix = snapshot.get("case_fact_matrix")
    if isinstance(case_matrix, dict):
        fact_rows = case_matrix.get("fact_rows")
        compact["case_fact_matrix"] = _non_empty_mapping(
            {
                "schema_version": case_matrix.get("schema_version"),
                "matrix_id": case_matrix.get("matrix_id"),
                "matrix_version": case_matrix.get("matrix_version"),
                "matrix_kind": case_matrix.get("matrix_kind"),
                "content_hash": case_matrix.get("content_hash"),
                "party_map": case_matrix.get("party_map"),
                "case_overview": case_matrix.get("case_overview"),
                "claims": case_matrix.get("claims"),
                "fact_rows": fact_rows[:100]
                if isinstance(fact_rows, list)
                else [],
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
        "READY_PENDING_REMARK_INVITE",
        "WAITING_FOR_REMARK",
        "NOT_READY",
    )
    return [
        str(item).strip()
        for item in value
        if str(item or "").strip()
        and not any(marker in str(item) for marker in process_markers)
    ]


# 所属模块：接待室 Agent > 单轮 LangGraph > 确定性卷宗渲染节点。
# 具体功能：`_render_case_detail_dossier` 重新验收原请求，将 LLM 各字段交给 CaseDetailDossierSkill 做合并、补全、裁剪与评分，再过滤越界证据提问并归一化知识问句模式。
# 上下游：上游是 reason_with_llm 写入的 IntakeCaseDetailLlmOutput；下游写回房间话术、dossier_patch、完整 scroll_snapshot、画布操作、缺失项和置信度。
# 系统意义：模型输出不是可直接持久化卷宗；Skill 才是接待业务 Schema 和旧卷宗合并规则的权威，确保模型不能删除历史事实或伪造就绪状态。
def _render_case_detail_dossier(state: IntakeTurnGraphState) -> dict[str, Any]:
    """把 LLM 的原始结构化输出交给 deterministic skill 做业务归一化。

    设计重点：LLM 负责理解自然语言；CaseDetailDossierSkill 负责兜底、裁剪、
    字段补全和安全边界，避免把模型原样输出直接写入案件卷宗。
    """

    projected = project_intake_case_detail_output(
        request=IntakeTurnRequest.model_validate(state["request"]),
        output=state["llm_output"],
        source_text=state["source_text"],
    )
    return {
        **projected,
        "executed_nodes": ["render_case_detail_dossier"],
    }


def project_intake_case_detail_output(
    *,
    request: IntakeTurnRequest,
    output: IntakeCaseDetailLlmOutput,
    source_text: str,
) -> dict[str, Any]:
    """Apply the baseline deterministic Intake business adapter after model output."""

    request = IntakeTurnRequest.model_validate(request)
    materialized_v3 = (
        output if isinstance(output, MaterializedIntakeRoomLlmOutputV3) else None
    )
    model_semantics_authoritative = materialized_v3 is not None
    if materialized_v3 is None:
        output = IntakeCaseDetailLlmOutput.model_validate(output)
    rendered = CaseDetailDossierSkill().render(
        request=request,
        conversation_action=output.conversation_action,
        room_utterance=output.room_utterance,
        llm_case_detail=output.case_detail,
        llm_dossier_patch=output.dossier_patch,
        llm_scroll_snapshot=output.scroll_snapshot,
        llm_canvas_operations=output.canvas_operations,
        llm_admission_recommendation=output.admission_recommendation,
        llm_missing_fields=output.missing_fields,
        llm_confidence=output.confidence,
        llm_case_matrix_delta=(
            output.case_matrix_delta or output.unilateral_case_matrix
        ),
        model_semantics_authoritative=model_semantics_authoritative,
        llm_respondent_source_binding=(
            materialized_v3.respondent_source_binding.model_dump(mode="json")
            if materialized_v3 is not None
            and materialized_v3.respondent_source_binding is not None
            else None
        ),
    )
    dossier_patch = copy.deepcopy(rendered.dossier_patch)
    dossier_patch["room_utterance_source"] = output.room_utterance
    return {
        "conversation_action": output.conversation_action,
        "room_utterance": output.room_utterance,
        "dossier_patch": dossier_patch,
        "scroll_snapshot": rendered.scroll_snapshot,
        "canvas_operations": rendered.canvas_operations,
        "admission_recommendation": rendered.admission_recommendation,
        "missing_fields": rendered.missing_fields,
        "knowledge_query_intent": (
            output.knowledge_query_intent or _is_knowledge_query(source_text)
        ),
        "knowledge_answer_mode": (
            "STUB"
            if output.knowledge_query_intent
            or _is_knowledge_query(source_text)
            else output.knowledge_answer_mode
        ),
        "confidence": rendered.confidence,
    }


def finalize_intake_projected_output(
    projected: dict[str, Any],
) -> dict[str, Any]:
    """Apply the exact baseline readiness node to an already rendered result.

    The durable Target graph calls this after the same dossier projection used by
    the baseline graph.  Readiness remains observable through its graph
    trajectory, while the prompt-owned visible utterance is preserved verbatim.
    """

    finalized = copy.deepcopy(projected)
    _validate_readiness(
        {
            "scroll_snapshot": finalized["scroll_snapshot"],
            "room_utterance": finalized["room_utterance"],
        }
    )
    return finalized


# 所属模块：接待室 Agent > 单轮 LangGraph > 最终就绪一致性节点。
# 具体功能：`_validate_readiness` 仅信任当前 CaseDetailDossierSkill.schema_version 的 intake_quality，并记录卷宗就绪状态对应的执行轨迹，不改写模型话术。
# 上下游：上游是卷宗渲染后的 scroll_snapshot 与 room_utterance；下游是 END 前最后一次局部更新和最终 IntakeTurnResult。
# 系统意义：结构化 ready 标志与校验轨迹保持可观察，且不以确定性代码覆盖用户可见的模型话术。
def _validate_readiness(state: IntakeTurnGraphState) -> dict[str, Any]:
    """最后一道可流转性校验。

    根据卷宗质量保留就绪校验轨迹，但不修改上游模型生成的话术。
    """

    snapshot = state["scroll_snapshot"]
    if snapshot.get("schema_version") != CaseDetailDossierSkill.schema_version:
        return {"executed_nodes": ["validate_legacy_readiness"]}
    quality = snapshot.get("intake_quality")
    ready = isinstance(quality, dict) and quality.get("ready_for_next_step") is True
    if ready:
        handoff_notes = snapshot.get("handoff_notes")
        remark_status = (
            str(handoff_notes.get("remark_status") or "")
            if isinstance(handoff_notes, dict)
            else ""
        )
        if remark_status == "READY_PENDING_REMARK_INVITE":
            return {"executed_nodes": ["validate_readiness_pending_remark_invite"]}
    return {"executed_nodes": ["validate_readiness"]}


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
