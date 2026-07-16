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
import { useRoute } from "vue-router";
import { extractAgentRunDescriptor } from "../../api/agentStream";
import { evidenceApi } from "../../api/evidence";
import { reviewApi } from "../../api/review";
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

const props = defineProps({
  initialPacket: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  decideAction: { type: Function, default: null },
});

const route = useRoute();
const packet = ref(normalizeReviewPacket(props.initialPacket));
const evidenceCatalog = ref(null);
const loading = ref(!props.initialPacket);
const taskOpen = ref(Boolean(props.initialPacket));
const taskStateKnown = ref(Boolean(props.initialPacket));
const taskStatus = ref(props.initialPacket ? "IN_REVIEW" : "");
const taskLookupError = ref("");
const activeSection = ref("overview");
const reason = ref("");
const pendingDecision = ref("");
const decisionResult = ref(null);
const error = ref("");
const submitting = ref(false);
const planEditorOpen = ref(false);
const modifiedPlan = ref("");
const approvedPlanDraft = ref(null);
const submittedDecision = ref("");
const confirmButton = ref(null);
const confirmCancelButton = ref(null);
const confirmDialog = ref(null);
let decisionTrigger = null;
const agentState = ref("LISTENING");
const copilotQuestion = ref("");
const copilotMessages = ref([]);
const copilotSubmitting = ref(false);
const copilotStreamError = ref("");

const reviewId = computed(() => route.params.reviewId);
const historyMode = computed(() => route.query.view === "history");
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const packetExpiry = computed(
  () =>
    packet.value?.expires_at ||
    new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
);
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
  if (props.viewerRole) return props.viewerRole === "PLATFORM_REVIEWER";
  return actor.role === "PLATFORM_REVIEWER" && actor.id === "reviewer-local";
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
  { value: "evidence", label: "证据与规则" },
  { value: "draft", label: "草案与执行" },
];
const explanationPrompts = [
  "请概括这个案件的核心争议",
  "当前证据缺口和主要风险是什么？",
  "解释草案建议与适用规则的关系",
];
const approvalDecisions = [
  {
    value: "APPROVE",
    label: "批准并交执行",
    description: "采用冻结方案并交执行助手",
    icon: "✓",
  },
  {
    value: "MODIFY_AND_APPROVE",
    label: "修改后交执行",
    description: "编辑方案副本后交执行助手",
    icon: "✎",
  },
];
const exceptionDecisions = [
  {
    value: "REQUEST_MORE_EVIDENCE",
    label: "退回补证",
    description: "关键事实仍需举证",
    icon: "↺",
  },
  {
    value: "ESCALATE_MANUAL",
    label: "升级人工接管",
    description: "终止自动链路并转人工处理",
    icon: "↗",
  },
  {
    value: "REJECT",
    label: "驳回草案",
    description: "不采纳当前草案",
    icon: "×",
  },
];

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
  ...preconditionLabels,
  ...notificationLabels,
  ...decisionLabels,
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
  if (/^V\d+$/.test(text)) return text;
  if (/^ISSUE_\d+$/.test(text)) return `争点 ${text.slice(6)}`;
  if (/^ACTION_\d+$/.test(text)) return `执行动作 ${text.slice(7)}`;

  const words = text.split("_");
  const localized = words.map((word) => enumWordLabels[word] || "");
  if (localized.every(Boolean)) return localized.join("");
  return "待人工确认";
}

