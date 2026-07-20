from __future__ import annotations

import copy

import pytest

from app.contracts.v1.codec import canonical_sha256_omitting
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import new_intake_graph_state
from app.graphs.intake.validators import validate_event, validate_snapshot, validate_state


def test_snapshot_rejects_private_binding_mismatch(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    snapshot["agent_session_id"] = "AGENT_SESSION_OTHER"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_PRIVATE_BINDING_MISMATCH"):
        validate_snapshot(state, snapshot)


def test_event_rejects_other_party_audience(bindings, version_pins, event) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    event["audience"] = "MERCHANT"
    event["event_hash"] = canonical_sha256_omitting(event, "event_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_EVENT_AUDIENCE_MISMATCH"):
        validate_event(state, event)


def test_state_rejects_memory_frame_at_any_depth(bindings, version_pins) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    state["dossier_draft"] = {"nested": {"memory_frame": {"secret": True}}}

    with pytest.raises(IntakeGraphContractError, match="INTAKE_FORBIDDEN_FIELD"):
        validate_state(state)


def test_snapshot_self_hash_is_mandatory(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    corrupted = copy.deepcopy(snapshot)
    corrupted["current_dossier"]["case_story"] = {"summary": "changed"}

    with pytest.raises(IntakeGraphContractError, match="INTAKE_SNAPSHOT_HASH_INVALID"):
        validate_snapshot(state, corrupted)


def test_snapshot_rejects_unknown_authority_field(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    snapshot["writer_mode"] = "TEMPORAL"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_SNAPSHOT_SCHEMA_INVALID"):
        validate_snapshot(state, snapshot)
