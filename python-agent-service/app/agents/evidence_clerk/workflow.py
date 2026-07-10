from __future__ import annotations

import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.evidence_clerk.skills.authenticity import EvidenceAuthenticitySkill
from app.harness.context_pack import build_context_pack
from app.harness.localization_policy import localize_internal_text
from app.harness.memory import MemeoMemoryAssembler
from app.schemas import (
    EvidenceAuthenticityFlag,
    EvidenceTurnQuestion,
    EvidenceTurnLlmOutput,
    EvidenceTurnRequest,
    EvidenceTurnResult,
)


LOGGER = logging.getLogger(__name__)


class EvidenceTurnGraphState(TypedDict):
    request: dict[str, Any]
    executed_nodes: Annotated[list[str], operator.add]
    memory_frame: dict[str, Any]
    llm_output: NotRequired[EvidenceTurnLlmOutput]
    room_utterance: NotRequired[str]
    evidence_requests: NotRequired[list[dict[str, Any]]]
    verification_suggestions: NotRequired[list[dict[str, Any]]]
    authenticity_flags: NotRequired[list[dict[str, Any]]]
    confidence: NotRequired[float]


class EvidenceTurnWorkflow:
    """LangGraph workflow for the evidence clerk room conversation."""

    def __init__(self, model_runner: Any | None = None) -> None:
        self._graph = build_evidence_turn_graph(model_runner)

    def run(self, request: EvidenceTurnRequest) -> EvidenceTurnResult:
        result = self._graph.invoke(
            {
                "request": request.model_dump(mode="json"),
                "executed_nodes": [],
                "memory_frame": {},
            }
        )
        return EvidenceTurnResult(
            room_utterance=result["room_utterance"],
            evidence_requests=result["evidence_requests"],
            verification_suggestions=result["verification_suggestions"],
            authenticity_flags=result["authenticity_flags"],
            memory_frame=result["memory_frame"],
            confidence=float(result["confidence"]),
        )


def build_evidence_turn_graph(model_runner: Any | None = None):
    builder = StateGraph(EvidenceTurnGraphState)
    builder.add_node("load_context", _load_context)
    builder.add_node("reason_with_llm", _reason_with_llm_node(model_runner))
    builder.add_node("apply_authenticity_guardrails", _apply_authenticity_guardrails)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "reason_with_llm")
    builder.add_edge("reason_with_llm", "apply_authenticity_guardrails")
    builder.add_edge("apply_authenticity_guardrails", END)
    return builder.compile()


def _load_context(state: EvidenceTurnGraphState) -> dict[str, Any]:
    request = state["request"]
    agent_context = request["agent_context"]
    memory_frame = MemeoMemoryAssembler().assemble(
        request.get("recent_turns") or [],
        expected_agent_session_id=str(agent_context.get("agent_session_id") or ""),
        expected_conversation_scope=str(agent_context.get("conversation_scope") or ""),
        strict_scope=True,
    ).model_dump(mode="json")
    return {
        "memory_frame": memory_frame,
        "executed_nodes": ["load_context"],
    }


def _reason_with_llm_node(model_runner: Any | None):
    def reason_with_llm(state: EvidenceTurnGraphState) -> dict[str, Any]:
        request = state["request"]
        agent_context = request["agent_context"]
        if model_runner is None:
            return {
                "llm_output": _fallback_output(state),
                "executed_nodes": ["fallback_reasoning"],
            }
        try:
            context_pack = build_context_pack(
                "evidence_turn",
                {
                    "current_turn": _current_turn_context(request),
                    "case_identity": _case_identity_context(request),
                    "canonical_case_dossier": request.get("case_intake_dossier") or {},
                    "actor_private_memory": str(
                        state["memory_frame"].get("prompt_memory") or ""
                    ),
                    "compressed_summary": str(
                        state["memory_frame"].get("compressed_summary") or ""
                    ),
                    "actor_visible_evidence": request.get("available_evidence") or [],
                },
                actor_role=str(request.get("actor_role") or ""),
            )
            generation = model_runner.invoke_structured(
                node_name="evidence_turn",
                case_data={
                    "case_id": request.get("case_id"),
                    "room_type": request.get("room_type"),
                    "turn_source": request.get("turn_source"),
                    "actor_role": request.get("actor_role"),
                    "agent_key": agent_context.get("agent_key"),
                    "prompt_profile_id": agent_context.get("prompt_profile_id"),
                    "current_party_message": request.get("current_party_message") or {},
                },
                output_type=EvidenceTurnLlmOutput,
                agent_context=agent_context,
                prompt_profile_id=agent_context.get("prompt_profile_id"),
                context_pack=context_pack,
            )
            return {
                "llm_output": generation.value,
                "executed_nodes": ["reason_with_llm"],
            }
        except Exception as failure:
            LOGGER.warning(
                "evidence clerk LLM turn degraded: case_id=%s actor_role=%s "
                "agent_invocation_id=%s error_type=%s error=%s",
                request.get("case_id"),
                request.get("actor_role"),
                agent_context.get("agent_invocation_id"),
                type(failure).__name__,
                failure,
                exc_info=True,
            )
            return {
                "llm_output": _fallback_output(state),
                "executed_nodes": ["fallback_reasoning_after_llm_error"],
            }

    return reason_with_llm


