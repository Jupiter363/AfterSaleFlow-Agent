import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../api/evidence", () => ({
  evidenceApi: {
    dossier: vi.fn(),
    catalog: vi.fn(),
    processProjection: vi.fn(),
  },
}));

import { evidenceApi } from "../api/evidence";
import { evidenceStore, loadEvidenceWorkspace } from "./evidence";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe("evidenceStore", () => {
  beforeEach(() => {
    evidenceApi.dossier.mockReset();
    evidenceApi.catalog.mockReset();
    evidenceApi.processProjection.mockReset();
    evidenceApi.dossier.mockResolvedValue(null);
    evidenceApi.catalog.mockResolvedValue([]);
    evidenceApi.processProjection.mockResolvedValue({
      schema_version: "evidence-process-projection.v1",
      projection_state: "PROCESSING",
    });
    Object.assign(evidenceStore.processProjection, {
      status: "idle",
      data: null,
      error: null,
      updatedAt: null,
    });
  });

  it("loads the process projection as a separate read-only resource", async () => {
    const actor = { id: "user-local", role: "USER" };

    await loadEvidenceWorkspace(actor, "CASE_1");

    expect(evidenceApi.processProjection).toHaveBeenCalledWith(actor, "CASE_1");
    expect(evidenceStore.processProjection.data).toEqual({
      schema_version: "evidence-process-projection.v1",
      projection_state: "PROCESSING",
    });
  });

  it("clears the private projection and ignores a late response after a case switch", async () => {
    const actor = { id: "case-switch-user", role: "USER" };
    const staleResponse = deferred();
    const currentResponse = deferred();
    const previousProjection = { projection_state: "CASE_A_PRIVATE" };
    const currentProjection = { projection_state: "CASE_B_PRIVATE" };
    evidenceApi.processProjection
      .mockResolvedValueOnce(previousProjection)
      .mockReturnValueOnce(staleResponse.promise)
      .mockReturnValueOnce(currentResponse.promise);

    await loadEvidenceWorkspace(actor, "CASE_A");
    const staleLoad = loadEvidenceWorkspace(actor, "CASE_A");
    const currentLoad = loadEvidenceWorkspace(actor, "CASE_B");

    expect(evidenceStore.processProjection).toMatchObject({
      status: "loading",
      data: null,
      error: null,
      updatedAt: null,
    });

    currentResponse.resolve(currentProjection);
    await currentLoad;
    const currentState = { ...evidenceStore.processProjection };

    staleResponse.resolve({ projection_state: "CASE_A_STALE" });
    await staleLoad;

    expect(evidenceStore.processProjection).toEqual(currentState);
    expect(evidenceStore.processProjection.data).toEqual(currentProjection);
  });

  it("ignores a late error after a role-only projection scope switch", async () => {
    const user = { id: "shared-actor", role: "USER" };
    const merchant = { id: "shared-actor", role: "MERCHANT" };
    const staleError = deferred();
    const currentProjection = { projection_state: "MERCHANT_PRIVATE" };
    evidenceApi.processProjection
      .mockReturnValueOnce(staleError.promise)
      .mockResolvedValueOnce(currentProjection);

    const staleLoad = loadEvidenceWorkspace(user, "CASE_ROLE");
    await loadEvidenceWorkspace(merchant, "CASE_ROLE");
    const currentState = { ...evidenceStore.processProjection };

    staleError.reject(new Error("stale user projection failed"));
    await staleLoad;

    expect(evidenceStore.processProjection).toEqual(currentState);
    expect(evidenceStore.processProjection).toMatchObject({
      status: "ready",
      data: currentProjection,
      error: null,
    });
  });

  it("ignores a late response after an actor-only projection scope switch", async () => {
    const staleResponse = deferred();
    const currentProjection = { projection_state: "CURRENT_ACTOR_PRIVATE" };
    evidenceApi.processProjection
      .mockReturnValueOnce(staleResponse.promise)
      .mockResolvedValueOnce(currentProjection);

    const staleLoad = loadEvidenceWorkspace(
      { id: "previous-user", role: "USER" },
      "CASE_ACTOR",
    );
    await loadEvidenceWorkspace(
      { id: "current-user", role: "USER" },
      "CASE_ACTOR",
    );
    const currentState = { ...evidenceStore.processProjection };

    staleResponse.resolve({ projection_state: "PREVIOUS_ACTOR_STALE" });
    await staleLoad;

    expect(evidenceStore.processProjection).toEqual(currentState);
    expect(evidenceStore.processProjection.data).toEqual(currentProjection);
  });
});
