import { apiRequest, newIdempotencyKey } from "./client";

export const hearingApi = {
  hearing: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/hearing`, actor),
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
