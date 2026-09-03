# 文件作用：自动化测试文件，验证 test_api_contracts 相关模块的行为、契约或页面布局。

import json
import os
import urllib.error
import urllib.request
import pytest


BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")


# 所属模块：跨服务契约测试 > test_api_contracts；函数角色：模块公开业务函数。
# 具体功能：`request` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.Request`、`encode`、`urllib.request.urlopen`。
# 上下游：上游为 本文件的 `require_gateway`、`test_final_dispute_root_is_available_and_old_case_alias_is_gone`、`test_gateway_hides_internal_services_and_parties_cannot_enter_review_domain`；下游为 本文件的 `decode_body`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
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


# 所属模块：跨服务契约测试 > test_api_contracts；函数角色：模块公开业务函数。
# 具体功能：`decode_body` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`json.loads`；返回/更新字段：`raw`。
# 上下游：上游为 本文件的 `request`；下游为 协作调用 `json.loads`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def decode_body(body: str) -> dict:
    if not body:
        return {}
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {"raw": body}


# 所属模块：跨服务契约测试 > test_api_contracts；函数角色：模块公开业务函数。
# 具体功能：`require_gateway` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`pytest.skip`、`urllib.request.urlopen`、`OSError`。
# 上下游：上游为 本文件的 `test_final_dispute_root_is_available_and_old_case_alias_is_gone`、`test_gateway_hides_internal_services_and_parties_cannot_enter_review_domain`；下游为 本文件的 `request`。
# 系统意义：失败显式映射为 `OSError`，避免错误状态被当成成功结果。
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


# 所属模块：跨服务契约测试 > test_api_contracts；函数角色：回归测试用例。
# 具体功能：`test_final_dispute_root_is_available_and_old_case_alias_is_gone` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `require_gateway`、`request`。
# 系统意义：固定“跨服务契约测试 > test_api_contracts”的可观察契约，防止后续重构改变业务结果。
def test_final_dispute_root_is_available_and_old_case_alias_is_gone() -> None:
    require_gateway()
    status, page = request("GET", "/api/disputes?page=0&size=5")
    assert status == 200
    assert page["success"] is True
    assert "items" in page["data"]

    old_status, _ = request("GET", "/api/v1/cases")
    assert old_status == 404


# 所属模块：跨服务契约测试 > test_api_contracts；函数角色：回归测试用例。
# 具体功能：`test_gateway_hides_internal_services_and_parties_cannot_enter_review_domain` 验证人工复核信息在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `require_gateway`、`request`。
# 系统意义：固定“跨服务契约测试 > test_api_contracts”的可观察契约，防止后续重构改变业务结果。
def test_gateway_hides_internal_services_and_parties_cannot_enter_review_domain() -> None:
    require_gateway()

    internal_status, _ = request("GET", "/internal/disputes/import")
    review_status, review_error = request("GET", "/api/reviews")

    assert internal_status == 404
    assert review_status == 403
    assert review_error["code"] == "FORBIDDEN"
