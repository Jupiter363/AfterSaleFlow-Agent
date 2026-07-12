import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EvidenceRoomView from "./EvidenceRoomView.vue";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";

const evidenceRoomSource = readFileSync(
  resolve(process.cwd(), "src/views/disputes/EvidenceRoomView.vue"),
  "utf8",
);

vi.mock("../../api/evidence", () => ({
  evidenceApi: {
    catalog: vi.fn(),
    completion: vi.fn(),
    upload: vi.fn(),
    submitBatch: vi.fn(),
    deletePending: vi.fn(),
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
      submission_status: "SUBMITTED",
      submitted_at: "2026-07-03T00:30:00Z",
      submission_batch_id: "BATCH_USER_1",
      parsed_text: "用户开箱时拍摄的表盘划痕照片。",
    },
    {
      evidence_id: "EVIDENCE_USER_PENDING",
      evidence_type: "LOGISTICS_PROOF",
      submitted_by_role: "USER",
      visibility: "PRIVATE",
      content_url: "/objects/logistics-pending.png",
      redacted: false,
      verification_status: "PENDING",
      original_filename: "logistics-pending.png",
      submission_status: "PENDING_SUBMISSION",
    },
    {
      evidence_id: "EVIDENCE_MERCHANT_SUBMITTED",
      evidence_type: "DELIVERY_RECORD",
      submitted_by_role: "MERCHANT",
      visibility: "PRIVATE",
      content_url: "/objects/merchant-delivery.pdf",
      redacted: false,
      verification_status: "SUSPICIOUS",
      submission_status: "SUBMITTED",
      submitted_at: "2026-07-03T00:40:00Z",
      submission_batch_id: "BATCH_MERCHANT_1",
    },
    {
      evidence_id: "EVIDENCE_REJECTED",
      evidence_type: "OTHER",
      submitted_by_role: "USER",
      visibility: "PRIVATE",
      content_url: null,
      redacted: false,
      verification_status: "REJECTED",
      submission_status: "SUBMITTED",
      submitted_at: "2026-07-03T00:45:00Z",
      submission_batch_id: "BATCH_USER_2",
    },
    {
      evidence_id: "EVIDENCE_REVIEW",
      evidence_type: "VIDEO",
      submitted_by_role: "USER",
      visibility: "PRIVATE",
      content_url: null,
      redacted: false,
      verification_status: "NEEDS_HUMAN_REVIEW",
      submission_status: "SUBMITTED",
      submitted_at: "2026-07-03T00:50:00Z",
      submission_batch_id: "BATCH_USER_3",
    },
  ],
};

function stressCatalog(role, count = 100, filenameFactory = null) {
  return {
    case_id: "CASE_EVIDENCE_1",
    initiator_role: role,
    items: Array.from({ length: count }, (_, index) => ({
      evidence_id: `EVIDENCE_${role}_${String(index + 1).padStart(3, "0")}`,
      evidence_type: index % 2 === 0 ? "DELIVERY_RECORD" : "CHAT_SCREENSHOT",
      submitted_by_role: role,
      visibility: "PRIVATE",
      content_url: null,
      redacted: false,
      verification_status: index % 3 === 0 ? "NEEDS_HUMAN_REVIEW" : "VERIFIED",
      confidence_score: 0.88,
      confidence_level: "HIGH",
      verification_feedback:
        `书记官核验说明 ${index + 1}：` + "来源完整性与时间线需要逐项核对。".repeat(8),
      original_filename: filenameFactory
        ? filenameFactory(index)
        : `${role.toLowerCase()}-evidence-${index + 1}.pdf`,
      submission_status: "SUBMITTED",
      submitted_at: "2026-07-03T00:30:00Z",
      submission_batch_id: `BATCH_${role}_${index + 1}`,
    })),
  };
}

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

async function mountView(overrides = {}, mountOptions = {}) {
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
    ...mountOptions,
  });
  return { wrapper, router, completeAction };
}

