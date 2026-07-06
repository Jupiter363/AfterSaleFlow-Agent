import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EvidenceRoomView from "./EvidenceRoomView.vue";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";

vi.mock("../../api/evidence", () => ({
  evidenceApi: {
    catalog: vi.fn(),
    completion: vi.fn(),
    upload: vi.fn(),
    complete: vi.fn(),
  },
}));

vi.mock("../../api/rooms", () => ({
  roomApi: {
    ensureOpening: vi.fn(),
    messages: vi.fn(),
    postMessage: vi.fn(),
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
      confidence_score: 0.92,
      confidence_level: "HIGH",
      verification_feedback: "原始截图来源清晰，时间线可核对。",
      original_filename: "user-original.png",
      parsed_text: "用户开箱时拍摄的表盘划痕照片。",
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

const initialCompletion = {
  case_id: "CASE_EVIDENCE_1",
  user_completed: false,
  merchant_completed: false,
  sealed: false,
  next_room: null,
};

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((innerResolve, innerReject) => {
    resolve = innerResolve;
    reject = innerReject;
  });
  return { promise, resolve, reject };
}

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
      initialCompletion,
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
    evidenceApi.completion.mockResolvedValue(initialCompletion);
    evidenceApi.upload.mockResolvedValue({});
    roomApi.ensureOpening.mockResolvedValue({
      id: "EVIDENCE_OPENING",
      sequence_no: 1,
      sender_role: "CUSTOMER_SERVICE",
      message_type: "AGENT_MESSAGE",
      message_text: "请先补充与接待室案情匹配的证据来源、形成时间和原件。",
    });
    roomApi.messages.mockResolvedValue([]);
    roomApi.postMessage.mockResolvedValue({
      id: "MESSAGE_POSTED",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "posted",
    });
  });

  it("renders an intake-like fixed two-panel evidence room", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.get("[data-evidence-room-layout]").exists()).toBe(true);
    expect(wrapper.get("[data-evidence-chat-panel]").exists()).toBe(true);
    expect(wrapper.get("[data-evidence-board-panel]").exists()).toBe(true);
    expect(wrapper.get("[data-evidence-list-scroll]").exists()).toBe(true);
    expect(
      wrapper.get("[data-evidence-board-panel]").find(".evidence-uploader").exists(),
    ).toBe(true);
  });

  it("shows confidence feedback and keeps completion available for low confidence evidence", async () => {
    const lowConfidenceCatalog = {
      ...catalog,
      items: [
        {
          ...catalog.items[0],
          confidence_score: 0.31,
          confidence_level: "LOW",
          verification_feedback: "证据来源仍需补充，当前只能作为低置信参考。",
          verification_status: "SUSPICIOUS",
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: lowConfidenceCatalog });

    expect(wrapper.get("[data-evidence-confidence]").text()).toContain("31%");
    expect(wrapper.get("[data-evidence-confidence]").text()).toContain("低置信");
    expect(wrapper.get("[data-complete-evidence]").element.disabled).toBe(false);
  });

  it("opens a submitted evidence detail modal from a card", async () => {
    const { wrapper } = await mountView();

    await wrapper.get("[data-evidence-card]").trigger("click");

    const modal = wrapper.get("[data-evidence-detail-modal]");
    expect(modal.text()).toContain("EVIDENCE_USER_PRIVATE");
    expect(modal.text()).toContain("92%");
    expect(modal.text()).toContain("原始截图来源清晰");
    expect(modal.text()).toContain("user-original.png");

    await modal.get("[data-close-evidence-modal]").trigger("click");
    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
  });

  it("ensures the evidence clerk opens the first actor-scoped turn only for an empty thread", async () => {
    roomApi.messages.mockResolvedValueOnce([]);
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "EVIDENCE_OPENING",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "请根据接待室案情先补充质检视频、物流记录和原图。",
      },
    ]);

    const { wrapper } = await mountView({ initialMessages: null });
    await flushPromises();

    expect(roomApi.ensureOpening).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(roomApi.messages).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).toContain("请根据接待室案情先补充质检视频");
  });

  it("does not append a clerk opening when the actor already has an evidence conversation", async () => {
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "USER_EXISTING_EVIDENCE_TURN",
        sequence_no: 4,
        sender_role: "USER",
        message_type: "PARTY_TEXT",
        message_text: "I already started this evidence conversation.",
      },
    ]);

    const { wrapper } = await mountView({ initialMessages: null });
    await flushPromises();

    expect(roomApi.ensureOpening).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("I already started this evidence conversation.");
  });

  it("requests a dossier-specific opening upgrade for a stale generic clerk welcome", async () => {
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "STALE_GENERIC_OPENING",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "您好！我是您的证据书记官，请上传与本案相关的证据材料。",
      },
    ]);
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "STALE_GENERIC_OPENING",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "您好！我是您的证据书记官，请上传与本案相关的证据材料。",
      },
      {
        id: "DOSSIER_OPENING",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text:
          "我先根据接待室收敛的案情开始举证核对，请补充商家质检视频、用户划痕原图和物流签收记录。",
      },
    ]);

    const { wrapper } = await mountView({ initialMessages: null });
    await flushPromises();

    expect(roomApi.ensureOpening).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).toContain("接待室收敛的案情");
    expect(wrapper.text()).toContain("商家质检视频");
  });

  it("requests an opening upgrade when earlier fallback openings still have no case focus", async () => {
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "STALE_GENERIC_OPENING",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "您好！我是您的证据书记官，请上传与本案相关的证据材料。",
      },
      {
        id: "STALE_PENDING_FOCUS_OPENING",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text:
          "我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 争议焦点待确认，首轮请围绕这些材料补充证据：原始证据文件、证据形成时间、证据来源路径。",
      },
    ]);
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "STALE_GENERIC_OPENING",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "您好！我是您的证据书记官，请上传与本案相关的证据材料。",
      },
      {
        id: "STALE_PENDING_FOCUS_OPENING",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text:
          "我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 争议焦点待确认，首轮请围绕这些材料补充证据：原始证据文件、证据形成时间、证据来源路径。",
      },
      {
        id: "DOSSIER_OPENING",
        sequence_no: 3,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text:
          "我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 SIGNED_NOT_RECEIVED，请补充物流签收记录、投递轨迹和收货地址匹配记录。",
      },
    ]);

    const { wrapper } = await mountView({ initialMessages: null });
    await flushPromises();

    expect(roomApi.ensureOpening).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).not.toContain("您好！我是您的证据书记官");
    expect(wrapper.text()).toContain("SIGNED_NOT_RECEIVED");
    expect(wrapper.text()).toContain("物流签收记录");
  });

  it("still opens the evidence clerk conversation when the evidence catalog is not created yet", async () => {
    const missingCatalog = new Error("catalog not found");
    missingCatalog.code = "EVIDENCE_NOT_FOUND";
    evidenceApi.catalog.mockRejectedValueOnce(missingCatalog);
    roomApi.messages
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "EVIDENCE_OPENING_WITH_EMPTY_CATALOG",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_type: "AGENT_MESSAGE",
          message_text: "Please provide evidence source, timestamp and original file.",
        },
      ]);

    const { wrapper } = await mountView({
      initialCatalog: null,
      initialMessages: null,
      eventStreamer: vi.fn(async () => {}),
    });
    await flushPromises();
    await flushPromises();

    expect(roomApi.ensureOpening).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).toContain(
      "Please provide evidence source, timestamp and original file.",
    );
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
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

  it("keeps platform-only evidence out of the party shared dossier", async () => {
    const platformCatalog = {
      ...catalog,
      items: [
        ...catalog.items,
        {
          evidence_id: "EVIDENCE_PLATFORM_ONLY",
          evidence_type: "OTHER",
          submitted_by_role: "PLATFORM_REVIEWER",
          visibility: "PLATFORM",
          content_url: null,
          redacted: false,
          verification_status: "PENDING",
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: platformCatalog });

    expect(wrapper.get("[data-evidence-shared]").text()).not.toContain(
      "EVIDENCE_PLATFORM_ONLY",
    );
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

  it("keeps the evidence conversation composer enabled while evidence is not uploading or completing", async () => {
    const { wrapper } = await mountView();
    const textarea = wrapper.get('[data-send-message] textarea');
    const submitButton = wrapper.get('[data-send-message] button[type="submit"]');

    expect(textarea.element.disabled).toBe(false);
    expect(submitButton.element.disabled).toBe(true);

    await textarea.setValue("Composer should accept evidence explanations.");

    expect(textarea.element.disabled).toBe(false);
    expect(submitButton.element.disabled).toBe(false);
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

  it("refreshes catalog, completion and evidence messages after a successful upload", async () => {
    const refreshedCatalog = {
      ...catalog,
      items: [
        ...catalog.items,
        {
          evidence_id: "EVIDENCE_MARKDOWN_UPLOAD",
          evidence_type: "OTHER",
          submitted_by_role: "MERCHANT",
          visibility: "PARTIES",
          content_url: "/objects/merchant-notes.md",
          redacted: true,
          verification_status: "PENDING",
        },
      ],
    };
    const clerkMessages = [
      {
        id: "CLERK_AFTER_UPLOAD",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "Markdown evidence has been indexed by the clerk.",
      },
    ];
    evidenceApi.catalog.mockResolvedValueOnce(refreshedCatalog);
    evidenceApi.completion.mockResolvedValueOnce({
      ...initialCompletion,
      merchant_completed: false,
    });
    roomApi.messages.mockResolvedValueOnce(clerkMessages);
    const { wrapper } = await mountView({ viewerRole: "MERCHANT" });
    const input = wrapper.get('input[type="file"]');
    const file = new File(["# delivery notes"], "delivery-notes.md", {
      type: "text/markdown",
    });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    await flushPromises();

    const uploadCommand = evidenceApi.upload.mock.calls[0][2];
    expect(uploadCommand.file).toBe(file);
    expect(["OTHER", "DOCUMENT"]).toContain(uploadCommand.evidenceType);
    expect(uploadCommand.sourceType).toBe("MERCHANT_UPLOAD");
    expect(uploadCommand.visibility).toBe("PARTIES");
    expect(evidenceApi.catalog).toHaveBeenCalledWith(
      expect.objectContaining({ role: "MERCHANT" }),
      "CASE_EVIDENCE_1",
    );
    expect(evidenceApi.completion).toHaveBeenCalledWith(
      expect.objectContaining({ role: "MERCHANT" }),
      "CASE_EVIDENCE_1",
    );
    expect(roomApi.messages).toHaveBeenCalledWith(
      expect.objectContaining({ role: "MERCHANT" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).toContain("EVIDENCE_MARKDOWN_UPLOAD");
    expect(wrapper.text()).toContain("Markdown evidence has been indexed by the clerk.");
  });

  it("refreshes evidence messages after sending an explanation and shows the clerk reply", async () => {
    const refreshedMessages = [
      {
        id: "USER_EXPLANATION",
        sequence_no: 1,
        sender_role: "USER",
        message_type: "PARTY_TEXT",
        message_text: "This photo was taken when I opened the package.",
      },
      {
        id: "CLERK_REPLY",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "I have linked the photo to the packaging timeline.",
      },
    ];
    roomApi.postMessage.mockResolvedValue({
      id: "USER_EXPLANATION",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "This photo was taken when I opened the package.",
    });
    roomApi.messages.mockResolvedValueOnce(refreshedMessages);
    const { wrapper } = await mountView();

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("This photo was taken when I opened the package.");
    await wrapper.get("[data-send-message]").trigger("submit");
    await flushPromises();

    expect(roomApi.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
      {
        message_type: "PARTY_TEXT",
        text: "This photo was taken when I opened the package.",
        attachment_refs: [],
      },
    );
    expect(roomApi.messages).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).toContain("This photo was taken when I opened the package.");
    expect(wrapper.text()).toContain("I have linked the photo to the packaging timeline.");
  });

  it("shows the party statement immediately while the evidence clerk turn is running", async () => {
    let resolvePost;
    const savedMessage = {
      id: "USER_EXPLANATION_CONFIRMED",
      sequence_no: 7,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "Please verify this markdown evidence.",
    };
    roomApi.postMessage.mockReturnValue(
      new Promise((resolve) => {
        resolvePost = resolve;
      }),
    );
    roomApi.messages.mockResolvedValueOnce([savedMessage]);
    const { wrapper } = await mountView({
      agentReplyPollAttempts: 0,
      agentReplyPollDelayMs: 0,
    });

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("Please verify this markdown evidence.");
    await wrapper.get("[data-send-message]").trigger("submit");

    expect(wrapper.text()).toContain("Please verify this markdown evidence.");

    resolvePost(savedMessage);
    await flushPromises();

    expect(wrapper.text()).toContain("Please verify this markdown evidence.");
  });

  it("continues refreshing after send until the evidence clerk reply appears", async () => {
    const savedMessage = {
      id: "USER_EXPLANATION_CONFIRMED",
      sequence_no: 7,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "Please verify this markdown evidence.",
    };
    const clerkReply = {
      id: "EVIDENCE_CLERK_REPLY",
      sequence_no: 8,
      sender_role: "CUSTOMER_SERVICE",
      message_type: "AGENT_MESSAGE",
      message_text: "证据书记官：请补充原始文件哈希和形成时间。",
    };
    roomApi.postMessage.mockResolvedValue(savedMessage);
    roomApi.messages
      .mockResolvedValueOnce([savedMessage])
      .mockResolvedValueOnce([savedMessage, clerkReply]);
    const { wrapper } = await mountView({
      agentReplyPollAttempts: 2,
      agentReplyPollDelayMs: 0,
    });

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("Please verify this markdown evidence.");
    await wrapper.get("[data-send-message]").trigger("submit");
    await flushPromises();
    await flushPromises();

    expect(roomApi.messages).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("证据书记官：请补充原始文件哈希和形成时间。");
  });

  it("reloads actor-scoped evidence data when the viewer role changes", async () => {
    const userThread = [
      {
        id: "USER_THREAD_MESSAGE",
        sequence_no: 1,
        sender_role: "USER",
        message_text: "User-only evidence explanation thread.",
      },
    ];
    const merchantThread = [
      {
        id: "MERCHANT_THREAD_MESSAGE",
        sequence_no: 1,
        sender_role: "MERCHANT",
        message_text: "Merchant-only evidence explanation thread.",
      },
    ];
    roomApi.messages.mockResolvedValueOnce(merchantThread);
    const { wrapper } = await mountView({
      viewerRole: "USER",
      initialMessages: userThread,
    });

    expect(wrapper.text()).toContain("User-only evidence explanation thread.");

    await wrapper.setProps({ viewerRole: "MERCHANT" });
    await flushPromises();

    expect(roomApi.messages).toHaveBeenCalledWith(
      expect.objectContaining({ role: "MERCHANT" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).not.toContain("User-only evidence explanation thread.");
    expect(wrapper.text()).toContain("Merchant-only evidence explanation thread.");
  });

  it("clears the previous party thread immediately when the viewer role changes", async () => {
    const stalledRefresh = deferred();
    roomApi.messages.mockReturnValueOnce(stalledRefresh.promise);
    const { wrapper } = await mountView({
      viewerRole: "USER",
      initialMessages: [
        {
          id: "USER_THREAD_MESSAGE",
          sequence_no: 1,
          sender_role: "USER",
          message_text: "User-only evidence explanation thread.",
        },
      ],
    });

    expect(wrapper.text()).toContain("User-only evidence explanation thread.");

    await wrapper.setProps({ viewerRole: "MERCHANT" });

    expect(wrapper.text()).not.toContain("User-only evidence explanation thread.");

    stalledRefresh.resolve([]);
    await flushPromises();
  });

  it("ignores stale evidence refresh results from the previous actor", async () => {
    const merchantMessages = deferred();
    const userMessages = deferred();
    roomApi.messages
      .mockReturnValueOnce(merchantMessages.promise)
      .mockReturnValueOnce(userMessages.promise);
    const { wrapper } = await mountView({
      viewerRole: "USER",
      initialMessages: [
        {
          id: "USER_INITIAL",
          sequence_no: 1,
          sender_role: "USER",
          message_text: "Initial user thread.",
        },
      ],
    });

    await wrapper.setProps({ viewerRole: "MERCHANT" });
    await wrapper.setProps({ viewerRole: "USER" });

    merchantMessages.resolve([
      {
        id: "MERCHANT_STALE",
        sequence_no: 1,
        sender_role: "MERCHANT",
        message_text: "Stale merchant thread must not overwrite user view.",
      },
    ]);
    await flushPromises();

    expect(wrapper.text()).not.toContain("Stale merchant thread must not overwrite user view.");

    userMessages.resolve([
      {
        id: "USER_FRESH",
        sequence_no: 2,
        sender_role: "USER",
        message_text: "Fresh user thread wins.",
      },
    ]);
    await flushPromises();

    expect(wrapper.text()).toContain("Fresh user thread wins.");
    expect(wrapper.text()).not.toContain("Stale merchant thread must not overwrite user view.");
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
        evidenceType: "OTHER",
        sourceType: "USER_UPLOAD",
        visibility: "PARTIES",
      }),
    );
  });
});
