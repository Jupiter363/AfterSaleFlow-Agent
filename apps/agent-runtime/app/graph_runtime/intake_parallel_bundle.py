"""Deterministic production bundle for one exact-three parallel Intake turn."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.intake_parallel_context import (
    ParallelTurnModelMaterial,
    build_parallel_turn_model_material_from_command,
)
from app.graph_runtime.intake_parallel_runtime import require_parallel_intake_execution
from app.graph_runtime.identity import ThreadIdentity
from app.graphs.intake.parallel_contracts import (
    FRAME_OUTPUT_SCHEMA,
    FRAME_PROMPT_PROFILE,
    FRAME_TYPES,
    ParallelFrameType,
    build_instruction_pack,
)
from app.graphs.intake.parallel_graph import (
    FRAME_NODE_NAMES,
    ParallelFrameExecutionRequest,
)
from app.graphs.intake.state import IntakeTurnContext
from app.harness.invocation_context import AgentInvocationContext
from app.harness.prompt_composer import PromptRepository


@dataclass(frozen=True, slots=True)
class ParallelIntakeProductionBundle:
    frame_set_id: str
    material: ParallelTurnModelMaterial
    requests: tuple[
        ParallelFrameExecutionRequest,
        ParallelFrameExecutionRequest,
        ParallelFrameExecutionRequest,
    ]
    agent_contexts: Mapping[ParallelFrameType, AgentInvocationContext]

    def __post_init__(self) -> None:
        if (
            tuple(request.frame_type for request in self.requests) != FRAME_TYPES
            or set(self.agent_contexts) != set(FRAME_TYPES)
            or any(request.frame_set_id != self.frame_set_id for request in self.requests)
        ):
            raise GraphContractError("parallel Intake production bundle is not exact-three")


def build_parallel_intake_production_bundle(
    execution: GatewayExecution,
    *,
    snapshot_context: IntakeTurnContext,
    event_context: IntakeTurnContext,
    prompts: PromptRepository,
) -> ParallelIntakeProductionBundle:
    """Seal IDs, prompts, per-lane budgets, and model inputs from signed authority."""

    require_parallel_intake_execution(execution)
    if not isinstance(prompts, PromptRepository):
        raise GraphContractError("parallel Intake prompt repository is not production-owned")
    return _build_parallel_intake_bundle(
        command=execution.admission.command,
        thread=execution.admission.thread,
        room_fencing_token=execution.fence.room_fencing_token,
        snapshot_context=snapshot_context,
        event_context=event_context,
        prompts=prompts,
    )


def build_parallel_intake_prepared_bundle(
    command: RoomGraphCommand,
    *,
    thread: ThreadIdentity,
    room_fencing_token: int,
    snapshot_context: IntakeTurnContext,
    event_context: IntakeTurnContext,
    prompts: PromptRepository,
) -> ParallelIntakeProductionBundle:
    """Build the exact pre-provider authority without acquiring a Python execution lease."""

    if (
        not command.is_parallel_intake_command
        or command.thread_id != thread.thread_id
        or command.tenant_surrogate != thread.tenant_surrogate
        or command.case_id != thread.case_id
        or command.room_epoch != thread.room_epoch
        or command.graph_key != thread.graph_key
        or command.graph_version != thread.graph_version
        or command.checkpoint_schema_version != thread.checkpoint_schema_version
        or command.actor_scope.model_dump(mode="json") != thread.actor_scope.to_json()
    ):
        raise GraphContractError("parallel Intake prepared authority differs from its thread")
    return _build_parallel_intake_bundle(
        command=command,
        thread=thread,
        room_fencing_token=room_fencing_token,
        snapshot_context=snapshot_context,
        event_context=event_context,
        prompts=prompts,
    )


def _build_parallel_intake_bundle(
    *,
    command: RoomGraphCommand,
    thread: ThreadIdentity,
    room_fencing_token: int,
    snapshot_context: IntakeTurnContext,
    event_context: IntakeTurnContext,
    prompts: PromptRepository,
) -> ParallelIntakeProductionBundle:
    if not isinstance(prompts, PromptRepository):
        raise GraphContractError("parallel Intake prompt repository is not production-owned")
    instruction_packs = []
    for frame_type in FRAME_TYPES:
        node_name = FRAME_NODE_NAMES[frame_type]
        common_authority, frame_prompt = prompts.parallel_frame_instruction_sources(
            node_name
        )
        instruction_packs.append(
            build_instruction_pack(
                frame_type=frame_type,
                common_authority_prompt=common_authority,
                frame_prompt=frame_prompt,
            )
        )
    material = build_parallel_turn_model_material_from_command(
        command,
        thread=thread,
        room_fencing_token=room_fencing_token,
        snapshot_context=snapshot_context,
        event_context=event_context,
        instruction_packs=tuple(instruction_packs),
    )
    actor = command.actor_scope
    context_hash = material.context_envelope.context_envelope_sha256
    frame_set_id = "IFS_" + canonical_sha256(
        {
            "contract_version": "intake.parallel-frame-set-identity.v1",
            "thread_id": command.thread_id,
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "request_hash": command.request_hash,
            "context_envelope_sha256": context_hash,
        }
    )[:32]
    source_message_id = material.context_envelope.source_event.message_id
    requests = tuple(
        ParallelFrameExecutionRequest(
            frame_set_id=frame_set_id,
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            command_id=command.command_id,
            command_request_sha256=command.request_hash,
            case_id=command.case_id,
            actor_id=actor.actor_id,
            actor_role=actor.actor_role,
            source_message_id=source_message_id,
            context_envelope_sha256=context_hash,
            frame_type=model_input.frame_type,
            generation=1,
            frame_id=_frame_id(
                frame_set_id=frame_set_id,
                frame_type=model_input.frame_type,
                frame_model_input_sha256=model_input.frame_model_input_sha256,
            ),
            model_input=model_input,
        )
        for model_input in material.frame_inputs
    )
    provider_budgets = _allocate_provider_budgets(
        command.retry_budget.provider_attempts_remaining
    )
    contexts = {
        frame_type: _agent_context(
            command,
            thread,
            frame_type=frame_type,
            provider_attempts=provider_budgets[frame_type],
        )
        for frame_type in FRAME_TYPES
    }
    return ParallelIntakeProductionBundle(
        frame_set_id=frame_set_id,
        material=material,
        requests=requests,  # type: ignore[arg-type]
        agent_contexts=contexts,
    )


def _allocate_provider_budgets(total: int) -> Mapping[ParallelFrameType, int]:
    if isinstance(total, bool) or not 3 <= total <= 6:
        raise GraphContractError("parallel Intake aggregate provider budget is invalid")
    extra = total - len(FRAME_TYPES)
    result = {
        frame_type: 1 + (1 if index < extra else 0)
        for index, frame_type in enumerate(FRAME_TYPES)
    }
    if sum(result.values()) != total or any(value not in {1, 2} for value in result.values()):
        raise GraphContractError("parallel Intake provider budget allocation drifted")
    return result


def _frame_id(
    *,
    frame_set_id: str,
    frame_type: ParallelFrameType,
    frame_model_input_sha256: str,
) -> str:
    return "IFR_" + canonical_sha256(
        {
            "contract_version": "intake.parallel-frame-identity.v1",
            "frame_set_id": frame_set_id,
            "frame_type": frame_type,
            "generation": 1,
            "frame_model_input_sha256": frame_model_input_sha256,
        }
    )[:32]


def _agent_context(
    command: RoomGraphCommand,
    thread: ThreadIdentity,
    *,
    frame_type: ParallelFrameType,
    provider_attempts: int,
) -> AgentInvocationContext:
    actor = command.actor_scope
    invocation = command.invocation_context
    permission_level = "PARTY_USER" if actor.actor_role == "USER" else "PARTY_MERCHANT"
    access_session_id = f"ACCESS_{thread.actor_scope_hash[:32]}"
    invocation_id = "IFV_" + canonical_sha256(
        {
            "attempt_id": command.attempt_id,
            "frame_type": frame_type,
            "frame_set_authority": command.request_hash,
        }
    )[:32]
    conversation_scope = ":".join(
        (
            command.tenant_surrogate,
            command.case_id,
            "INTAKE",
            actor.actor_id,
            actor.actor_role,
            command.attempt_id,
            frame_type,
        )
    )
    return AgentInvocationContext.model_validate(
        {
            "tenant_id": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_type": "INTAKE",
            "actor_id": actor.actor_id,
            "actor_role": actor.actor_role,
            "access_session_id": access_session_id,
            "permission_level": permission_level,
            "permission_scopes": sorted(actor.capabilities),
            "agent_key": invocation.agent_profile_id,
            "agent_invocation_id": invocation_id,
            "agent_session_id": thread.agent_session_id,
            "conversation_scope": conversation_scope,
            "scope_type": "INTAKE_PARTY_PRIVATE",
            "allowed_actor_ids": [actor.actor_id],
            "allowed_actor_roles": [actor.actor_role],
            "prompt_profile_id": FRAME_PROMPT_PROFILE[frame_type],
            "memory_policy_id": "INTAKE_PARALLEL_MEMORY_V1",
            "model_profile_id": invocation.model_profile_id,
            "output_schema_version": FRAME_OUTPUT_SCHEMA[frame_type],
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_capabilities": [],
            "retry_budget": {
                "provider_attempts_remaining": provider_attempts,
                "activity_attempts_remaining": 0,
                "repairs_remaining": 1 if provider_attempts == 2 else 0,
            },
            "deadline_at": command.deadline_at,
            "traceparent": command.traceparent,
        }
    )


__all__ = [
    "ParallelIntakeProductionBundle",
    "build_parallel_intake_prepared_bundle",
    "build_parallel_intake_production_bundle",
]
