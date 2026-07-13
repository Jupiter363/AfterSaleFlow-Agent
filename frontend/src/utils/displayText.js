// 文件作用：前端工程代码文件，支撑售后争议系统的页面、交互、样式或构建配置。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

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
  PRESIDING_JUDGE: "AI 法官",
  JURY: "AI 评审团",
  AI_JURY: "AI 评审团",
  JURY_PANEL: "AI 评审团",
  REVIEW_ASSISTANT: "审核解释官",
  REVIEW_COPILOT: "审核解释官",
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
  logistics_record: "物流记录",
  logistics_records: "物流记录",
  logisticsRecord: "物流记录",
  logisticsRecords: "物流记录",
  proof_of_delivery: "签收凭证",
  after_sales_record: "售后记录",
  communication_record: "沟通记录",
};

const VALUE_LABELS = {
  UNKNOWN: "待确认",
  PENDING: "待确认",
  PENDING_REVIEW: "待复核",
  PENDING_HUMAN_REVIEW: "待人工复核",
  PENDING_POLICY_REVIEW: "待规则复核",
  UNDETERMINED: "待终审确认",
  WAITING_HUMAN_REVIEW: "等待人工复核",
  NEEDS_HUMAN_REVIEW: "待人工复核",
  REQUIRES_HUMAN_REVIEW: "需人工复核",
  HUMAN_REVIEW: "人工复核",
  POLICY_REVIEW: "规则复核",
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
  RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW: "先核验签收凭证；若签收依据不足，建议补发或退款",
  RESHIP_IF_SIGNATURE_PROOF_MISSING: "若签收凭证缺失，建议补发",
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
  "Structured agent output could not be validated. No automated finding was accepted.":
    "结构化草案未通过校验，系统未采纳自动结论，需由终审人工复核。",
  "Review the failed final-convergence structured output manually.":
    "请人工复核未通过校验的终局结构化输出。",
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
const EVIDENCE_MATRIX_JSON_RE = /\{[^{}]*"evidence_id"[^{}]*\}/g;

// 业务位置：【前端应用】roleLabel：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
export function roleLabel(role) {
  if (!role) return "未知身份";
  return ROLE_LABELS[role] || humanizeDossierText(role, { fallback: "未知身份" });
}

// 业务位置：【前端应用】humanizeDossierText：围绕 案件卷宗 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
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

// 业务位置：【前端应用】humanizeDossierList：围绕 案件卷宗 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
export function humanizeDossierList(values, fallback = "等待补充更多信息") {
  const list = Array.isArray(values) ? values : [values];
  const mapped = list
    .map((value) => humanizeDossierText(value, { fallback: "" }))
    .filter(Boolean);
  return mapped.length ? mapped : [fallback];
}

// 业务位置：【前端应用】displayRoomMessageText：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
export function displayRoomMessageText(value) {
  if (!value) return "";
  const raw = String(value);
  const questionMarks = (raw.match(/\?/g) || []).length;
  if (questionMarks >= 6 && questionMarks / raw.length > 0.35) {
    return "历史消息编码异常，原始内容已按不可变记录留存。";
  }
  return replaceInternalTokensPreservingEvidenceIds(
    summarizeEvidenceMatrixJson(raw),
  );
}

function replaceInternalTokensPreservingEvidenceIds(raw) {
  const evidenceIds = [];
  const protectedText = String(raw || "").replace(
    /\bEVIDENCE_[A-Za-z0-9_-]+\b/g,
    (evidenceId) => {
      const index = evidenceIds.push(evidenceId) - 1;
      return `@@ROOM_EVIDENCE_${index}@@`;
    },
  );
  const localized = replaceInternalTokens(protectedText);
  return localized.replace(
    /@@ROOM_EVIDENCE_(\d+)@@/g,
    (_placeholder, index) => evidenceIds[Number(index)] || "",
  );
}

// 业务位置：【前端应用】summarizeEvidenceMatrixJson：围绕 事实-证据矩阵 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function summarizeEvidenceMatrixJson(raw) {
  return raw.replace(EVIDENCE_MATRIX_JSON_RE, (jsonText) => {
    try {
      const row = JSON.parse(jsonText);
      return localizedEvidenceMatrixRow(row);
    } catch {
      return "证据材料尚未映射到具体争议事实，当前核验状态为待核验";
    }
  });
}

