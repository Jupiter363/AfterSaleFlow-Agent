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


def request_result(
    method: str,
    path: str,
    *,
    actor_id: str,
    role: str,
    payload: dict[str, Any] | None = None,
    idempotency_key: str | None = None,
) -> tuple[int, dict[str, Any]]:
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
            status = response.status
            response_body = response.read().decode("utf-8")
            try:
                envelope = json.loads(response_body)
            except json.JSONDecodeError:
                envelope = {"raw_body": response_body}
    except urllib.error.HTTPError as error:
        status = error.code
        failure_body = error.read().decode("utf-8")
        try:
            envelope = json.loads(failure_body)
        except json.JSONDecodeError:
            envelope = {"raw_body": failure_body}
    return status, envelope


def request(
    method: str,
    path: str,
    *,
    actor_id: str,
    role: str,
    payload: dict[str, Any] | None = None,
    idempotency_key: str | None = None,
) -> Any:
    status, envelope = request_result(
        method,
        path,
        actor_id=actor_id,
        role=role,
        payload=payload,
        idempotency_key=idempotency_key,
    )
    assert 200 <= status < 300, {"status": status, "response": envelope}
    assert envelope.get("success") is True, envelope
    return envelope.get("data")


def read_sse_events(
    path: str,
    *,
    actor_id: str,
    role: str,
    last_event_id: str,
    max_events: int = 1,
) -> list[dict[str, Any]]:
    operation = urllib.request.Request(
        BASE_URL + path,
        method="GET",
        headers={
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache",
            "Last-Event-ID": last_event_id,
            "X-User-Id": actor_id,
            "X-Role": role,
        },
    )
    events: list[dict[str, Any]] = []
    with urllib.request.urlopen(operation, timeout=120) as response:
        assert response.status == 200
        assert response.headers.get_content_type() == "text/event-stream"
        frame: dict[str, Any] = {"data_lines": []}
        for raw_line in response:
            line = raw_line.decode("utf-8").rstrip("\r\n")
            if not line:
                if frame["data_lines"]:
                    raw_data = "\n".join(frame.pop("data_lines"))
                    try:
                        frame["data"] = json.loads(raw_data)
                    except json.JSONDecodeError:
                        frame["data"] = raw_data
                    events.append(frame)
                    if len(events) >= max_events:
                        break
                frame = {"data_lines": []}
                continue
            if line.startswith(":"):
                continue
            field, separator, value = line.partition(":")
            if separator and value.startswith(" "):
                value = value[1:]
            if field == "data":
                frame["data_lines"].append(value)
            elif field in {"id", "event", "retry"}:
                frame[field] = value
    assert len(events) == max_events, events
    return events


def assert_error(
    result: tuple[int, dict[str, Any]], expected_status: int, expected_code: str
) -> None:
    status, envelope = result
    assert status == expected_status, envelope
    assert envelope.get("success") is False, envelope
    assert envelope.get("code") == expected_code, envelope


def verify_agent_run_stream_contract(
    run_id: str,
    *,
    actor_id: str,
    role: str,
    outsider_id: str,
) -> None:
    replay = request(
        "GET",
        f"/api/agent-runs/{run_id}/events/replay?after_sequence=-1",
        actor_id=actor_id,
        role=role,
    )
    assert len(replay) >= 2, replay
    sequences = [item["sequence"] for item in replay]
    assert sequences == sorted(set(sequences)), replay
    assert replay[-1]["type"].lower() in {"final", "error"}, replay[-1]

    first_cursor = replay[0].get("cursor") or str(replay[0]["sequence"])
    resumed = request(
        "GET",
        "/api/agent-runs/"
        f"{run_id}/events/replay?after_cursor={urllib.parse.quote(first_cursor)}",
        actor_id=actor_id,
        role=role,
    )
    assert resumed, replay
    assert resumed[0]["sequence"] > replay[0]["sequence"], resumed

    streamed = read_sse_events(
        f"/api/agent-runs/{run_id}/events",
        actor_id=actor_id,
        role=role,
        last_event_id=first_cursor,
    )
    streamed_data = streamed[0]["data"]
    assert isinstance(streamed_data, dict), streamed[0]
    assert streamed_data["sequence"] > replay[0]["sequence"], streamed[0]

    assert_error(
        request_result(
            "GET",
            f"/api/agent-runs/{run_id}/events/replay?after_sequence=-1",
            actor_id=outsider_id,
            role="USER",
        ),
        403,
        "FORBIDDEN",
    )


