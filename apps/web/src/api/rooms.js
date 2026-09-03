// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, apiUrl, newIdempotencyKey } from "./client";
import { consumeSse, parseSseBlock } from "./sse";

export const roomApi = {
  messages: (actor, caseId, roomType) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/messages`, actor),

  latestTurnMemory: (actor, caseId, roomType) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/turn-memory/latest`, actor),

  events: (actor, caseId, afterSequence = 0) =>
    apiRequest(`/disputes/${caseId}/events/replay?after_sequence=${afterSequence}`, actor),

  ensureOpening: (
    actor,
    caseId,
    roomType,
    idempotencyKey = newIdempotencyKey("room-opening"),
  ) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/messages/opening`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  postMessage: (
    actor,
    caseId,
    roomType,
    command,
    idempotencyKey = newIdempotencyKey("room-message"),
  ) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/messages`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(command),
    }),
};

export { parseSseBlock };

// 业务位置：【前端 API/SSE 适配】consumeCaseEvents：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export async function consumeCaseEvents({
  actor,
  caseId,
  lastEventId = 0,
  onEvent,
  fetchImpl = globalThis.fetch,
  signal,
}) {
  return consumeSse({
    actor,
    lastEventId,
    onEvent,
    fetchImpl,
    signal,
    url: apiUrl(`/disputes/${caseId}/events?last_event_id=${lastEventId}`),
  });
}
