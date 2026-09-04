"""Deterministic user-facing guidance and intake-room evidence boundaries."""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any


FIELD_DISPLAY_LABELS = {
    "CURRENT_PARTY_STATEMENT": "当前参与方对案情的直接说明",
    "ORDER_REFERENCE": "订单号",
    "AFTER_SALES_REFERENCE": "售后单号",
    "LOGISTICS_REFERENCE": "物流单号",
    "REQUESTED_RESOLUTION": "明确的处理诉求",
    "order_reference_confirmation": "订单号核对",
    "after_sales_reference_confirmation": "售后单号核对",
    "logistics_reference_confirmation": "物流单号核对",
    "product_issue_details": "故障细节",
    "product_quality_details": "商品质量细节",
    "user_statement": "用户原始陈述",
    "merchant_statement": "商家原始陈述",
    "merchant_requested_outcome": "商家期望处理方案",
    "requested_outcome": "期望处理结果",
    "evidence_attachments": "证据材料",
    "buyer_evidence": "买家证据材料",
    "user_evidence": "用户证据材料",
    "merchant_evidence": "商家证据材料",
    "merchant_outbound_photos": "商家发货前照片",
    "merchant_outbound_records": "商家发货前记录",
    "merchant_quality_inspection": "商家质检记录",
    "buyer_photos": "买家照片",
    "user_photos": "用户照片",
    "unboxing_video": "开箱视频",
    "opening_video": "开箱视频",
    "delivery_record": "物流派送记录",
    "proof_of_delivery": "签收凭证",
}


def _human_field_label(field: str) -> str:
    if field in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[field]
    normalized = str(field or "").strip()
    if normalized in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[normalized]
    lower = normalized.lower()
    if lower in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[lower]
    if re.search(r"[A-Za-z_]{3,}", normalized):
        return "相关补充材料"
    return normalized or "相关补充材料"


def _human_missing_fields(missing: list[str]) -> list[str]:
    return [_human_field_label(field) for field in missing]


def _humanize_internal_tokens(text: str) -> str:
    output = text
    for token, label in sorted(
        FIELD_DISPLAY_LABELS.items(), key=lambda item: len(item[0]), reverse=True
    ):
        output = output.replace(token, label)
    return output


