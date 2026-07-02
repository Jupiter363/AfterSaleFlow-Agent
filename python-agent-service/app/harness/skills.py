"""Versioned Skill registry constrained by Agent Profile authority."""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from app.harness.profile import AgentProfile
from app.harness.tool_gateway import ToolAuthorizationError


class SkillDefinition(BaseModel):
    """A cognitive procedure that can require, but never grant, tools."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    code: str = Field(min_length=1, max_length=128)
    version: str = Field(min_length=1, max_length=64)
    allowed_agents: frozenset[str] = Field(min_length=1)
    required_tools: frozenset[str] = Field(default_factory=frozenset)
    input_schema: str = Field(min_length=1, max_length=128)
    output_schema: str = Field(min_length=1, max_length=128)


class SkillRegistry:
    def __init__(self) -> None:
        self._skills: dict[str, SkillDefinition] = {}

    def register(self, definition: SkillDefinition) -> None:
        if definition.code in self._skills:
            raise ValueError(f"skill is already registered: {definition.code}")
        self._skills[definition.code] = definition

    def resolve(
        self, profile: AgentProfile, skill_code: str
    ) -> SkillDefinition:
        if not profile.authorizes_skill(skill_code):
            raise ToolAuthorizationError(
                f"{profile.agent_id} cannot use skill {skill_code}"
            )
        definition = self._skills.get(skill_code)
        if definition is None:
            raise ToolAuthorizationError(
                f"skill is not registered: {skill_code}"
            )
        if profile.agent_id not in definition.allowed_agents:
            raise ToolAuthorizationError(
                f"skill {skill_code} does not allow {profile.agent_id}"
            )
        missing = {
            tool
            for tool in definition.required_tools
            if not profile.authorizes_tool(tool)
        }
        if missing:
            raise ToolAuthorizationError(
                f"skill {skill_code} requires unauthorized tools: "
                f"{sorted(missing)}"
            )
        return definition
