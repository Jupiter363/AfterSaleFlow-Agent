from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.contracts.v1.codec import canonical_sha256_omitting
from app.contracts.v1.models import RoomGraphResult
from app.graph_runtime.result import ResultBindings, project_room_graph_result
from app.graph_runtime.topology import (
    ClosedRouter,
    GraphRouteError,
    bounded_sends,
    build_shadow_kernel_graph,
)


ROOT = Path(__file__).resolve().parents[4]
RESULT_FIXTURE = (
    ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-result-valid.json"
)
RESULT_VECTOR = (
    ROOT
    / "contracts/agent-platform/v1/fixtures/canonical-hash/room-graph-result-self-hash.json"
)


def bindings() -> ResultBindings:
    result = RoomGraphResult.model_validate(
        json.loads(RESULT_FIXTURE.read_text(encoding="utf-8"))["instance"]
    )
    return ResultBindings(
        command_id=result.command_id,
        logical_run_id=result.logical_run_id,
        attempt_id=result.attempt_id,
        graph_key=result.graph_key,
        graph_version=result.graph_version,
        checkpoint_id=result.checkpoint_id,
        cognitive_revision=result.cognitive_revision,
        public_event_proposals=result.public_event_proposals,
        artifact_operations=result.artifact_operations,
        usage=result.usage,
        execution_metadata=result.execution_metadata,
    )


@pytest.mark.parametrize(
    ("draft", "detail"),
    [
        ({"status": "COMPLETED"}, None),
        (
            {
                "status": "NEEDS_INPUT",
                "needs_input": {
                    "reason_code": "PARTY_RESPONSE_REQUIRED",
                    "required_actor_scopes": ["USER"],
                },
            },
            "needs_input",
        ),
        (
            {
                "status": "NEEDS_REVIEW",
                "needs_review": {"reason_code": "HIGH_RISK", "risk_level": "HIGH"},
            },
            "needs_review",
        ),
        (
            {"status": "FAILED", "error": {"code": "POLICY_REJECTED", "retryable": False}},
            "error",
        ),
    ],
)
def test_terminal_projector_emits_exact_four_value_contract(draft, detail: str | None) -> None:
    result = project_room_graph_result(draft, bindings())
    encoded = result.model_dump(mode="json", exclude_none=True)

    assert result.status == draft["status"]
    assert canonical_sha256_omitting(result, "output_hash") == result.output_hash
    for candidate in ("needs_input", "needs_review", "error"):
        assert (candidate in encoded) is (candidate == detail)


def test_completed_projector_matches_cross_language_self_hash_vector() -> None:
    vector = json.loads(RESULT_VECTOR.read_text(encoding="utf-8"))

    result = project_room_graph_result({"status": "COMPLETED"}, bindings())

    assert result.output_hash == vector["sha256"]


def test_terminal_draft_rejects_sibling_detail_and_unknown_status() -> None:
    with pytest.raises(ValidationError):
        project_room_graph_result(
            {
                "status": "NEEDS_INPUT",
                "needs_input": {
                    "reason_code": "PARTY_RESPONSE_REQUIRED",
                    "required_actor_scopes": ["USER"],
                },
                "error": {"code": "FORGED", "retryable": False},
            },
            bindings(),
        )
    with pytest.raises(ValidationError):
        project_room_graph_result({"status": "WAITING"}, bindings())


def test_closed_router_is_exhaustive_and_unknown_values_fail_closed() -> None:
    routes = {"continue": "execute_graph", "finish": "project_result"}
    router = ClosedRouter(routes)
    routes["forged"] = "project_result"

    assert router({"route": "continue"}) == "execute_graph"
    assert router({"route": "finish"}) == "project_result"
    with pytest.raises(GraphRouteError, match="unknown or missing"):
        router({"route": "model_invented"})
    with pytest.raises(GraphRouteError, match="unknown or missing"):
        router({})
    with pytest.raises(GraphRouteError, match="unknown or missing"):
        router({"route": "forged"})


def test_send_fanout_is_bounded_sorted_and_detached() -> None:
    work = {"work_b": {"value": 2}, "work_a": {"value": 1}}

    sends = bounded_sends(work, target_node="execute_graph")

    assert [send.arg["work_item_key"] for send in sends] == ["work_a", "work_b"]
    work["work_a"]["value"] = 99
    assert sends[0].arg["work_item"]["value"] == 1

    with pytest.raises(GraphRouteError, match="fan-out"):
        bounded_sends(
            {f"work_{index}": {"value": index} for index in range(9)},
            target_node="execute_graph",
        )
    with pytest.raises(GraphRouteError, match="canonical JSON"):
        bounded_sends(
            {"work_invalid": {"client": object()}},
            target_node="execute_graph",
        )


def test_platform_topology_is_explicit_and_renderable() -> None:
    def no_change(state):
        return {}

    builder = build_shadow_kernel_graph(
        validate_command=no_change,
        execute_graph=no_change,
        project_result=no_change,
    )

    mermaid = builder.compile().get_graph().draw_mermaid()

    assert "validate_command" in mermaid
    assert "execute_graph" in mermaid
    assert "project_result" in mermaid
    assert mermaid.index("validate_command") < mermaid.index("execute_graph")
