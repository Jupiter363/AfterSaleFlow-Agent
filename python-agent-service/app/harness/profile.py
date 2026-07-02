"""Versioned Agent identity, authority, and execution budgets."""

from __future__ import annotations

from typing import Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class LoopBudget(BaseModel):
    """Hard limits that keep an Agent run bounded and interruptible."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    max_iterations: int = Field(ge=1, le=64)
    max_tool_calls: int = Field(ge=0, le=128)
    max_model_calls: int = Field(ge=1, le=64)
    max_input_tokens: int = Field(ge=256, le=1_000_000)
    max_output_tokens: int = Field(ge=64, le=100_000)
    deadline_seconds: int = Field(ge=1, le=3_600)
    stagnation_threshold: int = Field(ge=1, le=16)
    max_output_repairs: int = Field(ge=0, le=4)

    @model_validator(mode="after")
    def validate_coherent_limits(self) -> Self:
        if self.max_output_tokens > self.max_input_tokens:
            raise ValueError("output token budget cannot exceed input token budget")
        if self.max_model_calls > self.max_iterations:
            raise ValueError("model calls cannot exceed loop iterations")
        return self


class AgentProfile(BaseModel):
    """The complete authority envelope for one Agent identity.

    Any capability not listed here is denied. Skills can narrow behavior but
    cannot grant a context scope or tool omitted from the profile.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    agent_id: str = Field(min_length=3, max_length=128)
    role: str = Field(min_length=3, max_length=128)
    version: str = Field(min_length=1, max_length=64)
    allowed_case_states: frozenset[str] = Field(default_factory=frozenset)
    allowed_context_scopes: frozenset[str] = Field(default_factory=frozenset)
    allowed_skills: frozenset[str] = Field(default_factory=frozenset)
    allowed_tools: frozenset[str] = Field(default_factory=frozenset)
    forbidden_actions: frozenset[str] = Field(default_factory=frozenset)
    budget: LoopBudget
    output_schema: str = Field(min_length=1, max_length=128)
    risk_policy: str = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def reject_authority_conflicts(self) -> Self:
        conflicts = self.allowed_tools & self.forbidden_actions
        if conflicts:
            names = ", ".join(sorted(conflicts))
            raise ValueError(f"forbidden actions cannot be allowed tools: {names}")
        return self

    def authorizes_case_state(self, case_state: str) -> bool:
        return case_state in self.allowed_case_states

    def authorizes_context(self, scope: str) -> bool:
        return scope in self.allowed_context_scopes

    def authorizes_skill(self, skill: str) -> bool:
        return skill in self.allowed_skills

    def authorizes_tool(self, tool: str) -> bool:
        return (
            tool in self.allowed_tools
            and tool not in self.forbidden_actions
        )

    def forbids(self, action: str) -> bool:
        return action in self.forbidden_actions
