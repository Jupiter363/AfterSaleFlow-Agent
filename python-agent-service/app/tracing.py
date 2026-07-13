# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Iterator, Protocol


@dataclass(frozen=True)
class AgentTraceContext:
    trace_id: str
    request_id: str
    case_id: str
    workflow_id: str
    user_id: str | None
    role: str
    prompt_version: str


# 所属模块：Python 支撑模块 > tracing；函数角色：模块公开业务函数。
# 具体功能：`redacted_trace_input` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`hexdigest`、`sha256`、`prompt.encode`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `hexdigest`、`sha256`、`prompt.encode`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def redacted_trace_input(prompt: str) -> str:
    digest = sha256(prompt.encode("utf-8")).hexdigest()
    return f"[REDACTED_INPUT sha256={digest} length={len(prompt)}]"


class WorkflowTrace(Protocol):
    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`complete` 驱动本阶段状态对应的业务步骤并返回阶段结果。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def complete(self, output: dict[str, Any]) -> None: ...


class AgentTracer(Protocol):
    # 所属模块：Python 支撑模块 > tracing；函数角色：上下文管理器。
    # 具体功能：`workflow` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
    @contextmanager
    def workflow(
        self, context: AgentTraceContext, payload: dict[str, Any]
    ) -> Iterator[WorkflowTrace]: ...

    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`generation` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def generation(
        self,
        context: AgentTraceContext,
        node_name: str,
        model: str,
        prompt: str,
        output: dict[str, Any],
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None: ...


class _NoOpWorkflowTrace:
    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`complete` 驱动本阶段状态对应的业务步骤并返回阶段结果。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def complete(self, output: dict[str, Any]) -> None:
        pass


class NoOpAgentTracer:
    # 所属模块：Python 支撑模块 > tracing；函数角色：上下文管理器。
    # 具体功能：`workflow` 按协议增量产生或消费本阶段状态，维持顺序、限额和取消语义；关键协作调用：`_NoOpWorkflowTrace`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `_NoOpWorkflowTrace`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @contextmanager
    def workflow(
        self, context: AgentTraceContext, payload: dict[str, Any]
    ) -> Iterator[WorkflowTrace]:
        yield _NoOpWorkflowTrace()

    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`generation` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def generation(
        self,
        context: AgentTraceContext,
        node_name: str,
        model: str,
        prompt: str,
        output: dict[str, Any],
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None:
        pass


class LangfuseAgentTracer:
    # 所属模块：Python 支撑模块 > tracing；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`Langfuse`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `Langfuse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self, public_key: str, secret_key: str, host: str) -> None:
        from langfuse import Langfuse

        self._client = Langfuse(
            public_key=public_key,
            secret_key=secret_key,
            base_url=host,
        )

    # 所属模块：Python 支撑模块 > tracing；函数角色：上下文管理器。
    # 具体功能：`workflow` 按协议增量产生或消费本阶段状态，维持顺序、限额和取消语义；关键协作调用：`self._client.start_as_current_observation`、`propagate_attributes`、`_LangfuseWorkflowTrace`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self._client.start_as_current_observation`、`propagate_attributes`、`_LangfuseWorkflowTrace`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @contextmanager
    def workflow(
        self, context: AgentTraceContext, payload: dict[str, Any]
    ) -> Iterator[WorkflowTrace]:
        from langfuse import propagate_attributes

        with self._client.start_as_current_observation(
            as_type="span",
            name="hearing-c1-c6",
            input=payload,
        ) as root:
            with propagate_attributes(
                trace_name="hearing-c1-c6",
                user_id=context.user_id,
                session_id=context.case_id,
                version=context.prompt_version,
                metadata={
                    "trace_id": context.trace_id,
                    "request_id": context.request_id,
                    "case_id": context.case_id,
                    "workflow_id": context.workflow_id,
                    "role": context.role,
                },
                tags=["hearing", "c1-c6"],
            ):
                yield _LangfuseWorkflowTrace(root)

    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`generation` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self._client.start_as_current_observation`、`token_usage.get`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self._client.start_as_current_observation`、`token_usage.get`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def generation(
        self,
        context: AgentTraceContext,
        node_name: str,
        model: str,
        prompt: str,
        output: dict[str, Any],
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None:
        with self._client.start_as_current_observation(
            as_type="generation",
            name=node_name,
            model=model,
            input=prompt,
            output=output,
            metadata={
                "node_name": node_name,
                "prompt_version": context.prompt_version,
                "latency_ms": str(latency_ms),
                "token_usage": str(token_usage.get("total", 0)),
            },
        ):
            pass

    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`flush` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self._client.flush`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self._client.flush`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def flush(self) -> None:
        self._client.flush()


class _LangfuseWorkflowTrace:
    # 所属模块：Python 支撑模块 > tracing；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self, observation: Any) -> None:
        self._observation = observation

    # 所属模块：Python 支撑模块 > tracing；函数角色：类/闭包内部方法。
    # 具体功能：`complete` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`self._observation.update`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self._observation.update`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def complete(self, output: dict[str, Any]) -> None:
        self._observation.update(output=output)
