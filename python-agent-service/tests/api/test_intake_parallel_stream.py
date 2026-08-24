from __future__ import annotations

import base64
from datetime import datetime, timezone
from typing import Any

import pytest

from app.api.intake_parallel_stream import (
    decode_parallel_admission_receipt_header,
    ExpectedParallelFrame,
    parallel_frame_authority_sha256,
    ParallelFrameStreamAuthority,
    ParallelFrameStreamProtocolError,
    ParallelFrameStreamProtocolValidator,
)
from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.intake.parallel_contracts import FRAME_TYPES, ParallelFrameType
from app.graphs.intake.parallel_graph import (
    FrameGenerationReset,
    FrameInterrupted,
    FrameProjectionItem,
    FrameProviderUsage,
    FrameSealed,
    FrameStarted,
    canonical_parallel_public_projection,
)
from app.graphs.intake.parallel_outputs import validate_parallel_frame_output


FRAME_SET_ID = "frame-set.test"
RUN_ID = "run.test"
ATTEMPT_ID = "attempt.test"
CONTEXT_HASH = "a" * 64
MODEL_CONTEXT_HASH = "b" * 64


def test_admission_receipt_decodes_exact_three_literal_frame_types() -> None:
    document = _admission_receipt_document()

    receipt = decode_parallel_admission_receipt_header(_encode_receipt(document))

    assert receipt.frame_set_id == FRAME_SET_ID
    assert tuple(lane.frame_type for lane in receipt.lanes) == FRAME_TYPES
    assert receipt.receipt_sha256 == document["receipt_sha256"]


@pytest.mark.parametrize("mutation", ["unknown", "reordered", "tampered_hash"])
def test_admission_receipt_rejects_noncanonical_authority(mutation: str) -> None:
    document = _admission_receipt_document()
    if mutation == "unknown":
        document["unexpected"] = True
        document["receipt_sha256"] = canonical_sha256(
            {key: value for key, value in document.items() if key != "receipt_sha256"}
        )
    elif mutation == "reordered":
        document["lanes"] = list(reversed(document["lanes"]))
        document["receipt_sha256"] = canonical_sha256(
            {key: value for key, value in document.items() if key != "receipt_sha256"}
        )
    else:
        document["request_hash"] = "0" * 64

    with pytest.raises(ParallelFrameStreamProtocolError):
        decode_parallel_admission_receipt_header(_encode_receipt(document))


def test_admission_receipt_rejects_duplicate_json_members() -> None:
    canonical = canonicalize(_admission_receipt_document()).decode("utf-8")
    duplicated = canonical.replace(
        '"receipt_sha256":',
        '"receipt_sha256":"' + ("0" * 64) + '","receipt_sha256":',
        1,
    ).encode("utf-8")

    with pytest.raises(ParallelFrameStreamProtocolError, match="header is invalid"):
        decode_parallel_admission_receipt_header(
            base64.urlsafe_b64encode(duplicated).decode("ascii").rstrip("=")
        )


def test_interleaved_exact_three_lanes_seal_against_their_public_results() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    lane_events = {frame_type: _successful_lane(frame_type) for frame_type in FRAME_TYPES}

    for frame_type in ("DIALOGUE_FRAME", "QUALITY_FRAME", "DOSSIER_FRAME"):
        validator.accept(lane_events[frame_type][0])
    remaining = {frame_type: list(events[1:]) for frame_type, events in lane_events.items()}
    while any(remaining.values()):
        for frame_type in ("QUALITY_FRAME", "DOSSIER_FRAME", "DIALOGUE_FRAME"):
            if remaining[frame_type]:
                validator.accept(remaining[frame_type].pop(0))

    validator.finish()


