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
      ".agent-console__intro p",
      ".agent-console__intro > strong",
      ".agent-ticket small",
      ".agent-ticket i",
      ".agent-panel__header p",
      ".agent-config-tabs small",
      ".jury-strategy__node",
      ".jury-strategy p",
      ".prompt-card dd",
      ".prompt-card p",
      ".skill-badges span",
      ".court-preview b",
      ".court-mapping p",
    ]) {
      for (const element of document.querySelectorAll(selector)) {
        element.textContent = `${element.textContent} ${token}`;
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
}
