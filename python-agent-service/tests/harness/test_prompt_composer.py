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


def test_intake_officer_prompt_declares_context_pack_workflow_contract() -> None:
    system_prompt, _ = PromptRepository().render(
        "intake_turn_case_detail",
        {"case_id": "CASE_prompt_contract"},
        {"type": "object"},
    )

    assert "Context Pack 读取契约" in system_prompt
    assert "current_turn" in system_prompt
    assert "intake_initial_form" in system_prompt
    assert "latest_canvas_snapshot" in system_prompt
    assert "short_term_memory" in system_prompt
    assert "compressed_summary" in system_prompt
    assert "接待室是单方参与房间" in system_prompt
    assert "raw_statement" in system_prompt
    assert "platform_statement" in system_prompt
    assert "不要把上一轮展板丢掉重写" in system_prompt
    assert "WAITING_FOR_REMARK" in system_prompt
    assert "HAS_REMARKS" in system_prompt


def test_evidence_clerk_prompt_declares_context_pack_and_party_isolation_contract() -> None:
    system_prompt, _ = PromptRepository().render(
        "evidence_turn",
        {"case_id": "CASE_prompt_contract"},
        {"type": "object"},
    )

    assert "Context Pack 读取契约" in system_prompt
    assert "current_turn" in system_prompt
    assert "canonical_case_dossier" in system_prompt
    assert "actor_private_memory" in system_prompt
    assert "actor_visible_evidence" in system_prompt
    assert "evidence_gap_plan" in system_prompt
    assert "只读取当前 actor_id / agent_session_id 对应的私有上下文" in system_prompt
    assert "不得引用另一方私聊" in system_prompt
    assert "ROOM_OPENING" in system_prompt
    assert "PARTY_MESSAGE" in system_prompt
    assert "证据不足也不阻止进入小法庭" in system_prompt


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