def test_reset_requires_interruption_and_replacement_start_before_projection() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    expected = _expected("DIALOGUE_FRAME")
    replacement_id = expected.frame_id + ".retry"
    validator.accept(_started(expected))
    validator.accept(
        FrameInterrupted(
            frame_set_id=FRAME_SET_ID,
            run_id=RUN_ID,
            attempt_id=ATTEMPT_ID,
            frame_type="DIALOGUE_FRAME",
            generation=1,
            frame_id=expected.frame_id,
            error_code="OUTPUT_SCHEMA_INVALID",
            retryable=True,
        )
    )
    validator.accept(
        FrameGenerationReset(
            frame_set_id=FRAME_SET_ID,
            run_id=RUN_ID,
            attempt_id=ATTEMPT_ID,
            frame_type="DIALOGUE_FRAME",
            old_generation=1,
            new_generation=2,
            old_frame_id=expected.frame_id,
            new_frame_id=replacement_id,
            reason_code="OUTPUT_SCHEMA_INVALID",
        )
    )
    replacement = _successful_lane(
        "DIALOGUE_FRAME",
        generation=2,
        frame_id=replacement_id,
    )
    for event in replacement:
        validator.accept(event)
    for frame_type in ("DOSSIER_FRAME", "QUALITY_FRAME"):
        for event in _successful_lane(frame_type):
            validator.accept(event)

    validator.finish()


def test_reset_without_matching_interruption_is_rejected() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    expected = _expected("DIALOGUE_FRAME")
    validator.accept(_started(expected))

    with pytest.raises(ParallelFrameStreamProtocolError, match="reset is invalid"):
        validator.accept(
            FrameGenerationReset(
                frame_set_id=FRAME_SET_ID,
                run_id=RUN_ID,
                attempt_id=ATTEMPT_ID,
                frame_type="DIALOGUE_FRAME",
                old_generation=1,
                new_generation=2,
                old_frame_id=expected.frame_id,
                new_frame_id=expected.frame_id + ".retry",
                reason_code="OUTPUT_SCHEMA_INVALID",
            )
        )


def test_projection_must_be_contiguous_within_its_lane() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    expected = _expected("DIALOGUE_FRAME")
    validator.accept(_started(expected))
    valid_projection = _successful_lane("DIALOGUE_FRAME")[1]
    assert isinstance(valid_projection, FrameProjectionItem)

    with pytest.raises(ParallelFrameStreamProtocolError, match="projection is invalid"):
        validator.accept(
            valid_projection.model_copy(
                update={"local_index": 1, "next_local_index": 2}
            )
        )


@pytest.mark.parametrize(
    "update",
    [
        {"result_sha256": "f" * 64},
        {"canonical_result_json": '{ "frame_type": "DIALOGUE_FRAME" }'},
    ],
)
def test_sealed_result_requires_exact_canonical_bytes_and_hash(
    update: dict[str, str],
) -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    events = _successful_lane("DIALOGUE_FRAME")
    for event in events[:-1]:
        validator.accept(event)
    sealed = events[-1]
    assert isinstance(sealed, FrameSealed)

    with pytest.raises(ParallelFrameStreamProtocolError, match="sealed"):
        validator.accept(sealed.model_copy(update=update))


def test_streamed_projection_must_match_sealed_result_projection() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    events = _successful_lane("DIALOGUE_FRAME")
    validator.accept(events[0])
    projection = events[1]
    assert isinstance(projection, FrameProjectionItem)
    changed_item = projection.item.model_copy(update={"public_text": "被替换的展示文本。"})
    validator.accept(
        projection.model_copy(
            update={"item": changed_item, "item_sha256": changed_item.item_sha256}
        )
    )

    with pytest.raises(ParallelFrameStreamProtocolError, match="sealed hashes"):
        validator.accept(events[-1])


def test_finish_rejects_any_lane_without_a_terminal_event() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    for frame_type in ("DIALOGUE_FRAME", "QUALITY_FRAME"):
        for event in _successful_lane(frame_type):
            validator.accept(event)
    validator.accept(_started(_expected("DOSSIER_FRAME")))

    with pytest.raises(ParallelFrameStreamProtocolError, match="non-terminal"):
        validator.finish()


def test_finish_accepts_explicit_failed_lane_after_siblings_seal() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    for frame_type in ("DIALOGUE_FRAME", "QUALITY_FRAME"):
        for event in _successful_lane(frame_type):
            validator.accept(event)
    expected = _expected("DOSSIER_FRAME")
    validator.accept(_started(expected))
    validator.accept(
        FrameInterrupted(
            frame_set_id=FRAME_SET_ID,
            run_id=RUN_ID,
            attempt_id=ATTEMPT_ID,
            frame_type="DOSSIER_FRAME",
            generation=1,
            frame_id=expected.frame_id,
            error_code="INTAKE_PARALLEL_FRAME_EXECUTION_FAILED",
            retryable=True,
        )
    )

    validator.finish()


