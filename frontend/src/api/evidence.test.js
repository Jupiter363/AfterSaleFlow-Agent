import { beforeEach, describe, expect, it, vi } from "vitest";
import { evidenceApi } from "./evidence";

const actor = { id: "user-local", role: "USER" };

describe("evidenceApi", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ success: true, data: {} }),
      }),
    );
    vi.stubGlobal("crypto", { randomUUID: () => "uuid-1" });
  });

  it("submits a pending evidence batch with an idempotency key", async () => {
    await evidenceApi.submitBatch(
      actor,
      "CASE_1",
      { evidence_ids: ["EVIDENCE_1"], batch_note: "" },
      "batch-key-1",
    );

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/evidence/submissions",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-User-Id": "user-local",
          "X-Role": "USER",
          "Idempotency-Key": "batch-key-1",
          "Content-Type": "application/json",
        }),
        body: JSON.stringify({
          evidence_ids: ["EVIDENCE_1"],
          batch_note: "",
        }),
      }),
    );
  });

  it("deletes only a pending evidence item through the evidence endpoint", async () => {
    await evidenceApi.deletePending(actor, "CASE_1", "EVIDENCE_1");

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/evidence/EVIDENCE_1",
      expect.objectContaining({
        method: "DELETE",
        headers: expect.objectContaining({
          "X-User-Id": "user-local",
          "X-Role": "USER",
        }),
      }),
    );
  });
});
