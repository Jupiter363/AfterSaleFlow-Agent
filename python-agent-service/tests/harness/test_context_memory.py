# 文件作用：自动化测试文件，验证 test_context_memory 相关模块的行为、契约或页面布局。

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.harness.context import (
    ContextAssembler,
    ContextAuthorityError,
    ContextFragment,
    ContextTokenBudgetError,
)
from app.harness.memory import MemoryEntry, MemoryScope
from app.harness.profile import AgentProfile
from tests.harness.test_profile import profile_payload


# 所属模块：Agent Harness > test_context_memory；函数角色：模块公开业务函数。
# 具体功能：`profile` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`AgentProfile.model_validate`、`profile_payload`。
# 上下游：上游为 本文件的 `test_context_rejects_wrong_case_state_and_unauthorized_scope`、`test_context_is_deterministic_and_respects_the_token_budget`；下游为 协作调用 `AgentProfile.model_validate`、`profile_payload`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def profile() -> AgentProfile:
    return AgentProfile.model_validate(profile_payload())


# 所属模块：Agent Harness > test_context_memory；函数角色：模块公开业务函数。
# 具体功能：`fragment` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`ContextFragment`、`datetime`。
# 上下游：上游为 本文件的 `test_context_rejects_wrong_case_state_and_unauthorized_scope`、`test_context_is_deterministic_and_respects_the_token_budget`；下游为 协作调用 `ContextFragment`、`datetime`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def fragment(
    source_id: str,
    *,
    scope: str = "evidence",
    content: str = "short evidence",
    priority: int = 50,
) -> ContextFragment:
    return ContextFragment(
        source_type="EVIDENCE",
        source_id=source_id,
        source_version="3",
        captured_at=datetime(2026, 7, 2, tzinfo=UTC),
        access_scope=scope,
        content=content,
        priority=priority,
    )


# 所属模块：Agent Harness > test_context_memory；函数角色：回归测试用例。
# 具体功能：`test_context_rejects_wrong_case_state_and_unauthorized_scope` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`ContextAssembler`、`pytest.raises`、`assembler.assemble`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `profile`、`fragment`。
# 系统意义：固定“Agent Harness > test_context_memory”的可观察契约，防止后续重构改变业务结果。
def test_context_rejects_wrong_case_state_and_unauthorized_scope() -> None:
    assembler = ContextAssembler()

    with pytest.raises(ContextAuthorityError):
        assembler.assemble(profile(), "CLOSED", [fragment("EV-1")], 1_000)

    with pytest.raises(ContextAuthorityError):
        assembler.assemble(
            profile(),
            "DOSSIER_BUILDING",
            [fragment("PAY-1", scope="payment-secret")],
            1_000,
        )


# 所属模块：Agent Harness > test_context_memory；函数角色：回归测试用例。
# 具体功能：`test_context_is_deterministic_and_respects_the_token_budget` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`ContextAssembler`、`assembler.assemble`、`pytest.raises`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `fragment`、`profile`。
# 系统意义：固定“Agent Harness > test_context_memory”的可观察契约，防止后续重构改变业务结果。
def test_context_is_deterministic_and_respects_the_token_budget() -> None:
    assembler = ContextAssembler()
    high = fragment("EV-HIGH", content="a" * 200, priority=100)
    low = fragment("EV-LOW", content="b" * 200, priority=10)

    assembled = assembler.assemble(
        profile(), "DOSSIER_BUILDING", [low, high], 100
    )

    assert [item.source_id for item in assembled.fragments] == ["EV-HIGH"]
    assert assembled.estimated_tokens <= 100

    with pytest.raises(ContextTokenBudgetError):
        assembler.assemble(
            profile(),
            "DOSSIER_BUILDING",
            [fragment("EV-REQUIRED", content="x" * 2_000, priority=100)],
            10,
            required_source_ids={"EV-REQUIRED"},
        )


# 所属模块：Agent Harness > test_context_memory；函数角色：回归测试用例。
# 具体功能：`test_experience_memory_requires_explicit_offline_approval` 验证Agent 回合记忆在固定案例中的输出、边界和失败行为；关键协作调用：`MemoryEntry`、`pytest.raises`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `MemoryEntry`、`pytest.raises`。
# 系统意义：固定“Agent Harness > test_context_memory”的可观察契约，防止后续重构改变业务结果。
def test_experience_memory_requires_explicit_offline_approval() -> None:
    common = {
        "memory_key": "swap-fraud-pattern",
        "memory_version": 1,
        "source_refs": ["CASE-1"],
        "content": {"pattern": "serial number mismatch"},
    }

    with pytest.raises(ValidationError):
        MemoryEntry(
            **common,
            scope=MemoryScope.EXPERIENCE,
            approved_for_experience=False,
        )

    approved = MemoryEntry(
        **common,
        scope=MemoryScope.EXPERIENCE,
        approved_for_experience=True,
        approved_by="review-governance",
    )

    assert approved.scope is MemoryScope.EXPERIENCE
