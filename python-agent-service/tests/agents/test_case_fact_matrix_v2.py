from __future__ import annotations

import hashlib
import json
from copy import deepcopy

import pytest

from app.agents.dispute_intake_officer.case_fact_matrix import (
    finalize_case_fact_matrix,
    validate_case_fact_matrix_content_hash,
)
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    DIRECT_RESPONDENT_SOURCE,
    SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    SUBJECTIVE_RESPONDENT_SOURCE,
)
from app.contracts.v1.codec import canonical_sha256_omitting
from app.llm import AgentOutputSchemaError
from app.schemas import CaseFactMatrixDeltaV2, CaseFactMatrixV2, IntakeTurnRequest
from app.harness.evidence_context_assembler import (
    _allowed_fact_targets,
    _claim_and_response_state,
)


@pytest.mark.parametrize(
    ("kwargs", "expected"),
    [
        pytest.param({}, "AGENT_OUTPUT_SCHEMA_INVALID", id="default"),
        pytest.param(
            {"safe_code": "INTAKE_MATRIX_FACT_UNKNOWN"},
            "INTAKE_MATRIX_FACT_UNKNOWN",
            id="supplied",
        ),
    ],
)
def test_agent_output_schema_error_exposes_stable_safe_code(
    kwargs: dict[str, str],
    expected: str,
) -> None:
    error = AgentOutputSchemaError(
        "intake_turn_case_detail",
        "internal schema detail",
        **kwargs,
    )

    assert error.node_name == "intake_turn_case_detail"
    assert error.safe_code == expected
    assert str(error) == "internal schema detail"


def test_delta_requires_explicit_stance() -> None:
    with pytest.raises(ValueError, match="stance"):
        CaseFactMatrixDeltaV2.model_validate(
            {
                "schema_version": "case_fact_matrix.delta.v2",
                "fact_rows": [
                    {
                        "fact_key": "NEW_MISSING_STANCE",
                        "category": "ORDER",
                        "fact_target": "Whether the order exists.",
                        "materiality": "CORE",
                        "position_summary": "The actor stated a position.",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_MISSING_STANCE"],
            }
        )


def test_delta_rejects_new_fact_sourced_only_from_previous_matrix() -> None:
    with pytest.raises(ValueError, match="cannot come from PREVIOUS_MATRIX"):
        CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_PREVIOUS_ONLY",
                        "category": "ORDER",
                        "fact_target": "Whether the order exists.",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "The actor confirmed the order.",
                        "source_scope": "PREVIOUS_MATRIX",
                    }
                ],
                "summary_source_fact_keys": ["NEW_PREVIOUS_ONLY"],
            }
        )


def test_delta_allows_new_fact_with_previous_and_current_source() -> None:
    delta = CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": "NEW_MIXED_SOURCE",
                    "category": "ORDER",
                    "fact_target": "Whether the order exists.",
                    "materiality": "CORE",
                    "stance": "CONFIRM",
                    "position_summary": "The current source confirms the prior context.",
                    "source_scope": "PREVIOUS_AND_CURRENT_SOURCE",
                }
            ],
            "summary_source_fact_keys": ["NEW_MIXED_SOURCE"],
        }
    )

    assert delta.fact_rows[0].source_scope == "PREVIOUS_AND_CURRENT_SOURCE"


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


def _initiator_follow_up_request(
    case_id: str,
    previous_matrix: dict[str, object],
) -> IntakeTurnRequest:
    return IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": "MESSAGE_USER_SAFE_CODE_FOLLOW_UP",
                "sequence_no": 3,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "A bounded synthetic follow-up for schema-code coverage.",
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": {"case_fact_matrix": previous_matrix},
            "agent_context": _context(case_id, "USER", "user-local"),
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


