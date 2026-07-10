import { expect, test } from "@playwright/test";
import {
  GLOBAL_CASE_IDS,
  GLOBAL_REVIEW_ID,
  installGlobalWidthFixture,
} from "./fixtures/global-width.fixture.js";

const routes = [
  {
    name: "dispute overview",
    path: "/disputes",
    role: "USER",
    ready: `[data-case-id="${GLOBAL_CASE_IDS.intake}"]`,
  },
  {
    name: "intake room",
    path: `/disputes/${GLOBAL_CASE_IDS.intake}/intake`,
    role: "USER",
    ready: "[data-case-detail-dossier]",
  },
  {
    name: "evidence room",
    path: `/disputes/${GLOBAL_CASE_IDS.evidence}/evidence`,
    role: "USER",
    ready: "[data-evidence-card]",
  },
  {
    name: "hearing court",
    path: `/disputes/${GLOBAL_CASE_IDS.hearing}/hearing`,
    role: "USER",
    ready: "[data-hearing-stage-dock]",
  },
  {
    name: "outcome",
    path: `/disputes/${GLOBAL_CASE_IDS.outcome}/outcome`,
    role: "USER",
    ready: ".verdict-card",
  },
  {
    name: "review queue",
    path: "/reviews",
    role: "PLATFORM_REVIEWER",
    ready: "[data-review-task]",
  },
  {
    name: "review workbench",
    path: `/reviews/${GLOBAL_REVIEW_ID}`,
    role: "PLATFORM_REVIEWER",
    ready: "[data-packet-status]",
  },
  {
    name: "agent console",
    path: "/agents",
    role: "PLATFORM_REVIEWER",
    ready: "[data-agent-console]",
  },
];

const compactViewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

async function horizontalOverflowReport(page) {
  return page.evaluate(() => {
    const root = document.documentElement;
    const viewportWidth = root.clientWidth;
    const offenders = [...document.querySelectorAll("body *")]
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return {
          tag: element.tagName.toLowerCase(),
          id: element.id,
          className:
            typeof element.className === "string" ? element.className : "",
          left: Math.round(rect.left * 10) / 10,
          right: Math.round(rect.right * 10) / 10,
          width: Math.round(rect.width * 10) / 10,
          display: style.display,
          visibility: style.visibility,
          position: style.position,
        };
      })
      .filter(
        ({ display, visibility, left, right }) =>
          display !== "none" &&
          visibility !== "hidden" &&
          (left < -1 || right > viewportWidth + 1),
      )
      .sort((left, right) => right.right - left.right)
      .slice(0, 20);

    return {
      viewportWidth,
      scrollWidth: root.scrollWidth,
      bodyClientWidth: document.body.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
      offenders,
    };
  });
}

for (const viewport of compactViewports) {
  for (const route of routes) {
    test(`${route.name} has no document overflow at ${viewport.width}px`, async ({
      page,
    }) => {
      const pageErrors = [];
      page.on("pageerror", (error) => pageErrors.push(error.message));
      await page.setViewportSize(viewport);
      await installGlobalWidthFixture(page, { role: route.role });

      await page.goto(route.path, { waitUntil: "domcontentloaded" });
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );

      await expect(page.locator(route.ready)).toBeVisible();

      const report = await horizontalOverflowReport(page);
      expect(
        pageErrors,
        `${route.path} emitted page errors at ${viewport.width}px`,
      ).toEqual([]);
      test.fail(
        route.name === "outcome" && viewport.width === 320,
        "Tracked by room-by-room rollout Task 5: Outcome still has a 320px width floor.",
      );
      expect(
        report.scrollWidth <= report.viewportWidth + 1,
        `${route.path} overflowed at ${viewport.width}px:\n${JSON.stringify(
          report,
          null,
          2,
        )}`,
      ).toBe(true);
    });
  }
}
