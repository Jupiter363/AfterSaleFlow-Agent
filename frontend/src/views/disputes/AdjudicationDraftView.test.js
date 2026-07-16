import fs from "node:fs";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { actor } from "../../state/actor";
import AdjudicationDraftView from "./AdjudicationDraftView.vue";

const initialOutcome = {
  case_id: "CASE_DRAFT_1",
  title: "签收未收到争议",
  case_status: "WAITING_HUMAN_REVIEW",
  review_task_id: "REVIEW_DRAFT_1",
  review_task_status: "PENDING",
  adjudication_draft: {
    id: "DRAFT_1",
    draft_version: 4,
    recommended_decision: "建议核验签收身份后退款",
    confidence: 0.82,
    draft_text: "庭审记录显示签收人身份仍存在争议。",
    draft_status: "PENDING_HUMAN_REVIEW",
    fact_findings: [
      {
        fact_id: "FACT_RECIPIENT",
        finding: "物流记录显示订单已签收",
        evidence_ids: ["EVIDENCE_WAYBILL", "EVIDENCE_SIGNATURE"],
        evidence_gap: "签收人身份仍需核验",
        confidence: 0.78,
      },
    ],
    evidence_assessment: [
      {
        assessment_type: "EVIDENCE",
        evidence_id: "EVIDENCE_WAYBILL",
        fact_ids: ["FACT_RECIPIENT"],
        assessment: "签收底单不能证明用户本人签收",
        weight: "MEDIUM",
        confidence: 0.68,
        limitations: ["缺少签收人身份信息"],
      },
    ],
    policy_application: [
      {
        rule_code: "DELIVERY_PROOF",
        rule_version: 1,
        rule_name: "签收争议举证规则",
        fact_ids: ["FACT_RECIPIENT"],
        applicable: true,
        rationale: "签收争议举证责任由商家承担",
        limitations: ["仍需核验签收人身份"],
      },
    ],
    reviewer_attention: ["复核签收人身份"],
  },
  final_decision: { conclusion: "不应在草案室展示" },
  actions: [{ action_type: "REFUND" }],
};

const expandedV2Outcome = {
  ...initialOutcome,
  title: "生鲜到货变质无法食用",
  adjudication_draft: {
    ...initialOutcome.adjudication_draft,
    id: "JUDGE_V2_TARGET_DRAFT",
    draft_version: 2,
    recommended_decision: "MANUAL_REVIEW_REQUIRED",
    draft_text:
      "双方均未提供足以证明商品交付状态或食用情况的关键证据，需转交人工客服专家进一步审核与调解。".repeat(5),
    fact_findings: Array.from({ length: 4 }, (_, index) => ({
      fact_id: `FACT_INTAKE_${index + 1}`,
      finding: `第 ${index + 1} 项核心事实缺少客观证据，当前处于未证实状态。`,
      confidence: 0.1,
      evidence_gap: "缺少冷链温度记录、出库质检证明和开箱影像。",
      evidence_ids: [],
    })),
    reviewer_attention: Array.from(
      { length: 4 },
      (_, index) => `终审关注事项 ${index + 1}：人工权衡双方举证能力和行业惯例。`,
    ),
    policy_application: [
      {
        rule_code: "UNSHIPPED_CANCEL",
        rule_version: 1,
        rule_name: "Unshipped order cancellation policy",
        fact_ids: ["FACT_INTAKE_1"],
        applicable: false,
        rationale: "本案争议发生在收货后，不适用未发货取消规则。",
        limitations: ["场景不匹配"],
      },
      {
        rule_code: "MERCHANT_APPROVED_REFUND",
        rule_version: 1,
        rule_name: "Merchant-approved refund policy",
        fact_ids: ["FACT_INTAKE_2"],
        applicable: false,
        rationale: "商家明确拒绝退款，不适用商家同意退款规则。",
        limitations: ["商家未同意"],
      },
    ],
    approved_plan: {
      id: "REMEDY_TARGET_PLAN",
      version: 1,
      actions: [
        {
          action_type: "CREATE_MANUAL_REVIEW_TICKET",
          risk_level: "LOW",
          requires_approval: true,
          parameters: {
            source_recommendation: "MANUAL_REVIEW_REQUIRED",
            source_is_final_decision: false,
          },
          preconditions: [
            "CASE_NOT_CLOSED",
            "PLAN_VERSION_CURRENT",
            "PLATFORM_REVIEW_APPROVED",
            "TARGET_RESOURCE_AVAILABLE",
          ],
          idempotency_key: "INTERNAL_KEY_MUST_NOT_RENDER",
        },
      ],
      preconditions: [
        "CASE_NOT_CLOSED",
        "PLAN_VERSION_CURRENT",
        "PLATFORM_REVIEW_APPROVED",
      ],
      notifications: [
        "NOTIFY_USER_AFTER_EXECUTION",
        "NOTIFY_MERCHANT_AFTER_EXECUTION",
        "AUDIT_EXECUTION_RESULT",
      ],
    },
  },
};

