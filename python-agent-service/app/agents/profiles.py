# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Default-deny authority profiles for the final agent roster."""

from __future__ import annotations

from app.harness.profile import AgentProfile, LoopBudget


_FORBIDDEN_ACTIONS = frozenset(
    {
        "review.approve",
        "review.reject",
        "refund.execute",
        "replacement.execute",
        "after_sales.reject",
        "case.close",
        "action.execute",
    }
)


# 所属模块：Agent 角色能力 > profiles；函数角色：模块私有业务函数。
# 具体功能：`_budget` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`LoopBudget`。
# 上下游：上游为 本文件的 `final_agent_profiles`；下游为 协作调用 `LoopBudget`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _budget(
    iterations: int,
    tools: int,
    models: int,
    deadline: int,
) -> LoopBudget:
    return LoopBudget(
        max_iterations=iterations,
        max_tool_calls=tools,
        max_model_calls=models,
        max_input_tokens=32_000,
        max_output_tokens=8_000,
        deadline_seconds=deadline,
        stagnation_threshold=2,
        max_output_repairs=2,
    )


# 所属模块：Agent 角色能力 > profiles；函数角色：模块私有业务函数。
# 具体功能：`_profile` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`AgentProfile`、`frozenset`。
# 上下游：上游为 本文件的 `final_agent_profiles`；下游为 协作调用 `AgentProfile`、`frozenset`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _profile(
    agent_id: str,
    role: str,
    *,
    states: set[str],
    scopes: set[str],
    skills: set[str],
    tools: set[str],
    budget: LoopBudget,
    output_schema: str,
    risk_policy: str = "human-review-required-v1",
) -> AgentProfile:
    return AgentProfile(
        agent_id=agent_id,
        role=role,
        version="1.0.0",
        allowed_case_states=frozenset(states),
        allowed_context_scopes=frozenset(scopes),
        allowed_skills=frozenset(skills),
        allowed_tools=frozenset(tools),
        forbidden_actions=_FORBIDDEN_ACTIONS,
        budget=budget,
        output_schema=output_schema,
        risk_policy=risk_policy,
    )


# 所属模块：Agent 角色能力 > profiles；函数角色：模块公开业务函数。
# 具体功能：`final_agent_profiles` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`critic_specs.items`、`key.replace`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_profile`、`_budget`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def final_agent_profiles() -> dict[str, AgentProfile]:
    """Build fresh immutable profiles so runtime mutation cannot leak."""

    profiles = {
        "dispute_intake_officer": _profile(
            "dispute-intake-officer",
            "Dispute Intake Officer",
            states={"SUBMITTED", "INTAKE_PENDING"},
            scopes={"submission", "order_reference", "attachment_metadata"},
            skills={"dispute_admissibility", "claim_extraction"},
            tools={
                "order_reference.read",
                "after_sales_reference.read",
                "logistics_reference.read",
            },
            budget=_budget(3, 5, 2, 20),
            output_schema="DisputeIntakeResult",
        ),
        "evidence_clerk": _profile(
            "evidence-clerk",
            "Evidence Clerk",
            states={"DOSSIER_BUILDING", "EVIDENCE_PENDING", "HEARING"},
            scopes={"case", "submission", "evidence_metadata"},
            skills={"evidence_dossier_build", "timeline_construction"},
            tools={
                "order_facts.read",
                "logistics_facts.read",
                "payment_facts.read",
                "evidence_parser.read",
            },
            budget=_budget(8, 15, 4, 90),
            output_schema="EvidenceDossierResult",
        ),
        "presiding_judge": _profile(
            "ai-presiding-judge",
            "AI Presiding Judge",
            states={"FULL_HEARING", "DRAFT_REVISION"},
            scopes={"frozen_dossier", "party_claims", "rules", "hearing"},
            skills={
                "issue_framing",
                "evidence_gap",
                "evidence_cross_check",
                "rule_application",
                "draft_generation",
            },
            tools={
                "evidence_dossier.read",
                "hearing_state.read",
                "policy.search",
                "rule_version.read",
            },
            budget=_budget(8, 15, 4, 120),
            output_schema="HearingAnalysisResult",
        ),
        "review_copilot": _profile(
            "review-copilot",
            "Review Copilot",
            states={"HUMAN_REVIEW"},
            scopes={"frozen_review_packet"},
            skills={"review_explanation", "difference_summary"},
            tools={
                "review_packet.read",
                "evidence_dossier.read",
                "deliberation_report.read",
                "similar_case.search_summary",
            },
            budget=_budget(3, 5, 2, 30),
            output_schema="ReviewCopilotAnswer",
        ),
        "evaluation_agent": _profile(
            "evaluation-agent",
            "Evaluation Agent",
            states={"CLOSED"},
            scopes={"redacted_closed_case", "agent_runs", "review", "actions"},
            skills={"offline_case_evaluation"},
            tools={"evaluation_snapshot.read", "trace.read_redacted"},
            budget=_budget(6, 10, 3, 180),
            output_schema="EvaluationAnalysisResult",
            risk_policy="offline-only-v1",
        ),
    }
    critic_specs = {
        "evidence_critic": (
            "Evidence Critic",
            {"frozen_dossier", "frozen_hearing"},
            {"evidence_dossier.read", "hearing_record.read"},
        ),
        "rule_critic": (
            "Rule Critic",
            {"frozen_rules", "frozen_hearing"},
            {"policy.search", "rule_version.read"},
        ),
        "risk_critic": (
            "Risk Critic",
            {"frozen_risk", "frozen_hearing"},
            {"case_risk.read", "historical_risk_summary.read"},
        ),
        "remedy_critic": (
            "Remedy Critic",
            {"frozen_remedy", "frozen_hearing"},
            {"remedy_plan.read", "amount_constraint.read"},
        ),
        "fairness_critic": (
            "Fairness Critic",
            {"frozen_fairness", "frozen_hearing"},
            {"similar_case.search", "fairness_policy.read"},
        ),
    }
    for key, (role, scopes, tools) in critic_specs.items():
        profiles[key] = _profile(
            key.replace("_", "-"),
            role,
            states={"DELIBERATION"},
            scopes=scopes,
            skills={key},
            tools=tools,
            budget=_budget(3, 5, 2, 45),
            output_schema="CriticReport",
        )
    return profiles
