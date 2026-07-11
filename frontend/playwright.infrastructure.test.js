import { readFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  GLOBAL_CASE_IDS,
  assertGlobalRoomMessageEndpoint,
} from "./tests/browser/fixtures/global-width.fixture.js";

const originalPlaywrightPort = process.env.PLAYWRIGHT_PORT;

afterEach(() => {
  if (originalPlaywrightPort === undefined) {
    delete process.env.PLAYWRIGHT_PORT;
  } else {
    process.env.PLAYWRIGHT_PORT = originalPlaywrightPort;
  }
  vi.resetModules();
});

describe("Playwright worktree isolation", () => {
  test("derives the browser server and base URL from PLAYWRIGHT_PORT", async () => {
    process.env.PLAYWRIGHT_PORT = "43173";
    vi.resetModules();

    const { default: config } = await import("./playwright.config.js");

    expect(config.use.baseURL).toBe("http://127.0.0.1:43173");
    expect(config.webServer.url).toBe("http://127.0.0.1:43173");
    expect(config.webServer.command).toContain("--port 43173 --strictPort");
  });

  test.each(["not-a-port", "0", "65536", "4173suffix"])(
    "rejects an invalid PLAYWRIGHT_PORT value: %s",
    async (value) => {
      process.env.PLAYWRIGHT_PORT = value;
      vi.resetModules();

      await expect(import("./playwright.config.js")).rejects.toThrow(
        /PLAYWRIGHT_PORT must be an integer between 1 and 65535/,
      );
    },
  );

  test("starts this worktree's Vite server instead of reusing another one", async () => {
    const { default: config } = await import("./playwright.config.js");

    expect(config.webServer.reuseExistingServer).toBe(false);
  });

  test("layout specs inherit the configured base URL instead of pinning a port", async () => {
    const source = await readFile(
      path.join(
        process.cwd(),
        "tests/browser/hearing-court.layout.spec.js",
      ),
      "utf8",
    );

    expect(source).not.toContain("http://127.0.0.1:4173");
  });
});

describe("global width fixture room routing", () => {
  test.each([
    [GLOBAL_CASE_IDS.intake, "INTAKE"],
    [GLOBAL_CASE_IDS.evidence, "EVIDENCE"],
    [GLOBAL_CASE_IDS.hearing, "HEARING"],
  ])("accepts the single message endpoint for %s", (caseId, roomType) => {
    expect(assertGlobalRoomMessageEndpoint(caseId, roomType)).toBe(roomType);
  });

  test("rejects a known case ID routed to another room's messages", () => {
    expect(() =>
      assertGlobalRoomMessageEndpoint(GLOBAL_CASE_IDS.intake, "EVIDENCE"),
    ).toThrow(/CASE_GLOBAL_INTAKE.*INTAKE.*EVIDENCE/);
  });

  test.each([GLOBAL_CASE_IDS.outcome, "CASE_UNKNOWN"])(
    "rejects case IDs without a room-message fixture: %s",
    (caseId) => {
      expect(() =>
        assertGlobalRoomMessageEndpoint(caseId, "HEARING"),
      ).toThrow(/does not expose a room messages endpoint/);
    },
  );
});

test("global width smoke keeps strict page health checks without expected failures", async () => {
  const source = await readFile(
    path.join(process.cwd(), "tests/browser/global-width-smoke.spec.js"),
    "utf8",
  );
  const readyIndex = source.indexOf(
    "await expect(page.locator(route.ready)).toBeVisible();",
  );
  const measurementIndex = source.indexOf(
    "const report = await horizontalOverflowReport(page);",
  );
  const pageErrorIndex = source.indexOf(
    '`${route.path} emitted page errors at ${viewport.width}px`,',
  );
  const widthAssertionIndex = source.indexOf(
    "report.scrollWidth <= report.viewportWidth + 1",
  );

  expect(readyIndex).toBeGreaterThan(-1);
  expect(measurementIndex).toBeGreaterThan(readyIndex);
  expect(pageErrorIndex).toBeGreaterThan(measurementIndex);
  expect(widthAssertionIndex).toBeGreaterThan(pageErrorIndex);
  expect(source).not.toContain("test.fail(");
});
