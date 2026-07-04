import { describe, expect, it, vi } from "vitest";
import { notificationApi } from "../api/notifications";
import {
  loadNotifications,
  markAllNotificationsRead,
  mergeNotification,
  notificationStore,
} from "./notification";

describe("notification event merge", () => {
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
});
