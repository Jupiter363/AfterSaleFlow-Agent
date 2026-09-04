<!--
  平台终审工作台：将冻结 ReviewPacket 整理为可核验的事实、证据、草案和执行方案，
  并把最终决定限制在具备权限的平台审核员手中。
-->

<script setup>
import {
  computed,
  defineComponent,
  h,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { extractAgentRunDescriptor } from "../../api/agentStream";
import { evidenceApi } from "../../api/evidence";
import {
  ACTIVE_REVIEW_STATUSES,
  normalizeReviewPacket as normalizeReviewApiPacket,
  normalizeReviewTask,
  reviewApi,
} from "../../api/review";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import AgentStreamErrorDialog from "../../components/room/AgentStreamErrorDialog.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  activeAgentStreams,
  clearAgentStreams,
  consumeAgentRun,
} from "../../stores/agentStream";
import { domainCodeLabel } from "../../utils/displayText";

const props = defineProps({
  initialPacket: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  decideAction: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const normalizedInitialPacket = normalizeReviewPacket(props.initialPacket);
const packet = ref(normalizedInitialPacket);
const evidenceCatalog = ref(null);
const loading = ref(!props.initialPacket);
const initialTaskStatus = String(
  normalizedInitialPacket?.review_task_status ||
    (props.initialPacket ? "IN_REVIEW" : ""),
)
  .trim()
  .toUpperCase();
const taskOpen = ref(
  Boolean(props.initialPacket) && ACTIVE_REVIEW_STATUSES.includes(initialTaskStatus),
);
const taskStateKnown = ref(Boolean(props.initialPacket));
const taskStatus = ref(initialTaskStatus);
const taskAssignedReviewerId = ref(
  normalizedInitialPacket?.assigned_reviewer_id || "",
);
const taskLookupError = ref("");
const activeSection = ref("overview");
const reason = ref("");
const pendingDecision = ref("");
const decisionResult = ref(null);
const error = ref("");
const decisionSubmitError = ref("");
const submitting = ref(false);
const actionSelectorOpen = ref(false);
const reviewerOpinionDecision = ref("");
const selectedDecisionActionCode = ref("");
const approvedPlanDraft = ref(null);
const submittedDecision = ref("");
const confirmButton = ref(null);
const confirmCancelButton = ref(null);
const confirmDialog = ref(null);
const actionSelectorDialog = ref(null);
const actionSelectorCloseButton = ref(null);
const reviewOperationScroll = ref(null);
const reviewOperationScrollTrack = ref(null);
const reviewOperationScrollVisible = ref(false);
const reviewOperationScrollOffset = ref(0);
let decisionTrigger = null;
let actionSelectorTrigger = null;
let reviewOperationResizeObserver = null;
let reviewOperationMutationObserver = null;
const agentState = ref("LISTENING");
const copilotQuestion = ref("");
const copilotMessages = ref([]);
const copilotSubmitting = ref(false);
const copilotStreamError = ref("");
const REVIEW_OPERATION_SCROLL_THUMB_HEIGHT = 72;

const reviewId = computed(() => route.params.reviewId);
const historyMode = computed(() => route.query.view === "history");
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const packetExpiry = computed(() => {
  const deadlines = [packet.value?.expires_at, packet.value?.review_deadline]
    .map((value) => Date.parse(value || ""))
    .filter(Number.isFinite);
  return deadlines.length ? new Date(Math.min(...deadlines)).toISOString() : "";
});
const clockNow = ref(Date.now());
const clockAnchorLocal = ref(Date.now());
const clockAnchorServer = ref(Date.parse(effectiveServerNow.value));
const packetExpired = computed(() => {
  const expiresAt = Date.parse(packetExpiry.value);
  const estimatedServerNow =
    clockAnchorServer.value + (clockNow.value - clockAnchorLocal.value);
  if (!Number.isFinite(expiresAt) || !Number.isFinite(estimatedServerNow)) {
    return false;
  }
  return estimatedServerNow >= expiresAt;
});
const hasReviewerWriteCapability = computed(() => {
  const role = props.viewerRole || actor.role;
  if (role !== "PLATFORM_REVIEWER") return false;
  const assignedReviewerId =
    taskAssignedReviewerId.value || packet.value?.assigned_reviewer_id || "";
  return !assignedReviewerId || assignedReviewerId === actor.id;
});
const canDecide = computed(
  () =>
    !historyMode.value &&
    !decisionResult.value &&
    taskOpen.value &&
    !packetExpired.value &&
    hasReviewerWriteCapability.value &&
    packet.value?.status === "FROZEN",
);

const copilotContext = computed(() => ({
  caseId: packet.value?.case_id || "",
  roomType: "REVIEW",
  actor,
}));
const copilotRuns = computed(() => {
  if (historyMode.value) return [];
  const durableRunIds = new Set(
    copilotMessages.value.map((message) => message.agent_run_id).filter(Boolean),
  );
  return activeAgentStreams(copilotContext.value).filter(
    (run) => !durableRunIds.has(run.runId),
  );
});
const copilotBusy = computed(
  () => copilotSubmitting.value || copilotRuns.value.length > 0,
);
const canUseCopilot = computed(
  () =>
    !historyMode.value &&
    taskOpen.value &&
    !packetExpired.value &&
    hasReviewerWriteCapability.value &&
    packet.value?.status === "FROZEN",
);
const digitalHumanState = computed(() =>
  copilotBusy.value ? "THINKING" : agentState.value,
);

const reviewSections = [
  { value: "overview", label: "案件概览" },
  { value: "evidence", label: "证据矩阵" },
  { value: "draft", label: "裁决草案" },
  { value: "risk", label: "重点复核" },
  { value: "audit", label: "版本与审计" },
];
const explanationPrompts = [
  "请概括这个案件的核心争议",
  "当前证据缺口和主要风险是什么？",
  "解释草案建议与适用规则的关系",
];
const approvalDecisions = [
  {
    value: "APPROVE",
    label: "批准 AI 建议",
    description: "沿用 AI 建议的执行动作",
    icon: "✓",
  },
  {
    value: "MODIFY_AND_APPROVE",
    label: "修改执行动作",
    description: "从候选决定中另选执行动作",
    icon: "✎",
  },
];
const exceptionDecisions = [
  {
    value: "ESCALATE_MANUAL",
    label: "升级人工接管",
    description: "终止自动链路并转人工处理",
    icon: "↗",
  },
];
const decisionActionCatalog = Object.freeze([
  {
    code: "CANCEL_ORDER",
    label: "取消订单",
    meaning: "取消当前订单；是否产生退款由后续订单结算处理",
  },
  {
    code: "RETURN_AND_REFUND",
    label: "退货退款",
    meaning: "用户退回商品后，由商家退还相应款项",
  },
  {
    code: "REFUND_ONLY",
    label: "仅退款",
    meaning: "用户无需退货，直接获得全额或部分退款",
  },
  {
    code: "RESHIP",
    label: "补发商品",
    meaning: "针对未送达、漏发或缺失商品，由商家补发",
  },
  {
    code: "REPLACE",
    label: "更换商品",
    meaning: "针对已收到但存在问题的商品，由商家换货",
  },
  {
    code: "REPAIR",
    label: "维修商品",
    meaning: "由商家对争议商品提供维修处理",
  },
  {
    code: "COMPENSATE",
    label: "补偿",
    meaning: "在主要交易处理之外或无需退款时，向用户提供补偿",
  },
  {
    code: "CONTINUE_FULFILLMENT",
    label: "继续履约",
    meaning: "维持当前订单关系，等待商家继续完成原有履约义务",
  },
  {
    code: "REJECT_CLAIM",
    label: "驳回诉求",
    meaning: "不支持本次售后诉求并结束案件",
  },
]);
const decisionActionByCode = new Map(
  decisionActionCatalog.map((item) => [item.code, item]),
);
const decisionActionLabels = Object.fromEntries(
  decisionActionCatalog.map((item) => [item.code, item.label]),
);

const roleLabels = {
  user: "用户",
  merchant: "商家",
  platform: "平台观察",
  USER: "用户",
  MERCHANT: "商家",
  PLATFORM: "平台观察",
  PLATFORM_REVIEWER: "平台审核员",
  REVIEW_COPILOT: "审核解释官",
};
const riskLabels = {
  CRITICAL: "极高风险",
  HIGH: "高风险",
  MEDIUM: "中风险",
  LOW: "低风险",
};
const packetStatusLabels = {
  FROZEN: "已冻结",
  PREPARING: "生成中",
  EXPIRED: "已过期",
  DECIDED: "已终审",
  APPROVED: "已批准",
  REJECTED: "已驳回",
};
const taskStatusLabels = {
  PENDING: "待审核",
  ASSIGNED: "已分配",
  IN_REVIEW: "审核中",
  APPROVED: "审核通过",
  ESCALATED: "已升级人工接管",
  MANUAL_HANDOFF: "人工接管中",
  REJECTED: "审核未通过",
  CLOSED: "已关闭",
};
const routeLabels = {
  FULL_HEARING: "完整庭审",
  DISPUTE_HEARING: "争议庭审",
  NORMAL_HEARING: "普通庭审",
  FAST_TRACK: "快速处理",
  MEDIATION: "协商处理",
};
const outcomeLabels = {
  REFUND: "退款",
  FULL_REFUND: "全额退款",
  PARTIAL_REFUND: "部分退款",
  RETURN: "退货",
  RETURN_REFUND: "退货退款",
  RETURN_AND_REFUND: "退货退款",
  RESHIP: "补发",
  RESEND: "补发",
  REPLACEMENT: "换新或补发",
  REPLACE: "换货",
  EXCHANGE: "换货",
  REPAIR: "维修",
  REPLACE_OR_REPAIR: "换货或维修",
  COMPENSATION: "补偿",
  REJECT: "不支持售后",
  REJECT_REFUND: "不支持退款",
  NO_REFUND: "不予退款",
  MANUAL_REVIEW: "转人工复核",
  MANUAL_REVIEW_REQUIRED: "需要人工复核",
  CONDITIONAL_RETURN_FOR_INSPECTION: "退回检测后按结果处理",
  RESHIP_IF_SIGNATURE_PROOF_MISSING: "签收凭证缺失时补发",
  RESHIP_BY_CONFIRMED_SETTLEMENT: "按已确认方案补发",
  RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW: "核验签收凭证后补发或退款",
  OTHER: "其他处理诉求",
  UNKNOWN: "待确认",
};
const actionLabels = {
  REFUND: "原路退款",
  RETURN_AND_REFUND: "退货退款",
  RESHIP: "重新发货",
  REPLACE: "更换商品",
  CANCEL_ORDER: "取消订单",
  REJECT_AFTER_SALE: "关闭售后申请",
  CLOSE_AFTER_SALE: "关闭售后申请",
  CREATE_MANUAL_REVIEW_TICKET: "创建人工复核工单",
  CREATE_FULFILLMENT_REMINDER: "创建履约提醒",
  QUERY_LOGISTICS: "查询物流状态",
  NOTIFY_USER: "通知用户",
  NOTIFY_MERCHANT: "通知商家",
};
const riskFlagLabels = {
  EVIDENCE_INSUFFICIENT: "关键证据不足",
  HIGH_VALUE: "高金额案件",
  HIGH_VALUE_REFUND: "高金额退款",
  HIGH_VALUE_RESHIP: "高金额补发",
  ITEM_SWAP_DISPUTE: "商品调包争议",
  SIGNATURE_MISMATCH: "签收信息不一致",
  SAFETY_RISK_HIGH: "人身或财产安全风险",
};
const factFindingLabels = {
  CONFIRMED: "已确认",
  PARTIALLY_CONFIRMED: "部分确认",
  NOT_EVALUATED: "尚未认定",
  NOT_ESTABLISHED: "未能认定",
  NOT_PROVEN: "未证实",
  CLAIMED_BY_USER: "用户单方主张",
  CLAIMED_BY_MERCHANT: "商家单方主张",
  CLAIMED_BY_BOTH: "双方均有主张",
  CONTESTED: "仍有争议",
  INCONCLUSIVE: "无法确认",
};
const evidenceCoverageLabels = {
  PENDING_EVIDENCE_REVIEW: "待证据审查",
  COVERED_BY_SUBMITTED_EVIDENCE: "已有提交证据覆盖",
  COVERED_BY_FROZEN_DOSSIER: "已有冻结证据覆盖",
  PARTIALLY_COVERED_BY_FROZEN_DOSSIER: "部分证据覆盖",
  NOT_COVERED_BY_FROZEN_DOSSIER: "冻结证据未覆盖",
  REQUIRES_HUMAN_REVIEW: "需要人工复核",
};
const evidenceRelationLabels = {
  CONTENT_SUPPORTS: "支持该事实",
  CONTENT_CONTRADICTS: "反驳该事实",
  CONTEXT_ONLY: "仅作背景参考",
  INCONCLUSIVE: "关联性不确定",
  SUPPORTS: "支持该事实",
  OPPOSES: "反驳该事实",
};
const alignmentLabels = {
  NOT_COMPUTED: "尚未比对",
  AGREED: "双方一致",
  PARTIALLY_AGREED: "部分一致",
  CONTESTED: "双方有争议",
  ONE_SIDED: "仅一方陈述",
  UNRESOLVED: "尚未解决",
};
const stanceLabels = {
  CONFIRM: "确认",
  AGREE: "同意",
  ACCEPT: "认可",
  DENY: "否认",
  DISAGREE: "不同意",
  REJECT: "不认可",
  PARTIAL: "部分认可",
  PARTIALLY_AGREE: "部分认可",
  NOT_ADDRESSED: "未回应",
};
const materialityLabels = {
  CORE: "核心事实",
  SUPPORTING: "辅助事实",
  CONTEXT: "背景事实",
};
const factCategoryLabels = {
  ORDER: "订单",
  PRODUCT_PAGE: "商品信息",
  PRODUCT_STATE: "商品状态",
  AFTER_SALES: "售后诉求",
  LOGISTICS: "物流",
  PAYMENT: "支付",
  TIME: "时间",
  PARTY: "当事人",
  OTHER: "其他",
};
const ruleCodeLabels = {
  MERCHANT_APPROVED_REFUND: "商家同意退款规则",
  UNSHIPPED_CANCEL: "未发货订单取消规则",
};
const policyReferenceLabels = {
  POLICY_MERCHANT_REFUND_V1: "商家同意退款规则",
  POLICY_UNSHIPPED_CANCEL_V1: "未发货订单取消规则",
  POLICY_DELIVERY_PROOF_V1: "交付证明规则",
};
const ruleNameLabels = {
  "merchant-approved refund policy": "商家同意退款规则",
  "unshipped order cancellation policy": "未发货订单取消规则",
};
const ruleApplicationTokenLabels = {
  requires_evidence: "证据充分性",
  evidence_sufficiency: "证据充分性",
  merchant_approval: "商家退款同意",
  shipment_status: "订单发货状态",
  delivery_status: "商品交付状态",
  order_status: "订单状态",
  payment_status: "支付状态",
  refund_eligibility: "退款资格",
  cancellation_eligibility: "订单取消资格",
  product_quality_issue: "商品质量问题",
  party_agreement: "双方意见一致",
  requested_resolution: "请求处理方式",
  user_request: "用户诉求",
};
const ruleApplicationTokenPattern = new RegExp(
  `\\b(${Object.keys(ruleApplicationTokenLabels).join("|")})\\b`,
  "g",
);
const remedyTypeLabels = {
  FURTHER_VERIFICATION: "进一步人工核验",
  ...decisionActionLabels,
};
const reviewSourceLabels = {
  V1_REVIEW_FOCUS: "法官 V1 复核重点",
  JURY_FINDING: "陪审意见",
  MANDATORY_REVISION: "陪审强制修订项",
};
const reviewDispositionLabels = {
  ACCEPTED: "已采纳",
  PARTIALLY_ACCEPTED: "部分采纳",
  REJECTED: "未采纳",
};
const reviewTopicLabels = {
  FACT_COMPLETENESS: "事实完整性",
  EVIDENCE_CONSISTENCY: "证据一致性",
  RULE_APPLICABILITY: "规则适用性",
  PROCEDURAL_FAIRNESS: "程序公平性",
  REMEDY_FEASIBILITY: "执行可行性",
  RISK_AND_OMISSIONS: "风险与遗漏",
  SINGLE_ACTION: "执行动作收束",
};
const affectedFieldLabels = {
  decision_action: "最终执行决定",
  recommended_decision: "总体建议",
  remedy_orders: "处理事项",
  fact_findings: "事实认定",
  rule_applications: "规则适用",
  decision_reasoning: "裁决理由",
  reviewer_attention: "人工关注事项",
};
const reviewTextFieldLabels = {
  ...affectedFieldLabels,
  truth_status: "事实认定状态",
  evidence_coverage_status: "证据覆盖状态",
  evidence_relation: "证据关联性",
  evidence_ids: "证据引用",
  fact_id: "事实编号",
  fact_ids: "关联事实编号",
  review_item_ref: "复审事项编号",
  review_source: "复审来源",
  requires_human_review: "需人工复核",
};
const reviewTextFieldTokenPattern = new RegExp(
  `\\b(${Object.keys(reviewTextFieldLabels)
    .sort((left, right) => right.length - left.length)
    .join("|")})\\b`,
  "g",
);
const evidenceStatusLabels = {
  UNVERIFIED: { label: "待核验", tone: "neutral" },
  PENDING: { label: "待核验", tone: "neutral" },
  UNKNOWN: { label: "待核验", tone: "neutral" },
  PLAUSIBLE: { label: "初步可信", tone: "verified" },
  VERIFIED: { label: "已核验", tone: "verified" },
  PARTIALLY_VERIFIED: { label: "部分核验", tone: "partial" },
  QUESTIONABLE: { label: "存在疑点", tone: "warning" },
  SUSPICIOUS: { label: "存在疑点", tone: "warning" },
  NEEDS_HUMAN_REVIEW: { label: "待人工复核", tone: "review" },
  REQUIRES_HUMAN_REVIEW: { label: "需人工复核", tone: "review" },
  INCONCLUSIVE: { label: "证据不足", tone: "warning" },
  REJECTED: { label: "未采纳", tone: "danger" },
};
const evidenceStatusPattern = new RegExp(
  `\\b(${Object.keys(evidenceStatusLabels).join("|")})\\b`,
  "g",
);
const preconditionLabels = {
  CASE_NOT_CLOSED: "案件未关闭",
  PLAN_VERSION_CURRENT: "方案版本仍有效",
  PLATFORM_REVIEW_APPROVED: "平台终审已批准",
  TARGET_RESOURCE_AVAILABLE: "目标资源可用",
  PAYMENT_ELIGIBLE: "支付记录可退款",
  REFUND_AMOUNT_RESOLVED: "退款金额已确定",
  ORDER_CANCELLABLE: "订单仍可取消",
  INVENTORY_AVAILABLE: "库存可用",
  REVIEW_DECISION_RECORDED: "终审决定已记录",
};
const notificationLabels = {
  NOTIFY_USER_AFTER_EXECUTION: "执行后通知用户",
  NOTIFY_MERCHANT_AFTER_EXECUTION: "执行后通知商家",
  AUDIT_EXECUTION_RESULT: "记录执行审计",
};
const decisionLabels = Object.fromEntries(
  [...approvalDecisions, ...exceptionDecisions].map((item) => [
    item.value,
    item.label,
  ]),
);

const fieldLabels = {
  source_recommendation: "草案建议",
  source_is_final_decision: "是否最终决定",
  amount: "金额",
  refund_amount: "退款金额",
  target: "目标方",
  recipient: "接收方",
  reason: "原因",
  currency: "币种",
  quantity: "数量",
  order_id: "订单号",
  item_id: "商品编号",
  address: "地址",
  deadline: "截止时间",
  channel: "处理渠道",
};

const commonValueLabels = {
  ...roleLabels,
  ...riskLabels,
  ...packetStatusLabels,
  ...taskStatusLabels,
  ...routeLabels,
  ...outcomeLabels,
  ...actionLabels,
  ...riskFlagLabels,
  ...factFindingLabels,
  ...evidenceCoverageLabels,
  ...evidenceRelationLabels,
  ...alignmentLabels,
  ...stanceLabels,
  ...materialityLabels,
  ...factCategoryLabels,
  ...ruleCodeLabels,
  ...policyReferenceLabels,
  ...remedyTypeLabels,
  ...reviewSourceLabels,
  ...reviewTopicLabels,
  ...reviewDispositionLabels,
  ...affectedFieldLabels,
  ...preconditionLabels,
  ...notificationLabels,
  ...decisionLabels,
  ...decisionActionLabels,
  ...Object.fromEntries(
    Object.entries(evidenceStatusLabels).map(([code, presentation]) => [
      code,
      presentation.label,
    ]),
  ),
  AI: "AI",
  API: "接口",
  ID: "编号",
  JSON: "结构化数据",
  OCR: "文字识别",
  PDF: "PDF 文件",
  URL: "链接",
  CNY: "人民币",
  TRUE: "是",
  FALSE: "否",
  NONE: "无",
  COMPLETED: "已完成",
  FAILED: "失败",
  TIMED_OUT: "已超时",
  BLOCKER: "阻断风险",
  NO_MAJOR_OBJECTION: "无重大异议",
  REVISION_REQUIRED: "需要修订",
  ACCEPTED: "已受理",
  NEED_MORE_INFO: "需要补充信息",
  NOT_ADMISSIBLE: "暂不受理",
  PENDING_HUMAN_REVIEW: "等待人工复核",
  WAITING_HUMAN_REVIEW: "等待人工复核",
  RULE_DELIVERY_PROOF: "交付证明规则",
  RULE_REFUND_ELIGIBILITY: "退款资格规则",
  BASED_ON_PARTY_AGREEMENT: "基于双方一致陈述确认",
  MANDATORY: "强制修订",
  JURY: "陪审意见",
};

const enumWordLabels = {
  ACTION: "执行动作",
  ADDRESS: "地址",
  AMOUNT: "金额",
  APPROVED: "已批准",
  CASE: "案件",
  CHANNEL: "渠道",
  CONDITIONAL: "附条件",
  CURRENCY: "币种",
  DEADLINE: "截止时间",
  DECISION: "决定",
  DELIVERY: "交付",
  EVIDENCE: "证据",
  FACT: "事实",
  FOR: "用于",
  HUMAN: "人工",
  IF: "如果",
  INSPECTION: "检测",
  IS: "是否",
  ITEM: "商品",
  MANUAL: "人工",
  MISSING: "缺失",
  NOT: "未",
  PENDING: "待处理",
  PARTY: "参与方",
  PROOF: "凭证",
  QUANTITY: "数量",
  REASON: "原因",
  RECIPIENT: "接收方",
  RECOMMENDATION: "建议",
  REFUND: "退款",
  REJECTED: "已驳回",
  REQUIRED: "需要",
  REVIEW: "复核",
  RETURN: "退货",
  RULE: "规则",
  SIGNATURE: "签收",
  SOURCE: "来源",
  TARGET: "目标",
};

function fallbackEnumLabel(value) {
  const text = String(value || "").trim();
  if (!/^[A-Z][A-Z0-9_]*$/.test(text)) return text;
  if (/^(?:V|E)\d+$/.test(text)) return text;
  if (/^ISSUE_\d+$/.test(text)) return `争点 ${text.slice(6)}`;
  if (/^ACTION_\d+$/.test(text)) return `执行动作 ${text.slice(7)}`;

  const words = text.split("_");
  const localized = words.map((word) => enumWordLabels[word] || "");
  if (localized.every(Boolean)) return localized.join("");
  return text.includes("_") ? "待人工确认" : text;
}

function mapReviewTokens(value) {
  return String(value || "")
    .replace(/\bDirect Probative Value\b/gi, "直接证明力")
    .replace(
      reviewTextFieldTokenPattern,
      (token) => reviewTextFieldLabels[token] || token,
    )
    .replace(
      ruleApplicationTokenPattern,
      (token) => ruleApplicationTokenLabels[token] || token,
    )
    .replace(
      /\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b/g,
      (token) => domainCodeLabel(token, token),
    )
    .replace(
      /\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\b/g,
      (token, offset, source) => {
        if (source[offset - 1] === "-" || source[offset + token.length] === "-") {
          return token;
        }
        return commonValueLabels[token] || domainCodeLabel(token, "") || fallbackEnumLabel(token);
      },
    );
}

function fieldLabel(value) {
  const key = String(value || "");
  if (fieldLabels[key]) return fieldLabels[key];
  const normalized = key.toUpperCase();
  const localized = mapReviewTokens(normalized);
  return localized === "待人工确认" ? "执行参数" : localized;
}

function listEntries(value) {
  if (value === null || value === undefined || value === "") return [];
  return (Array.isArray(value) ? value : [value]).filter(
    (item) => item !== null && item !== undefined && item !== "",
  );
}

function firstText(...values) {
  const value = values.find(
    (item) => typeof item === "string" && item.trim().length > 0,
  );
  return value?.trim() || "";
}

function cleanText(value) {
  if (value === null || value === undefined) return "";
  return String(value).replace(/^\s*[\][,，;；:：、-]+\s*/, "").trim();
}

function displayValue(value) {
  if (value === null || value === undefined || value === "") return "未提供";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (Array.isArray(value)) return value.map(displayValue).join("、");
  if (typeof value === "object") {
    const preferred = firstText(value.title, value.name, value.label, value.id);
    if (preferred) return preferred;
    return Object.values(value)
      .filter((item) => ["string", "number", "boolean"].includes(typeof item))
      .map(displayValue)
      .join("；");
  }
  return mapReviewTokens(value);
}

function evidenceReferenceId(value) {
  if (["string", "number"].includes(typeof value)) return String(value);
  return firstText(
    value?.evidence_id,
    value?.evidenceId,
    value?.reference_id,
    value?.referenceId,
    value?.id,
  );
}

function evidenceItemName(value) {
  return firstText(
    value?.evidence_name,
    value?.evidenceName,
    value?.original_filename,
    value?.originalFilename,
    value?.file_name,
    value?.fileName,
    value?.title,
    value?.name,
    value?.label,
  );
}

function evidenceReferenceName(value) {
  const directName = evidenceItemName(value);
  if (directName) return directName;
  return evidenceNamesById.value.get(evidenceReferenceId(value)) || "";
}

function evidenceReferenceLabel(value) {
  const name = evidenceReferenceName(value);
  if (!name) {
    const id = evidenceReferenceId(value);
    const evidenceNumber = id.match(/^EVIDENCE_(\d+)$/i)?.[1];
    if (evidenceNumber) return `证据 ${evidenceNumber}`;
    return id ? "证据材料" : displayValue(value);
  }
  const characters = Array.from(name);
  return characters.length > 5
    ? `${characters.slice(0, 5).join("")}…`
    : name;
}

function numberedEvidenceReferenceLabel(value, index) {
  const label = evidenceReferenceLabel(value);
  return label === "证据材料"
    ? `证据材料 ${String(index + 1).padStart(2, "0")}`
    : label;
}

function evidenceReferenceTitle(value) {
  const id = evidenceReferenceId(value);
  const name = evidenceReferenceName(value);
  if (!name) return id;
  return id ? `${name}（${id}）` : name;
}

function replaceEvidenceReferences(value) {
  return cleanText(value).replace(
    /EVIDENCE_[A-Za-z0-9_-]+/g,
    (reference) => evidenceReferenceLabel(reference),
  );
}

function replaceFactReferences(value) {
  return String(value || "").replace(
    /FACT_[A-Za-z0-9_:-]+/g,
    (reference) => factReferenceLabel(reference),
  );
}

function evidenceTextSegments(value) {
  return replaceFactReferences(replaceEvidenceReferences(value))
    .split(evidenceStatusPattern)
    .filter(Boolean)
    .map((text) => {
      const status = evidenceStatusLabels[text];
      return status
        ? { type: "status", code: text, ...status }
        : { type: "text", text: mapReviewTokens(text) };
    });
}

const EvidenceMappedText = defineComponent({
  name: "EvidenceMappedText",
  props: {
    text: { type: [String, Number], default: "" },
  },
  setup(componentProps) {
    return () =>
      h(
        "span",
        { class: "evidence-mapped-text" },
        evidenceTextSegments(componentProps.text).map((segment, index) =>
          segment.type === "status"
            ? h(
                "span",
                {
                  key: `${segment.code}-${index}`,
                  class: "evidence-inline-status",
                  "data-status": segment.code,
                  "data-tone": segment.tone,
                },
                segment.label,
              )
            : segment.text,
        ),
      );
  },
});

function displayDateTime(value) {
  if (!value) return "未冻结";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "时间无效";
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  return `${date.getMonth() + 1}月${date.getDate()}日 ${hour}:${minute}`;
}

function displayAmount(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "";
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 2,
  }).format(amount);
}

