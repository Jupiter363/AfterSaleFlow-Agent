import { describe, expect, it, vi } from "vitest";
import { createRoomState, resumeRoomEvents } from "./room";

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
});
