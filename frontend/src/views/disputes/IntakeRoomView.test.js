import { flushPromises, mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { actor } from "../../state/actor";
import IntakeRoomView from "./IntakeRoomView.vue";

function readUtf8Source(path) {
  return readFileSync(path, "utf8").replace(/\r\n/g, "\n");
}

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
      initialMessages: Object.hasOwn(options, "initialMessages") ? options.initialMessages : [],
      initialTurnMemory: Object.hasOwn(options, "initialTurnMemory")
        ? options.initialTurnMemory
        : null,
      postMessageAction: options.postMessageAction,
      messagesLoader: options.messagesLoader,
      turnMemoryLoader: options.turnMemoryLoader,
      confirmAction: options.confirmAction || vi.fn(),
      cancelAction: options.cancelAction,
    },
    global: { plugins: [router] },
    attachTo: options.attachTo,
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
    expect(wrapper.get("[data-dispute-detail-card]").text()).toContain("争议详情");
    expect(wrapper.text()).toContain("未收到包裹");
    expect(wrapper.get("[data-dispute-detail-respondent]").text()).toContain("商家尚未回应");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("下一步核验重点");
    expect(wrapper.text()).toContain("高风险");
    expect(wrapper.text()).not.toContain("签收人与收件人不一致");
    expect(wrapper.text()).not.toContain("最终确认说明");
    expect(wrapper.text()).not.toContain("AI 受理建议非最终");
    expect(wrapper.find(".intake-dossier__confirm textarea").exists()).toBe(false);
    expect(wrapper.find(".intake-dossier__actions").exists()).toBe(true);
    expect(wrapper.find("[data-origin-statement-card]").exists()).toBe(true);
    expect(wrapper.find("[data-single-party-statement]").exists()).toBe(true);
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

    expect(wrapper.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(wrapper.get("[data-dispute-detail-summary]").element.tagName).toBe("DIV");
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
      initialAnalysis: {
        ...analysis,
        initiator_role: "MERCHANT",
      },
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
              improvement_reason: "仍缺少可信的用户原始陈述与商家质检视频。",
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
    const summaryCard = wrapper.get("[data-case-detail-summary-card]");
    expect(wrapper.find("[data-dossier-status-rail]").exists()).toBe(true);
    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("完善度 88%");
    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("中风险");
    expect(summaryCard.find("[data-dispute-detail-card]").exists()).toBe(true);
    expect(summaryCard.find("[data-dispute-detail-card]").text()).toContain("争议详情");
    expect(summaryCard.find(".intake-case-detail__story").exists()).toBe(false);
    expect(summaryCard.find(".intake-case-detail__focus").exists()).toBe(false);
    expect(summaryCard.find(".intake-case-detail__reason").exists()).toBe(false);
    expect(summaryCard.find(".intake-case-detail__chips").exists()).toBe(false);
    expect(summaryCard.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(summaryCard.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(summaryCard.get("[data-dispute-detail-summary]").element.tagName).toBe("DIV");
    expect(wrapper.text()).toContain("用户称订单物流已显示签收");
    expect(summaryCard.text()).not.toContain("仍缺少可信的用户原始陈述与商家质检视频。");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("下一步核验重点");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("签收底单");
    expect(wrapper.text()).not.toContain("SIGNED_NOT_RECEIVED");
    expect(wrapper.text()).not.toContain("88/100");
    expect(wrapper.text()).toContain("可以进入下一步");
    expect(wrapper.find("[data-case-detail-dossier]").exists()).toBe(true);
    expect(wrapper.find("[data-case-detail-meta]").exists()).toBe(false);
    expect(wrapper.find("[data-case-index-strip]").exists()).toBe(true);
    expect([...summaryCard.element.children].some((child) => child.hasAttribute("data-case-index-strip"))).toBe(false);
    expect(summaryCard.get("[data-dispute-detail-card] [data-case-index-strip]").exists()).toBe(true);
    expect(summaryCard.get("[data-dispute-detail-card] [data-origin-statement-card]").exists()).toBe(true);
    expect(wrapper.findAll("[data-case-index-chip]").length).toBe(0);
    expect(wrapper.find("[data-case-index-list]").exists()).toBe(true);
    expect(wrapper.findAll("[data-case-index-field]").length).toBe(3);
    expect(wrapper.get("[data-case-index-strip]").text()).toContain("ORDER-1");
    expect(wrapper.get("[data-case-index-strip]").text()).toContain("AFTER-1");
    expect(wrapper.get("[data-case-index-strip]").text()).toContain("SF1234567890");
    expect(wrapper.find("[data-party-claims-grid]").exists()).toBe(false);
    expect(wrapper.findAll("[data-party-claim-card]").length).toBe(0);
    expect(wrapper.find("[data-origin-statement-card]").exists()).toBe(true);
    expect(wrapper.find("[data-single-party-statement]").exists()).toBe(true);
    expect(wrapper.findAll("[data-dossier-section]").length).toBe(0);
    expect(wrapper.text()).toContain("案件索引");
    expect(wrapper.text()).toContain("原始陈述");
    expect(wrapper.find("[data-origin-statement-note]").exists()).toBe(false);
    expect(wrapper.find("[data-single-party-statement-label]").exists()).toBe(true);
    expect(wrapper.text()).toContain("商家要求等待物流核查。");
    expect(wrapper.text()).not.toContain("用户描述商家要求等待物流核查。");
    expect(wrapper.text()).not.toContain("用户描述：商家自述");
    expect(wrapper.text()).not.toContain("商家情况（用户转述/待核验）");
    expect(wrapper.text()).not.toContain("双方说法");
    expect(wrapper.text()).not.toContain("用户主张");
    expect(wrapper.text()).not.toContain("商家主张");
    expect(wrapper.text()).not.toContain("订单 / 售后 / 物流");
    expect(wrapper.text()).toContain("ORDER-1");
    expect(wrapper.text()).toContain("AFTER-1");
    expect(wrapper.text()).toContain("SF1234567890");
    expect(wrapper.text()).not.toContain("发起方：商家");
    expect(wrapper.text()).not.toContain("关联引用");
    expect(wrapper.text()).not.toContain("处理判断");
    expect(wrapper.get("[data-case-risk-grade]").text()).toContain("中风险");
    expect(wrapper.get("[data-dossier-progress-hint]").text()).toBe("可以进入下一步");
    expect(wrapper.text()).not.toContain("可继续对话纠正");
  });

  it("renders the claim and respondent attitude state from the intake dossier", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 1,
          quality_score: 82,
          ready_for_next_step: true,
          admission_recommendation: "ACCEPTED",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "签收未收到争议",
              one_sentence_summary: "物流显示签收，但用户称本人未收到包裹。",
            },
            references: {
              order_reference: "ORDER-CLAIM-1",
              logistics_reference: "SF123456789",
            },
            party_positions: {
              user_claim: "用户称本人未收到包裹。",
              merchant_claim: "",
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户请求退款。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REFUND",
              requested_amount: 299,
              requested_items: "儿童手表 1 件",
              request_reason: "用户称物流显示签收但本人未收到包裹，希望退款。",
              original_statement: "我没收到包裹，希望退款",
              normalized_statement: "用户称未实际收到包裹，并请求退款。",
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "NOT_RESPONDED",
              position: "商家尚未在接待室表达态度。",
              source: "尚未回应",
              confidence: 0.5,
            },
            dispute_core_state: {
              core_conflict: "用户请求退款，但商家态度尚待补充。",
              conflict_type: "CLAIM_UNANSWERED",
              facts_in_dispute: ["用户是否实际收到商品"],
              next_verification_focus: ["签收人身份", "物流投递轨迹"],
            },
            dispute_focus: {
              core_issue: "SIGNED_NOT_RECEIVED",
              facts_to_verify: ["签收人身份"],
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
            },
            intake_quality: {
              score: 82,
              threshold: 80,
              ready_for_next_step: true,
            },
            admission: {
              recommendation: "ACCEPTED",
            },
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.text()).toContain("争议详情");
    expect(disputeDetail.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(disputeDetail.get("[data-dispute-detail-summary]").element.tagName).toBe("DIV");
    expect(disputeDetail.get("[data-dispute-detail-summary]").text()).toContain("物流显示签收");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("用户称未实际收到包裹，并请求退款");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("¥299");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("儿童手表 1 件");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("商家尚未回应");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).not.toContain("商家尚未在接待室表达态度");
    expect(disputeDetail.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("签收人身份");
    expect(wrapper.find("[data-case-claim-status]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("REFUND");
    expect(wrapper.text()).not.toContain("NOT_RESPONDED");
  });

  it("shows claim status and verification gaps for legacy case-detail dossiers without structured claim fields", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        order_reference: "ORDER-FE-31029016",
        after_sales_reference: "",
        logistics_reference: "",
      },
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 1,
          quality_score: 0,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "履约争议待核实",
              one_sentence_summary: "物流显示签收，但我没有收到包裹。我希望平台核实后退款。",
            },
            references: {
              order_reference: "ORDER-FE-31029016",
              after_sales_reference: "",
              logistics_reference: "",
            },
            party_positions: {
              user_claim: "物流显示签收，但我没有收到包裹。我希望平台核实后退款。",
              merchant_claim: "",
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户请求退款。",
            },
            risk_assessment: {
              case_grade: "HIGH",
              risk_signals: [],
            },
            intake_quality: {
              score: 0,
              threshold: 80,
              ready_for_next_step: false,
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.text()).toContain("争议详情");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("用户请求退款");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("商家尚未回应");
    expect(disputeDetail.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(disputeDetail.text()).not.toContain("REFUND");
    expect(disputeDetail.text()).not.toContain("UNKNOWN");

    const gaps = wrapper.get("[data-verification-gaps]");
    expect(gaps.text()).toContain("下一步核验重点");
    expect(gaps.text()).toContain("物流单号或平台可识别的物流引用");
    expect(gaps.text()).toContain("签收截图、取件记录或未收到凭证");
    expect(gaps.text()).toContain("商家对诉求的明确回应");
  });

  it("uses the user position as the respondent response when the merchant initiated the dispute", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "MERCHANT",
        party_claims: {
          merchant: "商家请求平台驳回退款。",
          user: "用户不同意驳回，仍要求退款。",
        },
      },
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 1,
          dossier: {
            schema_version: "intake_case_detail.v1",
            claim_resolution: {
              initiator_role: "MERCHANT",
              requested_resolution: "OTHER",
              normalized_statement: "商家请求平台驳回退款。",
            },
            party_positions: {
              merchant_claim: "商家请求平台驳回退款。",
              user_claim: "用户不同意驳回，仍要求退款。",
            },
          },
        },
      },
    });

    const respondent = wrapper.get("[data-dispute-detail-respondent]");
    expect(respondent.text()).toContain("用户不同意驳回，仍要求退款");
    expect(respondent.text()).not.toContain("商家请求平台驳回退款");
  });

  it("asks for the user response when a merchant-initiated dispute has no respondent position", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "MERCHANT",
        party_claims: {
          merchant: "商家请求平台核验用户退款理由。",
          user: "",
        },
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 1,
          dossier: {
            schema_version: "intake_case_detail.v1",
            claim_resolution: {
              initiator_role: "MERCHANT",
              requested_resolution: "VERIFY_OR_EXPLAIN_ONLY",
            },
            party_positions: {
              merchant_claim: "商家请求平台核验用户退款理由。",
              user_claim: "",
            },
          },
        },
      },
    });

    expect(wrapper.get("[data-dispute-detail-respondent]").text()).toContain("用户尚未回应");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("用户对诉求的明确回应");
    expect(wrapper.get("[data-verification-gaps]").text()).not.toContain("商家对诉求的明确回应");
  });

  it("preserves external reference identifiers without translating enum-like tokens", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        order_reference: "ORDER_MERCHANT_INITIATED",
        after_sales_reference: "AFTER_USER_HIGH",
        logistics_reference: "LOGISTICS_MEDIUM_USER",
      },
    });

    const index = wrapper.get("[data-case-index-strip]");
    expect(index.text()).toContain("ORDER_MERCHANT_INITIATED");
    expect(index.text()).toContain("AFTER_USER_HIGH");
    expect(index.text()).toContain("LOGISTICS_MEDIUM_USER");
  });

  it("keeps a claim status placeholder visible when the intake memory has no case-detail schema yet", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "USER",
        requested_outcome: "",
        party_claims: {
          user: "",
          merchant: "",
        },
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 0,
          quality_score: 0,
          ready_for_next_step: false,
          dossier: {
            schema_version: "legacy_intake_summary.v1",
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.text()).toContain("争议详情");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("诉求待确认");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).not.toContain("请求待确认诉求");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).not.toContain("待确认诉求待确认");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("商家尚未回应");
    expect(disputeDetail.text()).not.toContain("UNKNOWN");
  });

  it("does not duplicate unknown role and unknown resolution in the claim placeholder", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "UNKNOWN",
        requested_outcome: "",
        party_claims: {
          user: "",
          merchant: "",
        },
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 0,
          quality_score: 0,
          ready_for_next_step: false,
          dossier: {
            schema_version: "legacy_intake_summary.v1",
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("诉求待确认");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).not.toContain("待确认诉求待确认");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("对方尚未回应");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).not.toContain("待确认尚未回应");
    expect(disputeDetail.text()).not.toContain("待确认尚未");
    expect(disputeDetail.text()).not.toContain("待确认的具体诉求");
    expect(disputeDetail.text()).not.toContain("待确认诉求与待确认回应");
  });

  it("shows service errors in a modal notice instead of embedding them in the right dossier card", async () => {
    const wrapper = await mountInteractiveView({
      confirmAction: vi.fn().mockRejectedValue(new Error("服务返回了不可解析的响应（HTTP 502）")),
    });

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(wrapper.find(".intake-dossier__error").exists()).toBe(false);
    const notice = wrapper.get("[data-intake-error-dialog]");
    expect(notice.attributes("role")).toBe("alertdialog");
    expect(notice.text()).toContain("服务暂时不可用");
    expect(notice.text()).toContain("HTTP 502");
    expect(wrapper.get("[data-case-detail-dossier]").text()).not.toContain("HTTP 502");
  });

  it("keeps long dossier text bounded while preserving full content for inspection", async () => {
    const longTitle = "物流显示签收但用户称本人、家人、同住人和门岗均未收到商品且商家坚持以系统签收记录拒绝退款的复杂履约争议";
    const longSummary =
      "用户称订单物流在系统中显示签收，但本人没有收到包裹，快递柜也没有取件记录，门岗和家人均表示未代收；商家客服要求用户自行联系平台，暂未提供签收底单、投递照片或签收人身份信息。";
    const longConflict =
      "用户请求退款并要求核验签收真实性，但商家暂未明确同意退款，双方争议集中在物流签收记录是否足以证明用户本人或其授权人员已经实际收到商品。";
    const longStatement =
      "我没有收到包裹。系统显示签收，但快递柜没有记录，门岗也说没有帮我签收，家里人也没有收到。商家客服让我找平台处理，我希望平台核验签收真实性后给我退款。";
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 63,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: longTitle,
              one_sentence_summary: longSummary,
            },
            references: {
              order_reference: "ORDER-WITH-A-VERY-LONG-REFERENCE-202607090001",
              after_sales_reference: "AFTER-SALE-LONG-ID-202607090001",
              logistics_reference: "SF-VERY-LONG-LOGISTICS-TRACKING-NUMBER-202607090001",
            },
            party_positions: {
              user_claim: longStatement,
              merchant_claim: "商家尚未给出明确退款态度，只要求用户等待物流核查。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REFUND",
              requested_amount: 299,
              requested_items: "儿童手表 1 件，订单内还包含表带和保护膜，需要确认是否整体退款",
              normalized_statement: "用户称未实际收到包裹，并请求退款。",
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "NOT_RESPONDED",
              position: "商家尚未在接待室表达明确态度，仅要求用户等待物流核查，未说明是否接受退款。",
            },
            dispute_core_state: {
              core_conflict: longConflict,
              facts_in_dispute: ["用户是否实际收到商品", "签收记录是否足以证明本人收货"],
              next_verification_focus: [
                "签收人身份、签收位置和签收时间需要进一步核验",
                "物流投递照片、快递柜记录或门岗代收记录需要补充",
              ],
            },
            dispute_focus: {
              core_issue: "物流签收记录与用户未收到陈述之间的事实冲突，需要核验签收人身份、签收位置和投递链路",
              facts_to_verify: ["签收人身份", "签收位置", "物流投递轨迹"],
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
            },
            intake_quality: {
              score: 63,
              threshold: 80,
              ready_for_next_step: false,
              improvement_reason:
                "仍缺少可信的签收人身份、签收位置、物流投递照片、商家是否接受退款的明确态度。",
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    const detailCard = wrapper.get("[data-dispute-detail-card]");
    expect(detailCard.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(detailCard.get("[data-dispute-detail-summary]").attributes("title")).toBe(longSummary);
    expect(detailCard.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(detailCard.find("[data-dispute-detail-facts]").exists()).toBe(false);
    expect(wrapper.get("[data-origin-statement-text]").attributes("title")).toBe(longStatement);
    expect(wrapper.findAll("[data-verification-gap-item]").length).toBeLessThanOrEqual(4);

    const source = readUtf8Source("src/views/disputes/IntakeRoomView.vue");
    expect(source).toContain("@supports (-webkit-line-clamp: 1)");
    expect(source).toContain("import ExpandableText");
    expect(source).toContain('data-dossier-fulltext-trigger="summary"');
    expect(source).toContain('data-dossier-fulltext-trigger="origin"');
    expect(source).toContain(':lines="5"');
    expect(source).toContain(':lines="4"');
    expect(source).toContain(".intake-case-detail__summary-note");
    expect(source).toContain("height: 110px;");
    expect(source).toContain("align-content: center;");
    expect(source).toContain(".intake-case-detail__origin-card");
    expect(source).toContain("height: 108px;");
    expect(source).toMatch(
      /\.intake-case-detail__single-statement\s*\{[\s\S]*?overflow: hidden;/,
    );
    expect(source).not.toContain("data-dispute-detail-title");
    expect(source).not.toContain("background: #ffffffad;");
    expect(source).not.toContain("border: 1px solid #e1ebf7;");
    expect(source).toContain("data-origin-statement-text");
  });

  it("keeps four verification previews and opens complete dossier text accessibly", async () => {
    const longSummary = "摘".repeat(300);
    const longStatement = "原".repeat(500);
    const verificationItems = Array.from(
      { length: 10 },
      (_, index) => `核验事项 ${index + 1}`,
    );
    const resizeCallbacks = [];

    class ResizeObserverMock {
      constructor(callback) {
        resizeCallbacks.push(callback);
      }

      observe() {}

      disconnect() {}
    }

    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    const dialogPrototype = globalThis.HTMLDialogElement.prototype;
    const originalShowModal = dialogPrototype.showModal;
    const originalClose = dialogPrototype.close;
    const showModal = vi.fn(function showModalMock() {
      this.open = true;
    });
    const close = vi.fn(function closeMock() {
      this.open = false;
    });
    Object.defineProperty(dialogPrototype, "showModal", {
      configurable: true,
      writable: true,
      value: showModal,
    });
    Object.defineProperty(dialogPrototype, "close", {
      configurable: true,
      writable: true,
      value: close,
    });
    let wrapper = await mountInteractiveView({
      attachTo: document.body,
      initialTurnMemory: {
        turn_no: 3,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 72,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "复杂售后争议",
              one_sentence_summary: longSummary,
            },
            references: {
              order_reference: "ORDER-1",
              after_sales_reference: "AFTER-1",
              logistics_reference: "LOG-1",
            },
            party_positions: {
              user_claim: longStatement,
              merchant_claim: "商家已明确回应。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REFUND",
              normalized_statement: "用户请求退款。",
              original_statement: longStatement,
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "AGREE",
              position: "商家同意退款。",
            },
            dispute_core_state: {
              core_conflict: "退款执行细节仍待确认。",
              facts_in_dispute: [],
              next_verification_focus: verificationItems,
            },
            dispute_focus: {
              core_issue: "退款执行细节",
              facts_to_verify: verificationItems,
            },
            missing_information: {
              blocking_gaps: verificationItems,
              nice_to_have_gaps: [],
              next_questions: [],
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
            },
            intake_quality: {
              score: 72,
              threshold: 80,
              ready_for_next_step: false,
              improvement_reason: "",
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    try {
      const summaryContent = wrapper.get(
        '[data-dossier-fulltext-trigger="summary"] [data-expandable-content]',
      ).element;
      const originContent = wrapper.get(
        '[data-dossier-fulltext-trigger="origin"] [data-expandable-content]',
      ).element;
      for (const element of [summaryContent, originContent]) {
        Object.defineProperty(element, "clientHeight", {
          value: 72,
          configurable: true,
        });
        Object.defineProperty(element, "scrollHeight", {
          value: 160,
          configurable: true,
        });
      }
      resizeCallbacks.forEach((callback) => callback());
      await wrapper.vm.$nextTick();

      expect(wrapper.findAll("[data-verification-gap-item]")).toHaveLength(4);
      expect(wrapper.get("[data-verification-gap-overflow]").text()).toContain(
        "另有 6 项",
      );

      const summaryTrigger = wrapper.get(
        '[data-dossier-fulltext-trigger="summary"] [data-expandable-trigger]',
      );
      summaryTrigger.element.focus();
      await summaryTrigger.trigger("click");
      await flushPromises();

      let dialog = wrapper.get("[data-dossier-fulltext-dialog]");
      expect(dialog.element.tagName).toBe("DIALOG");
      expect(dialog.element.open).toBe(true);
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(dialog.text()).toContain(longSummary);
      expect(document.activeElement).toBe(dialog.element);

      await wrapper.get("[data-dismiss-dossier-fulltext]").trigger("click");
      await flushPromises();
      expect(close).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(summaryTrigger.element);

      const originTrigger = wrapper.get(
        '[data-dossier-fulltext-trigger="origin"] [data-expandable-trigger]',
      );
      originTrigger.element.focus();
      await originTrigger.trigger("click");
      await flushPromises();

      dialog = wrapper.get("[data-dossier-fulltext-dialog]");
      expect(dialog.element.open).toBe(true);
      expect(showModal).toHaveBeenCalledTimes(2);
      expect(dialog.text()).toContain(longStatement);
      expect(document.activeElement).toBe(dialog.element);

      await dialog.trigger("cancel");
      await flushPromises();
      expect(close).toHaveBeenCalledTimes(2);
      expect(wrapper.find("[data-dossier-fulltext-dialog]").exists()).toBe(false);
      expect(document.activeElement).toBe(originTrigger.element);

      await originTrigger.trigger("click");
      await flushPromises();
      expect(showModal).toHaveBeenCalledTimes(3);
      wrapper.unmount();
      wrapper = null;
      expect(close).toHaveBeenCalledTimes(3);
    } finally {
      wrapper?.unmount();
      document.body.innerHTML = "";
      if (originalShowModal === undefined) {
        delete dialogPrototype.showModal;
      } else {
        Object.defineProperty(dialogPrototype, "showModal", {
          configurable: true,
          writable: true,
          value: originalShowModal,
        });
      }
      if (originalClose === undefined) {
        delete dialogPrototype.close;
      } else {
        Object.defineProperty(dialogPrototype, "close", {
          configurable: true,
          writable: true,
          value: originalClose,
        });
      }
      vi.unstubAllGlobals();
    }
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
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.get("[data-intake-result]").text()).toContain(
      "争议已取消，接待室已归档",
    );
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
    expect(wrapper.text()).toContain("只有发起方可以查看哦");
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(true);
  });

  it("clears intake messages and latest memory immediately when actor changes in-place", async () => {
    const wrapper = await mountInteractiveView({
      initialMessages: [
        {
          id: "MESSAGE_USER_PRIVATE",
          sequence_no: 1,
          sender_role: "USER",
          message_text: "USER private intake chat should vanish",
        },
      ],
      initialTurnMemory: {
        turn_no: 3,
        case_intake_dossier: {
          quality_score: 88,
          ready_for_next_step: true,
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "USER private right board",
              one_sentence_summary: "USER-only dossier text",
            },
            party_positions: {
              user_claim: "USER-only dossier text",
            },
            intake_quality: {
              score: 88,
              ready_for_next_step: true,
            },
          },
        },
      },
    });

    expect(wrapper.text()).toContain("用户 private intake chat should vanish");
    expect(wrapper.text()).toContain("用户-only dossier text");

    actor.id = "user-other";
    actor.role = "USER";
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).not.toContain("用户 private intake chat should vanish");
    expect(wrapper.text()).not.toContain("USER-only dossier text");
  });

  it("ignores stale intake refresh results from the previous actor", async () => {
    let resolveUserMemory;
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_USER_INITIAL",
          sequence_no: 1,
          sender_role: "USER",
          message_text: "USER initial intake chat",
        },
      ])
      .mockResolvedValue([
        {
          id: "MESSAGE_MERCHANT_CURRENT",
          sequence_no: 1,
          sender_role: "MERCHANT",
          message_text: "MERCHANT current intake chat",
        },
      ]);
    const turnMemoryLoader = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveUserMemory = resolve;
          }),
      )
      .mockResolvedValue({ turn_no: 1 });

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialTurnMemory: null,
      messagesLoader,
      turnMemoryLoader,
    });
    await flushPromises();

    actor.id = "user-other";
    actor.role = "USER";
    await wrapper.vm.$nextTick();

    resolveUserMemory({
      turn_no: 9,
      case_intake_dossier: {
        dossier: {
          schema_version: "intake_case_detail.v1",
          case_story: {
            title: "USER stale right board",
            one_sentence_summary: "USER stale right board",
          },
        },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("商家 current intake chat");
    expect(wrapper.text()).not.toContain("USER stale right board");
  });

  it("keeps the intake room outer cards at a fixed non-stretching height", () => {
    const source = readUtf8Source("src/views/disputes/IntakeRoomView.vue");

    expect(source).toContain("--intake-panel-height: 740px;");
    expect(source).toContain(
      "grid-template-rows: 44px minmax(0, 1fr) 52px;",
    );
    expect(source).toContain("grid-template-rows: 44px 412px 112px;");
    expect(source).toContain(
      "grid-template-columns: repeat(2, minmax(0, 1fr));",
    );
    expect(source).toContain(
      "grid-template-columns: repeat(3, minmax(0, 1fr));",
    );
    expect(source).toContain(
      "grid-template-columns: repeat(2, minmax(0, 1fr));",
    );
    expect(source).toContain("-webkit-line-clamp: 2;");
    expect(source).toContain(
      "@container room-workspace (min-width: 1060px)",
    );
    expect(source).not.toContain("@media (max-width: 980px)");
    expect(source).not.toMatch(
      /@media \(max-width: 580px\)[\s\S]*?intake-dossier__actions--two-column[\s\S]*?grid-template-columns: 1fr/,
    );
  });
});
