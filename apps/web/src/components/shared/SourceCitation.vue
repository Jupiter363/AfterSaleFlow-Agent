<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
defineProps({
  sourceId: { type: String, required: true },
  label: { type: String, default: "查看来源" },
  kind: { type: String, default: "证据" },
});
defineEmits(["open"]);
</script>

<template>
  <button class="source-citation" type="button" @click="$emit('open', sourceId)">
    <span>{{ kind }}</span>
    <strong>{{ label }}</strong>
    <code>{{ sourceId }}</code>
  </button>
</template>
