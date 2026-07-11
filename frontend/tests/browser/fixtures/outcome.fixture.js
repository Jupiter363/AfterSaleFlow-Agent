import { expect } from "@playwright/test";

export const OUTCOME_CASE_ID = "CASE_OUTCOME_LAYOUT";

const actors = {
  USER: { id: "user-local", role: "USER", label: "鐢ㄦ埛" },
  PLATFORM_REVIEWER: {
    id: "reviewer-local",
    role: "PLATFORM_REVIEWER",
    label: "骞冲彴瀹℃牳鍛?",
  },
};

const longUnbroken =
  "LONGTOKEN_".repeat(36) +
  "https://example.invalid/" +
  "receipt/".repeat(26);

const longParagraph =
  "鐢ㄦ埛绉扮墿娴佺姸鎬佹樉绀虹鏀讹紝浣嗘湰浜哄強瀹朵汉鍧囪〃绀烘湭鏀跺埌鍟嗗搧銆傚晢瀹朵富寮犵墿娴佸凡瀹屾垚灞ョ害锛岀洰鍓嶉渶瑕佸洿缁曠鏀朵汉韬唤銆佹姇閫掍綅缃€佺墿娴佺収鐗囦笌鍙屾柟闄堣堪杩涜澶嶆牳銆?".repeat(
    10,
  );

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

function draftBlock(scenario) {
  const pressure = scenario === "long";
  return {
    id: "DRAFT_OUTCOME_LAYOUT",
    draft_version: 3,
    recommended_decision: pressure
      ? `${longParagraph}${longUnbroken}`
      : "寤鸿鍚庣画纭绛炬敹鍑瘉鍚庡啀瀹氭柟鍚?",
    confidence: 0.78,
    draft_text: pressure
      ? `${longParagraph}${longParagraph}${longUnbroken}`
      : "AI 娉曞畼宸插熀浜庡涵瀹℃潗鏂欑敓鎴愰潪鏈€缁堣鍐宠崏妗堛€?",
    fact_findings: [
      {
        fact: pressure ? `${longParagraph}${longUnbroken}` : "鐗╂祦鏄剧ず宸茬鏀?",
        conclusion: "闇€缁撳悎绛炬敹浜鸿韩浠藉啀鍒ゆ柇",
      },
    ],
    evidence_assessment: [
      pressure
        ? `${longParagraph}${longUnbroken}`
        : "鍟嗗璇佹嵁鑳借瘉鏄庣墿娴佽褰曞瓨鍦紝浣嗕笉瓒充互鐩存帴璇佹槑鐢ㄦ埛鏈汉鏀惰揣銆?",
    ],
    policy_application: [
      "绛炬敹浜夎搴旂粨鍚堣瘉鎹湡瀹炴€с€佸叧鑱旀€у拰瀹屾暣鎬ц繘琛屽垽鏂€?",
    ],
    reviewer_attention: [
      pressure ? `${longParagraph}${longUnbroken}` : "鏍搁獙绛炬敹浜鸿韩浠藉拰绛炬敹鍦扮偣銆?",
    ],
    explanation_officer_notes: {
      replay_summary: pressure
        ? `${longParagraph}${longParagraph}${longUnbroken}`
        : "瑙ｉ噴鍛樺鐩樹笁杞涵瀹★紝纭浜夎涓昏鍥寸粫绛炬敹浜嬪疄銆?",
      final_plan_explanation: pressure
        ? `${longParagraph}${longUnbroken}`
        : "鑽夋闇€鍦ㄧ‘璁ゅ墠閲嶇偣澶嶆牳绛炬敹閾捐矾銆?",
      reviewer_focus: [
        pressure ? `${longParagraph}${longUnbroken}` : "鏍搁獙绛炬敹搴曞崟",
      ],
    },
    approved_plan: {
      id: pressure ? `PLAN_${longUnbroken}` : "PLAN_LAYOUT",
      handling_direction: "REFUND",
      execution_plan: pressure ? `${longParagraph}${longUnbroken}` : "寰呭鏍稿憳纭",
      actions: [],
    },
  };
}

function outcomeSnapshot({ scenario = "final" } = {}) {
  const pressure = scenario === "long";
  const isDraft = scenario === "draft" || scenario === "reviewer" || pressure;
  return {
    case_id: OUTCOME_CASE_ID,
    title: pressure
      ? `${longParagraph}${longUnbroken}`
      : "绛炬敹鏈敹鍒颁簤璁?",
    case_status: isDraft ? "WAITING_HUMAN_REVIEW" : "CLOSED",
    closed_at: isDraft ? null : "2026-07-11T11:30:00+08:00",
    final_decision: {
      conclusion: pressure
        ? `${longParagraph}${longUnbroken}`
        : isDraft
          ? "AI 瑁佸喅鑽夋宸茬敓鎴?"
          : "鏀寔鐢ㄦ埛閫€娆捐姹?",
      explanation: pressure
        ? `${longParagraph}${longParagraph}${longUnbroken}`
        : "鐜版湁鏉愭枡闇€缁х画鏍搁獙绛炬敹浜鸿韩浠戒笌绛炬敹鍦扮偣銆?",
      review_reason: pressure ? `${longParagraph}${longUnbroken}` : "瀹℃牳鍛樼‘璁ゆ潗鏂欏凡鍏ュ嵎銆?",
      human_confirmed: !isDraft,
      source: isDraft ? "AI_JUDGE" : "HUMAN_REVIEW",
    },
    actions: isDraft
      ? []
      : [
          {
            action_record_id: "ACTION_OUTCOME_LAYOUT",
            action_type: "REFUND",
            execution_status: "SUCCEEDED",
            result: pressure
              ? {
                  operation: longUnbroken,
                  response: {
                    idempotency_key: longUnbroken,
                    status: "SUCCEEDED",
                  },
                }
              : { amount: 299, currency: "CNY" },
            external_result_ref: pressure ? longUnbroken : "REFUND-LAYOUT-1",
          },
        ],
    adjudication_draft: isDraft ? draftBlock(pressure ? "long" : "draft") : null,
  };
}

export async function installOutcomeFixture(page, options = {}) {
  const actor = actors[options.role || "USER"];
  if (!actor) throw new Error(`Unsupported outcome role: ${options.role}`);

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
      url.pathname === `/api/disputes/${OUTCOME_CASE_ID}/outcome`
    ) {
      return fulfillJson(route, outcomeSnapshot({ scenario: options.scenario }));
    }

    throw new Error(
      `Unhandled outcome browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}
