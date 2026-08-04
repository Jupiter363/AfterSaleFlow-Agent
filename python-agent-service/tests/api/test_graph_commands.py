from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Mapping
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
from fastapi.testclient import TestClient
from psycopg import OperationalError
from psycopg.errors import DiskFull, LockNotAvailable, ProtocolViolation
from psycopg_pool import PoolTimeout

from app.api.graph_commands import (
    AgentStreamProtocolError,
    AgentStreamProtocolValidator,
    GraphCommandEndpointDependencies,
    _encode_event,
    _log_safe_failure,
    _stream_ndjson,
    create_graph_commands_router,
)
from app.contracts.v1.codec import (
    ContractCodec,
    canonical_sha256,
    canonical_sha256_omitting,
)
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    RoomGraphCommand,
    Usage,
)
from app.graph_runtime.errors import (
    GraphContractError,
    GraphLeaseLostError,
    GraphNewAgentAttemptRequiredError,
)
from app.graphs.intake.errors import IntakeGraphContractError
from app.graph_runtime.identity import ActorScopeBinding, RoomType, ThreadIdentity
from app.graph_runtime.target_e2e import (
    TargetE2EGraphCommandEnvelope,
    VerifiedTargetE2EInvocation,
    target_e2e_command_hash,
)
from app.security.invocation_envelope import (
    InvocationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    VerifiedInvocation,
    invocation_binding_claims,
)


ROOT = Path(__file__).resolve().parents[3]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/v1"
KID = "java-invocation-es256-1"


class KeyResolver:
    def __init__(self, key: Any) -> None:
        self.key = key

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        assert kid == KID
        return ResolvedVerificationKey(kid=kid, public_key=self.key)


class IdentityResolver:
    def resolve(self, scope: Mapping[str, Any]) -> TransportIdentity:
        return TransportIdentity(
            service_id="java-api-service",
            authenticated=True,
            certificate_sha256="c" * 64,
        )


class FakeStreamService:
    def __init__(
        self,
        events: tuple[AgentStreamEvent, ...],
        *,
        failure_before: Exception | None = None,
        failure_after: Exception | None = None,
    ) -> None:
        self.events = events
        self.failure_before = failure_before
        self.failure_after = failure_after
        self.closed = False
        self.calls: list[tuple[RoomGraphCommand, VerifiedInvocation, ThreadIdentity]] = []

    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
        expected_thread: ThreadIdentity,
    ) -> AsyncIterator[AgentStreamEvent]:
        self.calls.append((command, verified_invocation, expected_thread))

        async def emit() -> AsyncIterator[AgentStreamEvent]:
            try:
                if self.failure_before is not None:
                    raise self.failure_before
                for event in self.events:
                    yield event
                if self.failure_after is not None:
                    raise self.failure_after
            finally:
                self.closed = True

        return emit()


async def _collect_validated_ndjson(
    command: RoomGraphCommand,
    events: tuple[AgentStreamEvent, ...],
) -> list[dict[str, Any]]:
    async def remaining() -> AsyncIterator[AgentStreamEvent]:
        for event in events[1:]:
            yield event

    validator = AgentStreamProtocolValidator(
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        audience=command.actor_scope.audience,
    )
    codec = ContractCodec(CONTRACT_ROOT)
    first_line = _encode_event(codec, validator, events[0])
    return [
        json.loads(line)
        async for line in _stream_ndjson(
            codec=codec,
            iterator=remaining(),
            validator=validator,
            first_line=first_line,
        )
    ]


class ThreadResolver:
    async def resolve(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
    ) -> ThreadIdentity:
        assert verified_invocation.request_hash == command.request_hash
        return ThreadIdentity(
            thread_id=command.thread_id,
            tenant_surrogate=command.tenant_surrogate,
            case_id=command.case_id,
            room_type=RoomType(command.room_type),
            room_epoch=command.room_epoch,
            actor_scope=ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json")),
            agent_session_id="trusted-agent-session-1",
            shared_session=False,
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_schema_version=command.checkpoint_schema_version,
        )


