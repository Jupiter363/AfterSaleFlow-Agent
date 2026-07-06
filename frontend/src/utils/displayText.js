const ROLE_LABELS = {
  USER: "用户",
  CUSTOMER: "用户",
  MERCHANT: "商家",
  CUSTOMER_SERVICE: "争议接待官",
  DISPUTE_INTAKE_OFFICER: "争议接待官",
  INTAKE_OFFICER: "争议接待官",
  EVIDENCE_CLERK: "证据书记官",
  COURT_CLERK: "庭审书记官",
  JUDGE: "AI 法官",
  AI_JUDGE: "AI 法官",
  JURY: "AI 评审团",
  AI_JURY: "AI 评审团",
  REVIEW_ASSISTANT: "审核解释官",
  PLATFORM_REVIEWER: "平台审核员",
  SYSTEM: "系统",
};

const FIELD_LABELS = {
  ORDER_REFERENCE: "订单号",
  AFTER_SALES_REFERENCE: "售后单号",
  LOGISTICS_REFERENCE: "物流单号",
  order_reference: "订单号",
  after_sales_reference: "售后单号",
  logistics_reference: "物流单号",
  order_reference_confirmation: "订单号核对",
  after_sales_reference_confirmation: "售后单号核对",
  logistics_reference_confirmation: "物流单号核对",
  product_issue_details: "故障细节",
  product_quality_details: "商品质量细节",
  user_statement: "用户原始陈述",
  merchant_statement: "商家原始陈述",
  merchant_requested_outcome: "商家期望处理方案",
  requested_outcome: "期望处理结果",
  expected_resolution_text: "期望处理说明",
  evidence_attachments: "证据材料",
  buyer_evidence: "买家证据材料",
  user_evidence: "用户证据材料",
  merchant_evidence: "商家证据材料",
  merchant_outbound_photos: "商家发货前照片",
  merchant_outbound_records: "商家发货前记录",
  merchant_quality_inspection: "商家质检记录",
  buyer_photos: "买家照片",
  user_photos: "用户照片",
  unboxing_video: "开箱视频",
  opening_video: "开箱视频",
  delivery_record: "物流派送记录",
  proof_of_delivery: "签收凭证",
  after_sales_record: "售后记录",
  communication_record: "沟通记录",
};

const VALUE_LABELS = {
  UNKNOWN: "待确认",
  PENDING: "待确认",
  WAITING: "等待补充",
  NEED_MORE_INFO: "继续补充信息",
  ACCEPTED: "建议受理",
  NOT_ADMISSIBLE: "暂不受理",
  ADMISSIBLE: "可受理",
  USER: "用户",
  MERCHANT: "商家",
  PLATFORM: "平台",
  LOW: "低风险",
  MEDIUM: "中风险",
  HIGH: "高风险",
  REFUND: "退款",
  RETURN_REFUND: "退货退款",
  REPLACEMENT: "换新/补发",
  REPAIR: "维修",
  COMPENSATION: "补偿",
  OTHER: "其他诉求",
  ORDER_REFERENCE_CONFLICT: "订单引用存在冲突",
  LOGISTICS_REFERENCE_CONFLICT: "物流引用存在冲突",
  AFTER_SALES_REFERENCE_CONFLICT: "售后引用存在冲突",
  SIGNATURE_MISMATCH: "签收人与收件人不一致",
  HIGH_VALUE_ORDER: "高价值订单",
  EVIDENCE_CONFLICT: "双方证据出入较大",
  SIGNED_NOT_RECEIVED: "物流显示签收但用户称未收到包裹",
  DAMAGED_OR_DEFECTIVE: "商品破损或质量问题",
  SCRATCHED_WATCH_AFTER_DELIVERY: "签收后发现手表划痕",
  SCRATCHED_WATCH: "手表划痕争议",
  QUALITY_DISPUTE: "商品质量争议",
  NON_RECEIPT: "用户称未收到包裹",
};