def test_lane_cannot_emit_after_it_is_sealed() -> None:
    validator = ParallelFrameStreamProtocolValidator(_authority())
    events = _successful_lane("DIALOGUE_FRAME")
    for event in events:
        validator.accept(event)

    with pytest.raises(ParallelFrameStreamProtocolError, match="after seal"):
        validator.accept(events[0])


def _admission_receipt_document() -> dict[str, Any]:
    authority = _authority()
    document: dict[str, Any] = {
        "schema_version": "intake.parallel-admission-receipt.v1",
        "request_hash": "f" * 64,
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
                "action": "RUN",
                "next_local_index": 0,
            }
            for frame in authority.frames
        ],
    }
    document["receipt_sha256"] = canonical_sha256(document)
    return document


def _encode_receipt(document: dict[str, Any]) -> str:
    return base64.urlsafe_b64encode(canonicalize(document)).decode("ascii").rstrip("=")


def _authority() -> ParallelFrameStreamAuthority:
    return ParallelFrameStreamAuthority(
        frame_set_id=FRAME_SET_ID,
        run_id=RUN_ID,
        attempt_id=ATTEMPT_ID,
        frames=tuple(_expected(frame_type) for frame_type in FRAME_TYPES),
    )


def _expected(frame_type: ParallelFrameType) -> ExpectedParallelFrame:
    suffix = FRAME_TYPES.index(frame_type) + 1
    return ExpectedParallelFrame(
        frame_type=frame_type,
        generation=1,
        frame_id=f"frame.{frame_type.lower()}",
        frame_model_input_sha256=str(suffix) * 64,
        frame_prompt_sha256=str(suffix + 3) * 64,
        context_envelope_sha256=CONTEXT_HASH,
        model_context_view_sha256=MODEL_CONTEXT_HASH,
    )


def _started(
    expected: ExpectedParallelFrame,
    *,
    generation: int | None = None,
    frame_id: str | None = None,
) -> FrameStarted:
    return FrameStarted(
        frame_set_id=FRAME_SET_ID,
        run_id=RUN_ID,
        attempt_id=ATTEMPT_ID,
        frame_type=expected.frame_type,
        generation=expected.generation if generation is None else generation,
        frame_id=expected.frame_id if frame_id is None else frame_id,
        frame_model_input_sha256=expected.frame_model_input_sha256,
        frame_prompt_sha256=expected.frame_prompt_sha256,
        context_envelope_sha256=expected.context_envelope_sha256,
        model_context_view_sha256=expected.model_context_view_sha256,
    )


def _successful_lane(
    frame_type: ParallelFrameType,
    *,
    generation: int = 1,
    frame_id: str | None = None,
) -> list[FrameStarted | FrameProjectionItem | FrameSealed]:
    expected = _expected(frame_type)
    selected_frame_id = expected.frame_id if frame_id is None else frame_id
    result = validate_parallel_frame_output(frame_type, _result_payload(frame_type))
    canonical_items = [
        canonical_parallel_public_projection(frame_type, item)
        for item in result.public_projection_items
    ]
    events: list[FrameStarted | FrameProjectionItem | FrameSealed] = [
        _started(expected, generation=generation, frame_id=selected_frame_id)
    ]
    for local_index, item in enumerate(canonical_items):
        events.append(
            FrameProjectionItem(
                frame_set_id=FRAME_SET_ID,
                run_id=RUN_ID,
                attempt_id=ATTEMPT_ID,
                frame_type=frame_type,
                generation=generation,
                frame_id=selected_frame_id,
                local_index=local_index,
                next_local_index=local_index + 1,
                item=item,
                item_sha256=item.item_sha256,
            )
        )
    result_payload = result.model_dump(mode="json")
    canonical_projection = [
        item.model_dump(mode="json", exclude_none=True) for item in canonical_items
    ]
    events.append(
        FrameSealed(
            frame_set_id=FRAME_SET_ID,
            run_id=RUN_ID,
            attempt_id=ATTEMPT_ID,
            frame_type=frame_type,
            generation=generation,
            frame_id=selected_frame_id,
            child_checkpoint_ref=f"checkpoint://{frame_type.lower()}",
            child_checkpoint_sha256="c" * 64,
            context_envelope_sha256=CONTEXT_HASH,
            model_context_view_sha256=MODEL_CONTEXT_HASH,
            canonical_result_json=canonicalize(result_payload).decode("utf-8"),
            result_sha256=canonical_sha256(result_payload),
            public_projection_sha256=canonical_sha256(canonical_projection),
            next_local_index=len(canonical_items),
            usage=FrameProviderUsage(
                input_tokens=10,
                output_tokens=5,
                total_tokens=15,
                latency_ms=12,
                provider_call_count=1,
                model="qwen3.7-max-2026-06-08",
            ),
            completed_at=datetime.now(timezone.utc),
        )
    )
    return events


