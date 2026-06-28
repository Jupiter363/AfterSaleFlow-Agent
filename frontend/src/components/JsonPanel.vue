<script setup>
defineProps({
  title: { type: String, required: true },
  value: { type: [Object, Array, String, Number, Boolean], default: null },
  empty: { type: String, default: "暂无数据" },
});

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
