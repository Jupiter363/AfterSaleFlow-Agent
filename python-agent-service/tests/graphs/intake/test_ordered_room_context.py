"""Focused contract checks for the ordered Intake room model stream."""

from __future__ import annotations

import copy
import json

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
    build_intake_turn_context_pack,
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
                    "initiator_position": "用户要求退款。",
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
                    }
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


def _respondent_v3_payload() -> dict[str, object]:
    payload = _initiator_v3_payload()
    payload["ordered_sections"][2]["value"] = {
        "respondent_position": "商家仅补充本方直接回应。",
        "platform_observation": "平台仅整理当前被发起方的直接陈述。",
    }
    payload["ordered_sections"][3]["value"] = {
        "respondent_attitude": {
            "respondent_role": "MERCHANT",
            "source_attribution": "NO_DIRECT_POSITION",
            "attitude": "NOT_RESPONDED",
            "position": "本轮未表达处理态度。",
            "alternative_proposal": None,
        }
    }
    return payload


def test_ordered_room_matrix_rebinds_only_unique_case_drifted_summary_keys() -> None:
    payload = _initiator_v3_payload()
    matrix = payload["ordered_sections"][0]["value"]
    matrix["fact_rows"][0]["fact_key"] = "FACT_ORDER_REF"
    matrix["summary_source_fact_keys"] = ["FACt_ORDER_REF"]

    normalized = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)

    assert normalized.ordered_sections[0].value.summary_source_fact_keys == [
        "FACT_ORDER_REF"
    ]

    ambiguous = copy.deepcopy(payload)
    ambiguous_matrix = ambiguous["ordered_sections"][0]["value"]
    second_row = copy.deepcopy(ambiguous_matrix["fact_rows"][0])
    second_row["fact_key"] = "FACT_order_ref"
    second_row["fact_target"] = "订单引用是否与另一个大小写不同的事实键相同"
    ambiguous_matrix["fact_rows"].append(second_row)
    ambiguous_matrix["summary_source_fact_keys"] = ["FaCt_ORDER_REF"]

    with pytest.raises(
        ValidationError,
        match="summary_source_fact_keys must reference at least one delta fact",
    ):
        IntakeInitiatorRoomLlmOutputV3.model_validate(ambiguous)


def test_ordered_room_matrix_rebinds_only_unique_missing_namespace_prefixes() -> None:
    payload = _initiator_v3_payload()
    matrix = payload["ordered_sections"][0]["value"]
    existing_row = matrix["fact_rows"][0]
    existing_row["fact_key"] = "FACT_E6720A1DE3A7194BF9B5000D"
    new_row = copy.deepcopy(existing_row)
    new_row.update(
        fact_key="NEW_MERCHANT_RETEST_COORDINATION_PREFERENCE",
        fact_target="复测机构选定方式",
        source_scope="CURRENT_SOURCE",
    )
    matrix["fact_rows"].append(new_row)
    matrix["summary_source_fact_keys"] = [
        "E6720A1DE3A7194BF9B5000D",
        "MERCHANT_RETEST_COORDINATION_PREFERENCE",
    ]

    normalized = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)

    assert normalized.ordered_sections[0].value.summary_source_fact_keys == [
        "FACT_E6720A1DE3A7194BF9B5000D",
        "NEW_MERCHANT_RETEST_COORDINATION_PREFERENCE",
    ]

    ambiguous = copy.deepcopy(payload)
    ambiguous_matrix = ambiguous["ordered_sections"][0]["value"]
    ambiguous_row = copy.deepcopy(new_row)
    ambiguous_row.update(
        fact_key="FACT_MERCHANT_RETEST_COORDINATION_PREFERENCE",
        fact_target="另一条同主体键事实",
        source_scope="CURRENT_SOURCE",
    )
    ambiguous_matrix["fact_rows"].append(ambiguous_row)
    ambiguous_matrix["summary_source_fact_keys"] = [
        "MERCHANT_RETEST_COORDINATION_PREFERENCE"
    ]

    with pytest.raises(
        ValidationError,
        match="summary_source_fact_keys must reference at least one delta fact",
    ):
        IntakeInitiatorRoomLlmOutputV3.model_validate(ambiguous)


