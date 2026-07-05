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
import {
  humanizeDossierList,
  humanizeDossierText,
  roleLabel,
} from "../../utils/displayText";

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
const submitting = ref(false);
const admitted = ref(false);
const resolved = ref(false);
const error = ref("");
const eventState = reactive(createRoomState());
const eventAbortController = new AbortController();

const caseId = computed(() => dispute.value?.id || route.params.caseId);
const caseNoteTitle = computed(() =>
  humanizeDossierText(dispute.value?.title || "履约争端", {
    kind: "title",
    fallback: "履约争端",
  }),
);
const caseNoteDescription = computed(() =>
  humanizeDossierText(dispute.value?.description || "", {
    kind: "summary",
    fallback: "接待官正在整理争议事实，请继续补充订单、证据和处理诉求。",
  }),
);
const partyCanChat = computed(() => ["USER", "MERCHANT"].includes(actor.role));
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
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
    reason: humanizeDossierText(quality.improvement_reason || "", { fallback: "" }),
  };
});
const caseDetailReadyCopy = computed(() =>
  caseDetailQuality.value.ready ? "可以进入下一步" : "继续完善案件信息",
);
const caseRiskGradeValue = computed(() =>
  caseDetailDossier.value?.risk_assessment?.case_grade ||
  dispute.value?.risk_level ||
  analysis.value?.risk_level ||
  "UNKNOWN",
);
const caseRiskGradeCopy = computed(() =>
  humanizeDossierText(caseRiskGradeValue.value, { fallback: "风险待确认" }),
);
const caseRiskGradeTone = computed(() => {
  const value = String(caseRiskGradeValue.value || "").toUpperCase();
  if (value.includes("HIGH") || value.includes("高")) return "high";
  if (value.includes("MEDIUM") || value.includes("中")) return "medium";
  if (value.includes("LOW") || value.includes("低")) return "low";
  return "unknown";
});
const handoffRemarkSticker = computed(() => {
  const notes = caseDetailDossier.value?.handoff_notes;
  if (!notes) return null;
  const latestRemark = humanizeDossierText(notes.latest_remark || "", {
    fallback: "",
  });
  const status = notes.remark_status === "NO_EXTRA_REMARKS"
    ? "无额外备注"
    : "待随案件详情提交";
  const value = latestRemark || status;
  if (!value) return null;
  return {
    label: "下一轮备注",
    value,
    tone: "purple",
  };
});
const scrollCards = computed(() => scrollSnapshot.value?.cards || []);
function scrollCardValue(key, fallback = "") {
  return scrollCards.value.find((card) => card.key === key)?.value || fallback;
}
const riskSignals = computed(() => {
  const detailSignals = caseDetailDossier.value?.risk_assessment?.risk_signals || [];
  if (detailSignals.length) return humanizeDossierList(detailSignals);
  const stamps = scrollSnapshot.value?.stamps || [];
  if (stamps.length) {
    return humanizeDossierList(
      stamps.map((stamp) => stamp.text || stamp.value).filter(Boolean),
    );
  }
  return humanizeDossierList(analysis.value?.initial_risk_signals);
});
const initialRiskSignals = computed(() => {
  return humanizeDossierList(analysis.value?.initial_risk_signals);
});
const initiatorRoleValue = computed(() => {
  const explicitRole =
    analysis.value?.initiator_role ||
    dispute.value?.initiator_role ||
    dispute.value?.initiatorRole;
  if (explicitRole) return explicitRole;
  return ["USER", "MERCHANT"].includes(actor.role) ? actor.role : "UNKNOWN";
});
const initiatorRoleCopy = computed(() =>
  roleLabel(initiatorRoleValue.value || "UNKNOWN"),
);

function formatReferenceSummary(references = {}) {
  const items = [
    ["订单", references.order],
    ["售后", references.afterSales],
    ["物流", references.logistics],
  ].filter(([, value]) => Boolean(value));
  if (!items.length) return "待补充";
  return items.map(([label, value]) => `${label}：${value}`).join(" / ");
}

