# 文件作用：为 Agent 运行各关键生命周期点分发同步旁路事件，供审计、指标和测试适配器监听。

"""面向审计与指标适配器的小型同步生命周期 Hook 总线。"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Callable
from enum import StrEnum


class HookEvent(StrEnum):
    BEFORE_RUN = "before_run"
    AFTER_CONTEXT_ASSEMBLED = "after_context_assembled"
    BEFORE_MODEL_CALL = "before_model_call"
    AFTER_MODEL_CALL = "after_model_call"
    BEFORE_TOOL_CALL = "before_tool_call"
    AFTER_TOOL_CALL = "after_tool_call"
    BEFORE_OUTPUT_COMMIT = "before_output_commit"
    AFTER_OUTPUT_COMMIT = "after_output_commit"
    ON_ERROR = "on_error"
    ON_INTERRUPT = "on_interrupt"


class LifecycleHooks:
    """只分发生命周期通知，不向处理器授予修改业务决策的返回通道。"""

    # 所属模块：Agent Harness > 生命周期 Hook > 处理器注册表初始化。
    # 具体功能：`__init__` 为每个 HookEvent 建立按注册顺序保存回调的列表；defaultdict 会在首次访问事件时自动创建空列表。
    # 上下游：上游是 Harness 依赖装配；下游是 `register` 添加监听器和 `emit` 同步分发。
    # 系统意义：审计/指标能力与核心决策解耦，且回调没有修改状态的返回值，避免观测插件变成隐式业务控制器。
    def __init__(self) -> None:
        self._handlers: dict[HookEvent, list[Callable[[str], None]]] = (
            defaultdict(list)
        )

    # 所属模块：Agent Harness > 生命周期 Hook > 监听器注册。
    # 具体功能：`register` 把接收事件名字符串的同步 handler 追加到指定生命周期点，同一事件可有多个处理器。
    # 上下游：上游是 trace、metrics 或测试适配器；下游是后续 `emit(event)` 按注册顺序调用。
    # 系统意义：显式事件枚举限制可插入位置，避免任意字符串 Hook 绕开正常 Agent 执行顺序。
    def register(
        self, event: HookEvent, handler: Callable[[str], None]
    ) -> None:
        self._handlers[event].append(handler)

    # 所属模块：Agent Harness > 生命周期 Hook > 同步事件分发。
    # 具体功能：`emit` 对当前处理器列表做 tuple 快照，再逐个传入 `event.value`；分发中新增处理器不会影响本轮遍历。
    # 上下游：上游是运行循环在模型、工具、提交、错误或中断边界触发；下游是所有已注册审计/指标回调。
    # 系统意义：稳定快照保证事件分发顺序可预测；回调异常会向上冒泡，避免审计失败被静默吞掉。
    def emit(self, event: HookEvent) -> None:
        for handler in tuple(self._handlers[event]):
            handler(event.value)
