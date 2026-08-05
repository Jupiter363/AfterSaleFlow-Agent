from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from dataclasses import dataclass
from typing import Any, Protocol, cast

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage
from pydantic import BaseModel

from app.llm import (
    AgentOutputSchemaError,
    AgentProviderContractError,
    AgentServiceUnavailable,
    GovernedProviderRequest,
    StructuredGeneration,
    StructuredLlmClient,
    StructuredStreamCompleted,
    StructuredStreamDelta,
)
from app.streaming import IncrementalVisibleJsonProjector, VisibleFieldSpec


_BUFFERED_VALIDATED_STREAM_NODE = "intake_turn_case_detail"


class ModelTransportError(RuntimeError):
    pass


class TransientModelTransportError(ModelTransportError):
    def __init__(self, message: str, *, provider_attempts_used: int = 1) -> None:
        super().__init__(message)
        self.provider_attempts_used = provider_attempts_used


class ModelTransportOutputError(ModelTransportError):
    def __init__(
        self,
        message: str,
        *,
        safe_code: str | None = None,
        node_name: str | None = None,
    ) -> None:
        super().__init__(message)
        self.safe_code = safe_code
        self.node_name = node_name


class NativeAsyncTransportRequired(ModelTransportError):
    pass


@dataclass(frozen=True, slots=True)
class ModelTransportRequest:
    node_name: str
    messages: tuple[BaseMessage, ...]
    output_type: type[BaseModel]
    governed_request: GovernedProviderRequest
    visible_fields: tuple[VisibleFieldSpec, ...] = ()
    user_content_parts: tuple[dict[str, Any], ...] = ()


@dataclass(frozen=True, slots=True)
class ModelTransportResult:
    json_document: str
    model: str
    latency_ms: int
    token_usage: dict[str, int]
    provider_attempts_used: int = 1
    repairs_used: int = 0


@dataclass(frozen=True, slots=True)
class ModelTransportVisibleDelta:
    field: str
    delta: str


@dataclass(frozen=True, slots=True)
class ModelTransportCompleted:
    result: ModelTransportResult


ModelTransportStreamUpdate = ModelTransportVisibleDelta | ModelTransportCompleted


class ModelTransport(Protocol):
    def generate(self, request: ModelTransportRequest) -> ModelTransportResult: ...

    async def agenerate(self, request: ModelTransportRequest) -> ModelTransportResult: ...

    def stream(
        self, request: ModelTransportRequest
    ) -> Iterator[ModelTransportStreamUpdate]: ...

    def astream(
        self, request: ModelTransportRequest
    ) -> AsyncIterator[ModelTransportStreamUpdate]: ...


class NativeAsyncStructuredLlmClient(Protocol):
    async def agenerate(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[BaseModel],
        user_content_parts: list[dict[str, Any]] | None = None,
        governed_request: GovernedProviderRequest | None = None,
    ) -> StructuredGeneration: ...

    def agenerate_stream(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[BaseModel],
        visible_fields: tuple[VisibleFieldSpec, ...] = (),
        user_content_parts: list[dict[str, Any]] | None = None,
        governed_request: GovernedProviderRequest | None = None,
    ) -> AsyncIterator[StructuredStreamDelta | StructuredStreamCompleted]: ...


