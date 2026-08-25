from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.config import (
    GraphTargetE2EBindingSettings,
    GraphTargetE2ERuntimeContextSettings,
)
from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting
from app.contracts.v1.models import (
    PARALLEL_INTAKE_AGENT_PROFILE_ID,
    PARALLEL_INTAKE_OUTPUT_SCHEMA,
    AgentStreamEvent,
    AgentStreamPayload,
    RoomGraphCommand,
)
from app.graph_runtime.errors import (
    GraphCommandAbortedError,
    GraphCommandCancelledError,
    GraphCommandNotFoundError,
    GraphContractError,
    GraphGatewayDisabledError,
    GraphLeaseLostError,
    GraphNewAgentAttemptRequiredError,
    GraphNonceReplayError,
    GraphResultNotCommittedError,
    GraphTerminalBindingError,
    GraphThreadBindingError,
    GraphVersionBindingError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.gateway import (
    AdmissionAction,
    GatewayAuditEvent,
    GatewayAdmission,
    GatewayExecution,
    GraphCommandGateway,
    ReconciliationDisposition,
)
from app.graph_runtime.identity import (
    ActorRole,
    ActorScopeBinding,
    Audience,
    RoomType,
    ThreadIdentity,
    ThreadLifecycle,
    ThreadRecord,
)
from app.graph_runtime.ledger import (
    AttemptRecord,
    AttemptStatus,
    CommandBinding,
    CommandRecord,
    CommandRegistration,
    CommandStatus,
    ParallelReceiptExecutionRecord,
    ResultRecord,
    TechnicalCompletionRecord,
)
from app.graph_runtime.lease import (
    LeaseAcquisition,
    LeaseAcquisitionKind,
    LeaseDisplacement,
    LeaseInspection,
    LeaseRecord,
)
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.registry import (
    CommandProfileBinding,
    RegistryRecord,
    RegistryState,
    VersionBinding,
)
from app.graph_runtime.target_e2e import TargetE2ERuntimeAuthority
from app.security.invocation_envelope import (
    InvocationClaims,
    ReconciliationClaims,
    VerifiedInvocation,
    VerifiedReconciliation,
    invocation_binding_claims,
)


NOW = datetime(2026, 7, 19, 8, 0, tzinfo=timezone.utc)
THREAD = f"grt.v1.{'5' * 32}"


def _command() -> RoomGraphCommand:
    payload: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "command_id": "command-1",
        "logical_run_id": "run-1",
        "attempt_id": "attempt-1",
        "tenant_surrogate": "tenant-1",
        "case_id": "case-1",
        "room_type": "INTAKE",
        "room_epoch": 2,
        "graph_key": "intake.flow",
        "graph_version": "intake.v2",
        "checkpoint_schema_version": "intake.checkpoint.v2",
        "thread_id": THREAD,
        "actor_scope": {
            "actor_id": "user-1",
            "actor_role": "USER",
            "audience": "USER",
            "capabilities": ["order.read"],
        },
        "process_revision": 3,
        "stage_code": "INTAKE_ACTIVE",
        "stage_sequence": 1,
        "domain_snapshot_ref": {
            "artifact_id": "snapshot-1",
            "schema_version": "case-snapshot.v1",
            "uri": "s3://graph-input/snapshot-1.json",
            "sha256": "a" * 64,
            "size_bytes": 128,
        },
        "invocation_context": {
            "agent_profile_id": "intake-agent.v2",
            "prompt_profile_id": "intake.prompt.v2",
            "model_profile_id": "model.standard.v1",
            "output_schema_version": "intake.output.v2",
            "policy_version": "policy.v2",
            "guardrail_version": "guardrail.v2",
            "tool_capabilities": ["order.read"],
            "envelope_key_id": "java-key-1",
            "envelope_nonce": "envelope-nonce-1",
        },
        "retry_budget": {
            "provider_attempts_remaining": 2,
            "activity_attempts_remaining": 3,
            "repairs_remaining": 1,
        },
        "deadline_at": "2026-07-19T08:01:00Z",
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    }
    payload["request_hash"] = canonical_sha256(payload)
    return RoomGraphCommand.model_validate(payload)


def _thread() -> ThreadIdentity:
    return ThreadIdentity(
        thread_id=THREAD,
        tenant_surrogate="tenant-1",
        case_id="case-1",
        room_type=RoomType.INTAKE,
        room_epoch=2,
        actor_scope=ActorScopeBinding(
            actor_id="user-1",
            actor_role=ActorRole.USER,
            audience=Audience.USER,
            capabilities=("order.read",),
        ),
        agent_session_id="agent-session-1",
        shared_session=False,
        graph_key="intake.flow",
        graph_version="intake.v2",
        checkpoint_schema_version="intake.checkpoint.v2",
    )


def _verified(command: RoomGraphCommand) -> VerifiedInvocation:
    claims = {
        "iss": "java-api-service",
        "aud": "python-agent-service",
        "sub": "graph-command",
        "iat": int(NOW.timestamp()),
        "nbf": int(NOW.timestamp()),
        "exp": int((NOW + timedelta(seconds=60)).timestamp()),
        "jti": "jti-1",
        **invocation_binding_claims(
            command,
            registry_binding_hash="e" * 64,
            tool_policy_version="tools.none.v1",
        ),
    }
    return VerifiedInvocation(
        claims=InvocationClaims.model_validate(claims),
        key_id="java-key-1",
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
    )


def _verified_reconciliation(command: RoomGraphCommand) -> VerifiedReconciliation:
    claims = {
        "iss": "java-api-service",
        "aud": "python-agent-service",
        "sub": "graph-reconcile",
        "capability": "RECONCILE_ONLY",
        "original_envelope_key_id": command.invocation_context.envelope_key_id,
        "iat": int(NOW.timestamp()),
        "nbf": int(NOW.timestamp()),
        "exp": int((NOW + timedelta(seconds=60)).timestamp()),
        "jti": "reconcile-jti-1",
        **invocation_binding_claims(
            command,
            registry_binding_hash="e" * 64,
            tool_policy_version="tools.none.v1",
        ),
    }
    return VerifiedReconciliation(
        claims=ReconciliationClaims.model_validate(claims),
        key_id="java-key-2",
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
    )


def _registry() -> RegistryRecord:
    return RegistryRecord(
        binding=VersionBinding(
            graph_key="intake.flow",
            graph_version="intake.v2",
            checkpoint_schema_version="intake.checkpoint.v2",
            state_schema_version="intake.state.v2",
            state_schema_hash="d" * 64,
            command_schema_version="room-graph-command.v1",
            result_schema_version="room-graph-result.v1",
            prompt_version="intake.prompt.v2",
            model_profile_id="model.standard.v1",
            output_schema_version="intake.output.v2",
            policy_version="policy.v2",
            guardrail_version="guardrail.v2",
            tool_policy_version="tools.none.v1",
            binding_hash="e" * 64,
            code_build_id="build-1",
        ),
        state=RegistryState.SHADOW,
        loadable=True,
        revision=1,
    )


def _target_e2e_command(
    *,
    actor_role: str = "USER",
    audience: str | None = None,
    room_type: str = "INTAKE",
    graph_key: str = "all-rooms.target-e2e.v2",
    prompt_profile_id: str | None = None,
) -> RoomGraphCommand:
    payload = _command().model_dump(
        mode="json",
        exclude={"request_hash"},
        exclude_none=True,
    )
    selected_audience = audience or actor_role
    payload.update(
        {
            "room_type": room_type,
            "graph_key": graph_key,
            "graph_version": "target-e2e-graph.2026-08-18.1",
            "checkpoint_schema_version": "target-e2e-checkpoint.v2",
            "actor_scope": {
                **payload["actor_scope"],
                "actor_role": actor_role,
                "audience": selected_audience,
            },
            "invocation_context": {
                **payload["invocation_context"],
                "prompt_profile_id": (
                    prompt_profile_id
                    or f"DISPUTE_INTAKE_OFFICER:{actor_role}:v1"
                ),
                "output_schema_version": "target-e2e-room-proposal-source.v2",
            },
        }
    )
    payload["request_hash"] = "0" * 64
    payload["request_hash"] = canonical_sha256_omitting(payload, "request_hash")
    return RoomGraphCommand.model_validate(payload)


def _target_e2e_registry(*, prompt_version: str = "all-rooms-prompt.target-e2e.v2") -> RegistryRecord:
    record = _registry()
    return replace(
        record,
        binding=replace(
            record.binding,
            graph_key="all-rooms.target-e2e.v2",
            graph_version="target-e2e-graph.2026-08-18.1",
            checkpoint_schema_version="target-e2e-checkpoint.v2",
            prompt_version=prompt_version,
            output_schema_version="target-e2e-room-proposal-source.v2",
        ),
        state=RegistryState.ACTIVE_CANDIDATE,
    )


def _target_e2e_thread(command: RoomGraphCommand) -> ThreadIdentity:
    scope = command.actor_scope
    return ThreadIdentity(
        thread_id=command.thread_id,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType(command.room_type),
        room_epoch=command.room_epoch,
        actor_scope=ActorScopeBinding(
            actor_id=scope.actor_id,
            actor_role=ActorRole(scope.actor_role),
            audience=Audience(scope.audience),
            capabilities=tuple(scope.capabilities),
        ),
        agent_session_id="agent-session-1",
        shared_session=False,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )


