from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.agents.model_roles import ModelReviewAnswerer
from app.agents.review_copilot import ReviewCopilot
from app.agents.review_output import review_answer_type
from app.harness.prompt_composer import PromptRepository
from app.harness.validation import CitationValidationError
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


def _request():
    return ReviewCopilotRequest(
        review_id="REVIEW_1", case_id="CASE_1", review_packet_version=1,
        reviewer_role="PLATFORM_REVIEWER", question="Explain the frozen plan, not a decision.",
        available_fact_refs=["FACT_1", "FACT_2"], available_rule_refs=["RULE_1"],
        available_draft_refs=["DRAFT_1"], available_deliberation_refs=["DELIBERATION_1"],
        frozen_packet={"remedy_plan": {"id": "PLAN_NOT_AUTHORIZED", "effect": "NO_EXTERNAL_EFFECT"}},
    )


def _answer():
    return {
        "answer": "Only explain the frozen plan; no decision or effect is performed.",
        "statements": [{"kind": "SUGGESTION", "text": "Inspect the plan in the frozen draft.",
                        "refs": ["DRAFT_1"]}],
        "fact_refs": ["FACT_1"], "rule_refs": ["RULE_1"],
        "draft_refs": ["DRAFT_1"], "deliberation_refs": ["DELIBERATION_1"],
    }


def test_real_model_adapter_receives_request_scoped_schema_context_and_prompt():
    calls = []

    class CaptureLlm:
        def generate(self, **kwargs):
            calls.append(kwargs)
            schema = kwargs["output_type"].model_json_schema()
            assert schema["properties"]["fact_refs"]["items"]["enum"] == ["FACT_1", "FACT_2"]
            statement = schema["$defs"]["RequestBoundReviewStatement"]
            choices = statement["properties"]["refs"]["items"]["enum"]
            assert choices == ["DELIBERATION_1", "DRAFT_1", "FACT_1", "FACT_2", "RULE_1"]
            assert '"citation_catalog"' in kwargs["user_prompt"]
            assert '"PLAN_NOT_AUTHORIZED"' in kwargs["user_prompt"]  # Preserve original material.
            assert "不自动获得引用资格" in kwargs["system_prompt"]
            return SimpleNamespace(value=kwargs["output_type"].model_validate(_answer()))

    request = _request()
    original = request.model_dump()
    result = ModelReviewAnswerer(CaptureLlm(), PromptRepository())(request)
    assert type(result) is ReviewCopilotAnswer
    assert ReviewCopilot(lambda _: result).query(request) == result
    assert request.model_dump() == original
    assert len(calls) == 1


@pytest.mark.parametrize("field,wrong", [
    ("fact_refs", "DRAFT_1"), ("rule_refs", "FACT_1"),
    ("draft_refs", "RULE_1"), ("deliberation_refs", "DRAFT_1"),
])
def test_each_top_level_reference_group_is_closed(field, wrong):
    payload = {**_answer(), field: [wrong]}
    with pytest.raises(ValidationError):
        review_answer_type(_request()).model_validate(payload)


@pytest.mark.parametrize("kind", ["FACT", "INFERENCE", "SUGGESTION"])
def test_packet_id_is_not_a_citation_capability_even_when_present_in_material(kind):
    payload = _answer()
    payload["statements"] = [{"kind": kind, "text": "Read-only explanation.",
                              "refs": ["PLAN_NOT_AUTHORIZED"]}]
    # Decisive old boundary: the static schema admits this shape, the authority
    # validator rejects it. The model's new schema now rejects it as well.
    static = ReviewCopilotAnswer.model_validate(payload)
    with pytest.raises(CitationValidationError, match="PLAN_NOT_AUTHORIZED"):
        ReviewCopilot(lambda _: static).query(_request())
    with pytest.raises(ValidationError):
        review_answer_type(_request()).model_validate(payload)


def test_empty_catalog_allows_explanation_without_fabricated_citations():
    request = _request().model_copy(update={
        "available_fact_refs": [], "available_rule_refs": [],
        "available_draft_refs": [], "available_deliberation_refs": [],
    })
    model = review_answer_type(request)
    payload = {"answer": "No authorized citation is available.",
               "statements": [{"kind": "SUGGESTION", "text": "Review manually.", "refs": []}]}
    assert model.model_validate(payload).fact_refs == []
    assert model.model_json_schema()["properties"]["fact_refs"]["maxItems"] == 0
    payload["statements"][0]["refs"] = ["PLAN_NOT_AUTHORIZED"]
    with pytest.raises(ValidationError):
        model.model_validate(payload)


def test_request_models_do_not_leak_reference_authority_and_formal_flags_stay_false():
    first = review_answer_type(_request())
    second = review_answer_type(_request().model_copy(update={"available_fact_refs": ["OTHER_FACT"]}))
    assert first.model_validate(_answer()).fact_refs == ["FACT_1"]
    with pytest.raises(ValidationError):
        second.model_validate(_answer())
    for field in ("approval_performed", "execution_triggered", "is_final_decision"):
        with pytest.raises(ValidationError):
            first.model_validate({**_answer(), field: True})


def test_adapter_revalidates_even_if_provider_returns_static_unbound_shape():
    payload = _answer()
    payload["statements"][0]["refs"] = ["PLAN_NOT_AUTHORIZED"]

    class UnboundLlm:
        def generate(self, **kwargs):
            return SimpleNamespace(value=payload)

    with pytest.raises(ValidationError):
        ModelReviewAnswerer(UnboundLlm(), PromptRepository())(_request())
