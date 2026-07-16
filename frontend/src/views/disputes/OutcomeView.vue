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
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
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
const loading = ref(props.initialOutcome === null);
const error = ref("");
const mockExecutionStage = ref(0);
let mockExecutionTimer = null;
const outcomeStreamAbortController = new AbortController();
const DRAFT_STREAM_OPERATIONS = new Set(["HEARING_JUDGE_V2"]);
const MOCK_EXECUTION_STEPS = [
  { label: "方案下发", detail: "读取管理员批准方案" },
  { label: "执行准备", detail: "校验动作与前置条件" },
  { label: "业务处理", detail: "模拟调用履约执行工具" },
  { label: "结果同步", detail: "生成模拟执行回执" },
];
const MOCK_EXECUTION_STEP_INTERVAL_MS = 5_000;
const caseId = computed(
  () => outcome.value?.case_id || route.params.caseId,
);
const actions = computed(() => outcome.value?.actions || []);
const persistedDecision = computed(
  () => outcome.value?.final_decision || outcome.value?.finalDecision || null,
);
const rawDecision = computed(
  () => persistedDecision.value || {},
);
const adjudicationDraft = computed(() => {
  const source =
    outcome.value?.adjudication_draft || outcome.value?.adjudicationDraft || {};
  return source.draft && typeof source.draft === "object"
    ? { ...source, ...source.draft }
    : source;
});
const reviewTaskStatus = computed(() =>
  String(outcome.value?.review_task_status || outcome.value?.reviewTaskStatus || "")
    .trim()
    .toUpperCase(),
);
const approvedPlan = computed(() => {
  const decisionPlan =
    rawDecision.value?.approved_plan || rawDecision.value?.approvedPlan;
  if (decisionPlan && typeof decisionPlan === "object") return decisionPlan;
  const legacyPlan =
    adjudicationDraft.value?.approved_plan || adjudicationDraft.value?.approvedPlan;
  return legacyPlan && typeof legacyPlan === "object" ? legacyPlan : {};
});
const finalPlanLabels = {
  REFUND: "退款",
  RETURN_AND_REFUND: "退货退款",
  RETURN_REFUND: "退货退款",
  RESHIP: "补发",
  RESEND: "补发",
  REPLACE: "换货",
  EXCHANGE: "换货",
  REPAIR: "维修",
  COMPENSATE: "补偿",
  COMPENSATION: "补偿",
  REJECT: "不支持售后",
  MANUAL_REVIEW: "转人工复核",
  MANUAL_REVIEW_REQUIRED: "转人工复核",
};
const isFinalOutcome = computed(() => {
  const decision = rawDecision.value || {};
  const humanConfirmed = Boolean(
    decision.human_confirmed || decision.humanConfirmed,
  );
  return (
    humanConfirmed &&
    (!reviewTaskStatus.value || reviewTaskStatus.value === "APPROVED")
  );
});
const hearingDecision = computed(() => {
  const source = adjudicationDraft.value || {};
  return {
    version: source.draft_version || source.draftVersion || "-",
    type: formatFinalConclusion(
      source.recommended_decision ||
        source.recommendedDecision ||
        rawDecision.value?.conclusion ||
        "待同步",
    ),
    text: localizeOutcomeCopy(
      source.draft_text ||
        source.draftText ||
        rawDecision.value?.explanation ||
        "庭审 V2 裁决说明待同步。",
    ),
    confidence: formatConfidence(source.confidence),
  };
});
const reviewOpinion = computed(() =>
  localizeOutcomeCopy(
    rawDecision.value?.review_reason ||
      rawDecision.value?.reviewReason ||
      rawDecision.value?.admin_review_opinion ||
      rawDecision.value?.adminReviewOpinion ||
      rawDecision.value?.decision_reason ||
      rawDecision.value?.decisionReason ||
      "管理员已确认庭审裁决与最终执行方案，无补充审核意见。",
  ),
);
const reviewStatusLabel = computed(() => {
  const labels = {
    APPROVED: "审核通过",
    REJECTED: "未通过",
    WAITING_HUMAN_REVIEW: "等待审核",
    IN_REVIEW: "审核中",
  };
  return labels[reviewTaskStatus.value] || "管理员已确认";
});
const approvedPlanActions = computed(() => {
  const source = approvedPlan.value?.actions || approvedPlan.value?.action || [];
  return Array.isArray(source) ? source.filter(Boolean) : [];
});
const approvedPlanType = computed(() => {
  const source = approvedPlan.value || {};
  const explicit =
    source.plan_type ||
    source.planType ||
    source.solution_type ||
    source.solutionType ||
    source.handling_direction ||
    source.handlingDirection ||
    source.type;
  if (explicit) return planTypeLabel(explicit);
  const labels = Array.from(
    new Set(
      approvedPlanActions.value
        .map((action) => actionTypeLabel(action))
        .filter(Boolean),
    ),
  );
  if (labels.length > 1) return "组合执行方案";
  return labels[0] || hearingDecision.value.type || "已批准执行方案";
});
const approvedPlanDescription = computed(() => {
  const source = approvedPlan.value || {};
  const explicit =
    source.description ||
    source.natural_language_description ||
    source.naturalLanguageDescription ||
    source.execution_plan ||
    source.executionPlan ||
    source.summary ||
    source.note;
  if (explicit) return localizeOutcomeCopy(explicit);
  const descriptions = approvedPlanActions.value
    .map(describePlanAction)
    .filter(Boolean);
  return descriptions.length
    ? `${descriptions.join("；")}。`
    : "管理员已确认该执行方案，具体动作将在执行阶段同步。";
});
const approvedPlanVersion = computed(
  () => approvedPlan.value?.version || approvedPlan.value?.plan_version || "-",
);
const approvedPlanActionLabels = computed(() =>
  Array.from(
    new Set(approvedPlanActions.value.map((action) => actionTypeLabel(action))),
  ),
);
const approvedPlanSource = computed(() =>
  rawDecision.value?.approved_plan || rawDecision.value?.approvedPlan
    ? "管理员批准快照"
    : "已批准历史方案",
);
const heroKicker = computed(() =>
  loading.value
    ? "正在加载"
    : isFinalOutcome.value
      ? "四段结果已归档"
      : "等待最终结果",
);
const heroStatusTitle = computed(() => {
  if (loading.value) return "正在获取最终结果";
  return isFinalOutcome.value ? "管理员审核已完成" : "等待审核通过";
});
const heroStatusDetail = computed(() => {
  if (loading.value) return "正在同步裁决、审核与执行信息";
  if (isFinalOutcome.value) return "庭审裁决、审核意见与执行方案已生效";
  return "审核通过后展示完整四段结果";
});

