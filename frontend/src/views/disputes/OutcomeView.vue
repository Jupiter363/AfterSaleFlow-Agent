<!--
  文件作用：前端页面视图文件，组织售后争议对应页面的数据加载、交互和展示。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  extractAgentRunDescriptor,
  loadActiveAgentRuns,
} from "../../api/agentStream";
import { disputeApi } from "../../api/disputes";
import AgentStreamErrorDialog from "../../components/room/AgentStreamErrorDialog.vue";
import AgentStreamingMessage from "../../components/room/AgentStreamingMessage.vue";
import { actor } from "../../state/actor";
import {
  activeAgentStreams,
  clearAgentStreams,
  consumeAgentRun,
} from "../../stores/agentStream";
import { humanizeDossierText } from "../../utils/displayText";

const props = defineProps({
  initialOutcome: { type: Object, default: null },
});

const route = useRoute();
const router = useRouter();
const outcome = ref(props.initialOutcome);
const error = ref("");
const streamError = ref("");
const reviewReason = ref("审核员确认 AI 裁决草案。");
const reviewPlanDraft = ref({
  id: "",
  handling_direction: "",
  execution_plan: "",
  actions: [],
});
const reviewBusy = ref(false);
const reviewStatus = ref("");
const executionAssistantState = ref("idle");
let executionAssistantTimer = null;
let executionAssistantCaseId = "";
const outcomeStreamAbortController = new AbortController();
const DRAFT_STREAM_OPERATIONS = new Set(["HEARING_ANALYSIS", "HEARING_STAGE"]);
const caseId = computed(
  () => outcome.value?.case_id || route.params.caseId,
);
const outcomeDraftStreamingRuns = computed(() =>
  activeAgentStreams({
    caseId: caseId.value,
    roomType: "HEARING",
    actorId: actor.id,
    actorRole: actor.role,
  }).filter((run) => DRAFT_STREAM_OPERATIONS.has(run.operation)),
);
const actions = computed(() => outcome.value?.actions || []);
const persistedDecision = computed(
  () => outcome.value?.final_decision || outcome.value?.finalDecision || null,
);
const rawDecision = computed(
  () => persistedDecision.value || {},
);
const adjudicationDraft = computed(
  () => outcome.value?.adjudication_draft || outcome.value?.adjudicationDraft || null,
);
const caseStatus = computed(() => outcome.value?.case_status || outcome.value?.caseStatus || "");
const isFinalOutcome = computed(() => {
  const decision = rawDecision.value || {};
  return Boolean(
    decision.human_confirmed ||
      decision.humanConfirmed ||
      caseStatus.value === "CLOSED" ||
      outcome.value?.closed_at ||
      outcome.value?.closedAt,
  );
});
const isDraftOutcome = computed(
  () =>
    !isFinalOutcome.value &&
    Boolean(
      adjudicationDraft.value ||
        caseStatus.value === "WAITING_HUMAN_REVIEW" ||
        caseStatus.value === "HEARING_COMPLETED",
    ),
);
const hasPersistedDecision = computed(() => {
  const source = persistedDecision.value || {};
  const conclusion = String(source.conclusion || "").trim();
  const explanation = String(source.explanation || "").trim();
  if (!conclusion && !explanation) return false;
  const pendingConclusion =
    !conclusion ||
    /^(?:处理结果|裁决草案|裁决结论|结论)?\s*(?:待生成|尚未生成|生成中)$/.test(conclusion);
  const placeholderExplanation =
    !explanation ||
    /以后续确认记录为准|当前(?:没有|暂无)可展示|尚未生成|正在生成|待生成/.test(explanation);
  return !(pendingConclusion && placeholderExplanation);
});
const hasRenderableOutcome = computed(() =>
  isFinalOutcome.value || Boolean(adjudicationDraft.value) || hasPersistedDecision.value,
);
const draftGenerationActive = computed(() => outcomeDraftStreamingRuns.value.length > 0);
const canReviewOutcomeDraft = computed(
  () => actor.role === "PLATFORM_REVIEWER" && isDraftOutcome.value,
);
const shouldShowExecutionAssistant = computed(
  () =>
    isFinalOutcome.value &&
    actions.value.length === 0 &&
    Boolean(rawDecision.value?.human_confirmed || rawDecision.value?.humanConfirmed),
);
const canModifyReviewPlan = computed(
  () =>
    Boolean(
      String(reviewPlanDraft.value.handling_direction || "").trim() ||
        String(reviewPlanDraft.value.execution_plan || "").trim(),
    ),
);
const decision = computed(() => {
  const source = rawDecision.value || {};
  return {
    ...source,
    conclusion: localizeOutcomeCopy(
      source.conclusion || (isDraftOutcome.value ? "AI 裁决草案已生成" : ""),
    ),
    explanation: localizeOutcomeCopy(
      source.explanation ||
        (isDraftOutcome.value ? "此处展示的是 AI 生成的非最终裁决草案。" : ""),
    ),
    review_reason: localizeOutcomeCopy(source.review_reason || source.reviewReason || ""),
  };
});
const heroSealText = computed(() => {
  if (isFinalOutcome.value) return "最终";
  if (!hasRenderableOutcome.value) return draftGenerationActive.value ? "生成" : "等待";
  return "草案";
});
const heroKicker = computed(() =>
  isFinalOutcome.value
    ? "最终结果已确认"
    : !hasRenderableOutcome.value
      ? draftGenerationActive.value ? "实时生成" : "等待裁决草案"
      : "裁决草案已生成",
);
const heroTitle = computed(() => {
  if (isFinalOutcome.value) return "最终裁决";
  if (!hasRenderableOutcome.value) {
    return draftGenerationActive.value ? "法官正在生成裁决草案" : "裁决草案尚未生成";
  }
  return "AI 裁决草案";
});
const heroStatusTitle = computed(() => {
  if (isFinalOutcome.value) return "裁决已生效";
  if (!hasRenderableOutcome.value) return draftGenerationActive.value ? "正在生成" : "等待法官处理";
  return "等待后续确认";
});
const heroStatusDetail = computed(() => {
  if (isFinalOutcome.value) return outcome.value?.closed_at || "执行结果持续同步中";
  if (!hasRenderableOutcome.value) {
    return draftGenerationActive.value
      ? "模型输出完成并校验入库后展示正式草案"
      : "庭审记录封存后由 AI 法官生成";
  }
  return "非最终结果，确认后形成最终处理结果";
});
const verdictBoundaryText = computed(() => {
  if (isFinalOutcome.value && decision.value.human_confirmed) {
    return "此处展示的是平台审核员确认后的最终裁决，不是 AI 草案。";
  }
  if (isFinalOutcome.value) {
    return "此处展示的是已生效的系统结案结果。";
  }
  return "此处展示的是 AI 生成的非最终裁决草案，后续确认后才会形成最终结果。";
});
const draftStructureIntro = computed(() =>
  isDraftOutcome.value
    ? "以下为 AI 法官草案的结构化依据，最终以后续确认为准。"
    : "以下为本次处理结果的结构化依据。",
);
const draftCardSummary = computed(() => {
  const draftText = localizeOutcomeCopy(
    adjudicationDraft.value?.draft_text ||
      adjudicationDraft.value?.draftText ||
      "",
  );
  if (!draftText) return draftStructureIntro.value;
  const explanation = localizeOutcomeCopy(decision.value?.explanation || "");
  if (isSameDraftCopy(draftText, explanation)) return draftStructureIntro.value;
  return draftText;
});
const draftRecommendedDecision = computed(() =>
  localizeOutcomeCopy(
    adjudicationDraft.value?.recommended_decision ||
      adjudicationDraft.value?.recommendedDecision ||
      "待生成",
  ),
);
const draftRecommendedDecisionDisplay = computed(() => {
  const recommendation = draftRecommendedDecision.value;
  if (
    recommendation.length > 48 &&
    isSameDraftCopy(recommendation, decision.value?.conclusion)
  ) {
    return "同上方白话结论一致";
  }
  return recommendation;
});
const reviewerAttentionHeading = computed(() =>
  isDraftOutcome.value ? "后续确认关注" : "审核关注",
);
const executionBoardKicker = computed(() =>
  isFinalOutcome.value ? "执行回执" : "后续轨迹",
);
const executionBoardTitle = computed(() =>
  isFinalOutcome.value ? "裁决落地轨迹" : "确认与执行轨迹",
);
const executionBoardCountText = computed(() => {
  if (shouldShowExecutionAssistant.value) {
    return executionAssistantState.value === "succeeded"
      ? "模拟执行完成"
      : "执行专员助手";
  }
  return isFinalOutcome.value
    ? `${actions.value.length} 项执行动作`
    : `${actions.value.length} 项后续动作`;
});
const executionBoardEmptyText = computed(() =>
  isFinalOutcome.value
    ? "执行回执正在路上，请稍后刷新。"
    : "尚未产生执行动作，等待后续确认后同步处理结果。",
);
const footerNoticeText = computed(() =>
  isFinalOutcome.value
    ? "裁决、执行成功或异常都会同步进入双方平台信箱。"
    : "草案、后续确认结果或执行异常都会同步进入双方平台信箱。",
);

