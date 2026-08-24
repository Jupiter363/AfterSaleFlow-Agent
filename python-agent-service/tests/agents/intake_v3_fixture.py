"""Reusable provider-shaped fixtures for the ordered Intake V3 contract."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any


_SCORE_CAPS = (
    ("references", 15),
    ("event_story", 20),
    ("party_positions", 20),
    ("requested_resolution", 15),
    ("risk_and_conflicts", 15),
    ("next_action_clarity", 15),
)


def _score_breakdown(total_score: int) -> dict[str, int]:
    remaining = total_score
    breakdown: dict[str, int] = {}
    for field, cap in _SCORE_CAPS:
        value = min(cap, remaining)
        breakdown[field] = value
        remaining -= value
    if remaining:
        raise ValueError("total_score must be between 0 and 100")
    return breakdown


def intake_initiator_v3_payload(
    *,
    room_utterance: str,
    total_score: int,
    conversation_action: str | None = None,
    blocking_gaps: Sequence[str] | None = None,
    nice_to_have_gaps: Sequence[str] = ("签收底单",),
    next_questions: Sequence[str] | None = None,
    improvement_reason: str | None = None,
    confidence: float = 0.86,
) -> dict[str, Any]:
    """Build one complete ordered provider result without legacy root fields."""

    action = conversation_action or (
        "INVITE_OPTIONAL_REMARK" if total_score >= 85 else "ASK_SUBSTANTIVE"
    )
    resolved_blocking_gaps = list(
        blocking_gaps
        if blocking_gaps is not None
        else (() if total_score >= 85 else ("订单号", "异常发现时间"))
    )
    resolved_questions = list(
        next_questions
        if next_questions is not None
        else (
            (
                "请补充商家当时对退款诉求给出的具体答复？",
            )
            if action == "ASK_SUBSTANTIVE" and total_score >= 85
            else (
                "请问该订单的订单号是什么？",
                "您最早在什么时间发现本人没有收到包裹？",
            )
            if action == "ASK_SUBSTANTIVE"
            else ()
        )
    )
    ready = total_score >= 85 and not resolved_blocking_gaps
    remark_status = {
        "INVITE_OPTIONAL_REMARK": "WAITING_FOR_REMARK",
        "ACK_NO_REMARK": "NO_EXTRA_REMARKS",
    }.get(
        action,
        "READY_PENDING_REMARK_INVITE" if ready else "NOT_READY",
    )
    admission = "ACCEPTED" if ready else "NEED_MORE_INFO"
    resolved_improvement = (
        improvement_reason
        if improvement_reason is not None
        else ("" if ready else "仍需补充订单号和异常发现时间。")
    )

    return {
        "room_utterance": room_utterance,
        "ordered_sections": [
            {
                "sequence": 1,
                "kind": "CASE_MATRIX",
                "value": {
                    "schema_version": "case_fact_matrix.delta.v2",
                    "fact_rows": [
                        {
                            "fact_key": "NEW_SIGNED_NOT_RECEIVED",
                            "category": "LOGISTICS",
                            "fact_target": "物流显示签收但发起方称未收到商品",
                            "materiality": "CORE",
                            "stance": "CONFIRM",
                            "position_summary": "用户称物流显示签收但本人未收到商品。",
                            "asserted_value": "物流显示签收但用户未收到",
                            "source_scope": "CURRENT_SOURCE",
                        }
                    ],
                    "summary_source_fact_keys": ["NEW_SIGNED_NOT_RECEIVED"],
                },
            },
            {
                "sequence": 2,
                "kind": "CASE_STORY",
                "value": {
                    "title": "物流显示签收但用户称未收到商品",
                    "one_sentence_summary": (
                        "用户称订单物流已显示签收，但本人未收到商品，"
                        "商家要求等待物流核查且暂未提供签收底单。"
                    ),
                },
            },
            {
                "sequence": 3,
                "kind": "PARTY_POSITIONS",
                "value": {
                    "initiator_position": "用户要求退款。",
                    "platform_observation": "目前需要核对物流签收状态与收货陈述。",
                },
            },
            {
                "sequence": 4,
                "kind": "CLAIM_AND_RESPONSE",
                "value": {
                    "claim_resolution": {
                        "initiator_role": "USER",
                        "requested_resolution": "REFUND",
                        "requested_amount": None,
                        "requested_items": None,
                        "request_reason": "用户称物流显示签收但未收到商品。",
                        "normalized_statement": "用户请求对未收到的商品退款。",
                    },
                },
            },
            {
                "sequence": 5,
                "kind": "DISPUTE_FOCUS",
                "value": {
                    "dispute_core_state": {
                        "conflict_type": "CLAIM_UNANSWERED",
                        "core_conflict": "用户要求退款，商家尚未直接回应。",
                        "facts_in_dispute": ["物流显示签收但用户是否实际收货"],
                    },
                    "dispute_focus": {
                        "core_issue": "物流签收状态与实际收货状态冲突",
                        "focus_points": ["物流签收状态", "用户实际收货状态"],
                    },
                },
            },
            {
                "sequence": 6,
                "kind": "VERIFICATION_FOCUS",
                "value": {"items": ["核验物流签收状态与用户实际收货状态"]},
            },
            {
                "sequence": 7,
                "kind": "RISK_ASSESSMENT",
                "value": {
                    "case_grade": "MEDIUM",
                    "risk_points": ["物流状态与用户陈述存在冲突"],
                    "summary": "当前争议集中在签收状态与实际收货事实。",
                },
            },
            {
                "sequence": 8,
                "kind": "MISSING_INFORMATION",
                "value": {
                    "blocking_gaps": resolved_blocking_gaps,
                    "nice_to_have_gaps": list(nice_to_have_gaps),
                    "next_questions": resolved_questions,
                },
            },
            {
                "sequence": 9,
                "kind": "HANDOFF_SUMMARY",
                "value": {
                    "remark_status": remark_status,
                    "latest_remark": "",
                    "instruction": (
                        "案情已达到提交条件，可补充可选备注。"
                        if ready
                        else "补充阻塞信息后继续整理。"
                    ),
                },
            },
            {
                "sequence": 10,
                "kind": "TURN_EVALUATION",
                "value": {
                    "score_breakdown": _score_breakdown(total_score),
                    "threshold": 85,
                    "ready_for_next_step": ready,
                    "improvement_reason": resolved_improvement,
                    "admission_recommendation": admission,
                    "admission_reasoning": (
                        "案情信息已达到接待室提交标准。"
                        if ready
                        else "仍有阻塞案情信息需要补充。"
                    ),
                    "confidence": confidence,
                    "conversation_action": action,
                    "knowledge_answer_mode": "NONE",
                },
            },
        ],
    }
