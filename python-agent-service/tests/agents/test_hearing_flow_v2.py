from __future__ import annotations

import hashlib
import json
import threading
from contextvars import ContextVar
from types import SimpleNamespace

from fastapi.testclient import TestClient
import pytest

from app.agents.hearing_flow import HearingFlowWorkflows
from app.config import Settings
from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.main import create_app
from app.schemas import (
    CaseFactMatrixV2,
    FactEvidenceMatrixV2,
    HearingEvidenceFileAssessmentLlmOutput,
    HearingEvidenceRequestsRequest,
    HearingEvidenceSynthesisRequest,
    HearingAnswerBundleV1,
    HearingIntakeQuestionsLlmOutput,
    HearingIntakeQuestionsRequest,
    HearingIntakeSynthesisRequest,
    HearingJudgeV1Request,
    HearingJudgeV2Request,
    HearingJuryReviewRequest,
    HearingPartyStatementV1,
    TrialDossierV1,
    content_hash,
)


def _hash_payload(value: dict[str, object], field: str = "content_hash") -> str:
    payload = dict(value)
    payload.pop(field, None)
    return hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def _case_matrix() -> CaseFactMatrixV2:
    payload = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": "CASE_hearing_flow",
        "matrix_id": "CASE_MATRIX_hearing_flow",
        "matrix_version": 3,
        "matrix_kind": "HEARING_CLARIFIED_FROZEN",
        "parent_ref": None,
        "content_hash": "0" * 64,
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "source_refs": ["SOURCE_hearing_clarification"],
        "case_overview": {
            "neutral_summary": "用户称未收到商品，商家称物流已签收。",
            "core_conflict": "包裹是否实际交付。",
            "summary_source_fact_ids": ["FACT_DELIVERY", "FACT_RECIPIENT"],
        },
        "claims": {
            "initiator_claim": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                "requested_amount": 100.0,
                "requested_items": "商品",
                "reason_summary": "未收到商品。",
                "position_summary": "用户要求退款。",
                "source_refs": ["SOURCE_USER"],
            },
            "respondent_reported_by_initiator": None,
            "respondent_direct": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position_summary": "商家认为已经签收。",
                "alternative_proposal": None,
                "source_refs": ["SOURCE_MERCHANT"],
            },
            "claim_conflict": "双方对实际交付有争议。",
        },
        "fact_rows": [
            {
                "fact_id": "FACT_DELIVERY",
                "category": "LOGISTICS",
                "fact_target": "物流系统记录包裹已签收",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": ["SOURCE_USER"],
                },
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户否认本人收到。",
                        "asserted_value": "未收到",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_USER"],
                    },
                    "MERCHANT": {
                        "stance": "CONFIRM",
                        "position_summary": "商家确认物流已签收。",
                        "asserted_value": "已签收",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_MERCHANT"],
                    },
                },
                "party_alignment": {
                    "status": "CONTESTED",
                    "agreed_statement": None,
                    "conflict_summary": "是否实际交付存在争议。",
                },
                "requires_resolution": True,
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "COVERED_BY_FROZEN_DOSSIER",
            },
            {
                "fact_id": "FACT_RECIPIENT",
                "category": "LOGISTICS",
                "fact_target": "签收人身份在庭审澄清阶段首次提出",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "HEARING_CLARIFICATION",
                    "source_refs": ["SOURCE_hearing_clarification"],
                },
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户称签收人不是本人。",
                        "asserted_value": "非本人",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_hearing_clarification"],
                    },
                    "MERCHANT": {
                        "stance": "NOT_ADDRESSED",
                        "position_summary": "商家尚未回应。",
                        "asserted_value": None,
                        "source_type": "NO_DIRECT_POSITION",
                        "source_refs": [],
                    },
                },
                "party_alignment": {
                    "status": "ONE_SIDED",
                    "agreed_statement": None,
                    "conflict_summary": "商家尚未回应签收人身份。",
                },
                "requires_resolution": True,
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
            },
        ],
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": "USER",
            "source_stage": "HEARING_CLARIFICATION",
            "latest_source_ref": "SOURCE_hearing_clarification",
            "source_context_hash": "a" * 64,
        },
        "fact_indexes": {
            "not_computed_fact_ids": [],
            "agreed_fact_ids": [],
            "partially_agreed_fact_ids": [],
            "contested_fact_ids": ["FACT_DELIVERY"],
            "one_sided_fact_ids": ["FACT_RECIPIENT"],
            "unresolved_fact_ids": [],
            "core_fact_ids": ["FACT_DELIVERY", "FACT_RECIPIENT"],
            "requires_resolution_fact_ids": ["FACT_DELIVERY", "FACT_RECIPIENT"],
        },
    }
    normalized = CaseFactMatrixV2.model_validate(payload).model_dump(mode="json")
    normalized["content_hash"] = _hash_payload(normalized)
    return CaseFactMatrixV2.model_validate(normalized)


def _prehearing_case_matrix() -> CaseFactMatrixV2:
    payload = _case_matrix().model_dump(mode="json")
    payload.update(
        {
            "matrix_id": "CASE_MATRIX_prehearing",
            "matrix_version": 2,
            "matrix_kind": "BILATERAL_FROZEN",
            "parent_ref": None,
            "source_refs": ["SOURCE_USER", "SOURCE_MERCHANT"],
            "case_overview": {
                "neutral_summary": "用户称未收到商品，商家称物流已签收。",
                "core_conflict": "包裹是否实际交付。",
                "summary_source_fact_ids": ["FACT_DELIVERY"],
            },
            "fact_rows": [payload["fact_rows"][0]],
            "generation_ref": {
                "actor_role": "MERCHANT",
                "source_stage": "RESPONDENT_INTAKE",
                "latest_source_ref": "SOURCE_MERCHANT",
                "source_context_hash": "b" * 64,
            },
            "fact_indexes": {
                "not_computed_fact_ids": [],
                "agreed_fact_ids": [],
                "partially_agreed_fact_ids": [],
                "contested_fact_ids": ["FACT_DELIVERY"],
                "one_sided_fact_ids": [],
                "unresolved_fact_ids": [],
                "core_fact_ids": ["FACT_DELIVERY"],
                "requires_resolution_fact_ids": ["FACT_DELIVERY"],
            },
            "content_hash": "0" * 64,
        }
    )
    payload["content_hash"] = _hash_payload(payload)
    return CaseFactMatrixV2.model_validate(payload)


def _adjudication_case_matrix() -> CaseFactMatrixV2:
    parent = _prehearing_case_matrix()
    payload = _case_matrix().model_dump(mode="json")
    payload["parent_ref"] = {
        "matrix_id": parent.matrix_id,
        "matrix_version": parent.matrix_version,
        "content_hash": parent.content_hash,
    }
    payload["content_hash"] = "0" * 64
    payload["content_hash"] = _hash_payload(payload)
    return CaseFactMatrixV2.model_validate(payload)


