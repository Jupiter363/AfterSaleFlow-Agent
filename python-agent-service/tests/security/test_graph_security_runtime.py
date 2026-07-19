from __future__ import annotations

from collections.abc import Iterable

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.security.graph_runtime import GraphSecurityRuntime
from app.security.jwks_loader import JwksRefreshError


def _jwks(kid: str = "graph-key-1") -> dict[str, object]:
    key = ec.generate_private_key(ec.SECP256R1())
    public = jwt.algorithms.ECAlgorithm.to_jwk(key.public_key(), as_dict=True)
    return {
        "keys": [
            {
                **public,
                "use": "sig",
                "alg": "ES256",
                "kid": kid,
            }
        ]
    }


async def _no_references() -> Iterable[str]:
    return ()


@pytest.mark.asyncio
async def test_open_loads_keys_before_reporting_ready_and_closes_idempotently() -> None:
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        assert request.headers["accept"] == "application/jwk-set+json, application/json"
        return httpx.Response(200, json=_jwks(), headers={"content-type": "application/json"})

    runtime = await GraphSecurityRuntime.open(
        jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
        timeout_seconds=1,
        refresh_interval_seconds=3600,
        referenced_key_ids=_no_references,
        transport=httpx.MockTransport(handler),
    )

    assert requests == 1
    assert runtime.resolver.snapshot().key_ids == ("graph-key-1",)
    readiness = runtime.readiness()
    assert readiness.ready is True
    assert readiness.code == "GRAPH_JWKS_READY"
    assert readiness.generation == 1
    assert readiness.key_count == 1

    await runtime.close()
    await runtime.close()
    assert runtime.readiness().code == "GRAPH_JWKS_CLOSED"


@pytest.mark.asyncio
async def test_initial_fetch_failure_never_returns_a_runtime() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "https://attacker.example/jwks"})

    with pytest.raises(JwksRefreshError, match="JWKS_HTTP_UNAVAILABLE"):
        await GraphSecurityRuntime.open(
            jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
            timeout_seconds=1,
            refresh_interval_seconds=3600,
            referenced_key_ids=_no_references,
            transport=httpx.MockTransport(handler),
        )


@pytest.mark.asyncio
async def test_refresh_failure_keeps_the_last_immutable_snapshot_available() -> None:
    responses = iter(
        (
            httpx.Response(200, json=_jwks(), headers={"content-type": "application/json"}),
            httpx.Response(503, headers={"content-type": "application/json"}),
        )
    )

    runtime = await GraphSecurityRuntime.open(
        jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
        timeout_seconds=1,
        refresh_interval_seconds=3600,
        referenced_key_ids=_no_references,
        transport=httpx.MockTransport(lambda request: next(responses)),
    )
    try:
        with pytest.raises(JwksRefreshError, match="JWKS_HTTP_UNAVAILABLE"):
            await runtime.refresh_now()

        readiness = runtime.readiness()
        assert readiness.ready is True
        assert readiness.code == "GRAPH_JWKS_READY_CACHED"
        assert readiness.refresh_error_code == "JWKS_HTTP_UNAVAILABLE"
        assert runtime.resolver.snapshot().key_ids == ("graph-key-1",)
    finally:
        await runtime.close()


@pytest.mark.parametrize(
    "url",
    [
        "file:///tmp/jwks.json",
        "https://user:secret@java-api-service/jwks",
        "https://java-api-service/jwks#fragment",
        "relative/jwks",
    ],
)
@pytest.mark.asyncio
async def test_jwks_url_rejects_unsafe_shapes(url: str) -> None:
    with pytest.raises(ValueError, match="JWKS URL"):
        await GraphSecurityRuntime.open(
            jwks_url=url,
            timeout_seconds=1,
            refresh_interval_seconds=3600,
            referenced_key_ids=_no_references,
            transport=httpx.MockTransport(lambda request: httpx.Response(500)),
        )
