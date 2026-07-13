<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
defineProps({
  message: { type: String, default: "" },
  title: { type: String, default: "数字人生成失败" },
});

defineEmits(["dismiss"]);
</script>

<template>
  <div
    v-if="message"
    class="agent-stream-error"
    data-agent-stream-error-dialog
    role="alertdialog"
    aria-modal="true"
    aria-labelledby="agent-stream-error-title"
  >
    <section class="agent-stream-error__card">
      <span aria-hidden="true">!</span>
      <h3 id="agent-stream-error-title">{{ title }}</h3>
      <p>{{ message }}</p>
      <button type="button" data-dismiss-agent-stream-error @click="$emit('dismiss')">
        我知道了
      </button>
    </section>
  </div>
</template>

<style scoped>
.agent-stream-error {
  position: fixed;
  inset: 0;
  z-index: 1600;
  display: grid;
  place-items: center;
  padding: 24px;
  background: #16203355;
  backdrop-filter: blur(5px);
}
.agent-stream-error__card {
  display: grid;
  justify-items: center;
  width: min(430px, 100%);
  padding: 28px;
  text-align: center;
  background: #fff;
  border: 1px solid #f1c8bf;
  border-radius: 22px;
  box-shadow: 0 22px 70px #26334f2e;
}
.agent-stream-error__card > span {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  color: #fff;
  background: #dc6a5d;
  border-radius: 50%;
  font-size: 22px;
  font-weight: 900;
}
.agent-stream-error h3 {
  margin: 14px 0 7px;
  color: #493f47;
  font-size: 19px;
}
.agent-stream-error p {
  margin: 0;
  color: #746a72;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.agent-stream-error button {
  margin-top: 20px;
  padding: 10px 24px;
  color: #fff;
  background: #b95f58;
  border: 0;
  border-radius: 12px;
  font-weight: 800;
  cursor: pointer;
}
</style>