def _evidence_matrix(
    *,
    frozen: bool = False,
    prehearing_binding: bool = False,
    case_matrix: CaseFactMatrixV2 | None = None,
) -> FactEvidenceMatrixV2:
    case = case_matrix or _case_matrix()
    bound_case = _prehearing_case_matrix() if prehearing_binding else case
    payload = {
        "schema_version": "fact_evidence_matrix.v2",
        "case_id": case.case_id,
        "matrix_id": "FACT_EVIDENCE_MATRIX_prior",
        "matrix_version": 2,
        "matrix_status": "FROZEN" if frozen else "WORKING",
        "parent_ref": None,
        "case_fact_matrix_id": bound_case.matrix_id,
        "case_fact_matrix_version": bound_case.matrix_version,
        "case_fact_matrix_hash": bound_case.content_hash,
        "content_hash": "0" * 64,
        "source_refs": ["BATCH_prior"],
        "links": [
            {
                "fact_id": "FACT_DELIVERY",
                "evidence_id": "EVIDENCE_old",
                "relation": "SUPPORTS",
                "reason": "旧物流记录显示已签收。",
                "confidence": 0.7,
                "source_batch_id": "BATCH_prior",
            }
        ],
        "fact_coverage": [
            {
                "fact_id": "FACT_DELIVERY",
                "coverage_status": "COVERED_BY_FROZEN_DOSSIER",
                "evidence_ids": ["EVIDENCE_old"],
                "note": "已由旧冻结卷宗覆盖。",
            },
            *(
                []
                if prehearing_binding
                else [
                    {
                        "fact_id": "FACT_RECIPIENT",
                        "coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
                        "evidence_ids": [],
                        "note": "庭审新增事实尚未覆盖。",
                    }
                ]
            ),
        ],
    }
    payload["content_hash"] = _hash_payload(payload)
    return FactEvidenceMatrixV2.model_validate(payload)


def _trial_dossier(
    *,
    answer_schemas: tuple[str, str] = (
        "hearing_answer_bundle.v1",
        "hearing_answer_bundle.v1",
    ),
) -> TrialDossierV1:
    case_matrix = _adjudication_case_matrix()
    evidence_matrix = _evidence_matrix(frozen=True, case_matrix=case_matrix)
    question_set_id = "HEARING_QUESTION_SET_hearing_flow"
    request_set_id = "HEARING_REQUEST_SET_hearing_flow"
    payload = {
        "schema_version": "trial_dossier.v1",
        "trial_dossier_id": "TRIAL_DOSSIER_hearing_flow",
        "case_id": "CASE_hearing_flow",
        "frozen_at": "2026-07-15T21:30:00+08:00",
        "case_matrix_version": case_matrix.matrix_version,
        "case_matrix_hash": case_matrix.content_hash,
        "case_fact_matrix": case_matrix.model_dump(mode="json"),
        "evidence_matrix_version": evidence_matrix.matrix_version,
        "evidence_matrix_hash": evidence_matrix.content_hash,
        "fact_evidence_matrix": evidence_matrix.model_dump(mode="json"),
        "question_set_id": question_set_id,
        "question_set": {
            "schema_version": "hearing_question_set.v1",
            "question_set_id": question_set_id,
            "issue_set_id": question_set_id,
            "case_id": case_matrix.case_id,
            "case_matrix_version": case_matrix.parent_ref.matrix_version,
            "case_matrix_hash": case_matrix.parent_ref.content_hash,
            "questions": [],
        },
        "answer_bundles": [],
        "request_set_id": request_set_id,
        "evidence_request_set": {
            "schema_version": "hearing_evidence_request_set.v1",
            "request_set_id": request_set_id,
            "case_matrix_version": case_matrix.matrix_version,
            "case_matrix_hash": case_matrix.content_hash,
            "requests": [],
        },
        "evidence_batches": [
            {
                "schema_version": "hearing_evidence_batch.v1",
                "request_set_id": request_set_id,
                "participant_role": role,
                "submission_status": "SUBMITTED",
                "request_ids": [],
                "evidence_ids": [],
            }
            for role in ("USER", "MERCHANT")
        ],
        "policy_rules": [
            {
                "policy_id": "POLICY_DELIVERY_PROOF_V1",
                "rule_code": "DELIVERY_PROOF",
                "rule_version": 1,
                "rule_name": "签收争议举证规则",
                "rule_scope": "DELIVERY_DISPUTE",
                "rule_status": "ACTIVE",
                "effective_from": "2020-01-01T00:00:00Z",
                "effective_to": None,
                "priority": 100,
                "conditions": {"requires_delivery_proof": True},
                "outcome": {"requires_human_review": True},
                "source_document": {"section": "DELIVERY_PROOF"},
            }
        ],
        "content_hash": "0" * 64,
    }
    for role, schema_version in zip(
        ("USER", "MERCHANT"), answer_schemas, strict=True
    ):
        common = {
            "schema_version": schema_version,
            "question_set_id": question_set_id,
            "participant_id": f"{role.lower()}-local",
            "participant_role": role,
            "submission_status": "SUBMITTED",
            "submitted_at": "2026-07-15T21:10:00+08:00",
            "source_message_ids": [f"MESSAGE_{role}_STATEMENT"],
        }
        if schema_version == "hearing_party_statement.v1":
            common.update(
                {
                    "issue_set_id": question_set_id,
                    "statement_text": f"{role} 围绕全部争议点作出完整自然语言陈述。",
                }
            )
        else:
            common["answers"] = []
        payload["answer_bundles"].append(common)
    payload["content_hash"] = _hash_payload(payload)
    return TrialDossierV1.model_validate(payload)


def _base(stage_code: str, stage_sequence: int) -> dict[str, object]:
    return {
        "flow_schema_version": "hearing_flow.v2",
        "case_id": "CASE_hearing_flow",
        "workflow_id": "WORKFLOW_hearing_flow",
        "stage_code": stage_code,
        "stage_sequence": stage_sequence,
        "stage_deadline_at": "2026-07-15T21:00:00+08:00",
        "source_refs": [f"SOURCE_{stage_code}"],
    }


class QueueRunner:
    def __init__(self, outputs: dict[str, object]) -> None:
        self.outputs = outputs
        self.calls: list[dict[str, object]] = []

    def invoke_structured(self, **kwargs):
        self.calls.append(kwargs)
        output_type = kwargs["output_type"]
        configured = self.outputs[kwargs["node_name"]]
        value = configured(kwargs) if callable(configured) else configured
        return SimpleNamespace(
            value=output_type.model_validate(value),
            model="test-model",
        )


class ParallelEvidenceRunner(QueueRunner):
    def __init__(self, outputs: dict[str, object], expected_files: int) -> None:
        super().__init__(outputs)
        self.assessment_barrier = threading.Barrier(expected_files)

    def invoke_structured(self, **kwargs):
        if kwargs["node_name"] == "hearing_evidence_file_assessment":
            self.assessment_barrier.wait(timeout=2)
        return super().invoke_structured(**kwargs)


def test_case_matrix_supports_hearing_clarification_coverage() -> None:
    matrix = _case_matrix()

    assert matrix.matrix_kind == "HEARING_CLARIFIED_FROZEN"
    assert matrix.fact_rows[1].origin.introduced_stage == "HEARING_CLARIFICATION"
    assert matrix.fact_rows[1].evidence_coverage_status == "NOT_COVERED_BY_FROZEN_DOSSIER"


