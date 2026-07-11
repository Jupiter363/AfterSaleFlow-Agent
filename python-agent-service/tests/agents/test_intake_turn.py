from __future__ import annotations

import json
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.config import Settings
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
    ORIGINAL_STATEMENT_MISSING,
    ORIGINAL_STATEMENT_SEPARATOR,
    SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    SUBJECTIVE_RESPONDENT_SOURCE,
)
from app.intake_turn import IntakeTurnWorkflow
from app.main import create_app
from app.schemas import IntakeTurnRequest


def _settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


def _client() -> TestClient:
    return TestClient(
        create_app(
            _settings(),
            intake_turn_workflow=IntakeTurnWorkflow(),
        )
    )


def _headers() -> dict[str, str]:
    return {
        "X-Service-Secret": "test-agent-service-secret",
        "X-Role": "SYSTEM",
    }


def _agent_context(
    case_id: str,
    *,
    actor_id: str = "USER_local_1",
    actor_role: str = "USER",
    agent_session_id: str | None = None,
) -> dict[str, object]:
    resolved_session_id = agent_session_id or f"SESSION_{case_id}_user_intake"
    access_session_id = f"ACCESS_{case_id}_{actor_role}"
    prompt_profile_id = f"DISPUTE_INTAKE_OFFICER:{actor_role}:v1"
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "INTAKE",
        "actor_id": actor_id,
        "actor_role": actor_role,
        "access_session_id": access_session_id,
        "permission_level": "PARTY_USER" if actor_role == "USER" else "PARTY_MERCHANT",
        "permission_scopes": [],
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_invocation_id": f"INVOCATION_{case_id}",
        "agent_session_id": resolved_session_id,
        "conversation_scope": (
            f"default:{case_id}:INTAKE:{actor_id}:{actor_role}:"
            f"DISPUTE_INTAKE_OFFICER:{prompt_profile_id}:{access_session_id}"
        ),
        "scope_type": "INTAKE_INITIATOR_PRIVATE",
        "allowed_actor_ids": [actor_id],
        "allowed_actor_roles": [actor_role],
        "prompt_profile_id": prompt_profile_id,
        "memory_policy_id": "MEMORY_POLICY_INTAKE_V1",
    }


def _with_agent_context(payload: dict[str, object]) -> dict[str, object]:
    context = _agent_context(str(payload["case_id"]))
    payload["agent_context"] = context
    for turn in payload.get("recent_turns") or []:
        if isinstance(turn, dict):
            turn.setdefault("agent_session_id", context["agent_session_id"])
            turn.setdefault("conversation_scope", context["conversation_scope"])
    return payload


