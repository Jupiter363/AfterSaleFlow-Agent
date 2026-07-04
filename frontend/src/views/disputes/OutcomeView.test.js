import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import OutcomeView from "./OutcomeView.vue";

const outcome = {
  case_id: "CASE_OUTCOME_1",
  title: "签收未收到争议",
  case_status: "CLOSED",
  closed_at: "2026-07-03T13:20:00+08:00",
  final_decision: {
    conclusion: "支持用户退款请求",
    explanation: "现有证据不足以证明包裹由用户本人或授权人签收。",
    review_reason: "审核员确认现有证据链完整。",
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

describe("OutcomeView", () => {
  it("explains the final ruling and exposes deterministic execution receipts", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/disputes/:caseId/outcome", component: { template: "<div />" } }],
    });
    await router.push("/disputes/CASE_OUTCOME_1/outcome");
    await router.isReady();
    const wrapper = mount(OutcomeView, {
      props: { initialOutcome: outcome },
      global: { plugins: [router] },
    });

    expect(wrapper.text()).toContain("最终裁决");
    expect(wrapper.text()).toContain("支持用户退款请求");
    expect(wrapper.text()).toContain("REFUND-20260703-1");
    expect(wrapper.findAll("[data-execution-receipt]")).toHaveLength(2);
    expect(wrapper.text()).toContain("裁决已生效");
    expect(wrapper.text()).toContain("审核员确认现有证据链完整");
  });

  it("formats nested execution result payloads instead of dumping raw JSON", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/disputes/:caseId/outcome", component: { template: "<div />" } }],
    });
    await router.push("/disputes/CASE_OUTCOME_1/outcome");
    await router.isReady();
    const wrapper = mount(OutcomeView, {
      props: {
        initialOutcome: {
          ...outcome,
          actions: [
            {
              action_record_id: "ACTION_RESHIP",
              action_type: "RESHIP",
              execution_status: "SUCCEEDED",
              external_result_ref: "SIM_RESHIP_1",
              result: {
                operation: "reship",
                simulated: true,
                tool_name: "warehouse_tool",
                response: {
                  status: "SUCCEEDED",
                  action_type: "RESHIP",
                  idempotency_key: "REMEDY:CASE:1:0:RESHIP",
                },
              },
            },
          ],
        },
      },
      global: { plugins: [router] },
    });

    const receipt = wrapper.get("[data-execution-receipt]");
    expect(receipt.text()).toContain("RESHIP");
    expect(receipt.text()).toContain("reship");
    expect(receipt.text()).toContain("warehouse_tool");
    expect(receipt.text()).toContain("REMEDY:CASE:1:0:RESHIP");
    expect(receipt.find("pre").exists()).toBe(false);
    expect(receipt.text()).not.toContain('"response"');
    expect(receipt.text()).not.toContain("{");
  });
});
