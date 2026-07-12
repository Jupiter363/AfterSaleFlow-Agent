from __future__ import annotations

import base64
import binascii
import re
import time
from dataclasses import dataclass
from typing import Any, Protocol, TypeVar

import httpx
from pydantic import BaseModel, ValidationError


T = TypeVar("T", bound=BaseModel)

_ALLOWED_INLINE_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}
_INLINE_IMAGE_DATA_URL = re.compile(
    r"^data:(image/(?:jpeg|png|webp));base64,([A-Za-z0-9+/]*={0,2})$"
)
_MAX_INLINE_IMAGE_BYTES = 4 * 1024 * 1024
_MAX_INLINE_IMAGE_TOTAL_BYTES = 10 * 1024 * 1024
_MAX_INLINE_IMAGE_DATA_URL_LENGTH = 6 * 1024 * 1024


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
        user_content_parts: list[dict[str, Any]] | None = None,
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
        user_content_parts: list[dict[str, Any]] | None = None,
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
                        user_content_parts=user_content_parts,
                        json_mode=True,
                    )
                except httpx.HTTPStatusError as exception:
                    if not self._is_response_format_rejection(exception):
                        raise
                    payload = self._request_completion(
                        client,
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
                except (KeyError, IndexError, TypeError, ValidationError, ValueError):
                    if allow_json_extraction:
                        raise
                    payload = self._request_completion(
                        client,
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

    def _request_completion(
        self,
        client: httpx.Client,
        *,
        system_prompt: str,
        user_prompt: str,
        user_content_parts: list[dict[str, Any]] | None,
        json_mode: bool,
    ) -> dict[str, Any]:
        user_content: str | list[dict[str, Any]] = user_prompt
        if user_content_parts:
            user_content = [
                {"type": "text", "text": user_prompt},
                *self._validated_multimodal_parts(user_content_parts),
            ]
        request_body: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
            "temperature": 0,
        }
        if json_mode:
            request_body["response_format"] = {"type": "json_object"}
        response = client.post(
            self._chat_completions_url(),
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
    def _validated_multimodal_parts(
        parts: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
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

    def _chat_completions_url(self) -> str:
        if self._base_url.endswith("/v1"):
            return f"{self._base_url}/chat/completions"
        return f"{self._base_url}/v1/chat/completions"

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