def test_question_and_file_assessment_outputs_reject_ambiguous_empty_or_duplicate_rows() -> None:
    with pytest.raises(ValueError, match="at least 1 item"):
        HearingIntakeQuestionsLlmOutput.model_validate(
            {"questions": [], "public_message": "没有问题。"}
        )

    with pytest.raises(ValueError, match="cannot repeat a fact link"):
        HearingEvidenceFileAssessmentLlmOutput.model_validate(
            {
                "fact_links": [
                    {
                        "fact_id": "FACT_DELIVERY",
                        "relation": relation,
                        "reason": "同一文件不能重复关联同一事实。",
                        "confidence": 0.6,
                    }
                    for relation in ("SUPPORTS", "OPPOSES")
                ],
                "summary": "重复关联。",
            }
        )


def test_trial_dossier_uses_the_java_canonical_frozen_payload_and_hash() -> None:
    dossier = _trial_dossier()

    assert set(TrialDossierV1.model_fields) == {
        "schema_version",
        "trial_dossier_id",
        "case_id",
        "frozen_at",
        "case_matrix_version",
        "case_matrix_hash",
        "case_fact_matrix",
        "evidence_matrix_version",
        "evidence_matrix_hash",
        "fact_evidence_matrix",
        "question_set_id",
        "question_set",
        "answer_bundles",
        "request_set_id",
        "evidence_request_set",
        "evidence_batches",
        "policy_rules",
        "content_hash",
    }
    assert dossier.policy_rules[0].rule_code == "DELIVERY_PROOF"
    assert dossier.policy_rules[0].rule_version == 1
    assert dossier.content_hash == _hash_payload(dossier.model_dump(mode="json"))

    mismatched = dossier.model_dump(mode="json")
    mismatched["case_matrix_version"] += 1
    mismatched["content_hash"] = _hash_payload(mismatched)
    with pytest.raises(ValueError, match="case matrix version/hash binding"):
        TrialDossierV1.model_validate(mismatched)

    corrupted = dossier.model_dump(mode="json")
    corrupted["content_hash"] = "f" * 64
    with pytest.raises(ValueError, match="content hash is invalid"):
        TrialDossierV1.model_validate(corrupted)


def test_trial_dossier_accepts_bilateral_natural_language_statements() -> None:
    dossier = _trial_dossier(
        answer_schemas=(
            "hearing_party_statement.v1",
            "hearing_party_statement.v1",
        )
    )

    assert all(
        isinstance(submission, HearingPartyStatementV1)
        for submission in dossier.answer_bundles
    )
    assert {submission.participant_role for submission in dossier.answer_bundles} == {
        "USER",
        "MERCHANT",
    }


def test_trial_dossier_accepts_mixed_legacy_answer_and_natural_language_statement() -> None:
    dossier = _trial_dossier(
        answer_schemas=(
            "hearing_answer_bundle.v1",
            "hearing_party_statement.v1",
        )
    )

    assert isinstance(dossier.answer_bundles[0], HearingAnswerBundleV1)
    assert isinstance(dossier.answer_bundles[1], HearingPartyStatementV1)


def test_trial_dossier_rejects_statement_bound_to_another_issue_set() -> None:
    dossier = _trial_dossier(
        answer_schemas=(
            "hearing_party_statement.v1",
            "hearing_party_statement.v1",
        )
    )
    payload = dossier.model_dump(mode="json")
    payload["answer_bundles"][0]["issue_set_id"] = "HEARING_QUESTION_SET_other"
    payload["content_hash"] = _hash_payload(payload)

    with pytest.raises(ValueError, match="party statement issue set binding"):
        TrialDossierV1.model_validate(payload)


def test_trial_dossier_accepts_timed_out_statement_without_text() -> None:
    dossier = _trial_dossier(
        answer_schemas=(
            "hearing_party_statement.v1",
            "hearing_party_statement.v1",
        )
    )
    payload = dossier.model_dump(mode="json")
    payload["answer_bundles"][0].update(
        {
            "submission_status": "AUTO_TIMEOUT",
            "statement_text": None,
            "source_message_ids": [],
        }
    )
    payload["content_hash"] = _hash_payload(payload)

    validated = TrialDossierV1.model_validate(payload)

    assert validated.answer_bundles[0].submission_status == "AUTO_TIMEOUT"
    assert validated.answer_bundles[0].statement_text is None


def test_trial_dossier_rejects_submitted_statement_without_text() -> None:
    dossier = _trial_dossier(
        answer_schemas=(
            "hearing_party_statement.v1",
            "hearing_party_statement.v1",
        )
    )
    payload = dossier.model_dump(mode="json")
    payload["answer_bundles"][0]["statement_text"] = None
    payload["content_hash"] = _hash_payload(payload)

    with pytest.raises(ValueError, match="requires statement_text"):
        TrialDossierV1.model_validate(payload)


