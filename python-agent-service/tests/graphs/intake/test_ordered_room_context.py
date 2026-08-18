"""Focused contract checks for the ordered Intake room model stream."""

from __future__ import annotations

import copy

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from app.agents.dispute_intake_officer.schemas import (
    INTAKE_ROOM_SECTION_KINDS,
    IntakeInitiatorRoomLlmOutputV3,
    IntakeRespondentRoomLlmOutputV3,
    MaterializedIntakeRoomLlmOutputV3,
    intake_case_detail_output_type,
    materialize_intake_case_detail_output,
    revalidate_materialized_intake_output,
)
from app.agents.dispute_intake_officer.skills.dossier import dossier_skill
from app.agents.dispute_intake_officer.workflow import (
    project_intake_case_detail_output,
)
from app.harness.context_pack import build_context_pack
from app.harness.context_window import ContextWindowManager
from app.llm import AgentOutputSchemaError
from app.schemas import IntakeTurnRequest
from app.schemas.case_fact_matrix import CaseFactMatrixDeltaV2


def _agent_context(*, case_id: str, role: str) -> dict[str, object]:
    actor_id = f"{role}_ordered_room"
    prompt_profile_id = f"DISPUTE_INTAKE_OFFICER:{role}:v1"
    access_session_id = f"ACCESS_{case_id}_{role}"
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "INTAKE",
        "actor_id": actor_id,
        "actor_role": role,
        "access_session_id": access_session_id,
        "permission_level": f"PARTY_{role}",
        "permission_scopes": [],
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_invocation_id": f"INVOCATION_{case_id}_{role}",
        "agent_session_id": f"SESSION_{case_id}_{role}",
        "conversation_scope": (
            f"default:{case_id}:INTAKE:{actor_id}:{role}:"
            f"DISPUTE_INTAKE_OFFICER:{prompt_profile_id}:{access_session_id}"
        ),
        "scope_type": "INTAKE_PARTY_PRIVATE",
        "allowed_actor_ids": [actor_id],
        "allowed_actor_roles": [role],
        "prompt_profile_id": prompt_profile_id,
        "memory_policy_id": "MEMORY_POLICY_INTAKE_V1",
    }


