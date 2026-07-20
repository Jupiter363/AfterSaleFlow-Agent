from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from app.graphs.intake.state import IntakeCommandBindings
from app.graph_runtime.state import VersionPinsState


ROOT = Path(__file__).resolve().parents[4]
FIXTURES = ROOT / "contracts" / "agent-platform" / "intake" / "v2" / "fixtures" / "valid"


def _fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


@pytest.fixture
def snapshot() -> dict:
    return copy.deepcopy(_fixture("intake-domain-snapshot-valid.json"))


@pytest.fixture
def event() -> dict:
    return copy.deepcopy(_fixture("intake-turn-event-valid.json"))


@pytest.fixture
def bindings(snapshot: dict) -> IntakeCommandBindings:
    return {
        "schema_version": "graph-command-binding.v1",
        "command_id": "COMMAND_P4_USER_1",
        "logical_run_id": "RUN_P4_USER_1",
        "attempt_id": "ATTEMPT_P4_USER_1_1",
        "tenant_surrogate": snapshot["tenant_surrogate"],
        "case_id": snapshot["case_id"],
        "room_type": "INTAKE",
        "room_epoch": snapshot["room_epoch"],
        "actor_scope_hash": snapshot["actor_scope_hash"],
        "thread_id": snapshot["thread_id"],
        "agent_session_id": snapshot["agent_session_id"],
        "audience": "USER",
    }


@pytest.fixture
def version_pins() -> VersionPinsState:
    return {
        "schema_version": "graph-version-pins.v1",
        "graph_key": "intake.v2",
        "graph_version": "2.0.0",
        "checkpoint_schema_version": "intake-checkpoint.v2",
        "state_schema_version": "intake-graph-state.v2",
        "prompt_version": "intake-prompt.v2",
        "model_profile_id": "intake-model.synthetic.v1",
        "output_schema_version": "intake-turn-proposal.v2",
        "policy_version": "intake-policy.v2",
        "guardrail_version": "intake-guardrail.v2",
        "tool_policy_version": "no-tools.v1",
    }
