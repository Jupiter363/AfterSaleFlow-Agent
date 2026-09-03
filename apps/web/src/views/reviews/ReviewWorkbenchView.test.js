// 文件作用：自动化测试文件，验证 ReviewWorkbenchView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { reviewApi } from "../../api/review";
import { actor } from "../../state/actor";
import ReviewWorkbenchView from "./ReviewWorkbenchView.vue";

const packet = {
  id: "PACKET_1",
  case_id: "CASE_REVIEW_1",
  plan_id: "REMEDY_1",
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
  remedy: { id: "REMEDY_1", actions: [{ type: "REFUND", amount: 299 }] },
  risk_flags: ["HIGH_VALUE", "SIGNATURE_MISMATCH"],
  status: "FROZEN",
  action_hash: "ACTION_HASH_1",
  agent_run_refs: ["AGENT_RUN_1", "AGENT_RUN_2", "AGENT_RUN_3"],
};

// 业务位置：【前端审核工作台】mountView：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function mountView(overrides = {}) {
  const { historyMode: openAsHistory = false, ...viewOverrides } = overrides;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/reviews", component: { template: "<div />" } },
      { path: "/reviews/:reviewId", component: { template: "<div />" } },
      {
        path: "/disputes/:caseId/outcome",
        name: "dispute-outcome",
        component: { template: "<div />" },
      },
    ],
  });
  await router.push(
    openAsHistory
      ? "/reviews/REVIEW_1?view=history"
      : "/reviews/REVIEW_1",
  );
  await router.isReady();
  const decideAction = viewOverrides.decideAction || vi.fn();
  const wrapper = mount(ReviewWorkbenchView, {
    props: {
      initialPacket: packet,
      viewerRole: "PLATFORM_REVIEWER",
      decideAction,
      serverNow: "2026-07-03T12:00:00+08:00",
      ...viewOverrides,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router, decideAction };
}

// 业务位置：【前端审核工作台】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
describe("ReviewWorkbenchView", () => {
  afterEach(() => {
    actor.id = "user-local";
    actor.role = "USER";
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("locks copilot and decision controls when reopening historical review", async () => {
    const decideAction = vi.fn();
    const { wrapper } = await mountView({ historyMode: true, decideAction });

    expect(wrapper.get("[data-review-history-banner]").text()).toContain("历史浏览模式");
    expect(wrapper.find("[data-review-decisions]").exists()).toBe(false);
    expect(wrapper.find(".review-explain-room textarea").exists()).toBe(false);
    expect(wrapper.get("[data-room-readonly]").text()).toContain("已封存");
    expect(wrapper.text()).toContain("所有批准、修改和人工接管操作均已锁定");
    expect(decideAction).not.toHaveBeenCalled();
  });

  it("clears an open decision confirmation when the active view becomes historical", async () => {
    const { wrapper, router, decideAction } = await mountView();
    await wrapper.get("[data-review-reason]").setValue("已完成证据与规则复核");
    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(true);

    await router.push("/reviews/REVIEW_1?view=history");
    await flushPromises();

    expect(wrapper.get("[data-review-history-banner]").text()).toContain("历史浏览模式");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(false);
    expect(wrapper.find("[data-review-decisions]").exists()).toBe(false);
    expect(decideAction).not.toHaveBeenCalled();
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("shows the frozen packet, review copilot and human-only controls", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.get(".room-shell__eyebrow").text()).toBe("平台人工终审");
    expect(wrapper.get(".room-shell__header h1").text()).toBe("平台终审室");
    expect(wrapper.get(".room-shell__context").text()).toContain("平台最终确认");
    expect(wrapper.find(".room-shell__boundary").exists()).toBe(false);
    expect(wrapper.findAll("[data-review-agent-card]")).toHaveLength(1);
    expect(wrapper.get("[data-review-agent-card]").text()).toContain("小译");
    expect(wrapper.text()).toContain("审核解释官");
    expect(wrapper.text()).toContain("和小译核对案件");
    const chatColumn = wrapper.get("[data-review-chat-column]");
    const materialColumn = wrapper.get("[data-review-material-column]");
    const operationColumn = wrapper.get("[data-review-operation-column]");
    expect(chatColumn.element.parentElement).toBe(operationColumn.element.parentElement);
    expect(wrapper.get(".review-triple-layout").findAll("[data-review-chat-column]")).toHaveLength(1);
    expect(wrapper.get(".review-triple-layout").findAll("[data-review-material-column]")).toHaveLength(1);
    expect(wrapper.get(".review-triple-layout").findAll("[data-review-operation-column]")).toHaveLength(1);
    expect(chatColumn.find("[data-room-message]").exists()).toBe(true);
    expect(chatColumn.find(".review-explain-room__prompts").exists()).toBe(false);
    expect(chatColumn.get("[data-quick-prompts-toggle]").attributes("aria-label")).toBe(
      "展开常用输入",
    );
    expect(chatColumn.find("[data-review-reason]").exists()).toBe(false);
    expect(materialColumn.findAll('[role="tab"]')).toHaveLength(5);
    expect(materialColumn.find("[data-review-reason]").exists()).toBe(false);
    const draftTab = materialColumn.get("#review-tab-draft");
    const riskTab = materialColumn.get("#review-tab-risk");
    const auditTab = materialColumn.get("#review-tab-audit");
    const auditPanel = materialColumn.get("[data-review-audit-panel]");
    expect(draftTab.text()).toBe("裁决草案");
    expect(riskTab.text()).toBe("重点复核");
    expect(materialColumn.find("#review-panel-draft").exists()).toBe(true);
    expect(auditTab.text()).toBe("版本与审计");
    expect(auditPanel.isVisible()).toBe(false);
    expect(wrapper.find(".review-triple-layout > .review-audit").exists()).toBe(false);
    await auditTab.trigger("click");
    await flushPromises();
    expect(auditTab.attributes("aria-selected")).toBe("true");
    const visibleAuditPanel = materialColumn.get("[data-review-audit-panel]");
    expect(visibleAuditPanel.attributes("style")).toBe("");
    expect(materialColumn.get("#review-panel-overview").attributes("style")).toContain(
      "display: none",
    );
    expect(visibleAuditPanel.text()).toContain("冻结版本与审计信息");
    expect(visibleAuditPanel.text()).toContain("审核包v3");
    expect(visibleAuditPanel.get("[data-review-version-ledger]").exists()).toBe(true);
    expect(visibleAuditPanel.findAll("[data-review-version-group]")).toHaveLength(2);
    expect(
      visibleAuditPanel.findAll("[data-review-version-group]").map((group) => group.text()),
    ).toEqual([
      expect.stringContaining("案件材料"),
      expect.stringContaining("运行基线"),
    ]);
    expect(visibleAuditPanel.findAll("[data-review-version-entry]")).toHaveLength(3);
    const auditIdentifiers = visibleAuditPanel.get("[data-review-audit-identifiers]");
    expect(auditIdentifiers.findAll("[data-review-identifier-row]")).toHaveLength(4);
    expect(auditIdentifiers.text()).toContain("审核包 IDPACKET_1");
    expect(auditIdentifiers.get("[data-review-agent-run-count]").text()).toBe("3 条记录");
    expect(auditIdentifiers.findAll("[data-review-agent-run-id]")).toHaveLength(3);
    const caseStrip = operationColumn.get("[data-review-case-strip]");
    expect(caseStrip.get("[data-review-case-identity]").text()).toContain("当前终审案件");
    expect(caseStrip.get("[data-review-case-title]").text()).toBe("签收未收到争议");
    expect(caseStrip.find("[data-review-case-baseline]").exists()).toBe(false);
    expect(caseStrip.get("[data-review-case-statuses]").text()).toContain("已冻结");
    const decisionHeading = operationColumn.get("[data-review-decision-heading]");
    expect(decisionHeading.text()).toContain("人工决定");
    expect(decisionHeading.text()).toContain("审核员判决");
    expect(decisionHeading.text()).toContain("仅审核员");
    expect(caseStrip.element.previousElementSibling).toBe(decisionHeading.element);
    expect(
      caseStrip.get("[data-review-case-identity]").find(".review-workbench__badges").exists(),
    ).toBe(false);
    expect(caseStrip.element.parentElement).toBe(operationColumn.element);
    expect(
      operationColumn
        .get(".review-operation-room__scroll")
        .find("[data-review-case-strip]")
        .exists(),
    ).toBe(false);
    const riskPanel = materialColumn.get("[data-review-risk-panel]");
    expect(riskPanel.get("[data-review-risk-count]").text()).toBe("2 项待核验");
    expect(riskPanel.findAll("[data-review-risk-item]")).toHaveLength(2);
    expect(
      riskPanel.findAll("[data-review-risk-item]").map((item) => item.text()),
    ).toEqual(["01高金额案件", "02签收信息不一致"]);
    expect(operationColumn.find("[data-review-risk-panel]").exists()).toBe(false);
    const reviewerOpinion = operationColumn.get("[data-reviewer-opinion]");
    expect(reviewerOpinion.get("strong").text()).toBe("尚未形成");
    expect(reviewerOpinion.find("[data-reviewer-opinion-status]").exists()).toBe(false);
    expect(operationColumn.find("[data-review-reason]").exists()).toBe(true);
    expect(operationColumn.findAll("[data-decision]")).toHaveLength(2);
    const submitDecision = operationColumn.get("[data-reviewer-opinion-submit]");
    expect(submitDecision.attributes("disabled")).toBeDefined();
    expect(submitDecision.element.previousElementSibling).toBe(
      operationColumn.get(".decision-actions--approval").element,
    );
    expect(operationColumn.find("[data-manual-escalation-choice]").exists()).toBe(false);
    expect(
      wrapper.get(".review-workbench__workspace").find("[data-review-decisions]").exists(),
    ).toBe(false);
    expect(wrapper.get("[data-room-message]").text()).toContain("2 个核心争点");
    expect(wrapper.text()).toContain("冻结审核包 v3");
    expect(wrapper.get("[data-packet-status]").text()).toBe("已冻结");
    expect(wrapper.get("[data-frozen-time]").text()).toBe("7月3日 12:00");
    expect(wrapper.text()).not.toContain("AI 裁决草案（非最终）");
    expect(wrapper.get("[data-case-summary]").text()).toContain("案件摘要");
    expect(wrapper.get("[data-claims-card]").text()).toContain("用户");
    expect(wrapper.get("[data-claims-card]").text()).toContain("未收到商品");
    expect(wrapper.get("[data-issues-card]").text()).toContain("签收人身份是否可信");
    expect(wrapper.get("[data-evidence-matrix]").text()).toContain("证据 1");
    expect(wrapper.find("[data-remedy-card]").exists()).toBe(false);
    expect(wrapper.get("[data-claims-card]").text()).not.toContain("{");
    expect(wrapper.findAll("[data-review-decisions]")).toHaveLength(1);
    expect(wrapper.findAll("[data-review-reason]")).toHaveLength(1);
    const readableMaterial = ["overview", "evidence", "draft", "risk"]
      .map((section) => materialColumn.get(`#review-panel-${section}`).text())
      .join(" ");
    const readableWorkspace = [
      chatColumn.text(),
      readableMaterial,
      operationColumn.text(),
    ].join(" ");
    expect(readableWorkspace).not.toMatch(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/);
  });

  it("formats repeated review lead-ins as a concise numbered briefing", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        risk_flags: [
          "需重点审查用户提交的检测报告",
          "需核实商家采用的测试条件",
        ],
      },
    });

    const briefing = wrapper.get("[data-room-message] p").text();
    expect(briefing).toContain("终审重点：");
    expect(briefing).toContain("1. 用户提交的检测报告");
    expect(briefing).toContain("2. 商家采用的测试条件");
    expect(briefing).not.toContain("需重点审查");
    expect(briefing).not.toContain("需核实");
  });

  it("renders long machine-prefixed risks as a readable review checklist", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        risk_flags: [
          "logistics_status_verification_critical: 需人工确认物流实际状态，核实用户标签收货的真实性。",
          "detection_report_verification: 需人工核验用户提供的第三方检测报告（EVIDENCE_52a7dfd93ad74ab6bf96947964c8dd7f）原件。",
        ],
      },
    });

    const riskPanel = wrapper.get("[data-review-risk-panel]");
    const riskItems = riskPanel.findAll("[data-review-risk-item]");
    expect(riskItems).toHaveLength(2);
    expect(riskPanel.text()).not.toContain("logistics_status_verification_critical");
    expect(riskPanel.text()).not.toContain("detection_report_verification");
    expect(riskPanel.text()).not.toContain("EVIDENCE_52a7dfd93ad74ab6bf96947964c8dd7f");
    expect(riskItems[0].attributes("data-risk-code")).toBe(
      "logistics_status_verification_critical",
    );
    expect(riskItems[0].text()).toContain("需人工确认物流实际状态");
    expect(riskItems[1].text()).toContain("证据材料");
  });

  it("preserves standards and product identifiers inside natural-language risks", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        risk_flags: [
          "需核验检测过程是否符合GB/T 18801-2022标准，并确认型号KJ500F-A01。",
        ],
      },
    });

    const riskPanel = wrapper.get("[data-review-risk-panel]");
    expect(riskPanel.text()).toContain("GB/T 18801-2022");
    expect(riskPanel.text()).toContain("KJ500F-A01");
    expect(riskPanel.text()).not.toContain("待人工确认/待人工确认");
  });

  it("claims an unassigned pending task before letting reviewer-local decide from a direct workbench route", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const start = vi.spyOn(reviewApi, "start").mockResolvedValue({
      id: "REVIEW_1",
      status: "IN_REVIEW",
      assigned_reviewer_id: "reviewer-local",
    });
    vi.spyOn(reviewApi, "list").mockImplementation((_actor, status) =>
      Promise.resolve(
        status === "PENDING"
          ? [{ id: "REVIEW_1", status: "PENDING", assigned_reviewer_id: "" }]
          : [],
      ),
    );
    vi.spyOn(reviewApi, "packet").mockResolvedValue(packet);
    vi.spyOn(reviewApi, "activeCopilotRuns").mockResolvedValue([]);
    const decideAction = vi.fn().mockResolvedValue({
      decision: "APPROVE",
      status: "APPROVED",
    });

    const { wrapper } = await mountView({ initialPacket: null, decideAction });
    await flushPromises();

    expect(start).toHaveBeenCalledTimes(1);
    expect(start).toHaveBeenCalledWith(actor, "REVIEW_1");
    expect(wrapper.findAll("[data-decision]")).toHaveLength(3);

    await wrapper.get("[data-review-reason]").setValue("证据与规则已完成核验");
    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "APPROVE",
      reason: "证据与规则已完成核验",
      approved_plan: null,
      confirmed: true,
    });
  });

  it("does not claim a task that is already assigned to reviewer-local", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const start = vi.spyOn(reviewApi, "start");
    vi.spyOn(reviewApi, "list").mockImplementation((_actor, status) =>
      Promise.resolve(
        status === "IN_REVIEW"
          ? [
              {
                id: "REVIEW_1",
                status: "IN_REVIEW",
                assigned_reviewer_id: "reviewer-local",
              },
            ]
          : [],
      ),
    );
    vi.spyOn(reviewApi, "packet").mockResolvedValue(packet);
    vi.spyOn(reviewApi, "activeCopilotRuns").mockResolvedValue([]);

    const { wrapper } = await mountView({ initialPacket: null });
    await flushPromises();

    expect(start).not.toHaveBeenCalled();
    expect(wrapper.findAll("[data-decision]")).toHaveLength(3);
  });

  it("maps uppercase business fields throughout the readable review workspace", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        case_summary: {
          title: "RETURN_REFUND",
          description: "当前路径为FULL_HEARING，申请RETURN_REFUND",
          route_type: "FULL_HEARING",
          risk_level: "HIGH",
        },
        claims: {
          party_positions: {
            user_claim: "用户申请RETURN_REFUND",
            merchant_claim: "商家建议MANUAL_REVIEW",
          },
          claim_resolution: {
            requested_resolution: "RETURN_REFUND",
          },
        },
        issues: [{ issue_id: "ISSUE_001", title: "是否需要MANUAL_REVIEW" }],
        draft: {
          recommended_decision: "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
          draft_text: "EVIDENCE_1当前为NEEDS_HUMAN_REVIEW",
        },
        remedy: {
          actions: [
            {
              action_type: "RESHIP",
              preconditions: ["CASE_NOT_CLOSED", "INVENTORY_AVAILABLE"],
              parameters: {
                source_recommendation: "RESHIP_BY_CONFIRMED_SETTLEMENT",
              },
            },
          ],
          notifications: ["NOTIFY_USER_AFTER_EXECUTION"],
        },
        risk_flags: ["HIGH_VALUE_REFUND", "SIGNATURE_MISMATCH"],
      },
    });

    const readableMaterial = ["overview", "evidence", "risk"]
      .map((section) => wrapper.get(`#review-panel-${section}`).text())
      .join(" ");
    const readableWorkspace = [
      wrapper.get("[data-review-chat-column]").text(),
      readableMaterial,
      wrapper.get("[data-review-operation-column]").text(),
    ].join(" ");

    expect(readableWorkspace).toContain("退货退款");
    expect(readableWorkspace).toContain("完整庭审");
    expect(readableWorkspace).toContain("高金额退款");
    expect(readableWorkspace).toContain("签收信息不一致");
    expect(readableWorkspace).not.toMatch(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/);
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("separates the complete adjudication draft from the key review panel", async () => {
    const { wrapper } = await mountView();

    const materialColumn = wrapper.get("[data-review-material-column]");
    expect(materialColumn.get("#review-tab-draft").text()).toBe("裁决草案");
    expect(materialColumn.get("#review-tab-risk").text()).toBe("重点复核");
    expect(materialColumn.get("#review-panel-draft").text()).toContain(
      "法官 V2 裁决草案",
    );
    expect(materialColumn.get("#review-panel-risk").text()).toContain("终审核验清单");
    expect(materialColumn.find("[data-remedy-card]").exists()).toBe(false);
    expect(materialColumn.find(".packet-cards__draft").exists()).toBe(false);
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("renders backend snake_case adjudication draft fields instead of waiting copy", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        draft: {
          recommended_decision: "RESHIP_IF_SIGNATURE_PROOF_MISSING",
          draft_text: "裁决评审已完成，系统形成补发方向的非最终裁决草案。",
          reviewer_attention: ["核验签收证明与库存条件"],
        },
      },
    });

    const aiOpinion = wrapper.get("[data-ai-opinion]");
    expect(aiOpinion.text()).toContain("签收凭证缺失时补发");
    expect(aiOpinion.text()).not.toContain("RESHIP_IF_SIGNATURE_PROOF_MISSING");
  });

  it("renders the current adjudication_draft.v3 decision_action contract", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        claims: {
          schema_version: "case_fact_matrix.v2",
          case_overview: {
            neutral_summary: "订单尚未发货，双方同意取消。",
            core_conflict: "订单是否满足未发货取消条件",
          },
          claims: {
            initiator_claim: {
              initiator_role: "USER",
              requested_resolution: "CANCEL_ORDER",
              position_summary: "用户要求取消尚未发货的订单。",
            },
            respondent_direct: {
              respondent_role: "MERCHANT",
              attitude: "AGREE",
              position_summary: "商家确认未发货并同意取消。",
            },
          },
          fact_rows: [
            {
              fact_id: "FACT_UNSHIPPED",
              fact_target: "订单发货状态",
              category: "LOGISTICS",
              materiality: "CORE",
              party_alignment: { status: "AGREED" },
              positions: {
                USER: { stance: "CONFIRM", position_summary: "订单尚未发货。" },
                MERCHANT: { stance: "CONFIRM", position_summary: "订单尚未发货。" },
              },
            },
          ],
        },
        evidence_matrix: {
          schema_version: "fact_evidence_matrix.v3",
          matrix_status: "FROZEN",
          fact_coverage: [
            {
              fact_id: "FACT_UNSHIPPED",
              evidence_ids: ["EVIDENCE_ORDER_STATUS"],
              coverage_status: "COVERED_BY_FROZEN_DOSSIER",
              note: "订单状态记录覆盖未发货事实。",
            },
          ],
          links: [
            {
              fact_id: "FACT_UNSHIPPED",
              evidence_id: "EVIDENCE_ORDER_STATUS",
              relation: "CONTENT_SUPPORTS",
              reason: "订单记录显示尚未发货。",
            },
          ],
        },
        draft: {
          schema_version: "adjudication_draft.v3",
          draft_id: "JUDGE_V2_CURRENT",
          content_hash: "draft-hash",
          decision_action: "CANCEL_ORDER",
          draft: {
            decision_action: "CANCEL_ORDER",
            fact_findings: [
              {
                fact_id: "FACT_UNSHIPPED",
                finding: "CONFIRMED",
                evidence_ids: ["EVIDENCE_ORDER_STATUS"],
                evidence_gap: null,
                confidence: 0.99,
              },
            ],
            rule_applications: [
              {
                rule_code: "UNSHIPPED_CANCEL",
                rule_version: 1,
                rule_name: "Unshipped order cancellation policy",
                fact_ids: ["FACT_UNSHIPPED"],
                applicable: true,
                conditions_met: [
                  "shipment_status: 订单尚未发货（FACT_UNSHIPPED）",
                  "maximum_risk_level: HIGH",
                ],
                conditions_unmet: [],
                rationale: "冻结事实和证据满足规则适用条件。",
                resulting_effect: "取消当前订单。",
              },
            ],
            decision_reasoning: "订单未发货事实已经冻结证据确认，因此适用取消规则。",
            remedy_orders: [
              {
                remedy_type: "CANCEL_ORDER",
                order_text: "取消当前订单。",
                fact_ids: ["FACT_UNSHIPPED"],
                conditions: [],
              },
            ],
            reviewer_attention: [],
          },
          review_responses: [
            {
              review_item_ref: "JURY_SINGLE_ACTION",
              review_source: "JURY_FINDING",
              disposition: "ACCEPTED",
              response: "已将执行结果收束为唯一的取消订单动作。",
              affected_fields: [
                "decision_action",
                "remedy_orders",
                "recommended_decision",
              ],
            },
          ],
          public_text: "建议取消订单。",
        },
        review_source_items: [
          {
            review_item_ref: "JURY_SINGLE_ACTION",
            review_source: "JURY_FINDING",
            review_item_text:
              "陪审认为执行结果应收束为唯一动作。事实矩阵中truth_status为NOT_EVALUATED，evidence_coverage_status为NOT_COVERED_BY_FROZEN_DOSSIER；[FACT_COMPLETENESS]需在remedy_orders中注明requires_human_review条件，并避免误判证据的Direct Probative Value。具体依据为POLICY_MERCHANT_REFUND_V1。",
            source_artifact_id: "JURY_REVIEW_CURRENT",
            source_content_hash: "jury-review-hash",
          },
        ],
        remedy: {
          id: "REMEDY_CURRENT",
          version: 1,
          actions: [],
          preconditions: [],
          notifications: [],
          decision_action: "CANCEL_ORDER",
        },
        risk_flags: [],
      },
    });

    const aiOpinion = wrapper.get("[data-ai-opinion]");
    expect(aiOpinion.text()).toContain("取消订单");
    expect(aiOpinion.text()).not.toContain("需人工复核");

    const issuesCard = wrapper.get("[data-issues-card]");
    expect(issuesCard.findAll("[data-judge-finding-tag]")).toHaveLength(1);
    const judgeFinding = issuesCard.get("[data-judge-finding-tag]");
    expect(judgeFinding.get(".issue-finding__meta").text()).toBe("法官认定可信度 99%");
    expect(judgeFinding.get(".issue-finding__text").text()).toBe("已确认");
    expect(issuesCard.get("[data-issue-heading]").find("[data-judge-finding-tag]").exists()).toBe(
      false,
    );
    expect(issuesCard.find(".issue-tag--category").exists()).toBe(false);
    expect(issuesCard.find(".issue-tag--materiality").exists()).toBe(false);
    expect(issuesCard.find(".issue-tag--alignment").exists()).toBe(false);

    const draftPanel = wrapper.get("#review-panel-draft");
    expect(draftPanel.get("[data-adjudication-decision]").text()).toContain(
      "取消当前订单；是否产生退款由后续订单结算处理",
    );
    expect(draftPanel.get("[data-adjudication-reasoning]").text()).toContain(
      "订单未发货事实已经冻结证据确认",
    );
    const adjudicationFindings = draftPanel.get("[data-adjudication-findings]");
    expect(adjudicationFindings.findAll("article")).toHaveLength(1);
    const adjudicationFinding = adjudicationFindings.get("article");
    expect(adjudicationFinding.get("[data-adjudication-finding-heading]").text()).toBe(
      "订单发货状态",
    );
    expect(adjudicationFinding.get(".adjudication-finding__meta").text()).toBe(
      "认定结论可信度 99%",
    );
    expect(adjudicationFinding.get("[data-adjudication-finding-result]").text()).toContain(
      "已确认",
    );
    expect(
      adjudicationFinding
        .get("[data-adjudication-finding-heading]")
        .find("[data-adjudication-finding-result]")
        .exists(),
    ).toBe(false);
    expect(draftPanel.get("[data-adjudication-rules]").text()).toContain(
      "未发货订单取消规则",
    );
    expect(draftPanel.get("[data-adjudication-rules]").text()).toContain(
      "订单发货状态",
    );
    const adjudicationRuleHeader = draftPanel.get("[data-adjudication-rules] article > header");
    expect(adjudicationRuleHeader.get("[data-adjudication-rule-meta] [data-applicable]").text()).toBe(
      "适用",
    );
    expect(adjudicationRuleHeader.element.lastElementChild).toBe(
      adjudicationRuleHeader.get("[data-adjudication-rule-meta]").element,
    );
    expect(draftPanel.get("[data-adjudication-rules]").text()).not.toContain(
      "Unshipped order cancellation policy",
    );
    expect(draftPanel.get("[data-adjudication-rules]").text()).not.toContain(
      "shipment_status",
    );
    expect(draftPanel.get("[data-adjudication-rules]").text()).toContain(
      "最高风险等级: 高风险",
    );
    expect(draftPanel.get("[data-adjudication-rules]").text()).not.toContain(
      "maximum_risk_level",
    );
    expect(draftPanel.get("[data-adjudication-rules]").text()).not.toContain(
      "FACT_UNSHIPPED",
    );
    expect(draftPanel.get("[data-adjudication-remedies]").text()).toContain(
      "取消当前订单",
    );
    expect(draftPanel.get("[data-adjudication-review-responses]").text()).toContain(
      "已将执行结果收束为唯一的取消订单动作",
    );
    const reviewResponse = draftPanel.get('[data-review-source="JURY_FINDING"]');
    expect(reviewResponse.get("[data-review-source-opinion]").text()).toContain(
      "陪审认为执行结果应收束为唯一动作",
    );
    const juryOpinion = reviewResponse.get("[data-review-source-opinion]").text();
    expect(juryOpinion).toContain("事实认定状态为尚未认定");
    expect(juryOpinion).toContain("证据覆盖状态为冻结证据未覆盖");
    expect(juryOpinion).toContain("[事实完整性]");
    expect(juryOpinion).toContain("处理事项中注明需人工复核条件");
    expect(juryOpinion).toContain("直接证明力");
    expect(juryOpinion).toContain("具体依据为商家同意退款规则");
    expect(juryOpinion).not.toMatch(
      /truth_status|evidence_coverage_status|NOT_EVALUATED|FACT_COMPLETENESS|remedy_orders|requires_human_review|Direct Probative Value|POLICY_MERCHANT_REFUND_V1/,
    );
    expect(reviewResponse.text()).toContain("总体建议");
    expect(reviewResponse.text()).not.toContain("recommended_decision");
    expect(reviewResponse.get("[data-judge-review-response]").text()).toContain(
      "已将执行结果收束为唯一的取消订单动作",
    );
    expect(
      reviewResponse.get("[data-review-source-opinion]").element.compareDocumentPosition(
        reviewResponse.get("[data-judge-review-response]").element,
      ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(draftPanel.text()).not.toContain("CANCEL_ORDER");
    const evidencePanel = wrapper.get("#review-panel-evidence");
    expect(evidencePanel.text()).toContain("已有冻结证据覆盖");
    expect(evidencePanel.get("[data-evidence-material-reference]").text()).toBe(
      "证据材料 01",
    );
    expect(evidencePanel.find(".evidence-binding-list li > div").exists()).toBe(false);
    expect(draftPanel.get("[data-evidence-material-reference]").text()).toBe(
      "证据材料 01",
    );
    expect(wrapper.text()).not.toContain("recommended_decision");
  });

  it("unwraps and renders the frozen hearing_flow.v2 adjudication artifact", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        evidence_matrix: [
          {
            assessment_type: "EVIDENCE",
            evidence_id: "EVIDENCE_1",
            fact_ids: ["FACT_RECIPIENT"],
            assessment: "现有证据不能单独确认签收主体。",
            weight: "MEDIUM",
            confidence: 0.72,
            limitations: ["缺少签收人身份信息"],
          },
          {
            assessment_type: "EVIDENCE_GAP",
            evidence_id: null,
            fact_ids: ["FACT_RECIPIENT"],
            assessment: "仍缺少签收主体身份材料。",
            weight: "NONE",
            confidence: 0.4,
            limitations: ["需人工判断举证不能的不利后果"],
          },
        ],
        draft: {
          schema_version: "adjudication_draft.v2",
          draft_id: "JUDGE_V2_1",
          trial_dossier_id: "TRIAL_DOSSIER_1",
          trial_dossier_hash: "dossier-hash",
          proposal_id: "JUDGE_V1_1",
          proposal_content_hash: "proposal-hash",
          report_id: "JURY_REVIEW_1",
          report_content_hash: "report-hash",
          content_hash: "draft-hash",
          public_text: "建议转人工核验签收主体后再决定退款。",
          draft: {
            recommended_decision: "MANUAL_REVIEW",
            confidence: 0.72,
            draft_text: "建议转人工核验签收主体后再决定退款。",
            fact_findings: [
              {
                fact_id: "FACT_RECIPIENT",
                finding: "签收主体仍需人工核对。",
                evidence_ids: ["EVIDENCE_1"],
                evidence_gap: "缺少签收主体身份材料。",
                confidence: 0.72,
              },
            ],
            evidence_assessment: [
              {
                assessment_type: "EVIDENCE",
                evidence_id: "EVIDENCE_1",
                fact_ids: ["FACT_RECIPIENT"],
                assessment: "现有证据不能单独确认签收主体。",
                weight: "MEDIUM",
                confidence: 0.72,
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
                rationale: "履约方应提供可核验交付记录。",
                limitations: ["签收主体仍待核实"],
              },
            ],
            reviewer_attention: ["核对签收主体无法查明时的处理路径。"],
            draft_status: "PENDING_HUMAN_REVIEW",
            requires_human_review: true,
            is_final_decision: false,
          },
        },
      },
    });

    const aiOpinion = wrapper.get("[data-ai-opinion]");
    expect(aiOpinion.text()).toContain("转人工复核");
    expect(aiOpinion.text()).not.toContain("MANUAL_REVIEW");

    const evidencePanel = wrapper.get("#review-panel-evidence");
    expect(evidencePanel.text()).toContain("证据 1");
    expect(evidencePanel.text()).toContain("关联事实：事实接收方");
    expect(evidencePanel.text()).toContain("存在证据缺口");
    const draftPanel = wrapper.get("#review-panel-draft");
    expect(draftPanel.text()).toContain("履约方应提供可核验交付记录");
    expect(draftPanel.text()).toContain("签收争议举证规则");
    expect(draftPanel.text()).toContain("签收主体仍需人工核对");
    expect(wrapper.text()).not.toContain("adjudication_draft.v2");
    expect(wrapper.text()).not.toContain("PENDING_HUMAN_REVIEW");
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("requires a reason and explicit second confirmation before a final decision", async () => {
    const decideAction = vi.fn().mockResolvedValue({
      decision: "APPROVE",
      status: "APPROVED",
    });
    const { wrapper, router } = await mountView({ decideAction });

    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(false);
    expect(wrapper.get("[data-reviewer-opinion]").get("strong").text()).toBe(
      "批准 AI 建议",
    );
    expect(wrapper.get('[data-decision="APPROVE"]').attributes("aria-pressed")).toBe(
      "true",
    );
    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    expect(wrapper.text()).toContain("请先填写审核理由");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(false);
    await wrapper.get("[data-review-reason]").setValue("证据链与规则适用均已核验");
    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(true);
    expect(wrapper.get(".decision-confirm__outcome").text()).toContain(
      "本次提交结果批准 AI 建议",
    );
    expect(wrapper.get("[data-reviewer-opinion]").get("strong").text()).toBe(
      "批准 AI 建议",
    );
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "APPROVE",
      reason: "证据链与规则适用均已核验",
      approved_plan: null,
      confirmed: true,
    });
    expect(wrapper.text()).toContain("终审决定已提交");
    expect(wrapper.find("[data-reviewer-opinion-status]").exists()).toBe(false);
    expect(router.currentRoute.value.fullPath).toBe(
      "/disputes/CASE_REVIEW_1/outcome",
    );
  });

  it("stays on the review page when the backend rejects the final decision", async () => {
    const decideAction = vi.fn().mockRejectedValue(new Error("审核状态已变化"));
    const { wrapper, router } = await mountView({ decideAction });

    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    await wrapper.get("[data-review-reason]").setValue("已完成最终核验");
    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledOnce();
    expect(router.currentRoute.value.fullPath).toBe("/reviews/REVIEW_1");
    const submitErrorDialog = wrapper.get("[data-agent-stream-error-dialog]");
    expect(submitErrorDialog.text()).toContain("终审决定提交失败");
    expect(submitErrorDialog.text()).toContain("审核状态已变化");
    await submitErrorDialog.get("[data-dismiss-agent-stream-error]").trigger("click");
    expect(wrapper.find("[data-agent-stream-error-dialog]").exists()).toBe(false);
  });

  it("selects a bounded decision action before modify and approve", async () => {
    const decideAction = vi.fn().mockResolvedValue({
      decision: "MODIFY_AND_APPROVE",
      status: "APPROVED",
    });
    const { wrapper } = await mountView({ decideAction });

    await wrapper.get("[data-review-reason]").setValue("金额需要按责任比例调整");
    await wrapper.get('[data-decision="MODIFY_AND_APPROVE"]').trigger("click");
    const selector = wrapper.get("[data-decision-action-selector]");
    const choices = selector.findAll("[data-decision-action-choice]");
    expect(choices.map((choice) => choice.attributes("data-decision-action-choice"))).toEqual([
      "CANCEL_ORDER",
      "RETURN_AND_REFUND",
      "REFUND_ONLY",
      "RESHIP",
      "REPLACE",
      "REPAIR",
      "COMPENSATE",
      "CONTINUE_FULFILLMENT",
      "REJECT_CLAIM",
    ]);
    expect(wrapper.find("[data-plan-editor]").exists()).toBe(false);

    await selector.get('[data-decision-action-choice="REFUND_ONLY"]').trigger("click");
    expect(wrapper.find("[data-decision-action-selector]").exists()).toBe(false);
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(false);
    const reviewerOpinion = wrapper.get("[data-reviewer-opinion]");
    expect(reviewerOpinion.get("strong").text()).toBe("仅退款");
    expect(reviewerOpinion.text()).toContain("用户无需退货，直接获得全额或部分退款");
    expect(reviewerOpinion.find("[data-reviewer-opinion-submit]").exists()).toBe(false);

    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    const confirmation = wrapper.get(".decision-confirm");
    expect(confirmation.get(".decision-confirm__outcome").text()).toContain(
      "本次提交结果仅退款",
    );
    expect(confirmation.get(".decision-confirm__outcome").text()).toContain(
      "修改执行动作",
    );
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "MODIFY_AND_APPROVE",
      reason: "金额需要按责任比例调整",
      approved_plan: {
        ...packet.remedy,
        decision_action: "REFUND_ONLY",
      },
      confirmed: true,
    });
    expect(wrapper.find("[data-reviewer-opinion-status]").exists()).toBe(false);
  });

  it("moves manual escalation into the action selector as a distinct reviewer option", async () => {
    const decideAction = vi.fn().mockResolvedValue({
      decision: "ESCALATE_MANUAL",
      status: "ESCALATED",
    });
    const { wrapper } = await mountView({ decideAction });
    const decisions = wrapper
      .findAll("[data-decision]")
      .map((button) => button.attributes("data-decision"));

    expect(decisions).toEqual(["APPROVE", "MODIFY_AND_APPROVE"]);
    expect(wrapper.find("[data-manual-escalation-choice]").exists()).toBe(false);

    await wrapper.get('[data-decision="MODIFY_AND_APPROVE"]').trigger("click");
    const selector = wrapper.get("[data-decision-action-selector]");
    const manualEscalation = selector.get("[data-manual-escalation-choice]");
    expect(manualEscalation.text()).toContain("升级人工接管");
    expect(manualEscalation.text()).toContain("人工审核状态");
    expect(manualEscalation.classes()).not.toContain("is-selected");
    await manualEscalation.trigger("click");

    expect(wrapper.find("[data-decision-action-selector]").exists()).toBe(false);
    expect(wrapper.get("[data-reviewer-opinion]").get("strong").text()).toBe(
      "升级人工接管",
    );
    await wrapper.get("[data-review-reason]").setValue("自动执行条件不足，转人工处理");
    await wrapper.get("[data-reviewer-opinion-submit]").trigger("click");
    expect(wrapper.get(".decision-confirm__outcome").text()).toContain("升级人工接管");
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "ESCALATE_MANUAL",
      reason: "自动执行条件不足，转人工处理",
      approved_plan: null,
      confirmed: true,
    });
    expect(wrapper.text()).not.toContain("退回补证");
    expect(wrapper.text()).not.toContain("驳回草案");
  });

  it("renders nested intake and evidence fields without exposing raw schema keys", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        claims: {
          party_positions: {
            user_claim: "用户主张商品存在安全隐患",
            merchant_claim: "商家主张首次加热属于正常现象",
            platform_observation: "双方对故障性质存在分歧",
          },
          claim_resolution: {
            requested_resolution: "REFUND",
            requested_amount: 699,
            requested_items: "空气炸锅 1 台",
          },
          dispute_core_state: {
            disputed_facts: ["首次通电冒烟是否属于质量缺陷"],
          },
        },
        issues: [],
        draft: {
          ...packet.draft,
          draft_text:
            "EVIDENCE_9 当前为UNVERIFIED，EVIDENCE_10 当前为NEEDS_HUMAN_REVIEW",
          reviewer_attention: ["复核EVIDENCE_10的NEEDS_HUMAN_REVIEW状态"],
          policy_application: [
            {
              title: "EVIDENCE_9 的证明范围",
              application: "EVIDENCE_10 尚不能形成有效反证",
            },
          ],
          fact_findings: [
            { finding: "EVIDENCE_9 与 EVIDENCE_10 均需要人工复核" },
          ],
        },
        evidence_items: [
          {
            evidence_id: "EVIDENCE_9",
            original_filename: "首次通电冒烟现场照片.jpg",
          },
          {
            evidence_id: "EVIDENCE_10",
            original_filename: "商家质检报告.pdf",
          },
        ],
        evidence_matrix: [
          {
            issue_id: "ISSUE_001",
            confidence: 0.3,
            supported_by: ["EVIDENCE_9"],
            contradicted_by: ["EVIDENCE_10"],
            missing_evidence: true,
            neutral_analysis:
              "EVIDENCE_9 与 EVIDENCE_10 的真实性标记为UNVERIFIED或NEEDS_HUMAN_REVIEW，均不足以确认故障性质",
          },
        ],
      },
    });

    expect(wrapper.get("[data-claims-card]").text()).toContain("用户主张商品存在安全隐患");
    expect(wrapper.get("[data-claims-card]").text()).toContain("商家主张首次加热属于正常现象");
    expect(wrapper.get("[data-issues-card]").text()).toContain("首次通电冒烟是否属于质量缺陷");
    const evidencePanel = wrapper.get("#review-panel-evidence");
    expect(evidencePanel.text()).toContain("首次通电冒…");
    expect(evidencePanel.text()).toContain("商家质检报…");
    expect(evidencePanel.text()).not.toContain("EVIDENCE_9");
    expect(evidencePanel.text()).not.toContain("EVIDENCE_10");
    expect(wrapper.get('[data-evidence-reference][title*="EVIDENCE_9"]')).toBeTruthy();
    expect(wrapper.get("[data-evidence-matrix]").text()).toContain(
      "首次通电冒… 与 商家质检报… 的真实性标记为待核验或待人工复核，均不足以确认故障性质",
    );
    expect(evidencePanel.find('[data-status="UNVERIFIED"]').text()).toBe("待核验");
    expect(evidencePanel.find('[data-status="NEEDS_HUMAN_REVIEW"]').text()).toBe(
      "待人工复核",
    );
    expect(evidencePanel.text()).not.toContain("UNVERIFIED");
    expect(evidencePanel.text()).not.toContain("NEEDS_HUMAN_REVIEW");
    const draftPanel = wrapper.get("#review-panel-draft");
    expect(draftPanel.text()).toContain("首次通电冒… 的证明范围");
    expect(draftPanel.text()).toContain("商家质检报… 尚不能形成有效反证");
    expect(wrapper.get("#review-panel-risk").text()).toContain("关键证据不足");
    expect(wrapper.text()).toContain("¥699.00");
    expect(wrapper.text()).not.toContain("party_positions");
    expect(wrapper.text()).not.toContain("claim_resolution");
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("never exposes final decision controls before the packet is frozen", async () => {
    const { wrapper } = await mountView({
      initialPacket: { ...packet, status: "PREPARING" },
    });

    expect(wrapper.find("[data-review-decisions]").exists()).toBe(false);
    expect(wrapper.text()).toContain("冻结审核包生成前仅可只读旁观");
  });

  it.each([
    {
      name: "non reviewer",
      overrides: { viewerRole: "MERCHANT" },
      message: "当前角色只能查看",
    },
    {
      name: "another assigned reviewer",
      overrides: {
        initialPacket: { ...packet, assigned_reviewer_id: "reviewer-else" },
      },
      message: "另一名平台审核员",
    },
    {
      name: "expired packet",
      overrides: {
        initialPacket: { ...packet, expires_at: "2026-07-03T11:59:59+08:00" },
      },
      message: "超过有效期",
    },
    {
      name: "closed task",
      overrides: {
        initialPacket: { ...packet, review_task_status: "APPROVED" },
      },
      message: "离开可办理队列",
    },
  ])("keeps decisions read-only for $name", async ({ overrides, message }) => {
    const { wrapper } = await mountView(overrides);

    expect(wrapper.find("[data-review-decisions]").exists()).toBe(false);
    expect(wrapper.text()).toContain(message);
  });

  it("accepts the additive structured packet and task projection", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        review_packet: {
          ...packet,
          packetId: "PACKET_STRUCTURED",
          packet_version: undefined,
          packetVersion: 5,
          contentHash: "a".repeat(64),
          status: "FROZEN",
        },
        reviewTaskStatus: "IN_REVIEW",
      },
    });

    expect(wrapper.get("[data-packet-status]").text()).toBe("已冻结");
    expect(wrapper.text()).toContain("冻结审核包 v5");
    expect(wrapper.findAll("[data-decision]")).toHaveLength(3);
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("streams the reviewer copilot answer through the shared AgentRun component", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    vi.spyOn(reviewApi, "queryCopilot").mockResolvedValue({
      run_id: "AGENT_RUN_REVIEW_1",
      operation: "REVIEW",
      stream_url: "/api/agent-runs/AGENT_RUN_REVIEW_1/events",
    });
    const encoder = new TextEncoder();
    const eventStream = [
      'id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_REVIEW_1","sequence":0,"type":"start"}\n\n',
      'id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_REVIEW_1","sequence":1,"type":"visible_delta","field":"answer","delta":"重点复核"}\n\n',
      'id: 2\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_REVIEW_1","sequence":2,"type":"visible_delta","field":"answer","delta":"签收人身份。"}\n\n',
      'id: 3\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"AGENT_RUN_REVIEW_1","sequence":3,"type":"final","response":{"answer":"重点复核签收人身份。"}}\n\n',
    ].join("");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode(eventStream));
          controller.close();
        },
      }),
    }));
    const { wrapper } = await mountView();

    await wrapper.get(".review-explain-room textarea").setValue("最需要复核什么？");
    await wrapper.get(".review-explain-room [data-send-message]").trigger("submit");
    await flushPromises();

    expect(reviewApi.queryCopilot).toHaveBeenCalledWith(
      actor,
      "REVIEW_1",
      "最需要复核什么？",
    );
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain("重点复核签收人身份。");
    });
    expect(wrapper.text()).not.toContain("reasoning_content");
  });
});
