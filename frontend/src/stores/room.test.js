// 文件作用：自动化测试文件，验证 room.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { describe, expect, it, vi } from "vitest";
import {
  createRoomState,
  primeRoomEventCursor,
  resumeRoomEvents,
  streamRoomEvents,
} from "./room";

vi.mock("../api/rooms", () => ({
  consumeCaseEvents: vi.fn(),
  roomApi: {
    events: vi.fn(async () => []),
    messages: vi.fn(),
  },
}));

import { consumeCaseEvents, roomApi } from "../api/rooms";

// 业务位置：【前端状态仓库】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
describe("room event recovery", () => {
  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
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

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
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

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
  it("isolates durable cursors by case when the user switches disputes", async () => {
    const state = createRoomState();
    const consumedCursors = [];
    const snapshotLoader = vi.fn();

    // 业务位置：【前端状态仓库】consume：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
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

  // 业务位置：【前端状态仓库】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 API 响应、SSE 增量和用户操作 正确进入 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
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
    // 业务位置：【前端状态仓库】runOnce：执行 当前阶段业务数据 对应的业务动作，并将结果交给 跨组件一致的案件/房间/证据状态。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
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

  it("does not let a late prime from an old case contaminate the active cursor", async () => {
    const state = createRoomState();
    const actor = { id: "user-local", role: "USER" };
    let resolveOldPrime;
    let deliverNewEvent;
    roomApi.events
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveOldPrime = resolve;
      }))
      .mockResolvedValueOnce([
        { sequence_no: 2, event_type: "ROOM_MESSAGE_CREATED" },
      ]);

    const oldPrime = primeRoomEventCursor({
      actor,
      caseId: "CASE_OLD",
      roomType: "INTAKE",
      state,
    });
    await primeRoomEventCursor({
      actor,
      caseId: "CASE_NEW",
      roomType: "INTAKE",
      state,
    });
    const activeStream = resumeRoomEvents({
      state,
      cursorKey: "CASE_NEW:INTAKE:user-local:USER",
      snapshotLoader: vi.fn(),
      eventConsumer: ({ lastEventId, onEvent }) => {
        expect(lastEventId).toBe(2);
        return new Promise((resolve) => {
          deliverNewEvent = async () => {
            await onEvent({ id: 3, event: "INTAKE_PROJECTION_READY", data: {} });
            resolve(3);
          };
        });
      },
    });
    await vi.waitFor(() => expect(deliverNewEvent).toBeTypeOf("function"));

    resolveOldPrime([
      { sequence_no: 99, event_type: "ROOM_MESSAGE_CREATED" },
    ]);
    await oldPrime;
    await deliverNewEvent();
    await activeStream;

    expect(state.lastEventIds).toEqual({
      "CASE_OLD:INTAKE:user-local:USER": 99,
      "CASE_NEW:INTAKE:user-local:USER": 3,
    });
    expect(state.lastEventId).toBe(3);
  });

  it.each(["snapshot", "apply"])(
    "replays an event when %s processing fails before cursor commit",
    async (failureStage) => {
      const state = createRoomState();
      const cursorKey = `CASE_REPLAY_${failureStage.toUpperCase()}`;
      const event = { id: 8, event: "INTAKE_PROJECTION_READY", data: {} };
      const consumedCursors = [];
      let snapshotCalls = 0;
      let applyCalls = 0;
      state.lastEventIds[cursorKey] = 7;
      const snapshotLoader = vi.fn(async () => {
        snapshotCalls += 1;
        if (failureStage === "snapshot" && snapshotCalls === 2) {
          throw new Error("snapshot unavailable");
        }
      });
      const applyEvent = vi.fn(async () => {
        applyCalls += 1;
        if (failureStage === "apply" && applyCalls === 1) {
          throw new Error("apply failed");
        }
      });
      const consume = async ({ lastEventId, onEvent }) => {
        consumedCursors.push(lastEventId);
        await onEvent(event);
        return event.id;
      };

      await expect(resumeRoomEvents({
        state,
        cursorKey,
        snapshotLoader,
        eventConsumer: consume,
        applyEvent,
      })).rejects.toThrow();
      expect(state.lastEventIds[cursorKey]).toBe(7);

      await resumeRoomEvents({
        state,
        cursorKey,
        snapshotLoader,
        eventConsumer: consume,
        applyEvent,
      });

      expect(consumedCursors).toEqual([7, 7]);
      expect(state.lastEventIds[cursorKey]).toBe(8);
      expect(state.lastEventId).toBe(8);
    },
  );

  it("starts a fresh room subscription after the replayed snapshot baseline", async () => {
    const state = createRoomState();
    const abortController = new AbortController();
    roomApi.events.mockResolvedValueOnce([
      { sequence_no: 7, event_type: "AGENT_RUN_STARTED" },
      { sequence_no: 9, event_type: "ROOM_MESSAGE_CREATED" },
    ]);
    consumeCaseEvents.mockImplementationOnce(async ({ lastEventId }) => {
      expect(lastEventId).toBe(9);
      abortController.abort();
      return 9;
    });

    await streamRoomEvents({
      actor: { id: "user-local", role: "USER" },
      caseId: "CASE_BASELINE",
      roomType: "HEARING",
      state,
      signal: abortController.signal,
      snapshotLoader: vi.fn(),
      retryDelayMs: 0,
    });

    expect(state.lastEventIds["CASE_BASELINE:HEARING:user-local:USER"]).toBe(9);
  });

  it("replays events emitted after a cursor is primed and before SSE connects", async () => {
    const state = createRoomState();
    const abortController = new AbortController();
    const actor = { id: "user-local", role: "USER" };
    roomApi.events.mockResolvedValueOnce([
      { sequence_no: 12, event_type: "ROOM_MESSAGE_CREATED" },
    ]);

    await primeRoomEventCursor({
      actor,
      caseId: "CASE_OPENING",
      roomType: "INTAKE",
      state,
    });
    const replayCallsAfterPrime = roomApi.events.mock.calls.length;

    const readyEvent = {
      id: 13,
      event: "INTAKE_PROJECTION_READY",
      data: { payload_json: "{}" },
    };
    const applyEvent = vi.fn(async () => abortController.abort());
    consumeCaseEvents.mockImplementationOnce(async ({ lastEventId, onEvent }) => {
      expect(lastEventId).toBe(12);
      await onEvent(readyEvent);
      return 13;
    });

    await streamRoomEvents({
      actor,
      caseId: "CASE_OPENING",
      roomType: "INTAKE",
      state,
      signal: abortController.signal,
      snapshotLoader: vi.fn(),
      applyEvent,
      retryDelayMs: 0,
    });

    expect(roomApi.events).toHaveBeenLastCalledWith(actor, "CASE_OPENING", 0);
    expect(roomApi.events).toHaveBeenCalledTimes(replayCallsAfterPrime);
    expect(applyEvent).toHaveBeenCalledWith(readyEvent);
    expect(state.lastEventIds["CASE_OPENING:INTAKE:user-local:USER"]).toBe(13);
  });
});
