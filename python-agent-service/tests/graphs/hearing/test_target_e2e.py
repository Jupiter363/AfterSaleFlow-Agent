from __future__ import annotations

import asyncio
import json
import inspect
from collections.abc import AsyncIterator
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

import httpx
from langchain_core.messages import AIMessageChunk
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
import pytest

from app.agents.hearing_flow import HearingFlowWorkflows
from app.api.graph_stream_service import _model_transport_output_error_code
from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.checkpoint import (
    ExternalTerminalCommit,
    FENCE_CONTEXT_KEY,
    FencedPostgresSaver,
    bind_fence_context,
)
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import (
    ActorRole,
    ActorScopeBinding,
    Audience,
    RoomType,
    ThreadIdentity,
)
from app.graph_runtime.persistence_models import (
    GraphFenceContext,
    GraphFenceError,
    GraphGatewayMode,
)
from app.graph_runtime.target_e2e import TargetE2EGraphResultEnvelope
from app.graphs.hearing.contracts import (
    HEARING_MODEL_NODE_PROMPTS,
    HEARING_TARGET_E2E_OPERATION_BINDINGS,
    HEARING_WORKFLOW_STAGE_CODES,
    HearingOperation,
)
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.lcel import invoke_hearing_lcel
from app.graphs.hearing.state import HearingGraphInvocation
from app.graphs.hearing import target_e2e as hearing_target_e2e
from app.graphs.hearing.target_e2e import (
    HearingTargetE2ELoadedInvocation,
    HearingTargetE2EProposalSource,
    HearingTargetE2ERuntimeAdapter,
    HearingTargetE2EStoredPayload,
    build_target_e2e_hearing_provider,
    target_e2e_hearing_family_registrations,
)
from app.harness.model_runner import HarnessModelRunner
from app.harness.prompt_composer import PromptRepository
from app.llm import AgentOutputSchemaError, LiteLlmProxyClient
from app.model_runtime.transports import ModelTransportOutputError
from app.schemas import (
    CaseFactMatrixV2,
    HearingIntakeQuestionsLlmOutput,
    HearingIntakeQuestionsRequest,
    content_hash,
)
from app.streaming import AgentStreamObserver, bind_stream_observer


THREAD_ID = "grt.v1." + "9" * 32
ACTIVATION_ID = "p9act.v1." + "a" * 32
ROOM_FENCE = 17
GRAPH_FENCE = 29
COMMAND_ENVELOPE_HASH = "e" * 64
OUTER_GRAPH_KEY = "all-rooms.target-e2e.v1"
OUTER_GRAPH_VERSION = "target-e2e-graph.2026-07-27.1"
OUTER_CHECKPOINT_SCHEMA = "target-e2e-checkpoint.v1"


class _Request(BaseModel):
    model_config = ConfigDict(extra="forbid")

    flow_schema_version: str = "hearing_flow.v2"
    case_id: str = "CASE_hearing"
    workflow_id: str = "WORKFLOW_hearing"
    stage_code: str
    stage_sequence: int
    private_statement: str = "ephemeral target fixture"


class _Proposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str
    case_id: str = "CASE_hearing"


class _LcelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: str


class _DeterministicModelRunner:
    def __init__(self) -> None:
        self.nodes: list[str] = []

    def invoke_structured(self, **kwargs):
        node_name = kwargs["node_name"]
        self.nodes.append(node_name)
        return SimpleNamespace(value={"value": node_name})


class _InvocationProvider:
    def __init__(self, *, request_stage_override: str | None = None) -> None:
        self.calls: list[HearingOperation] = []
        self.model_calls = 0
        self.request_stage_override = request_stage_override

    async def load(
        self,
        execution: GatewayExecution,
    ) -> HearingTargetE2ELoadedInvocation:
        command = execution.admission.command
        operation = next(
            operation
            for operation, binding in HEARING_TARGET_E2E_OPERATION_BINDINGS.items()
            if binding.command_stage_code == command.stage_code
        )
        request = _Request(
            stage_code=(
                self.request_stage_override
                or HEARING_TARGET_E2E_OPERATION_BINDINGS[operation].request_stage_code
            ),
            stage_sequence=command.stage_sequence,
        )
        self.calls.append(operation)
        result_schema = HEARING_TARGET_E2E_OPERATION_BINDINGS[
            operation
        ].result_schema_version

        def execute(_: BaseModel) -> _Proposal:
            self.model_calls += 1
            return _Proposal(schema_version=result_schema)

        invocation = HearingGraphInvocation(
            request=request,
            execute=execute,
        )
        if operation is HearingOperation.EVIDENCE_SYNTHESIS:
            invocation = HearingGraphInvocation(
                request=request,
                execute=execute,
                plan_work_items=lambda _: [],
                execute_work_item=lambda _request, _key: _Proposal(
                    schema_version=result_schema
                ),
                execute_with_work_results=lambda _request, _results: execute(_request),
            )
        return HearingTargetE2ELoadedInvocation(
            operation=operation,
            request=request,
            invocation=invocation,
            snapshot_uri=command.domain_snapshot_ref.uri,
            snapshot_hash=command.domain_snapshot_ref.sha256,
            event_uri=(command.event_ref.uri if command.event_ref is not None else None),
            event_hash=(
                command.event_ref.sha256 if command.event_ref is not None else None
            ),
        )