def test_intake_room_v3_contract_places_reply_first_and_evaluation_last() -> None:
    schema = IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    serialized_schema = json.dumps(schema, sort_keys=True)

    assert list(schema["properties"]) == ["room_utterance", "ordered_sections"]
    assert "respondent_attitude" not in serialized_schema
    assert "respondent_claim" not in serialized_schema
    assert "respondent_position" not in serialized_schema
    assert "user_claim" not in serialized_schema
    assert "merchant_claim" not in serialized_schema
    assert "INITIATOR_REPORTED" not in serialized_schema
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
    # The provider contract has one explicit branch for the one-turn-lag
    # threshold crossing: this turn still asks the prior question while the
    # persisted next-turn state becomes READY_PENDING_REMARK_INVITE.
    assert len(section_schema["anyOf"]) == 5
    assert all(
        len(branch["prefixItems"]) == len(INTAKE_ROOM_SECTION_KINDS)
        for branch in section_schema["anyOf"]
    )
    assert all(
        all("$ref" in branch["prefixItems"][index] for index in (0, 7, 8, 9))
        for branch in section_schema["anyOf"]
    )

    injected_counterparty_claim = _initiator_v3_payload()
    injected_counterparty_claim["ordered_sections"][0]["value"][
        "respondent_claim"
    ] = {
        "attitude": "AGREE",
        "position_summary": "用户转述商家同意退款。",
    }
    with pytest.raises(ValidationError):
        IntakeInitiatorRoomLlmOutputV3.model_validate(injected_counterparty_claim)


def test_initiator_provider_schema_keeps_reported_and_direct_opponent_positions_distinct() -> None:
    schema = IntakeInitiatorRoomLlmOutputV3.model_json_schema()

    utterance_description = schema["properties"]["room_utterance"]["description"]
    sections_description = schema["properties"]["ordered_sections"]["description"]

    assert "voluntarily reports what the merchant/opponent previously said" in utterance_description
    assert "never treat it as the opponent's direct position" in utterance_description
    assert "not a completeness gap" in utterance_description
    assert "An attributed counterparty report remains part of the initiator's narrative" in sections_description
    assert "direct opponent positions are collected only in the respondent turn" in sections_description


def test_intake_room_v3_accepts_prior_state_action_with_new_high_score() -> None:
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

    assert provider_validator.is_valid(payload)
    # The current action is owned by the previously persisted state.  A newly
    # high six-component sum must therefore remain structurally admissible as
    # ASK_SUBSTANTIVE; the reducer persists it for the next turn.
    lagged = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)
    assert lagged.ordered_sections[9].value.conversation_action == "ASK_SUBSTANTIVE"
    assert lagged.ordered_sections[9].value.ready_for_next_step is False

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


