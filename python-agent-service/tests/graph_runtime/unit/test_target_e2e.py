from __future__ import annotations

from datetime import datetime, timezone
import base64
from dataclasses import replace
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from cryptography.hazmat.primitives.asymmetric import ec
import jwt
import pytest

from app.config import (
    GraphTargetE2EBindingSettings,
    GraphTargetE2ERuntimeContextSettings,
)
from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting
from app.contracts.v1.models import ExecutionMetadata, RoomGraphCommand, Usage
from app.graph_runtime.checkpoint import TerminalResultMaterializer
from app.graph_runtime.errors import GraphContractError, GraphThreadBindingError
from app.graph_runtime.gateway import GraphCommandGateway
from app.graph_runtime.ledger import PostgresCommandLedger
from app.graph_runtime.persistence_models import (
    GraphFenceContext,
    GraphBindingError,
    GraphGatewayMode,
    GraphPersistenceConfigurationError,
)
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graph_runtime.target_e2e import (
    ADVANCE_ROOM_AUTHORITY_SQL,
    PostgresTargetE2EActivationRepository,
    TargetE2EGraphCommandEnvelope,
    TargetE2EGraphResultEnvelope,
    TargetE2EInputAuthorizer,
    TargetE2EInvocationVerifier,
    TargetE2ERoomProposal,
    TargetE2ERoomProposalSource,
    TargetE2ERuntimeAuthority,
    TargetE2EThreadIdentityResolver,
    target_e2e_command_hash,
)
from app.graph_runtime.target_e2e_composite import TargetE2ECompositeExecutor
from app.graph_runtime.identity import RoomType
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    VerifiedInvocation,
    invocation_binding_claims,
)


ROOT = Path(__file__).resolve().parents[4]
VECTOR = ROOT / "contracts/agent-platform/v1/fixtures/canonical-hash/room-graph-command-self-hash.json"
NOW_DT = datetime(2026, 7, 27, 10, 30, tzinfo=timezone.utc)
NOW = int(NOW_DT.timestamp())
KID = "java-invocation-es256-1"
ACTIVATION_ID = f"p9act.v1.{'a' * 32}"
BINDING_HASH = "b" * 64


class _Resolver:
    def __init__(self, public_key: Any, *, kid: str = KID) -> None:
        self.public_key = public_key
        self.kid = kid

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        if kid != self.kid:
            raise KeyError(kid)
        return ResolvedVerificationKey(kid=kid, public_key=self.public_key)


def _command() -> RoomGraphCommand:
    vector = json.loads(VECTOR.read_text(encoding="utf-8"))
    values = {**vector["input"]}
    values.update(
        {
        "graph_key": "all-rooms.target-e2e.v1",
            "graph_version": "target-e2e-graph.2026-07-27.1",
            "checkpoint_schema_version": "target-e2e-checkpoint.v1",
            "invocation_context": {
                **values["invocation_context"],
                "output_schema_version": "target-e2e-room-proposal-source.v1",
            },
        }
    )
    values[vector["hash_field"]] = vector["sha256"]
    values[vector["hash_field"]] = canonical_sha256_omitting(
        values,
        vector["hash_field"],
    )
    return RoomGraphCommand.model_validate(values)


def _binding() -> GraphTargetE2EBindingSettings:
    command = _command()
    return GraphTargetE2EBindingSettings(
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        state_schema_version="intake-state.v2",
        state_schema_hash="d" * 64,
        command_schema_version="room-graph-command.v1",
        result_schema_version="room-graph-result.v1",
        agent_profile_id=command.invocation_context.agent_profile_id,
        prompt_version=command.invocation_context.prompt_profile_id,
        model_profile_id=command.invocation_context.model_profile_id,
        output_schema_version=command.invocation_context.output_schema_version,
        policy_version=command.invocation_context.policy_version,
        guardrail_version=command.invocation_context.guardrail_version,
        tool_policy_version="tools.none.v1",
        binding_hash=BINDING_HASH,
        code_build_id="candidate-build-1",
        allowed_room_types=("INTAKE", "EVIDENCE", "HEARING", "REVIEW"),
        allowed_stage_codes=(command.stage_code,),
    )


