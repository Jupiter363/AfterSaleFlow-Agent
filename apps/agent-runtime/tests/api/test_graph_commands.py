from __future__ import annotations

import asyncio
import base64
import json
from collections.abc import AsyncIterator, Mapping
from datetime import datetime, timezone
from enum import Enum
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
from app.api.intake_parallel_stream import (
    ExpectedParallelFrame,
    OpenedParallelFrameStream,
    ParallelFrameAdmissionReceipt,
    ParallelFrameFailureTerminationReceipt,
    ParallelFrameStreamAuthority,
    parallel_frame_authority_sha256,
)
from app.contracts.v1.codec import (
    ContractCodec,
    canonical_sha256,
    canonical_sha256_omitting,
    canonicalize,
)
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    PARALLEL_INTAKE_AGENT_PROFILE_ID,
    PARALLEL_INTAKE_OUTPUT_SCHEMA,
    RoomGraphCommand,
    Usage,
)
from app.graphs.intake.parallel_contracts import FRAME_TYPES
from app.graphs.intake.parallel_graph import FrameInterrupted, FrameStarted
from app.graph_runtime.errors import (
    EvidenceModelInvocationContractError,
    GraphContractError,
    GraphLeaseLostError,
    GraphNewAgentAttemptRequiredError,
)
from app.graph_runtime import intake_executor as intake_executor_module
from app.graphs.intake.errors import IntakeGraphContractError
from app.harness.prompt_composer import PromptResourceError
from app.graph_runtime.identity import ActorScopeBinding, RoomType, ThreadIdentity
from app.graph_runtime.ledger import ParallelReceiptAbandonmentRecord
from app.graph_runtime.production_runtime import (
    ProductionGraphCommandEnvelope,
    VerifiedProductionInvocation,
    production_runtime_command_hash,
)
from app.model_runtime.transports import ModelTransportOutputError
from app.model_runtime.governed_chat_model import ModelStreamInterrupted
from app.security.invocation_envelope import (
    InvocationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    VerifiedInvocation,
    invocation_binding_claims,
)


ROOT = Path(__file__).resolve().parents[4]
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


