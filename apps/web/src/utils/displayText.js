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
  user_claimed_specific_performance_metrics: "用户主张的具体性能指标",
  user_merchant_communication_details: "用户与商家的沟通详情",
  decision_action: "执行动作",
  recommended_decision: "总体建议",
  remedy_orders: "处理事项",
  fact_findings: "事实认定",
  rule_applications: "规则适用",
  decision_reasoning: "裁决理由",
  reviewer_attention: "人工关注事项",
  review_focus: "复核重点",
  review_responses: "复审回应",
  mandatory_revisions: "强制修订项",
  requires_revision: "是否需要修订",
  evidence_gap: "证据缺口",
  truth_status: "事实认定状态",
  evidence_coverage_status: "证据覆盖状态",
  evidence_relation: "证据关联性",
  evidence_ids: "证据引用",
  fact_id: "事实编号",
  fact_ids: "关联事实编号",
  review_item_ref: "复审事项编号",
  review_source: "复审来源",
  requires_human_review: "是否需要人工复核",
  source_fact_ids: "来源事实编号",
  target_roles: "目标参与方",
  requested_material: "所需材料",
  verification_goal: "核验目标",
  satisfied_conditions: "已满足条件",
  unmet_conditions: "未满足条件",
  applicability_status: "规则适用状态",
  application_result: "适用结果",
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
  user: "用户",
  merchant: "商家",
  platform: "平台",
  TRUE: "是",
  FALSE: "否",
  true: "是",
  false: "否",
  NONE: "无",
  CREATED: "已创建",
  PREPARING: "生成中",
  COMPLETED: "已完成",
  FAILED: "失败",
  TIMED_OUT: "已超时",
  FROZEN: "已冻结",
  EXPIRED: "已过期",
  DECIDED: "已终审",
  APPROVED: "已批准",
  REJECTED: "已驳回",
  ASSIGNED: "已分配",
  IN_REVIEW: "审核中",
  ESCALATED: "已升级人工接管",
  MANUAL_HANDOFF: "人工接管中",
  CLOSED: "已关闭",
  APPROVED_FOR_EXECUTION: "已批准执行",
  EXECUTING: "执行中",
  SUCCEEDED: "执行成功",
  CANCELLED: "已取消",
  INTAKE_PENDING: "等待接待",
  INTAKE_ACTIVE: "接待处理中",
  EVIDENCE_PENDING: "等待举证",
  HEARING_PENDING: "等待庭审",
  DRAFT_READY: "裁决草案已生成",
  DELIBERATION_RUNNING: "裁决生成中",
  REVIEW_PENDING: "等待人工终审",
  REMEDY_PLANNED: "执行方案已形成",
  PENDING_SUBMISSION: "待提交",
  SUBMITTED: "已提交",
  VOIDED: "已作废",
  UNVERIFIED: "待核验",
  VERIFIED: "已核验",
  PLAUSIBLE: "初步可信",
  PARTIALLY_VERIFIED: "部分核验",
  QUESTIONABLE: "存在疑点",
  SUSPICIOUS: "存在疑点",
  INCONCLUSIVE: "无法确认",
  ESTABLISHED: "已认定",
  PARTIALLY_ESTABLISHED: "部分认定",
  UNESTABLISHED: "未认定",
  CONFIRMED: "已确认",
  PARTIALLY_CONFIRMED: "部分确认",
  NOT_EVALUATED: "尚未认定",
  NOT_ESTABLISHED: "未能认定",
  NOT_PROVEN: "未证实",
  CLAIMED_BY_USER: "用户单方主张",
  CLAIMED_BY_MERCHANT: "商家单方主张",
  CLAIMED_BY_BOTH: "双方均有主张",
  CONTESTED: "双方有争议",
  DISPUTED: "双方有争议",
  COVERED: "已有证据覆盖",
  PARTIALLY_COVERED: "部分证据覆盖",
  UNCOVERED: "证据未覆盖",
  PENDING_EVIDENCE_REVIEW: "待证据审查",
  COVERED_BY_SUBMITTED_EVIDENCE: "已有提交证据覆盖",
  COVERED_BY_FROZEN_DOSSIER: "已有冻结证据覆盖",
  PARTIALLY_COVERED_BY_FROZEN_DOSSIER: "部分证据覆盖",
  NOT_COVERED_BY_FROZEN_DOSSIER: "冻结证据未覆盖",
  CONTENT_SUPPORTS: "支持该事实",
  CONTENT_CONTRADICTS: "反驳该事实",
  CONTEXT_ONLY: "仅作背景参考",
  SUPPORTS: "支持该事实",
  OPPOSES: "反驳该事实",
  NOT_COMPUTED: "尚未比对",
  AGREED: "双方一致",
  PARTIALLY_AGREED: "部分一致",
  ONE_SIDED: "仅一方陈述",
  UNRESOLVED: "尚未解决",
  CONFIRM: "确认",
  AGREE: "同意",
  ACCEPT: "认可",
  DENY: "否认",
  DISAGREE: "不同意",
  REJECT: "不认可",
  PARTIAL: "部分认可",
  PARTIALLY_AGREE: "部分认可",
  NOT_ADDRESSED: "未回应",
  CORE: "核心事实",
  SUPPORTING: "辅助事实",
  CONTEXT: "背景事实",
  ORDER: "订单",
  PRODUCT_PAGE: "商品信息",
  PRODUCT_STATE: "商品状态",
  AFTER_SALES: "售后诉求",
  LOGISTICS: "物流",
  PAYMENT: "支付",
  TIME: "时间",
  PARTY: "当事人",
  CANCEL_ORDER: "取消订单",
  RETURN_AND_REFUND: "退货退款",
  REFUND_ONLY: "仅退款",
  RESHIP: "补发商品",
  REPLACE: "更换商品",
  CONTINUE_FULFILLMENT: "继续履约",
  REJECT_CLAIM: "驳回诉求",
  ESCALATE_MANUAL: "升级人工接管",
  MERCHANT_APPROVED_REFUND: "商家同意退款规则",
  UNSHIPPED_CANCEL: "未发货订单取消规则",
  APPLICABLE: "适用",
  NOT_APPLICABLE: "不适用",
  PARTIALLY_APPLICABLE: "部分适用",
  SATISFIED: "已满足",
  UNSATISFIED: "未满足",
  ACCEPTED: "已采纳",
  PARTIALLY_ACCEPTED: "部分采纳",
  BLOCKER: "阻断",
  CRITICAL: "极高风险",
  FACT_COMPLETENESS: "事实完整性",
  EVIDENCE_CONSISTENCY: "证据一致性",
  RULE_APPLICABILITY: "规则适用性",
  PROCEDURAL_FAIRNESS: "程序公平性",
  REMEDY_FEASIBILITY: "执行方案可行性",
  RISK_AND_OMISSIONS: "风险与遗漏",
  JURY_FINDING_FACT_COMPLETENESS: "陪审意见：事实完整性",
  JURY_FINDING_EVIDENCE_CONSISTENCY: "陪审意见：证据一致性",
  JURY_FINDING_RULE_APPLICABILITY: "陪审意见：规则适用性",
  JURY_FINDING_PROCEDURAL_FAIRNESS: "陪审意见：程序公平性",
  JURY_FINDING_REMEDY_FEASIBILITY: "陪审意见：执行方案可行性",
  JURY_FINDING_RISK_AND_OMISSIONS: "陪审意见：风险与遗漏",
  JURY_REVIEW_REPORT: "陪审复核报告",
  JUDGE_PROPOSAL: "法官裁决草案",
  ADJUDICATION_DRAFT: "裁决草案",
  AGENT_LLM: "AI 模型生成",
  AGENT_MESSAGE: "AI 消息",
  PARTY_ACTION: "当事人操作",
  PARTY_EVIDENCE_REFERENCE: "当事人证据引用",
  PARTY_TEXT: "当事人陈述",
  SYSTEM_STAGE_EVENT: "阶段系统通知",
  ROLE_TEMPLATE: "角色模板",
  USER_UPLOAD: "用户上传",
  MERCHANT_UPLOAD: "商家上传",
  PLATFORM_UPLOAD: "平台上传",
  CHAT_SCREENSHOT: "沟通截图",
  LOGISTICS_PROOF: "物流凭证",
  DELIVERY_RECORD: "履约记录",
  VIDEO: "视频证据",
  IMAGE: "图片证据",
  DOCUMENT: "文档材料",
  LOW_AUTHENTICITY_SUSPECTED_FORGERY: "真实性较低，疑似造假",
  LOW_RELEVANCE_SCORE: "关联度较低",
  LOW_COMPLETENESS_SCORE: "完整度较低",
  LOW_ASSESSMENT_CONFIDENCE: "核验把握较低",
  HIGH_RISK_FLAG: "高风险提示",
  COMPLETE_INTAKE: "完成受理确认",
  SUBMIT_EVIDENCE: "提交证据",
  ENTER_HEARING: "进入庭审",
  PARTICIPATE_HEARING: "参与庭审",
  REVIEW_SETTLEMENT: "确认一致方案",
  AWAIT_REVIEW: "等待平台终审",
  TRACK_EXECUTION: "跟踪执行",
  VIEW_OUTCOME: "查看处理结果",
  CONTINUE_CASE: "继续处理",
  CONTINUE_PROCESSING: "继续处理",
  TARGET_NO_EXTERNAL_EFFECT: "仅形成裁决结论，不直接执行外部操作",
  NO_EXTERNAL_EFFECT: "不直接执行外部操作",
  maximum_risk_level: "最高风险等级",
  conditions_met: "已满足条件",
  conditions_unmet: "未满足条件",
  INTAKE: "案情接待",
  EVIDENCE: "证据核验",
  HEARING: "智能庭审",
  DRAFT: "裁决草案",
  REVIEW: "人工终审",
  OUTCOME: "执行结果",
  EXTERNAL_IMPORT: "外部导入",
  INTAKE_CREATED: "接待官创建",
  healthy: "运行正常",
  attention: "需要关注",
  offline: "已离线",
  draft: "草稿",
  published: "生产中",
  archived: "历史版本",
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
  "Merchant-approved refund policy": "商家同意退款规则",
  "Unshipped order cancellation policy": "未发货订单取消规则",
  "在 finding 中": "在事实认定中",
  finding: "事实认定",
};

