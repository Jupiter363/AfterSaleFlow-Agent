from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from app.graphs.intake.parallel_outputs import (
    FRAME_OUTPUT_MODELS,
    DialoguePublicSegmentDraftV3,
    DossierPublicFactDraftV3,
    IntakeDialogueFrameV3,
    IntakeDossierFrameV3,
    IntakeQualityFrameV2,
    QualityPublicProjectionDraftV2,
    materialize_request_bound_frame_output,
    request_bound_dialogue_output_types,
    request_bound_dossier_output_types,
    request_bound_quality_output_types,
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

    assert isinstance(dialogue, IntakeDialogueFrameV3)
    assert isinstance(dossier, IntakeDossierFrameV3)
    assert dossier.materialized_dossier_patch() == {
        "case_story": {"one_sentence_summary": "商品已使用约半小时。"}
    }
    assert isinstance(quality, IntakeQualityFrameV2)
    assert sum(
        item.root.candidate_score
        for item in quality.public_projection_items[:6]
    ) == 85
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
    quality["public_projection_items"][0]["candidate_score"] = 15
    with pytest.raises(ValidationError, match="full-score dimension"):
        validate_parallel_frame_output("QUALITY_FRAME", quality)

    reordered = _quality_frame()
    reordered["public_projection_items"][0], reordered["public_projection_items"][1] = (
        reordered["public_projection_items"][1],
        reordered["public_projection_items"][0],
    )
    with pytest.raises(ValidationError, match="score order"):
        validate_parallel_frame_output("QUALITY_FRAME", reordered)


def test_provider_visible_schema_rejects_question_segments_and_dimension_score_overflow(
) -> None:
    dialogue_schema = IntakeDialogueFrameV3.model_json_schema()
    dialogue_text_schema = dialogue_schema["$defs"][
        "DialoguePublicSegmentDraftV3"
    ]["properties"]["candidate_text"]
    assert dialogue_text_schema["pattern"] == r"^[^?？]+$"
    assert dialogue_text_schema["maxLength"] == 80
    assert dialogue_schema["properties"]["public_projection_items"]["maxItems"] == 1
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
        QualityPublicProjectionDraftV2.model_validate(references)

    event_story = _quality_frame()["public_projection_items"][1]
    event_story["candidate_score"] = 18
    assert (
        QualityPublicProjectionDraftV2.model_validate(event_story)
        .root.candidate_score
        == 18
    )

    not_ready_type, _ = request_bound_dialogue_output_types(
        persisted_phase="NOT_READY"
    )
    not_ready_schema = not_ready_type.model_json_schema()
    assert set(not_ready_schema["properties"]) == {"public_projection_items"}
    assert '"const": null' not in json.dumps(not_ready_schema, sort_keys=True)
    assert '"type": "null"' not in json.dumps(not_ready_schema, sort_keys=True)
    assert not_ready_type.model_validate(_dialogue_provider_frame())

    invalid_not_ready = _dialogue_provider_frame()
    invalid_not_ready["dialogue"] = {"remark_disposition": None}
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        not_ready_type.model_validate(invalid_not_ready)
    assert materialize_request_bound_frame_output(
        "DIALOGUE_FRAME",
        _dialogue_provider_frame(),
        persisted_phase="NOT_READY",
        respondent_capacity=False,
    ).model_dump(mode="json") == _dialogue_frame()

    waiting_type, _ = request_bound_dialogue_output_types(
        persisted_phase="WAITING_FOR_REMARK"
    )
    waiting_schema = waiting_type.model_json_schema()
    assert set(waiting_schema["properties"]) == {
        "public_projection_items",
        "dialogue",
    }
    disposition_schema = waiting_schema["$defs"]["DialogueRemarkUpdateDraftV4"]
    assert disposition_schema["properties"]["remark_disposition"] == {
        "enum": ["REMARK", "NO_REMARK"],
        "title": "Remark Disposition",
        "type": "string",
    }
    assert '"const": null' not in json.dumps(disposition_schema, sort_keys=True)
    assert '"type": "null"' not in json.dumps(disposition_schema, sort_keys=True)
    for disposition in ("REMARK", "NO_REMARK"):
        waiting = _dialogue_remark_provider_frame(disposition)
        assert (
            waiting_type.model_validate(waiting).dialogue.remark_disposition
            == disposition
        )
        assert (
            materialize_request_bound_frame_output(
                "DIALOGUE_FRAME",
                waiting,
                persisted_phase="WAITING_FOR_REMARK",
                respondent_capacity=False,
            ).dialogue.remark_disposition
            == disposition
        )

    dossier_schema = IntakeDossierFrameV3.model_json_schema()
    dossier_item = dossier_schema["$defs"]["DossierPublicFactDraftV3"]
    assert set(dossier_item["properties"]) == {"source_row"}
    dossier_delta = dossier_schema["$defs"]["DossierFrameDeltaV2"]
    assert set(dossier_delta["properties"]) == {"respondent_claim"}
    source_row = dossier_schema["$defs"]["DossierCurrentFactDraftV3"]["properties"]
    assert set(source_row) == {
        "fact_key",
        "category",
        "fact_target",
        "materiality",
        "stance",
        "position_summary",
        "asserted_value",
    }
    assert "source_scope" not in source_row
    assert "NOT_ADDRESSED" not in source_row["stance"]["enum"]
    respondent_claim = dossier_schema["$defs"]["DossierRespondentClaimV2"]
    assert "NOT_ADDRESSED" not in respondent_claim["properties"]["attitude"]["enum"]
    assert dossier_schema["properties"]["public_projection_items"]["maxItems"] == 5
    assert source_row["position_summary"]["maxLength"] == 100
    assert any(
        option.get("maxLength") == 60
        for option in source_row["asserted_value"]["anyOf"]
    )


def test_request_bound_dossier_schema_exposes_fact_namespace_and_respondent_capacity() -> None:
    frame_type, item_type = request_bound_dossier_output_types(
        existing_fact_keys=("FACT_01",),
        new_fact_key_prefix="NEW_AAAAAAAAAAAAAAAAAAAAAAAA_",
        respondent_capacity=False,
    )
    payload = _dossier_provider_frame()
    assert item_type.model_validate(payload["public_projection_items"][0])
    assert frame_type.model_validate(payload)
    initiator_schema = frame_type.model_json_schema()
    assert set(initiator_schema["properties"]) == {"public_projection_items"}
    schema_text = json.dumps(initiator_schema, sort_keys=True)
    assert '"category"' not in schema_text
    assert '"materiality"' not in schema_text

    unknown = _dossier_provider_frame()["public_projection_items"][0]
    unknown["source_row"]["fact_key"] = "FACT_UNKNOWN"
    with pytest.raises(ValidationError):
        item_type.model_validate(unknown)

    foreign_new = _dossier_provider_frame()["public_projection_items"][0]
    foreign_new["source_row"]["fact_key"] = "NEW_BBBBBBBBBBBBBBBBBBBBBBBB_FACT"
    with pytest.raises(ValidationError):
        item_type.model_validate(foreign_new)

    valid_new = _dossier_provider_frame()["public_projection_items"][0]
    valid_new["source_row"]["fact_key"] = "NEW_AAAAAAAAAAAAAAAAAAAAAAAA_FACT"
    assert item_type.model_validate(valid_new)

    initiator_tail = _dossier_provider_frame()
    initiator_tail["dossier_delta"] = {"respondent_claim_updates": []}
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        frame_type.model_validate(initiator_tail)
    assert materialize_request_bound_frame_output(
        "DOSSIER_FRAME",
        _dossier_provider_frame(),
        persisted_phase="NOT_READY",
        respondent_capacity=False,
        frozen_case_matrix=_frozen_matrix(),
    ).model_dump(mode="json") == _dossier_frame()

    provider_drift = _dossier_provider_frame()
    provider_drift["public_projection_items"][0]["source_row"]["fact_target"] = (
        "模型改写的既有目标"
    )
    restored = materialize_request_bound_frame_output(
        "DOSSIER_FRAME",
        provider_drift,
        persisted_phase="NOT_READY",
        respondent_capacity=False,
        frozen_case_matrix=_frozen_matrix(),
    )
    assert restored.public_projection_items[0].source_row.fact_target == "商品使用状态"
    assert restored.public_projection_items[0].source_row.category == "PRODUCT_STATE"
    assert restored.public_projection_items[0].source_row.materiality == "CORE"

    new_fact = _dossier_provider_frame()
    new_fact["public_projection_items"][0]["source_row"].update(
        {
            "fact_key": "NEW_AAAAAAAAAAAAAAAAAAAAAAAA_USAGE",
            "fact_target": "本轮新增事实",
        }
    )
    materialized_new = materialize_request_bound_frame_output(
        "DOSSIER_FRAME",
        new_fact,
        persisted_phase="NOT_READY",
        respondent_capacity=False,
        frozen_case_matrix=_frozen_matrix(),
    )
    assert materialized_new.public_projection_items[0].source_row.category == "OTHER"
    assert materialized_new.public_projection_items[0].source_row.materiality == "CORE"

    respondent_frame_type, _ = request_bound_dossier_output_types(
        existing_fact_keys=("FACT_01",),
        new_fact_key_prefix="NEW_AAAAAAAAAAAAAAAAAAAAAAAA_",
        respondent_capacity=True,
    )
    respondent_schema = respondent_frame_type.model_json_schema()
    assert set(respondent_schema["properties"]) == {
        "public_projection_items",
        "dossier_delta",
    }
    respondent_tail_schema = {
        name: respondent_schema["$defs"][name]
        for name in ("DossierFrameDeltaDraftV4", "DossierRespondentClaimDraftV4")
    }
    assert '"const": null' not in json.dumps(respondent_tail_schema, sort_keys=True)
    assert '"type": "null"' not in json.dumps(respondent_tail_schema, sort_keys=True)

    empty_updates = _respondent_dossier_provider_frame([])
    assert respondent_frame_type.model_validate(empty_updates)
    assert (
        materialize_request_bound_frame_output(
            "DOSSIER_FRAME",
            empty_updates,
            persisted_phase="NOT_READY",
            respondent_capacity=True,
            frozen_case_matrix=_frozen_matrix(),
        ).dossier_delta.respondent_claim
        is None
    )

    claim_update = {
        "attitude": "ALTERNATIVE_PROPOSED",
        "position_summary": "同意按约定条件复测。",
        "alternative_proposals": ["复测不达标后办理退货退款。"],
    }
    respondent_claim = _respondent_dossier_provider_frame([claim_update])
    assert respondent_frame_type.model_validate(respondent_claim)
    materialized = materialize_request_bound_frame_output(
        "DOSSIER_FRAME",
        respondent_claim,
        persisted_phase="NOT_READY",
        respondent_capacity=True,
        frozen_case_matrix=_frozen_matrix(),
    )
    assert materialized.dossier_delta.respondent_claim.model_dump(mode="json") == {
        "attitude": "ALTERNATIVE_PROPOSED",
        "position_summary": "同意按约定条件复测。",
        "alternative_proposal": "复测不达标后办理退货退款。",
    }


def test_dossier_schema_accepts_five_facts_and_rejects_the_sixth() -> None:
    dossier = _dossier_frame()
    template = dossier["public_projection_items"][0]
    for index in range(2, 6):
        item = {
            "source_row": {
                **template["source_row"],
                "fact_key": f"NEW_{'A' * 24}_FACT_{index}",
                "position_summary": f"补充事实{index}。",
            }
        }
        dossier["public_projection_items"].append(item)

    assert len(
        validate_parallel_frame_output(
            "DOSSIER_FRAME", dossier
        ).public_projection_items
    ) == 5

    dossier["public_projection_items"].append(
        {
            "source_row": {
                **template["source_row"],
                "fact_key": f"NEW_{'A' * 24}_FACT_6",
                "position_summary": "第六项事实。",
            }
        }
    )
    with pytest.raises(ValidationError, match="too_long"):
        validate_parallel_frame_output("DOSSIER_FRAME", dossier)


def test_quality_public_gap_is_the_single_typed_gap_authority() -> None:
    quality = _quality_frame()
    validated = validate_parallel_frame_output("QUALITY_FRAME", quality)
    public_gap = validated.public_projection_items[-1]

    assert isinstance(public_gap, QualityPublicProjectionDraftV2)
    assert public_gap.model_dump(mode="json") == quality["public_projection_items"][-1]

    missing = _quality_frame()
    missing["public_projection_items"].pop()
    assert len(validate_parallel_frame_output(
        "QUALITY_FRAME", missing
    ).public_projection_items) == 6

    drifted = _quality_frame()
    drifted["public_projection_items"][-1]["source_role"] = "USER"
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("QUALITY_FRAME", drifted)


def test_request_bound_quality_schema_accepts_only_frozen_fact_keys() -> None:
    frame_type, item_type = request_bound_quality_output_types(
        existing_fact_keys=("FACT_01",),
    )
    provider = _quality_provider_frame()
    assert frame_type.model_validate(provider)
    assert item_type.model_validate(provider["public_projection_items"][0])

    foreign = _quality_provider_frame()
    foreign["gap_candidates"][-1]["linked_fact_keys"] = ["FACT_FOREIGN"]
    with pytest.raises(ValidationError, match="literal_error"):
        frame_type.model_validate(foreign)

    no_facts_type, _ = request_bound_quality_output_types(existing_fact_keys=())
    no_facts = _quality_provider_frame()
    no_facts["gap_candidates"][-1]["linked_fact_keys"] = []
    assert no_facts_type.model_validate(no_facts)
    with pytest.raises(ValidationError, match="too_long"):
        no_facts_type.model_validate(_quality_provider_frame())


def test_quality_provider_draft_materializes_fixed_scores_and_filters_full_score_gap() -> None:
    frame_type, _ = request_bound_quality_output_types(existing_fact_keys=())
    provider = _quality_provider_frame()
    provider["public_projection_items"][0]["candidate_score"] = 15
    provider["gap_candidates"][-1]["linked_fact_keys"] = []

    validated = frame_type.model_validate(provider)
    materialized = materialize_request_bound_frame_output(
        "QUALITY_FRAME",
        validated,
        persisted_phase="NOT_READY",
        respondent_capacity=False,
    )

    assert len(materialized.public_projection_items) == 6
    assert [
        item.root.dimension for item in materialized.public_projection_items
    ] == [
        "REFERENCES",
        "EVENT_STORY",
        "PARTY_POSITIONS",
        "REQUESTED_RESOLUTION",
        "RISK_AND_CONFLICTS",
        "NEXT_ACTION_CLARITY",
    ]
    schema = frame_type.model_json_schema()
    score_schema = schema["properties"]["public_projection_items"]
    assert score_schema["minItems"] == score_schema["maxItems"] == 6
    assert len(score_schema["prefixItems"]) == 6
    schema_text = json.dumps(schema, sort_keys=True)
    for unsupported in ("uniqueItems", "contains", "minContains", "maxContains"):
        assert unsupported not in schema_text


def test_quality_gap_materialization_is_independent_of_provider_candidate_order() -> None:
    first = _quality_provider_frame()
    first["gap_candidates"] = [
        {
            "dimension": "EVENT_STORY",
            "question": "请说明商品首次使用时间？",
            "linked_fact_keys": ["FACT_01"],
        },
        {
            "dimension": "REFERENCES",
            "question": "请补充检测报告机构名称？",
            "linked_fact_keys": ["FACT_01"],
        },
        {
            "dimension": "REFERENCES",
            "question": "请补充第三方报告名称？",
            "linked_fact_keys": ["FACT_01"],
        },
    ]
    second = {
        **first,
        "gap_candidates": list(reversed(first["gap_candidates"])),
    }

    def materialize(value: dict[str, object]) -> dict[str, object]:
        return materialize_request_bound_frame_output(
            "QUALITY_FRAME",
            value,
            persisted_phase="NOT_READY",
            respondent_capacity=False,
        ).model_dump(mode="json")

    assert materialize(first) == materialize(second)
    gaps = materialize(first)["public_projection_items"][6:]
    assert [gap["dimension"] for gap in gaps] == ["REFERENCES", "EVENT_STORY"]


def test_dossier_public_items_are_the_only_registered_patch_authority() -> None:
    false_binding = _dossier_frame()
    false_binding["public_projection_items"][0]["fact_key"] = "FACT_01"
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("DOSSIER_FRAME", false_binding)

    foreign = _dossier_frame()
    foreign["public_projection_items"][0]["projection_path_id"] = (
        "intake_quality.score"
    )
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
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
    item["source_row"]["source_scope"] = "CURRENT_SOURCE"
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        DossierPublicFactDraftV3.model_validate(item)

    item = _dossier_frame()["public_projection_items"][0]
    item["source_row"]["stance"] = "NOT_ADDRESSED"
    with pytest.raises(ValidationError, match="literal_error"):
        DossierPublicFactDraftV3.model_validate(item)

    item = _dossier_frame()["public_projection_items"][0]
    item["source_row"]["position_summary"] = "   "
    with pytest.raises(ValidationError, match="string_pattern_mismatch"):
        DossierPublicFactDraftV3.model_validate(item)


def test_dossier_rows_are_generated_once_and_materialize_existing_contracts() -> None:
    dossier = _dossier_frame()
    dossier["public_projection_items"].append(
        {
            "source_row": {
                "fact_key": "FACT_02",
                "category": "LOGISTICS",
                "fact_target": "商品外观状态",
                "materiality": "SUPPORTING",
                "stance": "CONFIRM",
                "position_summary": "商品外观完整。",
                "asserted_value": "外观完整",
            },
        }
    )

    validated = validate_parallel_frame_output("DOSSIER_FRAME", dossier)
    assert validated.materialized_dossier_patch() == {
        "case_story": {
            "one_sentence_summary": "商品已使用约半小时。；商品外观完整。"
        }
    }
    assert all(
        set(type(item.source_row).model_fields) == {
            "fact_key",
            "category",
            "fact_target",
            "materiality",
            "stance",
            "position_summary",
            "asserted_value",
        }
        for item in validated.public_projection_items
    )


def test_legacy_v2_provider_shapes_are_rejected() -> None:
    dialogue = _dialogue_frame()
    dialogue["frame_type"] = "DIALOGUE_FRAME"
    dialogue["schema_version"] = "intake.dialogue-frame.v2"
    dialogue["public_projection_items"][0].update(
        {
            "schema_version": "intake.dialogue-public-segment-proposal.v1",
            "provider_slot_id": "DSEG_01",
        }
    )
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("DIALOGUE_FRAME", dialogue)

    dossier = _dossier_frame()
    dossier["frame_type"] = "DOSSIER_FRAME"
    dossier["schema_version"] = "intake.dossier-frame.v2"
    dossier["public_projection_items"][0].update(
        {
            "schema_version": "intake.dossier-public-fact-proposal.v2",
            "projection_kind": "CURRENT_FACT",
            "projection_path_id": "case_story.one_sentence_summary",
        }
    )
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        validate_parallel_frame_output("DOSSIER_FRAME", dossier)


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
                "segment_kind": "ACKNOWLEDGEMENT",
                "candidate_text": "已记录您本轮补充的事实与处理意见。",
            }
        ],
        "dialogue": {
            "remark_disposition": None,
        },
    }