function detailReferenceSummary(detail, value) {
  return formatReferenceSummary({
    order: detail?.references?.order_reference || value.order_reference || dispute.value?.order_id,
    afterSales:
      detail?.references?.after_sales_reference ||
      value.after_sales_reference ||
      dispute.value?.after_sale_id,
    logistics: detail?.references?.logistics_reference || value.logistics_reference,
  });
}

function fallbackReferenceSummary(value) {
  return formatReferenceSummary({
    order: scrollCardValue("order_reference", value.order_reference || dispute.value?.order_id),
    afterSales: scrollCardValue(
      "after_sales_reference",
      value.after_sales_reference || dispute.value?.after_sale_id,
    ),
    logistics: scrollCardValue("logistics_reference", value.logistics_reference),
  });
}

const liveStickers = computed(() => {
  const value = analysis.value || {};
  const detail = caseDetailDossier.value;
  if (detail) {
    return [
      {
        label: "订单 / 售后 / 物流",
        value: detailReferenceSummary(detail, value),
        tone: "blue",
      },
      {
        label: "发起方",
        value: initiatorRoleCopy.value,
        tone: "mint",
      },
      {
        label: "用户主张",
        value: humanizeDossierText(detail.party_positions?.user_claim || value.party_claims?.user),
        tone: "coral",
      },
      {
        label: "商家主张",
        value: humanizeDossierText(
          detail.party_positions?.merchant_claim ||
            value.party_claims?.merchant ||
            "Waiting for response",
        ),
        tone: "purple",
      },
      {
        label: "期望处理结果",
        value: humanizeDossierText(
          detail.requested_resolution?.expected_resolution_text ||
            detail.requested_resolution?.requested_outcome ||
            "Pending",
        ),
        tone: "yellow",
      },
      {
        label: "受理建议",
        value: humanizeDossierText(
          detail.admission?.recommendation ||
            currentCaseDossier.value?.admission_recommendation ||
            "Needs more information",
        ),
        tone: "mint",
      },
      ...(handoffRemarkSticker.value ? [handoffRemarkSticker.value] : []),
      {
        label: "风险信号",
        value: riskSignals.value.join(" / "),
        tone: "coral",
      },
    ];
  }
  return [
    {
      label: "订单 / 售后 / 物流",
      value: fallbackReferenceSummary(value),
      tone: "blue",
    },
    {
      label: "发起方",
      value: humanizeDossierText(
        scrollCardValue("initiator_role", initiatorRoleValue.value),
        { fallback: "待确认" },
      ),
      tone: "mint",
    },
    {
      label: "用户主张",
      value: humanizeDossierText(scrollCardValue("user_claim", value.party_claims?.user || dispute.value?.description)),
      tone: "coral",
    },
    {
      label: "商家主张",
      value: humanizeDossierText(scrollCardValue("merchant_claim", value.party_claims?.merchant || "Waiting for response")),
      tone: "purple",
    },
    {
      label: "期望处理结果",
      value: humanizeDossierText(scrollCardValue("requested_outcome", value.requested_outcome || "Pending")),
      tone: "yellow",
    },
    {
      label: "受理建议",
      value: humanizeDossierText(
        scrollSnapshot.value?.admission_recommendation ||
          value.admission_recommendation ||
          "Needs more information",
      ),
      tone: "mint",
    },
    {
      label: "风险信号",
      value: riskSignals.value.join(" / "),
      tone: "coral",
    },
  ];
});

function pickStickers(labels) {
  return labels
    .map((label) => liveStickers.value.find((sticker) => sticker.label === label))
    .filter(Boolean);
}

