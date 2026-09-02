"""Strict all-room dispatcher for the single frozen target-E2E Graph binding."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterable
from types import MappingProxyType
from typing import Protocol

from app.contracts.v1.models import AgentStreamEvent
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import RoomType
from app.graph_runtime.persistence_models import GraphGatewayMode


TARGET_E2E_GRAPH_KEY = "all-rooms.target-e2e.v2"
TARGET_E2E_GRAPH_VERSION = "target-e2e-graph.2026-08-18.2"
TARGET_E2E_CHECKPOINT_SCHEMA_VERSION = "target-e2e-checkpoint.v2"
TARGET_E2E_OUTPUT_SCHEMA_VERSION = "target-e2e-room-proposal-source.v2"
TARGET_E2E_ROOM_TYPES = frozenset(RoomType)


class TargetE2ERoomProvider(Protocol):
    """One exact room implementation behind the common proposal-only outer binding."""

    @property
    def room_type(self) -> RoomType: ...

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]: ...


class TargetE2ECompositeExecutor:
    """Dispatch by the already-verified room enum, with no default or fallback provider."""

    def __init__(self, providers: Iterable[TargetE2ERoomProvider]) -> None:
        entries: dict[RoomType, TargetE2ERoomProvider] = {}
        for provider in providers:
            room_type = getattr(provider, "room_type", None)
            if not isinstance(room_type, RoomType) or not callable(
                getattr(provider, "stream", None)
            ):
                raise GraphContractError("target-E2E room provider binding is invalid")
            if room_type in entries:
                raise GraphContractError("duplicate target-E2E room provider")
            entries[room_type] = provider
        missing = TARGET_E2E_ROOM_TYPES.difference(entries)
        if missing or len(entries) != len(TARGET_E2E_ROOM_TYPES):
            raise GraphContractError("target-E2E composite requires exactly four room providers")
        self._providers = MappingProxyType(entries)

    @property
    def provider_count(self) -> int:
        return len(self._providers)

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        command = execution.admission.command
        fence = execution.fence
        authority = execution.admission.candidate_authority
        expected_binding = (
            TARGET_E2E_GRAPH_KEY,
            TARGET_E2E_GRAPH_VERSION,
            TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
        )
        actual_binding = (
            command.graph_key,
            command.graph_version,
            command.checkpoint_schema_version,
        )
        if (
            fence.execution_lane is not GraphGatewayMode.TARGET_E2E_CANDIDATE
            or authority is None
            or actual_binding != expected_binding
            or execution.admission.binding.execution_lane
            is not GraphGatewayMode.TARGET_E2E_CANDIDATE
        ):
            raise GraphContractError("target-E2E composite execution binding is invalid")
        try:
            room_type = RoomType(command.room_type)
            provider = self._providers[room_type]
        except (KeyError, ValueError) as error:
            raise GraphContractError("target-E2E room provider is unavailable") from error
        if provider.room_type is not room_type:
            raise GraphContractError("target-E2E room provider dispatch mismatch")
        stream = provider.stream(execution)
        if not hasattr(stream, "__aiter__"):
            raise GraphContractError("target-E2E room provider returned an invalid stream")
        return stream


__all__ = [
    "TARGET_E2E_CHECKPOINT_SCHEMA_VERSION",
    "TARGET_E2E_GRAPH_KEY",
    "TARGET_E2E_GRAPH_VERSION",
    "TARGET_E2E_OUTPUT_SCHEMA_VERSION",
    "TARGET_E2E_ROOM_TYPES",
    "TargetE2ECompositeExecutor",
    "TargetE2ERoomProvider",
]