def test_delta_normalizes_recoverable_provider_key_cardinality_without_merging_conflicts() -> (
    None
):
    first_row = {
        "fact_key": "NEW_PROVIDER_ORDER",
        "category": "ORDER",
        "fact_target": "Whether the order exists.",
        "materiality": "CORE",
        "stance": "CONFIRM",
        "position_summary": "The current source confirms the order.",
        "asserted_value": "confirmed",
        "source_scope": "CURRENT_SOURCE",
    }
    second_row = {
        "fact_key": "NEW_PROVIDER_DELIVERY",
        "category": "FULFILLMENT",
        "fact_target": "Whether delivery was completed.",
        "materiality": "CORE",
        "stance": "DENY",
        "position_summary": "The current source disputes completed delivery.",
        "asserted_value": "not delivered",
        "source_scope": "CURRENT_SOURCE",
    }
    recoverable_payload = {
        "fact_rows": [deepcopy(first_row), deepcopy(second_row), deepcopy(first_row)],
        "summary_source_fact_keys": [
            "NEW_PROVIDER_DELIVERY",
            "NEW_PROVIDER_DANGLING",
            "NEW_PROVIDER_ORDER",
            "NEW_PROVIDER_DELIVERY",
        ],
    }
    original_payload = deepcopy(recoverable_payload)

    normalized = CaseFactMatrixDeltaV2.model_validate(recoverable_payload)

    assert [row.fact_key for row in normalized.fact_rows] == [
        "NEW_PROVIDER_ORDER",
        "NEW_PROVIDER_DELIVERY",
    ]
    assert normalized.summary_source_fact_keys == [
        "NEW_PROVIDER_DELIVERY",
        "NEW_PROVIDER_ORDER",
    ]
    assert recoverable_payload == original_payload

    conflicting_row = deepcopy(first_row)
    conflicting_row["position_summary"] = "A conflicting provider position."
    with pytest.raises(ValueError, match="case fact delta keys must be unique"):
        CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [first_row, conflicting_row],
                "summary_source_fact_keys": [first_row["fact_key"]],
            }
        )

    with pytest.raises(
        ValueError,
        match="summary_source_fact_keys must reference at least one delta fact",
    ):
        CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [first_row],
                "summary_source_fact_keys": ["NEW_PROVIDER_DANGLING"],
            }
        )


def _java_jcs_previous_matrix(case_id: str, *, requested_amount: int | None):
    previous = _single_fact_initiator_matrix(case_id).model_dump(mode="json")
    initiator_claim = previous["claims"]["initiator_claim"]
    initiator_claim.pop("requested_items")
    if requested_amount is None:
        initiator_claim.pop("requested_amount")
    else:
        initiator_claim["requested_amount"] = requested_amount
    previous["content_hash"] = canonical_sha256_omitting(previous, "content_hash")
    return previous


@pytest.mark.parametrize(
    ("case_id", "requested_amount"),
    [
        ("CASE_java_jcs_integer_amount", 2399),
        ("CASE_java_jcs_omitted_amount", None),
    ],
)
def test_previous_java_jcs_matrix_with_omitted_claim_optionals_generates_successor(
    case_id: str,
    requested_amount: int | None,
) -> None:
    previous = _java_jcs_previous_matrix(
        case_id,
        requested_amount=requested_amount,
    )
    parsed = CaseFactMatrixV2.model_validate(previous)
    assert validate_case_fact_matrix_content_hash(previous)
    assert not validate_case_fact_matrix_content_hash(parsed.model_dump(mode="json"))
    initiator_claim = previous["claims"]["initiator_claim"]
    assert "requested_items" not in initiator_claim
    if requested_amount is None:
        assert "requested_amount" not in initiator_claim
    else:
        assert initiator_claim["requested_amount"] == 2399
        assert type(initiator_claim["requested_amount"]) is int

    successor = finalize_case_fact_matrix(
        request=_respondent_request(case_id, previous),
        case_detail=_detail("The respondent confirms the prior fact."),
        delta=_existing_fact_delta(previous["fact_rows"][0]["fact_id"]),
    )

    assert successor.matrix_version == 2
    assert successor.parent_ref is not None
    assert successor.parent_ref.content_hash == previous["content_hash"]


