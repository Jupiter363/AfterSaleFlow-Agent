import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import HearingCourtView from "./HearingCourtView.vue";

const hearing = {
  rounds: [
    {
      round_id: "ROUND_1",
      round_no: 1,
      status: "COMPLETED",
      dossier_version: 1,
      stop_reason: "EVIDENCE_SUPPLEMENT_REQUIRED",
      summary_json: JSON.stringify({
        clerk: "双方已经提交第一版证据目录。",
        judge: "物流签收凭证仍需交叉核验。",
      }),
    },
    {
      round_id: "ROUND_2",
      round_no: 2,
      status: "OPEN",
      dossier_version: 2,
      round_deadline_at: "2026-07-03T12:05:00+08:00",
      submitted_roles: [],
      current_actor_submitted: false,
      summary_json: JSON.stringify({
        clerk: "补充证据已入卷。",
        judge: "正在形成裁决草案。",
        jury: "建议核对签收人身份。",
      }),
    },
  ],
  settlements: [
    {
      settlement_id: "SETTLEMENT_1",
      version: 1,
      status: "PROPOSED",
      proposed_by_role: "USER",
      proposal_text: "商家退款，用户撤回额外赔偿请求。",
      confirmed_roles: ["USER"],
    },
  ],
};

async function mountView(overrides = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes/:caseId/hearing", component: { template: "<div />" } },
      { path: "/disputes/:caseId/outcome", component: { template: "<div />" } },
    ],
  });
  await router.push("/disputes/CASE_HEARING_1/hearing");
  await router.isReady();
  const confirmSettlementAction =
    overrides.confirmSettlementAction || vi.fn();
  const wrapper = mount(HearingCourtView, {
    props: {
      initialHearing: hearing,
      viewerRole: "USER",
      deadlineAt: "2026-07-03T15:00:00+08:00",
      serverNow: "2026-07-03T12:00:00+08:00",
      roundLimit: 3,
      confirmSettlementAction,
      initialMessages: [],
      ...overrides,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router, confirmSettlementAction };
}

describe("HearingCourtView", () => {
  it("renders a collaborative little court with agents, rounds and a three-hour clock", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("小法庭");
    expect(wrapper.text()).toContain("证据书记官");
    expect(wrapper.text()).toContain("AI 法官");
    expect(wrapper.text()).toContain("AI 评审团");
    expect(wrapper.text()).toContain("第 2 / 3 轮");
    expect(wrapper.get("[data-hearing-countdown]").text()).toContain("03:00:00");
    await wrapper.get("[data-open-court-ledger]").trigger("click");
    expect(wrapper.get("[data-court-ledger-drawer]").text()).toContain(
      "物流签收凭证仍需交叉核验",
    );
    expect(wrapper.get("[data-court-ledger-drawer]").text()).toContain("已完成");
    expect(wrapper.get("[data-court-ledger-drawer]").text()).not.toContain("COMPLETED");
    expect(wrapper.text()).not.toContain("审核解释官");
  });

  it("uses the AI-native courtroom layout with party evidence rails and no party role cards", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.get("[data-hearing-courtroom-page]").exists()).toBe(true);
    expect(wrapper.get("[data-judge-bench]").text()).toContain("主审法官");
    expect(wrapper.get('[data-party-evidence-rail="user"]').text()).toContain(
      "用户证据原件匣",
    );
    expect(wrapper.get('[data-party-evidence-rail="merchant"]').text()).toContain(
      "商家证据原件匣",
    );
    expect(wrapper.get("[data-court-transcript]").text()).toContain(
      "庭审记录大屏",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).toContain(
      "提交本轮陈述",
    );
    expect(wrapper.get('[data-party-evidence-rail="user"]').text()).not.toContain(
      "用户代表",
    );
    expect(wrapper.get('[data-party-evidence-rail="merchant"]').text()).not.toContain(
      "商家代表",
    );
    expect(wrapper.get("[data-open-court-ledger]").text()).toContain(
      "查看庭审卷轴",
    );
  });

  it("keeps the audit aide out of the hearing page even for a platform reviewer", async () => {
    const { wrapper } = await mountView({ viewerRole: "PLATFORM_REVIEWER" });

    expect(wrapper.text()).not.toContain("审核解释官");
  });

  it("sends a settlement confirmation to the server before changing its state", async () => {
    const confirmSettlementAction = vi.fn().mockResolvedValue({
      ...hearing.settlements[0],
      status: "CONFIRMED",
      confirmed_roles: ["USER", "MERCHANT"],
    });
    const { wrapper } = await mountView({ confirmSettlementAction });

    await wrapper.get("[data-confirm-settlement]").trigger("click");
    await flushPromises();

    expect(confirmSettlementAction).toHaveBeenCalledWith(1);
    expect(wrapper.text()).toContain("双方已达成一致");
    expect(wrapper.text()).toContain("等待平台终审");
  });

  it("lets the current party submit their side of the active hearing round", async () => {
    const submitRoundAction = vi.fn().mockResolvedValue({
      round_id: "ROUND_2",
      round_no: 2,
      status: "WAITING",
      dossier_version: 2,
      round_deadline_at: "2026-07-03T12:05:00+08:00",
      submitted_roles: ["USER"],
      current_actor_submitted: true,
      summary_json: JSON.stringify({
        clerk: "用户已提交本轮陈述。",
        judge: "等待商家提交后再生成本轮判断。",
      }),
    });
    const { wrapper } = await mountView({
      submitRoundAction,
    });

    await wrapper.get("[data-submit-hearing-round]").trigger("click");
    await flushPromises();

    expect(submitRoundAction).toHaveBeenCalledWith(
      expect.objectContaining({
        dossier_version: 2,
        statement_json: expect.stringContaining('"submitted_by_role":"USER"'),
      }),
    );
    expect(wrapper.text()).toContain("已提交本轮，等待商家");
  });

  it("hides the party submit button after that party has submitted", async () => {
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialHearing: {
        ...hearing,
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "WAITING",
            submitted_roles: ["MERCHANT"],
            current_actor_submitted: true,
          },
        ],
      },
    });

    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.text()).toContain("已提交本轮，等待用户");
  });

  it("shows a sealed-round message after both parties have submitted", async () => {
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialHearing: {
        ...hearing,
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "COMPLETED",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
            summary_json: JSON.stringify({
              trigger: "BOTH_PARTIES_SUBMITTED",
              judge: "双方本轮提交完成，等待 AI 法官生成本轮判断。",
            }),
          },
        ],
      },
    });

    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.text()).toContain("双方已提交本轮");
    expect(wrapper.text()).not.toContain("等待用户");
  });

  it("hands the parties off to platform review after the final hearing round is sealed", async () => {
    const { wrapper } = await mountView({
      viewerRole: "USER",
      initialHearing: {
        ...hearing,
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "COMPLETED",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
          },
          {
            round_id: "ROUND_3",
            round_no: 3,
            status: "FORCED_CLOSED",
            dossier_version: 3,
            stop_reason: "MAX_ROUNDS",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
            summary_json: JSON.stringify({
              trigger: "MAX_ROUNDS_REACHED",
              judge: "第三轮陈述已封存，正在生成最终裁决草案。",
            }),
          },
        ],
      },
    });

    expect(wrapper.find("[data-review-handoff]").exists()).toBe(true);
    expect(wrapper.get("[data-review-handoff]").text()).toContain("平台终审");
    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.find("[data-round-input-bar]").exists()).toBe(false);
  });

  it("does not expose party round submission to platform reviewers", async () => {
    const { wrapper } = await mountView({ viewerRole: "PLATFORM_REVIEWER" });

    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
  });

  it("opens the outcome only after a durable case-closed event", async () => {
    const eventStreamer = vi.fn(async (options) => {
      options.state.connected = true;
      await options.applyEvent({ id: 14, event: "CASE_CLOSED", data: {} });
    });
    const { wrapper, router } = await mountView({ eventStreamer });
    await flushPromises();

    expect(eventStreamer).toHaveBeenCalledWith(
      expect.objectContaining({
        caseId: "CASE_HEARING_1",
        roomType: "HEARING",
      }),
    );
    expect(wrapper.find('[data-connection="connected"]').exists()).toBe(true);
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_HEARING_1/outcome",
    );
  });

  it("records party statements and creates a server-backed settlement card", async () => {
    const messageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_HEARING_1",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "我愿意接受退款方案。",
    });
    const proposeSettlementAction = vi.fn().mockResolvedValue({
      settlement_id: "SETTLEMENT_2",
      version: 2,
      status: "PROPOSED",
      proposed_by_role: "USER",
      proposal_text: "商家退款 299 元，用户不再主张额外赔偿。",
      confirmed_roles: ["USER"],
    });
    const { wrapper } = await mountView({
      messageAction,
      proposeSettlementAction,
    });

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("我愿意接受退款方案。");
    await wrapper.get("[data-send-message]").trigger("submit");
    await wrapper.get("[data-open-settlement]").trigger("click");
    await wrapper
      .get("[data-settlement-proposal]")
      .setValue("商家退款 299 元，用户不再主张额外赔偿。");
    await wrapper.get(".settlement-dialog form").trigger("submit");
    await flushPromises();

    expect(messageAction).toHaveBeenCalledWith(
      expect.objectContaining({ message_type: "PARTY_TEXT" }),
    );
    expect(proposeSettlementAction).toHaveBeenCalledWith(
      expect.objectContaining({
        proposal_text: "商家退款 299 元，用户不再主张额外赔偿。",
      }),
    );
    expect(wrapper.text()).toContain(
      "商家退款 299 元，用户不再主张额外赔偿。",
    );
  });

  it("uploads merchant supplementary evidence with the backend actor-specific source type", async () => {
    const supplementAction = vi.fn().mockResolvedValue({});
    const messageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_SUPPLEMENT_1",
      sequence_no: 3,
      sender_role: "MERCHANT",
      message_text: "已补充证据：签收底单.pdf",
    });
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      supplementAction,
      messageAction,
    });
    const input = wrapper.get('input[type="file"]');
    const file = new File(["demo"], "签收底单.pdf", {
      type: "application/pdf",
    });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    await flushPromises();

    expect(supplementAction).toHaveBeenCalledWith(
      expect.objectContaining({
        file,
        sourceType: "MERCHANT_UPLOAD",
        visibility: "PARTIES",
      }),
    );
  });

  it("shows a friendly empty ledger when no hearing rounds have started", async () => {
    const { wrapper } = await mountView({
      initialHearing: { rounds: [], settlements: [] },
    });

    await wrapper.get("[data-open-court-ledger]").trigger("click");

    expect(wrapper.get("[data-round-ledger-empty]").text()).toContain(
      "第一轮庭审记录生成后",
    );
  });
});