class TargetVerifier:
    def __init__(
        self,
        envelope: TargetE2EGraphCommandEnvelope,
        verified: VerifiedTargetE2EInvocation,
    ) -> None:
        self.envelope = envelope
        self.verified = verified

    def verify_envelope(self, **kwargs: Any) -> VerifiedTargetE2EInvocation:
        assert kwargs["envelope"] == self.envelope
        return self.verified


def test_log_safe_failure_records_stable_intake_error_code(
    caplog: pytest.LogCaptureFixture,
) -> None:
    error_code = "INTAKE_CONTRACT_REJECTED"

    _log_safe_failure("intake contract validation", IntakeGraphContractError(error_code))

    assert caplog.messages == [
        "intake contract validation failed: "
        "error_type=IntakeGraphContractError "
        f"error_code={error_code}"
    ]


@pytest.mark.parametrize(
    "message",
    (
        "model output with spaces",
        "https://example.test/failure?token=secret",
        "api_token=super-secret",
        "first line\nsecond line",
        "API_TOKEN_SECRET",
        "INTAKE_API_TOKEN_SECRET",
    ),
)
def test_log_safe_failure_omits_unstable_error_messages(
    caplog: pytest.LogCaptureFixture,
    message: str,
) -> None:
    _log_safe_failure("intake contract validation", ValueError(message))

    assert caplog.messages == ["intake contract validation failed: error_type=ValueError"]
    assert message not in caplog.text


def _command() -> tuple[RoomGraphCommand, dict[str, Any]]:
    vector = json.loads(
        (CONTRACT_ROOT / "fixtures/canonical-hash/room-graph-command-self-hash.json").read_text(
            encoding="utf-8"
        )
    )
    instance = {**vector["input"], "request_hash": vector["sha256"]}
    return RoomGraphCommand.model_validate(instance), instance


def _event(command: RoomGraphCommand, event_type: str, sequence: int) -> AgentStreamEvent:
    if event_type == "attempt_started":
        payload = AgentStreamPayload(node="intake.reason")
    elif event_type == "visible_delta":
        payload = AgentStreamPayload(
            node="intake.reason",
            field="room_utterance",
            delta="visible",
        )
    elif event_type == "usage":
        payload = AgentStreamPayload(usage=Usage(input_tokens=10, output_tokens=5, total_tokens=15))
    elif event_type == "attempt_aborted":
        payload = AgentStreamPayload(reason_code="PROVIDER_TRANSPORT_LOST")
    elif event_type == "attempt_reset":
        payload = AgentStreamPayload(
            reset_attempt_id="attempt-previous",
            reason_code="RETRY",
        )
    elif event_type == "final":
        payload = AgentStreamPayload(
            final_result_ref="urn:graph-result:result-1",
            final_result_hash="d" * 64,
        )
    elif event_type == "error":
        payload = AgentStreamPayload(error_code="GRAPH_FAILED", retryable=False)
    else:
        raise AssertionError(event_type)
    return AgentStreamEvent(
        schema_version="agent-stream.v2",
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        sequence_no=sequence,
        event_type=event_type,
        audience=command.actor_scope.audience,
        occurred_at=datetime.now(timezone.utc),
        payload=payload,
    )


def _token(command: RoomGraphCommand, private_key: Any) -> str:
    now = int(datetime.now(timezone.utc).timestamp())
    claims = {
        "iss": "java-api-service",
        "aud": "python-agent-service",
        "sub": "graph-command",
        "iat": now,
        "nbf": now,
        "exp": now + 60,
        "jti": "transport-jti-1",
        **invocation_binding_claims(command),
    }
    return jwt.encode(
        claims,
        private_key,
        algorithm="ES256",
        headers={"alg": "ES256", "kid": KID, "typ": "graph-command+jwt"},
    )


def _client(
    *,
    command: RoomGraphCommand,
    private_key: Any,
    service: FakeStreamService,
    mode: str = "SHADOW",
    ready: bool = True,
) -> TestClient:
    app = FastAPI()
    app.include_router(
        create_graph_commands_router(
            GraphCommandEndpointDependencies(
                mode=mode,
                codec=ContractCodec(CONTRACT_ROOT),
                transport_identity_resolver=IdentityResolver(),
                envelope_verifier=InvocationEnvelopeVerifier(
                    key_resolver=KeyResolver(private_key.public_key())
                ),
                thread_identity_resolver=ThreadResolver(),
                stream_service=service,
                ready=lambda: ready,
            )
        )
    )
    return TestClient(app, raise_server_exceptions=False)


