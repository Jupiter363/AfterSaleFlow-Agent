"""Presence-preserving hashes for the cross-language case fact matrix."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel

from app.contracts.v1.codec import canonical_sha256


_CONTENT_HASH = re.compile(r"^[0-9a-f]{64}$")


def case_fact_matrix_hash_material(
    value: BaseModel | Mapping[str, Any],
) -> dict[str, Any]:
    """Return JSON material without inventing optional members absent on input."""

    if isinstance(value, BaseModel):
        return value.model_dump(mode="json", exclude_unset=True)
    return dict(value)


def case_fact_matrix_content_hash(
    value: BaseModel | Mapping[str, Any],
) -> str:
    """Return the RFC 8785 hash of a CaseFactMatrixV2 authority."""

    material = case_fact_matrix_hash_material(value)
    material.pop("content_hash", None)
    return canonical_sha256(material)


def historic_case_fact_matrix_content_hash(
    value: BaseModel | Mapping[str, Any],
) -> str:
    """Return the pre-JCS Python hash accepted only for persisted replay."""

    material = case_fact_matrix_hash_material(value)
    material.pop("content_hash", None)
    return hashlib.sha256(
        json.dumps(
            material,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def validate_case_fact_matrix_content_hash(
    value: BaseModel | Mapping[str, Any],
) -> bool:
    """Validate current JCS and historic hashes against the received field set."""

    material = case_fact_matrix_hash_material(value)
    stored_hash = material.get("content_hash")
    return (
        isinstance(stored_hash, str)
        and _CONTENT_HASH.fullmatch(stored_hash) is not None
        and stored_hash
        in {
            case_fact_matrix_content_hash(value),
            historic_case_fact_matrix_content_hash(value),
        }
    )
