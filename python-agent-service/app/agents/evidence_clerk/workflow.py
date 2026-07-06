from __future__ import annotations

import json
import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.agents.evidence_clerk.skills.authenticity import EvidenceAuthenticitySkill
from app.harness.context_window import PromptSection
from app.harness.memory import MemeoMemoryAssembler
from app.schemas import (
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
    memory_frame = MemeoMemoryAssembler().assemble(
        request.get("recent_turns") or []
    ).model_dump(mode="json")
    return {
        "memory_frame": memory_frame,
        "executed_nodes": ["load_context"],
    }


def _reason_with_llm_node(model_runner: Any | None):
    def reason_with_llm(state: EvidenceTurnGraphState) -> dict[str, Any]:
        request = state["request"]
        if model_runner is None:
            return {
                "llm_output": _fallback_output(state),
                "executed_nodes": ["fallback_reasoning"],
            }
        try:
            generation = model_runner.invoke_structured(
                node_name="evidence_turn",
                case_data={
                    "case_id": request.get("case_id"),
                    "room_type": request.get("room_type"),
                    "actor_role": request.get("actor_role"),
                    "current_party_message": request.get("current_party_message") or {},
                },
                output_type=EvidenceTurnLlmOutput,
                context_sections=[
                    PromptSection(
                        name="memeo_memory",
                        content=str(state["memory_frame"].get("prompt_memory") or ""),
                        priority=90,
                        required=False,
                    ),
                    PromptSection(
                        name="case_intake_dossier",
                        content=json.dumps(
                            request.get("case_intake_dossier") or {},
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                        priority=88,
                        required=False,
                    ),
                    PromptSection(
                        name="available_evidence",
                        content=json.dumps(
                            request.get("available_evidence") or [],
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                        priority=86,
                        required=False,
                    ),
                ],
            )
            return {
                "llm_output": generation.value,
                "executed_nodes": ["reason_with_llm"],
            }
        except Exception as failure:
            LOGGER.warning(
                "evidence clerk LLM turn degraded: case_id=%s actor_role=%s error_type=%s error=%s",
                request.get("case_id"),
                request.get("actor_role"),
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
    baseline = EvidenceAuthenticitySkill().draft(request)
    evidence_requests = output.evidence_requests or baseline.evidence_requests
    verification_suggestions = (
        output.verification_suggestions or baseline.verification_suggestions
    )
    authenticity_flags = output.authenticity_flags or baseline.authenticity_flags
    return {
        "room_utterance": _sanitize_non_final(output.room_utterance),
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


def _fallback_output(state: EvidenceTurnGraphState) -> EvidenceTurnLlmOutput:
    request = EvidenceTurnRequest.model_validate(state["request"])
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


def _sanitize_non_final(text: str) -> str:
    forbidden = ("最终判定", "最终裁决", "责任在", "应当退款", "应当赔付")
    sanitized = text
    for phrase in forbidden:
        sanitized = sanitized.replace(phrase, "证据层面仍需核验")
    if "不会判断责任" not in sanitized and "不判断责任" not in sanitized:
        sanitized = sanitized.rstrip("。") + "。本轮只做证据核验，不判断责任或最终方案。"
    return sanitized
