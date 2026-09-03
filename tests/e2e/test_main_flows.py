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
BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
JAVA_BASE_URL = os.getenv(
    "ACCEPTANCE_JAVA_BASE_URL", "http://127.0.0.1:8080"
).rstrip("/")
INTERNAL_BASE_URL = os.getenv("ACCEPTANCE_INTERNAL_BASE_URL", JAVA_BASE_URL).rstrip(
    "/"
)
HEALTH_BASE_URL = os.getenv("ACCEPTANCE_HEALTH_BASE_URL", JAVA_BASE_URL).rstrip(
    "/"
)
LIVE_ENABLED = os.getenv("RUN_LIVE_HEARING_V2_E2E") == "1"


def decode_response_body(body: str):
    if not body:
        return {}
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {"raw_body": body}


def test_response_decoder_preserves_non_json_gateway_errors():
    assert decode_response_body("") == {}
    assert decode_response_body('{"error":"bad gateway"}') == {"error": "bad gateway"}
    assert decode_response_body("<html>502 Bad Gateway</html>") == {
        "raw_body": "<html>502 Bad Gateway</html>"
    }


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：模块公开业务函数。
# 具体功能：`request` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.Request`、`encode`、`urllib.request.urlopen`。
# 上下游：上游为 本文件的 `test_seeded_disputes_are_listed_and_enterable_through_nginx`、`test_live_room_flow_reaches_confirmed_settlement_idempotently`；下游为 协作调用 `urllib.request.Request`、`encode`、`urllib.request.urlopen`、`decode`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def request(
    method: str,
    path: str,
    *,
    payload: dict | None = None,
    headers: dict | None = None,
    base_url: str = BASE_URL,
):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    provided_headers = headers or {}
    request_headers = {"Content-Type": "application/json"}
    if "X-Service-Identity" not in provided_headers:
        request_headers.update(
            {
                "X-User-Id": "user-local",
                "X-Role": "USER",
            }
        )
    request_headers.update(provided_headers)
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers=request_headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            body = response.read().decode("utf-8")
            return response.status, decode_response_body(body)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, decode_response_body(body)


def system_headers() -> dict[str, str]:
    secret = os.getenv("JAVA_SERVICE_SECRET") or os.getenv(
        "TARGET_E2E_JAVA_SERVICE_SECRET"
    )
    if not secret:
        pytest.skip(
            "JAVA_SERVICE_SECRET or TARGET_E2E_JAVA_SERVICE_SECRET is required "
            "for internal import E2E"
        )
    return {
        "X-Service-Identity": "external-dispute-adapter",
        "X-Service-Secret": secret,
    }


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
            return response.status, decode_response_body(body)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, decode_response_body(body)


# 所属模块：跨服务契约测试 > test_main_flows；函数角色：模块公开业务函数。
# 具体功能：`require_gateway` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`urllib.request.urlopen`、`pytest.skip`、`OSError`。
# 上下游：上游为 本文件的 `test_seeded_disputes_are_listed_and_enterable_through_nginx`、`test_live_room_flow_reaches_confirmed_settlement_idempotently`；下游为 协作调用 `urllib.request.urlopen`、`pytest.skip`、`OSError`。
# 系统意义：失败显式映射为 `OSError`，避免错误状态被当成成功结果。
def require_gateway() -> None:
    try:
        with urllib.request.urlopen(
            HEALTH_BASE_URL + "/actuator/health", timeout=3
        ) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
            payload = json.loads(response.read().decode("utf-8"))
            if payload.get("status") != "UP":
                raise OSError(f"unexpected health payload {payload!r}")
    except OSError as exc:
        pytest.skip(f"local Java API is not ready: {exc}")


