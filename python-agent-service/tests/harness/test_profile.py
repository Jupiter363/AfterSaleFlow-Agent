import pytest
from pydantic import ValidationError

from app.harness.profile import AgentProfile, LoopBudget


def profile_payload() -> dict:
    return {
        "agent_id": "evidence-clerk",
        "role": "Evidence Clerk",
        "version": "final-1",
        "allowed_case_states": ["DOSSIER_BUILDING"],
        "allowed_context_scopes": ["case", "evidence"],
        "allowed_skills": ["timeline.build", "evidence.gaps"],
        "allowed_tools": ["case.read", "evidence.read", "evidence.parse"],
        "forbidden_actions": [
            "decide_liability",
            "review.approve",
            "refund.execute",
        ],
        "budget": {
            "max_iterations": 8,
            "max_tool_calls": 12,
            "max_model_calls": 8,
            "max_input_tokens": 40_000,
            "max_output_tokens": 8_000,
            "deadline_seconds": 120,
            "stagnation_threshold": 2,
            "max_output_repairs": 1,
        },
        "output_schema": "EvidenceDossierResult",
        "risk_policy": "evidence-clerk-final",
    }


def test_profile_explicitly_authorizes_only_declared_capabilities() -> None:
    profile = AgentProfile.model_validate(profile_payload())

    assert profile.authorizes_case_state("DOSSIER_BUILDING")
    assert profile.authorizes_context("evidence")
    assert profile.authorizes_skill("timeline.build")
    assert profile.authorizes_tool("evidence.read")
    assert not profile.authorizes_tool("refund.execute")
    assert not profile.authorizes_tool("unknown.read")
    assert profile.forbids("decide_liability")


def test_profile_rejects_authority_conflicts() -> None:
    payload = profile_payload()
    payload["allowed_tools"].append("refund.execute")

    with pytest.raises(ValidationError):
        AgentProfile.model_validate(payload)


def test_loop_budget_rejects_unbounded_or_incoherent_limits() -> None:
    with pytest.raises(ValidationError):
        LoopBudget(
            max_iterations=0,
            max_tool_calls=12,
            max_model_calls=8,
            max_input_tokens=40_000,
            max_output_tokens=8_000,
            deadline_seconds=120,
            stagnation_threshold=2,
            max_output_repairs=1,
        )

    with pytest.raises(ValidationError):
        LoopBudget(
            max_iterations=8,
            max_tool_calls=2,
            max_model_calls=8,
            max_input_tokens=40_000,
            max_output_tokens=50_000,
            deadline_seconds=120,
            stagnation_threshold=2,
            max_output_repairs=1,
        )