def test_request_specific_output_contract_locks_action_to_previous_phase() -> None:
    threshold_crossing = _initiator_v3_payload()
    threshold_crossing["ordered_sections"][7]["value"].update(
        {
            "blocking_gaps": [],
            "nice_to_have_gaps": [],
            "next_questions": ["请补充最后一项可核验事实。"],
        }
    )
    threshold_crossing["ordered_sections"][8]["value"].update(
        {
            "remark_status": "READY_PENDING_REMARK_INVITE",
            "instruction": "本轮回答已达标；下一轮先完成当前提问，再邀请备注。",
        }
    )
    threshold_crossing["ordered_sections"][9]["value"].update(
        {
            "score_breakdown": {
                "references": 15,
                "event_story": 20,
                "party_positions": 20,
                "requested_resolution": 15,
                "risk_and_conflicts": 15,
                "next_action_clarity": 15,
            },
            "ready_for_next_step": True,
            "admission_recommendation": "ACCEPTED",
            "admission_reasoning": "六项分数达到阈值且不存在阻塞缺口。",
            "conversation_action": "ASK_SUBSTANTIVE",
        }
    )
    invitation = copy.deepcopy(threshold_crossing)
    invitation["ordered_sections"][7]["value"]["next_questions"] = []
    invitation["ordered_sections"][8]["value"].update(
        {
            "remark_status": "WAITING_FOR_REMARK",
            "instruction": "请确认是否还有可选交接备注。",
        }
    )
    invitation["ordered_sections"][9]["value"][
        "conversation_action"
    ] = "INVITE_OPTIONAL_REMARK"

    def request_with_previous_status(status: str) -> IntakeTurnRequest:
        case_id = f"CASE_PHASE_LOCK_{status}"
        return IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": f"MESSAGE_PHASE_LOCK_{status}",
                    "sequence_no": 2,
                    "role": "USER",
                    "source": "ROOM_MESSAGE",
                    "text": "补充最后一项事实。",
                },
                "previous_case_detail": {
                    "party_intake_state": {
                        "USER": {
                            "handoff_notes": {"remark_status": status},
                        }
                    }
                },
                "agent_context": _agent_context(case_id=case_id, role="USER"),
            }
        )

    not_ready_type = intake_case_detail_output_type(
        request_with_previous_status("NOT_READY")
    )
    not_ready_schema = not_ready_type.model_json_schema()
    assert not_ready_schema != IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    Draft202012Validator.check_schema(not_ready_schema)
    not_ready_provider_validator = Draft202012Validator(not_ready_schema)
    assert not_ready_provider_validator.is_valid(threshold_crossing)
    assert not not_ready_provider_validator.is_valid(invitation)
    assert not_ready_type is intake_case_detail_output_type(
        request_with_previous_status("NOT_READY")
    )
    accepted_crossing = not_ready_type.model_validate(threshold_crossing)
    assert (
        accepted_crossing.ordered_sections[9].value.conversation_action
        == "ASK_SUBSTANTIVE"
    )
    with pytest.raises(ValidationError):
        not_ready_type.model_validate(invitation)

    pending_type = intake_case_detail_output_type(
        request_with_previous_status("READY_PENDING_REMARK_INVITE")
    )
    pending_schema = pending_type.model_json_schema()
    assert pending_schema != IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    Draft202012Validator.check_schema(pending_schema)
    pending_provider_validator = Draft202012Validator(pending_schema)
    assert pending_provider_validator.is_valid(invitation)
    assert not pending_provider_validator.is_valid(threshold_crossing)
    assert pending_type is intake_case_detail_output_type(
        request_with_previous_status("READY_PENDING_REMARK_INVITE")
    )
    accepted_invitation = pending_type.model_validate(invitation)
    assert (
        accepted_invitation.ordered_sections[9].value.conversation_action
        == "INVITE_OPTIONAL_REMARK"
    )
    with pytest.raises(ValidationError):
        pending_type.model_validate(threshold_crossing)


def test_intake_room_v3_exposes_only_component_score_authority() -> None:
    payload = _initiator_v3_payload()
    evaluation = payload["ordered_sections"][9]["value"]
    evaluation["score_breakdown"]["references"] = 11

    schema = IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    assert Draft202012Validator(schema).is_valid(payload)
    assert '"total_score"' not in json.dumps(schema, sort_keys=True)
    assert '"total_score"' not in json.dumps(
        IntakeRespondentRoomLlmOutputV3.model_json_schema(),
        sort_keys=True,
    )

    first = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)
    replay = IntakeInitiatorRoomLlmOutputV3.model_validate(copy.deepcopy(payload))

    assert first.model_dump(mode="python") == replay.model_dump(mode="python")
    assert first.ordered_sections[9].value.score_breakdown.references == 11
    assert "total_score" not in first.ordered_sections[9].value.model_dump()

    legacy = copy.deepcopy(payload)
    legacy["ordered_sections"][9]["value"]["total_score"] = 1
    accepted_legacy = IntakeInitiatorRoomLlmOutputV3.model_validate(legacy)
    assert "total_score" not in accepted_legacy.ordered_sections[9].value.model_dump()


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


