<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
defineProps({
  title: { type: String, required: true },
  value: { type: [Object, Array, String, Number, Boolean], default: null },
  empty: { type: String, default: "暂无数据" },
});

// 业务位置：【前端业务组件】display：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
function display(value) {
  if (value === null || value === undefined || value === "") return "";
  return typeof value === "string" ? value : JSON.stringify(value, null, 2);
}
</script>

<template>
  <article class="data-panel">
    <h3>{{ title }}</h3>
    <pre v-if="display(value)">{{ display(value) }}</pre>
    <el-empty v-else :image-size="48" :description="empty" />
  </article>
</template>
