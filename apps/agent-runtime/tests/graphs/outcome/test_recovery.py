from __future__ import annotations

from langgraph.checkpoint.memory import InMemorySaver
import pytest

from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.graph import build_outcome_review_v1_graph
from app.graphs.outcome.runtime import build_outcome_review_graph_session
from app.graphs.outcome.state import (
    OutcomeReviewInvocation,
    OutcomeReviewPrivateCommand,
    new_outcome_review_state,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest
from tests.graphs.outcome.conftest import ACTOR_HASH


def _build_session(
    *,
    command,
    request,
    answerer,
    validator,
    saver,
):
    return build_outcome_review_graph_session(
        command=command,
        request=request,
        reviewer_actor_hash=ACTOR_HASH,
        answerer=answerer,
        validate_answer=validator,
        checkpointer=saver,
        runtime_mode="JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
        java_signature_verified=True,
        synthetic_only=True,
        contains_real_case_or_party_data=False,
    )


def test_resume_reuses_exact_frozen_binding_and_checkpoint_has_no_sensitive_text(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    saver = InMemorySaver()
    calls = 0

    def crash_once(_request):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("synthetic model interruption")
        return review_answer

    session = _build_session(
        command=private_command,
        request=review_request,
        answerer=crash_once,
        validator=answer_validator,
        saver=saver,
    )
    with pytest.raises(RuntimeError, match="synthetic model interruption"):
        session.run()

    recovered = session.run()
    snapshot = session.graph.get_state(session._config())
    persisted = repr(snapshot.values)

    assert recovered == review_answer
    assert calls == 2
    assert snapshot.values["status"] == "PROPOSED"
    assert review_request.question not in persisted
    assert review_request.frozen_packet["summary"] not in persisted
    assert review_answer.answer not in persisted
    assert "advisory" not in snapshot.values
    assert "projection" not in snapshot.values


def test_same_thread_with_stale_fence_cannot_resume(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    saver = InMemorySaver()
    current = _build_session(
        command=private_command,
        request=review_request,
        answerer=lambda _request: review_answer,
        validator=answer_validator,
        saver=saver,
    )
    current.run()
    stale_command = private_command.model_copy(
        update={"fencing_token": private_command.fencing_token + 1}
    )
    stale = _build_session(
        command=stale_command,
        request=review_request,
        answerer=lambda _request: review_answer,
        validator=answer_validator,
        saver=saver,
    )

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_REVIEW_STALE_COMMAND_OR_FENCE",
    ):
        stale.run()


def test_changed_packet_body_cannot_silently_refresh_command(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    changed = review_request.model_copy(
        update={"frozen_packet": {"synthetic": True, "summary": "changed"}}
    )

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_REVIEW_FROZEN_PACKET_BINDING_MISMATCH",
    ):
        _build_session(
            command=private_command,
            request=changed,
            answerer=lambda _request: review_answer,
            validator=answer_validator,
            saver=InMemorySaver(),
        )


def test_resume_after_compose_fails_if_recomposition_changes_advisory(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    saver = InMemorySaver()
    graph = build_outcome_review_v1_graph().compile(
        checkpointer=saver,
        interrupt_after=["compose_advisory"],
    )
    config = {"configurable": {"thread_id": private_command.thread_id}}
    graph.invoke(
        new_outcome_review_state(command=private_command, request=review_request),
        config,
        context=OutcomeReviewInvocation(
            request=review_request,
            reviewer_actor_hash=ACTOR_HASH,
            answerer=lambda _request: review_answer,
            validate_answer=answer_validator,
        ),
        durability="sync",
    )
    changed = review_answer.model_copy(update={"answer": "A different advisory."})

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_REVIEW_RECOMPOSITION_HASH_MISMATCH",
    ):
        graph.invoke(
            None,
            config,
            context=OutcomeReviewInvocation(
                request=review_request,
                reviewer_actor_hash=ACTOR_HASH,
                answerer=lambda _request: changed,
                validate_answer=answer_validator,
            ),
            durability="sync",
        )