def _context_values(**overrides: Any) -> dict[str, Any]:
    command = _command()
    values: dict[str, Any] = {
        "schemaVersion": "graph-target-e2e-runtime-context.v1",
        "executionLane": "TARGET_E2E_CANDIDATE",
        "activationId": ACTIVATION_ID,
        "environmentId": "target-e2e-local",
        "environmentGeneration": 7,
        "candidateSha": "c" * 40,
        "issuedAt": "2026-07-27T10:00:00Z",
        "expiresAt": "2026-07-27T11:30:00Z",
        "runNonce": "runtime-projection-nonce-0123456789abcdef",
        "tenantSurrogate": command.tenant_surrogate,
        "caseScope": {
            "mode": "EXPLICIT_CASE_IDS",
            "allowedCaseIds": [command.case_id],
        },
        "allowedRoomTypes": [command.room_type],
        "composeProject": "p9_target_e2e",
        "temporalNamespace": "target-e2e-p9",
        "buildBindings": {
            "caseBuildId": "p9-case-build-1",
            "controlBuildId": "p9-control-build-1",
            "agentBuildId": "p9-agent-build-1",
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
                "environmentGeneration": 7,
                "restoreVerificationHash": "6" * 64,
            },
        },
        "trustedSigningKeyIds": [KID],
        "perCommandManifestAllowed": False,
    }
    values.update(overrides)
    return values


def _authority(**overrides: Any) -> TargetE2ERuntimeAuthority:
    return TargetE2ERuntimeAuthority.from_context(
        GraphTargetE2ERuntimeContextSettings.model_validate(_context_values(**overrides)),
        (_binding(),),
    )


def _command_envelope(command: RoomGraphCommand) -> TargetE2EGraphCommandEnvelope:
    values = {
        "schema_version": "target-e2e-graph-command-envelope.v1",
        "execution_lane": "TARGET_E2E_CANDIDATE",
        "activation_id": ACTIVATION_ID,
        "room_fencing_token": 11,
        "command_hash": target_e2e_command_hash(command),
        "command": command.model_dump(mode="json", exclude_none=True),
    }
    return TargetE2EGraphCommandEnvelope.model_validate(
        {**values, "command_envelope_hash": canonical_sha256(values)}
    )


def _command_token(
    key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    *,
    kid: str = KID,
    **overrides: Any,
) -> str:
    claims: dict[str, Any] = {
        "iss": "java-api-service",
        "aud": "python-agent-service",
        "sub": "graph-command",
        "iat": NOW,
        "nbf": NOW,
        "exp": NOW + 60,
        "jti": "candidate-command-jti-1",
        **invocation_binding_claims(
            command,
            registry_binding_hash=BINDING_HASH,
            tool_policy_version="tools.none.v1",
        ),
        "execution_lane": "TARGET_E2E_CANDIDATE",
        "activation_id": ACTIVATION_ID,
        "room_fencing_token": 11,
        "command_hash": target_e2e_command_hash(command),
        "command_envelope_hash": _command_envelope(command).command_envelope_hash,
    }
    claims.update(overrides)
    return jwt.encode(
        claims,
        key,
        algorithm="ES256",
        headers={"alg": "ES256", "kid": kid, "typ": "target-e2e-graph-command+jwt"},
    )


