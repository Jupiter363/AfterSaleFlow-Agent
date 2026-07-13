// 文件作用：自动化测试文件，验证 outcome.layout.spec 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

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

const compactViewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

// 业务位置：【前端浏览器回归测试】openOutcome：切换与 阶段处理结果或草案 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function openOutcome(page, { viewport, scenario = "final", role = "USER" }) {
  await page.setViewportSize(viewport);
  await installOutcomeFixture(page, { scenario, role });
  await page.goto(`/disputes/${OUTCOME_CASE_ID}/outcome`);
  await expect(page.locator(".outcome-page")).toBeVisible();
}

// 业务位置：【前端浏览器回归测试】captureLayoutScreenshot：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
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

// 业务位置：【前端浏览器回归测试】horizontalOverflowReport：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
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

// 业务位置：【前端浏览器回归测试】assertNoPageHorizontalOverflow：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertNoPageHorizontalOverflow(page) {
  const report = await horizontalOverflowReport(page);
  expect(
    report.scrollWidth <= report.viewportWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
}

// 业务位置：【前端浏览器回归测试】assertScrollableWhenLong：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertScrollableWhenLong(locator) {
  const metrics = await locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      overflowY: style.overflowY,
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
    };
  });
  expect(["auto", "scroll"].includes(metrics.overflowY)).toBe(true);
  expect(metrics.scrollHeight).toBeGreaterThanOrEqual(metrics.clientHeight);
  return metrics.scrollHeight > metrics.clientHeight;
}

// 业务位置：【前端浏览器回归测试】assertLongTextGroupContained：核验 面向当事人的业务文本 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertLongTextGroupContained(locator) {
  const states = [];
  for (const card of await locator.all()) {
    states.push(await assertScrollableWhenLong(card));
  }
  expect(
    states.some(Boolean),
    "At least one card in the long-text group should use local scrolling.",
  ).toBe(true);
}

for (const viewport of compactViewports) {
  for (const scenario of ["final", "draft"]) {
    // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
    test(`contains ${scenario} outcome at ${viewport.width}px`, async ({
      page,
    }) => {
      await openOutcome(page, { viewport, scenario });

      await expect(page.locator(".verdict-card")).toBeVisible();
      if (scenario === "draft") {
        await expect(page.locator("[data-adjudication-draft]")).toBeVisible();
        await expect(page.locator("[data-explanation-officer]")).toBeVisible();
      }

      await assertNoPageHorizontalOverflow(page);
      await captureLayoutScreenshot(page, { viewport, scenario, role: "USER" });
    });
  }

  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`contains reviewer operation panel at ${viewport.width}px`, async ({
    page,
  }) => {
    await openOutcome(page, {
      viewport,
      scenario: "reviewer",
      role: "PLATFORM_REVIEWER",
    });

    const panel = page.locator("[data-outcome-review-panel]");
    await expect(panel).toBeVisible();
    await expect(panel.locator("[data-review-plan-editor]")).toBeVisible();
    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, {
      viewport,
      scenario: "reviewer",
      role: "PLATFORM_REVIEWER",
    });
  });
}

for (const viewport of compactViewports) {
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps long outcome text inside fixed cards at ${viewport.width}px`, async ({
    page,
  }) => {
    await openOutcome(page, { viewport, scenario: "long" });

    await assertNoPageHorizontalOverflow(page);
    await assertScrollableWhenLong(page.locator(".verdict-card h2"));
    await assertScrollableWhenLong(page.locator(".verdict-card > p").first());
    await assertLongTextGroupContained(page.locator(".draft-explain-grid article"));
    await assertLongTextGroupContained(
      page.locator(".explanation-officer-card__body article"),
    );
    await captureLayoutScreenshot(page, {
      viewport,
      scenario: "long",
      role: "USER",
    });
  });
}
