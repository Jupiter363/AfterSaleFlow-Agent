from __future__ import annotations

from functools import reduce

import pytest
from hypothesis import given, strategies as st

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.reducers import KeyedReducerConflict, merge_keyed_json


JSON_SCALARS = st.one_of(
    st.none(),
    st.booleans(),
    st.integers(min_value=-1_000_000, max_value=1_000_000),
    st.integers(min_value=-1_000_000, max_value=1_000_000).map(float),
    st.just(-0.0),
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

    left_associative = merge_keyed_json(merge_keyed_json(left, middle), right)
    right_associative = merge_keyed_json(left, merge_keyed_json(middle, right))

    assert _strict_shape(left_associative) == _strict_shape(right_associative)
    assert canonical_sha256(left_associative) == canonical_sha256(right_associative)


@given(PATCHES, PATCHES)
def test_keyed_reducer_is_completion_order_independent(a, b) -> None:
    left = {f"left_{key}": value for key, value in a.items()}
    right = {f"right_{key}": value for key, value in b.items()}

    forward = merge_keyed_json(left, right)
    reverse = merge_keyed_json(right, left)

    assert _strict_shape(forward) == _strict_shape(reverse)
    assert canonical_sha256(forward) == canonical_sha256(reverse)
    assert list(forward) == sorted({*left, *right})


@given(PATCHES)
def test_identical_replay_is_idempotent(values) -> None:
    once = merge_keyed_json(None, values)
    twice = merge_keyed_json(once, values)

    assert _strict_shape(twice) == _strict_shape(once)
    assert canonical_sha256(twice) == canonical_sha256(once)


@given(st.integers(min_value=-1_000_000, max_value=1_000_000))
def test_jcs_equivalent_numbers_and_nested_values_have_one_strict_form(number: int) -> None:
    first = {
        "result": {
            "z_values": [float(number), -0.0],
            "a_nested": {"count": float(number)},
        }
    }
    equivalent = {
        "result": {
            "a_nested": {"count": number},
            "z_values": [number, 0],
        }
    }

    forward = merge_keyed_json(first, equivalent)
    reverse = merge_keyed_json(equivalent, first)
    left_associative = merge_keyed_json(merge_keyed_json(first, equivalent), first)
    right_associative = merge_keyed_json(first, merge_keyed_json(equivalent, first))

    expected = {
        "result": {
            "a_nested": {"count": number},
            "z_values": [number, 0],
        }
    }
    assert _strict_shape(forward) == _strict_shape(expected)
    assert _strict_shape(reverse) == _strict_shape(expected)
    assert _strict_shape(left_associative) == _strict_shape(right_associative)
    assert canonical_sha256(forward) == canonical_sha256(reverse)
    assert canonical_sha256(left_associative) == canonical_sha256(right_associative)
    assert type(forward["result"]["a_nested"]["count"]) is int
    assert type(forward["result"]["z_values"][0]) is int
    assert type(forward["result"]["z_values"][1]) is int


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
    for left, right in ((True, 1), (1, True)):
        with pytest.raises(KeyedReducerConflict) as captured:
            merge_keyed_json({"result": left}, {"result": right})
        assert "True" not in str(captured.value)


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


def _strict_shape(value):
    if isinstance(value, dict):
        return (dict, tuple((key, _strict_shape(item)) for key, item in value.items()))
    if isinstance(value, list):
        return (list, tuple(_strict_shape(item) for item in value))
    return (type(value), value)