def test_runtime_projection_and_command_credential_are_distinct() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    command = _command()
    envelope = _command_envelope(command)
    authority = _authority()
    assert authority.context_hash == canonical_sha256(
        authority.context.model_dump(mode="json", by_alias=True)
    )
    verified = TargetE2EInvocationVerifier(
        key_resolver=_Resolver(key.public_key()),
        authority=authority,
        now=lambda: NOW,
    ).verify_envelope(
        token=_command_token(key, command),
        envelope=envelope,
        transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
    )

    assert verified.authority.activation_id == ACTIVATION_ID
    assert verified.room_fencing_token == 11
    with pytest.raises(InvocationEnvelopeError, match="HEADER_REJECTED"):
        InvocationEnvelopeVerifier(
            key_resolver=_Resolver(key.public_key()),
            now=lambda: NOW,
        ).verify(
            token=_command_token(key, command),
            command=command,
            transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
        )


@pytest.mark.asyncio
async def test_signed_java_agent_session_round_trips_and_missing_or_tampered_claims_fail_closed() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    command = _command()
    verifier = TargetE2EInvocationVerifier(
        key_resolver=_Resolver(key.public_key()),
        authority=_authority(),
        now=lambda: NOW,
    )
    transport = TransportIdentity("java-api-service", True, "e" * 64)
    session_id = "AGENT_SESSION_java_issued_001"
    verified = verifier.verify_envelope(
        token=_command_token(key, command, agent_session_id=session_id),
        envelope=_command_envelope(command),
        transport_identity=transport,
    )

    identity = await TargetE2EThreadIdentityResolver().resolve(
        command=command,
        verified_invocation=verified,
    )
    assert identity.agent_session_id == session_id

    without_session = verifier.verify_envelope(
        token=_command_token(key, command),
        envelope=_command_envelope(command),
        transport_identity=transport,
    )
    with pytest.raises(GraphThreadBindingError, match="AGENT_SESSION_REQUIRED"):
        await TargetE2EThreadIdentityResolver().resolve(
            command=command,
            verified_invocation=without_session,
        )

    token = _command_token(key, command, agent_session_id=session_id)
    header, payload, signature = token.split(".")
    padding = "=" * (-len(payload) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload + padding))
    claims["agent_session_id"] = "AGENT_SESSION_tampered_001"
    encoded = base64.urlsafe_b64encode(
        json.dumps(claims, separators=(",", ":")).encode("utf-8")
    ).rstrip(b"=").decode("ascii")
    with pytest.raises(InvocationEnvelopeError, match="CLAIMS_REJECTED"):
        verifier.verify_envelope(
            token=f"{header}.{encoded}.{signature}",
            envelope=_command_envelope(command),
            transport_identity=transport,
        )

def test_command_key_must_be_in_runtime_projection_allowlist() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    command = _command()
    verifier = TargetE2EInvocationVerifier(
        key_resolver=_Resolver(key.public_key()),
        authority=_authority(trustedSigningKeyIds=["other-key"]),
        now=lambda: NOW,
    )
    with pytest.raises(InvocationEnvelopeError, match="COMMAND_KEY_REJECTED"):
        verifier.verify_envelope(
            token=_command_token(key, command),
            envelope=_command_envelope(command),
            transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
        )


def test_command_envelope_hash_and_signed_room_fence_are_tamper_evident() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    command = _command()
    envelope = _command_envelope(command)
    verifier = TargetE2EInvocationVerifier(
        key_resolver=_Resolver(key.public_key()),
        authority=_authority(),
        now=lambda: NOW,
    )
    with pytest.raises(InvocationEnvelopeError, match="ENVELOPE_MISMATCH"):
        verifier.verify_envelope(
            token=_command_token(key, command, room_fencing_token=12),
            envelope=envelope,
            transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
        )
    with pytest.raises(ValueError, match="self-hash"):
        TargetE2EGraphCommandEnvelope.model_validate(
            {**envelope.model_dump(mode="json"), "command_envelope_hash": "f" * 64}
        )


