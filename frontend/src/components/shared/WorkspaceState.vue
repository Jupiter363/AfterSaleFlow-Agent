<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
defineProps({
  resource: { type: Object, required: true },
  emptyTitle: { type: String, default: "暂无数据" },
  emptyText: { type: String, default: "当前阶段还没有生成可展示的内容。" },
});
defineEmits(["retry"]);
</script>

<template>
  <div v-if="resource.status === 'loading'" class="state-card state-loading">
    <span class="state-spinner" />
    <div><strong>正在同步工作区</strong><p>正在读取已授权的最新版本。</p></div>
  </div>
  <div v-else-if="resource.status === 'error'" class="state-card state-error">
    <span class="state-icon">!</span>
    <div>
      <strong>工作区暂时不可用</strong>
      <p>{{ resource.error?.message || "服务连接失败，请稍后重试。" }}</p>
      <button class="text-button" type="button" @click="$emit('retry')">重新加载</button>
    </div>
  </div>
  <div v-else-if="resource.status === 'degraded'" class="state-card state-warning">
    <span class="state-icon">~</span>
    <div><strong>降级展示</strong><p>部分服务暂不可用，已保留可验证信息。</p></div>
  </div>
  <div v-else-if="resource.status === 'empty'" class="state-card">
    <span class="state-icon">○</span>
    <div><strong>{{ emptyTitle }}</strong><p>{{ emptyText }}</p></div>
  </div>
  <slot v-else />
</template>
