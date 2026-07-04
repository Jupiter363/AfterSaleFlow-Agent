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
    eyebrow="COLLABORATIVE COURT"
    title="履约争端小法庭"
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
      <div class="court-agents">
        <DigitalHuman
          state="SPEAKING"
          name="小册"
          role="证据书记官"
          message="我负责依次宣读双方证据，并标记本轮新增材料。"
        />
        <DigitalHuman
          :state="agentState"
          name="衡衡"
          role="AI 法官"
          message="我会主持固定三轮陈述，并在第三轮后生成裁决方案草案；草案仍需平台审核员确认。"
        />
        <DigitalHuman
          state="THINKING"
          name="圆桌团"
          role="AI 评审团"
          message="我们只在最终方案后，从事实完整性、规则一致性与双方可接受度三个角度复核草案。"
        />
        <DigitalHuman
          v-if="isReviewer"
          state="LISTENING"
          name="小译"
          role="审核解释官"
          message="我只向平台审核员转述争点、证据与草案，不代替审核员作出终审。"
        />
      </div>
    </template>

    <div class="hearing-court">
      <section class="court-stage">
        <header class="court-stage__header">
          <div>
            <span>LIVE HEARING</span>
            <h2>第 {{ currentRound }} / {{ roundLimit }} 轮</h2>
          </div>
          <div class="round-dots" aria-label="庭审轮次">
            <i
              v-for="roundNumber in roundLimit"
              :key="roundNumber"
              :class="{
                complete: roundNumber < currentRound,
                active: roundNumber === currentRound,
              }"
            />
          </div>
        </header>

        <div class="courtroom" aria-label="协作小法庭">
          <div class="courtroom__bench">
            <span aria-hidden="true">⚖️</span>
            <strong>AI 法官席</strong>
            <p>{{ summary(activeRound).judge || "正在核对证据链、归纳本轮争点，并为第三轮后的最终方案做准备。" }}</p>
          </div>
          <div class="courtroom__party courtroom__party--user">
            <span>🧑</span><strong>用户席</strong><small>可陈述、解释证据、确认和解</small>
          </div>
          <div class="courtroom__clerk">
            <span>📚</span><strong>证据书记官</strong>
            <p>{{ summary(activeRound).clerk || "本轮证据已按序摆上证据台。" }}</p>
          </div>
          <div class="courtroom__party courtroom__party--merchant">
            <span>🏪</span><strong>商家席</strong><small>可答辩、解释证据、确认和解</small>
          </div>
          <div class="courtroom__jury">
            <span>💬</span><strong>AI 评审团</strong>
            <p>{{ summary(activeRound).jury || "等待裁决草案后进行一致性复核。" }}</p>
          </div>
        </div>

        <section v-if="isCaseParty" class="round-submit-card">
          <div class="round-submit-card__copy">
            <span>ROUND COMMITMENT</span>
            <h3>本轮双方确认提交</h3>
            <p v-if="activeRoundClosed">
              双方已提交本轮，本轮陈述已封存；第三轮结束后，AI 法官会统一生成确定的裁决方案草案。
            </p>
            <p v-else-if="currentActorSubmitted">
              已提交本轮，等待{{ counterpartyLabel }}。双方都提交后，系统会自动封存本轮陈述并开放下一轮。
            </p>
            <p v-else>
              当前陈述、证据解释和和解意向会被封装为本轮立场。双方都点击提交，或 5 分钟时效届满后，系统自动封存并推进流程。
            </p>
          </div>
          <div class="round-submit-card__control">
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
            <strong v-else class="round-submit-card__submitted">
              {{ activeRoundClosed ? "本轮已封存" : "已提交本轮" }}
            </strong>
          </div>
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

        <section ref="hearingDialogue" class="hearing-dialogue">
          <header>
            <span>COURT DIALOGUE</span>
            <h3>双方庭审陈述</h3>
          </header>
          <ConversationStream
            :messages="messages"
            placeholder="回应当前争点，或说明新证据与和解意愿…"
            @submit="postMessage"
          />
        </section>
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
.court-agents {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.hearing-court { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(300px, .65fr); gap: 18px; }
.court-stage, .hearing-ledger {
  padding: 20px;
  background: #ffffffd9;
  border: 1px solid #dfe8f2;
  border-radius: 28px;
  box-shadow: 0 22px 56px #536c8b10;
}
.court-stage__header { display: flex; justify-content: space-between; align-items: center; }
.court-stage__header span, .hearing-ledger header span, .settlement-card > span {
  color: #7486a3; font-size: 10px; font-weight: 900; letter-spacing: .16em;
}
.court-stage h2, .hearing-ledger h2 { margin: 5px 0; color: #33435c; }
.round-dots { display: flex; gap: 7px; }
.round-dots i { width: 11px; height: 11px; background: #e0e6ef; border-radius: 50%; }
.round-dots i.complete { background: #75d0a3; }
.round-dots i.active { background: #ff8f71; box-shadow: 0 0 0 5px #ff8f7126; }
.courtroom {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  grid-template-areas:
    ". bench ."
    "user clerk merchant"
    ". jury .";
  gap: 13px;
  padding: 22px;
  margin: 16px 0;
  background:
    radial-gradient(circle at 50% 0, #fff4cc 0 11%, transparent 12%),
    linear-gradient(180deg, #edf8ff, #f7f1ff);
  border: 2px solid #fff;
  border-radius: 32px 32px 18px 18px;
}
.courtroom > div { padding: 14px; text-align: center; border-radius: 18px; }
.courtroom span { display: block; margin-bottom: 5px; font-size: 24px; }
.courtroom p, .courtroom small { display: block; margin: 5px 0 0; color: #6f7d92; line-height: 1.5; }
.courtroom__bench { grid-area: bench; background: #fff5d8; border: 1px solid #f0dfaa; }
.courtroom__party--user { grid-area: user; background: #e5f6ff; }
.courtroom__clerk { grid-area: clerk; background: #fff; border: 1px dashed #d9dfea; }
.courtroom__party--merchant { grid-area: merchant; background: #e8f8ef; }
.courtroom__jury { grid-area: jury; background: #f0ebff; }
.round-submit-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, .46fr);
  gap: 14px;
  align-items: center;
  padding: 16px;
  margin: 0 0 14px;
  background:
    radial-gradient(circle at 8% 0, #fff5c5 0 14%, transparent 15%),
    linear-gradient(135deg, #fff, #f2fbff 48%, #fff4f8);
  border: 1px solid #dfe8f2;
  border-radius: 22px;
  box-shadow: inset 0 1px 0 #ffffff, 0 14px 34px #5b769216;
}
.round-submit-card__copy span {
  color: #7486a3;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}
.round-submit-card__copy h3 {
  margin: 4px 0 7px;
  color: #34455e;
}
.round-submit-card__copy p {
  margin: 0;
  color: #6d7890;
  line-height: 1.6;
}
.round-submit-card__control {
  display: grid;
  gap: 9px;
  align-content: center;
}
.round-submit-card__control button {
  width: 100%;
  padding: 12px 14px;
  color: #fff;
  background: linear-gradient(135deg, #6b8cff, #f07fa3);
  border: 0;
  border-radius: 15px;
  box-shadow: 0 14px 28px #6b8cff28;
  font-weight: 900;
  cursor: pointer;
}
.round-submit-card__control button:disabled {
  cursor: wait;
  opacity: .72;
}
.round-submit-card__submitted {
  justify-self: stretch;
  padding: 11px 14px;
  color: #267152;
  text-align: center;
  background: #e1f6e9;
  border: 1px solid #bde8d1;
  border-radius: 14px;
}
.court-actions { display: flex; gap: 9px; flex-wrap: wrap; }
.court-actions button, .settlement-card button {
  padding: 10px 13px; color: #4c5d76; background: #f3f7fb; border: 1px solid #dae4ef; border-radius: 12px; cursor: pointer;
}
.court-actions__upload {
  position: relative;
  padding: 10px 13px;
  color: #4c5d76;
  background: #f3f7fb;
  border: 1px solid #dae4ef;
  border-radius: 12px;
  cursor: pointer;
}
.court-actions__upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.court-actions .court-actions__settle { color: #8b5272; background: #fff0f4; border-color: #f2d7df; }
.hearing-dialogue { padding: 15px; margin-top: 15px; background: #f8fbff; border: 1px solid #e1e9f2; border-radius: 20px; }
.hearing-dialogue header span, .settlement-dialog form header span { color: #7486a3; font-size: 10px; font-weight: 900; letter-spacing: .16em; }
.hearing-dialogue h3 { margin: 4px 0 10px; color: #3d4d65; }
.hearing-ledger ol { display: grid; gap: 10px; padding: 0; list-style: none; }
.hearing-ledger li { padding: 13px; background: #f7f9fc; border-radius: 15px; }
.hearing-ledger li div { display: flex; justify-content: space-between; gap: 8px; }
.hearing-ledger li span { color: #657a9b; font-size: 10px; }
.hearing-ledger li p { margin: 7px 0 0; color: #6d798d; font-size: 12px; line-height: 1.55; }
.hearing-ledger__empty {
  display: grid;
  gap: 8px;
  padding: 18px;
  color: #6d7890;
  text-align: center;
  background: #f7f9fc;
  border: 1px dashed #d8e2ee;
  border-radius: 18px;
}
.hearing-ledger__empty span { font-size: 28px; }
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
  .court-agents { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 940px) {
  .hearing-court { grid-template-columns: 1fr; }
}
@media (max-width: 680px) {
  .court-agents { grid-template-columns: 1fr; }
  .courtroom { grid-template-columns: 1fr; grid-template-areas: "bench" "user" "clerk" "merchant" "jury"; }
  .round-submit-card { grid-template-columns: 1fr; }
}
</style>
