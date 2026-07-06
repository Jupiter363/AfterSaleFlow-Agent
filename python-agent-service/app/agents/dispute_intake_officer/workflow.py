from __future__ import annotations

import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
)
from app.harness.context_pack import build_context_pack
from app.harness.memory import MemeoMemoryAssembler
from app.harness.narrative_policy import rewrite_platform_narrative
from app.schemas import IntakeTurnRequest, IntakeTurnResult


LOGGER = logging.getLogger(__name__)


class IntakeTurnGraphState(TypedDict):
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
    """LangGraph workflow for the dispute intake officer room conversation."""

    def __init__(self, model_runner: Any | None = None) -> None:
        self._graph = build_intake_turn_graph(model_runner)

    def run(self, request: IntakeTurnRequest) -> IntakeTurnResult:
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


def build_intake_turn_graph(model_runner: Any | None = None):
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


def _load_context(state: IntakeTurnGraphState) -> dict[str, Any]:
    request = state["request"]
    agent_context = request["agent_context"]
    current = request.get("current_user_message") or {}
    seed = request["lobby_seed"]
    source_text = str(current.get("text") or seed.get("raw_text") or "")
    actor_role = str(
        agent_context.get("actor_role")
        or current.get("role")
        or seed.get("initiator_role")
        or "USER"
    )
    memory_frame = MemeoMemoryAssembler().assemble(
        request.get("recent_turns") or [],
        expected_agent_session_id=str(agent_context.get("agent_session_id") or ""),
        expected_conversation_scope=str(agent_context.get("conversation_scope") or ""),
        strict_scope=True,
    ).model_dump(mode="json")
    return {
        "source_text": source_text,
        "actor_role": actor_role,
        "memory_frame": memory_frame,
        "executed_nodes": ["load_context"],
    }


def _reason_with_llm_node(model_runner: Any | None):
    def reason_with_llm(state: IntakeTurnGraphState) -> dict[str, Any]:
        request = state["request"]
        agent_context = request["agent_context"]
        if model_runner is None:
            return {
                "llm_output": _fallback_output(state),
                "executed_nodes": ["fallback_reasoning"],
            }
        try:
            context_pack = build_context_pack(
                "intake_turn_case_detail",
                {
                    "current_turn": _current_turn_context(request, state),
                    "intake_initial_form": request.get("lobby_seed") or {},
                    "case_identity": _case_identity_context(request, state),
                    "latest_canvas_snapshot": request.get("latest_scroll_snapshot") or {},
                    "short_term_memory": str(
                        state["memory_frame"].get("prompt_memory") or ""
                    ),
                    "compressed_summary": str(
                        state["memory_frame"].get("compressed_summary") or ""
                    ),
                },
                actor_role=state["actor_role"],
            )
            generation = model_runner.invoke_structured(
                node_name="intake_turn_case_detail",
                case_data={
                    "case_id": request.get("case_id"),
                    "room_type": request.get("room_type"),
                    "turn_source": request.get("turn_source"),
                    "actor_role": state["actor_role"],
                    "agent_key": agent_context.get("agent_key"),
                    "prompt_profile_id": agent_context.get("prompt_profile_id"),
                    "lobby_seed": request.get("lobby_seed") or {},
                    "current_user_message": request.get("current_user_message"),
                    "latest_scroll_snapshot": request.get("latest_scroll_snapshot")
                    or {},
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
        except Exception as failure:
            LOGGER.warning(
                "intake turn LLM reasoning degraded: case_id=%s turn_source=%s "
                "agent_invocation_id=%s error_type=%s error=%s",
                request.get("case_id"),
                request.get("turn_source"),
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


def _render_case_detail_dossier(state: IntakeTurnGraphState) -> dict[str, Any]:
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
    return {
        "room_utterance": output.room_utterance,
        "dossier_patch": rendered.dossier_patch,
        "scroll_snapshot": rendered.scroll_snapshot,
        "canvas_operations": rendered.canvas_operations,
        "admission_recommendation": rendered.admission_recommendation,
        "missing_fields": rendered.missing_fields,
        "knowledge_query_intent": output.knowledge_query_intent,
        "knowledge_answer_mode": output.knowledge_answer_mode,
        "confidence": rendered.confidence,
        "executed_nodes": ["render_case_detail_dossier"],
    }


def _validate_readiness(state: IntakeTurnGraphState) -> dict[str, Any]:
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
        if "已了解" not in utterance:
            additions.append("我已了解本案情况，可以进入下一步。")
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


def _fallback_output(state: IntakeTurnGraphState) -> IntakeCaseDetailLlmOutput:
    request = state["request"]
    source_text = state["source_text"]
    platform_text = rewrite_platform_narrative(
        source_text,
        actor_role=state["actor_role"],
    )
    seed = request.get("lobby_seed") or {}
    requested_outcome = seed.get("requested_outcome_hint") or _requested_outcome_from_text(
        source_text
    )
    knowledge_query = _is_knowledge_query(source_text)
    utterance = (
        "我已先把你的补充安全记录下来，并整理为右侧案件详情。"
        "为了继续推进，请补充仍缺少的订单、物流或商家沟通材料。"
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
                "event_timeline": [
                    {
                        "time_hint": "接待室当前轮次",
                        "event": platform_text,
                        "source": request.get("turn_source") or "USER_MESSAGE",
                    }
                ],
            },
            "references": {
                "order_reference": seed.get("order_reference") or "",
                "after_sales_reference": seed.get("after_sales_reference") or "",
                "logistics_reference": seed.get("logistics_reference") or "",
            },
            "party_positions": {
                "user_claim": platform_text if state["actor_role"] != "MERCHANT" else "",
                "merchant_claim": platform_text if state["actor_role"] == "MERCHANT" else "",
                "raw_statement": source_text,
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
                "threshold": 80,
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


def _is_knowledge_query(text: str) -> bool:
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


def _requested_outcome_from_text(text: str) -> str:
    normalized = (text or "").casefold()
    if any(term in normalized for term in ("退款", "退钱", "refund")):
        return "REFUND"
    if any(term in normalized for term in ("补发", "重发", "换货", "replacement", "reship")):
        return "REPLACEMENT"
    if any(term in normalized for term in ("退货", "return")):
        return "RETURN"
    return "UNKNOWN"


def _current_turn_context(
    request: dict[str, Any],
    state: IntakeTurnGraphState,
) -> dict[str, Any]:
    current = request.get("current_user_message") or {}
    seed = request.get("lobby_seed") or {}
    return {
        "turn_source": request.get("turn_source"),
        "role": state["actor_role"],
        "text": current.get("text") or seed.get("raw_text") or "",
        "message_id": current.get("message_id"),
        "is_lobby_seed": not bool(current),
        "remark_status": (
            (request.get("latest_scroll_snapshot") or {})
            .get("handoff_notes", {})
            .get("remark_status")
            if isinstance(request.get("latest_scroll_snapshot") or {}, dict)
            else ""
        ),
    }


def _case_identity_context(
    request: dict[str, Any],
    state: IntakeTurnGraphState,
) -> dict[str, Any]:
    seed = request.get("lobby_seed") or {}
    return {
        "case_id": request.get("case_id"),
        "room_type": request.get("room_type"),
        "actor_role": state["actor_role"],
        "order_reference": seed.get("order_reference") or "",
        "after_sales_reference": seed.get("after_sales_reference") or "",
        "logistics_reference": seed.get("logistics_reference") or "",
        "initiator_role": seed.get("initiator_role") or state["actor_role"],
        "risk_level": seed.get("risk_level") or "",
    }