def _apply_authenticity_guardrails(state: EvidenceTurnGraphState) -> dict[str, Any]:
    output = state["llm_output"]
    request = EvidenceTurnRequest.model_validate(state["request"])
    opening_baseline = (
        _opening_fallback_output(request)
        if request.turn_source == "ROOM_OPENING"
        else None
    )
    if opening_baseline is not None:
        output = _coerce_room_opening_output(output, opening_baseline, request)
    baseline = opening_baseline or EvidenceAuthenticitySkill().draft(request)
    evidence_requests = _localize_model_text_fields(
        output.evidence_requests or baseline.evidence_requests,
        ("question", "reason"),
    )
    verification_suggestions = (
        output.verification_suggestions or baseline.verification_suggestions
    )
    verification_suggestions = _localize_model_text_fields(
        verification_suggestions,
        ("suggestion",),
    )
    authenticity_flags = _localize_model_text_fields(
        output.authenticity_flags or baseline.authenticity_flags,
        ("flag_type", "description"),
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
        "confidence": min(1.0, max(0.0, float(output.confidence or baseline.confidence))),
        "executed_nodes": ["apply_authenticity_guardrails"],
    }


def _coerce_room_opening_output(
    output: EvidenceTurnLlmOutput,
    baseline: EvidenceTurnLlmOutput,
    request: EvidenceTurnRequest,
) -> EvidenceTurnLlmOutput:
    """Make the first evidence-room turn dossier-driven even if the LLM is generic."""

    if _contains_opening_dossier_anchor(output.room_utterance, request):
        room_utterance = output.room_utterance
    else:
        room_utterance = baseline.room_utterance
    return EvidenceTurnLlmOutput(
        room_utterance=room_utterance,
        evidence_requests=_dedupe_questions(
            [*baseline.evidence_requests, *output.evidence_requests]
        ),
        verification_suggestions=output.verification_suggestions,
        authenticity_flags=_dedupe_flags(
            [*baseline.authenticity_flags, *output.authenticity_flags]
        ),
        confidence=max(float(output.confidence), float(baseline.confidence)),
    )


def _contains_opening_dossier_anchor(
    text: str,
    request: EvidenceTurnRequest,
) -> bool:
    normalized = text.casefold()
    return any(anchor.casefold() in normalized for anchor in _opening_dossier_anchors(request))


def _opening_dossier_anchors(request: EvidenceTurnRequest) -> list[str]:
    dossier = request.case_intake_dossier or {}
    dispute_focus = _dict_value(dossier.get("dispute_focus"))
    case_story = _dict_value(dossier.get("case_story"))
    anchors: list[str] = []
    core_issue = str(dispute_focus.get("core_issue") or "").strip()
    if core_issue:
        anchors.append(core_issue)
    core_issue_label = _localized_core_issue(dispute_focus)
    if core_issue_label:
        anchors.append(core_issue_label)
    anchors.extend(_string_list(dispute_focus.get("facts_to_verify")))
    summary = str(case_story.get("one_sentence_summary") or "").strip()
    if summary:
        anchors.append(summary[:32])
    return [anchor for anchor in anchors if len(anchor) >= 4]


def _dedupe_questions(
    questions: list[EvidenceTurnQuestion],
) -> list[EvidenceTurnQuestion]:
    result: list[EvidenceTurnQuestion] = []
    seen: set[str] = set()
    for question in questions:
        key = question.question.strip().casefold()
        if key and key not in seen:
            result.append(question)
            seen.add(key)
    return result


def _dedupe_flags(
    flags: list[EvidenceAuthenticityFlag],
) -> list[EvidenceAuthenticityFlag]:
    result: list[EvidenceAuthenticityFlag] = []
    seen: set[tuple[str | None, str, str]] = set()
    for flag in flags:
        key = (flag.evidence_id, flag.flag_type, flag.description)
        if key not in seen:
            result.append(flag)
            seen.add(key)
    return result


def _fallback_output(state: EvidenceTurnGraphState) -> EvidenceTurnLlmOutput:
    request = EvidenceTurnRequest.model_validate(state["request"])
    if request.turn_source == "ROOM_OPENING":
        return _opening_fallback_output(request)
    draft = EvidenceAuthenticitySkill().draft(request)
    return EvidenceTurnLlmOutput(
        room_utterance=(
            "我会只围绕这批材料的来源、形成时间、完整性、可读性和与本案争议事实的关联性来核验；"
            "现在不会判断责任，也不会给出退款、赔付或最终处理方案。"
        ),
        evidence_requests=draft.evidence_requests,
        verification_suggestions=draft.verification_suggestions,
        authenticity_flags=draft.authenticity_flags,
        confidence=draft.confidence,
    )


