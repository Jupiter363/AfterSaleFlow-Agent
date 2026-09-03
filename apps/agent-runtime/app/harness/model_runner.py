# 文件作用：连接业务 LangGraph 节点与底层 LLM 网关，统一完成上下文裁剪、Prompt 渲染、可信身份白名单、结构化校验和流式事件适配。

from __future__ import annotations

from collections.abc import AsyncIterator, Callable, Iterator, Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Generic, Literal, TypeVar, cast

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from pydantic import BaseModel, create_model, model_validator
from typing_extensions import TypedDict

from app.graph_runtime.state_lens import StateLens
from app.harness.context_window import AssembledPromptContext, ContextWindowManager, PromptSection
from app.harness.context_pack import ContextPack
from app.harness.evidence_asset_loader import (
    LoadedEvidenceAssets,
    validated_evidence_content_parts,
)
from app.harness.invocation_context import AgentInvocationContext
from app.harness.prompt_composer import PromptRepository
from app.llm import StructuredLlmClient
from app.model_runtime.callbacks import (
    InvocationMetadataCapture,
    governed_events_from_chunk,
    governed_reset_usage_from_chunk,
)
from app.model_runtime.profiles import (
    ModelInvocationPolicy,
    ModelProfile,
    system_prompt_sha256,
)
from app.model_runtime.runnable_factory import ModelNodeSpec, build_model_node
from app.model_runtime.transports import StructuredClientTransport
from app.streaming import (
    IncrementalVisibleJsonProjector,
    VisibleFieldSpec,
    bind_stream_observer,
    current_stream_observer,
)


T = TypeVar("T", bound=BaseModel)


@dataclass(frozen=True)
class HarnessGeneration(Generic[T]):
    """Harness 层对一次模型调用的封装结果。

    Generic[T] 表示这是泛型类：value 的具体类型由调用时的 output_type 决定。
    除了模型输出，还保留了最终 prompt 上下文和 LangChain message，方便测试和审计。
    """

    value: T
    model: str
    latency_ms: int
    token_usage: dict[str, int]
    context: AssembledPromptContext
    messages: tuple[BaseMessage, ...]


@dataclass(frozen=True)
class HarnessStreamDelta:
    """Harness 对外暴露的一小段白名单可见字段文本；不包含完整未校验 JSON。"""

    kind: Literal["visible_delta"]
    field: str
    delta: str


@dataclass(frozen=True)
class HarnessStreamReset:
    """The prior provisional generation is invalid and must be cleared."""

    kind: Literal["generation_reset"]
    generation: int
    reason_code: Literal["OUTPUT_SCHEMA_INVALID"]
    failed_model: str
    failed_latency_ms: int
    failed_token_usage: dict[str, int]


@dataclass(frozen=True)
class HarnessStreamCompleted(Generic[T]):
    """同一次流式模型调用在完整 JSON 通过 Pydantic 校验后的最终结果。"""

    kind: Literal["completed"]
    generation: HarnessGeneration[T]


HarnessStreamUpdate = HarnessStreamDelta | HarnessStreamReset | HarnessStreamCompleted[T]


class _HarnessPromptInput(TypedDict):
    human_prompt: str


class _HarnessPatch(TypedDict):
    value: dict[str, Any]


@dataclass(frozen=True)
class PreparedHarnessInvocation:
    """Exact provider-facing invocation prepared by the production Harness.

    Target LangGraph nodes consume this same immutable value instead of rebuilding
    prompts from Target-specific templates.  That keeps message roles, ordering,
    ContextPack trimming, trusted-context projection and the response schema on one
    production code path for every digital human.
    """

    context: AssembledPromptContext
    system_prompt: str
    user_prompt: str
    trusted_context: dict[str, Any]
    raw_trusted_context: dict[str, Any]
    resolved_prompt_profile_id: str | None
    output_type: type[BaseModel]

    @property
    def messages(self) -> tuple[BaseMessage, BaseMessage]:
        return (
            SystemMessage(content=self.system_prompt),
            HumanMessage(content=self.user_prompt),
        )


@dataclass(frozen=True)
class PreparedPromptAuthority:
    """Validated identity/profile projection shared by baseline and Target nodes."""

    system_prompt: str
    trusted_context: dict[str, Any]
    raw_trusted_context: dict[str, Any]
    resolved_prompt_profile_id: str | None


