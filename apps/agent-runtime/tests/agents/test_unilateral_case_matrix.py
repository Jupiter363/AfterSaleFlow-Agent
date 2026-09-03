from __future__ import annotations

import hashlib
import json

import pytest

from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
)
from app.schemas import IntakeTurnRequest, UnilateralCaseMatrixDraftV1


def _agent_context(case_id: str) -> dict[str, object]:
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "INTAKE",
        "actor_id": "user-local",
        "actor_role": "USER",
        "access_session_id": "ACCESS_UNILATERAL",
        "permission_level": "PARTY_USER",
        "permission_scopes": ["ROOM_READ", "ROOM_WRITE"],
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_invocation_id": "INVOCATION_UNILATERAL",
        "agent_session_id": "SESSION_UNILATERAL",
        "conversation_scope": f"default:{case_id}:INTAKE:user-local",
        "scope_type": "INTAKE_INITIATOR_PRIVATE",
        "allowed_actor_ids": ["user-local"],
        "allowed_actor_roles": ["USER"],
        "prompt_profile_id": "DISPUTE_INTAKE_OFFICER:USER:v1",
        "memory_policy_id": "MEMORY_POLICY_INTAKE_PRIVATE_V1",
    }


def _initial_request() -> IntakeTurnRequest:
    case_id = "CASE_unilateral_matrix"
    return IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": (
                    "用户称电视页面包含基础安装，但上门人员另收150元。"
                ),
                "order_reference": "ORDER_UNILATERAL",
                "logistics_reference": "LOG_UNILATERAL",
                "initiator_role": "USER",
                "claim_resolution_seed": {
                    "initiator_role": "USER",
                    "requested_resolution": "REFUND",
                    "requested_amount": 150,
                    "request_reason": "页面标注包含基础安装。",
                },
            },
            "agent_context": _agent_context(case_id),
        }
    )


def _detail(summary: str) -> dict[str, object]:
    return {
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "title": "电视安装额外收费争议",
            "one_sentence_summary": summary,
        },
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
            "requested_amount": 150,
            "requested_items": "电视安装服务",
            "request_reason": "页面标注包含基础安装。",
            "normalized_statement": "用户要求退还150元安装费。",
        },
        "respondent_attitude": {
            "respondent_role": "MERCHANT",
            "attitude": "DISAGREE",
            "position": "用户转述商家称现场复杂，应当额外收费。",
            "source": "发起方单方陈述（主观）",
            "confidence": 0.8,
        },
        "dispute_core_state": {
            "core_conflict": "150元是否属于页面承诺的基础安装范围。",
            "facts_in_dispute": ["额外收费是否提前说明"],
            "next_verification_focus": ["核验基础安装范围"],
        },
        "missing_information": {
            "blocking_gaps": [],
            "nice_to_have_gaps": [],
            "next_questions": [],
        },
        "intake_quality": {"score": 90},
        "admission": {"recommendation": "ACCEPTED", "confidence": 0.9},
    }


def _draft(*rows, summary_refs: list[str]) -> UnilateralCaseMatrixDraftV1:
    return UnilateralCaseMatrixDraftV1.model_validate(
        {
            "fact_rows": list(rows),
            "summary_source_fact_keys": summary_refs,
        }
    )


