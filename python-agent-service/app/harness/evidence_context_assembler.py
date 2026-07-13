# 文件作用：把 Java 已授权的证据回合信封拆成受限模型上下文与确定性护栏工作集，统一卷宗、事实、矩阵、私聊和证据预览。

"""Java 可信证据信封进入 Python Evidence Clerk Harness 的上下文装配边界。"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import re
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.harness.invocation_context import AgentInvocationContext
from app.harness.memory import MemeoMemoryAssembler
from app.schemas import (
    EvidenceContextEnvelopeV1,
    EvidenceTurnEvidenceItem,
    EvidenceTurnRequest,
)


MAX_PROMPT_EVIDENCE_ITEMS = 20
MAX_EVIDENCE_PREVIEW_CHARS = 3_000
MAX_MODEL_TEXT_CHARS = 20_000
MAX_CASE_SUMMARY_CHARS = 4_000
_SAFE_FACT_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")


class EvidenceTurnWorkingSet(BaseModel):
    """确定性证据护栏使用的规范化可信材料，不接受模型新增事实或证据。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    case_id: str
    room_type: str
    turn_source: str
    task_mode: str
    actor_role: str
    actor_id: str
    current_event: dict[str, Any]
    case_intake_dossier: dict[str, Any]
    allowed_fact_targets: tuple[dict[str, str], ...] = Field(default_factory=tuple)
    available_evidence: tuple[EvidenceTurnEvidenceItem, ...] = Field(default_factory=tuple)
    evidence_matrix_snapshot: dict[str, Any] = Field(default_factory=dict)


@dataclass(frozen=True)
class AssembledEvidenceContext:
    """一次可信证据回合经治理后的统一结果，包含模型视图和护栏视图。"""

    working_set: EvidenceTurnWorkingSet
    context_sources: dict[str, Any]
    memory_frame: dict[str, Any]
    case_data: dict[str, Any]
    agent_context: AgentInvocationContext
    raw_envelope: EvidenceContextEnvelopeV1


