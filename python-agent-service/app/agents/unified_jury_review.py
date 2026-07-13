"""One governed jury-review Agent covering all review dimensions in one model call."""

from __future__ import annotations

from typing import Any

from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.schemas import (
    HearingRoundTurnRequest,
    HearingRoundTurnResult,
    JuryReviewDimension,
    JuryReviewReport,
)


_REQUIRED_DIMENSIONS = frozenset(JuryReviewDimension)


class UnifiedJuryReviewAgent:
    """Review the sealed hearing once without becoming a second adjudicator."""

    def __init__(self, model_runner: Any) -> None:
        self._model_runner = model_runner

    def review(
        self,
        request: HearingRoundTurnRequest,
        judge_result: HearingRoundTurnResult,
    ) -> JuryReviewReport:
        if not (request.final_round or request.round_no >= 3):
            raise ValueError("jury review is only available after the final hearing round")
        if self._model_runner is None:
            raise AgentServiceUnavailable("jury review model runner is not configured")
        reviewed_proposal = str(
            judge_result.final_proposed_resolution or ""
        ).strip()
        if not reviewed_proposal:
            raise AgentOutputSchemaError(
                "jury_review",
                "jury review requires the judge final proposed resolution V1",
            )

        generation = self._model_runner.invoke_structured(
            node_name="jury_review",
            case_data={
                "case_identity": {
                    "case_id": request.case_id,
                    "workflow_id": request.workflow_id,
                    "round_no": request.round_no,
                    "dossier_version": request.dossier_version,
                    "risk_level": request.risk_level,
                },
                "courtroom_context": request.courtroom_context,
                "sealed_party_submissions": [
                    item.model_dump(mode="json") for item in request.party_submissions
                ],
                "presiding_judge_round_result": judge_result.model_dump(
                    mode="json",
                    exclude={"jury_review_report"},
                ),
                "reviewed_proposal": reviewed_proposal,
                "review_focus_signal": list(judge_result.review_focus_signal),
                "required_dimensions": [item.value for item in JuryReviewDimension],
            },
            output_type=JuryReviewReport,
        )
        report = JuryReviewReport.model_validate(generation.value)
        dimensions = [finding.dimension for finding in report.findings]
        if len(set(dimensions)) != len(dimensions):
            raise AgentOutputSchemaError(
                "jury_review",
                "jury review returned duplicate evaluation dimensions",
            )
        if set(dimensions) != _REQUIRED_DIMENSIONS:
            raise AgentOutputSchemaError(
                "jury_review",
                "jury review did not cover every required evaluation dimension",
            )
        model = str(getattr(generation, "model", "") or report.model or "unknown")
        return report.model_copy(
            update={
                "model": model,
                "prompt_version": "unified-jury-review-v1",
                "review_focus_signal": list(judge_result.review_focus_signal),
                # 审核对象由代码锁定为法官 V1；模型只能评价，不能替换或改写。
                "reviewed_proposal": reviewed_proposal,
            }
        )