function actionExecutionStatus(action) {
  return action.execution_status || action.executionStatus || action.status || "";
}

const executionSummary = computed(() => {
  const statuses = actions.value.map(actionExecutionStatus);
  if (!statuses.length) {
    return {
      label: "等待执行回执",
      detail: "方案已生效，正在等待执行系统返回真实处理进度",
      state: "active",
    };
  }
  if (statuses.every((status) => status === "SUCCEEDED")) {
    return { label: "执行完成", detail: "全部执行动作均已成功", state: "complete" };
  }
  if (statuses.includes("FAILED")) {
    return { label: "执行异常", detail: "部分执行动作失败，请关注回执详情", state: "attention" };
  }
  if (statuses.includes("MANUAL_REQUIRED")) {
    return { label: "等待人工处理", detail: "执行系统已记录人工接管状态", state: "attention" };
  }
  if (statuses.includes("RUNNING")) {
    return { label: "执行中", detail: "执行动作正在处理中", state: "active" };
  }
  if (statuses.includes("COMPENSATING")) {
    return { label: "补偿处理中", detail: "执行异常正在进行补偿处理", state: "active" };
  }
  if (statuses.includes("PENDING")) {
    return { label: "等待执行", detail: "执行动作已创建，等待系统处理", state: "active" };
  }
  if (statuses.every((status) => ["SUCCEEDED", "COMPENSATED"].includes(status))) {
    return { label: "执行已结束", detail: "全部动作已完成，异常动作已补偿处理", state: "complete" };
  }
  return { label: "回执同步中", detail: "正在同步最新执行状态", state: "active" };
});

const shouldUseMockExecution = computed(
  () => isFinalOutcome.value && actions.value.length === 0,
);
const mockExecutionStatus = computed(() => {
  if (mockExecutionStage.value >= MOCK_EXECUTION_STEPS.length) {
    return {
      label: "模拟执行完成",
      detail: "四个演示步骤均已完成",
      state: "complete",
    };
  }
  const current = MOCK_EXECUTION_STEPS[mockExecutionStage.value];
  return { label: current.label, detail: current.detail, state: "active" };
});
const mockExecutionProgress = computed(() =>
  Math.min(
    100,
    Math.round(
      ((mockExecutionStage.value + 1) / MOCK_EXECUTION_STEPS.length) * 100,
    ),
  ),
);
const footerNoticeText =
  "庭审裁决、管理员审核、最终方案与执行情况会共同归档到案件结果。";

const actionLabels = {
  REFUND: "退款",
  RETURN_AND_REFUND: "退货退款",
  RESHIP: "重新发货",
  REPLACE: "更换商品",
  COMPENSATE: "补偿",
  CANCEL_ORDER: "取消订单",
  REJECT_AFTER_SALE: "关闭售后申请",
  CLOSE_AFTER_SALE: "关闭售后申请",
  CREATE_MANUAL_REVIEW_TICKET: "创建人工复核工单",
  CREATE_FULFILLMENT_REMINDER: "创建履约提醒",
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
  COMPENSATING: "补偿处理中",
  COMPENSATED: "已补偿处理",
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

function formatConfidence(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "未提供";
  const percent = numeric <= 1 ? numeric * 100 : numeric;
  return `${Math.round(percent)}%`;
}

function actionTypeCode(action) {
  return String(
    action?.action_type || action?.actionType || action?.type || "",
  )
    .trim()
    .toUpperCase();
}

function actionTypeLabel(action) {
  const code = actionTypeCode(action);
  return actionLabels[code] || finalPlanLabels[code] || humanizeDossierText(code, {
    fallback: code,
  });
}

function planTypeLabel(value) {
  const code = String(value || "").trim().toUpperCase();
  return finalPlanLabels[code] || actionLabels[code] || localizeOutcomeCopy(value);
}

function describePlanAction(action) {
  const parameters = action?.parameters || {};
  const explicit =
    action?.description ||
    action?.note ||
    parameters.description ||
    parameters.plan_description ||
    parameters.planDescription ||
    parameters.reason;
  if (explicit) return localizeOutcomeCopy(explicit);

  const descriptions = {
    REFUND: "按管理员批准方案向用户原支付渠道发起退款",
    RETURN_AND_REFUND: "安排用户退回商品，并在验收后完成退款",
    RESHIP: "为用户重新发货并同步新的履约信息",
    RESEND: "为用户重新发货并同步新的履约信息",
    REPLACE: "为用户更换符合订单约定的商品",
    EXCHANGE: "为用户更换符合订单约定的商品",
    REPAIR: "安排商品维修并同步处理进度",
    COMPENSATE: "按管理员批准标准向用户发放补偿",
    CANCEL_ORDER: "取消争议订单并同步订单状态",
    REJECT_AFTER_SALE: "关闭本次售后申请并归档处理依据",
    CLOSE_AFTER_SALE: "关闭本次售后申请并归档处理依据",
    CREATE_MANUAL_REVIEW_TICKET:
      "创建人工复核工单，继续核验关键事实并由平台完成后续处理",
    CREATE_FULFILLMENT_REMINDER: "创建履约提醒并通知责任方继续处理",
    NOTIFY_USER: "向用户发送最终方案与执行结果通知",
    NOTIFY_MERCHANT: "向商家发送最终方案与执行要求通知",
    CLOSE_CASE: "在执行完成后关闭案件并归档结果",
  };
  const code = actionTypeCode(action);
  return descriptions[code] || `执行“${actionTypeLabel(action)}”动作`;
}

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
  return flattenResult(
    action.result || action.execution_result || action.executionResult,
  );
}

