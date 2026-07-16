# 文件作用：编排证据室单轮 LangGraph：装配当前参与方可信证据视图、执行一次结构化多模态判断，再用确定性护栏验收矩阵与人工任务。

from __future__ import annotations

import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
from app.harness.context_pack import build_context_pack
from app.harness.evidence_context_assembler import (
    AssembledEvidenceContext,
    EvidenceContextAssembler,
    EvidenceTurnWorkingSet,
)
from app.harness.evidence_asset_loader import EvidenceAssetLoader
from app.harness.localization_policy import localize_internal_text
from app.llm import AgentServiceUnavailable
from app.schemas import (
    EvidenceTurnLlmOutput,
    EvidenceTurnRequest,
    EvidenceTurnResult,
)


LOGGER = logging.getLogger(__name__)


class EvidenceTurnGraphState(TypedDict):
    """证据书记官单轮处理图的状态。

    证据室比接待室更强调权限和可见性：
    Java 先把“当前参与方可见”的证据封装成 envelope，
    Python 再把 envelope 组装成模型可读上下文，最后用 guardrails 校验模型输出。
    executed_nodes 使用 operator.add reducer 累加轨迹；NotRequired 字段由对应节点逐步写入。
    """

    request: dict[str, Any]
    executed_nodes: Annotated[list[str], operator.add]
    memory_frame: dict[str, Any]
    assembled_context: NotRequired[AssembledEvidenceContext]
    llm_output: NotRequired[EvidenceTurnLlmOutput]
    room_utterance: NotRequired[str]
    evidence_requests: NotRequired[list[dict[str, Any]]]
    verification_suggestions: NotRequired[list[dict[str, Any]]]
    authenticity_flags: NotRequired[list[dict[str, Any]]]
    evidence_assessments: NotRequired[list[dict[str, Any]]]
    fact_matrix_patch: NotRequired[list[dict[str, Any]]]
    human_review_tasks: NotRequired[list[dict[str, Any]]]
    internal_handoff: NotRequired[dict[str, Any]]
    confidence: NotRequired[float]
    asset_manifest: NotRequired[dict[str, Any]]


class EvidenceTurnWorkflow:
    """证据书记官 LangGraph 工作流入口。"""

    # 所属模块：证据室 Agent > 单轮 LangGraph > 工作流实例初始化。
    # 具体功能：`__init__` 将 HarnessModelRunner 与可选 EvidenceAssetLoader 绑定到 LLM 节点，并编译固定的“上下文→模型→护栏”三节点图。
    # 上下游：上游是服务启动时的文本模型与内部证据加载器依赖；下游是 `run` 重用编译图处理每个证据房间事件。
    # 系统意义：多模态加载和模型调用都被确定性前后节点包围，LLM 无法跳过 Java 可见性信封或直接提交证据判断。
    def __init__(
        self,
        model_runner: Any | None = None,
        asset_loader: EvidenceAssetLoader | None = None,
    ) -> None:
        self._graph = build_evidence_turn_graph(model_runner, asset_loader)

    # 所属模块：证据室 Agent > 单轮 LangGraph > Java 调用门面与矩阵落地投影。
    # 具体功能：`run` 以序列化请求初始化图，执行完后把已校验 fact_matrix_patch 合并旧矩阵，将矩阵/人工任务/内部交接写入 memory_frame 与 canvas_operations，再构造 EvidenceTurnResult。
    # 上下游：上游是 Java 按当前 actor 过滤的 EvidenceTurnRequest；下游是房间话术、证据请求/评估、增量矩阵、人工复核任务及 Java 画布持久化操作。
    # 系统意义：只有护栏节点产出的 patch 能改变矩阵；原始 LLM 输出不会直接进入返回面，且 referenced_evidence_ids 固定为本轮附件。
    def run(self, request: EvidenceTurnRequest) -> EvidenceTurnResult:
        # 图执行结束后，result 是一个普通 dict；再组装成 EvidenceTurnResult 返回给 Java。
        result = self._graph.invoke(
            {
                "request": request.model_dump(mode="json"),
                "executed_nodes": [],
                "memory_frame": {},
            }
        )
        matrix_snapshot = _merge_fact_matrix(
            result["assembled_context"].working_set.evidence_matrix_snapshot,
            result["fact_matrix_patch"],
        )
        memory_frame = {
            # **dict 是 Python 字典展开语法：把 result["memory_frame"] 的键值拷贝进新 dict。
            **result["memory_frame"],
            "evidence_matrix_snapshot": matrix_snapshot,
            "fact_matrix_patch": result["fact_matrix_patch"],
            "human_review_tasks": result["human_review_tasks"],
            "internal_handoff": result["internal_handoff"],
        }
        return EvidenceTurnResult(
            room_utterance=result["room_utterance"],
            evidence_requests=result["evidence_requests"],
            verification_suggestions=result["verification_suggestions"],
            authenticity_flags=result["authenticity_flags"],
            evidence_assessments=result["evidence_assessments"],
            fact_matrix_patch=result["fact_matrix_patch"],
            human_review_tasks=result["human_review_tasks"],
            internal_handoff=result["internal_handoff"],
            memory_frame=memory_frame,
            canvas_operations=[
                {
                    "operation": "REPLACE_EVIDENCE_MATRIX_SNAPSHOT",
                    "value": matrix_snapshot,
                },
                {
                    "operation": "SET_EVIDENCE_HUMAN_REVIEW_TASKS",
                    "value": result["human_review_tasks"],
                },
            ],
            referenced_evidence_ids=list(request.context_envelope.current_event.attachment_refs),
            confidence=float(result["confidence"]),
        )


