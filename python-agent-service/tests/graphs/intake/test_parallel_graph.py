from __future__ import annotations

import asyncio
import json
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import RoomGraphCommand
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.parallel_contracts import (
    FRAME_OUTPUT_SCHEMA,
    FRAME_PROMPT_PROFILE,
    FRAME_TYPES,
    IntakeAuthorityRefV1,
    IntakeCaseRefV1,
    IntakeFrameInstructionPackV1,
    IntakeModelContextViewV1,
    IntakeSourceEventRefV1,
    build_frame_model_inputs,
    build_instruction_pack,
    build_parallel_context_envelope,
)
from app.graphs.intake.parallel_graph import (
    FrameGenerationReset,
    FrameInterrupted,
    FrameProjectionItem,
    FrameSealed,
    FrameStarted,
    ParallelFrameExecutionRequest,
    ParallelIntakeFrameOrchestrator,
    build_parallel_frame_graph,
    compile_parallel_frame_graphs,
)
from app.graph_runtime.checkpoint import (
    TECHNICAL_CHILD_CHECKPOINT_CONTEXT_KEY,
    TechnicalChildCheckpointBinding,
    bind_technical_child_checkpoint,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import (
    ActorScopeBinding,
    RoomType,
    ThreadIdentity,
    ThreadLifecycle,
    ThreadRecord,
)
from app.graph_runtime.intake_parallel_runtime import (
    PARALLEL_INTAKE_AGENT_PROFILE_ID,
    PARALLEL_INTAKE_OUTPUT_SCHEMA,
    build_parallel_checkpoint_configs,
    build_parallel_technical_completion,
)
from app.graph_runtime.intake_parallel_context import (
    build_parallel_turn_model_material,
)
from app.graph_runtime.lease import LeaseRecord
from app.graph_runtime.ledger import AttemptRecord, AttemptStatus
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graphs.intake.state import IntakeTurnContext
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import (
    HarnessGeneration,
    HarnessStreamCompleted,
    HarnessStreamDelta,
    HarnessStreamReset,
)


ROOT = Path(__file__).resolve().parents[4]
PROMPT_ROOT = (
    ROOT
    / "python-agent-service"
    / "app"
    / "agents"
    / "prompts"
    / "dispute_intake_officer"
)


class _CollectingSink:
    def __init__(self) -> None:
        self.events: list[Any] = []
        self.first_projection = asyncio.Event()

    async def emit(self, event: Any) -> None:
        self.events.append(event)
        if isinstance(event, FrameProjectionItem):
            self.first_projection.set()


class _StreamingRunner:
    def __init__(
        self,
        outputs: dict[str, dict[str, Any]],
        *,
        release: asyncio.Event | None = None,
        reset_node: str | None = None,
        fail_node: str | None = None,
    ) -> None:
        self.outputs = outputs
        self.release = release
        self.reset_node = reset_node
        self.fail_node = fail_node
        self.calls: list[dict[str, Any]] = []

    async def ainvoke_structured_stream(self, **kwargs: Any):
        self.calls.append(kwargs)
        node_name = kwargs["node_name"]
        if node_name == self.fail_node:
            raise RuntimeError("private provider failure")
        value = self.outputs[node_name]
        items = value["public_projection_items"]
        if node_name == self.reset_node:
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="public_projection_items",
                delta=_json(items[0]),
            )
            yield HarnessStreamReset(
                kind="generation_reset",
                generation=2,
                reason_code="OUTPUT_SCHEMA_INVALID",
            )
        if self.release is not None:
            if node_name == "intake_turn_dialogue_frame":
                yield HarnessStreamDelta(
                    kind="visible_delta",
                    field="public_projection_items",
                    delta=_json(items[0]),
                )
                await self.release.wait()
                items = items[1:]
            else:
                await self.release.wait()
        for item in items:
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="public_projection_items",
                delta=_json(item),
            )
        output_type = kwargs["output_type"]
        yield HarnessStreamCompleted(
            kind="completed",
            generation=HarnessGeneration(
                value=output_type.model_validate(value),
                model="qwen3.7-max-2026-06-08",
                latency_ms=12,
                token_usage={"input": 10, "output": 5, "total": 15},
                context=None,
                messages=(),
            ),
        )