function actionExternalReference(action) {
  return (
    action.external_result_ref ||
    action.externalResultRef ||
    action.reference_id ||
    action.referenceId ||
    ""
  );
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
    .replace(/\b(ISSUE|FACT|EVIDENCE|EVD|DRAFT|A2A)_[A-Za-z0-9_-]+\b/g, "相关材料");
}

// 业务位置：【前端处理结果】localizeOutcomeCopy：将 阶段处理结果或草案 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function localizeOutcomeCopy(value) {
  if (value === null || value === undefined || value === "") return "";
  return sanitizeOutcomeCopy(humanizeDossierText(value, { fallback: "" }));
}

function formatFinalConclusion(value) {
  const normalized = String(value || "").trim().toUpperCase();
  return finalPlanLabels[normalized] || localizeOutcomeCopy(value);
}

function mockStepState(index) {
  if (mockExecutionStage.value >= MOCK_EXECUTION_STEPS.length) return "complete";
  if (index < mockExecutionStage.value) return "complete";
  if (index === mockExecutionStage.value) return "active";
  return "pending";
}

function clearMockExecutionTimer() {
  if (!mockExecutionTimer) return;
  clearInterval(mockExecutionTimer);
  mockExecutionTimer = null;
}

function syncMockExecution(active) {
  clearMockExecutionTimer();
  mockExecutionStage.value = 0;
  if (!active) return;
  mockExecutionTimer = setInterval(() => {
    mockExecutionStage.value += 1;
    if (mockExecutionStage.value >= MOCK_EXECUTION_STEPS.length) {
      clearMockExecutionTimer();
    }
  }, MOCK_EXECUTION_STEP_INTERVAL_MS);
}

watch(shouldUseMockExecution, syncMockExecution, { immediate: true });

// 业务位置：【前端处理结果】refreshOutcome：重新加载 阶段处理结果或草案，确保页面和下一次 Agent 调用基于最新案件版本。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function refreshOutcome() {
  outcome.value = await disputeApi.outcome(actor, caseId.value);
  return outcome.value;
}

// 业务位置：【前端处理结果】isDraftStreamDescriptor：判断 Agent 流事件 是否满足当前流程分支的进入条件。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
function isDraftStreamDescriptor(value) {
  const descriptor = extractAgentRunDescriptor(value);
  return Boolean(descriptor && DRAFT_STREAM_OPERATIONS.has(descriptor.operation));
}