class FakeParallelStreamService:
    def __init__(self, command: RoomGraphCommand) -> None:
        self.prepare_calls: list[
            tuple[RoomGraphCommand, VerifiedProductionInvocation, ThreadIdentity]
        ] = []
        self.calls: list[tuple[RoomGraphCommand, VerifiedInvocation, ThreadIdentity]] = []
        self.abandonment_calls: list[str] = []
        self.termination_calls: list[tuple[str, str]] = []
        self.closed = False
        frames = tuple(
            ExpectedParallelFrame(
                frame_type=frame_type,
                actor_role=command.actor_scope.actor_role,
                generation=1,
                frame_id=f"IFR_{index}",
                frame_model_input_sha256=str(index) * 64,
                frame_prompt_sha256=str(index + 3) * 64,
                context_envelope_sha256="a" * 64,
                model_context_view_sha256="b" * 64,
            )
            for index, frame_type in enumerate(FRAME_TYPES, start=1)
        )
        self.authority = ParallelFrameStreamAuthority(
            frame_set_id="IFS_ENDPOINT_1",
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            frames=frames,  # type: ignore[arg-type]
        )

    async def prepare(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
    ) -> ParallelFrameStreamAuthority:
        self.prepare_calls.append((command, verified_invocation, expected_thread))
        return self.authority

    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
        admission_receipt: ParallelFrameAdmissionReceipt,
    ) -> OpenedParallelFrameStream:
        admission_receipt.require_authority(command=command, authority=self.authority)
        self.calls.append((command, verified_invocation, expected_thread))

        async def emit():
            try:
                for frame in self.authority.frames:
                    yield FrameStarted(
                        frame_set_id=self.authority.frame_set_id,
                        run_id=self.authority.run_id,
                        attempt_id=self.authority.attempt_id,
                        frame_type=frame.frame_type,
                        generation=frame.generation,
                        frame_id=frame.frame_id,
                        frame_model_input_sha256=frame.frame_model_input_sha256,
                        frame_prompt_sha256=frame.frame_prompt_sha256,
                        context_envelope_sha256=frame.context_envelope_sha256,
                        model_context_view_sha256=frame.model_context_view_sha256,
                    )
                for frame in self.authority.frames:
                    yield FrameInterrupted(
                        frame_set_id=self.authority.frame_set_id,
                        run_id=self.authority.run_id,
                        attempt_id=self.authority.attempt_id,
                        frame_type=frame.frame_type,
                        generation=frame.generation,
                        frame_id=frame.frame_id,
                        error_code="TEST_TERMINAL_INTERRUPTION",
                        retryable=False,
                    )
            finally:
                self.closed = True

        return OpenedParallelFrameStream(self.authority, FRAME_TYPES, emit())

    async def terminate_uncommitted_failure(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
        admission_receipt: ParallelFrameAdmissionReceipt,
        failure_code: str,
    ) -> ParallelFrameFailureTerminationReceipt:
        del verified_invocation, expected_thread
        admission_receipt.require_authority(command=command, authority=self.authority)
        self.termination_calls.append((admission_receipt.receipt_sha256, failure_code))
        return ParallelFrameFailureTerminationReceipt.create(
            request_hash=command.request_hash,
            frame_set_id=self.authority.frame_set_id,
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            command_id=command.command_id,
            admission_receipt_sha256=admission_receipt.receipt_sha256,
            requested_failure_code=failure_code,
            graph_command_status="ABORTED",
            graph_attempt_status="FAILED",
            graph_error_code=failure_code,
            graph_error_classification="JAVA_FINAL_RETRY_EXHAUSTED",
            provider_permit_statuses=("RELEASED",),
        )

    async def abandon_stale_execution(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
        admission_receipt: ParallelFrameAdmissionReceipt,
    ) -> ParallelReceiptAbandonmentRecord:
        del verified_invocation, expected_thread
        admission_receipt.require_authority(command=command, authority=self.authority)
        self.abandonment_calls.append(admission_receipt.receipt_sha256)
        return ParallelReceiptAbandonmentRecord.create(
            thread_id=command.thread_id,
            command_id=command.command_id,
            request_hash=command.request_hash,
            attempt_id=command.attempt_id,
            frame_set_id=admission_receipt.frame_set_id,
            receipt_sha256=admission_receipt.receipt_sha256,
            authority_sha256=admission_receipt.authority_sha256,
            admission_receipt=admission_receipt.canonical_document(),
            provider_call_count_before=0,
            provider_call_count_after=1,
            owner_id="python:test-endpoint",
            fencing_token=1,
            abandoned_at=datetime(2026, 8, 26, tzinfo=timezone.utc),
        )


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
        envelope: ProductionGraphCommandEnvelope,
        verified: VerifiedProductionInvocation,
    ) -> None:
        self.envelope = envelope
        self.verified = verified

    def verify_envelope(self, **kwargs: Any) -> VerifiedProductionInvocation:
        assert kwargs["envelope"] == self.envelope
        return self.verified

    def verify_parallel_envelope(self, **kwargs: Any) -> VerifiedProductionInvocation:
        assert kwargs["envelope"] == self.envelope
        if kwargs["phase"] == "PREPARE":
            assert kwargs["admission_receipt_sha256"] is None
        elif kwargs["phase"] in {"EXECUTE", "ABANDON"}:
            assert isinstance(kwargs["admission_receipt_sha256"], str)
            assert len(kwargs["admission_receipt_sha256"]) == 64
        else:
            assert kwargs["phase"] == "TERMINATE"
            assert isinstance(kwargs["admission_receipt_sha256"], str)
            assert len(kwargs["admission_receipt_sha256"]) == 64
            assert kwargs["failure_code"] == "ACTIVITY_RETRY_EXHAUSTED"
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


