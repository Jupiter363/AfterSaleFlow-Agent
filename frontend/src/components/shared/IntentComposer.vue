<script setup>
import { ref } from "vue";

const props = defineProps({
  title: { type: String, default: "先说发生了什么" },
  placeholder: { type: String, default: "用自然语言描述争议事实、你的诉求和已有证据…" },
  busy: { type: Boolean, default: false },
});
const emit = defineEmits(["submit"]);
const intent = ref("");

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