# Intake may discuss evidence, but it must not ask a party to transfer evidence into this room.
_EVIDENCE_TRANSFER_OBJECT_RE = re.compile(
    r"(?:"
    r"截图|图片|照片|视频|聊天记录|沟通记录|通话记录|物流记录|交易记录|录音|凭证|证明材料|证据材料|证据(?!书记官|室)|"
    r"检测报告|检验报告|发票|交易流水|支付流水|快递底单|签收单|物流单(?!号)|运单(?!号)|文件|文档|附件|材料|订单确认稿|"
    r"screenshots?|images?|photos?|videos?|chat\s+records?|communication\s+records?|"
    r"recordings?|vouchers?|receipts?|documents?|files?|materials?|attachments?|"
    r"order[-\s]?confirmation\s+(?:draft|document)|\b(?:evidence|proofs?)\b"
    r")",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_ACTION_RE = re.compile(
    r"(?:上传|补交|补充(?!说明)|提供|提交|发送|发来|附上|出示|发给|发至|寄给|分享|共享|"
    r"\b(?:upload|provide|submit|send|attach|show|email|share)\b)",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_REQUEST_CUE_RE = re.compile(
    r"(?:请(?:您)?|麻烦(?:您)?|劳烦|烦请|还请|能否|可否|是否(?:可以|能)|方便|"
    r"please|could\s+you|can\s+you|would\s+you|kindly)",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_DIRECT_CUE_RE = re.compile(
    r"^(?:请(?:您)?|麻烦(?:您)?|劳烦|烦请|还请|能否|可否|是否(?:可以|能)|方便|"
    r"please|could\s+you|can\s+you|would\s+you|kindly)\s*",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_OBLIGATION_RE = re.compile(
    r"(?:还需要|还需|需要|必须|务必|应当|"
    r"\bmust\b|\bneed\s+to\b|\brequired\s+to\b)",
    re.IGNORECASE,
)
_EVIDENCE_TRANSFER_ATTRIBUTION_RE = re.compile(
    r"(?:商家|用户|对方|发起方|被发起方).{0,24}(?:称|表示|说|回复|主张|认为|告知|反馈)",
)
_EVIDENCE_FACTUAL_HISTORY_RE = re.compile(
    r"(?:确认|核实|说明|告知|提到).{0,80}(?:已经|已|此前|之前|曾|曾经|目前)",
)
_EVIDENCE_FACTUAL_ACTOR_QUERY_RE = re.compile(
    r"(?:请问|确认|核实|说明|告知|提到).{0,80}"
    r"(?:商家|用户|对方|发起方|被发起方).{0,48}"
    r"(?:是否|还需要|还需|需要|必须|务必|应当)",
)


def _is_evidence_action_history_form(text: str, action: re.Match[str]) -> bool:
    """Recognize forms such as ``提供的`` and ``上传过`` as material history."""

    return text[action.end() :].lstrip().startswith(("的", "方", "过", "了"))


def _is_current_evidence_transfer_instruction(
    clause: str,
    action: re.Match[str] | None,
) -> bool:
    """Recognize a current officer-to-user transfer direction before attribution checks."""

    if action is None:
        return False
    leading = re.sub(r"^(?:[-*•]|\d+[.、)])\s*", "", clause).lstrip()
    leading_action = _EVIDENCE_TRANSFER_ACTION_RE.match(leading)
    if leading_action is not None:
        return not _is_evidence_action_history_form(leading, leading_action)

    direct_cue = _EVIDENCE_TRANSFER_DIRECT_CUE_RE.match(leading)
    if direct_cue is None:
        return False
    remainder = re.sub(
        r"^(?:现在|立即|马上|尽快|now|immediately|right\s+away)\s*",
        "",
        leading[direct_cue.end() :],
        flags=re.IGNORECASE,
    )
    direct_action = _EVIDENCE_TRANSFER_ACTION_RE.match(remainder)
    if direct_action is not None:
        return not _is_evidence_action_history_form(remainder, direct_action)
    return remainder.startswith(("把", "将"))


def _is_factual_evidence_reference(
    clause: str,
    action: re.Match[str] | None,
) -> bool:
    """Return whether a clause refers to material history rather than asks for delivery."""

    if _EVIDENCE_TRANSFER_ATTRIBUTION_RE.search(clause):
        return True
    if action is None:
        return False
    before_action = clause[: action.start()]
    if _is_evidence_action_history_form(clause, action):
        return True
    if re.search(r"(?:由谁|谁|哪一方|哪个主体)\s*$", before_action):
        return True
    return (
        _EVIDENCE_FACTUAL_HISTORY_RE.search(before_action) is not None
        or _EVIDENCE_FACTUAL_ACTOR_QUERY_RE.search(before_action) is not None
    )


def _is_evidence_material_request(value: Any) -> bool:
    """Return whether text directs a party to transfer evidence into the intake room."""

    text = str(value or "").strip()
    if not text:
        return False
    for clause in re.split(r"[，,。！？?；;\n]+", text):
        evidence_object = _EVIDENCE_TRANSFER_OBJECT_RE.search(clause)
        if evidence_object is None:
            continue

        action = _EVIDENCE_TRANSFER_ACTION_RE.search(clause)
        if _is_current_evidence_transfer_instruction(clause, action):
            return True
        if _is_factual_evidence_reference(clause, action):
            continue
        if _EVIDENCE_TRANSFER_OBLIGATION_RE.search(clause):
            return True
        if action is None:
            continue
        if _EVIDENCE_TRANSFER_REQUEST_CUE_RE.search(clause):
            return True
        if re.search(
            r"(?:把|将).{0,80}(?:上传|补交|提供|提交|发送|发来|附上|出示|发给|发至|寄给|分享|共享|"
            r"\b(?:send|email|share)\b)",
            clause,
            re.IGNORECASE,
        ):
            return True
    return False


def _question_for_missing(missing: list[str]) -> str:
    questions = {
        "ORDER_REFERENCE": "请补充订单号或平台可识别的订单引用。",
        "LOGISTICS_REFERENCE": "请补充物流单号或平台可识别的物流引用。",
        "REQUESTED_RESOLUTION": "请明确希望获得的处理方式。",
    }
    return " ".join(
        questions.get(field, f"请补充{_human_field_label(field)}。") for field in missing
    )


def _question_for_quality_gap(
    breakdown: Mapping[str, int],
    component_maxima: Mapping[str, int],
) -> str:
    questions = {
        "references": "请继续补充可核验的业务引用。",
        "event_story": "请继续补充可核验的事件经过。",
        "party_positions": "请继续补充当事方的已知立场。",
        "requested_resolution": "请明确希望获得的处理方式。",
        "risk_and_conflicts": "请继续补充需要核验的争议事实。",
        "next_action_clarity": "请继续补充下一步需要核验的事项。",
    }
    for component, maximum in component_maxima.items():
        if breakdown.get(component, 0) < maximum:
            return questions[component]
    return "请继续补充可核验的案件事实。"
