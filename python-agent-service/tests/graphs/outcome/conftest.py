from __future__ import annotations

from collections.abc import Callable

import pytest

from app.agents.review_copilot import ReviewCopilot
from app.graphs.outcome.state import (
    OutcomeReviewPrivateCommand,
    packet_hash,
    question_hash,
    request_hash,
    version_pins,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest, ReviewStatement


ACTOR_HASH = "a" * 64


@pytest.fixture
def review_request() -> ReviewCopilotRequest:
    return ReviewCopilotRequest(
        review_id="REVIEW_SYNTHETIC_1",
        case_id="CASE_SYNTHETIC_1",
        review_packet_version=3,
        reviewer_role="PLATFORM_REVIEWER",
        question="Which frozen facts support the draft?",
        available_fact_refs=["FACT_TRACKING"],
        available_rule_refs=["RULE_REFUND_1"],
        available_draft_refs=["DRAFT_V2"],
        available_deliberation_refs=["DELIBERATION_1"],
        frozen_packet={
            "synthetic": True,
            "summary": "private packet body must never enter checkpoint state",
        },
    )


@pytest.fixture
def review_answer() -> ReviewCopilotAnswer:
    return ReviewCopilotAnswer(
        answer="The draft cites the tracking fact and refund rule.",
        statements=[
            ReviewStatement(
                kind="FACT",
                text="The frozen packet includes a tracking fact.",
                refs=["FACT_TRACKING"],
            ),
            ReviewStatement(
                kind="SUGGESTION",
                text="Inspect the rule-to-draft mapping.",
                refs=["RULE_REFUND_1", "DRAFT_V2"],
            ),
        ],
        fact_refs=["FACT_TRACKING"],
        rule_refs=["RULE_REFUND_1"],
        draft_refs=["DRAFT_V2"],
        deliberation_refs=["DELIBERATION_1"],
        uncertainties=["The packet does not prove physical handover."],
        suggested_review_focus=["Confirm the cited rule version."],
    )


@pytest.fixture
def private_command(
    review_request: ReviewCopilotRequest,
) -> OutcomeReviewPrivateCommand:
    return OutcomeReviewPrivateCommand(
        command_id="COMMAND_SYNTHETIC_1",
        thread_id="THREAD_SYNTHETIC_1",
        tenant_surrogate="TENANT_SYNTHETIC_1",
        case_id=review_request.case_id,
        review_task_id=review_request.review_id,
        reviewer_actor_hash=ACTOR_HASH,
        packet_id="PACKET_SYNTHETIC_1",
        frozen_packet_ref="urn:synthetic-outcome:review-packet-1",
        frozen_packet_hash=packet_hash(review_request),
        frozen_packet_version=review_request.review_packet_version,
        action_hash="b" * 64,
        review_task_status="ASSIGNED",
        review_deadline="2026-07-24T12:00:00Z",
        authorized_artifact_refs={
            "evidence_matrix": "urn:synthetic-outcome:evidence-matrix-1",
            "adjudication_draft": "urn:synthetic-outcome:draft-1",
        },
        room_epoch=7,
        process_revision=11,
        fencing_token=13,
        fact_refs=tuple(review_request.available_fact_refs),
        rule_refs=tuple(review_request.available_rule_refs),
        draft_refs=tuple(review_request.available_draft_refs),
        deliberation_refs=tuple(review_request.available_deliberation_refs),
        question_hash=question_hash(review_request),
        request_hash=request_hash(review_request),
        version_pins=version_pins(),
    )


@pytest.fixture
def answer_validator() -> Callable:
    copilot = ReviewCopilot(lambda _request: None)  # type: ignore[arg-type]
    return copilot.validate_answer