@pytest.mark.asyncio
async def test_three_physical_graphs_stream_independently_before_fan_in() -> None:
    saver = InMemorySaver()
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(checkpointer=saver)
    )
    requests, contexts = _requests_and_contexts()
    release = asyncio.Event()
    sink = _CollectingSink()
    runner = _StreamingRunner(_outputs(), release=release)

    task = asyncio.create_task(
        orchestrator.execute(
            requests,
            agent_contexts=contexts,
            model_runner=runner,
            event_sink=sink,
        )
    )
    await asyncio.wait_for(sink.first_projection.wait(), timeout=1)

    assert not task.done()
    first_projection = next(
        event for event in sink.events if isinstance(event, FrameProjectionItem)
    )
    assert first_projection.frame_type == "DIALOGUE_FRAME"
    assert not any(isinstance(event, FrameSealed) for event in sink.events)

    release.set()
    result = await asyncio.wait_for(task, timeout=2)

    assert result.all_succeeded
    assert set(result.completed) == set(FRAME_TYPES)
    assert len(runner.calls) == 3
    assert {call["node_name"] for call in runner.calls} == {
        "intake_turn_dialogue_frame",
        "intake_turn_dossier_frame",
        "intake_turn_quality_frame",
    }
    assert len({item.child_checkpoint_ref for item in result.completed.values()}) == 3
    assert all("checkpoint_id" not in request.model_input.model_dump_json()
               for request in requests)
    assert len([event for event in sink.events if isinstance(event, FrameSealed)]) == 3

    completion = build_parallel_technical_completion(
        _parallel_execution(requests),
        frame_set_id=requests[0].frame_set_id,
        events=sink.events,
        batch_result=result,
    )
    assert completion.completion_json["frame_set_id"] == requests[0].frame_set_id
    assert [frame["frame_type"] for frame in completion.completion_json["frames"]] == list(
        FRAME_TYPES
    )
    assert completion.canonical_json_text()


@pytest.mark.asyncio
async def test_external_checkpoint_configs_keep_three_children_in_distinct_namespaces() -> None:
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(
            checkpointer={frame_type: InMemorySaver() for frame_type in FRAME_TYPES}
        )
    )
    requests, contexts = _requests_and_contexts()
    checkpoint_configs = {
        frame_type: bind_technical_child_checkpoint(
            {
                "configurable": {
                    "thread_id": "grt.v1." + "1" * 32,
                }
            },
            TechnicalChildCheckpointBinding(
                frame_set_id=request.frame_set_id,
                run_id=request.run_id,
                attempt_id=request.attempt_id,
                frame_type=frame_type,
                generation=request.generation,
                frame_id=request.frame_id,
                checkpoint_ns=f"intake.parallel.{frame_type.lower()}",
                authority_sha256="9" * 64,
                cognitive_revision=1,
            ),
        )
        for frame_type, request in (
            (request.frame_type, request) for request in requests
        )
    }

    result = await orchestrator.execute(
        requests,
        agent_contexts=contexts,
        model_runner=_StreamingRunner(_outputs()),
        event_sink=_CollectingSink(),
        checkpoint_configs=checkpoint_configs,
    )

    assert result.all_succeeded
    refs = {
        frame_type: item.child_checkpoint_ref
        for frame_type, item in result.completed.items()
    }
    assert len(set(refs.values())) == 3
    for frame_type, ref in refs.items():
        assert f"intake.parallel.{frame_type.lower()}" in ref


@pytest.mark.asyncio
async def test_parallel_children_reject_shared_checkpoint_namespace() -> None:
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(checkpointer=InMemorySaver())
    )
    requests, contexts = _requests_and_contexts()
    shared = {
        frame_type: bind_technical_child_checkpoint(
            {"configurable": {"thread_id": "grt.v1." + "1" * 32}},
            TechnicalChildCheckpointBinding(
                frame_set_id=request.frame_set_id,
                run_id=request.run_id,
                attempt_id=request.attempt_id,
                frame_type=frame_type,
                generation=request.generation,
                frame_id=request.frame_id,
                checkpoint_ns="intake.parallel.shared",
                authority_sha256="9" * 64,
                cognitive_revision=1,
            ),
        )
        for frame_type, request in (
            (request.frame_type, request) for request in requests
        )
    }

    with pytest.raises(ValueError, match="cannot share"):
        await orchestrator.execute(
            requests,
            agent_contexts=contexts,
            model_runner=_StreamingRunner(_outputs()),
            event_sink=_CollectingSink(),
            checkpoint_configs=shared,
        )


def test_checkpoint_config_issuer_binds_exact_execution_and_three_private_namespaces() -> None:
    requests, _ = _requests_and_contexts()
    execution = _parallel_execution(requests)

    configs = build_parallel_checkpoint_configs(execution, requests)

    assert set(configs) == set(FRAME_TYPES)
    bindings = {
        frame_type: config["configurable"][
            TECHNICAL_CHILD_CHECKPOINT_CONTEXT_KEY
        ]
        for frame_type, config in configs.items()
    }
    assert all(
        config["configurable"]["checkpoint_ns"] == ""
        for config in configs.values()
    )
    assert all(
        config["configurable"]["thread_id"] == execution.fence.thread_id
        for config in configs.values()
    )
    assert len({binding.checkpoint_ns for binding in bindings.values()}) == 3
    assert all(binding.cognitive_revision == 9 for binding in bindings.values())
    assert all(
        binding.frame_type == frame_type for frame_type, binding in bindings.items()
    )

    drifted = list(requests)
    drifted[0] = drifted[0].model_copy(
        update={"command_request_sha256": "c" * 64}
    )
    with pytest.raises(GraphContractError, match="cross turn authority"):
        build_parallel_checkpoint_configs(execution, tuple(drifted))


