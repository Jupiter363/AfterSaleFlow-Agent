# 文件作用：自动化测试文件，验证 test_main_flows 相关模块的行为、契约或页面布局。

import json
import os
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：模块公开业务函数。
# 具体功能：`request` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.Request`、`encode`、`urllib.request.urlopen`。
# 上下游：上游为 本文件的 `test_seeded_disputes_are_listed_and_enterable_through_nginx`、`test_live_room_flow_reaches_confirmed_settlement_idempotently`；下游为 协作调用 `urllib.request.Request`、`encode`、`urllib.request.urlopen`、`decode`。
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
        with urllib.request.urlopen(req, timeout=45) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, json.loads(body) if body else {}


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：模块公开业务函数。
# 具体功能：`require_gateway` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.urlopen`、`pytest.skip`、`OSError`。
# 上下游：上游为 本文件的 `test_seeded_disputes_are_listed_and_enterable_through_nginx`、`test_live_room_flow_reaches_confirmed_settlement_idempotently`；下游为 协作调用 `urllib.request.urlopen`、`pytest.skip`、`OSError`。
# 系统意义：失败显式映射为 `OSError`，避免错误状态被当成成功结果。
def require_gateway() -> None:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
    except OSError as exc:
        pytest.skip(f"local acceptance gateway is not running: {exc}")


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：回归测试用例。
# 具体功能：`test_seeded_disputes_are_listed_and_enterable_through_nginx` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `require_gateway`、`request`。
# 系统意义：固定“跨服务契约测试 > test_main_flows”的可观察契约，防止后续重构改变业务结果。
def test_seeded_disputes_are_listed_and_enterable_through_nginx() -> None:
    require_gateway()
    status, response = request("GET", "/api/disputes?page=0&size=20")
    assert status == 200, response
    items = response["data"]["items"]
    assert items
    assert all(item["case_type"] == "DISPUTE" for item in items)

    for item in items:
        case_id = item["id"]
        status, response = request("GET", f"/api/disputes/{case_id}")
        assert status == 200, response
        assert response["data"]["id"] == case_id


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：回归测试用例。
# 具体功能：`test_repository_e2e_flow_coverage_is_not_only_happy_path` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`join`、`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `join`、`read_text`。
# 系统意义：固定“跨服务契约测试 > test_main_flows”的可观察契约，防止后续重构改变业务结果。
def test_repository_e2e_flow_coverage_is_not_only_happy_path() -> None:
    java_tests = "\n".join(
        [
            (ROOT / "java-api-service/src/test/java/com/example/dispute/evidence/EvidenceRoomIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/executor/ToolExecutorServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/evaluation/CaseClosureServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/workflow/DisputeHearingWorkflowTest.java").read_text(encoding="utf-8"),
        ]
    )

    for required in (
        "BOTH_PARTIES_COMPLETED",
        "DEADLINE_EXPIRED",
        "SETTLEMENT_CONFIRMED",
        "PLATFORM_REVIEWER",
        "unapproved",
        "closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation",
    ):
        assert required in java_tests


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：回归测试用例。
# 具体功能：`test_live_room_flow_reaches_confirmed_settlement_idempotently` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`uuid.uuid4`、`json.dumps`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `require_gateway`、`request`。
# 系统意义：固定“跨服务契约测试 > test_main_flows”的可观察契约，防止后续重构改变业务结果。
def test_live_room_flow_reaches_confirmed_settlement_idempotently() -> None:
    require_gateway()
    suffix = uuid.uuid4().hex[:16]
    user_id = f"flow-user-{suffix}"
    merchant_id = f"flow-merchant-{suffix}"
    creation_key = f"create-{suffix}"
    create_payload = {
        "initiator_role": "USER",
        "order_reference": f"ORDER-{suffix}",
        "after_sales_reference": f"AFTER-{suffix}",
        "logistics_reference": f"LOG-{suffix}",
        "user_id": user_id,
        "merchant_id": merchant_id,
        "description": "签收未收到，双方愿意通过平台争议流程协商",
        "attachment_ids": [],
        "channel": "WEB",
    }
    user_headers = {"X-User-Id": user_id, "X-Role": "USER"}
    merchant_headers = {
        "X-User-Id": merchant_id,
        "X-Role": "MERCHANT",
    }

    status, created = request(
        "POST",
        "/api/disputes",
        payload=create_payload,
        headers={**user_headers, "Idempotency-Key": creation_key},
    )
    assert status == 201, created
    case_id = created["data"]["id"]
    assert created["data"]["case_status"] == "INTAKE_COMPLETED"

    status, replayed_create = request(
        "POST",
        "/api/disputes",
        payload=create_payload,
        headers={**user_headers, "Idempotency-Key": creation_key},
    )
    assert status == 201, replayed_create
    assert replayed_create["data"]["id"] == case_id

    status, accepted = request(
        "POST",
        f"/api/disputes/{case_id}/intake/confirm",
        payload={
            "admissible": True,
            "dispute_type": "SIGNED_NOT_RECEIVED",
            "risk_level": "LOW",
            "confirmation_note": "E2E 受理并进入证据室",
        },
        headers=user_headers,
    )
    assert status == 200, accepted
    assert accepted["data"]["case_status"] == "EVIDENCE_OPEN"
    assert accepted["data"]["current_room"] == "EVIDENCE"

    user_completion_key = f"complete-user-{suffix}"
    status, user_completion = request(
        "POST",
        f"/api/disputes/{case_id}/evidence/complete",
        headers={
            **user_headers,
            "Idempotency-Key": user_completion_key,
        },
    )
    assert status == 200, user_completion
    status, replayed_user_completion = request(
        "POST",
        f"/api/disputes/{case_id}/evidence/complete",
        headers={
            **user_headers,
            "Idempotency-Key": user_completion_key,
        },
    )
    assert status == 200, replayed_user_completion
    assert replayed_user_completion["data"] == user_completion["data"]

    status, merchant_completion = request(
        "POST",
        f"/api/disputes/{case_id}/evidence/complete",
        headers={
            **merchant_headers,
            "Idempotency-Key": f"complete-merchant-{suffix}",
        },
    )
    assert status == 200, merchant_completion
    assert merchant_completion["data"]["all_parties_completed"] is True
    assert merchant_completion["data"]["next_room"] == "HEARING"

    status, proposal = request(
        "POST",
        f"/api/disputes/{case_id}/hearing/settlements",
        payload={
            "proposal_text": "退款 50 元并结束争议",
            "proposal_json": json.dumps(
                {"action": "REFUND", "amount": 50, "currency": "CNY"}
            ),
        },
        headers=merchant_headers,
    )
    assert status == 200, proposal
    version = proposal["data"]["version"]

    status, user_confirmation = request(
        "POST",
        f"/api/disputes/{case_id}/hearing/settlements/{version}/confirm",
        headers={
            **user_headers,
            "Idempotency-Key": f"settlement-user-{suffix}",
        },
    )
    assert status == 200, user_confirmation
    assert user_confirmation["data"]["status"] == "PENDING_CONFIRMATION"

    merchant_confirmation_headers = {
        **merchant_headers,
        "Idempotency-Key": f"settlement-merchant-{suffix}",
    }
    status, merchant_confirmation = request(
        "POST",
        f"/api/disputes/{case_id}/hearing/settlements/{version}/confirm",
        headers=merchant_confirmation_headers,
    )
    assert status == 200, merchant_confirmation
    assert merchant_confirmation["data"]["status"] == "CONFIRMED"
    assert set(merchant_confirmation["data"]["confirmed_roles"]) == {
        "USER",
        "MERCHANT",
    }

    status, replayed_final_confirmation = request(
        "POST",
        f"/api/disputes/{case_id}/hearing/settlements/{version}/confirm",
        headers=merchant_confirmation_headers,
    )
    assert status == 200, replayed_final_confirmation
    assert replayed_final_confirmation["data"] == merchant_confirmation["data"]
