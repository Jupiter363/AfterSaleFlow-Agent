from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.graph_commands import (
    GraphReconciliationEndpointDependencies,
    create_graph_reconciliation_router,
)
from app.config import (
    GraphProductionBindingSettings,
    GraphProductionRuntimeContextSettings,
)
from app.contracts.v1.codec import (
    ContractCodec,
    canonical_sha256,
    canonical_sha256_omitting,
)
from app.contracts.v1.models import GraphReconcileResponse, RoomGraphCommand, RoomGraphResult
from app.graph_runtime.errors import (
    GraphCommandAbortedError,
    GraphCommandCancelledError,
    GraphCommandHashConflictError,
    GraphCommandNotFoundError,
    GraphGatewayDisabledError,
    GraphLeaseLostError,
    GraphLeaseUnavailableError,
    GraphNewAgentAttemptRequiredError,
    GraphNonceReplayError,
    GraphRecoveryError,
    GraphResultNotCommittedError,
    GraphTerminalBindingError,
)
from app.graph_runtime.identity import ActorScopeBinding, RoomType, ThreadIdentity
from app.graph_runtime.production_runtime import (
    ProductionGraphCommandEnvelope,
    ProductionGraphResultEnvelope,
    ProductionInvocationClaims,
    ProductionRoomProposalSource,
    ProductionRuntimeAuthority,
    VerifiedProductionInvocation,
    production_runtime_command_hash,
)
from app.security.invocation_envelope import (
    InvocationClaims,
    InvocationEnvelopeError,
    ReconciliationClaims,
    TransportIdentity,
    VerifiedInvocation,
    VerifiedReconciliation,
    invocation_binding_claims,
)


ROOT = Path(__file__).resolve().parents[4]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/v1"
COMMAND_FIXTURE = (
    CONTRACT_ROOT / "fixtures/valid/room-graph-command-valid.json"
)
RESPONSE_FIXTURE = (
    CONTRACT_ROOT / "fixtures/valid/graph-reconcile-response-valid.json"
)
PATH = "/internal/graphs/commands/reconcile"
TARGET_PATH = "/internal/graphs/production-runtime/commands/reconcile"
TARGET_PROPOSAL_PATH = "/internal/graphs/production-runtime/commands/proposal-source"


def _command() -> tuple[RoomGraphCommand, dict[str, Any]]:
    instance = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    return RoomGraphCommand.model_validate(instance), instance


def _target_command() -> RoomGraphCommand:
    command, _ = _command()
    values = command.model_dump(mode="json", exclude_none=True)
    values.update(
        {
        "graph_key": "all-rooms.production-runtime.v1",
            "graph_version": "production-runtime-graph.2026-07-27.1",
            "checkpoint_schema_version": "production-runtime-checkpoint.v1",
            "invocation_context": {
                **values["invocation_context"],
                "output_schema_version": "production-runtime-room-proposal-source.v1",
            },
        }
    )
    values["request_hash"] = canonical_sha256_omitting(values, "request_hash")
    return RoomGraphCommand.model_validate(values)


def _response(command: RoomGraphCommand) -> GraphReconcileResponse:
    instance = json.loads(RESPONSE_FIXTURE.read_text(encoding="utf-8"))["instance"]
    instance.update(
        {
            "thread_id": command.thread_id,
            "command_id": command.command_id,
            "request_hash": command.request_hash,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "graph_key": command.graph_key,
            "graph_version": command.graph_version,
            "checkpoint_schema_version": command.checkpoint_schema_version,
        }
    )
    instance["result"].update(
        {
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "graph_key": command.graph_key,
            "graph_version": command.graph_version,
        }
    )
    return GraphReconcileResponse.model_validate(instance)


def _verified_reconciliation(command: RoomGraphCommand) -> VerifiedReconciliation:
    claims = ReconciliationClaims(
        iss="java-api-service",
        aud="python-agent-service",
        sub="graph-reconcile",
        iat=1_752_739_200,
        nbf=1_752_739_200,
        exp=1_752_739_260,
        jti="reconcile-jti-001",
        capability="RECONCILE_ONLY",
        original_envelope_key_id=command.invocation_context.envelope_key_id,
        **invocation_binding_claims(command),
    )
    return VerifiedReconciliation(
        claims=claims,
        key_id="java-reconciliation-es256-1",
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
    )


