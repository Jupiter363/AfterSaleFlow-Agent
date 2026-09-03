from __future__ import annotations

from pathlib import Path

import pytest

from app.contracts.v1.codec import canonical_sha256
from app.graphs.intake.parallel_contracts import (
    FRAME_OUTPUT_SCHEMA,
    FRAME_TYPES,
    IntakeAuthorityRefV1,
    IntakeCaseRefV1,
    IntakeFrameInstructionPackV1,
    IntakeFrameModelInputV2,
    IntakeModelContextViewV1,
    IntakeSourceEventRefV1,
    IntakeTurnRouteV1,
    build_frame_model_inputs,
    build_instruction_pack,
    build_parallel_context_envelope,
)


SERVICE_ROOT = Path(__file__).resolve().parents[3]
PROMPT_ROOT = (
    SERVICE_ROOT
    / "app"
    / "agents"
    / "prompts"
    / "dispute_intake_officer"
)


def _prompt(name: str) -> str:
    return (PROMPT_ROOT / name).read_text(encoding="utf-8")


def _common_context(
    *,
    dossier_projection: dict | None = None,
) -> IntakeModelContextViewV1:
    question = "请说明签收时间。"
    current_message = "商品于昨日签收。"
    previous_message = "请补充签收时间。"
    matrix_payload = {
        "fact_rows": [
            {
                "fact_id": "FACT_DELIVERY_001",
                "category": "FULFILLMENT",
                "fact_target": "商品是否已经发货",
                "materiality": "CORE",
            }
        ]
    }
    previous_state = {
        "revision": 8,
        "persisted_phase": "NOT_READY",
        "quality": {
            "score_breakdown": {
                "references": 10,
                "event_story": 10,
                "party_positions": 10,
                "requested_resolution": 10,
                "risk_and_conflicts": 10,
                "next_action_clarity": 10,
            }
        },
        "dossier_projection": dossier_projection
        if dossier_projection is not None
        else {"event_story": "商品已经发货。"},
    }
    return IntakeModelContextViewV1.seal(
        {
            "contract_version": "intake.model-context-view.v1",
            "turn_route": {
                "source_type": "ROOM_MESSAGE",
                "execution_profile": "PARALLEL_FRAMES",
            },
            "source_capacity": {
                "business_role": "USER",
                "litigation_capacity": "INITIATOR",
                "writable_partition": "INITIATOR_ONLY",
            },
            "previous_state": previous_state,
            "current_action_binding": {
                "action": "ASK_SUBSTANTIVE",
                "derived_from_phase": "NOT_READY",
                "phase_source_sha256": canonical_sha256(previous_state),
            },
            "authorized_question_slots": [
                {
                    "question_id": "Q_DELIVERY_TIME",
                    "target_capacity": "INITIATOR",
                    "source": "PREVIOUS_PERSISTED_STATE",
                    "canonical_text": question,
                    "canonical_text_sha256": canonical_sha256(question),
                }
            ],
            "frozen_case_matrix": {
                "version": 3,
                "sha256": canonical_sha256(matrix_payload),
                "payload": matrix_payload,
            },
            "fact_key_authority": {
                "existing_fact_keys": ["FACT_DELIVERY_001"],
                "new_fact_key_prefix": "NEW_AAAAAAAAAAAAAAAAAAAAAAAA_",
            },
            "recent_dialogue_messages": [
                {
                    "sequence": 1,
                    "speaker_role": "USER",
                    "speaker_capacity": "INITIATOR",
                    "text": previous_message,
                    "source_sha256": canonical_sha256(previous_message),
                }
            ],
            "current_user_message": {
                "source_sequence": 2,
                "source_role": "USER",
                "source_capacity": "INITIATOR",
                "text": current_message,
                "text_sha256": canonical_sha256(current_message),
            },
        }
    )


