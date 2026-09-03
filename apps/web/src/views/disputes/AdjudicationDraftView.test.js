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
    decision_reasoning:
      "签收底单未显示签收主体，签收人身份仍存在争议，现有证据不足以确认由用户本人签收。",
    remedy_orders: [
      {
        remedy_type: "REFUND_ONLY",
        order_text: "完成签收主体核验后，向用户退还订单款项。",
      },
    ],
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
    jury_review_exchanges: [
      {
        review_item_ref: "JURY_FINDING_FACT_COMPLETENESS",
        item_type: "FINDING",
        dimension: "FACT_COMPLETENESS",
        jury_opinion: "签收主体事实仍有关键缺口。",
        basis: ["签收底单未记载签收人身份。"],
        severity: "HIGH",
        requires_revision: true,
        judge_response: "已在事实认定中补充签收主体证据缺口。",
        disposition: "ACCEPTED",
        affected_fields: ["fact_findings"],
      },
      {
        review_item_ref: "JURY_MANDATORY_01",
        item_type: "MANDATORY_REVISION",
        dimension: null,
        jury_opinion: "必须核验签收主体后再作出终局裁决。",
        basis: [],
        severity: null,
        requires_revision: null,
        judge_response: "已改为等待签收主体核验。",
        disposition: "PARTIALLY_ACCEPTED",
        affected_fields: ["decision_reasoning"],
      },
    ],
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
      "双方均未提供足以证明商品交付状态或食用情况的关键证据，需转交人工客服专家进一步审核与调解。".repeat(5) +
      "Merchant-approved refund policy 与 Unshipped order cancellation policy 均不适用，应在 finding 中说明。",
    decision_reasoning:
      "双方均未提供足以证明商品交付状态或食用情况的关键证据，需转交人工客服专家进一步审核与调解。" +
      "Merchant-approved refund policy 与 Unshipped order cancellation policy 均不适用，应在 finding 中说明。",
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
  serverNow = "",
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
    props: {
      initialOutcome: outcome,
      viewerRole: role,
      startReviewAction,
      serverNow,
    },
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
    expect(source).toContain(
      "grid-template-columns: minmax(270px, .72fr) minmax(0, 1.85fr)",
    );
    expect(source).toContain("grid-template-columns: repeat(2, minmax(0, 1fr))");
    expect(source).toContain("grid-template-rows: repeat(2, minmax(0, 1fr))");
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

  it("maps the judge output into conclusion, merged fact-evidence, policy and review sections", async () => {
    const { wrapper } = await mountDraft("USER");

    expect(wrapper.get("[data-draft-summary]").text()).toContain("建议核验签收身份后退款");
    const reasoning = wrapper.get("[data-draft-reasoning]");
    expect(reasoning.text()).toContain("法官裁判理由");
    expect(reasoning.text()).not.toContain("庭审结论摘要");
    expect(reasoning.get("[data-decision-reasoning]").text()).toContain(
      "签收底单未显示签收主体",
    );
    expect(reasoning.get("[data-remedy-orders]").text()).toContain("1. [仅退款]");
    expect(reasoning.get("[data-remedy-orders]").text()).toContain(
      "完成签收主体核验后，向用户退还订单款项",
    );
    expect(reasoning.text()).not.toContain("庭审记录显示签收人身份仍存在争议");

    const facts = wrapper.get('[data-draft-section="facts"]');
    expect(facts.text()).toContain("事实 01");
    expect(facts.text()).toContain("物流记录显示订单已签收");
    expect(facts.text()).toContain("证据材料 01、证据材料 02");
    expect(facts.text()).toContain("签收争议举证规则 · V1");
    expect(facts.text()).toContain("签收人身份仍需核验");
    expect(facts.get(".draft-scroll__fact-summary-score small").text()).toBe("综合可信分");
    expect(facts.get(".draft-scroll__fact-summary-score strong").text()).toBe("78/100");
    expect(facts.text()).not.toContain("事实可信");
    expect(facts.text()).not.toContain("核验可信");
    expect(facts.text()).toContain("证据材料 01");
    expect(facts.text()).toContain("签收底单不能证明用户本人签收");
    expect(facts.text()).toContain("事实 01");
    expect(facts.text()).toContain("证明权重中");
    expect(facts.text()).toContain("缺少签收人身份信息");
    const factUnit = facts.get("[data-fact-evidence-unit]");
    const factSummary = factUnit.get(".draft-scroll__fact-detail-heading");
    expect(factSummary.text()).not.toContain("当前事实");
    expect(factSummary.text()).toContain("事实认定");
    expect(factSummary.text()).toContain("证据依据");
    expect(factSummary.text()).toContain("规则依据");
    expect(factUnit.text()).toContain("证据核验");
    expect(factUnit.text()).toContain("物流记录显示订单已签收");
    expect(factUnit.text()).toContain("签收底单不能证明用户本人签收");
    expect(factUnit.get(".draft-scroll__fact-record").text()).not.toContain("事实认定");
    expect(factUnit.get(".draft-scroll__fact-record").text()).not.toContain("证据依据");
    expect(factUnit.get(".draft-scroll__fact-record").text()).not.toContain("规则依据");
    expect(wrapper.find('[data-draft-section="evidence"]').exists()).toBe(false);
    expect(wrapper.text().match(/签收底单不能证明用户本人签收/g)).toHaveLength(1);

    const policy = wrapper.get('[data-draft-section="policy"]');
    expect(policy.text()).toContain("签收争议举证规则 · V1");
    expect(policy.get(".draft-scroll__rule").attributes("title")).toBe("签收争议举证规则 · V1");
    expect(policy.text()).toContain("签收争议举证责任由商家承担");
    expect(policy.text()).toContain("事实 01");
    expect(policy.text()).toContain("仍需核验签收人身份");

    expect(wrapper.get('[data-draft-section="attention"]').text()).toContain("复核签收人身份");
    const jury = wrapper.get('[data-draft-section="jury"]');
    expect(jury.text()).toContain("肆");
    expect(jury.text()).toContain("陪审团评审意见");
    expect(jury.text()).toContain("事实完整性");
    expect(jury.text()).toContain("签收主体事实仍有关键缺口");
    expect(jury.text()).toContain("签收底单未记载签收人身份");
    expect(jury.text()).toContain("已在事实认定中补充签收主体证据缺口");
    expect(jury.text()).toContain("陪审必改项 01");
    expect(jury.text()).toContain("部分采纳");
    expect(jury.text()).not.toContain("JURY_FINDING_FACT_COMPLETENESS");
    expect(jury.text()).not.toContain("PARTIALLY_ACCEPTED");
  });

  it("omits the complete processing-items module when the frozen draft has no orders", async () => {
    const outcomeWithoutOrders = {
      ...initialOutcome,
      adjudication_draft: {
        ...initialOutcome.adjudication_draft,
        remedy_orders: [],
      },
    };
    const { wrapper } = await mountDraft("USER", vi.fn(), false, outcomeWithoutOrders);

    expect(wrapper.find("[data-remedy-orders]").exists()).toBe(false);
    expect(wrapper.get("[data-draft-reasoning]").text()).not.toContain("处理事项");
    expect(wrapper.text()).not.toContain("暂无处理事项");
  });

  it("uses one confidence projection and switches fact-evidence details from the index", async () => {
    const twoFactOutcome = {
      ...initialOutcome,
      adjudication_draft: {
        ...initialOutcome.adjudication_draft,
        fact_findings: [
          {
            fact_id: "FACT_RECIPIENT",
            finding: "第一项事实认定",
            evidence_ids: ["EVIDENCE_WAYBILL"],
            confidence: 0.9,
          },
          {
            fact_id: "FACT_DELIVERY",
            finding: "第二项事实认定",
            evidence_ids: ["EVIDENCE_SIGNATURE"],
          },
        ],
        evidence_assessment: [
          {
            evidence_id: "EVIDENCE_WAYBILL",
            fact_ids: ["FACT_RECIPIENT"],
            assessment: "第一项证据核验",
            confidence: 0.4,
          },
          {
            evidence_id: "EVIDENCE_SIGNATURE",
            fact_ids: ["FACT_DELIVERY"],
            assessment: "第二项证据核验",
            confidence: 0.62,
          },
        ],
      },
    };
    const { wrapper } = await mountDraft("USER", vi.fn(), false, twoFactOutcome);
    const facts = wrapper.get('[data-draft-section="facts"]');
    const indexItems = facts.findAll("[data-fact-index-item]");
    const detail = facts.get("[data-fact-evidence-unit]");

    expect(indexItems).toHaveLength(2);
    expect(indexItems[0].text()).not.toContain("90/100");
    expect(indexItems[0].text()).toContain("事实");
    expect(indexItems[0].text()).toContain("01");
    expect(indexItems[0].find("small").exists()).toBe(false);
    expect(indexItems[1].text()).not.toContain("62/100");
    expect(detail.text()).toContain("第一项事实认定");
    expect(detail.text()).toContain("第一项证据核验");
    expect(detail.get(".draft-scroll__fact-summary-score small").text()).toBe("综合可信分");
    expect(detail.get(".draft-scroll__fact-summary-score strong").text()).toBe("90/100");
    expect(detail.text()).not.toContain("40/100");

    await indexItems[1].trigger("click");

    expect(detail.text()).toContain("第二项事实认定");
    expect(detail.text()).toContain("第二项证据核验");
    expect(detail.get(".draft-scroll__fact-summary-score strong").text()).toBe("62/100");
    expect(facts.text()).not.toContain("事实可信");
    expect(facts.text()).not.toContain("核验可信");
  });

  it("maps the complete V2 remedy payload into one continuous document", async () => {
    const { wrapper } = await mountDraft("USER", vi.fn(), false, expandedV2Outcome);

    expect(wrapper.get("[data-adjudication-draft-room]").attributes("data-content-density"))
      .toBeUndefined();
    expect(wrapper.get(".draft-scroll__title").text()).toContain("生鲜到货变质无法食用");
    expect(wrapper.get(".draft-scroll__title").text()).toContain("裁决草案第 2 版 · 编号已记录");
    expect(wrapper.get(".draft-scroll__title small").attributes("title")).toBeUndefined();
    expect(wrapper.get("[data-draft-summary]").text()).toContain("转人工复核");
    expect(wrapper.get(".draft-scroll__recommendation").attributes("title"))
      .toBe("建议结论：转人工复核");
    expect(wrapper.get("[data-draft-summary]").text()).not.toContain("MANUAL_REVIEW_REQUIRED");

    const policy = wrapper.get('[data-draft-section="policy"]');
    expect(policy.text()).toContain("未发货订单取消规则 · V1");
    expect(policy.text()).toContain("商家同意退款规则 · V1");
    expect(policy.text()).not.toContain("Unshipped order cancellation policy");
    expect(policy.text()).not.toContain("Merchant-approved refund policy");

    const overview = wrapper.get(".draft-scroll__overview");
    const board = wrapper.get(".draft-scroll__analysis-board");
    expect(overview.find('[data-draft-section="attention"]').exists()).toBe(false);
    expect(board.find('[data-draft-section="attention"]').exists()).toBe(true);
    expect(board.find('[data-draft-section="plan"]').exists()).toBe(false);
    expect(board.findAll("[data-draft-section]").map((section) => section.attributes("data-draft-section")))
      .toEqual(["facts", "policy", "attention", "jury"]);
    expect(board.get('[data-draft-section="attention"]').text()).toContain("叁");
    expect(board.get('[data-draft-section="attention"]').text()).toContain("重点关注事项");
    expect(board.get('[data-draft-section="jury"]').text()).toContain("肆");
    expect(board.get('[data-draft-section="jury"]').text()).toContain("陪审意见");
    expect(board.get('[data-draft-section="jury"]').text()).toContain("法官回复");
    expect(wrapper.text()).not.toContain("拟定执行方案");
    expect(wrapper.text()).not.toContain("INTERNAL_KEY_MUST_NOT_RENDER");
    expect(wrapper.text()).not.toContain("Merchant-approved refund policy");
    expect(wrapper.text()).not.toContain("Unshipped order cancellation policy");
    expect(wrapper.get("[data-draft-reasoning]").text()).toContain("应在事实认定中说明");
    expect(wrapper.text()).not.toMatch(/\b(?:FACT|EVIDENCE)_[A-Z0-9_-]+\b/u);
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

    const facts = wrapper.get('[data-draft-section="facts"]');
    expect(facts.text()).toContain("历史事实认定");
    expect(facts.text()).toContain("其他证据核验");
    const unmatchedIndex = facts
      .findAll("[data-fact-index-item]")
      .find((item) => item.text().includes("其他证据核验"));
    await unmatchedIndex.trigger("click");
    expect(facts.get("[data-fact-evidence-unit]").text()).toContain("历史证据评估");
    expect(wrapper.find('[data-draft-section="evidence"]').exists()).toBe(false);
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
    const facts = wrapper.get('[data-draft-section="facts"]');

    expect(facts.text()).toContain("证据缺口 01");
    expect(facts.text()).toContain("仍有缺失");
    expect(facts.text()).toContain("证明权重未形成");
    expect(facts.text()).not.toContain("EVIDENCE_undefined");
  });

  it("keeps a fixed dossier with internal scroll areas and the boundary at the bottom", async () => {
    const { wrapper } = await mountDraft("USER");
    const parchment = wrapper.get(".draft-scroll");
    const boundary = wrapper.get("[data-draft-boundary]");

    expect(parchment.element.lastElementChild).toBe(boundary.element);
    expect(boundary.text()).toContain("平台终审完成前");
    expect(wrapper.findAll(".draft-scroll__module-content")).toHaveLength(4);

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
    expect(source).toContain(".draft-scroll__overview { display: grid; grid-template-columns: minmax(0, 1fr);");
    expect(source).not.toContain(".draft-scroll__plan-grid");
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

  it("reads the structured open task projection without dropping legacy draft content", async () => {
    const structuredOutcome = {
      ...initialOutcome,
      review_task_id: undefined,
      review_task_status: undefined,
      review_task: {
        taskId: "REVIEW_STRUCTURED",
        taskStatus: "PENDING",
        packetStatus: "FROZEN",
        dueAt: "2026-08-01T10:00:00Z",
      },
    };
    const { wrapper, router, startReviewAction } = await mountDraft(
      "PLATFORM_REVIEWER",
      vi.fn(),
      false,
      structuredOutcome,
      "2026-07-24T10:00:00Z",
    );

    expect(wrapper.get("[data-draft-reasoning]").text()).toContain("签收人身份仍存在争议");
    await wrapper.get("[data-enter-review-room]").trigger("click");
    await flushPromises();
    expect(startReviewAction).toHaveBeenCalledWith("REVIEW_STRUCTURED");
    expect(router.currentRoute.value.path).toBe("/reviews/REVIEW_STRUCTURED");
  });

  it.each([
    { packetStatus: "PREPARING", dueAt: "2026-08-01T10:00:00Z" },
    { packetStatus: "FROZEN", dueAt: "2026-07-23T10:00:00Z" },
  ])("hides review entry for $packetStatus with due date $dueAt", async (reviewTask) => {
    const { wrapper } = await mountDraft(
      "PLATFORM_REVIEWER",
      vi.fn(),
      false,
      {
        ...initialOutcome,
        review_task_id: undefined,
        review_task_status: undefined,
        review_task: {
          taskId: "REVIEW_LOCKED",
          taskStatus: "PENDING",
          ...reviewTask,
        },
      },
      "2026-07-24T10:00:00Z",
    );

    expect(wrapper.find("[data-enter-review-room]").exists()).toBe(false);
  });
});