class HarnessModelRunner:
    """所有 Agent 节点共用的结构化 LLM 调用器。

    它位于业务 workflow 和底层 LiteLlmProxyClient 之间，负责：
    - 按 token 预算裁剪上下文；
    - 渲染 system/user prompt；
    - 注入可信 agent_context；
    - 统一调用结构化 LLM；
    - 在流式模式下把底层 delta 转成 Harness delta。
    """

    # 所属模块：Agent Harness > 模型执行中枢 > 依赖装配。
    # 具体功能：`__init__` 固定结构化 LLM 客户端、服务端 Prompt 仓库和上下文窗口管理器；未注入窗口管理器时创建统一默认预算实现。
    # 上下游：上游是 FastAPI 服务启动/测试依赖装配；下游是所有接待、证据、庭审节点共用的非流式与流式调用入口。
    # 系统意义：业务节点不直接拼 Prompt 或发 HTTP，确保每次模型调用都经过同一套 Token、信任分层、Schema 和审计载荷规则。
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

    # 所属模块：Agent Harness > 模型执行中枢 > 非流式结构化调用。
    # 具体功能：`invoke_structured` 裁剪 ContextPack/sections、白名单化 Agent 身份、选择角色 Prompt、注入上下文审计元数据，生成 system/human messages 后仅调用一次 `llm.generate`。
    # 上下游：上游是 LangGraph 业务节点提供的 node_name、case_data、Pydantic output_type 和可选证据能力；下游是 StructuredLlmClient 及带模型/延迟/Token/最终 Prompt 的 HarnessGeneration。
    # 系统意义：模型自由文本必须先解析成 output_type 才返回业务层；不可信案件数据只进 human message，可信身份也只暴露白名单字段。
    def invoke_structured(
        self,
        *,
        node_name: str,
        case_data: dict[str, Any],
        output_type: type[T],
        context_sections: list[PromptSection] | None = None,
        context_pack: ContextPack | None = None,
        max_input_tokens: int | None = None,
        agent_context: AgentInvocationContext | None = None,
        prompt_profile_id: str | None = None,
        evidence_assets: LoadedEvidenceAssets | None = None,
        semantic_validator: Callable[[T], T] | None = None,
    ) -> HarnessGeneration[T]:
        """执行一次非流式结构化模型调用。"""

        user_content_parts = (
            validated_evidence_content_parts(evidence_assets)
            if evidence_assets is not None
            else ()
        )
        governed_output_type = _semantic_output_type(output_type, semantic_validator)
        prepared = self._prepare(
            node_name=node_name,
            case_data=case_data,
            output_type=governed_output_type,
            context_sections=context_sections,
            context_pack=context_pack,
            max_input_tokens=max_input_tokens,
            agent_context=agent_context,
            prompt_profile_id=prompt_profile_id,
        )
        built = self._build_node(
            node_name=node_name,
            output_type=governed_output_type,
            prepared=prepared,
            visible_fields=(),
            user_content_parts=user_content_parts,
            semantic_repair=semantic_validator is not None,
        )
        capture = InvocationMetadataCapture()
        state = {"human_prompt": prepared.user_prompt}
        observer = current_stream_observer()
        if semantic_validator is not None and observer is not None:
            observer.raise_if_cancelled()
            with bind_stream_observer(cast(Any, None)):
                patch = built.runnable.invoke(
                    state,
                    config={"callbacks": [capture], "tags": ["governed-lcel", node_name]},
                )
            observer.raise_if_cancelled()
        else:
            patch = built.runnable.invoke(
                state,
                config={"callbacks": [capture], "tags": ["governed-lcel", node_name]},
            )
        value = output_type.model_validate(patch["value"])
        messages = tuple(built.prompt.invoke(state).messages)
        metadata = capture.metadata
        if semantic_validator is not None and observer is not None:
            projector = IncrementalVisibleJsonProjector(
                observer.visible_fields_for(node_name)
            )
            for field, delta in projector.feed(value.model_dump_json()):
                observer.visible_delta(node_name, field, delta)
            observer.usage(
                node_name=node_name,
                model=str(metadata["model"]),
                latency_ms=int(metadata["latency_ms"]),
                token_usage=dict(metadata["token_usage"]),
            )
        return HarnessGeneration(
            value=value,
            model=str(metadata["model"]),
            latency_ms=int(metadata["latency_ms"]),
            token_usage=dict(metadata["token_usage"]),
            context=prepared.context,
            messages=messages,
        )

    async def ainvoke_structured(
        self,
        *,
        node_name: str,
        case_data: dict[str, Any],
        output_type: type[T],
        context_sections: list[PromptSection] | None = None,
        context_pack: ContextPack | None = None,
        max_input_tokens: int | None = None,
        agent_context: AgentInvocationContext | None = None,
        prompt_profile_id: str | None = None,
        evidence_assets: LoadedEvidenceAssets | None = None,
        semantic_validator: Callable[[T], T] | None = None,
    ) -> HarnessGeneration[T]:
        """Execute one governed structured invocation on the native async model path."""

        # A bound public stream must use the provider SSE path even when the
        # business caller asks for the ordinary async result API.  This keeps
        # the one-call contract while allowing the observer to receive the
        # explicitly whitelisted fields before the terminal JSON document.
        observer = current_stream_observer()
        if observer is not None:
            visible_fields = observer.visible_fields_for(node_name)
            if visible_fields:
                generation: HarnessGeneration[T] | None = None
                provisional_output_emitted = False
                async for update in self.ainvoke_structured_stream(
                    node_name=node_name,
                    case_data=case_data,
                    output_type=output_type,
                    visible_fields=visible_fields,
                    context_sections=context_sections,
                    context_pack=context_pack,
                    max_input_tokens=max_input_tokens,
                    agent_context=agent_context,
                    prompt_profile_id=prompt_profile_id,
                    evidence_assets=evidence_assets,
                    semantic_validator=semantic_validator,
                ):
                    if isinstance(update, HarnessStreamDelta):
                        observer.visible_delta(node_name, update.field, update.delta)
                        provisional_output_emitted = True
                    elif isinstance(update, HarnessStreamReset):
                        if provisional_output_emitted:
                            observer.generation_reset(
                                node_name=node_name,
                                generation=update.generation,
                                reason_code=update.reason_code,
                            )
                        provisional_output_emitted = False
                    elif isinstance(update, HarnessStreamCompleted):
                        if generation is not None:
                            raise RuntimeError(
                                "governed async stream emitted multiple completions"
                            )
                        generation = update.generation
                    else:
                        raise RuntimeError("governed async stream emitted an unknown update")
                if generation is None:
                    raise RuntimeError("governed async stream emitted no completion")
                return generation

        user_content_parts = (
            validated_evidence_content_parts(evidence_assets)
            if evidence_assets is not None
            else ()
        )
        governed_output_type = _semantic_output_type(
            output_type, semantic_validator
        )
        prepared = self._prepare(
            node_name=node_name,
            case_data=case_data,
            output_type=governed_output_type,
            context_sections=context_sections,
            context_pack=context_pack,
            max_input_tokens=max_input_tokens,
            agent_context=agent_context,
            prompt_profile_id=prompt_profile_id,
        )
        built = self._build_node(
            node_name=node_name,
            output_type=governed_output_type,
            prepared=prepared,
            visible_fields=(),
            user_content_parts=user_content_parts,
            semantic_repair=semantic_validator is not None,
        )
        capture = InvocationMetadataCapture()
        state = {"human_prompt": prepared.user_prompt}
        if semantic_validator is not None and observer is not None:
            observer.raise_if_cancelled()
            with bind_stream_observer(cast(Any, None)):
                patch = await built.runnable.ainvoke(
                    state,
                    config={
                        "callbacks": [capture],
                        "tags": ["governed-lcel", node_name],
                    },
                )
            observer.raise_if_cancelled()
        else:
            patch = await built.runnable.ainvoke(
                state,
                config={"callbacks": [capture], "tags": ["governed-lcel", node_name]},
            )
        value = output_type.model_validate(patch["value"])
        messages = tuple(built.prompt.invoke(state).messages)
        metadata = capture.metadata
        generation = HarnessGeneration(
            value=value,
            model=str(metadata["model"]),
            latency_ms=int(metadata["latency_ms"]),
            token_usage=dict(metadata["token_usage"]),
            context=prepared.context,
            messages=messages,
        )
        observer = current_stream_observer()
        if observer is not None:
            observer.raise_if_cancelled()
            if semantic_validator is not None:
                projector = IncrementalVisibleJsonProjector(
                    observer.visible_fields_for(node_name)
                )
                for field, delta in projector.feed(value.model_dump_json()):
                    observer.visible_delta(node_name, field, delta)
            observer.usage(
                node_name=node_name,
                model=generation.model,
                latency_ms=generation.latency_ms,
                token_usage=generation.token_usage,
            )
        return generation

    async def ainvoke_structured_stream(
        self,
        *,
        node_name: str,
        case_data: dict[str, Any],
        output_type: type[T],
        visible_fields: tuple[VisibleFieldSpec, ...] = (),
        context_sections: list[PromptSection] | None = None,
        context_pack: ContextPack | None = None,
        max_input_tokens: int | None = None,
        agent_context: AgentInvocationContext | None = None,
        prompt_profile_id: str | None = None,
        evidence_assets: LoadedEvidenceAssets | None = None,
        semantic_validator: Callable[[T], T] | None = None,
    ) -> AsyncIterator[HarnessStreamUpdate[T]]:
        """Run one native async structured call and expose governed deltas.

        The provider's JSON document is still parsed and validated exactly once
        at the end of this generator.  Only fields explicitly declared in
        ``visible_fields`` can cross the stream boundary before that point.
        This is the async counterpart of ``invoke_structured_stream`` and is
        intentionally a single provider invocation (there is no preview call
        followed by a second terminal call).
        """

        user_content_parts = (
            validated_evidence_content_parts(evidence_assets)
            if evidence_assets is not None
            else ()
        )
        governed_output_type = _semantic_output_type(
            output_type, semantic_validator
        )
        prepared = self._prepare(
            node_name=node_name,
            case_data=case_data,
            output_type=governed_output_type,
            context_sections=context_sections,
            context_pack=context_pack,
            max_input_tokens=max_input_tokens,
            agent_context=agent_context,
            prompt_profile_id=prompt_profile_id,
        )
        built = self._build_node(
            node_name=node_name,
            output_type=governed_output_type,
            prepared=prepared,
            visible_fields=visible_fields,
            user_content_parts=user_content_parts,
            semantic_repair=semantic_validator is not None,
        )
        capture = InvocationMetadataCapture()
        state = {"human_prompt": prepared.user_prompt}
        prompt_input = built.lens.invoke(state)
        prompt_value = built.prompt.invoke(prompt_input)
        messages = tuple(prompt_value.messages)
        final_document: str | None = None
        async for chunk in built.model.astream(
            prompt_value,
            config={"callbacks": [capture], "tags": ["governed-lcel", node_name]},
        ):
            for event in governed_events_from_chunk(chunk):
                if event["event_type"] == "generation_reset":
                    reset_usage = governed_reset_usage_from_chunk(chunk)
                    if reset_usage is None:
                        raise RuntimeError("governed generation reset omitted usage")
                    yield HarnessStreamReset(
                        kind="generation_reset",
                        generation=event["generation"],
                        reason_code=event["reason_code"],
                        failed_model=reset_usage["model"],
                        failed_latency_ms=reset_usage["latency_ms"],
                        failed_token_usage=dict(reset_usage["token_usage"]),
                    )
                else:
                    yield HarnessStreamDelta(
                        kind="visible_delta",
                        field=event["field"],
                        delta=event["delta"],
                    )
            if chunk.content:
                if not isinstance(chunk.content, str) or final_document is not None:
                    raise RuntimeError(
                        "governed async stream must emit one final JSON document"
                    )
                final_document = chunk.content
        if final_document is None:
            raise RuntimeError("governed async stream ended without a final JSON document")
        parsed = built.parser.invoke(AIMessage(content=final_document))
        guarded = built.guardrail.invoke(parsed)
        patch = built.patch_projector.invoke(guarded)
        metadata = capture.metadata
        yield HarnessStreamCompleted(
            kind="completed",
            generation=HarnessGeneration(
                value=output_type.model_validate(patch["value"]),
                model=str(metadata["model"]),
                latency_ms=int(metadata["latency_ms"]),
                token_usage=dict(metadata["token_usage"]),
                context=prepared.context,
                messages=messages,
            ),
        )

    # 所属模块：Agent Harness > 模型执行中枢 > 单次调用流式适配。
    # 具体功能：`invoke_structured_stream` 复用与非流式完全相同的裁剪/Prompt/身份规则，消费一次 `llm.generate_stream`，把可见字段增量映射为 HarnessStreamDelta，最终映射为已校验 HarnessGeneration。
    # 上下游：上游是声明 visible_fields 的流式业务节点；下游是 NDJSON AgentStreamObserver 或其他消费者，事件顺序为零到多条 delta 后恰好一条 completed。
    # 系统意义：不会为“流式展示”和“最终结果”调用两次模型；未在 visible_fields 白名单中的 JSON、reasoning_content 及未校验最终对象不经此通道暴露。
    def invoke_structured_stream(
        self,
        *,
        node_name: str,
        case_data: dict[str, Any],
        output_type: type[T],
        visible_fields: tuple[VisibleFieldSpec, ...] = (),
        context_sections: list[PromptSection] | None = None,
        context_pack: ContextPack | None = None,
        max_input_tokens: int | None = None,
        agent_context: AgentInvocationContext | None = None,
        prompt_profile_id: str | None = None,
        evidence_assets: LoadedEvidenceAssets | None = None,
    ) -> Iterator[HarnessStreamUpdate[T]]:
        """Stream one structured Harness invocation without a second model call."""

        user_content_parts = (
            validated_evidence_content_parts(evidence_assets)
            if evidence_assets is not None
            else ()
        )
        prepared = self._prepare(
            node_name=node_name,
            case_data=case_data,
            output_type=output_type,
            context_sections=context_sections,
            context_pack=context_pack,
            max_input_tokens=max_input_tokens,
            agent_context=agent_context,
            prompt_profile_id=prompt_profile_id,
        )
        built = self._build_node(
            node_name=node_name,
            output_type=output_type,
            prepared=prepared,
            visible_fields=visible_fields,
            user_content_parts=user_content_parts,
        )
        capture = InvocationMetadataCapture()
        state = {"human_prompt": prepared.user_prompt}
        prompt_input = built.lens.invoke(state)
        prompt_value = built.prompt.invoke(prompt_input)
        messages = tuple(prompt_value.messages)
        final_document: str | None = None
        for chunk in built.model.stream(
            prompt_value,
            config={"callbacks": [capture], "tags": ["governed-lcel", node_name]},
        ):
            for event in governed_events_from_chunk(chunk):
                if event["event_type"] == "generation_reset":
                    reset_usage = governed_reset_usage_from_chunk(chunk)
                    if reset_usage is None:
                        raise RuntimeError("governed generation reset omitted usage")
                    yield HarnessStreamReset(
                        kind="generation_reset",
                        generation=event["generation"],
                        reason_code=event["reason_code"],
                        failed_model=reset_usage["model"],
                        failed_latency_ms=reset_usage["latency_ms"],
                        failed_token_usage=dict(reset_usage["token_usage"]),
                    )
                else:
                    yield HarnessStreamDelta(
                        kind="visible_delta",
                        field=event["field"],
                        delta=event["delta"],
                    )
            if chunk.content:
                if not isinstance(chunk.content, str) or final_document is not None:
                    raise RuntimeError("governed stream must emit one final JSON document")
                final_document = chunk.content
        if final_document is None:
            raise RuntimeError("governed stream ended without a final JSON document")
        parsed = built.parser.invoke(AIMessage(content=final_document))
        guarded = built.guardrail.invoke(parsed)
        patch = built.patch_projector.invoke(guarded)
        metadata = capture.metadata
        yield HarnessStreamCompleted(
            kind="completed",
            generation=HarnessGeneration(
                value=output_type.model_validate(patch["value"]),
                model=str(metadata["model"]),
                latency_ms=int(metadata["latency_ms"]),
                token_usage=dict(metadata["token_usage"]),
                context=prepared.context,
                messages=messages,
            ),
        )

    def _prepare(
        self,
        *,
        node_name: str,
        case_data: dict[str, Any],
        output_type: type[BaseModel],
        context_sections: list[PromptSection] | None,
        context_pack: ContextPack | None,
        max_input_tokens: int | None,
        agent_context: AgentInvocationContext | None,
        prompt_profile_id: str | None,
    ) -> PreparedHarnessInvocation:
        return prepare_baseline_invocation(
            prompts=self._prompts,
            context_window=self._context_window,
            node_name=node_name,
            case_data=case_data,
            output_type=output_type,
            context_sections=context_sections,
            context_pack=context_pack,
            max_input_tokens=max_input_tokens,
            agent_context=agent_context,
            prompt_profile_id=prompt_profile_id,
        )

    def _build_node(
        self,
        *,
        node_name: str,
        output_type: type[T],
        prepared: PreparedHarnessInvocation,
        visible_fields: tuple[VisibleFieldSpec, ...],
        user_content_parts: tuple[dict[str, Any], ...],
        semantic_repair: bool = False,
    ):
        lens = StateLens(
            name=f"{node_name}.harness_lens",
            source_fields=("human_prompt",),
            selector=_select_harness_prompt,
            output_type=_HarnessPromptInput,
        )
        profile = _model_profile(node_name, prepared.raw_trusted_context, self._llm)
        policy = _invocation_policy(
            node_name=node_name,
            output_type=output_type,
            system_prompt=prepared.system_prompt,
            raw_trusted_context=prepared.raw_trusted_context,
            prompt_version=prepared.resolved_prompt_profile_id,
        )
        if semantic_repair and "retry_budget" not in prepared.raw_trusted_context:
            if profile.max_provider_attempts < 2:
                raise ValueError("semantic output repair requires two provider attempts")
            policy = ModelInvocationPolicy.model_validate(
                {
                    **policy.model_dump(mode="python"),
                    "provider_attempts_remaining": 2,
                    "repairs_remaining": 1,
                }
            )
        spec = ModelNodeSpec(
            node_name=node_name,
            lens=lens,
            trusted_system_prompt=prepared.system_prompt,
            human_prompt_template="{human_prompt}",
            output_type=output_type,
            patch_type=_HarnessPatch,
            guardrail=_identity_guardrail,
            patch_projector=_harness_patch,
            profile=profile,
            policy=policy,
            visible_fields=visible_fields,
            user_content_parts=tuple(dict(part) for part in user_content_parts),
        )
        return build_model_node(
            spec,
            transport=StructuredClientTransport(self._llm),
        )


