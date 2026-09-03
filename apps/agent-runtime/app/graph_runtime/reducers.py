from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from typing import TypeVar, cast

from app.contracts.v1.codec import canonicalize


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
    """Merge immutable JSON values by key into one canonical Python representation."""

    merged: dict[str, tuple[bytes, T]] = {}
    for key, value in (left or {}).items():
        _validate_key(key, namespace)
        merged[key] = _normalize_json(value)
    for key, incoming in (right or {}).items():
        _validate_key(key, namespace)
        incoming_bytes, normalized_incoming = _normalize_json(incoming)
        if key not in merged:
            merged[key] = (incoming_bytes, normalized_incoming)
            continue
        existing_bytes, _ = merged[key]
        if existing_bytes != incoming_bytes:
            raise KeyedReducerConflict(
                namespace=namespace,
                key=key,
                existing_sha256=hashlib.sha256(existing_bytes).hexdigest(),
                incoming_sha256=hashlib.sha256(incoming_bytes).hexdigest(),
            )
    return {key: merged[key][1] for key in sorted(merged)}


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


def _normalize_json(value: T) -> tuple[bytes, T]:
    canonical = canonicalize(value)
    return canonical, cast(T, json.loads(canonical))


def _validate_key(key: object, namespace: str) -> None:
    if not isinstance(key, str) or not key or len(key) > 128 or "\x00" in key:
        raise ValueError(f"invalid stable key in {namespace}")
