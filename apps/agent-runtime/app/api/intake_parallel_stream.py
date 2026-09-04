"""Strict transport validator for interleaved parallel Intake Frame events."""

from __future__ import annotations

import base64
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
import json
import re
from typing import Literal, Protocol, cast

import anyio

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.contracts.v1.models import (
    PARALLEL_INTAKE_AGENT_PROFILE_ID,
    PARALLEL_INTAKE_OUTPUT_SCHEMA,
)
from app.graphs.intake.parallel_contracts import (
    FRAME_TYPES,
    ParallelFrameType,
    PartyRole,
)
from app.graphs.intake.parallel_graph import (
    FrameGenerationReset,
    FrameInterrupted,
    FrameProjectionItem,
    FrameSealed,
    FrameStarted,
    ParallelFrameTechnicalEvent,
    canonical_parallel_public_projection,
)
from app.graphs.intake.parallel_outputs import validate_parallel_frame_output
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.errors import GraphRuntimeError
from app.graph_runtime.production_runtime import VerifiedProductionInvocation
from app.contracts.v1.models import RoomGraphCommand


class ParallelFrameStreamProtocolError(RuntimeError):
    pass


_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SAFE_CODE = re.compile(r"^[A-Z][A-Z0-9_]{2,127}$")
_STREAM_CLOSE_TIMEOUT_SECONDS = 5.0


@dataclass(frozen=True, slots=True)
class ExpectedParallelFrame:
    frame_type: ParallelFrameType
    actor_role: PartyRole
    generation: int
    frame_id: str
    frame_model_input_sha256: str
    frame_prompt_sha256: str
    context_envelope_sha256: str
    model_context_view_sha256: str

    def __post_init__(self) -> None:
        if (
            self.frame_type not in FRAME_TYPES
            or self.actor_role not in {"USER", "MERCHANT"}
            or isinstance(self.generation, bool)
            or self.generation < 1
            or not self.frame_id
            or len(self.frame_id) > 128
            or any(
                len(value) != 64
                or any(character not in "0123456789abcdef" for character in value)
                for value in (
                    self.frame_model_input_sha256,
                    self.frame_prompt_sha256,
                    self.context_envelope_sha256,
                    self.model_context_view_sha256,
                )
            )
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel Frame transport authority is invalid"
            )


@dataclass(frozen=True, slots=True)
class ParallelFrameStreamAuthority:
    frame_set_id: str
    run_id: str
    attempt_id: str
    frames: tuple[ExpectedParallelFrame, ExpectedParallelFrame, ExpectedParallelFrame]

    def __post_init__(self) -> None:
        if (
            not self.frame_set_id
            or len(self.frame_set_id) > 128
            or not self.run_id
            or len(self.run_id) > 128
            or not self.attempt_id
            or len(self.attempt_id) > 128
            or tuple(frame.frame_type for frame in self.frames) != FRAME_TYPES
            or len({frame.actor_role for frame in self.frames}) != 1
            or len({frame.context_envelope_sha256 for frame in self.frames}) != 1
            or len({frame.model_context_view_sha256 for frame in self.frames}) != 1
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel Frame stream authority is not exact-three"
            )


@dataclass(frozen=True, slots=True)
class ParallelFrameAdmissionLane:
    frame_type: ParallelFrameType
    generation: int
    frame_id: str
    slot_state: Literal["ADMITTED", "SEALED"]
    action: Literal["SKIP_SEALED", "RUN_CURRENT", "RUN_RETRY"]
    next_local_index: int
    slot_version: int
    result_id: str | None
    result_sha256: str | None
    public_projection_sha256: str | None
    predecessor_failure_code: str | None


