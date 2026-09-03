from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from langchain_core.runnables import Runnable, RunnableLambda
from langgraph.graph import END, START, StateGraph
from langgraph.utils.runnable import RunnableCallable

from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graphs.evidence.contracts import (
    EvidenceGraphContext,
    EvidenceGraphContractError,
    JsonObject,
)
from app.graphs.evidence.nodes import (
    AssessEvidenceItemNode,
    authorize_registration_and_manifest,
    build_matrix_and_review_proposal,
    checkpoint_terminal,
    dispatch_wave,
    keyed_fan_in,
    plan_next_deterministic_wave,
    project_evidence_batch_proposal,
    require_complete_valid_coverage,
    route_after_fan_in,
    validate_item_assessment,
)
from app.graphs.evidence.state import EvidenceGraphStateV2


def build_evidence_v2_graph(
    *,
    item_assessor: Runnable[JsonObject, Mapping[str, Any]] | None = None,
    bulkhead: PostgresGraphFanoutBulkhead | None = None,
    graph_fence: GraphFenceContext | None = None,
) -> StateGraph:
    if not isinstance(bulkhead, PostgresGraphFanoutBulkhead):
        raise EvidenceGraphContractError("EVIDENCE_DURABLE_BULKHEAD_REQUIRED")
    if not isinstance(graph_fence, GraphFenceContext):
        raise EvidenceGraphContractError("EVIDENCE_GRAPH_LEASE_FENCE_REQUIRED")
    assessor = item_assessor or RunnableLambda(_unconfigured_item_assessor)
    builder = StateGraph(EvidenceGraphStateV2, context_schema=EvidenceGraphContext)
    builder.add_node(
        "authorize_registration_and_manifest",
        authorize_registration_and_manifest,
    )
    builder.add_node("plan_next_deterministic_wave", plan_next_deterministic_wave)
    assessment_node = AssessEvidenceItemNode(
        assessor,
        bulkhead=bulkhead,
        graph_fence=graph_fence,
    )
    builder.add_node(
        "assess_evidence_item",
        RunnableCallable(
            assessment_node,
            afunc=assessment_node.ainvoke,
            name="assess_evidence_item",
        ),
    )
    builder.add_node("validate_item_assessment", validate_item_assessment)
    builder.add_node("keyed_fan_in", keyed_fan_in)
    builder.add_node(
        "require_complete_valid_coverage",
        require_complete_valid_coverage,
    )
    builder.add_node(
        "build_matrix_and_review_proposal",
        build_matrix_and_review_proposal,
    )
    builder.add_node(
        "project_evidence_batch_proposal",
        project_evidence_batch_proposal,
    )
    builder.add_node("checkpoint_terminal", checkpoint_terminal)

    builder.add_edge(START, "authorize_registration_and_manifest")
    builder.add_edge(
        "authorize_registration_and_manifest",
        "plan_next_deterministic_wave",
    )
    builder.add_conditional_edges("plan_next_deterministic_wave", dispatch_wave)
    builder.add_edge("assess_evidence_item", "validate_item_assessment")
    builder.add_edge("validate_item_assessment", "keyed_fan_in")
    builder.add_conditional_edges("keyed_fan_in", route_after_fan_in)
    builder.add_edge(
        "require_complete_valid_coverage",
        "build_matrix_and_review_proposal",
    )
    builder.add_edge(
        "build_matrix_and_review_proposal",
        "project_evidence_batch_proposal",
    )
    builder.add_edge("project_evidence_batch_proposal", "checkpoint_terminal")
    builder.add_edge("checkpoint_terminal", END)
    return builder


def compile_evidence_v2_graph(
    *,
    item_assessor: Runnable[JsonObject, Mapping[str, Any]] | None = None,
    checkpointer: Any = None,
    bulkhead: PostgresGraphFanoutBulkhead | None = None,
    graph_fence: GraphFenceContext | None = None,
):
    return build_evidence_v2_graph(
        item_assessor=item_assessor,
        bulkhead=bulkhead,
        graph_fence=graph_fence,
    ).compile(checkpointer=checkpointer)


def _unconfigured_item_assessor(_: JsonObject) -> Mapping[str, Any]:
    raise EvidenceGraphContractError("EVIDENCE_ITEM_ASSESSOR_NOT_CONFIGURED")