def _target_e2e_authority(
    command: RoomGraphCommand,
    registry: RegistryRecord,
    *,
    activation_id: str,
) -> TargetE2ERuntimeAuthority:
    context = GraphTargetE2ERuntimeContextSettings.model_validate(
        {
            "schemaVersion": "graph-target-e2e-runtime-context.v1",
            "executionLane": "TARGET_E2E_CANDIDATE",
            "activationId": activation_id,
            "activationManifestHash": "f" * 64,
            "environmentId": "target-e2e-test",
            "environmentGeneration": 1,
            "candidateSha": "c" * 40,
            "issuedAt": NOW,
            "expiresAt": NOW + timedelta(hours=1),
            "runNonce": "target-e2e-test-run-nonce-000001",
            "tenantSurrogate": command.tenant_surrogate,
            "caseScope": {
                "mode": "EXPLICIT_CASE_IDS",
                "allowedCaseIds": [command.case_id],
            },
            "allowedRoomTypes": [command.room_type],
            "composeProject": "p9_target_e2e",
            "temporalNamespace": "target-e2e-test",
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
                    "database": "target_domain",
                    "schema": "domain_runtime",
                    "expectedUser": "java_domain_runtime",
                },
                "graph": {
                    "service": "graph-db",
                    "database": "target_graph",
                    "schema": "graph_runtime",
                    "runtimeUser": "graph_runtime",
                    "environmentGeneration": 1,
                    "restoreVerificationHash": "6" * 64,
                },
            },
            "trustedSigningKeyIds": ["target-test-key-1"],
            "perCommandManifestAllowed": False,
        }
    )
    invocation = command.invocation_context
    runtime_binding = GraphTargetE2EBindingSettings(
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        state_schema_version="target-e2e-state.v2",
        state_schema_hash="d" * 64,
        command_schema_version=command.schema_version,
        result_schema_version="room-graph-result.v1",
        agent_profile_id=invocation.agent_profile_id,
        prompt_version=invocation.prompt_profile_id,
        model_profile_id=invocation.model_profile_id,
        output_schema_version=invocation.output_schema_version,
        policy_version=invocation.policy_version,
        guardrail_version=invocation.guardrail_version,
        tool_policy_version=registry.binding.tool_policy_version,
        binding_hash=registry.binding.binding_hash,
        code_build_id=registry.binding.code_build_id,
        allowed_room_types=("INTAKE", "EVIDENCE", "HEARING", "REVIEW"),
        allowed_stage_codes=(command.stage_code,),
    )
    return TargetE2ERuntimeAuthority.from_context(context, (runtime_binding,))


def _command_profile(command: RoomGraphCommand) -> CommandProfileBinding:
    invocation = command.invocation_context
    return CommandProfileBinding(
        command_schema_version=command.schema_version,
        prompt_version=invocation.prompt_profile_id,
        model_profile_id=invocation.model_profile_id,
        output_schema_version=invocation.output_schema_version,
        policy_version=invocation.policy_version,
        guardrail_version=invocation.guardrail_version,
        tool_policy_version="tools.none.v1",
    )


@pytest.mark.parametrize("actor_role", ("USER", "MERCHANT"))
def test_target_e2e_intake_accepts_only_the_matching_baseline_prompt_alias(
    actor_role: str,
) -> None:
    command = _target_e2e_command(actor_role=actor_role)
    actual_profile = _command_profile(command)

    GraphCommandGateway._require_registry_command_profile(
        command=command,
        registry=_target_e2e_registry(),
        actual_profile=actual_profile,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
    )

    assert actual_profile.prompt_version == f"DISPUTE_INTAKE_OFFICER:{actor_role}:v1"


