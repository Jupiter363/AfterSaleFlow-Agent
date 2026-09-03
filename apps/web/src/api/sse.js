// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

// 业务位置：【前端 API/SSE 适配】parseSseBlock：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function parseSseBlock(block) {
  let id = null;
  let event = "message";
  const dataLines = [];

  for (const rawLine of String(block || "").split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(":")) continue;
    const separator = rawLine.indexOf(":");
    const field = separator === -1 ? rawLine : rawLine.slice(0, separator);
    const value =
      separator === -1
        ? ""
        : rawLine.slice(separator + 1).replace(/^ /, "");
    if (field === "id") id = value;
    if (field === "event") event = value;
    if (field === "data") dataLines.push(value);
  }

  if (!dataLines.length) return null;
  const rawData = dataLines.join("\n");
  const numericId = Number(id);
  return {
    id: id !== null && Number.isFinite(numericId) ? numericId : id,
    event,
    data: JSON.parse(rawData),
  };
}

// 业务位置：【前端 API/SSE 适配】consumeSse：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export async function consumeSse({
  url,
  actor,
  lastEventId = 0,
  onEvent,
  fetchImpl = globalThis.fetch,
  signal,
}) {
  const response = await fetchImpl(url, {
    headers: {
      Accept: "text/event-stream",
      "Last-Event-ID": String(lastEventId || 0),
      "X-Role": actor.role,
      "X-User-Id": actor.id,
    },
    signal,
  });
  if (!response.ok || !response.body) {
    const error = new Error(`流式连接失败（HTTP ${response.status}）`);
    error.code = "AGENT_STREAM_CONNECTION_FAILED";
    error.status = response.status;
    throw error;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let cursor = lastEventId || 0;

  // 业务位置：【前端 API/SSE 适配】dispatch：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  const dispatch = async (block) => {
    const parsed = parseSseBlock(block);
    if (!parsed) return true;
    if (parsed.id !== null && parsed.id !== undefined) cursor = parsed.id;
    return (await onEvent?.(parsed)) !== false;
  };

  let readerFinished = false;
  try {
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || "";
      for (const block of blocks) {
        if (!(await dispatch(block))) {
          await reader.cancel();
          readerFinished = true;
          return cursor;
        }
      }
      if (done) {
        readerFinished = true;
        break;
      }
    }

    if (buffer.trim()) await dispatch(buffer);
    return cursor;
  } finally {
    if (!readerFinished) {
      try {
        await reader.cancel();
      } catch {
        // The transport may already be closed after an abort or parse error.
      }
    }
  }
}
