import { apiRequest, newIdempotencyKey } from "./client";

function queryString(filters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== "" && value !== null && value !== undefined) {
      params.set(key, String(value));
    }
  });
  return params.toString();
}

export const caseApi = {
  list: (actor, filters = {}) =>
    apiRequest(`/v1/cases?${queryString(filters)}`, actor),
  get: (actor, caseId) => apiRequest(`/v1/cases/${caseId}`, actor),
  dossier: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/dossier`, actor),
  hearing: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/hearing`, actor),
  draft: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/adjudication-draft`, actor),
  remedy: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/remedy-plan`, actor),
  actions: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/actions`, actor),
  evaluation: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/evaluation`, actor),
  auditLogs: (actor, caseId) =>
    apiRequest(`/v1/cases/${caseId}/audit-logs`, actor),
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
    return apiRequest(`/v1/cases/${caseId}/evidences`, actor, {
      method: "POST",
      body,
    });
  },
  submitEvidence: (actor, caseId, party, command) =>
    apiRequest(`/v1/cases/${caseId}/submissions/${party}`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey(`submission-${party}`) },
      body: JSON.stringify(command),
    }),
};