@pytest.mark.parametrize(
    ("command", "registry", "actual_profile", "execution_lane"),
    [
        (
            _target_e2e_command(prompt_profile_id="all-rooms-prompt.target-e2e.v2"),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
        (
            _target_e2e_command(prompt_profile_id="DISPUTE_INTAKE_OFFICER:ADMIN:v1"),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
        (
            _target_e2e_command(actor_role="USER", prompt_profile_id="DISPUTE_INTAKE_OFFICER:MERCHANT:v1"),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
        (
            _target_e2e_command(actor_role="USER", audience="MERCHANT"),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
        (
            _target_e2e_command(),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.SHADOW,
        ),
        (
            _target_e2e_command(room_type="EVIDENCE"),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
        (
            _target_e2e_command(graph_key="other.target-e2e.v1"),
            _target_e2e_registry(),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
        (
            _target_e2e_command(),
            _target_e2e_registry(prompt_version="other-prompt.v1"),
            None,
            GraphGatewayMode.TARGET_E2E_CANDIDATE,
        ),
    ],
)
def test_target_e2e_intake_prompt_alias_rejects_wrong_scope_and_legacy_pin(
    command: RoomGraphCommand,
    registry: RegistryRecord,
    actual_profile: CommandProfileBinding | None,
    execution_lane: GraphGatewayMode,
) -> None:
    with pytest.raises(GraphVersionBindingError):
        GraphCommandGateway._require_registry_command_profile(
            command=command,
            registry=registry,
            actual_profile=actual_profile or _command_profile(command),
            execution_lane=execution_lane,
        )


@pytest.mark.parametrize(
    "profile_field",
    (
        "command_schema_version",
        "model_profile_id",
        "output_schema_version",
        "policy_version",
        "guardrail_version",
        "tool_policy_version",
    ),
)
def test_target_e2e_intake_prompt_alias_rejects_every_non_prompt_profile_drift(
    profile_field: str,
) -> None:
    command = _target_e2e_command()
    actual_profile = replace(
        _command_profile(command),
        **{profile_field: f"drifted-{profile_field}.v1"},
    )

    with pytest.raises(GraphVersionBindingError):
        GraphCommandGateway._require_registry_command_profile(
            command=command,
            registry=_target_e2e_registry(),
            actual_profile=actual_profile,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        )


class _Cursor:
    async def fetchone(self) -> None:
        return None


class _Transaction:
    def __init__(self, events: list[str]) -> None:
        self.events = events

    async def __aenter__(self) -> None:
        self.events.append("transaction:enter")

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.events.append("transaction:rollback" if exc_type else "transaction:commit")


class _Connection:
    def __init__(self, events: list[str]) -> None:
        self.events = events

    def transaction(self) -> _Transaction:
        return _Transaction(self.events)

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        raise AssertionError("stub repositories own this test")


class _ConnectionContext:
    def __init__(self, connection: _Connection) -> None:
        self.connection = connection

    async def __aenter__(self) -> _Connection:
        self.connection.events.append("connection:enter")
        return self.connection

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.connection.events.append("connection:exit")


class _Pool:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.connection_value = _Connection(self.events)

    def connection(self, *, timeout: float) -> _ConnectionContext:
        assert timeout == 3.0
        return _ConnectionContext(self.connection_value)


class _Threads:
    def __init__(
        self,
        events: list[str],
        lifecycle: ThreadLifecycle = ThreadLifecycle.ACTIVE,
    ) -> None:
        self.events = events
        self.lifecycle = lifecycle

    async def ensure_registered(
        self,
        connection: Any,
        expected: ThreadIdentity,
    ) -> ThreadRecord:
        self.events.append("repo:thread")
        return ThreadRecord(
            identity=expected,
            lifecycle=self.lifecycle,
            cognitive_revision=0,
            last_checkpoint_ns=None,
            last_checkpoint_id=None,
        )

    async def require_binding(
        self,
        connection: Any,
        expected: ThreadIdentity,
    ) -> ThreadRecord:
        self.events.append("repo:thread-binding")
        return ThreadRecord(
            identity=expected,
            lifecycle=self.lifecycle,
            cognitive_revision=0,
            last_checkpoint_ns=None,
            last_checkpoint_id=None,
        )


class _Registry:
    def __init__(
        self,
        events: list[str],
        state: RegistryState = RegistryState.SHADOW,
        record: RegistryRecord | None = None,
    ) -> None:
        self.events = events
        self.state = state
        self.record = record

    async def load(self, connection: Any, **kwargs: Any) -> RegistryRecord:
        self.events.append("repo:registry")
        return replace(self.record or _registry(), state=self.state)

    async def require_thread_restore(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> RegistryRecord:
        self.events.append("repo:registry-restore")
        record = replace(self.record or _registry(), state=self.state)
        record.require_thread_restore()
        return record


class _Ledger:
    def __init__(
        self,
        events: list[str],
        *,
        replay: bool = False,
        status: CommandStatus = CommandStatus.REGISTERED,
        created: bool = True,
    ) -> None:
        self.events = events
        self.replay = replay
        self.status = status
        self.created = created

    async def register_with_nonce(
        self, connection: Any, *, binding: Any, nonce: Any
    ) -> CommandRegistration:
        self.events.append("repo:command+nonce")
        if self.replay:
            raise GraphNonceReplayError()
        record = CommandRecord(
            binding=binding,
            status=self.status,
            attempt_count=0,
            fencing_token=None,
            start_checkpoint_ns=None,
            start_checkpoint_id=None,
            committed_checkpoint_ns=None,
            committed_checkpoint_id=None,
            result_ref=None,
            result_hash=None,
            error_code=None,
            error_classification=None,
            revision=0,
        )
        return CommandRegistration(record, self.created)

    async def consume_nonce_for_existing(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        nonce: Any,
    ) -> CommandRecord:
        self.events.append("repo:existing-command+nonce")
        return CommandRecord(
            binding=binding,
            status=self.status,
            attempt_count=1,
            fencing_token=1 if self.status is not CommandStatus.REGISTERED else None,
            start_checkpoint_ns="",
            start_checkpoint_id="checkpoint-start",
            committed_checkpoint_ns=(
                ""
                if self.status in {CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED}
                else None
            ),
            committed_checkpoint_id=(
                "checkpoint-1"
                if self.status in {CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED}
                else None
            ),
            result_ref=(
                "urn:after-sale-flow:graph-result:test"
                if self.status in {CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED}
                else None
            ),
            result_hash=(
                "f" * 64
                if self.status in {CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED}
                else None
            ),
            error_code=None,
            error_classification=None,
            revision=1,
        )

    async def referenced_verification_key_ids(self, connection: Any) -> frozenset[str]:
        self.events.append("repo:kids")
        return frozenset({"java-key-1", "java-key-previous"})


class _InputAuthorizer:
    def __init__(self, events: list[str]) -> None:
        self.events = events

    async def authorize(self, **kwargs: Any) -> None:
        self.events.append("input:authorized")


class _RejectingInputAuthorizer:
    async def authorize(self, **kwargs: Any) -> None:
        raise GraphThreadBindingError("IMMUTABLE_INPUT_REJECTED")


class _Audit:
    def __init__(self) -> None:
        self.events: list[GatewayAuditEvent] = []

    async def emit(self, event: GatewayAuditEvent) -> None:
        self.events.append(event)


def _gateway(pool: _Pool, ledger: _Ledger, audit: _Audit) -> GraphCommandGateway:
    return GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        registry=_Registry(pool.events),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer(pool.events),
        audit_sink=audit,
    )


@pytest.mark.asyncio
async def test_admission_uses_one_explicit_transaction_for_thread_registry_and_nonce() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = _gateway(pool, _Ledger(pool.events), audit)
    command = _command()

    admission = await gateway.admit(
        command=command,
        verified_invocation=_verified(command),
        expected_thread=_thread(),
    )

    assert admission.action is AdmissionAction.ACQUIRE
    assert pool.events == [
        "input:authorized",
        "connection:enter",
        "transaction:enter",
        "repo:registry",
        "repo:thread",
        "repo:command+nonce",
        "transaction:commit",
        "connection:exit",
    ]
    assert audit.events[0].request_hash == command.request_hash


@pytest.mark.asyncio
async def test_signed_registry_profile_mismatch_fails_before_thread_or_ledger_write() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = _gateway(pool, _Ledger(pool.events), audit)
    command = _command()
    verified = _verified(command)
    forged = VerifiedInvocation(
        claims=verified.claims.model_copy(
            update={"profile_bindings_hash": "0" * 64}
        ),
        key_id=verified.key_id,
        request_hash=verified.request_hash,
        transport_certificate_sha256=verified.transport_certificate_sha256,
    )

    with pytest.raises(GraphThreadBindingError, match="exact Graph registry profile"):
        await gateway.admit(
            command=command,
            verified_invocation=forged,
            expected_thread=_thread(),
        )

    assert "repo:registry" in pool.events
    assert "repo:thread" not in pool.events
    assert "repo:command+nonce" not in pool.events
    assert "transaction:rollback" in pool.events


@pytest.mark.asyncio
async def test_nonce_replay_rolls_back_the_whole_admission_transaction() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = _gateway(pool, _Ledger(pool.events, replay=True), audit)
    command = _command()

    with pytest.raises(GraphNonceReplayError):
        await gateway.admit(
            command=command,
            verified_invocation=_verified(command),
            expected_thread=_thread(),
        )

    assert "transaction:rollback" in pool.events
    assert "transaction:commit" not in pool.events
    assert audit.events[-1].code == "GRAPH_INVOCATION_NONCE_REPLAY"


@pytest.mark.asyncio
async def test_pre_database_authorization_rejection_is_security_audited() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        registry=_Registry(pool.events),  # type: ignore[arg-type]
        ledger=_Ledger(pool.events),  # type: ignore[arg-type]
        input_authorizer=_RejectingInputAuthorizer(),
        audit_sink=audit,
    )
    command = _command()

    with pytest.raises(GraphThreadBindingError, match="IMMUTABLE_INPUT_REJECTED"):
        await gateway.admit(
            command=command,
            verified_invocation=_verified(command),
            expected_thread=_thread(),
        )

    assert pool.events == []
    assert audit.events[-1].event_type == "graph.command.rejected"
    assert audit.events[-1].code == "GRAPH_THREAD_BINDING_CONFLICT"


@pytest.mark.asyncio
async def test_retired_version_and_thread_can_replay_an_existing_cached_result() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events, ThreadLifecycle.RETIRED),  # type: ignore[arg-type]
        registry=_Registry(pool.events, RegistryState.RETIRED),  # type: ignore[arg-type]
        ledger=_Ledger(  # type: ignore[arg-type]
            pool.events,
            status=CommandStatus.COMPLETED,
            created=False,
        ),
        input_authorizer=_InputAuthorizer(pool.events),
        audit_sink=audit,
    )
    command = _command()

    admission = await gateway.admit(
        command=command,
        verified_invocation=_verified(command),
        expected_thread=_thread(),
    )

    assert admission.action is AdmissionAction.RETURN_CACHED
    assert admission.registry.state is RegistryState.RETIRED
    assert "transaction:commit" in pool.events


@pytest.mark.asyncio
async def test_retired_version_rejects_new_command_and_rolls_back_registration() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        registry=_Registry(pool.events, RegistryState.RETIRED),  # type: ignore[arg-type]
        ledger=_Ledger(pool.events, created=True),  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer(pool.events),
        audit_sink=audit,
    )
    command = _command()

    with pytest.raises(GraphVersionUnavailableError):
        await gateway.admit(
            command=command,
            verified_invocation=_verified(command),
            expected_thread=_thread(),
        )

    assert "repo:command+nonce" in pool.events
    assert "transaction:rollback" in pool.events
    assert "transaction:commit" not in pool.events


@pytest.mark.asyncio
async def test_jwks_retention_port_reads_nonterminal_command_kids() -> None:
    pool = _Pool()
    audit = _Audit()
    gateway = _gateway(pool, _Ledger(pool.events), audit)

    kids = await gateway.referenced_verification_key_ids()

    assert kids == frozenset({"java-key-1", "java-key-previous"})
    assert pool.events == [
        "connection:enter",
        "transaction:enter",
        "repo:kids",
        "transaction:commit",
        "connection:exit",
    ]


@pytest.mark.asyncio
async def test_disabled_gateway_has_no_database_or_unsigned_fallback() -> None:
    gateway = GraphCommandGateway(mode=GraphGatewayMode.DISABLED, pool=None)
    command = _command()

    with pytest.raises(GraphGatewayDisabledError):
        await gateway.admit(
            command=command,
            verified_invocation=_verified(command),
            expected_thread=_thread(),
        )


def _reconciliation_result(binding: CommandBinding) -> ResultRecord:
    payload: dict[str, Any] = {
        "schema_version": "room-graph-result.v1",
        "command_id": binding.command_id,
        "logical_run_id": "run-1",
        "attempt_id": "attempt-1",
        "graph_key": binding.graph_key,
        "graph_version": binding.graph_version,
        "checkpoint_id": "checkpoint-1",
        "cognitive_revision": 1,
        "status": "COMPLETED",
        "public_event_proposals": [],
        "artifact_operations": [],
        "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
        "execution_metadata": {
            "prompt_version": binding.profile.prompt_version,
            "model_profile_id": binding.profile.model_profile_id,
            "schema_version": binding.profile.output_schema_version,
            "policy_version": binding.profile.policy_version,
            "guardrail_version": binding.profile.guardrail_version,
        },
    }
    payload["output_hash"] = canonical_sha256(payload)
    return ResultRecord(
        result_id="result-1",
        thread_id=binding.thread_id,
        command_id=binding.command_id,
        request_hash=binding.request_hash,
        result_schema_version="room-graph-result.v1",
        checkpoint_ns="",
        checkpoint_id="checkpoint-1",
        cognitive_revision=1,
        terminal_status="COMPLETED",
        result_json=payload,
        result_ref=f"urn:after-sale-flow:graph-result:{payload['output_hash']}",
        result_hash=payload["output_hash"],
        usage_json={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
    )


class _ReconcileRecovery:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.calls = 0

    async def reconcile_terminal(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        owner_id: str,
    ) -> tuple[CommandRecord, ResultRecord]:
        self.events.append("recovery:terminal")
        self.calls += 1
        result = _reconciliation_result(binding)
        return (
            CommandRecord(
                binding=binding,
                status=CommandStatus.COMPLETED,
                attempt_count=1,
                fencing_token=2,
                start_checkpoint_ns="",
                start_checkpoint_id="checkpoint-start",
                committed_checkpoint_ns=result.checkpoint_ns,
                committed_checkpoint_id=result.checkpoint_id,
                result_ref=result.result_ref,
                result_hash=result.result_hash,
                error_code=None,
                error_classification=None,
                revision=2,
            ),
            result,
        )


class _MissingExistingLedger(_Ledger):
    async def consume_nonce_for_existing(self, connection: Any, **kwargs: Any) -> CommandRecord:
        self.events.append("repo:existing-command-missing")
        raise GraphCommandNotFoundError()


def _reconciliation_gateway(
    *,
    status: CommandStatus,
    registry_state: RegistryState = RegistryState.SHADOW,
    ledger: _Ledger | None = None,
    mode: GraphGatewayMode = GraphGatewayMode.SHADOW,
    registry_record: RegistryRecord | None = None,
) -> tuple[GraphCommandGateway, _Pool, _Audit, _ReconcileRecovery]:
    pool = _Pool()
    audit = _Audit()
    selected_ledger = ledger or _Ledger(pool.events, status=status, created=False)
    gateway = GraphCommandGateway(
        mode=mode,
        pool=pool,
        threads=_Threads(pool.events, ThreadLifecycle.RETIRED),  # type: ignore[arg-type]
        registry=_Registry(pool.events, registry_state, registry_record),  # type: ignore[arg-type]
        ledger=selected_ledger,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer(pool.events),
        audit_sink=audit,
    )
    recovery = _ReconcileRecovery(pool.events)
    gateway._recovery = recovery  # type: ignore[assignment]
    return gateway, pool, audit, recovery


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "disposition"),
    [
        (CommandStatus.COMPLETED, ReconciliationDisposition.RETURN_CACHED),
        (
            CommandStatus.RESULT_CHECKPOINTED,
            ReconciliationDisposition.RECONCILED_TERMINAL,
        ),
    ],
)
async def test_reconcile_only_returns_exact_existing_result_without_execution_path(
    status: CommandStatus,
    disposition: ReconciliationDisposition,
) -> None:
    gateway, pool, audit, recovery = _reconciliation_gateway(
        status=status,
        registry_state=RegistryState.RETIRED,
    )
    command = _command()

    reconciled = await gateway.reconcile_only(
        command=command,
        verified_reconciliation=_verified_reconciliation(command),
        expected_thread=_thread(),
        owner_id="worker-reconcile-1",
    )

    assert reconciled.disposition is disposition
    assert reconciled.command.status is CommandStatus.COMPLETED
    assert reconciled.result.result_hash == reconciled.command.result_hash
    assert reconciled.registry.state is RegistryState.RETIRED
    assert recovery.calls == 1
    assert "input:authorized" not in pool.events
    assert pool.events == [
        "connection:enter",
        "transaction:enter",
        "repo:thread-binding",
        "repo:registry-restore",
        "repo:existing-command+nonce",
        "recovery:terminal",
        "transaction:commit",
        "connection:exit",
    ]
    assert audit.events[-1].event_type == "graph.command.result_reconciled"
    assert audit.events[-1].code == disposition.value


@pytest.mark.asyncio
async def test_reconcile_only_rejects_registry_profile_drift_before_nonce_consumption() -> None:
    gateway, pool, _, recovery = _reconciliation_gateway(
        status=CommandStatus.COMPLETED,
    )
    command = _command()
    verified = _verified_reconciliation(command)
    forged = VerifiedReconciliation(
        claims=verified.claims.model_copy(
            update={"profile_bindings_hash": "0" * 64}
        ),
        key_id=verified.key_id,
        request_hash=verified.request_hash,
        transport_certificate_sha256=verified.transport_certificate_sha256,
    )

    with pytest.raises(GraphThreadBindingError, match="exact Graph registry profile"):
        await gateway.reconcile_only(
            command=command,
            verified_reconciliation=forged,
            expected_thread=_thread(),
            owner_id="worker-reconcile-1",
        )

    assert "repo:registry-restore" in pool.events
    assert "repo:existing-command+nonce" not in pool.events
    assert "transaction:rollback" in pool.events
    assert recovery.calls == 0


@pytest.mark.asyncio
async def test_reconcile_only_uses_the_durable_shadow_binding_lane_not_gateway_mode() -> None:
    command = _target_e2e_command()
    gateway, pool, _, recovery = _reconciliation_gateway(
        status=CommandStatus.COMPLETED,
        registry_state=RegistryState.ACTIVE_CANDIDATE,
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        registry_record=_target_e2e_registry(),
    )

    with pytest.raises(GraphVersionBindingError):
        await gateway.reconcile_only(
            command=command,
            verified_reconciliation=_verified_reconciliation(command),
            expected_thread=_target_e2e_thread(command),
            owner_id="worker-reconcile-1",
        )

    assert "repo:registry-restore" in pool.events
    assert "repo:existing-command+nonce" not in pool.events
    assert recovery.calls == 0


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "error_type"),
    [
        (CommandStatus.REGISTERED, GraphResultNotCommittedError),
        (CommandStatus.EXECUTING, GraphNewAgentAttemptRequiredError),
        (CommandStatus.CANCELLED, GraphCommandCancelledError),
        (CommandStatus.ABORTED, GraphCommandAbortedError),
    ],
)
async def test_reconcile_only_rejects_every_non_result_state_before_recovery(
    status: CommandStatus,
    error_type: type[Exception],
) -> None:
    gateway, pool, audit, recovery = _reconciliation_gateway(status=status)
    command = _command()

    with pytest.raises(error_type):
        await gateway.reconcile_only(
            command=command,
            verified_reconciliation=_verified_reconciliation(command),
            expected_thread=_thread(),
            owner_id="worker-reconcile-1",
        )

    assert recovery.calls == 0
    assert "transaction:rollback" in pool.events
    assert audit.events[-1].event_type == "graph.command.result_rejected"


