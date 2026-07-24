from __future__ import annotations

from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnableSequence
import pytest

from app.graphs.outcome.contracts import EMPTY_OUTCOME_REVIEW_TOOL_POLICY
from app.graphs.outcome.errors import OutcomeReviewLcelError
from app.graphs.outcome.lcel import (
    GovernedOutcomeReviewModelAdapter,
    build_outcome_review_lcel,
    invoke_outcome_review_lcel,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


def test_lcel_is_real_prompt_pipe_model_pipe_strict_parser(
    review_answer: ReviewCopilotAnswer,
) -> None:
    flow = build_outcome_review_lcel(answerer=lambda _request: review_answer)

    assert isinstance(flow.runnable, RunnableSequence)
    assert isinstance(flow.prompt, ChatPromptTemplate)
    assert isinstance(flow.model, GovernedOutcomeReviewModelAdapter)
    assert isinstance(flow.parser, PydanticOutputParser)
    assert flow.runnable.first is flow.prompt
    assert flow.runnable.middle == [flow.model]
    assert flow.runnable.last is flow.parser
    assert flow.model.tool_policy == EMPTY_OUTCOME_REVIEW_TOOL_POLICY == ()


def test_lcel_preserves_exact_typed_request_and_answer(
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
) -> None:
    received: list[ReviewCopilotRequest] = []

    def answerer(request: ReviewCopilotRequest) -> ReviewCopilotAnswer:
        received.append(request)
        return review_answer

    result = invoke_outcome_review_lcel(answerer=answerer, request=review_request)

    assert result == review_answer
    assert received == [review_request]


def test_lcel_rejects_unknown_output_fields(
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
) -> None:
    payload = review_answer.model_dump(mode="json")
    payload["formal_decision"] = "APPROVE"

    with pytest.raises(OutcomeReviewLcelError, match="MODEL_INPUT_OR_OUTPUT_INVALID"):
        invoke_outcome_review_lcel(
            answerer=lambda _request: payload,  # type: ignore[arg-type,return-value]
            request=review_request,
        )


def test_model_adapter_rejects_any_tool_policy(
    review_answer: ReviewCopilotAnswer,
) -> None:
    with pytest.raises(OutcomeReviewLcelError, match="TOOL_POLICY_FORBIDDEN"):
        GovernedOutcomeReviewModelAdapter(
            answerer=lambda _request: review_answer,
            tool_policy=("review.approve",),  # type: ignore[arg-type]
        )
