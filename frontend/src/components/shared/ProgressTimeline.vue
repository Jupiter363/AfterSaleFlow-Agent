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
