"""LiteLLM-backed evaluators for deliberation and review assistance."""

from __future__ import annotations

from app.llm import StructuredLlmClient
from app.harness.prompt_composer import PromptRepository
from app.schemas import (
    CriticDraft,
    CriticType,
    FrozenDeliberationInput,
    ReviewCopilotAnswer,
    ReviewCopilotRequest,
)


class ModelCriticEvaluator:
    """Run each critic with an independent prompt and a shared frozen payload."""

    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
    ) -> None:
        self._llm = llm
        self._prompts = prompts

    def __call__(
        self,
        critic_type: CriticType,
        frozen_input: FrozenDeliberationInput,
        fingerprint: str,
    ) -> CriticDraft:
        node_name = critic_type.value.lower()
        system_prompt, user_prompt = self._prompts.render(
            node_name,
            {
                "frozen_input_fingerprint": fingerprint,
                "frozen_input": frozen_input.model_dump(mode="json"),
            },
            CriticDraft.model_json_schema(),
        )
        generation = self._llm.generate(
            node_name=node_name,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            output_type=CriticDraft,
        )
        return CriticDraft.model_validate(generation.value)


class ModelReviewAnswerer:
    """Answer only from the versioned ReviewPacket embedded in the request."""

    def __init__(
        self,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
    ) -> None:
        self._llm = llm
        self._prompts = prompts

    def __call__(
        self,
        request: ReviewCopilotRequest,
    ) -> ReviewCopilotAnswer:
        system_prompt, user_prompt = self._prompts.render(
            "review_copilot",
            request.model_dump(mode="json"),
            ReviewCopilotAnswer.model_json_schema(),
        )
        generation = self._llm.generate(
            node_name="review_copilot",
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            output_type=ReviewCopilotAnswer,
        )
        return ReviewCopilotAnswer.model_validate(generation.value)
