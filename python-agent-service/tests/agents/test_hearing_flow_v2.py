from __future__ import annotations

import hashlib
import json
import threading
from contextvars import ContextVar
from types import SimpleNamespace

from fastapi.testclient import TestClient
import pytest

from app.agents.hearing_flow import HearingFlowWorkflows, _assert_case_matrix_integrity
from app.agents.hearing_intake_v4 import _assert_matrix_integrity
from app.config import Settings
from app.contracts.v1.codec import canonicalize
from app.graph_runtime.target_e2e_room_exchange import (
    GovernedTargetE2EHearingInvocationDecoder,
)
from app.graphs.hearing.contracts import HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.main import create_app
from app.schemas import (
    CaseFactMatrixV2,
    FactEvidenceMatrixV3,
    HearingAdjudicationDraftBody,
    HearingCaseFactMatrixDelta,
    HearingDecisionAction,
    HearingEvidenceFileAssessmentLlmOutput,
    HearingEvidenceRequestsRequest,
    HearingEvidenceSynthesisRequest,
    HearingEvidenceSynthesisResult,
    HearingAnswerBundleV1,
    HearingIntakeQuestionsLlmOutput,
    HearingIntakeQuestionsRequest,
    HearingIntakeSynthesisRequest,
    HearingJudgeV1Request,
    HearingJudgeV2Request,
    HearingJuryReviewRequest,
    HearingPartyStatementV1,
    TrialDossierV1,
    TrialDossierV2,
    content_hash,
)