def test_intake_synthesis_deterministically_merges_a_bounded_fact_delta() -> None:
    runner = QueueRunner(
        {
            "hearing_intake_questions": {
                "questions": [
                    {
                        "fact_ids": ["FACT_DELIVERY"],
                        "issue_statement": f"争议点 {index}",
                        "party_prompts": {
                            "USER": f"请用户围绕争议点 {index} 自由陈述。",
                            "MERCHANT": f"请商家围绕争议点 {index} 自由陈述。",
                        },
                    }
                    for index in range(5)
                ],
                "public_message": "请双方围绕以下争议点作完整陈述。",
            },
            "hearing_intake_synthesis": {
                "case_fact_matrix_delta": {
                    "schema_version": "hearing_case_fact_matrix.delta.v1",
                    "neutral_summary": "用户称签收人并非本人，商家称包裹由驿站代收。",
                    "core_conflict": "包裹是否由有权代收人实际接收。",
                    "fact_rows": [
                        {
                            "fact_key": "NEW_RECIPIENT",
                            "category": "LOGISTICS",
                            "fact_target": "庭审回答首次提出包裹由驿站代收",
                            "materiality": "CORE",
                            "positions": {
                                "USER": {
                                    "stance": "DENY",
                                    "position_summary": "用户称签收人不是本人。",
                                    "asserted_value": "非本人签收",
                                },
                                "MERCHANT": {
                                    "stance": "CONFIRM",
                                    "position_summary": "商家称包裹由驿站代收。",
                                    "asserted_value": "驿站代收",
                                },
                            },
                            "conflict_summary": "双方对代收是否构成实际交付存在争议。",
                        }
                    ],
                    "summary_source_fact_keys": [
                        "FACT_DELIVERY",
                        "NEW_RECIPIENT",
                    ],
                },
                "issue_mappings": [],
                "public_message": "综合庭前矩阵与双方回答，包裹交付记录存在，但双方仍对实际签收人及代收效力存在争议。",
            },
        }
    )
    workflows = HearingFlowWorkflows(runner)
    question_request = HearingIntakeQuestionsRequest.model_validate(
        {
            **_base("INTAKE_QUESTIONS", 1),
            "case_fact_matrix": _prehearing_case_matrix(),
            "max_questions": 5,
        }
    )
    questions = workflows.intake_questions(question_request)
    runner.outputs["hearing_intake_synthesis"]["issue_mappings"] = [
        {
            "issue_id": question.issue_id,
            "party_positions": {
                "USER": {
                    "coverage": "ADDRESSED",
                    "position_summary": "用户称签收人并非本人。",
                },
                "MERCHANT": {
                    "coverage": "ADDRESSED",
                    "position_summary": "商家称包裹由驿站代收。",
                },
            },
        }
        for question in questions.questions
    ]
    synthesis_request = HearingIntakeSynthesisRequest.model_validate(
        {
            **_base("INTAKE_SYNTHESIS", 2),
            "questions": questions.questions,
            "party_submissions": [
                {
                    "participant_id": "user-local",
                    "participant_role": "USER",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "source_refs": ["ACTION_USER_ANSWER"],
                    "statement_text": "物流虽然显示签收，但签收人不是我本人。",
                    "submission": {"source_message_ids": ["MESSAGE_USER_STATEMENT"]},
                },
                {
                    "participant_id": "merchant-local",
                    "participant_role": "MERCHANT",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "source_refs": ["ACTION_MERCHANT_ANSWER"],
                    "statement_text": "包裹已经按约送到驿站并由驿站代收。",
                    "submission": {"source_message_ids": ["MESSAGE_MERCHANT_STATEMENT"]},
                },
            ],
            "case_fact_matrix": _prehearing_case_matrix(),
        }
    )
    synthesis = workflows.intake_synthesis(synthesis_request)

    assert len(questions.questions) == 5
    assert all(item.fact_ids == ["FACT_DELIVERY"] for item in questions.questions)
    assert all(item.target_roles == ["USER", "MERCHANT"] for item in questions.questions)
    assert all(item.question_id == item.issue_id for item in questions.questions)
    assert questions.questions[0].party_prompts.USER.startswith("请用户")
    matrix = synthesis.case_fact_matrix
    assert matrix.matrix_version == 3
    assert matrix.matrix_kind == "HEARING_CLARIFIED_FROZEN"
    assert matrix.parent_ref.matrix_id == _prehearing_case_matrix().matrix_id
    assert matrix.generation_ref.actor_role == "SYSTEM"
    assert matrix.content_hash == _hash_payload(matrix.model_dump(mode="json"))
    new_row = next(
        row for row in matrix.fact_rows if row.origin.introduced_stage == "HEARING_CLARIFICATION"
    )
    assert new_row.fact_id.startswith("FACT_HEARING_")
    assert new_row.evidence_coverage_status == "NOT_COVERED_BY_FROZEN_DOSSIER"
    assert new_row.fact_id in matrix.case_overview.summary_source_fact_ids
    assert new_row.fact_id in matrix.fact_indexes.requires_resolution_fact_ids
    assert synthesis.public_message == (
        "综合庭前矩阵与双方回答，包裹交付记录存在，但双方仍对实际签收人及代收效力存在争议。"
    )
    assert new_row.positions.USER.source_refs == [
        "ACTION_USER_ANSWER",
        "MESSAGE_USER_STATEMENT",
    ]
    assert new_row.positions.MERCHANT.source_refs == [
        "ACTION_MERCHANT_ANSWER",
        "MESSAGE_MERCHANT_STATEMENT",
    ]
    assert synthesis.issue_mappings[0].party_positions.USER.statement_refs == [
        "ACTION_USER_ANSWER",
        "MESSAGE_USER_STATEMENT",
    ]
    assert synthesis.issue_mappings[0].party_positions.MERCHANT.statement_refs == [
        "ACTION_MERCHANT_ANSWER",
        "MESSAGE_MERCHANT_STATEMENT",
    ]
    assert runner.calls[-1]["case_data"]["party_statements"][0]["statement_text"] == (
        "物流虽然显示签收，但签收人不是我本人。"
    )
    assert any(point.fact_ids == [new_row.fact_id] for point in synthesis.dispute_points)
    repeated = workflows.intake_synthesis(synthesis_request)
    repeated_new_row = next(
        row
        for row in repeated.case_fact_matrix.fact_rows
        if row.origin.introduced_stage == "HEARING_CLARIFICATION"
    )
    assert repeated_new_row.fact_id == new_row.fact_id
    assert repeated.case_fact_matrix.content_hash == matrix.content_hash
    assert set(runner.calls[-1]["output_type"].model_fields) == {
        "case_fact_matrix_delta",
        "issue_mappings",
        "public_message",
    }


def test_intake_synthesis_rejects_a_position_without_a_substantive_party_answer() -> None:
    runner = QueueRunner(
        {
            "hearing_intake_synthesis": {
                "case_fact_matrix_delta": {
                    "schema_version": "hearing_case_fact_matrix.delta.v1",
                    "neutral_summary": "用户本轮没有提交实际回答。",
                    "core_conflict": "包裹是否实际交付。",
                    "fact_rows": [
                        {
                            "fact_key": "FACT_DELIVERY",
                            "category": "LOGISTICS",
                            "fact_target": "物流系统记录包裹已签收",
                            "materiality": "CORE",
                            "positions": {
                                "USER": {
                                    "stance": "CONFIRM",
                                    "position_summary": "模型臆造的确认陈述。",
                                    "asserted_value": "已签收",
                                }
                            },
                        }
                    ],
                    "summary_source_fact_keys": ["FACT_DELIVERY"],
                },
                "issue_mappings": [],
                "public_message": "不应生成。",
            }
        }
    )
    request = HearingIntakeSynthesisRequest.model_validate(
        {
            **_base("INTAKE_SYNTHESIS", 2),
            "party_submissions": [
                {
                    "participant_id": "user-local",
                    "participant_role": "USER",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "source_refs": ["ACTION_USER_EMPTY"],
                    "submission": {"answers": []},
                },
                {
                    "participant_id": "merchant-local",
                    "participant_role": "MERCHANT",
                    "terminal_status": "TIMED_OUT",
                    "submission_source": "AUTO_TIMEOUT",
                    "source_refs": ["ACTION_MERCHANT_TIMEOUT"],
                    "submission": {},
                },
            ],
            "case_fact_matrix": _prehearing_case_matrix(),
        }
    )

    with pytest.raises(AgentOutputSchemaError, match="substantive party answer"):
        HearingFlowWorkflows(runner).intake_synthesis(request)


