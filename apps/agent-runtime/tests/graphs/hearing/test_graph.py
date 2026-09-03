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


class _EvidenceAssessment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    evidence_id: str
    score: int


class _FormalEffectProposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = "hearing_test_proposal.v1"
    formal_action: str = "ADVANCE_STAGE"


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
        expected = {
            "validate_and_route",
            "project_proposal",
            *operations,
        }
        if identity_name == "hearing.evidence.v1":
            expected.remove(HearingOperation.EVIDENCE_SYNTHESIS.value)
            expected.update(
                {
                    "plan_evidence_work",
                    "plan_evidence_wave",
                    "assess_evidence_item",
                    "keyed_evidence_fan_in",
                    "complete_evidence_synthesis",
                }
            )
        assert set(graph.nodes) == expected


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
    invocation = HearingGraphInvocation(
        request=request,
        execute=lambda _: _Proposal(),
    )
    if operation is HearingOperation.EVIDENCE_SYNTHESIS:
        invocation = HearingGraphInvocation(
            request=request,
            execute=lambda _: _Proposal(),
            plan_work_items=lambda _: [],
            execute_work_item=lambda _request, _key: _Proposal(),
            execute_with_work_results=lambda _request, _results: _Proposal(),
        )
    result = graphs[identity.identity].invoke(
        state,
        context=invocation,
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


def test_evidence_synthesis_uses_sorted_bounded_send_fanout_and_exact_fan_in() -> None:
    operation = HearingOperation.EVIDENCE_SYNTHESIS
    identity = HEARING_OPERATION_IDENTITIES[operation]
    request = _Request(stage_sequence=4)
    state = new_hearing_graph_state(identity=identity, operation=operation, request=request)
    keys = [f"EVIDENCE_{index:02d}" for index in reversed(range(9))]
    projected_keys: list[str] = []

    def project(_request, results):
        projected_keys.extend(results)
        return _Proposal()

    result = compile_hearing_graph_candidates()[identity.identity].invoke(
        state,
        context=HearingGraphInvocation(
            request=request,
            execute=lambda _: _Proposal(),
            plan_work_items=lambda _: keys,
            execute_work_item=lambda _request, key: _EvidenceAssessment(
                evidence_id=key,
                score=int(key.rsplit("_", 1)[1]),
            ),
            execute_with_work_results=project,
        ),
        config={"max_concurrency": 8, "recursion_limit": 64},
    )

    expected = sorted(keys)
    assert result["ordered_work_item_keys"] == expected
    assert list(result["work_results"]) == expected
    assert projected_keys == expected
    assert result["next_dispatch_index"] == 9
    assert result["in_flight_keys"] == []
    assert result["status"] == "PROPOSED"


def test_evidence_fanout_rejects_duplicate_stable_keys_before_model_execution() -> None:
    operation = HearingOperation.EVIDENCE_SYNTHESIS
    identity = HEARING_OPERATION_IDENTITIES[operation]
    request = _Request(stage_sequence=4)
    state = new_hearing_graph_state(identity=identity, operation=operation, request=request)
    assessed = False

    def assess(_request, key):
        nonlocal assessed
        assessed = True
        return _EvidenceAssessment(evidence_id=key, score=1)

    with pytest.raises(HearingGraphContractError, match="HEARING_EVIDENCE_WORK_KEYS_INVALID"):
        compile_hearing_graph_candidates()[identity.identity].invoke(
            state,
            context=HearingGraphInvocation(
                request=request,
                execute=lambda _: _Proposal(),
                plan_work_items=lambda _: ["EVIDENCE_01", "EVIDENCE_01"],
                execute_work_item=assess,
                execute_with_work_results=lambda _request, _results: _Proposal(),
            ),
        )
    assert assessed is False


def test_nested_or_top_level_formal_effect_never_leaves_graph() -> None:
    operation = HearingOperation.JUDGE_V2
    identity = HEARING_OPERATION_IDENTITIES[operation]
    request = _Request(stage_sequence=7)
    state = new_hearing_graph_state(identity=identity, operation=operation, request=request)

    with pytest.raises(HearingGraphContractError, match="HEARING_FORMAL_EFFECT_FORBIDDEN"):
        compile_hearing_graph_candidates()[identity.identity].invoke(
            state,
            context=HearingGraphInvocation(
                request=request,
                execute=lambda _: _FormalEffectProposal(),
            ),
        )
