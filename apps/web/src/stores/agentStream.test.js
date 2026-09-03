// 文件作用：自动化测试文件，验证 agentStream.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  abortAgentStream,
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

function v4SseFrame(runId, attemptId, sequence, event, payload) {
  const cursor = `v4:${attemptId}:${sequence}`;
  return `id: ${cursor}\nevent: ${event}\ndata: ${JSON.stringify({
    schemaVersion: "agent-stream.v4",
    protocol: "agent-stream.v4",
    runId,
    attemptId,
    sequence,
    cursor,
    audience: "USER",
    payload,
  })}\n\n`;
}

async function flushMicrotasks(turns = 24) {
  for (let turn = 0; turn < turns; turn += 1) {
    await Promise.resolve();
  }
}

afterEach(() => {
  clearAgentStreams({}, { abort: true });
  vi.useRealTimers();
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

  it("keeps an Intake dossier unacknowledged until the reply pacer has drained", async () => {
    vi.useFakeTimers();
    const runId = "AGENT_RUN_REPLY_THEN_BOARD";
    const reply = "接待官回复".repeat(600);
    const dossier = JSON.stringify({ one_sentence_summary: "回复后才展示的卷宗" });
    const observed = [];
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      `id: 0\nevent: start\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 0,
        type: "start",
      })}\n\n`,
      `id: 1\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 1,
        type: "visible_delta",
        field: "room_utterance",
        delta: reply,
      })}\n\n`,
      `id: 2\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 2,
        type: "visible_delta",
        field: "case_detail.case_story",
        delta: dossier,
      })}\n\n`,
      `id: 3\nevent: final\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 3,
        type: "final",
        result: {},
      })}\n\n`,
    ]));

    const consuming = consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      replyThenBoard: true,
      descriptor: {
        runId,
        streamUrl: `/api/agent-runs/${runId}/events`,
      },
      fetchImpl,
      onEvent: (event) => observed.push(event.sequence),
    });

    await flushMicrotasks();
    const beforeDrain = getAgentStreamRun(runId);
    expect(beforeDrain.receivedContent).toBe(reply);
    expect(beforeDrain.content.length).toBeLessThan(reply.length);
    expect(beforeDrain.fieldText["case_detail.case_story"]).toBeUndefined();
    expect(beforeDrain.receivedFieldText["case_detail.case_story"]).toBeUndefined();
    expect(beforeDrain.lastEventId).toBe("1");
    expect(observed).toEqual([0, 1]);

    await vi.advanceTimersByTimeAsync(1_500);
    await vi.runAllTimersAsync();
    await consuming;

    const completed = getAgentStreamRun(runId);
    expect(completed.content).toBe(reply);
    expect(completed.fieldText["case_detail.case_story"]).toBe(dossier);
    expect(completed.receivedFieldText["case_detail.case_story"]).toBe(dossier);
    expect(completed.lastEventId).toBe("3");
    expect(observed).toEqual([0, 1, 2, 3]);
  });

  it("does not acknowledge an Intake dossier that is aborted behind the reply barrier", async () => {
    vi.useFakeTimers();
    const runId = "AGENT_RUN_REPLY_THEN_BOARD_ABORT";
    const reply = "仍在打字".repeat(600);
    const dossier = JSON.stringify({ one_sentence_summary: "不应提前确认" });
    const firstResponse = streamResponse([
      `id: 0\nevent: start\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 0,
        type: "start",
      })}\n\n`,
      `id: 1\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 1,
        type: "visible_delta",
        field: "room_utterance",
        delta: reply,
      })}\n\n`,
      `id: 2\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 2,
        type: "visible_delta",
        field: "case_detail.case_story",
        delta: dossier,
      })}\n\n`,
    ]);
    const replayResponse = streamResponse([
      `id: 0\nevent: start\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 0,
        type: "start",
      })}\n\n`,
      `id: 1\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 1,
        type: "visible_delta",
        field: "room_utterance",
        delta: reply,
      })}\n\n`,
      `id: 2\nevent: visible_delta\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 2,
        type: "visible_delta",
        field: "case_detail.case_story",
        delta: dossier,
      })}\n\n`,
      `id: 3\nevent: final\ndata: ${JSON.stringify({
        schemaVersion: "agent_stream.v1",
        runId,
        sequence: 3,
        type: "final",
        result: {},
      })}\n\n`,
    ]);
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(firstResponse)
      .mockResolvedValueOnce(replayResponse);

    const first = consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      replyThenBoard: true,
      descriptor: {
        runId,
        streamUrl: `/api/agent-runs/${runId}/events`,
      },
      fetchImpl,
    });
    await flushMicrotasks();
    const blocked = getAgentStreamRun(runId);
    expect(blocked.lastEventId).toBe("1");
    expect(blocked.fieldText["case_detail.case_story"]).toBeUndefined();

    abortAgentStream(runId);
    await first;

    const aborted = getAgentStreamRun(runId);
    expect(aborted.status).toBe("ABORTED");
    expect(aborted.lastEventId).toBe("1");
    expect(aborted.fieldText["case_detail.case_story"]).toBeUndefined();

    const replay = consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      replyThenBoard: true,
      descriptor: {
        runId,
        streamUrl: `/api/agent-runs/${runId}/events`,
      },
      fetchImpl,
    });
    await flushMicrotasks();
    await vi.advanceTimersByTimeAsync(1_500);
    await vi.runAllTimersAsync();
    await replay;

    const replayed = getAgentStreamRun(runId);
    expect(fetchImpl.mock.calls[1][0]).toContain("last_event_id=-1");
    expect(replayed.fieldText["case_detail.case_story"]).toBe(dossier);
    expect(replayed.lastEventId).toBe("3");
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
      replyThenBoard: true,
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

  it("replaces provisional text after a V3 generation reset in the same attempt", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      'id: v3:ATTEMPT_GEN:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v3","runId":"AGENT_RUN_GEN_RESET","attemptId":"ATTEMPT_GEN","sequence":0,"cursor":"v3:ATTEMPT_GEN:0","audience":"USER","payload":{"node":"turn"}}\n\n',
      'id: v3:ATTEMPT_GEN:1\nevent: visible_delta\ndata: {"protocol":"agent-stream.v3","runId":"AGENT_RUN_GEN_RESET","attemptId":"ATTEMPT_GEN","sequence":1,"cursor":"v3:ATTEMPT_GEN:1","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"旧生成文本"}}\n\n',
      'id: v3:ATTEMPT_GEN:2\nevent: generation_reset\ndata: {"protocol":"agent-stream.v3","runId":"AGENT_RUN_GEN_RESET","attemptId":"ATTEMPT_GEN","sequence":2,"cursor":"v3:ATTEMPT_GEN:2","audience":"USER","payload":{"node":"turn","generation":2,"reason_code":"OUTPUT_SCHEMA_INVALID"}}\n\n',
      'id: v3:ATTEMPT_GEN:3\nevent: visible_delta\ndata: {"protocol":"agent-stream.v3","runId":"AGENT_RUN_GEN_RESET","attemptId":"ATTEMPT_GEN","sequence":3,"cursor":"v3:ATTEMPT_GEN:3","audience":"USER","payload":{"node":"turn","field":"room_utterance","delta":"新生成文本"}}\n\n',
      'id: v3:ATTEMPT_GEN:4\nevent: final\ndata: {"protocol":"agent-stream.v3","runId":"AGENT_RUN_GEN_RESET","attemptId":"ATTEMPT_GEN","sequence":4,"cursor":"v3:ATTEMPT_GEN:4","audience":"USER","payload":{"final_result_ref":"urn:result:generation-reset","final_result_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}\n\n',
    ]));

    await consumeAgentRun({
      actor,
      caseId: "CASE_1",
      roomType: "INTAKE",
      descriptor: {
        runId: "AGENT_RUN_GEN_RESET",
        streamUrl: "/api/agent-runs/AGENT_RUN_GEN_RESET/events",
      },
      fetchImpl,
    });

    const run = getAgentStreamRun("AGENT_RUN_GEN_RESET");
    expect(run.content).toBe("新生成文本");
    expect(run.content).not.toContain("旧生成文本");
    expect(run.currentAttemptId).toBe("ATTEMPT_GEN");
    expect(run.resetCount).toBe(1);
    expect(run.lastEventId).toBe("v3:ATTEMPT_GEN:4");
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

  it("renders v3 Evidence provider deltas immediately and folds the committed frame", async () => {
    const runId = "AGENT_RUN_EVIDENCE_V3";
    const attemptId = "ATTEMPT_EVIDENCE_V3";
    const frameId = "EFRM_0123456789ABCDEF01234567";
    const frames = [
      `id: v3:${attemptId}:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":0,"cursor":"v3:${attemptId}:0","audience":"USER","payload":{"node":"evidence_turn"}}\n\n`,
      `event: public_frame_start\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":1,"audience":"USER","payload":{"frameId":"${frameId}","frameSequence":1,"frameType":"ROOM_WELCOME","publicHeader":{"frame_sequence":1,"frame_type":"ROOM_WELCOME"}}}\n\n`,
      `event: public_text_delta\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":2,"audience":"USER","payload":{"frameId":"${frameId}","frameSequence":1,"deltaIndex":0,"delta":"欢迎"}}\n\n`,
      `event: public_text_delta\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":3,"audience":"USER","payload":{"frameId":"${frameId}","frameSequence":1,"deltaIndex":1,"delta":"进入"}}\n\n`,
      `id: v3:${attemptId}:1\nevent: public_frame_start\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":1,"cursor":"v3:${attemptId}:1","audience":"USER","payload":{"frameId":"${frameId}","frameSequence":1,"frameType":"ROOM_WELCOME","publicHeader":{"frame_sequence":1,"frame_type":"ROOM_WELCOME"}}}\n\n`,
      `id: v3:${attemptId}:2\nevent: active_frame_snapshot\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":2,"cursor":"v3:${attemptId}:2","audience":"USER","payload":{"frameId":"${frameId}","frameSequence":1,"deltaIndex":2,"publicText":"欢迎进入"}}\n\n`,
      `id: v3:${attemptId}:3\nevent: public_frame_committed\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":3,"cursor":"v3:${attemptId}:3","audience":"USER","payload":{"frameId":"${frameId}","frameSequence":1,"durableCursor":"v3:${attemptId}:FRAME:1","headerSha256":"${"a".repeat(64)}","publicTextSha256":"${"b".repeat(64)}","frameSha256":"${"c".repeat(64)}","publicTextChars":4}}\n\n`,
      `id: v3:${attemptId}:4\nevent: final\ndata: {"protocol":"agent-stream.v3","runId":"${runId}","attemptId":"${attemptId}","sequence":4,"cursor":"v3:${attemptId}:4","audience":"USER","payload":{"finalResultRef":"urn:target-e2e:result:${runId}","finalResultHash":"${"d".repeat(64)}"}}\n\n`,
    ];
    const visiblePrefixes = [];

    await consumeAgentRun({
      actor,
      caseId: "CASE_EVIDENCE_V3",
      roomType: "EVIDENCE",
      descriptor: { runId, streamUrl: `/api/agent-runs/${runId}/events` },
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frames)),
      onEvent: (_event, run) => visiblePrefixes.push(run.content),
    });

    const run = getAgentStreamRun(runId);
    expect(visiblePrefixes).toContain("欢迎");
    expect(visiblePrefixes).toContain("欢迎进入");
    expect(run.content).toBe("欢迎进入");
    expect(run.frames[frameId]).toMatchObject({
      status: "COMMITTED",
      nextDeltaIndex: 2,
      durableCursor: `v3:${attemptId}:FRAME:1`,
    });
    expect(run.lastEventId).toBe(`v3:${attemptId}:4`);
  });

  it("reduces interleaved V4 Intake lanes and resets only the failed lane", async () => {
    const runId = "AGENT_RUN_INTAKE_V4";
    const attemptId = "ATTEMPT_INTAKE_V4";
    const dialogueFrameId = "FRAME_DIALOGUE_1";
    const dossierFrameId = "FRAME_DOSSIER_1";
    const replacementDossierFrameId = "FRAME_DOSSIER_2";
    const qualityFrameId = "FRAME_QUALITY_1";
    const hash = "a".repeat(64);
    const frames = [
      v4SseFrame(runId, attemptId, 0, "public_frame_start", {
        frame_id: dialogueFrameId,
        frame_type: "DIALOGUE_FRAME",
        generation: 1,
        frame_set_receipt_id: "FRAME_SET_RECEIPT_1",
        projection_registry_version: "intake-projection-registry.v1",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 1, "public_frame_start", {
        frame_id: dossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 1,
        frame_set_receipt_id: "FRAME_SET_RECEIPT_1",
        projection_registry_version: "intake-projection-registry.v1",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 2, "public_frame_start", {
        frame_id: qualityFrameId,
        frame_type: "QUALITY_FRAME",
        generation: 1,
        frame_set_receipt_id: "FRAME_SET_RECEIPT_1",
        projection_registry_version: "intake-projection-registry.v1",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 3, "public_frame_projection_item", {
        frame_id: dialogueFrameId,
        frame_type: "DIALOGUE_FRAME",
        generation: 1,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 0,
        next_local_index: 1,
        canonical_item_id: "DSEG_01",
        projection_kind: "ACKNOWLEDGEMENT",
        projection_path_id: "intake.dialogue.public_segments",
        value_kind: "TEXT",
        public_text: "已记录本轮补充。",
        item_sha256: hash,
      }),
      v4SseFrame(runId, attemptId, 4, "public_frame_projection_item", {
        frame_id: dossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 1,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 0,
        next_local_index: 1,
        canonical_item_id: "DPATCH_OLD",
        projection_kind: "CURRENT_FACT",
        projection_path_id: "case_story.one_sentence_summary",
        value_kind: "JSON_VALUE",
        canonical_value_json: JSON.stringify("旧事实"),
        item_sha256: hash,
      }),
      v4SseFrame(runId, attemptId, 5, "public_frame_projection_item", {
        frame_id: qualityFrameId,
        frame_type: "QUALITY_FRAME",
        generation: 1,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 0,
        next_local_index: 1,
        canonical_item_id: "QMETRIC_01",
        projection_kind: "DIMENSION_SCORE",
        projection_path_id: "intake.quality.scores.references",
        value_kind: "JSON_VALUE",
        canonical_value_json: "12",
        item_sha256: hash,
      }),
      ...[
        ["event_story", 18],
        ["party_positions", 18],
        ["requested_resolution", 14],
        ["risk_and_conflicts", 13],
        ["next_action_clarity", 12],
      ].map(([dimension, value], offset) => v4SseFrame(
        runId,
        attemptId,
        6 + offset,
        "public_frame_projection_item",
        {
          frame_id: qualityFrameId,
          frame_type: "QUALITY_FRAME",
          generation: 1,
          delivery_class: "DURABLE_PREVIEW",
          local_index: 1 + offset,
          next_local_index: 2 + offset,
          canonical_item_id: `QMETRIC_${dimension.toUpperCase()}`,
          projection_kind: "DIMENSION_SCORE",
          projection_path_id: `intake.quality.scores.${dimension}`,
          value_kind: "JSON_VALUE",
          canonical_value_json: String(value),
          item_sha256: hash,
        },
      )),
      v4SseFrame(runId, attemptId, 11, "public_frame_projection_item", {
        frame_id: qualityFrameId,
        frame_type: "QUALITY_FRAME",
        generation: 1,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 6,
        next_local_index: 7,
        canonical_item_id: "QGAP_REFERENCES",
        projection_kind: "BLOCKING_GAP",
        projection_path_id: "intake.quality.gaps.references",
        value_kind: "JSON_VALUE",
        canonical_value_json: JSON.stringify({
          dimension: "REFERENCES",
          question: "请补充第三方检测报告的机构名称？",
          source_role: "USER",
          linked_fact_keys: ["FACT_01"],
        }),
        item_sha256: hash,
      }),
      v4SseFrame(runId, attemptId, 12, "public_frame_interrupted", {
        frame_id: dossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 1,
        delivery_class: "DURABLE_CONTROL",
        next_local_index: 1,
        reason_code: "OUTPUT_SCHEMA_INVALID",
        retryable: true,
      }),
      v4SseFrame(runId, attemptId, 13, "frame_generation_reset", {
        frame_type: "DOSSIER_FRAME",
        old_frame_id: dossierFrameId,
        new_frame_id: replacementDossierFrameId,
        old_generation: 1,
        new_generation: 2,
        reason_code: "OUTPUT_SCHEMA_INVALID",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 14, "public_frame_start", {
        frame_id: replacementDossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 2,
        frame_set_receipt_id: "FRAME_SET_RECEIPT_1",
        projection_registry_version: "intake-projection-registry.v1",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 15, "public_frame_projection_item", {
        frame_id: replacementDossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 2,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 0,
        next_local_index: 1,
        canonical_item_id: "DPATCH_NEW",
        projection_kind: "CURRENT_FACT",
        projection_path_id: "case_story.one_sentence_summary",
        value_kind: "JSON_VALUE",
        canonical_value_json: JSON.stringify("新事实"),
        item_sha256: hash,
      }),
      v4SseFrame(runId, attemptId, 16, "public_frame_projection_item", {
        frame_id: replacementDossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 2,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 1,
        next_local_index: 2,
        canonical_item_id: "DPATCH_NEW_2",
        projection_kind: "CURRENT_FACT",
        projection_path_id: "case_story.one_sentence_summary",
        value_kind: "JSON_VALUE",
        canonical_value_json: JSON.stringify("补充事实"),
        item_sha256: hash,
      }),
      v4SseFrame(runId, attemptId, 17, "usage", {
        frame_type: "DIALOGUE_FRAME",
        generation: 1,
        usage: { input_tokens: 100, output_tokens: 20, total_tokens: 120 },
        delivery_class: "DURABLE_STAGING",
      }),
      v4SseFrame(runId, attemptId, 18, "public_frame_sealed", {
        frame_id: dialogueFrameId,
        frame_type: "DIALOGUE_FRAME",
        generation: 1,
        delivery_class: "DURABLE_STAGING",
        frame_receipt_id: "DIALOGUE_RECEIPT_1",
        next_local_index: 1,
        result_sha256: hash,
        public_projection_sha256: "b".repeat(64),
      }),
      v4SseFrame(runId, attemptId, 19, "usage", {
        frame_type: "DOSSIER_FRAME",
        generation: 2,
        usage: { input_tokens: 110, output_tokens: 30, total_tokens: 140 },
        delivery_class: "DURABLE_STAGING",
      }),
      v4SseFrame(runId, attemptId, 20, "public_frame_sealed", {
        frame_id: replacementDossierFrameId,
        frame_type: "DOSSIER_FRAME",
        generation: 2,
        delivery_class: "DURABLE_STAGING",
        frame_receipt_id: "DOSSIER_RECEIPT_2",
        next_local_index: 2,
        result_sha256: hash,
        public_projection_sha256: "b".repeat(64),
      }),
      v4SseFrame(runId, attemptId, 21, "usage", {
        frame_type: "QUALITY_FRAME",
        generation: 1,
        usage: { input_tokens: 90, output_tokens: 10, total_tokens: 100 },
        delivery_class: "DURABLE_STAGING",
      }),
      v4SseFrame(runId, attemptId, 22, "public_frame_sealed", {
        frame_id: qualityFrameId,
        frame_type: "QUALITY_FRAME",
        generation: 1,
        delivery_class: "DURABLE_STAGING",
        frame_receipt_id: "QUALITY_RECEIPT_1",
        next_local_index: 7,
        result_sha256: hash,
        public_projection_sha256: "b".repeat(64),
      }),
      v4SseFrame(runId, attemptId, 23, "final", {
        delivery_class: "DURABLE_TERMINAL",
        final_receipt_id: "FINAL_RECEIPT_1",
        final_result_hash: "c".repeat(64),
      }),
    ];
    const observed = [];

    await consumeAgentRun({
      actor,
      caseId: "CASE_INTAKE_V4",
      roomType: "INTAKE",
      descriptor: { runId, streamUrl: `/api/agent-runs/${runId}/events` },
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frames)),
      onEvent: (event, run) => observed.push({
        event: event.event,
        frameType: event.frameType,
        content: run.content,
      }),
    });

    const run = getAgentStreamRun(runId);
    expect(observed).toContainEqual(expect.objectContaining({
      event: "public_frame_projection_item",
      frameType: "DIALOGUE_FRAME",
      content: "已记录本轮补充。",
    }));
    expect(run.content).toBe("已记录本轮补充。");
    expect(run.currentAttemptId).toBe(attemptId);
    expect(run.parallelFrameIds).toMatchObject({
      DIALOGUE_FRAME: dialogueFrameId,
      DOSSIER_FRAME: replacementDossierFrameId,
      QUALITY_FRAME: qualityFrameId,
    });
    expect(run.frames[dossierFrameId].status).toBe("RESET");
    expect(run.frames[replacementDossierFrameId].items.DPATCH_NEW.value).toBe("新事实");
    expect(run.frames[replacementDossierFrameId].items.DPATCH_NEW_2.value).toBe("补充事实");
    expect(run.frames[qualityFrameId].items.QMETRIC_01.value).toBe(12);
    expect(run.frames[qualityFrameId].items.QGAP_REFERENCES.value.question)
      .toBe("请补充第三方检测报告的机构名称？");
    expect(run.frames[dialogueFrameId].status).toBe("SEALED");
    expect(run.usage).toEqual({
      inputTokens: 300,
      outputTokens: 60,
      totalTokens: 360,
    });
    expect(run.usageByFrame.DOSSIER_FRAME).toEqual({
      inputTokens: 110,
      outputTokens: 30,
      totalTokens: 140,
    });
    expect(run.lastEventId).toBe(`v4:${attemptId}:23`);
  });

  it("rejects a V4 Dossier item outside the exact current-facts projection", async () => {
    const runId = "AGENT_RUN_INTAKE_V4_FOREIGN_DOSSIER_PATH";
    const attemptId = "ATTEMPT_INTAKE_V4_FOREIGN_DOSSIER_PATH";
    const frames = [
      v4SseFrame(runId, attemptId, 0, "public_frame_start", {
        frame_id: "FRAME_DOSSIER_FOREIGN_PATH",
        frame_type: "DOSSIER_FRAME",
        generation: 1,
        frame_set_receipt_id: "FRAME_SET_FOREIGN_PATH",
        projection_registry_version: "intake-projection-registry.v1",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 1, "public_frame_projection_item", {
        frame_id: "FRAME_DOSSIER_FOREIGN_PATH",
        frame_type: "DOSSIER_FRAME",
        generation: 1,
        delivery_class: "DURABLE_PREVIEW",
        local_index: 0,
        next_local_index: 1,
        canonical_item_id: "DPATCH_FOREIGN_PATH",
        projection_kind: "CURRENT_FACT",
        projection_path_id: "case_story.current_facts",
        value_kind: "JSON_VALUE",
        canonical_value_json: JSON.stringify(["越权事实数组"]),
        item_sha256: "a".repeat(64),
      }),
    ];

    await expect(consumeAgentRun({
      actor,
      caseId: "CASE_INTAKE_V4_FOREIGN_DOSSIER_PATH",
      roomType: "INTAKE",
      descriptor: { runId, streamUrl: `/api/agent-runs/${runId}/events` },
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frames)),
      reconnectAttempts: 0,
    })).rejects.toMatchObject({ code: "AGENT_STREAM_V4_PROJECTION_CONTRACT_INVALID" });
  });

  it("rejects a V4 final before all three current lanes are sealed", async () => {
    const runId = "AGENT_RUN_INTAKE_V4_EARLY_FINAL";
    const attemptId = "ATTEMPT_INTAKE_V4_EARLY_FINAL";
    const frames = [
      v4SseFrame(runId, attemptId, 0, "public_frame_start", {
        frame_id: "FRAME_DIALOGUE_EARLY",
        frame_type: "DIALOGUE_FRAME",
        generation: 1,
        frame_set_receipt_id: "FRAME_SET_EARLY",
        projection_registry_version: "intake-projection-registry.v1",
        delivery_class: "DURABLE_CONTROL",
      }),
      v4SseFrame(runId, attemptId, 1, "final", {
        delivery_class: "DURABLE_TERMINAL",
        final_receipt_id: "FINAL_RECEIPT_EARLY",
        final_result_hash: "c".repeat(64),
      }),
    ];

    await expect(consumeAgentRun({
      actor,
      caseId: "CASE_INTAKE_V4_EARLY_FINAL",
      roomType: "INTAKE",
      descriptor: { runId, streamUrl: `/api/agent-runs/${runId}/events` },
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frames)),
      reconnectAttempts: 0,
    })).rejects.toMatchObject({ code: "AGENT_STREAM_V4_FINAL_BEFORE_EXACT_THREE" });
  });

  it("rejects a V4 lane that is not bound to the registered Intake projection contract", async () => {
    const runId = "AGENT_RUN_INTAKE_V4_FOREIGN_REGISTRY";
    const attemptId = "ATTEMPT_INTAKE_V4_FOREIGN_REGISTRY";
    const frames = [v4SseFrame(runId, attemptId, 0, "public_frame_start", {
      frame_id: "FRAME_DIALOGUE_FOREIGN",
      frame_type: "DIALOGUE_FRAME",
      generation: 1,
      frame_set_receipt_id: "FRAME_SET_RECEIPT_FOREIGN",
      projection_registry_version: "foreign-projection-registry.v1",
      delivery_class: "DURABLE_CONTROL",
    })];

    await expect(consumeAgentRun({
      actor,
      caseId: "CASE_INTAKE_V4_FOREIGN_REGISTRY",
      roomType: "INTAKE",
      descriptor: { runId, streamUrl: `/api/agent-runs/${runId}/events` },
      fetchImpl: vi.fn().mockResolvedValue(streamResponse(frames)),
      reconnectAttempts: 0,
    })).rejects.toMatchObject({ code: "AGENT_STREAM_V4_FRAME_START_INVALID" });
  });

  it("keeps the stable server diagnostic code in the visible error status", async () => {
    const runId = "AGENT_RUN_EVIDENCE_CONTRACT_ERROR";
    const attemptId = "ATTEMPT_EVIDENCE_CONTRACT_ERROR";
    const diagnosticCode = "EVIDENCE_MODEL_INVOCATION_CONTRACT_INVALID";
    const onError = vi.fn();
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse([
      `id: v2:${attemptId}:0\nevent: attempt_started\ndata: {"protocol":"agent-stream.v2","runId":"${runId}","attemptId":"${attemptId}","sequence":0,"cursor":"v2:${attemptId}:0","audience":"USER","payload":{"node":"evidence_turn"}}\n\n`,
      `id: v2:${attemptId}:1\nevent: error\ndata: {"protocol":"agent-stream.v2","runId":"${runId}","attemptId":"${attemptId}","sequence":1,"cursor":"v2:${attemptId}:1","audience":"USER","payload":{"errorCode":"${diagnosticCode}","retryable":false}}\n\n`,
    ]));

    await expect(consumeAgentRun({
      actor,
      caseId: "CASE_EVIDENCE_CONTRACT_ERROR",
      roomType: "EVIDENCE",
      descriptor: {
        runId,
        streamUrl: `/api/agent-runs/${runId}/events`,
      },
      fetchImpl,
      onError,
    })).rejects.toMatchObject({
      code: diagnosticCode,
      retryable: false,
      message: expect.stringContaining(`诊断码：${diagnosticCode}`),
    });

    const run = getAgentStreamRun(runId);
    expect(run.status).toBe("ERROR");
    expect(run.error.code).toBe(diagnosticCode);
    expect(run.error.message).toContain(`诊断码：${diagnosticCode}`);
    expect(onError).toHaveBeenCalledWith(run.error, run);
  });
});
