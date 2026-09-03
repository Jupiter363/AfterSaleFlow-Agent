<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed, ref } from "vue";

const props = defineProps({
  notifications: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: "" },
  defaultOpen: { type: Boolean, default: false },
  deletingNotificationIds: { type: Array, default: () => [] },
});

const emit = defineEmits([
  "open-notification",
  "mark-read",
  "mark-all-read",
  "delete-notification",
]);
const open = ref(props.defaultOpen);
const unreadCount = computed(
  () => props.notifications.filter((item) => !item.read).length,
);

// 业务位置：【案件通知】select：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 生命周期事件和目标角色 正确进入 站内通知视图和已读/关闭状态。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
function select(item) {
  if (!item.read) emit("mark-read", item.id);
  emit("open-notification", item);
}

// 业务位置：【案件通知】isDeleting：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
function isDeleting(notificationId) {
  return props.deletingNotificationIds.includes(notificationId);
}

// 业务位置：【案件通知】displayTime：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
function displayTime(value) {
  if (!value) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

// 业务位置：【案件通知】shortId：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 生命周期事件和目标角色 正确进入 站内通知视图和已读/关闭状态。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
function shortId(value) {
  if (!value) return "未关联案件";
  return value.length > 16 ? `${value.slice(0, 9)}…` : value;
}
</script>

<template>
  <div class="summons-mailbox" :class="{ 'summons-mailbox--open': open }">
    <button
      class="summons-mailbox__trigger"
      type="button"
      :aria-expanded="open"
      aria-label="打开传票信箱"
      @click="open = !open"
    >
      <span class="summons-mailbox__box" aria-hidden="true">
        <span class="summons-mailbox__flag" />
        <span class="summons-mailbox__letter">✦</span>
      </span>
      <span>传票信箱</span>
      <strong v-if="unreadCount" data-unread-count>{{ unreadCount }}</strong>
    </button>

    <aside v-if="open" class="summons-mailbox__drawer" aria-label="传票信箱">
      <header>
        <div>
          <span class="summons-mailbox__eyebrow">CASE SUMMONS</span>
          <h2>你的争议来信</h2>
        </div>
        <div class="summons-mailbox__actions">
          <button
            v-if="unreadCount"
            type="button"
            class="summons-mailbox__read-all"
            data-mark-all-read
            @click="emit('mark-all-read')"
          >
            全部已读
          </button>
          <button
            type="button"
            aria-label="关闭传票信箱"
            @click="open = false"
          >
            ×
          </button>
        </div>
      </header>

      <div v-if="error" class="summons-mailbox__state summons-mailbox__state--error">
        <strong>信箱打了个小盹</strong>
        <span>{{ error }}</span>
      </div>
      <div v-else-if="loading" class="summons-mailbox__state">
        <span class="summons-mailbox__sorting" aria-hidden="true">✉</span>
        <strong>正在整理传票…</strong>
      </div>
      <div v-else-if="!notifications.length" class="summons-mailbox__state">
        <span aria-hidden="true">📭</span>
        <strong>信箱里暂时没有新消息</strong>
        <small>案件状态变化时，盖章传票会出现在这里。</small>
      </div>
      <div v-else class="summons-mailbox__list">
        <article
          v-for="item in notifications"
          :key="item.id"
          class="summons-mailbox__notice"
          :class="{ 'summons-mailbox__notice--unread': !item.read }"
          :data-notification-id="item.id"
        >
          <button
            type="button"
            class="summons-mailbox__notice-main"
            data-open-notification
            @click="select(item)"
          >
            <span class="summons-mailbox__stamp" aria-hidden="true">传</span>
            <span class="summons-mailbox__notice-copy">
              <span class="summons-mailbox__notice-meta">
                <small data-case-label :title="item.case_id">{{ shortId(item.case_id) }}</small>
                <time>{{ displayTime(item.created_at) }}</time>
              </span>
              <strong>{{ item.title }}</strong>
              <span>{{ item.body }}</span>
              <small class="summons-mailbox__deep-link">进入对应房间 →</small>
            </span>
          </button>
          <button
            type="button"
            class="summons-mailbox__delete"
            :aria-label="`删除通知：${item.title}`"
            :disabled="isDeleting(item.id)"
            :data-delete-notification="item.id"
            @click="emit('delete-notification', item.id)"
          >
            <span v-if="isDeleting(item.id)" aria-hidden="true">…</span>
            <span v-else aria-hidden="true">×</span>
          </button>
        </article>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.summons-mailbox { position: relative; z-index: 20; }
.summons-mailbox__trigger {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 48px;
  padding: 6px 12px 6px 8px;
  color: #31405d;
  background: #fff;
  border: 1px solid #dfe7f5;
  border-radius: 17px;
  box-shadow: 0 10px 26px #667aa818;
  cursor: pointer;
}
.summons-mailbox__trigger strong {
  display: grid;
  min-width: 22px;
  height: 22px;
  place-items: center;
  color: white;
  background: #ff7768;
  border-radius: 50%;
  font-size: 12px;
}
.summons-mailbox__box {
  position: relative;
  display: grid;
  width: 38px;
  height: 34px;
  place-items: center;
  color: #fff;
  background: #66b9ff;
  border-radius: 9px 9px 13px 13px;
}
.summons-mailbox__flag {
  position: absolute;
  top: -8px;
  right: 5px;
  width: 12px;
  height: 14px;
  background: #ff806e;
  border-radius: 4px 4px 1px 1px;
}
.summons-mailbox__letter {
  transform: translateY(-6px);
  width: 25px;
  height: 18px;
  color: #ff806e;
  background: #fff9e9;
  border-radius: 4px;
  box-shadow: 0 4px 8px #4a6f9d26;
}
.summons-mailbox__drawer {
  position: fixed;
  top: 0;
  right: 0;
  width: min(420px, 100vw);
  height: 100vh;
  padding: 22px;
  overflow-y: auto;
  color: #263650;
  background: linear-gradient(180deg, #f9fcff, #fff7ef);
  border-left: 1px solid #dfe7f5;
  box-shadow: -24px 0 70px #374b7426;
}
.summons-mailbox__drawer header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 22px;
}
.summons-mailbox__drawer h2 { margin: 4px 0 0; font-size: 24px; }
.summons-mailbox__drawer header button {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 50%;
  background: #eaf2ff;
  cursor: pointer;
  font-size: 22px;
}
.summons-mailbox__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.summons-mailbox__drawer header .summons-mailbox__read-all {
  width: auto;
  padding: 0 13px;
  color: #377fbe;
  border-radius: 14px;
  font-size: 13px;
  font-weight: 700;
}
.summons-mailbox__eyebrow { color: #7083a8; font-size: 10px; letter-spacing: .17em; }
.summons-mailbox__list { display: grid; gap: 12px; }
.summons-mailbox__notice {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px;
  gap: 6px;
  width: 100%;
  color: inherit;
  background: #ffffffc9;
  border: 1px solid #e2e8f2;
  border-radius: 20px;
  overflow: hidden;
}
.summons-mailbox__notice-main {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 12px;
  min-width: 0;
  padding: 15px 4px 15px 15px;
  text-align: left;
  color: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
}
.summons-mailbox__delete {
  align-self: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: #8a6670;
  background: #fff0f1;
  border: 1px solid #f2d6d9;
  border-radius: 50%;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
}
.summons-mailbox__delete:hover { color: #b44552; background: #ffe5e7; }
.summons-mailbox__delete:disabled { cursor: wait; opacity: .58; }
.summons-mailbox__delete:focus-visible,
.summons-mailbox__notice-main:focus-visible {
  outline: 2px solid #58aaf0;
  outline-offset: -2px;
}
.summons-mailbox__notice--unread {
  background: #fff;
  border-color: #8dcaff;
  box-shadow: 0 12px 30px #5c86bd18;
}
.summons-mailbox__stamp {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  color: #ef6c5d;
  border: 2px dashed #ef8d7f;
  border-radius: 50%;
  font-weight: 800;
  transform: rotate(-8deg);
}
.summons-mailbox__notice-copy,
.summons-mailbox__notice-copy > span,
.summons-mailbox__notice-copy > strong { display: block; }
.summons-mailbox__notice-copy > strong { margin: 6px 0; }
.summons-mailbox__notice-copy > span { color: #66728a; line-height: 1.5; }
.summons-mailbox__notice-meta {
  display: flex !important;
  justify-content: space-between;
  gap: 8px;
  color: #8994a8 !important;
}
.summons-mailbox__notice-meta small {
  min-width: 0;
  overflow: hidden;
  color: #6b7d99;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.summons-mailbox__deep-link { margin-top: 10px; color: #377fbe !important; }
.summons-mailbox__state {
  display: grid;
  min-height: 260px;
  place-items: center;
  align-content: center;
  gap: 10px;
  padding: 32px;
  text-align: center;
  background: #ffffffb8;
  border: 1px dashed #cbd8eb;
  border-radius: 24px;
}
.summons-mailbox__state > span:first-child { font-size: 40px; }
.summons-mailbox__state--error { color: #9b4a4f; background: #fff0ef; }
.summons-mailbox__sorting { animation: mailbox-sort 1.4s ease-in-out infinite; }
@keyframes mailbox-sort { 50% { transform: translateY(-8px) rotate(5deg); } }
@media (prefers-reduced-motion: reduce) {
  .summons-mailbox__sorting { animation: none; }
}
</style>