def test_frozen_parallel_ingress_projects_one_shared_model_context_for_three_frames() -> None:
    requests, _ = _requests_and_contexts()
    execution = _parallel_execution(requests)
    snapshot, event, execution = _parallel_ingress(execution)

    material = build_parallel_turn_model_material(
        execution,
        room_id="ROOM_PARALLEL_1",
        snapshot_context=IntakeTurnContext("SNAPSHOT", snapshot),
        event_context=IntakeTurnContext("EVENT", event),
        instruction_packs=_instruction_packs(),
    )
    replay = build_parallel_turn_model_material(
        execution,
        room_id="ROOM_PARALLEL_1",
        snapshot_context=IntakeTurnContext("SNAPSHOT", snapshot),
        event_context=IntakeTurnContext("EVENT", event),
        instruction_packs=_instruction_packs(),
    )

    assert material == replay
    assert len(material.frame_inputs) == 3
    assert {
        item.common_model_context.model_context_view_sha256
        for item in material.frame_inputs
    } == {material.model_context.model_context_view_sha256}
    assert material.model_context.source_capacity.litigation_capacity == "INITIATOR"
    assert material.model_context.current_action_binding.action == "ASK_SUBSTANTIVE"
    assert material.model_context.current_user_message.text == "商品于昨日签收。"
    assert len(material.model_context.authorized_question_slots) == 1
    assert material.model_context.authorized_question_slots[0].canonical_text == (
        "请说明签收时间。"
    )
    assert [message.sequence for message in material.model_context.recent_dialogue_messages] == [0]
    matrix = material.model_context.frozen_case_matrix.payload
    assert "case_id" not in matrix
    assert "message_id" not in json.dumps(matrix, ensure_ascii=False)

    drifted = dict(snapshot)
    drifted["snapshot_hash"] = "f" * 64
    with pytest.raises(GraphContractError, match="differs from command authority"):
        build_parallel_turn_model_material(
            execution,
            room_id="ROOM_PARALLEL_1",
            snapshot_context=IntakeTurnContext("SNAPSHOT", drifted),
            event_context=IntakeTurnContext("EVENT", event),
            instruction_packs=_instruction_packs(),
        )


@pytest.mark.asyncio
async def test_one_lane_reset_does_not_change_sibling_generation() -> None:
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(checkpointer=InMemorySaver())
    )
    requests, contexts = _requests_and_contexts()
    sink = _CollectingSink()
    runner = _StreamingRunner(
        _outputs(),
        reset_node="intake_turn_dialogue_frame",
    )

    result = await orchestrator.execute(
        requests,
        agent_contexts=contexts,
        model_runner=runner,
        event_sink=sink,
    )

    assert result.all_succeeded
    assert result.completed["DIALOGUE_FRAME"].generation == 2
    assert result.completed["DOSSIER_FRAME"].generation == 1
    assert result.completed["QUALITY_FRAME"].generation == 1
    resets = [event for event in sink.events if isinstance(event, FrameGenerationReset)]
    assert len(resets) == 1
    assert resets[0].frame_type == "DIALOGUE_FRAME"
    assert resets[0].new_generation == 2
    interruption_index = next(
        index
        for index, event in enumerate(sink.events)
        if isinstance(event, FrameInterrupted)
        and event.frame_type == "DIALOGUE_FRAME"
    )
    reset_index = sink.events.index(resets[0])
    replacement_start_index = next(
        index
        for index, event in enumerate(sink.events)
        if isinstance(event, FrameStarted)
        and event.frame_type == "DIALOGUE_FRAME"
        and event.generation == 2
    )
    interruption = sink.events[interruption_index]
    assert isinstance(interruption, FrameInterrupted)
    assert interruption.generation == 1
    assert interruption.error_code == "OUTPUT_SCHEMA_INVALID"
    assert interruption.retryable
    assert interruption_index < reset_index < replacement_start_index
    dialogue_items = [
        event
        for event in sink.events
        if isinstance(event, FrameProjectionItem)
        and event.frame_type == "DIALOGUE_FRAME"
    ]
    assert [event.local_index for event in dialogue_items] == [0, 0]


