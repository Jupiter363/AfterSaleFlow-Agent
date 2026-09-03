import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { actor } from "../../state/actor";
import OutcomeView from "./OutcomeView.vue";

const publishedOutcome = {
  case_id: "CASE_OUTCOME_1",
  title: "未发货订单取消",
  case_status: "APPROVED_FOR_EXECUTION",
  closed_at: null,
  review_task_status: "APPROVED",
  adjudication_draft: {
    draft_text: "这段内部裁决草案不应出现在最终事件页。",
  },
  final_decision: {
    conclusion: "这段内部审核结论不应出现在最终事件页。",
    explanation: "这段内部审核说明不应出现在最终事件页。",
    review_reason: "这段审核员意见不应出现在最终事件页。",
    source: "HUMAN_REVIEW",
    human_confirmed: true,
    approval_record_id: "APPROVAL_1",
    decision_type: "APPROVE",
    ai_decision_action: "CANCEL_ORDER",
    reviewer_decision_action: "CANCEL_ORDER",
    decided_at: "2026-08-21T22:00:00+08:00",
    approved_plan: {
      id: "PLAN_1",
      version: 1,
      decision_action: "CANCEL_ORDER",
      actions: [],
    },
  },
  actions: [],
};

function apiResponse(data) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ success: true, data }),
  };
}

function apiFailure(code, message) {
  return {
    ok: false,
    status: 400,
    json: async () => ({ success: false, code, message }),
  };
}

async function mountOutcome(initialOutcome = publishedOutcome) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/disputes/:caseId/outcome",
        name: "dispute-outcome",
        component: { template: "<div />" },
      },
    ],
  });
  await router.push("/disputes/CASE_OUTCOME_1/outcome");
  await router.isReady();

  return mount(OutcomeView, {
    props: { initialOutcome },
    global: { plugins: [router] },
  });
}

describe("OutcomeView", () => {
  beforeEach(() => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders one published final execution event and removes the old result chain", async () => {
    const wrapper = await mountOutcome();

    expect(wrapper.get(".room-shell__header h1").text()).toBe("执行结果");
    expect(wrapper.findAll("[data-final-execution-event]")).toHaveLength(1);
    expect(wrapper.get("[data-final-execution-event]").attributes("data-state")).toBe("published");
    expect(wrapper.text()).toContain("取消订单执行事件已发布");
    expect(wrapper.text()).toContain("订单取消决定已经发布");
    expect(wrapper.find("[data-outcome-hearing]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-review]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-plan]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-closure]").exists()).toBe(false);
    expect(wrapper.find("[data-mock-execution]").exists()).toBe(false);
  });

  it("uses the reviewer decision action as the authoritative execution action", async () => {
    const wrapper = await mountOutcome({
      ...publishedOutcome,
      final_decision: {
        ...publishedOutcome.final_decision,
        reviewer_decision_action: "REFUND_ONLY",
        approved_plan: { decision_action: "CANCEL_ORDER" },
      },
    });

    expect(wrapper.text()).toContain("仅退款执行事件已发布");
    expect(wrapper.text()).not.toContain("取消订单执行事件已发布");
  });

  it("does not expose the adjudication draft or review reasoning", async () => {
    const wrapper = await mountOutcome();

    expect(wrapper.text()).not.toContain("内部裁决草案");
    expect(wrapper.text()).not.toContain("内部审核结论");
    expect(wrapper.text()).not.toContain("内部审核说明");
    expect(wrapper.text()).not.toContain("审核员意见");
  });

  it("shows the real completed action reference and execution time", async () => {
    const wrapper = await mountOutcome({
      ...publishedOutcome,
      case_status: "CLOSED",
      final_decision: {
        ...publishedOutcome.final_decision,
        reviewer_decision_action: "REFUND_ONLY",
      },
      actions: [
        {
          action_record_id: "ACTION_REFUND_1",
          action_type: "REFUND_ONLY",
          execution_status: "SUCCEEDED",
          external_result_ref: "REFUND-20260821-1",
          execution_time: "2026-08-21T22:18:00+08:00",
        },
      ],
    });

    const event = wrapper.get("[data-final-execution-event]");
    expect(event.attributes("data-state")).toBe("complete");
    expect(event.text()).toContain("仅退款已完成");
    expect(event.text()).toContain("REFUND-20260821-1");
    expect(event.text()).toContain("2026");
  });

  it("maps an escalated review to the manual handoff event", async () => {
    const wrapper = await mountOutcome({
      ...publishedOutcome,
      case_status: "MANUAL_HANDOFF",
      review_task_status: "ESCALATED",
      final_decision: {
        ...publishedOutcome.final_decision,
        reviewer_decision_action: "ESCALATE_MANUAL",
      },
    });

    const event = wrapper.get("[data-final-execution-event]");
    expect(event.attributes("data-state")).toBe("manual");
    expect(event.text()).toContain("案件已升级人工接管");
  });

  it("keeps unapproved internal content hidden while waiting for final review", async () => {
    const wrapper = await mountOutcome({
      ...publishedOutcome,
      case_status: "WAITING_HUMAN_REVIEW",
      review_task_status: "IN_REVIEW",
      final_decision: {
        ...publishedOutcome.final_decision,
        human_confirmed: false,
        reviewer_decision_action: null,
        conclusion: "未审批敏感结论",
        approved_plan: { decision_action: "CANCEL_ORDER" },
      },
      actions: [],
    });

    const event = wrapper.get("[data-final-execution-event]");
    expect(event.attributes("data-state")).toBe("waiting");
    expect(event.text()).toContain("终审决定尚未发布");
    expect(wrapper.text()).not.toContain("未审批敏感结论");
  });

  it("loads only the case outcome endpoint when opened after the redirect", async () => {
    const fetchMock = vi.fn().mockResolvedValue(apiResponse(publishedOutcome));
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountOutcome(null);
    await flushPromises();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/disputes/CASE_OUTCOME_1/outcome");
    expect(wrapper.text()).toContain("取消订单执行事件已发布");
  });

  it("shows a retryable event notification when the outcome request fails", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      apiFailure("OUTCOME_UNAVAILABLE", "执行事件暂不可用"),
    );
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountOutcome(null);
    await flushPromises();

    const event = wrapper.get("[data-final-execution-event]");
    expect(event.attributes("data-state")).toBe("error");
    expect(event.text()).toContain("执行事件暂不可用");
    expect(wrapper.get(".execution-event__retry").text()).toBe("重新读取");
  });
});
