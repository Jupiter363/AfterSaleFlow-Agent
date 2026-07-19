from __future__ import annotations

from functools import reduce

import pytest
from hypothesis import given, strategies as st

from app.graph_runtime.reducers import KeyedReducerConflict, merge_keyed_json


JSON_SCALARS = st.one_of(
    st.none(),
    st.booleans(),
    st.integers(min_value=-1_000_000, max_value=1_000_000),
    st.text(max_size=32),
)
JSON_VALUES = st.recursive(
    JSON_SCALARS,
    lambda children: st.one_of(
        st.lists(children, max_size=4),
        st.dictionaries(st.text(min_size=1, max_size=8), children, max_size=4),
    ),
    max_leaves=12,
)
KEYS = st.from_regex(r"[a-z][a-z0-9_]{0,10}", fullmatch=True)
PATCHES = st.dictionaries(KEYS, JSON_VALUES, max_size=8)


@given(PATCHES, PATCHES, PATCHES)
def test_keyed_reducer_is_associative_for_compatible_maps(a, b, c) -> None:
    left = {f"a_{key}": value for key, value in a.items()}
    middle = {f"b_{key}": value for key, value in b.items()}
    right = {f"c_{key}": value for key, value in c.items()}

    assert merge_keyed_json(merge_keyed_json(left, middle), right) == merge_keyed_json(
        left, merge_keyed_json(middle, right)
    )


@given(PATCHES, PATCHES)
def test_keyed_reducer_is_completion_order_independent(a, b) -> None:
    left = {f"left_{key}": value for key, value in a.items()}
    right = {f"right_{key}": value for key, value in b.items()}

    assert merge_keyed_json(left, right) == merge_keyed_json(right, left)
    assert list(merge_keyed_json(left, right)) == sorted({*left, *right})


@given(PATCHES)
def test_identical_replay_is_idempotent(values) -> None:
    once = merge_keyed_json(None, values)
    twice = merge_keyed_json(once, values)

    assert twice == once


def test_duplicate_key_conflict_is_not_last_write_wins_and_does_not_leak_payload() -> None:
    with pytest.raises(KeyedReducerConflict) as captured:
        merge_keyed_json(
            {"work_1": {"private_text": "first"}},
            {"work_1": {"private_text": "second"}},
            namespace="work_results",
        )

    error = captured.value
    assert error.namespace == "work_results"
    assert error.key == "work_1"
    assert len(error.existing_sha256) == 64
    assert len(error.incoming_sha256) == 64
    assert "first" not in str(error)
    assert "second" not in str(error)


def test_canonical_json_distinguishes_boolean_from_integer() -> None:
    with pytest.raises(KeyedReducerConflict):
        merge_keyed_json({"result": True}, {"result": 1})


def test_merge_detaches_values_from_mutable_inputs() -> None:
    original = {"work": {"values": [1]}}
    merged = merge_keyed_json(None, original)

    original["work"]["values"].append(2)

    assert merged == {"work": {"values": [1]}}


def test_many_patches_have_one_deterministic_fold() -> None:
    patches = [
        {"b": {"value": 2}},
        {"a": {"value": 1}},
        {"c": {"value": 3}},
        {"a": {"value": 1}},
    ]

    forward = reduce(merge_keyed_json, patches, {})
    reverse = reduce(merge_keyed_json, reversed(patches), {})

    assert forward == reverse
    assert list(forward) == ["a", "b", "c"]