def _target_client(
    *,
    envelope: TargetE2EGraphCommandEnvelope,
    service: FakeStreamService,
) -> TestClient:
    command = envelope.command
    verified = VerifiedTargetE2EInvocation(
        claims=object(),  # type: ignore[arg-type]
        key_id=KID,
        request_hash=command.request_hash,
        transport_certificate_sha256="c" * 64,
        authority=object(),  # type: ignore[arg-type]
        command_hash=envelope.command_hash,
        command_envelope_hash=envelope.command_envelope_hash,
        room_fencing_token=envelope.room_fencing_token,
    )
    app = FastAPI()
    app.include_router(
        create_graph_commands_router(
            GraphCommandEndpointDependencies(
                mode="TARGET_E2E_CANDIDATE",
                codec=ContractCodec(CONTRACT_ROOT),
                transport_identity_resolver=IdentityResolver(),
                envelope_verifier=object(),  # type: ignore[arg-type]
                thread_identity_resolver=ThreadResolver(),
                stream_service=service,
                ready=lambda: True,
                target_e2e_envelope_verifier=TargetVerifier(envelope, verified),
            )
        )
    )
    return TestClient(app, raise_server_exceptions=False)


def _target_envelope(command: RoomGraphCommand) -> TargetE2EGraphCommandEnvelope:
    values = {
        "schema_version": "target-e2e-graph-command-envelope.v1",
        "execution_lane": "TARGET_E2E_CANDIDATE",
        "activation_id": "p9act.v1." + ("a" * 32),
        "room_fencing_token": 7,
        "command_hash": target_e2e_command_hash(command),
        "command": command.model_dump(mode="json", exclude_none=True),
    }
    return TargetE2EGraphCommandEnvelope.model_validate(
        {**values, "command_envelope_hash": canonical_sha256(values)}
    )


def test_signed_command_streams_only_validated_agent_stream_v2_events() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (_event(command, "attempt_started", 0), _event(command, "final", 1))
    )
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance, separators=(",", ":")),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json; charset=utf-8",
        },
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/x-ndjson")
    assert response.headers["cache-control"] == "no-store, no-transform"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-accel-buffering"] == "no"
    assert response.headers["x-agent-run-id"] == command.logical_run_id
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in events] == [
        (0, "attempt_started"),
        (1, "final"),
    ]
    assert len(service.calls) == 1
    assert service.calls[0][1].request_hash == command.request_hash
    assert service.calls[0][2].agent_session_id == "trusted-agent-session-1"


def test_graph_command_endpoint_rejects_bootstrap_activation_header() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(())
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance, separators=(",", ":")),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json; charset=utf-8",
            "X-AfterSaleFlow-Target-E2E-Activation": "forbidden",
        },
    )

    assert response.status_code == 400
    assert response.json()["code"] == "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN"
    assert service.calls == []


