from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from dataclasses import replace
from types import SimpleNamespace
from typing import Any

import pytest
from langchain_core.runnables.config import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
)
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import BaseModel, ConfigDict

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.checkpoint import FencedPostgresSaver, bind_fence_context
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import (
    ActorRole,
    ActorScopeBinding,
    Audience,
    RoomType,
    ThreadIdentity,
)
from app.graph_runtime.persistence_models import GraphFenceContext, GraphFenceError
from app.graphs.hearing.contracts import HEARING_OPERATION_IDENTITIES, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.graph import compile_hearing_graph_candidates
from app.graphs.hearing.runtime import HearingRuntimeAuthority, build_hearing_runtime_bundle
from app.graphs.hearing.state import HearingGraphInvocation, new_hearing_graph_state


THREAD_ID = "grt.v1." + "7" * 32


class _Request(BaseModel):
    model_config = ConfigDict(extra="forbid")

    flow_schema_version: str = "hearing_flow.v2"
    case_id: str = "CASE_hearing"
    workflow_id: str = "WORKFLOW_hearing"
    stage_sequence: int = 4
    private_statement: str = "ephemeral private input"


class _Proposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = "hearing_test_proposal.v1"
    case_id: str = "CASE_hearing"


class _EvidenceAssessment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    evidence_id: str
    score: int


class _MemoryFencedSaver(FencedPostgresSaver):
    def __init__(self, fence: GraphFenceContext) -> None:
        BaseCheckpointSaver.__init__(self)
        self._memory = InMemorySaver()
        self.active_fence = fence

    def _guard(self, config: RunnableConfig) -> GraphFenceContext:
        fence = self._require_fence(config)
        if fence != self.active_fence:
            raise GraphFenceError("Graph lease is stale")
        return fence

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        fence = self._guard(config)
        found = await self._memory.aget_tuple(config)
        if found is None:
            return None
        self._validate_checkpoint_tuple(found, fence)
        return self._bind_tuple(found, fence)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        if config is None:
            raise GraphFenceError("trusted Graph fence required")
        fence = self._guard(config)
        async for item in self._memory.alist(
            config,
            filter=filter,
            before=before,
            limit=limit,
        ):
            self._validate_checkpoint_tuple(item, fence)
            yield self._bind_tuple(item, fence)

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        fence = self._guard(config)
        revision = self._checkpoint_revision(checkpoint)
        bound = self._bind_metadata(metadata, fence, revision)
        saved = await self._memory.aput(config, checkpoint, bound, new_versions)
        return bind_fence_context(saved, fence)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes,
        task_id: str,
        task_path: str = "",
    ) -> None:
        self._guard(config)
        await self._memory.aput_writes(config, writes, task_id, task_path)

    def get_next_version(self, current, channel):
        return self._memory.get_next_version(current, channel)


def _gateway_execution() -> GatewayExecution:
    identity = HEARING_OPERATION_IDENTITIES[HearingOperation.INTAKE_QUESTIONS]
    payload: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "command_id": "command-hearing-1",
        "logical_run_id": "run-hearing-1",
        "attempt_id": "attempt-hearing-1",
        "tenant_surrogate": "tenant-hearing",
        "case_id": "CASE_hearing",
        "room_type": "HEARING",
        "room_epoch": 1,
        "graph_key": identity.graph_key,
        "graph_version": identity.graph_version,
        "checkpoint_schema_version": identity.checkpoint_schema_version,
        "thread_id": THREAD_ID,
        "actor_scope": {
            "actor_id": "user-hearing",
            "actor_role": "USER",
            "audience": "USER",
            "capabilities": [],
        },
        "process_revision": 3,
        "stage_code": "INTAKE_QUESTIONS_GENERATING",
        "stage_sequence": 4,
        "domain_snapshot_ref": {
            "artifact_id": "snapshot-hearing-1",
            "schema_version": "hearing-snapshot.v1",
            "uri": "urn:synthetic-hearing:snapshot-hearing-1",
            "sha256": "a" * 64,
            "size_bytes": 128,
        },
        "invocation_context": {
            "agent_profile_id": "hearing-agent.v1",
            "prompt_profile_id": identity.prompt_version,
            "model_profile_id": identity.model_profile_id,
            "output_schema_version": identity.output_schema_version,
            "policy_version": identity.policy_version,
            "guardrail_version": identity.guardrail_version,
            "tool_capabilities": [],
            "envelope_key_id": "java-key-1",
            "envelope_nonce": "nonce-hearing-1",
        },
        "retry_budget": {
            "provider_attempts_remaining": 2,
            "activity_attempts_remaining": 3,
            "repairs_remaining": 1,
        },
        "deadline_at": "2026-07-24T12:00:00Z",
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    }
    payload["request_hash"] = canonical_sha256(payload)
    command = RoomGraphCommand.model_validate(payload)
    thread = ThreadIdentity(
        thread_id=THREAD_ID,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType.HEARING,
        room_epoch=command.room_epoch,
        actor_scope=ActorScopeBinding(
            actor_id="user-hearing",
            actor_role=ActorRole.USER,
            audience=Audience.USER,
            capabilities=(),
        ),
        agent_session_id="session-hearing-1",
        shared_session=False,
        graph_key=identity.graph_key,
        graph_version=identity.graph_version,
        checkpoint_schema_version=identity.checkpoint_schema_version,
    )
    fence = GraphFenceContext(
        thread_id=THREAD_ID,
        command_id=command.command_id,
        owner_id="worker-hearing-1",
        fencing_token=11,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=identity.graph_key,
        graph_version=identity.graph_version,
        checkpoint_schema_version=identity.checkpoint_schema_version,
    )
    admission = SimpleNamespace(
        command=command,
        thread=thread,
        registry=SimpleNamespace(
            binding=SimpleNamespace(tool_policy_version=identity.tool_policy_version)
        ),
    )
    return GatewayExecution(
        admission=admission,  # type: ignore[arg-type]
        attempt=None,  # type: ignore[arg-type]
        lease=None,  # type: ignore[arg-type]
        fence=fence,
    )