def test_legacy_unilateral_draft_upgrades_to_unified_matrix_with_stable_fact_ids() -> None:
    initial = _initial_request()
    first = CaseDetailDossierSkill().render(
        request=initial,
        room_utterance="请补充收费发生的时间。",
        llm_case_detail=_detail(
            "用户称电视页面包含基础安装，但上门人员另收150元，用户要求退还。"
        ),
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="ACCEPTED",
        llm_missing_fields=[],
        llm_confidence=0.9,
        llm_unilateral_case_matrix=_draft(
            {
                "fact_key": "NEW_INSTALL_SCOPE",
                "category": "PRODUCT_PAGE",
                "fact_target": "商品页面是否标注包含基础安装",
                "materiality": "CORE",
                "position_summary": "用户称商品页面标注包含基础安装。",
                "asserted_value": "包含基础安装",
                "source_scope": "CURRENT_SOURCE",
            },
            summary_refs=["NEW_INSTALL_SCOPE"],
        ),
    )
    first_matrix = first.scroll_snapshot["case_fact_matrix"]
    first_fact_id = first_matrix["fact_rows"][0]["fact_id"]

    follow_up = IntakeTurnRequest.model_validate(
        {
            "case_id": initial.case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_FEE_TIME",
                "sequence_no": 2,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "7月12日早上收费，我通过支付宝支付150元。",
            },
            "recent_dialogue_messages": [
                {
                    "message_id": "MESSAGE_AGENT_QUESTION",
                    "sequence_no": 1,
                    "role": "AGENT",
                    "source": "AGENT_RESPONSE",
                    "text": "请补充收费发生的时间和支付方式。",
                }
            ],
            "previous_case_detail": first.scroll_snapshot,
            "agent_context": _agent_context(initial.case_id),
        }
    )
    second = CaseDetailDossierSkill().render(
        request=follow_up,
        room_utterance="已记录收费时间和支付方式。",
        llm_case_detail=_detail(
            "用户称电视页面包含基础安装，7月12日上门人员另收150元，用户通过支付宝付款并要求退还。"
        ),
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="ACCEPTED",
        llm_missing_fields=[],
        llm_confidence=0.9,
        llm_unilateral_case_matrix=_draft(
            {
                "fact_key": first_fact_id,
                "category": "PRODUCT_PAGE",
                "fact_target": "商品页面是否标注包含基础安装",
                "materiality": "CORE",
                "position_summary": "用户称商品页面标注包含基础安装。",
                "asserted_value": "包含基础安装",
                "source_scope": "PREVIOUS_MATRIX",
            },
            {
                "fact_key": "NEW_PAYMENT",
                "category": "PAYMENT",
                "fact_target": "用户是否于7月12日通过支付宝支付150元安装费",
                "materiality": "CORE",
                "position_summary": "用户称其于7月12日通过支付宝支付150元。",
                "asserted_value": "7月12日支付宝支付150元",
                "source_scope": "CURRENT_SOURCE",
            },
            summary_refs=[first_fact_id, "NEW_PAYMENT"],
        ),
    )
    second_matrix = second.scroll_snapshot["case_fact_matrix"]

    assert second_matrix["matrix_version"] == first_matrix["matrix_version"] + 1
    assert second_matrix["fact_rows"][0]["fact_id"] == first_fact_id
    assert second_matrix["fact_rows"][0]["truth_status"] == "NOT_EVALUATED"
    assert second_matrix["fact_rows"][0]["origin"]["source_refs"] == [
        f"INTAKE_FORM_{initial.case_id}"
    ]
    assert second_matrix["fact_rows"][1]["origin"]["source_refs"] == [
        "MESSAGE_FEE_TIME"
    ]
    assert second_matrix["case_overview"]["summary_source_fact_ids"] == [
        row["fact_id"] for row in second_matrix["fact_rows"]
    ]
    assert len(second_matrix["content_hash"]) == 64
    hash_input = dict(second_matrix)
    content_hash = hash_input.pop("content_hash")
    assert content_hash == hashlib.sha256(
        json.dumps(
            hash_input,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    assert second.dossier_patch["case_detail"]["case_fact_matrix"] == second_matrix
    assert "unilateral_case_matrix" not in second.dossier_patch["case_detail"]
    assert second_matrix["claims"]["initiator_claim"]["source_refs"] == [
        f"INTAKE_FORM_{initial.case_id}"
    ]


def test_intake_model_output_requires_a_case_matrix_delta() -> None:
    with pytest.raises(ValueError, match="case_matrix_delta is required"):
        IntakeCaseDetailLlmOutput.model_validate(
            {
                "room_utterance": "请继续补充案情。",
                "case_detail": {
                    "case_story": {"one_sentence_summary": "用户提交履约争议。"}
                },
            }
        )
