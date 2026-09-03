from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from pydantic import TypeAdapter, ValidationError

from app.agents.hearing_intake_v4 import (
    materialize_hearing_questions_v5,
    materialize_hearing_synthesis_v5,
)
from app.agents.hearing_flow import HearingFlowWorkflows
from app.harness.hearing_intake_context_v4 import (
    assemble_hearing_intake_context_v4,
)
from app.llm import AgentOutputSchemaError, governed_max_output_tokens
from app.harness.invocation_context import AgentInvocationContext
from app.schemas import (
    CaseFactMatrixV2,
    HearingAnswerBundleV4,
    HearingIntakeQuestionsLlmOutputV5,
    HearingIntakeQuestionsRequestV4,
    HearingIntakeSynthesisLlmOutputV5,
    HearingIntakeSynthesisRequestV4,
    content_hash,
)
from app.schemas.hearing_flow import HearingIssueAlignmentV4


SYNTHESIS_PROMPT = (
    Path(__file__).parents[2]
    / "app"
    / "agents"
    / "prompts"
    / "dispute_intake_officer"
    / "hearing_intake_answer_synthesis_v5.md"
)


def _m1() -> CaseFactMatrixV2:
    value = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": "CASE_hearing_v4",
        "matrix_id": "CASE_MATRIX_M1",
        "matrix_version": 2,
        "matrix_kind": "BILATERAL_FROZEN",
        "parent_ref": None,
        "content_hash": "0" * 64,
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "source_refs": ["SOURCE_USER", "SOURCE_MERCHANT"],
        "case_overview": {
            "neutral_summary": "用户称商品性能不足，商家认为符合约定。",
            "core_conflict": "商品性能是否符合约定。",
            "summary_source_fact_ids": ["FACT_PERFORMANCE"],
        },
        "claims": {
            "initiator_claim": {
                "initiator_role": "USER",
                "requested_resolution": "RETURN_REFUND",
                "requested_amount": 500.0,
                "requested_items": "商品",
                "reason_summary": "性能未达到宣传标准。",
                "position_summary": "用户要求退货退款。",
                "source_refs": ["SOURCE_USER"],
            },
            "respondent_reported_by_initiator": None,
            "respondent_direct": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position_summary": "商家认为商品符合约定。",
                "alternative_proposal": None,
                "source_type": "RESPONDENT_DIRECT_INTAKE",
                "source_refs": ["SOURCE_MERCHANT"],
            },
            "claim_conflict": "双方对性能和处理方案存在争议。",
        },
        "fact_rows": [
            {
                "fact_id": "FACT_PERFORMANCE",
                "category": "PRODUCT_STATE",
                "fact_target": "商品核心性能是否达到约定标准",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": ["SOURCE_USER"],
                },
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户认为未达到约定。",
                        "asserted_value": "未达到",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_USER"],
                    },
                    "MERCHANT": {
                        "stance": "CONFIRM",
                        "position_summary": "商家认为达到约定。",
                        "asserted_value": "达到",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_MERCHANT"],
                    },
                },
                "party_alignment": {
                    "status": "CONTESTED",
                    "agreed_statement": None,
                    "conflict_summary": "双方对性能是否达标存在分歧。",
                },
                "requires_resolution": True,
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "COVERED_BY_FROZEN_DOSSIER",
            }
        ],
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": "MERCHANT",
            "source_stage": "RESPONDENT_INTAKE",
            "latest_source_ref": "SOURCE_MERCHANT",
            "source_context_hash": "a" * 64,
        },
        "fact_indexes": {
            "not_computed_fact_ids": [],
            "agreed_fact_ids": [],
            "partially_agreed_fact_ids": [],
            "contested_fact_ids": ["FACT_PERFORMANCE"],
            "one_sided_fact_ids": [],
            "unresolved_fact_ids": [],
            "core_fact_ids": ["FACT_PERFORMANCE"],
            "requires_resolution_fact_ids": ["FACT_PERFORMANCE"],
        },
    }
    value["content_hash"] = content_hash(value, hash_field="content_hash")
    return CaseFactMatrixV2.model_validate(value)


