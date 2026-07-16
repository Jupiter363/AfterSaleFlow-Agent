# 文件作用：定义 Java 服务为单次 Agent 回合签发的可信身份、会话、权限与 Prompt 配置信封，并在入口处严格校验。

"""无状态 Agent 回合使用的服务端可信调用上下文；客户端案件文本不能自行构造这些权限字段。"""

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
    "INTAKE_PARTY_PRIVATE",
    "EVIDENCE_PARTY_PRIVATE",
    "ROOM_SHARED",
    "SYSTEM",
]


class AgentInvocationContext(StrictModel):
    """Java 为一次 LLM 回合提供的访问/会话信封，是参与方隔离与审计关联的根上下文。"""

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

    # 所属模块：Agent Harness > 调用身份信封 > 必填标量校验。
    # 具体功能：`reject_blank_scalar` 是 Pydantic 字段验证器，在构造上下文时逐个拒绝只含空白的租户、案件、参与方、会话、Agent 与策略标识。
    # 上下游：上游是 API 对 Java 请求执行 `AgentInvocationContext.model_validate`；下游是 ContextPack、记忆隔离、Prompt profile 选择和全链路 trace 关联。
    # 系统意义：这些字符串共同组成授权范围；允许空白会让不同案件或参与方落入同一个默认会话，造成严重串话风险。
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
        # `info.field_name` 由 Pydantic 注入，使同一验证函数能准确指出是哪个字段为空。
        if not value.strip():
            raise ValueError(f"{info.field_name} must not be blank")
        return value

    # 所属模块：Agent Harness > 调用身份信封 > 权限列表元素校验。
    # 具体功能：`reject_blank_list_items` 检查 permission_scopes、allowed_actor_ids、allowed_actor_roles 中每一项，并在错误里保留列表下标。
    # 上下游：上游同样是 Pydantic 请求解析；下游是证据/消息可见性过滤与参与方会话授权判断。
    # 系统意义：空元素不能被解释成通配符或默认角色；失败关闭可防止权限列表在服务间传递时语义扩大。
    @field_validator("permission_scopes", "allowed_actor_ids", "allowed_actor_roles")
    @classmethod
    def reject_blank_list_items(cls, values: list[str], info) -> list[str]:
        for index, value in enumerate(values):
            if not value.strip():
                raise ValueError(f"{info.field_name}[{index}] must not be blank")
        return values
