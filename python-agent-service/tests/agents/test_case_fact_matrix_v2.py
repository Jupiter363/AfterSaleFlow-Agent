from __future__ import annotations

import hashlib
import json
from copy import deepcopy

import pytest

from app.agents.dispute_intake_officer.case_fact_matrix import (
    finalize_case_fact_matrix,
)
from app.llm import AgentOutputSchemaError
from app.schemas import CaseFactMatrixDeltaV2, IntakeTurnRequest
from app.harness.evidence_context_assembler import (
    _allowed_fact_targets,
    _claim_and_response_state,
)


def test_delta_can_carry_an_existing_unaddressed_fact_without_new_source() -> None:
    carried = CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": "FACT_INTAKE_EXISTING",
                    "category": "FULFILLMENT",
                    "fact_target": "商家是否完成约定服务",
                    "materiality": "CORE",
                    "stance": "NOT_ADDRESSED",
                    "position_summary": "被发起方本轮尚未直接回应该事实。",
                    "asserted_value": None,
                    "source_scope": "PREVIOUS_MATRIX",
                }
            ],
            "summary_source_fact_keys": ["FACT_INTAKE_EXISTING"],
            "respondent_claim": {
                "attitude": "NEED_MORE_INFO",
                "position_summary": "被发起方需要进一步说明案情。",
            },
        }
    )

    assert carried.fact_rows[0].stance == "NOT_ADDRESSED"

    invalid_rows = (
        {
            "fact_key": "NEW_UNADDRESSED",
            "source_scope": "PREVIOUS_MATRIX",
            "asserted_value": None,
        },
        {
            "fact_key": "FACT_INTAKE_EXISTING",
            "source_scope": "CURRENT_SOURCE",
            "asserted_value": None,
        },
        {
            "fact_key": "FACT_INTAKE_EXISTING",
            "source_scope": "PREVIOUS_MATRIX",
            "asserted_value": "擅自补值",
        },
    )
    for invalid in invalid_rows:
        with pytest.raises(ValueError):
            CaseFactMatrixDeltaV2.model_validate(
                {
                    "fact_rows": [
                        {
                            "category": "FULFILLMENT",
                            "fact_target": "商家是否完成约定服务",
                            "materiality": "CORE",
                            "stance": "NOT_ADDRESSED",
                            "position_summary": "被发起方本轮尚未直接回应该事实。",
                            **invalid,
                        }
                    ],
                    "summary_source_fact_keys": [invalid["fact_key"]],
                }
            )


def _context(case_id: str, role: str, actor_id: str) -> dict[str, object]:
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "INTAKE",
        "actor_id": actor_id,
        "actor_role": role,
        "access_session_id": f"ACCESS_{role}",
        "permission_level": f"PARTY_{role}",
        "permission_scopes": ["ROOM_READ", "ROOM_WRITE"],
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_invocation_id": f"INVOCATION_{role}",
        "agent_session_id": f"SESSION_{role}",
        "conversation_scope": f"default:{case_id}:INTAKE:{actor_id}",
        "scope_type": "INTAKE_PARTY_PRIVATE",
        "allowed_actor_ids": [actor_id],
        "allowed_actor_roles": [role],
        "prompt_profile_id": f"DISPUTE_INTAKE_OFFICER:{role}:v1",
        "memory_policy_id": "MEMORY_POLICY_INTAKE_PRIVATE_V1",
    }


def _detail(summary: str) -> dict[str, object]:
    return {
        "case_story": {"one_sentence_summary": summary},
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
            "requested_amount": 150,
            "request_reason": "页面承诺包含基础安装。",
            "normalized_statement": "用户要求退还150元安装费。",
        },
        "dispute_core_state": {
            "core_conflict": "150元是否属于页面承诺的基础安装范围。"
        },
    }


def _single_fact_initiator_matrix(case_id: str):
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": "用户称商品页面包含基础安装。",
                "order_reference": f"ORDER_{case_id}",
                "initiator_role": "USER",
            },
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    return finalize_case_fact_matrix(
        request=request,
        case_detail=_detail("用户称商品页面包含基础安装。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_INSTALL_SCOPE",
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "用户称页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_INSTALL_SCOPE"],
            }
        ),
    )


def _respondent_request(case_id: str, previous_matrix: dict[str, object]):
    return IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_MERCHANT_FACT_ID_CORRECTION",
                "sequence_no": 2,
                "role": "MERCHANT",
                "source": "ROOM_MESSAGE",
                "text": "商家确认商品页面标注包含基础安装。",
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": {"case_fact_matrix": previous_matrix},
            "agent_context": _context(case_id, "MERCHANT", "merchant-local"),
        }
    )


