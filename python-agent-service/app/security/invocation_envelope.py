from __future__ import annotations

import hmac
import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, Literal, Protocol

import jwt
from pydantic import BaseModel, ConfigDict, Field

from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting
from app.contracts.v1.models import RoomGraphCommand


_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_THREAD_PATTERN = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
_CERTIFICATE_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_EXPECTED_HEADER_KEYS = frozenset({"alg", "kid", "typ"})
_MAX_JWS_BYTES = 8192
_MAX_TOKEN_LIFETIME_SECONDS = 60
_MAX_CLOCK_SKEW_SECONDS = 5


class InvocationEnvelopeError(ValueError):
    """A bounded, public-safe rejection of a signed invocation envelope."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class TransportIdentity:
    service_id: str
    authenticated: bool
    certificate_sha256: str


@dataclass(frozen=True)
class ResolvedVerificationKey:
    kid: str
    public_key: Any
    algorithm: Literal["ES256"] = "ES256"
    curve: Literal["P-256"] = "P-256"
    use: Literal["sig"] = "sig"


class VerificationKeyResolver(Protocol):
    def resolve(self, kid: str) -> ResolvedVerificationKey: ...


class InvocationClaims(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    iss: Literal["java-api-service"]
    aud: Literal["python-agent-service"]
    sub: Literal["graph-command"]
    iat: int = Field(ge=0)
    nbf: int = Field(ge=0)
    exp: int = Field(ge=0)
    jti: str = Field(min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern)
    command_id: str = Field(min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern)
    command_nonce: str = Field(
        min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern
    )
    request_hash: str = Field(pattern=_SHA256_PATTERN.pattern)
    tenant_surrogate: str = Field(
        min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern
    )
    case_id: str = Field(min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern)
    room_epoch: int = Field(ge=0)
    thread_id: str = Field(pattern=_THREAD_PATTERN.pattern)
    graph_key: str = Field(min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern)
    graph_version: str = Field(
        min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern
    )
    checkpoint_schema_version: str = Field(
        min_length=1, max_length=128, pattern=_IDENTIFIER_PATTERN.pattern
    )
    actor_scope_hash: str = Field(pattern=_SHA256_PATTERN.pattern)
    capabilities_hash: str = Field(pattern=_SHA256_PATTERN.pattern)
    profile_bindings_hash: str = Field(pattern=_SHA256_PATTERN.pattern)


@dataclass(frozen=True)
class VerifiedInvocation:
    claims: InvocationClaims
    key_id: str
    request_hash: str
    transport_certificate_sha256: str


class InvocationEnvelopeVerifier:
    """Verify mTLS identity, ES256 claims, and every RoomGraphCommand binding."""

    def __init__(
        self,
        *,
        key_resolver: VerificationKeyResolver,
        now: Callable[[], int] | None = None,
    ) -> None:
        self._key_resolver = key_resolver
        self._now = now or (lambda: int(time.time()))

    def verify(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> VerifiedInvocation:
        self._verify_transport_identity(transport_identity)
        header = self._decode_header(token)
        key = self._resolve_key(header["kid"])
        claims = self._decode_claims(token, key)
        self._verify_time_window(claims)
        expected = invocation_binding_claims(command)
        if not hmac.compare_digest(command.request_hash, str(expected["request_hash"])):
            raise InvocationEnvelopeError("INVOCATION_COMMAND_SELF_HASH_MISMATCH")
        actual = claims.model_dump(mode="json")
        for name, value in expected.items():
            if not _constant_time_equal(actual[name], value):
                raise InvocationEnvelopeError(f"INVOCATION_{name.upper()}_MISMATCH")
        if command.invocation_context.envelope_key_id != key.kid:
            raise InvocationEnvelopeError("INVOCATION_COMMAND_KEY_ID_MISMATCH")
        return VerifiedInvocation(
            claims=claims,
            key_id=key.kid,
            request_hash=expected["request_hash"],
            transport_certificate_sha256=transport_identity.certificate_sha256,
        )

    @staticmethod
    def _verify_transport_identity(identity: TransportIdentity) -> None:
        if not identity.authenticated or identity.service_id != "java-api-service":
            raise InvocationEnvelopeError("INVOCATION_MTLS_IDENTITY_REJECTED")
        if not _CERTIFICATE_PATTERN.fullmatch(identity.certificate_sha256):
            raise InvocationEnvelopeError("INVOCATION_MTLS_CERTIFICATE_REJECTED")

    @staticmethod
    def _decode_header(token: str) -> Mapping[str, str]:
        if len(token.encode("utf-8")) > _MAX_JWS_BYTES or token.count(".") != 2:
            raise InvocationEnvelopeError("INVOCATION_JWS_MALFORMED")
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as error:
            raise InvocationEnvelopeError("INVOCATION_JWS_MALFORMED") from error
        if set(header) != _EXPECTED_HEADER_KEYS:
            raise InvocationEnvelopeError("INVOCATION_JWS_HEADER_REJECTED")
        if header.get("alg") != "ES256" or header.get("typ") != "graph-command+jwt":
            raise InvocationEnvelopeError("INVOCATION_JWS_HEADER_REJECTED")
        kid = header.get("kid")
        if not isinstance(kid, str) or not _IDENTIFIER_PATTERN.fullmatch(kid):
            raise InvocationEnvelopeError("INVOCATION_JWS_KEY_ID_REJECTED")
        return header

    def _resolve_key(self, kid: str) -> ResolvedVerificationKey:
        try:
            key = self._key_resolver.resolve(kid)
        except Exception as error:
            raise InvocationEnvelopeError("INVOCATION_JWS_KEY_UNAVAILABLE") from error
        if (
            key.kid != kid
            or key.algorithm != "ES256"
            or key.curve != "P-256"
            or key.use != "sig"
        ):
            raise InvocationEnvelopeError("INVOCATION_JWS_KEY_REJECTED")
        return key

    @staticmethod
    def _decode_claims(token: str, key: ResolvedVerificationKey) -> InvocationClaims:
        try:
            payload = jwt.decode(
                token,
                key.public_key,
                algorithms=["ES256"],
                audience="python-agent-service",
                issuer="java-api-service",
                options={
                    "require": ["iss", "aud", "sub", "iat", "nbf", "exp", "jti"],
                    "verify_exp": False,
                    "verify_iat": False,
                    "verify_nbf": False,
                },
            )
            return InvocationClaims.model_validate(payload)
        except (jwt.PyJWTError, ValueError) as error:
            raise InvocationEnvelopeError("INVOCATION_JWS_CLAIMS_REJECTED") from error

    def _verify_time_window(self, claims: InvocationClaims) -> None:
        now = self._now()
        if claims.exp <= claims.iat or claims.exp - claims.iat > _MAX_TOKEN_LIFETIME_SECONDS:
            raise InvocationEnvelopeError("INVOCATION_JWS_LIFETIME_REJECTED")
        if claims.nbf < claims.iat or claims.nbf > claims.exp:
            raise InvocationEnvelopeError("INVOCATION_JWS_TIME_ORDER_REJECTED")
        if claims.iat > now + _MAX_CLOCK_SKEW_SECONDS:
            raise InvocationEnvelopeError("INVOCATION_JWS_NOT_YET_VALID")
        if claims.nbf > now + _MAX_CLOCK_SKEW_SECONDS:
            raise InvocationEnvelopeError("INVOCATION_JWS_NOT_YET_VALID")
        if claims.exp < now - _MAX_CLOCK_SKEW_SECONDS:
            raise InvocationEnvelopeError("INVOCATION_JWS_EXPIRED")


def extract_bearer_token(authorization: str | None) -> str:
    if authorization is None:
        raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_MISSING")
    scheme, separator, token = authorization.partition(" ")
    if (
        scheme != "Bearer"
        or separator != " "
        or not token
        or " " in token
        or token.count(".") != 2
        or len(token.encode("utf-8")) > _MAX_JWS_BYTES
    ):
        raise InvocationEnvelopeError("INVOCATION_AUTHORIZATION_REJECTED")
    return token


def invocation_binding_claims(command: RoomGraphCommand) -> dict[str, str | int]:
    actor_scope = command.actor_scope.model_dump(mode="json")
    invocation = command.invocation_context
    capabilities = {
        "actor_capabilities": sorted(actor_scope["capabilities"]),
        "tool_capabilities": sorted(invocation.tool_capabilities),
    }
    profile_bindings = {
        "agent_profile_id": invocation.agent_profile_id,
        "prompt_profile_id": invocation.prompt_profile_id,
        "model_profile_id": invocation.model_profile_id,
        "output_schema_version": invocation.output_schema_version,
        "policy_version": invocation.policy_version,
        "guardrail_version": invocation.guardrail_version,
    }
    return {
        "command_id": command.command_id,
        "command_nonce": invocation.envelope_nonce,
        "request_hash": canonical_sha256_omitting(command, "request_hash"),
        "tenant_surrogate": command.tenant_surrogate,
        "case_id": command.case_id,
        "room_epoch": command.room_epoch,
        "thread_id": command.thread_id,
        "graph_key": command.graph_key,
        "graph_version": command.graph_version,
        "checkpoint_schema_version": command.checkpoint_schema_version,
        "actor_scope_hash": canonical_sha256(actor_scope),
        "capabilities_hash": canonical_sha256(capabilities),
        "profile_bindings_hash": canonical_sha256(profile_bindings),
    }


def _constant_time_equal(actual: str | int, expected: str | int) -> bool:
    if isinstance(actual, int) or isinstance(expected, int):
        return type(actual) is type(expected) and actual == expected
    return hmac.compare_digest(actual, expected)
