// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { markRaw, reactive } from "vue";
import {
  consumeAgentRunEvents,
  extractAgentRunDescriptor,
} from "../api/agentStream";
import { createStreamTextPacer } from "../utils/streamTextPacer";
import { streamCardPresentation } from "../utils/agentSpeakerPresentation";

const ACTIVE_STATUSES = new Set([
  "PENDING",
  "CONNECTING",
  "STREAMING",
  "RECONNECTING",
  "FINALIZING",
]);
const FORBIDDEN_VISIBLE_FIELDS = [
  "reasoning_content",
  "chain_of_thought",
  "tool_args",
  "tool_arguments",
  "system_prompt",
  "internal_context",
  "private_a2a",
];

export const agentStreamStore = reactive({
  runs: {},
});

// 业务位置：【前端状态仓库】normalizeContext：将 案件会话和上下文快照 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function normalizeContext(context = {}) {
  return {
    caseId: String(context.caseId || ""),
    roomType: String(context.roomType || "").toUpperCase(),
    actorId: String(context.actorId || context.actor?.id || ""),
    actorRole: String(context.actorRole || context.actor?.role || "").toUpperCase(),
  };
}

// 业务位置：【前端状态仓库】runMatchesContext：执行 案件会话和上下文快照 对应的业务动作，并将结果交给 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function runMatchesContext(run, context = {}) {
  const target = normalizeContext(context);
  return (
    (!target.caseId || run.caseId === target.caseId) &&
    (!target.roomType || run.roomType === target.roomType) &&
    (!target.actorId || run.actorId === target.actorId) &&
    (!target.actorRole || run.actorRole === target.actorRole)
  );
}

// 业务位置：【前端状态仓库】isVisibleField：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function isVisibleField(fieldPath) {
  const value = String(fieldPath || "").toLowerCase();
  return !FORBIDDEN_VISIBLE_FIELDS.some((field) => value.includes(field));
}

function isStructuredVisibleField(fieldPath) {
  const value = String(fieldPath || "");
  return value.startsWith("case_detail.") || [
    "ordered_sections",
    "final_proposed_resolution",
    "reviewed_proposal",
  ].includes(value);
}

function isCaseDetailRootSnapshotField(fieldPath) {
  const segments = String(fieldPath || "").split(".");
  return segments.length === 2 && segments[0] === "case_detail" && Boolean(segments[1]);
}

function applyVisibleFieldDelta(fieldText, fieldPath, delta) {
  // A case-detail branch is a complete JSON snapshot in one SSE event. It is
  // deliberately not a text stream: a terminal snapshot for the same branch
  // supersedes the model's earlier provisional snapshot. Nested case-detail
  // leaves continue to use normal append semantics.
  fieldText[fieldPath] = isCaseDetailRootSnapshotField(fieldPath)
    ? delta
    : (fieldText[fieldPath] || "") + delta;
}

function createStreamCard(presentation) {
  return reactive({
    key: presentation.key,
    identity: presentation.identity,
    name: presentation.name,
    senderRole: presentation.senderRole,
    fieldText: {},
    fieldOrder: [],
    content: "",
  });
}

function ensureStreamCard(run, event = {}) {
  const presentation = streamCardPresentation({
    operation: run.operation,
    nodeName: event.nodeName,
    fieldPath: event.fieldPath,
    senderRole: run.senderRole,
    agentLabel: run.agentLabel,
  });
  if (!run.cards[presentation.key]) {
    run.cards[presentation.key] = createStreamCard(presentation);
    run.cardOrder.push(presentation.key);
  }
  return run.cards[presentation.key];
}

function rebuildCardContent(card) {
  card.content = card.fieldOrder
    .map((field) => card.fieldText[field] || "")
    .filter(Boolean)
    .join("\n\n");
}

export function streamCardsForRun(run) {
  if (!run) return [];
  return (run.cardOrder || [])
    .map((key) => run.cards?.[key])
    .filter(Boolean);
}