def _existing_fact_delta(
    fact_key: str,
    *,
    fact_target: str = "商品页面是否标注包含基础安装",
):
    return CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": fact_key,
                    "category": "PRODUCT_PAGE",
                    "fact_target": fact_target,
                    "materiality": "CORE",
                    "stance": "CONFIRM",
                    "position_summary": "商家确认页面标注包含基础安装。",
                    "asserted_value": "包含基础安装",
                    "source_scope": "CURRENT_SOURCE",
                }
            ],
            "summary_source_fact_keys": [fact_key],
        }
    )


def _mistyped_fact_id(fact_id: str) -> str:
    replacement = "A" if fact_id[-1] != "A" else "B"
    return fact_id[:-1] + replacement


def test_unknown_fact_id_is_rebound_by_one_exact_normalized_fingerprint() -> None:
    case_id = "CASE_fact_id_rebind"
    previous = _single_fact_initiator_matrix(case_id)
    real_fact_id = previous.fact_rows[0].fact_id
    mistyped_fact_id = _mistyped_fact_id(real_fact_id)
    request = _respondent_request(case_id, previous.model_dump(mode="json"))
    delta = _existing_fact_delta(
        mistyped_fact_id,
        fact_target=" 商品页面 是否标注包含基础安装 ",
    )

    corrected = finalize_case_fact_matrix(
        request=request,
        case_detail=_detail("双方确认商品页面标注包含基础安装。"),
        delta=delta,
    )
    repeated = finalize_case_fact_matrix(
        request=request,
        case_detail=_detail("双方确认商品页面标注包含基础安装。"),
        delta=delta,
    )
    canonical = finalize_case_fact_matrix(
        request=request,
        case_detail=_detail("双方确认商品页面标注包含基础安装。"),
        delta=_existing_fact_delta(
            real_fact_id,
            fact_target=" 商品页面 是否标注包含基础安装 ",
        ),
    )

    assert corrected.fact_rows[0].fact_id == real_fact_id
    assert corrected.case_overview.summary_source_fact_ids == [real_fact_id]
    assert corrected.content_hash == repeated.content_hash
    assert corrected.content_hash == canonical.content_hash
    serialized = corrected.model_dump(mode="json")
    content_hash = serialized.pop("content_hash")
    assert content_hash == hashlib.sha256(
        json.dumps(
            serialized,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def test_unknown_fact_id_without_a_matching_fingerprint_still_fails_closed() -> None:
    case_id = "CASE_fact_id_no_match"
    previous = _single_fact_initiator_matrix(case_id)
    mistyped_fact_id = _mistyped_fact_id(previous.fact_rows[0].fact_id)

    with pytest.raises(AgentOutputSchemaError, match="references unknown fact"):
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, previous.model_dump(mode="json")),
            case_detail=_detail("商家补充了另一项事实。"),
            delta=_existing_fact_delta(
                mistyped_fact_id,
                fact_target="现场是否实施额外墙体加固服务",
            ),
        )


def test_unknown_fact_id_with_an_ambiguous_fingerprint_still_fails_closed() -> None:
    case_id = "CASE_fact_id_ambiguous"
    previous = _single_fact_initiator_matrix(case_id)
    payload = previous.model_dump(mode="json")
    duplicate = deepcopy(payload["fact_rows"][0])
    duplicate["fact_id"] = "FACT_INTAKE_DUPLICATE_FINGERPRINT"
    payload["fact_rows"].append(duplicate)
    payload["fact_indexes"]["not_computed_fact_ids"].append(duplicate["fact_id"])
    payload["fact_indexes"]["core_fact_ids"].append(duplicate["fact_id"])
    material = deepcopy(payload)
    material.pop("content_hash")
    payload["content_hash"] = hashlib.sha256(
        json.dumps(
            material,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    mistyped_fact_id = _mistyped_fact_id(previous.fact_rows[0].fact_id)

    with pytest.raises(AgentOutputSchemaError, match="cannot uniquely resolve"):
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, payload),
            case_detail=_detail("商家确认商品页面标注包含基础安装。"),
            delta=_existing_fact_delta(mistyped_fact_id),
        )


