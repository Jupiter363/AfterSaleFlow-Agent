import gzip
import json

import httpx
import pytest

from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.target_e2e_room_exchange import (
    JavaTargetE2ERoomExchange,
    TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
    _PROPOSAL_REF,
)


@pytest.mark.asyncio
async def test_room_exchange_forces_identity_encoding_and_rejects_compressed_responses() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            headers={"content-type": "application/json", "content-encoding": "gzip"},
            content=gzip.compress(json.dumps({"ok": True}).encode("utf-8")),
        )

    exchange = JavaTargetE2ERoomExchange(
        java_api_service_url="http://graph-exchange-proxy:8080",
        java_service_secret="service-secret-0123456789",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(GraphContractError, match="CONTENT_ENCODING_INVALID"):
        await exchange._post(  # noqa: SLF001
            TARGET_E2E_ROOM_OBJECT_LOAD_PATH,
            {"value": 1},
            maximum_bytes=1024,
        )

    assert len(requests) == 1
    assert requests[0].headers["accept-encoding"] == "identity"


@pytest.mark.asyncio
async def test_room_exchange_rejects_any_non_frozen_path_before_http() -> None:
    exchange = JavaTargetE2ERoomExchange(
        java_api_service_url="http://graph-exchange-proxy:8080",
        java_service_secret="service-secret-0123456789",
        transport=httpx.MockTransport(lambda _request: pytest.fail("HTTP must not run")),
    )

    with pytest.raises(GraphContractError, match="PATH_REJECTED"):
        await exchange._post(  # noqa: SLF001
            "/internal/evidence/CASE_X/EVIDENCE_X/content",
            {"value": 1},
            maximum_bytes=1024,
        )


def test_room_exchange_accepts_only_exact_room_scoped_content_addressed_refs() -> None:
    digest = "a" * 64

    assert _PROPOSAL_REF.fullmatch(f"urn:target-e2e:proposal:evidence:{digest}")
    assert _PROPOSAL_REF.fullmatch(f"urn:target-e2e:proposal:hearing:{digest}")
    assert _PROPOSAL_REF.fullmatch(f"urn:target-e2e:proposal:review:{digest}")
    assert _PROPOSAL_REF.fullmatch(f"urn:target-e2e:proposal:intake:{digest}") is None
    assert _PROPOSAL_REF.fullmatch("urn:target-e2e:proposal:review:not-a-hash") is None
    assert _PROPOSAL_REF.fullmatch(f"urn:target-e2e:proposal:review:{digest}:suffix") is None
