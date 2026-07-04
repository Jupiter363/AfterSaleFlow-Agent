import { flushPromises, mount } from "@vue/test-utils";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { describe, expect, it, vi } from "vitest";
import IntakeRoomView from "./IntakeRoomView.vue";

const dispute = {
  id: "CASE_INTAKE_1",
  order_id: "ORDER-1",
  after_sale_id: "AFTER-1",
  title: "签收未收到",
  description: "物流显示签收，但用户没有收到。",
  dispute_type: "SIGNED_NOT_RECEIVED",
  risk_level: "HIGH",
  current_room: "INTAKE",
};

const analysis = {
  initiator_role: "USER",
  order_reference: "ORDER-1",
  after_sales_reference: "AFTER-1",
  logistics_reference: "LOG-1",
  party_claims: {
    user: "未收到包裹",
    merchant: "等待商家回应",
  },
  requested_outcome: "核实签收并退款",
  initial_risk_signals: ["签收人与收件人不一致"],
  admission_recommendation: "建议受理",
};

async function mountView(confirmAction = vi.fn(), eventStreamer = null) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes/:caseId/intake", component: { template: "<div />" } },
      { path: "/disputes/:caseId/evidence", component: { template: "<div />" } },
    ],
  });
  await router.push("/disputes/CASE_INTAKE_1/intake");
  await router.isReady();
  const wrapper = mount(IntakeRoomView, {
    props: {
      initialDispute: dispute,
      initialAnalysis: analysis,
      initialMessages: [],
      confirmAction,
      eventStreamer,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router, confirmAction };
}

describe("IntakeRoomView", () => {
  it("turns intake analysis into correctable dossier stickers", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("争议接待官");
    expect(wrapper.text()).toContain("ORDER-1");
    expect(wrapper.text()).toContain("未收到包裹");
    expect(wrapper.text()).toContain("签收人与收件人不一致");
    expect(wrapper.text()).toContain("AI 受理建议非最终");
    expect(wrapper.findAll("[data-intake-sticker]").length).toBeGreaterThan(3);
  });

  it("opens the evidence room only after the server confirms admission", async () => {
    const confirmAction = vi.fn().mockResolvedValue({
      admissible: true,
      current_room: "EVIDENCE",
    });
    const { wrapper, router } = await mountView(confirmAction);

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(confirmAction).toHaveBeenCalledWith(
      expect.objectContaining({
        admissible: true,
        dispute_type: "SIGNED_NOT_RECEIVED",
      }),
    );
    expect(wrapper.text()).toContain("已上报");
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_INTAKE_1/evidence",
    );
  });

  it("subscribes to the resumable case stream and aborts it on room exit", async () => {
    let signal;
    const eventStreamer = vi.fn(async (options) => {
      signal = options.signal;
      options.state.connected = true;
    });
    const { wrapper } = await mountView(vi.fn(), eventStreamer);
    await flushPromises();

    expect(eventStreamer).toHaveBeenCalledWith(
      expect.objectContaining({
        caseId: "CASE_INTAKE_1",
        roomType: "INTAKE",
      }),
    );
    expect(wrapper.find('[data-connection="connected"]').exists()).toBe(true);
    wrapper.unmount();
    expect(signal.aborted).toBe(true);
  });
});
