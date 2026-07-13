// 文件作用：自动化测试文件，验证 intake-room.fixture 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect } from "@playwright/test";

export const CASE_ID = "CASE_INTAKE_LAYOUT";

const actor = { id: "user-local", role: "USER", label: "用户" };

// 业务位置：【前端浏览器回归测试】repeatToLength：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

// 业务位置：【前端浏览器回归测试】buildMessages：把 页面夹具和拦截 API 响应 组装为本块需要的 房间消息和对话记录，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildMessages({ count = 2, unbrokenLength = 0 } = {}) {
  const messages = [
    {
      id: "MESSAGE_AGENT_1",
      sequence_no: 1,
      sender_role: "CUSTOMER_SERVICE",
      message_type: "AGENT_MESSAGE",
      message_text: "请补充说明签收时间、地点以及你希望平台如何处理。",
    },
    {
      id: "MESSAGE_USER_1",
      sequence_no: 2,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "物流显示昨天下午签收，但我和家人都没有收到，希望核验后退款。",
    },
  ];
  for (let index = messages.length; index < count; index += 1) {
    messages.push({
      id: `MESSAGE_${index + 1}`,
      sequence_no: index + 1,
      sender_role: index % 2 === 0 ? "CUSTOMER_SERVICE" : "USER",
      message_type: index % 2 === 0 ? "AGENT_MESSAGE" : "PARTY_TEXT",
      message_text:
        unbrokenLength && index === count - 1
          ? "A".repeat(unbrokenLength)
          : repeatToLength("这是接待室布局压力消息。", 120),
    });
  }
  return messages;
}

// 业务位置：【前端浏览器回归测试】buildTurnMemory：把 页面夹具和拦截 API 响应 组装为本块需要的 案件会话和上下文快照，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildTurnMemory({
  summaryLength = 150,
  statementLength = 160,
  gapCount = 4,
} = {}) {
  const gaps = Array.from({ length: gapCount }, (_, index) =>
    repeatToLength(`第${index + 1}项核验重点需要核对签收主体与投递链路。`, 42),
  );
  return {
    turn_no: 4,
    case_intake_dossier: {
      dossier_version: 2,
      quality_score: 88,
      ready_for_next_step: true,
      admission_recommendation: "ACCEPTED",
      dossier: {
        schema_version: "intake_case_detail.v1",
        case_story: {
          title: "物流显示签收但用户称未收到商品",
          one_sentence_summary: repeatToLength(
            "订单物流已显示签收，但用户本人及家人均表示没有收到商品，签收链路仍待核验。",
            summaryLength,
          ),
        },
        references: {
          order_reference: "ORDER-1001",
          after_sales_reference: "AFTER-1001",
          logistics_reference: "SF1234567890",
        },
        party_positions: {
          user_claim: "用户称本人及家人均未收到商品，希望核验后退款。",
          merchant_claim: "",
        },
        claim_resolution: {
          initiator_role: "USER",
          requested_resolution: "REFUND",
          requested_amount: "299.00",
          normalized_statement: repeatToLength("用户请求核验签收链路并退款。", 40),
          original_statement: repeatToLength(
            "物流显示签收，但本人和家人都没有收到，希望平台核验后退款。",
            statementLength,
          ),
        },
        respondent_attitude: {
          respondent_role: "MERCHANT",
          attitude: "NOT_RESPONDED",
          position: repeatToLength("商家尚未明确回应退款诉求。", 40),
        },
        dispute_core_state: {
          core_conflict: "物流签收记录与用户未收到商品的陈述存在冲突。",
          facts_in_dispute: ["用户是否实际收到商品"],
          next_verification_focus: gaps,
        },
        dispute_focus: {
          core_issue: "签收记录与实际收货情况不一致",
          facts_to_verify: gaps,
        },
        missing_information: {
          blocking_gaps: gaps,
          nice_to_have_gaps: [],
          next_questions: [],
        },
        risk_assessment: {
          case_grade: "MEDIUM",
          risk_signals: ["签收事实存在冲突"],
        },
        intake_quality: {
          score: 88,
          threshold: 80,
          ready_for_next_step: true,
          improvement_reason: "",
        },
        admission: {
          recommendation: "ACCEPTED",
          reasoning: "现有信息足以进入证据阶段。",
          confidence: 0.88,
        },
      },
    },
  };
}

// 业务位置：【前端浏览器回归测试】fulfillJson：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

// 业务位置：【前端浏览器回归测试】installIntakeRoomFixture：围绕 案件受理信息和接待结论 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
export async function installIntakeRoomFixture(page, options = {}) {
  const messages = buildMessages(options.messages);
  const turnMemory = buildTurnMemory(options.dossier);
  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, actor);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const headers = request.headers();
    expect(headers).toMatchObject({
      "x-user-id": "user-local",
      "x-role": "USER",
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
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}`
    ) {
      return fulfillJson(route, {
        id: CASE_ID,
        order_id: "ORDER-1001",
        after_sale_id: "AFTER-1001",
        logistics_id: "SF1234567890",
        initiator_role: "USER",
        title: "物流显示签收但用户未收到商品",
        description: "用户称物流已显示签收，但本人没有收到商品。",
        dispute_type: "SIGNED_NOT_RECEIVED",
        risk_level: "MEDIUM",
        current_room: "INTAKE",
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/rooms/INTAKE/messages`
    ) {
      return fulfillJson(route, messages);
    }
    if (
      request.method() === "GET" &&
      url.pathname ===
        `/api/disputes/${CASE_ID}/rooms/INTAKE/turn-memory/latest`
    ) {
      return fulfillJson(route, turnMemory);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/events`
    ) {
      return route.fulfill({
        status: 200,
        headers: { "content-type": "text/event-stream" },
        body: ": deterministic-playwright-heartbeat\n\n",
      });
    }
    if (
      request.method() === "POST" &&
      url.pathname === `/api/disputes/${CASE_ID}/intake/confirm` &&
      options.confirmErrorDetail
    ) {
      return route.fulfill({
        status: 502,
        contentType: "application/json",
        body: JSON.stringify({
          success: false,
          message: options.confirmErrorDetail,
        }),
      });
    }
    throw new Error(
      `Unhandled browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}
