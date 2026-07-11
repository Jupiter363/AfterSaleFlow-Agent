const baseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

export function apiUrl(path) {
  return `${baseUrl}${path}`;
}

export function newIdempotencyKey(prefix = "web") {
  return `${prefix}-${crypto.randomUUID()}`;
}

export async function apiRequest(path, actor, options = {}) {
  const { timeoutMs, signal: callerSignal, ...requestOptions } = options;
  const headers = {
    "X-User-Id": actor.id,
    "X-Role": actor.role,
    ...(requestOptions.headers || {}),
  };
  if (requestOptions.body && !(requestOptions.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }
  let timeoutId;
  let timeoutFailure;
  let removeCallerAbortListener;
  let requestSignal = callerSignal;

  if (Number.isFinite(timeoutMs) && timeoutMs > 0) {
    const timeoutController = new AbortController();
    requestSignal = timeoutController.signal;

    if (callerSignal) {
      const abortFromCaller = () => timeoutController.abort(callerSignal.reason);
      if (callerSignal.aborted) {
        abortFromCaller();
      } else {
        callerSignal.addEventListener("abort", abortFromCaller, { once: true });
        removeCallerAbortListener = () =>
          callerSignal.removeEventListener("abort", abortFromCaller);
      }
    }

    timeoutId = setTimeout(() => {
      if (timeoutController.signal.aborted) return;
      timeoutFailure = new Error("请求超时，请稍后重试");
      timeoutFailure.code = "REQUEST_TIMEOUT";
      timeoutController.abort(timeoutFailure);
    }, timeoutMs);
  }

  try {
    const response = await fetch(apiUrl(path), {
      ...requestOptions,
      ...(requestSignal ? { signal: requestSignal } : {}),
      headers,
    });
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
  } catch (error) {
    if (timeoutFailure) throw timeoutFailure;
    throw error;
  } finally {
    if (timeoutId !== undefined) clearTimeout(timeoutId);
    removeCallerAbortListener?.();
  }
}

export function optional(promise, fallback = null) {
  return promise.catch((error) => {
    if (["CASE_NOT_FOUND", "EVIDENCE_NOT_FOUND"].includes(error.code)) {
      return fallback;
    }
    throw error;
  });
}
