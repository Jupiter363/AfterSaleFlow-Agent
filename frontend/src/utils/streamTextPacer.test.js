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
});
