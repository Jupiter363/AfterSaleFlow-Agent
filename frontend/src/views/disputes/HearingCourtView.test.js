import { readFileSync } from "node:fs";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { evidenceApi } from "../../api/evidence";
import { hearingApi } from "../../api/hearing";
import { roomApi } from "../../api/rooms";
import HearingCourtView from "./HearingCourtView.vue";

const componentSource = readFileSync("src/views/disputes/HearingCourtView.vue", "utf8");

afterEach(() => {
  vi.restoreAllMocks();
});

const evidenceCatalog = {
  case_id: "CASE_HEARING_1",
  items: [
    {
      evidence_id: "EVIDENCE_USER_REAL",
      evidence_type: "IMAGE",
      submitted_by_role: "USER",
      visibility: "PARTIES",
      content_url: "/api/disputes/CASE_HEARING_1/evidence/EVIDENCE_USER_REAL/content",
      redacted: false,
      verification_status: "PLAUSIBLE",
      confidence_score: 0.86,
      confidence_level: "HIGH",
      verification_feedback: "原始截图时间线与物流节点基本一致。",
      source_type: "USER_UPLOAD",
      original_filename: "用户门口监控截图.jpg",
      parsed_text: "门口监控截图 OCR 摘要",
      submission_status: "SUBMITTED",
    },
    {
      evidence_id: "EVIDENCE_MERCHANT_REAL",
      evidence_type: "DOCUMENT",
      submitted_by_role: "MERCHANT",
      visibility: "PARTIES",
      content_url: "/api/disputes/CASE_HEARING_1/evidence/EVIDENCE_MERCHANT_REAL/content",
      redacted: false,
      verification_status: "VERIFIED",
      confidence_score: 0.9,
      confidence_level: "HIGH",
      verification_feedback: "物流签收底单与运单号匹配。",
      source_type: "MERCHANT_UPLOAD",
      original_filename: "商家物流签收底单.pdf",
      parsed_text: "物流签收底单解析摘要",
      submission_status: "SUBMITTED",
    },
  ],
};

const hearing = {
  rounds: [
    {
      round_id: "ROUND_1",
      round_no: 1,
      status: "COMPLETED",
      dossier_version: 1,
      stop_reason: "EVIDENCE_SUPPLEMENT_REQUIRED",
      summary_json: JSON.stringify({
        clerk: "双方已经提交第一版证据目录。",
        judge: "物流签收凭证仍需交叉核验。",
      }),
    },
    {
      round_id: "ROUND_2",
      round_no: 2,
      status: "OPEN",
      dossier_version: 2,
      round_deadline_at: "2026-07-03T12:05:00+08:00",
      submitted_roles: [],
      current_actor_submitted: false,
      summary_json: JSON.stringify({
        clerk: "补充证据已入卷。",
        judge: "正在形成裁决草案。",
        jury: "建议核对签收人身份。",
      }),
    },
  ],
  settlements: [
    {
      settlement_id: "SETTLEMENT_1",
      version: 1,
      status: "PROPOSED",
      proposed_by_role: "USER",
      proposal_text: "商家退款，用户撤回额外赔偿请求。",
      confirmed_roles: ["USER"],
    },
  ],
};

const courtMessages = [
  {
    id: "MESSAGE_JUDGE_REAL",
    sequence_no: 1,
    sender_role: "JUDGE",
    message_text:
      "根据现有案情，物流记录显示包裹已签收，但用户称未实际收到商品。请双方围绕签收事实进行说明。",
    created_at: "2026-07-03T12:00:00+08:00",
  },
  {
    id: "MESSAGE_USER_REAL",
    sequence_no: 2,
    sender_role: "USER",
    message_text:
      "用户称门口监控未见快递员投递，并已提交截图用于核验包裹实际去向。",
    created_at: "2026-07-03T12:02:00+08:00",
  },
  {
    id: "MESSAGE_MERCHANT_REAL",
    sequence_no: 3,
    sender_role: "MERCHANT",
    message_text:
      "商家称已按订单发货，并需要平台核验物流交接、签收底单与异常工单处理记录。",
    created_at: "2026-07-03T12:03:00+08:00",
  },
  {
    id: "MESSAGE_JURY_REAL",
    sequence_no: 4,
    sender_role: "JURY",
    message_text:
      "中风险 · 当前可信分 75/100 · 建议核验物流轨迹定位与签收凭证。",
    created_at: "2026-07-03T12:04:00+08:00",
  },
];

async function mountView(overrides = {}) {
  const { attachTo, ...propOverrides } = overrides;
  if (!vi.isMockFunction(roomApi.events)) {
    vi.spyOn(roomApi, "events").mockResolvedValue(overrides.initialEvents || []);
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes/:caseId/hearing", component: { template: "<div />" } },
      { path: "/disputes/:caseId/outcome", component: { template: "<div />" } },
    ],
  });
  await router.push("/disputes/CASE_HEARING_1/hearing");
  await router.isReady();
  const confirmSettlementAction =
    overrides.confirmSettlementAction || vi.fn();
  const wrapper = mount(HearingCourtView, {
    props: {
      initialHearing: hearing,
      initialEvidenceCatalog: evidenceCatalog,
      viewerRole: "USER",
      deadlineAt: "2026-07-03T15:00:00+08:00",
      serverNow: "2026-07-03T12:00:00+08:00",
      roundLimit: 3,
      confirmSettlementAction,
      initialEvents: [],
      initialMessages: courtMessages,
      ...propOverrides,
    },
    attachTo,
    global: { plugins: [router] },
  });
  return { wrapper, router, confirmSettlementAction };
}

function makeTranscriptMessage(index, text, senderRole = "USER") {
  return {
    id: `MESSAGE_STRESS_${index}`,
    sequence_no: index,
    sender_role: senderRole,
    message_type: senderRole === "JURY" ? "JURY_REVIEW_REPORT" : "PARTY_TEXT",
    message_text:
      senderRole === "JURY"
        ? JSON.stringify({
            risk_level: "MEDIUM",
            confidence_score: 0.78,
            summary: text,
          })
        : text,
    created_at: `2026-07-03T12:${String(index % 60).padStart(2, "0")}:00+08:00`,
  };
}

function makeEvidence(index, submittedByRole) {
  return {
    evidence_id: `EVIDENCE_${submittedByRole}_${index}`,
    evidence_type: index % 3 === 0 ? "IMAGE" : "DOCUMENT",
    submitted_by_role: submittedByRole,
    visibility: "PARTIES",
    content_url: `/api/evidence/${submittedByRole}/${index}`,
    verification_status: index % 2 === 0 ? "VERIFIED" : "PLAUSIBLE",
    confidence_score: 0.8,
    source_type: `${submittedByRole}_UPLOAD`,
    original_filename: `${submittedByRole === "USER" ? "用户" : "商家"}庭审证据-${index}-${"长文件名".repeat(8)}.pdf`,
    submission_status: "SUBMITTED",
  };
}

