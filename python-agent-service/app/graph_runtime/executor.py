"""Explicit target-E2E executor assembly without process-global security state."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import TYPE_CHECKING

from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.target_e2e_composite import TargetE2ERoomProvider
from app.graph_runtime.target_e2e_room_adapters import (
    TargetE2EHearingInvocationDecoder,
    TargetE2ESpecializedRoomDependencies,
    build_target_e2e_specialized_room_providers,
)
from app.graph_runtime.target_e2e_room_exchange import (
    DeterministicTargetE2EHearingInvocationDecoder,
    JavaTargetE2ERoomExchange,
)
from app.graphs.evidence.contracts import EvidenceAdmissionVerifier
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

    def __call__(self, kernel: GraphExecutorKernel) -> Iterable[TargetE2ERoomProvider]:
        if type(self.security_runtime) is not GraphSecurityRuntime:
            raise GraphContractError("TARGET_E2E_GRAPH_SECURITY_RUNTIME_REQUIRED")
        if not callable(getattr(self.room_exchange, "for_execution", None)):
            raise GraphContractError("TARGET_E2E_ROOM_EXCHANGE_REQUIRED")
        verifier = EvidenceAdmissionVerifier.from_security_runtime(self.security_runtime)
        return build_target_e2e_specialized_room_providers(
            saver=kernel.saver,
            bulkhead=kernel.durable_bulkhead,
            dependencies=TargetE2ESpecializedRoomDependencies(
                evidence_verifier=verifier,
                object_store=None,
                object_store_factory=self.room_exchange,
                evidence_model=None,
                hearing_decoder=(
                    self.hearing_decoder
                    if self.hearing_decoder is not None
                    else DeterministicTargetE2EHearingInvocationDecoder()
                ),
            ),
        )


__all__ = ["TargetE2ESpecializedRoomProviderFactory"]
