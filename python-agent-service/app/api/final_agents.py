"""Composition root for the six final internal Agent APIs."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class FinalAgentServices:
    intake: Any
    evidence: Any
    hearing: Any
    deliberation: Any
    review_copilot: Any
    evaluation: Any
