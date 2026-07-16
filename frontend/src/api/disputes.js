// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, newIdempotencyKey } from "./client";

// 业务位置：【前端 API/SSE 适配】queryString：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
function queryString(filters = {}) {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== "" && value != null) query.set(key, value);
  });
  return query.toString();
}

export const disputeApi = {
  create: (actor, command) =>
    apiRequest("/disputes", actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("dispute") },
      body: JSON.stringify(command),
    }),
  list: (actor, filters = {}) =>
    apiRequest(`/disputes?${queryString(filters)}`, actor),
  get: (actor, caseId) => apiRequest(`/disputes/${caseId}`, actor),
  outcome: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/outcome`, actor),
  confirmOutcomeDraft: (actor, caseId, reason) =>
    apiRequest(`/disputes/${caseId}/outcome/review/confirm`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("outcome-confirm") },
      body: JSON.stringify({ reason }),
    }),
  modifyOutcomeDraft: (actor, caseId, reason, approvedPlan) =>
    apiRequest(`/disputes/${caseId}/outcome/review/modify`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("outcome-modify") },
      body: JSON.stringify({
        reason,
        approved_plan: approvedPlan,
      }),
    }),
  actions: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/actions`, actor),
  confirmIntake: (actor, caseId, command) =>
    apiRequest(`/disputes/${caseId}/intake/confirm`, actor, {
      method: "POST",
      body: JSON.stringify(command),
    }),
  intakeStatus: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/intake/status`, actor),
  cancelIntake: (actor, caseId, reason = "") =>
    apiRequest(`/disputes/${caseId}/intake/cancel`, actor, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
  simulateExternalImport: (actor, command) => {
    const idempotencyKey = newIdempotencyKey("external-import");
    return apiRequest("/disputes/import/simulate", actor, {
      method: "POST",
      timeoutMs: 15_000,
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({
        ...command,
        simulation_batch_id: command.simulation_batch_id || idempotencyKey,
      }),
    });
  },
  deleteSimulatedCase: (actor, caseId) =>
    apiRequest(`/disputes/${encodeURIComponent(caseId)}`, actor, {
      method: "DELETE",
    }),
};
