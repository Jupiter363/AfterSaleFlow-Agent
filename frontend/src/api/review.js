// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, newIdempotencyKey } from "./client";

export const reviewApi = {
  list: (actor, status = "PENDING") =>
    apiRequest(`/reviews?status=${status}`, actor),
  packet: (actor, taskId) =>
    apiRequest(`/reviews/${taskId}/packet`, actor),
  queryCopilot: (actor, taskId, question) =>
    apiRequest(`/reviews/${taskId}/copilot/query`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("review-copilot") },
      body: JSON.stringify({ question }),
    }),
  activeCopilotRuns: (actor, taskId) =>
    apiRequest(`/reviews/${taskId}/copilot/active`, actor),
  decide: (actor, taskId, command) =>
    apiRequest(`/reviews/${taskId}/decision`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("review") },
      body: JSON.stringify(command),
    }),
};