@dataclass(frozen=True, slots=True)
class ParallelFrameAdmissionReceipt:
    request_hash: str
    frame_set_id: str
    run_id: str
    attempt_id: str
    java_receipt_id: str
    authority_sha256: str
    lanes: tuple[
        ParallelFrameAdmissionLane,
        ParallelFrameAdmissionLane,
        ParallelFrameAdmissionLane,
    ]
    receipt_sha256: str

    def canonical_document(self) -> dict[str, object]:
        document: dict[str, object] = {
            "schema_version": "intake.parallel-admission-receipt.v1",
            "request_hash": self.request_hash,
            "frame_set_id": self.frame_set_id,
            "run_id": self.run_id,
            "attempt_id": self.attempt_id,
            "java_receipt_id": self.java_receipt_id,
            "authority_sha256": self.authority_sha256,
            "lanes": [
                {
                    "frame_type": lane.frame_type,
                    "generation": lane.generation,
                    "frame_id": lane.frame_id,
                    "slot_state": lane.slot_state,
                    "action": lane.action,
                    "next_local_index": lane.next_local_index,
                    "slot_version": lane.slot_version,
                    "result_id": lane.result_id,
                    "result_sha256": lane.result_sha256,
                    "public_projection_sha256": lane.public_projection_sha256,
                    "predecessor_failure_code": lane.predecessor_failure_code,
                }
                for lane in self.lanes
            ],
            "receipt_sha256": self.receipt_sha256,
        }
        unsigned = dict(document)
        unsigned.pop("receipt_sha256")
        if canonical_sha256(unsigned) != self.receipt_sha256:
            raise ParallelFrameStreamProtocolError(
                "parallel admission receipt self-hash drifted"
            )
        return document

    def require_authority(
        self,
        *,
        command: RoomGraphCommand,
        authority: ParallelFrameStreamAuthority,
    ) -> ParallelFrameStreamAuthority:
        expected_frames = tuple(
            (frame.frame_type, frame.generation, frame.frame_id)
            for frame in authority.frames
        )
        if (
            self.request_hash != command.request_hash
            or self.frame_set_id != authority.frame_set_id
            or self.run_id != authority.run_id
            or self.attempt_id != authority.attempt_id
            or self.run_id != command.logical_run_id
            or self.attempt_id != command.attempt_id
            or self.authority_sha256 != parallel_frame_authority_sha256(authority)
            or tuple(lane.frame_type for lane in self.lanes) != FRAME_TYPES
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel admission receipt differs from prepared authority"
            )
        projected: list[ExpectedParallelFrame] = []
        for prepared, lane, expected in zip(
            authority.frames, self.lanes, expected_frames, strict=True
        ):
            if lane.frame_type != expected[0]:
                raise ParallelFrameStreamProtocolError(
                    "parallel admission receipt Frame order drifted"
                )
            skip = lane.action == "SKIP_SEALED"
            retry = lane.action == "RUN_RETRY"
            current = lane.action == "RUN_CURRENT"
            if (
                lane.action not in {"RUN_CURRENT", "RUN_RETRY", "SKIP_SEALED"}
                or lane.generation < 1
                or lane.generation > 2
                or lane.next_local_index < 0
                or lane.slot_version < 0
                or (skip and lane.slot_state != "SEALED")
                or (not skip and lane.slot_state != "ADMITTED")
                or (
                    skip
                    and (
                        lane.result_id is None
                        or lane.result_sha256 is None
                        or lane.public_projection_sha256 is None
                        or lane.predecessor_failure_code is not None
                    )
                )
                or (
                    not skip
                    and (
                        lane.next_local_index != 0
                        or lane.result_id is not None
                        or lane.result_sha256 is not None
                        or lane.public_projection_sha256 is not None
                    )
                )
                or (retry != (lane.predecessor_failure_code is not None))
                or (retry and lane.generation != 2)
                or (
                    current
                    and (
                        lane.generation != prepared.generation
                        or lane.frame_id != prepared.frame_id
                    )
                )
                or (
                    retry
                    and (
                        lane.generation != prepared.generation + 1
                        or lane.frame_id == prepared.frame_id
                    )
                )
                or (
                    skip
                    and (
                        lane.generation < prepared.generation
                        or lane.generation > prepared.generation + 1
                        or (
                            lane.generation == prepared.generation
                            and lane.frame_id != prepared.frame_id
                        )
                    )
                )
            ):
                raise ParallelFrameStreamProtocolError(
                    "parallel admission receipt lane state is invalid"
                )
            projected.append(
                ExpectedParallelFrame(
                    frame_type=prepared.frame_type,
                    actor_role=prepared.actor_role,
                    generation=lane.generation,
                    frame_id=lane.frame_id,
                    frame_model_input_sha256=prepared.frame_model_input_sha256,
                    frame_prompt_sha256=prepared.frame_prompt_sha256,
                    context_envelope_sha256=prepared.context_envelope_sha256,
                    model_context_view_sha256=prepared.model_context_view_sha256,
                )
            )
        return ParallelFrameStreamAuthority(
            frame_set_id=authority.frame_set_id,
            run_id=authority.run_id,
            attempt_id=authority.attempt_id,
            frames=tuple(projected),  # type: ignore[arg-type]
        )


