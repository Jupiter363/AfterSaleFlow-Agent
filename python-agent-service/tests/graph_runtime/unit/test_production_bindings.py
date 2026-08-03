from __future__ import annotations

import asyncio
import base64
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any, TypedDict, cast

import httpx
import pytest
from langchain_core.messages import AIMessageChunk
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel

from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
import app.graph_runtime.intake_executor as intake_executor
import app.graph_runtime.production_bindings as production_bindings
from app.api.graph_lifecycle import GraphExecutorKernel
from app.api.graph_stream_service import (
    ExactShadowExecutorRegistry,
    GatewayBackedGraphCommandStreamService,
    GraphStreamAdmissionGate,
)
from app.config import GraphShadowBindingSettings, Settings
from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand, SnapshotRef, Usage
from app.graph_runtime.compiled_executor import (
    CompiledGraphShadowExecutor,
    GraphPublicUpdate,
)
from app.graph_runtime.errors import (
    GraphContractError,
    GraphTerminalBindingError,
    GraphThreadBindingError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.gateway import AdmissionAction, GatewayExecution
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
from app.graph_runtime.intake_executor import CompiledIntakeGraphShadowExecutor
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.recovery import RecoveryAction, RecoveryDecision
from app.graph_runtime.checkpoint import FENCE_CONTEXT_KEY, bind_fence_context
from app.graphs.intake.baseline import BASELINE_INTAKE_NODE_NAME
from app.graphs.intake.contracts import IntakeTurnProposal
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.runtime import IntakeRuntimeBundle
from app.graph_runtime.production_bindings import (
    _advance_revision,
    _initial_state,
    _project_synthetic_result,
    _terminal_plan,
    _target_e2e_executor_registration,
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
from app.llm import GovernedProviderRequest, LiteLlmProxyClient
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportRequest,
    ModelTransportResult,
    StructuredClientTransport,
)


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
    snapshot["initial_case_facts"]["form_source"] = "EXTERNAL_IMPORT"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")
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
            # The retained command fixture is historical. Opening now reaches
            # the governed model preflight, so this synthetic live command must
            # carry an unexpired deadline rather than testing stale-command
            # rejection instead of the baseline Intake binding.
            "deadline_at": "2099-01-01T00:00:00Z",
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
            "prompt_profile_id": "DISPUTE_INTAKE_OFFICER:USER:v1",
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


def _target_settings() -> Settings:
    activation_manifest_hash = "e" * 64
    return Settings(
        **BASE_SETTINGS,
        litellm_base_url="http://model-runtime:4000",
        litellm_model="qwen3.7-plus-target",
        graph_gateway_mode="TARGET_E2E_CANDIDATE",
        graph_database_dsn=("postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"),
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        graph_expected_environment_generation="7",
        graph_expected_restore_verification_hash="a" * 64,
        graph_target_e2e_isolated=True,
        target_e2e_activation_manifest_hash=activation_manifest_hash,
        graph_target_e2e_bindings=[
            {
                "graph_key": "all-rooms.target-e2e.v1",
                "graph_version": "target-e2e-graph.2026-07-27.1",
                "checkpoint_schema_version": "target-e2e-checkpoint.v1",
                "state_schema_version": "target-e2e-room-state.v1",
                "state_schema_hash": "b" * 64,
                "command_schema_version": "room-graph-command.v1",
                "result_schema_version": "room-graph-result.v1",
                "agent_profile_id": "all-rooms-agent.target-e2e.v1",
                "prompt_version": "all-rooms-prompt.target-e2e.v1",
                "model_profile_id": "qwen3.7-plus.structured.v1",
                "output_schema_version": "target-e2e-room-proposal-source.v1",
                "policy_version": "all-rooms-policy.target-e2e.v1",
                "guardrail_version": "all-rooms-guardrail.target-e2e.v1",
                "tool_policy_version": "tools.none.v1",
                "binding_hash": "c" * 64,
                "code_build_id": "candidate-build-1",
                "allowed_room_types": ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"],
                "allowed_stage_codes": ["INTAKE_MESSAGE"],
            }
        ],
        graph_target_e2e_runtime_context={
            "schemaVersion": "graph-target-e2e-runtime-context.v1",
            "executionLane": "TARGET_E2E_CANDIDATE",
            "activationId": f"p9act.v1.{'1' * 32}",
            "activationManifestHash": activation_manifest_hash,
            "environmentId": "target-e2e-local",
            "environmentGeneration": 7,
            "candidateSha": "d" * 40,
            "issuedAt": "2026-07-27T10:00:00Z",
            "expiresAt": "2026-07-27T11:00:00Z",
            "runNonce": "runtime-projection-nonce-0123456789abcdef",
            "tenantSurrogate": "tenant-p9-isolated",
            "caseScope": {
                "mode": "EXPLICIT_CASE_IDS",
                "allowedCaseIds": ["case-p9-001"],
            },
            "allowedRoomTypes": ["INTAKE"],
            "composeProject": "p9_target_e2e",
            "temporalNamespace": "target-e2e-p9",
            "buildBindings": {
                "caseBuildId": "case-build-1",
                "controlBuildId": "control-build-1",
                "agentBuildId": "agent-build-1",
            },
            "imageDigests": {
                "javaApi": f"sha256:{'1' * 64}",
                "temporalControlWorker": f"sha256:{'2' * 64}",
                "temporalAgentWorker": f"sha256:{'3' * 64}",
                "pythonAgent": f"sha256:{'4' * 64}",
                "frontend": f"sha256:{'5' * 64}",
            },
            "databaseIdentities": {
                "domain": {
                    "service": "domain-db",
                    "database": "isolated_domain",
                    "schema": "domain_runtime",
                    "expectedUser": "java_domain_runtime",
                },
                "graph": {
                    "service": "postgresql",
                    "database": "dispute_graph",
                    "schema": "graph_runtime",
                    "runtimeUser": "graph_runtime",
                    "environmentGeneration": 7,
                    "restoreVerificationHash": "a" * 64,
                },
            },
            "trustedSigningKeyIds": ["java-command-key-1"],
            "perCommandManifestAllowed": False,
        },
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


def _strict_baseline_opening_output() -> dict[str, Any]:
    """A valid retained-baseline response for a snapshot-only opening turn."""

    return IntakeCaseDetailLlmOutput.model_validate(
        {
            "room_utterance": "Please confirm the requested resolution.",
            "case_detail": {
                "case_story": {
                    "one_sentence_summary": (
                        "The imported case concerns the reported damaged order."
                    )
                }
            },
            "unilateral_case_matrix": {
                "schema_version": "unilateral_case_matrix.draft.v1",
                "fact_rows": [
                    {
                        "fact_key": "NEW_CASE_SUMMARY",
                        "category": "OTHER",
                        "fact_target": "The order was reported damaged.",
                        "materiality": "CORE",
                        "position_summary": "The user reports a damaged order.",
                        "asserted_value": "The order was reported damaged.",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_CASE_SUMMARY"],
            },
            "admission_recommendation": "NEED_MORE_INFO",
            "missing_fields": ["requested_resolution_detail"],
            "knowledge_query_intent": False,
            "knowledge_answer_mode": "NONE",
            "confidence": 0.82,
        }
    ).model_dump(mode="json", exclude_none=True)


class _StrictBaselineIntakeTransport:
    """Capture and validate the one governed baseline call for an opening form."""

    def __init__(
        self,
        *,
        form_source: str,
        form_description: str,
        agent_session_id: str,
        output: dict[str, Any] | None = None,
    ) -> None:
        self._form_source = form_source
        self._form_description = form_description
        self._agent_session_id = agent_session_id
        self._output = deepcopy(output) if output is not None else _strict_baseline_opening_output()
        self.generate_calls = 0
        self.requests: list[ModelTransportRequest] = []

    def generate(self, request: ModelTransportRequest) -> ModelTransportResult:
        self.generate_calls += 1
        self.requests.append(request)
        self._assert_baseline_opening_request(request)
        return ModelTransportResult(
            json_document=json.dumps(
                self._output,
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            model="intake-model",
            latency_ms=4,
            token_usage={"input": 8, "output": 5, "total": 13},
        )

    async def agenerate(self, request: ModelTransportRequest) -> ModelTransportResult:
        return self.generate(request)

    def stream(self, request: ModelTransportRequest):
        yield ModelTransportCompleted(result=self.generate(request))

    async def astream(self, request: ModelTransportRequest):
        yield ModelTransportCompleted(result=await self.agenerate(request))

    def _assert_baseline_opening_request(self, request: ModelTransportRequest) -> None:
        assert request.node_name == BASELINE_INTAKE_NODE_NAME
        assert request.output_type is IntakeCaseDetailLlmOutput
        assert len(request.messages) == 2
        system_prompt = str(request.messages[0].content)
        human_prompt = str(request.messages[1].content)
        assert system_prompt
        assert self._agent_session_id in system_prompt
        assert self._agent_session_id not in human_prompt
        assert self._form_source in human_prompt
        assert self._form_description in human_prompt
        assert "initial_case_facts" in human_prompt


def _strict_baseline_opening_transport(
    execution: GatewayExecution,
    snapshot: dict[str, Any],
    *,
    output: dict[str, Any] | None = None,
) -> _StrictBaselineIntakeTransport:
    facts = snapshot["initial_case_facts"]
    return _StrictBaselineIntakeTransport(
        form_source=facts["form_source"],
        form_description=facts["form_description"],
        agent_session_id=execution.thread_record.identity.agent_session_id,
        output=output,
    )


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


def _target_candidate_intake_execution(command: RoomGraphCommand) -> GatewayExecution:
    """Build a complete candidate fence for target-only publication tests."""

    execution = _intake_execution(command)
    return replace(
        execution,
        fence=replace(
            execution.fence,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
            activation_id=f"p9act.v1.{'a' * 32}",
            room_fencing_token=1,
            command_hash="b" * 64,
            command_envelope_hash="c" * 64,
            execution_provider="synthetic",
            execution_model="intake-model",
            environment_id="target-intake-test",
            environment_generation=1,
            tenant_surrogate=command.tenant_surrogate,
            case_id=command.case_id,
            room_type="INTAKE",
            binding_hash="d" * 64,
            code_build_id="target-intake-build",
        ),
    )


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


@pytest.mark.asyncio
async def test_target_e2e_default_intake_uses_configured_structured_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    class FakeLiteLlmProxyClient:
        def __init__(
            self,
            base_url: str,
            model: str,
            api_key: str,
            timeout_seconds: float,
        ) -> None:
            captured["client_args"] = (base_url, model, api_key, timeout_seconds)
            self.governed_provider = "litellm"
            self.governed_model = model

        async def aopen(self) -> None:
            captured["client_opened"] = True

        async def aclose(self) -> None:
            captured["client_closed"] = True

    class FakeJavaIntakeExchangeClient:
        def __init__(
            self,
            *,
            java_api_service_url: str,
            java_service_secret: str,
        ) -> None:
            captured["exchange_args"] = (
                java_api_service_url,
                java_service_secret,
            )

        async def aopen(self) -> None:
            captured["exchange_opened"] = True

        async def aclose(self) -> None:
            captured["exchange_closed"] = True

    class DefaultProvidersCaptured(Exception):
        pass

    def capture_default_providers(
        kernel: GraphExecutorKernel,
        **kwargs: Any,
    ) -> tuple[Any, ...]:
        captured["kernel"] = kernel
        captured.update(kwargs)
        raise DefaultProvidersCaptured

    monkeypatch.setattr(
        production_bindings,
        "LiteLlmProxyClient",
        FakeLiteLlmProxyClient,
    )
    monkeypatch.setattr(
        production_bindings,
        "JavaIntakeExchangeClient",
        FakeJavaIntakeExchangeClient,
    )
    monkeypatch.setattr(
        production_bindings,
        "_build_target_e2e_room_providers",
        capture_default_providers,
    )
    settings = _target_settings()

    runtime = build_graph_runtime_bindings(
        settings,
        target_e2e_specialized_provider_factory=lambda _kernel: (),
    )
    kernel = GraphExecutorKernel(
        saver=cast(Any, InMemorySaver()),
        gateway=cast(Any, object()),
        durable_bulkhead=cast(Any, object()),
    )

    with pytest.raises(DefaultProvidersCaptured):
        runtime.executor_registry_factory(kernel)

    assert runtime.resource_opener is not None
    await runtime.resource_opener()

    assert captured["client_args"] == (
        settings.resolved_llm_base_url,
        settings.resolved_llm_model,
        settings.resolved_llm_api_key,
        settings.llm_timeout_seconds,
    )
    assert captured["exchange_args"] == (
        settings.java_api_service_url,
        settings.java_service_secret,
    )
    assert captured["kernel"] is kernel
    assert isinstance(captured["intake_transport"], StructuredClientTransport)
    assert captured["intake_provider"] == "litellm"
    assert captured["intake_model"] == "qwen3.7-plus-target"
    assert captured["client_opened"] is True
    assert captured["exchange_opened"] is True

    assert runtime.resource_closer is not None
    await runtime.resource_closer()
    assert captured["client_closed"] is True
    assert captured["exchange_closed"] is True


def test_target_e2e_composite_registers_the_exact_intake_provider_binding() -> None:
    class Provider:
        def __init__(self, room_type: RoomType) -> None:
            self.room_type = room_type

        async def stream(self, execution: GatewayExecution):
            if False:
                yield execution

    settings = _target_settings()
    registration = _target_e2e_executor_registration(
        settings.graph_target_e2e_bindings[0],
        GraphExecutorKernel(
            saver=cast(Any, InMemorySaver()),
            gateway=cast(Any, object()),
            durable_bulkhead=cast(Any, object()),
        ),
        providers=tuple(Provider(room_type) for room_type in RoomType),
        intake_provider="litellm",
        intake_model="qwen3.7-plus-target",
    )

    intake_binding = registration.provider_binding_for("INTAKE")
    assert intake_binding.provider == "litellm"
    assert intake_binding.model == "qwen3.7-plus-target"
    assert intake_binding.allowed_nodes == frozenset({BASELINE_INTAKE_NODE_NAME})
    assert registration.provider_binding_for("HEARING") is registration.provider_binding


def test_target_e2e_explicit_provider_factory_bypasses_live_model_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class UnexpectedLiveClient:
        def __init__(self, *_args: Any, **_kwargs: Any) -> None:
            raise AssertionError("explicit target provider injection must not build a live client")

    class ExplicitProviderFactoryUsed(Exception):
        pass

    def explicit_provider_factory(_kernel: GraphExecutorKernel) -> tuple[Any, ...]:
        raise ExplicitProviderFactoryUsed

    monkeypatch.setattr(
        production_bindings,
        "LiteLlmProxyClient",
        UnexpectedLiveClient,
    )
    runtime = build_graph_runtime_bindings(
        _target_settings(),
        target_e2e_provider_factory=explicit_provider_factory,
    )

    with pytest.raises(ExplicitProviderFactoryUsed):
        runtime.executor_registry_factory(
            GraphExecutorKernel(
                saver=cast(Any, InMemorySaver()),
                gateway=cast(Any, object()),
                durable_bulkhead=cast(Any, object()),
            )
        )


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
    assert registration.provider_binding.allowed_nodes == frozenset({BASELINE_INTAKE_NODE_NAME})


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("node_name", "expects_provider_record"),
    [
        (BASELINE_INTAKE_NODE_NAME, True),
        ("intake_lcel", False),
        ("unknown_node", False),
    ],
)
async def test_target_e2e_intake_registration_installs_provider_binding_in_stream_lifecycle(
    node_name: str,
    expects_provider_record: bool,
) -> None:
    provider_name = "litellm"
    model_name = "intake-model"

    class ProviderResponse(BaseModel):
        answer: str

    class Provider:
        def __init__(self, room_type: RoomType) -> None:
            self.room_type = room_type
            self.http_calls = 0
            self.invocations = 0

        async def stream(self, execution: GatewayExecution):
            if self.room_type is not RoomType.INTAKE:
                return
            self.invocations += 1
            yield cast(Any, SimpleNamespace(event_type="attempt_started"))

            async def handler(_request: httpx.Request) -> httpx.Response:
                self.http_calls += 1
                return httpx.Response(
                    200,
                    json={
                        "model": model_name,
                        "choices": [{"message": {"content": '{"answer":"ok"}'}}],
                        "usage": {
                            "prompt_tokens": 1,
                            "completion_tokens": 1,
                            "total_tokens": 2,
                        },
                    },
                )

            transport = httpx.MockTransport(handler)
            client = LiteLlmProxyClient(
                "http://litellm:4000",
                model_name,
                "test-key",
                transport=transport,
                async_transport=transport,
            )
            result = await client.agenerate(
                node_name=node_name,
                system_prompt="system",
                user_prompt="human",
                output_type=ProviderResponse,
                governed_request=GovernedProviderRequest(
                    provider=provider_name,
                    model=model_name,
                    temperature=0,
                    max_output_tokens=32,
                    response_format="STRICT_JSON_SCHEMA",
                    tool_allowlist=(),
                    deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
                    provider_attempts_remaining=1,
                    repairs_remaining=0,
                    traceparent=execution.admission.command.traceparent,
                ),
            )
            assert result.value.answer == "ok"
            yield cast(Any, SimpleNamespace(event_type="final"))

    settings = _target_settings()
    providers = tuple(Provider(room_type) for room_type in RoomType)
    intake_provider = next(
        provider for provider in providers if provider.room_type is RoomType.INTAKE
    )
    registration = _target_e2e_executor_registration(
        settings.graph_target_e2e_bindings[0],
        GraphExecutorKernel(
            saver=cast(Any, InMemorySaver()),
            gateway=cast(Any, object()),
            durable_bulkhead=cast(Any, object()),
        ),
        providers=providers,
        intake_provider=provider_name,
        intake_model=model_name,
    )
    intake_binding = registration.provider_binding_for("INTAKE")
    assert intake_binding.allowed_nodes == frozenset({BASELINE_INTAKE_NODE_NAME})

    source_command, _, _ = _intake_command()
    command = source_command.model_copy(
        update={
            "graph_key": registration.binding.graph_key,
            "graph_version": registration.binding.graph_version,
            "checkpoint_schema_version": registration.binding.checkpoint_schema_version,
        }
    )
    registry = RegistryRecord(
        binding=registration.binding,
        state=RegistryState.ACTIVE_CANDIDATE,
        loadable=True,
        revision=1,
    )
    admission = SimpleNamespace(
        action=AdmissionAction.ACQUIRE,
        command=command,
        registry=registry,
        binding=SimpleNamespace(execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE),
        candidate_authority=object(),
    )
    seeded_execution = _intake_execution(command)
    execution = replace(
        seeded_execution,
        admission=cast(Any, admission),
        attempt=cast(Any, SimpleNamespace(provider_call_count=0)),
        fence=replace(
            seeded_execution.fence,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
            activation_id=f"p9act.v1.{'a' * 32}",
            room_fencing_token=1,
            command_hash="c" * 64,
            command_envelope_hash="d" * 64,
            environment_id="target-e2e-local",
            environment_generation=1,
            tenant_surrogate=command.tenant_surrogate,
            case_id=command.case_id,
            room_type=command.room_type,
            binding_hash=registration.binding.binding_hash,
            code_build_id=registration.binding.code_build_id,
        ),
    )

    class Gateway:
        def __init__(self) -> None:
            self.executed: GatewayExecution | None = None
            self.finished = 0
            self.provider_call_counts: list[int] = []
            self.recorded: GatewayExecution | None = None

        async def admit(self, **_kwargs: Any):
            return admission

        async def inspect_recovery(self, _admission: Any) -> RecoveryDecision:
            return RecoveryDecision(
                action=RecoveryAction.RESUME_BEFORE_MODEL,
                invoke_model=True,
                emit_attempt_reset=False,
                reason_code="NO_MODEL_CALL_DURABLY_STARTED",
            )

        async def acquire_execution(self, _admission: Any, **_kwargs: Any) -> GatewayExecution:
            return execution

        async def execute_stream(
            self,
            *,
            execution: GatewayExecution,
            executor: Any,
            durable_terminal_signal: asyncio.Event | None = None,
            terminal_processing_started: asyncio.Event | None = None,
        ):
            self.executed = execution
            async for event in executor.stream(execution):
                if (
                    event.event_type in {"attempt_aborted", "error", "final"}
                ):
                    if terminal_processing_started is not None:
                        terminal_processing_started.set()
                    if durable_terminal_signal is not None:
                        durable_terminal_signal.set()
                yield event

        async def renew_execution(self, execution: GatewayExecution) -> Any:
            return execution.lease

        def cleanup_execution_lease(self, _execution: GatewayExecution) -> None:
            return None

        async def record_provider_call(self, current: GatewayExecution) -> GatewayExecution:
            self.provider_call_counts.append(current.attempt.provider_call_count)
            self.recorded = replace(
                current,
                attempt=SimpleNamespace(
                    provider_call_count=current.attempt.provider_call_count + 1
                ),
            )
            return self.recorded

        async def finish_execution_attempt(self, _execution: GatewayExecution, **_kwargs: Any):
            self.finished += 1
            return execution

    gateway = Gateway()
    gate = GraphStreamAdmissionGate()
    await gate.start()
    service = GatewayBackedGraphCommandStreamService(
        gateway=cast(Any, gateway),
        executors=ExactShadowExecutorRegistry((registration,)),
        owner_id="target-binding-test",
        admission_gate=gate,
    )
    assert execution.thread_record is not None
    stream = await service.open_stream(
        command=command,
        verified_invocation=cast(VerifiedInvocation, object()),
        expected_thread=execution.thread_record.identity,
    )

    if expects_provider_record:
        events = [event async for event in stream]
        assert [event.event_type for event in events] == ["attempt_started", "final"]
        assert gateway.provider_call_counts == [0]
        assert gateway.recorded is not None
        assert gateway.recorded.attempt.provider_call_count == 1
        assert gateway.executed is not None
        assert gateway.executed.fence.execution_provider == provider_name
        assert gateway.executed.fence.execution_model == model_name
        assert intake_provider.http_calls == 1
    else:
        with pytest.raises(GraphContractError, match="provider call intent conflicts"):
            _ = [event async for event in stream]
        assert gateway.provider_call_counts == []
        assert gateway.finished == 1
        assert intake_provider.http_calls == 0
    assert intake_provider.invocations == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_authorized_intake_adapter_builds_the_real_governed_graph_proposal() -> None:
    command, snapshot, payload = _intake_command()
    saver = InMemorySaver()
    execution = _intake_execution(command)
    transport = _strict_baseline_opening_transport(execution, snapshot)
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
        transport=cast(Any, transport),
        provider="synthetic",
        model="intake-model",
        checkpointer=saver,
    )
    assert bundle.graph.checkpointer is saver
    assert bundle.model_node.model.profile.profile_id == "intake-model.synthetic.v1"

    state = build_intake_execution_state(execution)
    assert state["cognitive_revision"] == 1
    result = await bundle.graph.ainvoke(
        state,
        {"configurable": {"thread_id": command.thread_id}},
        context=context,
    )
    proposal = bundle.terminal_proposal(result)
    artifact = canonical_intake_proposal(proposal)

    assert transport.generate_calls == 1
    assert len(transport.requests) == 1
    assert result["cognitive_revision"] == 1
    assert result["result_json"]["cognitive_revision"] == 1
    assert result["terminal_draft"] == result["result_json"]
    assert proposal.schema_version == "intake-turn-proposal.v2"
    assert proposal.command_id == command.command_id
    assert proposal.thread_id == command.thread_id
    assert proposal.actor_scope_hash == snapshot["actor_scope_hash"]
    assert proposal.source_snapshot_hash == snapshot["snapshot_hash"]
    assert proposal.source_event_hash is None
    assert proposal.room_utterance == "Please confirm the requested resolution."
    projected_dossier = proposal.dossier_patch.model_dump(mode="json", exclude_none=True)
    assert projected_dossier["case_story"]["one_sentence_summary"] == (
        "The imported case concerns the reported damaged order."
    )
    assert proposal.profile_versions.prompt_version == command.invocation_context.prompt_profile_id
    assert proposal.profile_versions.model_profile_id == "intake-model.synthetic.v1"
    assert proposal.profile_versions.tool_policy_version == "no-tools.v1"
    assert artifact.schema_version == "intake-turn-proposal.v2"
    assert artifact.sha256 == proposal.proposal_hash
    assert artifact.size_bytes == len(artifact.canonical_payload)
    assert b"memory_frame" not in artifact.canonical_payload
    assert b"formal_action" not in artifact.canonical_payload
    assert result["baseline_pending_case_detail"] is None

    def tamper_proposal_hash(candidate: dict[str, Any]) -> None:
        candidate["baseline_previous_case_detail"]["proposal_hash"] = "0" * 64

    def tamper_committed_identity(candidate: dict[str, Any]) -> None:
        candidate["baseline_previous_case_detail"]["committed_proposal_identity"][
            "attempt_id"
        ] = "ATTEMPT_TAMPERED"

    def tamper_normalized_matrix_patch(candidate: dict[str, Any]) -> None:
        candidate["baseline_previous_case_detail"]["normalized_matrix_patch"]["fact_rows"][
            0
        ]["asserted_value"] = "A tampered same-turn matrix assertion."

    for tamper in (
        tamper_proposal_hash,
        tamper_committed_identity,
        tamper_normalized_matrix_patch,
    ):
        tampered = deepcopy(result)
        tamper(tampered)
        with pytest.raises(IntakeGraphContractError, match="INTAKE_"):
            bundle.terminal_proposal(tampered)

    authority_injected = deepcopy(result)
    envelope = authority_injected["baseline_previous_case_detail"]
    invented_authority = deepcopy(envelope["formal_matrix"])
    envelope["authority_input_matrix"] = invented_authority
    envelope["authority_input_content_hash"] = invented_authority["content_hash"]
    envelope["authority_input_matrix_hash"] = canonical_sha256(invented_authority)
    envelope["envelope_hash"] = canonical_sha256_omitting(envelope, "envelope_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_PATCH_UNAUTHORIZED"):
        bundle.terminal_proposal(authority_injected)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "commit_fails",
    [False, True],
    ids=("success", "commit_failure"),
)
async def test_target_intake_executor_persists_historical_evidence_reference_to_final(
    monkeypatch: pytest.MonkeyPatch,
    commit_fails: bool,
) -> None:
    command, snapshot, payload = _intake_command()
    execution = _target_candidate_intake_execution(command)
    historical_fact = "商家稍后将上传官方链接以佐证标准编号123345"
    historical_missing_field = "official_document_link_123345"
    terminal_document = _strict_baseline_opening_output()
    terminal_document["case_detail"].update(
        {
            "case_story": {"one_sentence_summary": historical_fact},
            "missing_information": {
                "missing_facts": [historical_fact],
                "next_questions": [historical_fact],
            },
        }
    )
    terminal_document["missing_fields"] = [historical_missing_field]
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
    source_transport = _strict_baseline_opening_transport(
        execution,
        snapshot,
        output=terminal_document,
    )
    source_bundle = build_governed_intake_runtime(
        execution=execution,
        transport=cast(Any, source_transport),
        provider="synthetic",
        model="intake-model",
        checkpointer=InMemorySaver(),
    )
    durable_state = await source_bundle.graph.ainvoke(
        build_intake_execution_state(execution),
        {"configurable": {"thread_id": command.thread_id}},
        context=context,
    )
    assert source_transport.generate_calls == 1
    assert len(source_transport.requests) == 1
    assert durable_state["cognitive_revision"] == 1
    assert durable_state["result_json"]["cognitive_revision"] == 1
    durable_state["usage_by_invocation"][command.attempt_id] = {
        "input_tokens": 3,
        "output_tokens": 2,
        "total_tokens": 5,
    }
    proposal_before = dict(durable_state["result_json"])
    terminal_proposal = IntakeTurnProposal.model_validate(proposal_before)
    terminal_dossier = terminal_proposal.dossier_patch.model_dump(mode="json", exclude_none=True)
    assert terminal_dossier["case_story"]["one_sentence_summary"] == historical_fact
    assert historical_fact in terminal_dossier["missing_information"]["missing_facts"]
    assert historical_fact in terminal_dossier["missing_information"]["next_questions"]
    assert historical_missing_field in terminal_proposal.missing_fields
    terminal_room_utterance = terminal_proposal.room_utterance
    model_room_deltas = (
        terminal_room_utterance[:20],
        terminal_room_utterance[20:],
    )
    assert all(model_room_deltas)

    class Saver:
        def __init__(self, *, fail_commit: bool) -> None:
            self.preflights = 0
            self.commits = []
            self.fail_commit = fail_commit
            self.terminal_commit_succeeded = False

        async def avalidate_external_terminal_checkpoint(self, config, **kwargs):
            self.preflights += 1
            assert kwargs == {"cognitive_revision": 1}

        async def acommit_external_terminal(self, config, commit):
            self.commits.append(commit)
            canonical_dossier = json.loads(store.proposals[0].canonical_payload)["dossier_patch"]
            assert canonical_dossier["case_story"]["one_sentence_summary"] == historical_fact
            assert historical_fact in canonical_dossier["missing_information"]["missing_facts"]
            assert historical_fact in canonical_dossier["missing_information"]["next_questions"]
            if self.fail_commit:
                raise GraphTerminalBindingError("terminal commit failed")
            effective = replace(
                execution.fence,
                result_ref=commit.result.result_ref,
                result_hash=commit.result.result_hash,
                proposal_hash=commit.result.proposal_hash,
                result_envelope_hash=commit.result.result_envelope_hash,
            )
            configurable = dict(config["configurable"])
            configurable[FENCE_CONTEXT_KEY] = effective
            self.terminal_commit_succeeded = True
            return {"configurable": configurable}

    saver = Saver(fail_commit=commit_fails)
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
            assert kwargs["stream_mode"] == ["messages", "custom"]
            yield (
                "messages",
                (
                    AIMessageChunk(
                        content='{"room_utterance":"private raw completion"}',
                        additional_kwargs={"reasoning_content": "private reasoning"},
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
            for room_delta in model_room_deltas:
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
                                        "node_name": BASELINE_INTAKE_NODE_NAME,
                                        "field": "room_utterance",
                                        "delta": room_delta,
                                    }
                                ]
                            },
                        ),
                        {"langgraph_node": "intake_lcel"},
                    ),
                )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.dispute_core_state",
                                    "delta": (
                                        '{"blocker":"missing detail","current_status":"INITIATED",'
                                        '"fact_disputes":["legacy alias"]}'
                                    ),
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.case_story",
                                    "delta": json.dumps(
                                        {
                                            "one_sentence_summary": (
                                                "商家稍后将上传官方链接以佐证标准编号123345"
                                            )
                                        },
                                        ensure_ascii=False,
                                    ),
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.missing_information",
                                    "delta": json.dumps(
                                        {
                                            "missing_facts": [
                                                "商家稍后将上传官方链接以佐证标准编号123345"
                                            ],
                                            "next_questions": [
                                                "商家稍后将上传官方链接以佐证标准编号123345"
                                            ],
                                        },
                                        ensure_ascii=False,
                                    ),
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.case_story",
                                    "delta": '{"one_sentence_summary":"Case summary."}',
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.references",
                                    "delta": '{"order_reference":"ORDER-1"}',
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
            for title_delta in ("Order ", "delivery dispute"):
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
                                        "node_name": BASELINE_INTAKE_NODE_NAME,
                                        "field": "case_detail.case_story.title",
                                        "delta": title_delta,
                                    }
                                ]
                            },
                        ),
                        {"langgraph_node": "intake_lcel"},
                    ),
                )
            yield (
                "custom",
                GraphPublicUpdate.usage(Usage(input_tokens=3, output_tokens=2, total_tokens=5)),
            )

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
        lambda **kwargs: SimpleNamespace(graph=Graph()),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: terminal_proposal),
    )

    class Loader:
        async def load(self, selected_execution):
            return loaded

    class Store:
        def __init__(self) -> None:
            self.calls = 0
            self.proposals = []

        async def put(self, selected_execution, *, proposal, **kwargs):
            self.calls += 1
            assert kwargs == {
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-terminal",
                "cognitive_revision": 1,
            }
            self.proposals.append(proposal)
            canonical_dossier = json.loads(proposal.canonical_payload)["dossier_patch"]
            assert canonical_dossier["case_story"]["one_sentence_summary"] == historical_fact
            assert historical_fact in canonical_dossier["missing_information"]["missing_facts"]
            assert historical_fact in canonical_dossier["missing_information"]["next_questions"]
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

    events = []
    visible_commit_states = []

    async def collect() -> None:
        async for event in executor.stream(execution):
            events.append(event)
            if event.event_type == "visible_delta":
                visible_commit_states.append((event.payload.field, saver.terminal_commit_succeeded))

    if commit_fails:
        with pytest.raises(GraphTerminalBindingError, match="terminal commit failed"):
            await collect()
        assert [event.event_type for event in events] == [
            "attempt_started",
            *["visible_delta"] * 8,
        ]
        assert [event.payload.field for event in events[1:3]] == [
            "room_utterance",
            "room_utterance",
        ]
        assert [event.payload.delta for event in events[1:3]] == list(model_room_deltas)
        assert events[3].payload.field == "case_detail.case_story"
        assert json.loads(events[3].payload.delta or "")["one_sentence_summary"] == (
            "商家稍后将上传官方链接以佐证标准编号123345"
        )
        assert events[4].payload.field == "case_detail.missing_information"
        assert "商家稍后将上传官方链接以佐证标准编号123345" in json.loads(
            events[4].payload.delta or ""
        )["missing_facts"]
        assert events[5].payload.field == "case_detail.case_story"
        assert events[6].payload.field == "case_detail.references"
        assert [event.payload.delta for event in events[7:9]] == [
            "Order ",
            "delivery dispute",
        ]
        assert visible_commit_states == [
            ("room_utterance", False),
            ("room_utterance", False),
            ("case_detail.case_story", False),
            ("case_detail.missing_information", False),
            ("case_detail.case_story", False),
            ("case_detail.references", False),
            ("case_detail.case_story.title", False),
            ("case_detail.case_story.title", False),
        ]
        assert saver.preflights == 1
        assert store.calls == 1
        assert json.loads(store.proposals[0].canonical_payload)["room_utterance"] == terminal_room_utterance
        assert len(saver.commits) == 1
        assert not saver.terminal_commit_succeeded
        return

    await collect()
    assert [event.event_type for event in events] == [
        "attempt_started",
        *["visible_delta"] * 8,
        "usage",
        "final",
    ]
    assert [event.payload.field for event in events[1:3]] == [
        "room_utterance",
        "room_utterance",
    ]
    assert [event.payload.delta for event in events[1:3]] == list(model_room_deltas)
    assert "".join(event.payload.delta or "" for event in events[1:3]) == terminal_room_utterance
    assert events[3].payload.field == "case_detail.case_story"
    assert json.loads(events[3].payload.delta or "")["one_sentence_summary"] == (
        "商家稍后将上传官方链接以佐证标准编号123345"
    )
    assert events[4].payload.field == "case_detail.missing_information"
    assert "商家稍后将上传官方链接以佐证标准编号123345" in json.loads(
        events[4].payload.delta or ""
    )["missing_facts"]
    assert events[5].payload.field == "case_detail.case_story"
    assert events[5].payload.delta == '{"one_sentence_summary":"Case summary."}'
    assert events[6].payload.field == "case_detail.references"
    assert events[6].payload.delta == '{"order_reference":"ORDER-1"}'
    assert [event.payload.delta for event in events[7:9]] == [
        "Order ",
        "delivery dispute",
    ]
    assert events[9].payload.usage == Usage(input_tokens=3, output_tokens=2, total_tokens=5)
    assert visible_commit_states == [
        ("room_utterance", False),
        ("room_utterance", False),
        ("case_detail.case_story", False),
        ("case_detail.missing_information", False),
        ("case_detail.case_story", False),
        ("case_detail.references", False),
        ("case_detail.case_story.title", False),
        ("case_detail.case_story.title", False),
    ]
    assert saver.preflights == 1
    assert store.calls == 1
    canonical_document = json.loads(store.proposals[0].canonical_payload)
    assert canonical_document["room_utterance"] == terminal_room_utterance
    assert canonical_document["dossier_patch"]["case_story"]["one_sentence_summary"] == historical_fact
    assert historical_fact in canonical_document["dossier_patch"]["missing_information"][
        "missing_facts"
    ]
    assert historical_fact in canonical_document["dossier_patch"]["missing_information"][
        "next_questions"
    ]
    assert len(saver.commits) == 1
    assert saver.terminal_commit_succeeded
    assert events[-1].event_type == "final"
    result_json = saver.commits[0].result.result_json
    assert len(result_json["artifact_operations"]) == 1
    assert result_json["artifact_operations"][0]["operation"] == "PROPOSE_PATCH"
    assert durable_state["terminal_draft"] == proposal_before
    assert durable_state["result_json"] == proposal_before


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "resume_checkpoint_id",
    [None, "cp-intake-previous"],
    ids=("fresh", "continuation"),
)
async def test_compiled_intake_executor_reads_latest_terminal_checkpoint_without_mutating_stream_config(
    monkeypatch: pytest.MonkeyPatch,
    resume_checkpoint_id: str | None,
) -> None:
    command, _, _ = _intake_command()
    execution = _intake_execution(command)
    if resume_checkpoint_id is not None:
        execution = replace(
            execution,
            thread_record=replace(
                execution.thread_record,
                last_checkpoint_ns="",
                last_checkpoint_id=resume_checkpoint_id,
            ),
        )

    current_terminal_config = bind_fence_context(
        {
            "configurable": {
                "thread_id": command.thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-current-terminal",
            }
        },
        execution.fence,
    )
    current_terminal_snapshot = object()
    stale_snapshot = object()
    terminal_state = {
        "terminal_draft": {"checkpoint": "current"},
        "result_json": {"checkpoint": "current"},
        "cognitive_revision": 3,
    }
    proposal = SimpleNamespace(room_utterance="unused")
    canonical = SimpleNamespace(
        artifact_id="intake-proposal-current-terminal",
        schema_version="intake-turn-proposal.v2",
        sha256="a" * 64,
        size_bytes=1,
    )
    result = SimpleNamespace(
        result_ref="urn:intake:latest-terminal:result",
        result_hash="b" * 64,
        proposal_hash="c" * 64,
        result_envelope_hash="d" * 64,
    )

    class Saver:
        async def avalidate_external_terminal_checkpoint(self, config, **kwargs) -> None:
            assert config is current_terminal_config
            assert kwargs == {"cognitive_revision": 3}

        async def acommit_external_terminal(self, config, commit):
            assert config is current_terminal_config
            assert commit.result is result
            configurable = dict(config["configurable"])
            configurable[FENCE_CONTEXT_KEY] = replace(
                execution.fence,
                result_ref=result.result_ref,
                result_hash=result.result_hash,
                proposal_hash=result.proposal_hash,
                result_envelope_hash=result.result_envelope_hash,
            )
            return {"configurable": configurable}

    saver = Saver()

    class Graph:
        checkpointer = saver

        def __init__(self) -> None:
            self.stream_config: dict[str, Any] | None = None
            self.state_configs: list[dict[str, Any]] = []

        async def astream(self, input, config, **kwargs):
            assert kwargs["stream_mode"] == ["messages", "custom"]
            self.stream_config = config
            configurable = config["configurable"]
            assert configurable["thread_id"] == execution.fence.thread_id
            assert configurable["checkpoint_ns"] == ""
            assert configurable[FENCE_CONTEXT_KEY] is execution.fence
            if resume_checkpoint_id is None:
                assert "checkpoint_id" not in configurable
            else:
                assert configurable["checkpoint_id"] == resume_checkpoint_id
            if False:
                yield None

        async def aget_state(self, config):
            self.state_configs.append(config)
            if "checkpoint_id" in config["configurable"]:
                return stale_snapshot
            return current_terminal_snapshot

    graph = Graph()

    class Store:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        async def put(self, selected_execution, *, proposal, **kwargs):
            assert selected_execution is execution
            assert proposal is canonical
            self.calls.append(kwargs)
            return StoredIntakeProposal(
                artifact_id=canonical.artifact_id,
                schema_version=canonical.schema_version,
                uri="s3://intake-proposals/current-terminal.json",
                object_version="version-current",
                sha256=canonical.sha256,
                size_bytes=canonical.size_bytes,
            )

    class Materializer:
        def materialize(self, checkpoint_ns, checkpoint_id, *, fence):
            assert checkpoint_ns == ""
            assert checkpoint_id == "cp-intake-current-terminal"
            assert fence is execution.fence
            return result

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    def snapshot(snapshot: object, selected_execution: GatewayExecution):
        assert selected_execution is execution
        assert snapshot is current_terminal_snapshot
        return terminal_state, current_terminal_config

    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=graph),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_snapshot", staticmethod(snapshot))
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_room_utterance_updates",
        staticmethod(lambda _: ()),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_terminal_normalized_dossier_updates",
        staticmethod(lambda _: ()),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: Usage(input_tokens=0, output_tokens=0, total_tokens=0)),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: canonical,
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_materializer",
        staticmethod(lambda *_, **__: Materializer()),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.ExternalTerminalCommit",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class UnusedLoader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("patched context loader should be the only loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=UnusedLoader(),
        proposal_store=store,
    )

    events = [event async for event in executor.stream(execution)]

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert graph.stream_config is not None
    assert len(graph.state_configs) == 1
    latest_config = graph.state_configs[0]
    assert latest_config is not graph.stream_config
    assert latest_config["configurable"] is not graph.stream_config["configurable"]
    assert latest_config["configurable"]["thread_id"] == execution.fence.thread_id
    assert latest_config["configurable"]["checkpoint_ns"] == ""
    assert latest_config["configurable"][FENCE_CONTEXT_KEY] is execution.fence
    assert "checkpoint_id" not in latest_config["configurable"]
    if resume_checkpoint_id is not None:
        assert graph.stream_config["configurable"]["checkpoint_id"] == resume_checkpoint_id
    assert store.calls == [
        {
            "checkpoint_ns": "",
            "checkpoint_id": "cp-intake-current-terminal",
            "cognitive_revision": 3,
        }
    ]


