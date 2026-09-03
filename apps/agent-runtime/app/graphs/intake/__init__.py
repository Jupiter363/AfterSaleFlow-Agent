"""Side-effect-free public interface for the governed Intake graph package."""

from __future__ import annotations

from importlib import import_module
from typing import TYPE_CHECKING, Any, Final

if TYPE_CHECKING:
    from app.graph_runtime.intake_binding import (
        build_governed_intake_runtime as build_governed_intake_runtime,
    )
    from app.graphs.intake.graph import (
        build_intake_v2_graph as build_intake_v2_graph,
        compile_intake_v2_graph as compile_intake_v2_graph,
    )
    from app.graphs.intake.lcel import (
        INTAKE_SYSTEM_PROMPT as INTAKE_SYSTEM_PROMPT,
        BuiltIntakeModelNode as BuiltIntakeModelNode,
        build_intake_model_node as build_intake_model_node,
    )
    from app.graphs.intake.runtime import (
        IntakeRuntimeBundle as GovernedIntakeRuntime,
        IntakeRuntimeBundle as IntakeRuntimeBundle,
        build_intake_initial_state as build_intake_initial_state,
        build_intake_runtime_bundle as build_intake_runtime_bundle,
        extract_intake_terminal_proposal as extract_intake_terminal_proposal,
    )
    from app.graphs.intake.state import (
        IntakeCommandBindings as IntakeCommandBindings,
        IntakeGraphBindings as IntakeGraphBindings,
        IntakeGraphStateV2 as IntakeGraphStateV2,
        IntakePrivateBindings as IntakePrivateBindings,
        IntakeTurnContext as IntakeTurnContext,
        new_intake_graph_state as new_intake_graph_state,
    )


_LAZY_EXPORTS: Final[dict[str, tuple[str, str]]] = {
    "IntakeCommandBindings": ("app.graphs.intake.state", "IntakeCommandBindings"),
    "IntakeGraphBindings": ("app.graphs.intake.state", "IntakeGraphBindings"),
    "IntakeGraphStateV2": ("app.graphs.intake.state", "IntakeGraphStateV2"),
    "IntakePrivateBindings": ("app.graphs.intake.state", "IntakePrivateBindings"),
    "IntakeRuntimeBundle": ("app.graphs.intake.runtime", "IntakeRuntimeBundle"),
    "GovernedIntakeRuntime": ("app.graphs.intake.runtime", "IntakeRuntimeBundle"),
    "IntakeTurnContext": ("app.graphs.intake.state", "IntakeTurnContext"),
    "BuiltIntakeModelNode": ("app.graphs.intake.lcel", "BuiltIntakeModelNode"),
    "INTAKE_SYSTEM_PROMPT": ("app.graphs.intake.lcel", "INTAKE_SYSTEM_PROMPT"),
    "build_governed_intake_runtime": (
        "app.graph_runtime.intake_binding",
        "build_governed_intake_runtime",
    ),
    "build_intake_initial_state": (
        "app.graphs.intake.runtime",
        "build_intake_initial_state",
    ),
    "build_intake_model_node": ("app.graphs.intake.lcel", "build_intake_model_node"),
    "build_intake_runtime_bundle": (
        "app.graphs.intake.runtime",
        "build_intake_runtime_bundle",
    ),
    "build_intake_v2_graph": ("app.graphs.intake.graph", "build_intake_v2_graph"),
    "compile_intake_v2_graph": (
        "app.graphs.intake.graph",
        "compile_intake_v2_graph",
    ),
    "extract_intake_terminal_proposal": (
        "app.graphs.intake.runtime",
        "extract_intake_terminal_proposal",
    ),
    "new_intake_graph_state": ("app.graphs.intake.state", "new_intake_graph_state"),
}

__all__ = [
    "IntakeCommandBindings",
    "IntakeGraphBindings",
    "IntakeGraphStateV2",
    "IntakePrivateBindings",
    "IntakeRuntimeBundle",
    "GovernedIntakeRuntime",
    "IntakeTurnContext",
    "BuiltIntakeModelNode",
    "INTAKE_SYSTEM_PROMPT",
    "build_governed_intake_runtime",
    "build_intake_initial_state",
    "build_intake_model_node",
    "build_intake_runtime_bundle",
    "build_intake_v2_graph",
    "compile_intake_v2_graph",
    "extract_intake_terminal_proposal",
    "new_intake_graph_state",
]


def __getattr__(name: str) -> Any:
    """Resolve a public export without initializing unrelated Intake submodules."""

    target = _LAZY_EXPORTS.get(name)
    if target is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    module_name, attribute_name = target
    value = getattr(import_module(module_name), attribute_name)
    globals()[name] = value
    return value


def __dir__() -> list[str]:
    """Include unresolved lazy exports in normal module introspection."""

    return sorted(set(globals()) | set(__all__))