@dataclass(frozen=True, slots=True)
class ParallelFrameFailureTerminationReceipt:
    receipt_id: str
    request_hash: str
    frame_set_id: str
    run_id: str
    attempt_id: str
    command_id: str
    admission_receipt_sha256: str
    requested_failure_code: str
    graph_command_status: Literal["ABORTED", "CANCELLED"]
    graph_attempt_status: Literal["FAILED", "LEASE_LOST", "CANCELLED", "ABSENT"]
    graph_error_code: str
    graph_error_classification: str
    provider_permit_statuses: tuple[str, ...]
    receipt_sha256: str

    def __post_init__(self) -> None:
        identifiers = (
            self.receipt_id,
            self.frame_set_id,
            self.run_id,
            self.attempt_id,
            self.command_id,
            self.requested_failure_code,
            self.graph_error_code,
            self.graph_error_classification,
        )
        terminal_permit_statuses = {
            "RELEASED",
            "CANCELLED",
            "EXPIRED",
            "TIMED_OUT",
            "ORPHANED",
        }
        if (
            any(_IDENTIFIER.fullmatch(value) is None for value in identifiers)
            or any(
                _SAFE_CODE.fullmatch(value) is None
                for value in (
                    self.requested_failure_code,
                    self.graph_error_code,
                    self.graph_error_classification,
                )
            )
            or _SHA256.fullmatch(self.request_hash) is None
            or _SHA256.fullmatch(self.admission_receipt_sha256) is None
            or _SHA256.fullmatch(self.receipt_sha256) is None
            or any(status not in terminal_permit_statuses for status in self.provider_permit_statuses)
            or tuple(sorted(self.provider_permit_statuses)) != self.provider_permit_statuses
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel failure termination receipt is invalid"
            )
        self.canonical_document()

    def canonical_document(self) -> dict[str, object]:
        document: dict[str, object] = {
            "schema_version": "intake.parallel-failure-termination.v1",
            "receipt_id": self.receipt_id,
            "request_hash": self.request_hash,
            "frame_set_id": self.frame_set_id,
            "run_id": self.run_id,
            "attempt_id": self.attempt_id,
            "command_id": self.command_id,
            "admission_receipt_sha256": self.admission_receipt_sha256,
            "requested_failure_code": self.requested_failure_code,
            "graph_command_status": self.graph_command_status,
            "graph_attempt_status": self.graph_attempt_status,
            "graph_error_code": self.graph_error_code,
            "graph_error_classification": self.graph_error_classification,
            "provider_permit_statuses": list(self.provider_permit_statuses),
            "receipt_sha256": self.receipt_sha256,
        }
        unsigned = dict(document)
        unsigned.pop("receipt_sha256")
        if canonical_sha256(unsigned) != self.receipt_sha256:
            raise ParallelFrameStreamProtocolError(
                "parallel failure termination receipt self-hash drifted"
            )
        return document

    @classmethod
    def create(
        cls,
        *,
        request_hash: str,
        frame_set_id: str,
        run_id: str,
        attempt_id: str,
        command_id: str,
        admission_receipt_sha256: str,
        requested_failure_code: str,
        graph_command_status: Literal["ABORTED", "CANCELLED"],
        graph_attempt_status: Literal["FAILED", "LEASE_LOST", "CANCELLED", "ABSENT"],
        graph_error_code: str,
        graph_error_classification: str,
        provider_permit_statuses: tuple[str, ...],
    ) -> ParallelFrameFailureTerminationReceipt:
        values: dict[str, object] = {
            "schema_version": "intake.parallel-failure-termination.v1",
            "receipt_id": f"parallel-failure-terminal.{admission_receipt_sha256[:24]}",
            "request_hash": request_hash,
            "frame_set_id": frame_set_id,
            "run_id": run_id,
            "attempt_id": attempt_id,
            "command_id": command_id,
            "admission_receipt_sha256": admission_receipt_sha256,
            "requested_failure_code": requested_failure_code,
            "graph_command_status": graph_command_status,
            "graph_attempt_status": graph_attempt_status,
            "graph_error_code": graph_error_code,
            "graph_error_classification": graph_error_classification,
            "provider_permit_statuses": list(provider_permit_statuses),
        }
        return cls(
            receipt_id=cast(str, values["receipt_id"]),
            request_hash=request_hash,
            frame_set_id=frame_set_id,
            run_id=run_id,
            attempt_id=attempt_id,
            command_id=command_id,
            admission_receipt_sha256=admission_receipt_sha256,
            requested_failure_code=requested_failure_code,
            graph_command_status=graph_command_status,
            graph_attempt_status=graph_attempt_status,
            graph_error_code=graph_error_code,
            graph_error_classification=graph_error_classification,
            provider_permit_statuses=provider_permit_statuses,
            receipt_sha256=canonical_sha256(values),
        )


