import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  installDisputeOverviewFixture,
  LONG_CASE_ID,
  LONG_GUIDE,
  LONG_ORDER_ID,
} from "./fixtures/dispute-overview.fixture.js";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task4-dispute-overview/",
    import.meta.url,
  ),
);

const viewportMatrix = [
  { width: 1021, height: 900, expectedHeight: 730, columns: 2 },
  { width: 1020, height: 900, expectedHeight: 810, columns: 1 },
  { width: 681, height: 900, expectedHeight: 810, columns: 1 },
  { width: 680, height: 900, expectedHeight: 880, columns: 1 },
  { width: 361, height: 900, expectedHeight: 880, columns: 1 },
  { width: 360, height: 900, expectedHeight: 940, columns: 1 },
  { width: 390, height: 844, expectedHeight: 880, columns: 1 },
  { width: 320, height: 568, expectedHeight: 940, columns: 1 },
  { width: 1024, height: 600, expectedHeight: 720, columns: 2 },
];

async function openOverview(page, viewport, scenario = "normal") {
  await page.setViewportSize(viewport);
  await installDisputeOverviewFixture(page, { scenario });
  await page.goto("/disputes");
  await expect(page.locator(".overview-layout")).toBeVisible();
}

async function captureLayoutScreenshot(
  page,
  { viewport, state = "user", scenario = "normal", track = "track-start" },
) {
  if (process.env.CAPTURE_LAYOUT_SCREENSHOTS !== "1") return;
  await mkdir(screenshotDirectory, { recursive: true });
  const filename = [
    "task4-disputes",
    `${viewport.width}x${viewport.height}`,
    state,
    scenario,
    track,
  ].join("-");
  await page.screenshot({
    path: path.join(screenshotDirectory, `${filename}.png`),
    fullPage: track === "fullpage",
  });
}

async function assertInside(inner, outer) {
  const [innerBox, outerBox] = await Promise.all([
    inner.boundingBox(),
    outer.boundingBox(),
  ]);
  expect(innerBox).not.toBeNull();
  expect(outerBox).not.toBeNull();
  expect(innerBox.x).toBeGreaterThanOrEqual(outerBox.x - 1);
  expect(innerBox.y).toBeGreaterThanOrEqual(outerBox.y - 1);
  expect(innerBox.x + innerBox.width).toBeLessThanOrEqual(
    outerBox.x + outerBox.width + 1,
  );
  expect(innerBox.y + innerBox.height).toBeLessThanOrEqual(
    outerBox.y + outerBox.height + 1,
  );
}

async function assertNoPageHorizontalOverflow(page) {
  const report = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    const hasOverflow =
      document.documentElement.scrollWidth > viewportWidth + 1;
    const offenders = hasOverflow
      ? [...document.querySelectorAll("body *")]
          .map((element) => {
            const rect = element.getBoundingClientRect();
            return {
              tag: element.tagName.toLowerCase(),
              className:
                typeof element.className === "string" ? element.className : "",
              left: Math.round(rect.left * 10) / 10,
              right: Math.round(rect.right * 10) / 10,
              width: Math.round(rect.width * 10) / 10,
            };
          })
          .filter(({ left, right }) => left < -1 || right > viewportWidth + 1)
          .slice(0, 12)
      : [];
    return {
      hasOverflow,
      viewportWidth,
      scrollWidth: document.documentElement.scrollWidth,
      offenders,
    };
  });
  expect(report.hasOverflow, JSON.stringify(report, null, 2)).toBe(false);
}

async function gridTrackCount(locator, property) {
  return locator.evaluate((element, name) => {
    const value = getComputedStyle(element)[name];
    return value.split(" ").filter(Boolean).length;
  }, property);
}

