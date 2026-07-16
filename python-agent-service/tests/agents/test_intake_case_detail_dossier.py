# 文件作用：自动化测试文件，验证 test_intake_case_detail_dossier 相关模块的行为、契约或页面布局。

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
    ORIGINAL_STATEMENT_SEPARATOR,
    SUBJECTIVE_RESPONDENT_SOURCE,
    _canonical_verification_focus,
    _enforce_case_story_summary,
    _enforce_claim_resolution,
    _reported_attitude_position,
)
from app.agents.dispute_intake_officer.workflow import (
    _enforce_intake_question_boundary,
)
from app.schemas import IntakeTurnRequest
from app.streaming import (
    AgentStreamObserver,
    IncrementalVisibleJsonProjector,
    bind_stream_observer,
    current_stream_observer,
)


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
# 上下游：上游为 本文件的 `test_intake_turn_workflow_lives_under_agent_package_and_outputs_case_detail`、`test_intake_case_detail_readiness_is_gated_by_score_and_required_references`、`test_intake_case_detail_translates_llm_missing_field_codes_before_persisting`、`test_ready_intake_turn_asks_for_handoff_remark_before_next_room`；下游为 本文件的 `_agent_context`。
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
    schema = IntakeCaseDetailLlmOutput.model_json_schema()
    detail_schema = schema["$defs"]["IntakeCaseDetailPatch"]
    story_schema = schema["$defs"]["IntakeCaseStoryPatch"]

    assert "case_detail" in schema["required"]
    assert detail_schema["required"] == ["case_story"]
    assert detail_schema["properties"]["case_story"] == {
        "$ref": "#/$defs/IntakeCaseStoryPatch"
    }
    assert story_schema["required"] == ["one_sentence_summary"]
    assert story_schema["properties"]["one_sentence_summary"]["minLength"] == 1


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


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_intake_question_boundary_replaces_evidence_requests_with_case_questions` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`_enforce_intake_question_boundary`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `_enforce_intake_question_boundary`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_intake_question_boundary_replaces_evidence_requests_with_case_questions() -> None:
    utterance = "请上传开箱视频、聊天记录截图和物流签收凭证。"
    case_detail = {
        "intake_quality": {"score": 72, "ready_for_next_step": False},
        "missing_information": {
            "next_questions": [
                "包裹实际到达时间是什么时候？",
                "包裹由谁签收？",
                "您在什么时间联系了商家？",
                "能否提供物流签收截图？",
            ]
        },
    }

    result = _enforce_intake_question_boundary(utterance, case_detail)

    assert "包裹实际到达时间是什么时候" in result
    assert "上传" not in result
    assert "截图" not in result
    assert "凭证" not in result
    assert result.count("？") == 2
    assert "什么时间联系了商家" not in result


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


def test_intake_question_boundary_removes_question_answered_by_claim_seed() -> None:
    utterance = (
        "为了继续梳理案情，请补充：您目前的具体诉求是换货、退货退款，"
        "还是其他处理方式？ 您是否已在平台发起售后单，目前进度如何？"
    )
    case_detail = {
        "intake_quality": {"score": 70, "ready_for_next_step": False},
        "claim_resolution": {"requested_resolution": "REPLACE_OR_REPAIR"},
        "missing_information": {
            "next_questions": ["您是否已在平台发起售后单，目前进度如何？"]
        },
    }

    result = _enforce_intake_question_boundary(utterance, case_detail)

    assert "具体诉求" not in result
    assert "售后单" in result


def test_respondent_resolution_question_is_not_replaced_by_initiator_claim() -> None:
    utterance = (
        "已记录您关于商品未拆封、未激活的说明。为了明确争议焦点，请补充："
        "1. 您提到的“白色大容量型号”具体是指哪一款配置（例如内存大小）？"
        "2. 针对商家提出的换货方案，您目前的最终诉求是什么？"
    )
    case_detail = {
        "intake_quality": {"score": 75, "ready_for_next_step": False},
        "claim_resolution": {
            "initiator_role": "MERCHANT",
            "requested_resolution": "REPLACE_OR_REPAIR",
        },
        "missing_information": {
            "next_questions": ["用户是否已拆封或激活手机"]
        },
    }

    result = _enforce_intake_question_boundary(
        utterance,
        case_detail,
        actor_role="USER",
    )

    assert result == utterance
    assert "用户是否已拆封或激活手机" not in result


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
    def __init__(self, score: int = 86, *, room_utterance: str | None = None) -> None:
        self.score = score
        self.room_utterance = room_utterance or (
            "我已经了解本案的基本情况：物流显示签收但你主张未收到，"
            "商家暂未提供签收底单。右侧案件详情已整理到可进入下一步。"
        )
        self.calls: list[dict[str, object]] = []

    # 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`self.calls.append`、`SimpleNamespace`、`output_type`。
    # 上下游：上游为 本文件的 `test_intake_case_detail_translates_llm_missing_field_codes_before_persisting`；下游为 协作调用 `self.calls.append`、`SimpleNamespace`、`output_type`、`context_pack.prompt_sections`。
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
            }
        )
        return SimpleNamespace(
            value=output_type(
                room_utterance=self.room_utterance,
                unilateral_case_matrix={
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
                },
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
        == "READY_PENDING_REMARK_INVITE"
    )


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

    assert result.scroll_snapshot["intake_quality"]["score"] == 92
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
# 具体功能：`test_intake_case_detail_translates_llm_missing_field_codes_before_persisting` 把结构化模型调用写入或合并到可追溯的阶段状态；关键协作调用：`run`、`IntakeTurnWorkflow`、`RunnerWithFieldCodes`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`、`invoke_structured`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_intake_case_detail_translates_llm_missing_field_codes_before_persisting() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    class RunnerWithFieldCodes(CaseDetailRunner):
        # 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：类/闭包内部方法。
        # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`invoke_structured`。
        # 上下游：上游为 本文件的 `test_intake_case_detail_translates_llm_missing_field_codes_before_persisting`；下游为 协作调用 `invoke_structured`。
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
        ):
            generation = super().invoke_structured(
                node_name=node_name,
                case_data=case_data,
                output_type=output_type,
                context_sections=context_sections,
                context_pack=context_pack,
                agent_context=agent_context,
                prompt_profile_id=prompt_profile_id,
            )
            generation.value.case_detail["intake_quality"]["score"] = 70
            generation.value.case_detail["intake_quality"][
                "improvement_reason"
            ] = "仍缺少可信的buyer_evidence、merchant_outbound_photos"
            generation.value.case_detail["missing_information"]["blocking_gaps"] = [
                "buyer_evidence",
                "merchant_outbound_photos",
            ]
            generation.value.missing_fields = [
                "buyer_evidence",
                "merchant_outbound_photos",
            ]
            return generation

    result = IntakeTurnWorkflow(model_runner=RunnerWithFieldCodes()).run(_request())

    quality_reason = result.scroll_snapshot["intake_quality"]["improvement_reason"]
    blocking_gaps = result.scroll_snapshot["missing_information"]["blocking_gaps"]
    assert "买家证据材料" in quality_reason
    assert "商家发货前照片" in quality_reason
    assert "buyer_evidence" not in quality_reason
    assert "merchant_outbound_photos" not in quality_reason
    assert blocking_gaps == ["买家证据材料", "商家发货前照片"]


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_ready_intake_turn_asks_for_handoff_remark_before_next_room` 读取并按案件、角色或会话范围筛选接待信息；关键协作调用：`CaseDetailRunner`、`run`、`IntakeTurnWorkflow`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_newly_ready_intake_turn_keeps_streamed_final_question() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    final_question = "已记录。商家当时对退款诉求给出的具体答复是什么？"
    runner = CaseDetailRunner(score=86, room_utterance=final_question)
    result = IntakeTurnWorkflow(model_runner=runner).run(_request())

    assert result.room_utterance == final_question
    assert (
        result.scroll_snapshot["handoff_notes"]["remark_status"]
        == "READY_PENDING_REMARK_INVITE"
    )


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_handoff_remark_is_persisted_when_officer_is_waiting_for_remark` 把本阶段状态写入或合并到可追溯的阶段状态；关键协作调用：`CaseDetailRunner`、`run`、`IntakeTurnWorkflow`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_handoff_remark_is_persisted_when_officer_is_waiting_for_remark() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    remark = "请证据书记官重点核查快递柜取件记录。"
    runner = CaseDetailRunner(score=88)
    request = _request(
        current_user_message={
            "message_id": "MESSAGE_REMARK_1",
            "role": "USER",
            "text": remark,
        },
        latest_scroll_snapshot={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_1001",
                "after_sales_reference": "AS_1001",
                "logistics_reference": "SF1001001001",
            },
            "intake_quality": {
                "score": 86,
                "threshold": 80,
                "ready_for_next_step": True,
            },
            "handoff_notes": {
                "remark_status": "WAITING_FOR_REMARK",
                "remarks": [],
            },
        },
    )

    result = IntakeTurnWorkflow(model_runner=runner).run(request)

    notes = result.scroll_snapshot["handoff_notes"]
    assert notes["remark_status"] == "HAS_REMARKS"
    assert notes["latest_remark"] == remark
    assert notes["remarks"][-1]["role"] == "USER"
    assert notes["remarks"][-1]["text"] == remark
    assert notes["remarks"][-1]["source_message_id"] == "MESSAGE_REMARK_1"
    assert "已收到备注" in result.room_utterance


