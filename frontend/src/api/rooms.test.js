// 文件作用：自动化测试文件，验证 rooms.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  consumeCaseEvents,
  parseSseBlock,
  roomApi,
} from "./rooms";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.restoreAllMocks();
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("room API", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("posts an immutable message with an idempotency key", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { id: "MESSAGE_1", sequence_no: 1 },
      }),
    });

    await roomApi.postMessage(actor, "CASE_1", "EVIDENCE", {
      message_type: "PARTY_TEXT",
      text: "补充说明",
      attachment_refs: [],
    }, "room-message-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/rooms/EVIDENCE/messages",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Idempotency-Key": "room-message-1",
        }),
      }),
    );
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("posts an idempotent room opening request", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { id: "MESSAGE_OPENING", sequence_no: 1 },
      }),
    });

    const opening = await roomApi.ensureOpening(
      actor,
      "CASE_1",
      "EVIDENCE",
      "room-opening-1",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/rooms/EVIDENCE/messages/opening",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Idempotency-Key": "room-opening-1",
        }),
      }),
    );
    expect(opening.id).toBe("MESSAGE_OPENING");
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("loads the latest agent turn memory for a room", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          turn_no: 2,
          scroll_snapshot: { current_outcome: "REFUND" },
        },
      }),
    });

    const memory = await roomApi.latestTurnMemory(actor, "CASE_1", "INTAKE");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/rooms/INTAKE/turn-memory/latest",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(memory.scroll_snapshot.current_outcome).toBe("REFUND");
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("replays durable case events for audit ledger rebuild", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: [
          {
            sequence_no: 12,
            event_type: "EXECUTION_ASSISTANT_HANDOFF",
            payload_json: "{\"status\":\"EXECUTION_ASSISTANT_HANDOFF\"}",
          },
        ],
      }),
    });

    const events = await roomApi.events(actor, "CASE_1", 7);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/events/replay?after_sequence=7",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(events[0].event_type).toBe("EXECUTION_ASSISTANT_HANDOFF");
  });
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("SSE resume", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("parses the server sequence id and typed payload", () => {
    expect(
      parseSseBlock(
        'id: 8\nevent: ROOM_MESSAGE_CREATED\ndata: {"sequence_no":8,"room_id":"ROOM_1"}',
      ),
    ).toEqual({
      id: 8,
      event: "ROOM_MESSAGE_CREATED",
      data: { sequence_no: 8, room_id: "ROOM_1" },
    });
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("sends actor headers and the last durable event id when reconnecting", async () => {
    const encoded = new TextEncoder().encode(
      'id: 8\nevent: HEARING_OPENED\ndata: {"sequence_no":8}\n\n',
    );
    const response = new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoded);
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );
    const fetchImpl = vi.fn().mockResolvedValue(response);
    const received = [];

    const cursor = await consumeCaseEvents({
      actor,
      caseId: "CASE_1",
      lastEventId: 7,
      fetchImpl,
      onEvent: (event) => received.push(event),
    });

    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/events?last_event_id=7",
      expect.objectContaining({
        headers: {
          Accept: "text/event-stream",
          "Last-Event-ID": "7",
          "X-Role": "USER",
          "X-User-Id": "user-local",
        },
      }),
    );
    expect(received).toEqual([
      {
        id: 8,
        event: "HEARING_OPENED",
        data: { sequence_no: 8 },
      },
    ]);
    expect(cursor).toBe(8);
  });
});