def _opening_fallback_output(request: EvidenceTurnRequest) -> EvidenceTurnLlmOutput:
    dossier = request.case_intake_dossier or {}
    dispute_focus = _dict_value(dossier.get("dispute_focus"))
    case_story = _dict_value(dossier.get("case_story"))
    core_issue = _localized_core_issue(dispute_focus)
    facts = [
        _localize_internal_text(fact)
        for fact in _string_list(dispute_focus.get("facts_to_verify"))
    ]
    if not facts:
        facts = [
            "原始证据文件",
            "证据形成时间",
            "证据来源路径",
            "与接待室案情的关联事实",
        ]
    summary = _normalize_sentence(
        _localize_internal_text(str(case_story.get("one_sentence_summary") or ""))
    )
    facts_text = "、".join(facts)
    story_text = f"。接待室案情摘要：{summary}" if summary else "。"
    room_utterance = (
        f"我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 {core_issue}，"
        f"首轮请围绕这些材料补充证据：{facts_text}{story_text}"
        "你可以上传文件，也可以先说明证据来源、形成时间、保存方式和能证明的事实；"
        "发起争议方须至少正式提交 1 份相关证据后才能完成举证；"
        "另一方可补充材料，或等待举证时效结束。"
    )
    evidence_requests = [
        EvidenceTurnQuestion(
            question_id=f"QUESTION_OPENING_FACT_{index}",
            target_evidence_id=None,
            question=(
                f"请补充或说明「{fact}」对应的原始材料、形成时间、来源路径，"
                f"以及它如何关联争议焦点 {core_issue}。"
            ),
            reason=f"接待室案情把「{fact}」列为首轮需要核验的事实材料。",
        )
        for index, fact in enumerate(facts, start=1)
    ]
    return EvidenceTurnLlmOutput(
        room_utterance=room_utterance,
        evidence_requests=evidence_requests[:10],
        verification_suggestions=[],
        authenticity_flags=[
            EvidenceAuthenticityFlag(
                evidence_id=None,
                flag_type="OPENING_EVIDENCE_GAPS",
                description=f"首轮举证需要围绕 {core_issue} 补齐：{facts_text}。",
                severity="MEDIUM",
            )
        ],
        confidence=0.42,
    )


def _dict_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        text = str(item).strip()
        if text:
            result.append(text)
    return result


def _localized_core_issue(dispute_focus: dict[str, Any]) -> str:
    for key in ("core_issue_label", "core_issue_text", "core_issue"):
        value = str(dispute_focus.get(key) or "").strip()
        if value:
            return _localize_internal_text(value)
    return "争议焦点待确认"


def _localize_internal_text(text: str) -> str:
    return localize_internal_text(text)


def _localize_model_text_fields(items: list[Any], fields: tuple[str, ...]) -> list[Any]:
    localized: list[Any] = []
    for item in items:
        updates: dict[str, str] = {}
        for field in fields:
            value = getattr(item, field, None)
            if isinstance(value, str) and value:
                updates[field] = _localize_internal_text(value)
        localized.append(item.model_copy(update=updates) if updates else item)
    return localized


def _current_turn_context(request: dict[str, Any]) -> dict[str, Any]:
    message = request.get("current_party_message") or {}
    return {
        "turn_source": request.get("turn_source"),
        "role": request.get("actor_role") or message.get("role"),
        "actor_id": request.get("actor_id"),
        "message_id": message.get("message_id"),
        "message_type": message.get("message_type"),
        "text": message.get("text") or "",
        "attachment_refs": message.get("attachment_refs") or [],
    }


def _case_identity_context(request: dict[str, Any]) -> dict[str, Any]:
    return {
        "case_id": request.get("case_id"),
        "room_type": request.get("room_type"),
        "actor_role": request.get("actor_role"),
        "actor_id": request.get("actor_id"),
    }


def _normalize_sentence(text: str) -> str:
    stripped = text.strip()
    return stripped.rstrip("。.!！?？") + "。" if stripped else ""


def _sanitize_non_final(text: str) -> str:
    forbidden = ("最终判定", "最终裁决", "责任在", "应当退款", "应当赔付")
    sanitized = text
    for phrase in forbidden:
        sanitized = sanitized.replace(phrase, "证据层面仍需核验")
    if "不会判断责任" not in sanitized and "不判断责任" not in sanitized:
        sanitized = sanitized.rstrip("。") + "。本轮只做证据核验，不判断责任或最终方案。"
    return sanitized