# 所属模块：证据室 Agent > 单轮 LangGraph > 拓扑构建与编译。
# 具体功能：`build_evidence_turn_graph` 注册 load_context、闭包 LLM 节点、apply_authenticity_guardrails，并用固定边连接 START 到 END 后 compile。
# 上下游：上游是 EvidenceTurnWorkflow 初始化；下游是 invoke 时由每个节点返回局部 state 更新并按 reducer 合并。
# 系统意义：模型节点永远位于可信装配之后、确定性验收之前；证据相关工作流没有由模型决定的任意条件跳转。
def build_evidence_turn_graph(
    model_runner: Any | None = None,
    asset_loader: EvidenceAssetLoader | None = None,
):
    """组装证据室图：上下文装配 -> LLM 判断 -> 真实性/一致性护栏。"""

    builder = StateGraph(EvidenceTurnGraphState)
    builder.add_node("load_context", _load_context)
    builder.add_node(
        "reason_with_llm",
        _reason_with_llm_node(model_runner, asset_loader),
    )
    builder.add_node("apply_authenticity_guardrails", _apply_authenticity_guardrails)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "reason_with_llm")
    builder.add_edge("reason_with_llm", "apply_authenticity_guardrails")
    builder.add_edge("apply_authenticity_guardrails", END)
    return builder.compile()


# 所属模块：证据室 Agent > 单轮 LangGraph > load_context 信任边界节点。
# 具体功能：`_load_context` 重新用 EvidenceTurnRequest 校验初始 dict，再由 EvidenceContextAssembler 从同一 envelope 构造模型 context_sources、严格会话 memory_frame 和护栏 working_set。
# 上下游：上游是 run 提供的 request 初始状态；下游把 AssembledEvidenceContext 与 memory_frame 写入 state，供 LLM 和护栏节点分别读取。
# 系统意义：模型视图与护栏白名单同源，但职责分离；如果 Java envelope 或会话范围不合法，图在任何模型调用前失败。
def _load_context(state: EvidenceTurnGraphState) -> dict[str, Any]:
    """把可信 EvidenceTurnRequest 转成模型上下文和 deterministic working set。"""

    request = EvidenceTurnRequest.model_validate(state["request"])
    assembled_context = EvidenceContextAssembler().assemble(request)
    return {
        "assembled_context": assembled_context,
        "memory_frame": assembled_context.memory_frame,
        "executed_nodes": ["load_context"],
    }


