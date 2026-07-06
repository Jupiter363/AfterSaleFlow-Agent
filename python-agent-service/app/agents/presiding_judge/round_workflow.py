from __future__ import annotations

import logging
import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.harness.context_pack import build_context_pack
from app.harness.localization_policy import localize_internal_text
from app.schemas import HearingRoundTurnRequest, HearingRoundTurnResult


LOGGER = logging.getLogger(__name__)


class HearingRoundTurnGraphState(TypedDict):
    request: dict[str, Any]
    executed_nodes: Annotated[list[str], operator.add]
    llm_output: NotRequired[HearingRoundTurnResult]
    result: NotRequired[HearingRoundTurnResult]


class HearingRoundTurnWorkflow:
    """LangGraph workflow for per-round presiding judge court utterances."""

    def __init__(self, model_runner: Any | None = None) -> None:
        self._graph = build_hearing_round_turn_graph(model_runner)

    def run(self, request: HearingRoundTurnRequest) -> HearingRoundTurnResult:
        state = self._graph.invoke(
            {
                "request": request.model_dump(mode="json"),
                "executed_nodes": [],
            }
        )
        return HearingRoundTurnResult.model_validate(state["result"])


def build_hearing_round_turn_graph(model_runner: Any | None = None):
    builder = StateGraph(HearingRoundTurnGraphState)
    builder.add_node("reason_with_llm", _reason_with_llm_node(model_runner))
    builder.add_node("apply_court_guardrails", _apply_court_guardrails)
    builder.add_edge(START, "reason_with_llm")
    builder.add_edge("reason_with_llm", "apply_court_guardrails")
    builder.add_edge("apply_court_guardrails", END)
    return builder.compile()


def _reason_with_llm_node(model_runner: Any | None):
    def reason_with_llm(state: HearingRoundTurnGraphState) -> dict[str, Any]:
        request = HearingRoundTurnRequest.model_validate(state["request"])
        if model_runner is None:
            return {
                "llm_output": _fallback_result(request),
                "executed_nodes": ["fallback_reasoning"],
            }
        try:
            context_pack = build_context_pack(
                "hearing_round_turn",
                {
                    "current_turn": _current_turn_context(request),
                    "case_identity": _case_identity_context(request),
                    "canonical_case_dossier": _canonical_case_dossier(request),
                    "hearing_round_submissions": [
                        item.model_dump(mode="json") for item in request.party_submissions
                    ],
                    "round_control_policy": _round_control_policy(request),
                },
            )
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
            output = HearingRoundTurnResult.model_validate(generation.value)
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
            return {
                "llm_output": _fallback_result(request),
                "executed_nodes": ["fallback_reasoning_after_llm_error"],
            }

    return reason_with_llm


def _apply_court_guardrails(state: HearingRoundTurnGraphState) -> dict[str, Any]:
    request = HearingRoundTurnRequest.model_validate(state["request"])
    output = HearingRoundTurnResult.model_validate(state["llm_output"])
    opening_turn = _is_opening_turn(request)
    final_round = request.final_round or request.round_no >= 3
    if opening_turn:
        expected_event = "JUDGE_OPENING_READY"
        message_text = _opening_message(request)
        next_round_no = request.round_no
        questions_for_user = _opening_questions_for_user(request)
        questions_for_merchant = _opening_questions_for_merchant(request)
        round_summary = "法官已打开本轮庭审，等待用户和商家分别提交本轮说明。"
    elif final_round:
        expected_event = "FINAL_DRAFT_REQUIRED"
        message_text = _sanitize_judge_message(
            output.message_text,
            final_round=True,
        )
        next_round_no = None
        questions_for_user = []
        questions_for_merchant = []
        round_summary = _sanitize_round_summary(output.round_summary)
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
            "non_final": True,
            "requires_human_review": True,
        }
    )
    return {
        "result": result,
        "executed_nodes": ["apply_court_guardrails"],
    }


