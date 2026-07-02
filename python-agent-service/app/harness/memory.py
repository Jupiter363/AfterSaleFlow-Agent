"""Memory scopes and promotion rules for governed Agent runs."""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class MemoryScope(StrEnum):
    RUN = "RUN"
    CASE = "CASE"
    EXPERIENCE = "EXPERIENCE"


class MemoryEntry(BaseModel):
    """A versioned memory item with explicit promotion approval.

    Case output cannot silently become shared experience. EXPERIENCE entries
    require an offline governance identity so one disputed case never teaches
    the whole system by itself.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    scope: MemoryScope
    memory_key: str = Field(min_length=1, max_length=128)
    memory_version: int = Field(ge=1)
    source_refs: tuple[str, ...] = Field(min_length=1)
    content: dict[str, Any]
    approved_for_experience: bool = False
    approved_by: str | None = Field(default=None, min_length=3, max_length=128)

    @model_validator(mode="after")
    def enforce_experience_approval(self) -> Self:
        if self.scope is MemoryScope.EXPERIENCE:
            if not self.approved_for_experience or not self.approved_by:
                raise ValueError(
                    "experience memory requires explicit offline approval"
                )
        elif self.approved_for_experience:
            raise ValueError(
                "only EXPERIENCE memory may be approved for experience"
            )
        return self
