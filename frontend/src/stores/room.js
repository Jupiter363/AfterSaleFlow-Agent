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
    streamError: null,
  };
}

export const roomStore = reactive(createRoomState());

function eventSequenceNo(event) {
  const value = Number(
    event?.sequence_no ?? event?.sequenceNo ?? event?.id ?? 0,
  );
  return Number.isFinite(value) && value > 0 ? value : 0;
}

async function initializeRoomEventCursor({
  actor,
  caseId,
  state,
  cursorKey,
}) {
  if (Object.prototype.hasOwnProperty.call(state.lastEventIds, cursorKey)) {
    return;
  }
  const replayed = await roomApi.events(actor, caseId, 0);
  const baseline = (Array.isArray(replayed) ? replayed : []).reduce(
    (highest, event) => Math.max(highest, eventSequenceNo(event)),
    0,
  );
  state.lastEventIds[cursorKey] = baseline;
  state.lastEventId = baseline;
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
  if (cursorKey) {
    state.lastEventId = state.lastEventIds[cursorKey] ?? 0;
  }
  await snapshotLoader();
  state.streamError = null;
  state.connected = true;
  state.reconnecting = false;
  state.lastEventId = await eventConsumer({
    lastEventId: state.lastEventId,
    onEvent: async (event) => {
      if (event.id != null) {
        state.lastEventId = Math.max(state.lastEventId, event.id);
        if (cursorKey) {
          state.lastEventIds[cursorKey] = state.lastEventId;
        }
      }
      await snapshotLoader();
      await applyEvent?.(event);
    },
  });
  if (cursorKey) {
    state.lastEventIds[cursorKey] = state.lastEventId;
  }
  return state.lastEventId;
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
  const cursorKey = [caseId, roomType, actor?.id, actor?.role]
    .filter(Boolean)
    .join(":");
  while (!signal?.aborted) {
    try {
      await initializeRoomEventCursor({ actor, caseId, state, cursorKey });
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