class FakeCaseDetailRunner:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def invoke_structured(
        self,
        *,
        node_name,
        case_data,
        output_type,
        context_sections=None,
        context_pack=None,
        agent_context=None,
        prompt_profile_id=None,
    ):
        resolved_sections = (
            context_pack.prompt_sections() if context_pack is not None else context_sections
        )
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "context_sections": resolved_sections,
                "context_pack": context_pack,
                "agent_context": agent_context,
                "prompt_profile_id": prompt_profile_id,
            }
        )
        return SimpleNamespace(
            value=output_type(
                room_utterance="我已了解本案情况，右侧案件详情已达到可进入下一步的标准。",
                case_detail={
                    "schema_version": "intake_case_detail.v1",
                    "case_story": {
                        "title": "物流显示签收但用户称未收到商品",
                        "one_sentence_summary": "用户称物流显示签收但未收到商品，商家暂未提供签收底单。",
                        "event_timeline": [
                            {
                                "time_hint": "物流签收后",
                                "event": "用户发现未收到商品",
                                "source": "USER_MESSAGE",
                            }
                        ],
                    },
                    "references": {
                        "order_reference": "ORDER_123",
                        "after_sales_reference": "AS_456",
                        "logistics_reference": "SF1234567890",
                    },
                    "party_positions": {
                        "user_claim": "物流显示签收但我没有收到商品。",
                        "merchant_claim": "商家要求等待物流核查。",
                        "platform_observation": "需核验签收底单。",
                    },
                    "dispute_focus": {
                        "core_issue": "SIGNED_NOT_RECEIVED",
                        "key_conflicts": ["物流签收记录与用户未收货陈述冲突"],
                        "facts_to_verify": ["签收底单", "派送记录"],
                    },
                    "requested_resolution": {
                        "requested_outcome": "REFUND",
                        "expected_resolution_text": "用户希望退款。",
                    },
                    "risk_assessment": {
                        "case_grade": "MEDIUM",
                        "risk_signals": ["SIGNED_NOT_RECEIVED"],
                        "reasoning": "签收状态存在事实冲突。",
                    },
                    "missing_information": {
                        "blocking_gaps": [],
                        "nice_to_have_gaps": ["签收底单"],
                        "next_questions": [],
                    },
                    "intake_quality": {
                        "score": 88,
                        "threshold": 80,
                        "ready_for_next_step": True,
                        "score_breakdown": {
                            "references": 15,
                            "event_story": 18,
                            "party_positions": 18,
                            "requested_resolution": 10,
                            "risk_and_conflicts": 14,
                            "next_action_clarity": 13,
                        },
                        "improvement_reason": "",
                    },
                    "admission": {
                        "recommendation": "ACCEPTED",
                        "reasoning": "接待信息已足够进入证据阶段。",
                        "confidence": 0.88,
                    },
                },
                confidence=0.88,
            )
        )


def test_intake_turn_workflow_uses_agent_case_detail_node_and_memory_context() -> None:
    runner = FakeCaseDetailRunner()
    workflow = IntakeTurnWorkflow(model_runner=runner)

    result = workflow.run(
        IntakeTurnRequest.model_validate(
            _with_agent_context(
                {
                "case_id": "CASE_intake_turn_llm",
                "room_type": "INTAKE",
                "turn_source": "USER_MESSAGE",
                "lobby_seed": {
                    "order_reference": "ORDER_123",
                    "after_sales_reference": "AS_456",
                    "initiator_role": "USER",
                    "raw_text": "物流显示签收但我没有收到。",
                },
                "current_user_message": {
                    "message_id": "MESSAGE_llm",
                    "role": "USER",
                    "text": "物流单号 SF1234567890，我希望退款。",
                },
                "initiator_statement_transcript": [
                    {
                        "message_id": "MESSAGE_initial",
                        "role": "USER",
                        "text": "系统备注 SIGNED_NOT_RECEIVED，但我没有收到包裹。",
                    },
                    {
                        "message_id": "MESSAGE_llm",
                        "role": "USER",
                        "text": "物流单号 SF1234567890，我希望退款。",
                    },
                ],
                "latest_scroll_snapshot": None,
                "recent_turns": [
                    {
                        "turn_no": 1,
                        "actor_id": "user-local",
                        "answer_role": "USER",
                        "answer_content": "之前说过没有收到包裹。",
                        "agent_role": None,
                        "agent_response": None,
                        "scroll_snapshot": {},
                    }
                ],
                }
            )
        )
    )

    assert runner.calls[0]["node_name"] == "intake_turn_case_detail"
    assert runner.calls[0]["agent_context"]["agent_session_id"] == (
        "SESSION_CASE_intake_turn_llm_user_intake"
    )
    assert runner.calls[0]["prompt_profile_id"] == "DISPUTE_INTAKE_OFFICER:USER:v1"
    context_pack = runner.calls[0]["context_pack"]
    assert context_pack.configuration_profile_key == "DISPUTE_INTAKE_CONTEXT_PACK_V1"
    section_by_name = {
        section.name: section
        for section in runner.calls[0]["context_sections"]  # type: ignore[index]
    }
    section_names = set(section_by_name)
    assert {
        "current_turn",
        "initiator_statement_transcript",
        "case_identity",
        "short_term_memory",
        "latest_canvas_snapshot",
    } <= section_names
    current_turn = json.loads(section_by_name["current_turn"].content)
    assert current_turn["raw_statement"] == "物流单号 SF1234567890，我希望退款。"
    assert current_turn["platform_statement"].startswith("用户称")
    assert "我希望" not in current_turn["platform_statement"]
    transcript_section = section_by_name["initiator_statement_transcript"]
    transcript = json.loads(transcript_section.content)
    assert transcript_section.trust_level == "participant_raw_untrusted"
    assert transcript[0]["text"] == (
        "系统备注 SIGNED_NOT_RECEIVED，但我没有收到包裹。"
    )
    assert transcript[1]["text"] == "物流单号 SF1234567890，我希望退款。"
    case_identity = json.loads(section_by_name["case_identity"].content)
    assert case_identity["case_id"] == "CASE_intake_turn_llm"
    assert case_identity["order_reference"] == "ORDER_123"
    assert result.scroll_snapshot["schema_version"] == "intake_case_detail.v1"
    assert result.scroll_snapshot["intake_quality"]["ready_for_next_step"] is True
    assert result.admission_recommendation == "ACCEPTED"