def _question_request() -> HearingIntakeQuestionsRequestV4:
    return HearingIntakeQuestionsRequestV4(
        case_id="CASE_hearing_v4",
        workflow_id="WORKFLOW_hearing_v4",
        stage_code="INTAKE_QUESTIONS",
        stage_sequence=4,
        source_refs=["PRELUDE_RECEIPT"],
        prelude_authority_hash="b" * 64,
        case_fact_matrix=_m1(),
        question_slots=[
            {"question_slot_id": f"QUESTION_SLOT_{index:02d}"}
            for index in range(1, 6)
        ],
    )


def _question_output() -> HearingIntakeQuestionsLlmOutputV5:
    return HearingIntakeQuestionsLlmOutputV5.model_validate(
        {
            "lead_public_text": "庭前案情已经装载，我会先围绕核心争议请双方说明。",
            "schema_version": "hearing_intake_question_stream.v5",
            "frames": [
                {
                    "header": {
                        "frame_sequence": 2,
                        "frame_type": "SHARED_ISSUE_QUESTION",
                        "question_slot_id": "QUESTION_SLOT_01",
                        "fact_ids": ["FACT_PERFORMANCE"],
                    },
                    "public_text": "请双方分别说明商品核心性能是否达到约定标准。",
                }
            ],
            "question_bindings": [
                {
                    "question_slot_id": "QUESTION_SLOT_01",
                    "issue_baseline": {
                        "issue_statement": "商品核心性能是否达到约定标准",
                        "source_fact_ids": ["FACT_PERFORMANCE"],
                        "effective_party_positions": {
                            "USER": {
                                "position_source": "M1",
                                "position_summary": "用户认为未达到约定。",
                            },
                            "MERCHANT": {
                                "position_source": "M1",
                                "position_summary": "商家认为达到约定。",
                            },
                        },
                        "alignment": {
                            "status": "CONTESTED",
                            "agreed_statement": None,
                            "conflict_summary": "双方对性能是否达标存在分歧。",
                        },
                    },
                    "party_prompts": {
                        "USER": "请说明实际使用表现。",
                        "MERCHANT": "请说明交付标准及实际状态。",
                    },
                }
            ],
        }
    )


def _bundle(role: str, question: object) -> HearingAnswerBundleV4:
    participant = "ACTOR_USER" if role == "USER" else "ACTOR_MERCHANT"
    bundle_id = f"HEARING_ACTION_{role}"
    unit_id = f"ANSWER_UNIT_{role}_01"
    value = {
        "schema_version": "hearing_answer_bundle.v4",
        "answer_bundle_id": bundle_id,
        "answer_bundle_hash": "0" * 64,
        "question_set_id": question.question_set_id,
        "question_set_hash": question.question_set_hash,
        "formal_issue_catalog_hash": question.formal_issue_catalog_hash,
        "participant_id": participant,
        "participant_role": role,
        "submission_status": "SUBMITTED",
        "answer_units": [
            {
                "answer_unit_id": unit_id,
                "question_id": question.questions[0].question_id,
                "issue_id": question.questions[0].issue_id,
                "answer_text": (
                    "本轮确认性能未达到约定，并将诉求调整为部分退款。"
                    if role == "USER"
                    else "本轮确认部分性能未达到约定，同意讨论部分退款。"
                ),
            }
        ],
        "source_message_ids": [f"MESSAGE_{role}"],
    }
    value["answer_bundle_hash"] = content_hash(value, hash_field="answer_bundle_hash")
    return HearingAnswerBundleV4.model_validate(value)