# 所属模块：证据室 Agent > 单轮 LangGraph > 多模态 LLM 节点工厂。
# 具体功能：`_reason_with_llm_node` 用闭包固定 model_runner/asset_loader，返回只接收 EvidenceTurnGraphState 的 `reason_with_llm`，避免不可序列化依赖进入图状态。
# 上下游：上游是 build_evidence_turn_graph 注册节点；下游是 LangGraph 在 load_context 后调用内部执行器。
# 系统意义：测试可注入受控模型/HTTP transport，案件请求却不能替换加载器或借 state 提供任意网络客户端。
def _reason_with_llm_node(
    model_runner: Any | None,
    asset_loader: EvidenceAssetLoader | None,
):
    """创建证据室 LLM 节点。

    这个节点可能携带多模态附件：asset_loader 会把允许进入模型的图片转成 content_parts，
    但模型仍必须输出 EvidenceTurnLlmOutput 这种结构化 JSON。
    """

    # 所属模块：证据室 Agent > 单轮 LangGraph > reason_with_llm 节点执行器。
    # 具体功能：`reason_with_llm` 从 assembled context 读取可信 working_set，可选加载本轮授权图片，将“实际加载状态”写入 multimodal_observation，按 evidence_turn 合同裁剪后执行一次 EvidenceTurnLlmOutput 结构化调用。
    # 上下游：上游是 load_context 产生的 AssembledEvidenceContext；下游仅写 llm_output 与 asset_manifest，交给真实性/引用护栏验收。
    # 系统意义：manifest 阻止模型声称看过未加载图片；多模态 parts 只能来自内部 AssetLoader，任何异常记录案件/actor/invocation 后失败关闭。
    def reason_with_llm(state: EvidenceTurnGraphState) -> dict[str, Any]:
        assembled = state["assembled_context"]
        working_set = assembled.working_set
        agent_context = assembled.agent_context
        if model_runner is None:
            raise AgentServiceUnavailable("evidence clerk model runner is unavailable")
        try:
            # asset_loader 未配置时仍可基于 OCR/元数据做文本核验，但 manifest 明确没有任何像素输入。
            loaded_assets = (
                asset_loader.load(assembled.raw_envelope)
                if asset_loader is not None
                else None
            )
            asset_manifest = (
                loaded_assets.manifest if loaded_assets is not None else {"items": []}
            )
            context_sources = dict(assembled.context_sources)
            # multimodal_observation 告诉模型：哪些附件实际被加载、哪些只是元数据。
            # 这样模型不能假装看过未加载的证据图片。
            context_sources["multimodal_observation"] = {
                "source": "HARNESS_ASSET_LOADER",
                "requested_attachment_ids": list(
                    working_set.current_event.get("attachment_refs", [])
                ),
                "manifest": asset_manifest,
                "trust_note": (
                    "只有 visual_input_status 为 LOADED 或 "
                    "LOADED_WITHOUT_HASH 的证据实际进入多模态模型。"
                ),
            }
            context_pack = build_context_pack(
                "evidence_turn",
                context_sources,
                actor_role=working_set.actor_role,
            )
            # invocation 字典统一文本和多模态路径；只有存在已授权 content_parts 才追加 multimodal_parts。
            invocation = {
                "node_name": "evidence_turn",
                "case_data": assembled.case_data,
                "output_type": EvidenceTurnLlmOutput,
                "agent_context": agent_context.model_dump(mode="json"),
                "prompt_profile_id": agent_context.prompt_profile_id,
                "context_pack": context_pack,
            }
            if loaded_assets is not None and loaded_assets.content_parts:
                # 只有 asset_loader 判定可用的内容才进入多模态输入。
                invocation["multimodal_parts"] = list(loaded_assets.content_parts)
            generation = model_runner.invoke_structured(
                **invocation,
            )
            return {
                "llm_output": generation.value,
                "asset_manifest": asset_manifest,
                "executed_nodes": ["reason_with_llm"],
            }
        except Exception as failure:
            LOGGER.exception(
                "evidence clerk LLM turn failed closed: case_id=%s actor_role=%s "
                "agent_invocation_id=%s error_type=%s error=%s",
                working_set.case_id,
                working_set.actor_role,
                agent_context.agent_invocation_id,
                type(failure).__name__,
                failure,
            )
            raise

    return reason_with_llm


