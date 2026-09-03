// 文件作用：自动化测试文件，验证 dispute-overview.layout.spec 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  installDisputeOverviewFixture,
  LONG_CASE_ID,
  LONG_GUIDE,
  LONG_ORDER_ID,
  LONG_TITLE,
} from "./fixtures/dispute-overview.fixture.js";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task4-dispute-overview/",
    import.meta.url,
  ),
);

const viewportMatrix = [
  { width: 1021, height: 900, columns: 2 },
  { width: 1020, height: 900, columns: 1 },
  { width: 681, height: 900, columns: 1 },
  { width: 680, height: 900, columns: 1 },
  { width: 361, height: 900, columns: 1 },
  { width: 360, height: 900, columns: 1 },
  { width: 390, height: 844, columns: 1 },
  { width: 320, height: 568, columns: 1 },
  { width: 1024, height: 600, columns: 2 },
];

// 业务位置：【前端浏览器回归测试】openOverview：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function openOverview(page, viewport, scenario = "normal") {
  await page.setViewportSize(viewport);
  await installDisputeOverviewFixture(page, { scenario });
  await page.goto("/disputes");
  await expect(page.locator(".overview-layout")).toBeVisible();
}

// 业务位置：【前端浏览器回归测试】captureLayoutScreenshot：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
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

// 业务位置：【前端浏览器回归测试】assertInside：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
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

// 业务位置：【前端浏览器回归测试】assertNoPageHorizontalOverflow：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
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

// 业务位置：【前端浏览器回归测试】gridTrackCount：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function gridTrackCount(locator, property) {
  return locator.evaluate((element, name) => {
    const value = getComputedStyle(element)[name];
    return value.split(" ").filter(Boolean).length;
  }, property);
}

test("keeps terminal review locked for parties before and after results open", async ({
  page,
}) => {
  await openOverview(page, { width: 1024, height: 900 });

  const currentRoomButton = page.locator("[data-enter-current-room]");
  const reviewStage = page.locator('[data-stage-room="REVIEW"]');
  const reviewEntry = reviewStage.locator('[data-stage-entry="REVIEW"]');

  await expect(currentRoomButton).toBeDisabled();
  await expect(currentRoomButton).toContainText("等待人工终审");
  await expect(reviewStage).toHaveAttribute("data-stage-state", "locked");
  await expect(reviewEntry).toHaveAttribute("data-permission-locked", "true");
  await reviewEntry.click();
  await expect(page.locator("[data-review-permission-dialog]")).toContainText(
    "抱歉您没有权限",
  );
  await page.locator("[data-close-review-permission]").click();

  await page.locator('[data-case-id="CASE_OVERVIEW_CLOSED"]').click();
  await expect(currentRoomButton).toBeEnabled();
  await expect(currentRoomButton).toContainText("查看最终结果");
  await expect(reviewStage).toHaveAttribute("data-stage-state", "locked");
  await reviewEntry.click();
  await expect(page.locator("[data-review-permission-dialog]")).toContainText(
    "抱歉您没有权限",
  );
});