def test_log_safe_failure_records_closed_graph_contract_intake_code_without_mutation(
    caplog: pytest.LogCaptureFixture,
) -> None:
    error_code = "INTAKE_ACTION_GATE_ROOM_MISMATCH"
    error = GraphContractError(error_code)
    original_args = error.args
    original_public_code = error.code

    _log_safe_failure("graph stream runtime", error)

    assert caplog.messages == [
        "graph stream runtime failed: "
        "error_type=GraphContractError "
        f"error_code={error_code}"
    ]
    assert error.args == original_args == (error_code,)
    assert error.code == original_public_code == "GRAPH_CONTRACT_REJECTED"


def test_log_safe_failure_records_closed_evidence_diagnostic_without_public_mutation(
    caplog: pytest.LogCaptureFixture,
) -> None:
    diagnostic_code = "EVIDENCE_V2_OPENING_FRAME_ORDER_INVALID"
    error = GraphContractError(diagnostic_code)

    _log_safe_failure("graph stream runtime", error)

    assert caplog.messages == [
        "graph stream runtime failed: "
        "error_type=GraphContractError "
        f"error_code={diagnostic_code}"
    ]
    assert error.args == (diagnostic_code,)
    assert error.code == "GRAPH_CONTRACT_REJECTED"


@pytest.mark.parametrize(
    "message",
    (
        "private binding detail",
        "https://example.test/failure?token=secret",
        "first line\nsecond line",
        "INTAKE_API_TOKEN_SECRET",
    ),
)
def test_log_safe_failure_omits_arbitrary_graph_contract_messages(
    caplog: pytest.LogCaptureFixture,
    message: str,
) -> None:
    error = GraphContractError(message)

    _log_safe_failure("graph stream runtime", error)

    assert caplog.messages == [
        "graph stream runtime failed: error_type=GraphContractError"
    ]
    assert message not in caplog.text
    assert error.args == (message,)
    assert error.code == "GRAPH_CONTRACT_REJECTED"


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


def test_log_safe_failure_records_only_code_owned_error_site(
    caplog: pytest.LogCaptureFixture,
) -> None:
    namespace: dict[str, Any] = {"__name__": "app.safe_diagnostic_fixture"}
    exec(
        compile(
            "def fail():\n    raise KeyError('api_token=private')\n",
            "C:/private/provider-payload.py",
            "exec",
        ),
        namespace,
    )
    try:
        namespace["fail"]()
    except KeyError as error:
        _log_safe_failure("graph stream iteration", error)

    assert len(caplog.messages) == 1
    assert caplog.messages[0].startswith(
        "graph stream iteration failed: error_type=KeyError "
        "error_site=app.safe_diagnostic_fixture:fail:"
    )
    assert "private" not in caplog.text
    assert "provider-payload" not in caplog.text

    caplog.clear()
    schema_namespace: dict[str, Any] = {"__name__": "pydantic.safe_fixture"}
    exec(
        compile(
            "def fail():\n    raise KeyError('$ref')\n",
            "C:/private/schema-payload.py",
            "exec",
        ),
        schema_namespace,
    )
    try:
        schema_namespace["fail"]()
    except KeyError as error:
        _log_safe_failure("graph stream iteration", error)

    assert caplog.messages == [
        "graph stream iteration failed: error_type=KeyError "
        "error_site=pydantic.safe_fixture:fail:2 error_key=$ref"
    ]
    assert "schema-payload" not in caplog.text


