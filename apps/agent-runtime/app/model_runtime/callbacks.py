from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from threading import Lock
from typing import Any

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.messages import AIMessageChunk
from langchain_core.outputs import ChatGenerationChunk, LLMResult


GOVERNED_EVENTS_KEY = "governed_events"
GOVERNED_RESET_USAGE_KEY = "governed_generation_reset_usage"

GovernedVisibleDeltaSink = Callable[[str, str, str], None]
GovernedGenerationResetSink = Callable[[str, int, str], None]
_ACTIVE_GOVERNED_VISIBLE_DELTA_SINK: ContextVar[
    GovernedVisibleDeltaSink | None
] = ContextVar("active_governed_visible_delta_sink", default=None)
_ACTIVE_GOVERNED_GENERATION_RESET_SINK: ContextVar[
    GovernedGenerationResetSink | None
] = ContextVar("active_governed_generation_reset_sink", default=None)


@contextmanager
def bind_governed_visible_delta_sink(
    sink: GovernedVisibleDeltaSink,
) -> Iterator[None]:
    """Bind one execution-local consumer for already-governed live deltas."""

    token = _ACTIVE_GOVERNED_VISIBLE_DELTA_SINK.set(sink)
    try:
        yield
    finally:
        _ACTIVE_GOVERNED_VISIBLE_DELTA_SINK.reset(token)


@contextmanager
def bind_governed_generation_reset_sink(
    sink: GovernedGenerationResetSink,
) -> Iterator[None]:
    """Bind one execution-local consumer for an in-stream generation reset."""

    token = _ACTIVE_GOVERNED_GENERATION_RESET_SINK.set(sink)
    try:
        yield
    finally:
        _ACTIVE_GOVERNED_GENERATION_RESET_SINK.reset(token)


def publish_governed_visible_delta(
    *,
    node_name: str,
    field: str,
    delta: str,
) -> None:
    """Mirror a validated provider delta without changing its callback payload."""

    sink = _ACTIVE_GOVERNED_VISIBLE_DELTA_SINK.get()
    if sink is not None:
        sink(node_name, field, delta)


def publish_governed_generation_reset(
    *,
    node_name: str,
    generation: int,
    reason_code: str,
) -> None:
    sink = _ACTIVE_GOVERNED_GENERATION_RESET_SINK.get()
    if sink is not None:
        sink(node_name, generation, reason_code)


def visible_delta_event(*, node_name: str, field: str, delta: str) -> dict[str, str]:
    return {
        "schema_version": "governed-model-event.v1",
        "event_type": "visible_delta",
        "node_name": node_name,
        "field": field,
        "delta": delta,
    }


def generation_reset_event(
    *,
    node_name: str,
    generation: int,
    reason_code: str,
) -> dict[str, Any]:
    return {
        "schema_version": "governed-model-event.v1",
        "event_type": "generation_reset",
        "node_name": node_name,
        "generation": generation,
        "reason_code": reason_code,
    }


def generation_reset_usage(
    *, model: str, latency_ms: int, token_usage: dict[str, int]
) -> dict[str, Any]:
    if not model or latency_ms < 0:
        raise ValueError("governed generation reset usage is invalid")
    normalized: dict[str, int] = {}
    for key in ("input", "output", "total"):
        value = token_usage.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError("governed generation reset token usage is invalid")
        normalized[key] = value
    if normalized["total"] != normalized["input"] + normalized["output"]:
        raise ValueError("governed generation reset token usage drifted")
    return {
        "model": model,
        "latency_ms": latency_ms,
        "token_usage": normalized,
    }


def governed_reset_usage_from_chunk(chunk: AIMessageChunk) -> dict[str, Any] | None:
    raw = chunk.additional_kwargs.get(GOVERNED_RESET_USAGE_KEY)
    if raw is None:
        return None
    if not isinstance(raw, dict):
        raise ValueError("governed generation reset usage payload is invalid")
    model = raw.get("model")
    latency_ms = raw.get("latency_ms")
    token_usage = raw.get("token_usage")
    if (
        not isinstance(model, str)
        or not isinstance(latency_ms, int)
        or isinstance(latency_ms, bool)
        or not isinstance(token_usage, dict)
    ):
        raise ValueError("governed generation reset usage payload is invalid")
    return generation_reset_usage(
        model=model,
        latency_ms=latency_ms,
        token_usage=token_usage,
    )


def governed_events_from_chunk(chunk: AIMessageChunk) -> tuple[dict[str, Any], ...]:
    raw = chunk.additional_kwargs.get(GOVERNED_EVENTS_KEY, ())
    if not isinstance(raw, list):
        return ()
    events: list[dict[str, Any]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        event_type = item.get("event_type")
        if event_type == "visible_delta":
            required = ("schema_version", "event_type", "node_name", "field", "delta")
            if not all(isinstance(item.get(key), str) for key in required):
                continue
            events.append({key: item[key] for key in required})
            continue
        if event_type != "generation_reset":
            continue
        if (
            item.get("schema_version") != "governed-model-event.v1"
            or not isinstance(item.get("node_name"), str)
            or item.get("generation") != 2
            or item.get("reason_code") != "OUTPUT_SCHEMA_INVALID"
        ):
            continue
        events.append(
            {
                "schema_version": item["schema_version"],
                "event_type": event_type,
                "node_name": item["node_name"],
                "generation": item["generation"],
                "reason_code": item["reason_code"],
            }
        )
    return tuple(events)


class InvocationMetadataCapture(BaseCallbackHandler):
    """Capture bounded governed metadata without retaining prompt or model output."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._metadata: dict[str, Any] | None = None

    @property
    def metadata(self) -> dict[str, Any]:
        with self._lock:
            if self._metadata is None:
                raise RuntimeError("model invocation metadata is not available")
            return dict(self._metadata)

    def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        del kwargs
        if not response.generations or not response.generations[0]:
            return
        generation = response.generations[0][0]
        message = getattr(generation, "message", None)
        metadata = getattr(message, "response_metadata", None)
        if not isinstance(metadata, dict):
            return
        allowlist = {
            "provider",
            "model",
            "model_profile_id",
            "prompt_version",
            "prompt_hash",
            "output_schema_version",
            "policy_version",
            "guardrail_version",
            "traceparent",
            "temperature",
            "max_output_tokens",
            "tool_allowlist",
            "latency_ms",
            "token_usage",
            "transport_attempts",
            "repairs_used",
        }
        with self._lock:
            self._metadata = {key: metadata[key] for key in allowlist if key in metadata}


class GovernedEventCollector(BaseCallbackHandler):
    def __init__(self) -> None:
        self.events: list[dict[str, Any]] = []

    def on_llm_new_token(
        self,
        token: str | list[str | dict[str, Any]],
        *,
        chunk: ChatGenerationChunk | None = None,
        **kwargs: Any,
    ) -> None:
        del token, kwargs
        if chunk is not None and isinstance(chunk.message, AIMessageChunk):
            self.events.extend(governed_events_from_chunk(chunk.message))
