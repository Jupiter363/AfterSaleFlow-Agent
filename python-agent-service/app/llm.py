# 文件作用：实现 LiteLLM/OpenAI 兼容的结构化 LLM 网关，负责节点预算、JSON/Pydantic 验收、多模态输入复核、限长响应和安全流式解析。

from __future__ import annotations

import base64
import binascii
import json
import re
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass
from io import StringIO
from typing import Any, Literal, Protocol, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from app.streaming import (
    IncrementalVisibleJsonProjector,
    VisibleFieldSpec,
    current_stream_observer,
)


# 这个文件是“真正请求大模型”的底层适配层。
# 上层 Agent 不直接认识 LiteLLM / HTTP / SSE，它们只调用 StructuredLlmClient：
# 1. 传入 system_prompt、user_prompt、期望的 Pydantic 输出模型；
# 2. 这里调用 OpenAI-compatible chat/completions 接口；
# 3. 再把模型返回的 JSON 校验成 Pydantic 对象。
# 对 Python 新手：下面的 TypeVar 表示“泛型类型变量”。T 被限制为 BaseModel 的子类，
# 所以 generate(...) 可以返回任意一种业务输出模型，同时保留类型提示。
T = TypeVar("T", bound=BaseModel)

# 所有大小限制都集中放在常量里，避免模型返回超大内容拖垮服务进程。
_ALLOWED_INLINE_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}
_INLINE_IMAGE_DATA_URL = re.compile(
    r"^data:(image/(?:jpeg|png|webp));base64,([A-Za-z0-9+/]*={0,2})$"
)
_MAX_INLINE_IMAGE_BYTES = 4 * 1024 * 1024
_MAX_INLINE_IMAGE_TOTAL_BYTES = 10 * 1024 * 1024
_MAX_INLINE_IMAGE_DATA_URL_LENGTH = 6 * 1024 * 1024
_MAX_COMPLETION_TOKENS = 16_384
_MAX_MODEL_RESPONSE_BYTES = 2 * 1024 * 1024
_MAX_STREAM_EVENT_BYTES = 128 * 1024
_MAX_STREAM_DELTA_BYTES = 64 * 1024
_MODEL_HEALTH_SUCCESS_TTL_SECONDS = 60.0
_MODEL_HEALTH_FAILURE_TTL_SECONDS = 10.0


@dataclass(frozen=True)
class ModelGenerationBudget:
    """单个节点在供应商侧的结构化输出上限。"""

    max_completion_tokens: int


_DEFAULT_GENERATION_BUDGET = ModelGenerationBudget(
    max_completion_tokens=_MAX_COMPLETION_TOKENS,
)

# 这些预算不切换模型，也不增加调用次数，只调整同一次模型请求的输出上限。
_NODE_GENERATION_BUDGETS: dict[str, ModelGenerationBudget] = {
    "external_import_simulator": ModelGenerationBudget(4_096),
    "intake_analyze": ModelGenerationBudget(4_096),
    "intake_turn_dialogue": ModelGenerationBudget(4_096),
    "intake_turn_case_detail": ModelGenerationBudget(6_144),
    "evidence_turn": ModelGenerationBudget(8_192),
    "evaluation_analyze": ModelGenerationBudget(8_192),
    "hearing_round_turn": ModelGenerationBudget(8_192),
    "issue_framing_node": ModelGenerationBudget(8_192),
    "evidence_gap_request_node": ModelGenerationBudget(8_192),
    "party_liaison_node": ModelGenerationBudget(8_192),
    "evidence_cross_check_node": ModelGenerationBudget(8_192),
    "rule_application_node": ModelGenerationBudget(8_192),
    "adjudication_draft_node": ModelGenerationBudget(12_288),
    "evidence_critic": ModelGenerationBudget(8_192),
    "rule_critic": ModelGenerationBudget(8_192),
    "risk_critic": ModelGenerationBudget(8_192),
    "remedy_critic": ModelGenerationBudget(8_192),
    "fairness_critic": ModelGenerationBudget(8_192),
    "review_copilot": ModelGenerationBudget(8_192),
}


# 所属模块：LLM 网关 > 节点资源治理 > 生成预算查询。
# 具体功能：`_generation_budget_for` 按 node_name 返回职责匹配的输出上限，未登记节点使用保守统一默认值。
# 上下游：上游是 `_completion_request_body` 构造任何文本/流式请求；下游是 OpenAI 兼容参数 max_tokens。
# 系统意义：业务节点不能从案件数据自行抬高预算；集中映射让延迟、成本和不同 Agent 职责保持可审计。
def _generation_budget_for(node_name: str) -> ModelGenerationBudget:
    return _NODE_GENERATION_BUDGETS.get(node_name, _DEFAULT_GENERATION_BUDGET)


# 所属模块：LLM 网关 > Provider 结构化输出 > Schema 名称规范化。
# 具体功能：`_response_schema_name` 把 node_name 中供应商不接受的字符替换为下划线、去首尾下划线、为空时回退 agent_output，并限制 64 字符。
# 上下游：上游是 `_completion_request_body` 当前业务节点名；下游是 response_format.json_schema.name，Schema 正文本身仍来自 output_type.model_json_schema()。
# 系统意义：节点名用于供应商 Schema 标识而非业务授权；规范化可避免合法节点因命名字符导致请求被拒，也阻止超长/特殊字符污染协议。
def _response_schema_name(node_name: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9_-]+", "_", node_name).strip("_")
    return (normalized or "agent_output")[:64]


