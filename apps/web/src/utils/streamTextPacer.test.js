import { describe, expect, it, vi } from "vitest";
import { createStreamTextPacer } from "./streamTextPacer";

describe("streamTextPacer bounded queue", () => {
  it("fails with a replayable slow-consumer signal before exceeding capacity", () => {
    const pacer = createStreamTextPacer({
      onReveal: vi.fn(),
      maxPendingCharacters: 5,
      scheduleFrame: vi.fn(() => ({ kind: "test" })),
      cancelFrame: vi.fn(),
    });

    pacer.enqueue("room_utterance", "12345");

    expect(() => pacer.assertCapacity("6")).toThrowError(
      expect.objectContaining({
        code: "AGENT_STREAM_SLOW_CONSUMER",
        retryable: true,
      }),
    );
    expect(pacer.pendingCharacters).toBe(5);
    expect(() => pacer.enqueue("room_utterance", "6")).toThrowError(
      expect.objectContaining({ code: "AGENT_STREAM_SLOW_CONSUMER" }),
    );
  });

  it("flushes received text when the visual-frame watchdog fires", async () => {
    const scheduled = [];
    const revealed = [];
    const pacer = createStreamTextPacer({
      onReveal: (_fieldPath, fragment) => revealed.push(fragment),
      scheduleFrame: vi.fn((callback) => {
        scheduled.push(callback);
        return { kind: "test" };
      }),
      cancelFrame: vi.fn(),
    });

    pacer.enqueue("room_utterance", "已经收到的完整流式正文");
    expect(pacer.pendingCharacters).toBeGreaterThan(1);

    scheduled.shift()({ watchdog: true });

    expect(revealed.join("")).toBe("已经收到的完整流式正文");
    expect(pacer.pendingCharacters).toBe(0);

    const drained = pacer.drain();
    scheduled.shift()({ watchdog: true });
    await drained;
  });
});
