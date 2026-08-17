from __future__ import annotations

import asyncio
import hashlib
import json
import time
from collections.abc import AsyncIterator, Callable, Iterator, Sequence
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from contextlib import contextmanager
from contextvars import copy_context
from dataclasses import replace
from datetime import datetime, timezone
from threading import BoundedSemaphore
from typing import Any, TypeVar, cast

from langchain_core.callbacks import AsyncCallbackManagerForLLMRun, CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import (
    AIMessage,
    AIMessageChunk,
    BaseMessage,
    HumanMessage,
    SystemMessage,
)
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult
from pydantic import BaseModel, ConfigDict, Field, PrivateAttr

from app.llm import GovernedProviderRequest
from app.model_runtime.callbacks import (
    GOVERNED_EVENTS_KEY,
    publish_governed_visible_delta,
    visible_delta_event,
)
from app.model_runtime.profiles import ModelInvocationPolicy, ModelProfile, system_prompt_sha256
from app.model_runtime.transports import (
    ModelTransport,
    ModelTransportCompleted,
    ModelTransportRequest,
    ModelTransportResult,
    ModelTransportStreamUpdate,
    ModelTransportVisibleDelta,
    TransientModelTransportError,
)
from app.streaming import (
    AgentStreamObserver,
    VISIBLE_FIELD_VALUE_MODES,
    VisibleFieldSpec,
    bind_stream_observer,
    current_stream_observer,
)


class ModelPolicyViolation(ValueError):
    pass


class ModelDeadlineExceeded(TimeoutError):
    pass


class ModelInvocationCancelled(RuntimeError):
    pass


class ModelStreamInterrupted(RuntimeError):
    pass


class ModelRetryBudgetExhausted(RuntimeError):
    pass


_SYNC_DEADLINE_MAX_OUTSTANDING = 32
_SYNC_DEADLINE_EXECUTOR = ThreadPoolExecutor(
    max_workers=_SYNC_DEADLINE_MAX_OUTSTANDING,
    thread_name_prefix="governed-model-deadline",
)
_SYNC_DEADLINE_SLOTS = BoundedSemaphore(_SYNC_DEADLINE_MAX_OUTSTANDING)
_MAX_MODEL_DOCUMENT_BYTES = 2 * 1024 * 1024
_MAX_VISIBLE_DELTA_BYTES = 64 * 1024
_MAX_PROVIDER_LATENCY_MS = 24 * 60 * 60 * 1_000
_MAX_TOKEN_USAGE = 2_147_483_647
_BUFFERED_INVOKE_OBSERVER_NODE = "intake_turn_case_detail"
SyncResultT = TypeVar("SyncResultT")