def test_intake_prompt_excludes_formal_respondent_attitude_sources() -> None:
    runner = FakeCaseDetailRunner()
    request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_formal_prompt_filter",
                "room_type": "INTAKE",
                "turn_source": "LOBBY_SEED",
                "lobby_seed": {
                    "order_reference": "ORDER_123",
                    "logistics_reference": "SF1234567890",
                    "initiator_role": "USER",
                    "raw_text": "用户请求补发缺少的配件。",
                    "respondent_attitude_seed": {
                        "respondent_role": "MERCHANT",
                        "attitude": "DISAGREE",
                        "position": "外部系统记录商家拒绝补发。",
                        "source": "外部售后状态",
                        "confidence": 0.98,
                    },
                },
                "current_user_message": None,
                "initiator_statement_transcript": [
                    {
                        "message_id": "MESSAGE_initial",
                        "role": "USER",
                        "text": "我收到的套装缺少配件，希望补发。",
                    }
                ],
                "latest_scroll_snapshot": {
                    "schema_version": "intake_case_detail.v1",
                    "respondent_attitude": {
                        "respondent_role": "MERCHANT",
                        "attitude": "DISAGREE",
                        "position": "历史卷宗记录商家正式拒绝补发。",
                        "source": "商家正式回应",
                        "confidence": 0.99,
                    },
                },
                "recent_turns": [],
            }
        )
    )

    IntakeTurnWorkflow(model_runner=runner).run(request)

    call = runner.calls[0]
    assert "respondent_attitude_seed" not in call["case_data"]["lobby_seed"]
    assert "respondent_attitude" not in call["case_data"]["latest_scroll_snapshot"]
    section_by_name = {
        section.name: section
        for section in call["context_sections"]  # type: ignore[index]
    }
    initial_form = json.loads(section_by_name["intake_initial_form"].content)
    latest_snapshot = json.loads(section_by_name["latest_canvas_snapshot"].content)
    assert "respondent_attitude_seed" not in initial_form
    assert "respondent_attitude" not in latest_snapshot


