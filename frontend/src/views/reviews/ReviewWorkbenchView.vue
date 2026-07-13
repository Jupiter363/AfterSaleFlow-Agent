<!--
  文件作用：前端页面视图文件，组织售后争议对应页面的数据加载、交互和展示。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { extractAgentRunDescriptor } from "../../api/agentStream";
import { reviewApi } from "../../api/review";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import AgentStreamErrorDialog from "../../components/room/AgentStreamErrorDialog.vue";
import AgentStreamingMessage from "../../components/room/AgentStreamingMessage.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
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
const packet = ref(props.initialPacket);
const reason = ref("");
const pendingDecision = ref("");
const decisionResult = ref(null);
const error = ref("");
const submitting = ref(false);
const agentState = ref("LISTENING");
const copilotQuestion = ref("");
const copilotMessages = ref([]);
const copilotSubmitting = ref(false);
const copilotStreamError = ref("");
const reviewId = computed(() => route.params.reviewId);
const role = computed(() => props.viewerRole || actor.role);
const canDecide = computed(
  () => role.value === "PLATFORM_REVIEWER" && packet.value?.status === "FROZEN",
);
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const copilotContext = computed(() => ({
  caseId: packet.value?.case_id || "",
  roomType: "REVIEW",
  actor,
}));
const copilotRuns = computed(() => {
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
  () => role.value === "PLATFORM_REVIEWER" && Boolean(packet.value),
);
const digitalHumanState = computed(() =>
  copilotBusy.value ? "THINKING" : agentState.value,
);
const packetExpiry = computed(
  () =>
    packet.value?.expires_at ||
    new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
);
const decisions = [
  ["REQUEST_MORE_EVIDENCE", "要求补证"],
  ["ESCALATE_MANUAL", "升级人工会商"],
  ["REJECT", "驳回草案"],
  ["MODIFY_AND_APPROVE", "修改并批准"],
  ["APPROVE", "批准执行"],
];
const roleLabels = {
  user: "用户",
  merchant: "商家",
  platform: "平台",
  USER: "用户",
  MERCHANT: "商家",
  PLATFORM: "平台",
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
};

// 业务位置：【前端审核工作台】displayDateTime：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function displayDateTime(value) {
  if (!value) return "未冻结";
  const date = new Date(value);
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  return `${date.getMonth() + 1}月${date.getDate()}日 ${hour}:${minute}`;
}

// 业务位置：【前端审核工作台】displayValue：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function displayValue(value) {
  if (value === null || value === undefined || value === "") return "未提供";
  if (Array.isArray(value)) return value.map(displayValue).join("、");
  if (typeof value === "object") {
    const entries = Object.entries(value).filter(
      ([, item]) => item !== null && item !== undefined && item !== "",
    );
    if (!entries.length) return "未提供";
    return entries
      .map(([key, item]) => `${key}: ${displayValue(item)}`)
      .join("；");
  }
  return String(value);
}

// 业务位置：【前端审核工作台】claimEntries：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function claimEntries(claims) {
  if (!claims) return [];
  if (Array.isArray(claims)) {
    return claims.map((item, index) => ({
      label: roleLabels[item.party] || roleLabels[item.role] || `主张 ${index + 1}`,
      text: displayValue(item.claim || item.text || item),
    }));
  }
  if (typeof claims === "object") {
    return Object.entries(claims).map(([roleName, text]) => ({
      label: roleLabels[roleName] || roleName,
      text: displayValue(text),
    }));
  }
  return [{ label: "主张", text: displayValue(claims) }];
}

// 业务位置：【前端审核工作台】listEntries：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function listEntries(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

// 业务位置：【前端审核工作台】issueText：判断 面向当事人的业务文本 是否满足当前流程分支的进入条件。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function issueText(issue) {
  return displayValue(issue.issue || issue.title || issue);
}

// 业务位置：【前端审核工作台】evidenceRows：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function evidenceRows(matrix) {
  return listEntries(matrix).map((row, index) => ({
    issue: displayValue(row.issue || row.title || `证据组 ${index + 1}`),
    supporting: listEntries(row.supporting || row.evidence_ids || row.evidence || row.items),
    conclusion: displayValue(row.conclusion || row.note || row.status || ""),
  }));
}

