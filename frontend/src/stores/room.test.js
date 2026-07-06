import { describe, expect, it, vi } from "vitest";
import { createRoomState, resumeRoomEvents, streamRoomEvents } from "./room";

vi.mock("../api/rooms", () => ({
  consumeCaseEvents: vi.fn(),
  roomApi: {
    messages: vi.fn(),
  },
}));

import { consumeCaseEvents } from "../api/rooms";

describe("room event recovery", () => {
  it("reloads the authoritative snapshot before applying replayed events", async () => {
    const order = [];
    const state = createRoomState();
    state.lastEventId = 7;
    const snapshotLoader = vi.fn(async () => {
      order.push("snapshot");
    });
    const eventConsumer = vi.fn(async ({ lastEventId, onEvent }) => {
      order.push(`consume:${lastEventId}`);
      await onEvent({ id: 8, event: "HEARING_OPENED", data: {} });
      return 8;
    });

    await resumeRoomEvents({
      state,
      snapshotLoader,
      eventConsumer,
      applyEvent: async (event) => {
        order.push(`apply:${event.id}`);
      },
    });

    expect(order).toEqual([
      "snapshot",
      "consume:7",
      "snapshot",
      "apply:8",
    ]);
    expect(state.lastEventId).toBe(8);
    expect(state.connected).toBe(true);
  });

  it("persists each durable cursor even when the stream disconnects mid-flight", async () => {
    const state = createRoomState();
    state.lastEventId = 11;

    await expect(
      resumeRoomEvents({
        state,
        snapshotLoader: vi.fn(),
        eventConsumer: async ({ onEvent }) => {
          await onEvent({ id: 12, event: "ROOM_MESSAGE_CREATED", data: {} });
          throw new Error("socket closed");
        },
      }),
    ).rejects.toThrow("socket closed");

    expect(state.lastEventId).toBe(12);
  });

  it("isolates durable cursors by case when the user switches disputes", async () => {
    const state = createRoomState();
    const consumedCursors = [];
    const snapshotLoader = vi.fn();

    const consume = (nextCursor) => async ({ lastEventId }) => {
      consumedCursors.push(lastEventId);
      return nextCursor;
    };

    await resumeRoomEvents({
      state,
      cursorKey: "CASE_A",
      snapshotLoader,
      eventConsumer: consume(13),
    });
    await resumeRoomEvents({
      state,
      cursorKey: "CASE_B",
      snapshotLoader,
      eventConsumer: consume(2),
    });
    await resumeRoomEvents({
      state,
      cursorKey: "CASE_A",
      snapshotLoader,
      eventConsumer: consume(13),
    });

    expect(consumedCursors).toEqual([0, 0, 13]);
    expect(state.lastEventIds).toEqual({ CASE_A: 13, CASE_B: 2 });
  });

  it("isolates durable cursors by case, room and actor for room streams", async () => {
    const state = createRoomState();
    const consumedCursors = [];
    const userAbort = new AbortController();
    const merchantAbort = new AbortController();
    const userAgainAbort = new AbortController();
    consumeCaseEvents
      .mockImplementationOnce(async ({ lastEventId, onEvent }) => {
        consumedCursors.push(lastEventId);
        await onEvent({ id: 17, event: "ROOM_MESSAGE_CREATED", data: {} });
        return 17;
      })
      .mockImplementationOnce(async ({ lastEventId, onEvent }) => {
        consumedCursors.push(lastEventId);
        await onEvent({ id: 3, event: "ROOM_MESSAGE_CREATED", data: {} });
        return 3;
      })
      .mockImplementationOnce(async ({ lastEventId }) => {
        consumedCursors.push(lastEventId);
        userAgainAbort.abort();
        return 17;
      });
    const snapshotLoader = vi.fn();
    const runOnce = (abortController) => async () => {
      abortController.abort();
    };

    await streamRoomEvents({
      actor: { id: "user-local", role: "USER" },
      caseId: "CASE_A",
      roomType: "EVIDENCE",
      state,
      signal: userAbort.signal,
      snapshotLoader,
      applyEvent: runOnce(userAbort),
      retryDelayMs: 0,
    });
    await streamRoomEvents({
      actor: { id: "merchant-local", role: "MERCHANT" },
      caseId: "CASE_A",
      roomType: "EVIDENCE",
      state,
      signal: merchantAbort.signal,
      snapshotLoader,
      applyEvent: runOnce(merchantAbort),
      retryDelayMs: 0,
    });
    await streamRoomEvents({
      actor: { id: "user-local", role: "USER" },
      caseId: "CASE_A",
      roomType: "EVIDENCE",
      state,
      signal: userAgainAbort.signal,
      snapshotLoader,
      applyEvent: runOnce(userAgainAbort),
      retryDelayMs: 0,
    });

    expect(consumedCursors).toEqual([0, 0, 17]);
    expect(state.lastEventIds).toEqual({
      "CASE_A:EVIDENCE:user-local:USER": 17,
      "CASE_A:EVIDENCE:merchant-local:MERCHANT": 3,
    });
  });
});