@pytest.mark.parametrize(
    ("length", "expected_chunk_lengths"),
    [(4096, (4096,)), (4097, (4096, 1))],
)
def test_compiled_intake_executor_chunks_guarded_room_utterance_at_stream_limit(
    length: int,
    expected_chunk_lengths: tuple[int, ...],
) -> None:
    room_utterance = "\U0001F642" * length

    updates = CompiledIntakeGraphShadowExecutor._room_utterance_updates(room_utterance)

    assert tuple(len(update.payload.delta or "") for update in updates) == expected_chunk_lengths
    assert "".join(update.payload.delta or "" for update in updates) == room_utterance
    assert all((update.payload.delta or "") for update in updates)


@pytest.mark.parametrize(
    ("length", "expected_chunk_lengths"),
    [(4096, (4096,)), (4097, (4096, 1))],
)
def test_compiled_intake_executor_chunks_streamed_room_utterance_at_stream_limit(
    length: int,
    expected_chunk_lengths: tuple[int, ...],
) -> None:
    room_utterance = "🙂" * length

    updates = CompiledIntakeGraphShadowExecutor._streamed_room_utterance_updates(
        node=BASELINE_INTAKE_NODE_NAME,
        delta=room_utterance,
    )

    assert tuple(len(update.payload.delta or "") for update in updates) == expected_chunk_lengths
    assert "".join(update.payload.delta or "" for update in updates) == room_utterance
    assert all(update.payload.node == BASELINE_INTAKE_NODE_NAME for update in updates)
    assert all(update.payload.field == "room_utterance" for update in updates)