def _verified_invocation(command: RoomGraphCommand) -> VerifiedInvocation:
    claims = InvocationClaims(
        iss="java-api-service",
        aud="python-agent-service",
        sub="graph-command",
        iat=1_752_739_200,
        nbf=1_752_739_200,
        exp=1_752_739_260,
        jti="execute-jti-001",
        **invocation_binding_claims(command),
    )
    return VerifiedInvocation(
        claims=claims,
        key_id=command.invocation_context.envelope_key_id,
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
    )


def _target_proposal_fixture() -> tuple[
    RoomGraphCommand,
    ProductionGraphCommandEnvelope,
    VerifiedProductionInvocation,
    ProductionRoomProposalSource,
    str,
]:
    command = _target_command()
    activation_id = f"p9act.v1.{'a' * 32}"
    command_hash = production_runtime_command_hash(command)
    envelope_values = {
        "schema_version": "production-runtime-graph-command-envelope.v1",
        "execution_lane": "PRODUCTION",
        "activation_id": activation_id,
        "room_fencing_token": 19,
        "command_hash": command_hash,
        "command": command.model_dump(mode="json", exclude_none=True),
    }
    envelope = ProductionGraphCommandEnvelope.model_validate(
        {
            **envelope_values,
            "command_envelope_hash": canonical_sha256(envelope_values),
        }
    )
    verified = VerifiedProductionInvocation(
        claims=_verified_invocation(command).claims,
        key_id=command.invocation_context.envelope_key_id,
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
        authority=cast(
            Any,
            SimpleNamespace(activation_id=activation_id),
        ),
        command_hash=command_hash,
        command_envelope_hash=envelope.command_envelope_hash,
        room_fencing_token=19,
    )
    proposal_source = ProductionRoomProposalSource.model_validate(
        {
            "schema_version": "production-runtime-room-proposal-source.v1",
            "room_type": command.room_type,
            "proposal": {
                "schema_version": "production-runtime-intake-proposal.v1",
                "proposal_id": "proposal-target-001",
                "command_id": command.command_id,
                "logical_run_id": command.logical_run_id,
                "attempt_id": command.attempt_id,
                "payload_schema_version": "intake-turn-proposal.v2",
                "payload_ref": "urn:production-runtime:proposal:intake:001",
                "payload_hash": "7" * 64,
                "terminal_class": "COMPLETED",
                "formal_authority": False,
            },
        }
    )
    result_ref = f"urn:after-sale-flow:graph-result:{'8' * 64}"
    return command, envelope, verified, proposal_source, result_ref


def _thread(command: RoomGraphCommand) -> ThreadIdentity:
    return ThreadIdentity(
        thread_id=command.thread_id,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType(command.room_type),
        room_epoch=command.room_epoch,
        actor_scope=ActorScopeBinding.from_json(
            command.actor_scope.model_dump(mode="json")
        ),
        agent_session_id="trusted-agent-session-1",
        shared_session=False,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )


class IdentityResolver:
    def __init__(self, identity: Any | None = None) -> None:
        self.identity = identity or TransportIdentity(
            service_id="java-api-service",
            authenticated=True,
            certificate_sha256="c" * 64,
        )
        self.calls = 0

    def resolve(self, scope: Mapping[str, Any]) -> Any:
        self.calls += 1
        return self.identity


