from __future__ import annotations

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from app.streaming import (
    AgentStreamV2Event,
    StreamFinalEvent,
    StreamStartEvent,
    StreamV2Payload,
    StreamVisibleDeltaEvent,
    V2DeltaCoalescer,
    adapt_v1_event_to_v2,
    encode_stream_event,
)


NOW = "2026-07-19T00:00:00+00:00"


def test_v1_adapter_preserves_identity_and_drops_raw_final_response() -> None:
    start = StreamStartEvent(
        run_id="run-1",
        sequence=0,
        timestamp=NOW,
        operation="evidence_turn",
    )
    final = StreamFinalEvent(
        run_id="run-1",
        sequence=2,
        timestamp=NOW,
        operation="evidence_turn",
        response={"reasoning_content": "must-not-leak", "room_utterance": "public"},
    )

    started = adapt_v1_event_to_v2(
        start,
        attempt_id="attempt-1",
        audience="USER",
        allowed_fields=frozenset({"room_utterance"}),
    )
    projected_final = adapt_v1_event_to_v2(
        final,
        attempt_id="attempt-1",
        audience="USER",
        allowed_fields=frozenset({"room_utterance"}),
        final_result_ref="urn:result:1",
        final_result_hash="a" * 64,
    )

    assert started.event_type == "attempt_started"
    assert projected_final.payload.final_result_ref == "urn:result:1"
    assert "reasoning_content" not in encode_stream_event(projected_final)
    assert "room_utterance" not in encode_stream_event(projected_final)


def test_v1_adapter_denies_non_public_delta() -> None:
    hidden = StreamVisibleDeltaEvent(
        run_id="run-1",
        sequence=1,
        timestamp=NOW,
        node_name="evidence_turn",
        field="reasoning_content",
        delta="secret",
    )

    with pytest.raises(ValueError, match="non-public"):
        adapt_v1_event_to_v2(
            hidden,
            attempt_id="attempt-1",
            audience="USER",
            allowed_fields=frozenset({"room_utterance"}),
        )


def test_v2_model_rejects_unknown_payload_and_unbound_final() -> None:
    with pytest.raises(ValidationError):
        AgentStreamV2Event.model_validate(
            {
                "schema_version": "agent-stream.v2",
                "run_id": "run-1",
                "attempt_id": "attempt-1",
                "sequence_no": 1,
                "event_type": "visible_delta",
                "audience": "USER",
                "occurred_at": NOW,
                "payload": {
                    "node": "evidence_turn",
                    "field": "room_utterance",
                    "delta": "public",
                    "raw_response": {"secret": True},
                },
            }
        )

    with pytest.raises(ValidationError, match="incompatible payload fields"):
        AgentStreamV2Event.model_validate(
            {
                "schema_version": "agent-stream.v2",
                "run_id": "run-1",
                "attempt_id": "attempt-1",
                "sequence_no": 1,
                "event_type": "visible_delta",
                "audience": "USER",
                "occurred_at": NOW,
                "payload": {
                    "node": "evidence_turn",
                    "field": "room_utterance",
                    "delta": "public",
                    "error_code": "MUST_NOT_RIDE_WITH_A_DELTA",
                },
            }
        )

    with pytest.raises(ValueError, match="persisted result reference"):
        adapt_v1_event_to_v2(
            StreamFinalEvent(
                run_id="run-1",
                sequence=2,
                timestamp=NOW,
                operation="evidence_turn",
                response={"room_utterance": "public"},
            ),
            attempt_id="attempt-1",
            audience="USER",
            allowed_fields=frozenset({"room_utterance"}),
        )


def test_v2_model_enforces_identifier_and_reference_bounds() -> None:
    with pytest.raises(ValidationError):
        AgentStreamV2Event.model_validate(
            {
                **delta(1, "public").model_dump(),
                "run_id": "run with spaces",
            }
        )

    with pytest.raises(ValidationError):
        AgentStreamV2Event(
            run_id="run-1",
            attempt_id="attempt-1",
            sequence_no=2,
            event_type="final",
            audience="USER",
            occurred_at=datetime.now(timezone.utc),
            payload=StreamV2Payload(
                final_result_ref="urn:" + "x" * 1025,
                final_result_hash="a" * 64,
            ),
        )


def test_delta_coalescer_batches_only_compatible_bounded_deltas() -> None:
    coalescer = V2DeltaCoalescer(window_ms=75, max_chars=1024)
    first = delta(1, "hello")
    second = delta(2, " world")
    other_field = delta(3, "next", field="public_message")

    assert coalescer.push(first, now_ms=0) == []
    assert coalescer.push(second, now_ms=50) == []
    emitted = coalescer.push(other_field, now_ms=60)
    assert [event.payload.delta for event in emitted] == ["hello world"]
    assert [event.payload.delta for event in coalescer.flush()] == ["next"]


def delta(sequence: int, text: str, *, field: str = "room_utterance") -> AgentStreamV2Event:
    return AgentStreamV2Event(
        run_id="run-1",
        attempt_id="attempt-1",
        sequence_no=sequence,
        event_type="visible_delta",
        audience="USER",
        occurred_at=datetime.now(timezone.utc),
        payload=StreamV2Payload(
            node="evidence_turn",
            field=field,
            delta=text,
        ),
    )
