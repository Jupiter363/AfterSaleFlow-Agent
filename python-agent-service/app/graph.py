from __future__ import annotations

import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.llm import StructuredLlmClient
from app.prompts import PromptRepository
from app.schemas import (
    AdjudicationDraftOutput,
    EvidenceCrossCheckOutput,
    EvidenceGapOutput,
    IssueFramingOutput,
    PartyLiaisonOutput,
    RuleApplicationOutput,
)
from app.tracing import AgentTraceContext, AgentTracer, redacted_trace_input


class HearingGraphState(TypedDict):
    request: dict[str, Any]
    trace_context: AgentTraceContext
    executed_nodes: Annotated[list[str], operator.add]
    issue_framing: NotRequired[dict[str, Any]]
    evidence_gap: NotRequired[dict[str, Any]]
    party_liaison: NotRequired[dict[str, Any]]
    evidence_cross_check: NotRequired[dict[str, Any]]
    rule_application: NotRequired[dict[str, Any]]
    adjudication_draft: NotRequired[dict[str, Any]]


OUTPUT_TYPES = {
    "issue_framing_node": IssueFramingOutput,
    "evidence_gap_request_node": EvidenceGapOutput,
    "party_liaison_node": PartyLiaisonOutput,
    "evidence_cross_check_node": EvidenceCrossCheckOutput,
    "rule_application_node": RuleApplicationOutput,
    "adjudication_draft_node": AdjudicationDraftOutput,
}

STATE_KEYS = {
    "issue_framing_node": "issue_framing",
    "evidence_gap_request_node": "evidence_gap",
    "party_liaison_node": "party_liaison",
    "evidence_cross_check_node": "evidence_cross_check",
    "rule_application_node": "rule_application",
    "adjudication_draft_node": "adjudication_draft",
}


def build_hearing_graph(
    llm: StructuredLlmClient,
    prompts: PromptRepository,
    tracer: AgentTracer,
):
    def node(node_name: str):
        output_type = OUTPUT_TYPES[node_name]
        state_key = STATE_KEYS[node_name]

        def execute(state: HearingGraphState) -> dict[str, Any]:
            case_data = {
                "request": state["request"],
                "prior_outputs": {
                    key: state[key]
                    for key in STATE_KEYS.values()
                    if key in state
                },
            }
            system_prompt, user_prompt = prompts.render(
                node_name, case_data, output_type.model_json_schema()
            )
            generation = llm.generate(
                node_name=node_name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                output_type=output_type,
            )
            output = generation.value.model_dump(mode="json")
            tracer.generation(
                state["trace_context"],
                node_name,
                generation.model,
                redacted_trace_input(user_prompt),
                output,
                generation.latency_ms,
                generation.token_usage,
            )
            return {state_key: output, "executed_nodes": [node_name]}

        execute.__name__ = node_name
        return execute

    builder = StateGraph(HearingGraphState)
    for node_name in OUTPUT_TYPES:
        builder.add_node(node_name, node(node_name))
    builder.add_edge(START, "issue_framing_node")
    builder.add_edge("issue_framing_node", "evidence_gap_request_node")
    builder.add_conditional_edges(
        "evidence_gap_request_node",
        _after_evidence_gap,
        {
            "request_evidence": "party_liaison_node",
            "cross_check": "evidence_cross_check_node",
        },
    )
    builder.add_edge("party_liaison_node", "evidence_cross_check_node")
    builder.add_edge("evidence_cross_check_node", "rule_application_node")
    builder.add_edge("rule_application_node", "adjudication_draft_node")
    builder.add_edge("adjudication_draft_node", END)
    return builder.compile()


def _after_evidence_gap(state: HearingGraphState) -> str:
    hearing_context = state["request"].get("hearing_context") or {}
    if (
        hearing_context.get("must_produce_final_plan")
        or not hearing_context.get("allow_supplemental_request", True)
    ):
        return "cross_check"
    if state["evidence_gap"]["requires_supplemental_evidence"]:
        return "request_evidence"
    return "cross_check"