def test_initiator_context_exposes_only_the_initiator_persisted_position() -> None:
    case_id = "CASE_INITIATOR_CONTEXT_ISOLATION"
    initial_request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "EXTERNAL_IMPORT",
            "initial_case_facts": {
                "form_source": "EXTERNAL_IMPORT",
                "form_description": "用户称订单未发货并要求取消。",
                "order_reference": "ORDER_CONTEXT_ISOLATION",
                "initiator_role": "USER",
                "respondent_attitude_seed": {
                    "respondent_role": "MERCHANT",
                    "attitude": "AGREE",
                    "position": "用户转述商家已经同意取消。",
                    "source": "发起方单方陈述（主观）",
                    "confidence": 0.8,
                },
            },
            "agent_context": _agent_context(case_id=case_id, role="USER"),
        }
    )
    initial_sections = {
        section.name: json.loads(section.content)
        for section in build_intake_turn_context_pack(
            initial_request
        ).prompt_sections()
    }
    assert "respondent_attitude_seed" not in initial_sections["initial_case_facts"]

    previous_request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_INITIATOR_CONTEXT_ISOLATION",
                "sequence_no": 3,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "我要求取消订单并退款。",
            },
            "previous_case_detail": {
                "schema_version": "intake_case_detail.v1",
                "party_positions": {
                    "user_claim": "用户要求取消订单并退款。",
                    "merchant_claim": "用户转述商家已经同意取消。",
                    "initiator_position": "用户要求取消订单并退款。",
                    "respondent_position": "用户转述商家已经同意取消。",
                    "platform_observation": "平台仅整理当前方陈述。",
                },
                "respondent_attitude": {
                    "respondent_role": "MERCHANT",
                    "attitude": "AGREE",
                    "position": "用户转述商家已经同意取消。",
                    "source": "发起方单方陈述（主观）",
                    "confidence": 0.8,
                },
            },
            "agent_context": _agent_context(case_id=case_id, role="USER"),
        }
    )

    sections = {
        section.name: json.loads(section.content)
        for section in build_intake_turn_context_pack(
            previous_request
        ).prompt_sections()
    }

    previous = sections["previous_dispute_outline"]
    assert "respondent_attitude" not in previous
    assert previous["party_positions"] == {
        "user_claim": "用户要求取消订单并退款。",
        "initiator_position": "用户要求取消订单并退款。",
        "platform_observation": "平台仅整理当前方陈述。",
    }


def test_respondent_context_uses_frozen_initiator_position_not_reported_opponent_claim() -> None:
    case_id = "CASE_RESPONDENT_CONTEXT_ISOLATION"
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_RESPONDENT_CONTEXT_ISOLATION",
                "sequence_no": 2,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": "我方确认订单尚未发货并同意取消。",
            },
            "previous_case_detail": {
                "schema_version": "intake_case_detail.v1",
                "case_fact_matrix": {
                    "schema_version": "case_fact_matrix.v2",
                    "matrix_id": "MATRIX_CONTEXT_ISOLATION",
                    "matrix_version": 1,
                    "matrix_kind": "WORKING",
                    "party_map": {
                        "initiator_role": "USER",
                        "respondent_role": "MERCHANT",
                    },
                    "claims": {
                        "initiator_claim": {
                            "initiator_role": "USER",
                            "requested_resolution": "CANCEL_ORDER",
                            "reason_summary": "用户称订单尚未发货。",
                            "position_summary": "用户要求取消订单。",
                        },
                        "respondent_reported_by_initiator": {
                            "respondent_role": "MERCHANT",
                            "attitude": "AGREE",
                            "position_summary": "用户转述商家同意取消。",
                            "source_type": "INITIATOR_REPORTED",
                        },
                    },
                    "fact_rows": [],
                },
            },
            "agent_context": _agent_context(case_id=case_id, role="MERCHANT"),
        }
    )

    sections = {
        section.name: json.loads(section.content)
        for section in build_intake_turn_context_pack(request).prompt_sections()
    }
    claims = sections["frozen_case_matrix"]["claims"]

    assert claims["initiator_claim"]["position_summary"] == "用户要求取消订单。"
    assert "respondent_reported_by_initiator" not in claims


