// 文件作用：自动化测试文件，验证 summons-mailbox.layout.spec 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task9-summons-mailbox/",
    import.meta.url,
  ),
);

const user = { id: "user-local", role: "USER", label: "鐢ㄦ埛" };
const longToken = "SUMMONS_MAILBOX_LONG_UNBROKEN_TOKEN_".repeat(30);

const viewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

// 业务位置：【前端浏览器回归测试】fulfillJson：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

// 业务位置：【前端浏览器回归测试】notifications：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function notifications(scenario = "normal") {
  const pressure = scenario === "long";
  return [
    {
      id: "NOTICE_MAILBOX_LAYOUT_1",
      case_id: pressure ? `CASE_${longToken}` : "CASE_MAILBOX_LAYOUT",
      notification_type: "DISPUTE_SUMMONS",
      title: pressure ? `浜夎浼犵エ ${longToken}` : "浜夎浼犵エ",
      body: pressure
        ? `璇疯繘鍏ュ搴旀埧闂村鐞嗗綋鍓嶆浠躲€?${longToken}`
        : "璇疯繘鍏ヨ瘉鎹功璁板畼瀹ゆ彁浜ゆ潗鏂欍€?",
      deep_link: "/disputes/CASE_MAILBOX_LAYOUT/evidence",
      read: false,
      created_at: "2026-07-11T10:00:00+08:00",
    },
    {
      id: "NOTICE_MAILBOX_LAYOUT_2",
      case_id: "CASE_MAILBOX_SECOND",
      notification_type: "HEARING_OPENED",
      title: pressure ? `灏忔硶搴凡寮€鏀?${longToken}` : "灏忔硶搴凡寮€鏀?",
      body: pressure ? `${longToken}${longToken}` : "鍙屾柟鍙繘鍏ュ皬娉曞涵銆?",
      deep_link: "/disputes/CASE_MAILBOX_SECOND/hearing",
      read: true,
      created_at: "2026-07-11T10:05:00+08:00",
    },
  ];
}

// 业务位置：【前端浏览器回归测试】installMailboxFixture：围绕 模型请求和结构化结果 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function installMailboxFixture(page, scenario = "normal") {
  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, user);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(request.headers()).toMatchObject({
      "x-user-id": user.id,
      "x-role": user.role,
    });

    if (request.method() === "GET" && url.pathname === "/api/notifications") {
      return fulfillJson(route, notifications(scenario));
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return fulfillJson(route, { unread_count: 1 });
    }
    if (request.method() === "GET" && url.pathname === "/api/disputes") {
      return fulfillJson(route, { items: [] });
    }

    throw new Error(
      `Unhandled summons-mailbox browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}

// 业务位置：【前端浏览器回归测试】openMailbox：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function openMailbox(page, { viewport, scenario = "normal" }) {
  await page.setViewportSize(viewport);
  await installMailboxFixture(page, scenario);
  await page.goto("/disputes");
  await expect(page.locator(".summons-mailbox__trigger")).toBeVisible();
  await page.locator(".summons-mailbox__trigger").click();
  await expect(page.locator(".summons-mailbox__drawer")).toBeVisible();
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
      `task9-summons-mailbox-${viewport.width}x${viewport.height}-${scenario}.png`,
    ),
    fullPage: true,
  });
}

for (const viewport of viewports) {
  for (const scenario of ["normal", "long"]) {
    // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
    test(`keeps summons mailbox contained at ${viewport.width}px with ${scenario} data`, async ({
      page,
    }) => {
      await openMailbox(page, { viewport, scenario });

      await assertNoPageHorizontalOverflow(page);
      await captureLayoutScreenshot(page, { viewport, scenario });
    });
  }
}
