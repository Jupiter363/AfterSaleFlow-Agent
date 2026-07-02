"""Redacted Agent Run events for trace, audit, cost, and evaluation."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class AgentRunEvent(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    trace_id: str
    case_id: str
    workflow_id: str
    run_id: str
    agent_id: str
    profile_version: str
    prompt_version: str
    skill_version: str
    ruleset_version: str
    event_type: str
    occurred_at: datetime
    metadata: dict[str, Any] = Field(default_factory=dict)


class InMemoryRunObserver:
    """Test/local observer; production adapters can persist the same event."""

    _sensitive_markers = (
        "api_key",
        "secret",
        "password",
        "token",
        "credential",
    )

    def __init__(self) -> None:
        self.events: list[AgentRunEvent] = []

    def record(self, event: AgentRunEvent) -> None:
        redacted = {
            key: (
                "[REDACTED]"
                if any(marker in key.lower() for marker in self._sensitive_markers)
                else value
            )
            for key, value in event.metadata.items()
        }
        self.events.append(event.model_copy(update={"metadata": redacted}))
