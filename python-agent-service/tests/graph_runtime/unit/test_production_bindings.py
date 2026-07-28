from __future__ import annotations

import base64
from dataclasses import replace
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any, TypedDict, cast

import pytest
import httpx
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from app.api.graph_lifecycle import GraphExecutorKernel
from app.config import GraphShadowBindingSettings, Settings
from app.contracts.v1.codec import canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand, SnapshotRef
from app.graph_runtime.compiled_executor import CompiledGraphShadowExecutor
from app.graph_runtime.errors import (
    GraphContractError,
    GraphThreadBindingError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import (
    ActorScopeBinding,
    RoomType,
    ThreadIdentity,
    ThreadLifecycle,
    ThreadRecord,
)
from app.graph_runtime.intake_binding import (
    LoadedIntakePayload,
    StoredIntakeProposal,
    build_governed_intake_runtime,
    build_intake_execution_state,
    canonical_intake_proposal,
    decode_authorized_intake_ingress,
)
from app.graph_runtime.intake_exchange import (
    INTAKE_PAYLOAD_LOAD_PATH,
    INTAKE_PROPOSAL_PUT_PATH,
    JavaIntakeExchangeClient,
)
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graph_runtime.intake_executor import CompiledIntakeGraphShadowExecutor
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.checkpoint import FENCE_CONTEXT_KEY, bind_fence_context
from app.graph_runtime.production_bindings import (
    _advance_revision,
    _initial_state,
    _project_synthetic_result,
    _terminal_plan,
    _validate_synthetic_state,
    _executor_registration,
    build_graph_runtime_bindings,
)
from app.graph_runtime.registry import RegistryRecord, RegistryState, VersionBinding
from app.security.invocation_envelope import (
    InvocationClaims,
    ReconciliationClaims,
    VerifiedInvocation,
    VerifiedReconciliation,
    invocation_binding_claims,
)
from app.model_runtime.transports import ModelTransportRequest


ROOT = Path(__file__).resolve().parents[4]
COMMAND_FIXTURE = ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-command-valid.json"
BASE_SETTINGS = {
    "litellm_master_key": "test-litellm-master-key",
    "langfuse_public_key": "test-public-key",
    "langfuse_secret_key": "test-secret-key",
    "java_service_secret": "test-java-service-secret",
    "python_agent_service_secret": "test-python-service-secret",
}


class _CheckpointProbeState(TypedDict):
    marker: str


def _command() -> RoomGraphCommand:
    document = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    document["graph_key"] = "phase3.synthetic.v1"
    document["invocation_context"]["tool_capabilities"] = []
    document["request_hash"] = canonical_sha256_omitting(document, "request_hash")
    return RoomGraphCommand.model_validate(document)


def _intake_command() -> tuple[RoomGraphCommand, dict[str, Any], bytes]:
    snapshot = json.loads(
        (
            ROOT
            / "contracts/agent-platform/intake/v2/fixtures/valid/intake-domain-snapshot-valid.json"
        ).read_text(encoding="utf-8")
    )
    payload = canonicalize(snapshot)
    registration = json.loads(
        (
            ROOT
            / "contracts/agent-platform/intake/v2/fixtures/valid/graph-private-thread-registration-valid.json"
        ).read_text(encoding="utf-8")
    )
    document = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    document.update(
        {
            "command_id": "COMMAND_P4_USER_1",
            "logical_run_id": "RUN_P4_USER_1",
            "attempt_id": "ATTEMPT_P4_USER_1_1",
            "tenant_surrogate": snapshot["tenant_surrogate"],
            "case_id": snapshot["case_id"],
            "room_epoch": snapshot["room_epoch"],
            "graph_key": registration["graph_key"],
            "graph_version": registration["graph_version"],
            "checkpoint_schema_version": registration["checkpoint_schema_version"],
            "thread_id": snapshot["thread_id"],
            "actor_scope": registration["actor_scope"],
            "domain_snapshot_ref": {
                "artifact_id": snapshot["snapshot_id"],
                "schema_version": snapshot["schema_version"],
                "uri": "s3://graph-input/intake/snapshot-p4-user-1.json",
                "sha256": snapshot["snapshot_hash"],
                "size_bytes": len(payload),
            },
        }
    )
    document["invocation_context"].update(
        {
            "agent_profile_id": "intake-agent.synthetic.v2",
            "prompt_profile_id": registration["prompt_version"],
            "model_profile_id": registration["model_profile_id"],
            "output_schema_version": registration["output_schema_version"],
            "policy_version": registration["policy_version"],
            "guardrail_version": registration["guardrail_version"],
            "tool_capabilities": [],
        }
    )
    document["request_hash"] = canonical_sha256_omitting(document, "request_hash")
    return RoomGraphCommand.model_validate(document), snapshot, payload


def _binding(command: RoomGraphCommand) -> dict[str, Any]:
    invocation = command.invocation_context
    return {
        "graph_key": command.graph_key,
        "graph_version": command.graph_version,
        "checkpoint_schema_version": command.checkpoint_schema_version,
        "state_schema_version": "intake.state.v2",
        "state_schema_hash": "a" * 64,
        "command_schema_version": command.schema_version,
        "result_schema_version": "room-graph-result.v1",
        "agent_profile_id": invocation.agent_profile_id,
        "prompt_version": invocation.prompt_profile_id,
        "model_profile_id": invocation.model_profile_id,
        "output_schema_version": invocation.output_schema_version,
        "policy_version": invocation.policy_version,
        "guardrail_version": invocation.guardrail_version,
        "tool_policy_version": "tools.none.v1",
        "binding_hash": "b" * 64,
        "code_build_id": "phase3.synthetic.v1",
        "allowed_room_types": [command.room_type],
        "allowed_stage_codes": [command.stage_code],
    }


def _thread(command: RoomGraphCommand) -> dict[str, Any]:
    scope = command.actor_scope
    reference = command.domain_snapshot_ref
    return {
        "thread_id": command.thread_id,
        "tenant_surrogate": command.tenant_surrogate,
        "case_id": command.case_id,
        "room_type": command.room_type,
        "room_epoch": command.room_epoch,
        "actor_id": scope.actor_id,
        "actor_role": scope.actor_role,
        "audience": scope.audience,
        "actor_capabilities": list(scope.capabilities),
        "agent_session_id": "synthetic.session.v1",
        "shared_session": False,
        "graph_key": command.graph_key,
        "graph_version": command.graph_version,
        "checkpoint_schema_version": command.checkpoint_schema_version,
        "request_hash": command.request_hash,
        "allowed_inputs": [
            {
                **reference.model_dump(mode="json"),
                "visibility": "PRIVATE",
            }
        ],
    }


def _intake_binding(command: RoomGraphCommand) -> dict[str, Any]:
    binding = _binding(command)
    binding.update(
        {
            "state_schema_version": "intake-graph-state.v2",
            "output_schema_version": "intake-turn-proposal.v2",
            "tool_policy_version": "no-tools.v1",
            "code_build_id": "phase4.intake.v2",
            "allowed_room_types": ["INTAKE"],
        }
    )
    return binding


def _intake_config(command: RoomGraphCommand) -> GraphShadowBindingSettings:
    values = _intake_binding(command)
    return GraphShadowBindingSettings.model_construct(
        **{
            **values,
            "allowed_room_types": tuple(values["allowed_room_types"]),
            "allowed_stage_codes": tuple(values["allowed_stage_codes"]),
        }
    )


def _evidence_config(command: RoomGraphCommand) -> GraphShadowBindingSettings:
    values = _binding(command)
    values.update(
        {
            "graph_key": "evidence.v2",
            "graph_version": "evidence.v2.0.0",
            "checkpoint_schema_version": "evidence.checkpoint.v2",
            "allowed_room_types": ["EVIDENCE"],
        }
    )
    return GraphShadowBindingSettings.model_construct(
        **{
            **values,
            "allowed_room_types": tuple(values["allowed_room_types"]),
            "allowed_stage_codes": tuple(values["allowed_stage_codes"]),
        }
    )


def _settings(
    command: RoomGraphCommand | None = None,
    *,
    binding: dict[str, Any] | None = None,
) -> Settings:
    selected = command or _command()
    return Settings(
        **BASE_SETTINGS,
        graph_gateway_mode="SHADOW",
        graph_database_dsn=("postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"),
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        graph_expected_environment_generation="graphenv-test-001",
        graph_expected_restore_verification_hash="c" * 64,
        graph_shadow_bindings=[binding or _binding(selected)],
        graph_shadow_threads=[_thread(selected)],
    )


def _verified(command: RoomGraphCommand) -> VerifiedInvocation:
    claims = InvocationClaims(
        iss="java-api-service",
        aud="python-agent-service",
        sub="graph-command",
        iat=1,
        nbf=1,
        exp=60,
        jti="synthetic-delivery-1",
        **invocation_binding_claims(
            command,
            registry_binding_hash="b" * 64,
            tool_policy_version="tools.none.v1",
        ),
    )
    return VerifiedInvocation(
        claims=claims,
        key_id=command.invocation_context.envelope_key_id,
        request_hash=command.request_hash,
        transport_certificate_sha256="d" * 64,
    )


def _verified_reconciliation(command: RoomGraphCommand) -> VerifiedReconciliation:
    claims = ReconciliationClaims(
        iss="java-api-service",
        aud="python-agent-service",
        sub="graph-reconcile",
        iat=1,
        nbf=1,
        exp=60,
        jti="synthetic-reconcile-1",
        capability="RECONCILE_ONLY",
        original_envelope_key_id=command.invocation_context.envelope_key_id,
        **invocation_binding_claims(
            command,
            registry_binding_hash="b" * 64,
            tool_policy_version="tools.none.v1",
        ),
    )
    return VerifiedReconciliation(
        claims=claims,
        key_id="java-reconciliation-es256-1",
        request_hash=command.request_hash,
        transport_certificate_sha256="d" * 64,
    )


def _version(command: RoomGraphCommand) -> VersionBinding:
    configured = _binding(command)
    return VersionBinding(
        graph_key=configured["graph_key"],
        graph_version=configured["graph_version"],
        checkpoint_schema_version=configured["checkpoint_schema_version"],
        state_schema_version=configured["state_schema_version"],
        state_schema_hash=configured["state_schema_hash"],
        command_schema_version=configured["command_schema_version"],
        result_schema_version=configured["result_schema_version"],
        prompt_version=configured["prompt_version"],
        model_profile_id=configured["model_profile_id"],
        output_schema_version=configured["output_schema_version"],
        policy_version=configured["policy_version"],
        guardrail_version=configured["guardrail_version"],
        tool_policy_version=configured["tool_policy_version"],
        binding_hash=configured["binding_hash"],
        code_build_id=configured["code_build_id"],
    )


def _intake_version(command: RoomGraphCommand) -> VersionBinding:
    configured = _intake_binding(command)
    return VersionBinding(
        graph_key=configured["graph_key"],
        graph_version=configured["graph_version"],
        checkpoint_schema_version=configured["checkpoint_schema_version"],
        state_schema_version=configured["state_schema_version"],
        state_schema_hash=configured["state_schema_hash"],
        command_schema_version=configured["command_schema_version"],
        result_schema_version=configured["result_schema_version"],
        prompt_version=configured["prompt_version"],
        model_profile_id=configured["model_profile_id"],
        output_schema_version=configured["output_schema_version"],
        policy_version=configured["policy_version"],
        guardrail_version=configured["guardrail_version"],
        tool_policy_version=configured["tool_policy_version"],
        binding_hash=configured["binding_hash"],
        code_build_id=configured["code_build_id"],
    )


class _NoCallIntakeTransport:
    def generate(self, request: ModelTransportRequest):
        raise AssertionError("snapshot initialization must not call the governed model")

    async def agenerate(self, request: ModelTransportRequest):
        raise AssertionError("snapshot initialization must not call the governed model")

    def stream(self, request: ModelTransportRequest):
        raise AssertionError("snapshot initialization must not call the governed model")
        yield

    async def astream(self, request: ModelTransportRequest):
        raise AssertionError("snapshot initialization must not call the governed model")
        yield


def _intake_execution(
    command: RoomGraphCommand,
) -> GatewayExecution:
    identity = ThreadIdentity(
        thread_id=command.thread_id,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType.INTAKE,
        room_epoch=command.room_epoch,
        actor_scope=ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json")),
        agent_session_id="AGENT_SESSION_P4_USER_1",
        shared_session=False,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    registry = RegistryRecord(
        binding=_intake_version(command),
        state=RegistryState.SHADOW,
        loadable=True,
        revision=1,
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="intake-binding-test",
        fencing_token=1,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    execution = GatewayExecution(
        admission=cast(
            Any,
            SimpleNamespace(command=command, registry=registry, thread=identity),
        ),
        attempt=cast(Any, object()),
        lease=cast(Any, object()),
        fence=fence,
        thread_record=ThreadRecord(
            identity=identity,
            lifecycle=ThreadLifecycle.ACTIVE,
            cognitive_revision=0,
            last_checkpoint_ns=None,
            last_checkpoint_id=None,
        ),
    )
    return execution


@pytest.mark.asyncio
async def test_manifest_resolver_and_input_authorizer_accept_only_the_exact_command() -> None:
    command = _command()
    runtime = build_graph_runtime_bindings(_settings(command))

    thread = await runtime.thread_identity_resolver.resolve(
        command=command,
        verified_invocation=_verified(command),
    )
    await runtime.input_authorizer.authorize(command=command, thread=thread)
    assert (
        await runtime.thread_identity_resolver.resolve(
            command=command,
            verified_reconciliation=_verified_reconciliation(command),
        )
        == thread
    )

    forged_hash = command.model_copy(update={"request_hash": "e" * 64})
    with pytest.raises(GraphThreadBindingError, match="self-hash|manifest"):
        await runtime.thread_identity_resolver.resolve(
            command=forged_hash,
            verified_invocation=_verified(forged_hash),
        )

    forged_input = command.model_copy(
        update={
            "domain_snapshot_ref": command.domain_snapshot_ref.model_copy(
                update={"sha256": "f" * 64}
            )
        }
    )
    with pytest.raises(GraphThreadBindingError, match="input"):
        await runtime.input_authorizer.authorize(
            command=forged_input,
            thread=thread,
        )


@pytest.mark.asyncio
async def test_manifest_rejects_runtime_profile_and_tool_overrides() -> None:
    command = _command()
    runtime = build_graph_runtime_bindings(_settings(command))
    thread = await runtime.thread_identity_resolver.resolve(
        command=command,
        verified_invocation=_verified(command),
    )

    forged_context = command.invocation_context.model_copy(
        update={"tool_capabilities": ("order.read",)}
    )
    forged = command.model_copy(update={"invocation_context": forged_context})
    with pytest.raises(GraphThreadBindingError, match="profile.*tools"):
        await runtime.input_authorizer.authorize(command=forged, thread=thread)

    forged_credential = replace(
        _verified(command),
        claims=cast(Any, object()),
    )
    with pytest.raises(GraphThreadBindingError, match="claims type"):
        await runtime.thread_identity_resolver.resolve(
            command=command,
            verified_invocation=forged_credential,
        )

    profile_drift = replace(
        _verified(command),
        claims=_verified(command).claims.model_copy(update={"profile_bindings_hash": "e" * 64}),
    )
    with pytest.raises(GraphThreadBindingError, match="profile_bindings_hash"):
        await runtime.thread_identity_resolver.resolve(
            command=command,
            verified_invocation=profile_drift,
        )


def test_executor_factory_registers_a_real_exact_compiled_executor() -> None:
    command = _command()
    runtime = build_graph_runtime_bindings(_settings(command))
    saver = InMemorySaver()
    registry = runtime.executor_registry_factory(
        GraphExecutorKernel(
            saver=cast(Any, saver),
            gateway=cast(Any, object()),
            durable_bulkhead=cast(Any, object()),
        )
    )
    record = RegistryRecord(
        binding=_version(command),
        state=RegistryState.SHADOW,
        loadable=True,
        revision=1,
    )

    registration = registry.resolve_registration(record)

    assert type(registration.executor) is CompiledGraphShadowExecutor
    assert registration.provider_binding.provider == "none"
    assert registration.provider_binding.model == "deterministic-synthetic"
    assert registration.provider_binding.allowed_nodes == frozenset({"execute_graph"})

    drifted = replace(record, binding=replace(record.binding, binding_hash="e" * 64))
    with pytest.raises(GraphVersionUnavailableError):
        registry.resolve_registration(drifted)


def test_exact_intake_graph_key_requires_all_durable_executor_dependencies() -> None:
    command, _, _ = _intake_command()

    with pytest.raises(ValueError, match="dependencies are incomplete"):
        _executor_registration(
            _intake_config(command),
            GraphExecutorKernel(
                saver=cast(Any, InMemorySaver()),
                gateway=cast(Any, object()),
                durable_bulkhead=cast(Any, object()),
            ),
        )


def test_exact_intake_graph_key_registers_only_the_dedicated_executor() -> None:
    command, _, _ = _intake_command()

    class Exchange:
        async def load(self, execution):  # pragma: no cover - registration only
            raise AssertionError

        async def put(self, execution, **kwargs):  # pragma: no cover - registration only
            raise AssertionError

    registration = _executor_registration(
        _intake_config(command),
        GraphExecutorKernel(
            saver=cast(Any, InMemorySaver()),
            gateway=cast(Any, object()),
            durable_bulkhead=cast(Any, object()),
        ),
        intake_transport=cast(Any, object()),
        intake_exchange=Exchange(),
        intake_provider="litellm",
        intake_model="intake-model",
    )

    assert isinstance(registration.executor, CompiledIntakeGraphShadowExecutor)
    assert registration.provider_binding.provider == "litellm"
    assert registration.provider_binding.model == "intake-model"
    assert registration.provider_binding.allowed_nodes == frozenset({"intake_lcel"})


@pytest.mark.asyncio
async def test_authorized_intake_adapter_builds_the_real_governed_graph_proposal() -> None:
    command, snapshot, payload = _intake_command()
    saver = InMemorySaver()
    execution = _intake_execution(command)
    context = decode_authorized_intake_ingress(
        command=command,
        loaded=LoadedIntakePayload(
            artifact_id=command.domain_snapshot_ref.artifact_id,
            schema_version=command.domain_snapshot_ref.schema_version,
            uri=command.domain_snapshot_ref.uri,
            sha256=command.domain_snapshot_ref.sha256,
            size_bytes=len(payload),
            object_version="version-1",
            canonical_payload=payload,
        ),
    )
    bundle = build_governed_intake_runtime(
        execution=execution,
        transport=cast(Any, _NoCallIntakeTransport()),
        provider="synthetic",
        model="intake-model",
        checkpointer=saver,
    )
    assert bundle.graph.checkpointer is saver
    assert bundle.model_node.model.profile.profile_id == "intake-model.synthetic.v1"

    state = build_intake_execution_state(execution)
    result = await bundle.graph.ainvoke(
        state,
        {"configurable": {"thread_id": command.thread_id}},
        context=context,
    )
    proposal = bundle.terminal_proposal(result)
    artifact = canonical_intake_proposal(proposal)

    assert proposal.schema_version == "intake-turn-proposal.v2"
    assert proposal.command_id == command.command_id
    assert proposal.thread_id == command.thread_id
    assert proposal.actor_scope_hash == snapshot["actor_scope_hash"]
    assert proposal.profile_versions.model_profile_id == "intake-model.synthetic.v1"
    assert proposal.profile_versions.tool_policy_version == "no-tools.v1"
    assert artifact.schema_version == "intake-turn-proposal.v2"
    assert artifact.sha256 == proposal.proposal_hash
    assert artifact.size_bytes == len(artifact.canonical_payload)
    assert b"memory_frame" not in artifact.canonical_payload
    assert b"formal_action" not in artifact.canonical_payload


@pytest.mark.asyncio
async def test_compiled_intake_executor_persists_one_pointer_without_replacing_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, payload = _intake_command()
    execution = _intake_execution(command)
    loaded = LoadedIntakePayload(
        artifact_id=command.domain_snapshot_ref.artifact_id,
        schema_version=command.domain_snapshot_ref.schema_version,
        uri=command.domain_snapshot_ref.uri,
        sha256=command.domain_snapshot_ref.sha256,
        size_bytes=len(payload),
        object_version="version-1",
        canonical_payload=payload,
    )
    context = decode_authorized_intake_ingress(command=command, loaded=loaded)
    source_bundle = build_governed_intake_runtime(
        execution=execution,
        transport=cast(Any, _NoCallIntakeTransport()),
        provider="synthetic",
        model="intake-model",
        checkpointer=InMemorySaver(),
    )
    durable_state = await source_bundle.graph.ainvoke(
        build_intake_execution_state(execution),
        {"configurable": {"thread_id": command.thread_id}},
        context=context,
    )
    proposal_before = dict(durable_state["result_json"])

    class Saver:
        def __init__(self) -> None:
            self.preflights = 0
            self.commits = []

        async def avalidate_external_terminal_checkpoint(self, config, **kwargs):
            self.preflights += 1

        async def acommit_external_terminal(self, config, commit):
            self.commits.append(commit)
            effective = replace(
                execution.fence,
                result_ref=commit.result.result_ref,
                result_hash=commit.result.result_hash,
            )
            configurable = dict(config["configurable"])
            configurable[FENCE_CONTEXT_KEY] = effective
            return {"configurable": configurable}

    saver = Saver()
    final_config = bind_fence_context(
        {
            "configurable": {
                "thread_id": command.thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-terminal",
            }
        },
        execution.fence,
    )

    class Graph:
        checkpointer = saver

        async def astream(self, input, config, **kwargs):
            if False:
                yield None

        async def aget_state(self, config):
            return SimpleNamespace(
                values=durable_state,
                config=final_config,
                next=(),
                tasks=(),
                interrupts=(),
            )

    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(
            graph=Graph(),
            terminal_proposal=source_bundle.terminal_proposal,
        ),
    )

    class Loader:
        async def load(self, selected_execution):
            return loaded

    class Store:
        def __init__(self) -> None:
            self.calls = 0

        async def put(self, selected_execution, *, proposal, **kwargs):
            self.calls += 1
            return StoredIntakeProposal(
                artifact_id=proposal.artifact_id,
                schema_version=proposal.schema_version,
                uri=f"s3://intake-proposals/{proposal.artifact_id}.json",
                object_version="version-1",
                sha256=proposal.sha256,
                size_bytes=proposal.size_bytes,
            )

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=store,
    )

    events = [event async for event in executor.stream(execution)]

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert saver.preflights == 1
    assert store.calls == 1
    assert len(saver.commits) == 1
    result_json = saver.commits[0].result.result_json
    assert len(result_json["artifact_operations"]) == 1
    assert result_json["artifact_operations"][0]["operation"] == "PROPOSE_PATCH"
    assert durable_state["terminal_draft"] == proposal_before
    assert durable_state["result_json"] == proposal_before


@pytest.mark.asyncio
async def test_intake_continuation_resumes_the_durable_checkpoint_not_a_newer_orphan() -> None:
    command, _, _ = _intake_command()
    execution = _intake_execution(command)
    builder = StateGraph(_CheckpointProbeState)
    builder.add_node("complete", lambda state: {})
    builder.add_edge(START, "complete")
    builder.add_edge("complete", END)
    graph = builder.compile(checkpointer=InMemorySaver())
    latest_config = {
        "configurable": {
            "thread_id": command.thread_id,
            "checkpoint_ns": "",
        }
    }

    await graph.ainvoke({"marker": "committed"}, latest_config)
    committed = await graph.aget_state(latest_config)
    committed_id = committed.config["configurable"]["checkpoint_id"]
    await graph.aupdate_state(
        committed.config,
        {"marker": "newer-uncommitted"},
        as_node="complete",
    )
    assert (await graph.aget_state(latest_config)).values["marker"] == "newer-uncommitted"

    assert execution.thread_record is not None
    committed_record = replace(
        execution.thread_record,
        cognitive_revision=1,
        last_checkpoint_ns="",
        last_checkpoint_id=committed_id,
    )
    resume_config = CompiledIntakeGraphShadowExecutor._graph_config(
        replace(execution, thread_record=committed_record)
    )

    assert resume_config["configurable"]["checkpoint_id"] == committed_id
    assert (await graph.aget_state(resume_config)).values["marker"] == "committed"


@pytest.mark.parametrize(
    ("checkpoint_ns", "checkpoint_id"),
    [
        (None, "cp-committed"),
        ("", None),
    ],
)
def test_intake_continuation_rejects_a_partial_durable_checkpoint_pointer(
    checkpoint_ns: str | None,
    checkpoint_id: str | None,
) -> None:
    command, _, _ = _intake_command()
    execution = _intake_execution(command)
    assert execution.thread_record is not None
    inconsistent = replace(
        execution,
        thread_record=replace(
            execution.thread_record,
            last_checkpoint_ns=checkpoint_ns,
            last_checkpoint_id=checkpoint_id,
        ),
    )

    with pytest.raises(GraphContractError, match="must be present together"):
        CompiledIntakeGraphShadowExecutor._graph_config(inconsistent)


def test_intake_ingress_rejects_private_contaminants_before_graph_mutation() -> None:
    command, snapshot, _ = _intake_command()
    contaminated = {**snapshot, "memory_frame": {"other_party": "private"}}
    contaminated["snapshot_hash"] = canonical_sha256_omitting(
        contaminated,
        "snapshot_hash",
    )
    payload = canonicalize(contaminated)
    reference = command.domain_snapshot_ref.model_copy(
        update={"sha256": contaminated["snapshot_hash"], "size_bytes": len(payload)}
    )
    forged_command = command.model_copy(update={"domain_snapshot_ref": reference})

    with pytest.raises(GraphContractError, match="frozen schema"):
        decode_authorized_intake_ingress(
            command=forged_command,
            loaded=LoadedIntakePayload(
                artifact_id=reference.artifact_id,
                schema_version=reference.schema_version,
                uri=reference.uri,
                sha256=reference.sha256,
                size_bytes=reference.size_bytes,
                object_version="version-1",
                canonical_payload=payload,
            ),
        )


@pytest.mark.asyncio
async def test_java_intake_exchange_loads_only_exact_canonical_receipt_bytes() -> None:
    command, _, payload = _intake_command()
    execution = _intake_execution(command)

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == INTAKE_PAYLOAD_LOAD_PATH
        assert request.headers["X-Service-Secret"] == "test-java-service-secret"
        body = json.loads(request.content)
        reference = body["object_ref"]
        return httpx.Response(
            200,
            headers={"content-type": "application/json"},
            json={
                "schema_version": "intake-payload-load-response.v1",
                "authority": body["authority"],
                "receipt": {
                    "schema_version": "intake-payload-load-receipt.v1",
                    "artifact_id": reference["artifact_id"],
                    "content_schema_version": reference["schema_version"],
                    "uri": reference["uri"],
                    "object_version": "version-1",
                    "sha256": reference["sha256"],
                    "size_bytes": reference["size_bytes"],
                },
                "canonical_payload_base64": base64.b64encode(payload).decode("ascii"),
            },
        )

    client = JavaIntakeExchangeClient(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(handler),
    )

    loaded = await client.load(execution)

    assert loaded.object_version == "version-1"
    assert loaded.canonical_payload == payload
    assert loaded.sha256 == command.domain_snapshot_ref.sha256


@pytest.mark.asyncio
async def test_intake_executor_bootstraps_with_two_exact_loads_then_resumes_with_one() -> None:
    command, snapshot, snapshot_payload = _intake_command()
    event = json.loads(
        (
            ROOT
            / "contracts/agent-platform/intake/v2/fixtures/valid/intake-turn-event-valid.json"
        ).read_text(encoding="utf-8")
    )
    event_payload = canonicalize(event)
    event_ref = SnapshotRef(
        artifact_id=event["event_id"],
        schema_version=event["schema_version"],
        uri="s3://graph-input/intake/event-p4-user-2.json",
        sha256=event["event_hash"],
        size_bytes=len(event_payload),
    )
    command = command.model_copy(update={"event_ref": event_ref})
    execution = _intake_execution(command)

    def loaded(reference, payload: bytes) -> LoadedIntakePayload:
        return LoadedIntakePayload(
            artifact_id=reference.artifact_id,
            schema_version=reference.schema_version,
            uri=reference.uri,
            sha256=reference.sha256,
            size_bytes=reference.size_bytes,
            object_version="version-1",
            canonical_payload=payload,
        )

    class Loader:
        def __init__(self) -> None:
            self.calls: list[object | None] = []

        async def load(self, selected_execution, *, object_ref=None):
            assert selected_execution is execution
            self.calls.append(object_ref)
            if object_ref == command.domain_snapshot_ref:
                return loaded(object_ref, snapshot_payload)
            if object_ref == command.event_ref:
                return loaded(object_ref, event_payload)
            raise AssertionError("continuation test must not request an unbound object")

    loader = Loader()
    executor = object.__new__(CompiledIntakeGraphShadowExecutor)
    executor._input_loader = loader

    context = await executor._load_context(execution, execution)

    assert context.ingress_kind == "BOOTSTRAP_EVENT"
    assert context.ingress_payload["snapshot"]["snapshot_hash"] == snapshot["snapshot_hash"]
    assert context.ingress_payload["event"]["event_hash"] == event["event_hash"]
    assert loader.calls == [command.domain_snapshot_ref, command.event_ref]

    resumed = replace(
        execution,
        thread_record=replace(
            execution.thread_record,
            last_checkpoint_ns="",
            last_checkpoint_id="checkpoint-p4-1",
        ),
    )

    class ContinuationLoader:
        def __init__(self) -> None:
            self.calls: list[object | None] = []

        async def load(self, selected_execution, *, object_ref=None):
            assert selected_execution is resumed
            self.calls.append(object_ref)
            assert object_ref is None
            return loaded(command.event_ref, event_payload)

    continuation_loader = ContinuationLoader()
    executor._input_loader = continuation_loader
    resumed_context = await executor._load_context(resumed, resumed)

    assert resumed_context.ingress_kind == "EVENT"
    assert continuation_loader.calls == [None]


@pytest.mark.asyncio
async def test_java_intake_exchange_rejects_payload_hash_mismatch() -> None:
    command, _, payload = _intake_command()
    execution = _intake_execution(command)

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        reference = body["object_ref"]
        tampered = json.loads(payload)
        tampered["case_id"] = "CASE_TAMPERED"
        tampered_payload = canonicalize(tampered)
        return httpx.Response(
            200,
            headers={"content-type": "application/json"},
            json={
                "schema_version": "intake-payload-load-response.v1",
                "authority": body["authority"],
                "receipt": {
                    "schema_version": "intake-payload-load-receipt.v1",
                    "artifact_id": reference["artifact_id"],
                    "content_schema_version": reference["schema_version"],
                    "uri": reference["uri"],
                    "object_version": "version-1",
                    "sha256": reference["sha256"],
                    "size_bytes": len(tampered_payload),
                },
                "canonical_payload_base64": base64.b64encode(tampered_payload).decode("ascii"),
            },
        )

    client = JavaIntakeExchangeClient(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(GraphContractError, match="receipt|hash"):
        await client.load(execution)


@pytest.mark.asyncio
async def test_java_intake_exchange_put_returns_exact_versioned_receipt() -> None:
    command, _, _ = _intake_command()
    execution = _intake_execution(command)
    proposal_document = json.loads(
        (
            ROOT
            / "contracts/agent-platform/intake/v2/fixtures/valid/intake-turn-proposal-valid.json"
        ).read_text(encoding="utf-8")
    )
    proposal_document.update(
        {
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "source_snapshot_hash": command.domain_snapshot_ref.sha256,
        }
    )
    proposal_document.pop("source_event_hash", None)
    proposal_document["proposal_hash"] = canonical_sha256_omitting(
        proposal_document, "proposal_hash"
    )
    proposal = canonical_intake_proposal(proposal_document)

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == INTAKE_PROPOSAL_PUT_PATH
        body = json.loads(request.content)
        assert base64.b64decode(body["proposal"]["canonical_payload_base64"]) == (
            proposal.canonical_payload
        )
        return httpx.Response(
            200,
            headers={"content-type": "application/json"},
            json={
                "schema_version": "intake-proposal-put-response.v1",
                "authority": body["authority"],
                "checkpoint_ns": body["checkpoint_ns"],
                "checkpoint_id": body["checkpoint_id"],
                "cognitive_revision": body["cognitive_revision"],
                "receipt": {
                    "schema_version": "intake-proposal-put-receipt.v1",
                    "artifact_id": proposal.artifact_id,
                    "content_schema_version": proposal.schema_version,
                    "uri": f"s3://intake-proposals/{proposal.artifact_id}.json",
                    "object_version": "version-1",
                    "sha256": proposal.sha256,
                    "size_bytes": proposal.size_bytes,
                },
            },
        )

    client = JavaIntakeExchangeClient(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(handler),
    )

    stored = await client.put(
        execution,
        proposal=proposal,
        checkpoint_ns="",
        checkpoint_id="cp-terminal",
        cognitive_revision=2,
    )

    assert stored.object_version == "version-1"
    assert stored.sha256 == proposal.sha256


def test_settings_allow_no_tools_only_for_exact_intake_graph() -> None:
    command, _, _ = _intake_command()

    intake = _settings(command, binding=_intake_binding(command))

    assert intake.graph_shadow_bindings[0].tool_policy_version == "no-tools.v1"
    invalid = _binding(_command())
    invalid["tool_policy_version"] = "no-tools.v1"
    with pytest.raises(ValueError, match="exact tools.none.v1"):
        _settings(binding=invalid)


def test_intake_dispatch_rejects_version_relabeling_before_registration() -> None:
    command, _, _ = _intake_command()
    drifted = _intake_config(command).model_copy(
        update={"state_schema_version": "phase3.synthetic.state.v1"}
    )

    with pytest.raises(GraphContractError, match="frozen Intake binding"):
        _executor_registration(
            drifted,
            GraphExecutorKernel(
                    saver=cast(Any, InMemorySaver()),
                    gateway=cast(Any, object()),
                    durable_bulkhead=cast(Any, object()),
            ),
        )


@pytest.mark.asyncio
async def test_synthetic_program_advances_each_checkpoint_and_has_no_effects() -> None:
    command = _command()
    runtime = build_graph_runtime_bindings(_settings(command))
    configured_thread = await runtime.thread_identity_resolver.resolve(
        command=command,
        verified_invocation=_verified(command),
    )
    execution = cast(
        GatewayExecution,
        SimpleNamespace(
            thread_record=ThreadRecord(
                identity=configured_thread,
                lifecycle=ThreadLifecycle.ACTIVE,
                cognitive_revision=7,
                last_checkpoint_ns=None,
                last_checkpoint_id=None,
            ),
            admission=SimpleNamespace(
                command=command,
                registry=SimpleNamespace(binding=_version(command)),
            ),
        ),
    )

    state = dict(_initial_state(execution))
    assert state["cognitive_revision"] == 8
    assert _validate_synthetic_state(cast(Any, state)) == {"cognitive_revision": 9}

    state.update(_validate_synthetic_state(cast(Any, state)))
    state.update(_advance_revision(cast(Any, state)))
    assert state["cognitive_revision"] == 10
    state.update(_project_synthetic_result(cast(Any, state)))
    assert state["cognitive_revision"] == 11

    saver = InMemorySaver()
    registry = runtime.executor_registry_factory(
        GraphExecutorKernel(
            saver=cast(Any, saver),
            gateway=cast(Any, object()),
            durable_bulkhead=cast(Any, object()),
        )
    )
    registration = registry.resolve_registration(
        RegistryRecord(
            binding=_version(command),
            state=RegistryState.SHADOW,
            loadable=True,
            revision=1,
        )
    )
    graph = registration.executor._graph
    config = {
        "configurable": {
            "thread_id": command.thread_id,
            "checkpoint_ns": "",
        }
    }
    stream = graph.astream(_initial_state(execution), config, stream_mode="custom")
    assert [item async for item in stream] == []

    terminal_snapshot = await graph.aget_state(config)
    assert terminal_snapshot.values["cognitive_revision"] == 11
    plan = _terminal_plan(execution, terminal_snapshot.values)
    assert plan.bindings.cognitive_revision == 11
    assert plan.bindings.public_event_proposals == ()
    assert plan.bindings.artifact_operations == ()
    assert plan.bindings.usage.total_tokens == 0
    assert plan.draft.status == "COMPLETED"

    saved = await graph.aupdate_state(
        terminal_snapshot.config,
        {
            "cognitive_revision": 12,
            "result_json": {"status": "PENDING_TERMINAL_COMMIT"},
        },
        as_node="project_result",
    )
    final_snapshot = await graph.aget_state(saved)
    assert final_snapshot.values["cognitive_revision"] == 12
    assert final_snapshot.values["result_json"] == {"status": "PENDING_TERMINAL_COMMIT"}

    with pytest.raises(GraphContractError, match="model, tool, or domain effects"):
        _terminal_plan(
            execution,
            {
                **terminal_snapshot.values,
                "usage_by_invocation": {"forged": {"total_tokens": 1}},
            },
        )


def test_default_runtime_builder_never_creates_shadow_bindings_implicitly() -> None:
    disabled = Settings(**BASE_SETTINGS)
    with pytest.raises(ValueError, match="only in SHADOW"):
        build_graph_runtime_bindings(disabled)

    incomplete = Settings(
        **BASE_SETTINGS,
        graph_gateway_mode="SHADOW",
        graph_database_dsn=("postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"),
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        graph_expected_environment_generation="graphenv-test-001",
        graph_expected_restore_verification_hash="c" * 64,
    )
    with pytest.raises(ValueError, match="incomplete"):
        build_graph_runtime_bindings(incomplete)


@pytest.mark.parametrize("durable", [None, object()])
def test_evidence_binding_never_falls_back_to_generic_synthetic_executor(
    durable: object | None,
) -> None:
    configured = _evidence_config(_command())
    kernel = SimpleNamespace(saver=InMemorySaver(), durable_bulkhead=durable)

    with pytest.raises(GraphContractError, match="durable PostgreSQL bulkhead"):
        _executor_registration(configured, cast(GraphExecutorKernel, kernel))

    exact_bulkhead = object.__new__(PostgresGraphFanoutBulkhead)
    exact_kernel = SimpleNamespace(
        saver=InMemorySaver(),
        durable_bulkhead=exact_bulkhead,
    )
    with pytest.raises(GraphContractError, match="exact executor binding is unavailable"):
        _executor_registration(configured, cast(GraphExecutorKernel, exact_kernel))


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("command_schema_version", "room-graph-command.v2"),
        ("result_schema_version", "room-graph-result.v2"),
        ("tool_policy_version", "tools.write.v1"),
    ],
)
def test_manifest_cannot_relabel_the_fixed_no_effect_protocol(
    field: str,
    value: str,
) -> None:
    command = _command()
    binding = _binding(command)
    binding[field] = value

    with pytest.raises(ValueError):
        _settings(command, binding=binding)