def test_previous_java_jcs_matrix_rejects_raw_content_tampering() -> None:
    case_id = "CASE_java_jcs_tampered"
    previous = _java_jcs_previous_matrix(case_id, requested_amount=2399)
    previous["claims"]["initiator_claim"]["requested_resolution"] = "EXCHANGE"

    with pytest.raises(
        AgentOutputSchemaError,
        match="content hash is invalid",
    ) as failure:
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, previous),
            case_detail=_detail("The respondent confirms the prior fact."),
            delta=_existing_fact_delta(previous["fact_rows"][0]["fact_id"]),
        )

    assert failure.value.safe_code == "INTAKE_MATRIX_PREVIOUS_HASH_INVALID"


def test_reducer_allows_new_fact_with_mixed_scope_using_only_current_source() -> None:
    case_id = "CASE_new_mixed_source"
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": "The current form confirms the order exists.",
                "order_reference": "ORDER_NEW_MIXED_SOURCE",
                "initiator_role": "USER",
            },
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    matrix = finalize_case_fact_matrix(
        request=request,
        case_detail=_detail("The current form confirms the order exists."),
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_ORDER_EXISTS",
                        "category": "ORDER",
                        "fact_target": "Whether the order exists.",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "The current form confirms the order.",
                        "asserted_value": "exists",
                        "source_scope": "PREVIOUS_AND_CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_ORDER_EXISTS"],
            }
        ),
    )

    expected_source = f"INTAKE_FORM_{case_id}"
    row = matrix.fact_rows[0]
    assert row.origin.source_refs == [expected_source]
    assert row.positions.USER.source_refs == [expected_source]


@pytest.mark.parametrize(
    ("source_scope", "stance", "position_summary", "asserted_value"),
    [
        (
            "CURRENT_SOURCE",
            "CONFIRM",
            "The respondent confirms the existing fact.",
            "confirmed",
        ),
        (
            "PREVIOUS_AND_CURRENT_SOURCE",
            "CONFIRM",
            "The respondent confirms the existing fact and its prior context.",
            "confirmed",
        ),
        (
            "PREVIOUS_MATRIX",
            "NOT_ADDRESSED",
            "The respondent has not addressed this fact.",
            None,
        ),
    ],
)
def test_reducer_preserves_existing_fact_materiality_for_every_source_scope(
    source_scope: str,
    stance: str,
    position_summary: str,
    asserted_value: str | None,
) -> None:
    case_id = f"CASE_materiality_{source_scope.lower()}"
    previous = _single_fact_initiator_matrix(case_id)
    previous_row = previous.fact_rows[0]
    delta = CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": previous_row.fact_id,
                    "category": previous_row.category,
                    "fact_target": previous_row.fact_target,
                    "materiality": "SUPPORTING",
                    "stance": stance,
                    "position_summary": position_summary,
                    "asserted_value": asserted_value,
                    "source_scope": source_scope,
                }
            ],
            "summary_source_fact_keys": [previous_row.fact_id],
        }
    )

    with pytest.raises(
        AgentOutputSchemaError,
        match="cannot change materiality",
    ) as failure:
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, previous.model_dump(mode="json")),
            case_detail=_detail("The respondent addressed the frozen fact."),
            delta=delta,
        )

    assert failure.value.safe_code == "INTAKE_MATRIX_FACT_MATERIALITY_MUTATED"


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

    with pytest.raises(
        AgentOutputSchemaError,
        match="references unknown fact",
    ) as failure:
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, previous.model_dump(mode="json")),
            case_detail=_detail("商家补充了另一项事实。"),
            delta=_existing_fact_delta(
                mistyped_fact_id,
                fact_target="现场是否实施额外墙体加固服务",
            ),
        )

    assert failure.value.safe_code == "INTAKE_MATRIX_FACT_UNKNOWN"


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

    with pytest.raises(
        AgentOutputSchemaError,
        match="cannot uniquely resolve",
    ) as failure:
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, payload),
            case_detail=_detail("商家确认商品页面标注包含基础安装。"),
            delta=_existing_fact_delta(mistyped_fact_id),
        )

    assert failure.value.safe_code == "INTAKE_MATRIX_FACT_AMBIGUOUS"