def test_unified_matrix_evolves_from_initiator_to_bilateral_without_changing_fact_ids() -> None:
    case_id = "CASE_matrix_v2"
    initial = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": "用户称页面包含基础安装，但现场另收150元。",
                "order_reference": "ORDER_MATRIX_V2",
                "initiator_role": "USER",
            },
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    initiator = finalize_case_fact_matrix(
        request=initial,
        case_detail=_detail("用户称页面包含基础安装，但现场另收150元。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_INSTALL_SCOPE",
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "用户称页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_INSTALL_SCOPE"],
            }
        ),
    )
    fact_id = initiator.fact_rows[0].fact_id
    assert initiator.schema_version == "case_fact_matrix.v2"
    assert initiator.matrix_kind == "INITIATOR_FROZEN"
    assert initiator.fact_rows[0].party_alignment.status == "NOT_COMPUTED"
    assert initiator.fact_rows[0].requires_resolution is None
    assert initiator.fact_indexes.not_computed_fact_ids == [fact_id]

    respondent = finalize_case_fact_matrix(
        request=IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": "MESSAGE_MERCHANT_1",
                    "sequence_no": 10,
                    "role": "MERCHANT",
                    "source": "ROOM_MESSAGE",
                    "text": "页面确实包含基础安装，但现场是额外墙体加固服务。",
                },
                "recent_dialogue_messages": [],
                "previous_case_detail": {
                    "case_fact_matrix": initiator.model_dump(mode="json")
                },
                "agent_context": _context(case_id, "MERCHANT", "merchant-local"),
            }
        ),
        case_detail=_detail(
            "用户主张页面包含基础安装并要求退费；商家确认页面说明，但称收费对应额外墙体加固。"
        ),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": fact_id,
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "商家确认页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "CURRENT_SOURCE",
                    },
                    {
                        "fact_key": "NEW_WALL_REINFORCEMENT",
                        "category": "FULFILLMENT",
                        "fact_target": "现场是否实施额外墙体加固服务",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "商家称现场实施了额外墙体加固。",
                        "asserted_value": "实施额外墙体加固",
                        "source_scope": "CURRENT_SOURCE",
                    },
                ],
                "summary_source_fact_keys": [fact_id, "NEW_WALL_REINFORCEMENT"],
                "respondent_claim": {
                    "attitude": "DISAGREE",
                    "position_summary": "商家不同意退还额外服务费。",
                },
            }
        ),
    )

    assert respondent.matrix_kind == "BILATERAL_FROZEN"
    assert respondent.parent_ref is not None
    assert respondent.parent_ref.content_hash == initiator.content_hash
    assert respondent.fact_rows[0].fact_id == fact_id
    assert respondent.fact_rows[0].party_alignment.status == "AGREED"
    assert respondent.fact_rows[0].requires_resolution is False
    assert respondent.fact_rows[1].party_alignment.status == "ONE_SIDED"
    assert respondent.fact_rows[1].requires_resolution is True
    assert respondent.claims.respondent_direct is not None
    serialized = respondent.model_dump(mode="json")
    targets = _allowed_fact_targets({"case_fact_matrix": serialized})
    claim_state = _claim_and_response_state({"case_fact_matrix": serialized})
    assert [target["fact_id"] for target in targets] == [
        row.fact_id for row in respondent.fact_rows
    ]
    assert claim_state["source"] == "CASE_FACT_MATRIX_V2"
    assert claim_state["respondent_direct"]["attitude"] == "DISAGREE"
    content_hash = serialized.pop("content_hash")
    assert content_hash == hashlib.sha256(
        json.dumps(
            serialized,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def test_respondent_fact_only_turn_does_not_invent_or_erase_a_claim() -> None:
    case_id = "CASE_respondent_fact_only"
    initiator_request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": "用户称页面包含基础安装。",
                "order_reference": "ORDER_RESPONDENT_FACT_ONLY",
                "initiator_role": "USER",
            },
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    initiator = finalize_case_fact_matrix(
        request=initiator_request,
        case_detail=_detail("用户称页面包含基础安装。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_INSTALL_SCOPE",
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "用户称页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_INSTALL_SCOPE"],
            }
        ),
    )
    fact_id = initiator.fact_rows[0].fact_id

    fact_only = finalize_case_fact_matrix(
        request=IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": "MESSAGE_MERCHANT_FACT_ONLY",
                    "sequence_no": 2,
                    "role": "MERCHANT",
                    "source": "ROOM_MESSAGE",
                    "text": "页面确实标注包含基础安装。",
                },
                "recent_dialogue_messages": [],
                "previous_case_detail": {
                    "case_fact_matrix": initiator.model_dump(mode="json")
                },
                "agent_context": _context(case_id, "MERCHANT", "merchant-local"),
            }
        ),
        case_detail=_detail("双方均确认页面标注包含基础安装。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": fact_id,
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "商家确认页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": [fact_id],
            }
        ),
    )

    assert fact_only.matrix_kind == "BILATERAL_FROZEN"
    assert fact_only.claims.respondent_direct is None
    assert fact_only.claims.claim_conflict is None

    claim_answer = finalize_case_fact_matrix(
        request=IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": "MESSAGE_MERCHANT_CLAIM",
                    "sequence_no": 4,
                    "role": "MERCHANT",
                    "source": "ROOM_MESSAGE",
                    "text": (
                        "我不接受，我认为他说的不合理，我看轨迹签收了应该以物流平台为准，"
                        "对‘物流轨迹显示订单已签收’这一事实我确认。"
                    ),
                },
                "recent_dialogue_messages": [],
                "previous_case_detail": {
                    "case_fact_matrix": fact_only.model_dump(mode="json")
                },
                "agent_context": _context(case_id, "MERCHANT", "merchant-local"),
            }
        ),
        case_detail=_detail("双方确认页面标注，但商家不同意退还安装费。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": fact_id,
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "商家确认页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "PREVIOUS_MATRIX",
                    }
                ],
                "summary_source_fact_keys": [fact_id],
            }
        ),
    )
    assert claim_answer.claims.respondent_direct is not None
    assert claim_answer.claims.respondent_direct.attitude == "DISAGREE"
    claim_refs = claim_answer.claims.respondent_direct.source_refs
    assert claim_refs == ["MESSAGE_MERCHANT_CLAIM"]

    carried = finalize_case_fact_matrix(
        request=IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": "MESSAGE_MERCHANT_LATER_FACT",
                    "sequence_no": 6,
                    "role": "MERCHANT",
                    "source": "ROOM_MESSAGE",
                    "text": "现场没有新增其他服务。",
                },
                "recent_dialogue_messages": [],
                "previous_case_detail": {
                    "case_fact_matrix": claim_answer.model_dump(mode="json")
                },
                "agent_context": _context(case_id, "MERCHANT", "merchant-local"),
            }
        ),
        case_detail=_detail("双方确认页面标注，商家补充现场没有其他服务。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": fact_id,
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "商家确认页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "PREVIOUS_MATRIX",
                    }
                ],
                "summary_source_fact_keys": [fact_id],
            }
        ),
    )

    assert carried.claims.respondent_direct is not None
    assert carried.claims.respondent_direct.attitude == "DISAGREE"
    assert carried.claims.respondent_direct.source_refs == claim_refs
    assert "MESSAGE_MERCHANT_LATER_FACT" not in carried.claims.respondent_direct.source_refs


