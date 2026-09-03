from __future__ import annotations

import json
import re
from collections.abc import Mapping, Sequence
from copy import deepcopy
from typing import Any, cast

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.evidence.contracts import EvidenceGraphContractError, JsonObject


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_TERMINAL_ASSESSMENT_STATUSES = frozenset({"COMPLETED", "NEEDS_REVIEW"})


def merge_evidence_assessments(
    left: Mapping[str, JsonObject] | None,
    right: Mapping[str, JsonObject] | None,
) -> dict[str, JsonObject]:
    """Reduce immutable, self-hashed assessments by exact Evidence ID."""

    merged: dict[str, tuple[bytes, JsonObject]] = {}
    for operand in (left, right):
        if operand is None:
            continue
        if not isinstance(operand, Mapping):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_INPUT_INVALID")
        for key, value in operand.items():
            canonical, normalized = _validated_assessment(key, value)
            existing = merged.get(key)
            if existing is not None and existing[0] != canonical:
                raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_CONFLICT")
            merged[key] = (canonical, normalized)
    return {key: deepcopy(merged[key][1]) for key in sorted(merged)}


def require_exact_assessment_coverage(
    assessments: Mapping[str, JsonObject] | None,
    ordered_item_keys: Sequence[str],
) -> dict[str, JsonObject]:
    """Return a canonical map only for exact, terminal manifest coverage."""

    if (
        isinstance(ordered_item_keys, (str, bytes))
        or not isinstance(ordered_item_keys, Sequence)
        or not ordered_item_keys
        or any(not isinstance(key, str) or not key for key in ordered_item_keys)
        or list(ordered_item_keys) != sorted(set(ordered_item_keys))
    ):
        raise EvidenceGraphContractError("EVIDENCE_MANIFEST_REDUCER_KEYS_INVALID")
    reduced = merge_evidence_assessments(None, assessments)
    if set(reduced) != set(ordered_item_keys):
        raise EvidenceGraphContractError("EVIDENCE_COMPLETE_COVERAGE_REQUIRED")
    if any(
        assessment.get("assessment_status") not in _TERMINAL_ASSESSMENT_STATUSES
        for assessment in reduced.values()
    ):
        raise EvidenceGraphContractError("EVIDENCE_COMPLETE_COVERAGE_REQUIRED")
    return reduced


def assessment_refs_for_manifest(
    assessments: Mapping[str, JsonObject] | None,
    ordered_item_keys: Sequence[str],
) -> list[JsonObject]:
    reduced = require_exact_assessment_coverage(assessments, ordered_item_keys)
    return [
        {
            "evidence_id": evidence_id,
            "assessment_status": cast(str, reduced[evidence_id]["assessment_status"]),
            "assessment_hash": cast(str, reduced[evidence_id]["assessment_hash"]),
        }
        for evidence_id in ordered_item_keys
    ]


def _validated_assessment(key: Any, value: Any) -> tuple[bytes, JsonObject]:
    if not isinstance(key, str) or not key or not isinstance(value, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_KEY_INVALID")
    try:
        canonical = canonicalize(value)
        normalized = json.loads(canonical)
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_NOT_CANONICAL_JSON") from error
    if not isinstance(normalized, dict) or normalized.get("evidence_id") != key:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_REDUCER_KEY_INVALID")
    assessment_hash = normalized.get("assessment_hash")
    if not isinstance(assessment_hash, str) or not _SHA256.fullmatch(assessment_hash):
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_HASH_INVALID")
    preimage = dict(normalized)
    preimage.pop("assessment_hash")
    if canonical_sha256(preimage) != assessment_hash:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_HASH_MISMATCH")
    return canonical, cast(JsonObject, normalized)


__all__ = [
    "assessment_refs_for_manifest",
    "merge_evidence_assessments",
    "require_exact_assessment_coverage",
]
