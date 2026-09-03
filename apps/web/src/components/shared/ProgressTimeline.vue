<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
defineProps({
  items: { type: Array, default: () => [] },
  activeId: { type: String, default: "" },
});
defineEmits(["select"]);
</script>

<template>
  <ol class="progress-timeline">
    <li
      v-for="(item, index) in items"
      :key="item.id"
      :class="{ active: item.id === activeId, complete: item.status === 'complete' }"
    >
      <button type="button" @click="$emit('select', item.id)">
        <span class="timeline-index">{{ String(index + 1).padStart(2, "0") }}</span>
        <span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span>
        <em>{{ item.statusLabel || item.status }}</em>
      </button>
    </li>
  </ol>
</template>
