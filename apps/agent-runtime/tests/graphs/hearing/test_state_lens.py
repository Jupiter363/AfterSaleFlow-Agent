from __future__ import annotations

import json

import pytest
from pydantic import BaseModel, ConfigDict

from app.graphs.hearing.contracts import HEARING_OPERATION_IDENTITIES, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.privacy import (
    build_actor_private_state_lens,
    build_shared_state_lens,
    validate_hearing_scope_binding,
)
from app.graphs.hearing.state import new_hearing_graph_state


class _Request(BaseModel):
    model_config = ConfigDict(extra="forbid")

    flow_schema_version: str = "hearing_flow.v2"
    case_id: str = "CASE_hearing"
    workflow_id: str = "WORKFLOW_hearing"
    stage_sequence: int = 1
    raw_private_statement: str = "never checkpoint this value"


def _state(scope: str, barrier: str | None):
    operation = HearingOperation.INTAKE_QUESTIONS
    identity = HEARING_OPERATION_IDENTITIES[operation]
    return new_hearing_graph_state(
        identity=identity,
        operation=operation,
        request=_Request(),
        command_binding={
            "schema_version": "hearing-command-binding.v1",
            "command_id": "COMMAND_hearing",
            "operation_key": "hearing.agent:key",
            "command_request_hash": "a" * 64,
            "thread_id": "grt.v1." + "1" * 32,
            "tenant_surrogate": "TENANT_hearing",
            "room_epoch": 1,
            "process_revision": 3,
            "java_room_fencing_token": 7,
            "graph_lease_owner_id": "worker-a",
            "graph_lease_fencing_token": 9,
        },
        scope_binding={
            "schema_version": "hearing-scope-binding.v1",
            "state_scope": scope,  # type: ignore[typeddict-item]
            "actor_scope_hash": "b" * 64,
            "authorized_artifact_refs": [
                {
                    "artifact_id": "ARTIFACT_statement",
                    "schema_version": "hearing_statement.v1",
                    "uri": "urn:hearing:artifact:statement",
                    "sha256": "c" * 64,
                }
            ],
            "shared_barrier_receipt_hash": barrier,
        },
    )


def test_actor_private_lens_exposes_only_hash_bound_refs_and_pins() -> None:
    state = _state("ACTOR_PRIVATE", None)

    selected = build_actor_private_state_lens().invoke(state)

    assert set(selected) == {
        "state_scope",
        "audience_binding",
        "authorized_artifact_refs_json",
        "version_pins_json",
    }
    assert selected["state_scope"] == "ACTOR_PRIVATE"
    assert selected["audience_binding"] == "b" * 64
    assert json.loads(selected["authorized_artifact_refs_json"])[0]["artifact_id"] == (
        "ARTIFACT_statement"
    )
    assert _Request().raw_private_statement not in repr(state)
    assert _Request().raw_private_statement not in repr(selected)


def test_shared_lens_requires_java_barrier_receipt() -> None:
    valid = _state("SHARED", "d" * 64)
    selected = build_shared_state_lens().invoke(valid)

    assert selected["state_scope"] == "SHARED"
    assert selected["audience_binding"] == "SHARED:" + "d" * 64

    invalid = _state("SHARED", None)
    with pytest.raises(HearingGraphContractError, match="HEARING_SCOPE_LENS_REJECTED"):
        validate_hearing_scope_binding(invalid)


def test_actor_private_scope_rejects_shared_barrier_rebinding() -> None:
    state = _state("ACTOR_PRIVATE", "d" * 64)

    with pytest.raises(HearingGraphContractError, match="HEARING_SCOPE_LENS_REJECTED"):
        validate_hearing_scope_binding(state)
