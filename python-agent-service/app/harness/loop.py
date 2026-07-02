"""Bounded Agent loop control with explicit stop reasons."""

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
    """Executes cognitive steps without allowing unbounded autonomy."""

    def __init__(
        self, budget: LoopBudget, clock: Callable[[], float] = monotonic
    ) -> None:
        self._budget = budget
        self._clock = clock

    def run(
        self, step_fn: Callable[[LoopProgress], LoopStep]
    ) -> LoopResult:
        started = self._clock()
        progress = LoopProgress()
        previous_fingerprint: str | None = None
        stagnant_repeats = 0

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

    @staticmethod
    def _stopped(
        reason: LoopStopReason, progress: LoopProgress
    ) -> LoopResult:
        return LoopResult(
            stop_reason=reason,
            requires_human=True,
            **progress.model_dump(),
        )
