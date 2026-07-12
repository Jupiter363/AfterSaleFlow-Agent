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
  upload: (actor, caseId, {
    file,
    evidenceType,
    sourceType,
    visibility,
    occurredAt,
    modelProcessingAuthorized = false,
  }) => {
    const form = new FormData();
    form.append("file", file);
    form.append("evidence_type", evidenceType);
    form.append("source_type", sourceType);
    form.append("visibility", visibility);
    form.append("model_processing_authorized", String(modelProcessingAuthorized));
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
  submitBatch: (
    actor,
    caseId,
    command,
    idempotencyKey = newIdempotencyKey("evidence-batch"),
  ) =>
    apiRequest(`/disputes/${caseId}/evidence/submissions`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(command),
    }),
  deletePending: (actor, caseId, evidenceId) =>
    apiRequest(`/disputes/${caseId}/evidence/${evidenceId}`, actor, {
      method: "DELETE",
    }),
  complete: (actor, caseId, idempotencyKey = newIdempotencyKey("evidence")) =>
    apiRequest(`/disputes/${caseId}/evidence/complete`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    }),
};
