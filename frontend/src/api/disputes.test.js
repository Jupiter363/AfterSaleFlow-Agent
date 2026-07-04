import { afterEach, describe, expect, it, vi } from "vitest";
import { disputeApi } from "./disputes";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.restoreAllMocks();
});

describe("dispute API", () => {
  it("loads the aggregated final outcome from the case endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          case_id: "CASE_outcome",
          final_decision: { human_confirmed: true },
          actions: [],
        },
      }),
    });

    const outcome = await disputeApi.outcome(
      actor,
      "CASE_outcome",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_outcome/outcome",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(outcome.final_decision.human_confirmed).toBe(true);
  });
});
