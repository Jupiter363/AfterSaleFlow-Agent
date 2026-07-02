"""Schema-validated, audited gateway for all Agent tool calls."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field

from app.harness.profile import AgentProfile


class ToolAuthorizationError(PermissionError):
    """Raised when an Agent, state, or requested field exceeds authority."""


class ToolExecutionError(RuntimeError):
    """Raised when a registered tool fails after authorization."""


class ToolRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    tool_name: str = Field(min_length=1, max_length=128)
    case_id: str = Field(min_length=3, max_length=128)
    case_state: str = Field(min_length=1, max_length=64)
    agent_run_id: str = Field(min_length=3, max_length=128)
    arguments: dict[str, Any]
    reason: str = Field(min_length=3, max_length=2_000)
    requested_fields: tuple[str, ...] = ()


class ToolResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    status: str
    data: dict[str, Any]
    source_refs: tuple[str, ...] = ()
    redactions: tuple[str, ...] = ()
    audit_id: str


@dataclass(frozen=True)
class ToolDefinition:
    """A registered tool contract; handlers remain invisible to Agent text."""

    name: str
    version: str
    input_model: type[BaseModel]
    allowed_case_states: frozenset[str] | set[str]
    output_fields: frozenset[str] | set[str]
    handler: Callable[[BaseModel], dict[str, Any]]
    sensitive_output_fields: frozenset[str] | set[str] = field(
        default_factory=frozenset
    )


class ToolGateway:
    """The only runtime path from an Agent to deterministic capabilities."""

    def __init__(
        self, audit_sink: Callable[[dict[str, Any]], None] | None = None
    ) -> None:
        self._definitions: dict[str, ToolDefinition] = {}
        self._audit_sink = audit_sink or (lambda _: None)

    def register(self, definition: ToolDefinition) -> None:
        if definition.name in self._definitions:
            raise ValueError(f"tool is already registered: {definition.name}")
        self._definitions[definition.name] = definition

    def execute(
        self, profile: AgentProfile, request: ToolRequest
    ) -> ToolResult:
        if not profile.authorizes_tool(request.tool_name):
            raise ToolAuthorizationError(
                f"{profile.agent_id} cannot call {request.tool_name}"
            )
        definition = self._definitions.get(request.tool_name)
        if definition is None:
            raise ToolAuthorizationError(
                f"tool is not registered: {request.tool_name}"
            )
        if request.case_state not in definition.allowed_case_states:
            raise ToolAuthorizationError(
                f"{request.tool_name} is not allowed in {request.case_state}"
            )
        requested = set(request.requested_fields)
        allowed_fields = set(definition.output_fields)
        if not requested.issubset(allowed_fields):
            denied = sorted(requested - allowed_fields)
            raise ToolAuthorizationError(
                f"requested fields are not allowed: {denied}"
            )

        validated = definition.input_model.model_validate(request.arguments)
        try:
            raw = definition.handler(validated)
        except Exception as exception:
            raise ToolExecutionError(
                f"tool failed: {request.tool_name}"
            ) from exception

        sensitive = set(definition.sensitive_output_fields)
        visible_fields = requested or allowed_fields
        data = {
            key: value
            for key, value in raw.items()
            if key in visible_fields and key not in sensitive
        }
        redactions = tuple(sorted(key for key in raw if key in sensitive))
        audit_id = "TOOL-AUDIT-" + uuid4().hex
        self._audit_sink(
            {
                "audit_id": audit_id,
                "agent_id": profile.agent_id,
                "profile_version": profile.version,
                "agent_run_id": request.agent_run_id,
                "case_id": request.case_id,
                "case_state": request.case_state,
                "tool_name": definition.name,
                "tool_version": definition.version,
                "reason": request.reason,
                "requested_fields": sorted(requested),
                "redactions": list(redactions),
                "status": "SUCCESS",
            }
        )
        return ToolResult(
            status="SUCCESS",
            data=data,
            source_refs=tuple(raw.get("source_refs", ())),
            redactions=redactions,
            audit_id=audit_id,
        )