function displayPercent(value) {
  const confidence = Number(value);
  if (!Number.isFinite(confidence)) return "";
  const percent = confidence <= 1 ? confidence * 100 : confidence;
  return `${Math.round(percent)}%`;
}

function enumLabel(value, labels = {}) {
  if (!value) return "";
  const text = String(value).trim();
  return labels[text] || commonValueLabels[text] || mapReviewTokens(text);
}

function reviewRiskEntry(value) {
  const structured = value && typeof value === "object" ? value : null;
  let code = firstText(
    structured?.code,
    structured?.type,
    structured?.risk_code,
    structured?.id,
  );
  let label = firstText(
    structured?.label,
    structured?.message,
    structured?.description,
    typeof value === "string" ? value : "",
  );
  const prefixedLabel = label.match(
    /^([A-Za-z][A-Za-z0-9_-]*)\s*[:：]\s*([\s\S]+)$/u,
  );
  if (prefixedLabel) {
    code ||= prefixedLabel[1];
    label = prefixedLabel[2].trim();
  }
  const reviewSource = label.match(/^\[(MANDATORY|JURY)\]\s*([\s\S]+)$/u);
  if (reviewSource) {
    code ||= label;
    label = `${enumLabel(reviewSource[1])}：${reviewSource[2].trim()}`;
  }
  code ||= label;
  const codeOnly = /^[A-Za-z][A-Za-z0-9_-]*$/u.test(label);
  if (!label) {
    label = "待人工确认";
  } else if (label === code && codeOnly) {
    const localized = enumLabel(code, riskFlagLabels);
    label = localized === code
      ? fallbackEnumLabel(code.toUpperCase().replaceAll("-", "_"))
      : localized;
  }
  return {
    code,
    label:
      mapReviewTokens(
        replaceFactReferences(replaceEvidenceReferences(label)),
      ) || "未提供风险说明",
  };
}