const actionLabels = {
  REFUND: "退款",
  COMPENSATE: "补偿",
  NOTIFY_MERCHANT: "通知商家",
  NOTIFY_USER: "通知用户",
  CLOSE_CASE: "关闭案件",
};
const statusLabels = {
  SUCCEEDED: "执行成功",
  PENDING: "等待执行",
  RUNNING: "执行中",
  FAILED: "执行失败",
  MANUAL_REQUIRED: "等待人工处理",
};
const resultLabels = {
  amount: "金额",
  currency: "币种",
  delivered: "已送达",
  operation: "操作",
  reference_id: "引用号",
  simulated: "模拟执行",
  tool_name: "工具",
  logistics_record: "物流记录",
  logistics_records: "物流记录",
  logisticsRecord: "物流记录",
  logisticsRecords: "物流记录",
  "response.action_type": "动作类型",
  "response.idempotency_key": "幂等键",
  "response.status": "回执状态",
  status: "状态",
};

// 业务位置：【前端处理结果】resultValue：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function resultValue(value) {
  if (value === null || value === undefined || value === "") return "未提供";
  if (typeof value === "boolean") return value ? "是" : "否";
  return String(value);
}

// 业务位置：【前端处理结果】flattenResult：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function flattenResult(value, prefix = "") {
  if (value === null || value === undefined || value === "") return [];
  if (Array.isArray(value)) {
    return value.flatMap((item, index) =>
      flattenResult(item, prefix ? `${prefix}.${index + 1}` : `结果 ${index + 1}`),
    );
  }
  if (typeof value === "object") {
    return Object.entries(value).flatMap(([key, item]) =>
      flattenResult(item, prefix ? `${prefix}.${key}` : key),
    );
  }
  return [
    {
      label: resultLabels[prefix] || prefix,
      value: resultValue(value),
    },
  ];
}

// 业务位置：【前端处理结果】executionResultRows：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function executionResultRows(action) {
  return flattenResult(action.result);
}

// 业务位置：【前端处理结果】sanitizeOutcomeCopy：核验 阶段处理结果或草案 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function sanitizeOutcomeCopy(value) {
  if (value === null || value === undefined || value === "") return "";
  const raw = String(value);
  if (
    raw.includes("复核模型兜底生成原因") ||
    raw.includes("Unprocessable Entity") ||
    raw.includes("INVALID_ARGUMENT")
  ) {
    return "复核模型曾触发兜底生成，后续确认环节需重点复核草案完整性。";
  }
  return raw
    .replace(/\bNEEDS_HUMAN_REVIEW\b/g, "待人工复核")
    .replace(/\bPARTIALLY_VERIFIED\b/g, "部分核验")
    .replace(/\bVERIFIED\b/g, "已核验")
    .replace(/\bUNVERIFIED\b/g, "待核验")
    .replace(/\bSIGNED_NOT_RECEIVED\b/g, "物流显示签收但用户称未收到包裹")
    .replace(/\b(ISSUE|FACT|EVIDENCE|EVD|DRAFT|A2A)_[A-Za-z0-9_-]+\b/g, "相关材料")
    .replaceAll("裁决草案已经进入平台审核入口", "裁决草案已生成")
    .replaceAll("进入平台终审，等待审核员确认最终结果", "查看裁决草案并等待后续确认")
    .replaceAll("最终由平台审核员确认", "后续进入确认流程")
    .replaceAll("最终结果仍需平台审核确认", "最终结果以后续确认为准")
    .replaceAll("最终结果仍需平台审核员确认", "最终结果以后续确认为准")
    .replaceAll("等待平台审核员确认", "等待后续确认")
    .replaceAll("希望平台审核员给出", "希望后续确认环节给出")
    .replaceAll("平台审核员确认", "后续确认")
    .replaceAll("审核员确认", "后续确认")
    .replaceAll("平台审核确认", "后续确认")
    .replaceAll("平台审核员", "后续确认环节")
    .replaceAll("平台终审", "后续确认")
    .replaceAll("审核员终审", "后续确认");
}

// 业务位置：【前端处理结果】localizeOutcomeCopy：将 阶段处理结果或草案 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function localizeOutcomeCopy(value) {
  if (value === null || value === undefined || value === "") return "";
  return sanitizeOutcomeCopy(humanizeDossierText(value, { fallback: "" }));
}

// 业务位置：【前端处理结果】isSameDraftCopy：判断 阶段处理结果或草案 是否满足当前流程分支的进入条件。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function isSameDraftCopy(left, right) {
  const normalizedLeft = String(left || "").replace(/\s+/g, "");
  const normalizedRight = String(right || "").replace(/\s+/g, "");
  if (!normalizedLeft || !normalizedRight) return false;
  return (
    normalizedLeft === normalizedRight ||
    normalizedLeft.includes(normalizedRight) ||
    normalizedRight.includes(normalizedLeft)
  );
}

const INTERNAL_DRAFT_KEYS = new Set([
  "id",
  "issue_id",
  "issueId",
  "fact_id",
  "factId",
  "evidence_id",
  "evidenceId",
]);

const DRAFT_VALUE_LABELS = {
  NEEDS_HUMAN_REVIEW: "待人工复核",
  REQUIRES_HUMAN_REVIEW: "需人工复核",
  PENDING_HUMAN_REVIEW: "待人工复核",
  PENDING_POLICY_REVIEW: "待规则复核",
  PENDING_REVIEW: "待复核",
  WAITING_HUMAN_REVIEW: "等待人工复核",
  HUMAN_REVIEW: "人工复核",
  POLICY_REVIEW: "规则复核",
  PARTIALLY_VERIFIED: "部分核验",
  VERIFIED: "已核验",
  UNVERIFIED: "待核验",
  SUPPORTED: "已有材料支持",
  UNSUPPORTED: "缺少有效支持",
  HIGH: "高",
  MEDIUM: "中",
  LOW: "低",
  true: "是",
  false: "否",
};