def _dialogue_provider_frame() -> dict[str, object]:
    return {
        "public_projection_items": _dialogue_frame()["public_projection_items"],
    }


def _dialogue_remark_provider_frame(disposition: str) -> dict[str, object]:
    return {
        **_dialogue_provider_frame(),
        "dialogue": {"remark_disposition": disposition},
    }


def _dossier_frame() -> dict[str, object]:
    return {
        "public_projection_items": [
            {
                "source_row": _source_row(),
            }
        ],
        "dossier_delta": {
            "respondent_claim": None,
        },
    }


def _dossier_provider_frame() -> dict[str, object]:
    source_row = dict(_source_row())
    source_row.pop("category")
    source_row.pop("materiality")
    return {
        "public_projection_items": [{"source_row": source_row}],
    }


def _frozen_matrix() -> dict[str, object]:
    return {
        "fact_rows": [
            {
                "fact_id": "FACT_01",
                "category": "PRODUCT_STATE",
                "fact_target": "商品使用状态",
                "materiality": "CORE",
            }
        ]
    }


def _respondent_dossier_provider_frame(
    updates: list[dict[str, object]],
) -> dict[str, object]:
    return {
        **_dossier_provider_frame(),
        "dossier_delta": {"respondent_claim_updates": updates},
    }