@pytest.mark.asyncio
async def test_reconcile_only_missing_command_never_registers_or_executes() -> None:
    pool = _Pool()
    ledger = _MissingExistingLedger(pool.events, status=CommandStatus.COMPLETED)
    gateway, pool, audit, recovery = _reconciliation_gateway(
        status=CommandStatus.COMPLETED,
        ledger=ledger,
    )
    ledger.events = pool.events
    command = _command()

    with pytest.raises(GraphCommandNotFoundError):
        await gateway.reconcile_only(
            command=command,
            verified_reconciliation=_verified_reconciliation(command),
            expected_thread=_thread(),
            owner_id="worker-reconcile-1",
        )

    assert recovery.calls == 0
    assert "repo:existing-command-missing" in pool.events
    assert "repo:command+nonce" not in pool.events
    assert audit.events[-1].code == "GRAPH_COMMAND_NOT_FOUND"


@pytest.mark.asyncio
async def test_reconcile_only_rejects_execution_credential_before_database_access() -> None:
    gateway, pool, audit, recovery = _reconciliation_gateway(status=CommandStatus.COMPLETED)
    command = _command()

    with pytest.raises(GraphThreadBindingError, match="credential type"):
        await gateway.reconcile_only(
            command=command,
            verified_reconciliation=_verified(command),  # type: ignore[arg-type]
            expected_thread=_thread(),
            owner_id="worker-reconcile-1",
        )

    assert pool.events == []
    assert recovery.calls == 0
    assert audit.events[-1].code == "GRAPH_THREAD_BINDING_CONFLICT"


@pytest.mark.asyncio
async def test_reconcile_terminal_signals_after_commit_before_audit_delivery() -> None:
    pool = _Pool()
    audit_entered = asyncio.Event()
    release_audit = asyncio.Event()
    durable_terminal_signal = asyncio.Event()

    class BlockingAudit:
        async def emit(self, event: GatewayAuditEvent) -> None:
            assert event.event_type == "graph.command.reconciled"
            audit_entered.set()
            await release_audit.wait()

    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        input_authorizer=_InputAuthorizer(pool.events),
        audit_sink=BlockingAudit(),
    )
    gateway._recovery = _ReconcileRecovery(pool.events)  # type: ignore[assignment]
    execution = _execution()
    task = asyncio.create_task(
        gateway.reconcile_terminal(
            execution.admission,
            owner_id=execution.fence.owner_id,
            durable_terminal_signal=durable_terminal_signal,
        )
    )

    await asyncio.wait_for(audit_entered.wait(), timeout=0.1)
    assert "transaction:commit" in pool.events
    assert durable_terminal_signal.is_set()
    assert task.done() is False

    release_audit.set()
    _, result = await task
    assert result.result_hash