// 业务位置：【前端审核工作台】remedyActions：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function remedyActions(remedy) {
  if (!remedy) return [];
  return listEntries(remedy.actions || remedy.action || remedy).map((action, index) => ({
    title: displayValue(
      action.action_type ||
        action.actionType ||
        action.type ||
        action.name ||
        `执行动作 ${index + 1}`,
    ),
    detail: displayValue({
      amount: action.amount,
      target: action.target,
      deadline: action.deadline || action.due_at || action.dueAt,
      risk_level: action.risk_level || action.riskLevel,
      requires_approval: action.requires_approval ?? action.requiresApproval,
      preconditions: action.preconditions,
      parameters: action.parameters,
      note: action.note || action.description,
    }),
  }));
}

// 业务位置：【前端审核工作台】draftAttention：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function draftAttention(draft) {
  return listEntries(draft?.reviewer_attention);
}

// 业务位置：【前端审核工作台】draftDecision：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function draftDecision(draft) {
  return displayValue(
    draft?.recommended_decision ||
      draft?.recommendedDecision ||
      draft?.recommended_outcome ||
      draft?.recommendedOutcome ||
      draft?.conclusion ||
      draft?.decision ||
      "等待草案",
  );
}

// 业务位置：【前端审核工作台】draftReasoning：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function draftReasoning(draft) {
  return (
    draft?.draft_text ||
    draft?.draftText ||
    draft?.reasoning_summary ||
    draft?.reasoningSummary ||
    draft?.reasoning ||
    draft?.reason ||
    ""
  );
}

// 业务位置：【前端审核工作台】riskLabel：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function riskLabel(risk) {
  return riskLabels[risk] || risk || "未评估";
}

// 业务位置：【前端审核工作台】packetStatusLabel：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function packetStatusLabel(status) {
  return packetStatusLabels[status] || status || "未知";
}

// 业务位置：【前端审核工作台】load：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function load() {
  try {
    if (packet.value === null) {
      packet.value = await reviewApi.packet(actor, reviewId.value);
    }
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
    return;
  }
  if (props.initialPacket) return;
  try {
    await resumeCopilotRuns();
  } catch (failure) {
    copilotStreamError.value =
      failure?.message || "无法恢复审核解释官的生成任务。";
    agentState.value = "ERROR";
  }
}

// 业务位置：【前端审核工作台】appendCopilotAnswer：更新 当前阶段业务数据 的消息、缓存或持久记录，避免旧回合数据影响当前处理。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function appendCopilotAnswer(result, run) {
  const answer = String(result?.answer || "").trim();
  if (!answer) return;
  if (
    copilotMessages.value.some(
      (message) => message.agent_run_id === run.runId,
    )
  ) return;
  copilotMessages.value.push({
    id: `answer-${run.runId}`,
    sender_role: "REVIEW_COPILOT",
    agent_run_id: run.runId,
    text: answer,
  });
}

// 业务位置：【前端审核工作台】consumeCopilotRun：执行 当前阶段业务数据 对应的业务动作，并将结果交给 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function consumeCopilotRun(rawDescriptor) {
  const descriptor = extractAgentRunDescriptor(rawDescriptor);
  if (!descriptor) throw new Error("服务未返回有效的审核解释官流任务");
  return consumeAgentRun({
    actor,
    caseId: packet.value?.case_id || "",
    roomType: "REVIEW",
    descriptor,
    agentLabel: "小译 · 审核解释官",
    senderRole: "REVIEW_COPILOT",
    onFinal: (result, run) => appendCopilotAnswer(result, run),
    onError: (streamFailure) => {
      copilotStreamError.value = streamFailure.message;
      agentState.value = "ERROR";
    },
  });
}

// 业务位置：【前端审核工作台】resumeCopilotRuns：执行 当前阶段业务数据 对应的业务动作，并将结果交给 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function resumeCopilotRuns() {
  if (!packet.value || role.value !== "PLATFORM_REVIEWER") return;
  const activeRuns = await reviewApi.activeCopilotRuns(actor, reviewId.value);
  await Promise.all(activeRuns.map((run) => consumeCopilotRun(run)));
}

// 业务位置：【前端审核工作台】submitCopilotQuestion：执行 当前阶段业务数据 对应的业务动作，并将结果交给 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function submitCopilotQuestion() {
  const question = copilotQuestion.value.trim();
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

// 业务位置：【前端审核工作台】requestDecision：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
function requestDecision(decision) {
  error.value = "";
  if (!reason.value.trim()) {
    error.value = "请先填写审核理由";
    return;
  }
  pendingDecision.value = decision;
}

