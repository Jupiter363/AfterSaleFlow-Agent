// 文件作用：自动化测试文件，验证 EvidenceRoomView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EvidenceRoomView from "./EvidenceRoomView.vue";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";
import { getAgentStreamRun } from "../../stores/agentStream";

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
    latestTurnMemory: vi.fn(),
    messages: vi.fn(),
    postMessage: vi.fn(),
  },
}));

const catalog = {
  case_id: "CASE_EVIDENCE_1",
  initiator_role: "USER",
  initiator_id: "user-local",
  items: [
    {
      evidence_id: "EVIDENCE_USER_PRIVATE",
      evidence_type: "CHAT_SCREENSHOT",
      submitted_by_role: "USER",
      submitted_by_id: "user-local",
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
      submitted_by_id: "user-local",
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
      submitted_by_id: "merchant-local",
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
      submitted_by_id: "user-local",
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
      submitted_by_id: "user-local",
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

// 业务位置：【前端证据室】stressCatalog：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function stressCatalog(role, count = 100, filenameFactory = null) {
  return {
    case_id: "CASE_EVIDENCE_1",
    initiator_role: role,
    initiator_id: role === "MERCHANT" ? "merchant-local" : "user-local",
    items: Array.from({ length: count }, (_, index) => ({
      evidence_id: `EVIDENCE_${role}_${String(index + 1).padStart(3, "0")}`,
      evidence_type: index % 2 === 0 ? "DELIVERY_RECORD" : "CHAT_SCREENSHOT",
      submitted_by_role: role,
      submitted_by_id: role === "MERCHANT" ? "merchant-local" : "user-local",
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

const initialEvidenceMessages = [
  {
    id: "EVIDENCE_OPENING",
    sequence_no: 1,
    sender_role: "CUSTOMER_SERVICE",
    message_type: "AGENT_MESSAGE",
    message_text: "请先补充与接待室案情匹配的证据来源、形成时间和原件。",
  },
];

const evidenceTurnMemory = {
  case_intake_dossier: {
    dossier: {
      schema_version: "intake_case_detail.v1",
      case_fact_matrix: {
        schema_version: "case_fact_matrix.v2",
        fact_rows: [
          {
            fact_id: "FACT_INTAKE_32C8D9067B3377209DBD",
            fact_target: "冷链运输状态及商品收货时状况",
          },
          {
            fact_id: "FACT_INTAKE_8609A837FBFD8A7896B2",
            fact_target: "用户是否已经食用涉案商品",
          },
        ],
      },
    },
  },
};

const connectedModelHealth = vi.fn().mockResolvedValue({
  status: "UP",
  model_status: "CONNECTED",
});

// 业务位置：【前端证据室】deferred：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((innerResolve, innerReject) => {
    resolve = innerResolve;
    reject = innerReject;
  });
  return { promise, resolve, reject };
}

// 业务位置：【前端证据室】routerForEvidence：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function routerForEvidence() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes/:caseId/evidence", component: { template: "<div />" } },
      { path: "/disputes/:caseId/hearing", component: { template: "<div />" } },
    ],
  });
}

// 业务位置：【前端证据室】mountView：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function mountView(overrides = {}, mountOptions = {}) {
  const { historyMode: openAsHistory = false, ...viewOverrides } = overrides;
  const router = routerForEvidence();
  await router.push(
    openAsHistory
      ? "/disputes/CASE_EVIDENCE_1/evidence?view=history"
      : "/disputes/CASE_EVIDENCE_1/evidence",
  );
  await router.isReady();
  const completeAction = viewOverrides.completeAction || vi.fn();
  const wrapper = mount(EvidenceRoomView, {
    props: {
      initialCatalog: catalog,
      initialCompletion,
      deadlineAt: "2026-07-03T14:00:00+08:00",
      serverNow: "2026-07-03T12:00:00+08:00",
      viewerRole: "USER",
      initialMessages: initialEvidenceMessages,
      completeAction,
      modelHealthLoader: connectedModelHealth,
      ...viewOverrides,
    },
    global: { plugins: [router] },
    ...mountOptions,
  });
  await flushPromises();
  return { wrapper, router, completeAction };
}

