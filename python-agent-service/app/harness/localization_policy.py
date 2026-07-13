# 文件作用：把内部枚举与字段代码转换为中文业务表达，同时保护 ID、引用号和当事人原话不被字符串替换破坏。

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


# 所属模块：Agent Harness > 业务本地化 > 单段展示文本替换。
# 具体功能：`localize_internal_text` 按代码长度降序替换业务枚举/字段名，优先处理长代码，避免短 token 先替换破坏长 token。
# 上下游：上游是模型输出展示字段或 `localize_context_tree` 中允许转换的字符串；下游是中文 Prompt、房间话术和审核界面文本。
# 系统意义：仅负责可读性，不承担授权或事实判断；调用方必须确保机器 ID 和原始陈述不进入此函数。
def localize_internal_text(text: str) -> str:
    output = str(text or "")
    for code, label in _TOKEN_REPLACEMENTS:
        output = output.replace(code, label)
    return output


# 所属模块：Agent Harness > 业务本地化 > 嵌套上下文递归转换。
# 具体功能：`localize_context_tree` 递归复制 dict/list，对普通字符串本地化；通过 current_key 把 ID、引用、证据关系和原话字段原样保留。
# 上下游：上游是 ContextPack 对卷宗、画布、模型结果等结构化树的规范化；下游是 `_should_preserve_value` 和单文本替换器。
# 系统意义：返回新树而不原地修改冻结快照；字段感知的保护规则避免 `USER` 等字符串替换污染 case_id、evidence_ref 或用户原话。
def localize_context_tree(value: Any, *, current_key: str | None = None) -> Any:
    # `isinstance` 按运行时类型选择递归分支；dict 的 key 不翻译，只处理 value。
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


# 所属模块：Agent Harness > 业务本地化 > 机器值/原文保护判定。
# 具体功能：`_should_preserve_value` 对显式字段白名单及 `_id`、`_ids`、`_reference` 后缀返回 True，指示递归转换器跳过字符串替换。
# 上下游：上游是 `localize_context_tree` 的每个字符串叶子；下游决定原值直返还是进入 `localize_internal_text`。
# 系统意义：稳定引用是跨 Java、Python、数据库和前端关联同一事实/证据的合同，不能为了中文展示而改变。
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
