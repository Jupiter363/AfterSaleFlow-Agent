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
import ConversationStream from "../../components/room/ConversationStream.vue";
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
const proposalText = ref("");
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
const roleLabels = {
  USER: "用户",
  MERCHANT: "商家",
  PLATFORM_REVIEWER: "平台审核员",
};
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
const activeRoundSummary = computed(() => summary(activeRound.value));
const currentRoleLabel = computed(() => roleLabels[role.value] || "体验身份");
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
      <p class="court-agent-note">
        三轮结构化庭审 · 单轮 5 分钟 · 双方提交或超时后自动封存 · AI 建议非最终
      </p>
    </template>

    <main class="hearing-courtroom-page" data-hearing-courtroom-page>
      <aside
        class="party-evidence-rail party-evidence-rail--user"
        data-party-evidence-rail="user"
      >
        <header class="party-evidence-rail__header">
          <div>
            <span>USER EVIDENCE</span>
            <h2>用户证据原件匣</h2>
            <p>固定高度展示，更多材料在内部滚动。</p>
          </div>
          <b>用户侧</b>
        </header>

        <section class="party-avatar-card">
          <DigitalHuman
            state="LISTENING"
            name="用户代表"
            role="路线引导员 · 用户"
            message="用户可围绕签收事实、未收货经过和已提交证据进行陈述。"
          />
        </section>

        <div class="evidence-pocket" aria-label="用户已提交证据">
          <article
            v-for="item in mockEvidenceRails.user"
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

        <button class="evidence-expand-button" type="button">
          展开证据预览
          <span aria-hidden="true">↗</span>
        </button>
      </aside>

      <section class="courtroom-center">
        <section class="judge-bench" data-judge-bench>
          <div class="jury-seat jury-seat--left">
            <DigitalHuman
              state="THINKING"
              name="评审 A"
              role="AI 评审团"
              message="我关注事实完整性、证据冲突和风险信号。"
            />
            <strong class="jury-seat__label">评审 A · 风险评分</strong>
          </div>

          <div class="judge-seat">
            <div class="judge-seat__avatar">
              <DigitalHuman
                :state="agentState"
                name="衡衡"
                role="AI 法官"
                message="我会主持三轮陈述，并在第三轮后生成非最终裁决草案。"
              />
            </div>
            <div class="judge-seat__desk" aria-hidden="true">
              <span class="judge-seat__book">法典</span>
              <span class="judge-seat__gavel">法槌</span>
            </div>
            <strong>主审法官席</strong>
            <p>{{ activeRoundSummary.judge || "正在核对证据链、归纳本轮争点，并准备提出下一轮问题。" }}</p>
          </div>

          <div class="jury-seat jury-seat--right">
            <DigitalHuman
              state="LISTENING"
              name="评审 B"
              role="AI 评审团"
              message="我关注裁决草案是否符合平台规则和双方可接受度。"
            />
            <strong class="jury-seat__label">评审 B · 方案复核</strong>
          </div>

          <div class="round-progress-board">
            <article
              v-for="roundNumber in roundLimit"
              :key="roundNumber"
              :class="{
                complete: roundNumber < currentRound,
                active: roundNumber === currentRound,
              }"
            >
              <b>{{ roundNumber }}</b>
              <span>{{ roundStepLabels[roundNumber - 1] || `第 ${roundNumber} 轮` }}</span>
            </article>
            <div class="round-progress-board__timer">
              <small>本轮倒计时</small>
              <strong>04:18</strong>
            </div>
          </div>
        </section>

        <section class="court-transcript" data-court-transcript>
          <header class="court-transcript__header">
            <div>
              <span>COURT TRANSCRIPT</span>
              <h2>庭审记录大屏</h2>
            </div>
            <small>证据书记官已宣读双方材料 · 第 {{ currentRound }} / {{ roundLimit }} 轮 · {{ currentRoundLabel }} · 内部滚动</small>
          </header>

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

            <ConversationStream
              :messages="messages"
              placeholder="回应当前争点，或说明新证据与和解意愿…"
              @submit="postMessage"
            />
          </div>
        </section>

        <section v-if="isReviewer" class="review-aide-agent-card">
          <DigitalHuman
            state="LISTENING"
            name="小译"
            role="审核解释官"
            message="我只向平台审核员转述争点、证据与草案，不代替审核员作出终审。"
          />
          <p>
            审核员视角已开启：可查看庭审归纳、陪审团评分与裁决草案交接信息。
          </p>
        </section>

        <section
          v-if="isCaseParty"
          class="round-input-bar"
          data-round-input-bar
        >
          <div>
            <span>ROUND STATEMENT PODIUM</span>
            <h3>本轮陈述发言台</h3>
            <p>{{ roundSubmitDescription }}</p>
          </div>
          <div class="round-input-bar__controls">
            <PhaseCountdown
              v-if="activeRoundDeadline && !activeRoundClosed"
              label="本轮提交时效"
              :deadline-at="activeRoundDeadline"
              :server-now="effectiveServerNow"
            />
            <button
              v-if="canSubmitRound"
              type="button"
              data-submit-hearing-round
              :disabled="submittingRound"
              @click="submitCurrentRound"
            >
              {{ submittingRound ? "正在提交本轮…" : "提交本轮陈述" }}
            </button>
            <strong v-else class="round-input-bar__submitted">
              {{ activeRoundClosed ? "本轮已封存" : "已提交，等待对方或倒计时结束" }}
            </strong>
          </div>
          <footer>
            <i>用户提交</i>
            <i>商家提交</i>
            <i>法官处理中</i>
            <i>下一轮状态</i>
          </footer>
        </section>

        <section
          v-if="reviewHandoffVisible"
          class="review-handoff-card"
          data-review-handoff
        >
          <div>
            <span>REVIEW HANDOFF</span>
            <h3>{{ reviewHandoffTitle }}</h3>
            <p>{{ reviewHandoffBody }}</p>
          </div>
          <button type="button" @click="router.push('/disputes')">
            返回争议订单中心
          </button>
        </section>

        <div class="court-actions">
          <label class="court-actions__upload">
            {{ supplementing ? "正在补入卷宗…" : "补充证据" }}
            <input type="file" :disabled="supplementing" @change="supplementEvidence" />
          </label>
          <button type="button" @click="$refs.hearingDialogue?.scrollIntoView?.({ behavior: 'smooth' })">
            回应当前争点
          </button>
          <button
            type="button"
            class="court-actions__settle"
            data-open-settlement
            @click="settlementOpen = true"
          >
            提出一致方案
          </button>
        </div>
      </section>

      <aside class="hearing-ledger">
        <header>
          <span>ROUND LEDGER</span>
          <h2>庭审卷轴</h2>
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

        <section v-if="activeSettlement" class="settlement-card">
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
      </aside>
      <aside
        class="party-evidence-rail party-evidence-rail--merchant"
        data-party-evidence-rail="merchant"
      >
        <header class="party-evidence-rail__header">
          <div>
            <span>MERCHANT EVIDENCE</span>
            <h2>商家证据原件匣</h2>
            <p>正式提交后进入庭审可见证据架。</p>
          </div>
          <b>商家侧</b>
        </header>

        <section class="party-avatar-card">
          <DigitalHuman
            state="SPEAKING"
            name="商家代表"
            role="路线引导员 · 商家履约代表"
            message="商家可围绕发货、物流交接、签收凭证和异常工单进行说明。"
          />
        </section>

        <div class="evidence-pocket" aria-label="商家已提交证据">
          <article
            v-for="item in mockEvidenceRails.merchant"
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

        <button class="evidence-expand-button" type="button">
          展开证据预览
          <span aria-hidden="true">↗</span>
        </button>
      </aside>
    </main>

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
.court-agent-note {
  width: max-content;
  max-width: 100%;
  padding: 7px 12px;
  margin: 0;
  color: #6f6388;
  background: #f4efff;
  border: 1px solid #e7dcff;
  border-radius: 14px;
  font-size: 11px;
  font-weight: 800;
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
  padding-bottom: 18px;
}
.hearing-courtroom-page {
  position: relative;
  display: grid;
  grid-template-columns: 282px minmax(620px, 1fr) 282px;
  grid-template-rows: minmax(0, 1fr) 106px;
  gap: 14px 18px;
  height: clamp(560px, calc(100vh - 285px), 615px);
  min-height: 0;
}
.party-evidence-rail,
.courtroom-center,
.hearing-ledger {
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
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 10px;
  height: 100%;
  padding: 16px;
  border-radius: 28px;
}
.party-evidence-rail--user {
  grid-row: 1 / span 2;
  grid-column: 1;
}
.party-evidence-rail--merchant {
  grid-row: 1 / span 2;
  grid-column: 3;
}
.party-evidence-rail__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.party-evidence-rail__header span,
.court-transcript__header span,
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
.court-transcript__header h2,
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
.party-avatar-card {
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 18%, #eaf8ff 0 22%, transparent 23%),
    linear-gradient(135deg, #f8fcff, #fff);
  border: 1px solid #dcecf6;
  border-radius: 24px;
}
.party-evidence-rail--merchant .party-avatar-card {
  background:
    radial-gradient(circle at 18% 18%, #fff2e7 0 22%, transparent 23%),
    linear-gradient(135deg, #fff8f2, #fff);
  border-color: #f3decf;
}
.party-avatar-card :deep(.digital-human) {
  grid-template-columns: 78px minmax(0, 1fr);
  gap: 10px;
  min-height: 0;
  padding: 10px;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.party-avatar-card :deep(.digital-human__portrait) {
  width: 78px;
  height: 78px;
}
.party-avatar-card :deep(.digital-human__portrait svg) {
  width: 78px;
  height: 78px;
}
.party-avatar-card :deep(.digital-human__identity) {
  display: grid;
  gap: 4px;
}
.party-avatar-card :deep(.digital-human__identity strong),
.party-avatar-card :deep(.digital-human__identity span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.party-avatar-card :deep(.digital-human__identity strong) {
  font-size: 15px;
}
.party-avatar-card :deep(.digital-human__identity span) {
  font-size: 10px;
}
.party-avatar-card :deep(.digital-human__identity small) {
  width: max-content;
  padding: 4px 7px;
  font-size: 10px;
}
.party-avatar-card :deep(.digital-human__copy p) {
  -webkit-line-clamp: 2;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  font-size: 11px;
  line-height: 1.4;
  margin: 6px 0 0;
}
.party-avatar-card :deep(.digital-human__boundary) {
  display: none;
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
.evidence-expand-button {
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
.courtroom-center {
  grid-row: 1;
  grid-column: 2;
  display: grid;
  grid-template-rows: 150px minmax(0, 1fr) auto auto;
  gap: 10px;
  height: 100%;
  overflow: hidden;
  padding: 14px 16px;
  border-radius: 30px;
}
.judge-bench {
  position: relative;
  display: grid;
  grid-template-columns: minmax(110px, .56fr) minmax(240px, 1fr) minmax(110px, .56fr);
  gap: 14px;
  min-height: 0;
  padding: 7px 18px 46px;
  overflow: hidden;
  background:
    radial-gradient(circle at 50% 0, #fff0c9 0 16%, transparent 17%),
    linear-gradient(180deg, #ffffffee, #f7fbffdc);
  border: 1px solid #ddeaf4;
  border-radius: 28px;
}
.judge-bench::after {
  position: absolute;
  right: 44px;
  bottom: 66px;
  left: 44px;
  height: 42px;
  pointer-events: none;
  content: "";
  border: 1px dashed #a9c5df80;
  border-top: 0;
  border-radius: 0 0 50% 50%;
}
.jury-seat,
.judge-seat {
  position: relative;
  display: grid;
  justify-items: center;
  text-align: center;
}
.jury-seat :deep(.digital-human) {
  width: 100%;
  padding: 8px;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.jury-seat :deep(.digital-human__portrait) {
  width: 70px;
  height: 70px;
}
.jury-seat :deep(.digital-human__portrait svg) {
  width: 70px;
  height: 70px;
}
.jury-seat :deep(.digital-human__copy p),
.jury-seat :deep(.digital-human__copy),
.jury-seat :deep(.digital-human__boundary) {
  display: none;
}
.jury-seat__label {
  padding: 5px 9px;
  margin-top: -4px;
  color: #53619a;
  background: #ffffffd9;
  border: 1px solid #dfe8f2;
  border-radius: 999px;
  font-size: 11px;
  box-shadow: 0 8px 18px #536c8b12;
}
.judge-seat {
  z-index: 1;
  align-self: start;
}
.judge-seat__avatar :deep(.digital-human) {
  width: 214px;
  padding: 0;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.judge-seat__avatar :deep(.digital-human__portrait) {
  width: 100px;
  height: 100px;
  margin: 0 auto -12px;
}
.judge-seat__avatar :deep(.digital-human__portrait svg) {
  width: 100px;
  height: 100px;
}
.judge-seat__avatar :deep(.digital-human__copy) {
  display: none;
}
.judge-seat__desk {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  width: 214px;
  height: 36px;
  padding: 9px 24px 0;
  margin-top: -20px;
  background: linear-gradient(135deg, #f7d59a, #d79c4e);
  border: 1px solid #c58b3c;
  border-radius: 22px 22px 16px 16px;
  box-shadow: 0 16px 24px #a46d2a20;
}
.judge-seat__book,
.judge-seat__gavel {
  color: #fff3da;
  font-size: 11px;
  font-weight: 900;
}
.judge-seat > strong {
  margin-top: 6px;
  color: #34455e;
}
.judge-seat > p {
  max-width: 320px;
  margin: 4px 0 0;
  color: #7b8798;
  font-size: 11px;
  display: -webkit-box;
  overflow: hidden;
  line-height: 1.3;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.round-progress-board {
  position: absolute;
  right: 16px;
  bottom: 10px;
  left: 16px;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)) 118px;
  gap: 8px;
  padding: 8px 10px;
  background: #ffffffed;
  border: 1px solid #ddeaf4;
  border-radius: 22px;
  box-shadow: 0 14px 28px #506c9412;
}
.round-progress-board article {
  display: flex;
  gap: 9px;
  align-items: center;
  color: #91a0b4;
  font-size: 12px;
  font-weight: 900;
}
.round-progress-board article b {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  color: #91a0b4;
  background: #fff;
  border: 1px solid #ddeaf4;
  border-radius: 50%;
}
.round-progress-board article.complete b {
  color: #fff;
  background: #78d9bd;
}
.round-progress-board article.active {
  color: #34455e;
}
.round-progress-board article.active b {
  color: #fff;
  background: #17a8e6;
  box-shadow: 0 0 0 6px #17a8e621;
}
.round-progress-board__timer {
  display: grid;
  justify-items: end;
}
.round-progress-board__timer small {
  color: #91a0b4;
  font-size: 9px;
  font-weight: 900;
}
.round-progress-board__timer strong {
  color: #17a8e6;
  font-size: 26px;
  line-height: 1;
}
.court-transcript {
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background:
    linear-gradient(90deg, #8bd7ff00, #8bd7ff70 18%, #ffd48a70 82%, #ffd48a00),
    linear-gradient(180deg, #fff, #f7fcff 55%, #fff8ee);
  background-size: 100% 4px, 100% 100%;
  background-repeat: no-repeat;
  background-position: top 18px center, 0 0;
  border: 1px solid #ddeaf4;
  border-radius: 26px;
  box-shadow: inset 0 1px 0 #fff, 0 18px 40px #506c9412;
}
.court-transcript__header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  padding: 12px 20px 6px;
}
.court-transcript__header h2 {
  margin-bottom: 0;
  font-size: 18px;
}
.court-transcript__header small {
  padding: 6px 10px;
  color: #7d8a9f;
  background: #f6fafd;
  border: 1px solid #e4edf6;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 900;
}
.court-transcript__messages {
  display: grid;
  gap: 12px;
  max-height: calc(100% - 52px);
  padding: 4px 20px 12px;
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
.court-transcript :deep(.conversation-stream) {
  padding-top: 4px;
  background: transparent;
  border: 0;
}
.court-transcript :deep(.conversation-stream__messages) {
  max-height: 120px;
}
.court-transcript :deep(.conversation-stream__composer) {
  background: #ffffffc9;
  border-radius: 18px;
}
.round-input-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, .32fr);
  gap: 8px 12px;
  align-items: center;
  padding: 9px 14px 8px;
  background:
    radial-gradient(circle at 8% 0, #fff5c5 0 14%, transparent 15%),
    linear-gradient(135deg, #fff, #f2fbff 48%, #fff4f8);
  border: 1px solid #dfe8f2;
  border-radius: 24px;
  box-shadow: inset 0 1px 0 #fff, 0 14px 34px #5b769216;
}
.round-input-bar h3 {
  margin: 3px 0 5px;
  color: #34455e;
}
.round-input-bar p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #6d7890;
  font-size: 11px;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.round-input-bar__controls {
  display: grid;
  gap: 6px;
  align-content: center;
}
.round-input-bar__controls button {
  width: 100%;
  padding: 10px 12px;
  color: #fff;
  background: linear-gradient(135deg, #20b8f0, #1097d3);
  border: 0;
  border-radius: 16px;
  box-shadow: 0 14px 28px #20a7df26;
  font-weight: 900;
  cursor: pointer;
}
.round-input-bar__controls button:disabled {
  cursor: wait;
  opacity: .72;
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
.round-input-bar footer {
  grid-column: 1 / -1;
  display: flex;
  gap: 10px;
  color: #8b98aa;
  font-size: 9px;
  font-weight: 900;
}
.round-input-bar footer i {
  font-style: normal;
}
.round-input-bar footer i::before {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 6px;
  content: "";
  background: #17a8e6;
  border-radius: 50%;
}
.round-input-bar footer i:nth-child(2)::before { background: #f6bf62; }
.round-input-bar footer i:nth-child(3)::before { background: #afa1ff; }
.round-input-bar footer i:nth-child(4)::before { background: #78d9bd; }
.review-aide-agent-card {
  display: grid;
  grid-template-columns: minmax(210px, .42fr) minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 9px 12px;
  background:
    radial-gradient(circle at 6% 0, #efe9ff 0 16%, transparent 17%),
    linear-gradient(135deg, #fbfaff, #f4fbff 60%, #fff8ef);
  border: 1px solid #e5defa;
  border-radius: 22px;
  box-shadow: inset 0 1px 0 #fff, 0 12px 28px #7157b914;
}
.review-aide-agent-card :deep(.digital-human) {
  min-height: 0;
}
.review-aide-agent-card p {
  margin: 0;
  color: #65748a;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.7;
}
.review-handoff-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  padding: 16px;
  margin: 0 0 14px;
  background:
    radial-gradient(circle at 10% 0, #ffe4ad 0 12%, transparent 13%),
    linear-gradient(135deg, #fff9ec, #eef8ff 55%, #f6f0ff);
  border: 1px solid #f1d7a7;
  border-radius: 22px;
  box-shadow: inset 0 1px 0 #ffffff, 0 16px 34px #a8753417;
}
.review-handoff-card span {
  color: #a47333;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}
.review-handoff-card h3 {
  margin: 4px 0 7px;
  color: #34455e;
}
.review-handoff-card p {
  max-width: 680px;
  margin: 0;
  color: #6d7890;
  line-height: 1.6;
}
.review-handoff-card button {
  padding: 12px 15px;
  color: #fff;
  white-space: nowrap;
  background: linear-gradient(135deg, #ffb65c, #f07fa3);
  border: 0;
  border-radius: 15px;
  box-shadow: 0 14px 28px #f07fa326;
  font-weight: 900;
  cursor: pointer;
}
.court-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.court-actions button, .settlement-card button {
  padding: 8px 12px; color: #4c5d76; background: #f3f7fb; border: 1px solid #dae4ef; border-radius: 12px; cursor: pointer;
}
.court-actions__upload {
  position: relative;
  padding: 8px 12px;
  color: #4c5d76;
  background: #f3f7fb;
  border: 1px solid #dae4ef;
  border-radius: 12px;
  cursor: pointer;
}
.court-actions__upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.court-actions .court-actions__settle { color: #8b5272; background: #fff0f4; border-color: #f2d7df; }
.hearing-ledger {
  grid-row: 2;
  grid-column: 2;
  height: 100%;
  padding: 12px 14px;
  overflow: auto;
  border-radius: 24px;
}
.hearing-ledger header {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 12px;
}
.hearing-ledger header h2 {
  margin: 3px 0 0;
  font-size: 16px;
}
.hearing-ledger ol {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 0;
  margin: 8px 0 0;
  list-style: none;
}
.hearing-ledger li { padding: 10px; background: #f7f9fc; border-radius: 15px; }
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
  .courtroom-center,
  .hearing-ledger {
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
  .judge-bench,
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
