from __future__ import annotations

from langgraph.checkpoint.memory import InMemorySaver
import pytest

from app.graphs.outcome.contracts import (
    EMPTY_OUTCOME_REVIEW_TOOL_POLICY,
    OUTCOME_REVIEW_GRAPH_IDENTITY,
)
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.graph import build_outcome_review_v1_graph
from app.graphs.outcome.runtime import build_outcome_review_graph_session
from app.graphs.outcome.state import (
    OutcomeReviewPrivateCommand,
    new_outcome_review_state,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest
from tests.graphs.outcome.conftest import ACTOR_HASH


def _session(
    *,
    command: OutcomeReviewPrivateCommand,
    request: ReviewCopilotRequest,
    answer: ReviewCopilotAnswer,
    validator,
    saver: InMemorySaver | None = None,
):
    return build_outcome_review_graph_session(
        command=command,
        request=request,
        reviewer_actor_hash=ACTOR_HASH,
        answerer=lambda _request: answer,
        validate_answer=validator,
        checkpointer=saver or InMemorySaver(),
        runtime_mode="JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
        java_signature_verified=True,
        synthetic_only=True,
        contains_real_case_or_party_data=False,
    )


def test_topology_is_private_three_node_advisory_chain() -> None:
    graph = build_outcome_review_v1_graph()

    assert OUTCOME_REVIEW_GRAPH_IDENTITY == "outcome/review.v1"
    assert set(graph.nodes) == {
        "validate_scope_packet",
        "compose_advisory",
        "project_proposal",
    }
    assert graph.edges == {
        ("__start__", "validate_scope_packet"),
        ("validate_scope_packet", "compose_advisory"),
        ("compose_advisory", "project_proposal"),
        ("project_proposal", "__end__"),
    }
    assert EMPTY_OUTCOME_REVIEW_TOOL_POLICY == ()


def test_graph_returns_only_non_final_advisory(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    session = _session(
        command=private_command,
        request=review_request,
        answer=review_answer,
        validator=answer_validator,
    )

    result = session.query(review_request)

    assert result == review_answer
    assert result.approval_performed is False
    assert result.execution_triggered is False
    assert result.is_final_decision is False


def test_initial_checkpoint_state_contains_only_bounded_bindings(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
) -> None:
    state = new_outcome_review_state(command=private_command, request=review_request)
    serialized = repr(state)

    assert set(state) == {
        "schema_version",
        "graph_identity",
        "version_pins",
        "command_binding",
        "scope_binding",
        "request_hash",
        "question_hash",
        "status",
        "cognitive_revision",
    }
    assert review_request.question not in serialized
    assert review_request.frozen_packet["summary"] not in serialized
    assert "packet body" not in serialized


@pytest.mark.parametrize(
    ("mode", "signature", "synthetic", "real_data", "error"),
    [
        ("DISABLED", True, True, False, "OUTCOME_REVIEW_RUNTIME_DISABLED"),
        (
            "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
            False,
            True,
            False,
            "OUTCOME_REVIEW_SYNTHETIC_AUTHORITY_REQUIRED",
        ),
        (
            "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
            True,
            False,
            False,
            "OUTCOME_REVIEW_SYNTHETIC_AUTHORITY_REQUIRED",
        ),
        (
            "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
            True,
            True,
            True,
            "OUTCOME_REVIEW_SYNTHETIC_AUTHORITY_REQUIRED",
        ),
    ],
)
def test_runtime_rejects_disabled_unsigned_or_real_data_admission(
    mode: str,
    signature: bool,
    synthetic: bool,
    real_data: bool,
    error: str,
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    with pytest.raises(OutcomeReviewContractError, match=error):
        build_outcome_review_graph_session(
            command=private_command,
            request=review_request,
            reviewer_actor_hash=ACTOR_HASH,
            answerer=lambda _request: review_answer,
            validate_answer=answer_validator,
            checkpointer=InMemorySaver(),
            runtime_mode=mode,  # type: ignore[arg-type]
            java_signature_verified=signature,
            synthetic_only=synthetic,
            contains_real_case_or_party_data=real_data,
        )


def test_actor_switch_is_rejected_before_model_execution(
    private_command: OutcomeReviewPrivateCommand,
    review_request: ReviewCopilotRequest,
    review_answer: ReviewCopilotAnswer,
    answer_validator,
) -> None:
    called = False

    def answerer(_request):
        nonlocal called
        called = True
        return review_answer

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_REVIEW_REVIEWER_BINDING_MISMATCH",
    ):
        build_outcome_review_graph_session(
            command=private_command,
            request=review_request,
            reviewer_actor_hash="b" * 64,
            answerer=answerer,
            validate_answer=answer_validator,
            checkpointer=InMemorySaver(),
            runtime_mode="JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
            java_signature_verified=True,
            synthetic_only=True,
            contains_real_case_or_party_data=False,
        )
    assert called is False
