from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.contracts.v1.codec import canonical_sha256
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
    ParallelFrameExecutionRequest,
    ParallelIntakeFrameOrchestrator,
    build_parallel_frame_graph,
    compile_parallel_frame_graphs,
)
from app.graph_runtime.checkpoint import (
    TechnicalChildCheckpointBinding,
    bind_technical_child_checkpoint,
)
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
