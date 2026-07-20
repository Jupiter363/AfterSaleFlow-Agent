from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from app.graph_runtime.topology import ClosedRouter
from app.graphs.intake.nodes import (
    IntakeCognitionNode,
    apply_dossier_patch,
    authorize_and_load,
    cached_terminal_projection,
    checkpoint_terminal,
    deterministic_message_fallback,
    deterministic_seed,
    import_snapshot_once_or_apply_event,
    project_intake_proposal,
    route_turn,
    validate_readiness,
)
from app.graphs.intake.state import IntakeGraphStateV2, IntakeTurnContext


def build_intake_v2_graph(
    *,
    intake_lcel: IntakeCognitionNode = deterministic_message_fallback,
) -> StateGraph:
    builder = StateGraph(IntakeGraphStateV2, context_schema=IntakeTurnContext)
    builder.add_node("authorize_and_load", authorize_and_load)
    builder.add_node(
        "import_snapshot_once_or_apply_event",
        import_snapshot_once_or_apply_event,
    )
    builder.add_node("route_turn", route_turn)
    builder.add_node("deterministic_seed", deterministic_seed)
    builder.add_node("intake_lcel", intake_lcel)
    builder.add_node("cached_terminal_projection", cached_terminal_projection)
    builder.add_node("apply_dossier_patch", apply_dossier_patch)
    builder.add_node("validate_readiness", validate_readiness)
    builder.add_node("project_intake_proposal", project_intake_proposal)
    builder.add_node("checkpoint_terminal", checkpoint_terminal)

    builder.add_edge(START, "authorize_and_load")
    builder.add_edge("authorize_and_load", "import_snapshot_once_or_apply_event")
    builder.add_edge("import_snapshot_once_or_apply_event", "route_turn")
    builder.add_conditional_edges(
        "route_turn",
        ClosedRouter(
            {
                "initialize": "deterministic_seed",
                "message": "intake_lcel",
                "replay": "cached_terminal_projection",
            }
        ),
    )
    for node in (
        "deterministic_seed",
        "intake_lcel",
        "cached_terminal_projection",
    ):
        builder.add_edge(node, "apply_dossier_patch")
    builder.add_edge("apply_dossier_patch", "validate_readiness")
    builder.add_edge("validate_readiness", "project_intake_proposal")
    builder.add_edge("project_intake_proposal", "checkpoint_terminal")
    builder.add_edge("checkpoint_terminal", END)
    return builder


def compile_intake_v2_graph(
    *,
    intake_lcel: IntakeCognitionNode = deterministic_message_fallback,
):
    return build_intake_v2_graph(intake_lcel=intake_lcel).compile()
