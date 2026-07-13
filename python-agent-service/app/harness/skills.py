# 文件作用：注册可版本化的 Agent 认知 Skill，并在解析时同时校验 Profile、Skill 角色白名单及其依赖工具权限。

"""受 AgentProfile 权限约束的可版本化 Skill 注册表。"""

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
    # 所属模块：Agent Harness > Skill 治理 > 注册表初始化。
    # 具体功能：`__init__` 创建以唯一 skill code 为键的进程内定义表；实际执行状态不保存在注册表里。
    # 上下游：上游是服务启动时的 Skill 装配；下游是 `register` 建目录、`resolve` 按 Agent 权限查询。
    # 系统意义：Skill 只是受控认知流程合同，不是自动获得工具权限的插件；运行权限始终来自 AgentProfile。
    def __init__(self) -> None:
        self._skills: dict[str, SkillDefinition] = {}

    # 所属模块：Agent Harness > Skill 治理 > 唯一定义注册。
    # 具体功能：`register` 按 definition.code 保存 Skill；重复 code 直接报错，不能用后加载项静默覆盖旧版本。
    # 上下游：上游是启动配置提供的 SkillDefinition；下游是后续 `resolve(profile, code)`。
    # 系统意义：避免依赖加载顺序决定实际 Skill 实现，保证审计中的 code/version 能唯一对应输入/输出与工具依赖。
    def register(self, definition: SkillDefinition) -> None:
        if definition.code in self._skills:
            raise ValueError(f"skill is already registered: {definition.code}")
        self._skills[definition.code] = definition

    # 所属模块：Agent Harness > Skill 治理 > 三层授权解析。
    # 具体功能：`resolve` 依次要求 Profile 允许 skill code、注册表存在定义、definition 允许该 agent_id，并确认每个 required_tool 都被 Profile 授权。
    # 上下游：上游是工作流按角色选择 Skill；下游只有全部检查通过才返回 SkillDefinition，之后才可执行具体流程。
    # 系统意义：Skill 声明需要某工具并不能反向授予该工具；Profile 与 Skill 双白名单防止共享 Skill 被其他 Agent 越权复用。
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