function mapReviewTokens(value) {
  return String(value || "").replace(
    /\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\b/g,
    (token) => commonValueLabels[token] || fallbackEnumLabel(token),
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

function evidenceTextSegments(value) {
  return replaceEvidenceReferences(value)
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

function riskLabel(risk) {
  return enumLabel(risk, riskLabels) || "未评估";
}

function packetStatusLabel(status) {
  return enumLabel(status, packetStatusLabels) || "未知";
}

function shortIdentifier(value, visible = 18) {
  const text = String(value || "");
  if (text.length <= visible) return text;
  return `${text.slice(0, 9)}…${text.slice(-6)}`;
}

function claimEntries(claims) {
  if (!claims) return [];
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
  const directIssues = listEntries(sourcePacket?.issues);
  const fallbackIssues = listEntries(
    sourcePacket?.claims?.dispute_core_state?.disputed_facts ||
      sourcePacket?.claims?.dispute_core_state?.facts_in_dispute ||
      sourcePacket?.claims?.dispute_focus?.key_conflicts,
  );
  return (directIssues.length ? directIssues : fallbackIssues).map(
    (issue, index) => ({
      id: issue?.id || issue?.issue_id || `issue-${index}`,
      code: issue?.issue_id || issue?.id || "",
      text: cleanText(
        issue?.issue ||
          issue?.title ||
          issue?.question ||
          issue?.description ||
          displayValue(issue),
      ),
    }),
  );
}

function evidenceEntries(matrix, issues) {
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
          linkedIssue?.text ||
          `证据组 ${index + 1}`,
      ),
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

function policyEntries(draft) {
  const policy = draft?.policy_application;
  if (!policy) return [];
  if (!Array.isArray(policy) && typeof policy === "object") {
    return Object.entries(policy).map(([title, detail], index) => ({
      id: `policy-${index}`,
      title: cleanText(title),
      detail: cleanText(displayValue(detail)),
    }));
  }
  return listEntries(policy).map((item, index) => ({
    id: item?.id || item?.rule_id || item?.rule_code || `policy-${index}`,
    title: cleanText(
      item?.rule_name ||
        item?.rule ||
        item?.title ||
        item?.rule_code ||
        item?.clause ||
        `适用规则 ${index + 1}`,
    ),
    detail: cleanText(
      item?.rationale ||
        item?.application ||
        item?.reason ||
        item?.conclusion ||
        (typeof item === "string" ? item : displayValue(item)),
    ),
  }));
}

function normalizeReviewPacket(value) {
  if (!value) return value;
  const artifact = value.draft;
  if (
    artifact?.schema_version === "adjudication_draft.v2" &&
    artifact.draft &&
    typeof artifact.draft === "object"
  ) {
    return {
      ...value,
      draft: {
        ...artifact.draft,
        artifact_id: artifact.draft_id,
        artifact_hash: artifact.content_hash,
        trial_dossier_id: artifact.trial_dossier_id,
        trial_dossier_hash: artifact.trial_dossier_hash,
      },
    };
  }
  return value;
}

function draftAttention(draft) {
  return listEntries(draft?.reviewer_attention).map(cleanText).filter(Boolean);
}

function draftDecisionCode(draft) {
  return (
    draft?.recommended_decision ||
    draft?.recommendedDecision ||
    draft?.recommended_outcome ||
    draft?.recommendedOutcome ||
    draft?.conclusion ||
    draft?.decision ||
    ""
  );
}

function draftDecision(draft) {
  const code = draftDecisionCode(draft);
  return code ? enumLabel(code, outcomeLabels) : "等待草案";
}

function draftReasoning(draft) {
  return cleanText(
    draft?.draft_text ||
      draft?.draftText ||
      draft?.reasoning_summary ||
      draft?.reasoningSummary ||
      draft?.reasoning ||
      draft?.reason,
  );
}

function findingEntries(draft) {
  return listEntries(draft?.fact_findings).map((finding, index) => ({
    id: finding?.id || finding?.fact_id || finding?.issue_id || `finding-${index}`,
    text: cleanText(
      finding?.finding ||
        finding?.conclusion ||
        finding?.text ||
        displayValue(finding),
    ),
  }));
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
      "案件终审摘要",
  ),
);
const caseDescription = computed(() =>
  firstText(
    packet.value?.case_summary?.description,
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
  const code =
    claimResolution.requested_resolution || requestedResolution.requested_outcome;
  return {
    code,
    label: enumLabel(code, outcomeLabels),
    text: firstText(
      claimResolution.normalized_statement,
      requestedResolution.expected_resolution_text,
      claimResolution.request_reason,
    ),
    amount: displayAmount(claimResolution.requested_amount),
    items: cleanText(claimResolution.requested_items),
  };
});
const coreConflict = computed(() =>
  firstText(
    packet.value?.claims?.dispute_core_state?.core_conflict,
    packet.value?.claims?.dispute_focus?.core_issue,
  ),
);
const factsToVerify = computed(() =>
  listEntries(
    packet.value?.claims?.dispute_core_state?.next_verification_focus ||
      packet.value?.claims?.dispute_focus?.facts_to_verify,
  )
    .map((value) => mapReviewTokens(cleanText(value)))
    .filter(Boolean),
);
const issues = computed(() => issueEntries(packet.value));
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
const findings = computed(() => findingEntries(packet.value?.draft));
const actions = computed(() => remedyActions(packet.value?.remedy));
const notifications = computed(() => notificationEntries(packet.value?.remedy));
const reviewRisks = computed(() => {
  const risks = listEntries(packet.value?.risk_flags).map((code) => ({
    code,
    label: enumLabel(code, riskFlagLabels),
  }));
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
  actions: actions.value.length,
}));
const auditEntries = computed(() => [
  ["审核包", `v${packet.value?.packet_version ?? "-"}`],
  ["案件快照", `v${packet.value?.case_version ?? "-"}`],
  ["证据卷", `v${packet.value?.dossier_version ?? "-"}`],
  ["争点", `v${packet.value?.issue_version ?? "-"}`],
  ["裁决草案", `v${packet.value?.adjudication_draft_version ?? "-"}`],
  ["评议报告", `v${packet.value?.deliberation_report_version ?? "-"}`],
  ["执行方案", `v${packet.value?.remedy_plan_version ?? "-"}`],
  ["规则集", packet.value?.ruleset_version || "-"],
  ["提示词", packet.value?.prompt_version || "-"],
  ["技能", packet.value?.skill_version || "-"],
  ["角色配置", packet.value?.profile_version || "-"],
]);
const pendingDecisionLabel = computed(
  () => decisionLabels[pendingDecision.value] || pendingDecision.value,
);
const decisionReadonlyMessage = computed(() => {
  if (historyMode.value) {
    return "这是已封存的历史终审记录，所有批准、修改和驳回操作均已锁定。";
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
  return "冻结审核包生成前仅可只读旁观，系统不会展示任何批准按钮。";
});
const decisionResultMessage = computed(() => {
  const decision = decisionResult.value?.decision || submittedDecision.value;
  if (decision === "REQUEST_MORE_EVIDENCE") {
    return "案件已退回证据室，等待补充关键材料。";
  }
  if (decision === "REJECT") {
    return "当前裁决草案已驳回，案件进入人工接管。";
  }
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
  parts.push(`当前草案建议为“${draftDecision(packet.value?.draft)}”。`);
  if (reviewRisks.value.length) {
    parts.push(`终审需要重点复核：${reviewRisks.value.map((risk) => risk.label).join("、")}。`);
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

async function loadTaskAccess() {
  taskLookupError.value = "";
  try {
    const statuses = ["PENDING", "ASSIGNED", "IN_REVIEW"];
    const groups = await Promise.all(
      statuses.map((status) => reviewApi.list(actor, status)),
    );
    const task = groups.flat().find((item) => item.id === reviewId.value);
    taskOpen.value = Boolean(task);
    taskStatus.value = task?.status || "";
  } catch (failure) {
    taskOpen.value = false;
    taskStatus.value = "";
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
  if (decision === "MODIFY_AND_APPROVE") {
    modifiedPlan.value = JSON.stringify(packet.value.remedy, null, 2);
    approvedPlanDraft.value = null;
    planEditorOpen.value = true;
    return;
  }
  if (!reason.value.trim()) {
    error.value = "请先填写审核理由";
    return;
  }
  pendingDecision.value = decision;
}

function confirmModifiedPlan() {
  error.value = "";
  if (!reason.value.trim()) {
    error.value = "请先填写审核理由";
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(modifiedPlan.value);
  } catch {
    error.value = "修改后的执行方案不是有效 JSON";
    return;
  }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    error.value = "修改后的执行方案必须是对象";
    return;
  }
  if (!String(parsed.id || "").trim() || !Array.isArray(parsed.actions)) {
    error.value = "修改后的执行方案必须保留方案 ID 和 actions 数组";
    return;
  }
  if (parsed.id !== packet.value.remedy?.id || parsed.id !== packet.value.plan_id) {
    error.value = "修改方案必须保留当前冻结方案 ID";
    return;
  }
  if (
    parsed.actions.length === 0 ||
    parsed.actions.some(
      (action) =>
        !action ||
        typeof action !== "object" ||
        !String(action.action_type || action.actionType || action.type || "").trim(),
    )
  ) {
    error.value = "修改方案必须保留至少一个有效执行动作";
    return;
  }
  const originalPlan = packet.value.remedy || {};
  const immutableKeys = Object.keys(originalPlan).filter((key) => key !== "actions");
  const hasUnexpectedTopLevelKey = Object.keys(parsed).some(
    (key) => !Object.prototype.hasOwnProperty.call(originalPlan, key),
  );
  const changedImmutableField = immutableKeys.some(
    (key) =>
      JSON.stringify(normalizedJson(parsed[key])) !==
      JSON.stringify(normalizedJson(originalPlan[key])),
  );
  if (hasUnexpectedTopLevelKey || changedImmutableField) {
    error.value = "只能修改 actions；方案 ID、版本、前置条件和通知必须保持冻结一致";
    return;
  }
  const original = JSON.stringify(normalizedJson(packet.value.remedy));
  const modified = JSON.stringify(normalizedJson(parsed));
  if (original === modified) {
    error.value = "执行方案尚未修改；如采用原方案，请选择批准执行";
    return;
  }
  approvedPlanDraft.value = parsed;
  planEditorOpen.value = false;
  pendingDecision.value = "MODIFY_AND_APPROVE";
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
  submitting.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    decision: pendingDecision.value,
    reason: reason.value.trim(),
    approved_plan:
      pendingDecision.value === "MODIFY_AND_APPROVE"
        ? approvedPlanDraft.value
        : null,
  };
  submittedDecision.value = pendingDecision.value;
  try {
    decisionResult.value = props.decideAction
      ? await props.decideAction(command)
      : await reviewApi.decide(actor, reviewId.value, command);
    pendingDecision.value = "";
    agentState.value = "HANDOFF";
  } catch (failure) {
    error.value = failure?.message || "终审决定提交失败，请稍后重试。";
    agentState.value = "ERROR";
  } finally {
    submitting.value = false;
  }
}

watch(historyMode, (historical) => {
  if (!historical) return;
  pendingDecision.value = "";
  planEditorOpen.value = false;
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

onMounted(load);
onBeforeUnmount(() => {
  clearInterval(clockTimer);
  clearAgentStreams(copilotContext.value);
});
</script>

<template>
  <RoomShell
    class="review-workbench"
    eyebrow="平台人工终审"
    title="平台终审室"
    subtitle="平台最终确认"
    subtitle-description="审核员可以核对、修改或驳回裁决草案，并决定最终执行方案。"
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
          <time data-frozen-time :datetime="packet.frozen_at" :title="packet.frozen_at">
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
        message="我只依据当前冻结审核包转述事实、证据、规则和草案。批准、修改或驳回必须由你亲自确认。"
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

          <div class="review-explain-room__prompts" aria-label="快捷提问">
            <button
              v-for="prompt in explanationPrompts"
              :key="prompt"
              type="button"
              :disabled="!canUseCopilot || copilotBusy"
              @click="submitCopilotQuestion({ text: prompt })"
            >
              {{ prompt }}
            </button>
          </div>

          <div class="review-explain-room__conversation">
            <ConversationStream
              :messages="copilotConversationMessages"
              :streaming-runs="copilotRuns"
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
          <header class="review-operation-room__header">
            <div>
              <span>终审工作台</span>
              <h2>冻结审核与最终决定</h2>
              <p>核对当前冻结版本，记录终审依据并提交平台最终决定。</p>
            </div>
            <small>仅审核员</small>
          </header>

          <div class="review-operation-room__scroll">
            <section class="review-case-strip" aria-label="当前终审案件">
              <div class="review-case-strip__identity">
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
                <strong>{{ caseTitle }}</strong>
                <span>当前终审案件</span>
              </div>
              <div class="review-case-strip__version">
                <span>当前核验基线</span>
                <strong>冻结审核包 v{{ packet.packet_version }}</strong>
                <small>所有决定仅作用于本冻结版本</small>
              </div>
            </section>

            <section v-if="reviewRisks.length" class="review-risk-strip" aria-label="审核风险">
              <strong>重点复核</strong>
              <span v-for="risk in reviewRisks" :key="risk.code" :title="risk.label">
                {{ risk.label }}
              </span>
            </section>

            <section class="decision-panel">
            <header>
              <div>
                <span>人工决定</span>
                <h2>人类最终决定</h2>
              </div>
              <i>仅审核员</i>
            </header>

            <div class="decision-panel__draft">
              <span>AI 建议</span>
              <strong>{{ draftDecision(packet.draft) }}</strong>
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
                  :class="{ 'decision-action--primary': decision.value === 'APPROVE' }"
                  :data-decision="decision.value"
                  @click="requestDecision(decision.value)"
                >
                  <span aria-hidden="true">{{ decision.icon }}</span>
                  <span>
                    <strong>{{ decision.label }}</strong>
                    <small>{{ decision.description }}</small>
                  </span>
                </button>
              </div>

              <div v-if="planEditorOpen" class="plan-editor" data-plan-editor>
                <header>
                  <strong>修改执行方案</strong>
                  <button
                    type="button"
                    aria-label="关闭方案编辑"
                    title="关闭方案编辑"
                    @click="planEditorOpen = false"
                  >
                    ×
                  </button>
                </header>
                <textarea
                  v-model="modifiedPlan"
                  rows="12"
                  spellcheck="false"
                  aria-label="修改后的执行方案"
                  data-modified-plan
                />
                <button type="button" data-modified-plan-confirm @click="confirmModifiedPlan">
                  校验并继续
                </button>
              </div>

              <div class="decision-actions decision-actions--exception">
                <span>其他处理</span>
                <button
                  v-for="decision in exceptionDecisions"
                  :key="decision.value"
                  type="button"
                  :class="{ 'decision-action--danger': decision.value === 'REJECT' }"
                  :data-decision="decision.value"
                  @click="requestDecision(decision.value)"
                >
                  <span aria-hidden="true">{{ decision.icon }}</span>
                  <span>
                    <strong>{{ decision.label }}</strong>
                    <small>{{ decision.description }}</small>
                  </span>
                </button>
              </div>
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
        </aside>
      </div>

      <details class="review-audit">
        <summary>
          <span>冻结版本与审计信息</span>
          <small>{{ shortIdentifier(packet.action_hash, 22) }}</small>
        </summary>
        <div class="review-audit__content">
          <dl class="review-audit__versions">
            <div v-for="[label, value] in auditEntries" :key="label">
              <dt>{{ label }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
          <dl class="review-audit__identifiers">
            <div>
              <dt>审核包 ID</dt>
              <dd><code>{{ packet.id }}</code></dd>
            </div>
            <div>
              <dt>执行方案 ID</dt>
              <dd><code>{{ packet.plan_id }}</code></dd>
            </div>
            <div>
              <dt>执行哈希</dt>
              <dd><code>{{ packet.action_hash }}</code></dd>
            </div>
            <div v-if="listEntries(packet.agent_run_refs).length">
              <dt>智能体运行记录</dt>
              <dd>
                <code v-for="run in listEntries(packet.agent_run_refs)" :key="run">
                  {{ run }}
                </code>
              </dd>
            </div>
          </dl>
        </div>
      </details>

      <div class="review-workbench__workspace">
        <section
          class="review-document"
          data-review-material-column
          aria-label="冻结审核材料"
        >
          <nav class="review-tabs" role="tablist" aria-label="审核材料分类">
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
            <header class="review-panel__header">
              <div>
                <span>01</span>
                <div>
                  <h2>案件事实与诉求</h2>
                  <p>核对当事人陈述、核心冲突和待核事实。</p>
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
              <p v-if="caseDescription" class="case-narrative">
                <EvidenceMappedText :text="caseDescription" />
              </p>

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

              <div v-if="references.length" class="case-references" aria-label="业务单据">
                <span v-for="reference in references" :key="reference.label">
                  {{ reference.label }} <code>{{ reference.value }}</code>
                </span>
              </div>
            </section>

            <section class="review-subsection" data-claims-card>
              <header>
                <h3>双方主张</h3>
                <span>{{ claims.length }} 方陈述</span>
              </header>
              <div v-if="claims.length" class="claim-list">
                <article v-for="claim in claims" :key="claim.label" class="claim-item">
                  <strong>{{ claim.label }}</strong>
                  <p><EvidenceMappedText :text="claim.text" /></p>
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
                    <strong><EvidenceMappedText :text="issue.text" /></strong>
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
            <header class="review-panel__header">
              <div>
                <span>02</span>
                <div>
                  <h2>证据与规则核验</h2>
                  <p>按争点检查支持证据、反驳证据、缺口和规则依据。</p>
                </div>
              </div>
              <dl class="review-panel__metrics">
                <div><dt>证据组</dt><dd>{{ reviewMetrics.evidence }}</dd></div>
                <div><dt>缺口</dt><dd>{{ reviewMetrics.missingEvidence }}</dd></div>
                <div><dt>规则</dt><dd>{{ policies.length }}</dd></div>
              </dl>
            </header>

            <section class="review-subsection" data-evidence-matrix>
              <header>
                <h3>争点证据矩阵</h3>
                <span>{{ evidence.length }} 组</span>
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
                    <span v-if="row.missing">存在证据缺口</span>
                    <span v-else>材料已归集</span>
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

                  <dl class="evidence-links">
                    <div>
                      <dt>支持证据</dt>
                      <dd v-if="row.supporting.length">
                        <code
                          v-for="item in row.supporting"
                          :key="evidenceReferenceId(item) || displayValue(item)"
                          :title="evidenceReferenceTitle(item)"
                          data-evidence-reference
                        >
                          {{ evidenceReferenceLabel(item) }}
                        </code>
                      </dd>
                      <dd v-else>暂无有效支持证据</dd>
                    </div>
                    <div>
                      <dt>反驳证据</dt>
                      <dd v-if="row.contradicting.length">
                        <code
                          v-for="item in row.contradicting"
                          :key="evidenceReferenceId(item) || displayValue(item)"
                          :title="evidenceReferenceTitle(item)"
                          data-evidence-reference
                        >
                          {{ evidenceReferenceLabel(item) }}
                        </code>
                      </dd>
                      <dd v-else>暂无反驳证据</dd>
                    </div>
                  </dl>
                  <p v-if="row.analysis" class="evidence-analysis">
                    <EvidenceMappedText :text="row.analysis" />
                  </p>
                </article>
              </div>
              <p v-else class="empty-state">冻结包未提供证据矩阵。</p>
            </section>

            <section class="review-subsection">
              <header>
                <h3>规则适用</h3>
                <span>{{ packet.ruleset_version }}</span>
              </header>
              <div v-if="policies.length" class="policy-list">
                <article v-for="policy in policies" :key="policy.id">
                  <strong><EvidenceMappedText :text="policy.title" /></strong>
                  <p><EvidenceMappedText :text="policy.detail" /></p>
                </article>
              </div>
              <p v-else class="empty-state">
                当前草案未附结构化规则条款，请结合冻结规则集复核。
              </p>
            </section>

            <section v-if="findings.length" class="review-subsection">
              <header>
                <h3>事实认定</h3>
                <span>{{ findings.length }} 项</span>
              </header>
              <ul class="focus-list">
                <li v-for="finding in findings" :key="finding.id">
                  <EvidenceMappedText :text="finding.text" />
                </li>
              </ul>
            </section>
          </section>

          <section
            v-show="activeSection === 'draft'"
            id="review-panel-draft"
            class="review-panel"
            role="tabpanel"
            aria-labelledby="review-tab-draft"
          >
            <header class="review-panel__header">
              <div>
                <span>03</span>
                <div>
                  <h2>裁决草案与执行方案</h2>
                  <p>比较非最终建议、人工关注点和待批准执行动作。</p>
                </div>
              </div>
              <dl class="review-panel__metrics">
                <div><dt>关注项</dt><dd>{{ draftAttention(packet.draft).length }}</dd></div>
                <div><dt>执行动作</dt><dd>{{ reviewMetrics.actions }}</dd></div>
              </dl>
            </header>

            <section class="review-subsection packet-cards__draft">
              <header>
                <h3>AI 裁决草案（非最终）</h3>
                <span v-if="packet.draft?.confidence">
                  置信度 {{ displayPercent(packet.draft.confidence) }}
                </span>
              </header>
              <div class="draft-decision">
                <div>
                  <span>建议方向</span>
                  <strong>{{ draftDecision(packet.draft) }}</strong>
                </div>
                <p v-if="draftReasoning(packet.draft)">
                  <EvidenceMappedText :text="draftReasoning(packet.draft)" />
                </p>
              </div>
              <div v-if="draftAttention(packet.draft).length" class="review-attention">
                <strong>人工关注点</strong>
                <ul>
                  <li v-for="item in draftAttention(packet.draft)" :key="item">
                    <EvidenceMappedText :text="item" />
                  </li>
                </ul>
              </div>
            </section>

            <section class="review-subsection" data-remedy-card>
              <header>
                <h3>待批准执行方案</h3>
                <span>v{{ packet.remedy_plan_version }}</span>
              </header>
              <div v-if="actions.length" class="remedy-actions">
                <article v-for="(action, index) in actions" :key="action.id" class="remedy-action">
                  <header>
                    <span>{{ String(index + 1).padStart(2, "0") }}</span>
                    <div>
                      <strong>{{ action.title }}</strong>
                    </div>
                    <i :data-risk="action.risk">
                      {{ riskLabel(action.risk) }} · {{ action.requiresApproval ? "需终审" : "无需终审" }}
                    </i>
                  </header>
                  <dl v-if="action.amount || action.target || action.deadline" class="remedy-action__facts">
                    <div v-if="action.amount"><dt>金额</dt><dd>{{ action.amount }}</dd></div>
                    <div v-if="action.target">
                      <dt>目标方</dt><dd><EvidenceMappedText :text="action.target" /></dd>
                    </div>
                    <div v-if="action.deadline">
                      <dt>截止时间</dt><dd>{{ displayDateTime(action.deadline) }}</dd>
                    </div>
                  </dl>
                  <div v-if="action.preconditions.length" class="remedy-action__conditions">
                    <span>执行前置条件</span>
                    <p>
                      <code v-for="condition in action.preconditions" :key="condition">
                        {{ enumLabel(condition, preconditionLabels) }}
                      </code>
                    </p>
                  </div>
                  <dl v-if="action.parameters.length" class="remedy-action__parameters">
                    <div v-for="parameter in action.parameters" :key="parameter.key">
                      <dt>{{ parameter.label }}</dt>
                      <dd><EvidenceMappedText :text="parameter.value" /></dd>
                    </div>
                  </dl>
                  <p v-if="action.note" class="remedy-action__note">
                    <EvidenceMappedText :text="action.note" />
                  </p>
                </article>
              </div>
              <p v-else class="empty-state">冻结包未提供可执行动作。</p>
            </section>

            <section v-if="notifications.length" class="review-subsection">
              <header>
                <h3>执行后通知</h3>
                <span>{{ notifications.length }} 项</span>
              </header>
              <ul class="notification-list">
                <li v-for="notification in notifications" :key="notification.code">
                  <span aria-hidden="true">✓</span>
                  <strong>{{ notification.label }}</strong>
                </li>
              </ul>
            </section>
          </section>
        </section>

      </div>
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
        @keydown.tab="trapDecisionFocus"
      >
        <span class="decision-confirm__icon" aria-hidden="true">!</span>
        <div>
          <span>最终确认</span>
          <h2 id="decision-confirm-title">确认{{ pendingDecisionLabel }}？</h2>
          <p>该决定会写入不可变审核记录，并驱动后续执行链路。</p>
          <dl>
            <div><dt>案件</dt><dd><EvidenceMappedText :text="caseTitle" /></dd></div>
            <div><dt>审核包</dt><dd>v{{ packet.packet_version }}</dd></div>
            <div><dt>决定</dt><dd>{{ pendingDecisionLabel }}</dd></div>
          </dl>
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
        </div>
      </section>
    </div>

    <AgentStreamErrorDialog
      :message="copilotStreamError"
      title="审核解释官生成失败"
      @dismiss="copilotStreamError = ''"
    />
  </RoomShell>
</template>

<style scoped>
.review-workbench {
  --review-ink: #34435c;
  --review-muted: #71809a;
  --review-border: #dfe8f4;
  --review-soft: #f8fbff;
  --review-teal: #6279ca;
  --review-teal-soft: #eef3ff;
  --review-amber: #9a6a18;
  --review-amber-soft: #fff6df;
  --review-danger: #b24b5d;
  --review-danger-soft: #fff0f3;
  display: grid;
  width: 100%;
  min-width: 0;
  gap: 18px;
  color: var(--review-ink);
}

.review-workbench,
.review-workbench * {
  box-sizing: border-box;
}

.review-workbench :where(
  header,
  aside,
  section,
  article,
  div,
  nav,
  dl,
  dt,
  dd,
  ol,
  ul,
  li,
  p,
  h1,
  h2,
  h3,
  strong,
  span,
  small,
  i,
  label,
  textarea,
  button,
  code
) {
  min-width: 0;
}

.review-workbench :where(p, h1, h2, h3, strong, span, small, dt, dd, li, label, code) {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.review-workbench button,
.review-workbench summary,
.review-workbench a {
  -webkit-tap-highlight-color: transparent;
}

.review-workbench__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.review-workbench__badges > span {
  padding: 5px 9px;
  border: 1px solid var(--review-border);
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
  line-height: 1;
}

.status-badge {
  color: #246149;
  background: #edf8f1;
  border-color: #cfe7d8 !important;
}

.task-badge {
  color: #5e5682;
  background: #f1edff;
  border-color: #ded5f4 !important;
}

.risk-badge {
  color: var(--review-amber);
  background: var(--review-amber-soft);
  border-color: #ead7af !important;
}

.risk-badge[data-risk="HIGH"],
.risk-badge[data-risk="CRITICAL"] {
  color: var(--review-danger);
  background: var(--review-danger-soft);
  border-color: #ebcbc7 !important;
}

.route-badge {
  color: #586b8c;
  background: #f1f5ff;
  border-color: #dce5f5 !important;
}

.review-workbench__timing {
  display: flex;
  align-items: stretch;
  gap: 10px;
}

.review-workbench__packet-version {
  display: grid;
  min-width: 154px;
  align-content: center;
  gap: 3px;
  padding: 9px 12px;
  background: rgba(255, 255, 255, .82);
  border: 1px solid #dfe8f4;
  border-radius: 14px;
  box-shadow: inset 0 1px 0 #fff;
}

.review-workbench__packet-version span,
.review-workbench__packet-version time {
  color: #7a8591;
  font-size: 10px;
}

.review-workbench__packet-version strong {
  color: #34414e;
  font-size: 12px;
}

.review-workbench__timer :deep(.phase-countdown) {
  display: grid;
  min-width: 160px;
  height: 100%;
  align-content: center;
  gap: 3px;
  padding: 9px 12px;
  background: rgba(255, 255, 255, .82);
  border: 1px solid #dfe8f4;
  border-radius: 14px;
  box-shadow: inset 0 1px 0 #fff;
}

.review-workbench__timer :deep(.phase-countdown > span),
.review-workbench__timer :deep(.phase-countdown > small) {
  color: #7a8591;
  font-size: 10px;
}

.review-workbench__timer :deep(.phase-countdown > strong) {
  color: #273541;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 17px;
  font-variant-numeric: tabular-nums;
}

.review-workbench__timer :deep(.phase-countdown--zero > strong) {
  color: var(--review-danger);
}

.review-triple-layout {
  display: grid;
  grid-template-columns:
    minmax(320px, .92fr)
    minmax(420px, 1.05fr)
    minmax(320px, .83fr);
  grid-template-areas:
    "chat materials operation"
    "audit audit audit";
  align-items: start;
  gap: 18px;
}

.review-room-layout,
.review-workbench__workspace {
  display: contents;
}

.review-explain-room,
.review-operation-room {
  box-sizing: border-box;
  height: 740px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #ffffffbf;
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px #556d9512;
}

.review-explain-room {
  grid-area: chat;
  display: grid;
  grid-template-rows: 92px auto minmax(0, 1fr);
  gap: 12px;
  padding: 18px;
}

.review-explain-room__header,
.review-operation-room__header {
  box-sizing: border-box;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  height: 92px;
  min-height: 92px;
  padding: 15px 16px 18px;
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, .95), transparent 34%),
    linear-gradient(135deg, #f8fbff, #f4f7ff);
  border: 1px solid #dce8f4;
  border-radius: 18px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .92);
}

.review-explain-room__status {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #8ca2ff, #77dfb7);
  border-radius: 14px;
  box-shadow: 0 10px 24px rgba(96, 122, 180, .22);
  font-size: 13px;
}

.review-explain-room__header > div,
.review-operation-room__header > div {
  display: grid;
  gap: 3px;
}

.review-explain-room__header > div > span,
.review-operation-room__header > div > span {
  color: #7186aa;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}

.review-explain-room__header h2,
.review-operation-room__header h2 {
  margin: 3px 0 2px;
  color: #34435c;
  font-size: 17px;
  line-height: 1.22;
}

.review-explain-room__header p,
.review-operation-room__header p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #6f7d92;
  font-size: 12px;
  line-height: 1.42;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.review-explain-room__header p {
  -webkit-line-clamp: 1;
}

.review-explain-room__header small,
.review-operation-room__header small {
  justify-self: end;
  padding: 4px 9px;
  color: #34755a;
  background: rgba(229, 250, 240, .82);
  border: 1px solid rgba(106, 211, 169, .48);
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
  white-space: nowrap;
}

.review-explain-room__header small[data-agent-state="working"] {
  color: #875c20;
  background: #fff3d9;
}

.review-explain-room__prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.review-explain-room__prompts button {
  min-height: 38px;
  padding: 7px 11px;
  color: #586d91;
  background: #f8fbff;
  border: 1px solid #d9e4f2;
  border-radius: 13px;
  cursor: pointer;
  font-size: 11px;
}

.review-explain-room__prompts button:hover:not(:disabled) {
  color: #385a83;
  background: #edf5ff;
  border-color: #bcd4eb;
}

.review-explain-room__prompts button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.review-explain-room__conversation {
  min-height: 0;
  overflow: hidden;
}

.review-explain-room__conversation :deep(.conversation-stream) {
  height: 100%;
  min-height: 0;
}

.review-operation-room {
  grid-area: operation;
  display: grid;
  grid-template-rows: 112px minmax(0, 1fr);
  gap: 14px;
  padding: 18px;
}

.review-operation-room__header {
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas:
    "eyebrow eyebrow"
    "title badge"
    "description description";
  align-content: start;
  align-items: start;
  column-gap: 12px;
  row-gap: 3px;
  height: 112px;
  min-height: 112px;
}

.review-operation-room__header > div {
  display: contents;
}

.review-operation-room__header > div > span {
  grid-area: eyebrow;
}

.review-operation-room__header h2 {
  grid-area: title;
  margin: 0;
}

.review-operation-room__header p {
  grid-area: description;
}

.review-operation-room__header small {
  grid-area: badge;
  align-self: center;
}

.review-operation-room__scroll {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  padding: 2px 6px 8px 2px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-color: #cbd8e8 transparent;
  scrollbar-width: thin;
}

.review-operation-room__scroll::-webkit-scrollbar {
  width: 8px;
}

.review-operation-room__scroll::-webkit-scrollbar-thumb {
  background: #cbd8e8;
  border-radius: 999px;
}

.review-operation-room .decision-actions--approval {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.review-operation-room .decision-actions--exception {
  grid-template-columns: minmax(0, 1fr);
}

.review-operation-room .decision-actions--exception > button {
  grid-template-columns: 26px minmax(0, 1fr);
  min-height: 48px;
  align-content: center;
  min-width: 0;
}

.review-risk-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 15px;
}

.review-case-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 15px 18px;
  background:
    radial-gradient(circle at 12% 0, rgba(255, 255, 255, .94), transparent 34%),
    linear-gradient(135deg, #f8fbff 0%, #f4f8ff 52%, #f2fbf7 100%);
  border: 1px solid #dce8f4;
  border-radius: 18px;
}

.review-case-strip__identity {
  display: grid;
  gap: 6px;
}

.review-case-strip__identity > strong {
  color: #34445b;
  font-size: 15px;
}

.review-case-strip__identity > span,
.review-case-strip__version > span,
.review-case-strip__version > small {
  color: #7a8799;
  font-size: 10px;
}

.review-case-strip__identity > span {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.review-case-strip__version {
  display: grid;
  flex: 0 0 auto;
  gap: 3px;
  padding-left: 20px;
  border-left: 1px solid #dce4e9;
  text-align: right;
}

.review-case-strip__version strong {
  color: #4f6078;
  font-size: 12px;
}

.review-loading,
.review-load-error {
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 24px;
  background: #fff;
  border: 1px solid var(--review-border);
  border-radius: 28px;
}

.review-loading > span {
  width: 28px;
  height: 28px;
  border: 3px solid #dce4e4;
  border-top-color: var(--review-teal);
  border-radius: 50%;
  animation: review-spin 0.8s linear infinite;
}

.review-loading strong,
.review-load-error strong {
  font-size: 14px;
}

.review-loading p,
.review-load-error p {
  margin: 4px 0 0;
  color: var(--review-muted);
  font-size: 12px;
}

.review-load-error {
  flex-direction: column;
  text-align: center;
}

.review-load-error button {
  padding: 8px 12px;
  color: #fff;
  background: var(--review-teal);
  border: 0;
  border-radius: 12px;
  cursor: pointer;
}

.review-risk-strip {
  justify-content: flex-start;
  color: #815a26;
  background: #fffaf0;
  border: 1px solid #efdfbb;
}

.review-risk-strip strong {
  margin-right: 4px;
  font-size: 12px;
}

.review-risk-strip span {
  padding: 4px 8px;
  color: #815a26;
  background: rgba(255, 255, 255, .82);
  border: 1px solid #eeddb8;
  border-radius: 999px;
  font-size: 10px;
}

.review-audit {
  grid-area: audit;
  background: linear-gradient(135deg, #ffffffd6, #f7faffc9);
  border: 1px solid #dfe8f4;
  border-radius: 20px;
  box-shadow: 0 12px 30px #556d950d;
}

.review-audit summary {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 16px;
  color: #536683;
  cursor: pointer;
  font-size: 12px;
  font-weight: 750;
}

.review-audit summary small {
  color: #8a949f;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 10px;
  font-weight: 500;
}

.review-audit__content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 0.85fr);
  gap: 18px;
  padding: 12px;
  border-top: 1px solid var(--review-border);
}

.review-audit__versions {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.review-audit__versions > div,
.review-audit__identifiers > div {
  display: grid;
  gap: 3px;
}

.review-audit dt {
  color: #87919c;
  font-size: 9px;
}

.review-audit dd {
  margin: 0;
  color: #414f5d;
  font-size: 11px;
}

.review-audit__identifiers {
  display: grid;
  gap: 8px;
}

.review-audit__identifiers code {
  display: block;
  color: #435563;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 9px;
}

.review-workbench__workspace {
  display: contents;
}

.review-document {
  grid-area: materials;
  display: grid;
  grid-template-rows: 58px minmax(0, 1fr);
  height: 740px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #ffffffbf;
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px #556d9512;
}

.review-tabs {
  display: flex;
  min-height: 58px;
  gap: 6px;
  padding: 8px 12px;
  overflow-x: auto;
  background: linear-gradient(135deg, #f8fbff, #f6f3ff);
  border-bottom: 1px solid var(--review-border);
}

.review-tabs button {
  position: relative;
  flex: 0 0 auto;
  padding: 0 16px;
  color: #687a96;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 14px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 750;
}

.review-tabs button[aria-selected="true"] {
  color: #526bc1;
  background: #fff;
  border-color: #dce6f4;
  box-shadow: 0 8px 20px #5d73a514;
}

.review-tabs button[aria-selected="true"]::after {
  content: none;
}

.review-panel {
  min-height: 0;
  padding: 18px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: #cbd8e8 transparent;
  scrollbar-width: thin;
}

.review-panel::-webkit-scrollbar {
  width: 8px;
}

.review-panel::-webkit-scrollbar-thumb {
  background: #cbd8e8;
  border-radius: 999px;
}

.review-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.review-panel__header > div:first-child {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.review-panel__header > div:first-child > span {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #7f91e6, #69bea7);
  border-radius: 11px;
  box-shadow: 0 8px 18px #657ab42b;
  font-size: 10px;
  font-weight: 900;
}

.review-panel__header h2 {
  margin: 0;
  color: #26333f;
  font-size: 20px;
  line-height: 1.3;
}

.review-panel__header p {
  margin: 4px 0 0;
  color: #7b8692;
  font-size: 12px;
  line-height: 1.5;
}

.review-panel__metrics {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
}

.review-panel__metrics > div {
  display: grid;
  min-width: 40px;
  gap: 2px;
  text-align: right;
}

.review-panel__metrics dt {
  color: #89939e;
  font-size: 9px;
}

.review-panel__metrics dd {
  margin: 0;
  color: #34424f;
  font-size: 16px;
  font-weight: 800;
}

.review-panel__header + .case-narrative,
.review-panel__header + .case-key-facts,
.review-panel__header + .packet-cards__draft {
  margin-top: 10px;
}

.review-panel__header + .review-subsection:not(.packet-cards__draft) {
  padding-top: 0;
  margin-top: 10px;
  border-top: 0;
}

.review-case-summary > header + .case-narrative,
.review-case-summary > header + .case-key-facts {
  margin-top: 0;
}

.review-case-summary .case-narrative + .case-key-facts {
  margin-top: 10px;
}

.case-narrative {
  padding: 12px 14px;
  margin: 18px 0 0;
  color: #41505e;
  background: linear-gradient(135deg, #f8fbff, #f5f8ff);
  border: 1px solid #e1eaf5;
  border-left: 3px solid #8197e4;
  border-radius: 15px;
  font-size: 13px;
  line-height: 1.7;
}

.case-key-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 18px 0 0;
}

.case-key-facts > div {
  display: grid;
  gap: 5px;
  padding: 12px;
  background: #f9fbff;
  border: 1px solid #e1e9f3;
  border-radius: 14px;
}

.case-key-facts dt,
.remedy-action__facts dt,
.remedy-action__parameters dt {
  color: #818c98;
  font-size: 9px;
}

.case-key-facts dd,
.remedy-action__facts dd,
.remedy-action__parameters dd {
  margin: 0;
  color: #40505e;
  font-size: 12px;
  line-height: 1.55;
}

.case-key-facts dd strong {
  color: #273744;
  font-size: 14px;
}

.case-key-facts code,
.draft-decision code,
.decision-panel__draft code,
.issue-list code,
.remedy-action header code,
.notification-list code {
  display: block;
  margin-top: 3px;
  color: #788490;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 9px;
}

.case-references {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}

.case-references span {
  padding: 5px 7px;
  color: #6a7581;
  background: #f7faff;
  border: 1px solid #dfe7f2;
  border-radius: 10px;
  font-size: 9px;
}

.case-references code {
  margin-left: 3px;
  color: #455461;
}

.review-subsection {
  padding-top: 18px;
  margin-top: 18px;
  border-top: 1px solid #e3eaf4;
}

.review-subsection > header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 10px;
}

.review-subsection > header h3 {
  margin: 0;
  color: #2d3b47;
  font-size: 14px;
}

.review-subsection > header span {
  color: #7f8a96;
  font-size: 9px;
}

.claim-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.claim-item,
.evidence-row,
.policy-list article,
.remedy-action {
  padding: 14px;
  background: linear-gradient(135deg, #fff, #f9fbff);
  border: 1px solid #dfe8f4;
  border-radius: 18px;
  box-shadow: 0 8px 22px #58779b0b;
}

.claim-item strong {
  display: inline-block;
  padding: 3px 6px;
  color: #536bc0;
  background: #eef3ff;
  border: 1px solid #dbe4fb;
  border-radius: 999px;
  font-size: 10px;
}

.claim-item p {
  margin: 8px 0 0;
  color: #4d5b68;
  font-size: 12px;
  line-height: 1.65;
}

.issue-list,
.focus-list,
.notification-list {
  display: grid;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.issue-list li {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 0;
  border-bottom: 1px solid #e6eaee;
}

.issue-list li:last-child {
  border-bottom: 0;
}

.issue-list li > span {
  display: grid;
  width: 25px;
  height: 25px;
  flex: 0 0 25px;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #8294e5, #72bba9);
  border-radius: 10px;
  font-size: 9px;
  font-weight: 800;
}

.issue-list strong {
  color: #3c4a57;
  font-size: 12px;
  line-height: 1.6;
}

.focus-list li {
  position: relative;
  padding-left: 16px;
  color: #4d5c69;
  font-size: 12px;
  line-height: 1.65;
}

.focus-list li::before {
  position: absolute;
  top: 0.7em;
  left: 1px;
  width: 5px;
  height: 5px;
  background: #6f86d8;
  border-radius: 50%;
  content: "";
}

.empty-state {
  padding: 16px;
  margin: 0;
  color: #84909b;
  background: #f8fbff;
  border: 1px dashed #d7e3f1;
  border-radius: 16px;
  font-size: 11px;
  text-align: center;
}

.evidence-matrix,
.policy-list,
.remedy-actions {
  display: grid;
  gap: 10px;
}

.evidence-row[data-missing="true"] {
  border-left: 3px solid #daa74a;
}

.evidence-row > header,
.remedy-action > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.evidence-row > header > div {
  display: grid;
  gap: 3px;
}

.evidence-row > header code {
  color: #77838f;
  font-size: 9px;
}

.evidence-row > header strong {
  color: #30404d;
  font-size: 13px;
  line-height: 1.5;
}

.evidence-row > header > span {
  flex: 0 0 auto;
  padding: 3px 6px;
  color: #36775f;
  background: #eaf8f1;
  border: 1px solid #cfeadb;
  border-radius: 999px;
  font-size: 9px;
}

.evidence-row[data-missing="true"] > header > span {
  color: #8d521a;
  background: #fff1dc;
}

.evidence-confidence {
  display: grid;
  grid-template-columns: auto minmax(90px, 1fr) auto;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: #7b8691;
  font-size: 9px;
}

.evidence-confidence > div {
  height: 5px;
  overflow: hidden;
  background: #e8eef7;
  border-radius: 999px;
}

.evidence-confidence i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #7e93e5, #69c2a4);
  border-radius: inherit;
}

.evidence-confidence strong {
  color: #495763;
  font-size: 10px;
}

.evidence-links {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.evidence-links > div {
  padding: 9px;
  background: #f7faff;
  border: 1px solid #e3eaf4;
  border-radius: 13px;
}

.evidence-links dt {
  color: #7a8590;
  font-size: 9px;
}

.evidence-links dd {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 6px 0 0;
  color: #78838f;
  font-size: 10px;
}

.evidence-links code {
  padding: 3px 5px;
  color: #3e5b68;
  background: #fff;
  border: 1px solid #dce6f1;
  border-radius: 8px;
  font-size: 9px;
}

.evidence-analysis,
.policy-list p,
.draft-decision > p,
.review-attention li,
.remedy-action__note {
  color: #52616e;
  font-size: 11px;
  line-height: 1.7;
}

.evidence-analysis {
  padding-top: 10px;
  margin: 10px 0 0;
  border-top: 1px solid #e4e8ec;
}

.evidence-mapped-text {
  line-height: inherit;
}

.evidence-mapped-text :deep(.evidence-inline-status) {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 1px 7px;
  margin: 0 2px;
  border: 1px solid #dce3eb;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 750;
  line-height: 1.3;
  vertical-align: 1px;
  white-space: nowrap;
}

.evidence-mapped-text :deep(.evidence-inline-status[data-tone="neutral"]) {
  color: #667587;
  background: #f1f4f8;
}

.evidence-mapped-text :deep(.evidence-inline-status[data-tone="verified"]) {
  color: #2f735b;
  background: #eaf8f1;
  border-color: #cfeadb;
}

.evidence-mapped-text :deep(.evidence-inline-status[data-tone="partial"]) {
  color: #486985;
  background: #edf5fb;
  border-color: #d2e4f1;
}

.evidence-mapped-text :deep(.evidence-inline-status[data-tone="warning"]) {
  color: #8d5a1e;
  background: #fff3df;
  border-color: #efd9b3;
}

.evidence-mapped-text :deep(.evidence-inline-status[data-tone="review"]) {
  color: #6e5799;
  background: #f2edff;
  border-color: #ddd2f5;
}

.evidence-mapped-text :deep(.evidence-inline-status[data-tone="danger"]) {
  color: #944a52;
  background: #fff0f2;
  border-color: #efd3d8;
}

.policy-list article {
  border-left: 3px solid #8197e4;
}

.policy-list strong {
  color: #344b60;
  font-size: 12px;
}

.policy-list p {
  margin: 6px 0 0;
}

.packet-cards__draft {
  border-top: 0;
  padding: 14px;
  margin-top: 18px;
  background: linear-gradient(135deg, #f4f7ff, #f2fbf7);
  border: 1px solid #dbe7f1;
  border-left: 4px solid #7f92df;
  border-radius: 18px;
}

.draft-decision {
  display: grid;
  grid-template-columns: minmax(170px, 0.35fr) minmax(0, 1fr);
  gap: 18px;
}

.draft-decision > div {
  display: grid;
  align-content: start;
  gap: 3px;
  padding-right: 16px;
  border-right: 1px solid #dae4ef;
}

.draft-decision > div > span {
  color: #71817f;
  font-size: 9px;
}

.draft-decision > div > strong {
  color: #526bc0;
  font-size: 17px;
}

.draft-decision > p {
  margin: 0;
}

.review-attention {
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid #d4e0de;
}

.review-attention > strong {
  color: #695235;
  font-size: 11px;
}

.review-attention ul {
  display: grid;
  gap: 6px;
  padding-left: 17px;
  margin: 7px 0 0;
}

.review-attention li {
  color: #665945;
}

.remedy-action > header > span {
  display: grid;
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #7d90df, #66b79f);
  border-radius: 10px;
  font-size: 9px;
  font-weight: 800;
}

.remedy-action > header > div {
  flex: 1;
}

.remedy-action > header strong {
  color: #2f3f4c;
  font-size: 13px;
}

.remedy-action > header i {
  flex: 0 0 auto;
  padding: 3px 6px;
  color: #496654;
  background: #edf5ef;
  border-radius: 999px;
  font-size: 9px;
  font-style: normal;
}

.remedy-action > header i[data-risk="HIGH"],
.remedy-action > header i[data-risk="CRITICAL"] {
  color: var(--review-danger);
  background: var(--review-danger-soft);
}

.remedy-action__facts,
.remedy-action__parameters {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.remedy-action__facts > div,
.remedy-action__parameters > div {
  display: grid;
  gap: 3px;
  padding: 8px;
  background: #f7faff;
  border: 1px solid #e3eaf4;
  border-radius: 12px;
}

.remedy-action__conditions {
  margin-top: 12px;
}

.remedy-action__conditions > span {
  color: #7a8591;
  font-size: 9px;
}

.remedy-action__conditions p {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 6px 0 0;
}

.remedy-action__conditions code {
  padding: 4px 6px;
  color: #4e5e6c;
  background: #f4f7ff;
  border: 1px solid #dce5f2;
  border-radius: 9px;
  font-size: 8px;
}

.remedy-action__note {
  margin: 10px 0 0;
}

.notification-list {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.notification-list li {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 3px 7px;
  padding: 9px;
  background: #f7faff;
  border: 1px solid #e3eaf4;
  border-radius: 13px;
}

.notification-list li > span {
  display: grid;
  width: 18px;
  height: 18px;
  grid-row: 1 / span 2;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #7991df, #66bba2);
  border-radius: 50%;
  font-size: 9px;
}

.notification-list strong {
  color: #41505d;
  font-size: 10px;
}

.notification-list code {
  grid-column: 2;
}

.decision-panel {
  padding: 4px 2px 8px;
}

.decision-panel > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 12px;
  border-bottom: 1px solid #e2eaf4;
}

.decision-panel > header span {
  color: #7186aa;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: .14em;
}

.decision-panel > header h2 {
  margin: 3px 0 0;
  color: #34435c;
  font-size: 17px;
}

.decision-panel > header i {
  padding: 5px 9px;
  color: #526bc0;
  background: #eef3ff;
  border: 1px solid #dbe4fb;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
}

.decision-panel__draft {
  display: grid;
  gap: 3px;
  padding: 12px 14px;
  margin-top: 12px;
  background: linear-gradient(135deg, #f4f7ff, #f2fbf7);
  border: 1px solid #dce7f2;
  border-left: 3px solid #8197e4;
  border-radius: 14px;
}

.decision-panel__draft > span {
  color: #77848f;
  font-size: 9px;
}

.decision-panel__draft > strong {
  color: #4f68bd;
  font-size: 14px;
}

.decision-dock {
  display: grid;
  gap: 12px;
  margin-top: 12px;
}

.decision-reason {
  position: relative;
  display: grid;
  gap: 6px;
}

.decision-reason > span {
  color: #53647d;
  font-size: 11px;
  font-weight: 750;
}

.decision-reason b {
  margin-left: 4px;
  color: var(--review-danger);
  font-size: 9px;
}

.decision-reason textarea,
.plan-editor textarea {
  width: 100%;
  padding: 10px;
  color: #34435c;
  background: #f8fbff;
  border: 1px solid #dbe6f2;
  border-radius: 14px;
  outline: none;
  resize: vertical;
  font-size: 11px;
  line-height: 1.55;
}

.decision-reason textarea:focus,
.plan-editor textarea:focus {
  border-color: #91a6e8;
  box-shadow: 0 0 0 3px #748be326;
}

.decision-reason > small {
  position: absolute;
  right: 7px;
  bottom: 6px;
  padding-left: 5px;
  color: #9099a3;
  background: #fff;
  font-size: 10px;
}

.decision-actions {
  display: grid;
  gap: 7px;
}

.decision-actions > button {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  min-height: 46px;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  color: #4d5f7b;
  background: #f9fbff;
  border: 1px solid #dce6f2;
  border-radius: 14px;
  cursor: pointer;
  text-align: left;
}

.decision-actions > button:hover {
  border-color: #b7c6e8;
  background: #f1f5ff;
}

.decision-actions > button > span:first-child {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: #5d72c6;
  background: #edf2ff;
  border-radius: 9px;
  font-size: 12px;
  font-weight: 900;
}

.decision-actions > button > span:last-child {
  display: grid;
  gap: 2px;
}

.decision-actions > button strong {
  font-size: 11px;
}

.decision-actions > button small {
  color: #818b95;
  font-size: 10px;
}

.decision-actions > .decision-action--primary {
  color: #fff;
  background: linear-gradient(135deg, #5b91d7, #62b99b);
  border-color: #69a9b3;
  box-shadow: 0 10px 24px #4d83b52b;
}

.decision-actions > .decision-action--primary:hover {
  background: linear-gradient(135deg, #4e83cb, #53aa8d);
  border-color: #579aa8;
}

.decision-actions > .decision-action--primary > span:first-child {
  color: #fff;
  background: #ffffff1f;
}

.decision-actions > .decision-action--primary small {
  color: #e8f6f3;
}

.decision-actions--approval > button:not(.decision-action--primary) {
  color: #735b2f;
  background: #fffaf0;
  border-color: #eddfbd;
}

.decision-actions--approval > button:not(.decision-action--primary) > span:first-child {
  color: #9a6a18;
  background: #fff1cf;
}

.decision-actions--exception {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding-top: 11px;
  border-top: 1px solid #e2eaf4;
}

.decision-actions--exception > span {
  grid-column: 1 / -1;
  color: #7d8792;
  font-size: 9px;
}

.decision-actions--exception > button {
  grid-template-columns: 1fr;
  min-height: 70px;
  align-content: start;
  gap: 5px;
  padding: 8px;
}

.decision-actions--exception > button > span:first-child {
  width: 22px;
  height: 22px;
}

.decision-actions--exception > button small {
  line-height: 1.35;
}

.decision-actions--exception > .decision-action--danger {
  color: var(--review-danger);
  background: #fff5f7;
  border-color: #efd4dc;
}

.decision-actions--exception > button[data-decision="REQUEST_MORE_EVIDENCE"] {
  color: #526bc0;
  background: #f4f7ff;
  border-color: #dbe4fb;
}

.decision-actions--exception > button[data-decision="ESCALATE_MANUAL"] {
  color: #69568f;
  background: #f8f4ff;
  border-color: #e3d8f5;
}

.plan-editor {
  display: grid;
  gap: 8px;
  padding: 10px;
  background: #f7f9ff;
  border: 1px solid #dce6f3;
  border-radius: 16px;
}

.plan-editor > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.plan-editor > header strong {
  font-size: 11px;
}

.plan-editor > header button {
  width: 24px;
  height: 24px;
  padding: 0;
  color: #69747f;
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 16px;
}

.plan-editor textarea {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 10px;
}

.plan-editor > button {
  min-height: 44px;
  color: #fff;
  background: linear-gradient(135deg, #7188d8, #5fae95);
  border: 0;
  border-radius: 11px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 750;
}

.decision-error {
  padding: 8px;
  margin: 10px 0 0;
  color: #943f3f;
  background: #fff3f5;
  border: 1px solid #efd5dc;
  border-left: 3px solid #d36b7d;
  border-radius: 12px;
  font-size: 10px;
  line-height: 1.5;
}

.decision-readonly,
.decision-success {
  padding: 11px;
  margin-top: 12px;
  border-radius: 14px;
  font-size: 11px;
  line-height: 1.6;
}

.decision-readonly {
  color: #5f6873;
  background: #f2f4f6;
  border: 1px solid #dce1e5;
}

.decision-success {
  color: #225f47;
  background: #eaf7ef;
  border: 1px solid #cee8d8;
}

.decision-success strong {
  font-size: 12px;
}

.decision-success p {
  margin: 3px 0 0;
  font-size: 10px;
}

.decision-confirm-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  background: #1d2935a8;
  backdrop-filter: blur(3px);
}

.decision-confirm {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  width: min(460px, 100%);
  gap: 13px;
  padding: 18px;
  background: #fff;
  border: 1px solid #d6dce1;
  border-radius: 24px;
  box-shadow: 0 28px 80px #1d29353d;
  box-shadow: 0 24px 70px #16202a4d;
}

.decision-confirm__icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  color: #8a561d;
  background: #fff1d9;
  border-radius: 50%;
  font-size: 16px;
  font-weight: 900;
}

.decision-confirm > div > span {
  color: #8a743f;
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.decision-confirm h2 {
  margin: 4px 0 0;
  color: #273440;
  font-size: 18px;
}

.decision-confirm p {
  margin: 7px 0 0;
  color: #687480;
  font-size: 11px;
  line-height: 1.55;
}

.decision-confirm dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 13px 0 0;
  border-top: 1px solid #e1e5e9;
  border-left: 1px solid #e1e5e9;
}

.decision-confirm dl > div {
  padding: 8px;
  border-right: 1px solid #e1e5e9;
  border-bottom: 1px solid #e1e5e9;
}

.decision-confirm dt {
  color: #89939d;
  font-size: 8px;
}

.decision-confirm dd {
  margin: 3px 0 0;
  color: #3f4d59;
  font-size: 10px;
  font-weight: 700;
}

.decision-confirm__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}

.decision-confirm__actions button {
  min-height: 44px;
  padding: 0 13px;
  color: #53606d;
  background: #fff;
  border: 1px solid #ccd3d9;
  border-radius: 12px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 750;
}

.decision-confirm__actions button:last-child {
  color: #fff;
  background: linear-gradient(135deg, #7188d8, #5fae95);
  border-color: #6d9eb3;
}

.decision-confirm__actions button:disabled {
  cursor: wait;
  opacity: 0.6;
}

@keyframes review-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1120px) {
  .review-audit__content {
    grid-template-columns: 1fr;
  }
}

@container room-workspace (max-width: 1099px) {
  .review-triple-layout {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-areas:
      "chat operation"
      "materials materials"
      "audit audit";
  }

  .review-explain-room,
  .review-operation-room {
    height: 700px;
  }
}

@media (max-width: 760px) {
  .review-triple-layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      "chat"
      "materials"
      "operation"
      "audit";
  }

  .review-workbench__timing,
  .review-panel__header {
    align-items: stretch;
    flex-direction: column;
  }

  .review-workbench__timing {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .review-case-strip {
    align-items: stretch;
    flex-direction: column;
  }

  .review-case-strip__version {
    padding: 12px 0 0;
    border-top: 1px solid #dce4e9;
    border-left: 0;
    text-align: left;
  }

  .review-explain-room {
    height: 660px;
    grid-template-rows: auto auto minmax(0, 1fr);
    padding: 12px;
    border-radius: 24px;
  }

  .review-operation-room {
    height: 660px;
    grid-template-rows: auto minmax(0, 1fr);
    padding: 12px;
    border-radius: 24px;
  }

  .review-document {
    height: 660px;
  }

  .review-explain-room__header,
  .review-operation-room__header {
    height: auto;
    min-height: 112px;
  }

  .review-explain-room__header {
    grid-template-columns: 42px minmax(0, 1fr);
  }

  .review-explain-room__header small {
    grid-column: 2;
    justify-self: start;
  }

  .review-operation-room__header {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      "eyebrow"
      "title"
      "description"
      "badge";
  }

  .review-operation-room__header small {
    justify-self: start;
  }

  .review-audit__versions {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .review-panel {
    padding: 15px;
  }

  .review-panel__metrics {
    justify-content: flex-start;
  }

  .review-panel__metrics > div {
    text-align: left;
  }

  .case-key-facts,
  .claim-list,
  .evidence-links,
  .draft-decision,
  .remedy-action__facts,
  .remedy-action__parameters,
  .notification-list {
    grid-template-columns: 1fr;
  }

  .draft-decision > div {
    padding: 0 0 10px;
    border-right: 0;
    border-bottom: 1px solid #d4e0de;
  }

  .review-risk-strip {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}

@media (max-width: 480px) {
  .review-workbench__timing,
  .review-operation-room .decision-actions--approval,
  .decision-actions--exception,
  .decision-confirm dl {
    grid-template-columns: 1fr;
  }

  .review-tabs {
    padding: 0 5px;
  }

  .review-tabs button {
    padding: 0 10px;
  }

  .review-tabs button[aria-selected="true"]::after {
    right: 10px;
    left: 10px;
  }

  .decision-actions--exception > button {
    grid-template-columns: 26px minmax(0, 1fr);
    min-height: 46px;
  }

  .decision-confirm {
    grid-template-columns: 1fr;
  }

}

@media (prefers-reduced-motion: reduce) {
  .review-loading > span {
    animation: none;
  }
}
</style>
