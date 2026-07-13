// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, apiUrl } from "./client";
import { consumeSse } from "./sse";

export const AGENT_STREAM_SCHEMA_VERSION = "agent_stream.v1";
const TERMINAL_EVENTS = new Set(["final", "error"]);
const AGENT_STREAM_EVENTS = new Set([
  "start",
  "visible_delta",
  "usage",
  "final",
  "error",
]);

// 业务位置：【前端 API/SSE 适配】firstDefined：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
function firstDefined(...values) {
  return values.find((value) => value !== undefined && value !== null);
}

// 业务位置：【前端 API/SSE 适配】loadActiveAgentRuns：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function loadActiveAgentRuns(actor, caseId, roomType) {
  return apiRequest(
    `/disputes/${caseId}/rooms/${roomType}/agent-runs/active`,
    actor,
  );
}

// 业务位置：【前端 API/SSE 适配】descriptorCandidate：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
function descriptorCandidate(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidates = [
    value,
    value.agent_run,
    value.agentRun,
    value.accepted_run,
    value.acceptedRun,
    value.run,
    value.stream,
    value.room_message,
    value.roomMessage,
  ];
  return candidates.find((candidate) => {
    if (!candidate || typeof candidate !== "object") return false;
    const runId =
      candidate.run_id ??
      candidate.runId ??
      candidate.agent_run_id ??
      candidate.agentRunId;
    return Boolean(runId);
  }) || null;
}

// 业务位置：【前端 API/SSE 适配】extractAgentRunDescriptor：执行 当前阶段业务数据 对应的业务动作，并将结果交给 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function extractAgentRunDescriptor(value) {
  const candidate = descriptorCandidate(value);
  if (!candidate) return null;
  const runId = String(
    candidate.run_id ??
    candidate.runId ??
    candidate.agent_run_id ??
    candidate.agentRunId,
  );
  return {
    runId,
    streamUrl: String(
      candidate.stream_url ??
      candidate.streamUrl ??
      `/api/agent-runs/${encodeURIComponent(runId)}/events`,
    ),
    operation: String(candidate.operation || "").toUpperCase(),
    status: String(candidate.status || "PENDING").toUpperCase(),
    createdAt: candidate.created_at ?? candidate.createdAt ?? null,
    response: value,
  };
}

// 业务位置：【前端 API/SSE 适配】resultRoomMessage：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function resultRoomMessage(value) {
  if (!value || typeof value !== "object") return null;
  const nested = value.room_message ?? value.roomMessage ?? value.message ?? null;
  if (nested && typeof nested === "object") return nested;
  if (
    value.id &&
    (value.message_text !== undefined || value.messageText !== undefined)
  ) {
    return value;
  }
  return null;
}

// 业务位置：【前端 API/SSE 适配】resolveAgentStreamUrl：读取 Agent 流事件，并依据当前案件、角色和会话权限裁剪成可用输入。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function resolveAgentStreamUrl(streamUrl, afterSequence = 0) {
  const raw = String(streamUrl || "").trim();
  if (!raw) throw new Error("服务未返回数字人流地址");

  let resolved;
  if (/^https?:\/\//i.test(raw)) {
    resolved = raw;
  } else if (raw.startsWith("/api/")) {
    const base = apiUrl("");
    resolved = /^https?:\/\//i.test(base)
      ? new URL(raw, new URL(base).origin).toString()
      : raw;
  } else {
    resolved = apiUrl(raw.startsWith("/") ? raw : `/${raw}`);
  }

  const separator = resolved.includes("?") ? "&" : "?";
  return `${resolved}${separator}last_event_id=${encodeURIComponent(afterSequence || 0)}`;
}