function riskLabel(risk) {
  return enumLabel(risk, riskLabels) || "未评估";
}

function packetStatusLabel(status) {
  return enumLabel(status, packetStatusLabels) || "未知";
}

function claimEntries(claims) {
  if (!claims) return [];
  const canonicalClaims = claims?.claims;
  if (canonicalClaims && typeof canonicalClaims === "object") {
    const entries = [];
    const initiator = canonicalClaims.initiator_claim;
    if (initiator?.position_summary) {
      entries.push({
        label: enumLabel(initiator.initiator_role, roleLabels) || "发起方",
        text: cleanText(initiator.position_summary),
        detail: cleanText(initiator.reason_summary),
        attitude: "提出售后诉求",
      });
    }
    const respondent =
      canonicalClaims.respondent_direct ||
      canonicalClaims.respondent_reported_by_initiator;
    if (respondent?.position_summary) {
      entries.push({
        label: enumLabel(respondent.respondent_role, roleLabels) || "响应方",
        text: cleanText(respondent.position_summary),
        detail: cleanText(respondent.alternative_proposal),
        attitude: enumLabel(respondent.attitude, stanceLabels),
      });
    }
    if (entries.length) return entries;
  }
  if (Array.isArray(claims)) {
    return claims.map((item, index) => ({
      label:
        roleLabels[item?.party] ||
        roleLabels[item?.role] ||
        `主张 ${index + 1}`,
      text: cleanText(item?.claim || item?.text || displayValue(item)),
    }));
  }
  if (typeof claims !== "object") {
    return [{ label: "当事人主张", text: cleanText(claims) }];
  }

  const positions = claims.party_positions || {};
  const resolution = claims.claim_resolution || {};
  const initiatorRole = resolution.initiator_role;
  const entries = [];
  const userClaim = firstText(
    positions.user_claim,
    initiatorRole === "USER" ? positions.initiator_position : "",
    claims.user,
    claims.USER,
  );
  const merchantClaim = firstText(
    positions.merchant_claim,
    claims.respondent_attitude?.position,
    initiatorRole === "MERCHANT" ? positions.initiator_position : "",
    claims.merchant,
    claims.MERCHANT,
  );
  const platformObservation = firstText(
    positions.platform_observation,
    claims.platform,
    claims.PLATFORM,
  );
  if (userClaim) entries.push({ label: "用户", text: cleanText(userClaim) });
  if (merchantClaim) {
    entries.push({ label: "商家", text: cleanText(merchantClaim) });
  }
  if (platformObservation) {
    entries.push({ label: "平台观察", text: cleanText(platformObservation) });
  }
  if (entries.length) return entries;

  return Object.entries(claims)
    .filter(([, value]) => ["string", "number"].includes(typeof value))
    .slice(0, 4)
    .map(([roleName, value]) => ({
      label: enumLabel(roleName, roleLabels),
      text: mapReviewTokens(cleanText(value)),
    }));
}

function referenceEntries(claims) {
  const references = claims?.references || {};
  return [
    ["订单", references.order_reference],
    ["物流", references.logistics_reference],
    ["售后", references.after_sales_reference],
  ]
    .filter(([, value]) => value)
    .map(([label, value]) => ({ label, value }));
}

function issueEntries(sourcePacket) {
  const factRows = listEntries(sourcePacket?.claims?.fact_rows);
  if (factRows.length) {
    const findingsByFactId = new Map(
      listEntries(adjudicationBody(sourcePacket?.draft)?.fact_findings)
        .filter((item) => item?.fact_id)
        .map((item) => [item.fact_id, item]),
    );
    return factRows
      .filter((fact) => fact?.fact_id && fact?.fact_target)
      .map((fact) => {
        const finding = findingsByFactId.get(fact.fact_id);
        const positions = ["USER", "MERCHANT"]
          .map((role) => {
            const position = fact?.positions?.[role];
            if (!position?.position_summary) return null;
            return {
              role,
              roleLabel: enumLabel(role, roleLabels),
              stance: enumLabel(position.stance, stanceLabels),
              text:
                position.stance === "NOT_ADDRESSED"
                  ? "该方未就此事实直接陈述。"
                  : cleanText(position.position_summary),
            };
          })
          .filter(Boolean);
        return {
          id: fact.fact_id,
          code: fact.fact_id,
          text: cleanText(fact.fact_target),
          category: enumLabel(fact.category, factCategoryLabels),
          materiality: enumLabel(fact.materiality, materialityLabels),
          alignment: enumLabel(fact?.party_alignment?.status, alignmentLabels),
          positions,
          finding: finding?.finding
            ? enumLabel(finding.finding, factFindingLabels)
            : "",
          confidence: displayPercent(finding?.confidence),
        };
      });
  }
  const directIssues = listEntries(sourcePacket?.issues);
  const fallbackIssues = listEntries(
    sourcePacket?.claims?.dispute_core_state?.disputed_facts ||
      sourcePacket?.claims?.dispute_core_state?.facts_in_dispute ||
      sourcePacket?.claims?.dispute_focus?.key_conflicts,
  );
  return (directIssues.length ? directIssues : fallbackIssues).map(
    (issue, index) => ({
      id: issue?.id || issue?.issue_id || issue?.fact_id || `issue-${index}`,
      code: issue?.issue_id || issue?.id || issue?.fact_id || "",
      text: cleanText(
        issue?.issue ||
          issue?.title ||
          issue?.question ||
          issue?.description ||
          issue?.finding ||
          displayValue(issue),
      ),
      category: "",
      materiality: "",
      alignment: "",
      positions: [],
      finding: issue?.finding
        ? enumLabel(issue.finding, factFindingLabels)
        : "",
      confidence: displayPercent(issue?.confidence),
    }),
  );
}

function evidenceEntries(matrix, issues) {
  if (matrix && !Array.isArray(matrix) && Array.isArray(matrix.fact_coverage)) {
    const issuesByFactId = new Map(
      issues.filter((issue) => issue?.code).map((issue) => [issue.code, issue]),
    );
    const links = listEntries(matrix.links);
    return matrix.fact_coverage
      .filter((coverage) => coverage?.fact_id)
      .map((coverage) => {
        const linkedIssue = issuesByFactId.get(coverage.fact_id);
        const relatedLinks = Array.from(
          new Map(
            links
              .filter((link) => link?.fact_id === coverage.fact_id)
              .map((link) => [
                [link.evidence_id, link.relation, link.reason].join(":"),
                {
                  evidenceId: link.evidence_id,
                  relation: link.relation,
                  relationLabel: enumLabel(
                    link.relation,
                    evidenceRelationLabels,
                  ),
                  reason: cleanText(link.reason),
                },
              ]),
          ).values(),
        );
        const status = String(coverage.coverage_status || "").trim();
        return {
          id: coverage.fact_id,
          code: coverage.fact_id,
          issue: linkedIssue?.text || "",
          matrixKind: "FACT_LEVEL",
          coverageStatus: status,
          coverageLabel: enumLabel(status, evidenceCoverageLabels),
          links: relatedLinks,
          supporting: relatedLinks
            .filter((link) => link.relation === "CONTENT_SUPPORTS")
            .map((link) => link.evidenceId),
          contradicting: relatedLinks
            .filter((link) => link.relation === "CONTENT_CONTRADICTS")
            .map((link) => link.evidenceId),
          missing: [
            "PARTIALLY_COVERED_BY_FROZEN_DOSSIER",
            "NOT_COVERED_BY_FROZEN_DOSSIER",
            "REQUIRES_HUMAN_REVIEW",
          ].includes(status),
          confidence: "",
          confidenceValue: Number.NaN,
          analysis: cleanText(coverage.note),
        };
      });
  }
  return listEntries(matrix).map((row, index) => {
    const linkedIssue = issues[index];
    const evidenceId = row?.evidence_id || row?.evidenceId;
    const factIds = listEntries(row?.fact_ids || row?.factIds);
    const supporting = listEntries(
      row?.supporting ||
        row?.supported_by ||
        row?.evidence_ids ||
        (evidenceId ? [evidenceId] : null) ||
        row?.evidence ||
        row?.items,
    );
    const contradicting = listEntries(
      row?.contradicting || row?.contradicted_by || row?.counter_evidence,
    );
    return {
      id: evidenceId || row?.issue_id || linkedIssue?.id || `evidence-${index}`,
      code: evidenceId || row?.issue_id || linkedIssue?.code || "",
      issue: cleanText(
        row?.issue ||
          row?.title ||
          (factIds.length ? `关联事实：${factIds.join("、")}` : "") ||
          linkedIssue?.text,
      ),
      matrixKind: "LEGACY",
      coverageStatus: "",
      coverageLabel: "",
      links: [],
      supporting,
      contradicting,
      missing:
        row?.assessment_type === "EVIDENCE_GAP" || Boolean(row?.missing_evidence),
      confidence: displayPercent(row?.confidence),
      confidenceValue: Number(row?.confidence),
      analysis: cleanText(
        row?.assessment ||
          row?.neutral_analysis ||
          row?.conclusion ||
          row?.note ||
          row?.status,
      ),
    };
  });
}

function adjudicationBody(draft) {
  if (draft?.draft && typeof draft.draft === "object" && !Array.isArray(draft.draft)) {
    return draft.draft;
  }
  return draft && typeof draft === "object" ? draft : {};
}

function policyEntries(draft) {
  const body = adjudicationBody(draft);
  const policy = body.rule_applications || body.policy_application;
  if (!policy) return [];
  if (!Array.isArray(policy) && typeof policy === "object") {
    return Object.entries(policy).map(([title, detail], index) => ({
      id: `policy-${index}`,
      title: cleanText(title),
      detail: cleanText(displayValue(detail)),
      code: "",
      version: "",
      applicable: null,
      factIds: [],
      conditionsMet: [],
      conditionsUnmet: [],
      resultingEffect: "",
    }));
  }
  return listEntries(policy)
    .map((item, index) => {
      const code = firstText(item?.rule_code, item?.rule_id, item?.id);
      const ruleName = firstText(
        item?.rule_name,
        item?.rule,
        item?.title,
        item?.clause,
      );
      const mappedCode = enumLabel(code, ruleCodeLabels);
      const cleanedRuleName = cleanText(ruleName);
      const explicitChineseRuleName = /[\u3400-\u9fff]/u.test(cleanedRuleName)
        ? cleanedRuleName
        : "";
      const localizedRuleName =
        ruleNameLabels[cleanedRuleName.toLowerCase()] || "";
      const title =
        localizedRuleName ||
        explicitChineseRuleName ||
        (mappedCode && mappedCode !== code ? mappedCode : "") ||
        mapReviewTokens(cleanedRuleName) ||
        (code ? enumLabel(code, ruleCodeLabels) : "");
      return {
        id: code || `policy-${index}`,
        code,
        version: item?.rule_version ?? item?.version ?? "",
        title,
        applicable:
          typeof item?.applicable === "boolean" ? item.applicable : null,
        detail: cleanText(
          item?.rationale ||
            item?.application ||
            item?.reason ||
            item?.conclusion ||
            (typeof item === "string" ? item : ""),
        ),
        factIds: listEntries(item?.fact_ids),
        conditionsMet: listEntries(item?.conditions_met).map(cleanText),
        conditionsUnmet: listEntries(item?.conditions_unmet).map(cleanText),
        resultingEffect: cleanText(item?.resulting_effect),
      };
    })
    .filter((item) => item.title || item.detail);
}

