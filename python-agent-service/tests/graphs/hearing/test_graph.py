from __future__ import annotations

from pydantic import BaseModel, ConfigDict
import pytest

from app.graphs.hearing.contracts import (
    HEARING_GRAPH_IDENTITIES,
    HEARING_OPERATION_IDENTITIES,
    HearingOperation,
)
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.graph import (
    build_hearing_evidence_v1_graph,
    build_hearing_intake_v1_graph,
    build_hearing_judge_v1_graph,
    build_hearing_jury_v1_graph,
    compile_hearing_graph_candidates,
)
from app.graphs.hearing.state import HearingGraphInvocation, new_hearing_graph_state


class _Request(BaseModel):
    model_config = ConfigDict(extra="forbid")

    flow_schema_version: str = "hearing_flow.v2"
    case_id: str = "CASE_hearing"
    workflow_id: str = "WORKFLOW_hearing"
    stage_sequence: int = 1
    private_statement: str = "must remain outside serializable graph state"


class _Proposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = "hearing_test_proposal.v1"
    case_id: str = "CASE_hearing"


def test_registry_candidate_has_four_families_and_exactly_seven_operations() -> None:
    assert set(HEARING_GRAPH_IDENTITIES) == {
        "hearing.intake.v1",
        "hearing.evidence.v1",
        "hearing.judge.v1",
        "hearing.jury.v1",
    }
    assert set(HEARING_OPERATION_IDENTITIES) == set(HearingOperation)
    assert sum(
        len(identity.operations) for identity in HEARING_GRAPH_IDENTITIES.values()
    ) == 7


def test_each_family_has_an_explicit_operation_router() -> None:
    graphs = {
        "hearing.intake.v1": build_hearing_intake_v1_graph(),
        "hearing.evidence.v1": build_hearing_evidence_v1_graph(),
        "hearing.judge.v1": build_hearing_judge_v1_graph(),
        "hearing.jury.v1": build_hearing_jury_v1_graph(),
    }
    for identity_name, graph in graphs.items():
        operations = {
            operation.value
            for operation in HEARING_GRAPH_IDENTITIES[identity_name].operations
        }
        assert set(graph.nodes) == {
            "validate_and_route",
            "project_proposal",
            *operations,
        }


@pytest.mark.parametrize("operation", list(HearingOperation))
def test_all_seven_operations_return_typed_proposals(operation: HearingOperation) -> None:
    identity = HEARING_OPERATION_IDENTITIES[operation]
    request = _Request(stage_sequence=list(HearingOperation).index(operation) + 1)
    state = new_hearing_graph_state(
        identity=identity,
        operation=operation,
        request=request,
    )
    graphs = compile_hearing_graph_candidates()
    result = graphs[identity.identity].invoke(
        state,
        context=HearingGraphInvocation(
            request=request,
            execute=lambda _: _Proposal(),
        ),
    )

    assert result["status"] == "PROPOSED"
    assert result["proposal_schema_version"] == "hearing_test_proposal.v1"
    assert result["proposal"] == _Proposal().model_dump(mode="json")


def test_private_request_body_is_not_serialized_into_initial_graph_state() -> None:
    request = _Request()
    identity = HEARING_OPERATION_IDENTITIES[HearingOperation.INTAKE_QUESTIONS]

    state = new_hearing_graph_state(
        identity=identity,
        operation=HearingOperation.INTAKE_QUESTIONS,
        request=request,
    )

    assert "private_statement" not in state
    assert request.private_statement not in repr(state)
    assert set(state) == {
        "schema_version",
        "graph_identity",
        "version_pins",
        "operation",
        "case_id",
        "workflow_id",
        "stage_sequence",
        "request_schema_version",
        "request_hash",
        "status",
    }


def test_unknown_operation_fails_closed_before_execution() -> None:
    request = _Request()
    identity = HEARING_OPERATION_IDENTITIES[HearingOperation.INTAKE_QUESTIONS]
    state = new_hearing_graph_state(
        identity=identity,
        operation=HearingOperation.INTAKE_QUESTIONS,
        request=request,
    )
    state["operation"] = "unexpected_operation"
    executed = False

    def execute(_: BaseModel) -> _Proposal:
        nonlocal executed
        executed = True
        return _Proposal()

    with pytest.raises(HearingGraphContractError, match="HEARING_OPERATION_UNKNOWN"):
        compile_hearing_graph_candidates()[identity.identity].invoke(
            state,
            context=HearingGraphInvocation(request=request, execute=execute),
        )
    assert executed is False


def test_request_hash_rebinding_fails_closed_before_execution() -> None:
    request = _Request()
    changed = _Request(private_statement="a different private statement")
    identity = HEARING_OPERATION_IDENTITIES[HearingOperation.INTAKE_SYNTHESIS]
    state = new_hearing_graph_state(
        identity=identity,
        operation=HearingOperation.INTAKE_SYNTHESIS,
        request=request,
    )

    with pytest.raises(HearingGraphContractError, match="HEARING_REQUEST_BINDING_MISMATCH"):
        compile_hearing_graph_candidates()[identity.identity].invoke(
            state,
            context=HearingGraphInvocation(
                request=changed,
                execute=lambda _: _Proposal(),
            ),
        )
