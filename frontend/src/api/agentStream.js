// 文件作用：前端 API 客户端文件，封装浏览器到后端服务的 HTTP/SSE 调用。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { apiRequest, apiUrl } from "./client";
import { consumeSse } from "./sse";

export const AGENT_STREAM_SCHEMA_VERSION = "agent_stream.v1";
export const AGENT_STREAM_V2_SCHEMA_VERSION = "agent-stream.v2";
export const AGENT_STREAM_V3_SCHEMA_VERSION = "agent-stream.v3";
const SUPPORTED_PROTOCOLS = new Set([
  AGENT_STREAM_SCHEMA_VERSION,
  AGENT_STREAM_V2_SCHEMA_VERSION,
  AGENT_STREAM_V3_SCHEMA_VERSION,
]);
const TERMINAL_EVENTS = new Set(["final", "error"]);
const AGENT_STREAM_EVENTS = new Set([
  "start",
  "attempt_started",
  "visible_delta",
  "public_frame_start",
  "public_text_delta",
  "active_frame_snapshot",
  "public_frame_committed",
  "public_frame_interrupted",
  "usage",
  "attempt_aborted",
  "attempt_reset",
  "final",
  "error",
]);
const AGENT_STREAM_V2_AUDIENCES = new Set([
  "USER",
  "MERCHANT",
  "PLATFORM_REVIEWER",
  "SYSTEM",
]);

// 业务位置：【前端 API/SSE 适配】firstDefined：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
function firstDefined(...values) {
  return values.find((value) => value !== undefined && value !== null);
}

