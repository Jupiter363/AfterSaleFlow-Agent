"""Read-only execution tool declarations for Agent planning.

The Python harness may present these declarations as allowed execution
intentions, but it must not execute business tools. Java remains the authority
for approval validation, idempotency, audit records, and simulated/real adapter
selection.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.harness.context_window import PromptSection


class ExecutionToolDeclaration(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    action_type: str = Field(min_length=1, max_length=128)
    tool_name: str = Field(min_length=1, max_length=128)
    operation: str = Field(min_length=1, max_length=128)
    display_name: str = Field(min_length=1, max_length=128)
    description: str = Field(min_length=1, max_length=2_000)
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    simulated: bool
    requires_approved_plan: bool


class _JavaCatalogResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    success: bool
    data: list[ExecutionToolDeclaration]


class JavaExecutionToolCatalogClient:
    """Fetches Java-owned execution tool declarations without executing them."""

    def __init__(
        self,
        *,
        base_url: str,
        service_identity: str = "python-agent-service",
        timeout_seconds: float = 10.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._service_identity = service_identity
        self._timeout_seconds = timeout_seconds
        self._transport = transport

    def fetch(self) -> list[ExecutionToolDeclaration]:
        with httpx.Client(
            base_url=self._base_url,
            timeout=self._timeout_seconds,
            transport=self._transport,
        ) as client:
            response = client.get(
                "/internal/tools/execution",
                headers={"X-Service-Identity": self._service_identity},
            )
            response.raise_for_status()
        payload = _JavaCatalogResponse.model_validate(response.json())
        if not payload.success:
            raise RuntimeError("java execution tool catalog returned failure")
        return list(payload.data)


def build_execution_tool_intention_section(
    declarations: Iterable[ExecutionToolDeclaration],
) -> PromptSection:
    """Render Java tool declarations as proposal-only Agent context."""

    tools = [
        {
            "action_type": declaration.action_type,
            "display_name": declaration.display_name,
            "description": declaration.description,
            "risk_level": declaration.risk_level,
            "simulated": declaration.simulated,
            "requires_approved_plan": declaration.requires_approved_plan,
        }
        for declaration in sorted(declarations, key=lambda item: item.action_type)
    ]
    content = {
        "allowed_use": "ONLY_PROPOSE_EXECUTION_INTENT",
        "governance_note": (
            "这些工具只能作为裁决草案或执行计划的动作意图提出，"
            "不得直接执行；最终执行必须回到 Java 审核、幂等、审计和 ToolRegistry 链路。"
        ),
        "tools": tools,
    }
    return PromptSection(
        name="execution_tool_intentions",
        content=json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        priority=60,
        required=False,
        trust_level="java_tool_catalog",
        prompt_included=True,
    )
