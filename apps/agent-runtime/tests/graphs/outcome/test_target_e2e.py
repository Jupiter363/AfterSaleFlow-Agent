from __future__ import annotations

from inspect import signature
from types import SimpleNamespace

import pytest

from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.registry import VersionBinding
from app.graph_runtime.target_e2e_composite import (
    TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
    TARGET_E2E_GRAPH_KEY,
    TARGET_E2E_GRAPH_VERSION,
    TARGET_E2E_OUTPUT_SCHEMA_VERSION,
)
from app.graphs.outcome.contracts import OUTCOME_REVIEW_IDENTITY
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.state import new_outcome_review_state, version_pins
from app.graphs.outcome.target_e2e import (
    DeterministicOutcomeTargetE2EModel,
    OutcomeTargetE2EExecutionContext,
    OutcomeTargetE2EProposalPayload,
    build_outcome_target_e2e_proposal_source,
    require_exact_outcome_target_e2e_registry_binding,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


def _registry_binding(**updates: str) -> VersionBinding:
    values = {
        "graph_key": TARGET_E2E_GRAPH_KEY,
        "graph_version": TARGET_E2E_GRAPH_VERSION,
        "checkpoint_schema_version": TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
        "state_schema_version": "target-e2e-composite-state.v1",
        "state_schema_hash": "1" * 64,
        "command_schema_version": "room-graph-command.v1",
        "result_schema_version": "room-graph-result.v1",
        "prompt_version": "target-e2e.all-rooms.prompt.v1",
        "model_profile_id": "target-e2e.all-rooms.model.v1",
        "output_schema_version": TARGET_E2E_OUTPUT_SCHEMA_VERSION,
        "policy_version": "target-e2e.proposal-only.v1",
        "guardrail_version": "target-e2e.guardrails.v1",
        "tool_policy_version": "target-e2e.no-tools.v1",
        "binding_hash": "2" * 64,
        "code_build_id": "p9-review-build-1",
    }
    values.update(updates)
    return VersionBinding(**values)


def _graph_command() -> RoomGraphCommand:
    payload = {
        "schema_version": "room-graph-command.v1",
        "command_id": "command-review-001",
        "logical_run_id": "run-review-001",
        "attempt_id": "attempt-review-001",
        "tenant_surrogate": "tenant-p9",
        "case_id": "CASE_P9_SYNTHETIC_1",
        "room_type": "REVIEW",
        "room_epoch": 4,
        "graph_key": TARGET_E2E_GRAPH_KEY,
        "graph_version": TARGET_E2E_GRAPH_VERSION,
        "checkpoint_schema_version": TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
        "thread_id": "grt.v1.0123456789abcdef0123456789abcdef",
        "actor_scope": {
            "actor_id": "reviewer-p9",
            "actor_role": "PLATFORM_REVIEWER",
            "audience": "PLATFORM_REVIEWER",
            "capabilities": [],
        },
        "process_revision": 17,
        "stage_code": "REVIEW_COPILOT",
        "stage_sequence": 29,
        "domain_snapshot_ref": {
            "artifact_id": "review-packet-001",
            "schema_version": "review-packet.v1",
            "uri": "urn:target-e2e:review-packet:001",
            "sha256": "3" * 64,
            "size_bytes": 1024,
        },
        "event_ref": {
            "artifact_id": "review-question-001",
            "schema_version": "review-question.v1",
            "uri": "urn:target-e2e:review-question:001",
            "sha256": "4" * 64,
            "size_bytes": 256,
        },
        "invocation_context": {
            "agent_profile_id": "outcome.review.agent.v1",
            "prompt_profile_id": "target-e2e.all-rooms.prompt.v1",
            "model_profile_id": "target-e2e.all-rooms.model.v1",
            "output_schema_version": TARGET_E2E_OUTPUT_SCHEMA_VERSION,
            "policy_version": "target-e2e.proposal-only.v1",
            "guardrail_version": "target-e2e.guardrails.v1",
            "tool_capabilities": [],
            "envelope_key_id": "target-key-1",
            "envelope_nonce": "target-nonce-1",
        },
        "retry_budget": {
            "provider_attempts_remaining": 0,
            "activity_attempts_remaining": 1,
            "repairs_remaining": 0,
        },
        "deadline_at": "2026-07-28T01:00:00Z",
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "request_hash": "0" * 64,
    }
    payload["request_hash"] = canonical_sha256_omitting(payload, "request_hash")
    return RoomGraphCommand.model_validate(payload)


def _target_execution(command: RoomGraphCommand) -> GatewayExecution:
    registry = _registry_binding()
    command_hash = canonical_sha256(command.model_dump(mode="json", exclude_none=True))
    activation_id = "p9act.v1." + "a" * 32
    envelope_hash = "3" * 64
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="owner-review-1",
        fencing_token=3,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=activation_id,
        room_fencing_token=5,
        command_hash=command_hash,
        command_envelope_hash=envelope_hash,
        environment_id="target-e2e-review",
        environment_generation=1,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=command.room_type,
        binding_hash=registry.binding_hash,
        code_build_id=registry.code_build_id,
    )
    return GatewayExecution(
        admission=SimpleNamespace(
            command=command,
            binding=SimpleNamespace(
                execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
                activation_id=activation_id,
                room_fencing_token=5,
                command_hash=command_hash,
                command_envelope_hash=envelope_hash,
            ),
            registry=SimpleNamespace(binding=registry),
            candidate_authority=SimpleNamespace(activation_id=activation_id),
        ),
        attempt=None,  # type: ignore[arg-type]
        lease=None,  # type: ignore[arg-type]
        fence=fence,
    )


