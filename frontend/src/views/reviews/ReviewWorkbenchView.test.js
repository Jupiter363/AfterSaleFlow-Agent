import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import ReviewWorkbenchView from "./ReviewWorkbenchView.vue";

const packet = {
  id: "PACKET_1",
  case_id: "CASE_REVIEW_1",
  packet_version: 3,
  dossier_version: 2,
  ruleset_version: "rules-2026.07",
  frozen_at: "2026-07-03T12:00:00+08:00",
  expires_at: "2026-07-03T14:00:00+08:00",
  case_summary: { title: "签收未收到争议", risk_level: "HIGH" },
  claims: { user: "未收到商品", merchant: "物流显示签收" },
  issues: ["签收人身份是否可信", "是否满足退款条件"],
  evidence_matrix: [{ issue: "签收人", supporting: ["EVIDENCE_1"] }],
  draft: { conclusion: "建议退款", reviewer_attention: ["核实代签关系"] },
  remedy: { actions: [{ type: "REFUND", amount: 299 }] },
  risk_flags: ["HIGH_VALUE", "SIGNATURE_MISMATCH"],
  status: "FROZEN",
};

async function mountView(overrides = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/reviews/:reviewId", component: { template: "<div />" } }],
  });
  await router.push("/reviews/REVIEW_1");
  await router.isReady();
  const decideAction = overrides.decideAction || vi.fn();
  const wrapper = mount(ReviewWorkbenchView, {
    props: {
      initialPacket: packet,
      viewerRole: "PLATFORM_REVIEWER",
      decideAction,
      serverNow: "2026-07-03T12:00:00+08:00",
      ...overrides,
    },
    global: { plugins: [router] },
  });
  return { wrapper, decideAction };
}

describe("ReviewWorkbenchView", () => {
  it("shows the frozen packet, review copilot and human-only controls", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("审核解释官");
    expect(wrapper.text()).toContain("ReviewPacket v3");
    expect(wrapper.get("[data-packet-status]").text()).toBe("已冻结");
    expect(wrapper.get("[data-frozen-time]").text()).toBe("7月3日 12:00");
    expect(wrapper.text()).toContain("核实代签关系");
    expect(wrapper.text()).toContain("AI 裁决草案（非最终）");
    expect(wrapper.get("[data-claims-card]").text()).toContain("用户");
    expect(wrapper.get("[data-claims-card]").text()).toContain("未收到商品");
    expect(wrapper.get("[data-issues-card]").text()).toContain("签收人身份是否可信");
    expect(wrapper.get("[data-evidence-matrix]").text()).toContain("EVIDENCE_1");
    expect(wrapper.get("[data-remedy-card]").text()).toContain("REFUND");
    expect(wrapper.get("[data-claims-card]").text()).not.toContain("{");
    expect(wrapper.find("[data-review-decisions]").exists()).toBe(true);
  });

  it("renders backend snake_case remedy actions as concrete execution work", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        remedy: {
          actions: [
            {
              action_type: "RESHIP",
              preconditions: [
                "CASE_NOT_CLOSED",
                "INVENTORY_AVAILABLE",
              ],
              parameters: {
                source_recommendation: "RESHIP_BY_CONFIRMED_SETTLEMENT",
              },
            },
          ],
        },
      },
    });

    const remedyCard = wrapper.get("[data-remedy-card]");
    expect(remedyCard.text()).toContain("RESHIP");
    expect(remedyCard.text()).toContain("INVENTORY_AVAILABLE");
    expect(remedyCard.text()).toContain("RESHIP_BY_CONFIRMED_SETTLEMENT");
    expect(remedyCard.text()).not.toContain("执行动作 1");
    expect(remedyCard.text()).not.toContain("未提供");
  });

  it("requires a reason and explicit second confirmation before a final decision", async () => {
    const decideAction = vi.fn().mockResolvedValue({
      decision: "APPROVE",
      status: "APPROVED",
    });
    const { wrapper } = await mountView({ decideAction });

    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    expect(wrapper.text()).toContain("请先填写审核理由");
    await wrapper.get("[data-review-reason]").setValue("证据链与规则适用均已核验");
    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(true);
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "APPROVE",
      reason: "证据链与规则适用均已核验",
      approved_plan: null,
    });
    expect(wrapper.text()).toContain("终审决定已提交");
  });

  it("never exposes final decision controls before the packet is frozen", async () => {
    const { wrapper } = await mountView({
      initialPacket: { ...packet, status: "PREPARING" },
    });

    expect(wrapper.find("[data-review-decisions]").exists()).toBe(false);
    expect(wrapper.text()).toContain("ReviewPacket 冻结前仅可只读旁观");
  });
});