@pytest.mark.parametrize(
    ("length", "expected_chunk_lengths"),
    [(4096, (4096,)), (4097, (4096, 1))],
)
def test_compiled_intake_executor_chunks_unicode_string_prefix_leaf_at_stream_limit(
    length: int,
    expected_chunk_lengths: tuple[int, ...],
) -> None:
    delta = "\U0001F642" * length

    updates = CompiledIntakeGraphShadowExecutor._string_prefix_dossier_updates(
        node=BASELINE_INTAKE_NODE_NAME,
        field="case_detail.case_story.title",
        delta=delta,
    )

    assert tuple(len(update.payload.delta or "") for update in updates) == expected_chunk_lengths
    assert "".join(update.payload.delta or "" for update in updates) == delta
    assert all((update.payload.delta or "") for update in updates)


@pytest.mark.asyncio
async def test_compiled_intake_executor_suppresses_provisional_dossier_without_model_room_and_finalizes_after_room_chunks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _intake_execution(command)
    room_utterance = "\U0001F642" * 4097
    oversized_leaf_delta = "\U0001F642" * 4097
    oversized_case_story = {"one_sentence_summary": "x" * 4097}
    oversized_delta = json.dumps(oversized_case_story, separators=(",", ":"))
    assert len(oversized_delta) > 4096

    class DossierPatch:
        def model_dump(self, **_: Any) -> dict[str, Any]:
            return {
                "case_story": oversized_case_story,
                "references": {"order_reference": "ORDER-TERMINAL"},
            }

    proposal = SimpleNamespace(
        room_utterance=room_utterance,
        dossier_patch=DossierPatch(),
    )
    state = {
        "terminal_draft": {"same": True},
        "result_json": {"same": True},
        "cognitive_revision": 1,
    }
    result = SimpleNamespace(
        result_ref="urn:intake:delta-bound:result",
        result_hash="a" * 64,
        proposal_hash="b" * 64,
        result_envelope_hash="c" * 64,
    )
    canonical = SimpleNamespace(
        artifact_id="intake-proposal-delta-bound",
        schema_version="intake-turn-proposal.v2",
        sha256="d" * 64,
        size_bytes=1,
    )
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

    class Saver:
        def __init__(self) -> None:
            self.terminal_committed = False

        async def avalidate_external_terminal_checkpoint(self, config, **kwargs) -> None:
            assert config is final_config
            assert kwargs["cognitive_revision"] == 1

        async def acommit_external_terminal(self, config, commit):
            assert config is final_config
            assert commit.result is result
            self.terminal_committed = True
            configurable = dict(config["configurable"])
            configurable[FENCE_CONTEXT_KEY] = replace(
                execution.fence,
                result_ref=result.result_ref,
                result_hash=result.result_hash,
                proposal_hash=result.proposal_hash,
                result_envelope_hash=result.result_envelope_hash,
            )
            return {"configurable": configurable}

    saver = Saver()

    class Graph:
        checkpointer = saver

        async def astream(self, input, config, **kwargs):
            assert kwargs["stream_mode"] == ["messages", "custom"]
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.case_story",
                                    "delta": oversized_delta,
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.case_story.title",
                                    "delta": oversized_leaf_delta,
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                ),
            )

        async def aget_state(self, config):
            return object()

    class Store:
        def __init__(self) -> None:
            self.calls = 0

        async def put(self, selected_execution, *, proposal, **kwargs):
            assert selected_execution is execution
            assert proposal is canonical
            self.calls += 1
            return StoredIntakeProposal(
                artifact_id=canonical.artifact_id,
                schema_version=canonical.schema_version,
                uri="s3://intake-proposals/delta-bound.json",
                object_version="version-1",
                sha256=canonical.sha256,
                size_bytes=canonical.size_bytes,
            )

    class Materializer:
        def materialize(self, checkpoint_ns, checkpoint_id, *, fence):
            assert checkpoint_ns == ""
            assert checkpoint_id == "cp-intake-terminal"
            assert fence is execution.fence
            return result

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=Graph()),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(lambda *_: (state, final_config)),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: Usage(input_tokens=0, output_tokens=0, total_tokens=0)),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: canonical,
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_materializer",
        staticmethod(lambda *_, **__: Materializer()),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.ExternalTerminalCommit",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class UnusedLoader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("patched context loader should be the only loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=UnusedLoader(),
        proposal_store=store,
    )

    events = []
    visible_commit_states = []
    async for event in executor.stream(execution):
        events.append(event)
        if event.event_type == "visible_delta":
            visible_commit_states.append((event.payload.field, saver.terminal_committed))
    room_events = [
        event
        for event in events
        if event.event_type == "visible_delta" and event.payload.field == "room_utterance"
    ]
    terminal_references_event = next(
        event
        for event in events
        if event.event_type == "visible_delta"
        and event.payload.field == "case_detail.references"
    )

    assert [event.sequence_no for event in events] == list(range(len(events)))
    assert [
        event.payload.field for event in events if event.event_type == "visible_delta"
    ] == [
        "room_utterance",
        "room_utterance",
        "case_detail.references",
    ]
    assert [len(event.payload.delta or "") for event in room_events] == [4096, 1]
    assert "".join(event.payload.delta or "" for event in room_events) == room_utterance
    assert all(len(event.payload.delta or "") <= 4096 for event in room_events)
    assert not any(
        event.event_type == "visible_delta"
        and event.payload.field.startswith("case_detail.case_story")
        for event in events
    )
    assert json.loads(terminal_references_event.payload.delta or "") == {
        "order_reference": "ORDER-TERMINAL"
    }
    assert visible_commit_states == [
        ("room_utterance", False),
        ("room_utterance", False),
        ("case_detail.references", True),
    ]
    assert events[-1].event_type == "final"
    assert events[-2] is terminal_references_event
    assert saver.terminal_committed
    assert store.calls == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("failure_stage", "replay_suffix"),
    [
        (None, ""),
        (None, "\n另请保留“引号”和反斜杠\\的原样文本。"),
        ("storage", ""),
        ("commit", ""),
    ],
    ids=(
        "success_normal_unicode",
        "success_json_escaped_unicode",
        "storage_failure",
        "commit_failure",
    ),
)
async def test_target_intake_executor_streams_room_equal_to_terminal_before_terminal_commit(
    monkeypatch: pytest.MonkeyPatch,
    failure_stage: str | None,
    replay_suffix: str,
) -> None:
    command, _, _ = _intake_command()
    execution = _target_candidate_intake_execution(command)
    first_two_questions = (
        "请确认订单号？商品故障对您的使用造成了什么影响？"
    )
    terminal_room_utterance = (
        "您好🙂，我已记录您补充的订单与使用情况。"
        "为准确整理争议，请核对以下信息，并说明下列两点："
        + replay_suffix
        + first_two_questions
    )
    streamed_room_utterance = terminal_room_utterance
    assert streamed_room_utterance == terminal_room_utterance
    assert streamed_room_utterance.count("？") == 2
    preview_split_at = len(streamed_room_utterance) // 2
    preview_room_deltas = (
        streamed_room_utterance[:preview_split_at],
        streamed_room_utterance[preview_split_at:],
    )
    assert all(preview_room_deltas)
    oversized_title = "终态长标题🙂" * 1025
    dossier_payload = {
        "case_story": {
            "title": oversized_title,
            "one_sentence_summary": "这是基线终结器确认后的争议摘要。",
        },
        "references": {
            "order_reference": "ORDER-TERMINAL-UNICODE-001",
            "after_sales_reference": "AFTER-TERMINAL-001",
        },
    }
    assert len(
        json.dumps(
            dossier_payload["case_story"],
            ensure_ascii=False,
            separators=(",", ":"),
        )
    ) > 4096

    class DossierPatch:
        def model_dump(self, **_: Any) -> dict[str, Any]:
            return deepcopy(dossier_payload)

    state = {
        "terminal_draft": {"same": True},
        "result_json": {"same": True},
        "cognitive_revision": 1,
    }
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
    proposal = SimpleNamespace(
        room_utterance=terminal_room_utterance,
        dossier_patch=DossierPatch(),
    )
    canonical = SimpleNamespace(
        artifact_id="intake-proposal-terminal",
        schema_version="intake-turn-proposal.v2",
        sha256="a" * 64,
        size_bytes=1,
    )
    result = SimpleNamespace(
        result_ref="urn:intake:canonical-reply:result",
        result_hash="b" * 64,
        proposal_hash="c" * 64,
        result_envelope_hash="d" * 64,
    )
    raw_dossier_delta = '{"order_reference":"RAW-UNCOMMITTED"}'
    usage = Usage(input_tokens=3, output_tokens=2, total_tokens=5)

    class Saver:
        def __init__(self) -> None:
            self.preflights = 0
            self.commits = 0
            self.terminal_committed = False

        async def avalidate_external_terminal_checkpoint(self, *args: Any, **kwargs: Any) -> None:
            self.preflights += 1
            assert args == (final_config,)
            assert kwargs == {"cognitive_revision": 1}

        async def acommit_external_terminal(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
            self.commits += 1
            assert args[0] is final_config
            if failure_stage == "commit":
                raise GraphTerminalBindingError("terminal commit failed")
            self.terminal_committed = True
            return {
                "configurable": {
                    FENCE_CONTEXT_KEY: replace(
                        execution.fence,
                        result_ref=result.result_ref,
                        result_hash=result.result_hash,
                        proposal_hash=result.proposal_hash,
                        result_envelope_hash=result.result_envelope_hash,
                    )
                }
            }

    saver = Saver()

    class Graph:
        checkpointer = saver
        source_completed = False

        async def astream(self, input: Any, config: Any, **kwargs: Any):
            assert kwargs["stream_mode"] == ["messages", "custom"]
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "room_utterance",
                                    "delta": preview_room_deltas[0],
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "room_utterance",
                                    "delta": preview_room_deltas[1],
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.references",
                                    "delta": raw_dossier_delta,
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                ),
            )
            yield ("custom", GraphPublicUpdate.usage(usage))
            self.source_completed = True

        async def aget_state(self, config: Any) -> object:
            return object()

    class Store:
        def __init__(self) -> None:
            self.calls = 0

        async def put(
            self,
            selected_execution: GatewayExecution,
            *,
            proposal: Any,
            **kwargs: Any,
        ) -> Any:
            self.calls += 1
            assert selected_execution is execution
            assert proposal is canonical
            assert kwargs == {
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-terminal",
                "cognitive_revision": 1,
            }
            if failure_stage == "storage":
                raise GraphTerminalBindingError("terminal storage failed")
            return SimpleNamespace(
                artifact_id=canonical.artifact_id,
                schema_version=canonical.schema_version,
                uri="s3://intake-proposals/canonical-reply.json",
                sha256=canonical.sha256,
                size_bytes=canonical.size_bytes,
            )

    class Materializer:
        def materialize(self, checkpoint_ns: str, checkpoint_id: str, *, fence: Any) -> Any:
            assert checkpoint_ns == ""
            assert checkpoint_id == "cp-intake-terminal"
            assert fence is execution.fence
            return result

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    graph = Graph()
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=graph),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_input",
        staticmethod(lambda _: {}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_config",
        staticmethod(lambda _: {"configurable": {}}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(lambda *_: (state, final_config)),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: usage),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: canonical,
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_materializer",
        staticmethod(lambda *_, **__: Materializer()),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_target_proposal_source",
        staticmethod(lambda *_: None),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.ExternalTerminalCommit",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class Loader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the test harness patches the Intake context loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=store,
    )

    events = []
    visible_commit_states = []
    visible_source_completion_states = []

    async def collect() -> None:
        async for event in executor.stream(execution):
            events.append(event)
            if event.event_type == "visible_delta":
                visible_commit_states.append(saver.terminal_committed)
                visible_source_completion_states.append(graph.source_completed)

    if failure_stage is not None:
        with pytest.raises(GraphTerminalBindingError, match=f"terminal {failure_stage} failed"):
            await collect()
        assert [event.event_type for event in events] == [
            "attempt_started",
            "visible_delta",
            "visible_delta",
            "visible_delta",
        ]
        assert [
            (event.payload.field, event.payload.delta)
            for event in events
            if event.event_type == "visible_delta"
        ] == [
            ("room_utterance", preview_room_deltas[0]),
            ("room_utterance", preview_room_deltas[1]),
            ("case_detail.references", raw_dossier_delta),
        ]
        assert visible_commit_states == [False, False, False]
        # The third visible frame is a provisional board update.  It must reach
        # the client before the graph source completes; the later failure is
        # still fail-closed and lets the client discard this provisional view.
        assert visible_source_completion_states == [False, False, False]
        assert saver.preflights == 1
        assert store.calls == 1
        assert saver.commits == (1 if failure_stage == "commit" else 0)
        assert not saver.terminal_committed
        return

    await collect()
    visible = [event for event in events if event.event_type == "visible_delta"]
    room_events = [event for event in visible if event.payload.field == "room_utterance"]
    board_events = [
        event
        for event in visible
        if (event.payload.field or "").startswith("case_detail.")
    ]
    title_leaf_events = [
        event
        for event in board_events
        if event.payload.field == "case_detail.case_story.title"
    ]
    references_leaf_events = [
        event
        for event in board_events
        if event.payload.field == "case_detail.references.order_reference"
    ]
    references_root_events = [
        event
        for event in board_events
        if event.payload.field == "case_detail.references"
    ]

    assert [event.sequence_no for event in events] == list(range(len(events)))
    assert [event.payload.delta for event in room_events] == list(preview_room_deltas)
    assert len(room_events) == 2
    assert "".join(
        event.payload.delta or "" for event in room_events
    ) == terminal_room_utterance
    assert visible_commit_states == [False, False, False]
    # The board is emitted while the source is still open, after both room
    # chunks.  The terminal commit remains the durable authority.
    assert visible_source_completion_states == [False, False, False]
    assert max(event.sequence_no for event in room_events) < min(
        event.sequence_no for event in board_events
    )
    assert [(event.payload.field, event.payload.delta) for event in board_events] == [
        ("case_detail.references", raw_dossier_delta)
    ]
    assert title_leaf_events == []
    assert references_leaf_events == []
    assert references_root_events == [board_events[0]]
    all_visible_text = "".join(event.payload.delta or "" for event in visible)
    assert terminal_room_utterance in all_visible_text
    assert "RAW-UNCOMMITTED" in all_visible_text
    assert dossier_payload["references"]["order_reference"] not in all_visible_text
    assert events[-2].event_type == "usage"
    assert events[-2].payload.usage == usage
    assert events[-1].event_type == "final"
    assert saver.preflights == 1
    assert store.calls == 1
    assert saver.commits == 1
    assert saver.terminal_committed


