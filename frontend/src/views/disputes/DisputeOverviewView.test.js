// 文件作用：自动化测试文件，验证 DisputeOverviewView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { flushPromises, mount } from "@vue/test-utils";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { actor } from "../../state/actor";
import { disputeApi } from "../../api/disputes";
import { disputeStore } from "../../stores/dispute";
import DisputeOverviewView from "./DisputeOverviewView.vue";

const longCaseId = "CASE_EXT_002_LONG_IDENTIFIER_FOR_LAYOUT_TESTING";
const longOrderId = "ORDER_EXT_002_LONG_IDENTIFIER_FOR_LAYOUT_TESTING";
const formalExternalCaseId = "CASE_REAL_OMS_003";

const cases = [
  {
    id: "CASE_EXT_001",
    order_id: "ORDER-001",
    source_type: "EXTERNAL_IMPORT",
    source_system: "TEMPLATE_SIMULATED_OMS",
    external_case_reference: "TPL-001",
    dispute_type: "SIGNED_NOT_RECEIVED",
    case_status: "EVIDENCE_OPEN",
    current_room: "EVIDENCE",
    deadline_at: "2026-07-03T12:00:00Z",
    risk_level: "HIGH",
    pending_action: "SUBMIT_EVIDENCE",
    title: "签收未收到",
  },
  {
    id: longCaseId,
    order_id: longOrderId,
    source_type: "INTAKE_CREATED",
    dispute_type: "DAMAGED_GOODS",
    case_status: "HEARING_OPEN",
    current_room: "HEARING",
    deadline_at: "2026-07-03T13:00:00Z",
    risk_level: "MEDIUM",
    pending_action: "PARTICIPATE_HEARING",
    title: "到货破损",
  },
  {
    id: formalExternalCaseId,
    order_id: "ORDER-REAL-003",
    source_type: "EXTERNAL_IMPORT",
    source_system: "PRODUCTION_OMS",
    dispute_type: "QUALITY_DISPUTE",
    case_status: "INTAKE_PENDING",
    current_room: "INTAKE",
    risk_level: "LOW",
    pending_action: "COMPLETE_INTAKE",
    title: "正式外部订单争议",
  },
];

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

