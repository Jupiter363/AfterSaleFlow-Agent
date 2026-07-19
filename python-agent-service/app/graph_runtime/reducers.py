from __future__ import annotations

from collections.abc import Mapping
from copy import deepcopy
from typing import TypeVar

from app.contracts.v1.codec import canonical_sha256, canonicalize


T = TypeVar("T")


class KeyedReducerConflict(ValueError):
    """A stable key was reused with a different canonical JSON value."""

    def __init__(
        self,
        *,
        namespace: str,
        key: str,
        existing_sha256: str,
        incoming_sha256: str,
    ) -> None:
        super().__init__(f"keyed reducer conflict in {namespace} at {key}")
        self.namespace = namespace
        self.key = key
        self.existing_sha256 = existing_sha256
        self.incoming_sha256 = incoming_sha256


def merge_keyed_json(
    left: Mapping[str, T] | None,
    right: Mapping[str, T] | None,
    *,
    namespace: str = "state",
) -> dict[str, T]:
    """Merge immutable JSON values by key, rejecting conflicting duplicates."""

    merged: dict[str, T] = {}
    for key, value in (left or {}).items():
        _validate_key(key, namespace)
        canonicalize(value)
        merged[key] = deepcopy(value)
    for key, incoming in (right or {}).items():
        _validate_key(key, namespace)
        if key not in merged:
            canonicalize(incoming)
            merged[key] = deepcopy(incoming)
            continue
        existing = merged[key]
        existing_bytes = canonicalize(existing)
        incoming_bytes = canonicalize(incoming)
        if existing_bytes != incoming_bytes:
            raise KeyedReducerConflict(
                namespace=namespace,
                key=key,
                existing_sha256=canonical_sha256(existing),
                incoming_sha256=canonical_sha256(incoming),
            )
    return {key: merged[key] for key in sorted(merged)}


def merge_messages(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="messages")


def merge_work_items(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="work_items")


def merge_work_results(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="work_results")


def merge_artifact_refs(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="artifact_refs")


def merge_node_results(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="node_results")


def merge_execution_receipts(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="execution_receipts")


def merge_usage_by_invocation(
    left: Mapping[str, T] | None, right: Mapping[str, T] | None
) -> dict[str, T]:
    return merge_keyed_json(left, right, namespace="usage_by_invocation")


def _validate_key(key: object, namespace: str) -> None:
    if not isinstance(key, str) or not key or len(key) > 128 or "\x00" in key:
        raise ValueError(f"invalid stable key in {namespace}")