class _PayloadStore:
    def __init__(self) -> None:
        self.calls: list[
            tuple[HearingOperation, str, bytes, str, str, str, int]
        ] = []

    async def put(
        self,
        *,
        execution: GatewayExecution,
        operation: HearingOperation,
        proposal_id: str,
        payload_schema_version: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> HearingTargetE2EStoredPayload:
        assert execution.admission.command.room_type == "HEARING"
        self.calls.append(
            (
                operation,
                proposal_id,
                payload,
                payload_hash,
                checkpoint_ns,
                checkpoint_id,
                cognitive_revision,
            )
        )
        return HearingTargetE2EStoredPayload(
            proposal_id=proposal_id,
            payload_schema_version=payload_schema_version,
            payload_ref=f"urn:target-e2e:proposal:hearing:{payload_hash}",
            payload_hash=payload_hash,
            size_bytes=len(payload),
        )


class _MemoryFencedSaver(FencedPostgresSaver):
    def __init__(self, fence: GraphFenceContext) -> None:
        BaseCheckpointSaver.__init__(self)
        self._memory = InMemorySaver()
        self.active_fence = fence
        self.external_terminal_commits: list[
            tuple[RunnableConfig, ExternalTerminalCommit]
        ] = []

    def _guard(self, config: RunnableConfig) -> GraphFenceContext:
        configurable = config.get("configurable") or {}
        fence = configurable.get(FENCE_CONTEXT_KEY)
        if fence != self.active_fence:
            raise GraphFenceError("Graph lease is stale")
        return self.active_fence

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

    async def acommit_external_terminal(
        self,
        config: RunnableConfig,
        commit: ExternalTerminalCommit,
    ) -> RunnableConfig:
        self._guard(config)
        self.external_terminal_commits.append((config, commit))
        return config

    def get_next_version(self, current, channel):
        return self._memory.get_next_version(current, channel)


if "execution_lane" not in inspect.signature(GraphFenceContext).parameters:

    @dataclass(frozen=True, slots=True)
    class _LegacyCandidateFence(GraphFenceContext):
        @property
        def execution_lane(self) -> str:
            return "TARGET_E2E_CANDIDATE"

        @property
        def activation_id(self) -> str:
            return ACTIVATION_ID

        @property
        def room_fencing_token(self) -> int:
            return ROOM_FENCE

        @property
        def command_hash(self) -> str:
            return _COMMAND_HASH

        @property
        def command_envelope_hash(self) -> str:
            return COMMAND_ENVELOPE_HASH

        @property
        def binding_hash(self) -> str:
            return "b" * 64

        @property
        def code_build_id(self) -> str:
            return "p9-graph-hearing-1"


_COMMAND_HASH = "0" * 64


def _fence(command: RoomGraphCommand) -> GraphFenceContext:
    global _COMMAND_HASH
    _COMMAND_HASH = canonical_sha256(command.model_dump(mode="json", exclude_none=True))
    values: dict[str, Any] = {
        "thread_id": command.thread_id,
        "command_id": command.command_id,
        "owner_id": "target-hearing-worker-1",
        "fencing_token": GRAPH_FENCE,
        "request_hash": command.request_hash,
        "room_epoch": command.room_epoch,
        "graph_key": command.graph_key,
        "graph_version": command.graph_version,
        "checkpoint_schema_version": command.checkpoint_schema_version,
    }
    parameters = inspect.signature(GraphFenceContext).parameters
    if "execution_lane" in parameters:
        values.update(
            {
                "execution_lane": getattr(
                    GraphGatewayMode,
                    "TARGET_E2E_CANDIDATE",
                ),
                "activation_id": ACTIVATION_ID,
                "room_fencing_token": ROOM_FENCE,
                "command_hash": _COMMAND_HASH,
                "command_envelope_hash": COMMAND_ENVELOPE_HASH,
                "execution_provider": "hearing-test-provider",
                "execution_model": "hearing-test-model-v1",
                "environment_id": "p9-isolated-preprod-01",
                "environment_generation": 7,
                "tenant_surrogate": command.tenant_surrogate,
                "case_id": command.case_id,
                "room_type": command.room_type,
                "binding_hash": "b" * 64,
                "code_build_id": "p9-graph-hearing-1",
            }
        )
        return GraphFenceContext(
            **{key: value for key, value in values.items() if key in parameters}
        )
    return _LegacyCandidateFence(**values)


def _execution(operation: HearingOperation, *, lane: str = "TARGET_E2E_CANDIDATE"):
    operation_binding = HEARING_TARGET_E2E_OPERATION_BINDINGS[operation]
    payload: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "command_id": f"command-hearing-{operation.value}",
        "logical_run_id": f"run-hearing-{operation.value}",
        "attempt_id": f"attempt-hearing-{operation.value}",
        "tenant_surrogate": "tenant-hearing",
        "case_id": "CASE_hearing",
        "room_type": "HEARING",
        "room_epoch": 1,
        "graph_key": OUTER_GRAPH_KEY,
        "graph_version": OUTER_GRAPH_VERSION,
        "checkpoint_schema_version": OUTER_CHECKPOINT_SCHEMA,
        "thread_id": THREAD_ID,
        "actor_scope": {
            "actor_id": "user-hearing",
            "actor_role": "USER",
            "audience": "USER",
            "capabilities": [],
        },
        "process_revision": 3,
        "stage_code": operation_binding.command_stage_code,
        "stage_sequence": list(HearingOperation).index(operation) + 1,
        "domain_snapshot_ref": {
            "artifact_id": "snapshot-hearing-1",
            "schema_version": "hearing-snapshot.v1",
            "uri": "urn:target-e2e:input:hearing:snapshot-1",
            "sha256": "a" * 64,
            "size_bytes": 128,
        },
        "invocation_context": {
            "agent_profile_id": "hearing-agent.v1",
            "prompt_profile_id": "target-e2e.all-rooms.prompt.v1",
            "model_profile_id": "target-e2e.all-rooms.model.v1",
            "output_schema_version": "target-e2e-room-proposal.v1",
            "policy_version": "target-e2e.proposal-only.v1",
            "guardrail_version": "target-e2e.guardrails.v1",
            "tool_capabilities": [],
            "envelope_key_id": "java-key-1",
            "envelope_nonce": f"nonce-{operation.value}",
        },
        "retry_budget": {
            "provider_attempts_remaining": 2,
            "activity_attempts_remaining": 3,
            "repairs_remaining": 1,
        },
        "deadline_at": "2026-07-27T12:00:00Z",
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
        graph_key=OUTER_GRAPH_KEY,
        graph_version=OUTER_GRAPH_VERSION,
        checkpoint_schema_version=OUTER_CHECKPOINT_SCHEMA,
    )
    fence = _fence(command)
    binding = SimpleNamespace(
        graph_key=OUTER_GRAPH_KEY,
        graph_version=OUTER_GRAPH_VERSION,
        checkpoint_schema_version=OUTER_CHECKPOINT_SCHEMA,
        state_schema_version="target-e2e-composite-state.v1",
        state_schema_hash="c" * 64,
        command_schema_version="room-graph-command.v1",
        result_schema_version="room-graph-result.v1",
        prompt_version="target-e2e.all-rooms.prompt.v1",
        model_profile_id="target-e2e.all-rooms.model.v1",
        output_schema_version="target-e2e-room-proposal.v1",
        policy_version="target-e2e.proposal-only.v1",
        guardrail_version="target-e2e.guardrails.v1",
        tool_policy_version="target-e2e.no-tools.v1",
        binding_hash="b" * 64,
        code_build_id="p9-graph-hearing-1",
    )
    admission = SimpleNamespace(
        command=command,
        thread=thread,
        record=SimpleNamespace(execution_mode=lane),
        binding=SimpleNamespace(
            execution_lane=lane,
            activation_id=ACTIVATION_ID,
            room_fencing_token=ROOM_FENCE,
            command_hash=canonical_sha256(
                command.model_dump(mode="json", exclude_none=True)
            ),
            command_envelope_hash=COMMAND_ENVELOPE_HASH,
        ),
        registry=SimpleNamespace(
            state="ACTIVE_CANDIDATE",
            loadable=True,
            binding=binding,
        ),
        candidate_authority=SimpleNamespace(
            activation_id=ACTIVATION_ID,
            context=SimpleNamespace(allowedRoomTypes=("HEARING",)),
        ),
    )
    execution = GatewayExecution(
        admission=admission,
        attempt=None,  # type: ignore[arg-type]
        lease=None,  # type: ignore[arg-type]
        fence=fence,
    )
    request = _Request(
        stage_code=operation_binding.request_stage_code,
        stage_sequence=command.stage_sequence,
    )
    return execution, request


