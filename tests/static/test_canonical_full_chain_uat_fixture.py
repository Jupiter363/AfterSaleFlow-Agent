from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests" / "fixtures" / "canonical_full_chain_uat_case_v1.json"
FRESH_RUNNER = ROOT / ".local-dev" / "fresh-backend-full-chain-uat.py"


def load_fresh_runner() -> object:
    name = "focused_fresh_backend_full_chain_uat"
    spec = importlib.util.spec_from_file_location(name, FRESH_RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def intake_memory(role: str, source_turn: int, dossier_version: int, phase: str) -> dict:
    return {
        "case_intake_dossier": {
            "source_turn_no": source_turn,
            "dossier_version": dossier_version,
            "dossier": {
                "party_intake_state": {
                    role: {"handoff_notes": {"remark_status": phase}}
                }
            },
        }
    }


def test_full_chain_uat_uses_one_hash_bound_case_and_evidence_fixture() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    case = fixture["case"]
    evidence = fixture["evidence"]

    assert fixture["schema_version"] == "full-chain-uat-fixture.v1"
    assert fixture["fixture_id"] == "air-purifier-specification-mismatch-v1"
    assert fixture["immutable_contract"] == {
        "only_runtime_ids_may_change": True,
        "case_and_evidence_must_not_be_randomized": True,
    }
    assert {
        "template_no": case["template_no"],
        "title": case["title"],
        "dispute_type": case["dispute_type"],
        "risk_level": case["risk_level"],
        "requested_amount": case["requested_amount"],
        "requested_items": case["requested_items"],
    } == {
        "template_no": 20,
        "title": "商品参数宣传与检测结果不符",
        "dispute_type": "SPECIFICATION_MISMATCH",
        "risk_level": "HIGH",
        "requested_amount": "1899.00",
        "requested_items": "空气净化器 1 台",
    }
    assert set(fixture["party_statements"]) == {"USER", "MERCHANT"}
    assert {
        role: len(statements)
        for role, statements in fixture["party_statements"].items()
    } == {"USER": 6, "MERCHANT": 5}
    user_statements = fixture["party_statements"]["USER"]
    detailed_test_statement = user_statements[-2]
    merchant_authority_statement = user_statements[-1]
    assert "20平方米" in detailed_test_statement
    assert "CMA/CNAS" in detailed_test_statement
    assert "平台核验商品页参数来源" in detailed_test_statement
    assert "GB/T 18801-2022" in merchant_authority_statement
    assert "AP-CADR-2026-0710" in merchant_authority_statement
    final_merchant_statement = fixture["party_statements"]["MERCHANT"][-1]
    assert "退款1899元" in final_merchant_statement
    assert "CMA/CNAS" in final_merchant_statement
    assert "两个工作日" in final_merchant_statement
    assert "五个工作日" in final_merchant_statement
    assert evidence["evidence_type"] == "SPECIFICATION_TEST_REPORT"
    assert evidence["filename"] == "air-purifier-performance-test.txt"
    assert evidence["content_type"] == "text/plain"
    assert evidence["claimed_fact"] == (
        "净化器实测性能低于页面宣传参数，需要核验宣传参数依据和检测条件。"
    )
    assert hashlib.sha256(evidence["content"].encode("utf-8")).hexdigest() == (
        evidence["content_sha256"]
    )


def test_user_resume_uses_persisted_turn_then_exact_handoff_bridge(monkeypatch) -> None:
    runner = load_fresh_runner()
    resume = runner.resume
    user = SimpleNamespace(actor_role="USER")
    states = {
        5: intake_memory("USER", 5, 5, "NOT_READY"),
        6: intake_memory("USER", 6, 6, "READY_PENDING_REMARK_INVITE"),
        7: intake_memory("USER", 7, 7, "WAITING_FOR_REMARK"),
    }
    calls: list[tuple] = []
    runner.USER_INTAKE_ANSWERS = tuple(f"user-{index}" for index in range(1, 7))
    monkeypatch.setattr(resume, "USER", user)
    monkeypatch.setattr(resume.base.uat, "ensure_opening", lambda _ctx: "opening")
    monkeypatch.setattr(resume, "wait_run", lambda *_args: None)
    monkeypatch.setattr(resume, "latest_intake_memory", lambda *_args: states[5])
    monkeypatch.setattr(
        resume,
        "wait_intake_turn",
        lambda _ctx, _stage, source_turn: states[source_turn],
    )
    monkeypatch.setattr(
        resume,
        "post_intake_text",
        lambda _ctx, _stage, text: calls.append(("answer", text)) or "detail-run",
    )
    monkeypatch.setattr(
        resume.base,
        "post_handoff_bridge",
        lambda _ctx, _stage, **authority: calls.append(
            ("bridge", authority)
        )
        or "bridge-run",
    )
    monkeypatch.setattr(
        resume,
        "confirm_intake",
        lambda _ctx, _stage: calls.append(("confirm",)),
    )

    runner.complete_user_intake()

    assert calls == [
        ("answer", "user-5"),
        (
            "bridge",
            {"expected_dossier_version": 6, "expected_source_turn_no": 6},
        ),
        ("confirm",),
    ]


def test_merchant_resume_uses_persisted_turn_then_exact_handoff_bridge(
    monkeypatch,
) -> None:
    runner = load_fresh_runner()
    resume = runner.resume
    user = SimpleNamespace(actor_role="USER")
    merchant = SimpleNamespace(actor_role="MERCHANT")
    states = {
        5: intake_memory("MERCHANT", 5, 14, "NOT_READY"),
        6: intake_memory("MERCHANT", 6, 15, "READY_PENDING_REMARK_INVITE"),
        7: intake_memory("MERCHANT", 7, 16, "WAITING_FOR_REMARK"),
    }
    calls: list[tuple] = []
    resume.MERCHANT_INTAKE_ANSWERS = tuple(
        f"merchant-{index}" for index in range(1, 6)
    )
    monkeypatch.setattr(resume, "USER", user)
    monkeypatch.setattr(resume, "MERCHANT", merchant)
    monkeypatch.setattr(
        resume,
        "confirm_intake",
        lambda ctx, _stage: calls.append(("confirm", ctx.actor_role)),
    )
    monkeypatch.setattr(
        resume,
        "intake_status",
        lambda *_args: {"respondent_status": "OPEN"},
    )
    monkeypatch.setattr(
        resume,
        "request_data",
        lambda *_args, **_kwargs: [
            {"message_type": "AGENT_MESSAGE", "agent_run_id": "opening"}
        ],
    )
    monkeypatch.setattr(resume, "wait_run", lambda *_args: None)
    monkeypatch.setattr(resume, "latest_intake_memory", lambda *_args: states[5])
    monkeypatch.setattr(
        resume,
        "wait_intake_turn",
        lambda _ctx, _stage, source_turn: states[source_turn],
    )
    monkeypatch.setattr(
        resume,
        "post_intake_text",
        lambda _ctx, _stage, text: calls.append(("answer", text)) or "detail-run",
    )
    monkeypatch.setattr(
        resume.base,
        "post_handoff_bridge",
        lambda _ctx, _stage, **authority: calls.append(
            ("bridge", authority)
        )
        or "bridge-run",
    )

    resume.complete_merchant_intake()

    assert calls == [
        ("confirm", "USER"),
        ("answer", "merchant-5"),
        (
            "bridge",
            {"expected_dossier_version": 15, "expected_source_turn_no": 6},
        ),
        ("confirm", "MERCHANT"),
    ]