def _source_row() -> dict[str, object]:
    return {
        "fact_key": "FACT_01",
        "category": "PRODUCT_STATE",
        "fact_target": "商品使用状态",
        "materiality": "CORE",
        "stance": "CONFIRM",
        "position_summary": "商品已使用约半小时。",
        "asserted_value": "约半小时",
    }


def _quality_frame() -> dict[str, object]:
    dimensions = [
        ("REFERENCES", 10),
        ("EVENT_STORY", 18),
        ("PARTY_POSITIONS", 18),
        ("REQUESTED_RESOLUTION", 14),
        ("RISK_AND_CONFLICTS", 13),
        ("NEXT_ACTION_CLARITY", 12),
    ]
    gap = {
        "dimension": "REFERENCES",
        "question": "请补充第三方检测报告的机构名称？",
        "linked_fact_keys": ["FACT_01"],
    }
    public_items = [
            {
                "projection_kind": "DIMENSION_SCORE",
                "dimension": dimension,
                "candidate_score": score,
            }
            for dimension, score in dimensions
        ]
    public_items.append(
        {
            "projection_kind": "BLOCKING_GAP",
            **gap,
        }
    )
    return {
        "public_projection_items": public_items,
        "quality": {
            "assessment_reasoning": "主要事实和处理方向已较清楚，但证据来源仍需补充。",
        },
    }


def _quality_provider_frame() -> dict[str, object]:
    stable = _quality_frame()
    public_items = stable["public_projection_items"]
    assert isinstance(public_items, list)
    return {
        "public_projection_items": public_items[:6],
        "gap_candidates": [
            {
                key: value
                for key, value in public_items[6].items()
                if key != "projection_kind"
            }
        ],
        "quality": stable["quality"],
    }
