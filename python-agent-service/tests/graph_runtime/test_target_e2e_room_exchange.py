from __future__ import annotations

import httpx
import pytest

from app.contracts.v1.codec import canonicalize
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.target_e2e_room_exchange import (
    JavaTargetE2ERoomExchange,
    TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
)


_SECRET = "target-e2e-service-secret"


@pytest.mark.parametrize(
    "origin",
    [
        "ftp://java.internal",
        "https://user@java.internal",
        "https://java.internal/internal",
        "https://java.internal?query=true",
        "https://java.internal#fragment",
    ],
)
def test_exchange_rejects_ambiguous_java_origins(origin: str) -> None:
    with pytest.raises(ValueError, match="configuration is invalid"):
        JavaTargetE2ERoomExchange(
            java_api_service_url=origin,
            java_service_secret=_SECRET,
        )


def test_exchange_rejects_weak_service_secret_and_unbounded_timeout() -> None:
    with pytest.raises(ValueError, match="configuration is invalid"):
        JavaTargetE2ERoomExchange(
            java_api_service_url="https://java.internal",
            java_service_secret="too-short",
        )
    with pytest.raises(ValueError, match="configuration is invalid"):
        JavaTargetE2ERoomExchange(
            java_api_service_url="https://java.internal",
            java_service_secret=_SECRET,
            timeout_seconds=31,
        )


@pytest.mark.asyncio
async def test_exchange_posts_canonical_json_only_to_the_fixed_java_path() -> None:
    payload = {"z": 2, "a": {"value": 1}}

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert str(request.url) == (
            "https://java.internal" + TARGET_E2E_ROOM_OBJECT_LOAD_PATH
        )
        assert request.headers["x-service-secret"] == _SECRET
        assert request.headers["accept"] == "application/json"
        assert request.headers["content-type"] == "application/json"
        assert request.content == canonicalize(payload)
        return httpx.Response(
            200,
            headers={"content-type": "application/json; charset=utf-8"},
            content=b'{"receipt":"accepted"}',
        )

    exchange = JavaTargetE2ERoomExchange(
        java_api_service_url="https://java.internal",
        java_service_secret=_SECRET,
        transport=httpx.MockTransport(handler),
    )

    assert await exchange._post(  # noqa: SLF001
        TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
        payload,
        maximum_bytes=256,
    ) == {"receipt": "accepted"}


@pytest.mark.asyncio
async def test_exchange_reuses_one_lifecycle_owned_http_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    created: list[httpx.AsyncClient] = []
    real_client = httpx.AsyncClient

    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            headers={"content-type": "application/json"},
            content=b'{"receipt":"accepted"}',
        )

    def build_client(*args: object, **kwargs: object) -> httpx.AsyncClient:
        client = real_client(*args, **kwargs)  # type: ignore[arg-type]
        created.append(client)
        return client

    monkeypatch.setattr(httpx, "AsyncClient", build_client)
    exchange = JavaTargetE2ERoomExchange(
        java_api_service_url="https://java.internal",
        java_service_secret=_SECRET,
        transport=httpx.MockTransport(handler),
    )

    await exchange.aopen()
    for value in (1, 2):
        assert await exchange._post(  # noqa: SLF001
            TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
            {"value": value},
            maximum_bytes=256,
        ) == {"receipt": "accepted"}

    assert len(created) == 1
    await exchange.aclose()
    assert created[0].is_closed
    with pytest.raises(GraphContractError, match="TARGET_E2E_ROOM_EXCHANGE_CLOSED"):
        await exchange._post(  # noqa: SLF001
            TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
            {"value": 3},
            maximum_bytes=256,
        )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("response", "maximum_bytes", "error_code"),
    [
        (
            httpx.Response(307, headers={"location": "https://other.invalid"}),
            256,
            "REJECTED",
        ),
        (
            httpx.Response(200, headers={"content-type": "text/plain"}, content=b"{}"),
            256,
            "MEDIA_TYPE_INVALID",
        ),
        (
            httpx.Response(
                200,
                headers={"content-type": "application/json"},
                content=b'{"value":"too-large"}',
            ),
            16,
            "RESPONSE_TOO_LARGE",
        ),
        (
            httpx.Response(
                200,
                headers={"content-type": "application/json"},
                content=b'{"duplicate":1,"duplicate":2}',
            ),
            256,
            "RESPONSE_INVALID",
        ),
    ],
)
async def test_exchange_fails_closed_on_untrusted_responses(
    response: httpx.Response,
    maximum_bytes: int,
    error_code: str,
) -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return response

    exchange = JavaTargetE2ERoomExchange(
        java_api_service_url="https://java.internal",
        java_service_secret=_SECRET,
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(GraphContractError, match=error_code):
        await exchange._post(  # noqa: SLF001
            TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
            {"request": "bounded"},
            maximum_bytes=maximum_bytes,
        )


@pytest.mark.asyncio
async def test_exchange_maps_transport_failures_without_leaking_details() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("internal endpoint details", request=request)

    exchange = JavaTargetE2ERoomExchange(
        java_api_service_url="https://java.internal",
        java_service_secret=_SECRET,
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(GraphContractError, match="TRANSPORT_FAILED") as captured:
        await exchange._post(  # noqa: SLF001
            TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
            {"request": "bounded"},
            maximum_bytes=256,
        )
    assert "internal endpoint details" not in str(captured.value)