def prepare_baseline_invocation(
    *,
    prompts: PromptRepository,
    context_window: ContextWindowManager,
    node_name: str,
    case_data: dict[str, Any],
    output_type: type[BaseModel],
    context_sections: list[PromptSection] | None = None,
    context_pack: ContextPack | None = None,
    max_input_tokens: int | None = None,
    agent_context: AgentInvocationContext | None = None,
    prompt_profile_id: str | None = None,
) -> PreparedHarnessInvocation:
    """Prepare the exact production-baseline model messages without invoking a model.

    Both the established Harness workflows and durable Target graphs call this
    function.  Callers may change how LangGraph stores state or how a validated
    result is adapted to a transport proposal, but they cannot silently fork the
    provider-facing prompt hierarchy or ContextPack behavior.
    """

    if not isinstance(prompts, PromptRepository):
        raise TypeError("prompts must be the production PromptRepository")
    if not isinstance(context_window, ContextWindowManager):
        raise TypeError("context_window must be the production ContextWindowManager")
    assembled_context = context_window.assemble(
        context_pack.prompt_sections() if context_pack is not None else context_sections or [],
        max_input_tokens=max_input_tokens,
    )
    authority = prepare_baseline_prompt_authority(
        prompts=prompts,
        node_name=node_name,
        agent_context=agent_context,
        prompt_profile_id=prompt_profile_id,
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
            "display_only_section_names": list(context_pack.display_only_section_names),
        }
    user_prompt = prompts.render_user_prompt(
        enriched_case_data,
        output_type.model_json_schema(),
    )
    return PreparedHarnessInvocation(
        context=assembled_context,
        system_prompt=authority.system_prompt,
        user_prompt=user_prompt,
        trusted_context=authority.trusted_context,
        raw_trusted_context=authority.raw_trusted_context,
        resolved_prompt_profile_id=authority.resolved_prompt_profile_id,
        output_type=output_type,
    )


