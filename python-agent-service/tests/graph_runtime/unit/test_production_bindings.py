from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.api.graph_lifecycle import GraphExecutorKernel
from app.config import GraphShadowBindingSettings, Settings
from app.contracts.v1.codec import canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand
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
    build_governed_intake_runtime,
    build_intake_execution_state,
    canonical_intake_proposal,
    decode_authorized_intake_ingress,
)
from app.graph_runtime.persistence_models import GraphFenceContext
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
COMMAND_FIXTURE = (
    ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-command-valid.json"
)
BASE_SETTINGS = {
    "litellm_master_key": "test-litellm-master-key",
    "langfuse_public_key": "test-public-key",
    "langfuse_secret_key": "test-secret-key",
    "java_service_secret": "test-java-service-secret",
    "python_agent_service_secret": "test-python-service-secret",
}


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


def _settings(
    command: RoomGraphCommand | None = None,
    *,
    binding: dict[str, Any] | None = None,
) -> Settings:
    selected = command or _command()
    return Settings(
        **BASE_SETTINGS,
        graph_gateway_mode="SHADOW",
        graph_database_dsn=(
            "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"
        ),
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
        actor_scope=ActorScopeBinding.from_json(
            command.actor_scope.model_dump(mode="json")
        ),
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
    assert await runtime.thread_identity_resolver.resolve(
        command=command,
        verified_reconciliation=_verified_reconciliation(command),
    ) == thread

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
        claims=_verified(command).claims.model_copy(
            update={"profile_bindings_hash": "e" * 64}
        ),
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


def test_exact_intake_graph_key_never_falls_back_to_the_phase3_kernel() -> None:
    command, _, _ = _intake_command()

    with pytest.raises(ValueError, match="terminal checkpoint protocol.*proposal storage"):
        _executor_registration(
            _intake_config(command),
            GraphExecutorKernel(
                saver=cast(Any, InMemorySaver()), gateway=cast(Any, object())
            ),
        )


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
                canonical_payload=payload,
            ),
        )


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
    assert final_snapshot.values["result_json"] == {
        "status": "PENDING_TERMINAL_COMMIT"
    }

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
        graph_database_dsn=(
            "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"
        ),
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        graph_expected_environment_generation="graphenv-test-001",
        graph_expected_restore_verification_hash="c" * 64,
    )
    with pytest.raises(ValueError, match="incomplete"):
        build_graph_runtime_bindings(incomplete)


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