def _authority(execution: GatewayExecution) -> HearingRuntimeAuthority:
    command = execution.admission.command
    operation = HearingOperation.INTAKE_QUESTIONS
    key = (
        f"hearing.agent:{command.tenant_surrogate}:{command.case_id}:"
        f"{command.room_epoch}:{command.stage_sequence}:{operation.value}:"
        f"{command.request_hash}"
    )
    return HearingRuntimeAuthority(
        execution=execution,
        operation=operation,
        operation_key=key,
        java_room_fencing_token=5,
    )


def _bundle(
    *,
    saver: _MemoryFencedSaver,
    execution: GatewayExecution,
    request: _Request,
    execute,
):
    operation = HearingOperation.INTAKE_QUESTIONS
    return build_hearing_runtime_bundle(
        identity=HEARING_OPERATION_IDENTITIES[operation],
        authority=_authority(execution),
        request=request,
        invocation=HearingGraphInvocation(request=request, execute=execute),
        checkpointer=saver,
        runtime_mode="SIGNED_SYNTHETIC_SHADOW",
    )


def test_checkpoint_recovery_reuses_command_and_does_not_repeat_terminal_work() -> None:
    execution = _gateway_execution()
    saver = _MemoryFencedSaver(execution.fence)
    request = _Request()
    crashed = False

    def crash_once(_: BaseModel) -> _Proposal:
        nonlocal crashed
        if not crashed:
            crashed = True
            raise RuntimeError("synthetic process death")
        return _Proposal()

    with pytest.raises(RuntimeError, match="synthetic process death"):
        asyncio.run(
            _bundle(
                saver=saver,
                execution=execution,
                request=request,
                execute=crash_once,
            ).astart()
        )

    calls = 0

    def recover(_: BaseModel) -> _Proposal:
        nonlocal calls
        calls += 1
        return _Proposal()

    recovered = _bundle(
        saver=saver,
        execution=execution,
        request=request,
        execute=recover,
    )
    state = asyncio.run(recovered.aresume())
    replay = asyncio.run(recovered.arun())

    assert state["proposal"] == replay["proposal"] == _Proposal().model_dump(mode="json")
    assert calls == 1
    assert request.private_statement not in repr(state)


def test_same_command_with_changed_private_payload_fails_recovery_binding() -> None:
    execution = _gateway_execution()
    saver = _MemoryFencedSaver(execution.fence)
    original = _Request()
    asyncio.run(
        _bundle(
            saver=saver,
            execution=execution,
            request=original,
            execute=lambda _: _Proposal(),
        ).astart()
    )
    rebound = _Request(private_statement="different private payload")

    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_RECOVERY_RUNTIME_BINDING_MISMATCH",
    ):
        asyncio.run(
            _bundle(
                saver=saver,
                execution=execution,
                request=rebound,
                execute=lambda _: _Proposal(),
            ).arun()
        )


