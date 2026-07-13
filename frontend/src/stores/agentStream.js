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
    "final_proposed_resolution",
    "reviewed_proposal",
  ].includes(value);
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
  const error = new Error(eventError?.message || "数字人生成失败，请稍后重试。");
  error.code = eventError?.code || "AGENT_STREAM_FAILED";
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
    status: "PENDING",
    content: "",
    fieldText: {},
    fieldOrder: [],
    receivedContent: "",
    receivedFieldText: {},
    receivedFieldOrder: [],
    cards: {},
    cardOrder: [],
    activeCardKey: "default",
    pacedFieldMeta: {},
    seenEventSequences: markRaw(new Set()),
    lastEventId: -1,
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
              if (
                event.sequence >= 0 &&
                run.seenEventSequences.has(event.sequence)
              ) {
                return;
              }
              if (event.sequence >= 0) run.seenEventSequences.add(event.sequence);
              run.lastEventId = Math.max(run.lastEventId, event.sequence || 0);
              if (event.event === "start") {
                run.status = "STREAMING";
              } else if (event.event === "visible_delta") {
                if (!isVisibleField(event.fieldPath) || !event.delta) return;
                run.status = "STREAMING";
                const card = isStructuredVisibleField(event.fieldPath)
                  ? null
                  : ensureStreamCard(run, event);
                if (card) run.activeCardKey = card.key;
                if (!run.receivedFieldOrder.includes(event.fieldPath)) {
                  run.receivedFieldOrder.push(event.fieldPath);
                }
                run.receivedFieldText[event.fieldPath] =
                  (run.receivedFieldText[event.fieldPath] || "") + event.delta;
                rebuildReceivedContent(run);
                if (isStructuredVisibleField(event.fieldPath)) {
                  if (!run.fieldOrder.includes(event.fieldPath)) {
                    run.fieldOrder.push(event.fieldPath);
                  }
                  run.fieldText[event.fieldPath] =
                    (run.fieldText[event.fieldPath] || "") + event.delta;
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
              } else if (event.event === "usage") {
                run.usage = event.usage;
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
              } else if (event.event === "error") {
                terminal = true;
                throw streamFailure(event.error);
              }
              await onEvent?.(event, run);
            },
          });
          run.lastEventId = Math.max(run.lastEventId, Number(consumed.cursor) || 0);
          terminal ||= consumed.terminal;
          if (!terminal) throw new Error("数字人流在完成前断开");
        } catch (failure) {
          if (controller.signal.aborted) throw failure;
          if (failure?.code && failure.code !== "AGENT_STREAM_CONNECTION_FAILED") {
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
