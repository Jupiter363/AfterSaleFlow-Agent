from __future__ import annotations

from dataclasses import dataclass
from typing import Final, Literal


OUTCOME_REVIEW_GRAPH_IDENTITY: Final = "outcome/review.v1"
EMPTY_OUTCOME_REVIEW_TOOL_POLICY: Final[tuple[()]] = ()
OutcomeReviewRuntimeMode = Literal[
    "DISABLED",
    "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
]


@dataclass(frozen=True, slots=True)
class OutcomeReviewGraphIdentity:
    identity: str = OUTCOME_REVIEW_GRAPH_IDENTITY
    graph_key: str = "outcome/review"
    graph_version: str = "outcome.review.v1"
    state_schema_version: str = "outcome.review.graph-state.v1"
    checkpoint_schema_version: str = "outcome.review.checkpoint.v1"
    command_schema_version: str = "outcome-graph-command.v1"
    result_schema_version: str = "outcome-graph-result.v1"
    prompt_version: str = "outcome.review.prompt.v1"
    model_profile_id: str = "outcome.review.model-profile.v1"
    output_schema_version: str = "ReviewCopilotAnswer.v1"
    policy_version: str = "outcome.review.advisory-only.v1"
    guardrail_version: str = "outcome.review.guardrails.v1"
    tool_policy_version: str = "outcome.review.no-tools.v1"


OUTCOME_REVIEW_IDENTITY: Final = OutcomeReviewGraphIdentity()


__all__ = [
    "EMPTY_OUTCOME_REVIEW_TOOL_POLICY",
    "OUTCOME_REVIEW_GRAPH_IDENTITY",
    "OUTCOME_REVIEW_IDENTITY",
    "OutcomeReviewGraphIdentity",
    "OutcomeReviewRuntimeMode",
]
