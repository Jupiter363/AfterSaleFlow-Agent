import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const routerSource = readFileSync(
  resolve(process.cwd(), "src/router/index.js"),
  "utf8",
);

function declaredPaths() {
  return [...routerSource.matchAll(/path:\s*"([^"]+)"/g)].map((match) => match[1]);
}

describe("final room-based routes", () => {
  it("exposes the overview, three rooms, outcome and reviewer workspace", () => {
    expect(declaredPaths()).toEqual(
      expect.arrayContaining([
        "/disputes",
        "/disputes/:caseId/intake",
        "/disputes/:caseId/evidence",
        "/disputes/:caseId/hearing",
        "/disputes/:caseId/outcome",
        "/reviews",
        "/reviews/:reviewId",
        "/agents",
      ]),
    );
  });

  it("does not expose legacy cases, generic workspace or deliberation pages", () => {
    expect(declaredPaths()).not.toEqual(
      expect.arrayContaining([
        "/cases",
        "/disputes/:caseId",
        "/disputes/:caseId/deliberation",
      ]),
    );
  });

  it("declares reviewer and agent operation routes as PLATFORM_REVIEWER only", () => {
    const reviewerRoleDeclarations =
      routerSource.match(/roles:\s*\["PLATFORM_REVIEWER"\]/g) || [];
    expect(reviewerRoleDeclarations).toHaveLength(3);
  });
});
