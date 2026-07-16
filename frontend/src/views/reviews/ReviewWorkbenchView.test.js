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
};

// 业务位置：【前端审核工作台】mountView：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function mountView(overrides = {}) {
  const { historyMode: openAsHistory = false, ...viewOverrides } = overrides;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/reviews", component: { template: "<div />" } },
      { path: "/reviews/:reviewId", component: { template: "<div />" } },
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
    expect(wrapper.text()).toContain("所有批准、修改和驳回操作均已锁定");
    expect(decideAction).not.toHaveBeenCalled();
  });

  it("clears an open decision confirmation when the active view becomes historical", async () => {
    const { wrapper, router, decideAction } = await mountView();
    await wrapper.get("[data-review-reason]").setValue("已完成证据与规则复核");
    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
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
    expect(chatColumn.find("[data-review-reason]").exists()).toBe(false);
    expect(materialColumn.findAll('[role="tab"]')).toHaveLength(3);
    expect(materialColumn.find("[data-review-reason]").exists()).toBe(false);
    expect(operationColumn.find(".review-case-strip").exists()).toBe(true);
    expect(operationColumn.find(".review-risk-strip").exists()).toBe(true);
    expect(operationColumn.find("[data-review-reason]").exists()).toBe(true);
    expect(operationColumn.findAll("[data-decision]")).toHaveLength(5);
    expect(
      wrapper.get(".review-workbench__workspace").find("[data-review-decisions]").exists(),
    ).toBe(false);
    expect(wrapper.get("[data-room-message]").text()).toContain("2 个核心争点");
    expect(wrapper.text()).toContain("冻结审核包 v3");
    expect(wrapper.get("[data-packet-status]").text()).toBe("已冻结");
    expect(wrapper.get("[data-frozen-time]").text()).toBe("7月3日 12:00");
    expect(wrapper.text()).toContain("核实代签关系");
    expect(wrapper.text()).toContain("AI 裁决草案（非最终）");
    expect(wrapper.get("[data-case-summary]").text()).toContain("案件摘要");
    expect(wrapper.get("[data-claims-card]").text()).toContain("用户");
    expect(wrapper.get("[data-claims-card]").text()).toContain("未收到商品");
    expect(wrapper.get("[data-issues-card]").text()).toContain("签收人身份是否可信");
    expect(wrapper.get("[data-evidence-matrix]").text()).toContain("证据 1");
    expect(wrapper.get("[data-remedy-card]").text()).toContain("原路退款");
    expect(wrapper.get("[data-remedy-card]").text()).not.toContain("REFUND");
    expect(wrapper.get("[data-claims-card]").text()).not.toContain("{");
    expect(wrapper.findAll("[data-review-decisions]")).toHaveLength(1);
    expect(wrapper.findAll("[data-review-reason]")).toHaveLength(1);
    const readableWorkspace = [chatColumn, materialColumn, operationColumn]
      .map((column) => column.text())
      .join(" ");
    expect(readableWorkspace).not.toMatch(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/);
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

    const readableWorkspace = [
      wrapper.get("[data-review-chat-column]"),
      wrapper.get("[data-review-material-column]"),
      wrapper.get("[data-review-operation-column]"),
    ]
      .map((column) => column.text())
      .join(" ");

    expect(readableWorkspace).toContain("退货退款");
    expect(readableWorkspace).toContain("完整庭审");
    expect(readableWorkspace).toContain("转人工复核");
    expect(readableWorkspace).toContain("核验签收凭证后补发或退款");
    expect(readableWorkspace).toContain("执行后通知用户");
    expect(readableWorkspace).not.toMatch(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/);
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("renders backend snake_case remedy actions as concrete execution work", async () => {
    const { wrapper } = await mountView({
      initialPacket: {
        ...packet,
        remedy: {
          actions: [
            {
              action_type: "RESHIP",
              preconditions: [
                "CASE_NOT_CLOSED",
                "INVENTORY_AVAILABLE",
              ],
              parameters: {
                source_recommendation: "RESHIP_BY_CONFIRMED_SETTLEMENT",
              },
            },
          ],
        },
      },
    });

    const remedyCard = wrapper.get("[data-remedy-card]");
    expect(remedyCard.text()).toContain("重新发货");
    expect(remedyCard.text()).toContain("库存可用");
    expect(remedyCard.text()).toContain("按已确认方案补发");
    expect(remedyCard.text()).not.toContain("RESHIP");
    expect(remedyCard.text()).not.toContain("INVENTORY_AVAILABLE");
    expect(remedyCard.text()).not.toContain("执行动作 1");
    expect(remedyCard.text()).not.toContain("未提供");
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

    const draftCard = wrapper.get(".packet-cards__draft");
    expect(draftCard.text()).toContain("签收凭证缺失时补发");
    expect(draftCard.text()).not.toContain("RESHIP_IF_SIGNATURE_PROOF_MISSING");
    expect(draftCard.text()).toContain("裁决评审已完成");
    expect(draftCard.text()).not.toContain("等待草案");
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

    const draftCard = wrapper.get(".packet-cards__draft");
    expect(draftCard.text()).toContain("转人工复核");
    expect(draftCard.text()).not.toContain("MANUAL_REVIEW");
    expect(draftCard.text()).toContain("建议转人工核验签收主体后再决定退款");
    expect(draftCard.text()).toContain("置信度 72%");
    expect(draftCard.text()).toContain("核对签收主体无法查明时的处理路径");
    expect(draftCard.text()).not.toContain("等待草案");

    const evidencePanel = wrapper.get("#review-panel-evidence");
    expect(evidencePanel.text()).toContain("履约方应提供可核验交付记录");
    expect(evidencePanel.text()).toContain("签收争议举证规则");
    expect(evidencePanel.text()).toContain("证据 1");
    expect(evidencePanel.text()).toContain("关联事实：事实接收方");
    expect(evidencePanel.text()).toContain("存在证据缺口");
    expect(evidencePanel.text()).toContain("签收主体仍需人工核对");
    expect(wrapper.text()).not.toContain("adjudication_draft.v2");
    expect(wrapper.text()).not.toContain("PENDING_HUMAN_REVIEW");
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("requires a reason and explicit second confirmation before a final decision", async () => {
    const decideAction = vi.fn().mockResolvedValue({
      decision: "APPROVE",
      status: "APPROVED",
    });
    const { wrapper } = await mountView({ decideAction });

    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    expect(wrapper.text()).toContain("请先填写审核理由");
    await wrapper.get("[data-review-reason]").setValue("证据链与规则适用均已核验");
    await wrapper.get('[data-decision="APPROVE"]').trigger("click");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(true);
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "APPROVE",
      reason: "证据链与规则适用均已核验",
      approved_plan: null,
    });
    expect(wrapper.text()).toContain("终审决定已提交");
  });

  it("requires a real execution-plan change before modify and approve", async () => {
    const decideAction = vi.fn().mockResolvedValue({
      decision: "MODIFY_AND_APPROVE",
      status: "APPROVED",
    });
    const { wrapper } = await mountView({ decideAction });

    await wrapper.get("[data-review-reason]").setValue("金额需要按责任比例调整");
    await wrapper.get('[data-decision="MODIFY_AND_APPROVE"]').trigger("click");
    expect(wrapper.find("[data-plan-editor]").exists()).toBe(true);

    await wrapper.get("[data-modified-plan-confirm]").trigger("click");
    expect(wrapper.text()).toContain("执行方案尚未修改");
    expect(wrapper.find("[data-decision-confirm]").exists()).toBe(false);

    const changedPlan = {
      ...packet.remedy,
      actions: [{ ...packet.remedy.actions[0], amount: 199 }],
    };
    await wrapper.get("[data-modified-plan]").setValue(JSON.stringify(changedPlan));
    await wrapper.get("[data-modified-plan-confirm]").trigger("click");
    await wrapper.get("[data-decision-confirm]").trigger("click");
    await flushPromises();

    expect(decideAction).toHaveBeenCalledWith({
      decision: "MODIFY_AND_APPROVE",
      reason: "金额需要按责任比例调整",
      approved_plan: changedPlan,
    });
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
    expect(evidencePanel.text()).toContain("首次通电冒… 的证明范围");
    expect(evidencePanel.text()).toContain("商家质检报… 尚不能形成有效反证");
    const draftPanel = wrapper.get("#review-panel-draft");
    expect(draftPanel.text()).toContain("首次通电冒… 当前为待核验");
    expect(draftPanel.text()).toContain("商家质检报… 当前为待人工复核");
    expect(draftPanel.find('[data-status="UNVERIFIED"]').text()).toBe("待核验");
    expect(draftPanel.find('[data-status="NEEDS_HUMAN_REVIEW"]').text()).toBe(
      "待人工复核",
    );
    expect(draftPanel.text()).not.toContain("UNVERIFIED");
    expect(draftPanel.text()).not.toContain("NEEDS_HUMAN_REVIEW");
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
