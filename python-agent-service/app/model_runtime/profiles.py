from __future__ import annotations

import hashlib
from datetime import datetime

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator


class ModelProfile(BaseModel):
    """Immutable provider settings resolved from the trusted server registry."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    profile_id: str = Field(min_length=1, max_length=128)
    provider: str = Field(min_length=1, max_length=64)
    model: str = Field(min_length=1, max_length=128)
    temperature: float = Field(default=0.0, ge=0.0, le=2.0)
    max_output_tokens: int = Field(default=8_192, ge=1, le=16_384)
    response_format: str = Field(default="STRICT_JSON_SCHEMA", pattern="^STRICT_JSON_SCHEMA$")
    tool_allowlist: tuple[str, ...] = Field(default=(), max_length=32)
    max_provider_attempts: int = Field(default=1, ge=1, le=2)
    retry_backoff_ms: int = Field(default=0, ge=0, le=1_000)

    @field_validator("tool_allowlist")
    @classmethod
    def require_unique_tools(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(set(value)) != len(value) or any(not item or len(item) > 128 for item in value):
            raise ValueError("tool allowlist entries must be non-empty and unique")
        return value


class ModelInvocationPolicy(BaseModel):
    """Command-bound settings that callers cannot replace through Runnable kwargs."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    invocation_id: str = Field(min_length=1, max_length=128)
    node_name: str = Field(min_length=1, max_length=128)
    deadline_at: AwareDatetime
    provider_attempts_remaining: int = Field(default=1, ge=0, le=2)
    repairs_remaining: int = Field(default=0, ge=0, le=1)
    prompt_version: str = Field(min_length=1, max_length=128)
    output_schema_version: str = Field(min_length=1, max_length=128)
    policy_version: str = Field(min_length=1, max_length=128)
    guardrail_version: str = Field(min_length=1, max_length=128)
    trusted_system_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    traceparent: str | None = Field(
        default=None,
        pattern=r"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
    )

    @field_validator("deadline_at")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.utcoffset() is None:
            raise ValueError("model deadline must be timezone-aware")
        return value


def system_prompt_sha256(system_prompt: str) -> str:
    return hashlib.sha256(system_prompt.encode("utf-8")).hexdigest()
