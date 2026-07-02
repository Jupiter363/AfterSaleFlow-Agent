"""Offline-only final Evaluation Agent."""

from __future__ import annotations

from typing import Any

from app.agents.profiles import final_agent_profiles
from app.schemas import EvaluationAnalysisResult


class EvaluationAgent:
    def __init__(self, workflow: Any) -> None:
        self.profile = final_agent_profiles()["evaluation_agent"]
        self._workflow = workflow

    def analyze(
        self,
        request: Any,
        trace_context: Any,
        *,
        offline: bool,
    ) -> EvaluationAnalysisResult:
        if not offline:
            raise PermissionError("evaluation agent is offline-only")
        if not self.profile.authorizes_case_state(request.case_status):
            raise PermissionError("evaluation agent requires a closed case")
        result = EvaluationAnalysisResult.model_validate(
            self._workflow.analyze(request, trace_context)
        )
        if result.online_case_mutated or result.automatic_changes_applied:
            raise PermissionError("evaluation agent cannot mutate online state")
        return result