@pytest.mark.asyncio
async def test_target_intake_executor_uses_canonical_reply_then_board_when_no_preview(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _target_candidate_intake_execution(command)
    terminal_room_utterance = "Committed baseline reply."
    raw_board_delta = '{"order_reference":"RAW-BEFORE-ROOM"}'
    state = {
        "terminal_draft": {"same": True},
        "result_json": {"same": True},
        "cognitive_revision": 1,
    }
    final_config = bind_fence_context(
        {
            "configurable": {
                "thread_id": command.thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-no-preview",
            }
        },
        execution.fence,
    )

    class DossierPatch:
        def model_dump(self, **_: Any) -> dict[str, Any]:
            return {"references": {"order_reference": "ORDER-TERMINAL-NO-PREVIEW"}}

    proposal = SimpleNamespace(
        room_utterance=terminal_room_utterance,
        dossier_patch=DossierPatch(),
    )
    canonical = SimpleNamespace(
        artifact_id="intake-proposal-no-preview",
        schema_version="intake-turn-proposal.v2",
        sha256="a" * 64,
        size_bytes=1,
    )
    result = SimpleNamespace(
        result_ref="urn:intake:no-preview:result",
        result_hash="b" * 64,
        proposal_hash="c" * 64,
        result_envelope_hash="d" * 64,
    )

    class Saver:
        def __init__(self) -> None:
            self.terminal_committed = False

        async def avalidate_external_terminal_checkpoint(self, config: Any, **kwargs: Any) -> None:
            assert config is final_config
            assert kwargs == {"cognitive_revision": 1}

        async def acommit_external_terminal(self, config: Any, commit: Any) -> dict[str, Any]:
            assert config is final_config
            assert commit.result is result
            self.terminal_committed = True
            return {
                "configurable": {
                    FENCE_CONTEXT_KEY: replace(
                        execution.fence,
                        result_ref=result.result_ref,
                        result_hash=result.result_hash,
                        proposal_hash=result.proposal_hash,
                        result_envelope_hash=result.result_envelope_hash,
                    )
                }
            }

    saver = Saver()

    class Graph:
        checkpointer = saver

        async def astream(self, input: Any, config: Any, **kwargs: Any):
            assert kwargs["stream_mode"] == ["messages", "custom"]
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.references",
                                    "delta": raw_board_delta,
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                ),
            )

        async def aget_state(self, config: Any) -> object:
            return object()

    class Store:
        async def put(
            self,
            selected_execution: GatewayExecution,
            *,
            proposal: Any,
            **kwargs: Any,
        ) -> Any:
            assert selected_execution is execution
            assert proposal is canonical
            assert kwargs == {
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-no-preview",
                "cognitive_revision": 1,
            }
            return SimpleNamespace(
                artifact_id=canonical.artifact_id,
                schema_version=canonical.schema_version,
                uri="s3://intake-proposals/no-preview.json",
                sha256=canonical.sha256,
                size_bytes=canonical.size_bytes,
            )

    class Materializer:
        def materialize(self, checkpoint_ns: str, checkpoint_id: str, *, fence: Any) -> Any:
            assert checkpoint_ns == ""
            assert checkpoint_id == "cp-intake-no-preview"
            assert fence is execution.fence
            return result

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    graph = Graph()
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=graph),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_input",
        staticmethod(lambda _: {}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_config",
        staticmethod(lambda _: {"configurable": {}}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(lambda *_: (state, final_config)),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: Usage(input_tokens=0, output_tokens=0, total_tokens=0)),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: canonical,
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_materializer",
        staticmethod(lambda *_, **__: Materializer()),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_target_proposal_source",
        staticmethod(lambda *_: None),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.ExternalTerminalCommit",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class Loader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the test harness patches the Intake context loader")

    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=Store(),
    )

    events = []
    visible_commit_states = []
    async for event in executor.stream(execution):
        events.append(event)
        if event.event_type == "visible_delta":
            visible_commit_states.append(saver.terminal_committed)

    visible = [event for event in events if event.event_type == "visible_delta"]
    room_events = [event for event in visible if event.payload.field == "room_utterance"]
    board_events = [event for event in visible if event not in room_events]

    assert "".join(event.payload.delta or "" for event in room_events) == terminal_room_utterance
    assert all(visible_commit_states)
    assert max(event.sequence_no for event in room_events) < min(
        event.sequence_no for event in board_events
    )
    assert raw_board_delta not in "".join(event.payload.delta or "" for event in visible)
    assert any(event.payload.field == "case_detail.references" for event in board_events)
    assert events[-1].event_type == "final"
    assert saver.terminal_committed