@dataclass(frozen=True, slots=True)
class OpenedParallelFrameStream:
    authority: ParallelFrameStreamAuthority
    active_frame_types: tuple[ParallelFrameType, ...]
    events: AsyncIterator[ParallelFrameTechnicalEvent]


class ParallelIntakeFrameStreamService(Protocol):
    async def prepare(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
    ) -> ParallelFrameStreamAuthority: ...

    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
        admission_receipt: ParallelFrameAdmissionReceipt,
    ) -> OpenedParallelFrameStream: ...

    async def terminate_uncommitted_failure(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedProductionInvocation,
        expected_thread: ThreadIdentity,
        admission_receipt: ParallelFrameAdmissionReceipt,
        failure_code: str,
    ) -> ParallelFrameFailureTerminationReceipt: ...


@dataclass(slots=True)
class _FrameTransportState:
    expected: ExpectedParallelFrame
    generation: int
    frame_id: str
    started: bool = False
    interrupted: FrameInterrupted | None = None
    reset_count: int = 0
    next_local_index: int = 0
    canonical_items: list[dict[str, object]] = field(default_factory=list)
    sealed: bool = False


class ParallelFrameStreamProtocolValidator:
    """Validate three independent lane state machines while permitting interleaving."""

    def __init__(
        self,
        authority: ParallelFrameStreamAuthority,
        active_frame_types: tuple[ParallelFrameType, ...] = FRAME_TYPES,
    ) -> None:
        if not isinstance(authority, ParallelFrameStreamAuthority):
            raise ParallelFrameStreamProtocolError("parallel Frame authority is not typed")
        if (
            not active_frame_types
            or len(set(active_frame_types)) != len(active_frame_types)
            or any(frame_type not in FRAME_TYPES for frame_type in active_frame_types)
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel active Frame subset is invalid"
            )
        self._authority = authority
        self._active_frame_types = frozenset(active_frame_types)
        self._states = {
            frame.frame_type: _FrameTransportState(
                expected=frame,
                generation=frame.generation,
                frame_id=frame.frame_id,
            )
            for frame in authority.frames
        }

    @property
    def authority(self) -> ParallelFrameStreamAuthority:
        return self._authority

    def accept(self, event: ParallelFrameTechnicalEvent) -> None:
        if not isinstance(
            event,
            (
                FrameStarted,
                FrameProjectionItem,
                FrameGenerationReset,
                FrameInterrupted,
                FrameSealed,
            ),
        ):
            raise ParallelFrameStreamProtocolError("parallel Frame event type is invalid")
        if (
            event.frame_set_id != self._authority.frame_set_id
            or event.run_id != self._authority.run_id
            or event.attempt_id != self._authority.attempt_id
            or event.frame_type not in self._active_frame_types
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel Frame event crossed stream authority"
            )
        state = self._states[event.frame_type]
        if state.sealed:
            raise ParallelFrameStreamProtocolError("parallel Frame emitted after seal")
        if isinstance(event, FrameStarted):
            self._accept_started(state, event)
        elif isinstance(event, FrameProjectionItem):
            self._accept_projection(state, event)
        elif isinstance(event, FrameInterrupted):
            self._accept_interrupted(state, event)
        elif isinstance(event, FrameGenerationReset):
            self._accept_reset(state, event)
        else:
            self._accept_sealed(state, event)

    def finish(self) -> None:
        terminal = [
            state.sealed or state.interrupted is not None
            for frame_type, state in self._states.items()
            if frame_type in self._active_frame_types
        ]
        if not all(terminal):
            raise ParallelFrameStreamProtocolError(
                "parallel Frame stream ended with a non-terminal lane"
            )

    @staticmethod
    def _accept_started(state: _FrameTransportState, event: FrameStarted) -> None:
        if (
            state.started
            or state.interrupted is not None
            or event.generation != state.generation
            or event.frame_id != state.frame_id
            or event.frame_model_input_sha256 != state.expected.frame_model_input_sha256
            or event.frame_prompt_sha256 != state.expected.frame_prompt_sha256
            or event.context_envelope_sha256 != state.expected.context_envelope_sha256
            or event.model_context_view_sha256
            != state.expected.model_context_view_sha256
        ):
            raise ParallelFrameStreamProtocolError("parallel Frame start is invalid")
        state.started = True

    @staticmethod
    def _accept_projection(
        state: _FrameTransportState,
        event: FrameProjectionItem,
    ) -> None:
        if (
            not state.started
            or state.interrupted is not None
            or event.generation != state.generation
            or event.frame_id != state.frame_id
            or event.local_index != state.next_local_index
            or event.next_local_index != state.next_local_index + 1
        ):
            raise ParallelFrameStreamProtocolError("parallel Frame projection is invalid")
        state.canonical_items.append(
            event.item.model_dump(mode="json", exclude_none=True)
        )
        state.next_local_index = event.next_local_index

    @staticmethod
    def _accept_interrupted(
        state: _FrameTransportState,
        event: FrameInterrupted,
    ) -> None:
        if (
            not state.started
            or state.interrupted is not None
            or event.generation != state.generation
            or event.frame_id != state.frame_id
        ):
            raise ParallelFrameStreamProtocolError("parallel Frame interruption is invalid")
        state.interrupted = event

    @staticmethod
    def _accept_reset(
        state: _FrameTransportState,
        event: FrameGenerationReset,
    ) -> None:
        interrupted = state.interrupted
        if (
            interrupted is None
            or not interrupted.retryable
            or interrupted.error_code != event.reason_code
            or state.reset_count != 0
            or event.old_generation != state.generation
            or event.old_frame_id != state.frame_id
            or event.new_generation != state.generation + 1
            or event.new_generation != state.expected.generation + 1
        ):
            raise ParallelFrameStreamProtocolError("parallel Frame reset is invalid")
        state.generation = event.new_generation
        state.frame_id = event.new_frame_id
        state.started = False
        state.interrupted = None
        state.reset_count = 1
        state.next_local_index = 0
        state.canonical_items.clear()

    @staticmethod
    def _accept_sealed(state: _FrameTransportState, event: FrameSealed) -> None:
        if (
            not state.started
            or state.interrupted is not None
            or event.generation != state.generation
            or event.frame_id != state.frame_id
            or event.next_local_index != state.next_local_index
            or event.context_envelope_sha256 != state.expected.context_envelope_sha256
            or event.model_context_view_sha256
            != state.expected.model_context_view_sha256
        ):
            raise ParallelFrameStreamProtocolError("parallel Frame seal is invalid")
        try:
            result_json = json.loads(
                event.canonical_result_json,
                object_pairs_hook=_unique_json_object,
            )
            if not isinstance(result_json, dict):
                raise ValueError("Frame result is not an object")
            if canonicalize(result_json).decode("utf-8") != event.canonical_result_json:
                raise ValueError("Frame result bytes are not canonical")
            result = validate_parallel_frame_output(event.frame_type, result_json)
        except Exception as error:
            raise ParallelFrameStreamProtocolError(
                "parallel Frame sealed result is invalid"
            ) from error
        expected_items = [
            canonical_parallel_public_projection(
                event.frame_type,
                item,
                actor_role=state.expected.actor_role,
            ).model_dump(
                mode="json",
                exclude_none=True,
            )
            for item in result.public_projection_items
        ]
        if (
            state.canonical_items != expected_items
            or canonical_sha256(result_json) != event.result_sha256
            or canonical_sha256(state.canonical_items)
            != event.public_projection_sha256
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel Frame sealed hashes are invalid"
            )
        state.sealed = True


