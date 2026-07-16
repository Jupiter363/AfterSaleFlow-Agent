<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import {
  computed,
  nextTick,
  onMounted,
  ref,
  watch,
} from "vue";
import AgentStreamingMessage from "./AgentStreamingMessage.vue";
import AgentSpeakerLabel from "./AgentSpeakerLabel.vue";
import { displayRoomMessageText, roleLabel } from "../../utils/displayText";
import {
  durableMessagesOutsideActiveStreams,
  streamCardsForRun,
  visibleAgentStreams,
} from "../../stores/agentStream";

const props = defineProps({
  messages: { type: Array, default: () => [] },
  streamingRuns: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  agentLabel: { type: String, default: "" },
  composerVisible: { type: Boolean, default: true },
  disabledReason: {
    type: String,
    default: "切换为用户或商家身份后，可以继续与数字人对话。",
  },
  emptyText: {
    type: String,
    default: "对话还没有开始。数字人会先听你完整说明。",
  },
  placeholder: { type: String, default: "把你的情况告诉数字人…" },
  submitLabel: { type: String, default: "发送陈述" },
  composerHint: {
    type: String,
    default: "消息提交后成为不可变房间记录",
  },
});

const emit = defineEmits(["submit"]);
const text = ref("");
const messagesRail = ref(null);
const orderedMessages = computed(() =>
  [...props.messages].sort(
    (left, right) => (left.sequence_no ?? 0) - (right.sequence_no ?? 0),
  ),
);
const displayedMessages = computed(() =>
  durableMessagesOutsideActiveStreams(
    orderedMessages.value,
    props.streamingRuns,
  ),
);
const pendingStreamingRuns = computed(() =>
  visibleAgentStreams(props.streamingRuns, displayedMessages.value),
);
const agentSurfaceTone = computed(() => {
  const label = String(props.agentLabel || "").toLowerCase();
  const roles = [
    ...orderedMessages.value.map((message) => message.sender_role),
    ...props.streamingRuns.map((run) => run.senderRole),
  ].map((value) => String(value || "").toLowerCase());
  if (label.includes("证据") || roles.includes("evidence_clerk")) return "evidence-clerk";
  if (label.includes("陪审") || roles.includes("jury_panel")) return "jury-panel";
  return "default";
});
const PARTY_ROLES = new Set(["USER", "MERCHANT", "PLATFORM_REVIEWER"]);
const AGENT_ROLES = new Set([
  "CUSTOMER_SERVICE",
  "DISPUTE_INTAKE_OFFICER",
  "INTAKE_OFFICER",
  "EVIDENCE_CLERK",
  "JUDGE",
  "AI_JUDGE",
  "PRESIDING_JUDGE",
  "JURY",
  "AI_JURY",
  "JURY_PANEL",
  "REVIEW_COPILOT",
  "SYSTEM",
]);

// 业务位置：【Java 房间协作】submit：执行 当前阶段业务数据 对应的业务动作，并将结果交给 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
function submit() {
  const value = text.value.trim();
  if (!value || props.disabled) return;
  emit("submit", {
    message_type: "PARTY_TEXT",
    text: value,
    attachment_refs: [],
  });
  text.value = "";
}

// 业务位置：【Java 房间协作】messageLane：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
function messageLane(role) {
  if (PARTY_ROLES.has(role)) return "right";
  if (AGENT_ROLES.has(role)) return "left";
  return "left";
}

// 业务位置：【Java 房间协作】messageLaneClass：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
function messageLaneClass(role) {
  return messageLane(role) === "right"
    ? "conversation-stream__message--party"
    : "conversation-stream__message--agent";
}

// 业务位置：【Java 房间协作】displaySenderLabel：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
function isAgentSpeakerMessage(message) {
  return (
    message.message_type === "AGENT_MESSAGE" ||
    (AGENT_ROLES.has(message.sender_role) && message.sender_role !== "SYSTEM")
  );
}

function agentIdentityForMessage(message) {
  if (props.agentLabel && message.message_type === "AGENT_MESSAGE") {
    return props.agentLabel;
  }
  return "";
}

function displaySenderLabel(message) {
  return roleLabel(message.sender_role);
}

// 业务位置：【Java 房间协作】scrollToLatestMessage：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
async function scrollToLatestMessage() {
  await nextTick();
  const rail = messagesRail.value;
  if (!rail) return;
  rail.scrollTop = rail.scrollHeight;
}

watch([displayedMessages, pendingStreamingRuns], () => {
  void scrollToLatestMessage();
}, { deep: true });

onMounted(() => {
  void scrollToLatestMessage();
});
</script>

