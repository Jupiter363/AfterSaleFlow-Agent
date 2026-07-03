import os
import statistics
import time
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


def list_disputes(_: int) -> float:
    req = urllib.request.Request(
        BASE_URL + "/api/disputes?page=0&size=20",
        method="GET",
        headers={
            "X-User-Id": "user-local",
            "X-Role": "USER",
        },
    )
    start = time.perf_counter()
    with urllib.request.urlopen(req, timeout=60) as response:
        assert response.status == 200
    return (time.perf_counter() - start) * 1000


def test_dispute_overview_p95_smoke_budget() -> None:
    require_gateway()
    durations = [list_disputes(index) for index in range(REQUESTS)]
    p95 = max(durations) if len(durations) < 20 else statistics.quantiles(durations, n=20)[18]
    assert p95 < P95_BUDGET_MS, {"p95_ms": p95, "durations_ms": durations}