# 所属模块：Agent 角色能力 > test_intake_case_detail_dossier；函数角色：回归测试用例。
# 具体功能：`test_ready_board_does_not_treat_regular_followup_as_remark_before_officer_asks` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`CaseDetailRunner`、`run`、`IntakeTurnWorkflow`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_request`。
# 系统意义：固定“Agent 角色能力 > test_intake_case_detail_dossier”的可观察契约，防止后续重构改变业务结果。
def test_pending_ready_board_records_final_answer_then_invites_remark() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    runner = CaseDetailRunner(score=88)
    request = _request(
        current_user_message={
            "message_id": "MESSAGE_FOLLOWUP_1",
            "role": "USER",
            "text": "我再补充一下，商家当时说要等物流回复。",
        },
        latest_scroll_snapshot={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_1001",
                "after_sales_reference": "AS_1001",
                "logistics_reference": "SF1001001001",
            },
            "intake_quality": {
                "score": 86,
                "threshold": 80,
                "ready_for_next_step": True,
            },
            "handoff_notes": {
                "remark_status": "READY_PENDING_REMARK_INVITE",
                "latest_remark": "",
                "remarks": [],
            },
        },
    )

    result = IntakeTurnWorkflow(model_runner=runner).run(request)

    notes = result.scroll_snapshot["handoff_notes"]
    assert notes["remark_status"] == "WAITING_FOR_REMARK"
    assert notes["latest_remark"] == ""
    assert notes["remarks"] == []
    assert "已记录本轮补充" in result.room_utterance
    assert "还有没有需要备注" in result.room_utterance


