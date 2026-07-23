from __future__ import annotations

from functools import partial
from types import MappingProxyType
from typing import Any, Mapping

from langgraph.graph import END, START, StateGraph

from app.graph_runtime.topology import ClosedRouter
from app.graphs.hearing.contracts import (
    HEARING_GRAPH_IDENTITIES,
    HearingGraphIdentity,
    HearingOperation,
)
from app.graphs.hearing.nodes import (
    assess_evidence_item,
    complete_evidence_synthesis,
    dispatch_evidence_wave,
    execute_operation,
    keyed_evidence_fan_in,
    plan_evidence_wave,
    plan_evidence_work,
    project_proposal,
    validate_and_route,
)
from app.graphs.hearing.state import HearingGraphInvocation, HearingGraphStateV1


def _build_family(identity: HearingGraphIdentity) -> StateGraph:
    builder = StateGraph(HearingGraphStateV1, context_schema=HearingGraphInvocation)
    builder.add_node(
        "validate_and_route",
        partial(validate_and_route, identity=identity),
    )
    for operation in identity.operations:
        if operation is HearingOperation.EVIDENCE_SYNTHESIS:
            continue
        builder.add_node(
            operation.value,
            partial(execute_operation, expected_operation=operation),
        )
    builder.add_node("project_proposal", project_proposal)

    if HearingOperation.EVIDENCE_SYNTHESIS in identity.operations:
        builder.add_node("plan_evidence_work", plan_evidence_work)
        builder.add_node("plan_evidence_wave", plan_evidence_wave)
        builder.add_node("assess_evidence_item", assess_evidence_item)
        builder.add_node("keyed_evidence_fan_in", keyed_evidence_fan_in)
        builder.add_node("complete_evidence_synthesis", complete_evidence_synthesis)

    builder.add_edge(START, "validate_and_route")
    builder.add_conditional_edges(
        "validate_and_route",
        ClosedRouter(
            {
                operation.value: (
                    "plan_evidence_work"
                    if operation is HearingOperation.EVIDENCE_SYNTHESIS
                    else operation.value
                )
                for operation in identity.operations
            }
        ),
    )
    for operation in identity.operations:
        if operation is HearingOperation.EVIDENCE_SYNTHESIS:
            continue
        builder.add_edge(operation.value, "project_proposal")
    if HearingOperation.EVIDENCE_SYNTHESIS in identity.operations:
        builder.add_edge("plan_evidence_work", "plan_evidence_wave")
        builder.add_conditional_edges("plan_evidence_wave", dispatch_evidence_wave)
        builder.add_edge("assess_evidence_item", "keyed_evidence_fan_in")
        builder.add_edge("keyed_evidence_fan_in", "plan_evidence_wave")
        builder.add_edge("complete_evidence_synthesis", "project_proposal")
    builder.add_edge("project_proposal", END)
    return builder


def build_hearing_intake_v1_graph() -> StateGraph:
    return _build_family(HEARING_GRAPH_IDENTITIES["hearing.intake.v1"])


def build_hearing_evidence_v1_graph() -> StateGraph:
    return _build_family(HEARING_GRAPH_IDENTITIES["hearing.evidence.v1"])


def build_hearing_judge_v1_graph() -> StateGraph:
    return _build_family(HEARING_GRAPH_IDENTITIES["hearing.judge.v1"])


def build_hearing_jury_v1_graph() -> StateGraph:
    return _build_family(HEARING_GRAPH_IDENTITIES["hearing.jury.v1"])


def compile_hearing_graph_candidates(*, checkpointer: Any = None) -> Mapping[str, Any]:
    """Compile candidates without registering them in the shared runtime registry."""

    return MappingProxyType(
        {
            identity.identity: _build_family(identity).compile(checkpointer=checkpointer)
            for identity in HEARING_GRAPH_IDENTITIES.values()
        }
    )
