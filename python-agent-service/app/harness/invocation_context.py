"""Trusted server-owned invocation context for stateless agent turns."""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator


Identifier = Annotated[str, StringConstraints(min_length=3, max_length=128)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


PermissionLevel = Literal[
    "PARTY_USER",
    "PARTY_MERCHANT",
    "SERVICE_ASSIST",
    "REVIEWER_ALL",
    "ADMIN_ALL",
    "SYSTEM_ALL",
]

RoomType = Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]

ScopeType = Literal[
    "INTAKE_INITIATOR_PRIVATE",
    "EVIDENCE_PARTY_PRIVATE",
    "ROOM_SHARED",
    "SYSTEM",
]


class AgentInvocationContext(StrictModel):
    """Server-owned access/session envelope supplied by Java for one LLM turn."""

    tenant_id: Identifier = "default"
    case_id: Identifier
    room_type: RoomType
    actor_id: Identifier
    actor_role: Identifier
    access_session_id: Identifier
    permission_level: PermissionLevel
    permission_scopes: list[Identifier] = Field(default_factory=list)
    agent_key: Identifier
    agent_invocation_id: Identifier
    agent_session_id: Identifier
    conversation_scope: str = Field(min_length=10, max_length=512)
    scope_type: ScopeType
    allowed_actor_ids: list[Identifier] = Field(default_factory=list)
    allowed_actor_roles: list[Identifier] = Field(default_factory=list)
    prompt_profile_id: Identifier
    memory_policy_id: Identifier

    @field_validator(
        "tenant_id",
        "case_id",
        "room_type",
        "actor_id",
        "actor_role",
        "access_session_id",
        "agent_key",
        "agent_invocation_id",
        "agent_session_id",
        "conversation_scope",
        "scope_type",
        "prompt_profile_id",
        "memory_policy_id",
    )
    @classmethod
    def reject_blank_scalar(cls, value: str, info) -> str:
        if not value.strip():
            raise ValueError(f"{info.field_name} must not be blank")
        return value

    @field_validator("permission_scopes", "allowed_actor_ids", "allowed_actor_roles")
    @classmethod
    def reject_blank_list_items(cls, values: list[str], info) -> list[str]:
        for index, value in enumerate(values):
            if not value.strip():
                raise ValueError(f"{info.field_name}[{index}] must not be blank")
        return values