class Verifier:
    def __init__(self, result: Any) -> None:
        self.result = result
        self.calls: list[tuple[str, RoomGraphCommand, TransportIdentity]] = []

    def verify(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> Any:
        self.calls.append((token, command, transport_identity))
        return self.result


class ThreadResolver:
    def __init__(self) -> None:
        self.calls: list[tuple[RoomGraphCommand, VerifiedReconciliation]] = []

    async def resolve(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
    ) -> ThreadIdentity:
        self.calls.append((command, verified_reconciliation))
        return _thread(command)


class ReconciliationService:
    def __init__(
        self,
        response: GraphReconcileResponse,
        *,
        failure: Exception | None = None,
    ) -> None:
        self.response = response
        self.failure = failure
        self.calls: list[
            tuple[RoomGraphCommand, VerifiedReconciliation, ThreadIdentity]
        ] = []

    async def reconcile(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
        expected_thread: ThreadIdentity,
    ) -> GraphReconcileResponse:
        self.calls.append((command, verified_reconciliation, expected_thread))
        if self.failure is not None:
            raise self.failure
        return self.response


def _client(
    *,
    command: RoomGraphCommand,
    verifier_result: Any | None = None,
    service_failure: Exception | None = None,
    identity: Any | None = None,
    mode: str = "SHADOW",
    ready: bool = True,
) -> tuple[TestClient, Verifier, ThreadResolver, ReconciliationService]:
    verifier = Verifier(verifier_result or _verified_reconciliation(command))
    thread_resolver = ThreadResolver()
    service = ReconciliationService(
        _response(command),
        failure=service_failure,
    )
    app = FastAPI()
    app.include_router(
        create_graph_reconciliation_router(
            GraphReconciliationEndpointDependencies(
                mode=mode,
                codec=ContractCodec(CONTRACT_ROOT),
                transport_identity_resolver=IdentityResolver(identity),
                envelope_verifier=verifier,
                thread_identity_resolver=thread_resolver,
                reconciliation_service=service,
                ready=lambda: ready,
            )
        )
    )
    return (
        TestClient(app, raise_server_exceptions=False),
        verifier,
        thread_resolver,
        service,
    )


def _headers() -> dict[str, str]:
    return {
        "Authorization": "Bearer reconciliation.token.signature",
        "Content-Type": "application/json; charset=utf-8",
    }


class _TargetProposalVerifier:
    def __init__(
        self,
        verified: VerifiedProductionInvocation,
        *,
        failure: InvocationEnvelopeError | None = None,
    ) -> None:
        self.verified = verified
        self.failure = failure
        self.calls: list[dict[str, Any]] = []

    def verify_envelope(self, **kwargs: Any) -> VerifiedProductionInvocation:
        return self.verify_envelope_for_reconciliation(**kwargs)

    def verify_envelope_for_reconciliation(
        self,
        **kwargs: Any,
    ) -> VerifiedProductionInvocation:
        self.calls.append(kwargs)
        if self.failure is not None:
            raise self.failure
        return self.verified


class _TargetProposalThreadResolver:
    def __init__(self, command: RoomGraphCommand) -> None:
        self.thread = _thread(command)
        self.calls: list[dict[str, Any]] = []

    async def resolve(self, **kwargs: Any) -> ThreadIdentity:
        self.calls.append(kwargs)
        return self.thread


class _TargetProposalService:
    def __init__(
        self,
        proposal_source: ProductionRoomProposalSource,
        *,
        failure: Exception | None = None,
    ) -> None:
        self.proposal_source = proposal_source
        self.failure = failure
        self.calls: list[dict[str, Any]] = []

    async def retrieve_production_runtime_proposal_source(
        self,
        **kwargs: Any,
    ) -> ProductionRoomProposalSource:
        self.calls.append(kwargs)
        if self.failure is not None:
            raise self.failure
        return self.proposal_source


def _target_proposal_client(
    *,
    mode: str = "PRODUCTION",
    ready: bool = True,
    verification_failure: InvocationEnvelopeError | None = None,
    service_failure: Exception | None = None,
) -> tuple[
    TestClient,
    ProductionGraphCommandEnvelope,
    ProductionRoomProposalSource,
    str,
    _TargetProposalVerifier,
    _TargetProposalThreadResolver,
    _TargetProposalService,
]:
    command, envelope, verified, proposal_source, result_ref = (
        _target_proposal_fixture()
    )
    verifier = _TargetProposalVerifier(
        verified,
        failure=verification_failure,
    )
    resolver = _TargetProposalThreadResolver(command)
    service = _TargetProposalService(proposal_source, failure=service_failure)
    app = FastAPI()
    app.include_router(
        create_graph_reconciliation_router(
            GraphReconciliationEndpointDependencies(
                mode=mode,
                codec=ContractCodec(CONTRACT_ROOT),
                transport_identity_resolver=IdentityResolver(),
                envelope_verifier=Verifier(_verified_reconciliation(command)),
                thread_identity_resolver=ThreadResolver(),
                reconciliation_service=cast(Any, service),
                ready=lambda: ready,
                production_runtime_envelope_verifier=verifier,
                production_runtime_thread_identity_resolver=resolver,
            )
        )
    )
    return (
        TestClient(app, raise_server_exceptions=False),
        envelope,
        proposal_source,
        result_ref,
        verifier,
        resolver,
        service,
    )


def _target_proposal_headers(
    result_ref: str,
    proposal_hash: str,
) -> dict[str, str]:
    return {
        **_headers(),
        "X-Graph-Result-Ref": result_ref,
        "X-Graph-Proposal-Hash": proposal_hash,
    }


def test_target_proposal_source_returns_only_exact_validated_persisted_object() -> None:
    client, envelope, proposal_source, result_ref, verifier, resolver, service = (
        _target_proposal_client()
    )

    response = client.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers=_target_proposal_headers(result_ref, proposal_source.proposal_hash),
    )

    assert response.status_code == 200
    assert response.json() == proposal_source.model_dump(mode="json")
    assert set(response.json()) == {"schema_version", "room_type", "proposal"}
    assert response.headers["cache-control"] == "no-store, no-transform"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert len(verifier.calls) == len(resolver.calls) == len(service.calls) == 1
    assert service.calls[0]["expected_result_ref"] == result_ref
    assert (
        service.calls[0]["expected_proposal_hash"]
        == proposal_source.proposal_hash
    )