const ENGLISH_PHRASE_LABELS = {
  "Waiting for more information": "等待补充更多信息",
  "Waiting for response": "等待对方回应",
  "Needs more information": "继续补充信息",
  "Pending": "待确认",
  "Expected outcome": "期望处理结果",
  "Admission advice": "受理建议",
  "Risk signals": "风险信号",
  "User claim": "用户主张",
  "Merchant claim": "商家主张",
  "Initiator": "发起方",
  "References": "订单 / 售后 / 物流",
  "delivery conflict": "物流履约冲突",
  "The user reports that the watch is broken. No additional details or evidence provided.":
    "用户反馈手表损坏，仍需补充故障细节、证据和双方处理意见。",
  "The user reports that the watch is broken": "用户反馈手表损坏",
  "No additional details or evidence provided.": "尚未补充更多细节或证据。",
  "Package not received.": "用户称未收到包裹。",
  "Package not received": "用户称未收到包裹",
};

const TOKEN_LABELS = {
  ...VALUE_LABELS,
  ...FIELD_LABELS,
  ...ENGLISH_PHRASE_LABELS,
};

const TOKEN_REPLACEMENTS = Object.entries(TOKEN_LABELS).sort(
  ([left], [right]) => right.length - left.length,
);

const CHINESE_RE = /[\u3400-\u9fff]/;
const LATIN_RE = /[A-Za-z]{3,}/;

export function roleLabel(role) {
  if (!role) return "未知身份";
  return ROLE_LABELS[role] || humanizeDossierText(role, { fallback: "未知身份" });
}

export function humanizeDossierText(value, options = {}) {
  const fallback = options.fallback ?? "待补充";
  if (Array.isArray(value)) {
    const text = value
      .map((item) => humanizeDossierText(item, options))
      .filter(Boolean)
      .join("、");
    return text || fallback;
  }
  if (value === null || value === undefined) return fallback;

  const raw = String(value).trim();
  if (!raw) return fallback;

  const exact = TOKEN_LABELS[raw];
  if (exact) return exact;
  if (options.kind === "title") return humanizeTitle(raw, fallback);
  if (options.kind === "summary") return humanizeSummary(raw, fallback);

  return replaceInternalTokens(raw);
}

export function humanizeDossierList(values, fallback = "等待补充更多信息") {
  const list = Array.isArray(values) ? values : [values];
  const mapped = list
    .map((value) => humanizeDossierText(value, { fallback: "" }))
    .filter(Boolean);
  return mapped.length ? mapped : [fallback];
}

export function displayRoomMessageText(value) {
  if (!value) return "";
  const raw = String(value);
  const questionMarks = (raw.match(/\?/g) || []).length;
  if (questionMarks >= 6 && questionMarks / raw.length > 0.35) {
    return "历史消息编码异常，原始内容已按不可变记录留存。";
  }
  return replaceInternalTokens(raw);
}

function humanizeTitle(raw, fallback) {
  const localized = replaceInternalTokens(raw);
  if (CHINESE_RE.test(localized)) return localized;

  const lower = localized.toLowerCase();
  if (lower.includes("broken") && lower.includes("watch")) return "手表质量争议";
  if (lower.includes("watch")) return "手表履约争议";
  if (lower.includes("quality issue") || lower.includes("quality")) return "商品质量争议";
  if (lower.includes("delivery") || lower.includes("logistics")) return "物流履约争议";
  if (LATIN_RE.test(localized)) return fallback || "争议事件待梳理";
  return localized;
}

function humanizeSummary(raw, fallback) {
  const localized = replaceInternalTokens(raw);
  if (CHINESE_RE.test(localized)) return localized;

  const lower = localized.toLowerCase();
  if (lower.includes("watch") && lower.includes("broken")) {
    return "用户反馈手表损坏，仍需补充故障细节、证据和双方处理意见。";
  }
  if (lower.includes("no additional details") || lower.includes("provided")) {
    return "接待官正在整理争议事实，请继续补充订单、证据和处理诉求。";
  }
  if (LATIN_RE.test(localized)) {
    return fallback || "接待官正在整理争议事实，请继续补充关键信息。";
  }
  return localized;
}

function replaceInternalTokens(raw) {
  let output = raw;
  for (const [token, label] of TOKEN_REPLACEMENTS) {
    output = output.split(token).join(label);
  }
  return output;
}