def test_reconcile_only_verifier_accepts_original_expired_60s_token() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    command = _command()
    verifier = TargetE2EInvocationVerifier(
        key_resolver=_Resolver(key.public_key()),
        authority=_authority(),
        now=lambda: NOW,
    )
    token = _command_token(
        key,
        command,
        iat=NOW - 125,
        nbf=NOW - 125,
        exp=NOW - 65,
    )
    with pytest.raises(InvocationEnvelopeError, match="JWS_EXPIRED"):
        verifier.verify_envelope(
            token=token,
            envelope=_command_envelope(command),
            transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
        )

    verified = verifier.verify_envelope_for_reconciliation(
        token=token,
        envelope=_command_envelope(command),
        transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
    )

    assert verified.claims.exp == NOW - 65


def test_result_envelope_binds_candidate_lane_command_result_and_proposal() -> None:
    fixture = json.loads(
        (
            ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-result-valid.json"
        ).read_text(encoding="utf-8")
    )["instance"]
    fixture["output_hash"] = canonical_sha256(
        {name: value for name, value in fixture.items() if name != "output_hash"}
    )
    proposal = {"schema_version": "target-e2e-intake-proposal.v1", "case_id": "case-001"}
    values = {
        "schema_version": "target-e2e-graph-result-envelope.v1",
        "execution_lane": "TARGET_E2E_CANDIDATE",
        "activation_id": ACTIVATION_ID,
        "room_fencing_token": 11,
        "command_hash": "d" * 64,
        "command_envelope_hash": "e" * 64,
        "execution_provider": "target-e2e-composite",
        "execution_model": "room-provider-dispatch",
        "result_hash": fixture["output_hash"],
        "proposal_hash": canonical_sha256(proposal),
        "result": fixture,
        "graph_output_authority": "PROPOSAL_ONLY",
    }
    envelope = TargetE2EGraphResultEnvelope.model_validate(
        {**values, "result_envelope_hash": canonical_sha256(values)}
    )
    envelope.require_proposal_hash(proposal)
    with pytest.raises(ValueError, match="proposal hash"):
        envelope.require_proposal_hash({**proposal, "case_id": "other"})


def test_candidate_terminal_materializer_atomically_binds_proposal_and_result_envelope() -> None:
    command = _command()
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-1",
        fencing_token=4,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=ACTIVATION_ID,
        room_fencing_token=11,
        command_hash=target_e2e_command_hash(command),
        command_envelope_hash=_command_envelope(command).command_envelope_hash,
        execution_provider="target-e2e-composite",
        execution_model="room-provider-dispatch",
        environment_id="target-e2e-local",
        environment_generation=7,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=command.room_type,
        binding_hash=BINDING_HASH,
        code_build_id="candidate-build-1",
    )
    source = TargetE2ERoomProposalSource(
        schema_version="target-e2e-room-proposal-source.v1",
        room_type="INTAKE",
        proposal=TargetE2ERoomProposal(
            schema_version="target-e2e-intake-proposal.v1",
            proposal_id="target-proposal.001",
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            payload_schema_version="intake-turn-proposal.v2",
            payload_ref="urn:target-e2e:proposal:intake:001",
            payload_hash="9" * 64,
            terminal_class="COMPLETED",
            formal_authority=False,
        ),
    )
    materializer = TerminalResultMaterializer(
        thread_id=command.thread_id,
        request_hash=command.request_hash,
        draft=CompletedDraft(status="COMPLETED"),
        bindings=ResultBindings(
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_id="pending",
            cognitive_revision=1,
            public_event_proposals=(),
            artifact_operations=(),
            usage=Usage(input_tokens=0, output_tokens=0, total_tokens=0),
            execution_metadata=ExecutionMetadata(
                prompt_version=command.invocation_context.prompt_profile_id,
                model_profile_id=command.invocation_context.model_profile_id,
                schema_version=command.invocation_context.output_schema_version,
                policy_version=command.invocation_context.policy_version,
                guardrail_version=command.invocation_context.guardrail_version,
            ),
        ),
        target_proposal_source=source,
    )
    result = materializer.materialize("intake", "checkpoint-1", fence=fence)

    envelope = TargetE2EGraphResultEnvelope.model_validate(result.result_envelope_json)
    assert result.proposal_hash == source.proposal_hash == envelope.proposal_hash
    assert result.result_envelope_hash == envelope.result_envelope_hash
    assert envelope.room_fencing_token == 11
    assert envelope.execution_provider == "target-e2e-composite"
    assert envelope.execution_model == "room-provider-dispatch"
    PostgresCommandLedger._validate_result_record(result)  # noqa: SLF001
    with pytest.raises(GraphBindingError, match="execution identity"):
        materializer.materialize(
            "intake",
            "checkpoint-identity-missing",
            fence=replace(fence, execution_provider=None, execution_model=None),
        )


