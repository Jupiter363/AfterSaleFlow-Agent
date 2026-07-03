import { beforeEach, describe, expect, it, vi } from "vitest";
import { caseApi } from "./cases";

const actor = { id: "user-1", role: "USER" };

describe("caseApi", () => {
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

  it("builds encoded role-scoped list filters", async () => {
    await caseApi.list(actor, {
      status: "WAITING_HUMAN_REVIEW",
      case_type: "DISPUTE",
      page: 0,
      size: 20,
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes?status=WAITING_HUMAN_REVIEW&page=0&size=20",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-User-Id": "user-1",
          "X-Role": "USER",
        }),
      }),
    );
  });

  it("leaves multipart content type to the browser", async () => {
    const file = new File(["proof"], "proof.pdf", {
      type: "application/pdf",
    });
    await caseApi.uploadEvidence(actor, "CASE_1", file, {
      evidenceType: "LOGISTICS_PROOF",
      sourceType: "USER_UPLOAD",
      visibility: "PARTIES",
    });

    const [, options] = fetch.mock.calls[0];
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.headers["Content-Type"]).toBeUndefined();
    expect(options.body.get("file")).toBe(file);
  });

  it("submits party evidence with an idempotency key", async () => {
    await caseApi.submitEvidence(actor, "CASE_1", "user", {
      submission_text: "签收照片不是本人",
      evidence_ids: ["EVIDENCE_1"],
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/rooms/EVIDENCE/messages",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Idempotency-Key": "message-user-uuid-1",
        }),
      }),
    );
  });
});
