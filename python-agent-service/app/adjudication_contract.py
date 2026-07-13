"""C6 V2 output contract derived from a validated jury review report."""

from __future__ import annotations

from functools import lru_cache
from typing import Annotated, Any

from pydantic import Field, create_model

from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    JuryReviewResponse,
)


_MANDATORY_SEVERITIES = {"HIGH", "BLOCKER"}


def mandatory_c6_review_responses(
    request: dict[str, Any],
) -> tuple[tuple[str, str], ...]:
    """Return the dimension/severity pairs that C6 V2 must answer.

    ``HearingWorkflow`` validates the formal report before the graph starts.
    This helper deliberately derives only the compact response contract and
    never invents review content or a draft response.
    """

    hearing_context = request.get("hearing_context")
    if not isinstance(hearing_context, dict) or not (
        hearing_context.get("final_convergence")
        or hearing_context.get("must_produce_final_plan")
    ):
        return ()
    courtroom_context = hearing_context.get("courtroom_context")
    if not isinstance(courtroom_context, dict):
        return ()
    formal_report = courtroom_context.get("jury_review_report")
    if not isinstance(formal_report, dict):
        return ()
    payload = formal_report.get("payload")
    if not isinstance(payload, dict):
        return ()
    findings = payload.get("findings")
    if not isinstance(findings, list):
        return ()

    requirements: list[tuple[str, str]] = []
    for finding in findings:
        if not isinstance(finding, dict):
            continue
        dimension = finding.get("dimension")
        severity = finding.get("severity")
        if not isinstance(dimension, str) or not isinstance(severity, str):
            continue
        if (
            severity in _MANDATORY_SEVERITIES
            or finding.get("requires_revision") is True
        ):
            requirements.append((dimension, severity))
    return tuple(requirements)


@lru_cache(maxsize=6)
def _final_c6_output_type(
    required_response_count: int,
) -> type[AdjudicationDraftOutput]:
    """Build a strict provider schema with the exact mandatory item count."""

    response_list_type = Annotated[
        list[JuryReviewResponse],
        Field(
            min_length=required_response_count,
            max_length=required_response_count,
        ),
    ]
    draft_type = create_model(
        f"FinalConvergenceAdjudicationDraft{required_response_count}",
        __base__=AdjudicationDraft,
        jury_review_responses=(response_list_type, ...),
    )
    return create_model(
        f"FinalConvergenceAdjudicationDraftOutput{required_response_count}",
        __base__=AdjudicationDraftOutput,
        draft=(draft_type, ...),
    )


def c6_output_type_for_request(
    request: dict[str, Any],
) -> type[AdjudicationDraftOutput]:
    """Select the stricter C6 schema only when responses are mandatory."""

    required_count = len(mandatory_c6_review_responses(request))
    if required_count == 0:
        return AdjudicationDraftOutput
    return _final_c6_output_type(required_count)


def trusted_c6_review_contract(request: dict[str, Any]) -> dict[str, object]:
    """Expose a compact, prevalidated response manifest to the C6 prompt."""

    requirements = mandatory_c6_review_responses(request)
    if not requirements:
        return {}
    return {
        "c6_v2_mandatory_review_responses": {
            "required_count": len(requirements),
            "required_items": [
                {"dimension": dimension, "severity": severity}
                for dimension, severity in requirements
            ],
        }
    }
