"""Opt-in live acceptance test for the complete ``hearing_flow.v2`` chain."""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Callable

import pytest


BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")
LIVE_ENABLED = os.getenv("RUN_LIVE_HEARING_V2_E2E") == "1"
POLL_SECONDS = float(os.getenv("HEARING_V2_E2E_POLL_SECONDS", "2"))
TIMEOUT_SECONDS = int(os.getenv("HEARING_V2_E2E_TIMEOUT_SECONDS", "1200"))


def request(
    method: str,
    path: str,
    *,
    actor_id: str,
    role: str,
    payload: dict[str, Any] | None = None,
    idempotency_key: str | None = None,
) -> dict[str, Any]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "X-User-Id": actor_id,
        "X-Role": role,
    }
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    operation = urllib.request.Request(
        BASE_URL + path,
        data=body,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(operation, timeout=120) as response:
            envelope = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        failure_body = error.read().decode("utf-8")
        raise AssertionError(
            f"{method} {path} returned HTTP {error.code}: {failure_body}"
        ) from error
    assert envelope.get("success") is True, envelope
    return envelope.get("data")


def wait_until(
    description: str,
    loader: Callable[[], Any],
    predicate: Callable[[Any], bool],
    *,
    timeout: int = TIMEOUT_SECONDS,
) -> Any:
    deadline = time.monotonic() + timeout
    last_value: Any = None
    while time.monotonic() < deadline:
        last_value = loader()
        if predicate(last_value):
            return last_value
        time.sleep(POLL_SECONDS)
    raise AssertionError(f"timed out waiting for {description}; last value={last_value!r}")


def upload_text_evidence(case_id: str, actor_id: str, content: str) -> dict[str, Any]:
    boundary = f"----hearing-v2-e2e-{uuid.uuid4().hex}"
    file_payload = content.encode("utf-8")
    multipart = b"".join(
        (
            f"--{boundary}\r\n".encode("ascii"),
            b'Content-Disposition: form-data; name="file"; filename="user-failure-note.txt"\r\n',
            b"Content-Type: text/plain\r\n\r\n",
            file_payload,
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
            "claimed_fact": "空气炸锅首次通电后无法加热且外观无撞击痕迹",
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
            "X-User-Id": actor_id,
            "X-Role": "USER",
        },
    )
    try:
        with urllib.request.urlopen(operation, timeout=120) as response:
            envelope = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        failure_body = error.read().decode("utf-8")
        raise AssertionError(
            f"evidence upload returned HTTP {error.code}: {failure_body}"
        ) from error
    assert envelope.get("success") is True, envelope
    return envelope["data"]


def role_targets(item: dict[str, Any], role: str) -> bool:
    return role in (item.get("target_roles") or item.get("targetRoles") or [])


@pytest.mark.skipif(
    not LIVE_ENABLED,
    reason="set RUN_LIVE_HEARING_V2_E2E=1 to exercise real Java/Python/model services",
)
def test_live_hearing_flow_v2_reaches_execution_assistant_handoff() -> None:
    suffix = uuid.uuid4().hex[:16]
    user_id = f"v2-user-{suffix}"
    merchant_id = f"v2-merchant-{suffix}"

    created = request(
        "POST",
        "/api/disputes",
        actor_id=user_id,
        role="USER",
        idempotency_key=f"create-v2-{suffix}",
        payload={
            "initiator_role": "USER",
            "order_reference": f"ORDER-V2-{suffix}",
            "after_sales_reference": f"AFTER-V2-{suffix}",
            "logistics_reference": f"LOG-V2-{suffix}",
            "user_id": user_id,
            "merchant_id": merchant_id,
            "description": (
                "用户称空气炸锅签收后首次使用即无法加热，要求退货退款；"
                "商家称出库检测正常，但同意由平台核验责任。"
            ),
            "attachment_ids": [],
            "channel": "WEB",
        },
    )
    case_id = created["id"]
    print(f"live hearing_flow.v2 case: {case_id}")

    wait_until(
        "initiator intake matrix",
        lambda: request(
            "GET",
            f"/api/disputes/{case_id}/rooms/INTAKE/turn-memory/latest",
            actor_id=user_id,
            role="USER",
        ),
        lambda memory: (
            ((memory or {}).get("scroll_snapshot") or {})
            .get("case_fact_matrix", {})
            .get("matrix_kind")
            == "INITIATOR_FROZEN"
        ),
        timeout=300,
    )

    accepted = request(
        "POST",
        f"/api/disputes/{case_id}/intake/confirm",
        actor_id=user_id,
        role="USER",
        payload={
            "admissible": True,
            "dispute_type": "QUALITY_NOT_AS_DESCRIBED",
            "risk_level": "LOW",
            "confirmation_note": "V2 live E2E accepted for full hearing",
        },
    )
    assert accepted["case_status"] == "INTAKE_COMPLETED"

    merchant_statement = request(
        "POST",
        f"/api/disputes/{case_id}/rooms/INTAKE/messages",
        actor_id=merchant_id,
        role="MERCHANT",
        idempotency_key=f"merchant-intake-statement-{suffix}",
        payload={
            "message_type": "PARTY_TEXT",
            "text": (
                "商家确认该订单设备出库检测记录正常，但用户描述的首次通电不加热"
                "可能属于产品或运输故障，同意平台结合证据判断并接受合理处理。"
            ),
            "attachment_refs": [],
        },
    )
    merchant_run_id = merchant_statement["agent_run_id"]
    merchant_run = wait_until(
        "merchant intake AgentRun completion",
        lambda: request(
            "GET",
            f"/api/agent-runs/{merchant_run_id}",
            actor_id=merchant_id,
            role="MERCHANT",
        ),
        lambda run: run["status"] in {"COMPLETED", "FAILED"},
        timeout=300,
    )
    assert merchant_run["status"] == "COMPLETED", merchant_run
    merchant_memory = request(
        "GET",
        f"/api/disputes/{case_id}/rooms/INTAKE/turn-memory/latest",
        actor_id=merchant_id,
        role="MERCHANT",
    )
    assert (
        merchant_memory["scroll_snapshot"]["case_fact_matrix"]["matrix_kind"]
        == "BILATERAL_FROZEN"
    )
    respondent_confirmed = request(
        "POST",
        f"/api/disputes/{case_id}/intake/confirm",
        actor_id=merchant_id,
        role="MERCHANT",
        payload={
            "admissible": True,
            "dispute_type": "QUALITY_NOT_AS_DESCRIBED",
            "risk_level": "LOW",
            "confirmation_note": "Merchant completed the bilateral V2 intake matrix",
        },
    )
    assert respondent_confirmed["case_status"] == "EVIDENCE_OPEN"

    uploaded = upload_text_evidence(
        case_id,
        user_id,
        "2026-07-15 10:00 首次通电测试：风扇转动但加热管不发热，设备外观无撞击痕迹。",
    )
    evidence_id = uploaded["id"]
    wait_until(
        "uploaded evidence OCR result",
        lambda: request(
            "GET",
            f"/api/disputes/{case_id}/evidence",
            actor_id=user_id,
            role="USER",
        ),
        lambda catalog: any(
            item["evidence_id"] == evidence_id and bool(item.get("parsed_text"))
            for item in catalog["items"]
        ),
        timeout=180,
    )
    submitted = request(
        "POST",
        f"/api/disputes/{case_id}/evidence/submissions",
        actor_id=user_id,
        role="USER",
        idempotency_key=f"evidence-submit-user-{suffix}",
        payload={
            "evidence_ids": [evidence_id],
            "batch_note": "用户提交首次通电故障记录。",
        },
    )
    assert submitted["submit_status"] == "SUBMITTED"
    evidence_run_id = submitted["room_message"]["agent_run_id"]
    assert evidence_run_id
    evidence_run = wait_until(
        "evidence submission AgentRun completion",
        lambda: request(
            "GET",
            f"/api/agent-runs/{evidence_run_id}",
            actor_id=user_id,
            role="USER",
        ),
        lambda run: run["status"] in {"COMPLETED", "FAILED"},
        timeout=300,
    )
    assert evidence_run["status"] == "COMPLETED", evidence_run

    for actor_id, role in ((user_id, "USER"), (merchant_id, "MERCHANT")):
        completion = request(
            "POST",
            f"/api/disputes/{case_id}/evidence/complete",
            actor_id=actor_id,
            role=role,
            idempotency_key=f"evidence-complete-{role.lower()}-{suffix}",
        )
    assert completion["all_parties_completed"] is True
    assert completion["next_room"] == "HEARING"

    def hearing(actor_id: str = user_id, role: str = "USER") -> dict[str, Any]:
        value = request(
            "GET",
            f"/api/disputes/{case_id}/hearing",
            actor_id=actor_id,
            role=role,
        )
        if value["status"].get("flow_status") == "FAILED":
            raise AssertionError(f"hearing flow failed: {value['status']!r}")
        return value

    answers_open = wait_until(
        "PARTY_ANSWERS_OPEN",
        hearing,
        lambda value: value["status"]["flow_stage"] == "PARTY_ANSWERS_OPEN",
    )
    question_set = answers_open["question_set"]
    assert question_set["schema_version"] == "hearing_question_set.v1"
    assert 1 <= len(question_set["questions"]) <= 5

    issue_set_id = question_set.get("issue_set_id") or question_set["question_set_id"]
    statement_actions = []
    for actor_id, role in ((user_id, "USER"), (merchant_id, "MERCHANT")):
        statement_actions.append(
            request(
                "POST",
                f"/api/disputes/{case_id}/hearing/statements",
                actor_id=actor_id,
                role=role,
                payload={
                    "schema_version": "hearing_party_statement.v1",
                    "issue_set_id": issue_set_id,
                    "statement_text": (
                        "用户围绕全部争议点陈述：设备首次通电后无法加热，"
                        "外观无明显撞击，因此要求退货退款。"
                        if role == "USER"
                        else "商家围绕全部争议点陈述：设备出库检测记录正常，"
                        "但尚无法排除运输或产品故障，同意平台结合证据裁判。"
                    ),
                    "source_message_ids": [],
                },
            )
        )
    assert {
        (item["schema_version"], item["participant_role"])
        for item in statement_actions
    } == {
        ("hearing_party_statement.v1", "USER"),
        ("hearing_party_statement.v1", "MERCHANT"),
    }

    evidence_open = wait_until(
        "PARTY_EVIDENCE_OPEN",
        hearing,
        lambda value: value["status"]["flow_stage"] == "PARTY_EVIDENCE_OPEN",
    )
    evidence_request_set = evidence_open["evidence_request_set"]
    assert evidence_request_set["schema_version"] == "hearing_evidence_request_set.v1"
    assert len(evidence_request_set["requests"]) <= 10

    for actor_id, role in ((user_id, "USER"), (merchant_id, "MERCHANT")):
        applicable_ids = [
            item["request_id"]
            for item in evidence_request_set["requests"]
            if role_targets(item, role)
        ]
        request(
            "POST",
            f"/api/disputes/{case_id}/hearing/evidence-batches",
            actor_id=actor_id,
            role=role,
            payload={
                "schema_version": "hearing_evidence_batch.v1",
                "request_set_id": evidence_request_set["request_set_id"],
                "request_ids": applicable_ids,
                "evidence_ids": [],
                "batch_note": "本方确认当前没有其他可补充材料。",
            },
        )

    closed = wait_until(
        "CLOSED hearing flow with review gate",
        hearing,
        lambda value: (
            value["status"]["flow_stage"] == "CLOSED"
            and value["status"]["review_gate_ready"] is True
        ),
    )
    trial_dossier = closed["trial_dossier"]
    assert trial_dossier["schema_version"] == "trial_dossier.v1"
    assert set(closed["decision_chain"]) == {
        "JUDGE_PROPOSAL",
        "JURY_REVIEW_REPORT",
        "ADJUDICATION_DRAFT",
    }
    assert closed["status"]["latest_draft_id"]

    reviewer_tasks = wait_until(
        "pending human review task",
        lambda: request(
            "GET",
            "/api/reviews?status=PENDING",
            actor_id="reviewer-local",
            role="PLATFORM_REVIEWER",
        ),
        lambda items: any(item["case_id"] == case_id for item in items),
        timeout=120,
    )
    task = next(item for item in reviewer_tasks if item["case_id"] == case_id)
    task_id = task["id"]
    packet = request(
        "GET",
        f"/api/reviews/{task_id}/packet",
        actor_id="reviewer-local",
        role="PLATFORM_REVIEWER",
    )
    assert packet["case_id"] == case_id
    assert packet["prompt_version"] == "hearing-flow.v2"
    assert len(packet["agent_run_refs"]) == 3

    started = request(
        "POST",
        f"/api/reviews/{task_id}/start",
        actor_id="reviewer-local",
        role="PLATFORM_REVIEWER",
    )
    assert started["status"] == "IN_REVIEW"

    decision = request(
        "POST",
        f"/api/reviews/{task_id}/decision",
        actor_id="reviewer-local",
        role="PLATFORM_REVIEWER",
        idempotency_key=f"approve-v2-{suffix}",
        payload={
            "decision": "APPROVE",
            "reason": "V2 live E2E verified the frozen dossier and complete decision chain.",
            "approved_plan": None,
        },
    )
    assert decision["case_status"] == "APPROVED_FOR_EXECUTION"
    assert decision["execution_allowed"] is True

    events = request(
        "GET",
        f"/api/disputes/{case_id}/events/replay?after_sequence=0",
        actor_id=user_id,
        role="USER",
    )
    event_types = {
        item.get("event_type") or item.get("eventType") or item.get("type")
        for item in events
    }
    assert "EXECUTION_ASSISTANT_HANDOFF" in event_types