def _initiator_v3_payload() -> dict[str, object]:
    return {
        "room_utterance": "已记录订单未按约送达，请补充实际发现异常的时间。",
        "ordered_sections": [
            {
                "sequence": 1,
                "kind": "CASE_MATRIX",
                "value": {
                    "schema_version": "case_fact_matrix.delta.v2",
                    "fact_rows": [
                        {
                            "fact_key": "NEW_DELIVERY_STATE",
                            "category": "LOGISTICS",
                            "fact_target": "订单是否在承诺时间内送达",
                            "materiality": "CORE",
                            "stance": "DENY",
                            "position_summary": "用户称订单未在承诺时间内送达。",
                            "asserted_value": "尚未收到",
                            "source_scope": "CURRENT_SOURCE",
                        }
                    ],
                    "summary_source_fact_keys": ["NEW_DELIVERY_STATE"],
                },
            },
            {
                "sequence": 2,
                "kind": "CASE_STORY",
                "value": {
                    "title": "订单履约时效争议",
                    "one_sentence_summary": "用户称订单未在承诺时间内送达并要求退款。",
                },
            },
            {
                "sequence": 3,
                "kind": "PARTY_POSITIONS",
                "value": {
                    "user_claim": "用户称尚未收到订单。",
                    "merchant_claim": "商家尚未直接回应。",
                    "initiator_position": "用户要求退款。",
                    "respondent_position": "商家尚未直接回应。",
                    "platform_observation": "目前仅有用户单方陈述。",
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
                        "request_reason": "订单未在承诺时间内送达。",
                        "normalized_statement": "用户要求对未按时送达的订单退款。",
                    },
                    "respondent_attitude": {
                        "respondent_role": "MERCHANT",
                        "source_attribution": "NO_DIRECT_POSITION",
                        "attitude": "NOT_RESPONDED",
                        "position": "商家尚未在接待室表达态度。",
                        "alternative_proposal": None,
                    },
                },
            },
            {
                "sequence": 5,
                "kind": "DISPUTE_FOCUS",
                "value": {
                    "dispute_core_state": {
                        "conflict_type": "CLAIM_UNANSWERED",
                        "core_conflict": "用户要求退款，商家尚未回应。",
                        "facts_in_dispute": ["订单是否按承诺时间送达"],
                    },
                    "dispute_focus": {
                        "core_issue": "订单履约状态与退款诉求",
                        "focus_points": ["实际送达状态", "异常发现时间"],
                    },
                },
            },
            {
                "sequence": 6,
                "kind": "VERIFICATION_FOCUS",
                "value": {"items": ["核验订单是否在承诺时间内送达"]},
            },
            {
                "sequence": 7,
                "kind": "RISK_ASSESSMENT",
                "value": {
                    "case_grade": "MEDIUM",
                    "risk_points": ["送达状态尚未形成双方陈述"],
                    "summary": "当前争议集中在履约状态与退款诉求。",
                },
            },
            {
                "sequence": 8,
                "kind": "MISSING_INFORMATION",
                "value": {
                    "blocking_gaps": ["异常发现时间"],
                    "nice_to_have_gaps": [],
                    "next_questions": ["您是在什么时间发现订单仍未送达的？"],
                },
            },
            {
                "sequence": 9,
                "kind": "HANDOFF_SUMMARY",
                "value": {
                    "remark_status": "NOT_READY",
                    "latest_remark": "",
                    "instruction": "补充异常发现时间后继续整理。",
                },
            },
            {
                "sequence": 10,
                "kind": "TURN_EVALUATION",
                "value": {
                    "score_breakdown": {
                        "references": 10,
                        "event_story": 8,
                        "party_positions": 8,
                        "requested_resolution": 10,
                        "risk_and_conflicts": 7,
                        "next_action_clarity": 7,
                    },
                    "total_score": 50,
                    "threshold": 85,
                    "ready_for_next_step": False,
                    "improvement_reason": "仍需补充异常发现时间。",
                    "admission_recommendation": "NEED_MORE_INFO",
                    "admission_reasoning": "关键时间事实仍不完整。",
                    "confidence": 0.8,
                    "conversation_action": "ASK_SUBSTANTIVE",
                    "knowledge_answer_mode": "NONE",
                },
            },
        ],
    }


def test_intake_room_v3_contract_places_reply_first_and_evaluation_last() -> None:
    schema = IntakeInitiatorRoomLlmOutputV3.model_json_schema()

    assert list(schema["properties"]) == ["room_utterance", "ordered_sections"]
    assert INTAKE_ROOM_SECTION_KINDS == (
        "CASE_MATRIX",
        "CASE_STORY",
        "PARTY_POSITIONS",
        "CLAIM_AND_RESPONSE",
        "DISPUTE_FOCUS",
        "VERIFICATION_FOCUS",
        "RISK_ASSESSMENT",
        "MISSING_INFORMATION",
        "HANDOFF_SUMMARY",
        "TURN_EVALUATION",
    )
    section_schema = schema["properties"]["ordered_sections"]
    assert len(section_schema["anyOf"]) == 4
    assert all(
        len(branch["prefixItems"]) == len(INTAKE_ROOM_SECTION_KINDS)
        for branch in section_schema["anyOf"]
    )
    assert all(
        all("$ref" in branch["prefixItems"][index] for index in (0, 7, 8, 9))
        for branch in section_schema["anyOf"]
    )


