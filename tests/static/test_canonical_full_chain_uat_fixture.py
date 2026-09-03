from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests" / "fixtures" / "canonical_full_chain_uat_case_v1.json"


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
