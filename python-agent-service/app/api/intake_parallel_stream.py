"""Strict transport validator for interleaved parallel Intake Frame events."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
import json
from typing import Protocol

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.intake.parallel_contracts import FRAME_TYPES, ParallelFrameType
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


PARALLEL_INTAKE_AGENT_PROFILE_ID = "dispute-intake-officer.parallel-frames.v1"
PARALLEL_INTAKE_OUTPUT_SCHEMA = "target-e2e-room-proposal-source.v2"


class ParallelFrameStreamProtocolError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class ExpectedParallelFrame:
    frame_type: ParallelFrameType
    generation: int
    frame_id: str
    frame_model_input_sha256: str
    context_envelope_sha256: str
    model_context_view_sha256: str

    def __post_init__(self) -> None:
        if (
            self.frame_type not in FRAME_TYPES
            or isinstance(self.generation, bool)
            or self.generation < 1
            or not self.frame_id
            or len(self.frame_id) > 128
            or any(
                len(value) != 64
                or any(character not in "0123456789abcdef" for character in value)
                for value in (
                    self.frame_model_input_sha256,
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
            or len({frame.context_envelope_sha256 for frame in self.frames}) != 1
            or len({frame.model_context_view_sha256 for frame in self.frames}) != 1
        ):
            raise ParallelFrameStreamProtocolError(
                "parallel Frame stream authority is not exact-three"
            )


@dataclass(frozen=True, slots=True)
class OpenedParallelFrameStream:
    authority: ParallelFrameStreamAuthority
    events: AsyncIterator[ParallelFrameTechnicalEvent]


class ParallelIntakeFrameStreamService(Protocol):
    async def open_stream(self, **kwargs: object) -> OpenedParallelFrameStream: ...


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

    def __init__(self, authority: ParallelFrameStreamAuthority) -> None:
        if not isinstance(authority, ParallelFrameStreamAuthority):
            raise ParallelFrameStreamProtocolError("parallel Frame authority is not typed")
        self._authority = authority
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
            for state in self._states.values()
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
            canonical_parallel_public_projection(event.frame_type, item).model_dump(
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


async def stream_parallel_frame_ndjson(
    *,
    iterator: AsyncIterator[ParallelFrameTechnicalEvent],
    validator: ParallelFrameStreamProtocolValidator,
    first_line: bytes,
) -> AsyncIterator[bytes]:
    yield first_line
    async for event in iterator:
        yield encode_parallel_frame_event(validator, event)
    validator.finish()


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
    "ParallelFrameStreamAuthority",
    "ParallelFrameStreamProtocolError",
    "ParallelFrameStreamProtocolValidator",
    "ParallelIntakeFrameStreamService",
    "encode_parallel_frame_event",
    "stream_parallel_frame_ndjson",
]
