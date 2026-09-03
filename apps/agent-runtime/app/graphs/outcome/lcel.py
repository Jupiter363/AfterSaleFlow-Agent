from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, cast

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompt_values import ChatPromptValue, PromptValue
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import Runnable, RunnableConfig, RunnableSequence

from app.graphs.outcome.contracts import EMPTY_OUTCOME_REVIEW_TOOL_POLICY
from app.graphs.outcome.errors import OutcomeReviewLcelError
from app.graphs.outcome.state import ReviewAnswerer
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


_SYSTEM_PROMPT = """You are a private, read-only review copilot.
Answer only from the single frozen ReviewPacket supplied by the caller and cite only its authorized
reference identifiers. Explain uncertainty and review focus. Never approve, reject, modify an
action plan, issue a human decision, trigger execution, call a tool, or claim a case transition.
Return only the strict ReviewCopilotAnswer JSON object requested by the parser."""


class GovernedOutcomeReviewModelAdapter(Runnable[PromptValue, AIMessage]):
    """Expose the existing governed structured answerer as PromptValue -> AIMessage."""

    def __init__(
        self,
        *,
        answerer: ReviewAnswerer,
        tool_policy: tuple[()] = EMPTY_OUTCOME_REVIEW_TOOL_POLICY,
    ) -> None:
        if not callable(answerer):
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_MODEL_RUNNER_UNAVAILABLE")
        if tool_policy != EMPTY_OUTCOME_REVIEW_TOOL_POLICY:
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_TOOL_POLICY_FORBIDDEN")
        if ReviewCopilotAnswer.model_config.get("extra") != "forbid":
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_OUTPUT_SCHEMA_NOT_STRICT")
        self._answerer = answerer
        self.tool_policy = tool_policy

    def invoke(
        self,
        input: PromptValue,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> AIMessage:
        del config
        if kwargs:
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_MODEL_OVERRIDES_FORBIDDEN")
        if not isinstance(input, ChatPromptValue):
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_PROMPT_VALUE_INVALID")
        messages = input.to_messages()
        if (
            len(messages) != 2
            or not isinstance(messages[0], SystemMessage)
            or not isinstance(messages[1], HumanMessage)
            or not isinstance(messages[1].content, str)
        ):
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_MESSAGE_FLOW_INVALID")
        try:
            payload = json.loads(messages[1].content)
            request = ReviewCopilotRequest.model_validate(payload)
            answer = ReviewCopilotAnswer.model_validate(self._answerer(request))
        except (TypeError, ValueError) as error:
            raise OutcomeReviewLcelError("OUTCOME_REVIEW_MODEL_INPUT_OR_OUTPUT_INVALID") from error
        return AIMessage(content=answer.model_dump_json())


@dataclass(frozen=True, slots=True)
class OutcomeReviewLcelFlow:
    prompt: ChatPromptTemplate
    model: GovernedOutcomeReviewModelAdapter
    parser: PydanticOutputParser[ReviewCopilotAnswer]
    runnable: RunnableSequence


def build_outcome_review_lcel(*, answerer: ReviewAnswerer) -> OutcomeReviewLcelFlow:
    prompt = ChatPromptTemplate.from_messages(
        [
            SystemMessage(content=_SYSTEM_PROMPT),
            ("human", "{review_request_json}"),
        ]
    )
    model = GovernedOutcomeReviewModelAdapter(answerer=answerer)
    parser = PydanticOutputParser(pydantic_object=ReviewCopilotAnswer)
    runnable = cast(RunnableSequence, prompt | model | parser)
    return OutcomeReviewLcelFlow(
        prompt=prompt,
        model=model,
        parser=parser,
        runnable=runnable,
    )


def invoke_outcome_review_lcel(
    *,
    answerer: ReviewAnswerer,
    request: ReviewCopilotRequest,
) -> ReviewCopilotAnswer:
    encoded = json.dumps(
        request.model_dump(mode="json"),
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    result = build_outcome_review_lcel(answerer=answerer).runnable.invoke(
        {"review_request_json": encoded},
        config={
            "tags": ["governed-lcel", "outcome-review", "advisory-only"],
            "recursion_limit": 8,
        },
    )
    return ReviewCopilotAnswer.model_validate(result)


__all__ = [
    "GovernedOutcomeReviewModelAdapter",
    "OutcomeReviewLcelFlow",
    "build_outcome_review_lcel",
    "invoke_outcome_review_lcel",
]
