import { apiRequest, newIdempotencyKey } from "./client";

export const reviewApi = {
  list: (actor, status = "PENDING") =>
    apiRequest(`/reviews?status=${status}`, actor),
  packet: (actor, taskId) =>
    apiRequest(`/reviews/${taskId}/packet`, actor),
  decide: (actor, taskId, command) =>
    apiRequest(`/reviews/${taskId}/decision`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("review") },
      body: JSON.stringify(command),
    }),
};
