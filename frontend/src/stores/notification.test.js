// 文件作用：自动化测试文件，验证 notification.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { describe, expect, it, vi } from "vitest";
import { notificationApi } from "../api/notifications";
import {
  deleteNotification,
  loadNotifications,
  markAllNotificationsRead,
  mergeNotification,
  notificationStore,
} from "./notification";

// 业务位置：【前端状态仓库】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
describe("notification event merge", () => {
  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("updates repeated notification events idempotently", () => {
    const first = {
      id: "NOTICE_1",
      title: "争议传票",
      read: false,
    };
    const updated = {
      id: "NOTICE_1",
      title: "争议传票",
      read: true,
    };

    const result = mergeNotification(
      mergeNotification([], first),
      updated,
    );

    expect(result).toEqual([updated]);
  });

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("keeps the application usable when the unread counter is unavailable", async () => {
    vi.spyOn(notificationApi, "list").mockResolvedValue([]);
    vi.spyOn(notificationApi, "unreadCount").mockRejectedValue(
      new Error("counter offline"),
    );

    await expect(
      loadNotifications({ id: "user-1", role: "USER" }),
    ).resolves.toEqual([]);
    expect(notificationStore.unreadCount).toBe(0);
  });

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("marks all local inbox items as read after the server accepts the command", async () => {
    notificationStore.items.data = [
      { id: "NOTICE_1", read: false },
      { id: "NOTICE_2", read: true },
    ];
    notificationStore.unreadCount = 1;
    vi.spyOn(notificationApi, "markAllRead").mockResolvedValue({
      marked_count: 1,
    });

    const result = await markAllNotificationsRead({
      id: "user-1",
      role: "USER",
    });

    expect(result).toEqual({ marked_count: 1 });
    expect(notificationStore.items.data.every((item) => item.read)).toBe(true);
    expect(notificationStore.unreadCount).toBe(0);
  });

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("removes a deleted unread notification without touching other inbox items", async () => {
    notificationStore.items.data = [
      { id: "NOTICE_1", read: false },
      { id: "NOTICE_2", read: true },
    ];
    notificationStore.unreadCount = 1;
    vi.spyOn(notificationApi, "dismiss").mockResolvedValue({
      notification_id: "NOTICE_1",
      deleted: true,
    });

    const result = await deleteNotification(
      { id: "reviewer-local", role: "PLATFORM_REVIEWER" },
      "NOTICE_1",
    );

    expect(result.deleted).toBe(true);
    expect(notificationStore.items.data).toEqual([
      { id: "NOTICE_2", read: true },
    ]);
    expect(notificationStore.unreadCount).toBe(0);
  });
});
