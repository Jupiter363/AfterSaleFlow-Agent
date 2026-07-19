"""Execution-scoped bridge from the governed provider boundary to the Graph ledger."""

from __future__ import annotations

import asyncio
from typing import Protocol

from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.llm import ProviderCallIntent


class ProviderCallGateway(Protocol):
    async def record_provider_call(
        self,
        execution: GatewayExecution,
    ) -> GatewayExecution: ...


class GatewayProviderCallIntentRecorder:
    """Persist provider-call intent before transport and retain the latest attempt record."""

    def __init__(
        self,
        *,
        gateway: ProviderCallGateway,
        execution: GatewayExecution,
        provider: str,
        model: str,
        allowed_nodes: frozenset[str],
    ) -> None:
        if not provider or not model or not allowed_nodes:
            raise ValueError("provider intent binding must be complete")
        self._gateway = gateway
        self._execution = execution
        self._provider = provider
        self._model = model
        self._allowed_nodes = allowed_nodes
        self._lock = asyncio.Lock()

    @property
    def execution(self) -> GatewayExecution:
        return self._execution

    def record_provider_call(self, intent: ProviderCallIntent) -> None:
        del intent
        raise GraphContractError(
            "production Graph provider calls require the native async model path"
        )

    async def arecord_provider_call(self, intent: ProviderCallIntent) -> None:
        self._require_binding(intent)
        async with self._lock:
            self._execution = await self._gateway.record_provider_call(self._execution)

    def _require_binding(self, intent: ProviderCallIntent) -> None:
        if not isinstance(intent, ProviderCallIntent):
            raise GraphContractError("provider call intent is not typed")
        command = self._execution.admission.command
        if (
            intent.provider != self._provider
            or intent.model != self._model
            or intent.node_name not in self._allowed_nodes
            or intent.traceparent != command.traceparent
        ):
            raise GraphContractError("provider call intent conflicts with the Graph binding")