def test_v3_projection_derives_total_from_the_six_components() -> None:
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
    # The provider owns only the six components.  The public and durable total
    # is their deterministic sum.
    payload["ordered_sections"][9]["value"]["score_breakdown"]["references"] = 11
    output = IntakeInitiatorRoomLlmOutputV3.model_validate(payload)
    materialized = materialize_intake_case_detail_output(request, output)

    projected = project_intake_case_detail_output(
        request=request,
        output=materialized,
        source_text="用户称订单未在承诺时间内送达并要求退款。",
    )

    quality = projected["scroll_snapshot"]["intake_quality"]
    assert quality["score"] == 51
    assert quality["score_breakdown"] == output.ordered_sections[-1].value.score_breakdown.model_dump()
    assert quality["improvement_reason"] == "仍需补充异常发现时间。"
    assert projected["missing_fields"] == ["异常发现时间"]
    assert projected["scroll_snapshot"]["party_positions"] == {
        "user_claim": "用户要求退款。",
        "merchant_claim": "尚未直接陈述",
        "raw_statement": "",
        "platform_observation": "目前仅有用户单方陈述。",
        "initiator_position": "用户要求退款。",
        "respondent_position": "尚未直接陈述",
    }
    assert projected["scroll_snapshot"]["respondent_attitude"] == {
        "respondent_role": "MERCHANT",
        "attitude": "NOT_RESPONDED",
        "position": "尚未直接陈述",
        "source": "尚未回应",
        "confidence": 0.5,
    }


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
    rebound_detail = copy.deepcopy(model_detail)
    dossier_skill._bind_model_trusted_respondent_attitude(
        rebound_detail,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        wrong_binding,
    )
    assert rebound_detail["respondent_attitude"] == detail["respondent_attitude"]

    no_binding_detail = copy.deepcopy(model_detail)
    dossier_skill._bind_model_trusted_respondent_attitude(
        no_binding_detail,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        None,
    )
    assert no_binding_detail["respondent_attitude"] == detail["respondent_attitude"]

    invalid_matrix = matrix.model_dump(mode="json")
    invalid_matrix["respondent_claim"]["attitude"] = "UNRECOGNIZED"
    with pytest.raises(ValueError):
        CaseFactMatrixDeltaV2.model_validate(invalid_matrix)


