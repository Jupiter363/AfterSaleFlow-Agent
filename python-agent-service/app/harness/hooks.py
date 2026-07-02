"""Small synchronous lifecycle hooks for audit and metrics adapters."""

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
    """Dispatches hooks without granting them authority to change decisions."""

    def __init__(self) -> None:
        self._handlers: dict[HookEvent, list[Callable[[str], None]]] = (
            defaultdict(list)
        )

    def register(
        self, event: HookEvent, handler: Callable[[str], None]
    ) -> None:
        self._handlers[event].append(handler)

    def emit(self, event: HookEvent) -> None:
        for handler in tuple(self._handlers[event]):
            handler(event.value)
