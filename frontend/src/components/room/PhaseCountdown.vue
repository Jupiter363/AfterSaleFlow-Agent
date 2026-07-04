<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";

const props = defineProps({
  deadlineAt: { type: String, required: true },
  serverNow: { type: String, required: true },
  label: { type: String, default: "剩余时间" },
});

const localNow = ref(Date.now());
const anchorLocal = ref(Date.now());
const anchorServer = ref(Date.parse(props.serverNow));

watch(
  () => props.serverNow,
  (value) => {
    anchorServer.value = Date.parse(value);
    anchorLocal.value = Date.now();
    localNow.value = Date.now();
  },
  { immediate: true },
);

const remainingMs = computed(() => {
  const estimatedServerNow =
    anchorServer.value + (localNow.value - anchorLocal.value);
  return Math.max(0, Date.parse(props.deadlineAt) - estimatedServerNow);
});

const formatted = computed(() => {
  const totalSeconds = Math.floor(remainingMs.value / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return [hours, minutes, seconds]
    .map((value) => String(value).padStart(2, "0"))
    .join(":");
});

const timer = setInterval(() => {
  localNow.value = Date.now();
}, 1000);

onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <section
    class="phase-countdown"
    :class="{ 'phase-countdown--zero': remainingMs === 0 }"
    :data-awaiting-server="remainingMs === 0 ? 'true' : 'false'"
    aria-live="polite"
  >
    <span>{{ label }}</span>
    <strong>{{ formatted }}</strong>
    <small v-if="remainingMs === 0">等待服务端确认下一阶段</small>
  </section>
</template>