@pytest.mark.parametrize(
    ("variant", "expected_code"),
    [
        pytest.param(
            "binding",
            "INTAKE_MATRIX_FACT_BINDING_MUTATED",
            id="binding",
        ),
        pytest.param(
            "duplicate",
            "INTAKE_MATRIX_FACT_DUPLICATE",
            id="duplicate",
        ),
        pytest.param(
            "membership",
            "INTAKE_MATRIX_PRIOR_FACT_MISSING",
            id="membership",
        ),
    ],
)
def test_round_three_reachable_matrix_rejections_expose_safe_codes(
    variant: str,
    expected_code: str,
) -> None:
    case_id = f"CASE_safe_code_{variant}"
    previous = _single_fact_initiator_matrix(case_id)
    previous_row = previous.fact_rows[0]
    if variant == "binding":
        delta = _existing_fact_delta(
            previous_row.fact_id,
            fact_target="A different bounded synthetic fact target.",
        )
    elif variant == "duplicate":
        payload = _existing_fact_delta(previous_row.fact_id).model_dump(mode="json")
        duplicate = deepcopy(payload["fact_rows"][0])
        duplicate["fact_key"] = _mistyped_fact_id(previous_row.fact_id)
        payload["fact_rows"].append(duplicate)
        payload["summary_source_fact_keys"].append(duplicate["fact_key"])
        delta = CaseFactMatrixDeltaV2.model_validate(payload)
    else:
        delta = _existing_fact_delta(
            "NEW_SAFE_CODE_CURRENT_FACT",
            fact_target="A distinct bounded synthetic current fact.",
        )

    with pytest.raises(AgentOutputSchemaError) as failure:
        finalize_case_fact_matrix(
            request=_respondent_request(case_id, previous.model_dump(mode="json")),
            case_detail=_detail("A bounded synthetic matrix transition."),
            delta=delta,
        )

    assert failure.value.safe_code == expected_code


def test_changed_reported_claim_without_current_grounding_exposes_safe_code() -> None:
    case_id = "CASE_safe_code_reported_claim"
    previous = _single_fact_initiator_matrix(case_id)
    previous_row = previous.fact_rows[0]
    previous_position = previous_row.positions.USER
    detail = _detail("A bounded synthetic reported-claim transition.")
    detail["respondent_attitude"] = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "A bounded synthetic historical respondent position.",
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        "grounding": {"source": "INITIAL_FORM", "message_id": ""},
    }
    delta = CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": previous_row.fact_id,
                    "category": previous_row.category,
                    "fact_target": previous_row.fact_target,
                    "materiality": previous_row.materiality,
                    "stance": previous_position.stance,
                    "position_summary": previous_position.position_summary,
                    "asserted_value": previous_position.asserted_value,
                    "source_scope": "PREVIOUS_MATRIX",
                }
            ],
            "summary_source_fact_keys": [previous_row.fact_id],
        }
    )

    with pytest.raises(AgentOutputSchemaError) as failure:
        finalize_case_fact_matrix(
            request=_initiator_follow_up_request(
                case_id,
                previous.model_dump(mode="json"),
            ),
            case_detail=detail,
            delta=delta,
        )

    assert (
        failure.value.safe_code
        == "INTAKE_MATRIX_REPORTED_CLAIM_SOURCE_MISSING"
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

    respondent_detail = _detail(
        "用户主张页面包含基础安装并要求退费；商家确认页面说明，但称收费对应额外墙体加固。"
    )
    respondent_detail["respondent_attitude"] = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "商家不同意退还额外服务费。",
        "source": DIRECT_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "grounding": {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_MERCHANT_1",
        },
    }
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
        case_detail=respondent_detail,
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


