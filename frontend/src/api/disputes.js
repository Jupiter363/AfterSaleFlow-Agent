import { apiRequest, newIdempotencyKey } from "./client";

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
  actions: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/actions`, actor),
  confirmIntake: (actor, caseId, command) =>
    apiRequest(`/disputes/${caseId}/intake/confirm`, actor, {
      method: "POST",
      body: JSON.stringify(command),
    }),
  cancelIntake: (actor, caseId, reason = "") =>
    apiRequest(`/disputes/${caseId}/intake/cancel`, actor, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
  simulateExternalImport: (actor, command) => {
    const idempotencyKey = newIdempotencyKey("external-import");
    return apiRequest("/disputes/import/simulate", actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({
        ...command,
        simulation_batch_id: command.simulation_batch_id || idempotencyKey,
      }),
    });
  },
};