def _hash_payload(value: dict[str, object], field: str = "content_hash") -> str:
    payload = dict(value)
    payload.pop(field, None)
    return hashlib.sha256(canonicalize(payload)).hexdigest()


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
) -> FactEvidenceMatrixV3:
    case = case_matrix or _case_matrix()
    bound_case = _prehearing_case_matrix() if prehearing_binding else case
    payload = {
        "schema_version": "fact_evidence_matrix.v3",
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
                "relation": "CONTENT_SUPPORTS",
                "reason": "旧物流记录显示已签收。",
                "source_unit_id": "SOURCE_UNIT_prior",
                "observation_slot": "OBS_prior",
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
    return FactEvidenceMatrixV3.model_validate(payload)


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


def _trial_dossier_v2() -> TrialDossierV2:
    case_matrix = _adjudication_case_matrix()
    evidence_matrix = _evidence_matrix(frozen=True, case_matrix=case_matrix)
    payload = {
        "schema_version": "trial_dossier.v2",
        "trial_dossier_id": "TRIAL_DOSSIER_V2_hearing_flow",
        "case_id": case_matrix.case_id,
        "frozen_at": "2026-07-15T21:30:00+08:00",
        "case_matrix_version": case_matrix.matrix_version,
        "case_matrix_hash": case_matrix.content_hash,
        "case_fact_matrix": case_matrix.model_dump(mode="json"),
        "evidence_matrix_version": evidence_matrix.matrix_version,
        "evidence_matrix_hash": evidence_matrix.content_hash,
        "fact_evidence_matrix": evidence_matrix.model_dump(mode="json"),
        "adjudication_rules": [
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
    payload["content_hash"] = _hash_payload(payload)
    return TrialDossierV2.model_validate(payload)


def _adjudication_draft(*, reviewer_attention: list[str] | None = None) -> dict[str, object]:
    return {
        "remedy_orders": [
            {
                "remedy_type": "REJECT_CLAIM",
                "order_text": "现有冻结材料不足以支持退款诉求，结束本次案件。",
                "fact_ids": ["FACT_DELIVERY", "FACT_RECIPIENT"],
                "conditions": [],
            }
        ],
        "fact_findings": [
            {
                "fact_id": "FACT_DELIVERY",
                "finding": "现有物流记录仅能证明系统记载已签收。",
                "evidence_ids": ["EVIDENCE_old"],
                "evidence_gap": None,
                "confidence": 0.72,
            },
            {
                "fact_id": "FACT_RECIPIENT",
                "finding": "冻结证据不能确认实际签收主体。",
                "evidence_ids": [],
                "evidence_gap": "没有与签收主体事实绑定的证据。",
                "confidence": 0.4,
            },
        ],
        "rule_applications": [
            {
                "rule_code": "DELIVERY_PROOF",
                "rule_version": 1,
                "rule_name": "签收争议举证规则",
                "fact_ids": ["FACT_DELIVERY", "FACT_RECIPIENT"],
                "applicable": True,
                "conditions_met": ["存在交付是否完成的争议"],
                "conditions_unmet": [],
                "rationale": "本案须依据可核验交付证据认定履约。",
                "resulting_effect": "签收主体不明事项保留人工审核",
            }
        ],
        "decision_reasoning": (
            "M2 显示双方对实际交付和签收主体存在冲突；E2 仅绑定物流系统记录，"
            "不能确认实际签收主体；依冻结举证规则，当前材料不足以支持退款诉求。"
        ),
        "reviewer_attention": reviewer_attention or ["核验实际签收主体。"],
        "decision_action": "REJECT_CLAIM",
    }


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


@pytest.mark.parametrize("decision_action", list(HearingDecisionAction))
def test_adjudication_draft_requires_one_supported_decision_action(
    decision_action: HearingDecisionAction,
) -> None:
    payload = _adjudication_draft()
    payload["decision_action"] = decision_action.value

    draft = HearingAdjudicationDraftBody.model_validate(payload)

    assert draft.decision_action is decision_action


def test_decision_action_schema_explains_every_supported_code() -> None:
    description = HearingAdjudicationDraftBody.model_json_schema()["properties"][
        "decision_action"
    ]["description"]

    assert "必须且只能选择一个" in description
    for decision_action in HearingDecisionAction:
        assert f"{decision_action.value}=" in description
    assert "RETURN_AND_REFUND=用户退回商品后，由商家退还相应款项" in description
    assert "CONTINUE_FULFILLMENT=维持当前订单关系" in description


@pytest.mark.parametrize(
    "mutate",
    [
        lambda payload: payload.pop("decision_action"),
        lambda payload: payload.update(decision_action="MANUAL_REVIEW"),
        lambda payload: payload.update(decision_action="建议退货退款"),
    ],
)
def test_adjudication_draft_rejects_missing_or_open_decision_action(mutate) -> None:
    payload = _adjudication_draft()
    mutate(payload)

    with pytest.raises(ValueError, match="decision_action"):
        HearingAdjudicationDraftBody.model_validate(payload)


def test_adjudication_draft_rejects_legacy_recommended_decision() -> None:
    payload = _adjudication_draft()
    payload.pop("decision_action")
    payload["recommended_decision"] = "建议退货退款"

    with pytest.raises(ValueError, match="decision_action|recommended_decision"):
        HearingAdjudicationDraftBody.model_validate(payload)


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


def _hearing_context_from_call(call: dict[str, object]) -> dict[str, object]:
    context_pack = call["context_pack"]
    assert len(context_pack.sections) == 1
    assert context_pack.sections[0].name == "hearing_room_context_v3"
    return json.loads(context_pack.sections[0].content)


class ParallelEvidenceRunner(QueueRunner):
    def __init__(self, outputs: dict[str, object], expected_files: int) -> None:
        super().__init__(outputs)
        self.assessment_barrier = threading.Barrier(expected_files)
        self._assessment_lock = threading.Lock()
        self._assessment_started = 0

    def invoke_structured(self, **kwargs):
        if kwargs["node_name"] == "hearing_evidence_file_assessment":
            with self._assessment_lock:
                self._assessment_started += 1
                join_first_wave = self._assessment_started <= self.assessment_barrier.parties
            if join_first_wave:
                self.assessment_barrier.wait(timeout=2)
        return super().invoke_structured(**kwargs)


def test_intake_questions_accepts_java_canonical_hash_for_whole_number_amount() -> None:
    matrix_payload = _prehearing_case_matrix().model_dump(mode="json")
    matrix_payload["claims"]["initiator_claim"]["requested_amount"] = 11_899
    matrix_payload["content_hash"] = _hash_payload(matrix_payload)
    matrix = CaseFactMatrixV2.model_validate(matrix_payload)

    _assert_case_matrix_integrity(
        matrix,
        expected_case_id=matrix.case_id,
        node_name="hearing_intake_questions",
    )
    _assert_matrix_integrity(matrix, matrix.case_id)

    assert matrix.claims.initiator_claim.requested_amount == 11_899.0


def test_intake_questions_preserves_omitted_claim_optionals_during_hash_verification() -> None:
    matrix_payload = _prehearing_case_matrix().model_dump(mode="json")
    initiator_claim = matrix_payload["claims"]["initiator_claim"]
    initiator_claim.pop("requested_amount")
    initiator_claim.pop("requested_items")
    matrix_payload["content_hash"] = _hash_payload(matrix_payload)
    matrix = CaseFactMatrixV2.model_validate(matrix_payload)

    _assert_case_matrix_integrity(
        matrix,
        expected_case_id=matrix.case_id,
        node_name="hearing_intake_questions",
    )
    _assert_matrix_integrity(matrix, matrix.case_id)

    parsed_claim = matrix.claims.initiator_claim
    assert "requested_amount" not in parsed_claim.model_fields_set
    assert "requested_items" not in parsed_claim.model_fields_set


def test_intake_questions_still_rejects_tampered_omitted_claim_matrix() -> None:
    matrix_payload = _prehearing_case_matrix().model_dump(mode="json")
    matrix_payload["claims"]["initiator_claim"].pop("requested_amount")
    matrix_payload["content_hash"] = _hash_payload(matrix_payload)
    matrix_payload["case_overview"]["neutral_summary"] = "哈希生成后被篡改的摘要。"
    matrix = CaseFactMatrixV2.model_validate(matrix_payload)

    with pytest.raises(AgentOutputSchemaError, match="input case matrix hash is invalid"):
        _assert_case_matrix_integrity(
            matrix,
            expected_case_id=matrix.case_id,
            node_name="hearing_intake_questions",
        )


def test_target_e2e_hearing_v2_invocation_uses_governed_case_specific_model_output() -> None:
    matrix_payload = _prehearing_case_matrix().model_dump(mode="json")
    matrix_payload["case_overview"] = {
        "neutral_summary": "用户购买加急配送商品，双方对约定送达日期及延迟后替代购买有争议。",
        "core_conflict": "30元加急费对应的送达承诺是否履行，以及270元替代购买是否必要。",
        "summary_source_fact_ids": ["FACT_DELIVERY"],
    }
    matrix_payload["claims"]["initiator_claim"]["requested_amount"] = 270.0
    matrix_payload["fact_rows"][0]["fact_target"] = (
        "订单是否在约定送达日期前交付，以及延迟后用户是否支出270元替代购买费用"
    )
    matrix_payload["content_hash"] = "0" * 64
    matrix_payload["content_hash"] = _hash_payload(matrix_payload)
    matrix = CaseFactMatrixV2.model_validate(matrix_payload)
    request = HearingIntakeQuestionsRequest.model_validate(
        {
            **_base("INTAKE_QUESTIONS", 1),
            "case_fact_matrix": matrix,
            "max_questions": 5,
        }
    )
    runner = QueueRunner(
        {
            "hearing_intake_questions": {
                "questions": [
                    {
                        "fact_ids": ["FACT_DELIVERY"],
                        "issue_statement": (
                            "请围绕约定送达日期、30元加急费与270元替代购买费用说明事实依据。"
                        ),
                        "party_prompts": {
                            "USER": "请说明约定送达日期及270元替代购买的时间、金额和凭证。",
                            "MERCHANT": "请说明30元加急费对应的送达承诺、实际轨迹及延迟原因。",
                        },
                    }
                ],
                "public_message": "现就本案加急送达与替代购买争议向双方发问。",
            }
        }
    )
    decoder = GovernedTargetE2EHearingInvocationDecoder(HearingFlowWorkflows(runner))
    document = {
        "schema_version": "target-e2e-hearing-invocation.v2",
        "operation": "intake_questions",
        "shared_barrier_receipt_hash": "3" * 64,
        "request": request.model_dump(mode="json"),
    }
    command = SimpleNamespace(
        case_id=request.case_id,
        stage_sequence=request.stage_sequence,
        domain_snapshot_ref=SimpleNamespace(uri="urn:target-e2e:hearing:v2", sha256="4" * 64),
        event_ref=None,
    )
    execution = SimpleNamespace(admission=SimpleNamespace(command=command))

    loaded = decoder.decode(
        execution=execution,
        snapshot_payload=canonicalize(document),
        event_payload=None,
    )
    result = loaded.invocation.execute(loaded.request)
    replay_loaded = decoder.decode(
        execution=execution,
        snapshot_payload=canonicalize(document),
        event_payload=None,
    )
    replay = replay_loaded.invocation.execute(replay_loaded.request)

    assert loaded.operation is HearingOperation.INTAKE_QUESTIONS
    assert result == replay
    assert "约定送达日期" in result.questions[0].question_text
    assert "30元加急费" in result.questions[0].party_prompts.MERCHANT
    assert "270元替代购买" in result.questions[0].party_prompts.USER
    assert "Synthetic" not in result.model_dump_json()
    assert "Provide your account" not in result.model_dump_json()
    assert "270元替代购买" in json.dumps(
        _hearing_context_from_call(runner.calls[0]), ensure_ascii=False
    )

    with pytest.raises(
        HearingGraphContractError,
        match="TARGET_E2E_HEARING_INVOCATION_REQUIRED",
    ):
        decoder.decode(
            execution=execution,
            snapshot_payload=canonicalize(
                {**document, "fixture_proposal": result.model_dump(mode="json")}
            ),
            event_payload=None,
        )


def test_hearing_fact_delta_summary_refs_only_accept_existing_fact_ids() -> None:
    existing_only = HearingCaseFactMatrixDelta.model_validate(
        {
            "neutral_summary": "既有物流事实仍是摘要依据。",
            "core_conflict": "包裹是否实际交付。",
            "fact_rows": [],
            "summary_source_fact_keys": ["FACT_DELIVERY"],
        }
    )
    assert existing_only.summary_source_fact_keys == ["FACT_DELIVERY"]

    valid_new_payload = {
        "neutral_summary": "双方补充了签收人身份陈述。",
        "core_conflict": "签收人是否有权代收。",
        "fact_rows": [
            {
                "fact_key": "NEW_RECIPIENT_DETAIL",
                "category": "LOGISTICS",
                "fact_target": "庭审陈述首次提出签收人身份",
                "materiality": "CORE",
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户否认本人签收。",
                    }
                },
            }
        ],
        "summary_source_fact_keys": ["FACT_DELIVERY"],
    }
    valid_new = HearingCaseFactMatrixDelta.model_validate(valid_new_payload)
    assert valid_new.fact_rows[0].fact_key == "NEW_RECIPIENT_DETAIL"
    assert valid_new.summary_source_fact_keys == ["FACT_DELIVERY"]

    invalid_payload = dict(valid_new_payload)
    invalid_payload["summary_source_fact_keys"] = ["NEW_RECIPIENT_DETAIL"]
    with pytest.raises(ValueError, match="String should match pattern"):
        HearingCaseFactMatrixDelta.model_validate(invalid_payload)


def test_intake_synthesis_payload_catalogs_existing_fact_keys_in_sorted_order() -> None:
    matrix_payload = _case_matrix().model_dump(mode="json")
    matrix_payload["fact_rows"] = list(reversed(matrix_payload["fact_rows"]))
    matrix_payload["content_hash"] = _hash_payload(matrix_payload)
    matrix = CaseFactMatrixV2.model_validate(matrix_payload)
    assert [row.fact_id for row in matrix.fact_rows] == [
        "FACT_RECIPIENT",
        "FACT_DELIVERY",
    ]

    runner = QueueRunner(
        {
            "hearing_intake_synthesis": {
                "case_fact_matrix_delta": {
                    "neutral_summary": "既有物流事实仍是摘要依据。",
                    "core_conflict": "包裹是否实际交付。",
                    "fact_rows": [],
                    "summary_source_fact_keys": ["FACT_DELIVERY"],
                },
                "issue_mappings": [],
                "public_message": "双方仍对包裹是否实际交付存在争议。",
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
                    "source_refs": ["ACTION_USER_ANSWER"],
                    "statement_text": "我本人没有收到包裹。",
                },
                {
                    "participant_id": "merchant-local",
                    "participant_role": "MERCHANT",
                    "terminal_status": "COMPLETED",
                    "submission_source": "PARTY_ACTION",
                    "source_refs": ["ACTION_MERCHANT_ANSWER"],
                    "statement_text": "物流记录显示包裹已签收。",
                },
            ],
            "case_fact_matrix": matrix,
        }
    )

    HearingFlowWorkflows(runner).intake_synthesis(request)

    assert _hearing_context_from_call(runner.calls[-1])["existing_fact_keys"] == [
        "FACT_DELIVERY",
        "FACT_RECIPIENT",
    ]


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


