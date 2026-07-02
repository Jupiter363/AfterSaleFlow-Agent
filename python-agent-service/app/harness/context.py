"""Deterministic, authority-aware context assembly."""

from __future__ import annotations

import json
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.harness.profile import AgentProfile


class ContextAuthorityError(ValueError):
    """Raised when a context request exceeds the Agent profile."""


class ContextTokenBudgetError(ValueError):
    """Raised when required context cannot fit the declared budget."""


class ContextFragment(BaseModel):
    """A versioned context fragment whose source remains traceable."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    source_type: str = Field(min_length=1, max_length=64)
    source_id: str = Field(min_length=1, max_length=128)
    source_version: str = Field(min_length=1, max_length=64)
    captured_at: datetime
    access_scope: str = Field(min_length=1, max_length=64)
    content: str = Field(min_length=1, max_length=100_000)
    priority: int = Field(default=50, ge=0, le=100)

    def estimated_tokens(self) -> int:
        serialized = json.dumps(
            self.model_dump(mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        return max(1, (len(serialized) + 3) // 4)


class AssembledContext(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fragments: tuple[ContextFragment, ...]
    estimated_tokens: int = Field(ge=0)
    omitted_source_ids: tuple[str, ...]


class ContextAssembler:
    """Builds the smallest deterministic context allowed by a profile."""

    def assemble(
        self,
        profile: AgentProfile,
        case_state: str,
        fragments: list[ContextFragment],
        max_tokens: int,
        *,
        required_source_ids: set[str] | None = None,
    ) -> AssembledContext:
        if max_tokens < 1:
            raise ContextTokenBudgetError("max_tokens must be positive")
        if not profile.authorizes_case_state(case_state):
            raise ContextAuthorityError(
                f"{profile.agent_id} cannot run in case state {case_state}"
            )
        for fragment in fragments:
            if not profile.authorizes_context(fragment.access_scope):
                raise ContextAuthorityError(
                    f"{profile.agent_id} cannot access scope "
                    f"{fragment.access_scope}"
                )

        required = required_source_ids or set()
        by_id = {fragment.source_id: fragment for fragment in fragments}
        missing = required - by_id.keys()
        if missing:
            raise ContextAuthorityError(
                f"required context sources are missing: {sorted(missing)}"
            )

        ordered = sorted(
            fragments,
            key=lambda item: (
                item.source_id not in required,
                -item.priority,
                item.source_id,
                item.source_version,
            ),
        )
        selected: list[ContextFragment] = []
        omitted: list[str] = []
        used = 0
        for fragment in ordered:
            cost = fragment.estimated_tokens()
            if used + cost <= max_tokens:
                selected.append(fragment)
                used += cost
                continue
            if fragment.source_id in required:
                raise ContextTokenBudgetError(
                    f"required source {fragment.source_id} exceeds token budget"
                )
            omitted.append(fragment.source_id)

        return AssembledContext(
            fragments=tuple(selected),
            estimated_tokens=used,
            omitted_source_ids=tuple(omitted),
        )
