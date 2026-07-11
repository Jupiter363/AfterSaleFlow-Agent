import { expect } from "@playwright/test";

export const CASE_ID = "CASE_EVIDENCE_LAYOUT";
export const LONG_FILENAME = `${"F".repeat(196)}.pdf`;
export const LONG_UNBROKEN_TEXT = "OCRTOKEN".repeat(30);

const actors = {
  USER: { id: "user-local", role: "USER", label: "用户" },
  MERCHANT: { id: "merchant-local", role: "MERCHANT", label: "商家" },
};

function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

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

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

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
