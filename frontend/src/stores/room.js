import { reactive } from "vue";
import { consumeCaseEvents, roomApi } from "../api/rooms";
import { createResourceState, loadResource } from "./resource";

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

export function loadRoomMessages(actor, caseId, roomType, state = roomStore) {
  state.roomType = roomType;
  return loadResource(
    state.messages,
    () => roomApi.messages(actor, caseId, roomType),
    [],
  );
}

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
