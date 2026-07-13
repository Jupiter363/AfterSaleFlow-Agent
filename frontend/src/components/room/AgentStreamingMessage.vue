<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed } from "vue";

const props = defineProps({
  run: { type: Object, required: true },
  label: { type: String, default: "" },
  appearance: {
    type: String,
    default: "conversation",
    validator: (value) => ["conversation", "court"].includes(value),
  },
});

const displayLabel = computed(() => props.label || props.run.agentLabel || "数字人");
const statusLabel = computed(() => {
  if (props.run.status === "RECONNECTING") return "正在恢复连接";
  if (props.run.status === "FINALIZING") return "正在整理正式记录";
  return "正在生成";
});
</script>

<template>
  <article
    class="agent-streaming-message"
    :class="`agent-streaming-message--${appearance}`"
    :data-agent-run-id="run.runId"
    :data-agent-stream-status="run.status"
    data-agent-streaming-message
    aria-live="polite"
    aria-busy="true"
  >
    <header>
      <strong>{{ displayLabel }}</strong>
      <small>{{ statusLabel }}</small>
    </header>
    <p>
      <span v-if="run.content">{{ run.content }}</span>
      <span v-else class="agent-streaming-message__waiting">正在组织回复</span>
      <i class="agent-streaming-message__cursor" aria-hidden="true" />
    </p>
  </article>
</template>

<style scoped>
.agent-streaming-message {
  box-sizing: border-box;
  min-width: 0;
  color: #334159;
  background: #fffaf1;
  border: 1px solid #e7decc;
  box-shadow: 0 8px 26px #455d8610;
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
.agent-streaming-message header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.agent-streaming-message header strong {
  color: #5e5143;
  font-size: 13px;
}
.agent-streaming-message header small {
  color: #9a7c50;
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
