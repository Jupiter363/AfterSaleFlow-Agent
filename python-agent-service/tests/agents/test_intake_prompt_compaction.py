# 文件作用：验证接待室 Prompt 压缩只投影增量推理所需字段，并保持最近三轮对话和旧问题去重合同。

from __future__ import annotations

import json

from app.agents.dispute_intake_officer.workflow import (
    _compact_case_detail_snapshot,
    _compact_dialogue_window,
)


# 所属模块：接待室 Agent 测试 > 上一版案件看板 Prompt 投影。
# 具体功能：验证压缩器删除原话/provenance、时间线与重复旧别名，限制核验重点，并把整体 JSON 大小压到源看板一半以下。
# 上下游：上游构造含重复字段、超长原话和 10 条时间线的完整旧卷宗；下游断言 `_compact_case_detail_snapshot` 仅保留模型增量回合所需白名单。
# 系统意义：锁定“持久化看板保持完整、Prompt 只用紧凑投影”的边界，防止后续重构重新把敏感原话或大量重复内容送入模型。
def test_previous_board_prompt_projection_removes_duplicate_and_raw_fields() -> None:
    repeated_focus = [
        "核验续费提醒记录",
        "核验续费提醒记录",
        "核验客服沟通时间",
        "核验商家回应",
        "核验服务使用情况",
        "核验扣款时间",
    ]
    source = {
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "title": "自动续费争议",
            "one_sentence_summary": "用户称未收到续费提醒并请求退款。",
            "timeline": [
                {"time": str(index), "event": f"事件{index}"}
                for index in range(10)
            ],
            "event_timeline": [{"event": "重复时间线"}],
        },
        "references": {"order_reference": "ORDER_123"},
        "party_positions": {
            "initiator_position": "用户请求退款。",
            "respondent_position": "用户转述商家拒绝退款。",
            "raw_statement": "不应进入模型上下文" * 500,
        },
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
            "normalized_statement": "用户请求退款。",
            "original_statement": "原始输入" * 500,
            "original_statement_provenance": {"policy": "INITIATOR_INPUTS_V1"},
        },
        "respondent_attitude": {
            "source": "发起方单方陈述（主观）",
            "attitude": "DISAGREE",
            "position": "用户转述商家拒绝退款。",
        },
        "dispute_core_state": {
            "core_conflict": "用户请求退款，但用户转述商家拒绝退款。",
            "facts_in_dispute": ["是否发送续费提醒"],
            "next_verification_focus": [
                "等待接待官完成案件详情整理",
                "信息完整度已达到提交阈值",
                *repeated_focus,
            ],
        },
        "requested_resolution": {"requested_outcome": "REFUND"},
        "dispute_focus": {"core_issue": "与核心状态重复"},
        "missing_information": {
            "next_questions": ["何时发现扣款？", "何时发现扣款？"],
        },
        "intake_quality": {
            "score": 88,
            "ready_for_next_step": True,
            "improvement_reason": "信息完整度已达到提交阈值。",
        },
    }

    compact = _compact_case_detail_snapshot(source)

    assert "raw_statement" not in compact["party_positions"]
    assert "original_statement" not in compact["claim_resolution"]
    assert "original_statement_provenance" not in compact["claim_resolution"]
    assert "requested_resolution" not in compact
    assert "dispute_focus" not in compact
    assert "timeline" not in compact["case_story"]
    assert compact["missing_information"]["next_questions"] == ["何时发现扣款？"]
    assert compact["dispute_core_state"]["next_verification_focus"] == [
        "核验续费提醒记录",
        "核验客服沟通时间",
        "核验商家回应",
        "核验服务使用情况",
    ]
    assert "improvement_reason" not in compact["intake_quality"]
    assert len(json.dumps(compact, ensure_ascii=False)) < (
        len(json.dumps(source, ensure_ascii=False)) // 2
    )


# 所属模块：接待室 Agent 测试 > 最近对话窗口投影。
# 具体功能：验证 7 条历史只保留最后 5 条（覆盖三个由 Agent 开始的片段），并从每条消息删除 message_id/source，仅保留 role/text/sequence_no。
# 上下游：上游构造交替 Agent/User 的房间消息；下游断言 `_compact_dialogue_window` 的长度、首尾角色和字段集合。
# 系统意义：锁定接待 Prompt 的会话窗口与最小披露合同，避免传输元数据和无限历史重新进入 LLM 上下文。
def test_dialogue_projection_keeps_three_system_started_turns_without_ids() -> None:
    messages = [
        {
            "role": "AGENT" if index % 2 == 0 else "USER",
            "text": f"消息{index}",
            "sequence_no": index + 1,
            "message_id": f"MESSAGE_{index}",
            "source": "ROOM_MESSAGE",
        }
        for index in range(7)
    ]

    compact = _compact_dialogue_window(messages)

    assert len(compact) == 5
    assert compact[0]["role"] == "AGENT"
    assert compact[-1]["role"] == "AGENT"
    assert all(set(message) == {"role", "text", "sequence_no"} for message in compact)