@pytest.mark.parametrize(
    "headers",
    [
        _headers(),
        {
            **_headers(),
            "X-Graph-Result-Ref": "not-a-result-ref",
            "X-Graph-Proposal-Hash": "7" * 64,
        },
        {
            **_headers(),
            "X-Graph-Result-Ref": f"urn:{'x' * 509}",
            "X-Graph-Proposal-Hash": "7" * 64,
        },
        {
            **_headers(),
            "X-Graph-Result-Ref": "urn:result:valid",
            "X-Graph-Proposal-Hash": "A" * 64,
        },
    ],
)
def test_target_proposal_source_rejects_missing_malformed_or_oversized_headers(
    headers: dict[str, str],
) -> None:
    client, envelope, _, _, verifier, resolver, service = _target_proposal_client()

    response = client.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers=headers,
    )

    assert response.status_code == 400
    assert response.json()["code"] == "PRODUCTION_RUNTIME_PROPOSAL_SOURCE_HEADERS_REJECTED"
    assert verifier.calls == resolver.calls == service.calls == []
    assert "proposal" not in response.text


def test_target_proposal_source_rejects_duplicate_selector_headers() -> None:
    client, envelope, proposal_source, result_ref, verifier, resolver, service = (
        _target_proposal_client()
    )

    response = client.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers=[
            ("Authorization", "Bearer reconciliation.token.signature"),
            ("Content-Type", "application/json"),
            ("X-Graph-Result-Ref", result_ref),
            ("X-Graph-Result-Ref", result_ref),
            ("X-Graph-Proposal-Hash", proposal_source.proposal_hash),
            ("X-Graph-Proposal-Hash", proposal_source.proposal_hash),
        ],
    )

    assert response.status_code == 400
    assert response.json()["code"] == "PRODUCTION_RUNTIME_PROPOSAL_SOURCE_HEADERS_REJECTED"
    assert verifier.calls == resolver.calls == service.calls == []


@pytest.mark.parametrize(
    ("failure", "expected_code"),
    [
        (
            InvocationEnvelopeError("PRODUCTION_RUNTIME_COMMAND_SIGNATURE_REJECTED"),
            "PRODUCTION_RUNTIME_COMMAND_SIGNATURE_REJECTED",
        ),
        (
            InvocationEnvelopeError("PRODUCTION_RUNTIME_COMMAND_BINDING_MISMATCH"),
            "PRODUCTION_RUNTIME_COMMAND_BINDING_MISMATCH",
        ),
    ],
)
def test_target_proposal_source_rejects_wrong_credential_or_binding(
    failure: InvocationEnvelopeError,
    expected_code: str,
) -> None:
    client, envelope, proposal_source, result_ref, verifier, resolver, service = (
        _target_proposal_client(verification_failure=failure)
    )

    response = client.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers=_target_proposal_headers(result_ref, proposal_source.proposal_hash),
    )

    assert response.status_code == 401
    assert response.json()["code"] == expected_code
    assert len(verifier.calls) == 1
    assert resolver.calls == service.calls == []
    assert "proposal-target-001" not in response.text


