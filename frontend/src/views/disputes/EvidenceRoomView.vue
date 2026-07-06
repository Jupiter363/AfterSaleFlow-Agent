<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
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
  initialCatalog: { type: Object, default: null },
  initialCompletion: { type: Object, default: null },
  initialMessages: { type: Array, default: null },
  viewerRole: { type: String, default: "" },
  deadlineAt: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  completeAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
  messageAction: { type: Function, default: null },
  agentReplyPollAttempts: { type: Number, default: 5 },
  agentReplyPollDelayMs: { type: Number, default: 1500 },
});

const route = useRoute();
const router = useRouter();
const catalog = ref(props.initialCatalog);
const completion = ref(props.initialCompletion);
const uploading = ref(false);
const completing = ref(false);
const error = ref("");
const agentState = ref("LISTENING");
const fileInput = ref(null);
const messages = ref([...(props.initialMessages || [])]);
const eventState = reactive(createRoomState());
let eventAbortController = new AbortController();
let eventStreamActive = false;
let workspaceGeneration = 0;

const caseId = computed(
  () => catalog.value?.case_id || route.params.caseId,
);
const role = computed(() => props.viewerRole || actor.role);
const demoActorIds = {
  USER: "user-local",
  MERCHANT: "merchant-local",
  PLATFORM_REVIEWER: "reviewer-local",
};
const effectiveActor = computed(() => {
  if (actor.role === role.value) return actor;
  return {
    ...actor,
    id: demoActorIds[role.value] || actor.id,
    role: role.value,
  };
});
const isMerchant = computed(() => role.value === "MERCHANT");
const items = computed(() => catalog.value?.items || []);
const privateItems = computed(() =>
  items.value.filter(
    (item) =>
      item.visibility === "PRIVATE" &&
      item.submitted_by_role === role.value,
  ),
);
const sharedItems = computed(() =>
  items.value.filter((item) => item.visibility === "PARTIES"),
);
const effectiveDeadline = computed(
  () =>
    props.deadlineAt ||
    completion.value?.deadline_at ||
    new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
);
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const currentPartyCompleted = computed(() => {
  if (completion.value?.sealed) return true;
  if (role.value === "USER") return Boolean(completion.value?.user_completed);
  if (role.value === "MERCHANT") return Boolean(completion.value?.merchant_completed);
  return false;
});
const waitingCounterpartyLabel = computed(() =>
  isMerchant.value ? "用户" : "商家",
);
const completionStatusMessage = computed(() => {
  if (completion.value?.sealed) {
    return "卷宗已封存，庭审入口已开放。";
  }
  if (currentPartyCompleted.value) {
    return `你的举证已完成，正在等待${waitingCounterpartyLabel.value}完成或举证时效结束。`;
  }
  return "提交完成只代表当前一方完成；系统会等待另一方或举证时效结束。";
});
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const evidenceSourceType = computed(() => {
  if (role.value === "MERCHANT") return "MERCHANT_UPLOAD";
  if (role.value === "USER") return "USER_UPLOAD";
  return "PLATFORM_UPLOAD";
});

const statusLabels = {
  PENDING: "待核验",
  VERIFIED: "已核验",
  PLAUSIBLE: "基本可信",
  SUSPICIOUS: "存在疑点",
  REJECTED: "不予采纳",
  NEEDS_HUMAN_REVIEW: "待人工复核",
};

const evidenceTypeLabels = {
  CHAT_SCREENSHOT: "沟通截图",
  LOGISTICS_PROOF: "物流凭证",
  DELIVERY_RECORD: "履约记录",
  VIDEO: "视频证据",
  OTHER: "其他材料",
};