def test_intake_room_v3_provider_schema_binds_readiness_before_streaming() -> None:
    payload = _initiator_v3_payload()
    missing = payload["ordered_sections"][7]["value"]
    missing.update(
        {
            "blocking_gaps": [],
            "nice_to_have_gaps": [
                "detailed_delivery_address",
                "property_management_confirmation",
            ],
            "next_questions": [
                "该订单的收货地址具体是哪里？",
                "您是否尝试过联系物业或前台核实？",
            ],
        }
    )
    evaluation = payload["ordered_sections"][9]["value"]
    evaluation.update(
        {
            "score_breakdown": {
                "references": 15,
                "event_story": 20,
                "party_positions": 20,
                "requested_resolution": 15,
                "risk_and_conflicts": 12,
                "next_action_clarity": 12,
            },
            "total_score": 94,
            "threshold": 85,
            "ready_for_next_step": False,
            "improvement_reason": "仍有两项补充信息可进一步明确履约事实。",
            "admission_recommendation": "NEED_MORE_INFO",
            "admission_reasoning": "本轮继续询问补充信息。",
            "conversation_action": "ASK_SUBSTANTIVE",
        }
    )

    schema = IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    Draft202012Validator.check_schema(schema)
    provider_validator = Draft202012Validator(schema)

    assert list(provider_validator.iter_errors(payload))
    with pytest.raises(ValidationError):
        IntakeInitiatorRoomLlmOutputV3.model_validate(payload)

    ready = copy.deepcopy(payload)
    ready["ordered_sections"][7]["value"]["next_questions"] = []
    ready["ordered_sections"][8]["value"].update(
        {
            "remark_status": "WAITING_FOR_REMARK",
            "instruction": "案情已达到接待要求，请确认是否还有可选交接备注。",
        }
    )
    ready["ordered_sections"][9]["value"].update(
        {
            "ready_for_next_step": True,
            "admission_recommendation": "ACCEPTED",
            "admission_reasoning": "评分达到阈值且不存在阻塞缺口。",
            "conversation_action": "INVITE_OPTIONAL_REMARK",
        }
    )
    assert provider_validator.is_valid(ready)
    first = IntakeInitiatorRoomLlmOutputV3.model_validate(ready)
    replay = IntakeInitiatorRoomLlmOutputV3.model_validate(copy.deepcopy(ready))
    assert first.model_dump(mode="python") == replay.model_dump(mode="python")

    blocked = copy.deepcopy(payload)
    blocked["ordered_sections"][7]["value"]["blocking_gaps"] = [
        "缺少可核对的具体收货地址"
    ]
    assert provider_validator.is_valid(blocked)
    assert (
        IntakeInitiatorRoomLlmOutputV3.model_validate(blocked)
        .ordered_sections[9]
        .value.ready_for_next_step
        is False
    )


def test_intake_room_v3_total_score_is_the_single_model_score_authority() -> None:
    payload = _initiator_v3_payload()
    evaluation = payload["ordered_sections"][9]["value"]
    evaluation["score_breakdown"]["references"] = 11

    schema = IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    assert Draft202012Validator(schema).is_valid(payload)

    first = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)
    replay = IntakeInitiatorRoomLlmOutputV3.model_validate(copy.deepcopy(payload))

    assert first.model_dump(mode="python") == replay.model_dump(mode="python")
    assert first.ordered_sections[9].value.total_score == 50
    assert first.ordered_sections[9].value.score_breakdown.references == 11


def test_intake_context_retention_is_separate_from_physical_prompt_order() -> None:
    pack = build_context_pack(
        "intake_turn_case_detail",
        {
            "current_user_message": {"text": "CURRENT"},
            "recent_dialogue_messages": [{"text": "RECENT"}],
            "previous_dispute_outline": {"case_story": {"title": "OUTLINE"}},
            "frozen_case_matrix": {"schema_version": "case_fact_matrix.v2"},
            "initial_case_facts": {"form_description": "INITIAL"},
            "case_identity": {"case_id": "CASE_ORDERED_CONTEXT"},
        },
        actor_role="USER",
        required_section_names=frozenset(
            {"case_identity", "initial_case_facts", "current_user_message"}
        ),
    )

    assembled = ContextWindowManager().assemble(pack.prompt_sections())

    assert [section.name for section in assembled.sections] == [
        "case_identity",
        "initial_case_facts",
        "frozen_case_matrix",
        "previous_dispute_outline",
        "recent_dialogue_messages",
        "current_user_message",
    ]