@pytest.mark.asyncio
async def test_complete_checkpoint_replays_only_missing_prefix_without_provider() -> None:
    saver = InMemorySaver()
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(checkpointer=saver)
    )
    requests, contexts = _requests_and_contexts()
    quality_request = next(
        request for request in requests if request.frame_type == "QUALITY_FRAME"
    )
    first_sink = _CollectingSink()
    runner = _StreamingRunner(_outputs())

    first = await orchestrator.execute_frame(
        quality_request,
        agent_context=contexts["QUALITY_FRAME"],
        model_runner=runner,
        event_sink=first_sink,
    )
    replay_request = quality_request.model_copy(
        update={"resume_local_index": 2, "emit_start": False}
    )
    replay_sink = _CollectingSink()
    replay = await orchestrator.execute_frame(
        replay_request,
        agent_context=contexts["QUALITY_FRAME"],
        model_runner=runner,
        event_sink=replay_sink,
    )

    assert not first.replayed_from_checkpoint
    assert replay.replayed_from_checkpoint
    assert len(runner.calls) == 1
    replay_items = [
        event for event in replay_sink.events if isinstance(event, FrameProjectionItem)
    ]
    assert [event.local_index for event in replay_items] == [2, 3, 4, 5]
    assert isinstance(replay_sink.events[-1], FrameSealed)
    assert replay.result_sha256 == first.result_sha256
    assert replay.child_checkpoint_ref == first.child_checkpoint_ref


@pytest.mark.asyncio
async def test_reset_checkpoint_replays_transition_before_replacement_generation() -> None:
    saver = InMemorySaver()
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(checkpointer=saver)
    )
    requests, contexts = _requests_and_contexts()
    dialogue_request = next(
        request for request in requests if request.frame_type == "DIALOGUE_FRAME"
    )
    runner = _StreamingRunner(
        _outputs(),
        reset_node="intake_turn_dialogue_frame",
    )
    first_sink = _CollectingSink()
    first = await orchestrator.execute_frame(
        dialogue_request,
        agent_context=contexts["DIALOGUE_FRAME"],
        model_runner=runner,
        event_sink=first_sink,
    )

    replay_sink = _CollectingSink()
    replay = await orchestrator.execute_frame(
        dialogue_request.model_copy(
            update={"resume_local_index": 1, "emit_start": False}
        ),
        agent_context=contexts["DIALOGUE_FRAME"],
        model_runner=runner,
        event_sink=replay_sink,
    )

    assert replay.replayed_from_checkpoint
    assert len(runner.calls) == 1
    assert [type(event) for event in replay_sink.events] == [
        FrameInterrupted,
        FrameGenerationReset,
        FrameStarted,
        FrameProjectionItem,
        FrameSealed,
    ]
    assert replay_sink.events[0].generation == dialogue_request.generation
    assert replay_sink.events[2].generation == first.generation
    assert replay_sink.events[3].local_index == 0

    terminal_sink = _CollectingSink()
    terminal_replay = await orchestrator.execute_frame(
        dialogue_request.model_copy(
            update={
                "resume_generation": first.generation,
                "resume_frame_id": first.frame_id,
                "resume_local_index": 1,
                "emit_start": False,
            }
        ),
        agent_context=contexts["DIALOGUE_FRAME"],
        model_runner=runner,
        event_sink=terminal_sink,
    )

    assert terminal_replay.replayed_from_checkpoint
    assert len(runner.calls) == 1
    assert [type(event) for event in terminal_sink.events] == [FrameSealed]


@pytest.mark.asyncio
async def test_failed_lane_isolated_while_siblings_checkpoint_and_seal() -> None:
    orchestrator = ParallelIntakeFrameOrchestrator(
        compile_parallel_frame_graphs(checkpointer=InMemorySaver())
    )
    requests, contexts = _requests_and_contexts()
    sink = _CollectingSink()
    runner = _StreamingRunner(
        _outputs(),
        fail_node="intake_turn_dossier_frame",
    )

    result = await orchestrator.execute(
        requests,
        agent_contexts=contexts,
        model_runner=runner,
        event_sink=sink,
    )

    assert set(result.completed) == {"DIALOGUE_FRAME", "QUALITY_FRAME"}
    assert set(result.failed) == {"DOSSIER_FRAME"}
    assert result.failed["DOSSIER_FRAME"].error_code == (
        "INTAKE_PARALLEL_FRAME_EXECUTION_FAILED"
    )
    interruptions = [
        event for event in sink.events if isinstance(event, FrameInterrupted)
    ]
    assert len(interruptions) == 1
    assert interruptions[0].frame_type == "DOSSIER_FRAME"
    assert "private provider failure" not in interruptions[0].model_dump_json()
    assert {
        event.frame_type for event in sink.events if isinstance(event, FrameSealed)
    } == {"DIALOGUE_FRAME", "QUALITY_FRAME"}


