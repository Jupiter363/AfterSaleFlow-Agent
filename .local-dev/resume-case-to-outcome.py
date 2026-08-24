#!/usr/bin/env python3
"""Resume a valid Intake checkpoint through Evidence, Hearing, Review and Outcome."""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
CASE_ID = os.environ.get("UAT_CASE_ID", "CASE_P9_6A8BF9E2_3")
USER_ID = os.environ.get("UAT_USER_ID", "user-local")
MERCHANT_ID = os.environ.get("UAT_MERCHANT_ID", "merchant-local")
BASE_URL = os.environ.get("UAT_BASE_URL", "http://127.0.0.1:8081")
USER_TEXT = HERE / "uat-assets" / "uat-2-user-observation.txt"
MERCHANT_IMAGE = HERE / "uat-assets" / "uat-2-merchant-lab-test.png"
MERCHANT_INTAKE_ANSWER = os.environ.get(
    "UAT_MERCHANT_INTAKE_ANSWER",
    (
        "订单承诺7月10日前送达，实际7月15日签收，仓库按时交承运方后发生延误。"
        "赠品保温杯未发出，原因是仓库漏配，本方同意免费补发。"
        "本方同意退还30元加急配送费，不承担270元替代购买费；"
        "仓库交接、物流轨迹和客服记录可供核验，无其他事实或附加条件。"
    ),
)
CONFIRM_DISPUTE_TYPE = os.environ.get("UAT_CONFIRM_DISPUTE_TYPE", "DELIVERY_DELAY")
CONFIRM_RISK_LEVEL = os.environ.get("UAT_CONFIRM_RISK_LEVEL", "MEDIUM")