def _execution() -> GatewayExecution:
    command = _command()
    registry = _registry()
    binding = CommandBinding.from_command(
        command,
        tool_policy_version=registry.binding.tool_policy_version,
    )
    record = CommandRecord(
        binding=binding,
        status=CommandStatus.EXECUTING,
        attempt_count=1,
        fencing_token=1,
        start_checkpoint_ns=None,
        start_checkpoint_id=None,
        committed_checkpoint_ns=None,
        committed_checkpoint_id=None,
        result_ref=None,
        result_hash=None,
        error_code=None,
        error_classification=None,
        revision=1,
    )
    admission = GatewayAdmission(
        command=command,
        binding=binding,
        thread=_thread(),
        registry=registry,
        record=record,
        action=AdmissionAction.OBSERVE_OR_TAKEOVER,
        created=False,
    )
    attempt = AttemptRecord(
        attempt_id=command.attempt_id,
        thread_id=command.thread_id,
        command_id=command.command_id,
        attempt_no=1,
        owner_id="worker-1",
        fencing_token=1,
        status=AttemptStatus.EXECUTING,
        provider_call_count=1,
        error_code=None,
        error_classification=None,
    )
    lease = LeaseRecord(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-1",
        fencing_token=1,
        lease_expires_at=NOW + timedelta(seconds=30),
        acquired_at=NOW,
        renewed_at=NOW,
        released_at=None,
        cancelled_at=None,
        cancelled_by_command_id=None,
        revision=0,
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-1",
        fencing_token=1,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    return GatewayExecution(admission, attempt, lease, fence)


def _parallel_execution(*, provider_call_count: int = 0) -> GatewayExecution:
    payload = _target_e2e_command().model_dump(
        mode="json",
        exclude={"request_hash"},
        exclude_none=True,
    )
    payload["room_id"] = "ROOM_PARALLEL_1"
    payload["event_ref"] = {
        "artifact_id": "intake.event.parallel-1",
        "schema_version": "intake-turn-event.v2",
        "uri": "urn:intake:event:parallel-1",
        "sha256": "7" * 64,
        "size_bytes": 256,
    }
    payload["invocation_context"] = {
        **payload["invocation_context"],
        "agent_profile_id": PARALLEL_INTAKE_AGENT_PROFILE_ID,
        "output_schema_version": PARALLEL_INTAKE_OUTPUT_SCHEMA,
    }
    payload["retry_budget"] = {
        **payload["retry_budget"],
        "provider_attempts_remaining": 3,
    }
    payload["request_hash"] = canonical_sha256(payload)
    command = RoomGraphCommand.model_validate(payload)
    registry = _target_e2e_registry()
    binding = CommandBinding.from_command(
        command,
        tool_policy_version=registry.binding.tool_policy_version,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=f"p9act.v1.{'8' * 32}",
        room_fencing_token=1,
        command_hash="9" * 64,
        command_envelope_hash="a" * 64,
    )
    authority = _target_e2e_authority(
        command,
        registry,
        activation_id=binding.activation_id,
    )
    record = CommandRecord(
        binding=binding,
        status=CommandStatus.EXECUTING,
        attempt_count=1,
        fencing_token=1,
        start_checkpoint_ns=None,
        start_checkpoint_id=None,
        committed_checkpoint_ns=None,
        committed_checkpoint_id=None,
        result_ref=None,
        result_hash=None,
        error_code=None,
        error_classification=None,
        revision=1,
    )
    admission = GatewayAdmission(
        command=command,
        binding=binding,
        thread=_target_e2e_thread(command),
        registry=registry,
        record=record,
        action=AdmissionAction.OBSERVE_OR_TAKEOVER,
        created=False,
        candidate_authority=authority,
    )
    attempt = AttemptRecord(
        attempt_id=command.attempt_id,
        thread_id=command.thread_id,
        command_id=command.command_id,
        attempt_no=1,
        owner_id="worker-1",
        fencing_token=1,
        status=AttemptStatus.EXECUTING,
        provider_call_count=provider_call_count,
        error_code=None,
        error_classification=None,
    )
    lease = LeaseRecord(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-1",
        fencing_token=1,
        lease_expires_at=NOW + timedelta(seconds=30),
        acquired_at=NOW,
        renewed_at=NOW,
        released_at=None,
        cancelled_at=None,
        cancelled_by_command_id=None,
        revision=0,
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-1",
        fencing_token=1,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=binding.activation_id,
        room_fencing_token=binding.room_fencing_token,
        command_hash=binding.command_hash,
        command_envelope_hash=binding.command_envelope_hash,
        environment_id=authority.context.environmentId,
        environment_generation=authority.context.environmentGeneration,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=command.room_type,
        binding_hash=registry.binding.binding_hash,
        code_build_id=registry.binding.code_build_id,
    )
    return GatewayExecution(admission, attempt, lease, fence)


def _parallel_receipt(execution: GatewayExecution) -> dict[str, Any]:
    document: dict[str, Any] = {
        "schema_version": "intake.parallel-admission-receipt.v1",
        "request_hash": execution.admission.binding.request_hash,
        "frame_set_id": "IPFS_PARALLEL_1",
        "run_id": execution.admission.command.logical_run_id,
        "attempt_id": execution.attempt.attempt_id,
        "java_receipt_id": "FRAME_SET_RECEIPT_V4_1",
        "authority_sha256": "b" * 64,
        "lanes": [
            {
                "frame_type": frame_type,
                "generation": 1,
                "frame_id": f"frame.{frame_type.lower()}.1",
                "slot_state": "ADMITTED",
                "action": "RUN_CURRENT",
                "next_local_index": 0,
                "slot_version": 0,
                "result_id": None,
                "result_sha256": None,
                "public_projection_sha256": None,
                "predecessor_failure_code": None,
            }
            for frame_type in (
                "DIALOGUE_FRAME",
                "DOSSIER_FRAME",
                "QUALITY_FRAME",
            )
        ],
    }
    document["receipt_sha256"] = canonical_sha256(document)
    return document


class _Executor:
    def __init__(self, events: list[AgentStreamEvent]) -> None:
        self.events = events

    async def stream(self, execution: GatewayExecution):
        for event in self.events:
            yield event


class _StreamGateway(GraphCommandGateway):
    def __init__(self, *, terminal_result_barrier: Any | None = None) -> None:
        super().__init__(
            mode=GraphGatewayMode.SHADOW,
            pool=object(),
            input_authorizer=_InputAuthorizer([]),
            terminal_result_barrier=terminal_result_barrier,
        )
        self.reconciled = False
        self.finished = False

    async def reconcile_terminal(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
        durable_terminal_signal: asyncio.Event | None = None,
    ):
        self.reconciled = True
        if durable_terminal_signal is not None:
            durable_terminal_signal.set()
        result = ResultRecord(
            result_id="result-1",
            thread_id=admission.binding.thread_id,
            command_id=admission.binding.command_id,
            request_hash=admission.binding.request_hash,
            result_schema_version="room-graph-result.v1",
            checkpoint_ns="",
            checkpoint_id="checkpoint-1",
            cognitive_revision=1,
            terminal_status="COMPLETED",
            result_json={"output_hash": "f" * 64},
            result_ref="s3://graph-results/result-1.json",
            result_hash="f" * 64,
            usage_json={},
        )
        return admission.record, result

    async def finish_execution_attempt(self, execution: GatewayExecution, **kwargs: Any):
        self.finished = True
        return execution


def _event(sequence: int, event_type: str, payload: dict[str, Any]) -> AgentStreamEvent:
    return AgentStreamEvent.model_validate(
        {
            "schema_version": "agent-stream.v3",
            "run_id": "run-1",
            "attempt_id": "attempt-1",
            "sequence_no": sequence,
            "event_type": event_type,
            "audience": "USER",
            "occurred_at": NOW,
            "payload": payload,
        }
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("event_type", "payload"),
    [
        (
            "public_frame_start",
            {
                "frame_id": "frame-1",
                "frame_sequence": 1,
                "frame_type": "ROOM_WELCOME",
                "public_header": {
                    "frame_sequence": 1,
                    "frame_type": "ROOM_WELCOME",
                },
            },
        ),
        (
            "public_text_delta",
            {
                "frame_id": "frame-1",
                "frame_sequence": 1,
                "delta_index": 0,
                "delta": "欢迎进入证据室",
            },
        ),
        (
            "active_frame_snapshot",
            {
                "frame_id": "frame-1",
                "frame_sequence": 1,
                "delta_index": 0,
                "public_text": "欢迎进入证据室",
            },
        ),
        (
            "public_frame_committed",
            {
                "frame_id": "frame-1",
                "frame_sequence": 1,
                "durable_cursor": "v3:attempt-1:FRAME:1",
                "header_sha256": "a" * 64,
                "public_text_sha256": "b" * 64,
                "frame_sha256": "c" * 64,
                "public_text_chars": 8,
            },
        ),
        (
            "public_frame_interrupted",
            {
                "frame_id": "frame-1",
                "frame_sequence": 1,
                "durable_cursor": "v3:attempt-1:INTERRUPTED:1",
                "reason_code": "PROVIDER_INTERRUPTED",
                "public_text": "欢迎进入证据室",
            },
        ),
    ],
)
async def test_stream_accepts_contract_authorized_v3_frame_payloads(
    event_type: str,
    payload: dict[str, Any],
) -> None:
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "evidence.start"}),
            _event(1, event_type, payload),
            _event(
                2,
                "error",
                {"error_code": "TEST_TERMINAL", "retryable": False},
            ),
        ]
    )

    events = [
        event
        async for event in gateway.execute_stream(
            execution=_execution(),
            executor=executor,
        )
    ]

    assert [event.event_type for event in events] == [
        "attempt_started",
        event_type,
        "error",
    ]


@pytest.mark.asyncio
async def test_execution_attempt_must_match_signed_command_before_database_access() -> None:
    with pytest.raises(GraphContractError, match="signed command"):
        await _StreamGateway().acquire_execution(
            _execution().admission,
            owner_id="worker-1",
            attempt_id="forged-attempt",
        )


class _TakeoverLedger:
    def __init__(self, execution: GatewayExecution) -> None:
        self.execution = execution
        self.begin_called = False

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return self.execution.admission.record

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> AttemptRecord:
        return self.execution.attempt

    async def begin_attempt(self, connection: Any, **kwargs: Any) -> None:
        self.begin_called = True
        raise AssertionError("takeover must not allocate a second public attempt")


class _TakeoverLeases:
    def __init__(self, execution: GatewayExecution) -> None:
        self.execution = execution
        self.called = False

    async def acquire(self, connection: Any, **kwargs: Any) -> LeaseAcquisition:
        self.called = True
        return LeaseAcquisition(
            LeaseAcquisitionKind.TAKEOVER,
            replace(
                self.execution.lease,
                owner_id="worker-2",
                fencing_token=2,
                revision=1,
            ),
            LeaseDisplacement(
                command_id=self.execution.lease.command_id,
                owner_id=self.execution.lease.owner_id,
                fencing_token=self.execution.lease.fencing_token,
            ),
        )


