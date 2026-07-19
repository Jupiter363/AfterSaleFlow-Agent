"""Fail-closed service-to-service security boundaries."""

from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    VerifiedInvocation,
    extract_bearer_token,
    invocation_binding_claims,
)
from app.security.transport_identity import AsgiMtlsIdentityResolver, MTLS_SCOPE_EXTENSION

__all__ = [
    "InvocationEnvelopeError",
    "InvocationEnvelopeVerifier",
    "AsgiMtlsIdentityResolver",
    "MTLS_SCOPE_EXTENSION",
    "ResolvedVerificationKey",
    "TransportIdentity",
    "VerifiedInvocation",
    "extract_bearer_token",
    "invocation_binding_claims",
]
