from __future__ import annotations

import json
from types import SimpleNamespace

from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnableSequence
from pydantic import BaseModel, ConfigDict
import pytest

from app.graphs.hearing.contracts import EMPTY_HEARING_TOOL_POLICY
from app.graphs.hearing.errors import HearingLcelContractError
from app.graphs.hearing.lcel import (
    GovernedHearingModelAdapter,
    build_hearing_lcel,
    invoke_hearing_lcel,
)
from app.harness.hearing_room_context_v3 import assemble_hearing_room_context_v3
from app.schemas import (
    HearingEvidenceRequestsLlmOutput,
    HearingEvidenceSynthesisLlmOutput,
    HearingIntakeQuestionsLlmOutput,
    HearingIntakeSynthesisLlmOutput,
)


class _StrictOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: str


class _LooseOutput(BaseModel):
    value: str


class _Runner:
    def __init__(self, value: object) -> None:
        self.value = value
        self.calls: list[dict[str, object]] = []

    def invoke_structured(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(value=self.value, model="test-model")


def test_lcel_is_prompt_pipe_model_pipe_parser_object_flow() -> None:
    runner = _Runner({"value": "typed proposal"})

    flow = build_hearing_lcel(
        model_runner=runner,
        node_name="hearing_judge_v1",
        output_type=_StrictOutput,
    )

    assert isinstance(flow.runnable, RunnableSequence)
    assert isinstance(flow.prompt, ChatPromptTemplate)
    assert isinstance(flow.model, GovernedHearingModelAdapter)
    assert isinstance(flow.parser, PydanticOutputParser)
    assert flow.runnable.first is flow.prompt
    assert flow.runnable.middle == [flow.model]
    assert flow.runnable.last is flow.parser
    assert flow.model.tool_policy == EMPTY_HEARING_TOOL_POLICY == ()


def test_lcel_preserves_prompt_message_and_typed_parser_boundaries() -> None:
    runner = _Runner({"value": "typed proposal"})
    case_data = {
        "request": {
            "flow_schema_version": "hearing_flow.v2",
            "context_schema_version": "hearing_intake_context.v4",
            "case_id": "CASE_hearing",
            "workflow_id": "WORKFLOW_hearing",
            "stage_code": "INTAKE_QUESTIONS",
            "stage_sequence": 4,
            "stage_deadline_at": "2026-08-19T18:00:00+08:00",
            "source_refs": ["SOURCE_PRELUDE"],
            "prelude_authority_hash": "b" * 64,
            "case_fact_matrix": {
                "schema_version": "case_fact_matrix.v2",
                "case_id": "CASE_hearing",
                "matrix_id": "MATRIX_hearing",
                "matrix_version": 3,
                "content_hash": "a" * 64,
                "party_map": {
                    "initiator_role": "USER",
                    "respondent_role": "MERCHANT",
                },
                "case_overview": {
                    "neutral_summary": "双方对交付状态存在争议。",
                    "core_conflict": "商品是否实际交付。",
                    "summary_source_fact_ids": [],
                },
                "claims": {},
                "fact_rows": [],
            },
            "question_slots": [
                {"question_slot_id": "QUESTION_SLOT_01"},
            ],
        }
    }

    result = invoke_hearing_lcel(
        model_runner=runner,
        node_name="hearing_intake_questions",
        case_data=case_data,
        output_type=_StrictOutput,
    )

    assert result == _StrictOutput(value="typed proposal")
    assert len(runner.calls) == 1
    invocation = runner.calls[0]
    assert invocation["node_name"] == "hearing_intake_questions"
    assert invocation["output_type"] is _StrictOutput
    assert invocation["case_data"] == {
        "context_contract": "hearing_intake_context.v4",
        "agent_role": "INTAKE_OFFICER",
        "stage_mode": "QUESTION_GENERATION",
        "source_authority_hash": invocation["case_data"]["source_authority_hash"],
    }
    context_pack = invocation["context_pack"]
    assert context_pack.configuration_profile_key == "HEARING_INTAKE_CONTEXT_PACK_V4"
    assert [section.name for section in context_pack.sections] == [
        "hearing_intake_context_v4"
    ]
    context_payload = json.loads(context_pack.sections[0].content)
    assert context_payload["context_header"]["source_authority_hash"] == (
        invocation["case_data"]["source_authority_hash"]
    )
    assert context_payload["frozen_case_matrix_projection"]["source_matrix_id"] == (
        case_data["request"]["case_fact_matrix"]["matrix_id"]
    )
    assert "request" not in context_payload


def test_lcel_rejects_non_strict_output_schema() -> None:
    with pytest.raises(HearingLcelContractError, match="HEARING_OUTPUT_SCHEMA_NOT_STRICT"):
        GovernedHearingModelAdapter(
            model_runner=_Runner({"value": "x"}),
            node_name="hearing_judge_v1",
            output_type=_LooseOutput,
        )


def test_lcel_rejects_any_formal_tool_policy() -> None:
    with pytest.raises(HearingLcelContractError, match="HEARING_FORMAL_TOOL_POLICY_FORBIDDEN"):
        GovernedHearingModelAdapter(
            model_runner=_Runner({"value": "x"}),
            node_name="hearing_judge_v1",
            output_type=_StrictOutput,
            tool_policy=("domain.write",),  # type: ignore[arg-type]
        )


def test_lcel_strict_parser_rejects_unknown_fields() -> None:
    runner = _Runner({"value": "typed proposal", "formal_effect": True})

    with pytest.raises(ValueError, match="formal_effect"):
        invoke_hearing_lcel(
            model_runner=runner,
            node_name="hearing_jury_review",
            case_data={"request": {"case_id": "CASE_hearing"}},
            output_type=_StrictOutput,
        )


def test_hearing_evidence_context_is_ordered_single_source_v3() -> None:
    matrix = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": "CASE_hearing",
        "matrix_id": "MATRIX_hearing",
        "matrix_version": 3,
        "content_hash": "a" * 64,
        "fact_rows": [
            {
                "fact_id": "FACT_COVERED",
                "fact_target": "商品是否按约定送达",
                "requires_resolution": True,
                "origin": {"introduced_stage": "INITIATOR_INTAKE"},
                "evidence_coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
            },
            {
                "fact_id": "FACT_PARTIAL",
                "fact_target": "签收记录是否完整",
                "requires_resolution": True,
                "origin": {"introduced_stage": "INITIATOR_INTAKE"},
            },
            {
                "fact_id": "FACT_REVIEW",
                "fact_target": "签收图片是否需要人工复核",
                "requires_resolution": True,
                "origin": {"introduced_stage": "RESPONDENT_INTAKE"},
            },
            {
                "fact_id": "FACT_NOT_COVERED",
                "fact_target": "实际签收人身份",
                "requires_resolution": True,
                "origin": {"introduced_stage": "RESPONDENT_INTAKE"},
            },
            {
                "fact_id": "FACT_NEW",
                "fact_target": "代收是否获得授权",
                "requires_resolution": False,
                "origin": {"introduced_stage": "HEARING_CLARIFICATION"},
            },
            {
                "fact_id": "FACT_AGREED",
                "fact_target": "订单金额",
                "requires_resolution": False,
                "origin": {"introduced_stage": "INITIATOR_INTAKE"},
            },
        ],
    }
    evidence_source = {
        "request": {
            "flow_schema_version": "hearing_flow.v2",
            "case_id": "CASE_hearing",
            "workflow_id": "WORKFLOW_hearing",
            "stage_code": "EVIDENCE_REQUESTS",
            "stage_sequence": 7,
            "stage_deadline_at": "2026-08-19T18:10:00+08:00",
            "source_refs": ["SOURCE_PRELUDE"],
            "case_fact_matrix": matrix,
            "evidence_dossier": {
                "dossier_id": "DOSSIER_hearing",
                "dossier_version": 2,
                "dossier_status": "FROZEN",
                "fact_evidence_matrix": {
                    "schema_version": "fact_evidence_matrix.v3",
                    "matrix_status": "FROZEN",
                    "fact_coverage": [
                        {
                            "fact_id": "FACT_COVERED",
                            "coverage_status": "COVERED_BY_FROZEN_DOSSIER",
                            "evidence_ids": ["EVIDENCE_DELIVERY"],
                            "note": "已有正式证据绑定。",
                        },
                        {
                            "fact_id": "FACT_PARTIAL",
                            "coverage_status": (
                                "PARTIALLY_COVERED_BY_FROZEN_DOSSIER"
                            ),
                            "evidence_ids": ["EVIDENCE_SIGNING"],
                            "note": "已有部分正式证据绑定。",
                        },
                        {
                            "fact_id": "FACT_REVIEW",
                            "coverage_status": "REQUIRES_HUMAN_REVIEW",
                            "evidence_ids": ["EVIDENCE_IMAGE"],
                            "note": "已有证据，转人工复核。",
                        },
                        {
                            "fact_id": "FACT_NOT_COVERED",
                            "coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
                            "evidence_ids": [],
                            "note": "尚无正式证据绑定。",
                        },
                    ],
                    "links": [],
                },
                "evidence_summary": {"submitted": 2},
            },
        }
    }

    evidence = assemble_hearing_room_context_v3(
        "hearing_evidence_requests", evidence_source
    )

    assert list(evidence.payload) == [
        "context_header",
        "stage_contract",
        "authority_scope",
        "m2_fact_catalog",
        "fact_evidence_coverage_catalog",
        "uncovered_fact_catalog",
        "output_contract",
    ]
    assert evidence.payload["stage_contract"]["agent_role"] == "EVIDENCE_CLERK"
    assert [
        row["fact_id"] for row in evidence.payload["m2_fact_catalog"]["facts"]
    ] == [
        "FACT_COVERED",
        "FACT_PARTIAL",
        "FACT_REVIEW",
        "FACT_NOT_COVERED",
        "FACT_NEW",
    ]
    assert all(
        "evidence_coverage_status" not in row
        for row in evidence.payload["m2_fact_catalog"]["facts"]
    )
    assert evidence.payload["fact_evidence_coverage_catalog"] == [
        {
            "fact_id": "FACT_COVERED",
            "evidence_ids": ["EVIDENCE_DELIVERY"],
            "coverage_status": "COVERED_BY_FROZEN_DOSSIER",
        },
        {
            "fact_id": "FACT_PARTIAL",
            "evidence_ids": ["EVIDENCE_SIGNING"],
            "coverage_status": "PARTIALLY_COVERED_BY_FROZEN_DOSSIER",
        },
        {
            "fact_id": "FACT_REVIEW",
            "evidence_ids": ["EVIDENCE_IMAGE"],
            "coverage_status": "REQUIRES_HUMAN_REVIEW",
        },
        {
            "fact_id": "FACT_NOT_COVERED",
            "evidence_ids": [],
            "coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
        },
    ]
    assert evidence.payload["uncovered_fact_catalog"] == [
        {
            "fact_id": "FACT_NOT_COVERED",
            "uncovered_reason": "NOT_COVERED_BY_FROZEN_DOSSIER",
        },
        {
            "fact_id": "FACT_NEW",
            "uncovered_reason": "MISSING_FROM_FROZEN_E1",
        },
    ]
    assert "frozen_evidence_dossier" not in evidence.payload
    assert evidence.payload["output_contract"]["property_order"] == [
        "public_message",
        "requests",
    ]