def encode_parallel_frame_event(
    validator: ParallelFrameStreamProtocolValidator,
    event: ParallelFrameTechnicalEvent,
) -> bytes:
    validator.accept(event)
    return canonicalize(event.model_dump(mode="json", exclude_none=True)) + b"\n"


def encode_parallel_stream_failure(
    validator: ParallelFrameStreamProtocolValidator,
    error: BaseException,
) -> bytes:
    """Encode a bound public-safe failure after the HTTP success metadata was sent."""

    authority = validator.authority
    if isinstance(error, GraphRuntimeError):
        error_code = error.code
        retryable = bool(error.retryable)
    elif isinstance(error, ParallelFrameStreamProtocolError):
        error_code = "GRAPH_STREAM_PROTOCOL_REJECTED"
        retryable = False
    else:
        error_code = "GRAPH_STREAM_INTERNAL_ERROR"
        retryable = False
    if not isinstance(error_code, str) or re.fullmatch(
        r"[A-Z][A-Z0-9_]{2,127}", error_code
    ) is None:
        error_code = "GRAPH_STREAM_INTERNAL_ERROR"
        retryable = False
    document: dict[str, object] = {
        "schema_version": "intake.parallel-stream-failure.v1",
        "event_kind": "PARALLEL_STREAM_FAILED",
        "frame_set_id": authority.frame_set_id,
        "run_id": authority.run_id,
        "attempt_id": authority.attempt_id,
        "authority_sha256": parallel_frame_authority_sha256(authority),
        "error_code": error_code,
        "retryable": retryable,
    }
    return canonicalize(document) + b"\n"


