"""Explicit production-runtime executor assembly without process-global security state."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, replace
from typing import TYPE_CHECKING

from app.agents.hearing_flow import HearingFlowWorkflows
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.evidence_turn_executor import EvidenceTurnWorkflowPort
from app.graph_runtime.production_runtime_composite import ProductionRoomProvider
from app.graph_runtime.production_runtime_room_adapters import (
    ProductionHearingInvocationDecoder,
    ProductionSpecializedRoomDependencies,
    build_production_runtime_specialized_room_providers,
)
from app.graph_runtime.production_runtime_room_exchange import (
    GovernedProductionHearingInvocationDecoder,
    JavaProductionRoomExchange,
)
from app.security.graph_runtime import GraphSecurityRuntime

if TYPE_CHECKING:
    from app.api.graph_lifecycle import GraphExecutorKernel


@dataclass(frozen=True, slots=True)
class ProductionSpecializedRoomProviderFactory:
    """Build non-Intake providers from lifecycle-owned trusted dependencies.

    The lifecycle opens the JWKS-backed ``GraphSecurityRuntime`` before it
    calls this object.  That concrete runtime is injected directly, instead of
    being recreated from settings or stored in module state.
    """

    security_runtime: GraphSecurityRuntime
    room_exchange: JavaProductionRoomExchange
    hearing_decoder: ProductionHearingInvocationDecoder | None = None
    evidence_workflow: EvidenceTurnWorkflowPort | None = None

    def with_evidence_workflow(
        self,
        workflow: EvidenceTurnWorkflowPort,
    ) -> ProductionSpecializedRoomProviderFactory:
        if (
            not callable(getattr(workflow, "run", None))
            or not callable(getattr(workflow, "arun", None))
            or getattr(workflow, "protocol_version", None)
            != "evidence-turn-result.v3"
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_FORMAL_EVIDENCE_WORKFLOW_REQUIRED")
        return replace(self, evidence_workflow=workflow)

    def with_hearing_workflow(
        self,
        workflow: HearingFlowWorkflows,
    ) -> ProductionSpecializedRoomProviderFactory:
        if not callable(getattr(workflow, "production_runtime_invocation", None)):
            raise GraphContractError("PRODUCTION_RUNTIME_FORMAL_HEARING_WORKFLOW_REQUIRED")
        return replace(
            self,
            hearing_decoder=GovernedProductionHearingInvocationDecoder(workflow),
        )

    def __call__(self, kernel: GraphExecutorKernel) -> Iterable[ProductionRoomProvider]:
        if type(self.security_runtime) is not GraphSecurityRuntime:
            raise GraphContractError("PRODUCTION_RUNTIME_GRAPH_SECURITY_RUNTIME_REQUIRED")
        if not callable(getattr(self.room_exchange, "for_execution", None)):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_REQUIRED")
        if (
            not callable(getattr(self.evidence_workflow, "run", None))
            or not callable(getattr(self.evidence_workflow, "arun", None))
            or getattr(self.evidence_workflow, "protocol_version", None)
            != "evidence-turn-result.v3"
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_FORMAL_EVIDENCE_WORKFLOW_REQUIRED")
        if self.hearing_decoder is None:
            raise GraphContractError("PRODUCTION_RUNTIME_FORMAL_HEARING_WORKFLOW_REQUIRED")
        return build_production_runtime_specialized_room_providers(
            saver=kernel.saver,
            bulkhead=kernel.durable_bulkhead,
            dependencies=ProductionSpecializedRoomDependencies(
                object_store=None,
                object_store_factory=self.room_exchange,
                evidence_workflow=self.evidence_workflow,
                hearing_decoder=self.hearing_decoder,
            ),
        )


__all__ = ["ProductionSpecializedRoomProviderFactory"]
