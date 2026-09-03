import { afterEach, describe, expect, it, vi } from "vitest";
import { hearingApi } from "../api/hearing";
import {
  hearingStore,
  loadHearing,
  resetHearingStore,
} from "./hearing";

afterEach(() => {
  resetHearingStore();
  vi.restoreAllMocks();
});

describe("hearing store authority scope", () => {
  it("discards a late private projection after the authenticated actor changes", async () => {
    let resolveUser;
    const userResponse = new Promise((resolve) => {
      resolveUser = resolve;
    });
    vi.spyOn(hearingApi, "hearing")
      .mockReturnValueOnce(userResponse)
      .mockResolvedValueOnce({ status: { stage_code: "PARTY_EVIDENCE_OPEN" } });

    const userLoad = loadHearing(
      { id: "user-local", role: "USER" },
      "CASE_1",
    );
    const merchantProjection = await loadHearing(
      { id: "merchant-local", role: "MERCHANT" },
      "CASE_1",
    );
    resolveUser({
      status: { stage_code: "PARTY_ANSWERS_OPEN" },
      private_statement: "user-private",
    });

    expect(await userLoad).toBeNull();
    expect(merchantProjection.status.stage_code).toBe("PARTY_EVIDENCE_OPEN");
    expect(hearingStore.hearing.data).toEqual(merchantProjection);
    expect(hearingStore.hearing.data.private_statement).toBeUndefined();
    expect(hearingStore.scopeKey).toBe("MERCHANT:merchant-local:CASE_1");
  });

  it("keeps only the newest request for one actor and case", async () => {
    let resolveFirst;
    const firstResponse = new Promise((resolve) => {
      resolveFirst = resolve;
    });
    vi.spyOn(hearingApi, "hearing")
      .mockReturnValueOnce(firstResponse)
      .mockResolvedValueOnce({ status: { stage_code: "INTAKE_SYNTHESIZING" } });

    const firstLoad = loadHearing({ id: "user-local", role: "USER" }, "CASE_1");
    const newest = await loadHearing({ id: "user-local", role: "USER" }, "CASE_1");
    resolveFirst({ status: { stage_code: "COURT_PREPARING" } });

    expect(await firstLoad).toBeNull();
    expect(hearingStore.hearing.data).toEqual(newest);
  });
});
