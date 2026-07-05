from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from langchain_core.messages import BaseMessage
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel

from app.harness.context_window import AssembledPromptContext, ContextWindowManager, PromptSection
from app.harness.prompt_composer import PromptRepository
from app.llm import StructuredGeneration, StructuredLlmClient


T = TypeVar("T", bound=BaseModel)


@dataclass(frozen=True)
class HarnessGeneration(Generic[T]):
    value: T
    model: str
    latency_ms: int
    token_usage: dict[str, int]
    context: AssembledPromptContext
    messages: tuple[BaseMessage, ...]


class HarnessModelRunner:
    """Shared LangChain-compatible structured LLM runner for Agent nodes."""

    def __init__(
        self,
        *,
        llm: StructuredLlmClient,
        prompts: PromptRepository,
        context_window: ContextWindowManager | None = None,
    ) -> None:
        self._llm = llm
        self._prompts = prompts
        self._context_window = context_window or ContextWindowManager()

    def invoke_structured(
        self,
        *,
        node_name: str,
        case_data: dict[str, Any],
        output_type: type[T],
        context_sections: list[PromptSection] | None = None,
        max_input_tokens: int | None = None,
    ) -> HarnessGeneration[T]:
        assembled_context = self._context_window.assemble(
            context_sections or [],
            max_input_tokens=max_input_tokens,
        )
        enriched_case_data = {
            **case_data,
            "harness_context": assembled_context.as_prompt_payload(),
        }
        system_prompt, user_prompt = self._prompts.render(
            node_name,
            enriched_case_data,
            output_type.model_json_schema(),
        )
        messages = tuple(
            ChatPromptTemplate.from_messages(
                [
                    ("system", "{system_prompt}"),
                    ("human", "{user_prompt}"),
                ]
            ).format_messages(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
            )
        )
        generation: StructuredGeneration = self._llm.generate(
            node_name=node_name,
            system_prompt=str(messages[0].content),
            user_prompt=str(messages[1].content),
            output_type=output_type,
        )
        return HarnessGeneration(
            value=generation.value,  # type: ignore[arg-type]
            model=generation.model,
            latency_ms=generation.latency_ms,
            token_usage=generation.token_usage,
            context=assembled_context,
            messages=messages,
        )