def test_runtime_projection_scope_and_synthetic_slots_fail_closed() -> None:
    command = _command()
    with pytest.raises(InvocationEnvelopeError, match="CASE_NOT_ALLOWED"):
        _authority(
            caseScope={"mode": "EXPLICIT_CASE_IDS", "allowedCaseIds": ["another-case"]}
        ).authorize(_command_envelope(command))
    synthetic = _authority(
        caseScope={
            "mode": "ISOLATED_SYNTHETIC_NEW_CASES",
            "caseIdPrefix": "CASE_P9_SYNTHETIC_",
            "maxCases": 4,
            "fixtureSetId": "p9-synthetic-all-rooms-001",
            "fixtureSetHash": "7" * 64,
            "containsRealCaseOrPartyData": False,
            "externalEffectsAllowed": False,
        }
    )
    assert synthetic.synthetic_slot("CASE_P9_SYNTHETIC_0001") == 1
    assert synthetic.synthetic_slot("CASE_P9_SYNTHETIC_0005") is None
    assert synthetic.synthetic_slot("CASE_P9_SYNTHETIC_6f4f10b5-8f77-4a2d-9fbc-1ca2b9af4321") is None
    assert synthetic.synthetic_slot("1") is None


def test_shadow_and_candidate_gateways_reject_each_others_credentials() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    command = _command()
    candidate = TargetE2EInvocationVerifier(
        key_resolver=_Resolver(key.public_key()),
        authority=_authority(),
        now=lambda: NOW,
    ).verify_envelope(
        token=_command_token(key, command),
        envelope=_command_envelope(command),
        transport_identity=TransportIdentity("java-api-service", True, "e" * 64),
    )
    shadow = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=object(),
        input_authorizer=TargetE2EInputAuthorizer(),
    )
    target = GraphCommandGateway(
        mode=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        pool=object(),
        input_authorizer=TargetE2EInputAuthorizer(),
    )
    with pytest.raises(Exception, match="SHADOW_CANDIDATE"):
        shadow._require_invocation_lane(candidate)  # noqa: SLF001
    with pytest.raises(Exception, match="TARGET_E2E_CREDENTIAL"):
        target._require_invocation_lane(  # noqa: SLF001
            VerifiedInvocation(
                claims=candidate.claims,
                key_id=candidate.key_id,
                request_hash=candidate.request_hash,
                transport_certificate_sha256=candidate.transport_certificate_sha256,
            )
        )


