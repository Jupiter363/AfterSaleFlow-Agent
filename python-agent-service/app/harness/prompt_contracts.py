# 文件作用：集中声明每个 LLM 节点能接收哪些上下文段、优先级、必填性、信任来源及是否允许进入 Prompt。

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ContextSectionSpec:
    """一个上下文段的静态治理规则；frozen=True 表示运行时不能临时改权限。"""

    name: str
    priority: int
    required: bool = False
    trust_level: str = "untrusted"
    prompt_included: bool = True


@dataclass(frozen=True)
class AgentContextContract:
    """一个 node_name 的完整输入白名单及配置版本标识。"""

    node_name: str
    configuration_profile_key: str
    sections: tuple[ContextSectionSpec, ...]
    configuration_source: str = "code"


_COMMON_DISPLAY_ONLY = (
    ContextSectionSpec(
        "long_term_memory_preview",
        priority=20,
        required=False,
        trust_level="reserved_memory_slot",
        prompt_included=False,
    ),
)


_CONTRACTS: dict[str, AgentContextContract] = {
    "intake_turn_case_detail": AgentContextContract(
        node_name="intake_turn_case_detail",
        configuration_profile_key="DISPUTE_INTAKE_CONTEXT_PACK_V2",
        sections=(
            ContextSectionSpec("current_user_message", 100, False, "room_message"),
            ContextSectionSpec("previous_case_detail", 98, False, "dossier_snapshot"),
            ContextSectionSpec(
                "recent_dialogue_messages", 96, False, "room_message_history"
            ),
            ContextSectionSpec("initial_case_facts", 94, False, "java_filtered"),
            ContextSectionSpec("case_identity", 92, True, "java_filtered"),
            *_COMMON_DISPLAY_ONLY,
        ),
    ),
    "evidence_turn": AgentContextContract(
        node_name="evidence_turn",
        configuration_profile_key="EVIDENCE_CLERK_CONTEXT_PACK_V2",
        sections=(
            ContextSectionSpec("current_turn", 100, True, "harness_assembled"),
            ContextSectionSpec("case_identity", 98, True, "harness_assembled"),
            ContextSectionSpec(
                "claim_and_response_state",
                97,
                False,
                "intake_dossier_derived",
            ),
            ContextSectionSpec(
                "canonical_case_dossier",
                96,
                False,
                "harness_assembled",
            ),
            ContextSectionSpec(
                "fact_targets",
                95,
                True,
                "intake_dossier_allowlist",
            ),
            ContextSectionSpec(
                "evidence_matrix_snapshot",
                92,
                False,
                "session_scoped_snapshot",
            ),
            ContextSectionSpec(
                "evidence_gap_plan",
                91,
                False,
                "harness_derived",
            ),
            ContextSectionSpec(
                "party_visible_evidence_catalog",
                90,
                False,
                "java_authorized_harness_bounded",
            ),
            ContextSectionSpec(
                "private_conversation_window",
                86,
                False,
                "current_actor_session_only",
            ),
            ContextSectionSpec(
                "multimodal_observation",
                84,
                False,
                "harness_asset_loader",
            ),
            ContextSectionSpec(
                "internal_audit_context",
                82,
                False,
                "system_audit_only",
            ),
            ContextSectionSpec("room_deadline", 75, False, "harness_assembled"),
            *_COMMON_DISPLAY_ONLY,
        ),
    ),
}


# 所属模块：Agent Harness > 节点输入合同 > 合同查找入口。
# 具体功能：`context_contract` 按 LangGraph/工作流 node_name 精确返回不可变合同；未知节点抛出带节点名的 KeyError，不提供“全量上下文”默认值。
# 上下游：上游是 `build_context_pack`；下游是该节点的段白名单、必填检查、Token 优先级和 display-only 决策。
# 系统意义：新节点若未登记合同就不能调用模型，避免开发者漏配时把整个请求对象作为便利回退而泄露跨角色数据。
def context_contract(node_name: str) -> AgentContextContract:
    try:
        return _CONTRACTS[node_name]
    except KeyError as exception:
        raise KeyError(f"unknown context contract: {node_name}") from exception
