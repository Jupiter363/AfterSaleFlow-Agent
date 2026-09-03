# 文件作用：自动化测试文件，验证 test_intake_resource_bounds 相关模块的行为、契约或页面布局。

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
)
from app.schemas import IntakeTurnRequest


# 所属模块：Agent 角色能力 > test_intake_resource_bounds；函数角色：模块私有业务函数。
# 具体功能：`_agent_context` 围绕案件与会话上下文计算该函数独立负责的业务派生值；返回/更新字段：`tenant_id`、`case_id`、`room_type`、`actor_id`。
# 上下游：上游为 本文件的 `_room_payload`；下游为 返回/更新 `tenant_id`、`case_id`、`room_type`、`actor_id`。
# 系统意义：控制隐私、Token 和会话隔离：服从角色权限、上下文范围和非最终结论边界。
def _agent_context(case_id: str) -> dict[str, object]:
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "INTAKE",
        "actor_id": "USER_local_1",
        "actor_role": "USER",
        "access_session_id": f"ACCESS_{case_id}_USER",
        "permission_level": "PARTY_USER",
        "permission_scopes": [],
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_invocation_id": f"INVOCATION_{case_id}",
        "agent_session_id": f"SESSION_{case_id}_user_intake",
        "conversation_scope": (
            f"default:{case_id}:INTAKE:USER_local_1:USER:"
            "DISPUTE_INTAKE_OFFICER:DISPUTE_INTAKE_OFFICER:USER:v1:"
            f"ACCESS_{case_id}_USER"
        ),
        "scope_type": "INTAKE_INITIATOR_PRIVATE",
        "allowed_actor_ids": ["USER_local_1"],
        "allowed_actor_roles": ["USER"],
        "prompt_profile_id": "DISPUTE_INTAKE_OFFICER:USER:v1",
        "memory_policy_id": "MEMORY_POLICY_INTAKE_V1",
    }


# 所属模块：Agent 角色能力 > test_intake_resource_bounds；函数角色：模块私有业务函数。
# 具体功能：`_room_payload` 读取并按案件、角色或会话范围筛选本阶段状态；返回/更新字段：`case_id`、`room_type`、`turn_source`、`initial_case_facts`。
# 上下游：上游为 本文件的 `test_intake_request_rejects_unbounded_verbatim_transcript`、`test_intake_request_rejects_deep_previous_board_before_copying`、`test_dossier_drops_context_echo_fields_instead_of_persisting_them`、`test_repeated_dossier_turns_do_not_nest_prior_snapshots`；下游为 本文件的 `_agent_context`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _room_payload(
    case_id: str,
    *,
    previous_case_detail: dict[str, object] | None = None,
    transcript: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "case_id": case_id,
        "room_type": "INTAKE",
        "turn_source": "ROOM_MESSAGE",
        "initial_case_facts": None,
        "current_user_message": {
            "message_id": "MESSAGE_current",
            "sequence_no": 2,
            "role": "USER",
            "source": "ROOM_MESSAGE",
            "text": "我补充本轮案情。",
        },
        "recent_dialogue_messages": [
            {
                "message_id": "MESSAGE_agent_opening",
                "sequence_no": 1,
                "role": "AGENT",
                "source": "AGENT_RESPONSE",
                "text": "请说明需要补充的案情。",
            }
        ],
        "previous_case_detail": previous_case_detail,
        "initiator_statement_transcript": transcript or [],
        "agent_context": _agent_context(case_id),
    }


# 所属模块：Agent 角色能力 > test_intake_resource_bounds；函数角色：回归测试用例。
# 具体功能：`test_intake_request_rejects_unbounded_verbatim_transcript` 验证接待信息在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`IntakeTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_room_payload`。
# 系统意义：固定“Agent 角色能力 > test_intake_resource_bounds”的可观察契约，防止后续重构改变业务结果。
def test_intake_request_rejects_unbounded_verbatim_transcript() -> None:
    transcript = [
        {
            "message_id": f"MESSAGE_{index:03d}",
            "role": "USER",
            "text": "原始陈述",
        }
        for index in range(101)
    ]

    with pytest.raises(ValidationError, match="exceeds 100 messages"):
        IntakeTurnRequest.model_validate(
            _room_payload("CASE_intake_transcript_limit", transcript=transcript)
        )


