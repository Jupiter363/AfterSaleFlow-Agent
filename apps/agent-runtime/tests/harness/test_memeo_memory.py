# 文件作用：自动化测试文件，验证 test_memeo_memory 相关模块的行为、契约或页面布局。

from __future__ import annotations

import pytest

from app.harness.memory import (
    MemeoMemoryAssembler,
    MemeoMemoryConfig,
    MemoryScopeViolation,
)


# 所属模块：Agent Harness > test_memeo_memory；函数角色：模块私有业务函数。
# 具体功能：`_participant` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`turn_no`、`actor_id`、`answer_role`、`answer_content`。
# 上下游：上游为 本文件的 `test_memeo_memory_uses_latest_five_rounds_as_short_term_memory`、`test_memeo_memory_compresses_latest_ten_round_window_to_200_tokens`、`test_memeo_long_term_memory_slot_is_reserved_but_not_prompted`、`test_memeo_memory_modes_are_configurable_for_future_agent_center`；下游为 返回/更新 `turn_no`、`actor_id`、`answer_role`、`answer_content`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def _participant(turn_no: int, text: str) -> dict[str, object]:
    return {
        "turn_no": turn_no,
        "actor_id": "user-local",
        "answer_role": "USER",
        "answer_content": text,
        "agent_role": None,
        "agent_response": None,
        "scroll_snapshot": {},
    }


# 所属模块：Agent Harness > test_memeo_memory；函数角色：模块私有业务函数。
# 具体功能：`_agent` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`turn_no`、`actor_id`、`answer_role`、`answer_content`。
# 上下游：上游为 本文件的 `test_memeo_memory_uses_latest_five_rounds_as_short_term_memory`、`test_strict_memeo_memory_rejects_turn_from_different_agent_session`、`test_strict_memeo_memory_accepts_only_matching_session_turns`；下游为 返回/更新 `turn_no`、`actor_id`、`answer_role`、`answer_content`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def _agent(turn_no: int, text: str) -> dict[str, object]:
    return {
        "turn_no": turn_no,
        "actor_id": "dispute-intake-officer",
        "answer_role": None,
        "answer_content": None,
        "agent_role": "DISPUTE_INTAKE_OFFICER",
        "agent_response": text,
        "scroll_snapshot": {},
    }