// 业务位置：【前端案件页面】mountOverview：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function mountOverview(
  createAction = null,
  simulateExternalImportAction = null,
  deleteSimulatedCaseAction = null,
  initialCases = cases,
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes", component: { template: "<div />" } },
      {
        path: "/disputes/:caseId/:room",
        component: { template: "<div />" },
      },
    ],
  });
  await router.push("/disputes");
  await router.isReady();
  const wrapper = mount(DisputeOverviewView, {
    props: {
      initialCases,
      serverNow: "2026-07-03T10:00:00Z",
      createAction,
      simulateExternalImportAction,
      deleteSimulatedCaseAction,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router };
}

// 业务位置：【前端案件页面】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
describe("DisputeOverviewView", () => {
  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("reconciles a reviewer deletion from the server into another actor's open overview", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    vi.spyOn(disputeApi, "list").mockResolvedValue({ items: cases });
    disputeStore.list.data = [];
    disputeStore.list.updatedAt = null;
    const { wrapper } = await mountOverview(null, null, null, []);
    await flushPromises();
    expect(wrapper.find('[data-case-id="CASE_EXT_001"]').exists()).toBe(true);

    disputeStore.list.data = cases.filter((item) => item.id !== "CASE_EXT_001");
    disputeStore.list.updatedAt = "2026-07-11T18:00:01.000Z";
    await flushPromises();

    expect(wrapper.find('[data-case-id="CASE_EXT_001"]').exists()).toBe(false);
    expect(wrapper.get(`[data-case-id="${longCaseId}"]`).classes()).toContain(
      "dispute-ticket--active",
    );

    disputeStore.list.data = [];
    disputeStore.list.updatedAt = "2026-07-11T18:00:02.000Z";
    await flushPromises();
    expect(wrapper.find("[data-case-id]").exists()).toBe(false);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("switches the selected dispute without navigating", async () => {
    const { wrapper, router } = await mountOverview();

    await wrapper.get('[data-case-id="CASE_EXT_001"]').trigger("click");

    expect(wrapper.get("[data-hearing-adventure]").text()).toContain("证据核验");
    expect(router.currentRoute.value.path).toBe("/disputes");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("translates raw risk, room and pending-action enums into readable labels", async () => {
    const { wrapper } = await mountOverview();

    const firstTicket = wrapper.get('[data-case-id="CASE_EXT_001"]');
    expect(firstTicket.text()).toContain("高风险");
    expect(firstTicket.text()).toContain("证据核验");
    expect(firstTicket.text()).toContain("提交证据");
    expect(firstTicket.text()).not.toContain("SUBMIT_EVIDENCE");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("does not expose case or order identifiers in overview cards", async () => {
    const { wrapper } = await mountOverview();

    const longTicket = wrapper.get(
      `[data-case-id="${longCaseId}"]`,
    );
    expect(longTicket.text()).not.toContain(longCaseId);
    expect(longTicket.text()).not.toContain(longOrderId);
    await wrapper.get(`[data-case-id="${longCaseId}"]`).trigger("click");
    expect(wrapper.get("[data-hearing-adventure]").text()).not.toContain(longCaseId);
    expect(wrapper.get("[data-hearing-adventure]").text()).not.toContain(longOrderId);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("enters the selected dispute's current room", async () => {
    const { wrapper, router } = await mountOverview();

    await wrapper.get("[data-enter-current-room]").trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_EXT_001/evidence",
    );
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("keeps the order rail scrollable while the selected case owns the main journey space", async () => {
    const { wrapper } = await mountOverview();

    const scrollRail = wrapper.get("[data-dispute-rail-scroll]");
    expect(scrollRail.findAll(".dispute-ticket")).toHaveLength(cases.length);

    const dashboard = wrapper.get("[data-case-journey-dashboard]");
    expect(dashboard.text()).not.toContain("CASE_EXT_001");
    expect(dashboard.text()).not.toContain("ORDER-001");
    expect(wrapper.get("[data-adventure-path]").text()).toContain("证据核验");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("keeps the guide card below the title and outside the split rail-and-map layout", async () => {
    const { wrapper } = await mountOverview();
    const main = wrapper.get("[data-hearing-adventure]");
    const guide = wrapper.get("[data-overview-guide]");
    const path = main.get("[data-adventure-path]");
    const dashboard = main.get("[data-case-journey-dashboard]");

    expect(main.find("[data-overview-guide]").exists()).toBe(false);
    expect(guide.find("[data-enter-current-room]").exists()).toBe(false);
    expect(main.get("[data-enter-current-room]").text()).toContain("进入当前房间");
    expect(guide.element.compareDocumentPosition(main.element)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(dashboard.element.compareDocumentPosition(path.element)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(path.findAll("li")).toHaveLength(6);
    expect(path.text()).toContain("案情接待");
    expect(path.text()).toContain("证据核验");
    expect(path.text()).toContain("三轮庭审");
    expect(path.text()).toContain("裁决草案");
    expect(path.text()).toContain("人工终审");
    expect(path.text()).toContain("执行结果");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("declares independent rail and cognitive-field map layout contracts", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "DisputeOverviewView.vue"),
      "utf8",
    );

    expect(source).toContain("/* Light Cognitive Field overview refactor */");
    expect(source).toMatch(
      /\.overview-layout\s*\{[^}]*grid-template-columns: var\(--overview-rail-width\) minmax\(0, 1fr\)[^}]*background: transparent[^}]*border: 0/,
    );
    expect(source).toMatch(
      /\.dispute-rail\s*\{[^}]*border-radius: 30px[^}]*box-shadow:/,
    );
    expect(source).toMatch(
      /\.hearing-adventure\s*\{[^}]*background: rgba\(255, 255, 255, \.93\)[^}]*border-radius: 38px[^}]*box-shadow:/,
    );
    expect(source).toMatch(
      /\.adventure-path__route path\s*\{[^}]*stroke: #40c791[^}]*stroke-dasharray: 5 18/,
    );
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("places the overview context directly before the intro copy", async () => {
    const { wrapper } = await mountOverview();

    const lead = wrapper.get("[data-overview-lead]");
    const leadText = lead.text();

    expect(leadText).toContain("争议办理总览");
    expect(leadText).toContain("这里只有已经进入争端流程的订单");
    expect(leadText).not.toContain("AI 建议非最终");
    expect(leadText.indexOf("争议办理总览")).toBeLessThan(
      leadText.indexOf("这里只有已经进入争端流程的订单"),
    );
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("creates a minimal dispute ticket before entering the intake room", async () => {
    actor.id = "user-1";
    actor.role = "USER";
    const createAction = vi.fn().mockResolvedValue({ id: "CASE_NEW_1" });
    const { wrapper, router } = await mountOverview(createAction);

    await wrapper.get("[data-start-dispute]").trigger("click");
    await wrapper.get("[data-intake-order]").setValue("ORDER-NEW-1");
    await wrapper.get("[data-intake-merchant]").setValue("merchant-1");
    await wrapper
      .get("[data-intake-description]")
      .setValue("物流显示签收，但我没有收到包裹。");
    await wrapper.get(".intake-launcher__card").trigger("submit");
    await flushPromises();

    expect(createAction).toHaveBeenCalledWith(
      expect.objectContaining({
        initiator_role: "USER",
        order_reference: "ORDER-NEW-1",
        user_id: "user-1",
        merchant_id: "merchant-1",
      }),
    );
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_NEW_1/intake",
    );
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("submits the initiator claim resolution as a claim seed, not an execution action", async () => {
    actor.id = "user-1";
    actor.role = "USER";
    const createAction = vi.fn().mockResolvedValue({ id: "CASE_CLAIM_1" });
    const { wrapper } = await mountOverview(createAction);

    await wrapper.get("[data-start-dispute]").trigger("click");
    expect(wrapper.get("[data-claim-resolution-section]").text()).toContain("初始诉求");
    expect(wrapper.get("[data-claim-resolution-section]").text()).toContain("主张，不代表系统已执行");

    await wrapper.get("[data-intake-order]").setValue("ORDER-CLAIM-1");
    await wrapper.get("[data-intake-merchant]").setValue("merchant-1");
    await wrapper.get("[data-claim-resolution-type]").setValue("REFUND");
    await wrapper.get("[data-claim-requested-amount]").setValue("299");
    await wrapper.get("[data-claim-requested-items]").setValue("儿童手表 1 件");
    await wrapper
      .get("[data-intake-description]")
      .setValue("我没收到包裹，希望退款");
    await wrapper.get(".intake-launcher__card").trigger("submit");
    await flushPromises();

    expect(createAction).toHaveBeenCalledWith(
      expect.objectContaining({
        claim_resolution_seed: {
          initiator_role: "USER",
          requested_resolution: "REFUND",
          requested_amount: 299,
          requested_items: "儿童手表 1 件",
          request_reason: "我没收到包裹，希望退款",
        },
      }),
    );
    expect(
      createAction.mock.calls[0][0].claim_resolution_seed,
    ).not.toHaveProperty("original_statement");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("keeps dispute initiation available to merchants", async () => {
    actor.id = "merchant-1";
    actor.role = "MERCHANT";
    const { wrapper } = await mountOverview();

    expect(wrapper.find("[data-start-dispute]").exists()).toBe(true);

    await wrapper.get("[data-start-dispute]").trigger("click");

    expect(wrapper.find(".intake-launcher").exists()).toBe(true);
    expect(wrapper.get("[data-intake-merchant]").element.value).toBe("merchant-1");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("does not expose dispute initiation to platform reviewers", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const { wrapper } = await mountOverview();

    expect(wrapper.find("[data-start-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-simulate-external-import]").exists()).toBe(false);
    expect(wrapper.find(".intake-launcher").exists()).toBe(false);
  });

  it.each([
    ["USER", "user-local"],
    ["MERCHANT", "merchant-local"],
  ])("does not expose simulation deletion to %s actors", async (role, id) => {
    actor.id = id;
    actor.role = role;
    const { wrapper } = await mountOverview();

    expect(wrapper.find("[data-delete-simulated-case]").exists()).toBe(false);
    expect(wrapper.find("[data-simulate-external-import]").exists()).toBe(true);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("shows deletion only for a reviewer selecting an approved simulated source", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const { wrapper } = await mountOverview();

    expect(wrapper.find("[data-delete-simulated-case]").exists()).toBe(true);

    await wrapper.get(`[data-case-id="${longCaseId}"]`).trigger("click");

    expect(wrapper.find("[data-delete-simulated-case]").exists()).toBe(false);

    await wrapper.get(`[data-case-id="${formalExternalCaseId}"]`).trigger("click");

    expect(wrapper.find("[data-delete-simulated-case]").exists()).toBe(false);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("cancels simulated-case deletion without calling the API", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const deleteAction = vi.fn();
    const { wrapper } = await mountOverview(null, null, deleteAction);

    await wrapper.get("[data-delete-simulated-case]").trigger("click");
    const modal = wrapper.get("[data-delete-case-modal]");
    expect(modal.text()).toContain("删除后不可恢复");

    await modal.get("[data-cancel-delete-case]").trigger("click");

    expect(deleteAction).not.toHaveBeenCalled();
    expect(wrapper.find("[data-delete-case-modal]").exists()).toBe(false);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("deletes a simulated case once, updates the list and selects the next case", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    let resolveDelete;
    const pendingDelete = new Promise((resolve) => {
      resolveDelete = resolve;
    });
    const deleteAction = vi.fn(() => pendingDelete);
    const { wrapper } = await mountOverview(null, null, deleteAction);

    await wrapper.get("[data-delete-simulated-case]").trigger("click");
    const confirmButton = wrapper.get("[data-confirm-delete-case]");
    const firstClick = confirmButton.trigger("click");
    const duplicateClick = confirmButton.trigger("click");
    await Promise.all([firstClick, duplicateClick]);

    expect(deleteAction).toHaveBeenCalledTimes(1);
    expect(deleteAction).toHaveBeenCalledWith("CASE_EXT_001");
    expect(wrapper.get("[data-confirm-delete-case]").attributes("disabled")).toBeDefined();

    resolveDelete({ case_id: "CASE_EXT_001", deleted: true });
    await flushPromises();

    expect(wrapper.find('[data-case-id="CASE_EXT_001"]').exists()).toBe(false);
    expect(wrapper.get(`[data-case-id="${longCaseId}"]`).classes()).toContain(
      "dispute-ticket--active",
    );
    expect(wrapper.find("[data-delete-case-modal]").exists()).toBe(false);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("keeps confirmation open and restores actions when deletion fails", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const deleteAction = vi.fn().mockRejectedValue(new Error("删除服务暂不可用"));
    const { wrapper } = await mountOverview(null, null, deleteAction);

    await wrapper.get("[data-delete-simulated-case]").trigger("click");
    await wrapper.get("[data-confirm-delete-case]").trigger("click");
    await flushPromises();

    const modal = wrapper.get("[data-delete-case-modal]");
    expect(modal.get("[role=alert]").text()).toContain("删除服务暂不可用");
    expect(modal.get("[data-confirm-delete-case]").attributes("disabled")).toBeUndefined();
    expect(wrapper.find('[data-case-id="CASE_EXT_001"]').exists()).toBe(true);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("does not open intake when a stale party action is triggered after switching to reviewer", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const { wrapper } = await mountOverview();
    const staleStartAction = wrapper.get("[data-start-dispute]");

    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    await flushPromises();
    await staleStartAction.trigger("click");

    expect(wrapper.find(".intake-launcher").exists()).toBe(false);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("does not create a dispute when the actor becomes a reviewer after opening intake", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const createAction = vi.fn().mockResolvedValue({ id: "CASE_FORBIDDEN" });
    const { wrapper, router } = await mountOverview(createAction);

    await wrapper.get("[data-start-dispute]").trigger("click");
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    await wrapper.get(".intake-launcher__card").trigger("submit");
    await flushPromises();

    expect(createAction).not.toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe("/disputes");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("simulates external imported disputes from the current demo identity", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const simulateExternalImportAction = vi.fn().mockResolvedValue({
      items: [
        {
          id: "CASE_IMPORT_1",
          case_id: "CASE_IMPORT_1",
          order_id: "ORDER-20260706-4201",
          title: "商家发起手表故障争议",
          source_type: "EXTERNAL_IMPORT",
          source_system: "TEMPLATE_SIMULATED_OMS",
          dispute_type: "QUALITY_DISPUTE",
          case_status: "INTAKE_PENDING",
          current_room: "INTAKE",
          risk_level: "MEDIUM",
          pending_action: "COMPLETE_INTAKE",
          initiator_role: "MERCHANT",
        },
      ],
    });
    const { wrapper } = await mountOverview(null, simulateExternalImportAction);

    await wrapper.get("[data-simulate-external-import]").trigger("click");
    await flushPromises();

    expect(simulateExternalImportAction).toHaveBeenCalledWith(
      expect.objectContaining({
        count: 1,
        initiator_role_hint: "MERCHANT",
        current_actor_id: "merchant-local",
        counterparty_actor_id: "user-local",
      }),
    );
    expect(wrapper.text()).toContain("商家发起手表故障争议");
    expect(wrapper.text()).not.toContain("LLM 模拟外部导入争议");
    expect(wrapper.text()).not.toContain("ORDER-SIM");
    const importedTicket = wrapper.get('[data-case-id="CASE_IMPORT_1"]');
    expect(importedTicket.exists()).toBe(true);
    expect(importedTicket.attributes("data-source-system")).toBe(
      "TEMPLATE_SIMULATED_OMS",
    );
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("ignores duplicate import triggers while an import is pending", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    let resolveImport;
    const pendingImport = new Promise((resolve) => {
      resolveImport = resolve;
    });
    const simulateExternalImportAction = vi.fn(() => pendingImport);
    const { wrapper } = await mountOverview(null, simulateExternalImportAction);
    const importButton = wrapper.get("[data-simulate-external-import]");

    const firstTrigger = importButton.trigger("click");
    const duplicateTrigger = importButton.trigger("click");
    await Promise.all([firstTrigger, duplicateTrigger]);
    const requestCountWhilePending = simulateExternalImportAction.mock.calls.length;

    resolveImport({
      items: [
        {
          id: "CASE_IMPORT_DEDUPED",
          source_type: "EXTERNAL_IMPORT",
          case_status: "INTAKE_PENDING",
          current_room: "INTAKE",
          initiator_role: "USER",
        },
      ],
    });
    await flushPromises();

    expect(requestCountWhilePending).toBe(1);
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("re-enables the import button after the API request times out", async () => {
    vi.useFakeTimers();
    actor.id = "user-local";
    actor.role = "USER";
    vi.spyOn(globalThis, "fetch").mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("The request was aborted", "AbortError")),
            { once: true },
          );
        }),
    );
    const { wrapper } = await mountOverview();
    const importButton = wrapper.get("[data-simulate-external-import]");

    await importButton.trigger("click");
    expect(importButton.attributes("disabled")).toBeDefined();

    await vi.advanceTimersByTimeAsync(15_000);
    await flushPromises();

    expect(importButton.attributes("disabled")).toBeUndefined();
    expect(importButton.text()).toContain("导入外部争议");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("does not expose simulation wording when an imported item lacks optional display fields", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const simulateExternalImportAction = vi.fn().mockResolvedValue({
      items: [
        {
          id: "CASE_IMPORT_MINIMAL",
          source_type: "EXTERNAL_IMPORT",
          case_status: "INTAKE_PENDING",
          current_room: "INTAKE",
          initiator_role: "USER",
        },
      ],
    });
    const { wrapper } = await mountOverview(null, simulateExternalImportAction);

    await wrapper.get("[data-simulate-external-import]").trigger("click");
    await flushPromises();

    const ticketText = wrapper.get('[data-case-id="CASE_IMPORT_MINIMAL"]').text();
    expect(ticketText).toContain("外部导入争议");
    expect(ticketText).not.toContain("模拟");
    expect(ticketText).not.toContain("ORDER-SIM");
  });
});
