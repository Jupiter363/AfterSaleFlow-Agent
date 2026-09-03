// 文件作用：自动化测试文件，验证 agent-console.layout.spec 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task8-agent-console/",
    import.meta.url,
  ),
);

const reviewer = {
  id: "reviewer-local",
  role: "PLATFORM_REVIEWER",
  label: "骞冲彴瀹℃牳鍛?",
};

const longToken = "AGENT_CONFIG_LONG_UNBROKEN_TOKEN_".repeat(34);

const viewports = [
  { width: 1440, height: 1100 },
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

// 业务位置：【前端浏览器回归测试】installAgentConsoleFixture：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function installAgentConsoleFixture(page) {
  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, reviewer);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(request.headers()).toMatchObject({
      "x-user-id": reviewer.id,
      "x-role": reviewer.role,
    });

    if (request.method() === "GET" && url.pathname === "/api/notifications") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: [] }),
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: { unread_count: 0 } }),
      });
    }
    if (request.method() === "GET" && url.pathname === "/api/disputes") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: { items: [] } }),
      });
    }

    throw new Error(
      `Unhandled agent-console browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}

// 业务位置：【前端浏览器回归测试】openAgentConsole：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function openAgentConsole(page, viewport) {
  await page.setViewportSize(viewport);
  await installAgentConsoleFixture(page);
  await page.goto("/agents");
  await expect(page.locator("[data-agent-console]")).toBeVisible();
}

// 业务位置：【前端浏览器回归测试】injectLongAgentCopy：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function injectLongAgentCopy(page) {
  await page.evaluate((token) => {
    for (const selector of [
      ".agent-console__description",
      ".fleet-status small",
      ".agent-list-item__copy small",
      ".agent-list-item__copy em",
      ".governance-note span",
      ".agent-detail__summary",
      ".agent-detail-header__tags span",
      ".agent-info-list dd",
      ".panel-toolbar p",
      ".metric-cell dt",
      ".metric-cell small",
      ".overview-section__header p",
      ".run-row span",
      ".prompt-selector dd",
      ".prompt-editor textarea",
      ".safety-panel li",
      ".switch-row p",
      ".authority-block code",
      ".authority-block li",
      ".version-row__main p",
    ]) {
      for (const element of document.querySelectorAll(selector)) {
        if (element instanceof HTMLTextAreaElement) {
          element.value = `${element.value} ${token}`;
        } else {
          element.textContent = `${element.textContent} ${token}`;
        }
      }
    }
  }, longToken);
}

// 业务位置：【前端浏览器回归测试】assertNoPageHorizontalOverflow：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertNoPageHorizontalOverflow(page) {
  const report = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    const offenders = [...document.querySelectorAll("body *")]
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
      .slice(0, 20);
    return {
      viewportWidth,
      scrollWidth: document.documentElement.scrollWidth,
      offenders,
    };
  });
  expect(
    report.scrollWidth <= report.viewportWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
}

// 业务位置：【前端浏览器回归测试】captureLayoutScreenshot：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function captureLayoutScreenshot(page, { viewport, scenario }) {
  if (process.env.CAPTURE_LAYOUT_SCREENSHOTS !== "1") return;
  await mkdir(screenshotDirectory, { recursive: true });
  await page.screenshot({
    path: path.join(
      screenshotDirectory,
      `task8-agent-console-${viewport.width}x${viewport.height}-${scenario}.png`,
    ),
    fullPage: true,
  });
}

for (const viewport of viewports) {
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps agent console contained at ${viewport.width}px`, async ({
    page,
  }) => {
    await openAgentConsole(page, viewport);

    await expect(page.locator("[data-info-panel]")).toBeVisible();
    await expect(page.locator(".agent-workbench > .agent-detail-header")).toHaveCount(0);
    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, { viewport, scenario: "normal" });
  });

  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps future long agent config copy contained at ${viewport.width}px`, async ({
    page,
  }) => {
    await openAgentConsole(page, viewport);
    await injectLongAgentCopy(page);

    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, { viewport, scenario: "long" });
  });

  test(`keeps every agent management view contained at ${viewport.width}px`, async ({
    page,
  }) => {
    await openAgentConsole(page, viewport);

    for (const scenario of ["overview", "prompt", "strategy", "debug", "versions", "info"]) {
      await page.locator(`[data-agent-tab="${scenario}"]`).click();
      await expect(page.locator(`[data-${scenario}-panel]`)).toBeVisible();
      await assertNoPageHorizontalOverflow(page);
      await captureLayoutScreenshot(page, { viewport, scenario });
    }

    await page.locator("[data-change-avatar]").click();
    await expect(page.locator(".avatar-dialog")).toBeVisible();
    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, { viewport, scenario: "avatar-picker" });
    await page.locator(".avatar-dialog .dialog-close").click();
  });
}

test("keeps the desktop workbench lower edge stable between management views", async ({
  page,
}) => {
  await openAgentConsole(page, { width: 1440, height: 1100 });

  const manager = page.locator(".agent-manager");
  const initialBox = await manager.boundingBox();
  expect(initialBox).not.toBeNull();
  const expectedCardCounts = {
    info: 4,
    overview: 4,
    prompt: 4,
    strategy: 6,
    debug: 2,
    versions: 2,
  };

  for (const scenario of ["info", "overview", "prompt", "strategy", "debug", "versions"]) {
    await page.locator(`[data-agent-tab="${scenario}"]`).click();
    const panel = page.locator(`[data-${scenario}-panel]`);
    await expect(panel).toBeVisible();

    const currentBox = await manager.boundingBox();
    expect(currentBox).not.toBeNull();
    expect(Math.abs(currentBox.height - initialBox.height)).toBeLessThanOrEqual(1);

    const layout = await panel.evaluate((element) => {
      const panelRect = element.getBoundingClientRect();
      const cards = [...element.querySelectorAll("[data-fixed-card]")].map((card) => {
        const rect = card.getBoundingClientRect();
        return {
          height: rect.height,
          top: rect.top,
          bottom: rect.bottom,
        };
      });
      const modeRow = element.querySelector(".switch-row--mode");
      const modeControl = modeRow?.querySelector(".thinking-mode-control");
      const followingRow = modeRow?.nextElementSibling;
      const modeControlBottom = modeControl?.getBoundingClientRect().bottom ?? 0;
      const followingRowTop = followingRow?.getBoundingClientRect().top ?? Infinity;
      return {
        clientHeight: element.clientHeight,
        scrollHeight: element.scrollHeight,
        overflowY: getComputedStyle(element).overflowY,
        panelTop: panelRect.top,
        panelBottom: panelRect.bottom,
        modeControlOverlapsNextRow: modeControlBottom > followingRowTop + 1,
        cards,
      };
    });

    expect(layout.overflowY).toBe("hidden");
    expect(layout.clientHeight).toBeGreaterThan(740);
    expect(layout.scrollHeight).toBeLessThanOrEqual(layout.clientHeight + 1);
    expect(layout.cards).toHaveLength(expectedCardCounts[scenario]);
    expect(layout.modeControlOverlapsNextRow).toBe(false);
    for (const card of layout.cards) {
      expect(card.height).toBeGreaterThan(60);
      expect(card.top).toBeGreaterThanOrEqual(layout.panelTop - 1);
      expect(card.bottom).toBeLessThanOrEqual(layout.panelBottom + 1);
    }
  }
});