def test_target_registry_explicitly_covers_15_stage_flow_and_model_operations() -> None:
    registry = target_e2e_hearing_family_registrations()

    assert len(HEARING_WORKFLOW_STAGE_CODES) == len(set(HEARING_WORKFLOW_STAGE_CODES)) == 15
    assert len(registry) == 4
    assert set(HEARING_TARGET_E2E_OPERATION_BINDINGS) == set(HearingOperation)
    assert {
        node
        for binding in HEARING_TARGET_E2E_OPERATION_BINDINGS.values()
        for node in binding.model_nodes
    } == set(HEARING_MODEL_NODE_PROMPTS)
    assert {
        binding.result_schema_version
        for binding in HEARING_TARGET_E2E_OPERATION_BINDINGS.values()
    } == {
        "hearing_intake_questions.v1",
        "hearing_intake_synthesis.v1",
        "hearing_evidence_requests.v1",
        "hearing_evidence_synthesis.v1",
        "hearing_judge_v1.v1",
        "hearing_jury_review.v1",
        "hearing_judge_v2.v1",
    }


def test_all_target_model_nodes_keep_prompt_pipe_model_pipe_parser_flow() -> None:
    runner = _DeterministicModelRunner()

    results = {
        node_name: invoke_hearing_lcel(
            model_runner=runner,
            node_name=node_name,
            case_data={"fixture": "p9-hearing-001", "node": node_name},
            output_type=_LcelOutput,
        )
        for node_name in HEARING_MODEL_NODE_PROMPTS
    }

    assert runner.nodes == list(HEARING_MODEL_NODE_PROMPTS)
    assert {name: result.value for name, result in results.items()} == {
        name: name for name in HEARING_MODEL_NODE_PROMPTS
    }


