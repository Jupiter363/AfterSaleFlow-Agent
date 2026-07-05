import { beforeEach, describe, expect, it, vi } from "vitest";

describe("actor state", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  it("defaults the local demo experience to the user-side journey", async () => {
    const { actor } = await import("./actor");

    expect(actor.id).toBe("user-local");
    expect(actor.role).toBe("USER");
  });

  it("migrates the previous reviewer default to the user-side journey", async () => {
    localStorage.setItem(
      "dispute-actor",
      JSON.stringify({ id: "reviewer-local", role: "PLATFORM_REVIEWER" }),
    );

    const { actor } = await import("./actor");

    expect(actor.id).toBe("user-local");
    expect(actor.role).toBe("USER");
  });
});
