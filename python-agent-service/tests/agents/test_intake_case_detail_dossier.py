# 文件作用：自动化测试文件，验证 test_intake_case_detail_dossier 相关模块的行为、契约或页面布局。

from __future__ import annotations

import copy
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.agents.dispute_intake_officer.schemas import (
    IntakeCaseDetailLlmOutput,
    IntakeFreshFormOpeningLlmOutput,
    IntakeRemarkAcknowledgementLlmOutput,
)
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
    DIRECT_RESPONDENT_SOURCE,
    ORIGINAL_STATEMENT_SEPARATOR,
    RESPONDENT_AUTHORED_CURRENT_MESSAGE,
    SUBJECTIVE_RESPONDENT_SOURCE,
    SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    _canonical_verification_focus,
    _enforce_case_story_summary,
    _enforce_claim_resolution,
    _enforce_respondent_attitude_source,
    _question_targets_resolved_intake_field,
    _reported_attitude_position,
    detect_direct_respondent_attitude,
)
from app.llm import AgentOutputSchemaError
from app.schemas import IntakeTurnRequest
from app.streaming import (
    AgentStreamObserver,
    IncrementalVisibleJsonProjector,
    bind_stream_observer,
    current_stream_observer,
)
from tests.agents.intake_v3_fixture import intake_initiator_v3_payload


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：模块私有业务函数。
# 具体功能：`_agent_context` 围绕案件与会话上下文计算该函数独立负责的业务派生值；返回/更新字段：`tenant_id`、`case_id`、`room_type`、`actor_id`。
# 上下游：上游为 本文件的 `_request`、`test_current_message_is_not_duplicated_in_summary_or_original_statement`；下游为 返回/更新 `tenant_id`、`case_id`、`room_type`、`actor_id`。
# 系统意义：控制隐私、Token 和会话隔离：服从角色权限、上下文范围和非最终结论边界。
def _agent_context(case_id: str) -> dict[str, object]:
    prompt_profile_id = "DISPUTE_INTAKE_OFFICER:USER:v1"
    access_session_id = f"ACCESS_{case_id}_USER"
    actor_id = "USER_local_1"
    actor_role = "USER"
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "INTAKE",
        "actor_id": actor_id,
        "actor_role": actor_role,
        "access_session_id": access_session_id,
        "permission_level": "PARTY_USER",
        "permission_scopes": [],
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_invocation_id": f"INVOCATION_{case_id}",
        "agent_session_id": f"SESSION_{case_id}_user_intake",
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


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：模块私有业务函数。
# 具体功能：`_request` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`payload.update`、`payload.pop`、`current.setdefault`。
# 上下游：上游为 本文件的 `test_intake_turn_workflow_lives_under_agent_package_and_outputs_case_detail`、`test_intake_case_detail_readiness_is_gated_by_score_and_required_references`、`test_intake_case_detail_preserves_model_human_missing_fields_without_rewriting`、`test_ready_intake_turn_asks_for_handoff_remark_before_next_room`；下游为 本文件的 `_agent_context`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _request(**overrides):
    payload = {
        "case_id": "CASE_intake_case_detail",
        "room_type": "INTAKE",
        "turn_source": "ROOM_MESSAGE",
        "initial_case_facts": None,
        "current_user_message": {
            "message_id": "MESSAGE_1001",
            "sequence_no": 2,
            "role": "USER",
            "source": "ROOM_MESSAGE",
            "text": "我补充：商家没有给签收底单，我希望退款。订单和物流信息都在右侧。",
        },
        "recent_dialogue_messages": [
            {
                "message_id": "MESSAGE_OPENING",
                "sequence_no": 1,
                "role": "AGENT",
                "source": "AGENT_RESPONSE",
                "text": "请补充本案仍不清楚的事实。",
            }
        ],
        "previous_case_detail": {
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_1001",
                "after_sales_reference": "AS_1001",
                "logistics_reference": "SF1001001001",
            },
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
            },
        },
    }
    payload.update(overrides)
    legacy_seed = payload.pop("lobby_seed", None)
    if isinstance(legacy_seed, dict):
        legacy_seed.pop("raw_text", None)
        previous = dict(payload.get("previous_case_detail") or {})
        previous["schema_version"] = "intake_case_detail.v1"
        previous["references"] = {
            "order_reference": legacy_seed.get("order_reference") or "",
            "after_sales_reference": legacy_seed.get("after_sales_reference") or "",
            "logistics_reference": legacy_seed.get("logistics_reference") or "",
        }
        previous["claim_resolution"] = {
            "initiator_role": legacy_seed.get("initiator_role") or "USER",
            "requested_resolution": legacy_seed.get("requested_outcome_hint")
            or "REFUND",
        }
        payload["previous_case_detail"] = previous
    if "latest_scroll_snapshot" in payload:
        payload["previous_case_detail"] = payload.pop("latest_scroll_snapshot")
    payload.pop("recent_turns", None)
    current = payload["current_user_message"]
    current.setdefault("sequence_no", 2)
    current.setdefault("source", payload["turn_source"])
    payload.setdefault("recent_dialogue_messages", [])
    payload.setdefault("agent_context", _agent_context(str(payload["case_id"])))
    return IntakeTurnRequest.model_validate(payload)


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_verification_focus_merges_names_gaps_questions_and_actions` 把本阶段状态写入或合并到可追溯的阶段状态；关键协作调用：`_canonical_verification_focus`、`item.endswith`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `_canonical_verification_focus`、`item.endswith`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_verification_focus_merges_names_gaps_questions_and_actions() -> None:
    result = _canonical_verification_focus(
        [
            "开箱视频/照片",
            "商品页面截图",
            "沟通记录",
            "物流签收细节",
            "缺少开箱视频或照片以客观记录磨损情况",
            "缺少商品页面完整截图或快照",
            "缺少用户与商家的聊天记录",
            "缺少物流签收状态和用户是否当场验货的信息",
            "请问您是否有收到包裹时的开箱视频或照片？",
            "能否提供商品页面的完整截图？",
            "您与商家的沟通记录是否可以提供？",
            "物流显示签收了吗？您是签收后多久打开检查的？",
            "开箱视频",
            "照片",
            "核对商品页面描述截图或快照",
            "获取用户开箱照片或视频",
            "核实物流签收时间与用户开启包裹的间隔",
            "获取用户与商家的完整沟通记录",
        ]
    )

    assert result == [
        "核验商品异常照片或开箱视频，确认商品状态及形成时间",
        "核对商品页面完整描述、截图或快照",
        "核验用户与商家的完整沟通记录",
        "核验物流签收及投递记录，确认签收人身份、位置、时间与开箱检查间隔",
    ]
    assert all(not item.endswith(("?", "？")) for item in result)


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_promotion_case_focus_collapses_questions_into_three_audit_actions` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`_canonical_verification_focus`、`item.endswith`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `_canonical_verification_focus`、`item.endswith`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_promotion_case_focus_collapses_questions_into_three_audit_actions() -> None:
    result = _canonical_verification_focus(
        [
            "直播间宣传内容及主播承诺的具体规则",
            "活动规则及名额状态",
            "用户联系客服的具体时间及客服回复原文",
            "商家对用户补偿诉求的正式态度",
            "您是通过哪个直播间下单的？主播当时具体怎么承诺返现？",
            "7月12日订单完成后，您是什么时候联系客服的？",
        ]
    )

    assert result == [
        "核验直播宣传承诺、适用条件与活动规则",
        "核验用户与商家的完整沟通记录",
        "核实商家对诉求的明确回应",
    ]
    assert all(not item.endswith(("?", "？")) for item in result)


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_subscription_focus_collapses_to_four_business_audit_actions` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`_canonical_verification_focus`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `_canonical_verification_focus`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_subscription_focus_collapses_to_four_business_audit_actions() -> None:
    result = _canonical_verification_focus(
        [
            "用户发现自动续费扣款的具体时间和金额",
            "是否收到续费提醒及短信、邮件或App推送渠道",
            "扣款后是否使用新周期服务",
            "用户是否已经联系商家客服，商家如何回应",
            "您是在什么时间发现这笔扣款的？",
        ],
        respondent_role="MERCHANT",
    )

    assert result == [
        "核验自动续费扣款时间、金额与服务周期",
        "核验续费提醒的发送时间、渠道与显著性",
        "核验新周期服务是否实际使用",
        "核验用户与商家的完整沟通记录",
    ]


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_current_message_is_not_duplicated_in_summary_or_original_statement` 验证房间消息在固定案例中的输出、边界和失败行为；关键协作调用：`IntakeTurnRequest.model_validate`、`render`、`count`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_agent_context`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_current_message_is_not_duplicated_in_summary_or_original_statement() -> None:
    current_text = (
        "我在7月10日晚上8点通过品牌官方直播间下单，主播明确说订单完成后返现120元，"
        "没有说明名额限制。7月12日订单完成后我立即联系客服，客服回复活动名额已满，"
        "拒绝返现。除此之外没有其他沟通，商家目前明确不同意补偿。"
    )
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": "CASE_intake_no_duplicate",
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "initial_case_facts": None,
            "current_user_message": {
                "message_id": "MESSAGE_current",
                "sequence_no": 2,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": current_text,
            },
            "recent_dialogue_messages": [
                {
                    "message_id": "MESSAGE_opening",
                    "sequence_no": 1,
                    "role": "AGENT",
                    "source": "AGENT_RESPONSE",
                    "text": "请补充下单时间、客服回复和商家态度。",
                }
            ],
            "previous_case_detail": {
                "schema_version": "intake_case_detail.v1",
                "case_story": {
                    "one_sentence_summary": "用户称直播间承诺返现120元，但客服表示名额已满。"
                },
                "claim_resolution": {
                    "initiator_role": "USER",
                    "requested_resolution": "COMPENSATION",
                },
            },
            "initiator_statement_transcript": [
                {
                    "message_id": "INTAKE_TURN_2",
                    "role": "USER",
                    "text": current_text,
                }
            ],
            "agent_context": _agent_context("CASE_intake_no_duplicate"),
        }
    )
    rendered = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录本轮补充。",
        llm_case_detail={
            "schema_version": "intake_case_detail.v1",
            "case_story": {
                "one_sentence_summary": (
                    "用户称7月10日晚上8点通过品牌官方直播间下单购买咖啡机，"
                    "主播承诺订单完成后返现120元且未说明名额限制，7月12日订单完成后"
                    "联系客服被告知活动名额已满，商家明确不同意补偿。"
                )
            },
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "COMPENSATION",
                "normalized_statement": "用户请求商家补偿120元。",
            },
            "missing_information": {
                "blocking_gaps": [],
                "nice_to_have_gaps": [],
                "next_questions": [],
            },
            "intake_quality": {"score": 75},
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.75,
    )

    detail = rendered.scroll_snapshot
    assert detail["claim_resolution"]["original_statement"] == current_text
    assert detail["case_story"]["one_sentence_summary"].count("7月10日晚上8点") == 1
    assert detail["case_story"]["one_sentence_summary"].count("商家明确不同意补偿") == 1


def test_real_intake_replay_keeps_summary_attitude_statement_and_focus_clean() -> None:
    form_text = (
        "我购买轻薄笔记本电脑后正常使用十天，电脑开始频繁自动关机。"
        "按商家指导完成远程排障和恢复出厂设置后仍未解决。"
        "商家表示超过七天不支持换货，只同意由我付费维修。"
        "我希望换货；如无法换货，则要求免费维修。"
    )
    opening = IntakeTurnRequest.model_validate(
        {
            "case_id": "CASE_intake_e2e_quality",
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": form_text,
                "order_reference": "ORDER-E2E-001",
                "after_sales_reference": "AFTERSALE-E2E-001",
                "logistics_reference": "LOGISTICS-E2E-001",
                "initiator_role": "USER",
                "requested_outcome_hint": "REPLACE_OR_REPAIR",
            },
            "current_user_message": None,
            "recent_dialogue_messages": [],
            "previous_case_detail": None,
            "initiator_statement_transcript": [],
            "agent_context": _agent_context("CASE_intake_e2e_quality"),
        }
    )
    first = CaseDetailDossierSkill().render(
        request=opening,
        room_utterance="请补充商家的检测结论和您的处理诉求。",
        llm_case_detail={
            "case_story": {
                "one_sentence_summary": (
                    "用户称笔记本电脑使用十天后频繁自动关机，远程排障和恢复出厂设置"
                    "仍未解决，商家拒绝换货且仅同意付费维修，用户要求换货或免费维修。"
                )
            },
            "respondent_attitude": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                # Reproduce the bad model output: it copied the entire form.
                "position": form_text,
                "confidence": 0.8,
            },
            "dispute_core_state": {
                "next_verification_focus": [
                    "等待接待官完成案件详情整理",
                    "核实商品故障原因及是否属于质量问题",
                    "核实商品故障原因与质量问题",
                    "信息完整度已达到提交阈值",
                    "确认商家对换货与维修的处理意见",
                ]
            },
            "intake_quality": {"score": 70},
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    first_detail = first.scroll_snapshot
    assert first_detail["claim_resolution"]["original_statement"] == form_text
    assert first_detail["respondent_attitude"]["source"] == SUBJECTIVE_RESPONDENT_SOURCE
    assert first_detail["respondent_attitude"]["position"] == (
        "商家表示超过七天不支持换货，只同意由我付费维修。"
    )
    assert all(
        "完整度" not in item and "等待接待官" not in item
        for item in first_detail["dispute_core_state"]["next_verification_focus"]
    )
    assert len(first_detail["dispute_core_state"]["next_verification_focus"]) <= 4

    supplement = (
        "商家没有给出书面检测结论，只说系统超过七天不能换货。"
        "我优先要求换货；若确实无法换货，可以接受商家免费维修，"
        "但不能让我承担维修费。"
    )
    cumulative_summary = (
        "用户称购买笔记本电脑并正常使用约十天后频繁自动关机，按商家指导远程排障"
        "及恢复出厂设置仍未解决；商家未出具书面检测结论并口头表示超过七天不能换货，"
        "用户优先要求换货，无法换货时接受免费维修但不承担维修费。"
    )
    second_request = IntakeTurnRequest.model_validate(
        {
            "case_id": "CASE_intake_e2e_quality",
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "initial_case_facts": None,
            "current_user_message": {
                "message_id": "MESSAGE_supplement",
                "sequence_no": 2,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": supplement,
            },
            "recent_dialogue_messages": [
                {
                    "message_id": "MESSAGE_opening",
                    "sequence_no": 1,
                    "role": "AGENT",
                    "source": "AGENT_RESPONSE",
                    "text": "请补充商家的检测结论和您的处理诉求。",
                }
            ],
            "previous_case_detail": first_detail,
            "initiator_statement_transcript": [
                {
                    "message_id": "INTAKE_TURN_2",
                    "role": "USER",
                    "text": supplement,
                }
            ],
            "agent_context": _agent_context("CASE_intake_e2e_quality"),
        }
    )
    second = CaseDetailDossierSkill().render(
        request=second_request,
        room_utterance="当前案情已可以提交。",
        llm_case_detail={
            "case_story": {"one_sentence_summary": cumulative_summary},
            "respondent_attitude": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position": "商家口头表示系统超过七天不能换货，未出具书面检测结论。",
                "confidence": 0.8,
            },
            "dispute_core_state": {
                "next_verification_focus": [
                    "核验商家是否已出具书面检测结论或故障原因说明",
                    "确认商家对免费维修及维修费用承担的最终处理方案",
                    "核实商家对诉求的明确回应",
                    "核验信息完整度已达到提交阈值",
                ]
            },
            "intake_quality": {"score": 88},
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="ACCEPTED",
        llm_missing_fields=[],
        llm_confidence=0.8,
    )

    second_detail = second.scroll_snapshot
    assert second_detail["case_story"]["one_sentence_summary"] == cumulative_summary
    assert second_detail["case_story"]["one_sentence_summary"].count("没有给出") == 0
    assert second_detail["claim_resolution"]["original_statement"] == (
        form_text + ORIGINAL_STATEMENT_SEPARATOR + supplement
    )
    assert second_detail["respondent_attitude"]["position"] == (
        "商家没有给出书面检测结论，只说系统超过七天不能换货。"
    )
    second_focus = second_detail["dispute_core_state"]["next_verification_focus"]
    assert len(second_focus) <= 4
    assert not any("完整度" in item for item in second_focus)
    assert sum("商家" in item and "诉求" in item for item in second_focus) <= 1


def test_prior_attitude_grounding_carries_exactly_and_factual_only_response_stays_absent() -> None:
    base_attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "The merchant explicitly rejected the requested refund.",
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    }
    for grounding in (
        {"source": "INITIAL_FORM", "message_id": ""},
        {
            "source": "PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_PRIOR_ATTITUDE",
        },
    ):
        prior_attitude = {**base_attitude, "grounding": grounding}
        previous = {
            "schema_version": "intake_case_detail.v1",
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
            },
            "respondent_attitude": prior_attitude,
        }
        request = _request(
            current_user_message={
                "message_id": "MESSAGE_NEUTRAL_FACT",
                "sequence_no": 5,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "The order reference was corrected in this turn.",
            },
            previous_case_detail=previous,
            initiator_statement_transcript=[
                {
                    "message_id": "MESSAGE_PRIOR_ATTITUDE",
                    "role": "USER",
                    "text": base_attitude["position"],
                }
            ],
        )
        detail = {"respondent_attitude": dict(prior_attitude)}

        _enforce_respondent_attitude_source(detail, request, previous, None)

        assert detail["respondent_attitude"] == prior_attitude

    case_id = "CASE_respondent_factual_only"
    merchant_context = _agent_context(case_id)
    merchant_context.update(
        actor_id="MERCHANT_local_1",
        actor_role="MERCHANT",
        permission_level="PARTY_MERCHANT",
        scope_type="INTAKE_PARTY_PRIVATE",
        allowed_actor_ids=["MERCHANT_local_1"],
        allowed_actor_roles=["MERCHANT"],
    )
    previous = {
        "schema_version": "intake_case_detail.v1",
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
        },
        "case_fact_matrix": {
            "schema_version": "case_fact_matrix.v2",
            "party_map": {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
            },
        },
    }
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_MERCHANT_FACT_ONLY",
                "sequence_no": 2,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": "The service reference number is SERVICE-2026-001.",
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": previous,
            "agent_context": merchant_context,
        }
    )
    detail = {
        "respondent_attitude": {
            "respondent_role": "MERCHANT",
            "attitude": "NOT_RESPONDED",
            "position": "The respondent has not expressed an attitude.",
            "source": "尚未回应",
            "confidence": 0.5,
        }
    }

    _enforce_respondent_attitude_source(detail, request, previous, None)
    factual_only = detail["respondent_attitude"]

    assert factual_only["attitude"] == "NOT_RESPONDED"
    assert factual_only["source"] != DIRECT_RESPONDENT_SOURCE
    assert "grounding" not in factual_only


@pytest.mark.parametrize(
    "provider_attitude",
    [
        pytest.param(
            {
                "attitude": "AGREE",
                "position": "Provider inverted the attributed stance.",
                "confidence": 0.97,
                "extensions": {"provider_only": "must-not-survive"},
            },
            id="wrong-substantive",
        ),
        pytest.param(
            {
                "attitude": "ALTERNATIVE_PROPOSED",
                "position": "Provider invented an alternative response.",
                "confidence": 0.96,
                "extensions": {"provider_only": "must-not-survive"},
            },
            id="wrong-alternative",
        ),
        pytest.param(
            {
                "attitude": "DISAGREE",
                "position": "Provider wording is not source authority.",
                "confidence": 0.95,
                "extensions": {"provider_only": "must-not-survive"},
            },
            id="correct-code-wrong-position-confidence",
        ),
        pytest.param(
            {
                "attitude": "NOT_RESPONDED",
                "position": "Provider emitted an absence placeholder.",
                "confidence": 0.94,
                "extensions": {"provider_only": "must-not-survive"},
            },
            id="not-responded-alias",
        ),
        pytest.param(
            {
                "status": "UNKNOWN",
                "description": "Provider emitted a legacy absence placeholder.",
                "extensions": {"provider_only": "must-not-survive"},
            },
            id="legacy-unknown-alias",
        ),
    ],
)
def test_initiator_attributed_attitude_canonicalizes_the_entire_provider_branch(
    provider_attitude: dict[str, object],
) -> None:
    message_id = "MESSAGE_USER_CURRENT_REPORTED_ATTITUDE"
    current_text = "The merchant explicitly rejected the requested refund."
    prior_attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "AGREE",
        "position": "The prior report must be superseded by the fresh source.",
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        "grounding": {
            "source": "PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_USER_PRIOR_REPORTED_ATTITUDE",
        },
    }
    previous = {
        "schema_version": "intake_case_detail.v1",
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
        },
        "respondent_attitude": prior_attitude,
        "case_fact_matrix": {
            "party_map": {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
            },
        },
    }
    request = _request(
        current_user_message={
            "message_id": message_id,
            "sequence_no": 6,
            "role": "USER",
            "source": "ROOM_MESSAGE",
            "text": current_text,
        },
        previous_case_detail=previous,
        initiator_statement_transcript=[
            {
                "message_id": "MESSAGE_USER_PRIOR_REPORTED_ATTITUDE",
                "role": "USER",
                "text": prior_attitude["position"],
            },
            {
                "message_id": message_id,
                "role": "USER",
                "text": current_text,
            },
        ],
    )
    detail = {"respondent_attitude": dict(provider_attitude)}
    llm_case_detail = {"respondent_attitude": dict(provider_attitude)}

    _enforce_respondent_attitude_source(
        detail,
        request,
        previous,
        llm_case_detail,
    )

    assert detail["respondent_attitude"] == {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": current_text,
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": 0.65,
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        "grounding": {
            "source": "PARTICIPANT_MESSAGE",
            "message_id": message_id,
        },
    }


@pytest.mark.parametrize(
    ("text", "expected", "provide_candidate"),
    [
        ("We did not cause X, but we accept Y.", "AGREE", False),
        ("Our company accepts Y.", "AGREE", True),
        ("我方并不同意Y。", "DISAGREE", False),
        ("我方没有同意Y。", "DISAGREE", True),
        pytest.param(
            "我方先说明处理边界。不接受该请求。",
            "DISAGREE",
            False,
            id="authoritative-hard-boundary-zero-subject",
        ),
        pytest.param(
            "不接受该请求。",
            "DISAGREE",
            False,
            id="authoritative-first-clause-zero-subject",
        ),
        pytest.param(
            "我方不接受对方提出的处理方案。",
            "DISAGREE",
            False,
            id="self-attitude-with-third-party-proposal-object",
        ),
        pytest.param(
            "不接受对方提出的处理方案。",
            "DISAGREE",
            False,
            id="omitted-self-with-third-party-proposal-object",
        ),
        pytest.param(
            "我方不接受原处理请求并提出替代方案。",
            "ALTERNATIVE_PROPOSED",
            True,
            id="single-clause-consistent-alternative",
        ),
        pytest.param(
            "我方不接受原处理请求。提出替代方案。",
            "ALTERNATIVE_PROPOSED",
            False,
            id="cross-clause-consistent-alternative",
        ),
    ],
)
def test_direct_respondent_adversarial_substantive_signal_updates_current_grounding(
    text: str,
    expected: str,
    provide_candidate: bool,
) -> None:
    case_id = "CASE_direct_attitude_adversarial"
    message_id = "MESSAGE_MERCHANT_CURRENT_ATTITUDE"
    merchant_context = _agent_context(case_id)
    merchant_context.update(
        actor_id="MERCHANT_local_1",
        actor_role="MERCHANT",
        permission_level="PARTY_MERCHANT",
        scope_type="INTAKE_PARTY_PRIVATE",
        allowed_actor_ids=["MERCHANT_local_1"],
        allowed_actor_roles=["MERCHANT"],
    )
    prior_attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE" if expected == "AGREE" else "AGREE",
        "position": "The prior respondent attitude must not be carried.",
        "source": DIRECT_RESPONDENT_SOURCE,
        "confidence": 0.7,
        "grounding": {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_MERCHANT_PRIOR_ATTITUDE",
        },
    }
    previous = {
        "respondent_attitude": prior_attitude,
        "case_fact_matrix": {
            "party_map": {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
            }
        },
    }
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": message_id,
                "sequence_no": 2,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": text,
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": previous,
            "agent_context": merchant_context,
        }
    )
    detail = {"respondent_attitude": dict(prior_attitude)}
    llm_case_detail = (
        {
            "respondent_attitude": {
                "attitude": expected,
                "position": text,
                "confidence": 0.9,
            }
        }
        if provide_candidate
        else None
    )

    _enforce_respondent_attitude_source(
        detail,
        request,
        previous,
        llm_case_detail,
    )

    attitude = detail["respondent_attitude"]
    assert attitude["respondent_role"] == "MERCHANT"
    assert attitude["attitude"] == expected
    assert attitude["position"] == text
    assert attitude["source"] == DIRECT_RESPONDENT_SOURCE
    assert attitude["grounding"] == {
        "source": "RESPONDENT_PARTICIPANT_MESSAGE",
        "message_id": message_id,
    }


def test_direct_respondent_hard_boundary_subject_requires_exact_authority() -> None:
    texts = (
        "不接受该请求。",
        "我方先说明处理边界。不接受该请求。",
    )

    for text in texts:
        assert detect_direct_respondent_attitude(text).state == "UNRESOLVED"
        assert (
            detect_direct_respondent_attitude(
                text,
                source_authority="UNVERIFIED_RESPONDENT_MESSAGE",
            ).state
            == "UNRESOLVED"
        )

        detection = detect_direct_respondent_attitude(
            text,
            source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
        )
        assert detection.state == "SUBSTANTIVE"
        assert detection.candidate == {
            "attitude": "DISAGREE",
            "position": text,
            "confidence": 0.65,
        }


@pytest.mark.parametrize(
    ("text", "expected_attitude"),
    [
        (
            "商家确认系统发货清单中列有智能手表、充电器和连接线。"
            "正常出库流程是拣货员按清单配齐后，由复核员扫描商品条码并封箱；"
            "但经查本单只能找到系统扫描记录，目前找不到装箱影像或复核员纸质签名，"
            "因此我们无法排除仓库漏装。"
            "我们对用户提出的补发诉求部分同意："
            "愿意在平台核验后补发缺失的充电器和连接线，不主张用户承担费用。",
            "PARTIALLY_AGREE",
        ),
        (
            "商家确认该订单应当出库黑色 256GB 手机。"
            "本单目前找不到装箱照片或复核员签名，因此无法排除错拿白色基础型号。"
            "我们对用户的换货诉求同意：愿意免费更换为订单约定的黑色 256GB 手机，"
            "并承担错发商品退回及重新寄送的全部运费。",
            "AGREE",
        ),
    ],
)
def test_direct_respondent_accepts_a_participant_proposal_as_own_attitude(
    text: str,
    expected_attitude: str,
) -> None:

    detection = detect_direct_respondent_attitude(
        text,
        source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
        respondent_role="MERCHANT",
    )

    assert detection.state == "SUBSTANTIVE"
    assert detection.candidate == {
        "attitude": expected_attitude,
        "position": text,
        "confidence": 0.65,
    }


def test_direct_respondent_conditional_alternative_requires_disjoint_remedy_scopes_and_authority() -> None:
    disjoint_remedy_position = (
        "我方不同意退款。我们同意送检核查。"
        "若检测确认质量问题，我们愿意换货。"
    )
    same_remedy_overlap = (
        "我方不同意退款。我们同意送检核查。"
        "若检测确认质量问题，我们愿意退款。"
    )
    condition_only_investigation = (
        "我方不同意退款；如核查确认存在质量问题，则同意维修。"
    )
    precondition_remedy_position = (
        "我方不同意退款。我们同意送检核查。"
        "如果无法退款则可以维修。"
    )

    detection = detect_direct_respondent_attitude(
        disjoint_remedy_position,
        source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
        respondent_role="MERCHANT",
    )

    assert detection.state == "SUBSTANTIVE"
    assert detection.candidate == {
        "attitude": "ALTERNATIVE_PROPOSED",
        "position": disjoint_remedy_position,
        "confidence": 0.65,
    }
    overlap = detect_direct_respondent_attitude(
        same_remedy_overlap,
        source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
        respondent_role="MERCHANT",
    )
    missing_authority = detect_direct_respondent_attitude(
        disjoint_remedy_position,
        respondent_role="MERCHANT",
    )
    condition_only = detect_direct_respondent_attitude(
        condition_only_investigation,
        source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
        respondent_role="MERCHANT",
    )
    consequent_scoped = detect_direct_respondent_attitude(
        precondition_remedy_position,
        source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
        respondent_role="MERCHANT",
    )

    assert overlap.state == "UNRESOLVED"
    assert overlap.candidate is None
    assert missing_authority.state == "UNRESOLVED"
    assert missing_authority.candidate is None
    assert condition_only.state == "UNRESOLVED"
    assert condition_only.candidate is None
    assert consequent_scoped.state == "SUBSTANTIVE"
    assert consequent_scoped.candidate == {
        "attitude": "ALTERNATIVE_PROPOSED",
        "position": precondition_remedy_position,
        "confidence": 0.65,
    }


@pytest.mark.parametrize(
    "text",
    [
        "建议Y的是对方，不是我方。",
        "同意Y的是对方，我方未表态。",
        "The buyer accepted Y; our company only recorded it.",
        pytest.param(
            "We do not disagree with Y.",
            id="unsupported-double-negation",
        ),
        "We do not accept Y.",
        "I have not accepted Y.",
        "We accept no Y.",
        "We do not propose Y.",
        pytest.param(
            "我方同意方案A。不同意方案B。",
            id="true-mixed-codes",
        ),
        pytest.param(
            "我方同意方案A。对方表示不同意方案B。",
            id="resolved-plus-third-party-attribution",
        ),
        pytest.param(
            "我方仅记录对方表示同意方案A。",
            id="first-person-reported-speech",
        ),
        pytest.param(
            "我方不接受该请求。（对方意见）",
            id="deferred-parenthetical-attribution",
        ),
        pytest.param(
            "我方不接受该请求。以上是对方的意见。",
            id="deferred-trailing-attribution",
        ),
    ],
)
def test_direct_respondent_adversarial_text_does_not_override_typed_claim_or_prior(
    text: str,
) -> None:
    case_id = "CASE_direct_attitude_unresolved"
    merchant_context = _agent_context(case_id)
    merchant_context.update(
        actor_id="MERCHANT_local_1",
        actor_role="MERCHANT",
        permission_level="PARTY_MERCHANT",
        scope_type="INTAKE_PARTY_PRIVATE",
        allowed_actor_ids=["MERCHANT_local_1"],
        allowed_actor_roles=["MERCHANT"],
    )
    prior_attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "The prior respondent attitude must not resolve ambiguity.",
        "source": DIRECT_RESPONDENT_SOURCE,
        "confidence": 0.7,
        "grounding": {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_MERCHANT_PRIOR_ATTITUDE",
        },
    }
    previous = {
        "respondent_attitude": prior_attitude,
        "case_fact_matrix": {
            "party_map": {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
            }
        },
    }
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_MERCHANT_UNRESOLVED",
                "sequence_no": 2,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": text,
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": previous,
            "agent_context": merchant_context,
        }
    )
    detail = {"respondent_attitude": dict(prior_attitude)}

    _enforce_respondent_attitude_source(
        detail,
        request,
        previous,
        None,
        None,
    )

    assert detail["respondent_attitude"] == prior_attitude


def test_intake_model_output_requires_a_complete_case_summary_each_turn() -> None:
    with pytest.raises(ValueError, match="one_sentence_summary"):
        IntakeCaseDetailLlmOutput.model_validate(
            {
                "room_utterance": "已记录。",
                "case_detail": {
                    "case_story": {"title": "履约争议"},
                    "respondent_attitude": {"attitude": "DISAGREE"},
                },
                "unilateral_case_matrix": {
                    "fact_rows": [
                        {
                            "fact_key": "NEW_CASE_EVENT",
                            "category": "OTHER",
                            "fact_target": "发起方提交履约争议",
                            "materiality": "CORE",
                            "position_summary": "发起方提交履约争议。",
                            "asserted_value": "发起方提交履约争议",
                            "source_scope": "CURRENT_SOURCE",
                        }
                    ],
                    "summary_source_fact_keys": ["NEW_CASE_EVENT"],
                },
            }
        )


def test_intake_model_output_schema_requires_the_nested_case_summary() -> None:
    schema = IntakeFreshFormOpeningLlmOutput.model_json_schema()
    detail_schema = schema["$defs"]["IntakeFreshFormCaseDetail"]
    story_schema = schema["$defs"]["IntakeCaseStoryPatch"]
    remark_capable_schema = IntakeCaseDetailLlmOutput.model_json_schema()

    assert "case_detail" in schema["required"]
    assert "case_matrix_delta" in schema["required"]
    assert detail_schema["required"] == ["case_story"]
    assert detail_schema["properties"]["case_story"] == {
        "$ref": "#/$defs/IntakeCaseStoryPatch"
    }
    assert story_schema["required"] == ["one_sentence_summary"]
    assert story_schema["properties"]["one_sentence_summary"]["minLength"] == 1
    assert "required" not in remark_capable_schema["$defs"]["IntakeCaseDetailPatch"]


def test_intake_case_detail_patch_stays_a_dict_and_keeps_dynamic_branches() -> None:
    parsed = IntakeCaseDetailLlmOutput.model_validate(
        {
            "room_utterance": "已记录。",
            "case_detail": {
                "case_story": {
                    "one_sentence_summary": "用户提交履约争议。",
                    "event_timeline": [
                        {"event": "用户提交争议", "source": "USER_MESSAGE"}
                    ],
                },
                "missing_information": {
                    "blocking_gaps": ["商家回应"],
                    "next_questions": ["商家如何回应您的诉求？"],
                },
            },
            "unilateral_case_matrix": {
                "fact_rows": [
                    {
                        "fact_key": "NEW_CASE_EVENT",
                        "category": "OTHER",
                        "fact_target": "发起方提交履约争议",
                        "materiality": "CORE",
                        "position_summary": "发起方提交履约争议。",
                        "asserted_value": "发起方提交履约争议",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_CASE_EVENT"],
            },
        }
    )

    assert isinstance(parsed.case_detail, dict)
    assert parsed.case_detail["case_story"]["event_timeline"] == [
        {"event": "用户提交争议", "source": "USER_MESSAGE"}
    ]
    assert parsed.case_detail["missing_information"]["blocking_gaps"] == [
        "商家回应"
    ]
    assert "references" not in parsed.case_detail


@pytest.mark.parametrize(
    ("score", "model_utterance"),
    [
        (
            72,
            "我已记录您目前的说明。请上传开箱视频和聊天记录截图，可以吗？"
            "物流签收凭证是否也能提供？",
        ),
        (
            72,
            "请补充：1. 订单号是多少？2. 物流单号是多少？3. 商家如何回应您的诉求？",
        ),
        (
            88,
            "已记录本轮补充，当前信息已经可以提交。如有交接备注请继续说明。",
        ),
    ],
)
def test_intake_turn_preserves_model_room_utterance_verbatim(
    score: int,
    model_utterance: str,
) -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    result = IntakeTurnWorkflow(
        model_runner=CaseDetailRunner(score=score, room_utterance=model_utterance)
    ).run(_request())

    assert result.room_utterance == model_utterance
    assert result.scroll_snapshot["schema_version"] == "intake_case_detail.v1"
    assert result.dossier_patch["case_detail"]["case_story"]["title"] == (
        "物流显示签收但用户称未收到商品"
    )
    quality = result.scroll_snapshot["intake_quality"]
    assert quality["score"] == sum(quality["score_breakdown"].values()) == score
    assert quality["ready_for_next_step"] is (score >= 85)


def test_respondent_message_keeps_initiator_claim_but_isolates_original_statement() -> None:
    case_id = "CASE_respondent_claim_guard"
    context = _agent_context(case_id)
    context.update(
        {
            "actor_id": "MERCHANT_local_1",
            "actor_role": "MERCHANT",
            "permission_level": "PARTY_MERCHANT",
            "scope_type": "INTAKE_PARTY_PRIVATE",
            "allowed_actor_ids": ["MERCHANT_local_1"],
            "allowed_actor_roles": ["MERCHANT"],
        }
    )
    previous_claim = {
        "initiator_role": "USER",
        "requested_resolution": "REFUND",
        "requested_amount": 200,
        "requested_items": "安装服务",
        "request_reason": "用户称服务没有完成。",
        "normalized_statement": "用户请求退还200元服务费。",
        "original_statement": "这是发起方逐字原始陈述。",
        "original_statement_provenance": {
            "policy": "INITIATOR_INPUTS_V1",
            "last_message_id": "MESSAGE_USER_LAST",
            "submission_count": 1,
            "separator": "BLANK_LINE",
            "source": "INITIATOR_STATEMENT_TRANSCRIPT",
        },
    }
    previous = {
        "schema_version": "intake_case_detail.v1",
        "claim_resolution": previous_claim,
        "case_fact_matrix": {
            "schema_version": "case_fact_matrix.v2",
            "party_map": {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
            },
        },
    }
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_MERCHANT_REPLY",
                "sequence_no": 1,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": "我方不同意退款，只愿意免费维修。",
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": previous,
            "initiator_statement_transcript": [
                {
                    "message_id": "MESSAGE_MERCHANT_REPLY",
                    "role": "MERCHANT",
                    "text": "我方不同意退款，只愿意免费维修。",
                }
            ],
            "agent_context": context,
        }
    )
    detail = {
        "claim_resolution": {
            "initiator_role": "MERCHANT",
            "requested_resolution": "REPLACE_OR_REPAIR",
            "request_reason": "我方只愿意维修。",
            "normalized_statement": "商家请求改为维修。",
            "original_statement": "错误覆盖",
        }
    }

    _enforce_claim_resolution(detail, request, previous)

    assert detail["claim_resolution"] == {
        key: value
        for key, value in previous_claim.items()
        if key not in {"original_statement", "original_statement_provenance"}
    }
    assert detail["requested_resolution"]["requested_outcome"] == "REFUND"


def test_user_respondent_summary_uses_the_matrix_initiator_role() -> None:
    case_id = "CASE_user_respondent_summary"
    context = _agent_context(case_id)
    context["scope_type"] = "INTAKE_PARTY_PRIVATE"
    previous = {
        "case_story": {"one_sentence_summary": "商家称服务已经完成。"},
        "case_fact_matrix": {
            "schema_version": "case_fact_matrix.v2",
            "party_map": {
                "initiator_role": "MERCHANT",
                "respondent_role": "USER",
            },
        },
    }
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_USER_RESPONSE",
                "sequence_no": 1,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "我不同意，约定服务并没有完成。",
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": previous,
            "agent_context": context,
        }
    )
    summary = "商家主张服务已经完成，用户直接回应约定服务并未完成。"
    detail = {"case_story": {"one_sentence_summary": summary}}

    _enforce_case_story_summary(
        detail,
        request,
        previous,
        {"case_story": {"one_sentence_summary": summary}},
    )

    assert detail["case_story"]["one_sentence_summary"] == summary


def test_attitude_position_skips_counterparty_used_as_contact_object() -> None:
    statement = (
        "我立即联系商家要求换货，商家回复照片不能证明是收货时损坏，"
        "只愿意补偿50元，不同意换货。"
    )

    assert _reported_attitude_position(statement, "USER") == (
        "商家回复照片不能证明是收货时损坏，只愿意补偿50元，不同意换货。"
    )


def test_resolved_claim_guard_keeps_counterparty_response_question() -> None:
    case_detail = {
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
        }
    }

    assert not _question_targets_resolved_intake_field(
        "商家目前对这笔费用的退还诉求是否有过回应？",
        case_detail,
        actor_role="USER",
    )
    assert _question_targets_resolved_intake_field(
        "您的诉求是什么？",
        case_detail,
        actor_role="USER",
    )
    assert _question_targets_resolved_intake_field(
        "您的具体诉求是什么？",
        case_detail,
        actor_role="USER",
    )
    assert _question_targets_resolved_intake_field(
        "您的诉求是否为退款？",
        case_detail,
        actor_role="USER",
    )


def test_visible_follow_up_questions_replace_stale_structured_questions() -> None:
    utterance = (
        "已记录本轮补充。为了继续梳理，请补充："
        "1. 手机的具体内存容量是多少？"
        "2. 您是什么时间联系商家的？"
    )
    rendered = CaseDetailDossierSkill().render(
        request=_request(),
        room_utterance=utterance,
        llm_case_detail={
            "case_story": {
                "one_sentence_summary": "用户称商品状态存在争议，并补充商品尚未拆封激活。"
            },
            "missing_information": {
                "next_questions": ["用户是否已拆封或激活手机"]
            },
            "intake_quality": {"score": 75},
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.75,
    )

    assert rendered.scroll_snapshot["missing_information"]["next_questions"] == [
        "手机的具体内存容量是多少？",
        "您是什么时间联系商家的？",
    ]


class CaseDetailRunner:
    # 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 符合 Schema 的角色分析结果。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(
        self,
        score: int = 86,
        *,
        room_utterance: str | None = None,
        conversation_action: str | None = None,
    ) -> None:
        self.score = score
        self.conversation_action = conversation_action or (
            "INVITE_OPTIONAL_REMARK" if score >= 85 else "ASK_SUBSTANTIVE"
        )
        self.room_utterance = room_utterance or (
            "我已经了解本案的基本情况：物流显示签收但你主张未收到，"
            "商家暂未提供签收底单。右侧案件详情已整理到可进入下一步。"
        )
        self.calls: list[dict[str, object]] = []

    # 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`self.calls.append`、`SimpleNamespace`、`output_type`。
    # 上下游：上游为 本文件的 `test_intake_case_detail_preserves_model_human_missing_fields_without_rewriting`；下游为 协作调用 `self.calls.append`、`SimpleNamespace`、`output_type`、`context_pack.prompt_sections`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
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
        max_input_tokens=None,
    ):
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "context_sections": (
                    context_pack.prompt_sections()
                    if context_pack is not None
                    else context_sections
                ),
                "context_pack": context_pack,
                "agent_context": agent_context,
                "prompt_profile_id": prompt_profile_id,
                "max_input_tokens": max_input_tokens,
            }
        )
        if output_type.__name__ == "IntakeInitiatorRoomLlmOutputV3":
            return SimpleNamespace(
                value=output_type.model_validate(
                    intake_initiator_v3_payload(
                        room_utterance=self.room_utterance,
                        total_score=self.score,
                        conversation_action=self.conversation_action,
                    )
                )
            )
        return SimpleNamespace(
            value=output_type(
                conversation_action=self.conversation_action,
                room_utterance=self.room_utterance,
                unilateral_case_matrix=(
                    None
                    if self.conversation_action in {"ACK_REMARK", "ACK_NO_REMARK"}
                    else {
                        "fact_rows": [
                            {
                                "fact_key": "NEW_SIGNED_NOT_RECEIVED",
                                "category": "LOGISTICS",
                                "fact_target": "物流显示签收但发起方称未收到商品",
                                "materiality": "CORE",
                                "position_summary": "用户称物流显示签收但本人未收到商品。",
                                "asserted_value": "物流显示签收但用户未收到",
                                "source_scope": "CURRENT_SOURCE",
                            }
                        ],
                        "summary_source_fact_keys": ["NEW_SIGNED_NOT_RECEIVED"],
                    }
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


def _complete_scoring_case_detail(model_score: int) -> dict[str, object]:
    model_breakdown = {
        "references": 0,
        "event_story": 0,
        "party_positions": 0,
        "requested_resolution": 0,
        "risk_and_conflicts": 0,
        "next_action_clarity": 0,
    }
    if model_score > 85:
        model_breakdown = {key: 999 for key in model_breakdown}
    return {
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "title": "履约争议",
            "one_sentence_summary": "发起方报告履约状态与实际情况不一致。",
            "event_timeline": [
                {
                    "time_hint": "履约后",
                    "event": "当事方发现履约状态存在差异",
                    "source": "PARTY_MESSAGE",
                }
            ],
        },
        "party_positions": {
            "user_claim": "发起方请求核验履约状态并处理争议。",
            "merchant_claim": "",
            "platform_observation": "需要核对履约记录与当事方陈述。",
        },
        "dispute_focus": {
            "core_issue": "FULFILLMENT_STATUS_CONFLICT",
            "key_conflicts": ["FULFILLMENT_RECORD_CONFLICT"],
            "facts_to_verify": ["FULFILLMENT_RECORD"],
        },
        "requested_resolution": {
            "requested_outcome": "REFUND",
            "expected_resolution_text": "发起方请求退款。",
        },
        "risk_assessment": {
            "case_grade": "MEDIUM",
            "risk_signals": ["FULFILLMENT_CONFLICT"],
            "reasoning": "履约记录与当事方陈述需要核验。",
        },
        "missing_information": {
            "blocking_gaps": [],
            "nice_to_have_gaps": [],
            "next_questions": [],
        },
        "intake_quality": {
            "score": model_score,
            "threshold": 1,
            "ready_for_next_step": model_score > 0,
            "score_breakdown": model_breakdown,
        },
        "admission": {
            "recommendation": "ACCEPTED",
            "confidence": 1.0,
        },
    }


def _sparse_scoring_case_detail(model_score: int) -> dict[str, object]:
    return {
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "one_sentence_summary": "发起方报告履约状态需要核验。",
        },
        "missing_information": {
            "blocking_gaps": [],
            "nice_to_have_gaps": [],
            "next_questions": [],
        },
        "intake_quality": {
            "score": model_score,
            "threshold": 1,
            "ready_for_next_step": model_score > 0,
            "score_breakdown": {},
        },
        "admission": {
            "recommendation": "NEED_MORE_INFO",
            "confidence": 0.0,
        },
    }


def _render_provider_case_detail(
    *,
    request: IntakeTurnRequest,
    case_detail: dict[str, object],
):
    return CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已完成结构化案件梳理。",
        llm_case_detail=case_detail,
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.0,
    )


def test_sparse_and_rich_provider_presentations_share_quality_authority() -> None:
    request = _request()
    sparse = _render_provider_case_detail(
        request=request,
        case_detail=_sparse_scoring_case_detail(0),
    )
    rich = _render_provider_case_detail(
        request=request,
        case_detail=_complete_scoring_case_detail(100),
    )

    sparse_quality = sparse.scroll_snapshot["intake_quality"]
    rich_quality = rich.scroll_snapshot["intake_quality"]
    assert {
        "score_breakdown": sparse_quality["score_breakdown"],
        "score": sparse_quality["score"],
        "ready": sparse_quality["ready_for_next_step"],
        "recommendation": sparse.admission_recommendation,
    } == {
        "score_breakdown": rich_quality["score_breakdown"],
        "score": rich_quality["score"],
        "ready": rich_quality["ready_for_next_step"],
        "recommendation": rich.admission_recommendation,
    }


def _render_scoring_case_detail(
    *,
    model_score: int,
    request: IntakeTurnRequest | None = None,
    blocking_gaps: list[str] | None = None,
    admission_recommendation: str = "ACCEPTED",
):
    case_detail = _complete_scoring_case_detail(model_score)
    case_detail["missing_information"]["blocking_gaps"] = list(blocking_gaps or [])
    return CaseDetailDossierSkill().render(
        request=request or _request(),
        room_utterance="已完成结构化案件梳理。",
        llm_case_detail=case_detail,
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation=admission_recommendation,
        llm_missing_fields=[],
        llm_confidence=1.0,
    )


@pytest.mark.parametrize("model_score", [75, 0, 100])
def test_case_detail_quality_is_derived_independently_of_model_score(
    model_score: int,
) -> None:
    rendered = _render_scoring_case_detail(model_score=model_score)
    quality = rendered.scroll_snapshot["intake_quality"]

    assert quality["score_breakdown"] == {
        "references": 15,
        "event_story": 20,
        "party_positions": 20,
        "requested_resolution": 15,
        "risk_and_conflicts": 15,
        "next_action_clarity": 15,
    }
    assert quality["score"] == sum(quality["score_breakdown"].values()) == 100
    assert quality["threshold"] == 85
    assert quality["ready_for_next_step"] is True
    assert rendered.admission_recommendation == "ACCEPTED"


@pytest.mark.parametrize("missing_authority", ["trusted_reference", "blocking_gap"])
def test_case_detail_quality_missing_authority_remains_incomplete(
    missing_authority: str,
) -> None:
    request = _request()
    blocking_gaps: list[str] = []
    if missing_authority == "trusted_reference":
        request = _request(
            previous_case_detail={
                "schema_version": "intake_case_detail.v1",
                "references": {
                    "order_reference": "ORDER_GENERIC_1",
                    "after_sales_reference": "AS_GENERIC_1",
                    "logistics_reference": "",
                },
                "claim_resolution": {
                    "initiator_role": "USER",
                    "requested_resolution": "REFUND",
                },
            }
        )
    else:
        blocking_gaps = ["COUNTERPARTY_RESPONSE"]

    rendered = _render_scoring_case_detail(
        model_score=100,
        request=request,
        blocking_gaps=blocking_gaps,
    )
    quality = rendered.scroll_snapshot["intake_quality"]

    assert quality["score"] == sum(quality["score_breakdown"].values())
    assert quality["ready_for_next_step"] is False
    assert rendered.admission_recommendation == "NEED_MORE_INFO"
    assert rendered.missing_fields


def test_not_admissible_recommendation_requires_deterministic_incompleteness() -> None:
    ready = _render_scoring_case_detail(
        model_score=0,
        admission_recommendation="NOT_ADMISSIBLE",
    )
    incomplete = _render_scoring_case_detail(
        model_score=100,
        blocking_gaps=["COUNTERPARTY_RESPONSE"],
        admission_recommendation="NOT_ADMISSIBLE",
    )

    assert ready.admission_recommendation == "ACCEPTED"
    assert incomplete.admission_recommendation == "NOT_ADMISSIBLE"
    assert incomplete.scroll_snapshot["intake_quality"]["ready_for_next_step"] is False


def _render_legacy_scoring_snapshot(
    *,
    model_score: int,
    request: IntakeTurnRequest,
):
    return CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已完成结构化案件梳理。",
        llm_case_detail=None,
        llm_dossier_patch=None,
        llm_scroll_snapshot=_complete_scoring_case_detail(model_score),
        llm_canvas_operations=[],
        llm_admission_recommendation="ACCEPTED",
        llm_missing_fields=[],
        llm_confidence=1.0,
    )


def test_legacy_scroll_snapshot_uses_canonical_quality_authority() -> None:
    missing_reference_request = _request(
        previous_case_detail={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_GENERIC_1",
                "after_sales_reference": "AS_GENERIC_1",
                "logistics_reference": "",
            },
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
            },
        }
    )

    forged_high = _render_legacy_scoring_snapshot(
        model_score=100,
        request=missing_reference_request,
    )
    complete_low = _render_legacy_scoring_snapshot(
        model_score=0,
        request=_request(),
    )

    assert forged_high.scroll_snapshot["intake_quality"]["ready_for_next_step"] is False
    assert forged_high.admission_recommendation == "NEED_MORE_INFO"
    assert forged_high.missing_fields
    complete_quality = complete_low.scroll_snapshot["intake_quality"]
    assert complete_quality["score"] == 100
    assert complete_quality["score"] == sum(
        complete_quality["score_breakdown"].values()
    )
    assert complete_quality["ready_for_next_step"] is True
    assert complete_low.admission_recommendation == "ACCEPTED"


def test_placeholder_only_provider_collections_do_not_change_authoritative_score() -> None:
    case_detail = _complete_scoring_case_detail(100)
    case_detail["case_story"]["event_timeline"] = [
        {},
        {"event": "UNKNOWN", "source": "USER_MESSAGE"},
    ]
    case_detail["risk_assessment"] = {
        "case_grade": "MEDIUM",
        "risk_signals": [{}, {"signal": "UNKNOWN", "source": "MODEL"}],
        "reasoning": "UNKNOWN",
    }
    case_detail["dispute_focus"]["facts_to_verify"] = [
        {},
        {"fact": "UNKNOWN", "source": "MODEL"},
    ]
    case_detail["dispute_core_state"] = {
        "facts_in_dispute": [{}, {"fact": "UNKNOWN"}],
        "next_verification_focus": ["UNKNOWN"],
    }

    rendered = CaseDetailDossierSkill().render(
        request=_request(),
        room_utterance="已完成结构化案件梳理。",
        llm_case_detail=case_detail,
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="ACCEPTED",
        llm_missing_fields=[],
        llm_confidence=1.0,
    )
    quality = rendered.scroll_snapshot["intake_quality"]

    assert quality["score_breakdown"] == {
        "references": 15,
        "event_story": 20,
        "party_positions": 20,
        "requested_resolution": 15,
        "risk_and_conflicts": 15,
        "next_action_clarity": 15,
    }
    assert quality["score"] == sum(quality["score_breakdown"].values()) == 100
    assert quality["ready_for_next_step"] is True
    assert rendered.admission_recommendation == "ACCEPTED"


@pytest.mark.parametrize("provider_resolution", ["ARBITRARY", {}, "UNKNOWN"])
def test_unresolved_or_non_string_resolution_is_missing_and_scores_zero(
    provider_resolution: object,
) -> None:
    request = _request(
        current_user_message={
            "message_id": "MESSAGE_UNRESOLVED_RESOLUTION",
            "sequence_no": 2,
            "role": "USER",
            "source": "ROOM_MESSAGE",
            "text": "我补充了新的履约状态。",
        },
        previous_case_detail={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_GENERIC_1",
                "after_sales_reference": "AS_GENERIC_1",
                "logistics_reference": "LOG_GENERIC_1",
            },
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "UNKNOWN",
            },
        },
    )
    case_detail = _complete_scoring_case_detail(100)
    case_detail["claim_resolution"] = {
        "initiator_role": "USER",
        "requested_resolution": copy.deepcopy(provider_resolution),
    }
    case_detail["requested_resolution"] = {
        "requested_outcome": copy.deepcopy(provider_resolution),
    }

    rendered = _render_provider_case_detail(
        request=request,
        case_detail=case_detail,
    )
    quality = rendered.scroll_snapshot["intake_quality"]
    questions = rendered.scroll_snapshot["missing_information"]["next_questions"]

    assert quality["score_breakdown"]["requested_resolution"] == 0
    assert quality["score"] == sum(quality["score_breakdown"].values()) == 85
    assert quality["ready_for_next_step"] is False
    assert rendered.admission_recommendation == "NEED_MORE_INFO"
    assert rendered.missing_fields
    assert questions and all(isinstance(question, str) and question for question in questions)


@pytest.mark.parametrize(
    ("trusted_role", "provider_role"),
    [("USER", "MERCHANT"), ("MERCHANT", "USER")],
)
def test_provider_cannot_replace_trusted_initiator_role(
    trusted_role: str,
    provider_role: str,
) -> None:
    case_id = f"CASE_{trusted_role.lower()}_initiator_role_authority"
    actor_id = f"{trusted_role}_local_role_authority"
    context = _agent_context(case_id)
    context.update(
        {
            "actor_id": actor_id,
            "actor_role": trusted_role,
            "permission_level": f"PARTY_{trusted_role}",
            "scope_type": "INTAKE_INITIATOR_PRIVATE",
            "allowed_actor_ids": [actor_id],
            "allowed_actor_roles": [trusted_role],
            "prompt_profile_id": f"DISPUTE_INTAKE_OFFICER:{trusted_role}:v1",
        }
    )
    request = _request(
        case_id=case_id,
        current_user_message={
            "message_id": f"MESSAGE_{trusted_role}_CLAIM_AUTHORITY",
            "sequence_no": 2,
            "role": trusted_role,
            "source": "ROOM_MESSAGE",
            "text": "我方明确要求退款。",
        },
        previous_case_detail={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_ROLE_AUTHORITY",
                "after_sales_reference": "AS_ROLE_AUTHORITY",
                "logistics_reference": "LOG_ROLE_AUTHORITY",
            },
            "claim_resolution": {
                "initiator_role": trusted_role,
                "requested_resolution": "REFUND",
            },
        },
        agent_context=context,
    )
    case_detail = _sparse_scoring_case_detail(0)
    case_detail["claim_resolution"] = {
        "initiator_role": provider_role,
        "requested_resolution": "REFUND",
    }

    rendered = _render_provider_case_detail(
        request=request,
        case_detail=case_detail,
    )

    assert rendered.scroll_snapshot["claim_resolution"]["initiator_role"] == trusted_role
    assert rendered.scroll_snapshot["respondent_attitude"]["respondent_role"] == (
        "MERCHANT" if trusted_role == "USER" else "USER"
    )


def test_merchant_initiator_score_is_structural_and_replay_stable() -> None:
    case_id = "CASE_merchant_scoring_authority"
    context = _agent_context(case_id)
    context.update(
        {
            "actor_id": "MERCHANT_local_1",
            "actor_role": "MERCHANT",
            "permission_level": "PARTY_MERCHANT",
            "scope_type": "INTAKE_INITIATOR_PRIVATE",
            "allowed_actor_ids": ["MERCHANT_local_1"],
            "allowed_actor_roles": ["MERCHANT"],
            "prompt_profile_id": "DISPUTE_INTAKE_OFFICER:MERCHANT:v1",
        }
    )
    request = _request(
        case_id=case_id,
        current_user_message={
            "message_id": "MESSAGE_MERCHANT_INITIATOR",
            "sequence_no": 2,
            "role": "MERCHANT",
            "source": "ROOM_MESSAGE",
            "text": "我方请求核验履约状态并退还相应款项。",
        },
        previous_case_detail={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_GENERIC_MERCHANT",
                "after_sales_reference": "AS_GENERIC_MERCHANT",
                "logistics_reference": "LOG_GENERIC_MERCHANT",
            },
            "claim_resolution": {
                "initiator_role": "MERCHANT",
                "requested_resolution": "REFUND",
            },
        },
        agent_context=context,
    )
    case_detail = _sparse_scoring_case_detail(0)

    def render_quality() -> dict[str, object]:
        rendered = CaseDetailDossierSkill().render(
            request=request,
            room_utterance="已完成结构化案件梳理。",
            llm_case_detail=case_detail,
            llm_dossier_patch=None,
            llm_scroll_snapshot=None,
            llm_canvas_operations=[],
            llm_admission_recommendation="NEED_MORE_INFO",
            llm_missing_fields=[],
            llm_confidence=0.0,
        )
        return rendered.scroll_snapshot["intake_quality"]

    first = render_quality()
    replay = render_quality()
    user = _render_provider_case_detail(
        request=_request(),
        case_detail=_sparse_scoring_case_detail(100),
    ).scroll_snapshot["intake_quality"]

    assert first["score"] == 100
    assert first["ready_for_next_step"] is True
    assert replay == first
    assert {
        "score_breakdown": first["score_breakdown"],
        "score": first["score"],
        "ready": first["ready_for_next_step"],
    } == {
        "score_breakdown": user["score_breakdown"],
        "score": user["score"],
        "ready": user["ready_for_next_step"],
    }


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_intake_turn_workflow_lives_under_agent_package_and_outputs_case_detail` 验证接待信息在固定案例中的输出、边界和失败行为；关键协作调用：`CaseDetailRunner`、`run`、`IntakeTurnWorkflow`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_intake_turn_workflow_lives_under_agent_package_and_outputs_case_detail() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    runner = CaseDetailRunner(score=86)
    result = IntakeTurnWorkflow(model_runner=runner).run(_request())

    assert runner.calls[0]["node_name"] == "intake_turn_case_detail"
    assert result.scroll_snapshot["schema_version"] == "intake_case_detail.v1"
    assert result.scroll_snapshot["intake_quality"]["score"] == 86
    assert result.scroll_snapshot["intake_quality"]["ready_for_next_step"] is True
    assert result.admission_recommendation == "ACCEPTED"
    assert result.scroll_snapshot["claim_resolution"]["requested_resolution"] == "REFUND"
    assert result.scroll_snapshot["claim_resolution"]["initiator_role"] == "USER"
    assert result.scroll_snapshot["respondent_attitude"]["attitude"] == "NOT_RESPONDED"
    assert result.scroll_snapshot["dispute_core_state"]["conflict_type"] == "CLAIM_UNANSWERED"
    assert result.dossier_patch["case_detail"]["case_story"]["title"] == "物流显示签收但用户称未收到商品"
    assert "我已经了解本案的基本情况" in result.room_utterance
    assert (
        result.scroll_snapshot["handoff_notes"]["remark_status"]
        == "WAITING_FOR_REMARK"
    )