def test_v3_direct_binding_ignores_model_source_binding_and_uses_current_message() -> None:
    case_id = "CASE_ORDERED_ROOM_LINK_SUBSET"
    current_text = (
        "现阶段不同意直接退货退款，但同意核验宣传参数依据和检测方法。"
    )
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_ORDERED_LINK_SUBSET",
                "sequence_no": 6,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": current_text,
            },
            "previous_case_detail": {
                "schema_version": "intake_case_detail.v1",
                "claim_resolution": {
                    "initiator_role": "USER",
                    "requested_resolution": "RETURN_REFUND",
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
    matrix = CaseFactMatrixDeltaV2.model_validate(
        {
            "schema_version": "case_fact_matrix.delta.v2",
            "fact_rows": [
                {
                    "fact_key": "FACT_CURRENT_SPEC",
                    "category": "PRODUCT_PAGE",
                    "fact_target": "宣传参数是否有依据",
                    "materiality": "CORE",
                    "stance": "PARTIAL",
                    "position_summary": "商家同意核验宣传参数依据。",
                    "asserted_value": "同意核验",
                    "source_scope": "PREVIOUS_AND_CURRENT_SOURCE",
                },
                {
                    "fact_key": "FACT_CURRENT_METHOD",
                    "category": "PRODUCT_STATE",
                    "fact_target": "检测方法是否需要核验",
                    "materiality": "CORE",
                    "stance": "CONFIRM",
                    "position_summary": "商家同意核验检测方法。",
                    "asserted_value": "同意核验",
                    "source_scope": "CURRENT_SOURCE",
                },
                {
                    "fact_key": "FACT_PREVIOUS_REFUND",
                    "category": "AFTER_SALES",
                    "fact_target": "商家是否同意直接退货退款",
                    "materiality": "CORE",
                    "stance": "NOT_ADDRESSED",
                    "position_summary": "本轮未就历史退款事实形成新陈述。",
                    "asserted_value": None,
                    "source_scope": "PREVIOUS_MATRIX",
                },
            ],
            "summary_source_fact_keys": [
                "FACT_CURRENT_SPEC",
                "FACT_CURRENT_METHOD",
            ],
            "respondent_claim": {
                "attitude": "DISAGREE",
                "position_summary": "商家现阶段不同意直接退货退款，但同意核验依据和方法。",
                "alternative_proposal": "先核验宣传参数依据和检测方法",
            },
        }
    )
    model_detail = {
        "respondent_attitude": {
            "respondent_role": "MERCHANT",
            "attitude": "DISAGREE",
            "position": "商家现阶段不同意直接退货退款，但同意核验依据和方法。",
            "alternative_proposal": "先核验宣传参数依据和检测方法",
        }
    }
    binding = {
        "schema_version": "respondent-claim-binding.v1",
        "binding_kind": "CURRENT_ACTOR_DIRECT",
        "subject_role": "MERCHANT",
        "source_quote": "现阶段不同意直接退货退款，但同意核验宣传参数依据和检测方法",
        "linked_fact_keys": [
            "FACT_CURRENT_SPEC",
            "FACT_CURRENT_METHOD",
            "FACT_PREVIOUS_REFUND",
        ],
    }

    first = copy.deepcopy(model_detail)
    dossier_skill._bind_model_trusted_respondent_attitude(
        first,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        copy.deepcopy(binding),
    )
    replay = copy.deepcopy(model_detail)
    dossier_skill._bind_model_trusted_respondent_attitude(
        replay,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        copy.deepcopy(binding),
    )

    assert first["respondent_attitude"]["attitude"] == "DISAGREE"
    assert first["respondent_attitude"]["grounding"] == {
        "source": "RESPONDENT_PARTICIPANT_MESSAGE",
        "message_id": "MESSAGE_ORDERED_LINK_SUBSET",
    }
    assert replay == first

    unknown_binding = copy.deepcopy(binding)
    unknown_binding["linked_fact_keys"][-1] = "FACT_UNKNOWN"
    unknown_detail = copy.deepcopy(model_detail)
    dossier_skill._bind_model_trusted_respondent_attitude(
        unknown_detail,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        unknown_binding,
    )
    assert unknown_detail["respondent_attitude"] == first["respondent_attitude"]

    historical_only_binding = copy.deepcopy(binding)
    historical_only_binding["linked_fact_keys"] = ["FACT_PREVIOUS_REFUND"]
    historical_detail = copy.deepcopy(model_detail)
    dossier_skill._bind_model_trusted_respondent_attitude(
        historical_detail,
        request,
        copy.deepcopy(request.previous_case_detail or {}),
        copy.deepcopy(model_detail),
        matrix,
        historical_only_binding,
    )
    assert historical_detail["respondent_attitude"] == first["respondent_attitude"]


def test_respondent_turn_generates_only_own_view_and_server_copies_frozen_claim() -> None:
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
        "party_positions": {
            "initiator_position": "用户主张商品实际性能与宣传不符并要求退货退款。",
            "user_claim": "用户主张商品实际性能与宣传不符并要求退货退款。",
        },
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
    provider_payload = _respondent_v3_payload()
    provider_payload["ordered_sections"][2]["value"]["respondent_position"] = (
        "商家同意标准复检，复检不达标时同意退货退款。"
    )
    matrix = provider_payload["ordered_sections"][0]["value"]
    matrix["fact_rows"][0].update(
        {
            "fact_target": "商家是否提出标准复检方案",
            "position_summary": "商家同意标准复检，复检不达标时同意退货退款。",
            "asserted_value": "标准复检",
        }
    )
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
    assert len(respondent_schema["properties"]["ordered_sections"]["anyOf"]) == 5
    assert "claim_resolution" not in json.dumps(respondent_schema, sort_keys=True)
    assert "initiator_position" not in json.dumps(respondent_schema, sort_keys=True)
    assert "INITIATOR_REPORTED" not in json.dumps(respondent_schema, sort_keys=True)

    injected_opponent_view = copy.deepcopy(provider_payload)
    injected_opponent_view["ordered_sections"][3]["value"]["claim_resolution"] = (
        copy.deepcopy(frozen_claim)
    )
    with pytest.raises(ValidationError):
        respondent_output_type.model_validate(injected_opponent_view)
    validated_provider_output = respondent_output_type.model_validate(provider_payload)
    materialized_provider_output = materialize_intake_case_detail_output(
        respondent_request,
        validated_provider_output,
    )
    assert materialized_provider_output.case_detail["claim_resolution"] == frozen_claim
    assert materialized_provider_output.case_detail["party_positions"] == {
        "user_claim": "用户主张商品实际性能与宣传不符并要求退货退款。",
        "merchant_claim": "商家同意标准复检，复检不达标时同意退货退款。",
        "initiator_position": "用户主张商品实际性能与宣传不符并要求退货退款。",
        "respondent_position": "商家同意标准复检，复检不达标时同意退货退款。",
        "platform_observation": "平台仅整理当前被发起方的直接陈述。",
    }

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
        "position": "尚未直接陈述",
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
                    "requested_amount": None,
                    "requested_items": None,
                    "request_reason": "订单未按时送达。",
                    "normalized_statement": "用户要求退款。",
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
    provider_payload = _respondent_v3_payload()
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
