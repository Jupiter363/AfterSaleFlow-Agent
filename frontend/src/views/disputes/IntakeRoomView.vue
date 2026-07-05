<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { disputeApi } from "../../api/disputes";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  streamRoomEvents,
} from "../../stores/room";

const props = defineProps({
  initialDispute: { type: Object, default: null },
  initialAnalysis: { type: Object, default: null },
  initialTurnMemory: { type: Object, default: null },
  initialMessages: { type: Array, default: null },
  messagesLoader: { type: Function, default: null },
  turnMemoryLoader: { type: Function, default: null },
  postMessageAction: { type: Function, default: null },
  confirmAction: { type: Function, default: null },
  cancelAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const dispute = ref(props.initialDispute);
const analysis = ref(props.initialAnalysis);
const turnMemory = ref(props.initialTurnMemory);
const messages = ref([...(props.initialMessages || [])]);
const agentState = ref("LISTENING");
const confirmationNote = ref("以上信息无误，同意提交争议审理。");
const submitting = ref(false);
const admitted = ref(false);
const resolved = ref(false);
const error = ref("");
const eventState = reactive(createRoomState());
const eventAbortController = new AbortController();

const caseId = computed(() => dispute.value?.id || route.params.caseId);
const partyCanChat = computed(() => ["USER", "MERCHANT"].includes(actor.role));
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const stickers = computed(() => {
  const value = analysis.value || {};
  return [
    {
      label: "关联引用",
      value: [
        value.order_reference || dispute.value?.order_id,
        value.after_sales_reference || dispute.value?.after_sale_id,
        value.logistics_reference,
      ].filter(Boolean).join(" · "),
      tone: "blue",
    },
    {
      label: "发起方",
      value: value.initiator_role || "待确认",
      tone: "mint",
    },
    {
      label: "用户主张",
      value: value.party_claims?.user || dispute.value?.description,
      tone: "coral",
    },
    {
      label: "商家主张",
      value: value.party_claims?.merchant || "等待传票送达后回应",
      tone: "purple",
    },
    {
      label: "期望结果",
      value: value.requested_outcome || "等待进一步确认",
      tone: "yellow",
    },
    {
      label: "受理建议",
      value: value.admission_recommendation || "建议结合补充信息判断",
      tone: "mint",
    },
  ];
});

const scrollSnapshot = computed(() => turnMemory.value?.scroll_snapshot || null);
const currentCaseDossier = computed(() => turnMemory.value?.case_intake_dossier || null);
const caseDetailDossier = computed(() => {
  const current = currentCaseDossier.value?.dossier;
  if (current?.schema_version === "intake_case_detail.v1") return current;
  if (scrollSnapshot.value?.schema_version === "intake_case_detail.v1") return scrollSnapshot.value;
  return null;
});
const isCaseDetailDossier = computed(() => Boolean(caseDetailDossier.value));
const caseDetailQuality = computed(() => {
  const quality = caseDetailDossier.value?.intake_quality || {};
  return {
    score: currentCaseDossier.value?.quality_score ?? quality.score ?? 0,
    threshold: quality.threshold ?? 80,
    ready: currentCaseDossier.value?.ready_for_next_step ?? Boolean(quality.ready_for_next_step),
    reason: quality.improvement_reason || "",
  };
});
const caseDetailReadyCopy = computed(() =>
  caseDetailQuality.value.ready ? "可以进入下一步" : "继续补充案件信息",
);
const scrollCards = computed(() => scrollSnapshot.value?.cards || []);
function scrollCardValue(key, fallback = "") {
  return scrollCards.value.find((card) => card.key === key)?.value || fallback;
}
const riskSignals = computed(() => {
  const detailSignals = caseDetailDossier.value?.risk_assessment?.risk_signals || [];
  if (detailSignals.length) return detailSignals;
  const stamps = scrollSnapshot.value?.stamps || [];
  if (stamps.length) return stamps.map((stamp) => stamp.text || stamp.value).filter(Boolean);
  return analysis.value?.initial_risk_signals || ["Waiting for more information"];
});
const liveStickers = computed(() => {
  const value = analysis.value || {};
  const detail = caseDetailDossier.value;
  if (detail) {
    return [
      {
        label: "References",
        value: [
          detail.references?.order_reference,
          detail.references?.after_sales_reference,
          detail.references?.logistics_reference,
        ].filter(Boolean).join(" / "),
        tone: "blue",
      },
      {
        label: "Initiator",
        value: value.initiator_role || "Pending",
        tone: "mint",
      },
      {
        label: "User claim",
        value: detail.party_positions?.user_claim || value.party_claims?.user,
        tone: "coral",
      },
      {
        label: "Merchant claim",
        value: detail.party_positions?.merchant_claim || value.party_claims?.merchant || "Waiting for response",
        tone: "purple",
      },
      {
        label: "Expected outcome",
        value: detail.requested_resolution?.requested_outcome || "Pending",
        tone: "yellow",
      },
      {
        label: "Admission advice",
        value: detail.admission?.recommendation || currentCaseDossier.value?.admission_recommendation || "Needs more information",
        tone: "mint",
      },
      {
        label: "Risk signals",
        value: riskSignals.value.join(" / "),
        tone: "coral",
      },
    ];
  }
  return [
    {
      label: "References",
      value: [
        scrollCardValue("order_reference", value.order_reference || dispute.value?.order_id),
        scrollCardValue("after_sales_reference", value.after_sales_reference || dispute.value?.after_sale_id),
        scrollCardValue("logistics_reference", value.logistics_reference),
      ].filter(Boolean).join(" / "),
      tone: "blue",
    },
    {
      label: "Initiator",
      value: scrollCardValue("initiator_role", value.initiator_role || "Pending"),
      tone: "mint",
    },
    {
      label: "User claim",
      value: scrollCardValue("user_claim", value.party_claims?.user || dispute.value?.description),
      tone: "coral",
    },
    {
      label: "Merchant claim",
      value: scrollCardValue("merchant_claim", value.party_claims?.merchant || "Waiting for response"),
      tone: "purple",
    },
    {
      label: "Expected outcome",
      value: scrollCardValue("requested_outcome", value.requested_outcome || "Pending"),
      tone: "yellow",
    },
    {
      label: "Admission advice",
      value: scrollSnapshot.value?.admission_recommendation || value.admission_recommendation || "Needs more information",
      tone: "mint",
    },
    {
      label: "Risk signals",
      value: riskSignals.value.join(" / "),
      tone: "coral",
    },
  ];
});

async function load() {
  try {
    if (!dispute.value) {
      dispute.value = await disputeApi.get(actor, caseId.value);
    }
    if (props.initialMessages === null) {
      messages.value = await loadMessages();
    }
    if (props.initialTurnMemory === null && props.initialMessages === null) {
      await refreshTurnMemory();
    }
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function refreshMessages() {
  messages.value = await loadMessages();
}

async function loadMessages() {
  const loader =
    props.messagesLoader ||
    (() => roomApi.messages(actor, caseId.value, "INTAKE"));
  return loader();
}

async function refreshTurnMemory() {
  const loader =
    props.turnMemoryLoader ||
    (() => roomApi.latestTurnMemory(actor, caseId.value, "INTAKE"));
  turnMemory.value = await loader();
}

async function refreshRoomSnapshot() {
  await Promise.all([refreshMessages(), refreshTurnMemory()]);
}

function startEventStream() {
  const streamer = props.eventStreamer || streamRoomEvents;
  void streamer({
    actor,
    caseId: caseId.value,
    roomType: "INTAKE",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: refreshRoomSnapshot,
    applyEvent: async (event) => {
      if (event.event === "EVIDENCE_OPENED") {
        await router.push(`/disputes/${caseId.value}/evidence`);
      }
    },
  });
}

async function postMessage(command) {
  agentState.value = "THINKING";
  error.value = "";
  try {
    const submit =
      props.postMessageAction ||
      ((payload) => roomApi.postMessage(actor, caseId.value, "INTAKE", payload));
    await submit(command);
    await refreshRoomSnapshot();
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function resolveWithoutDispute() {
  submitting.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    reason: "resolved_before_admission",
  };
  try {
    const cancel =
      props.cancelAction ||
      ((payload) => disputeApi.cancelIntake(actor, caseId.value, payload.reason));
    const result = await cancel(command);
    if (result) {
      dispute.value = {
        ...(dispute.value || {}),
        ...result,
        id: result.case_id || result.caseId || dispute.value?.id || caseId.value,
      };
    }
    resolved.value = true;
    admitted.value = true;
    agentState.value = "HANDOFF";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submitting.value = false;
  }
}

async function confirmAdmission() {
  submitting.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    admissible: true,
    dispute_type: dispute.value?.dispute_type || "OTHER",
    risk_level: dispute.value?.risk_level || "MEDIUM",
    confirmation_note: confirmationNote.value,
  };
  try {
    const confirm =
      props.confirmAction ||
      ((payload) => disputeApi.confirmIntake(actor, caseId.value, payload));
    await confirm(command);
    admitted.value = true;
    agentState.value = "HANDOFF";
    await router.push(`/disputes/${caseId.value}/evidence`);
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  await load();
  if (props.eventStreamer || props.initialMessages === null) {
    startEventStream();
  }
});
onBeforeUnmount(() => eventAbortController.abort());
</script>

<template>
  <RoomShell
    eyebrow="INTAKE LOUNGE"
    title="争议接待室"
    :case-id="caseId"
    :connection-state="connectionState"
  >
    <template #agent>
      <DigitalHuman
        :state="agentState"
        name="小衡"
        role="争议接待官"
        :message="
          admitted
            ? '受理信息已经上报，证据书记官正在为双方准备证据书房。'
            : '先把事情完整告诉我，我会把引用、主张和诉求整理成可确认的卷宗贴纸。'
        "
      />
    </template>

    <div class="intake-room">
      <section class="intake-room__conversation">
        <div class="intake-room__case-note">
          <span>你正在说明</span>
          <h2>{{ dispute?.title || "履约争端" }}</h2>
          <p>{{ dispute?.description }}</p>
        </div>
        <ConversationStream
          :messages="messages"
          :disabled="submitting || admitted || !partyCanChat"
          :composer-visible="partyCanChat"
          disabled-reason="当前是平台观察/审核身份。请切换为用户或商家身份，才能继续与争议接待官对话。"
          placeholder="补充订单、物流、双方沟通或你的期望…"
          @submit="postMessage"
        />
      </section>

      <section class="intake-dossier" aria-label="受理分析卷宗">
        <header>
          <div>
            <span>LIVE DOSSIER</span>
            <h2>接待官整理出的争议轮廓</h2>
          </div>
          <small>可继续对话纠正</small>
        </header>

        <div
          v-if="isCaseDetailDossier"
          class="intake-case-detail"
          data-case-detail-dossier
        >
          <div class="intake-case-detail__score">
            <span>案件完善度</span>
            <strong>{{ caseDetailQuality.score }}/100</strong>
            <i :data-ready="caseDetailQuality.ready">{{ caseDetailReadyCopy }}</i>
          </div>
          <div class="intake-case-detail__story">
            <span>CASE STORY</span>
            <h3>{{ caseDetailDossier.case_story?.title }}</h3>
            <p>{{ caseDetailDossier.case_story?.one_sentence_summary }}</p>
          </div>
          <div class="intake-case-detail__focus">
            <span>核心争议</span>
            <strong>{{ caseDetailDossier.dispute_focus?.core_issue || "UNKNOWN" }}</strong>
            <p v-if="caseDetailDossier.risk_assessment?.reasoning">
              {{ caseDetailDossier.risk_assessment.reasoning }}
            </p>
          </div>
          <div
            v-if="caseDetailDossier.dispute_focus?.facts_to_verify?.length"
            class="intake-case-detail__chips"
          >
            <i
              v-for="fact in caseDetailDossier.dispute_focus.facts_to_verify"
              :key="fact"
            >
              待核验：{{ fact }}
            </i>
          </div>
          <p v-if="caseDetailQuality.reason" class="intake-case-detail__reason">
            {{ caseDetailQuality.reason }}
          </p>
        </div>

        <div class="intake-dossier__stickers">
          <article
            v-for="sticker in liveStickers"
            :key="sticker.label"
            class="intake-sticker"
            :data-tone="sticker.tone"
            data-intake-sticker
          >
            <span>{{ sticker.label }}</span>
            <strong>{{ sticker.value || "待补充" }}</strong>
          </article>
          <article
            class="intake-sticker intake-sticker--wide"
            data-tone="coral"
            data-intake-sticker
          >
            <span>初始风险信号</span>
            <div class="intake-sticker__chips">
              <i
                v-for="signal in analysis?.initial_risk_signals || ['等待更多信息']"
                :key="signal"
              >
                {{ signal }}
              </i>
            </div>
          </article>
        </div>

        <div class="intake-dossier__confirm">
          <label>
            最终确认说明
            <textarea v-model="confirmationNote" rows="3" />
          </label>
          <p>AI 受理建议非最终；确认后双方会收到平台传票。</p>
          <button
            type="button"
            data-confirm-admission
            :disabled="submitting || admitted"
            @click="confirmAdmission"
          >
            <span v-if="admitted">已上报</span>
            <span v-else-if="submitting">正在盖章…</span>
            <span v-else>确认发起并上报</span>
          </button>
          <div v-if="admitted" class="intake-dossier__stamp">已上报</div>
          <button
            type="button"
            class="intake-dossier__secondary"
            data-resolve-without-dispute
            :disabled="submitting || admitted"
            @click="resolveWithoutDispute"
          >
            问题已解决，取消争议
          </button>
          <p v-if="resolved">已在平台内取消争议发起，接待室已归档。</p>
          <div v-if="error" class="intake-dossier__error">{{ error }}</div>
        </div>
      </section>
    </div>
  </RoomShell>
</template>

<style scoped>
.intake-room {
  display: grid;
  grid-template-columns: minmax(330px, .9fr) minmax(480px, 1.2fr);
  gap: 18px;
}
.intake-room__conversation,
.intake-dossier {
  padding: 20px;
  background: #ffffffbf;
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px #556d9512;
}
.intake-room__case-note {
  padding: 16px;
  margin-bottom: 14px;
  background: linear-gradient(135deg, #e8f6ff, #f4efff);
  border-radius: 20px;
}
.intake-room__case-note span,
.intake-dossier header span {
  color: #7186aa;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .16em;
}
.intake-room__case-note h2 { margin: 5px 0; color: #34435c; }
.intake-room__case-note p { margin: 0; color: #6f7d92; line-height: 1.6; }
.intake-dossier header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
}
.intake-dossier header h2 { margin: 5px 0 0; color: #34435c; font-size: 23px; }
.intake-dossier header small { color: #7384a1; }
.intake-case-detail {
  display: grid;
  gap: 12px;
  margin: 16px 0;
  padding: 16px;
  background:
    radial-gradient(circle at 12% 12%, #fff7cf 0 12%, transparent 13%),
    linear-gradient(135deg, #fffdf8, #eef7ff);
  border: 1px solid #dce9f6;
  border-radius: 24px;
  box-shadow: inset 0 1px 0 #ffffff, 0 16px 35px #55739914;
}
.intake-case-detail__score {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: #ffffffd9;
  border: 1px solid #e2ebf5;
  border-radius: 18px;
}
.intake-case-detail__score span,
.intake-case-detail__story span,
.intake-case-detail__focus span {
  color: #7788a5;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .14em;
}
.intake-case-detail__score strong {
  color: #5b69d8;
  font-size: 22px;
}
.intake-case-detail__score i {
  padding: 6px 10px;
  color: #8b6170;
  background: #fff0f4;
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
}
.intake-case-detail__score i[data-ready="true"] {
  color: #22795e;
  background: #dff8ec;
}
.intake-case-detail__story h3 {
  margin: 6px 0;
  color: #2f3e58;
  font-size: 18px;
}
.intake-case-detail__story p,
.intake-case-detail__focus p,
.intake-case-detail__reason {
  margin: 0;
  color: #68768e;
  line-height: 1.65;
}
.intake-case-detail__focus {
  display: grid;
  gap: 5px;
  padding: 12px;
  background: #f8fbff;
  border: 1px dashed #d4e1f0;
  border-radius: 18px;
}
.intake-case-detail__focus strong {
  color: #ef7c67;
  letter-spacing: .03em;
}
.intake-case-detail__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.intake-case-detail__chips i {
  padding: 7px 10px;
  color: #5e6f88;
  background: #fff;
  border: 1px solid #e2eaf5;
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
}
.intake-dossier__stickers {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0;
}
.intake-sticker {
  display: grid;
  gap: 8px;
  min-height: 104px;
  padding: 15px;
  color: #3c4760;
  background: #eaf6ff;
  border: 1px solid #d4e9f7;
  border-radius: 8px 22px 22px 22px;
  transform: rotate(-.35deg);
}
.intake-sticker:nth-child(even) { transform: rotate(.45deg); }
.intake-sticker[data-tone="mint"] { background: #e7f8ef; border-color: #cdebdc; }
.intake-sticker[data-tone="coral"] { background: #fff0e9; border-color: #f4d9ce; }
.intake-sticker[data-tone="purple"] { background: #f2edff; border-color: #e0d8f8; }
.intake-sticker[data-tone="yellow"] { background: #fff7d9; border-color: #efe2af; }
.intake-sticker > span { color: #738099; font-size: 11px; }
.intake-sticker > strong { line-height: 1.55; }
.intake-sticker--wide { grid-column: 1 / -1; min-height: auto; }
.intake-sticker__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.intake-sticker__chips i {
  padding: 6px 9px;
  color: #a24f4f;
  background: #fff;
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
}
.intake-dossier__confirm {
  position: relative;
  padding: 16px;
  background: #f8fbff;
  border: 1px dashed #cddbec;
  border-radius: 20px;
}
.intake-dossier__confirm label { display: grid; gap: 7px; color: #58657b; font-size: 13px; }
.intake-dossier__confirm textarea {
  padding: 10px;
  color: #2f3e58;
  background: #fff;
  border: 1px solid #dce5f1;
  border-radius: 12px;
}
.intake-dossier__confirm p { color: #7b718e; font-size: 12px; }
.intake-dossier__confirm button {
  width: 100%;
  padding: 13px;
  color: white;
  background: linear-gradient(135deg, #ff8c72, #8e8bef);
  border: 0;
  border-radius: 14px;
  cursor: pointer;
  font-weight: 800;
}
.intake-dossier__confirm button:disabled { opacity: .7; }
.intake-dossier__confirm .intake-dossier__secondary {
  margin-top: 9px;
  color: #69758a;
  background: #edf4fb;
}
.intake-dossier__stamp {
  position: absolute;
  right: 25px;
  bottom: 64px;
  padding: 8px 13px;
  color: #e45d55;
  border: 3px double #e45d55;
  border-radius: 9px;
  transform: rotate(-8deg);
  font-weight: 900;
}
.intake-dossier__error { margin-top: 10px; color: #ad4853; }
@media (max-width: 980px) {
  .intake-room { grid-template-columns: 1fr; }
}
@media (max-width: 580px) {
  .intake-dossier__stickers { grid-template-columns: 1fr; }
  .intake-sticker--wide { grid-column: auto; }
}
</style>
