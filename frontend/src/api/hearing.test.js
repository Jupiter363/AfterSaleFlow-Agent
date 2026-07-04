import { afterEach, describe, expect, it, vi } from "vitest";
import { hearingApi } from "./hearing";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.restoreAllMocks();
});

describe("hearing API", () => {
  it("submits a party hearing round without using the trusted complete endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          round_id: "ROUND_1",
          round_no: 1,
          status: "WAITING",
          submitted_roles: ["USER"],
        },
      }),
    });

    await hearingApi.submitRound(actor, "CASE_1", {
      dossier_version: 2,
      statement_json: '{"text":"本轮陈述"}',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/hearing/rounds/current/submissions",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          dossier_version: 2,
          statement_json: '{"text":"本轮陈述"}',
        }),
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
  });
});
