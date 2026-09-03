from __future__ import annotations

import json
import re
from collections.abc import Mapping
from copy import deepcopy
from typing import Any

from app.contracts.v1.codec import canonicalize
from app.graphs.hearing.errors import HearingGraphContractError


_STABLE_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MAX_REDUCED_RESULT_BYTES = 2_000_000


def merge_keyed_hearing_results(
    left: Mapping[str, Mapping[str, Any]] | None,
    right: Mapping[str, Mapping[str, Any]] | None,
) -> dict[str, dict[str, Any]]:
    """Merge immutable work results by stable key and canonical JSON payload."""

    merged: dict[str, tuple[bytes, dict[str, Any]]] = {}
    for operand in (left, right):
        if operand is None:
            continue
        if not isinstance(operand, Mapping):
            raise HearingGraphContractError("HEARING_REDUCER_INPUT_INVALID")
        for key, payload in operand.items():
            if not isinstance(key, str) or _STABLE_KEY.fullmatch(key) is None:
                raise HearingGraphContractError("HEARING_REDUCER_KEY_INVALID")
            if not isinstance(payload, Mapping):
                raise HearingGraphContractError("HEARING_REDUCER_PAYLOAD_INVALID")
            try:
                canonical = canonicalize(payload)
                normalized = json.loads(canonical)
            except (TypeError, ValueError, json.JSONDecodeError) as error:
                raise HearingGraphContractError(
                    "HEARING_REDUCER_PAYLOAD_NOT_CANONICAL_JSON"
                ) from error
            if not isinstance(normalized, dict):
                raise HearingGraphContractError("HEARING_REDUCER_PAYLOAD_INVALID")
            existing = merged.get(key)
            if existing is not None and existing[0] != canonical:
                raise HearingGraphContractError("HEARING_REDUCER_KEY_CONFLICT")
            merged[key] = (canonical, normalized)
    if sum(len(item[0]) for item in merged.values()) > _MAX_REDUCED_RESULT_BYTES:
        raise HearingGraphContractError("HEARING_REDUCER_TOTAL_BYTES_EXCEEDED")
    return {key: deepcopy(merged[key][1]) for key in sorted(merged)}


__all__ = ["merge_keyed_hearing_results"]
