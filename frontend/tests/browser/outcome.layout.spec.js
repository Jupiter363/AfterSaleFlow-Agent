// 执行结果页布局回归：审批后按四段展示庭审、审核、方案与执行情况。

import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  installOutcomeFixture,
  OUTCOME_CASE_ID,
} from "./fixtures/outcome.fixture.js";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task5-outcome/",
    import.meta.url,
  ),
);

const desktopViewport = { width: 1440, height: 1100 };
const compactViewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];
const allViewports = [desktopViewport, ...compactViewports];
const outcomeRoles = ["USER", "PLATFORM_REVIEWER"];
const internalUiSelectors = [
  "[data-adjudication-draft]",
  "[data-explanation-officer]",
  "[data-outcome-review-panel]",
  "[data-review-plan-editor]",
];

async function openOutcome(
  page,
  { viewport, scenario = "final", role = "USER" },
) {
  await page.setViewportSize(viewport);
  await installOutcomeFixture(page, { scenario, role });
  await page.goto(`/disputes/${OUTCOME_CASE_ID}/outcome`);

  await expect(page.locator(".outcome-page")).toBeVisible();
  const header = page.locator(".room-shell__header");
  await expect(header).toBeVisible();
  await expect(header.locator("h1")).toHaveText("执行结果");
  await expect(header.locator(".room-shell__context")).toContainText(
    "最终结果归档",
  );
}

async function captureLayoutScreenshot(
  page,
  { viewport, scenario, role, track = "fullpage" },
) {
  if (process.env.CAPTURE_LAYOUT_SCREENSHOTS !== "1") return;
  await mkdir(screenshotDirectory, { recursive: true });
  const filename = [
    "task5-outcome",
    `${viewport.width}x${viewport.height}`,
    role.toLowerCase(),
    scenario,
    track,
  ].join("-");
  await page.screenshot({
    path: path.join(screenshotDirectory, `${filename}.png`),
    fullPage: track === "fullpage",
  });
}

async function horizontalOverflowReport(page) {
  return page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    const offenders = [...document.querySelectorAll("body *")]
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return {
          tag: element.tagName.toLowerCase(),
          className:
            typeof element.className === "string" ? element.className : "",
          left: Math.round(rect.left * 10) / 10,
          right: Math.round(rect.right * 10) / 10,
          width: Math.round(rect.width * 10) / 10,
          display: style.display,
          visibility: style.visibility,
        };
      })
      .filter(
        ({ display, visibility, left, right }) =>
          display !== "none" &&
          visibility !== "hidden" &&
          (left < -1 || right > viewportWidth + 1),
      )
      .slice(0, 20);

    return {
      viewportWidth,
      scrollWidth: document.documentElement.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      offenders,
    };
  });
}

async function assertNoPageHorizontalOverflow(page) {
  const report = await horizontalOverflowReport(page);
  expect(
    report.scrollWidth <= report.viewportWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
  expect(
    report.bodyScrollWidth <= report.viewportWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
}

async function assertNoInternalOrReviewerUi(page) {
  await expect(page.locator(internalUiSelectors.join(","))).toHaveCount(0);
  await expect(page.locator("textarea")).toHaveCount(0);
  await expect(
    page.getByRole("button", {
      name: /确认裁决|确认结果|修改方案|退回重审|提交审核/,
    }),
  ).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(
    "INTERNAL_DRAFT_DO_NOT_RENDER",
  );
  await expect(page.locator("body")).not.toContainText(
    "INTERNAL_EXPLANATION_DO_NOT_RENDER",
  );
  await expect(page.locator("body")).not.toContainText(
    "INTERNAL_REVIEW_DO_NOT_RENDER",
  );
  await expect(page.locator("body")).not.toContainText(
    "INTERNAL_PLAN_DO_NOT_RENDER",
  );
}

async function assertFinalOutcome(page, { expectReceipt = true } = {}) {
  await expect(page.locator("[data-outcome-waiting]")).toHaveCount(0);
  await expect(page.locator("[data-outcome-summary-layout]")).toBeVisible();
  await expect(page.locator("[data-outcome-hearing]")).toBeVisible();
  await expect(page.locator("[data-outcome-review]")).toBeVisible();
  await expect(page.locator("[data-outcome-plan]")).toBeVisible();
  await expect(page.locator("[data-outcome-execution]")).toBeVisible();
  if (expectReceipt) {
    await expect(page.locator("[data-execution-receipt]")).not.toHaveCount(0);
  }
  await assertNoInternalOrReviewerUi(page);
}

async function assertPendingOutcome(page) {
  await expect(page.locator("[data-outcome-waiting]")).toBeVisible();
  await expect(page.locator("[data-outcome-summary-layout]")).toHaveCount(0);
  await expect(page.locator("[data-outcome-hearing]")).toHaveCount(0);
  await expect(page.locator("[data-outcome-review]")).toHaveCount(0);
  await expect(page.locator("[data-outcome-plan]")).toHaveCount(0);
  await expect(page.locator("[data-outcome-execution]")).toHaveCount(0);
  await expect(page.locator("[data-mock-execution]")).toHaveCount(0);
  await expect(page.locator("[data-execution-receipt]")).toHaveCount(0);
  await assertNoInternalOrReviewerUi(page);
}

async function assertDesktopSummaryColumns(page) {
  const hearing = await page.locator("[data-outcome-hearing]").boundingBox();
  const review = await page.locator("[data-outcome-review]").boundingBox();
  expect(hearing).not.toBeNull();
  expect(review).not.toBeNull();
  expect(Math.abs(hearing.y - review.y)).toBeLessThanOrEqual(2);
  expect(Math.abs(hearing.height - review.height)).toBeLessThanOrEqual(2);
  expect(hearing.x + hearing.width).toBeLessThan(review.x);
}

async function assertLocallyScrollable(locator) {
  const metrics = await locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      overflowY: style.overflowY,
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
    };
  });

  expect(["auto", "scroll"]).toContain(metrics.overflowY);
  expect(metrics.scrollHeight).toBeGreaterThan(metrics.clientHeight);
}

