<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

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

// 业务位置：【前端业务组件】measure：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
function measure() {
  const element = content.value;
  overflowing.value = Boolean(
    element && element.scrollHeight > element.clientHeight + 1,
  );
}

// 业务位置：【前端业务组件】open：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
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
  z-index: 1;
  padding: 2px 0 2px 14px;
  color: #5f6fd8;
  background: linear-gradient(90deg, transparent, #fff 24%);
  border: 0;
  cursor: pointer;
  font-size: 11px;
  font-weight: 900;
}
</style>