@pytest.mark.asyncio
async def test_same_generation_incomplete_checkpoint_fails_closed() -> None:
    saver = InMemorySaver()
    graphs = dict(compile_parallel_frame_graphs(checkpointer=saver))
    graphs["DIALOGUE_FRAME"] = build_parallel_frame_graph(
        "DIALOGUE_FRAME"
    ).compile(
        checkpointer=saver,
        interrupt_before=["invoke_model"],
    )
    orchestrator = ParallelIntakeFrameOrchestrator(graphs)
    requests, contexts = _requests_and_contexts()
    request = next(
        item for item in requests if item.frame_type == "DIALOGUE_FRAME"
    )
    sink = _CollectingSink()
    runner = _StreamingRunner(_outputs())

    with pytest.raises(IntakeGraphContractError):
        await orchestrator.execute_frame(
            request,
            agent_context=contexts["DIALOGUE_FRAME"],
            model_runner=runner,
            event_sink=sink,
        )
    with pytest.raises(
        IntakeGraphContractError,
        match="INTAKE_PARALLEL_FRAME_CHECKPOINT_INCOMPLETE",
    ):
        await orchestrator.execute_frame(
            request,
            agent_context=contexts["DIALOGUE_FRAME"],
            model_runner=runner,
            event_sink=sink,
        )
    assert runner.calls == []


def _requests_and_contexts() -> tuple[
    tuple[ParallelFrameExecutionRequest, ...],
    dict[str, AgentInvocationContext],
]:
    model_context = _model_context()
    context_envelope = _context_envelope(model_context)
    model_inputs = build_frame_model_inputs(
        context_envelope=context_envelope,
        common_model_context=model_context,
        instruction_packs=_instruction_packs(),
    )
    requests = tuple(
        ParallelFrameExecutionRequest(
            frame_set_id="FRAME_SET_PARALLEL_1",
            run_id="RUN_PARALLEL_1",
            attempt_id="ATTEMPT_PARALLEL_1",
            command_id="COMMAND_PARALLEL_1",
            command_request_sha256="b" * 64,
            case_id="CASE_PARALLEL_1",
            actor_id="user-local",
            actor_role="USER",
            source_message_id="MESSAGE_PARALLEL_1",
            context_envelope_sha256=context_envelope.context_envelope_sha256,
            frame_type=model_input.frame_type,
            generation=1,
            frame_id=f"intake.frame.{model_input.frame_type.lower()}.1",
            model_input=model_input,
        )
        for model_input in model_inputs
    )
    contexts = {
        frame_type: AgentInvocationContext.model_validate(
            {
                "tenant_id": "tenant-local",
                "case_id": "CASE_PARALLEL_1",
                "room_type": "INTAKE",
                "actor_id": "user-local",
                "actor_role": "USER",
                "access_session_id": "ACCESS_PARALLEL_1",
                "permission_level": "PARTY_USER",
                "permission_scopes": ["INTAKE_ROOM_WRITE"],
                "agent_key": "DISPUTE_INTAKE_OFFICER",
                "agent_invocation_id": f"INVOCATION_{frame_type}",
                "agent_session_id": "SESSION_PARALLEL_1",
                "conversation_scope": "case:CASE_PARALLEL_1:intake:user-local",
                "scope_type": "INTAKE_INITIATOR_PRIVATE",
                "allowed_actor_ids": ["user-local"],
                "allowed_actor_roles": ["USER"],
                "prompt_profile_id": FRAME_PROMPT_PROFILE[frame_type],
                "memory_policy_id": "INTAKE_MEMORY_V1",
                "model_profile_id": "qwen3.7-max-2026-06-08",
                "output_schema_version": FRAME_OUTPUT_SCHEMA[frame_type],
                "policy_version": "INTAKE_PARALLEL_V1",
                "guardrail_version": "INTAKE_PARALLEL_V1",
            }
        )
        for frame_type in FRAME_TYPES
    }
    return requests, contexts