def encode_parallel_frame_authority_header(
    authority: ParallelFrameStreamAuthority,
) -> str:
    """Encode the exact-three pre-provider authority into one bounded response header."""

    document = _parallel_frame_authority_document(authority)
    document["authority_sha256"] = canonical_sha256(document)
    encoded = base64.urlsafe_b64encode(canonicalize(document)).decode("ascii")
    return encoded.rstrip("=")


def parallel_frame_authority_sha256(authority: ParallelFrameStreamAuthority) -> str:
    return canonical_sha256(_parallel_frame_authority_document(authority))


def _parallel_frame_authority_document(
    authority: ParallelFrameStreamAuthority,
) -> dict[str, object]:
    return {
        "schema_version": "intake.parallel-frame-stream-authority.v1",
        "frame_set_id": authority.frame_set_id,
        "run_id": authority.run_id,
        "attempt_id": authority.attempt_id,
        "frames": [
            {
                "frame_type": frame.frame_type,
                "generation": frame.generation,
                "frame_id": frame.frame_id,
                "frame_model_input_sha256": frame.frame_model_input_sha256,
                "frame_prompt_sha256": frame.frame_prompt_sha256,
                "context_envelope_sha256": frame.context_envelope_sha256,
                "model_context_view_sha256": frame.model_context_view_sha256,
            }
            for frame in authority.frames
        ],
    }