const TOKEN_LABELS = {
  ...ROLE_LABELS,
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

  const exact = TOKEN_LABELS[raw] ?? TOKEN_LABELS[raw.toUpperCase()];
  if (exact) return exact;
  if (options.kind === "title") return humanizeTitle(raw, fallback);
  if (options.kind === "summary") return humanizeSummary(raw, fallback);

  return replaceInternalTokens(raw);
}

// 将单个后端枚举转换为展示文案。未知值不在此处猜测业务含义，由调用方提供安全回退。
export function domainCodeLabel(value, fallback = "") {
  if (value === null || value === undefined) return fallback;
  const raw = String(value).trim();
  if (!raw) return fallback;
  if (CHINESE_RE.test(raw)) return raw;
  return TOKEN_LABELS[raw] ?? TOKEN_LABELS[raw.toUpperCase()] ?? fallback;
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
  const exact = TOKEN_LABELS[source.trim()] ?? TOKEN_LABELS[source.trim().toUpperCase()];
  if (exact !== undefined) return exact;

  let output = source;
  for (const [token, label] of TOKEN_REPLACEMENTS) {
    if (/^[A-Za-z][A-Za-z0-9_]*$/.test(token)) {
      const escaped = token.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const rightBoundary = token.includes("_")
        ? "(?=$|[^A-Za-z_]|\\d+%)"
        : "(?=$|[^A-Za-z0-9_])";
      output = output.replace(
        new RegExp(`(^|[^A-Za-z0-9_])${escaped}${rightBoundary}`, "g"),
        (_match, prefix) => `${prefix}${label}`,
      );
    } else {
      output = output.split(token).join(label);
    }
  }
  return output
    .replace(/(商家同意退款规则|未发货订单取消规则)@(\d+)\s+\1/gu, "$1（版本 $2）")
    .replace(/(是否需要修订)\s*=\s*(是|否)/gu, "$1：$2")
    .replace(/(是否需要人工复核)\s*=\s*(是|否)/gu, "$1：$2")
    .replace(/\bJURY_MANDATORY_(\d+)\b/gu, "陪审必改项 $1")
    .replace(/\bV1_FOCUS_(\d+)\b/gu, "法官 V1 复核重点 $1");
}
