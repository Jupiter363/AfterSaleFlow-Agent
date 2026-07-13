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
  remedy: { actions: [{ type: "REFUND", amount: 299 }] },
  risk_flags: ["HIGH_VALUE", "SIGNATURE_MISMATCH"],
  status: "FROZEN",
};

// 业务位置：【前端审核工作台】mountView：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
async function mountView(overrides = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/reviews/:reviewId", component: { template: "<div />" } }],
  });
  await router.push("/reviews/REVIEW_1");
  await router.isReady();
  const decideAction = overrides.decideAction || vi.fn();
  const wrapper = mount(ReviewWorkbenchView, {
    props: {
      initialPacket: packet,
      viewerRole: "PLATFORM_REVIEWER",
      decideAction,
      serverNow: "2026-07-03T12:00:00+08:00",
      ...overrides,
    },
    global: { plugins: [router] },
  });
  return { wrapper, decideAction };
}

// 业务位置：【前端审核工作台】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
describe("ReviewWorkbenchView", () => {
  afterEach(() => {
    actor.id = "user-local";
    actor.role = "USER";
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("shows the frozen packet, review copilot and human-only controls", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("审核解释官");
    expect(wrapper.text()).toContain("ReviewPacket v3");
    expect(wrapper.get("[data-packet-status]").text()).toBe("已冻结");
    expect(wrapper.get("[data-frozen-time]").text()).toBe("7月3日 12:00");
    expect(wrapper.text()).toContain("核实代签关系");
    expect(wrapper.text()).toContain("AI 裁决草案（非最终）");
    expect(wrapper.get("[data-claims-card]").text()).toContain("用户");
    expect(wrapper.get("[data-claims-card]").text()).toContain("未收到商品");
    expect(wrapper.get("[data-issues-card]").text()).toContain("签收人身份是否可信");
    expect(wrapper.get("[data-evidence-matrix]").text()).toContain("EVIDENCE_1");
    expect(wrapper.get("[data-remedy-card]").text()).toContain("REFUND");
    expect(wrapper.get("[data-claims-card]").text()).not.toContain("{");
    expect(wrapper.find("[data-review-decisions]").exists()).toBe(true);
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
    expect(remedyCard.text()).toContain("RESHIP");
    expect(remedyCard.text()).toContain("INVENTORY_AVAILABLE");
    expect(remedyCard.text()).toContain("RESHIP_BY_CONFIRMED_SETTLEMENT");
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
          draft_text: "最终轮次已结束，系统形成补发方向的非最终裁决草案。",
          reviewer_attention: ["核验签收证明与库存条件"],
        },
      },
    });

    const draftCard = wrapper.get(".packet-cards__draft");
    expect(draftCard.text()).toContain("RESHIP_IF_SIGNATURE_PROOF_MISSING");
    expect(draftCard.text()).toContain("最终轮次已结束");
    expect(draftCard.text()).not.toContain("等待草案");
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

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("never exposes final decision controls before the packet is frozen", async () => {
    const { wrapper } = await mountView({
      initialPacket: { ...packet, status: "PREPARING" },
    });

    expect(wrapper.find("[data-review-decisions]").exists()).toBe(false);
    expect(wrapper.text()).toContain("ReviewPacket 冻结前仅可只读旁观");
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

    await wrapper.get("[data-review-copilot-input]").setValue("最需要复核什么？");
    await wrapper.get(".review-copilot__composer").trigger("submit");
    await flushPromises();

    expect(reviewApi.queryCopilot).toHaveBeenCalledWith(
      actor,
      "REVIEW_1",
      "最需要复核什么？",
    );
    expect(wrapper.text()).toContain("重点复核签收人身份。");
    expect(wrapper.text()).not.toContain("reasoning_content");
  });
});