def test_fallback_lobby_seed_turn_generates_case_detail_board() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json=_with_agent_context({
            "case_id": "CASE_intake_turn_seed",
            "room_type": "INTAKE",
            "turn_source": "LOBBY_SEED",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "after_sales_reference": "AS_456",
                "logistics_reference": "SF789000111",
                "initiator_role": "USER",
                "raw_text": "物流显示签收，但我没有收到包裹，希望退款。",
                "requested_outcome_hint": "REFUND",
                "claim_resolution_seed": {
                    "initiator_role": "USER",
                    "requested_resolution": "REFUND",
                    "requested_amount": 299,
                    "requested_items": "儿童手表 1 件",
                    "request_reason": "物流显示签收但用户本人没有收到包裹，希望退款。",
                    "original_statement": "我没收到包裹，希望退款",
                },
            },
            "current_user_message": None,
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        }),
    )

    assert response.status_code == 200
    payload = response.json()
    detail = payload["scroll_snapshot"]
    assert detail["schema_version"] == "intake_case_detail.v1"
    assert detail["references"]["order_reference"] == "ORDER_123"
    assert detail["references"]["logistics_reference"] == "SF789000111"
    assert detail["requested_resolution"]["requested_outcome"] == "REFUND"
    assert detail["claim_resolution"]["requested_resolution"] == "REFUND"
    assert detail["claim_resolution"]["requested_amount"] == 299
    assert detail["claim_resolution"]["requested_items"] == "儿童手表 1 件"
    assert detail["claim_resolution"]["original_statement"] == "我没收到包裹，希望退款"
    assert detail["claim_resolution"]["normalized_statement"].startswith("用户")
    assert "我" not in detail["claim_resolution"]["normalized_statement"]
    assert detail["respondent_attitude"]["respondent_role"] == "MERCHANT"
    assert detail["respondent_attitude"]["attitude"] == "NOT_RESPONDED"
    assert "尚未" in detail["respondent_attitude"]["position"]
    assert detail["dispute_core_state"]["conflict_type"] == "CLAIM_UNANSWERED"
    assert "退款" in detail["dispute_core_state"]["core_conflict"]
    assert detail["case_story"]["one_sentence_summary"].startswith("用户称")
    assert "我没有" not in detail["case_story"]["one_sentence_summary"]
    assert "本人没有收到包裹" in detail["case_story"]["one_sentence_summary"]
    assert "我没有" not in detail["party_positions"]["user_claim"]
    assert detail["party_positions"]["raw_statement"] == (
        "物流显示签收，但我没有收到包裹，希望退款。"
    )
    assert detail["intake_quality"]["score"] < 80
    assert detail["intake_quality"]["ready_for_next_step"] is False
    assert payload["admission_recommendation"] == "NEED_MORE_INFO"


def test_dossier_pins_seed_original_statement_and_marks_reported_attitude_subjective() -> None:
    original_statement = (
        "我买的是完整套装，收到后发现没有充电器，请补发。"
        "商家客服说不能补发。"
    )
    request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_original_pin",
                "room_type": "INTAKE",
                "turn_source": "LOBBY_SEED",
                "lobby_seed": {
                    "initiator_role": "USER",
                    "raw_text": "用户称套装缺少充电器并请求补发，并转述商家客服拒绝补发。",
                    "claim_resolution_seed": {
                        "initiator_role": "USER",
                        "requested_resolution": "RESHIP",
                        "request_reason": "套装配件缺失。",
                        "original_statement": original_statement,
                    },
                },
                "current_user_message": None,
                "latest_scroll_snapshot": None,
                "recent_turns": [],
            }
        )
    )

    result = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录。",
        llm_case_detail={
            "claim_resolution": {
                "original_statement": "模型把原话改写成了摘要。",
                "normalized_statement": "用户称套装缺少充电器，并请求补发。",
            },
            "respondent_attitude": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position": "发起方称商家客服拒绝补发。",
                "source": "商家正式回应",
                "confidence": 0.9,
            },
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    claim = result.scroll_snapshot["claim_resolution"]
    assert claim["original_statement"] == original_statement
    assert claim["normalized_statement"] == "用户称套装缺少充电器，并请求补发。"
    attitude = result.scroll_snapshot["respondent_attitude"]
    assert attitude["attitude"] == "DISAGREE"
    assert attitude["source"] == SUBJECTIVE_RESPONDENT_SOURCE
    assert attitude["confidence_note"] == SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE


