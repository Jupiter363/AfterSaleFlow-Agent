from __future__ import annotations

from typing import Any
from weakref import WeakSet

from langchain_core.runnables import Runnable, RunnableConfig
from langgraph.graph import END, START, StateGraph

from app.graph_runtime.topology import ClosedRouter
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.lcel import (
    _ainvoke_vetted_intake_model_runnable,
    _invoke_vetted_intake_model_runnable,
    _is_vetted_intake_model_runnable,
)
from app.graphs.intake.nodes import (
    IntakeCognitionNode,
    apply_dossier_patch,
    authorize_and_load,
    cached_terminal_projection,
    checkpoint_terminal,
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
        patch = _invoke_vetted_intake_model_runnable(
            self._delegate,
            input,
            config=config,
            **kwargs,
        )
        return validate_cognition_patch(input, patch)

    async def ainvoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        patch = await _ainvoke_vetted_intake_model_runnable(
            self._delegate,
            input,
            config=config,
            **kwargs,
        )
        return validate_cognition_patch(input, patch)


_TEST_ONLY_INTAKE_COGNITION_TOKEN = object()


class _TestOnlyIntakeCognition:
    __slots__ = ("_node", "_token", "__weakref__")

    def __init__(self, node: IntakeCognitionNode, *, _token: object) -> None:
        if _token is not _TEST_ONLY_INTAKE_COGNITION_TOKEN or not callable(node):
            raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")
        self._node = node
        self._token = _token


_TEST_ONLY_INTAKE_COGNITION_NODES: WeakSet[_TestOnlyIntakeCognition] = WeakSet()


def _create_test_only_intake_cognition(
    node: IntakeCognitionNode,
) -> _TestOnlyIntakeCognition:
    wrapped = _TestOnlyIntakeCognition(
        node,
        _token=_TEST_ONLY_INTAKE_COGNITION_TOKEN,
    )
    _TEST_ONLY_INTAKE_COGNITION_NODES.add(wrapped)
    return wrapped


def _is_test_only_intake_cognition(value: Any) -> bool:
    return (
        type(value) is _TestOnlyIntakeCognition
        and value in _TEST_ONLY_INTAKE_COGNITION_NODES
        and value._token is _TEST_ONLY_INTAKE_COGNITION_TOKEN
    )


def build_intake_v2_graph(
    *,
    intake_lcel: Runnable | _TestOnlyIntakeCognition | None = None,
) -> StateGraph:
    if intake_lcel is None:
        cognition_node = guard_intake_cognition(unconfigured_intake_lcel)
    elif _is_vetted_intake_model_runnable(intake_lcel):
        cognition_node = _ValidatedIntakeCognitionRunnable(intake_lcel)
    elif _is_test_only_intake_cognition(intake_lcel):
        cognition_node = guard_intake_cognition(intake_lcel._node)
    else:
        raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")
    builder = StateGraph(IntakeGraphStateV2, context_schema=IntakeTurnContext)
    builder.add_node("authorize_and_load", authorize_and_load)
    builder.add_node(
        "import_snapshot_once_or_apply_event",
        import_snapshot_once_or_apply_event,
    )
    builder.add_node("route_turn", route_turn)
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
                # Snapshot-only opening uses the same vetted baseline model
                # path as every participant message; no deterministic English
                # placeholder is allowed at the model-facing boundary.
                "initialize": "intake_lcel",
                "message": "intake_lcel",
                "replay": "cached_terminal_projection",
            }
        ),
    )
    for node in (
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
    intake_lcel: Runnable | _TestOnlyIntakeCognition | None = None,
    checkpointer: Any = None,
):
    return build_intake_v2_graph(intake_lcel=intake_lcel).compile(checkpointer=checkpointer)