def _parallel_execution(
    requests: tuple[ParallelFrameExecutionRequest, ...],
) -> GatewayExecution:
    request = requests[0]
    fixture = json.loads(
        (
            ROOT
            / "contracts"
            / "agent-platform"
            / "v1"
            / "fixtures"
            / "valid"
            / "room-graph-command-valid.json"
        ).read_text(encoding="utf-8")
    )["instance"]
    fixture.update(
        {
            "command_id": request.command_id,
            "logical_run_id": request.run_id,
            "attempt_id": request.attempt_id,
            "case_id": request.case_id,
            "thread_id": "grt.v1." + "1" * 32,
            "actor_scope": {
                "actor_id": request.actor_id,
                "actor_role": request.actor_role,
                "audience": request.actor_role,
                "capabilities": ["INTAKE_ROOM_WRITE"],
            },
            "event_ref": {
                "artifact_id": "intake.event.parallel-1",
                "schema_version": "intake-turn-event.v2",
                "uri": "urn:intake:event:parallel-1",
                "sha256": "e" * 64,
                "size_bytes": 256,
            },
            "request_hash": request.command_request_sha256,
        }
    )
    fixture["invocation_context"].update(
        {
            "agent_profile_id": PARALLEL_INTAKE_AGENT_PROFILE_ID,
            "output_schema_version": PARALLEL_INTAKE_OUTPUT_SCHEMA,
        }
    )
    command = RoomGraphCommand.model_validate(fixture)
    actor_scope = ActorScopeBinding.from_json(
        command.actor_scope.model_dump(mode="json")
    )
    identity = ThreadIdentity(
        thread_id=command.thread_id,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType.INTAKE,
        room_epoch=command.room_epoch,
        actor_scope=actor_scope,
        agent_session_id="SESSION_PARALLEL_1",
        shared_session=False,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    record = ThreadRecord(
        identity=identity,
        lifecycle=ThreadLifecycle.ACTIVE,
        cognitive_revision=8,
        last_checkpoint_ns="intake",
        last_checkpoint_id="cp-8",
    )
    now = datetime.now(timezone.utc)
    lease = LeaseRecord(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-parallel-1",
        fencing_token=4,
        lease_expires_at=now + timedelta(seconds=30),
        acquired_at=now,
        renewed_at=now,
        released_at=None,
        cancelled_at=None,
        cancelled_by_command_id=None,
        revision=1,
    )
    attempt = AttemptRecord(
        attempt_id=command.attempt_id,
        thread_id=command.thread_id,
        command_id=command.command_id,
        attempt_no=1,
        owner_id=lease.owner_id,
        fencing_token=lease.fencing_token,
        status=AttemptStatus.EXECUTING,
        provider_call_count=0,
        error_code=None,
        error_classification=None,
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id=lease.owner_id,
        fencing_token=lease.fencing_token,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id="p9act.v1." + "2" * 32,
        room_fencing_token=7,
        command_hash="3" * 64,
        command_envelope_hash="4" * 64,
        execution_provider="litellm",
        execution_model="qwen3.7-max-2026-06-08",
        environment_id="target-e2e-test",
        environment_generation=1,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=command.room_type,
        binding_hash="5" * 64,
        code_build_id="parallel-test-build",
    )
    admission = SimpleNamespace(command=command, thread=identity)
    return GatewayExecution(
        admission=admission,  # type: ignore[arg-type]
        attempt=attempt,
        lease=lease,
        fence=fence,
        thread_record=record,
    )


def _parallel_ingress(
    execution: GatewayExecution,
) -> tuple[dict[str, Any], dict[str, Any], GatewayExecution]:
    command = execution.admission.command
    identity = execution.admission.thread
    actor_hash = identity.actor_scope_hash
    party_entry = {
        "intake_quality": {
            "score": 0,
            "threshold": 85,
            "ready_for_next_step": False,
            "score_breakdown": {
                "references": 0,
                "event_story": 0,
                "party_positions": 0,
                "requested_resolution": 0,
                "risk_and_conflicts": 0,
                "next_action_clarity": 0,
            },
            "improvement_reason": "等待补充。",
        },
        "missing_information": {
            "blocking_gaps": [],
            "nice_to_have_gaps": [],
            "next_questions": ["请说明签收时间。"],
        },
        "handoff_notes": {
            "remark_status": "NOT_READY",
            "phase_source_message_id": "",
            "latest_remark": "",
            "remarks": [],
            "instruction": "请继续补充。",
        },
        "admission": {
            "recommendation": "NEED_MORE_INFO",
            "reasoning": "",
            "confidence": 0,
        },
    }
    matrix = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": command.case_id,
        "matrix_id": "MATRIX_PARALLEL_1",
        "matrix_version": 3,
        "matrix_kind": "BILATERAL_FROZEN",
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "case_overview": {"neutral_summary": "商品参数存在争议。"},
        "claims": {},
        "fact_rows": [
            {
                "fact_id": "FACT_01",
                "fact_target": "商品是否达到宣传参数。",
                "message_id": "MESSAGE_PRIVATE_1",
            }
        ],
        "fact_relationships": [],
        "fact_indexes": {"core_fact_ids": ["FACT_01"]},
    }
    snapshot = {
        "schema_version": "intake-domain-snapshot.v2",
        "snapshot_id": "SNAPSHOT_PARALLEL_1",
        "tenant_surrogate": command.tenant_surrogate,
        "case_id": command.case_id,
        "room_type": "INTAKE",
        "room_epoch": command.room_epoch,
        "thread_id": command.thread_id,
        "actor_scope_hash": actor_hash,
        "agent_session_id": identity.agent_session_id,
        "domain_revision": 8,
        "room_revision": 8,
        "projection_revision": 8,
        "visibility": "PRIVATE",
        "source_refs": ["MESSAGE_OLD_1", "MESSAGE_AI_1"],
        "initial_case_facts": {},
        "shareable_projection": {
            "initiator_role": "USER",
            "respondent_role": "MERCHANT",
        },
        "own_messages": [
            {
                "message_id": "MESSAGE_OLD_1",
                "role": "HUMAN",
                "audience": "USER",
                "sequence": 0,
                "text": "商品参数不符。",
                "source_hash": "1" * 64,
            },
            {
                "message_id": "MESSAGE_AI_1",
                "role": "AI",
                "audience": "USER",
                "sequence": 1,
                "text": "请说明签收时间。",
                "source_hash": "2" * 64,
            },
        ],
        "current_dossier": {
            "schema_version": "intake-dossier.v2",
            "case_story": {
                "summary": "商品参数存在争议。",
                "message_id": "MESSAGE_PRIVATE_1",
            },
            "case_fact_matrix": matrix,
            "party_intake_state": {
                "schema_version": "party-intake-state.v1",
                "USER": party_entry,
                "MERCHANT": party_entry,
            },
        },
        "created_at": "2026-08-25T10:00:00Z",
        "snapshot_hash": "a" * 64,
    }
    event = {
        "schema_version": "intake-turn-event.v2",
        "event_id": "EVENT_PARALLEL_1",
        "message_id": "MESSAGE_PARALLEL_1",
        "tenant_surrogate": command.tenant_surrogate,
        "case_id": command.case_id,
        "room_type": "INTAKE",
        "room_epoch": command.room_epoch,
        "thread_id": command.thread_id,
        "actor_scope_hash": actor_hash,
        "agent_session_id": identity.agent_session_id,
        "sequence_no": 9,
        "domain_revision": 9,
        "audience": "USER",
        "source_type": "ROOM_MESSAGE",
        "text": "商品于昨日签收。",
        "source_refs": ["MESSAGE_PARALLEL_1"],
        "occurred_at": "2026-08-25T10:00:01Z",
        "event_hash": "b" * 64,
    }
    snapshot_ref = command.domain_snapshot_ref.model_copy(
        update={
            "schema_version": "intake-domain-snapshot.v2",
            "sha256": snapshot["snapshot_hash"],
        }
    )
    event_ref = command.event_ref.model_copy(
        update={
            "schema_version": "intake-turn-event.v2",
            "sha256": event["event_hash"],
        }
    )
    updated_command = command.model_copy(
        update={"domain_snapshot_ref": snapshot_ref, "event_ref": event_ref}
    )
    updated_admission = SimpleNamespace(command=updated_command, thread=identity)
    return (
        snapshot,
        event,
        replace(execution, admission=updated_admission),  # type: ignore[arg-type]
    )