class _BufferedIntakeObserver:
    """Withhold exact Intake observer side effects until governed policy accepts."""

    def __init__(self, delegate: AgentStreamObserver) -> None:
        self._delegate = delegate
        self._events: list[tuple[str, dict[str, Any]]] = []

    def raise_if_cancelled(self) -> None:
        self._delegate.raise_if_cancelled()

    def visible_fields_for(self, node_name: str) -> tuple[VisibleFieldSpec, ...]:
        return self._delegate.visible_fields_for(node_name)

    def visible_delta(self, node_name: str, field: str, delta: str) -> None:
        self._events.append(
            (
                "visible_delta",
                {"node_name": node_name, "field": field, "delta": delta},
            )
        )

    def usage(
        self,
        *,
        node_name: str,
        model: str,
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None:
        self._events.append(
            (
                "usage",
                {
                    "node_name": node_name,
                    "model": model,
                    "latency_ms": latency_ms,
                    "token_usage": dict(token_usage),
                },
            )
        )

    def release(self) -> None:
        events, self._events = self._events, []
        for event_type, payload in events:
            if event_type == "visible_delta":
                self._delegate.visible_delta(**payload)
                continue
            self._delegate.usage(**payload)


@contextmanager
def _buffer_intake_observer(
    node_name: str,
) -> Iterator[_BufferedIntakeObserver | None]:
    observer = current_stream_observer()
    if node_name != _BUFFERED_INVOKE_OBSERVER_NODE or observer is None:
        yield None
        return
    buffered = _BufferedIntakeObserver(observer)
    with bind_stream_observer(cast(AgentStreamObserver, buffered)):
        yield buffered


class GovernedChatModel(BaseChatModel):
    """BaseChatModel with immutable profiles, native async, and public-delta separation."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    profile: ModelProfile = Field(frozen=True)
    policy: ModelInvocationPolicy = Field(frozen=True)

    _transport: ModelTransport = PrivateAttr()
    _output_type: type[BaseModel] = PrivateAttr()
    _visible_fields: tuple[VisibleFieldSpec, ...] = PrivateAttr(default=())
    _visible_field_names: frozenset[str] = PrivateAttr(default=frozenset())
    _user_content_parts: tuple[dict[str, Any], ...] = PrivateAttr(default=())
    _cancelled: Callable[[], bool] = PrivateAttr()
    _clock: Callable[[], datetime] = PrivateAttr()

    def __init__(
        self,
        *,
        transport: ModelTransport,
        output_type: type[BaseModel],
        profile: ModelProfile,
        policy: ModelInvocationPolicy,
        visible_fields: tuple[VisibleFieldSpec, ...] = (),
        user_content_parts: Sequence[dict[str, Any]] = (),
        cancellation_probe: Callable[[], bool] | None = None,
        clock: Callable[[], datetime] | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(profile=profile, policy=policy, **kwargs)
        self._transport = transport
        self._output_type = output_type
        self._visible_fields = _validated_visible_fields(visible_fields)
        self._visible_field_names = frozenset(spec.field for spec in self._visible_fields)
        self._user_content_parts = _normalized_content_parts(user_content_parts)
        self._cancelled = cancellation_probe or (lambda: False)
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    @property
    def _llm_type(self) -> str:
        return "governed-structured-chat-model"

    @property
    def _identifying_params(self) -> dict[str, Any]:
        return {
            "profile_id": self.profile.profile_id,
            "provider": self.profile.provider,
            "model": self.profile.model,
            "prompt_version": self.policy.prompt_version,
            "output_schema_version": self.policy.output_schema_version,
            "policy_version": self.policy.policy_version,
            "guardrail_version": self.policy.guardrail_version,
        }

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        del run_manager
        request = self._request(messages, stop=stop, overrides=kwargs)
        prompt_hash = _prompt_hash(request.messages, request.user_content_parts)
        result, attempts = self._generate_with_retry(request)
        return ChatResult(
            generations=[
                ChatGeneration(message=self._message(result, attempts, prompt_hash))
            ]
        )

    async def _agenerate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: AsyncCallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        del run_manager
        request = self._request(messages, stop=stop, overrides=kwargs)
        prompt_hash = _prompt_hash(request.messages, request.user_content_parts)
        result, attempts = await self._agenerate_with_retry(request)
        return ChatResult(
            generations=[
                ChatGeneration(message=self._message(result, attempts, prompt_hash))
            ]
        )

    def _stream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> Iterator[ChatGenerationChunk]:
        del run_manager
        request = self._request(messages, stop=stop, overrides=kwargs)
        prompt_hash = _prompt_hash(request.messages, request.user_content_parts)
        attempts_allowed = self._attempts_allowed()
        if attempts_allowed == 0:
            raise ModelRetryBudgetExhausted("provider attempt budget is exhausted")
        attempts_used = 0
        while attempts_used < attempts_allowed:
            remaining = attempts_allowed - attempts_used
            bounded_request = _request_with_attempt_budget(request, remaining)
            visible = False
            completed_result: ModelTransportResult | None = None
            try:
                self._guard()
                for update in self._sync_stream(bounded_request):
                    self._guard()
                    if completed_result is not None:
                        raise ModelStreamInterrupted(
                            "model transport emitted data after completion"
                        )
                    if isinstance(update, ModelTransportVisibleDelta):
                        update = self._validated_visible_delta(update)
                        visible = True
                        publish_governed_visible_delta(
                            node_name=self.policy.node_name,
                            field=update.field,
                            delta=update.delta,
                        )
                        yield self._visible_chunk(update)
                        continue
                    if not isinstance(update, ModelTransportCompleted):
                        raise ModelStreamInterrupted(
                            "model transport emitted an unknown update"
                        )
                    completed_result = self._validated_result(update.result)
                if completed_result is None:
                    raise TransientModelTransportError(
                        "model transport stream ended without a completed result"
                    )
                attempts_used += _attempts_consumed(
                    completed_result.provider_attempts_used,
                    remaining,
                )
                yield self._final_chunk(completed_result, attempts_used, prompt_hash)
                return
            except TransientModelTransportError as error:
                attempts_used += _attempts_consumed(
                    error.provider_attempts_used,
                    remaining,
                )
                if (
                    visible
                    or completed_result is not None
                    or attempts_used >= attempts_allowed
                    or not self._retry_possible()
                ):
                    raise ModelStreamInterrupted("governed model stream interrupted") from error
                self._sync_backoff()
        raise ModelStreamInterrupted("governed model stream ended without a result")

    async def _astream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: AsyncCallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[ChatGenerationChunk]:
        del run_manager
        request = self._request(messages, stop=stop, overrides=kwargs)
        prompt_hash = _prompt_hash(request.messages, request.user_content_parts)
        attempts_allowed = self._attempts_allowed()
        if attempts_allowed == 0:
            raise ModelRetryBudgetExhausted("provider attempt budget is exhausted")
        attempts_used = 0
        while attempts_used < attempts_allowed:
            remaining = attempts_allowed - attempts_used
            bounded_request = _request_with_attempt_budget(request, remaining)
            visible = False
            completed_result: ModelTransportResult | None = None
            try:
                self._guard()
                async with asyncio.timeout(self._remaining_seconds()):
                    async for update in self._transport.astream(bounded_request):
                        self._guard()
                        if completed_result is not None:
                            raise ModelStreamInterrupted(
                                "model transport emitted data after completion"
                            )
                        if isinstance(update, ModelTransportVisibleDelta):
                            update = self._validated_visible_delta(update)
                            visible = True
                            publish_governed_visible_delta(
                                node_name=self.policy.node_name,
                                field=update.field,
                                delta=update.delta,
                            )
                            yield self._visible_chunk(update)
                            continue
                        if not isinstance(update, ModelTransportCompleted):
                            raise ModelStreamInterrupted(
                                "model transport emitted an unknown update"
                            )
                        completed_result = self._validated_result(update.result)
                if completed_result is None:
                    raise TransientModelTransportError(
                        "model transport stream ended without a completed result"
                    )
                attempts_used += _attempts_consumed(
                    completed_result.provider_attempts_used,
                    remaining,
                )
                yield self._final_chunk(completed_result, attempts_used, prompt_hash)
                return
            except TimeoutError as error:
                raise ModelDeadlineExceeded("model invocation deadline exceeded") from error
            except TransientModelTransportError as error:
                attempts_used += _attempts_consumed(
                    error.provider_attempts_used,
                    remaining,
                )
                if (
                    visible
                    or completed_result is not None
                    or attempts_used >= attempts_allowed
                    or not self._retry_possible()
                ):
                    raise ModelStreamInterrupted("governed model stream interrupted") from error
                await self._async_backoff()
        raise ModelStreamInterrupted("governed model stream ended without a result")

    def _generate_with_retry(
        self, request: ModelTransportRequest
    ) -> tuple[ModelTransportResult, int]:
        attempts_allowed = self._attempts_allowed()
        if attempts_allowed == 0:
            raise ModelRetryBudgetExhausted("provider attempt budget is exhausted")
        attempts_used = 0
        while attempts_used < attempts_allowed:
            remaining = attempts_allowed - attempts_used
            bounded_request = _request_with_attempt_budget(request, remaining)
            try:
                self._guard()
                with _buffer_intake_observer(self.policy.node_name) as buffered_observer:
                    result = self._sync_generate(bounded_request)
                self._guard()
                validated = self._validated_result(result)
                attempts_used += _attempts_consumed(
                    validated.provider_attempts_used,
                    remaining,
                )
                if buffered_observer is not None:
                    buffered_observer.release()
                return validated, attempts_used
            except TransientModelTransportError as error:
                attempts_used += _attempts_consumed(
                    error.provider_attempts_used,
                    remaining,
                )
                if attempts_used >= attempts_allowed or not self._retry_possible():
                    raise
                self._sync_backoff()
        raise AssertionError("bounded model retry loop did not terminate")

    async def _agenerate_with_retry(
        self, request: ModelTransportRequest
    ) -> tuple[ModelTransportResult, int]:
        attempts_allowed = self._attempts_allowed()
        if attempts_allowed == 0:
            raise ModelRetryBudgetExhausted("provider attempt budget is exhausted")
        attempts_used = 0
        while attempts_used < attempts_allowed:
            remaining = attempts_allowed - attempts_used
            bounded_request = _request_with_attempt_budget(request, remaining)
            try:
                self._guard()
                with _buffer_intake_observer(self.policy.node_name) as buffered_observer:
                    async with asyncio.timeout(self._remaining_seconds()):
                        result = await self._transport.agenerate(bounded_request)
                self._guard()
                validated = self._validated_result(result)
                attempts_used += _attempts_consumed(
                    validated.provider_attempts_used,
                    remaining,
                )
                if buffered_observer is not None:
                    buffered_observer.release()
                return validated, attempts_used
            except TimeoutError as error:
                raise ModelDeadlineExceeded("model invocation deadline exceeded") from error
            except TransientModelTransportError as error:
                attempts_used += _attempts_consumed(
                    error.provider_attempts_used,
                    remaining,
                )
                if attempts_used >= attempts_allowed or not self._retry_possible():
                    raise
                await self._async_backoff()
        raise AssertionError("bounded async model retry loop did not terminate")

    def _request(
        self,
        messages: Sequence[BaseMessage],
        *,
        stop: list[str] | None,
        overrides: dict[str, Any],
    ) -> ModelTransportRequest:
        if stop is not None or overrides:
            raise ModelPolicyViolation("model invocation overrides are forbidden")
        if (
            len(messages) != 2
            or not isinstance(messages[0], SystemMessage)
            or not isinstance(messages[1], HumanMessage)
        ):
            raise ModelPolicyViolation(
                "governed model requires exactly one trusted system and one human message"
            )
        system_content = messages[0].content
        if not isinstance(system_content, str) or system_prompt_sha256(
            system_content
        ) != self.policy.trusted_system_sha256:
            raise ModelPolicyViolation("trusted system prompt binding mismatch")
        if not isinstance(messages[1].content, str):
            raise ModelPolicyViolation(
                "governed multimodal input must use trusted content parts"
            )
        return ModelTransportRequest(
            node_name=self.policy.node_name,
            messages=tuple(message.model_copy(deep=True) for message in messages),
            output_type=self._output_type,
            governed_request=GovernedProviderRequest(
                provider=self.profile.provider,
                model=self.profile.model,
                temperature=self.profile.temperature,
                max_output_tokens=self.profile.max_output_tokens,
                response_format="STRICT_JSON_SCHEMA",
                tool_allowlist=self.profile.tool_allowlist,
                deadline_at=self.policy.deadline_at,
                provider_attempts_remaining=self.policy.provider_attempts_remaining,
                repairs_remaining=self.policy.repairs_remaining,
                traceparent=self.policy.traceparent,
            ),
            visible_fields=self._visible_fields,
            user_content_parts=_normalized_content_parts(self._user_content_parts),
        )

    def _sync_generate(self, request: ModelTransportRequest) -> ModelTransportResult:
        return self._sync_call(lambda: self._transport.generate(request))

    def _sync_stream(
        self,
        request: ModelTransportRequest,
    ) -> Iterator[ModelTransportStreamUpdate]:
        iterator = self._sync_call(lambda: iter(self._transport.stream(request)))
        while True:
            has_update, update = self._sync_call(lambda: _next_stream_update(iterator))
            if not has_update:
                return
            if update is None:
                raise ModelStreamInterrupted("model transport emitted an empty update")
            yield update

    def _sync_call(self, operation: Callable[[], SyncResultT]) -> SyncResultT:
        remaining = self._remaining_seconds()
        if remaining <= 0 or not _SYNC_DEADLINE_SLOTS.acquire(timeout=remaining):
            raise ModelDeadlineExceeded("model invocation deadline exceeded")
        slots = _SYNC_DEADLINE_SLOTS
        context = copy_context()
        try:
            future = _SYNC_DEADLINE_EXECUTOR.submit(context.run, operation)
        except BaseException:
            slots.release()
            raise
        future.add_done_callback(lambda _future: slots.release())
        try:
            return future.result(timeout=self._remaining_seconds())
        except FutureTimeoutError as error:
            future.cancel()
            raise ModelDeadlineExceeded("model invocation deadline exceeded") from error

    def _validated_result(self, result: ModelTransportResult) -> ModelTransportResult:
        if not isinstance(result.json_document, str) or not result.json_document:
            raise ModelPolicyViolation("model transport returned an empty JSON document")
        try:
            document_bytes = len(result.json_document.encode("utf-8"))
        except UnicodeEncodeError as error:
            raise ModelPolicyViolation("model transport returned invalid Unicode") from error
        if document_bytes > _MAX_MODEL_DOCUMENT_BYTES:
            raise ModelPolicyViolation("model transport returned an oversized JSON document")
        if result.model != self.profile.model:
            raise ModelPolicyViolation("provider returned a model outside the pinned profile")
        latency_ms = _nonnegative_integer(result.latency_ms, "latency_ms")
        if latency_ms > _MAX_PROVIDER_LATENCY_MS:
            raise ModelPolicyViolation("provider returned implausible latency")
        if not isinstance(result.token_usage, dict):
            raise ModelPolicyViolation("provider returned invalid token usage")
        for key, value in result.token_usage.items():
            if not isinstance(key, str):
                raise ModelPolicyViolation("provider returned invalid token usage")
            _nonnegative_integer(value, f"token_usage.{key}")
        input_tokens = _usage_value(result.token_usage, "input", "input_tokens")
        output_tokens = _usage_value(result.token_usage, "output", "output_tokens")
        total_tokens = _usage_value(
            result.token_usage,
            "total",
            "total_tokens",
            default=input_tokens + output_tokens,
        )
        if total_tokens < input_tokens + output_tokens:
            raise ModelPolicyViolation("provider returned inconsistent token usage")
        if total_tokens > _MAX_TOKEN_USAGE:
            raise ModelPolicyViolation("provider returned excessive token usage")
        if output_tokens > self.profile.max_output_tokens:
            raise ModelPolicyViolation("provider exceeded the pinned output token budget")
        provider_attempts_used = _positive_integer(
            result.provider_attempts_used,
            "provider_attempts_used",
        )
        repairs_used = _nonnegative_integer(result.repairs_used, "repairs_used")
        if repairs_used > self.policy.repairs_remaining:
            raise ModelPolicyViolation("model transport exceeded the repair budget")
        return ModelTransportResult(
            json_document=result.json_document,
            model=result.model,
            latency_ms=latency_ms,
            token_usage={
                "input": input_tokens,
                "output": output_tokens,
                "total": total_tokens,
            },
            provider_attempts_used=provider_attempts_used,
            repairs_used=repairs_used,
        )

    def _validated_visible_delta(
        self,
        update: ModelTransportVisibleDelta,
    ) -> ModelTransportVisibleDelta:
        if update.field not in self._visible_field_names:
            raise ModelPolicyViolation("model transport emitted a non-public field")
        if not isinstance(update.delta, str) or not update.delta:
            raise ModelPolicyViolation("model transport emitted an invalid visible delta")
        try:
            delta_bytes = len(update.delta.encode("utf-8"))
        except UnicodeEncodeError as error:
            raise ModelPolicyViolation("model transport emitted invalid Unicode") from error
        if delta_bytes > _MAX_VISIBLE_DELTA_BYTES:
            raise ModelPolicyViolation("model transport emitted an oversized visible delta")
        return update

    def _message(
        self,
        result: ModelTransportResult,
        attempts: int,
        prompt_hash: str,
    ) -> AIMessage:
        return AIMessage(
            content=result.json_document,
            response_metadata=self._metadata(result, attempts, prompt_hash),
            usage_metadata=_usage_metadata(result.token_usage),
        )

    def _visible_chunk(self, update: ModelTransportVisibleDelta) -> ChatGenerationChunk:
        event = visible_delta_event(
            node_name=self.policy.node_name,
            field=update.field,
            delta=update.delta,
        )
        return ChatGenerationChunk(
            message=AIMessageChunk(
                content="",
                additional_kwargs={GOVERNED_EVENTS_KEY: [event]},
            ),
            generation_info={"governed_event": "visible_delta"},
        )

    def _final_chunk(
        self,
        result: ModelTransportResult,
        attempts: int,
        prompt_hash: str,
    ) -> ChatGenerationChunk:
        return ChatGenerationChunk(
            message=AIMessageChunk(
                content=result.json_document,
                response_metadata=self._metadata(result, attempts, prompt_hash),
                usage_metadata=_usage_metadata(result.token_usage),
            ),
            generation_info={"governed_event": "completed"},
        )

    def _metadata(
        self,
        result: ModelTransportResult,
        attempts: int,
        prompt_hash: str,
    ) -> dict[str, Any]:
        return {
            "provider": self.profile.provider,
            "model": result.model,
            "model_profile_id": self.profile.profile_id,
            "prompt_version": self.policy.prompt_version,
            "prompt_hash": prompt_hash,
            "output_schema_version": self.policy.output_schema_version,
            "policy_version": self.policy.policy_version,
            "guardrail_version": self.policy.guardrail_version,
            "traceparent": self.policy.traceparent,
            "temperature": self.profile.temperature,
            "max_output_tokens": self.profile.max_output_tokens,
            "tool_allowlist": list(self.profile.tool_allowlist),
            "latency_ms": result.latency_ms,
            "token_usage": dict(result.token_usage),
            "transport_attempts": attempts,
            "repairs_used": result.repairs_used,
        }

    def _attempts_allowed(self) -> int:
        return min(
            self.profile.max_provider_attempts,
            self.policy.provider_attempts_remaining,
        )

    def _guard(self) -> None:
        if self._cancelled():
            raise ModelInvocationCancelled("model invocation cancelled")
        if self._remaining_seconds() <= 0:
            raise ModelDeadlineExceeded("model invocation deadline exceeded")

    def _remaining_seconds(self) -> float:
        now = self._clock()
        if now.utcoffset() is None:
            raise ModelPolicyViolation("model clock must return a timezone-aware value")
        return max(0.0, (self.policy.deadline_at - now).total_seconds())

    def _retry_possible(self) -> bool:
        return not self._cancelled() and self._remaining_seconds() > 0

    def _sync_backoff(self) -> None:
        if self.profile.retry_backoff_ms:
            time.sleep(min(self.profile.retry_backoff_ms / 1_000, self._remaining_seconds()))
        self._guard()

    async def _async_backoff(self) -> None:
        if self.profile.retry_backoff_ms:
            await asyncio.sleep(
                min(self.profile.retry_backoff_ms / 1_000, self._remaining_seconds())
            )
        self._guard()


def _usage_metadata(usage: dict[str, int]) -> dict[str, int]:
    input_tokens = _usage_value(usage, "input", "input_tokens")
    output_tokens = _usage_value(usage, "output", "output_tokens")
    total_tokens = _usage_value(
        usage,
        "total",
        "total_tokens",
        default=input_tokens + output_tokens,
    )
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
    }


def _prompt_hash(
    messages: Sequence[BaseMessage],
    user_content_parts: Sequence[dict[str, Any]],
) -> str:
    payload = {
        "messages": [
            {"type": message.type, "content": message.content}
            for message in messages
        ],
        "user_content_parts": list(user_content_parts),
    }
    try:
        encoded = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ModelPolicyViolation("prompt content is not canonical JSON") from error
    return hashlib.sha256(encoded).hexdigest()


def _normalized_content_parts(
    parts: Sequence[dict[str, Any]],
) -> tuple[dict[str, Any], ...]:
    try:
        encoded = json.dumps(
            list(parts),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        decoded = json.loads(encoded)
    except (TypeError, ValueError) as error:
        raise ModelPolicyViolation("multimodal prompt parts are not canonical JSON") from error
    if not isinstance(decoded, list) or any(not isinstance(part, dict) for part in decoded):
        raise ModelPolicyViolation("multimodal prompt parts must be JSON objects")
    return tuple(decoded)


def _validated_visible_fields(
    fields: Sequence[VisibleFieldSpec],
) -> tuple[VisibleFieldSpec, ...]:
    normalized = tuple(fields)
    property_names: set[str] = set()
    public_fields: set[str] = set()
    for spec in normalized:
        if not isinstance(spec, VisibleFieldSpec):
            raise ModelPolicyViolation("visible field policy contains an invalid entry")
        if (
            not isinstance(spec.property_name, str)
            or not spec.property_name
            or not isinstance(spec.field, str)
            or not spec.field
            or spec.value_mode not in VISIBLE_FIELD_VALUE_MODES
        ):
            raise ModelPolicyViolation("visible field policy contains an invalid entry")
        if spec.property_name in property_names or spec.field in public_fields:
            raise ModelPolicyViolation("visible field policy contains duplicate entries")
        property_names.add(spec.property_name)
        public_fields.add(spec.field)
    return normalized


def _usage_value(
    usage: dict[str, int],
    primary_key: str,
    alias_key: str,
    *,
    default: int = 0,
) -> int:
    value = usage.get(primary_key, usage.get(alias_key, default))
    return _nonnegative_integer(value, f"token_usage.{primary_key}")


def _nonnegative_integer(value: object, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ModelPolicyViolation(f"provider returned invalid {field_name}")
    return value


def _positive_integer(value: object, field_name: str) -> int:
    result = _nonnegative_integer(value, field_name)
    if result == 0:
        raise ModelPolicyViolation(f"provider returned invalid {field_name}")
    return result


def _attempts_consumed(value: object, remaining: int) -> int:
    attempts = _positive_integer(value, "provider_attempts_used")
    if attempts > remaining:
        raise ModelPolicyViolation("model transport exceeded the provider attempt budget")
    return attempts


def _request_with_attempt_budget(
    request: ModelTransportRequest,
    remaining: int,
) -> ModelTransportRequest:
    return replace(
        request,
        governed_request=replace(
            request.governed_request,
            provider_attempts_remaining=remaining,
        ),
    )


def _next_stream_update(
    iterator: Iterator[ModelTransportStreamUpdate],
) -> tuple[bool, ModelTransportStreamUpdate | None]:
    try:
        return True, next(iterator)
    except StopIteration:
        return False, None
