from __future__ import annotations

from functools import reduce

import pytest

from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.reducers import merge_keyed_hearing_results


def test_keyed_reducer_is_associative_order_independent_and_replay_idempotent() -> None:
    patches = [
        {"EVIDENCE_c": {"evidence_id": "EVIDENCE_c", "score": 3}},
        {"EVIDENCE_a": {"score": 1, "evidence_id": "EVIDENCE_a"}},
        {"EVIDENCE_b": {"evidence_id": "EVIDENCE_b", "score": 2}},
    ]

    forward = reduce(merge_keyed_hearing_results, patches, {})
    reverse = reduce(merge_keyed_hearing_results, reversed(patches), {})
    grouped = merge_keyed_hearing_results(
        merge_keyed_hearing_results(patches[0], patches[1]),
        patches[2],
    )

    assert forward == reverse == grouped
    assert list(forward) == ["EVIDENCE_a", "EVIDENCE_b", "EVIDENCE_c"]
    assert merge_keyed_hearing_results(forward, patches[1]) == forward


def test_same_stable_key_with_another_canonical_payload_fails_closed() -> None:
    with pytest.raises(HearingGraphContractError, match="HEARING_REDUCER_KEY_CONFLICT"):
        merge_keyed_hearing_results(
            {"EVIDENCE_a": {"evidence_id": "EVIDENCE_a", "score": 1}},
            {"EVIDENCE_a": {"evidence_id": "EVIDENCE_a", "score": 2}},
        )


def test_reducer_returns_detached_canonical_json() -> None:
    payload = {"EVIDENCE_a": {"evidence_id": "EVIDENCE_a", "values": [1, 2]}}

    reduced = merge_keyed_hearing_results(None, payload)
    payload["EVIDENCE_a"]["values"].append(3)

    assert reduced["EVIDENCE_a"]["values"] == [1, 2]


@pytest.mark.parametrize("key", ["", " contains-space", "x" * 129])
def test_reducer_rejects_unbounded_or_unstable_keys(key: str) -> None:
    with pytest.raises(HearingGraphContractError, match="HEARING_REDUCER_KEY_INVALID"):
        merge_keyed_hearing_results(None, {key: {"value": 1}})