@pytest.mark.parametrize(
    "failure",
    [
        GraphCommandNotFoundError(),
        GraphResultNotCommittedError(),
        GraphTerminalBindingError("candidate proposal selector differs"),
    ],
)
def test_target_proposal_source_rejects_missing_nonterminal_or_hash_mismatched_result(
    failure: Exception,
) -> None:
    client, envelope, proposal_source, result_ref, _, _, service = (
        _target_proposal_client(service_failure=failure)
    )

    response = client.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers=_target_proposal_headers(result_ref, proposal_source.proposal_hash),
    )

    assert response.status_code in {404, 409}
    assert len(service.calls) == 1
    assert "proposal-target-001" not in response.text


def test_target_proposal_source_rejects_wrong_mode_activation_and_oversized_body() -> None:
    disabled, envelope, proposal_source, result_ref, _, _, disabled_service = (
        _target_proposal_client(mode="SHADOW")
    )
    headers = _target_proposal_headers(result_ref, proposal_source.proposal_hash)
    wrong_mode = disabled.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers=headers,
    )

    client, envelope, proposal_source, result_ref, verifier, resolver, service = (
        _target_proposal_client()
    )
    activation = client.post(
        TARGET_PROPOSAL_PATH,
        content=json.dumps(envelope.model_dump(mode="json")),
        headers={
            **_target_proposal_headers(result_ref, proposal_source.proposal_hash),
            "X-AfterSaleFlow-Production-Runtime-Activation": "forbidden",
        },
    )
    oversized = client.post(
        TARGET_PROPOSAL_PATH,
        content=b"x" * 65_537,
        headers=_target_proposal_headers(result_ref, proposal_source.proposal_hash),
    )

    assert wrong_mode.status_code == 503
    assert activation.status_code == 400
    assert activation.json()["code"] == "PRODUCTION_RUNTIME_ACTIVATION_HEADER_FORBIDDEN"
    assert oversized.status_code == 413
    assert disabled_service.calls == []
    assert verifier.calls == resolver.calls == service.calls == []


def test_reconciliation_returns_exact_schema_json_without_stream_semantics() -> None:
    command, instance = _command()
    client, verifier, thread_resolver, service = _client(command=command)
    body = json.dumps(instance, separators=(",", ":"))

    response = client.post(PATH, content=body, headers=_headers())

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    assert response.headers["cache-control"] == "no-store, no-transform"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert "x-accel-buffering" not in response.headers
    assert "x-agent-run-id" not in response.headers
    assert "\n" not in response.text
    assert response.json() == ContractCodec(CONTRACT_ROOT).encode(
        "graph-reconcile-response.schema.json",
        _response(command),
    )
    assert len(verifier.calls) == len(thread_resolver.calls) == len(service.calls) == 1
    verified_command = verifier.calls[0][1]
    assert verified_command.model_dump(mode="json", exclude_none=True) == instance
    assert thread_resolver.calls[0][0] is verified_command
    assert service.calls[0][0] is verified_command
    assert service.calls[0][1] is verifier.result


def test_execution_credential_returned_by_wrong_verifier_fails_closed() -> None:
    command, instance = _command()
    execution = _verified_invocation(command)
    forged_reconciliation = VerifiedReconciliation(
        claims=execution.claims,
        key_id=execution.key_id,
        request_hash=execution.request_hash,
        transport_certificate_sha256=execution.transport_certificate_sha256,
    )

    for wrong_credential in (execution, forged_reconciliation):
        client, verifier, thread_resolver, service = _client(
            command=command,
            verifier_result=wrong_credential,
        )

        response = client.post(PATH, content=json.dumps(instance), headers=_headers())

        assert response.status_code == 401
        assert response.json() == {
            "code": "INVOCATION_RECONCILIATION_CREDENTIAL_TYPE_REJECTED",
            "retryable": False,
            "recovery_action": "FAIL_LOGICAL_RUN",
        }
        assert len(verifier.calls) == 1
        assert thread_resolver.calls == []
        assert service.calls == []


