from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Protocol, TypeVar

import httpx
from pydantic import BaseModel, ValidationError


T = TypeVar("T", bound=BaseModel)


class AgentOutputSchemaError(RuntimeError):
    def __init__(self, node_name: str, message: str) -> None:
        super().__init__(message)
        self.node_name = node_name


class AgentServiceUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class StructuredGeneration:
    value: BaseModel
    model: str
    latency_ms: int
    token_usage: dict[str, int]


class StructuredLlmClient(Protocol):
    def generate(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[T],
    ) -> StructuredGeneration: ...


class LiteLlmProxyClient:
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

    def generate(
        self,
        *,
        node_name: str,
        system_prompt: str,
        user_prompt: str,
        output_type: type[T],
    ) -> StructuredGeneration:
        started = time.perf_counter()
        try:
            with httpx.Client(
                timeout=self._timeout, transport=self._transport
            ) as client:
                response = client.post(
                    f"{self._base_url}/v1/chat/completions",
                    headers={"Authorization": f"Bearer {self._api_key}"},
                    json={
                        "model": self._model,
                        "messages": [
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_prompt},
                        ],
                        "response_format": {"type": "json_object"},
                        "temperature": 0,
                    },
                )
                response.raise_for_status()
                payload: dict[str, Any] = response.json()
        except (httpx.HTTPError, ValueError) as exception:
            raise AgentServiceUnavailable("LiteLLM proxy request failed") from exception
        latency_ms = int((time.perf_counter() - started) * 1000)
        try:
            content = payload["choices"][0]["message"]["content"]
            value = output_type.model_validate_json(content)
        except (KeyError, IndexError, TypeError, ValidationError, ValueError) as exception:
            raise AgentOutputSchemaError(
                node_name, f"{node_name} returned invalid structured output"
            ) from exception
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