def test_intake_synthesis_maps_the_legacy_java_answer_envelope_as_party_statements() -> None:
    issue_id = "HEARING_ISSUE_legacy"
    runner = QueueRunner(
        {
            "hearing_intake_synthesis": {
                "case_fact_matrix_delta": {
                    "schema_version": "hearing_case_fact_matrix.delta.v1",
                    "neutral_summary": "双方仍对包裹是否实际交付存在争议。",
                    "core_conflict": "包裹是否实际交付。",
                    "fact_rows": [],
                    "summary_source_fact_keys": ["FACT_DELIVERY"],
                },
                "issue_mappings": [
                    {
                        "issue_id": issue_id,
                        "party_positions": {
                            "USER": {
                                "coverage": "ADDRESSED",
                                "position_summary": "用户称本人没有收到包裹。",
                            },
                            "MERCHANT": {
                                "coverage": "PARTIALLY_ADDRESSED",
                                "position_summary": "商家仅说明物流状态为已签收。",
                            },
                        },
                    }
                ],
                "public_message": "双方均已陈述，但实际交付仍待证据核验。",
            }
        }
    )
    request = HearingIntakeSynthesisRequest.model_validate(
        {
            **_base("INTAKE_SYNTHESIS", 2),
            "questions": [
                {
                    "question_id": issue_id,
                    "target_roles": ["USER", "MERCHANT"],
                    "fact_ids": ["FACT_DELIVERY"],
                    "question_text": "请双方说明包裹是否实际交付。",
                }
            ],
            "party_submissions": [
                {
                    "participant_id": "user-local",
                    "participant_role": "USER",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "source_refs": ["ACTION_USER_LEGACY"],
                    "submission": {
                        "answers": [
                            {
                                "question_id": issue_id,
                                "answer_text": "物流显示签收，但我本人没有收到包裹。",
                            }
                        ],
                        "source_message_ids": ["MESSAGE_USER_LEGACY"],
                    },
                },
                {
                    "participant_id": "merchant-local",
                    "participant_role": "MERCHANT",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "source_refs": ["ACTION_MERCHANT_LEGACY"],
                    "submission": {
                        "answers": [
                            {
                                "questionId": issue_id,
                                "answerText": "承运方记录的状态为已签收。",
                            }
                        ]
                    },
                },
            ],
            "case_fact_matrix": _prehearing_case_matrix(),
        }
    )

    result = HearingFlowWorkflows(runner).intake_synthesis(request)

    statements = runner.calls[-1]["case_data"]["party_statements"]
    assert statements[0]["statement_text"] == "物流显示签收，但我本人没有收到包裹。"
    assert statements[1]["statement_text"] == "承运方记录的状态为已签收。"
    assert result.issue_mappings[0].issue_id == issue_id
    assert result.issue_mappings[0].issue_statement == "请双方说明包裹是否实际交付。"
    assert result.issue_mappings[0].party_positions.USER.statement_refs == [
        "ACTION_USER_LEGACY",
        "MESSAGE_USER_LEGACY",
    ]


def test_intake_synthesis_requires_two_distinct_participant_ids() -> None:
    payload = {
        **_base("INTAKE_SYNTHESIS", 2),
        "party_submissions": [
            {
                "participant_id": "participant-shared",
                "participant_role": "USER",
                "terminal_status": "TIMED_OUT",
                "submission_source": "AUTO_TIMEOUT",
                "source_refs": ["ACTION_USER_TIMEOUT"],
            },
            {
                "participant_id": "participant-shared",
                "participant_role": "MERCHANT",
                "terminal_status": "TIMED_OUT",
                "submission_source": "AUTO_TIMEOUT",
                "source_refs": ["ACTION_MERCHANT_TIMEOUT"],
            },
        ],
        "case_fact_matrix": _prehearing_case_matrix(),
    }

    with pytest.raises(ValueError, match="two distinct participant_id values"):
        HearingIntakeSynthesisRequest.model_validate(payload)


def test_intake_synthesis_promotes_legacy_nested_participant_ids() -> None:
    payload = {
        **_base("INTAKE_SYNTHESIS", 2),
        "party_submissions": [
            {
                "participant_role": "USER",
                "terminal_status": "COMPLETED",
                "submission_source": "PARTY_ACTION",
                "source_refs": ["ACTION_USER_STATEMENT"],
                "submission": {
                    "participant_id": "user-local",
                    "statement_text": "The parcel arrived after the promised date.",
                },
            },
            {
                "participant_id": "   ",
                "participant_role": "MERCHANT",
                "terminal_status": "COMPLETED",
                "submission_source": "PARTY_ACTION",
                "source_refs": ["ACTION_MERCHANT_STATEMENT"],
                "submission": {
                    "participant_id": "merchant-local",
                    "statement_text": "The carrier delay was outside our control.",
                },
            },
        ],
        "case_fact_matrix": _prehearing_case_matrix(),
    }

    request = HearingIntakeSynthesisRequest.model_validate(payload)

    assert [item.participant_id for item in request.party_submissions] == [
        "user-local",
        "merchant-local",
    ]


def test_party_submission_does_not_derive_participant_id_from_role() -> None:
    payload = {
        **_base("INTAKE_SYNTHESIS", 2),
        "party_submissions": [
            {
                "participant_id": "   ",
                "participant_role": "USER",
                "terminal_status": "TIMED_OUT",
                "submission_source": "AUTO_TIMEOUT",
                "source_refs": ["ACTION_USER_TIMEOUT"],
            },
            {
                "participant_id": "merchant-local",
                "participant_role": "MERCHANT",
                "terminal_status": "TIMED_OUT",
                "submission_source": "AUTO_TIMEOUT",
                "source_refs": ["ACTION_MERCHANT_TIMEOUT"],
            },
        ],
        "case_fact_matrix": _prehearing_case_matrix(),
    }

    with pytest.raises(ValueError, match="participant_id"):
        HearingIntakeSynthesisRequest.model_validate(payload)


def test_evidence_requests_accept_the_prehearing_frozen_matrix_for_new_facts() -> None:
    runner = QueueRunner(
        {
            "hearing_evidence_requests": {
                "requests": [
                    {
                        "target_roles": ["USER", "MERCHANT"],
                        "fact_ids": ["FACT_RECIPIENT"],
                        "requested_material": "能够核对实际签收人身份的原始记录",
                        "verification_goal": "核验实际签收人与代收授权",
                        "required": True,
                    }
                ],
                "public_message": "请商家围绕庭审新增的签收人事实定向补证。",
            }
        }
    )
    request = HearingEvidenceRequestsRequest.model_validate(
        {
            **_base("EVIDENCE_REQUESTS", 3),
            "case_fact_matrix": _adjudication_case_matrix(),
            "evidence_dossier": {
                "dossier_id": "EVIDENCE_DOSSIER_prehearing",
                "dossier_version": 1,
                "dossier_status": "FROZEN",
                "fact_evidence_matrix": _evidence_matrix(frozen=True, prehearing_binding=True),
                "evidence_summary": {},
                "evidence_gaps": [],
            },
        }
    )

    result = HearingFlowWorkflows(runner).evidence_requests(request)

    assert result.requests[0].fact_ids == ["FACT_RECIPIENT"]
    assert result.requests[0].target_roles == ["USER", "MERCHANT"]
    assert result.requests[0].verification_goal == "核验实际签收人与代收授权"


def test_evidence_requests_reject_a_matrix_not_bound_to_current_or_parent_case_matrix() -> None:
    request = HearingEvidenceRequestsRequest.model_validate(
        {
            **_base("EVIDENCE_REQUESTS", 3),
            "case_fact_matrix": _adjudication_case_matrix(),
            "evidence_dossier": {
                "dossier_id": "EVIDENCE_DOSSIER_wrong_binding",
                "dossier_version": 1,
                "dossier_status": "FROZEN",
                "fact_evidence_matrix": _evidence_matrix(
                    frozen=True,
                    case_matrix=_case_matrix(),
                ),
                "evidence_summary": {},
                "evidence_gaps": [],
            },
        }
    )
    runner = QueueRunner({})

    with pytest.raises(AgentOutputSchemaError, match="current case matrix or its direct parent"):
        HearingFlowWorkflows(runner).evidence_requests(request)

    assert runner.calls == []


