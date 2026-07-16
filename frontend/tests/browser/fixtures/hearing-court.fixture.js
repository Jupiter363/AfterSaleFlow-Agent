// 文件作用：自动化测试文件，验证 hearing-court.fixture 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect } from "@playwright/test";

export const CASE_ID = "CASE_HEARING_LAYOUT";
export const INTERNAL_A2A_TEXT = "INTERNAL_A2A_ONLY_DO_NOT_RENDER";

const actors = {
  USER: { id: "user-local", role: "USER", label: "User" },
  MERCHANT: { id: "merchant-local", role: "MERCHANT", label: "Merchant" },
  PLATFORM_REVIEWER: {
    id: "reviewer-local",
    role: "PLATFORM_REVIEWER",
    label: "Reviewer",
  },
};

// 业务位置：【前端浏览器回归测试】repeatToLength：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

// 业务位置：【前端浏览器回归测试】buildHearing：把 页面夹具和拦截 API 响应 组装为本块需要的 庭审轮次和法官发言，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildHearing() {
  return {
    flow_schema_version: "hearing_flow.v2",
    question_set: {
      schema_version: "hearing_question_set.v1",
      question_set_id: "HEARING_QUESTION_SET_LAYOUT",
      questions: [
        {
          question_id: "HEARING_QUESTION_LAYOUT_1",
          fact_ids: ["FACT_DELIVERY_LAYOUT"],
          target_roles: ["USER", "MERCHANT"],
          question_text: "请说明签收记录与实际交付情况之间的对应关系。",
        },
      ],
    },
    evidence_request_set: null,
    settlements: [],
    status: {
      flow_schema_version: "hearing_flow.v2",
      flow_stage: "PARTY_ANSWERS_OPEN",
      stage_sequence: 5,
      stage_deadline_at: new Date(Date.now() + 20 * 60 * 1000).toISOString(),
      party_statuses: {
        USER: { submission_status: "PENDING" },
        MERCHANT: { submission_status: "PENDING" },
      },
      can_complete_hearing: false,
      review_gate_ready: false,
    },
  };
}

// 业务位置：【前端浏览器回归测试】buildMessages：把 页面夹具和拦截 API 响应 组装为本块需要的 房间消息和对话记录，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildMessages({ count = 4, longMessageLength = 0 } = {}) {
  const roles = ["INTAKE_OFFICER", "USER", "MERCHANT", "EVIDENCE_CLERK"];
  const messages = Array.from({ length: count }, (_, index) => {
    const sequence = index + 1;
    const senderRole =
      longMessageLength && sequence === count
        ? "USER"
        : roles[index % roles.length];
    const longText =
      longMessageLength && sequence === count
        ? "L".repeat(longMessageLength)
        : repeatToLength(`Hearing statement ${sequence}. `, 128);
    const isParty = ["USER", "MERCHANT"].includes(senderRole);
    return {
      id: `MESSAGE_${sequence}`,
      sequence_no: sequence,
      sender_role: senderRole,
      message_type: isParty ? "PARTY_TEXT" : "AGENT_MESSAGE",
      message_source: isParty ? "PARTY_ACTION" : "ROLE_TEMPLATE",
      stage_code: "PARTY_ANSWERS_OPEN",
      message_text: longText,
      created_at: `2026-07-11T10:${String(sequence % 60).padStart(2, "0")}:00+08:00`,
    };
  });
  messages.push({
    id: "MESSAGE_A2A_INTERNAL",
    sequence_no: count + 1,
    sender_role: "SYSTEM",
    message_type: "A2A_AGENT_MESSAGE",
    visibility: "SYSTEM_AUDIT_ONLY",
    message_source: "SYSTEM_STAGE_EVENT",
    stage_code: "PARTY_ANSWERS_OPEN",
    message_text: INTERNAL_A2A_TEXT,
    created_at: "2026-07-11T11:00:00+08:00",
  });
  return messages;
}

// 业务位置：【前端浏览器回归测试】buildEvidenceCatalog：把 页面夹具和拦截 API 响应 组装为本块需要的 当前可见证据和附件，供 房间、审核和结果页面的交互断言 使用。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function buildEvidenceCatalog({ count = 4 } = {}) {
  return {
    case_id: CASE_ID,
    items: Array.from({ length: count }, (_, index) => {
      const submittedByRole = index % 2 === 0 ? "USER" : "MERCHANT";
      const sequence = index + 1;
      return {
        evidence_id: `EVIDENCE_${sequence}`,
        evidence_type: index % 3 === 0 ? "IMAGE" : "DOCUMENT",
        submitted_by_role: submittedByRole,
        visibility: "PARTIES",
        content_url: `/api/disputes/${CASE_ID}/evidence/EVIDENCE_${sequence}/content`,
        redacted: false,
        verification_status: index % 2 === 0 ? "VERIFIED" : "PLAUSIBLE",
        confidence_score: 0.8,
        confidence_level: "HIGH",
        verification_feedback: "Deterministic browser-layout evidence.",
        source_type: `${submittedByRole}_UPLOAD`,
        original_filename: `${submittedByRole.toLowerCase()}-evidence-${sequence}-${"long-name-".repeat(10)}.pdf`,
        parsed_text: `Evidence summary ${sequence}`,
        submission_status: "SUBMITTED",
      };
    }),
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

// 业务位置：【前端浏览器回归测试】installHearingCourtFixture：围绕 庭审轮次和法官发言 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
export async function installHearingCourtFixture(page, options = {}) {
  const actor = actors[options.role || "USER"];
  if (!actor) throw new Error(`Unsupported hearing fixture role: ${options.role}`);
  const hearing = buildHearing();
  const messages = buildMessages(options.messages);
  const evidenceCatalog = buildEvidenceCatalog(options.evidence);

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
      return fulfillJson(route, { items: [] });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/hearing`
    ) {
      if (options.loadError) {
        return route.fulfill({
          status: 502,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            message: options.loadError,
          }),
        });
      }
      return fulfillJson(route, hearing);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/evidence`
    ) {
      return fulfillJson(route, evidenceCatalog);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/rooms/HEARING/messages`
    ) {
      return fulfillJson(route, messages);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/rooms/HEARING/agent-runs/active`
    ) {
      return fulfillJson(route, []);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/events/replay`
    ) {
      return fulfillJson(route, []);
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