def test_log_safe_failure_records_only_trusted_closed_intake_executor_stage(
    caplog: pytest.LogCaptureFixture,
) -> None:
    stage_type = getattr(
        intake_executor_module,
        "IntakeExecutorDiagnosticStage",
        None,
    )
    error_type = getattr(
        intake_executor_module,
        "IntakeExecutorDiagnosticError",
        None,
    )
    boundary = getattr(
        intake_executor_module,
        "_intake_executor_diagnostic_error",
        None,
    )
    assert stage_type is not None, "trusted Intake executor stage enum is missing"
    assert error_type is not None, "trusted Intake executor diagnostic error is missing"
    assert callable(boundary), "trusted Intake executor diagnostic boundary is missing"
    expected_stages = {
        "GRAPH_STREAM_ADVANCE",
        "GRAPH_STREAM_CLOSE",
        "TERMINAL_STATE_REHYDRATE",
        "TERMINAL_PUBLIC_BINDING",
        "CHECKPOINT_PREFLIGHT",
        "PROPOSAL_STORE_PUT",
        "RESULT_MATERIALIZE",
        "FORMAL_COMMIT",
        "TERMINAL_PUBLIC_REPLAY",
    }
    assert {member.name for member in stage_type} == expected_stages
    assert {member.value for member in stage_type} == expected_stages
    assert set(stage_type.__members__) == expected_stages
    assert len(stage_type.__members__) == len(stage_type) == len(expected_stages)

    stage = stage_type.PROPOSAL_STORE_PUT
    private_message = "provider payload api_token=private-message"
    private_cause = "https://provider.test/private?case_id=CASE_PRIVATE"
    source = GraphContractError(private_message)
    error = boundary(source, stage=stage)
    error.__cause__ = RuntimeError(private_cause)
    original_type = type(error)
    original_args = error.args

    assert type(error) is error_type
    assert isinstance(error, GraphContractError)
    assert error.args == source.args == (private_message,)
    assert error.code == source.code == "GRAPH_CONTRACT_REJECTED"
    _log_safe_failure("graph stream runtime", error)

    assert caplog.messages == [
        "graph stream runtime failed: "
        "error_type=IntakeExecutorDiagnosticError "
        "diagnostic_stage=PROPOSAL_STORE_PUT"
    ]
    assert private_message not in caplog.text
    assert private_cause not in caplog.text
    assert type(error) is original_type
    assert error.args == original_args
    assert error.code == "GRAPH_CONTRACT_REJECTED"

    stable_code = "INTAKE_ACTION_GATE_ROOM_MISMATCH"
    stable_error = GraphContractError(stable_code)
    stable_args = stable_error.args
    stable_public_code = stable_error.code
    stable_wrapped = boundary(
        stable_error,
        stage=stage_type.GRAPH_STREAM_ADVANCE,
    )
    assert stable_wrapped is stable_error
    assert not hasattr(stable_wrapped, "diagnostic_stage")
    assert stable_wrapped.args == stable_args == (stable_code,)
    assert stable_wrapped.code == stable_public_code == "GRAPH_CONTRACT_REJECTED"

    caplog.clear()
    _log_safe_failure("graph stream runtime", stable_wrapped)
    assert caplog.messages == [
        "graph stream runtime failed: "
        "error_type=GraphContractError "
        f"error_code={stable_code}"
    ]
    assert stable_wrapped is stable_error
    assert stable_error.args == stable_args
    assert stable_error.code == stable_public_code

    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    before = _client(
        command=command,
        private_key=private_key,
        service=FakeStreamService((), failure_before=error),
    ).post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )
    assert before.status_code == 409
    assert before.json() == {"code": "GRAPH_CONTRACT_REJECTED", "retryable": False}
    assert private_message not in before.text
    assert private_cause not in before.text

    after = _client(
        command=command,
        private_key=private_key,
        service=FakeStreamService(
            (_event(command, "attempt_started", 0),),
            failure_after=error,
        ),
    ).post(
        "/internal/graphs/commands/stream",
        content=json.dumps(instance),
        headers={
            "Authorization": f"Bearer {_token(command, private_key)}",
            "Content-Type": "application/json",
        },
    )
    assert after.status_code == 200
    after_events = [json.loads(line) for line in after.text.splitlines()]
    assert [event["event_type"] for event in after_events] == ["attempt_started", "error"]
    assert after_events[-1]["payload"] == {
        "error_code": "GRAPH_CONTRACT_REJECTED",
        "retryable": False,
    }
    assert private_message not in after.text
    assert private_cause not in after.text
    assert type(error) is original_type
    assert error.args == original_args

    class GraphContractSubclass(GraphContractError):
        pass

    class ForeignStage(str, Enum):
        UNKNOWN = "PROPOSAL_STORE_PUT"

    forged_plain = GraphContractError("plain api_token=private")
    forged_plain.diagnostic_stage = stage  # type: ignore[attr-defined]
    forged_string = GraphContractError("string api_token=private")
    forged_string.diagnostic_stage = "PROPOSAL_STORE_PUT"  # type: ignore[attr-defined]
    forged_unknown = GraphContractError("unknown api_token=private")
    forged_unknown.diagnostic_stage = ForeignStage.UNKNOWN  # type: ignore[attr-defined]
    subclass = GraphContractSubclass("subclass api_token=private")
    subclass.diagnostic_stage = stage  # type: ignore[attr-defined]
    negatives = (forged_plain, forged_string, forged_unknown, subclass)

    caplog.clear()
    for candidate in negatives:
        _log_safe_failure("graph stream runtime", candidate)

    assert all("diagnostic_stage=" not in message for message in caplog.messages)
    assert caplog.messages == [
        "graph stream runtime failed: error_type=GraphContractError",
        "graph stream runtime failed: error_type=GraphContractError",
        "graph stream runtime failed: error_type=GraphContractError",
        "graph stream runtime failed: error_type=GraphContractSubclass",
    ]
    assert "api_token=private" not in caplog.text


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
        schema_version="agent-stream.v3",
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
    envelope: ProductionGraphCommandEnvelope,
    service: FakeStreamService,
    parallel_service: FakeParallelStreamService | None = None,
) -> TestClient:
    command = envelope.command
    verified = VerifiedProductionInvocation(
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
                mode="PRODUCTION",
                codec=ContractCodec(CONTRACT_ROOT),
                transport_identity_resolver=IdentityResolver(),
                envelope_verifier=object(),  # type: ignore[arg-type]
                thread_identity_resolver=ThreadResolver(),
                stream_service=service,
                ready=lambda: True,
                production_runtime_envelope_verifier=TargetVerifier(envelope, verified),
                parallel_intake_stream_service=parallel_service,
            )
        )
    )
    return TestClient(app, raise_server_exceptions=False)


