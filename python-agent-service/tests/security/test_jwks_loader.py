from __future__ import annotations

import json

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.security.jwks import JwksVerificationKeyResolver
from app.security.jwks_loader import (
    HttpJwksDocumentFetcher,
    JwksRefreshError,
    JwksRefreshManager,
)


def public_jwk(kid: str) -> dict[str, object]:
    key = ec.generate_private_key(ec.SECP256R1())
    value = jwt.algorithms.ECAlgorithm.to_jwk(key.public_key(), as_dict=True)
    return {**value, "kid": kid, "use": "sig", "alg": "ES256"}


@pytest.mark.asyncio
async def test_http_fetcher_accepts_only_bounded_strict_jwks_json() -> None:
    document = {"keys": [public_jwk("key-current")]}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["accept"] == "application/jwk-set+json, application/json"
        return httpx.Response(
            200,
            headers={"content-type": "application/jwk-set+json; charset=utf-8"},
            content=json.dumps(document).encode(),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        fetcher = HttpJwksDocumentFetcher(client, "https://java.internal/jwks")
        assert await fetcher() == document


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("response", "code"),
    [
        (httpx.Response(503), "JWKS_HTTP_UNAVAILABLE"),
        (
            httpx.Response(200, headers={"content-type": "text/plain"}, content=b"{}"),
            "JWKS_MEDIA_TYPE_REJECTED",
        ),
        (
            httpx.Response(
                200,
                headers={"content-type": "application/json"},
                content=b'{"keys":[],"keys":[]}',
            ),
            "JWKS_DOCUMENT_JSON_REJECTED",
        ),
        (
            httpx.Response(
                200,
                headers={"content-type": "application/json"},
                content=b"[]",
            ),
            "JWKS_DOCUMENT_JSON_REJECTED",
        ),
    ],
)
async def test_http_fetcher_fails_closed_on_untrusted_responses(
    response: httpx.Response,
    code: str,
) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: response)
    ) as client:
        fetcher = HttpJwksDocumentFetcher(client, "https://java.internal/jwks")
        with pytest.raises(JwksRefreshError) as captured:
            await fetcher()
        assert captured.value.code == code


@pytest.mark.asyncio
async def test_http_fetcher_rejects_empty_and_oversized_documents() -> None:
    responses = iter(
        [
            httpx.Response(200, headers={"content-type": "application/json"}, content=b""),
            httpx.Response(
                200,
                headers={"content-type": "application/json"},
                content=b"{" + b"x" * 64 + b"}",
            ),
        ]
    )
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: next(responses))
    ) as client:
        fetcher = HttpJwksDocumentFetcher(
            client,
            "https://java.internal/jwks",
            max_document_bytes=32,
        )
        for _ in range(2):
            with pytest.raises(JwksRefreshError) as captured:
                await fetcher()
            assert captured.value.code == "JWKS_DOCUMENT_SIZE_REJECTED"


@pytest.mark.asyncio
async def test_rotation_honors_overlap_and_durable_recovery_key_references() -> None:
    old = public_jwk("key-old")
    current = public_jwk("key-current")
    document = {"keys": [old]}
    references: set[str] = set()
    clock = [0.0]

    async def fetch_document() -> dict[str, object]:
        return document

    async def referenced_key_ids() -> set[str]:
        return set(references)

    resolver = JwksVerificationKeyResolver()
    manager = JwksRefreshManager(
        resolver=resolver,
        fetch_document=fetch_document,
        referenced_key_ids=referenced_key_ids,
        monotonic=lambda: clock[0],
    )

    assert (await manager.refresh_once()).key_ids == ("key-old",)
    document = {"keys": [current]}
    clock[0] = 1.0
    assert (await manager.refresh_once()).key_ids == ("key-current", "key-old")

    references.add("key-old")
    clock[0] = 80.0
    assert (await manager.refresh_once()).key_ids == ("key-current", "key-old")

    references.clear()
    clock[0] = 81.0
    assert (await manager.refresh_once()).key_ids == ("key-current",)


@pytest.mark.asyncio
async def test_failed_refresh_keeps_last_atomic_snapshot_and_reports_error() -> None:
    document: dict[str, object] = {"keys": [public_jwk("key-current")]}

    async def fetch_document() -> dict[str, object]:
        return document

    async def referenced_key_ids() -> set[str]:
        return set()

    resolver = JwksVerificationKeyResolver()
    manager = JwksRefreshManager(
        resolver=resolver,
        fetch_document=fetch_document,
        referenced_key_ids=referenced_key_ids,
    )
    expected = await manager.refresh_once()

    document = {"keys": []}
    with pytest.raises(JwksRefreshError) as captured:
        await manager.refresh_once()

    assert captured.value.code == "JWKS_DOCUMENT_REJECTED"
    assert resolver.snapshot() == expected
    status = manager.status()
    assert status.generation == 1
    assert status.key_ids == ("key-current",)
    assert status.last_error_code == "JWKS_DOCUMENT_REJECTED"


@pytest.mark.asyncio
async def test_start_loads_before_running_and_close_is_idempotent() -> None:
    async def fetch_document() -> dict[str, object]:
        return {"keys": [public_jwk("key-current")]}

    async def referenced_key_ids() -> set[str]:
        return set()

    manager = JwksRefreshManager(
        resolver=JwksVerificationKeyResolver(),
        fetch_document=fetch_document,
        referenced_key_ids=referenced_key_ids,
        refresh_interval_seconds=60,
    )

    snapshot = await manager.start()
    assert snapshot.key_ids == ("key-current",)
    assert manager.status().running is True

    await manager.close()
    await manager.close()
    assert manager.status().running is False
