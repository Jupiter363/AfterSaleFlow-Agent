import { flushPromises, mount } from "@vue/test-utils";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { actor } from "../../state/actor";
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

async function mountView(confirmAction = vi.fn(), eventStreamer = null, cancelAction = vi.fn()) {
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
      cancelAction,
      eventStreamer,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router, confirmAction, cancelAction };
}

async function mountInteractiveView(options = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes/:caseId/intake", component: { template: "<div />" } },
      { path: "/disputes/:caseId/evidence", component: { template: "<div />" } },
    ],
  });
  await router.push("/disputes/CASE_INTAKE_1/intake");
  await router.isReady();
  return mount(IntakeRoomView, {
    props: {
      initialDispute: dispute,
      initialAnalysis: analysis,
      initialMessages: [],
      initialTurnMemory: options.initialTurnMemory || null,
      postMessageAction: options.postMessageAction,
      messagesLoader: options.messagesLoader,
      turnMemoryLoader: options.turnMemoryLoader,
      confirmAction: vi.fn(),
      cancelAction: options.cancelAction,
    },
    global: { plugins: [router] },
  });
}

describe("IntakeRoomView", () => {
  beforeEach(() => {
    actor.id = "user-local";
    actor.role = "USER";
  });

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

  it("refreshes room messages and the live dossier after every intake dialogue turn", async () => {
    const postMessageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_USER_1",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "I want a refund.",
    });
    const messagesLoader = vi.fn().mockResolvedValue([
      {
        id: "MESSAGE_USER_1",
        sequence_no: 1,
        sender_role: "USER",
        message_text: "I want a refund.",
      },
      {
        id: "MESSAGE_AGENT_2",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_text: "Refund request recorded.",
      },
    ]);
    const turnMemoryLoader = vi.fn().mockResolvedValue({
      turn_no: 2,
      scroll_snapshot: {
        cards: [
          {
            key: "requested_outcome",
            label: "Expected outcome",
            value: "REFUND",
          },
          {
            key: "user_claim",
            label: "User claim",
            value: "Package not received.",
          },
        ],
        stamps: [{ text: "delivery conflict", level: "MEDIUM" }],
        admission_recommendation: "NEED_MORE_INFO",
      },
    });
    const wrapper = await mountInteractiveView({
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
    });

    await wrapper
      .get(".conversation-stream__composer textarea")
      .setValue("I want a refund.");
    await wrapper.get("[data-send-message]").trigger("submit");
    await flushPromises();

    expect(postMessageAction).toHaveBeenCalledWith(
      expect.objectContaining({ text: "I want a refund." }),
    );
    expect(messagesLoader).toHaveBeenCalled();
    expect(turnMemoryLoader).toHaveBeenCalled();
    expect(wrapper.text()).toContain("Refund request recorded.");
    expect(wrapper.text()).toContain("REFUND");
    expect(wrapper.text()).toContain("delivery conflict");
  });

  it("keeps agent memory internals out of the party intake room UI", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 11,
        memory_frame: {
          memory_modes: {
            short_term_enabled: true,
            summary_enabled: true,
            long_term_enabled: false,
            short_term_round_limit: 5,
            summary_window_round_limit: 10,
            compressed_token_limit: 200,
          },
          short_term_rounds: [
            {
              turn_no: 7,
              messages: [
                { role: "USER", content: "round 7 user answer" },
                {
                  role: "DISPUTE_INTAKE_OFFICER",
                  content: "round 7 agent question",
                },
              ],
            },
          ],
          compressed_summary: "compressed ten-round intake memory",
          long_term_slots: [],
        },
      },
    });

    expect(wrapper.find("[data-memory-panel]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("round 7 user answer");
    expect(wrapper.text()).not.toContain("compressed ten-round intake memory");
    expect(wrapper.text()).not.toContain("Mem0");
  });

  it("persists intake cancellation instead of only changing local state", async () => {
    const confirmAction = vi.fn();
    const cancelAction = vi.fn().mockResolvedValue({
      case_id: "CASE_INTAKE_1",
      case_status: "CANCELLED",
      current_room: null,
    });
    const { wrapper, router } = await mountView(confirmAction, null, cancelAction);

    await wrapper.get("[data-resolve-without-dispute]").trigger("click");
    await flushPromises();

    expect(cancelAction).toHaveBeenCalledWith(
      expect.objectContaining({
        reason: "resolved_before_admission",
      }),
    );
    expect(confirmAction).not.toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_INTAKE_1/intake",
    );
    expect(wrapper.get("[data-resolve-without-dispute]").attributes("disabled")).toBeDefined();
  });

  it("keeps reviewer identities from sending party dialogue to the intake agent", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";

    const { wrapper } = await mountView();

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.text()).toContain("切换为用户或商家身份");
  });
});