class EvidenceContextAssembler:
    """把可信 Java envelope 转换成有长度与作用域边界的 Harness 上下文段。

    Java 负责授权、可见性过滤和持久化快照；本装配器只负责模型侧命名、确定性回退、
    记忆构造和 Prompt 形状，不判断证据关联性、真实性或可采性。
    """

    # 所属模块：Agent Harness > 证据上下文边界 > Java envelope 总装配入口。
    # 具体功能：`assemble` 从同一可信信封派生卷宗、事实白名单、本轮事件、可见证据、严格会话记忆、旧矩阵与缺口计划，并分别构造模型 context_sources 和确定性 working_set。
    # 上下游：上游是 Java 经 Pydantic 校验的 EvidenceTurnRequest/AgentInvocationContext；下游是 Evidence LangGraph 的 ContextPack、LLM 调用和输出引用护栏。
    # 系统意义：模型视图可为可读性做裁剪/转述，护栏视图保留机器白名单；两者来自同一 envelope，防止模型输出反过来扩大可见证据或 fact_id 范围。
    def assemble(self, request: EvidenceTurnRequest) -> AssembledEvidenceContext:
        """装配证据室模型上下文。

        这里是 Java -> Python Agent 的边界：
        - Java 已经完成权限过滤和快照读取；
        - Python 只接收 envelope 中允许看到的内容；
        - 再把它拆成 current_turn、case_identity、fact_targets 等 prompt section。
        """

        envelope = request.context_envelope
        case_snapshot = envelope.case_snapshot
        current_event = envelope.current_event
        room_policy = envelope.room_policy
        turn_source = current_event.event_type
        canonical_dossier = _canonical_case_dossier(envelope)
        # allowed_fact_targets 是后续矩阵 patch 的白名单，模型只能围绕这些 fact_id 建链接。
        allowed_fact_targets = _allowed_fact_targets(canonical_dossier)
        task_mode = _task_mode(envelope, turn_source=turn_source)
        current_turn = _current_turn(
            envelope,
            turn_source=turn_source,
            task_mode=task_mode,
        )
        # 模型证据目录有最多 20 项的 Prompt 上限；working_evidence 稍后仍由同一 Java 可见集合生成。
        visible_evidence = _actor_visible_evidence(envelope)
        recent_turns = [
            _memory_turn(turn)
            for turn in envelope.private_conversation.recent_turns
        ]
        # strict_scope=True 会逐条核对 session/scope；发现跨参与方记忆时整轮失败，而不是偷偷过滤。
        memory_frame = MemeoMemoryAssembler().assemble(
            recent_turns,
            expected_agent_session_id=envelope.private_conversation.agent_session_id,
            expected_conversation_scope=envelope.private_conversation.conversation_scope,
            strict_scope=True,
        ).model_dump(mode="json")
        latest_canvas_snapshot = _latest_canvas_snapshot(envelope)
        evidence_matrix_snapshot = _evidence_matrix_snapshot(latest_canvas_snapshot)
        claim_and_response_state = _claim_and_response_state(canonical_dossier)
        fact_targets = _fact_targets(allowed_fact_targets, canonical_dossier)
        evidence_gap_plan = _evidence_gap_plan(
            fact_targets,
            evidence_matrix_snapshot,
            canonical_dossier,
        )
        private_conversation_window = _private_conversation_window(envelope)
        actor = envelope.actor_snapshot
        working_evidence = tuple(
            _working_evidence(item) for item in envelope.visible_evidence
        )
        working_set = EvidenceTurnWorkingSet(
            # working_set 是 deterministic guardrails 使用的可信数据视图；
            # 它比 prompt 更严格，不包含模型自由解释出来的内容。
            case_id=case_snapshot.case_id,
            room_type=room_policy.room_type,
            turn_source=turn_source,
            task_mode=task_mode,
            actor_role=actor.actor_role,
            actor_id=actor.actor_id,
            current_event=current_turn,
            case_intake_dossier=canonical_dossier,
            allowed_fact_targets=allowed_fact_targets,
            available_evidence=working_evidence,
            evidence_matrix_snapshot=evidence_matrix_snapshot,
        )
        # context_sources 只是候选段；下一步 build_context_pack 仍会按 evidence_turn 合同做白名单和优先级筛选。
        context_sources = {
            "current_turn": current_turn,
            "case_identity": {
                "case_id": case_snapshot.case_id,
                "case_version": case_snapshot.case_version,
                "room_type": room_policy.room_type,
                "actor_role": actor.actor_role,
                "initiator_role": actor.initiator_role,
                "context_captured_at": envelope.captured_at,
            },
            "claim_and_response_state": claim_and_response_state,
            "canonical_case_dossier": canonical_dossier,
            "fact_targets": fact_targets,
            "evidence_matrix_snapshot": evidence_matrix_snapshot,
            "party_visible_evidence_catalog": visible_evidence,
            "evidence_gap_plan": evidence_gap_plan,
            "private_conversation_window": private_conversation_window,
            "internal_audit_context": {
                "intake_dossier_provenance": canonical_dossier.get(
                    "intake_dossier_provenance", {}
                ),
                "conversation_scope": actor.conversation_scope,
                "visibility_policy": "CURRENT_ACTOR_SESSION_ONLY",
                "output_policy": "EVIDENCE_ONLY_NON_FINAL",
                "model_call_policy": "ONE_MODEL_CALL_PER_BUSINESS_TURN",
            },
            "room_deadline": room_policy.model_dump(mode="json"),
        }
        case_data = {
            "case_id": case_snapshot.case_id,
            "case_version": case_snapshot.case_version,
            "room_type": room_policy.room_type,
            "turn_source": turn_source,
            "task_mode": task_mode,
            "event_id": envelope.current_event.event_id,
            "actor_role": actor.actor_role,
            "agent_key": request.agent_context.agent_key,
            "prompt_profile_id": actor.prompt_profile_id,
        }
        return AssembledEvidenceContext(
            working_set=working_set,
            context_sources=context_sources,
            memory_frame=memory_frame,
            case_data=case_data,
            agent_context=request.agent_context,
            raw_envelope=envelope,
        )