// 业务位置：【前端处理结果】draftValue：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function draftValue(value) {
  if (value === null || value === undefined || value === "") return "未提供";
  if (Array.isArray(value)) {
    const items = value.map(draftValue).filter(Boolean);
    return items.length ? items.join("、") : "";
  }
  if (typeof value === "object") {
    return Object.entries(value)
      .filter(([key, item]) => shouldShowDraftField(key, item))
      .map(([key, item]) => {
        const formatted = draftValue(item);
        return formatted ? `${draftFieldLabel(key)}：${formatted}` : "";
      })
      .filter(Boolean)
      .join("；");
  }
  if (typeof value === "boolean") return value ? "是" : "否";
  const raw = String(value);
  if (isInternalDraftIdentifier(raw)) return "";
  if (DRAFT_VALUE_LABELS[raw] !== undefined) return DRAFT_VALUE_LABELS[raw];
  return localizeOutcomeCopy(value);
}

// 业务位置：【前端处理结果】shouldShowDraftField：判断 阶段处理结果或草案 是否满足当前流程分支的进入条件。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function shouldShowDraftField(key, value) {
  if (INTERNAL_DRAFT_KEYS.has(key)) return false;
  if (value === null || value === undefined || value === "") return false;
  if (Array.isArray(value)) return value.some((item) => draftValue(item));
  return Boolean(draftValue(value));
}

// 业务位置：【前端处理结果】isInternalDraftIdentifier：判断 阶段处理结果或草案 是否满足当前流程分支的进入条件。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function isInternalDraftIdentifier(value) {
  return /^(ISSUE|FACT|EVIDENCE|EVD|DRAFT|A2A|CASE)_[A-Za-z0-9_-]+$/.test(value);
}

// 业务位置：【前端处理结果】draftFieldLabel：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function draftFieldLabel(key) {
  return (
    {
      fact: "事实",
      conclusion: "结论",
      support_level: "支持程度",
      assessment: "采信意见",
      rule: "规则",
      application: "适用说明",
      source: "来源",
      logistics_record: "物流记录",
      logistics_records: "物流记录",
      logisticsRecord: "物流记录",
      logisticsRecords: "物流记录",
      confidence: "可信度",
      risk_flag: "风险提示",
      policy_basis: "规则依据",
      policyBasis: "规则依据",
      evidence_basis: "证据依据",
      evidenceBasis: "证据依据",
      suggested_finding: "认定意见",
      suggestedFinding: "认定意见",
      verification_status: "核验状态",
      verificationStatus: "核验状态",
      supported_by: "支持材料",
      supportedBy: "支持材料",
      contradicted_by: "反向材料",
      contradictedBy: "反向材料",
      missing_evidence: "仍需补证",
      missingEvidence: "仍需补证",
      neutral_analysis: "中立分析",
      neutralAnalysis: "中立分析",
    }[key] || key
  );
}

// 业务位置：【前端处理结果】draftList：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function draftList(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

// 业务位置：【前端处理结果】draftConfidenceScore：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function draftConfidenceScore(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "待评分";
  const score = numeric <= 1 ? numeric * 100 : numeric;
  return `${Math.round(score)}/100`;
}

const explanationOfficerNotes = computed(() => {
  if (!isDraftOutcome.value || !adjudicationDraft.value) return null;
  const source =
    adjudicationDraft.value.explanation_officer_notes ||
    adjudicationDraft.value.explanationOfficerNotes ||
    adjudicationDraft.value.hearing_replay ||
    adjudicationDraft.value.hearingReplay ||
    {};
  const replaySummary =
    source.replay_summary ||
    source.replaySummary ||
    adjudicationDraft.value.hearing_replay_summary ||
    adjudicationDraft.value.hearingReplaySummary ||
    "解释员将基于三轮庭审、补证记录、证据矩阵和评审团复核报告复盘草案依据。";
  const finalPlanExplanation =
    source.final_plan_explanation ||
    source.finalPlanExplanation ||
    source.plan_explanation ||
    source.planExplanation ||
    "解释员将围绕事实采信、证据缺口、双方异议和后续确认关注点解释草案依据。";
  const focus =
    source.reviewer_focus ||
    source.reviewerFocus ||
    source.confirmation_focus ||
    source.confirmationFocus ||
    adjudicationDraft.value.reviewer_attention ||
    adjudicationDraft.value.reviewerAttention ||
    [];
  return {
    replaySummary: localizeOutcomeCopy(replaySummary),
    finalPlanExplanation: localizeOutcomeCopy(finalPlanExplanation),
    focus: draftList(focus).map((item) => draftValue(item)),
  };
});

// 业务位置：【前端处理结果】defaultApprovedPlan：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function defaultApprovedPlan() {
  const draft = adjudicationDraft.value || {};
  const plan =
    draft.approved_plan ||
    draft.approvedPlan ||
    draft.remedy_plan ||
    draft.remedyPlan ||
    outcome.value?.approved_plan ||
    outcome.value?.approvedPlan ||
    outcome.value?.remedy_plan ||
    outcome.value?.remedyPlan;
  if (plan) return plan;
  return {
    id: draft.plan_id || draft.planId || "",
    handling_direction:
      draft.recommended_decision || draft.recommendedDecision || "",
    execution_plan: "",
    actions: [],
  };
}

// 业务位置：【前端处理结果】normalizeApprovedPlan：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function normalizeApprovedPlan(plan) {
  const source = plan && typeof plan === "object" ? plan : {};
  const firstAction = Array.isArray(source.actions) ? source.actions[0] : null;
  return {
    id: source.id || source.plan_id || source.planId || "",
    handling_direction:
      source.handling_direction ||
      source.handlingDirection ||
      source.main_direction ||
      source.mainDirection ||
      firstAction?.action_type ||
      firstAction?.actionType ||
      "",
    execution_plan:
      source.execution_plan ||
      source.executionPlan ||
      source.plan_summary ||
      source.planSummary ||
      source.description ||
      "",
    actions: [],
  };
}

// 业务位置：【前端处理结果】syncReviewPlanDraft：围绕 人工审核关注点和陪审团提示 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function syncReviewPlanDraft(force = false) {
  if (
    !force &&
    (reviewPlanDraft.value.id ||
      reviewPlanDraft.value.handling_direction ||
      reviewPlanDraft.value.execution_plan)
  ) {
    return;
  }
  reviewPlanDraft.value = normalizeApprovedPlan(defaultApprovedPlan());
}

// 业务位置：【前端处理结果】refreshOutcomeAfterReview：重新加载 人工审核关注点和陪审团提示，确保页面和下一次 Agent 调用基于最新案件版本。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function refreshOutcomeAfterReview(message) {
  await refreshOutcome();
  reviewStatus.value = message;
  syncReviewPlanDraft(true);
}

// 业务位置：【前端处理结果】refreshOutcome：重新加载 阶段处理结果或草案，确保页面和下一次 Agent 调用基于最新案件版本。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function refreshOutcome() {
  outcome.value = await disputeApi.outcome(actor, caseId.value);
  return outcome.value;
}

