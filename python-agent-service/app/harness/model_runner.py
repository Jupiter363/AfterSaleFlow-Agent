from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from langchain_core.messages import BaseMessage
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel

from app.harness.context_window import AssembledPromptContext, ContextWindowManager, PromptSection
from app.harness.context_pack import ContextPack
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
        context_pack: ContextPack | None = None,
        max_input_tokens: int | None = None,
        agent_context: Any | None = None,
        prompt_profile_id: str | None = None,
        multimodal_parts: list[dict[str, Any]] | None = None,
    ) -> HarnessGeneration[T]:
        assembled_context = self._context_window.assemble(
            context_pack.prompt_sections() if context_pack is not None else context_sections or [],
            max_input_tokens=max_input_tokens,
        )
        trusted_agent_context = _trusted_agent_context_payload(agent_context)
        resolved_prompt_profile_id = prompt_profile_id or trusted_agent_context.get(
            "prompt_profile_id"
        )
        enriched_case_data = {
            **case_data,
            "harness_context": assembled_context.as_prompt_payload(),
        }
        if context_pack is not None:
            enriched_case_data["harness_context_pack"] = {
                "node_name": context_pack.node_name,
                "configuration_profile_key": context_pack.configuration_profile_key,
                "configuration_source": context_pack.configuration_source,
                "display_only_section_names": list(
                    context_pack.display_only_section_names
                ),
            }
        system_prompt, user_prompt = self._prompts.render(
            node_name,
            enriched_case_data,
            output_type.model_json_schema(),
            prompt_profile_id=resolved_prompt_profile_id,
            trusted_agent_context=trusted_agent_context or None,
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
        generation_args = {
            "node_name": node_name,
            "system_prompt": str(messages[0].content),
            "user_prompt": str(messages[1].content),
            "output_type": output_type,
        }
        if multimodal_parts:
            generation_args["user_content_parts"] = multimodal_parts
        generation: StructuredGeneration = self._llm.generate(**generation_args)
        return HarnessGeneration(
            value=generation.value,  # type: ignore[arg-type]
            model=generation.model,
            latency_ms=generation.latency_ms,
            token_usage=generation.token_usage,
            context=assembled_context,
            messages=messages,
        )


def _trusted_agent_context_payload(agent_context: Any | None) -> dict[str, Any]:
    if agent_context is None:
        return {}
    if isinstance(agent_context, BaseModel):
        raw_context = agent_context.model_dump(mode="json")
    elif isinstance(agent_context, dict):
        raw_context = dict(agent_context)
    else:
        return {}

    allowed_fields = (
        "case_id",
        "room_type",
        "actor_id",
        "actor_role",
        "agent_key",
        "agent_invocation_id",
        "agent_session_id",
        "scope_type",
        "allowed_actor_ids",
        "allowed_actor_roles",
        "prompt_profile_id",
    )
    return {
        field: raw_context[field]
        for field in allowed_fields
        if raw_context.get(field) is not None
    }