# 所属模块：证据室 Agent > 单轮 LangGraph > 输出确定性验收节点。
# 具体功能：`_apply_authenticity_guardrails` 本地化仅展示字段，经 EvidenceAssessmentPolicy 复核每项评估，再交叉校验矩阵 patch、人工任务、内部 handoff，最后限长并计算评估平均置信度。
# 上下游：上游是模型 EvidenceTurnLlmOutput、可信 working_set 与 asset_manifest；下游写入 END 所需的全部公开/内部结果字段。
# 系统意义：Schema 合法仍不代表业务引用合法；该节点要求模型各输出分区相互一致，并强制“证据核验非定责”的公开话术边界。
def _apply_authenticity_guardrails(state: EvidenceTurnGraphState) -> dict[str, Any]:
    """对模型输出做确定性护栏。

    证据室不能直接相信模型：
    - 文本需要本地化清理；
    - 证据评估要经过 EvidenceAssessmentPolicy；
    - fact_matrix_patch 只能引用本轮允许的 fact_id 和 evidence_id；
    - human_review_tasks 必须和 assessment 中的人工复核标记一致。
    """

    output = state["llm_output"]
    request = state["assembled_context"].working_set
    evidence_requests = _localize_model_text_fields(
        output.evidence_requests,
        ("question", "reason"),
    )
    verification_suggestions = _localize_model_text_fields(
        output.verification_suggestions,
        ("suggestion",),
    )
    authenticity_flags = _localize_model_text_fields(
        output.authenticity_flags,
        ("flag_type", "description"),
    )
    evidence_assessments = EvidenceAssessmentPolicy().apply(
        output.evidence_assessments,
        request,
        state.get("asset_manifest", {"items": []}),
    )
    fact_matrix_patch = _validated_matrix_patch(
        output.fact_matrix_patch,
        request,
        evidence_assessments,
    )
    human_review_tasks = _validated_human_review_tasks(
        output.human_review_tasks,
        request,
        evidence_assessments,
    )
    internal_handoff = _validated_internal_handoff(
        output.internal_handoff,
        request,
        evidence_assessments,
        human_review_tasks,
    )
    # 有逐证据评估时采用其平均值；没有评估才回退模型总置信度，最终再夹在 0..1 范围。
    assessment_confidence = (
        sum(item.assessment_confidence for item in evidence_assessments)
        / len(evidence_assessments)
        if evidence_assessments
        else float(output.confidence)
    )
    return {
        "room_utterance": _sanitize_non_final(
            _localize_internal_text(output.room_utterance)
        ),
        "evidence_requests": [
            item.model_dump(mode="json") for item in evidence_requests[:10]
        ],
        "verification_suggestions": [
            item.model_dump(mode="json") for item in verification_suggestions[:20]
        ],
        "authenticity_flags": [
            item.model_dump(mode="json") for item in authenticity_flags[:20]
        ],
        "evidence_assessments": [
            item.model_dump(mode="json") for item in evidence_assessments[:50]
        ],
        "fact_matrix_patch": fact_matrix_patch,
        "human_review_tasks": human_review_tasks,
        "internal_handoff": internal_handoff,
        "confidence": min(1.0, max(0.0, assessment_confidence)),
        "executed_nodes": ["apply_authenticity_guardrails"],
    }


