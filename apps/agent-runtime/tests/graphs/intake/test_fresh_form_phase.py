"""Fresh INITIAL_FORM must use the same phase authority in prompt, wire and parser."""
import copy

import pytest
from jsonschema import Draft202012Validator
from langchain_core.exceptions import OutputParserException

from app.agents.dispute_intake_officer.schemas import IntakeInitiatorRoomLlmOutputV3
from app.graphs.intake.graph import compile_intake_v2_graph
from app.graphs.intake.state import new_intake_graph_state
from tests.graphs.intake.test_lcel import (
    RawBaselineIntakeTransport, _bootstrap_event_context, _initial_form_ingress,
    _policy, _profile, build_intake_model_node,
)
from tests.graphs.intake.test_lcel import version_pins as lcel_version_pins
from tests.graphs.intake.test_ordered_room_context import _initiator_v3_payload


@pytest.fixture
def version_pins():
    return lcel_version_pins.__wrapped__()


def _invitation():
    payload = _initiator_v3_payload()
    payload["ordered_sections"][7]["value"].update(blocking_gaps=[], next_questions=[])
    payload["ordered_sections"][8]["value"].update(remark_status="WAITING_FOR_REMARK")
    payload["ordered_sections"][9]["value"].update(
        score_breakdown={"references": 15, "event_story": 20, "party_positions": 20,
                         "requested_resolution": 15, "risk_and_conflicts": 15, "next_action_clarity": 15},
        ready_for_next_step=True, admission_recommendation="ACCEPTED",
        conversation_action="INVITE_OPTIONAL_REMARK",
    )
    return payload


def _run(payload, bindings, version_pins, snapshot, event):
    transport = RawBaselineIntakeTransport(payload)
    phases = []
    built = build_intake_model_node(transport=transport, profile=_profile(), policy=_policy(),
                                    _test_hook=phases.append)
    graph = compile_intake_v2_graph(intake_lcel=built.runnable)
    initial = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    initial["bindings"]["command"].update(command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2", attempt_id="ATTEMPT_P4_USER_2_1")
    imported, form = _initial_form_ingress(snapshot, event)
    return transport, phases, graph, initial, _bootstrap_event_context(imported, form)


def test_fresh_form_real_graph_uses_narrow_schema_and_keeps_context_authority(
    bindings, version_pins, snapshot, event,
):
    transport, _, graph, initial, context = _run(_initiator_v3_payload(), bindings, version_pins, snapshot, event)
    original = copy.deepcopy(initial)
    result = graph.invoke(initial, context=context)
    assert result["result_json"]["conversation_action"] == "ASK_SUBSTANTIVE"
    assert initial == original
    assert transport.generate_calls == 1
    request = transport.requests[0]
    schema = request.output_type.model_json_schema()
    assert schema != IntakeInitiatorRoomLlmOutputV3.model_json_schema()
    validator = Draft202012Validator(schema)
    assert validator.is_valid(_initiator_v3_payload())
    assert not validator.is_valid(_invitation())
    first = request.output_type.model_validate(_initiator_v3_payload())
    replay = request.output_type.model_validate(first.model_dump(mode="json"))
    assert replay == first
    assert transport.generate_calls == 1
    prompt = str(request.messages[0].content)
    context_text = str(request.messages[1].content)
    assert "首次表单" in prompt
    assert '"previous_phase":"NOT_READY"' in context_text.replace(" ", "")
    assert '"allowed_conversation_actions":["ASK_SUBSTANTIVE"]' in context_text.replace(" ", "")


def test_fresh_form_rejects_premature_invitation_before_reducer_not_by_rewriting(
    bindings, version_pins, snapshot, event,
):
    payload = _invitation()
    # The old generic ordered schema admits the same semantically valid invitation.
    IntakeInitiatorRoomLlmOutputV3.model_validate(payload)
    transport, phases, graph, initial, context = _run(payload, bindings, version_pins, snapshot, event)
    with pytest.raises(OutputParserException):
        graph.invoke(initial, context=context)
    assert transport.generate_calls == 1
    assert phases == ["before_model"]