class StructuredClientTransport:
    """Compatibility boundary around the existing structured client protocol."""

    def __init__(self, client: StructuredLlmClient) -> None:
        self._client = client

    @property
    def supports_absolute_deadline(self) -> bool:
        return bool(getattr(self._client, "supports_governed_provider_request", False))

    def generate(self, request: ModelTransportRequest) -> ModelTransportResult:
        self._validate_request_binding(request)
        system_prompt, user_prompt = _prompt_pair(request.messages)
        generation_args = _generation_args(
            request,
            system_prompt,
            user_prompt,
            client=self._client,
        )
        try:
            generation = self._client.generate(**generation_args)
        except AgentProviderContractError as error:
            raise ModelTransportOutputError(
                "structured provider contract invalid",
                safe_code="AGENT_PROVIDER_CONTRACT_INVALID",
                node_name=request.node_name,
            ) from error
        except AgentServiceUnavailable as error:
            raise TransientModelTransportError(
                "structured provider unavailable",
                provider_attempts_used=error.provider_attempts_used,
            ) from error
        except AgentOutputSchemaError as error:
            raise ModelTransportOutputError(
                "structured provider output invalid",
                safe_code=error.safe_code,
                node_name=error.node_name,
            ) from error
        return _transport_result(generation, node_name=request.node_name)

    async def agenerate(self, request: ModelTransportRequest) -> ModelTransportResult:
        self._validate_request_binding(request)
        client = _native_async_client(self._client)
        system_prompt, user_prompt = _prompt_pair(request.messages)
        generation_args = _generation_args(
            request,
            system_prompt,
            user_prompt,
            client=self._client,
        )
        try:
            generation = await client.agenerate(**generation_args)
        except AgentProviderContractError as error:
            raise ModelTransportOutputError(
                "structured provider contract invalid",
                safe_code="AGENT_PROVIDER_CONTRACT_INVALID",
                node_name=request.node_name,
            ) from error
        except AgentServiceUnavailable as error:
            raise TransientModelTransportError(
                "structured provider unavailable",
                provider_attempts_used=error.provider_attempts_used,
            ) from error
        except AgentOutputSchemaError as error:
            raise ModelTransportOutputError(
                "structured provider output invalid",
                safe_code=error.safe_code,
                node_name=error.node_name,
            ) from error
        return _transport_result(generation, node_name=request.node_name)

    def stream(self, request: ModelTransportRequest) -> Iterator[ModelTransportStreamUpdate]:
        self._validate_request_binding(request)
        if request.node_name == _BUFFERED_VALIDATED_STREAM_NODE:
            yield from _buffered_stream_updates(
                self.generate(request),
                request.visible_fields,
            )
            return
        system_prompt, user_prompt = _prompt_pair(request.messages)
        completed = False
        generation_args = _generation_args(
            request,
            system_prompt,
            user_prompt,
            client=self._client,
        )
        generation_args["visible_fields"] = request.visible_fields
        try:
            updates = self._client.generate_stream(**generation_args)
            for update in updates:
                if completed:
                    raise ModelTransportOutputError("stream emitted data after completion")
                if isinstance(update, StructuredStreamDelta):
                    yield ModelTransportVisibleDelta(field=update.field, delta=update.delta)
                    continue
                if not isinstance(update, StructuredStreamCompleted):
                    raise ModelTransportOutputError("stream emitted an unknown update")
                completed = True
                yield ModelTransportCompleted(
                    result=_transport_result(
                        update.generation,
                        node_name=request.node_name,
                    )
                )
        except AgentProviderContractError as error:
            raise ModelTransportOutputError(
                "structured provider stream contract invalid",
                safe_code="AGENT_PROVIDER_CONTRACT_INVALID",
                node_name=request.node_name,
            ) from error
        except AgentServiceUnavailable as error:
            raise TransientModelTransportError(
                "structured provider stream unavailable",
                provider_attempts_used=error.provider_attempts_used,
            ) from error
        except AgentOutputSchemaError as error:
            raise ModelTransportOutputError(
                "structured provider stream output invalid",
                safe_code=error.safe_code,
                node_name=error.node_name,
            ) from error
        if not completed:
            raise TransientModelTransportError("structured provider stream ended without result")

    async def astream(
        self, request: ModelTransportRequest
    ) -> AsyncIterator[ModelTransportStreamUpdate]:
        self._validate_request_binding(request)
        if request.node_name == _BUFFERED_VALIDATED_STREAM_NODE:
            result = await self.agenerate(request)
            for update in _buffered_stream_updates(result, request.visible_fields):
                yield update
            return
        client = _native_async_client(self._client)
        system_prompt, user_prompt = _prompt_pair(request.messages)
        completed = False
        generation_args = _generation_args(
            request,
            system_prompt,
            user_prompt,
            client=self._client,
        )
        generation_args["visible_fields"] = request.visible_fields
        try:
            updates = client.agenerate_stream(**generation_args)
            async for update in updates:
                if completed:
                    raise ModelTransportOutputError("stream emitted data after completion")
                if isinstance(update, StructuredStreamDelta):
                    yield ModelTransportVisibleDelta(field=update.field, delta=update.delta)
                    continue
                if not isinstance(update, StructuredStreamCompleted):
                    raise ModelTransportOutputError("stream emitted an unknown update")
                completed = True
                yield ModelTransportCompleted(
                    result=_transport_result(
                        update.generation,
                        node_name=request.node_name,
                    )
                )
        except AgentProviderContractError as error:
            raise ModelTransportOutputError(
                "structured provider stream contract invalid",
                safe_code="AGENT_PROVIDER_CONTRACT_INVALID",
                node_name=request.node_name,
            ) from error
        except AgentServiceUnavailable as error:
            raise TransientModelTransportError(
                "structured provider stream unavailable",
                provider_attempts_used=error.provider_attempts_used,
            ) from error
        except AgentOutputSchemaError as error:
            raise ModelTransportOutputError(
                "structured provider stream output invalid",
                safe_code=error.safe_code,
                node_name=error.node_name,
            ) from error
        if not completed:
            raise TransientModelTransportError("structured provider stream ended without result")

    def _validate_request_binding(self, request: ModelTransportRequest) -> None:
        if not bool(getattr(self._client, "supports_governed_provider_request", False)):
            return
        provider = getattr(self._client, "governed_provider", None)
        if provider != request.governed_request.provider:
            raise ModelTransportError("governed provider conflicts with the transport binding")