def test_composite_provider_exports_exact_hearing_room_and_stream_seam() -> None:
    execution, _ = _execution(HearingOperation.INTAKE_QUESTIONS)

    provider = build_target_e2e_hearing_provider(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=_InvocationProvider(),
        payload_store=_PayloadStore(),
    )

    assert provider.room_type is RoomType.HEARING
    assert callable(provider.stream)
    assert callable(provider.execute)


@pytest.mark.parametrize("operation", list(HearingOperation))
def test_target_adapter_runs_each_exact_family_and_returns_proposal_only_source(
    operation: HearingOperation,
) -> None:
    execution, _ = _execution(operation)
    provider = _InvocationProvider()
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=provider,
        payload_store=store,
    )

    material = asyncio.run(adapter.execute(execution))

    assert provider.calls == [operation]
    assert len(store.calls) == 1
    (
        stored_operation,
        proposal_id,
        payload_bytes,
        payload_hash,
        checkpoint_ns,
        checkpoint_id,
        cognitive_revision,
    ) = store.calls[0]
    expected_payload = _Proposal(
        schema_version=HEARING_TARGET_E2E_OPERATION_BINDINGS[
            operation
        ].result_schema_version
    ).model_dump(mode="json")
    assert stored_operation is operation
    assert proposal_id == material.source.proposal.proposal_id
    assert payload_bytes == canonicalize(expected_payload)
    assert payload_hash == canonical_sha256(expected_payload)
    assert checkpoint_ns == material.checkpoint_ns
    assert checkpoint_id == material.checkpoint_id
    assert cognitive_revision == material.cognitive_revision
    assert material.source == HearingTargetE2EProposalSource.model_validate(
        material.source.model_dump(mode="json")
    )
    assert material.source.room_type == "HEARING"
    assert material.source.proposal.schema_version == "target-e2e-hearing-proposal.v1"
    assert material.source.proposal.payload_hash == payload_hash
    assert material.source.proposal.formal_authority is False
    assert material.proposal_hash == canonical_sha256(
        material.source.proposal.model_dump(mode="json")
    )
    assert material.checkpoint_ns == ""
    assert material.checkpoint_id
    assert material.cognitive_revision >= 1
    assert material.formal_sink_eligible is False


def test_target_recovery_reuses_checkpoint_without_repeating_model_execution() -> None:
    operation = HearingOperation.JUDGE_V1
    execution, _ = _execution(operation)
    provider = _InvocationProvider()
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=provider,
        payload_store=store,
    )

    first = asyncio.run(adapter.execute(execution))
    replay = asyncio.run(adapter.execute(execution))

    assert provider.calls == [operation, operation]
    assert provider.model_calls == 1
    assert first.source == replay.source
    assert first.runtime_binding_sha256 == replay.runtime_binding_sha256