// 业务位置：【前端处理结果】confirmOutcomeDraft：执行 阶段处理结果或草案 对应的业务动作，并将结果交给 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function confirmOutcomeDraft() {
  if (reviewBusy.value) return;
  error.value = "";
  reviewStatus.value = "";
  reviewBusy.value = true;
  try {
    await disputeApi.confirmOutcomeDraft(
      actor,
      caseId.value,
      reviewReason.value || "审核员确认 AI 裁决草案。",
    );
    await refreshOutcomeAfterReview("已确认草案，最终处理状态已刷新。");
  } catch (failure) {
    error.value = failure.message;
  } finally {
    reviewBusy.value = false;
  }
}

// 业务位置：【前端处理结果】modifyOutcomeDraftFromStructuredPlan：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function modifyOutcomeDraftFromStructuredPlan() {
  if (reviewBusy.value) return;
  error.value = "";
  reviewStatus.value = "";
  const approvedPlan = buildApprovedPlan();
  reviewBusy.value = true;
  try {
    await disputeApi.modifyOutcomeDraft(
      actor,
      caseId.value,
      reviewReason.value || "审核员修改并确认 AI 裁决草案。",
      approvedPlan,
    );
    await refreshOutcomeAfterReview("已按修改方案确认草案，最终处理状态已刷新。");
  } catch (failure) {
    error.value = failure.message;
  } finally {
    reviewBusy.value = false;
  }
}

// 业务位置：【前端处理结果】buildApprovedPlan：把 审核决定和执行/结案状态 组装为本块需要的 当前阶段业务数据，供 当事人可见的处理结论和后续动作 使用。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function buildApprovedPlan() {
  return {
    id: reviewPlanDraft.value.id,
    handling_direction: reviewPlanDraft.value.handling_direction,
    execution_plan: reviewPlanDraft.value.execution_plan,
    actions: [],
  };
}

// 业务位置：【前端处理结果】clearExecutionAssistantTimer：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function clearExecutionAssistantTimer() {
  if (executionAssistantTimer) {
    clearTimeout(executionAssistantTimer);
    executionAssistantTimer = null;
  }
}

// 业务位置：【前端处理结果】scheduleExecutionAssistantSuccess：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function scheduleExecutionAssistantSuccess() {
  clearExecutionAssistantTimer();
  executionAssistantTimer = setTimeout(() => {
    executionAssistantState.value = "succeeded";
    executionAssistantTimer = null;
  }, 3000);
}

// 业务位置：【前端处理结果】syncExecutionAssistantState：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 审核决定和执行/结案状态 正确进入 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function syncExecutionAssistantState() {
  if (!shouldShowExecutionAssistant.value) {
    clearExecutionAssistantTimer();
    executionAssistantState.value = "idle";
    executionAssistantCaseId = "";
    return;
  }
  if (
    executionAssistantCaseId === caseId.value &&
    executionAssistantState.value !== "idle"
  ) {
    return;
  }
  executionAssistantCaseId = caseId.value;
  executionAssistantState.value = "processing";
  scheduleExecutionAssistantSuccess();
}

watch(
  adjudicationDraft,
  () => {
    syncReviewPlanDraft();
  },
  { immediate: true },
);

watch(
  [shouldShowExecutionAssistant, caseId],
  syncExecutionAssistantState,
  { immediate: true },
);

// 业务位置：【前端处理结果】isDraftStreamDescriptor：判断 Agent 流事件 是否满足当前流程分支的进入条件。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function isDraftStreamDescriptor(value) {
  const descriptor = extractAgentRunDescriptor(value);
  return Boolean(descriptor && DRAFT_STREAM_OPERATIONS.has(descriptor.operation));
}

// 业务位置：【前端处理结果】consumeOutcomeDraftRun：执行 阶段处理结果或草案 对应的业务动作，并将结果交给 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function consumeOutcomeDraftRun(value) {
  const descriptor = extractAgentRunDescriptor(value);
  if (!descriptor || !DRAFT_STREAM_OPERATIONS.has(descriptor.operation)) return false;
  streamError.value = "";
  await consumeAgentRun({
    actor: { id: actor.id, role: actor.role },
    caseId: caseId.value,
    roomType: "HEARING",
    descriptor,
    agentLabel: "AI 法官",
    senderRole: "JUDGE",
    signal: outcomeStreamAbortController.signal,
    onFinal: async () => {
      await refreshOutcome();
      syncReviewPlanDraft(true);
    },
    onError: (failure) => {
      streamError.value = failure.message;
    },
  });
  return true;
}

// 业务位置：【前端处理结果】resumeOutcomeDraftRuns：执行 阶段处理结果或草案 对应的业务动作，并将结果交给 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function resumeOutcomeDraftRuns() {
  if (props.initialOutcome !== null) return false;
  const activeRuns = await loadActiveAgentRuns(actor, caseId.value, "HEARING");
  const draftRuns = (activeRuns || []).filter(isDraftStreamDescriptor);
  if (!draftRuns.length) return false;
  error.value = "";
  await Promise.all(draftRuns.map(consumeOutcomeDraftRun));
  return true;
}

// 业务位置：【前端处理结果】dismissStreamError：切换与 Agent 流事件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function dismissStreamError() {
  const previous = streamError.value;
  streamError.value = "";
  if (error.value === previous) error.value = "";
}

// 业务位置：【前端处理结果】load：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function load() {
  let outcomeFailure = null;
  try {
    if (outcome.value === null) {
      await refreshOutcome();
    }
  } catch (failure) {
    outcomeFailure = failure;
  }
  try {
    const resumed = await resumeOutcomeDraftRuns();
    if (!resumed && outcomeFailure) error.value = outcomeFailure.message;
  } catch (failure) {
    if (!streamError.value) streamError.value = failure.message;
    error.value = failure.message;
  }
}

onMounted(load);
onBeforeUnmount(() => {
  clearExecutionAssistantTimer();
  outcomeStreamAbortController.abort();
  clearAgentStreams({
    caseId: caseId.value,
    roomType: "HEARING",
    actorId: actor.id,
    actorRole: actor.role,
  });
});
</script>

