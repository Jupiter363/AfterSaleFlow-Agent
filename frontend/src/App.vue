<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import SummonsMailbox from "./components/notification/SummonsMailbox.vue";
import { actor, demoActors, switchDemoActor } from "./state/actor";
import {
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
let notificationTimer;

async function refreshNotifications() {
  await loadNotifications(actor);
}

async function openNotification(notification) {
  if (notification.deep_link) await router.push(notification.deep_link);
}

async function markRead(notificationId) {
  await markNotificationRead(actor, notificationId);
}

async function markAllRead() {
  await markAllNotificationsRead(actor);
}

async function switchIdentity(role) {
  switchDemoActor(role);
  if (route.path !== "/disputes") {
    await router.push("/disputes");
  }
}

watch(
  () => [actor.id, actor.role],
  refreshNotifications,
  { immediate: true },
);
onMounted(() => {
  notificationTimer = window.setInterval(refreshNotifications, 15_000);
});
onBeforeUnmount(() => window.clearInterval(notificationTimer));
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

      <div class="app-center">
        <nav class="app-nav" aria-label="主导航">
          <router-link to="/disputes">争议订单</router-link>
          <router-link v-if="showReview" to="/reviews">平台终审</router-link>
          <router-link v-if="showReview" to="/agents">数字人管理中心</router-link>
        </nav>
      </div>

      <div class="app-tools">
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
          :loading="notificationStore.items.status === 'loading'"
          :error="noticeError"
          @open-notification="openNotification"
          @mark-read="markRead"
          @mark-all-read="markAllRead"
        />
      </div>
    </header>

    <main class="app-page">
      <router-view :key="`${route.fullPath}:${actor.id}:${actor.role}`" />
    </main>
  </div>
</template>