class _FinishLedger:
    def __init__(self, events: list[str]) -> None:
        self.events = events

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        self.events.append("command_locked")
        return _execution().admission.record

    async def latest_attempt(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> AttemptRecord:
        return _execution().attempt

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def finish_attempt(
        self,
        connection: Any,
        attempt: AttemptRecord,
        **kwargs: Any,
    ) -> AttemptRecord:
        self.events.append("attempt_finished")
        return replace(
            attempt,
            status=kwargs["status"],
            error_code=kwargs["error_code"],
            error_classification=kwargs["error_classification"],
        )

    async def terminate(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        status: CommandStatus,
        error_code: str,
        error_classification: str,
    ) -> CommandRecord:
        self.events.append("command_terminated")
        return replace(
            _execution().admission.record,
            status=status,
            error_code=error_code,
            error_classification=error_classification,
        )


class _ReleaseLeases:
    def __init__(self, events: list[str]) -> None:
        self.called = False
        self.events = events

    async def cancel(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        self.called = True
        self.events.append("lease_cancelled")
        return replace(
            _execution().lease,
            fencing_token=2,
            cancelled_at=NOW,
            cancelled_by_command_id=kwargs["cancellation_command_id"],
            revision=1,
        )


@pytest.mark.asyncio
async def test_fence_takeover_cannot_reuse_an_existing_public_agent_attempt() -> None:
    execution = _execution()
    pool = _Pool()
    ledger = _TakeoverLedger(execution)
    leases = _TakeoverLeases(execution)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),
        ledger=ledger,  # type: ignore[arg-type]
        leases=leases,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    with pytest.raises(GraphNewAgentAttemptRequiredError):
        await gateway.acquire_execution(
            execution.admission,
            owner_id="worker-2",
            attempt_id=execution.admission.command.attempt_id,
        )

    assert ledger.begin_called is False
    assert leases.called is True
    assert "transaction:rollback" in pool.events
    assert "transaction:commit" not in pool.events


class _DisplacedCommandLedger:
    def __init__(self, admission: GatewayAdmission, old_status: CommandStatus) -> None:
        self.new_record = admission.record
        old_binding = replace(admission.binding, command_id="command-old")
        self.old_record = replace(
            admission.record,
            binding=old_binding,
            status=old_status,
            attempt_count=1,
            fencing_token=1,
        )
        self.old_attempt = AttemptRecord(
            attempt_id="attempt-old",
            thread_id=admission.binding.thread_id,
            command_id="command-old",
            attempt_no=1,
            owner_id="worker-old",
            fencing_token=1,
            status=AttemptStatus.EXECUTING,
            provider_call_count=1,
            error_code=None,
            error_classification=None,
        )
        self.events: list[str] = []

    async def load(
        self,
        connection: Any,
        *,
        command_id: str,
        **kwargs: Any,
    ) -> CommandRecord:
        return self.old_record if command_id == "command-old" else self.new_record

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def latest_attempt(
        self,
        connection: Any,
        *,
        command_id: str,
        **kwargs: Any,
    ) -> AttemptRecord | None:
        return self.old_attempt if command_id == "command-old" else None

    async def finish_attempt(
        self,
        connection: Any,
        attempt: AttemptRecord,
        **kwargs: Any,
    ) -> AttemptRecord:
        self.events.append("old_attempt_lease_lost")
        assert attempt == self.old_attempt
        assert kwargs["status"] is AttemptStatus.LEASE_LOST
        return replace(attempt, status=AttemptStatus.LEASE_LOST)

    async def terminate(self, connection: Any, **kwargs: Any) -> CommandRecord:
        self.events.append("old_command_aborted")
        assert kwargs["binding"] == self.old_record.binding
        assert kwargs["status"] is CommandStatus.ABORTED
        return replace(self.old_record, status=CommandStatus.ABORTED)

    async def begin_attempt(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        attempt_id: str,
        owner_id: str,
        fencing_token: int,
    ) -> tuple[CommandRecord, AttemptRecord]:
        self.events.append("new_attempt_started")
        command = replace(
            self.new_record,
            status=CommandStatus.EXECUTING,
            attempt_count=1,
            fencing_token=fencing_token,
        )
        return command, AttemptRecord(
            attempt_id=attempt_id,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            attempt_no=1,
            owner_id=owner_id,
            fencing_token=fencing_token,
            status=AttemptStatus.EXECUTING,
            provider_call_count=0,
            error_code=None,
            error_classification=None,
        )


class _DisplacingLeases:
    def __init__(self, admission: GatewayAdmission) -> None:
        self.admission = admission

    async def acquire(self, connection: Any, **kwargs: Any) -> LeaseAcquisition:
        return LeaseAcquisition(
            LeaseAcquisitionKind.TAKEOVER,
            replace(
                _execution().lease,
                command_id=self.admission.binding.command_id,
                owner_id="worker-new",
                fencing_token=2,
                revision=1,
            ),
            LeaseDisplacement(
                command_id="command-old",
                owner_id="worker-old",
                fencing_token=1,
            ),
        )

    async def renew(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        assert kwargs == {
            "thread_id": self.admission.binding.thread_id,
            "command_id": self.admission.binding.command_id,
            "owner_id": "worker-new",
            "fencing_token": 2,
            "command_deadline_at": self.admission.command.deadline_at,
        }
        return replace(
            _execution().lease,
            command_id=self.admission.binding.command_id,
            owner_id="worker-new",
            fencing_token=2,
            lease_expires_at=NOW + timedelta(seconds=31),
            renewed_at=NOW + timedelta(seconds=1),
            revision=2,
        )


def _registered_admission() -> GatewayAdmission:
    execution = _execution()
    return replace(
        execution.admission,
        record=replace(
            execution.admission.record,
            status=CommandStatus.REGISTERED,
            attempt_count=0,
            fencing_token=None,
        ),
        action=AdmissionAction.ACQUIRE,
        created=True,
    )


class _BeginningAttemptLedger:
    def __init__(self, admission: GatewayAdmission, ordering: list[str]) -> None:
        self.admission = admission
        self.ordering = ordering

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        assert kwargs == {
            "thread_id": self.admission.binding.thread_id,
            "command_id": self.admission.binding.command_id,
        }
        return self.admission.record

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> None:
        assert kwargs == {
            "thread_id": self.admission.binding.thread_id,
            "command_id": self.admission.binding.command_id,
        }
        return None

    async def begin_attempt(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        attempt_id: str,
        owner_id: str,
        fencing_token: int,
    ) -> tuple[CommandRecord, AttemptRecord]:
        self.ordering.append("begin_attempt")
        assert binding == self.admission.binding
        assert attempt_id == self.admission.command.attempt_id
        assert owner_id == "worker-1"
        assert fencing_token == 1
        command = replace(
            self.admission.record,
            status=CommandStatus.EXECUTING,
            attempt_count=1,
            fencing_token=fencing_token,
        )
        return command, AttemptRecord(
            attempt_id=attempt_id,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            attempt_no=1,
            owner_id=owner_id,
            fencing_token=fencing_token,
            status=AttemptStatus.EXECUTING,
            provider_call_count=0,
            error_code=None,
            error_classification=None,
        )


class _FresheningLeases:
    def __init__(
        self,
        admission: GatewayAdmission,
        ordering: list[str],
        *,
        lose_on_renew: bool = False,
    ) -> None:
        self.admission = admission
        self.ordering = ordering
        self.lose_on_renew = lose_on_renew
        self.acquired_lease = LeaseRecord(
            thread_id=admission.binding.thread_id,
            command_id=admission.binding.command_id,
            owner_id="worker-1",
            fencing_token=1,
            lease_expires_at=NOW + timedelta(seconds=30),
            acquired_at=NOW,
            renewed_at=NOW,
            released_at=None,
            cancelled_at=None,
            cancelled_by_command_id=None,
            revision=0,
        )
        self.fresh_lease = replace(
            self.acquired_lease,
            lease_expires_at=NOW + timedelta(seconds=50),
            renewed_at=NOW + timedelta(seconds=20),
            revision=1,
        )

    async def acquire(self, connection: Any, **kwargs: Any) -> LeaseAcquisition:
        self.ordering.append("acquire")
        assert kwargs == {
            "thread_id": self.admission.binding.thread_id,
            "command_id": self.admission.binding.command_id,
            "owner_id": "worker-1",
        }
        return LeaseAcquisition(LeaseAcquisitionKind.FIRST, self.acquired_lease)

    async def renew(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        self.ordering.append("renew")
        assert kwargs == {
            "thread_id": self.admission.binding.thread_id,
            "command_id": self.admission.binding.command_id,
            "owner_id": "worker-1",
            "fencing_token": self.acquired_lease.fencing_token,
            "command_deadline_at": self.admission.command.deadline_at,
        }
        if self.lose_on_renew:
            raise GraphLeaseLostError()
        return self.fresh_lease


@pytest.mark.asyncio
async def test_acquire_execution_renews_after_begin_attempt_and_keeps_fresh_lease() -> None:
    admission = _registered_admission()
    pool = _Pool()
    ordering: list[str] = []
    leases = _FresheningLeases(admission, ordering)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        ledger=_BeginningAttemptLedger(admission, ordering),  # type: ignore[arg-type]
        leases=leases,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    execution = await gateway.acquire_execution(
        admission,
        owner_id="worker-1",
        attempt_id=admission.command.attempt_id,
    )

    assert ordering == ["acquire", "begin_attempt", "renew"]
    assert execution.lease is leases.fresh_lease
    assert execution.fence.fencing_token == leases.fresh_lease.fencing_token
    assert gateway._latest_leases == {
        (
            admission.binding.thread_id,
            admission.binding.command_id,
            "worker-1",
            leases.fresh_lease.fencing_token,
        ): leases.fresh_lease
    }
    assert "transaction:commit" in pool.events


@pytest.mark.asyncio
async def test_acquire_execution_rolls_back_when_post_begin_renew_loses_lease() -> None:
    admission = _registered_admission()
    pool = _Pool()
    ordering: list[str] = []
    leases = _FresheningLeases(admission, ordering, lose_on_renew=True)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        ledger=_BeginningAttemptLedger(admission, ordering),  # type: ignore[arg-type]
        leases=leases,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    with pytest.raises(GraphLeaseLostError):
        await gateway.acquire_execution(
            admission,
            owner_id="worker-1",
            attempt_id=admission.command.attempt_id,
        )

    assert ordering == ["acquire", "begin_attempt", "renew"]
    assert gateway._latest_leases == {}
    assert "transaction:rollback" in pool.events
    assert "transaction:commit" not in pool.events


@pytest.mark.asyncio
async def test_new_command_takeover_resolves_expired_execution_in_same_transaction() -> None:
    admission = _registered_admission()
    pool = _Pool()
    ledger = _DisplacedCommandLedger(admission, CommandStatus.EXECUTING)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
        leases=_DisplacingLeases(admission),  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    execution = await gateway.acquire_execution(
        admission,
        owner_id="worker-new",
        attempt_id=admission.command.attempt_id,
    )

    assert execution.fence.fencing_token == 2
    assert ledger.events == [
        "old_attempt_lease_lost",
        "old_command_aborted",
        "new_attempt_started",
    ]
    assert "transaction:commit" in pool.events


@pytest.mark.asyncio
async def test_new_command_takeover_rolls_back_until_old_terminal_reconciles() -> None:
    admission = _registered_admission()
    pool = _Pool()
    ledger = _DisplacedCommandLedger(admission, CommandStatus.RESULT_CHECKPOINTED)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        ledger=ledger,  # type: ignore[arg-type]
        leases=_DisplacingLeases(admission),  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    with pytest.raises(GraphNewAgentAttemptRequiredError, match="must reconcile"):
        await gateway.acquire_execution(
            admission,
            owner_id="worker-new",
            attempt_id=admission.command.attempt_id,
        )

    assert ledger.events == []
    assert "transaction:rollback" in pool.events
    assert "transaction:commit" not in pool.events


@pytest.mark.asyncio
async def test_terminal_attempt_failure_fences_lease_before_command_and_attempt() -> None:
    pool = _Pool()
    ordering: list[str] = []
    leases = _ReleaseLeases(ordering)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=pool,
        ledger=_FinishLedger(ordering),  # type: ignore[arg-type]
        leases=leases,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    finished = await gateway.finish_execution_attempt(
        _execution(),
        status=AttemptStatus.FAILED,
        error_code="PROVIDER_TIMEOUT",
        error_classification="RECOVERABLE_ATTEMPT",
    )

    assert finished.attempt.status is AttemptStatus.FAILED
    assert finished.admission.record.status is CommandStatus.ABORTED
    assert finished.admission.action is AdmissionAction.RETURN_ABORTED
    assert finished.lease.cancelled_at == NOW
    assert finished.lease.fencing_token == 2
    assert leases.called is True
    assert ordering == [
        "lease_cancelled",
        "command_locked",
        "command_terminated",
        "attempt_finished",
    ]
    assert "transaction:commit" in pool.events


def test_cleanup_adopts_exact_parallel_technical_completion() -> None:
    execution = _execution()
    command = replace(
        execution.admission.record,
        status=CommandStatus.TECHNICAL_COMPLETED,
    )
    attempt = replace(execution.attempt, status=AttemptStatus.COMPLETED)

    adopted = GraphCommandGateway._completed_attempt_abort_adoption(  # noqa: SLF001
        execution,
        command=command,
        attempt=attempt,
        status=AttemptStatus.FAILED,
        error_code="CLIENT_DISCONNECTED",
        error_classification="TRANSPORT_FAILURE",
    )

    assert adopted == (command, attempt)


class _ParallelTakeoverLedger:
    def __init__(self, execution: GatewayExecution) -> None:
        self.command = execution.admission.record
        self.attempt = execution.attempt
        receipt = _parallel_receipt(execution)
        self.receipt_execution = ParallelReceiptExecutionRecord.create(
            thread_id=execution.fence.thread_id,
            command_id=execution.fence.command_id,
            request_hash=execution.fence.request_hash,
            attempt_id=execution.attempt.attempt_id,
            frame_set_id=receipt["frame_set_id"],
            receipt_sha256=receipt["receipt_sha256"],
            authority_sha256=receipt["authority_sha256"],
            predecessor_cycle_id=None,
            provider_call_count_at_admission=0,
            owner_id=execution.fence.owner_id,
            fencing_token=execution.fence.fencing_token,
        )
        self.rebind_predecessor: ParallelReceiptExecutionRecord | None = None

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return self.command

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> AttemptRecord:
        return self.attempt

    async def load_latest_parallel_receipt_cycle(self, connection: Any, **kwargs: Any) -> None:
        return None

    async def load_parallel_receipt_cycle(self, connection: Any, **kwargs: Any) -> None:
        return None

    async def load_parallel_receipt_execution(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> ParallelReceiptExecutionRecord:
        assert kwargs["receipt_sha256"] == self.receipt_execution.receipt_sha256
        return self.receipt_execution

    async def rebind_parallel_attempt(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> tuple[CommandRecord, AttemptRecord]:
        assert kwargs["prior_cycle"] is None
        prior_execution = kwargs["prior_execution"]
        assert prior_execution == self.receipt_execution
        receipt_execution = kwargs["receipt_execution"]
        assert receipt_execution.predecessor_execution_id == prior_execution.execution_id
        assert receipt_execution.provider_call_count_at_admission == 0
        self.rebind_predecessor = prior_execution
        self.command = replace(
            self.command,
            fencing_token=kwargs["next_fencing_token"],
            revision=self.command.revision + 1,
        )
        self.attempt = replace(
            self.attempt,
            owner_id=kwargs["next_owner_id"],
            fencing_token=kwargs["next_fencing_token"],
        )
        return self.command, self.attempt


class _InitialParallelReceiptLedger:
    def __init__(self, execution: GatewayExecution) -> None:
        self.command = execution.admission.record
        self.attempt = execution.attempt
        self.stored: ParallelReceiptExecutionRecord | None = None

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return self.command

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> AttemptRecord:
        return self.attempt

    async def store_parallel_receipt_execution(
        self,
        connection: Any,
        *,
        execution_attempt: AttemptRecord,
        receipt_execution: ParallelReceiptExecutionRecord,
    ) -> ParallelReceiptExecutionRecord:
        assert execution_attempt == self.attempt
        self.stored = receipt_execution
        return receipt_execution


class _InitialParallelReceiptLeases:
    def __init__(self, execution: GatewayExecution) -> None:
        self.execution = execution

    async def lock_for_recovery(self, connection: Any, **kwargs: Any) -> LeaseInspection:
        assert kwargs["thread_id"] == self.execution.fence.thread_id
        return LeaseInspection(
            lease=self.execution.lease,
            database_now=self.execution.lease.renewed_at + timedelta(seconds=1),
        )


@pytest.mark.asyncio
async def test_initial_parallel_receipt_accepts_thread_scoped_nonfirst_fence() -> None:
    initial = _parallel_execution(provider_call_count=0)
    execution = GatewayExecution(
        replace(
            initial.admission,
            record=replace(initial.admission.record, fencing_token=2),
        ),
        replace(initial.attempt, fencing_token=2),
        replace(initial.lease, fencing_token=2, revision=1),
        replace(initial.fence, fencing_token=2),
        initial.thread_record,
    )
    pool = _Pool()
    ledger = _InitialParallelReceiptLedger(execution)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
        leases=_InitialParallelReceiptLeases(execution),  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )
    receipt = _parallel_receipt(execution)

    bound = await gateway.bind_parallel_receipt_execution(
        execution,
        frame_set_id=receipt["frame_set_id"],
        receipt_sha256=receipt["receipt_sha256"],
        authority_sha256=receipt["authority_sha256"],
    )

    assert bound == execution
    assert ledger.stored is not None
    assert ledger.stored.fencing_token == 2
    assert ledger.stored.owner_id == execution.fence.owner_id
    assert "transaction:commit" in pool.events


class _ParallelTakeoverLeases:
    def __init__(self, execution: GatewayExecution) -> None:
        self.old = execution.lease
        self.current = replace(
            execution.lease,
            owner_id="worker-2",
            fencing_token=2,
            acquired_at=NOW + timedelta(seconds=31),
            renewed_at=NOW + timedelta(seconds=31),
            lease_expires_at=NOW + timedelta(seconds=60),
            revision=1,
        )

    async def acquire(self, connection: Any, **kwargs: Any) -> LeaseAcquisition:
        return LeaseAcquisition(
            LeaseAcquisitionKind.TAKEOVER,
            self.current,
            LeaseDisplacement(
                command_id=self.old.command_id,
                owner_id=self.old.owner_id,
                fencing_token=self.old.fencing_token,
            ),
        )

    async def renew(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        return self.current


@pytest.mark.asyncio
async def test_parallel_same_receipt_takeover_rebinds_without_provider_replay() -> None:
    original = _parallel_execution(provider_call_count=0)
    receipt = _parallel_receipt(original)
    pool = _Pool()
    ledger = _ParallelTakeoverLedger(original)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
        leases=_ParallelTakeoverLeases(original),  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    resumed = await gateway.resume_parallel_technical_execution(
        original.admission,
        owner_id="worker-2",
        attempt_id=original.attempt.attempt_id,
        frame_set_id=receipt["frame_set_id"],
        receipt_sha256=receipt["receipt_sha256"],
        authority_sha256=receipt["authority_sha256"],
        admission_receipt=receipt,
    )

    assert resumed.attempt.provider_call_count == 0
    assert resumed.fence.fencing_token == 2
    assert resumed.fence.owner_id == "worker-2"
    assert ledger.rebind_predecessor == ledger.receipt_execution
    assert "transaction:commit" in pool.events


@pytest.mark.asyncio
async def test_parallel_same_receipt_takeover_rejects_started_provider_call() -> None:
    original = _parallel_execution(provider_call_count=1)
    receipt = _parallel_receipt(original)
    pool = _Pool()
    ledger = _ParallelTakeoverLedger(original)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        pool=pool,
        threads=_Threads(pool.events),  # type: ignore[arg-type]
        ledger=ledger,  # type: ignore[arg-type]
        leases=_ParallelTakeoverLeases(original),  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    with pytest.raises(
        GraphNewAgentAttemptRequiredError,
        match="began Provider execution",
    ):
        await gateway.resume_parallel_technical_execution(
            original.admission,
            owner_id="worker-2",
            attempt_id=original.attempt.attempt_id,
            frame_set_id=receipt["frame_set_id"],
            receipt_sha256=receipt["receipt_sha256"],
            authority_sha256=receipt["authority_sha256"],
            admission_receipt=receipt,
        )

    assert ledger.rebind_predecessor is None
    assert "transaction:rollback" in pool.events


class _ParallelCompletionLedger:
    def __init__(self, execution: GatewayExecution) -> None:
        self.command = execution.admission.record
        self.attempt = execution.attempt
        self.completion: TechnicalCompletionRecord | None = None

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return self.command

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        assert actual == expected

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> AttemptRecord:
        return self.attempt

    async def load_technical_completion(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> TechnicalCompletionRecord:
        assert self.completion is not None
        return self.completion

    async def store_technical_completion(
        self,
        connection: Any,
        *,
        execution_attempt: AttemptRecord,
        completion: TechnicalCompletionRecord,
    ) -> TechnicalCompletionRecord:
        assert execution_attempt == self.attempt
        self.completion = completion
        return completion

    async def complete_technical_attempt(
        self,
        connection: Any,
        attempt: AttemptRecord,
    ) -> AttemptRecord:
        self.attempt = replace(attempt, status=AttemptStatus.COMPLETED)
        return self.attempt

    async def complete_technical_command(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> CommandRecord:
        self.command = replace(
            self.command,
            status=CommandStatus.TECHNICAL_COMPLETED,
            revision=self.command.revision + 1,
        )
        return self.command


class _ParallelCompletionLeases:
    def __init__(self, execution: GatewayExecution) -> None:
        self.lease = execution.lease

    async def lock_for_recovery(self, connection: Any, **kwargs: Any) -> LeaseInspection:
        return LeaseInspection(self.lease, NOW + timedelta(seconds=1))

    async def release(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        self.lease = replace(
            self.lease,
            released_at=NOW + timedelta(seconds=1),
            revision=self.lease.revision + 1,
        )
        return self.lease


def _parallel_completion(execution: GatewayExecution) -> TechnicalCompletionRecord:
    completion_id = "parallel-technical-completion-1"
    schema_version = "intake-parallel-technical-completion.v2"
    document: dict[str, Any] = {
        "completion_id": completion_id,
        "thread_id": execution.fence.thread_id,
        "command_id": execution.fence.command_id,
        "request_hash": execution.fence.request_hash,
        "attempt_id": execution.attempt.attempt_id,
        "fencing_token": execution.fence.fencing_token,
        "schema_version": schema_version,
        "sealed_frame_types": [
            "DIALOGUE_FRAME",
            "DOSSIER_FRAME",
            "QUALITY_FRAME",
        ],
    }
    document["completion_hash"] = canonical_sha256(document)
    return TechnicalCompletionRecord(
        completion_id=completion_id,
        thread_id=execution.fence.thread_id,
        command_id=execution.fence.command_id,
        request_hash=execution.fence.request_hash,
        attempt_id=execution.attempt.attempt_id,
        fencing_token=execution.fence.fencing_token,
        completion_schema_version=schema_version,
        completion_json=document,
        completion_hash=document["completion_hash"],
    )


@pytest.mark.asyncio
async def test_parallel_technical_completion_adopts_commit_response_loss(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    execution = _parallel_execution(provider_call_count=3)
    ledger = _ParallelCompletionLedger(execution)
    leases = _ParallelCompletionLeases(execution)
    calls = 0

    async def ambiguous_transaction(pool: Any, **kwargs: Any) -> None:
        nonlocal calls
        assert pool is gateway._pool
        await kwargs["operation"](object())
        calls += 1
        if calls == 1:
            raise RuntimeError("commit response lost")

    monkeypatch.setattr(
        "app.graph_runtime.gateway.run_postgres_transaction",
        ambiguous_transaction,
    )
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        pool=object(),
        ledger=ledger,  # type: ignore[arg-type]
        leases=leases,  # type: ignore[arg-type]
        input_authorizer=_InputAuthorizer([]),
    )

    completed = await gateway.complete_technical_execution(
        execution,
        completion=_parallel_completion(execution),
    )

    assert calls == 2
    assert completed.admission.record.status is CommandStatus.TECHNICAL_COMPLETED
    assert completed.attempt.status is AttemptStatus.COMPLETED
    assert completed.lease.released_at == NOW + timedelta(seconds=1)


@pytest.mark.asyncio
async def test_stream_reconciles_durable_result_before_yielding_final() -> None:
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "final",
                {
                    "final_result_ref": "s3://graph-results/result-1.json",
                    "final_result_hash": "f" * 64,
                },
            ),
        ]
    )

    events = [
        event async for event in gateway.execute_stream(execution=_execution(), executor=executor)
    ]

    assert [event.event_type for event in events] == ["attempt_started", "final"]
    assert gateway.reconciled is True


@pytest.mark.asyncio
async def test_stream_waits_at_post_commit_barrier_before_yielding_final() -> None:
    events: list[str] = []

    class Barrier:
        async def wait_after_durable_commit(self, **kwargs: Any) -> None:
            assert kwargs["result"].checkpoint_id == "checkpoint-1"
            events.append("barrier")

    gateway = _StreamGateway(terminal_result_barrier=Barrier())
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "final",
                {
                    "final_result_ref": "s3://graph-results/result-1.json",
                    "final_result_hash": "f" * 64,
                },
            ),
        ]
    )

    stream = gateway.execute_stream(execution=_execution(), executor=executor)
    assert (await anext(stream)).event_type == "attempt_started"
    assert events == []
    assert (await anext(stream)).event_type == "final"
    assert events == ["barrier"]


@pytest.mark.asyncio
async def test_stream_signals_durable_final_before_its_post_commit_barrier() -> None:
    barrier_entered = asyncio.Event()
    release_barrier = asyncio.Event()
    durable_terminal_signal = asyncio.Event()

    class Barrier:
        async def wait_after_durable_commit(self, **kwargs: Any) -> None:
            assert kwargs["result"].checkpoint_id == "checkpoint-1"
            barrier_entered.set()
            await release_barrier.wait()

    gateway = _StreamGateway(terminal_result_barrier=Barrier())
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "final",
                {
                    "final_result_ref": "s3://graph-results/result-1.json",
                    "final_result_hash": "f" * 64,
                },
            ),
        ]
    )

    stream = gateway.execute_stream(
        execution=_execution(),
        executor=executor,
        durable_terminal_signal=durable_terminal_signal,
    )
    assert (await anext(stream)).event_type == "attempt_started"
    pending_final = asyncio.create_task(anext(stream))
    await asyncio.wait_for(barrier_entered.wait(), timeout=0.1)

    assert gateway.reconciled is True
    assert durable_terminal_signal.is_set()
    assert pending_final.done() is False

    release_barrier.set()
    assert (await pending_final).event_type == "final"