function normalizeReviewPacket(value) {
  return normalizeReviewApiPacket(value);
}

function draftAttention(draft) {
  return listEntries(adjudicationBody(draft).reviewer_attention)
    .map((item) => {
      const text = String(item || "").trim();
      const source = text.match(/^\[(MANDATORY|JURY)\]\s*([\s\S]+)$/u);
      return source
        ? `${enumLabel(source[1])}：${cleanText(source[2])}`
        : cleanText(text);
    })
    .filter(Boolean);
}

function draftDecisionCode(draft) {
  const body = adjudicationBody(draft);
  return (
    body?.decision_action ||
    body?.recommended_decision ||
    body?.recommendedDecision ||
    body?.recommended_outcome ||
    body?.recommendedOutcome ||
    body?.conclusion ||
    body?.decision ||
    ""
  );
}

function draftDecision(draft) {
  const code = draftDecisionCode(draft);
  return code
    ? enumLabel(code, { ...decisionActionLabels, ...outcomeLabels })
    : "草案未提供执行建议";
}

function draftDecisionMeaning(draft) {
  const code = String(draftDecisionCode(draft) || "").trim().toUpperCase();
  return decisionActionByCode.get(code)?.meaning || "";
}

function draftReasoning(draft) {
  const body = adjudicationBody(draft);
  return cleanText(
    body?.decision_reasoning ||
      body?.draft_text ||
      body?.draftText ||
      body?.reasoning_summary ||
      body?.reasoningSummary ||
      body?.reasoning ||
      body?.reason,
  );
}

function findingEntries(draft, issues = []) {
  const issuesByFactId = new Map(
    issues.filter((issue) => issue?.code).map((issue) => [issue.code, issue]),
  );
  return listEntries(adjudicationBody(draft).fact_findings)
    .map((finding, index) => {
      const factId = firstText(finding?.fact_id, finding?.issue_id, finding?.id);
      const rawFinding = firstText(
        finding?.finding,
        finding?.conclusion,
        finding?.text,
      );
      return {
        id: factId || `finding-${index}`,
        factId,
        title:
          issuesByFactId.get(factId)?.text ||
          (factId ? enumLabel(factId) : `事实认定 ${index + 1}`),
        finding: rawFinding
          ? enumLabel(rawFinding, factFindingLabels)
          : "",
        confidence: displayPercent(finding?.confidence),
        evidenceIds: listEntries(finding?.evidence_ids),
        evidenceGap: cleanText(finding?.evidence_gap),
      };
    })
    .filter((finding) => finding.finding || finding.evidenceGap);
}

function remedyOrderEntries(draft, issues = []) {
  const issuesByFactId = new Map(
    issues.filter((issue) => issue?.code).map((issue) => [issue.code, issue.text]),
  );
  return listEntries(adjudicationBody(draft).remedy_orders)
    .map((order, index) => ({
      id: order?.id || `${order?.remedy_type || "remedy"}-${index}`,
      type: firstText(order?.remedy_type, order?.type),
      title: enumLabel(
        firstText(order?.remedy_type, order?.type),
        remedyTypeLabels,
      ),
      text: cleanText(order?.order_text || order?.text || order?.description),
      facts: listEntries(order?.fact_ids).map((factId) => ({
        id: factId,
        label: issuesByFactId.get(factId) || enumLabel(factId),
      })),
      conditions: listEntries(order?.conditions).map(cleanText).filter(Boolean),
    }))
    .filter((order) => order.title || order.text);
}

function reviewResponseTopic(reference, source) {
  if (source === "JURY_FINDING") {
    const dimension = String(reference || "").replace(/^JURY_FINDING_/u, "");
    return reviewTopicLabels[dimension] || "陪审意见";
  }
  const sequence = String(reference || "").match(/(\d+)$/u)?.[1];
  if (source === "MANDATORY_REVISION") {
    return sequence ? `强制修订 ${Number(sequence)}` : "陪审强制修订";
  }
  return sequence ? `复核重点 ${Number(sequence)}` : "法官 V1 复核重点";
}

function reviewResponseEntries(draft, reviewSourceItems = []) {
  const sourceItemsByReference = new Map(
    listEntries(reviewSourceItems)
      .filter((item) => item?.review_item_ref)
      .map((item) => [String(item.review_item_ref), item]),
  );
  const sourcePriority = {
    JURY_FINDING: 0,
    MANDATORY_REVISION: 1,
    V1_REVIEW_FOCUS: 2,
  };
  return listEntries(draft?.review_responses)
    .map((response, index) => {
      const id = response?.review_item_ref || `review-response-${index}`;
      const reviewSource = String(response?.review_source || "").trim().toUpperCase();
      const sourceItem = sourceItemsByReference.get(String(id));
      const isJury = reviewSource === "JURY_FINDING" || reviewSource === "MANDATORY_REVISION";
      return {
        id,
        originalIndex: index,
        sortPriority: sourcePriority[reviewSource] ?? 3,
        reviewSource,
        source: enumLabel(reviewSource, reviewSourceLabels),
        topic: reviewResponseTopic(id, reviewSource),
        opinionLabel: isJury ? "陪审意见" : "法官 V1 复核重点",
        sourceKind: reviewSource === "MANDATORY_REVISION" ? "强制修订" : "",
        reviewItemText: cleanText(sourceItem?.review_item_text),
        disposition: enumLabel(
          response?.disposition,
          reviewDispositionLabels,
        ),
        response: cleanText(response?.response),
        affectedFields: listEntries(response?.affected_fields)
          .map((field) => enumLabel(field, affectedFieldLabels))
          .filter(Boolean),
      };
    })
    .filter((response) => response.response)
    .sort(
      (left, right) =>
        left.sortPriority - right.sortPriority || left.originalIndex - right.originalIndex,
    );
}

function parameterEntries(parameters) {
  if (!parameters || typeof parameters !== "object") return [];
  return Object.entries(parameters).map(([key, value]) => ({
    key,
    label: fieldLabel(key),
    value: displayValue(value),
  }));
}

function remedyActions(remedy) {
  if (!remedy) return [];
  return listEntries(remedy.actions || remedy.action || remedy).map(
    (action, index) => {
      const code =
        action?.action_type ||
        action?.actionType ||
        action?.type ||
        action?.name ||
        `ACTION_${index + 1}`;
      return {
        id: action?.id || action?.idempotency_key || `${code}-${index}`,
        code,
        title: enumLabel(code, actionLabels),
        amount: displayAmount(action?.amount ?? action?.parameters?.amount),
        target: action?.target || action?.parameters?.target || "",
        deadline: action?.deadline || action?.due_at || action?.dueAt || "",
        risk: action?.risk_level || action?.riskLevel || "",
        requiresApproval:
          action?.requires_approval ?? action?.requiresApproval ?? false,
        preconditions: listEntries(action?.preconditions),
        parameters: parameterEntries(action?.parameters),
        note: cleanText(action?.note || action?.description),
      };
    },
  );
}

function notificationEntries(remedy) {
  return listEntries(remedy?.notifications).map((code) => ({
    code,
    label: enumLabel(code, notificationLabels),
  }));
}

function normalizedJson(value) {
  if (Array.isArray(value)) return value.map(normalizedJson);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, normalizedJson(value[key])]),
    );
  }
  return value;
}

const caseTitle = computed(() =>
  mapReviewTokens(
    packet.value?.case_summary?.title ||
      packet.value?.claims?.case_story?.title ||
      packet.value?.case_id ||
      "",
  ),
);
const caseDescription = computed(() =>
  firstText(
    packet.value?.case_summary?.description,
    packet.value?.claims?.case_overview?.neutral_summary,
    packet.value?.claims?.case_story?.one_sentence_summary,
    packet.value?.claims?.claim_resolution?.request_reason,
  ),
);
const caseRoute = computed(() =>
  enumLabel(packet.value?.case_summary?.route_type, routeLabels),
);
const claims = computed(() => claimEntries(packet.value?.claims));
const references = computed(() => referenceEntries(packet.value?.claims));
const resolution = computed(() => {
  const claimResolution = packet.value?.claims?.claim_resolution || {};
  const requestedResolution = packet.value?.claims?.requested_resolution || {};
  const canonicalClaim = packet.value?.claims?.claims?.initiator_claim || {};
  const code =
    canonicalClaim.requested_resolution ||
    claimResolution.requested_resolution ||
    requestedResolution.requested_outcome;
  return {
    code,
    label: enumLabel(code, outcomeLabels),
    text: firstText(
      canonicalClaim.position_summary,
      canonicalClaim.reason_summary,
      claimResolution.normalized_statement,
      requestedResolution.expected_resolution_text,
      claimResolution.request_reason,
    ),
    amount: displayAmount(
      canonicalClaim.requested_amount ?? claimResolution.requested_amount,
    ),
    items: cleanText(
      canonicalClaim.requested_items || claimResolution.requested_items,
    ),
  };
});
const coreConflict = computed(() =>
  firstText(
    packet.value?.claims?.case_overview?.core_conflict,
    packet.value?.claims?.dispute_core_state?.core_conflict,
    packet.value?.claims?.dispute_focus?.core_issue,
  ),
);
const factsToVerify = computed(() =>
  (Array.isArray(packet.value?.claims?.fact_rows)
    ? packet.value.claims.fact_rows
        .filter((fact) => fact?.requires_resolution && fact?.fact_target)
        .map((fact) => fact.fact_target)
    : listEntries(
        packet.value?.claims?.dispute_core_state?.next_verification_focus ||
          packet.value?.claims?.dispute_focus?.facts_to_verify,
      ))
    .map((value) => mapReviewTokens(cleanText(value)))
    .filter(Boolean),
);
const issues = computed(() => issueEntries(packet.value));
function factReferenceLabel(factId) {
  return (
    issues.value.find((issue) => issue.code === factId)?.text ||
    enumLabel(factId)
  );
}
const evidenceCatalogItems = computed(() =>
  listEntries(
    evidenceCatalog.value?.items ||
      packet.value?.evidence_items ||
      packet.value?.evidence_catalog?.items ||
      packet.value?.dossier?.evidence_items,
  ),
);
const evidenceNamesById = computed(
  () =>
    new Map(
      evidenceCatalogItems.value
        .map((item) => [evidenceReferenceId(item), evidenceItemName(item)])
        .filter(([id, name]) => id && name),
    ),
);
const evidence = computed(() =>
  evidenceEntries(packet.value?.evidence_matrix, issues.value),
);
const policies = computed(() => policyEntries(packet.value?.draft));
const findings = computed(() => findingEntries(packet.value?.draft, issues.value));
const remedyOrders = computed(() =>
  remedyOrderEntries(packet.value?.draft, issues.value),
);
const reviewResponses = computed(() =>
  reviewResponseEntries(packet.value?.draft, packet.value?.review_source_items),
);
const adjudicationReasoning = computed(() => draftReasoning(packet.value?.draft));
const adjudicationPublicText = computed(() => {
  const body = adjudicationBody(packet.value?.draft);
  if (
    Array.isArray(body.fact_findings) &&
    Array.isArray(body.rule_applications) &&
    body.decision_reasoning
  ) {
    return "";
  }
  const text = firstText(
    packet.value?.draft?.public_text,
    packet.value?.draft?.public_message,
  );
  return text && text !== adjudicationReasoning.value ? cleanText(text) : "";
});
const adjudicationAttention = computed(() => draftAttention(packet.value?.draft));
const adjudicationDecisionMeaning = computed(() =>
  draftDecisionMeaning(packet.value?.draft),
);
const actions = computed(() => remedyActions(packet.value?.remedy));
const notifications = computed(() => notificationEntries(packet.value?.remedy));
const reviewRisks = computed(() => {
  const risks = listEntries(packet.value?.risk_flags).map(reviewRiskEntry);
  const safetyRisk = packet.value?.claims?.risk_assessment?.safety_risk;
  if (safetyRisk === "HIGH" || safetyRisk === "CRITICAL") {
    risks.push({
      code: "SAFETY_RISK_HIGH",
      label: riskFlagLabels.SAFETY_RISK_HIGH,
    });
  }
  if (evidence.value.some((row) => row.missing)) {
    risks.push({
      code: "EVIDENCE_INSUFFICIENT",
      label: riskFlagLabels.EVIDENCE_INSUFFICIENT,
    });
  }
  return Array.from(new Map(risks.map((item) => [item.code, item])).values());
});
const reviewMetrics = computed(() => ({
  issues: issues.value.length,
  evidence: evidence.value.length,
  missingEvidence: evidence.value.filter((row) => row.missing).length,
  coveredEvidence: evidence.value.filter((row) => !row.missing).length,
  boundEvidence: evidence.value.filter(
    (row) =>
      row.links?.length || row.supporting?.length || row.contradicting?.length,
  ).length,
  actions: actions.value.length,
  findings: findings.value.length,
  rules: policies.value.length,
  responses: reviewResponses.value.length,
}));
function versionEntry(label, value) {
  return value === null || value === undefined || value === ""
    ? null
    : [label, `v${value}`];
}

function textEntry(label, value) {
  return String(value || "").trim() ? [label, value] : null;
}

