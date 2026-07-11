from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ContextSectionSpec:
    name: str
    priority: int
    required: bool = False
    trust_level: str = "untrusted"
    prompt_included: bool = True


@dataclass(frozen=True)
class AgentContextContract:
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
        configuration_profile_key="DISPUTE_INTAKE_CONTEXT_PACK_V1",
        sections=(
            ContextSectionSpec("current_turn", 100, True, "java_filtered"),
            ContextSectionSpec(
                "initiator_statement_transcript",
                98,
                False,
                "participant_raw_untrusted",
            ),
            ContextSectionSpec("intake_initial_form", 96, False, "java_filtered"),
            ContextSectionSpec("case_identity", 95, False, "java_filtered"),
            ContextSectionSpec("latest_canvas_snapshot", 90, False, "java_filtered"),
            ContextSectionSpec("short_term_memory", 85, False, "session_scoped"),
            ContextSectionSpec("compressed_summary", 80, False, "session_scoped"),
            ContextSectionSpec("room_deadline", 75, False, "java_filtered"),
            ContextSectionSpec("tool_results", 70, False, "tool_result"),
            ContextSectionSpec("frontend_display_hints", 40, False, "ui_hint"),
            *_COMMON_DISPLAY_ONLY,
        ),
    ),
    "evidence_turn": AgentContextContract(
        node_name="evidence_turn",
        configuration_profile_key="EVIDENCE_CLERK_CONTEXT_PACK_V1",
        sections=(
            ContextSectionSpec("current_turn", 100, True, "harness_assembled"),
            ContextSectionSpec("case_identity", 95, False, "harness_assembled"),
            ContextSectionSpec(
                "canonical_case_dossier",
                95,
                False,
                "harness_assembled",
            ),
            ContextSectionSpec("latest_canvas_snapshot", 90, False, "java_filtered"),
            ContextSectionSpec("actor_private_memory", 85, False, "session_scoped"),
            ContextSectionSpec("compressed_summary", 80, False, "session_scoped"),
            ContextSectionSpec(
                "actor_visible_evidence",
                80,
                False,
                "harness_assembled",
            ),
            ContextSectionSpec("evidence_gap_plan", 78, False, "derived_skill"),
            ContextSectionSpec("room_deadline", 75, False, "harness_assembled"),
            ContextSectionSpec("tool_results", 70, False, "tool_result"),
            ContextSectionSpec("frontend_display_hints", 40, False, "ui_hint"),
            *_COMMON_DISPLAY_ONLY,
        ),
    ),
    "hearing_round_turn": AgentContextContract(
        node_name="hearing_round_turn",
        configuration_profile_key="PRESIDING_JUDGE_ROUND_CONTEXT_PACK_V1",
        sections=(
            ContextSectionSpec("current_turn", 100, True, "java_filtered"),
            ContextSectionSpec("case_identity", 95, True, "java_filtered"),
            ContextSectionSpec("canonical_case_dossier", 95, True, "java_filtered"),
            ContextSectionSpec("hearing_round_submissions", 90, False, "java_filtered"),
            ContextSectionSpec("prior_judge_messages", 85, False, "session_scoped"),
            ContextSectionSpec("compressed_summary", 80, False, "session_scoped"),
            ContextSectionSpec("actor_visible_evidence", 78, False, "java_filtered"),
            ContextSectionSpec("jury_a2a_notes", 77, False, "system_audit_only"),
            ContextSectionSpec("round_control_policy", 75, True, "java_filtered"),
            ContextSectionSpec("execution_tool_intentions", 68, False, "java_tool_catalog"),
            ContextSectionSpec("tool_results", 70, False, "tool_result"),
            *_COMMON_DISPLAY_ONLY,
        ),
    ),
}


def context_contract(node_name: str) -> AgentContextContract:
    try:
        return _CONTRACTS[node_name]
    except KeyError as exception:
        raise KeyError(f"unknown context contract: {node_name}") from exception