@pytest.mark.asyncio
async def test_stream_marks_terminal_processing_before_reconciliation_can_await() -> None:
    """The early signal closes commit-release -> durable-signal scheduler interleaving."""

    processing_started = asyncio.Event()
    durable_terminal_signal = asyncio.Event()
    reconciliation_entered = asyncio.Event()
    release_reconciliation = asyncio.Event()

    class BlockingReconciliationGateway(_StreamGateway):
        async def reconcile_terminal(
            self,
            admission: GatewayAdmission,
            *,
            owner_id: str,
            durable_terminal_signal: asyncio.Event | None = None,
        ):
            assert processing_started.is_set()
            assert durable_terminal_signal is not None
            assert durable_terminal_signal.is_set() is False
            reconciliation_entered.set()
            await release_reconciliation.wait()
            return await super().reconcile_terminal(
                admission,
                owner_id=owner_id,
                durable_terminal_signal=durable_terminal_signal,
            )

    gateway = BlockingReconciliationGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "final",
                {
                    "final_result_ref": "s3://graph-results/result-1.json",
                    "final_result_hash": "f" * 64,
                },
            ),
        ]
    )

    stream = gateway.execute_stream(
        execution=_execution(),
        executor=executor,
        durable_terminal_signal=durable_terminal_signal,
        terminal_processing_started=processing_started,
    )
    assert (await anext(stream)).event_type == "attempt_started"
    pending_final = asyncio.create_task(anext(stream))
    await asyncio.wait_for(reconciliation_entered.wait(), timeout=0.1)

    assert processing_started.is_set()
    assert durable_terminal_signal.is_set() is False
    assert pending_final.done() is False

    release_reconciliation.set()
    assert (await pending_final).event_type == "final"
    assert durable_terminal_signal.is_set()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("event_type", "payload"),
    [
        ("attempt_aborted", {"reason_code": "PROVIDER_TIMEOUT"}),
        ("error", {"error_code": "GRAPH_STREAM_ERROR", "retryable": False}),
    ],
)
async def test_stream_signals_durable_abort_or_error_before_terminal_yield(
    event_type: str,
    payload: dict[str, Any],
) -> None:
    durable_terminal_signal = asyncio.Event()
    terminal_processing_started = asyncio.Event()
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(1, event_type, payload),
        ]
    )

    stream = gateway.execute_stream(
        execution=_execution(),
        executor=executor,
        durable_terminal_signal=durable_terminal_signal,
        terminal_processing_started=terminal_processing_started,
    )
    assert (await anext(stream)).event_type == "attempt_started"

    assert (await anext(stream)).event_type == event_type
    assert gateway.finished is True
    assert terminal_processing_started.is_set()
    assert durable_terminal_signal.is_set()


