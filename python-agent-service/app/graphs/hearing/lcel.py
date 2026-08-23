from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Generic, TypeVar, cast

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompt_values import ChatPromptValue, PromptValue
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import Runnable, RunnableConfig, RunnableSequence
from pydantic import BaseModel

from app.graphs.hearing.contracts import (
    EMPTY_HEARING_TOOL_POLICY,
    HEARING_MODEL_NODE_PROMPTS,
)
from app.graphs.hearing.errors import HearingLcelContractError
from app.harness.context_pack import build_context_pack
from app.harness.hearing_intake_context_v4 import (
    HEARING_INTAKE_CONTEXT_V4_MAX_ESTIMATED_TOKENS,
    HEARING_INTAKE_CONTEXT_V4_NODES,
    assemble_hearing_intake_context_v4,
)
from app.harness.hearing_room_context_v3 import (
    HEARING_ROOM_CONTEXT_V3_NODES,
    assemble_hearing_room_context_v3,
)
from app.harness.invocation_context import AgentInvocationContext


TOutput = TypeVar("TOutput", bound=BaseModel)
_PROMPT_ROOT = Path(__file__).resolve().parents[2] / "agents" / "prompts"
_ORDERED_CONTEXT_TAIL_NODES = frozenset({"hearing_judge_v1", "hearing_judge_v2"})