def test_v3_projection_preserves_model_evaluation_without_legacy_recalculation() -> None:
    case_id = "CASE_ORDERED_ROOM_SCORE"
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "EXTERNAL_IMPORT",
            "initial_case_facts": {
                "form_source": "EXTERNAL_IMPORT",
                "form_description": "用户称订单未在承诺时间内送达并要求退款。",
                "order_reference": "ORDER_ORDERED_1",
                "initiator_role": "USER",
                "requested_outcome_hint": "REFUND",
            },
            "agent_context": _agent_context(case_id=case_id, role="USER"),
        }
    )
    payload = _initiator_v3_payload()
    # The typed total is the model's sole score authority; component values are
    # retained as explanatory detail and are not recomputed or cross-summed by
    # the legacy dossier reducer.
    payload["ordered_sections"][9]["value"]["score_breakdown"]["references"] = 11
    output = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)
    materialized = materialize_intake_case_detail_output(request, output)

    projected = project_intake_case_detail_output(
        request=request,
        output=materialized,
        source_text="用户称订单未在承诺时间内送达并要求退款。",
    )

    quality = projected["scroll_snapshot"]["intake_quality"]
    assert quality["score"] == 50
    assert quality["score_breakdown"] == output.ordered_sections[-1].value.score_breakdown.model_dump()
    assert quality["improvement_reason"] == "仍需补充异常发现时间。"
    assert projected["missing_fields"] == ["异常发现时间"]


def test_v3_direct_binding_uses_typed_model_authority_without_regex(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    case_id = "CASE_ORDERED_ROOM_BINDING"
    current_text = "本店认可用户描述的异常，并把处理方案定为补发一年会员权益。"
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_ORDERED_BINDING",
                "sequence_no": 8,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": current_text,
            },
            "previous_case_detail": {
                "schema_version": "intake_case_detail.v1",
                "claim_resolution": {
                    "initiator_role": "USER",
                    "requested_resolution": "REFUND",
                },
            },
            "agent_context": _agent_context(case_id=case_id, role="MERCHANT"),
        }
    )
    matrix = CaseFactMatrixDeltaV2.model_validate(
        {
            "schema_version": "case_fact_matrix.delta.v2",
            "fact_rows": [
                {
                    "fact_key": "NEW_MEMBER_RESHIP",
                    "category": "AFTER_SALES",
                    "fact_target": "商家是否提出补发一年会员权益",
                    "materiality": "CORE",
                    "stance": "CONFIRM",
                    "position_summary": "商家提出补发一年会员权益。",
                    "asserted_value": "补发一年会员权益",
                    "source_scope": "CURRENT_SOURCE",
                }
            ],
            "summary_source_fact_keys": ["NEW_MEMBER_RESHIP"],
            "respondent_claim": {
                "attitude": "ALTERNATIVE_PROPOSED",
                "position_summary": "商家提出补发一年会员权益。",
                "alternative_proposal": "补发一年会员权益",
            },
        }
    )
    model_detail = {
        "respondent_attitude": {
            "respondent_role": "MERCHANT",
            "attitude": "ALTERNATIVE_PROPOSED",
            "position": "商家提出补发一年会员权益。",
            "alternative_proposal": "补发一年会员权益",
        }
    }
    detail = copy.deepcopy(model_detail)
    binding = {
        "schema_version": "respondent-claim-binding.v1",
        "binding_kind": "CURRENT_ACTOR_DIRECT",
        "subject_role": "MERCHANT",
        "source_quote": "把处理方案定为补发一年会员权益",
        "linked_fact_keys": ["NEW_MEMBER_RESHIP"],
    }
    monkeypatch.setattr(
        dossier_skill,
        "detect_direct_respondent_attitude",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("legacy regex detector must not run for V3")
        ),
    )

    dossier_skill._bind_model_trusted_respondent_attitude(
        detail,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        binding,
    )

    assert detail["respondent_attitude"] == {
        "respondent_role": "MERCHANT",
        "attitude": "ALTERNATIVE_PROPOSED",
        "position": "商家提出补发一年会员权益。",
        "source": "被发起方接待室直接陈述",
        "confidence": 0.65,
        "grounding": {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_ORDERED_BINDING",
        },
        "alternative_proposal": "补发一年会员权益",
    }

    wrong_binding = {**binding, "source_quote": "当前消息中不存在的引文"}
    with pytest.raises(AgentOutputSchemaError) as failure:
        negative_detail = copy.deepcopy(model_detail)
        dossier_skill._bind_model_trusted_respondent_attitude(
            negative_detail,
            request,
            copy.deepcopy(request.previous_case_detail or {}),
            copy.deepcopy(model_detail),
            matrix,
            wrong_binding,
        )
    assert failure.value.safe_code == "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED"