@pytest.mark.asyncio
async def test_target_intake_preview_does_not_turn_graph_failure_into_formal_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _target_candidate_intake_execution(command)
    saver = object()
    preview = "Provisional response before the provider failure."

    class Graph:
        checkpointer = saver

        def __init__(self) -> None:
            self.closed = False

        async def astream(self, input: Any, config: Any, **kwargs: Any):
            try:
                assert kwargs["stream_mode"] == ["messages", "custom"]
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
                                        "node_name": BASELINE_INTAKE_NODE_NAME,
                                        "field": "room_utterance",
                                        "delta": preview,
                                    }
                                ]
                            },
                        ),
                        {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                    ),
                )
                raise RuntimeError("provider stream failed after preview")
            finally:
                self.closed = True

    class Store:
        def __init__(self) -> None:
            self.calls = 0

        async def put(self, *args: Any, **kwargs: Any) -> Any:
            self.calls += 1
            raise AssertionError("a failed graph must not store a formal proposal")

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    graph = Graph()
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=graph),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_input",
        staticmethod(lambda _: {}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_config",
        staticmethod(lambda _: {"configurable": {}}),
    )

    class Loader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the test harness patches the Intake context loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=store,
    )

    events = []
    with pytest.raises(RuntimeError, match="provider stream failed after preview"):
        async for event in executor.stream(execution):
            events.append(event)

    assert [event.event_type for event in events] == ["attempt_started", "visible_delta"]
    assert events[-1].payload.field == "room_utterance"
    assert events[-1].payload.delta == preview
    assert graph.closed is True
    assert store.calls == 0