def _buffered_stream_updates(
    result: ModelTransportResult,
    visible_fields: tuple[VisibleFieldSpec, ...],
) -> Iterator[ModelTransportStreamUpdate]:
    projector = IncrementalVisibleJsonProjector(visible_fields)
    for field, delta in projector.feed(result.json_document):
        yield ModelTransportVisibleDelta(field=field, delta=delta)
    yield ModelTransportCompleted(result=result)


def _native_async_client(client: StructuredLlmClient) -> NativeAsyncStructuredLlmClient:
    if not callable(getattr(client, "agenerate", None)) or not callable(
        getattr(client, "agenerate_stream", None)
    ):
        raise NativeAsyncTransportRequired(
            "production async model execution requires native async client methods"
        )
    return cast(NativeAsyncStructuredLlmClient, client)


def _prompt_pair(messages: tuple[BaseMessage, ...]) -> tuple[str, str]:
    if len(messages) != 2 or not isinstance(messages[0], SystemMessage) or not isinstance(
        messages[1], HumanMessage
    ):
        raise ModelTransportError("structured client adapter requires system then human messages")
    if not isinstance(messages[0].content, str) or not isinstance(messages[1].content, str):
        raise ModelTransportError("structured client adapter requires text messages")
    return messages[0].content, messages[1].content


def _generation_args(
    request: ModelTransportRequest,
    system_prompt: str,
    user_prompt: str,
    *,
    client: StructuredLlmClient,
) -> dict[str, Any]:
    arguments: dict[str, Any] = {
        "node_name": request.node_name,
        "system_prompt": system_prompt,
        "user_prompt": user_prompt,
        "output_type": request.output_type,
    }
    if request.user_content_parts:
        arguments["user_content_parts"] = [
            dict(part) for part in request.user_content_parts
        ]
    if bool(getattr(client, "supports_governed_provider_request", False)):
        arguments["governed_request"] = request.governed_request
    return arguments


def _transport_result(
    generation: StructuredGeneration,
    *,
    node_name: str,
) -> ModelTransportResult:
    try:
        return _unclassified_transport_result(generation)
    except ModelTransportOutputError as error:
        raise ModelTransportOutputError(
            "structured provider contract invalid",
            safe_code="AGENT_PROVIDER_CONTRACT_INVALID",
            node_name=node_name,
        ) from error


def _unclassified_transport_result(
    generation: StructuredGeneration,
) -> ModelTransportResult:
    if not isinstance(generation.model, str) or not generation.model:
        raise ModelTransportOutputError("structured provider returned an invalid model")
    latency_ms = _nonnegative_integer(generation.latency_ms, "latency_ms")
    provider_attempts_used = _positive_integer(
        generation.provider_attempts_used,
        "provider_attempts_used",
    )
    repairs_used = _nonnegative_integer(generation.repairs_used, "repairs_used")
    if not isinstance(generation.token_usage, dict):
        raise ModelTransportOutputError("structured provider returned invalid token usage")
    if set(generation.token_usage) != {"input", "output", "total"}:
        raise ModelTransportOutputError("structured provider returned ambiguous token usage")
    token_usage = {
        key: _nonnegative_integer(value, f"token_usage.{key}")
        for key, value in generation.token_usage.items()
    }
    if token_usage["total"] != token_usage["input"] + token_usage["output"]:
        raise ModelTransportOutputError("structured provider returned inconsistent token usage")
    return ModelTransportResult(
        json_document=generation.value.model_dump_json(),
        model=generation.model,
        latency_ms=latency_ms,
        token_usage=token_usage,
        provider_attempts_used=provider_attempts_used,
        repairs_used=repairs_used,
    )


def _nonnegative_integer(value: object, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ModelTransportOutputError(
            f"structured provider returned invalid {field_name}"
        )
    return value


def _positive_integer(value: object, field_name: str) -> int:
    result = _nonnegative_integer(value, field_name)
    if result == 0:
        raise ModelTransportOutputError(
            f"structured provider returned invalid {field_name}"
        )
    return result