afterEach(() => {
  vi.restoreAllMocks();
});

async function mountDraft(
  role = "USER",
  startReviewAction = vi.fn(),
  historyMode = false,
  outcome = initialOutcome,
) {
  actor.id = role === "PLATFORM_REVIEWER" ? "reviewer-local" : `${role.toLowerCase()}-local`;
  actor.role = role;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes", component: { template: "<div />" } },
      { path: "/disputes/:caseId/draft", component: { template: "<div />" } },
      { path: "/reviews/:reviewId", component: { template: "<div />" } },
    ],
  });
  await router.push(
    historyMode
      ? "/disputes/CASE_DRAFT_1/draft?view=history"
      : "/disputes/CASE_DRAFT_1/draft",
  );
  await router.isReady();
  const wrapper = mount(AdjudicationDraftView, {
    props: { initialOutcome: outcome, viewerRole: role, startReviewAction },
    global: { plugins: [router] },
  });
  return { wrapper, router, startReviewAction };
}

describe("AdjudicationDraftView", () => {
  it("uses the shared room header, judge card and fixed dossier height", async () => {
    const { wrapper } = await mountDraft("USER");
    const source = fs.readFileSync(
      "src/views/disputes/AdjudicationDraftView.vue",
      "utf8",
    );

    expect(wrapper.find(".room-shell").exists()).toBe(true);
    expect(wrapper.get('[data-persona="judge"]').text()).toContain("小正");
    expect(wrapper.get('[data-persona="judge"]').text()).toContain("AI 法官");
    expect(wrapper.get("[data-draft-stage]").text()).toContain("待进入平台终审");
    expect(source).toContain(".draft-scroll__masthead");
    expect(source).toContain(".draft-scroll__analysis-board");
    expect(source).toContain("grid-template-columns: repeat(4, minmax(240px, 1fr))");
    expect(source).toContain("width: 100%");
    expect(source).toContain("--draft-panel-height: 740px");
    expect(source).toContain("grid-template-rows: var(--draft-panel-height) auto auto");
    expect(source).toMatch(
      /\.draft-room__document\s*\{[\s\S]*?height: var\(--draft-panel-height\);/,
    );
    expect(source).toMatch(
      /\.draft-scroll__summary\s*\{[^}]*align-items: start;/,
    );
    expect(source).toMatch(
      /\.draft-scroll__recommendation h3\s*\{[^}]*margin: 4px 0 0;[^}]*font-size: 34px;[^}]*line-height: 1\.1;/,
    );
    expect(source).toContain("grid-template-rows: 24px minmax(0, 1fr) 24px");
    expect(source).toContain("overflow-y: auto");
  });

  it("matches the agent-card width and keeps both scroll ends inside the frame", async () => {
    const { wrapper } = await mountDraft("USER");
    const frame = wrapper.get("[data-draft-scroll]");

    expect(frame.findAll(".draft-scroll-frame__rod")).toHaveLength(2);
    expect(frame.findAll(".draft-scroll-frame__rod i")).toHaveLength(4);

    const source = fs.readFileSync(
      "src/views/disputes/AdjudicationDraftView.vue",
      "utf8",
    );
    const frameRule = source.match(/\.draft-scroll-frame \{([\s\S]*?)\n\}/)?.[1] || "";
    const rodRule = source.match(/\.draft-scroll-frame__rod \{([\s\S]*?)\n\}/)?.[1] || "";
    expect(frameRule).toContain("width: 100%");
    expect(frameRule).toContain("overflow: visible");
    expect(rodRule).toContain("margin: 0");
    expect(source).toContain("box-sizing: border-box; width: 30px");
    expect(source).not.toContain("margin: 0 -12px");
  });

  it("uses the parchment directly without an outer card surface", async () => {
    const { wrapper } = await mountDraft("USER");
    const room = wrapper.get("[data-adjudication-draft-room]");

    expect(room.classes()).toContain("draft-room");
    expect(room.find("[data-draft-scroll]").exists()).toBe(true);
    expect(room.attributes("style") || "").not.toMatch(/background|border|box-shadow/);

    const source = fs.readFileSync(
      "src/views/disputes/AdjudicationDraftView.vue",
      "utf8",
    );
    const outerRule = source.match(/\.draft-room \{([\s\S]*?)\n\}/)?.[1] || "";
    expect(outerRule).not.toMatch(/background\s*:|border\s*:|border-radius\s*:|box-shadow\s*:/);
  });

  it("maps the judge output into conclusion, issue, evidence, policy and review sections", async () => {
    const { wrapper } = await mountDraft("USER");

    expect(wrapper.get("[data-draft-summary]").text()).toContain("建议核验签收身份后退款");
    expect(wrapper.get("[data-draft-reasoning]").text()).toContain(
      "庭审记录显示签收人身份仍存在争议",
    );

    const facts = wrapper.get('[data-draft-section="facts"]');
    expect(facts.text()).toContain("FACT_RECIPIENT");
    expect(facts.text()).toContain("物流记录显示订单已签收");
    expect(facts.text()).toContain("EVIDENCE_WAYBILL、EVIDENCE_SIGNATURE");
    expect(facts.text()).toContain("签收争议举证规则 · V1");
    expect(facts.text()).toContain("签收人身份仍需核验");
    expect(facts.text()).toContain("可信分 78/100");

    const evidence = wrapper.get('[data-draft-section="evidence"]');
    expect(evidence.text()).toContain("EVIDENCE_WAYBILL");
    expect(evidence.text()).toContain("签收底单不能证明用户本人签收");
    expect(evidence.text()).toContain("FACT_RECIPIENT");
    expect(evidence.text()).toContain("证明权重中");
    expect(evidence.text()).toContain("缺少签收人身份信息");

    const policy = wrapper.get('[data-draft-section="policy"]');
    expect(policy.text()).toContain("签收争议举证规则 · V1");
    expect(policy.get(".draft-scroll__rule").attributes("title")).toBe("DELIVERY_PROOF");
    expect(policy.text()).toContain("签收争议举证责任由商家承担");
    expect(policy.text()).toContain("FACT_RECIPIENT");
    expect(policy.text()).toContain("仍需核验签收人身份");

    expect(wrapper.get('[data-draft-section="attention"]').text()).toContain("复核签收人身份");
  });

  it("maps the complete V2 remedy payload into one continuous document", async () => {
    const { wrapper } = await mountDraft("USER", vi.fn(), false, expandedV2Outcome);

    expect(wrapper.get("[data-adjudication-draft-room]").attributes("data-content-density"))
      .toBeUndefined();
    expect(wrapper.get(".draft-scroll__title").text()).toContain("生鲜到货变质无法食用");
    expect(wrapper.get(".draft-scroll__title").text()).toContain("JUDGE_V2_TARGET_DRAFT");
    expect(wrapper.get("[data-draft-summary]").text()).toContain("转人工复核");
    expect(wrapper.get(".draft-scroll__recommendation").attributes("title"))
      .toBe("MANUAL_REVIEW_REQUIRED");
    expect(wrapper.get("[data-draft-summary]").text()).not.toContain("MANUAL_REVIEW_REQUIRED");

    const policy = wrapper.get('[data-draft-section="policy"]');
    expect(policy.text()).toContain("未发货订单取消规则 · V1");
    expect(policy.text()).toContain("商家同意退款规则 · V1");
    expect(policy.text()).not.toContain("Unshipped order cancellation policy");
    expect(policy.text()).not.toContain("Merchant-approved refund policy");

    const plan = wrapper.get('[data-draft-section="plan"]');
    expect(wrapper.get(".draft-scroll__analysis-board").find('[data-draft-section="plan"]').exists())
      .toBe(true);
    expect(plan.text()).toContain("REMEDY_TARGET_PLAN");
    expect(plan.text()).toContain("创建人工复核工单");
    expect(plan.text()).toContain("低风险");
    expect(plan.text()).toContain("否，仍需平台终审");
    expect(plan.text()).toContain("平台终审通过后执行");
    expect(plan.text()).toContain("案件尚未关闭");
    expect(plan.text()).toContain("执行后通知用户");
    expect(plan.text()).toContain("执行后通知商家");
    expect(plan.text()).toContain("记录执行审计结果");
    expect(plan.text()).not.toContain("INTERNAL_KEY_MUST_NOT_RENDER");
  });

  it("keeps historical string-only V2 sections readable", async () => {
    const legacyOutcome = {
      ...initialOutcome,
      adjudication_draft: {
        ...initialOutcome.adjudication_draft,
        fact_findings: ["历史事实认定"],
        evidence_assessment: ["历史证据评估"],
        policy_application: ["历史规则适用"],
      },
    };
    const { wrapper } = await mountDraft("USER", vi.fn(), false, legacyOutcome);

    expect(wrapper.get('[data-draft-section="facts"]').text()).toContain("历史事实认定");
    expect(wrapper.get('[data-draft-section="evidence"]').text()).toContain("历史证据评估");
    expect(wrapper.get('[data-draft-section="policy"]').text()).toContain("历史规则适用");
  });

  it("renders an explicit evidence gap without inventing an evidence id", async () => {
    const gapOutcome = {
      ...initialOutcome,
      adjudication_draft: {
        ...initialOutcome.adjudication_draft,
        evidence_assessment: [
          {
            assessment_type: "EVIDENCE_GAP",
            evidence_id: null,
            fact_ids: ["FACT_RECIPIENT"],
            assessment: "签收主体事实没有可供采信的证据。",
            weight: "NONE",
            confidence: 0.4,
            limitations: ["需人工判断举证不能的不利后果"],
          },
        ],
      },
    };
    const { wrapper } = await mountDraft("USER", vi.fn(), false, gapOutcome);
    const evidence = wrapper.get('[data-draft-section="evidence"]');

    expect(evidence.text()).toContain("证据缺口 01");
    expect(evidence.text()).toContain("仍有缺失");
    expect(evidence.text()).toContain("证明权重未形成");
    expect(evidence.text()).not.toContain("EVIDENCE_undefined");
  });

  it("keeps a fixed dossier with internal scroll areas and the boundary at the bottom", async () => {
    const { wrapper } = await mountDraft("USER");
    const parchment = wrapper.get(".draft-scroll");
    const boundary = wrapper.get("[data-draft-boundary]");

    expect(parchment.element.lastElementChild).toBe(boundary.element);
    expect(boundary.text()).toContain("平台终审完成前");
    expect(wrapper.findAll(".draft-scroll__module-content")).toHaveLength(5);

    const source = fs.readFileSync(
      "src/views/disputes/AdjudicationDraftView.vue",
      "utf8",
    );
    expect(source).toContain("--draft-panel-height: 740px");
    expect(source).toContain("--draft-masthead-height: 104px");
    expect(source).toContain("--draft-notice-height: 42px");
    expect(source).toMatch(
      /grid-template-rows:\s*var\(--draft-masthead-height\)\s*minmax\(0, 1fr\)\s*minmax\(0, 1fr\)\s*var\(--draft-notice-height\)/,
    );
    expect(source).toContain(".draft-scroll__module-content { min-width: 0; min-height: 0; padding-right: 5px; overflow-y: auto;");
    expect(source).toContain(".draft-scroll__plan-grid { display: grid;");
    expect(source).toContain("overflow-y: auto");
    expect(source).toContain("padding: 24px 40px 0");
  });

  it.each(["USER", "MERCHANT", "PLATFORM_REVIEWER"])(
    "shows the same read-only hearing draft to %s",
    async (role) => {
      const { wrapper } = await mountDraft(role);

      expect(wrapper.get("[data-draft-scroll]").text()).toContain("履约争端裁决草案");
      expect(wrapper.get('[data-draft-section="facts"]').text()).toContain("物流记录显示订单已签收");
      expect(wrapper.get('[data-draft-section="attention"]').text()).toContain("复核签收人身份");
      expect(wrapper.text()).not.toContain("不应在草案室展示");
      expect(wrapper.text()).not.toContain("确认草案");
      expect(wrapper.find("[data-review-confirm]").exists()).toBe(false);
    },
  );

  it("starts terminal review only when the platform reviewer clicks the handoff", async () => {
    const { wrapper, router, startReviewAction } = await mountDraft("PLATFORM_REVIEWER");

    await wrapper.get("[data-enter-review-room]").trigger("click");
    await flushPromises();

    expect(startReviewAction).toHaveBeenCalledWith("REVIEW_DRAFT_1");
    expect(router.currentRoute.value.fullPath).toBe("/reviews/REVIEW_DRAFT_1");
  });

  it("does not show the terminal-review handoff to either party", async () => {
    const { wrapper } = await mountDraft("MERCHANT");
    expect(wrapper.find("[data-enter-review-room]").exists()).toBe(false);
  });

  it("keeps a historical draft read-only even for the platform reviewer", async () => {
    const startReviewAction = vi.fn();
    const { wrapper } = await mountDraft(
      "PLATFORM_REVIEWER",
      startReviewAction,
      true,
    );

    expect(wrapper.get("[data-room-history-banner]").text()).toContain("历史浏览模式");
    expect(wrapper.find("[data-enter-review-room]").exists()).toBe(false);
    expect(startReviewAction).not.toHaveBeenCalled();
  });
});
