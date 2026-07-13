// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, newIdempotencyKey } from "./client";

export const hearingApi = {
  hearing: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing`, actor),
  complete: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing/complete`, actor, {
      method: "POST",
    }),
  rounds: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing/rounds`, actor),
  completeRound: (actor, caseId, command) =>
    apiRequest(`/disputes/${caseId}/hearing/rounds/complete`, actor, {
      method: "POST",
      body: JSON.stringify(command),
    }),
  submitRound: (actor, caseId, command) =>
    apiRequest(`/disputes/${caseId}/hearing/rounds/current/submissions`, actor, {
      method: "POST",
      body: JSON.stringify(command),
    }),
  settlements: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing/settlements`, actor),
  proposeSettlement: (actor, caseId, command) =>
    apiRequest(`/disputes/${caseId}/hearing/settlements`, actor, {
      method: "POST",
      body: JSON.stringify(command),
    }),
  confirmSettlement: (
    actor,
    caseId,
    version,
    idempotencyKey = newIdempotencyKey("settlement"),
  ) =>
    apiRequest(
      `/disputes/${caseId}/hearing/settlements/${version}/confirm`,
      actor,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
      },
    ),
  agentRuns: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/agent-runs`, actor),
};
