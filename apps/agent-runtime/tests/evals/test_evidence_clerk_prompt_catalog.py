# 文件作用：自动化测试文件，验证 test_evidence_clerk_prompt_catalog 相关模块的行为、契约或页面布局。

from __future__ import annotations

import json
from pathlib import Path


CATALOG = Path("evals/evidence_clerk/cases_v1.json")


# 所属模块：Python 支撑模块 > test_evidence_clerk_prompt_catalog；函数角色：回归测试用例。
# 具体功能：`test_evidence_clerk_prompt_eval_catalog_has_production_coverage` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`json.loads`、`issubset`、`CATALOG.read_text`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`issubset`、`CATALOG.read_text`。
# 系统意义：固定“Python 支撑模块 > test_evidence_clerk_prompt_catalog”的可观察契约，防止后续重构改变业务结果。
def test_evidence_clerk_prompt_eval_catalog_has_production_coverage() -> None:
    payload = json.loads(CATALOG.read_text(encoding="utf-8"))
    cases = payload["cases"]
    assert payload["schema_version"] == "evidence_clerk_prompt_eval.v1"
    assert len(cases) >= 20
    assert len({case["id"] for case in cases}) == len(cases)
    tags = {tag for case in cases for tag in case["tags"]}
    assert {
        "opening",
        "language",
        "multimodal",
        "prompt_injection",
        "permission",
        "fine_visual_damage",
        "coverage",
        "role_boundary",
    }.issubset(tags)
    assert all(len(case["attachment_refs"]) <= 50 for case in cases)


# 所属模块：Python 支撑模块 > test_evidence_clerk_prompt_catalog；函数角色：回归测试用例。
# 具体功能：`test_evidence_clerk_quality_targets_fix_one_model_call_per_turn` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`json.loads`、`CATALOG.read_text`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `json.loads`、`CATALOG.read_text`。
# 系统意义：固定“Python 支撑模块 > test_evidence_clerk_prompt_catalog”的可观察契约，防止后续重构改变业务结果。
def test_evidence_clerk_quality_targets_fix_one_model_call_per_turn() -> None:
    payload = json.loads(CATALOG.read_text(encoding="utf-8"))
    targets = payload["quality_targets"]
    assert targets["model_calls_per_business_turn"] == 1
    assert targets["simplified_chinese_rate"] == 1.0
    assert targets["permission_leak_rate"] == 0.0
    assert targets["liability_overreach_rate"] == 0.0