def test_evidence_send_recovery_keeps_completed_wave_and_resumes_missing_key_once() -> None:
    operation = HearingOperation.EVIDENCE_SYNTHESIS
    identity = HEARING_OPERATION_IDENTITIES[operation]
    request = _Request()
    initial = new_hearing_graph_state(identity=identity, operation=operation, request=request)
    keys = [f"EVIDENCE_{index:02d}" for index in range(9)]
    saver = InMemorySaver()
    config = {
        "configurable": {"thread_id": "hearing-send-recovery"},
        "max_concurrency": 8,
        "recursion_limit": 64,
    }
    crashed = False

    def crash_last(_request: BaseModel, key: str) -> _EvidenceAssessment:
        nonlocal crashed
        if key == keys[-1] and not crashed:
            crashed = True
            raise RuntimeError("crash in second Send wave")
        return _EvidenceAssessment(evidence_id=key, score=int(key[-2:]))

    async def scenario() -> tuple[dict[str, Any], int]:
        graph = compile_hearing_graph_candidates(checkpointer=saver)[identity.identity]
        with pytest.raises(RuntimeError, match="second Send wave"):
            await graph.ainvoke(
                initial,
                config,
                context=HearingGraphInvocation(
                    request=request,
                    execute=lambda _: _Proposal(),
                    plan_work_items=lambda _: keys,
                    execute_work_item=crash_last,
                    execute_with_work_results=lambda _request, _results: _Proposal(),
                ),
                durability="sync",
            )

        snapshot = await graph.aget_state(config)
        assert list(snapshot.values["work_results"]) == keys[:8]
        resumed_calls = 0

        def resume_item(_request: BaseModel, key: str) -> _EvidenceAssessment:
            nonlocal resumed_calls
            resumed_calls += 1
            return _EvidenceAssessment(evidence_id=key, score=int(key[-2:]))

        recovered = await graph.ainvoke(
            None,
            config,
            context=HearingGraphInvocation(
                request=request,
                execute=lambda _: _Proposal(),
                plan_work_items=lambda _: keys,
                execute_work_item=resume_item,
                execute_with_work_results=lambda _request, _results: _Proposal(),
            ),
            durability="sync",
        )
        return recovered, resumed_calls

    recovered, resumed_calls = asyncio.run(scenario())

    assert list(recovered["work_results"]) == keys
    assert recovered["status"] == "PROPOSED"
    assert resumed_calls == 1


def test_stale_graph_lease_fence_cannot_resume_checkpoint() -> None:
    execution = _gateway_execution()
    saver = _MemoryFencedSaver(execution.fence)
    request = _Request()
    bundle = _bundle(
        saver=saver,
        execution=execution,
        request=request,
        execute=lambda _: _Proposal(),
    )
    asyncio.run(bundle.astart())
    saver.active_fence = replace(
        execution.fence,
        owner_id="replacement-worker",
        fencing_token=execution.fence.fencing_token + 1,
    )

    with pytest.raises(GraphFenceError, match="stale"):
        asyncio.run(bundle.aresume())


@pytest.mark.parametrize(
    ("mode", "code"),
    [
        ("DISABLED", "HEARING_RUNTIME_DISABLED"),
        ("TEMPORAL", "HEARING_RUNTIME_MODE_FORBIDDEN"),
        ("REAL_SHADOW", "HEARING_RUNTIME_MODE_FORBIDDEN"),
    ],
)
def test_runtime_allows_only_java_signed_synthetic_shadow(mode: str, code: str) -> None:
    execution = _gateway_execution()
    saver = _MemoryFencedSaver(execution.fence)
    request = _Request()

    with pytest.raises(HearingGraphContractError, match=code):
        build_hearing_runtime_bundle(
            identity=HEARING_OPERATION_IDENTITIES[HearingOperation.INTAKE_QUESTIONS],
            authority=_authority(execution),
            request=request,
            invocation=HearingGraphInvocation(
                request=request,
                execute=lambda _: _Proposal(),
            ),
            checkpointer=saver,
            runtime_mode=mode,  # type: ignore[arg-type]
        )


def test_runtime_rejects_unfenced_checkpoint_saver() -> None:
    execution = _gateway_execution()
    request = _Request()

    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_RUNTIME_FENCED_CHECKPOINTER_REQUIRED",
    ):
        build_hearing_runtime_bundle(
            identity=HEARING_OPERATION_IDENTITIES[HearingOperation.INTAKE_QUESTIONS],
            authority=_authority(execution),
            request=request,
            invocation=HearingGraphInvocation(
                request=request,
                execute=lambda _: _Proposal(),
            ),
            checkpointer=InMemorySaver(),  # type: ignore[arg-type]
            runtime_mode="SIGNED_SYNTHETIC_SHADOW",
        )
