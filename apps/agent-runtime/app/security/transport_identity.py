from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from cryptography import x509
from cryptography.x509.oid import ExtensionOID

from app.security.invocation_envelope import InvocationEnvelopeError, TransportIdentity


MTLS_SCOPE_EXTENSION = "after_sale_flow.mtls"


@dataclass(frozen=True)
class AsgiMtlsIdentityResolver:
    """Resolve identity only from a TLS-server-populated ASGI extension."""

    expected_spiffe_id: str = "spiffe://after-sale-flow/java-api-service"
    expected_service_id: str = "java-api-service"

    def resolve(self, scope: Mapping[str, Any]) -> TransportIdentity:
        extensions = scope.get("extensions")
        if not isinstance(extensions, Mapping):
            raise InvocationEnvelopeError("INVOCATION_MTLS_EXTENSION_MISSING")
        mtls = extensions.get(MTLS_SCOPE_EXTENSION)
        if not isinstance(mtls, Mapping) or mtls.get("verified") is not True:
            raise InvocationEnvelopeError("INVOCATION_MTLS_EXTENSION_REJECTED")
        certificate_der = mtls.get("client_certificate_der")
        if not isinstance(certificate_der, bytes) or not certificate_der:
            raise InvocationEnvelopeError("INVOCATION_MTLS_CERTIFICATE_REJECTED")
        try:
            certificate = x509.load_der_x509_certificate(certificate_der)
            san = certificate.extensions.get_extension_for_oid(
                ExtensionOID.SUBJECT_ALTERNATIVE_NAME
            ).value
            uri_names = set(san.get_values_for_type(x509.UniformResourceIdentifier))
        except (ValueError, x509.ExtensionNotFound) as error:
            raise InvocationEnvelopeError("INVOCATION_MTLS_CERTIFICATE_REJECTED") from error
        if self.expected_spiffe_id not in uri_names:
            raise InvocationEnvelopeError("INVOCATION_MTLS_IDENTITY_REJECTED")
        return TransportIdentity(
            service_id=self.expected_service_id,
            authenticated=True,
            certificate_sha256=hashlib.sha256(certificate_der).hexdigest(),
        )
