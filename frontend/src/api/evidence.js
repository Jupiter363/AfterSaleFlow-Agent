import { apiRequest, newIdempotencyKey, optional } from "./client";

export const evidenceApi = {
  dossier: (actor, caseId, version = "latest") =>
    optional(
      apiRequest(`/disputes/${caseId}/evidence-dossiers/${version}`, actor),
      null,
    ),
  catalog: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/evidence`, actor),
  completion: (actor, caseId) =>
    apiRequest(`/disputes/${caseId}/evidence/completion`, actor),
  upload: (actor, caseId, { file, evidenceType, sourceType, visibility, occurredAt }) => {
    const form = new FormData();
    form.append("file", file);
    form.append("evidence_type", evidenceType);
    form.append("source_type", sourceType);
    form.append("visibility", visibility);
    if (occurredAt) form.append("occurred_at", occurredAt);
    return apiRequest(`/disputes/${caseId}/evidence`, actor, {
      method: "POST",
      body: form,
    });
  },
  verify: (actor, caseId, evidenceId, command) =>
    apiRequest(`/disputes/${caseId}/evidence/${evidenceId}/verify`, actor, {
      method: "POST",
      body: JSON.stringify(command),
    }),
  complete: (actor, caseId, idempotencyKey = newIdempotencyKey("evidence")) =>
    apiRequest(`/disputes/${caseId}/evidence/complete`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    }),
};