def prepare_baseline_prompt_authority(
    *,
    prompts: PromptRepository,
    node_name: str,
    agent_context: AgentInvocationContext | None,
    prompt_profile_id: str | None = None,
) -> PreparedPromptAuthority:
    """Render the exact baseline SystemMessage authority before graph execution."""

    if not isinstance(prompts, PromptRepository):
        raise TypeError("prompts must be the production PromptRepository")
    validated_agent_context = _validated_agent_context(agent_context)
    raw_trusted_context = _trusted_agent_context_mapping(validated_agent_context)
    trusted_context = _trusted_agent_context_payload(validated_agent_context)
    if validated_agent_context is None:
        if prompt_profile_id is not None:
            raise ValueError("explicit prompt profile requires a validated agent context")
        resolved_prompt_profile_id = None
    else:
        signed_prompt_profile_id = validated_agent_context.prompt_profile_id
        if prompt_profile_id is not None and prompt_profile_id != signed_prompt_profile_id:
            raise ValueError("explicit prompt profile conflicts with trusted agent context")
        resolved_prompt_profile_id = signed_prompt_profile_id
    system_prompt = prompts.render_system_prompt(
        node_name,
        prompt_profile_id=resolved_prompt_profile_id,
        trusted_agent_context=trusted_context or None,
    )
    return PreparedPromptAuthority(
        system_prompt=system_prompt,
        trusted_context=trusted_context,
        raw_trusted_context=raw_trusted_context,
        resolved_prompt_profile_id=resolved_prompt_profile_id,
    )


