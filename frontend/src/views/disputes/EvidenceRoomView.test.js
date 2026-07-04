import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EvidenceRoomView from "./EvidenceRoomView.vue";
import { evidenceApi } from "../../api/evidence";

vi.mock("../../api/evidence", () => ({
  evidenceApi: {
    catalog: vi.fn(),
    completion: vi.fn(),
    upload: vi.fn(),
    complete: vi.fn(),
  },
}));

const catalog = {
  case_id: "CASE_EVIDENCE_1",
  items: [
    {
      evidence_id: "EVIDENCE_USER_PRIVATE",
      evidence_type: "CHAT_SCREENSHOT",
      submitted_by_role: "USER",
      visibility: "PRIVATE",
      content_url: "/objects/user-original.png",
      redacted: false,
      verification_status: "VERIFIED",
    },
    {
      evidence_id: "EVIDENCE_USER_SHARED",
      evidence_type: "LOGISTICS_PROOF",
      submitted_by_role: "USER",
      visibility: "PARTIES",
      content_url: "/objects/logistics-redacted.png",
      redacted: true,
      verification_status: "PLAUSIBLE",
    },
    {
      evidence_id: "EVIDENCE_MERCHANT_SHARED",
      evidence_type: "DELIVERY_RECORD",
      submitted_by_role: "MERCHANT",
      visibility: "PARTIES",
      content_url: "/objects/merchant-delivery.pdf",
      redacted: false,
      verification_status: "SUSPICIOUS",
    },
    {
      evidence_id: "EVIDENCE_REJECTED",
      evidence_type: "OTHER",
      submitted_by_role: "USER",
      visibility: "PARTIES",
      content_url: null,
      redacted: false,
      verification_status: "REJECTED",
    },
    {
      evidence_id: "EVIDENCE_REVIEW",
      evidence_type: "VIDEO",
      submitted_by_role: "MERCHANT",
      visibility: "PARTIES",
      content_url: null,
      redacted: false,
      verification_status: "NEEDS_HUMAN_REVIEW",
    },
  ],
};

function routerForEvidence() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes/:caseId/evidence", component: { template: "<div />" } },
      { path: "/disputes/:caseId/hearing", component: { template: "<div />" } },
    ],
  });
}

async function mountView(overrides = {}) {
  const router = routerForEvidence();
  await router.push("/disputes/CASE_EVIDENCE_1/evidence");
  await router.isReady();
  const completeAction = overrides.completeAction || vi.fn();
  const wrapper = mount(EvidenceRoomView, {
    props: {
      initialCatalog: catalog,
      initialCompletion: {
        case_id: "CASE_EVIDENCE_1",
        user_completed: false,
        merchant_completed: false,
        sealed: false,
        next_room: null,
      },
      deadlineAt: "2026-07-03T14:00:00+08:00",
      serverNow: "2026-07-03T12:00:00+08:00",
      viewerRole: "USER",
      initialMessages: [],
      completeAction,
      ...overrides,
    },
    global: { plugins: [router] },
  });
  return { wrapper, router, completeAction };
}

describe("EvidenceRoomView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    evidenceApi.catalog.mockResolvedValue(catalog);
    evidenceApi.upload.mockResolvedValue({});
  });

  it("separates my originals from the shared dossier and renders all verification states", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("证据书记官");
    expect(wrapper.get("[data-evidence-private]").text()).toContain(
      "EVIDENCE_USER_PRIVATE",
    );
    expect(wrapper.get("[data-evidence-shared]").text()).not.toContain(
      "EVIDENCE_USER_PRIVATE",
    );
    expect(wrapper.get("[data-evidence-shared]").text()).toContain(
      "EVIDENCE_MERCHANT_SHARED",
    );
    for (const status of [
      "VERIFIED",
      "PLAUSIBLE",
      "SUSPICIOUS",
      "REJECTED",
      "NEEDS_HUMAN_REVIEW",
    ]) {
      expect(wrapper.find(`[data-verification="${status}"]`).exists()).toBe(true);
    }
    expect(wrapper.get("[data-evidence-countdown]").text()).toContain("02:00:00");
  });

  it("waits for the server completion result before exposing the hearing entrance", async () => {
    const completeAction = vi.fn().mockResolvedValue({
      all_parties_completed: true,
      next_room: "HEARING",
    });
    const { wrapper, router } = await mountView({ completeAction });

    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    expect(completeAction).toHaveBeenCalledOnce();
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_EVIDENCE_1/evidence",
    );
    expect(wrapper.get("[data-enter-hearing]").text()).toContain("进入小法庭");

    await wrapper.get("[data-enter-hearing]").trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_EVIDENCE_1/hearing",
    );
  });

  it("shows a durable waiting state after the current party completes evidence", async () => {
    const completeAction = vi.fn().mockResolvedValue({
      completed_role: "USER",
      all_parties_completed: false,
      next_room: "EVIDENCE",
    });
    const { wrapper } = await mountView({ completeAction });

    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    expect(completeAction).toHaveBeenCalledOnce();
    expect(wrapper.find("[data-complete-evidence]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-hearing]").exists()).toBe(false);
    expect(wrapper.get("[data-evidence-completed]").text()).toContain("等待商家");
  });

  it("shows the merchant completion variant without exposing a user-only original", async () => {
    const { wrapper } = await mountView({ viewerRole: "MERCHANT" });

    expect(wrapper.text()).toContain("商家证据方");
    expect(wrapper.get("[data-evidence-private]").text()).not.toContain(
      "EVIDENCE_USER_PRIVATE",
    );
  });

  it("exposes the hearing entrance when the resumed event stream announces it", async () => {
    const eventStreamer = vi.fn(async (options) => {
      options.state.connected = true;
      await options.applyEvent({ id: 9, event: "HEARING_OPENED", data: {} });
    });
    const { wrapper, router } = await mountView({ eventStreamer });
    await flushPromises();

    expect(eventStreamer).toHaveBeenCalledWith(
      expect.objectContaining({
        caseId: "CASE_EVIDENCE_1",
        roomType: "EVIDENCE",
      }),
    );
    expect(wrapper.find('[data-connection="connected"]').exists()).toBe(true);
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_EVIDENCE_1/evidence",
    );
    expect(wrapper.get("[data-enter-hearing]").text()).toContain("进入小法庭");
  });

  it("records evidence explanations through the immutable clerk conversation", async () => {
    const messageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_EVIDENCE_1",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "这张照片由我在开箱时拍摄。",
    });
    const { wrapper } = await mountView({ messageAction });

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("这张照片由我在开箱时拍摄。");
    await wrapper.get("[data-send-message]").trigger("submit");
    await flushPromises();

    expect(messageAction).toHaveBeenCalledWith({
      message_type: "PARTY_TEXT",
      text: "这张照片由我在开箱时拍摄。",
      attachment_refs: [],
    });
    expect(wrapper.text()).toContain("这张照片由我在开箱时拍摄。");
  });

  it("uploads user evidence with the backend actor-specific source type", async () => {
    const { wrapper } = await mountView({ viewerRole: "USER" });
    const input = wrapper.get('input[type="file"]');
    const file = new File(["demo"], "开箱照片.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    await flushPromises();

    expect(evidenceApi.upload).toHaveBeenCalledWith(
      expect.anything(),
      "CASE_EVIDENCE_1",
      expect.objectContaining({
        file,
        sourceType: "USER_UPLOAD",
        visibility: "PARTIES",
      }),
    );
  });
});