def test_candidate_fence_binds_distinct_java_room_and_graph_lease_fences() -> None:
    fence = GraphFenceContext(
        thread_id=f"grt.v1.{'1' * 32}",
        command_id="command-1",
        owner_id="worker-1",
        fencing_token=4,
        request_hash="a" * 64,
        room_epoch=2,
        graph_key="intake.v2",
        graph_version="1.0.0",
        checkpoint_schema_version="checkpoint.v1",
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=ACTIVATION_ID,
        room_fencing_token=11,
        command_hash="d" * 64,
        command_envelope_hash="e" * 64,
        environment_id="target-e2e-local",
        environment_generation=7,
        tenant_surrogate="tenant-demo",
        case_id="case-001",
        room_type="INTAKE",
        binding_hash=BINDING_HASH,
        code_build_id="candidate-build-1",
    )
    assert fence.checkpoint_metadata()["graph_fencing_token"] == 4
    assert fence.checkpoint_metadata()["graph_room_fencing_token"] == 11
    with pytest.raises(GraphPersistenceConfigurationError, match="complete activation"):
        GraphFenceContext(
            thread_id=fence.thread_id,
            command_id=fence.command_id,
            owner_id=fence.owner_id,
            fencing_token=4,
            request_hash=fence.request_hash,
            room_epoch=2,
            graph_key=fence.graph_key,
            graph_version=fence.graph_version,
            checkpoint_schema_version=fence.checkpoint_schema_version,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
            activation_id=ACTIVATION_ID,
            room_fencing_token=11,
            command_hash="d" * 64,
            command_envelope_hash="e" * 64,
        )


class _Cursor:
    def __init__(self, row: dict[str, Any] | None = None, rowcount: int = 1) -> None:
        self.row = row
        self.rowcount = rowcount

    async def fetchone(self) -> dict[str, Any] | None:
        return self.row


class _StaleGenerationConnection:
    async def execute(self, sql: str, params: tuple[Any, ...]) -> _Cursor:
        if "from agent_graph_target_e2e_environment_generation" in sql:
            return _Cursor(
                {
                    "environment_generation": 8,
                    "activation_id": "p9act.v1." + "f" * 32,
                    "context_hash": "f" * 64,
                }
            )
        return _Cursor()


@pytest.mark.asyncio
async def test_runtime_projection_registration_rejects_stale_generation() -> None:
    with pytest.raises(GraphContractError, match="GENERATION_STALE"):
        await PostgresTargetE2EActivationRepository().register(
            _StaleGenerationConnection(),
            _authority(),
        )


def test_equal_room_fence_is_idempotent_only_for_the_same_activation() -> None:
    normalized = " ".join(ADVANCE_ROOM_AUTHORITY_SQL.split())

    assert (
        "excluded.room_fencing_token > "
        "agent_graph_target_e2e_room_authority.room_fencing_token"
    ) in normalized
    assert (
        "excluded.room_fencing_token = "
        "agent_graph_target_e2e_room_authority.room_fencing_token "
        "and excluded.activation_id = "
        "agent_graph_target_e2e_room_authority.activation_id"
    ) in normalized
    assert "room_fencing_token >=" not in normalized


@pytest.mark.asyncio
async def test_composite_executor_requires_exact_four_room_providers_and_no_fallback() -> None:
    class Provider:
        def __init__(self, room_type: RoomType) -> None:
            self.room_type = room_type

        async def stream(self, execution: Any):
            assert execution.admission.command.room_type == self.room_type.value
            if False:
                yield None

    providers = tuple(Provider(room_type) for room_type in RoomType)
    executor = TargetE2ECompositeExecutor(providers)
    command = _command()
    execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=command,
            candidate_authority=_authority(),
            binding=SimpleNamespace(
                execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE
            ),
        ),
        fence=SimpleNamespace(execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE),
    )

    assert executor.provider_count == 4
    assert [event async for event in executor.stream(execution)] == []
    with pytest.raises(GraphContractError, match="exactly four"):
        TargetE2ECompositeExecutor(providers[:-1])
    with pytest.raises(GraphContractError, match="duplicate"):
        TargetE2ECompositeExecutor((*providers, providers[0]))
    wrong = SimpleNamespace(
        admission=SimpleNamespace(
            command=command.model_copy(update={"graph_version": "wrong"}),
            candidate_authority=_authority(),
            binding=execution.admission.binding,
        ),
        fence=execution.fence,
    )
    with pytest.raises(GraphContractError, match="binding is invalid"):
        executor.stream(wrong)
