"""Final AI Presiding Judge identity over the validated C1-C6 workflow."""

from __future__ import annotations

from typing import Any

from app.agents.profiles import final_agent_profiles
from app.schemas import (
    HearingAnalysisResult,
    HearingStageResult,
)


class PresidingJudge:
    def __init__(self, workflow: Any) -> None:
        self.profile = final_agent_profiles()["presiding_judge"]
        self._workflow = workflow

    def analyze(
        self,
        request: Any,
        trace_context: Any,
        *,
        case_state: str,
    ) -> HearingAnalysisResult:
        if not self.profile.authorizes_case_state(case_state):
            raise PermissionError(
                f"presiding judge cannot run in case state {case_state}"
            )
        result = HearingAnalysisResult.model_validate(
            self._workflow.analyze(request, trace_context)
        )
        draft = result.adjudication_draft.draft
        if draft.is_final_decision or not draft.requires_human_review:
            raise PermissionError("presiding judge output bypassed human review")
        return result

    def run_stage(
        self,
        request: Any,
        trace_context: Any,
        *,
        case_state: str,
    ) -> HearingStageResult:
        if not self.profile.authorizes_case_state(case_state):
            raise PermissionError(
                f"presiding judge cannot run in case state {case_state}"
            )
        return HearingStageResult.model_validate(
            self._workflow.run_stage(request, trace_context)
        )
