import { apiRequest, newIdempotencyKey } from "./client";

export const reviewApi = {
  list: (actor, status = "PENDING") =>
    apiRequest(`/v1/review-tasks?status=${status}`, actor),
  packet: (actor, taskId) =>
    apiRequest(`/v1/review-tasks/${taskId}/packet`, actor),
  decide: (actor, taskId, command) =>
    apiRequest(`/v1/review-tasks/${taskId}/decision`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("review") },
      body: JSON.stringify(command),
    }),
};