// 业务位置：【前端处理结果】consumeOutcomeDraftRun：执行 阶段处理结果或草案 对应的业务动作，并将结果交给 当事人可见的处理结论和后续动作。上游：审核决定和执行/结案状态。下游：当事人可见的处理结论和后续动作。边界：仅展示当前角色获授权的结论。
async function consumeOutcomeDraftRun(value) {
  const descriptor = extractAgentRunDescriptor(value);
  if (!descriptor || !DRAFT_STREAM_OPERATIONS.has(descriptor.operation)) return false;
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
    },
    onError: () => {
      error.value = "最终结果暂时无法更新，请稍后刷新。";
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
    if (!resumed && outcomeFailure) error.value = "最终结果加载失败，请稍后重试。";
  } catch {
    error.value = "最终结果暂时无法更新，请稍后刷新。";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
onBeforeUnmount(() => {
  clearMockExecutionTimer();
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
  <RoomShell
    class="outcome-shell"
    eyebrow="CASE OUTCOME"
    title="执行结果"
    subtitle="最终结果归档"
    subtitle-description="依次呈现庭审裁决、管理员审核意见、最终执行方案与执行情况。"
    :case-id="caseId"
    :show-case-id="false"
    :show-connection="false"
    :show-boundary="false"
  >
    <template #clock>
      <div class="outcome-shell-status" :data-final="isFinalOutcome">
        <span>{{ heroKicker }}</span>
        <strong>{{ heroStatusTitle }}</strong>
        <small>{{ heroStatusDetail }}</small>
      </div>
    </template>

    <main class="outcome-page">
      <section v-if="!isFinalOutcome" class="outcome-generation" data-outcome-waiting>
        <header>
          <div>
            <span class="outcome-kicker">最终结果</span>
            <h2>{{ loading ? "正在获取最终结果" : "等待最终结果" }}</h2>
            <p>
              {{
                loading
                  ? "正在同步最新审批结果与执行状态。"
                  : "管理员审核通过后，本页将展示完整的四段结果链路。"
              }}
            </p>
          </div>
          <strong>{{ loading ? "同步中" : "尚未生效" }}</strong>
        </header>
        <div class="outcome-generation__waiting">
          <i aria-hidden="true"></i>
          <div>
            <strong>{{ loading ? "结果加载中" : "等待审核通过" }}</strong>
            <p>审批前不会展示庭审裁决、审核意见、执行方案或执行动画。</p>
          </div>
        </div>
      </section>

      <div
        v-if="isFinalOutcome"
        class="outcome-four-stage"
        data-outcome-summary-layout
      >
        <div class="outcome-primary-grid">
          <section
            class="outcome-stage outcome-stage--hearing"
            data-outcome-hearing
          >
            <header class="outcome-stage__heading">
              <span class="outcome-stage__index">01</span>
              <div>
                <span class="outcome-kicker">庭审结果</span>
                <h2>庭审法官 V2</h2>
              </div>
              <strong>裁决稿 v{{ hearingDecision.version }}</strong>
            </header>
            <div class="outcome-stage__content">
              <div class="outcome-result-type">
                <span>裁决方向</span>
                <strong>{{ hearingDecision.type }}</strong>
              </div>
              <p class="outcome-scroll-copy">{{ hearingDecision.text }}</p>
            </div>
            <footer class="outcome-stage__meta">
              <span>裁决置信度</span>
              <strong>{{ hearingDecision.confidence }}</strong>
            </footer>
          </section>

          <section
            class="outcome-stage outcome-stage--review"
            data-outcome-review
          >
            <header class="outcome-stage__heading">
              <span class="outcome-stage__index">02</span>
              <div>
                <span class="outcome-kicker">人工终审</span>
                <h2>管理员审核意见</h2>
              </div>
              <strong>{{ reviewStatusLabel }}</strong>
            </header>
            <div class="outcome-review-opinion">
              <span>审核意见</span>
              <p class="outcome-scroll-copy">{{ reviewOpinion }}</p>
            </div>
            <footer class="outcome-stage__meta">
              <span>结果属性</span>
              <strong>只读归档</strong>
            </footer>
          </section>
        </div>

        <section
          class="outcome-stage outcome-stage--plan"
          data-outcome-plan
        >
          <header class="outcome-stage__heading">
            <span class="outcome-stage__index">03</span>
            <div>
              <span class="outcome-kicker">管理员收束</span>
              <h2>最终执行方案</h2>
            </div>
            <strong>{{ approvedPlanSource }} · v{{ approvedPlanVersion }}</strong>
          </header>
          <div class="approved-plan-layout">
            <div class="approved-plan-type">
              <span>方案类型</span>
              <h3>{{ approvedPlanType }}</h3>
              <div v-if="approvedPlanActionLabels.length" class="approved-plan-tags">
                <span v-for="label in approvedPlanActionLabels" :key="label">
                  {{ label }}
                </span>
              </div>
            </div>
            <div class="approved-plan-description">
              <span>方案说明</span>
              <p class="outcome-scroll-copy">{{ approvedPlanDescription }}</p>
            </div>
          </div>
        </section>

        <section
          class="outcome-stage outcome-stage--execution"
          data-outcome-execution
          data-execution-board
        >
          <header class="outcome-stage__heading">
            <span class="outcome-stage__index">04</span>
            <div>
              <span class="outcome-kicker">执行情况</span>
              <h2>{{ actions.length ? executionSummary.label : mockExecutionStatus.label }}</h2>
            </div>
            <strong :data-state="actions.length ? executionSummary.state : mockExecutionStatus.state">
              {{ actions.length ? `${actions.length} 项真实回执` : "前端 Mock 演示" }}
            </strong>
          </header>

          <div v-if="actions.length" class="execution-board__grid execution-board__grid--stage">
            <article
              v-for="(action, index) in actions"
              :key="action.action_record_id || action.actionRecordId || index"
              data-execution-receipt
            >
              <div class="execution-receipt__step">{{ index + 1 }}</div>
              <div>
                <span>{{ actionTypeLabel(action) }}</span>
                <h3>{{ statusLabels[actionExecutionStatus(action)] || actionExecutionStatus(action) }}</h3>
                <p v-if="actionExternalReference(action)">
                  外部回执：{{ actionExternalReference(action) }}
                </p>
                <dl v-if="executionResultRows(action).length" class="execution-result">
                  <div
                    v-for="row in executionResultRows(action)"
                    :key="`${action.action_record_id || action.actionRecordId || index}-${row.label}`"
                  >
                    <dt>{{ row.label }}</dt>
                    <dd>{{ row.value }}</dd>
                  </div>
                </dl>
                <p v-if="action.error_message || action.errorMessage" class="execution-receipt__error">
                  异常说明：{{ action.error_message || action.errorMessage }}
                </p>
              </div>
              <i :data-status="actionExecutionStatus(action)">
                {{ actionExecutionStatus(action) === "SUCCEEDED" ? "✓" : "·" }}
              </i>
            </article>
          </div>

          <div v-else class="mock-execution" data-mock-execution>
            <div class="mock-execution__summary">
              <div class="mock-execution__signal" :data-state="mockExecutionStatus.state" aria-hidden="true">
                <i></i>
              </div>
              <div>
                <span>当前模拟状态</span>
                <strong>{{ mockExecutionStatus.label }}</strong>
                <small>{{ mockExecutionStatus.detail }}</small>
              </div>
              <b>{{ mockExecutionProgress }}%</b>
            </div>
            <div class="mock-execution__bar" aria-hidden="true">
              <i :style="{ width: `${mockExecutionProgress}%` }"></i>
            </div>
            <ol class="mock-execution__steps" aria-label="前端模拟执行进度">
              <li
                v-for="(step, index) in MOCK_EXECUTION_STEPS"
                :key="step.label"
                :data-state="mockStepState(index)"
              >
                <i aria-hidden="true"></i>
                <strong>{{ step.label }}</strong>
                <span>{{ step.detail }}</span>
              </li>
            </ol>
            <p class="mock-execution__notice">
              此处为前端 Mock 动画，仅用于展示执行流程，不代表真实资金或履约结果。
            </p>
          </div>
        </section>
      </div>

      <footer class="outcome-footer">
      <div>
        <span aria-hidden="true">📬</span>
        <p>{{ footerNoticeText }}</p>
      </div>
      <button type="button" @click="router.push('/disputes')">返回争议订单中心</button>
    </footer>
      <p v-if="error" class="outcome-error" role="alert">{{ error }}</p>
    </main>
  </RoomShell>
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

/* Final-result workspace aligned with the preceding RoomShell pages. */
.outcome-shell :deep(.room-shell__agent:empty) {
  display: none;
}

.outcome-shell-status {
  display: grid;
  width: min(300px, 100%);
  gap: 3px;
  padding: 12px 15px;
  background: #fff;
  border: 1px solid #dce6f1;
  border-radius: 14px;
  box-shadow: 0 9px 24px #536e9012;
}

.outcome-shell-status span {
  color: #7185a8;
  font-size: 9px;
  font-weight: 900;
}

.outcome-shell-status strong {
  color: #536683;
  font-size: 13px;
}

.outcome-shell-status small {
  color: #7d899a;
  font-size: 10px;
  line-height: 1.45;
}

.outcome-shell-status[data-final="true"] {
  background: linear-gradient(145deg, #f4fff9, #fff);
  border-color: #cfe7d8;
}

.outcome-shell-status[data-final="true"] strong {
  color: #2f7557;
}

.outcome-page {
  max-width: 1280px;
  gap: 22px;
  padding-bottom: 24px;
}

.outcome-summary-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(320px, .65fr);
  grid-auto-rows: 420px;
  gap: 18px;
  align-items: stretch;
}

.verdict-card,
.outcome-status-card {
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: #ffffffdf;
  border: 1px solid #dfe8f2;
  border-radius: 20px;
  box-shadow: 0 14px 34px #536e900d;
}

.verdict-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  padding: 22px;
  text-align: left;
}

.outcome-section-heading,
.outcome-status-card > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.outcome-section-heading h2,
.outcome-status-card h2 {
  margin: 5px 0 0;
  color: #34435c;
  font-size: 18px;
  line-height: 1.3;
}

.outcome-section-heading > strong,
.outcome-status-card > header > strong {
  flex: 0 0 auto;
  padding: 6px 9px;
  color: #2f7557;
  background: #edf8f1;
  border: 1px solid #cfe7d8;
  border-radius: 9px;
  font-size: 10px;
}

.verdict-card__body {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  align-content: stretch;
  gap: 12px;
  min-height: 0;
  padding: 20px 0;
  margin-top: 16px;
  border-top: 1px solid #e4ebf2;
}

.verdict-card__body h3 {
  min-height: 0;
  max-height: 4.2em;
  margin: 0;
  padding-right: 5px;
  overflow-y: auto;
  color: #263754;
  font-size: 26px;
  line-height: 1.42;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
}

.verdict-card__body > p {
  min-height: 0;
  margin: 0;
  padding-right: 5px;
  overflow-y: auto;
  color: #64738a;
  font-size: 13px;
  line-height: 1.72;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
}

.verdict-card__review {
  padding-top: 11px;
  color: #526b86 !important;
  border-top: 1px dashed #dce5ef;
  font-size: 12px !important;
}

.verdict-card__boundary {
  width: 100%;
  padding: 10px 12px;
  margin: 0;
  color: #665b7e;
  background: #f3f0fb;
  border-radius: 10px;
  font-size: 11px;
  line-height: 1.5;
}

.outcome-status-card {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  align-content: start;
  gap: 18px;
  padding: 20px;
}

.outcome-status-card dl {
  display: grid;
  gap: 0;
  margin: 0;
  border-top: 1px solid #e4ebf2;
}

.outcome-status-card dl > div {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 10px;
  min-height: 46px;
  align-items: center;
  border-bottom: 1px solid #e4ebf2;
}

.outcome-status-card dt {
  color: #7b889b;
  font-size: 10px;
}

.outcome-status-card dd {
  margin: 0;
  color: #405069;
  font-size: 11px;
  font-weight: 800;
}

.outcome-status-card > header > strong[data-state="active"] {
  color: #93651c;
  background: #fff4d7;
  border-color: #ead8ac;
}

.outcome-status-card > header > strong[data-state="attention"] {
  color: #9a4c57;
  background: #ffeced;
  border-color: #efcfd3;
}

.outcome-progress {
  display: grid;
  gap: 0;
  padding: 0;
  margin: 0;
  list-style: none;
}

.outcome-progress li {
  position: relative;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 10px;
  min-height: 51px;
}

.outcome-progress li:not(:last-child)::after {
  position: absolute;
  top: 18px;
  bottom: 0;
  left: 7px;
  width: 2px;
  content: "";
  background: #dfe7ef;
}

.outcome-progress i {
  position: relative;
  z-index: 1;
  width: 16px;
  height: 16px;
  margin-top: 1px;
  background: #e7ecf2;
  border: 4px solid #f7f9fc;
  border-radius: 50%;
  box-shadow: 0 0 0 1px #d8e1eb;
}

.outcome-progress li[data-state="complete"] i {
  background: #52c790;
  box-shadow: 0 0 0 1px #9cd7ba;
}

.outcome-progress li[data-state="active"] i {
  background: #f5b84d;
  box-shadow: 0 0 0 1px #e5c778;
}

.outcome-progress li[data-state="attention"] i {
  background: #d96d78;
  box-shadow: 0 0 0 1px #e7aab0;
}

.outcome-progress li > div {
  display: grid;
  align-content: start;
  gap: 2px;
}

.outcome-progress strong {
  color: #45556d;
  font-size: 11px;
}

.outcome-progress span {
  color: #8390a2;
  font-size: 10px;
}

.outcome-generation {
  min-height: 360px;
  align-content: center;
  padding: 30px;
  background: #ffffffdf;
  border: 1px solid #dfe8f2;
  border-radius: 20px;
  box-shadow: 0 14px 34px #536e900d;
}

.outcome-generation > header strong {
  color: #93651c;
  background: #fff4d7;
  border-color: #ead8ac;
}

.outcome-generation__waiting {
  grid-template-columns: 42px minmax(0, 1fr);
  min-height: 112px;
  background: #f8fbff;
  border-style: solid;
}

.outcome-generation__waiting > i {
  width: 32px;
  height: 32px;
  background: #fff;
  border: 8px solid #dce8f4;
  border-top-color: #7e93e5;
  border-radius: 50%;
  animation: execution-spin 1.1s linear infinite;
}

.outcome-generation__waiting > div {
  display: grid;
  gap: 4px;
}

.outcome-generation__waiting strong {
  color: #45556d;
  font-size: 13px;
}

.outcome-generation__waiting p {
  color: #7b889b;
  font-size: 12px;
}

.execution-board {
  padding: 0;
  background: transparent;
  border: 0;
  border-radius: 0;
}

.execution-board > header {
  align-items: flex-end;
  padding: 0 2px 13px;
  border-bottom: 1px solid #dfe8f2;
}

.execution-board h2 {
  margin: 5px 0 0;
  font-size: 19px;
}

.execution-board__grid {
  grid-auto-rows: 240px;
  gap: 14px;
  margin-top: 16px;
}

.execution-board article {
  height: 100%;
  min-height: 0;
  padding: 17px;
  overflow: hidden;
  align-items: start;
  background: #ffffffdf;
  border-color: #dfe8f2;
  border-radius: 18px;
  box-shadow: 0 12px 28px #536e900d;
}

.execution-board article > div:not(.execution-receipt__step) {
  min-height: 0;
  max-height: 100%;
  padding-right: 5px;
  overflow-y: auto;
  scrollbar-color: #c8d8e8 transparent;
  scrollbar-width: thin;
}

.execution-receipt__error {
  padding: 9px 10px;
  color: #954753 !important;
  background: #fff0f1;
  border: 1px solid #f0d2d5;
  border-radius: 8px;
  line-height: 1.55;
}

.execution-result div {
  background: #f4f8fc;
}

.execution-assistant,
.execution-board__empty {
  display: grid;
  min-height: 154px;
  place-items: center;
  margin-top: 16px;
  background: #ffffffdf;
  border: 1px solid #dfe8f2;
  border-radius: 18px;
  box-shadow: 0 12px 28px #536e900d;
}

.outcome-footer {
  padding: 15px 18px;
  background: #fff8e8;
  border-color: #eadcb9;
  border-radius: 16px;
}

.outcome-footer > div > span {
  display: none;
}

.outcome-footer button {
  min-height: 42px;
  padding: 9px 15px;
  background: linear-gradient(135deg, #55b8df, #8585ef);
  border-radius: 12px;
}

@media (max-width: 900px) {
  .outcome-summary-layout {
    grid-template-columns: 1fr;
    grid-auto-rows: auto;
  }

  .verdict-card {
    height: 390px;
  }

  .outcome-status-card {
    height: 360px;
    grid-template-columns: minmax(240px, .8fr) minmax(0, 1.2fr);
    grid-template-rows: auto minmax(0, 1fr);
  }

  .outcome-status-card > header {
    grid-column: 1 / -1;
  }

  .outcome-status-card dl,
  .outcome-progress {
    align-self: start;
  }
}

@media (max-width: 720px) {
  .outcome-shell-status {
    width: 100%;
  }

  .outcome-page {
    gap: 18px;
  }

  .outcome-status-card {
    height: 390px;
    grid-template-columns: 1fr;
    grid-template-rows: auto auto minmax(0, 1fr);
  }

  .outcome-status-card > header {
    grid-column: auto;
  }

  .verdict-card,
  .outcome-status-card,
  .outcome-generation {
    padding: 17px;
    border-radius: 18px;
  }

  .verdict-card__body h3 {
    font-size: 21px;
  }

  .execution-board__grid {
    grid-template-columns: 1fr;
    grid-auto-rows: 230px;
  }
}

@media (max-width: 520px) {
  .outcome-section-heading,
  .outcome-status-card > header {
    display: grid;
  }

  .outcome-section-heading > strong,
  .outcome-status-card > header > strong {
    justify-self: start;
  }

  .verdict-card {
    height: 410px;
  }

  .outcome-status-card {
    height: 410px;
  }

  .outcome-generation {
    min-height: 300px;
  }

  .outcome-generation > header {
    display: grid;
  }

  .outcome-generation > header strong {
    justify-self: start;
  }

  .outcome-footer {
    gap: 12px;
  }

  .outcome-footer button {
    width: 100%;
  }
}

/* Four-part approved outcome archive. */
.outcome-four-stage {
  display: grid;
  min-width: 0;
  gap: 18px;
}

.outcome-primary-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(310px, .65fr);
  gap: 18px;
}

.outcome-stage {
  position: relative;
  display: grid;
  min-width: 0;
  overflow: hidden;
  background: rgba(255, 255, 255, .93);
  border: 1px solid #dfe7f0;
  border-radius: 18px;
  box-shadow: 0 14px 34px rgba(72, 91, 119, .07);
}

.outcome-stage--hearing,
.outcome-stage--review {
  height: 338px;
  grid-template-rows: auto minmax(0, 1fr) auto;
  padding: 20px;
}

.outcome-stage--plan {
  height: 220px;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 20px;
}

.outcome-stage--execution {
  height: 340px;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 20px;
}

.outcome-stage--hearing {
  border-top: 3px solid #579f96;
}

.outcome-stage--review {
  border-top: 3px solid #c49a55;
}

.outcome-stage--plan {
  border-top: 3px solid #6685ba;
}

.outcome-stage--execution {
  border-top: 3px solid #5b9d74;
}

.outcome-stage__heading {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: start;
  gap: 11px;
  min-height: 58px;
  padding-bottom: 14px;
  border-bottom: 1px solid #e5ebf2;
}

.outcome-stage__index {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #52657f;
  background: #f1f5fa;
  border: 1px solid #dce5ef;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 900;
}

.outcome-stage--hearing .outcome-stage__index {
  color: #32766f;
  background: #eaf7f4;
  border-color: #cce7e1;
}

.outcome-stage--review .outcome-stage__index {
  color: #8a6324;
  background: #fff6e5;
  border-color: #ecdcb9;
}

.outcome-stage--plan .outcome-stage__index {
  color: #4e6695;
  background: #eef2fb;
  border-color: #d6dff0;
}

.outcome-stage--execution .outcome-stage__index {
  color: #397354;
  background: #edf8f1;
  border-color: #d2e8da;
}

.outcome-stage__heading h2 {
  margin: 5px 0 0;
  color: #2f3f58;
  font-size: 18px;
  line-height: 1.3;
}

.outcome-stage__heading > strong {
  max-width: 180px;
  padding: 6px 9px;
  color: #52677f;
  background: #f3f6fa;
  border: 1px solid #dfe6ef;
  border-radius: 8px;
  font-size: 10px;
  line-height: 1.35;
  text-align: center;
}

.outcome-stage__heading > strong[data-state="active"] {
  color: #8b6220;
  background: #fff5de;
  border-color: #ead7aa;
}

.outcome-stage__heading > strong[data-state="complete"] {
  color: #347153;
  background: #edf8f1;
  border-color: #cfe5d7;
}

.outcome-stage__heading > strong[data-state="attention"] {
  color: #954753;
  background: #fff0f1;
  border-color: #efcfd3;
}

.outcome-stage__content {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 13px;
  min-height: 0;
  padding: 16px 0 12px;
}

.outcome-result-type {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 12px;
  background: #f1f8f7;
  border: 1px solid #dbece8;
  border-radius: 8px;
}

.outcome-result-type span,
.outcome-review-opinion > span,
.approved-plan-type > span,
.approved-plan-description > span {
  color: #7b889a;
  font-size: 10px;
  font-weight: 800;
}

.outcome-result-type strong {
  color: #326f69;
  font-size: 14px;
  text-align: right;
}

.outcome-scroll-copy {
  min-height: 0;
  margin: 0;
  padding-right: 6px;
  overflow-y: auto;
  color: #5f6e84;
  font-size: 12px;
  line-height: 1.72;
  white-space: pre-wrap;
  scrollbar-color: #c8d7e6 transparent;
  scrollbar-width: thin;
}

.outcome-stage__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 31px;
  padding-top: 10px;
  border-top: 1px solid #e7edf3;
}

.outcome-stage__meta span {
  color: #8995a6;
  font-size: 10px;
}

.outcome-stage__meta strong {
  color: #4e6079;
  font-size: 11px;
}

.outcome-review-opinion {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
  min-height: 0;
  padding: 17px 0 12px;
}

.outcome-review-opinion .outcome-scroll-copy {
  padding: 13px 14px;
  color: #5d566d;
  background: #faf7f1;
  border-left: 3px solid #d1ad6c;
  border-radius: 0 8px 8px 0;
}

.approved-plan-layout {
  display: grid;
  grid-template-columns: minmax(250px, .72fr) minmax(0, 1.28fr);
  gap: 18px;
  min-height: 0;
  padding-top: 14px;
}

.approved-plan-type,
.approved-plan-description {
  display: grid;
  min-height: 0;
  align-content: start;
}

.approved-plan-type {
  gap: 7px;
  padding: 12px 14px;
  background: #f2f5fb;
  border: 1px solid #dfe5f1;
  border-radius: 8px;
}

.approved-plan-type h3 {
  margin: 0;
  color: #364f80;
  font-size: 19px;
  line-height: 1.35;
}

.approved-plan-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 46px;
  overflow-y: auto;
}