def test_intake_turn_uses_six_component_sum_when_model_total_is_wrong() -> None:
    result = CaseDetailDossierSkill().render(
        request=_request(),
        conversation_action="ASK_SUBSTANTIVE",
        room_utterance="已记录本轮补充，请继续说明商家的具体回应。",
        llm_case_detail={
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                "requested_amount": None,
                "requested_items": None,
                "request_reason": "用户称物流显示签收但本人没有收到商品。",
                "normalized_statement": "用户请求对未收到的商品退款。",
            },
            "respondent_attitude": {"respondent_role": "MERCHANT"},
            "missing_information": {
                "blocking_gaps": ["商家的具体回应"],
                "nice_to_have_gaps": [],
                "next_questions": ["请补充商家的具体回应？"],
            },
            "intake_quality": {
                "score": 9,
                "threshold": 85,
                "ready_for_next_step": False,
                "score_breakdown": {
                    "references": 15,
                    "event_story": 20,
                    "party_positions": 20,
                    "requested_resolution": 15,
                    "risk_and_conflicts": 10,
                    "next_action_clarity": 10,
                },
                "improvement_reason": "仍需补充商家的具体回应。",
            },
            "handoff_notes": {
                "remark_status": "NOT_READY",
                "latest_remark": "",
                "instruction": "信息尚不完整，继续实质问询。",
            },
            "admission": {
                "recommendation": "NEED_MORE_INFO",
                "reasoning": "仍有案情信息需要补充。",
                "confidence": 0.9,
            },
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.9,
        model_semantics_authoritative=True,
    )

    quality = result.scroll_snapshot["intake_quality"]
    assert quality["score"] == sum(quality["score_breakdown"].values()) == 90
    assert (
        result.scroll_snapshot["party_intake_state"]["USER"]["intake_quality"]
        == quality
    )
    score_operation = next(
        operation
        for operation in result.canvas_operations
        if operation["type"] == "SET_QUALITY_SCORE"
    )
    assert score_operation["value"] == 90


