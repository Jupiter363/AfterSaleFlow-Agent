// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { consumeCaseEvents, roomApi } from "../api/rooms";
import { createResourceState, loadResource } from "./resource";

// 业务位置：【前端状态仓库】createRoomState：把 API 响应、SSE 增量和用户操作 组装为本块需要的 当前阶段业务数据，供 跨组件一致的案件/房间/证据状态 使用。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function createRoomState() {
  return {
    messages: createResourceState([]),
    roomType: null,
    connected: false,
    reconnecting: false,
    lastEventId: 0,
    lastEventIds: {},
    activeEventCursorKey: null,
    streamError: null,
  };
}

export const roomStore = reactive(createRoomState());

const roomEventCursorPrimeGenerations = new WeakMap();

function eventSequenceNo(event) {
  const value = Number(
    event?.sequence_no ?? event?.sequenceNo ?? event?.id ?? 0,
  );
  return Number.isFinite(value) && value > 0 ? value : 0;
}

function nextRoomEventCursorPrimeGeneration(state, cursorKey) {
  let generations = roomEventCursorPrimeGenerations.get(state);
  if (!generations) {
    generations = new Map();
    roomEventCursorPrimeGenerations.set(state, generations);
  }
  const generation = (generations.get(cursorKey) || 0) + 1;
  generations.set(cursorKey, generation);
  return { generation, generations };
}

function publishRoomEventCursor(state, cursorKey, cursor) {
  if (cursorKey) state.lastEventIds[cursorKey] = cursor;
  if (!cursorKey || state.activeEventCursorKey === cursorKey) {
    state.lastEventId = cursor;
  }
}

async function initializeRoomEventCursor({
  actor,
  caseId,
  state,
  cursorKey,
  signal,
}) {
  if (Object.prototype.hasOwnProperty.call(state.lastEventIds, cursorKey)) {
    return state.lastEventIds[cursorKey];
  }
  const { generation, generations } = nextRoomEventCursorPrimeGeneration(
    state,
    cursorKey,
  );
  // roomApi.events does not currently accept an AbortSignal. The per-key
  // generation makes a late replay response harmless even when transport abort
  // is unavailable at this layer.
  const replayed = await roomApi.events(actor, caseId, 0);
  const baseline = (Array.isArray(replayed) ? replayed : []).reduce(
    (highest, event) => Math.max(highest, eventSequenceNo(event)),
    0,
  );
  if (
    signal?.aborted ||
    generations.get(cursorKey) !== generation
  ) {
    return state.lastEventIds[cursorKey] ?? baseline;
  }
  if (!Object.prototype.hasOwnProperty.call(state.lastEventIds, cursorKey)) {
    state.lastEventIds[cursorKey] = baseline;
  }
  return state.lastEventIds[cursorKey];
}

function roomEventCursorKey({ actor, caseId, roomType }) {
  return [caseId, roomType, actor?.id, actor?.role]
    .filter(Boolean)
    .join(":");
}

// Capture the durable case-event boundary before a command that can emit an
// event. A later SSE connection can then replay everything after this cursor.
export async function primeRoomEventCursor({
  actor,
  caseId,
  roomType,
  state = roomStore,
  signal,
}) {
  const cursorKey = roomEventCursorKey({ actor, caseId, roomType });
  return initializeRoomEventCursor({
    actor,
    caseId,
    state,
    cursorKey,
    signal,
  });
}

// 业务位置：【前端状态仓库】loadRoomMessages：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadRoomMessages(actor, caseId, roomType, state = roomStore) {
  state.roomType = roomType;
  return loadResource(
    state.messages,
    () => roomApi.messages(actor, caseId, roomType),
    [],
  );
}

// 业务位置：【前端状态仓库】resumeRoomEvents：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function resumeRoomEvents({
  state = roomStore,
  snapshotLoader,
  eventConsumer,
  applyEvent,
  cursorKey,
}) {
  let committedCursor = cursorKey
    ? state.lastEventIds[cursorKey] ?? 0
    : eventSequenceNo({ id: state.lastEventId });
  state.activeEventCursorKey = cursorKey || null;
  state.lastEventId = committedCursor;
  await snapshotLoader();
  state.streamError = null;
  state.connected = true;
  state.reconnecting = false;
  const completedCursor = await eventConsumer({
    lastEventId: committedCursor,
    onEvent: async (event) => {
      await snapshotLoader();
      await applyEvent?.(event);
      committedCursor = Math.max(committedCursor, eventSequenceNo(event));
      publishRoomEventCursor(state, cursorKey, committedCursor);
    },
  });
  committedCursor = Math.max(
    committedCursor,
    eventSequenceNo({ id: completedCursor }),
  );
  publishRoomEventCursor(state, cursorKey, committedCursor);
  return committedCursor;
}

// 业务位置：【前端状态仓库】streamRoomEvents：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function streamRoomEvents({
  actor,
  caseId,
  roomType,
  state = roomStore,
  signal,
  applyEvent,
  snapshotLoader,
  retryDelayMs = 1200,
}) {
  const cursorKey = roomEventCursorKey({ actor, caseId, roomType });
  while (!signal?.aborted) {
    try {
      await primeRoomEventCursor({ actor, caseId, roomType, state, signal });
      if (signal?.aborted) break;
      await resumeRoomEvents({
        state,
        snapshotLoader:
          snapshotLoader ||
          (() => loadRoomMessages(actor, caseId, roomType, state)),
        eventConsumer: (options) =>
          consumeCaseEvents({
            actor,
            caseId,
            signal,
            ...options,
        }),
        applyEvent,
        cursorKey,
      });
    } catch (error) {
      if (signal?.aborted) break;
      state.connected = false;
      state.reconnecting = true;
      state.streamError = error;
    }
    if (signal?.aborted) break;
    await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    state.reconnecting = false;
  }
}