.approved-plan-tags span {
  padding: 4px 7px;
  color: #597099;
  background: #fff;
  border: 1px solid #d8e0ee;
  border-radius: 7px;
  font-size: 9px;
  font-weight: 800;
}

.approved-plan-description {
  grid-template-rows: auto minmax(0, 1fr);
  gap: 8px;
  padding: 10px 0;
}

.approved-plan-description .outcome-scroll-copy {
  color: #52637b;
  font-size: 13px;
}

.mock-execution {
  display: grid;
  grid-template-rows: auto 7px minmax(0, 1fr) auto;
  gap: 11px;
  min-height: 0;
  padding-top: 14px;
}

.mock-execution__summary {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
}

.mock-execution__signal {
  position: relative;
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  background: #edf8f1;
  border: 1px solid #cfe5d7;
  border-radius: 50%;
}

.mock-execution__signal i {
  width: 10px;
  height: 10px;
  background: #5da87a;
  border-radius: 50%;
}

.mock-execution__signal[data-state="active"]::after {
  position: absolute;
  inset: 5px;
  content: "";
  border: 2px solid rgba(83, 154, 111, .35);
  border-top-color: #4f956c;
  border-radius: 50%;
  animation: execution-spin 1s linear infinite;
}

.mock-execution__summary > div:nth-child(2) {
  display: grid;
  gap: 2px;
}