def _synthesis_fixture():
    question_result = materialize_hearing_questions_v5(
        _question_request(), _question_output()
    )
    question_set = question_result.question_set
    user = _bundle("USER", question_set)
    merchant = _bundle("MERCHANT", question_set)
    request = HearingIntakeSynthesisRequestV4(
        case_id="CASE_hearing_v4",
        workflow_id="WORKFLOW_hearing_v4",
        stage_code="INTAKE_SYNTHESIS",
        stage_sequence=6,
        source_refs=["ANSWER_STAGE_RECEIPT"],
        prelude_authority_hash="b" * 64,
        case_fact_matrix=_m1(),
        question_set=question_set,
        party_answer_bundles=[user, merchant],
        new_issue_slots=[f"NEW_ISSUE_SLOT_{index:02d}" for index in range(1, 6)],
        new_fact_slots=[f"NEW_FACT_SLOT_{index:02d}" for index in range(1, 21)],
    )
    issue_id = question_set.questions[0].issue_id
    output = HearingIntakeSynthesisLlmOutputV5.model_validate(
        {
            "lead_public_text": "双方本轮陈述已经收齐，我将按争议点进行汇总。",
            "schema_version": "hearing_intake_answer_stream.v5",
            "frames": [
                {
                    "header": {
                        "frame_sequence": 2,
                        "frame_type": "REBIND_ISSUE_SYNTHESIS",
                        "issue_ref": issue_id,
                    },
                    "public_text": "双方现均确认至少部分性能未达到约定，并同意讨论部分退款。",
                }
            ],
            "issue_rebindings": [
                {
                    "issue_id": issue_id,
                    "party_bindings": {
                        "USER": {
                            "binding_action": "REPLACE",
                            "answer_bundle_id": user.answer_bundle_id,
                            "answer_unit_id": user.answer_units[0].answer_unit_id,
                            "current_position": {
                                "position_summary": "用户现主张部分退款。"
                            },
                        },
                        "MERCHANT": {
                            "binding_action": "REPLACE",
                            "answer_bundle_id": merchant.answer_bundle_id,
                            "answer_unit_id": merchant.answer_units[0].answer_unit_id,
                            "current_position": {
                                "position_summary": "商家同意讨论部分退款。"
                            },
                        },
                    },
                    "current_alignment": {
                        "status": "PARTIALLY_AGREED",
                        "agreed_statement": "双方均接受以部分退款作为讨论方向。",
                        "conflict_summary": "具体退款金额尚未一致。",
                    },
                }
            ],
            "new_issue_proposals": [],
            "matrix_effects": {
                "claim_effects": [
                    {
                        "source_issue_refs": [issue_id],
                        "effect_type": "INITIATOR_CLAIM_REPLACE",
                        "subject_role": "USER",
                        "answer_bundle_id": user.answer_bundle_id,
                        "answer_unit_ids": [user.answer_units[0].answer_unit_id],
                        "replacement": {
                            "requested_resolution": "PARTIAL_REFUND",
                            "requested_amount": 200.0,
                            "requested_items": None,
                            "reason_summary": "用户根据本轮陈述调整诉求。",
                            "position_summary": "用户现主张部分退款二百元。",
                        },
                    },
                    {
                        "source_issue_refs": [issue_id],
                        "effect_type": "RESPONDENT_CLAIM_REPLACE",
                        "subject_role": "MERCHANT",
                        "answer_bundle_id": merchant.answer_bundle_id,
                        "answer_unit_ids": [merchant.answer_units[0].answer_unit_id],
                        "replacement": {
                            "attitude": "PARTIALLY_AGREE",
                            "position_summary": "商家同意讨论部分退款。",
                            "alternative_proposal": "可部分退款一百五十元。",
                        },
                    },
                ],
                "existing_fact_effects": [
                    {
                        "source_issue_refs": [issue_id],
                        "fact_id": "FACT_PERFORMANCE",
                        "party_updates": {
                            "USER": {
                                "answer_bundle_id": user.answer_bundle_id,
                                "answer_unit_ids": [user.answer_units[0].answer_unit_id],
                                "stance": "DENY",
                                "position_summary": "用户确认性能未达到约定。",
                                "asserted_value": "部分未达到",
                            },
                            "MERCHANT": {
                                "answer_bundle_id": merchant.answer_bundle_id,
                                "answer_unit_ids": [merchant.answer_units[0].answer_unit_id],
                                "stance": "PARTIAL",
                                "position_summary": "商家确认部分性能未达到约定。",
                                "asserted_value": "部分未达到",
                            },
                        },
                        "alignment": {
                            "status": "PARTIALLY_AGREED",
                            "agreed_statement": "双方确认至少部分性能未达到约定。",
                            "conflict_summary": "双方对未达范围仍有分歧。",
                        },
                    }
                ],
                "new_fact_effects": [],
                "relationship_effects": [],
            },
            "matrix_summary": {
                "summary_text": "双方确认部分性能问题，并转为协商部分退款。",
                "core_conflict": "具体未达范围及部分退款金额尚未一致。",
                "summary_fact_refs": ["FACT_PERFORMANCE"],
            },
        }
    )
    return request, output


