"""Composition root for the six final internal Agent APIs."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


class AnalyzeAgent(Protocol):
    def analyze(self, *args: Any, **kwargs: Any) -> Any: ...


class EvidenceAgent(Protocol):
    def build(self, *args: Any, **kwargs: Any) -> Any: ...


class HearingAgent(Protocol):
    def run_stage(self, *args: Any, **kwargs: Any) -> Any: ...


class DeliberationAgent(Protocol):
    def run(self, *args: Any, **kwargs: Any) -> Any: ...


class ReviewCopilotAgent(Protocol):
    def query(self, *args: Any, **kwargs: Any) -> Any: ...


@dataclass(frozen=True)
class FinalAgentServices:
    intake: AnalyzeAgent
    evidence: EvidenceAgent
    hearing: HearingAgent
    deliberation: DeliberationAgent
    review_copilot: ReviewCopilotAgent
    evaluation: AnalyzeAgent