def test_evidence_synthesis_consumes_complete_batch_and_prior_matrix() -> None:
    def assessment(kwargs: dict[str, object]) -> dict[str, object]:
        evidence_id = kwargs["case_data"]["evidence_file"]["evidence_id"]
        if evidence_id == "EVIDENCE_user_new":
            return {
                "fact_links": [
                    {
                        "fact_id": "FACT_RECIPIENT",
                        "relation": "SUPPORTS",
                        "reason": "图片显示签收人为他人。",
                        "confidence": 0.7,
                    }
                ],
                "summary": "用户补充签收图片。",
                "requires_human_review": False,
            }
        return {
            "fact_links": [
                {
                    "fact_id": "FACT_RECIPIENT",
                    "relation": "OPPOSES",
                    "reason": "驿站记录称已通知本人。",
                    "confidence": 0.65,
                }
            ],
            "summary": "商家补充驿站记录。",
            "requires_human_review": True,
        }

    runner = ParallelEvidenceRunner(
        {
            "hearing_evidence_file_assessment": assessment,
            "hearing_evidence_synthesis": {
                "evidence_summary": {"new_file_count": 2},
                "evidence_gaps": ["签收通知原始日志仍待人工核对。"],
                "public_message": "已结合旧矩阵和双方新材料完成全量证据整理。",
            },
        },
        expected_files=2,
    )
    request = HearingEvidenceSynthesisRequest.model_validate(
        {
            **_base("EVIDENCE_SYNTHESIS", 4),
            "requests": [],
            "party_batches": [
                {
                    "participant_role": "USER",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "batch_id": "BATCH_user_new",
                    "evidence": [
                        {
                            "evidence_id": "EVIDENCE_user_new",
                            "evidence_type": "IMAGE",
                            "source_type": "USER",
                            "parsed_text": "签收人张某",
                        }
                    ],
                },
                {
                    "participant_role": "MERCHANT",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "batch_id": "BATCH_merchant_new",
                    "evidence": [
                        {
                            "evidence_id": "EVIDENCE_merchant_new",
                            "evidence_type": "DOCUMENT",
                            "source_type": "MERCHANT",
                            "parsed_text": "驿站通知记录",
                        }
                    ],
                },
            ],
            "case_fact_matrix": _adjudication_case_matrix(),
            "prior_fact_evidence_matrix": _evidence_matrix(
                frozen=True,
                prehearing_binding=True,
            ),
        }
    )

    result = HearingFlowWorkflows(runner).evidence_synthesis(request)

    assert [item["node_name"] for item in runner.calls].count(
        "hearing_evidence_file_assessment"
    ) == 2
    assert [item["node_name"] for item in runner.calls].count("hearing_evidence_synthesis") == 1
    synthesis_call = next(
        item for item in runner.calls if item["node_name"] == "hearing_evidence_synthesis"
    )
    assert len(synthesis_call["case_data"]["evidence_assessments"]) == 2
    assert len(synthesis_call["case_data"]["merged_fact_evidence_matrix"]["links"]) == 3
    assert result.fact_evidence_matrix.schema_version == "fact_evidence_matrix.v2"
    assert result.fact_evidence_matrix.matrix_version == 3
    assert result.fact_evidence_matrix.parent_ref.matrix_id == "FACT_EVIDENCE_MATRIX_prior"
    assert (
        result.fact_evidence_matrix.case_fact_matrix_version
        == _adjudication_case_matrix().matrix_version
    )
    assert (
        result.fact_evidence_matrix.case_fact_matrix_hash
        == _adjudication_case_matrix().content_hash
    )
    assert {item.evidence_id for item in result.fact_evidence_matrix.links} == {
        "EVIDENCE_old",
        "EVIDENCE_user_new",
        "EVIDENCE_merchant_new",
    }
    coverage = {
        item.fact_id: item.coverage_status for item in result.fact_evidence_matrix.fact_coverage
    }
    assert coverage["FACT_DELIVERY"] == "COVERED_BY_FROZEN_DOSSIER"
    assert coverage["FACT_RECIPIENT"] == "REQUIRES_HUMAN_REVIEW"


def test_evidence_synthesis_assesses_the_present_file_when_other_party_times_out() -> None:
    runner = QueueRunner(
        {
            "hearing_evidence_file_assessment": {
                "fact_links": [],
                "summary": "材料无法关联到已登记事实。",
                "requires_human_review": True,
            },
            "hearing_evidence_synthesis": {
                "evidence_summary": {},
                "evidence_gaps": ["商家本阶段超时，且用户材料无法关联到已登记事实。"],
                "public_message": "已基于当前全量材料完成整理，并保留超时与覆盖缺口。",
            },
        }
    )
    payload = {
        **_base("EVIDENCE_SYNTHESIS", 4),
        "party_batches": [
            {
                "participant_role": "USER",
                "terminal_status": "COMPLETED",
                "submission_source": "PARTY_ACTION",
                "batch_id": "BATCH_user_new",
                "evidence": [
                    {
                        "evidence_id": "EVIDENCE_user_new",
                        "evidence_type": "IMAGE",
                        "source_type": "USER",
                    }
                ],
            },
            {
                "participant_role": "MERCHANT",
                "terminal_status": "TIMED_OUT",
                "submission_source": "AUTO_TIMEOUT",
                "batch_id": "BATCH_merchant_timeout",
                "evidence": [],
            },
        ],
        "case_fact_matrix": _case_matrix(),
    }
    result = HearingFlowWorkflows(runner).evidence_synthesis(
        HearingEvidenceSynthesisRequest.model_validate(payload)
    )

    assert [item["node_name"] for item in runner.calls] == [
        "hearing_evidence_file_assessment",
        "hearing_evidence_synthesis",
    ]
    assert result.evidence_gaps


def test_evidence_synthesis_starts_every_file_assessment_in_parallel() -> None:
    file_count = 9
    stream_context: ContextVar[str | None] = ContextVar(
        "test_hearing_file_stream_context",
        default=None,
    )

    def assessment(_: dict[str, object]) -> dict[str, object]:
        assert stream_context.get() == "STREAM_OBSERVER_BOUND"
        return {
            "fact_links": [],
            "summary": "该文件没有形成可采纳的事实关联。",
            "requires_human_review": False,
        }

    runner = ParallelEvidenceRunner(
        {
            "hearing_evidence_file_assessment": assessment,
            "hearing_evidence_synthesis": {
                "evidence_summary": {"new_file_count": file_count},
                "evidence_gaps": ["九份文件均未形成事实关联。"],
                "public_message": "已完成全部文件的并行核验与一次性合并。",
            },
        },
        expected_files=file_count,
    )
    files = [
        {
            "evidence_id": f"EVIDENCE_parallel_{index}",
            "evidence_type": "DOCUMENT",
            "source_type": "USER" if index < 5 else "MERCHANT",
        }
        for index in range(file_count)
    ]
    request = HearingEvidenceSynthesisRequest.model_validate(
        {
            **_base("EVIDENCE_SYNTHESIS", 4),
            "party_batches": [
                {
                    "participant_role": "USER",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "batch_id": "BATCH_parallel_user",
                    "evidence": files[:5],
                },
                {
                    "participant_role": "MERCHANT",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "batch_id": "BATCH_parallel_merchant",
                    "evidence": files[5:],
                },
            ],
            "case_fact_matrix": _case_matrix(),
        }
    )

    token = stream_context.set("STREAM_OBSERVER_BOUND")
    try:
        result = HearingFlowWorkflows(runner).evidence_synthesis(request)
    finally:
        stream_context.reset(token)

    assert (
        sum(item["node_name"] == "hearing_evidence_file_assessment" for item in runner.calls)
        == file_count
    )
    assert result.evidence_summary == {"new_file_count": file_count}


