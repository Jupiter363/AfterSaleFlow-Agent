// 文件作用：自动化测试文件，验证 notifications.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import { notificationApi } from "./notifications";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.restoreAllMocks();
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("notification API", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
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

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
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

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
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

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("deletes one notification from the current actor's inbox", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { notification_id: "NOTICE_1", deleted: true },
      }),
    });

    await notificationApi.dismiss(actor, "NOTICE_1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/notifications/NOTICE_1",
      expect.objectContaining({
        method: "DELETE",
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
  });
});