// 业务位置：【前端证据室】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
describe("EvidenceRoomView", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    connectedModelHealth.mockResolvedValue({
      status: "UP",
      model_status: "CONNECTED",
    });
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
    roomApi.latestTurnMemory.mockResolvedValue(evidenceTurnMemory);
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

  it("keeps historical evidence readable while locking every write control", async () => {
    const eventStreamer = vi.fn();
    const completeAction = vi.fn();
    const { wrapper } = await mountView({
      historyMode: true,
      eventStreamer,
      completeAction,
    });

    expect(wrapper.get("[data-room-history-banner]").text()).toContain("历史浏览模式");
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.get("[data-open-evidence-upload]").attributes("disabled")).toBeDefined();
    expect(wrapper.find("[data-evidence-upload-modal]").exists()).toBe(false);
    expect(wrapper.get("[data-complete-evidence]").attributes("disabled")).toBeDefined();
    expect(wrapper.findAll("[data-delete-pending-evidence]").every(
      (button) => button.attributes("disabled") !== undefined,
    )).toBe(true);
    expect(roomApi.ensureOpening).not.toHaveBeenCalled();
    expect(evidenceApi.upload).not.toHaveBeenCalled();
    expect(completeAction).not.toHaveBeenCalled();
    expect(eventStreamer).not.toHaveBeenCalled();
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("renders an intake-like fixed two-panel evidence room", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.get("[data-evidence-room-layout]").exists()).toBe(true);
    expect(wrapper.get("[data-evidence-chat-panel]").exists()).toBe(true);
    expect(wrapper.get("[data-evidence-board-panel]").exists()).toBe(true);
    expect(wrapper.get("[data-evidence-list-scroll]").exists()).toBe(true);
    expect(
      wrapper.get("[data-evidence-board-panel]").find(".evidence-uploader").exists(),
    ).toBe(true);
    expect(wrapper.get(".evidence-room__case-note").text()).toContain("证据书记官已就绪");
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("uses the evidence completion deadline returned by the server", async () => {
    const { wrapper } = await mountView({
      initialCompletion: {
        ...initialCompletion,
        next_deadline_at: "2026-07-03T15:00:00+08:00",
      },
      deadlineAt: "2026-07-03T14:00:00+08:00",
      serverNow: "2026-07-03T12:00:00+08:00",
    });

    expect(wrapper.get(".phase-countdown").text()).toContain("03:00:00");
    expect(wrapper.get(".phase-countdown").attributes("data-awaiting-server")).toBe(
      "false",
    );
  });

  it("maps second-round fact and coverage fields in durable clerk messages", async () => {
    const { wrapper } = await mountView({
      initialMessages: [
        ...initialEvidenceMessages,
        {
          id: "EVIDENCE_SECOND_ROUND",
          sequence_no: 2,
          sender_role: "EVIDENCE_CLERK",
          message_type: "AGENT_MESSAGE",
          message_text:
            "所有关键事实行（FACT_INTAKE_32C8D9067B3377209DBD、FACT_INTAKE_8609A837FBFD8A7896B2）的证据覆盖状态均为“未被庭前冻结证据卷宗覆盖”，且 REQUIRES_HUMAN_REVIEW。",
        },
      ],
    });

    expect(wrapper.text()).toContain("冷链运输状态及商品收货时状况");
    expect(wrapper.text()).toContain("用户是否已经食用涉案商品");
    expect(wrapper.text()).toContain("尚无庭前证据支持");
    expect(wrapper.text()).toContain("需要人工复核");
    expect(wrapper.text()).not.toContain("FACT_INTAKE_");
    expect(wrapper.text()).not.toContain("REQUIRES_HUMAN_REVIEW");
    expect(wrapper.text()).not.toContain("未被庭前冻结证据卷宗覆盖");
  });

  it("encodes a fixed four-card board with horizontal evidence rails", () => {
    expect(evidenceRoomSource).toContain("--evidence-panel-height: 740px");
    expect(evidenceRoomSource).toMatch(
      /\.evidence-board\s*\{[^}]*grid-template-rows:\s*76px minmax\(0, 1fr\) 60px/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-board__cards\s*\{[^}]*overflow:\s*hidden/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-card-strip\s*\{[^}]*overflow-x:\s*auto[^}]*overflow-y:\s*hidden/s,
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
    expect(evidenceRoomSource).toMatch(/:deep\(\.room-shell__header\)/);
    expect(evidenceRoomSource).toMatch(
      /:deep\(\.room-shell__boundary\)[^{]*\{[^}]*overflow-wrap:\s*anywhere/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-footer\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto/s,
    );
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("reserves a real 44px touch target for uploader, footer, and modal actions", () => {
    expect(evidenceRoomSource).toMatch(
      /\.evidence-uploader__button,\s*\.evidence-footer button\s*\{[^}]*min-height:\s*44px[^}]*display:\s*inline-flex/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__panel button,\s*\.evidence-modal__link\s*\{[^}]*min-height:\s*44px[^}]*display:\s*inline-flex/s,
    );
  });

  it.each(["USER", "MERCHANT"])(
    "renders 100 %s evidence cards inside fixed horizontal rails without moving the footer",
    async (viewerRole) => {
      const { wrapper } = await mountView({
        viewerRole,
        initialCatalog: stressCatalog(viewerRole),
      });

      const listRail = wrapper.get("[data-evidence-list-scroll]");
      expect(
        listRail.get("[data-evidence-originals]").findAll("[data-evidence-card]"),
      ).toHaveLength(100);
      expect(listRail.get("[data-human-review-queue]").findAll("[data-human-review-card]")).toHaveLength(34);
      expect(listRail.find(".evidence-footer").exists()).toBe(false);
      expect(wrapper.get("[data-evidence-board-panel] > .evidence-footer").exists()).toBe(true);
      expect(wrapper.findAll("[data-evidence-status-row]")).toHaveLength(100);
      expect(wrapper.get("[data-evidence-status-row]").text()).toContain(
        viewerRole === "MERCHANT" ? "商家提交" : "用户提交",
      );
      expect(wrapper.get("[data-evidence-status-row]").text()).toContain("待人工复核");
    },
  );

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("keeps a 200-character filename inspectable without rendering feedback inside the compact card", async () => {
    const filename = `${"证据原件无空格长文件名".repeat(20).slice(0, 200)}.pdf`;
    const longCatalog = stressCatalog("USER", 1, () => filename);
    const { wrapper } = await mountView({ initialCatalog: longCatalog });

    const card = wrapper.get("[data-evidence-card]");
    const filenameNode = card.get("[data-evidence-filename]");

    expect(filenameNode.attributes("title")).toBe(filename);
    expect(filenameNode.text()).toBe(filename);
    expect(card.find("[data-evidence-description]").exists()).toBe(false);
    expect(evidenceRoomSource).toMatch(
      /\.evidence-card__filename\s*\{[^}]*text-overflow:\s*ellipsis[^}]*white-space:\s*nowrap/s,
    );

    await card.trigger("click");

    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain(filename);
    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain(
      "来源完整性与时间线需要逐项核对",
    );
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
      /\.evidence-card__labels\s*\{[^}]*overflow:\s*hidden/s,
    );
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

    const compactCard = wrapper.get("[data-evidence-originals] [data-evidence-card]");
    expect(compactCard.find("[data-evidence-confidence]").exists()).toBe(false);
    await compactCard.trigger("click");
    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain("31%");
    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain("低置信");
    expect(wrapper.get("[data-complete-evidence]").element.disabled).toBe(false);
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
    expect(submittedCard.text()).toContain("用户提交");
    expect(submittedCard.text()).toContain("待人工复核");
    expect(submittedCard.find("[data-evidence-confidence]").exists()).toBe(false);

    const queue = wrapper.get("[data-human-review-queue]");
    expect(queue.text()).toContain("待人工审核");
    const reviewCard = queue.get("[data-human-review-card]");
    expect(reviewCard.text()).toContain("商品划痕细节照片.png");
    expect(reviewCard.text()).toContain("用户提交");
    expect(reviewCard.text()).toContain("人工审核任务");
    expect(reviewCard.text()).not.toContain("单张照片无法排除光线反射");
    expect(reviewCard.element.tagName).toBe("BUTTON");

    await submittedCard.trigger("click");
    let detail = wrapper.get("[data-evidence-detail-modal]");
    expect(detail.attributes("data-detail-mode")).toBe("evidence");
    expect(detail.get("[data-evidence-detail-assessment]").text()).toContain("真实性74%");
    expect(detail.get("[data-evidence-detail-assessment]").text()).toContain("AI 初步核验");
    expect(detail.text()).not.toContain("解析文本 / OCR");
    expect(detail.find("[data-evidence-detail-human-review]").exists()).toBe(false);

    await detail.get("[data-close-evidence-modal]").trigger("click");
    await reviewCard.trigger("click");
    detail = wrapper.get("[data-evidence-detail-modal]");
    expect(detail.attributes("data-detail-mode")).toBe("human-review");
    expect(detail.get("[data-evidence-detail-human-review]").text()).toContain("审核指引");
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("accepts camelCase assessment fields and requiresHumanReview independently of status", async () => {
    const camelCaseCatalog = {
      ...catalog,
      items: [
        {
          evidenceId: "EVIDENCE_CAMEL_REVIEW",
          evidenceType: "CHAT_SCREENSHOT",
          submittedByRole: "USER",
          submittedById: "user-local",
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
    expect(reviewCard.text()).toContain("人工审核任务");
    expect(reviewCard.text()).not.toContain("聊天参与方身份仍需核对");
    await reviewCard.trigger("click");
    const detail = wrapper.get("[data-evidence-detail-modal]");
    expect(detail.text()).toContain("材料来源或流转链路尚未核实");
    expect(detail.text()).toContain("聊天参与方身份仍需核对");
    expect(detail.text()).toContain("76%");
  });

  it("distinguishes suspected forgery from low relevance in the human review queue", async () => {
    const reasonCatalog = {
      ...catalog,
      items: [
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_LOW_AUTHENTICITY",
          original_filename: "真实性偏低.png",
          authenticity_score: 0.42,
          relevance_score: 0.91,
          requires_human_review: true,
          human_review_reason_codes: ["SUSPECTED_FORGERY_LOW_AUTHENTICITY"],
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_LOW_RELEVANCE",
          original_filename: "关联度偏低.png",
          authenticity_score: 0.88,
          relevance_score: 0.34,
          requires_human_review: true,
          human_review_reason_codes: ["LOW_RELEVANCE_SCORE"],
        },
        {
          ...catalog.items[0],
          evidence_id: "EVIDENCE_LOW_BOTH",
          original_filename: "双项偏低.png",
          authenticity_score: 0.31,
          relevance_score: 0.28,
          requires_human_review: true,
          human_review_reason_codes: [
            "LOW_AUTHENTICITY_SUSPECTED_FORGERY",
            "LOW_RELEVANCE_SCORE",
          ],
        },
      ],
    };
    const { wrapper } = await mountView({ initialCatalog: reasonCatalog });

    const cards = wrapper.findAll("[data-human-review-card]");
    expect(cards).toHaveLength(3);
    expect(cards[0].text()).toContain("疑似造假");
    expect(cards[1].text()).toContain("关联度低");
    expect(cards[1].text()).not.toContain("疑似造假");
    expect(cards[2].text()).toContain("疑似造假");
    expect(cards[2].text()).toContain("关联度低");
    expect(cards[2].findAll("[data-human-review-risk-label]")).toHaveLength(2);

    await cards[0].trigger("click");
    expect(wrapper.get("[data-evidence-detail-human-review]").text()).toContain(
      "疑似造假：真实性评分低于 50%",
    );
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("keeps the board fixed with horizontal evidence rails and a vertical human-review queue", () => {
    expect(evidenceRoomSource).toMatch(
      /\.evidence-board__cards\s*\{[^}]*overflow:\s*hidden/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-card-strip\s*\{[^}]*display:\s*flex[^}]*overflow-x:\s*auto[^}]*overflow-y:\s*hidden/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-library--human-review \.evidence-card-strip\s*\{[^}]*flex-direction:\s*column[^}]*overflow-x:\s*hidden[^}]*overflow-y:\s*auto/s,
    );
    expect(evidenceRoomSource).toMatch(
      /\.evidence-modal__review-scroll\s*\{[^}]*max-height:\s*230px[^}]*overflow-y:\s*auto/s,
    );
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
    expect(wrapper.get(".digital-human").attributes("data-state")).toBe("LISTENING");
    expect(wrapper.text()).not.toContain("证据书记官生成失败");
    expect(wrapper.find('.evidence-board [role="alert"]').exists()).toBe(false);

    await modal.get("[data-dismiss-evidence-gate]").trigger("click");

    expect(wrapper.find("[data-evidence-gate-modal]").exists()).toBe(false);
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("opens a submitted evidence detail modal from a card", async () => {
    const { wrapper } = await mountView();

    await wrapper
      .get("[data-evidence-originals] [data-evidence-card]")
      .trigger("click");

    const modal = wrapper.get("[data-evidence-detail-modal]");
    expect(modal.text()).not.toContain("EVIDENCE_USER_PRIVATE");
    expect(modal.text()).toContain("92%");
    expect(modal.text()).toContain("原始截图来源清晰");
    expect(modal.text()).toContain("user-original.png");

    await modal.get("[data-close-evidence-modal]").trigger("click");
    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("does not open evidence detail when keyboard activation comes from nested actions", async () => {
    const { wrapper } = await mountView({}, { attachTo: document.body });

    await wrapper.get("[data-delete-pending-evidence]").trigger("keydown", {
      key: "Enter",
    });
    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
    wrapper.unmount();
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("opens compact evidence cards directly into a focus-restoring detail modal", async () => {
    const { wrapper } = await mountView({}, { attachTo: document.body });
    const card = wrapper.get("[data-evidence-originals] [data-evidence-card]");
    card.element.focus();
    await card.trigger("click");
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

    document.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Escape",
        bubbles: true,
        cancelable: true,
      }),
    );
    await flushPromises();

    expect(wrapper.find("[data-evidence-detail-modal]").exists()).toBe(false);
    expect(document.activeElement).toBe(card.element);
    wrapper.unmount();
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("removes the modal keyboard listener when the view unmounts", async () => {
    const addSpy = vi.spyOn(document, "addEventListener");
    const removeSpy = vi.spyOn(document, "removeEventListener");
    const { wrapper } = await mountView({}, { attachTo: document.body });

    await wrapper.get("[data-evidence-originals] [data-evidence-card]").trigger("click");
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
    expect(wrapper.find(".evidence-card__preview img").exists()).toBe(false);
    expect(wrapper.find('[data-file-kind="image"]').exists()).toBe(true);
    expect(wrapper.find('[data-file-kind="video"]').exists()).toBe(true);
    expect(
      wrapper
        .findAll("[data-file-badge]")
        .map((node) => node.text()),
    ).toEqual(expect.arrayContaining(["PDF", "DOC", "MD", "IMG", "VID"]));
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  it("opens an independent first evidence turn for the merchant actor", async () => {
    roomApi.messages
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "MERCHANT_EVIDENCE_OPENING",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_type: "AGENT_MESSAGE",
          message_text: "请商家围绕发货质检记录和告知时间补充证据。",
        },
      ]);

    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialMessages: null,
    });
    await flushPromises();

    expect(roomApi.ensureOpening).toHaveBeenCalledTimes(1);
    expect(roomApi.ensureOpening).toHaveBeenCalledWith(
      expect.objectContaining({ id: "merchant-local", role: "MERCHANT" }),
      "CASE_EVIDENCE_1",
      "EVIDENCE",
    );
    expect(wrapper.text()).toContain("请商家围绕发货质检记录");
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("does not append another opening when the private thread already has a current clerk turn", async () => {
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "USER_EXISTING_EVIDENCE_TURN",
        sequence_no: 4,
        sender_role: "USER",
        message_type: "PARTY_TEXT",
        message_text: "I already started this evidence conversation.",
      },
      {
        id: "CLERK_EXISTING_EVIDENCE_TURN",
        sequence_no: 5,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "Please provide the original photo and its capture time.",
      },
    ]);

    const { wrapper } = await mountView({ initialMessages: null });
    await flushPromises();

    expect(roomApi.ensureOpening).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("I already started this evidence conversation.");
  });

  it("repairs a party-only private thread by requesting the missing clerk opening", async () => {
    const partyMessage = {
      id: "USER_PARTY_ONLY_TURN",
      sequence_no: 1,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "This explanation was saved before the opening completed.",
    };
    roomApi.messages
      .mockResolvedValueOnce([partyMessage])
      .mockResolvedValueOnce([
        partyMessage,
        {
          id: "RECOVERED_CLERK_OPENING",
          sequence_no: 2,
          sender_role: "CUSTOMER_SERVICE",
          message_type: "AGENT_MESSAGE",
          message_text: "I have restored the evidence checklist for this private thread.",
        },
      ]);

    const { wrapper } = await mountView({ initialMessages: null });
    await flushPromises();

    expect(roomApi.ensureOpening).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("restored the evidence checklist");
    expect(wrapper.get('[data-send-message] textarea').element.disabled).toBe(false);
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  it("refreshes a delayed merchant-initiated catalog before allowing the user to complete", async () => {
    const missingCatalog = new Error("catalog not found");
    missingCatalog.code = "EVIDENCE_NOT_FOUND";
    const catalogReady = deferred();
    evidenceApi.catalog
      .mockRejectedValueOnce(missingCatalog)
      .mockReturnValueOnce(catalogReady.promise);
    roomApi.messages
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "EVIDENCE_OPENING_BEFORE_CATALOG",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_type: "AGENT_MESSAGE",
          message_text: "Please provide evidence source, timestamp and original file.",
        },
      ]);
    const completeAction = vi.fn().mockResolvedValue({
      completed_role: "USER",
      all_parties_completed: false,
      next_room: "EVIDENCE",
    });

    const { wrapper } = await mountView({
      initialCatalog: null,
      initialMessages: null,
      viewerRole: "USER",
      completeAction,
      eventStreamer: vi.fn(async () => {}),
    });

    expect(wrapper.text()).toContain("正在同步证据目录");
    expect(wrapper.get("[data-complete-evidence]").attributes("disabled")).toBeDefined();
    expect(wrapper.get('[data-send-message] textarea').element.disabled).toBe(true);

    catalogReady.resolve({
      case_id: "CASE_EVIDENCE_1",
      initiator_role: "MERCHANT",
      initiator_id: "merchant-local",
      items: [],
    });
    await flushPromises();
    await flushPromises();

    expect(evidenceApi.catalog).toHaveBeenCalledTimes(2);
    expect(wrapper.get("[data-complete-evidence]").attributes("disabled")).toBeUndefined();
    expect(wrapper.get('[data-send-message] textarea').element.disabled).toBe(false);
    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    expect(completeAction).toHaveBeenCalledOnce();
    expect(wrapper.find("[data-evidence-gate-modal]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("证据书记官生成失败");
  });

  it("retries the catalog after a role switch instead of restoring a roleless snapshot", async () => {
    const missingCatalog = new Error("catalog not found");
    missingCatalog.code = "EVIDENCE_NOT_FOUND";
    evidenceApi.catalog
      .mockRejectedValueOnce(missingCatalog)
      .mockResolvedValueOnce({
        case_id: "CASE_EVIDENCE_1",
        initiator_role: "MERCHANT",
        initiator_id: "merchant-local",
        items: [],
      });
    const completeAction = vi.fn();
    const { wrapper } = await mountView({
      initialCatalog: {
        case_id: "CASE_EVIDENCE_1",
        initiator_role: "USER",
        initiator_id: "user-local",
        items: [],
      },
      completeAction,
    });

    await wrapper.setProps({ viewerRole: "MERCHANT" });
    await flushPromises();
    await flushPromises();

    expect(evidenceApi.catalog).toHaveBeenCalledTimes(2);
    expect(wrapper.get("[data-complete-evidence]").attributes("disabled")).toBeUndefined();
    await wrapper.get("[data-complete-evidence]").trigger("click");
    await flushPromises();

    expect(completeAction).not.toHaveBeenCalled();
    expect(wrapper.get("[data-evidence-gate-modal]").text()).toContain(
      "发起争议方需先正式提交至少 1 份相关证据",
    );
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
    const submittedStatus = wrapper.get(
      "[data-evidence-originals] [data-evidence-status-row]",
    );
    expect(submittedStatus.text()).toContain("用户提交");
    expect(submittedStatus.text()).toContain("待人工复核");
    expect(wrapper.find("[data-verification]").exists()).toBe(false);
    expect(wrapper.get("[data-evidence-countdown]").text()).toContain("02:00:00");
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("shows the merchant completion variant without exposing a user-only original", async () => {
    const { wrapper } = await mountView({ viewerRole: "MERCHANT" });

    expect(wrapper.text()).toContain("商家证据方");
    expect(wrapper.get("[data-evidence-originals]").text()).not.toContain(
      "EVIDENCE_USER_PRIVATE",
    );
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("uploads into the pending batch card without interrupting the evidence clerk", async () => {
    const refreshedCatalog = {
      ...catalog,
      items: [
        ...catalog.items,
        {
          evidence_id: "EVIDENCE_MARKDOWN_UPLOAD",
          evidence_type: "OTHER",
          submitted_by_role: "MERCHANT",
          submitted_by_id: "merchant-local",
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
    await wrapper.get("[data-open-evidence-upload]").trigger("click");
    const input = wrapper.get("[data-evidence-upload-file]");
    const file = new File(["# delivery notes"], "delivery-notes.md", {
      type: "text/markdown",
    });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    await flushPromises();

    expect(input.attributes("multiple")).toBeUndefined();
    expect(evidenceApi.upload).not.toHaveBeenCalled();
    const declaration = wrapper.get("[data-evidence-upload-modal]");
    expect(declaration.text()).toContain("商家 · 被争议方");
    expect(declaration.text()).toContain("人工复核确认证据造假后");
    expect(declaration.text()).toContain("支持并进入执行对方的全部合理诉求");
    expect(declaration.text()).toContain("真实性与相关性承诺");
    await declaration
      .get("[data-evidence-claimed-fact]")
      .setValue("证明商家已在发货前向用户说明商品状态。");
    await declaration.get("[data-evidence-truth-attested]").setValue(true);
    await declaration.get("[data-evidence-upload-form]").trigger("submit");
    await flushPromises();

    const uploadCommand = evidenceApi.upload.mock.calls[0][2];
    expect(uploadCommand.file).toBe(file);
    expect(["OTHER", "DOCUMENT"]).toContain(uploadCommand.evidenceType);
    expect(uploadCommand.sourceType).toBe("MERCHANT_UPLOAD");
    expect(uploadCommand.visibility).toBe("PRIVATE");
    expect(uploadCommand.modelProcessingAuthorized).toBe(true);
    expect(uploadCommand.claimedFact).toBe("证明商家已在发货前向用户说明商品状态。");
    expect(uploadCommand.truthAttested).toBe(true);
    expect(wrapper.find(".evidence-uploader__model-consent").exists()).toBe(false);
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
              verification_status: "PLAUSIBLE",
              verification_feedback: "已映射到待核验事实，仍需补充形成时间。",
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
    const mappedEvidence = wrapper
      .findAll("[data-evidence-card]")
      .find((card) => card.text().includes("logistics-pending.png"));
    await mappedEvidence.trigger("click");
    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain("基本可信");
    expect(wrapper.get("[data-evidence-detail-modal]").text()).toContain("已映射到待核验事实");
    expect(wrapper.text()).toContain("书记官已读取本批材料");
  });

  it("finishes the merchant clerk stream before refreshing the evidence board", async () => {
    const runId = "AGENT_RUN_MERCHANT_EVIDENCE";
    const streamedReply =
      "FACT_INTAKE_32C8D9067B3377209DBD 的覆盖状态为 NOT_COVERED_BY_FROZEN_DOSSIER。";
    let roomSnapshotLoader;
    let streamController;
    const merchantPending = {
      ...catalog.items[1],
      evidence_id: "EVIDENCE_MERCHANT_PENDING",
      submitted_by_role: "MERCHANT",
      submitted_by_id: "merchant-local",
      original_filename: "merchant-pending.pdf",
    };
    const merchantCatalog = {
      ...catalog,
      items: [merchantPending],
    };
    const refreshedCatalog = {
      ...merchantCatalog,
      items: [{
        ...merchantPending,
        submission_status: "SUBMITTED",
        verification_status: "PLAUSIBLE",
      }],
    };
    const batchMessage = {
      id: "MERCHANT_BATCH_MESSAGE",
      sequence_no: 2,
      sender_role: "MERCHANT",
      message_type: "PARTY_EVIDENCE_REFERENCE",
      message_text: "Merchant submitted one evidence item.",
      attachment_refs: [merchantPending.evidence_id],
    };
    evidenceApi.submitBatch.mockResolvedValue({
      room_message: batchMessage,
      agent_run: {
        run_id: runId,
        stream_url: `/api/agent-runs/${runId}/events`,
      },
    });
    roomApi.messages.mockResolvedValue([
      batchMessage,
      {
        id: "MERCHANT_CLERK_REPLY",
        sequence_no: 3,
        sender_role: "EVIDENCE_CLERK",
        message_type: "AGENT_MESSAGE",
        message_text: streamedReply,
        agent_run_id: runId,
      },
    ]);
    evidenceApi.catalog.mockImplementation(() => {
      const run = getAgentStreamRun(runId);
      expect(run?.content).toBe(streamedReply);
      expect(run?.status).toBe("COMPLETED");
      return Promise.resolve(refreshedCatalog);
    });
    const eventStreamer = vi.fn(({ snapshotLoader }) => {
      roomSnapshotLoader = snapshotLoader;
    });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(new ReadableStream({
        start(controller) {
          streamController = controller;
        },
      }), { status: 200, headers: { "Content-Type": "text/event-stream" } }),
    );
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialCatalog: merchantCatalog,
      eventStreamer,
    });

    try {
      await wrapper.get("[data-submit-evidence-batch]").trigger("click");
      streamController.enqueue(new TextEncoder().encode([
        `id: 0\nevent: start\ndata: {"schemaVersion":"agent_stream.v1","runId":"${runId}","sequence":0,"type":"start"}\n\n`,
        `id: 1\nevent: visible_delta\ndata: {"schemaVersion":"agent_stream.v1","runId":"${runId}","sequence":1,"type":"visible_delta","field":"room_utterance","delta":"${streamedReply}"}\n\n`,
      ].join("")));
      await vi.waitFor(() => {
        expect(getAgentStreamRun(runId)?.status).toBe("STREAMING");
      });

      await roomSnapshotLoader();
      expect(evidenceApi.catalog).not.toHaveBeenCalled();

      streamController.enqueue(new TextEncoder().encode(
        `id: 2\nevent: final\ndata: {"schemaVersion":"agent_stream.v1","runId":"${runId}","sequence":2,"type":"final","response":{"room_utterance":"${streamedReply}"}}\n\n`,
      ));
      await vi.waitFor(
        () => expect(evidenceApi.catalog).toHaveBeenCalledTimes(1),
        { timeout: 3000 },
      );
      await flushPromises();

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      expect(getAgentStreamRun(runId)?.status).toBe("COMPLETED");
      expect(wrapper.get("[data-evidence-board-panel]").attributes("data-board-streaming"))
        .toBe("true");
      expect(wrapper.get(".evidence-board__badge").text()).toContain("展板同步 0/1");
      expect(wrapper.get("[data-evidence-pending]").text())
        .toContain("merchant-pending.pdf");
      expect(wrapper.get("[data-evidence-originals]").text())
        .not.toContain("merchant-pending.pdf");

      await vi.waitFor(() => {
        expect(wrapper.find(".evidence-card--stream-updating").exists()).toBe(true);
      });
      await vi.waitFor(() => {
        expect(wrapper.get("[data-evidence-originals]").text())
          .toContain("merchant-pending.pdf");
        expect(wrapper.get("[data-evidence-board-panel]").attributes("data-board-streaming"))
          .toBe("false");
      });
      expect(wrapper.text()).toContain("冷链运输状态及商品收货时状况");
      expect(wrapper.text()).toContain("尚无庭前证据支持");
      expect(wrapper.text()).not.toContain("FACT_INTAKE_");
      expect(wrapper.text()).not.toContain("NOT_COVERED_BY_FROZEN_DOSSIER");
    } finally {
      wrapper.unmount();
      fetchSpy.mockRestore();
    }
  });

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

  // 业务位置：【前端证据室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
  it("does not write a late user message response into the merchant private thread", async () => {
    const lateUserResponse = deferred();
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "MERCHANT_CURRENT_CLERK_TURN",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "Merchant-private evidence checklist.",
      },
    ]);
    const { wrapper } = await mountView({
      viewerRole: "USER",
      messageAction: vi.fn(() => lateUserResponse.promise),
    });

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("User-private explanation still in flight.");
    await wrapper.get("[data-send-message]").trigger("submit");
    await wrapper.setProps({ viewerRole: "MERCHANT" });
    await flushPromises();

    lateUserResponse.resolve({
      id: "LATE_USER_PRIVATE_MESSAGE",
      sequence_no: 8,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "Late user response must stay out of the merchant thread.",
    });
    await flushPromises();

    expect(wrapper.text()).toContain("Merchant-private evidence checklist.");
    expect(wrapper.text()).not.toContain("Late user response must stay out");
  });

  it("does not write a late user batch result into the merchant private thread", async () => {
    const lateBatchResponse = deferred();
    evidenceApi.submitBatch.mockReturnValueOnce(lateBatchResponse.promise);
    roomApi.messages.mockResolvedValueOnce([
      {
        id: "MERCHANT_CURRENT_CLERK_TURN",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "Merchant-private batch checklist.",
      },
    ]);
    const { wrapper } = await mountView({ viewerRole: "USER" });

    await wrapper.get("[data-submit-evidence-batch]").trigger("click");
    await wrapper.setProps({ viewerRole: "MERCHANT" });
    await flushPromises();

    lateBatchResponse.resolve({
      batch_id: "LATE_USER_BATCH",
      evidence_ids: ["EVIDENCE_USER_PENDING"],
      room_message: {
        id: "LATE_USER_BATCH_MESSAGE",
        sequence_no: 9,
        sender_role: "USER",
        message_type: "PARTY_EVIDENCE_REFERENCE",
        message_text: "Late user batch must stay out of the merchant thread.",
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("Merchant-private batch checklist.");
    expect(wrapper.text()).not.toContain("Late user batch must stay out");
    expect(wrapper.get('[data-send-message] textarea').element.disabled).toBe(false);
  });

  it("uploads user evidence with the backend actor-specific source type", async () => {
    const host = document.createElement("div");
    document.body.append(host);
    const { wrapper } = await mountView(
      { viewerRole: "USER" },
      { attachTo: host },
    );
    await wrapper.get("[data-open-evidence-upload]").trigger("click");
    expect(wrapper.get("[data-evidence-upload-modal]").text()).toContain("尚未选择文件");
    const input = wrapper.get("[data-evidence-upload-file]");
    const file = new File(["demo"], "开箱照片.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    await flushPromises();

    expect(evidenceApi.upload).not.toHaveBeenCalled();
    const declaration = wrapper.get("[data-evidence-upload-modal]");
    expect(declaration.get("[data-evidence-upload-guidance]").text()).toContain(
      "文件已选择",
    );
    expect(document.activeElement).toBe(
      declaration.get("[data-evidence-claimed-fact]").element,
    );
    expect(declaration.get("[data-confirm-evidence-upload]").attributes("disabled"))
      .toBeUndefined();
    expect(declaration.text()).toContain("用户 · 争议发起方");
    expect(declaration.text()).toContain("驳回用户的全部诉求");
    expect(declaration.text()).toContain("真实性评分低于 50% 时标记“疑似造假”");
    expect(declaration.text()).toContain("关联性评分低于 50% 时标记“关联度低”并进入人工审核");
    await declaration
      .get("[data-evidence-claimed-fact]")
      .setValue("证明商品开箱时已存在划痕。");
    await declaration.get("[data-evidence-truth-attested]").setValue(true);
    await declaration.get("[data-evidence-upload-form]").trigger("submit");
    await flushPromises();

    expect(evidenceApi.upload).toHaveBeenCalledWith(
      expect.anything(),
      "CASE_EVIDENCE_1",
      expect.objectContaining({
        file,
        evidenceType: "OTHER",
        sourceType: "USER_UPLOAD",
        visibility: "PRIVATE",
        claimedFact: "证明商品开箱时已存在划痕。",
        truthAttested: true,
      }),
    );
    wrapper.unmount();
    host.remove();
  });

  it("rejects a claimed fact shorter than the backend minimum before upload", async () => {
    const { wrapper } = await mountView({ viewerRole: "USER" });
    await wrapper.get("[data-open-evidence-upload]").trigger("click");
    const input = wrapper.get("[data-evidence-upload-file]");
    const file = new File(["demo"], "质检说明.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    const declaration = wrapper.get("[data-evidence-upload-modal]");
    const claimedFact = declaration.get("[data-evidence-claimed-fact]");
    await claimedFact.setValue("质检");
    await declaration.get("[data-evidence-truth-attested]").setValue(true);

    expect(claimedFact.attributes("minlength")).toBe("5");
    expect(declaration.get("[data-confirm-evidence-upload]").attributes("disabled"))
      .toBeUndefined();

    await declaration.get("[data-evidence-upload-form]").trigger("submit");
    await flushPromises();

    expect(evidenceApi.upload).not.toHaveBeenCalled();
    expect(declaration.get("[role=alert]").text()).toContain(
      "证明内容至少填写 5 个字符",
    );
  });

  it("clears the selected file when the upload declaration is cancelled", async () => {
    const { wrapper } = await mountView({ viewerRole: "USER" });
    await wrapper.get("[data-open-evidence-upload]").trigger("click");
    const input = wrapper.get("[data-evidence-upload-file]");
    const file = new File(["demo"], "同名证据.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    expect(wrapper.find("[data-evidence-upload-modal]").exists()).toBe(true);

    await wrapper.get("[data-cancel-evidence-upload]").trigger("click");
    await flushPromises();

    expect(wrapper.find("[data-evidence-upload-modal]").exists()).toBe(false);
    expect(input.element.value).toBe("");
    expect(evidenceApi.upload).not.toHaveBeenCalled();
  });

  it("derives the declaration consequence from initiatorRole instead of a fixed user role", async () => {
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialCatalog: {
        ...catalog,
        initiator_role: "MERCHANT",
        initiator_id: "merchant-local",
      },
    });
    await wrapper.get("[data-open-evidence-upload]").trigger("click");
    const input = wrapper.get("[data-evidence-upload-file]");
    Object.defineProperty(input.element, "files", {
      value: [new File(["demo"], "商家举证.pdf", { type: "application/pdf" })],
      configurable: true,
    });

    await input.trigger("change");

    const declaration = wrapper.get("[data-evidence-upload-modal]");
    expect(declaration.text()).toContain("商家 · 争议发起方");
    expect(declaration.text()).toContain("驳回商家的全部诉求");
    expect(declaration.text()).not.toContain("支持并进入执行对方的全部合理诉求");
  });
});
