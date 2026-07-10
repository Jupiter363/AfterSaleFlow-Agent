import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("global responsive width", () => {
  it("does not impose a 320px minimum width on root scrolling elements", () => {
    const source = readFileSync("src/styles.css", "utf8");
    expect(source).not.toMatch(/html\s*\{[^}]*min-width:\s*320px/);
    expect(source).not.toMatch(/body\s*\{[^}]*min-width:\s*320px/);
  });
});
