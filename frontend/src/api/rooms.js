import { apiRequest, apiUrl, newIdempotencyKey } from "./client";

export const roomApi = {
  messages: (actor, caseId, roomType) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/messages`, actor),

  latestTurnMemory: (actor, caseId, roomType) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/turn-memory/latest`, actor),

  ensureOpening: (
    actor,
    caseId,
    roomType,
    idempotencyKey = newIdempotencyKey("room-opening"),
  ) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/messages/opening`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  postMessage: (
    actor,
    caseId,
    roomType,
    command,
    idempotencyKey = newIdempotencyKey("room-message"),
  ) =>
    apiRequest(`/disputes/${caseId}/rooms/${roomType}/messages`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(command),
    }),
};

export function parseSseBlock(block) {
  let id = null;
  let event = "message";
  const dataLines = [];

  for (const rawLine of block.split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(":")) continue;
    const separator = rawLine.indexOf(":");
    const field = separator === -1 ? rawLine : rawLine.slice(0, separator);
    const value =
      separator === -1
        ? ""
        : rawLine.slice(separator + 1).replace(/^ /, "");
    if (field === "id") id = Number(value);
    if (field === "event") event = value;
    if (field === "data") dataLines.push(value);
  }

  if (!dataLines.length) return null;
  const rawData = dataLines.join("\n");
  return {
    id: Number.isFinite(id) ? id : null,
    event,
    data: JSON.parse(rawData),
  };
}

export async function consumeCaseEvents({
  actor,
  caseId,
  lastEventId = 0,
  onEvent,
  fetchImpl = globalThis.fetch,
  signal,
}) {
  const response = await fetchImpl(
    apiUrl(`/disputes/${caseId}/events?last_event_id=${lastEventId}`),
    {
      headers: {
        Accept: "text/event-stream",
        "Last-Event-ID": String(lastEventId),
        "X-Role": actor.role,
        "X-User-Id": actor.id,
      },
      signal,
    },
  );
  if (!response.ok || !response.body) {
    throw new Error(`事件流连接失败（HTTP ${response.status}）`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let cursor = lastEventId;

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || "";
    for (const block of blocks) {
      const event = parseSseBlock(block);
      if (!event) continue;
      if (event.id != null) cursor = Math.max(cursor, event.id);
      await onEvent?.(event);
    }
    if (done) break;
  }

  if (buffer.trim()) {
    const event = parseSseBlock(buffer);
    if (event) {
      if (event.id != null) cursor = Math.max(cursor, event.id);
      await onEvent?.(event);
    }
  }
  return cursor;
}
