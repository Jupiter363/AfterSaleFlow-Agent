# 文件作用：自动化测试文件，验证 test_main_flows 相关模块的行为、契约或页面布局。

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:18080")
LIVE_ENABLED = os.getenv("RUN_LIVE_HEARING_V2_E2E") == "1"


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


def upload_text_evidence(case_id: str, user_id: str, content: str):
    boundary = f"----main-flow-e2e-{uuid.uuid4().hex}"
    multipart = b"".join(
        (
            f"--{boundary}\r\n".encode("ascii"),
            b'Content-Disposition: form-data; name="file"; filename="delivery-note.txt"\r\n',
            b"Content-Type: text/plain\r\n\r\n",
            content.encode("utf-8"),
            b"\r\n",
            f"--{boundary}--\r\n".encode("ascii"),
        )
    )
    query = urllib.parse.urlencode(
        {
            "evidence_type": "OTHER",
            "source_type": "USER_UPLOAD",
            "visibility": "PARTIES",
            "model_processing_authorized": "true",
            "claimed_fact": "The parcel was marked delivered but was not received.",
            "truth_attested": "true",
        }
    )
    operation = urllib.request.Request(
        f"{BASE_URL}/api/disputes/{case_id}/evidence?{query}",
        data=multipart,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(multipart)),
            "X-User-Id": user_id,
            "X-Role": "USER",
        },
    )
    try:
        with urllib.request.urlopen(operation, timeout=120) as response:
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
            (ROOT / "java-api-service/src/test/java/com/example/dispute/hearing/HearingFlowRuntimeServiceTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/executor/ToolExecutorServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/evaluation/CaseClosureServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/remedy/RemedyPlannerTest.java").read_text(encoding="utf-8"),
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
@pytest.mark.skipif(
    not LIVE_ENABLED,
    reason="set RUN_LIVE_HEARING_V2_E2E=1 to exercise real Java/Python/model services",
)
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

    deadline = time.monotonic() + 300
    initiator_memory = None
    initiator_memory_response = None
    while time.monotonic() < deadline:
        status, initiator_memory_response = request(
            "GET",
            f"/api/disputes/{case_id}/rooms/INTAKE/turn-memory/latest",
            headers=user_headers,
        )
        if status == 200 and initiator_memory_response.get("data") is not None:
            initiator_memory = initiator_memory_response["data"]
            matrix_kind = (
                ((initiator_memory or {}).get("scroll_snapshot") or {})
                .get("case_fact_matrix", {})
                .get("matrix_kind")
            )
            if matrix_kind == "INITIATOR_FROZEN":
                break
        time.sleep(2)
    assert status == 200, initiator_memory_response
    assert initiator_memory is not None
    assert (
        initiator_memory["scroll_snapshot"]["case_fact_matrix"]["matrix_kind"]
        == "INITIATOR_FROZEN"
    )

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
    assert accepted["data"]["case_status"] == "INTAKE_COMPLETED"
    assert accepted["data"]["current_room"] == "INTAKE"

    status, merchant_statement = request(
        "POST",
        f"/api/disputes/{case_id}/rooms/INTAKE/messages",
        payload={
            "message_type": "PARTY_TEXT",
            "text": (
                "The merchant confirms the order record and agrees that the platform "
                "should resolve the signed-but-not-received dispute from both accounts."
            ),
            "attachment_refs": [],
        },
        headers={
            **merchant_headers,
            "Idempotency-Key": f"merchant-intake-statement-{suffix}",
        },
    )
    assert status == 201, merchant_statement
    merchant_run_id = merchant_statement["data"]["agent_run_id"]
    deadline = time.monotonic() + 300
    merchant_run = None
    while time.monotonic() < deadline:
        status, run_response = request(
            "GET",
            f"/api/agent-runs/{merchant_run_id}",
            headers=merchant_headers,
        )
        assert status == 200, run_response
        merchant_run = run_response["data"]
        if merchant_run["status"] in {"COMPLETED", "FAILED"}:
            break
        time.sleep(2)
    assert merchant_run is not None
    assert merchant_run["status"] == "COMPLETED", merchant_run

    status, merchant_memory = request(
        "GET",
        f"/api/disputes/{case_id}/rooms/INTAKE/turn-memory/latest",
        headers=merchant_headers,
    )
    assert status == 200, merchant_memory
    assert (
        merchant_memory["data"]["scroll_snapshot"]["case_fact_matrix"]["matrix_kind"]
        == "BILATERAL_FROZEN"
    )

    status, respondent_accepted = request(
        "POST",
        f"/api/disputes/{case_id}/intake/confirm",
        payload={
            "admissible": True,
            "dispute_type": "SIGNED_NOT_RECEIVED",
            "risk_level": "LOW",
            "confirmation_note": "E2E respondent intake confirmation",
        },
        headers=merchant_headers,
    )
    assert status == 200, respondent_accepted
    assert respondent_accepted["data"]["case_status"] == "EVIDENCE_OPEN"
    assert respondent_accepted["data"]["current_room"] == "EVIDENCE"

    status, uploaded = upload_text_evidence(
        case_id,
        user_id,
        "The delivery system marked this parcel delivered, but the user did not receive it.",
    )
    assert status == 201, uploaded
    evidence_id = uploaded["data"]["id"]

    deadline = time.monotonic() + 180
    evidence_catalog = None
    evidence_parsed = False
    while time.monotonic() < deadline:
        status, catalog_response = request(
            "GET",
            f"/api/disputes/{case_id}/evidence",
            headers=user_headers,
        )
        assert status == 200, catalog_response
        evidence_catalog = catalog_response["data"]
        evidence_parsed = any(
            item["evidence_id"] == evidence_id and bool(item.get("parsed_text"))
            for item in evidence_catalog["items"]
        )
        if evidence_parsed:
            break
        time.sleep(2)
    assert evidence_catalog is not None
    assert evidence_parsed, evidence_catalog

    status, submitted = request(
        "POST",
        f"/api/disputes/{case_id}/evidence/submissions",
        payload={
            "evidence_ids": [evidence_id],
            "batch_note": "User submitted the delivery record for formal review.",
        },
        headers={
            **user_headers,
            "Idempotency-Key": f"evidence-submit-user-{suffix}",
        },
    )
    assert status == 200, submitted
    assert submitted["data"]["submit_status"] == "SUBMITTED"
    evidence_run_id = submitted["data"]["room_message"]["agent_run_id"]
    assert evidence_run_id

    deadline = time.monotonic() + 300
    evidence_run = None
    while time.monotonic() < deadline:
        status, run_response = request(
            "GET",
            f"/api/agent-runs/{evidence_run_id}",
            headers=user_headers,
        )
        assert status == 200, run_response
        evidence_run = run_response["data"]
        if evidence_run["status"] in {"COMPLETED", "FAILED"}:
            break
        time.sleep(2)
    assert evidence_run is not None
    assert evidence_run["status"] == "COMPLETED", evidence_run

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