def _context_envelope(context: IntakeModelContextViewV1):
    return build_parallel_context_envelope(
        case_ref=IntakeCaseRefV1.model_validate(
            {
                "tenant_id": "tenant-local",
                "case_id": "CASE_PARALLEL_1",
                "thread_id": "THREAD_PARALLEL_1",
                "room_id": "ROOM_PARALLEL_1",
                "room_epoch": 2,
                "fence_token": "FENCE_PARALLEL_1",
            }
        ),
        source_event=IntakeSourceEventRefV1.model_validate(
            {
                "message_id": "MESSAGE_PARALLEL_1",
                "logical_sequence": context.current_user_message.source_sequence,
                "actor_id": "user-local",
                "actor_role": "USER",
                "payload_sha256": context.current_user_message.text_sha256,
            }
        ),
        authority=IntakeAuthorityRefV1.model_validate(
            {
                "initiator_role": "USER",
                "respondent_role": "MERCHANT",
                "authority_snapshot_ref": "urn:intake:authority:parallel-1",
                "authority_snapshot_sha256": "a" * 64,
            }
        ),
        previous_state_ref="urn:intake:previous-state:parallel-1",
        previous_state_sha256=canonical_sha256(
            context.previous_state.model_dump(mode="json")
        ),
        model_context_view=context,
    )


def _instruction_packs() -> tuple[IntakeFrameInstructionPackV1, ...]:
    common = _prompt("intake_turn_parallel_authority.md")
    names = {
        "DIALOGUE_FRAME": "intake_turn_dialogue_frame.md",
        "DOSSIER_FRAME": "intake_turn_dossier_frame.md",
        "QUALITY_FRAME": "intake_turn_quality_frame.md",
    }
    return tuple(
        build_instruction_pack(
            frame_type=frame_type,
            common_authority_prompt=common,
            frame_prompt=_prompt(names[frame_type]),
        )
        for frame_type in FRAME_TYPES
    )


def _nested_keys(value: object) -> tuple[str, ...]:
    if isinstance(value, dict):
        return tuple(
            key
            for name, child in value.items()
            for key in (str(name), *_nested_keys(child))
        )
    if isinstance(value, (list, tuple)):
        return tuple(key for child in value for key in _nested_keys(child))
    return ()


def test_exact_three_inputs_share_one_immutable_business_context() -> None:
    context = _common_context()

    inputs = build_frame_model_inputs(
        context_envelope=_context_envelope(context),
        common_model_context=context,
        instruction_packs=_instruction_packs(),
    )
    replay = build_frame_model_inputs(
        context_envelope=_context_envelope(context),
        common_model_context=context,
        instruction_packs=_instruction_packs(),
    )

    assert inputs == replay
    assert tuple(item.frame_type for item in inputs) == FRAME_TYPES
    assert {
        item.common_model_context.model_context_view_sha256 for item in inputs
    } == {context.model_context_view_sha256}
    assert all(
        item.model_dump(mode="json")["common_model_context"]
        == context.model_dump(mode="json")
        for item in inputs
    )
    assert len({item.lane_model_context.contract_version for item in inputs}) == 3
    assert len({item.lane_model_context.lane_context_sha256 for item in inputs}) == 3
    assert len({item.instruction_pack.instruction_pack_sha256 for item in inputs}) == 3
    assert len({item.frame_model_input_sha256 for item in inputs}) == 3


def test_provider_payloads_expose_only_each_lane_minimum_context() -> None:
    context = _common_context()
    inputs = {
        item.frame_type: item
        for item in build_frame_model_inputs(
            context_envelope=_context_envelope(context),
            common_model_context=context,
            instruction_packs=_instruction_packs(),
        )
    }
    payloads = {frame_type: item.provider_payload() for frame_type, item in inputs.items()}

    for frame_type, payload in payloads.items():
        assert payload == inputs[frame_type].provider_payload()
        assert set(payload) == {"contract_version", "frame_type", "lane_model_context"}
        assert payload["frame_type"] == frame_type
        assert "common_model_context" not in payload
        assert "instruction_pack" not in payload
        assert not any(key.endswith("_sha256") for key in _nested_keys(payload))

    dialogue = payloads["DIALOGUE_FRAME"]["lane_model_context"]
    assert not {
        "frozen_case_matrix",
        "fact_key_authority",
        "previous_quality",
    } & set(dialogue)

    dossier = payloads["DOSSIER_FRAME"]["lane_model_context"]
    assert not {
        "current_action_binding",
        "authorized_question_slots",
        "recent_dialogue_messages",
        "previous_quality",
    } & set(dossier)

    quality = payloads["QUALITY_FRAME"]["lane_model_context"]
    assert not {
        "current_action_binding",
        "authorized_question_slots",
        "recent_dialogue_messages",
    } & set(quality)