def load_module(name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


base = load_module("resume_five_round_support", HERE / "five-round-intake-api-uat.py")
judge = load_module("resume_judge_support", HERE / "resume-case-6a8766ec-judge-uat.py")
base.os.environ["UAT_BASE_URL"] = BASE_URL
base.os.environ["UAT_TIMEOUT_SECONDS"] = "3600"
judge.CASE_ID = CASE_ID
judge.USER_ID = USER_ID
judge.MERCHANT_ID = MERCHANT_ID
judge.BASE_URL = BASE_URL


def v(value: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in value:
            return value[name]
    return None


def context(actor_id: str, role: str) -> Any:
    ctx = base.uat.UatContext(
        base_url=BASE_URL,
        deadline=base.uat.Deadline(timeout_seconds=3600.0),
        user_id=actor_id,
        merchant_id=MERCHANT_ID,
        opener=urllib.request.build_opener(
            urllib.request.ProxyHandler({}), base.RejectRedirectHandler()
        ),
        case_id=CASE_ID,
    )
    ctx.actor_role = role
    return ctx


USER = context(USER_ID, "USER")
MERCHANT = context(MERCHANT_ID, "MERCHANT")


def request_data(
    ctx: Any,
    stage: str,
    method: str,
    path: str,
    *,
    payload: dict[str, Any] | None = None,
    idempotency_key: str | None = None,
) -> Any:
    headers = {"X-User-Id": ctx.user_id, "X-Role": ctx.actor_role}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    status, envelope = base.uat.request_json(
        ctx, stage, method, path, payload=payload, extra_headers=headers
    )
    if status < 200 or status >= 300:
        raise RuntimeError(
            f"{stage} failed: status={status} envelope={json.dumps(envelope, ensure_ascii=False)}"
        )
    return base.uat.envelope_data(envelope, stage)


def intake_status(ctx: Any, stage: str) -> dict[str, Any]:
    value = request_data(ctx, stage, "GET", f"/api/disputes/{CASE_ID}/intake/status")
    return base.uat.required_object(value, stage, "status")


def wait_run(ctx: Any, stage: str, run_id: str) -> dict[str, Any]:
    while True:
        value = request_data(
            ctx,
            stage,
            "GET",
            f"/api/agent-runs/{base.uat.quote_path(run_id)}",
        )
        run = base.uat.required_object(value, stage, "run")
        status = v(run, "status")
        if status == "COMPLETED":
            return run
        if status == "FAILED":
            raise RuntimeError(f"{stage} failed: {json.dumps(run, ensure_ascii=False)}")
        ctx.deadline.pause(stage, 0.25)


def wait_intake_turn(ctx: Any, stage: str, source_turn_no: int) -> dict[str, Any]:
    while True:
        value = request_data(
            ctx,
            stage,
            "GET",
            f"/api/disputes/{CASE_ID}/rooms/INTAKE/turn-memory/latest",
        )
        memory = base.uat.required_object(value, stage, "memory")
        dossier = v(memory, "case_intake_dossier", "caseIntakeDossier")
        if isinstance(dossier, dict) and v(dossier, "source_turn_no", "sourceTurnNo") == source_turn_no:
            return memory
        ctx.deadline.pause(stage, 0.25)


def latest_intake_memory(ctx: Any, stage: str) -> dict[str, Any]:
    value = request_data(
        ctx,
        stage,
        "GET",
        f"/api/disputes/{CASE_ID}/rooms/INTAKE/turn-memory/latest",
    )
    return base.uat.required_object(value, stage, "memory")


def intake_source_turn(memory: dict[str, Any]) -> int:
    case_dossier = v(memory, "case_intake_dossier", "caseIntakeDossier")
    if not isinstance(case_dossier, dict):
        return 0
    value = v(case_dossier, "source_turn_no", "sourceTurnNo")
    return value if type(value) is int else 0


def post_intake_text(ctx: Any, stage: str, text: str) -> str:
    message = request_data(
        ctx,
        stage,
        "POST",
        f"/api/disputes/{CASE_ID}/rooms/INTAKE/messages",
        payload={
            "message_type": "PARTY_TEXT",
            "text": text,
            "attachment_refs": [],
        },
        idempotency_key=f"resume-{CASE_ID}-{ctx.actor_role.lower()}-{uuid.uuid4().hex}",
    )
    message = base.uat.required_object(message, stage, "message")
    return base.uat.required_text(
        v(message, "agent_run_id", "agentRunId"), stage, "run_id"
    )


def actor_intake_phase(memory: dict[str, Any], role: str) -> str:
    case_dossier = v(memory, "case_intake_dossier", "caseIntakeDossier")
    if not isinstance(case_dossier, dict):
        return "NOT_READY"
    dossier = v(case_dossier, "dossier")
    if not isinstance(dossier, dict):
        return "NOT_READY"
    party_state = v(dossier, "party_intake_state", "partyIntakeState")
    if not isinstance(party_state, dict):
        return "NOT_READY"
    actor_state = party_state.get(role)
    if not isinstance(actor_state, dict):
        return "NOT_READY"
    handoff = v(actor_state, "handoff_notes", "handoffNotes")
    if not isinstance(handoff, dict):
        return "NOT_READY"
    phase = v(handoff, "remark_status", "remarkStatus")
    return phase if isinstance(phase, str) and phase else "NOT_READY"


def confirm_intake(ctx: Any, stage: str) -> None:
    status = intake_status(ctx, f"{stage}_status")
    completed_field = "initiator_status" if ctx.actor_role == "USER" else "respondent_status"
    if v(status, completed_field) == "COMPLETED":
        return
    payload = {
        "admissible": True,
        "dispute_type": CONFIRM_DISPUTE_TYPE,
        "risk_level": CONFIRM_RISK_LEVEL,
    }
    headers = {
        "X-User-Id": ctx.user_id,
        "X-Role": ctx.actor_role,
        "Idempotency-Key": f"resume-{CASE_ID}-{ctx.actor_role.lower()}-confirm",
    }
    while True:
        code, envelope = base.uat.request_json(
            ctx,
            stage,
            "POST",
            f"/api/disputes/{CASE_ID}/intake/confirm",
            payload=payload,
            extra_headers=headers,
        )
        if code == 200:
            break
        details = envelope.get("details") if isinstance(envelope, dict) else None
        projection_pending = (
            code == 409
            and isinstance(details, dict)
            and details.get("reason_code") == "TARGET_E2E_INTAKE_PROJECTION_PENDING"
        )
        if not projection_pending:
            raise RuntimeError(
                f"{stage} failed: status={code} "
                f"envelope={json.dumps(envelope, ensure_ascii=False)}"
            )
        ctx.deadline.pause(stage, 0.25)
    while True:
        projected = intake_status(ctx, f"{stage}_projected")
        if v(projected, completed_field) == "COMPLETED":
            return
        ctx.deadline.pause(stage, 0.25)


def complete_merchant_intake() -> None:
    confirm_intake(USER, "resume_user_confirm")
    status = intake_status(MERCHANT, "resume_merchant_status")
    if v(status, "respondent_status") == "COMPLETED":
        return

    messages = request_data(
        MERCHANT,
        "resume_merchant_messages",
        "GET",
        f"/api/disputes/{CASE_ID}/rooms/INTAKE/messages",
    )
    opening_run = None
    if isinstance(messages, list):
        for item in messages:
            if not isinstance(item, dict):
                continue
            candidate = v(item, "agent_run_id", "agentRunId")
            if (
                v(item, "message_type", "messageType") == "AGENT_MESSAGE"
                and isinstance(candidate, str)
                and candidate
            ):
                opening_run = candidate
                break
    if opening_run is None:
        opening_run = base.uat.ensure_opening(MERCHANT)
    wait_run(MERCHANT, "resume_merchant_opening", opening_run)
    while True:
        memory = latest_intake_memory(MERCHANT, "resume_merchant_opening_turn")
        if intake_source_turn(memory) >= 1:
            break
        MERCHANT.deadline.pause("resume_merchant_opening_turn", 0.25)
    print(
        json.dumps(
            {
                "checkpoint": "MERCHANT_INTAKE_OPENED",
                "run_id": opening_run,
                "reused": bool(messages),
            }
        ),
        flush=True,
    )

    source_turn = intake_source_turn(memory)
    for follow_up in range(1, 7):
        phase = actor_intake_phase(memory, "MERCHANT")
        if phase in {"HAS_REMARKS", "NO_EXTRA_REMARKS"}:
            break
        if phase == "WAITING_FOR_REMARK":
            answer = "无额外备注，确认按现有陈述提交。"
        else:
            answer = MERCHANT_INTAKE_ANSWER
        source_turn += 1
        run_id = post_intake_text(
            MERCHANT, f"resume_merchant_follow_up_{follow_up}", answer
        )
        wait_run(MERCHANT, f"resume_merchant_follow_up_run_{follow_up}", run_id)
        memory = wait_intake_turn(
            MERCHANT, f"resume_merchant_follow_up_turn_{follow_up}", source_turn
        )
        print(
            json.dumps(
                {
                    "checkpoint": "MERCHANT_INTAKE_FOLLOW_UP",
                    "source_turn": source_turn,
                    "run_id": run_id,
                    "previous_phase": phase,
                    "persisted_phase": actor_intake_phase(memory, "MERCHANT"),
                }
            ),
            flush=True,
        )
    if actor_intake_phase(memory, "MERCHANT") not in {
        "HAS_REMARKS",
        "NO_EXTRA_REMARKS",
    }:
        raise RuntimeError("merchant intake did not reach a terminal remark phase")
    confirm_intake(MERCHANT, "resume_merchant_confirm")


def upload_evidence(ctx: Any, stage: str) -> str:
    if ctx.actor_role == "USER":
        path = USER_TEXT
        evidence_type = "USER_STATEMENT"
        source_type = "USER_UPLOAD"
        content_type = "text/plain"
        claimed_fact = "用户记录显示订单履约时间与约定不一致。"
    else:
        path = MERCHANT_IMAGE
        evidence_type = "TEST_REPORT"
        source_type = "MERCHANT_UPLOAD"
        content_type = "image/png"
        claimed_fact = "商家图片记录展示检测与交付前状态。"
    if not path.is_file():
        raise RuntimeError(f"missing fixture: {path}")
    boundary, body = base.multipart_body(
        {
            "evidence_type": evidence_type,
            "source_type": source_type,
            "visibility": "PARTIES",
            "model_processing_authorized": "true",
            "claimed_fact": claimed_fact,
            "truth_attested": "true",
        },
        filename=path.name,
        content_type=content_type,
        content=path.read_bytes(),
    )
    headers = base.actor_headers(ctx)
    headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    request = urllib.request.Request(
        ctx.url(f"/api/disputes/{CASE_ID}/evidence"),
        data=body,
        method="POST",
        headers=headers,
    )
    try:
        with ctx.opener.open(request, timeout=ctx.deadline.request_timeout(stage)) as response:
            status = response.status
            envelope = base.uat.parse_json_body(response.read(), stage)
    except urllib.error.HTTPError as error:
        status = error.code
        envelope = base.uat.parse_json_body(error.read(), stage)
    if status != 201:
        raise RuntimeError(f"{stage} failed: {status} {json.dumps(envelope, ensure_ascii=False)}")
    evidence = base.uat.required_object(
        base.uat.envelope_data(envelope, stage), stage, "evidence"
    )
    return base.uat.required_text(v(evidence, "id", "evidence_id", "evidenceId"), stage, "id")


def evidence_stage() -> tuple[str, str]:
    opening_key = f"resume-{CASE_ID}-evidence-opening"
    opening_run, _ = base.post_evidence_opening(USER, "resume_evidence_opening", opening_key)
    wait_run(USER, "resume_evidence_opening_run", opening_run)

    evidence_ids: dict[str, str] = {}
    for ctx in (USER, MERCHANT):
        role = ctx.actor_role
        evidence_id = upload_evidence(ctx, f"resume_{role.lower()}_upload")
        identity = base.submit_synthetic_evidence(
            ctx,
            f"resume_{role.lower()}_submit",
            evidence_id,
            f"resume-{CASE_ID}-{role.lower()}-submit",
        )
        wait_run(ctx, f"resume_{role.lower()}_evidence_run", identity.run_id)
        evidence_ids[role] = evidence_id
        print(
            json.dumps(
                {
                    "checkpoint": "EVIDENCE_SUBMITTED",
                    "role": role,
                    "evidence_id": evidence_id,
                    "run_id": identity.run_id,
                }
            ),
            flush=True,
        )

    for ctx in (USER, MERCHANT):
        base.post_evidence_completion(
            ctx,
            f"resume_{ctx.actor_role.lower()}_evidence_complete",
            f"resume-{CASE_ID}-{ctx.actor_role.lower()}-evidence-complete",
        )
    base.wait_for_evidence_completion(MERCHANT, "resume_evidence_sealed")
    return evidence_ids["USER"], evidence_ids["MERCHANT"]


def hearing_stage(user_evidence_id: str, merchant_evidence_id: str) -> dict[str, Any]:
    hearing = base.wait_for_hearing_questions(MERCHANT, "resume_hearing_questions")
    question_set = base.uat.required_object(
        v(hearing, "question_set", "questionSet"), "resume_hearing_questions", "question_set"
    )
    base.post_hearing_answer_bundle(USER, "resume_hearing_user_answers", question_set)
    base.post_hearing_answer_bundle(MERCHANT, "resume_hearing_merchant_answers", question_set)
    base.wait_for_hearing_m2(MERCHANT, "resume_hearing_m2")
    print(json.dumps({"checkpoint": "HEARING_M2_FROZEN"}), flush=True)

    judge.CASE_ID = CASE_ID
    judge.USER_ID = USER_ID
    judge.MERCHANT_ID = MERCHANT_ID
    judge.BASE_URL = BASE_URL
    user = judge.context(USER_ID, "USER")
    merchant = judge.context(MERCHANT_ID, "MERCHANT")
    evidence_open = judge.wait_for_stage(
        merchant, "PARTY_EVIDENCE_OPEN", "resume_hearing_evidence_open"
    )
    request_set = base.uat.required_object(
        v(evidence_open, "evidence_request_set", "evidenceRequestSet"),
        "resume_hearing_evidence_open",
        "request_set",
    )
    judge.submit_hearing_batch(
        user, "resume_hearing_user_batch", request_set, user_evidence_id
    )
    judge.submit_hearing_batch(
        merchant, "resume_hearing_merchant_batch", request_set, merchant_evidence_id
    )
    timings = base.uat.TimingLedger()
    closed, observed = judge.wait_for_closed(merchant, timings)
    print(
        json.dumps({"checkpoint": "HEARING_CLOSED", "stages": observed}, ensure_ascii=False),
        flush=True,
    )
    return closed


def wait_for_review_task() -> dict[str, Any]:
    reviewer = context("reviewer-local", "PLATFORM_REVIEWER")
    while True:
        for review_status in ("PENDING", "IN_REVIEW"):
            items = request_data(
                reviewer,
                f"review_list_{review_status.lower()}",
                "GET",
                f"/api/reviews?status={review_status}",
            )
            if isinstance(items, list):
                for item in items:
                    if isinstance(item, dict) and v(item, "case_id", "caseId") == CASE_ID:
                        return item
        reviewer.deadline.pause("review_list", 0.5)


def review_and_execute() -> dict[str, Any]:
    reviewer = context("reviewer-local", "PLATFORM_REVIEWER")
    task = wait_for_review_task()
    task_id = base.uat.required_text(v(task, "id"), "review_task", "id")
    packet = request_data(reviewer, "review_packet", "GET", f"/api/reviews/{task_id}/packet")
    packet = base.uat.required_object(packet, "review_packet", "packet")
    base.uat.required_object(v(packet, "remedy"), "review_packet", "remedy")
    task_status = base.uat.required_text(v(task, "status"), "review_task", "status")
    if task_status == "PENDING":
        request_data(reviewer, "review_start", "POST", f"/api/reviews/{task_id}/start")
    elif task_status != "IN_REVIEW":
        raise RuntimeError(f"review task is not actionable: status={task_status}")
    request_data(
        reviewer,
        "review_decision",
        "POST",
        f"/api/reviews/{task_id}/decision",
        payload={
            "decision": "APPROVE",
            "reason": "后端全链路 UAT 已核对冻结卷宗、证据及裁决链。",
        },
        idempotency_key=f"resume-{CASE_ID}-review-approve",
    )
    admin = context("admin-local", "ADMIN")
    executed = request_data(
        admin,
        "execution",
        "POST",
        f"/api/disputes/{CASE_ID}/execution/execute",
        idempotency_key=f"resume-{CASE_ID}-execute",
    )
    outcome = request_data(USER, "outcome", "GET", f"/api/disputes/{CASE_ID}/outcome")
    return {
        "task_id": task_id,
        "execution": executed,
        "outcome": outcome,
    }


def main() -> int:
    started = time.monotonic()
    complete_merchant_intake()
    print(json.dumps({"checkpoint": "INTAKE_COMPLETE", "case_id": CASE_ID}), flush=True)
    user_evidence_id, merchant_evidence_id = evidence_stage()
    closed = hearing_stage(user_evidence_id, merchant_evidence_id)
    result = review_and_execute()
    print(
        json.dumps(
            {
                "result": "PASS",
                "case_id": CASE_ID,
                "elapsed_seconds": round(time.monotonic() - started, 3),
                "user_evidence_id": user_evidence_id,
                "merchant_evidence_id": merchant_evidence_id,
                "decision_chain": v(closed, "decision_chain", "decisionChain"),
                **result,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