def test_hearing_intake_closed_world_fact_violation_repairs_once_and_replays_exactly() -> None:
    operation = HearingOperation.INTAKE_QUESTIONS
    execution, _ = _execution(operation)
    matrix_payload: dict[str, Any] = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": execution.admission.command.case_id,
        "matrix_id": "CASE_MATRIX_hearing_target",
        "matrix_version": 2,
        "matrix_kind": "BILATERAL_FROZEN",
        "parent_ref": None,
        "content_hash": "0" * 64,
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "source_refs": ["SOURCE_USER", "SOURCE_MERCHANT"],
        "case_overview": {
            "neutral_summary": "用户称未收到商品，商家称物流已签收。",
            "core_conflict": "包裹是否实际交付。",
            "summary_source_fact_ids": ["FACT_DELIVERY"],
        },
        "claims": {
            "initiator_claim": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                "requested_amount": 100.0,
                "requested_items": "商品",
                "reason_summary": "未收到商品。",
                "position_summary": "用户要求退款。",
                "source_refs": ["SOURCE_USER"],
            },
            "respondent_reported_by_initiator": None,
            "respondent_direct": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position_summary": "商家认为已经签收。",
                "alternative_proposal": None,
                "source_refs": ["SOURCE_MERCHANT"],
            },
            "claim_conflict": "双方对实际交付有争议。",
        },
        "fact_rows": [
            {
                "fact_id": "FACT_DELIVERY",
                "category": "LOGISTICS",
                "fact_target": "物流系统记录包裹已签收",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": ["SOURCE_USER"],
                },
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户否认本人收到。",
                        "asserted_value": "未收到",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_USER"],
                    },
                    "MERCHANT": {
                        "stance": "CONFIRM",
                        "position_summary": "商家确认物流已签收。",
                        "asserted_value": "已签收",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["SOURCE_MERCHANT"],
                    },
                },
                "party_alignment": {
                    "status": "CONTESTED",
                    "agreed_statement": None,
                    "conflict_summary": "是否实际交付存在争议。",
                },
                "requires_resolution": True,
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "COVERED_BY_FROZEN_DOSSIER",
            }
        ],
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": "MERCHANT",
            "source_stage": "RESPONDENT_INTAKE",
            "latest_source_ref": "SOURCE_MERCHANT",
            "source_context_hash": "b" * 64,
        },
        "fact_indexes": {
            "not_computed_fact_ids": [],
            "agreed_fact_ids": [],
            "partially_agreed_fact_ids": [],
            "contested_fact_ids": ["FACT_DELIVERY"],
            "one_sided_fact_ids": [],
            "unresolved_fact_ids": [],
            "core_fact_ids": ["FACT_DELIVERY"],
            "requires_resolution_fact_ids": ["FACT_DELIVERY"],
        },
    }
    normalized_matrix = CaseFactMatrixV2.model_validate(matrix_payload).model_dump(
        mode="json"
    )
    normalized_matrix["content_hash"] = content_hash(
        normalized_matrix,
        hash_field="content_hash",
    )
    matrix = CaseFactMatrixV2.model_validate(normalized_matrix)
    request = HearingIntakeQuestionsRequest.model_validate(
        {
            "flow_schema_version": "hearing_flow.v2",
            "case_id": execution.admission.command.case_id,
            "workflow_id": "WORKFLOW_hearing_target",
            "stage_code": "INTAKE_QUESTIONS",
            "stage_sequence": execution.admission.command.stage_sequence,
            "case_fact_matrix": matrix.model_dump(mode="json"),
            "max_questions": 1,
        }
    )
    invalid = {
        "questions": [
            {
                "fact_ids": ["FACT_UNKNOWN_BUT_WELL_FORMED"],
                "issue_statement": "请双方说明包裹是否实际交付。",
                "party_prompts": {
                    "USER": "请用户说明是否实际收到包裹。",
                    "MERCHANT": "请商家说明物流签收依据。",
                },
            }
        ],
        "public_message": "该无效引用响应不得公开。",
    }
    corrected = {
        **invalid,
        "questions": [{**invalid["questions"][0], "fact_ids": ["FACT_DELIVERY"]}],
        "public_message": "请双方围绕交付事实作答。",
    }

    def client_with(outputs: list[dict[str, Any]]):
        calls: list[dict[str, Any]] = []

        def handler(provider_request: httpx.Request) -> httpx.Response:
            body = json.loads(provider_request.content)
            calls.append(body)
            output = outputs[min(len(calls) - 1, len(outputs) - 1)]
            return httpx.Response(
                200,
                json={
                    "model": "hearing-test-model",
                    "choices": [
                        {"message": {"content": json.dumps(output, ensure_ascii=False)}}
                    ],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15,
                    },
                },
            )

        transport = httpx.MockTransport(handler)
        client = LiteLlmProxyClient(
            "http://litellm:4000",
            "hearing-test-model",
            "test-key",
            transport=transport,
            async_transport=transport,
        )
        return client, calls

    client, provider_calls = client_with([invalid, corrected])
    workflows = HearingFlowWorkflows(
        HarnessModelRunner(llm=client, prompts=PromptRepository())
    )

    class _GovernedProvider:
        async def load(
            self,
            current_execution: GatewayExecution,
        ) -> HearingTargetE2ELoadedInvocation:
            invocation = workflows.target_e2e_invocation(operation, request)
            command = current_execution.admission.command
            return HearingTargetE2ELoadedInvocation(
                operation=operation,
                request=request,
                invocation=invocation,
                snapshot_uri=command.domain_snapshot_ref.uri,
                snapshot_hash=command.domain_snapshot_ref.sha256,
                event_uri=None,
                event_hash=None,
            )

    saver = _MemoryFencedSaver(execution.fence)
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=saver,
        invocation_provider=_GovernedProvider(),
        payload_store=store,
    )
    published = []
    observer = AgentStreamObserver(
        operation="hearing_intake_questions",
        run_id="RUN_hearing_semantic_repair",
        publish=published.append,
    )
    with bind_stream_observer(observer):
        first = asyncio.run(adapter.execute(execution))
    replay = asyncio.run(adapter.execute(execution))

    assert len(provider_calls) == 2
    assert "response_format" in provider_calls[0]
    assert "response_format" not in provider_calls[1]
    assert provider_calls[0]["response_format"]["json_schema"]["schema"] == (
        HearingIntakeQuestionsLlmOutput.model_json_schema()
    )
    assert len(store.calls) == 2
    assert store.calls[0][2] == store.calls[1][2]
    assert first.source.model_dump_json() == replay.source.model_dump_json()
    assert first.proposal_hash == replay.proposal_hash
    assert [event.type for event in published] == ["visible_delta", "usage"]
    assert published[0].node_name == "hearing_intake_questions"
    assert published[0].field == "public_message"
    assert published[0].delta == corrected["public_message"]

    exhausted_client, exhausted_calls = client_with([invalid, invalid])
    exhausted_workflows = HearingFlowWorkflows(
        HarnessModelRunner(llm=exhausted_client, prompts=PromptRepository())
    )
    with pytest.raises(ModelTransportOutputError) as exhausted:
        exhausted_workflows.intake_questions(request)
    assert exhausted.value.safe_code == "AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED"
    assert len(exhausted_calls) == 2
    assert _model_transport_output_error_code(exhausted.value) == (
        "AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED"
    )
    assert _model_transport_output_error_code(
        AgentOutputSchemaError(
            "hearing_intake_questions",
            "private detail",
            safe_code="PRIVATE_UNKNOWN_OUTPUT_CODE",
        )
    ) == "AGENT_OUTPUT_SCHEMA_INVALID"

    invalid_matrix_payload = matrix.model_dump(mode="json")
    invalid_matrix_payload["content_hash"] = "0" * 64
    invalid_request = request.model_copy(
        update={"case_fact_matrix": CaseFactMatrixV2.model_validate(invalid_matrix_payload)}
    )
    calls_before_negative = len(provider_calls)
    with pytest.raises(AgentOutputSchemaError) as deterministic:
        workflows.intake_questions(invalid_request)
    assert deterministic.value.safe_code == "AGENT_OUTPUT_SCHEMA_INVALID"
    assert len(provider_calls) == calls_before_negative