# 所属模块：证据室 Agent > 输出护栏 > 事实-证据矩阵 patch 验收。
# 具体功能：`_validated_matrix_patch` 从已验收 assessment.fact_links 确定性生成 UPSERT_LINK；模型不再同时维护两份关联。显式 REMOVE_LINK 仅能引用本轮证据和白名单事实。
# 上下游：上游是模型 patches、working_set 和 EvidenceAssessmentPolicy 结果；下游是 `_merge_fact_matrix` 可实际应用的 JSON patch 列表。
# 系统意义：事实关联只有 assessment.fact_links 一个语义真源；矩阵 patch 是确定性持久化指令，避免同一模型在两个字段给出不一致结果。
def _validated_matrix_patch(
    patches: list[Any],
    request: EvidenceTurnWorkingSet,
    assessments: list[Any],
) -> list[dict[str, Any]]:
    """Derive UPSERTs from accepted links and validate optional removals."""

    allowed_fact_ids = {
        str(item.get("fact_id") or "") for item in request.allowed_fact_targets
    }
    current_evidence_ids = {
        str(item) for item in request.current_event.get("attachment_refs", [])
    }
    result: list[dict[str, Any]] = []
    upsert_pairs: set[tuple[str, str]] = set()

    # 先为每份有效评估保留至少一个事实坐标，再补充其余关联。
    # 当单轮附件较多时，这个顺序保证 100 条 patch 上限不会
    # 先被某一份证据的大量次要关联占满。
    link_groups = [
        (assessment.evidence_id, list(assessment.fact_links))
        for assessment in assessments
        if assessment.evidence_id in current_evidence_ids and assessment.fact_links
    ]

    def append_upsert(evidence_id: str, link: Any) -> None:
        if len(result) >= 100 or link.fact_id not in allowed_fact_ids:
            return
        key = (link.fact_id, evidence_id)
        if key in upsert_pairs:
            return
        upsert_pairs.add(key)
        result.append(
            {
                "operation": "UPSERT_LINK",
                "fact_id": link.fact_id,
                "evidence_id": evidence_id,
                "relation": link.relation,
                "reason": link.reason,
                "confidence": float(link.confidence),
            }
        )

    for evidence_id, links in link_groups:
        append_upsert(evidence_id, links[0])
    for evidence_id, links in link_groups:
        for link in links[1:]:
            append_upsert(evidence_id, link)

    # UPSERT_LINK 由上方的已验收 fact_links 生成。仅保留模型显式
    # 要求删除的旧关联，且不能删除本轮刚验收的同一坐标。
    seen_removals: set[tuple[str, str]] = set()
    for patch in patches:
        if patch.operation != "REMOVE_LINK":
            LOGGER.info(
                "ignoring model-authored UPSERT_LINK; normalized assessment is authoritative: "
                "evidence_id=%s fact_id=%s",
                patch.evidence_id,
                patch.fact_id,
            )
            continue
        if patch.fact_id not in allowed_fact_ids:
            LOGGER.warning(
                "dropping evidence matrix patch with unknown fact_id: fact_id=%s",
                patch.fact_id,
            )
            continue
        if patch.evidence_id not in current_evidence_ids:
            LOGGER.warning(
                "dropping evidence matrix patch outside current turn: evidence_id=%s",
                patch.evidence_id,
            )
            continue
        pair = (patch.fact_id, patch.evidence_id)
        if pair in upsert_pairs:
            LOGGER.warning(
                "dropping REMOVE_LINK that conflicts with a normalized assessment link: "
                "evidence_id=%s fact_id=%s",
                patch.evidence_id,
                patch.fact_id,
            )
            continue
        if pair in seen_removals or len(result) >= 100:
            continue
        seen_removals.add(pair)
        result.append(patch.model_dump(mode="json"))
    return result


# 所属模块：证据室 Agent > 输出护栏 > 人工证据复核任务验收。
# 具体功能：`_validated_human_review_tasks` 要求每个任务引用本轮附件且对应 assessment.human_review.required=True；按 evidence_id 去重后还要与全部 required IDs 完全相等。
# 上下游：上游是模型任务列表、working_set 和已验收 assessments；下游是 EvidenceTurnResult.human_review_tasks 与内部 handoff 校验。
# 系统意义：不能凭空创建无评估依据的人工任务，也不能漏掉护栏认为必须复核的证据；一证据一结构化任务便于 Java 幂等持久化。
def _validated_human_review_tasks(
    tasks: list[Any],
    request: EvidenceTurnWorkingSet,
    assessments: list[Any],
) -> list[dict[str, Any]]:
    """校验人工复核任务必须和证据评估结论一一对应。"""

    current_evidence_ids = {
        str(item) for item in request.current_event.get("attachment_refs", [])
    }
    review_required_ids = {
        assessment.evidence_id
        for assessment in assessments
        if assessment.human_review.required
    }
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for task in tasks:
        if task.evidence_id not in current_evidence_ids:
            LOGGER.warning(
                "dropping human review task outside current turn: evidence_id=%s",
                task.evidence_id,
            )
            continue
        if task.evidence_id not in review_required_ids:
            LOGGER.warning(
                "dropping human review task not required by normalized assessment: "
                "evidence_id=%s",
                task.evidence_id,
            )
            continue
        if task.evidence_id in seen:
            continue
        seen.add(task.evidence_id)
        result.append(task.model_dump(mode="json"))

    assessment_by_id = {assessment.evidence_id: assessment for assessment in assessments}
    for evidence_id in sorted(review_required_ids - seen):
        assessment = assessment_by_id[evidence_id]
        reason_codes = list(assessment.human_review.reason_codes) or [
            "NORMALIZED_ASSESSMENT_REQUIRES_REVIEW"
        ]
        instructions = list(assessment.human_review.instructions) or [
            "核对原始材料来源、完整上下文及形成时间，并记录人工复核结论。"
        ]
        high_risk = any(flag.severity == "HIGH" for flag in assessment.risk_flags)
        result.append(
            {
                "evidence_id": evidence_id,
                "reason_codes": reason_codes[:20],
                "review_goal": "人工复核该证据的来源、完整性与真实性风险。",
                "instructions": instructions[:20],
                "priority": "HIGH" if high_risk else "MEDIUM",
            }
        )
        seen.add(evidence_id)
    return result[:50]