// 业务位置：【前端状态仓库】rebuildVisibleContent：把 API 响应、SSE 增量和用户操作 组装为本块需要的 面向当事人的业务文本，供 跨组件一致的案件/房间/证据状态 使用。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function rebuildVisibleContent(run) {
  run.content = run.fieldOrder
    .filter((field) => !isStructuredVisibleField(field))
    .map((field) => run.fieldText[field] || "")
    .filter(Boolean)
    .join("\n\n");
}

function rebuildReceivedContent(run) {
  run.receivedContent = run.receivedFieldOrder
    .filter((field) => !isStructuredVisibleField(field))
    .map((field) => run.receivedFieldText[field] || "")
    .filter(Boolean)
    .join("\n\n");
}

function frameFieldKey(frameSequence) {
  return `frame:${frameSequence}`;
}

function ensureV3Frame(run, event) {
  if (!event.frameId || !Number.isSafeInteger(event.frameSequence) || event.frameSequence < 1) {
    const error = new Error("Evidence 流帧缺少有效身份");
    error.code = "AGENT_STREAM_V3_FRAME_INVALID";
    throw error;
  }
  const existing = run.frames[event.frameId];
  if (existing) {
    if (
      existing.frameSequence !== event.frameSequence ||
      (event.frameType && existing.frameType !== event.frameType)
    ) {
      const error = new Error("Evidence 流帧身份发生冲突");
      error.code = "AGENT_STREAM_V3_FRAME_CONFLICT";
      throw error;
    }
    return existing;
  }
  if (event.event !== "public_frame_start") {
    const error = new Error("Evidence 流帧在 header 前到达");
    error.code = "AGENT_STREAM_V3_FRAME_HEADER_MISSING";
    throw error;
  }
  const expectedSequence = run.frameOrder.length + 1;
  if (event.frameSequence !== expectedSequence || !event.frameType || !event.publicHeader) {
    const error = new Error("Evidence 流帧顺序或 header 无效");
    error.code = "AGENT_STREAM_V3_FRAME_ORDER_INVALID";
    throw error;
  }
  const frame = reactive({
    frameId: event.frameId,
    frameSequence: event.frameSequence,
    frameType: event.frameType,
    publicHeader: event.publicHeader,
    publicText: "",
    nextDeltaIndex: 0,
    status: "STREAMING",
    durableCursor: "",
    headerSha256: "",
    publicTextSha256: "",
    frameSha256: "",
  });
  run.frames[event.frameId] = frame;
  run.frameOrder.push(event.frameId);
  return frame;
}

function rebuildV3FrameProjection(run) {
  const card = run.cards.default || ensureStreamCard(run, {});
  const publicFrames = run.frameOrder
    .map((frameId) => run.frames[frameId])
    .filter(Boolean);
  card.fieldOrder = publicFrames.map((frame) => frameFieldKey(frame.frameSequence));
  card.fieldText = Object.fromEntries(
    publicFrames.map((frame) => [frameFieldKey(frame.frameSequence), frame.publicText]),
  );
  rebuildCardContent(card);
  run.fieldOrder = [...card.fieldOrder];
  run.fieldText = { ...card.fieldText };
  run.receivedFieldOrder = [...card.fieldOrder];
  run.receivedFieldText = { ...card.fieldText };
  run.content = card.content;
  run.receivedContent = card.content;
  run.activeCardKey = card.key;
}