def _target_envelope(command: RoomGraphCommand) -> ProductionGraphCommandEnvelope:
    values = {
        "schema_version": "production-runtime-graph-command-envelope.v1",
        "execution_lane": "PRODUCTION",
        "activation_id": "p9act.v1." + ("a" * 32),
        "room_fencing_token": 7,
        "command_hash": production_runtime_command_hash(command),
        "command": command.model_dump(mode="json", exclude_none=True),
    }
    return ProductionGraphCommandEnvelope.model_validate(
        {**values, "command_envelope_hash": canonical_sha256(values)}
    )


def _parallel_command() -> RoomGraphCommand:
    command, _ = _command()
    value = command.model_dump(mode="json", exclude_none=True)
    value.update(
        {
            "room_id": "ROOM_PARALLEL_ENDPOINT_1",
            "event_ref": {
                "artifact_id": "intake.event.parallel-endpoint-1",
                "schema_version": "intake-turn-event.v2",
                "uri": "urn:intake:event:parallel-endpoint-1",
                "sha256": "e" * 64,
                "size_bytes": 128,
            },
        }
    )
    value["invocation_context"].update(
        {
            "agent_profile_id": PARALLEL_INTAKE_AGENT_PROFILE_ID,
            "output_schema_version": PARALLEL_INTAKE_OUTPUT_SCHEMA,
        }
    )
    value["retry_budget"]["provider_attempts_remaining"] = 6
    value["request_hash"] = canonical_sha256_omitting(value, "request_hash")
    return RoomGraphCommand.model_validate(value)


