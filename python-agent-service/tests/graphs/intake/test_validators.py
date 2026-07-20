from __future__ import annotations

import copy

import pytest

from app.contracts.v1.codec import canonical_sha256_omitting
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import new_intake_graph_state
from app.graphs.intake.validators import (
    validate_event,
    validate_snapshot,
    validate_state,
    validate_terminal_proposal,
)


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


def test_snapshot_rejects_other_party_private_message(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    snapshot["own_messages"][0]["audience"] = "MERCHANT"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(
        IntakeGraphContractError,
        match="INTAKE_SNAPSHOT_MESSAGE_AUDIENCE_MISMATCH",
    ):
        validate_snapshot(state, snapshot)


def test_snapshot_rejects_nested_credentials(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    snapshot["initial_case_facts"]["credentials"] = {"access_token": "not-checkpointed"}
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_FORBIDDEN_FIELD"):
        validate_snapshot(state, snapshot)


def test_snapshot_rejects_duplicate_stable_message_id(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    duplicate = copy.deepcopy(snapshot["own_messages"][0])
    duplicate.update(sequence=2, text="Conflicting payload.", source_hash="2" * 64)
    snapshot["own_messages"].append(duplicate)
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_SNAPSHOT_MESSAGE_ID_CONFLICT"):
        validate_snapshot(state, snapshot)


@pytest.mark.parametrize("sequences", [(1, 1), (2, 1)])
def test_snapshot_rejects_duplicate_or_unordered_sequence(
    bindings,
    version_pins,
    snapshot,
    sequences,
) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    snapshot["own_messages"][0]["sequence"] = sequences[0]
    second = copy.deepcopy(snapshot["own_messages"][0])
    second.update(message_id="MESSAGE_P4_USER_2", sequence=sequences[1], source_hash="2" * 64)
    snapshot["own_messages"].append(second)
    snapshot["source_refs"].append(second["message_id"])
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(
        IntakeGraphContractError,
        match="INTAKE_SNAPSHOT_MESSAGE_SEQUENCE_INVALID",
    ):
        validate_snapshot(state, snapshot)


def test_snapshot_enforces_message_limit_in_utf8_bytes(bindings, version_pins, snapshot) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    snapshot["own_messages"][0]["text"] = "庭" * 3000
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_SNAPSHOT_MESSAGE_TOO_LARGE"):
        validate_snapshot(state, snapshot)


def test_event_requires_its_message_in_source_refs(bindings, version_pins, event) -> None:
    state = new_intake_graph_state(bindings=bindings, version_pins=version_pins)
    event["source_refs"] = ["MESSAGE_UNRELATED"]
    event["event_hash"] = canonical_sha256_omitting(event, "event_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_EVENT_MESSAGE_SOURCE_MISSING"):
        validate_event(state, event)


def test_proposal_rejects_explicit_null_dossier_branch(proposal) -> None:
    proposal["dossier_patch"]["case_story"] = None
    proposal["proposal_hash"] = canonical_sha256_omitting(proposal, "proposal_hash")

    with pytest.raises(IntakeGraphContractError, match="INTAKE_PROPOSAL_SCHEMA_INVALID"):
        validate_terminal_proposal(proposal)
