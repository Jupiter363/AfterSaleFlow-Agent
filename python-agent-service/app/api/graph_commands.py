from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from dataclasses import dataclass, replace
from email.message import Message
from typing import Any, Protocol, cast

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse

from app.contracts.v1.codec import ContractCodec
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    RoomGraphCommand,
)
from app.graph_runtime.errors import (
    GraphCommandDeadlineError,
    GraphGatewayDisabledError,
    GraphLeaseLostError,
    GraphLeaseUnavailableError,
    GraphNonceReplayError,
    GraphRuntimeError,
)
from app.graph_runtime.identity import ThreadIdentity
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    TransportIdentity,
    VerifiedInvocation,
    extract_bearer_token,
)


GRAPH_COMMAND_SCHEMA = "room-graph-command.schema.json"
AGENT_STREAM_SCHEMA = "agent-stream-event.schema.json"
GRAPH_COMMAND_MAX_BYTES = 65_536
GRAPH_STREAM_PATH = "/internal/graphs/commands/stream"
_TERMINAL_EVENTS = frozenset({"attempt_aborted", "final", "error"})
_NO_STORE_HEADERS: Mapping[str, str] = {
    "Cache-Control": "no-store, no-transform",
    "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff",
}
_REQUIRED_PAYLOAD_FIELDS: Mapping[str, frozenset[str]] = {
    "attempt_started": frozenset({"node"}),
    "visible_delta": frozenset({"node", "field", "delta"}),
    "usage": frozenset({"usage"}),
    "attempt_aborted": frozenset({"reason_code"}),
    "attempt_reset": frozenset({"reset_attempt_id", "reason_code"}),
    "final": frozenset({"final_result_ref", "final_result_hash"}),
    "error": frozenset({"error_code", "retryable"}),
}
LOGGER = logging.getLogger(__name__)


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


@dataclass(frozen=True, slots=True)
class GraphCommandEndpointDependencies:
    mode: str
    codec: ContractCodec
    transport_identity_resolver: TransportIdentityResolver
    envelope_verifier: InvocationEnvelopeVerifierPort
    thread_identity_resolver: TrustedThreadIdentityResolver
    stream_service: GraphCommandStreamService
    ready: Callable[[], bool]


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
        if (
            event.schema_version != "agent-stream.v2"
            or event.event_type not in _REQUIRED_PAYLOAD_FIELDS
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
        if present != _REQUIRED_PAYLOAD_FIELDS[event.event_type]:
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
        except (AgentStreamProtocolError, TypeError, ValueError):
            if iterator is not None:
                await _close_iterator_safely(iterator)
            return _error_response(502, "GRAPH_STREAM_PROTOCOL_REJECTED", False)
        except Exception as error:
            if iterator is not None:
                await _close_iterator_safely(iterator)
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

    return router


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
                raise AgentStreamProtocolError(
                    "stream ended without a terminal event"
                ) from error

            candidate = replace(validator)
            encoded = _encode_event(codec, candidate, event)
            if candidate.terminal:
                try:
                    await anext(iterator)
                except StopAsyncIteration:
                    yield encoded
                    return
                raise AgentStreamProtocolError(
                    "stream emitted an event after its terminal event"
                )
            validator = candidate
            yield encoded
    except GraphRuntimeError as error:
        _log_safe_failure("graph stream runtime", error)
        return
    except (AgentStreamProtocolError, TypeError, ValueError) as error:
        _log_safe_failure("graph stream protocol", error)
        return
    except Exception as error:
        _log_safe_failure("graph stream iteration", error)
        return
    finally:
        await _close_iterator_safely(iterator)


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
    return (
        json.dumps(encoded, ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode("utf-8")


async def _close_iterator_safely(iterator: AsyncIterator[AgentStreamEvent]) -> None:
    close = getattr(iterator, "aclose", None)
    if close is not None:
        try:
            await cast(Callable[[], Awaitable[None]], close)()
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
    return not values or (
        len(values) == 1 and values[0].strip().lower() == "identity"
    )


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


def _error_response(status_code: int, code: str, retryable: bool) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"code": code, "retryable": retryable},
        headers=dict(_NO_STORE_HEADERS),
    )


def _log_safe_failure(stage: str, error: Exception) -> None:
    LOGGER.error("%s failed: error_type=%s", stage, type(error).__name__)


class _BodyTooLarge(ValueError):
    pass
