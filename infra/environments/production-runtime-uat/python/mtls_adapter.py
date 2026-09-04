from __future__ import annotations

import datetime as dt
import os
from collections.abc import Awaitable, Callable, MutableMapping
from pathlib import Path
from urllib.parse import unquote_to_bytes

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, ExtensionOID

from app.main import create_app as create_inner_app


_CERTIFICATE_HEADER = b"x-production-runtime-mtls-certificate"
_VERIFIED_HEADER = b"x-production-runtime-mtls-verified"
_EXTENSION = "after_sale_flow.mtls"
_MAXIMUM_CERTIFICATE_BYTES = 32 * 1024


class MtlsExtensionAdapter:
    """Translate only UDS-delivered, independently verified mTLS identity into ASGI."""

    def __init__(
        self,
        app: Callable[..., Awaitable[None]],
        *,
        ca_certificate: x509.Certificate,
        expected_ca_sha256: str,
        expected_client_sha256: str,
        expected_spiffe_id: str,
    ) -> None:
        if ca_certificate.fingerprint(hashes.SHA256()).hex() != expected_ca_sha256:
            raise RuntimeError("production runtime mTLS CA fingerprint mismatch")
        constraints = ca_certificate.extensions.get_extension_for_oid(
            ExtensionOID.BASIC_CONSTRAINTS
        ).value
        if not constraints.ca:
            raise RuntimeError("production runtime mTLS trust anchor is not a CA")
        self._app = app
        self._ca_certificate = ca_certificate
        self._expected_client_sha256 = expected_client_sha256
        self._expected_spiffe_id = expected_spiffe_id

    async def __call__(
        self,
        scope: MutableMapping[str, object],
        receive: object,
        send: object,
    ) -> None:
        if scope.get("type") != "http":
            await self._app(scope, receive, send)
            return

        copied = dict(scope)
        headers = list(copied.get("headers", []))
        certificate_values = [
            value for name, value in headers if name.lower() == _CERTIFICATE_HEADER
        ]
        verified_values = [
            value for name, value in headers if name.lower() == _VERIFIED_HEADER
        ]
        copied["headers"] = [
            (name, value)
            for name, value in headers
            if name.lower() not in {_CERTIFICATE_HEADER, _VERIFIED_HEADER}
        ]

        if len(certificate_values) == 1 and verified_values == [b"SUCCESS"]:
            certificate_der = self._verified_certificate_der(certificate_values[0])
            extensions = dict(copied.get("extensions") or {})
            if _EXTENSION in extensions:
                raise RuntimeError("mTLS ASGI extension already exists")
            extensions[_EXTENSION] = {
                "verified": True,
                "client_certificate_der": certificate_der,
            }
            copied["extensions"] = extensions

        await self._app(copied, receive, send)

    def _verified_certificate_der(self, escaped_pem: bytes) -> bytes:
        if not escaped_pem or len(escaped_pem) > _MAXIMUM_CERTIFICATE_BYTES:
            raise ValueError("mTLS certificate header is invalid")
        pem = unquote_to_bytes(escaped_pem.decode("ascii", errors="strict"))
        if len(pem) > _MAXIMUM_CERTIFICATE_BYTES:
            raise ValueError("mTLS certificate is too large")
        certificate = x509.load_pem_x509_certificate(pem)
        if certificate.issuer != self._ca_certificate.subject:
            raise ValueError("mTLS client issuer is not the production runtime CA")
        if (
            certificate.fingerprint(hashes.SHA256()).hex()
            != self._expected_client_sha256
        ):
            raise ValueError("mTLS client fingerprint is not deployment-bound")
        now = dt.datetime.now(dt.timezone.utc)
        if not (
            certificate.not_valid_before_utc <= now <= certificate.not_valid_after_utc
        ):
            raise ValueError("mTLS client certificate is outside its validity window")
        constraints = certificate.extensions.get_extension_for_oid(
            ExtensionOID.BASIC_CONSTRAINTS
        ).value
        key_usage = certificate.extensions.get_extension_for_oid(
            ExtensionOID.KEY_USAGE
        ).value
        extended_usage = certificate.extensions.get_extension_for_oid(
            ExtensionOID.EXTENDED_KEY_USAGE
        ).value
        san = certificate.extensions.get_extension_for_oid(
            ExtensionOID.SUBJECT_ALTERNATIVE_NAME
        ).value
        uri_names = set(san.get_values_for_type(x509.UniformResourceIdentifier))
        if (
            constraints.ca
            or not key_usage.digital_signature
            or ExtendedKeyUsageOID.CLIENT_AUTH not in extended_usage
            or uri_names != {self._expected_spiffe_id}
        ):
            raise ValueError("mTLS client extensions are not the exact Java identity")
        ca_key = self._ca_certificate.public_key()
        if not isinstance(ca_key, ec.EllipticCurvePublicKey):
            raise ValueError("mTLS CA key algorithm is unsupported")
        ca_key.verify(
            certificate.signature,
            certificate.tbs_certificate_bytes,
            ec.ECDSA(certificate.signature_hash_algorithm),
        )
        return certificate.public_bytes(serialization.Encoding.DER)


def _required_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"{name} is required")
    return value


def create_app() -> MtlsExtensionAdapter:
    ca_path = Path(_required_env("PRODUCTION_RUNTIME_MTLS_CA_CERTIFICATE_PATH"))
    if not ca_path.is_absolute() or ca_path.is_symlink() or not ca_path.is_file():
        raise RuntimeError("production runtime mTLS CA path must be an absolute regular file")
    ca_certificate = x509.load_pem_x509_certificate(ca_path.read_bytes())
    ca_sha256 = _required_env("PRODUCTION_RUNTIME_MTLS_CA_CERT_SHA256")
    client_sha256 = _required_env("PRODUCTION_RUNTIME_MTLS_CLIENT_CERT_SHA256")
    if any(
        len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
        for value in (ca_sha256, client_sha256)
    ):
        raise RuntimeError("production runtime mTLS fingerprints must be lowercase SHA-256")
    return MtlsExtensionAdapter(
        create_inner_app(),
        ca_certificate=ca_certificate,
        expected_ca_sha256=ca_sha256,
        expected_client_sha256=client_sha256,
        expected_spiffe_id="spiffe://after-sale-flow/java-api-service",
    )