def test_target_adapter_rejects_shadow_lane_before_provider_or_store() -> None:
    operation = HearingOperation.INTAKE_QUESTIONS
    execution, _ = _execution(operation, lane="SHADOW")
    provider = _InvocationProvider()
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=provider,
        payload_store=store,
    )

    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_TARGET_GATEWAY_CONTEXT_INVALID",
    ):
        asyncio.run(adapter.execute(execution))
    assert provider.calls == []
    assert store.calls == []


@pytest.mark.parametrize(
    ("identity_field", "identity_value"),
    (
        ("execution_provider", " "),
        ("execution_model", "\t"),
    ),
)
def test_target_adapter_rejects_blank_frozen_execution_identity(
    identity_field: str,
    identity_value: str,
) -> None:
    operation = HearingOperation.INTAKE_QUESTIONS
    execution, _ = _execution(operation)
    execution = replace(
        execution,
        fence=replace(execution.fence, **{identity_field: identity_value}),
    )
    provider = _InvocationProvider()
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=provider,
        payload_store=store,
    )

    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_TARGET_AUTHORITY_BINDING_MISMATCH",
    ):
        asyncio.run(adapter.execute(execution))
    assert provider.calls == []
    assert store.calls == []


def test_target_adapter_rejects_wrong_operation_stage_before_model_execution() -> None:
    operation = HearingOperation.JUDGE_V2
    execution, _ = _execution(operation)
    provider = _InvocationProvider(request_stage_override="JURY_REVIEW")
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=provider,
        payload_store=store,
    )

    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_TARGET_OPERATION_BINDING_MISMATCH",
    ):
        asyncio.run(adapter.execute(execution))
    assert provider.calls == [operation]
    assert store.calls == []