# 所属模块：Agent Harness > 模型执行中枢 > 可信调用上下文最小披露。
# 具体功能：`_trusted_agent_context_payload` 只接受重新校验过的 AgentInvocationContext，并仅复制案件、房间、参与方、Agent、会话范围与 Prompt Profile 等显式字段。
# 上下游：上游是 Java 签发的 AgentInvocationContext；下游是 PromptComposer 的 `<trusted_agent_context>` system 片段和角色模板选择。
# 系统意义：即使服务端上下文整体可信，也不能随模型演进自动暴露 tenant、权限细节、密钥或未来新增敏感字段；白名单要求新增披露经过代码审查。
def _trusted_agent_context_payload(
    agent_context: AgentInvocationContext | None,
) -> dict[str, Any]:
    """只把白名单字段注入 prompt。

    agent_context 是已校验的可信系统上下文，但也不能整包塞给模型。这里显式列出允许暴露的字段，
    防止未来新增敏感字段时自动进入 prompt。
    """

    raw_context = _trusted_agent_context_mapping(agent_context)

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
    # 字典推导式同时过滤值为 None 的可选字段；False、0、空列表等合法显式值仍会被保留。
    return {
        field: raw_context[field]
        for field in allowed_fields
        if raw_context.get(field) is not None
    }


