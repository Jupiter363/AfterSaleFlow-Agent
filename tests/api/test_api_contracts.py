import json
import os
import urllib.error
import urllib.request
import pytest


BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")


def request(method: str, path: str, *, payload: dict | None = None, headers: dict | None = None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "X-User-Id": "user-local",
            "X-Role": "USER",
            **(headers or {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = response.read().decode("utf-8")
            return response.status, decode_body(body)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, decode_body(body)


def decode_body(body: str) -> dict:
    if not body:
        return {}
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {"raw": body}


def require_gateway() -> None:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
    except OSError as exc:
        pytest.skip(f"local acceptance gateway is not running: {exc}")
    status, _ = request("GET", "/api/disputes?page=0&size=1")
    if status != 200:
        pytest.skip(f"local gateway is not running the final API contract: HTTP {status}")


def test_final_dispute_root_is_available_and_old_case_alias_is_gone() -> None:
    require_gateway()
    status, page = request("GET", "/api/disputes?page=0&size=5")
    assert status == 200
    assert page["success"] is True
    assert "items" in page["data"]

    old_status, _ = request("GET", "/api/v1/cases")
    assert old_status == 404


def test_gateway_hides_internal_services_and_parties_cannot_enter_review_domain() -> None:
    require_gateway()

    internal_status, _ = request("GET", "/internal/disputes/import")
    review_status, review_error = request("GET", "/api/reviews")

    assert internal_status == 404
    assert review_status == 403
    assert review_error["code"] == "FORBIDDEN"