const auditVersionGroups = computed(() =>
  [
    {
      label: "案件材料",
      entries: [
        versionEntry("审核包", packet.value?.packet_version),
        versionEntry("案件快照", packet.value?.case_version),
        versionEntry("证据卷", packet.value?.dossier_version),
        versionEntry("争点", packet.value?.issue_version),
      ].filter(Boolean),
    },
    {
      label: "裁决链路",
      entries: [
        versionEntry("裁决草案", packet.value?.adjudication_draft_version),
        versionEntry("评议报告", packet.value?.deliberation_report_version),
        versionEntry("执行方案", packet.value?.remedy_plan_version),
      ].filter(Boolean),
    },
    {
      label: "运行基线",
      entries: [
        textEntry("规则集", packet.value?.ruleset_version),
        textEntry("提示词", packet.value?.prompt_version),
        textEntry("技能", packet.value?.skill_version),
        textEntry("角色配置", packet.value?.profile_version),
      ].filter(Boolean),
    },
  ].filter((group) => group.entries.length),
);
const auditEntryCount = computed(() =>
  auditVersionGroups.value.reduce((total, group) => total + group.entries.length, 0),
);
const pendingDecisionLabel = computed(
  () => decisionLabels[pendingDecision.value] || pendingDecision.value,
);
const frozenDecisionActionCode = computed(() =>
  String(
    packet.value?.remedy?.decision_action ||
      packet.value?.draft?.decision_action ||
      packet.value?.draft?.draft?.decision_action ||
      "",
  )
    .trim()
    .toUpperCase(),
);
const selectedDecisionAction = computed(
  () => decisionActionByCode.get(selectedDecisionActionCode.value) || null,
);
const reviewerOpinionTitle = computed(() => {
  if (reviewerOpinionDecision.value === "APPROVE") return "批准 AI 建议";
  if (reviewerOpinionDecision.value === "MODIFY_AND_APPROVE") {
    return selectedDecisionAction.value?.label || "待选择修改决定";
  }
  return decisionLabels[reviewerOpinionDecision.value] || "尚未形成";
});
const reviewerOpinionDescription = computed(() => {
  if (reviewerOpinionDecision.value === "APPROVE") {
    return "采纳当前 AI 建议及其冻结方案，待确认后提交执行。";
  }
  if (reviewerOpinionDecision.value === "MODIFY_AND_APPROVE") {
    return selectedDecisionAction.value?.meaning || "请先选择一个最终决定候选。";
  }
  const option = exceptionDecisions.find(
    (item) => item.value === reviewerOpinionDecision.value,
  );
  return option?.description || "批准或修改后，审核员意见会显示在这里。";
});
const reviewerOpinionState = computed(() => {
  if (decisionResult.value) return "submitted";
  return reviewerOpinionDecision.value ? "ready" : "empty";
});
const reviewerOpinionReady = computed(() => {
  if (!reviewerOpinionDecision.value) return false;
  if (reviewerOpinionDecision.value !== "MODIFY_AND_APPROVE") return true;
  return Boolean(selectedDecisionAction.value && approvedPlanDraft.value);
});
const decisionReadonlyMessage = computed(() => {
  if (historyMode.value) {
    return "这是已封存的历史终审记录，所有批准、修改和人工接管操作均已锁定。";
  }
  if (packetExpired.value) {
    return "冻结审核包已超过有效期，决定与解释官已锁定，请返回队列重新确认任务。";
  }
  if (!taskStateKnown.value) return "正在确认审核任务状态。";
  if (!taskOpen.value) {
    return taskLookupError.value
      ? "无法确认审核任务状态，决定与解释官已保守锁定。"
      : "审核任务已离开可办理队列，当前页面仅保留冻结材料。";
  }
  if ((props.viewerRole || actor.role) !== "PLATFORM_REVIEWER") {
    return "当前角色只能查看获授权的冻结材料，不能提交终审决定。";
  }
  if (!hasReviewerWriteCapability.value) {
    return "该任务已分配给另一名平台审核员，当前页面保持只读。";
  }
  if (packet.value?.status !== "FROZEN") {
    return "冻结审核包生成前仅可只读旁观，系统不会展示任何批准按钮。";
  }
  return "冻结审核包生成前仅可只读旁观，系统不会展示任何批准按钮。";
});
const decisionResultMessage = computed(() => {
  const decision = decisionResult.value?.decision || submittedDecision.value;
  if (decision === "ESCALATE_MANUAL") {
    return "自动处理链路已停止，案件进入人工接管。";
  }
  return "冻结方案已交执行助手，等待后续执行结果。";
});
const caseBriefingMessage = computed(() => {
  const parts = [`这是“${caseTitle.value}”案件。`];
  if (resolution.value.label) {
    parts.push(
      `申请处理为${resolution.value.label}${resolution.value.amount ? `，争议金额为${resolution.value.amount}` : ""}。`,
    );
  }
  parts.push(`冻结包整理出 ${reviewMetrics.value.issues} 个核心争点。`);
  if (draftDecisionCode(packet.value?.draft)) {
    parts.push(`当前草案建议为“${draftDecision(packet.value?.draft)}”。`);
  }
  if (reviewRisks.value.length) {
    const conciseRisks = Array.from(
      new Set(
        reviewRisks.value
          .map((risk) =>
            cleanText(risk.label)
              .replace(
                /^(?:需重点审查|需要重点审查|需重点复核|需要重点复核|需核实|需要核实|需确认|需要确认|需审查|需要审查|需评估|需要评估)\s*/u,
                "",
              )
              .replace(/[。；、]+$/u, ""),
          )
          .filter(Boolean),
      ),
    );
    parts.push(
      `终审重点：\n${conciseRisks.map((risk, index) => `${index + 1}. ${risk}`).join("\n")}`,
    );
  }
  parts.push("你可以继续问我事实、证据、规则或执行方案，我只解释冻结材料，不代替你作出终审决定。");
  return parts.join("");
});
const copilotConversationMessages = computed(() => [
  {
    id: "review-case-briefing",
    sequence_no: 0,
    sender_role: "REVIEW_COPILOT",
    message_type: "AGENT_MESSAGE",
    message_text: caseBriefingMessage.value,
  },
  ...copilotMessages.value.map((message, index) => ({
    id: message.id,
    sequence_no: index + 1,
    sender_role: message.sender_role,
    message_type:
      message.sender_role === "PLATFORM_REVIEWER"
        ? "PARTY_TEXT"
        : "AGENT_MESSAGE",
    message_text: mapReviewTokens(message.text),
    agent_run_id: message.agent_run_id,
  })),
]);

const clockTimer = setInterval(() => {
  clockNow.value = Date.now();
}, 1000);

function applyTaskAccess(task) {
  taskOpen.value = Boolean(
    task && ACTIVE_REVIEW_STATUSES.includes(task.status),
  );
  taskStatus.value = task?.status || "";
  taskAssignedReviewerId.value = task?.assigned_reviewer_id || "";
}

function shouldStartTask(task) {
  const reviewerRole = props.viewerRole || actor.role;
  return (
    !historyMode.value &&
    reviewerRole === "PLATFORM_REVIEWER" &&
    Boolean(actor.id) &&
    task?.status === "PENDING" &&
    !task.assigned_reviewer_id
  );
}

async function loadTaskAccess() {
  taskLookupError.value = "";
  try {
    const statuses = ACTIVE_REVIEW_STATUSES;
    const groups = await Promise.all(
      statuses.map((status) => reviewApi.list(actor, status)),
    );
    let task = groups.flat().find((item) => item.id === reviewId.value);
    if (shouldStartTask(task)) {
      task = normalizeReviewTask(await reviewApi.start(actor, reviewId.value));
    }
    applyTaskAccess(task);
  } catch (failure) {
    applyTaskAccess(null);
    taskLookupError.value = failure?.message || "审核任务状态查询失败";
  } finally {
    taskStateKnown.value = true;
  }
}

async function loadEvidenceCatalog() {
  const caseId = packet.value?.case_id;
  if (!caseId || evidenceCatalog.value) return;
  try {
    evidenceCatalog.value = await evidenceApi.catalog(actor, caseId);
  } catch {
    evidenceCatalog.value = null;
  }
}

async function load() {
  if (packet.value === null) {
    loading.value = true;
    try {
      packet.value = normalizeReviewPacket(
        await reviewApi.packet(actor, reviewId.value),
      );
    } catch (failure) {
      error.value = failure?.message || "审核包加载失败，请稍后重试。";
      agentState.value = "ERROR";
      loading.value = false;
      return;
    }
  }
  if (props.initialPacket) {
    loading.value = false;
    return;
  }
  try {
    await Promise.all([loadTaskAccess(), loadEvidenceCatalog()]);
    await resumeCopilotRuns();
  } catch (failure) {
    copilotStreamError.value =
      failure?.message || "无法恢复审核解释官的生成任务。";
    agentState.value = "ERROR";
  } finally {
    loading.value = false;
  }
}

function appendCopilotAnswer(result, run) {
  const answer = String(result?.answer || "").trim();
  if (!answer) return;
  if (
    copilotMessages.value.some(
      (message) => message.agent_run_id === run.runId,
    )
  ) {
    return;
  }
  copilotMessages.value.push({
    id: `answer-${run.runId}`,
    sender_role: "REVIEW_COPILOT",
    agent_run_id: run.runId,
    text: answer,
  });
}

async function consumeCopilotRun(rawDescriptor) {
  const descriptor = extractAgentRunDescriptor(rawDescriptor);
  if (!descriptor) throw new Error("服务未返回有效的审核解释官流任务");
  return consumeAgentRun({
    actor,
    caseId: packet.value?.case_id || "",
    roomType: "REVIEW",
    descriptor,
    agentLabel: "审核解释官",
    senderRole: "REVIEW_COPILOT",
    onFinal: (result, run) => appendCopilotAnswer(result, run),
    onError: (streamFailure) => {
      copilotStreamError.value = streamFailure.message;
      agentState.value = "ERROR";
    },
  });
}

async function resumeCopilotRuns() {
  if (!canUseCopilot.value) return;
  const activeRuns = await reviewApi.activeCopilotRuns(actor, reviewId.value);
  await Promise.all(activeRuns.map((run) => consumeCopilotRun(run)));
}

async function submitCopilotQuestion(command = null) {
  const question = String(command?.text ?? copilotQuestion.value).trim();
  if (!question || !canUseCopilot.value || copilotBusy.value) return;
  copilotStreamError.value = "";
  copilotSubmitting.value = true;
  agentState.value = "THINKING";
  copilotMessages.value.push({
    id: `question-${Date.now()}`,
    sender_role: "PLATFORM_REVIEWER",
    text: question,
  });
  copilotQuestion.value = "";
  try {
    const descriptor = await reviewApi.queryCopilot(
      actor,
      reviewId.value,
      question,
    );
    if (historyMode.value) return;
    await consumeCopilotRun(descriptor);
    agentState.value = "LISTENING";
  } catch (failure) {
    copilotStreamError.value =
      failure?.message || "审核解释官生成失败，请稍后重试。";
    agentState.value = "ERROR";
  } finally {
    copilotSubmitting.value = false;
  }
}

function requestDecision(decision) {
  if (!canDecide.value) return;
  error.value = "";
  decisionSubmitError.value = "";
  pendingDecision.value = "";
  if (decision === "MODIFY_AND_APPROVE") {
    actionSelectorOpen.value = true;
    return;
  }
  reviewerOpinionDecision.value = decision;
  selectedDecisionActionCode.value = "";
  approvedPlanDraft.value = null;
}

function closeDecisionActionSelector() {
  actionSelectorOpen.value = false;
}

function selectDecisionAction(actionCode) {
  error.value = "";
  const catalogItem = decisionActionByCode.get(actionCode);
  const frozenPlan = normalizedJson(packet.value?.remedy || {});
  if (!catalogItem) return;
  if (!String(frozenPlan.id || "").trim() || !Array.isArray(frozenPlan.actions)) {
    error.value = "当前冻结执行方案缺少方案 ID 或 actions，无法提交修改。";
    return;
  }
  if (frozenDecisionActionCode.value === catalogItem.code) {
    error.value = "所选决定与当前 AI 建议一致，如无其他修改请直接选择批准 AI 建议。";
    return;
  }
  approvedPlanDraft.value = {
    ...frozenPlan,
    decision_action: catalogItem.code,
  };
  selectedDecisionActionCode.value = catalogItem.code;
  reviewerOpinionDecision.value = "MODIFY_AND_APPROVE";
  pendingDecision.value = "";
  closeDecisionActionSelector();
}

function selectManualEscalation() {
  requestDecision("ESCALATE_MANUAL");
  closeDecisionActionSelector();
}

function prepareReviewerOpinionSubmission() {
  if (!canDecide.value || !reviewerOpinionDecision.value) return;
  error.value = "";
  decisionSubmitError.value = "";
  if (!reason.value.trim()) {
    error.value = "请先填写审核理由";
    return;
  }
  if (
    reviewerOpinionDecision.value === "MODIFY_AND_APPROVE" &&
    (!selectedDecisionAction.value || !approvedPlanDraft.value)
  ) {
    actionSelectorOpen.value = true;
    return;
  }
  pendingDecision.value = reviewerOpinionDecision.value;
}

