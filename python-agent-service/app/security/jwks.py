from __future__ import annotations

import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from threading import Lock
from types import MappingProxyType
from typing import Any

import jwt

from app.security.invocation_envelope import InvocationEnvelopeError, ResolvedVerificationKey


_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


@dataclass(frozen=True)
class JwksSnapshot:
    generation: int
    key_ids: tuple[str, ...]


class JwksVerificationKeyResolver:
    """Atomically publish a validated, public-only ES256 JWKS snapshot."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._generation = 0
        self._keys: Mapping[str, ResolvedVerificationKey] = MappingProxyType({})

    def install(
        self,
        jwks: Mapping[str, Any],
        *,
        retain_key_ids: Iterable[str] = (),
    ) -> JwksSnapshot:
        parsed = _parse_jwks(jwks)
        retained = set(retain_key_ids)
        if any(not _IDENTIFIER_PATTERN.fullmatch(kid) for kid in retained):
            raise InvocationEnvelopeError("INVOCATION_JWKS_RETAINED_KEY_REJECTED")
        with self._lock:
            missing = retained - set(parsed) - set(self._keys)
            if missing:
                raise InvocationEnvelopeError("INVOCATION_JWKS_RETAINED_KEY_MISSING")
            for kid in retained - set(parsed):
                parsed[kid] = self._keys[kid]
            self._generation += 1
            self._keys = MappingProxyType(dict(sorted(parsed.items())))
            return JwksSnapshot(self._generation, tuple(self._keys))

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        key = self._keys.get(kid)
        if key is None:
            raise InvocationEnvelopeError("INVOCATION_JWS_KEY_UNAVAILABLE")
        return key

    def snapshot(self) -> JwksSnapshot:
        keys = self._keys
        return JwksSnapshot(self._generation, tuple(keys))


def _parse_jwks(jwks: Mapping[str, Any]) -> dict[str, ResolvedVerificationKey]:
    if set(jwks) != {"keys"} or not isinstance(jwks["keys"], list) or not jwks["keys"]:
        raise InvocationEnvelopeError("INVOCATION_JWKS_DOCUMENT_REJECTED")
    parsed: dict[str, ResolvedVerificationKey] = {}
    for candidate in jwks["keys"]:
        if not isinstance(candidate, dict):
            raise InvocationEnvelopeError("INVOCATION_JWKS_KEY_REJECTED")
        kid = candidate.get("kid")
        if not isinstance(kid, str) or not _IDENTIFIER_PATTERN.fullmatch(kid):
            raise InvocationEnvelopeError("INVOCATION_JWKS_KEY_REJECTED")
        if kid in parsed:
            raise InvocationEnvelopeError("INVOCATION_JWKS_DUPLICATE_KEY")
        if (
            candidate.get("kty") != "EC"
            or candidate.get("crv") != "P-256"
            or candidate.get("use") != "sig"
            or candidate.get("alg") != "ES256"
            or "d" in candidate
        ):
            raise InvocationEnvelopeError("INVOCATION_JWKS_KEY_REJECTED")
        try:
            public_key = jwt.algorithms.ECAlgorithm.from_jwk(candidate)
        except (jwt.PyJWTError, ValueError, TypeError) as error:
            raise InvocationEnvelopeError("INVOCATION_JWKS_KEY_REJECTED") from error
        parsed[kid] = ResolvedVerificationKey(kid=kid, public_key=public_key)
    return parsed