# 所属模块：证据室 Agent > 输出护栏 > 后续庭审内部交接验收。
# 具体功能：`_validated_internal_handoff` 将 Pydantic handoff 转 JSON，检查 uncovered_fact_ids 全在卷宗白名单，并要求 human_review_evidence_ids 与已生成任务完全一致且属于已评估证据。
# 上下游：上游是模型 handoff、working_set、assessments 和已验收人工任务；下游是 memory_frame/internal_handoff 供庭审或审核环节消费。
# 系统意义：内部摘要虽然不展示给当事人，也不能携带模型发明事实或不可见证据；跨阶段传递的引用必须和本轮确定性结果闭合。
def _validated_internal_handoff(
    handoff: Any,
    request: EvidenceTurnWorkingSet,
    assessments: list[Any],
    human_review_tasks: list[dict[str, Any]],
) -> dict[str, Any]:
    """校验证据室交给后续庭审/审核环节的内部摘要。

    摘要中的未覆盖事实、待人工核验的证据必须都来自本次可信 working set，避免
    模型凭空扩大案件范围或把对当前参与方不可见的材料传入下一环节。
    """

    allowed_fact_ids = {
        str(item.get("fact_id") or "") for item in request.allowed_fact_targets
    }
    assessed_evidence_ids = {assessment.evidence_id for assessment in assessments}
    human_review_ids = {
        str(item.get("evidence_id") or "") for item in human_review_tasks
    }
    payload = handoff.model_dump(mode="json")
    payload["uncovered_fact_ids"] = [
        fact_id
        for fact_id in payload.get("uncovered_fact_ids", [])
        if fact_id in allowed_fact_ids
    ][:100]
    handoff_review_ids = set(payload.get("human_review_evidence_ids", []))
    if handoff_review_ids != human_review_ids or not handoff_review_ids.issubset(
        assessed_evidence_ids
    ):
        LOGGER.warning(
            "normalizing internal handoff human review references: supplied=%s expected=%s",
            sorted(handoff_review_ids),
            sorted(human_review_ids),
        )
    payload["human_review_evidence_ids"] = sorted(human_review_ids)
    return payload


# 所属模块：证据室 Agent > 矩阵持久化投影 > patch 合并器。
# 具体功能：`_merge_fact_matrix` 兼容旧矩阵包装，用 `(fact_id,evidence_id)` 建稳定索引；REMOVE 删除，UPSERT 覆盖关系，只有存在 patch 时版本加一并标记 updated。
# 上下游：上游是 working_set 中旧 snapshot 与已通过 `_validated_matrix_patch` 的列表；下游是 memory_frame 和 REPLACE_EVIDENCE_MATRIX_SNAPSHOT 画布操作。
# 系统意义：同一事实-证据对最多一条当前关系，重放空 patch 不增版本；函数只合并已验收 patch，不再接触原始模型对象。
def _merge_fact_matrix(
    snapshot: dict[str, Any],
    patches: list[dict[str, Any]],
) -> dict[str, Any]:
    """把模型给出的矩阵 patch 合并到旧快照，生成新的事实-证据链接视图。"""

    # 使用 (fact_id, evidence_id) 作为稳定键：同一证据对同一事实只能保留一条最新链接。

    raw_matrix = snapshot.get("matrix", []) if isinstance(snapshot, dict) else []
    if isinstance(raw_matrix, dict):
        raw_links = raw_matrix.get("links") or raw_matrix.get("items") or raw_matrix.get("rows")
        links = list(raw_links) if isinstance(raw_links, list) else []
    else:
        links = list(raw_matrix) if isinstance(raw_matrix, list) else []
    by_key: dict[tuple[str, str], dict[str, Any]] = {}
    for item in links:
        if not isinstance(item, dict):
            continue
        fact_id = str(item.get("fact_id") or "")
        evidence_id = str(item.get("evidence_id") or "")
        if fact_id and evidence_id:
            by_key[(fact_id, evidence_id)] = dict(item)
    before_by_key = {key: dict(value) for key, value in by_key.items()}
    for patch in patches:
        key = (str(patch["fact_id"]), str(patch["evidence_id"]))
        if patch["operation"] == "REMOVE_LINK":
            by_key.pop(key, None)
            continue
        by_key[key] = {
            "fact_id": patch["fact_id"],
            "evidence_id": patch["evidence_id"],
            "relation": patch["relation"],
            "reason": patch["reason"],
            "confidence": patch["confidence"],
        }
    base_version = int(snapshot.get("version", 0) or 0) if isinstance(snapshot, dict) else 0
    changed = by_key != before_by_key
    return {
        "schema_version": "fact_evidence_matrix.v1",
        "version": base_version + (1 if changed else 0),
        "links": list(by_key.values()),
        "updated": changed,
    }


