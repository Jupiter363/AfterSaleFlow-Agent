from __future__ import annotations

from collections.abc import Awaitable, Callable, MutableMapping
from urllib.parse import unquote_to_bytes

from cryptography import x509
from cryptography.hazmat.primitives import serialization

from app.main import create_app as create_inner_app


_CERTIFICATE_HEADER = b"x-target-e2e-mtls-certificate"
_VERIFIED_HEADER = b"x-target-e2e-mtls-verified"
_EXTENSION = "after_sale_flow.mtls"
_MAXIMUM_CERTIFICATE_BYTES = 32 * 1024


class MtlsExtensionAdapter:
    """Translate identity from the isolated TLS terminator into the ASGI extension."""

    def __init__(self, app: Callable[..., Awaitable[None]]) -> None:
        self._app = app

    async def __call__(self, scope: MutableMapping[str, object], receive: object, send: object) -> None:
        if scope.get("type") != "http":
            await self._app(scope, receive, send)
            return

        copied = dict(scope)
        headers = list(copied.get("headers", []))
        certificate_values = [value for name, value in headers if name.lower() == _CERTIFICATE_HEADER]
        verified_values = [value for name, value in headers if name.lower() == _VERIFIED_HEADER]
        copied["headers"] = [
            (name, value)
            for name, value in headers
            if name.lower() not in {_CERTIFICATE_HEADER, _VERIFIED_HEADER}
        ]

        if len(certificate_values) == 1 and verified_values == [b"SUCCESS"]:
            certificate_der = _certificate_der(certificate_values[0])
            extensions = dict(copied.get("extensions") or {})
            if _EXTENSION in extensions:
                raise RuntimeError("mTLS ASGI extension already exists")
            extensions[_EXTENSION] = {
                "verified": True,
                "client_certificate_der": certificate_der,
            }
            copied["extensions"] = extensions

        await self._app(copied, receive, send)


def _certificate_der(escaped_pem: bytes) -> bytes:
    if not escaped_pem or len(escaped_pem) > _MAXIMUM_CERTIFICATE_BYTES:
        raise ValueError("mTLS certificate header is invalid")
    pem = unquote_to_bytes(escaped_pem.decode("ascii", errors="strict"))
    if len(pem) > _MAXIMUM_CERTIFICATE_BYTES:
        raise ValueError("mTLS certificate is too large")
    certificate = x509.load_pem_x509_certificate(pem)
    return certificate.public_bytes(serialization.Encoding.DER)


def create_app() -> MtlsExtensionAdapter:
    return MtlsExtensionAdapter(create_inner_app())