def _model_context() -> IntakeModelContextViewV1:
    previous_state = {
        "revision": 8,
        "persisted_phase": "NOT_READY",
        "quality": {"score_breakdown": {"references": 10}},
        "dossier_projection": {"event_story": "商品已经发货。"},
    }
    matrix = {"facts": [{"fact_key": "FACT_01", "statement": "商品已经发货。"}]}
    question = "请说明签收时间。"
    previous_message = "请补充签收时间。"
    current_message = "商品于昨日签收。"
    return IntakeModelContextViewV1.seal(
        {
            "contract_version": "intake.model-context-view.v1",
            "turn_route": {
                "source_type": "ROOM_MESSAGE",
                "execution_profile": "PARALLEL_FRAMES",
            },
            "source_capacity": {
                "business_role": "USER",
                "litigation_capacity": "INITIATOR",
                "writable_partition": "INITIATOR_ONLY",
            },
            "previous_state": previous_state,
            "current_action_binding": {
                "action": "ASK_SUBSTANTIVE",
                "derived_from_phase": "NOT_READY",
                "phase_source_sha256": canonical_sha256(previous_state),
            },
            "authorized_question_slots": [
                {
                    "question_id": "Q_DELIVERY_TIME",
                    "target_capacity": "INITIATOR",
                    "source": "PREVIOUS_PERSISTED_STATE",
                    "canonical_text": question,
                    "canonical_text_sha256": canonical_sha256(question),
                }
            ],
            "frozen_case_matrix": {
                "version": 3,
                "sha256": canonical_sha256(matrix),
                "payload": matrix,
            },
            "recent_dialogue_messages": [
                {
                    "sequence": 1,
                    "speaker_role": "USER",
                    "speaker_capacity": "INITIATOR",
                    "text": previous_message,
                    "source_sha256": canonical_sha256(previous_message),
                }
            ],
            "current_user_message": {
                "source_sequence": 2,
                "source_role": "USER",
                "source_capacity": "INITIATOR",
                "text": current_message,
                "text_sha256": canonical_sha256(current_message),
            },
        }
    )