for (const viewport of viewportMatrix) {
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps the fixed overview frame contract at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await openOverview(page, viewport);

    const layout = page.locator(".overview-layout");
    const rail = page.locator(".dispute-rail");
    const main = page.locator("[data-hearing-adventure]");
    const journey = page.locator("[data-adventure-path]");
    const mapViewport = page.locator(".hearing-adventure__viewport");
    const map = page.locator(".hearing-adventure__map");
    const stages = journey.locator(":scope > li");
    const dashboard = page.locator("[data-case-journey-dashboard]");

    const layoutBox = await layout.boundingBox();
    expect(layoutBox).not.toBeNull();
    if (viewport.columns === 2) {
      expect(layoutBox.height).toBeCloseTo(690, 0);
    } else {
      expect(layoutBox.height).toBeGreaterThanOrEqual(850);
      expect(layoutBox.height).toBeLessThanOrEqual(980);
    }
    expect(await gridTrackCount(layout, "gridTemplateColumns")).toBe(
      viewport.columns,
    );
    await expect(dashboard).toBeVisible();

    const [mapBox, stageBoxes] = await Promise.all([
      map.boundingBox(),
      stages.evaluateAll((items) =>
        items.map((item) => {
          const box = item.getBoundingClientRect();
          return { x: box.x, y: box.y, width: box.width, height: box.height };
        }),
      ),
    ]);
    expect(mapBox).not.toBeNull();
    expect(stageBoxes).toHaveLength(6);
    for (const stageBox of stageBoxes) {
      expect(stageBox.x).toBeGreaterThanOrEqual(mapBox.x - 1);
      expect(stageBox.x + stageBox.width).toBeLessThanOrEqual(
        mapBox.x + mapBox.width + 1,
      );
      expect(stageBox.y).toBeGreaterThanOrEqual(mapBox.y - 1);
      expect(stageBox.y + stageBox.height).toBeLessThanOrEqual(
        mapBox.y + mapBox.height + 1,
      );
    }

    await assertInside(rail, layout);
    await assertInside(main, layout);
    await assertInside(main.locator(".hearing-adventure__header"), main);
    await assertInside(mapViewport, main);
    await assertInside(dashboard, main);
    expect(
      await mapViewport.evaluate(
        (element) => element.scrollWidth > element.clientWidth + 1,
      ),
    ).toBe(true);
    for (const card of await dashboard.locator(":scope > article").all()) {
      await assertInside(card, dashboard);
    }

    const caseActions = page.locator(".overview-case-actions");
    for (const action of await caseActions.locator(":scope > button").all()) {
      await assertInside(action, caseActions);
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
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`contains long unbroken overview content at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await openOverview(page, viewport, "long-unbroken");

    const selectedTitle = page.locator(".hearing-adventure__header h2");
    const guideMessage = page.locator(
      "[data-overview-guide] .digital-human__copy p",
    );
    const activeTicket = page.locator(".dispute-ticket--active");
    const ticketAction = activeTicket.locator(":scope > small");
    const ticketTitle = activeTicket.locator(":scope > strong");

    await expect(page.locator("body")).not.toContainText(LONG_CASE_ID);
    await expect(page.locator("body")).not.toContainText(LONG_ORDER_ID);
    await expect(selectedTitle).toHaveAttribute("title", LONG_TITLE);
    await expect(ticketTitle).toContainText(LONG_TITLE);
    await expect(guideMessage).toContainText(LONG_GUIDE);

    const selectedTitleMetrics = await selectedTitle.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        overflow: style.overflow,
        textOverflow: style.textOverflow,
        whiteSpace: style.whiteSpace,
      };
    });
    expect(selectedTitleMetrics).toMatchObject({
      overflow: "hidden",
      textOverflow: "ellipsis",
      whiteSpace: "nowrap",
    });
    expect(selectedTitleMetrics.scrollWidth).toBeGreaterThan(
      selectedTitleMetrics.clientWidth,
    );

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
    await assertInside(ticketTitle, activeTicket);
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
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps the final stage visible at the map track end on ${viewport.width}px`, async ({
    page,
  }) => {
    await openOverview(page, viewport);

    const journey = page.locator("[data-adventure-path]");
    const mapViewport = page.locator(".hearing-adventure__viewport");
    const map = page.locator(".hearing-adventure__map");
    const dashboard = page.locator("[data-case-journey-dashboard]");
    await expect
      .poll(() =>
        mapViewport.evaluate(
          (element) => element.scrollWidth > element.clientWidth + 1,
        ),
      )
      .toBe(true);
    await mapViewport.evaluate((element) => {
      element.scrollLeft = element.scrollWidth;
    });
    await expect
      .poll(() => mapViewport.evaluate((element) => element.scrollLeft))
      .toBeGreaterThan(0);

    const [viewportBox, mapBox, dashboardBox, finalStageBox] = await Promise.all([
      mapViewport.boundingBox(),
      map.boundingBox(),
      dashboard.boundingBox(),
      journey.locator(":scope > li").last().boundingBox(),
    ]);
    expect(viewportBox).not.toBeNull();
    expect(mapBox).not.toBeNull();
    expect(dashboardBox).not.toBeNull();
    expect(finalStageBox).not.toBeNull();
    expect(finalStageBox.x).toBeGreaterThanOrEqual(viewportBox.x - 1);
    expect(finalStageBox.x + finalStageBox.width).toBeLessThanOrEqual(
      viewportBox.x + viewportBox.width + 1,
    );
    expect(finalStageBox.y).toBeGreaterThanOrEqual(mapBox.y - 1);
    expect(finalStageBox.y + finalStageBox.height).toBeLessThanOrEqual(
      mapBox.y + mapBox.height + 1,
    );
    expect(viewportBox.y).toBeGreaterThanOrEqual(
      dashboardBox.y + dashboardBox.height,
    );

    await assertNoPageHorizontalOverflow(page);
    await mapViewport.scrollIntoViewIfNeeded();
    await captureLayoutScreenshot(page, {
      viewport,
      track: "track-end",
    });
  });
}