function applyV3FrameEvent(run, event) {
  const frame = ensureV3Frame(run, event);
  if (event.event === "public_frame_start") {
    if (
      event.publicHeader &&
      JSON.stringify(frame.publicHeader) !== JSON.stringify(event.publicHeader)
    ) {
      const error = new Error("Evidence 流帧 header 重放不一致");
      error.code = "AGENT_STREAM_V3_FRAME_HEADER_CONFLICT";
      throw error;
    }
    return;
  }
  if (event.event === "public_text_delta") {
    if (!Number.isSafeInteger(event.deltaIndex) || event.deltaIndex !== frame.nextDeltaIndex) {
      const error = new Error("Evidence 文本 delta 序号不连续");
      error.code = "AGENT_STREAM_V3_DELTA_OUT_OF_ORDER";
      throw error;
    }
    frame.publicText += event.delta;
    frame.nextDeltaIndex += 1;
    frame.status = "STREAMING";
    rebuildV3FrameProjection(run);
    return;
  }
  if (event.event === "active_frame_snapshot") {
    if (!Number.isSafeInteger(event.deltaIndex) || event.deltaIndex < frame.nextDeltaIndex) {
      const error = new Error("Evidence 活动帧快照发生回退");
      error.code = "AGENT_STREAM_V3_SNAPSHOT_REWIND";
      throw error;
    }
    frame.publicText = event.publicText;
    frame.nextDeltaIndex = event.deltaIndex;
    rebuildV3FrameProjection(run);
    return;
  }
  if (event.event === "public_frame_committed") {
    if (
      !event.durableCursor ||
      !event.headerSha256 ||
      !event.publicTextSha256 ||
      !event.frameSha256 ||
      event.publicTextChars !== [...frame.publicText].length
    ) {
      const error = new Error("Evidence 已提交帧与当前文本不一致");
      error.code = "AGENT_STREAM_V3_FRAME_COMMIT_INVALID";
      throw error;
    }
    frame.status = "COMMITTED";
    frame.durableCursor = event.durableCursor;
    frame.headerSha256 = event.headerSha256;
    frame.publicTextSha256 = event.publicTextSha256;
    frame.frameSha256 = event.frameSha256;
    return;
  }
  if (event.event === "public_frame_interrupted") {
    if (event.publicText !== frame.publicText) {
      const error = new Error("Evidence 中断帧与已显示文本不一致");
      error.code = "AGENT_STREAM_V3_FRAME_INTERRUPTED_INVALID";
      throw error;
    }
    frame.status = "INTERRUPTED";
    frame.durableCursor = event.durableCursor;
  }
}

function installDisplayPacer(run) {
  run.displayPacer = markRaw(createStreamTextPacer({
    onReveal: (pacedFieldKey, fragment) => {
      const meta = run.pacedFieldMeta[pacedFieldKey] || {
        fieldPath: pacedFieldKey,
        cardKey: "default",
      };
      const fieldPath = meta.fieldPath;
      if (!run.fieldOrder.includes(pacedFieldKey)) {
        run.fieldOrder.push(pacedFieldKey);
      }
      run.fieldText[pacedFieldKey] = (run.fieldText[pacedFieldKey] || "") + fragment;
      const card = run.cards[meta.cardKey] || run.cards.default;
      if (!card.fieldOrder.includes(pacedFieldKey)) {
        card.fieldOrder.push(pacedFieldKey);
      }
      card.fieldText[pacedFieldKey] = (card.fieldText[pacedFieldKey] || "") + fragment;
      rebuildCardContent(card);
      rebuildVisibleContent(run);
    },
  }));
}

function isReplyThenBoardBarrierEnabled(run) {
  return Boolean(
    run.replyThenBoard &&
    run.roomType === "INTAKE",
  );
}

async function awaitReplyThenBoardBarrier(run, signal) {
  if (!isReplyThenBoardBarrierEnabled(run) || !run.replyThenBoardPending) return;
  // Intake's visible reply owns the first paint. Do not acknowledge the first
  // case-detail event until that text pacer has finished: advancing the cursor
  // first would make a reload or abort permanently skip a durable event that
  // has never reached the right-side projection.
  await run.displayPacer.drain();
  if (signal?.aborted) {
    throw signal.reason || new DOMException("Aborted", "AbortError");
  }
}

