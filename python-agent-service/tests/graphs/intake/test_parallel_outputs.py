from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.graphs.intake.parallel_outputs import (
    FRAME_OUTPUT_MODELS,
    IntakeDialogueFrameV1,
    DossierPublicPatchProposalV1,
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
    assert dossier.materialized_dossier_patch() == {
        "case_story": {"one_sentence_summary": "商品已使用约半小时。"}
    }
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


def test_dossier_public_items_are_the_only_registered_patch_authority() -> None:
    false_binding = _dossier_frame()
    false_binding["public_projection_items"][0]["fact_key"] = "FACT_01"
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("DOSSIER_FRAME", false_binding)

    foreign = _dossier_frame()
    foreign["public_projection_items"][0]["projection_path_id"] = (
        "intake_quality.score"
    )
    with pytest.raises(ValidationError, match="case_story.one_sentence_summary"):
        validate_parallel_frame_output("DOSSIER_FRAME", foreign)

    overlapping = _dossier_frame()
    repeated = dict(overlapping["public_projection_items"][0])
    repeated["provider_slot_id"] = "DPATCH_02"
    overlapping["public_projection_items"].append(repeated)
    overlapping["dossier_delta"]["public_projection_slots"].append("DPATCH_02")
    with pytest.raises(ValidationError, match="exactly project"):
        validate_parallel_frame_output("DOSSIER_FRAME", overlapping)


def test_dossier_public_item_requires_typed_current_source_authority_before_streaming() -> None:
    item = _dossier_frame()["public_projection_items"][0]
    item["candidate_value"] = "与 typed source row 不一致"
    with pytest.raises(ValidationError, match="typed source row"):
        DossierPublicPatchProposalV1.model_validate(item)

    item = _dossier_frame()["public_projection_items"][0]
    item["source_row"]["source_scope"] = "PREVIOUS_MATRIX"
    with pytest.raises(ValidationError, match="current-source row"):
        DossierPublicPatchProposalV1.model_validate(item)


def test_dossier_summary_is_the_exact_ordered_projection_of_current_matrix_rows() -> None:
    dossier = _dossier_frame()
    matrix = dossier["dossier_delta"]["matrix_patch"]
    matrix["fact_rows"].append(
        {
            "fact_key": "FACT_02",
            "category": "LOGISTICS",
            "fact_target": "商品外观状态",
            "materiality": "SUPPORTING",
            "stance": "CONFIRM",
            "position_summary": "商品外观完整。",
            "asserted_value": "外观完整",
            "source_scope": "CURRENT_SOURCE",
            "agreed_statement": None,
            "conflict_summary": None,
        }
    )
    matrix["summary_source_fact_keys"].append("FACT_02")
    dossier["public_projection_items"].append(
        {
            "schema_version": "intake.dossier-public-patch-proposal.v1",
            "provider_slot_id": "DPATCH_02",
            "projection_kind": "CURRENT_FACT",
            "projection_path_id": "case_story.one_sentence_summary",
            "source_row": dict(matrix["fact_rows"][1]),
            "candidate_value": "商品外观完整。",
        }
    )
    dossier["dossier_delta"]["public_projection_slots"].append("DPATCH_02")

    validated = validate_parallel_frame_output("DOSSIER_FRAME", dossier)
    assert validated.materialized_dossier_patch() == {
        "case_story": {
            "one_sentence_summary": "商品已使用约半小时。；商品外观完整。"
        }
    }

    dossier["public_projection_items"][1]["candidate_value"] = "顺序或内容发生漂移"
    with pytest.raises(ValidationError, match="typed source row"):
        validate_parallel_frame_output("DOSSIER_FRAME", dossier)


def test_dossier_materialization_preserves_authoritative_whitespace() -> None:
    dossier = _dossier_frame()
    row = dossier["dossier_delta"]["matrix_patch"]["fact_rows"][0]
    row["position_summary"] = "  商品已使用约半小时。  "
    item = dossier["public_projection_items"][0]
    item["source_row"] = dict(row)
    item["candidate_value"] = row["position_summary"]

    validated = validate_parallel_frame_output("DOSSIER_FRAME", dossier)
    assert validated.materialized_dossier_patch() == {
        "case_story": {"one_sentence_summary": "  商品已使用约半小时。  "}
    }


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
    matrix_patch = _matrix_patch()
    return {
        "public_projection_items": [
            {
                "schema_version": "intake.dossier-public-patch-proposal.v1",
                "provider_slot_id": "DPATCH_01",
                "projection_kind": "CURRENT_FACT",
                "projection_path_id": "case_story.one_sentence_summary",
                "source_row": dict(matrix_patch["fact_rows"][0]),
                "candidate_value": "商品已使用约半小时。",
            }
        ],
        "frame_type": "DOSSIER_FRAME",
        "schema_version": "intake.dossier-frame.v1",
        "dossier_delta": {
            "matrix_patch": matrix_patch,
            "public_projection_slots": ["DPATCH_01"],
        },
    }


def _matrix_patch() -> dict[str, object]:
    return {
        "schema_version": "case_fact_matrix.delta.v2",
        "fact_rows": [
            {
                "fact_key": "FACT_01",
                "category": "PRODUCT_STATE",
                "fact_target": "商品使用状态",
                "materiality": "CORE",
                "stance": "CONFIRM",
                "position_summary": "商品已使用约半小时。",
                "asserted_value": "约半小时",
                "source_scope": "CURRENT_SOURCE",
                "agreed_statement": None,
                "conflict_summary": None,
            }
        ],
        "summary_source_fact_keys": ["FACT_01"],
        "respondent_claim": None,
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