# 所属模块：Agent Harness > test_memeo_memory；函数角色：模块私有业务函数。
# 具体功能：`_scoped` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`agent_session_id`、`conversation_scope`。
# 上下游：上游为 本文件的 `test_strict_memeo_memory_rejects_turn_from_different_agent_session`、`test_strict_memeo_memory_rejects_conversation_scope_mismatch`、`test_strict_memeo_memory_accepts_only_matching_session_turns`；下游为 返回/更新 `agent_session_id`、`conversation_scope`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def _scoped(turn: dict[str, object], session_id: str = "SESSION_A") -> dict[str, object]:
    return {
        **turn,
        "agent_session_id": session_id,
        "conversation_scope": f"default:CASE_memory:INTAKE:USER_local:USER:AGENT:v1:{session_id}",
    }


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_memeo_memory_uses_latest_five_rounds_as_short_term_memory` 验证Agent 回合记忆在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`turns.append`、`MemeoMemoryAssembler`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_participant`、`_agent`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_memeo_memory_uses_latest_five_rounds_as_short_term_memory() -> None:
    turns: list[dict[str, object]] = []
    for turn_no in range(1, 8):
        turns.append(_participant(turn_no, f"user says round {turn_no}"))
        turns.append(_agent(turn_no, f"agent asks round {turn_no}"))

    frame = MemeoMemoryAssembler().assemble(turns)

    assert frame.memory_modes.short_term_enabled is True
    assert frame.memory_modes.summary_enabled is True
    assert frame.memory_modes.long_term_enabled is False
    assert frame.memory_modes.short_term_round_limit == 5
    assert [item.turn_no for item in frame.short_term_rounds] == [3, 4, 5, 6, 7]
    assert "user says round 2" not in frame.prompt_memory
    assert "user says round 7" in frame.prompt_memory
    assert "agent asks round 7" in frame.prompt_memory
    assert frame.compressed_summary == ""
    assert frame.prompt_memory_estimated_tokens <= 200


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_memeo_memory_compresses_latest_ten_round_window_to_200_tokens` 验证Agent 回合记忆在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`MemeoMemoryAssembler`、`MemeoMemoryConfig`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_participant`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_memeo_memory_compresses_latest_ten_round_window_to_200_tokens() -> None:
    turns = [
        _participant(
            turn_no,
            f"round {turn_no} "
            + "delivery cabinet screenshot proof merchant reply " * 20,
        )
        for turn_no in range(1, 13)
    ]

    frame = MemeoMemoryAssembler().assemble(
        turns,
        config=MemeoMemoryConfig(compressed_token_limit=200),
    )

    assert frame.compression_window_turns == (3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    assert "Round 1:" not in frame.compressed_summary
    assert "round 12" in frame.compressed_summary
    assert frame.compressed_summary_estimated_tokens <= 200
    assert "round 12" in frame.prompt_memory
    assert "Short-term memory" in frame.prompt_memory
    assert "Compressed summary" in frame.prompt_memory


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_memeo_long_term_memory_slot_is_reserved_but_not_prompted` 验证模型提示词在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`MemeoMemoryAssembler`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_participant`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_memeo_long_term_memory_slot_is_reserved_but_not_prompted() -> None:
    frame = MemeoMemoryAssembler().assemble(
        [_participant(1, "historical material should be visible only")],
        long_term_preview=[
            {
                "memory_key": "MEM0_VECTOR_SLOT",
                "status": "RESERVED",
                "content": "vector memory not enabled yet",
            }
        ],
    )

    assert frame.long_term_slots[0].memory_key == "MEM0_VECTOR_SLOT"
    assert frame.long_term_slots[0].prompt_included is False
    assert "vector memory not enabled yet" not in frame.prompt_memory


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_memeo_memory_modes_are_configurable_for_future_agent_center` 验证Agent 回合记忆在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`MemeoMemoryAssembler`、`MemeoMemoryConfig`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_participant`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_memeo_memory_modes_are_configurable_for_future_agent_center() -> None:
    turns = [_participant(turn_no, f"user round {turn_no}") for turn_no in range(1, 12)]

    no_short_term = MemeoMemoryAssembler().assemble(
        turns,
        config=MemeoMemoryConfig(short_term_enabled=False, summary_enabled=True),
    )
    assert no_short_term.memory_modes.short_term_enabled is False
    assert no_short_term.memory_modes.summary_enabled is True
    assert no_short_term.short_term_rounds == ()
    assert "Compressed summary" in no_short_term.prompt_memory
    assert no_short_term.compressed_summary in no_short_term.prompt_memory

    no_summary = MemeoMemoryAssembler().assemble(
        turns,
        config=MemeoMemoryConfig(short_term_enabled=True, summary_enabled=False),
    )
    assert no_summary.memory_modes.short_term_enabled is True
    assert no_summary.memory_modes.summary_enabled is False
    assert no_summary.compressed_summary == ""
    assert "user round 7" in no_summary.prompt_memory
    assert "user round 6" not in no_summary.prompt_memory

    long_term_enabled = MemeoMemoryAssembler().assemble(
        turns,
        config=MemeoMemoryConfig(long_term_enabled=True),
        long_term_preview=[
            {
                "memory_key": "merchant-pattern",
                "status": "RETRIEVED",
                "content": "prior merchant refusal pattern",
            }
        ],
    )
    assert long_term_enabled.memory_modes.long_term_enabled is True
    assert long_term_enabled.long_term_slots[0].enabled is True
    assert long_term_enabled.long_term_slots[0].prompt_included is False


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_strict_memeo_memory_rejects_turn_from_different_agent_session` 验证参与方会话在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`assemble`、`MemeoMemoryAssembler`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_scoped`、`_participant`、`_agent`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_strict_memeo_memory_rejects_turn_from_different_agent_session() -> None:
    turns = [
        _scoped(_participant(1, "current actor private memory"), "SESSION_A"),
        _scoped(_agent(2, "polluted counterparty memory"), "SESSION_B"),
    ]

    with pytest.raises(MemoryScopeViolation) as failure:
        MemeoMemoryAssembler().assemble(
            turns,
            expected_agent_session_id="SESSION_A",
            strict_scope=True,
        )

    assert "agent_session_id mismatch" in str(failure.value)


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_strict_memeo_memory_rejects_turn_without_agent_session_id` 验证参与方会话在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`assemble`、`MemeoMemoryAssembler`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_participant`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_strict_memeo_memory_rejects_turn_without_agent_session_id() -> None:
    with pytest.raises(MemoryScopeViolation) as failure:
        MemeoMemoryAssembler().assemble(
            [_participant(1, "legacy unscoped memory")],
            expected_agent_session_id="SESSION_A",
            strict_scope=True,
        )

    assert "missing agent_session_id" in str(failure.value)


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_strict_memeo_memory_rejects_conversation_scope_mismatch` 验证Agent 回合记忆在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`assemble`、`MemeoMemoryAssembler`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_scoped`、`_participant`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_strict_memeo_memory_rejects_conversation_scope_mismatch() -> None:
    turns = [
        {
            **_scoped(_participant(1, "current actor private memory"), "SESSION_A"),
            "conversation_scope": (
                "default:CASE_memory:INTAKE:USER_other:USER:AGENT:v1:SESSION_A"
            ),
        }
    ]

    with pytest.raises(MemoryScopeViolation) as failure:
        MemeoMemoryAssembler().assemble(
            turns,
            expected_agent_session_id="SESSION_A",
            expected_conversation_scope=(
                "default:CASE_memory:INTAKE:USER_local:USER:AGENT:v1:SESSION_A"
            ),
            strict_scope=True,
        )

    assert "conversation_scope mismatch" in str(failure.value)


# 所属模块：Agent Harness > test_memeo_memory；函数角色：回归测试用例。
# 具体功能：`test_strict_memeo_memory_accepts_only_matching_session_turns` 验证参与方会话在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`MemeoMemoryAssembler`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_scoped`、`_participant`、`_agent`。
# 系统意义：固定“Agent Harness > test_memeo_memory”的可观察契约，防止后续重构改变业务结果。
def test_strict_memeo_memory_accepts_only_matching_session_turns() -> None:
    frame = MemeoMemoryAssembler().assemble(
        [
            _scoped(_participant(1, "current actor private memory"), "SESSION_A"),
            _scoped(_agent(1, "agent private response"), "SESSION_A"),
        ],
        expected_agent_session_id="SESSION_A",
        expected_conversation_scope=(
            "default:CASE_memory:INTAKE:USER_local:USER:AGENT:v1:SESSION_A"
        ),
        strict_scope=True,
    )

    assert "current actor private memory" in frame.prompt_memory
    assert "agent private response" in frame.prompt_memory