class AgentOutputSchemaError(RuntimeError):
    """模型返回了内容，但内容无法通过业务 JSON Schema / Pydantic 校验。"""

    # 所属模块：LLM 网关 > 错误合同 > 结构化输出失败异常。
    # 具体功能：`__init__` 在标准异常消息之外保存 node_name，使 API、工作流和人工兜底能定位哪一个 Agent 节点违反输出 Schema。
    # 上下游：上游是非流式/流式 Pydantic 校验、矩阵引用护栏等；下游是稳定错误映射、trace 与 MANUAL_REVIEW_REQUIRED 原因。
    # 系统意义：模型服务“可达”与模型结果“业务不可接受”必须分型，后者不能被当网络错误无脑重试或提交自由文本。
    def __init__(self, node_name: str, message: str) -> None:
        super().__init__(message)
        self.node_name = node_name


class AgentServiceUnavailable(RuntimeError):
    """模型服务、LiteLLM 网关或网络不可用时抛出。"""

    pass


@dataclass(frozen=True)
class StructuredGeneration:
    """一次结构化模型调用的完整结果。

    @dataclass 会自动生成 __init__ 等方法；frozen=True 表示实例创建后不可修改。
    value 是已经通过 Pydantic 校验的业务对象，不是裸字符串。
    """

    value: BaseModel
    model: str
    latency_ms: int
    token_usage: dict[str, int]


@dataclass(frozen=True)
class StructuredStreamDelta:
    """从结构化 JSON 中投影出的单个白名单字段增量。"""

    kind: Literal["visible_delta"]
    field: str
    delta: str


@dataclass(frozen=True)
class StructuredStreamCompleted:
    """同一流完整收齐并通过 Pydantic 后的唯一终态。"""

    kind: Literal["completed"]
    generation: StructuredGeneration


StructuredStreamUpdate = StructuredStreamDelta | StructuredStreamCompleted


class StructuredLlmClient(Protocol):
    """LLM 客户端协议。

    Protocol 是“鸭子类型”的接口声明：只要某个对象拥有同名方法和兼容签名，
    就可以被当作 StructuredLlmClient 使用，不强制继承这个类。
    """

    # 所属模块：LLM 网关 > 客户端协议 > 非流式结构化能力合同。
    # 具体功能：`generate` 声明实现方必须接收节点、两层 Prompt、目标 Pydantic 类型及可选已校验多模态 parts，并返回 StructuredGeneration。
    # 上下游：上游是 HarnessModelRunner/业务节点；下游可以是 LiteLlmProxyClient 或兼容测试替身。
    # 系统意义：Protocol 采用结构化鸭子类型解耦业务与供应商实现，但返回值必须是已校验业务对象而非裸字符串。
    def generate(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[T],
        user_content_parts: list[dict[str, Any]] | None = None,
    ) -> StructuredGeneration: ...

    # 所属模块：LLM 网关 > 客户端协议 > 单请求流式结构化能力合同。
    # 具体功能：`generate_stream` 声明实现方只能按 visible_fields 产生增量，并在同一 Iterator 尾部给出已校验 StructuredStreamCompleted。
    # 上下游：上游是 HarnessModelRunner 或绑定 AgentStreamObserver 的同步工作流；下游是白名单字段 delta 与最终结构化 generation。
    # 系统意义：流式 API 不等于信任半成品 JSON；消费者必须等待 completed 才可提交业务结果。
    def generate_stream(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[T],
        visible_fields: tuple[VisibleFieldSpec, ...] = (),
        user_content_parts: list[dict[str, Any]] | None = None,
    ) -> Iterator[StructuredStreamUpdate]: ...