.mock-execution__summary span,
.mock-execution__summary small {
  color: #8490a1;
  font-size: 9px;
}

.mock-execution__summary strong {
  color: #3f5b4b;
  font-size: 13px;
}

.mock-execution__summary b {
  color: #4d765e;
  font-size: 18px;
}

.mock-execution__bar {
  overflow: hidden;
  background: #e8edf2;
  border-radius: 7px;
}

.mock-execution__bar i {
  display: block;
  height: 100%;
  background: #65a982;
  border-radius: inherit;
  transition: width .45s ease;
}

.mock-execution__steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  min-height: 0;
  padding: 0;
  margin: 0;
  list-style: none;
}

.mock-execution__steps li {
  position: relative;
  display: grid;
  grid-template-rows: 18px auto minmax(0, 1fr);
  gap: 4px;
  min-height: 0;
  padding: 10px;
  background: #f7f9fb;
  border: 1px solid #e3e9ef;
  border-radius: 8px;
}

.mock-execution__steps li > i {
  width: 12px;
  height: 12px;
  background: #d6dee7;
  border: 3px solid #fff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px #cad4df;
}

.mock-execution__steps li[data-state="complete"] {
  background: #f0f8f3;
  border-color: #d4e8da;
}

.mock-execution__steps li[data-state="complete"] > i {
  background: #61a77e;
  box-shadow: 0 0 0 1px #a9d1b7;
}