def test_public_internal_and_health_requests_use_separate_origins(monkeypatch):
    requested_urls: list[str] = []

    class Response:
        status = 200

        def __init__(self, body: bytes):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return self.body

    def fake_urlopen(operation, timeout):
        url = operation if isinstance(operation, str) else operation.full_url
        requested_urls.append(url)
        body = b'{"status":"UP"}' if url.endswith("/actuator/health") else b"{}"
        return Response(body)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)

    request("GET", "/api/disputes")
    request(
        "POST",
        "/internal/disputes/import",
        base_url=INTERNAL_BASE_URL,
    )
    require_gateway()

    assert requested_urls == [
        BASE_URL + "/api/disputes",
        INTERNAL_BASE_URL + "/internal/disputes/import",
        HEALTH_BASE_URL + "/actuator/health",
    ]


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
            (ROOT / "apps/domain-service/src/test/java/com/example/dispute/evidence/EvidenceRoomIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "apps/domain-service/src/test/java/com/example/dispute/hearing/HearingFlowRuntimeServiceTest.java").read_text(encoding="utf-8"),
            (ROOT / "apps/domain-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "apps/domain-service/src/test/java/com/example/dispute/executor/ToolExecutorServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "apps/domain-service/src/test/java/com/example/dispute/evaluation/CaseClosureServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "apps/domain-service/src/test/java/com/example/dispute/remedy/RemedyPlannerTest.java").read_text(encoding="utf-8"),
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


@pytest.mark.skipif(
    not LIVE_ENABLED,
    reason="set RUN_LIVE_HEARING_V2_E2E=1 to exercise real Java/Python/model services",
)
def test_live_external_import_simulation_is_idempotent() -> None:
    """Exercise the public external-import adapter and its replay boundary."""
    require_gateway()
    suffix = uuid.uuid4().hex[:16]
    user_id = f"import-user-{suffix}"
    merchant_id = f"import-merchant-{suffix}"
    idempotency_key = f"simulate-import-{suffix}"
    payload = {
        "count": 1,
        "scenario": "watch dispute",
        "risk_level_hint": "MEDIUM",
        "initiator_role_hint": "USER",
        "current_actor_id": user_id,
        "counterparty_actor_id": merchant_id,
        "simulation_batch_id": f"batch-{suffix}",
    }
    actor_headers = {"X-User-Id": user_id, "X-Role": "USER"}

    status, created = request(
        "POST",
        "/api/disputes/import/simulate",
        payload=payload,
        headers={**actor_headers, "Idempotency-Key": idempotency_key},
    )
    assert status == 201, created
    items = created["data"]["items"]
    assert len(items) == 1, created
    imported = items[0]
    assert imported["source_type"] == "EXTERNAL_IMPORT", imported
    assert imported["initiator_role"] == "USER", imported
    case_id = imported["id"]

    status, replayed = request(
        "POST",
        "/api/disputes/import/simulate",
        payload=payload,
        headers={**actor_headers, "Idempotency-Key": idempotency_key},
    )
    assert status == 201, replayed
    assert replayed["data"]["items"][0]["id"] == case_id, replayed

    conflicting_payload = {**payload, "scenario": "different dispute"}
    status, conflict = request(
        "POST",
        "/api/disputes/import/simulate",
        payload=conflicting_payload,
        headers={**actor_headers, "Idempotency-Key": idempotency_key},
    )
    assert status == 409, conflict
    assert conflict.get("success") is False, conflict
    assert conflict.get("code") == "IDEMPOTENCY_CONFLICT", conflict


@pytest.mark.skipif(
    not LIVE_ENABLED,
    reason="set RUN_LIVE_HEARING_V2_E2E=1 to exercise real Java/Python/model services",
)
def test_live_internal_external_import_requires_service_identity() -> None:
    """Keep the internal OMS boundary authenticated and idempotent in live E2E."""
    require_gateway()
    suffix = uuid.uuid4().hex[:16]
    user_id = f"internal-import-user-{suffix}"
    merchant_id = f"internal-import-merchant-{suffix}"
    idempotency_key = f"internal-import-{suffix}"
    payload = {
        "source_system": "external-dispute-adapter",
        "external_case_reference": f"EXT-{suffix}",
        "order_reference": f"ORDER-{suffix}",
        "after_sales_reference": f"AFTER-{suffix}",
        "logistics_reference": f"LOG-{suffix}",
        "user_id": user_id,
        "merchant_id": merchant_id,
        "initiator_role": "USER",
        "dispute_type": "SIGNED_NOT_RECEIVED",
        "title": "External dispute import E2E",
        "description": "External adapter import contract verification.",
        "requested_outcome_hint": "REFUND",
        "risk_level": "LOW",
    }

    status, forbidden = request(
        "POST",
        "/internal/disputes/import",
        payload=payload,
        headers={
            "X-User-Id": user_id,
            "X-Role": "USER",
            "Idempotency-Key": f"{idempotency_key}-user",
        },
        base_url=INTERNAL_BASE_URL,
    )
    assert status == 403, forbidden
    assert forbidden.get("success") is False, forbidden
    assert forbidden.get("code") == "FORBIDDEN", forbidden

    service_headers = system_headers()
    status, imported = request(
        "POST",
        "/internal/disputes/import",
        payload=payload,
        headers={**service_headers, "Idempotency-Key": idempotency_key},
        base_url=INTERNAL_BASE_URL,
    )
    assert status == 201, imported
    imported_case_id = imported["data"]["id"]
    assert imported["data"]["source_type"] == "EXTERNAL_IMPORT", imported

    status, replayed = request(
        "POST",
        "/internal/disputes/import",
        payload=payload,
        headers={**service_headers, "Idempotency-Key": idempotency_key},
        base_url=INTERNAL_BASE_URL,
    )
    assert status == 201, replayed
    assert replayed["data"]["id"] == imported_case_id, replayed

    conflicting_payload = {**payload, "title": "Changed external dispute import"}
    status, conflict = request(
        "POST",
        "/internal/disputes/import",
        payload=conflicting_payload,
        headers={**service_headers, "Idempotency-Key": idempotency_key},
        base_url=INTERNAL_BASE_URL,
    )
    assert status == 409, conflict
    assert conflict.get("success") is False, conflict
    assert conflict.get("code") == "IDEMPOTENCY_CONFLICT", conflict


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

    # The target Temporal Intake lane materializes the imported form as a hidden
    # INITIAL_FORM source.  It is the first accepted command, but it must never
    # masquerade as a visible participant chat message: the Intake officer asks
    # the first room question and the opening response exposes the stable run.
    opening_deadline = time.monotonic() + 60
    opening = None
    while time.monotonic() < opening_deadline:
        status, opening = request(
            "POST",
            f"/api/disputes/{case_id}/rooms/INTAKE/messages/opening",
            headers=user_headers,
        )
        if status == 200:
            break
        if (
            status != 409
            or opening.get("details", {}).get("reason_code")
            != "TARGET_E2E_INTAKE_EPOCH_NOT_READY"
        ):
            break
        time.sleep(1)
    assert status == 200, opening
    opening_data = opening["data"]
    opening_run_id = opening_data.get("run_id") or opening_data.get("runId")
    assert isinstance(opening_run_id, str) and opening_run_id, opening

    status, opening_replay = request(
        "POST",
        f"/api/disputes/{case_id}/rooms/INTAKE/messages/opening",
        headers=user_headers,
    )
    assert status == 200, opening_replay
    replay_data = opening_replay["data"]
    assert (replay_data.get("run_id") or replay_data.get("runId")) == opening_run_id

    status, opening_messages = request(
        "GET",
        f"/api/disputes/{case_id}/rooms/INTAKE/messages",
        headers=user_headers,
    )
    assert status == 200, opening_messages
    assert all(
        (message.get("sender_type") or message.get("senderType")) != "PARTY"
        and (message.get("message_type") or message.get("messageType")) != "PARTY_TEXT"
        for message in opening_messages["data"]
    ), opening_messages

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
            dossier = (
                ((initiator_memory or {}).get("case_intake_dossier") or {}).get(
                    "dossier"
                )
                or {}
            )
            matrix = dossier.get("case_fact_matrix") or {}
            if (
                matrix.get("schema_version") == "case_fact_matrix.v2"
                and matrix.get("matrix_kind") == "INITIATOR_FROZEN"
            ):
                break
        time.sleep(2)
    assert status == 200, initiator_memory_response
    assert initiator_memory is not None
    initiator_dossier = initiator_memory["case_intake_dossier"]["dossier"]
    assert initiator_dossier["schema_version"] == "intake-dossier.v2"
    initiator_matrix = initiator_dossier["case_fact_matrix"]
    assert "unilateral_case_matrix" not in initiator_dossier
    assert "unilateral_case_matrix" not in initiator_memory["scroll_snapshot"]
    assert initiator_matrix["schema_version"] == "case_fact_matrix.v2"
    assert initiator_matrix["matrix_kind"] == "INITIATOR_FROZEN"
    assert initiator_matrix["party_map"] == {
        "initiator_role": "USER",
        "respondent_role": "MERCHANT",
    }
    assert all(
        set(row["positions"]) == {"USER", "MERCHANT"}
        for row in initiator_matrix["fact_rows"]
    )

    status, initiated_messages = request(
        "GET",
        f"/api/disputes/{case_id}/rooms/INTAKE/messages",
        headers=user_headers,
    )
    assert status == 200, initiated_messages
    assert initiated_messages["data"], initiated_messages
    first_message = initiated_messages["data"][0]
    assert (first_message.get("sender_type") or first_message.get("senderType")) == "AGENT"
    assert (first_message.get("agent_run_id") or first_message.get("agentRunId")) == opening_run_id
    initiator_matrix_before_confirmation = json.loads(json.dumps(initiator_matrix))
    initiator_matrix_identity = (
        initiator_matrix["matrix_version"],
        initiator_matrix["content_hash"],
    )
    initiator_confirmation_key = f"initiator-intake-confirm-{suffix}"

    status, accepted = request(
        "POST",
        f"/api/disputes/{case_id}/intake/confirm",
        payload={
            "admissible": True,
            "dispute_type": "SIGNED_NOT_RECEIVED",
            "risk_level": "LOW",
            "confirmation_note": "E2E 受理并进入证据室",
        },
        headers={**user_headers, "Idempotency-Key": initiator_confirmation_key},
    )
    assert status == 200, accepted
    assert accepted["data"]["case_status"] == "INTAKE_COMPLETED"
    assert accepted["data"]["current_room"] == "INTAKE"

    status, accepted_replay = request(
        "POST",
        f"/api/disputes/{case_id}/intake/confirm",
        payload={
            "admissible": True,
            "dispute_type": "SIGNED_NOT_RECEIVED",
            "risk_level": "LOW",
            "confirmation_note": "E2E 受理并进入证据室",
        },
        headers={**user_headers, "Idempotency-Key": initiator_confirmation_key},
    )
    assert status == 200, accepted_replay
    assert accepted_replay["data"] == accepted["data"]

    deadline = time.monotonic() + 120
    initiator_frozen_memory = None
    while time.monotonic() < deadline:
        status, initiator_frozen_response = request(
            "GET",
            f"/api/disputes/{case_id}/rooms/INTAKE/turn-memory/latest",
            headers=user_headers,
        )
        if status == 200 and initiator_frozen_response.get("data") is not None:
            initiator_frozen_memory = initiator_frozen_response["data"]
            matrix_kind = (
                (
                    (initiator_frozen_memory.get("case_intake_dossier") or {}).get(
                        "dossier"
                    )
                    or {}
                )
                .get("case_fact_matrix", {})
                .get("matrix_kind")
            )
            if matrix_kind == "INITIATOR_FROZEN":
                break
        time.sleep(2)
    assert status == 200, initiator_frozen_response
    assert initiator_frozen_memory is not None
    confirmed_matrix = initiator_frozen_memory["case_intake_dossier"]["dossier"][
        "case_fact_matrix"
    ]
    assert confirmed_matrix["matrix_kind"] == "INITIATOR_FROZEN"
    assert (
        confirmed_matrix["matrix_version"],
        confirmed_matrix["content_hash"],
    ) == initiator_matrix_identity
    assert confirmed_matrix == initiator_matrix_before_confirmation
    assert (
        "unilateral_case_matrix"
        not in initiator_frozen_memory["case_intake_dossier"]["dossier"]
    )

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
        merchant_memory["data"]["case_intake_dossier"]["dossier"]["case_fact_matrix"][
            "matrix_kind"
        ]
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
