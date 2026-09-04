from app.agents.dispute_intake_officer.skills.dossier.intake_guidance import (
    _human_missing_fields,
    _is_evidence_material_request,
    _question_for_missing,
    _question_for_quality_gap,
)


def test_internal_field_names_are_never_exposed_as_user_copy() -> None:
    assert _human_missing_fields(["ORDER_REFERENCE", "custom_internal_token"]) == [
        "订单号",
        "相关补充材料",
    ]
    assert _question_for_missing(["ORDER_REFERENCE", "custom_internal_token"]) == (
        "请补充订单号或平台可识别的订单引用。 请补充相关补充材料。"
    )


def test_evidence_boundary_distinguishes_transfer_requests_from_factual_context() -> None:
    assert _is_evidence_material_request("请上传开箱视频和聊天记录截图。") is True
    assert _is_evidence_material_request("还需要物流凭证。") is True
    assert _is_evidence_material_request("商家表示此前已提供物流凭证。") is False
    assert _is_evidence_material_request("订单确认稿具体是哪个版本的沟通记录或文件？") is False


def test_quality_gap_question_uses_the_first_incomplete_component() -> None:
    maxima = {"references": 15, "event_story": 20}
    assert _question_for_quality_gap({"references": 15, "event_story": 19}, maxima) == (
        "请继续补充可核验的事件经过。"
    )