async function submitDecision() {
  if (
    historyMode.value ||
    !canDecide.value ||
    submitting.value ||
    !pendingDecision.value
  ) {
    return;
  }
  if (
    pendingDecision.value === "MODIFY_AND_APPROVE" &&
    !approvedPlanDraft.value
  ) {
    pendingDecision.value = "";
    error.value = "请先选择一个修改后的最终决定";
    return;
  }
  submitting.value = true;
  error.value = "";
  decisionSubmitError.value = "";
  agentState.value = "THINKING";
  const command = {
    decision: pendingDecision.value,
    reason: reason.value.trim(),
    approved_plan:
      pendingDecision.value === "MODIFY_AND_APPROVE"
        ? approvedPlanDraft.value
        : null,
    confirmed: true,
  };
  submittedDecision.value = pendingDecision.value;
  try {
    const result = props.decideAction
      ? await props.decideAction(command)
      : await reviewApi.decide(actor, reviewId.value, command);
    decisionResult.value = result;
    pendingDecision.value = "";
    agentState.value = "HANDOFF";
    const outcomeCaseId = String(
      result?.case_id || result?.caseId || packet.value?.case_id || "",
    ).trim();
    if (!outcomeCaseId) {
      decisionSubmitError.value =
        "终审决定已提交，但返回结果缺少案件编号，暂时无法打开执行结果页。";
      return;
    }
    await router.push({
      name: "dispute-outcome",
      params: { caseId: outcomeCaseId },
    });
  } catch (failure) {
    if (decisionResult.value) {
      decisionSubmitError.value =
        failure?.message || "终审决定已提交，但执行结果页跳转失败，请从案件列表重新进入。";
    } else {
      decisionSubmitError.value =
        failure?.message || "终审决定提交失败，请稍后重试。";
      agentState.value = "ERROR";
    }
  } finally {
    submitting.value = false;
  }
}

watch(historyMode, (historical) => {
  if (!historical) return;
  pendingDecision.value = "";
  actionSelectorOpen.value = false;
  reviewerOpinionDecision.value = "";
  selectedDecisionActionCode.value = "";
  approvedPlanDraft.value = null;
  copilotQuestion.value = "";
  copilotSubmitting.value = false;
  clearAgentStreams(copilotContext.value);
});
watch(effectiveServerNow, (value) => {
  clockAnchorServer.value = Date.parse(value);
  clockAnchorLocal.value = Date.now();
  clockNow.value = Date.now();
});
watch(pendingDecision, async (decision, previousDecision) => {
  if (decision) {
    decisionTrigger = document.activeElement;
    await nextTick();
    confirmButton.value?.focus();
    return;
  }
  if (previousDecision && decisionTrigger instanceof HTMLElement) {
    await nextTick();
    decisionTrigger.focus();
  }
});
watch(actionSelectorOpen, async (open, wasOpen) => {
  if (open) {
    actionSelectorTrigger = document.activeElement;
    await nextTick();
    actionSelectorCloseButton.value?.focus();
    return;
  }
  if (wasOpen && actionSelectorTrigger instanceof HTMLElement) {
    await nextTick();
    actionSelectorTrigger.focus({ preventScroll: true });
  }
});