def test_duplicate_json_member_is_rejected_before_security_or_gateway_dispatch() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(())
    client = _client(command=command, private_key=private_key, service=service)
    raw = json.dumps(instance, separators=(",", ":"))
    duplicate = raw[:-1] + ',"command_id":"duplicate"}'

    response = client.post(
        "/internal/graphs/commands/stream",
        content=duplicate,
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 400
    assert response.json() == {"code": "GRAPH_COMMAND_REJECTED", "retryable": False}
    assert service.calls == []


def test_nested_duplicate_json_member_is_rejected_before_gateway_dispatch() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(())
    client = _client(command=command, private_key=private_key, service=service)
    raw = json.dumps(instance, separators=(",", ":"))
    duplicate = raw.replace(
        '"audience":"USER"',
        '"audience":"USER","audience":"SYSTEM"',
        1,
    )

    response = client.post(
        "/internal/graphs/commands/stream",
        content=duplicate,
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 400
    assert response.json()["code"] == "GRAPH_COMMAND_REJECTED"
    assert service.calls == []


def test_body_limit_mode_readiness_and_authorization_fail_closed() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(())

    disabled = _client(
        command=command,
        private_key=private_key,
        service=service,
        mode="DISABLED",
    ).post(
        "/internal/graphs/commands/stream",
        content=b"{}",
        headers={"Content-Type": "application/json"},
    )
    unavailable = _client(
        command=command,
        private_key=private_key,
        service=service,
        ready=False,
    ).post(
        "/internal/graphs/commands/stream",
        content=b"{}",
        headers={"Content-Type": "application/json"},
    )
    oversized = _client(
        command=command,
        private_key=private_key,
        service=service,
    ).post(
        "/internal/graphs/commands/stream",
        content=b"x" * 65_537,
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )
    unsigned = _client(
        command=command,
        private_key=private_key,
        service=service,
    ).post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={"Content-Type": "application/json"},
    )

    assert disabled.status_code == 503
    assert disabled.json()["code"] == "GRAPH_GATEWAY_DISABLED"
    assert unavailable.status_code == 503
    assert unavailable.json()["code"] == "GRAPH_GATEWAY_NOT_READY"
    assert oversized.status_code == 413
    assert oversized.json()["code"] == "GRAPH_COMMAND_TOO_LARGE"
    assert unsigned.status_code == 401
    assert unsigned.json()["code"] == "INVOCATION_AUTHORIZATION_MISSING"
    for response in (disabled, unavailable, oversized, unsigned):
        assert response.headers["cache-control"] == "no-store, no-transform"
        assert response.headers["x-content-type-options"] == "nosniff"
    assert service.calls == []


def test_exact_request_limit_is_accepted_without_changing_the_signed_json_value() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (_event(command, "attempt_started", 0), _event(command, "final", 1))
    )
    client = _client(command=command, private_key=private_key, service=service)
    compact = json.dumps(instance, separators=(",", ":"))
    body = (compact + (" " * (65_536 - len(compact.encode("utf-8"))))).encode("utf-8")

    response = client.post(
        "/internal/graphs/commands/stream",
        content=body,
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert len(body) == 65_536
    assert response.status_code == 200
    assert [json.loads(line)["event_type"] for line in response.text.splitlines()] == [
        "attempt_started",
        "final",
    ]


@pytest.mark.parametrize(
    ("body_factory", "extra_headers", "status", "code"),
    [
        (
            lambda instance: json.dumps(instance).encode("utf-16-le"),
            {},
            400,
            "GRAPH_COMMAND_REJECTED",
        ),
        (
            lambda instance: json.dumps(instance).encode("utf-8"),
            {"Content-Type": "application/json; charset=iso-8859-1"},
            415,
            "GRAPH_CONTENT_TYPE_REJECTED",
        ),
        (
            lambda instance: json.dumps(instance).encode("utf-8"),
            {"Content-Type": "application/json; charset*=utf-8''utf-8"},
            415,
            "GRAPH_CONTENT_TYPE_REJECTED",
        ),
        (
            lambda instance: json.dumps(instance).encode("utf-8"),
            {"Content-Type": "application/json; profile=graph-command"},
            415,
            "GRAPH_CONTENT_TYPE_REJECTED",
        ),
        (
            lambda instance: json.dumps(instance).encode("utf-8"),
            {"Content-Encoding": "gzip"},
            415,
            "GRAPH_CONTENT_ENCODING_REJECTED",
        ),
        (
            lambda instance: json.dumps(instance).encode("utf-8"),
            {"Content-Length": "1"},
            400,
            "GRAPH_COMMAND_REJECTED",
        ),
    ],
)
def test_request_bytes_content_metadata_and_length_are_strict(
    body_factory: Any,
    extra_headers: dict[str, str],
    status: int,
    code: str,
) -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(())
    client = _client(command=command, private_key=private_key, service=service)
    headers = {
        "Authorization": f"Bearer {_token(command, private_key)}",
        "Content-Type": "application/json; charset=utf-8",
        **extra_headers,
    }

    response = client.post(
        "/internal/graphs/commands/stream",
        content=body_factory(instance),
        headers=headers,
    )

    assert response.status_code == status
    assert response.json()["code"] == code
    assert service.calls == []


def test_signed_envelope_is_bound_to_the_exact_self_hashed_body() -> None:
    command, _ = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(())
    client = _client(command=command, private_key=private_key, service=service)
    tampered = command.model_copy(update={"case_id": "case-forged"})
    tampered = tampered.model_copy(
        update={"request_hash": canonical_sha256_omitting(tampered, "request_hash")}
    )

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(
            tampered.model_dump(mode="json", exclude_none=True),
            separators=(",", ":"),
        ),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 401
    assert response.json()["code"] == "INVOCATION_REQUEST_HASH_MISMATCH"
    assert service.calls == []


def test_invalid_first_event_is_rejected_before_stream_headers_are_sent() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService((_event(command, "final", 0),))
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 502
    assert response.json()["code"] == "GRAPH_STREAM_PROTOCOL_REJECTED"
    assert service.closed is True


def test_gateway_contract_error_before_first_event_keeps_public_runtime_mapping() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (),
        failure_before=GraphContractError("private binding detail"),
    )
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 409
    assert response.json() == {"code": "GRAPH_CONTRACT_REJECTED", "retryable": False}
    assert "private binding detail" not in response.text
    assert service.closed is True


