from __future__ import annotations

from collections.abc import Iterable

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.security.graph_runtime import (
    GraphSecurityRuntime,
    GraphSecurityRuntimeError,
    _open_for_lifecycle,
)
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


@pytest.fixture
def runtime_launcher():
    return _open_for_lifecycle


@pytest.mark.asyncio
async def test_open_loads_keys_before_reporting_ready_and_closes_idempotently(
    runtime_launcher,
) -> None:
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        assert request.headers["accept"] == "application/jwk-set+json, application/json"
        return httpx.Response(200, json=_jwks(), headers={"content-type": "application/json"})

    runtime = await runtime_launcher(
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
async def test_initial_fetch_failure_never_returns_a_runtime(runtime_launcher) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "https://attacker.example/jwks"})

    with pytest.raises(JwksRefreshError, match="JWKS_HTTP_UNAVAILABLE"):
        await runtime_launcher(
            jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
            timeout_seconds=1,
            refresh_interval_seconds=3600,
            referenced_key_ids=_no_references,
            transport=httpx.MockTransport(handler),
        )


@pytest.mark.asyncio
async def test_refresh_failure_keeps_the_last_immutable_snapshot_available(
    runtime_launcher,
) -> None:
    responses = iter(
        (
            httpx.Response(200, json=_jwks(), headers={"content-type": "application/json"}),
            httpx.Response(503, headers={"content-type": "application/json"}),
        )
    )

    runtime = await runtime_launcher(
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
async def test_jwks_url_rejects_unsafe_shapes(url: str, runtime_launcher) -> None:
    with pytest.raises(ValueError, match="JWKS URL"):
        await runtime_launcher(
            jwks_url=url,
            timeout_seconds=1,
            refresh_interval_seconds=3600,
            referenced_key_ids=_no_references,
            transport=httpx.MockTransport(lambda request: httpx.Response(500)),
        )


def test_public_open_and_direct_runtime_construction_are_unavailable() -> None:
    assert "open" not in GraphSecurityRuntime.__dict__

    with pytest.raises(
        GraphSecurityRuntimeError,
        match="GRAPH_SECURITY_RUNTIME_BOOTSTRAP_REQUIRED",
    ):
        GraphSecurityRuntime(
            resolver=object(),
            manager=object(),
            client=object(),
        )


@pytest.mark.asyncio
async def test_public_attacker_url_cannot_mint_a_registered_runtime() -> None:
    fetches = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal fetches
        del request
        fetches += 1
        return httpx.Response(200, json=_jwks())

    public_open = getattr(GraphSecurityRuntime, "open", None)
    assert public_open is None
    assert fetches == 0


@pytest.mark.asyncio
async def test_runtime_exposes_only_a_sealed_read_only_resolver_capability() -> None:
    runtime = await _open_for_lifecycle(
        jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
        timeout_seconds=1,
        refresh_interval_seconds=3600,
        referenced_key_ids=_no_references,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json=_jwks(),
                headers={"content-type": "application/json"},
            )
        ),
    )
    try:
        resolver = runtime.resolver
        assert not hasattr(resolver, "install")
        assert resolver.snapshot().generation == 1

        returned = resolver.resolve("graph-key-1")
        attacker_key = ec.generate_private_key(ec.SECP256R1())
        object.__setattr__(returned, "public_key", attacker_key.public_key())
        detached = resolver.resolve("graph-key-1")
        assert detached.public_key.public_numbers() != attacker_key.public_key().public_numbers()

        original = runtime._resolver
        try:
            object.__setattr__(runtime, "_resolver", object())
            with pytest.raises(
                GraphSecurityRuntimeError,
                match="GRAPH_SECURITY_RUNTIME_INVALID",
            ):
                runtime.capture_verification_snapshot()
        finally:
            object.__setattr__(runtime, "_resolver", original)
    finally:
        await runtime.close()


@pytest.mark.asyncio
async def test_registered_runtime_rejects_full_slot_transplant() -> None:
    async def opened() -> GraphSecurityRuntime:
        return await _open_for_lifecycle(
            jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
            timeout_seconds=1,
            refresh_interval_seconds=3600,
            referenced_key_ids=_no_references,
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json=_jwks(),
                    headers={"content-type": "application/json"},
                )
            ),
        )

    source = await opened()
    target = await opened()
    original_target_slots = {
        slot: getattr(target, slot)
        for slot in GraphSecurityRuntime.__slots__
        if slot != "__weakref__"
    }
    try:
        for slot in GraphSecurityRuntime.__slots__:
            if slot != "__weakref__":
                object.__setattr__(target, slot, getattr(source, slot))

        with pytest.raises(
            GraphSecurityRuntimeError,
            match="GRAPH_SECURITY_RUNTIME_INVALID",
        ):
            target.readiness()
    finally:
        for slot, value in original_target_slots.items():
            object.__setattr__(target, slot, value)
        await target.close()
        await source.close()


@pytest.mark.asyncio
async def test_unregistered_snapshot_clone_is_rejected_and_close_revokes_snapshot() -> None:
    runtime = await _open_for_lifecycle(
        jwks_url="https://java-api-service.internal/.well-known/graph-jwks.json",
        timeout_seconds=1,
        refresh_interval_seconds=3600,
        referenced_key_ids=_no_references,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json=_jwks("graph-key-snapshot"),
                headers={"content-type": "application/json"},
            )
        ),
    )
    snapshot = runtime.capture_verification_snapshot()
    clone = object.__new__(type(snapshot))
    for slot in type(snapshot).__slots__:
        if slot != "__weakref__":
            object.__setattr__(clone, slot, getattr(snapshot, slot))

    with pytest.raises(
        GraphSecurityRuntimeError,
        match="GRAPH_SECURITY_SNAPSHOT_INVALID",
    ):
        clone.resolve("graph-key-snapshot")

    await runtime.close()
    with pytest.raises(
        GraphSecurityRuntimeError,
        match="GRAPH_SECURITY_RUNTIME_CLOSED",
    ):
        snapshot.resolve("graph-key-snapshot")
