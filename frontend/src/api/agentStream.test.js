// 文件作用：自动化测试文件，验证 agentStream.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  consumeAgentRunEvents,
  extractAgentRunDescriptor,
  loadActiveAgentRuns,
  normalizeAgentStreamEvent,
} from "./agentStream";

const actor = { id: "user-local", role: "USER" };

function v1Event(options = {}) {
  const { event = "visible_delta", data = {} } = options;
  const id = Object.hasOwn(options, "id") ? options.id : 2;
  return {
    id,
    event,
    data: {
      schemaVersion: "agent_stream.v1",
      runId: "AGENT_RUN_1",
      sequence: 2,
      type: event,
      field: "room_utterance",
      delta: "请补充发生时间。",
      ...data,
    },
  };
}

function v2Event(options = {}) {
  const { event = "attempt_reset", data = {} } = options;
  const id = Object.hasOwn(options, "id") ? options.id : "v2:ATTEMPT:2:1";
  return {
    id,
    event,
    data: {
      protocol: "agent-stream.v2",
      schemaVersion: "agent-stream.v2",
      runId: "AGENT_RUN_V2",
      attemptId: "ATTEMPT:2",
      sequence: 1,
      cursor: "v2:ATTEMPT:2:1",
      audience: "USER",
      resetAttemptId: "ATTEMPT_1",
      payload: {
        reasonCode: "RETRY",
        resetAttemptId: "ATTEMPT_1",
      },
      ...data,
    },
  };
}

function captureFailure(callback) {
  try {
    callback();
  } catch (error) {
    return error;
  }
  throw new Error("expected protocol validation to fail");
}