// 业务位置：【前端 API/SSE 适配】normalizeAgentStreamEvent：将 Agent 流事件 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export function normalizeAgentStreamEvent(sseEvent, expectedRunId = "") {
  const envelope = sseEvent?.data && typeof sseEvent.data === "object"
    ? sseEvent.data
    : {};
  const payload = envelope.data && typeof envelope.data === "object"
    ? envelope.data
    : envelope;
  const event = String(
    sseEvent?.event && sseEvent.event !== "message"
      ? sseEvent.event
      : firstDefined(envelope.event, envelope.type, payload.event, payload.type, "message"),
  ).toLowerCase();
  if (!AGENT_STREAM_EVENTS.has(event)) {
    const error = new Error(`不支持的数字人流事件：${event}`);
    error.code = "AGENT_STREAM_EVENT_UNSUPPORTED";
    throw error;
  }
  const schemaVersion = firstDefined(
    envelope.schema_version,
    envelope.schemaVersion,
    payload.schema_version,
    payload.schemaVersion,
    AGENT_STREAM_SCHEMA_VERSION,
  );
  if (schemaVersion !== AGENT_STREAM_SCHEMA_VERSION) {
    const error = new Error(`不支持的数字人流协议：${schemaVersion}`);
    error.code = "AGENT_STREAM_SCHEMA_UNSUPPORTED";
    throw error;
  }

  const runId = String(firstDefined(
    envelope.run_id,
    envelope.runId,
    payload.run_id,
    payload.runId,
    expectedRunId,
    "",
  ));
  if (expectedRunId && runId && runId !== expectedRunId) {
    const error = new Error("数字人流与当前任务不匹配");
    error.code = "AGENT_STREAM_RUN_MISMATCH";
    throw error;
  }

  const sequence = Number(firstDefined(
    envelope.sequence,
    payload.sequence,
    sseEvent?.id,
    0,
  ));
  const errorPayload = payload.error && typeof payload.error === "object"
    ? payload.error
    : envelope.error && typeof envelope.error === "object"
      ? envelope.error
      : payload;

  return {
    schemaVersion,
    runId,
    event,
    sequence: Number.isFinite(sequence) ? sequence : 0,
    nodeName: String(firstDefined(
      payload.node_name,
      payload.nodeName,
      envelope.node_name,
      envelope.nodeName,
      "",
    )),
    fieldPath: String(firstDefined(
      payload.field_path,
      payload.fieldPath,
      payload.field,
      envelope.field_path,
      envelope.fieldPath,
      envelope.field,
      "room_utterance",
    )),
    delta: event === "visible_delta"
      ? String(firstDefined(payload.delta, envelope.delta, ""))
      : "",
    usage: firstDefined(
      payload.usage,
      envelope.usage,
      payload.token_usage,
      payload.tokenUsage,
      envelope.token_usage,
      envelope.tokenUsage,
      null,
    ),
    result: firstDefined(payload.result, payload.response, envelope.result, envelope.response, null),
    error: event === "error"
      ? {
          code: String(firstDefined(errorPayload.code, "AGENT_STREAM_FAILED")),
          message: String(firstDefined(errorPayload.message, "数字人生成失败，请稍后重试。")),
          retryable: Boolean(firstDefined(errorPayload.retryable, false)),
        }
      : null,
    terminal: TERMINAL_EVENTS.has(event),
  };
}

// 业务位置：【前端 API/SSE 适配】consumeAgentRunEvents：执行 Agent 流事件 对应的业务动作，并将结果交给 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
export async function consumeAgentRunEvents({
  actor,
  descriptor,
  lastEventId = -1,
  onEvent,
  fetchImpl = globalThis.fetch,
  signal,
}) {
  const run = extractAgentRunDescriptor(descriptor) || descriptor;
  if (!run?.runId || !run?.streamUrl) {
    throw new Error("无效的数字人流任务");
  }
  let terminal = false;
  try {
    const cursor = await consumeSse({
      actor,
      lastEventId,
      url: resolveAgentStreamUrl(run.streamUrl, lastEventId),
      fetchImpl,
      signal,
      onEvent: async (sseEvent) => {
        const event = normalizeAgentStreamEvent(sseEvent, run.runId);
        terminal ||= event.terminal;
        await onEvent?.(event);
        return !event.terminal;
      },
    });
    return { cursor, terminal };
  } catch (failure) {
    if (failure instanceof SyntaxError) {
      failure.code = "AGENT_STREAM_PROTOCOL_INVALID";
    }
    throw failure;
  }
}
