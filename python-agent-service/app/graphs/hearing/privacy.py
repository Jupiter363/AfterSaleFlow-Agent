from __future__ import annotations

import json
import re
from collections.abc import Mapping
from typing import Any, Literal, cast

from typing_extensions import TypedDict

from app.graph_runtime.state_lens import StateLens, StateLensError
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.state import HearingGraphStateV1


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MAX_AUTHORIZED_ARTIFACT_REFS = 256


class HearingLensInput(TypedDict):
    state_scope: Literal["ACTOR_PRIVATE", "SHARED"]
    audience_binding: str
    authorized_artifact_refs_json: str
    version_pins_json: str


def build_actor_private_state_lens() -> StateLens[HearingGraphStateV1, HearingLensInput]:
    return StateLens(
        name="hearing.actor_private.state_lens",
        source_fields=("command_binding", "scope_binding", "version_pins"),
        selector=_select_actor_private,
        output_type=HearingLensInput,
    )


def build_shared_state_lens() -> StateLens[HearingGraphStateV1, HearingLensInput]:
    return StateLens(
        name="hearing.shared.state_lens",
        source_fields=("command_binding", "scope_binding", "version_pins"),
        selector=_select_shared,
        output_type=HearingLensInput,
    )


def validate_hearing_scope_binding(state: Mapping[str, Any]) -> None:
    if "command_binding" not in state and "scope_binding" not in state:
        return
    if "command_binding" not in state or "scope_binding" not in state:
        raise HearingGraphContractError("HEARING_SCOPE_BINDING_INCOMPLETE")
    scope = state.get("scope_binding")
    if not isinstance(scope, Mapping):
        raise HearingGraphContractError("HEARING_SCOPE_BINDING_INVALID")
    try:
        if scope.get("state_scope") == "ACTOR_PRIVATE":
            build_actor_private_state_lens().invoke(cast(HearingGraphStateV1, state))
        elif scope.get("state_scope") == "SHARED":
            build_shared_state_lens().invoke(cast(HearingGraphStateV1, state))
        else:
            raise HearingGraphContractError("HEARING_SCOPE_UNKNOWN")
    except StateLensError as error:
        raise HearingGraphContractError("HEARING_SCOPE_LENS_REJECTED") from error


def _select_actor_private(state: Mapping[str, Any]) -> Mapping[str, Any]:
    scope = _validated_scope(state, expected="ACTOR_PRIVATE")
    if scope.get("shared_barrier_receipt_hash") is not None:
        raise HearingGraphContractError("HEARING_PRIVATE_SCOPE_BARRIER_FORBIDDEN")
    return _lens_input(state, scope, audience=cast(str, scope["actor_scope_hash"]))


def _select_shared(state: Mapping[str, Any]) -> Mapping[str, Any]:
    scope = _validated_scope(state, expected="SHARED")
    barrier = scope.get("shared_barrier_receipt_hash")
    if not isinstance(barrier, str) or _SHA256.fullmatch(barrier) is None:
        raise HearingGraphContractError("HEARING_SHARED_BARRIER_REQUIRED")
    return _lens_input(state, scope, audience=f"SHARED:{barrier}")


def _validated_scope(
    state: Mapping[str, Any],
    *,
    expected: Literal["ACTOR_PRIVATE", "SHARED"],
) -> Mapping[str, Any]:
    command = state.get("command_binding")
    scope = state.get("scope_binding")
    pins = state.get("version_pins")
    if not isinstance(command, Mapping) or not isinstance(scope, Mapping) or not isinstance(
        pins, Mapping
    ):
        raise HearingGraphContractError("HEARING_SCOPE_BINDING_INVALID")
    if (
        command.get("schema_version") != "hearing-command-binding.v1"
        or scope.get("schema_version") != "hearing-scope-binding.v1"
        or scope.get("state_scope") != expected
    ):
        raise HearingGraphContractError("HEARING_SCOPE_BINDING_INVALID")
    actor_scope_hash = scope.get("actor_scope_hash")
    refs = scope.get("authorized_artifact_refs")
    if (
        not isinstance(actor_scope_hash, str)
        or _SHA256.fullmatch(actor_scope_hash) is None
        or not isinstance(refs, list)
        or len(refs) > _MAX_AUTHORIZED_ARTIFACT_REFS
    ):
        raise HearingGraphContractError("HEARING_SCOPE_BINDING_INVALID")
    for ref in refs:
        if not isinstance(ref, Mapping) or set(ref) != {
            "artifact_id",
            "schema_version",
            "uri",
            "sha256",
        }:
            raise HearingGraphContractError("HEARING_SCOPE_ARTIFACT_REF_INVALID")
        if (
            any(
                not isinstance(ref.get(field), str)
                or _IDENTIFIER.fullmatch(cast(str, ref[field])) is None
                for field in ("artifact_id", "schema_version")
            )
            or not isinstance(ref.get("uri"), str)
            or not cast(str, ref["uri"])
            or len(cast(str, ref["uri"])) > 512
            or not isinstance(ref.get("sha256"), str)
            or _SHA256.fullmatch(cast(str, ref.get("sha256"))) is None
        ):
            raise HearingGraphContractError("HEARING_SCOPE_ARTIFACT_REF_INVALID")
    return scope


def _lens_input(
    state: Mapping[str, Any],
    scope: Mapping[str, Any],
    *,
    audience: str,
) -> HearingLensInput:
    refs = sorted(
        cast(list[dict[str, Any]], scope["authorized_artifact_refs"]),
        key=lambda ref: (ref["artifact_id"], ref["sha256"]),
    )
    return {
        "state_scope": cast(Literal["ACTOR_PRIVATE", "SHARED"], scope["state_scope"]),
        "audience_binding": audience,
        "authorized_artifact_refs_json": _canonical_text(refs),
        "version_pins_json": _canonical_text(state["version_pins"]),
    }


def _canonical_text(value: Any) -> str:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as error:
        raise HearingGraphContractError("HEARING_SCOPE_VALUE_NOT_SERIALIZABLE") from error


__all__ = [
    "HearingLensInput",
    "build_actor_private_state_lens",
    "build_shared_state_lens",
    "validate_hearing_scope_binding",
]
