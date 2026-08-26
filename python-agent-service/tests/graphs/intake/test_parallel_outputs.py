from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.graphs.intake.parallel_outputs import (
    FRAME_OUTPUT_MODELS,
    DossierPublicFactProposalV2,
    IntakeDialogueFrameV2,
    IntakeDossierFrameV2,
    IntakeQualityFrameV1,
    QualityPublicProjectionProposalV1,
    request_bound_dialogue_output_types,
    request_bound_dossier_output_types,
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

    assert isinstance(dialogue, IntakeDialogueFrameV2)
    assert isinstance(dossier, IntakeDossierFrameV2)
    assert dossier.materialized_dossier_patch() == {
        "case_story": {"one_sentence_summary": "商品已使用约半小时。"}
    }
    assert dossier.materialized_matrix_patch().model_dump(mode="json") == _matrix_patch()
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
    quality = _quality_frame()
    quality["quality"]["scores"]["references"] = 15
    with pytest.raises(ValidationError, match="full-score dimension"):
        validate_parallel_frame_output("QUALITY_FRAME", quality)

    reordered = _quality_frame()
    reordered["public_projection_items"][0], reordered["public_projection_items"][1] = (
        reordered["public_projection_items"][1],
        reordered["public_projection_items"][0],
    )
    reordered["quality"]["public_projection_slots"][0:2] = [
        reordered["public_projection_items"][0]["provider_slot_id"],
        reordered["public_projection_items"][1]["provider_slot_id"],
    ]
    with pytest.raises(ValidationError, match="score order"):
        validate_parallel_frame_output("QUALITY_FRAME", reordered)


def test_provider_visible_schema_rejects_question_segments_and_dimension_score_overflow(
) -> None:
    dialogue_schema = IntakeDialogueFrameV2.model_json_schema()
    dialogue_text_schema = dialogue_schema["$defs"][
        "DialoguePublicSegmentProposalV1"
    ]["properties"]["candidate_text"]
    assert dialogue_text_schema["pattern"] == r"^[^?？]+$"
    assert dialogue_text_schema["maxLength"] == 200
    assert dialogue_schema["properties"]["public_projection_items"]["maxItems"] == 2
    assert set(dialogue_schema["$defs"]["DialogueFrameValueV2"]["properties"]) == {
        "remark_disposition"
    }

    dialogue = _dialogue_frame()
    dialogue["public_projection_items"][0]["candidate_text"] = "还需要补充吗？"
    with pytest.raises(ValidationError, match="string_pattern_mismatch"):
        validate_parallel_frame_output("DIALOGUE_FRAME", dialogue)

    references = _quality_frame()["public_projection_items"][0]
    references["candidate_score"] = 18
    with pytest.raises(ValidationError, match="less_than_equal"):
        QualityPublicProjectionProposalV1.model_validate(references)

    event_story = _quality_frame()["public_projection_items"][1]
    event_story["candidate_score"] = 18
    assert (
        QualityPublicProjectionProposalV1.model_validate(event_story)
        .root.candidate_score
        == 18
    )

    not_ready_type, _ = request_bound_dialogue_output_types(
        persisted_phase="NOT_READY"
    )
    assert not_ready_type.model_validate(_dialogue_frame()).dialogue.remark_disposition is None
    invalid_not_ready = _dialogue_frame()
    invalid_not_ready["dialogue"]["remark_disposition"] = "REMARK"
    with pytest.raises(ValidationError, match="literal_error"):
        not_ready_type.model_validate(invalid_not_ready)

    waiting_type, _ = request_bound_dialogue_output_types(
        persisted_phase="WAITING_FOR_REMARK"
    )
    waiting = _dialogue_frame()
    waiting["dialogue"]["remark_disposition"] = "NO_REMARK"
    assert waiting_type.model_validate(waiting).dialogue.remark_disposition == "NO_REMARK"

    dossier_schema = IntakeDossierFrameV2.model_json_schema()
    dossier_item = dossier_schema["$defs"]["DossierPublicFactProposalV2"]
    assert set(dossier_item["properties"]) == {
        "schema_version",
        "projection_kind",
        "projection_path_id",
        "source_row",
    }
    dossier_delta = dossier_schema["$defs"]["DossierFrameDeltaV2"]
    assert set(dossier_delta["properties"]) == {"respondent_claim"}
    source_row = dossier_schema["$defs"]["DossierCurrentFactRowV2"]["properties"]
    assert set(source_row["source_scope"]["enum"]) == {
        "CURRENT_SOURCE",
        "PREVIOUS_AND_CURRENT_SOURCE",
    }
    assert "NOT_ADDRESSED" not in source_row["stance"]["enum"]
    respondent_claim = dossier_schema["$defs"]["DossierRespondentClaimV2"]
    assert "NOT_ADDRESSED" not in respondent_claim["properties"]["attitude"]["enum"]
    assert dossier_schema["properties"]["public_projection_items"]["maxItems"] == 6
    assert source_row["position_summary"]["maxLength"] == 240
    assert any(
        option.get("maxLength") == 160
        for option in source_row["asserted_value"]["anyOf"]
    )


def test_request_bound_dossier_schema_exposes_fact_namespace_and_respondent_capacity() -> None:
    frame_type, item_type = request_bound_dossier_output_types(
        existing_fact_keys=("FACT_01",),
        new_fact_key_prefix="NEW_AAAAAAAAAAAAAAAAAAAAAAAA_",
        respondent_capacity=False,
    )
    payload = _dossier_frame()
    assert item_type.model_validate(payload["public_projection_items"][0])
    assert frame_type.model_validate(payload)

    unknown = _dossier_frame()["public_projection_items"][0]
    unknown["source_row"]["fact_key"] = "FACT_UNKNOWN"
    with pytest.raises(ValidationError):
        item_type.model_validate(unknown)

    foreign_new = _dossier_frame()["public_projection_items"][0]
    foreign_new["source_row"]["fact_key"] = "NEW_BBBBBBBBBBBBBBBBBBBBBBBB_FACT"
    foreign_new["source_row"]["source_scope"] = "CURRENT_SOURCE"
    with pytest.raises(ValidationError):
        item_type.model_validate(foreign_new)

    valid_new = _dossier_frame()["public_projection_items"][0]
    valid_new["source_row"]["fact_key"] = "NEW_AAAAAAAAAAAAAAAAAAAAAAAA_FACT"
    valid_new["source_row"]["source_scope"] = "CURRENT_SOURCE"
    assert item_type.model_validate(valid_new)

    initiator_claim = _dossier_frame()
    initiator_claim["dossier_delta"]["respondent_claim"] = {
        "attitude": "DISAGREE",
        "position_summary": "不同意该诉求。",
        "alternative_proposal": None,
    }
    with pytest.raises(ValidationError):
        frame_type.model_validate(initiator_claim)

    respondent_frame_type, _ = request_bound_dossier_output_types(
        existing_fact_keys=("FACT_01",),
        new_fact_key_prefix="NEW_AAAAAAAAAAAAAAAAAAAAAAAA_",
        respondent_capacity=True,
    )
    assert respondent_frame_type.model_validate(initiator_claim)


def test_quality_public_gap_is_a_root_discriminated_item_and_must_match_seal() -> None:
    quality = _quality_frame()
    validated = validate_parallel_frame_output("QUALITY_FRAME", quality)
    public_gap = validated.public_projection_items[-1]

    assert isinstance(public_gap, QualityPublicProjectionProposalV1)
    assert public_gap.model_dump(mode="json") == quality["public_projection_items"][-1]
    assert public_gap.provider_slot_id == "QGAP_01"

    missing = _quality_frame()
    missing["public_projection_items"].pop()
    missing["quality"]["public_projection_slots"].pop()
    with pytest.raises(ValidationError, match="exactly trace sealed gaps"):
        validate_parallel_frame_output("QUALITY_FRAME", missing)

    drifted = _quality_frame()
    drifted["public_projection_items"][-1]["question"] = "请补充其他信息？"
    with pytest.raises(ValidationError, match="differs from sealed gap authority"):
        validate_parallel_frame_output("QUALITY_FRAME", drifted)


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
    repeated = {
        **overlapping["public_projection_items"][0],
        "source_row": dict(overlapping["public_projection_items"][0]["source_row"]),
    }
    overlapping["public_projection_items"].append(repeated)
    with pytest.raises(ValidationError, match="unique fact keys"):
        validate_parallel_frame_output("DOSSIER_FRAME", overlapping)


def test_dossier_public_item_requires_typed_current_source_authority_before_streaming() -> None:
    item = _dossier_frame()["public_projection_items"][0]
    item["source_row"]["source_scope"] = "PREVIOUS_MATRIX"
    with pytest.raises(ValidationError, match="literal_error"):
        DossierPublicFactProposalV2.model_validate(item)

    item = _dossier_frame()["public_projection_items"][0]
    item["source_row"]["stance"] = "NOT_ADDRESSED"
    with pytest.raises(ValidationError, match="literal_error"):
        DossierPublicFactProposalV2.model_validate(item)

    item = _dossier_frame()["public_projection_items"][0]
    item["source_row"]["position_summary"] = "   "
    with pytest.raises(ValidationError, match="string_pattern_mismatch"):
        DossierPublicFactProposalV2.model_validate(item)


def test_dossier_rows_are_generated_once_and_materialize_existing_contracts() -> None:
    dossier = _dossier_frame()
    dossier["public_projection_items"].append(
        {
            "schema_version": "intake.dossier-public-fact-proposal.v2",
            "projection_kind": "CURRENT_FACT",
            "projection_path_id": "case_story.one_sentence_summary",
            "source_row": {
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
            },
        }
    )

    validated = validate_parallel_frame_output("DOSSIER_FRAME", dossier)
    assert validated.materialized_dossier_patch() == {
        "case_story": {
            "one_sentence_summary": "商品已使用约半小时。；商品外观完整。"
        }
    }
    matrix = validated.materialized_matrix_patch().model_dump(mode="json")
    assert matrix["fact_rows"] == [
        item["source_row"] for item in dossier["public_projection_items"]
    ]
    assert matrix["summary_source_fact_keys"] == ["FACT_01", "FACT_02"]


def test_dossier_materialization_preserves_authoritative_whitespace() -> None:
    dossier = _dossier_frame()
    row = dossier["public_projection_items"][0]["source_row"]
    row["position_summary"] = "  商品已使用约半小时。  "

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
        "schema_version": "intake.dialogue-frame.v2",
        "dialogue": {
            "remark_disposition": None,
        },
    }


