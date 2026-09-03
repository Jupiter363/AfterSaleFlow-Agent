// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

const baseUrl = import.meta.env.VITE_API_BASE_URL || "/api";

// 业务位置：【前端 API/SSE 适配】apiUrl：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function apiUrl(path) {
  return `${baseUrl}${path}`;
}

// 业务位置：【前端 API/SSE 适配】newIdempotencyKey：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function newIdempotencyKey(prefix = "web") {
  return `${prefix}-${crypto.randomUUID()}`;
}

// 业务位置：【前端 API/SSE 适配】apiRequest：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
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
      // 业务位置：【前端 API/SSE 适配】abortFromCaller：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
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

// 业务位置：【前端 API/SSE 适配】optional：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function optional(promise, fallback = null) {
  return promise.catch((error) => {
    if (["CASE_NOT_FOUND", "EVIDENCE_NOT_FOUND"].includes(error.code)) {
      return fallback;
    }
    throw error;
  });
}
