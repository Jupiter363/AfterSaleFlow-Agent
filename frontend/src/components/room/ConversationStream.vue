<script setup>
import { computed, ref } from "vue";
import { roleLabel } from "../../utils/displayText";

const props = defineProps({
  messages: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  composerVisible: { type: Boolean, default: true },
  disabledReason: {
    type: String,
    default: "切换为用户或商家身份后，可以继续与数字人对话。",
  },
  placeholder: { type: String, default: "把你的情况告诉数字人…" },
});

const emit = defineEmits(["submit"]);
const text = ref("");
const orderedMessages = computed(() =>
  [...props.messages].sort(
    (left, right) => (left.sequence_no ?? 0) - (right.sequence_no ?? 0),
  ),
);

function submit() {
  const value = text.value.trim();
  if (!value || props.disabled) return;
  emit("submit", {
    message_type: "PARTY_TEXT",
    text: value,
    attachment_refs: [],
  });
  text.value = "";
}

function displayMessageText(value) {
  if (!value) return "";
  const questionMarks = (value.match(/\?/g) || []).length;
  if (questionMarks >= 6 && questionMarks / value.length > 0.35) {
    return "历史消息编码异常，原始内容已按不可变记录留存。";
  }
  return value;
}
</script>

<template>
  <section class="conversation-stream" aria-label="房间对话">
    <div class="conversation-stream__messages" aria-live="polite">
      <article
        v-for="message in orderedMessages"
        :key="message.id"
        class="conversation-stream__message"
        :class="`conversation-stream__message--${message.sender_role?.toLowerCase()}`"
        data-room-message
      >
        <header>
          <strong>{{ roleLabel(message.sender_role) }}</strong>
          <small>#{{ message.sequence_no }}</small>
        </header>
        <p>{{ displayMessageText(message.message_text) }}</p>
      </article>
      <div v-if="!orderedMessages.length" class="conversation-stream__empty">
        对话还没有开始。数字人会先听你完整说明。
      </div>
    </div>

    <form
      v-if="composerVisible"
      class="conversation-stream__composer"
      data-send-message
      @submit.prevent="submit"
    >
      <textarea
        v-model="text"
        :disabled="disabled"
        :placeholder="placeholder"
        rows="3"
        aria-label="房间消息"
      />
      <div>
        <span>消息提交后成为不可变房间记录</span>
        <button type="submit" :disabled="disabled || !text.trim()">发送陈述</button>
      </div>
    </form>
    <p v-else class="conversation-stream__readonly" data-room-readonly>
      {{ disabledReason }}
    </p>
  </section>
</template>

<style scoped>
.conversation-stream {
  display: grid;
  grid-template-rows: minmax(360px, 1fr) auto;
  gap: 14px;
  width: 100%;
  min-height: 0;
}
.conversation-stream__messages {
  display: grid;
  align-content: start;
  gap: 10px;
  min-height: 360px;
  padding: 8px;
  overflow-y: auto;
}
.conversation-stream__message {
  max-width: 82%;
  padding: 13px 15px;
  background: #fff;
  border: 1px solid #e1e8f4;
  border-radius: 18px 18px 18px 6px;
}
.conversation-stream__message--user { justify-self: end; background: #eaf6ff; border-radius: 18px 18px 6px; }
.conversation-stream__message--merchant { background: #effaef; }
.conversation-stream__message--platform_reviewer { background: #f4efff; }
.conversation-stream__message header { display: flex; justify-content: space-between; gap: 12px; }
.conversation-stream__message small { color: #8a96aa; }
.conversation-stream__message p { margin: 7px 0 0; line-height: 1.6; }
.conversation-stream__empty {
  padding: 28px;
  text-align: center;
  color: #78849a;
  background: #ffffff99;
  border: 1px dashed #d9e1ed;
  border-radius: 18px;
}
.conversation-stream__composer {
  box-sizing: border-box;
  width: 100%;
  padding: 12px;
  background: #fff;
  border: 1px solid #dde6f2;
  border-radius: 20px;
  box-shadow: 0 12px 34px #536b9412;
}
.conversation-stream__composer textarea {
  box-sizing: border-box;
  width: 100%;
  padding: 10px;
  resize: vertical;
  color: #25344c;
  background: #f8fbff;
  border: 0;
  border-radius: 13px;
  outline: none;
}
.conversation-stream__composer > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-top: 8px;
}
.conversation-stream__composer span { color: #8994a6; font-size: 11px; }
.conversation-stream__composer button {
  padding: 10px 16px;
  color: white;
  background: #4b9fe1;
  border: 0;
  border-radius: 13px;
  cursor: pointer;
}
.conversation-stream__composer button:disabled { opacity: .45; cursor: not-allowed; }
.conversation-stream__readonly {
  margin: 0;
  padding: 14px 16px;
  color: #6b7890;
  background: #f7fbff;
  border: 1px dashed #cddbec;
  border-radius: 18px;
}
</style>