# 所属模块：证据室 Agent > 展示规范化 > 单文本本地化适配。
# 具体功能：`_localize_internal_text` 把证据工作流调用统一转发到 Harness 共享码表，供本文件保持稳定私有接口。
# 上下游：上游是公开话术和 `_localize_model_text_fields`；下游是 localization_policy，不处理 evidence_id/fact_id。
# 系统意义：接待、证据、庭审共享同一内部代码中文表达，避免各模块出现不同翻译；只作用展示文本，不改变机器合同。
def _localize_internal_text(text: str) -> str:
    """把模型可能输出的内部枚举/英文术语改成面向业务人员的展示文字。"""

    return localize_internal_text(text)


# 所属模块：证据室 Agent > 展示规范化 > Pydantic 字段白名单本地化。
# 具体功能：`_localize_model_text_fields` 只读取调用方列出的展示字段，用 model_copy(update=...) 生成新对象；未列字段和机器 ID 保持原值。
# 上下游：上游是 evidence_requests/verification_suggestions/authenticity_flags；下游是输出护栏序列化后的公开列表。
# 系统意义：本地化不能用递归全对象替换，否则可能改变 evidence_id、fact_id 并使后续白名单校验失效。
def _localize_model_text_fields(items: list[Any], fields: tuple[str, ...]) -> list[Any]:
    """只本地化允许展示的字段，不触碰 evidence_id、fact_id 等需要保持稳定的机器标识。"""

    localized: list[Any] = []
    for item in items:
        updates: dict[str, str] = {}
        for field in fields:
            value = getattr(item, field, None)
            if isinstance(value, str) and value:
                updates[field] = _localize_internal_text(value)
        localized.append(item.model_copy(update=updates) if updates else item)
    return localized


# 所属模块：证据室 Agent > 展示护栏 > 非最终职责强制。
# 具体功能：`_sanitize_non_final` 将最终判定、责任归属、应退款/赔付等越权短语替换为“证据层面仍需核验”，并确保尾句明确本轮不定责、不出最终方案。
# 上下游：上游是本地化后的模型 room_utterance；下游是当事人在证据房间看到的最终话术。
# 系统意义：即使结构化字段都合法，公开自然语言也不能让用户误以为证据书记官已经作出平台裁决或承诺履约动作。
def _sanitize_non_final(text: str) -> str:
    """清除证据室越权的责任或赔付结论。

    证据书记官只能说明核验进展、缺口和真实性风险；责任判断与最终方案只能由后续
    庭审草案和人工审核处理。
    """

    forbidden = ("最终判定", "最终裁决", "责任在", "应当退款", "应当赔付")
    sanitized = text
    for phrase in forbidden:
        sanitized = sanitized.replace(phrase, "证据层面仍需核验")
    if "不会判断责任" not in sanitized and "不判断责任" not in sanitized:
        sanitized = sanitized.rstrip("。") + "。本轮只做证据核验，不判断责任或最终方案。"
    return sanitized