<template>
  <section
    class="conversation-stream"
    :class="`conversation-stream--${agentSurfaceTone}`"
    aria-label="房间对话"
  >
    <div
      ref="messagesRail"
      class="conversation-stream__messages"
      aria-live="polite"
    >
      <article
        v-for="(message, visibleIndex) in displayedMessages"
        :key="message.id"
        class="conversation-stream__message"
        :class="[
          `conversation-stream__message--${message.sender_role?.toLowerCase()}`,
          messageLaneClass(message.sender_role),
        ]"
        :data-message-lane="messageLane(message.sender_role)"
        data-room-message
      >
        <header>
          <strong>
            <AgentSpeakerLabel
              v-if="isAgentSpeakerMessage(message)"
              :role="message.sender_role"
              :identity="agentIdentityForMessage(message)"
            />
            <template v-else>{{ displaySenderLabel(message) }}</template>
          </strong>
          <small>#{{ visibleIndex + 1 }}</small>
        </header>
        <p>{{ displayRoomMessageText(message.message_text) }}</p>
      </article>
      <template v-for="run in pendingStreamingRuns" :key="run.runId">
        <AgentStreamingMessage
          v-for="card in streamCardsForRun(run)"
          :key="`${run.runId}:${card.key}`"
          :run="run"
          :card="card"
          :label="run.agentLabel || agentLabel"
        />
      </template>
      <div
        v-if="!displayedMessages.length && !pendingStreamingRuns.length"
        class="conversation-stream__empty"
      >
        {{ emptyText }}
      </div>
    </div>

    <form
      v-if="composerVisible"
      class="conversation-stream__composer"
      data-send-message
      @submit.prevent="submit"
    >
      <textarea
        v-model="text"
        :disabled="disabled"
        :placeholder="placeholder"
        rows="3"
        aria-label="房间消息"
      />
      <div>
        <span>{{ composerHint }}</span>
        <button type="submit" :disabled="disabled || !text.trim()">
          {{ submitLabel }}
        </button>
      </div>
    </form>
    <p v-else class="conversation-stream__readonly" data-room-readonly>
      {{ disabledReason }}
    </p>
  </section>
</template>

<style scoped>
.conversation-stream {
  --conversation-message-font-size: 13px;
  --conversation-message-body-font-size: 12.5px;
  --conversation-message-meta-font-size: 10.5px;
  --conversation-agent-message-color: #334159;
  --conversation-agent-message-background: #fffaf1;
  --conversation-agent-message-border: #e7decc;
  --conversation-agent-message-title: #5e5143;
  --conversation-agent-message-meta: #8a7c68;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 14px;
  width: 100%;
  min-height: 0;
  overflow: hidden;
}
.conversation-stream--evidence-clerk {
  --conversation-agent-message-color: #27475d;
  --conversation-agent-message-background: #eef8fb;
  --conversation-agent-message-border: #cee4ea;
  --conversation-agent-message-title: #31596c;
  --conversation-agent-message-meta: #668a99;
}
.conversation-stream--jury-panel {
  --conversation-agent-message-color: #494263;
  --conversation-agent-message-background: #f3efff;
  --conversation-agent-message-border: #ddd2f1;
  --conversation-agent-message-title: #5a4e78;
  --conversation-agent-message-meta: #80749a;
}
.conversation-stream__messages {
  display: grid;
  align-content: start;
  gap: 10px;
  min-height: 0;
  padding: 8px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.conversation-stream__message {
  height: auto;
  min-width: 0;
  max-width: 82%;
  max-height: none;
  padding: 13px 15px;
  font-size: var(--conversation-message-font-size);
  background: #fff;
  border: 1px solid #e1e8f4;
  border-radius: 18px 18px 18px 6px;
}
.conversation-stream__message--agent {
  justify-self: start;
  color: var(--conversation-agent-message-color);
  background: var(--conversation-agent-message-background);
  border-color: var(--conversation-agent-message-border);
  border-radius: 18px 18px 18px 6px;
  box-shadow: none;
}
.conversation-stream__message--party {
  justify-self: end;
  border-radius: 18px 18px 6px;
}
.conversation-stream__message--user { background: #eaf6ff; }
.conversation-stream__message--merchant { background: #effaef; }
.conversation-stream__message--customer_service,
.conversation-stream__message--dispute_intake_officer,
.conversation-stream__message--intake_officer { background: var(--conversation-agent-message-background); }
.conversation-stream__message--platform_reviewer { background: #f4efff; }
.conversation-stream__message header { display: flex; justify-content: space-between; gap: 12px; }
.conversation-stream__message small { color: #8a96aa; font-size: var(--conversation-message-meta-font-size); }
.conversation-stream__message--agent header strong { color: var(--conversation-agent-message-title); }
.conversation-stream__message--agent header small { color: var(--conversation-agent-message-meta); }
.conversation-stream__message p {
  margin: 7px 0 0;
  font-size: var(--conversation-message-body-font-size);
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.conversation-stream__empty {
  padding: 28px;
  text-align: center;
  color: #78849a;
  background: #ffffff99;
  border: 1px dashed #d9e1ed;
  border-radius: 18px;
}
.conversation-stream__composer {
  display: grid;
  grid-template-rows: 72px minmax(44px, 1fr);
  gap: 2px;
  box-sizing: border-box;
  width: 100%;
  height: 132px;
  padding: 6px 10px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #dde6f2;
  border-radius: 20px;
  box-shadow: 0 12px 34px #536b9412;
}
.conversation-stream__composer textarea {
  box-sizing: border-box;
  width: 100%;
  height: 72px;
  min-height: 72px;
  max-height: 72px;
  padding: 10px;
  resize: none;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.5;
  color: #25344c;
  background: #f8fbff;
  border: 0;
  border-radius: 13px;
  outline: none;
}
.conversation-stream__composer > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  min-width: 0;
}
.conversation-stream__composer span {
  min-width: 0;
  color: #8994a6;
  font-size: 11px;
  line-height: 1.35;
}
.conversation-stream__composer button {
  flex: 0 0 auto;
  box-sizing: border-box;
  min-height: 44px;
  padding: 7px 12px;
  white-space: nowrap;
  color: white;
  background: #4b9fe1;
  border: 0;
  border-radius: 13px;
  cursor: pointer;
}
.conversation-stream__composer button:disabled { opacity: .45; cursor: not-allowed; }
.conversation-stream__readonly {
  margin: 0;
  padding: 14px 16px;
  color: #6b7890;
  background: #f7fbff;
  border: 1px dashed #cddbec;
  border-radius: 18px;
}

@media (max-width: 620px) {
  .conversation-stream__message {
    max-width: 94%;
  }
}
</style>