def _fallback_result(request: HearingRoundTurnRequest) -> HearingRoundTurnResult:
    if _is_opening_turn(request):
        return HearingRoundTurnResult(
            message_text=_opening_message(request),
            round_summary="法官已打开本轮庭审，等待用户和商家分别提交本轮说明。",
            questions_for_user=_opening_questions_for_user(request),
            questions_for_merchant=_opening_questions_for_merchant(request),
            court_event_type="JUDGE_OPENING_READY",
            round_no=request.round_no,
            next_round_no=request.round_no,
            final_draft_required=False,
            prompt_version="hearing-round-opening-fallback-v1",
            model="local-fallback",
        )
    final_round = request.final_round or request.round_no >= 3
    if final_round:
        message = (
            "第三轮陈述已封存。AI 法官将基于当前案情、证据和双方陈述形成非最终裁决草案，"
            "并提交平台审核员终审。"
        )
        return HearingRoundTurnResult(
            message_text=message,
            round_summary="模型暂不可用，系统已封存最终轮材料并进入裁决草案生成路径。",
            court_event_type="FINAL_DRAFT_REQUIRED",
            round_no=request.round_no,
            next_round_no=None,
            final_draft_required=True,
            prompt_version="hearing-round-turn-fallback-v1",
            model="local-fallback",
        )
    return HearingRoundTurnResult(
        message_text=(
            f"第 {request.round_no} 轮陈述已封存。下一轮请双方继续围绕争议焦点、证据来源、"
            "形成时间和与案情的关联性进行定向说明。"
        ),
        round_summary="模型暂不可用，系统已按结构化庭审流程封存本轮材料。",
        questions_for_user=["请补充与本人主张直接相关的事实、时间线和证据来源说明。"],
        questions_for_merchant=["请补充履约记录、物流交接记录和与用户主张差异相关的说明。"],
        court_event_type="JUDGE_NEXT_QUESTIONS_READY",
        round_no=request.round_no,
        next_round_no=request.round_no + 1,
        final_draft_required=False,
        prompt_version="hearing-round-turn-fallback-v1",
        model="local-fallback",
    )


def _current_turn_context(request: HearingRoundTurnRequest) -> dict[str, Any]:
    return {
        "turn_source": "ROUND_OPENED" if _is_opening_turn(request) else "ROUND_CLOSED",
        "round_no": request.round_no,
        "final_round": request.final_round,
        "round_status": request.round_status,
        "stop_reason": request.stop_reason,
        "round_summary_json": request.round_summary_json,
    }


def _case_identity_context(request: HearingRoundTurnRequest) -> dict[str, Any]:
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


def _canonical_case_dossier(request: HearingRoundTurnRequest) -> dict[str, Any]:
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


def _round_control_policy(request: HearingRoundTurnRequest) -> dict[str, Any]:
    return {
        "structure": "法官主持的三轮结构化庭审",
        "round_no": request.round_no,
        "round_names": {
            "1": "事实陈述轮",
            "2": "证据解释/定向回应轮",
            "3": "方案确认轮",
        },
        "final_round_must_generate_draft": request.final_round or request.round_no >= 3,
        "free_debate_allowed": False,
        "non_final_ai_advice": True,
        "human_reviewer_final_decision": True,
    }


def _sanitize_judge_message(text: str, *, final_round: bool) -> str:
    localized = localize_internal_text(text.strip())
    if not localized:
        return _fallback_result(
            HearingRoundTurnRequest(
                case_id="CASE_FALLBACK",
                workflow_id="WORKFLOW_FALLBACK",
                title="庭审回合",
                case_description="庭审回合材料已封存。",
                round_no=3 if final_round else 1,
                dossier_version=1,
                round_status="COMPLETED",
                final_round=final_round,
            )
        ).message_text
    if final_round and _looks_like_more_questions(localized):
        localized = "第三轮陈述已封存。AI 法官将基于当前案情、证据和双方陈述形成非最终裁决草案，并提交平台审核员终审。"
    if "最终裁决" in localized and "非最终裁决" not in localized:
        localized = localized.replace("最终裁决", "非最终裁决草案")
    if final_round and "非最终裁决草案" not in localized:
        localized = localized.rstrip("。") + "，并进入非最终裁决草案生成路径。"
    if "人类终审" not in localized and "审核员终审" not in localized and "平台审核员" not in localized:
        localized = localized.rstrip("。") + "。AI 法官意见为非最终建议，最终由平台审核员确认。"
    return localized


def _sanitize_round_summary(text: str) -> str:
    return localize_internal_text(str(text or "").strip())


def _is_opening_turn(request: HearingRoundTurnRequest) -> bool:
    return (
        request.round_status.upper() == "OPEN"
        and not request.party_submissions
        and not request.stop_reason
    )


def _opening_message(request: HearingRoundTurnRequest) -> str:
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


def _opening_questions_for_user(request: HearingRoundTurnRequest) -> list[str]:
    if request.round_no >= 3:
        return ["请用户说明对当前证据、履约事实和拟处理方向是否还有最后确认意见。"]
    if request.round_no == 2:
        return ["请用户围绕证据来源、形成时间、真实性、完整性以及与争议事实的关联性补充说明。"]
    return ["请用户说明争议发生经过、签收或验货情况，以及希望平台优先核验的事实。"]


def _opening_questions_for_merchant(request: HearingRoundTurnRequest) -> list[str]:
    if request.round_no >= 3:
        return ["请商家说明对当前证据、履约事实和拟处理方向是否还有最后确认意见。"]
    if request.round_no == 2:
        return ["请商家围绕履约记录、证据来源、形成时间、真实性和与用户主张的差异补充说明。"]
    return ["请商家说明履约记录、发货或物流交接情况，以及与用户主张不一致的事实。"]


def _looks_like_more_questions(text: str) -> bool:
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