<template>
  <main class="outcome-page">
    <section class="outcome-hero">
      <div class="outcome-hero__seal" aria-hidden="true">
        <span>⚖️</span>
        <i>{{ heroSealText }}</i>
      </div>
      <div>
        <span class="outcome-kicker">{{ heroKicker }}</span>
        <h1>{{ heroTitle }}</h1>
        <p class="outcome-hero__lead">
          <span class="outcome-hero__context">
            裁决草案与最终结果 <i aria-hidden="true">✦</i>
          </span>
          <span>当事人查看裁决说明，平台审核员确认或修改后形成最终结果。</span>
        </p>
      </div>
      <div class="outcome-hero__status">
        <strong>{{ heroStatusTitle }}</strong>
        <small>{{ heroStatusDetail }}</small>
      </div>
    </section>

    <section
      v-if="!hasRenderableOutcome"
      class="outcome-generation"
      data-outcome-draft-generation
      :data-streaming="draftGenerationActive"
    >
      <header>
        <div>
          <span class="outcome-kicker">AI 法官</span>
          <h2>
            {{ draftGenerationActive ? "法官正在生成裁决草案" : "等待法官生成裁决草案" }}
          </h2>
          <p>
            {{ draftGenerationActive
              ? "正在依据庭审记录、证据矩阵和复核意见生成草案；校验入库前不会展示为正式结论。"
              : "庭审记录已封存时，法官会在后台生成草案；当前没有可展示的正式结论。" }}
          </p>
        </div>
        <strong>{{ draftGenerationActive ? "实时输出中" : "尚未开始" }}</strong>
      </header>
      <div v-if="draftGenerationActive" class="outcome-generation__streams">
        <AgentStreamingMessage
          v-for="run in outcomeDraftStreamingRuns"
          :key="run.runId"
          :run="run"
          label="AI 法官"
          appearance="court"
        />
      </div>
      <div v-else class="outcome-generation__waiting">
        <span aria-hidden="true">⚖️</span>
        <p>当前没有正在生成的草案任务，请稍后刷新或返回庭审页查看案件状态。</p>
      </div>
    </section>

    <section v-if="hasRenderableOutcome" class="verdict-card">
      <span class="outcome-kicker">白话结论</span>
      <h2>{{ decision.conclusion }}</h2>
      <p>{{ decision.explanation }}</p>
      <p v-if="decision.review_reason" class="verdict-card__review">
        审核说明：{{ decision.review_reason }}
      </p>
      <div class="verdict-card__boundary">
        {{
          verdictBoundaryText
        }}
      </div>
    </section>

    <section
      v-if="adjudicationDraft"
      class="adjudication-draft-card"
      data-adjudication-draft
    >
      <header>
        <div>
          <span class="outcome-kicker">草案依据</span>
          <h2>AI 裁决草案（非最终）</h2>
          <p>
            {{ draftCardSummary }}
          </p>
        </div>
        <dl>
          <div>
            <dt>建议方向</dt>
            <dd>{{ draftRecommendedDecisionDisplay }}</dd>
          </div>
          <div>
            <dt>可信分</dt>
            <dd>{{ draftConfidenceScore(adjudicationDraft.confidence) }}</dd>
          </div>
        </dl>
      </header>

      <div class="draft-explain-grid">
        <article data-fact-findings>
          <span>事实认定</span>
          <p v-if="!draftList(adjudicationDraft.fact_findings || adjudicationDraft.factFindings).length">
            暂无结构化事实认定。
          </p>
          <ul v-else>
            <li
              v-for="(item, index) in draftList(adjudicationDraft.fact_findings || adjudicationDraft.factFindings)"
              :key="`fact-${index}`"
            >
              {{ draftValue(item) }}
            </li>
          </ul>
        </article>
        <article data-evidence-assessment>
          <span>证据采信</span>
          <p v-if="!draftList(adjudicationDraft.evidence_assessment || adjudicationDraft.evidenceAssessment).length">
            暂无结构化证据采信意见。
          </p>
          <ul v-else>
            <li
              v-for="(item, index) in draftList(adjudicationDraft.evidence_assessment || adjudicationDraft.evidenceAssessment)"
              :key="`evidence-${index}`"
            >
              {{ draftValue(item) }}
            </li>
          </ul>
        </article>
        <article data-policy-application>
          <span>规则适用</span>
          <p v-if="!draftList(adjudicationDraft.policy_application || adjudicationDraft.policyApplication).length">
            暂无结构化规则适用说明。
          </p>
          <ul v-else>
            <li
              v-for="(item, index) in draftList(adjudicationDraft.policy_application || adjudicationDraft.policyApplication)"
              :key="`policy-${index}`"
            >
              {{ draftValue(item) }}
            </li>
          </ul>
        </article>
        <article data-reviewer-attention>
          <span>{{ reviewerAttentionHeading }}</span>
          <p v-if="!draftList(adjudicationDraft.reviewer_attention || adjudicationDraft.reviewerAttention).length">
            暂无额外{{ reviewerAttentionHeading }}点。
          </p>
          <ul v-else>
            <li
              v-for="(item, index) in draftList(adjudicationDraft.reviewer_attention || adjudicationDraft.reviewerAttention)"
              :key="`attention-${index}`"
            >
              {{ draftValue(item) }}
            </li>
          </ul>
        </article>
      </div>
    </section>

    <section
      v-if="explanationOfficerNotes"
      class="explanation-officer-card"
      data-explanation-officer
    >
      <header>
        <div>
          <span class="outcome-kicker">草案说明</span>
          <h2>解释员复盘</h2>
        </div>
        <strong>草案确认辅助</strong>
      </header>
      <div class="explanation-officer-card__body">
        <article>
          <span>庭审复盘</span>
          <p>{{ explanationOfficerNotes.replaySummary }}</p>
        </article>
        <article>
          <span>方案解释</span>
          <p>{{ explanationOfficerNotes.finalPlanExplanation }}</p>
        </article>
        <article>
          <span>后续确认关注</span>
          <ul v-if="explanationOfficerNotes.focus.length">
            <li
              v-for="(item, index) in explanationOfficerNotes.focus"
              :key="`explainer-focus-${index}`"
            >
              {{ item }}
            </li>
          </ul>
          <p v-else>暂无额外关注点。</p>
        </article>
      </div>
    </section>

    <section
      v-if="canReviewOutcomeDraft"
      class="outcome-review-panel"
      data-outcome-review-panel
    >
      <header>
        <div>
          <span class="outcome-kicker">审核员操作</span>
          <h2>审核员确认草案</h2>
          <p>确认或修改后，系统会复用平台审核流生成最终确认记录。</p>
        </div>
        <strong>仅审核员可见</strong>
      </header>
      <label>
        <span>确认说明</span>
        <textarea
          v-model="reviewReason"
          data-review-reason
          rows="3"
          maxlength="2000"
          placeholder="请填写确认或修改理由"
        />
      </label>
      <div class="review-plan-editor" data-review-plan-editor>
        <label>
          <span>方案编号</span>
          <input
            v-model="reviewPlanDraft.id"
            data-review-plan-id
            placeholder="PLAN_1"
          />
        </label>
        <label>
          <span>主处理方向</span>
          <select
            v-model="reviewPlanDraft.handling_direction"
            data-review-handling-direction
          >
            <option value="">请选择处理方向</option>
            <option value="REFUND">退款</option>
            <option value="RETURN_REFUND">退货退款</option>
            <option value="RESHIP">补发</option>
            <option value="REPLACE_OR_REPAIR">换货 / 维修</option>
            <option value="COMPENSATION">赔付</option>
            <option value="CANCEL_ORDER">取消订单</option>
            <option value="REJECT_AFTER_SALE">驳回售后</option>
            <option value="VERIFY_OR_EXPLAIN_ONLY">仅核验 / 解释</option>
            <option value="MANUAL_REVIEW">转人工重点复核</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label>
          <span>执行方案说明</span>
          <textarea
            v-model="reviewPlanDraft.execution_plan"
            data-review-execution-plan
            rows="4"
            maxlength="3000"
            placeholder="说明本次裁决如何落地，例如金额、商品、时效、前置条件、风险和注意事项。"
          />
        </label>
        <p class="review-plan-editor__empty">
          当前只确认主处理方向和方案说明；具体工具拆分后续由执行专员助手处理。
        </p>
      </div>
      <div class="outcome-review-panel__actions">
        <button
          type="button"
          data-review-confirm
          :disabled="reviewBusy"
          @click="confirmOutcomeDraft"
        >
          {{ reviewBusy ? "处理中..." : "确认草案" }}
        </button>
        <button
          type="button"
          data-review-modify
          :disabled="reviewBusy || !canModifyReviewPlan"
          @click="modifyOutcomeDraftFromStructuredPlan"
        >
          修改并确认
        </button>
      </div>
      <p v-if="reviewStatus" class="outcome-review-panel__status">
        {{ reviewStatus }}
      </p>
    </section>

    <section v-if="hasRenderableOutcome" class="execution-board">
      <header>
        <div>
          <span class="outcome-kicker">{{ executionBoardKicker }}</span>
          <h2>{{ executionBoardTitle }}</h2>
        </div>
        <span>{{ executionBoardCountText }}</span>
      </header>

      <div v-if="actions.length" class="execution-board__grid">
        <article
          v-for="(action, index) in actions"
          :key="action.action_record_id"
          data-execution-receipt
        >
          <div class="execution-receipt__step">{{ index + 1 }}</div>
          <div>
            <span>{{ actionLabels[action.action_type] || action.action_type }}</span>
            <h3>{{ statusLabels[action.execution_status] || action.execution_status }}</h3>
            <p v-if="action.external_result_ref">
              外部回执：{{ action.external_result_ref }}
            </p>
            <dl v-if="executionResultRows(action).length" class="execution-result">
              <div
                v-for="row in executionResultRows(action)"
                :key="`${action.action_record_id}-${row.label}`"
              >
                <dt>{{ row.label }}</dt>
                <dd>{{ row.value }}</dd>
              </div>
            </dl>
          </div>
          <i :data-status="action.execution_status">
            {{ action.execution_status === "SUCCEEDED" ? "✓" : "…" }}
          </i>
        </article>
      </div>
      <div
        v-else-if="shouldShowExecutionAssistant"
        class="execution-assistant"
        data-execution-assistant
      >
        <div
          class="execution-assistant__indicator"
          :data-state="executionAssistantState"
          aria-hidden="true"
        >
          <span />
        </div>
        <div>
          <span>裁决已确认</span>
          <h3>方案已移交给执行专员助手处理</h3>
          <p>
            {{
              executionAssistantState === "succeeded"
                ? "方案执行成功"
                : "执行专员助手处理中"
            }}
          </p>
        </div>
      </div>
      <div v-else class="execution-board__empty">{{ executionBoardEmptyText }}</div>
    </section>

    <footer class="outcome-footer">
      <div>
        <span aria-hidden="true">📬</span>
        <p>{{ footerNoticeText }}</p>
      </div>
      <button type="button" @click="router.push('/disputes')">返回争议订单中心</button>
    </footer>
    <AgentStreamErrorDialog
      :message="streamError"
      title="裁决草案生成失败"
      @dismiss="dismissStreamError"
    />
    <p v-if="error" class="outcome-error" role="alert">{{ error }}</p>
  </main>
