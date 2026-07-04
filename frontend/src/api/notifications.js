import { apiRequest } from "./client";

export const notificationApi = {
  list: (actor) => apiRequest("/notifications", actor),
  unreadCount: (actor) => apiRequest("/notifications/unread-count", actor),
  markRead: (actor, notificationId) =>
    apiRequest(`/notifications/${notificationId}/read`, actor, {
      method: "POST",
    }),
  markAllRead: (actor) =>
    apiRequest("/notifications/read-all", actor, {
      method: "POST",
    }),
};
