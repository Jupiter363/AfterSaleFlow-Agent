"""Read-only reviewer assistant constrained to one frozen ReviewPacket."""

from __future__ import annotations

from collections.abc import Callable

from app.agents.profiles import final_agent_profiles
from app.harness.guardrails import GuardrailChecker
from app.harness.validation import CitationValidationError
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


ReviewAnswerer = Callable[[ReviewCopilotRequest], ReviewCopilotAnswer]


class ReviewCopilot:
    def __init__(self, answerer: ReviewAnswerer) -> None:
        self.profile = final_agent_profiles()["review_copilot"]
        self._answerer = answerer
        self._guardrails = GuardrailChecker()

    def query(self, request: ReviewCopilotRequest) -> ReviewCopilotAnswer:
        self._guardrails.assert_safe_input(request.question)
        answer = ReviewCopilotAnswer.model_validate(self._answerer(request))
        self._validate_refs(
            "fact",
            answer.fact_refs,
            request.available_fact_refs,
        )
        self._validate_refs(
            "rule",
            answer.rule_refs,
            request.available_rule_refs,
        )
        self._validate_refs(
            "draft",
            answer.draft_refs,
            request.available_draft_refs,
        )
        self._validate_refs(
            "deliberation",
            answer.deliberation_refs,
            request.available_deliberation_refs,
        )
        available_refs = (
            request.available_fact_refs
            + request.available_rule_refs
            + request.available_draft_refs
            + request.available_deliberation_refs
        )
        for statement in answer.statements:
            self._validate_refs(
                f"{statement.kind.lower()} statement",
                statement.refs,
                available_refs,
            )
            self._guardrails.assert_safe_output(statement.text)
        self._guardrails.assert_safe_output(answer.answer)
        return answer

    @staticmethod
    def _validate_refs(
        kind: str,
        actual: list[str],
        available: list[str],
    ) -> None:
        unknown = set(actual) - set(available)
        if unknown:
            raise CitationValidationError(
                f"unknown {kind} references: {sorted(unknown)}"
            )
