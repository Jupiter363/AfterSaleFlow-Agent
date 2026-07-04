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
