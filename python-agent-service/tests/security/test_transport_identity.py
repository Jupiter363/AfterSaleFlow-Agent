from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from app.security.invocation_envelope import InvocationEnvelopeError
from app.security.transport_identity import AsgiMtlsIdentityResolver, MTLS_SCOPE_EXTENSION


def certificate_der(spiffe_id: str, *, include_san: bool = True) -> bytes:
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.now(UTC)
    builder = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "service")]))
        .issuer_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-ca")]))
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=1))
        .not_valid_after(now + timedelta(minutes=5))
    )
    if include_san:
        builder = builder.add_extension(
            x509.SubjectAlternativeName([x509.UniformResourceIdentifier(spiffe_id)]),
            critical=False,
        )
    certificate = builder.sign(private_key=key, algorithm=hashes.SHA256())
    return certificate.public_bytes(serialization.Encoding.DER)


def test_verified_asgi_tls_extension_resolves_spiffe_identity() -> None:
    identity = AsgiMtlsIdentityResolver().resolve(
        {
            "extensions": {
                MTLS_SCOPE_EXTENSION: {
                    "verified": True,
                    "client_certificate_der": certificate_der(
                        "spiffe://after-sale-flow/java-api-service"
                    ),
                }
            }
        }
    )

    assert identity.authenticated is True
    assert identity.service_id == "java-api-service"
    assert len(identity.certificate_sha256) == 64


def test_browser_controlled_certificate_headers_are_never_an_identity_source() -> None:
    scope = {
        "headers": [
            (b"x-forwarded-client-cert", b"spiffe://after-sale-flow/java-api-service"),
            (b"x-client-cert-sha256", b"a" * 64),
        ]
    }

    with pytest.raises(InvocationEnvelopeError) as captured:
        AsgiMtlsIdentityResolver().resolve(scope)
    assert captured.value.code == "INVOCATION_MTLS_EXTENSION_MISSING"


@pytest.mark.parametrize(
    ("extension", "code"),
    [
        ({"verified": False, "client_certificate_der": b"certificate"}, "INVOCATION_MTLS_EXTENSION_REJECTED"),
        ({"verified": True}, "INVOCATION_MTLS_CERTIFICATE_REJECTED"),
        ({"verified": True, "client_certificate_der": b"not-der"}, "INVOCATION_MTLS_CERTIFICATE_REJECTED"),
    ],
)
def test_unverified_or_malformed_extension_fails_closed(
    extension: dict[str, object], code: str
) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        AsgiMtlsIdentityResolver().resolve(
            {"extensions": {MTLS_SCOPE_EXTENSION: extension}}
        )
    assert captured.value.code == code


@pytest.mark.parametrize(
    "certificate",
    [
        certificate_der("spiffe://after-sale-flow/another-service"),
        certificate_der("spiffe://after-sale-flow/java-api-service", include_san=False),
    ],
)
def test_wrong_or_missing_spiffe_san_is_rejected(certificate: bytes) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        AsgiMtlsIdentityResolver().resolve(
            {
                "extensions": {
                    MTLS_SCOPE_EXTENSION: {
                        "verified": True,
                        "client_certificate_der": certificate,
                    }
                }
            }
        )
    assert captured.value.code in {
        "INVOCATION_MTLS_CERTIFICATE_REJECTED",
        "INVOCATION_MTLS_IDENTITY_REJECTED",
    }
