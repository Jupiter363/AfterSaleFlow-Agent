// 文件作用：自动化测试文件，验证 agentStream.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearAgentStreams,
  consumeAgentRun,
  durableMessagesOutsideActiveStreams,
  getAgentStreamRun,
} from "./agentStream";

const actor = { id: "user-local", role: "USER" };

// 业务位置：【前端状态仓库】streamResponse：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function streamResponse(frames) {
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames.join("")));
    },
  }), { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

afterEach(() => {
  clearAgentStreams({}, { abort: true });
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// 业务位置：【前端状态仓库】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
describe("agentStreamStore", () => {
  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("collects approved deltas and keeps the temporary message until final refresh completes", async () => {
    let releaseRefresh;
    const refreshBarrier = new Promise((resolve) => {
      releaseRefresh = resolve;
    });
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      'id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_STORE","sequence":0,"type":"start"}\n\n',
      'id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_STORE","sequence":1,"type":"visible_delta","field":"room_utterance","delta":"第一段"}\n\n',
      'id: 2\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_STORE","sequence":2,"type":"visible_delta","field":"room_utterance","delta":"第二段"}\n\n',
      'id: 3\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_STORE","sequence":3,"type":"visible_delta","field":"case_detail.case_story","delta":"{\\"one_sentence_summary\\":\\"案情摘要\\"}"}\n\n',
      'id: 4\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_STORE","sequence":4,"type":"final","response":{"ok":true}}\n\n',
    ]));

    const consuming = consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_STORE",
        streamUrl: "/api/agent-runs/AGENT_RUN_STORE/events",
      },
      fetchImpl,
      onFinal: () => refreshBarrier,
    });

    await vi.waitFor(() => {
      expect(getAgentStreamRun("AGENT_RUN_STORE")?.status).toBe("FINALIZING");
    });
    expect(getAgentStreamRun("AGENT_RUN_STORE")?.content).toBe("第一段第二段");
    expect(getAgentStreamRun("AGENT_RUN_STORE")?.fieldText["case_detail.case_story"])
      .toBe('{"one_sentence_summary":"案情摘要"}');

    releaseRefresh();
    await consuming;
    expect(getAgentStreamRun("AGENT_RUN_STORE")?.status).toBe("COMPLETED");
  });

  it("replaces a model root JSON snapshot with the terminal snapshot while case-detail leaves append", async () => {
    const runId = "AGENT_RUN_CASE_DETAIL_SNAPSHOT";
    const modelSnapshot = JSON.stringify({
      one_sentence_summary: "model provisional summary",
      tags: ["model"],
    });
    const terminalSnapshot = JSON.stringify({
      one_sentence_summary: "terminal summary",
      tags: ["terminal"],
    });
    const visibleDelta = (sequence, field, delta) => [
      `id: ${sequence}`,
      "event: visible_delta",
      `data: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence,
        type: "visible_delta",
        field,
        delta,
      })}`,
      "",
      "",
    ].join("\n");
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      `id: 0\nevent: start\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 0,
        type: "start",
      })}\n\n`,
      visibleDelta(1, "case_detail.case_story", modelSnapshot),
      visibleDelta(2, "case_detail.case_story.one_sentence_summary", "leaf "),
      visibleDelta(3, "case_detail.case_story.one_sentence_summary", "text"),
      visibleDelta(4, "case_detail.case_story", terminalSnapshot),
      `id: 5\nevent: final\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 5,
        type: "final",
        response: {},
      })}\n\n`,
    ]));

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId,
        streamUrl: `/api/agent-runs/${runId}/events`,
      },
      fetchImpl,
    });

    const run = getAgentStreamRun(runId);
    expect(run.fieldText["case_detail.case_story"]).toBe(terminalSnapshot);
    expect(run.receivedFieldText["case_detail.case_story"]).toBe(terminalSnapshot);
    expect(run.fieldText["case_detail.case_story.one_sentence_summary"])
      .toBe("leaf text");
    expect(run.receivedFieldText["case_detail.case_story.one_sentence_summary"])
      .toBe("leaf text");
  });

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("does not render internal reasoning fields even if a malformed relay emits one", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      'id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_PRIVATE","sequence":0,"type":"start"}\n\n',
      'id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_PRIVATE","sequence":1,"type":"visible_delta","field":"reasoning_content","delta":"内部推理"}\n\n',
      'id: 2\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_PRIVATE","sequence":2,"type":"final","response":{}}\n\n',
    ]));

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_PRIVATE",
        streamUrl: "/api/agent-runs/AGENT_RUN_PRIVATE/events",
      },
      fetchImpl,
    });

    expect(getAgentStreamRun("AGENT_RUN_PRIVATE")?.content).toBe("");
  });

  it("clears the aborted attempt before revealing replacement attempt text", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      'id: v2:ATTEMPT_1:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_1","sequence":0,"cursor":"v2:ATTEMPT_1:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_1:1\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_1","sequence":1,"cursor":"v2:ATTEMPT_1:1","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"旧文本"}}\n\n',
      'id: v2:ATTEMPT_1:2\nevent: attempt_aborted\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_1","sequence":2,"cursor":"v2:ATTEMPT_1:2","audience":"USER","payload":{"reasonCode":"TRANSPORT_LOST"}}\n\n',
      'id: v2:ATTEMPT_2:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_2","sequence":0,"cursor":"v2:ATTEMPT_2:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_2:1\nevent: attempt_reset\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_2","sequence":1,"cursor":"v2:ATTEMPT_2:1","audience":"USER","resetAttemptId":"ATTEMPT_1","payload":{"reasonCode":"RETRY","resetAttemptId":"ATTEMPT_1"}}\n\n',
      'id: v2:ATTEMPT_2:2\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_2","sequence":2,"cursor":"v2:ATTEMPT_2:2","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"新文本"}}\n\n',
      'id: v2:ATTEMPT_2:3\nevent: final\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET","attemptId":"ATTEMPT_2","sequence":3,"cursor":"v2:ATTEMPT_2:3","audience":"USER","response":{"finalResultHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"payload":{"finalResultHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}\n\n',
    ]));

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_RESET",
        streamUrl: "/api/agent-runs/AGENT_RUN_RESET/events",
      },
      fetchImpl,
    });

    const run = getAgentStreamRun("AGENT_RUN_RESET");
    expect(run.content).toBe("新文本");
    expect(run.content).not.toContain("旧文本");
    expect(run.currentAttemptId).toBe("ATTEMPT_2");
    expect(run.resetCount).toBe(1);
    expect(run.lastEventId).toBe("v2:ATTEMPT_2:3");
  });

  it("replays an overflowed delta without duplicating received or durable fallback text", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true })));
    const accepted = "A".repeat(256 * 1024);
    const firstResponse = streamResponse([
      'id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_SLOW","sequence":0,"type":"start"}\n\n',
      `id: 1\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId: "AGENT_RUN_SLOW",
        sequence: 1,
        type: "visible_delta",
        field: "room_utterance",
        delta: accepted,
      })}\n\n`,
      'id: 2\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_SLOW","sequence":2,"type":"visible_delta","field":"room_utterance","delta":"B"}\n\n',
    ]);
    const replayResponse = streamResponse([
      'id: 2\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_SLOW","sequence":2,"type":"visible_delta","field":"room_utterance","delta":"B"}\n\n',
      'id: 3\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_SLOW","sequence":3,"type":"final","response":{"ok":true}}\n\n',
    ]);
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(firstResponse)
      .mockResolvedValueOnce(replayResponse);

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_SLOW",
        streamUrl: "/api/agent-runs/AGENT_RUN_SLOW/events",
      },
      reconnectBaseDelayMs: 1,
      fetchImpl,
    });

    const run = getAgentStreamRun("AGENT_RUN_SLOW");
    expect(run.reconnectCount).toBe(1);
    expect(run.receivedContent).toBe(`${accepted}B`);
    expect(run.receivedContent.endsWith("BB")).toBe(false);
    expect(durableMessagesOutsideActiveStreams([
      { senderRole: "AGENT", messageText: `${accepted}B` },
    ], [run])).toEqual([]);
  });

  it("commits a visible delta before an observer failure reconnects and replays it", async () => {
    const firstResponse = streamResponse([
      'id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_OBSERVER","sequence":0,"type":"start"}\n\n',
      'id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_OBSERVER","sequence":1,"type":"visible_delta","field":"room_utterance","delta":"仅应用一次"}\n\n',
    ]);
    const replayResponse = streamResponse([
      'id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_OBSERVER","sequence":1,"type":"visible_delta","field":"room_utterance","delta":"仅应用一次"}\n\n',
      'id: 2\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_OBSERVER","sequence":2,"type":"final","response":{"ok":true}}\n\n',
    ]);
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(firstResponse)
      .mockResolvedValueOnce(replayResponse);
    let failObserver = true;
    const onEvent = vi.fn((event) => {
      if (event.event === "visible_delta" && failObserver) {
        failObserver = false;
        throw new Error("observer failed once");
      }
    });

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_OBSERVER",
        streamUrl: "/api/agent-runs/AGENT_RUN_OBSERVER/events",
      },
      reconnectBaseDelayMs: 1,
      fetchImpl,
      onEvent,
    });

    const run = getAgentStreamRun("AGENT_RUN_OBSERVER");
    expect(run.reconnectCount).toBe(1);
    expect(run.receivedContent).toBe("仅应用一次");
    expect(run.content).toBe("仅应用一次");
    expect(onEvent.mock.calls.filter(([event]) => event.event === "visible_delta"))
      .toHaveLength(1);
    expect(fetchImpl.mock.calls[1][0]).toContain("last_event_id=1");
    expect(fetchImpl.mock.calls[1][1].headers["Last-Event-ID"]).toBe("1");
  });

  it("commits an attempt reset before observer failure replay", async () => {
    const firstResponse = streamResponse([
      'id: v2:ATTEMPT_1:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_1","sequence":0,"cursor":"v2:ATTEMPT_1:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_1:1\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_1","sequence":1,"cursor":"v2:ATTEMPT_1:1","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"旧文本"}}\n\n',
      'id: v2:ATTEMPT_1:2\nevent: attempt_aborted\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_1","sequence":2,"cursor":"v2:ATTEMPT_1:2","audience":"USER","payload":{"reasonCode":"TRANSPORT_LOST"}}\n\n',
      'id: v2:ATTEMPT_2:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_2","sequence":0,"cursor":"v2:ATTEMPT_2:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_2:1\nevent: attempt_reset\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_2","sequence":1,"cursor":"v2:ATTEMPT_2:1","audience":"USER","resetAttemptId":"ATTEMPT_1","payload":{"reasonCode":"RETRY","resetAttemptId":"ATTEMPT_1"}}\n\n',
    ]);
    const replayResponse = streamResponse([
      'id: v2:ATTEMPT_2:1\nevent: attempt_reset\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_2","sequence":1,"cursor":"v2:ATTEMPT_2:1","audience":"USER","resetAttemptId":"ATTEMPT_1","payload":{"reasonCode":"RETRY","resetAttemptId":"ATTEMPT_1"}}\n\n',
      'id: v2:ATTEMPT_2:2\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_2","sequence":2,"cursor":"v2:ATTEMPT_2:2","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"新文本"}}\n\n',
      'id: v2:ATTEMPT_2:3\nevent: final\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_RESET_REPLAY","attemptId":"ATTEMPT_2","sequence":3,"cursor":"v2:ATTEMPT_2:3","audience":"USER","response":{"finalResultHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"payload":{"finalResultHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}\n\n',
    ]);
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(firstResponse)
      .mockResolvedValueOnce(replayResponse);
    let failObserver = true;
    const onEvent = vi.fn((event) => {
      if (event.event === "attempt_reset" && failObserver) {
        failObserver = false;
        throw new Error("observer failed once");
      }
    });

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_RESET_REPLAY",
        streamUrl: "/api/agent-runs/AGENT_RUN_RESET_REPLAY/events",
      },
      reconnectBaseDelayMs: 1,
      fetchImpl,
      onEvent,
    });

    const run = getAgentStreamRun("AGENT_RUN_RESET_REPLAY");
    expect(run.reconnectCount).toBe(1);
    expect(run.resetCount).toBe(1);
    expect(run.currentAttemptId).toBe("ATTEMPT_2");
    expect(run.content).toBe("新文本");
    expect(run.content).not.toContain("旧文本");
    expect(run.lastEventId).toBe("v2:ATTEMPT_2:3");
    expect(onEvent.mock.calls.filter(([event]) => event.event === "attempt_reset"))
      .toHaveLength(1);
    expect(fetchImpl.mock.calls[1][0]).toContain(
      "last_event_id=v2%3AATTEMPT_2%3A1",
    );
    expect(fetchImpl.mock.calls[1][1].headers["Last-Event-ID"])
      .toBe("v2:ATTEMPT_2:1");
  });

  it("activates a retry without reset when the failed attempt emitted no visible delta", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      'id: v2:ATTEMPT_1:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_EMPTY_RETRY","attemptId":"ATTEMPT_1","sequence":0,"cursor":"v2:ATTEMPT_1:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_1:1\nevent: attempt_aborted\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_EMPTY_RETRY","attemptId":"ATTEMPT_1","sequence":1,"cursor":"v2:ATTEMPT_1:1","audience":"USER","payload":{"reasonCode":"MODEL_TRANSIENT_FAILURE"}}\n\n',
      'id: v2:ATTEMPT_2:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_EMPTY_RETRY","attemptId":"ATTEMPT_2","sequence":0,"cursor":"v2:ATTEMPT_2:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_2:1\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_EMPTY_RETRY","attemptId":"ATTEMPT_2","sequence":1,"cursor":"v2:ATTEMPT_2:1","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"retry output"}}\n\n',
      'id: v2:ATTEMPT_2:2\nevent: final\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_EMPTY_RETRY","attemptId":"ATTEMPT_2","sequence":2,"cursor":"v2:ATTEMPT_2:2","audience":"USER","response":{"finalResultHash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"payload":{"finalResultHash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}}\n\n',
    ]));

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_EMPTY_RETRY",
        streamUrl: "/api/agent-runs/AGENT_RUN_EMPTY_RETRY/events",
      },
      fetchImpl,
    });

    const run = getAgentStreamRun("AGENT_RUN_EMPTY_RETRY");
    expect(run.currentAttemptId).toBe("ATTEMPT_2");
    expect(run.pendingAttemptId).toBe("");
    expect(run.resetCount).toBe(0);
    expect(run.attempts.ATTEMPT_1.status).toBe("ABORTED");
    expect(run.attempts.ATTEMPT_1.hasVisibleOutput).toBe(false);
    expect(run.attempts.ATTEMPT_2.hasVisibleOutput).toBe(true);
    expect(run.content).toBe("retry output");
  });

  it("rejects an unreset retry after the active attempt has visible output", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      'id: v2:ATTEMPT_1:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_VISIBLE_RETRY","attemptId":"ATTEMPT_1","sequence":0,"cursor":"v2:ATTEMPT_1:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_1:1\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_VISIBLE_RETRY","attemptId":"ATTEMPT_1","sequence":1,"cursor":"v2:ATTEMPT_1:1","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"original output"}}\n\n',
      'id: v2:ATTEMPT_1:2\nevent: attempt_aborted\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_VISIBLE_RETRY","attemptId":"ATTEMPT_1","sequence":2,"cursor":"v2:ATTEMPT_1:2","audience":"USER","payload":{"reasonCode":"MODEL_TRANSIENT_FAILURE"}}\n\n',
      'id: v2:ATTEMPT_2:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_VISIBLE_RETRY","attemptId":"ATTEMPT_2","sequence":0,"cursor":"v2:ATTEMPT_2:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v2:ATTEMPT_2:1\nevent: visible_delta\ndata: {"protocol":"agent-stream.v2","runId":"AGENT_RUN_VISIBLE_RETRY","attemptId":"ATTEMPT_2","sequence":1,"cursor":"v2:ATTEMPT_2:1","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"replacement output"}}\n\n',
    ]));

    await expect(consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_VISIBLE_RETRY",
        streamUrl: "/api/agent-runs/AGENT_RUN_VISIBLE_RETRY/events",
      },
      fetchImpl,
    })).rejects.toMatchObject({ code: "AGENT_STREAM_ATTEMPT_OUT_OF_ORDER" });

    const run = getAgentStreamRun("AGENT_RUN_VISIBLE_RETRY");
    expect(run.currentAttemptId).toBe("ATTEMPT_1");
    expect(run.pendingAttemptId).toBe("ATTEMPT_2");
    expect(run.attempts.ATTEMPT_1.hasVisibleOutput).toBe(true);
    expect(run.receivedContent).toBe("original output");
    expect(run.receivedContent).not.toContain("replacement output");
  });
});
