<script setup>
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { reviewApi } from "../../api/review";
import { actor } from "../../state/actor";

const props = defineProps({
  initialTasks: { type: Array, default: null },
});

const router = useRouter();
const tasks = ref(props.initialTasks || []);
const loading = ref(false);
const error = ref("");
const priorityLabels = {
  URGENT: "急件",
  HIGH: "高优先",
  MEDIUM: "中优先",
  NORMAL: "普通",
  LOW: "低优先",
};
const statusLabels = {
  PENDING: "待审核",
  ASSIGNED: "已分配",
  IN_PROGRESS: "审核中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
};

async function load() {
  if (props.initialTasks !== null) return;
  loading.value = true;
  try {
    tasks.value = await reviewApi.list(actor);
  } catch (failure) {
    error.value = failure.message;
  } finally {
    loading.value = false;
  }
}

function openTask(task) {
  return router.push(`/reviews/${task.id}`);
}

function shortId(value) {
  if (!value) return "未关联";
  return value.length > 16 ? `${value.slice(0, 9)}…` : value;
}

function displayTime(value) {
  if (!value) return "等待分配";
  const date = new Date(value);
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  return `${date.getMonth() + 1}月${date.getDate()}日 ${hour}:${minute}`;
}

function priorityLabel(priority) {
  return priorityLabels[priority] || priority || "普通";
}

function statusLabel(status) {
  return statusLabels[status] || status || "待审核";
}

onMounted(load);
</script>

<template>
  <main class="review-queue-page">
    <header class="review-queue-page__hero">
      <div>
        <span>HUMAN FINAL GATE</span>
        <h1>平台终审队列</h1>
        <p>AI 已经把长卷宗折成可核验的审核包，最后一槌仍由人来落下。</p>
      </div>
      <div class="review-queue-page__mascot" aria-hidden="true">
        <span>⚖️</span><i>仅审核员可进入</i>
      </div>
    </header>

    <section class="review-queue-board">
      <div class="review-queue-board__title">
        <div>
        <span>冻结审核包</span>
          <h2>待审核案件</h2>
        </div>
        <button type="button" :disabled="loading" @click="load">刷新队列</button>
      </div>

      <div v-if="loading" class="review-queue-state">正在整理审核包…</div>
      <div v-else-if="error" class="review-queue-state review-queue-state--error">{{ error }}</div>
      <div v-else-if="!tasks.length" class="review-queue-state">队列已经清空，今天的法槌可以歇一会儿。</div>
      <div v-else class="review-queue-grid">
        <button
          v-for="task in tasks"
          :key="task.id"
          type="button"
          class="review-task"
          :data-priority="task.priority"
          data-review-task
          @click="openTask(task)"
        >
          <div class="review-task__flag">
            <span>{{ priorityLabel(task.priority) }}</span>
            <i>{{ statusLabel(task.status) }}</i>
          </div>
          <strong data-review-case-id :title="task.case_id">{{ shortId(task.case_id) }}</strong>
          <small :title="task.id">{{ shortId(task.id) }}</small>
          <div class="review-task__due">
            <span>审核时效</span>
            <time data-review-due :datetime="task.due_at || null">{{ displayTime(task.due_at) }}</time>
          </div>
          <em>打开冻结审核包 →</em>
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.review-queue-page { display: grid; gap: 22px; }
.review-queue-page__hero {
  display: flex; justify-content: space-between; align-items: center; gap: 24px;
  padding: 30px; background: linear-gradient(135deg, #e9f7ff, #f3edff 56%, #fff4dc);
  border: 1px solid #dce7f2; border-radius: 34px;
}
.review-queue-page__hero > div > span, .review-queue-board__title span {
  color: #7487a6; font-size: 10px; font-weight: 900; letter-spacing: .17em;
}
.review-queue-page__hero h1 { margin: 7px 0; color: #30405b; font-size: clamp(30px, 5vw, 48px); }
.review-queue-page__hero p { margin: 0; color: #697991; }
.review-queue-page__mascot { display: grid; place-items: center; min-width: 150px; padding: 18px; background: #ffffffb8; border-radius: 26px; }
.review-queue-page__mascot span { font-size: 52px; }
.review-queue-page__mascot i { color: #77688d; font-size: 11px; font-style: normal; }
.review-queue-board { padding: 22px; background: #ffffffcc; border: 1px solid #e0e8f1; border-radius: 30px; }
.review-queue-board__title { display: flex; justify-content: space-between; gap: 16px; }
.review-queue-board__title h2 { margin: 5px 0; color: #34445d; }
.review-queue-board__title button { padding: 9px 13px; color: #5f6f89; background: #f2f6fb; border: 1px solid #dae4ef; border-radius: 12px; }
.review-queue-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-top: 16px; }
.review-task {
  display: grid; gap: 9px; padding: 17px; text-align: left; background: linear-gradient(160deg, #fff, #f6faff);
  border: 1px solid #dde7f2; border-radius: 22px; cursor: pointer; transition: transform .18s ease, box-shadow .18s ease;
}
.review-task:hover { transform: translateY(-3px); box-shadow: 0 16px 35px #556b8818; }
.review-task[data-priority="URGENT"] { background: linear-gradient(160deg, #fff6f1, #fff); border-color: #f0d9cc; }
.review-task__flag { display: flex; justify-content: space-between; }
.review-task__flag span { padding: 4px 7px; color: #a95851; background: #ffebe5; border-radius: 999px; font-size: 10px; }
.review-task__flag i { color: #74849b; font-size: 10px; font-style: normal; }
.review-task strong { color: #34445e; font-size: 18px; }
.review-task strong,
.review-task small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.review-task small { color: #8a96a8; }
.review-task__due { display: grid; gap: 3px; padding: 10px; background: #f6f8fb; border-radius: 12px; }
.review-task__due span { color: #8793a5; font-size: 10px; }
.review-task__due time { color: #5d6b80; font-size: 12px; }
.review-task em { color: #6e65a1; font-size: 12px; font-style: normal; font-weight: 800; }
.review-queue-state { padding: 50px; color: #7d899c; text-align: center; }
.review-queue-state--error { color: #a54753; }
@media (max-width: 900px) { .review-queue-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 620px) {
  .review-queue-page__mascot { display: none; }
  .review-queue-grid { grid-template-columns: 1fr; }
}
</style>