def test_v4_context_uses_one_m1_projection_and_excludes_e1() -> None:
    source = {"request": _question_request().model_dump(mode="json")}
    source["evidence_matrix"] = {"schema_version": "fact_evidence_matrix.v2"}
    assembled = assemble_hearing_intake_context_v4(
        "hearing_intake_questions", source
    )

    assert list(assembled.payload) == [
        "context_header",
        "mode_contract",
        "authority_scope",
        "frozen_case_matrix_projection",
        "question_slot_catalog",
        "question_policy",
        "output_contract",
    ]
    assert "evidence_matrix" not in assembled.payload
    assert "source_refs" not in str(
        assembled.payload["frozen_case_matrix_projection"]["fact_rows"]
    )
    assert assembled.payload["output_contract"] == {
        "schema_version": "hearing_intake_model_output_contract.v5",
        "model_schema_version": "hearing_intake_question_stream.v5",
        "structured_output_only": True,
        "property_order": [
            "lead_public_text",
            "schema_version",
            "frames",
            "question_bindings",
        ],
        "public_delta_fields": ["lead_public_text", "frames.public_text"],
        "frame_contract": {
            "array_field": "frames",
            "property_order": ["header", "public_text"],
            "header_must_complete_before_public_text": True,
            "persist_after_frame_close": True,
        },
        "server_owned_identifiers": True,
    }


def test_v5_question_frame_cannot_represent_header_without_its_public_text() -> None:
    invalid = _question_output().model_dump(mode="json")
    invalid["frames"][0].pop("public_text")

    with pytest.raises(ValidationError):
        HearingIntakeQuestionsLlmOutputV5.model_validate(invalid)


def test_v4_alignment_contract_is_visible_in_schema_and_prompt() -> None:
    schema = TypeAdapter(HearingIssueAlignmentV4).json_schema()
    discriminator = schema["discriminator"]
    assert discriminator["propertyName"] == "status"
    assert set(discriminator["mapping"]) == {
        "AGREED",
        "PARTIALLY_AGREED",
        "CONTESTED",
        "ONE_SIDED",
        "UNRESOLVED",
    }
    encoded_schema = json.dumps(schema, ensure_ascii=False, sort_keys=True)
    assert '"const": "AGREED"' in encoded_schema
    assert '"const": "PARTIALLY_AGREED"' in encoded_schema
    assert '"type": "null"' in encoded_schema

    prompt = SYNTHESIS_PROMPT.read_text(encoding="utf-8")
    for status in (
        "AGREED",
        "PARTIALLY_AGREED",
        "CONTESTED",
        "ONE_SIDED",
        "UNRESOLVED",
    ):
        assert f"`{status}`：" in prompt
    assert "不能省略字段，不能用空字符串代替 `null`" in prompt


def test_v4_rebinding_emits_current_position_once_and_projects_effective_state() -> None:
    schema = HearingIntakeSynthesisLlmOutputV5.model_json_schema()
    rebinding_schema = schema["$defs"]["HearingIssueRebindingV4"]
    assert rebinding_schema["required"] == [
        "issue_id",
        "party_bindings",
        "current_alignment",
    ]
    assert "effective_party_positions" not in rebinding_schema["properties"]

    request, output = _synthesis_fixture()
    result = materialize_hearing_synthesis_v5(request, output)
    issue = result.issue_transition_set.issues[0]
    assert issue.effective_party_positions.USER.position_source == "CURRENT_ANSWER"
    assert issue.effective_party_positions.USER.position_summary == "用户现主张部分退款。"
    assert issue.effective_party_positions.MERCHANT.position_source == "CURRENT_ANSWER"
    assert issue.effective_party_positions.MERCHANT.position_summary == (
        "商家同意讨论部分退款。"
    )


def test_v5_synthesis_root_order_accepts_an_omitted_optional_issue_list_only() -> None:
    request, output = _synthesis_fixture()
    payload = deepcopy(output.model_dump(mode="json"))
    payload.pop("new_issue_proposals")

    parsed = HearingIntakeSynthesisLlmOutputV5.model_validate(payload)
    result = materialize_hearing_synthesis_v5(request, parsed)

    assert parsed.new_issue_proposals == []
    assert [issue.issue_kind for issue in result.issue_transition_set.issues] == [
        "REBIND"
    ]

    frames_first = {"frames": payload["frames"], **payload}
    with pytest.raises(ValidationError, match="root property order"):
        HearingIntakeSynthesisLlmOutputV5.model_validate(frames_first)


