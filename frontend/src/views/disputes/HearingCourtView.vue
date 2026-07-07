<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { hearingApi } from "../../api/hearing";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  streamRoomEvents,
} from "../../stores/room";

const props = defineProps({
  initialHearing: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  deadlineAt: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  roundLimit: { type: Number, default: 3 },
  confirmSettlementAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
  initialMessages: { type: Array, default: null },
  messageAction: { type: Function, default: null },
  proposeSettlementAction: { type: Function, default: null },
  supplementAction: { type: Function, default: null },
  submitRoundAction: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const hearing = ref(props.initialHearing);
const agentState = ref("LISTENING");
const reviewGateOpen = ref(false);
const error = ref("");
const confirmingVersion = ref(null);
const messages = ref([...(props.initialMessages || [])]);
const settlementOpen = ref(false);
const ledgerOpen = ref(false);
const proposalText = ref("");
const statementText = ref("");
const proposing = ref(false);
const supplementing = ref(false);
const submittingRound = ref(false);
const eventState = reactive(createRoomState());
const eventAbortController = new AbortController();
const caseId = computed(() => route.params.caseId);
const role = computed(() => props.viewerRole || actor.role);
const isReviewer = computed(() => role.value === "PLATFORM_REVIEWER");
const rounds = computed(() => hearing.value?.rounds || []);
const settlements = computed(() => hearing.value?.settlements || []);
const currentRound = computed(
  () => Math.max(1, ...rounds.value.map((round) => round.round_no || 1)),
);
const activeRound = computed(
  () =>
    rounds.value.find((round) => round.status === "OPEN") ||
    rounds.value.at(-1) ||
    null,
);
const activeSettlement = computed(
  () =>
    settlements.value.find((settlement) => settlement.status !== "SUPERSEDED") ||
    null,
);
const effectiveDeadline = computed(
  () =>
    props.deadlineAt ||
    new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
);
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const roundStatusLabels = {
  OPEN: "进行中",
  WAITING: "等待另一方",
  COMPLETED: "已完成",
  CLOSED: "已封存",
  TIMEOUT: "已超时",
  FORCED_CLOSED: "已强制收束",
};
const evidenceSourceType = computed(() => {
  if (role.value === "MERCHANT") return "MERCHANT_UPLOAD";
  if (role.value === "USER") return "USER_UPLOAD";
  return "PLATFORM_UPLOAD";
});
const partyRoles = ["USER", "MERCHANT"];
const reviewGateEvents = new Set([
  "REVIEW_TASK_CREATED",
  "REVIEW_GATE_READY",
  "HUMAN_REVIEW_OPENED",
  "ADJUDICATION_DRAFT_READY",
]);
const isCaseParty = computed(() => partyRoles.includes(role.value));
const submittedRoles = computed(
  () => activeRound.value?.submitted_roles || activeRound.value?.submittedRoles || [],
);
const currentActorSubmitted = computed(
  () =>
    Boolean(
      activeRound.value?.current_actor_submitted ||
        activeRound.value?.currentActorSubmitted ||
        submittedRoles.value.includes(role.value),
    ),
);
const activeRoundClosed = computed(() =>
  ["COMPLETED", "FORCED_CLOSED", "CLOSED"].includes(
    activeRound.value?.status || "",
  ),
);
const activeRoundNo = computed(
  () => activeRound.value?.round_no || activeRound.value?.roundNo || 0,
);
const finalRoundSealed = computed(
  () => activeRoundClosed.value && activeRoundNo.value >= props.roundLimit,
);
const reviewHandoffVisible = computed(
  () => isCaseParty.value && finalRoundSealed.value,
);
const reviewHandoffTitle = computed(() =>
  reviewGateOpen.value
    ? "裁决草案已送入平台终审"
    : "三轮陈述已封存，准备进入平台终审",
);
const reviewHandoffBody = computed(() =>
  reviewGateOpen.value
    ? "平台审核员会基于冻结卷宗完成最终确认。用户和商家不用再留在小法庭等待，新的传票信箱会同步终审与执行结果。"
    : "本案已经达到三轮陈述上限，双方内容已自动封存。AI 法官会输出确定裁决草案，随后交给平台审核员完成平台终审。",
);
const counterpartyLabel = computed(() =>
  role.value === "USER" ? "商家" : "用户",
);
const canSubmitRound = computed(
  () =>
    isCaseParty.value &&
    !currentActorSubmitted.value &&
    !activeRoundClosed.value,
);
const activeRoundDeadline = computed(
  () => activeRound.value?.round_deadline_at || activeRound.value?.roundDeadlineAt || "",
);
const roundStepLabels = ["事实陈述", "证据解释", "方案确认"];
const mockEvidenceRails = {
  user: [
    {
      id: "user-door-camera",
      type: "image",
      title: "门口监控截图.jpg",
      subtitle: "图片证据 · OCR 已提取",
      confidence: 95,
      status: "已核验",
      tone: "blue",
    },
    {
      id: "user-statement",
      type: "text",
      title: "未收到货说明.pdf",
      subtitle: "文本证据 · 已解析",
      confidence: 88,
      status: "待复核",
      tone: "purple",
    },
    {
      id: "user-call-record",
      type: "video",
      title: "物业通话录音.mp4",
      subtitle: "视频/音频 · 待核验",
      confidence: 62,
      status: "待核验",
      tone: "gold",
    },
  ],
  merchant: [
    {
      id: "merchant-waybill",
      type: "text",
      title: "物流签收底单.pdf",
      subtitle: "文本证据 · 已解析",
      confidence: 90,
      status: "已核验",
      tone: "purple",
    },
    {
      id: "merchant-package-photo",
      type: "image",
      title: "打包交接照片.jpg",
      subtitle: "图片证据 · OCR 已提取",
      confidence: 84,
      status: "待复核",
      tone: "blue",
    },
    {
      id: "merchant-scan-log",
      type: "text",
      title: "出库扫描记录.md",
      subtitle: "文本记录 · 已解析",
      confidence: 79,
      status: "已核验",
      tone: "mint",
    },
  ],
};
const evidenceRailProfiles = {
  user: {
    key: "user",
    role: "USER",
    eyebrow: "USER EVIDENCE",
    title: "用户证据原件匣",
    description: "固定高度展示，更多材料在内部滚动。",
    badge: "用户侧",
    ariaLabel: "用户已提交证据",
    supplementLabel: "补充用户证据",
  },
  merchant: {
    key: "merchant",
    role: "MERCHANT",
    eyebrow: "MERCHANT EVIDENCE",
    title: "商家证据原件匣",
    description: "正式提交后进入庭审可见证据架。",
    badge: "商家侧",
    ariaLabel: "商家已提交证据",
    supplementLabel: "补充商家证据",
  },
};
const leftEvidenceRail = computed(() =>
  role.value === "MERCHANT" ? evidenceRailProfiles.merchant : evidenceRailProfiles.user,
);
const rightEvidenceRail = computed(() =>
  leftEvidenceRail.value.key === "merchant"
    ? evidenceRailProfiles.user
    : evidenceRailProfiles.merchant,
);
const activeRoundSummary = computed(() => summary(activeRound.value));
const currentRoundLabel = computed(
  () => roundStepLabels[Math.min(currentRound.value, roundStepLabels.length) - 1] || "庭审陈述",
);
const roundSubmitDescription = computed(() => {
  if (activeRoundClosed.value) {
    return "双方已提交本轮，本轮陈述已封存；第三轮结束后，AI 法官会统一生成确定的裁决方案草案。";
  }
  if (currentActorSubmitted.value) {
    return `已提交本轮，等待${counterpartyLabel.value}。双方都提交后，系统会自动封存本轮陈述并开放下一轮。`;
  }
  return "当前陈述、证据解释和和解意向会被封装为本轮立场。双方都点击提交，或 5 分钟时效届满后，系统自动封存并推进流程。";
});
const statementInputDisabled = computed(
  () => !isCaseParty.value || currentActorSubmitted.value || activeRoundClosed.value,
);
const stageDockMode = computed(() => {
  if (reviewHandoffVisible.value) return "handoff";
  if (activeRoundClosed.value) return "sealed";
  if (currentActorSubmitted.value) return "waiting";
  return "active";
});
const stageDockTitle = computed(() => {
  if (reviewHandoffVisible.value) return reviewHandoffTitle.value;
  if (activeRoundClosed.value) return "本轮已封存";
  if (currentActorSubmitted.value) return `已提交本轮，等待${counterpartyLabel.value}`;
  return `第 ${currentRound.value} 轮 · ${currentRoundLabel.value}`;
});
const stageDockBody = computed(() => {
  if (reviewHandoffVisible.value) return reviewHandoffBody.value;
  if (activeRoundClosed.value) {
    return "双方已提交本轮，陈述已经封存。AI 法官会读取庭审记录和证据架，生成本轮判断或推进到下一轮。";
  }
  if (currentActorSubmitted.value) {
    return `你的本轮立场已经入卷，系统会在${counterpartyLabel.value}提交或倒计时结束后自动封存。`;
  }
  return "请双方围绕法官问题完成本轮陈述。双方都提交，或 5 分钟倒计时届满后，本轮会自动封存。";
});
const stageDockBadge = computed(() => {
  if (reviewHandoffVisible.value) return "平台终审";
  if (activeRoundClosed.value) return "本轮已封存";
  if (currentActorSubmitted.value) return "等待对方";
  return "进行中";
});
const roundProgressItems = computed(() =>
  Array.from({ length: props.roundLimit }, (_, index) => {
    const roundNumber = index + 1;
    const round = rounds.value.find((item) => (item.round_no || item.roundNo) === roundNumber);
    let status = "未开始";
    let tone = "pending";
    if (roundNumber < currentRound.value || ["COMPLETED", "FORCED_CLOSED", "CLOSED"].includes(round?.status || "")) {
      status = ["COMPLETED", "FORCED_CLOSED", "CLOSED"].includes(round?.status || "") ? "已封存" : "已完成";
      tone = "complete";
    } else if (roundNumber === currentRound.value) {
      status = activeRoundClosed.value ? "已封存" : "进行中";
      tone = activeRoundClosed.value ? "complete" : "active";
    }
    return {
      number: roundNumber,
      label: roundStepLabels[index] || `第 ${roundNumber} 轮`,
      status,
      tone,
    };
  }),
);
const partySubmissionStatuses = computed(() =>
  [
    { role: "USER", label: "用户提交" },
    { role: "MERCHANT", label: "商家提交" },
  ].map((party) => {
    if (activeRoundClosed.value) {
      return { ...party, status: "已封存", tone: "sealed" };
    }
    if (submittedRoles.value.includes(party.role)) {
      return { ...party, status: "已提交", tone: "submitted" };
    }
    return { ...party, status: "未提交", tone: "pending" };
  }),
);
const timeStatus = computed(() => {
  if (reviewHandoffVisible.value) return { label: "时间/封存", value: "等待平台终审", tone: "sealed" };
  if (activeRoundClosed.value) return { label: "时间/封存", value: "已封存", tone: "sealed" };
  if (activeRoundDeadline.value) return { label: "本轮倒计时", value: "04:18", tone: "active" };
  return { label: "时间/封存", value: "等待法官处理", tone: "waiting" };
});
const judgeReviewStatus = computed(() => {
  if (reviewHandoffVisible.value) return { label: "法官/评审", value: "评审通过", tone: "complete" };
  if (activeRoundClosed.value) return { label: "法官/评审", value: "法官处理中", tone: "processing" };
  if (stageDockMode.value === "waiting") return { label: "法官/评审", value: "等待双方陈述", tone: "waiting" };
  return { label: "法官/评审", value: "法官提问中", tone: "active" };
});
const nextStepHint = computed(() => {
  if (reviewHandoffVisible.value) return "进入平台终审，等待审核员确认最终结果。";
  if (activeRoundClosed.value) return "法官将读取本轮封存记录，生成下一轮问题或裁决草案。";
  if (currentActorSubmitted.value) return `等待${counterpartyLabel.value}提交，或倒计时结束后自动封存。`;
  return "双方完成陈述并提交后，本轮会自动封存进入法官处理。";
});
const mockTranscriptItems = computed(() => [
  {
    id: "judge-opening",
    type: "judge",
    speaker: "主审法官",
    time: "14:00:00",
    text:
      activeRoundSummary.value.judge ||
      "根据现有案情，物流记录显示包裹已签收，但用户称未实际收到商品。请用户补充未收到包裹的具体情况，请商家说明发货、物流交接及签收记录。",
  },
  {
    id: "user-statement",
    type: "user",
    speaker: "用户陈述",
    time: "14:02:15",
    text: "用户称门口监控未见快递员投递，并已提交截图用于核验包裹实际去向。",
  },
  {
    id: "merchant-statement",
    type: "merchant",
    speaker: "商家陈述",
    time: "待提交",
    text: "商家需说明发货记录、物流交接、签收底单与异常工单处理记录。",
  },
  {
    id: "jury-review",
    type: "jury",
    speaker: "AI 评审团",
    time: "裁决辅助分析",
    text:
      activeRoundSummary.value.jury ||
      "中风险 · 当前可信分 75/100 · 建议核验物流轨迹定位与签收凭证。",
  },
]);

function evidenceTypeLabel(type) {
  return {
    image: "图片",
    video: "视频",
    text: "文本",
  }[type] || "文件";
}

function summary(round) {
  try {
    return JSON.parse(round?.summary_json || "{}");
  } catch {
    return { judge: round?.summary_json || "本轮记录正在整理。" };
  }
}

function roundStatusLabel(status) {
  return roundStatusLabels[status] || status || "待开始";
}

async function load() {
  try {
    if (hearing.value === null) {
      hearing.value = await hearingApi.hearing(actor, caseId.value);
    }
    if (props.initialMessages === null) {
      messages.value = await roomApi.messages(
        actor,
        caseId.value,
        "HEARING",
      );
    }
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function refreshHearing() {
  const [nextHearing, nextMessages] = await Promise.all([
    hearingApi.hearing(actor, caseId.value),
    roomApi.messages(actor, caseId.value, "HEARING"),
  ]);
  hearing.value = nextHearing;
  messages.value = nextMessages;
}

async function postMessage(command) {
  error.value = "";
  agentState.value = "THINKING";
  try {
    const saved = props.messageAction
      ? await props.messageAction(command)
      : await roomApi.postMessage(
          actor,
          caseId.value,
          "HEARING",
          command,
        );
    messages.value.push(saved);
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function submitStatementInput() {
  const text = statementText.value.trim();
  if (!text || statementInputDisabled.value) return;
  await postMessage({
    message_type: "PARTY_TEXT",
    text,
    attachment_refs: [],
  });
  statementText.value = "";
}

async function proposeSettlement() {
  const text = proposalText.value.trim();
  if (!text) return;
  proposing.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    proposal_text: text,
    proposal_json: JSON.stringify({
      source: "PARTY_CONSENSUS",
      text,
    }),
  };
  try {
    const saved = props.proposeSettlementAction
      ? await props.proposeSettlementAction(command)
      : await hearingApi.proposeSettlement(
          actor,
          caseId.value,
          command,
        );
    hearing.value = {
      ...(hearing.value || {}),
      rounds: hearing.value?.rounds || [],
      settlements: [
        saved,
        ...(hearing.value?.settlements || []).filter(
          (item) => item.version !== saved.version,
        ),
      ],
    };
    settlementOpen.value = false;
    proposalText.value = "";
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    proposing.value = false;
  }
}

async function supplementEvidence(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  supplementing.value = true;
  error.value = "";
  try {
    const command = {
      file,
      evidenceType: file.type.startsWith("video/")
        ? "VIDEO"
        : "OTHER",
      sourceType: evidenceSourceType.value,
      visibility: "PARTIES",
    };
    if (props.supplementAction) {
      await props.supplementAction(command);
    } else {
      await evidenceApi.upload(actor, caseId.value, command);
    }
    await postMessage({
      message_type: "PARTY_TEXT",
      text: `已补充证据：${file.name}`,
      attachment_refs: [],
    });
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    supplementing.value = false;
    event.target.value = "";
  }
}

async function submitCurrentRound() {
  if (!isCaseParty.value) return;
  submittingRound.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    dossier_version: activeRound.value?.dossier_version || activeRound.value?.dossierVersion || 1,
    statement_json: JSON.stringify({
      submitted_by_role: role.value,
      submitted_message_ids: messages.value
        .filter((message) => message.sender_role === role.value)
        .map((message) => message.id)
        .filter(Boolean),
      active_round_no: currentRound.value,
    }),
  };
  try {
    const saved = props.submitRoundAction
      ? await props.submitRoundAction(command)
      : await hearingApi.submitRound(actor, caseId.value, command);
    if (!props.submitRoundAction) {
      await refreshHearing();
      agentState.value = saved.status === "COMPLETED" ? "THINKING" : "SPEAKING";
      return;
    }
    const existing = rounds.value.findIndex(
      (round) => round.round_no === saved.round_no,
    );
    const nextRounds =
      existing >= 0
        ? rounds.value.map((round, index) =>
            index === existing ? saved : round,
          )
        : [...rounds.value, saved];
    hearing.value = {
      ...(hearing.value || {}),
      rounds: nextRounds,
      settlements: hearing.value?.settlements || [],
    };
    agentState.value = saved.status === "COMPLETED" ? "THINKING" : "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submittingRound.value = false;
  }
}

function startEventStream() {
  const streamer = props.eventStreamer || streamRoomEvents;
  void streamer({
    actor,
    caseId: caseId.value,
    roomType: "HEARING",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: refreshHearing,
    applyEvent: async (event) => {
      if (reviewGateEvents.has(event.event)) {
        reviewGateOpen.value = true;
        agentState.value = "HANDOFF";
        await refreshHearing();
      }
      if (event.event === "CASE_CLOSED") {
        await router.push(`/disputes/${caseId.value}/outcome`);
      }
    },
  });
}

async function confirmSettlement(version) {
  confirmingVersion.value = version;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const result = props.confirmSettlementAction
      ? await props.confirmSettlementAction(version)
      : await hearingApi.confirmSettlement(actor, caseId.value, version);
    const index = settlements.value.findIndex(
      (settlement) => settlement.version === version,
    );
    if (index >= 0) {
      hearing.value = {
        ...hearing.value,
        settlements: hearing.value.settlements.map((settlement, itemIndex) =>
          itemIndex === index ? result : settlement,
        ),
      };
    }
    agentState.value =
      result.status === "CONFIRMED" ? "HANDOFF" : "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    confirmingVersion.value = null;
  }
}

onMounted(async () => {
  await load();
  if (
    props.eventStreamer ||
    props.initialHearing === null ||
    props.initialMessages === null
  ) {
    startEventStream();
  }
});
onBeforeUnmount(() => eventAbortController.abort());
</script>

<template>
  <RoomShell
    eyebrow="AI NATIVE COURTROOM"
    title="AI 小法庭 · 履约争端庭审"
    :case-id="caseId"
    :connection-state="connectionState"
  >
    <template #clock>
      <div data-hearing-countdown>
        <PhaseCountdown
          label="庭审时效"
          :deadline-at="effectiveDeadline"
          :server-now="effectiveServerNow"
        />
      </div>
    </template>

    <template #agent>
      <section
        class="court-agent-strip"
        data-court-agent-strip
        aria-label="庭审数字人席位"
      >
        <DigitalHuman
          data-court-agent-card="judge"
          :state="agentState"
          name="衡衡"
          role="AI 法官"
          message="主持三轮陈述，第三轮后生成非最终裁决草案。"
        />
        <DigitalHuman
          data-court-agent-card="jury-a"
          state="THINKING"
          name="评审 A"
          role="AI 评审团"
          message="关注事实完整性、证据冲突和风险信号。"
        />
        <DigitalHuman
          data-court-agent-card="jury-b"
          state="LISTENING"
          name="评审 B"
          role="AI 评审团"
          message="关注裁决草案是否符合规则与双方可接受度。"
        />
      </section>
    </template>

    <main class="hearing-courtroom-page" data-hearing-courtroom-page>
      <aside
        class="party-evidence-rail party-evidence-rail--left"
        :class="`party-evidence-rail--${leftEvidenceRail.key}`"
        :data-party-evidence-rail="leftEvidenceRail.key"
        data-rail-position="left"
      >
        <header class="party-evidence-rail__header">
          <div>
            <span>{{ leftEvidenceRail.eyebrow }}</span>
            <h2>{{ leftEvidenceRail.title }}</h2>
            <p>{{ leftEvidenceRail.description }}</p>
          </div>
          <b>{{ leftEvidenceRail.badge }}</b>
        </header>

        <div class="evidence-pocket" :aria-label="leftEvidenceRail.ariaLabel">
          <article
            v-for="item in mockEvidenceRails[leftEvidenceRail.key]"
            :key="item.id"
            class="evidence-file-card"
            :class="`evidence-file-card--${item.tone}`"
          >
            <i class="evidence-file-card__icon" :data-type="item.type">
              {{ evidenceTypeLabel(item.type) }}
            </i>
            <div>
              <strong>{{ item.title }}</strong>
              <small>{{ item.subtitle }}</small>
              <footer>
                <span>置信度 {{ item.confidence }}%</span>
                <em>{{ item.status }}</em>
              </footer>
            </div>
          </article>
        </div>

        <label
          v-if="isCaseParty && leftEvidenceRail.role === role"
          class="evidence-supplement-button"
          :class="{ 'evidence-supplement-button--merchant': leftEvidenceRail.key === 'merchant' }"
          :data-supplement-evidence="leftEvidenceRail.key"
        >
          {{ supplementing ? "正在补入卷宗…" : leftEvidenceRail.supplementLabel }}
          <input type="file" :disabled="supplementing" @change="supplementEvidence" />
        </label>

        <button class="evidence-expand-button" type="button">
          展开证据预览
          <span aria-hidden="true">↗</span>
        </button>
      </aside>

      <section class="courtroom-center">
        <section
          class="hearing-stage-dock"
          :class="`hearing-stage-dock--${stageDockMode}`"
          data-hearing-stage-dock
        >
          <div class="hearing-stage-dock__copy">
            <span>当前庭审状态</span>
            <h2>{{ stageDockTitle }}</h2>
            <p>{{ nextStepHint }}</p>
          </div>
          <div class="hearing-stage-dock__meta">
            <strong class="hearing-stage-dock__badge">{{ stageDockBadge }}</strong>
          </div>
          <div class="round-progress-board">
            <article
              v-for="item in roundProgressItems"
              :key="item.number"
              class="round-progress-board__item"
              :class="`round-progress-board__item--${item.tone}`"
              data-round-progress-item
              :data-round-progress-state="item.tone"
            >
              <b>{{ item.number }}</b>
              <span>{{ item.label }}</span>
              <em>{{ item.status }}</em>
            </article>
          </div>
          <div class="hearing-stage-dock__status-grid">
            <article
              v-for="party in partySubmissionStatuses"
              :key="party.role"
              class="hearing-status-chip"
              :class="`hearing-status-chip--${party.tone}`"
              :data-hearing-status-chip="party.role"
            >
              <span>{{ party.label }}</span>
              <strong>{{ party.status }}</strong>
            </article>
            <article
              class="hearing-status-chip"
              :class="`hearing-status-chip--${timeStatus.tone}`"
              data-hearing-status-chip="time"
            >
              <span>{{ timeStatus.label }}</span>
              <strong>{{ timeStatus.value }}</strong>
            </article>
            <article
              class="hearing-status-chip"
              :class="`hearing-status-chip--${judgeReviewStatus.tone}`"
              data-hearing-status-chip="judge-review"
            >
              <span>{{ judgeReviewStatus.label }}</span>
              <strong>{{ judgeReviewStatus.value }}</strong>
            </article>
          </div>
        </section>

        <section class="court-transcript" data-court-transcript>
          <div class="court-transcript__messages">
            <article
              v-for="item in mockTranscriptItems"
              :key="item.id"
              class="court-message"
              :class="`court-message--${item.type}`"
            >
              <header>
                <strong>{{ item.speaker }}</strong>
                <span>{{ item.time }}</span>
              </header>
              <p>{{ item.text }}</p>
            </article>

          </div>
        </section>

        <section v-if="isCaseParty" class="round-input-bar" data-round-input-bar>
          <div class="round-input-bar__body">
            <h3>本轮陈述输入台</h3>
            <form
              class="round-input-bar__composer"
              data-send-message
              @submit.prevent="submitStatementInput"
            >
              <textarea
                v-model="statementText"
                :disabled="statementInputDisabled"
                :placeholder="
                  reviewHandoffVisible
                    ? '庭审已封存，等待平台终审'
                    : '输入本轮陈述、证据解释或和解意向…'
                "
                rows="3"
                aria-label="本轮陈述"
              ></textarea>
              <div class="round-input-bar__submit-column">
                <PhaseCountdown
                  v-if="activeRoundDeadline && !activeRoundClosed"
                  label="本轮提交时效"
                  :deadline-at="activeRoundDeadline"
                  :server-now="effectiveServerNow"
                />
                <button
                  v-if="activeRoundClosed || reviewHandoffVisible"
                  type="button"
                  class="round-input-bar__lock-button"
                  disabled
                >
                  🔒 本轮已封存
                </button>
                <button
                  v-else
                  type="button"
                  class="round-input-bar__send-button"
                  data-send-hearing-statement
                  :disabled="statementInputDisabled || !statementText.trim()"
                  @click="submitStatementInput()"
                >
                  发送陈述
                </button>
                <button
                  v-if="canSubmitRound"
                  type="button"
                  class="round-input-bar__round-submit"
                  data-submit-hearing-round
                  :disabled="submittingRound"
                  @click="submitCurrentRound"
                >
                  {{ submittingRound ? "正在提交本轮…" : "提交本轮陈述" }}
                </button>
                <button
                  v-if="!reviewHandoffVisible && !activeRoundClosed"
                  type="button"
                  class="round-input-bar__settlement-button"
                  data-open-settlement
                  @click="settlementOpen = true"
                >
                  提出一致方案
                </button>
                <strong v-if="!canSubmitRound && !activeRoundClosed" class="round-input-bar__submitted">
                  已提交，等待对方或倒计时结束
                </strong>
              </div>
            </form>
          </div>
        </section>

        <section v-if="activeSettlement" class="settlement-card settlement-card--dock">
          <span>CONSENSUS CARD</span>
          <h3>双方一致方案</h3>
          <p>{{ activeSettlement.proposal_text }}</p>
          <div class="settlement-card__parties">
            <i
              :class="{
                confirmed: activeSettlement.confirmed_roles?.includes('USER'),
              }"
            >用户</i>
            <i
              :class="{
                confirmed: activeSettlement.confirmed_roles?.includes('MERCHANT'),
              }"
            >商家</i>
          </div>
          <template v-if="activeSettlement.status === 'CONFIRMED'">
            <strong class="settlement-card__confirmed">双方已达成一致</strong>
            <small>一致方案已作为裁决草案，等待平台终审。</small>
          </template>
          <button
            v-else
            type="button"
            data-confirm-settlement
            :disabled="confirmingVersion !== null"
            @click="confirmSettlement(activeSettlement.version)"
          >
            {{ confirmingVersion ? "正在签署…" : "确认这份一致方案" }}
          </button>
        </section>
        <p v-if="error" class="hearing-error" role="alert">{{ error }}</p>
      </section>

      <div
        class="evidence-rail-column evidence-rail-column--right"
        :data-party-evidence-rail="rightEvidenceRail.key"
        data-rail-position="right"
      >
        <aside
          class="party-evidence-rail party-evidence-rail--right"
          :class="`party-evidence-rail--${rightEvidenceRail.key}`"
        >
          <header class="party-evidence-rail__header">
            <div>
              <span>{{ rightEvidenceRail.eyebrow }}</span>
              <h2>{{ rightEvidenceRail.title }}</h2>
              <p>{{ rightEvidenceRail.description }}</p>
            </div>
            <b>{{ rightEvidenceRail.badge }}</b>
          </header>

          <div class="evidence-pocket" :aria-label="rightEvidenceRail.ariaLabel">
            <article
              v-for="item in mockEvidenceRails[rightEvidenceRail.key]"
              :key="item.id"
              class="evidence-file-card"
              :class="`evidence-file-card--${item.tone}`"
            >
              <i class="evidence-file-card__icon" :data-type="item.type">
                {{ evidenceTypeLabel(item.type) }}
              </i>
              <div>
                <strong>{{ item.title }}</strong>
                <small>{{ item.subtitle }}</small>
                <footer>
                  <span>置信度 {{ item.confidence }}%</span>
                  <em>{{ item.status }}</em>
                </footer>
              </div>
            </article>
          </div>

          <label
            v-if="isCaseParty && rightEvidenceRail.role === role"
            class="evidence-supplement-button"
            :class="{ 'evidence-supplement-button--merchant': rightEvidenceRail.key === 'merchant' }"
            :data-supplement-evidence="rightEvidenceRail.key"
          >
            {{ supplementing ? "正在补入卷宗…" : rightEvidenceRail.supplementLabel }}
            <input type="file" :disabled="supplementing" @change="supplementEvidence" />
          </label>

          <button class="evidence-expand-button" type="button">
            展开证据预览
            <span aria-hidden="true">↗</span>
          </button>
        </aside>

        <div class="hearing-side-actions">
          <button
            type="button"
            class="evidence-ledger-button"
            data-open-court-ledger
            @click="ledgerOpen = true"
          >
            查看庭审卷轴
          </button>
          <button
            type="button"
            class="evidence-complete-button"
            data-complete-hearing
            @click="router.push(`/disputes/${caseId}/outcome`)"
          >
            庭审完成
          </button>
        </div>
      </div>
    </main>

    <div
      v-if="ledgerOpen"
      class="court-ledger-backdrop"
      data-court-ledger-drawer
      role="dialog"
      aria-modal="true"
      aria-label="庭审卷轴"
      @click.self="ledgerOpen = false"
    >
      <aside class="hearing-ledger">
        <header>
          <div>
            <span>TRACEABLE ROUND LEDGER</span>
            <h2>庭审卷轴</h2>
            <p>这里保存每一轮封存后的可追溯记录，用于后续复核、申诉和平台终审。</p>
          </div>
          <button
            type="button"
            aria-label="关闭庭审卷轴"
            @click="ledgerOpen = false"
          >
            ×
          </button>
        </header>
        <ol>
          <li v-for="round in rounds" :key="round.round_id">
            <div>
              <strong>第 {{ round.round_no }} 轮</strong>
              <span :data-round-status="round.status">{{ roundStatusLabel(round.status) }}</span>
            </div>
            <p>{{ summary(round).judge || summary(round).clerk || "本轮记录已封存。" }}</p>
          </li>
        </ol>
        <div v-if="!rounds.length" class="hearing-ledger__empty" data-round-ledger-empty>
          <span aria-hidden="true">📜</span>
          <strong>第一轮庭审记录生成后，书记官会把卷轴挂在这里。</strong>
          <small>目前双方仍可先陈述、解释证据或提出一致方案。</small>
        </div>
      </aside>
    </div>

    <div v-if="settlementOpen" class="settlement-dialog" role="dialog" aria-modal="true">
      <form @submit.prevent="proposeSettlement">
        <header>
          <div>
            <span>CONSENSUS PROPOSAL</span>
            <h2>把一致方案写成双方都读得懂的话</h2>
          </div>
          <button type="button" aria-label="关闭一致方案窗口" @click="settlementOpen = false">×</button>
        </header>
        <label>
          一致方案
          <textarea
            v-model="proposalText"
            data-settlement-proposal
            rows="5"
            placeholder="例如：商家退款 299 元，用户确认不再主张额外赔偿。"
          />
        </label>
        <p>另一方确认后，该方案会成为法官草案，仍需平台审核员终审。</p>
        <button type="submit" data-submit-settlement :disabled="proposing || !proposalText.trim()">
          {{ proposing ? "正在生成签署卡…" : "提交一致方案" }}
        </button>
      </form>
    </div>
  </RoomShell>
</template>

<style scoped>
.court-agent-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
  min-width: 0;
}
.court-agent-strip :deep(.digital-human) {
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-width: 0;
  min-height: 132px;
  padding: 14px;
  border-radius: 24px;
  box-shadow: 0 16px 38px #536c8b12;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="judge"]) {
  background: linear-gradient(145deg, #fffaf0, #f6f8ff 56%, #eef8ff);
  border-color: #ecd9ad;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="jury-a"]) {
  background: linear-gradient(145deg, #ffffff, #f5f0ff 58%, #f8fbff);
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="jury-b"]) {
  background: linear-gradient(145deg, #ffffff, #effff9 52%, #f8f2ff);
}
.court-agent-strip :deep(.digital-human__portrait) {
  min-height: 96px;
}
.court-agent-strip :deep(.digital-human__portrait svg) {
  width: 96px;
  height: 96px;
}
.court-agent-strip :deep(.digital-human__state-dot) {
  right: 3px;
  bottom: 10px;
  width: 14px;
  height: 14px;
  border-width: 3px;
}
.court-agent-strip :deep(.digital-human__identity) {
  align-items: center;
}
.court-agent-strip :deep(.digital-human__identity strong) {
  font-size: 16px;
}
.court-agent-strip :deep(.digital-human__identity span) {
  margin-top: 2px;
  font-size: 11px;
}
.court-agent-strip :deep(.digital-human__identity small) {
  padding: 4px 7px;
  font-size: 10px;
  white-space: nowrap;
}
.court-agent-strip :deep(.digital-human__copy p) {
  display: -webkit-box;
  overflow: hidden;
  margin: 8px 0 0;
  color: #69758a;
  font-size: 11px;
  line-height: 1.42;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.court-agent-strip :deep(.digital-human__boundary) {
  display: none;
}
:deep(.room-shell) {
  gap: 8px;
  min-height: auto;
}
:deep(.room-shell__header) {
  align-items: center;
}
:deep(.room-shell__header h1) {
  margin: 3px 0 0;
  font-size: clamp(24px, 2.4vw, 30px);
  line-height: 1.16;
}
:deep(.room-shell__header p) {
  display: none;
}
:deep(.room-shell__boundary) {
  display: none;
}
:global(.app-page:has(.hearing-courtroom-page)) {
  padding-bottom: 42px;
}
.hearing-courtroom-page {
  position: relative;
  display: grid;
  grid-template-columns: 282px minmax(620px, 1fr) 282px;
  gap: 18px;
  height: clamp(720px, calc(100vh - 150px), 820px);
  min-height: 0;
}
.party-evidence-rail,
.courtroom-center {
  min-width: 0;
  background: #ffffffdf;
  border: 1px solid #dfe9f4;
  box-shadow: 0 22px 56px #536c8b12;
  backdrop-filter: blur(18px);
}
.party-evidence-rail {
  position: sticky;
  top: 96px;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 12px;
  height: 100%;
  padding: 16px;
  border-radius: 28px;
}
.party-evidence-rail--left {
  grid-column: 1;
}
.evidence-rail-column--right {
  grid-column: 3;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 10px;
  min-width: 0;
  height: 100%;
}
.evidence-rail-column--right .party-evidence-rail {
  position: static;
  height: 100%;
  min-height: 0;
}
.party-evidence-rail--right .evidence-pocket {
  gap: 9px;
}
.party-evidence-rail__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.party-evidence-rail__header span,
.round-input-bar span,
.hearing-ledger header span,
.settlement-card > span,
.settlement-dialog form header span {
  color: #7486a3;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}
.party-evidence-rail__header h2,
.hearing-ledger h2 {
  margin: 5px 0 4px;
  color: #30415c;
}
.party-evidence-rail__header p {
  margin: 0;
  color: #8996a8;
  font-size: 11px;
  line-height: 1.45;
}
.party-evidence-rail__header b {
  flex: 0 0 auto;
  padding: 7px 13px;
  color: #53619a;
  background: #edf7ff;
  border: 1px solid #cfe8f7;
  border-radius: 999px;
  font-size: 11px;
}
.party-evidence-rail--merchant .party-evidence-rail__header b {
  background: #fff3e9;
  border-color: #f4d7c8;
}
.evidence-pocket {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  overflow: auto;
  padding: 2px 3px 10px 0;
}
.evidence-pocket::-webkit-scrollbar,
.court-transcript__messages::-webkit-scrollbar {
  width: 8px;
}
.evidence-pocket::-webkit-scrollbar-thumb,
.court-transcript__messages::-webkit-scrollbar-thumb {
  background: #cbd8e8;
  border-radius: 999px;
}
.evidence-file-card {
  position: relative;
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-height: 72px;
  padding: 10px 10px 10px 14px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #ddeaf4;
  border-radius: 20px;
  box-shadow: 0 8px 20px #506c9410;
}
.evidence-file-card::before {
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 0;
  width: 4px;
  content: "";
  background: #17a8e6;
  border-radius: 0 999px 999px 0;
}
.evidence-file-card--purple::before { background: #afa1ff; }
.evidence-file-card--gold::before { background: #f6bf62; }
.evidence-file-card--mint::before { background: #78d9bd; }
.evidence-file-card__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  color: transparent;
  background: #eaf8ff;
  border-radius: 14px;
  font-style: normal;
}
.evidence-file-card__icon::before {
  color: #17a8e6;
  font-size: 13px;
  font-weight: 900;
  content: attr(data-type);
}
.evidence-file-card__icon[data-type="text"] { background: #f5f2ff; }
.evidence-file-card__icon[data-type="text"]::before { color: #7f70dd; content: "TXT"; }
.evidence-file-card__icon[data-type="image"]::before { content: "IMG"; }
.evidence-file-card__icon[data-type="video"] {
  background: #fff4e5;
}
.evidence-file-card__icon[data-type="video"]::before {
  color: #bd7b15;
  content: "VID";
}
.evidence-file-card strong {
  display: block;
  overflow: hidden;
  color: #33435c;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.evidence-file-card small {
  display: block;
  margin-top: 5px;
  color: #8492a7;
  font-size: 10px;
  font-weight: 700;
}
.evidence-file-card footer {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
}
.evidence-file-card footer span,
.evidence-file-card footer em {
  padding: 3px 8px;
  color: #2fa870;
  background: #e9fff8;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  font-weight: 900;
}
.evidence-file-card footer em {
  color: #8190a3;
  background: #f4fbff;
}
.party-evidence-rail--right .evidence-file-card {
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 10px;
  min-height: 62px;
  padding: 8px 9px 8px 12px;
}
.party-evidence-rail--right .evidence-file-card__icon {
  width: 38px;
  height: 38px;
  border-radius: 13px;
}
.party-evidence-rail--right .evidence-file-card small {
  margin-top: 3px;
}
.party-evidence-rail--right .evidence-file-card footer {
  margin-top: 5px;
}
.evidence-supplement-button,
.evidence-expand-button,
.evidence-ledger-button,
.evidence-complete-button {
  position: relative;
  display: flex;
  justify-content: center;
  gap: 12px;
  align-items: center;
  height: 38px;
  color: #53619a;
  background: #f6fafd;
  border: 1px solid #e0e7f0;
  border-radius: 999px;
  font-weight: 900;
  cursor: pointer;
}
.evidence-supplement-button {
  color: #0f8abf;
  background: #eaf8ff;
  border-color: #cae9f8;
}
.evidence-supplement-button--merchant {
  color: #9a681c;
  background: #fff5e6;
  border-color: #f1d9ae;
}
.evidence-supplement-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
.evidence-ledger-button {
  color: #58648a;
  background: #f3f0ff;
  border-color: #dfd7fb;
}
.hearing-side-actions {
  display: grid;
  gap: 8px;
}
.evidence-complete-button {
  color: #fff;
  background: linear-gradient(135deg, #62cda6, #4aa7d3);
  border-color: transparent;
  box-shadow: 0 12px 24px #4aa7d322;
}
.courtroom-center {
  grid-column: 2;
  display: grid;
  grid-template-rows: 180px minmax(352px, 1fr) auto auto;
  gap: 12px;
  height: 100%;
  overflow: hidden;
  padding: 14px 16px;
  border-radius: 30px;
}
.hearing-stage-dock {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 9px 14px;
  align-content: start;
  align-items: start;
  min-height: 0;
  padding: 12px 14px;
  overflow: hidden;
  background:
    radial-gradient(circle at 10% 0, #fff1c7 0 16%, transparent 17%),
    linear-gradient(135deg, #ffffffef, #f6fbff 52%, #fff8ef);
  border: 1px solid #ddeaf4;
  border-radius: 26px;
  box-shadow: inset 0 1px 0 #fff, 0 16px 34px #506c9412;
}
.hearing-stage-dock::after {
  position: absolute;
  inset: 8px 10px auto auto;
  width: 90px;
  height: 90px;
  pointer-events: none;
  content: "";
  background: radial-gradient(circle, #8bd7ff35 0 44%, transparent 45%);
}
.hearing-stage-dock--waiting {
  background:
    radial-gradient(circle at 10% 0, #f3e9ff 0 16%, transparent 17%),
    linear-gradient(135deg, #ffffffef, #f8f4ff 52%, #eefaff);
}
.hearing-stage-dock--sealed,
.hearing-stage-dock--handoff {
  background:
    radial-gradient(circle at 10% 0, #ffe6b6 0 16%, transparent 17%),
    linear-gradient(135deg, #fff9ec, #eef8ff 55%, #f6f0ff);
  border-color: #f0d7a7;
}
.hearing-stage-dock__copy {
  position: relative;
  z-index: 1;
  min-width: 0;
}
.hearing-stage-dock__copy span {
  color: #7486a3;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .08em;
}
.hearing-stage-dock__copy h2 {
  margin: 3px 0 4px;
  color: #30415c;
  font-size: 19px;
  line-height: 1.15;
}
.hearing-stage-dock__copy p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #6d7890;
  font-size: 11.5px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.hearing-stage-dock__badge {
  position: relative;
  z-index: 1;
  padding: 7px 11px;
  color: #0f8abf;
  white-space: nowrap;
  background: #e9f9ff;
  border: 1px solid #cde9f8;
  border-radius: 999px;
  font-size: 12px;
}
.hearing-stage-dock__meta {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 8px;
  justify-items: end;
}
.hearing-stage-dock--waiting .hearing-stage-dock__badge {
  color: #7d5cc5;
  background: #f1ebff;
  border-color: #dfd3ff;
}
.hearing-stage-dock--sealed .hearing-stage-dock__badge,
.hearing-stage-dock--handoff .hearing-stage-dock__badge {
  color: #9a6a18;
  background: #fff3d5;
  border-color: #efd5a2;
}
.round-progress-board {
  position: relative;
  grid-column: 1 / -1;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding: 8px;
  background: #ffffffed;
  border: 1px solid #ddeaf4;
  border-radius: 20px;
  box-shadow: 0 14px 28px #506c9412;
}
.round-progress-board__item {
  position: relative;
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  padding: 6px 7px;
  overflow: hidden;
  color: #91a0b4;
  background: #f8fbff;
  border: 1px solid #e8f0f8;
  border-radius: 16px;
  font-size: 11px;
  font-weight: 900;
}
.round-progress-board__item:not(:last-child)::after {
  position: absolute;
  top: 50%;
  right: -9px;
  width: 10px;
  height: 2px;
  content: "";
  background: #d7e6f1;
}
.round-progress-board__item b {
  flex: 0 0 auto;
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: #91a0b4;
  background: #fff;
  border: 1px solid #ddeaf4;
  border-radius: 50%;
}
.round-progress-board__item span {
  min-width: 0;
  overflow: hidden;
  color: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.round-progress-board__item em {
  flex: 0 0 auto;
  padding: 3px 6px;
  color: #8a98ad;
  background: #fff;
  border: 1px solid #e4edf6;
  border-radius: 999px;
  font-size: 9px;
  font-style: normal;
  font-weight: 900;
}
.round-progress-board__item--complete {
  color: #4e8f7f;
  background: linear-gradient(135deg, #f1fff8, #f6fffb);
  border-color: #cfeee2;
}
.round-progress-board__item--complete b {
  color: #fff;
  background: #78d9bd;
  border-color: #78d9bd;
}
.round-progress-board__item--complete em {
  color: #4b9b83;
  background: #eafff5;
  border-color: #ccefe2;
}
.round-progress-board__item--active {
  color: #34455e;
  background: linear-gradient(135deg, #eefaff, #fff);
  border-color: #cfeaf8;
}
.round-progress-board__item--active b {
  color: #fff;
  background: #17a8e6;
  border-color: #17a8e6;
  box-shadow: 0 0 0 6px #17a8e621;
}
.round-progress-board__item--active em {
  color: #17a8e6;
  background: #eaf9ff;
  border-color: #ccebf8;
}
.round-progress-board__item--pending {
  color: #91a0b4;
}
.hearing-stage-dock__status-grid {
  position: relative;
  grid-column: 1 / -1;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.hearing-status-chip {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 3px;
  min-width: 0;
  padding: 7px 9px 7px 23px;
  background: #ffffffb8;
  border: 1px solid #e4edf6;
  border-radius: 16px;
  box-shadow: inset 0 1px 0 #fff;
}
.hearing-status-chip::before {
  position: absolute;
  top: 12px;
  left: 10px;
  width: 7px;
  height: 7px;
  content: "";
  background: #91a0b4;
  border-radius: 50%;
  box-shadow: 0 0 0 4px #91a0b41b;
}
.hearing-status-chip span {
  overflow: hidden;
  color: #8b98aa;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 9px;
  font-weight: 900;
}
.hearing-status-chip strong {
  overflow: hidden;
  color: #34455e;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.hearing-status-chip--pending::before {
  background: #a6b4c7;
  box-shadow: 0 0 0 4px #a6b4c722;
}
.hearing-status-chip--submitted::before,
.hearing-status-chip--active::before {
  background: #17a8e6;
  box-shadow: 0 0 0 4px #17a8e622;
}
.hearing-status-chip--submitted strong,
.hearing-status-chip--active strong {
  color: #128ec4;
}
.hearing-status-chip--sealed::before,
.hearing-status-chip--complete::before {
  background: #78d9bd;
  box-shadow: 0 0 0 4px #78d9bd25;
}
.hearing-status-chip--sealed strong,
.hearing-status-chip--complete strong {
  color: #4b9b83;
}
.hearing-status-chip--waiting::before {
  background: #f6bf62;
  box-shadow: 0 0 0 4px #f6bf6228;
}
.hearing-status-chip--waiting strong {
  color: #a56e13;
}
.hearing-status-chip--processing::before {
  background: #afa1ff;
  box-shadow: 0 0 0 4px #afa1ff28;
}
.hearing-status-chip--processing strong {
  color: #7460d7;
}
.court-transcript {
  min-height: 0;
  height: 100%;
  overflow: visible;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.court-transcript__messages {
  display: grid;
  gap: 12px;
  height: 100%;
  padding: 2px 4px 10px;
  overflow: auto;
}
.court-message {
  display: grid;
  gap: 6px;
  max-width: 72%;
  padding: 12px 15px;
  border-radius: 20px;
  box-shadow: 0 8px 20px #506c940d;
}
.court-message header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: #7c899e;
  font-size: 10px;
  font-weight: 900;
}
.court-message p {
  margin: 0;
  color: #516178;
  font-size: 12px;
  line-height: 1.65;
}
.court-message--judge {
  justify-self: center;
  width: 66%;
  background: #f4fbff;
  border: 1px solid #cde9f8;
}
.court-message--judge header strong::before {
  margin-right: 6px;
  content: "⚖";
}
.court-message--user {
  justify-self: start;
  background: #eef9ff;
  border: 1px solid #cde9f8;
}
.court-message--merchant {
  justify-self: end;
  background: #fff6ec;
  border: 1px solid #f3d8bc;
}
.court-message--jury {
  justify-self: stretch;
  max-width: 100%;
  background: #f5f2ff;
  border: 1px solid #e1daff;
}
.round-input-bar {
  display: grid;
  gap: 9px;
  padding: 14px 16px 10px;
  background:
    radial-gradient(circle at 8% 0, #fff5c5 0 14%, transparent 15%),
    linear-gradient(135deg, #fff, #f2fbff 48%, #fff4f8);
  border: 1px solid #dfe8f2;
  border-radius: 24px;
  box-shadow: inset 0 1px 0 #fff, 0 14px 34px #5b769216;
}
.round-input-bar__body {
  min-width: 0;
}
.round-input-bar h3 {
  margin: 0;
  color: #34455e;
}
.round-input-bar__composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 12px;
  align-items: stretch;
  margin-top: 10px;
}
.round-input-bar__composer textarea {
  box-sizing: border-box;
  width: 100%;
  min-height: 86px;
  padding: 14px 15px;
  color: #25344c;
  background: #f8fbff;
  border: 1px solid #dce7f3;
  border-radius: 18px;
  outline: none;
  resize: none;
  font-size: 13px;
  line-height: 1.6;
}
.round-input-bar__composer textarea:disabled {
  color: #7f8ca0;
  background: #f3f7fb;
  cursor: not-allowed;
}
.round-input-bar__submit-column {
  display: grid;
  gap: 8px;
  align-content: stretch;
}
.round-input-bar__submit-column :deep(.phase-countdown) {
  min-height: auto;
}
.round-input-bar__send-button,
.round-input-bar__lock-button,
.round-input-bar__round-submit,
.round-input-bar__settlement-button {
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  color: #fff;
  background: linear-gradient(135deg, #20b8f0, #1097d3);
  border: 0;
  border-radius: 16px;
  box-shadow: 0 14px 28px #20a7df26;
  font-weight: 900;
  cursor: pointer;
}
.round-input-bar__send-button:disabled,
.round-input-bar__round-submit:disabled {
  cursor: not-allowed;
  opacity: .72;
}
.round-input-bar__round-submit {
  color: #28664e;
  background: #e2f8ec;
  border: 1px solid #bde8d1;
  box-shadow: none;
}
.round-input-bar__settlement-button {
  color: #8b5272;
  background: #fff0f4;
  border: 1px solid #f2d7df;
  box-shadow: none;
}
.round-input-bar__lock-button {
  color: #277154;
  background: #e1f6e9;
  border: 1px solid #bde8d1;
  box-shadow: none;
  cursor: not-allowed;
}
.round-input-bar__submitted {
  justify-self: stretch;
  padding: 11px 14px;
  color: #267152;
  text-align: center;
  background: #e1f6e9;
  border: 1px solid #bde8d1;
  border-radius: 14px;
}
.settlement-card button {
  padding: 8px 12px; color: #4c5d76; background: #f3f7fb; border: 1px solid #dae4ef; border-radius: 12px; cursor: pointer;
}
.court-ledger-backdrop {
  position: fixed;
  inset: 0;
  z-index: 68;
  display: flex;
  justify-content: flex-end;
  padding: 18px;
  background: #42557536;
  backdrop-filter: blur(10px);
}
.hearing-ledger {
  width: min(520px, 100%);
  min-width: 0;
  height: 100%;
  padding: 18px;
  overflow: auto;
  background:
    radial-gradient(circle at 14% 0, #fff1c7 0 16%, transparent 17%),
    linear-gradient(145deg, #ffffff, #f6fbff 58%, #fff7ec);
  border: 1px solid #dfe9f4;
  border-radius: 28px;
  box-shadow: 0 28px 80px #33445f30;
}
.hearing-ledger header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.hearing-ledger header p {
  max-width: 360px;
  margin: 4px 0 0;
  color: #7c899e;
  font-size: 12px;
  line-height: 1.55;
}
.hearing-ledger header button {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  color: #53617a;
  background: #f3f7fb;
  border: 1px solid #dce5ef;
  border-radius: 50%;
  cursor: pointer;
  font-size: 20px;
}
.hearing-ledger header h2 {
  margin: 3px 0 0;
  font-size: 24px;
}
.hearing-ledger ol {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  padding: 0;
  margin: 16px 0 0;
  list-style: none;
}
.hearing-ledger li { padding: 14px; background: #f7f9fc; border: 1px solid #e2eaf3; border-radius: 18px; }
.hearing-ledger li div { display: flex; justify-content: space-between; gap: 8px; }
.hearing-ledger li span { color: #657a9b; font-size: 10px; }
.hearing-ledger li p { margin: 7px 0 0; color: #6d798d; font-size: 12px; line-height: 1.55; }
.hearing-ledger__empty {
  display: grid;
  gap: 4px;
  padding: 10px;
  color: #6d7890;
  text-align: center;
  background: #f7f9fc;
  border: 1px dashed #d8e2ee;
  border-radius: 18px;
}
.hearing-ledger__empty span { font-size: 22px; }
.hearing-ledger__empty strong { color: #3f4d64; line-height: 1.5; }
.hearing-ledger__empty small { color: #8390a2; line-height: 1.5; }
.settlement-card { padding: 15px; background: linear-gradient(135deg, #fff6d9, #fff0ea); border: 1px solid #f0dfbd; border-radius: 20px; }
.settlement-card--dock {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px 14px;
  align-items: center;
  padding: 10px 12px;
}
.settlement-card--dock > span,
.settlement-card--dock .settlement-card__parties,
.settlement-card--dock small {
  display: none;
}
.settlement-card--dock h3,
.settlement-card--dock p {
  margin: 0;
}
.settlement-card--dock p {
  overflow: hidden;
  color: #756b69;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.settlement-card--dock button {
  width: auto;
  white-space: nowrap;
}
.settlement-card h3 { margin: 5px 0; color: #594e4d; }
.settlement-card p { color: #756b69; line-height: 1.55; }
.settlement-card__parties { display: flex; gap: 7px; margin: 10px 0; }
.settlement-card__parties i { padding: 5px 8px; color: #9a7a6d; background: #fff; border-radius: 999px; font-size: 11px; font-style: normal; }
.settlement-card__parties i.confirmed { color: #267152; background: #e1f6e9; }
.settlement-card button { width: 100%; color: white; background: linear-gradient(135deg, #ff8d70, #e9779d); border: 0; font-weight: 800; }
.settlement-card__confirmed { display: block; color: #277154; }
.settlement-card small { display: block; margin-top: 5px; color: #7d7280; }
.hearing-error { color: #a94552; }
.settlement-dialog {
  position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px;
  background: #42557540; backdrop-filter: blur(10px);
}
.settlement-dialog form {
  width: min(620px, 100%); padding: 22px; background: linear-gradient(145deg, #fff, #fff8e7);
  border: 1px solid #eadfbe; border-radius: 28px; box-shadow: 0 30px 80px #40506c32;
}
.settlement-dialog form header { display: flex; justify-content: space-between; gap: 20px; }
.settlement-dialog form header h2 { margin: 6px 0 16px; color: #4a4651; }
.settlement-dialog form header button { width: 36px; height: 36px; background: #f5ecd8; border: 0; border-radius: 50%; font-size: 21px; }
.settlement-dialog label { display: grid; gap: 7px; color: #695f61; font-size: 12px; }
.settlement-dialog textarea { padding: 11px; color: #4c464d; background: #fff; border: 1px solid #e5d9bf; border-radius: 13px; resize: vertical; }
.settlement-dialog p { color: #7d7170; font-size: 11px; }
.settlement-dialog form > button { width: 100%; padding: 12px; color: #fff; background: linear-gradient(135deg, #ff8d70, #e8759a); border: 0; border-radius: 13px; font-weight: 800; }
@media (max-width: 1180px) {
  .hearing-courtroom-page {
    grid-template-columns: minmax(0, 1fr);
  }
  .party-evidence-rail,
  .evidence-rail-column--right,
  .courtroom-center {
    grid-column: 1;
    grid-row: auto;
    position: static;
    height: auto;
  }
  .party-evidence-rail {
    max-height: none;
  }
  .evidence-pocket {
    max-height: 360px;
  }
}
@media (max-width: 680px) {
  .court-agent-strip,
  .hearing-stage-dock,
  .round-input-bar {
    grid-template-columns: 1fr;
  }
  .round-progress-board {
    position: static;
    grid-template-columns: 1fr;
    margin-top: 12px;
  }
  .court-message,
  .court-message--judge {
    width: auto;
    max-width: 100%;
  }
  .hearing-ledger ol {
    grid-template-columns: 1fr;
  }
}
</style>
