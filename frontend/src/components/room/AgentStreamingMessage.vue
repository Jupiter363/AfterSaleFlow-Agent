<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed } from "vue";
import { agentSpeakerPresentation } from "../../utils/agentSpeakerPresentation";

const props = defineProps({
  run: { type: Object, required: true },
  card: { type: Object, default: null },
  label: { type: String, default: "" },
  appearance: {
    type: String,
    default: "conversation",
    validator: (value) => ["conversation", "court"].includes(value),
  },
});

const fallbackPresentation = computed(() =>
  agentSpeakerPresentation(
    props.card?.senderRole || props.run.senderRole,
    props.label || props.run.agentLabel || "数字人",
  ),
);
const displayIdentity = computed(() =>
  props.card?.identity || fallbackPresentation.value.identity,
);
const displayName = computed(() =>
  props.card?.name || fallbackPresentation.value.name,
);
const displayContent = computed(() =>
  props.card ? props.card.content || "" : props.run.content || "",
);
const cardIsActive = computed(() =>
  !props.card || props.run.activeCardKey === props.card.key,
);
const statusLabel = computed(() => {
  if (props.run.status === "RECONNECTING") return "正在恢复连接";
  if (props.run.status === "FINALIZING") return "正在整理正式记录";
  if (!cardIsActive.value && displayContent.value) return "本段生成完成";
  return "正在生成";
});
</script>

<template>
  <article
    class="agent-streaming-message"
    :class="[
      `agent-streaming-message--${appearance}`,
      `agent-streaming-message--${String(card?.senderRole || run.senderRole || 'agent').toLowerCase()}`,
    ]"
    :data-agent-run-id="run.runId"
    :data-agent-stream-card="card?.key || 'default'"
    :data-agent-stream-status="run.status"
    data-agent-streaming-message
    aria-live="polite"
    :aria-busy="cardIsActive"
  >
    <header>
      <strong>{{ displayIdentity }} · {{ displayName }} 正常发言：</strong>
      <small>{{ statusLabel }}</small>
    </header>
    <p>
      <span v-if="displayContent">{{ displayContent }}</span>
      <span v-else class="agent-streaming-message__waiting">正在组织回复</span>
      <i v-if="cardIsActive" class="agent-streaming-message__cursor" aria-hidden="true" />
    </p>
  </article>
</template>

<style scoped>
.agent-streaming-message {
  box-sizing: border-box;
  min-width: 0;
  color: var(--conversation-agent-message-color, #334159);
  background: var(--conversation-agent-message-background, #fffaf1);
  border: 1px solid var(--conversation-agent-message-border, #e7decc);
  box-shadow: none;
}
.agent-streaming-message--conversation {
  justify-self: start;
  width: fit-content;
  max-width: 82%;
  padding: 13px 15px;
  border-radius: 18px 18px 18px 6px;
}
.agent-streaming-message--court {
  width: min(82%, 760px);
  padding: 16px 18px;
  border-color: #ead5b6;
  border-radius: 18px 18px 18px 7px;
  background: linear-gradient(145deg, #fffdf7, #fff8e9);
}
.agent-streaming-message--jury_panel {
  --conversation-agent-message-color: #494263;
  --conversation-agent-message-background: #f3efff;
  --conversation-agent-message-border: #ddd2f1;
  --conversation-agent-message-title: #5a4e78;
  --conversation-agent-message-meta: #80749a;
}
.agent-streaming-message--evidence_clerk {
  --conversation-agent-message-color: #27475d;
  --conversation-agent-message-background: #eef8fb;
  --conversation-agent-message-border: #cee4ea;
  --conversation-agent-message-title: #31596c;
  --conversation-agent-message-meta: #668a99;
}
.agent-streaming-message header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.agent-streaming-message header strong {
  color: var(--conversation-agent-message-title, #5e5143);
  font-size: 13px;
}
.agent-streaming-message header small {
  color: var(--conversation-agent-message-meta, #8a7c68);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: .02em;
}
.agent-streaming-message p {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.agent-streaming-message__waiting {
  color: #8d8579;
}
.agent-streaming-message__cursor {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 3px;
  vertical-align: -.13em;
  background: #d18845;
  border-radius: 1px;
  animation: agent-stream-cursor .8s steps(1, end) infinite;
}
@keyframes agent-stream-cursor {
  0%, 45% { opacity: 1; }
  46%, 100% { opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .agent-streaming-message__cursor { animation: none; }
}
</style>