def test_respondent_turn_projects_frozen_claim_and_keeps_reported_attitude_attribution() -> None:
    case_id = "CASE_ORDERED_ROOM_CROSS_PARTY_AUTHORITY"
    frozen_claim = {
        "initiator_role": "USER",
        "requested_resolution": "RETURN_REFUND",
        "requested_amount": 1899,
        "requested_items": "空气净化器 1 台",
        "request_reason": "核心性能未达到宣传标准，要求退货退款并核验宣传参数依据。",
        "normalized_statement": "用户要求退回空气净化器并获得全额退款，理由是商品实际性能与宣传不符。",
    }
    previous = {
        "schema_version": "intake_case_detail.v1",
        "claim_resolution": copy.deepcopy(frozen_claim),
        "case_fact_matrix": {
            "schema_version": "case_fact_matrix.v2",
            "party_map": {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
            },
        },
    }
    respondent_request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_CROSS_PARTY_AUTHORITY",
                "sequence_no": 5,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": "我方同意标准复检，复检不达标时同意退货退款。",
            },
            "previous_case_detail": previous,
            "agent_context": _agent_context(case_id=case_id, role="MERCHANT"),
        }
    )
    provider_detail = {
        "claim_resolution": {
            **copy.deepcopy(frozen_claim),
            # The model is allowed to understand the current respondent turn,
            # but it does not own a new wording of the other party's frozen claim.
            "request_reason": "核心性能未达到宣传标准",
        }
    }
    provider_payload = _initiator_v3_payload()
    matrix = provider_payload["ordered_sections"][0]["value"]
    matrix["respondent_claim"] = {
        "attitude": "ALTERNATIVE_PROPOSED",
        "position_summary": "商家同意标准复检，复检不达标时同意退货退款。",
        "alternative_proposal": "标准复检",
        "source_binding": {
            "schema_version": "respondent-claim-binding.v1",
            "binding_kind": "CURRENT_ACTOR_DIRECT",
            "subject_role": "MERCHANT",
            "source_quote": "我方同意标准复检，复检不达标时同意退货退款",
            "linked_fact_keys": ["NEW_DELIVERY_STATE"],
        },
    }
    claim_and_response = provider_payload["ordered_sections"][3]["value"]
    claim_and_response["claim_resolution"] = copy.deepcopy(
        provider_detail["claim_resolution"]
    )
    claim_and_response["respondent_attitude"] = {
        "respondent_role": "MERCHANT",
        "source_attribution": "RESPONDENT_DIRECT",
        "attitude": "ALTERNATIVE_PROPOSED",
        "position": "商家同意标准复检，复检不达标时同意退货退款。",
        "alternative_proposal": "标准复检",
    }
    respondent_output_type = intake_case_detail_output_type(respondent_request)
    respondent_schema = respondent_output_type.model_json_schema()
    Draft202012Validator.check_schema(respondent_schema)
    assert len(respondent_schema["properties"]["ordered_sections"]["anyOf"]) == 4

    with pytest.raises(ValidationError):
        respondent_output_type.model_validate(provider_payload)
    provider_payload["ordered_sections"][3]["value"]["claim_resolution"] = (
        copy.deepcopy(frozen_claim)
    )
    assert respondent_output_type.model_validate(provider_payload)

    first = copy.deepcopy(provider_detail)
    dossier_skill._bind_model_trusted_claim_authority(
        first,
        respondent_request,
        copy.deepcopy(previous),
    )
    replay = copy.deepcopy(provider_detail)
    dossier_skill._bind_model_trusted_claim_authority(
        replay,
        respondent_request,
        copy.deepcopy(previous),
    )

    assert first["claim_resolution"] == frozen_claim
    assert replay == first

    wrong_role = copy.deepcopy(provider_detail)
    wrong_role["claim_resolution"]["initiator_role"] = "MERCHANT"
    with pytest.raises(AgentOutputSchemaError) as role_failure:
        dossier_skill._bind_model_trusted_claim_authority(
            wrong_role,
            respondent_request,
            copy.deepcopy(previous),
        )
    assert role_failure.value.safe_code == "INTAKE_PARTY_STATE_ROLE_AUTHORITY_DRIFT"

    initiator_request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_REPORTED_ATTITUDE",
                "sequence_no": 3,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "商家此前表示不认可我的检测结果。",
            },
            "previous_case_detail": previous,
            "agent_context": _agent_context(case_id=case_id, role="USER"),
        }
    )
    no_direct_response = {
        "respondent_attitude": {
            "respondent_role": "MERCHANT",
            "attitude": "NOT_RESPONDED",
            "position": "据用户单方陈述，商家不认可现有检测结果。",
        }
    }
    dossier_skill._bind_model_trusted_respondent_attitude(
        no_direct_response,
        initiator_request,
        copy.deepcopy(previous),
        copy.deepcopy(no_direct_response),
        None,
        None,
    )

    assert no_direct_response["respondent_attitude"] == {
        "respondent_role": "MERCHANT",
        "attitude": "NOT_RESPONDED",
        "position": "商家尚未在接待室表达态度。",
        "source": "尚未回应",
        "confidence": 0.5,
    }