@pytest.mark.asyncio
async def test_target_intake_executor_streams_governed_ready_handoff_equal_to_terminal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _target_candidate_intake_execution(command)
    baseline_ready_handoff = (
        "已记录本轮补充，当前信息已经可以提交。"
        "请问还有没有需要备注给证据书记官或后续审理环节的案情内容？"
    )
    assert (
        intake_executor._normalized_intake_room_utterance(baseline_ready_handoff)
        == baseline_ready_handoff
    )
    governed_room_utterance = baseline_ready_handoff
    raw_dossier_delta = '{"order_reference":"RAW-UNCOMMITTED"}'
    state = {
        "terminal_draft": {"same": True},
        "result_json": {"same": True},
        "cognitive_revision": 1,
    }
    final_config = bind_fence_context(
        {
            "configurable": {
                "thread_id": command.thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-ready-handoff",
            }
        },
        execution.fence,
    )

    class DossierPatch:
        def model_dump(self, **_: Any) -> dict[str, Any]:
            return {"references": {"order_reference": "ORDER-READY-HANDOFF"}}

    proposal = SimpleNamespace(
        room_utterance=governed_room_utterance,
        dossier_patch=DossierPatch(),
    )
    canonical = SimpleNamespace(
        artifact_id="intake-proposal-ready-handoff",
        schema_version="intake-turn-proposal.v2",
        sha256="a" * 64,
        size_bytes=1,
    )
    result = SimpleNamespace(
        result_ref="urn:intake:ready-handoff:result",
        result_hash="b" * 64,
        proposal_hash="c" * 64,
        result_envelope_hash="d" * 64,
    )

    class Saver:
        def __init__(self) -> None:
            self.preflights = 0
            self.commits = 0
            self.terminal_committed = False

        async def avalidate_external_terminal_checkpoint(self, config: Any, **kwargs: Any) -> None:
            self.preflights += 1
            assert config is final_config
            assert kwargs == {"cognitive_revision": 1}

        async def acommit_external_terminal(self, config: Any, commit: Any) -> dict[str, Any]:
            self.commits += 1
            assert config is final_config
            assert store.stored
            assert commit.result is result
            self.terminal_committed = True
            return {
                "configurable": {
                    FENCE_CONTEXT_KEY: replace(
                        execution.fence,
                        result_ref=result.result_ref,
                        result_hash=result.result_hash,
                        proposal_hash=result.proposal_hash,
                        result_envelope_hash=result.result_envelope_hash,
                    )
                }
            }

    saver = Saver()

    class Graph:
        checkpointer = saver

        async def astream(self, input: Any, config: Any, **kwargs: Any):
            assert kwargs["stream_mode"] == ["messages", "custom"]
            for field, delta in (
                ("room_utterance", governed_room_utterance),
                ("case_detail.references", raw_dossier_delta),
            ):
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
                                        "node_name": BASELINE_INTAKE_NODE_NAME,
                                        "field": field,
                                        "delta": delta,
                                    }
                                ]
                            },
                        ),
                        {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
                    ),
                )

        async def aget_state(self, config: Any) -> object:
            return object()

    class Store:
        def __init__(self) -> None:
            self.calls = 0
            self.stored = False

        async def put(
            self,
            selected_execution: GatewayExecution,
            *,
            proposal: Any,
            **kwargs: Any,
        ) -> Any:
            self.calls += 1
            assert selected_execution is execution
            assert proposal is canonical
            assert kwargs == {
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-ready-handoff",
                "cognitive_revision": 1,
            }
            self.stored = True
            return SimpleNamespace(
                artifact_id=canonical.artifact_id,
                schema_version=canonical.schema_version,
                uri="s3://intake-proposals/ready-handoff.json",
                sha256=canonical.sha256,
                size_bytes=canonical.size_bytes,
            )

    class Materializer:
        def materialize(self, checkpoint_ns: str, checkpoint_id: str, *, fence: Any) -> Any:
            assert checkpoint_ns == ""
            assert checkpoint_id == "cp-intake-ready-handoff"
            assert fence is execution.fence
            return result

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=Graph()),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_input",
        staticmethod(lambda _: {}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_config",
        staticmethod(lambda _: {"configurable": {}}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(lambda *_: (state, final_config)),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: Usage(input_tokens=0, output_tokens=0, total_tokens=0)),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: canonical,
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_materializer",
        staticmethod(lambda *_, **__: Materializer()),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_target_proposal_source",
        staticmethod(lambda *_: None),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.ExternalTerminalCommit",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class Loader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the test harness patches the Intake context loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=store,
    )

    events = []
    visible_commit_states = []
    async for event in executor.stream(execution):
        events.append(event)
        if event.event_type == "visible_delta":
            visible_commit_states.append(saver.terminal_committed)

    visible = [event for event in events if event.event_type == "visible_delta"]
    room_events = [event for event in visible if event.payload.field == "room_utterance"]
    board_events = [event for event in visible if event not in room_events]

    assert [event.sequence_no for event in events] == list(range(len(events)))
    assert "".join(
        event.payload.delta or "" for event in room_events
    ) == governed_room_utterance
    assert visible_commit_states == [False, False]
    assert max(event.sequence_no for event in room_events) < min(
        event.sequence_no for event in board_events
    )
    all_visible_text = "".join(event.payload.delta or "" for event in visible)
    assert governed_room_utterance in all_visible_text
    assert raw_dossier_delta in all_visible_text
    assert baseline_ready_handoff in all_visible_text
    assert [(event.payload.field, event.payload.delta) for event in board_events] == [
        ("case_detail.references", raw_dossier_delta)
    ]
    assert events[-1].event_type == "final"
    assert saver.preflights == 1
    assert store.calls == 1
    assert saver.commits == 1
    assert saver.terminal_committed


