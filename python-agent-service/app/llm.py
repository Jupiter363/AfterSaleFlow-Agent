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
                allow_json_extraction = False
                try:
                    payload = self._request_completion(
                        client,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
                        json_mode=True,
                    )
                except httpx.HTTPStatusError as exception:
                    if not self._is_response_format_rejection(exception):
                        raise
                    payload = self._request_completion(
                        client,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
                        json_mode=False,
                    )
                    allow_json_extraction = True
                try:
                    value = self._parse_structured_payload(
                        payload,
                        output_type,
                        allow_json_extraction=allow_json_extraction,
                    )
                except (KeyError, IndexError, TypeError, ValidationError, ValueError):
                    if allow_json_extraction:
                        raise
                    payload = self._request_completion(
                        client,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
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

    def _request_completion(
        self,
        client: httpx.Client,
        *,
        system_prompt: str,
        user_prompt: str,
        json_mode: bool,
    ) -> dict[str, Any]:
        request_body: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0,
        }
        if json_mode:
            request_body["response_format"] = {"type": "json_object"}
        response = client.post(
            f"{self._base_url}/v1/chat/completions",
            headers={"Authorization": f"Bearer {self._api_key}"},
            json=request_body,
        )
        response.raise_for_status()
        try:
            payload: dict[str, Any] = response.json()
        except ValueError as exception:
            raise AgentServiceUnavailable("LiteLLM proxy returned invalid JSON") from exception
        return payload

    @staticmethod
    def _parse_structured_payload(
        payload: dict[str, Any],
        output_type: type[T],
        *,
        allow_json_extraction: bool,
    ) -> T:
        message = payload["choices"][0]["message"]
        content = str(message.get("content") or message.get("reasoning_content") or "")
        if allow_json_extraction:
            content = LiteLlmProxyClient._extract_json_object(content)
        return output_type.model_validate_json(content)

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