// 业务位置：【前端审核工作台】submitDecision：执行 当前阶段业务数据 对应的业务动作，并将结果交给 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function submitDecision() {
  submitting.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    decision: pendingDecision.value,
    reason: reason.value.trim(),
    approved_plan:
      pendingDecision.value === "MODIFY_AND_APPROVE"
        ? packet.value.remedy
        : null,
  };
  try {
    decisionResult.value = props.decideAction
      ? await props.decideAction(command)
      : await reviewApi.decide(actor, reviewId.value, command);
    pendingDecision.value = "";
    agentState.value = "HANDOFF";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
onBeforeUnmount(() => clearAgentStreams(copilotContext.value));
</script>

<template>
  <main class="review-workbench">
    <header class="review-workbench__header">
      <div>
        <span>HUMAN FINAL GATE</span>
        <h1>平台终审室</h1>
        <p class="review-workbench__lead">
          <span class="review-workbench__context">
            平台最终确认 <i aria-hidden="true">✦</i>
          </span>
          <span>审核员可以核对、修改或驳回裁决草案，并决定最终执行方案。</span>
        </p>
      </div>
      <PhaseCountdown
        v-if="packet"
        label="审核包有效期"
        :deadline-at="packetExpiry"
        :server-now="effectiveServerNow"
      />
    </header>

    <DigitalHuman
      :state="digitalHumanState"
      name="小译"
      role="审核解释官"
      message="我只依据当前冻结 ReviewPacket 转述事实、证据、规则和草案。批准、修改或驳回必须由你亲自确认。"
    />

    <section v-if="packet" class="review-copilot" data-review-copilot>
      <header>
        <div>
          <span>REVIEW COPILOT</span>
          <h2>向审核解释官提问</h2>
        </div>
        <small>{{ copilotBusy ? "正在生成" : "冻结卷宗已连接" }}</small>
      </header>
      <div class="review-copilot__conversation" aria-live="polite">
        <p v-if="!copilotMessages.length && !copilotRuns.length" class="review-copilot__empty">
          可询问事实依据、证据缺口、规则适用或草案风险。解释官只读取当前冻结审核包，不会代替你作出终审决定。
        </p>
        <article
          v-for="message in copilotMessages"
          :key="message.id"
          class="review-copilot__message"
          :class="{
            'review-copilot__message--reviewer': message.sender_role === 'PLATFORM_REVIEWER',
          }"
        >
          <strong>
            {{ message.sender_role === "PLATFORM_REVIEWER" ? "审核员" : "小译 · 审核解释官" }}
          </strong>
          <p>{{ message.text }}</p>
        </article>
        <AgentStreamingMessage
          v-for="run in copilotRuns"
          :key="run.runId"
          :run="run"
          label="小译 · 审核解释官"
        />
      </div>
      <form class="review-copilot__composer" @submit.prevent="submitCopilotQuestion">
        <textarea
          v-model="copilotQuestion"
          rows="2"
          maxlength="20000"
          :disabled="!canUseCopilot || copilotBusy"
          placeholder="例如：这份草案最需要人工复核的证据缺口是什么？"
          data-review-copilot-input
          @keydown.enter.exact.prevent="submitCopilotQuestion"
        />
        <button
          type="submit"
          :disabled="!copilotQuestion.trim() || !canUseCopilot || copilotBusy"
          data-review-copilot-submit
        >
          {{ copilotBusy ? "生成中" : "发送" }}
        </button>
      </form>
    </section>

    <div v-if="packet" class="review-workbench__grid">
      <aside class="packet-index">
        <span>冻结索引</span>
        <h2>ReviewPacket v{{ packet.packet_version }}</h2>
        <dl>
          <div><dt>状态</dt><dd data-packet-status>{{ packetStatusLabel(packet.status) }}</dd></div>
          <div><dt>证据卷</dt><dd>v{{ packet.dossier_version }}</dd></div>
          <div><dt>规则集</dt><dd>{{ packet.ruleset_version }}</dd></div>
          <div><dt>冻结时间</dt><dd data-frozen-time :title="packet.frozen_at">{{ displayDateTime(packet.frozen_at) }}</dd></div>
        </dl>
        <div class="packet-hash">冻结后不可修改 · 所有决定可追溯</div>
      </aside>

      <section class="packet-canvas">
        <header>
          <div>
            <span>CASE IN ONE GLANCE</span>
            <h2>{{ packet.case_summary?.title || "案件终审摘要" }}</h2>
          </div>
          <strong>{{ riskLabel(packet.case_summary?.risk_level) }}</strong>
        </header>

        <div class="packet-cards">
          <article data-claims-card>
            <span>双方主张</span>
            <dl class="claim-list">
              <div v-for="claim in claimEntries(packet.claims)" :key="claim.label">
                <dt>{{ claim.label }}</dt>
                <dd>{{ claim.text }}</dd>
              </div>
            </dl>
          </article>
          <article data-issues-card>
            <span>核心争点</span>
            <ol class="issue-list">
              <li v-for="(issue, index) in listEntries(packet.issues)" :key="index">
                {{ issueText(issue) }}
              </li>
            </ol>
          </article>
          <article data-evidence-matrix>
            <span>证据矩阵</span>
            <div class="evidence-matrix">
              <section v-for="row in evidenceRows(packet.evidence_matrix)" :key="row.issue">
                <strong>{{ row.issue }}</strong>
                <p v-if="row.supporting.length">
                  <i v-for="item in row.supporting" :key="displayValue(item)">
                    {{ displayValue(item) }}
                  </i>
                </p>
                <small v-if="row.conclusion !== '未提供'">{{ row.conclusion }}</small>
              </section>
            </div>
          </article>
          <article class="packet-cards__draft">
            <span>AI 裁决草案（非最终）</span>
            <strong>{{ draftDecision(packet.draft) }}</strong>
            <p v-if="draftReasoning(packet.draft)">
              {{ displayValue(draftReasoning(packet.draft)) }}
            </p>
            <div v-if="draftAttention(packet.draft).length" class="risk-chips">
              <i v-for="item in draftAttention(packet.draft)" :key="displayValue(item)">
                {{ displayValue(item) }}
              </i>
            </div>
          </article>
          <article class="packet-cards__remedy" data-remedy-card>
            <span>待批准执行方案</span>
            <div class="remedy-actions">
              <section v-for="action in remedyActions(packet.remedy)" :key="action.title">
                <strong>{{ action.title }}</strong>
                <small>{{ action.detail }}</small>
              </section>
            </div>
          </article>
          <article class="packet-cards__risk">
            <span>风险与审核注意</span>
            <div class="risk-chips">
              <i v-for="flag in packet.risk_flags || []" :key="flag">{{ flag }}</i>
              <i v-for="item in packet.draft?.reviewer_attention || []" :key="item">{{ item }}</i>
            </div>
          </article>
        </div>

        <section v-if="canDecide" class="decision-dock" data-review-decisions>
          <label>
            终审理由（必填）
            <textarea
              v-model="reason"
              data-review-reason
              maxlength="2000"
              rows="3"
              placeholder="写下你核验过的事实、规则和决定依据"
            />
          </label>
          <div>
            <button
              v-for="[value, label] in decisions"
              :key="value"
              type="button"
              :data-decision="value"
              @click="requestDecision(value)"
            >
              {{ label }}
            </button>
          </div>
        </section>
        <div v-else class="decision-readonly">
          ReviewPacket 冻结前仅可只读旁观，系统不会展示任何批准按钮。
        </div>

        <div v-if="pendingDecision" class="decision-confirm" role="dialog">
          <strong>确认提交 {{ pendingDecision }}？</strong>
          <p>此操作会写入不可变审核记录，并驱动后续执行链路。</p>
          <div>
            <button type="button" @click="pendingDecision = ''">返回检查</button>
            <button
              type="button"
              data-decision-confirm
              :disabled="submitting"
              @click="submitDecision"
            >
              {{ submitting ? "正在落槌…" : "确认并落槌" }}
            </button>
          </div>
        </div>
        <p v-if="error" class="decision-error" role="alert">{{ error }}</p>
        <div v-if="decisionResult" class="decision-success">终审决定已提交，执行链路正在接棒。</div>
      </section>
    </div>
    <AgentStreamErrorDialog
      :message="copilotStreamError"
      title="审核解释官生成失败"
      @dismiss="copilotStreamError = ''"
    />
  </main>
