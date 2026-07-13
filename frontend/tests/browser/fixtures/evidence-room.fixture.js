// 文件作用：自动化测试文件，验证 evidence-room.fixture 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect } from "@playwright/test";

export const CASE_ID = "CASE_EVIDENCE_LAYOUT";
export const LONG_FILENAME = `${"F".repeat(196)}.pdf`;
export const LONG_UNBROKEN_TEXT = "OCRTOKEN".repeat(30);

const actors = {
  USER: { id: "user-local", role: "USER", label: "用户" },
  MERCHANT: { id: "merchant-local", role: "MERCHANT", label: "商家" },
};

// 业务位置：【前端浏览器回归测试】repeatToLength：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

// 业务位置：【前端浏览器回归测试】buildEvidenceCatalog：把 页面夹具和拦截 API 响应 组装为本块需要的 当前可见证据和附件，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildEvidenceCatalog({
  role,
  count = 100,
  initiatorRole = "USER",
} = {}) {
  return {
    case_id: CASE_ID,
    initiator_role: initiatorRole,
    items: Array.from({ length: count }, (_, index) => {
      const sequence = index + 1;
      return {
        evidence_id: `EVIDENCE_${role}_${String(sequence).padStart(3, "0")}`,
        evidence_type:
          index % 2 === 0 ? "DELIVERY_RECORD" : "CHAT_SCREENSHOT",
        submitted_by_role: role,
        visibility: "PRIVATE",
        content_url: null,
        redacted: false,
        verification_status:
          index % 3 === 0 ? "NEEDS_HUMAN_REVIEW" : "VERIFIED",
        confidence_score: index % 4 === 0 ? 0.62 : 0.91,
        confidence_level: index % 4 === 0 ? "MEDIUM" : "HIGH",
        authenticity_score: 0.78,
        relevance_score: index % 3 === 0 ? 0.86 : 0.72,
        completeness_score: index % 3 === 0 ? 0.54 : 0.81,
        assessment_confidence: index % 3 === 0 ? 0.67 : 0.88,
        requires_human_review: index % 3 === 0,
        inspected_modalities: ["IMAGE_PIXELS", "OCR_TEXT", "FILE_METADATA"],
        limitations:
          index % 3 === 0
            ? ["单张图片不能单独证明外观痕迹形成时间、原因或责任归属。"]
            : [],
        human_review_reason_codes:
          index % 3 === 0 ? ["FINE_VISUAL_DAMAGE_REQUIRES_HUMAN"] : [],
        human_review_instructions:
          index % 3 === 0
            ? ["请审核员打开原图，核对疑似划痕区域、拍摄时间和来源链。"]
            : [],
        verification_feedback:
          index === 0
            ? LONG_UNBROKEN_TEXT
            : repeatToLength(
                `第${sequence}份证据的来源完整性、形成时间、提交主体与争议事实关联性仍需逐项核验。`,
                220,
              ),
        original_filename:
          index === 0
            ? LONG_FILENAME
            : `${role.toLowerCase()}-evidence-${sequence}.pdf`,
        submission_status: "SUBMITTED",
        submitted_at: `2026-07-11T10:${String(sequence % 60).padStart(2, "0")}:00+08:00`,
        submission_batch_id: `BATCH_${role}_${sequence}`,
        parsed_text:
          index === 0
            ? LONG_UNBROKEN_TEXT
            : repeatToLength(
                `证据${sequence}的解析文本用于确定性浏览器布局测试。`,
                120,
              ),
      };
    }),
  };
}

// 业务位置：【前端浏览器回归测试】buildCompletion：把 页面夹具和拦截 API 响应 组装为本块需要的 当前阶段业务数据，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildCompletion(options = {}) {
  const sealed = Boolean(options.sealed);
  return {
    case_id: CASE_ID,
    user_completed: sealed,
    merchant_completed: sealed,
    sealed,
    next_room: sealed ? "HEARING" : "EVIDENCE",
    deadline_at: "2099-01-01T00:00:00Z",
  };
}

// 业务位置：【前端浏览器回归测试】buildMessages：把 页面夹具和拦截 API 响应 组装为本块需要的 房间消息和对话记录，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildMessages(role) {
  return [
    {
      id: "MESSAGE_EVIDENCE_AGENT",
      sequence_no: 1,
      sender_role: "CUSTOMER_SERVICE",
      message_type: "AGENT_MESSAGE",
      message_text:
        "我会围绕接待室收敛的案情，核对证据来源、形成时间、完整性与争议事实关联性。",
    },
    {
      id: `MESSAGE_${role}`,
      sequence_no: 2,
      sender_role: role,
      message_type: "PARTY_TEXT",
      message_text: "这些材料均来自原始履约记录，请书记官逐项核验。",
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

// 业务位置：【前端浏览器回归测试】installEvidenceRoomFixture：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
export async function installEvidenceRoomFixture(page, options = {}) {
  const role = options.role || "USER";
  const actor = actors[role];
  if (!actor) {
    throw new Error(`Unsupported evidence fixture role: ${role}`);
  }

  const catalog = buildEvidenceCatalog({
    role,
    count: options.count,
    initiatorRole: options.initiatorRole,
  });
  const completion = buildCompletion(options);
  const messages = buildMessages(role);

  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, actor);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(request.headers()).toMatchObject({
      "x-user-id": actor.id,
      "x-role": actor.role,
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
      return fulfillJson(route, {
        items: [],
        page: 0,
        size: 20,
        total_elements: 0,
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}`
    ) {
      return fulfillJson(route, {
        id: CASE_ID,
        status: "EVIDENCE_COLLECTION",
        initiator_role: options.initiatorRole || "USER",
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/evidence`
    ) {
      return fulfillJson(route, catalog);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/evidence/completion`
    ) {
      return fulfillJson(route, completion);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/rooms/EVIDENCE/messages`
    ) {
      return fulfillJson(route, messages);
    }
    if (
      request.method() === "POST" &&
      url.pathname ===
        `/api/disputes/${CASE_ID}/rooms/EVIDENCE/messages/opening`
    ) {
      return fulfillJson(route, messages[0]);
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

    throw new Error(
      `Unhandled browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}