def _parallel_admission_header(
    command: RoomGraphCommand,
    authority: ParallelFrameStreamAuthority,
) -> str:
    document: dict[str, Any] = {
        "schema_version": "intake.parallel-admission-receipt.v1",
        "request_hash": command.request_hash,
        "frame_set_id": authority.frame_set_id,
        "run_id": authority.run_id,
        "attempt_id": authority.attempt_id,
        "java_receipt_id": "FRAME_SET_RECEIPT_V4_1",
        "authority_sha256": parallel_frame_authority_sha256(authority),
        "lanes": [
            {
                "frame_type": frame.frame_type,
                "generation": frame.generation,
                "frame_id": frame.frame_id,
                "slot_state": "ADMITTED",
                "action": "RUN_CURRENT",
                "next_local_index": 0,
                "slot_version": 0,
                "result_id": None,
                "result_sha256": None,
                "public_projection_sha256": None,
                "predecessor_failure_code": None,
            }
            for frame in authority.frames
        ],
    }
    document["receipt_sha256"] = canonical_sha256(document)
    return base64.urlsafe_b64encode(canonicalize(document)).decode("ascii").rstrip("=")


def test_target_exact_parallel_command_uses_only_parallel_technical_stream() -> None:
    command = _parallel_command()
    envelope = _target_envelope(command)
    legacy = FakeStreamService(())
    parallel = FakeParallelStreamService(command)
    client = _target_client(
        envelope=envelope,
        service=legacy,
        parallel_service=parallel,
    )

    prepared = client.post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
            "X-Intake-Parallel-Phase": "PREPARE",
        },
    )

    assert prepared.status_code == 200
    assert prepared.json() == {"schema_version": "intake.parallel-prepared.v1"}
    assert prepared.headers["x-intake-frame-set-id"] == parallel.authority.frame_set_id
    assert len(parallel.prepare_calls) == 1
    assert parallel.calls == []

    response = client.post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
            "X-Intake-Parallel-Phase": "EXECUTE",
            "X-Intake-Parallel-Admission": _parallel_admission_header(
                command, parallel.authority
            ),
        },
    )

    assert response.status_code == 200
    assert response.headers["x-agent-stream-protocol"] == "agent-stream.v4"
    assert response.headers["x-intake-frame-set-id"] == parallel.authority.frame_set_id
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [event["event_kind"] for event in events] == [
        "FRAME_STARTED",
        "FRAME_STARTED",
        "FRAME_STARTED",
        "FRAME_INTERRUPTED",
        "FRAME_INTERRUPTED",
        "FRAME_INTERRUPTED",
    ]
    assert legacy.calls == []
    assert len(parallel.calls) == 1
    assert parallel.closed


def test_target_exact_parallel_terminate_returns_bound_failure_receipt() -> None:
    command = _parallel_command()
    envelope = _target_envelope(command)
    parallel = FakeParallelStreamService(command)
    client = _target_client(
        envelope=envelope,
        service=FakeStreamService(()),
        parallel_service=parallel,
    )
    admission_header = _parallel_admission_header(command, parallel.authority)

    response = client.post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
            "X-Intake-Parallel-Phase": "TERMINATE",
            "X-Intake-Parallel-Admission": admission_header,
            "X-Intake-Parallel-Failure-Code": "ACTIVITY_RETRY_EXHAUSTED",
        },
    )

    assert response.status_code == 200
    assert response.json()["schema_version"] == "intake.parallel-failure-termination.v1"
    assert response.json()["graph_command_status"] == "ABORTED"
    assert response.headers["x-agent-stream-protocol"] == "agent-stream.v4"
    assert response.headers["x-intake-frame-set-id"] == parallel.authority.frame_set_id
    assert response.headers["x-intake-parallel-terminal-receipt"] == response.json()[
        "receipt_sha256"
    ]
    assert len(parallel.termination_calls) == 1
    assert parallel.termination_calls[0][1] == "ACTIVITY_RETRY_EXHAUSTED"