@pytest.mark.parametrize(
    "failure",
    [
        LockNotAvailable("private lock holder detail"),
        PoolTimeout("private pool state"),
        OperationalError("private connection timeout"),
    ],
)
def test_transient_persistence_failure_before_first_event_returns_retryable_503(
    failure: Exception,
) -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService((), failure_before=failure)
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 503
    assert response.json() == {"code": "GRAPH_LEASE_UNAVAILABLE", "retryable": True}
    assert "private" not in response.text
    assert service.closed is True


def test_target_e2e_lock_contention_before_first_event_returns_retryable_503() -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    service = FakeStreamService(
        (),
        failure_before=LockNotAvailable("private target lock holder detail"),
    )
    client = _target_client(envelope=envelope, service=service)

    response = client.post(
        "/internal/graphs/target-e2e/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 503
    assert response.json() == {"code": "GRAPH_LEASE_UNAVAILABLE", "retryable": True}
    assert "private" not in response.text
    assert service.closed is True


@pytest.mark.parametrize(
    "failure",
    [
        DiskFull("private storage detail"),
        ProtocolViolation("private protocol detail"),
    ],
)
def test_unclassified_database_failure_before_first_event_remains_nonretryable(
    failure: Exception,
) -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService((), failure_before=failure)
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 500
    assert response.json() == {"code": "GRAPH_STREAM_INTERNAL_ERROR", "retryable": False}
    assert "private" not in response.text
    assert service.closed is True


def test_started_public_attempt_returns_the_explicit_new_attempt_contract() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (),
        failure_before=GraphNewAgentAttemptRequiredError(
            "PUBLIC_ATTEMPT_EXECUTION_ALREADY_STARTED"
        ),
    )
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 409
    assert response.json() == {
        "code": "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED",
        "retryable": True,
    }
    assert service.closed is True


def test_attempt_aborted_is_a_valid_attempt_terminal_event() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (
            _event(command, "attempt_started", 0),
            _event(command, "attempt_aborted", 1),
        )
    )
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [event["event_type"] for event in events] == [
        "attempt_started",
        "attempt_aborted",
    ]
    assert service.closed is True


@pytest.mark.parametrize(
    ("failure", "terminal_type", "terminal_payload"),
    [
        (
            GraphLeaseLostError("private lease detail"),
            "attempt_aborted",
            {"reason_code": "GRAPH_LEASE_LOST"},
        ),
        (
            GraphContractError("private binding detail"),
            "error",
            {"error_code": "GRAPH_CONTRACT_REJECTED", "retryable": False},
        ),
        (
            RuntimeError("private provider response"),
            "error",
            {"error_code": "GRAPH_STREAM_INTERNAL_ERROR", "retryable": False},
        ),
        (
            DiskFull("private storage detail"),
            "error",
            {"error_code": "GRAPH_STREAM_INTERNAL_ERROR", "retryable": False},
        ),
    ],
)
def test_failure_after_headers_emits_one_safe_terminal_event_and_closes_iterator(
    failure: Exception,
    terminal_type: str,
    terminal_payload: dict[str, Any],
) -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=failure,
    )
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in events] == [
        (0, "attempt_started"),
        (1, terminal_type),
    ]
    assert events[-1]["payload"] == terminal_payload
    if events[-1]["event_type"] == "error":
        assert events[-1]["payload"]["retryable"] is False
    decoded = ContractCodec(CONTRACT_ROOT).decode(
        "agent-stream-event.schema.json",
        json.dumps(events[-1], separators=(",", ":")),
    )
    assert isinstance(decoded, AgentStreamEvent)
    assert "private" not in response.text
    assert service.closed is True


