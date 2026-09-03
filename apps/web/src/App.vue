<!--
  文件作用：前端工程代码文件，支撑售后争议系统的页面、交互、样式或构建配置。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { disputeApi } from "./api/disputes";
import SummonsMailbox from "./components/notification/SummonsMailbox.vue";
import { actor, demoActors, switchDemoActor } from "./state/actor";
import {
  disputeStore,
  loadDisputes,
} from "./stores/dispute";
import {
  deleteNotification,
  loadNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationStore,
} from "./stores/notification";

const route = useRoute();
const router = useRouter();
const showReview = computed(() => actor.role === "PLATFORM_REVIEWER");
const notices = computed(() => notificationStore.items.data || []);
const noticeError = computed(
  () => notificationStore.items.error?.message || "",
);
const deletingNotificationIds = ref([]);
let notificationTimer;
let caseSyncTimer;
let caseSyncRunning = false;
const CASE_SYNC_INTERVAL_MS = 3_000;

// 业务位置：【前端应用】refreshNotifications：重新加载 当前阶段业务数据，确保页面和下一次 Agent 调用基于最新案件版本。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function refreshNotifications() {
  await loadNotifications(actor);
}

// 业务位置：【前端应用】refreshCaseState：重新加载 当前阶段业务数据，确保页面和下一次 Agent 调用基于最新案件版本。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function refreshCaseState() {
  if (caseSyncRunning) return;
  caseSyncRunning = true;
  const actorSnapshot = { id: actor.id, role: actor.role };
  try {
    await loadDisputes(actorSnapshot);
    const caseId = route.params.caseId;
    if (!caseId || typeof caseId !== "string") return;
    await disputeApi.get(actorSnapshot, caseId);
  } catch (error) {
    const caseId = route.params.caseId;
    if (
      error?.code === "CASE_NOT_FOUND" &&
      typeof caseId === "string"
    ) {
      disputeStore.current.data = null;
      disputeStore.current.status = "empty";
      await router.replace("/disputes");
      ElMessage.warning("该案例已被审核员删除，已返回争议总览");
    }
  } finally {
    caseSyncRunning = false;
  }
}

// 业务位置：【前端应用】openNotification：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function openNotification(notification) {
  if (notification.deep_link) await router.push(notification.deep_link);
}

// 业务位置：【前端应用】markRead：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function markRead(notificationId) {
  await markNotificationRead(actor, notificationId);
}

// 业务位置：【前端应用】markAllRead：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function markAllRead() {
  await markAllNotificationsRead(actor);
}

// 业务位置：【前端应用】deleteNotice：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function deleteNotice(notificationId) {
  if (deletingNotificationIds.value.includes(notificationId)) return;
  deletingNotificationIds.value = [
    ...deletingNotificationIds.value,
    notificationId,
  ];
  try {
    await deleteNotification(actor, notificationId);
  } catch (error) {
    ElMessage.error(error?.message || "通知删除失败，请稍后重试");
  } finally {
    deletingNotificationIds.value = deletingNotificationIds.value.filter(
      (id) => id !== notificationId,
    );
  }
}

// 业务位置：【前端应用】switchIdentity：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function switchIdentity(role) {
  switchDemoActor(role);
  if (route.path !== "/disputes") {
    await router.push("/disputes");
  }
}

watch(
  () => [actor.id, actor.role],
  () => {
    refreshNotifications();
    refreshCaseState();
  },
  { immediate: true },
);
onMounted(() => {
  notificationTimer = window.setInterval(refreshNotifications, 15_000);
  caseSyncTimer = window.setInterval(refreshCaseState, CASE_SYNC_INTERVAL_MS);
  window.addEventListener("focus", refreshCaseState);
});
onBeforeUnmount(() => {
  window.clearInterval(notificationTimer);
  window.clearInterval(caseSyncTimer);
  window.removeEventListener("focus", refreshCaseState);
});
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <router-link class="brand" to="/disputes" aria-label="返回争议订单中心">
        <span class="brand__mark" aria-hidden="true">
          <i>⚖</i><b>AI</b>
        </span>
        <span class="brand__copy">
          <strong>履约争议游园会</strong>
          <small>AI Native Dispute Court</small>
        </span>
      </router-link>

      <div class="app-tools">
        <nav class="app-nav app-nav--tools" aria-label="主导航">
          <router-link to="/disputes">总览</router-link>
          <router-link v-if="showReview" to="/reviews">平台终审</router-link>
          <router-link v-if="showReview" to="/agents">数字人管理中心</router-link>
        </nav>
        <div class="actor-switcher" aria-label="体验身份">
          <label>
            <span>身份 ID</span>
            <input :value="actor.id" aria-label="身份 ID" readonly />
          </label>
          <label>
            <span>体验角色</span>
            <select
              :value="actor.role"
              aria-label="体验角色"
              @change="switchIdentity($event.target.value)"
            >
              <option
                v-for="demoActor in demoActors"
                :key="demoActor.role"
                :value="demoActor.role"
              >
                {{ demoActor.label }}
              </option>
            </select>
          </label>
        </div>
        <SummonsMailbox
          :notifications="notices"
          :deleting-notification-ids="deletingNotificationIds"
          :loading="notificationStore.items.status === 'loading'"
          :error="noticeError"
          @open-notification="openNotification"
          @mark-read="markRead"
          @mark-all-read="markAllRead"
          @delete-notification="deleteNotice"
        />
      </div>
    </header>

    <main class="app-page">
      <router-view :key="`${route.fullPath}:${actor.id}:${actor.role}`" />
    </main>
  </div>
</template>