class LiteLlmProxyClient:
    """LiteLLM 代理客户端。

    本项目通过 LiteLLM 统一访问不同模型供应商。这里使用 OpenAI 兼容接口，
    并额外做了 JSON 模式降级、响应大小限制、多模态图片校验和流式投影。
    """

    # 所属模块：LLM 网关 > LiteLLM 适配器 > 连接配置初始化。
    # 具体功能：`__init__` 固定代理 base_url、模型别名、Bearer API key、总超时和可测试 httpx transport；去除尾斜杠供 URL 统一拼接。
    # 上下游：上游是服务配置；下游是健康检查、非流式 POST 和 SSE stream 共用连接参数。
    # 系统意义：业务请求不能指定模型、密钥或代理地址；所有节点经同一大小/时限/响应解析边界访问供应商。
    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str,
        timeout_seconds: float = 120.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._api_key = api_key
        self._timeout = timeout_seconds
        self._transport = transport
        self._health_lock = threading.Lock()
        self._health_cached_at = 0.0
        self._health_cached_result: dict[str, Any] | None = None
        self._health_failed_at = 0.0

    # 所属模块：LLM 网关 > LiteLLM 适配器 > 真实模型可用性探测。
    # 具体功能：`check_available` 发送 temperature=0、max_tokens=3 的最小 chat completion，在最多 15 秒内验证 HTTP、响应 JSON 和 choices，并返回实际模型名与耗时。
    # 上下游：上游是健康检查/启动诊断接口；下游是 `_post_with_limited_response` 和 AgentServiceUnavailable，不进入任何案件工作流状态。
    # 系统意义：能连到代理端口不代表模型路由可用；真实最小调用可发现认证/路由/供应商故障，同时仍限制响应体与等待时间。
    def check_available(self) -> dict[str, Any]:
        """健康检查：发一个极小的 ping 请求，确认模型网关可用。"""

        # Several room tabs poll this endpoint. A lock plus short TTL keeps
        # those polls from becoming concurrent paid model generations that
        # contend with the actual intake/evidence/hearing request.
        with self._health_lock:
            now = time.monotonic()
            if (
                self._health_cached_result is not None
                and now - self._health_cached_at < _MODEL_HEALTH_SUCCESS_TTL_SECONDS
            ):
                return dict(self._health_cached_result)
            if (
                self._health_failed_at > 0
                and now - self._health_failed_at < _MODEL_HEALTH_FAILURE_TTL_SECONDS
            ):
                raise AgentServiceUnavailable("LLM health check recently failed")
            try:
                result = self._probe_available()
            except AgentServiceUnavailable:
                self._health_cached_result = None
                self._health_failed_at = time.monotonic()
                raise
            self._health_cached_result = dict(result)
            self._health_cached_at = time.monotonic()
            self._health_failed_at = 0.0
            return dict(result)

    def _probe_available(self) -> dict[str, Any]:
        """Perform the one real provider probe used to refresh the cache."""

        started = time.perf_counter()
        request_body: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": "Return a short health check token."},
                {"role": "user", "content": "ping"},
            ],
            "temperature": 0,
            "max_tokens": 3,
            # The probe validates routing/authentication, not reasoning quality.
            # Explicitly overriding the model-level default keeps it sub-second
            # on providers where thinking is otherwise enabled globally.
            "enable_thinking": False,
        }
        try:
            with httpx.Client(
                timeout=min(self._timeout, 15.0), transport=self._transport
            ) as client:
                response = _post_with_limited_response(
                    client,
                    self._chat_completions_url(),
                    headers={"Authorization": f"Bearer {self._api_key}"},
                    json_body=request_body,
                    max_body_bytes=_MAX_MODEL_RESPONSE_BYTES,
                    deadline_seconds=min(self._timeout, 15.0),
                )
                response.raise_for_status()
                try:
                    payload: dict[str, Any] = response.json()
                except ValueError as exception:
                    raise AgentServiceUnavailable(
                        "LiteLLM proxy returned invalid JSON"
                    ) from exception
                if not payload.get("choices"):
                    raise AgentServiceUnavailable("LiteLLM proxy returned no choices")
        except httpx.HTTPError as exception:
            raise AgentServiceUnavailable("LLM health check failed") from exception
        return {
            "model": str(payload.get("model") or self._model),
            "latency_ms": int((time.perf_counter() - started) * 1000),
        }

    # 所属模块：LLM 网关 > LiteLLM 适配器 > 非流式/透明流式统一入口。
    # 具体功能：`generate` 若当前 ContextVar 绑定流 observer，则消费一次真实 stream 并转发可见 delta；否则优先请求 provider strict JSON Schema，只有供应商拒绝 response_format 或严格结果仍不可解析时才回退普通文本提取 JSON。
    # 上下游：上游是 HarnessModelRunner 提供的 Prompt/output_type/多模态 parts；下游是 StructuredGeneration、observer usage，或分型的服务不可用/Schema 错误。
    # 系统意义：普通同步工作流无需改写也可被外层流式响应观察；任何路径最终都由 Pydantic 验收，reasoning_content 和未校验字段不返回。
    def generate(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[T],
        user_content_parts: list[dict[str, Any]] | None = None,
    ) -> StructuredGeneration:
        """非流式结构化调用。

        关键链路：
        1. 如果当前请求绑定了流式 observer，就改走 generate_stream 并把可见字段吐给前端；
        2. 否则先尝试 response_format=json_schema，并附上 output_type 的严格 Schema；
        3. 如果供应商不支持该 response_format，再退回普通文本并从文本中抽取 JSON；
        4. 最后用 output_type.model_validate_json(...) 做强类型校验。
        """

        # ContextVar 让同一同步调用栈感知外层流请求；没有绑定时保持传统非流式行为。
        observer = current_stream_observer()
        if observer is not None:
            completed: StructuredGeneration | None = None
            for update in self.generate_stream(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
                visible_fields=observer.visible_fields_for(node_name),
                user_content_parts=user_content_parts,
            ):
                if isinstance(update, StructuredStreamDelta):
                    observer.visible_delta(
                        node_name,
                        update.field,
                        update.delta,
                    )
                else:
                    completed = update.generation
            if completed is None:
                raise AgentServiceUnavailable("LLM stream ended without a result")
            observer.usage(
                node_name=node_name,
                model=completed.model,
                latency_ms=completed.latency_ms,
                token_usage=completed.token_usage,
            )
            return completed

        started = time.perf_counter()
        try:
            with httpx.Client(
                timeout=self._timeout, transport=self._transport
            ) as client:
                # allow_json_extraction=False 表示当前期待供应商按 strict JSON Schema 直接返回纯 JSON；
                # 一旦 response_format 被网关拒绝，才允许从普通文本中截取 JSON 对象。
                allow_json_extraction = False
                # 第一请求要求供应商执行 strict JSON Schema；只有明确兼容性问题才允许第二请求，不对认证、限流等错误盲目重试。
                try:
                    payload = self._request_completion(
                        client,
                        node_name=node_name,
                        output_type=output_type,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
                        user_content_parts=user_content_parts,
                        json_mode=True,
                    )
                except httpx.HTTPStatusError as exception:
                    if not self._is_response_format_rejection(exception):
                        raise
                    payload = self._request_completion(
                        client,
                        node_name=node_name,
                        output_type=output_type,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
                        user_content_parts=user_content_parts,
                        json_mode=False,
                    )
                    allow_json_extraction = True
                try:
                    value = self._parse_structured_payload(
                        payload,
                        output_type,
                        allow_json_extraction=allow_json_extraction,
                    )
                # 某些代理声称接受 response_format 却仍返回不合 Schema 内容；只在首次严格解析失败时用普通模式重试一次。
                except (KeyError, IndexError, TypeError, ValidationError, ValueError):
                    if allow_json_extraction:
                        raise
                    payload = self._request_completion(
                        client,
                        node_name=node_name,
                        output_type=output_type,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
                        user_content_parts=user_content_parts,
                        json_mode=False,
                    )
                    value = self._parse_structured_payload(
                        payload,
                        output_type,
                        allow_json_extraction=True,
                    )
        except httpx.HTTPError as exception:
            raise AgentServiceUnavailable("LLM request failed") from exception
        except (KeyError, IndexError, TypeError, ValidationError, ValueError) as exception:
            raise AgentOutputSchemaError(
                node_name, f"{node_name} returned invalid structured output"
            ) from exception
        latency_ms = int((time.perf_counter() - started) * 1000)
        usage = payload.get("usage") or {}
        return StructuredGeneration(
            value=value,
            model=str(payload.get("model") or self._model),
            latency_ms=latency_ms,
            token_usage={
                "input": int(usage.get("prompt_tokens") or 0),
                "output": int(usage.get("completion_tokens") or 0),
                "total": int(usage.get("total_tokens") or 0),
            },
        )

    # 所属模块：LLM 网关 > LiteLLM 适配器 > 单请求 SSE 结构化流。
    # 具体功能：`generate_stream` 发起一次 provider strict JSON Schema 流请求，逐行限长解析 SSE，仅累计 delta.content；同时经 IncrementalVisibleJsonProjector 投影白名单字段，完整收齐后再用 output_type 校验并产出唯一 completed。
    # 上下游：上游是 `generate` 的 observer 路径或 HarnessModelRunner.invoke_structured_stream；下游是 StructuredStreamDelta/Completed Iterator。
    # 系统意义：流一旦开始不做模板/Schema/response_format 二次调用，确保前端增量与最终结果来自同一次生成；reasoning_content 从不读取、累计或公开。
    def generate_stream(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[T],
        visible_fields: tuple[VisibleFieldSpec, ...] = (),
        user_content_parts: list[dict[str, Any]] | None = None,
    ) -> Iterator[StructuredStreamUpdate]:
        """Perform one real provider stream and validate its final JSON.

        This path intentionally has no response-format retry, schema retry, or
        template fallback. Once the provider request starts, a failure belongs
        to this run. ``reasoning_content`` is never accumulated or projected.
        """

        started = time.perf_counter()
        # projector 只从“允许公开”的 JSON 字段中增量提取文本，例如 room_utterance。
        # 这样前端能边生成边显示，但内部推理、证据矩阵、工具参数不会泄露。
        projector = IncrementalVisibleJsonProjector(visible_fields)
        # StringIO 用一个可增长缓冲区保存有硬上限的最终 JSON；若用许多 token 小字符串列表，
        # Python 对象开销可能远大于正文。
        content_buffer = StringIO()
        content_bytes = 0
        model = self._model
        usage: dict[str, Any] = {}
        stream_observer = current_stream_observer()

        try:
            with httpx.Client(
                timeout=self._timeout,
                transport=self._transport,
            ) as client:
                request_body = self._completion_request_body(
                    node_name=node_name,
                    output_type=output_type,
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    user_content_parts=user_content_parts,
                    json_mode=True,
                )
                request_body["stream"] = True
                request_body["stream_options"] = {"include_usage": True}
                with client.stream(
                    "POST",
                    self._chat_completions_url(),
                    headers={
                        "Authorization": f"Bearer {self._api_key}",
                        # Avoid compressed response expansion before the stream
                        # event and cumulative document limits can be enforced.
                        "Accept-Encoding": "identity",
                    },
                    json=request_body,
                ) as response:
                    response.raise_for_status()
                    # SSE 流通常是一行一个 data: {...} 事件；这里逐行读取并设置单行上限。
                    for raw_line in _iter_limited_lines(
                        response,
                        max_line_bytes=_MAX_STREAM_EVENT_BYTES,
                    ):
                        if stream_observer is not None:
                            stream_observer.raise_if_cancelled()
                        if time.perf_counter() - started > self._timeout:
                            raise AgentServiceUnavailable(
                                "LiteLLM proxy stream exceeded the request deadline"
                            )
                        line = raw_line.strip()
                        if not line or line.startswith(b":"):
                            continue
                        if line.startswith((b"event:", b"id:", b"retry:")):
                            continue
                        if line.startswith(b"data:"):
                            line = line[5:].strip()
                        if line == b"[DONE]":
                            break
                        try:
                            payload = json.loads(line)
                        except ValueError as exception:
                            raise AgentServiceUnavailable(
                                "LiteLLM proxy returned an invalid stream event"
                            ) from exception
                        if not isinstance(payload, dict):
                            raise AgentServiceUnavailable(
                                "LiteLLM proxy returned an invalid stream event"
                            )
                        if payload.get("error") is not None:
                            raise AgentServiceUnavailable(
                                "LiteLLM proxy returned a stream error"
                            )
                        model = str(payload.get("model") or model)
                        if isinstance(payload.get("usage"), dict):
                            usage = payload["usage"]
                        choices = payload.get("choices")
                        if not isinstance(choices, list) or not choices:
                            continue
                        choice = choices[0]
                        if not isinstance(choice, dict):
                            continue
                        delta_payload = choice.get("delta")
                        if not isinstance(delta_payload, dict):
                            continue
                        # 只读取供应商最终 answer 的 content；即使事件含 reasoning_content 也完全忽略。
                        content_delta = _stream_text_content(
                            delta_payload.get("content")
                        )
                        if not content_delta:
                            continue
                        try:
                            delta_bytes = len(content_delta.encode("utf-8"))
                        except UnicodeEncodeError as exception:
                            raise AgentServiceUnavailable(
                                "LiteLLM proxy returned invalid Unicode content"
                            ) from exception
                        if delta_bytes > _MAX_STREAM_DELTA_BYTES:
                            raise AgentOutputSchemaError(
                                node_name,
                                f"{node_name} streamed a content delta above the size limit",
                            )
                        content_bytes += delta_bytes
                        if content_bytes > _MAX_MODEL_RESPONSE_BYTES:
                            raise AgentOutputSchemaError(
                                node_name,
                                f"{node_name} streamed output above the size limit",
                            )
                        # 在保存新块前检查累计 UTF-8 字节上限，使最终 JSON 缓冲和公开投影器共享同一硬内存边界。
                        projected_deltas = projector.feed(content_delta)
                        content_buffer.write(content_delta)
                        # yield 是 Python 生成器语法：函数不会一次性返回完整列表，
                        # 而是每拿到一个可见增量就“产出”给调用方。
                        for field, delta in projected_deltas:
                            yield StructuredStreamDelta(
                                kind="visible_delta",
                                field=field,
                                delta=delta,
                            )
        except httpx.HTTPError as exception:
            raise AgentServiceUnavailable("LLM streaming request failed") from exception

        content = content_buffer.getvalue()
        try:
            # 流式阶段也必须等完整 JSON 收齐后再做 Pydantic 校验；
            # 前面投影出的只是“可见字段增量”，不能当成最终可信结果。
            value = output_type.model_validate_json(content)
        except (TypeError, ValidationError, ValueError) as exception:
            raise AgentOutputSchemaError(
                node_name,
                f"{node_name} returned invalid streamed structured output",
            ) from exception

        latency_ms = int((time.perf_counter() - started) * 1000)
        yield StructuredStreamCompleted(
            kind="completed",
            generation=StructuredGeneration(
                value=value,
                model=model,
                latency_ms=latency_ms,
                token_usage={
                    "input": int(usage.get("prompt_tokens") or 0),
                    "output": int(usage.get("completion_tokens") or 0),
                    "total": int(usage.get("total_tokens") or 0),
                },
            ),
        )

    # 所属模块：LLM 网关 > LiteLLM 适配器 > 单次非流式 HTTP 请求。
    # 具体功能：`_request_completion` 构造节点预算请求体，经限长 POST 获取响应，先 raise_for_status，再把合法 JSON object 返回给上层结构化解析。
    # 上下游：上游是 `generate` 的严格 JSON 或兼容回退尝试；下游是 `_completion_request_body`、限长传输与 `_parse_structured_payload`。
    # 系统意义：HTTP 成功与业务 Schema 成功分层处理；无效代理 JSON 属服务故障，不能作为模型业务输出继续解析。
    def _request_completion(
        self,
        client: httpx.Client,
        *,
        node_name: str,
        output_type: type[BaseModel],
        system_prompt: str,
        user_prompt: str,
        user_content_parts: list[dict[str, Any]] | None,
        json_mode: bool,
    ) -> dict[str, Any]:
        request_body = self._completion_request_body(
            node_name=node_name,
            output_type=output_type,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            user_content_parts=user_content_parts,
            json_mode=json_mode,
        )
        response = _post_with_limited_response(
            client,
            self._chat_completions_url(),
            headers={"Authorization": f"Bearer {self._api_key}"},
            json_body=request_body,
            max_body_bytes=_MAX_MODEL_RESPONSE_BYTES,
            deadline_seconds=self._timeout,
        )
        response.raise_for_status()
        try:
            payload: dict[str, Any] = response.json()
        except ValueError as exception:
            raise AgentServiceUnavailable("LiteLLM proxy returned invalid JSON") from exception
        return payload

    # 所属模块：LLM 网关 > LiteLLM 适配器 > OpenAI 兼容请求体构造。
    # 具体功能：`_completion_request_body` 固定 system/user 消息、temperature=0、节点输出预算并关闭 Thinking；有图片时先二次校验，json_mode 时把 output_type 的 Pydantic JSON Schema 作为 strict provider response_format。
    # 上下游：上游是非流式与流式请求路径；下游是 LiteLLM `/chat/completions` HTTP JSON body。
    # 系统意义：所有业务节点统一关闭隐藏推理；外部调用者不能通过 case_data 覆盖模型、温度、预算或 response_format。
    def _completion_request_body(
        self,
        *,
        node_name: str,
        output_type: type[BaseModel],
        system_prompt: str,
        user_prompt: str,
        user_content_parts: list[dict[str, Any]] | None,
        json_mode: bool,
    ) -> dict[str, Any]:
        """组装 OpenAI-compatible chat/completions 请求体。"""

        user_content: str | list[dict[str, Any]] = user_prompt
        if user_content_parts:
            user_content = [
                {"type": "text", "text": user_prompt},
                *self._validated_multimodal_parts(user_content_parts),
            ]
        budget = _generation_budget_for(node_name)
        request_body: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
            "temperature": 0,
            "max_tokens": budget.max_completion_tokens,
            # 当前正式调用统一关闭 Qwen 隐藏推理；不再传 thinking_budget，避免网关或供应商误判为仍需推理。
            "enable_thinking": False,
        }
        if json_mode:
            # 普通 json_object 只保证语法是 JSON；供应商侧 strict Schema 可提前限制枚举、额外字段和标量类型，
            # 降低用户已看到部分流文本后，最终对象才因 Pydantic 不通过而被整体丢弃的概率。
            request_body["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": _response_schema_name(node_name),
                    "strict": True,
                    "schema": output_type.model_json_schema(),
                },
            }
        return request_body

    # 所属模块：LLM 网关 > 多模态边界 > content parts 二次验收。
    # 具体功能：`_validated_multimodal_parts` 只接受纯文本或内联 PNG/JPEG/WebP data URL，校验 URL 长度、严格 base64、单图/总字节、magic bytes 与 detail 枚举，并重建最小安全对象。
    # 上下游：上游通常是 EvidenceAssetLoader 已筛选的 content_parts；下游是 OpenAI 兼容 user content 数组。
    # 系统意义：纵深防御不信任上层“已校验”承诺，拒绝外部 URL、伪 MIME、压缩超限或未知 part，避免 SSRF 与超大图片内存风险。
    @staticmethod
    def _validated_multimodal_parts(
        parts: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """校验多模态输入。

        当前只允许内联 base64 图片，且会检查 MIME、大小和真实文件头，避免把不可信 URL
        或伪装类型的二进制内容交给模型网关。
        """

        validated: list[dict[str, Any]] = []
        total_image_bytes = 0
        for part in parts:
            part_type = part.get("type")
            if part_type == "text" and isinstance(part.get("text"), str):
                validated.append({"type": "text", "text": part["text"]})
                continue
            image_url = part.get("image_url")
            if part_type != "image_url" or not isinstance(image_url, dict):
                raise ValueError("unsupported multimodal content part")
            url = image_url.get("url")
            detail = image_url.get("detail", "high")
            if not isinstance(url, str):
                raise ValueError("multimodal image must use an inline image data URL")
            if len(url) > _MAX_INLINE_IMAGE_DATA_URL_LENGTH:
                raise ValueError("multimodal image data URL exceeds size limit")
            match = _INLINE_IMAGE_DATA_URL.fullmatch(url)
            if match is None or match.group(1) not in _ALLOWED_INLINE_IMAGE_TYPES:
                raise ValueError(
                    "multimodal image must be a base64 PNG, JPEG, or WebP data URL"
                )
            try:
                decoded = base64.b64decode(match.group(2), validate=True)
            except (binascii.Error, ValueError) as exception:
                raise ValueError("multimodal image contains invalid base64") from exception
            if not decoded or len(decoded) > _MAX_INLINE_IMAGE_BYTES:
                raise ValueError("multimodal image payload has an invalid size")
            total_image_bytes += len(decoded)
            if total_image_bytes > _MAX_INLINE_IMAGE_TOTAL_BYTES:
                raise ValueError("multimodal image payloads exceed total size limit")
            if not _inline_image_matches_mime(decoded, match.group(1)):
                raise ValueError("multimodal image MIME does not match its payload")
            if detail not in {"auto", "low", "high"}:
                raise ValueError("unsupported multimodal image detail")
            validated.append(
                {
                    "type": "image_url",
                    "image_url": {"url": url, "detail": detail},
                }
            )
        return validated

    # 所属模块：LLM 网关 > LiteLLM 适配器 > chat completions URL 规范化。
    # 具体功能：`_chat_completions_url` 兼容配置到代理根路径或已包含 `/v1` 两种形式，确保最终只追加一次 `/v1/chat/completions`。
    # 上下游：上游是健康检查、非流式和 SSE 请求；下游是 httpx 请求 URL。
    # 系统意义：集中拼接避免不同调用路径出现双 `/v1` 或遗漏版本前缀，模型流与普通调用始终命中同一代理路由。
    def _chat_completions_url(self) -> str:
        if self._base_url.endswith("/v1"):
            return f"{self._base_url}/chat/completions"
        return f"{self._base_url}/v1/chat/completions"

    # 所属模块：LLM 网关 > 结构化验收 > OpenAI 响应解析。
    # 具体功能：`_parse_structured_payload` 只取 choices[0].message.content；兼容模式可先截取 JSON 对象，随后始终调用目标 Pydantic 类型的 model_validate_json。
    # 上下游：上游是限长且 HTTP/JSON 成功的供应商 payload；下游是强类型业务值或由 generate 映射的 AgentOutputSchemaError。
    # 系统意义：message.reasoning_content 永远不是业务载荷，也不持久化；只有 content 中满足 Schema 的完整对象能越过网关。
    @staticmethod
    def _parse_structured_payload(
        payload: dict[str, Any],
        output_type: type[T],
        *,
        allow_json_extraction: bool,
    ) -> T:
        """从供应商响应中取出 assistant.content，并校验为指定 Pydantic 模型。"""

        message = payload["choices"][0]["message"]
        # 即使供应商返回 reasoning_content，也不读取；业务载荷唯一来源是 assistant.content。
        content = str(message.get("content") or "")
        if allow_json_extraction:
            content = LiteLlmProxyClient._extract_json_object(content)
        return output_type.model_validate_json(content)

    # 所属模块：LLM 网关 > 结构化验收 > 非 JSON-mode 兼容提取。
    # 具体功能：`_extract_json_object` 若内容本身是 `{...}` 直接返回，否则仅截取第一个左花括号到最后一个右花括号；找不到完整边界则原样交给 Pydantic 报错。
    # 上下游：上游仅是 generate 明确允许 extraction 的供应商兼容回退；下游是 model_validate_json。
    # 系统意义：该函数不尝试修复字段、补括号或解释 Markdown 语义，避免“宽松修复”把错误模型文本变成看似合法业务结果。
    @staticmethod
    def _extract_json_object(content: str) -> str:
        stripped = content.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            return stripped
        start = stripped.find("{")
        end = stripped.rfind("}")
        if start == -1 or end == -1 or end <= start:
            return stripped
        return stripped[start : end + 1]

    # 所属模块：LLM 网关 > 兼容回退 > response_format 拒绝识别。
    # 具体功能：`_is_response_format_rejection` 仅对 400/422 且响应体提到 response_format/json_object/json mode 返回 True；其他状态全部不允许格式回退。
    # 上下游：上游是 json_mode 请求的 HTTPStatusError；下游决定 generate 是否发起一次普通文本兼容请求。
    # 系统意义：认证失败、限流、服务错误不能被误判为格式兼容问题并重复计费；回退范围保持最小且可解释。
    @staticmethod
    def _is_response_format_rejection(exception: httpx.HTTPStatusError) -> bool:
        response = exception.response
        if response.status_code not in {400, 422}:
            return False
        body = response.text.lower()
        return (
            "response_format" in body
            or "json_object" in body
            or "json mode" in body
        )


# 所属模块：LLM 网关 > 多模态边界 > 图片 MIME 与 magic bytes 对照。
# 具体功能：`_inline_image_matches_mime` 对 PNG/JPEG 检查固定文件头，对 WebP 同时检查 RIFF 和 WEBP 标记，其他 MIME 返回 False。
# 上下游：上游是已严格 base64 解码的图片 bytes 与 data URL 声明 MIME；下游是多模态 parts 验收通过/拒绝。
# 系统意义：data URL 标签仍是不可信文本，必须与真实二进制格式一致后才发送给模型供应商。
def _inline_image_matches_mime(payload: bytes, mime_type: str) -> bool:
    if mime_type == "image/png":
        return payload.startswith(b"\x89PNG\r\n\x1a\n")
    if mime_type == "image/jpeg":
        return payload.startswith(b"\xff\xd8\xff")
    return (
        mime_type == "image/webp"
        and len(payload) >= 12
        and payload[:4] == b"RIFF"
        and payload[8:12] == b"WEBP"
    )


# 所属模块：LLM 网关 > SSE 解析 > answer 文本增量提取。
# 具体功能：`_stream_text_content` 接受供应商 delta.content 的字符串或 typed parts 列表，只拼接 type=text/output_text 的显式 text；未知结构返回空。
# 上下游：上游是 choices[0].delta.content；下游是完整 JSON 缓冲与可见字段 projector。
# 系统意义：不读取 reasoning、tool call 或其他供应商扩展 part，防止内部推理和工具参数混入用户可见流或最终业务 JSON。
def _stream_text_content(value: Any) -> str:
    if isinstance(value, str):
        return value
    if not isinstance(value, list):
        return ""
    # 部分 OpenAI 兼容网关把文本拆成 typed parts；只接受明确的答案文本类型，其他类型全部忽略。
    return "".join(
        str(part.get("text") or "")
        for part in value
        if isinstance(part, dict) and part.get("type") in {"text", "output_text"}
    )


# 所属模块：LLM 网关 > HTTP 资源边界 > 限长 POST 包装。
# 具体功能：`_post_with_limited_response` 强制 Accept-Encoding=identity，以 stream 模式读取并限长响应体，再重建普通 httpx.Response 供上层统一 raise_for_status/json。
# 上下游：上游是健康检查和非流式 completion；下游是 `_read_limited_body` 与上层 HTTP/JSON 处理。
# 系统意义：不能先让 httpx 无界读完整响应再检查长度；禁压缩避免小压缩包在解压后越过内存上限。
def _post_with_limited_response(
    client: httpx.Client,
    url: str,
    *,
    headers: dict[str, str],
    json_body: dict[str, Any],
    max_body_bytes: int,
    deadline_seconds: float,
) -> httpx.Response:
    """普通 POST 的安全包装：边读响应边限制最大字节数。"""

    request_headers = {**headers, "Accept-Encoding": "identity"}
    with client.stream(
        "POST",
        url,
        headers=request_headers,
        json=json_body,
    ) as streamed_response:
        body = _read_limited_body(
            streamed_response,
            max_body_bytes=max_body_bytes,
            deadline_seconds=deadline_seconds,
        )
        return httpx.Response(
            status_code=streamed_response.status_code,
            headers=streamed_response.headers,
            content=body,
            request=streamed_response.request,
        )


# 所属模块：LLM 网关 > HTTP 资源边界 > 响应体逐块读取。
# 具体功能：`_read_limited_body` 先用可信度有限的 Content-Length 做快速拒绝，再以 16KiB chunk 实际累计 bytearray，并在每块前检查单调时钟 deadline 与真实总字节上限。
# 上下游：上游是 `_post_with_limited_response` 的 streamed_response；下游是有限 bytes 或 AgentServiceUnavailable。
# 系统意义：同时防御伪造/缺失 Content-Length、慢速响应和超大模型输出，避免代理拖垮工作线程或进程内存。
def _read_limited_body(
    response: httpx.Response,
    *,
    max_body_bytes: int,
    deadline_seconds: float,
) -> bytes:
    content_length = response.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > max_body_bytes:
                raise AgentServiceUnavailable(
                    "LiteLLM proxy response exceeded the size limit"
                )
        except ValueError:
            pass

    body = bytearray()
    deadline = time.monotonic() + deadline_seconds
    for chunk in response.iter_bytes(chunk_size=16 * 1024):
        if time.monotonic() > deadline:
            raise AgentServiceUnavailable(
                "LiteLLM proxy response exceeded the request deadline"
            )
        if len(body) + len(chunk) > max_body_bytes:
            raise AgentServiceUnavailable(
                "LiteLLM proxy response exceeded the size limit"
            )
        body.extend(chunk)
    return bytes(body)


# 所属模块：LLM 网关 > SSE 解析 > 有界传输行生成器。
# 具体功能：`_iter_limited_lines` 用最多 max_line_bytes 的 pending bytearray 跨 chunk 拼行，找到换行就 yield bytes 并原地删除已消费前缀；无换行超限立即失败，流尾剩余内容作为最后一行。
# 上下游：上游是 httpx SSE response.iter_bytes；下游是 generate_stream 逐行解析 data/event/id/retry 协议。
# 系统意义：单个恶意或损坏 SSE 事件不能无限占用内存；按 bytes 限制比解码后字符数更接近真实传输资源成本。
def _iter_limited_lines(
    response: httpx.Response,
    *,
    max_line_bytes: int,
) -> Iterator[bytes]:
    """逐条产出原始传输行，不在内存中保留无界 SSE 事件。"""

    pending = bytearray()
    # 不指定 chunk_size：httpx 一旦从上游收到原始块就立即交给本解析器。
    # 指定 16 KiB 会触发 ByteChunker 聚合，小型 SSE 回复可能直到攒满或流结束
    # 才释放首行，导致供应商已经生成的 token 无法及时投影到前端。
    for chunk in response.iter_bytes():
        pending.extend(chunk)
        while True:
            newline_index = pending.find(b"\n")
            if newline_index < 0:
                break
            if newline_index > max_line_bytes:
                raise AgentServiceUnavailable(
                    "LiteLLM proxy stream event exceeded the size limit"
                )
            yield bytes(pending[:newline_index])
            del pending[: newline_index + 1]
        if len(pending) > max_line_bytes:
            raise AgentServiceUnavailable(
                "LiteLLM proxy stream event exceeded the size limit"
            )
    if pending:
        yield bytes(pending)
