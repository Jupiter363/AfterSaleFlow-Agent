<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed } from "vue";

const props = defineProps({
  eyebrow: { type: String, default: "DISPUTE ROOM" },
  title: { type: String, required: true },
  caseId: { type: String, required: true },
  subtitle: { type: String, default: "" },
  subtitleDescription: { type: String, default: "" },
  showCaseId: { type: Boolean, default: true },
  showConnection: { type: Boolean, default: true },
  showBoundary: { type: Boolean, default: true },
  connectionState: {
    type: String,
    default: "connected",
    validator: (value) =>
      ["connected", "reconnecting", "offline"].includes(value),
  },
});

const connectionLabels = {
  connected: "实时连接",
  reconnecting: "正在续传",
  offline: "暂时离线",
};

const shortCaseId = computed(() =>
  props.caseId.length > 16 ? `${props.caseId.slice(0, 9)}…` : props.caseId,
);
</script>

<template>
  <article class="room-shell" :data-connection="connectionState">
    <header class="room-shell__header">
      <div>
        <span class="room-shell__eyebrow">{{ eyebrow }}</span>
        <h1>{{ title }}</h1>
        <p v-if="subtitle || subtitleDescription" class="room-shell__lead">
          <span v-if="subtitle" class="room-shell__context">
            {{ subtitle }} <i aria-hidden="true">✦</i>
          </span>
          <span v-if="subtitleDescription">{{ subtitleDescription }}</span>
        </p>
        <p v-else-if="showCaseId" data-room-case-id :title="caseId">{{ shortCaseId }}</p>
      </div>
      <div v-if="showConnection || $slots.clock" class="room-shell__status">
        <span v-if="showConnection">{{ connectionLabels[connectionState] }}</span>
        <slot name="clock" />
      </div>
    </header>

    <div v-if="showBoundary" class="room-shell__boundary">
      <span aria-hidden="true">◇</span>
      AI 只提供非最终建议，最终裁决由平台审核员确认
    </div>

    <section class="room-shell__agent">
      <slot name="agent" />
    </section>

    <section class="room-shell__workspace">
      <slot />
    </section>
  </article>
</template>

<style scoped>
.room-shell {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  min-height: calc(100vh - 130px);
}
.room-shell__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
}
.room-shell__eyebrow {
  color: #7185a8;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .18em;
}
.room-shell__header h1 {
  margin: 7px 0 8px;
  min-width: 0;
  color: #263754;
  font-size: clamp(32px, 5vw, 56px);
  line-height: 1.05;
  overflow-wrap: anywhere;
}
.room-shell__header p { margin: 0; color: #7c899e; }
.room-shell__lead {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  align-items: center;
  min-width: 0;
  color: #738097;
  overflow-wrap: anywhere;
}
.room-shell__context {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: #586b85;
  font-size: 12px;
  font-weight: 900;
  line-height: 1;
}
.room-shell__context i {
  color: #ff9a76;
  font-size: 9px;
  font-style: normal;
}
.room-shell__status { display: flex; align-items: center; gap: 12px; }
.room-shell__status > span {
  padding: 7px 11px;
  color: #24734f;
  background: #e7f8ef;
  border-radius: 999px;
  font-size: 12px;
}
.room-shell[data-connection="reconnecting"] .room-shell__status > span {
  color: #93651c;
  background: #fff4d7;
}
.room-shell[data-connection="offline"] .room-shell__status > span {
  color: #9a4c57;
  background: #ffeced;
}
.room-shell__boundary {
  display: flex;
  gap: 8px;
  align-items: center;
  width: max-content;
  max-width: 100%;
  padding: 8px 12px;
  color: #695e83;
  background: #f1edff;
  border-radius: 12px;
  font-size: 12px;
}
.room-shell__agent {
  min-width: 0;
}
.room-shell__workspace {
  container: room-workspace / inline-size;
  min-width: 0;
}
@media (max-width: 760px) {
  .room-shell__header { flex-direction: column; }
  .room-shell__status { width: 100%; justify-content: space-between; }
}
</style>
