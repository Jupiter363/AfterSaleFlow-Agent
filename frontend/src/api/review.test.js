import { beforeEach, describe, expect, it, vi } from "vitest";
import { reviewApi } from "./review";

describe("reviewApi", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ success: true, data: [{ id: "REVIEW_1" }] }),
    }));
    vi.stubGlobal("crypto", { randomUUID: () => "idem-review-1" });
  });

  it("sends reviewer identity and idempotency headers", async () => {
    const actor = { id: "reviewer-1", role: "PLATFORM_REVIEWER" };
    await reviewApi.decide(actor, "REVIEW_1", {
      decision: "APPROVE",
      reason: "verified",
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/review-tasks/REVIEW_1/decision",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-User-Id": "reviewer-1",
          "X-Role": "PLATFORM_REVIEWER",
          "Idempotency-Key": "idem-review-1",
        }),
      }),
    );
  });

  it("shows a readable fallback when the backend omits an error message", async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      json: async () => ({ success: false }),
    });

    await expect(
      reviewApi.list({ id: "reviewer-1", role: "PLATFORM_REVIEWER" }),
    ).rejects.toThrow("请求失败");
  });
});
