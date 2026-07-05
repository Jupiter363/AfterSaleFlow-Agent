import { flushPromises, mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
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
      initialAnalysis: options.initialAnalysis || analysis,
      initialMessages: options.initialMessages || [],
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
    expect(wrapper.text()).toContain("高风险");
    expect(wrapper.text()).not.toContain("签收人与收件人不一致");
    expect(wrapper.text()).not.toContain("最终确认说明");
    expect(wrapper.text()).not.toContain("AI 受理建议非最终");
    expect(wrapper.find(".intake-dossier__confirm textarea").exists()).toBe(false);
    expect(wrapper.find(".intake-dossier__actions").exists()).toBe(true);
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
    expect(confirmAction.mock.calls[0][0]).not.toHaveProperty("confirmation_note");
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
    expect(wrapper.text()).not.toContain("REFUND");
    expect(wrapper.text()).not.toContain("delivery conflict");
  });

  it("shows the party statement immediately while the intake officer is thinking", async () => {
    let resolvePost;
    const postMessageAction = vi.fn(
      () =>
        new Promise((resolve) => {
          resolvePost = resolve;
        }),
    );
    const messagesLoader = vi.fn().mockResolvedValue([
      {
        id: "MESSAGE_USER_1",
        sequence_no: 1,
        sender_role: "USER",
        message_text: "我希望退款。",
      },
      {
        id: "MESSAGE_AGENT_2",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_text: "我已记录你的退款诉求。",
      },
    ]);
    const turnMemoryLoader = vi.fn().mockResolvedValue({ turn_no: 2 });
    const wrapper = await mountInteractiveView({
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
    });

    await wrapper
      .get(".conversation-stream__composer textarea")
      .setValue("我希望退款。");
    await wrapper.get("[data-send-message]").trigger("submit");
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain("我希望退款。");
    expect(messagesLoader).not.toHaveBeenCalled();

    resolvePost({
      id: "MESSAGE_USER_1",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "我希望退款。",
    });
    await flushPromises();

    expect(messagesLoader).toHaveBeenCalled();
    expect(wrapper.text()).toContain("我已记录你的退款诉求。");
  });

  it("maps backend enum values and missing slot keys into Chinese dossier copy", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 4,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 45,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "Broken watch quality issue",
              one_sentence_summary:
                "The user reports that the watch is broken. No additional details or evidence provided.",
            },
            references: {
              order_reference: "123456",
              after_sales_reference: "123123",
              logistics_reference: "123123",
            },
            party_positions: {
              user_claim:
                "The user reports that the watch is broken. No additional details or evidence provided.",
              merchant_claim: "",
            },
            dispute_focus: {
              core_issue: "UNKNOWN",
              facts_to_verify: ["product_issue_details", "user_statement"],
            },
            requested_resolution: {
              requested_outcome: "UNKNOWN",
            },
            risk_assessment: {
              risk_signals: ["ORDER_REFERENCE_CONFLICT"],
              reasoning:
                "仍缺少可信的product_issue_details、user_statement、merchant_requested_outcome、order_reference_confirmation",
            },
            intake_quality: {
              score: 45,
              threshold: 80,
              ready_for_next_step: false,
              improvement_reason:
                "仍缺少可信的product_issue_details、user_statement、merchant_requested_outcome、order_reference_confirmation",
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    expect(wrapper.text()).toContain("手表质量争议");
    expect(wrapper.text()).toContain("用户反馈手表损坏");
    expect(wrapper.text()).toContain("故障细节");
    expect(wrapper.text()).toContain("用户原始陈述");
    expect(wrapper.text()).toContain("商家期望处理方案");
    expect(wrapper.text()).toContain("订单号核对");
    expect(wrapper.text()).toContain("继续完善案件信息");
    expect(wrapper.text()).toContain("待确认");
    expect(wrapper.text()).not.toContain("Expected outcome");
    expect(wrapper.text()).not.toContain("NEED_MORE_INFO");
    expect(wrapper.text()).not.toContain("product_issue_details");
    expect(wrapper.text()).not.toContain("Broken watch quality issue");
    expect(wrapper.text()).not.toContain("The user reports");
  });

  it("renders the current case-detail dossier as the right-side board", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 4,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 88,
          ready_for_next_step: true,
          admission_recommendation: "ACCEPTED",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "物流显示签收但用户称未收到商品",
              one_sentence_summary:
                "用户称订单物流已显示签收，但本人未收到商品，商家暂未提供签收底单。",
              event_timeline: [
                {
                  time_hint: "物流签收后",
                  event: "用户发现未收到商品",
                  source: "USER_MESSAGE",
                },
              ],
            },
            references: {
              order_reference: "ORDER-1",
              after_sales_reference: "AFTER-1",
              logistics_reference: "SF1234567890",
            },
            party_positions: {
              user_claim: "物流显示签收但我没有收到商品。",
              merchant_claim: "商家要求等待物流核查。",
              platform_observation: "需要核验签收底单。",
            },
            dispute_focus: {
              core_issue: "SIGNED_NOT_RECEIVED",
              key_conflicts: ["签收记录与用户未收货陈述冲突"],
              facts_to_verify: ["签收底单"],
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户希望退款。",
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: ["SIGNED_NOT_RECEIVED"],
              reasoning: "存在签收事实冲突。",
            },
            missing_information: {
              blocking_gaps: [],
              nice_to_have_gaps: ["签收底单"],
              next_questions: [],
            },
            intake_quality: {
              score: 88,
              threshold: 80,
              ready_for_next_step: true,
              improvement_reason: "",
            },
            admission: {
              recommendation: "ACCEPTED",
              reasoning: "接待信息已足够进入证据阶段。",
              confidence: 0.88,
            },
          },
        },
      },
    });

    expect(wrapper.find("[data-case-detail-dossier]").exists()).toBe(true);
    expect(wrapper.text()).toContain("物流显示签收但用户称未收到商品");
    expect(wrapper.text()).toContain("用户称订单物流已显示签收");
    expect(wrapper.text()).toContain("SIGNED_NOT_RECEIVED");
    expect(wrapper.text()).toContain("88/100");
    expect(wrapper.text()).toContain("可以进入下一步");
    expect(wrapper.find("[data-case-detail-dossier]").exists()).toBe(true);
    expect(wrapper.find("[data-case-detail-meta]").exists()).toBe(true);
    expect(wrapper.find("[data-case-index-strip]").exists()).toBe(true);
    expect(wrapper.findAll("[data-case-index-chip]").length).toBe(2);
    expect(wrapper.find("[data-party-claims-grid]").exists()).toBe(true);
    expect(wrapper.findAll("[data-party-claim-card]").length).toBe(2);
    expect(wrapper.findAll("[data-dossier-section]").length).toBe(0);
    expect(wrapper.text()).toContain("案件索引");
    expect(wrapper.text()).toContain("单方主观描述");
    expect(wrapper.text()).toContain("用户自述");
    expect(wrapper.text()).toContain("商家情况（用户转述/待核验）");
    expect(wrapper.text()).not.toContain("双方说法");
    expect(wrapper.text()).not.toContain("用户主张");
    expect(wrapper.text()).not.toContain("商家主张");
    expect(wrapper.text()).toContain("订单 / 售后 / 物流");
    expect(wrapper.text()).toContain("订单：ORDER-1");
    expect(wrapper.text()).toContain("售后：AFTER-1");
    expect(wrapper.text()).toContain("物流：SF1234567890");
    expect(wrapper.text()).not.toContain("关联引用");
    expect(wrapper.text()).not.toContain("处理判断");
    expect(wrapper.get("[data-case-risk-grade]").text()).toContain("中风险");
    expect(wrapper.get("[data-dossier-progress-hint]").text()).toBe("可以进入下一步");
    expect(wrapper.text()).not.toContain("可继续对话纠正");
  });

  it("infers the single-party intake initiator from immutable party messages when the model slot is missing", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const { initiator_role: _initiatorRole, ...analysisWithoutInitiator } = analysis;
    const wrapper = await mountInteractiveView({
      initialAnalysis: analysisWithoutInitiator,
      initialMessages: [
        {
          id: "MESSAGE_AGENT_1",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "agent prompt",
          created_at: "2026-07-05T00:00:00Z",
        },
        {
          id: "MESSAGE_MERCHANT_1",
          sequence_no: 2,
          sender_role: "MERCHANT",
          message_text: "merchant intake answer",
          created_at: "2026-07-05T00:01:00Z",
        },
      ],
    });

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.find("[data-intake-locked-chat]").exists()).toBe(true);
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(true);
  });

  it("lays out the submit and resolve actions as a two-column action bar", async () => {
    const { wrapper } = await mountView();

    const actions = wrapper.get(".intake-dossier__actions");

    expect(actions.find("[data-confirm-admission]").exists()).toBe(true);
    expect(actions.find("[data-resolve-without-dispute]").exists()).toBe(true);
    expect(actions.classes()).toContain("intake-dossier__actions--two-column");
  });

  it("keeps persisted handoff remarks out of the right-side judgment area without adding a second input box", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 5,
        case_intake_dossier: {
          dossier_version: 3,
          quality_score: 91,
          ready_for_next_step: true,
          admission_recommendation: "ACCEPTED",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "签收未收到争议",
              one_sentence_summary: "用户补充了进入下一轮前需要带给证据书记官的备注。",
            },
            references: {
              order_reference: "ORDER-1",
              after_sales_reference: "AFTER-1",
              logistics_reference: "SF1234567890",
            },
            party_positions: {
              user_claim: "物流显示签收但我没有收到商品。",
              merchant_claim: "商家要求等待物流核查。",
            },
            dispute_focus: {
              core_issue: "SIGNED_NOT_RECEIVED",
              facts_to_verify: [],
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户希望退款。",
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
              reasoning: "",
            },
            handoff_notes: {
              remark_status: "HAS_REMARKS",
              latest_remark: "请证据书记官重点核查快递柜取件记录。",
              remarks: [
                {
                  role: "USER",
                  text: "请证据书记官重点核查快递柜取件记录。",
                  source_message_id: "MESSAGE_REMARK_1",
                },
              ],
            },
            intake_quality: {
              score: 91,
              threshold: 80,
              ready_for_next_step: true,
              improvement_reason: "",
            },
            admission: {
              recommendation: "ACCEPTED",
            },
          },
        },
      },
    });

    expect(wrapper.text()).not.toContain("下一轮备注");
    expect(wrapper.text()).not.toContain("请证据书记官重点核查快递柜取件记录。");
    expect(wrapper.find(".intake-dossier__confirm textarea").exists()).toBe(false);
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
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-intake-actions-readonly]").exists()).toBe(true);
  });

  it("keeps the non-initiating party out of the single-party intake composer", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";

    const wrapper = await mountInteractiveView({
      initialAnalysis: { ...analysis, initiator_role: "USER" },
    });

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.find("[data-intake-locked-chat]").exists()).toBe(true);
    expect(wrapper.text()).toContain("仅发起方可查看");
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(true);
  });

  it("keeps the intake room outer cards at a fixed non-stretching height", () => {
    const source = readFileSync("src/views/disputes/IntakeRoomView.vue", "utf8");

    expect(source).toContain("align-items: start;");
    expect(source).toContain("--intake-panel-height: 740px;");
    expect(source).toContain("height: var(--intake-panel-height);");
    expect(source).toContain("grid-template-rows: auto minmax(0, 1fr);");
    expect(source).toContain(".intake-room__conversation-lock-frame :deep(.conversation-stream)");
    expect(source).toContain("grid-template-rows: auto minmax(0, 1fr) auto;");
    expect(source).toContain("overflow-y: auto;");
    expect(source).not.toContain(".intake-case-detail__meta {\n  display: grid;\n  flex: 1;");
  });
});