def test_missing_delta_carries_the_prior_matrix_without_renumbering_facts() -> None:
    case_id = "CASE_matrix_carry"
    initial_request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": "用户称页面包含基础安装。",
                "order_reference": "ORDER_MATRIX_CARRY",
                "initiator_role": "USER",
            },
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    first = finalize_case_fact_matrix(
        request=initial_request,
        case_detail=_detail("用户称页面包含基础安装。"),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_INSTALL_SCOPE",
                        "category": "PRODUCT_PAGE",
                        "fact_target": "商品页面是否标注包含基础安装",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "用户称页面标注包含基础安装。",
                        "asserted_value": "包含基础安装",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_INSTALL_SCOPE"],
            }
        ),
    )
    follow_up_ref = "MESSAGE_USER_CARRY"
    second = finalize_case_fact_matrix(
        request=IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": follow_up_ref,
                    "sequence_no": 2,
                    "role": "USER",
                    "source": "ROOM_MESSAGE",
                    "text": "补充内容只更新案情叙述。",
                },
                "previous_case_detail": {
                    "case_fact_matrix": first.model_dump(mode="json")
                },
                "agent_context": _context(case_id, "USER", "user-local"),
            }
        ),
        case_detail=_detail("用户称页面包含基础安装。"),
        delta=None,
    )

    assert second.matrix_version == first.matrix_version + 1
    assert second.parent_ref is not None
    assert second.parent_ref.content_hash == first.content_hash
    assert [row.fact_id for row in second.fact_rows] == [
        row.fact_id for row in first.fact_rows
    ]
    assert follow_up_ref in second.source_refs
