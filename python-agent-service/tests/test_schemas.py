# 文件作用：自动化测试文件，验证 test_schemas 相关模块的行为、契约或页面布局。

import pytest
from pydantic import ValidationError

from app.schemas import AdjudicationDraft


# 所属模块：Python 支撑模块 > test_schemas；函数角色：回归测试用例。
# 具体功能：`test_adjudication_output_can_only_be_a_non_final_human_review_draft` 验证人工复核信息在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`AdjudicationDraft.model_validate`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `pytest.raises`、`AdjudicationDraft.model_validate`。
# 系统意义：固定“Python 支撑模块 > test_schemas”的可观察契约，防止后续重构改变业务结果。
def test_adjudication_output_can_only_be_a_non_final_human_review_draft() -> None:
    payload = {
        "recommended_outcome": "Refund may be appropriate",
        "reasoning_summary": "Evidence and policy support a draft recommendation.",
        "issue_findings": [],
        "confidence": 0.8,
        "risk_level": "MEDIUM",
        "review_focus": ["Confirm policy applicability"],
        "requires_human_review": True,
        "is_final_decision": False,
    }

    assert AdjudicationDraft.model_validate(payload).draft_status == "PENDING_HUMAN_REVIEW"

    with pytest.raises(ValidationError):
        AdjudicationDraft.model_validate({**payload, "is_final_decision": True})
    with pytest.raises(ValidationError):
        AdjudicationDraft.model_validate({**payload, "requires_human_review": False})
    with pytest.raises(ValidationError):
        AdjudicationDraft.model_validate({**payload, "execution_actions": ["REFUND"]})