def test_evidence_synthesis_never_merges_a_partial_assessment_set() -> None:
    def assessment(kwargs: dict[str, object]) -> dict[str, object]:
        evidence_id = kwargs["case_data"]["evidence_file"]["evidence_id"]
        if evidence_id == "EVIDENCE_failure":
            raise AgentServiceUnavailable("file assessment failed")
        return {
            "fact_links": [],
            "summary": "该文件已完成核验。",
            "requires_human_review": False,
        }

    runner = QueueRunner(
        {
            "hearing_evidence_file_assessment": assessment,
            "hearing_evidence_synthesis": {
                "evidence_summary": {},
                "evidence_gaps": [],
                "public_message": "不应生成。",
            },
        }
    )
    request = HearingEvidenceSynthesisRequest.model_validate(
        {
            **_base("EVIDENCE_SYNTHESIS", 4),
            "party_batches": [
                {
                    "participant_role": "USER",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "batch_id": "BATCH_partial_user",
                    "evidence": [
                        {
                            "evidence_id": "EVIDENCE_success",
                            "evidence_type": "DOCUMENT",
                            "source_type": "USER",
                        }
                    ],
                },
                {
                    "participant_role": "MERCHANT",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "batch_id": "BATCH_partial_merchant",
                    "evidence": [
                        {
                            "evidence_id": "EVIDENCE_failure",
                            "evidence_type": "DOCUMENT",
                            "source_type": "MERCHANT",
                        }
                    ],
                },
            ],
            "case_fact_matrix": _case_matrix(),
        }
    )

    with pytest.raises(AgentServiceUnavailable, match="file assessment failed"):
        HearingFlowWorkflows(runner).evidence_synthesis(request)

    assert all(item["node_name"] != "hearing_evidence_synthesis" for item in runner.calls)


def test_v1_review_v2_are_hash_bound_and_v2_text_is_persistable() -> None:
    dimensions = [
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    ]
    v2_text = "V2 草案：转人工审核签收主体与通知日志后再决定退款。"
    runner = QueueRunner(
        {
            "hearing_judge_v1": {
                "proposal_text": "非最终 V1：先核验签收主体，再决定退款。",
                "recommended_decision": "人工核验后决定退款",
                "reasoning_summary": "当前签收人事实仍有冲突。",
                "review_focus": ["签收主体", "驿站通知日志"],
                "public_message": "法官 V1 已形成并提交独立评审。",
            },
            "hearing_jury_review": {
                "findings": [
                    {
                        "dimension": dimension,
                        "severity": "HIGH" if dimension == "FACT_COMPLETENESS" else "LOW",
                        "assessment": f"{dimension} 评审意见。",
                        "basis": ["FACT_RECIPIENT"],
                        "requires_revision": dimension == "FACT_COMPLETENESS",
                    }
                    for dimension in dimensions
                ],
                "mandatory_revisions": ["V2 必须明确签收主体无法核实时的处理规则。"],
                "public_message": "独立评审完成，V1 需要补充事实不明时的处理规则。",
            },
            "hearing_judge_v2": {
                "draft": {
                    "recommended_decision": "转人工审核",
                    "confidence": 0.72,
                    "draft_text": v2_text,
                    "fact_findings": [
                        {
                            "fact_id": "FACT_DELIVERY",
                            "finding": "物流记录显示签收，但不能单独证明本人收货。",
                            "evidence_ids": ["EVIDENCE_old"],
                            "evidence_gap": "缺少能够确认实际签收主体的材料。",
                            "confidence": 0.72,
                        }
                    ],
                    "evidence_assessment": [
                        {
                            "assessment_type": "EVIDENCE",
                            "evidence_id": "EVIDENCE_old",
                            "fact_ids": ["FACT_DELIVERY"],
                            "assessment": "现有证据只能证明物流系统记录，不能单独确认签收主体。",
                            "weight": "MEDIUM",
                            "confidence": 0.7,
                            "limitations": ["缺少签收人身份信息"],
                        }
                    ],
                    "policy_application": [
                        {
                            "rule_code": "DELIVERY_PROOF",
                            "rule_version": 1,
                            "rule_name": "签收争议举证规则",
                            "fact_ids": ["FACT_DELIVERY"],
                            "applicable": True,
                            "rationale": "履约方应提供可核验的交付记录。",
                            "limitations": ["签收主体仍待人工核验"],
                        }
                    ],
                    "reviewer_attention": ["核对签收主体无法查明时的处理路径。"],
                },
                "public_message": v2_text,
            },
        }
    )
    workflows = HearingFlowWorkflows(runner)
    v1 = workflows.judge_v1(
        HearingJudgeV1Request.model_validate(
            {**_base("JUDGE_V1", 5), "trial_dossier": _trial_dossier()}
        )
    )
    review = workflows.jury_review(
        HearingJuryReviewRequest.model_validate(
            {
                **_base("JURY_REVIEW", 6),
                "trial_dossier": _trial_dossier(),
                "judge_v1": v1,
            }
        )
    )
    v2_request = HearingJudgeV2Request.model_validate(
        {
            **_base("JUDGE_V2", 7),
            "trial_dossier": _trial_dossier(),
            "judge_v1": v1,
            "jury_review": review,
        }
    )
    v2 = workflows.judge_v2(v2_request)

    assert review.reviewed_proposal_id == v1.proposal_id
    assert review.reviewed_proposal_hash == v1.proposal_hash
    assert v2.parent_proposal_hash == v1.proposal_hash
    assert v2.jury_review_hash == review.review_hash
    assert v2.public_message == v2.draft.draft_text == v2_text
    assert v2.draft.draft_status == "PENDING_HUMAN_REVIEW"
    assert content_hash(v2, hash_field="judge_v2_hash") == v2.judge_v2_hash

    gap_text = "V2 草案：签收主体缺少直接证据，提交人工终审。"
    runner.outputs["hearing_judge_v2"] = {
        "draft": {
            "recommended_decision": "转人工审核",
            "confidence": 0.4,
            "draft_text": gap_text,
            "fact_findings": [
                {
                    "fact_id": "FACT_RECIPIENT",
                    "finding": "现有卷宗不能确认签收主体。",
                    "evidence_ids": [],
                    "evidence_gap": "没有与签收主体事实关联的证据。",
                    "confidence": 0.4,
                }
            ],
            "evidence_assessment": [
                {
                    "assessment_type": "EVIDENCE_GAP",
                    "evidence_id": None,
                    "fact_ids": ["FACT_RECIPIENT"],
                    "assessment": "签收主体事实没有可供采信的证据。",
                    "weight": "NONE",
                    "confidence": 0.4,
                    "limitations": ["需人工判断举证不能的不利后果"],
                }
            ],
            "policy_application": [
                {
                    "rule_code": "DELIVERY_PROOF",
                    "rule_version": 1,
                    "rule_name": "签收争议举证规则",
                    "fact_ids": ["FACT_RECIPIENT"],
                    "applicable": True,
                    "rationale": "签收主体不明时仍需按冻结规则审查举证责任。",
                    "limitations": ["缺少直接证据"],
                }
            ],
            "reviewer_attention": ["确认签收主体无法查明时的处理路径。"],
        },
        "public_message": gap_text,
    }
    gap_v2 = workflows.judge_v2(v2_request)
    assert gap_v2.draft.evidence_assessment[0].assessment_type == "EVIDENCE_GAP"
    assert gap_v2.draft.evidence_assessment[0].evidence_id is None

    runner.outputs["hearing_judge_v2"] = {
        "draft": {
            "recommended_decision": "转人工审核",
            "confidence": 0.5,
            "draft_text": "错误草案：把其他事实的证据用于签收主体认定。",
            "fact_findings": [
                {
                    "fact_id": "FACT_RECIPIENT",
                    "finding": "错误引用。",
                    "evidence_ids": ["EVIDENCE_old"],
                    "evidence_gap": None,
                    "confidence": 0.5,
                }
            ],
            "evidence_assessment": [
                {
                    "assessment_type": "EVIDENCE",
                    "evidence_id": "EVIDENCE_old",
                    "fact_ids": ["FACT_DELIVERY"],
                    "assessment": "该证据只能关联交付记录事实。",
                    "weight": "MEDIUM",
                    "confidence": 0.5,
                    "limitations": [],
                }
            ],
            "policy_application": [
                {
                    "rule_code": "DELIVERY_PROOF",
                    "rule_version": 1,
                    "rule_name": "签收争议举证规则",
                    "fact_ids": ["FACT_RECIPIENT"],
                    "applicable": True,
                    "rationale": "需要人工纠正事实证据绑定。",
                    "limitations": [],
                }
            ],
            "reviewer_attention": ["检查事实证据绑定。"],
        },
        "public_message": "错误草案：把其他事实的证据用于签收主体认定。",
    }
    with pytest.raises(AgentOutputSchemaError, match="not linked to its fact"):
        workflows.judge_v2(v2_request)

    runner.outputs["hearing_judge_v2"]["draft"]["fact_findings"][0]["fact_id"] = (
        "FACT_DELIVERY"
    )
    runner.outputs["hearing_judge_v2"]["draft"]["policy_application"][0][
        "rule_code"
    ] = "INVENTED_RULE"
    with pytest.raises(AgentOutputSchemaError, match="absent from the frozen dossier"):
        workflows.judge_v2(v2_request)