.mock-execution__steps li[data-state="active"] {
  background: #fff8e8;
  border-color: #eadbb8;
}

.mock-execution__steps li[data-state="active"] > i {
  background: #e3ac45;
  box-shadow: 0 0 0 1px #e2c57f;
  animation: execution-pulse 1.2s ease-in-out infinite;
}

.mock-execution__steps strong {
  color: #4d5d72;
  font-size: 10px;
}

.mock-execution__steps span {
  color: #8793a3;
  font-size: 9px;
  line-height: 1.45;
}

.mock-execution__notice {
  margin: 0;
  color: #8a7460;
  font-size: 9px;
  line-height: 1.45;
}

.execution-board__grid--stage {
  min-height: 0;
  padding: 14px 5px 0 0;
  margin: 0;
  overflow-y: auto;
  grid-auto-rows: 210px;
  scrollbar-color: #c8d7e6 transparent;
  scrollbar-width: thin;
}

.execution-board__grid--stage article {
  height: 210px;
}

@media (max-width: 900px) {
  .outcome-primary-grid {
    grid-template-columns: 1fr;
  }

  .outcome-stage--hearing {
    height: 340px;
  }

  .outcome-stage--review {
    height: 300px;
  }
}

@media (max-width: 720px) {
  .outcome-four-stage,
  .outcome-primary-grid {
    gap: 14px;
  }

  .outcome-stage--hearing,
  .outcome-stage--review,
  .outcome-stage--plan,
  .outcome-stage--execution {
    padding: 16px;
    border-radius: 14px;
  }

  .outcome-stage--plan {
    height: 330px;
  }

  .outcome-stage--execution {
    height: 460px;
  }

  .approved-plan-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
    gap: 10px;
  }

  .approved-plan-tags {
    max-height: 28px;
  }

  .mock-execution__steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .execution-board__grid--stage {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 420px) {
  .outcome-stage__heading {
    grid-template-columns: 36px minmax(0, 1fr);
  }

  .outcome-stage__heading > strong {
    grid-column: 2;
    justify-self: start;
    max-width: 100%;
  }

  .outcome-stage--hearing {
    height: 380px;
  }

  .outcome-stage--review {
    height: 330px;
  }

  .outcome-stage--plan {
    height: 360px;
  }

  .outcome-stage--execution {
    height: 500px;
  }

  .mock-execution__summary {
    grid-template-columns: 32px minmax(0, 1fr) auto;
  }

  .mock-execution__signal {
    width: 30px;
    height: 30px;
  }
}

