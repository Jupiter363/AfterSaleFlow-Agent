const baseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

export function newIdempotencyKey(prefix = "web") {
  return `${prefix}-${crypto.randomUUID()}`;
}

export async function apiRequest(path, actor, options = {}) {
  const headers = {
    "X-User-Id": actor.id,
    "X-Role": actor.role,
    ...(options.headers || {}),
  };
  if (options.body && !(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }
  const response = await fetch(`${baseUrl}${path}`, { ...options, headers });
  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error(`服务返回了不可解析的响应（HTTP ${response.status}）`);
  }
  if (!response.ok || !payload.success) {
    const error = new Error(payload.message || "请求失败，请稍后重试");
    error.code = payload.code;
    error.details = payload.details;
    throw error;
  }
  return payload.data;
}

export function optional(promise, fallback = null) {
  return promise.catch((error) => {
    if (["CASE_NOT_FOUND", "EVIDENCE_NOT_FOUND"].includes(error.code)) {
      return fallback;
    }
    throw error;
  });
}
