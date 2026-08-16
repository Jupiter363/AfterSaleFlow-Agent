"""Explicit target-E2E executor assembly without process-global security state."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, replace
from typing import TYPE_CHECKING

from app.agents.hearing_flow import HearingFlowWorkflows
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.evidence_turn_executor import EvidenceTurnWorkflowPort
from app.graph_runtime.target_e2e_composite import TargetE2ERoomProvider
from app.graph_runtime.target_e2e_room_adapters import (
    TargetE2EHearingInvocationDecoder,
    TargetE2ESpecializedRoomDependencies,
    build_target_e2e_specialized_room_providers,
)
from app.graph_runtime.target_e2e_room_exchange import (
    GovernedTargetE2EHearingInvocationDecoder,
    JavaTargetE2ERoomExchange,
)
from app.security.graph_runtime import GraphSecurityRuntime

if TYPE_CHECKING:
    from app.api.graph_lifecycle import GraphExecutorKernel


@dataclass(frozen=True, slots=True)
class TargetE2ESpecializedRoomProviderFactory:
    """Build non-Intake providers from lifecycle-owned trusted dependencies.

    The lifecycle opens the JWKS-backed ``GraphSecurityRuntime`` before it
    calls this object.  That concrete runtime is injected directly, instead of
    being recreated from settings or stored in module state.
    """

    security_runtime: GraphSecurityRuntime
    room_exchange: JavaTargetE2ERoomExchange
    hearing_decoder: TargetE2EHearingInvocationDecoder | None = None
    evidence_workflow: EvidenceTurnWorkflowPort | None = None

    def with_evidence_workflow(
        self,
        workflow: EvidenceTurnWorkflowPort,
    ) -> TargetE2ESpecializedRoomProviderFactory:
        if not callable(getattr(workflow, "run", None)):
            raise GraphContractError("TARGET_E2E_FORMAL_EVIDENCE_WORKFLOW_REQUIRED")
        return replace(self, evidence_workflow=workflow)

    def with_hearing_workflow(
        self,
        workflow: HearingFlowWorkflows,
    ) -> TargetE2ESpecializedRoomProviderFactory:
        if not callable(getattr(workflow, "target_e2e_invocation", None)):
            raise GraphContractError("TARGET_E2E_FORMAL_HEARING_WORKFLOW_REQUIRED")
        return replace(
            self,
            hearing_decoder=GovernedTargetE2EHearingInvocationDecoder(workflow),
        )

    def __call__(self, kernel: GraphExecutorKernel) -> Iterable[TargetE2ERoomProvider]:
        if type(self.security_runtime) is not GraphSecurityRuntime:
            raise GraphContractError("TARGET_E2E_GRAPH_SECURITY_RUNTIME_REQUIRED")
        if not callable(getattr(self.room_exchange, "for_execution", None)):
            raise GraphContractError("TARGET_E2E_ROOM_EXCHANGE_REQUIRED")
        if not callable(getattr(self.evidence_workflow, "run", None)):
            raise GraphContractError("TARGET_E2E_FORMAL_EVIDENCE_WORKFLOW_REQUIRED")
        if self.hearing_decoder is None:
            raise GraphContractError("TARGET_E2E_FORMAL_HEARING_WORKFLOW_REQUIRED")
        return build_target_e2e_specialized_room_providers(
            saver=kernel.saver,
            bulkhead=kernel.durable_bulkhead,
            dependencies=TargetE2ESpecializedRoomDependencies(
                object_store=None,
                object_store_factory=self.room_exchange,
                evidence_workflow=self.evidence_workflow,
                hearing_decoder=self.hearing_decoder,
            ),
        )


__all__ = ["TargetE2ESpecializedRoomProviderFactory"]
