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
  initialMessages: { type: Array, default: null },
  confirmAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const dispute = ref(props.initialDispute);
const analysis = ref(props.initialAnalysis);
const messages = ref([...(props.initialMessages || [])]);
const agentState = ref("LISTENING");
const confirmationNote = ref("以上信息无误，同意提交争议审理。");
const submitting = ref(false);
const admitted = ref(false);
const error = ref("");
const eventState = reactive(createRoomState());
const eventAbortController = new AbortController();

const caseId = computed(() => dispute.value?.id || route.params.caseId);
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

async function load() {
  try {
    if (!dispute.value) {
      dispute.value = await disputeApi.get(actor, caseId.value);
    }
    if (props.initialMessages === null) {
      messages.value = await roomApi.messages(actor, caseId.value, "INTAKE");
    }
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function refreshMessages() {
  messages.value = await roomApi.messages(
    actor,
    caseId.value,
    "INTAKE",
  );
}

function startEventStream() {
  const streamer = props.eventStreamer || streamRoomEvents;
  void streamer({
    actor,
    caseId: caseId.value,
    roomType: "INTAKE",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: refreshMessages,
    applyEvent: async (event) => {
      if (event.event === "EVIDENCE_OPENED") {
        await router.push(`/disputes/${caseId.value}/evidence`);
      }
    },
  });
}

async function postMessage(command) {
  agentState.value = "THINKING";
  const saved = await roomApi.postMessage(
    actor,
    caseId.value,
    "INTAKE",
    command,
  );
  messages.value.push(saved);
  agentState.value = "SPEAKING";
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
          :disabled="submitting || admitted"
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

        <div class="intake-dossier__stickers">
          <article
            v-for="sticker in stickers"
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
