import { flushPromises, mount } from "@vue/test-utils";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { describe, expect, it, vi } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { actor } from "../../state/actor";
import DisputeOverviewView from "./DisputeOverviewView.vue";

const longCaseId = "CASE_EXT_002_LONG_IDENTIFIER_FOR_LAYOUT_TESTING";
const longOrderId = "ORDER_EXT_002_LONG_IDENTIFIER_FOR_LAYOUT_TESTING";

const cases = [
  {
    id: "CASE_EXT_001",
    order_id: "ORDER-001",
    source_type: "EXTERNAL_IMPORT",
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
];

async function mountOverview(createAction = null, simulateExternalImportAction = null) {
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
      initialCases: cases,
      serverNow: "2026-07-03T10:00:00Z",
      createAction,
      simulateExternalImportAction,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router };
}

describe("DisputeOverviewView", () => {
  it("switches the selected dispute without navigating", async () => {
    const { wrapper, router } = await mountOverview();

    await wrapper.get('[data-case-id="CASE_EXT_001"]').trigger("click");

    expect(wrapper.get("[data-hearing-adventure]").text()).toContain(
      "证据书记官室",
    );
    expect(router.currentRoute.value.path).toBe("/disputes");
  });

  it("translates raw risk, room and pending-action enums into readable labels", async () => {
    const { wrapper } = await mountOverview();

    const firstTicket = wrapper.get('[data-case-id="CASE_EXT_001"]');
    expect(firstTicket.text()).toContain("高风险");
    expect(firstTicket.text()).toContain("证据书记官室");
    expect(firstTicket.text()).toContain("提交证据");
    expect(firstTicket.text()).not.toContain("SUBMIT_EVIDENCE");
  });

  it("keeps long identifiers compact while preserving the full value as a title", async () => {
    const { wrapper } = await mountOverview();

    const longTicket = wrapper.get(
      `[data-case-id="${longCaseId}"]`,
    );
    expect(longTicket.get("[data-short-case-id]").text()).toContain("…");
    expect(longTicket.get("[data-short-case-id]").attributes("title")).toBe(
      longCaseId,
    );
  });

  it("exposes the full case and order values from the compact 2x2 case index", async () => {
    const { wrapper } = await mountOverview();

    await wrapper.get(`[data-case-id="${longCaseId}"]`).trigger("click");

    const caseValue = wrapper.get("[data-case-file-value]");
    const orderValue = wrapper.get("[data-order-value]");
    expect(caseValue.attributes()).toMatchObject({
      title: longCaseId,
      "aria-label": longCaseId,
    });
    expect(orderValue.attributes()).toMatchObject({
      title: longOrderId,
      "aria-label": longOrderId,
    });
  });

  it("enters the selected dispute's current room", async () => {
    const { wrapper, router } = await mountOverview();

    await wrapper.get("[data-enter-current-room]").trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_EXT_001/evidence",
    );
  });

  it("keeps the order rail scrollable while the selected case owns the main journey space", async () => {
    const { wrapper } = await mountOverview();

    const scrollRail = wrapper.get("[data-dispute-rail-scroll]");
    expect(scrollRail.findAll(".dispute-ticket")).toHaveLength(cases.length);

    const dashboard = wrapper.get("[data-case-journey-dashboard]");
    expect(dashboard.text()).toContain("CASE_EXT_001");
    expect(dashboard.text()).toContain("ORDER-001");
    expect(dashboard.text()).toContain("EVIDENCE");
  });

  it("shows the guide card first, then the journey path, then the case dashboard", async () => {
    const { wrapper } = await mountOverview();
    const main = wrapper.get("[data-hearing-adventure]");
    const guide = main.get("[data-overview-guide]");
    const path = main.get("[data-adventure-path]");
    const dashboard = main.get("[data-case-journey-dashboard]");

    expect(main.element.compareDocumentPosition(guide.element)).toBe(
      Node.DOCUMENT_POSITION_CONTAINED_BY | Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(guide.element.compareDocumentPosition(path.element)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(path.element.compareDocumentPosition(dashboard.element)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it("declares the fixed overview frame, horizontal track and 2x2 case-index contracts", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "DisputeOverviewView.vue"),
      "utf8",
    );

    expect(source).toContain("--overview-journey-gap");
    expect(source).toContain("gap: var(--overview-journey-gap)");
    expect(source).toContain(
      "height: clamp(720px, calc(100dvh - 170px), 780px)",
    );
    expect(source).toContain("height: 810px");
    expect(source).toContain("height: 880px");
    expect(source).toContain("height: 940px");
    expect(source).toMatch(
      /\.hearing-adventure__case-board\s*\{[^}]*grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/,
    );
    expect(source).toMatch(
      /\.adventure-path\s*\{[^}]*overflow-x: auto/,
    );
    expect(source).toContain(".hearing-adventure__sky { position: absolute");
    expect(source).not.toContain(
      ".adventure-path { grid-template-columns: 1fr",
    );
    expect(source).not.toContain(
      ".hearing-adventure__case-board { grid-template-columns: 1fr; }",
    );
  });

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
      .get("[data-claim-request-reason]")
      .setValue("物流显示签收但用户本人没有收到包裹，希望退款。");
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
          request_reason: "物流显示签收但用户本人没有收到包裹，希望退款。",
          original_statement: "我没收到包裹，希望退款",
        },
      }),
    );
  });

  it("keeps dispute initiation available to merchants", async () => {
    actor.id = "merchant-1";
    actor.role = "MERCHANT";
    const { wrapper } = await mountOverview();

    expect(wrapper.find("[data-start-dispute]").exists()).toBe(true);

    await wrapper.get("[data-start-dispute]").trigger("click");

    expect(wrapper.find(".intake-launcher").exists()).toBe(true);
    expect(wrapper.get("[data-intake-merchant]").element.value).toBe("merchant-1");
  });

  it("does not expose dispute initiation to platform reviewers", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const { wrapper } = await mountOverview();

    expect(wrapper.find("[data-start-dispute]").exists()).toBe(false);
    expect(wrapper.find(".intake-launcher").exists()).toBe(false);
  });

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
    expect(wrapper.get('[data-case-id="CASE_IMPORT_1"]').exists()).toBe(true);
  });

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
