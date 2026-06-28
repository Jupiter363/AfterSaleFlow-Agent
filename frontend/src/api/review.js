const baseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

async function request(path, actor, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-User-Id": actor.id,
      "X-Role": actor.role,
      ...(options.headers || {}),
    },
  });
  const payload = await response.json();
  if (!response.ok || !payload.success) {
    throw new Error(payload.message || "请求失败");
  }
  return payload.data;
}

export const reviewApi = {
  list: (actor, status = "PENDING") =>
    request(`/v1/review-tasks?status=${status}`, actor),
  packet: (actor, taskId) =>
    request(`/v1/review-tasks/${taskId}/packet`, actor),
  decide: (actor, taskId, command) =>
    request(`/v1/review-tasks/${taskId}/decision`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(command),
    }),
};