def decode_parallel_admission_receipt_header(
    encoded: str | None,
) -> ParallelFrameAdmissionReceipt:
    if encoded is None or not encoded or len(encoded) > 24 * 1024:
        raise ParallelFrameStreamProtocolError(
            "parallel admission receipt header is absent or oversized"
        )
    try:
        padding = (4 - len(encoded) % 4) % 4
        raw = base64.urlsafe_b64decode(encoded + "=" * padding)
        if not raw or len(raw) > 12 * 1024:
            raise ValueError("receipt bytes are oversized")
        document = json.loads(raw, object_pairs_hook=_unique_json_object)
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise ParallelFrameStreamProtocolError(
            "parallel admission receipt header is invalid"
        ) from error
    expected_fields = {
        "schema_version",
        "request_hash",
        "frame_set_id",
        "run_id",
        "attempt_id",
        "java_receipt_id",
        "authority_sha256",
        "lanes",
        "receipt_sha256",
    }
    if not isinstance(document, dict) or set(document) != expected_fields:
        raise ParallelFrameStreamProtocolError(
            "parallel admission receipt fields differ"
        )
    receipt_hash = document.get("receipt_sha256")
    unsigned = dict(document)
    unsigned.pop("receipt_sha256", None)
    if (
        document.get("schema_version") != "intake.parallel-admission-receipt.v1"
        or not isinstance(receipt_hash, str)
        or _SHA256.fullmatch(receipt_hash) is None
        or canonical_sha256(unsigned) != receipt_hash
    ):
        raise ParallelFrameStreamProtocolError(
            "parallel admission receipt self-hash drifted"
        )
    raw_lanes = document.get("lanes")
    if not isinstance(raw_lanes, list) or len(raw_lanes) != len(FRAME_TYPES):
        raise ParallelFrameStreamProtocolError(
            "parallel admission receipt is not exact-three"
        )
    lanes: list[ParallelFrameAdmissionLane] = []
    for index, value in enumerate(raw_lanes):
        if not isinstance(value, dict) or set(value) != {
            "frame_type",
            "generation",
            "frame_id",
            "slot_state",
            "action",
            "next_local_index",
            "slot_version",
            "result_id",
            "result_sha256",
            "public_projection_sha256",
            "predecessor_failure_code",
        }:
            raise ParallelFrameStreamProtocolError(
                "parallel admission lane fields differ"
            )
        raw_frame_type = value["frame_type"]
        if not isinstance(raw_frame_type, str) or raw_frame_type not in FRAME_TYPES:
            raise ParallelFrameStreamProtocolError(
                "parallel admission lane type is invalid"
            )
        frame_type = cast(ParallelFrameType, raw_frame_type)
        generation = value["generation"]
        next_local_index = value["next_local_index"]
        slot_version = value["slot_version"]
        frame_id = value["frame_id"]
        action = value["action"]
        slot_state = value["slot_state"]
        result_id = value["result_id"]
        result_sha256 = value["result_sha256"]
        public_projection_sha256 = value["public_projection_sha256"]
        predecessor_failure_code = value["predecessor_failure_code"]
        if (
            frame_type != FRAME_TYPES[index]
            or type(generation) is not int
            or generation < 1
            or generation > 2
            or not isinstance(frame_id, str)
            or _IDENTIFIER.fullmatch(frame_id) is None
            or action not in {"SKIP_SEALED", "RUN_CURRENT", "RUN_RETRY"}
            or slot_state not in {"ADMITTED", "SEALED"}
            or type(next_local_index) is not int
            or next_local_index < 0
            or type(slot_version) is not int
            or slot_version < 0
            or (
                result_id is not None
                and (
                    not isinstance(result_id, str)
                    or _IDENTIFIER.fullmatch(result_id) is None
                )
            )
            or any(
                item is not None
                and (not isinstance(item, str) or _SHA256.fullmatch(item) is None)
                for item in (result_sha256, public_projection_sha256)
            )
            or (
                predecessor_failure_code is not None
                and (
                    not isinstance(predecessor_failure_code, str)
                    or _IDENTIFIER.fullmatch(predecessor_failure_code) is None
                )
            )
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel admission lane authority is invalid"
            )
        lanes.append(
            ParallelFrameAdmissionLane(
                frame_type=frame_type,
                generation=generation,
                frame_id=frame_id,
                slot_state=slot_state,
                action=action,
                next_local_index=next_local_index,
                slot_version=slot_version,
                result_id=result_id,
                result_sha256=result_sha256,
                public_projection_sha256=public_projection_sha256,
                predecessor_failure_code=predecessor_failure_code,
            )
        )
    text_fields = (
        "frame_set_id",
        "run_id",
        "attempt_id",
        "java_receipt_id",
    )
    if any(
        not isinstance(document[field], str)
        or _IDENTIFIER.fullmatch(document[field]) is None
        for field in text_fields
    ) or any(
        not isinstance(document[field], str)
        or _SHA256.fullmatch(document[field]) is None
        for field in ("request_hash", "authority_sha256")
    ):
        raise ParallelFrameStreamProtocolError(
            "parallel admission receipt binding is invalid"
        )
    return ParallelFrameAdmissionReceipt(
        request_hash=document["request_hash"],
        frame_set_id=document["frame_set_id"],
        run_id=document["run_id"],
        attempt_id=document["attempt_id"],
        java_receipt_id=document["java_receipt_id"],
        authority_sha256=document["authority_sha256"],
        lanes=tuple(lanes),  # type: ignore[arg-type]
        receipt_sha256=receipt_hash,
    )