def _context_envelope(context: IntakeModelContextViewV1):
    return build_parallel_context_envelope(
        case_ref=IntakeCaseRefV1.model_validate(
            {
                "tenant_id": "tenant-local",
                "case_id": "CASE_PARALLEL_1",
                "thread_id": "THREAD_PARALLEL_1",
                "room_id": "ROOM_PARALLEL_1",
                "room_epoch": 2,
                "fence_token": "FENCE_PARALLEL_1",
            }
        ),
        source_event=IntakeSourceEventRefV1.model_validate(
            {
                "message_id": "MESSAGE_PARALLEL_1",
                "logical_sequence": 2,
                "actor_id": "user-local",
                "actor_role": "USER",
                "payload_sha256": context.current_user_message.text_sha256,
            }
        ),
        authority=IntakeAuthorityRefV1.model_validate(
            {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
                "authority_snapshot_ref": "urn:intake:authority:parallel-1",
                "authority_snapshot_sha256": "a" * 64,
            }
        ),
        previous_state_ref="urn:intake:previous-state:parallel-1",
        previous_state_sha256=canonical_sha256(
            context.previous_state.model_dump(mode="json")
        ),
        model_context_view=context,
    )


def _instruction_packs() -> tuple[IntakeFrameInstructionPackV1, ...]:
    common = (PROMPT_ROOT / "intake_turn_parallel_authority.md").read_text(
        encoding="utf-8"
    )
    names = {
        "DIALOGUE_FRAME": "intake_turn_dialogue_frame.md",
        "DOSSIER_FRAME": "intake_turn_dossier_frame.md",
        "QUALITY_FRAME": "intake_turn_quality_frame.md",
    }
    return tuple(
        build_instruction_pack(
            frame_type=frame_type,
            common_authority_prompt=common,
            frame_prompt=(PROMPT_ROOT / names[frame_type]).read_text(encoding="utf-8"),
        )
        for frame_type in FRAME_TYPES
    )


def _outputs() -> dict[str, dict[str, Any]]:
    return {
        "intake_turn_dialogue_frame": {
            "public_projection_items": [
                {
                    "schema_version": "intake.dialogue-public-segment-proposal.v1",
                    "provider_slot_id": "DSEG_01",
                    "segment_kind": "ACKNOWLEDGEMENT",
                    "candidate_text": "已记录您本轮补充的事实与处理意见。",
                }
            ],
            "frame_type": "DIALOGUE_FRAME",
            "schema_version": "intake.dialogue-frame.v1",
            "dialogue": {
                "action_binding": {
                    "action": "ASK_SUBSTANTIVE",
                    "phase_source_sha256": canonical_sha256(
                        _model_context().previous_state.model_dump(mode="json")
                    ),
                },
                "public_projection_slots": ["DSEG_01"],
                "language": "zh-CN",
            },
        },
        "intake_turn_dossier_frame": {
            "public_projection_items": [
                {
                    "schema_version": "intake.dossier-public-patch-proposal.v1",
                    "provider_slot_id": "DPATCH_01",
                    "projection_kind": "CURRENT_FACT",
                    "projection_path_id": "case_story.current_facts",
                    "fact_key": "FACT_01",
                    "source_binding_id": "SOURCE_01",
                    "candidate_value": {"summary": "商品已使用约半小时。"},
                }
            ],
            "frame_type": "DOSSIER_FRAME",
            "schema_version": "intake.dossier-frame.v1",
            "dossier_delta": {
                "dossier_patch": {
                    "case_story": {"current_facts": ["商品已使用约半小时。"]}
                },
                "matrix_patch": {"facts": [{"fact_key": "FACT_01"}]},
                "public_projection_slots": ["DPATCH_01"],
            },
        },
        "intake_turn_quality_frame": _quality_output(),
    }


def _quality_output() -> dict[str, Any]:
    dimensions = [
        ("REFERENCES", 10, "QMETRIC_01"),
        ("EVENT_STORY", 18, "QMETRIC_02"),
        ("PARTY_POSITIONS", 18, "QMETRIC_03"),
        ("REQUESTED_RESOLUTION", 14, "QMETRIC_04"),
        ("RISK_AND_CONFLICTS", 13, "QMETRIC_05"),
        ("NEXT_ACTION_CLARITY", 12, "QMETRIC_06"),
    ]
    return {
        "public_projection_items": [
            {
                "schema_version": "intake.quality-public-metric-proposal.v1",
                "provider_slot_id": slot,
                "projection_kind": "DIMENSION_SCORE",
                "dimension": dimension,
                "candidate_score": score,
                "linked_fact_keys": ["FACT_01"],
            }
            for dimension, score, slot in dimensions
        ],
        "frame_type": "QUALITY_FRAME",
        "schema_version": "intake.quality-frame.v1",
        "quality": {
            "scores": {
                "references": 10,
                "event_story": 18,
                "party_positions": 18,
                "requested_resolution": 14,
                "risk_and_conflicts": 13,
                "next_action_clarity": 12,
            },
            "gap_proposals": [
                {
                    "dimension": "REFERENCES",
                    "question": "请补充第三方检测报告的机构名称？",
                    "source_role": "USER",
                    "linked_fact_keys": ["FACT_01"],
                }
            ],
            "assessment_reasoning": "主要事实和处理方向已较清楚，但证据来源仍需补充。",
            "public_projection_slots": [slot for _, _, slot in dimensions],
        },
    }


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
