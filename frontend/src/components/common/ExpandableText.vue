<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps({
  text: { type: String, default: "" },
  label: { type: String, required: true },
  lines: { type: Number, default: 4 },
  expanded: { type: Boolean, default: false },
});

const emit = defineEmits(["open"]);
const content = ref(null);
const overflowing = ref(false);
let observer;

function measure() {
  const element = content.value;
  overflowing.value = Boolean(
    element && element.scrollHeight > element.clientHeight + 1,
  );
}

function open() {
  emit("open", { label: props.label, text: props.text });
}

watch(
  () => [props.text, props.lines],
  () => void nextTick(measure),
);
onMounted(() => {
  if (typeof ResizeObserver !== "undefined") {
    observer = new ResizeObserver(measure);
    if (content.value) observer.observe(content.value);
  }
  void nextTick(measure);
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div class="expandable-text" :style="{ '--expandable-lines': lines }">
    <p ref="content" class="expandable-text__content" data-expandable-content>
      {{ text }}
    </p>
    <button
      v-if="overflowing"
      type="button"
      data-expandable-trigger
      aria-haspopup="dialog"
      :aria-expanded="String(expanded)"
      @click="open"
    >
      查看全文
    </button>
  </div>
</template>

<style scoped>
.expandable-text {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 0;
  align-content: center;
  overflow: hidden;
}

.expandable-text__content {
  display: -webkit-box;
  min-width: 0;
  max-height: calc(1.55em * var(--expandable-lines));
  margin: 0;
  overflow: hidden;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: var(--expandable-lines);
}

.expandable-text button {
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 2px 0 2px 14px;
  color: #5f6fd8;
  background: linear-gradient(90deg, transparent, #fff 24%);
  border: 0;
  cursor: pointer;
  font-size: 11px;
  font-weight: 900;
}
</style>
