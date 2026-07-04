import { reactive } from "vue";
import { notificationApi } from "../api/notifications";
import { createResourceState, loadResource } from "./resource";

export function mergeNotification(current, incoming) {
  const next = current.filter((item) => item.id !== incoming.id);
  next.unshift(incoming);
  return next;
}

export const notificationStore = reactive({
  items: createResourceState([]),
  unreadCount: 0,
});

export async function loadNotifications(actor) {
  const [items, count] = await Promise.all([
    loadResource(notificationStore.items, () => notificationApi.list(actor), []),
    notificationApi.unreadCount(actor).catch(() => ({ unread_count: 0 })),
  ]);
  notificationStore.unreadCount = count.unread_count ?? 0;
  return items;
}

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

export async function markAllNotificationsRead(actor) {
  const result = await notificationApi.markAllRead(actor);
  notificationStore.items.data = notificationStore.items.data.map((item) => ({
    ...item,
    read: true,
  }));
  notificationStore.unreadCount = 0;
  return result;
}
