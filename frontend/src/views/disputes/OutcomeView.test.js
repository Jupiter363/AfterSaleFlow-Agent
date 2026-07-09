import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { disputeApi } from "../../api/disputes";
import { actor } from "../../state/actor";
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

const draftOutcome = {
  ...outcome,
  case_status: "WAITING_HUMAN_REVIEW",
  closed_at: null,
  final_decision: {
    conclusion: "等待确认裁决草案",
    explanation: "AI 法官已形成非最终裁决草案。",
    human_confirmed: false,
  },
  actions: [],
  adjudication_draft: {
    id: "DRAFT_REVIEW_READY",
    recommended_decision: "建议退款",
    confidence: 0.82,
    draft_text: "AI 法官已基于三轮庭审形成非最终裁决草案。",
    reviewer_attention: ["请审核员确认签收凭证是否足以证明用户本人签收。"],
    approved_plan: {
      id: "PLAN_1",
      actions: [{ action_type: "REFUND", amount: 199 }],
    },
  },
};

describe("OutcomeView", () => {
  beforeEach(() => {
    actor.id = "user-local";
    actor.role = "USER";
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("explains the final ruling and exposes deterministic execution receipts", async () => {
    const wrapper = await mountOutcome(outcome);

    expect(wrapper.text()).toContain("最终裁决");
    expect(wrapper.text()).toContain("支持用户退款请求");
    expect(wrapper.text()).toContain("REFUND-20260703-1");
    expect(wrapper.findAll("[data-execution-receipt]")).toHaveLength(2);
    expect(wrapper.text()).toContain("裁决已生效");
    expect(wrapper.text()).toContain("审核员确认现有证据链完整");
  });

  it("simulates execution assistant handoff for final decisions without real action receipts", async () => {
    vi.useFakeTimers();
    const wrapper = await mountOutcome({
      ...outcome,
      case_status: "APPROVED_FOR_EXECUTION",
      closed_at: null,
      final_decision: {
        conclusion: "审核员已确认草案",
        explanation: "最终裁决已确认，后续执行由执行专员助手处理。",
        review_reason: "审核员确认裁决草案。",
        source: "HUMAN_REVIEW",
        human_confirmed: true,
      },
      actions: [],
    });

    expect(wrapper.text()).toContain("裁决已确认");
    expect(wrapper.text()).toContain("方案已移交给执行专员助手处理");
    expect(wrapper.text()).toContain("执行专员助手处理中");
    expect(wrapper.text()).not.toContain("方案执行成功");

    vi.advanceTimersByTime(3000);
    await flushPromises();

    expect(wrapper.text()).toContain("方案执行成功");
  });

  it("formats nested execution result payloads instead of dumping raw JSON", async () => {
    const wrapper = await mountOutcome({
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

  it("shows the AI draft explanation chain when the backend exposes it", async () => {
    const wrapper = await mountOutcome({
      ...outcome,
      adjudication_draft: {
        id: "DRAFT_1",
        draft_version: 2,
        recommended_decision: "支持用户退款请求",
        confidence: 0.92,
        draft_text: "AI 法官基于三轮陈述和证据证明矩阵形成非最终裁决草案。",
        fact_findings: [{ fact: "物流记录显示已签收", conclusion: "已被物流记录支持" }],
        evidence_assessment: [{ assessment: "商家证据不足以证明用户本人签收" }],
        policy_application: [{ rule: "签收争议举证责任", application: "需核验签收人身份" }],
        reviewer_attention: ["核验签收人身份"],
      },
    });

    const draftCard = wrapper.get("[data-adjudication-draft]");
    expect(draftCard.text()).toContain("AI 裁决草案（非最终）");
    expect(draftCard.text()).toContain("支持用户退款请求");
    expect(draftCard.text()).toContain("92/100");
    expect(draftCard.text()).not.toContain("可信分可信分");
    expect(wrapper.get("[data-fact-findings]").text()).toContain("物流记录显示已签收");
    expect(wrapper.get("[data-evidence-assessment]").text()).toContain(
      "商家证据不足以证明用户本人签收",
    );
    expect(wrapper.get("[data-policy-application]").text()).toContain("签收争议举证责任");
    expect(wrapper.get("[data-reviewer-attention]").text()).toContain("核验签收人身份");
  });

  it("does not expose raw draft field names or internal enum values to parties", async () => {
    const wrapper = await mountOutcome({
      ...outcome,
      case_status: "WAITING_HUMAN_REVIEW",
      closed_at: null,
      final_decision: {
        conclusion: "等待确认裁决草案",
        explanation: "AI 法官已生成非最终草案。",
        human_confirmed: false,
      },
      actions: [],
      adjudication_draft: {
        id: "DRAFT_INTERNAL_FIELDS",
        recommended_decision: "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
        confidence: 0.73,
        draft_text: "AI 法官基于庭审材料生成非最终草案。",
        fact_findings: [
          {
            issue_id: "ISSUE_001",
            policy_basis: ["SIGNED_NOT_RECEIVED", "举证责任"],
            evidence_basis: ["EVIDENCE_INTERNAL_001"],
            suggested_finding:
              "用户称物流显示签收但本人未收到包裹，验证状态为NEEDS_HUMAN_REVIEW，证据ID EVIDENCE_INTERNAL_001。",
            verification_status: "NEEDS_HUMAN_REVIEW",
          },
        ],
        evidence_assessment: [
          {
            supported_by: ["EVIDENCE_INTERNAL_001"],
            contradicted_by: [],
            missing_evidence: true,
            neutral_analysis: "仍需核验签收人身份。",
          },
        ],
        reviewer_attention: ["NEEDS_HUMAN_REVIEW"],
      },
    });

    const pageText = wrapper.text();
    expect(pageText).not.toContain("issue_id");
    expect(pageText).not.toContain("policy_basis");
    expect(pageText).not.toContain("evidence_basis");
    expect(pageText).not.toContain("suggested_finding");
    expect(pageText).not.toContain("supported_by");
    expect(pageText).not.toContain("contradicted_by");
    expect(pageText).not.toContain("missing_evidence");
    expect(pageText).not.toContain("neutral_analysis");
    expect(pageText).not.toContain("NEEDS_HUMAN_REVIEW");
    expect(pageText).not.toContain("SIGNED_NOT_RECEIVED");
    expect(pageText).not.toContain("EVIDENCE_INTERNAL_001");
  });

  it("keeps the draft room compact and Chinese-first when detailed draft data is present", async () => {
    const wrapper = await mountOutcome({
      ...outcome,
      case_status: "WAITING_HUMAN_REVIEW",
      closed_at: null,
      final_decision: {
        conclusion: "建议先核验签收记录",
        explanation: "白话结论只呈现一次核心判断。",
        human_confirmed: false,
      },
      actions: [],
      adjudication_draft: {
        id: "DRAFT_COMPACT",
        recommended_decision: "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
        confidence: 0.7,
        draft_text: "重复草案正文：这段长文本不应该在草案页被多个卡片重复展示。",
        fact_findings: [{ fact: "物流显示签收但用户称未收到包裹。" }],
        evidence_assessment: [{ neutral_analysis: "仍需核验签收凭证。" }],
        reviewer_attention: ["核验签收人身份"],
      },
    });

    const pageText = wrapper.text();
    expect(pageText).toContain("白话结论");
    expect(pageText).toContain("草案依据");
    expect(pageText).toContain("解释员复盘");
    expect(pageText).not.toContain("PLAIN-LANGUAGE RULING");
    expect(pageText).not.toContain("AI JUDGE DRAFT");
    expect(pageText).not.toContain("EXPLANATION OFFICER");
    expect(pageText).not.toContain("FOLLOW-UP TRACE");
    expect(pageText.match(/重复草案正文/g) || []).toHaveLength(1);
  });

  it("does not repeat the long plain-language conclusion as the draft recommendation", async () => {
    const repeatedConclusion =
      "鉴于用户提交的售后请求及三轮庭审陈述均为乱码，无法识别具体争议内容和诉求，且用户提供的物流证明材料解析不完整，真实性待核验，商家亦未提交有效证据，现有物流记录显示已签收。建议维持订单完成状态，不支持发起赔付或退款程序。";
    const wrapper = await mountOutcome({
      ...outcome,
      case_status: "WAITING_HUMAN_REVIEW",
      closed_at: null,
      final_decision: {
        conclusion: repeatedConclusion,
        explanation: "本案仍以后续确认为准。",
        human_confirmed: false,
      },
      actions: [],
      adjudication_draft: {
        id: "DRAFT_REPEATED_RECOMMENDATION",
        recommended_decision: repeatedConclusion,
        confidence: 0.35,
        draft_text: "AI 法官已形成非最终草案。",
      },
    });

    const pageText = wrapper.text();
    expect(pageText.match(new RegExp(repeatedConclusion, "g")) || []).toHaveLength(1);
    expect(pageText).toContain("同上方白话结论一致");
    expect(pageText).not.toContain("可信分可信分");
  });

  it("presents waiting-review AI drafts as non-final outcomes without reviewer-facing wording", async () => {
    const wrapper = await mountOutcome({
      ...outcome,
      case_status: "WAITING_HUMAN_REVIEW",
      closed_at: null,
      final_decision: {
        conclusion: "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
        explanation: "最终结果仍需平台审核确认。",
        review_reason: "平台审核员需要核验签收凭证。",
        human_confirmed: false,
      },
      actions: [],
      adjudication_draft: {
        id: "DRAFT_WAITING",
        recommended_decision: "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
        confidence: 0.75,
        draft_text: "AI 法官已形成非最终裁决草案，等待平台审核员确认后生效。",
        fact_findings: ["物流显示签收，但用户称本人未收到包裹。"],
        evidence_assessment: ["商家证据仍需平台审核确认。"],
        policy_application: ["签收争议应结合签收凭证和物流记录判断。"],
        reviewer_attention: [
          "平台审核员应重点核验签收人身份。",
          '复核模型兜底生成原因：422 Unprocessable Entity: {"success":false,"code":"INVALID_ARGUMENT"}',
        ],
      },
    });

    const pageText = wrapper.text();
    expect(pageText).toContain("AI 裁决草案");
    expect(pageText).toContain("等待后续确认");
    expect(pageText).toContain("AI 生成的非最终裁决草案");
    expect(pageText).toContain("后续确认关注");
    expect(pageText).toContain("建议补发或退款");
    expect(pageText).toContain("确认与执行轨迹");
    expect(pageText).toContain("尚未产生执行动作");
    expect(pageText).toContain("复核模型曾触发兜底生成");
    expect(wrapper.get(".outcome-hero h1").text()).toBe("AI 裁决草案");
    expect(pageText).not.toContain("THE VERDICT HAS LANDED");
    expect(pageText).not.toContain("裁决已生效");
    expect(pageText).not.toContain("裁决落地轨迹");
    expect(pageText).not.toContain("执行回执正在路上");
    expect(pageText).not.toContain("平台审核员确认后的最终裁决");
    expect(pageText).not.toContain("平台审核员");
    expect(pageText).not.toContain("平台审核确认");
    expect(pageText).not.toContain("RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW");
    expect(pageText).not.toContain("422 Unprocessable Entity");
    expect(pageText).not.toContain("INVALID_ARGUMENT");
  });

  it("shows the explanation officer replay only in the draft room", async () => {
    const wrapper = await mountOutcome({
      ...outcome,
      case_status: "WAITING_HUMAN_REVIEW",
      closed_at: null,
      final_decision: {
        conclusion: "等待确认裁决草案",
        explanation: "AI 法官已生成非最终草案。",
        human_confirmed: false,
      },
      actions: [],
      adjudication_draft: {
        id: "DRAFT_WITH_EXPLAINER",
        recommended_decision: "支持用户退款请求",
        confidence: 0.81,
        draft_text: "AI 法官基于三轮庭审形成非最终草案。",
        explanation_officer_notes: {
          replay_summary:
            "解释员复盘：第一轮确认签收争点，第二轮核验补证，第三轮整理双方对拟处理方向的异议。",
          final_plan_explanation:
            "草案倾向退款，是因为商家仍未证明用户本人或授权人签收。",
          reviewer_focus: ["确认签收底单是否能证明本人签收"],
        },
      },
    });

    const explainer = wrapper.get("[data-explanation-officer]");
    expect(explainer.text()).toContain("解释员复盘");
    expect(explainer.text()).toContain("第一轮确认签收争点");
    expect(explainer.text()).toContain("草案倾向退款");
    expect(explainer.text()).toContain("确认签收底单是否能证明本人签收");
    expect(explainer.text()).not.toContain("explanation_officer_notes");
    expect(explainer.text()).not.toContain("平台终审");
  });

  it("keeps outcome review controls hidden from parties", async () => {
    const wrapper = await mountOutcome(draftOutcome);

    expect(wrapper.find("[data-outcome-review-panel]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("审核员确认草案");
    expect(wrapper.text()).not.toContain("修改并确认");
  });

  it("lets platform reviewers confirm the AI draft from the outcome room", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const confirm = vi
      .spyOn(disputeApi, "confirmOutcomeDraft")
      .mockResolvedValue({ decision: "APPROVE", execution_allowed: true });
    vi.spyOn(disputeApi, "outcome").mockResolvedValue({
      ...draftOutcome,
      final_decision: {
        conclusion: "审核员已确认草案",
        explanation: "最终处理方案等待执行同步。",
        human_confirmed: true,
      },
      case_status: "CLOSED",
      closed_at: "2026-07-09T15:40:00+08:00",
    });
    const wrapper = await mountOutcome(draftOutcome);

    expect(wrapper.get("[data-outcome-review-panel]").text()).toContain("审核员确认草案");
    await wrapper.get("[data-review-confirm]").trigger("click");
    await flushPromises();

    expect(confirm).toHaveBeenCalledWith(
      expect.objectContaining({ role: "PLATFORM_REVIEWER" }),
      "CASE_OUTCOME_1",
      expect.stringContaining("审核员确认"),
    );
    expect(wrapper.text()).toContain("审核员已确认草案");
  });

  it("shows reviewer controls for waiting-review outcomes even without embedded draft details", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";

    const wrapper = await mountOutcome({
      ...draftOutcome,
      adjudication_draft: null,
    });

    expect(wrapper.get("[data-outcome-review-panel]").text()).toContain("审核员确认草案");
    expect(wrapper.get("[data-review-confirm]").text()).toContain("确认草案");
  });

  it("lets platform reviewers modify and confirm the AI draft from the outcome room", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const modify = vi
      .spyOn(disputeApi, "modifyOutcomeDraft")
      .mockResolvedValue({ decision: "MODIFY_AND_APPROVE", execution_allowed: true });
    vi.spyOn(disputeApi, "outcome").mockResolvedValue(draftOutcome);
    const wrapper = await mountOutcome(draftOutcome);
    const plan = {
      id: "PLAN_1",
      actions: [{ action_type: "REFUND", amount: 188 }],
    };

    expect(wrapper.find("[data-review-approved-plan]").exists()).toBe(false);
    expect(wrapper.get("[data-review-plan-editor]").text()).toContain("退款");
    await wrapper.get("[data-review-action-amount]").setValue("188");
    await wrapper.get("[data-review-modify]").trigger("click");
    await flushPromises();

    expect(modify).toHaveBeenCalledWith(
      expect.objectContaining({ role: "PLATFORM_REVIEWER" }),
      "CASE_OUTCOME_1",
      expect.stringContaining("审核员"),
      plan,
    );
  });

  it("lets platform reviewers add a structured action when the draft has no actions", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const modify = vi
      .spyOn(disputeApi, "modifyOutcomeDraft")
      .mockResolvedValue({ decision: "MODIFY_AND_APPROVE", execution_allowed: true });
    vi.spyOn(disputeApi, "outcome").mockResolvedValue(draftOutcome);
    const wrapper = await mountOutcome({
      ...draftOutcome,
      adjudication_draft: {
        ...draftOutcome.adjudication_draft,
        approved_plan: { id: "PLAN_EMPTY", actions: [] },
      },
    });

    expect(wrapper.find("[data-review-approved-plan-raw]").exists()).toBe(false);
    expect(wrapper.get("[data-review-plan-editor]").text()).toContain("暂无执行动作");
    await wrapper.get("[data-review-add-action]").trigger("click");
    await wrapper.get("[data-review-action-type]").setValue("COMPENSATE");
    await wrapper.get("[data-review-action-amount]").setValue("30");
    await wrapper.get("[data-review-modify]").trigger("click");
    await flushPromises();

    expect(modify).toHaveBeenCalledWith(
      expect.objectContaining({ role: "PLATFORM_REVIEWER" }),
      "CASE_OUTCOME_1",
      expect.stringContaining("审核员"),
      {
        id: "PLAN_EMPTY",
        actions: [{ action_type: "COMPENSATE", amount: 30 }],
      },
    );
  });

  it("disables modifying an empty structured plan until an action is added", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const wrapper = await mountOutcome({
      ...draftOutcome,
      adjudication_draft: {
        ...draftOutcome.adjudication_draft,
        approved_plan: { id: "PLAN_EMPTY", actions: [] },
      },
    });

    expect(wrapper.get("[data-review-modify]").attributes("disabled")).toBeDefined();
    await wrapper.get("[data-review-add-action]").trigger("click");

    expect(wrapper.get("[data-review-modify]").attributes("disabled")).toBeUndefined();
  });
});
