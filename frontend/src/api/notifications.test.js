import { afterEach, describe, expect, it, vi } from "vitest";
import { notificationApi } from "./notifications";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.restoreAllMocks();
});

describe("notification API", () => {
  it("loads the role-scoped inbox and unread count", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ success: true, data: [{ id: "NOTICE_1" }] }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ success: true, data: { unread_count: 3 } }),
      });

    await notificationApi.list(actor);
    await notificationApi.unreadCount(actor);

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/notifications",
      "/api/notifications/unread-count",
    ]);
    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
      "X-Role": "USER",
      "X-User-Id": "user-local",
    });
  });

  it("marks one summons as read", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { id: "NOTICE_1", read_at: "2026-07-03T10:00:00Z" },
      }),
    });

    await notificationApi.markRead(actor, "NOTICE_1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/notifications/NOTICE_1/read",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("marks the current actor's whole inbox as read", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { marked_count: 2 },
      }),
    });

    await notificationApi.markAllRead(actor);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/notifications/read-all",
      expect.objectContaining({ method: "POST" }),
    );
  });
});