def verify_case_event_stream_contract(
    case_id: str,
    *,
    actor_id: str,
    role: str,
    outsider_id: str,
) -> list[dict[str, Any]]:
    replay = request(
        "GET",
        f"/api/disputes/{case_id}/events/replay?after_sequence=0",
        actor_id=actor_id,
        role=role,
    )
    assert len(replay) >= 2, replay
    sequences = [item["sequence_no"] for item in replay]
    assert sequences == sorted(set(sequences)), replay

    first_sequence = replay[0]["sequence_no"]
    resumed = request(
        "GET",
        f"/api/disputes/{case_id}/events/replay?after_sequence={first_sequence}",
        actor_id=actor_id,
        role=role,
    )
    assert resumed, replay
    assert resumed[0]["sequence_no"] > first_sequence, resumed

    streamed = read_sse_events(
        f"/api/disputes/{case_id}/events",
        actor_id=actor_id,
        role=role,
        last_event_id=str(first_sequence),
    )
    assert int(streamed[0]["id"]) > first_sequence, streamed[0]

    assert_error(
        request_result(
            "GET",
            f"/api/disputes/{case_id}/events/replay?after_sequence=0",
            actor_id=outsider_id,
            role="USER",
        ),
        403,
        "FORBIDDEN",
    )
    return replay


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
    approved_plan = packet["remedy"]
    assert isinstance(approved_plan, dict), packet
    assert approved_plan.get("id"), approved_plan
    assert approved_plan.get("actions"), approved_plan

    assert_error(
        request_result(
            "GET",
            f"/api/reviews/{task_id}/packet",
            actor_id=user_id,
            role="USER",
        ),
        403,
        "FORBIDDEN",
    )

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
            "approved_plan": approved_plan,
        },
    )
    assert decision["case_status"] == "APPROVED_FOR_EXECUTION"
    assert decision["execution_allowed"] is True

    handoff_events = verify_case_event_stream_contract(
        case_id,
        actor_id=user_id,
        role="USER",
        outsider_id=f"outsider-{suffix}",
    )
    event_types = {
        item.get("event_type") or item.get("eventType") or item.get("type")
        for item in handoff_events
    }
    assert "EXECUTION_ASSISTANT_HANDOFF" in event_types

    assert_error(
        request_result(
            "POST",
            f"/api/disputes/{case_id}/execution/execute",
            actor_id=user_id,
            role="USER",
            idempotency_key=f"execute-unauthorized-{suffix}",
        ),
        403,
        "FORBIDDEN",
    )
    admin_id = f"admin-{suffix}"
    execution_key = f"execute-v2-{suffix}"
    executed = request(
        "POST",
        f"/api/disputes/{case_id}/execution/execute",
        actor_id=admin_id,
        role="ADMIN",
        idempotency_key=execution_key,
    )
    assert executed["case_id"] == case_id, executed
    assert executed["all_succeeded"] is True, executed
    assert executed["actions"], executed
    assert all(
        action["execution_status"] == "SUCCEEDED" for action in executed["actions"]
    ), executed

    replayed_execution = request(
        "POST",
        f"/api/disputes/{case_id}/execution/execute",
        actor_id=admin_id,
        role="ADMIN",
        idempotency_key=execution_key,
    )
    assert replayed_execution == executed

    action_records = request(
        "GET",
        f"/api/disputes/{case_id}/actions",
        actor_id=admin_id,
        role="ADMIN",
    )
    assert action_records, action_records
    assert {item["execution_status"] for item in action_records} == {"SUCCEEDED"}

    outcome = request(
        "GET",
        f"/api/disputes/{case_id}/outcome",
        actor_id=user_id,
        role="USER",
    )
    assert outcome["case_id"] == case_id, outcome
    assert outcome["actions"], outcome
    assert outcome["final_decision"]["human_confirmed"] is True, outcome

    assert_error(
        request_result(
            "POST",
            f"/api/disputes/{case_id}/close",
            actor_id=user_id,
            role="USER",
            idempotency_key=f"close-unauthorized-{suffix}",
        ),
        403,
        "FORBIDDEN",
    )
    close_key = f"close-v2-{suffix}"
    closed_case = request(
        "POST",
        f"/api/disputes/{case_id}/close",
        actor_id=admin_id,
        role="ADMIN",
        idempotency_key=close_key,
    )
    assert closed_case["case_id"] == case_id, closed_case
    assert closed_case["case_status"] == "CLOSED", closed_case
    assert closed_case["evaluation_status"] == "COMPLETED", closed_case

    replayed_close = request(
        "POST",
        f"/api/disputes/{case_id}/close",
        actor_id=admin_id,
        role="ADMIN",
        idempotency_key=close_key,
    )
    assert replayed_close == closed_case

    evaluation = wait_until(
        "completed case evaluation",
        lambda: request(
            "GET",
            f"/api/disputes/{case_id}/evaluation",
            actor_id=admin_id,
            role="ADMIN",
        ),
        lambda value: value["evaluation_status"] == "COMPLETED",
        timeout=120,
    )
    assert evaluation["case_id"] == case_id, evaluation
    metrics = request(
        "GET",
        "/api/reviews/evaluations/metrics",
        actor_id=admin_id,
        role="ADMIN",
    )
    assert metrics["total_evaluations"] >= 1, metrics
    assert metrics["completed_evaluations"] >= 1, metrics

    verify_agent_run_stream_contract(
        evidence_run_id,
        actor_id=user_id,
        role="USER",
        outsider_id=f"outsider-{suffix}",
    )