@pytest.mark.asyncio
async def test_target_intake_executor_streams_three_full_width_questions_before_streaming_board(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _target_candidate_intake_execution(command)
    raw_room_deltas = (
        "为了更准确地梳理案情，请问：1. 您是否曾就此事与商家沟通过",
        "？如有，商家是如何回复的",
        "？2. 商品故障对您使用造成了什么影响【RAW_THIRD_QUESTION】",
        "？",
    )
    raw_room_utterance = "".join(raw_room_deltas)
    expected_live_room_after_each_delta = tuple(
        "".join(raw_room_deltas[: index + 1])
        for index in range(len(raw_room_deltas))
    )
    terminal_room_utterance = raw_room_utterance
    assert raw_room_utterance.count("？") == 3
    assert "?" not in raw_room_utterance
    assert "1." in raw_room_utterance and "2." in raw_room_utterance

    state = {
        "terminal_draft": {"same": True},
        "result_json": {"same": True},
        "cognitive_revision": 1,
    }
    final_config = bind_fence_context(
        {
            "configurable": {
                "thread_id": command.thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-question-projection",
            }
        },
        execution.fence,
    )
    proposal = SimpleNamespace(room_utterance=terminal_room_utterance)
    canonical = SimpleNamespace(
        artifact_id="intake-proposal-question-projection",
        schema_version="intake-turn-proposal.v2",
        sha256="a" * 64,
        size_bytes=1,
    )
    result = SimpleNamespace(
        result_ref="urn:intake:question-projection:result",
        result_hash="b" * 64,
        proposal_hash="c" * 64,
        result_envelope_hash="d" * 64,
    )
    raw_board_delta = '{"order_reference":"RAW-AFTER-ROOM"}'
    usage = Usage(input_tokens=3, output_tokens=2, total_tokens=5)
    events: list[Any] = []

    def governed_visible_delta(field: str, delta: str) -> tuple[str, tuple[Any, dict[str, str]]]:
        return (
            "messages",
            (
                AIMessageChunk(
                    content="",
                    additional_kwargs={
                        "governed_events": [
                            {
                                "schema_version": "governed-model-event.v1",
                                "event_type": "visible_delta",
                                "node_name": BASELINE_INTAKE_NODE_NAME,
                                "field": field,
                                "delta": delta,
                            }
                        ]
                    },
                ),
                {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
            ),
        )

    class Saver:
        def __init__(self) -> None:
            self.preflights = 0
            self.commits = 0
            self.terminal_committed = False

        async def avalidate_external_terminal_checkpoint(self, config: Any, **kwargs: Any) -> None:
            self.preflights += 1
            assert config is final_config
            assert kwargs == {"cognitive_revision": 1}
            assert any(
                event.event_type == "visible_delta"
                and event.payload.field == "case_detail.references"
                for event in events
            )

        async def acommit_external_terminal(self, config: Any, commit: Any) -> dict[str, Any]:
            self.commits += 1
            assert config is final_config
            assert commit.result is result
            self.terminal_committed = True
            return {
                "configurable": {
                    FENCE_CONTEXT_KEY: replace(
                        execution.fence,
                        result_ref=result.result_ref,
                        result_hash=result.result_hash,
                        proposal_hash=result.proposal_hash,
                        result_envelope_hash=result.result_envelope_hash,
                    )
                }
            }

    saver = Saver()

    class Graph:
        checkpointer = saver

        def __init__(self) -> None:
            self.source_completed = False
            self.room_after_each_delta: list[str] = []
            self.room_before_board = ""

        async def astream(self, input: Any, config: Any, **kwargs: Any):
            assert kwargs["stream_mode"] == ["messages", "custom"]
            for raw_delta, expected_live_room in zip(
                raw_room_deltas,
                expected_live_room_after_each_delta,
                strict=True,
            ):
                yield governed_visible_delta("room_utterance", raw_delta)
                visible_room = "".join(
                    event.payload.delta or ""
                    for event in events
                    if event.event_type == "visible_delta"
                    and event.payload.field == "room_utterance"
                )
                self.room_after_each_delta.append(visible_room)
                assert visible_room == expected_live_room
            self.room_before_board = "".join(
                event.payload.delta or ""
                for event in events
                if event.event_type == "visible_delta"
                and event.payload.field == "room_utterance"
            )
            yield governed_visible_delta("case_detail.references", raw_board_delta)
            yield ("custom", GraphPublicUpdate.usage(usage))
            self.source_completed = True

        async def aget_state(self, config: Any) -> object:
            return object()

    class Store:
        def __init__(self) -> None:
            self.calls = 0
            self.stored = False

        async def put(
            self,
            selected_execution: GatewayExecution,
            *,
            proposal: Any,
            **kwargs: Any,
        ) -> Any:
            self.calls += 1
            assert selected_execution is execution
            assert proposal is canonical
            assert kwargs == {
                "checkpoint_ns": "",
                "checkpoint_id": "cp-intake-question-projection",
                "cognitive_revision": 1,
            }
            self.stored = True
            return SimpleNamespace(
                artifact_id=canonical.artifact_id,
                schema_version=canonical.schema_version,
                uri="s3://intake-proposals/question-projection.json",
                sha256=canonical.sha256,
                size_bytes=canonical.size_bytes,
            )

    class Materializer:
        def materialize(self, checkpoint_ns: str, checkpoint_id: str, *, fence: Any) -> Any:
            assert checkpoint_ns == ""
            assert checkpoint_id == "cp-intake-question-projection"
            assert fence is execution.fence
            return result

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    graph = Graph()
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=graph),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_input",
        staticmethod(lambda _: {}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_config",
        staticmethod(lambda _: {"configurable": {}}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(lambda *_: (state, final_config)),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: usage),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: canonical,
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_materializer",
        staticmethod(lambda *_, **__: Materializer()),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_target_proposal_source",
        staticmethod(lambda *_: None),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.ExternalTerminalCommit",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class Loader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the test harness patches the Intake context loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=store,
    )

    visible_commit_states = []
    visible_source_completion_states = []
    async for event in executor.stream(execution):
        events.append(event)
        if event.event_type == "visible_delta":
            visible_commit_states.append(saver.terminal_committed)
            visible_source_completion_states.append(
                (event.payload.field, graph.source_completed)
            )

    visible = [event for event in events if event.event_type == "visible_delta"]
    room_events = [event for event in visible if event.payload.field == "room_utterance"]
    board_events = [event for event in visible if event.payload.field != "room_utterance"]
    visible_text = "".join(event.payload.delta or "" for event in visible)

    assert graph.room_after_each_delta == list(expected_live_room_after_each_delta)
    assert graph.room_before_board == expected_live_room_after_each_delta[-1]
    assert events[1].payload.field == "room_utterance"
    assert events[1].payload.delta == raw_room_deltas[0]
    assert [event.payload.delta for event in room_events] == list(raw_room_deltas)
    streamed_room_utterance = "".join(event.payload.delta or "" for event in room_events)
    assert streamed_room_utterance == terminal_room_utterance == raw_room_utterance
    assert "【RAW_THIRD_QUESTION】" in graph.room_before_board
    assert "【RAW_THIRD_QUESTION】" in visible_text
    assert max(event.sequence_no for event in room_events) < min(
        event.sequence_no for event in board_events
    )
    assert [(event.payload.field, event.payload.delta) for event in board_events] == [
        ("case_detail.references", raw_board_delta)
    ]
    assert visible_commit_states == [False] * len(visible)
    assert visible_source_completion_states[-1] == ("case_detail.references", False)
    assert events[-2].event_type == "usage"
    assert events[-1].event_type == "final"
    assert saver.preflights == 1
    assert store.calls == 1
    assert saver.commits == 1
    assert saver.terminal_committed


@pytest.mark.asyncio
async def test_target_intake_executor_rejects_room_append_after_root_close(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _target_candidate_intake_execution(command)
    saver = object()

    def governed_visible_delta(field: str, delta: str) -> tuple[str, tuple[Any, dict[str, str]]]:
        return (
            "messages",
            (
                AIMessageChunk(
                    content="",
                    additional_kwargs={
                        "governed_events": [
                            {
                                "schema_version": "governed-model-event.v1",
                                "event_type": "visible_delta",
                                "node_name": BASELINE_INTAKE_NODE_NAME,
                                "field": field,
                                "delta": delta,
                            }
                        ]
                    },
                ),
                {"langgraph_node": BASELINE_INTAKE_NODE_NAME},
            ),
        )

    class Graph:
        checkpointer = saver

        async def astream(self, input: Any, config: Any, **kwargs: Any):
            assert kwargs["stream_mode"] == ["messages", "custom"]
            yield governed_visible_delta("room_utterance", "Please confirm the order number?")
            yield governed_visible_delta(
                "case_detail.references",
                '{"order_reference":"RAW-AFTER-ROOM"}',
            )
            yield governed_visible_delta("room_utterance", " This must be rejected.")

        async def aget_state(self, config: Any) -> object:
            raise AssertionError("the order breach must fail before terminal state")

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=Graph()),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_input",
        staticmethod(lambda _: {}),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_graph_config",
        staticmethod(lambda _: {"configurable": {}}),
    )

    class Loader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the test harness patches the Intake context loader")

    class Store:
        async def put(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("the order breach must fail before proposal storage")

    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=Loader(),
        proposal_store=Store(),
    )

    events = []
    with pytest.raises(GraphContractError, match="INTAKE_ROOM_UTTERANCE_STREAM_ORDER_INVALID"):
        async for event in executor.stream(execution):
            events.append(event)

    # The provisional board was already visible when the later room-order breach
    # failed the stream.  The caller must emit its normal ERROR/reset path rather
    # than treating that provisional board as a terminal result.
    assert [event.event_type for event in events] == [
        "attempt_started",
        "visible_delta",
        "visible_delta",
    ]
    assert [(event.payload.field, event.payload.delta) for event in events[1:]] == [
        ("room_utterance", "Please confirm the order number?"),
        ("case_detail.references", '{"order_reference":"RAW-AFTER-ROOM"}'),
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "target_candidate",
    (False, True),
    ids=("legacy_non_prefix", "target_non_prefix"),
)
async def test_compiled_intake_executor_rejects_non_prefix_room_before_terminal_commit(
    monkeypatch: pytest.MonkeyPatch,
    target_candidate: bool,
) -> None:
    command, _, _ = _intake_command()
    execution = (
        _target_candidate_intake_execution(command)
        if target_candidate
        else _intake_execution(command)
    )
    streamed_room_utterance = "Please confirm the requested resolution."
    terminal_room_utterance = "Please describe the desired resolution."
    provisional_board_delta = '{"order_reference":"PROVISIONAL-BEFORE-MISMATCH"}'
    assert streamed_room_utterance != terminal_room_utterance
    state = {
        "terminal_draft": {"same": True},
        "result_json": {"same": True},
        "cognitive_revision": 1,
    }
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
    proposal = SimpleNamespace(room_utterance=terminal_room_utterance)

    class Saver:
        def __init__(self) -> None:
            self.preflights = 0
            self.commits = 0

        async def avalidate_external_terminal_checkpoint(self, *args: Any, **kwargs: Any) -> None:
            self.preflights += 1
            raise AssertionError("room mismatch must fail before terminal preflight")

        async def acommit_external_terminal(self, *args: Any, **kwargs: Any) -> None:
            self.commits += 1
            raise AssertionError("room mismatch must fail before terminal commit")

    saver = Saver()

    class Graph:
        checkpointer = saver

        async def astream(self, input, config, **kwargs):
            assert kwargs["stream_mode"] == ["messages", "custom"]
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "room_utterance",
                                    "delta": streamed_room_utterance,
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.references",
                                    "delta": provisional_board_delta,
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )

        async def aget_state(self, config):
            return object()

    class Store:
        def __init__(self) -> None:
            self.calls = 0

        async def put(self, *args: Any, **kwargs: Any) -> Any:
            self.calls += 1
            raise AssertionError("room mismatch must fail before proposal storage")

    async def load_context(*_: Any, **__: Any) -> object:
        return object()

    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=Graph()),
    )
    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(lambda *_: (state, final_config)),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(lambda _: proposal),
    )
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_command_usage",
        staticmethod(lambda *_: Usage(input_tokens=0, output_tokens=0, total_tokens=0)),
    )
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.canonical_intake_proposal",
        lambda _: object(),
    )

    class UnusedLoader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("patched context loader should be the only loader")

    store = Store()
    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=UnusedLoader(),
        proposal_store=store,
    )

    events = []
    with pytest.raises(
        GraphTerminalBindingError,
        match="streamed room utterance differs from normalized terminal proposal",
    ):
        async for event in executor.stream(execution):
            events.append(event)

    assert [event.event_type for event in events] == [
        "attempt_started",
        "visible_delta",
        "visible_delta",
    ]
    assert [(event.payload.field, event.payload.delta) for event in events[1:]] == [
        ("room_utterance", streamed_room_utterance),
        ("case_detail.references", provisional_board_delta),
    ]
    assert saver.preflights == 0
    assert saver.commits == 0
    assert store.calls == 0