def test_target_adapter_rejects_loaded_input_outside_verified_snapshot() -> None:
    operation = HearingOperation.EVIDENCE_REQUESTS
    execution, _ = _execution(operation)

    class _MismatchedProvider(_InvocationProvider):
        async def load(
            self,
            execution: GatewayExecution,
        ) -> HearingTargetE2ELoadedInvocation:
            loaded = await super().load(execution)
            return replace(loaded, snapshot_hash="f" * 64)

    provider = _MismatchedProvider()
    store = _PayloadStore()
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=_MemoryFencedSaver(execution.fence),
        invocation_provider=provider,
        payload_store=store,
    )

    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_TARGET_LOADED_INVOCATION_MISMATCH",
    ):
        asyncio.run(adapter.execute(execution))
    assert provider.model_calls == 0
    assert store.calls == []


def test_target_adapter_surface_does_not_accept_activation_jws() -> None:
    parameters = inspect.signature(HearingTargetE2ERuntimeAdapter.execute).parameters

    assert "activation_jws" not in parameters
    assert "activation_token" not in parameters
    assert "manifest_jws" not in parameters


def test_target_stream_binds_frozen_execution_identity_before_external_terminal_commit() -> None:
    execution, _ = _execution(HearingOperation.JUDGE_V2)
    saver = _MemoryFencedSaver(execution.fence)
    adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=saver,
        invocation_provider=_InvocationProvider(),
        payload_store=_PayloadStore(),
    )

    async def collect() -> list[Any]:
        return [event async for event in adapter.stream(execution)]

    events = asyncio.run(collect())

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert len(saver.external_terminal_commits) == 1
    _, commit = saver.external_terminal_commits[0]
    result = commit.result
    assert result.proposal_source_json is not None
    assert result.result_envelope_json is not None
    envelope = TargetE2EGraphResultEnvelope.model_validate(result.result_envelope_json)

    assert envelope.execution_provider == execution.fence.execution_provider
    assert envelope.execution_model == execution.fence.execution_model
    assert envelope.proposal_hash == result.proposal_hash
    assert envelope.result_envelope_hash == result.result_envelope_hash