function resetAttemptProjection(run, resetAttemptId, nextAttemptId) {
  if (run.currentAttemptId && run.currentAttemptId !== resetAttemptId) {
    const error = new Error("数字人 reset 与当前 attempt 不匹配");
    error.code = "AGENT_STREAM_RESET_MISMATCH";
    throw error;
  }
  run.displayPacer?.cancel();
  run.content = "";
  run.fieldText = {};
  run.fieldOrder = [];
  run.receivedContent = "";
  run.receivedFieldText = {};
  run.receivedFieldOrder = [];
  run.cards = {};
  run.cardOrder = [];
  run.activeCardKey = "default";
  run.pacedFieldMeta = {};
  run.frames = {};
  run.frameOrder = [];
  run.replyThenBoardPending = isReplyThenBoardBarrierEnabled(run);
  run.currentAttemptId = nextAttemptId;
  run.pendingAttemptId = "";
  run.resetCount += 1;
  ensureStreamCard(run, {});
  installDisplayPacer(run);
}

function eventIdentity(event) {
  if (event.protocol === "agent-stream.v3" && event.frameId) {
    const discriminator = event.event === "public_text_delta"
      || event.event === "active_frame_snapshot"
      ? `${event.event}:${event.deltaIndex}`
      : event.event;
    return `${event.protocol}:${event.attemptId}:${event.frameId}:${discriminator}`;
  }
  return `${event.protocol || "v1"}:${event.attemptId || "legacy"}:${event.sequence}`;
}

// 业务位置：【前端状态仓库】wait：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function wait(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    // 业务位置：【前端状态仓库】abort：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
    const abort = () => {
      clearTimeout(timer);
      reject(signal.reason || new DOMException("Aborted", "AbortError"));
    };
    if (signal?.aborted) abort();
    else signal?.addEventListener("abort", abort, { once: true });
  });
}

// 业务位置：【前端状态仓库】streamFailure：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
function streamFailure(eventError) {
  const candidateCode = String(eventError?.code || "").trim();
  const diagnosticCode = /^[A-Z][A-Z0-9_]{2,95}$/.test(candidateCode)
    ? candidateCode
    : "AGENT_STREAM_FAILED";
  const sourceMessage = eventError?.message || "数字人生成失败，请稍后重试。";
  const message = sourceMessage.includes(diagnosticCode)
    ? sourceMessage
    : `${sourceMessage}\n诊断码：${diagnosticCode}`;
  const error = new Error(message);
  error.code = diagnosticCode;
  error.retryable = Boolean(eventError?.retryable);
  return error;
}

// 业务位置：【前端状态仓库】getAgentStreamRun：读取 Agent 流事件，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function getAgentStreamRun(runId) {
  return agentStreamStore.runs[runId] || null;
}

// 业务位置：【前端状态仓库】activeAgentStreams：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function activeAgentStreams(context = {}) {
  return Object.values(agentStreamStore.runs)
    .filter((run) => ACTIVE_STATUSES.has(run.status) && runMatchesContext(run, context))
    .sort((left, right) => left.startedAt - right.startedAt);
}

// 业务位置：【前端状态仓库】hasActiveAgentStream：判断 Agent 流事件 是否满足当前流程分支的进入条件。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function hasActiveAgentStream(context = {}) {
  return activeAgentStreams(context).length > 0;
}

// 业务位置：【前端状态仓库】messageAgentRunId：执行 房间消息和对话记录 对应的业务动作，并将结果交给 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function messageAgentRunId(message) {
  return String(
    message?.agent_run_id ??
    message?.agentRunId ??
    message?.metadata?.agent_run_id ??
    message?.metadata?.agentRunId ??
    "",
  );
}