def test_dossier_appends_unilateral_inputs_verbatim_in_submission_order() -> None:
    seed_original = "我收到的套装少了充电器。"
    first_supplement = "补充一下：商家客服说不能补发。"
    request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_original_round_one",
                "room_type": "INTAKE",
                "turn_source": "USER_MESSAGE",
                "lobby_seed": {
                    "initiator_role": "USER",
                    "raw_text": "用户称套装缺少充电器。",
                    "claim_resolution_seed": {
                        "requested_resolution": "RESHIP",
                        "original_statement": seed_original,
                    },
                },
                "current_user_message": {
                    "message_id": "MESSAGE_round_one",
                    "role": "USER",
                    "text": first_supplement,
                },
                "initiator_statement_transcript": [
                    {
                        "message_id": "MESSAGE_seed_original",
                        "role": "USER",
                        "text": seed_original,
                    },
                    {
                        "message_id": "MESSAGE_round_one",
                        "role": "USER",
                        "text": first_supplement,
                    },
                ],
                "latest_scroll_snapshot": None,
                "recent_turns": [
                    {
                        "turn_no": 1,
                        "actor_id": "USER_local_1",
                        "answer_role": "USER",
                        "answer_content": first_supplement,
                        "agent_role": None,
                        "agent_response": "这段接待官回复不能进入原始陈述。",
                        "scroll_snapshot": {},
                    }
                ],
            }
        )
    )
    first_result = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录。",
        llm_case_detail={"claim_resolution": {"original_statement": "错误改写"}},
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )
    expected_first = ORIGINAL_STATEMENT_SEPARATOR.join(
        [seed_original, first_supplement]
    )
    assert first_result.scroll_snapshot["claim_resolution"]["original_statement"] == (
        expected_first
    )

    second_supplement = "订单里的连接线也没有收到，包装内确实没有。"
    second_payload = {
        "case_id": "CASE_intake_original_round_two",
        "room_type": "INTAKE",
        "turn_source": "USER_MESSAGE",
        "lobby_seed": {
            "initiator_role": "USER",
            "raw_text": "用户称套装缺少充电器。",
            "claim_resolution_seed": {
                "requested_resolution": "RESHIP",
                "original_statement": seed_original,
            },
        },
        "current_user_message": {
            "message_id": "MESSAGE_round_two",
            "role": "USER",
            "text": second_supplement,
        },
        "initiator_statement_transcript": [
            {
                "message_id": "MESSAGE_seed_original",
                "role": "USER",
                "text": seed_original,
            },
            {
                "message_id": "MESSAGE_round_one",
                "role": "USER",
                "text": first_supplement,
            },
            {
                "message_id": "MESSAGE_round_two",
                "role": "USER",
                "text": second_supplement,
            },
        ],
        "latest_scroll_snapshot": first_result.scroll_snapshot,
        "recent_turns": [],
    }
    second_result = CaseDetailDossierSkill().render(
        request=IntakeTurnRequest.model_validate(_with_agent_context(second_payload)),
        room_utterance="已记录。",
        llm_case_detail={"claim_resolution": {"original_statement": "再次错误改写"}},
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )
    assert second_result.scroll_snapshot["claim_resolution"]["original_statement"] == (
        ORIGINAL_STATEMENT_SEPARATOR.join(
            [seed_original, first_supplement, second_supplement]
        )
    )


def test_dossier_does_not_substitute_summary_when_structured_seed_lacks_original() -> None:
    request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_original_missing",
                "room_type": "INTAKE",
                "turn_source": "LOBBY_SEED",
                "lobby_seed": {
                    "initiator_role": "USER",
                    "raw_text": "用户称套装缺少配件并请求补发。",
                    "claim_resolution_seed": {"requested_resolution": "RESHIP"},
                },
                "current_user_message": None,
                "latest_scroll_snapshot": None,
                "recent_turns": [],
            }
        )
    )

    result = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录。",
        llm_case_detail={"claim_resolution": {"original_statement": "摘要冒充原话"}},
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    assert result.scroll_snapshot["claim_resolution"]["original_statement"] == (
        ORIGINAL_STATEMENT_MISSING
    )


