import pytest
from pydantic import ValidationError

from app.schemas import AdjudicationDraft


def test_adjudication_output_can_only_be_a_non_final_human_review_draft() -> None:
    payload = {
        "recommended_outcome": "Refund may be appropriate",
        "reasoning_summary": "Evidence and policy support a draft recommendation.",
        "issue_findings": [],
        "confidence": 0.8,
        "risk_level": "MEDIUM",
        "review_focus": ["Confirm policy applicability"],
        "requires_human_review": True,
        "is_final_decision": False,
    }

    assert AdjudicationDraft.model_validate(payload).draft_status == "PENDING_HUMAN_REVIEW"

    with pytest.raises(ValidationError):
        AdjudicationDraft.model_validate({**payload, "is_final_decision": True})
    with pytest.raises(ValidationError):
        AdjudicationDraft.model_validate({**payload, "requires_human_review": False})
    with pytest.raises(ValidationError):
        AdjudicationDraft.model_validate({**payload, "execution_actions": ["REFUND"]})