</template>

<style scoped>
.outcome-page {
  display: grid;
  width: 100%;
  min-width: 0;
  gap: 14px;
  box-sizing: border-box;
  max-width: 1120px;
  margin: 0 auto;
  padding-bottom: 20px;
}
.outcome-page,
.outcome-page * {
  box-sizing: border-box;
}
.outcome-page :where(section, header, article, div, dl, ul, li, p, h1, h2, h3, span, strong, small, dt, dd, footer, label, input, select, textarea, button) {
  min-width: 0;
}
.outcome-page :where(p, h1, h2, h3, span, strong, small, dt, dd, li, label, input, select, textarea, button) {
  overflow-wrap: anywhere;
  word-break: break-word;
}
.outcome-hero {
  min-width: 0;
  display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 16px; align-items: center;
  padding: 22px; background: linear-gradient(135deg, #e7f8ef, #eaf6ff 55%, #f1ebff);
  border: 1px solid #d7e8e4; border-radius: 28px;
}
.outcome-hero > div { min-width: 0; }
.outcome-hero__seal { display: grid; place-items: center; width: 78px; height: 78px; background: #fff; border-radius: 50%; box-shadow: inset 0 0 0 6px #eef8f1; }
.outcome-hero__seal span { font-size: 34px; }
.outcome-hero__seal i { color: #5d8a76; font-size: 9px; font-style: normal; font-weight: 900; letter-spacing: .15em; }
.outcome-kicker { color: #7185a8; font-size: 10px; font-weight: 800; letter-spacing: .18em; }
.outcome-hero h1 { margin: 7px 0 8px; color: #263754; font-size: clamp(32px, 5vw, 56px); line-height: 1.05; }
.outcome-hero p { margin: 0; overflow-wrap: anywhere; color: #728097; }
.outcome-hero__lead { display: flex; flex-wrap: wrap; gap: 6px 8px; align-items: center; }
.outcome-hero__context { display: inline-flex; gap: 6px; align-items: center; color: #586b85; font-size: 12px; font-weight: 900; line-height: 1; }
.outcome-hero__context i { color: #ff9a76; font-size: 9px; font-style: normal; }
.outcome-hero__status { display: grid; gap: 5px; padding: 13px; color: #267251; background: #fff; border-radius: 16px; }
.outcome-hero__status small { color: #7f8c9d; }
.outcome-generation {
  display: grid;
  gap: 16px;
  min-height: 250px;
  padding: 22px;
  overflow: hidden;
  background:
    radial-gradient(circle at 88% 14%, rgba(153, 127, 227, .13), transparent 30%),
    linear-gradient(145deg, #fffdf8, #f7f9ff);
  border: 1px solid #e4deee;
  border-radius: 24px;
  box-shadow: 0 16px 42px rgba(73, 86, 128, .09);
}
.outcome-generation > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}
.outcome-generation > header h2 {
  margin: 5px 0 6px;
  color: #35445d;
  font-size: 22px;
}
.outcome-generation > header p {
  max-width: 760px;
  margin: 0;
  color: #758197;
  font-size: 13px;
  line-height: 1.65;
}
.outcome-generation > header strong {
  flex: 0 0 auto;
  padding: 7px 11px;
  color: #7662b2;
  background: #f2eeff;
  border: 1px solid #e0d8fa;
  border-radius: 999px;
  font-size: 11px;
}
.outcome-generation[data-streaming="true"] > header strong {
  color: #a35c34;
  background: #fff3e8;
  border-color: #f3d5bd;
}
.outcome-generation__streams {
  display: grid;
  gap: 10px;
  align-content: start;
  min-height: 120px;
}
.outcome-generation__streams :deep(.agent-streaming-message--court) {
  width: 100%;
  max-width: none;
}
.outcome-generation__waiting {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-height: 110px;
  padding: 18px;
  color: #78849a;
  background: rgba(255, 255, 255, .72);
  border: 1px dashed #d9dfea;
  border-radius: 18px;
}
.outcome-generation__waiting span { font-size: 28px; }
.outcome-generation__waiting p { margin: 0; line-height: 1.65; }
.verdict-card { min-width: 0; padding: 20px; background: #ffffffde; border: 1px solid #e1e8f1; border-radius: 24px; text-align: left; }
.verdict-card h2 {
  max-height: 8.7em;
  margin: 9px 0;
  overflow: auto;
  overflow-wrap: anywhere;
  word-break: break-word;
  color: #34445d;
  font-size: clamp(19px, 2.1vw, 26px);
  line-height: 1.45;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
}
.verdict-card > p {
  max-width: none;
  max-height: 10.4em;
  margin: 0;
  overflow: auto;
  overflow-wrap: anywhere;
  word-break: break-word;
  color: #64738a;
  line-height: 1.72;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
}
.verdict-card__review { margin-top: 10px !important; color: #526b86 !important; font-size: 12px; }
.verdict-card__boundary { width: max-content; max-width: 100%; padding: 8px 12px; margin: 14px 0 0; color: #725f8d; background: #f1edff; border-radius: 999px; font-size: 11px; }
.adjudication-draft-card {
  display: grid;
  min-width: 0;
  gap: 16px;
  padding: 22px;
  background:
    radial-gradient(circle at 9% 0, #fff4c8 0 14%, transparent 15%),
    linear-gradient(145deg, #ffffff, #f5fbff 48%, #f6f1ff);
  border: 1px solid #dfe8f2;
  border-radius: 28px;
  box-shadow: inset 0 1px 0 #fff, 0 16px 38px #5c719112;
}
.adjudication-draft-card > header {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.adjudication-draft-card h2 {
  margin: 6px 0;
  color: #35455e;
}
.adjudication-draft-card header p {
  max-width: 760px;
  max-height: 8.5em;
  overflow: auto;
  margin: 0;
  padding-right: 4px;
  color: #65758d;
  font-size: 13px;
  line-height: 1.7;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
}
.adjudication-draft-card dl {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(120px, .28fr);
  gap: 8px;
  margin: 0;
}
.adjudication-draft-card dl div {
  min-width: 0;
  padding: 10px 12px;
  background: #ffffffd8;
  border: 1px solid #e2eaf3;
  border-radius: 16px;
}
.adjudication-draft-card dt {
  color: #8190a5;
  font-size: 10px;
  font-weight: 900;
}
.adjudication-draft-card dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: #40506a;
  font-size: 12px;
  font-weight: 900;
}
.draft-explain-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  align-items: start;
}
.draft-explain-grid article {
  min-width: 0;
  max-height: 330px;
  overflow: auto;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
  padding: 14px;
  background: #ffffffc8;
  border: 1px solid #e3ebf4;
  border-radius: 18px;
}
.draft-explain-grid article::-webkit-scrollbar {
  width: 6px;
}
.draft-explain-grid article::-webkit-scrollbar-track {
  background: transparent;
}
.draft-explain-grid article::-webkit-scrollbar-thumb {
  background: #c8d8e8;
  border-radius: 999px;
}
.draft-explain-grid article > span {
  color: #60738f;
  font-size: 11px;
  font-weight: 900;
}
.draft-explain-grid ul {
  display: grid;
  gap: 8px;
  padding-left: 0;
  margin: 10px 0 0;
  color: #526178;
  font-size: 12px;
  line-height: 1.65;
  list-style: none;
}
.draft-explain-grid li {
  padding: 10px 12px;
  overflow-wrap: anywhere;
  background: linear-gradient(135deg, #f8fbff, #fff);
  border: 1px solid #e6eef6;
  border-left: 3px solid #b8d8e8;
  border-radius: 12px;
}
.draft-explain-grid p {
  margin: 10px 0 0;
  color: #7c899d;
  font-size: 12px;
  line-height: 1.6;
}
.explanation-officer-card {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: 22px;
  background:
    radial-gradient(circle at 8% 0, #dff8e7 0 14%, transparent 15%),
    linear-gradient(145deg, #fbfff8, #eefbf2 52%, #f7fbff);
  border: 1px solid #cfe8d9;
  border-radius: 28px;
  box-shadow: inset 0 1px 0 #fff, 0 16px 38px #4f806512;
}
.explanation-officer-card header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}
.explanation-officer-card h2 {
  margin: 6px 0 0;
  color: #2f4d3d;
}
.explanation-officer-card header strong {
  padding: 8px 11px;
  color: #2e8057;
  background: #ffffffd8;
  border: 1px solid #cdebd8;
  border-radius: 999px;
  font-size: 11px;
}
.explanation-officer-card__body {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
}
.explanation-officer-card article {
  min-width: 0;
  max-height: 260px;
  overflow: auto;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
  padding: 14px;
  background: #ffffffc8;
  border: 1px solid #dcefe3;
  border-radius: 18px;
}
.explanation-officer-card article > span {
  color: #4e7d62;
  font-size: 11px;
  font-weight: 900;
}
.explanation-officer-card p,
.explanation-officer-card li {
  color: #4f6257;
  font-size: 12px;
  line-height: 1.65;
}
.explanation-officer-card ul {
  display: grid;
  gap: 7px;
  padding-left: 18px;
  margin-bottom: 0;
}
.outcome-review-panel {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: 22px;
  background:
    radial-gradient(circle at 6% 0, #dcecff 0 13%, transparent 14%),
    linear-gradient(145deg, #f9fbff, #eef5ff 52%, #f8f3ff);
  border: 1px solid #d8e6f7;
  border-radius: 28px;
  box-shadow: inset 0 1px 0 #fff, 0 16px 38px #4b6d9912;
}
.outcome-review-panel header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}
.outcome-review-panel h2 {
  margin: 6px 0;
  color: #324762;
}
.outcome-review-panel header p {
  margin: 0;
  color: #687894;
  font-size: 12px;
}
.outcome-review-panel header strong {
  flex: none;
  padding: 8px 11px;
  color: #386799;
  background: #ffffffd8;
  border: 1px solid #d7e6f7;
  border-radius: 999px;
  font-size: 11px;
}
.outcome-review-panel label {
  display: grid;
  gap: 7px;
  color: #4f6078;
  font-size: 12px;
  font-weight: 900;
}
.review-plan-editor {
  display: grid;
  min-width: 0;
  gap: 10px;
  padding: 13px;
  background: #ffffff9e;
  border: 1px solid #dce7f4;
  border-radius: 18px;
}
.review-plan-editor__actions {
  display: grid;
  gap: 9px;
}
.review-plan-editor__actions article {
  display: grid;
  grid-template-columns: minmax(150px, .8fr) minmax(100px, .45fr) auto;
  gap: 10px;
  align-items: end;
  padding: 10px;
  background: #f7fbff;
  border: 1px solid #e2ebf6;
  border-radius: 14px;
}
.review-plan-editor input,
.review-plan-editor select,
.review-plan-editor textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 11px;
  color: #34445d;
  background: #fff;
  border: 1px solid #dce7f4;
  border-radius: 12px;
  font: inherit;
  font-weight: 600;
  outline: none;
}
.review-plan-editor input:focus,
.review-plan-editor select:focus,
.review-plan-editor textarea:focus {
  border-color: #8ab7e6;
  box-shadow: 0 0 0 3px #8ab7e633;
}
.review-plan-editor textarea {
  min-height: 92px;
  resize: vertical;
  line-height: 1.6;
}
.review-plan-editor button {
  width: max-content;
  padding: 9px 11px;
  color: #35506d;
  background: #fff;
  border: 1px solid #d7e4f2;
  border-radius: 12px;
  cursor: pointer;
  font-weight: 900;
}
.review-plan-editor__empty {
  margin: 0;
  color: #75869d;
  font-size: 12px;
}
.outcome-review-panel textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  padding: 12px 13px;
  color: #34445d;
  background: #ffffffde;
  border: 1px solid #dce7f4;
  border-radius: 16px;
  font: inherit;
  font-weight: 500;
  line-height: 1.55;
  outline: none;
}
.outcome-review-panel textarea:focus {
  border-color: #8ab7e6;
  box-shadow: 0 0 0 3px #8ab7e633;
}
.outcome-review-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.outcome-review-panel__actions button {
  padding: 10px 14px;
  color: #fff;
  background: linear-gradient(135deg, #5277d7, #5aa8c9);
  border: 0;
  border-radius: 13px;
  cursor: pointer;
  font-weight: 900;
}
.outcome-review-panel__actions button:last-child {
  color: #35506d;
  background: #fff;
  border: 1px solid #d7e4f2;
}
.outcome-review-panel__actions button:disabled {
  cursor: not-allowed;
  opacity: .58;
}
.outcome-review-panel__status {
  margin: 0;
  color: #2f7b55;
  font-size: 12px;
  font-weight: 900;
}
.execution-board { min-width: 0; padding: 21px; background: #ffffffce; border: 1px solid #e1e8f1; border-radius: 28px; }
.execution-board header { display: flex; justify-content: space-between; gap: 16px; }
.execution-board h2 { margin: 5px 0; color: #34445d; }
.execution-board header > span { height: max-content; padding: 6px 9px; color: #5a738f; background: #edf5fb; border-radius: 999px; font-size: 11px; }
.execution-board__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 15px; }
.execution-board article { display: grid; min-width: 0; grid-template-columns: auto minmax(0, 1fr) auto; gap: 12px; padding: 15px; background: linear-gradient(145deg, #f8fbff, #fff); border: 1px solid #e3eaf2; border-radius: 18px; }
.execution-receipt__step { display: grid; place-items: center; width: 34px; height: 34px; color: #fff; background: #7f8fe0; border-radius: 11px; font-weight: 900; }
.execution-board article span { color: #7a889c; font-size: 10px; }
.execution-board article h3 { margin: 4px 0; color: #405069; }
.execution-board article p { color: #6d7a8e; font-size: 11px; }
.execution-result { display: grid; gap: 5px; margin: 8px 0 0; }
.execution-result div { display: grid; grid-template-columns: 70px 1fr; gap: 8px; align-items: start; padding: 5px 7px; background: #eef5fb; border-radius: 9px; }
.execution-result dt { color: #708196; font-size: 10px; font-weight: 800; }
.execution-result dd { margin: 0; overflow-wrap: anywhere; color: #405069; font-size: 11px; }
.execution-board article > i { display: grid; place-items: center; width: 27px; height: 27px; color: #267252; background: #def5e8; border-radius: 50%; font-style: normal; }
.execution-board article > i:not([data-status="SUCCEEDED"]) { color: #9a6d25; background: #fff0cc; }
.execution-assistant {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  margin-top: 15px;
  padding: 18px;
  background:
    radial-gradient(circle at 6% 10%, #e7fff1 0 12%, transparent 13%),
    linear-gradient(135deg, #f8fffb, #f3f8ff);
  border: 1px solid #dcebe6;
  border-radius: 20px;
}
.execution-assistant__indicator {
  position: relative;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  color: #2f7b55;
  background: #e7f8ef;
  border-radius: 50%;
}
.execution-assistant__indicator::before {
  content: "";
  position: absolute;
  inset: 5px;
  border: 3px solid #bddfcd;
  border-top-color: #5dbb92;
  border-radius: 50%;
  animation: execution-spin .9s linear infinite;
}
.execution-assistant__indicator span {
  width: 10px;
  height: 10px;
  background: currentColor;
  border-radius: 50%;
}
.execution-assistant__indicator[data-state="succeeded"]::before {
  border-color: #7cc89e;
  animation: none;
}
.execution-assistant__indicator[data-state="succeeded"] span {
  width: 18px;
  height: 18px;
  background: transparent;
}
.execution-assistant__indicator[data-state="succeeded"] span::before {
  content: "✓";
  color: #2f7b55;
  font-size: 18px;
  font-weight: 900;
}
.execution-assistant span {
  color: #5d7890;
  font-size: 11px;
  font-weight: 900;
}
.execution-assistant h3 {
  margin: 4px 0;
  color: #34445d;
}
.execution-assistant p {
  margin: 0;
  color: #2f7b55;
  font-size: 13px;
  font-weight: 900;
}
@keyframes execution-spin {
  to { transform: rotate(360deg); }
}
.execution-board__empty { padding: 35px; color: #8290a3; text-align: center; }
.outcome-footer { display: flex; min-width: 0; justify-content: space-between; gap: 20px; align-items: center; padding: 17px 20px; background: #fff5dc; border: 1px solid #efdfb8; border-radius: 20px; }
.outcome-footer div { display: flex; gap: 10px; align-items: center; color: #6f665f; }
.outcome-footer p { margin: 0; }
.outcome-footer button { padding: 10px 14px; color: white; background: linear-gradient(135deg, #5dbb92, #5aa8c9); border: 0; border-radius: 12px; cursor: pointer; font-weight: 800; }
.outcome-error { color: #a64551; }
@media (max-width: 720px) {
  .outcome-page { gap: 12px; }
  .outcome-hero { grid-template-columns: auto 1fr; }
  .outcome-hero {
    gap: 12px;
    padding: 16px;
    border-radius: 22px;
  }
  .outcome-hero__seal {
    width: 58px;
    height: 58px;
    box-shadow: inset 0 0 0 4px #eef8f1;
  }
  .outcome-hero__seal span { font-size: 25px; }
  .outcome-hero h1 { font-size: 32px; line-height: 1.05; }
  .outcome-hero p { font-size: 13px; }
  .outcome-hero__status { grid-column: 1 / -1; }
  .outcome-hero__status { padding: 10px 12px; border-radius: 14px; }
  .verdict-card,
  .adjudication-draft-card,
  .explanation-officer-card,
  .outcome-review-panel,
  .execution-board {
    padding: 16px;
    border-radius: 22px;
  }
  .verdict-card h2 { font-size: 18px; line-height: 1.55; }
  .verdict-card > p { font-size: 13px; line-height: 1.75; }
  .adjudication-draft-card > header { grid-template-columns: 1fr; }
  .draft-explain-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .adjudication-draft-card dl {
    grid-template-columns: minmax(0, 1fr) minmax(112px, .34fr);
  }
  .draft-explain-grid article { max-height: 280px; }
  .explanation-officer-card__body {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .explanation-officer-card__body article:last-child {
    grid-column: 1 / -1;
  }
  .execution-board__empty { padding: 26px 12px; }
  .execution-board__grid { grid-template-columns: 1fr; }
  .execution-board header {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
  .outcome-footer { align-items: stretch; flex-direction: column; }
}
@media (max-width: 520px) {
  .draft-explain-grid,
  .explanation-officer-card__body {
    grid-template-columns: 1fr;
  }
  .explanation-officer-card__body article:last-child { grid-column: auto; }
  .outcome-review-panel header {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
  .review-plan-editor__actions article {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 460px) {
  .adjudication-draft-card dl { grid-template-columns: 1fr; }
  .explanation-officer-card header { align-items: start; flex-direction: column; }
  .execution-result div { grid-template-columns: minmax(0, .44fr) minmax(0, 1fr); }
  .execution-board article { gap: 8px; padding: 12px; }
  .execution-receipt__step { width: 30px; height: 30px; }
  .execution-board article > i { width: 24px; height: 24px; }
}
</style>