def test_hearing_context_v3_rejects_retired_intake_nodes() -> None:
    with pytest.raises(
        HearingLcelContractError,
        match="HEARING_CONTEXT_NODE_UNSUPPORTED",
    ):
        assemble_hearing_room_context_v3(
            "hearing_intake_questions",
            {
                "request": {
                    "flow_schema_version": "hearing_flow.v2",
                    "case_id": "CASE_hearing",
                    "workflow_id": "WORKFLOW_hearing",
                    "stage_code": "EVIDENCE_REQUESTS",
                    "stage_sequence": 4,
                    "source_refs": [],
                    "case_fact_matrix": {},
                    "max_questions": 5,
                }
            },
        )


def test_hearing_synthesis_contexts_remove_legacy_duplicates_and_bind_evidence() -> None:
    matrix = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": "CASE_hearing",
        "matrix_id": "MATRIX_hearing",
        "matrix_version": 4,
        "content_hash": "b" * 64,
        "fact_rows": [{"fact_id": "FACT_DELIVERY", "fact_target": "约定送达时间"}],
    }
    evidence_flow = {
        "flow_schema_version": "hearing_flow.v2",
        "case_id": "CASE_hearing",
        "workflow_id": "WORKFLOW_hearing",
        "stage_code": "EVIDENCE_SYNTHESIS",
        "stage_sequence": 9,
        "stage_deadline_at": "2026-08-19T18:20:00+08:00",
        "source_refs": ["SOURCE_EVIDENCE_BATCH"],
    }
    requests = [
        {
            "request_id": "REQUEST_DELIVERY",
            "target_roles": ["USER"],
            "fact_ids": ["FACT_DELIVERY"],
            "requested_material": "送达记录",
            "verification_goal": "核验送达时间",
            "required": True,
        }
    ]
    evidence_file = {
        "evidence_id": "EVIDENCE_USER",
        "evidence_type": "DOCUMENT",
        "source_type": "USER",
        "parsed_text": "原始送达记录正文",
    }
    file_context = assemble_hearing_room_context_v3(
        "hearing_evidence_file_assessment",
        {
            "flow": evidence_flow,
            "participant_role": "USER",
            "batch_id": "BATCH_USER",
            "request_ids": ["REQUEST_DELIVERY"],
            "evidence_file": evidence_file,
            "requests": requests,
            "case_fact_matrix": matrix,
            "prior_fact_evidence_matrix": None,
        },
    )
    assert list(file_context.payload) == [
        "context_header",
        "stage_contract",
        "authority_scope",
        "frozen_case_matrix",
        "prior_evidence_matrix",
        "targeted_evidence_requests",
        "current_evidence_item",
        "output_contract",
    ]
    assert file_context.payload["current_evidence_item"]["evidence_file"] == evidence_file
    assert file_context.payload["targeted_evidence_requests"] == requests
    assert file_context.payload["output_contract"]["public_text_property"] is None

    synthesis = assemble_hearing_room_context_v3(
        "hearing_evidence_synthesis",
        {
            "request": {
                **evidence_flow,
                "requests": requests,
                "party_batches": [
                    {
                        "participant_role": "USER",
                        "terminal_status": "COMPLETED",
                        "submission_source": "PARTY_ACTION",
                        "batch_id": "BATCH_USER",
                        "request_ids": ["REQUEST_DELIVERY"],
                        "evidence": [evidence_file],
                    },
                    {
                        "participant_role": "MERCHANT",
                        "terminal_status": "ABSENT",
                        "submission_source": "AUTO_TIMEOUT",
                        "batch_id": "BATCH_MERCHANT",
                        "request_ids": [],
                        "evidence": [],
                    },
                ],
                "case_fact_matrix": matrix,
                "prior_fact_evidence_matrix": None,
            },
            "evidence_assessments": [
                {
                    "evidence_id": "EVIDENCE_USER",
                    "fact_links": [],
                    "summary": "送达记录已纳入核验。",
                    "requires_human_review": False,
                }
            ],
            "merged_fact_evidence_matrix": {
                "schema_version": "fact_evidence_matrix.v3",
                "case_id": "CASE_hearing",
                "matrix_status": "FROZEN",
                "links": [],
                "fact_coverage": [],
            },
        },
    )
    assert list(synthesis.payload) == [
        "context_header",
        "stage_contract",
        "authority_scope",
        "frozen_case_matrix",
        "targeted_evidence_requests",
        "party_evidence_batch_catalog",
        "prior_evidence_matrix",
        "evidence_assessment_catalog",
        "merged_evidence_matrix",
        "output_contract",
    ]
    projected_file = synthesis.payload["party_evidence_batch_catalog"][0]["evidence"][0]
    assert projected_file["evidence_id"] == "EVIDENCE_USER"
    assert "parsed_text" not in projected_file
    assert synthesis.payload["output_contract"]["property_order"] == [
        "public_message",
        "evidence_summary",
        "evidence_gaps",
    ]


def test_hearing_public_output_schemas_match_v3_public_first_contract() -> None:
    assert list(HearingIntakeQuestionsLlmOutput.model_fields) == [
        "public_message",
        "questions",
    ]
    assert list(HearingIntakeSynthesisLlmOutput.model_fields) == [
        "public_message",
        "case_fact_matrix_delta",
        "issue_mappings",
    ]
    assert list(HearingEvidenceRequestsLlmOutput.model_fields) == [
        "public_message",
        "requests",
    ]
    assert list(HearingEvidenceSynthesisLlmOutput.model_fields) == [
        "public_message",
        "evidence_summary",
        "evidence_gaps",
    ]