function trapDecisionFocus(event) {
  const focusable = [confirmCancelButton.value, confirmButton.value].filter(
    (element) => element && !element.disabled,
  );
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function trapActionSelectorFocus(event) {
  const focusable = Array.from(
    actionSelectorDialog.value?.querySelectorAll("button:not(:disabled)") || [],
  );
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function syncReviewOperationScrollIndicator() {
  const scroller = reviewOperationScroll.value;
  const track = reviewOperationScrollTrack.value;
  if (!scroller || !track) {
    reviewOperationScrollVisible.value = false;
    reviewOperationScrollOffset.value = 0;
    return;
  }
  const maximumScroll = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  reviewOperationScrollVisible.value = maximumScroll > 1;
  const travel = Math.max(
    0,
    track.clientHeight - REVIEW_OPERATION_SCROLL_THUMB_HEIGHT,
  );
  reviewOperationScrollOffset.value = maximumScroll
    ? (scroller.scrollTop / maximumScroll) * travel
    : 0;
}

function bindReviewOperationScrollIndicator() {
  reviewOperationResizeObserver?.disconnect();
  reviewOperationMutationObserver?.disconnect();
  reviewOperationResizeObserver = null;
  reviewOperationMutationObserver = null;
  nextTick(() => {
    const scroller = reviewOperationScroll.value;
    if (typeof ResizeObserver !== "undefined" && scroller) {
      reviewOperationResizeObserver = new ResizeObserver(
        syncReviewOperationScrollIndicator,
      );
      reviewOperationResizeObserver.observe(scroller);
      if (scroller.firstElementChild) {
        reviewOperationResizeObserver.observe(scroller.firstElementChild);
      }
    }
    if (typeof MutationObserver !== "undefined" && scroller) {
      reviewOperationMutationObserver = new MutationObserver(
        syncReviewOperationScrollIndicator,
      );
      reviewOperationMutationObserver.observe(scroller, {
        childList: true,
        subtree: true,
        characterData: true,
      });
    }
    syncReviewOperationScrollIndicator();
  });
}

watch(
  [packet, reviewerOpinionDecision, selectedDecisionActionCode, decisionResult],
  bindReviewOperationScrollIndicator,
  { flush: "post" },
);

onMounted(() => {
  load();
  window.addEventListener("resize", syncReviewOperationScrollIndicator);
  bindReviewOperationScrollIndicator();
});
onBeforeUnmount(() => {
  clearInterval(clockTimer);
  clearAgentStreams(copilotContext.value);
  window.removeEventListener("resize", syncReviewOperationScrollIndicator);
  reviewOperationResizeObserver?.disconnect();
  reviewOperationMutationObserver?.disconnect();
});
</script>

<template>
  <RoomShell
    class="review-workbench"
    eyebrow="平台人工终审"
    title="平台终审室"
    subtitle="平台最终确认"
    subtitle-description="审核员可以批准 AI 建议、修改最终决定或升级人工接管。"
    :case-id="packet?.case_id || String(reviewId || 'REVIEW')"
    :show-case-id="false"
    :show-connection="false"
    :show-boundary="false"
    :history-mode="historyMode"
    history-description="终审记录已经封存，解释官提问和所有终审决定均已锁定；你仍可查看当时的冻结材料。"
    :data-review-history-banner="historyMode ? '' : undefined"
  >
    <template #clock>
      <div v-if="packet" class="review-workbench__timing">
        <div class="review-workbench__packet-version">
          <span>冻结审核包</span>
          <strong>冻结审核包 v{{ packet.packet_version }}</strong>
          <time
            v-if="packet.frozen_at"
            data-frozen-time
            :datetime="packet.frozen_at"
            :title="packet.frozen_at"
          >
            {{ displayDateTime(packet.frozen_at) }}
          </time>
        </div>
        <div class="review-workbench__timer">
          <PhaseCountdown
            label="剩余审核时效"
            :deadline-at="packetExpiry"
            :server-now="effectiveServerNow"
          />
        </div>
      </div>
    </template>

    <template #agent>
      <DigitalHuman
        data-review-agent-card
        :state="digitalHumanState"
        name="小译"
        role="审核解释官"
        message="我只依据当前冻结审核包转述事实、证据、规则和草案。批准、修改或升级人工接管必须由你亲自确认。"
      />
    </template>

    <section v-if="loading" class="review-loading" aria-live="polite">
      <span />
      <div>
        <strong>正在读取冻结审核包</strong>
        <p>正在校验案件、证据、规则与执行方案版本。</p>
      </div>
    </section>

    <section v-else-if="!packet && error" class="review-load-error" role="alert">
      <strong>审核包暂时无法打开</strong>
      <p>{{ error }}</p>
      <button type="button" @click="error = ''; load()">重新加载</button>
    </section>

    <div v-else-if="packet" class="review-triple-layout">
      <div class="review-room-layout">
        <section
          class="review-explain-room"
          data-review-chat-column
          data-review-copilot
        >
          <header class="review-explain-room__header">
            <span class="review-explain-room__status" aria-hidden="true">✦</span>
            <div>
              <span>案件解释</span>
              <h2>和小译核对案件</h2>
              <p>基于当前冻结审核包解释事实、证据、规则和执行方案，不生成最终决定。</p>
            </div>
            <small :data-agent-state="copilotBusy ? 'working' : 'ready'">
              {{ copilotBusy ? "小译正在整理回答" : "解释官已连接" }}
            </small>
          </header>

          <div class="review-explain-room__conversation">
            <ConversationStream
              :messages="copilotConversationMessages"
              :streaming-runs="copilotRuns"
              :quick-prompts="explanationPrompts"
              :disabled="!canUseCopilot || copilotBusy"
              :composer-visible="!historyMode"
              :disabled-reason="decisionReadonlyMessage"
              agent-label="审核解释官"
              empty-text="冻结审核包已就绪，可以开始核对案件。"
              placeholder="向小译询问事实、证据、规则或执行方案…"
              submit-label="发送问题"
              composer-hint="解释仅基于当前冻结包，不会代替审核员提交决定"
              @submit="submitCopilotQuestion"
            />
          </div>
        </section>

        <aside
          class="review-operation-room"
          data-review-operation-column
          aria-label="终审表单与操作"
        >
          <header class="review-operation-room__decision-heading" data-review-decision-heading>
            <div>
              <span>人工决定</span>
              <h2>审核员判决</h2>
            </div>
            <i>仅审核员</i>
          </header>

          <section
            class="review-case-strip"
            data-review-case-strip
            aria-label="当前终审案件"
          >
            <div class="review-case-strip__identity" data-review-case-identity>
              <span>当前终审案件</span>
              <strong data-review-case-title>{{ caseTitle }}</strong>
            </div>
            <div class="review-case-strip__statuses" data-review-case-statuses>
              <div class="review-workbench__badges">
                <span class="status-badge" data-packet-status>
                  {{ packetStatusLabel(packet.status) }}
                </span>
                <span v-if="taskStatus" class="task-badge">
                  {{ enumLabel(taskStatus, taskStatusLabels) }}
                </span>
                <span class="risk-badge" :data-risk="packet.case_summary?.risk_level">
                  {{ riskLabel(packet.case_summary?.risk_level) }}
                </span>
                <span v-if="caseRoute" class="route-badge">{{ caseRoute }}</span>
              </div>
            </div>
          </section>

          <div class="review-operation-room__scroll-shell">
            <div
              ref="reviewOperationScroll"
              class="review-operation-room__scroll"
              @scroll="syncReviewOperationScrollIndicator"
            >
              <section class="decision-panel">
            <div class="decision-panel__positions">
              <section class="decision-position decision-position--ai" data-ai-opinion>
                <header>
                  <span>AI 建议</span>
                </header>
                <strong>{{ draftDecision(packet.draft) }}</strong>
                <p v-if="adjudicationDecisionMeaning">
                  {{ adjudicationDecisionMeaning }}
                </p>
              </section>

              <section
                class="decision-position decision-position--reviewer"
                :data-state="reviewerOpinionState"
                data-reviewer-opinion
              >
                <header>
                  <span>审核员意见</span>
                  <button
                    v-if="canDecide && reviewerOpinionDecision === 'MODIFY_AND_APPROVE' && !pendingDecision"
                    type="button"
                    data-reviewer-opinion-change
                    @click="actionSelectorOpen = true"
                  >
                    更改选择
                  </button>
                </header>
                <strong>{{ reviewerOpinionTitle }}</strong>
                <p>{{ reviewerOpinionDescription }}</p>
              </section>
            </div>

            <section v-if="canDecide" class="decision-dock" data-review-decisions>
              <label class="decision-reason">
                <span>终审理由 <b>必填</b></span>
                <textarea
                  v-model="reason"
                  data-review-reason
                  maxlength="2000"
                  rows="5"
                  placeholder="记录已核验的事实、规则和决定依据"
                />
                <small>{{ reason.length }} / 2000</small>
              </label>

              <div class="decision-actions decision-actions--approval">
                <button
                  v-for="decision in approvalDecisions"
                  :key="decision.value"
                  type="button"
                  :class="{
                    'decision-action--primary': decision.value === 'APPROVE',
                    'is-selected': reviewerOpinionDecision === decision.value,
                  }"
                  :data-decision="decision.value"
                  :aria-pressed="reviewerOpinionDecision === decision.value"
                  @click="requestDecision(decision.value)"
                >
                  <span aria-hidden="true">{{ decision.icon }}</span>
                  <span>
                    <strong>{{ decision.label }}</strong>
                    <small>{{ decision.description }}</small>
                  </span>
                </button>
              </div>

              <button
                type="button"
                class="decision-submit"
                data-reviewer-opinion-submit
                :disabled="!reviewerOpinionReady || submitting"
                @click="prepareReviewerOpinionSubmission"
              >
                <strong>{{ submitting ? "正在提交" : "提交终审决定" }}</strong>
                <small>
                  {{ reviewerOpinionReady ? "提交当前审核意见并进入最终确认" : "请先选择批准、修改或升级人工接管" }}
                </small>
              </button>
            </section>

            <div v-else-if="decisionResult" class="decision-success" role="status">
              <strong>终审决定已提交</strong>
              <p>{{ decisionResultMessage }}</p>
            </div>
            <div v-else class="decision-readonly">
              {{ decisionReadonlyMessage }}
            </div>

              <p v-if="error" class="decision-error" role="alert">{{ error }}</p>
              </section>
            </div>
            <span
              v-show="reviewOperationScrollVisible"
              ref="reviewOperationScrollTrack"
              class="review-operation-room__scroll-indicator"
              aria-hidden="true"
            >
              <i
                :style="{ transform: `translateY(${reviewOperationScrollOffset}px)` }"
              />
            </span>
          </div>
        </aside>
      </div>

      <div class="review-workbench__workspace">
        <section
          class="review-document"
          data-review-material-column
          aria-label="冻结审核材料"
        >
          <nav
            class="review-tabs"
            data-review-material-tabs
            role="tablist"
            aria-label="审核材料分类"
          >
            <button
              v-for="section in reviewSections"
              :id="`review-tab-${section.value}`"
              :key="section.value"
              type="button"
              role="tab"
              :aria-selected="activeSection === section.value"
              :aria-controls="`review-panel-${section.value}`"
              @click="activeSection = section.value"
            >
              {{ section.label }}
            </button>
          </nav>

          <section
            v-show="activeSection === 'overview'"
            id="review-panel-overview"
            class="review-panel"
            role="tabpanel"
            aria-labelledby="review-tab-overview"
          >
            <header class="review-panel__header review-panel__header--compact">
              <div>
                <span>01</span>
                <div>
                  <h2>案件事实与诉求</h2>
                </div>
              </div>
              <dl class="review-panel__metrics">
                <div><dt>争点</dt><dd>{{ reviewMetrics.issues }}</dd></div>
                <div><dt>证据组</dt><dd>{{ reviewMetrics.evidence }}</dd></div>
                <div><dt>缺口</dt><dd>{{ reviewMetrics.missingEvidence }}</dd></div>
              </dl>
            </header>

            <section class="review-subsection review-case-summary" data-case-summary>
              <header>
                <h3>案件摘要</h3>
                <span>冻结快照</span>
              </header>
              <div class="case-summary-card">
                <dl class="case-key-facts">
                  <div v-if="resolution.label">
                    <dt>申请结果</dt>
                    <dd>
                      <strong>{{ resolution.label }}</strong>
                    </dd>
                  </div>
                  <div v-if="resolution.amount">
                    <dt>争议金额</dt>
                    <dd><strong>{{ resolution.amount }}</strong></dd>
                  </div>
                  <div v-if="resolution.items">
                    <dt>争议商品</dt>
                    <dd><EvidenceMappedText :text="resolution.items" /></dd>
                  </div>
                  <div v-if="coreConflict">
                    <dt>核心冲突</dt>
                    <dd><EvidenceMappedText :text="coreConflict" /></dd>
                  </div>
                </dl>

                <div v-if="caseDescription" class="case-narrative">
                  <h4>案情摘要</h4>
                  <p><EvidenceMappedText :text="caseDescription" /></p>
                </div>

                <div v-if="references.length" class="case-references" aria-label="业务单据">
                  <span v-for="reference in references" :key="reference.label">
                    {{ reference.label }} <code>{{ reference.value }}</code>
                  </span>
                </div>
              </div>
            </section>

            <section class="review-subsection" data-claims-card>
              <header>
                <h3>双方主张</h3>
                <span>{{ claims.length }} 方陈述</span>
              </header>
              <div v-if="claims.length" class="claim-list">
                <article v-for="claim in claims" :key="claim.label" class="claim-item">
                  <header>
                    <strong>{{ claim.label }}</strong>
                    <span v-if="claim.attitude">{{ claim.attitude }}</span>
                  </header>
                  <p><EvidenceMappedText :text="claim.text" /></p>
                  <small v-if="claim.detail">
                    <EvidenceMappedText :text="claim.detail" />
                  </small>
                </article>
              </div>
              <p v-else class="empty-state">冻结包未提供结构化双方主张。</p>
            </section>

            <section class="review-subsection" data-issues-card>
              <header>
                <h3>核心争点</h3>
                <span>{{ issues.length }} 项</span>
              </header>
              <ol v-if="issues.length" class="issue-list">
                <li v-for="(issue, index) in issues" :key="issue.id">
                  <span>{{ String(index + 1).padStart(2, "0") }}</span>
                  <div>
                    <header class="issue-item__heading" data-issue-heading>
                      <strong><EvidenceMappedText :text="issue.text" /></strong>
                    </header>
                    <div
                      v-if="issue.finding || issue.confidence"
                      class="issue-finding"
                      :title="'法官认定：' + (issue.finding || '认定结果待明确') + (issue.confidence ? '，可信度 ' + issue.confidence : '')"
                      role="note"
                      aria-label="法官认定"
                      data-judge-finding-tag
                    >
                      <div class="issue-finding__meta">
                        <span>法官认定</span>
                        <small v-if="issue.confidence">可信度 {{ issue.confidence }}</small>
                      </div>
                      <p class="issue-finding__text">
                        <EvidenceMappedText :text="issue.finding || '认定结果待明确'" />
                      </p>
                    </div>
                    <details
                      v-if="issue.positions?.length"
                      class="issue-position-disclosure"
                    >
                      <summary>
                        查看双方陈述
                        <span>{{ issue.positions.length }} 方</span>
                      </summary>
                      <dl class="issue-positions">
                        <div v-for="position in issue.positions" :key="position.role">
                          <dt>{{ position.roleLabel }} · {{ position.stance }}</dt>
                          <dd><EvidenceMappedText :text="position.text" /></dd>
                        </div>
                      </dl>
                    </details>
                  </div>
                </li>
              </ol>
              <p v-else class="empty-state">冻结包未形成结构化争点。</p>
            </section>

            <section v-if="factsToVerify.length" class="review-subsection">
              <header>
                <h3>待核事实</h3>
                <span>{{ factsToVerify.length }} 项</span>
              </header>
              <ul class="focus-list">
                <li v-for="fact in factsToVerify" :key="fact">
                  <EvidenceMappedText :text="fact" />
                </li>
              </ul>
            </section>
          </section>

          <section
            v-show="activeSection === 'evidence'"
            id="review-panel-evidence"
            class="review-panel"
            role="tabpanel"
            aria-labelledby="review-tab-evidence"
          >
            <header class="review-panel__header review-panel__header--compact">
              <div>
                <span>02</span>
                <div>
                  <h2>事实级证据矩阵</h2>
                </div>
              </div>
              <dl class="review-panel__metrics">
                <div><dt>事实</dt><dd>{{ reviewMetrics.evidence }}</dd></div>
                <div><dt>已绑定证据</dt><dd>{{ reviewMetrics.boundEvidence }}</dd></div>
                <div><dt>需复核</dt><dd>{{ reviewMetrics.missingEvidence }}</dd></div>
              </dl>
            </header>

            <section class="review-subsection" data-evidence-matrix>
              <header>
                <h3>证据覆盖</h3>
                <span>{{ evidence.length }} 项事实</span>
              </header>
              <div v-if="evidence.length" class="evidence-matrix">
                <article
                  v-for="row in evidence"
                  :key="row.id"
                  class="evidence-row"
                  :data-missing="row.missing || null"
                >
                  <header>
                    <div>
                      <strong><EvidenceMappedText :text="row.issue" /></strong>
                    </div>
                    <span v-if="row.coverageLabel">{{ row.coverageLabel }}</span>
                    <span v-else-if="row.missing">存在证据缺口</span>
                    <span v-else>已有证据材料</span>
                  </header>

                  <div v-if="row.confidence" class="evidence-confidence">
                    <span>证据置信度</span>
                    <div
                      role="progressbar"
                      aria-label="证据置信度"
                      aria-valuemin="0"
                      aria-valuemax="100"
                      :aria-valuenow="parseInt(row.confidence, 10)"
                    >
                      <i :style="{ width: row.confidence }" />
                    </div>
                    <strong>{{ row.confidence }}</strong>
                  </div>

                  <ul
                    v-if="row.matrixKind === 'FACT_LEVEL' && row.links.length"
                    class="evidence-binding-list"
                    aria-label="证据材料"
                  >
                    <li
                      v-for="(link, linkIndex) in row.links"
                      :key="`${link.evidenceId}-${link.relation}-${link.reason}`"
                      :data-relation="link.relation"
                    >
                      <code
                        class="evidence-material-reference"
                        :title="evidenceReferenceTitle(link.evidenceId)"
                        data-evidence-reference
                        data-evidence-material-reference
                      >
                        {{ numberedEvidenceReferenceLabel(link.evidenceId, linkIndex) }}
                      </code>
                      <p v-if="link.reason">
                        <EvidenceMappedText :text="link.reason" />
                      </p>
                      <span class="evidence-binding-list__relation">
                        {{ link.relationLabel }}
                      </span>
                    </li>
                  </ul>
                  <p
                    v-else-if="row.matrixKind === 'FACT_LEVEL'"
                    class="evidence-no-binding"
                  >
                    该事实没有冻结证据绑定。
                  </p>

                  <dl
                    v-else-if="row.supporting.length || row.contradicting.length"
                    class="evidence-links"
                  >
                    <div>
                      <dt>支持证据</dt>
                      <dd>
                        <code
                          v-for="(item, itemIndex) in row.supporting"
                          :key="evidenceReferenceId(item) || displayValue(item)"
                          class="evidence-material-reference"
                          :title="evidenceReferenceTitle(item)"
                          data-evidence-reference
                          data-evidence-material-reference
                        >
                          {{ numberedEvidenceReferenceLabel(item, itemIndex) }}
                        </code>
                      </dd>
                    </div>
                    <div v-if="row.contradicting.length">
                      <dt>反驳证据</dt>
                      <dd>
                        <code
                          v-for="(item, itemIndex) in row.contradicting"
                          :key="evidenceReferenceId(item) || displayValue(item)"
                          class="evidence-material-reference"
                          :title="evidenceReferenceTitle(item)"
                          data-evidence-reference
                          data-evidence-material-reference
                        >
                          {{ numberedEvidenceReferenceLabel(item, itemIndex) }}
                        </code>
                      </dd>
                    </div>
                  </dl>
                  <p v-if="row.analysis" class="evidence-analysis">
                    <EvidenceMappedText :text="row.analysis" />
                  </p>
                </article>
              </div>
              <p v-else class="empty-state">冻结包未提供证据矩阵。</p>
            </section>
          </section>

          <section
            v-show="activeSection === 'draft'"
            id="review-panel-draft"
            class="review-panel review-panel--draft"
            data-review-draft-panel
            role="tabpanel"
            aria-labelledby="review-tab-draft"
          >
            <header class="review-panel__header review-panel__header--compact">
              <div>
                <span>03</span>
                <div>
                  <h2>法官 V2 裁决草案</h2>
                </div>
              </div>
              <dl class="review-panel__metrics">
                <div><dt>事实认定</dt><dd>{{ reviewMetrics.findings }}</dd></div>
                <div><dt>规则适用</dt><dd>{{ reviewMetrics.rules }}</dd></div>
                <div><dt>复审回应</dt><dd>{{ reviewMetrics.responses }}</dd></div>
              </dl>
            </header>

            <section class="review-subsection adjudication-decision" data-adjudication-decision>
              <header>
                <h3>最终执行建议</h3>
                <span>AI 建议，等待人工终审</span>
              </header>
              <div class="adjudication-decision__summary">
                <strong>{{ draftDecision(packet.draft) }}</strong>
                <p v-if="adjudicationDecisionMeaning">
                  {{ adjudicationDecisionMeaning }}
                </p>
              </div>
            </section>

            <section
              v-if="adjudicationPublicText"
              class="review-subsection adjudication-public-text"
              data-adjudication-public-text
            >
              <header>
                <h3>法官结论说明</h3>
                <span>草案公开文本</span>
              </header>
              <p><EvidenceMappedText :text="adjudicationPublicText" /></p>
            </section>

            <section
              v-if="adjudicationReasoning"
              class="review-subsection adjudication-reasoning"
              data-adjudication-reasoning
            >
              <header>
                <h3>完整裁决理由</h3>
                <span>事实、证据与规则推导</span>
              </header>
              <p><EvidenceMappedText :text="adjudicationReasoning" /></p>
            </section>

            <section
              v-if="remedyOrders.length"
              class="review-subsection"
              data-adjudication-remedies
            >
              <header>
                <h3>处理事项</h3>
                <span>{{ remedyOrders.length }} 项</span>
              </header>
              <div class="adjudication-remedy-list">
                <article v-for="order in remedyOrders" :key="order.id">
                  <header>
                    <strong>{{ order.title }}</strong>
                  </header>
                  <p v-if="order.text"><EvidenceMappedText :text="order.text" /></p>
                  <div v-if="order.facts.length" class="adjudication-fact-refs">
                    <span>依据事实</span>
                    <ul>
                      <li v-for="fact in order.facts" :key="fact.id">{{ fact.label }}</li>
                    </ul>
                  </div>
                  <div v-if="order.conditions.length" class="adjudication-conditions">
                    <span>执行条件</span>
                    <ul>
                      <li v-for="condition in order.conditions" :key="condition">
                        <EvidenceMappedText :text="condition" />
                      </li>
                    </ul>
                  </div>
                </article>
              </div>
            </section>

            <section
              v-if="findings.length"
              class="review-subsection"
              data-adjudication-findings
            >
              <header>
                <h3>逐事实认定</h3>
                <span>{{ findings.length }} 项</span>
              </header>
              <div class="adjudication-finding-list">
                <article v-for="finding in findings" :key="finding.id">
                  <header data-adjudication-finding-heading>
                    <strong>{{ finding.title }}</strong>
                  </header>
                  <div
                    v-if="finding.finding || finding.confidence"
                    class="adjudication-finding__result"
                    role="note"
                    aria-label="认定结论"
                    data-adjudication-finding-result
                  >
                    <div class="adjudication-finding__meta">
                      <span>认定结论</span>
                      <small v-if="finding.confidence">可信度 {{ finding.confidence }}</small>
                    </div>
                    <p v-if="finding.finding">
                      <EvidenceMappedText :text="finding.finding" />
                    </p>
                  </div>
                  <div v-if="finding.evidenceIds.length" class="adjudication-evidence-refs">
                    <span>引用证据</span>
                    <code
                      v-for="(evidenceId, evidenceIndex) in finding.evidenceIds"
                      :key="evidenceId"
                      class="evidence-material-reference"
                      :title="evidenceReferenceTitle(evidenceId)"
                      data-evidence-reference
                      data-evidence-material-reference
                    >
                      {{ numberedEvidenceReferenceLabel(evidenceId, evidenceIndex) }}
                    </code>
                  </div>
                  <p v-if="finding.evidenceGap" class="adjudication-gap">
                    <strong>证据缺口</strong>
                    <EvidenceMappedText :text="finding.evidenceGap" />
                  </p>
                </article>
              </div>
            </section>

            <section
              v-if="policies.length"
              class="review-subsection"
              data-adjudication-rules
            >
              <header>
                <h3>逐规则适用</h3>
                <span>{{ policies.length }} 条</span>
              </header>
              <div class="adjudication-rule-list">
                <article v-for="policy in policies" :key="policy.id">
                  <header>
                    <strong><EvidenceMappedText :text="policy.title" /></strong>
                    <div class="adjudication-rule__meta" data-adjudication-rule-meta>
                      <span v-if="policy.applicable !== null" :data-applicable="policy.applicable">
                        {{ policy.applicable ? "适用" : "不适用" }}
                      </span>
                      <small v-if="policy.version !== ''">版本 {{ policy.version }}</small>
                    </div>
                  </header>
                  <p v-if="policy.detail"><EvidenceMappedText :text="policy.detail" /></p>
                  <div v-if="policy.factIds.length" class="adjudication-fact-refs">
                    <span>关联事实</span>
                    <ul>
                      <li v-for="factId in policy.factIds" :key="factId">
                        {{ factReferenceLabel(factId) }}
                      </li>
                    </ul>
                  </div>
                  <dl v-if="policy.conditionsMet.length || policy.conditionsUnmet.length" class="adjudication-rule-conditions">
                    <div v-if="policy.conditionsMet.length">
                      <dt>已满足条件</dt>
                      <dd>
                        <template v-for="(condition, conditionIndex) in policy.conditionsMet" :key="condition">
                          <EvidenceMappedText :text="condition" /><template v-if="conditionIndex < policy.conditionsMet.length - 1">；</template>
                        </template>
                      </dd>
                    </div>
                    <div v-if="policy.conditionsUnmet.length">
                      <dt>未满足条件</dt>
                      <dd>
                        <template v-for="(condition, conditionIndex) in policy.conditionsUnmet" :key="condition">
                          <EvidenceMappedText :text="condition" /><template v-if="conditionIndex < policy.conditionsUnmet.length - 1">；</template>
                        </template>
                      </dd>
                    </div>
                  </dl>
                  <p v-if="policy.resultingEffect" class="adjudication-rule-effect">
                    <strong>适用结果</strong>
                    <EvidenceMappedText :text="policy.resultingEffect" />
                  </p>
                </article>
              </div>
            </section>

            <details
              v-if="reviewResponses.length"
              class="review-subsection adjudication-disclosure"
              data-adjudication-review-responses
            >
              <summary>
                <span>
                  <strong>V2 复审回应</strong>
                  <small>先看原意见，再核对法官回复</small>
                </span>
                <i>{{ reviewResponses.length }} 条</i>
              </summary>
              <ol class="adjudication-response-list">
                <li
                  v-for="(response, index) in reviewResponses"
                  :key="response.id"
                  :data-review-source="response.reviewSource"
                >
                  <span>{{ String(index + 1).padStart(2, "0") }}</span>
                  <div class="adjudication-response-list__content">
                    <header>
                      <strong>{{ response.topic }}</strong>
                      <div>
                        <small v-if="response.sourceKind">{{ response.sourceKind }}</small>
                        <i>{{ response.disposition }}</i>
                      </div>
                    </header>
                    <div class="adjudication-response-pair">
                      <section
                        :class="{ 'is-unavailable': !response.reviewItemText }"
                        data-review-source-opinion
                      >
                        <span>{{ response.opinionLabel }}</span>
                        <p>
                          <EvidenceMappedText
                            v-if="response.reviewItemText"
                            :text="response.reviewItemText"
                          />
                          <template v-else>该条原始意见未随当前冻结审核包展开。</template>
                        </p>
                      </section>
                      <section data-judge-review-response>
                        <span>法官回复</span>
                        <p><EvidenceMappedText :text="response.response" /></p>
                      </section>
                    </div>
                    <ul v-if="response.affectedFields.length">
                      <li v-for="field in response.affectedFields" :key="field">{{ field }}</li>
                    </ul>
                  </div>
                </li>
              </ol>
            </details>
          </section>

          <section
            v-show="activeSection === 'risk'"
            id="review-panel-risk"
            class="review-panel review-panel--risk"
            data-review-risk-panel
            role="tabpanel"
            aria-labelledby="review-tab-risk"
          >
            <header class="review-panel__header review-panel__header--compact">
              <div>
                <span>04</span>
                <div>
                  <h2>重点复核</h2>
                </div>
              </div>
              <dl class="review-panel__metrics">
                <div><dt>待核验</dt><dd>{{ reviewRisks.length }}</dd></div>
                <div><dt>证据缺口</dt><dd>{{ reviewMetrics.missingEvidence }}</dd></div>
                <div><dt>人工关注</dt><dd>{{ adjudicationAttention.length }}</dd></div>
              </dl>
            </header>

            <section
              v-if="reviewRisks.length"
              class="review-risk-strip review-risk-strip--document"
              aria-labelledby="review-risk-list-title"
            >
              <header class="review-risk-strip__header">
                <div>
                  <strong id="review-risk-list-title">终审核验清单</strong>
                  <small>确认每一项均已在终审理由中得到回应</small>
                </div>
                <span data-review-risk-count>{{ reviewRisks.length }} 项待核验</span>
              </header>
              <ol class="review-risk-strip__list">
                <li
                  v-for="(risk, index) in reviewRisks"
                  :key="risk.code"
                  :data-risk-code="risk.code"
                  data-review-risk-item
                >
                  <span class="review-risk-strip__index" aria-hidden="true">
                    {{ String(index + 1).padStart(2, "0") }}
                  </span>
                  <p>{{ risk.label }}</p>
                </li>
              </ol>
            </section>
            <p v-else class="empty-state">当前冻结审核包没有需要重点复核的风险项。</p>

            <details
              v-if="adjudicationAttention.length"
              class="review-subsection adjudication-attention adjudication-disclosure"
              data-adjudication-attention
            >
              <summary>
                <span>
                  <strong>法官保留的人工关注事项</strong>
                  <small>展开查看草案仍未消除的不确定性</small>
                </span>
                <i>{{ adjudicationAttention.length }} 项</i>
              </summary>
              <ol>
                <li v-for="(attention, index) in adjudicationAttention" :key="attention">
                  <span>{{ String(index + 1).padStart(2, "0") }}</span>
                  <p><EvidenceMappedText :text="attention" /></p>
                </li>
              </ol>
            </details>
          </section>

          <section
            v-show="activeSection === 'audit'"
            id="review-panel-audit"
            class="review-panel review-panel--audit"
            data-review-audit-panel
            role="tabpanel"
            aria-labelledby="review-tab-audit"
          >
            <header class="review-panel__header review-panel__header--compact">
              <div>
                <span>05</span>
                <div>
                  <h2>冻结版本与审计信息</h2>
                </div>
              </div>
            </header>

            <div class="review-audit__content">
              <section class="review-audit__group" aria-labelledby="review-audit-versions-title">
                <header>
                  <h3 id="review-audit-versions-title">冻结版本链</h3>
                  <span>{{ auditEntryCount }} 项</span>
                </header>
                <div class="review-audit__versions" data-review-version-ledger>
                  <section
                    v-for="group in auditVersionGroups"
                    :key="group.label"
                    class="review-audit__version-group"
                    data-review-version-group
                  >
                    <header>
                      <h4>{{ group.label }}</h4>
                      <span>{{ group.entries.length }} 项</span>
                    </header>
                    <dl>
                      <div
                        v-for="[label, value] in group.entries"
                        :key="label"
                        data-review-version-entry
                      >
                        <dt>{{ label }}</dt>
                        <dd>{{ value }}</dd>
                      </div>
                    </dl>
                  </section>
                </div>
              </section>

              <section class="review-audit__group" aria-labelledby="review-audit-identifiers-title">
                <header>
                  <h3 id="review-audit-identifiers-title">审计标识</h3>
                  <span>只读</span>
                </header>
                <dl class="review-audit__identifiers" data-review-audit-identifiers>
                  <div data-review-identifier-row>
                    <dt>审核包 ID</dt>
                    <dd><code>{{ packet.id }}</code></dd>
                  </div>
                  <div data-review-identifier-row>
                    <dt>执行方案 ID</dt>
                    <dd><code>{{ packet.plan_id }}</code></dd>
                  </div>
                  <div data-review-identifier-row>
                    <dt>执行哈希</dt>
                    <dd><code>{{ packet.action_hash }}</code></dd>
                  </div>
                  <div
                    v-if="listEntries(packet.agent_run_refs).length"
                    class="review-audit__identifier-runs"
                    data-review-identifier-row
                  >
                    <dt>
                      <span>智能体运行记录</span>
                      <small data-review-agent-run-count>
                        {{ listEntries(packet.agent_run_refs).length }} 条记录
                      </small>
                    </dt>
                    <dd class="review-audit__run-list">
                      <code
                        v-for="run in listEntries(packet.agent_run_refs)"
                        :key="run"
                        data-review-agent-run-id
                      >
                        {{ run }}
                      </code>
                    </dd>
                  </div>
                </dl>
              </section>
            </div>
          </section>
        </section>

      </div>
    </div>

    <div
      v-if="actionSelectorOpen && !historyMode"
      class="decision-action-selector-backdrop"
      role="presentation"
      @click.self="closeDecisionActionSelector"
      @keydown.esc="closeDecisionActionSelector"
    >
      <section
        ref="actionSelectorDialog"
        class="decision-action-selector"
        role="dialog"
        aria-modal="true"
        aria-labelledby="decision-action-selector-title"
        data-decision-action-selector
        @keydown.tab="trapActionSelectorFocus"
      >
        <header class="decision-action-selector__header">
          <div>
            <span>修改最终决定</span>
            <h2 id="decision-action-selector-title">选择一个执行候选</h2>
            <p>选择后会先回填到审核员意见，确认无误后再提交执行。</p>
          </div>
          <button
            ref="actionSelectorCloseButton"
            type="button"
            @click="closeDecisionActionSelector"
          >
            取消
          </button>
        </header>

        <div class="decision-action-selector__grid" aria-label="最终决定候选">
          <button
            v-for="item in decisionActionCatalog"
            :key="item.code"
            type="button"
            :class="{
              'is-selected': selectedDecisionActionCode === item.code,
              'is-current': frozenDecisionActionCode === item.code,
            }"
            :disabled="frozenDecisionActionCode === item.code"
            :aria-pressed="selectedDecisionActionCode === item.code"
            :data-decision-action-choice="item.code"
            @click="selectDecisionAction(item.code)"
          >
            <span class="decision-action-selector__choice-heading">
              <strong>{{ item.label }}</strong>
              <code>平台执行动作</code>
            </span>
            <small>{{ item.meaning }}</small>
            <i v-if="frozenDecisionActionCode === item.code">当前 AI 建议</i>
          </button>
          <button
            type="button"
            class="decision-action-selector__manual-choice"
            :class="{ 'is-selected': reviewerOpinionDecision === 'ESCALATE_MANUAL' }"
            :aria-pressed="reviewerOpinionDecision === 'ESCALATE_MANUAL'"
            data-manual-escalation-choice
            @click="selectManualEscalation"
          >
            <span class="decision-action-selector__choice-heading">
              <strong>{{ exceptionDecisions[0].label }}</strong>
              <code>人工审核状态</code>
            </span>
            <small>{{ exceptionDecisions[0].description }}</small>
          </button>
        </div>

        <footer>
          共 {{ decisionActionCatalog.length + 1 }} 个最终候选。选择不会立即提交，可在审核员意见中再次确认。
        </footer>
      </section>
    </div>

    <div
      v-if="pendingDecision && !historyMode"
      class="decision-confirm-backdrop"
      role="presentation"
      @keydown.esc="pendingDecision = ''"
    >
      <section
        ref="confirmDialog"
        class="decision-confirm"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="decision-confirm-title"
        aria-describedby="decision-confirm-description decision-confirm-record-note"
        @keydown.tab="trapDecisionFocus"
      >
        <header class="decision-confirm__header">
          <span class="decision-confirm__icon" aria-hidden="true">!</span>
          <div>
            <span class="decision-confirm__eyebrow">最终确认</span>
            <h2 id="decision-confirm-title">确认提交审核决定？</h2>
            <p id="decision-confirm-description">
              提交后不可撤回，请再次核对执行结果与冻结审核包。
            </p>
          </div>
        </header>

        <div class="decision-confirm__body">
          <section
            class="decision-confirm__outcome"
            aria-label="本次提交结果"
          >
            <div>
              <span>本次提交结果</span>
              <strong>
                {{
                  selectedDecisionAction
                    ? selectedDecisionAction.label
                    : pendingDecisionLabel
                }}
              </strong>
            </div>
            <span class="decision-confirm__type">
              {{ pendingDecisionLabel }}
            </span>
          </section>

          <dl class="decision-confirm__meta">
            <div class="decision-confirm__case">
              <dt>案件</dt>
              <dd><EvidenceMappedText :text="caseTitle" /></dd>
            </div>
            <div>
              <dt>冻结审核包</dt>
              <dd>v{{ packet.packet_version }}</dd>
            </div>
          </dl>
        </div>

        <footer class="decision-confirm__footer">
          <p id="decision-confirm-record-note">确认后将写入不可变审核记录</p>
          <div class="decision-confirm__actions">
            <button
              ref="confirmCancelButton"
              type="button"
              @click="pendingDecision = ''"
            >
              返回检查
            </button>
            <button
              ref="confirmButton"
              type="button"
              data-decision-confirm
              :disabled="submitting"
              @click="submitDecision"
            >
              {{ submitting ? "正在提交" : "确认提交" }}
            </button>
          </div>
        </footer>
      </section>
    </div>

    <AgentStreamErrorDialog
      :message="copilotStreamError"
      title="审核解释官生成失败"
      @dismiss="copilotStreamError = ''"
    />
    <AgentStreamErrorDialog
      :message="decisionSubmitError"
      title="终审决定提交失败"
      @dismiss="decisionSubmitError = ''"
    />
  </RoomShell>
</template>

<style scoped src="./ReviewWorkbenchView.css"></style>
