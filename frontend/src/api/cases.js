// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, newIdempotencyKey } from "./client";

// 业务位置：【前端 API/SSE 适配】queryString：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
function queryString(filters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (key === "case_type") return;
    if (value !== "" && value !== null && value !== undefined) {
      params.set(key, String(value));
    }
  });
  return params.toString();
}

export const caseApi = {
  list: (actor, filters = {}) =>
    apiRequest(`/disputes?${queryString(filters)}`, actor),
  get: (actor, caseId) => apiRequest(`/disputes/${caseId}`, actor),
  dossier: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/evidence-dossiers/latest`, actor),
  hearing: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing`, actor),
  draft: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing`, actor),
  remedy: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/remedy-plan`, actor),
  actions: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/actions`, actor),
  evaluation: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/evaluation`, actor),
  auditLogs: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/audit-logs`, actor),
  uploadEvidence: (
    actor,
    caseId,
    file,
    { evidenceType, sourceType, visibility },
  ) => {
    const body = new FormData();
    body.append("file", file);
    body.append("evidence_type", evidenceType);
    body.append("source_type", sourceType);
    body.append("visibility", visibility);
    return apiRequest(`/disputes/${caseId}/evidence`, actor, {
      method: "POST",
      body,
    });
  },
  submitEvidence: (actor, caseId, party, command) =>
    apiRequest(`/disputes/${caseId}/rooms/EVIDENCE/messages`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey(`message-${party}`) },
      body: JSON.stringify({
        message_type: "PARTY_EVIDENCE_REFERENCE",
        text: command.text ?? command.submission_text ?? "Evidence submitted.",
        attachment_refs: command.evidence_refs ?? command.evidence_ids ?? [],
      }),
    }),
};
