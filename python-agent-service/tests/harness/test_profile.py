# 文件作用：自动化测试文件，验证 test_profile 相关模块的行为、契约或页面布局。

import pytest
from pydantic import ValidationError

from app.harness.profile import AgentProfile, LoopBudget


# 所属模块：Agent Harness > test_profile；函数角色：模块公开业务函数。
# 具体功能：`profile_payload` 读取并按案件、角色或会话范围筛选本阶段状态；返回/更新字段：`agent_id`、`role`、`version`、`allowed_case_states`。
# 上下游：上游为 本文件的 `test_profile_explicitly_authorizes_only_declared_capabilities`、`test_profile_rejects_authority_conflicts`；下游为 返回/更新 `agent_id`、`role`、`version`、`allowed_case_states`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def profile_payload() -> dict:
    return {
        "agent_id": "evidence-clerk",
        "role": "Evidence Clerk",
        "version": "final-1",
        "allowed_case_states": ["DOSSIER_BUILDING"],
        "allowed_context_scopes": ["case", "evidence"],
        "allowed_skills": ["timeline.build", "evidence.gaps"],
        "allowed_tools": ["case.read", "evidence.read", "evidence.parse"],
        "forbidden_actions": [
            "decide_liability",
            "review.approve",
            "refund.execute",
        ],
        "budget": {
            "max_iterations": 8,
            "max_tool_calls": 12,
            "max_model_calls": 8,
            "max_input_tokens": 40_000,
            "max_output_tokens": 8_000,
            "deadline_seconds": 120,
            "stagnation_threshold": 2,
            "max_output_repairs": 1,
        },
        "output_schema": "EvidenceDossierResult",
        "risk_policy": "evidence-clerk-final",
    }


# 所属模块：Agent Harness > test_profile；函数角色：回归测试用例。
# 具体功能：`test_profile_explicitly_authorizes_only_declared_capabilities` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`AgentProfile.model_validate`、`profile.authorizes_case_state`、`profile.authorizes_context`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `profile_payload`。
# 系统意义：固定“Agent Harness > test_profile”的可观察契约，防止后续重构改变业务结果。
def test_profile_explicitly_authorizes_only_declared_capabilities() -> None:
    profile = AgentProfile.model_validate(profile_payload())

    assert profile.authorizes_case_state("DOSSIER_BUILDING")
    assert profile.authorizes_context("evidence")
    assert profile.authorizes_skill("timeline.build")
    assert profile.authorizes_tool("evidence.read")
    assert not profile.authorizes_tool("refund.execute")
    assert not profile.authorizes_tool("unknown.read")
    assert profile.forbids("decide_liability")


# 所属模块：Agent Harness > test_profile；函数角色：回归测试用例。
# 具体功能：`test_profile_rejects_authority_conflicts` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`append`、`pytest.raises`、`AgentProfile.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `profile_payload`。
# 系统意义：固定“Agent Harness > test_profile”的可观察契约，防止后续重构改变业务结果。
def test_profile_rejects_authority_conflicts() -> None:
    payload = profile_payload()
    payload["allowed_tools"].append("refund.execute")

    with pytest.raises(ValidationError):
        AgentProfile.model_validate(payload)


# 所属模块：Agent Harness > test_profile；函数角色：回归测试用例。
# 具体功能：`test_loop_budget_rejects_unbounded_or_incoherent_limits` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`LoopBudget`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `pytest.raises`、`LoopBudget`。
# 系统意义：固定“Agent Harness > test_profile”的可观察契约，防止后续重构改变业务结果。
def test_loop_budget_rejects_unbounded_or_incoherent_limits() -> None:
    with pytest.raises(ValidationError):
        LoopBudget(
            max_iterations=0,
            max_tool_calls=12,
            max_model_calls=8,
            max_input_tokens=40_000,
            max_output_tokens=8_000,
            deadline_seconds=120,
            stagnation_threshold=2,
            max_output_repairs=1,
        )

    with pytest.raises(ValidationError):
        LoopBudget(
            max_iterations=8,
            max_tool_calls=2,
            max_model_calls=8,
            max_input_tokens=40_000,
            max_output_tokens=50_000,
            deadline_seconds=120,
            stagnation_threshold=2,
            max_output_repairs=1,
        )