// 业务位置：【前端状态仓库】visibleAgentStreams：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function visibleAgentStreams(runs, durableMessages = []) {
  const agentMessages = (durableMessages || []).filter(
    (message) => !["USER", "MERCHANT"].includes(
      String(message?.sender_role ?? message?.senderRole ?? "").toUpperCase(),
    ),
  );
  const durableRunIds = new Set(
    agentMessages.map(messageAgentRunId).filter(Boolean),
  );
  const durableTexts = new Set(
    agentMessages
      .map((message) => String(
        message?.message_text ?? message?.messageText ?? message?.text ?? "",
      ).trim())
      .filter(Boolean),
  );
  return (runs || []).filter((run) => {
    if (durableRunIds.has(run.runId)) return false;
    return !run.content?.trim() || !durableTexts.has(run.content.trim());
  });
}

// Active stream bubbles own their corresponding output until paced rendering
// has drained. This prevents an early room snapshot refresh from replacing a
// partial stream with the complete durable message in a single paint.
export function durableMessagesOutsideActiveStreams(messages, runs = []) {
  const activeRunIds = new Set((runs || []).map((run) => run.runId).filter(Boolean));
  const activeReceivedTexts = new Set(
    (runs || [])
      .flatMap((run) => [
        String(run.receivedContent || "").trim(),
        ...streamCardsForRun(run).map((card) => String(card.content || "").trim()),
      ])
      .filter(Boolean),
  );
  return (messages || []).filter((message) => {
    const senderRole = String(
      message?.sender_role ?? message?.senderRole ?? "",
    ).toUpperCase();
    if (["USER", "MERCHANT"].includes(senderRole)) return true;

    const runId = messageAgentRunId(message);
    if (runId) return !activeRunIds.has(runId);

    const text = String(
      message?.message_text ?? message?.messageText ?? message?.text ?? "",
    ).trim();
    return !text || !activeReceivedTexts.has(text);
  });
}

// 业务位置：【前端状态仓库】abortAgentStream：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function abortAgentStream(runId) {
  const run = getAgentStreamRun(runId);
  if (!run) return;
  run.displayPacer?.cancel();
  run.abortController?.abort();
  if (ACTIVE_STATUSES.has(run.status)) run.status = "ABORTED";
}

// 业务位置：【前端状态仓库】clearAgentStreams：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function clearAgentStreams(context = {}, { abort = true } = {}) {
  Object.values(agentStreamStore.runs).forEach((run) => {
    if (!runMatchesContext(run, context)) return;
    run.displayPacer?.cancel();
    if (abort && ACTIVE_STATUSES.has(run.status)) run.abortController?.abort();
    delete agentStreamStore.runs[run.runId];
  });
}