@pytest.mark.asyncio
async def test_compiled_intake_executor_suppresses_provisional_dossier_when_model_room_is_absent_before_terminal_guard(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    command, _, _ = _intake_command()
    execution = _intake_execution(command)
    saver = object()

    class Graph:
        async def astream(self, input, config, **kwargs):
            assert kwargs["stream_mode"] == ["messages", "custom"]
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.dispute_core_state",
                                    "delta": json.dumps("Please confirm the requested resolution."),
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.case_story",
                                    "delta": '{"one_sentence_summary":"uncommitted"}',
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )
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
                                    "node_name": BASELINE_INTAKE_NODE_NAME,
                                    "field": "case_detail.respondent_attitude",
                                    "delta": '{"status":"UNKNOWN","description":"待确认"}',
                                }
                            ]
                        },
                    ),
                    {"langgraph_node": "intake_lcel"},
                ),
            )

        async def aget_state(self, config):
            return object()

    graph = Graph()
    graph.checkpointer = saver
    monkeypatch.setattr(
        "app.graph_runtime.intake_executor.build_governed_intake_runtime",
        lambda **kwargs: SimpleNamespace(graph=graph),
    )

    async def load_context(*args: Any) -> object:
        return object()

    def terminal_snapshot(*args: Any) -> tuple[Any, Any]:
        return {}, {}

    def terminal_business_guard_failure(*args: Any) -> Any:
        raise GraphTerminalBindingError("terminal business guard failed")

    monkeypatch.setattr(CompiledIntakeGraphShadowExecutor, "_load_context", load_context)
    monkeypatch.setattr(
        CompiledIntakeGraphShadowExecutor,
        "_snapshot",
        staticmethod(terminal_snapshot),
    )
    monkeypatch.setattr(
        IntakeRuntimeBundle,
        "terminal_proposal",
        staticmethod(terminal_business_guard_failure),
    )

    class UnusedLoader:
        async def load(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("terminal guard failure must happen before a load")

    class UnusedStore:
        async def put(self, *args: Any, **kwargs: Any) -> Any:
            raise AssertionError("terminal guard failure must happen before a proposal write")

    executor = CompiledIntakeGraphShadowExecutor(
        saver=cast(Any, saver),
        transport=cast(Any, object()),
        provider="synthetic",
        model="intake-model",
        input_loader=UnusedLoader(),
        proposal_store=UnusedStore(),
    )

    events = []
    with pytest.raises(GraphTerminalBindingError, match="terminal business guard failed"):
        async for event in executor.stream(execution):
            events.append(event)

    assert [event.event_type for event in events] == ["attempt_started"]


@pytest.mark.parametrize(
    "absence_marker",
    ["UNKNOWN", "PLATFORM_UNKNOWN", "NOT_RESPONDED", "NOT_ADDRESSED"],
)
def test_compiled_intake_executor_suppresses_absent_respondent_attitude_updates(
    absence_marker: str,
) -> None:
    update = GraphPublicUpdate.visible_delta(
        node="intake_lcel",
        field="case_detail.respondent_attitude",
        delta=json.dumps(
            {"status": absence_marker, "description": "待确认"},
            ensure_ascii=False,
        ),
    )

    assert CompiledIntakeGraphShadowExecutor._should_suppress_respondent_attitude_update(update)


def test_compiled_intake_executor_defers_substantive_respondent_attitude_to_terminal_gate() -> None:
    update = GraphPublicUpdate.visible_delta(
        node="intake_lcel",
        field="case_detail.respondent_attitude",
        delta=json.dumps(
            {
                "attitude": "DISAGREE",
                "position": "The merchant rejected the requested refund.",
            }
        ),
    )

    assert CompiledIntakeGraphShadowExecutor._should_suppress_respondent_attitude_update(update)


def test_compiled_intake_executor_replaces_all_streamable_dossier_sections_at_terminal() -> None:
    document = json.loads(
        (
            ROOT
            / "contracts/agent-platform/intake/v2/fixtures/valid/intake-turn-proposal-valid.json"
        ).read_text(encoding="utf-8")
    )
    document["dossier_patch"].update(
        {
            "references": {"order_reference": "ORDER_AUTHORITATIVE"},
            "party_positions": {"user_claim": "Authoritative claim"},
            "dispute_core_state": {
                "core_conflict": "Authoritative conflict",
                "facts_in_dispute": ["Authoritative fact"],
                "next_verification_focus": ["Authoritative focus"],
            },
            "missing_information": {"next_questions": ["Authoritative question?"]},
            "intake_quality": {"score": 0.72},
        }
    )
    document["proposal_hash"] = canonical_sha256_omitting(document, "proposal_hash")
    proposal = IntakeTurnProposal.model_validate(document)

    updates = CompiledIntakeGraphShadowExecutor._terminal_normalized_dossier_updates(
        proposal
    )
    replacements = {
        update.payload.field: json.loads(update.payload.delta or "") for update in updates
    }

    assert replacements["case_detail.case_story"] == document["dossier_patch"]["case_story"]
    assert replacements["case_detail.references"] == document["dossier_patch"]["references"]
    assert replacements["case_detail.party_positions"] == document["dossier_patch"][
        "party_positions"
    ]
    assert replacements["case_detail.dispute_core_state"] == document["dossier_patch"][
        "dispute_core_state"
    ]
    assert replacements["case_detail.missing_information"] == document["dossier_patch"][
        "missing_information"
    ]
    assert replacements["case_detail.intake_quality"] == document["dossier_patch"][
        "intake_quality"
    ]


def test_compiled_intake_executor_rejects_dual_respondent_attitude_discriminators() -> None:
    update = GraphPublicUpdate.visible_delta(
        node="intake_lcel",
        field="case_detail.respondent_attitude",
        delta=json.dumps(
            {
                "attitude": "DISAGREE",
                "status": "UNKNOWN",
                "position": "The merchant rejected the requested refund.",
            }
        ),
    )

    with pytest.raises(
        GraphContractError,
        match="INTAKE_RESPONDENT_ATTITUDE_STREAM_INVALID",
    ):
        CompiledIntakeGraphShadowExecutor._should_suppress_respondent_attitude_update(update)


def test_compiled_intake_executor_preserves_prompt_owned_room_utterance() -> None:
    update = GraphPublicUpdate.visible_delta(
        node=BASELINE_INTAKE_NODE_NAME,
        field="room_utterance",
        delta=json.dumps("请上传截图作为证据。", ensure_ascii=False),
    )

    preserved = CompiledIntakeGraphShadowExecutor._validated_room_utterance_update(update)

    assert preserved.payload.delta == update.payload.delta


def test_compiled_intake_executor_allows_historical_evidence_dossier_update() -> None:
    historical_fact = "商家稍后将上传官方链接以佐证标准编号123345"
    update = GraphPublicUpdate.visible_delta(
        node="intake_lcel",
        field="case_detail.case_story",
        delta=json.dumps(
            {
                "one_sentence_summary": historical_fact,
            },
            ensure_ascii=False,
        ),
    )

    CompiledIntakeGraphShadowExecutor._validate_public_update(update)


def test_compiled_intake_executor_rejects_forged_custom_visible_delta() -> None:
    forged = (
        "custom",
        GraphPublicUpdate.visible_delta(
            node="intake_lcel",
            field="case_detail.case_story",
            delta='{"one_sentence_summary":"forged"}',
        ),
    )

    with pytest.raises(GraphContractError, match="INTAKE_CUSTOM_VISIBLE_DELTA_FORBIDDEN"):
        CompiledIntakeGraphShadowExecutor._public_updates(forged)


def test_compiled_intake_executor_rejects_bare_public_update() -> None:
    forged = GraphPublicUpdate.visible_delta(
        node="intake_lcel",
        field="case_detail.case_story",
        delta='{"one_sentence_summary":"forged"}',
    )

    with pytest.raises(GraphContractError, match="INTAKE_PUBLIC_UPDATE_BYPASS_FORBIDDEN"):
        CompiledIntakeGraphShadowExecutor._public_updates(forged)


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


@pytest.mark.parametrize(
    ("fixture_name", "timestamp_field", "hash_field", "reference_field", "kind"),
    [
        (
            "intake-domain-snapshot-valid.json",
            "created_at",
            "snapshot_hash",
            "domain_snapshot_ref",
            "SNAPSHOT",
        ),
        (
            "intake-turn-event-valid.json",
            "occurred_at",
            "event_hash",
            "event_ref",
            "EVENT",
        ),
    ],
)
def test_intake_ingress_preserves_canonical_nanosecond_timestamps_after_validation(
    fixture_name: str,
    timestamp_field: str,
    hash_field: str,
    reference_field: str,
    kind: str,
) -> None:
    command, _, _ = _intake_command()
    document = json.loads(
        (ROOT / "contracts/agent-platform/intake/v2/fixtures/valid" / fixture_name).read_text(
            encoding="utf-8"
        )
    )
    timestamp = "2026-07-20T08:02:00.366349890Z"
    document[timestamp_field] = timestamp
    document[hash_field] = canonical_sha256_omitting(document, hash_field)
    payload = canonicalize(document)
    reference = SnapshotRef(
        artifact_id=document["event_id"] if kind == "EVENT" else document["snapshot_id"],
        schema_version=document["schema_version"],
        uri=f"s3://graph-input/intake/{fixture_name}",
        sha256=document[hash_field],
        size_bytes=len(payload),
    )
    command = command.model_copy(update={reference_field: reference})

    context = decode_authorized_intake_ingress(
        command=command,
        object_ref=reference,
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

    assert context.ingress_kind == kind
    assert context.ingress_payload[timestamp_field] == timestamp
    assert canonical_sha256_omitting(context.ingress_payload, hash_field) == reference.sha256


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

    try:
        loaded = await client.load(execution)
    finally:
        await client.aclose()

    assert loaded.object_version == "version-1"
    assert loaded.canonical_payload == payload
    assert loaded.sha256 == command.domain_snapshot_ref.sha256


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "origin",
    ["http://java-api-service:8080", "https://java-api-service:8443"],
)
async def test_java_intake_exchange_builds_one_lazy_baseline_environment_client(
    monkeypatch: pytest.MonkeyPatch,
    origin: str,
) -> None:
    real_client = httpx.AsyncClient
    constructor_options: list[dict[str, Any]] = []

    def build_client(**options: Any) -> httpx.AsyncClient:
        constructor_options.append(options)
        return real_client(**options)

    monkeypatch.setattr(httpx, "AsyncClient", build_client)
    client = JavaIntakeExchangeClient(
        java_api_service_url=origin,
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(lambda _: httpx.Response(200)),
    )
    try:
        assert constructor_options == []
        await asyncio.gather(client.aopen(), client.aopen())
        assert len(constructor_options) == 1
        assert constructor_options[0]["base_url"] == origin
        assert constructor_options[0]["follow_redirects"] is False
        # Preserve baseline proxy, SSL_CERT_*, and trusted-CA behavior.
        assert "trust_env" not in constructor_options[0]
        assert "verify" not in constructor_options[0]
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_java_intake_exchange_close_before_open_never_builds_or_reopens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    real_client = httpx.AsyncClient
    constructor_count = 0

    def build_client(**options: Any) -> httpx.AsyncClient:
        nonlocal constructor_count
        constructor_count += 1
        return real_client(**options)

    monkeypatch.setattr(httpx, "AsyncClient", build_client)
    client = JavaIntakeExchangeClient(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(lambda _: httpx.Response(200)),
    )

    assert client._client is None
    await asyncio.gather(client.aclose(), client.aclose())
    await client.aclose()
    assert constructor_count == 0
    assert client._client is None
    with pytest.raises(GraphContractError, match="client is closed"):
        await client.aopen()
    with pytest.raises(GraphContractError, match="client is closed"):
        await client._post("/after-close", {}, maximum_bytes=64)
    assert constructor_count == 0


@pytest.mark.asyncio
async def test_java_intake_exchange_reuses_one_client_and_drains_concurrent_requests(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class TrackingTransport(httpx.AsyncBaseTransport):
        def __init__(self) -> None:
            self.request_count = 0
            self.active = 0
            self.peak_active = 0
            self.close_count = 0
            self.closed = False
            self.two_started = asyncio.Event()
            self.release = asyncio.Event()

        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            assert not self.closed
            self.request_count += 1
            self.active += 1
            self.peak_active = max(self.peak_active, self.active)
            if self.active == 2:
                self.two_started.set()
            try:
                await asyncio.wait_for(self.release.wait(), timeout=1)
                return httpx.Response(
                    200,
                    headers={"content-type": "application/json"},
                    content=b"{}",
                )
            finally:
                self.active -= 1

        async def aclose(self) -> None:
            self.close_count += 1
            self.closed = True

    real_client = httpx.AsyncClient
    constructor_count = 0

    def build_client(**options: Any) -> httpx.AsyncClient:
        nonlocal constructor_count
        constructor_count += 1
        return real_client(**options)

    monkeypatch.setattr(httpx, "AsyncClient", build_client)
    transport = TrackingTransport()
    client = JavaIntakeExchangeClient(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=transport,
    )
    assert constructor_count == 0
    await asyncio.gather(client.aopen(), client.aopen())
    assert constructor_count == 1
    http_client = client._client
    assert http_client is not None
    requests = [
        asyncio.create_task(client._post("/first", {}, maximum_bytes=64)),
        asyncio.create_task(client._post("/second", {}, maximum_bytes=64)),
    ]
    await asyncio.wait_for(transport.two_started.wait(), timeout=1)
    closers = [asyncio.create_task(client.aclose()), asyncio.create_task(client.aclose())]
    while not client._closing:
        await asyncio.sleep(0)

    with pytest.raises(GraphContractError, match="client is closed"):
        await client._post("/after-close", {}, maximum_bytes=64)
    assert all(not closer.done() for closer in closers)

    transport.release.set()
    assert await asyncio.gather(*requests) == [{}, {}]
    await asyncio.gather(*closers)
    await client.aclose()

    assert constructor_count == 1
    assert client._client is http_client
    assert transport.request_count == 2
    assert transport.peak_active == 2
    assert transport.close_count == 1
    assert http_client.is_closed


@pytest.mark.asyncio
async def test_java_intake_exchange_cancelled_request_cannot_strand_close() -> None:
    class CancellationTrackingTransport(httpx.AsyncBaseTransport):
        def __init__(self) -> None:
            self.started = asyncio.Event()
            self.never_complete = asyncio.Event()
            self.close_count = 0

        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            self.started.set()
            await self.never_complete.wait()
            raise AssertionError("cancelled exchange must not produce a response")

        async def aclose(self) -> None:
            self.close_count += 1

    transport = CancellationTrackingTransport()
    client = JavaIntakeExchangeClient(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=transport,
    )
    await client.aopen()
    request = asyncio.create_task(client._post("/cancel", {}, maximum_bytes=64))
    await asyncio.wait_for(transport.started.wait(), timeout=1)
    closers = [asyncio.create_task(client.aclose()), asyncio.create_task(client.aclose())]
    while not client._closing:
        await asyncio.sleep(0)

    # Hold the lifecycle lock so the former await-based lease return would be
    # interrupted and lost by the second cancellation.
    await client._lifecycle.acquire()
    try:
        assert request.cancel()
        await asyncio.sleep(0)
        request.cancel()
    finally:
        client._lifecycle.release()

    with pytest.raises(asyncio.CancelledError):
        await request
    await asyncio.wait_for(asyncio.gather(*closers), timeout=1)
    await client.aclose()

    assert client._active_requests == 0
    assert client._idle.is_set()
    assert transport.close_count == 1


@pytest.mark.asyncio
async def test_intake_executor_bootstraps_with_two_exact_loads_then_resumes_with_one() -> None:
    command, snapshot, snapshot_payload = _intake_command()
    event = json.loads(
        (
            ROOT / "contracts/agent-platform/intake/v2/fixtures/valid/intake-turn-event-valid.json"
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

    try:
        with pytest.raises(GraphContractError, match="receipt|hash"):
            await client.load(execution)
    finally:
        await client.aclose()


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
    proposal_document["profile_versions"]["prompt_version"] = (
        command.invocation_context.prompt_profile_id
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

    try:
        stored = await client.put(
            execution,
            proposal=proposal,
            checkpoint_ns="",
            checkpoint_id="cp-terminal",
            cognitive_revision=2,
        )
    finally:
        await client.aclose()

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