# 所属模块：Agent 角色能力 > test_intake_resource_bounds；函数角色：回归测试用例。
# 具体功能：`test_intake_request_rejects_deep_previous_board_before_copying` 验证接待信息在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`IntakeTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_room_payload`。
# 系统意义：固定“Agent 角色能力 > test_intake_resource_bounds”的可观察契约，防止后续重构改变业务结果。
def test_intake_request_rejects_deep_previous_board_before_copying() -> None:
    nested: dict[str, object] = {"value": "leaf"}
    for _ in range(14):
        nested = {"nested": nested}

    with pytest.raises(ValidationError, match="exceeds nesting depth 12"):
        IntakeTurnRequest.model_validate(
            _room_payload(
                "CASE_intake_board_depth_limit",
                previous_case_detail={
                    "schema_version": "intake_case_detail.v1",
                    "case_story": nested,
                },
            )
        )


# 所属模块：Agent 角色能力 > test_intake_resource_bounds；函数角色：回归测试用例。
# 具体功能：`test_dossier_drops_context_echo_fields_instead_of_persisting_them` 把案件卷宗写入或合并到可追溯的阶段状态；关键协作调用：`IntakeTurnRequest.model_validate`、`render`、`CaseDetailDossierSkill`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_room_payload`。
# 系统意义：固定“Agent 角色能力 > test_intake_resource_bounds”的可观察契约，防止后续重构改变业务结果。
def test_dossier_drops_context_echo_fields_instead_of_persisting_them() -> None:
    previous = {
        "schema_version": "intake_case_detail.v1",
        "case_story": {"one_sentence_summary": "用户补充案情。"},
        "previous_case_detail": {"should_not": "persist"},
    }
    request = IntakeTurnRequest.model_validate(
        _room_payload(
            "CASE_intake_context_echo",
            previous_case_detail=previous,
            transcript=[
                {
                    "message_id": "MESSAGE_current",
                    "role": "USER",
                    "text": "我补充本轮案情。",
                }
            ],
        )
    )

    result = CaseDetailDossierSkill().render(
        request=request,
        room_utterance="已记录。",
        llm_case_detail={
            "case_story": {"one_sentence_summary": "用户补充了本轮案情。"},
            "previous_case_detail": copy.deepcopy(previous),
            "context_pack": {"previous_case_detail": copy.deepcopy(previous)},
        },
        llm_dossier_patch=None,
        llm_scroll_snapshot=None,
        llm_canvas_operations=[],
        llm_admission_recommendation="NEED_MORE_INFO",
        llm_missing_fields=[],
        llm_confidence=0.5,
    )

    assert "previous_case_detail" not in result.scroll_snapshot
    assert "context_pack" not in result.scroll_snapshot


# 所属模块：Agent 角色能力 > test_intake_resource_bounds；函数角色：回归测试用例。
# 具体功能：`test_repeated_dossier_turns_do_not_nest_prior_snapshots` 验证案件卷宗在固定案例中的输出、边界和失败行为；关键协作调用：`IntakeTurnRequest.model_validate`、`render`、`serialized_sizes.append`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_room_payload`。
# 系统意义：固定“Agent 角色能力 > test_intake_resource_bounds”的可观察契约，防止后续重构改变业务结果。
def test_repeated_dossier_turns_do_not_nest_prior_snapshots() -> None:
    previous: dict[str, object] = {
        "schema_version": "intake_case_detail.v1",
        "case_story": {"one_sentence_summary": "用户补充案情。"},
    }
    serialized_sizes: list[int] = []

    for round_no in range(40):
        request = IntakeTurnRequest.model_validate(
            _room_payload(
                f"CASE_intake_stable_{round_no}",
                previous_case_detail=copy.deepcopy(previous),
                transcript=[
                    {
                        "message_id": "MESSAGE_current",
                        "role": "USER",
                        "text": "我补充本轮案情。",
                    }
                ],
            )
        )
        result = CaseDetailDossierSkill().render(
            request=request,
            room_utterance="已记录。",
            llm_case_detail={
                "case_story": {"one_sentence_summary": "用户补充了本轮案情。"},
                "previous_case_detail": copy.deepcopy(previous),
            },
            llm_dossier_patch=None,
            llm_scroll_snapshot=None,
            llm_canvas_operations=[],
            llm_admission_recommendation="NEED_MORE_INFO",
            llm_missing_fields=[],
            llm_confidence=0.5,
        )
        previous = result.scroll_snapshot
        assert "previous_case_detail" not in previous
        serialized_sizes.append(
            len(json.dumps(previous, ensure_ascii=False, separators=(",", ":")))
        )

    # Matrix version fields legitimately gain digits, but prior snapshots must
    # not accumulate in the next render.
    assert max(serialized_sizes[1:]) - min(serialized_sizes[1:]) <= 8