for (const role of outcomeRoles) {
  test(`desktop final result is read-only for ${role}`, async ({ page }) => {
    await openOutcome(page, {
      viewport: desktopViewport,
      scenario: "final",
      role,
    });

    await assertFinalOutcome(page);
    await expect(page.locator("[data-outcome-hearing]")).toContainText(
      "庭审法官 V2",
    );
    await expect(page.locator("[data-outcome-hearing]")).toContainText("v7");
    await expect(page.locator("[data-outcome-review]")).toContainText(
      "管理员审核意见",
    );
    await expect(page.locator("[data-outcome-plan]")).toContainText(
      "最终执行方案",
    );
    await expect(page.locator("[data-outcome-plan]")).toContainText(
      "向用户原支付渠道退还订单实付金额 299 元",
    );
    await expect(page.locator("[data-execution-receipt]")).toHaveCount(2);
    await assertDesktopSummaryColumns(page);
    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, {
      viewport: desktopViewport,
      scenario: "final",
      role,
    });
  });

  test(`desktop pending result only shows waiting state for ${role}`, async ({
    page,
  }) => {
    await openOutcome(page, {
      viewport: desktopViewport,
      scenario: "pending",
      role,
    });

    await assertPendingOutcome(page);
    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, {
      viewport: desktopViewport,
      scenario: "pending",
      role,
    });
  });
}

test("frontend mock execution advances without a real receipt", async ({
  page,
}) => {
  await openOutcome(page, {
    viewport: desktopViewport,
    scenario: "mock",
  });

  await assertFinalOutcome(page, { expectReceipt: false });
  await expect(page.locator("[data-execution-receipt]")).toHaveCount(0);
  const mock = page.locator("[data-mock-execution]");
  await expect(mock).toBeVisible();
  await expect(mock.locator(".mock-execution__summary strong")).toHaveText(
    "执行准备",
    { timeout: 7_000 },
  );
  await assertNoPageHorizontalOverflow(page);
});

for (const viewport of compactViewports) {
  for (const scenario of ["final", "pending"]) {
    test(`${scenario} result fits ${viewport.width}px mobile viewport`, async ({
      page,
    }) => {
      await openOutcome(page, { viewport, scenario });

      if (scenario === "final") await assertFinalOutcome(page);
      else await assertPendingOutcome(page);
      await assertNoPageHorizontalOverflow(page);
      await captureLayoutScreenshot(page, {
        viewport,
        scenario,
        role: "USER",
      });
    });
  }
}

for (const viewport of allViewports) {
  test(`long approved result stays contained at ${viewport.width}px`, async ({
    page,
  }) => {
    await openOutcome(page, { viewport, scenario: "long" });

    await assertFinalOutcome(page);
    await assertLocallyScrollable(
      page.locator("[data-outcome-hearing] .outcome-scroll-copy"),
    );
    await assertLocallyScrollable(
      page.locator("[data-outcome-review] .outcome-scroll-copy"),
    );
    await assertLocallyScrollable(
      page.locator("[data-outcome-plan] .outcome-scroll-copy"),
    );

    const receipts = page.locator("[data-execution-receipt]");
    await expect(receipts).toHaveCount(1);
    const receipt = receipts.first();
    await expect(receipt.locator(".execution-result")).toBeVisible();
    await expect(receipt).toContainText("幂等键");
    await expect(receipt).toContainText("回执状态");
    await expect(receipt).not.toContainText("{");

    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, {
      viewport,
      scenario: "long",
      role: "USER",
    });
  });
}
