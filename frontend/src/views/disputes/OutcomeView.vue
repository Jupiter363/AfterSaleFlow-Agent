<!--
  文件作用：展示审核提交后由后端发布的最终执行事件通知。
  边界：只读取人工终审决定与真实动作记录，不展示草案链路，也不在前端模拟执行。
-->

<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { disputeApi } from "../../api/disputes";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";

const props = defineProps({
  initialOutcome: { type: Object, default: null },
});

const route = useRoute();
const outcome = ref(props.initialOutcome);
const loading = ref(props.initialOutcome === null);
const error = ref("");

const DECISION_ACTIONS = Object.freeze({
  CANCEL_ORDER: {
    label: "取消订单",
    description: "订单取消决定已经发布，后续结算与款项处理将按平台执行结果推进。",
  },
  RETURN_AND_REFUND: {
    label: "退货退款",
    description: "退货退款决定已经发布，用户退回商品后由商家退还相应款项。",
  },
  REFUND_ONLY: {
    label: "仅退款",
    description: "退款决定已经发布，用户无需退货，款项将按平台执行结果处理。",
  },
  RESHIP: {
    label: "补发商品",
    description: "补发决定已经发布，商家将为未送达、漏发或缺失商品安排补发。",
  },
  REPLACE: {
    label: "更换商品",
    description: "换货决定已经发布，商家将为存在问题的商品安排更换。",
  },
  REPAIR: {
    label: "维修商品",
    description: "维修决定已经发布，商家将为争议商品安排维修处理。",
  },
  COMPENSATE: {
    label: "补偿",
    description: "补偿决定已经发布，平台将按审核确定的方式推进补偿处理。",
  },
  CONTINUE_FULFILLMENT: {
    label: "继续履约",
    description: "继续履约决定已经发布，当前订单关系保持并继续完成原有履约义务。",
  },
  REJECT_CLAIM: {
    label: "驳回诉求",
    description: "驳回诉求决定已经发布，本次售后诉求不予支持并进入结案处理。",
  },
  ESCALATE_MANUAL: {
    label: "人工接管",
    description: "自动裁决流程已经终止，案件已移交人工专员继续处理。",
  },
});

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value
    : {};
}

function firstText(source, ...keys) {
  for (const key of keys) {
    const value = source?.[key];
    if (value !== undefined && value !== null && String(value).trim()) {
      return String(value).trim();
    }
  }
  return "";
}

function normalizedCode(value) {
  return String(value || "").trim().toUpperCase();
}

function actionFromRequest(value) {
  const request = asObject(value);
  const candidates = [
    request,
    asObject(request.action),
    asObject(request.approved_plan),
    asObject(request.approvedPlan),
    asObject(request.review_decision),
    asObject(request.reviewDecision),
  ];
  for (const candidate of candidates) {
    const code = firstText(
      candidate,
      "decision_action",
      "decisionAction",
      "action_code",
      "actionCode",
    );
    if (code) return normalizedCode(code);
  }
  return "";
}