def test_v5_rebinding_action_does_not_reclassify_model_owned_position() -> None:
    request, output = _synthesis_fixture()
    payload = deepcopy(output.model_dump(mode="json"))
    user_binding = payload["issue_rebindings"][0]["party_bindings"]["USER"]
    user_binding["binding_action"] = "NO_POSITION"

    parsed = HearingIntakeSynthesisLlmOutputV5.model_validate(payload)
    result = materialize_hearing_synthesis_v5(request, parsed)
    position = result.issue_transition_set.issues[0].effective_party_positions.USER

    assert user_binding["current_position"] is not None
    assert position is not None
    assert position.position_summary == "用户现主张部分退款。"


def test_v5_fact_update_accepts_model_not_addressed_as_canonical_position() -> None:
    request, output = _synthesis_fixture()
    payload = deepcopy(output.model_dump(mode="json"))
    user_update = payload["matrix_effects"]["existing_fact_effects"][0][
        "party_updates"
    ]["USER"]
    user_update["stance"] = "NOT_ADDRESSED"
    user_update["position_summary"] = "用户本轮未直接回应该项事实。"
    user_update["asserted_value"] = None

    parsed = HearingIntakeSynthesisLlmOutputV5.model_validate(payload)
    result = materialize_hearing_synthesis_v5(request, parsed)
    position = result.case_fact_matrix.fact_rows[0].positions.USER

    assert position.stance.value == "NOT_ADDRESSED"
    assert position.position_summary == "用户本轮未直接回应该项事实。"
    assert position.source_type == "NO_DIRECT_POSITION"
    assert position.source_refs == []
    assert "应用层不会根据自然语言重新判定该立场" in SYNTHESIS_PROMPT.read_text(
        encoding="utf-8"
    )


def test_v4_synthesis_context_exposes_one_copy_only_binding_catalog() -> None:
    request, _ = _synthesis_fixture()
    assembled = assemble_hearing_intake_context_v4(
        "hearing_intake_synthesis",
        {"request": request.model_dump(mode="json")},
    )
    catalog = assembled.payload["binding_authority_catalog"]
    issue_id = request.question_set.questions[0].issue_id
    fact_id = request.case_fact_matrix.fact_rows[0].fact_id

    assert catalog["binding_policy"] == "COPY_EXACT_VALUE_FROM_THIS_CATALOG_ONLY"
    assert catalog["formal_issue_ids"] == [issue_id]
    assert catalog["existing_fact_ids"] == [fact_id]
    assert catalog["authorized_new_issue_slots"] == request.new_issue_slots
    assert catalog["authorized_new_fact_slots"] == request.new_fact_slots
    assert "allowed_issue_refs" not in catalog
    assert "allowed_fact_refs" not in catalog
    assert catalog["answer_binding_catalog"][0]["role_bindings"] == {
        "USER": {
            "answer_bundle_id": request.party_answer_bundles[0].answer_bundle_id,
            "answer_unit_id": request.party_answer_bundles[0].answer_units[0].answer_unit_id,
        },
        "MERCHANT": {
            "answer_bundle_id": request.party_answer_bundles[1].answer_bundle_id,
            "answer_unit_id": request.party_answer_bundles[1].answer_units[0].answer_unit_id,
        },
    }
    prompt = SYNTHESIS_PROMPT.read_text(encoding="utf-8")
    assert "FACT_01" not in prompt
    assert "`binding_authority_catalog` 是所有引用字段的唯一取值权威" in prompt
    assert "本轮有效事实引用集合严格等于 `existing_fact_ids` 加“已激活新事实槽”" in prompt
    assert "本轮有效争点引用集合严格等于 `formal_issue_ids` 加“已激活新争点槽”" in prompt
    assert "若 `new_issue_proposals` 为空" in prompt
    assert "如果 `new_fact_effects` 为空" in prompt
    assert "未激活新槽引用为零" in prompt
    assert "重复 `source_issue_refs` 为零" in prompt
    assert "数组长度必须等于其去重后的长度" in prompt
    assert "`HeARING_ISSUE_`" in prompt
    assert "区分大小写、逐字属于上述有效集合" in prompt
    assert "完整闭合 JSON 优先于扩写" in prompt
    assert "`matrix_effects` 是相对 M1 的稀疏增量" in prompt
    assert governed_max_output_tokens("hearing_intake_synthesis") == 8_192
    assert "allowed_issue_refs" not in prompt
    assert "allowed_fact_refs" not in prompt