async function load() {
  const generation = workspaceGeneration;
  const actorSnapshot = { ...effectiveActor.value };
  const caseSnapshot = caseId.value;
  try {
    if (catalog.value === null) {
      const nextCatalog = await evidenceApi.catalog(actorSnapshot, caseSnapshot);
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      catalog.value = nextCatalog;
    }
    if (completion.value === null) {
      const nextCompletion = await evidenceApi.completion(actorSnapshot, caseSnapshot);
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      completion.value = nextCompletion;
    }
    if (props.initialMessages === null) {
      const nextMessages = await roomApi.messages(
        actorSnapshot,
        caseSnapshot,
        "EVIDENCE",
      );
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      messages.value = nextMessages;
    }
  } catch (failure) {
    if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

function isCurrentWorkspace(generation, actorSnapshot, caseSnapshot) {
  return (
    generation === workspaceGeneration &&
    caseSnapshot === caseId.value &&
    actorSnapshot.id === effectiveActor.value.id &&
    actorSnapshot.role === effectiveActor.value.role
  );
}

async function refreshWorkspace(options = {}) {
  const generation = options.generation ?? workspaceGeneration;
  const actorSnapshot = { ...effectiveActor.value };
  const caseSnapshot = caseId.value;
  const [nextCatalog, nextCompletion, nextMessages] = await Promise.all([
    evidenceApi.catalog(actorSnapshot, caseSnapshot),
    evidenceApi.completion(actorSnapshot, caseSnapshot),
    roomApi.messages(actorSnapshot, caseSnapshot, "EVIDENCE"),
  ]);
  if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) {
    return false;
  }
  catalog.value = nextCatalog;
  completion.value = nextCompletion;
  messages.value = nextMessages;
  return true;
}

function nextLocalSequence() {
  return Math.max(0, ...messages.value.map((message) => message.sequence_no || 0)) + 1;
}

function upsertMessage(message) {
  const existingIndex = messages.value.findIndex((item) => item.id === message.id);
  if (existingIndex >= 0) {
    messages.value.splice(existingIndex, 1, message);
    return;
  }
  messages.value.push(message);
}

function removeMessage(messageId) {
  messages.value = messages.value.filter((message) => message.id !== messageId);
}

function hasAgentReplyAfter(sequenceNo) {
  return messages.value.some(
    (message) =>
      message.message_type === "AGENT_MESSAGE" &&
      (message.sequence_no || 0) > sequenceNo,
  );
}

function delay(ms) {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

async function refreshUntilAgentReply(afterSequenceNo) {
  await refreshWorkspace();
  if (hasAgentReplyAfter(afterSequenceNo)) return;
  for (let attempt = 0; attempt < props.agentReplyPollAttempts; attempt += 1) {
    await delay(props.agentReplyPollDelayMs);
    await refreshWorkspace();
    if (hasAgentReplyAfter(afterSequenceNo)) return;
  }
}

async function postMessage(command) {
  error.value = "";
  agentState.value = "THINKING";
  const pendingId = `LOCAL_PENDING_${Date.now()}`;
  const pendingMessage = {
    id: pendingId,
    case_id: caseId.value,
    sequence_no: nextLocalSequence(),
    sender_role: role.value,
    message_type: command.message_type,
    message_text: command.text,
    attachment_refs: command.attachment_refs || [],
    pending: true,
  };
  messages.value.push(pendingMessage);
  try {
    const saved = props.messageAction
      ? await props.messageAction(command)
      : await roomApi.postMessage(
          effectiveActor.value,
          caseId.value,
          "EVIDENCE",
          command,
        );
    if (saved) {
      removeMessage(pendingId);
      upsertMessage(saved);
    }
    if (!props.messageAction) {
      await refreshUntilAgentReply(saved?.sequence_no || pendingMessage.sequence_no);
    }
    agentState.value = "SPEAKING";
  } catch (failure) {
    removeMessage(pendingId);
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

function startEventStream() {
  if (eventStreamActive) {
    eventAbortController.abort();
    eventAbortController = new AbortController();
  }
  eventStreamActive = true;
  const streamer = props.eventStreamer || streamRoomEvents;
  const generation = workspaceGeneration;
  const actorSnapshot = { ...effectiveActor.value };
  const caseSnapshot = caseId.value;
  void streamer({
    actor: actorSnapshot,
    caseId: caseSnapshot,
    roomType: "EVIDENCE",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: () => refreshWorkspace({ generation }),
    applyEvent: async (event) => {
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      if (event.event === "HEARING_OPENED") {
        completion.value = {
          ...(completion.value || {}),
          sealed: true,
          next_room: "HEARING",
        };
        agentState.value = "HANDOFF";
      }
    },
  });
}

async function uploadFiles(event) {
  const files = [...(event.target.files || [])];
  if (!files.length) return;
  uploading.value = true;
  error.value = "";
  agentState.value = "THINKING";
  try {
    for (const file of files) {
      await evidenceApi.upload(effectiveActor.value, caseId.value, {
        file,
        evidenceType: file.type.startsWith("video/")
          ? "VIDEO"
          : "OTHER",
        sourceType: evidenceSourceType.value,
        visibility: "PARTIES",
      });
    }
    await refreshWorkspace();
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    uploading.value = false;
    if (fileInput.value) fileInput.value.value = "";
  }
}

async function completeEvidence() {
  completing.value = true;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const result = props.completeAction
      ? await props.completeAction()
      : await evidenceApi.complete(effectiveActor.value, caseId.value);
    completion.value = mergeCompletionResult(completion.value, result);
    agentState.value = completion.value.sealed ? "HANDOFF" : "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    completing.value = false;
  }
}

function mergeCompletionResult(previous, result) {
  const sealed = result.sealed ?? result.all_parties_completed ?? false;
  const completedRole = result.completed_role || result.completedRole;
  const next = {
    ...(previous || {}),
    ...result,
    sealed,
  };
  if (completedRole === "USER") {
    next.user_completed = true;
  }
  if (completedRole === "MERCHANT") {
    next.merchant_completed = true;
  }
  if (sealed) {
    next.user_completed = true;
    next.merchant_completed = true;
  }
  return next;
}

function enterHearing() {
  return router.push(`/disputes/${caseId.value}/hearing`);
}

onMounted(async () => {
  await load();
  if (
    props.eventStreamer ||
    props.initialCatalog === null ||
    props.initialCompletion === null
  ) {
    startEventStream();
  }
});
watch(role, async (nextRole, previousRole) => {
  if (!previousRole || nextRole === previousRole) return;
  workspaceGeneration += 1;
  const generation = workspaceGeneration;
  error.value = "";
  agentState.value = "THINKING";
  catalog.value = null;
  completion.value = null;
  messages.value = [];
  if (eventStreamActive) {
    startEventStream();
  }
  try {
    await refreshWorkspace({ generation });
    agentState.value = "LISTENING";
  } catch (failure) {
    if (generation !== workspaceGeneration) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
});
onBeforeUnmount(() => eventAbortController.abort());
</script>

<template>
  <RoomShell
    eyebrow="EVIDENCE GARDEN"
    title="证据书记官室"
    :case-id="caseId"
    :connection-state="connectionState"
  >
    <template #clock>
      <div data-evidence-countdown>
        <PhaseCountdown
          label="举证窗口"
          :deadline-at="effectiveDeadline"
          :server-now="effectiveServerNow"
        />
      </div>
    </template>

    <template #agent>
      <DigitalHuman
        :state="agentState"
        name="小册"
        role="证据书记官"
        :message="
          isMerchant
            ? '商家证据方你好。我会检查材料的来源、完整性和可采性，并为双方生成脱敏共享目录。'
            : '用户证据方你好。图片、视频、文档都可以交给我；原件归你保管，共享目录只展示案件所需内容。'
        "
      />
    </template>

    <div class="evidence-room">
      <section class="evidence-uploader">
        <div class="evidence-uploader__illustration" aria-hidden="true">
          <span>📎</span><span>🖼️</span><span>🎞️</span>
        </div>
        <div>
          <span class="evidence-kicker">MULTIMODAL DESK</span>
          <h2>把证据放进书记官的文件篮</h2>
          <p>支持图片、视频与文档。上传后将自动查重、验真、脱敏并进入案件证据库。</p>
        </div>
        <label class="evidence-uploader__button">
          {{ uploading ? "正在核验材料…" : "选择证据材料" }}
          <input
            ref="fileInput"
            type="file"
            multiple
            :disabled="uploading"
            @change="uploadFiles"
          />
        </label>
      </section>

      <section class="evidence-dialogue">
        <div>
          <span class="evidence-kicker">CLERK CONVERSATION</span>
          <h2>和书记官核对证据</h2>
          <p>说明证据来源、形成时间与想证明的事实；每次陈述都会成为不可变房间记录。</p>
        </div>
        <ConversationStream
          :messages="messages"
          :disabled="uploading || completing"
          agent-label="证据书记官"
          placeholder="告诉书记官这份证据从哪里来、能证明什么…"
          @submit="postMessage"
        />
      </section>

      <div class="evidence-libraries">
        <section class="evidence-library evidence-library--private" data-evidence-private>
          <header>
            <div>
              <span class="evidence-kicker">MY ORIGINALS</span>
              <h2>我的原件匣</h2>
            </div>
            <span class="privacy-seal">仅当前一方可见</span>
          </header>
          <p v-if="!privateItems.length" class="evidence-empty">还没有仅自己可见的原件。</p>
          <article
            v-for="item in privateItems"
            :key="item.evidence_id"
            class="evidence-card"
          >
            <div class="evidence-card__icon">🔐</div>
            <div>
              <strong>{{ evidenceTypeLabels[item.evidence_type] || item.evidence_type }}</strong>
              <small>{{ item.evidence_id }}</small>
            </div>
            <span
              class="verification-pill"
              :data-verification="item.verification_status || 'PENDING'"
            >
              {{ statusLabels[item.verification_status] || item.verification_status || "待核验" }}
            </span>
          </article>
        </section>

        <section class="evidence-library evidence-library--shared" data-evidence-shared>
          <header>
            <div>
              <span class="evidence-kicker">SHARED DOSSIER</span>
              <h2>双方共享证据墙</h2>
            </div>
            <span class="shared-seal">已按权限脱敏</span>
          </header>
          <p v-if="!sharedItems.length" class="evidence-empty">共享目录仍在等待材料。</p>
          <div class="evidence-grid">
            <article
              v-for="item in sharedItems"
              :key="item.evidence_id"
              class="evidence-card"
            >
              <div class="evidence-card__icon">
                {{ item.submitted_by_role === "MERCHANT" ? "🏪" : "🧑" }}
              </div>
              <div>
                <strong>{{ evidenceTypeLabels[item.evidence_type] || item.evidence_type }}</strong>
                <small>{{ item.evidence_id }}</small>
                <em v-if="item.redacted">已脱敏副本</em>
              </div>
              <span
                class="verification-pill"
                :data-verification="item.verification_status || 'PENDING'"
              >
                {{ statusLabels[item.verification_status] || item.verification_status || "待核验" }}
              </span>
            </article>
          </div>
        </section>
      </div>

      <footer class="evidence-footer">
        <div>
          <strong>{{ isMerchant ? "商家证据方" : "用户证据方" }}</strong>
          <span>{{ completionStatusMessage }}</span>
        </div>
        <button
          v-if="completion?.sealed"
          type="button"
          data-enter-hearing
          @click="enterHearing"
        >
          进入小法庭
        </button>
        <div
          v-else-if="currentPartyCompleted"
          class="evidence-completed"
          data-evidence-completed
        >
          <strong>已封入证据袋</strong>
          <span>等待{{ waitingCounterpartyLabel }}或举证时效结束</span>
        </div>
        <button
          v-else
          type="button"
          data-complete-evidence
          :disabled="completing"
          @click="completeEvidence"
        >
          {{ completing ? "正在封入证据袋…" : "我方举证完成" }}
        </button>
      </footer>
      <p v-if="error" class="evidence-error" role="alert">{{ error }}</p>
    </div>
  </RoomShell>
</template>

<style scoped>
.evidence-room { display: grid; gap: 18px; }
.evidence-dialogue {
  display: grid;
  grid-template-columns: minmax(210px, .55fr) minmax(0, 1.45fr);
  gap: 18px;
  padding: 19px;
  background: #ffffffcf;
  border: 1px solid #e0e8f2;
  border-radius: 25px;
}
.evidence-dialogue h2 { margin: 5px 0; color: #34445d; }
.evidence-dialogue p { margin: 0; color: #748196; line-height: 1.6; }
.evidence-uploader {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 18px;
  align-items: center;
  padding: 20px 22px;
  background: linear-gradient(135deg, #fff9df, #edfbf4 52%, #edf6ff);
  border: 1px solid #dce9e4;
  border-radius: 28px;
}
.evidence-uploader__illustration { display: flex; gap: 4px; font-size: 26px; }
.evidence-uploader h2, .evidence-library h2 { margin: 4px 0; color: #31405a; }
.evidence-uploader p { margin: 0; color: #6f7c90; }
.evidence-kicker { color: #6f84a6; font-size: 10px; font-weight: 900; letter-spacing: .16em; }
.evidence-uploader__button, .evidence-footer button {
  padding: 12px 16px;
  color: white;
  background: linear-gradient(135deg, #55b8df, #8585ef);
  border: 0;
  border-radius: 14px;
  cursor: pointer;
  font-weight: 800;
}
.evidence-uploader__button input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.evidence-libraries { display: grid; grid-template-columns: .8fr 1.2fr; gap: 16px; }
.evidence-library {
  min-width: 0;
  padding: 19px;
  background: #ffffffd9;
  border: 1px solid #e0e8f2;
  border-radius: 26px;
  box-shadow: 0 18px 45px #5b74910d;
}
.evidence-library--private { background: linear-gradient(160deg, #f5f0ff, #fff); }
.evidence-library--shared { background: linear-gradient(160deg, #eaf8ff, #fff); }
.evidence-library header { display: flex; justify-content: space-between; gap: 12px; }
.privacy-seal, .shared-seal {
  height: max-content;
  padding: 6px 9px;
  color: #725e98;
  background: #fff;
  border-radius: 999px;
  font-size: 11px;
}
.shared-seal { color: #2e7b72; }
.evidence-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.evidence-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 13px;
  margin-top: 10px;
  background: #fff;
  border: 1px solid #e4eaf2;
  border-radius: 17px;
}
.evidence-card__icon { display: grid; place-items: center; width: 38px; height: 38px; background: #f4f7fb; border-radius: 12px; }
.evidence-card div:nth-child(2) { display: grid; min-width: 0; gap: 3px; }
.evidence-card strong { overflow: hidden; color: #3b4960; text-overflow: ellipsis; }
.evidence-card small { overflow: hidden; color: #8a96a8; text-overflow: ellipsis; }
.evidence-card em { color: #647fb0; font-size: 10px; font-style: normal; }
.verification-pill { padding: 5px 7px; border-radius: 999px; font-size: 10px; white-space: nowrap; }
.verification-pill[data-verification="VERIFIED"] { color: #25704e; background: #dcf5e8; }
.verification-pill[data-verification="PENDING"] { color: #68778e; background: #eef3f8; }
.verification-pill[data-verification="PLAUSIBLE"] { color: #39708e; background: #e0f2ff; }
.verification-pill[data-verification="SUSPICIOUS"] { color: #9b671b; background: #fff0c9; }
.verification-pill[data-verification="REJECTED"] { color: #a34b55; background: #ffeaed; }
.verification-pill[data-verification="NEEDS_HUMAN_REVIEW"] { color: #6e5799; background: #eee7ff; }
.evidence-empty { color: #8b96a8; font-size: 13px; }
.evidence-footer {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  padding: 16px 20px;
  background: #fff;
  border: 1px dashed #cfdaea;
  border-radius: 20px;
}
.evidence-footer div { display: grid; gap: 4px; color: #40506a; }
.evidence-footer span { color: #7a879b; font-size: 12px; }
.evidence-footer button { background: linear-gradient(135deg, #ff8b70, #ef6e91); }
.evidence-footer button:disabled { cursor: default; opacity: .6; }
.evidence-completed {
  display: grid;
  min-width: 178px;
  gap: 4px;
  padding: 10px 14px;
  color: #357052;
  background: linear-gradient(135deg, #e2f7eb, #eef7ff);
  border: 1px solid #c5ead6;
  border-radius: 16px;
  text-align: center;
}
.evidence-completed strong { color: #2d6d4f; }
.evidence-completed span { color: #5c7a6b; }
.evidence-error { color: #a84552; }
@media (max-width: 980px) {
  .evidence-libraries { grid-template-columns: 1fr; }
  .evidence-uploader { grid-template-columns: auto 1fr; }
  .evidence-uploader__button { grid-column: 1 / -1; text-align: center; }
  .evidence-dialogue { grid-template-columns: 1fr; }
}
@media (max-width: 620px) {
  .evidence-grid { grid-template-columns: 1fr; }
  .evidence-footer { align-items: stretch; flex-direction: column; }
}
</style>