def test_dossier_without_structured_seed_keeps_legacy_source_text_verbatim() -> None:
    raw_text = "我收到的套装少了充电器和连接线。"
    request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_original_legacy",
                "room_type": "INTAKE",
                "turn_source": "LOBBY_SEED",
                "lobby_seed": {"initiator_role": "USER", "raw_text": raw_text},
                "current_user_message": None,
                "latest_scroll_snapshot": None,
                "recent_turns": [],
            }
        )
    )

    result = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录。",
        llm_case_detail={"claim_resolution": {"original_statement": "模型改写"}},
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    assert result.scroll_snapshot["claim_resolution"]["original_statement"] == raw_text


def test_formal_respondent_attitude_seed_and_legacy_snapshot_are_ignored() -> None:
    initial_request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_formal_attitude_ignored",
                "room_type": "INTAKE",
                "turn_source": "LOBBY_SEED",
                "lobby_seed": {
                    "initiator_role": "USER",
                    "raw_text": "用户请求补发缺少的配件。",
                    "respondent_attitude_seed": {
                        "respondent_role": "MERCHANT",
                        "attitude": "DISAGREE",
                        "position": "外部售后记录显示商家拒绝补发。",
                        "source": "外部售后状态",
                        "confidence": 0.95,
                    },
                },
                "current_user_message": None,
                "latest_scroll_snapshot": {
                    "schema_version": "intake_case_detail.v1",
                    "respondent_attitude": {
                        "respondent_role": "MERCHANT",
                        "attitude": "DISAGREE",
                        "position": "历史卷宗声称商家已正式拒绝补发。",
                        "source": "商家正式回应",
                        "confidence": 0.99,
                    },
                },
                "recent_turns": [],
            }
        )
    )
    result = CaseDetailDossierSkill().render(
        request=initial_request,
        room_utterance="已记录。",
        llm_case_detail={
            "respondent_attitude": {
                "attitude": "NOT_RESPONDED",
                "position": "商家尚未在接待室表达态度。",
            }
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    attitude = result.scroll_snapshot["respondent_attitude"]
    assert attitude["attitude"] == "NOT_RESPONDED"
    assert attitude["source"] == "尚未回应"
    assert "正式" not in attitude["position"]


def test_subjective_respondent_attitude_seed_keeps_subjective_confidence_note() -> None:
    request = IntakeTurnRequest.model_validate(
        _with_agent_context(
            {
                "case_id": "CASE_intake_subjective_attitude_seed",
                "room_type": "INTAKE",
                "turn_source": "LOBBY_SEED",
                "lobby_seed": {
                    "initiator_role": "USER",
                    "raw_text": "用户称商家客服拒绝补发缺少的配件。",
                    "respondent_attitude_seed": {
                        "respondent_role": "MERCHANT",
                        "attitude": "DISAGREE",
                        "position": "发起方称商家客服拒绝补发。",
                        "source": SUBJECTIVE_RESPONDENT_SOURCE,
                        "confidence": 0.88,
                    },
                },
                "current_user_message": None,
                "latest_scroll_snapshot": None,
                "recent_turns": [],
            }
        )
    )

    result = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录。",
        llm_case_detail=None,
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    attitude = result.scroll_snapshot["respondent_attitude"]
    assert attitude["source"] == SUBJECTIVE_RESPONDENT_SOURCE
    assert attitude["confidence"] == 0.88
    assert attitude["confidence_note"] == SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE


def test_user_message_turn_merges_previous_case_detail_board() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json=_with_agent_context({
            "case_id": "CASE_intake_turn_memory",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "after_sales_reference": "AS_456",
                "logistics_reference": "SF1234567890",
                "initiator_role": "USER",
                "raw_text": "物流显示签收，但我没有收到包裹。",
            },
            "current_user_message": {
                "message_id": "MESSAGE_123",
                "role": "USER",
                "text": "我补充：商家客服让我找平台处理，我希望退款。",
            },
            "latest_scroll_snapshot": {
                "schema_version": "intake_case_detail.v1",
                "party_positions": {
                    "merchant_claim": "商家认为物流已签收。",
                },
                "requested_resolution": {
                    "requested_outcome": "REPLACEMENT",
                    "expected_resolution_text": "之前误填为换货。",
                },
            },
            "recent_turns": [],
        }),
    )

    assert response.status_code == 200
    detail = response.json()["scroll_snapshot"]
    assert detail["party_positions"]["merchant_claim"] == "商家认为物流已签收。"
    assert detail["requested_resolution"]["requested_outcome"] == "REFUND"
    assert detail["references"]["logistics_reference"] == "SF1234567890"