for (const viewport of viewportMatrix) {
  test(`keeps the fixed overview frame contract at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await openOverview(page, viewport);

    const layout = page.locator(".overview-layout");
    const rail = page.locator(".dispute-rail");
    const main = page.locator("[data-hearing-adventure]");
    const guide = page.locator("[data-overview-guide]");
    const journey = page.locator("[data-adventure-path]");
    const stages = journey.locator(":scope > li");
    const dashboard = page.locator("[data-case-journey-dashboard]");

    await expect(layout).toHaveCSS("height", `${viewport.expectedHeight}px`);
    expect(await gridTrackCount(layout, "gridTemplateColumns")).toBe(
      viewport.columns,
    );
    expect(await gridTrackCount(dashboard, "gridTemplateColumns")).toBe(2);
    expect(await gridTrackCount(dashboard, "gridTemplateRows")).toBe(2);

    const stageBoxes = await stages.evaluateAll((items) =>
      items.map((item) => {
        const box = item.getBoundingClientRect();
        return { x: box.x, y: box.y, width: box.width, height: box.height };
      }),
    );
    expect(stageBoxes).toHaveLength(5);
    expect(
      Math.max(...stageBoxes.map(({ y }) => y)) -
        Math.min(...stageBoxes.map(({ y }) => y)),
    ).toBeLessThanOrEqual(1);
    for (let index = 1; index < stageBoxes.length; index += 1) {
      expect(stageBoxes[index].x).toBeGreaterThan(stageBoxes[index - 1].x);
    }

    await assertInside(rail, layout);
    await assertInside(main, layout);
    await assertInside(main.locator(".hearing-adventure__header"), main);
    await assertInside(guide, main);
    await assertInside(journey, main);
    await assertInside(dashboard, main);
    for (const card of await dashboard.locator(":scope > article").all()) {
      await assertInside(card, dashboard);
    }

    if (viewport.width === 361 || viewport.width === 360) {
      const actions = page.locator(".overview-page__actions > button");
      const [first, second] = await Promise.all([
        actions.nth(0).boundingBox(),
        actions.nth(1).boundingBox(),
      ]);
      expect(first).not.toBeNull();
      expect(second).not.toBeNull();
      if (viewport.width === 361) {
        expect(Math.abs(first.y - second.y)).toBeLessThanOrEqual(1);
      } else {
        expect(second.y).toBeGreaterThanOrEqual(first.y + first.height - 1);
      }
    }

    if (viewport.height === 600) {
      expect(
        await page.evaluate(
          () =>
            document.documentElement.scrollHeight >
            document.documentElement.clientHeight,
        ),
      ).toBe(true);
    }

    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, {
      viewport,
      track: viewport.height === 600 ? "fullpage" : "track-start",
    });
  });
}

for (const viewport of [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
]) {
  test(`contains long unbroken overview content at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await openOverview(page, viewport, "long-unbroken");

    const caseValue = page.locator("[data-case-file-value]");
    const orderValue = page.locator("[data-order-value]");
    const nextAction = page.locator("[data-next-action-value]");
    const selectedTitle = page.locator(".hearing-adventure__header h2");
    const guideMessage = page.locator(
      "[data-overview-guide] .digital-human__copy p",
    );
    const activeTicket = page.locator(".dispute-ticket--active");
    const ticketAction = activeTicket.locator(":scope > small");

    await expect(caseValue).toHaveAttribute("title", LONG_CASE_ID);
    await expect(caseValue).toHaveAttribute("aria-label", LONG_CASE_ID);
    await expect(orderValue).toHaveAttribute("title", LONG_ORDER_ID);
    await expect(orderValue).toHaveAttribute("aria-label", LONG_ORDER_ID);
    await expect(nextAction).toHaveAttribute("title", LONG_GUIDE);
    await expect(nextAction).toHaveAttribute("aria-label", LONG_GUIDE);

    for (const value of [caseValue, orderValue]) {
      const metrics = await value.evaluate((element) => {
        const style = getComputedStyle(element);
        return {
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
          overflow: style.overflow,
          textOverflow: style.textOverflow,
          whiteSpace: style.whiteSpace,
        };
      });
      expect(metrics).toMatchObject({
        overflow: "hidden",
        textOverflow: "ellipsis",
        whiteSpace: "nowrap",
      });
      expect(metrics.scrollWidth).toBeGreaterThan(metrics.clientWidth);
    }

    for (const value of [nextAction, selectedTitle, guideMessage]) {
      const metrics = await value.evaluate((element) => {
        const style = getComputedStyle(element);
        return {
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
          clientHeight: element.clientHeight,
          lineHeight: Number.parseFloat(style.lineHeight),
          overflow: style.overflow,
          overflowWrap: style.overflowWrap,
          webkitLineClamp: style.webkitLineClamp,
        };
      });
      expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1);
      expect(metrics.overflowWrap).toBe("anywhere");
      expect(metrics.webkitLineClamp).toBe("2");
      expect(metrics.clientHeight).toBeLessThanOrEqual(
        metrics.lineHeight * 2 + 1,
      );
    }

    const ticketActionMetrics = await ticketAction.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        overflow: style.overflow,
        textOverflow: style.textOverflow,
        whiteSpace: style.whiteSpace,
      };
    });
    expect(ticketActionMetrics).toMatchObject({
      overflow: "hidden",
      textOverflow: "ellipsis",
      whiteSpace: "nowrap",
    });
    expect(ticketActionMetrics.scrollWidth).toBeGreaterThan(
      ticketActionMetrics.clientWidth,
    );
    await assertInside(ticketAction, activeTicket);

    await assertInside(
      page.locator("[data-overview-guide]"),
      page.locator("[data-hearing-adventure]"),
    );
    await assertInside(
      page.locator("[data-case-journey-dashboard]"),
      page.locator("[data-hearing-adventure]"),
    );
    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, {
      viewport,
      scenario: "long-unbroken",
    });
  });
}