@pytest.mark.parametrize(
    "grounding",
    [
        None,
        {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_WRONG_DIRECT_SOURCE",
        },
    ],
)
def test_changed_direct_claim_requires_exact_current_grounding(
    grounding: dict[str, str] | None,
) -> None:
    case_id = "CASE_direct_claim_grounding"
    previous = _single_fact_initiator_matrix(case_id)
    fact_id = previous.fact_rows[0].fact_id
    detail = _detail("The respondent proposes a different resolution.")
    attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "The respondent rejects the requested refund.",
        "source": DIRECT_RESPONDENT_SOURCE,
        "confidence": 0.8,
    }
    if grounding is not None:
        attitude["grounding"] = grounding
    detail["respondent_attitude"] = attitude
    delta_payload = _existing_fact_delta(fact_id).model_dump(mode="json")
    delta_payload["respondent_claim"] = {
        "attitude": "DISAGREE",
        "position_summary": "The respondent rejects the requested refund.",
    }

    with pytest.raises(
        AgentOutputSchemaError,
        match="changed respondent direct claim is not bound to the current source",
    ) as failure:
        finalize_case_fact_matrix(
            request=_respondent_request(
                case_id,
                previous.model_dump(mode="json"),
            ),
            case_detail=detail,
            delta=CaseFactMatrixDeltaV2.model_validate(delta_payload),
        )

    assert failure.value.safe_code == "INTAKE_MATRIX_DIRECT_CLAIM_SOURCE_MISSING"


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

    claim_answer_detail = _detail("双方确认页面标注，但商家不同意退还安装费。")
    claim_answer_detail["respondent_attitude"] = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "商家不同意退还安装费。",
        "source": DIRECT_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "grounding": {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": "MESSAGE_MERCHANT_CLAIM",
        },
    }
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
        case_detail=claim_answer_detail,
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


def test_historical_reported_attitude_exact_carry_does_not_append_neutral_current_source() -> None:
    case_id = "CASE_reported_attitude_exact_carry"
    prior_attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "The merchant explicitly rejected the requested refund.",
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        "grounding": {"source": "INITIAL_FORM", "message_id": ""},
    }
    opening = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "FORM_SUBMISSION",
            "initial_case_facts": {
                "form_source": "FORM_SUBMISSION",
                "form_description": prior_attitude["position"],
                "order_reference": "ORDER_REPORTED_ATTITUDE_CARRY",
                "initiator_role": "USER",
            },
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    opening_detail = _detail("The initiator reports the respondent's position.")
    opening_detail["respondent_attitude"] = deepcopy(prior_attitude)
    first = finalize_case_fact_matrix(
        request=opening,
        case_detail=opening_detail,
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": "NEW_REPORTED_ATTITUDE_FACT",
                        "category": "OTHER",
                        "fact_target": "Whether the respondent rejected the request",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "The initiator reports a rejection.",
                        "asserted_value": "rejected",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_REPORTED_ATTITUDE_FACT"],
            }
        ),
    )
    prior_claim = first.claims.respondent_reported_by_initiator
    assert prior_claim is not None
    prior_refs = list(prior_claim.source_refs)
    fact_id = first.fact_rows[0].fact_id

    neutral_message_id = "MESSAGE_USER_NEUTRAL_FACT"
    carried_detail = _detail("The current turn adds a factual reference only.")
    carried_detail["respondent_attitude"] = deepcopy(prior_attitude)
    carried = finalize_case_fact_matrix(
        request=IntakeTurnRequest.model_validate(
            {
                "case_id": case_id,
                "room_type": "INTAKE",
                "turn_source": "ROOM_MESSAGE",
                "current_user_message": {
                    "message_id": neutral_message_id,
                    "sequence_no": 2,
                    "role": "USER",
                    "source": "ROOM_MESSAGE",
                    "text": "The order reference was corrected in this turn.",
                },
                "recent_dialogue_messages": [],
                "previous_case_detail": {
                    "case_fact_matrix": first.model_dump(mode="json")
                },
                "agent_context": _context(case_id, "USER", "user-local"),
            }
        ),
        case_detail=carried_detail,
        delta=CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        "fact_key": fact_id,
                        "category": "OTHER",
                        "fact_target": "Whether the respondent rejected the request",
                        "materiality": "CORE",
                        "stance": "CONFIRM",
                        "position_summary": "The initiator reports a rejection.",
                        "asserted_value": "rejected",
                        "source_scope": "PREVIOUS_MATRIX",
                    }
                ],
                "summary_source_fact_keys": [fact_id],
            }
        ),
    )

    reported = carried.claims.respondent_reported_by_initiator
    assert reported is not None
    assert reported.model_dump(mode="json") == prior_claim.model_dump(mode="json")
    assert reported.source_refs == prior_refs
    assert neutral_message_id not in reported.source_refs