function timeValue(record) {
  const source = asObject(record);
  const raw = firstText(
    source,
    "execution_time",
    "executionTime",
    "created_at",
    "createdAt",
  );
  const parsed = Date.parse(raw);
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

const caseId = computed(() =>
  String(outcome.value?.case_id || outcome.value?.caseId || route.params.caseId || ""),
);
const caseTitle = computed(() => outcome.value?.title || "售后争议案件");
const finalDecision = computed(() =>
  asObject(outcome.value?.final_decision || outcome.value?.finalDecision),
);
const approvedPlan = computed(() =>
  asObject(finalDecision.value.approved_plan || finalDecision.value.approvedPlan),
);
const actionRecords = computed(() => {
  const records = Array.isArray(outcome.value?.actions)
    ? outcome.value.actions.filter(Boolean)
    : [];
  return [...records].sort((left, right) => timeValue(left) - timeValue(right));
});
const latestAction = computed(() => actionRecords.value.at(-1) || null);
const reviewTaskStatus = computed(() =>
  normalizedCode(outcome.value?.review_task_status || outcome.value?.reviewTaskStatus),
);
const caseStatus = computed(() =>
  normalizedCode(outcome.value?.case_status || outcome.value?.caseStatus),
);
const reviewerDecisionAction = computed(() =>
  normalizedCode(
    firstText(
      finalDecision.value,
      "reviewer_decision_action",
      "reviewerDecisionAction",
    ),
  ),
);
const decisionAction = computed(() => {
  if (reviewerDecisionAction.value) return reviewerDecisionAction.value;
  const planAction = firstText(approvedPlan.value, "decision_action", "decisionAction");
  if (planAction) return normalizedCode(planAction);
  const requestAction = actionFromRequest(latestAction.value?.request);
  if (requestAction) return requestAction;
  if (
    reviewTaskStatus.value === "ESCALATED" ||
    caseStatus.value === "MANUAL_HANDOFF"
  ) {
    return "ESCALATE_MANUAL";
  }
  return "";
});
const actionDefinition = computed(() =>
  DECISION_ACTIONS[decisionAction.value] || {
    label: "最终决定",
    description: "审核员的最终决定已经发布，平台将按该决定推进后续处理。",
  },
);
const executionStatus = computed(() =>
  normalizedCode(latestAction.value?.execution_status || latestAction.value?.executionStatus),
);
const isApproved = computed(() => {
  const confirmed = finalDecision.value.human_confirmed ?? finalDecision.value.humanConfirmed;
  return (
    confirmed === true ||
    reviewTaskStatus.value === "APPROVED" ||
    ["APPROVED_FOR_EXECUTION", "EXECUTING", "CLOSED"].includes(caseStatus.value)
  );
});
const eventState = computed(() => {
  if (error.value) return "error";
  if (loading.value) return "loading";
  if (decisionAction.value === "ESCALATE_MANUAL") return "manual";
  if (executionStatus.value === "FAILED") return "failed";
  if (executionStatus.value === "SUCCEEDED" || caseStatus.value === "CLOSED") return "complete";
  if (isApproved.value) return "published";
  return "waiting";
});
const eventTitle = computed(() => {
  const label = actionDefinition.value.label;
  return {
    error: "执行事件读取失败",
    loading: "正在读取最终执行事件",
    manual: "案件已升级人工接管",
    failed: `${label}执行未完成`,
    complete: `${label}已完成`,
    published: `${label}执行事件已发布`,
    waiting: "终审决定尚未发布",
  }[eventState.value];
});
const eventBadge = computed(() => ({
  error: "读取失败",
  loading: "读取中",
  manual: "人工处理中",
  failed: "执行异常",
  complete: "执行完成",
  published: "已进入执行",
  waiting: "等待终审",
})[eventState.value]);
const eventMark = computed(() => ({
  error: "!",
  loading: "···",
  manual: "转",
  failed: "!",
  complete: "✓",
  published: "✓",
  waiting: "待",
})[eventState.value]);
const eventDescription = computed(() => {
  if (error.value) return error.value;
  if (eventState.value === "loading") return "正在同步平台最新执行状态，请稍候。";
  if (eventState.value === "waiting") return "案件尚未形成可执行的人工终审决定。";
  if (eventState.value === "failed") {
    return firstText(latestAction.value, "error_message", "errorMessage") ||
      "本次执行未成功完成，平台将继续处理该异常。";
  }
  return actionDefinition.value.description;
});
const eventTime = computed(() => {
  const raw = firstText(
    latestAction.value,
    "execution_time",
    "executionTime",
    "created_at",
    "createdAt",
  ) || firstText(finalDecision.value, "decided_at", "decidedAt") ||
    firstText(outcome.value, "closed_at", "closedAt");
  return formatTime(raw);
});
const eventReference = computed(() =>
  firstText(
    latestAction.value,
    "external_result_ref",
    "externalResultRef",
    "action_record_id",
    "actionRecordId",
  ) || firstText(finalDecision.value, "approval_record_id", "approvalRecordId"),
);

async function loadOutcome() {
  loading.value = true;
  error.value = "";
  try {
    outcome.value = await disputeApi.outcome(actor, caseId.value);
  } catch (requestError) {
    error.value = requestError?.message || "暂时无法读取最终执行事件，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.initialOutcome,
  (value) => {
    if (value !== null) {
      outcome.value = value;
      loading.value = false;
      error.value = "";
    }
  },
);

onMounted(() => {
  if (props.initialOutcome === null) loadOutcome();
});
</script>

<template>
  <main class="outcome-page">
    <RoomShell
      eyebrow="平台执行中心"
      title="执行结果"
      :case-id="caseId"
      :subtitle="caseTitle"
      :show-case-id="false"
      :show-connection="false"
      :show-boundary="false"
    >
      <section
        class="execution-event"
        :data-state="eventState"
        data-final-execution-event
        :aria-live="eventState === 'loading' ? 'polite' : 'off'"
      >
        <header class="execution-event__masthead">
          <p>最终执行事件</p>
          <span class="execution-event__badge">
            <i aria-hidden="true"></i>
            {{ eventBadge }}
          </span>
        </header>

        <div v-if="eventState === 'loading'" class="execution-event__loading">
          <span class="sr-only">正在读取最终执行事件</span>
          <div class="execution-event__loading-copy" aria-hidden="true">
            <span></span>
            <span></span>
            <span></span>
          </div>
          <div class="execution-event__loading-mark" aria-hidden="true"></div>
        </div>

        <template v-else>
          <div class="execution-event__content">
            <div class="execution-event__message">
              <p class="execution-event__kicker">终审执行决定</p>
              <h2>{{ eventTitle }}</h2>
              <p class="execution-event__description">{{ eventDescription }}</p>
            </div>

            <div class="execution-event__signal" aria-hidden="true">
              <span>{{ eventMark }}</span>
              <small>平台发布</small>
            </div>
          </div>

          <dl v-if="eventTime || eventReference" class="execution-event__meta">
            <div v-if="eventTime">
              <dt>发布时间</dt>
              <dd>{{ eventTime }}</dd>
            </div>
            <div v-if="eventReference">
              <dt>事件编号</dt>
              <dd>{{ eventReference }}</dd>
            </div>
          </dl>

          <div v-if="eventState === 'error'" class="execution-event__actions">
            <button
              type="button"
              class="execution-event__retry"
              @click="loadOutcome"
            >
              重新读取
            </button>
          </div>
        </template>
      </section>

      <p class="outcome-page__notice">
        <span aria-hidden="true"></span>
        用户与商家可在消息中心查看平台后续通知
      </p>
    </RoomShell>
  </main>
</template>

<style scoped>
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.outcome-page {
  box-sizing: border-box;
  min-height: 100dvh;
  padding: 8px clamp(22px, 3.2vw, 42px) clamp(22px, 3.2vw, 42px);
  color: #425a70;
  background: transparent;
}

.outcome-page :deep(.room-shell) {
  width: min(1120px, 100%);
  min-height: 0;
  margin: 0 auto;
  gap: clamp(20px, 2.4vw, 28px);
  align-content: start;
}

.outcome-page :deep(.room-shell__header) {
  align-items: flex-end;
}

.outcome-page :deep(.room-shell__eyebrow) {
  color: #4f7193;
  font-size: 12px;
  letter-spacing: .12em;
}

.outcome-page :deep(.room-shell__header h1) {
  margin-top: 10px;
  color: #38536d;
  font-size: clamp(35px, 4.4vw, 50px);
  letter-spacing: -.045em;
}

.outcome-page :deep(.room-shell__lead) {
  color: #5f7284;
}

.outcome-page :deep(.room-shell__context) {
  color: #5b7083;
  font-size: 14px;
}

.outcome-page :deep(.room-shell__context i) {
  display: none;
}

.outcome-page :deep(.room-shell__agent:empty) {
  display: none;
}

.execution-event {
  --event-accent: #7fa6cd;
  --event-accent-strong: #3f5e7a;
  --event-accent-soft: #edf5fc;
  --event-accent-border: #bfd2e4;
  --event-secondary: #7fa6cd;
  --event-warm: #91aec9;
  --event-coral: #789fc6;
  --event-masthead: linear-gradient(110deg, #f1f7fd 0%, #e8f2fa 100%);
  --event-masthead-text: #4b6780;
  --event-surface: #f9fcff;
  --event-meta-surface: linear-gradient(90deg, #f3f8fc 0%, #eaf3fa 100%);
  --event-copy: #5c7183;
  position: relative;
  overflow: hidden;
  width: min(980px, 100%);
  margin: 0 auto;
  color: #425c73;
  background: linear-gradient(145deg, #fbfdff 0%, #f4f9fd 54%, #edf5fb 100%);
  border: 1px solid #cbdceb;
  border-radius: 28px;
  box-shadow: 0 24px 58px rgba(63, 91, 119, .10);
  animation: event-enter 520ms cubic-bezier(.22, .8, .28, 1) both;
}

.execution-event::before {
  position: absolute;
  inset: 0 0 auto;
  z-index: 2;
  height: 5px;
  content: "";
  background: var(--event-accent);
}

.execution-event__masthead {
  display: flex;
  min-height: 60px;
  box-sizing: border-box;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px clamp(24px, 4vw, 44px);
  color: var(--event-masthead-text);
  background: var(--event-masthead);
  border-bottom: 1px solid #d4e2ee;
}

.execution-event__masthead p {
  margin: 0;
  font-size: 14px;
  font-weight: 850;
  letter-spacing: .08em;
}

.execution-event__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  color: #456b90;
  background: rgba(255, 255, 255, .72);
  border: 1px solid #bfd3e5;
  border-radius: 999px;
  box-shadow: 0 5px 14px rgba(68, 104, 137, .10), inset 0 1px 0 rgba(255, 255, 255, .9);
  font-size: 13px;
  font-weight: 850;
}

.execution-event__badge i {
  width: 7px;
  height: 7px;
  background: var(--event-secondary);
  border-radius: 50%;
  box-shadow: 0 0 0 4px #deebf6;
}

.execution-event__content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: clamp(28px, 6vw, 76px);
  align-items: center;
  min-height: 240px;
  padding: clamp(38px, 5vw, 58px) clamp(28px, 5vw, 58px);
}

.execution-event__message {
  min-width: 0;
}

.execution-event__kicker {
  margin: 0 0 16px;
  color: #5a7186;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: .08em;
}

.execution-event__signal {
  position: relative;
  display: grid;
  width: clamp(116px, 14vw, 148px);
  aspect-ratio: 1;
  place-items: center;
  align-content: center;
  gap: 9px;
  color: var(--event-accent-strong);
  background: var(--event-accent);
  border: 0;
  border-radius: 50%;
  box-shadow: 0 16px 34px rgba(69, 99, 128, .12);
  animation: signal-enter 620ms 120ms cubic-bezier(.2, .8, .2, 1) both;
}

.execution-event__signal::before {
  position: absolute;
  inset: 8px;
  content: "";
  background: var(--event-surface);
  border-radius: 50%;
}

.execution-event__signal::after {
  position: absolute;
  inset: 17px;
  content: "";
  border: 1px solid rgba(91, 132, 170, .24);
  border-radius: 50%;
}

.execution-event__signal span {
  position: relative;
  z-index: 1;
  font-size: clamp(34px, 5vw, 52px);
  font-weight: 900;
  line-height: 1;
  color: #5d86ae;
}

.execution-event__signal small {
  position: relative;
  z-index: 1;
  font-size: 12px;
  font-weight: 850;
  letter-spacing: .12em;
}

.execution-event h2 {
  max-width: 650px;
  margin: 0;
  color: var(--event-accent-strong);
  font-size: clamp(32px, 4.4vw, 46px);
  line-height: 1.13;
  letter-spacing: -.035em;
}

.execution-event__description {
  max-width: 660px;
  margin: 18px 0 0;
  color: var(--event-copy);
  font-size: clamp(16px, 2vw, 18px);
  line-height: 1.7;
}

.execution-event__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  padding: 0 clamp(28px, 5vw, 58px);
  background: var(--event-meta-surface);
  border-top: 1px solid #d9e5ef;
}

.execution-event__meta div {
  min-width: 0;
  padding: 23px 0 26px;
}

.execution-event__meta div + div {
  padding-left: clamp(24px, 4vw, 48px);
  border-left: 1px solid #d4e1eb;
}

.execution-event__meta dt {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
  color: #596f83;
  font-size: 12px;
  font-weight: 750;
  letter-spacing: .06em;
}

.execution-event__meta dt::before {
  width: 4px;
  height: 13px;
  content: "";
  background: var(--event-accent);
  border-radius: 999px;
}

.execution-event__meta div:nth-child(2) dt::before {
  background: var(--event-secondary);
}

.execution-event__meta dd {
  margin: 0;
  color: #425e77;
  font-size: 14px;
  font-weight: 800;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.execution-event__actions {
  padding: 0 clamp(28px, 5vw, 58px) 32px;
}

.execution-event__retry {
  min-height: 44px;
  padding: 11px 20px;
  color: #fff;
  background: var(--event-accent-strong);
  border: 0;
  border-radius: 10px;
  font: inherit;
  font-weight: 800;
  cursor: pointer;
  transition: transform 160ms ease, box-shadow 160ms ease;
}

.execution-event__retry:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(35, 68, 108, .2);
}

.execution-event__retry:focus-visible {
  outline: 3px solid var(--event-accent-border);
  outline-offset: 3px;
}

.execution-event__loading {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: clamp(28px, 6vw, 76px);
  align-items: center;
  min-height: 240px;
  padding: clamp(38px, 5vw, 58px) clamp(28px, 5vw, 58px);
}

.execution-event__loading-copy {
  display: grid;
  gap: 16px;
}

.execution-event__loading-copy span,
.execution-event__loading-mark {
  background: #e1ebf4;
  animation: loading-pulse 1.25s ease-in-out infinite alternate;
}

.execution-event__loading-copy span {
  display: block;
  height: 16px;
  border-radius: 8px;
}

.execution-event__loading-copy span:first-child { width: 24%; }
.execution-event__loading-copy span:nth-child(2) { width: 72%; height: 42px; }
.execution-event__loading-copy span:last-child { width: 86%; }
.execution-event__loading-mark {
  width: clamp(116px, 14vw, 148px);
  aspect-ratio: 1;
  border-radius: 50%;
}

.outcome-page__notice {
  display: flex;
  width: min(980px, 100%);
  box-sizing: border-box;
  align-items: center;
  gap: 10px;
  margin: 18px auto 0;
  padding: 0 4px;
  color: #5f7385;
  font-size: 13px;
}

.outcome-page__notice span {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  background: #84a8ca;
  border-radius: 50%;
}

.execution-event[data-state="manual"] {
  --event-accent: #b7626e;
  --event-accent-strong: #764955;
  --event-accent-soft: #fbefef;
  --event-accent-border: #dfbdc2;
  --event-secondary: #b77973;
  --event-warm: #d0a064;
  --event-coral: #aa5967;
  --event-masthead: #faeceb;
  --event-masthead-text: #7b4c55;
  --event-meta-surface: #faf5f3;
}

.execution-event[data-state="failed"],
.execution-event[data-state="error"] {
  --event-accent: #bd6265;
  --event-accent-strong: #7e4549;
  --event-accent-soft: #fbefef;
  --event-accent-border: #e0babc;
  --event-secondary: #be7a68;
  --event-warm: #d3a15f;
  --event-coral: #aa5559;
  --event-masthead: #fbeceb;
  --event-masthead-text: #7c464a;
  --event-meta-surface: #faf4f3;
}

.execution-event[data-state="waiting"],
.execution-event[data-state="loading"] {
  --event-accent: #8b8d88;
  --event-accent-strong: #626b70;
  --event-accent-soft: #f2f1ee;
  --event-accent-border: #d3d0c9;
  --event-secondary: #8a9d94;
  --event-warm: #b6a27b;
  --event-coral: #a58b83;
  --event-masthead: #f3f1ed;
  --event-masthead-text: #666760;
  --event-meta-surface: #f6f4f0;
}

.execution-event[data-state="manual"] .execution-event__badge,
.execution-event[data-state="failed"] .execution-event__badge,
.execution-event[data-state="error"] .execution-event__badge {
  color: var(--event-accent-strong);
  background: var(--event-accent-soft);
  border-color: var(--event-accent-border);
}

.execution-event[data-state="waiting"] .execution-event__badge,
.execution-event[data-state="loading"] .execution-event__badge {
  color: #626b70;
  background: #f8f7f4;
  border-color: #d4d0c8;
}

@keyframes event-enter {
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes signal-enter {
  from { opacity: 0; transform: scale(.9) rotate(-3deg); }
  to { opacity: 1; transform: scale(1) rotate(0); }
}

@keyframes loading-pulse {
  from { opacity: .55; }
  to { opacity: 1; }
}

@container room-workspace (max-width: 920px) {
  .execution-event__content,
  .execution-event__loading {
    gap: 32px;
    padding-right: 42px;
    padding-left: 42px;
  }

  .execution-event__signal,
  .execution-event__loading-mark {
    width: 120px;
  }

  .execution-event h2 {
    font-size: 42px;
  }

  .execution-event__meta {
    padding-right: 42px;
    padding-left: 42px;
  }
}

@media (min-width: 681px) and (max-width: 1100px) {
  .execution-event__content,
  .execution-event__loading {
    grid-template-columns: minmax(0, 1fr) 112px;
    gap: 24px;
    padding: 38px 40px 42px;
  }

  .execution-event__signal,
  .execution-event__loading-mark {
    width: 112px;
  }

  .execution-event h2 {
    font-size: 38px;
  }

  .execution-event__meta {
    padding-right: 40px;
    padding-left: 40px;
  }
}

@media (max-width: 680px) {
  .outcome-page { padding: 24px 14px 38px; }

  .outcome-page :deep(.room-shell) { gap: 26px; }

  .outcome-page :deep(.room-shell__header h1) { font-size: 42px; }

  .execution-event {
    border-radius: 22px;
  }

  .execution-event__masthead {
    min-height: 62px;
    padding: 16px 20px;
  }

  .execution-event__content,
  .execution-event__loading {
    grid-template-columns: 1fr;
    min-height: 0;
    padding: 34px 22px 38px;
  }

  .execution-event__signal,
  .execution-event__loading-mark {
    width: 112px;
    grid-row: 1;
  }

  .execution-event__meta {
    grid-template-columns: 1fr;
    padding: 0 22px;
  }

  .execution-event__meta div + div {
    padding-top: 0;
    padding-left: 0;
    border-left: 0;
  }

  .execution-event__actions { padding: 0 22px 28px; }
}

@media (prefers-reduced-motion: reduce) {
  .execution-event,
  .execution-event__signal,
  .execution-event__loading-copy span,
  .execution-event__loading-mark {
    animation: none;
  }

  .execution-event__retry { transition: none; }
}
</style>