def _omit_claim_optionals_and_rehash_dossier(
    payload: dict[str, object],
) -> dict[str, object]:
    case_matrix = payload["case_fact_matrix"]
    case_matrix["claims"]["initiator_claim"].pop("requested_amount")
    case_matrix["claims"]["initiator_claim"].pop("requested_items")
    case_matrix["content_hash"] = _hash_payload(case_matrix)
    payload["case_matrix_hash"] = case_matrix["content_hash"]

    evidence_matrix = payload["fact_evidence_matrix"]
    evidence_matrix["case_fact_matrix_hash"] = case_matrix["content_hash"]
    evidence_matrix["content_hash"] = _hash_payload(evidence_matrix)
    payload["evidence_matrix_hash"] = evidence_matrix["content_hash"]

    evidence_request_set = payload.get("evidence_request_set")
    if evidence_request_set is not None:
        evidence_request_set["case_matrix_hash"] = case_matrix["content_hash"]
    payload["content_hash"] = _hash_payload(payload)
    return payload


def test_trial_dossier_v1_accepts_case_matrix_with_omitted_claim_optionals() -> None:
    payload = _omit_claim_optionals_and_rehash_dossier(
        _trial_dossier().model_dump(mode="json")
    )

    dossier = TrialDossierV1.model_validate(payload)

    claim = dossier.case_fact_matrix.claims.initiator_claim
    assert "requested_amount" not in claim.model_fields_set
    assert "requested_items" not in claim.model_fields_set


