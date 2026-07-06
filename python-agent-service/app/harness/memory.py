"""Memory scopes and promotion rules for governed Agent runs."""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class MemoryScope(StrEnum):
    RUN = "RUN"
    CASE = "CASE"
    EXPERIENCE = "EXPERIENCE"


class MemoryScopeViolation(ValueError):
    """Raised when backend-supplied turn memory crosses an agent session boundary."""


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


class MemeoMemoryConfig(BaseModel):
    """Per-agent memory mode flags, later owned by the Agent configuration center."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    short_term_enabled: bool = True
    summary_enabled: bool = True
    long_term_enabled: bool = False
    short_term_round_limit: int = Field(default=5, ge=0, le=20)
    summary_window_round_limit: int = Field(default=10, ge=1, le=50)
    compressed_token_limit: int = Field(default=200, ge=1, le=2_000)


class MemeoMemoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    role: str = Field(min_length=1, max_length=64)
    content: str = Field(min_length=1, max_length=20_000)


class MemeoMemoryRound(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    turn_no: int = Field(ge=1)
    messages: tuple[MemeoMemoryMessage, ...] = Field(default_factory=tuple)


class MemeoLongTermSlot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    memory_key: str = Field(min_length=1, max_length=128)
    status: str = Field(default="RESERVED", min_length=1, max_length=64)
    content: str = Field(default="", max_length=20_000)
    enabled: bool = False
    prompt_included: bool = False


class MemeoMemoryFrame(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    memory_modes: MemeoMemoryConfig = Field(default_factory=MemeoMemoryConfig)
    short_term_rounds: tuple[MemeoMemoryRound, ...] = Field(default_factory=tuple)
    compression_window_turns: tuple[int, ...] = Field(default_factory=tuple)
    compressed_summary: str = ""
    compressed_summary_estimated_tokens: int = 0
    prompt_memory: str = ""
    prompt_memory_estimated_tokens: int = 0
    long_term_slots: tuple[MemeoLongTermSlot, ...] = Field(default_factory=tuple)


class MemeoMemoryAssembler:
    """Builds the shared short-term memory frame for Agent harness runs.

    The current implementation is deterministic and local: it compresses recent
    room-turn memory into a bounded textual frame. Long-term memory is exposed
    as a slot for UI and future vector retrieval, but is not inserted into the
    prompt by default.
    """

    def assemble(
        self,
        recent_turns: list[dict[str, Any]],
        *,
        config: MemeoMemoryConfig | None = None,
        long_term_preview: list[dict[str, Any]] | None = None,
        expected_agent_session_id: str | None = None,
        expected_conversation_scope: str | None = None,
        strict_scope: bool = False,
    ) -> MemeoMemoryFrame:
        cfg = config or MemeoMemoryConfig()
        scoped_turns = _validate_recent_turn_scope(
            recent_turns,
            expected_agent_session_id=expected_agent_session_id,
            expected_conversation_scope=expected_conversation_scope,
            strict_scope=strict_scope,
        )
        rounds = _rounds_from_turn_memory(scoped_turns)
        latest_short_term = (
            tuple(rounds[-cfg.short_term_round_limit :])
            if cfg.short_term_enabled and cfg.short_term_round_limit > 0
            else tuple()
        )
        compression_window = (
            tuple(rounds[-cfg.summary_window_round_limit :])
            if cfg.summary_enabled and len(rounds) >= cfg.summary_window_round_limit
            else tuple()
        )
        summary = (
            _compress_rounds(compression_window, cfg.compressed_token_limit)
            if cfg.summary_enabled
            else ""
        )
        prompt_parts: list[str] = []
        if summary:
            prompt_parts.append(f"Compressed summary:\n{summary}")
        if cfg.short_term_enabled and latest_short_term:
            prompt_parts.append(f"Short-term memory:\n{_render_rounds(latest_short_term)}")
        prompt_memory = "\n\n".join(prompt_parts)

        slots = tuple(
            MemeoLongTermSlot(
                memory_key=str(item.get("memory_key") or "MEM0_VECTOR_SLOT"),
                status=str(item.get("status") or "RESERVED"),
                content=str(item.get("content") or ""),
                enabled=cfg.long_term_enabled,
                prompt_included=False,
            )
            for item in (long_term_preview or [])
        )

        return MemeoMemoryFrame(
            memory_modes=cfg,
            short_term_rounds=latest_short_term,
            compression_window_turns=tuple(item.turn_no for item in compression_window),
            compressed_summary=summary,
            compressed_summary_estimated_tokens=_estimate_tokens(summary),
            prompt_memory=prompt_memory,
            prompt_memory_estimated_tokens=_estimate_tokens(prompt_memory),
            long_term_slots=slots,
        )


def _validate_recent_turn_scope(
    recent_turns: list[dict[str, Any]],
    *,
    expected_agent_session_id: str | None,
    expected_conversation_scope: str | None,
    strict_scope: bool,
) -> list[dict[str, Any]]:
    if not strict_scope:
        return recent_turns
    if not _has_text(expected_agent_session_id):
        raise MemoryScopeViolation("expected_agent_session_id is required in strict scope")

    accepted: list[dict[str, Any]] = []
    for index, item in enumerate(recent_turns):
        if not isinstance(item, dict):
            raise MemoryScopeViolation(f"recent_turns[{index}] must be an object")
        agent_session_id = item.get("agent_session_id")
        if not _has_text(agent_session_id):
            raise MemoryScopeViolation(f"recent_turns[{index}] missing agent_session_id")
        if str(agent_session_id) != expected_agent_session_id:
            raise MemoryScopeViolation(
                "agent_session_id mismatch: "
                f"expected {expected_agent_session_id}, got {agent_session_id}"
            )
        if expected_conversation_scope is not None:
            conversation_scope = item.get("conversation_scope")
            if not _has_text(conversation_scope):
                raise MemoryScopeViolation(f"recent_turns[{index}] missing conversation_scope")
            if str(conversation_scope) != expected_conversation_scope:
                raise MemoryScopeViolation(
                    "conversation_scope mismatch: "
                    f"expected {expected_conversation_scope}, got {conversation_scope}"
                )
        accepted.append(item)
    return accepted


def _has_text(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _rounds_from_turn_memory(recent_turns: list[dict[str, Any]]) -> list[MemeoMemoryRound]:
    grouped: dict[int, list[MemeoMemoryMessage]] = {}
    for item in recent_turns:
        if not isinstance(item, dict):
            continue
        try:
            turn_no = int(item.get("turn_no") or 0)
        except (TypeError, ValueError):
            continue
        if turn_no < 1:
            continue
        messages = grouped.setdefault(turn_no, [])
        answer_role = item.get("answer_role")
        answer_content = item.get("answer_content")
        if answer_role and answer_content:
            messages.append(
                MemeoMemoryMessage(role=str(answer_role), content=str(answer_content))
            )
        agent_role = item.get("agent_role")
        agent_response = item.get("agent_response")
        if agent_role and agent_response:
            messages.append(
                MemeoMemoryMessage(role=str(agent_role), content=str(agent_response))
            )
    return [
        MemeoMemoryRound(turn_no=turn_no, messages=tuple(messages))
        for turn_no, messages in sorted(grouped.items())
        if messages
    ]


def _compress_rounds(rounds: tuple[MemeoMemoryRound, ...], token_limit: int) -> str:
    rendered = _render_rounds(rounds)
    if _estimate_tokens(rendered) <= token_limit:
        return rendered
    budget_chars = max(4, token_limit * 4)
    fragments: list[str] = []
    remaining = budget_chars
    for line in reversed(rendered.splitlines()):
        if remaining <= 0:
            break
        line_with_break = line if not fragments else line + "\n"
        if len(line_with_break) <= remaining:
            fragments.insert(0, line)
            remaining -= len(line_with_break)
            continue
        if not fragments:
            fragments.insert(0, line[:remaining])
        break
    compressed = "\n".join(fragments).strip()
    if _estimate_tokens(compressed) > token_limit:
        compressed = compressed[-budget_chars:]
    return compressed


def _render_rounds(rounds: tuple[MemeoMemoryRound, ...]) -> str:
    lines: list[str] = []
    for round_item in rounds:
        lines.append(f"Round {round_item.turn_no}:")
        for message in round_item.messages:
            lines.append(f"- {message.role}: {message.content}")
    return "\n".join(lines).strip()


def _estimate_tokens(text: str) -> int:
    if not text:
        return 0
    return max(1, (len(text) + 3) // 4)