def test_registry_binding_requires_the_unified_outer_target_e2e_binding() -> None:
    binding = _registry_binding()

    require_exact_outcome_target_e2e_registry_binding(binding)

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_TARGET_E2E_REGISTRY_BINDING_MISMATCH",
    ):
        require_exact_outcome_target_e2e_registry_binding(
            _registry_binding(graph_key=OUTCOME_REVIEW_IDENTITY.gateway_graph_key)
        )


def test_target_context_accepts_the_unified_outer_binding() -> None:
    context = OutcomeTargetE2EExecutionContext.from_gateway_execution(
        _target_execution(_graph_command())
    )

    assert context.registry_binding_hash == "2" * 64
    assert context.code_build_id == "p9-review-build-1"


def test_target_context_rejects_a_room_specific_outer_binding() -> None:
    command = _graph_command().model_copy(
        update={"graph_key": OUTCOME_REVIEW_IDENTITY.gateway_graph_key}
    )
    payload = command.model_dump(mode="json")
    payload["request_hash"] = canonical_sha256_omitting(payload, "request_hash")
    room_specific = RoomGraphCommand.model_validate(payload)

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_TARGET_E2E_COMMAND_BINDING_MISMATCH",
    ):
        OutcomeTargetE2EExecutionContext.from_gateway_execution(
            _target_execution(room_specific)
        )


def test_private_outcome_version_pins_remain_strict(
    private_command,
    review_request: ReviewCopilotRequest,
) -> None:
    drifted = private_command.model_copy(
        update={
            "version_pins": {
                **version_pins(),
                "prompt_version": "target-e2e.all-rooms.prompt.v1",
            }
        }
    )

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_REVIEW_VERSION_PINS_MISMATCH",
    ):
        new_outcome_review_state(command=drifted, request=review_request)


def test_normalized_review_proposal_uses_only_proposal_as_hash_source() -> None:
    command = _graph_command()
    source = build_outcome_target_e2e_proposal_source(
        command=command,
        proposal_id="proposal-review-001",
        payload_ref="urn:target-e2e:proposal:review:001",
        payload_hash="5" * 64,
    )

    assert source.room_type == "REVIEW"
    assert source.proposal.schema_version == "target-e2e-review-proposal.v1"
    assert source.proposal.terminal_class == "NEEDS_REVIEW"
    assert source.proposal.formal_authority is False
    assert source.proposal_hash == canonical_sha256(source.proposal.model_dump(mode="json"))
    assert source.proposal_hash != canonical_sha256(source.model_dump(mode="json"))


def test_deterministic_fixture_model_and_payload_hash_are_stable(
    review_request: ReviewCopilotRequest,
) -> None:
    model = DeterministicOutcomeTargetE2EModel()
    first = model(review_request)
    second = model(review_request)
    payload = OutcomeTargetE2EProposalPayload(
        review_task_id=review_request.review_id,
        packet_id="packet-review-001",
        advisory_hash=canonical_sha256(first.model_dump(mode="json")),
        citation_refs=tuple(
            sorted(
                {
                    *first.fact_refs,
                    *first.rule_refs,
                    *first.draft_refs,
                    *first.deliberation_refs,
                }
            )
        ),
        answer=first,
    )

    assert first == second
    assert canonicalize(payload.model_dump(mode="json")) == canonicalize(
        payload.model_dump(mode="json")
    )
    assert payload.formal_sink_eligible is False
    assert payload.formal_authority is False
    assert payload.external_effects_enabled is False
    assert payload.tools_enabled is False
    assert first.approval_performed is False
    assert first.execution_triggered is False
    assert first.is_final_decision is False


def test_target_context_cannot_be_built_from_unverified_shadow_execution() -> None:
    command = _graph_command()
    registry = _registry_binding()
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="owner-review-1",
        fencing_token=3,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    execution = GatewayExecution(
        admission=SimpleNamespace(
            command=command,
            binding=SimpleNamespace(execution_lane="SHADOW"),
            registry=SimpleNamespace(binding=registry),
        ),
        attempt=None,  # type: ignore[arg-type]
        lease=None,  # type: ignore[arg-type]
        fence=fence,
    )

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_TARGET_E2E_GATEWAY_AUTHORITY_REQUIRED",
    ):
        OutcomeTargetE2EExecutionContext.from_gateway_execution(execution)


def test_target_context_api_has_no_activation_token_or_jws_parameter() -> None:
    parameters = signature(
        OutcomeTargetE2EExecutionContext.from_gateway_execution
    ).parameters

    assert tuple(parameters) == ("execution",)
    assert all("token" not in name.lower() and "jws" not in name.lower() for name in parameters)


def test_payload_schema_forbids_any_formal_authority(
    review_answer: ReviewCopilotAnswer,
) -> None:
    values = {
        "review_task_id": "review-1",
        "packet_id": "packet-1",
        "advisory_hash": "6" * 64,
        "citation_refs": (),
        "answer": review_answer,
        "formal_sink_eligible": True,
    }

    with pytest.raises(ValueError):
        OutcomeTargetE2EProposalPayload.model_validate(values)
