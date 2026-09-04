"""Deterministic local model transport for the isolated target-E2E fixture lane."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from dataclasses import dataclass
import json
import re
from typing import Any

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
        draft = _fixture_draft(request)
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
        or binding.graph_key != "all-rooms.target-e2e.v2"
        or binding.graph_version != "target-e2e-graph.2026-08-18.3"
        or binding.checkpoint_schema_version != "target-e2e-checkpoint.v2"
        or binding.output_schema_version != "target-e2e-room-proposal-source.v2"
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


def _fixture_draft(request: ModelTransportRequest) -> IntakeCognitionDraft:
    """Emit the two valid Intake proposals exercised by the target E2E lane.

    The fixture is deterministic, but it must still honour the same unilateral-then-
    respondent-delta authority model as the production graph.  Returning a no-op
    proposal here makes a completed target run unusable by every downstream room.
    """
    content = _fixture_human_prompt(request)
    _fixture_audience(content)
    matrix = _fixture_frozen_initiator_matrix(content)
    if matrix is None:
        return IntakeCognitionDraft(
            room_utterance="The initiator's signed-but-not-received claim is ready for confirmation.",
            dossier_patch=_fixture_dossier_patch(),
            matrix_patch={
                "schema_version": "unilateral_case_matrix.draft.v1",
                "fact_rows": [
                    {
                        "fact_key": "NEW_TARGET_E2E_DELIVERY",
                        "category": "FULFILLMENT",
                        "fact_target": "Whether the signed parcel was received by the user.",
                        "materiality": "CORE",
                        "position_summary": "The initiator reports that the signed parcel was not received.",
                        "asserted_value": "not received",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_TARGET_E2E_DELIVERY"],
            },
            readiness="READY_TO_CONFIRM",
            missing_fields=(),
            recommendation="ACCEPTED",
            knowledge_answer_mode="STUB",
            confidence=1.0,
        )

    rows = matrix.get("fact_rows")
    if not isinstance(rows, list) or not rows:
        raise GraphContractError("TARGET_E2E_FIXTURE_FROZEN_INITIATOR_MATRIX_REQUIRED")
    delta_rows: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            raise GraphContractError("TARGET_E2E_FIXTURE_FROZEN_INITIATOR_MATRIX_REQUIRED")
        fact_id = row.get("fact_id")
        category = row.get("category")
        fact_target = row.get("fact_target")
        materiality = row.get("materiality")
        if not all(isinstance(value, str) and value for value in (fact_id, category, fact_target, materiality)):
            raise GraphContractError("TARGET_E2E_FIXTURE_FROZEN_INITIATOR_MATRIX_REQUIRED")
        delta_rows.append(
            {
                "fact_key": fact_id,
                "category": category,
                "fact_target": fact_target,
                "materiality": materiality,
                "stance": "CONFIRM",
                "position_summary": "The respondent confirms the delivery record for the target E2E case.",
                "asserted_value": "delivery record confirmed",
                "source_scope": "CURRENT_SOURCE",
            }
        )
    return IntakeCognitionDraft(
        room_utterance="The respondent's position is ready for confirmation.",
        dossier_patch=_fixture_dossier_patch(),
        matrix_patch={
            "schema_version": "case_fact_matrix.delta.v2",
            "fact_rows": delta_rows,
            "summary_source_fact_keys": [row["fact_key"] for row in delta_rows],
            "respondent_claim": {
                "attitude": "AGREE",
                "position_summary": "The respondent accepts the target E2E intake record.",
            },
        },
        readiness="READY_TO_CONFIRM",
        missing_fields=(),
        recommendation="ACCEPTED",
        knowledge_answer_mode="STUB",
        confidence=1.0,
    )


def _fixture_human_prompt(request: ModelTransportRequest) -> str:
    if len(request.messages) != 2 or not isinstance(request.messages[1].content, str):
        raise GraphContractError("TARGET_E2E_FIXTURE_PROMPT_REQUIRED")
    return request.messages[1].content


def _fixture_dossier_patch() -> dict[str, Any]:
    """Supply the non-model-controlled facts required by the formal matrix reducer."""
    return {
        "schema_version": "intake-dossier.v2",
        "case_story": {
            "one_sentence_summary": (
                "The parties ask the platform to resolve a signed-but-not-received parcel dispute."
            )
        },
        "claim_resolution": {
            "requested_resolution": "REFUND",
            "reason_summary": "The initiator reports that the signed parcel was not received.",
            "position_summary": "The initiator requests a refund for the undelivered parcel.",
        },
        "dispute_core_state": {
            "core_conflict": "Whether the signed parcel was actually received by the user.",
            "facts_in_dispute": ["Parcel receipt after a signed delivery record."],
            "next_verification_focus": ["Delivery record and recipient confirmation."],
        },
    }


def _fixture_audience(content: str) -> str:
    match = re.match(r"Authorized audience: (USER|MERCHANT)\n", content)
    if match is None:
        raise GraphContractError("TARGET_E2E_FIXTURE_AUDIENCE_REQUIRED")
    return match.group(1)


def _fixture_frozen_initiator_matrix(content: str) -> dict[str, Any] | None:
    match = re.search(
        r"<authorized_dossier_json>(.*?)</authorized_dossier_json>",
        content,
        flags=re.DOTALL,
    )
    if match is None:
        raise GraphContractError("TARGET_E2E_FIXTURE_DOSSIER_REQUIRED")
    try:
        dossier = json.loads(match.group(1))
    except json.JSONDecodeError as error:
        raise GraphContractError("TARGET_E2E_FIXTURE_DOSSIER_REQUIRED") from error
    matrix = dossier.get("case_fact_matrix") if isinstance(dossier, dict) else None
    if matrix is None:
        return None
    if not isinstance(matrix, dict) or matrix.get("schema_version") != "case_fact_matrix.v2":
        raise GraphContractError("TARGET_E2E_FIXTURE_FROZEN_INITIATOR_MATRIX_REQUIRED")
    return matrix


__all__ = [
    "TARGET_E2E_FIXTURE_MODEL",
    "TARGET_E2E_FIXTURE_PROVIDER",
    "TargetE2EDeterministicFixtureTransport",
    "build_target_e2e_fixture_transport",
]
