import { beforeEach, describe, expect, it, vi } from "vitest";

describe("actor state", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  it("defaults the local demo experience to the user-side journey", async () => {
    const { actor, demoActors } = await import("./actor");

    expect(actor.id).toBe("user-local");
    expect(actor.role).toBe("USER");
    expect(demoActors).toEqual([
      { id: "user-local", role: "USER", label: "用户" },
      { id: "merchant-local", role: "MERCHANT", label: "商家" },
      { id: "reviewer-local", role: "PLATFORM_REVIEWER", label: "平台审核员" },
    ]);
  });

  it("keeps a stored reviewer demo account instead of collapsing it into the user actor", async () => {
    localStorage.setItem(
      "dispute-actor",
      JSON.stringify({ id: "reviewer-local", role: "PLATFORM_REVIEWER" }),
    );

    const { actor } = await import("./actor");

    expect(actor.id).toBe("reviewer-local");
    expect(actor.role).toBe("PLATFORM_REVIEWER");
  });

  it("switches demo actors by role using the fixed actor id", async () => {
    const { actor, switchDemoActor } = await import("./actor");

    switchDemoActor("MERCHANT");

    expect(actor.id).toBe("merchant-local");
    expect(actor.role).toBe("MERCHANT");
  });
});