def _validated_agent_context(
    agent_context: Any | None,
) -> AgentInvocationContext | None:
    if agent_context is None:
        return None
    if type(agent_context) is not AgentInvocationContext:
        raise TypeError("agent context must be a validated AgentInvocationContext")
    return AgentInvocationContext.model_validate(
        agent_context.model_dump(mode="python")
    )


def _trusted_agent_context_mapping(
    agent_context: AgentInvocationContext | None,
) -> dict[str, Any]:
    if type(agent_context) is AgentInvocationContext:
        return agent_context.model_dump(mode="python")
    return {}


def _select_harness_prompt(state: Mapping[str, Any]) -> Mapping[str, Any]:
    return {"human_prompt": state["human_prompt"]}


def _identity_guardrail(value: T) -> T:
    return value


def _semantic_output_type(
    output_type: type[T],
    semantic_validator: Callable[[T], T] | None,
) -> type[T]:
    if semantic_validator is None:
        return output_type

    @model_validator(mode="after")
    def require_semantic_contract(value: BaseModel) -> BaseModel:
        candidate = output_type.model_validate(value)
        validated = semantic_validator(candidate)
        if validated is not candidate:
            raise TypeError("semantic validator must return the validated value")
        return value

    constrained = create_model(
        output_type.__name__,
        __base__=output_type,
        __module__=output_type.__module__,
        # ``create_model`` does not inherit a base model's docstring. Pydantic
        # projects that docstring into the provider JSON Schema as
        # ``description``; dropping it would make an otherwise validator-only
        # wrapper appear to mutate the wire contract and reject the invocation
        # before the Provider is called. Preserve the exact descriptive schema
        # authority while adding only the local after-validator.
        __doc__=output_type.__doc__,
        __validators__={"_governed_semantic_contract": require_semantic_contract},
    )
    if constrained.model_json_schema() != output_type.model_json_schema():
        raise ValueError("semantic output model changed the provider JSON Schema")
    return cast(type[T], constrained)


