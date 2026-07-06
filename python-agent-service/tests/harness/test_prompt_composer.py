from pathlib import Path

import pytest

from app.harness.prompt_composer import PromptRepository


def test_prompt_repository_loads_common_fragments_and_agent_prompt() -> None:
    repo = PromptRepository()

    system_prompt, user_prompt = repo.render(
        "intake_analyze",
        {"raw_text": "物流显示签收但用户未收到"},
        {"type": "object"},
    )

    assert "Common AI Native harness safety boundary" in system_prompt
    assert "Harness business code localization rules" in system_prompt
    assert "Harness case narration rules" in system_prompt
    assert "neutral Dispute Intake Officer" in system_prompt
    assert "<untrusted_case_data>" in user_prompt
    assert "物流显示签收但用户未收到" in user_prompt
    assert "<required_output_schema>" in user_prompt


def test_prompt_repository_resolves_agent_owned_template_path() -> None:
    repo = PromptRepository()

    path = repo.template_path("intake_analyze")

    assert path == Path("app/agents/prompts/dispute_intake_officer/intake_analyze.md")


def test_prompt_repository_resolves_role_specific_profile_template_path() -> None:
    repo = PromptRepository()

    assert repo.template_path(
        "evidence_turn",
        prompt_profile_id="EVIDENCE_CLERK:USER:v1",
    ) == Path("app/agents/prompts/evidence_clerk/evidence_turn.user.md")
    assert repo.template_path(
        "evidence_turn",
        prompt_profile_id="EVIDENCE_CLERK:MERCHANT:v1",
    ) == Path("app/agents/prompts/evidence_clerk/evidence_turn.merchant.md")


def test_prompt_repository_falls_back_for_unknown_profile_only_when_allowed() -> None:
    repo = PromptRepository()

    with pytest.raises(FileNotFoundError):
        repo.template_path(
            "evidence_turn",
            prompt_profile_id="EVIDENCE_CLERK:REVIEWER:v1",
        )

    assert repo.template_path(
        "evidence_turn",
        prompt_profile_id="EVIDENCE_CLERK:REVIEWER:v1",
        allow_profile_fallback=True,
    ) == Path("app/agents/prompts/evidence_clerk/evidence_turn.md")


def test_prompt_repository_rejects_unknown_node() -> None:
    repo = PromptRepository()

    with pytest.raises(KeyError):
        repo.render("unknown_node", {}, {"type": "object"})
