# 文件作用：用迭代、模型/工具调用、Token、截止时间和停滞指纹约束 Agent 自主循环，并返回明确停止原因。

"""带显式停止原因、可被中断且有硬预算的 Agent 循环控制器。"""

from __future__ import annotations

from enum import StrEnum
from time import monotonic
from typing import Any, Callable

from pydantic import BaseModel, ConfigDict, Field

from app.harness.profile import LoopBudget


class LoopStopReason(StrEnum):
    COMPLETED = "COMPLETED"
    ITERATION_BUDGET_EXCEEDED = "ITERATION_BUDGET_EXCEEDED"
    TOOL_BUDGET_EXCEEDED = "TOOL_BUDGET_EXCEEDED"
    MODEL_BUDGET_EXCEEDED = "MODEL_BUDGET_EXCEEDED"
    TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    STAGNATION = "STAGNATION"
    STEP_SOURCE_EXHAUSTED = "STEP_SOURCE_EXHAUSTED"


class LoopStep(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    progress_fingerprint: str = Field(min_length=1, max_length=256)
    model_calls: int = Field(default=0, ge=0)
    tool_calls: int = Field(default=0, ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    completed: bool = False
    output: dict[str, Any] | None = None


class LoopProgress(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    iterations: int = 0
    model_calls: int = 0
    tool_calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0


class LoopResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    stop_reason: LoopStopReason
    requires_human: bool
    iterations: int
    model_calls: int
    tool_calls: int
    input_tokens: int
    output_tokens: int
    output: dict[str, Any] | None = None


class ControlledAgentLoop:
    """重复执行认知步骤，但不允许无限迭代、无限调用或无进展循环。"""

    # 所属模块：Agent Harness > 受控循环 > 预算与时钟注入。
    # 具体功能：`__init__` 保存已校验 LoopBudget，并允许测试注入单调时钟；默认 monotonic 不受系统时间校准影响。
    # 上下游：上游是 AgentProfile 依赖装配；下游是 `run` 的每次限额和截止时间判断。
    # 系统意义：可替换时钟让超时逻辑可测试；单调时钟防止系统时间回拨让循环超过真实期限。
    def __init__(
        self, budget: LoopBudget, clock: Callable[[], float] = monotonic
    ) -> None:
        self._budget = budget
        self._clock = clock

    # 所属模块：Agent Harness > 受控循环 > 主执行状态机。
    # 具体功能：`run` 在每步前检查迭代/时限，执行 step_fn 后累计模型、工具、Token 用量，再检查预算、重复指纹和 completed 标志。
    # 上下游：上游是具体 Agent 提供的 `step_fn(LoopProgress)->LoopStep`；下游是成功输出或带停止原因且 `requires_human=True` 的 LoopResult。
    # 系统意义：模型不能自行决定继续多久；任何资源超限、步骤耗尽或连续无进展都会确定性停止并交给人工，而非继续消耗资源。
    def run(
        self, step_fn: Callable[[LoopProgress], LoopStep]
    ) -> LoopResult:
        started = self._clock()
        progress = LoopProgress()
        previous_fingerprint: str | None = None
        stagnant_repeats = 0

        # `while True` 本身没有退出条件，但下面每条终止路径都返回 LoopResult；硬预算保证循环有界。
        while True:
            if progress.iterations >= self._budget.max_iterations:
                return self._stopped(
                    LoopStopReason.ITERATION_BUDGET_EXCEEDED, progress
                )
            if self._clock() - started > self._budget.deadline_seconds:
                return self._stopped(
                    LoopStopReason.DEADLINE_EXCEEDED, progress
                )
            try:
                step = step_fn(progress)
            except StopIteration:
                return self._stopped(
                    LoopStopReason.STEP_SOURCE_EXHAUSTED, progress
                )

            # Pydantic 模型配置 frozen=True，因此不原地修改 progress，而是用累计值创建下一份不可变快照。
            progress = LoopProgress(
                iterations=progress.iterations + 1,
                model_calls=progress.model_calls + step.model_calls,
                tool_calls=progress.tool_calls + step.tool_calls,
                input_tokens=progress.input_tokens + step.input_tokens,
                output_tokens=progress.output_tokens + step.output_tokens,
            )
            overrun = self._budget_overrun(progress)
            if overrun is not None:
                return self._stopped(overrun, progress)

            # fingerprint 由业务步骤概括“本轮取得了什么进展”；连续相同说明 Agent 在重复同一策略。
            if step.progress_fingerprint == previous_fingerprint:
                stagnant_repeats += 1
            else:
                stagnant_repeats = 0
                previous_fingerprint = step.progress_fingerprint
            if stagnant_repeats >= self._budget.stagnation_threshold:
                return self._stopped(LoopStopReason.STAGNATION, progress)

            if step.completed:
                return LoopResult(
                    stop_reason=LoopStopReason.COMPLETED,
                    requires_human=False,
                    output=step.output,
                    **progress.model_dump(),
                )

    # 所属模块：Agent Harness > 受控循环 > 累计资源超限判定。
    # 具体功能：`_budget_overrun` 按工具、模型、Token 顺序比较累计进度与 Profile 硬上限，返回首个对应 StopReason；未超限返回 None。
    # 上下游：上游是 `run` 每完成一步后的 LoopProgress；下游是 `_stopped` 生成需人工介入结果或继续下一步。
    # 系统意义：使用累计值而非只看单次调用，防止多个小调用绕开总预算；停止原因可用于监控和配置调优。
    def _budget_overrun(
        self, progress: LoopProgress
    ) -> LoopStopReason | None:
        if progress.tool_calls > self._budget.max_tool_calls:
            return LoopStopReason.TOOL_BUDGET_EXCEEDED
        if progress.model_calls > self._budget.max_model_calls:
            return LoopStopReason.MODEL_BUDGET_EXCEEDED
        if (
            progress.input_tokens > self._budget.max_input_tokens
            or progress.output_tokens > self._budget.max_output_tokens
        ):
            return LoopStopReason.TOKEN_BUDGET_EXCEEDED
        return None

    # 所属模块：Agent Harness > 受控循环 > 非成功停止结果归一化。
    # 具体功能：`_stopped` 将任一预算、超时、停滞或步骤耗尽原因与当前累计用量合并成统一 LoopResult，并固定 requires_human=True。
    # 上下游：上游是 `run` 的所有非 COMPLETED 返回路径；下游是业务工作流创建人工任务、重试策略或运行审计。
    # 系统意义：非正常停止不会携带未经完成标志确认的业务 output，避免半成品被误当最终结果提交。
    @staticmethod
    def _stopped(
        reason: LoopStopReason, progress: LoopProgress
    ) -> LoopResult:
        return LoopResult(
            stop_reason=reason,
            requires_human=True,
            **progress.model_dump(),
        )