/* Align the result archive with the existing pastel room surfaces. */
.outcome-stage {
  padding: 18px;
  background: rgba(255, 255, 255, .75);
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px rgba(85, 109, 149, .07);
}

.outcome-stage--hearing,
.outcome-stage--review {
  height: 410px;
}

.outcome-stage--plan {
  height: 270px;
}

.outcome-stage--execution {
  height: 400px;
}

.outcome-stage--hearing,
.outcome-stage--review,
.outcome-stage--plan,
.outcome-stage--execution {
  border-top-width: 1px;
  border-top-color: #dfe8f4;
}

.outcome-stage__heading {
  height: 92px;
  min-height: 92px;
  align-items: center;
  padding: 15px 16px 18px;
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, .95), transparent 34%),
    linear-gradient(135deg, #f8fbff, #f6f3ff);
  border: 1px solid #dce8f4;
  border-radius: 18px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .92);
}

.outcome-stage--review .outcome-stage__heading {
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, .95), transparent 34%),
    linear-gradient(135deg, #fffaf0, #f7faff);
}

.outcome-stage--plan .outcome-stage__heading,
.outcome-stage--execution .outcome-stage__heading {
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, .95), transparent 34%),
    linear-gradient(135deg, #f4f7ff, #f2fbf7);
}

.outcome-stage--hearing .outcome-stage__index,
.outcome-stage--review .outcome-stage__index,
.outcome-stage--plan .outcome-stage__index,
.outcome-stage--execution .outcome-stage__index {
  width: 34px;
  height: 34px;
  color: #fff;
  background: linear-gradient(135deg, #8ca2ff, #77dfb7);
  border: 0;
  border-radius: 14px;
  box-shadow: 0 10px 24px rgba(96, 122, 180, .22);
}

.outcome-stage__heading > strong {
  color: #60718b;
  background: rgba(255, 255, 255, .82);
  border-color: #dfe7f2;
  border-radius: 999px;
}

.outcome-stage__heading > strong[data-state="active"] {
  color: #526bc1;
  background: #eef3ff;
  border-color: #dbe4fb;
}

.outcome-stage__heading > strong[data-state="complete"] {
  color: #347153;
  background: #eaf8f1;
  border-color: #d0e8da;
}

.outcome-stage__content {
  padding: 16px 2px 12px;
}

.outcome-result-type {
  background: linear-gradient(135deg, #f8fbff, #f2fbf7);
  border-color: #dce9ed;
  border-radius: 14px;
}

.outcome-review-opinion {
  padding: 16px 2px 12px;
}

.outcome-review-opinion .outcome-scroll-copy {
  padding: 14px;
  background: #fffaf0;
  border: 1px solid #efdfbb;
  border-left-width: 1px;
  border-radius: 16px;
}

.approved-plan-layout {
  padding-top: 14px;
}

.approved-plan-type,
.approved-plan-description {
  padding: 14px;
  background: linear-gradient(135deg, #fff, #f9fbff);
  border: 1px solid #dfe8f4;
  border-radius: 18px;
  box-shadow: 0 8px 22px rgba(88, 119, 155, .04);
}

.approved-plan-type {
  align-content: start;
}

.approved-plan-description {
  gap: 8px;
}

.approved-plan-tags span {
  color: #536bc0;
  background: #eef3ff;
  border-color: #dbe4fb;
  border-radius: 999px;
}

.mock-execution {
  margin-top: 14px;
  padding: 15px;
  background: linear-gradient(135deg, #f4f7ff, #f2fbf7);
  border: 1px solid #dfe8f4;
  border-radius: 18px;
}

.mock-execution__signal {
  background: linear-gradient(135deg, #eef3ff, #eaf8f1);
  border-color: #d7e3ec;
}

.mock-execution__bar {
  background: #e8eef7;
}

.mock-execution__bar i {
  background: linear-gradient(90deg, #7e93e5, #69c2a4);
}

.mock-execution__steps li {
  background: rgba(255, 255, 255, .82);
  border-color: #dfe8f4;
  border-radius: 14px;
  box-shadow: inset 0 1px 0 #fff;
}

.mock-execution__steps li[data-state="complete"] {
  background: rgba(229, 250, 240, .82);
  border-color: #d2eadc;
}

.mock-execution__steps li[data-state="active"] {
  background: #f1f5ff;
  border-color: #dbe4fb;
}

.execution-board__grid--stage {
  margin-top: 14px;
  padding: 0 5px 0 0;
}

.execution-board__grid--stage article {
  background: linear-gradient(135deg, #fff, #f9fbff);
  border-color: #dfe8f4;
  border-radius: 18px;
  box-shadow: 0 8px 22px rgba(88, 119, 155, .04);
}

@media (max-width: 900px) {
  .outcome-stage--hearing {
    height: 420px;
  }

  .outcome-stage--review {
    height: 360px;
  }
}

@media (max-width: 720px) {
  .outcome-stage--hearing,
  .outcome-stage--review,
  .outcome-stage--plan,
  .outcome-stage--execution {
    padding: 14px;
    border-radius: 24px;
  }

  .outcome-stage--plan {
    height: 400px;
  }

  .outcome-stage--execution {
    height: 540px;
  }

  .outcome-stage__heading {
    height: auto;
    min-height: 88px;
    padding: 13px 14px;
  }
}

@media (max-width: 420px) {
  .outcome-stage--hearing {
    height: 460px;
  }

  .outcome-stage--review {
    height: 390px;
  }

  .outcome-stage--plan {
    height: 440px;
  }

  .outcome-stage--execution {
    height: 590px;
  }
}

@keyframes execution-pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.35); opacity: .65; }
}

@media (prefers-reduced-motion: reduce) {
  .mock-execution__signal[data-state="active"]::after,
  .mock-execution__steps li[data-state="active"] > i {
    animation: none;
  }

  .mock-execution__bar i {
    transition: none;
  }
}
</style>