for (const viewport of [
  { width: 680, height: 900 },
  { width: 390, height: 844 },
  { width: 360, height: 900 },
  { width: 320, height: 568 },
]) {
  test(`keeps stages four and five clear of the case index at track end on ${viewport.width}px`, async ({
    page,
  }) => {
    await openOverview(page, viewport);

    const journey = page.locator("[data-adventure-path]");
    const dashboard = page.locator("[data-case-journey-dashboard]");
    await expect
      .poll(() =>
        journey.evaluate(
          (element) => element.scrollWidth > element.clientWidth + 1,
        ),
      )
      .toBe(true);
    await journey.evaluate((element) => {
      element.scrollLeft = element.scrollWidth;
    });
    await expect
      .poll(() => journey.evaluate((element) => element.scrollLeft))
      .toBeGreaterThan(0);

    const [journeyBox, dashboardBox, fourthBox, fifthBox] = await Promise.all([
      journey.boundingBox(),
      dashboard.boundingBox(),
      journey.locator(":scope > li").nth(3).boundingBox(),
      journey.locator(":scope > li").nth(4).boundingBox(),
    ]);
    expect(journeyBox).not.toBeNull();
    expect(dashboardBox).not.toBeNull();
    expect(fourthBox).not.toBeNull();
    expect(fifthBox).not.toBeNull();
    for (const stageBox of [fourthBox, fifthBox]) {
      expect(stageBox.x).toBeGreaterThanOrEqual(journeyBox.x - 1);
      expect(stageBox.x + stageBox.width).toBeLessThanOrEqual(
        journeyBox.x + journeyBox.width + 1,
      );
      expect(stageBox.y + stageBox.height).toBeLessThanOrEqual(
        dashboardBox.y + 1,
      );
    }

    await assertNoPageHorizontalOverflow(page);
    await journey.scrollIntoViewIfNeeded();
    await captureLayoutScreenshot(page, {
      viewport,
      track: "track-end",
    });
  });
}