function protocolError(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function declaredValues(values) {
  return values.filter((value) => value !== undefined && value !== null);
}

function resolveStringDeclaration(values, {
  code,
  label,
  defaultValue,
  required = false,
  nonEmpty = false,
  normalize = (value) => value,
} = {}) {
  const declarations = declaredValues(values);
  if (!declarations.length) {
    if (required) {
      throw protocolError(code, `数字人流事件缺少${label}`);
    }
    return defaultValue;
  }
  if (declarations.some((value) => typeof value !== "string")) {
    throw protocolError(code, `数字人流事件的${label}格式无效`);
  }
  const normalized = declarations.map(normalize);
  if (new Set(normalized).size !== 1) {
    throw protocolError(code, `数字人流事件的${label}声明冲突`);
  }
  if (nonEmpty && !normalized[0]) {
    throw protocolError(code, `数字人流事件缺少${label}`);
  }
  return normalized[0];
}

function resolveSequence(envelope, payload) {
  const declarations = declaredValues([
    envelope.sequence_no,
    envelope.sequence,
    payload.sequence_no,
    payload.sequence,
  ]);
  if (!declarations.length) {
    throw protocolError("AGENT_STREAM_SEQUENCE_INVALID", "数字人流事件缺少序号");
  }
  if (declarations.some((value) => !Number.isSafeInteger(value) || value < 0)) {
    throw protocolError("AGENT_STREAM_SEQUENCE_INVALID", "数字人流事件序号无效");
  }
  if (new Set(declarations).size !== 1) {
    throw protocolError("AGENT_STREAM_SEQUENCE_CONFLICT", "数字人流事件序号声明冲突");
  }
  return declarations[0];
}

function resolveCursor(sseEvent, envelope, payload) {
  const declarations = declaredValues([
    envelope.cursor,
    payload.cursor,
    sseEvent?.id,
  ]);
  if (!declarations.length) {
    throw protocolError("AGENT_STREAM_CURSOR_INVALID", "数字人流事件缺少游标");
  }
  const normalized = declarations.map((value) => {
    if (typeof value === "string") return value;
    if (typeof value === "number" && Number.isSafeInteger(value)) return String(value);
    throw protocolError("AGENT_STREAM_CURSOR_INVALID", "数字人流事件游标无效");
  });
  if (new Set(normalized).size !== 1) {
    throw protocolError("AGENT_STREAM_CURSOR_CONFLICT", "数字人流事件游标声明冲突");
  }
  if (!normalized[0]) {
    throw protocolError("AGENT_STREAM_CURSOR_INVALID", "数字人流事件缺少游标");
  }
  return normalized[0];
}

function validateCursor(protocol, cursor, attemptId, sequence) {
  const canonicalSequence = String(sequence);
  if (protocol === AGENT_STREAM_SCHEMA_VERSION) {
    if (cursor !== canonicalSequence) {
      throw protocolError(
        "AGENT_STREAM_CURSOR_INVALID",
        "数字人 V1 事件游标与序号不匹配",
      );
    }
    return;
  }

  const prefix = protocol === AGENT_STREAM_V3_SCHEMA_VERSION ? "v3:" : "v2:";
  const separator = cursor.lastIndexOf(":");
  if (!cursor.startsWith(prefix) || separator <= 2 || separator === cursor.length - 1) {
    throw protocolError("AGENT_STREAM_CURSOR_INVALID", "数字人事件游标无效");
  }
  const cursorAttemptId = cursor.slice(3, separator);
  const cursorSequence = cursor.slice(separator + 1);
  if (cursorAttemptId !== attemptId || cursorSequence !== canonicalSequence) {
    throw protocolError(
      "AGENT_STREAM_CURSOR_MISMATCH",
      "数字人 V2 事件游标与 attempt 或序号不匹配",
    );
  }
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
  const streamAccess = String(
    candidate.stream_access ?? candidate.streamAccess ?? "",
  ).toUpperCase();
  const explicitStreamUrl = candidate.stream_url ?? candidate.streamUrl ?? "";
  return {
    runId,
    streamUrl: streamAccess === "INTERNAL_SYSTEM_ONLY"
      ? ""
      : String(
          explicitStreamUrl ||
          `/api/agent-runs/${encodeURIComponent(runId)}/events`,
        ),
    streamAccess,
    schemaVersion: String(
      candidate.schema_version ?? candidate.schemaVersion ?? "",
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
export function normalizeAgentStreamEvent(
  sseEvent,
  expectedRunId = "",
  expectedAudience = "",
) {
  const envelope = sseEvent?.data && typeof sseEvent.data === "object"
    ? sseEvent.data
    : {};
  const payload = envelope.payload && typeof envelope.payload === "object"
    ? envelope.payload
    : envelope.data && typeof envelope.data === "object"
      ? envelope.data
      : envelope;
  const event = resolveStringDeclaration([
    sseEvent?.event && sseEvent.event !== "message" ? sseEvent.event : undefined,
    envelope.event,
    envelope.type,
    payload.event,
    payload.type,
  ], {
    code: "AGENT_STREAM_EVENT_CONFLICT",
    label: "事件类型",
    defaultValue: "message",
    normalize: (value) => value.toLowerCase(),
  });
  if (!AGENT_STREAM_EVENTS.has(event)) {
    throw protocolError(
      "AGENT_STREAM_EVENT_UNSUPPORTED",
      `不支持的数字人流事件：${event}`,
    );
  }
  const schemaVersion = resolveStringDeclaration([
    envelope.protocol,
    envelope.schema_version,
    envelope.schemaVersion,
    payload.protocol,
    payload.schema_version,
    payload.schemaVersion,
  ], {
    code: "AGENT_STREAM_PROTOCOL_CONFLICT",
    label: "协议版本",
    defaultValue: AGENT_STREAM_SCHEMA_VERSION,
  });
  if (!SUPPORTED_PROTOCOLS.has(schemaVersion)) {
    throw protocolError(
      "AGENT_STREAM_SCHEMA_UNSUPPORTED",
      `不支持的数字人流协议：${schemaVersion}`,
    );
  }
  const isV2 = schemaVersion === AGENT_STREAM_V2_SCHEMA_VERSION;
  const isV3 = schemaVersion === AGENT_STREAM_V3_SCHEMA_VERSION;
  const attemptScoped = isV2 || isV3;

  const runId = resolveStringDeclaration([
    envelope.run_id,
    envelope.runId,
    payload.run_id,
    payload.runId,
  ], {
    code: "AGENT_STREAM_RUN_INVALID",
    label: "run 标识",
    required: true,
    nonEmpty: true,
  });
  if (expectedRunId && runId !== String(expectedRunId)) {
    throw protocolError("AGENT_STREAM_RUN_MISMATCH", "数字人流与当前任务不匹配");
  }

  const sequence = resolveSequence(envelope, payload);
  const attemptId = resolveStringDeclaration([
    envelope.attempt_id,
    envelope.attemptId,
    payload.attempt_id,
    payload.attemptId,
  ], {
    code: "AGENT_STREAM_ATTEMPT_INVALID",
    label: "attempt 标识",
    defaultValue: "",
    required: attemptScoped,
    nonEmpty: true,
  });
  const transientV3 = isV3 && new Set([
    "public_frame_start",
    "public_text_delta",
    "active_frame_snapshot",
  ]).has(event) && sseEvent?.id == null && envelope.cursor == null && payload.cursor == null;
  const frameId = String(firstDefined(payload.frame_id, payload.frameId, ""));
  const deltaIndex = firstDefined(payload.delta_index, payload.deltaIndex, null);
  const cursor = transientV3
    ? `v3-live:${attemptId}:${frameId}:${event}:${deltaIndex ?? sequence}`
    : resolveCursor(sseEvent, envelope, payload);
  if (!transientV3) validateCursor(schemaVersion, cursor, attemptId, sequence);

  const audience = resolveStringDeclaration([
    envelope.audience,
    payload.audience,
  ], {
    code: "AGENT_STREAM_AUDIENCE_INVALID",
    label: "audience",
    defaultValue: "",
    required: attemptScoped,
    nonEmpty: true,
  });
  if (attemptScoped && !AGENT_STREAM_V2_AUDIENCES.has(audience)) {
    throw protocolError("AGENT_STREAM_AUDIENCE_INVALID", "数字人事件 audience 无效");
  }
  const actorRole = String(expectedAudience || "").toUpperCase();
  if (attemptScoped && actorRole && actorRole !== "ADMIN" && actorRole !== audience) {
    throw protocolError(
      "AGENT_STREAM_AUDIENCE_MISMATCH",
      "数字人 V2 事件 audience 与当前访问者不匹配",
    );
  }
  const errorPayload = payload.error && typeof payload.error === "object"
    ? payload.error
    : envelope.error && typeof envelope.error === "object"
      ? envelope.error
      : payload;

  return {
    schemaVersion,
    protocol: schemaVersion,
    runId,
    attemptId,
    event,
    sequence,
    cursor,
    durable: !transientV3,
    audience,
    resetAttemptId: String(firstDefined(
      envelope.reset_attempt_id,
      envelope.resetAttemptId,
      payload.reset_attempt_id,
      payload.resetAttemptId,
      "",
    )),
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
    delta: ["visible_delta", "public_text_delta"].includes(event)
      ? String(firstDefined(payload.delta, envelope.delta, ""))
      : "",
    frameId,
    frameSequence: firstDefined(payload.frame_sequence, payload.frameSequence, null),
    frameType: String(firstDefined(payload.frame_type, payload.frameType, "")),
    publicHeader: firstDefined(payload.public_header, payload.publicHeader, null),
    deltaIndex,
    publicText: String(firstDefined(payload.public_text, payload.publicText, "")),
    durableCursor: String(firstDefined(
      payload.durable_cursor,
      payload.durableCursor,
      "",
    )),
    headerSha256: String(firstDefined(payload.header_sha256, payload.headerSha256, "")),
    publicTextSha256: String(firstDefined(
      payload.public_text_sha256,
      payload.publicTextSha256,
      "",
    )),
    frameSha256: String(firstDefined(payload.frame_sha256, payload.frameSha256, "")),
    publicTextChars: firstDefined(
      payload.public_text_chars,
      payload.publicTextChars,
      null,
    ),
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
          code: String(firstDefined(
            errorPayload.code,
            errorPayload.error_code,
            errorPayload.errorCode,
            "AGENT_STREAM_FAILED",
          )),
          message: String(firstDefined(
            errorPayload.message,
            envelope.message,
            "数字人生成失败，请稍后重试。",
          )),
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
        const event = normalizeAgentStreamEvent(sseEvent, run.runId, actor?.role);
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
