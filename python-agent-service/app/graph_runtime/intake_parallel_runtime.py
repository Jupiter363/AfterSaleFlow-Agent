"""Request-bound runtime helpers for the three physical Intake Frame graphs."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from langchain_core.runnables import RunnableConfig

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.checkpoint import (
    TechnicalChildCheckpointBinding,
    bind_fence_context,
    bind_technical_child_checkpoint,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graphs.intake.parallel_contracts import (
    FRAME_OUTPUT_SCHEMA,
    FRAME_PROMPT_PROFILE,
    FRAME_TYPES,
    ParallelFrameType,
)
from app.graphs.intake.parallel_graph import ParallelFrameExecutionRequest


PARALLEL_INTAKE_AGENT_PROFILE_ID = "dispute-intake-officer.parallel-frames.v1"
PARALLEL_INTAKE_OUTPUT_SCHEMA = "target-e2e-room-proposal-source.v2"
_MAX_COGNITIVE_REVISION = (1 << 63) - 1


def build_parallel_checkpoint_configs(
    execution: GatewayExecution,
    requests: Sequence[ParallelFrameExecutionRequest],
) -> Mapping[ParallelFrameType, RunnableConfig]:
    """Issue one hidden storage namespace per Frame under the admitted command fence.

    LangGraph must see the empty top-level checkpoint namespace.  The typed capability is
    interpreted only by :class:`FencedPostgresSaver`, which projects it onto a private physical
    namespace while retaining the command lease and Java room fence.
    """

    require_parallel_intake_execution(execution)
    if not requests:
        raise GraphContractError("parallel Intake checkpoint requests are empty")
    by_type: dict[ParallelFrameType, ParallelFrameExecutionRequest] = {}
    for request in requests:
        if not isinstance(request, ParallelFrameExecutionRequest):
            raise GraphContractError("parallel Intake checkpoint request is untyped")
        if request.frame_type in by_type:
            raise GraphContractError("parallel Intake checkpoint request repeats a Frame")
        by_type[request.frame_type] = request
    if not set(by_type) <= set(FRAME_TYPES):
        raise GraphContractError("parallel Intake checkpoint request has another Frame type")

    command = execution.admission.command
    fence = execution.fence
    record = execution.thread_record
    assert record is not None
    cognitive_revision = record.cognitive_revision + 1
    if cognitive_revision < 1 or cognitive_revision > _MAX_COGNITIVE_REVISION:
        raise GraphContractError("parallel Intake checkpoint revision is exhausted")

    frame_set_ids = {request.frame_set_id for request in requests}
    if len(frame_set_ids) != 1:
        raise GraphContractError("parallel Intake checkpoint requests cross Frame sets")
    shared_authorities = {
        (
            request.run_id,
            request.attempt_id,
            request.command_id,
            request.command_request_sha256,
            request.case_id,
            request.actor_id,
            request.actor_role,
            request.source_message_id,
            request.context_envelope_sha256,
        )
        for request in requests
    }
    if len(shared_authorities) != 1:
        raise GraphContractError("parallel Intake checkpoint requests cross turn authority")

    configs: dict[ParallelFrameType, RunnableConfig] = {}
    namespaces: set[str] = set()
    for frame_type in FRAME_TYPES:
        request = by_type.get(frame_type)
        if request is None:
            continue
        _require_request_binding(execution, request)
        authority_sha256 = canonical_sha256(
            {
                "contract_version": "intake.parallel-child-checkpoint-authority.v1",
                "checkpoint_identity": request.checkpoint_identity(),
                "thread_id": fence.thread_id,
                "owner_id": fence.owner_id,
                "fencing_token": fence.fencing_token,
                "request_hash": fence.request_hash,
                "room_epoch": fence.room_epoch,
                "cognitive_revision": cognitive_revision,
            }
        )
        checkpoint_ns = (
            f"intake.parallel.{frame_type.lower()}.{authority_sha256[:24]}"
        )
        if checkpoint_ns in namespaces:
            raise GraphContractError("parallel Intake checkpoint namespace collided")
        namespaces.add(checkpoint_ns)
        binding = TechnicalChildCheckpointBinding(
            frame_set_id=request.frame_set_id,
            run_id=request.run_id,
            attempt_id=request.attempt_id,
            frame_type=frame_type,
            generation=request.generation,
            frame_id=request.frame_id,
            checkpoint_ns=checkpoint_ns,
            authority_sha256=authority_sha256,
            cognitive_revision=cognitive_revision,
        )
        graph_config: RunnableConfig = {
            "configurable": {
                "thread_id": fence.thread_id,
                "checkpoint_ns": "",
            }
        }
        configs[frame_type] = bind_technical_child_checkpoint(
            bind_fence_context(graph_config, fence),
            binding,
        )
    return configs


def require_parallel_intake_execution(execution: GatewayExecution) -> None:
    if not isinstance(execution, GatewayExecution):
        raise GraphContractError("parallel Intake runtime requires GatewayExecution")
    command = execution.admission.command
    fence = execution.fence
    attempt = execution.attempt
    record = execution.thread_record
    invocation = command.invocation_context
    actor_scope = command.actor_scope
    if (
        fence.execution_lane is not GraphGatewayMode.TARGET_E2E_CANDIDATE
        or isinstance(fence.room_fencing_token, bool)
        or not isinstance(fence.room_fencing_token, int)
        or fence.room_fencing_token < 1
        or command.room_type != "INTAKE"
        or command.event_ref is None
        or invocation.agent_profile_id != PARALLEL_INTAKE_AGENT_PROFILE_ID
        or invocation.output_schema_version != PARALLEL_INTAKE_OUTPUT_SCHEMA
        or actor_scope.actor_role not in {"USER", "MERCHANT"}
        or actor_scope.audience != actor_scope.actor_role
    ):
        raise GraphContractError("execution is not an authorized parallel Intake turn")
    if (
        record is None
        or record.identity != execution.admission.thread
        or record.identity.thread_id != command.thread_id
        or record.identity.actor_scope.to_json()
        != actor_scope.model_dump(mode="json")
    ):
        raise GraphContractError("parallel Intake thread authority is absent")
    if (
        fence.thread_id != command.thread_id
        or fence.command_id != command.command_id
        or fence.request_hash != command.request_hash
        or fence.room_epoch != command.room_epoch
        or attempt.attempt_id != command.attempt_id
        or attempt.thread_id != command.thread_id
        or attempt.command_id != command.command_id
        or attempt.owner_id != fence.owner_id
        or attempt.fencing_token != fence.fencing_token
    ):
        raise GraphContractError("parallel Intake execution fence drifted")


def _require_request_binding(
    execution: GatewayExecution,
    request: ParallelFrameExecutionRequest,
) -> None:
    command = execution.admission.command
    actor_scope = command.actor_scope
    expected = (
        command.logical_run_id,
        command.attempt_id,
        command.command_id,
        command.request_hash,
        command.case_id,
        actor_scope.actor_id,
        actor_scope.actor_role,
    )
    actual = (
        request.run_id,
        request.attempt_id,
        request.command_id,
        request.command_request_sha256,
        request.case_id,
        request.actor_id,
        request.actor_role,
    )
    if actual != expected:
        raise GraphContractError("parallel Frame request differs from its execution")
    context = request.model_input.common_model_context
    current = context.current_user_message
    instruction_pack = request.model_input.instruction_pack
    if (
        current.source_role != request.actor_role
        or context.turn_route.source_type != "ROOM_MESSAGE"
        or context.turn_route.execution_profile != "PARALLEL_FRAMES"
        or instruction_pack.frame_type != request.frame_type
        or instruction_pack.prompt_profile_id
        != FRAME_PROMPT_PROFILE[request.frame_type]
        or instruction_pack.output_schema_id != FRAME_OUTPUT_SCHEMA[request.frame_type]
    ):
        raise GraphContractError("parallel Frame request lost its source binding")


__all__ = [
    "PARALLEL_INTAKE_AGENT_PROFILE_ID",
    "PARALLEL_INTAKE_OUTPUT_SCHEMA",
    "build_parallel_checkpoint_configs",
    "require_parallel_intake_execution",
]