def test_target_e2e_retryable_runtime_failure_requests_a_new_attempt_in_band() -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=GraphLeaseLostError("private target lease detail"),
    )
    client = _target_client(envelope=envelope, service=service)

    response = client.post(
        "/internal/graphs/target-e2e/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    assert response.headers["x-graph-execution-lane"] == "TARGET_E2E_CANDIDATE"
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in events] == [
        (0, "attempt_started"),
        (1, "attempt_aborted"),
    ]
    assert events[-1]["payload"] == {"reason_code": "GRAPH_LEASE_LOST"}
    assert service.closed is True


def test_target_e2e_lock_contention_after_first_event_aborts_the_attempt() -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=LockNotAvailable("private target lock holder detail"),
    )
    client = _target_client(envelope=envelope, service=service)

    response = client.post(
        "/internal/graphs/target-e2e/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in events] == [
        (0, "attempt_started"),
        (1, "attempt_aborted"),
    ]
    assert events[-1]["payload"] == {"reason_code": "GRAPH_LEASE_UNAVAILABLE"}
    assert "private" not in response.text
    assert service.closed is True


@pytest.mark.parametrize(
    "events",
    [
        lambda command: (_event(command, "attempt_started", 0),),
        lambda command: (
            _event(command, "attempt_started", 0),
            _event(command, "final", 1),
            _event(command, "visible_delta", 2),
        ),
        lambda command: (
            _event(command, "attempt_started", 0),
            _event(command, "visible_delta", 2),
        ),
    ],
)
def test_truncated_extra_terminal_and_gapped_streams_end_with_one_protocol_error(
    events: Any,
) -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(events(command))
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    decoded = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in decoded] == [
        (0, "attempt_started"),
        (1, "error"),
    ]
    assert decoded[-1]["payload"] == {
        "error_code": "GRAPH_STREAM_PROTOCOL_REJECTED",
        "retryable": False,
    }
    assert service.closed is True


def test_twenty_independent_runs_accept_eighty_seven_deltas_then_one_usage_and_final() -> None:
    async def scenario() -> None:
        template, _ = _command()

        for round_no in range(1, 21):
            command = template.model_copy(
                update={
                    "logical_run_id": f"target-intake-run:long-stream:{round_no:02d}",
                    "attempt_id": f"target-intake-attempt:long-stream:{round_no:02d}",
                }
            )
            events = (
                _event(command, "attempt_started", 0),
                *(
                    _event(command, "visible_delta", sequence)
                    for sequence in range(1, 88)
                ),
                _event(command, "usage", 88),
                _event(command, "final", 89),
            )

            decoded = await _collect_validated_ndjson(command, events)

            assert [event["sequence_no"] for event in decoded] == list(range(90))
            assert decoded[0]["event_type"] == "attempt_started"
            assert [event["event_type"] for event in decoded[1:88]] == [
                "visible_delta"
            ] * 87
            assert [event["event_type"] for event in decoded].count("usage") == 1
            assert [event["event_type"] for event in decoded].count("final") == 1
            assert [event["event_type"] for event in decoded].count("error") == 0
            assert decoded[-2]["event_type"] == "usage"
            assert decoded[-1]["event_type"] == "final"
            assert {event["run_id"] for event in decoded} == {command.logical_run_id}
            assert {event["attempt_id"] for event in decoded} == {command.attempt_id}

    asyncio.run(scenario())


