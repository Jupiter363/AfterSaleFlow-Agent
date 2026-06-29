import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest


BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")
FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "case_payloads.json"


def request(method: str, path: str, *, payload: dict | None = None, headers: dict | None = None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "X-User-Id": "smoke-user",
            "X-Role": "USER",
            **(headers or {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, json.loads(body) if body else {}


def require_gateway() -> None:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
    except OSError as exc:
        pytest.skip(f"local acceptance gateway is not running: {exc}")


@pytest.fixture(scope="module")
def case_payloads() -> dict:
    return json.loads(FIXTURES.read_text(encoding="utf-8"))


def test_case_create_query_list_and_ownership_boundary(case_payloads: dict) -> None:
    require_gateway()
    payload = {
        **case_payloads["regular_fulfillment"],
        "order_id": f"ORDER_API_{int(time.time() * 1000)}",
    }

    status, created = request(
        "POST",
        "/api/v1/cases",
        payload=payload,
        headers={"Idempotency-Key": f"api-{int(time.time() * 1000)}"},
    )
    assert status == 201
    assert created["success"] is True
    case_id = created["data"]["id"]
    assert case_id.startswith("CASE_")
    assert created["trace_id"]
    assert created["request_id"]

    status, queried = request("GET", f"/api/v1/cases/{case_id}")
    assert status == 200
    assert queried["success"] is True
    assert queried["data"]["id"] == case_id

    status, page = request("GET", "/api/v1/cases?page=0&size=5")
    assert status == 200
    assert page["success"] is True
    assert "items" in page["data"]

    status, forbidden = request(
        "GET",
        f"/api/v1/cases/{case_id}",
        headers={"X-User-Id": "other-user"},
    )
    assert status == 403
    assert forbidden["code"] == "FORBIDDEN"


def test_agent_and_ocr_gateway_paths_reach_service_boundaries() -> None:
    require_gateway()

    status, agent_error = request(
        "POST",
        "/agent-api/v1/intake/analyze",
        payload={},
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert status in {401, 422}
    assert agent_error

    status, ocr_error = request(
        "POST",
        "/ocr-api/v1/parse-tasks",
        payload={},
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert status in {401, 422}
    assert ocr_error
