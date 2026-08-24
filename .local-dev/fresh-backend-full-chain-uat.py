#!/usr/bin/env python3
"""Run a resumable full-chain backend UAT with question-aligned Intake answers."""

from __future__ import annotations

import importlib.util
import json
import sys
import time
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
FIXTURE_PATH = (
    HERE.parent / "tests" / "fixtures" / "canonical_full_chain_uat_case_v1.json"
)


def load_module(name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


resume = load_module("fresh_full_chain_resume", HERE / "resume-case-to-outcome.py")
CANONICAL_FIXTURE = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
resume.CONFIRM_DISPUTE_TYPE = CANONICAL_FIXTURE["case"]["dispute_type"]
resume.CONFIRM_RISK_LEVEL = CANONICAL_FIXTURE["case"]["risk_level"]
USER_INTAKE_ANSWERS = tuple(CANONICAL_FIXTURE["party_statements"]["USER"])
_merchant_fixture_answers = tuple(
    CANONICAL_FIXTURE["party_statements"]["MERCHANT"]
)
MERCHANT_INTAKE_ANSWERS = (
    "订单于2026年8月11日发货，2026年8月12日已签收。"
    + _merchant_fixture_answers[0],
    *_merchant_fixture_answers[1:],
)
resume.MERCHANT_INTAKE_ANSWERS = MERCHANT_INTAKE_ANSWERS


def bind_case(case_id: str) -> None:
    resume.CASE_ID = case_id
    resume.USER = resume.context(resume.USER_ID, "USER")
    resume.MERCHANT = resume.context(resume.MERCHANT_ID, "MERCHANT")
    resume.judge.CASE_ID = case_id


def import_canonical_case(ctx: Any) -> str:
    fixture_case = CANONICAL_FIXTURE["case"]
    suffix = resume.base.uuid.uuid4().hex
    status, envelope = resume.base.uat.request_json(
        ctx,
        "canonical_case_import",
        "POST",
        "/api/disputes/import/simulate",
        payload={
            "count": 1,
            "scenario": "固定全链路验收案件",
            "risk_level_hint": fixture_case["risk_level"],
            "initiator_role_hint": "USER",
            "current_actor_id": ctx.user_id,
            "counterparty_actor_id": ctx.merchant_id,
            "simulation_batch_id": f"canonical-full-chain-{suffix}",
            "fixture_id": CANONICAL_FIXTURE["fixture_id"],
        },
        extra_headers={"Idempotency-Key": f"canonical-uat-import-{suffix}"},
    )
    if status != 201:
        raise RuntimeError(
            f"canonical import failed: {status} {json.dumps(envelope, ensure_ascii=False)}"
        )
    data = resume.base.uat.required_object(
        resume.base.uat.envelope_data(envelope, "canonical_case_import"),
        "canonical_case_import",
        "data",
    )
    items = resume.base.uat.required_list(
        data.get("items"), "canonical_case_import", "items"
    )
    if len(items) != 1:
        raise RuntimeError("canonical import returned an unexpected item count")
    item = resume.base.uat.required_object(
        items[0], "canonical_case_import", "item"
    )
    return resume.base.uat.required_text(
        resume.v(item, "id", "case_id", "caseId"),
        "canonical_case_import",
        "case_id",
    )


def complete_user_intake() -> None:
    opening_run = resume.base.uat.ensure_opening(resume.USER)
    resume.wait_run(resume.USER, "fresh_user_opening", opening_run)
    while True:
        memory = resume.latest_intake_memory(resume.USER, "fresh_user_opening_turn")
        source_turn = resume.intake_source_turn(memory)
        if source_turn >= 1:
            break
        resume.USER.deadline.pause("fresh_user_opening_turn", 0.25)
    print(
        json.dumps(
            {
                "checkpoint": "USER_INTAKE_OPENED",
                "run_id": opening_run,
                "source_turn": source_turn,
            }
        ),
        flush=True,
    )

    for follow_up in range(1, len(USER_INTAKE_ANSWERS) + 2):
        phase = resume.actor_intake_phase(memory, "USER")
        if phase in {"HAS_REMARKS", "NO_EXTRA_REMARKS"}:
            break
        if phase == "WAITING_FOR_REMARK":
            answer = "无额外备注，确认按现有陈述提交。"
        else:
            answer_index = follow_up - 1
            if answer_index >= len(USER_INTAKE_ANSWERS):
                raise RuntimeError(
                    "user intake remains substantive after all question-aligned "
                    "fixture answers were submitted"
                )
            answer = USER_INTAKE_ANSWERS[answer_index]
        source_turn += 1
        run_id = resume.post_intake_text(
            resume.USER, f"fresh_user_follow_up_{follow_up}", answer
        )
        resume.wait_run(resume.USER, f"fresh_user_follow_up_run_{follow_up}", run_id)
        memory = resume.wait_intake_turn(
            resume.USER, f"fresh_user_follow_up_turn_{follow_up}", source_turn
        )
        print(
            json.dumps(
                {
                    "checkpoint": "USER_INTAKE_FOLLOW_UP",
                    "source_turn": source_turn,
                    "run_id": run_id,
                    "previous_phase": phase,
                    "persisted_phase": resume.actor_intake_phase(memory, "USER"),
                }
            ),
            flush=True,
        )
    if resume.actor_intake_phase(memory, "USER") not in {
        "HAS_REMARKS",
        "NO_EXTRA_REMARKS",
    }:
        raise RuntimeError("user intake did not reach a terminal remark phase")
    resume.confirm_intake(resume.USER, "fresh_user_confirm")


def main() -> int:
    started = time.monotonic()
    import_context = resume.context(resume.USER_ID, "USER")
    import_context.case_id = None
    case_id = import_canonical_case(import_context)
    bind_case(case_id)
    resume.base.intake_opening_uat.prepare_intake_infrastructure(resume.USER)
    print(json.dumps({"checkpoint": "CASE_IMPORTED", "case_id": case_id}), flush=True)

    complete_user_intake()
    resume.complete_merchant_intake()
    print(json.dumps({"checkpoint": "INTAKE_COMPLETE", "case_id": case_id}), flush=True)

    user_evidence_id, merchant_evidence_id = resume.evidence_stage()
    closed = resume.hearing_stage(user_evidence_id, merchant_evidence_id)
    result = resume.review_and_execute()
    print(
        json.dumps(
            {
                "result": "PASS",
                "case_id": case_id,
                "elapsed_seconds": round(time.monotonic() - started, 3),
                "user_evidence_id": user_evidence_id,
                "merchant_evidence_id": merchant_evidence_id,
                "decision_chain": resume.v(closed, "decision_chain", "decisionChain"),
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
