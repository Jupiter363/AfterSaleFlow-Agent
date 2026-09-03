# 文件作用：定义每种 Agent 身份的案件阶段、上下文、技能、工具权限及硬预算；未显式授权的能力一律拒绝。

"""可版本化的 Agent 身份、权限包络与执行预算。"""

from __future__ import annotations

from typing import Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class LoopBudget(BaseModel):
    """限制迭代、模型/工具调用、Token、时长和修复次数的硬预算。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    max_iterations: int = Field(ge=1, le=64)
    max_tool_calls: int = Field(ge=0, le=128)
    max_model_calls: int = Field(ge=1, le=64)
    max_input_tokens: int = Field(ge=256, le=1_000_000)
    max_output_tokens: int = Field(ge=64, le=100_000)
    deadline_seconds: int = Field(ge=1, le=3_600)
    stagnation_threshold: int = Field(ge=1, le=16)
    max_output_repairs: int = Field(ge=0, le=4)

    # 所属模块：Agent Harness > AgentProfile > 循环预算一致性校验。
    # 具体功能：`validate_coherent_limits` 在所有字段完成解析后检查跨字段关系：输出 Token 不得大于输入预算，模型调用次数不得大于总迭代次数。
    # 上下游：上游是配置文件/配置中心构造 `LoopBudget`；下游是 `ControlledAgentLoop` 读取已验证的硬上限。
    # 系统意义：字段各自合法不代表组合合理；启动时拒绝矛盾预算，避免运行中出现永远达不到或无法解释的停止条件。
    @model_validator(mode="after")
    def validate_coherent_limits(self) -> Self:
        if self.max_output_tokens > self.max_input_tokens:
            raise ValueError("output token budget cannot exceed input token budget")
        if self.max_model_calls > self.max_iterations:
            raise ValueError("model calls cannot exceed loop iterations")
        return self


class AgentProfile(BaseModel):
    """一个 Agent 身份的完整权限包络。

    未列出的能力默认拒绝。Skill 只能收窄行为，不能授予 Profile 中没有的上下文域或工具。
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

    # 所属模块：Agent Harness > AgentProfile > 权限冲突校验。
    # 具体功能：`reject_authority_conflicts` 计算 allowed_tools 与 forbidden_actions 的交集；同一动作若同时允许和禁止就拒绝整个 Profile。
    # 上下游：上游是 Agent 配置加载；下游是上下文、Skill 与 ToolGateway 的所有授权查询。
    # 系统意义：不把冲突交给运行时猜优先级，确保相同版本 Profile 在任何服务实例上都有唯一、可审计的权限含义。
    @model_validator(mode="after")
    def reject_authority_conflicts(self) -> Self:
        conflicts = self.allowed_tools & self.forbidden_actions
        if conflicts:
            names = ", ".join(sorted(conflicts))
            raise ValueError(f"forbidden actions cannot be allowed tools: {names}")
        return self

    # 所属模块：Agent Harness > AgentProfile > 案件阶段授权查询。
    # 具体功能：`authorizes_case_state` 仅在当前案件状态被显式列入 allowed_case_states 时返回 True。
    # 上下游：上游是 `ContextAssembler` 或工作流启动前的阶段检查；下游决定该 Agent 能否在接待、证据、庭审或审核状态运行。
    # 系统意义：防止证据书记官等角色越过流程阶段提前参与裁判或在结案后继续修改状态。
    def authorizes_case_state(self, case_state: str) -> bool:
        return case_state in self.allowed_case_states

    # 所属模块：Agent Harness > AgentProfile > 数据域授权查询。
    # 具体功能：`authorizes_context` 判断某个 ContextFragment.access_scope 是否属于 Agent 的上下文白名单。
    # 上下游：上游是 `ContextAssembler.assemble` 对每个来源片段逐项检查；下游决定私聊、共享房间、系统审计等数据能否进入该 Agent 上下文。
    # 系统意义：角色能处理同一案件不等于能看见同一数据；该查询落实参与方与会话隔离。
    def authorizes_context(self, scope: str) -> bool:
        return scope in self.allowed_context_scopes

    # 所属模块：Agent Harness > AgentProfile > Skill 授权查询。
    # 具体功能：`authorizes_skill` 判断认知流程 code 是否被当前 AgentProfile 显式允许。
    # 上下游：上游是 `SkillRegistry.resolve`；下游是具体 Skill 定义及其输入/输出合同。
    # 系统意义：注册了 Skill 不代表所有 Agent 都能使用，避免通过共享注册表横向扩大角色职责。
    def authorizes_skill(self, skill: str) -> bool:
        return skill in self.allowed_skills

    # 所属模块：Agent Harness > AgentProfile > Tool 授权查询。
    # 具体功能：`authorizes_tool` 同时要求工具位于 allowed_tools 且不在 forbidden_actions；返回值供 Skill 解析和 ToolGateway 执行前复用。
    # 上下游：上游是 `SkillRegistry.resolve`、`ToolGateway.execute`；下游才可能进入工具输入校验和确定性 handler。
    # 系统意义：工具执行是模型影响外部状态的高风险边界，必须采用显式白名单并让禁止项具有最终否决权。
    def authorizes_tool(self, tool: str) -> bool:
        return (
            tool in self.allowed_tools
            and tool not in self.forbidden_actions
        )

    # 所属模块：Agent Harness > AgentProfile > 显式禁令查询。
    # 具体功能：`forbids` 供护栏或编排器判断某动作是否被角色规则明确禁止，不做模糊字符串推理。
    # 上下游：上游是确定性策略代码；下游是拒绝执行、转人工或记录权限异常。
    # 系统意义：把“不得裁决/不得执行”等职责边界编码为机器可判断集合，而不是只依赖 Prompt 提醒模型自律。
    def forbids(self, action: str) -> bool:
        return action in self.forbidden_actions
