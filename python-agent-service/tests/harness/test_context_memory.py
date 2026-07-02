from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.harness.context import (
    ContextAssembler,
    ContextAuthorityError,
    ContextFragment,
    ContextTokenBudgetError,
)
from app.harness.memory import MemoryEntry, MemoryScope
from app.harness.profile import AgentProfile
from tests.harness.test_profile import profile_payload


def profile() -> AgentProfile:
    return AgentProfile.model_validate(profile_payload())


def fragment(
    source_id: str,
    *,
    scope: str = "evidence",
    content: str = "short evidence",
    priority: int = 50,
) -> ContextFragment:
    return ContextFragment(
        source_type="EVIDENCE",
        source_id=source_id,
        source_version="3",
        captured_at=datetime(2026, 7, 2, tzinfo=UTC),
        access_scope=scope,
        content=content,
        priority=priority,
    )


def test_context_rejects_wrong_case_state_and_unauthorized_scope() -> None:
    assembler = ContextAssembler()

    with pytest.raises(ContextAuthorityError):
        assembler.assemble(profile(), "CLOSED", [fragment("EV-1")], 1_000)

    with pytest.raises(ContextAuthorityError):
        assembler.assemble(
            profile(),
            "DOSSIER_BUILDING",
            [fragment("PAY-1", scope="payment-secret")],
            1_000,
        )


def test_context_is_deterministic_and_respects_the_token_budget() -> None:
    assembler = ContextAssembler()
    high = fragment("EV-HIGH", content="a" * 200, priority=100)
    low = fragment("EV-LOW", content="b" * 200, priority=10)

    assembled = assembler.assemble(
        profile(), "DOSSIER_BUILDING", [low, high], 100
    )

    assert [item.source_id for item in assembled.fragments] == ["EV-HIGH"]
    assert assembled.estimated_tokens <= 100

    with pytest.raises(ContextTokenBudgetError):
        assembler.assemble(
            profile(),
            "DOSSIER_BUILDING",
            [fragment("EV-REQUIRED", content="x" * 2_000, priority=100)],
            10,
            required_source_ids={"EV-REQUIRED"},
        )


def test_experience_memory_requires_explicit_offline_approval() -> None:
    common = {
        "memory_key": "swap-fraud-pattern",
        "memory_version": 1,
        "source_refs": ["CASE-1"],
        "content": {"pattern": "serial number mismatch"},
    }

    with pytest.raises(ValidationError):
        MemoryEntry(
            **common,
            scope=MemoryScope.EXPERIENCE,
            approved_for_experience=False,
        )

    approved = MemoryEntry(
        **common,
        scope=MemoryScope.EXPERIENCE,
        approved_for_experience=True,
        approved_by="review-governance",
    )

    assert approved.scope is MemoryScope.EXPERIENCE
