"""Strict all-room dispatcher for the single frozen production-runtime Graph binding."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterable
from types import MappingProxyType
from typing import Protocol

from app.contracts.v1.models import AgentStreamEvent
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import RoomType
from app.graph_runtime.persistence_models import GraphGatewayMode


PRODUCTION_RUNTIME_GRAPH_KEY = "all-rooms.production-runtime.v2"
PRODUCTION_RUNTIME_GRAPH_VERSION = "production-runtime-graph.2026-08-18.3"
PRODUCTION_RUNTIME_CHECKPOINT_SCHEMA_VERSION = "production-runtime-checkpoint.v2"
PRODUCTION_RUNTIME_OUTPUT_SCHEMA_VERSION = "production-runtime-room-proposal-source.v2"
PRODUCTION_RUNTIME_ROOM_TYPES = frozenset(RoomType)


class ProductionRoomProvider(Protocol):
    """One exact room implementation behind the common proposal-only outer binding."""

    @property
    def room_type(self) -> RoomType: ...

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]: ...


class ProductionCompositeExecutor:
    """Dispatch by the already-verified room enum, with no default or fallback provider."""

    def __init__(self, providers: Iterable[ProductionRoomProvider]) -> None:
        entries: dict[RoomType, ProductionRoomProvider] = {}
        for provider in providers:
            room_type = getattr(provider, "room_type", None)
            if not isinstance(room_type, RoomType) or not callable(
                getattr(provider, "stream", None)
            ):
                raise GraphContractError("production-runtime room provider binding is invalid")
            if room_type in entries:
                raise GraphContractError("duplicate production-runtime room provider")
            entries[room_type] = provider
        missing = PRODUCTION_RUNTIME_ROOM_TYPES.difference(entries)
        if missing or len(entries) != len(PRODUCTION_RUNTIME_ROOM_TYPES):
            raise GraphContractError("production-runtime composite requires exactly four room providers")
        self._providers = MappingProxyType(entries)

    @property
    def provider_count(self) -> int:
        return len(self._providers)

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        command = execution.admission.command
        fence = execution.fence
        authority = execution.admission.candidate_authority
        expected_binding = (
            PRODUCTION_RUNTIME_GRAPH_KEY,
            PRODUCTION_RUNTIME_GRAPH_VERSION,
            PRODUCTION_RUNTIME_CHECKPOINT_SCHEMA_VERSION,
        )
        actual_binding = (
            command.graph_key,
            command.graph_version,
            command.checkpoint_schema_version,
        )
        if (
            fence.execution_lane is not GraphGatewayMode.PRODUCTION
            or authority is None
            or actual_binding != expected_binding
            or execution.admission.binding.execution_lane
            is not GraphGatewayMode.PRODUCTION
        ):
            raise GraphContractError("production-runtime composite execution binding is invalid")
        try:
            room_type = RoomType(command.room_type)
            provider = self._providers[room_type]
        except (KeyError, ValueError) as error:
            raise GraphContractError("production-runtime room provider is unavailable") from error
        if provider.room_type is not room_type:
            raise GraphContractError("production-runtime room provider dispatch mismatch")
        stream = provider.stream(execution)
        if not hasattr(stream, "__aiter__"):
            raise GraphContractError("production-runtime room provider returned an invalid stream")
        return stream


__all__ = [
    "PRODUCTION_RUNTIME_CHECKPOINT_SCHEMA_VERSION",
    "PRODUCTION_RUNTIME_GRAPH_KEY",
    "PRODUCTION_RUNTIME_GRAPH_VERSION",
    "PRODUCTION_RUNTIME_OUTPUT_SCHEMA_VERSION",
    "PRODUCTION_RUNTIME_ROOM_TYPES",
    "ProductionCompositeExecutor",
    "ProductionRoomProvider",
]