def _result_payload(frame_type: ParallelFrameType) -> dict[str, Any]:
    if frame_type == "DIALOGUE_FRAME":
        return {
            "public_projection_items": [
                {
                    "schema_version": "intake.dialogue-public-segment-proposal.v1",
                    "provider_slot_id": "DSEG_01",
                    "segment_kind": "ACKNOWLEDGEMENT",
                    "candidate_text": "已记录您本轮补充的事实与处理意见。",
                }
            ],
            "frame_type": frame_type,
            "schema_version": "intake.dialogue-frame.v1",
            "dialogue": {
                "action_binding": {
                    "action": "ASK_SUBSTANTIVE",
                    "phase_source_sha256": "d" * 64,
                },
                "public_projection_slots": ["DSEG_01"],
                "language": "zh-CN",
            },
        }
    if frame_type == "DOSSIER_FRAME":
        return {
            "public_projection_items": [
                {
                    "schema_version": "intake.dossier-public-patch-proposal.v1",
                    "provider_slot_id": "DPATCH_01",
                    "projection_kind": "CURRENT_FACT",
                    "projection_path_id": "case_story.current_facts",
                    "fact_key": "FACT_01",
                    "source_binding_id": "SOURCE_01",
                    "candidate_value": {"summary": "商品已使用约半小时。"},
                }
            ],
            "frame_type": frame_type,
            "schema_version": "intake.dossier-frame.v1",
            "dossier_delta": {
                "dossier_patch": {
                    "case_story": {"current_facts": ["商品已使用约半小时。"]}
                },
                "matrix_patch": {"facts": [{"fact_key": "FACT_01"}]},
                "public_projection_slots": ["DPATCH_01"],
            },
        }
    dimensions = [
        ("REFERENCES", 10, "QMETRIC_01"),
        ("EVENT_STORY", 18, "QMETRIC_02"),
        ("PARTY_POSITIONS", 18, "QMETRIC_03"),
        ("REQUESTED_RESOLUTION", 14, "QMETRIC_04"),
        ("RISK_AND_CONFLICTS", 13, "QMETRIC_05"),
        ("NEXT_ACTION_CLARITY", 12, "QMETRIC_06"),
    ]
    return {
        "public_projection_items": [
            {
                "schema_version": "intake.quality-public-metric-proposal.v1",
                "provider_slot_id": slot,
                "projection_kind": "DIMENSION_SCORE",
                "dimension": dimension,
                "candidate_score": score,
                "linked_fact_keys": ["FACT_01"],
            }
            for dimension, score, slot in dimensions
        ],
        "frame_type": frame_type,
        "schema_version": "intake.quality-frame.v1",
        "quality": {
            "scores": {
                "references": 10,
                "event_story": 18,
                "party_positions": 18,
                "requested_resolution": 14,
                "risk_and_conflicts": 13,
                "next_action_clarity": 12,
            },
            "gap_proposals": [
                {
                    "dimension": "REFERENCES",
                    "question": "请补充第三方检测报告的机构名称？",
                    "source_role": "USER",
                    "linked_fact_keys": ["FACT_01"],
                }
            ],
            "assessment_reasoning": "事实和处理方向较清楚，但证据来源仍需补充。",
            "public_projection_slots": [slot for _, _, slot in dimensions],
        },
    }