# 所属模块：Agent Harness > 证据上下文边界 > 接待卷宗规范化。
# 具体功能：`_canonical_case_dossier` 优先复制版本化 intake_dossier_snapshot，缺失 story/focus 时才从 case_snapshot 补兼容字段，并限制摘要/描述长度、附加截断和来源版本信息。
# 上下游：上游是 Java envelope 中冻结的接待卷宗与案件快照；下游是事实白名单提取、证据缺口计划、模型 canonical_case_dossier 段。
# 系统意义：证据室围绕接待阶段已形成的卷宗核验，不把本轮证据或模型推断直接改成案情；provenance 让审核员知道使用了哪一版卷宗。
def _canonical_case_dossier(
    envelope: EvidenceContextEnvelopeV1,
) -> dict[str, Any]:
    """整理接待室产出的案件卷宗，并补足必要快照字段。"""

    snapshot = envelope.case_snapshot
    dossier_snapshot = envelope.intake_dossier_snapshot
    # dict(...) 复制第一层，避免下面 setdefault/赋值修改 Java 请求对象中的原始 payload。
    dossier = dict(dossier_snapshot.payload) if dossier_snapshot is not None else {}
    case_story = dossier.get("case_story")
    if isinstance(case_story, dict):
        case_story = dict(case_story)
        summary = case_story.get("one_sentence_summary")
        if isinstance(summary, str):
            case_story["one_sentence_summary"] = summary[:MAX_CASE_SUMMARY_CHARS]
            case_story["summary_truncated"] = len(summary) > MAX_CASE_SUMMARY_CHARS
            case_story["summary_char_count"] = len(summary)
        dossier["case_story"] = case_story
    else:
        dossier["case_story"] = {
            "title": snapshot.title,
            "one_sentence_summary": snapshot.description[:MAX_CASE_SUMMARY_CHARS],
            "summary_truncated": len(snapshot.description) > MAX_CASE_SUMMARY_CHARS,
            "summary_char_count": len(snapshot.description),
        }
    dossier.setdefault(
        "dispute_focus",
        {
            "core_issue": snapshot.dispute_type or snapshot.case_type,
            "facts_to_verify": [],
        },
    )
    snapshot_view = snapshot.model_dump(mode="json")
    snapshot_view["description"] = snapshot.description[:MAX_MODEL_TEXT_CHARS]
    snapshot_view["description_truncated"] = (
        len(snapshot.description) > MAX_MODEL_TEXT_CHARS
    )
    snapshot_view["description_char_count"] = len(snapshot.description)
    dossier["case_snapshot"] = snapshot_view
    dossier["intake_dossier_provenance"] = {
        "dossier_id": dossier_snapshot.dossier_id if dossier_snapshot else None,
        "schema_version": (
            dossier_snapshot.schema_version if dossier_snapshot else None
        ),
        "version": dossier_snapshot.dossier_version if dossier_snapshot else None,
        "source_turn_no": (
            dossier_snapshot.source_turn_no if dossier_snapshot else None
        ),
        "quality_score": (
            dossier_snapshot.quality_score if dossier_snapshot else None
        ),
        "ready_for_next_step": (
            dossier_snapshot.ready_for_next_step if dossier_snapshot else False
        ),
        "admission_recommendation": (
            dossier_snapshot.admission_recommendation if dossier_snapshot else None
        ),
        "updated_at": dossier_snapshot.updated_at if dossier_snapshot else None,
        "available": dossier_snapshot is not None,
    }
    return dossier


# 所属模块：Agent Harness > 证据上下文边界 > 可关联事实白名单生成。
# 具体功能：`_allowed_fact_targets` 只扫描可信接待卷宗约定的 facts_to_verify/known/disputed 集合，将多种形态转成稳定 fact_id，按首次出现去重并硬限制 100 项。
# 上下游：上游是 `_canonical_case_dossier`；下游是模型 fact_targets、EvidenceTurnWorkingSet 和 fact_matrix_patch 校验。
# 系统意义：LLM 只能把证据关联到接待阶段已有事实，不能通过输出临时发明事实 ID 扩大案件范围或污染事实-证据矩阵。
def _allowed_fact_targets(dossier: dict[str, Any]) -> tuple[dict[str, str], ...]:
    """Build stable fact IDs only from the trusted intake dossier."""

    collections = (
        _nested_value(dossier, "dispute_focus", "facts_to_verify"),
        dossier.get("known_facts"),
        dossier.get("disputed_facts"),
        dossier.get("facts_in_dispute"),
        _nested_value(dossier, "dispute_core_state", "facts_in_dispute"),
    )
    targets: list[dict[str, str]] = []
    seen_ids: set[str] = set()
    for collection in collections:
        values = collection if isinstance(collection, list) else []
        for value in values:
            target = _fact_target(value)
            if target is None or target["fact_id"] in seen_ids:
                continue
            seen_ids.add(target["fact_id"])
            targets.append(target)
            if len(targets) >= 100:
                return tuple(targets)
    return tuple(targets)


