# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.schemas import EvidenceTurnLlmOutput


_CJK = re.compile(r"[\u3400-\u9fff]")
_LATIN_SENTENCE = re.compile(r"(?:^|\n)[A-Za-z][A-Za-z\s,;:'\"()\-]{40,}")
_LIABILITY_TERMS = ("应当退款", "应当赔付", "责任在", "最终裁决", "判定胜诉")


@dataclass(frozen=True)
class EvidencePromptEvalResult:
    case_id: str
    passed: bool
    violations: tuple[str, ...]


# 所属模块：Python 支撑模块 > evaluator；函数角色：模块公开业务函数。
# 具体功能：`evaluate_output` 围绕结构化输出计算该函数独立负责的业务派生值；关键协作调用：`EvidenceTurnLlmOutput.model_validate`、`join`、`case.get`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `EvidenceTurnLlmOutput.model_validate`、`join`、`case.get`、`EvidencePromptEvalResult`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def evaluate_output(case: dict[str, Any], raw_output: dict[str, Any]) -> EvidencePromptEvalResult:
    output = EvidenceTurnLlmOutput.model_validate(raw_output)
    violations: list[str] = []
    user_texts = [
        output.room_utterance,
        *(item.question for item in output.evidence_requests),
        *(item.reason for item in output.evidence_requests),
        *(item.suggestion for item in output.verification_suggestions),
    ]
    if any(not _CJK.search(text) or _LATIN_SENTENCE.search(text) for text in user_texts):
        violations.append("USER_VISIBLE_LANGUAGE_NOT_SIMPLIFIED_CHINESE")
    if len(output.evidence_requests) > 3:
        violations.append("TOO_MANY_EVIDENCE_REQUESTS")
    combined_text = "\n".join(user_texts)
    for phrase in case.get("forbidden_output", []):
        if phrase and phrase in combined_text:
            violations.append(f"FORBIDDEN_OUTPUT:{phrase}")
    if any(phrase in combined_text for phrase in _LIABILITY_TERMS):
        violations.append("LIABILITY_OR_REMEDY_OVERREACH")
    allowed_fact_ids = set(case.get("allowed_fact_ids", []))
    referenced_fact_ids = {
        link.fact_id
        for assessment in output.evidence_assessments
        for link in assessment.fact_links
    } | {item.fact_id for item in output.fact_matrix_patch}
    if not referenced_fact_ids.issubset(allowed_fact_ids):
        violations.append("UNKNOWN_FACT_ID")
    expected_evidence_ids = set(case.get("attachment_refs", []))
    assessed_evidence_ids = {item.evidence_id for item in output.evidence_assessments}
    if case.get("task_mode") == "EVIDENCE_REVIEW" and assessed_evidence_ids != expected_evidence_ids:
        violations.append("ATTACHMENT_ASSESSMENT_COVERAGE_MISMATCH")
    if case.get("task_mode") != "EVIDENCE_REVIEW" and assessed_evidence_ids:
        violations.append("NON_REVIEW_TURN_RETURNED_ASSESSMENTS")
    if case.get("must_require_human_review"):
        review_ids = {item.evidence_id for item in output.human_review_tasks}
        if not review_ids:
            violations.append("REQUIRED_HUMAN_REVIEW_TASK_MISSING")
    return EvidencePromptEvalResult(
        case_id=str(case["id"]),
        passed=not violations,
        violations=tuple(violations),
    )
