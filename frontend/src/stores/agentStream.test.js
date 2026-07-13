// 文件作用：自动化测试文件，验证 agentStream.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearAgentStreams,
  consumeAgentRun,
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
});
