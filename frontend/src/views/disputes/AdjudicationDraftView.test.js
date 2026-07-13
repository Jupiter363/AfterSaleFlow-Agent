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
        issue_id: "ISSUE_RECIPIENT",
        suggested_finding: "物流记录显示订单已签收",
        evidence_basis: ["EVIDENCE_WAYBILL", "EVIDENCE_SIGNATURE"],
        policy_basis: ["RULE_DELIVERY_PROOF"],
      },
    ],
    evidence_assessment: [
      {
        issue_id: "ISSUE_RECIPIENT",
        supported_by: ["EVIDENCE_WAYBILL"],
        contradicted_by: ["EVIDENCE_USER_STATEMENT"],
        missing_evidence: true,
        neutral_analysis: "签收底单不能证明用户本人签收",
        confidence: 0.68,
      },
    ],
    policy_application: [
      {
        issue_id: "ISSUE_RECIPIENT",
        rule_code: "RULE_DELIVERY_PROOF",
        rule_version: 2,
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

afterEach(() => {
  vi.restoreAllMocks();
});

async function mountDraft(role = "USER", startReviewAction = vi.fn(), historyMode = false) {
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
    props: { initialOutcome, viewerRole: role, startReviewAction },
    global: { plugins: [router] },
  });
  return { wrapper, router, startReviewAction };
}

describe("AdjudicationDraftView", () => {
  it("uses the shared room header, judge card and evidence-panel height", async () => {
    const { wrapper } = await mountDraft("USER");
    const source = fs.readFileSync(
      "src/views/disputes/AdjudicationDraftView.vue",
      "utf8",
    );

    expect(wrapper.find(".room-shell").exists()).toBe(true);
    expect(wrapper.get('[data-persona="judge"]').text()).toContain("小正");
    expect(wrapper.get('[data-persona="judge"]').text()).toContain("AI 法官");
    expect(wrapper.get("[data-draft-stage]").text()).toContain("待进入平台终审");
    expect(source).toContain("--draft-panel-height: 740px");
    expect(source).toContain(".draft-scroll__masthead");
    expect(source).toContain(".draft-scroll__analysis-board");
    expect(source).toContain("width: 100%");
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
    expect(facts.text()).toContain("ISSUE_RECIPIENT");
    expect(facts.text()).toContain("物流记录显示订单已签收");
    expect(facts.text()).toContain("EVIDENCE_WAYBILL、EVIDENCE_SIGNATURE");
    expect(facts.text()).toContain("RULE_DELIVERY_PROOF");

    const evidence = wrapper.get('[data-draft-section="evidence"]');
    expect(evidence.text()).toContain("签收底单不能证明用户本人签收");
    expect(evidence.text()).toContain("EVIDENCE_USER_STATEMENT");
    expect(evidence.text()).toContain("仍有缺失");

    const policy = wrapper.get('[data-draft-section="policy"]');
    expect(policy.text()).toContain("RULE_DELIVERY_PROOF · V2");
    expect(policy.text()).toContain("签收争议举证责任由商家承担");
    expect(policy.text()).toContain("仍需核验签收人身份");

    expect(wrapper.get('[data-draft-section="attention"]').text()).toContain("复核签收人身份");
  });

  it("reserves stable module space and keeps the boundary statement at the parchment bottom", async () => {
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
    expect(source).toContain("--draft-masthead-height: 104px");
    expect(source).toContain("--draft-overview-height: 136px");
    expect(source).toContain("--draft-notice-height: 42px");
    expect(source).toContain("minmax(220px, 1fr)");
    expect(source).toContain("grid-template-rows: auto minmax(0, 1fr)");
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