// 业务位置：【前端状态仓库】consumeAgentRun：执行 当前阶段业务数据 对应的业务动作，并将结果交给 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function consumeAgentRun({
  actor,
  caseId,
  roomType,
  descriptor: rawDescriptor,
  agentLabel = "数字人",
  senderRole = "CUSTOMER_SERVICE",
  onEvent,
  onFinal,
  onError,
  signal,
  reconnectAttempts = 8,
  reconnectBaseDelayMs = 350,
  replyThenBoard = false,
  fetchImpl = globalThis.fetch,
}) {
  const descriptor = extractAgentRunDescriptor(rawDescriptor) || rawDescriptor;
  if (!descriptor?.runId || !descriptor?.streamUrl) return null;

  const existing = getAgentStreamRun(descriptor.runId);
  if (existing?.promise && ACTIVE_STATUSES.has(existing.status)) {
    return existing.promise;
  }

  const controller = new AbortController();
  // 业务位置：【前端状态仓库】abortFromCaller：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  const abortFromCaller = () => controller.abort(signal?.reason);
  if (signal?.aborted) abortFromCaller();
  else signal?.addEventListener("abort", abortFromCaller, { once: true });

  const run = reactive({
    runId: descriptor.runId,
    streamUrl: descriptor.streamUrl,
    caseId: String(caseId || ""),
    roomType: String(roomType || "").toUpperCase(),
    operation: String(descriptor.operation || "").toUpperCase(),
    actorId: String(actor?.id || ""),
    actorRole: String(actor?.role || "").toUpperCase(),
    agentLabel,
    senderRole,
    protocol: "",
    currentAttemptId: "",
    pendingAttemptId: "",
    resetCount: 0,
    attempts: {},
    status: "PENDING",
    content: "",
    fieldText: {},
    fieldOrder: [],
    receivedContent: "",
    receivedFieldText: {},
    receivedFieldOrder: [],
    cards: {},
    cardOrder: [],
    frames: {},
    frameOrder: [],
    activeCardKey: "default",
    pacedFieldMeta: {},
    // This presentation policy is intentionally opt-in. Intake enables it so
    // the person-facing reply finishes before its dossier begins to render;
    // evidence, hearing, and other agent surfaces retain their existing order.
    replyThenBoard: Boolean(replyThenBoard) &&
      String(roomType || "").toUpperCase() === "INTAKE",
    replyThenBoardPending: Boolean(replyThenBoard) &&
      String(roomType || "").toUpperCase() === "INTAKE",
    seenEventSequences: markRaw(new Set()),
    lastEventId: "-1",
    usage: null,
    finalResult: null,
    error: null,
    reconnectCount: 0,
    startedAt: Number.isFinite(Date.parse(descriptor.createdAt))
      ? Date.parse(descriptor.createdAt)
      : Date.now(),
    completedAt: null,
    abortController: markRaw(controller),
    displayPacer: null,
    promise: null,
  });
  ensureStreamCard(run, {});
  installDisplayPacer(run);
  agentStreamStore.runs[run.runId] = run;

  // 业务位置：【前端状态仓库】execute：执行 当前阶段业务数据 对应的业务动作，并将结果交给 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  const execute = async () => {
    let terminal = false;
    let attempts = 0;
    try {
      while (!terminal && !controller.signal.aborted) {
        run.status = attempts > 0 ? "RECONNECTING" : "CONNECTING";
        try {
          const consumed = await consumeAgentRunEvents({
            actor,
            descriptor,
            lastEventId: run.lastEventId,
            fetchImpl,
            signal: controller.signal,
            onEvent: async (event) => {
              const identity = eventIdentity(event);
              if (run.seenEventSequences.has(identity)) return;
              if (run.protocol && run.protocol !== event.protocol) {
                const error = new Error("数字人流协议在运行中发生变化");
                error.code = "AGENT_STREAM_PROTOCOL_CHANGED";
                throw error;
              }
              run.protocol ||= event.protocol;

              const attemptScoped = ["agent-stream.v2", "agent-stream.v3"].includes(event.protocol);
              if (attemptScoped && event.event === "attempt_started") {
                run.attempts[event.attemptId] ||= {
                  startedAt: Date.now(),
                  hasVisibleOutput: false,
                };
                run.attempts[event.attemptId].status = "STREAMING";
                run.attempts[event.attemptId].hasVisibleOutput ??= false;
                if (!run.currentAttemptId) run.currentAttemptId = event.attemptId;
                else if (run.currentAttemptId !== event.attemptId) {
                  const currentAttempt = run.attempts[run.currentAttemptId];
                  // The ledger deliberately omits attempt_reset when a failed
                  // attempt never emitted a visible delta. There is no user
                  // projection to discard in that case, so atomically promote
                  // the retry. Once any visible output exists, only an explicit
                  // reset may replace it.
                  if (!currentAttempt?.hasVisibleOutput) {
                    run.currentAttemptId = event.attemptId;
                    run.pendingAttemptId = "";
                  } else {
                    run.pendingAttemptId = event.attemptId;
                  }
                }
              }
              if (attemptScoped && event.event === "attempt_reset") {
                if (!event.resetAttemptId || !event.attemptId) {
                  const error = new Error("数字人 reset 缺少 attempt 绑定");
                  error.code = "AGENT_STREAM_RESET_INVALID";
                  throw error;
                }
                resetAttemptProjection(run, event.resetAttemptId, event.attemptId);
                if (run.attempts[event.resetAttemptId]) {
                  run.attempts[event.resetAttemptId].status = "RESET";
                }
                run.attempts[event.attemptId] ||= { startedAt: Date.now() };
                run.attempts[event.attemptId].status = "STREAMING";
              } else if (attemptScoped && event.event === "generation_reset") {
                if (!event.attemptId || run.currentAttemptId !== event.attemptId) {
                  const error = new Error("数字人 generation reset 与当前 attempt 不匹配");
                  error.code = "AGENT_STREAM_GENERATION_RESET_MISMATCH";
                  throw error;
                }
                // A generation reset replaces provisional output inside the
                // same durable attempt. Clear every visible projection while
                // preserving the attempt/cursor authority for the replacement
                // generation that follows on the same SSE connection.
                resetAttemptProjection(run, event.attemptId, event.attemptId);
                run.attempts[event.attemptId] ||= { startedAt: Date.now() };
                run.attempts[event.attemptId].status = "STREAMING";
              } else if (
                attemptScoped &&
                !["attempt_started"].includes(event.event) &&
                run.currentAttemptId !== event.attemptId
              ) {
                const error = new Error("数字人事件来自未激活的 attempt");
                error.code = "AGENT_STREAM_ATTEMPT_OUT_OF_ORDER";
                throw error;
              }

              if (event.event === "start" || event.event === "attempt_started") {
                run.status = "STREAMING";
              } else if (
                event.protocol === "agent-stream.v3" &&
                [
                  "public_frame_start",
                  "public_text_delta",
                  "active_frame_snapshot",
                  "public_frame_committed",
                  "public_frame_interrupted",
                ].includes(event.event)
              ) {
                applyV3FrameEvent(run, event);
                run.status = event.event === "public_frame_interrupted"
                  ? "ERROR"
                  : "STREAMING";
              } else if (event.event === "visible_delta") {
                if (!isVisibleField(event.fieldPath) || !event.delta) {
                  run.seenEventSequences.add(identity);
                  if (event.durable && event.cursor) run.lastEventId = event.cursor;
                  return;
                }
                const structuredField = isStructuredVisibleField(event.fieldPath);
                // Capacity failure is replayable. Check it before mutating received/card
                // projections so the same durable event can be applied exactly once later.
                if (!structuredField) {
                  run.displayPacer.assertCapacity(event.delta);
                }
                if (structuredField) {
                  // Keep this event unacknowledged while the reply is still
                  // typing. The stream reader may back-pressure here, but a
                  // reconnect can safely replay the same durable event.
                  await awaitReplyThenBoardBarrier(run, controller.signal);
                }
                if (attemptScoped && run.attempts[event.attemptId]) {
                  run.attempts[event.attemptId].hasVisibleOutput = true;
                }
                run.status = "STREAMING";
                const card = structuredField
                  ? null
                  : ensureStreamCard(run, event);
                if (card) run.activeCardKey = card.key;
                if (!run.receivedFieldOrder.includes(event.fieldPath)) {
                  run.receivedFieldOrder.push(event.fieldPath);
                }
                applyVisibleFieldDelta(
                  run.receivedFieldText,
                  event.fieldPath,
                  event.delta,
                );
                rebuildReceivedContent(run);
                if (structuredField) {
                  if (!run.fieldOrder.includes(event.fieldPath)) {
                    run.fieldOrder.push(event.fieldPath);
                  }
                  applyVisibleFieldDelta(
                    run.fieldText,
                    event.fieldPath,
                    event.delta,
                  );
                  rebuildVisibleContent(run);
                } else {
                  const pacedFieldKey = `${event.nodeName || "node"}::${event.fieldPath}`;
                  run.pacedFieldMeta[pacedFieldKey] = {
                    fieldPath: event.fieldPath,
                    cardKey: card.key,
                    nodeName: event.nodeName || "",
                  };
                  run.displayPacer.enqueue(pacedFieldKey, event.delta);
                }
                // The Intake barrier is committed only after the right-side
                // projection observer has applied this first structured event.
                // This is deliberately different from the default stream path:
                // cursor/seen state must never get ahead of an unpainted dossier.
                if (structuredField && isReplyThenBoardBarrierEnabled(run)) {
                  await onEvent?.(event, run);
                  if (controller.signal.aborted) {
                    throw controller.signal.reason || new DOMException("Aborted", "AbortError");
                  }
                  run.seenEventSequences.add(identity);
                  if (event.durable && event.cursor) run.lastEventId = event.cursor;
                  run.replyThenBoardPending = false;
                  return;
                }
              } else if (event.event === "usage") {
                run.usage = event.usage;
              } else if (event.event === "attempt_aborted") {
                if (run.attempts[event.attemptId]) {
                  run.attempts[event.attemptId].status = "ABORTED";
                }
                run.status = "RECONNECTING";
              } else if (event.event === "attempt_reset") {
                run.status = "STREAMING";
              } else if (event.event === "generation_reset") {
                run.status = "STREAMING";
              } else if (event.event === "final") {
                terminal = true;
                await run.displayPacer.drain();
                if (controller.signal.aborted) {
                  throw controller.signal.reason || new DOMException("Aborted", "AbortError");
                }
                run.status = "FINALIZING";
                run.activeCardKey = "";
                run.finalResult = event.result;
                try {
                  await onFinal?.(event.result, run, event);
                } catch (failure) {
                  const refreshFailure = failure instanceof Error
                    ? failure
                    : new Error(String(failure || "正式记录刷新失败"));
                  refreshFailure.code ||= "AGENT_STREAM_FINALIZATION_REFRESH_FAILED";
                  throw refreshFailure;
                }
                if (controller.signal.aborted) {
                  throw controller.signal.reason || new DOMException("Aborted", "AbortError");
                }
                run.status = "COMPLETED";
                run.completedAt = Date.now();
                if (event.attemptId && run.attempts[event.attemptId]) {
                  run.attempts[event.attemptId].status = "COMPLETED";
                }
              } else if (event.event === "error") {
                terminal = true;
                throw streamFailure(event.error);
              }
              run.seenEventSequences.add(identity);
              if (event.durable && event.cursor) run.lastEventId = event.cursor;
              await onEvent?.(event, run);
            },
          });
          run.lastEventId = String(consumed.cursor ?? run.lastEventId);
          terminal ||= consumed.terminal;
          if (!terminal) throw new Error("数字人流在完成前断开");
        } catch (failure) {
          if (controller.signal.aborted) throw failure;
          if (failure?.code === "AGENT_STREAM_SLOW_CONSUMER") {
            await run.displayPacer.drain();
          } else if (
            failure?.code &&
            failure.code !== "AGENT_STREAM_CONNECTION_FAILED"
          ) {
            throw failure;
          }
          if (attempts >= reconnectAttempts) throw failure;
          attempts += 1;
          run.reconnectCount = attempts;
          run.status = "RECONNECTING";
          await wait(
            Math.min(3000, reconnectBaseDelayMs * (2 ** (attempts - 1))),
            controller.signal,
          );
        }
      }
      return run.finalResult;
    } catch (failure) {
      run.displayPacer?.cancel();
      if (controller.signal.aborted) {
        run.status = "ABORTED";
        return null;
      }
      run.status = "ERROR";
      run.error = {
        code: failure?.code || "AGENT_STREAM_FAILED",
        message: failure?.message || "数字人生成失败，请稍后重试。",
        retryable: Boolean(failure?.retryable),
      };
      run.completedAt = Date.now();
      await onError?.(run.error, run);
      throw failure;
    } finally {
      signal?.removeEventListener("abort", abortFromCaller);
    }
  };

  run.promise = markRaw(execute());
  return run.promise;
}
