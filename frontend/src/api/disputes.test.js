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

  it("simulates external dispute imports through the public demo adapter endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          items: [
            {
              case_id: "CASE_simulated",
              source_type: "EXTERNAL_IMPORT",
              initiator_role: "USER",
            },
          ],
        },
      }),
    });

    const result = await disputeApi.simulateExternalImport(actor, {
      count: 2,
      scenario: "手表售后争议",
      risk_level_hint: "MEDIUM",
      initiator_role_hint: "USER",
      current_actor_id: "user-local",
      counterparty_actor_id: "merchant-local",
    });

    const [, requestOptions] = fetchMock.mock.calls[0];
    const requestBody = JSON.parse(requestOptions.body);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/import/simulate",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "Idempotency-Key": expect.stringMatching(/^external-import-/),
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(requestBody).toEqual(
      expect.objectContaining({
        count: 2,
        scenario: "手表售后争议",
        risk_level_hint: "MEDIUM",
        initiator_role_hint: "USER",
        current_actor_id: "user-local",
        counterparty_actor_id: "merchant-local",
        simulation_batch_id: expect.stringMatching(/^external-import-/),
      }),
    );
    expect(requestBody.simulation_batch_id).toBe(
      requestOptions.headers["Idempotency-Key"],
    );
    expect(result.items[0].case_id).toBe("CASE_simulated");
  });
});
