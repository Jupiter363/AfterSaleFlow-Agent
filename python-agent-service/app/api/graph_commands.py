from __future__ import annotations

import json
import logging
import re
from collections.abc import AsyncIterator, Callable, Mapping
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from email.message import Message
from typing import Any, Protocol

import anyio
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse

from app.api.graph_stream_service import (
    _model_provider_stream_interruption_code,
    _model_transport_output_error_code,
)
from app.api.intake_parallel_stream import (
    OpenedParallelFrameStream,
    ParallelFrameStreamProtocolError,
    ParallelFrameStreamProtocolValidator,
    ParallelIntakeFrameStreamService,
    encode_parallel_frame_event,
    stream_parallel_frame_ndjson,
)
from app.api.graph_reconciliation_service import (
    GraphReconciliationService,
    TargetE2EReconciliationArtifacts,
)
from app.contracts.v1.codec import ContractCodec
from app.contracts.v1.models import (
    AGENT_STREAM_PAYLOAD_FIELDS,
    AgentStreamEvent,
    AgentStreamPayload,
    GraphReconcileResponse,
    RoomGraphCommand,
)
from app.graph_runtime.errors import (
    GraphCommandAbortedError,
    GraphCommandCancelledError,
    GraphCommandDeadlineError,
    GraphCommandNotFoundError,
    GraphCommandStateError,
    GraphContractError,
    GraphGatewayDisabledError,
    GraphLeaseLostError,
    GraphLeaseUnavailableError,
    GraphNewAgentAttemptRequiredError,
    GraphNonceReplayError,
    GraphResultNotCommittedError,
    GraphRuntimeError,
    IntakeExecutorDiagnosticError,
    IntakeExecutorDiagnosticStage,
    STABLE_INTAKE_GRAPH_CONTRACT_ERROR_CODES,
    normalize_transient_persistence_error,
    stable_graph_contract_diagnostic_code,
)
from app.graph_runtime.identity import ThreadIdentity
from app.graphs.intake.errors import IntakeGraphContractError
from app.harness.prompt_composer import PromptResourceError
from app.llm import AgentOutputSchemaError
from app.model_runtime.transports import ModelTransportOutputError
from app.graph_runtime.target_e2e import (
    TARGET_E2E_COMMAND_PATH,
    TargetE2EGraphCommandEnvelope,
    TargetE2ERoomProposalSource,
    VerifiedTargetE2EInvocation,
)
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    ReconciliationClaims,
    TransportIdentity,
    VerifiedInvocation,
    VerifiedReconciliation,
    extract_bearer_token,
)


GRAPH_COMMAND_SCHEMA = "room-graph-command.schema.json"
AGENT_STREAM_SCHEMA = "agent-stream-event.schema.json"
GRAPH_RECONCILE_RESPONSE_SCHEMA = "graph-reconcile-response.schema.json"
GRAPH_COMMAND_MAX_BYTES = 65_536
GRAPH_STREAM_PATH = "/internal/graphs/commands/stream"
GRAPH_RECONCILE_PATH = "/internal/graphs/commands/reconcile"
TARGET_E2E_RECONCILE_PATH = "/internal/graphs/target-e2e/commands/reconcile"
TARGET_E2E_PROPOSAL_SOURCE_PATH = "/internal/graphs/target-e2e/commands/proposal-source"
_TERMINAL_EVENTS = frozenset({"attempt_aborted", "final", "error"})
_STABLE_INTAKE_ERROR_CODE_PATTERN = re.compile(r"^INTAKE_[A-Z0-9_]{1,120}$")
_SAFE_ERROR_SITE_MODULE_PATTERN = re.compile(
    r"^(?:app|asyncio|concurrent\.futures|langchain_core|langgraph|pydantic|pydantic_core)"
    r"(?:\.[A-Za-z_][A-Za-z0-9_]*)*$"
)
_SAFE_ERROR_SITE_FUNCTION_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,127}$")
_SAFE_SCHEMA_KEY_ERROR_VALUES = frozenset(
    {
        "$defs",
        "$ref",
        "additionalProperties",
        "anyOf",
        "const",
        "discriminator",
        "frame_sequence",
        "frame_type",
        "items",
        "mapping",
        "oneOf",
        "prefixItems",
        "properties",
        "required",
        "type",
    }
)
_NO_STORE_HEADERS: Mapping[str, str] = {
    "Cache-Control": "no-store, no-transform",
    "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff",
}
LOGGER = logging.getLogger(__name__)
_FORBIDDEN_BOOTSTRAP_HEADER = "x-aftersaleflow-target-e2e-activation"
_TARGET_RESULT_REF_HEADER = "x-graph-result-ref"
_TARGET_PROPOSAL_HASH_HEADER = "x-graph-proposal-hash"


def _public_intake_contract_error_code(error: BaseException) -> str | None:
    """Return only a reviewed Intake contract code from the exact domain error."""

    if type(error) is not IntakeGraphContractError:
        return None
    code = error.code
    if not isinstance(code, str) or code not in STABLE_INTAKE_GRAPH_CONTRACT_ERROR_CODES:
        return None
    return code


_TARGET_RESULT_REF = re.compile(r"^(?:s3|minio|urn):[!-~]{1,507}$")
_TARGET_PROPOSAL_HASH = re.compile(r"^[0-9a-f]{64}$")
_STREAM_CLOSE_TIMEOUT_SECONDS = 3.0


class TransportIdentityResolver(Protocol):
    def resolve(self, scope: Mapping[str, Any]) -> TransportIdentity: ...


