from __future__ import annotations

from typing import Any

from langgraph.graph import END, START, StateGraph

from app.graphs.outcome.nodes import compose_advisory, project_proposal, validate_scope_packet
from app.graphs.outcome.state import OutcomeReviewGraphStateV1, OutcomeReviewInvocation


def build_outcome_review_v1_graph() -> StateGraph:
    builder = StateGraph(
        OutcomeReviewGraphStateV1,
        context_schema=OutcomeReviewInvocation,
    )
    builder.add_node("validate_scope_packet", validate_scope_packet)
    builder.add_node("compose_advisory", compose_advisory)
    builder.add_node("project_proposal", project_proposal)
    builder.add_edge(START, "validate_scope_packet")
    builder.add_edge("validate_scope_packet", "compose_advisory")
    builder.add_edge("compose_advisory", "project_proposal")
    builder.add_edge("project_proposal", END)
    return builder


def compile_outcome_review_v1_graph(*, checkpointer: Any = None):
    """Compile the private candidate without registering or activating it."""

    return build_outcome_review_v1_graph().compile(checkpointer=checkpointer)


__all__ = ["build_outcome_review_v1_graph", "compile_outcome_review_v1_graph"]