class GovernedHearingModelAdapter(Runnable[PromptValue, AIMessage], Generic[TOutput]):
    """Compatibility adapter from LangChain messages to the governed model runner.

    The injected runner retains provider budgets, retries, guardrails and audit metadata.
    This adapter freezes the Hearing-specific empty tool policy and keeps the object flow
    visible to LangChain as PromptValue -> AIMessage.
    """

    def __init__(
        self,
        *,
        model_runner: Any,
        node_name: str,
        output_type: type[TOutput],
        semantic_validator: Callable[[TOutput], TOutput] | None = None,
        agent_context: AgentInvocationContext | None = None,
        tool_policy: tuple[()] = EMPTY_HEARING_TOOL_POLICY,
    ) -> None:
        if model_runner is None or not callable(getattr(model_runner, "invoke_structured", None)):
            raise HearingLcelContractError("HEARING_MODEL_RUNNER_UNAVAILABLE")
        if node_name not in HEARING_MODEL_NODE_PROMPTS:
            raise HearingLcelContractError("HEARING_MODEL_NODE_UNKNOWN")
        if output_type.model_config.get("extra") != "forbid":
            raise HearingLcelContractError("HEARING_OUTPUT_SCHEMA_NOT_STRICT")
        if tool_policy != EMPTY_HEARING_TOOL_POLICY:
            raise HearingLcelContractError("HEARING_FORMAL_TOOL_POLICY_FORBIDDEN")
        self._model_runner = model_runner
        self.node_name = node_name
        self.output_type = output_type
        self.semantic_validator = semantic_validator
        self.agent_context = agent_context
        self.tool_policy = tool_policy

    def invoke(
        self,
        input: PromptValue,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> AIMessage:
        del config, kwargs
        invocation = self._invocation(input)
        generation = self._model_runner.invoke_structured(**invocation)
        value = self.output_type.model_validate(generation.value)
        return AIMessage(content=value.model_dump_json())

    async def ainvoke(
        self,
        input: PromptValue,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> AIMessage:
        del config, kwargs
        invocation = self._invocation(input)
        ainvoke_structured = getattr(
            self._model_runner, "ainvoke_structured", None
        )
        if not callable(ainvoke_structured):
            raise HearingLcelContractError("HEARING_ASYNC_MODEL_RUNNER_UNAVAILABLE")
        generation = await ainvoke_structured(**invocation)
        value = self.output_type.model_validate(generation.value)
        return AIMessage(content=value.model_dump_json())

    def _invocation(self, input: PromptValue) -> dict[str, Any]:
        if not isinstance(input, ChatPromptValue):
            raise HearingLcelContractError("HEARING_PROMPT_VALUE_INVALID")
        messages = input.to_messages()
        if (
            len(messages) != 2
            or not isinstance(messages[0], SystemMessage)
            or not isinstance(messages[1], HumanMessage)
            or not isinstance(messages[1].content, str)
        ):
            raise HearingLcelContractError("HEARING_PROMPT_MESSAGE_FLOW_INVALID")
        try:
            case_data = json.loads(messages[1].content)
        except (TypeError, ValueError) as error:
            raise HearingLcelContractError("HEARING_PROMPT_PAYLOAD_INVALID") from error
        if not isinstance(case_data, dict):
            raise HearingLcelContractError("HEARING_PROMPT_PAYLOAD_INVALID")

        invocation_case_data = case_data
        context_pack = None
        if self.node_name in HEARING_INTAKE_CONTEXT_V4_NODES:
            assembled_v4 = assemble_hearing_intake_context_v4(self.node_name, case_data)
            invocation_case_data = {
                "context_contract": "hearing_intake_context.v4",
                "agent_role": "INTAKE_OFFICER",
                "stage_mode": assembled_v4.stage_mode,
                "source_authority_hash": assembled_v4.source_authority_hash,
            }
            context_pack = build_context_pack(
                self.node_name,
                {"hearing_intake_context_v4": assembled_v4.payload},
                required_section_names=frozenset({"hearing_intake_context_v4"}),
            )
        elif self.node_name in HEARING_ROOM_CONTEXT_V3_NODES:
            assembled = assemble_hearing_room_context_v3(self.node_name, case_data)
            invocation_case_data = {
                "context_contract": "hearing_room_context.v3",
                "agent_role": assembled.agent_role,
                "stage_mode": assembled.stage_mode,
                "source_authority_hash": assembled.source_authority_hash,
            }
            context_pack = build_context_pack(
                self.node_name,
                {"hearing_room_context_v3": assembled.payload},
                required_section_names=frozenset({"hearing_room_context_v3"}),
            )

        invocation: dict[str, Any] = {
            "node_name": self.node_name,
            "case_data": invocation_case_data,
            "output_type": self.output_type,
        }
        if context_pack is not None:
            invocation["context_pack"] = context_pack
        if self.node_name in HEARING_INTAKE_CONTEXT_V4_NODES:
            invocation["max_input_tokens"] = (
                HEARING_INTAKE_CONTEXT_V4_MAX_ESTIMATED_TOKENS
            )
        if self.agent_context is not None:
            invocation["agent_context"] = self.agent_context
        if self.semantic_validator is not None:
            invocation["semantic_validator"] = self.semantic_validator
        return invocation


@dataclass(frozen=True, slots=True)
class HearingLcelFlow(Generic[TOutput]):
    prompt: ChatPromptTemplate
    model: GovernedHearingModelAdapter[TOutput]
    parser: PydanticOutputParser[TOutput]
    runnable: RunnableSequence


def build_hearing_lcel(
    *,
    model_runner: Any,
    node_name: str,
    output_type: type[TOutput],
    semantic_validator: Callable[[TOutput], TOutput] | None = None,
    agent_context: AgentInvocationContext | None = None,
) -> HearingLcelFlow[TOutput]:
    relative_prompt = HEARING_MODEL_NODE_PROMPTS.get(node_name)
    if relative_prompt is None:
        raise HearingLcelContractError("HEARING_MODEL_NODE_UNKNOWN")
    system_prompt = _read_prompt(relative_prompt)
    if not system_prompt.strip():
        raise HearingLcelContractError("HEARING_PROMPT_PROFILE_EMPTY")

    prompt = ChatPromptTemplate.from_messages(
        [
            SystemMessage(content=system_prompt),
            ("human", "{case_data_json}"),
        ]
    )
    model = GovernedHearingModelAdapter(
        model_runner=model_runner,
        node_name=node_name,
        output_type=output_type,
        semantic_validator=semantic_validator,
        agent_context=agent_context,
    )
    parser = PydanticOutputParser(pydantic_object=output_type)
    runnable = cast(RunnableSequence, prompt | model | parser)
    return HearingLcelFlow(
        prompt=prompt,
        model=model,
        parser=parser,
        runnable=runnable,
    )


@lru_cache(maxsize=8)
def _read_prompt(relative_prompt: str) -> str:
    prompt_path = _PROMPT_ROOT / relative_prompt
    try:
        return prompt_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise HearingLcelContractError("HEARING_PROMPT_PROFILE_UNAVAILABLE") from error


def invoke_hearing_lcel(
    *,
    model_runner: Any,
    node_name: str,
    case_data: dict[str, Any],
    output_type: type[TOutput],
    semantic_validator: Callable[[TOutput], TOutput] | None = None,
    agent_context: AgentInvocationContext | None = None,
) -> TOutput:
    flow = build_hearing_lcel(
        model_runner=model_runner,
        node_name=node_name,
        output_type=output_type,
        semantic_validator=semantic_validator,
        agent_context=agent_context,
    )
    encoded = json.dumps(
        case_data,
        ensure_ascii=False,
        sort_keys=node_name not in _ORDERED_CONTEXT_TAIL_NODES,
        separators=(",", ":"),
    )
    result = flow.runnable.invoke(
        {"case_data_json": encoded},
        config={"tags": ["governed-lcel", "hearing", node_name]},
    )
    return output_type.model_validate(result)


async def ainvoke_hearing_lcel(
    *,
    model_runner: Any,
    node_name: str,
    case_data: dict[str, Any],
    output_type: type[TOutput],
    semantic_validator: Callable[[TOutput], TOutput] | None = None,
    agent_context: AgentInvocationContext | None = None,
) -> TOutput:
    flow = build_hearing_lcel(
        model_runner=model_runner,
        node_name=node_name,
        output_type=output_type,
        semantic_validator=semantic_validator,
        agent_context=agent_context,
    )
    encoded = json.dumps(
        case_data,
        ensure_ascii=False,
        sort_keys=node_name not in _ORDERED_CONTEXT_TAIL_NODES,
        separators=(",", ":"),
    )
    result = await flow.runnable.ainvoke(
        {"case_data_json": encoded},
        config={"tags": ["governed-lcel", "hearing", node_name]},
    )
    return output_type.model_validate(result)