</template>

<style scoped>
.review-workbench {
  display: grid;
  width: 100%;
  min-width: 0;
  gap: 18px;
  box-sizing: border-box;
}
.review-workbench,
.review-workbench * {
  box-sizing: border-box;
}
.review-workbench :where(header, aside, section, article, div, dl, dt, dd, ol, li, p, h1, h2, strong, span, small, i, label, textarea, button) {
  min-width: 0;
}
.review-workbench :where(p, h1, h2, strong, span, small, i, dt, dd, li, label, textarea, button) {
  overflow-wrap: anywhere;
  word-break: break-word;
}
.review-workbench__header { display: flex; min-width: 0; justify-content: space-between; align-items: flex-end; gap: 24px; }
.review-workbench__header > div > span, .packet-index > span, .packet-canvas header span {
  color: #7486a4; font-size: 10px; font-weight: 900; letter-spacing: .17em;
}
.review-workbench__header > div > span { color: #7185a8; font-weight: 800; letter-spacing: .18em; }
.review-workbench__header h1 { margin: 7px 0 8px; color: #263754; font-size: clamp(32px, 5vw, 56px); line-height: 1.05; }
.review-workbench__header p { margin: 0; overflow-wrap: anywhere; color: #8793a5; }
.review-workbench__lead { display: flex; flex-wrap: wrap; gap: 6px 8px; align-items: center; }
.review-workbench__context { display: inline-flex; gap: 6px; align-items: center; color: #586b85; font-size: 12px; font-weight: 900; line-height: 1; }
.review-workbench__context i { color: #ff9a76; font-size: 9px; font-style: normal; }
.review-copilot {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: 18px;
  background: linear-gradient(145deg, #fbfcff, #f5f1ff);
  border: 1px solid #dfe5f2;
  border-radius: 22px;
}
.review-copilot > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.review-copilot > header span {
  color: #7887a1;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .15em;
}
.review-copilot > header h2 {
  margin: 4px 0 0;
  color: #3e4a63;
  font-size: 17px;
}
.review-copilot > header small {
  padding: 5px 9px;
  color: #675b8b;
  background: #ede8ff;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
}
.review-copilot__conversation {
  display: grid;
  min-height: 108px;
  max-height: 340px;
  gap: 10px;
  padding: 12px;
  overflow-y: auto;
  align-content: start;
  background: #ffffffb8;
  border: 1px solid #e4e9f3;
  border-radius: 16px;
}
.review-copilot__empty {
  align-self: center;
  margin: 0;
  color: #7a8496;
  font-size: 12px;
  line-height: 1.65;
}
.review-copilot__message {
  justify-self: start;
  width: fit-content;
  max-width: 82%;
  padding: 11px 13px;
  color: #4f5870;
  background: #fff9ef;
  border: 1px solid #eadfcb;
  border-radius: 16px 16px 16px 5px;
}
.review-copilot__message--reviewer {
  justify-self: end;
  color: #fff;
  background: linear-gradient(135deg, #667aa7, #7666a6);
  border: 0;
  border-radius: 16px 16px 5px;
}
.review-copilot__message strong {
  font-size: 11px;
}
.review-copilot__message p {
  margin: 5px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
}
.review-copilot__composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}
.review-copilot__composer textarea {
  width: 100%;
  min-height: 66px;
  padding: 11px 12px;
  color: #39465e;
  background: #fff;
  border: 1px solid #d9e1ed;
  border-radius: 13px;
  outline: none;
  resize: vertical;
}
.review-copilot__composer textarea:focus {
  border-color: #8a81bb;
  box-shadow: 0 0 0 3px #8376ba1a;
}
.review-copilot__composer button {
  align-self: stretch;
  min-width: 82px;
  padding: 0 18px;
  color: #fff;
  background: linear-gradient(135deg, #667ba8, #7665a6);
  border: 0;
  border-radius: 13px;
  font-weight: 800;
  cursor: pointer;
}
.review-copilot__composer :disabled {
  cursor: not-allowed;
  opacity: .55;
}
.review-workbench__grid { display: grid; min-width: 0; grid-template-columns: 245px minmax(0, 1fr); gap: 16px; }
.packet-index, .packet-canvas { min-width: 0; padding: 20px; background: #ffffffd9; border: 1px solid #dfe7f1; border-radius: 26px; }
.packet-index h2 { margin: 6px 0 16px; overflow-wrap: anywhere; color: #34445d; }
.packet-index dl { display: grid; gap: 9px; }
.packet-index dl div { display: grid; gap: 3px; padding: 10px; background: #f6f8fc; border-radius: 12px; }
.packet-index dt { color: #8994a5; font-size: 10px; }
.packet-index dd { margin: 0; overflow-wrap: anywhere; color: #526078; font-size: 12px; }
.packet-hash { margin-top: 14px; padding: 10px; color: #665c83; background: #f2edff; border-radius: 12px; font-size: 11px; line-height: 1.5; }
.packet-canvas > header { display: flex; min-width: 0; justify-content: space-between; gap: 14px; }
.packet-canvas h2 { margin: 5px 0; overflow-wrap: anywhere; color: #34445d; }
.packet-canvas header > strong { height: max-content; padding: 6px 9px; color: #a54f56; background: #ffeaeb; border-radius: 999px; font-size: 11px; }
.packet-cards { display: grid; min-width: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; margin-top: 15px; }
.packet-cards article { min-width: 0; padding: 14px; background: #f7fafe; border: 1px solid #e4eaf2; border-radius: 17px; }
.packet-cards article > span { color: #61738f; font-size: 11px; font-weight: 800; }
.claim-list,
.issue-list,
.evidence-matrix,
.remedy-actions {
  display: grid;
  gap: 9px;
  margin: 10px 0 0;
  padding: 0;
}
.claim-list div,
.evidence-matrix section,
.remedy-actions section {
  min-width: 0;
  padding: 10px;
  background: #fff;
  border: 1px solid #e8eef6;
  border-radius: 13px;
}
.claim-list dt,
.evidence-matrix strong,
.remedy-actions strong {
  color: #33445d;
  font-size: 12px;
  font-weight: 900;
}
.claim-list dd {
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  color: #637188;
  font-size: 12px;
  line-height: 1.55;
}
.issue-list { padding-left: 18px; overflow-wrap: anywhere; color: #526179; font-size: 12px; line-height: 1.65; }
.evidence-matrix p {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0 0;
}
.evidence-matrix i {
  max-width: 100%;
  padding: 4px 7px;
  overflow-wrap: anywhere;
  color: #557094;
  background: #f0f6ff;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
}
.evidence-matrix small,
.remedy-actions small {
  display: block;
  margin-top: 6px;
  overflow-wrap: anywhere;
  color: #728096;
  font-size: 11px;
  line-height: 1.55;
}
.packet-cards__draft > strong {
  display: block;
  margin-top: 10px;
  overflow-wrap: anywhere;
  color: #4f4380;
}
.packet-cards__draft > p {
  margin: 8px 0 0;
  overflow-wrap: anywhere;
  color: #6e638d;
  font-size: 12px;
  line-height: 1.55;
}
.packet-cards__draft { background: #f2edff !important; }
.packet-cards__remedy { background: #e9f8ef !important; }
.packet-cards__risk { background: #fff3ea !important; }
.risk-chips { display: flex; min-width: 0; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.risk-chips i { max-width: 100%; padding: 5px 7px; overflow-wrap: anywhere; color: #985a4e; background: #fff; border-radius: 999px; font-size: 10px; font-style: normal; }
.decision-dock { display: grid; gap: 12px; padding: 16px; margin-top: 15px; background: linear-gradient(135deg, #edf7ff, #f4efff); border-radius: 20px; }
.decision-dock label { display: grid; gap: 7px; color: #596a83; font-size: 12px; }
.decision-dock textarea { padding: 11px; color: #3e4c62; background: #fff; border: 1px solid #d9e3ef; border-radius: 12px; resize: vertical; }
.decision-dock > div { display: flex; min-width: 0; flex-wrap: wrap; gap: 8px; }
.decision-dock button, .decision-confirm button { padding: 9px 12px; color: #55657c; background: #fff; border: 1px solid #d8e2ee; border-radius: 11px; cursor: pointer; }
.decision-dock button[data-decision="APPROVE"] { color: #fff; background: linear-gradient(135deg, #5dbd92, #4fa7c8); border: 0; }
.decision-dock button[data-decision="REJECT"] { color: #a34852; background: #ffebed; }
.decision-readonly { padding: 14px; margin-top: 15px; color: #756b8f; background: #f2edff; border-radius: 15px; }
.decision-confirm { padding: 16px; margin-top: 12px; color: #554c59; background: #fff5dc; border: 1px solid #eedcac; border-radius: 18px; }
.decision-confirm p { color: #756b72; font-size: 12px; }
.decision-confirm > div { display: flex; min-width: 0; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.decision-confirm button:last-child { color: white; background: #e57962; border: 0; }
.decision-error { color: #a74351; }
.decision-success { padding: 13px; margin-top: 12px; color: #277152; background: #e4f7ec; border-radius: 14px; }
@media (max-width: 880px) { .review-workbench__grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) {
  .review-workbench__header {
    align-items: stretch;
    flex-direction: column;
  }
  .packet-canvas > header {
    align-items: flex-start;
    flex-direction: column;
  }
  .packet-cards { grid-template-columns: 1fr; }
  .review-copilot__composer { grid-template-columns: 1fr; }
  .review-copilot__composer button { min-height: 42px; }
}
</style>
