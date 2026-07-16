import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { actor } from "../../state/actor";
import OutcomeView from "./OutcomeView.vue";

const actionOnlyPlan = {
  actions: [
    {
      action_type: "REFUND",
      description: "向用户原支付渠道退还订单实付金额 299 元，并关闭本次争议。",
      amount: 299,
      currency: "CNY",
    },
  ],
};

const approvedOutcome = {
  case_id: "CASE_OUTCOME_1",
  title: "签收未收到争议",
  case_status: "CLOSED",
  closed_at: "2026-07-03T13:20:00+08:00",
  adjudication_draft: {
    id: "DRAFT_V2_7",
    draft_version: 7,
    recommended_decision: "建议支持用户退款请求",
    draft_text: "庭审认为现有证据不足以证明包裹由用户本人或授权人签收。",
  },
  final_decision: {
    conclusion: "支持用户退款请求",
    explanation: "最终处理方案已经审核生效。",
    review_reason: "管理员确认现有证据链完整，裁决适用规则准确。",
    approved_plan: actionOnlyPlan,
    source: "HUMAN_REVIEW",
    human_confirmed: true,
  },
  actions: [
    {
      action_record_id: "ACTION_1",
      action_type: "REFUND",
      execution_status: "SUCCEEDED",
      result: { amount: 299, currency: "CNY" },
      external_result_ref: "REFUND-20260703-1",
    },
    {
      action_record_id: "ACTION_2",
      action_type: "NOTIFY_MERCHANT",
      execution_status: "SUCCEEDED",
      result: { delivered: true },
    },
  ],
};

const unapprovedOutcome = {
  ...approvedOutcome,
  case_status: "WAITING_HUMAN_REVIEW",
  closed_at: null,
  final_decision: {
    conclusion: "这是一条尚未审批的内部草案结论",
    explanation: "这是一条尚未审批的内部草案说明",
    review_reason: "这是一条尚未审批的内部审核意见",
    approved_plan: {
      handling_direction: "REFUND",
      execution_plan: "这是一条尚未审批的内部执行方案",
    },
    human_confirmed: false,
  },
  actions: [],
};