async def stream_parallel_frame_ndjson(
    *,
    iterator: AsyncIterator[ParallelFrameTechnicalEvent],
    validator: ParallelFrameStreamProtocolValidator,
    first_line: bytes,
) -> AsyncIterator[bytes]:
    try:
        yield first_line
        try:
            async for event in iterator:
                yield encode_parallel_frame_event(validator, event)
            validator.finish()
        except Exception as error:
            # HTTP status and the first technical event are already visible.  Emit one
            # strict authority-bound failure record so Java cannot mistake the end for
            # a clean 200 EOF.  This record never represents a Frame seal/completion.
            yield encode_parallel_stream_failure(validator, error)
    finally:
        await _close_iterator_safely(iterator)


async def _close_iterator_safely(iterator: AsyncIterator[object]) -> None:
    close = getattr(iterator, "aclose", None)
    if not callable(close):
        return
    try:
        # Starlette closes a streaming response under AnyIO level cancellation.
        # Shield the child iterator long enough for its durable attempt/permit/lease
        # teardown to run instead of abandoning Graph execution authority.
        with anyio.fail_after(_STREAM_CLOSE_TIMEOUT_SECONDS, shield=True):
            await close()
    except BaseException:
        # The HTTP stream is already terminal. Cleanup failures are bounded here so
        # they cannot replace the original disconnect/protocol outcome.
        return


def _unique_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = member
    return value


__all__ = [
    "ExpectedParallelFrame",
    "OpenedParallelFrameStream",
    "PARALLEL_INTAKE_AGENT_PROFILE_ID",
    "PARALLEL_INTAKE_OUTPUT_SCHEMA",
    "ParallelFrameAdmissionLane",
    "ParallelFrameAdmissionReceipt",
    "ParallelFrameStreamAuthority",
    "ParallelFrameStreamProtocolError",
    "ParallelFrameStreamProtocolValidator",
    "ParallelIntakeFrameStreamService",
    "decode_parallel_admission_receipt_header",
    "encode_parallel_frame_authority_header",
    "encode_parallel_frame_event",
    "encode_parallel_stream_failure",
    "parallel_frame_authority_sha256",
    "stream_parallel_frame_ndjson",
]
