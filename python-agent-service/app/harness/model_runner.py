# 文件作用：连接业务 LangGraph 节点与底层 LLM 网关，统一完成上下文裁剪、Prompt 渲染、可信身份白名单、结构化校验和流式事件适配。

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any, Generic, Literal, TypeVar

from langchain_core.messages import BaseMessage
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel

from app.harness.context_window import AssembledPromptContext, ContextWindowManager, PromptSection
from app.harness.context_pack import ContextPack
from app.harness.prompt_composer import PromptRepository
from app.llm import (
    StructuredGeneration,
    StructuredLlmClient,
    StructuredStreamCompleted,
    StructuredStreamDelta,
)
from app.streaming import VisibleFieldSpec


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
class HarnessStreamCompleted(Generic[T]):
    """同一次流式模型调用在完整 JSON 通过 Pydantic 校验后的最终结果。"""

    kind: Literal["completed"]
    generation: HarnessGeneration[T]


HarnessStreamUpdate = HarnessStreamDelta | HarnessStreamCompleted[T]


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
    # 上下游：上游是 LangGraph 业务节点提供的 node_name、case_data、Pydantic output_type 和可选多模态内容；下游是 StructuredLlmClient 及带模型/延迟/Token/最终 Prompt 的 HarnessGeneration。
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
        agent_context: Any | None = None,
        prompt_profile_id: str | None = None,
        multimodal_parts: list[dict[str, Any]] | None = None,
    ) -> HarnessGeneration[T]:
        """执行一次非流式结构化模型调用。"""

        # 二选一：ContextPack 是按节点合同治理后的首选输入；旧调用仍可直接传 PromptSection 列表。
        assembled_context = self._context_window.assemble(
            context_pack.prompt_sections() if context_pack is not None else context_sections or [],
            max_input_tokens=max_input_tokens,
        )
        trusted_agent_context = _trusted_agent_context_payload(agent_context)
        # prompt_profile_id 可以来自显式参数，也可以来自可信 agent_context。
        # 例如不同角色可选择 user/merchant 专属提示词模板。
        resolved_prompt_profile_id = prompt_profile_id or trusted_agent_context.get(
            "prompt_profile_id"
        )
        # `**case_data` 是字典展开；harness_context 使用固定键覆盖同名调用方值，防止伪造裁剪结果。
        enriched_case_data = {
            **case_data,
            "harness_context": assembled_context.as_prompt_payload(),
        }
        # 只附加合同版本和 display-only 名称，不把 display-only 段正文重新塞回模型。
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
        # 把消息保存为 tuple（不可变序列），使测试/审计看到的正是底层客户端接收的两个消息内容。
        messages = tuple(
            # ChatPromptTemplate 来自 LangChain。这里不是让 LangChain 调模型，
            # 只是复用它的 message 格式化能力，最终仍由 app.llm 里的客户端发 HTTP。
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
        # generation_args 只在确有多模态附件时增加 user_content_parts，普通文本调用不改变底层请求形状。
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
        agent_context: Any | None = None,
        prompt_profile_id: str | None = None,
        multimodal_parts: list[dict[str, Any]] | None = None,
    ) -> Iterator[HarnessStreamUpdate[T]]:
        """Stream one structured Harness invocation without a second model call."""

        assembled_context = self._context_window.assemble(
            (
                context_pack.prompt_sections()
                if context_pack is not None
                else context_sections or []
            ),
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
            "visible_fields": visible_fields,
        }
        if multimodal_parts:
            generation_args["user_content_parts"] = multimodal_parts

        # `yield` 使本函数成为生成器：调用者逐条拉取事件，函数不会一次把所有增量存进内存。
        for update in self._llm.generate_stream(**generation_args):
            # isinstance 用来判断对象实际类型。流里有两类事件：
            # 1. StructuredStreamDelta：可见字段增量；
            # 2. StructuredStreamCompleted：完整 JSON 校验后的最终结果。
            if isinstance(update, StructuredStreamDelta):
                yield HarnessStreamDelta(
                    kind="visible_delta",
                    field=update.field,
                    delta=update.delta,
                )
                continue
            # 底层协议只有 Delta/Completed 两种；assert 在开发期捕获网关新增事件却未同步适配的错误。
            assert isinstance(update, StructuredStreamCompleted)
            generation = update.generation
            yield HarnessStreamCompleted(
                kind="completed",
                generation=HarnessGeneration(
                    value=generation.value,  # type: ignore[arg-type]
                    model=generation.model,
                    latency_ms=generation.latency_ms,
                    token_usage=generation.token_usage,
                    context=assembled_context,
                    messages=messages,
                ),
            )


# 所属模块：Agent Harness > 模型执行中枢 > 可信调用上下文最小披露。
# 具体功能：`_trusted_agent_context_payload` 接受 Pydantic 或 dict 调用上下文，只复制案件、房间、参与方、Agent、会话范围与 Prompt Profile 等显式字段；未知对象返回空字典。
# 上下游：上游是 Java 签发的 AgentInvocationContext；下游是 PromptComposer 的 `<trusted_agent_context>` system 片段和角色模板选择。
# 系统意义：即使服务端上下文整体可信，也不能随模型演进自动暴露 tenant、权限细节、密钥或未来新增敏感字段；白名单要求新增披露经过代码审查。
def _trusted_agent_context_payload(agent_context: Any | None) -> dict[str, Any]:
    """只把白名单字段注入 prompt。

    agent_context 是可信系统上下文，但也不能整包塞给模型。这里显式列出允许暴露的字段，
    防止未来新增敏感字段时自动进入 prompt。
    """

    if agent_context is None:
        return {}
    # `isinstance` 分支兼容强类型 Pydantic 上下文和历史 dict 调用；其他类型不尝试反射属性。
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
    # 字典推导式同时过滤值为 None 的可选字段；False、0、空列表等合法显式值仍会被保留。
    return {
        field: raw_context[field]
        for field in allowed_fields
        if raw_context.get(field) is not None
    }