async function mountOutcome(initialOutcome) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/disputes/:caseId/outcome", component: { template: "<div />" } }],
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
    actor.id = "user-local";
    actor.role = "USER";
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("uses the shared room header for the read-only execution result page", async () => {
    const wrapper = await mountOutcome(approvedOutcome);

    expect(wrapper.get(".room-shell__header h1").text()).toBe("执行结果");
  });

  it("shows the V2 hearing adjudication and its actual draft version", async () => {
    const wrapper = await mountOutcome(approvedOutcome);
    const hearing = wrapper.get("[data-outcome-hearing]");

    expect(hearing.text()).toContain("庭审法官 V2");
    expect(hearing.text()).toMatch(/v7|V7|版本\s*7/);
    expect(hearing.text()).toContain("建议支持用户退款请求");
    expect(hearing.text()).toContain(
      "庭审认为现有证据不足以证明包裹由用户本人或授权人签收。",
    );
  });

  it("shows the administrator review opinion without exposing review operations", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const wrapper = await mountOutcome(approvedOutcome);
    const review = wrapper.get("[data-outcome-review]");

    expect(review.text()).toContain("管理员审核意见");
    expect(review.text()).toContain(
      "管理员确认现有证据链完整，裁决适用规则准确。",
    );
    expect(wrapper.find("[data-outcome-review-panel]").exists()).toBe(false);
    expect(wrapper.find("[data-review-confirm]").exists()).toBe(false);
    expect(wrapper.find("[data-review-modify]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("审核员操作");
  });

  it("derives the approved plan type and description from an action-only plan", async () => {
    const wrapper = await mountOutcome(approvedOutcome);
    const plan = wrapper.get("[data-outcome-plan]");

    expect(plan.text()).toContain("最终执行方案");
    expect(plan.text()).toMatch(/退款|REFUND/);
    expect(plan.text()).toContain(
      "向用户原支付渠道退还订单实付金额 299 元，并关闭本次争议。",
    );
  });

  it("supports the handling direction and natural-language execution plan contract", async () => {
    const wrapper = await mountOutcome({
      ...approvedOutcome,
      final_decision: {
        ...approvedOutcome.final_decision,
        approved_plan: {
          handling_direction: "RETURN_AND_REFUND",
          execution_plan: "用户寄回商品后，原支付渠道退还 299 元，并向双方发送结案通知。",
        },
      },
    });
    const plan = wrapper.get("[data-outcome-plan]");

    expect(plan.text()).toMatch(/退货退款|RETURN_AND_REFUND/);
    expect(plan.text()).toContain(
      "用户寄回商品后，原支付渠道退还 299 元，并向双方发送结案通知。",
    );
  });

  it("animates a frontend mock execution when no real action receipt exists", async () => {
    vi.useFakeTimers();
    const wrapper = await mountOutcome({
      ...approvedOutcome,
      case_status: "APPROVED_FOR_EXECUTION",
      closed_at: null,
      actions: [],
    });

    const execution = wrapper.get("[data-outcome-execution]");
    expect(execution.find("[data-mock-execution]").exists()).toBe(true);
    expect(execution.text()).toContain("方案下发");
    expect(execution.text()).not.toContain("模拟执行完成");

    await vi.advanceTimersByTimeAsync(4_999);
    await flushPromises();
    expect(execution.get(".mock-execution__summary strong").text()).toBe("方案下发");

    await vi.advanceTimersByTimeAsync(1);
    await flushPromises();
    expect(execution.get(".mock-execution__summary strong").text()).toBe("执行准备");

    await vi.advanceTimersByTimeAsync(15_000);
    await flushPromises();

    expect(execution.text()).toContain("模拟执行完成");
    expect(wrapper.find("[data-execution-receipt]").exists()).toBe(false);
  });

  it("keeps structured real execution receipts when actions are available", async () => {
    const wrapper = await mountOutcome({
      ...approvedOutcome,
      actions: [
        {
          action_record_id: "ACTION_RESHIP",
          action_type: "RESHIP",
          execution_status: "SUCCEEDED",
          external_result_ref: "RESHIP-20260703-1",
          result: {
            operation: "reship",
            simulated: false,
            tool_name: "warehouse_tool",
            response: {
              status: "SUCCEEDED",
              action_type: "RESHIP",
              idempotency_key: "REMEDY:CASE:1:0:RESHIP",
            },
          },
        },
      ],
    });

    const execution = wrapper.get("[data-outcome-execution]");
    const receipt = execution.get("[data-execution-receipt]");
    expect(execution.find("[data-mock-execution]").exists()).toBe(false);
    expect(receipt.text()).toContain("RESHIP-20260703-1");
    expect(receipt.text()).toContain("warehouse_tool");
    expect(receipt.text()).toContain("REMEDY:CASE:1:0:RESHIP");
    expect(receipt.find("pre").exists()).toBe(false);
    expect(receipt.text()).not.toContain('"response"');
    expect(receipt.text()).not.toContain("{");
  });

  it("does not treat a rejected human review as an approved final result", async () => {
    const wrapper = await mountOutcome({
      ...approvedOutcome,
      review_task_status: "REJECTED",
      actions: [],
    });

    expect(wrapper.get("[data-outcome-waiting]").text()).toContain("等待最终结果");
    expect(wrapper.find("[data-outcome-hearing]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-plan]").exists()).toBe(false);
    expect(wrapper.find("[data-mock-execution]").exists()).toBe(false);
  });

  it("keeps every result section behind the approved-result boundary", async () => {
    const wrapper = await mountOutcome(unapprovedOutcome);

    expect(wrapper.get("[data-outcome-waiting]").text()).toContain("等待最终结果");
    expect(wrapper.find("[data-outcome-hearing]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-review]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-plan]").exists()).toBe(false);
    expect(wrapper.find("[data-outcome-execution]").exists()).toBe(false);
    expect(wrapper.find("[data-mock-execution]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("这是一条尚未审批的内部草案结论");
    expect(wrapper.text()).not.toContain("这是一条尚未审批的内部审核意见");
    expect(wrapper.text()).not.toContain("这是一条尚未审批的内部执行方案");
  });
});