function streamResponse(frames) {
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames));
      controller.close();
    },
  }), { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

afterEach(() => {
  vi.restoreAllMocks();
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("agent stream protocol", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("discovers active room runs after refresh with actor isolation headers", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: [{
          runId: "AGENT_RUN_ACTIVE",
          status: "STREAMING",
          streamUrl: "/api/agent-runs/AGENT_RUN_ACTIVE/events",
        }],
      }),
    });

    const active = await loadActiveAgentRuns(actor, "CASE_1", "INTAKE");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/rooms/INTAKE/agent-runs/active",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(active[0].runId).toBe("AGENT_RUN_ACTIVE");
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("accepts both Java camelCase and public snake_case run descriptors", () => {
    expect(extractAgentRunDescriptor({
      runId: "AGENT_RUN_1",
      status: "PENDING",
      streamUrl: "/api/agent-runs/AGENT_RUN_1/events",
    })).toMatchObject({ runId: "AGENT_RUN_1", status: "PENDING" });

    expect(extractAgentRunDescriptor({
      agent_run: {
        run_id: "AGENT_RUN_2",
        status: "PENDING",
        stream_url: "/api/agent-runs/AGENT_RUN_2/events",
      },
    })).toMatchObject({ runId: "AGENT_RUN_2" });

    expect(extractAgentRunDescriptor({
      id: "MESSAGE_1",
      agentRunId: "AGENT_RUN_3",
      messageText: "本轮陈述",
    })).toMatchObject({
      runId: "AGENT_RUN_3",
      streamUrl: "/api/agent-runs/AGENT_RUN_3/events",
    });
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("normalizes the persisted Java SSE event view", () => {
    expect(normalizeAgentStreamEvent({
      id: 2,
      event: "visible_delta",
      data: {
        schemaVersion: "agent_stream.v1",
        runId: "AGENT_RUN_1",
        sequence: 2,
        field: "room_utterance",
        delta: "请补充发生时间。",
      },
    }, "AGENT_RUN_1")).toMatchObject({
      event: "visible_delta",
      sequence: 2,
      fieldPath: "room_utterance",
      delta: "请补充发生时间。",
    });
  });

  it("normalizes attempt-scoped V2 reset cursors without collapsing them to numbers", () => {
    expect(normalizeAgentStreamEvent(
      v2Event(),
      "AGENT_RUN_V2",
      "USER",
    )).toMatchObject({
      protocol: "agent-stream.v2",
      runId: "AGENT_RUN_V2",
      attemptId: "ATTEMPT:2",
      event: "attempt_reset",
      sequence: 1,
      cursor: "v2:ATTEMPT:2:1",
      resetAttemptId: "ATTEMPT_1",
      terminal: false,
    });
  });

  it.each([
    ["missing", undefined],
    ["string", "2"],
    ["noninteger", 2.5],
    ["negative", -1],
    ["unsafe", Number.MAX_SAFE_INTEGER + 1],
  ])("rejects a %s sequence instead of coercing it", (_label, sequence) => {
    const failure = captureFailure(() => normalizeAgentStreamEvent(
      v1Event({ data: { sequence } }),
      "AGENT_RUN_1",
    ));

    expect(failure.code).toBe("AGENT_STREAM_SEQUENCE_INVALID");
  });

  it.each([
    ["missing V1 cursor", v1Event({ id: undefined }), "AGENT_STREAM_CURSOR_INVALID"],
    ["noncanonical V1 cursor", v1Event({ id: "02" }), "AGENT_STREAM_CURSOR_INVALID"],
    ["wrong V1 sequence", v1Event({ id: 3 }), "AGENT_STREAM_CURSOR_INVALID"],
    [
      "missing V2 cursor",
      v2Event({ id: undefined, data: { cursor: undefined } }),
      "AGENT_STREAM_CURSOR_INVALID",
    ],
    [
      "wrong V2 attempt",
      v2Event({ id: "v2:OTHER:1", data: { cursor: "v2:OTHER:1" } }),
      "AGENT_STREAM_CURSOR_MISMATCH",
    ],
    [
      "noncanonical V2 sequence",
      v2Event({ id: "v2:ATTEMPT:2:01", data: { cursor: "v2:ATTEMPT:2:01" } }),
      "AGENT_STREAM_CURSOR_MISMATCH",
    ],
  ])("rejects %s", (_label, event, errorCode) => {
    const expectedRunId = event.data.runId || "AGENT_RUN_1";
    const failure = captureFailure(() => normalizeAgentStreamEvent(
      event,
      expectedRunId,
      "USER",
    ));

    expect(failure.code).toBe(errorCode);
  });

  it("requires a declared run id instead of filling it from the descriptor", () => {
    const failure = captureFailure(() => normalizeAgentStreamEvent(
      v1Event({ data: { runId: undefined } }),
      "AGENT_RUN_1",
    ));

    expect(failure.code).toBe("AGENT_STREAM_RUN_INVALID");
  });

  it.each([
    ["missing", undefined],
    ["non-string", 2],
  ])("rejects a %s V2 attempt id", (_label, attemptId) => {
    const failure = captureFailure(() => normalizeAgentStreamEvent(
      v2Event({ data: { attemptId } }),
      "AGENT_RUN_V2",
      "USER",
    ));

    expect(failure.code).toBe("AGENT_STREAM_ATTEMPT_INVALID");
  });

  it.each([
    ["protocol aliases", v2Event({ data: { schemaVersion: "agent_stream.v1" } }), "AGENT_STREAM_PROTOCOL_CONFLICT"],
    ["run aliases", v1Event({ data: { run_id: "AGENT_RUN_OTHER" } }), "AGENT_STREAM_RUN_INVALID"],
    ["attempt aliases", v2Event({ data: { attempt_id: "ATTEMPT_OTHER" } }), "AGENT_STREAM_ATTEMPT_INVALID"],
    ["sequence aliases", v1Event({ data: { sequence_no: 3 } }), "AGENT_STREAM_SEQUENCE_CONFLICT"],
    [
      "cursor sources",
      v2Event({ data: { payload: { cursor: "v2:ATTEMPT:2:0" } } }),
      "AGENT_STREAM_CURSOR_CONFLICT",
    ],
    ["SSE and payload event types", v1Event({ data: { type: "final" } }), "AGENT_STREAM_EVENT_CONFLICT"],
  ])("fails closed on conflicting %s", (_label, event, errorCode) => {
    const failure = captureFailure(() => normalizeAgentStreamEvent(
      event,
      event.data.runId,
      "USER",
    ));

    expect(failure.code).toBe(errorCode);
  });

  it("requires a valid V2 audience and enforces it for non-admin actors", () => {
    expect(captureFailure(() => normalizeAgentStreamEvent(
      v2Event({ data: { audience: undefined } }),
      "AGENT_RUN_V2",
      "USER",
    )).code).toBe("AGENT_STREAM_AUDIENCE_INVALID");
    expect(captureFailure(() => normalizeAgentStreamEvent(
      v2Event({ data: { audience: "user" } }),
      "AGENT_RUN_V2",
      "USER",
    )).code).toBe("AGENT_STREAM_AUDIENCE_INVALID");
    expect(captureFailure(() => normalizeAgentStreamEvent(
      v2Event({ data: { audience: "user" } }),
      "AGENT_RUN_V2",
      "ADMIN",
    )).code).toBe("AGENT_STREAM_AUDIENCE_INVALID");
    expect(captureFailure(() => normalizeAgentStreamEvent(
      v2Event({ data: { audience: "MERCHANT" } }),
      "AGENT_RUN_V2",
      "USER",
    )).code).toBe("AGENT_STREAM_AUDIENCE_MISMATCH");
    expect(normalizeAgentStreamEvent(
      v2Event({ data: { audience: "MERCHANT" } }),
      "AGENT_RUN_V2",
      "ADMIN",
    ).audience).toBe("MERCHANT");
  });

  it("passes the consuming actor role into V2 audience validation", async () => {
    const frame = 'id: v2:ATTEMPT_1:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","schemaVersion":"agent-stream.v2","runId":"AGENT_RUN_AUDIENCE","attemptId":"ATTEMPT_1","sequence":0,"cursor":"v2:ATTEMPT_1:0","audience":"MERCHANT","type":"attempt_started","payload":{"node":"turn"}}\n\n';
    const descriptor = {
      runId: "AGENT_RUN_AUDIENCE",
      streamUrl: "/api/agent-runs/AGENT_RUN_AUDIENCE/events",
    };

    await expect(consumeAgentRunEvents({
      actor,
      descriptor,
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frame)),
    })).rejects.toMatchObject({ code: "AGENT_STREAM_AUDIENCE_MISMATCH" });

    const onEvent = vi.fn();
    await expect(consumeAgentRunEvents({
      actor: { id: "admin-local", role: "ADMIN" },
      descriptor,
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frame)),
      onEvent,
    })).resolves.toMatchObject({ terminal: false });
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ audience: "MERCHANT" }));
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("uses authenticated fetch, resumes at Last-Event-ID and stops at final", async () => {
    const frames = [
      'id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_1","sequence":0,"type":"start"}\n\n',
      'id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_1","sequence":1,"type":"visible_delta","field":"room_utterance","delta":"正在生成"}\n\n',
      'id: 2\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_1","sequence":2,"type":"final","response":{"room_utterance":"正在生成"}}\n\n',
    ].join("");
    const response = new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(frames));
      },
    }), { status: 200, headers: { "Content-Type": "text/event-stream" } });
    const fetchImpl = vi.fn().mockResolvedValue(response);
    const events = [];

    const result = await consumeAgentRunEvents({
      actor,
      descriptor: {
        runId: "AGENT_RUN_1",
        streamUrl: "/api/agent-runs/AGENT_RUN_1/events",
      },
      lastEventId: -1,
      fetchImpl,
      onEvent: (event) => events.push(event.event),
    });

    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/agent-runs/AGENT_RUN_1/events?last_event_id=-1",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Last-Event-ID": "-1",
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(events).toEqual(["start", "visible_delta", "final"]);
    expect(result).toEqual({ cursor: 2, terminal: true });
  });
});
