from __future__ import annotations

from typing import Any


BUSINESS_CODE_LABELS: dict[str, str] = {
    "UNKNOWN": "待确认",
    "PENDING": "待确认",
    "WAITING": "等待补充",
    "NEED_MORE_INFO": "继续补充信息",
    "ACCEPTED": "建议受理",
    "NOT_ADMISSIBLE": "暂不受理",
    "ADMISSIBLE": "可受理",
    "USER": "用户",
    "MERCHANT": "商家",
    "PLATFORM": "平台",
    "LOW": "低风险",
    "MEDIUM": "中风险",
    "HIGH": "高风险",
    "REFUND": "退款",
    "RETURN_REFUND": "退货退款",
    "REPLACEMENT": "换新/补发",
    "REPAIR": "维修",
    "COMPENSATION": "补偿",
    "OTHER": "其他诉求",
    "SIGNED_NOT_RECEIVED": "物流显示签收但用户称未收到包裹",
    "NON_RECEIPT": "用户称未收到包裹",
    "DAMAGED_OR_DEFECTIVE": "商品破损或质量问题",
    "SCRATCHED_WATCH_AFTER_DELIVERY": "签收后发现手表划痕",
    "SCRATCHED_WATCH": "手表划痕争议",
    "QUALITY_DISPUTE": "商品质量争议",
    "ORDER_REFERENCE_CONFLICT": "订单引用存在冲突",
    "LOGISTICS_REFERENCE_CONFLICT": "物流引用存在冲突",
    "AFTER_SALES_REFERENCE_CONFLICT": "售后引用存在冲突",
    "SIGNATURE_MISMATCH": "签收人与收件人不一致",
    "HIGH_VALUE_ORDER": "高价值订单",
    "EVIDENCE_CONFLICT": "双方证据出入较大",
    "OPENING_EVIDENCE_GAPS": "首轮举证材料缺口",
}


FIELD_LABELS: dict[str, str] = {
    "ORDER_REFERENCE": "订单号",
    "AFTER_SALES_REFERENCE": "售后单号",
    "LOGISTICS_REFERENCE": "物流单号",
    "order_reference": "订单号",
    "after_sales_reference": "售后单号",
    "logistics_reference": "物流单号",
    "order_reference_confirmation": "订单号核对",
    "after_sales_reference_confirmation": "售后单号核对",
    "logistics_reference_confirmation": "物流单号核对",
    "product_issue_details": "故障细节",
    "product_quality_details": "商品质量细节",
    "user_statement": "用户原始陈述",
    "merchant_statement": "商家原始陈述",
    "raw_statement": "原始陈述",
    "platform_statement": "平台转述",
    "merchant_requested_outcome": "商家期望处理方案",
    "requested_outcome": "期望处理结果",
    "expected_resolution_text": "期望处理说明",
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
    "after_sales_record": "售后记录",
    "communication_record": "沟通记录",
}


_TOKEN_LABELS = {**BUSINESS_CODE_LABELS, **FIELD_LABELS}
_TOKEN_REPLACEMENTS = sorted(
    _TOKEN_LABELS.items(),
    key=lambda item: len(item[0]),
    reverse=True,
)

_PRESERVED_VALUE_KEYS = {
    "id",
    "case_id",
    "tenant_id",
    "actor_id",
    "actor_ids",
    "message_id",
    "question_id",
    "evidence_id",
    "target_evidence_id",
    "agent_key",
    "agent_invocation_id",
    "agent_session_id",
    "access_session_id",
    "conversation_scope",
    "scope_type",
    "prompt_profile_id",
    "memory_policy_id",
    "order_reference",
    "after_sales_reference",
    "logistics_reference",
    "content_url",
    "original_filename",
    "raw_statement",
    "original_statement",
    "user_original_statement",
    "merchant_original_statement",
    "latest_party_message",
    "quote",
}

_PRESERVED_SEQUENCE_KEYS = {
    "attachment_refs",
    "evidence_refs",
    "related_claim_ids",
    "allowed_actor_ids",
    "allowed_actor_roles",
    "permission_scopes",
}


def localize_internal_text(text: str) -> str:
    output = str(text or "")
    for code, label in _TOKEN_REPLACEMENTS:
        output = output.replace(code, label)
    return output


def localize_context_tree(value: Any, *, current_key: str | None = None) -> Any:
    if isinstance(value, dict):
        localized: dict[str, Any] = {}
        for key, item in value.items():
            localized[key] = localize_context_tree(item, current_key=key)
        return localized
    if isinstance(value, list):
        return [
            localize_context_tree(item, current_key=current_key)
            for item in value
        ]
    if isinstance(value, str):
        if _should_preserve_value(current_key):
            return value
        return localize_internal_text(value)
    return value


def _should_preserve_value(current_key: str | None) -> bool:
    if not current_key:
        return False
    key = current_key.strip()
    return (
        key in _PRESERVED_VALUE_KEYS
        or key in _PRESERVED_SEQUENCE_KEYS
        or key.endswith("_id")
        or key.endswith("_ids")
        or key.endswith("_reference")
    )
