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
    assert all(len(statements) == 4 for statements in fixture["party_statements"].values())
    assert evidence["evidence_type"] == "SPECIFICATION_TEST_REPORT"
    assert evidence["filename"] == "air-purifier-performance-test.txt"
    assert evidence["content_type"] == "text/plain"
    assert evidence["claimed_fact"] == (
        "净化器实测性能低于页面宣传参数，需要核验宣传参数依据和检测条件。"
    )
    assert hashlib.sha256(evidence["content"].encode("utf-8")).hexdigest() == (
        evidence["content_sha256"]
    )