def test_pending_ready_stream_matches_normalized_final_utterance() -> None:
    from app.agents.dispute_intake_officer.workflow import IntakeTurnWorkflow

    raw_question = (
        "已记录您补充的情况。请问商家是否还提及其他拒绝退款的依据？"
        "另外，您希望平台重点核实哪方面的情况？"
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

    request = _request(
        current_user_message={
            "message_id": "MESSAGE_FINAL_ANSWER",
            "role": "USER",
            "text": "商家只说商品已经吃完，所以拒绝退款。",
        },
        latest_scroll_snapshot={
            "schema_version": "intake_case_detail.v1",
            "references": {
                "order_reference": "ORDER_1001",
                "after_sales_reference": "AS_1001",
                "logistics_reference": "SF1001001001",
            },
            "intake_quality": {
                "score": 86,
                "threshold": 80,
                "ready_for_next_step": True,
            },
            "handoff_notes": {
                "remark_status": "READY_PENDING_REMARK_INVITE",
                "latest_remark": "",
                "remarks": [],
            },
        },
    )
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
                room_utterance=raw_question,
            )
        ).run(request)

    streamed_utterance = "".join(
        event.delta
        for event in published
        if event.type == "visible_delta" and event.field == "room_utterance"
    )

    assert result.scroll_snapshot["handoff_notes"]["remark_status"] == (
        "WAITING_FOR_REMARK"
    )
    assert raw_question not in streamed_utterance
    assert streamed_utterance == result.room_utterance
    assert "还有没有需要备注" in streamed_utterance