# 所属模块：Agent Harness > 证据上下文边界 > 兼容卷宗的安全嵌套读取。
# 具体功能：`_nested_value` 按 path 逐层读取字典；任何中间值不是 dict 就返回 None，而不是抛 KeyError/AttributeError。
# 上下游：上游是事实目标和缺口提取对不同卷宗版本的读取；下游是可选列表/字段的保守空值处理。
# 系统意义：兼容旧卷宗结构时“字段缺失”与“流程崩溃”分离，但该函数不会把类型异常值强行转换成可信事实。
def _nested_value(value: dict[str, Any], *path: str) -> Any:
    current: Any = value
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


# 所属模块：Agent Harness > 证据上下文边界 > 单个事实引用规范化。
# 具体功能：`_fact_target` 接受字符串或旧版 dict；安全的显式 fact_id 原样沿用，否则对最多 500 字事实标签做 SHA-256，生成可重复的 FACT_INTAKE_* ID。
# 上下游：上游是 `_allowed_fact_targets` 遍历的可信卷宗条目；下游是事实状态、矩阵链接和人工审核引用。
# 系统意义：同一事实跨回合获得同一 ID，便于幂等 patch；非法显式 ID 不被信任，只有标签哈希可成为新机器键。
def _fact_target(value: Any) -> dict[str, str] | None:
    """把各种形态的事实描述转成稳定 fact_id。

    如果上游已经给了安全 ID，就沿用；否则用事实文本的 sha256 生成确定性 ID。
    """

    explicit_id = ""
    label = ""
    if isinstance(value, str):
        label = value.strip()
    elif isinstance(value, dict):
        candidate_id = str(value.get("fact_id") or value.get("id") or "").strip()
        if _SAFE_FACT_ID.fullmatch(candidate_id):
            explicit_id = candidate_id
        for key in ("fact", "description", "event", "title", "name", "issue"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate.strip():
                label = candidate.strip()
                break
    if not label and not explicit_id:
        return None
    label = (label or explicit_id)[:500]
    fact_id = explicit_id or (
        "FACT_INTAKE_" + hashlib.sha256(label.encode("utf-8")).hexdigest()[:16].upper()
    )
    return {"fact_id": fact_id, "fact": label}


# 所属模块：Agent Harness > 证据上下文边界 > 当前证据事件投影。
# 具体功能：`_current_turn` 把 envelope.current_event 投影为模型所需字段，正文最多 2 万字符并记录原长度/截断标志，附件 ID、角色、回合和时间保持原值。
# 上下游：上游是 Java 当前房间事件；下游同时进入 current_turn Prompt 段和 EvidenceTurnWorkingSet.current_event。
# 系统意义：模型只能围绕本轮事件行动；限制大文本防止单条 OCR/消息挤掉案件身份与事实合同，附件引用则供后续逐项白名单校验。
def _current_turn(
    envelope: EvidenceContextEnvelopeV1,
    *,
    turn_source: str,
    task_mode: str,
) -> dict[str, Any]:
    """整理当前证据室事件，限制文本长度并保留附件引用。"""

    event = envelope.current_event
    raw_text = event.text or ""
    return {
        "turn_source": turn_source,
        "task_mode": task_mode,
        "event_id": event.event_id,
        "event_type": event.event_type,
        "message_id": event.event_id,
        "message_type": event.message_type,
        "role": event.actor_role,
        "actor_id": event.actor_id,
        "text": raw_text[:MAX_MODEL_TEXT_CHARS],
        "text_truncated": len(raw_text) > MAX_MODEL_TEXT_CHARS,
        "text_char_count": len(raw_text),
        "attachment_refs": list(event.attachment_refs),
        "turn_no": event.turn_no,
        "occurred_at": event.occurred_at,
    }


# 所属模块：Agent Harness > 证据上下文边界 > 本轮任务模式判定。
# 具体功能：`_task_mode` 用确定性优先级把事件分成 ROOM_OPENING、带附件的 EVIDENCE_REVIEW、普通 PARTY_MESSAGE，不让 LLM 自行声明当前模式。
# 上下游：上游是 current_event.event_type 与 attachment_refs；下游是 current_turn、working_set、case_data 和证据书记官 Prompt 行为约束。
# 系统意义：开场、核验附件、回复消息的职责不同；服务端分型可防止模型在无附件时声称已做图像核验。
def _task_mode(
    envelope: EvidenceContextEnvelopeV1,
    *,
    turn_source: str,
) -> str:
    if turn_source == "ROOM_OPENING":
        return "ROOM_OPENING"
    if envelope.current_event.attachment_refs:
        return "EVIDENCE_REVIEW"
    return "PARTY_MESSAGE"


# 所属模块：Agent Harness > 证据上下文边界 > 双方主张状态投影。
# 具体功能：`_claim_and_response_state` 仅复制卷宗中的诉求、被申请方态度和争议核心，并附来源与“对方态度可能只是发起方转述”的信任提示。
# 上下游：上游是冻结接待卷宗；下游是 evidence_turn 的 claim_and_response_state Prompt 段。
# 系统意义：证据书记官需要理解核验目标，但不能把接待室单方描述当成对方正式承认或平台事实认定。
def _claim_and_response_state(dossier: dict[str, Any]) -> dict[str, Any]:
    return {
        "claim_resolution": _dict_value(dossier.get("claim_resolution")),
        "respondent_attitude": _dict_value(dossier.get("respondent_attitude")),
        "dispute_core_state": _dict_value(dossier.get("dispute_core_state")),
        "source": "INTAKE_DOSSIER",
        "trust_note": (
            "诉求来自发起方陈述；对方态度可能是发起方主观描述，"
            "除非卷宗明确标注平台或对方直接来源。"
        ),
    }


# 所属模块：Agent Harness > 证据上下文边界 > 事实核验状态标注。
# 具体功能：`_fact_targets` 对白名单事实按卷宗标签集合标为 KNOWN、DISPUTED 或 TO_VERIFY，并保留 fact_id 与 INTAKE_DOSSIER 来源。
# 上下游：上游是 `_allowed_fact_targets` 与 canonical dossier；下游是模型核验优先级和 `_evidence_gap_plan`。
# 系统意义：状态只是接待卷宗的现状映射，不是证据室重新认定；DISPUTED 事实可获得更高缺口优先级但仍需证据支持。
def _fact_targets(
    allowed_fact_targets: tuple[dict[str, str], ...],
    dossier: dict[str, Any],
) -> list[dict[str, Any]]:
    known_labels = {
        str(item).strip()
        for item in dossier.get("known_facts", [])
        if str(item).strip()
    }
    disputed_labels = {
        str(item).strip()
        for collection in (
            dossier.get("disputed_facts", []),
            dossier.get("facts_in_dispute", []),
            _nested_value(dossier, "dispute_core_state", "facts_in_dispute") or [],
        )
        if isinstance(collection, list)
        for item in collection
        if str(item).strip()
    }
    return [
        {
            **target,
            "status": (
                "KNOWN"
                if target["fact"] in known_labels
                else "DISPUTED"
                if target["fact"] in disputed_labels
                else "TO_VERIFY"
            ),
            "source": "INTAKE_DOSSIER",
        }
        for target in allowed_fact_targets
    ]


# 所属模块：Agent Harness > 证据上下文边界 > 最近画布快照选择。
# 具体功能：`_latest_canvas_snapshot` 从当前私有会话 recent_turns 逆序查找第一个非空 scroll_snapshot，并复制成普通 dict；没有则返回空快照。
# 上下游：上游是已通过会话隔离的历史回合；下游是 `_evidence_matrix_snapshot` 解析上一版事实-证据矩阵。
# 系统意义：只延续当前 Agent 会话的最新画布，不读取另一参与方私聊或把更旧矩阵覆盖新版本。
def _latest_canvas_snapshot(envelope: EvidenceContextEnvelopeV1) -> dict[str, Any]:
    for turn in reversed(envelope.private_conversation.recent_turns):
        snapshot = turn.scroll_snapshot
        if isinstance(snapshot, dict) and snapshot:
            return dict(snapshot)
    return {}


# 所属模块：Agent Harness > 证据上下文边界 > 多版本矩阵格式兼容。
# 具体功能：`_evidence_matrix_snapshot` 按新旧键名查找矩阵，兼容 dict/list 两种载荷并统一包成 `{version, matrix}`；完全缺失时显式标记 available=False。
# 上下游：上游是最近 scroll_snapshot；下游是缺口覆盖计算、working_set 以及本轮 patch 合并。
# 系统意义：兼容历史数据但不凭空生成链接；版本与 available 标志让下游区分“确实为空”与“从未建立矩阵”。
def _evidence_matrix_snapshot(canvas_snapshot: dict[str, Any]) -> dict[str, Any]:
    for key in (
        "evidence_matrix_snapshot",
        "fact_evidence_matrix",
        "evidence_matrix",
        "matrix",
    ):
        value = canvas_snapshot.get(key)
        if isinstance(value, dict):
            return {"version": value.get("version", 0), "matrix": value}
        if isinstance(value, list):
            return {"version": canvas_snapshot.get("version", 0), "matrix": value}
    return {"version": 0, "matrix": [], "available": False}


# 所属模块：Agent Harness > 证据上下文边界 > 本轮证据缺口计划。
# 具体功能：`_evidence_gap_plan` 从旧矩阵提取已覆盖 fact_id，将未覆盖事实生成 HIGH/MEDIUM 缺口，并合并去重卷宗中的下一核验重点，最多保留 10 条。
# 上下游：上游是 fact_targets、matrix snapshot 与 canonical dossier；下游是 evidence_gap_plan Prompt 段，指导证据书记官提问而非判责。
# 系统意义：缺口由白名单事实与已持久化链接确定性计算，不能由模型随意声称“已覆盖”或要求无关材料。
def _evidence_gap_plan(
    fact_targets: list[dict[str, Any]],
    matrix_snapshot: dict[str, Any],
    dossier: dict[str, Any],
) -> dict[str, Any]:
    covered_fact_ids = _covered_fact_ids(matrix_snapshot.get("matrix"))
    next_focus = _dedupe_texts(
        [
            *_string_items(_nested_value(dossier, "dispute_core_state", "next_verification_focus")),
            *_string_items(dossier.get("next_verification_focus")),
            *_string_items(dossier.get("missing_information")),
        ]
    )
    gaps = [
        {
            "fact_id": target["fact_id"],
            "fact": target["fact"],
            "priority": "HIGH" if target["status"] == "DISPUTED" else "MEDIUM",
            "status": "UNCOVERED",
        }
        for target in fact_targets
        if target["fact_id"] not in covered_fact_ids
    ]
    return {
        "uncovered_fact_targets": gaps,
        "next_verification_focus": next_focus[:10],
        "covered_fact_ids": sorted(covered_fact_ids),
    }


# 所属模块：Agent Harness > 证据上下文边界 > 当前参与方私聊窗口。
# 具体功能：`_private_conversation_window` 只取 envelope 已隔离私聊的最近 5 轮，输出来源数、实际纳入数和综合截断标记。
# 上下游：上游是 Java private_conversation；下游是 ContextPack 的 current_actor_session_only 段。
# 系统意义：模型无需看到无限历史，更不能看到另一方私聊；truncated 告知模型“历史不完整”，避免其把窗口首条误当案件起点。
def _private_conversation_window(envelope: EvidenceContextEnvelopeV1) -> dict[str, Any]:
    recent_turns = list(envelope.private_conversation.recent_turns)[-5:]
    return {
        "scope": "CURRENT_ACTOR_PRIVATE",
        "source_count": envelope.private_conversation.source_count,
        "included_count": len(recent_turns),
        "truncated": envelope.private_conversation.truncated or len(
            envelope.private_conversation.recent_turns
        ) > len(recent_turns),
        "turns": [_memory_turn(turn) for turn in recent_turns],
    }


# 所属模块：Agent Harness > 证据上下文边界 > 旧矩阵覆盖事实提取。
# 具体功能：`_covered_fact_ids` 兼容 list 矩阵及 dict.items/links/rows 包装，只从字典链接中收集非空 fact_id 为集合。
# 上下游：上游是 `_evidence_gap_plan` 读取的规范化 matrix；下游是未覆盖事实差集和排序后的 covered_fact_ids。
# 系统意义：集合去重并忽略损坏行，防止同一链接重复计数；它只表示已有链接，不等同于事实已被最终认定。
def _covered_fact_ids(matrix: Any) -> set[str]:
    items = matrix if isinstance(matrix, list) else []
    if isinstance(matrix, dict):
        candidate = matrix.get("items") or matrix.get("links") or matrix.get("rows")
        items = candidate if isinstance(candidate, list) else []
    return {
        str(item.get("fact_id") or "")
        for item in items
        if isinstance(item, dict) and str(item.get("fact_id") or "")
    }


# 所属模块：Agent Harness > 证据上下文边界 > 可选卷宗对象复制。
# 具体功能：`_dict_value` 对 dict 返回浅拷贝，其他类型统一为空 dict，避免下游直接引用并修改原卷宗对象。
# 上下游：上游是 claim/attitude/dispute_core_state 等兼容字段；下游是 claim_and_response_state Prompt 段。
# 系统意义：类型不符时保守视为缺失，不把字符串等脏数据包装成看似可信的结构化案件状态。
def _dict_value(value: Any) -> dict[str, Any]:
    return dict(value) if isinstance(value, dict) else {}


# 所属模块：Agent Harness > 证据上下文边界 > 可选文本列表清洗。
# 具体功能：`_string_items` 只接受 list，逐项转字符串、去首尾空白并删除空项；非列表不做宽松拆分。
# 上下游：上游是不同卷宗版本的 missing_information/next_verification_focus；下游是缺口计划去重。
# 系统意义：统一可选提示文本形态，同时避免把单个字符串按字符迭代成大量伪问题。
def _string_items(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


# 所属模块：Agent Harness > 证据上下文边界 > 文本稳定去重。
# 具体功能：`_dedupe_texts` 利用 dict 保持插入顺序的特性删除空值和重复文本，保留上游给出的首个优先顺序。
# 上下游：上游是多来源核验重点合并；下游是 evidence_gap_plan.next_verification_focus。
# 系统意义：避免模型重复追问同一缺口，且不使用 set 导致跨运行顺序随机变化。
def _dedupe_texts(values: list[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


# 所属模块：Agent Harness > 证据上下文边界 > 私聊回合安全投影。
# 具体功能：`_memory_turn` 把 Pydantic turn 转成 JSON 基础类型，并把当事人回答和 Agent 回复分别限制为 2 万字符，其他会话标识保持不变。
# 上下游：上游是严格 scope 校验与最近五轮选择；下游是 MemeoMemoryAssembler 和 private_conversation_window Prompt 段。
# 系统意义：会话身份字段继续可审计，超长单条消息不会吞掉整个上下文窗口；裁剪不会修改 Java 原对象。
def _memory_turn(turn: Any) -> dict[str, Any]:
    value = turn.model_dump(mode="json")
    for field in ("answer_content", "agent_response"):
        text = value.get(field)
        if isinstance(text, str):
            value[field] = text[:MAX_MODEL_TEXT_CHARS]
    return value


# 所属模块：Agent Harness > 证据上下文边界 > 单证据模型目录项。
# 具体功能：`_visible_evidence` 生成最多 3000 字的解析预览及截断/解析提示，并保留来源角色、可见性、脱敏、文件元数据和时间，不暴露任意下载逻辑。
# 上下游：上游是 `_actor_visible_evidence` 已选择的 Java 可见证据；下游是 party_visible_evidence_catalog Prompt 段。
# 系统意义：模型能区分证据正文、OCR 状态和文件元数据，也能知道材料是否被裁剪；“可见”不等于“已确认真实”。
def _visible_evidence(item: Any) -> dict[str, Any]:
    raw_preview, parse_notice = _content_preview(
        item.parsed_text,
        parse_status=item.parse_status,
    )
    content_preview = raw_preview[:MAX_EVIDENCE_PREVIEW_CHARS]
    return {
        "evidence_id": item.evidence_id,
        "evidence_type": item.evidence_type,
        "source_type": item.source_type,
        "submitted_by_role": item.submitted_by_role,
        "original_filename": item.original_filename,
        "content_type": item.content_type,
        "file_size": item.file_size,
        "has_file_hash": bool(item.file_hash),
        "content_preview": content_preview,
        "preview_truncated": len(raw_preview) > MAX_EVIDENCE_PREVIEW_CHARS,
        "content_char_count": len(item.parsed_text or ""),
        "parse_notice": parse_notice,
        "parse_status": item.parse_status,
        "visibility": item.visibility,
        "desensitized": item.desensitized,
        "occurred_at": item.occurred_at,
        "submitted_at": item.submitted_at,
        "submission_status": item.submission_status,
    }


# 所属模块：Agent Harness > 证据上下文边界 > 当前角色可见证据选择策略。
# 具体功能：`_actor_visible_evidence` 先按本轮 attachment_refs 原顺序选证据，再把其余可见证据按提交/创建时间和 ID 倒序补齐，最终限制 20 项并报告 selection_policy。
# 上下游：上游是 Java 已过滤的 visible_evidence 与 current_event；下游是 `_visible_evidence` 目录投影和 evidence_turn ContextPack。
# 系统意义：本轮正在讨论的附件不会因旧证据过多被裁掉；后续材料按最近优先，且任何选择都不会越出 Java 给定可见集合。
def _actor_visible_evidence(envelope: EvidenceContextEnvelopeV1) -> dict[str, Any]:
    """选择当前 actor 可见证据。

    排序策略：本轮附件优先，其余按提交时间倒序补充，并限制进入 prompt 的数量。
    """

    # 字典推导式建立 O(1) ID 查找；Envelope Schema 已保证本轮 attachment_refs 必须出现在可见集合中。
    items_by_id = {item.evidence_id: item for item in envelope.visible_evidence}
    selected: list[Any] = []
    selected_ids: set[str] = set()
    for evidence_id in envelope.current_event.attachment_refs:
        item = items_by_id[evidence_id]
        if evidence_id not in selected_ids:
            selected.append(item)
            selected_ids.add(evidence_id)
    remaining = [
        item
        for item in envelope.visible_evidence
        if item.evidence_id not in selected_ids
    ]
    remaining.sort(
        key=lambda item: (
            item.submitted_at or item.created_at,
            item.evidence_id,
        ),
        reverse=True,
    )
    selected.extend(remaining)
    included = selected[:MAX_PROMPT_EVIDENCE_ITEMS]
    return {
        "source_count": len(envelope.visible_evidence),
        "included_count": len(included),
        "truncated": len(included) < len(envelope.visible_evidence),
        "selection_policy": "CURRENT_EVENT_ATTACHMENTS_THEN_MOST_RECENT_V1",
        "items": [_visible_evidence(item) for item in included],
    }


# 所属模块：Agent Harness > 证据上下文边界 > 单证据护栏工作项。
# 具体功能：`_working_evidence` 把 Java 证据转换成 EvidenceTurnEvidenceItem，正文使用限长预览、完整 parsed_text 明确置 None，并保留 ID/来源/可见性/解析警告等校验字段。
# 上下游：上游是 envelope.visible_evidence 全部已授权项；下游是 EvidenceAssessmentPolicy 与 matrix/human-review 引用校验。
# 系统意义：确定性护栏只依赖受控字段，不重新信任模型目录；parsed_text 不重复保存可降低状态体积并避免两份正文不一致。
def _working_evidence(item: Any) -> EvidenceTurnEvidenceItem:
    raw_preview, parse_notice = _content_preview(
        item.parsed_text,
        parse_status=item.parse_status,
    )
    return EvidenceTurnEvidenceItem(
        evidence_id=item.evidence_id,
        evidence_type=item.evidence_type,
        source_type=item.source_type,
        content=raw_preview[:MAX_EVIDENCE_PREVIEW_CHARS],
        parsed_text=None,
        occurred_at=item.occurred_at,
        submitted_by_role=item.submitted_by_role,
        visibility=item.visibility,
        content_url=item.content_url,
        content_type=item.content_type,
        parse_status=item.parse_status,
        original_filename=item.original_filename,
        parser_warning=parse_notice,
        redacted=item.desensitized,
    )


# 所属模块：Agent Harness > 证据上下文边界 > OCR/解析状态语义化预览。
# 具体功能：`_content_preview` 有非空 parsed_text 时返回原文；否则按 pending、failed、其他缺失状态返回不同中文提示，并同时作为 parser_warning。
# 上下游：上游是模型目录和护栏工作项构造；下游是 evidence content 预览及人工复核提示。
# 系统意义：空文本不应被模型解释成“图片没有内容”；明确区分尚在解析、解析失败和无可解析文本，避免错误证据结论。
def _content_preview(value: str | None, *, parse_status: str) -> tuple[str, str | None]:
    """为证据文本生成模型可读预览。

    返回值是 (preview, parser_warning)。当 OCR/解析还没完成或失败时，
    用提示性文本代替空内容，避免模型误以为证据本身为空。
    """

    if value is not None and value.strip():
        return value, None
    normalized_status = parse_status.strip().upper()
    if normalized_status in {"PENDING", "PROCESSING", "PARSING"}:
        notice = "证据内容正在解析，当前仅可核对文件元数据。"
        return notice, notice
    if normalized_status in {"FAILED", "ERROR", "PARSE_FAILED"}:
        notice = "证据内容解析失败，当前仅可核对文件元数据。"
        return notice, notice
    notice = "未提供可解析文本，当前仅可核对文件元数据。"
    return notice, notice
