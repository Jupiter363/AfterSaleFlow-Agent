"""Fail-closed service-to-service security boundaries."""

from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ReconciliationClaims,
    ReconciliationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    VerifiedInvocation,
    VerifiedReconciliation,
    extract_bearer_token,
    invocation_binding_claims,
)
from app.security.transport_identity import AsgiMtlsIdentityResolver, MTLS_SCOPE_EXTENSION
from app.security.graph_runtime import GraphSecurityReadiness, GraphSecurityRuntime
from app.security.jwks import JwksSnapshot, JwksVerificationKeyResolver
from app.security.jwks_loader import (
    HttpJwksDocumentFetcher,
    JwksRefreshError,
    JwksRefreshManager,
    JwksRefreshStatus,
)

__all__ = [
    "InvocationEnvelopeError",
    "InvocationEnvelopeVerifier",
    "ReconciliationClaims",
    "ReconciliationEnvelopeVerifier",
    "GraphSecurityReadiness",
    "GraphSecurityRuntime",
    "AsgiMtlsIdentityResolver",
    "MTLS_SCOPE_EXTENSION",
    "JwksSnapshot",
    "JwksVerificationKeyResolver",
    "HttpJwksDocumentFetcher",
    "JwksRefreshError",
    "JwksRefreshManager",
    "JwksRefreshStatus",
    "ResolvedVerificationKey",
    "TransportIdentity",
    "VerifiedInvocation",
    "VerifiedReconciliation",
    "extract_bearer_token",
    "invocation_binding_claims",
]