def test_v5_synthesis_rejects_unactivated_reserved_issue_slot() -> None:
    request, output = _synthesis_fixture()
    invalid = deepcopy(output.model_dump(mode="json"))
    invalid["matrix_effects"]["claim_effects"][0]["source_issue_refs"] = [
        request.new_issue_slots[0]
    ]

    with pytest.raises(AgentOutputSchemaError) as raised:
        materialize_hearing_synthesis_v5(
            request,
            HearingIntakeSynthesisLlmOutputV5.model_validate(invalid),
        )

    assert raised.value.diagnostic_code == "HEARING_SYNTHESIS_MATRIX_ISSUE_AUTHORITY"


@pytest.mark.parametrize("malformed_prefix", ["FACCT_", "FACt_"])
def test_v5_fact_reference_schema_rejects_malformed_fact_prefix(
    malformed_prefix: str,
) -> None:
    _, output = _synthesis_fixture()
    invalid = deepcopy(output.model_dump(mode="json"))
    invalid["matrix_summary"]["summary_fact_refs"] = [
        f"{malformed_prefix}PERFORMANCE"
    ]

    with pytest.raises(ValidationError):
        HearingIntakeSynthesisLlmOutputV5.model_validate(invalid)

    summary_schema = HearingIntakeSynthesisLlmOutputV5.model_json_schema()["$defs"][
        "HearingMatrixSummaryV4"
    ]
    assert summary_schema["properties"]["summary_fact_refs"]["items"]["pattern"] == (
        r"^(?:FACT_[A-Za-z0-9_:-]{1,123}|"
        r"NEW_FACT_SLOT_(?:0[1-9]|1[0-9]|20))$"
    )


def test_v4_question_binding_fails_closed_on_unknown_m1_fact_id() -> None:
    invalid = _question_output().model_dump(mode="json")
    invalid["frames"][0]["header"]["fact_ids"] = ["FACT_UNKNOWN"]
    invalid["question_bindings"][0]["issue_baseline"]["source_fact_ids"] = [
        "FACT_UNKNOWN"
    ]

    with pytest.raises(AgentOutputSchemaError):
        materialize_hearing_questions_v5(
            _question_request(),
            HearingIntakeQuestionsLlmOutputV5.model_validate(invalid),
        )


def test_v4_question_to_m2_is_formally_bound_and_byte_stable() -> None:
    request, output = _synthesis_fixture()

    first = materialize_hearing_synthesis_v5(request, output)
    second = materialize_hearing_synthesis_v5(request, output)

    assert first.model_dump_json() == second.model_dump_json()
    assert first.case_fact_matrix.matrix_version == request.case_fact_matrix.matrix_version + 1
    assert first.case_fact_matrix.parent_ref.content_hash == request.case_fact_matrix.content_hash
    assert first.case_fact_matrix.claims.initiator_claim.requested_resolution == "PARTIAL_REFUND"
    assert first.case_fact_matrix.claims.respondent_direct.source_type == (
        "RESPONDENT_DIRECT_HEARING"
    )
    assert first.case_fact_matrix.fact_rows[0].category == request.case_fact_matrix.fact_rows[0].category
    assert first.case_fact_matrix.fact_rows[0].party_alignment.status == "PARTIALLY_AGREED"
    assert first.issue_state_set.matrix_hash == first.case_fact_matrix.content_hash
    assert first.issue_state_set.transition_hash == first.issue_transition_set.transition_hash
    assert first.issue_state_set.content_hash == content_hash(
        first.issue_state_set, hash_field="content_hash"
    )
    assert CaseFactMatrixV2.model_validate(
        first.case_fact_matrix.model_dump(mode="json")
    ) == first.case_fact_matrix