def test_target_exact_parallel_abandon_returns_bound_immutable_receipt() -> None:
    command = _parallel_command()
    envelope = _target_envelope(command)
    parallel = FakeParallelStreamService(command)
    client = _target_client(
        envelope=envelope,
        service=FakeStreamService(()),
        parallel_service=parallel,
    )

    response = client.post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
            "X-Intake-Parallel-Phase": "ABANDON",
            "X-Intake-Parallel-Admission": _parallel_admission_header(
                command,
                parallel.authority,
            ),
        },
    )

    assert response.status_code == 200
    assert response.json()["schema_version"] == (
        "intake.parallel-receipt-abandonment.v1"
    )
    assert response.headers["x-agent-stream-protocol"] == "agent-stream.v4"
    assert response.headers["x-intake-frame-set-id"] == parallel.authority.frame_set_id
    assert response.headers["x-intake-parallel-abandonment-receipt"] == (
        response.json()["abandonment_sha256"]
    )
    assert parallel.abandonment_calls == [response.json()["receipt_sha256"]]
    assert parallel.calls == []


def test_target_exact_parallel_command_fails_closed_without_parallel_runtime() -> None:
    command = _parallel_command()
    envelope = _target_envelope(command)
    legacy = FakeStreamService(())
    response = _target_client(envelope=envelope, service=legacy).post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
            "X-Intake-Parallel-Phase": "PREPARE",
        },
    )

    assert response.status_code == 503
    assert response.json() == {
        "code": "INTAKE_PARALLEL_RUNTIME_UNAVAILABLE",
        "retryable": False,
    }
    assert legacy.calls == []


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
            "X-AfterSaleFlow-Production-Runtime-Activation": "forbidden",
        },
    )

    assert response.status_code == 400
    assert response.json()["code"] == "PRODUCTION_RUNTIME_ACTIVATION_HEADER_FORBIDDEN"
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


def test_target_stream_exposes_only_registered_intake_contract_error_codes(
    caplog: pytest.LogCaptureFixture,
) -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    stable_code = "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED"

    before_service = FakeStreamService(
        (),
        failure_before=IntakeGraphContractError(stable_code),
    )
    before = _target_client(envelope=envelope, service=before_service).post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )
    assert before.status_code == 502
    assert before.json() == {"code": stable_code, "retryable": False}
    assert before_service.closed is True

    after_service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=IntakeGraphContractError(stable_code),
    )
    after = _target_client(envelope=envelope, service=after_service).post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )
    assert after.status_code == 200
    after_events = [json.loads(line) for line in after.text.splitlines()]
    assert [event["event_type"] for event in after_events] == ["attempt_started", "error"]
    assert after_events[-1]["payload"] == {
        "error_code": stable_code,
        "retryable": False,
    }
    assert after_service.closed is True

    private_code = "INTAKE_API_TOKEN_SECRET"
    private_detail = "provider payload api_token=private-value"
    private_error = IntakeGraphContractError(private_code)
    private_error.__cause__ = RuntimeError(private_detail)
    private_service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=private_error,
    )
    private = _target_client(envelope=envelope, service=private_service).post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )
    private_events = [json.loads(line) for line in private.text.splitlines()]
    assert private_events[-1]["payload"] == {
        "error_code": "GRAPH_STREAM_PROTOCOL_REJECTED",
        "retryable": False,
    }
    assert private_code not in private.text
    assert private_detail not in private.text
    assert private_detail not in caplog.text
    assert private_service.closed is True


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


