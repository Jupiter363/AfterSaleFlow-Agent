from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, cast

from langgraph.checkpoint.base import BaseCheckpointSaver

from app.graph_runtime.state import VersionPinsState
from app.graphs.intake.contracts import IntakeTurnProposal
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.graph import compile_intake_v2_graph
from app.graphs.intake.lcel import BuiltIntakeModelNode, build_intake_model_node
from app.graphs.intake.state import (
    IntakeGraphBindings,
    IntakeGraphStateV2,
    new_intake_graph_state,
)
from app.graphs.intake.validators import (
    validate_proposal_binding,
    validate_state,
    validate_terminal_proposal,
)
from app.harness.invocation_context import AgentInvocationContext
from app.model_runtime.profiles import ModelInvocationPolicy, ModelProfile
from app.model_runtime.transports import ModelTransport


@dataclass(frozen=True, slots=True)
class IntakeRuntimeBundle:
    """R1 assembly hooks without registration, mode selection, or a formal sink."""

    graph: Any
    model_node: BuiltIntakeModelNode

    @staticmethod
    def initial_state(
        *,
        bindings: IntakeGraphBindings,
        version_pins: VersionPinsState,
    ) -> IntakeGraphStateV2:
        return build_intake_initial_state(
            bindings=bindings,
            version_pins=version_pins,
        )

    @staticmethod
    def terminal_proposal(state: Mapping[str, Any]) -> IntakeTurnProposal:
        return extract_intake_terminal_proposal(state)


def build_intake_runtime_bundle(
    *,
    transport: ModelTransport,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
    checkpointer: BaseCheckpointSaver[Any],
    agent_context: AgentInvocationContext,
    trusted_system_prompt: str,
) -> IntakeRuntimeBundle:
    if checkpointer is None:
        raise IntakeGraphContractError("INTAKE_RUNTIME_CHECKPOINTER_REQUIRED")
    if not isinstance(checkpointer, BaseCheckpointSaver):
        raise IntakeGraphContractError("INTAKE_RUNTIME_CHECKPOINTER_INVALID")
    model_node = build_intake_model_node(
        transport=transport,
        profile=profile,
        policy=policy,
        agent_context=agent_context,
        trusted_system_prompt=trusted_system_prompt,
    )
    graph = compile_intake_v2_graph(
        intake_lcel=model_node.runnable,
        checkpointer=checkpointer,
    )
    if graph.checkpointer is not checkpointer:
        raise IntakeGraphContractError("INTAKE_RUNTIME_CHECKPOINTER_BINDING_INVALID")
    return IntakeRuntimeBundle(graph=graph, model_node=model_node)


def build_intake_initial_state(
    *,
    bindings: IntakeGraphBindings,
    version_pins: VersionPinsState,
) -> IntakeGraphStateV2:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    validate_state(state)
    return state


def extract_intake_terminal_proposal(
    state: Mapping[str, Any],
) -> IntakeTurnProposal:
    if not isinstance(state, dict):
        raise IntakeGraphContractError("INTAKE_RUNTIME_STATE_INVALID")
    typed_state = cast(IntakeGraphStateV2, state)
    validate_state(typed_state)
    value = state.get("result_json")
    if not isinstance(value, dict):
        raise IntakeGraphContractError("INTAKE_RUNTIME_PROPOSAL_MISSING")
    validate_terminal_proposal(value)
    validate_proposal_binding(typed_state, value)
    try:
        return IntakeTurnProposal.model_validate(value)
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_RUNTIME_PROPOSAL_INVALID") from error


__all__ = [
    "IntakeRuntimeBundle",
    "build_intake_initial_state",
    "build_intake_runtime_bundle",
    "extract_intake_terminal_proposal",
]
