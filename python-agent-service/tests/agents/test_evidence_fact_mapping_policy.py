from __future__ import annotations

import pytest

from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
from app.agents.evidence_clerk.workflow import _validated_matrix_patch
from app.harness.evidence_context_assembler import EvidenceTurnWorkingSet
from app.schemas import (
    EvidenceFactLink,
    EvidenceFactMatrixPatch,
    EvidenceItemAssessment,
    EvidenceTurnEvidenceItem,
)


EVIDENCE_ID = "EVIDENCE_mapping_1"
FACT_ID = "FACT_PAYMENT_1"


def _working_set() -> EvidenceTurnWorkingSet:
    return EvidenceTurnWorkingSet(
        case_id="CASE_evidence_mapping",
        room_type="EVIDENCE",
        turn_source="EVIDENCE_SUBMITTED",
        task_mode="EVIDENCE_REVIEW",
        actor_role="USER",
        actor_id="USER_local_1",
        current_event={"attachment_refs": [EVIDENCE_ID]},
        case_intake_dossier={},
        allowed_fact_targets=(
            {
                "fact_id": FACT_ID,
                "fact": "用户是否支付了争议款项",
                "materiality": "CORE",
                "truth_status": "NOT_EVALUATED",
            },
        ),
        available_evidence=(
            EvidenceTurnEvidenceItem(
                evidence_id=EVIDENCE_ID,
                evidence_type="DOCUMENT",
                source_type="USER",
                content="支付宝付款记录，金额150元。",
                claimed_fact="证明用户已经支付150元争议款项",
                truth_attested=True,
            ),
        ),
        evidence_matrix_snapshot={"version": 0, "matrix": []},
    )


def _assessment(
    *,
    relevance: float,
    authenticity: float = 0.9,
    completeness: float = 0.9,
    fact_links: list[EvidenceFactLink] | None = None,
) -> EvidenceItemAssessment:
    return EvidenceItemAssessment(
        evidence_id=EVIDENCE_ID,
        analysis_method="TEXT_ONLY",
        inspected_modalities=["FILE_METADATA"],
        fact_links=fact_links or [],
        authenticity_score=authenticity,
        relevance_score=relevance,
        completeness_score=completeness,
        assessment_confidence=0.8,
        source_basis=["付款记录内容与文件元数据"],
        supported_fact_ids=[],
        unsupported_claims=[],
        formation_time_assessment="记录包含付款时间，但仍需核对来源链。",
        findings=[],
        limitations=[],
        risk_flags=[],
        recommendation="PLAUSIBLE",
        summary="材料与争议付款事实存在关联。",
    )


def _link(
    *,
    relation: str = "SUPPORTS",
    fact_id: str = FACT_ID,
) -> EvidenceFactLink:
    return EvidenceFactLink(
        fact_id=fact_id,
        relation=relation,
        reason="付款记录内容对应争议款项支付事实。",
        confidence=0.78,
    )


def _apply(assessment: EvidenceItemAssessment) -> EvidenceItemAssessment:
    return EvidenceAssessmentPolicy().apply(
        [assessment],
        _working_set(),
        {
            "items": [
                {
                    "evidence_id": EVIDENCE_ID,
                    "visual_input_status": "NOT_REQUESTED",
                    "inspected_modalities": ["FILE_METADATA"],
                }
            ]
        },
    )[0]


def test_relevant_evidence_recovers_auditable_inconclusive_coordinate() -> None:
    result = _apply(_assessment(relevance=0.86, fact_links=[]))

    assert [link.fact_id for link in result.fact_links] == [FACT_ID]
    assert result.fact_links[0].relation == "INCONCLUSIVE"
    assert result.supported_fact_ids == []
    assert result.human_review.required is True
    assert "FACT_LINK_RECOVERED_FROM_SHARED_TEXT" in (
        result.human_review.reason_codes
    )


def test_unknown_fact_link_is_blocked_then_recovers_only_an_allowed_coordinate() -> None:
    result = _apply(
        _assessment(
            relevance=0.86,
            fact_links=[_link(fact_id="FACT_NOT_ALLOWED")],
        )
    )

    assert [link.fact_id for link in result.fact_links] == [FACT_ID]
    assert result.fact_links[0].relation == "INCONCLUSIVE"
    assert "UNKNOWN_FACT_REFERENCE" in result.human_review.reason_codes
    assert "FACT_LINK_RECOVERED_FROM_SHARED_TEXT" in (
        result.human_review.reason_codes
    )


@pytest.mark.parametrize(
    ("authenticity", "completeness"),
    [(0.49, 0.9), (0.9, 0.49)],
)
def test_weak_evidence_keeps_fact_coordinate_as_inconclusive(
    authenticity: float,
    completeness: float,
) -> None:
    result = _apply(
        _assessment(
            relevance=0.86,
            authenticity=authenticity,
            completeness=completeness,
            fact_links=[_link()],
        )
    )

    assert [link.fact_id for link in result.fact_links] == [FACT_ID]
    assert result.fact_links[0].relation == "INCONCLUSIVE"
    assert result.supported_fact_ids == []


def test_low_relevance_evidence_may_remain_unmapped() -> None:
    result = _apply(_assessment(relevance=0.49, fact_links=[]))

    assert result.fact_links == []
    assert result.supported_fact_ids == []


def test_matrix_upsert_is_derived_from_normalized_fact_links() -> None:
    result = _apply(
        _assessment(
            relevance=0.86,
            fact_links=[_link()],
        )
    )

    patches = _validated_matrix_patch([], _working_set(), [result])

    assert patches == [
        {
            "operation": "UPSERT_LINK",
            "fact_id": FACT_ID,
            "evidence_id": EVIDENCE_ID,
            "relation": "SUPPORTS",
            "reason": "付款记录内容对应争议款项支付事实。",
            "confidence": 0.78,
        }
    ]


def test_explicit_remove_link_remains_supported_without_a_new_link() -> None:
    removal = EvidenceFactMatrixPatch(
        operation="REMOVE_LINK",
        fact_id=FACT_ID,
        evidence_id=EVIDENCE_ID,
        relation="INCONCLUSIVE",
        reason="本轮复核确认原关联对象错误。",
        confidence=0.8,
    )

    assert _validated_matrix_patch([removal], _working_set(), []) == [
        removal.model_dump(mode="json")
    ]
