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
            recommendation = "NOT_ADMISSIBLE"
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
        risk_signals = (
            [analysis.risk_level.value]
            if analysis.risk_level.value in {"HIGH", "CRITICAL"}
            else []
        )
        return DisputeIntakeResult(
            admissible=analysis.potential_dispute,
            admission_recommendation=recommendation,
            dispute_type=analysis.dispute_type,
            initiator_role=request.initiator_role,
            order_reference=request.order_reference,
            after_sales_reference=request.after_sales_reference,
            logistics_reference=request.logistics_reference,
            party_claims=[
                {
                    "party": request.initiator_role,
                    "claim_text": analysis.normalized_description,
                    "source_ref": request.submission_id,
                }
            ],
            requested_outcome=_requested_outcome(request.raw_text),
            missing_initial_fields=analysis.missing_slots,
            initial_risk_signals=risk_signals,
            confidence=confidence,
            next_step=next_step,
            room_utterance=_room_utterance(recommendation),
        )


def _requested_outcome(text: str) -> str:
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


def _room_utterance(recommendation: str) -> str:
    """Return presentation copy, never the immutable structured decision."""

    if recommendation == "ACCEPTED":
        return "我已完成初步核对，这项请求建议作为履约争端受理并上报。"
    if recommendation == "NEED_MORE_INFO":
        return "这项请求可能构成履约争端，还需要补充少量关键信息。"
    return "当前信息不符合履约争端受理范围，我会引导你前往合适的处理入口。"