def test_user_message_turn_extracts_logistics_reference_from_current_message() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json=_with_agent_context({
            "case_id": "CASE_intake_turn_logistics_ref",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "after_sales_reference": "AS_456",
                "initiator_role": "USER",
                "raw_text": "Package shows signed but I did not receive it.",
            },
            "current_user_message": {
                "message_id": "MESSAGE_logistics_ref",
                "role": "USER",
                "text": (
                    "I found the tracking number SF1234567890. "
                    "The merchant replied that I should wait for logistics verification."
                ),
            },
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        }),
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["scroll_snapshot"]["references"]["logistics_reference"] == "SF1234567890"
    assert "LOGISTICS_REFERENCE" not in payload["missing_fields"]
    assert any(
        operation["type"] == "UPSERT_CASE_DETAIL"
        for operation in payload["canvas_operations"]
    )


def test_intake_turn_response_exposes_memeo_memory_frame() -> None:
    client = _client()
    recent_turns = []
    for turn_no in range(1, 8):
        recent_turns.append(
            {
                "turn_no": turn_no,
                "actor_id": "user-local",
                "answer_role": "USER",
                "answer_content": f"user memory round {turn_no}",
                "agent_role": None,
                "agent_response": None,
                "scroll_snapshot": {},
            }
        )
        recent_turns.append(
            {
                "turn_no": turn_no,
                "actor_id": "dispute-intake-officer",
                "answer_role": None,
                "answer_content": None,
                "agent_role": "DISPUTE_INTAKE_OFFICER",
                "agent_response": f"agent memory round {turn_no}",
                "scroll_snapshot": {},
            }
        )

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json=_with_agent_context({
            "case_id": "CASE_intake_turn_memory_frame",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "initiator_role": "USER",
                "raw_text": "Package signed but not received.",
            },
            "current_user_message": {
                "message_id": "MESSAGE_memory_frame",
                "role": "USER",
                "text": "I contacted the merchant and want a refund.",
            },
            "latest_scroll_snapshot": None,
            "recent_turns": recent_turns,
        }),
    )

    assert response.status_code == 200
    memory_frame = response.json()["memory_frame"]
    assert [item["turn_no"] for item in memory_frame["short_term_rounds"]] == [
        3,
        4,
        5,
        6,
        7,
    ]
    assert "user memory round 2" not in memory_frame["prompt_memory"]
    assert "user memory round 7" in memory_frame["prompt_memory"]
    assert memory_frame["long_term_slots"] == []


def test_process_question_marks_knowledge_query_intent_without_real_rag() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json=_with_agent_context({
            "case_id": "CASE_intake_turn_qa",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "initiator_role": "USER",
                "raw_text": "我想咨询处理时效。",
            },
            "current_user_message": {
                "message_id": "MESSAGE_qa",
                "role": "USER",
                "text": "这种签收未收到的平台规则一般多久处理？",
            },
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        }),
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["knowledge_query_intent"] is True
    assert payload["knowledge_answer_mode"] == "STUB"
    assert "知识库插件" in payload["room_utterance"]


def test_intake_turn_route_requires_service_secret() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers={"X-Service-Secret": "wrong-secret"},
        json=_with_agent_context({
            "case_id": "CASE_intake_turn_auth",
            "room_type": "INTAKE",
            "turn_source": "LOBBY_SEED",
            "lobby_seed": {
                "initiator_role": "USER",
                "raw_text": "我要发起争议。",
            },
            "current_user_message": None,
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        }),
    )

    assert response.status_code == 401
