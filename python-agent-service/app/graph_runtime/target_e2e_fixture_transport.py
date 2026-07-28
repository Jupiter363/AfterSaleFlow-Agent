"""Deterministic local model transport for the isolated target-E2E fixture lane."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from dataclasses import dataclass
import json

from app.config import (
    GraphTargetE2EBindingSettings,
    GraphTargetE2ERuntimeContextSettings,
    GraphTargetE2ESyntheticCaseScope,
)
from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.errors import GraphContractError
from app.graphs.intake.contracts import IntakeCognitionDraft
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportRequest,
    ModelTransportResult,
    ModelTransportStreamUpdate,
)


TARGET_E2E_FIXTURE_PROVIDER = "target-e2e-fixture"
TARGET_E2E_FIXTURE_MODEL = "target-e2e.intake-fixture.v1"


@dataclass(frozen=True, slots=True)
class TargetE2EDeterministicFixtureTransport:
    """No-egress fixture transport that still drives the real LCEL model node."""

    activation_id: str
    fixture_set_id: str
    fixture_set_hash: str
    binding_hash: str
    candidate_sha: str

    @property
    def fixture_binding_hash(self) -> str:
        return canonical_sha256(
            {
                "schema_version": "target-e2e-fixture-model-binding.v1",
                "activation_id": self.activation_id,
                "candidate_sha": self.candidate_sha,
                "fixture_set_id": self.fixture_set_id,
                "fixture_set_hash": self.fixture_set_hash,
                "binding_hash": self.binding_hash,
                "provider": TARGET_E2E_FIXTURE_PROVIDER,
                "model": TARGET_E2E_FIXTURE_MODEL,
            }
        )

    def generate(self, request: ModelTransportRequest) -> ModelTransportResult:
        return self._result(request)

    async def agenerate(self, request: ModelTransportRequest) -> ModelTransportResult:
        return self._result(request)

    def stream(self, request: ModelTransportRequest) -> Iterator[ModelTransportStreamUpdate]:
        yield ModelTransportCompleted(result=self._result(request))

    async def astream(
        self, request: ModelTransportRequest
    ) -> AsyncIterator[ModelTransportStreamUpdate]:
        yield ModelTransportCompleted(result=self._result(request))

    def _result(self, request: ModelTransportRequest) -> ModelTransportResult:
        _require_fixture_request(request)
        draft = IntakeCognitionDraft(
            room_utterance="Target E2E fixture requires additional intake details.",
            dossier_patch={},
            matrix_patch=None,
            readiness="INCOMPLETE",
            missing_fields=("FIXTURE_CONTEXT_REQUIRED",),
            recommendation="NEED_MORE_INFO",
            knowledge_answer_mode="STUB",
            confidence=0.0,
        )
        return ModelTransportResult(
            json_document=json.dumps(
                draft.model_dump(mode="json", exclude_none=True),
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
            model=TARGET_E2E_FIXTURE_MODEL,
            latency_ms=0,
            token_usage={"input": 0, "output": 0, "total": 0},
        )


def build_target_e2e_fixture_transport(
    *,
    context: GraphTargetE2ERuntimeContextSettings,
    binding: GraphTargetE2EBindingSettings,
) -> TargetE2EDeterministicFixtureTransport:
    scope = context.caseScope
    if (
        not isinstance(scope, GraphTargetE2ESyntheticCaseScope)
        or scope.containsRealCaseOrPartyData is not False
        or scope.externalEffectsAllowed is not False
        or "INTAKE" not in context.allowedRoomTypes
        or binding.graph_key != "all-rooms.target-e2e.v1"
        or binding.graph_version != "target-e2e-graph.2026-07-27.1"
        or binding.checkpoint_schema_version != "target-e2e-checkpoint.v1"
        or binding.output_schema_version != "target-e2e-room-proposal-source.v1"
        or frozenset(binding.allowed_room_types)
        != frozenset({"INTAKE", "EVIDENCE", "HEARING", "REVIEW"})
    ):
        raise GraphContractError("TARGET_E2E_FIXTURE_RUNTIME_BINDING_REQUIRED")
    return TargetE2EDeterministicFixtureTransport(
        activation_id=context.activationId,
        fixture_set_id=scope.fixtureSetId,
        fixture_set_hash=scope.fixtureSetHash,
        binding_hash=binding.binding_hash,
        candidate_sha=context.candidateSha,
    )


def _require_fixture_request(request: ModelTransportRequest) -> None:
    governed = request.governed_request
    if (
        request.node_name != "intake_lcel"
        or request.output_type is not IntakeCognitionDraft
        or governed.provider != TARGET_E2E_FIXTURE_PROVIDER
        or governed.model != TARGET_E2E_FIXTURE_MODEL
        or governed.temperature != 0
        or governed.tool_allowlist
        or governed.response_format != "STRICT_JSON_SCHEMA"
    ):
        raise GraphContractError("TARGET_E2E_FIXTURE_MODEL_REQUEST_REJECTED")


__all__ = [
    "TARGET_E2E_FIXTURE_MODEL",
    "TARGET_E2E_FIXTURE_PROVIDER",
    "TargetE2EDeterministicFixtureTransport",
    "build_target_e2e_fixture_transport",
]