def _dossier_frame() -> dict[str, object]:
    matrix_patch = _matrix_patch()
    return {
        "public_projection_items": [
            {
                "schema_version": "intake.dossier-public-fact-proposal.v2",
                "projection_kind": "CURRENT_FACT",
                "projection_path_id": "case_story.one_sentence_summary",
                "source_row": dict(matrix_patch["fact_rows"][0]),
            }
        ],
        "frame_type": "DOSSIER_FRAME",
        "schema_version": "intake.dossier-frame.v2",
        "dossier_delta": {
            "respondent_claim": None,
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
                "source_scope": "PREVIOUS_AND_CURRENT_SOURCE",
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
    gap = {
        "dimension": "REFERENCES",
        "question": "请补充第三方检测报告的机构名称？",
        "source_role": "USER",
        "linked_fact_keys": ["FACT_01"],
    }
    public_items = [
            {
                "schema_version": "intake.quality-public-metric-proposal.v1",
                "provider_slot_id": slot,
                "projection_kind": "DIMENSION_SCORE",
                "dimension": dimension,
                "candidate_score": score,
                "linked_fact_keys": ["FACT_01"],
            }
            for dimension, score, slot in dimensions
        ]
    public_items.append(
        {
            "schema_version": "intake.quality-public-gap-proposal.v1",
            "provider_slot_id": "QGAP_01",
            "projection_kind": "BLOCKING_GAP",
            **gap,
        }
    )
    return {
        "public_projection_items": public_items,
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
            "gap_proposals": [gap],
            "assessment_reasoning": "主要事实和处理方向已较清楚，但证据来源仍需补充。",
            "public_projection_slots": [slot for _, _, slot in dimensions]
            + ["QGAP_01"],
        },
    }
