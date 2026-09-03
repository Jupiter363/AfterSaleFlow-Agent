from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, Generic, TypeVar, cast

from langchain_core.messages import SystemMessage
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import Runnable, RunnableConfig
from pydantic import BaseModel, TypeAdapter, ValidationError

from app.graph_runtime.state import validate_graph_patch
from app.graph_runtime.state_lens import StateLens
from app.model_runtime.governed_chat_model import GovernedChatModel
from app.model_runtime.profiles import ModelInvocationPolicy, ModelProfile, system_prompt_sha256
from app.model_runtime.transports import ModelTransport
from app.streaming import VisibleFieldSpec


StateT = TypeVar("StateT", bound=Mapping[str, Any])
PromptInputT = TypeVar("PromptInputT", bound=Mapping[str, Any])
OutputT = TypeVar("OutputT", bound=BaseModel)
PatchT = TypeVar("PatchT", bound=Mapping[str, Any])


class ModelNodeBuildError(ValueError):
    pass


class GuardrailRunnable(Runnable[OutputT, OutputT], Generic[OutputT]):
    def __init__(self, *, name: str, guardrail: Callable[[OutputT], OutputT]) -> None:
        self.name = name
        self._guardrail = guardrail

    def invoke(
        self,
        input: OutputT,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> OutputT:
        if kwargs:
            raise ModelNodeBuildError("guardrail overrides are forbidden")
        return self._call_with_config(
            self._guardrail,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "business_guardrail"},
        )

    async def ainvoke(
        self,
        input: OutputT,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> OutputT:
        if kwargs:
            raise ModelNodeBuildError("guardrail overrides are forbidden")

        async def apply(value: OutputT) -> OutputT:
            return self._guardrail(value)

        return await self._acall_with_config(
            apply,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "business_guardrail"},
        )


class PatchProjectorRunnable(Runnable[OutputT, PatchT], Generic[OutputT, PatchT]):
    def __init__(
        self,
        *,
        name: str,
        projector: Callable[[OutputT], Mapping[str, Any]],
        patch_type: Any,
    ) -> None:
        self.name = name
        self._projector = projector
        self._adapter = TypeAdapter(patch_type)

    def invoke(
        self,
        input: OutputT,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> PatchT:
        if kwargs:
            raise ModelNodeBuildError("patch projector overrides are forbidden")
        return self._call_with_config(
            self._project,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "state_patch_projector"},
        )

    async def ainvoke(
        self,
        input: OutputT,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> PatchT:
        if kwargs:
            raise ModelNodeBuildError("patch projector overrides are forbidden")

        async def project(value: OutputT) -> PatchT:
            return self._project(value)

        return await self._acall_with_config(
            project,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "state_patch_projector"},
        )

    def _project(self, value: OutputT) -> PatchT:
        try:
            raw = self._projector(value)
            selected = self._adapter.validate_python(raw, strict=True)
        except (TypeError, ValueError, ValidationError) as error:
            raise ModelNodeBuildError("state patch failed validation") from error
        if not isinstance(selected, Mapping):
            raise ModelNodeBuildError("state patch must be a mapping")
        patch = dict(selected)
        validate_graph_patch(cast(dict[str, object], patch))
        return cast(PatchT, patch)


@dataclass(frozen=True, slots=True)
class ModelNodeSpec(Generic[StateT, PromptInputT, OutputT, PatchT]):
    node_name: str
    lens: StateLens[StateT, PromptInputT]
    trusted_system_prompt: str
    human_prompt_template: str
    output_type: type[OutputT]
    patch_type: Any
    guardrail: Callable[[OutputT], OutputT]
    patch_projector: Callable[[OutputT], Mapping[str, Any]]
    profile: ModelProfile
    policy: ModelInvocationPolicy
    visible_fields: tuple[VisibleFieldSpec, ...] = ()
    user_content_parts: tuple[dict[str, Any], ...] = ()


@dataclass(frozen=True, slots=True)
class BuiltModelNode(Generic[StateT, PromptInputT, OutputT, PatchT]):
    lens: StateLens[StateT, PromptInputT]
    prompt: ChatPromptTemplate
    model: GovernedChatModel
    parser: PydanticOutputParser[OutputT]
    guardrail: GuardrailRunnable[OutputT]
    patch_projector: PatchProjectorRunnable[OutputT, PatchT]
    runnable: Runnable[StateT, PatchT]


def build_model_node(
    spec: ModelNodeSpec[StateT, PromptInputT, OutputT, PatchT],
    *,
    transport: ModelTransport,
) -> BuiltModelNode[StateT, PromptInputT, OutputT, PatchT]:
    if spec.node_name != spec.policy.node_name:
        raise ModelNodeBuildError("node name conflicts with invocation policy")
    if system_prompt_sha256(spec.trusted_system_prompt) != spec.policy.trusted_system_sha256:
        raise ModelNodeBuildError("system prompt conflicts with its trusted binding")
    prompt = ChatPromptTemplate.from_messages(
        [
            SystemMessage(content=spec.trusted_system_prompt),
            ("human", spec.human_prompt_template),
        ]
    )
    model = GovernedChatModel(
        transport=transport,
        output_type=spec.output_type,
        profile=spec.profile,
        policy=spec.policy,
        visible_fields=spec.visible_fields,
        user_content_parts=spec.user_content_parts,
    )
    parser = PydanticOutputParser(pydantic_object=spec.output_type)
    guardrail = GuardrailRunnable(
        name=f"{spec.node_name}.guardrail",
        guardrail=spec.guardrail,
    )
    patch_projector = PatchProjectorRunnable(
        name=f"{spec.node_name}.patch",
        projector=spec.patch_projector,
        patch_type=spec.patch_type,
    )
    runnable = spec.lens | prompt | model | parser | guardrail | patch_projector
    return BuiltModelNode(
        lens=spec.lens,
        prompt=prompt,
        model=model,
        parser=parser,
        guardrail=guardrail,
        patch_projector=patch_projector,
        runnable=cast(Runnable[StateT, PatchT], runnable),
    )
