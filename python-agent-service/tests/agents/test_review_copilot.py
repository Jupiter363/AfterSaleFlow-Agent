from __future__ import annotations

import pytest

from app.agents.review_copilot import ReviewCopilot
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest, ReviewStatement


def _request() -> ReviewCopilotRequest:
    return ReviewCopilotRequest(
        review_id="REVIEW_1",
        case_id="CASE_1",
        review_packet_version=1,
        reviewer_role="PLATFORM_REVIEWER",
        question="What should the reviewer inspect?",
        available_fact_refs=["FACT_1"],
    )


def _answer() -> ReviewCopilotAnswer:
    return ReviewCopilotAnswer(
        answer="Inspect the cited frozen fact.",
        statements=[
            ReviewStatement(
                kind="SUGGESTION",
                text="Inspect the frozen fact.",
                refs=["FACT_1"],
            )
        ],
        fact_refs=["FACT_1"],
    )


def test_legacy_answerer_remains_compatible_and_has_no_tools() -> None:
    answer = _answer()
    copilot = ReviewCopilot(lambda _request: answer)

    assert copilot.query(_request()) == answer
    assert copilot.profile.allowed_tools == frozenset()
    assert copilot.profile.budget.max_tool_calls == 0


def test_explicit_private_graph_runner_is_compatible() -> None:
    answer = _answer()

    class _Runner:
        def __init__(self) -> None:
            self.requests: list[ReviewCopilotRequest] = []

        def query(self, request: ReviewCopilotRequest) -> ReviewCopilotAnswer:
            self.requests.append(request)
            return answer

    runner = _Runner()
    copilot = ReviewCopilot(graph_runner=runner)
    request = _request()

    assert copilot.query(request) == answer
    assert runner.requests == [request]


def test_adapter_requires_exactly_one_execution_path() -> None:
    with pytest.raises(ValueError, match="exactly one"):
        ReviewCopilot()
    with pytest.raises(ValueError, match="exactly one"):
        ReviewCopilot(lambda _request: _answer(), graph_runner=object())  # type: ignore[arg-type]
