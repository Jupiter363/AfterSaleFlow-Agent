"""Final Dispute Intake Officer identity over the validated intake workflow."""

from __future__ import annotations

from typing import Any

from app.agents.profiles import final_agent_profiles
from app.schemas import (
    DisputeIntakeRequest,
    DisputeIntakeResult,
    IntakeAnalysisOutput,
    IntakeAnalyzeRequest,
)


class DisputeIntakeOfficer:
    def __init__(self, workflow: Any) -> None:
        self.profile = final_agent_profiles()["dispute_intake_officer"]
        self._workflow = workflow

    def analyze(
        self,
        request: DisputeIntakeRequest,
        trace_context: Any,
        *,
        case_state: str,
    ) -> DisputeIntakeResult:
        if not self.profile.authorizes_case_state(case_state):
            raise PermissionError(
                f"intake officer cannot run in case state {case_state}"
            )
        legacy_request = IntakeAnalyzeRequest(
            order_id=request.order_reference,
            after_sale_id=request.after_sales_reference,
            user_id=f"USER_{request.submission_id}",
            merchant_id=f"MERCHANT_{request.submission_id}",
            description=request.raw_text,
            attachment_ids=request.attachments,
            channel=request.channel,
        )
        analysis = IntakeAnalysisOutput.model_validate(
            self._workflow.analyze(legacy_request, trace_context)
        )
        if not analysis.potential_dispute:
            recommendation = "TRANSFERRED"
            next_step = "TRANSFER"
            confidence = 0.7
        elif analysis.missing_slots:
            recommendation = "NEED_MORE_INFO"
            next_step = "REQUEST_MORE_INFO"
            confidence = 0.6
        else:
            recommendation = "ACCEPTED"
            next_step = "BUILD_DOSSIER"
            confidence = 0.85
        return DisputeIntakeResult(
            is_potential_dispute=analysis.potential_dispute,
            admissibility_recommendation=recommendation,
            dispute_type=analysis.dispute_type,
            initiator=request.initiator_role,
            claims=[
                {
                    "party": request.initiator_role,
                    "claim_text": analysis.normalized_description,
                    "source_ref": request.submission_id,
                }
            ],
            requested_remedy=_requested_remedy(request.raw_text),
            missing_initial_fields=analysis.missing_slots,
            risk_signals=(
                [analysis.risk_level.value]
                if analysis.risk_level.value in {"HIGH", "CRITICAL"}
                else []
            ),
            confidence=confidence,
            next_step=next_step,
        )


def _requested_remedy(text: str) -> str:
    normalized = text.casefold()
    if any(value in normalized for value in ("reject refund", "拒绝退款")):
        return "REJECT_REFUND"
    if any(value in normalized for value in ("refund", "退款")):
        return "REFUND"
    if any(
        value in normalized
        for value in ("replacement", "replace", "reship", "补发", "换货")
    ):
        return "REPLACEMENT"
    if any(value in normalized for value in ("return", "退货")):
        return "RETURN"
    return "UNKNOWN"
