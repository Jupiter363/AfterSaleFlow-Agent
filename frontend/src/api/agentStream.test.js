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
