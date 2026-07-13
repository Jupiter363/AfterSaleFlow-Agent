<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { ref } from "vue";

const props = defineProps({
  title: { type: String, default: "先说发生了什么" },
  placeholder: { type: String, default: "用自然语言描述争议事实、你的诉求和已有证据…" },
  busy: { type: Boolean, default: false },
});
const emit = defineEmits(["submit"]);
const intent = ref("");

// 业务位置：【前端业务组件】submit：执行 当前阶段业务数据 对应的业务动作，并将结果交给 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
function submit() {
  const value = intent.value.trim();
  if (!value || props.busy) return;
  emit("submit", value);
}
</script>

<template>
  <section class="intent-composer">
    <div>
      <span class="live-dot" />
      <strong>{{ title }}</strong>
      <small>AI 仅做结构化整理，不直接裁决</small>
    </div>
    <textarea v-model="intent" :placeholder="placeholder" rows="4" @keydown.ctrl.enter="submit" />
    <footer>
      <span>Ctrl + Enter 提交 · 全程保留来源与版本</span>
      <button class="primary-button" type="button" :disabled="busy || !intent.trim()" @click="submit">
        {{ busy ? "正在受理…" : "开始受理" }}
      </button>
    </footer>
  </section>
</template>
