from __future__ import annotations

from types import SimpleNamespace

from app.schemas import IntakeTurnRequest


def _request(**overrides):
    payload = {
        "case_id": "CASE_intake_case_detail",
        "room_type": "INTAKE",
        "turn_source": "USER_MESSAGE",
        "lobby_seed": {
            "order_reference": "ORDER_1001",
            "after_sales_reference": "AS_1001",
            "logistics_reference": "SF1001001001",
            "initiator_role": "USER",
            "raw_text": "物流显示签收，但用户称没有收到商品，商家要求等待物流核查。",
            "requested_outcome_hint": "REFUND",
        },
        "current_user_message": {
            "message_id": "MESSAGE_1001",
            "role": "USER",
            "text": "我补充：商家没有给签收底单，我希望退款。订单和物流信息都在右侧。",
        },
        "latest_scroll_snapshot": None,
        "recent_turns": [],
    }
    payload.update(overrides)
    return IntakeTurnRequest.model_validate(payload)


class CaseDetailRunner:
    def __init__(self, score: int = 86) -> None:
        self.score = score
        self.calls: list[dict[str, object]] = []

    def invoke_structured(self, *, node_name, case_data, output_type, context_sections):
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "context_sections": context_sections,
            }
        )
        return SimpleNamespace(
            value=output_type(
                room_utterance=(
                    "我已经了解本案的基本情况：物流显示签收但你主张未收到，"
                    "商家暂未提供签收底单。右侧案件详情已整理到可进入下一步。"
                ),
                case_detail={
                    "schema_version": "intake_case_detail.v1",
                    "case_story": {
                        "title": "物流显示签收但用户称未收到商品",
                        "one_sentence_summary": (
                            "用户称订单物流已显示签收，但本人未收到商品，"
                            "商家要求等待物流核查且暂未提供签收底单。"
                        ),
                        "event_timeline": [
                            {
                                "time_hint": "物流签收后",
                                "event": "用户发现物流显示签收但未收到商品",
                                "source": "USER_MESSAGE",
                            }
                        ],
                    },
                    "references": {
                        "order_reference": "ORDER_1001",
                        "after_sales_reference": "AS_1001",
                        "logistics_reference": "SF1001001001",
                    },
                    "party_positions": {
                        "user_claim": "物流显示签收但我没有收到商品，希望退款。",
                        "merchant_claim": "商家要求等待物流核查，暂未提供签收底单。",
                        "platform_observation": "需要核查签收底单和派送记录。",
                    },
                    "dispute_focus": {
                        "core_issue": "SIGNED_NOT_RECEIVED",
                        "key_conflicts": ["物流签收状态与用户实际收货陈述冲突"],
                        "facts_to_verify": ["签收底单", "派送记录"],
                    },
                    "requested_resolution": {
                        "requested_outcome": "REFUND",
                        "expected_resolution_text": "用户希望退款。",
                    },
                    "risk_assessment": {
                        "case_grade": "MEDIUM",
                        "risk_signals": ["SIGNED_NOT_RECEIVED"],
                        "reasoning": "存在签收状态与收货事实冲突。",
                    },
                    "missing_information": {
                        "blocking_gaps": [],
                        "nice_to_have_gaps": ["签收底单"],
                        "next_questions": [],
                    },
                    "intake_quality": {
                        "score": self.score,
                        "threshold": 80,
                        "ready_for_next_step": self.score >= 80,
                        "score_breakdown": {
                            "references": 15,
                            "event_story": 18,
                            "party_positions": 18,
                            "requested_resolution": 10,
                            "risk_and_conflicts": 13,
                            "next_action_clarity": 12,
                        },
                        "improvement_reason": "",
                    },
                    "admission": {
                        "recommendation": "ACCEPTED",
                        "reasoning": "案件事实已达到接待室可受理标准。",
                        "confidence": 0.86,
                    },
                },
                knowledge_query_intent=False,
                knowledge_answer_mode="NONE",
            )
        )


def test_intake_turn_workflow_lives_under_agent_package_and_outputs_case_detail() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    runner = CaseDetailRunner(score=86)
    result = IntakeTurnWorkflow(model_runner=runner).run(_request())

    assert runner.calls[0]["node_name"] == "intake_turn_case_detail"
    assert result.scroll_snapshot["schema_version"] == "intake_case_detail.v1"
    assert result.scroll_snapshot["intake_quality"]["score"] == 86
    assert result.scroll_snapshot["intake_quality"]["ready_for_next_step"] is True
    assert result.admission_recommendation == "ACCEPTED"
    assert result.dossier_patch["case_detail"]["case_story"]["title"] == "物流显示签收但用户称未收到商品"
    assert "已了解本案" in result.room_utterance


def test_intake_case_detail_readiness_is_gated_by_score_and_required_references() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    runner = CaseDetailRunner(score=92)
    request = _request(
        lobby_seed={
            "initiator_role": "USER",
            "raw_text": "物流显示签收，但用户称没有收到商品。",
        }
    )

    result = IntakeTurnWorkflow(model_runner=runner).run(request)

    assert result.scroll_snapshot["intake_quality"]["score"] == 92
    assert result.scroll_snapshot["intake_quality"]["ready_for_next_step"] is False
    assert result.admission_recommendation == "NEED_MORE_INFO"
    assert "ORDER_REFERENCE" in result.missing_fields
    assert "LOGISTICS_REFERENCE" in result.missing_fields