describe("HearingCourtView", () => {
  it("shows loading placeholders instead of false empty states while the hearing snapshot is still loading", async () => {
    vi.spyOn(hearingApi, "hearing").mockReturnValue(new Promise(() => {}));
    vi.spyOn(evidenceApi, "catalog").mockReturnValue(new Promise(() => {}));
    vi.spyOn(roomApi, "messages").mockReturnValue(new Promise(() => {}));

    const { wrapper } = await mountView({
      initialHearing: null,
      initialEvidenceCatalog: null,
      initialMessages: null,
    });

    expect(wrapper.find("[data-court-transcript-loading]").exists()).toBe(true);
    expect(wrapper.findAll("[data-evidence-loading]")).toHaveLength(2);
    expect(wrapper.find("[data-court-transcript-empty]").exists()).toBe(false);
    expect(wrapper.findAll("[data-evidence-empty]")).toHaveLength(0);
    expect(wrapper.text()).not.toContain("等待开庭消息");
    expect(wrapper.text()).not.toContain("暂无已提交证据");
    expect(wrapper.get('textarea[aria-label="本轮陈述"]').attributes("disabled")).toBeDefined();
    expect(wrapper.find("[data-send-hearing-statement]").exists()).toBe(false);
  });

  it("renders a collaborative little court with agents, rounds and a three-hour clock", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("小法庭");
    expect(wrapper.text()).not.toContain("证据书记官已宣读双方材料");
    expect(wrapper.text()).toContain("AI 法官");
    expect(wrapper.text()).toContain("AI 评审团");
    const statusDock = wrapper.get("[data-hearing-stage-dock]");
    expect(statusDock.text()).toContain("当前庭审状态");
    expect(statusDock.text()).toContain("第 2 轮");
    expect(statusDock.text()).not.toContain("HEARING STAGE DOCK");
    expect(statusDock.classes()).toContain("hearing-stage-dock--fixed-dashboard");
    expect(statusDock.classes()).toContain("hearing-stage-dock--short");
    expect(statusDock.find(".hearing-stage-dock__copy p").exists()).toBe(false);
    expect(statusDock.find(".hearing-stage-dock__copy").classes()).toContain(
      "hearing-stage-dock__copy--stacked",
    );
    expect(statusDock.find(".hearing-stage-dock__copy").classes()).toContain(
      "hearing-stage-dock__copy--breathing",
    );
    expect(wrapper.get("[data-hearing-stage-clock]").classes()).toContain(
      "hearing-stage-dock__clock--centered",
    );
    expect(wrapper.get("[data-hearing-stage-clock]").text()).toContain("当前轮次还剩");
    expect(wrapper.get("[data-hearing-stage-clock]").text()).toContain("05:00");
    expect(wrapper.get("[data-hearing-stage-clock]").text()).not.toContain("04:18");
    expect(wrapper.find("[data-hearing-progress-track]").exists()).toBe(true);
    expect(wrapper.find("[data-hearing-status-strip]").exists()).toBe(false);
    const progressItems = wrapper.findAll("[data-round-progress-item]");
    expect(progressItems).toHaveLength(3);
    expect(progressItems[0].attributes("data-round-progress-state")).toBe("complete");
    expect(progressItems[1].attributes("data-round-progress-state")).toBe("active");
    expect(progressItems[2].attributes("data-round-progress-state")).toBe("pending");
    expect(progressItems[0].attributes("data-round-connector-state")).toBe("complete");
    expect(progressItems[1].attributes("data-round-connector-state")).toBe("pending");
    expect(progressItems[0].find("[data-round-active-spinner]").exists()).toBe(false);
    expect(progressItems[1].find("[data-round-active-spinner]").exists()).toBe(true);
    expect(progressItems[2].find("[data-round-active-spinner]").exists()).toBe(false);
    expect(progressItems[0].find(".round-progress-board__number").exists()).toBe(false);
    expect(progressItems[1].find(".round-progress-board__number").exists()).toBe(false);
    expect(progressItems[2].find(".round-progress-board__number").exists()).toBe(false);
    expect(progressItems[0].get("b").text()).toBe("");
    expect(progressItems[1].get("b").text()).toBe("");
    expect(progressItems[2].get("b").text()).toBe("");
    expect(progressItems[0].get(".round-progress-board__label").text()).toBe("事实陈述");
    expect(progressItems[1].get(".round-progress-board__label").text()).toBe("证据解释");
    expect(progressItems[2].get(".round-progress-board__label").text()).toBe("方案确认");
    expect(progressItems[0].get(".round-progress-board__status").text()).toBe("已封存");
    expect(progressItems[1].get(".round-progress-board__status").text()).toBe("进行中");
    expect(progressItems[2].get(".round-progress-board__status").text()).toBe("未开始");
    expect(statusDock.find('[data-hearing-status-chip="USER"]').exists()).toBe(false);
    expect(statusDock.find('[data-hearing-status-chip="MERCHANT"]').exists()).toBe(false);
    expect(statusDock.text()).not.toContain("时间/封存");
    expect(statusDock.text()).not.toContain("法官/评审");
    expect(wrapper.get("[data-round-input-party-statuses]").text()).toContain("用户提交");
    expect(wrapper.get("[data-round-input-party-statuses]").text()).toContain("商家提交");
    expect(wrapper.get("[data-hearing-countdown]").text()).toContain("庭审时效");
    await wrapper.get("[data-open-court-ledger]").trigger("click");
    expect(wrapper.get("[data-court-ledger-drawer]").text()).toContain(
      "物流签收凭证仍需交叉核验",
    );
    expect(wrapper.get("[data-court-ledger-drawer]").text()).toContain("已完成");
    expect(wrapper.get("[data-court-ledger-drawer]").text()).not.toContain("COMPLETED");
    expect(wrapper.text()).not.toContain("审核解释官");
  });

  it("uses the AI-native courtroom layout with party evidence rails and no party role cards", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.get("[data-hearing-courtroom-page]").exists()).toBe(true);
    expect(wrapper.get("[data-court-agent-strip]").text()).not.toContain("COURT AGENTS");
    expect(wrapper.get("[data-court-agent-strip]").text()).not.toContain("法官与 AI 评审团");
    expect(wrapper.get(".courtroom-center").classes()).toContain("courtroom-center--compact-stage");
    expect(wrapper.findAll("[data-court-agent-card]")).toHaveLength(3);
    expect(wrapper.get('[data-court-agent-card="judge"]').text()).toContain("衡衡");
    expect(wrapper.get('[data-court-agent-card="jury-a"]').text()).toContain("评审 A");
    expect(wrapper.get('[data-court-agent-card="jury-b"]').text()).toContain("评审 B");
    expect(wrapper.get("[data-hearing-stage-dock]").text()).toContain("第 2 轮");
    expect(wrapper.get('[data-party-evidence-rail="user"]').text()).toContain(
      "用户证据原件匣",
    );
    expect(wrapper.get('[data-party-evidence-rail="merchant"]').text()).toContain(
      "商家证据原件匣",
    );
    expect(wrapper.get("[data-court-transcript]").text()).toContain("主审法官");
    expect(wrapper.get("[data-court-transcript]").text()).not.toContain("庭审记录大屏");
    expect(wrapper.get('[data-court-message="judge"]').classes()).toContain("court-message--judge-bench-card");
    expect(wrapper.get('[data-court-message="judge"]').classes()).toContain("court-message--tall-narrow-card");
    expect(wrapper.get('[data-court-message="judge"]').classes()).toContain("court-message--extended-length-card");
    expect(wrapper.get('[data-court-message="judge"]').classes()).toContain("court-message--authority-card");
    expect(wrapper.get('[data-court-message="judge"]').classes()).toContain("court-message--flexible-height-card");
    expect(wrapper.get('[data-court-message="judge"]').text()).toContain("法官宣读");
    expect(wrapper.get('[data-court-message="jury"]').classes()).toContain("court-message--jury-review-card");
    expect(wrapper.get('[data-court-message="jury"]').classes()).toContain("court-message--tall-narrow-card");
    expect(wrapper.get('[data-court-message="jury"]').classes()).toContain("court-message--extended-length-card");
    expect(wrapper.get('[data-court-message="jury"]').classes()).toContain("court-message--authority-card");
    expect(wrapper.get('[data-court-message="jury"]').classes()).toContain("court-message--flexible-height-card");
    expect(wrapper.get('[data-court-message="jury"]').text()).toContain("评审团观察");
    expect(wrapper.get('[data-court-message="jury"] header .court-message__jury-tags').exists()).toBe(true);
    expect(wrapper.get('[data-court-message="jury"] header strong').text()).toContain("AI 评审团");
    expect(wrapper.get('[data-court-message="jury"] header strong').text()).toContain("中风险");
    expect(wrapper.get('[data-court-message="jury"] header strong').text()).toContain("75/100");
    expect(wrapper.get('[data-court-message="user"]').classes()).toContain("court-message--party-statement-card");
    expect(wrapper.get('[data-court-message="user"]').classes()).toContain("court-message--soft-party-card");
    expect(wrapper.get('[data-court-message="user"]').classes()).toContain("court-message--flexible-height-card");
    expect(wrapper.get('[data-court-message="merchant"]').classes()).toContain("court-message--party-statement-card");
    expect(wrapper.get('[data-court-message="merchant"]').classes()).toContain("court-message--soft-party-card");
    expect(wrapper.get('[data-court-message="merchant"]').classes()).toContain("court-message--flexible-height-card");
    expect(wrapper.get("[data-round-input-bar]").text()).toContain(
      "本轮陈述输入台",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "提出一致方案",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "和解意向",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "确认或说明异议",
    );
    const inputHeader = wrapper.get("[data-round-input-header]");
    const inputComposer = wrapper.get("[data-round-input-composer]");
    expect(inputHeader.text()).toContain("本轮陈述输入台");
    expect(inputHeader.text()).toContain("用户提交");
    expect(inputHeader.text()).toContain("商家提交");
    expect(inputHeader.text()).not.toContain("确认或说明异议");
    expect(wrapper.find("[data-round-input-description]").exists()).toBe(false);
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "当前陈述、证据解释或对法官拟处理方向的确认或说明异议会被封装为本轮立场",
    );
    expect(inputHeader.element.compareDocumentPosition(inputComposer.element)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(wrapper.find("[data-open-settlement]").exists()).toBe(false);
    expect(wrapper.get("[data-round-input-bar]").classes()).toContain(
      "round-input-bar--fixed-dock",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).toContain(
      "提交陈述",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "发送陈述",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "发送本轮陈述",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "提交本轮陈述",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "本轮提交时效",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain(
      "等待服务端确认下一阶段",
    );
    expect(wrapper.find("[data-send-hearing-statement]").exists()).toBe(true);
    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.get('[data-party-evidence-rail="user"]').text()).not.toContain(
      "用户代表",
    );
    expect(wrapper.get('[data-party-evidence-rail="merchant"]').text()).not.toContain(
      "商家代表",
    );
    expect(wrapper.get("[data-open-court-ledger]").text()).toContain(
      "查看庭审卷轴",
    );
    expect(
      wrapper.get('[data-rail-position="right"]').find("[data-complete-hearing]").exists(),
    ).toBe(true);
  });

  it("renders bootstrap room messages with courtroom role labels instead of backend sender roles", async () => {
    const { wrapper } = await mountView({
      initialMessages: [
        {
          id: "MESSAGE_INTAKE_1",
          sequence_no: 1,
          sender_role: "INTAKE_OFFICER",
          message_text: "案情接待官宣读案情卷宗：已受理本次售后争议。",
          created_at: "2026-07-03T12:01:00+08:00",
        },
        {
          id: "MESSAGE_CLERK_1",
          sequence_no: 2,
          sender_role: "EVIDENCE_CLERK",
          message_text:
            '证据书记官宣读证据卷宗：核心证明矩阵显示：{"evidence_id":"EVIDENCE_001","relation_type":"UNMAPPED","verification_status":"UNVERIFIED"}。',
          created_at: "2026-07-03T12:02:00+08:00",
        },
        {
          id: "MESSAGE_JUDGE_1",
          sequence_no: 3,
          sender_role: "JUDGE",
          message_text:
            "现在开始围绕签收争议进行审理。AI 法官意见为非最终建议，最终由平台审核员确认。请双方说明希望平台审核员给出哪类处理方案。",
          created_at: "2026-07-03T12:03:00+08:00",
        },
      ],
    });

    const transcript = wrapper.get("[data-court-transcript]");
    expect(transcript.text()).toContain("案情接待官");
    expect(transcript.text()).toContain("证据书记官");
    expect(transcript.text()).toContain("主审法官");
    expect(transcript.text()).not.toContain("INTAKE_OFFICER");
    expect(transcript.text()).not.toContain("EVIDENCE_CLERK");
    expect(transcript.text()).not.toContain("JUDGE");
    expect(transcript.text()).not.toContain("中风险 · 当前可信分");
    expect(transcript.text()).not.toContain("案情接待官宣读案情卷宗：");
    expect(transcript.text()).not.toContain("证据书记官宣读证据卷宗：");
    expect(transcript.text()).not.toContain("平台审核员确认");
    expect(transcript.text()).not.toContain("希望平台审核员给出");
    expect(transcript.text()).toContain("后续确认");
    const intakeMessage = wrapper.get('[data-court-message="intake"]');
    const clerkMessage = wrapper.get('[data-court-message="clerk"]');
    const bootstrapJudgeMessage = wrapper
      .findAll('[data-court-message="judge"]')
      .find((message) => message.text().includes("现在开始围绕签收争议进行审理。"));
    expect(intakeMessage.text()).toContain("已受理本次售后争议。");
    expect(clerkMessage.text()).toContain("证据材料尚未映射到具体争议事实");
    expect(clerkMessage.text()).toContain("待核验");
    expect(clerkMessage.text()).not.toContain("evidence_id");
    expect(clerkMessage.text()).not.toContain("relation_type");
    expect(clerkMessage.text()).not.toContain("verification_status");
    expect(clerkMessage.text()).not.toContain("UNMAPPED");
    expect(clerkMessage.text()).not.toContain("UNVERIFIED");
    expect(bootstrapJudgeMessage).toBeDefined();
    expect(bootstrapJudgeMessage.text()).toContain("主审法官");
    expect(intakeMessage.classes()).toContain(
      "court-message--court-staff-card",
    );
    expect(intakeMessage.classes()).toContain(
      "court-message--tall-narrow-card",
    );
    expect(intakeMessage.classes()).toContain(
      "court-message--extended-length-card",
    );
    expect(intakeMessage.classes()).toContain(
      "court-message--authority-card",
    );
    expect(intakeMessage.classes()).toContain(
      "court-message--flexible-height-card",
    );
    expect(clerkMessage.classes()).toContain(
      "court-message--court-staff-card",
    );
    expect(clerkMessage.classes()).toContain(
      "court-message--tall-narrow-card",
    );
    expect(clerkMessage.classes()).toContain(
      "court-message--extended-length-card",
    );
    expect(clerkMessage.classes()).toContain(
      "court-message--authority-card",
    );
    expect(clerkMessage.classes()).toContain(
      "court-message--flexible-height-card",
    );
    expect(bootstrapJudgeMessage.classes()).toContain(
      "court-message--judge-bench-card",
    );
  });

  it("shows an empty transcript state instead of fabricated courtroom messages when no backend messages exist", async () => {
    const { wrapper } = await mountView({ initialMessages: [] });

    const transcript = wrapper.get("[data-court-transcript]");
    expect(transcript.text()).toContain("等待开庭消息");
    expect(transcript.text()).not.toContain("用户称门口监控未见快递员投递");
    expect(transcript.text()).not.toContain("商家需说明发货记录");
    expect(transcript.find("[data-court-message]").exists()).toBe(false);
  });

  it("keeps the audit aide out of the hearing page even for a platform reviewer", async () => {
    const { wrapper } = await mountView({ viewerRole: "PLATFORM_REVIEWER" });

    expect(wrapper.text()).not.toContain("审核解释官");
  });

  it("lets courtroom authority cards grow vertically with long transcript text", () => {
    expect(componentSource).toContain(".court-message--authority-card");
    expect(componentSource).toContain("height: auto");
    expect(componentSource).toContain("max-height: none");
    expect(componentSource).toContain("flex: 0 0 auto");
    expect(componentSource).toContain("--court-message-min-height: 143px");
    expect(componentSource).toContain("--court-message-min-height: 149px");
    expect(componentSource).toContain("--court-message-min-height: 123px");
    expect(componentSource).toContain("--court-message-min-height: 101px");
    expect(componentSource).toContain("padding-bottom: 18px");
    expect(componentSource).toContain("width: min(58%, 600px)");
    expect(componentSource).toContain("margin: 0 0 4px");
    expect(componentSource).toContain("padding: 15px 18px 14px");
    expect(componentSource).toContain("white-space: pre-wrap");
    expect(componentSource).toContain("overflow-wrap: anywhere");
    expect(componentSource).not.toContain("contain: layout paint");
  });

  it("uses explicit fixed rows and returns the input dock space to reviewers", async () => {
    const { wrapper: partyWrapper } = await mountView({ viewerRole: "USER" });
    const { wrapper: reviewerWrapper } = await mountView({
      viewerRole: "PLATFORM_REVIEWER",
    });

    expect(
      partyWrapper.get(".courtroom-center").attributes("data-has-input-dock"),
    ).toBe("true");
    expect(
      reviewerWrapper.get(".courtroom-center").attributes("data-has-input-dock"),
    ).toBe("false");
    expect(reviewerWrapper.get(".courtroom-center").classes()).toContain(
      "courtroom-center--without-input",
    );
    expect(reviewerWrapper.find("[data-round-input-bar]").exists()).toBe(false);
    expect(componentSource).toContain(
      "height: clamp(720px, calc(100dvh - 150px), 820px);",
    );
    expect(componentSource).toContain(
      "grid-template-rows: 122px minmax(0, 1fr) 154px;",
    );
    expect(componentSource).toMatch(
      /\.courtroom-center--without-input\s*{\s*grid-template-rows:\s*122px minmax\(0, 1fr\);/,
    );
    expect(componentSource).toContain("min-height: 720px;");
    expect(componentSource).toContain("max-height: 820px;");
  });

  it("keeps all three hearing stages horizontal at the narrow breakpoint", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.findAll("[data-round-progress-item]")).toHaveLength(3);
    expect(componentSource).toContain(
      "grid-template-columns: repeat(3, minmax(0, 1fr));",
    );
    expect(componentSource).not.toMatch(
      /\.round-progress-board\s*{[^}]*grid-template-columns:\s*1fr/,
    );
    expect(componentSource).toMatch(
      /@media \(max-width: 680px\)[\s\S]*?\.round-progress-board__item div\s*{\s*display:\s*grid;/,
    );
  });

  it("switches to abnormal-report mode at exactly 1500 Unicode characters", async () => {
    const text1499 = "陈".repeat(1499);
    const text1500 = "陈".repeat(1500);
    const text2000 = "陈".repeat(2000);
    const { wrapper } = await mountView({
      initialMessages: [
        makeTranscriptMessage(1499, text1499, "USER"),
        makeTranscriptMessage(1500, text1500, "JUDGE"),
        makeTranscriptMessage(2000, text2000, "MERCHANT"),
      ],
    });

    const normalCard = wrapper.get(
      '[data-court-message-id="MESSAGE_STRESS_1499"]',
    );
    const thresholdCard = wrapper.get(
      '[data-court-message-id="MESSAGE_STRESS_1500"]',
    );
    const oversizedCard = wrapper.get(
      '[data-court-message-id="MESSAGE_STRESS_2000"]',
    );

    expect(normalCard.attributes("data-long-transcript")).toBe("false");
    expect(normalCard.get("p").text()).toBe(text1499);
    expect(normalCard.find("[data-expand-transcript]").exists()).toBe(false);
    expect(thresholdCard.attributes("data-long-transcript")).toBe("true");
    expect(thresholdCard.get("p").text()).not.toBe(text1500);
    expect(oversizedCard.attributes("data-long-transcript")).toBe("true");

    const expandButton = thresholdCard.get("[data-expand-transcript]");
    expect(expandButton.attributes("aria-expanded")).toBe("false");
    await expandButton.trigger("click");
    expect(expandButton.attributes("aria-expanded")).toBe("true");
    expect(thresholdCard.get("p").text()).toBe(text1500);
  });

  it("keeps fifty naturally-sized messages inside the one transcript scroll rail", async () => {
    const roles = ["USER", "MERCHANT", "JUDGE", "JURY"];
    const initialMessages = Array.from({ length: 50 }, (_, index) =>
      makeTranscriptMessage(
        index + 1,
        `第 ${index + 1} 条庭审记录：${"自然增长正文".repeat((index % 5) + 1)}`,
        roles[index % roles.length],
      ),
    );
    const { wrapper } = await mountView({ initialMessages });
    const transcriptRail = wrapper.get(".court-transcript__messages");

    expect(wrapper.findAll("[data-court-message]")).toHaveLength(50);
    expect(transcriptRail.attributes("data-transcript-scroll-rail")).toBe(
      "true",
    );
    expect(wrapper.findAll('[data-court-message="user"]').length).toBeGreaterThan(0);
    expect(wrapper.findAll('[data-court-message="merchant"]').length).toBeGreaterThan(0);
    expect(wrapper.findAll('[data-court-message="judge"]').length).toBeGreaterThan(0);
    expect(wrapper.findAll('[data-court-message="jury"]').length).toBeGreaterThan(0);
    expect(componentSource).toContain("scrollbar-gutter: stable;");
    expect(componentSource).toContain("overscroll-behavior: contain;");
  });

  it("keeps one hundred evidence records in the two dedicated evidence scroll rails", async () => {
    const items = [
      ...Array.from({ length: 50 }, (_, index) => makeEvidence(index + 1, "USER")),
      ...Array.from({ length: 50 }, (_, index) => makeEvidence(index + 1, "MERCHANT")),
    ];
    const { wrapper } = await mountView({
      initialEvidenceCatalog: { case_id: "CASE_HEARING_1", items },
    });
    const leftRail = wrapper.get('[data-rail-position="left"]');
    const rightRail = wrapper.get('[data-rail-position="right"]');

    expect(leftRail.findAll(".evidence-file-card")).toHaveLength(50);
    expect(rightRail.findAll(".evidence-file-card")).toHaveLength(50);
    expect(leftRail.get(".evidence-pocket").attributes("data-evidence-scroll-rail")).toBe("true");
    expect(rightRail.get(".evidence-pocket").attributes("data-evidence-scroll-rail")).toBe("true");
    expect(leftRail.get(".evidence-file-card strong").attributes("title")).toContain(
      "用户庭审证据-1",
    );
    expect(rightRail.get(".evidence-file-card strong").attributes("title")).toContain(
      "商家庭审证据-1",
    );
  });

  it("opens only one accessible evidence drawer and restores launcher focus on Escape", async () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const { wrapper } = await mountView({ attachTo: host });

    try {
      const leftLauncher = wrapper.get('[data-open-evidence-drawer="left"]');
      const rightLauncher = wrapper.get('[data-open-evidence-drawer="right"]');
      expect(leftLauncher.attributes("aria-controls")).toBe("hearing-evidence-drawer-left");
      expect(rightLauncher.attributes("aria-controls")).toBe("hearing-evidence-drawer-right");

      await leftLauncher.trigger("click");
      await flushPromises();
      const leftDrawer = wrapper.get('[data-evidence-drawer-open="left"]');
      expect(leftDrawer.attributes("role")).toBe("dialog");
      expect(leftDrawer.attributes("aria-modal")).toBe("true");
      expect(document.activeElement).toBe(
        leftDrawer.get('[data-close-evidence-drawer="left"]').element,
      );

      await rightLauncher.trigger("click");
      await flushPromises();
      expect(wrapper.find('[data-evidence-drawer-open="left"]').exists()).toBe(false);
      expect(wrapper.get('[data-evidence-drawer-open="right"]').exists()).toBe(true);

      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
      await flushPromises();
      expect(wrapper.find("[data-evidence-drawer-open]").exists()).toBe(false);
      expect(document.activeElement).toBe(rightLauncher.element);
    } finally {
      wrapper.unmount();
      host.remove();
    }
  });

  it("moves focus into the court ledger, traps Tab, and restores the opener for explicit closes", async () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const { wrapper } = await mountView({ attachTo: host });

    try {
      const opener = wrapper.get("[data-open-court-ledger]");
      opener.element.focus();
      await opener.trigger("click");
      await flushPromises();

      const ledger = wrapper.get("[data-court-ledger-drawer]");
      const closeButton = ledger.get('button[aria-label="关闭庭审卷轴"]');
      expect(document.activeElement).toBe(closeButton.element);

      const tabEvent = new KeyboardEvent("keydown", {
        key: "Tab",
        bubbles: true,
        cancelable: true,
      });
      closeButton.element.dispatchEvent(tabEvent);
      expect(tabEvent.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(closeButton.element);

      await closeButton.trigger("click");
      await flushPromises();
      expect(wrapper.find("[data-court-ledger-drawer]").exists()).toBe(false);
      expect(document.activeElement).toBe(opener.element);

      await opener.trigger("click");
      await flushPromises();
      await wrapper.get("[data-court-ledger-drawer]").trigger("click");
      await flushPromises();
      expect(wrapper.find("[data-court-ledger-drawer]").exists()).toBe(false);
      expect(document.activeElement).toBe(opener.element);
    } finally {
      wrapper.unmount();
      host.remove();
    }
  });

  it("closes the stacked court ledger before the evidence drawer on consecutive Escape presses", async () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const { wrapper } = await mountView({ attachTo: host });

    try {
      const evidenceLauncher = wrapper.get('[data-open-evidence-drawer="right"]');
      await evidenceLauncher.trigger("click");
      await flushPromises();
      expect(wrapper.get('[data-evidence-drawer-open="right"]').exists()).toBe(true);

      const ledgerLauncher = wrapper.get("[data-open-court-ledger]");
      ledgerLauncher.element.focus();
      await ledgerLauncher.trigger("click");
      await flushPromises();

      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
      await flushPromises();
      expect(wrapper.find("[data-court-ledger-drawer]").exists()).toBe(false);
      expect(wrapper.get('[data-evidence-drawer-open="right"]').exists()).toBe(true);
      expect(document.activeElement).toBe(ledgerLauncher.element);

      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
      await flushPromises();
      expect(wrapper.find("[data-evidence-drawer-open]").exists()).toBe(false);
      expect(document.activeElement).toBe(evidenceLauncher.element);
    } finally {
      wrapper.unmount();
      host.remove();
    }
  });

  it("removes the shared courtroom keydown listener when the view unmounts", async () => {
    const addListener = vi.spyOn(window, "addEventListener");
    const removeListener = vi.spyOn(window, "removeEventListener");
    const { wrapper } = await mountView();
    const keydownHandler = addListener.mock.calls.find(
      ([eventName]) => eventName === "keydown",
    )?.[1];

    expect(keydownHandler).toEqual(expect.any(Function));
    wrapper.unmount();
    expect(removeListener).toHaveBeenCalledWith("keydown", keydownHandler);
  });

  it("clears an open evidence drawer without restoring focus when the courtroom grows past the drawer breakpoint", async () => {
    let resizeCallback;
    const observe = vi.fn();
    const disconnect = vi.fn();
    class ResizeObserverMock {
      constructor(callback) {
        resizeCallback = callback;
      }

      observe = observe;
      disconnect = disconnect;
    }
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    const host = document.createElement("div");
    document.body.appendChild(host);
    const { wrapper } = await mountView({ attachTo: host });

    try {
      await flushPromises();
      const courtroomContainer = wrapper.get(".room-shell__workspace");
      const leftLauncher = wrapper.get('[data-open-evidence-drawer="left"]');
      const launcherFocus = vi.spyOn(leftLauncher.element, "focus");
      expect(observe).toHaveBeenCalledWith(courtroomContainer.element);

      await leftLauncher.trigger("click");
      await flushPromises();
      const leftDrawer = wrapper.get('[data-rail-position="left"]');
      expect(leftDrawer.attributes("role")).toBe("dialog");
      expect(leftDrawer.attributes("aria-modal")).toBe("true");

      resizeCallback([
        {
          target: courtroomContainer.element,
          contentRect: { width: 1219 },
        },
      ]);
      await flushPromises();
      expect(leftDrawer.attributes("role")).toBe("dialog");

      resizeCallback([
        {
          target: courtroomContainer.element,
          contentRect: { width: 1220 },
        },
      ]);
      await flushPromises();

      expect(wrapper.find("[data-evidence-drawer-open]").exists()).toBe(false);
      expect(leftDrawer.attributes("role")).toBeUndefined();
      expect(leftDrawer.attributes("aria-modal")).toBeUndefined();
      expect(launcherFocus).not.toHaveBeenCalled();

      wrapper.unmount();
      expect(disconnect).toHaveBeenCalledTimes(1);
    } finally {
      if (wrapper.exists()) wrapper.unmount();
      host.remove();
      vi.unstubAllGlobals();
    }
  });

  it("uses a cleaned-up window resize fallback when ResizeObserver is unavailable", async () => {
    vi.stubGlobal("ResizeObserver", undefined);
    const addEventListener = vi.spyOn(window, "addEventListener");
    const removeEventListener = vi.spyOn(window, "removeEventListener");
    const host = document.createElement("div");
    document.body.appendChild(host);
    const { wrapper } = await mountView({ attachTo: host });

    try {
      await flushPromises();
      const courtroomContainer = wrapper.get(".room-shell__workspace");
      let containerWidth = 0;
      vi.spyOn(courtroomContainer.element, "getBoundingClientRect").mockImplementation(
        () => ({ width: containerWidth }),
      );
      const resizeRegistration = addEventListener.mock.calls.find(
        ([eventName]) => eventName === "resize",
      );
      expect(resizeRegistration).toBeDefined();

      await wrapper.get('[data-open-evidence-drawer="right"]').trigger("click");
      await flushPromises();
      expect(wrapper.get('[data-rail-position="right"]').attributes("role")).toBe(
        "dialog",
      );

      containerWidth = 1220;
      window.dispatchEvent(new Event("resize"));
      await flushPromises();
      expect(wrapper.find("[data-evidence-drawer-open]").exists()).toBe(false);

      wrapper.unmount();
      expect(removeEventListener).toHaveBeenCalledWith(
        "resize",
        resizeRegistration[1],
      );
    } finally {
      if (wrapper.exists()) wrapper.unmount();
      host.remove();
      vi.unstubAllGlobals();
    }
  });

  it("declares the 1220px evidence-drawer breakpoint and fixed rail rows", () => {
    expect(componentSource).toContain(
      "container: hearing-court / inline-size;",
    );
    expect(componentSource).toContain(
      "@container hearing-court (max-width: 1219px)",
    );
    expect(componentSource).toContain(
      "grid-template-rows: 88px minmax(0, 1fr) 48px;",
    );
    expect(componentSource).not.toContain("@media (max-width: 1180px)");
  });

  it("keeps ledger and settlement dialog close controls at least 44px square", () => {
    expect(componentSource).toMatch(
      /\.hearing-ledger header button\s*{[^}]*width:\s*44px;[^}]*height:\s*44px;/,
    );
    expect(componentSource).toMatch(
      /\.settlement-dialog form header button\s*{[^}]*width:\s*44px;\s*height:\s*44px;/,
    );
  });

  it("contains a 200-character unbroken hearing error inside the fixed canvas", async () => {
    const errorText = "E".repeat(200);
    const { wrapper } = await mountView({
      messageAction: vi.fn().mockRejectedValue(new Error(errorText)),
    });

    await wrapper.get('[data-send-message] textarea').setValue("trigger error");
    await wrapper.get("[data-send-hearing-statement]").trigger("click");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toBe(errorText);
    expect(componentSource).toMatch(
      /\.hearing-error\s*{[^}]*min-width:\s*0;[^}]*white-space:\s*pre-wrap;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/,
    );
  });

  it("clips translated inactive evidence drawers at the narrow canvas boundary", () => {
    expect(componentSource).toContain(
      "transform: translateX(calc(-100% - 20px));",
    );
    expect(componentSource).toContain(
      "transform: translateX(calc(100% + 20px));",
    );
    expect(componentSource).toMatch(
      /@container hearing-court \(max-width: 1219px\)\s*{\s*\.hearing-courtroom-page\s*{[^}]*overflow:\s*(?:clip|hidden);/,
    );
  });

  it("keeps user, merchant, and reviewer role shells semantically stable", async () => {
    for (const viewerRole of ["USER", "MERCHANT", "PLATFORM_REVIEWER"]) {
      const { wrapper } = await mountView({ viewerRole });
      expect(
        wrapper.get("[data-hearing-courtroom-page]").attributes("data-viewer-role"),
      ).toBe(viewerRole);
      expect(wrapper.find("[data-round-input-bar]").exists()).toBe(
        viewerRole !== "PLATFORM_REVIEWER",
      );
      wrapper.unmount();
    }
  });

  it("puts the current party evidence rail on the left and keeps the counterparty read-only on the right", async () => {
    const { wrapper } = await mountView({ viewerRole: "MERCHANT" });

    const leftRail = wrapper.get('[data-rail-position="left"]');
    const rightRail = wrapper.get('[data-rail-position="right"]');

    expect(leftRail.attributes("data-party-evidence-rail")).toBe("merchant");
    expect(leftRail.text()).toContain("商家证据原件匣");
    expect(leftRail.text()).toContain("补充商家证据");
    expect(rightRail.attributes("data-party-evidence-rail")).toBe("user");
    expect(rightRail.text()).toContain("用户证据原件匣");
    expect(rightRail.text()).not.toContain("补充用户证据");
  });

  it("renders party evidence rails from the backend evidence catalog instead of mock files", async () => {
    const { wrapper } = await mountView({ viewerRole: "MERCHANT" });

    const leftRail = wrapper.get('[data-rail-position="left"]');
    const rightRail = wrapper.get('[data-rail-position="right"]');

    expect(leftRail.text()).toContain("商家物流签收底单.pdf");
    expect(leftRail.text()).toContain("90%");
    expect(leftRail.text()).toContain("已核验");
    expect(rightRail.text()).toContain("用户门口监控截图.jpg");
    expect(rightRail.text()).toContain("86%");
    expect(rightRail.text()).toContain("基本可信");
    expect(wrapper.text()).not.toContain("打包交接照片.jpg");
    expect(wrapper.text()).not.toContain("出库扫描记录.md");
  });

  it("shows an empty evidence rail state when the backend catalog has no visible evidence", async () => {
    const { wrapper } = await mountView({
      initialEvidenceCatalog: { case_id: "CASE_HEARING_1", items: [] },
    });

    expect(wrapper.get('[data-rail-position="left"]').text()).toContain("暂无已提交证据");
    expect(wrapper.get('[data-rail-position="right"]').text()).toContain("暂无已提交证据");
    expect(wrapper.text()).not.toContain("物流签收底单.pdf");
  });

  it("keeps hearing-level actions below the right evidence card instead of inside it", async () => {
    const { wrapper } = await mountView();

    const rightColumn = wrapper.get('[data-rail-position="right"]');
    const rightEvidenceCard = rightColumn.get(".party-evidence-rail");

    expect(rightColumn.text()).toContain("查看庭审卷轴");
    expect(rightColumn.get("[data-complete-hearing]").text()).toBe(
      "等待裁决草案",
    );
    expect(rightEvidenceCard.text()).not.toContain("查看庭审卷轴");
    expect(rightEvidenceCard.find("[data-complete-hearing]").exists()).toBe(
      false,
    );
  });

  it("keeps settlement proposal and confirmation out of the normal hearing mainline", async () => {
    const confirmSettlementAction = vi.fn();
    const proposeSettlementAction = vi.fn();
    const { wrapper } = await mountView({
      confirmSettlementAction,
      proposeSettlementAction,
    });

    expect(wrapper.text()).not.toContain("提出一致方案");
    expect(wrapper.text()).not.toContain("双方一致方案");
    expect(wrapper.text()).not.toContain("确认这份一致方案");
    expect(wrapper.text()).not.toContain("和解意向");
    expect(wrapper.find("[data-open-settlement]").exists()).toBe(false);
    expect(wrapper.find("[data-confirm-settlement]").exists()).toBe(false);
    expect(wrapper.find("[data-settlement-proposal]").exists()).toBe(false);
    expect(wrapper.find("[data-submit-settlement]").exists()).toBe(false);
    expect(confirmSettlementAction).not.toHaveBeenCalled();
    expect(proposeSettlementAction).not.toHaveBeenCalled();
  });

  it("lets the current party submit a statement without closing the active hearing round", async () => {
    const messageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_ROUND_TEXT_1",
      sequence_no: 1,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "用户补充本轮陈述内容。",
    });
    const submitRoundAction = vi.fn();
    const { wrapper } = await mountView({
      messageAction,
      submitRoundAction,
    });

    await wrapper
      .get('[data-send-message] textarea')
      .setValue("用户补充本轮陈述内容。");
    await wrapper.get("[data-send-hearing-statement]").trigger("click");
    await flushPromises();

    expect(messageAction).toHaveBeenCalledWith(
      expect.objectContaining({
        message_type: "PARTY_TEXT",
        text: "用户补充本轮陈述内容。",
      }),
    );
    expect(submitRoundAction).not.toHaveBeenCalled();
    expect(wrapper.get('[data-send-message] textarea').element.value).toBe("");
    expect(wrapper.get("[data-court-transcript]").text()).toContain(
      "用户补充本轮陈述内容。",
    );
    expect(wrapper.get('[data-round-input-party-status="USER"]').text()).toContain(
      "已提交",
    );
    expect(wrapper.get("[data-send-hearing-statement]").exists()).toBe(true);
    expect(wrapper.get("[data-send-hearing-statement]").text()).toBe("提交陈述");
  });

  it("keeps the statement button available after that party has already spoken in an open round", async () => {
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialHearing: {
        ...hearing,
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "WAITING",
            submitted_roles: ["MERCHANT"],
            current_actor_submitted: true,
          },
        ],
      },
    });

    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.get("[data-send-hearing-statement]").exists()).toBe(true);
    expect(wrapper.get("[data-send-hearing-statement]").text()).toBe("提交陈述");
    expect(wrapper.get('[data-send-message] textarea').attributes("disabled")).toBeUndefined();
    expect(wrapper.text()).toContain("已提交本轮，等待用户");
  });

  it("shows a judge-waiting status after both parties have spoken without sealing the round", async () => {
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialHearing: {
        rounds: [
          {
            round_id: "ROUND_1",
            round_no: 1,
            status: "OPEN",
            dossier_version: 1,
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
          },
        ],
        settlements: [],
      },
      initialMessages: [
        {
          id: "MESSAGE_USER_ROUND_1",
          sequence_no: 1,
          sender_role: "USER",
          message_type: "PARTY_TEXT",
          message_text: "用户已完成事实陈述。",
          hearing_round: 1,
        },
        {
          id: "MESSAGE_MERCHANT_ROUND_1",
          sequence_no: 2,
          sender_role: "MERCHANT",
          message_type: "PARTY_TEXT",
          message_text: "商家已完成事实陈述。",
          hearing_round: 1,
        },
      ],
    });

    const statusDock = wrapper.get("[data-hearing-stage-dock]");

    expect(statusDock.text()).toContain("双方已陈述，等待法官收束");
    expect(statusDock.text()).not.toContain("等待用户");
    expect(statusDock.text()).not.toContain("等待商家");
    expect(wrapper.get('[data-round-input-party-status="USER"]').text()).toContain(
      "已提交",
    );
    expect(wrapper.get('[data-round-input-party-status="MERCHANT"]').text()).toContain(
      "已提交",
    );
    expect(wrapper.get("[data-send-hearing-statement]").text()).toBe("提交陈述");
  });

  it("shows a sealed-round message after both parties have submitted", async () => {
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      initialHearing: {
        ...hearing,
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "COMPLETED",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
            summary_json: JSON.stringify({
              trigger: "BOTH_PARTIES_SUBMITTED",
              judge: "双方本轮提交完成，等待 AI 法官生成本轮判断。",
            }),
          },
        ],
      },
    });

    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.get("[data-hearing-stage-dock]").text()).toContain("本轮已封存");
    expect(wrapper.get('[data-round-input-party-status="USER"]').text()).toContain("已封存");
    expect(wrapper.get('[data-round-input-party-status="MERCHANT"]').text()).toContain("已封存");
    expect(wrapper.text()).not.toContain("等待用户");
  });

  it("hands the parties off to platform review after the final hearing round is sealed", async () => {
    const { wrapper } = await mountView({
      viewerRole: "USER",
      initialHearing: {
        ...hearing,
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "COMPLETED",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
          },
          {
            round_id: "ROUND_3",
            round_no: 3,
            status: "FORCED_CLOSED",
            dossier_version: 3,
            stop_reason: "MAX_ROUNDS",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
            summary_json: JSON.stringify({
              trigger: "MAX_ROUNDS_REACHED",
              judge: "第三轮陈述已封存，正在生成最终裁决草案。",
            }),
          },
        ],
      },
    });

    expect(wrapper.find("[data-review-handoff]").exists()).toBe(false);
    const statusDock = wrapper.get("[data-hearing-stage-dock]");
    expect(statusDock.text()).toContain("等待裁决草案");
    expect(statusDock.text()).not.toContain("平台终审");
    expect(statusDock.text()).not.toContain("进入平台终审，等待审核员确认最终结果");
    expect(statusDock.get("[data-hearing-stage-clock]").text()).toContain("当前轮次还剩");
    expect(statusDock.get("[data-hearing-stage-clock]").text()).toContain("00:00");
    expect(wrapper.find("[data-hearing-progress-track]").classes()).toContain(
      "round-progress-board--timeline",
    );
    expect(statusDock.find("[data-hearing-status-strip]").exists()).toBe(false);
    expect(wrapper.get("[data-round-input-party-statuses]").text()).toContain("用户提交");
    expect(wrapper.get("[data-round-input-party-statuses]").text()).toContain("商家提交");
    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
    expect(wrapper.find("[data-round-input-bar]").exists()).toBe(true);
    expect(wrapper.get("[data-round-input-bar]").text()).toContain(
      "庭审已封存，等待裁决草案",
    );
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain("ROUND INPUT DOCK");
    expect(wrapper.get("[data-round-input-bar]").text()).not.toContain("等待平台终审");
    expect(wrapper.find('[data-send-message] textarea').exists()).toBe(false);
    expect(wrapper.find("[data-send-hearing-statement]").exists()).toBe(false);
    expect(wrapper.get("[data-round-input-final-status]").text()).toContain(
      "庭审已封存，等待裁决草案",
    );
  });

  it("uses the backend draft-ready status as the final sealed hearing headline", async () => {
    const { wrapper } = await mountView({
      viewerRole: "USER",
      initialHearing: {
        ...hearing,
        status: {
          hearing_phase: "DRAFT_READY",
          phase_label: "裁决草案已生成",
          next_step_hint: "AI 法官已生成裁决草案，可进入结果页查看草案说明。",
          can_complete_hearing: true,
          latest_draft_id: "DRAFT_READY_1",
          final_round_sealed: true,
        },
        rounds: [
          hearing.rounds[0],
          {
            ...hearing.rounds[1],
            status: "COMPLETED",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
          },
          {
            round_id: "ROUND_3",
            round_no: 3,
            status: "FORCED_CLOSED",
            dossier_version: 3,
            stop_reason: "MAX_ROUNDS",
            submitted_roles: ["USER", "MERCHANT"],
            current_actor_submitted: true,
          },
        ],
      },
    });

    const statusDock = wrapper.get("[data-hearing-stage-dock]");

    expect(statusDock.text()).toContain("裁决草案已生成");
    expect(statusDock.text()).not.toContain("等待裁决草案");
    expect(wrapper.get("[data-complete-hearing-hint]").text()).toContain(
      "可进入结果页查看草案说明",
    );
    expect(wrapper.get("[data-complete-hearing-hint]").text()).not.toContain(
      "平台审核入口",
    );
    expect(wrapper.find('[data-send-message] textarea').exists()).toBe(false);
    expect(wrapper.get("[data-round-input-final-status]").text()).toContain(
      "庭审已封存，等待裁决草案",
    );
  });

  it("does not expose party round submission to platform reviewers", async () => {
    const { wrapper } = await mountView({ viewerRole: "PLATFORM_REVIEWER" });

    expect(wrapper.find("[data-submit-hearing-round]").exists()).toBe(false);
  });

  it("opens the outcome only after a durable case-closed event", async () => {
    const eventStreamer = vi.fn(async (options) => {
      options.state.connected = true;
      await options.applyEvent({ id: 14, event: "CASE_CLOSED", data: {} });
    });
    const { wrapper, router } = await mountView({ eventStreamer });
    await flushPromises();

    expect(eventStreamer).toHaveBeenCalledWith(
      expect.objectContaining({
        caseId: "CASE_HEARING_1",
        roomType: "HEARING",
      }),
    );
    expect(wrapper.find('[data-connection="connected"]').exists()).toBe(true);
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_HEARING_1/outcome",
    );
  });

  it("refreshes the hearing snapshot when the backend emits a phase-changed event", async () => {
    const refreshedHearing = {
      ...hearing,
      status: {
        hearing_phase: "DRAFT_READY",
        phase_label: "裁决草案已生成",
        next_step_hint: "裁决草案已生成，可进入结果页查看草案说明。",
        can_complete_hearing: true,
        latest_draft_id: "DRAFT_READY_EVENT",
        final_round_sealed: true,
      },
    };
    const eventStreamer = vi.fn(async (options) => {
      options.state.connected = true;
      await options.applyEvent({
        id: 18,
        event: "HEARING_PHASE_CHANGED",
        data: {
          event_type: "HEARING_PHASE_CHANGED",
          payload_json: JSON.stringify({
            hearing_phase: "DRAFT_READY",
            latest_draft_id: "DRAFT_READY_EVENT",
          }),
        },
      });
    });
    const completeHearingAction = vi.fn();
    vi.spyOn(hearingApi, "hearing")
      .mockResolvedValueOnce(hearing)
      .mockResolvedValueOnce(refreshedHearing);
    vi.spyOn(roomApi, "messages").mockResolvedValue(courtMessages);
    vi.spyOn(evidenceApi, "catalog").mockResolvedValue(evidenceCatalog);

    const { wrapper } = await mountView({
      initialHearing: null,
      initialMessages: null,
      eventStreamer,
      completeHearingAction,
    });
    await flushPromises();

    expect(hearingApi.hearing).toHaveBeenCalledTimes(2);
    expect(wrapper.get("[data-hearing-stage-dock]").text()).toContain(
      "裁决草案已生成",
    );
    expect(wrapper.get("[data-complete-hearing]").text()).toBe("查看裁决草案");
  });

  it("lets parties leave the hearing through the right evidence rail completion button", async () => {
    const completeHearingAction = vi.fn().mockResolvedValue({
      hearing_phase: "DRAFT_READY",
      phase_label: "裁决草案已生成",
      next_step_hint: "AI 法官已生成裁决草案，可进入结果页查看草案说明。",
      can_complete_hearing: true,
      latest_draft_id: "DRAFT_READY_1",
      final_round_sealed: true,
    });
    const { wrapper, router } = await mountView({
      completeHearingAction,
      initialHearing: {
        ...hearing,
        status: {
          hearing_phase: "DRAFT_READY",
          phase_label: "裁决草案已生成",
          next_step_hint: "AI 法官已生成裁决草案，可进入结果页查看草案说明。",
          can_complete_hearing: true,
          latest_draft_id: "DRAFT_READY_1",
          final_round_sealed: true,
        },
      },
    });

    const button = wrapper.get("[data-complete-hearing]");
    expect(button.text()).toBe("查看裁决草案");

    await button.trigger("click");
    await flushPromises();

    expect(completeHearingAction).toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_HEARING_1/outcome",
    );
  });

  it("does not let the completion button bypass the judge while the draft is still being prepared", async () => {
    const completeHearingAction = vi.fn();
    const { wrapper, router } = await mountView({
      completeHearingAction,
      initialHearing: {
        ...hearing,
        status: {
          hearing_phase: "JUDGE_DRAFTING",
          phase_label: "等待裁决草案",
          next_step_hint: "三轮陈述已封存，等待 AI 法官生成裁决草案。",
          can_complete_hearing: false,
          final_round_sealed: true,
        },
      },
    });

    const button = wrapper.get("[data-complete-hearing]");

    expect(button.element.disabled).toBe(true);
    expect(button.text()).toBe("等待裁决草案");
    expect(wrapper.get("[data-complete-hearing-hint]").text()).toContain(
      "等待 AI 法官生成裁决草案",
    );

    await button.trigger("click");
    await flushPromises();

    expect(completeHearingAction).not.toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_HEARING_1/hearing",
    );
  });

  it("uploads and submits merchant supplementary evidence with the backend actor-specific source type", async () => {
    const supplementAction = vi.fn().mockResolvedValue({
      evidence_id: "EVIDENCE_SUPPLEMENT_1",
      original_filename: "签收底单.pdf",
    });
    const submitEvidenceBatchAction = vi.fn().mockResolvedValue({
      room_message: {
        id: "MESSAGE_SUPPLEMENT_BATCH_1",
        sequence_no: 3,
        sender_role: "MERCHANT",
        message_type: "PARTY_EVIDENCE_REFERENCE",
        message_text: "商家提交了 1 份证据材料。",
        attachment_refs: ["EVIDENCE_SUPPLEMENT_1"],
        hearing_round: 2,
      },
    });
    const messageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_SUPPLEMENT_1",
      sequence_no: 3,
      sender_role: "MERCHANT",
      message_text: "已补充证据：签收底单.pdf",
    });
    const { wrapper } = await mountView({
      viewerRole: "MERCHANT",
      supplementAction,
      submitEvidenceBatchAction,
      messageAction,
    });
    const input = wrapper.get('input[type="file"]');
    const file = new File(["demo"], "签收底单.pdf", {
      type: "application/pdf",
    });
    Object.defineProperty(input.element, "files", {
      value: [file],
      configurable: true,
    });

    await input.trigger("change");
    await flushPromises();

    expect(supplementAction).toHaveBeenCalledWith(
      expect.objectContaining({
        file,
        sourceType: "MERCHANT_UPLOAD",
        visibility: "PARTIES",
      }),
    );
    expect(submitEvidenceBatchAction).toHaveBeenCalledWith(
      expect.objectContaining({
        evidence_ids: ["EVIDENCE_SUPPLEMENT_1"],
        batch_note: "庭审补充证据：签收底单.pdf",
      }),
    );
    expect(messageAction).not.toHaveBeenCalled();
    expect(wrapper.get("[data-court-transcript]").text()).toContain(
      "商家提交了 1 份证据材料。",
    );
  });

  it("shows formal jury review reports as cards without exposing raw A2A internals", async () => {
    const { wrapper } = await mountView({
      initialMessages: [
        ...courtMessages,
        {
          id: "A2A_SILENT_NOTE_1",
          sequence_no: 5,
          sender_role: "JURY",
          message_type: "JURY_SILENT_NOTE",
          visibility: "SYSTEM_AUDIT_ONLY",
          message_text: JSON.stringify({
            a2a_message_id: "A2A_INTERNAL_SILENT_1",
            evidence_dossier_version: 2,
            judge_attention: ["签收人身份仍需关注"],
          }),
        },
        {
          id: "MESSAGE_JURY_REPORT_1",
          sequence_no: 6,
          sender_role: "JURY",
          message_type: "JURY_REVIEW_REPORT",
          hearing_round: 3,
          message_text: JSON.stringify({
            a2a_message_id: "A2A_INTERNAL_REPORT_1",
            message_type: "JURY_REVIEW_REPORT",
            visibility: "COURTROOM_CARD",
            risk_level: "MEDIUM",
            confidence_score: 0.78,
            summary: "评审团认为签收人身份和签收地点仍需重点核对。",
            recommendations: ["请法官在裁决草案中说明签收凭证不足的影响。"],
            evidence_dossier_version: 2,
          }),
        },
      ],
    });

    const juryCards = wrapper.findAll('[data-court-message="jury"]');
    const formalCard = juryCards.find((card) => card.text().includes("评审团复核报告"));

    expect(formalCard).toBeTruthy();
    expect(formalCard.text()).toContain("中风险");
    expect(formalCard.text()).toContain("78/100");
    expect(formalCard.text()).toContain("签收人身份和签收地点仍需重点核对");
    expect(formalCard.text()).toContain("请法官在裁决草案中说明签收凭证不足的影响");
    expect(wrapper.text()).not.toContain("A2A_INTERNAL");
    expect(wrapper.text()).not.toContain("SYSTEM_AUDIT_ONLY");
    expect(wrapper.text()).not.toContain("evidence_dossier_version");
  });

  it("adds evidence supplements, matrix revisions and jury reports to the hearing ledger", async () => {
    const { wrapper } = await mountView({
      initialMessages: [
        ...courtMessages,
        {
          id: "MESSAGE_SUPPLEMENT_BATCH_1",
          sequence_no: 5,
          sender_role: "MERCHANT",
          message_type: "PARTY_EVIDENCE_REFERENCE",
          message_text: "商家提交了 1 份补充证据材料。",
          attachment_refs: ["EVIDENCE_SUPPLEMENT_1"],
          hearing_round: 2,
        },
        {
          id: "MESSAGE_DOSSIER_REVISION_1",
          sequence_no: 6,
          sender_role: "EVIDENCE_CLERK",
          message_type: "EVIDENCE_DOSSIER_REVISED",
          hearing_round: 2,
          message_text: JSON.stringify({
            active_version: 2,
            supersedes_version: 1,
            updated_after_round: 2,
            revision_reason: "第二轮补证后更新证据矩阵",
          }),
        },
        {
          id: "MESSAGE_JURY_REPORT_1",
          sequence_no: 7,
          sender_role: "JURY",
          message_type: "JURY_REVIEW_REPORT",
          hearing_round: 3,
          message_text: JSON.stringify({
            risk_level: "MEDIUM",
            confidence_score: 75,
            summary: "评审团建议法官在草案中说明证据缺口。",
          }),
        },
      ],
    });

    await wrapper.get("[data-open-court-ledger]").trigger("click");
    const ledger = wrapper.get("[data-court-ledger-drawer]").text();

    expect(ledger).toContain("第 2 轮补充证据");
    expect(ledger).toContain("商家提交了 1 份补充证据材料");
    expect(ledger).toContain("证据矩阵更新");
    expect(ledger).toContain("v1 → v2");
    expect(ledger).toContain("第二轮补证后更新证据矩阵");
    expect(ledger).toContain("第 3 轮评审团复核报告");
    expect(ledger).toContain("评审团建议法官在草案中说明证据缺口");
    expect(ledger).not.toContain("active_version");
    expect(ledger).not.toContain("JURY_REVIEW_REPORT");
  });

  it("adds final draft, reviewer handoff and execution assistant events to the hearing ledger", async () => {
    const { wrapper } = await mountView({
      initialEvents: [
        {
          sequence_no: 21,
          event_type: "FINAL_DRAFT_REQUIRED",
          payload_json: JSON.stringify({ round_no: 3 }),
        },
        {
          sequence_no: 22,
          event_type: "HEARING_PHASE_CHANGED",
          payload_json: JSON.stringify({
            current_round_no: 3,
            phase_label: "裁决草案已生成",
            next_step_hint: "可以进入结果页查看裁决草案，并等待后续确认。",
          }),
        },
        {
          sequence_no: 23,
          event_type: "EXECUTION_ASSISTANT_HANDOFF",
          payload_json: JSON.stringify({
            status: "EXECUTION_ASSISTANT_HANDOFF",
            approval_record_id: "APPROVAL_1",
            reviewer_id: "reviewer-local",
          }),
        },
      ],
    });

    await wrapper.get("[data-open-court-ledger]").trigger("click");
    const ledger = wrapper.get("[data-court-ledger-drawer]").text();

    expect(ledger).toContain("法官进入裁决草案生成");
    expect(ledger).toContain("草案生成中");
    expect(ledger).toContain("裁决草案状态更新");
    expect(ledger).toContain("裁决草案已生成");
    expect(ledger).toContain("执行专员助手");
    expect(ledger).toContain("裁决已确认，方案已移交给执行专员助手处理");
    expect(ledger).not.toContain("approval_record_id");
    expect(ledger).not.toContain("EXECUTION_ASSISTANT_HANDOFF");
  });

  it("shows a friendly empty ledger when no hearing rounds have started", async () => {
    const { wrapper } = await mountView({
      initialHearing: { rounds: [], settlements: [] },
    });

    await wrapper.get("[data-open-court-ledger]").trigger("click");

    expect(wrapper.get("[data-round-ledger-empty]").text()).toContain(
      "第一轮庭审记录生成后",
    );
    expect(wrapper.get("[data-round-ledger-empty]").text()).not.toContain(
      "提出一致方案",
    );
  });
});