def test_v4_answer_references_are_model_owned_without_cross_party_rejection() -> None:
    request, output = _synthesis_fixture()
    model_owned = deepcopy(output.model_dump(mode="json"))
    merchant_bundle = request.party_answer_bundles[1]
    merchant_unit_id = merchant_bundle.answer_units[0].answer_unit_id
    model_owned["issue_rebindings"][0]["party_bindings"]["USER"][
        "answer_unit_id"
    ] = merchant_unit_id
    user_fact_update = model_owned["matrix_effects"]["existing_fact_effects"][0][
        "party_updates"
    ]["USER"]
    user_fact_update["answer_bundle_id"] = merchant_bundle.answer_bundle_id
    user_fact_update["answer_unit_ids"] = [merchant_unit_id]
    initiator_effect = model_owned["matrix_effects"]["claim_effects"][0]
    initiator_effect["answer_bundle_id"] = merchant_bundle.answer_bundle_id
    initiator_effect["answer_unit_ids"] = [merchant_unit_id]

    result = materialize_hearing_synthesis_v5(
        request,
        HearingIntakeSynthesisLlmOutputV5.model_validate(model_owned),
    )

    assert result.issue_transition_set.issues[0].source_answer_unit_ids == [
        merchant_unit_id
    ]
    assert result.case_fact_matrix.fact_rows[0].positions.USER.source_refs[-2:] == [
        merchant_bundle.answer_bundle_id,
        merchant_unit_id,
    ]
    assert result.case_fact_matrix.claims.initiator_claim.source_refs == [
        merchant_bundle.answer_bundle_id,
        merchant_unit_id,
    ]


@pytest.mark.asyncio
async def test_v4_agent_context_reaches_governed_harness_invocation() -> None:
    captured: dict[str, object] = {}

    class Runner:
        def invoke_structured(self, **kwargs):  # pragma: no cover - constructor contract
            raise AssertionError(kwargs)

        async def ainvoke_structured(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(value=_question_output())

    context = AgentInvocationContext.model_validate(
        {
            "tenant_id": "TENANT_v4",
            "case_id": "CASE_hearing_v4",
            "room_type": "HEARING",
            "actor_id": "ACTOR_SYSTEM",
            "actor_role": "SYSTEM",
            "access_session_id": "ACCESS_hearing_v4",
            "permission_level": "SYSTEM_ALL",
            "permission_scopes": ["HEARING_AGENT"],
            "agent_key": "hearing-intake-officer-v4",
            "agent_invocation_id": "ATTEMPT_hearing_v4",
            "agent_session_id": "SESSION_hearing_v4",
            "conversation_scope": "TENANT_v4:CASE_hearing_v4:HEARING:1:SYSTEM",
            "scope_type": "ROOM_SHARED",
            "allowed_actor_ids": ["ACTOR_SYSTEM"],
            "allowed_actor_roles": ["SYSTEM"],
            "prompt_profile_id": "all-rooms-prompt.target-e2e.v2",
            "memory_policy_id": "MEMEO_DEFAULT",
            "model_profile_id": "hearing.model-profile.v4",
            "output_schema_version": "hearing.intake-proposal.v4",
            "policy_version": "hearing.proposal-only.v4",
            "guardrail_version": "hearing.guardrails.v4",
            "tool_capabilities": [],
        }
    )
    workflow = HearingFlowWorkflows(Runner())

    result = await workflow._aintake_questions_proposal(  # noqa: SLF001
        _question_request(),
        agent_context=context,
    )

    assert result.schema_version == "hearing_intake_questions.v5"
    assert captured["agent_context"] is context
    assert captured["case_data"] == {
        "context_contract": "hearing_intake_context.v4",
        "agent_role": "INTAKE_OFFICER",
        "stage_mode": "QUESTION_GENERATION",
        "source_authority_hash": captured["case_data"]["source_authority_hash"],
    }
    assert captured["context_pack"].configuration_profile_key == (
        "HEARING_INTAKE_CONTEXT_PACK_V4"
    )


@pytest.mark.asyncio
async def test_v5_synthesis_materialization_is_inside_model_retry_boundary() -> None:
    captured: dict[str, object] = {}
    request, output = _synthesis_fixture()

    class Runner:
        def invoke_structured(self, **kwargs):  # pragma: no cover - async-only proof
            raise AssertionError(kwargs)

        async def ainvoke_structured(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(value=output)

    result = await HearingFlowWorkflows(Runner())._aintake_synthesis_proposal(  # noqa: SLF001
        request
    )

    assert result.schema_version == "hearing_intake_synthesis.v5"
    validator = captured["semantic_validator"]
    assert callable(validator)
    assert validator(output) is output

    invalid = deepcopy(output.model_dump(mode="json"))
    invalid["matrix_effects"]["claim_effects"][0]["source_issue_refs"] = [
        request.new_issue_slots[0]
    ]
    with pytest.raises(ValueError):
        validator(HearingIntakeSynthesisLlmOutputV5.model_validate(invalid))