def test_legacy_dossier_exact_attitude_carry_does_not_mint_a_current_reported_claim() -> None:
    case_id = "CASE_legacy_attitude_migration"
    current_message_id = "MESSAGE_USER_LEGACY_NEUTRAL"
    prior_attitude = {
        "respondent_role": "MERCHANT",
        "attitude": "DISAGREE",
        "position": "The merchant explicitly rejected the requested refund.",
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": 0.8,
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        "grounding": {"source": "INITIAL_FORM", "message_id": ""},
    }
    legacy_dossier = {
        "schema_version": "intake_case_detail.v1",
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
        },
        "respondent_attitude": deepcopy(prior_attitude),
    }
    request = IntakeTurnRequest.model_validate(
        {
            "case_id": case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "current_user_message": {
                "message_id": current_message_id,
                "sequence_no": 5,
                "role": "USER",
                "source": "ROOM_MESSAGE",
                "text": "The order reference was corrected in this turn.",
            },
            "recent_dialogue_messages": [],
            "previous_case_detail": legacy_dossier,
            "agent_context": _context(case_id, "USER", "user-local"),
        }
    )
    delta = CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": "NEW_LEGACY_MIGRATION_FACT",
                    "category": "ORDER",
                    "fact_target": "Whether the corrected order reference is current",
                    "materiality": "SUPPORTING",
                    "stance": "CONFIRM",
                    "position_summary": "The initiator corrected the order reference.",
                    "asserted_value": "corrected",
                    "source_scope": "CURRENT_SOURCE",
                }
            ],
            "summary_source_fact_keys": ["NEW_LEGACY_MIGRATION_FACT"],
        }
    )

    def finalize(attitude: dict[str, object]):
        detail = _detail("The current turn corrects an order reference.")
        detail["respondent_attitude"] = deepcopy(attitude)
        return finalize_case_fact_matrix(
            request=request,
            case_detail=detail,
            delta=delta,
        )

    migrated = finalize(prior_attitude)

    assert migrated.matrix_kind == "INITIATOR_FROZEN"
    assert len(migrated.fact_rows) == 1
    assert migrated.fact_rows[0].positions.USER.source_refs == [current_message_id]
    assert migrated.claims.respondent_reported_by_initiator is None

    variants: list[dict[str, object]] = []
    changed_attitude = deepcopy(prior_attitude)
    changed_attitude["attitude"] = "AGREE"
    variants.append(changed_attitude)
    changed_position = deepcopy(prior_attitude)
    changed_position["position"] = "The merchant accepted the requested refund."
    variants.append(changed_position)
    changed_grounding_source = deepcopy(prior_attitude)
    changed_grounding_source["grounding"]["source"] = "PARTICIPANT_MESSAGE"
    variants.append(changed_grounding_source)
    changed_grounding_message = deepcopy(prior_attitude)
    changed_grounding_message["grounding"]["message_id"] = "MESSAGE_WRONG_LEGACY_SOURCE"
    variants.append(changed_grounding_message)

    for changed in variants:
        with pytest.raises(AgentOutputSchemaError):
            finalize(changed)


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