def test_jury_review_repairs_duplicate_dimensions_as_mandatory_review_gaps() -> None:
    raw_dimensions = [
        "FACT_COMPLETENESS",
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
    ]
    runner = QueueRunner(
        {
            "hearing_judge_v1": {
                "proposal_text": "法官 V1 建议结合现有事实和证据形成待审核方案。",
                "recommended_decision": "形成待人工审核的处理方案",
                "reasoning_summary": "当前材料足以形成非最终建议。",
                "review_focus": ["事实完整性", "证据一致性"],
                "public_message": "法官 V1 已提交独立评议。",
            },
            "hearing_jury_review": {
                "findings": [
                    {
                        "dimension": dimension,
                        "severity": "LOW",
                        "assessment": f"{dimension} 原始评议。",
                        "basis": ["FACT_DELIVERY"],
                        "requires_revision": False,
                    }
                    for dimension in raw_dimensions
                ],
                "mandatory_revisions": [],
                "public_message": "独立评议完成，但原始输出存在重复维度。",
            },
        }
    )
    workflows = HearingFlowWorkflows(runner)
    dossier = _trial_dossier(
        answer_schemas=(
            "hearing_party_statement.v1",
            "hearing_party_statement.v1",
        )
    )
    v1 = workflows.judge_v1(
        HearingJudgeV1Request.model_validate(
            {**_base("JUDGE_V1", 5), "trial_dossier": dossier}
        )
    )

    review = workflows.jury_review(
        HearingJuryReviewRequest.model_validate(
            {
                **_base("JURY_REVIEW", 6),
                "trial_dossier": dossier,
                "judge_v1": v1,
            }
        )
    )

    findings = {finding.dimension: finding for finding in review.findings}
    assert set(findings) == {
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    }
    repaired = findings["RISK_AND_OMISSIONS"]
    assert repaired.severity == "MEDIUM"
    assert repaired.requires_revision is True
    assert any("RISK_AND_OMISSIONS" in item for item in review.mandatory_revisions)


def test_api_exposes_only_explicit_hearing_flow_runtime_routes() -> None:
    runner = QueueRunner(
        {
            "hearing_intake_questions": {
                "questions": [
                    {
                        "fact_ids": ["FACT_DELIVERY"],
                        "issue_statement": "实际签收人是谁以及是否具有代收授权。",
                        "party_prompts": {
                            "USER": "请说明你是否实际收到包裹，以及你与签收人的关系。",
                            "MERCHANT": "请说明你所依据的签收记录及代收授权情况。",
                        },
                    }
                ],
                "public_message": "请双方确认实际签收人。",
            }
        }
    )
    settings = Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )
    client = TestClient(
        create_app(
            settings,
            hearing_flow_workflows=HearingFlowWorkflows(runner),
        )
    )
    paths = {route.path for route in client.app.routes}

    assert "/internal/agents/hearing/round-turn" not in paths
    assert "/internal/agents/hearing/run-stage" not in paths
    assert "/internal/agents/legacy/hearing/analyze" not in paths
    for path in (
        "/internal/agents/hearing-flow/intake/questions",
        "/internal/agents/hearing-flow/intake/synthesis",
        "/internal/agents/hearing-flow/evidence/requests",
        "/internal/agents/hearing-flow/evidence/synthesis",
        "/internal/agents/hearing-flow/judge/v1",
        "/internal/agents/hearing-flow/jury/review",
        "/internal/agents/hearing-flow/judge/v2",
    ):
        assert path in paths
        assert path + "/stream" in paths

    response = client.post(
        "/internal/agents/hearing-flow/intake/questions/stream",
        headers={
            "X-Service-Secret": "test-agent-service-secret",
            "X-Agent-Run-Id": "AGENT_RUN_hearing_flow_questions",
        },
        json={
            **_base("INTAKE_QUESTIONS", 1),
            "case_fact_matrix": _case_matrix().model_dump(mode="json"),
            "max_questions": 5,
        },
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert events[-1]["type"] == "final"
    assert events[-1]["response"]["schema_version"] == "hearing_intake_questions.v1"
