from __future__ import annotations

from typing import Any

from langchain_core.runnables import Runnable, RunnableConfig, RunnableLambda
from langgraph.graph import END, START, StateGraph

from app.graph_runtime.topology import ClosedRouter
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.nodes import (
    IntakeCognitionNode,
    apply_dossier_patch,
    authorize_and_load,
    cached_terminal_projection,
    checkpoint_terminal,
    deterministic_seed,
    guard_intake_cognition,
    import_snapshot_once_or_apply_event,
    project_intake_proposal,
    route_turn,
    unconfigured_intake_lcel,
    validate_readiness,
)
from app.graphs.intake.state import IntakeGraphStateV2, IntakeTurnContext
from app.graphs.intake.validators import validate_cognition_patch


class _ValidatedIntakeCognitionRunnable(Runnable[IntakeGraphStateV2, dict[str, Any]]):
    def __init__(self, delegate: Runnable) -> None:
        self._delegate = delegate

    def invoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        patch = self._delegate.invoke(input, config=config, **kwargs)
        return validate_cognition_patch(input, patch)

    async def ainvoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        patch = await self._delegate.ainvoke(input, config=config, **kwargs)
        return validate_cognition_patch(input, patch)


def _reject_legacy_runnable_lambda(runnable: Runnable) -> None:
    if any(isinstance(node.data, RunnableLambda) for node in runnable.get_graph().nodes.values()):
        raise IntakeGraphContractError("INTAKE_LCEL_LEGACY_WRAPPER_FORBIDDEN")


def build_intake_v2_graph(
    *,
    intake_lcel: IntakeCognitionNode | Runnable = unconfigured_intake_lcel,
) -> StateGraph:
    if isinstance(intake_lcel, Runnable):
        _reject_legacy_runnable_lambda(intake_lcel)
        cognition_node = _ValidatedIntakeCognitionRunnable(intake_lcel)
    else:
        cognition_node = guard_intake_cognition(intake_lcel)
    builder = StateGraph(IntakeGraphStateV2, context_schema=IntakeTurnContext)
    builder.add_node("authorize_and_load", authorize_and_load)
    builder.add_node(
        "import_snapshot_once_or_apply_event",
        import_snapshot_once_or_apply_event,
    )
    builder.add_node("route_turn", route_turn)
    builder.add_node("deterministic_seed", deterministic_seed)
    builder.add_node("intake_lcel", cognition_node)
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
    intake_lcel: IntakeCognitionNode | Runnable = unconfigured_intake_lcel,
    checkpointer: Any = None,
):
    return build_intake_v2_graph(intake_lcel=intake_lcel).compile(checkpointer=checkpointer)