def test_duplicate_authorization_is_rejected_before_verifier_or_service() -> None:
    command, instance = _command()
    client, verifier, thread_resolver, service = _client(command=command)

    response = client.post(
        PATH,
        content=json.dumps(instance),
        headers=[
            ("Authorization", "Bearer reconciliation.one.signature"),
            ("Authorization", "Bearer reconciliation.two.signature"),
            ("Content-Type", "application/json"),
        ],
    )

    assert response.status_code == 401
    assert response.json()["code"] == "INVOCATION_AUTHORIZATION_REJECTED"
    assert verifier.calls == []
    assert thread_resolver.calls == []
    assert service.calls == []


@pytest.mark.parametrize(
    ("body_factory", "headers", "status", "code"),
    [
        (
            lambda: b"{}",
            {"Content-Type": "text/plain"},
            415,
            "GRAPH_CONTENT_TYPE_REJECTED",
        ),
        (
            lambda: b"{}",
            {"Content-Type": "application/json; charset=iso-8859-1"},
            415,
            "GRAPH_CONTENT_TYPE_REJECTED",
        ),
        (
            lambda: b"{}",
            {"Content-Type": "application/json", "Content-Encoding": "gzip"},
            415,
            "GRAPH_CONTENT_ENCODING_REJECTED",
        ),
        (
            lambda: "{}".encode("utf-16-le"),
            {"Content-Type": "application/json"},
            400,
            "GRAPH_COMMAND_REJECTED",
        ),
        (
            lambda: b"x" * 65_537,
            {"Content-Type": "application/json"},
            413,
            "GRAPH_COMMAND_TOO_LARGE",
        ),
    ],
)
def test_reconciliation_request_metadata_utf8_and_size_are_strict(
    body_factory: Any,
    headers: dict[str, str],
    status: int,
    code: str,
) -> None:
    command, _ = _command()
    client, verifier, thread_resolver, service = _client(command=command)

    response = client.post(
        PATH,
        content=body_factory(),
        headers={
            "Authorization": "Bearer reconciliation.token.signature",
            **headers,
        },
    )

    assert response.status_code == status
    assert response.json()["code"] == code
    assert response.json()["recovery_action"] == "FAIL_LOGICAL_RUN"
    assert verifier.calls == []
    assert thread_resolver.calls == []
    assert service.calls == []


def test_exact_65536_byte_original_command_body_is_accepted() -> None:
    command, instance = _command()
    client, _, _, service = _client(command=command)
    compact = json.dumps(instance, separators=(",", ":"))
    body = (compact + " " * (65_536 - len(compact.encode("utf-8")))).encode()

    response = client.post(PATH, content=body, headers=_headers())

    assert len(body) == 65_536
    assert response.status_code == 200
    assert len(service.calls) == 1


def test_non_transport_identity_fails_before_credential_verification() -> None:
    command, instance = _command()
    client, verifier, thread_resolver, service = _client(
        command=command,
        identity=object(),
    )

    response = client.post(PATH, content=json.dumps(instance), headers=_headers())

    assert response.status_code == 401
    assert response.json()["code"] == "INVOCATION_MTLS_IDENTITY_REJECTED"
    assert verifier.calls == []
    assert thread_resolver.calls == []
    assert service.calls == []


@pytest.mark.parametrize(
    ("failure", "status", "retryable", "action"),
    [
        (GraphCommandNotFoundError(), 404, False, "FAIL_LOGICAL_RUN"),
        (GraphResultNotCommittedError(), 409, False, "FAIL_LOGICAL_RUN"),
        (
            GraphNewAgentAttemptRequiredError(),
            409,
            False,
            "CREATE_NEXT_ATTEMPT",
        ),
        (GraphCommandCancelledError(), 409, False, "FAIL_LOGICAL_RUN"),
        (GraphCommandAbortedError(), 409, False, "FAIL_LOGICAL_RUN"),
        (GraphCommandHashConflictError(), 409, False, "FAIL_LOGICAL_RUN"),
        (GraphTerminalBindingError(), 409, False, "FAIL_LOGICAL_RUN"),
        (GraphRecoveryError(), 409, False, "FAIL_LOGICAL_RUN"),
        (GraphNonceReplayError(), 409, True, "RETRY_SAME_COMMAND"),
        (GraphGatewayDisabledError(), 503, True, "RETRY_SAME_COMMAND"),
        (GraphLeaseUnavailableError(), 503, True, "RETRY_SAME_COMMAND"),
        (GraphLeaseLostError(), 503, True, "RETRY_SAME_COMMAND"),
    ],
)
def test_reconciliation_error_taxonomy_is_closed(
    failure: Exception,
    status: int,
    retryable: bool,
    action: str,
) -> None:
    command, instance = _command()
    client, _, _, service = _client(
        command=command,
        service_failure=failure,
    )

    response = client.post(PATH, content=json.dumps(instance), headers=_headers())

    assert response.status_code == status
    assert response.json() == {
        "code": failure.code,
        "retryable": retryable,
        "recovery_action": action,
    }
    assert action in {
        "RETRY_SAME_COMMAND",
        "CREATE_NEXT_ATTEMPT",
        "RECONCILE_TERMINAL",
        "FAIL_LOGICAL_RUN",
    }
    assert len(service.calls) == 1


