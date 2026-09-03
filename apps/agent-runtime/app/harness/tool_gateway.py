# 文件作用：作为 Agent 调用确定性工具的唯一网关，执行 Profile/阶段/字段授权、输入 Schema 校验、输出脱敏和成功审计。

"""所有 Agent 工具调用共用的 Schema 校验、授权、脱敏与审计网关。"""

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
    """Agent 从不确定模型世界进入确定性能力的唯一运行时入口。"""

    # 所属模块：Agent Harness > ToolGateway > 工具目录与审计出口初始化。
    # 具体功能：`__init__` 创建工具定义表，并注入成功审计 sink；未配置时使用无副作用 lambda，方便纯单元测试。
    # 上下游：上游是服务启动依赖装配；下游是 `register` 与每次 `execute` 的审计记录。
    # 系统意义：handler 永不直接暴露给模型文本，所有调用必须经过同一目录和审计边界。
    def __init__(
        self, audit_sink: Callable[[dict[str, Any]], None] | None = None
    ) -> None:
        self._definitions: dict[str, ToolDefinition] = {}
        self._audit_sink = audit_sink or (lambda _: None)

    # 所属模块：Agent Harness > ToolGateway > 唯一工具合同注册。
    # 具体功能：`register` 按工具名保存版本、输入模型、阶段、输出字段、敏感字段和 handler；重复工具名拒绝覆盖。
    # 上下游：上游是可信启动代码创建 ToolDefinition；下游是 `execute` 查找并执行固定合同。
    # 系统意义：防止运行中后注册的同名工具劫持既有能力，也让 tool_name/version 在审计中可唯一追溯。
    def register(self, definition: ToolDefinition) -> None:
        if definition.name in self._definitions:
            raise ValueError(f"tool is already registered: {definition.name}")
        self._definitions[definition.name] = definition

    # 所属模块：Agent Harness > ToolGateway > 授权、执行、脱敏、审计主链路。
    # 具体功能：`execute` 先校验 Profile 工具权限、注册状态、案件阶段和 requested_fields，再用 Pydantic 校验参数、调用 handler、过滤敏感/未请求字段并生成 audit_id。
    # 上下游：上游是 Agent 规划形成的 ToolRequest 与服务端 AgentProfile；下游是确定性 handler、审计 sink 和只含可见字段的 ToolResult。
    # 系统意义：模型不能直接选择任意返回字段或读取敏感值；授权失败与执行失败分型，只有成功结果才带可提交数据和来源引用。
    def execute(
        self, profile: AgentProfile, request: ToolRequest
    ) -> ToolResult:
        # 授权检查必须全部发生在 handler 之前，越权请求不能产生任何外部副作用。
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

        # Prompt 中的 arguments 仍是不可信对象；Pydantic 在进入 handler 前强制字段、类型和 extra 规则。
        validated = definition.input_model.model_validate(request.arguments)
        try:
            raw = definition.handler(validated)
        except Exception as exception:
            raise ToolExecutionError(
                f"tool failed: {request.tool_name}"
            ) from exception

        # requested 为空表示采用工具声明的全部普通输出字段；敏感字段无论是否请求都最终排除。
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
