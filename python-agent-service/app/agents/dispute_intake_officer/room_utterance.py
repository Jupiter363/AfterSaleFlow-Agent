"""Deterministic governance for Intake room utterances."""

from __future__ import annotations

from typing import Any


def phase_safe_room_utterance(
    room_utterance: str,
    snapshot: dict[str, Any],
) -> str:
    """Preserve the exact prompt-owned utterance for compatibility callers."""

    del snapshot
    return room_utterance