const caseDetailMetaSections = computed(() => {
  return [
    {
      title: "案件索引",
      tone: "blue",
      items: pickStickers(["订单 / 售后 / 物流", "发起方"]),
    },
    {
      title: "双方说法",
      tone: "purple",
      items: pickStickers(["用户主张", "商家主张"]),
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

function nextLocalSequenceNo() {
  return Math.max(0, ...messages.value.map((message) => message.sequence_no || 0)) + 1;
}

function appendOptimisticPartyMessage(command) {
  if (!command?.text?.trim()) return "";
  const id = `PENDING_${Date.now()}_${Math.random().toString(16).slice(2)}`;
  messages.value = [
    ...messages.value,
    {
      id,
      sequence_no: nextLocalSequenceNo(),
      sender_role: actor.role,
      message_text: command.text.trim(),
      pending: true,
    },
  ];
  return id;
}

function removeOptimisticMessage(id) {
  if (!id) return;
  messages.value = messages.value.filter((message) => message.id !== id);
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
  submitting.value = true;
  error.value = "";
  const optimisticId = appendOptimisticPartyMessage(command);
  try {
    const submit =
      props.postMessageAction ||
      ((payload) => roomApi.postMessage(actor, caseId.value, "INTAKE", payload));
    await submit(command);
    await refreshRoomSnapshot();
    agentState.value = "SPEAKING";
  } catch (failure) {
    removeOptimisticMessage(optimisticId);
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submitting.value = false;
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
          <h2>{{ caseNoteTitle }}</h2>
          <p>{{ caseNoteDescription }}</p>
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
          <small data-dossier-progress-hint>{{ caseDetailReadyCopy }}</small>
        </header>

        <div
          class="intake-case-detail"
          data-case-detail-dossier
        >
          <div class="intake-case-detail__score-row">
            <div class="intake-case-detail__score">
              <span>案件完善度</span>
              <strong>{{ caseDetailQuality.score }}/100</strong>
            </div>
            <div
              class="intake-case-detail__risk"
              :data-risk="caseRiskGradeTone"
              data-case-risk-grade
            >
              <span>接待官初评风险</span>
              <strong>{{ caseRiskGradeCopy }}</strong>
            </div>
          </div>
          <div class="intake-case-detail__story">
            <span>案件故事</span>
            <h3>
              {{
                humanizeDossierText(caseDetailDossier?.case_story?.title || caseNoteTitle, {
                  kind: "title",
                  fallback: "争议事件待梳理",
                })
              }}
            </h3>
            <p>
              {{
                humanizeDossierText(caseDetailDossier?.case_story?.one_sentence_summary || caseNoteDescription, {
                  kind: "summary",
                  fallback: "接待官正在整理争议事实，请继续补充订单、证据和处理诉求。",
                })
              }}
            </p>
          </div>
          <div
            v-if="isCaseDetailDossier"
            class="intake-case-detail__focus"
          >
            <span>核心争议</span>
            <strong>
              {{ humanizeDossierText(caseDetailDossier?.dispute_focus?.core_issue || "UNKNOWN") }}
            </strong>
          </div>
          <div
            v-if="caseDetailDossier?.dispute_focus?.facts_to_verify?.length"
            class="intake-case-detail__chips"
          >
            <i
              v-for="fact in caseDetailDossier?.dispute_focus?.facts_to_verify"
              :key="fact"
            >
              待核验：{{ humanizeDossierText(fact) }}
            </i>
          </div>
          <p v-if="caseDetailQuality.reason" class="intake-case-detail__reason">
            {{ caseDetailQuality.reason }}
          </p>
          <section class="intake-case-detail__meta" data-case-detail-meta>
            <article
              v-for="section in caseDetailMetaSections"
              :key="section.title"
              class="intake-case-detail__meta-section"
              :data-tone="section.tone"
            >
              <span>{{ section.title }}</span>
              <div class="intake-case-detail__meta-grid">
                <article
                  v-for="sticker in section.items"
                  :key="`${section.title}-${sticker.label}`"
                  class="intake-sticker"
                  :data-tone="sticker.tone"
                  data-intake-sticker
                >
                  <span>{{ sticker.label }}</span>
                  <strong>{{ sticker.value || "待补充" }}</strong>
                </article>
              </div>
            </article>
          </section>
        </div>

        <div class="intake-dossier__confirm">
          <div class="intake-dossier__actions intake-dossier__actions--two-column">
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
            <button
              type="button"
              class="intake-dossier__secondary"
              data-resolve-without-dispute
              :disabled="submitting || admitted"
              @click="resolveWithoutDispute"
            >
              问题已解决，取消争议
            </button>
          </div>
          <div v-if="admitted" class="intake-dossier__stamp">已上报</div>
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
  grid-template-columns: minmax(520px, 1.05fr) minmax(480px, .95fr);
  gap: 18px;
  align-items: stretch;
}
.intake-room__conversation,
.intake-dossier {
  min-width: 0;
  padding: 20px;
  background: #ffffffbf;
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px #556d9512;
}
.intake-room__conversation {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
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
.intake-case-detail__score-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(150px, .72fr);
  gap: 10px;
}
.intake-case-detail__score,
.intake-case-detail__risk {
  display: grid;
  gap: 10px;
  padding: 10px 12px;
  background: #ffffffd9;
  border: 1px solid #e2ebf5;
  border-radius: 18px;
}
.intake-case-detail__risk {
  background: linear-gradient(135deg, #fff7f1, #fff);
}
.intake-case-detail__risk[data-risk="high"] {
  background: linear-gradient(135deg, #fff0ec, #fff);
  border-color: #ffd7ce;
}
.intake-case-detail__risk[data-risk="medium"] {
  background: linear-gradient(135deg, #fff8dc, #fff);
  border-color: #eadca1;
}
.intake-case-detail__risk[data-risk="low"] {
  background: linear-gradient(135deg, #e9faef, #fff);
  border-color: #cdebd8;
}
.intake-case-detail__score span,
.intake-case-detail__risk span,
.intake-case-detail__story span,
.intake-case-detail__focus span,
.intake-case-detail__meta-section > span {
  color: #7788a5;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .14em;
}
.intake-case-detail__score strong,
.intake-case-detail__risk strong {
  color: #5b69d8;
  font-size: 22px;
}
.intake-case-detail__risk[data-risk="high"] strong { color: #d85b4a; }
.intake-case-detail__risk[data-risk="medium"] strong { color: #b78b10; }
.intake-case-detail__risk[data-risk="low"] strong { color: #2f8b64; }
.intake-case-detail__meta {
  display: grid;
  gap: 10px;
  padding: 12px;
  background: #ffffffa8;
  border: 1px solid #e4edf7;
  border-radius: 20px;
}
.intake-case-detail__meta-section {
  display: grid;
  gap: 8px;
}
.intake-case-detail__meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
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
.intake-sticker {
  position: relative;
  display: grid;
  gap: 7px;
  min-height: 82px;
  padding: 13px 13px 13px 15px;
  color: #3c4760;
  background: #fff;
  border: 1px solid #e3ebf5;
  border-radius: 16px;
  overflow: hidden;
}
.intake-sticker::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: #83c6f5;
}
.intake-sticker[data-tone="mint"]::before { background: #75ce9e; }
.intake-sticker[data-tone="coral"]::before { background: #ff9a7e; }
.intake-sticker[data-tone="purple"]::before { background: #a491f1; }
.intake-sticker[data-tone="yellow"]::before { background: #efc84c; }
.intake-sticker[data-tone="blue"] { background: #f8fcff; }
.intake-sticker[data-tone="mint"] { background: #f7fdf9; }
.intake-sticker[data-tone="coral"] { background: #fff9f6; }
.intake-sticker[data-tone="purple"] { background: #fbf9ff; }
.intake-sticker[data-tone="yellow"] { background: #fffdf4; }
.intake-sticker > span { color: #738099; font-size: 11px; }
.intake-sticker > strong { line-height: 1.55; }
.intake-dossier__confirm {
  position: relative;
  display: grid;
  gap: 10px;
  padding: 16px;
  background: #f8fbff;
  border: 1px dashed #cddbec;
  border-radius: 20px;
}
.intake-dossier__confirm p { color: #7b718e; font-size: 12px; }
.intake-dossier__actions {
  display: grid;
  gap: 10px;
}
.intake-dossier__actions--two-column {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
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
  color: #69758a;
  background: #edf4fb;
}
.intake-dossier__stamp {
  justify-self: end;
  width: fit-content;
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
  .intake-case-detail__score-row,
  .intake-case-detail__meta-grid,
  .intake-dossier__actions--two-column {
    grid-template-columns: 1fr;
  }
}
</style>