def test_frame_model_input_rejects_rehashed_lane_drift_from_common_authority() -> None:
    context = _common_context()
    dialogue = build_frame_model_inputs(
        context_envelope=_context_envelope(context),
        common_model_context=context,
        instruction_packs=_instruction_packs(),
    )[0]
    payload = dialogue.model_dump(mode="json")
    payload.pop("frame_model_input_sha256")
    lane_payload = payload["lane_model_context"]
    lane_payload.pop("lane_context_sha256")
    lane_payload["recent_dialogue_messages"] = []
    payload["lane_model_context"] = type(dialogue.lane_model_context).seal(
        lane_payload
    ).model_dump(mode="json")

    with pytest.raises(ValueError, match="lane model context differs"):
        IntakeFrameModelInputV2.seal(payload)


def test_frame_model_input_rejects_lane_hash_drift_on_replay() -> None:
    context = _common_context()
    dialogue = build_frame_model_inputs(
        context_envelope=_context_envelope(context),
        common_model_context=context,
        instruction_packs=_instruction_packs(),
    )[0]
    payload = dialogue.model_dump(mode="json")
    payload["lane_model_context"]["lane_context_sha256"] = "f" * 64

    with pytest.raises(ValueError, match="lane_context_sha256"):
        IntakeFrameModelInputV2.model_validate(payload)


def test_instruction_packs_have_disjoint_frame_owned_outputs() -> None:
    packs = {pack.frame_type: pack for pack in _instruction_packs()}

    for frame_type, pack in packs.items():
        assert pack.allowed_output_fields[0] == "public_projection_items"
        assert not set(pack.allowed_output_fields) & set(pack.forbidden_output_fields)
        foreign_owned_fields = {
            field
            for other_type, other_pack in packs.items()
            if other_type != frame_type
            for field in other_pack.allowed_output_fields[1:]
        }
        assert set(pack.forbidden_output_fields) == foreign_owned_fields


def test_frame_output_schema_pins_dialogue_and_dossier_v4_without_changing_quality() -> None:
    assert dict(FRAME_OUTPUT_SCHEMA) == {
        "DIALOGUE_FRAME": "intake-dialogue-frame.v4",
        "DOSSIER_FRAME": "intake-dossier-frame.v4",
        "QUALITY_FRAME": "intake-quality-frame.v2",
    }


def test_prompt_profiles_do_not_embed_foreign_frame_rule_names() -> None:
    authority = _prompt("intake_turn_parallel_authority.md")
    dialogue = _prompt("intake_turn_dialogue_frame.md")
    dossier = _prompt("intake_turn_dossier_frame.md")
    quality = _prompt("intake_turn_quality_frame.md")

    assert "dossier_delta" not in dialogue and "score_breakdown" not in dialogue
    assert "room_utterance" not in dossier and "score_breakdown" not in dossier
    assert "room_utterance" not in quality and "dossier_delta" not in quality
    assert "不得生成、改写、转述问题正文" in dialogue
    assert "固定输出一个 `REMARK_ACKNOWLEDGEMENT`" in dialogue
    assert "立即闭合一次根对象并停止" in dialogue
    assert (
        '{"respondent_attitude":null,"respondent_position_summary":null,'
        '"respondent_alternative_proposal":null,"public_projection_items":[]}'
        in dossier
    )
    assert "不得把上一轮事实复制成伪新增项" in dossier
    assert "这不是空消息：它是对当前状态的直接确认" in dossier
    assert "不得输出空数组，也不得为同一语义创建新的 `NEW_*` 事实" in dossier
    assert "普通单条消息默认只输出 1 至 3 项" in dossier
    assert "`asserted_value` 即使没有短值也必须显式输出 `null`" in dossier
    assert "不得为了自我修正而重复任何右括号" in dossier
    assert "不得重新开始、改写或“修复”已经生成的 JSON" in authority
    assert (
        "`public_projection_items` → `gap_candidates` → `quality` 的顺序"
        in quality
    )
    assert "先在内部确定六项分数和 `gap_candidates` 的最终数量" in quality
    assert "必须把输入中的内部字段语义改写" in quality
    assert "内部事实键只允许出现在 `linked_fact_keys`" in quality
    assert "属于后续证据室材料，不是接待质量缺口" in quality
    assert "不得换一种说法重复追问" in quality
    assert "没有其他核心案情缺口时输出 `gap_candidates=[]`" in quality
    assert "只闭合一次 `quality` 对象和一次根对象并立即停止" in quality
    assert "下一阶段建议" not in quality
    assert "不得输出或建议阶段" in quality