@pytest.mark.asyncio
async def test_target_stream_forwards_governed_delta_before_completion_and_replays_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    operation = HearingOperation.JUDGE_V2
    execution, _ = _execution(operation)
    binding = HEARING_TARGET_E2E_OPERATION_BINDINGS[operation]
    node_name = binding.model_nodes[-1]
    public_text = "裁判草案已生成，正在进行形式校验。"
    fixed_time = datetime(2026, 8, 17, 1, 2, 3, tzinfo=timezone.utc)
    original_bundle_builder = hearing_target_e2e.build_target_e2e_hearing_runtime_bundle

    class _SlowStreamingBundle:
        runtime_binding_sha256 = "d" * 64

        def __init__(self, *, terminal_public_text: str = public_text) -> None:
            self.native_delta_available = asyncio.Event()
            self.release_completion = asyncio.Event()
            self.execute_completed = False
            self.stream_closed = False
            self.state = {
                "status": "PROPOSED",
                "proposal": {
                    "schema_version": binding.result_schema_version,
                    "case_id": execution.admission.command.case_id,
                    "public_message": terminal_public_text,
                },
                "cognitive_revision": 1,
            }

        async def arun(self) -> dict[str, Any]:
            # This is the old buffered seam: native output exists, but arun does
            # not return until the complete graph execution is released.
            self.native_delta_available.set()
            await self.release_completion.wait()
            self.execute_completed = True
            return self.state

        async def astream(self) -> AsyncIterator[Any]:
            try:
                self.native_delta_available.set()
                yield (
                    "messages",
                    (
                        AIMessageChunk(
                            content="",
                            additional_kwargs={
                                "governed_events": [
                                    {
                                        "schema_version": "governed-model-event.v1",
                                        "event_type": "visible_delta",
                                        "node_name": node_name,
                                        "field": "public_message",
                                        "delta": public_text,
                                    }
                                ]
                            },
                        ),
                        {"langgraph_node": node_name},
                    ),
                )
                await self.release_completion.wait()
                self.execute_completed = True
            finally:
                self.stream_closed = True

        async def completed_state(self) -> dict[str, Any]:
            assert self.execute_completed
            return self.state

        async def terminal_checkpoint(self) -> tuple[str, str, int]:
            assert self.execute_completed
            return "", "checkpoint-hearing-stream", 1

    async def run_valid(bundle: _SlowStreamingBundle) -> tuple[list[Any], _MemoryFencedSaver, _PayloadStore]:
        saver = _MemoryFencedSaver(execution.fence)
        store = _PayloadStore()
        adapter = HearingTargetE2ERuntimeAdapter(
            checkpointer=saver,
            invocation_provider=_InvocationProvider(),
            payload_store=store,
            clock=lambda: fixed_time,
        )
        monkeypatch.setattr(
            hearing_target_e2e,
            "build_target_e2e_hearing_runtime_bundle",
            lambda **_: bundle,
        )
        source = adapter.stream(execution)
        events = [await anext(source)]
        pending_delta = asyncio.create_task(anext(source))
        await bundle.native_delta_available.wait()
        timed_out = False
        try:
            delta = await asyncio.wait_for(asyncio.shield(pending_delta), timeout=0.1)
        except TimeoutError:
            timed_out = True
            bundle.release_completion.set()
            await pending_delta
            delta = None
        assert not timed_out, "visible_delta was buffered behind complete arun"
        assert delta is not None and delta.event_type == "visible_delta"
        events.append(delta)
        assert not bundle.execute_completed
        assert store.calls == []
        assert saver.external_terminal_commits == []
        bundle.release_completion.set()
        events.extend([event async for event in source])
        return events, saver, store

    first_events, first_saver, first_store = await run_valid(_SlowStreamingBundle())
    second_events, second_saver, second_store = await run_valid(_SlowStreamingBundle())

    assert [event.event_type for event in first_events] == [
        "attempt_started",
        "visible_delta",
        "final",
    ]
    assert [event.sequence_no for event in first_events] == [0, 1, 2]
    assert first_events[1].payload.field == "public_message"
    assert first_events[1].payload.delta == public_text
    assert all(event.attempt_id == execution.admission.command.attempt_id for event in first_events)
    assert all(event.audience == execution.admission.command.actor_scope.audience for event in first_events)
    assert len(first_store.calls) == len(second_store.calls) == 1
    assert len(first_saver.external_terminal_commits) == len(second_saver.external_terminal_commits) == 1
    assert [event.model_dump_json() for event in first_events] == [
        event.model_dump_json() for event in second_events
    ]

    invalid_bundle = _SlowStreamingBundle(terminal_public_text="冲突的终态公开文本")
    invalid_saver = _MemoryFencedSaver(execution.fence)
    invalid_store = _PayloadStore()
    invalid_adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=invalid_saver,
        invocation_provider=_InvocationProvider(),
        payload_store=invalid_store,
        clock=lambda: fixed_time,
    )
    monkeypatch.setattr(
        hearing_target_e2e,
        "build_target_e2e_hearing_runtime_bundle",
        lambda **_: invalid_bundle,
    )
    invalid_bundle.release_completion.set()
    invalid_events: list[Any] = []
    with pytest.raises(
        HearingGraphContractError,
        match="HEARING_TARGET_VISIBLE_TERMINAL_MISMATCH",
    ):
        async for event in invalid_adapter.stream(execution):
            invalid_events.append(event)
    assert [event.event_type for event in invalid_events] == [
        "attempt_started",
        "visible_delta",
    ]
    assert invalid_store.calls == []
    assert invalid_saver.external_terminal_commits == []

    cancelled_bundle = _SlowStreamingBundle()
    cancelled_saver = _MemoryFencedSaver(execution.fence)
    cancelled_store = _PayloadStore()
    cancelled_adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=cancelled_saver,
        invocation_provider=_InvocationProvider(),
        payload_store=cancelled_store,
        clock=lambda: fixed_time,
    )
    monkeypatch.setattr(
        hearing_target_e2e,
        "build_target_e2e_hearing_runtime_bundle",
        lambda **_: cancelled_bundle,
    )
    cancelled_source = cancelled_adapter.stream(execution)
    assert (await anext(cancelled_source)).event_type == "attempt_started"
    assert (await anext(cancelled_source)).event_type == "visible_delta"
    await cancelled_source.aclose()
    assert cancelled_bundle.stream_closed
    assert not cancelled_bundle.execute_completed
    assert cancelled_store.calls == []
    assert cancelled_saver.external_terminal_commits == []

    # Keep the same selector on the real compiled Hearing bundle seam so the
    # LangGraph multi-mode astream signature and terminal checkpoint path are covered.
    monkeypatch.setattr(
        hearing_target_e2e,
        "build_target_e2e_hearing_runtime_bundle",
        original_bundle_builder,
    )
    actual_saver = _MemoryFencedSaver(execution.fence)
    actual_adapter = HearingTargetE2ERuntimeAdapter(
        checkpointer=actual_saver,
        invocation_provider=_InvocationProvider(),
        payload_store=_PayloadStore(),
        clock=lambda: fixed_time,
    )
    actual_events = [event async for event in actual_adapter.stream(execution)]
    assert [event.event_type for event in actual_events] == ["attempt_started", "final"]
    assert len(actual_saver.external_terminal_commits) == 1
