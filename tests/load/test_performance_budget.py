import json
import os
import statistics
import time
import urllib.error
import urllib.request

import pytest


BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")
REQUESTS = int(os.getenv("LOAD_SMOKE_REQUESTS", "5"))
P95_BUDGET_MS = float(os.getenv("LOAD_SMOKE_P95_BUDGET_MS", "10000"))


def require_gateway() -> None:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
    except OSError as exc:
        pytest.skip(f"local acceptance gateway is not running: {exc}")


def post_case(index: int) -> float:
    payload = {
        "order_id": f"ORDER_LOAD_{int(time.time() * 1000)}_{index}",
        "user_id": "smoke-user",
        "merchant_id": "smoke-merchant",
        "description": "Performance smoke request for ordinary fulfillment query.",
        "channel": "WEB",
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + "/api/v1/cases",
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-User-Id": "smoke-user",
            "X-Role": "USER",
            "Idempotency-Key": f"load-{int(time.time() * 1000)}-{index}",
        },
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            body = json.loads(response.read().decode("utf-8"))
            assert response.status == 201
            assert body["success"] is True
    except urllib.error.HTTPError as error:
        pytest.fail(f"load request failed with {error.code}: {error.read().decode('utf-8')}")
    return (time.perf_counter() - start) * 1000


def test_case_creation_p95_smoke_budget() -> None:
    require_gateway()
    durations = [post_case(index) for index in range(REQUESTS)]
    p95 = max(durations) if len(durations) < 20 else statistics.quantiles(durations, n=20)[18]
    assert p95 < P95_BUDGET_MS, {"p95_ms": p95, "durations_ms": durations}
