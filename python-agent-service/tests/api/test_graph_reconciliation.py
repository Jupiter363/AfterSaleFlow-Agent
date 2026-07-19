from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.graph_commands import (
    GraphReconciliationEndpointDependencies,
    create_graph_reconciliation_router,
)
from app.contracts.v1.codec import ContractCodec
from app.contracts.v1.models import GraphReconcileResponse, RoomGraphCommand
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
from app.security.invocation_envelope import (
    InvocationClaims,
    ReconciliationClaims,
    TransportIdentity,
    VerifiedInvocation,
    VerifiedReconciliation,
    invocation_binding_claims,
)


ROOT = Path(__file__).resolve().parents[3]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/v1"
COMMAND_FIXTURE = (
    CONTRACT_ROOT / "fixtures/valid/room-graph-command-valid.json"
)
RESPONSE_FIXTURE = (
    CONTRACT_ROOT / "fixtures/valid/graph-reconcile-response-valid.json"
)
PATH = "/internal/graphs/commands/reconcile"


def _command() -> tuple[RoomGraphCommand, dict[str, Any]]:
    instance = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    return RoomGraphCommand.model_validate(instance), instance


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