def test_production_runtime_lock_contention_before_first_event_returns_retryable_503() -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    service = FakeStreamService(
        (),
        failure_before=LockNotAvailable("private target lock holder detail"),
    )
    client = _target_client(envelope=envelope, service=service)

    response = client.post(
        "/internal/graphs/production-runtime/commands/stream",
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
            ModelTransportOutputError(
                "private schema repair detail",
                safe_code="AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED",
                node_name="private-node",
            ),
            "error",
            {
                "error_code": "AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED",
                "retryable": False,
            },
        ),
        (
            ModelTransportOutputError(
                "private unknown schema detail",
                safe_code="PRIVATE_UNKNOWN_OUTPUT_CODE",
                node_name="private-node",
            ),
            "error",
            {"error_code": "AGENT_OUTPUT_SCHEMA_INVALID", "retryable": False},
        ),
        (
            ModelStreamInterrupted(
                "private incomplete provider stream",
                retryable=True,
                safe_code="MODEL_PROVIDER_STREAM_INTERRUPTED",
            ),
            "attempt_aborted",
            {"reason_code": "GRAPH_PROVIDER_STREAM_INTERRUPTED"},
        ),
        (
            ModelStreamInterrupted("private deterministic stream failure"),
            "error",
            {"error_code": "GRAPH_STREAM_INTERNAL_ERROR", "retryable": False},
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


def test_evidence_invocation_contract_failure_keeps_stable_diagnostic_code() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=EvidenceModelInvocationContractError(
            "private Evidence runner signature detail"
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

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [event["event_type"] for event in events] == ["attempt_started", "error"]
    assert events[-1]["payload"] == {
        "error_code": "EVIDENCE_MODEL_INVOCATION_CONTRACT_INVALID",
        "retryable": False,
    }
    assert "private Evidence runner signature detail" not in response.text
    assert service.closed is True


def test_prompt_resource_failure_keeps_stable_diagnostic_code() -> None:
    command, instance = _command()
    private_key = ec.generate_private_key(ec.SECP256R1())
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=PromptResourceError("private prompt path detail"),
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
    assert [event["event_type"] for event in events] == ["attempt_started", "error"]
    assert events[-1]["payload"] == {
        "error_code": "GRAPH_PROMPT_RESOURCE_UNAVAILABLE",
        "retryable": False,
    }
    assert "private prompt path detail" not in response.text
    assert service.closed is True


def test_production_runtime_retryable_runtime_failure_requests_a_new_attempt_in_band() -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=GraphLeaseLostError("private target lease detail"),
    )
    client = _target_client(envelope=envelope, service=service)

    response = client.post(
        "/internal/graphs/production-runtime/commands/stream",
        content=envelope.model_dump_json(),
        headers={
            "Authorization": "Bearer a.b.c",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    assert response.headers["x-graph-execution-lane"] == "PRODUCTION"
    events = [json.loads(line) for line in response.text.splitlines()]
    assert [(event["sequence_no"], event["event_type"]) for event in events] == [
        (0, "attempt_started"),
        (1, "attempt_aborted"),
    ]
    assert events[-1]["payload"] == {"reason_code": "GRAPH_LEASE_LOST"}
    assert service.closed is True


def test_production_runtime_lock_contention_after_first_event_aborts_the_attempt() -> None:
    command, _ = _command()
    envelope = _target_envelope(command)
    service = FakeStreamService(
        (_event(command, "attempt_started", 0),),
        failure_after=LockNotAvailable("private target lock holder detail"),
    )
    client = _target_client(envelope=envelope, service=service)

    response = client.post(
        "/internal/graphs/production-runtime/commands/stream",
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
def test_stream_protocol_accepts_contract_authorized_v3_frame_payloads(
    event_type: str,
    payload: dict[str, Any],
) -> None:
    command, _ = _command()
    validator = AgentStreamProtocolValidator(
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        audience=command.actor_scope.audience,
    )
    validator.accept(_event(command, "attempt_started", 0))

    validator.accept(
        AgentStreamEvent.model_validate(
            {
                "schema_version": "agent-stream.v3",
                "run_id": command.logical_run_id,
                "attempt_id": command.attempt_id,
                "sequence_no": 1,
                "event_type": event_type,
                "audience": command.actor_scope.audience,
                "occurred_at": datetime.now(timezone.utc),
                "payload": payload,
            }
        )
    )

    assert validator.last_sequence == 1


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
