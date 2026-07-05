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

  it("cancels intake when the issue is resolved before admission", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          case_id: "CASE_cancel",
          case_status: "CANCELLED",
          current_room: null,
        },
      }),
    });

    const result = await disputeApi.cancelIntake(
      actor,
      "CASE_cancel",
      "Issue resolved by negotiation.",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_cancel/intake/cancel",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ reason: "Issue resolved by negotiation." }),
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(result.case_status).toBe("CANCELLED");
  });
});
