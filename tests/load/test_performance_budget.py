# 文件作用：自动化测试文件，验证 test_performance_budget 相关模块的行为、契约或页面布局。

import os
import statistics
import time
import urllib.request

import pytest


BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")
REQUESTS = int(os.getenv("LOAD_SMOKE_REQUESTS", "5"))
P95_BUDGET_MS = float(os.getenv("LOAD_SMOKE_P95_BUDGET_MS", "10000"))


# 所属模块：跨服务契约测试 > test_performance_budget；函数角色：模块公开业务函数。
# 具体功能：`require_gateway` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.urlopen`、`pytest.skip`、`OSError`。
# 上下游：上游为 本文件的 `test_dispute_overview_p95_smoke_budget`；下游为 协作调用 `urllib.request.urlopen`、`pytest.skip`、`OSError`。
# 系统意义：失败显式映射为 `OSError`，避免错误状态被当成成功结果。
def require_gateway() -> None:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
    except OSError as exc:
        pytest.skip(f"local acceptance gateway is not running: {exc}")


# 所属模块：跨服务契约测试 > test_performance_budget；函数角色：模块公开业务函数。
# 具体功能：`list_disputes` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.Request`、`time.perf_counter`、`urllib.request.urlopen`。
# 上下游：上游为 本文件的 `test_dispute_overview_p95_smoke_budget`；下游为 协作调用 `urllib.request.Request`、`time.perf_counter`、`urllib.request.urlopen`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
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


# 所属模块：跨服务契约测试 > test_performance_budget；函数角色：回归测试用例。
# 具体功能：`test_dispute_overview_p95_smoke_budget` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`statistics.quantiles`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `require_gateway`、`list_disputes`。
# 系统意义：固定“跨服务契约测试 > test_performance_budget”的可观察契约，防止后续重构改变业务结果。
def test_dispute_overview_p95_smoke_budget() -> None:
    require_gateway()
    durations = [list_disputes(index) for index in range(REQUESTS)]
    p95 = max(durations) if len(durations) < 20 else statistics.quantiles(durations, n=20)[18]
    assert p95 < P95_BUDGET_MS, {"p95_ms": p95, "durations_ms": durations}
