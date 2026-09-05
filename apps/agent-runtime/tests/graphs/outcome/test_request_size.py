from __future__ import annotations

import json

from langgraph.checkpoint.memory import InMemorySaver
import pytest

from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.runtime import build_outcome_review_graph_session
from app.graphs.outcome.state import packet_hash, request_hash
from tests.graphs.outcome.conftest import ACTOR_HASH


# The existing signed room-object exchange admits up to 512 KiB. Its complete
# frozen input is distinct from the compact 32 KiB advisory output contract.
REQUEST_LIMIT = 512 * 1024


def _encoded(request):
    return json.dumps(
        request.model_dump(mode="json"), ensure_ascii=False, allow_nan=False,
        sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")


def _sized_request(request, size):
    request = request.model_copy(update={"frozen_packet": {"summary": "核验"}})
    padding = size - len(_encoded(request))
    request.frozen_packet["summary"] += "x" * padding
    assert len(_encoded(request)) == size
    return request


def _bound(command, request):
    return command.model_copy(update={
        "request_hash": request_hash(request), "frozen_packet_hash": packet_hash(request),
    })


def _session(command, request, answerer, validator):
    return build_outcome_review_graph_session(
        command=command, request=request, reviewer_actor_hash=ACTOR_HASH,
        answerer=answerer, validate_answer=validator, checkpointer=InMemorySaver(),
        runtime_mode="JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW",
        java_signature_verified=True, synthetic_only=True,
        contains_real_case_or_party_data=False,
    )


@pytest.mark.parametrize("size", [48_000, REQUEST_LIMIT])
def test_full_frozen_input_runs_without_widening_checkpoint_or_replay(
    size, private_command, review_request, review_answer, answer_validator,
):
    request = _sized_request(review_request, size)
    calls = []

    def answerer(actual):
        calls.append(actual)
        assert actual == request  # No truncation or substitution of signed material.
        return review_answer

    session = _session(_bound(private_command, request), request, answerer, answer_validator)
    assert session.run() == review_answer
    snapshot = session.graph.get_state(session._config())
    assert snapshot.values["status"] == "PROPOSED"
    assert len(repr(snapshot.values).encode("utf-8")) < 32_768
    assert request.frozen_packet["summary"] not in repr(snapshot.values)
    assert "advisory" not in snapshot.values and "projection" not in snapshot.values
    with pytest.raises(OutcomeReviewContractError, match="RESULT_ALREADY_PROJECTED"):
        session.run()
    assert calls == [request]


@pytest.mark.parametrize("size", [REQUEST_LIMIT + 1, REQUEST_LIMIT + 3])
def test_over_limit_input_rejects_by_utf8_bytes_before_model(
    size, private_command, review_request, review_answer, answer_validator,
):
    request = _sized_request(review_request, size)
    # Chinese content makes the character count smaller than the byte bound.
    assert len(_encoded(request).decode("utf-8")) <= REQUEST_LIMIT
    calls = []
    with pytest.raises(OutcomeReviewContractError, match="REQUEST_TOO_LARGE"):
        _session(_bound(private_command, request), request,
                 lambda actual: calls.append(actual) or review_answer, answer_validator)
    assert calls == []


@pytest.mark.parametrize("field,value", [
    ("request_hash", "f" * 64), ("frozen_packet_hash", "f" * 64),
    ("reviewer_actor_hash", "f" * 64), ("fact_refs", ("FOREIGN_FACT",)),
])
def test_larger_packet_preserves_exact_authority(
    field, value, private_command, review_request, review_answer, answer_validator,
):
    request = _sized_request(review_request, 48_000)
    command = _bound(private_command, request).model_copy(update={field: value})
    calls = []
    with pytest.raises(OutcomeReviewContractError, match="BINDING_MISMATCH"):
        _session(command, request, lambda actual: calls.append(actual) or review_answer,
                 answer_validator)
    assert calls == []


def test_larger_input_does_not_relax_advisory_output_limit(
    private_command, review_request, review_answer, answer_validator,
):
    request = _sized_request(review_request, 48_000)
    statement = review_answer.statements[0].model_copy(update={"text": "核验" * 300})
    answer = type(review_answer).model_validate({
        **review_answer.model_dump(), "statements": [statement.model_dump()] * 25,
    })
    session = _session(_bound(private_command, request), request, lambda _: answer,
                       answer_validator)
    with pytest.raises(OutcomeReviewContractError, match="RESULT_TOO_LARGE"):
        session.run()