@pytest.mark.asyncio
async def test_stream_keeps_durable_terminal_signal_when_post_commit_barrier_fails() -> None:
    durable_terminal_signal = asyncio.Event()

    class FailingBarrier:
        async def wait_after_durable_commit(self, **kwargs: Any) -> None:
            assert kwargs["result"].checkpoint_id == "checkpoint-1"
            raise RuntimeError("post-commit barrier failed")

    gateway = _StreamGateway(terminal_result_barrier=FailingBarrier())
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "final",
                {
                    "final_result_ref": "s3://graph-results/result-1.json",
                    "final_result_hash": "f" * 64,
                },
            ),
        ]
    )

    stream = gateway.execute_stream(
        execution=_execution(),
        executor=executor,
        durable_terminal_signal=durable_terminal_signal,
    )
    assert (await anext(stream)).event_type == "attempt_started"
    with pytest.raises(RuntimeError, match="post-commit barrier failed"):
        await anext(stream)

    assert gateway.reconciled is True
    assert durable_terminal_signal.is_set()


@pytest.mark.asyncio
async def test_stream_keeps_durable_terminal_signal_when_final_binding_validation_fails() -> None:
    durable_terminal_signal = asyncio.Event()
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "final",
                {
                    "final_result_ref": "s3://graph-results/wrong-result.json",
                    "final_result_hash": "a" * 64,
                },
            ),
        ]
    )

    stream = gateway.execute_stream(
        execution=_execution(),
        executor=executor,
        durable_terminal_signal=durable_terminal_signal,
    )
    assert (await anext(stream)).event_type == "attempt_started"
    with pytest.raises(GraphTerminalBindingError, match="conflicts with ledger"):
        await anext(stream)

    assert gateway.reconciled is True
    assert durable_terminal_signal.is_set()


@pytest.mark.asyncio
async def test_attempt_aborted_is_durable_terminal_for_one_http_attempt() -> None:
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(1, "attempt_aborted", {"reason_code": "PROVIDER_TIMEOUT"}),
        ]
    )

    events = [
        event async for event in gateway.execute_stream(execution=_execution(), executor=executor)
    ]

    assert events[-1].event_type == "attempt_aborted"
    assert gateway.finished is True


@pytest.mark.asyncio
async def test_stream_rejects_incompatible_payload_before_durable_terminal_work() -> None:
    gateway = _StreamGateway()
    executor = _Executor(
        [
            AgentStreamEvent.model_construct(
                schema_version="agent-stream.v3",
                run_id="run-1",
                attempt_id="attempt-1",
                sequence_no=0,
                event_type="attempt_started",
                audience="USER",
                occurred_at=NOW,
                payload=AgentStreamPayload.model_construct(
                    node="intake.start",
                    reason_code="INJECTED_FIELD",
                ),
            )
        ]
    )

    with pytest.raises(GraphContractError, match="payload fields"):
        _ = [
            event
            async for event in gateway.execute_stream(
                execution=_execution(),
                executor=executor,
            )
        ]

    assert gateway.reconciled is False
    assert gateway.finished is False


@pytest.mark.asyncio
async def test_stream_rejects_duplicate_attempt_started() -> None:
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(1, "attempt_started", {"node": "intake.again"}),
        ]
    )

    with pytest.raises(GraphContractError, match="another attempt_started"):
        _ = [
            event
            async for event in gateway.execute_stream(
                execution=_execution(),
                executor=executor,
            )
        ]


@pytest.mark.asyncio
async def test_python_stream_cannot_claim_java_attempt_reset_authority() -> None:
    gateway = _StreamGateway()
    executor = _Executor(
        [
            _event(0, "attempt_started", {"node": "intake.start"}),
            _event(
                1,
                "attempt_reset",
                {
                    "reset_attempt_id": "attempt-prior",
                    "reason_code": "MODEL_RESPONSE_NOT_CHECKPOINTED",
                },
            ),
        ]
    )

    with pytest.raises(GraphContractError, match="RESET_AUTHORITY_VIOLATION"):
        _ = [
            event
            async for event in gateway.execute_stream(
                execution=_execution(),
                executor=executor,
            )
        ]