def _harness_patch(value: BaseModel) -> Mapping[str, Any]:
    return {"value": value.model_dump(mode="json")}


def _model_profile(
    node_name: str,
    raw_context: dict[str, Any],
    llm: StructuredLlmClient,
) -> ModelProfile:
    profile_id = raw_context.get("model_profile_id") or "legacy:model-profile.v1"
    raw_tools = raw_context.get("tool_capabilities") or ()
    if not isinstance(profile_id, str):
        raise ValueError("trusted model profile id must be a string")
    if not isinstance(raw_tools, (list, tuple)) or any(
        not isinstance(item, str) for item in raw_tools
    ):
        raise ValueError("trusted tool capabilities must be a string sequence")
    tools = tuple(raw_tools)
    provider = getattr(llm, "governed_provider", "structured-client")
    model = getattr(llm, "governed_model", "fake-model")
    if not isinstance(provider, str) or not provider:
        raise ValueError("structured client must declare a valid governed provider")
    if not isinstance(model, str) or not model:
        raise ValueError("structured client must declare a valid governed model")
    budget_resolver = getattr(llm, "governed_max_output_tokens", None)
    max_output_tokens = 8_192
    if budget_resolver is not None:
        if not callable(budget_resolver):
            raise ValueError("structured client declared an invalid token budget resolver")
        max_output_tokens = budget_resolver(node_name)
    max_provider_attempts = getattr(llm, "governed_max_provider_attempts", 1)
    if (
        isinstance(max_output_tokens, bool)
        or not isinstance(max_output_tokens, int)
        or isinstance(max_provider_attempts, bool)
        or not isinstance(max_provider_attempts, int)
    ):
        raise ValueError("structured client declared invalid governed model limits")
    return ModelProfile(
        profile_id=profile_id,
        provider=provider,
        model=model,
        temperature=0,
        max_output_tokens=max_output_tokens,
        tool_allowlist=tools,
        max_provider_attempts=max_provider_attempts,
    )