@pytest.mark.parametrize(
    "forbidden_key",
    ["case_id", "room_epoch", "message_id", "logical_sequence", "COMMAND-ID"],
)
def test_provider_context_rejects_server_only_identity_at_any_depth(
    forbidden_key: str,
) -> None:
    with pytest.raises(ValueError, match="forbidden key"):
        _common_context(
            dossier_projection={"nested": {forbidden_key: "FORGED_AUTHORITY"}}
        )


def test_model_context_hash_fails_closed_on_fact_drift() -> None:
    context = _common_context()
    payload = context.model_dump(mode="json")
    payload["current_user_message"]["text"] = "商品于今日签收。"

    with pytest.raises(ValueError, match="text_sha256|model_context_view_sha256"):
        IntakeModelContextViewV1.model_validate(payload)


def test_exact_three_builder_rejects_duplicate_or_missing_frame_pack() -> None:
    context = _common_context()
    packs = _instruction_packs()

    with pytest.raises(ValueError, match="exactly one instruction pack"):
        build_frame_model_inputs(
            context_envelope=_context_envelope(context),
            common_model_context=context,
            instruction_packs=(packs[0], packs[0], packs[1]),
        )


def test_instruction_pack_hash_and_registry_are_both_authoritative() -> None:
    pack = _instruction_packs()[0]
    payload = pack.model_dump(mode="json")
    payload["output_schema_id"] = "intake-dossier-frame.v2"

    with pytest.raises(ValueError, match="instruction_pack_sha256|output_schema_id"):
        IntakeFrameInstructionPackV1.model_validate(payload)


def test_context_envelope_must_bind_the_exact_common_model_view() -> None:
    original = _common_context()
    drifted = _common_context(
        dossier_projection={"event_story": "商品尚未发货。"}
    )

    with pytest.raises(ValueError, match="envelope does not bind"):
        build_frame_model_inputs(
            context_envelope=_context_envelope(original),
            common_model_context=drifted,
            instruction_packs=_instruction_packs(),
        )


def test_action_binding_must_match_previous_persisted_phase() -> None:
    context = _common_context()
    payload = context.model_dump(mode="json")
    payload.pop("model_context_view_sha256")
    payload["current_action_binding"].update(
        {
            "action": "INVITE_OPTIONAL_REMARK",
            "derived_from_phase": "READY_PENDING_REMARK_INVITE",
        }
    )

    with pytest.raises(ValueError, match="action phase"):
        IntakeModelContextViewV1.seal(payload)


def test_opening_and_formal_events_cannot_enter_parallel_frame_context() -> None:
    assert IntakeTurnRouteV1.model_validate(
        {
            "source_type": "INITIAL_FORM",
            "execution_profile": "OPENING_DIALOGUE_ONLY",
        }
    ).execution_profile == "OPENING_DIALOGUE_ONLY"
    with pytest.raises(ValueError, match="authoritative source_type"):
        IntakeTurnRouteV1.model_validate(
            {
                "source_type": "INITIAL_FORM",
                "execution_profile": "PARALLEL_FRAMES",
            }
        )

    context = _common_context()
    payload = context.model_dump(mode="json")
    payload.pop("model_context_view_sha256")
    payload["turn_route"] = {
        "source_type": "RESPONDENT_OPENING",
        "execution_profile": "OPENING_DIALOGUE_ONLY",
    }
    with pytest.raises(ValueError, match="only accepts ROOM_MESSAGE"):
        IntakeModelContextViewV1.seal(payload)
