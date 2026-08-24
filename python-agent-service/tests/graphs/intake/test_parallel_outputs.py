from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.graphs.intake.parallel_outputs import (
    FRAME_OUTPUT_MODELS,
    IntakeDialogueFrameV1,
    IntakeDossierFrameV1,
    IntakeQualityFrameV1,
    validate_parallel_frame_output,
)


def test_exact_three_output_schemas_accept_java_assembler_shapes() -> None:
    dialogue = validate_parallel_frame_output(
        "DIALOGUE_FRAME",
        _dialogue_frame(),
    )
    dossier = validate_parallel_frame_output(
        "DOSSIER_FRAME",
        _dossier_frame(),
    )
    quality = validate_parallel_frame_output(
        "QUALITY_FRAME",
        _quality_frame(),
    )

    assert isinstance(dialogue, IntakeDialogueFrameV1)
    assert isinstance(dossier, IntakeDossierFrameV1)
    assert isinstance(quality, IntakeQualityFrameV1)
    assert sum(quality.quality.scores.model_dump().values()) == 85
    for model in FRAME_OUTPUT_MODELS.values():
        assert next(iter(model.model_fields)) == "public_projection_items"


def test_frame_schema_rejects_cross_lane_fields_and_independent_total_score() -> None:
    dialogue = _dialogue_frame()
    dialogue["quality"] = _quality_frame()["quality"]
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("DIALOGUE_FRAME", dialogue)

    quality = _quality_frame()
    quality["quality"]["total_score"] = 85
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("QUALITY_FRAME", quality)


def test_frame_schema_rejects_projection_reordering_and_full_score_gap() -> None:
    dossier = _dossier_frame()
    dossier["dossier_delta"]["public_projection_slots"] = ["DPATCH_02"]
    with pytest.raises(ValidationError, match="public projection slots"):
        validate_parallel_frame_output("DOSSIER_FRAME", dossier)

    quality = _quality_frame()
    quality["quality"]["scores"]["references"] = 15
    with pytest.raises(ValidationError, match="full-score dimension"):
        validate_parallel_frame_output("QUALITY_FRAME", quality)


def _dialogue_frame() -> dict[str, object]:
    return {
        "public_projection_items": [
            {
                "schema_version": "intake.dialogue-public-segment-proposal.v1",
                "provider_slot_id": "DSEG_01",
                "segment_kind": "ACKNOWLEDGEMENT",
                "candidate_text": "已记录您本轮补充的事实与处理意见。",
            }
        ],
        "frame_type": "DIALOGUE_FRAME",
        "schema_version": "intake.dialogue-frame.v1",
        "dialogue": {
            "action_binding": {
                "action": "ASK_SUBSTANTIVE",
                "phase_source_sha256": "a" * 64,
            },
            "public_projection_slots": ["DSEG_01"],
            "language": "zh-CN",
        },
    }


def _dossier_frame() -> dict[str, object]:
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
        "frame_type": "DOSSIER_FRAME",
        "schema_version": "intake.dossier-frame.v1",
        "dossier_delta": {
            "dossier_patch": {"case_story": {"current_facts": ["商品已使用约半小时。"]}},
            "matrix_patch": {"facts": [{"fact_key": "FACT_01"}]},
            "public_projection_slots": ["DPATCH_01"],
        },
    }


def _quality_frame() -> dict[str, object]:
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
        "frame_type": "QUALITY_FRAME",
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
            "assessment_reasoning": "主要事实和处理方向已较清楚，但证据来源仍需补充。",
            "public_projection_slots": [slot for _, _, slot in dimensions],
        },
    }
