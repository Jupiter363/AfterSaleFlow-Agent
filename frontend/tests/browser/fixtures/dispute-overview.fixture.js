// 文件作用：自动化测试文件，验证 dispute-overview.fixture 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect } from "@playwright/test";

export const OVERVIEW_ACTOR = {
  id: "user-local",
  role: "USER",
  label: "用户",
};

// 业务位置：【前端浏览器回归测试】repeatToLength：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

export const LONG_CASE_ID = "C".repeat(200);
export const LONG_ORDER_ID = "O".repeat(200);
export const LONG_TITLE = repeatToLength("超长争议标题", 96);
export const LONG_GUIDE = repeatToLength("请核验全部履约链路后继续处理", 80);
const futureDeadline = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();

// 业务位置：【前端浏览器回归测试】buildCases：把 页面夹具和拦截 API 响应 组装为本块需要的 当前阶段业务数据，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildCases(scenario = "normal") {
  if (!["normal", "long-unbroken"].includes(scenario)) {
    throw new Error(`Unsupported dispute overview scenario: ${scenario}`);
  }

  const underPressure = scenario === "long-unbroken";
  return [
    {
      id: underPressure ? LONG_CASE_ID : "CASE_OVERVIEW_LAYOUT",
      order_id: underPressure ? LONG_ORDER_ID : "ORDER-OVERVIEW-001",
      source_type: "EXTERNAL_IMPORT",
      dispute_type: "SIGNED_NOT_RECEIVED",
      case_status: "REVIEW_PENDING",
      current_room: "REVIEW",
      deadline_at: futureDeadline,
      risk_level: "HIGH",
      pending_action: underPressure ? LONG_GUIDE : "AWAIT_REVIEW",
      title: underPressure ? LONG_TITLE : "签收未收到争议",
    },
    {
      id: "CASE_OVERVIEW_EVIDENCE",
      order_id: "ORDER-OVERVIEW-002",
      source_type: "INTAKE_CREATED",
      dispute_type: "DAMAGED_GOODS",
      case_status: "EVIDENCE_OPEN",
      current_room: "EVIDENCE",
      deadline_at: null,
      risk_level: "MEDIUM",
      pending_action: "SUBMIT_EVIDENCE",
      title: "到货破损争议",
    },
    {
      id: "CASE_OVERVIEW_HEARING",
      order_id: "ORDER-OVERVIEW-003",
      source_type: "EXTERNAL_IMPORT",
      dispute_type: "FULFILLMENT_CONFLICT",
      case_status: "HEARING_OPEN",
      current_room: "HEARING",
      deadline_at: null,
      risk_level: "CRITICAL",
      pending_action: "PARTICIPATE_HEARING",
      title: "履约事实争议",
    },
    {
      id: "CASE_OVERVIEW_CLOSED",
      order_id: "ORDER-OVERVIEW-004",
      source_type: "INTAKE_CREATED",
      dispute_type: "QUALITY_DISPUTE",
      case_status: "CLOSED",
      current_room: "OUTCOME",
      deadline_at: null,
      risk_level: "LOW",
      pending_action: "VIEW_OUTCOME",
      title: "已完成争议",
    },
  ];
}

// 业务位置：【前端浏览器回归测试】fulfillJson：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

// 业务位置：【前端浏览器回归测试】installDisputeOverviewFixture：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
export async function installDisputeOverviewFixture(page, options = {}) {
  const scenario = options.scenario || "normal";
  const cases = buildCases(scenario);

  await page.addInitScript((actor) => {
    localStorage.setItem("dispute-actor", JSON.stringify(actor));
  }, OVERVIEW_ACTOR);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(request.headers()).toMatchObject({
      "x-user-id": OVERVIEW_ACTOR.id,
      "x-role": OVERVIEW_ACTOR.role,
    });

    if (request.method() === "GET" && url.pathname === "/api/notifications") {
      return fulfillJson(route, []);
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return fulfillJson(route, { unread_count: 0 });
    }
    if (request.method() === "GET" && url.pathname === "/api/disputes") {
      return fulfillJson(route, { items: cases });
    }

    throw new Error(
      `Unhandled dispute-overview browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}
