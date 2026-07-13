// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

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
  dismiss: (actor, notificationId) =>
    apiRequest(`/notifications/${encodeURIComponent(notificationId)}`, actor, {
      method: "DELETE",
    }),
};
