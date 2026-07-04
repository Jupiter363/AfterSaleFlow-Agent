import { describe, expect, it } from "vitest";
import { routeAccessDecision } from "./access";

describe("route access", () => {
  const reviewRoute = { meta: { roles: ["PLATFORM_REVIEWER"] } };

  it("allows a listed role", () => {
    expect(
      routeAccessDecision(reviewRoute, { role: "PLATFORM_REVIEWER" }),
    ).toBe(true);
  });

  it("redirects parties away from reviewer-only routes", () => {
    expect(routeAccessDecision(reviewRoute, { role: "USER" })).toEqual({
      path: "/disputes",
      query: { access: "reviewer-only" },
    });
  });
});