def _invocation_policy(
    *,
    node_name: str,
    output_type: type[BaseModel],
    system_prompt: str,
    raw_trusted_context: dict[str, Any],
    prompt_version: str | None,
) -> ModelInvocationPolicy:
    retry_budget = raw_trusted_context.get("retry_budget")
    if not isinstance(retry_budget, dict):
        retry_budget = {}
    return ModelInvocationPolicy(
        invocation_id=raw_trusted_context.get("agent_invocation_id")
        or f"legacy:{node_name}:invocation",
        node_name=node_name,
        deadline_at=_trusted_deadline(raw_trusted_context.get("deadline_at")),
        provider_attempts_remaining=retry_budget.get("provider_attempts_remaining", 1),
        repairs_remaining=retry_budget.get("repairs_remaining", 0),
        prompt_version=prompt_version or f"{node_name}:prompt:v1",
        output_schema_version=raw_trusted_context.get("output_schema_version")
        or f"{output_type.__name__}:schema:v1",
        policy_version=raw_trusted_context.get("policy_version") or "legacy:policy:v1",
        guardrail_version=raw_trusted_context.get("guardrail_version")
        or "legacy:guardrail:v1",
        trusted_system_sha256=system_prompt_sha256(system_prompt),
        traceparent=raw_trusted_context.get("traceparent"),
    )


def _trusted_deadline(value: Any) -> datetime:
    if isinstance(value, datetime):
        deadline = value
    elif isinstance(value, str):
        deadline = datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        deadline = datetime.now(timezone.utc) + timedelta(seconds=120)
    if deadline.utcoffset() is None:
        raise ValueError("trusted model deadline must be timezone-aware")
    return deadline
