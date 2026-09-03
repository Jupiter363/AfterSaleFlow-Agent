from __future__ import annotations

import random
from copy import deepcopy
from functools import reduce

import pytest

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence.contracts import EvidenceGraphContractError
from app.graphs.evidence.reducers import (
    assessment_refs_for_manifest,
    merge_evidence_assessments,
    require_exact_assessment_coverage,
)


def _assessments(admission, assessment_factory) -> dict[str, dict]:
    manifest = admission.manifest
    return {
        item["evidence_id"]: assessment_factory(
            {
                "command_binding": manifest["command_binding"],
                "thread_id": manifest["thread_id"],
                "manifest_id": manifest["manifest_id"],
                "manifest_hash": manifest["manifest_hash"],
                "actor_scope_hash": manifest["actor_scope_hash"],
                "profile_versions": manifest["profile_versions"],
                "item": item,
            }
        )
        for item in manifest["items"]
    }


def test_keyed_reduction_is_associative_order_independent_and_replay_idempotent(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(100)
    values = _assessments(admission, assessment_factory)
    patches = [{key: values[key]} for key in values]
    shuffled = list(patches)
    random.Random(731).shuffle(shuffled)

    canonical = reduce(merge_evidence_assessments, patches, {})
    randomized = reduce(merge_evidence_assessments, shuffled, {})
    midpoint = len(shuffled) // 2
    associative = merge_evidence_assessments(
        reduce(merge_evidence_assessments, shuffled[:midpoint], {}),
        reduce(merge_evidence_assessments, shuffled[midpoint:], {}),
    )

    assert canonical == randomized == associative
    assert list(canonical) == admission.manifest["ordered_item_keys"]
    assert merge_evidence_assessments(canonical, randomized) == canonical


def test_duplicate_identical_key_is_idempotent_and_returns_detached_json(
    admission,
    assessment_factory,
) -> None:
    values = _assessments(admission, assessment_factory)
    evidence_id = next(iter(values))

    merged = merge_evidence_assessments(values, {evidence_id: deepcopy(values[evidence_id])})
    values[evidence_id]["limitations"].append("CALLER_MUTATION")

    assert merged[evidence_id]["limitations"] == ["SYNTHETIC_FIXTURE_ONLY"]


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        ("value", "EVIDENCE_ASSESSMENT_REDUCER_CONFLICT"),
        ("hash", "EVIDENCE_ASSESSMENT_HASH_MISMATCH"),
        ("key", "EVIDENCE_ASSESSMENT_REDUCER_KEY_INVALID"),
    ],
)
def test_conflicting_value_hash_or_key_fails_closed(
    admission,
    assessment_factory,
    mutation: str,
    code: str,
) -> None:
    original = _assessments(admission, assessment_factory)
    evidence_id = next(iter(original))
    conflict = deepcopy(original[evidence_id])
    incoming_key = evidence_id
    if mutation == "value":
        conflict["confidence"] = 0.01
        conflict.pop("assessment_hash")
        conflict["assessment_hash"] = canonical_sha256(conflict)
    elif mutation == "hash":
        conflict["confidence"] = 0.01
    else:
        incoming_key = "EVIDENCE_DIFFERENT_KEY"

    with pytest.raises(EvidenceGraphContractError, match=code):
        merge_evidence_assessments(original, {incoming_key: conflict})


def test_proposal_refs_require_exact_terminal_manifest_coverage(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(8)
    values = _assessments(admission, assessment_factory)
    ordered = admission.manifest["ordered_item_keys"]
    missing = dict(values)
    missing.pop(ordered[-1])

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_COMPLETE_COVERAGE_REQUIRED"):
        assessment_refs_for_manifest(missing, ordered)

    extra = dict(values)
    extra_value = deepcopy(values[ordered[-1]])
    extra_value["evidence_id"] = "EVIDENCE_OUTSIDE_MANIFEST"
    extra_value.pop("assessment_hash")
    extra_value["assessment_hash"] = canonical_sha256(extra_value)
    extra[extra_value["evidence_id"]] = extra_value
    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_COMPLETE_COVERAGE_REQUIRED"):
        require_exact_assessment_coverage(extra, ordered)

    refs = assessment_refs_for_manifest(values, ordered)
    assert [ref["evidence_id"] for ref in refs] == ordered