def test_unclassified_service_failure_is_not_automatically_retried() -> None:
    command, instance = _command()
    client, _, _, service = _client(
        command=command,
        service_failure=RuntimeError("private programming error"),
    )

    response = client.post(PATH, content=json.dumps(instance), headers=_headers())

    assert response.status_code == 500
    assert response.json() == {
        "code": "GRAPH_RECONCILIATION_INTERNAL_ERROR",
        "retryable": False,
        "recovery_action": "FAIL_LOGICAL_RUN",
    }
    assert "private programming error" not in response.text
    assert len(service.calls) == 1


def test_target_reconciliation_reads_exact_durable_result_while_admission_is_unavailable() -> None:
    command = _target_command()
    activation_id = f"p9act.v1.{'a' * 32}"
    command_hash = production_runtime_command_hash(command)
    envelope_values = {
        "schema_version": "production-runtime-graph-command-envelope.v1",
        "execution_lane": "PRODUCTION",
        "activation_id": activation_id,
        "room_fencing_token": 19,
        "command_hash": command_hash,
        "command": command.model_dump(mode="json", exclude_none=True),
    }
    command_envelope = ProductionGraphCommandEnvelope.model_validate(
        {
            **envelope_values,
            "command_envelope_hash": canonical_sha256(envelope_values),
        }
    )
    binding = GraphProductionBindingSettings(
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        state_schema_version="target-state.v1",
        state_schema_hash="1" * 64,
        command_schema_version="room-graph-command.v1",
        result_schema_version="room-graph-result.v1",
        agent_profile_id=command.invocation_context.agent_profile_id,
        prompt_version=command.invocation_context.prompt_profile_id,
        model_profile_id=command.invocation_context.model_profile_id,
            output_schema_version="production-runtime-room-proposal-source.v1",
        policy_version=command.invocation_context.policy_version,
        guardrail_version=command.invocation_context.guardrail_version,
            tool_policy_version="tools.none.v1",
        binding_hash="2" * 64,
        code_build_id="target-build-1",
            allowed_room_types=("INTAKE", "EVIDENCE", "HEARING", "REVIEW"),
        allowed_stage_codes=(command.stage_code,),
    )
    context = GraphProductionRuntimeContextSettings.model_validate(
        {
            "schemaVersion": "production-runtime-context.v1",
            "executionLane": "PRODUCTION",
            "activationId": activation_id,
            "activationManifestHash": "f" * 64,
            "environmentId": "production-runtime-test",
            "environmentGeneration": 7,
            "candidateSha": "3" * 40,
            "issuedAt": "2026-07-27T10:00:00Z",
            "expiresAt": "2026-07-27T11:00:00Z",
            "runNonce": "runtime-projection-nonce-0123456789abcdef",
            "tenantSurrogate": command.tenant_surrogate,
            "caseScope": {
                "mode": "EXPLICIT_CASE_IDS",
                "allowedCaseIds": [command.case_id],
            },
                "allowedRoomTypes": ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"],
            "composeProject": "p9_production_runtime",
            "temporalNamespace": "production-runtime-test",
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
                    "database": "production_domain",
                    "schema": "domain_runtime",
                    "expectedUser": "java_domain_runtime",
                },
                "graph": {
                    "service": "graph-db",
                    "database": "production_graph",
                    "schema": "graph_runtime",
                    "runtimeUser": "graph_runtime",
                    "environmentGeneration": 7,
                    "restoreVerificationHash": "6" * 64,
                },
            },
            "trustedSigningKeyIds": [command.invocation_context.envelope_key_id],
            "perCommandManifestAllowed": False,
        }
    )
    authority = ProductionRuntimeAuthority.from_context(context, (binding,))
    claims = ProductionInvocationClaims(
        iss="java-api-service",
        aud="python-agent-service",
        sub="graph-command",
        iat=1_752_739_200,
        nbf=1_752_739_200,
        exp=1_752_739_260,
        jti="target-reconcile-jti-001",
        **invocation_binding_claims(command),
        execution_lane="PRODUCTION",
        activation_id=activation_id,
        room_fencing_token=19,
        command_hash=command_hash,
        command_envelope_hash=command_envelope.command_envelope_hash,
    )
    verified = VerifiedProductionInvocation(
        claims=claims,
        key_id=command.invocation_context.envelope_key_id,
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
        authority=authority,
        command_hash=command_hash,
        command_envelope_hash=command_envelope.command_envelope_hash,
        room_fencing_token=19,
    )
    nested_values = _response(command).result.model_dump(mode="json", exclude_none=True)
    nested_values["output_hash"] = canonical_sha256_omitting(
        nested_values,
        "output_hash",
    )
    nested = RoomGraphResult.model_validate(nested_values)
    result_values = {
        "schema_version": "production-runtime-graph-result-envelope.v1",
        "execution_lane": "PRODUCTION",
        "activation_id": activation_id,
        "room_fencing_token": 19,
        "command_hash": command_hash,
        "command_envelope_hash": command_envelope.command_envelope_hash,
        "execution_provider": "production-runtime-composite",
        "execution_model": "room-provider-dispatch",
        "result_hash": nested.output_hash,
        "proposal_hash": "7" * 64,
        "graph_output_authority": "PROPOSAL_ONLY",
        "result": nested.model_dump(mode="json", exclude_none=True),
    }
    result_envelope = ProductionGraphResultEnvelope.model_validate(
        {
            **result_values,
            "result_envelope_hash": canonical_sha256(result_values),
        }
    )

    class TargetVerifier:
        def verify_envelope(self, **_: Any) -> VerifiedProductionInvocation:
            return verified

        def verify_envelope_for_reconciliation(
            self,
            **_: Any,
        ) -> VerifiedProductionInvocation:
            return verified

    class TargetThreadResolver:
        async def resolve(self, **_: Any) -> ThreadIdentity:
            return _thread(command)

    class TargetService(ReconciliationService):
        async def reconcile_production_runtime(self, **_: Any) -> Any:
            from app.api.graph_reconciliation_service import ProductionReconciliationArtifacts

            return ProductionReconciliationArtifacts(
                envelope=result_envelope,
                result_ref="urn:graph-result:1",
                result_hash=result_envelope.result_hash,
                proposal_hash=result_envelope.proposal_hash,
            )

    service = TargetService(_response(command))

    def forbidden_command_admission_readiness() -> bool:
        raise AssertionError("durable reconciliation must not consult command admission readiness")

    app = FastAPI()
    app.include_router(
        create_graph_reconciliation_router(
            GraphReconciliationEndpointDependencies(
                mode="PRODUCTION",
                codec=ContractCodec(CONTRACT_ROOT),
                transport_identity_resolver=IdentityResolver(),
                envelope_verifier=Verifier(_verified_reconciliation(command)),
                thread_identity_resolver=ThreadResolver(),
                reconciliation_service=service,
                ready=forbidden_command_admission_readiness,
                production_runtime_envelope_verifier=TargetVerifier(),
                production_runtime_thread_identity_resolver=TargetThreadResolver(),
            )
        )
    )
    response = TestClient(app).post(
        TARGET_PATH,
        content=json.dumps(command_envelope.model_dump(mode="json")),
        headers=_headers(),
    )

    assert response.status_code == 200
    assert response.json() == result_envelope.model_dump(mode="json", exclude_none=True)
    assert response.headers["X-Graph-Result-Ref"] == "urn:graph-result:1"
    assert response.headers["X-Graph-Result-Hash"] == result_envelope.result_hash
    assert response.headers["X-Graph-Proposal-Hash"] == result_envelope.proposal_hash
    assert "proposal" not in response.json()
