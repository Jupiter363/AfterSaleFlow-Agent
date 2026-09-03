// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { notificationApi } from "../api/notifications";
import { createResourceState, loadResource } from "./resource";

// 业务位置：【前端状态仓库】mergeNotification：将 当前阶段业务数据 持久化或合并到案件快照，使 跨组件一致的案件/房间/证据状态 读取到可追溯版本。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function mergeNotification(current, incoming) {
  const next = current.filter((item) => item.id !== incoming.id);
  next.unshift(incoming);
  return next;
}

export const notificationStore = reactive({
  items: createResourceState([]),
  unreadCount: 0,
});

// 业务位置：【前端状态仓库】loadNotifications：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function loadNotifications(actor) {
  const [items, count] = await Promise.all([
    loadResource(notificationStore.items, () => notificationApi.list(actor), []),
    notificationApi.unreadCount(actor).catch(() => ({ unread_count: 0 })),
  ]);
  notificationStore.unreadCount = count.unread_count ?? 0;
  return items;
}

// 业务位置：【前端状态仓库】markNotificationRead：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function markNotificationRead(actor, notificationId) {
  const updated = await notificationApi.markRead(actor, notificationId);
  notificationStore.items.data = mergeNotification(
    notificationStore.items.data,
    updated,
  );
  notificationStore.unreadCount = Math.max(
    0,
    notificationStore.unreadCount - 1,
  );
  return updated;
}

// 业务位置：【前端状态仓库】markAllNotificationsRead：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function markAllNotificationsRead(actor) {
  const result = await notificationApi.markAllRead(actor);
  notificationStore.items.data = notificationStore.items.data.map((item) => ({
    ...item,
    read: true,
  }));
  notificationStore.unreadCount = 0;
  return result;
}

// 业务位置：【前端状态仓库】deleteNotification：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function deleteNotification(actor, notificationId) {
  const existing = notificationStore.items.data.find(
    (item) => item.id === notificationId,
  );
  const result = await notificationApi.dismiss(actor, notificationId);
  notificationStore.items.data = notificationStore.items.data.filter(
    (item) => item.id !== notificationId,
  );
  if (existing && !existing.read) {
    notificationStore.unreadCount = Math.max(
      0,
      notificationStore.unreadCount - 1,
    );
  }
  return result;
}
