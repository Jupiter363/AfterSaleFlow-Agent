from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from copy import deepcopy
from types import MappingProxyType
from typing import Any, Generic, TypeVar, cast

from langchain_core.runnables import Runnable, RunnableConfig
from pydantic import TypeAdapter, ValidationError


StateT = TypeVar("StateT", bound=Mapping[str, Any])
PromptInputT = TypeVar("PromptInputT", bound=Mapping[str, Any])


class StateLensError(ValueError):
    pass


class StateLens(Runnable[StateT, PromptInputT], Generic[StateT, PromptInputT]):
    """A traced Runnable that exposes only explicitly declared state fields."""

    def __init__(
        self,
        *,
        name: str,
        source_fields: Sequence[str],
        selector: Callable[[Mapping[str, Any]], Mapping[str, Any]],
        output_type: Any,
    ) -> None:
        if not name or len(name) > 128:
            raise StateLensError("State Lens name must be 1..128 characters")
        fields = tuple(source_fields)
        if not fields or len(set(fields)) != len(fields) or any(not field for field in fields):
            raise StateLensError("State Lens source fields must be non-empty and unique")
        self.name = name
        self.source_fields = fields
        self._selector = selector
        self._adapter = TypeAdapter(output_type)

    def invoke(
        self,
        input: StateT,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> PromptInputT:
        if kwargs:
            raise StateLensError("State Lens does not accept invocation overrides")
        return self._call_with_config(
            self._select,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "state_lens"},
        )

    async def ainvoke(
        self,
        input: StateT,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> PromptInputT:
        if kwargs:
            raise StateLensError("State Lens does not accept invocation overrides")
        return await self._acall_with_config(
            self._aselect,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "state_lens"},
        )

    def _select(self, state: StateT) -> PromptInputT:
        missing = [field for field in self.source_fields if field not in state]
        if missing:
            raise StateLensError(f"State Lens source field is missing: {missing[0]}")
        scoped = MappingProxyType(
            {field: deepcopy(state[field]) for field in self.source_fields}
        )
        try:
            selected = self._adapter.validate_python(self._selector(scoped))
        except (KeyError, ValidationError, TypeError, ValueError) as error:
            raise StateLensError(f"State Lens output failed validation: {self.name}") from error
        if not isinstance(selected, Mapping):
            raise StateLensError("State Lens output must be a prompt mapping")
        return cast(PromptInputT, dict(selected))

    async def _aselect(self, state: StateT) -> PromptInputT:
        return self._select(state)