def test_trial_dossier_v2_contains_only_frozen_adjudication_authorities() -> None:
    dossier = _trial_dossier_v2()

    assert set(TrialDossierV2.model_fields) == {
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
        "adjudication_rules",
        "content_hash",
    }
    payload = dossier.model_dump(mode="json")
    assert {
        "question_set_id",
        "question_set",
        "answer_bundles",
        "request_set_id",
        "evidence_request_set",
        "evidence_batches",
        "policy_rules",
    }.isdisjoint(payload)
    assert dossier.adjudication_rules[0].rule_code == "DELIVERY_PROOF"
    assert dossier.content_hash == _hash_payload(payload)

    mismatched = dict(payload)
    mismatched["evidence_matrix_hash"] = "f" * 64
    mismatched["content_hash"] = _hash_payload(mismatched)
    with pytest.raises(ValueError, match="evidence matrix binding"):
        TrialDossierV2.model_validate(mismatched)


def test_trial_dossier_v2_accepts_case_matrix_with_omitted_claim_optionals() -> None:
    payload = _omit_claim_optionals_and_rehash_dossier(
        _trial_dossier_v2().model_dump(mode="json")
    )

    dossier = TrialDossierV2.model_validate(payload)

    claim = dossier.case_fact_matrix.claims.initiator_claim
    assert "requested_amount" not in claim.model_fields_set
    assert "requested_items" not in claim.model_fields_set


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
                    "summary_source_fact_keys": ["FACT_DELIVERY"],
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
    assert matrix.case_overview.summary_source_fact_ids == [
        "FACT_DELIVERY",
        new_row.fact_id,
    ]
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
    assert _hearing_context_from_call(runner.calls[-1])["party_statement_catalog"][0][
        "statement_text"
    ] == (
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

    statements = _hearing_context_from_call(runner.calls[-1])["party_statement_catalog"]
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


def test_evidence_request_scope_remains_prompt_guidance_but_roles_are_shared() -> None:
    runner = QueueRunner(
        {
            "hearing_evidence_requests": {
                "requests": [
                    {
                        "target_roles": ["USER"],
                        "fact_ids": ["FACT_DELIVERY"],
                        "requested_material": "用户持有的交付记录",
                        "verification_goal": "从用户侧核对交付记录",
                        "required": True,
                    },
                    {
                        "target_roles": ["MERCHANT"],
                        "fact_ids": ["FACT_DELIVERY"],
                        "requested_material": "商家持有的交付记录",
                        "verification_goal": "从商家侧核对交付记录",
                        "required": True,
                    },
                ],
                "public_message": "请围绕交付记录补充说明。",
            }
        }
    )
    request = HearingEvidenceRequestsRequest.model_validate(
        {
            **_base("EVIDENCE_REQUESTS", 3),
            "case_fact_matrix": _adjudication_case_matrix(),
            "evidence_dossier": {
                "dossier_id": "EVIDENCE_DOSSIER_prompt_guidance",
                "dossier_version": 1,
                "dossier_status": "FROZEN",
                "fact_evidence_matrix": _evidence_matrix(
                    frozen=True,
                    prehearing_binding=True,
                ),
                "evidence_summary": {},
                "evidence_gaps": [],
            },
        }
    )

    result = HearingFlowWorkflows(runner).evidence_requests(request)

    context = _hearing_context_from_call(runner.calls[0])
    assert context["uncovered_fact_catalog"] == [
        {
            "fact_id": "FACT_RECIPIENT",
            "uncovered_reason": "MISSING_FROM_FROZEN_E1",
        }
    ]
    assert [item.fact_ids for item in result.requests] == [
        ["FACT_DELIVERY"],
        ["FACT_DELIVERY"],
    ]
    assert [item.target_roles for item in result.requests] == [
        ["USER", "MERCHANT"],
        ["USER", "MERCHANT"],
    ]


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
        evidence_id = _hearing_context_from_call(kwargs)["current_evidence_item"][
            "evidence_file"
        ]["evidence_id"]
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
    synthesis_context = _hearing_context_from_call(synthesis_call)
    assert len(synthesis_context["evidence_assessment_catalog"]) == 2
    assert len(synthesis_context["merged_evidence_matrix"]["links"]) == 3
    assert (
        synthesis_context["merged_evidence_matrix"]["matrix_status"]
        == "FROZEN"
    )
    assert result.fact_evidence_matrix.schema_version == "fact_evidence_matrix.v3"
    assert result.fact_evidence_matrix.matrix_version == 3
    assert result.fact_evidence_matrix.matrix_status == "FROZEN"
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
    assert (
        content_hash(result.fact_evidence_matrix, hash_field="content_hash")
        == result.fact_evidence_matrix.content_hash
    )

    working_terminal = result.model_dump(mode="json")
    working_terminal["fact_evidence_matrix"]["matrix_status"] = "WORKING"
    working_terminal["fact_evidence_matrix"]["content_hash"] = _hash_payload(
        working_terminal["fact_evidence_matrix"]
    )
    with pytest.raises(ValueError, match="terminal hearing evidence matrix must be frozen"):
        HearingEvidenceSynthesisResult.model_validate(working_terminal)

    replay = HearingFlowWorkflows(runner).evidence_synthesis(request)
    assert replay == result


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


def test_evidence_synthesis_runs_bounded_parallel_send_waves() -> None:
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
        expected_files=8,
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
        evidence_id = _hearing_context_from_call(kwargs)["current_evidence_item"][
            "evidence_file"
        ]["evidence_id"]
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


def test_v1_and_v2_assemble_separate_contexts_over_one_frozen_authority() -> None:
    dimensions = [
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    ]
    v1_draft = _adjudication_draft()
    v2_draft = _adjudication_draft(
        reviewer_attention=["人工终审仍需核验实际签收主体。"]
    )
    v1_draft["fact_findings"][1]["evidence_gap"] = None
    v2_draft["fact_findings"][1]["evidence_gap"] = None
    review_responses = [
        *[
            {
                "review_item_ref": f"V1_FOCUS_{index:02d}",
                "review_source": "V1_REVIEW_FOCUS",
                "disposition": "ACCEPTED",
                "response": "已回到冻结 M2、E2 与规则核验该关注点。",
                "affected_fields": ["reviewer_attention"],
            }
            for index in range(1, 3)
        ],
        *[
            {
                "review_item_ref": f"JURY_FINDING_{dimension}",
                "review_source": "JURY_FINDING",
                "disposition": "ACCEPTED",
                "response": "该意见未超出冻结裁判依据，已纳入复审。",
                "affected_fields": ["decision_reasoning"],
            }
            for dimension in dimensions
        ],
        {
            "review_item_ref": "JURY_MANDATORY_01",
            "review_source": "MANDATORY_REVISION",
            "disposition": "ACCEPTED",
            "response": "已明确签收主体不能确认时保留人工审核。",
            "affected_fields": ["decision_action", "reviewer_attention"],
        },
    ]
    runner = QueueRunner(
        {
            "hearing_judge_v1": {
                "draft": v1_draft,
                "review_focus": ["签收主体", "驿站通知日志"],
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
                "draft": v2_draft,
                "review_responses": review_responses,
            },
        }
    )
    workflows = HearingFlowWorkflows(runner)
    dossier = _trial_dossier_v2()
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
    v2_request = HearingJudgeV2Request.model_validate(
        {
            **_base("JUDGE_V2", 7),
            "trial_dossier": dossier,
            "judge_v1": v1,
            "jury_review": review,
        }
    )
    v2 = workflows.judge_v2(v2_request)

    assert review.reviewed_proposal_id == v1.proposal_id
    assert review.reviewed_proposal_hash == v1.proposal_hash
    assert v2.parent_proposal_hash == v1.proposal_hash
    assert v2.jury_review_hash == review.review_hash
    assert v1.schema_version == "hearing_judge_v1.v2"
    assert v2.schema_version == "hearing_judge_v2.v2"
    assert v2.draft_status == "PENDING_HUMAN_REVIEW"
    assert "法官 V1 裁决草案（非终局）" in v1.public_message
    assert "法官 V2 裁决草案（待人工审核）" in v2.public_message
    assert "REJECT_CLAIM（不支持本次售后诉求并结束案件）" in v1.public_message
    assert "REJECT_CLAIM（不支持本次售后诉求并结束案件）" in v2.public_message
    assert "JURY_MANDATORY_01 [ACCEPTED]" in v2.public_message
    assert v1.draft.fact_findings[1].evidence_gap == (
        "冻结 E2 中本事实认定未引用任何与该 fact_id 绑定的证据，需人工复核。"
    )
    assert v2.draft.fact_findings[1].evidence_gap == (
        "冻结 E2 中本事实认定未引用任何与该 fact_id 绑定的证据，需人工复核。"
    )
    assert content_hash(v2, hash_field="judge_v2_hash") == v2.judge_v2_hash

    v1_call = next(call for call in runner.calls if call["node_name"] == "hearing_judge_v1")
    v2_call = next(call for call in runner.calls if call["node_name"] == "hearing_judge_v2")
    v1_semantic_validator = v1_call["semantic_validator"]
    incomplete_v1_payload = {
        "draft": _adjudication_draft(),
        "review_focus": ["签收主体"],
    }
    incomplete_v1_payload["draft"]["fact_findings"] = incomplete_v1_payload[
        "draft"
    ]["fact_findings"][:1]
    incomplete_v1 = v1_call["output_type"].model_validate(incomplete_v1_payload)
    with pytest.raises(ValueError, match="must cover the frozen M2 exactly"):
        v1_semantic_validator(incomplete_v1)
    v1_context = v1_call["case_data"]
    v2_context = v2_call["case_data"]
    assert list(v1_context)[-1] == "decision_action_catalog"
    assert set(v1_context) == {
        "frozen_adjudication_context",
        "decision_action_catalog",
    }
    assert set(v1_context["frozen_adjudication_context"]) == {
        "case_fact_matrix",
        "fact_evidence_matrix",
        "adjudication_rules",
        "validation_requirements_pack",
    }
    validation_requirements = v1_context["frozen_adjudication_context"][
        "validation_requirements_pack"
    ]
    assert validation_requirements["required_fact_count"] == len(
        dossier.case_fact_matrix.fact_rows
    )
    assert [
        item["fact_id"] for item in validation_requirements["required_fact_findings"]
    ] == [item.fact_id for item in dossier.case_fact_matrix.fact_rows]
    assert validation_requirements["required_rule_count"] == len(
        dossier.adjudication_rules
    )
    assert [
        (item["rule_code"], item["rule_version"], item["rule_name"])
        for item in validation_requirements["required_rule_applications"]
    ] == [
        (item.rule_code, item.rule_version, item.rule_name)
        for item in dossier.adjudication_rules
    ]
    assert all(
        item["allowed_evidence_ids"]
        == [
            link.evidence_id
            for link in dossier.fact_evidence_matrix.links
            if link.fact_id == item["fact_id"]
        ]
        for item in validation_requirements["required_fact_findings"]
    )
    forbidden = {
        "trial_dossier_id",
        "frozen_at",
        "question_set",
        "answer_bundles",
        "evidence_request_set",
        "evidence_batches",
        "v1_draft_pack",
        "jury_opinion_pack",
    }
    assert forbidden.isdisjoint(v1_context)
    assert set(v2_context) == {
        "frozen_adjudication_context",
        "v1_draft_pack",
        "review_requirements_pack",
        "jury_opinion_pack",
        "decision_action_catalog",
    }
    assert list(v2_context)[-1] == "decision_action_catalog"
    assert v2_context["decision_action_catalog"] == v1_context[
        "decision_action_catalog"
    ]
    assert [item["code"] for item in v1_context["decision_action_catalog"]] == [
        action.value for action in HearingDecisionAction
    ]
    assert all(item["meaning"] for item in v1_context["decision_action_catalog"])
    assert (
        v2_context["frozen_adjudication_context"]
        == v1_context["frozen_adjudication_context"]
    )
    assert v2_context["v1_draft_pack"]["draft"] == v1.draft.model_dump(mode="json")
    assert set(v2_context["v1_draft_pack"]) == {"draft"}
    requirements = v2_context["review_requirements_pack"]
    assert requirements["required_response_count"] == len(review_responses)
    assert [item["review_item_ref"] for item in requirements["review_items"]] == [
        item["review_item_ref"] for item in review_responses
    ]
    assert len(v2_context["jury_opinion_pack"]["findings"]) == 6


def test_judge_v2_rejects_cross_fact_evidence_and_incomplete_review_responses() -> None:
    dimensions = [
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    ]
    runner = QueueRunner(
        {
            "hearing_judge_v1": {
                "draft": _adjudication_draft(),
                "review_focus": ["签收主体"],
            },
            "hearing_jury_review": {
                "findings": [
                    {
                        "dimension": dimension,
                        "severity": "LOW",
                        "assessment": f"{dimension} 评审意见。",
                        "basis": ["FACT_RECIPIENT"],
                        "requires_revision": False,
                    }
                    for dimension in dimensions
                ],
                "mandatory_revisions": [],
                "public_message": "评审完成。",
            },
            "hearing_judge_v2": {
                "draft": _adjudication_draft(),
                "review_responses": [],
            },
        }
    )
    workflows = HearingFlowWorkflows(runner)
    dossier = _trial_dossier_v2()
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
    request = HearingJudgeV2Request.model_validate(
        {
            **_base("JUDGE_V2", 7),
            "trial_dossier": dossier,
            "judge_v1": v1,
            "jury_review": review,
        }
    )

    invalid_binding = _adjudication_draft()
    invalid_binding["fact_findings"][1]["evidence_ids"] = ["EVIDENCE_old"]
    invalid_binding["fact_findings"][1]["evidence_gap"] = None
    runner.outputs["hearing_judge_v2"] = {
        "draft": invalid_binding,
        "review_responses": [
            {
                "review_item_ref": "V1_FOCUS_01",
                "review_source": "V1_REVIEW_FOCUS",
                "disposition": "REJECTED",
                "response": "测试引用边界。",
                "affected_fields": ["fact_findings"],
            }
        ],
    }
    with pytest.raises(AgentOutputSchemaError, match="not bound to its fact"):
        workflows.judge_v2(request)

    runner.outputs["hearing_judge_v2"] = {
        "draft": _adjudication_draft(),
        "review_responses": [
            {
                "review_item_ref": "V1_FOCUS_01",
                "review_source": "V1_REVIEW_FOCUS",
                "disposition": "ACCEPTED",
                "response": "只回应了一个项目。",
                "affected_fields": ["reviewer_attention"],
            }
        ],
    }
    with pytest.raises(AgentOutputSchemaError, match="address every assembled"):
        workflows.judge_v2(request)

    v2_call = next(
        call for call in reversed(runner.calls) if call["node_name"] == "hearing_judge_v2"
    )
    semantic_validator = v2_call["semantic_validator"]
    incomplete_output = v2_call["output_type"].model_validate(
        runner.outputs["hearing_judge_v2"]
    )
    with pytest.raises(ValueError, match="address every assembled"):
        semantic_validator(incomplete_output)

    complete_responses = [
        {
            "review_item_ref": "V1_FOCUS_01",
            "review_source": "JURY_FINDING",
            "disposition": "REJECTED",
            "response": "故意使用错误来源绑定。",
            "affected_fields": ["reviewer_attention"],
        },
        *[
            {
                "review_item_ref": f"JURY_FINDING_{dimension}",
                "review_source": "JURY_FINDING",
                "disposition": "ACCEPTED",
                "response": "已按冻结裁判依据完成复核。",
                "affected_fields": ["decision_reasoning"],
            }
            for dimension in dimensions
        ],
    ]
    runner.outputs["hearing_judge_v2"] = {
        "draft": _adjudication_draft(),
        "review_responses": complete_responses,
    }
    with pytest.raises(AgentOutputSchemaError, match="review_source binding"):
        workflows.judge_v2(request)


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
                "draft": _adjudication_draft(),
                "review_focus": ["事实完整性", "证据一致性"],
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
    dossier = _trial_dossier_v2()
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
    paths = {
        path
        for route in client.app.routes
        if (path := getattr(route, "path", None)) is not None
    }

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