def test_ordered_intake_preserves_typed_outcome_without_a_second_semantic_gate() -> None:
    result = CaseDetailDossierSkill().render(
        request=_request(),
        conversation_action="INVITE_OPTIONAL_REMARK",
        room_utterance="已记录本轮说明；如有补充可继续备注。",
        llm_case_detail={
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                "requested_amount": None,
                "requested_items": None,
                "request_reason": "用户称物流显示签收但本人没有收到商品。",
                "normalized_statement": "用户请求对未收到的商品退款。",
            },
            "respondent_attitude": {"respondent_role": "MERCHANT"},
            "missing_information": {
                "blocking_gaps": [],
                "nice_to_have_gaps": [],
                "next_questions": [],
            },
            "intake_quality": {
                "score": 85,
                "threshold": 85,
                "ready_for_next_step": True,
                "score_breakdown": {
                    "references": 15,
                    "event_story": 18,
                    "party_positions": 15,
                    "requested_resolution": 5,
                    "risk_and_conflicts": 12,
                    "next_action_clarity": 15,
                },
                "improvement_reason": "商家仍可补充退款处理立场。",
            },
            "handoff_notes": {
                "remark_status": "WAITING_FOR_REMARK",
                "latest_remark": "",
                "instruction": "如有补充可继续备注。",
            },
            "admission": {
                "recommendation": "ACCEPTED",
                "reasoning": "模型已选择收束本轮。",
                "confidence": 0.85,
            },
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="ACCEPTED",
        llm_missing_fields=[],
        llm_confidence=0.85,
        model_semantics_authoritative=True,
    )

    quality = result.scroll_snapshot["party_intake_state"]["USER"][
        "intake_quality"
    ]
    assert quality["score"] == 80
    assert quality["ready_for_next_step"] is True
    assert result.scroll_snapshot["handoff_notes"]["remark_status"] == (
        "WAITING_FOR_REMARK"
    )
    assert result.admission_recommendation == "ACCEPTED"


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_intake_case_detail_readiness_is_gated_by_score_and_required_references` 读取并按案件、角色或会话范围筛选接待信息；关键协作调用：`CaseDetailRunner`、`run`、`IntakeTurnWorkflow`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
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

    quality = result.scroll_snapshot["intake_quality"]
    assert quality["score"] == sum(quality["score_breakdown"].values()) == 85
    assert result.scroll_snapshot["intake_quality"]["ready_for_next_step"] is False
    assert result.admission_recommendation == "NEED_MORE_INFO"
    assert "ORDER_REFERENCE" in result.missing_fields
    assert "LOGISTICS_REFERENCE" in result.missing_fields

    quality = result.scroll_snapshot["intake_quality"]
    assert "订单号" in quality["improvement_reason"]
    assert "物流单号" in quality["improvement_reason"]
    assert "ORDER_REFERENCE" not in quality["improvement_reason"]
    assert "LOGISTICS_REFERENCE" not in quality["improvement_reason"]
    assert "订单号" in result.scroll_snapshot["missing_information"]["next_questions"][0]


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_intake_case_detail_preserves_model_human_missing_fields_without_rewriting` 验证 V3 模型生成的人类化案情缺口原样进入正式卷宗；关键协作调用：`run`、`IntakeTurnWorkflow`、`RunnerWithHumanFields`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`、`invoke_structured`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_intake_case_detail_preserves_model_human_missing_fields_without_rewriting() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    class RunnerWithHumanFields(CaseDetailRunner):
        # 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：类/闭包内部方法。
        # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`invoke_structured`。
        # 上下游：上游为 本文件的 `test_intake_case_detail_preserves_model_human_missing_fields_without_rewriting`；下游为 协作调用 `invoke_structured`。
        # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
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
            max_input_tokens=None,
        ):
            generation = super().invoke_structured(
                node_name=node_name,
                case_data=case_data,
                output_type=output_type,
                context_sections=context_sections,
                context_pack=context_pack,
                agent_context=agent_context,
                prompt_profile_id=prompt_profile_id,
                max_input_tokens=max_input_tokens,
            )
            payload = generation.value.model_dump(mode="json")
            payload["ordered_sections"][7]["value"].update(
                {
                    "blocking_gaps": [
                        "订单发生时间",
                        "商家对退款诉求的直接回应",
                    ],
                    "next_questions": ["请补充订单发生时间。"],
                }
            )
            payload["ordered_sections"][8]["value"].update(
                {
                    "remark_status": "NOT_READY",
                    "instruction": "补充阻塞信息后继续整理。",
                }
            )
            payload["ordered_sections"][9]["value"] = {
                "score_breakdown": {
                    "references": 15,
                    "event_story": 20,
                    "party_positions": 20,
                    "requested_resolution": 15,
                    "risk_and_conflicts": 0,
                    "next_action_clarity": 0,
                },
                "total_score": 70,
                "threshold": 85,
                "ready_for_next_step": False,
                "improvement_reason": (
                    "仍缺少订单发生时间和商家对退款诉求的直接回应。"
                ),
                "admission_recommendation": "NEED_MORE_INFO",
                "admission_reasoning": "仍有阻塞案情信息需要补充。",
                "confidence": 0.86,
                "conversation_action": "ASK_SUBSTANTIVE",
                "knowledge_answer_mode": "NONE",
            }
            generation.value = type(generation.value).model_validate(payload)
            return generation

    result = IntakeTurnWorkflow(model_runner=RunnerWithHumanFields()).run(_request())

    quality_reason = result.scroll_snapshot["intake_quality"]["improvement_reason"]
    blocking_gaps = result.scroll_snapshot["missing_information"]["blocking_gaps"]
    assert quality_reason == "仍缺少订单发生时间和商家对退款诉求的直接回应。"
    assert blocking_gaps == ["订单发生时间", "商家对退款诉求的直接回应"]


def test_not_ready_asks_all_bounded_substantive_questions_without_rewriting_reply() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    model_reply = (
        "已完整记录您本轮补充的物流情况。"
        "请问该订单的订单号是什么？"
        "您最早在什么时间发现本人没有收到包裹？"
    )
    runner = CaseDetailRunner(
        score=70,
        room_utterance=model_reply,
        conversation_action="ASK_SUBSTANTIVE",
    )
    request = _request(
        previous_case_detail={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "",
                "after_sales_reference": "AS_1001",
                "logistics_reference": "SF1001001001",
            },
            "claim_resolution": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
            },
        }
    )

    result = IntakeTurnWorkflow(model_runner=runner).run(request)

    assert result.room_utterance == model_reply
    assert result.room_utterance.count("？") == 2
    assert result.scroll_snapshot["intake_quality"]["ready_for_next_step"] is False
    assert result.scroll_snapshot["handoff_notes"]["remark_status"] == "NOT_READY"
    assert result.scroll_snapshot["missing_information"]["next_questions"] == [
        "请问该订单的订单号是什么？",
        "您最早在什么时间发现本人没有收到包裹？",
    ]
    assert "handoff_remark_partition" not in result.scroll_snapshot


def test_first_ready_turn_invites_optional_remark_and_freezes_substantive_state() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    invite = (
        "当前信息已达到提交条件。您可以直接提交确认；"
        "如有备注可选择补充，没有备注也可以直接确认提交。"
    )
    result = IntakeTurnWorkflow(
        model_runner=CaseDetailRunner(
            room_utterance=invite,
            conversation_action="INVITE_OPTIONAL_REMARK",
        )
    ).run(_request())

    snapshot = result.scroll_snapshot
    partition = snapshot["handoff_remark_partition"]
    notes = result.scroll_snapshot["handoff_notes"]
    assert result.room_utterance == invite
    assert snapshot["intake_quality"]["ready_for_next_step"] is True
    assert result.admission_recommendation == "ACCEPTED"
    assert snapshot["missing_information"]["next_questions"] == []
    assert notes["remark_status"] == "WAITING_FOR_REMARK"
    assert partition["schema_version"] == "handoff_remark_partition.v1"
    assert partition["case_fact_matrix_id"] == snapshot["case_fact_matrix"]["matrix_id"]
    assert partition["case_fact_matrix_version"] == snapshot["case_fact_matrix"][
        "matrix_version"
    ]
    assert partition["case_fact_matrix_hash"] == snapshot["case_fact_matrix"][
        "content_hash"
    ]
    assert partition["parties"]["USER"]["remark_status"] == "WAITING_FOR_REMARK"
    assert partition["parties"]["USER"]["source"]["message_id"] == "MESSAGE_1001"
    assert partition["parties"]["MERCHANT"] == {
        "party_role": "MERCHANT",
        "remark_status": "NOT_READY",
        "latest_remark": "",
        "remarks": [],
    }


def test_remark_partition_is_append_only_replay_safe_and_party_isolated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    class RemarkRunner(CaseDetailRunner):
        def invoke_structured(self, *, output_type, **kwargs):
            if output_type.__name__ == "IntakeRemarkAcknowledgementLlmOutput":
                return SimpleNamespace(
                    value=output_type(
                        conversation_action=self.conversation_action,
                        room_utterance=self.room_utterance,
                        confidence=0.9,
                    )
                )
            return super().invoke_structured(output_type=output_type, **kwargs)

    invite = IntakeTurnWorkflow(
        model_runner=CaseDetailRunner(
            room_utterance=(
                "当前信息已达到提交条件。您可以直接提交确认；"
                "如有备注可选择补充，没有备注也可以直接确认提交。"
            ),
            conversation_action="INVITE_OPTIONAL_REMARK",
        )
    ).run(_request())
    frozen = copy.deepcopy(invite.scroll_snapshot)
    frozen_core = {
        key: copy.deepcopy(frozen[key])
        for key in (
            "case_fact_matrix",
            "claim_resolution",
            "requested_resolution",
            "intake_quality",
            "missing_information",
        )
    }
    merchant_partition = copy.deepcopy(
        frozen["handoff_remark_partition"]["parties"]["MERCHANT"]
    )

    def raw_text_intent_must_not_run(_: str) -> bool:
        raise AssertionError(
            "the frozen acknowledgement reducer must use the model action"
        )

    monkeypatch.setattr(
        "app.agents.dispute_intake_officer.skills.dossier.dossier_skill._is_no_extra_remark",
        raw_text_intent_must_not_run,
    )

    no_remark_reply = "已确认没有额外备注，当前材料可以直接提交确认。"
    no_remark_runner = RemarkRunner(
        room_utterance=no_remark_reply,
        conversation_action="ACK_NO_REMARK",
    )
    no_remark_request = _request(
        current_user_message={
            "message_id": "MESSAGE_NO_REMARK_1",
            "role": "USER",
            "source": "ROOM_MESSAGE",
            "text": "无其他备注，以上陈述完整准确。",
        },
        previous_case_detail=frozen,
    )
    no_remark = IntakeTurnWorkflow(model_runner=no_remark_runner).run(
        no_remark_request
    )
    no_remark_party = no_remark.scroll_snapshot["handoff_remark_partition"][
        "parties"
    ]["USER"]
    assert no_remark.room_utterance == no_remark_reply
    assert no_remark.scroll_snapshot["missing_information"]["next_questions"] == []
    assert no_remark_party["remark_status"] == "NO_EXTRA_REMARKS"
    assert no_remark_party["latest_remark"] == ""
    assert no_remark_party["remarks"] == []
    assert no_remark_party["source"]["message_id"] == "MESSAGE_NO_REMARK_1"
    assert "无备注" not in repr(no_remark_party["remarks"])
    assert {
        key: no_remark.scroll_snapshot[key] for key in frozen_core
    } == frozen_core
    assert no_remark.scroll_snapshot["handoff_remark_partition"]["parties"][
        "MERCHANT"
    ] == merchant_partition
    no_remark_replay = IntakeTurnWorkflow(model_runner=no_remark_runner).run(
        no_remark_request
    )
    assert no_remark_replay == no_remark

    no_remark_variant = IntakeTurnWorkflow(
        model_runner=RemarkRunner(
            room_utterance=no_remark_reply,
            conversation_action="ACK_NO_REMARK",
        )
    ).run(
        _request(
            current_user_message={
                "message_id": "MESSAGE_NO_REMARK_VARIANT",
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "就这些。",
            },
            previous_case_detail=frozen,
        )
    )
    variant_party = no_remark_variant.scroll_snapshot[
        "handoff_remark_partition"
    ]["parties"]["USER"]
    assert variant_party["remark_status"] == "NO_EXTRA_REMARKS"
    assert variant_party["source"]["message_id"] == "MESSAGE_NO_REMARK_VARIANT"
    assert variant_party["remarks"] == []
    assert {key: no_remark_variant.scroll_snapshot[key] for key in frozen_core} == (
        frozen_core
    )
    assert no_remark_variant.scroll_snapshot["handoff_remark_partition"][
        "parties"
    ]["MERCHANT"] == merchant_partition

    substantive_no_phrases = (
        "设备外观无损，但请核查物流签收记录。",
        "商家无退款记录，请作为备注核查。",
    )
    for index, substantive_text in enumerate(substantive_no_phrases, start=1):
        message_id = f"MESSAGE_SUBSTANTIVE_NO_{index}"
        substantive_request = _request(
            current_user_message={
                "message_id": message_id,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": substantive_text,
            },
            previous_case_detail=frozen,
        )
        recorded = IntakeTurnWorkflow(
            model_runner=RemarkRunner(
                room_utterance="已记录该备注。",
                conversation_action="ACK_REMARK",
            )
        ).run(substantive_request)
        recorded_party = recorded.scroll_snapshot["handoff_remark_partition"][
            "parties"
        ]["USER"]
        assert recorded_party["remark_status"] == "HAS_REMARKS"
        assert recorded_party["latest_remark"] == substantive_text
        assert [item["text"] for item in recorded_party["remarks"]] == [
            substantive_text
        ]
        assert recorded_party["remarks"][0]["source_message_id"] == message_id
        assert {key: recorded.scroll_snapshot[key] for key in frozen_core} == (
            frozen_core
        )
        assert recorded.scroll_snapshot["handoff_remark_partition"]["parties"][
            "MERCHANT"
        ] == merchant_partition

    with pytest.raises(ValidationError):
        IntakeRemarkAcknowledgementLlmOutput.model_validate(
            {
                "conversation_action": "ASK_SUBSTANTIVE",
                "room_utterance": "invalid acknowledgement action",
                "confidence": 0.9,
            }
        )
    with pytest.raises(ValidationError):
        IntakeRemarkAcknowledgementLlmOutput.model_validate(
            {
                "room_utterance": "missing acknowledgement action",
                "confidence": 0.9,
            }
        )

    first_text = "请后续环节重点核查快递柜取件记录。"
    first_remark = IntakeTurnWorkflow(
        model_runner=RemarkRunner(
            room_utterance="已记录该备注，当前材料可以直接提交确认。",
            conversation_action="ACK_REMARK",
        )
    ).run(
        _request(
            current_user_message={
                "message_id": "MESSAGE_REMARK_1",
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": first_text,
            },
            previous_case_detail=no_remark.scroll_snapshot,
        )
    )
    first_party = first_remark.scroll_snapshot["handoff_remark_partition"]["parties"][
        "USER"
    ]
    assert first_party["remark_status"] == "HAS_REMARKS"
    assert first_party["latest_remark"] == first_text
    assert [item["text"] for item in first_party["remarks"]] == [first_text]
    assert first_party["remarks"][0]["source_message_id"] == "MESSAGE_REMARK_1"

    second_text = "另请保留签收底单原始时间戳。"
    second_request = _request(
        current_user_message={
            "message_id": "MESSAGE_REMARK_2",
            "role": "USER",
            "source": "ROOM_MESSAGE",
            "text": second_text,
        },
        previous_case_detail=first_remark.scroll_snapshot,
    )
    second_runner = RemarkRunner(
        room_utterance="已继续记录该备注，当前材料仍可直接提交确认。",
        conversation_action="ACK_REMARK",
    )
    second_remark = IntakeTurnWorkflow(model_runner=second_runner).run(second_request)
    second_party = second_remark.scroll_snapshot["handoff_remark_partition"][
        "parties"
    ]["USER"]
    assert [item["text"] for item in second_party["remarks"]] == [
        first_text,
        second_text,
    ]
    assert [item["source_message_id"] for item in second_party["remarks"]] == [
        "MESSAGE_REMARK_1",
        "MESSAGE_REMARK_2",
    ]
    assert second_party["latest_remark"] == second_text
    assert {key: second_remark.scroll_snapshot[key] for key in frozen_core} == frozen_core
    assert second_remark.scroll_snapshot["handoff_remark_partition"]["parties"][
        "MERCHANT"
    ] == merchant_partition

    replay = IntakeTurnWorkflow(model_runner=second_runner).run(
        _request(
            current_user_message={
                "message_id": "MESSAGE_REMARK_2",
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": second_text,
            },
            previous_case_detail=second_remark.scroll_snapshot,
        )
    )
    assert replay.scroll_snapshot["handoff_remark_partition"] == second_remark.scroll_snapshot[
        "handoff_remark_partition"
    ]


def test_pending_ready_stream_matches_model_final_utterance() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    raw_invite = (
        "当前信息已达到提交条件。您可以直接提交确认；"
        "如有备注可选择补充，没有备注也可以直接确认提交。"
    )

    class RegistryStreamingRunner(CaseDetailRunner):
        def invoke_structured(self, **kwargs):
            generation = super().invoke_structured(**kwargs)
            observer = current_stream_observer()
            if observer is not None:
                projector = IncrementalVisibleJsonProjector(
                    observer.visible_fields_for(kwargs["node_name"])
                )
                for field, delta in projector.feed(
                    generation.value.model_dump_json()
                ):
                    observer.visible_delta(kwargs["node_name"], field, delta)
            return generation

    request = _request()
    published = []
    observer = AgentStreamObserver(
        operation="intake_turn",
        run_id="AGENT_RUN_INTAKE_FINAL_VISIBLE",
        publish=published.append,
    )

    with bind_stream_observer(observer):
        result = IntakeTurnWorkflow(
            model_runner=RegistryStreamingRunner(
                score=88,
                room_utterance=raw_invite,
                conversation_action="INVITE_OPTIONAL_REMARK",
            )
        ).run(request)

    streamed_utterance = "".join(
        event.delta
        for event in published
        if event.type == "visible_delta" and event.field == "room_utterance"
    )

    assert streamed_utterance == raw_invite
    assert streamed_utterance == result.room_utterance