// 业务位置：【前端应用】localizedEvidenceMatrixRow：将 事实-证据矩阵 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function localizedEvidenceMatrixRow(row) {
  const relation = localizedRelationType(row?.relation_type);
  const verification = localizedVerificationStatus(row?.verification_status);
  const strength = localizedEvidenceStrength(row?.evidence_strength);
  const parts = [`证据材料${relation || "已入卷，但尚未形成明确证明方向"}`];
  if (verification) parts.push(`当前核验状态为${verification}`);
  if (strength) parts.push(`证明强度为${strength}`);
  return `${parts.join("，")}，庭审中需继续说明其对应的争议事实、形成时间和来源链路`;
}

// 业务位置：【前端应用】localizedRelationType：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function localizedRelationType(value) {
  switch (String(value || "").toUpperCase()) {
    case "UNMAPPED":
    case "UNKNOWN":
    case "":
      return "尚未映射到具体争议事实";
    case "SUPPORTS":
    case "SUPPORTING":
    case "SUPPORT":
      return "支持相关争议事实";
    case "OPPOSES":
    case "OPPOSING":
    case "REFUTES":
      return "反驳相关争议事实";
    case "PARTIAL":
    case "PARTIALLY_SUPPORTS":
      return "与相关争议事实存在部分关联";
    default:
      return "已关联到争议事实";
  }
}

// 业务位置：【前端应用】localizedVerificationStatus：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function localizedVerificationStatus(value) {
  switch (String(value || "").toUpperCase()) {
    case "UNVERIFIED":
    case "PENDING":
    case "UNKNOWN":
    case "":
      return "待核验";
    case "VERIFIED":
      return "已核验";
    case "PARTIALLY_VERIFIED":
      return "部分核验";
    case "QUESTIONABLE":
    case "SUSPICIOUS":
      return "存疑，需人工复核";
    case "REJECTED":
      return "未采纳";
    default:
      return "待复核";
  }
}

// 业务位置：【前端应用】localizedEvidenceStrength：将 当前可见证据和附件 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function localizedEvidenceStrength(value) {
  switch (String(value || "").toUpperCase()) {
    case "HIGH":
    case "STRONG":
      return "较强";
    case "MEDIUM":
      return "中等";
    case "LOW":
    case "WEAK":
      return "较弱";
    case "NONE":
    case "UNKNOWN":
    case "":
      return "";
    default:
      return "待评估";
  }
}

// 业务位置：【前端应用】humanizeTitle：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
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

// 业务位置：【前端应用】humanizeSummary：围绕 面向当事人的业务文本 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function humanizeSummary(raw, fallback) {
  const localized = replaceInternalTokens(raw);
  if (CHINESE_RE.test(localized)) return localized;

  const lower = localized.toLowerCase();
  if (lower.includes("watch") && lower.includes("broken")) {
    return "用户反馈手表损坏，仍需补充故障细节、证据和双方处理意见。";
  }
  if (lower.includes("no additional details") || lower.includes("provided")) {
    return "接待官正在整理争议事实，请继续补充案件经过、当前状态和处理诉求。";
  }
  if (LATIN_RE.test(localized)) {
    return fallback || "接待官正在整理争议事实，请继续补充关键信息。";
  }
  return localized;
}

// 业务位置：【前端应用】replaceInternalTokens：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function replaceInternalTokens(raw) {
  const source = String(raw || "");
  const exact = TOKEN_LABELS[source.trim()];
  if (exact !== undefined) return exact;

  let output = source;
  for (const [token, label] of TOKEN_REPLACEMENTS) {
    output = output.split(token).join(label);
  }
  return output;
}