def test_eighty_seven_deltas_without_usage_or_final_end_in_one_safe_protocol_error() -> None:
    async def scenario() -> None:
        template, _ = _command()
        command = template.model_copy(
            update={
                "logical_run_id": "target-intake-run:long-stream:truncated",
                "attempt_id": "target-intake-attempt:long-stream:truncated",
            }
        )
        events = (
            _event(command, "attempt_started", 0),
            *(
                _event(command, "visible_delta", sequence)
                for sequence in range(1, 88)
            ),
        )

        decoded = await _collect_validated_ndjson(command, events)

        assert [event["sequence_no"] for event in decoded] == list(range(89))
        assert [event["event_type"] for event in decoded].count("visible_delta") == 87
        assert [event["event_type"] for event in decoded].count("usage") == 0
        assert [event["event_type"] for event in decoded].count("final") == 0
        assert [event["event_type"] for event in decoded].count("error") == 1
        assert decoded[-1]["event_type"] == "error"
        assert decoded[-1]["payload"] == {
            "error_code": "GRAPH_STREAM_PROTOCOL_REJECTED",
            "retryable": False,
        }

    asyncio.run(scenario())


def test_stream_protocol_rejects_identity_sequence_payload_and_missing_terminal() -> None:
    command, _ = _command()
    validator = AgentStreamProtocolValidator(
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        audience=command.actor_scope.audience,
    )
    validator.accept(_event(command, "attempt_started", 0))

    duplicate = _event(command, "attempt_started", 1)
    with pytest.raises(AgentStreamProtocolError, match="another attempt_started"):
        validator.accept(duplicate)

    with pytest.raises(AgentStreamProtocolError, match="without a terminal"):
        validator.finish()

    with pytest.raises(AgentStreamProtocolError, match="sequence"):
        validator.accept(_event(command, "visible_delta", 2))

    terminal = _event(command, "attempt_aborted", 1)
    validator.accept(terminal)
    validator.finish()

    with pytest.raises(AgentStreamProtocolError, match="terminal state"):
        validator.accept(_event(command, "final", 2))


def test_stream_protocol_rejects_payload_siblings_and_python_reset() -> None:
    command, _ = _command()
    validator = AgentStreamProtocolValidator(
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        audience=command.actor_scope.audience,
    )
    validator.accept(_event(command, "attempt_started", 0))
    incompatible = _event(command, "visible_delta", 1).model_copy(
        update={
            "payload": AgentStreamPayload(
                node="intake.reason",
                field="room_utterance",
                delta="visible",
                reason_code="SHOULD_NOT_BE_PRESENT",
            )
        }
    )
    with pytest.raises(AgentStreamProtocolError, match="incompatible"):
        validator.accept(incompatible)

    python_reset = _event(command, "attempt_reset", 1)
    with pytest.raises(AgentStreamProtocolError, match="Java attempt reset authority"):
        validator.accept(python_reset)

    forged_type = _event(command, "visible_delta", 1).model_copy(
        update={"event_type": "hidden_reasoning"}
    )
    with pytest.raises(AgentStreamProtocolError, match="envelope"):
        validator.accept(forged_type)

    wrong_identity = _event(command, "visible_delta", 1).model_copy(
        update={"run_id": "another-run"}
    )
    with pytest.raises(AgentStreamProtocolError, match="identity"):
        validator.accept(wrong_identity)


def test_python_attempt_reset_is_never_forwarded_to_java() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (
            _event(command, "attempt_started", 0),
            _event(command, "attempt_reset", 1),
            _event(command, "final", 2),
        )
    )
    client = _client(command=command, private_key=private_key, service=service)

    response = client.post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in events] == [
        (0, "attempt_started"),
        (1, "error"),
    ]
    assert events[-1]["payload"] == {
        "error_code": "GRAPH_STREAM_PROTOCOL_REJECTED",
        "retryable": False,
    }
    assert service.closed is True


@pytest.mark.asyncio
async def test_response_body_cancellation_closes_the_prefetched_source_iterator() -> None:
    command, _ = _command()
    closed = False

    async def source() -> AsyncIterator[AgentStreamEvent]:
        nonlocal closed
        try:
            yield _event(command, "attempt_started", 0)
            await asyncio.Event().wait()
        finally:
            closed = True

    iterator = source()
    validator = AgentStreamProtocolValidator(
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        audience=command.actor_scope.audience,
    )
    codec = ContractCodec(CONTRACT_ROOT)
    first_line = _encode_event(codec, validator, await anext(iterator))
    body = _stream_ndjson(
        codec=codec,
        iterator=iterator,
        validator=validator,
        first_line=first_line,
    )

    assert await anext(body) == first_line
    await body.aclose()

    assert closed is True