class InvocationEnvelopeVerifierPort(Protocol):
    def verify(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> VerifiedInvocation: ...


class ReconciliationEnvelopeVerifierPort(Protocol):
    def verify(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> VerifiedReconciliation: ...


class TargetE2EInvocationEnvelopeVerifierPort(Protocol):
    def verify_envelope(
        self,
        *,
        token: str,
        envelope: TargetE2EGraphCommandEnvelope,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2EInvocation: ...

    def verify_envelope_for_reconciliation(
        self,
        *,
        token: str,
        envelope: TargetE2EGraphCommandEnvelope,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2EInvocation: ...


class GraphCommandStreamService(Protocol):
    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
        expected_thread: ThreadIdentity,
    ) -> AsyncIterator[AgentStreamEvent]: ...


class TrustedThreadIdentityResolver(Protocol):
    """Resolve agent-session authority from a trusted, hash-bound snapshot."""

    async def resolve(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
    ) -> ThreadIdentity: ...


class TrustedReconciliationThreadIdentityResolver(Protocol):
    """Resolve the same trusted historical thread for result reconciliation."""

    async def resolve(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
    ) -> ThreadIdentity: ...


@dataclass(frozen=True, slots=True)
class GraphCommandEndpointDependencies:
    mode: str
    codec: ContractCodec
    transport_identity_resolver: TransportIdentityResolver
    envelope_verifier: InvocationEnvelopeVerifierPort
    thread_identity_resolver: TrustedThreadIdentityResolver
    stream_service: GraphCommandStreamService
    ready: Callable[[], bool]
    target_e2e_envelope_verifier: TargetE2EInvocationEnvelopeVerifierPort | None = None
    parallel_intake_stream_service: ParallelIntakeFrameStreamService | None = None


@dataclass(frozen=True, slots=True)
class GraphReconciliationEndpointDependencies:
    mode: str
    codec: ContractCodec
    transport_identity_resolver: TransportIdentityResolver
    envelope_verifier: ReconciliationEnvelopeVerifierPort
    thread_identity_resolver: TrustedReconciliationThreadIdentityResolver
    reconciliation_service: GraphReconciliationService
    ready: Callable[[], bool]
    target_e2e_envelope_verifier: TargetE2EInvocationEnvelopeVerifierPort | None = None
    target_e2e_thread_identity_resolver: TrustedThreadIdentityResolver | None = None


class AgentStreamProtocolError(RuntimeError):
    pass


@dataclass(slots=True)
class AgentStreamProtocolValidator:
    run_id: str
    attempt_id: str
    audience: str
    last_sequence: int = -1
    started: bool = False
    terminal: bool = False

    def accept(self, event: AgentStreamEvent) -> None:
        expected_payload_fields = AGENT_STREAM_PAYLOAD_FIELDS.get(event.event_type)
        if (
            event.schema_version != "agent-stream.v3"
            or expected_payload_fields is None
            or not isinstance(event.payload, AgentStreamPayload)
            or isinstance(event.sequence_no, bool)
            or not isinstance(event.sequence_no, int)
        ):
            raise AgentStreamProtocolError("stream event envelope is invalid")
        if (
            event.run_id != self.run_id
            or event.attempt_id != self.attempt_id
            or event.audience != self.audience
        ):
            raise AgentStreamProtocolError("stream event identity or audience does not match")
        if self.terminal or event.sequence_no != self.last_sequence + 1:
            raise AgentStreamProtocolError("stream sequence or terminal state is invalid")
        if not self.started:
            if event.sequence_no != 0 or event.event_type != "attempt_started":
                raise AgentStreamProtocolError(
                    "stream must begin with attempt_started sequence zero"
                )
        elif event.event_type == "attempt_started":
            raise AgentStreamProtocolError("stream cannot contain another attempt_started event")
        present = frozenset(event.payload.model_dump(exclude_none=True))
        if present != expected_payload_fields:
            raise AgentStreamProtocolError("stream payload contains incompatible fields")
        if event.event_type == "attempt_reset":
            raise AgentStreamProtocolError(
                "Python stream cannot claim Java attempt reset authority"
            )
        self.started = True
        self.last_sequence = event.sequence_no
        self.terminal = event.event_type in _TERMINAL_EVENTS

    def finish(self) -> None:
        if not self.started or not self.terminal:
            raise AgentStreamProtocolError("stream ended without a terminal event")


def create_graph_commands_router(
    dependencies: GraphCommandEndpointDependencies,
) -> APIRouter:
    router = APIRouter()

    @router.post(GRAPH_STREAM_PATH, response_model=None)
    async def stream_graph_command(request: Request) -> JSONResponse | StreamingResponse:
        if dependencies.mode != "SHADOW":
            return _error_response(503, GraphGatewayDisabledError.code, False)
        if request.headers.get(_FORBIDDEN_BOOTSTRAP_HEADER) is not None:
            return _error_response(400, "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN", False)
        try:
            ready = dependencies.ready()
        except Exception:
            ready = False
        if not ready:
            return _error_response(503, "GRAPH_GATEWAY_NOT_READY", True)
        if not _has_json_utf8_content_type(request):
            return _error_response(415, "GRAPH_CONTENT_TYPE_REJECTED", False)
        if not _has_identity_content_encoding(request):
            return _error_response(415, "GRAPH_CONTENT_ENCODING_REJECTED", False)

        try:
            authorization = request.headers.getlist("authorization")
            if len(authorization) > 1:
                raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_REJECTED")
            token = extract_bearer_token(authorization[0] if authorization else None)
            transport_identity = dependencies.transport_identity_resolver.resolve(request.scope)
        except InvocationEnvelopeError as error:
            return _error_response(401, error.code, False)
        except Exception as error:
            _log_safe_failure("transport identity resolution", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)

        try:
            body_text = await _read_bounded_body(request, GRAPH_COMMAND_MAX_BYTES)
            command = dependencies.codec.decode(GRAPH_COMMAND_SCHEMA, body_text)
            if not isinstance(command, RoomGraphCommand):
                raise ValueError("decoded contract is not a RoomGraphCommand")
        except _BodyTooLarge:
            return _error_response(413, "GRAPH_COMMAND_TOO_LARGE", False)
        except (UnicodeDecodeError, ValueError):
            return _error_response(400, "GRAPH_COMMAND_REJECTED", False)
        except Exception as error:
            _log_safe_failure("graph command decoding", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)

        try:
            verified = dependencies.envelope_verifier.verify(
                token=token,
                command=command,
                transport_identity=transport_identity,
            )
        except InvocationEnvelopeError as error:
            return _error_response(401, error.code, False)
        except Exception as error:
            _log_safe_failure("invocation envelope verification", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)

        iterator: AsyncIterator[AgentStreamEvent] | None = None
        try:
            expected_thread = await dependencies.thread_identity_resolver.resolve(
                command=command,
                verified_invocation=verified,
            )
            if not isinstance(expected_thread, ThreadIdentity):
                raise AgentStreamProtocolError(
                    "trusted resolver returned an invalid thread identity"
                )
            iterator = await dependencies.stream_service.open_stream(
                command=command,
                verified_invocation=verified,
                expected_thread=expected_thread,
            )
            first = await anext(iterator)
            validator = AgentStreamProtocolValidator(
                run_id=command.logical_run_id,
                attempt_id=command.attempt_id,
                audience=command.actor_scope.audience,
            )
            first_line = _encode_event(dependencies.codec, validator, first)
        except StopAsyncIteration:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            return _error_response(502, "GRAPH_STREAM_EMPTY", True)
        except GraphRuntimeError as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            return _graph_runtime_error(error)
        except (AgentStreamProtocolError, TypeError, ValueError) as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            _log_safe_failure("graph stream startup protocol", error)
            return _error_response(
                502,
                _public_intake_contract_error_code(error)
                or "GRAPH_STREAM_PROTOCOL_REJECTED",
                False,
            )
        except Exception as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            persistence_error = normalize_transient_persistence_error(error)
            if persistence_error is not None:
                _log_safe_failure("graph stream startup persistence", error)
                return _graph_runtime_error(persistence_error)
            _log_safe_failure("graph stream startup", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)
        except BaseException:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            raise

        return StreamingResponse(
            _stream_ndjson(
                codec=dependencies.codec,
                iterator=iterator,
                validator=validator,
                first_line=first_line,
            ),
            media_type="application/x-ndjson",
            headers={
                **_NO_STORE_HEADERS,
                "X-Accel-Buffering": "no",
                "X-Agent-Run-Id": command.logical_run_id,
            },
        )

    @router.post(TARGET_E2E_COMMAND_PATH, response_model=None)
    async def stream_target_e2e_command(request: Request) -> JSONResponse | StreamingResponse:
        if dependencies.mode != "TARGET_E2E_CANDIDATE":
            return _error_response(503, GraphGatewayDisabledError.code, False)
        if request.headers.get(_FORBIDDEN_BOOTSTRAP_HEADER) is not None:
            return _error_response(400, "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN", False)
        verifier = dependencies.target_e2e_envelope_verifier
        if verifier is None:
            return _error_response(503, "TARGET_E2E_VERIFIER_NOT_CONFIGURED", False)
        try:
            ready = dependencies.ready()
        except Exception:
            ready = False
        if not ready:
            return _error_response(503, "GRAPH_GATEWAY_NOT_READY", True)
        if not _has_json_utf8_content_type(request):
            return _error_response(415, "GRAPH_CONTENT_TYPE_REJECTED", False)
        if not _has_identity_content_encoding(request):
            return _error_response(415, "GRAPH_CONTENT_ENCODING_REJECTED", False)

        try:
            authorization = request.headers.getlist("authorization")
            if len(authorization) > 1:
                raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_REJECTED")
            token = extract_bearer_token(authorization[0] if authorization else None)
            transport_identity = dependencies.transport_identity_resolver.resolve(request.scope)
        except InvocationEnvelopeError as error:
            return _error_response(401, error.code, False)
        except Exception as error:
            _log_safe_failure("target-E2E transport identity resolution", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)

        try:
            body_text = await _read_bounded_body(request, GRAPH_COMMAND_MAX_BYTES)
            envelope = TargetE2EGraphCommandEnvelope.model_validate(json.loads(body_text))
        except _BodyTooLarge:
            return _error_response(413, "GRAPH_COMMAND_TOO_LARGE", False)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return _error_response(400, "TARGET_E2E_COMMAND_ENVELOPE_REJECTED", False)
        except Exception as error:
            _log_safe_failure("target-E2E command envelope decoding", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)

        try:
            verified = verifier.verify_envelope(
                token=token,
                envelope=envelope,
                transport_identity=transport_identity,
            )
            if not isinstance(verified, VerifiedTargetE2EInvocation):
                raise InvocationEnvelopeError("TARGET_E2E_CREDENTIAL_TYPE_REJECTED")
        except InvocationEnvelopeError as error:
            return _error_response(401, error.code, False)
        except Exception as error:
            _log_safe_failure("target-E2E invocation envelope verification", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)

        command = envelope.command
        iterator: AsyncIterator[AgentStreamEvent] | None = None
        parallel_opened: OpenedParallelFrameStream | None = None
        parallel_first_line: bytes | None = None
        parallel_validator: ParallelFrameStreamProtocolValidator | None = None
        try:
            expected_thread = await dependencies.thread_identity_resolver.resolve(
                command=command,
                verified_invocation=verified,
            )
            if not isinstance(expected_thread, ThreadIdentity):
                raise AgentStreamProtocolError(
                    "trusted resolver returned an invalid thread identity"
                )
            if command.is_parallel_intake_command:
                parallel_service = dependencies.parallel_intake_stream_service
                if parallel_service is None:
                    return _error_response(
                        503,
                        "INTAKE_PARALLEL_RUNTIME_UNAVAILABLE",
                        False,
                    )
                parallel_opened = await parallel_service.open_stream(
                    command=command,
                    verified_invocation=verified,
                    expected_thread=expected_thread,
                )
                authority = parallel_opened.authority
                if (
                    authority.run_id != command.logical_run_id
                    or authority.attempt_id != command.attempt_id
                ):
                    raise ParallelFrameStreamProtocolError(
                        "parallel Frame authority differs from the signed command"
                    )
                parallel_validator = ParallelFrameStreamProtocolValidator(authority)
                first_parallel_event = await anext(parallel_opened.events)
                parallel_first_line = encode_parallel_frame_event(
                    parallel_validator,
                    first_parallel_event,
                )
            else:
                iterator = await dependencies.stream_service.open_stream(
                    command=command,
                    verified_invocation=verified,
                    expected_thread=expected_thread,
                )
                first = await anext(iterator)
                validator = AgentStreamProtocolValidator(
                    run_id=command.logical_run_id,
                    attempt_id=command.attempt_id,
                    audience=command.actor_scope.audience,
                )
                first_line = _encode_event(dependencies.codec, validator, first)
        except StopAsyncIteration:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            if parallel_opened is not None:
                await _close_iterator_safely(parallel_opened.events)
            return _error_response(502, "GRAPH_STREAM_EMPTY", True)
        except GraphRuntimeError as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            if parallel_opened is not None:
                await _close_iterator_safely(parallel_opened.events)
            return _graph_runtime_error(error)
        except (
            AgentStreamProtocolError,
            ParallelFrameStreamProtocolError,
            TypeError,
            ValueError,
        ) as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            if parallel_opened is not None:
                await _close_iterator_safely(parallel_opened.events)
            _log_safe_failure("target-E2E graph stream startup protocol", error)
            return _error_response(
                502,
                _public_intake_contract_error_code(error)
                or "GRAPH_STREAM_PROTOCOL_REJECTED",
                False,
            )
        except Exception as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            if parallel_opened is not None:
                await _close_iterator_safely(parallel_opened.events)
            persistence_error = normalize_transient_persistence_error(error)
            if persistence_error is not None:
                _log_safe_failure("target-E2E graph stream startup persistence", error)
                return _graph_runtime_error(persistence_error)
            _log_safe_failure("target-E2E graph stream startup", error)
            return _error_response(500, "GRAPH_STREAM_INTERNAL_ERROR", False)
        except BaseException:
            if iterator is not None:
                await _close_iterator_safely(iterator)
            if parallel_opened is not None:
                await _close_iterator_safely(parallel_opened.events)
            raise

        if parallel_opened is not None:
            assert parallel_first_line is not None
            assert parallel_validator is not None
            return StreamingResponse(
                stream_parallel_frame_ndjson(
                    iterator=parallel_opened.events,
                    validator=parallel_validator,
                    first_line=parallel_first_line,
                ),
                media_type="application/x-ndjson",
                headers={
                    **_NO_STORE_HEADERS,
                    "X-Accel-Buffering": "no",
                    "X-Agent-Run-Id": command.logical_run_id,
                    "X-Agent-Stream-Protocol": "agent-stream.v4",
                    "X-Intake-Frame-Set-Id": parallel_opened.authority.frame_set_id,
                    "X-Graph-Execution-Lane": envelope.execution_lane,
                    "X-Graph-Activation-Id": envelope.activation_id,
                },
            )

        return StreamingResponse(
            _stream_ndjson(
                codec=dependencies.codec,
                iterator=iterator,
                validator=validator,
                first_line=first_line,
            ),
            media_type="application/x-ndjson",
            headers={
                **_NO_STORE_HEADERS,
                "X-Accel-Buffering": "no",
                "X-Agent-Run-Id": command.logical_run_id,
                "X-Graph-Execution-Lane": envelope.execution_lane,
                "X-Graph-Activation-Id": envelope.activation_id,
            },
        )

    return router


def create_graph_reconciliation_router(
    dependencies: GraphReconciliationEndpointDependencies,
) -> APIRouter:
    """Expose result-only recovery without granting Graph execution authority."""

    router = APIRouter()

    @router.post(GRAPH_RECONCILE_PATH, response_model=None)
    async def reconcile_graph_command(request: Request) -> JSONResponse:
        if dependencies.mode != "SHADOW":
            return _reconciliation_error_response(
                503,
                GraphGatewayDisabledError.code,
                False,
                "FAIL_LOGICAL_RUN",
            )
        try:
            ready = dependencies.ready()
        except Exception:
            ready = False
        if not ready:
            return _reconciliation_error_response(
                503,
                "GRAPH_GATEWAY_NOT_READY",
                True,
                "RETRY_SAME_COMMAND",
            )
        if not _has_json_utf8_content_type(request):
            return _reconciliation_error_response(
                415,
                "GRAPH_CONTENT_TYPE_REJECTED",
                False,
                "FAIL_LOGICAL_RUN",
            )
        if not _has_identity_content_encoding(request):
            return _reconciliation_error_response(
                415,
                "GRAPH_CONTENT_ENCODING_REJECTED",
                False,
                "FAIL_LOGICAL_RUN",
            )

        try:
            authorization = request.headers.getlist("authorization")
            if len(authorization) > 1:
                raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_REJECTED")
            token = extract_bearer_token(authorization[0] if authorization else None)
            transport_identity = dependencies.transport_identity_resolver.resolve(request.scope)
            if not isinstance(transport_identity, TransportIdentity):
                raise InvocationEnvelopeError("INVOCATION_MTLS_IDENTITY_REJECTED")
        except InvocationEnvelopeError as error:
            return _reconciliation_error_response(
                401,
                error.code,
                False,
                "FAIL_LOGICAL_RUN",
            )
        except Exception as error:
            _log_safe_failure("reconciliation transport identity resolution", error)
            return _reconciliation_error_response(
                500,
                "GRAPH_RECONCILIATION_INTERNAL_ERROR",
                False,
                "FAIL_LOGICAL_RUN",
            )

        try:
            body_text = await _read_bounded_body(request, GRAPH_COMMAND_MAX_BYTES)
            command = dependencies.codec.decode(GRAPH_COMMAND_SCHEMA, body_text)
            if not isinstance(command, RoomGraphCommand):
                raise ValueError("decoded contract is not a RoomGraphCommand")
        except _BodyTooLarge:
            return _reconciliation_error_response(
                413,
                "GRAPH_COMMAND_TOO_LARGE",
                False,
                "FAIL_LOGICAL_RUN",
            )
        except (UnicodeDecodeError, ValueError):
            return _reconciliation_error_response(
                400,
                "GRAPH_COMMAND_REJECTED",
                False,
                "FAIL_LOGICAL_RUN",
            )
        except Exception as error:
            _log_safe_failure("reconciliation command decoding", error)
            return _reconciliation_error_response(
                500,
                "GRAPH_RECONCILIATION_INTERNAL_ERROR",
                False,
                "FAIL_LOGICAL_RUN",
            )

        try:
            verified = dependencies.envelope_verifier.verify(
                token=token,
                command=command,
                transport_identity=transport_identity,
            )
            if not isinstance(verified, VerifiedReconciliation) or not isinstance(
                verified.claims,
                ReconciliationClaims,
            ):
                raise InvocationEnvelopeError("INVOCATION_RECONCILIATION_CREDENTIAL_TYPE_REJECTED")
        except InvocationEnvelopeError as error:
            return _reconciliation_error_response(
                401,
                error.code,
                False,
                "FAIL_LOGICAL_RUN",
            )
        except Exception as error:
            _log_safe_failure("reconciliation envelope verification", error)
            return _reconciliation_error_response(
                500,
                "GRAPH_RECONCILIATION_INTERNAL_ERROR",
                False,
                "FAIL_LOGICAL_RUN",
            )

        try:
            expected_thread = await dependencies.thread_identity_resolver.resolve(
                command=command,
                verified_reconciliation=verified,
            )
            if not isinstance(expected_thread, ThreadIdentity):
                raise TypeError("trusted resolver returned an invalid thread identity")
            response = await dependencies.reconciliation_service.reconcile(
                command=command,
                verified_reconciliation=verified,
                expected_thread=expected_thread,
            )
            if not isinstance(response, GraphReconcileResponse):
                raise TypeError("reconciliation service returned an invalid response")
            encoded = dependencies.codec.encode(
                GRAPH_RECONCILE_RESPONSE_SCHEMA,
                response,
            )
        except GraphRuntimeError as error:
            return _reconciliation_runtime_error(error)
        except (TypeError, ValueError):
            return _reconciliation_error_response(
                502,
                "GRAPH_RECONCILIATION_PROTOCOL_REJECTED",
                False,
                "FAIL_LOGICAL_RUN",
            )
        except Exception as error:
            _log_safe_failure("graph result reconciliation", error)
            return _reconciliation_error_response(
                500,
                "GRAPH_RECONCILIATION_INTERNAL_ERROR",
                False,
                "FAIL_LOGICAL_RUN",
            )

        return JSONResponse(
            status_code=200,
            content=encoded,
            headers=dict(_NO_STORE_HEADERS),
        )

    @router.post(TARGET_E2E_RECONCILE_PATH, response_model=None)
    async def reconcile_target_e2e_command(request: Request) -> JSONResponse:
        if dependencies.mode != "TARGET_E2E_CANDIDATE":
            return _error_response(503, GraphGatewayDisabledError.code, False)
        if request.headers.get(_FORBIDDEN_BOOTSTRAP_HEADER) is not None:
            return _error_response(400, "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN", False)
        verifier = dependencies.target_e2e_envelope_verifier
        resolver = dependencies.target_e2e_thread_identity_resolver
        if verifier is None or resolver is None:
            return _error_response(503, "TARGET_E2E_VERIFIER_NOT_CONFIGURED", False)
        # This route can only recover the immutable result of an already-admitted command.
        # Global readiness gates new execution; it must not strand a durable result during a
        # transient admission outage. Verification and exact durable lookup remain fail closed.
        if not _has_json_utf8_content_type(request):
            return _error_response(415, "GRAPH_CONTENT_TYPE_REJECTED", False)
        if not _has_identity_content_encoding(request):
            return _error_response(415, "GRAPH_CONTENT_ENCODING_REJECTED", False)
        try:
            authorization = request.headers.getlist("authorization")
            if len(authorization) > 1:
                raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_REJECTED")
            token = extract_bearer_token(authorization[0] if authorization else None)
            transport_identity = dependencies.transport_identity_resolver.resolve(request.scope)
            body_text = await _read_bounded_body(request, GRAPH_COMMAND_MAX_BYTES)
            envelope = TargetE2EGraphCommandEnvelope.model_validate(json.loads(body_text))
            verified = verifier.verify_envelope_for_reconciliation(
                token=token,
                envelope=envelope,
                transport_identity=transport_identity,
            )
            if not isinstance(verified, VerifiedTargetE2EInvocation):
                raise InvocationEnvelopeError("TARGET_E2E_CREDENTIAL_TYPE_REJECTED")
        except _BodyTooLarge:
            return _error_response(413, "GRAPH_COMMAND_TOO_LARGE", False)
        except InvocationEnvelopeError as error:
            return _error_response(401, error.code, False)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return _error_response(400, "TARGET_E2E_COMMAND_ENVELOPE_REJECTED", False)
        except Exception as error:
            _log_safe_failure("target-E2E reconciliation verification", error)
            return _error_response(500, "GRAPH_RECONCILIATION_INTERNAL_ERROR", False)
        try:
            expected_thread = await resolver.resolve(
                command=envelope.command,
                verified_invocation=verified,
            )
            if not isinstance(expected_thread, ThreadIdentity):
                raise TypeError("trusted resolver returned an invalid thread identity")
            result = await dependencies.reconciliation_service.reconcile_target_e2e(
                command=envelope.command,
                verified_invocation=verified,
                expected_thread=expected_thread,
            )
            if not isinstance(result, TargetE2EReconciliationArtifacts):
                raise TypeError("candidate reconciliation returned invalid durable artifacts")
            return JSONResponse(
                status_code=200,
                content=result.envelope.model_dump(mode="json", exclude_none=True),
                headers={
                    **_NO_STORE_HEADERS,
                    "X-Graph-Result-Ref": result.result_ref,
                    "X-Graph-Result-Hash": result.result_hash,
                    "X-Graph-Proposal-Hash": result.proposal_hash,
                },
            )
        except GraphRuntimeError as error:
            return _graph_runtime_error(error)
        except (TypeError, ValueError):
            return _error_response(502, "TARGET_E2E_RESULT_ENVELOPE_REJECTED", False)
        except Exception as error:
            _log_safe_failure("target-E2E reconciliation", error)
            return _error_response(500, "GRAPH_RECONCILIATION_INTERNAL_ERROR", False)

    @router.post(TARGET_E2E_PROPOSAL_SOURCE_PATH, response_model=None)
    async def retrieve_target_e2e_proposal_source(request: Request) -> JSONResponse:
        if dependencies.mode != "TARGET_E2E_CANDIDATE":
            return _error_response(503, GraphGatewayDisabledError.code, False)
        if request.headers.get(_FORBIDDEN_BOOTSTRAP_HEADER) is not None:
            return _error_response(400, "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN", False)
        verifier = dependencies.target_e2e_envelope_verifier
        resolver = dependencies.target_e2e_thread_identity_resolver
        if verifier is None or resolver is None:
            return _error_response(503, "TARGET_E2E_VERIFIER_NOT_CONFIGURED", False)
        try:
            if not dependencies.ready():
                return _error_response(503, "GRAPH_GATEWAY_NOT_READY", True)
        except Exception:
            return _error_response(503, "GRAPH_GATEWAY_NOT_READY", True)
        if not _has_json_utf8_content_type(request):
            return _error_response(415, "GRAPH_CONTENT_TYPE_REJECTED", False)
        if not _has_identity_content_encoding(request):
            return _error_response(415, "GRAPH_CONTENT_ENCODING_REJECTED", False)
        try:
            expected_result_ref, expected_proposal_hash = _target_proposal_selector(request)
        except ValueError:
            return _error_response(
                400,
                "TARGET_E2E_PROPOSAL_SOURCE_HEADERS_REJECTED",
                False,
            )
        try:
            authorization = request.headers.getlist("authorization")
            if len(authorization) > 1:
                raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_REJECTED")
            token = extract_bearer_token(authorization[0] if authorization else None)
            transport_identity = dependencies.transport_identity_resolver.resolve(request.scope)
            body_text = await _read_bounded_body(request, GRAPH_COMMAND_MAX_BYTES)
            envelope = TargetE2EGraphCommandEnvelope.model_validate(json.loads(body_text))
            verified = verifier.verify_envelope_for_reconciliation(
                token=token,
                envelope=envelope,
                transport_identity=transport_identity,
            )
            if not isinstance(verified, VerifiedTargetE2EInvocation):
                raise InvocationEnvelopeError("TARGET_E2E_CREDENTIAL_TYPE_REJECTED")
        except _BodyTooLarge:
            return _error_response(413, "GRAPH_COMMAND_TOO_LARGE", False)
        except InvocationEnvelopeError as error:
            return _error_response(401, error.code, False)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return _error_response(400, "TARGET_E2E_COMMAND_ENVELOPE_REJECTED", False)
        except Exception as error:
            _log_safe_failure("target-E2E proposal-source verification", error)
            return _error_response(500, "GRAPH_RECONCILIATION_INTERNAL_ERROR", False)
        try:
            expected_thread = await resolver.resolve(
                command=envelope.command,
                verified_invocation=verified,
            )
            if not isinstance(expected_thread, ThreadIdentity):
                raise TypeError("trusted resolver returned an invalid thread identity")
            proposal_source = await (
                dependencies.reconciliation_service.retrieve_target_e2e_proposal_source(
                    command=envelope.command,
                    verified_invocation=verified,
                    expected_thread=expected_thread,
                    expected_result_ref=expected_result_ref,
                    expected_proposal_hash=expected_proposal_hash,
                )
            )
            if not isinstance(proposal_source, TargetE2ERoomProposalSource):
                raise TypeError("candidate reconciliation returned an invalid proposal source")
            return JSONResponse(
                status_code=200,
                content=proposal_source.model_dump(mode="json", exclude_none=True),
                headers={
                    **_NO_STORE_HEADERS,
                    "X-Graph-Result-Ref": expected_result_ref,
                    "X-Graph-Proposal-Hash": expected_proposal_hash,
                },
            )
        except GraphRuntimeError as error:
            return _graph_runtime_error(error)
        except (TypeError, ValueError):
            return _error_response(502, "TARGET_E2E_PROPOSAL_SOURCE_REJECTED", False)
        except Exception as error:
            _log_safe_failure("target-E2E proposal-source retrieval", error)
            return _error_response(500, "GRAPH_RECONCILIATION_INTERNAL_ERROR", False)

    return router


def _target_proposal_selector(request: Request) -> tuple[str, str]:
    result_refs = request.headers.getlist(_TARGET_RESULT_REF_HEADER)
    proposal_hashes = request.headers.getlist(_TARGET_PROPOSAL_HASH_HEADER)
    if len(result_refs) != 1 or len(proposal_hashes) != 1:
        raise ValueError("target-E2E proposal selector headers must be singular")
    result_ref = result_refs[0]
    proposal_hash = proposal_hashes[0]
    if (
        _TARGET_RESULT_REF.fullmatch(result_ref) is None
        or len(result_ref) > 512
        or _TARGET_PROPOSAL_HASH.fullmatch(proposal_hash) is None
    ):
        raise ValueError("target-E2E proposal selector headers are malformed")
    return result_ref, proposal_hash


async def _read_bounded_body(request: Request, maximum: int) -> str:
    content_lengths = request.headers.getlist("content-length")
    if len(content_lengths) > 1:
        raise ValueError("duplicate Content-Length")
    declared: int | None = None
    if content_lengths:
        content_length = content_lengths[0]
        if not content_length or not content_length.isascii() or not content_length.isdecimal():
            raise ValueError("invalid Content-Length")
        declared = int(content_length)
        if declared > maximum:
            raise _BodyTooLarge
    body = bytearray()
    async for chunk in request.stream():
        if len(chunk) > maximum - len(body):
            raise _BodyTooLarge
        body.extend(chunk)
    if not body:
        raise ValueError("request body is empty")
    if declared is not None and len(body) != declared:
        raise ValueError("Content-Length does not match the request body")
    return bytes(body).decode("utf-8", errors="strict")


async def _stream_ndjson(
    *,
    codec: ContractCodec,
    iterator: AsyncIterator[AgentStreamEvent],
    validator: AgentStreamProtocolValidator,
    first_line: bytes,
) -> AsyncIterator[bytes]:
    try:
        yield first_line
        while True:
            try:
                event = await anext(iterator)
            except StopAsyncIteration as error:
                raise AgentStreamProtocolError("stream ended without a terminal event") from error

            candidate = replace(validator)
            encoded = _encode_event(codec, candidate, event)
            if candidate.terminal:
                try:
                    await anext(iterator)
                except StopAsyncIteration:
                    yield encoded
                    return
                raise AgentStreamProtocolError("stream emitted an event after its terminal event")
            validator = candidate
            yield encoded
    except GraphRuntimeError as error:
        _log_safe_failure("graph stream runtime", error)
        if error.retryable:
            yield _encode_terminal_attempt_aborted(
                codec,
                validator,
                reason_code=error.code,
            )
        else:
            yield _encode_terminal_error(
                codec,
                validator,
                error_code=error.code,
            )
    except (AgentStreamProtocolError, TypeError, ValueError) as error:
        _log_safe_failure("graph stream protocol", error)
        yield _encode_terminal_error(
            codec,
            validator,
            error_code=_public_intake_contract_error_code(error)
            or "GRAPH_STREAM_PROTOCOL_REJECTED",
        )
    except (ModelTransportOutputError, AgentOutputSchemaError) as error:
        _log_safe_failure("graph stream model output", error)
        yield _encode_terminal_error(
            codec,
            validator,
            error_code=_model_transport_output_error_code(error),
        )
    except PromptResourceError as error:
        _log_safe_failure("graph prompt resource", error)
        yield _encode_terminal_error(
            codec,
            validator,
            error_code=PromptResourceError.code,
        )
    except Exception as error:
        provider_stream_code = _model_provider_stream_interruption_code(error)
        if provider_stream_code is not None:
            _log_safe_failure("graph provider stream interruption", error)
            yield _encode_terminal_attempt_aborted(
                codec,
                validator,
                reason_code=provider_stream_code,
            )
            return
        persistence_error = normalize_transient_persistence_error(error)
        if persistence_error is not None:
            _log_safe_failure("graph stream persistence", error)
            yield _encode_terminal_attempt_aborted(
                codec,
                validator,
                reason_code=persistence_error.code,
            )
            return
        _log_safe_failure("graph stream iteration", error)
        yield _encode_terminal_error(
            codec,
            validator,
            error_code="GRAPH_STREAM_INTERNAL_ERROR",
        )
    finally:
        await _close_iterator_safely(iterator)


def _encode_terminal_error(
    codec: ContractCodec,
    validator: AgentStreamProtocolValidator,
    *,
    error_code: str,
) -> bytes:
    event = AgentStreamEvent(
            schema_version="agent-stream.v3",
        run_id=validator.run_id,
        attempt_id=validator.attempt_id,
        sequence_no=validator.last_sequence + 1,
        event_type="error",
        audience=validator.audience,
        occurred_at=datetime.now(timezone.utc),
        payload=AgentStreamPayload(
            error_code=error_code,
            retryable=False,
        ),
    )
    return _encode_event(codec, validator, event)


def _encode_terminal_attempt_aborted(
    codec: ContractCodec,
    validator: AgentStreamProtocolValidator,
    *,
    reason_code: str,
) -> bytes:
    event = AgentStreamEvent(
            schema_version="agent-stream.v3",
        run_id=validator.run_id,
        attempt_id=validator.attempt_id,
        sequence_no=validator.last_sequence + 1,
        event_type="attempt_aborted",
        audience=validator.audience,
        occurred_at=datetime.now(timezone.utc),
        payload=AgentStreamPayload(reason_code=reason_code),
    )
    return _encode_event(codec, validator, event)


def _encode_event(
    codec: ContractCodec,
    validator: AgentStreamProtocolValidator,
    event: AgentStreamEvent,
) -> bytes:
    if not isinstance(event, AgentStreamEvent):
        raise AgentStreamProtocolError("stream service returned an untyped event")
    validator.accept(event)
    try:
        encoded = codec.encode(AGENT_STREAM_SCHEMA, event)
    except ValueError as error:
        raise AgentStreamProtocolError("stream event violates Agent Stream v2") from error
    return (json.dumps(encoded, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


async def _close_iterator_safely(iterator: AsyncIterator[AgentStreamEvent]) -> None:
    close = getattr(iterator, "aclose", None)
    if close is not None:
        try:
            # ASGI disconnect handling uses AnyIO level cancellation.  Run the
            # nested service generator in an AnyIO shield so its durable
            # abort/heartbeat/lease teardown is not cancelled at its first await.
            with anyio.fail_after(_STREAM_CLOSE_TIMEOUT_SECONDS, shield=True):
                await close()
        except Exception as error:
            _log_safe_failure("graph stream iterator cleanup", error)


def _has_json_utf8_content_type(request: Request) -> bool:
    values = request.headers.getlist("content-type")
    if len(values) != 1:
        return False
    message = Message()
    try:
        message["content-type"] = values[0]
        parameters = message.get_params(header="content-type", failobj=[])
    except (TypeError, ValueError):
        return False
    if message.get_content_type() != "application/json" or not parameters:
        return False
    options = parameters[1:]
    if not options:
        return True
    if len(options) != 1:
        return False
    name, value = options[0]
    return (
        isinstance(name, str)
        and isinstance(value, str)
        and name.lower() == "charset"
        and value.lower() == "utf-8"
    )


def _has_identity_content_encoding(request: Request) -> bool:
    values = request.headers.getlist("content-encoding")
    return not values or (len(values) == 1 and values[0].strip().lower() == "identity")


def _graph_runtime_error(error: GraphRuntimeError) -> JSONResponse:
    if isinstance(error, GraphCommandDeadlineError):
        status_code = 408
    elif isinstance(error, GraphNonceReplayError):
        status_code = 409
    elif isinstance(
        error,
        (GraphGatewayDisabledError, GraphLeaseUnavailableError, GraphLeaseLostError),
    ):
        status_code = 503
    else:
        status_code = 409
    return _error_response(status_code, error.code, error.retryable)


def _reconciliation_runtime_error(error: GraphRuntimeError) -> JSONResponse:
    if isinstance(error, GraphCommandNotFoundError):
        return _reconciliation_error_response(
            404,
            error.code,
            False,
            "FAIL_LOGICAL_RUN",
        )
    if isinstance(error, GraphNewAgentAttemptRequiredError):
        return _reconciliation_error_response(
            409,
            error.code,
            False,
            "CREATE_NEXT_ATTEMPT",
        )
    if isinstance(error, GraphNonceReplayError):
        return _reconciliation_error_response(
            409,
            error.code,
            True,
            "RETRY_SAME_COMMAND",
        )
    if isinstance(
        error,
        (GraphGatewayDisabledError, GraphLeaseUnavailableError, GraphLeaseLostError),
    ):
        return _reconciliation_error_response(
            503,
            error.code,
            True,
            "RETRY_SAME_COMMAND",
        )
    if isinstance(
        error,
        (
            GraphResultNotCommittedError,
            GraphCommandCancelledError,
            GraphCommandAbortedError,
            GraphCommandStateError,
        ),
    ):
        return _reconciliation_error_response(
            409,
            error.code,
            False,
            "FAIL_LOGICAL_RUN",
        )
    return _reconciliation_error_response(
        409,
        error.code,
        False,
        "FAIL_LOGICAL_RUN",
    )


def _error_response(status_code: int, code: str, retryable: bool) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"code": code, "retryable": retryable},
        headers=dict(_NO_STORE_HEADERS),
    )


def _reconciliation_error_response(
    status_code: int,
    code: str,
    retryable: bool,
    recovery_action: str,
) -> JSONResponse:
    if recovery_action not in {
        "RETRY_SAME_COMMAND",
        "CREATE_NEXT_ATTEMPT",
        "RECONCILE_TERMINAL",
        "FAIL_LOGICAL_RUN",
    }:
        raise ValueError("reconciliation recovery action is not closed")
    return JSONResponse(
        status_code=status_code,
        content={
            "code": code,
            "retryable": retryable,
            "recovery_action": recovery_action,
        },
        headers=dict(_NO_STORE_HEADERS),
    )


def _log_safe_failure(stage: str, error: Exception) -> None:
    error_code: str | None = None
    if isinstance(error, IntakeGraphContractError):
        if _STABLE_INTAKE_ERROR_CODE_PATTERN.fullmatch(error.code):
            error_code = error.code
    elif (
        isinstance(error, GraphContractError)
        and (diagnostic_code := stable_graph_contract_diagnostic_code(error)) is not None
    ):
        error_code = diagnostic_code
    if error_code is not None:
        LOGGER.error(
            "%s failed: error_type=%s error_code=%s",
            stage,
            type(error).__name__,
            error_code,
        )
        return
    if (
        type(error) is IntakeExecutorDiagnosticError
        and type(error.diagnostic_stage) is IntakeExecutorDiagnosticStage
    ):
        LOGGER.error(
            "%s failed: error_type=%s diagnostic_stage=%s",
            stage,
            type(error).__name__,
            error.diagnostic_stage.value,
        )
        return
    error_site = _safe_error_site(error)
    if error_site is not None:
        error_key = _safe_schema_key_error_value(error)
        if error_key is not None:
            LOGGER.error(
                "%s failed: error_type=%s error_site=%s:%s:%s error_key=%s",
                stage,
                type(error).__name__,
                *error_site,
                error_key,
            )
            return
        LOGGER.error(
            "%s failed: error_type=%s error_site=%s:%s:%s",
            stage,
            type(error).__name__,
            *error_site,
        )
        return
    LOGGER.error("%s failed: error_type=%s", stage, type(error).__name__)


def _safe_error_site(error: Exception) -> tuple[str, str, int] | None:
    """Return the deepest code-owned traceback coordinate without exception data."""

    candidate: tuple[str, str, int] | None = None
    traceback = error.__traceback__
    while traceback is not None:
        frame = traceback.tb_frame
        module_name = frame.f_globals.get("__name__")
        function_name = frame.f_code.co_name
        line_number = traceback.tb_lineno
        if (
            isinstance(module_name, str)
            and _SAFE_ERROR_SITE_MODULE_PATTERN.fullmatch(module_name) is not None
            and _SAFE_ERROR_SITE_FUNCTION_PATTERN.fullmatch(function_name) is not None
            and 1 <= line_number <= 10_000_000
        ):
            candidate = (module_name, function_name, line_number)
        traceback = traceback.tb_next
    return candidate


def _safe_schema_key_error_value(error: Exception) -> str | None:
    if (
        type(error) is KeyError
        and len(error.args) == 1
        and type(error.args[0]) is str
        and error.args[0] in _SAFE_SCHEMA_KEY_ERROR_VALUES
    ):
        return error.args[0]
    return None


class _BodyTooLarge(ValueError):
    pass