describe("EvidenceRoomView", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    evidenceApi.catalog.mockResolvedValue(catalog);
    evidenceApi.completion.mockResolvedValue(initialCompletion);
    evidenceApi.upload.mockResolvedValue({});
    evidenceApi.submitBatch.mockResolvedValue({
      batch_id: "EVIDENCE_BATCH_1",
      evidence_ids: ["EVIDENCE_USER_PENDING"],
      room_message: {
        id: "BATCH_MESSAGE",
        sequence_no: 3,
        sender_role: "USER",
        message_type: "PARTY_EVIDENCE_REFERENCE",
        message_text: "用户提交了 1 份证据材料。",
        attachment_refs: ["EVIDENCE_USER_PENDING"],
      },
    });
    evidenceApi.deletePending.mockResolvedValue({});
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
    expect(wrapper.get(".evidence-room__case-note").text()).toContain(
      "发起争议方须至少正式提交 1 份相关证据；另一方可提交材料，或等待举证时效结束。",
    );
  });

  it("encodes the fixed board budget, single list scroll rail, and approved breakpoints", () => {
    expect(evidenceRoomSource).toContain("--evidence-panel-height: 740px");
    expect(evidenceRoomSource).toMatch(
      /grid-template-rows:\s*76px 86px minmax\(0, 1fr\) 60px/,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-board__list\s*\{[^}]*overflow-y:\s*auto/s,
    );
    expect(evidenceRoomSource).not.toMatch(
      /\.evidence-library\s*\{[^}]*overflow(?:-y)?:\s*(?:auto|scroll)/s,
    );
    expect(evidenceRoomSource).toMatch(
      /@container room-workspace \(max-width: 1059px\)/,
    );
    expect(evidenceRoomSource).not.toMatch(
      /@container room-workspace \(max-width: 1119px\)/,
    );
    expect(evidenceRoomSource).not.toMatch(/@media \(max-width: 1059px\)/);
    expect(evidenceRoomSource).not.toMatch(/@media \(max-width: 1060px\)/);
    expect(evidenceRoomSource).toMatch(/@media \(max-width: 360px\)/);
    expect(evidenceRoomSource).toMatch(
      /grid-template-rows:\s*88px 96px minmax\(0, 1fr\) 72px/,
    );
    expect(evidenceRoomSource).toMatch(/:deep\(\.room-shell__header\)/);
    expect(evidenceRoomSource).toMatch(
      /:deep\(\.room-shell__boundary\)[^{]*\{[^}]*overflow-wrap:\s*anywhere/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-footer\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto/s,
    );
  });

  it("keeps compact footer controls horizontal and overlays errors outside the board tracks", () => {
    const compactStyles = evidenceRoomSource.match(
      /@media \(max-width: 620px\)([\s\S]*?)@media \(max-width: 360px\)/,
    )?.[1];

    expect(compactStyles).toBeTruthy();
    expect(compactStyles).toMatch(
      /\.evidence-footer\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto/s,
    );
    expect(compactStyles).not.toMatch(
      /\.evidence-footer[^}]*grid-template-columns:\s*1fr/s,
    );
    expect(compactStyles).toMatch(
      /\.evidence-uploader\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto/s,
    );
    expect(compactStyles).toMatch(
      /\.evidence-uploader__illustration,\s*\.evidence-uploader \.evidence-kicker\s*\{[^}]*display:\s*none/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-error\s*\{[^}]*position:\s*absolute/s,
    );
  });

  it("reserves a real 44px touch target for uploader, footer, and modal actions", () => {
    expect(evidenceRoomSource).toMatch(
      /\.evidence-uploader__button,\s*\.evidence-footer button\s*\{[^}]*min-height:\s*44px[^}]*display:\s*inline-flex/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__panel button,\s*\.evidence-modal__link\s*\{[^}]*min-height:\s*44px[^}]*display:\s*inline-flex/s,
    );
  });

  it.each(["USER", "MERCHANT"])(
    "renders 100 %s evidence cards inside the single list rail without moving the footer",
    async (viewerRole) => {
      const { wrapper } = await mountView({
        viewerRole,
        initialCatalog: stressCatalog(viewerRole),
      });

      const listRail = wrapper.get("[data-evidence-list-scroll]");
      expect(listRail.findAll("[data-evidence-card]")).toHaveLength(100);
      expect(listRail.find(".evidence-footer").exists()).toBe(false);
      expect(wrapper.get("[data-evidence-board-panel] > .evidence-footer").exists()).toBe(true);
      expect(wrapper.findAll("[data-evidence-status-row]")).toHaveLength(100);
      expect(wrapper.get("[data-evidence-status-row]").text()).toContain("已提交");
      expect(wrapper.get("[data-evidence-status-row]").text()).toContain("88% · 高置信");
    },
  );

  it("keeps a 200-character unbroken filename inspectable and long verification text on its own row", async () => {
    const filename = `${"证据原件无空格长文件名".repeat(20).slice(0, 200)}.pdf`;
    const longCatalog = stressCatalog("USER", 1, () => filename);
    const { wrapper } = await mountView({ initialCatalog: longCatalog });

    const card = wrapper.get("[data-evidence-card]");
    const filenameNode = card.get("[data-evidence-filename]");
    const description = card.get("[data-evidence-description]");

    expect(filenameNode.attributes("title")).toBe(filename);
    expect(filenameNode.text()).toBe(filename);
    expect(description.text()).toContain("来源完整性与时间线需要逐项核对");
    expect(evidenceRoomSource).toMatch(
      /\.evidence-card__description\s*\{[^}]*grid-column:\s*1\s*\/\s*-1[^}]*overflow-wrap:\s*anywhere/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-card__filename\s*\{[^}]*text-overflow:\s*ellipsis[^}]*white-space:\s*nowrap/s,
    );

    await card.trigger("click");

    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain(filename);
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__facts span\s*\{[^}]*min-width:\s*0[^}]*overflow-wrap:\s*anywhere[^}]*word-break:\s*break-word/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__panel header > div\s*\{[^}]*min-width:\s*0/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__panel h2\s*\{[^}]*overflow-wrap:\s*anywhere[^}]*word-break:\s*break-word/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__panel p\s*\{[^}]*min-width:\s*0[^}]*white-space:\s*pre-wrap[^}]*overflow-wrap:\s*anywhere[^}]*word-break:\s*break-word/s,
    );
  });

  it("keeps the narrow room header, countdown, AI notice, and conversation chain wrap-safe", () => {
    expect(evidenceRoomSource).toMatch(
      /@media \(max-width: 360px\)[\s\S]*:deep\(\.room-shell__header\)[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\) auto/,
    );
    expect(evidenceRoomSource).toMatch(
      /:deep\(\[data-evidence-countdown\]\)[^{]*\{[^}]*white-space:\s*nowrap/s,
    );
    expect(evidenceRoomSource).toMatch(
      /:deep\(\.conversation-stream__message\)[^{]*\{[^}]*min-width:\s*0[^}]*overflow-wrap:\s*anywhere/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-card__meta\s*\{[^}]*flex-wrap:\s*wrap/s,
    );
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

  it("renders multimodal assessment confidence and a read-only human review card", async () => {
    const reviewCatalog = {
      ...catalog,
      items: [
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_VISUAL_REVIEW",
          original_filename: "商品划痕细节照片.png",
          verification_status: "NEEDS_HUMAN_REVIEW",
          authenticity_score: 0.74,
          relevance_score: 0.93,
          completeness_score: 0.58,
          assessment_confidence: 0.67,
          confidence_score: 0.99,
          inspected_modalities: ["IMAGE", "OCR"],
          limitations: ["单张照片无法排除光线反射对划痕形态的影响。"],
          requires_human_review: true,
          human_review_reason_codes: ["VISUAL_DETAIL_UNCERTAIN"],
          human_review_instructions: ["对照原图检查划痕边缘、反光和拍摄时间。"],
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: reviewCatalog });

    const submittedCard = wrapper.get("[data-evidence-originals] [data-evidence-card]");
    expect(submittedCard.get("[data-evidence-confidence]").text()).toContain("核验把握 67%");

    const queue = wrapper.get("[data-human-review-queue]");
    expect(queue.text()).toContain("待人工审核");
    const reviewCard = queue.get("[data-human-review-card]");
    expect(reviewCard.text()).toContain("商品划痕细节照片.png");
    expect(reviewCard.text()).toContain("真实性74%");
    expect(reviewCard.text()).toContain("关联性93%");
    expect(reviewCard.text()).toContain("完整性58%");
    expect(reviewCard.text()).toContain("核验把握67%");
    expect(reviewCard.text()).toContain("图片或视频细节无法由模型可靠判定");
    expect(reviewCard.text()).toContain("单张照片无法排除光线反射");
    expect(reviewCard.text()).toContain("对照原图检查划痕边缘");
    expect(reviewCard.find("button").exists()).toBe(false);

    await submittedCard.trigger("click");
    const detail = wrapper.get("[data-evidence-detail-modal]");
    expect(detail.get("[data-evidence-detail-assessment]").text()).toContain("真实性74%");
    expect(detail.get("[data-evidence-detail-assessment]").text()).toContain("已检查模态：IMAGE、OCR");
    expect(detail.get("[data-evidence-detail-human-review]").text()).toContain("审核指引");
  });

  it("accepts camelCase assessment fields and requiresHumanReview independently of status", async () => {
    const camelCaseCatalog = {
      ...catalog,
      items: [
        {
          evidenceId: "EVIDENCE_CAMEL_REVIEW",
          evidenceType: "CHAT_SCREENSHOT",
          submittedByRole: "USER",
          contentUrl: "/objects/chat.png",
          originalFilename: "聊天记录.png",
          submissionStatus: "SUBMITTED",
          verificationStatus: "PLAUSIBLE",
          authenticityScore: 81,
          relevanceScore: 88,
          completenessScore: 72,
          assessmentConfidence: 76,
          inspectedModalities: ["IMAGE", "TEXT"],
          limitations: "聊天参与方身份仍需核对。",
          requiresHumanReview: true,
          humanReviewReasonCodes: ["SOURCE_PROVENANCE_UNVERIFIED"],
          humanReviewInstructions: ["核对账号主体和完整上下文。"],
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: camelCaseCatalog });

    const reviewCard = wrapper.get("[data-human-review-card]");
    expect(reviewCard.text()).toContain("聊天记录.png");
    expect(reviewCard.text()).toContain("材料来源或流转链路尚未核实");
    expect(reviewCard.text()).toContain("聊天参与方身份仍需核对");
    expect(wrapper.get("[data-evidence-confidence]").text()).toContain("76%");
  });

  it("shows every party human-review item to the platform reviewer", async () => {
    const reviewerCatalog = {
      ...catalog,
      items: [
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_USER_REVIEW",
          original_filename: "用户聊天截图.png",
          submission_status: "SUBMITTED",
          submitted_by_role: "USER",
          requires_human_review: true,
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_MERCHANT_REVIEW",
          original_filename: "商家发货照片.png",
          submission_status: "SUBMITTED",
          submitted_by_role: "MERCHANT",
          requires_human_review: true,
        },
      ],
    };
    const { wrapper } = await mountView({
      initialCatalog: reviewerCatalog,
      viewerRole: "PLATFORM_REVIEWER",
    });

    const queue = wrapper.get("[data-human-review-queue]");
    expect(queue.findAll("[data-human-review-card]")).toHaveLength(2);
    expect(queue.text()).toContain("用户聊天截图.png");
    expect(queue.text()).toContain("商家发货照片.png");
  });

  it("keeps one board scroll rail and expands human-review cards inside it", () => {
    expect(evidenceRoomSource).toMatch(
      /\.human-review-list\s*\{[^}]*display:\s*grid/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.human-review-card__body\s*\{[^}]*display:\s*grid/s,
    );
    expect(evidenceRoomSource).not.toMatch(
      /\.human-review-card__body\s*\{[^}]*overflow-y:\s*(?:auto|scroll)/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__review-scroll\s*\{[^}]*max-height:\s*230px[^}]*overflow-y:\s*auto/s,
    );
  });

  it("shows a dismissible modal when the dispute initiator has no submitted evidence", async () => {
    const emptyInitiatorCatalog = {
      ...catalog,
      initiator_role: "USER",
      items: catalog.items.filter(
        (item) =>
          item.submitted_by_role !== "USER" ||
          item.submission_status !== "SUBMITTED",
      ),
    };
    const completeAction = vi.fn();
    const { wrapper } = await mountView({
      initialCatalog: emptyInitiatorCatalog,
      completeAction,
    });

    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    expect(completeAction).not.toHaveBeenCalled();
    const modal = wrapper.get("[data-evidence-gate-modal]");
    expect(modal.attributes("role")).toBe("dialog");
    expect(modal.attributes("aria-modal")).toBe("true");
    expect(modal.text()).toContain("暂不能完成举证");
    expect(modal.text()).toContain(
      "发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。",
    );
    expect(wrapper.find('.evidence-board [role="alert"]').exists()).toBe(false);

    await modal.get("[data-dismiss-evidence-gate]").trigger("click");

    expect(wrapper.find("[data-evidence-gate-modal]").exists()).toBe(false);
  });

  it("moves focus into the evidence gate, traps Tab, closes on Escape, and restores the completion trigger", async () => {
    const emptyInitiatorCatalog = {
      ...catalog,
      initiator_role: "USER",
      items: catalog.items.filter(
        (item) =>
          item.submitted_by_role !== "USER" ||
          item.submission_status !== "SUBMITTED",
      ),
    };
    const { wrapper } = await mountView(
      { initialCatalog: emptyInitiatorCatalog },
      { attachTo: document.body },
    );
    const trigger = wrapper.get("[data-complete-evidence]").element;
    trigger.focus();

    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    const dismiss = wrapper.get("[data-dismiss-evidence-gate]").element;
    expect(document.activeElement).toBe(dismiss);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(dismiss);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        shiftKey: true,
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(dismiss);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Escape",
        bubbles: true,
        cancelable: true,
      }),
    );
    await flushPromises();

    expect(wrapper.find("[data-evidence-gate-modal]").exists()).toBe(false);
    expect(document.activeElement).toBe(trigger);
    wrapper.unmount();
  });

  it("allows a non-initiating party to complete without submitted evidence", async () => {
    const emptyMerchantCatalog = {
      ...catalog,
      initiator_role: "USER",
      items: catalog.items.filter(
        (item) => item.submitted_by_role !== "MERCHANT",
      ),
    };
    const completeAction = vi.fn().mockResolvedValue({
      completed_role: "MERCHANT",
      all_parties_completed: false,
      next_room: "EVIDENCE",
    });
    const { wrapper } = await mountView({
      initialCatalog: emptyMerchantCatalog,
      viewerRole: "MERCHANT",
      completeAction,
    });

    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    expect(completeAction).toHaveBeenCalledOnce();
    expect(wrapper.find("[data-evidence-gate-modal]").exists()).toBe(false);
  });

  it("opens a submitted evidence detail modal from a card", async () => {
    const { wrapper } = await mountView();

    await wrapper
      .get("[data-evidence-originals] [data-evidence-card]")
      .trigger("click");

    const modal = wrapper.get("[data-evidence-detail-modal]");
    expect(modal.text()).toContain("EVIDENCE_USER_PRIVATE");
    expect(modal.text()).toContain("92%");
    expect(modal.text()).toContain("原始截图来源清晰");
    expect(modal.text()).toContain("user-original.png");

    await modal.get("[data-close-evidence-modal]").trigger("click");
    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
  });

  it("does not open evidence detail when keyboard activation comes from nested actions", async () => {
    const { wrapper } = await mountView({}, { attachTo: document.body });

    await wrapper.get("[data-delete-pending-evidence]").trigger("keydown", {
      key: "Enter",
    });
    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);

    await wrapper.get("[data-expand-submitted-evidence]").trigger("click");
    await flushPromises();
    await wrapper.get("[data-gallery-download-evidence]").trigger("keydown", {
      key: "Enter",
    });

    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
    wrapper.unmount();
  });

  it("keeps gallery and detail as a focus-restoring modal stack", async () => {
    const { wrapper } = await mountView({}, { attachTo: document.body });
    const galleryTrigger = wrapper.get("[data-expand-submitted-evidence]").element;
    galleryTrigger.focus();

    await wrapper.get("[data-expand-submitted-evidence]").trigger("click");
    await flushPromises();

    const gallery = wrapper.get("[data-evidence-gallery-modal]");
    const galleryClose = gallery.get("[data-close-evidence-gallery]").element;
    expect(document.activeElement).toBe(galleryClose);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        shiftKey: true,
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(
      gallery.findAll("[data-evidence-gallery-card]").at(-1).element,
    );

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(galleryClose);

    const galleryCard = gallery.get("[data-evidence-gallery-card]");
    await galleryCard.trigger("click");
    await flushPromises();

    const detail = wrapper.get("[data-evidence-detail-modal]");
    const detailClose = detail.get("[data-close-evidence-modal]").element;
    const detailDownload = detail.get("[data-download-evidence]").element;
    expect(document.activeElement).toBe(detailClose);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(detailDownload);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(detailClose);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        shiftKey: true,
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(detailDownload);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Tab",
        bubbles: true,
        cancelable: true,
      }),
    );
    expect(document.activeElement).toBe(detailClose);
    expect(Number(detail.attributes("data-modal-depth"))).toBeGreaterThan(
      Number(gallery.attributes("data-modal-depth")),
    );
    expect(gallery.attributes("aria-hidden")).toBe("true");

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Escape",
        bubbles: true,
        cancelable: true,
      }),
    );
    await flushPromises();

    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
    expect(wrapper.find("[data-evidence-gallery-modal]").exists()).toBe(true);
    expect(document.activeElement).toBe(galleryCard.element);

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Escape",
        bubbles: true,
        cancelable: true,
      }),
    );
    await flushPromises();

    expect(wrapper.find("[data-evidence-gallery-modal]").exists()).toBe(false);
    expect(document.activeElement).toBe(galleryTrigger);
    wrapper.unmount();
  });

  it("removes the modal keyboard listener when the view unmounts", async () => {
    const addSpy = vi.spyOn(document, "addEventListener");
    const removeSpy = vi.spyOn(document, "removeEventListener");
    const { wrapper } = await mountView({}, { attachTo: document.body });

    await wrapper.get("[data-expand-submitted-evidence]").trigger("click");
    await flushPromises();

    const keydownRegistration = addSpy.mock.calls.find(
      ([eventName]) => eventName === "keydown",
    );
    expect(keydownRegistration).toBeTruthy();

    wrapper.unmount();

    expect(removeSpy).toHaveBeenCalledWith(
      "keydown",
      keydownRegistration[1],
    );
    addSpy.mockRestore();
    removeSpy.mockRestore();
  });

  it("uses reusable document image and video icons for evidence files", async () => {
    const iconCatalog = {
      ...catalog,
      items: [
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_ICON_PDF",
          evidence_type: "DOCUMENT",
          original_filename: "物流签收证明.pdf",
          content_url: "/objects/delivery-proof.pdf",
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_ICON_WORD",
          evidence_type: "DOCUMENT",
          original_filename: "商家质检说明.docx",
          content_url: "/objects/merchant-qc.docx",
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_ICON_MD",
          evidence_type: "DOCUMENT",
          original_filename: "客服对话记录.md",
          content_url: "/objects/chat-record.md",
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_ICON_IMAGE",
          evidence_type: "IMAGE",
          original_filename: "开箱划痕照片.png",
          content_url: "/objects/scratch-photo.png",
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_ICON_VIDEO",
          evidence_type: "VIDEO",
          original_filename: "发货质检视频.mp4",
          content_url: "/objects/qc-video.mp4",
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: iconCatalog });

    expect(wrapper.find('[data-file-kind="pdf"]').exists()).toBe(true);
    expect(wrapper.find('[data-file-kind="word"]').exists()).toBe(true);
    expect(wrapper.find('[data-file-kind="markdown"]').exists()).toBe(true);
    expect(wrapper.find('[data-file-kind="image"]').exists()).toBe(true);
    expect(wrapper.find('[data-file-kind="video"]').exists()).toBe(true);
    expect(
      wrapper
        .findAll("[data-file-badge]")
        .map((node) => node.text()),
    ).toEqual(expect.arrayContaining(["PDF", "DOC", "MD", "IMG", "VID"]));
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
    expect(wrapper.text()).toContain("物流显示签收但用户称未收到包裹");
    expect(wrapper.text()).not.toContain("SIGNED_NOT_RECEIVED");
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

  it("separates pending evidence from submitted originals and removes the shared wall", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("证据书记官");
    expect(wrapper.get("[data-evidence-pending]").text()).toContain(
      "logistics-pending.png",
    );
    expect(wrapper.get("[data-evidence-originals]").text()).toContain(
      "user-original.png",
    );
    expect(wrapper.get("[data-evidence-originals]").text()).not.toContain(
      "logistics-pending.png",
    );
    expect(wrapper.find("[data-evidence-shared]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain(
      "EVIDENCE_MERCHANT_SUBMITTED",
    );
    for (const status of [
      "VERIFIED",
      "REJECTED",
      "NEEDS_HUMAN_REVIEW",
    ]) {
      expect(wrapper.find(`[data-verification="${status}"]`).exists()).toBe(true);
    }
    expect(wrapper.get("[data-evidence-countdown]").text()).toContain("02:00:00");
  });

  it("keeps platform-only evidence out of the party evidence room", async () => {
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
          submission_status: "SUBMITTED",
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: platformCatalog });

    expect(wrapper.text()).not.toContain(
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
    expect(wrapper.get("[data-evidence-originals]").text()).not.toContain(
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

  it("uploads into the pending batch card without interrupting the evidence clerk", async () => {
    const refreshedCatalog = {
      ...catalog,
      items: [
        ...catalog.items,
        {
          evidence_id: "EVIDENCE_MARKDOWN_UPLOAD",
          evidence_type: "OTHER",
          submitted_by_role: "MERCHANT",
          visibility: "PRIVATE",
          content_url: "/objects/merchant-notes.md",
          redacted: false,
          verification_status: "PENDING",
          submission_status: "PENDING_SUBMISSION",
          original_filename: "merchant-notes.md",
        },
      ],
    };
    evidenceApi.catalog.mockResolvedValueOnce(refreshedCatalog);
    evidenceApi.completion.mockResolvedValueOnce({
      ...initialCompletion,
      merchant_completed: false,
    });
    roomApi.messages.mockResolvedValueOnce([]);
    const { wrapper } = await mountView({ viewerRole: "MERCHANT" });
    await wrapper
      .get(".evidence-uploader__model-consent input")
      .setValue(true);
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
    expect(uploadCommand.visibility).toBe("PRIVATE");
    expect(uploadCommand.modelProcessingAuthorized).toBe(true);
    expect(
      wrapper.get(".evidence-uploader__model-consent input").element.checked,
    ).toBe(false);
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
    expect(wrapper.get("[data-evidence-pending]").text()).toContain("merchant-notes.md");
    expect(wrapper.text()).not.toContain("Markdown evidence has been indexed by the clerk.");
  });

  it("submits pending evidence as a batch and then waits for the clerk feedback", async () => {
    const afterSubmitCatalog = {
      ...catalog,
      items: catalog.items.map((item) =>
        item.evidence_id === "EVIDENCE_USER_PENDING"
          ? {
              ...item,
              submission_status: "SUBMITTED",
              submitted_at: "2026-07-03T01:00:00Z",
              submission_batch_id: "EVIDENCE_BATCH_1",
            }
          : item,
      ),
    };
    const batchMessage = {
      id: "BATCH_MESSAGE",
      sequence_no: 3,
      sender_role: "USER",
      message_type: "PARTY_EVIDENCE_REFERENCE",
      message_text: "用户提交了 1 份证据材料。",
      attachment_refs: ["EVIDENCE_USER_PENDING"],
    };
    const clerkReply = {
      id: "CLERK_AFTER_BATCH",
      sequence_no: 4,
      sender_role: "CUSTOMER_SERVICE",
      message_type: "AGENT_MESSAGE",
      message_text: "书记官已读取本批材料，请补充形成时间。",
    };
    evidenceApi.catalog.mockResolvedValue(afterSubmitCatalog);
    evidenceApi.completion.mockResolvedValue(initialCompletion);
    roomApi.messages
      .mockResolvedValueOnce([batchMessage])
      .mockResolvedValueOnce([batchMessage, clerkReply]);
    const { wrapper } = await mountView({
      agentReplyPollAttempts: 1,
      agentReplyPollDelayMs: 0,
    });

    await wrapper.get("[data-submit-evidence-batch]").trigger("click");
    await flushPromises();
    await flushPromises();

    expect(evidenceApi.submitBatch).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      { evidence_ids: ["EVIDENCE_USER_PENDING"], batch_note: "" },
      expect.any(String),
    );
    expect(wrapper.get("[data-evidence-originals]").text()).toContain(
      "logistics-pending.png",
    );
    expect(wrapper.text()).toContain("书记官已读取本批材料");
  });

  it("deletes only pending evidence from the staging card", async () => {
    const afterDeleteCatalog = {
      ...catalog,
      items: catalog.items.filter((item) => item.evidence_id !== "EVIDENCE_USER_PENDING"),
    };
    evidenceApi.catalog.mockResolvedValueOnce(afterDeleteCatalog);
    evidenceApi.completion.mockResolvedValueOnce(initialCompletion);
    roomApi.messages.mockResolvedValueOnce([]);
    const { wrapper } = await mountView();

    await wrapper.get("[data-delete-pending-evidence]").trigger("click");
    await flushPromises();

    expect(evidenceApi.deletePending).toHaveBeenCalledWith(
      expect.objectContaining({ role: "USER" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE_USER_PENDING",
    );
    expect(wrapper.find("[data-evidence-pending]").text()).not.toContain(
      "EVIDENCE_USER_PENDING",
    );
    expect(wrapper.get("[data-evidence-originals]").text()).toContain(
      "user-original.png",
    );
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
        visibility: "PRIVATE",
      }),
    );
  });
});
