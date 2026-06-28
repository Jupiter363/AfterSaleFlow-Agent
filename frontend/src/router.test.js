import { describe, expect, it } from "vitest";
import { routes } from "./router";

describe("frontend routes", () => {
  it("exposes case, party submission and human review workspaces", () => {
    expect(routes.map((route) => route.path)).toEqual(
      expect.arrayContaining([
        "/cases",
        "/cases/:caseId",
        "/cases/:caseId/submissions/user",
        "/cases/:caseId/submissions/merchant",
        "/review",
      ]),
    );
  });
});
