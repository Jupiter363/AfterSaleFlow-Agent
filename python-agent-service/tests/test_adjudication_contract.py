from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.adjudication_contract import (
    c6_output_type_for_request,
    mandatory_c6_review_responses,
    trusted_c6_review_contract,
)


def _request() -> dict[str, object]:
    return {
        "hearing_context": {
            "final_convergence": True,
            "must_produce_final_plan": True,
            "courtroom_context": {
                "jury_review_report": {
                    "payload": {
                        "findings": [
                            {
                                "dimension": "FACT_COMPLETENESS",
                                "severity": "HIGH",
                                "requires_revision": True,
                            },
                            {
                                "dimension": "EVIDENCE_CONSISTENCY",
                                "severity": "HIGH",
                                "requires_revision": False,
                            },
                            {
                                "dimension": "RULE_APPLICABILITY",
                                "severity": "LOW",
                                "requires_revision": False,
                            },
                            {
                                "dimension": "PROCEDURAL_FAIRNESS",
                                "severity": "LOW",
                                "requires_revision": False,
                            },
                            {
                                "dimension": "REMEDY_FEASIBILITY",
                                "severity": "MEDIUM",
                                "requires_revision": True,
                            },
                            {
                                "dimension": "RISK_AND_OMISSIONS",
                                "severity": "HIGH",
                                "requires_revision": True,
                            },
                        ]
                    }
                }
            },
        }
    }


def _draft(responses: list[dict[str, object]]) -> dict[str, object]:
    return {
        "draft": {
            "recommended_outcome": "转平台人工审核",
            "reasoning_summary": "V2逐项吸收评审意见后形成非最终建议。",
            "issue_findings": [],
            "confidence": 0.6,
            "risk_level": "HIGH",
            "review_focus": ["人工核对冻结证据"],
            "jury_review_responses": responses,
            "requires_human_review": True,
            "is_final_decision": False,
        }
    }


def _response(dimension: str, severity: str) -> dict[str, object]:
    return {
        "dimension": dimension,
        "severity": severity,
        "disposition": "ACCEPTED",
        "response": "V2已按该项意见补充人工核验范围。",
        "basis": ["统一评审报告对应维度的冻结结论"],
    }


def test_c6_contract_extracts_only_mandatory_review_findings() -> None:
    requirements = mandatory_c6_review_responses(_request())

    assert requirements == (
        ("FACT_COMPLETENESS", "HIGH"),
        ("EVIDENCE_CONSISTENCY", "HIGH"),
        ("REMEDY_FEASIBILITY", "MEDIUM"),
        ("RISK_AND_OMISSIONS", "HIGH"),
    )
    assert trusted_c6_review_contract(_request())[
        "c6_v2_mandatory_review_responses"
    ]["required_count"] == 4


def test_final_c6_provider_schema_requires_exact_response_count() -> None:
    output_type = c6_output_type_for_request(_request())
    responses = [
        _response(dimension, severity)
        for dimension, severity in mandatory_c6_review_responses(_request())
    ]

    validated = output_type.model_validate(_draft(responses))
    assert len(validated.draft.jury_review_responses) == 4

    with pytest.raises(ValidationError):
        output_type.model_validate(_draft(responses[:-1]))

    schema = output_type.model_json_schema()
    draft_name = next(
        name
        for name in schema["$defs"]
        if name.startswith("FinalConvergenceAdjudicationDraft4")
    )
    response_schema = schema["$defs"][draft_name]["properties"][
        "jury_review_responses"
    ]
    assert response_schema["minItems"] == 4
    assert response_schema["maxItems"] == 4
