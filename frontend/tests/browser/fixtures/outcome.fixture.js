// 执行结果页浏览器测试夹具：覆盖已审批最终结果、未审批等待态和长文本回执。

import { expect } from "@playwright/test";

export const OUTCOME_CASE_ID = "CASE_OUTCOME_LAYOUT";

const actors = {
  USER: { id: "user-local", role: "USER", label: "消费者" },
  PLATFORM_REVIEWER: {
    id: "reviewer-local",
    role: "PLATFORM_REVIEWER",
    label: "平台审核员",
  },
};

const longUnbroken =
  "LONG_RECEIPT_TOKEN_".repeat(36) +
  "https://example.invalid/" +
  "receipt/".repeat(26);

const longParagraph =
  "审核员已结合订单记录、双方陈述、物流轨迹和售后规则确认最终处理方案。".repeat(
    44,
  );

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

function approvedOutcome({ long = false, mock = false } = {}) {
  const conclusion = long
    ? `${longParagraph}${longUnbroken}`
    : "审核通过：为用户退还订单实付金额，并同步关闭本次争议。";
  const explanation = long
    ? `${longParagraph}${longParagraph}${longUnbroken}`
    : "审核员已核对订单、物流和双方举证，确认本方案为已经生效的最终处理结果。";
  const reviewReason = long
    ? `${longParagraph}${longUnbroken}`
    : "证据链完整，退款范围和执行动作符合当前售后规则。";

  return {
    case_id: OUTCOME_CASE_ID,
    title: "售后争议执行结果",
    case_status: "CLOSED",
    closed_at: "2026-07-11T11:30:00+08:00",
    adjudication_draft: {
      id: "DRAFT_OUTCOME_V2",
      draft_version: 7,
      recommended_decision: long
        ? `${longParagraph}${longUnbroken}`
        : "建议支持用户退款请求",
      draft_text: long
        ? `${longParagraph}${longParagraph}${longUnbroken}`
        : "庭审法官 V2 认为商家现有材料不足以证明订单已经按约履行。",
      confidence: 0.86,
    },
    review_task_status: "APPROVED",
    final_decision: {
      conclusion,
      explanation,
      review_reason: reviewReason,
      approved_plan: long
        ? {
            handling_direction: "RETURN_AND_REFUND",
            execution_plan: `${longParagraph}${longUnbroken}`,
            version: 3,
            actions: [
              {
                action_type: "RETURN_AND_REFUND",
                description: `${longParagraph}${longUnbroken}`,
              },
            ],
          }
        : {
            version: 2,
            actions: [
              {
                action_type: "REFUND",
                description: "向用户原支付渠道退还订单实付金额 299 元，并同步结案。",
              },
            ],
          },
      human_confirmed: true,
      source: "HUMAN_REVIEW",
    },
    actions: mock
      ? []
      : long
      ? [
          {
            action_record_id: "ACTION_OUTCOME_LONG",
            action_type: "REFUND",
            execution_status: "SUCCEEDED",
            result: {
              operation: longUnbroken,
              response: {
                idempotency_key: longUnbroken,
                status: "SUCCEEDED",
              },
            },
            external_result_ref: longUnbroken,
          },
        ]
      : [
          {
            action_record_id: "ACTION_OUTCOME_REFUND",
            action_type: "REFUND",
            execution_status: "SUCCEEDED",
            result: {
              amount: 299,
              currency: "CNY",
              response: {
                idempotency_key: "REFUND-LAYOUT-1",
                status: "SUCCEEDED",
              },
            },
            external_result_ref: "REFUND-LAYOUT-1",
          },
          {
            action_record_id: "ACTION_OUTCOME_NOTIFY",
            action_type: "NOTIFY_USER",
            execution_status: "RUNNING",
            result: {
              delivered: false,
              reference_id: "NOTICE-LAYOUT-1",
            },
            external_result_ref: "NOTICE-LAYOUT-1",
          },
        ],
  };
}

function pendingOutcome() {
  return {
    case_id: OUTCOME_CASE_ID,
    title: "等待审核的售后争议",
    case_status: "WAITING_HUMAN_REVIEW",
    closed_at: null,
    review_task_status: "IN_REVIEW",
    final_decision: {
      conclusion: "INTERNAL_DRAFT_DO_NOT_RENDER",
      explanation: "INTERNAL_EXPLANATION_DO_NOT_RENDER",
      review_reason: "INTERNAL_REVIEW_DO_NOT_RENDER",
      approved_plan: {
        handling_direction: "INTERNAL_PLAN_DO_NOT_RENDER",
        execution_plan: "INTERNAL_PLAN_DO_NOT_RENDER",
      },
      human_confirmed: false,
      source: "AI_JUDGE",
    },
    actions: [],
    adjudication_draft: {
      id: "DRAFT_OUTCOME_LAYOUT",
      draft_version: 3,
      recommended_decision: "INTERNAL_DRAFT_DO_NOT_RENDER",
      draft_text: "INTERNAL_DRAFT_DO_NOT_RENDER",
      explanation_officer_notes: {
        replay_summary: "INTERNAL_EXPLANATION_DO_NOT_RENDER",
      },
    },
  };
}

function outcomeSnapshot({ scenario = "final" } = {}) {
  if (scenario === "pending") return pendingOutcome();
  return approvedOutcome({
    long: scenario === "long",
    mock: scenario === "mock",
  });
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
    if (request.method() === "GET" && url.pathname === "/api/disputes") {
      return fulfillJson(route, { items: [] });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${OUTCOME_CASE_ID}`
    ) {
      return fulfillJson(route, {
        case_id: OUTCOME_CASE_ID,
        title: "售后争议执行结果",
        case_status: "APPROVED_FOR_EXECUTION",
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname ===
        `/api/disputes/${OUTCOME_CASE_ID}/rooms/HEARING/agent-runs/active`
    ) {
      return fulfillJson(route, []);
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