def test_v3_fact_key_normalization_rebinds_private_respondent_source() -> None:
    case_id = "CASE_ORDERED_ROOM_NORMALIZED_BINDING"
    current_text = "我方不同意退款，订单已按时送达。"
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_NORMALIZED_BINDING",
                "sequence_no": 3,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": current_text,
            },
            "previous_case_detail": {
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
            },
            "agent_context": _agent_context(case_id=case_id, role="MERCHANT"),
        }
    )
    provider_payload = _initiator_v3_payload()
    matrix = provider_payload["ordered_sections"][0]["value"]
    matrix["fact_rows"] = [
        {
            "fact_key": "NEW_DELIVERY_STATE",
            "category": "LOGISTICS",
            "fact_target": "订单是否按时送达",
            "materiality": "CORE",
            "stance": "CONFIRM",
            "position_summary": "商家称订单已按时送达。",
            "asserted_value": "已按时送达",
            "source_scope": "CURRENT_SOURCE",
        }
    ]
    matrix["summary_source_fact_keys"] = ["NEW_DELIVERY_STATE"]
    matrix["respondent_claim"] = {
        "attitude": "DISAGREE",
        "position_summary": "商家不同意退款。",
        "alternative_proposal": None,
        "source_binding": {
            "schema_version": "respondent-claim-binding.v1",
            "binding_kind": "CURRENT_ACTOR_DIRECT",
            "subject_role": "MERCHANT",
            "source_quote": "我方不同意退款",
            "linked_fact_keys": ["NEW_DELIVERY_STATE"],
        },
    }
    claim_and_response = provider_payload["ordered_sections"][3]["value"]
    claim_and_response["respondent_attitude"] = {
        "respondent_role": "MERCHANT",
        "source_attribution": "RESPONDENT_DIRECT",
        "attitude": "DISAGREE",
        "position": "商家不同意退款。",
        "alternative_proposal": None,
    }

    provider_output = IntakeRespondentRoomLlmOutputV3.model_validate(
        provider_payload
    )
    original = materialize_intake_case_detail_output(request, provider_output)
    normalized_payload = original.model_dump(mode="json", exclude_none=True)
    normalized_matrix = normalized_payload["case_matrix_delta"]
    normalized_matrix["fact_rows"][0]["fact_key"] = "FACT_DELIVERY_STATE"
    normalized_matrix["summary_source_fact_keys"] = ["FACT_DELIVERY_STATE"]

    normalized = revalidate_materialized_intake_output(
        original,
        normalized_payload,
    )
    replay = revalidate_materialized_intake_output(
        normalized,
        normalized_payload,
    )

    assert isinstance(normalized, MaterializedIntakeRoomLlmOutputV3)
    assert normalized.respondent_source_binding is not None
    assert normalized.respondent_source_binding.linked_fact_keys == [
        "FACT_DELIVERY_STATE"
    ]
    assert replay.model_dump(mode="json") == normalized.model_dump(mode="json")
