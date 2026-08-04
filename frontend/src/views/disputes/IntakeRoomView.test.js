// 文件作用：自动化测试文件，验证 IntakeRoomView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { flushPromises, mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { actor } from "../../state/actor";
import {
  agentStreamStore,
  clearAgentStreams,
} from "../../stores/agentStream";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
import { disputeStore } from "../../stores/dispute";
import IntakeRoomView from "./IntakeRoomView.vue";

// 业务位置：【前端接待室】readUtf8Source：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function readUtf8Source(path) {
  return readFileSync(path, "utf8").replace(/\r\n/g, "\n");
}

const dispute = {
  id: "CASE_INTAKE_1",
  order_id: "ORDER-1",
  after_sale_id: "AFTER-1",
  title: "签收未收到",
  description: "物流显示签收，但用户没有收到。",
  dispute_type: "SIGNED_NOT_RECEIVED",
  risk_level: "HIGH",
  current_room: "INTAKE",
};

const analysis = {
  initiator_role: "USER",
  order_reference: "ORDER-1",
  after_sales_reference: "AFTER-1",
  logistics_reference: "LOG-1",
  party_claims: {
    user: "未收到包裹",
    merchant: "等待商家回应",
  },
  requested_outcome: "核实签收并退款",
  initial_risk_signals: ["签收人与收件人不一致"],
  admission_recommendation: "建议受理",
};

const readyTurnMemory = {
  turn_no: 1,
  case_intake_dossier: {
    dossier_version: 1,
    quality_score: 88,
    ready_for_next_step: true,
    admission_recommendation: "ACCEPTED",
    dossier: {
      schema_version: "intake_case_detail.v1",
      case_story: {
        one_sentence_summary: "用户称物流显示签收，但本人没有收到包裹。",
      },
      references: {
        order_reference: "ORDER-1",
        after_sales_reference: "AFTER-1",
        logistics_reference: "LOG-1",
      },
      claim_resolution: {
        initiator_role: "USER",
        requested_resolution: "核实签收并退款",
        original_statement: dispute.description,
      },
      respondent_attitude: {
        respondent_role: "MERCHANT",
        attitude: "NOT_RESPONDED",
        position: "商家尚未回应",
      },
      dispute_core_state: {
        core_conflict: "签收记录与用户未收货陈述存在冲突。",
        next_verification_focus: ["核验签收人身份", "核验实际投递位置"],
      },
      intake_quality: {
        score: 88,
        threshold: 85,
        ready_for_next_step: true,
      },
    },
  },
};

function formalTurnMemory(summary, dossierVersion = 1, sourceTurnNo = 1) {
  return {
    turn_no: sourceTurnNo,
    case_intake_dossier: {
      dossier_version: dossierVersion,
      source_turn_no: sourceTurnNo,
      quality_score: 100,
      ready_for_next_step: true,
      admission_recommendation: "ACCEPTED",
      dossier: {
        schema_version: "intake-dossier.v2",
        case_story: { one_sentence_summary: summary },
      },
    },
  };
}

function intakeProjectionReadyEvent(logicalRunId, payloadOverrides = {}) {
  return {
    id: 41,
    event: "INTAKE_PROJECTION_READY",
    data: {
      payload_json: JSON.stringify({
        logical_run_id: logicalRunId,
        attempt_id: `${logicalRunId}:1`,
        process_revision: 13,
        room_revision: 8,
        room_epoch: 4,
        fencing_token: 9,
        command_sequence: 7,
        event_id: `intake-ready:${logicalRunId}`,
        command_admission_state: "READY",
        ...payloadOverrides,
      }),
    },
  };
}

const connectedModelHealth = vi.fn().mockResolvedValue({
  status: "UP",
  model_status: "CONNECTED",
});

function currentProcessProjection(overrides = {}) {
  const projection = {
    schema_version: "intake-process-projection.v1",
    projection_state: "CURRENT",
    writer_mode: "SHADOW",
    command_admission_state: "READY",
    room_epoch: 4,
    process_revision: 12,
    room_revision: 7,
    fencing_token: 9,
    room_phase: "OPEN",
    pending_state: "NONE",
    active_logical_run_id: null,
    active_attempt_id: null,
    active_run_status: null,
    stream_cursor: null,
    version_pins: {},
    projected_at: "2026-07-22T03:04:05Z",
    ...overrides,
  };
  if (!Object.hasOwn(overrides, "pending_state")) {
    projection.pending_state = {
      OPEN: "NONE",
      WAITING_PARTY: "WAITING_PARTY",
      AGENT_RUNNING: "AGENT_RUNNING",
      READY_TO_CONFIRM: "NONE",
      CLOSED: "NONE",
      COMPLETED: "NONE",
    }[projection.room_phase];
  }
  return projection;
}

function currentCamelProcessProjection(overrides = {}) {
  const projection = {
    schemaVersion: "intake-process-projection.v1",
    projectionState: "CURRENT",
    writerMode: "SHADOW",
    commandAdmissionState: "READY",
    roomEpoch: 4,
    processRevision: 12,
    roomRevision: 7,
    fencingToken: 9,
    roomPhase: "OPEN",
    pendingState: "NONE",
    activeLogicalRunId: null,
    activeAttemptId: null,
    activeRunStatus: null,
    streamCursor: null,
    versionPins: {},
    projectedAt: "2026-07-22T03:04:05Z",
    ...overrides,
  };
  if (!Object.hasOwn(overrides, "pendingState")) {
    projection.pendingState = {
      OPEN: "NONE",
      WAITING_PARTY: "WAITING_PARTY",
      AGENT_RUNNING: "AGENT_RUNNING",
      READY_TO_CONFIRM: "NONE",
      CLOSED: "NONE",
      COMPLETED: "NONE",
    }[projection.roomPhase];
  }
  return projection;
}

function intakeStatusWithProjection(processProjection, overrides = {}) {
  const status = {
    initiator_role: "USER",
    respondent_role: "MERCHANT",
    initiator_status: "OPEN",
    respondent_status: "LOCKED",
    current_actor_completed: false,
    can_use_intake: true,
    can_enter_evidence: true,
    ...overrides,
  };
  if (processProjection !== undefined) {
    status.process_projection = processProjection;
  }
  return status;
}

function apiResponse(data) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ success: true, data }),
  };
}

function terminalStreamResponse(runId) {
  const frame = [
    "id: 0",
    "event: final",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 0,
      type: "final",
      result: {},
    })}`,
    "",
    "",
  ].join("\n");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frame));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

function dossierStreamResponse(runId, summary) {
  const frames = [
    "id: 1",
    "event: visible_delta",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 1,
      type: "visible_delta",
      field: "case_detail.case_story",
      delta: JSON.stringify({ one_sentence_summary: summary }),
    })}`,
    "",
    "id: 2",
    "event: final",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 2,
      type: "final",
      result: {},
    })}`,
    "",
    "",
  ].join("\n");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

function replyThenDossierStreamResponse(runId, reply, summary) {
  const frames = [
    "id: 0",
    "event: start",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 0,
      type: "start",
    })}`,
    "",
    "id: 1",
    "event: visible_delta",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 1,
      type: "visible_delta",
      field: "room_utterance",
      delta: reply,
    })}`,
    "",
    "id: 2",
    "event: visible_delta",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 2,
      type: "visible_delta",
      field: "case_detail.case_story",
      delta: JSON.stringify({ one_sentence_summary: summary }),
    })}`,
    "",
    "id: 3",
    "event: final",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 3,
      type: "final",
      result: {},
    })}`,
    "",
    "",
  ].join("\n");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

function caseDetailSequenceStreamResponse(runId, deltas) {
  const frame = (sequence, event, payload = {}) => [
    `id: ${sequence}`,
    `event: ${event}`,
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence,
      type: event,
      ...payload,
    })}`,
    "",
    "",
  ].join("\n");
  const frames = [
    ...deltas.map((delta, index) => frame(index + 1, "visible_delta", delta)),
    frame(deltas.length + 1, "final", { result: {} }),
  ].join("");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

function v2AttemptResetDossierStreamResponse(runId, oldSummary, newSummary) {
  const oldAttemptId = "ATTEMPT_OLD";
  const newAttemptId = "ATTEMPT_NEW";
  const frame = (attemptId, sequence, event, payload = {}, extra = {}) => [
    `id: v2:${attemptId}:${sequence}`,
    `event: ${event}`,
    `data: ${JSON.stringify({
      protocol: "agent-stream.v2",
      runId,
      attemptId,
      sequence,
      cursor: `v2:${attemptId}:${sequence}`,
      audience: "USER",
      ...extra,
      payload,
    })}`,
    "",
    "",
  ].join("\n");
  const frames = [
    frame(oldAttemptId, 0, "attempt_started", { node: "turn" }),
    frame(oldAttemptId, 1, "visible_delta", {
      node: "turn",
      field: "room_utterance",
      delta: "旧 attempt 的接待回复。",
    }),
    frame(oldAttemptId, 2, "visible_delta", {
      node: "turn",
      field: "case_detail.case_story.one_sentence_summary",
      delta: oldSummary,
    }),
    frame(oldAttemptId, 3, "attempt_aborted", {
      reasonCode: "MODEL_TRANSIENT_FAILURE",
    }),
    frame(newAttemptId, 0, "attempt_started", { node: "turn" }),
    frame(newAttemptId, 1, "attempt_reset", {
      reasonCode: "RETRY",
      resetAttemptId: oldAttemptId,
    }, { resetAttemptId: oldAttemptId }),
    frame(newAttemptId, 2, "visible_delta", {
      node: "turn",
      field: "case_detail.case_story.one_sentence_summary",
      delta: newSummary,
    }),
    frame(newAttemptId, 3, "final", {}, { response: {} }),
  ].join("");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

function failedDossierStreamResponse(runId, summary) {
  const frames = [
    "id: 1",
    "event: visible_delta",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 1,
      type: "visible_delta",
      field: "room_utterance",
      delta: "先完成这段接待回复。",
    })}`,
    "",
    "id: 2",
    "event: visible_delta",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 2,
      type: "visible_delta",
      field: "case_detail.case_story",
      delta: JSON.stringify({ one_sentence_summary: summary }),
    })}`,
    "",
    "id: 3",
    "event: error",
    `data: ${JSON.stringify({
      schemaVersion: "agent_stream.v1",
      runId,
      sequence: 3,
      type: "error",
      error: {
        code: "INTAKE_TEST_FAILURE",
        message: "target intake stream failed",
      },
    })}`,
    "",
    "",
  ].join("\n");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frames));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

function installIntakeApiFetch({
  status,
  activeRuns = [],
  disputeValue = dispute,
} = {}) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url === "/api/disputes/CASE_INTAKE_1") {
      return apiResponse(disputeValue);
    }
    if (url === "/api/disputes/CASE_INTAKE_1/intake/status") {
      return apiResponse(typeof status === "function" ? status() : status);
    }
    if (url === "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/messages") {
      return apiResponse([]);
    }
    if (url === "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/turn-memory/latest") {
      return apiResponse(readyTurnMemory);
    }
    if (url === "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active") {
      return apiResponse(activeRuns);
    }
    if (url.includes("last_event_id=")) {
      const descriptor = activeRuns.find((run) =>
        url.startsWith(String(run.stream_url ?? run.streamUrl ?? "")),
      );
      const runId = descriptor?.run_id ?? descriptor?.runId ?? "UNKNOWN_RUN";
      return terminalStreamResponse(runId);
    }
    throw new Error(`unexpected fetch: ${url}`);
  });
}

// 业务位置：【前端接待室】mountView：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function mountView(confirmAction = vi.fn(), eventStreamer = null, cancelAction = vi.fn()) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes", component: { template: "<div />" } },
      { path: "/disputes/:caseId/intake", component: { template: "<div />" } },
      { path: "/disputes/:caseId/evidence", component: { template: "<div />" } },
    ],
  });
  await router.push("/disputes/CASE_INTAKE_1/intake");
  await router.isReady();
  const wrapper = mount(IntakeRoomView, {
    props: {
      initialDispute: dispute,
      initialAnalysis: analysis,
      initialTurnMemory: readyTurnMemory,
      initialMessages: [],
      confirmAction,
      cancelAction,
      eventStreamer,
      modelHealthLoader: connectedModelHealth,
    },
    global: { plugins: [router] },
  });
  await flushPromises();
  return { wrapper, router, confirmAction, cancelAction };
}

// 业务位置：【前端接待室】mountInteractiveView：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function mountInteractiveView(options = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/disputes", component: { template: "<div />" } },
      { path: "/disputes/:caseId/intake", component: { template: "<div />" } },
      { path: "/disputes/:caseId/evidence", component: { template: "<div />" } },
    ],
  });
  await router.push(
    options.historyMode
      ? "/disputes/CASE_INTAKE_1/intake?view=history"
      : "/disputes/CASE_INTAKE_1/intake",
  );
  await router.isReady();
  const wrapper = mount(IntakeRoomView, {
    props: {
      initialDispute: Object.hasOwn(options, "initialDispute")
        ? options.initialDispute
        : dispute,
      initialAnalysis: options.initialAnalysis || analysis,
      initialMessages: Object.hasOwn(options, "initialMessages") ? options.initialMessages : [],
      initialTurnMemory: Object.hasOwn(options, "initialTurnMemory")
        ? options.initialTurnMemory
        : readyTurnMemory,
      initialIntakeStatus: options.initialIntakeStatus,
      postMessageAction: options.postMessageAction,
      openingAction: options.openingAction,
      messagesLoader: options.messagesLoader,
      turnMemoryLoader: options.turnMemoryLoader,
      intakeStatusLoader: options.intakeStatusLoader,
      confirmAction: options.confirmAction || vi.fn(),
      cancelAction: options.cancelAction,
      eventStreamer: options.eventStreamer,
      modelHealthLoader: options.modelHealthLoader || connectedModelHealth,
      evidenceReadyPollAttempts: options.evidenceReadyPollAttempts,
      evidenceReadyPollDelayMs: options.evidenceReadyPollDelayMs,
      formalReadinessPollAttempts: options.formalReadinessPollAttempts,
      formalReadinessPollDelayMs: options.formalReadinessPollDelayMs,
    },
    global: { plugins: [router] },
    attachTo: options.attachTo,
  });
  await flushPromises();
  return wrapper;
}

// 业务位置：【前端接待室】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
describe("IntakeRoomView", () => {
  beforeEach(() => {
    clearAgentStreams({}, { abort: true });
    actor.id = "user-local";
    actor.role = "USER";
    disputeStore.list.data = [];
    disputeStore.current.data = null;
  });

  afterEach(() => {
    clearAgentStreams({}, { abort: true });
    vi.useRealTimers();
    globalThis.fetch?.mockRestore?.();
  });

  it("keeps the respondent in a locked intake state before the initiator completes", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const wrapper = await mountInteractiveView({
      initialIntakeStatus: {
        initiator_status: "OPEN",
        respondent_status: "LOCKED",
        current_actor_completed: false,
        can_use_intake: false,
        can_enter_evidence: false,
      },
    });

    expect(wrapper.get("[data-intake-locked-chat]").text()).toContain("接待会话尚未开放");
    expect(wrapper.get("[data-intake-waiting-for-initiator]").text()).toContain("等待发起方");
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(false);
    expect(wrapper.find("textarea").exists()).toBe(false);
  });

  it("opens a private respondent intake and enters evidence only after respondent confirmation", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const confirmAction = vi.fn().mockResolvedValue({
      case_status: "INTAKE_COMPLETED",
      current_room: "INTAKE",
      deadline_at: null,
    });
    const bilateralMemory = structuredClone(readyTurnMemory);
    bilateralMemory.case_intake_dossier.dossier.case_fact_matrix = {
      schema_version: "case_fact_matrix.v2",
      matrix_kind: "BILATERAL_FROZEN",
    };
    const intakeStatusLoader = vi.fn().mockResolvedValue({
      initiator_status: "COMPLETED",
      respondent_status: "COMPLETED",
      current_actor_completed: true,
      can_use_intake: false,
      can_enter_evidence: true,
    });
    const wrapper = await mountInteractiveView({
      initialTurnMemory: bilateralMemory,
      initialIntakeStatus: {
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
        evidence_deadline_at: "2026-07-15T02:00:00Z",
      },
      confirmAction,
      intakeStatusLoader,
    });

    expect(wrapper.find("[data-intake-locked-chat]").exists()).toBe(false);
    expect(wrapper.get("[data-confirm-admission]").text()).toContain("确认陈述并进入证据室");
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(confirmAction).toHaveBeenCalledWith(expect.objectContaining({ admissible: true }));
    expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
    expect(wrapper.vm.$router.currentRoute.value.path)
      .toBe("/disputes/CASE_INTAKE_1/evidence");
  });

  it("rechecks bilateral completion before routing to evidence", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const bilateralMemory = structuredClone(readyTurnMemory);
    bilateralMemory.case_intake_dossier.dossier.case_fact_matrix = {
      schema_version: "case_fact_matrix.v2",
      matrix_kind: "BILATERAL_FROZEN",
    };
    let releaseEvidenceReady;
    const evidenceReady = new Promise((resolve) => {
      releaseEvidenceReady = resolve;
    });
    const intakeStatusLoader = vi.fn()
      .mockResolvedValueOnce({
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: true,
        can_use_intake: false,
        can_enter_evidence: false,
      })
      .mockReturnValueOnce(evidenceReady);
    const wrapper = await mountInteractiveView({
      initialTurnMemory: bilateralMemory,
      initialIntakeStatus: {
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
      confirmAction: vi.fn().mockResolvedValue({
        case_status: "EVIDENCE_OPEN",
        current_room: "EVIDENCE",
        deadline_at: "2026-07-15T02:00:00Z",
      }),
      intakeStatusLoader,
      evidenceReadyPollAttempts: 2,
      evidenceReadyPollDelayMs: 0,
    });

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.vm.$router.currentRoute.value.path)
      .toBe("/disputes/CASE_INTAKE_1/intake");
    expect(wrapper.get("[data-waiting-for-evidence-ready]").text())
      .toContain("双方完成状态正在核验");

    releaseEvidenceReady({
      initiator_status: "COMPLETED",
      respondent_status: "COMPLETED",
      current_actor_completed: true,
      can_use_intake: false,
      can_enter_evidence: true,
    });
    await flushPromises();

    expect(wrapper.vm.$router.currentRoute.value.path)
      .toBe("/disputes/CASE_INTAKE_1/evidence");
  });

  it("uses the server actor-id party position instead of inferring the side from role alone", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const wrapper = await mountInteractiveView({
      initialDispute: {
        ...dispute,
        initiator_id: "merchant-local",
        initiator_role: "MERCHANT",
        respondent_id: "user-local",
        respondent_role: "USER",
        party_position: "RESPONDENT",
      },
      initialIntakeStatus: {
        initiator_role: "MERCHANT",
        respondent_role: "USER",
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
    });

    expect(wrapper.get("[data-confirm-admission]").text()).toContain(
      "确认陈述并进入证据室",
    );
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
  });

  it("keeps the initiator in intake after submitting its statement", async () => {
    const confirmAction = vi.fn().mockResolvedValue({
      case_status: "INTAKE_COMPLETED",
      current_room: "INTAKE",
      deadline_at: null,
    });
    const wrapper = await mountInteractiveView({
      initialIntakeStatus: {
        initiator_role: "USER",
        respondent_role: "MERCHANT",
        initiator_status: "OPEN",
        respondent_status: "LOCKED",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
      confirmAction,
      intakeStatusLoader: vi.fn().mockResolvedValue({
        initiator_role: "USER",
        respondent_role: "MERCHANT",
        initiator_status: "COMPLETED",
        respondent_status: "LOCKED",
        current_actor_completed: true,
        can_use_intake: false,
        can_enter_evidence: false,
      }),
      evidenceReadyPollAttempts: 1,
      evidenceReadyPollDelayMs: 0,
      eventStreamer: vi.fn(async () => {}),
    });

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(false);
    expect(wrapper.get("[data-waiting-for-respondent-intake]").text()).toContain(
      "等待对方独立完善陈述",
    );
  });

  it("isolates the respondent original statement from the initiator dossier", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const respondentMemory = structuredClone(readyTurnMemory);
    respondentMemory.case_intake_dossier.dossier.claim_resolution = {
      initiator_role: "MERCHANT",
      requested_resolution: "REPLACE_OR_REPAIR",
      original_statement: "商家发起方的原始陈述不得展示给用户。",
    };
    const merchantAnalysis = {
      ...analysis,
      initiator_role: "MERCHANT",
    };

    const emptyWrapper = await mountInteractiveView({
      initialAnalysis: merchantAnalysis,
      initialTurnMemory: respondentMemory,
      initialMessages: [],
    });

    expect(
      emptyWrapper.get("[data-origin-statement-text]").attributes("title"),
    ).toBe("暂无原始陈述");
    emptyWrapper.unmount();

    const repliedWrapper = await mountInteractiveView({
      initialAnalysis: merchantAnalysis,
      initialTurnMemory: respondentMemory,
      initialMessages: [
        {
          id: "MESSAGE_RESPONDENT_1",
          sequence_no: 3,
          sender_type: "PARTY",
          sender_role: "USER",
          message_text: "没有拆封，也没有激活手机。",
        },
      ],
    });

    expect(
      repliedWrapper.get("[data-origin-statement-text]").attributes("title"),
    ).toBe("没有拆封，也没有激活手机。");
    expect(
      repliedWrapper.get("[data-origin-statement-text]").attributes("title"),
    ).not.toContain("商家发起方");
  });

  it("creates one matrix-guided opening when an eligible respondent thread is empty", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const openingAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_RESPONDENT_OPENING",
      sequence_no: 1,
    });
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_RESPONDENT_OPENING",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "以下为发起方尚未核验的陈述，请先回应诉求和核心事实。",
        },
      ]);

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialIntakeStatus: {
        initiator_role: "USER",
        respondent_role: "MERCHANT",
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
      messagesLoader,
      openingAction,
      eventStreamer: vi.fn(async () => {}),
    });

    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(openingAction).toHaveBeenCalledWith(
      { id: "merchant-local", role: "MERCHANT" },
      "CASE_INTAKE_1",
      "INTAKE",
    );
    expect(messagesLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("请先回应诉求和核心事实");
  });

  it("starts respondent completeness independently from the initiator dossier", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const initiatorMemory = structuredClone(readyTurnMemory);
    initiatorMemory.case_intake_dossier.dossier.case_fact_matrix = {
      schema_version: "case_fact_matrix.v2",
      matrix_kind: "INITIATOR_FROZEN",
    };
    const wrapper = await mountInteractiveView({
      initialTurnMemory: initiatorMemory,
      initialIntakeStatus: {
        initiator_role: "USER",
        respondent_role: "MERCHANT",
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
      eventStreamer: vi.fn(async () => {}),
    });

    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("完善度 0%");
    expect(wrapper.get("[data-dossier-status-rail]").text()).not.toContain("完善度 88%");
  });

  it("creates one opening when a TEMPORAL initiator enters an empty intake room", async () => {
    const openingAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_INITIATOR_OPENING",
      sequence_no: 1,
    });
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_INITIATOR_OPENING",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "请说明争议经过和处理诉求。",
        },
      ]);

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      messagesLoader,
      openingAction,
      eventStreamer: vi.fn(async () => {}),
    });

    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(openingAction).toHaveBeenCalledWith(
      { id: "user-local", role: "USER" },
      "CASE_INTAKE_1",
      "INTAKE",
    );
    expect(messagesLoader).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("consumes an AgentRun descriptor returned directly by the TEMPORAL opening", async () => {
    const runId = "run-temporal-opening-response";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) return terminalStreamResponse(runId);
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const formalMemory = formalTurnMemory("正式首轮卷宗已同步", 1, 1);
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValue([
        {
          id: "MESSAGE_AGENT_OPENING_FINAL",
          sequence_no: 2,
          sender_type: "AGENT",
          sender_role: "CUSTOMER_SERVICE",
          agent_run_id: runId,
          message_text: "formal opening reply",
        },
      ]);
    const openingAction = vi.fn().mockResolvedValue({
      accepted_run: { run_id: runId, stream_url: streamUrl },
    });

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      intakeStatusLoader: vi.fn().mockResolvedValue(
        intakeStatusWithProjection(
          currentProcessProjection({ writer_mode: "TEMPORAL" }),
        ),
      ),
      messagesLoader,
      turnMemoryLoader: vi.fn().mockResolvedValue(formalMemory),
      openingAction,
      eventStreamer: vi.fn(async () => {}),
      formalReadinessPollAttempts: 1,
      formalReadinessPollDelayMs: 0,
    });

    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.some(([input]) => String(input).startsWith(streamUrl)))
      .toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });
    expect(wrapper.text()).toContain("正式首轮卷宗已同步");
    wrapper.unmount();
  });

  it("subscribes before opening and wakes its formal wait from the READY event", async () => {
    vi.useFakeTimers();
    const runId = "run-temporal-opening-ready-event";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    const order = [];
    let applyCaseEvent;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) return terminalStreamResponse(runId);
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const pendingStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        command_admission_state: "PENDING",
      }),
    );
    const readyStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const messagesLoader = vi.fn()
      .mockResolvedValueOnce([])
      .mockResolvedValue([{
        id: "MESSAGE_OPENING_READY_EVENT",
        sequence_no: 1,
        sender_type: "AGENT",
        sender_role: "CUSTOMER_SERVICE",
        agent_run_id: runId,
        message_text: "首轮正式回复已就绪",
      }]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(
      formalTurnMemory("首轮事件驱动正式卷宗", 1, 1),
    );
    const intakeStatusLoader = vi.fn()
      .mockResolvedValueOnce(pendingStatus)
      .mockResolvedValueOnce(readyStatus);
    const openingAction = vi.fn(async () => {
      order.push("opening");
      return { accepted_run: { run_id: runId, stream_url: streamUrl } };
    });
    const eventStreamer = vi.fn(async ({ applyEvent }) => {
      order.push("subscribed");
      applyCaseEvent = applyEvent;
    });

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialTurnMemory: null,
      initialIntakeStatus: readyStatus,
      messagesLoader,
      turnMemoryLoader,
      intakeStatusLoader,
      openingAction,
      eventStreamer,
    });
    await vi.waitFor(() => {
      expect(applyCaseEvent).toBeTypeOf("function");
      expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
      expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    });

    expect(order).toEqual(["subscribed", "opening"]);
    await applyCaseEvent(intakeProjectionReadyEvent(runId));
    await flushPromises();
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });

    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("首轮事件驱动正式卷宗");
    await vi.advanceTimersByTimeAsync(4_999);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("keeps opening and formal fallback available when event cursor priming fails", async () => {
    vi.useFakeTimers();
    const runId = "run-temporal-opening-prime-degraded";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes("/events/replay")) {
        return new Response(JSON.stringify({
          code: "EVENT_REPLAY_UNAVAILABLE",
          message: "event replay unavailable",
        }), {
          status: 503,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (url.startsWith(streamUrl)) return terminalStreamResponse(runId);
      throw new Error(`unexpected fetch: ${url}`);
    });
    const pendingStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        command_admission_state: "PENDING",
      }),
    );
    const readyStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const messagesLoader = vi.fn()
      .mockResolvedValueOnce([])
      .mockResolvedValue([{
        id: "MESSAGE_OPENING_PRIME_DEGRADED",
        sequence_no: 1,
        sender_type: "AGENT",
        sender_role: "CUSTOMER_SERVICE",
        agent_run_id: runId,
        message_text: "事件通道降级时的正式首轮回复",
      }]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(
      formalTurnMemory("事件通道降级时的正式首轮卷宗", 1, 1),
    );
    const intakeStatusLoader = vi.fn()
      .mockResolvedValueOnce(pendingStatus)
      .mockResolvedValueOnce(readyStatus);
    const openingAction = vi.fn().mockResolvedValue({
      accepted_run: { run_id: runId, stream_url: streamUrl },
    });

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialTurnMemory: null,
      initialIntakeStatus: readyStatus,
      messagesLoader,
      turnMemoryLoader,
      intakeStatusLoader,
      openingAction,
      formalReadinessPollAttempts: 3,
      formalReadinessPollDelayMs: 0,
    });
    await flushPromises();
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });

    expect(fetchMock.mock.calls.some(([input]) => String(input).includes("/events/replay")))
      .toBe(true);
    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("事件通道降级时的正式首轮卷宗");
    wrapper.unmount();
  });

  it("uses the default AgentRun events URL when Java opening returns only runId", async () => {
    const runId = "run-temporal-opening-run-id-only";
    const defaultStreamUrl = `/api/agent-runs/${runId}/events`;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(defaultStreamUrl)) return terminalStreamResponse(runId);
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const formalMemory = formalTurnMemory("正式首轮卷宗已同步", 1, 1);
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValue([
        {
          id: "MESSAGE_AGENT_OPENING_RUN_ID_ONLY",
          sequence_no: 2,
          sender_type: "AGENT",
          sender_role: "CUSTOMER_SERVICE",
          agent_run_id: runId,
          message_text: "formal opening reply",
        },
      ]);
    const openingAction = vi.fn().mockResolvedValue({ runId });

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      intakeStatusLoader: vi.fn().mockResolvedValue(
        intakeStatusWithProjection(
          currentProcessProjection({ writer_mode: "TEMPORAL" }),
        ),
      ),
      messagesLoader,
      turnMemoryLoader: vi.fn().mockResolvedValue(formalMemory),
      openingAction,
      eventStreamer: vi.fn(async () => {}),
      formalReadinessPollAttempts: 1,
      formalReadinessPollDelayMs: 0,
    });

    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.some(([input]) =>
      String(input).startsWith(defaultStreamUrl),
    )).toBe(true);
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });
    wrapper.unmount();
  });

  it("retries a TEMPORAL final until the formal dossier becomes visible", async () => {
    const runId = "run-temporal-formal-retry";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "流式草稿卷宗");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_AGENT_FORMAL_RETRY",
          sequence_no: 3,
          sender_type: "AGENT",
          sender_role: "CUSTOMER_SERVICE",
          agent_run_id: runId,
          message_text: "正式回复已持久化",
        },
      ]);
    const turnMemoryLoader = vi
      .fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(formalTurnMemory("正式持久卷宗已可见", 1, 1));
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      intakeStatusLoader: vi.fn().mockResolvedValue(
        intakeStatusWithProjection(
          currentProcessProjection({ writer_mode: "TEMPORAL" }),
        ),
      ),
      postMessageAction: vi.fn().mockResolvedValue({
        agent_run: { run_id: runId, stream_url: streamUrl },
      }),
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
      formalReadinessPollAttempts: 3,
      formalReadinessPollDelayMs: 0,
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "start a target intake turn",
      attachment_refs: [],
    });
    await flushPromises();
    await flushPromises();

    await vi.waitFor(() => {
      expect(turnMemoryLoader).toHaveBeenCalledTimes(2);
    });

    expect(turnMemoryLoader).toHaveBeenCalledTimes(2);
    expect(messagesLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("正式持久卷宗已可见");
    expect(wrapper.text()).not.toContain("流式草稿卷宗");
    wrapper.unmount();
  });

  it("wakes a locked TEMPORAL final on its exact projection READY event", async () => {
    vi.useFakeTimers();
    const runId = "run-temporal-command-admission";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    let applyCaseEvent;
    const pendingStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        command_admission_state: "PENDING",
      }),
    );
    const initialReadyStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const readyStatus = intakeStatusWithProjection(
      currentCamelProcessProjection({
        writerMode: "TEMPORAL",
        commandAdmissionState: "READY",
      }),
    );
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "等待正式准入的流式草稿");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi.fn().mockResolvedValue([{
      id: "MESSAGE_FORMAL_COMMAND_ADMISSION",
      sequence_no: 4,
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      agent_run_id: runId,
      message_text: "正式回复已可见，但命令准入仍在确认。",
    }]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(
      formalTurnMemory("正式卷宗已可见，等待命令准入", 1, 1),
    );
    const intakeStatusLoader = vi.fn()
      .mockResolvedValueOnce(pendingStatus)
      .mockResolvedValueOnce(readyStatus);
    const postMessageAction = vi.fn().mockResolvedValue({
      run_id: runId,
      stream_url: streamUrl,
    });
    const eventStreamer = vi.fn(async ({ applyEvent }) => {
      applyCaseEvent = applyEvent;
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: initialReadyStatus,
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
      intakeStatusLoader,
      eventStreamer,
      formalReadinessPollAttempts: 3,
      formalReadinessPollDelayMs: 1_000,
    });
    await vi.waitFor(() => expect(applyCaseEvent).toBeTypeOf("function"));

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "wait for formal command admission",
      attachment_refs: [],
    });
    await flushPromises();
    await vi.waitFor(() => {
      expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
      expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    });

    expect(messagesLoader).toHaveBeenCalledTimes(1);
    expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    expect(wrapper.vm.$.setupState.messages).toEqual(expect.arrayContaining([
      expect.objectContaining({ agent_run_id: runId }),
    ]));
    expect(wrapper.vm.$.setupState.turnMemory.case_intake_dossier.dossier.case_story)
      .toEqual({ one_sentence_summary: "正式卷宗已可见，等待命令准入" });
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(true);
    expect(wrapper.get("textarea").attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("本轮回复已生成，正在同步正式卷宗");
    expect(wrapper.text()).not.toContain("对方完成接待后");
    expect(postMessageAction).toHaveBeenCalledTimes(1);

    await applyCaseEvent(intakeProjectionReadyEvent(runId));
    await flushPromises();
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });

    expect(postMessageAction).toHaveBeenCalledTimes(1);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(messagesLoader).toHaveBeenCalledTimes(2);
    expect(turnMemoryLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.get("textarea").attributes("disabled")).toBeUndefined();
    await vi.advanceTimersByTimeAsync(999);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("does not lose a projection READY event that arrives before the fallback waiter", async () => {
    vi.useFakeTimers();
    const runId = "run-target-temporal-ready-before-waiter";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    let applyCaseEvent;
    let resolveFirstStatus;
    const firstStatus = new Promise((resolve) => {
      resolveFirstStatus = resolve;
    });
    const pendingStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        command_admission_state: "PENDING",
      }),
    );
    const initialReadyStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const readyStatus = intakeStatusWithProjection(
      currentCamelProcessProjection({
        writerMode: "TEMPORAL",
        commandAdmissionState: "READY",
      }),
    );
    const baselineTurnMemory = formalTurnMemory("基线正式卷宗", 1, 1);
    const formalMemory = formalTurnMemory("正式卷宗已同步完成", 2, 2);
    const targetTemporalRun = {
      run_id: runId,
      stream_url: streamUrl,
    };
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "流式草稿卷宗");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi.fn().mockResolvedValue([{
      id: "MESSAGE_FORMAL_TARGET_EVENT_LATCH",
      sequence_no: 5,
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      agent_run_id: runId,
      message_text: "正式回复已完成准入",
    }]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(formalMemory);
    const intakeStatusLoader = vi.fn()
      .mockImplementationOnce(() => firstStatus)
      .mockResolvedValueOnce(readyStatus);
    const postMessageAction = vi.fn().mockResolvedValue(targetTemporalRun);
    const eventStreamer = vi.fn(async ({ applyEvent }) => {
      applyCaseEvent = applyEvent;
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: baselineTurnMemory,
      initialIntakeStatus: initialReadyStatus,
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
      intakeStatusLoader,
      eventStreamer,
    });
    await vi.waitFor(() => expect(applyCaseEvent).toBeTypeOf("function"));

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "等待 Target TEMPORAL 正式准入",
      attachment_refs: [],
    });
    await vi.waitFor(() => {
      expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
      expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    });

    await applyCaseEvent(intakeProjectionReadyEvent(runId));
    await flushPromises();
    expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(true);
    expect(wrapper.get("textarea").attributes("disabled")).toBeDefined();
    expect(wrapper.text()).not.toContain("对方完成接待后");
    expect(postMessageAction).toHaveBeenCalledTimes(1);
    expect(messagesLoader).toHaveBeenCalledTimes(1);
    expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    resolveFirstStatus(pendingStatus);
    await flushPromises();

    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });

    expect(postMessageAction).toHaveBeenCalledTimes(1);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(wrapper.vm.$.setupState.messages).toEqual(expect.arrayContaining([
      expect.objectContaining({
        agent_run_id: runId,
        message_text: "正式回复已完成准入",
      }),
    ]));
    expect(wrapper.vm.$.setupState.turnMemory.case_intake_dossier.dossier.case_story)
      .toEqual({ one_sentence_summary: "正式卷宗已同步完成" });
    expect(wrapper.text()).toContain("正式回复已完成准入");
    expect(wrapper.text()).toContain("正式卷宗已同步完成");
    expect(wrapper.text()).not.toContain("流式草稿卷宗");
    expect(wrapper.text()).not.toContain("正式卷宗尚未同步完成");
    expect(wrapper.get("textarea").attributes("disabled")).toBeUndefined();
    wrapper.unmount();
  });

  it("ignores malformed and wrong-run projection READY events", async () => {
    vi.useFakeTimers();
    const runId = "run-target-temporal-ignore-ready-signals";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    let applyCaseEvent;
    const pendingStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        command_admission_state: "PENDING",
      }),
    );
    const readyStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "仅在正式事件后替换的流式草稿");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi.fn().mockResolvedValue([{
      id: "MESSAGE_FORMAL_IGNORE_READY_SIGNALS",
      sequence_no: 5,
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      agent_run_id: runId,
      message_text: "正式事件契约已通过",
    }]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(
      formalTurnMemory("正式事件契约卷宗", 2, 2),
    );
    const intakeStatusLoader = vi.fn()
      .mockResolvedValueOnce(pendingStatus)
      .mockResolvedValueOnce(readyStatus);
    const postMessageAction = vi.fn().mockResolvedValue({
      run_id: runId,
      stream_url: streamUrl,
    });
    const eventStreamer = vi.fn(async ({ applyEvent }) => {
      applyCaseEvent = applyEvent;
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: formalTurnMemory("事件契约基线", 1, 1),
      initialIntakeStatus: readyStatus,
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
      intakeStatusLoader,
      eventStreamer,
    });
    await vi.waitFor(() => expect(applyCaseEvent).toBeTypeOf("function"));

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "验证错误事件不能解锁",
      attachment_refs: [],
    });
    await flushPromises();
    await vi.waitFor(() => {
      expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
      expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    });

    await applyCaseEvent(intakeProjectionReadyEvent("run-other-target"));
    await applyCaseEvent({
      event: "INTAKE_PROJECTION_READY",
      data: { payload_json: "{not-json" },
    });
    await applyCaseEvent(intakeProjectionReadyEvent(runId, { attempt_id: "" }));
    await flushPromises();

    expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
    expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    expect(postMessageAction).toHaveBeenCalledTimes(1);

    await applyCaseEvent(intakeProjectionReadyEvent(runId));
    await flushPromises();
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(postMessageAction).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("clears a provisional right-side board when its V2 attempt aborts before reset", async () => {
    const persistedSummary = "已持久化的正式案情摘要";
    const abortedDraft = "已中止的临时案情摘要";
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        ...readyTurnMemory,
        case_intake_dossier: {
          ...readyTurnMemory.case_intake_dossier,
          dossier: {
            ...readyTurnMemory.case_intake_dossier.dossier,
            case_story: {
              ...readyTurnMemory.case_intake_dossier.dossier.case_story,
              one_sentence_summary: persistedSummary,
            },
          },
        },
      },
      eventStreamer: vi.fn(async () => {}),
    });

    const applyEvent = wrapper.vm.$.setupState.applyStreamedCaseDetailEvent;
    applyEvent({
      event: "visible_delta",
      fieldPath: "case_detail.case_story.one_sentence_summary",
      delta: abortedDraft,
    });
    await wrapper.vm.$nextTick();
    expect(wrapper.get("[data-dispute-detail-summary]").text()).toContain(abortedDraft);

    applyEvent({ event: "attempt_aborted" });
    await wrapper.vm.$nextTick();

    const summary = wrapper.get("[data-dispute-detail-summary]").text();
    expect(summary).toContain(persistedSummary);
    expect(summary).not.toContain(abortedDraft);
    wrapper.unmount();
  });

  it("replaces the streamed right-side board after a V2 attempt reset", async () => {
    const runId = "run-v2-attempt-reset-board";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    let resolveMessages;
    let resolveTurnMemory;
    const messagesLoader = vi.fn(() => new Promise((resolve) => {
      resolveMessages = resolve;
    }));
    const turnMemoryLoader = vi.fn(() => new Promise((resolve) => {
      resolveTurnMemory = resolve;
    }));
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return v2AttemptResetDossierStreamResponse(
          runId,
          "旧展板草稿",
          "新展板草稿",
        );
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: readyTurnMemory,
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "trigger a retryable intake draft",
      attachment_refs: [],
    });

    await vi.waitFor(() => {
      expect(messagesLoader).toHaveBeenCalledTimes(1);
      expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    });

    const summary = wrapper.get("[data-dispute-detail-summary]").text();
    expect(summary).toContain("新展板草稿");
    expect(summary).not.toContain("旧展板草稿");

    resolveMessages([]);
    resolveTurnMemory(readyTurnMemory);
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });
    wrapper.unmount();
  });

  it("deep-merges streamed sections, replaces arrays, and lets a terminal root snapshot replace provisional branch data", async () => {
    const runId = "run-case-detail-terminal-snapshot";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    const persistedTurnMemory = {
      ...readyTurnMemory,
      case_intake_dossier: {
        ...readyTurnMemory.case_intake_dossier,
        dossier: {
          ...readyTurnMemory.case_intake_dossier.dossier,
          missing_information: {
            blocking_gaps: ["persisted blocker"],
            nice_to_have_gaps: ["persisted nice detail"],
            metadata: {
              preserved_nested_detail: "persisted nested detail",
              terminal_override: "persisted nested value",
            },
          },
        },
      },
    };
    let resolveMessages;
    let resolveTurnMemory;
    const messagesLoader = vi.fn(() => new Promise((resolve) => {
      resolveMessages = resolve;
    }));
    const turnMemoryLoader = vi.fn(() => new Promise((resolve) => {
      resolveTurnMemory = resolve;
    }));
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return caseDetailSequenceStreamResponse(runId, [
          {
            field: "case_detail.missing_information.next_questions",
            delta: "obsolete provisional question",
          },
          {
            field: "case_detail.missing_information",
            delta: JSON.stringify({
              blocking_gaps: ["model root blocker"],
              metadata: { terminal_override: "model nested value" },
            }),
          },
          {
            field: "case_detail.missing_information",
            delta: JSON.stringify({
              blocking_gaps: ["terminal root blocker"],
              metadata: { terminal_override: "terminal nested value" },
            }),
          },
        ]);
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: persistedTurnMemory,
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "stream a replacement dossier branch",
      attachment_refs: [],
    });

    await vi.waitFor(() => {
      expect(messagesLoader).toHaveBeenCalledTimes(1);
      expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    });

    const dossierText = wrapper.get("[data-case-detail-dossier]").text();
    expect(dossierText).toContain("terminal root blocker");
    expect(dossierText).toContain("persisted nice detail");
    expect(dossierText).not.toContain("persisted blocker");
    expect(dossierText).not.toContain("model root blocker");
    expect(dossierText).not.toContain("obsolete provisional question");
    expect(wrapper.vm.$.setupState.caseDetailDossier.missing_information.metadata)
      .toEqual({
        preserved_nested_detail: "persisted nested detail",
        terminal_override: "terminal nested value",
      });

    resolveMessages([]);
    resolveTurnMemory(persistedTurnMemory);
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });
    wrapper.unmount();
  });

  it("cancels formal readiness retries when the workspace changes", async () => {
    vi.useFakeTimers();
    const runId = "run-temporal-workspace-cancel";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    const caseEventCallbacks = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "workspace-scoped stream");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const pendingStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        command_admission_state: "PENDING",
      }),
    );
    const initialReadyStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const nextWorkspaceStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
      { can_use_intake: false },
    );
    const intakeStatusLoader = vi.fn()
      .mockResolvedValueOnce(pendingStatus)
      .mockResolvedValueOnce(nextWorkspaceStatus);
    const messagesLoader = vi.fn().mockResolvedValue([{
      id: "MESSAGE_FORMAL_WORKSPACE_CANCEL",
      sequence_no: 5,
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      agent_run_id: runId,
      message_text: "旧工作区正式回复",
    }]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(
      formalTurnMemory("旧工作区正式卷宗", 1, 1),
    );
    const eventStreamer = vi.fn(async ({ applyEvent }) => {
      caseEventCallbacks.push(applyEvent);
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: initialReadyStatus,
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader,
      turnMemoryLoader,
      intakeStatusLoader,
      eventStreamer,
      formalReadinessPollAttempts: 5,
      formalReadinessPollDelayMs: 1_000,
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "switch workspace while finalizing",
      attachment_refs: [],
    });
    await flushPromises();
    await vi.waitFor(() => {
      expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
      expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
      expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    });
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(true);
    expect(wrapper.get("textarea").attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("正在同步正式卷宗");
    const staleCaseEvent = caseEventCallbacks[0];

    actor.id = "user-other";
    await wrapper.vm.$nextTick();
    await flushPromises();
    await staleCaseEvent(intakeProjectionReadyEvent(runId));
    await vi.advanceTimersByTimeAsync(1_000);
    await flushPromises();

    expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    expect(messagesLoader).toHaveBeenCalledTimes(1);
    expect(intakeStatusLoader).toHaveBeenCalledTimes(2);
    expect(agentStreamStore.runs[runId]).toBeUndefined();
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("旧工作区正式回复");
    expect(wrapper.text()).not.toContain("旧工作区正式卷宗");
    wrapper.unmount();
  });

  it("clears a provisional dossier when formal readiness reaches the retry limit", async () => {
    vi.useFakeTimers();
    const runId = "run-temporal-retry-limit";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "流式草稿保持到重试上限");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi.fn().mockResolvedValue([]);
    const turnMemoryLoader = vi.fn().mockResolvedValue(null);
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      intakeStatusLoader: vi.fn().mockResolvedValue(
        intakeStatusWithProjection(
          currentProcessProjection({ writer_mode: "TEMPORAL" }),
        ),
      ),
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
      formalReadinessPollAttempts: 3,
      formalReadinessPollDelayMs: 25,
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "reach bounded retry limit",
      attachment_refs: [],
    });
    await flushPromises();
    await vi.advanceTimersByTimeAsync(100);
    await flushPromises();

    expect(turnMemoryLoader).toHaveBeenCalledTimes(3);
    expect(messagesLoader).toHaveBeenCalledTimes(3);
    expect(wrapper.text()).not.toContain("流式草稿保持到重试上限");
    expect(wrapper.get("[data-intake-error-dialog]").text())
      .toContain("正式卷宗尚未同步完成");
    await vi.advanceTimersByTimeAsync(500);
    expect(turnMemoryLoader).toHaveBeenCalledTimes(3);
    wrapper.unmount();
  });

  it("clears provisional Intake output when that run fails", async () => {
    const runId = "run-temporal-provisional-failure";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return failedDossierStreamResponse(runId, "failed provisional dossier");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader: vi.fn().mockResolvedValue([]),
      turnMemoryLoader: vi.fn().mockResolvedValue(null),
      eventStreamer: vi.fn(async () => {}),
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "trigger target stream failure",
      attachment_refs: [],
    });

    await vi.waitFor(() => {
      expect(wrapper.find("[data-intake-error-dialog]").exists()).toBe(true);
    });

    expect(agentStreamStore.runs[runId]?.status).toBe("ERROR");
    expect(wrapper.text()).not.toContain("failed provisional dossier");
    wrapper.unmount();
  });

  it("keeps formal readiness bound to the latest target Intake run", async () => {
    const runA = "run-temporal-overlap-a";
    const runB = "run-temporal-overlap-b";
    const streamA = `/api/private-agent-streams/${runA}/events`;
    const streamB = `/api/private-agent-streams/${runB}/events`;
    let formalPhase = "A_PENDING";
    let resolveRunAMessages;
    let resolveRunAMemory;
    const runAMessages = new Promise((resolve) => {
      resolveRunAMessages = resolve;
    });
    const runAMemory = new Promise((resolve) => {
      resolveRunAMemory = resolve;
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.startsWith(streamA)) return dossierStreamResponse(runA, "run A draft");
      if (url.startsWith(streamB)) return dossierStreamResponse(runB, "run B draft");
      throw new Error(`unexpected fetch: ${url}`);
    });
    const messagesLoader = vi.fn(() => {
      if (formalPhase === "B") {
        return Promise.resolve([{
          id: "MESSAGE_FORMAL_B",
          sequence_no: 3,
          sender_type: "AGENT",
          sender_role: "CUSTOMER_SERVICE",
          agent_run_id: runB,
          message_text: "formal B reply",
        }]);
      }
      return runAMessages;
    });
    const turnMemoryLoader = vi.fn(() => {
      if (formalPhase === "B") {
        return Promise.resolve(formalTurnMemory("正式 B 卷宗", 2, 2));
      }
      return runAMemory;
    });
    const postMessageAction = vi.fn()
      .mockResolvedValueOnce({ run_id: runA, stream_url: streamA })
      .mockResolvedValueOnce({ run_id: runB, stream_url: streamB });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      intakeStatusLoader: vi.fn().mockResolvedValue(
        intakeStatusWithProjection(
          currentProcessProjection({ writer_mode: "TEMPORAL" }),
        ),
      ),
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
      formalReadinessPollAttempts: 3,
      formalReadinessPollDelayMs: 10,
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "start run A",
      attachment_refs: [],
    });
    await flushPromises();
    await vi.waitFor(() => {
      expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    });

    formalPhase = "B";
    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "start run B",
      attachment_refs: [],
    });
    await flushPromises();
    await vi.waitFor(() => {
      expect(turnMemoryLoader).toHaveBeenCalledTimes(2);
      expect(wrapper.text()).toContain("正式 B 卷宗");
    });

    resolveRunAMessages([{
      id: "MESSAGE_FORMAL_A",
      sequence_no: 2,
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      agent_run_id: runA,
      message_text: "formal A reply",
    }]);
    resolveRunAMemory(formalTurnMemory("正式 A 卷宗", 1, 1));
    await flushPromises();
    await vi.waitFor(() => {
      expect(agentStreamStore.runs[runB]?.status).toBe("COMPLETED");
    });

    expect(postMessageAction).toHaveBeenCalledTimes(2);
    expect(agentStreamStore.runs[runA]?.status).toBe("ABORTED");
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain("正式 B 卷宗");
      expect(wrapper.text()).not.toContain("正式 A 卷宗");
    });
    expect(wrapper.find("[data-intake-error-dialog]").exists()).toBe(false);
    expect(turnMemoryLoader).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("rejects a stale room-event snapshot after the latest target run becomes formal", async () => {
    const runId = "run-temporal-event-snapshot-race";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    let resolveStaleMessages;
    let resolveStaleMemory;
    let eventSnapshotLoader;
    const staleMessages = new Promise((resolve) => {
      resolveStaleMessages = resolve;
    });
    const staleMemory = new Promise((resolve) => {
      resolveStaleMemory = resolve;
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return dossierStreamResponse(runId, "目标 run 流式草稿");
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi.fn()
      .mockImplementationOnce(() => staleMessages)
      .mockResolvedValueOnce([{
        id: "MESSAGE_FORMAL_EVENT_RACE",
        sequence_no: 3,
        sender_type: "AGENT",
        sender_role: "CUSTOMER_SERVICE",
        agent_run_id: runId,
        message_text: "目标 run 正式回复",
      }]);
    const turnMemoryLoader = vi.fn()
      .mockImplementationOnce(() => staleMemory)
      .mockResolvedValueOnce(formalTurnMemory("最新正式卷宗", 2, 2));
    const eventStreamer = vi.fn(async ({ snapshotLoader }) => {
      eventSnapshotLoader = snapshotLoader;
    });
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: null,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
      intakeStatusLoader: vi.fn().mockResolvedValue(
        intakeStatusWithProjection(
          currentProcessProjection({ writer_mode: "TEMPORAL" }),
        ),
      ),
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader,
      turnMemoryLoader,
      eventStreamer,
      formalReadinessPollAttempts: 1,
      formalReadinessPollDelayMs: 0,
    });

    await vi.waitFor(() => expect(eventSnapshotLoader).toBeTypeOf("function"));
    const staleRefresh = eventSnapshotLoader();
    await vi.waitFor(() => {
      expect(messagesLoader).toHaveBeenCalledTimes(1);
      expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "启动最新 target run",
      attachment_refs: [],
    });
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain("最新正式卷宗");
      expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    });

    resolveStaleMessages([{
      id: "MESSAGE_STALE_EVENT_RACE",
      sequence_no: 1,
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      agent_run_id: "run-stale-event-snapshot",
      message_text: "旧事件回复",
    }]);
    resolveStaleMemory(formalTurnMemory("旧事件卷宗", 1, 1));
    await staleRefresh;
    await flushPromises();

    expect(wrapper.text()).toContain("最新正式卷宗");
    expect(wrapper.text()).not.toContain("旧事件卷宗");
    expect(wrapper.text()).not.toContain("旧事件回复");
    wrapper.unmount();
  });

  it("ignores a non-TEMPORAL run final after the workspace changes", async () => {
    const runId = "run-shadow-workspace-switch";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    let resolveOldMessages;
    let resolveOldMemory;
    const oldMessages = new Promise((resolve) => {
      resolveOldMessages = resolve;
    });
    const oldMemory = new Promise((resolve) => {
      resolveOldMemory = resolve;
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) return terminalStreamResponse(runId);
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const messagesLoader = vi.fn(() => oldMessages);
    const turnMemoryLoader = vi.fn(() => oldMemory);
    const wrapper = await mountInteractiveView({
      initialMessages: [],
      initialTurnMemory: readyTurnMemory,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "SHADOW" }),
      ),
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "旧工作区请求",
      attachment_refs: [],
    });
    await vi.waitFor(() => {
      expect(messagesLoader).toHaveBeenCalledTimes(1);
      expect(turnMemoryLoader).toHaveBeenCalledTimes(1);
    });

    actor.id = "user-new-workspace";
    await wrapper.vm.$nextTick();
    await flushPromises();
    expect(wrapper.findComponent(DigitalHuman).props("state")).toBe("LISTENING");

    resolveOldMessages([{
      id: "MESSAGE_OLD_WORKSPACE",
      sender_type: "AGENT",
      sender_role: "CUSTOMER_SERVICE",
      message_text: "旧工作区正式回复",
    }]);
    resolveOldMemory(formalTurnMemory("旧工作区正式卷宗", 2, 2));
    await flushPromises();
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    await flushPromises();

    expect(wrapper.findComponent(DigitalHuman).props("state")).toBe("LISTENING");
    expect(wrapper.text()).not.toContain("旧工作区正式回复");
    expect(wrapper.text()).not.toContain("旧工作区正式卷宗");
    expect(wrapper.find("[data-intake-error-dialog]").exists()).toBe(false);
    wrapper.unmount();
  });

  it("does not create an opening for LEGACY or SHADOW initiators", async () => {
    const openingAction = vi.fn();
    for (const initialIntakeStatus of [
      intakeStatusWithProjection(undefined),
      intakeStatusWithProjection(currentProcessProjection()),
    ]) {
      const wrapper = await mountInteractiveView({
        initialMessages: null,
        initialIntakeStatus,
        messagesLoader: vi.fn().mockResolvedValue([]),
        openingAction,
        eventStreamer: vi.fn(async () => {}),
      });
      wrapper.unmount();
    }

    expect(openingAction).not.toHaveBeenCalled();
  });

  it("renders completed intake as history and blocks every write path", async () => {
    const postMessageAction = vi.fn();
    const confirmAction = vi.fn();
    const cancelAction = vi.fn();
    const eventStreamer = vi.fn();
    const wrapper = await mountInteractiveView({
      historyMode: true,
      postMessageAction,
      confirmAction,
      cancelAction,
      eventStreamer,
    });

    expect(wrapper.get("[data-room-history-banner]").text()).toContain("历史浏览模式");
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.get("[data-intake-history-actions]").text()).toContain("历史接待已锁定");
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(postMessageAction).not.toHaveBeenCalled();
    expect(confirmAction).not.toHaveBeenCalled();
    expect(cancelAction).not.toHaveBeenCalled();
    expect(eventStreamer).not.toHaveBeenCalled();
  });

  it("preserves legacy intake actions when the process projection is absent or unavailable", async () => {
    const compatibleProjections = [
      undefined,
      {
        schema_version: "intake-process-projection.v1",
        projection_state: "UNAVAILABLE",
        writer_mode: "LEGACY",
      },
      {
        schema_version: "intake-process-projection.v1",
        projection_state: "CURRENT",
        writer_mode: "LEGACY",
      },
    ];

    for (const processProjection of compatibleProjections) {
      const confirmAction = vi.fn().mockResolvedValue(null);
      const wrapper = await mountInteractiveView({
        initialIntakeStatus: intakeStatusWithProjection(processProjection),
        confirmAction,
      });

      expect(wrapper.find(".conversation-stream__composer").exists()).toBe(true);
      expect(wrapper.get("[data-confirm-admission]").attributes("disabled"))
        .toBeUndefined();
      expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(true);
      await wrapper.get("[data-confirm-admission]").trigger("click");
      await flushPromises();
      expect(confirmAction).toHaveBeenCalledTimes(1);
      wrapper.unmount();
    }
  });

  it("fails closed for reloaded TEMPORAL command admission until READY", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const reloadedMessages = [{
      id: "MESSAGE_RELOADED_INTAKE",
      sequence_no: 6,
      sender_role: "CUSTOMER_SERVICE",
      message_text: "已恢复接待记录。",
    }];
    const reloadedStatus = (processProjection) => intakeStatusWithProjection(
      processProjection,
      {
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        can_use_intake: true,
        can_enter_evidence: false,
      },
    );
    const mountReloadedRoom = (processProjection, postMessageAction = vi.fn()) =>
      mountInteractiveView({
        initialMessages: null,
        initialTurnMemory: readyTurnMemory,
        initialIntakeStatus: reloadedStatus(processProjection),
        messagesLoader: vi.fn().mockResolvedValue(reloadedMessages),
        turnMemoryLoader: vi.fn().mockResolvedValue(readyTurnMemory),
        postMessageAction,
        eventStreamer: vi.fn(async () => {}),
      });
    const pendingProjection = currentProcessProjection({
      writer_mode: "TEMPORAL",
      command_admission_state: "PENDING",
    });
    const missingAdmissionProjection = currentProcessProjection({
      writer_mode: "TEMPORAL",
    });
    delete missingAdmissionProjection.command_admission_state;

    for (const processProjection of [pendingProjection, missingAdmissionProjection]) {
      const postMessageAction = vi.fn();
      const wrapper = await mountReloadedRoom(processProjection, postMessageAction);

      expect(Object.values(agentStreamStore.runs)).toHaveLength(0);
      expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
      wrapper.findComponent(ConversationStream).vm.$emit("submit", {
        message_type: "PARTY_TEXT",
        text: "must remain blocked after reload",
        attachment_refs: [],
      });
      await flushPromises();
      expect(postMessageAction).not.toHaveBeenCalled();
      wrapper.unmount();
    }

    const readyPostMessageAction = vi.fn().mockResolvedValue(null);
    const readyWrapper = await mountReloadedRoom(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
      readyPostMessageAction,
    );
    expect(readyWrapper.find(".conversation-stream__composer").exists()).toBe(true);
    expect(readyWrapper.get("textarea").attributes("disabled")).toBeUndefined();
    readyWrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "READY permits a reloaded response",
      attachment_refs: [],
    });
    await vi.waitFor(() => {
      expect(readyPostMessageAction).toHaveBeenCalledTimes(1);
    });
    readyWrapper.unmount();

    const shadowWithoutAdmission = currentProcessProjection();
    delete shadowWithoutAdmission.command_admission_state;
    const shadowWrapper = await mountReloadedRoom(shadowWithoutAdmission);
    expect(shadowWrapper.find(".conversation-stream__composer").exists()).toBe(true);
    shadowWrapper.unmount();
  });

  it("fails closed for reloaded TEMPORAL confirmation and cancellation until READY", async () => {
    const reloadedMessages = [{
      id: "MESSAGE_RELOADED_READY_TO_CONFIRM",
      sequence_no: 7,
      sender_role: "CUSTOMER_SERVICE",
      message_text: "Formal intake record restored.",
    }];
    const reloadedStatus = (processProjection) => intakeStatusWithProjection(
      processProjection,
      {
        initiator_status: "OPEN",
        respondent_status: "LOCKED",
        can_use_intake: true,
        can_enter_evidence: false,
      },
    );
    const mountReloadedRoom = (processProjection, actions = {}) =>
      mountInteractiveView({
        initialMessages: null,
        initialTurnMemory: readyTurnMemory,
        initialIntakeStatus: reloadedStatus(processProjection),
        messagesLoader: vi.fn().mockResolvedValue(reloadedMessages),
        turnMemoryLoader: vi.fn().mockResolvedValue(readyTurnMemory),
        confirmAction: actions.confirmAction,
        cancelAction: actions.cancelAction,
        eventStreamer: vi.fn(async () => {}),
      });
    const pendingProjection = currentProcessProjection({
      writer_mode: "TEMPORAL",
      room_phase: "READY_TO_CONFIRM",
      command_admission_state: "PENDING",
    });
    const missingAdmissionProjection = currentProcessProjection({
      writer_mode: "TEMPORAL",
      room_phase: "READY_TO_CONFIRM",
    });
    delete missingAdmissionProjection.command_admission_state;

    for (const processProjection of [pendingProjection, missingAdmissionProjection]) {
      const confirmAction = vi.fn();
      const cancelAction = vi.fn();
      const wrapper = await mountReloadedRoom(processProjection, {
        confirmAction,
        cancelAction,
      });

      expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
      expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
      expect(wrapper.vm.$.setupState.intakeDossierSubmissionDisabled).toBe(true);
      expect(wrapper.vm.$.setupState.intakeCancellationDisabled).toBe(true);
      await wrapper.vm.$.setupState.confirmAdmission();
      await wrapper.vm.$.setupState.resolveWithoutDispute();
      await flushPromises();
      expect(confirmAction).not.toHaveBeenCalled();
      expect(cancelAction).not.toHaveBeenCalled();
      wrapper.unmount();
    }

    const confirmAction = vi.fn().mockResolvedValue(null);
    const cancelAction = vi.fn().mockResolvedValue(null);
    const readyWrapper = await mountReloadedRoom(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        room_phase: "READY_TO_CONFIRM",
      }),
      { confirmAction, cancelAction },
    );

    expect(readyWrapper.get("[data-confirm-admission]").attributes("disabled"))
      .toBeUndefined();
    expect(readyWrapper.get("[data-resolve-without-dispute]").attributes("disabled"))
      .toBeUndefined();
    await readyWrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();
    expect(confirmAction).toHaveBeenCalledTimes(1);
    await readyWrapper.get("[data-resolve-without-dispute]").trigger("click");
    await flushPromises();
    expect(cancelAction).toHaveBeenCalledTimes(1);
    readyWrapper.unmount();
  });

  it("fails closed while a projection is processing and blocks opening, writes, navigation, and run discovery", async () => {
    const processingStatus = intakeStatusWithProjection({
      schema_version: "intake-process-projection.v1",
      projection_state: "PROCESSING",
      writer_mode: "SHADOW",
      room_phase: "PROCESSING",
    });
    const fetchMock = installIntakeApiFetch({
      status: processingStatus,
      activeRuns: [{
        run_id: "run-must-not-be-discovered",
        stream_url: "/api/private-agent-streams/run-must-not-be-discovered/events",
      }],
    });
    const postMessageAction = vi.fn();
    const confirmAction = vi.fn();
    const cancelAction = vi.fn();
    const openingAction = vi.fn();
    const abortStaleStream = vi.fn();
    agentStreamStore.runs["run-stale-fence"] = {
      runId: "run-stale-fence",
      caseId: "CASE_INTAKE_1",
      roomType: "INTAKE",
      actorId: "user-local",
      actorRole: "USER",
      status: "STREAMING",
      startedAt: 0,
      abortController: { abort: abortStaleStream },
      displayPacer: { cancel: vi.fn() },
    };
    const eventStreamer = vi.fn(async ({ applyEvent }) => {
      await applyEvent({ event: "EVIDENCE_OPENED" });
    });
    const wrapper = await mountInteractiveView({
      initialDispute: null,
      initialIntakeStatus: processingStatus,
      initialMessages: null,
      initialTurnMemory: readyTurnMemory,
      postMessageAction,
      confirmAction,
      cancelAction,
      openingAction,
      eventStreamer,
    });

    wrapper.findComponent(ConversationStream).vm.$emit("submit", {
      message_type: "PARTY_TEXT",
      text: "must not be posted",
      attachment_refs: [],
    });
    await flushPromises();

    const requestedUrls = fetchMock.mock.calls.map(([input]) => String(input));
    expect(requestedUrls).not.toContain(
      "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active",
    );
    expect(abortStaleStream).toHaveBeenCalledTimes(1);
    expect(agentStreamStore.runs["run-stale-fence"]).toBeUndefined();
    expect(openingAction).not.toHaveBeenCalled();
    expect(postMessageAction).not.toHaveBeenCalled();
    expect(confirmAction).not.toHaveBeenCalled();
    expect(cancelAction).not.toHaveBeenCalled();
    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(false);
    expect(wrapper.vm.$router.currentRoute.value.path)
      .toBe("/disputes/CASE_INTAKE_1/intake");
    wrapper.unmount();
  });

  it("fails closed for unknown or malformed present projections", async () => {
    const invalidProjections = [
      null,
      {
        schema_version: "intake-process-projection.v2",
        projection_state: "UNAVAILABLE",
        writer_mode: "LEGACY",
      },
      {
        schema_version: "intake-process-projection.v1",
        projection_state: "FUTURE_STATE",
        writer_mode: "LEGACY",
      },
      {
        schema_version: "intake-process-projection.v1",
        projection_state: "UNAVAILABLE",
        writer_mode: "SHADOW",
      },
      {
        schema_version: "intake-process-projection.v1",
        projection_state: "UNAVAILABLE",
        writer_mode: "LEGACY",
        writerMode: "SHADOW",
      },
      currentProcessProjection({ room_phase: "WAITING_TIMER" }),
      currentProcessProjection({ fencing_token: 0 }),
      currentProcessProjection({ pending_state: "WAITING_PARTY" }),
      {
        ...currentProcessProjection(),
        roomPhase: "COMPLETED",
      },
      currentProcessProjection({
        room_phase: "AGENT_RUNNING",
        active_logical_run_id: "run-1",
        active_attempt_id: "attempt-2",
        active_run_status: "RUNNING",
        stream_cursor: "v2:another-attempt:6",
      }),
    ];
    const outerConflictStatus = intakeStatusWithProjection(
      currentProcessProjection(),
    );
    outerConflictStatus.processProjection = currentCamelProcessProjection({
      roomPhase: "COMPLETED",
    });
    const invalidStatuses = [
      ...invalidProjections.map((projection) =>
        intakeStatusWithProjection(projection),
      ),
      outerConflictStatus,
    ];

    for (const status of invalidStatuses) {
      const postMessageAction = vi.fn();
      const wrapper = await mountInteractiveView({
        initialIntakeStatus: status,
        intakeStatusLoader: vi.fn().mockResolvedValue(status),
        postMessageAction,
      });

      wrapper.findComponent(ConversationStream).vm.$emit("submit", {
        message_type: "PARTY_TEXT",
        text: "must remain blocked",
        attachment_refs: [],
      });
      await flushPromises();
      expect(postMessageAction).not.toHaveBeenCalled();
      expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
      expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
      expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
      wrapper.unmount();
    }
  });

  it("intersects current projection phases with server intake permissions", async () => {
    const openWrapper = await mountInteractiveView({
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ room_phase: "OPEN" }),
      ),
    });
    expect(openWrapper.find(".conversation-stream__composer").exists()).toBe(true);
    expect(openWrapper.get("[data-confirm-admission]").attributes("disabled"))
      .toBe("");
    expect(openWrapper.get("[data-resolve-without-dispute]").attributes("disabled"))
      .toBeUndefined();
    openWrapper.unmount();

    const serverLockedWrapper = await mountInteractiveView({
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ room_phase: "WAITING_PARTY" }),
        { can_use_intake: false },
      ),
    });
    expect(serverLockedWrapper.find(".conversation-stream__composer").exists())
      .toBe(false);
    expect(serverLockedWrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(serverLockedWrapper.find("[data-resolve-without-dispute]").exists())
      .toBe(false);
    serverLockedWrapper.unmount();

    const agentRunningWrapper = await mountInteractiveView({
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ room_phase: "AGENT_RUNNING" }),
      ),
    });
    expect(agentRunningWrapper.find(".conversation-stream__composer").exists())
      .toBe(false);
    expect(agentRunningWrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(agentRunningWrapper.find("[data-resolve-without-dispute]").exists())
      .toBe(false);
    agentRunningWrapper.unmount();

    const openingAction = vi.fn();
    const readyWrapper = await mountInteractiveView({
      initialMessages: null,
      messagesLoader: vi.fn().mockResolvedValue([]),
      openingAction,
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ room_phase: "READY_TO_CONFIRM" }),
      ),
      eventStreamer: vi.fn(async () => {}),
    });
    expect(readyWrapper.find(".conversation-stream__composer").exists()).toBe(true);
    expect(readyWrapper.get("[data-confirm-admission]").attributes("disabled"))
      .toBeUndefined();
    expect(readyWrapper.get("[data-resolve-without-dispute]").attributes("disabled"))
      .toBeUndefined();
    expect(openingAction).not.toHaveBeenCalled();
    readyWrapper.unmount();

    const completedWrapper = await mountInteractiveView({
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ room_phase: "COMPLETED" }),
        { current_actor_completed: true, can_enter_evidence: true },
      ),
    });
    expect(completedWrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(completedWrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(completedWrapper.find("[data-resolve-without-dispute]").exists())
      .toBe(false);
    expect(completedWrapper.find("[data-enter-evidence-room]").exists()).toBe(true);
    completedWrapper.unmount();

    const closedWrapper = await mountInteractiveView({
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({ room_phase: "CLOSED" }),
        { current_actor_completed: true, can_enter_evidence: true },
      ),
    });
    expect(closedWrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(closedWrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(closedWrapper.find("[data-resolve-without-dispute]").exists())
      .toBe(false);
    expect(closedWrapper.find("[data-enter-evidence-room]").exists()).toBe(false);
    closedWrapper.unmount();
  });

  it("does not treat stale top-level evidence permission as ready during projection processing", async () => {
    const confirmAction = vi.fn().mockResolvedValue({
      case_status: "EVIDENCE_OPEN",
      current_room: "EVIDENCE",
    });
    const processingStatus = intakeStatusWithProjection({
      schema_version: "intake-process-projection.v1",
      projection_state: "PROCESSING",
      writer_mode: "SHADOW",
      room_phase: "PROCESSING",
    }, {
      can_enter_evidence: true,
    });
    const wrapper = await mountInteractiveView({
      initialIntakeStatus: intakeStatusWithProjection(undefined, {
        can_enter_evidence: false,
      }),
      confirmAction,
      intakeStatusLoader: vi.fn().mockResolvedValue(processingStatus),
      evidenceReadyPollAttempts: 1,
      evidenceReadyPollDelayMs: 0,
    });

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(confirmAction).toHaveBeenCalledTimes(1);
    expect(wrapper.vm.$router.currentRoute.value.path)
      .toBe("/disputes/CASE_INTAKE_1/intake");
    wrapper.unmount();
  });

  it("recovers only the authorized descriptor matching the projected logical run", async () => {
    const processProjection = currentCamelProcessProjection({
      roomPhase: "AGENT_RUNNING",
      activeLogicalRunId: "run-target",
      activeAttemptId: "attempt-target",
      activeRunStatus: "RUNNING",
      streamCursor: "v2:attempt-target:6",
    });
    const status = intakeStatusWithProjection(undefined);
    delete status.process_projection;
    status.processProjection = processProjection;
    const activeRuns = [
      {
        run_id: "run-other",
        stream_url: "/api/private-agent-streams/run-other/events",
        status: "RUNNING",
      },
      {
        runId: "run-target",
        streamUrl: "/api/private-agent-streams/run-target/events",
        status: "RUNNING",
      },
    ];
    const fetchMock = installIntakeApiFetch({ status, activeRuns });
    const wrapper = await mountInteractiveView({
      initialDispute: null,
      initialMessages: null,
      initialTurnMemory: readyTurnMemory,
      eventStreamer: vi.fn(async () => {}),
    });

    const requestedUrls = fetchMock.mock.calls.map(([input]) => String(input));
    expect(requestedUrls).toContain(
      "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active",
    );
    expect(requestedUrls).toContain(
      "/api/private-agent-streams/run-target/events?last_event_id=-1",
    );
    expect(requestedUrls.some((url) => url.includes("run-other/events"))).toBe(false);
    expect(requestedUrls.some((url) =>
      url.includes("/api/agent-runs/run-target/events"),
    )).toBe(false);
    expect(requestedUrls.some((url) => url.includes("attempt-target:6"))).toBe(false);
    expect(agentStreamStore.runs["run-other"]).toBeUndefined();
    expect(agentStreamStore.runs["run-target"]).toBeDefined();
    wrapper.unmount();
  });

  it("keeps a recovered Intake dossier behind the reply pacer", async () => {
    vi.useFakeTimers();
    const runId = "run-recovered-reply-then-board";
    const reply = "恢复中的接待回复。".repeat(500);
    const summary = "恢复流在回复完成后才展示的案情摘要";
    const status = intakeStatusWithProjection(currentProcessProjection({
      room_phase: "AGENT_RUNNING",
      active_logical_run_id: runId,
      active_attempt_id: null,
      active_run_status: "PENDING",
      stream_cursor: "-1",
    }));
    let messagesCalls = 0;
    let memoryCalls = 0;
    let resolveFinalMessages;
    let resolveFinalMemory;
    const finalMessages = new Promise((resolve) => {
      resolveFinalMessages = resolve;
    });
    const finalMemory = new Promise((resolve) => {
      resolveFinalMemory = resolve;
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/disputes/CASE_INTAKE_1") return apiResponse(dispute);
      if (url === "/api/disputes/CASE_INTAKE_1/intake/status") {
        return apiResponse(status);
      }
      if (url === "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/messages") {
        messagesCalls += 1;
        return messagesCalls === 1 ? apiResponse([]) : finalMessages;
      }
      if (url === "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/turn-memory/latest") {
        memoryCalls += 1;
        return memoryCalls === 1 ? apiResponse(null) : finalMemory;
      }
      if (url === "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active") {
        return apiResponse([{
          run_id: runId,
          stream_url: `/api/private-agent-streams/${runId}/events`,
          status: "RUNNING",
        }]);
      }
      if (url.startsWith(`/api/private-agent-streams/${runId}/events`)) {
        return replyThenDossierStreamResponse(runId, reply, summary);
      }
      throw new Error(`unexpected fetch: ${url}`);
    });

    const wrapper = await mountInteractiveView({
      initialDispute: null,
      initialMessages: null,
      initialTurnMemory: null,
      eventStreamer: vi.fn(async () => {}),
    });
    await flushPromises();

    expect(agentStreamStore.runs[runId]?.lastEventId).toBe("1");
    expect(wrapper.get("[data-dispute-detail-summary]").text())
      .not.toContain(summary);

    await vi.advanceTimersByTimeAsync(1_500);
    await flushPromises();

    expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    expect(wrapper.get("[data-dispute-detail-summary]").text()).toContain(summary);

    resolveFinalMessages(apiResponse([]));
    resolveFinalMemory(apiResponse(null));
    await flushPromises();
    await vi.advanceTimersByTimeAsync(100);
    await flushPromises();
    expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    wrapper.unmount();
  });

  it("rejects projected recovery when the authorized descriptor omits its stream URL", async () => {
    const status = intakeStatusWithProjection(currentProcessProjection({
      room_phase: "AGENT_RUNNING",
      active_logical_run_id: "run-without-url",
      active_attempt_id: null,
      active_run_status: "PENDING",
      stream_cursor: "-1",
    }));
    const fetchMock = installIntakeApiFetch({
      status,
      activeRuns: [{ runId: "run-without-url", status: "PENDING" }],
    });
    const wrapper = await mountInteractiveView({
      initialDispute: null,
      initialMessages: null,
      initialTurnMemory: readyTurnMemory,
      eventStreamer: vi.fn(async () => {}),
    });

    const requestedUrls = fetchMock.mock.calls.map(([input]) => String(input));
    expect(requestedUrls).toContain(
      "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active",
    );
    expect(requestedUrls.some((url) =>
      url.includes("/api/agent-runs/run-without-url/events"),
    )).toBe(false);
    expect(agentStreamStore.runs["run-without-url"]).toBeUndefined();
    wrapper.unmount();
  });

  it("never queries active runs in history, observer, locked respondent, or current-without-run views", async () => {
    const activeProjection = currentProcessProjection({
      room_phase: "AGENT_RUNNING",
      active_logical_run_id: "run-private",
      active_attempt_id: null,
      active_run_status: "PENDING",
      stream_cursor: "-1",
    });
    const scenarios = [
      {
        actor: { id: "user-local", role: "USER" },
        historyMode: true,
        disputeValue: { ...dispute, party_position: "INITIATOR" },
        status: intakeStatusWithProjection(activeProjection),
      },
      {
        actor: { id: "reviewer-local", role: "PLATFORM_REVIEWER" },
        disputeValue: { ...dispute, party_position: "OBSERVER" },
        status: intakeStatusWithProjection(activeProjection),
      },
      {
        actor: { id: "merchant-local", role: "MERCHANT" },
        disputeValue: { ...dispute, party_position: "RESPONDENT" },
        status: intakeStatusWithProjection(activeProjection, {
          initiator_status: "OPEN",
          respondent_status: "LOCKED",
          can_use_intake: false,
        }),
      },
      {
        actor: { id: "user-local", role: "USER" },
        disputeValue: { ...dispute, party_position: "INITIATOR" },
        status: intakeStatusWithProjection(currentProcessProjection()),
      },
    ];

    for (const scenario of scenarios) {
      actor.id = scenario.actor.id;
      actor.role = scenario.actor.role;
      const fetchMock = installIntakeApiFetch({
        status: scenario.status,
        disputeValue: scenario.disputeValue,
        activeRuns: [{
          run_id: "run-private",
          stream_url: "/api/private-agent-streams/run-private/events",
        }],
      });
      const wrapper = await mountInteractiveView({
        initialDispute: null,
        initialMessages: null,
        initialTurnMemory: readyTurnMemory,
        historyMode: scenario.historyMode,
        eventStreamer: vi.fn(async () => {}),
      });

      expect(fetchMock.mock.calls.map(([input]) => String(input))).not.toContain(
        "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active",
      );
      wrapper.unmount();
      fetchMock.mockRestore();
    }
  });

  it("continues projection retries after the fast window and ignores a stale refresh after the actor changes", async () => {
    vi.useFakeTimers();
    const processingStatus = intakeStatusWithProjection({
      schema_version: "intake-process-projection.v1",
      projection_state: "PROCESSING",
      writer_mode: "SHADOW",
      room_phase: "PROCESSING",
    });
    const retryLoader = vi.fn().mockResolvedValue(processingStatus);
    const retryWrapper = await mountInteractiveView({
      initialIntakeStatus: processingStatus,
      intakeStatusLoader: retryLoader,
    });

    await vi.advanceTimersByTimeAsync(11_000);
    expect(retryLoader).toHaveBeenCalledTimes(41);
    retryWrapper.unmount();

    let resolveStaleStatus;
    const lockedCurrentStatus = intakeStatusWithProjection(
      currentProcessProjection(),
      { can_use_intake: false },
    );
    const staleStatusLoader = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveStaleStatus = resolve;
      }))
      .mockResolvedValueOnce(lockedCurrentStatus);
    const wrapper = await mountInteractiveView({
      initialIntakeStatus: processingStatus,
      intakeStatusLoader: staleStatusLoader,
    });

    await vi.advanceTimersByTimeAsync(250);
    expect(staleStatusLoader).toHaveBeenCalledTimes(1);
    actor.id = "user-other";
    await wrapper.vm.$nextTick();
    await flushPromises();
    expect(staleStatusLoader).toHaveBeenCalledTimes(2);

    resolveStaleStatus(intakeStatusWithProjection(currentProcessProjection(), {
      can_use_intake: true,
    }));
    await flushPromises();

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(staleStatusLoader.mock.calls[0][0].actor.id).toBe("user-local");
    expect(staleStatusLoader.mock.calls[1][0].actor.id).toBe("user-other");
    wrapper.unmount();
  });

  it("requests the TEMPORAL initiator opening when readiness arrives after the fast retry window", async () => {
    vi.useFakeTimers();
    const processingStatus = intakeStatusWithProjection({
      schema_version: "intake-process-projection.v1",
      projection_state: "PROCESSING",
      writer_mode: "TEMPORAL",
      room_phase: "PROCESSING",
    });
    const currentStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const intakeStatusLoader = vi.fn();
    intakeStatusLoader.mockImplementation(() => (
      intakeStatusLoader.mock.calls.length > 40 ? currentStatus : processingStatus
    ));
    const openingAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_INITIATOR_OPENING",
      sequence_no: 1,
    });
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_INITIATOR_OPENING",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "请说明争议经过和处理诉求。",
        },
      ]);

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialIntakeStatus: processingStatus,
      intakeStatusLoader,
      messagesLoader,
      openingAction,
      eventStreamer: vi.fn(async () => {}),
    });

    await vi.advanceTimersByTimeAsync(10_000);
    await flushPromises();
    expect(intakeStatusLoader).toHaveBeenCalledTimes(40);
    expect(openingAction).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1_000);
    await flushPromises();
    expect(intakeStatusLoader).toHaveBeenCalledTimes(41);
    expect(openingAction).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("reloads messages and requests the TEMPORAL initiator opening after processing resolves", async () => {
    vi.useFakeTimers();
    const processingStatus = intakeStatusWithProjection({
      schema_version: "intake-process-projection.v1",
      projection_state: "PROCESSING",
      writer_mode: "TEMPORAL",
      room_phase: "PROCESSING",
    });
    const openingAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_INITIATOR_OPENING",
      sequence_no: 1,
    });
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_INITIATOR_OPENING",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "请说明争议经过和处理诉求。",
        },
      ]);
    const intakeStatusLoader = vi.fn().mockResolvedValue(
      intakeStatusWithProjection(
        currentProcessProjection({ writer_mode: "TEMPORAL" }),
      ),
    );

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialIntakeStatus: processingStatus,
      intakeStatusLoader,
      messagesLoader,
      openingAction,
      eventStreamer: vi.fn(async () => {}),
    });

    expect(openingAction).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(250);
    await flushPromises();

    expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
    expect(messagesLoader).toHaveBeenCalledTimes(3);
    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(openingAction).toHaveBeenCalledWith(
      { id: "user-local", role: "USER" },
      "CASE_INTAKE_1",
      "INTAKE",
    );
    wrapper.unmount();
  });

  it("resumes the active TEMPORAL run once the opening status publishes its logical run", async () => {
    vi.useFakeTimers();
    const noRunStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const activeRunStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        room_phase: "AGENT_RUNNING",
        active_logical_run_id: "run-temporal-opening",
        active_attempt_id: null,
        active_run_status: "PENDING",
        stream_cursor: "-1",
      }),
    );
    const fetchMock = installIntakeApiFetch({
      status: activeRunStatus,
      activeRuns: [{
        run_id: "run-temporal-opening",
        stream_url: "/api/private-agent-streams/run-temporal-opening/events",
      }],
    });
    const openingAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_INITIATOR_OPENING",
      sequence_no: 1,
    });
    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialIntakeStatus: noRunStatus,
      intakeStatusLoader: vi.fn().mockResolvedValue(activeRunStatus),
      messagesLoader: vi
        .fn()
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce([
          {
            id: "MESSAGE_INITIATOR_OPENING",
            sequence_no: 1,
            sender_role: "CUSTOMER_SERVICE",
            message_text: "请说明争议经过和处理诉求。",
          },
        ]),
      openingAction,
      eventStreamer: vi.fn(async () => {}),
    });

    await vi.advanceTimersByTimeAsync(250);
    await flushPromises();

    expect(openingAction).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toContain(
      "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active",
    );
    wrapper.unmount();
    fetchMock.mockRestore();
  });

  it("resumes a TEMPORAL run after reload finds a persisted opening before its run projection", async () => {
    vi.useFakeTimers();
    const noRunStatus = intakeStatusWithProjection(
      currentProcessProjection({ writer_mode: "TEMPORAL" }),
    );
    const activeRunStatus = intakeStatusWithProjection(
      currentProcessProjection({
        writer_mode: "TEMPORAL",
        room_phase: "AGENT_RUNNING",
        active_logical_run_id: "run-reloaded-opening",
        active_attempt_id: null,
        active_run_status: "PENDING",
        stream_cursor: "-1",
      }),
    );
    const fetchMock = installIntakeApiFetch({
      status: activeRunStatus,
      activeRuns: [{
        run_id: "run-reloaded-opening",
        stream_url: "/api/private-agent-streams/run-reloaded-opening/events",
      }],
    });
    const openingAction = vi.fn();
    const intakeStatusLoader = vi.fn().mockResolvedValue(activeRunStatus);
    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialIntakeStatus: noRunStatus,
      intakeStatusLoader,
      messagesLoader: vi.fn().mockResolvedValue([
        {
          id: "MESSAGE_PERSISTED_INITIATOR_OPENING",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "请说明争议经过和处理诉求。",
        },
      ]),
      openingAction,
      eventStreamer: vi.fn(async () => {}),
    });

    expect(openingAction).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(250);
    await flushPromises();

    expect(intakeStatusLoader).toHaveBeenCalledTimes(1);
    expect(openingAction).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toContain(
      "/api/disputes/CASE_INTAKE_1/rooms/INTAKE/agent-runs/active",
    );
    wrapper.unmount();
    fetchMock.mockRestore();
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("turns intake analysis into correctable dossier stickers", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("争议接待官");
    expect(wrapper.get("[data-dispute-detail-card]").text()).toContain("争议详情");
    expect(wrapper.text()).toContain("没有收到");
    expect(wrapper.get("[data-dispute-detail-respondent]").text()).toContain("对方（商家）回应");
    expect(wrapper.get("[data-dispute-detail-respondent]").text()).toContain("暂无回应");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("下一步核验重点");
    expect(wrapper.text()).toContain("高风险");
    expect(wrapper.text()).not.toContain("签收人与收件人不一致");
    expect(wrapper.text()).not.toContain("最终确认说明");
    expect(wrapper.text()).not.toContain("AI 受理建议非最终");
    expect(wrapper.find(".intake-dossier__confirm textarea").exists()).toBe(false);
    expect(wrapper.find(".intake-dossier__actions").exists()).toBe(true);
    expect(wrapper.find("[data-origin-statement-card]").exists()).toBe(true);
    expect(wrapper.find("[data-single-party-statement]").exists()).toBe(true);
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("opens the evidence room only after the server confirms admission", async () => {
    const confirmAction = vi.fn().mockResolvedValue({
      admissible: true,
      current_room: "EVIDENCE",
    });
    const wrapper = await mountInteractiveView({
      confirmAction,
      initialIntakeStatus: {
        initiator_status: "OPEN",
        respondent_status: "LOCKED",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
      intakeStatusLoader: vi.fn().mockResolvedValue({
        initiator_status: "COMPLETED",
        respondent_status: "COMPLETED",
        current_actor_completed: true,
        can_use_intake: false,
        can_enter_evidence: true,
      }),
    });
    const router = wrapper.vm.$router;

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(confirmAction).toHaveBeenCalledWith(
      expect.objectContaining({
        admissible: true,
        dispute_type: "SIGNED_NOT_RECEIVED",
      }),
    );
    expect(confirmAction.mock.calls[0][0]).not.toHaveProperty("confirmation_note");
    expect(wrapper.text()).toContain("已上报");
    expect(router.currentRoute.value.path).toBe(
      "/disputes/CASE_INTAKE_1/evidence",
    );
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("subscribes to the resumable case stream and aborts it on room exit", async () => {
    let signal;
    const eventStreamer = vi.fn(async (options) => {
      signal = options.signal;
      options.state.connected = true;
    });
    const { wrapper } = await mountView(vi.fn(), eventStreamer);
    await flushPromises();

    expect(eventStreamer).toHaveBeenCalledWith(
      expect.objectContaining({
        caseId: "CASE_INTAKE_1",
        roomType: "INTAKE",
      }),
    );
    expect(wrapper.find('[data-connection="connected"]').exists()).toBe(true);
    wrapper.unmount();
    expect(signal.aborted).toBe(true);
  });

  it("keeps the room header connected while an intake AgentRun is actively streaming", async () => {
    agentStreamStore.runs.AGENT_RUN_INTAKE_LIVE = {
      runId: "AGENT_RUN_INTAKE_LIVE",
      caseId: "CASE_INTAKE_1",
      roomType: "INTAKE",
      actorId: "user-local",
      actorRole: "USER",
      status: "STREAMING",
      content: "正在生成首轮追问",
      startedAt: Date.now(),
    };
    const disconnectedModelHealth = vi.fn().mockResolvedValue({
      status: "DOWN",
      model_status: "DISCONNECTED",
    });

    const wrapper = await mountInteractiveView({
      modelHealthLoader: disconnectedModelHealth,
    });

    expect(wrapper.get("[data-intake-work-status]").text()).toContain(
      "LIVE GENERATION",
    );
    expect(
      wrapper.get('[data-connection="connected"]').attributes("data-connection"),
    ).toBe("connected");
    expect(wrapper.text()).not.toContain("暂时离线");
    expect(wrapper.get('[data-model-state="connected"]').text()).toContain(
      "正在输出",
    );
  });

  it("accepts target Graph readiness as the browser connection signal", async () => {
    const wrapper = await mountInteractiveView({
      modelHealthLoader: vi.fn().mockResolvedValue({ ready: true }),
    });

    expect(wrapper.get('[data-model-state="connected"]').attributes("data-model-state"))
      .toBe("connected");
    wrapper.unmount();
  });

  it("allows the first durable party message before a legacy intake dossier exists", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: null,
      modelHealthLoader: vi.fn().mockResolvedValue({ ready: true }),
      eventStreamer: vi.fn(async () => {}),
    });

    expect(wrapper.get('[data-send-message] textarea').element.disabled).toBe(false);
    wrapper.unmount();
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("refreshes room messages and the live dossier after every intake dialogue turn", async () => {
    const postMessageAction = vi.fn().mockResolvedValue({
      id: "MESSAGE_USER_1",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "I want a refund.",
    });
    const messagesLoader = vi.fn().mockResolvedValue([
      {
        id: "MESSAGE_USER_1",
        sequence_no: 1,
        sender_role: "USER",
        message_text: "I want a refund.",
      },
      {
        id: "MESSAGE_AGENT_2",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_text: "Refund request recorded.",
      },
    ]);
    const turnMemoryLoader = vi.fn().mockResolvedValue({
      turn_no: 2,
      scroll_snapshot: {
        cards: [
          {
            key: "requested_outcome",
            label: "Expected outcome",
            value: "REFUND",
          },
          {
            key: "user_claim",
            label: "User claim",
            value: "Package not received.",
          },
        ],
        stamps: [{ text: "delivery conflict", level: "MEDIUM" }],
        admission_recommendation: "NEED_MORE_INFO",
      },
    });
    const wrapper = await mountInteractiveView({
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
    });

    await wrapper
      .get(".conversation-stream__composer textarea")
      .setValue("I want a refund.");
    await wrapper.get("[data-send-message]").trigger("submit");
    await flushPromises();

    expect(postMessageAction).toHaveBeenCalledWith(
      expect.objectContaining({ text: "I want a refund." }),
    );
    expect(messagesLoader).toHaveBeenCalled();
    expect(turnMemoryLoader).toHaveBeenCalled();
    expect(wrapper.text()).toContain("Refund request recorded.");
    expect(wrapper.text()).not.toContain("REFUND");
    expect(wrapper.text()).not.toContain("delivery conflict");
  });

  it("keeps a user-submitted Intake dossier behind the reply pacer", async () => {
    vi.useFakeTimers();
    const runId = "run-user-submit-reply-then-board";
    const streamUrl = `/api/private-agent-streams/${runId}/events`;
    const reply = "提交后的接待回复。".repeat(500);
    const summary = "用户提交后只会在回复完成后展示的案情摘要";
    let resolveFinalMessages;
    let resolveFinalMemory;
    const finalMessages = new Promise((resolve) => {
      resolveFinalMessages = resolve;
    });
    const finalMemory = new Promise((resolve) => {
      resolveFinalMemory = resolve;
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(streamUrl)) {
        return replyThenDossierStreamResponse(runId, reply, summary);
      }
      throw new Error(`unexpected fetch: ${String(input)}`);
    });
    const wrapper = await mountInteractiveView({
      initialTurnMemory: null,
      postMessageAction: vi.fn().mockResolvedValue({
        run_id: runId,
        stream_url: streamUrl,
      }),
      messagesLoader: vi.fn(() => finalMessages),
      turnMemoryLoader: vi.fn(() => finalMemory),
      eventStreamer: vi.fn(async () => {}),
    });

    await wrapper.get(".conversation-stream__composer textarea")
      .setValue("请核实订单的延迟送达。");
    await wrapper.get("[data-send-message]").trigger("submit");
    await flushPromises();

    expect(agentStreamStore.runs[runId]?.lastEventId).toBe("1");
    expect(wrapper.get("[data-dispute-detail-summary]").text())
      .not.toContain(summary);

    await vi.advanceTimersByTimeAsync(1_500);
    await flushPromises();

    expect(agentStreamStore.runs[runId]?.status).toBe("FINALIZING");
    expect(wrapper.get("[data-dispute-detail-summary]").text()).toContain(summary);

    resolveFinalMessages([]);
    resolveFinalMemory(null);
    await flushPromises();
    await vi.advanceTimersByTimeAsync(100);
    await flushPromises();
    expect(agentStreamStore.runs[runId]?.status).toBe("COMPLETED");
    wrapper.unmount();
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("shows the party statement immediately while the intake officer is thinking", async () => {
    let resolvePost;
    const postMessageAction = vi.fn(
      () =>
        new Promise((resolve) => {
          resolvePost = resolve;
        }),
    );
    const messagesLoader = vi.fn().mockResolvedValue([
      {
        id: "MESSAGE_USER_1",
        sequence_no: 1,
        sender_role: "USER",
        message_text: "我希望退款。",
      },
      {
        id: "MESSAGE_AGENT_2",
        sequence_no: 2,
        sender_role: "CUSTOMER_SERVICE",
        message_text: "我已记录你的退款诉求。",
      },
    ]);
    const turnMemoryLoader = vi.fn().mockResolvedValue({ turn_no: 2 });
    const wrapper = await mountInteractiveView({
      postMessageAction,
      messagesLoader,
      turnMemoryLoader,
    });

    await wrapper
      .get(".conversation-stream__composer textarea")
      .setValue("我希望退款。");
    await wrapper.get("[data-send-message]").trigger("submit");
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain("我希望退款。");
    expect(messagesLoader).not.toHaveBeenCalled();
    expect(wrapper.get("[data-confirm-admission]").text()).toContain("正在整理…");
    expect(wrapper.get("[data-confirm-admission]").text()).not.toContain("正在盖章");

    resolvePost({
      id: "MESSAGE_USER_1",
      sequence_no: 1,
      sender_role: "USER",
      message_text: "我希望退款。",
    });
    await flushPromises();

    expect(messagesLoader).toHaveBeenCalled();
    expect(wrapper.text()).toContain("我已记录你的退款诉求。");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("maps backend enum values and missing slot keys into Chinese dossier copy", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 4,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 45,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "Broken watch quality issue",
              one_sentence_summary:
                "The user reports that the watch is broken. No additional details or evidence provided.",
            },
            references: {
              order_reference: "123456",
              after_sales_reference: "123123",
              logistics_reference: "123123",
            },
            party_positions: {
              user_claim:
                "The user reports that the watch is broken. No additional details or evidence provided.",
              merchant_claim: "",
            },
            dispute_focus: {
              core_issue: "UNKNOWN",
              facts_to_verify: ["product_issue_details", "user_statement"],
            },
            requested_resolution: {
              requested_outcome: "UNKNOWN",
            },
            risk_assessment: {
              risk_signals: ["ORDER_REFERENCE_CONFLICT"],
              reasoning:
                "仍缺少可信的product_issue_details、user_statement、merchant_requested_outcome、order_reference_confirmation",
            },
            intake_quality: {
              score: 45,
              threshold: 80,
              ready_for_next_step: false,
              improvement_reason:
                "仍缺少可信的product_issue_details、user_statement、merchant_requested_outcome、order_reference_confirmation",
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    expect(wrapper.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(wrapper.get("[data-dispute-detail-summary]").element.tagName).toBe("DIV");
    expect(wrapper.text()).toContain("用户反馈手表损坏");
    expect(wrapper.text()).toContain("故障细节");
    expect(wrapper.text()).toContain("用户原始陈述");
    expect(wrapper.text()).toContain("商家对诉求的明确回应");
    expect(wrapper.text()).not.toContain("核对订单信息与涉案商品");
    expect(wrapper.text()).toContain("继续完善案件信息");
    expect(wrapper.text()).toContain("待确认");
    expect(wrapper.text()).not.toContain("Expected outcome");
    expect(wrapper.text()).not.toContain("NEED_MORE_INFO");
    expect(wrapper.text()).not.toContain("product_issue_details");
    expect(wrapper.text()).not.toContain("Broken watch quality issue");
    expect(wrapper.get("[data-dispute-detail-summary]").text()).not.toContain(
      "The user reports",
    );
    expect(wrapper.get("[data-origin-statement-text]").attributes("title")).toBe(
      dispute.description,
    );
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("renders the live v2 product-quality dossier on the right-side board", async () => {
    const productQualityDispute = {
      ...dispute,
      order_id: "ORDER-T05-50465E63B684EB3081A33203",
      after_sale_id: "AFTER-T05-50465E63B684EB3081A33203",
      logistics_id: "LOG-T05-50465E63B684EB3081A33203",
      title: "使用短期后出现质量故障",
      description: "商品正常使用十天后频繁自动关机，远程排障和恢复出厂设置均未解决。",
      dispute_type: "PRODUCT_QUALITY",
    };
    const wrapper = await mountInteractiveView({
      initialDispute: productQualityDispute,
      initialAnalysis: {
        ...analysis,
        initiator_role: "USER",
        order_reference: productQualityDispute.order_id,
        after_sales_reference: productQualityDispute.after_sale_id,
        logistics_reference: productQualityDispute.logistics_id,
        requested_outcome: "换货或维修",
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 1,
          quality_score: 72,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake-dossier.v2",
            case_story: {
              one_sentence_summary:
                "商品正常使用十天后频繁自动关机，远程排障和恢复出厂设置均未解决。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REPLACE_OR_REPAIR",
              normalized_statement: "用户请求换货或维修，并核验商品频繁自动关机的原因。",
              original_statement: productQualityDispute.description,
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "NOT_RESPONDED",
            },
            dispute_core_state: {
              core_conflict: "商品在正常使用十天后是否存在质量故障。",
              facts_in_dispute: ["频繁自动关机是否可以稳定复现"],
              next_verification_focus: ["核验故障复现视频", "核验远程排障记录"],
            },
            intake_quality: {
              score: 72,
              threshold: 85,
              ready_for_next_step: false,
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
            case_fact_matrix: {
              schema_version: "case_fact_matrix.v2",
              matrix_kind: "INITIATOR_FROZEN",
            },
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.get("[data-dispute-detail-summary]").text()).toContain(
      "商品正常使用十天后频繁自动关机",
    );
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain(
      "用户请求换货或维修",
    );
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("核验故障复现视频");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("核验远程排障记录");
    expect(disputeDetail.text()).not.toContain("等待接待官整理");
    expect(wrapper.get("[data-case-index-strip]").text()).toContain(
      "ORDER-T05-50465E63B684EB3081A33203",
    );
  });

  it("keeps the legacy v1 rich dossier visible after schema normalization", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const legacyMemory = structuredClone(readyTurnMemory);
    legacyMemory.case_intake_dossier.dossier.caseFactMatrix = {
      schemaVersion: "case_fact_matrix.v2",
      matrixKind: "BILATERAL_FROZEN",
    };
    const wrapper = await mountInteractiveView({
      initialTurnMemory: legacyMemory,
      initialIntakeStatus: {
        initiator_role: "USER",
        respondent_role: "MERCHANT",
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
    });

    expect(wrapper.get("[data-dispute-detail-summary]").text()).toContain("物流显示签收");
    expect(wrapper.get("[data-dispute-detail-claim]").text()).not.toContain("等待接待官整理");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("签收人身份");
    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("完善度 88%");
    expect(wrapper.get("[data-confirm-admission]").attributes("disabled")).toBeUndefined();
  });

  it("ignores legacy unilateral branches when deriving formal matrix readiness", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const unilateralMemory = structuredClone(readyTurnMemory);
    unilateralMemory.case_intake_dossier.quality_score = 100;
    unilateralMemory.case_intake_dossier.ready_for_next_step = true;
    unilateralMemory.case_intake_dossier.dossier.schema_version = "intake-dossier.v2";
    unilateralMemory.case_intake_dossier.dossier.unilateralCaseMatrix = {
      schemaVersion: "unilateral_case_matrix.v1",
      factRows: [],
    };
    const wrapper = await mountInteractiveView({
      initialTurnMemory: unilateralMemory,
      initialIntakeStatus: {
        initiator_role: "USER",
        respondent_role: "MERCHANT",
        initiator_status: "COMPLETED",
        respondent_status: "OPEN",
        current_actor_completed: false,
        can_use_intake: true,
        can_enter_evidence: false,
      },
    });

    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("完善度 0%");
    expect(wrapper.get("[data-confirm-admission]").attributes("disabled")).toBeDefined();
  });

  it("renders the current case-detail dossier as the right-side board", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "MERCHANT",
      },
      initialTurnMemory: {
        turn_no: 4,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 88,
          ready_for_next_step: true,
          admission_recommendation: "ACCEPTED",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "物流显示签收但用户称未收到商品",
              one_sentence_summary:
                "用户称订单物流已显示签收，但本人未收到商品，商家暂未提供签收底单。",
              event_timeline: [
                {
                  time_hint: "物流签收后",
                  event: "用户发现未收到商品",
                  source: "USER_MESSAGE",
                },
              ],
            },
            references: {
              order_reference: "ORDER-1",
              after_sales_reference: "AFTER-1",
              logistics_reference: "SF1234567890",
            },
            party_positions: {
              user_claim: "物流显示签收但我没有收到商品。",
              merchant_claim: "商家要求等待物流核查。",
              platform_observation: "需要核验签收底单。",
            },
            dispute_focus: {
              core_issue: "SIGNED_NOT_RECEIVED",
              key_conflicts: ["签收记录与用户未收货陈述冲突"],
              facts_to_verify: ["签收底单"],
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户希望退款。",
            },
            claim_resolution: {
              initiator_role: "MERCHANT",
              requested_resolution: "REFUND",
              normalized_statement: "商家要求等待物流核查。",
              original_statement: "商家要求等待物流核查。",
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: ["SIGNED_NOT_RECEIVED"],
              reasoning: "存在签收事实冲突。",
            },
            missing_information: {
              blocking_gaps: [],
              nice_to_have_gaps: ["签收底单"],
              next_questions: [],
            },
            intake_quality: {
              score: 88,
              threshold: 80,
              ready_for_next_step: true,
              improvement_reason: "仍缺少可信的用户原始陈述与商家质检视频。",
            },
            admission: {
              recommendation: "ACCEPTED",
              reasoning: "接待信息已足够进入证据阶段。",
              confidence: 0.88,
            },
          },
        },
      },
    });

    expect(wrapper.find("[data-case-detail-dossier]").exists()).toBe(true);
    const summaryCard = wrapper.get("[data-case-detail-summary-card]");
    expect(wrapper.find("[data-dossier-status-rail]").exists()).toBe(true);
    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("完善度 88%");
    expect(wrapper.get("[data-dossier-status-rail]").text()).toContain("中风险");
    expect(summaryCard.find("[data-dispute-detail-card]").exists()).toBe(true);
    expect(summaryCard.find("[data-dispute-detail-card]").text()).toContain("争议详情");
    expect(summaryCard.find(".intake-case-detail__story").exists()).toBe(false);
    expect(summaryCard.find(".intake-case-detail__focus").exists()).toBe(false);
    expect(summaryCard.find(".intake-case-detail__reason").exists()).toBe(false);
    expect(summaryCard.find(".intake-case-detail__chips").exists()).toBe(false);
    expect(summaryCard.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(summaryCard.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(summaryCard.get("[data-dispute-detail-summary]").element.tagName).toBe("DIV");
    expect(wrapper.text()).toContain("用户称订单物流已显示签收");
    expect(summaryCard.text()).not.toContain("仍缺少可信的用户原始陈述与商家质检视频。");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("下一步核验重点");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("物流签收及投递记录");
    expect(wrapper.text()).not.toContain("SIGNED_NOT_RECEIVED");
    expect(wrapper.text()).not.toContain("88/100");
    expect(wrapper.text()).toContain("可以进入下一步");
    expect(wrapper.find("[data-case-detail-dossier]").exists()).toBe(true);
    expect(wrapper.find("[data-case-detail-meta]").exists()).toBe(false);
    expect(wrapper.find("[data-case-index-strip]").exists()).toBe(true);
    expect([...summaryCard.element.children].some((child) => child.hasAttribute("data-case-index-strip"))).toBe(false);
    expect(summaryCard.get("[data-dispute-detail-card] [data-case-index-strip]").exists()).toBe(true);
    expect(summaryCard.get("[data-dispute-detail-card] [data-origin-statement-card]").exists()).toBe(true);
    expect(wrapper.findAll("[data-case-index-chip]").length).toBe(0);
    expect(wrapper.find("[data-case-index-list]").exists()).toBe(true);
    expect(wrapper.findAll("[data-case-index-field]").length).toBe(3);
    expect(wrapper.get("[data-case-index-strip]").text()).toContain("ORDER-1");
    expect(wrapper.get("[data-case-index-strip]").text()).toContain("AFTER-1");
    expect(wrapper.get("[data-case-index-strip]").text()).toContain("SF1234567890");
    expect(wrapper.find("[data-party-claims-grid]").exists()).toBe(false);
    expect(wrapper.findAll("[data-party-claim-card]").length).toBe(0);
    expect(wrapper.find("[data-origin-statement-card]").exists()).toBe(true);
    expect(wrapper.find("[data-single-party-statement]").exists()).toBe(true);
    expect(wrapper.findAll("[data-dossier-section]").length).toBe(0);
    expect(wrapper.text()).toContain("案件索引");
    expect(wrapper.text()).toContain("原始陈述");
    expect(wrapper.find("[data-origin-statement-note]").exists()).toBe(false);
    expect(wrapper.find("[data-single-party-statement-label]").exists()).toBe(true);
    expect(wrapper.text()).toContain("商家要求等待物流核查。");
    expect(wrapper.text()).not.toContain("用户描述商家要求等待物流核查。");
    expect(wrapper.text()).not.toContain("用户描述：商家自述");
    expect(wrapper.text()).not.toContain("商家情况（用户转述/待核验）");
    expect(wrapper.text()).not.toContain("双方说法");
    expect(wrapper.text()).not.toContain("用户主张");
    expect(wrapper.text()).not.toContain("商家主张");
    expect(wrapper.text()).not.toContain("订单 / 售后 / 物流");
    expect(wrapper.text()).toContain("ORDER-1");
    expect(wrapper.text()).toContain("AFTER-1");
    expect(wrapper.text()).toContain("SF1234567890");
    expect(wrapper.text()).not.toContain("发起方：商家");
    expect(wrapper.text()).not.toContain("关联引用");
    expect(wrapper.text()).not.toContain("处理判断");
    expect(wrapper.get("[data-case-risk-grade]").text()).toContain("中风险");
    expect(wrapper.get("[data-dossier-progress-hint]").text()).toBe("可以进入下一步");
    expect(wrapper.text()).not.toContain("可继续对话纠正");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("renders the claim and respondent attitude state from the intake dossier", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 1,
          quality_score: 82,
          ready_for_next_step: true,
          admission_recommendation: "ACCEPTED",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "签收未收到争议",
              one_sentence_summary: "物流显示签收，但用户称本人未收到包裹。",
            },
            references: {
              order_reference: "ORDER-CLAIM-1",
              logistics_reference: "SF123456789",
            },
            party_positions: {
              user_claim: "用户称本人未收到包裹。",
              merchant_claim: "",
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户请求退款。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REFUND",
              requested_amount: 299,
              requested_items: "儿童手表 1 件",
              request_reason: "用户称物流显示签收但本人未收到包裹，希望退款。",
              original_statement: "我没收到包裹，希望退款",
              normalized_statement: "用户称未实际收到包裹，并请求退款。",
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "NOT_RESPONDED",
              position: "商家尚未在接待室表达态度。",
              source: "尚未回应",
              confidence: 0.5,
            },
            dispute_core_state: {
              core_conflict: "用户请求退款，但商家态度尚待补充。",
              conflict_type: "CLAIM_UNANSWERED",
              facts_in_dispute: ["用户是否实际收到商品"],
              next_verification_focus: ["签收人身份", "物流投递轨迹"],
            },
            dispute_focus: {
              core_issue: "SIGNED_NOT_RECEIVED",
              facts_to_verify: ["签收人身份"],
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
            },
            intake_quality: {
              score: 82,
              threshold: 80,
              ready_for_next_step: true,
            },
            admission: {
              recommendation: "ACCEPTED",
            },
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.text()).toContain("争议详情");
    expect(disputeDetail.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(disputeDetail.get("[data-dispute-detail-summary]").element.tagName).toBe("DIV");
    expect(disputeDetail.get("[data-dispute-detail-summary]").text()).toContain("物流显示签收");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("用户称未实际收到包裹，并请求退款");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("¥299");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("儿童手表 1 件");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("我方（用户）诉求");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("对方（商家）回应");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("暂无回应");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).not.toContain("商家尚未在接待室表达态度");
    expect(disputeDetail.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("签收人身份");
    expect(wrapper.find("[data-case-claim-status]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("REFUND");
    expect(wrapper.text()).not.toContain("NOT_RESPONDED");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps a reported counterparty attitude out of the initiator response card", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              one_sentence_summary: "用户称配件缺失，并表示商家此前拒绝补发。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "RESHIP",
              normalized_statement: "用户请求补发缺失配件。",
              original_statement: "商家说不给我补发。",
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "DISAGREE",
              position: "商家不支持补发。",
              source: "发起方单方陈述（主观）",
              confidence: 0.9,
            },
            intake_quality: {
              score: 60,
              ready_for_next_step: false,
            },
          },
        },
      },
    });

    const response = wrapper.get("[data-dispute-detail-respondent]");
    expect(response.text()).toContain("对方（商家）回应");
    expect(response.text()).toContain("暂无回应");
    expect(response.text()).not.toContain("商家不支持补发");
    expect(response.text()).not.toContain("主观");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps a legacy counterparty position out of the initiator response card", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              one_sentence_summary: "用户称配件缺失，并转述商家拒绝补发。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "RESHIP",
              normalized_statement: "用户请求补发缺失配件。",
              original_statement: "商家说不给我补发。",
            },
            party_positions: {
              user_claim: "用户请求补发缺失配件。",
              merchant_claim: "商家不支持补发。",
            },
            intake_quality: {
              score: 60,
              ready_for_next_step: false,
            },
          },
        },
      },
    });

    const response = wrapper.get("[data-dispute-detail-respondent]");
    expect(response.text()).toContain("对方（商家）回应");
    expect(response.text()).toContain("暂无回应");
    expect(response.text()).not.toContain("商家不支持补发");
    expect(response.text()).not.toContain("主观");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("renders the original statement without trimming or replacing internal-looking tokens", async () => {
    const originalStatement = "  我原话里写了 REFUND 和 UNKNOWN。\n\n请保持原样。  ";
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              one_sentence_summary: "接待官已将发起方诉求整理为完整事件摘要。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "OTHER",
              original_statement: originalStatement,
            },
            intake_quality: {
              score: 60,
              ready_for_next_step: false,
            },
          },
        },
      },
    });

    expect(wrapper.get("[data-origin-statement-text]").attributes("title")).toBe(
      originalStatement,
    );
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("shows claim status and verification gaps for legacy case-detail dossiers without structured claim fields", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        order_reference: "ORDER-FE-31029016",
        after_sales_reference: "",
        logistics_reference: "",
      },
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 1,
          quality_score: 0,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "履约争议待核实",
              one_sentence_summary: "物流显示签收，但我没有收到包裹。我希望平台核实后退款。",
            },
            references: {
              order_reference: "ORDER-FE-31029016",
              after_sales_reference: "",
              logistics_reference: "",
            },
            party_positions: {
              user_claim: "物流显示签收，但我没有收到包裹。我希望平台核实后退款。",
              merchant_claim: "",
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户请求退款。",
            },
            risk_assessment: {
              case_grade: "HIGH",
              risk_signals: [],
            },
            intake_quality: {
              score: 0,
              threshold: 80,
              ready_for_next_step: false,
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.text()).toContain("争议详情");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("用户请求退款");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("对方（商家）回应");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("暂无回应");
    expect(disputeDetail.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(disputeDetail.text()).not.toContain("REFUND");
    expect(disputeDetail.text()).not.toContain("UNKNOWN");

    const gaps = wrapper.get("[data-verification-gaps]");
    expect(gaps.text()).toContain("下一步核验重点");
    expect(gaps.text()).toContain("物流签收及投递记录");
    expect(gaps.text()).toContain("商家对诉求的明确回应");
    expect(gaps.findAll("[data-verification-gap-item]")).toHaveLength(2);
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("uses the user position as the respondent response when the merchant initiated the dispute", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "MERCHANT",
        party_claims: {
          merchant: "商家请求平台驳回退款。",
          user: "用户不同意驳回，仍要求退款。",
        },
      },
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 1,
          dossier: {
            schema_version: "intake_case_detail.v1",
            claim_resolution: {
              initiator_role: "MERCHANT",
              requested_resolution: "OTHER",
              normalized_statement: "商家请求平台驳回退款。",
            },
            party_positions: {
              merchant_claim: "商家请求平台驳回退款。",
              user_claim: "用户不同意驳回，仍要求退款。",
            },
          },
        },
      },
    });

    const respondent = wrapper.get("[data-dispute-detail-respondent]");
    expect(wrapper.get("[data-dispute-detail-claim]").text()).toContain("对方（商家）诉求");
    expect(respondent.text()).toContain("我方（用户）回应");
    expect(respondent.text()).toContain("用户不同意驳回，仍要求退款");
    expect(respondent.text()).not.toContain("商家请求平台驳回退款");
    expect(respondent.text()).not.toContain("主观");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("asks for the user response when a merchant-initiated dispute has no respondent position", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "MERCHANT",
        party_claims: {
          merchant: "商家请求平台核验用户退款理由。",
          user: "",
        },
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 1,
          dossier: {
            schema_version: "intake_case_detail.v1",
            claim_resolution: {
              initiator_role: "MERCHANT",
              requested_resolution: "VERIFY_OR_EXPLAIN_ONLY",
            },
            party_positions: {
              merchant_claim: "商家请求平台核验用户退款理由。",
              user_claim: "",
            },
          },
        },
      },
    });

    expect(wrapper.get("[data-dispute-detail-respondent]").text()).toContain("我方（用户）回应");
    expect(wrapper.get("[data-dispute-detail-respondent]").text()).toContain("暂无回应");
    expect(wrapper.get("[data-verification-gaps]").text()).toContain("用户对诉求的明确回应");
    expect(wrapper.get("[data-verification-gaps]").text()).not.toContain("商家对诉求的明确回应");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("preserves external reference identifiers without translating enum-like tokens", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        order_reference: "ORDER_MERCHANT_INITIATED",
        after_sales_reference: "AFTER_USER_HIGH",
        logistics_reference: "LOGISTICS_MEDIUM_USER",
      },
      initialTurnMemory: {
        ...readyTurnMemory,
        case_intake_dossier: {
          ...readyTurnMemory.case_intake_dossier,
          dossier: {
            ...readyTurnMemory.case_intake_dossier.dossier,
            references: {
              order_reference: "ORDER_MERCHANT_INITIATED",
              after_sales_reference: "AFTER_USER_HIGH",
              logistics_reference: "LOGISTICS_MEDIUM_USER",
            },
          },
        },
      },
    });

    const index = wrapper.get("[data-case-index-strip]");
    expect(index.text()).toContain("ORDER_MERCHANT_INITIATED");
    expect(index.text()).toContain("AFTER_USER_HIGH");
    expect(index.text()).toContain("LOGISTICS_MEDIUM_USER");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps a claim status placeholder visible when the intake memory has no case-detail schema yet", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "USER",
        requested_outcome: "",
        party_claims: {
          user: "",
          merchant: "",
        },
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 0,
          quality_score: 0,
          ready_for_next_step: false,
          dossier: {
            schema_version: "legacy_intake_summary.v1",
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.text()).toContain("争议详情");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("等待接待官整理");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).not.toContain("请求待确认诉求");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).not.toContain("待确认诉求待确认");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("暂无回应");
    expect(disputeDetail.text()).not.toContain("UNKNOWN");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("does not duplicate unknown role and unknown resolution in the claim placeholder", async () => {
    const wrapper = await mountInteractiveView({
      initialAnalysis: {
        ...analysis,
        initiator_role: "UNKNOWN",
        requested_outcome: "",
        party_claims: {
          user: "",
          merchant: "",
        },
      },
      initialTurnMemory: {
        turn_no: 1,
        case_intake_dossier: {
          dossier_version: 0,
          quality_score: 0,
          ready_for_next_step: false,
          dossier: {
            schema_version: "legacy_intake_summary.v1",
          },
        },
      },
    });

    const disputeDetail = wrapper.get("[data-dispute-detail-card]");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).toContain("等待接待官整理");
    expect(disputeDetail.get("[data-dispute-detail-claim]").text()).not.toContain("待确认诉求待确认");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).toContain("暂无回应");
    expect(disputeDetail.get("[data-dispute-detail-respondent]").text()).not.toContain("待确认尚未回应");
    expect(disputeDetail.text()).not.toContain("待确认尚未");
    expect(disputeDetail.text()).not.toContain("待确认的具体诉求");
    expect(disputeDetail.text()).not.toContain("待确认诉求与待确认回应");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("shows service errors in a modal notice instead of embedding them in the right dossier card", async () => {
    const wrapper = await mountInteractiveView({
      confirmAction: vi.fn().mockRejectedValue(new Error("服务返回了不可解析的响应（HTTP 502）")),
    });

    await wrapper.get("[data-confirm-admission]").trigger("click");
    await flushPromises();

    expect(wrapper.find(".intake-dossier__error").exists()).toBe(false);
    const notice = wrapper.get("[data-intake-error-dialog]");
    expect(notice.attributes("role")).toBe("alertdialog");
    expect(notice.text()).toContain("服务暂时不可用");
    expect(notice.text()).toContain("HTTP 502");
    expect(wrapper.get("[data-case-detail-dossier]").text()).not.toContain("HTTP 502");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps long dossier text bounded while preserving full content for inspection", async () => {
    const longTitle = "物流显示签收但用户称本人、家人、同住人和门岗均未收到商品且商家坚持以系统签收记录拒绝退款的复杂履约争议";
    const longSummary =
      "用户称订单物流在系统中显示签收，但本人没有收到包裹，快递柜也没有取件记录，门岗和家人均表示未代收；商家客服要求用户自行联系平台，暂未提供签收底单、投递照片或签收人身份信息。";
    const longConflict =
      "用户请求退款并要求核验签收真实性，但商家暂未明确同意退款，双方争议集中在物流签收记录是否足以证明用户本人或其授权人员已经实际收到商品。";
    const longStatement =
      "我没有收到包裹。系统显示签收，但快递柜没有记录，门岗也说没有帮我签收，家里人也没有收到。商家客服让我找平台处理，我希望平台核验签收真实性后给我退款。";
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 2,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 63,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: longTitle,
              one_sentence_summary: longSummary,
            },
            references: {
              order_reference: "ORDER-WITH-A-VERY-LONG-REFERENCE-202607090001",
              after_sales_reference: "AFTER-SALE-LONG-ID-202607090001",
              logistics_reference: "SF-VERY-LONG-LOGISTICS-TRACKING-NUMBER-202607090001",
            },
            party_positions: {
              user_claim: longStatement,
              merchant_claim: "商家尚未给出明确退款态度，只要求用户等待物流核查。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REFUND",
              requested_amount: 299,
              requested_items: "儿童手表 1 件，订单内还包含表带和保护膜，需要确认是否整体退款",
              original_statement: longStatement,
              normalized_statement: "用户称未实际收到包裹，并请求退款。",
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "NOT_RESPONDED",
              position: "商家尚未在接待室表达明确态度，仅要求用户等待物流核查，未说明是否接受退款。",
            },
            dispute_core_state: {
              core_conflict: longConflict,
              facts_in_dispute: ["用户是否实际收到商品", "签收记录是否足以证明本人收货"],
              next_verification_focus: [
                "签收人身份、签收位置和签收时间需要进一步核验",
                "物流投递照片、快递柜记录或门岗代收记录需要补充",
              ],
            },
            dispute_focus: {
              core_issue: "物流签收记录与用户未收到陈述之间的事实冲突，需要核验签收人身份、签收位置和投递链路",
              facts_to_verify: ["签收人身份", "签收位置", "物流投递轨迹"],
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
            },
            intake_quality: {
              score: 63,
              threshold: 80,
              ready_for_next_step: false,
              improvement_reason:
                "仍缺少可信的签收人身份、签收位置、物流投递照片、商家是否接受退款的明确态度。",
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    const detailCard = wrapper.get("[data-dispute-detail-card]");
    expect(detailCard.find("[data-dispute-detail-title]").exists()).toBe(false);
    expect(detailCard.get("[data-dispute-detail-summary]").attributes("title")).toBe(longSummary);
    expect(detailCard.find("[data-dispute-detail-focus]").exists()).toBe(false);
    expect(detailCard.find("[data-dispute-detail-facts]").exists()).toBe(false);
    expect(wrapper.get("[data-origin-statement-text]").attributes("title")).toBe(longStatement);
    expect(wrapper.findAll("[data-verification-gap-item]").length).toBeLessThanOrEqual(4);

    const source = readUtf8Source("src/views/disputes/IntakeRoomView.vue");
    expect(source).toContain("@supports (-webkit-line-clamp: 1)");
    expect(source).toContain("import ExpandableText");
    expect(source).toContain('data-dossier-fulltext-trigger="summary"');
    expect(source).toContain('data-dossier-fulltext-trigger="origin"');
    expect(source).toContain(':lines="5"');
    expect(source).toContain(':lines="4"');
    expect(source).toContain(".intake-case-detail__summary-note");
    expect(source).toContain("height: 110px;");
    expect(source).toContain("align-content: center;");
    expect(source).toContain(".intake-case-detail__origin-card");
    expect(source).toContain("height: 108px;");
    expect(source).toMatch(
      /\.intake-case-detail__single-statement\s*\{[\s\S]*?overflow: hidden;/,
    );
    expect(source).not.toContain("data-dispute-detail-title");
    expect(source).not.toContain("background: #ffffffad;");
    expect(source).not.toContain("border: 1px solid #e1ebf7;");
    expect(source).toContain("data-origin-statement-text");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps four verification previews and opens complete dossier text accessibly", async () => {
    const longSummary = "摘".repeat(300);
    const longStatement = "原".repeat(500);
    const verificationItems = Array.from(
      { length: 10 },
      (_, index) => `核验事项 ${index + 1}`,
    );
    const resizeCallbacks = [];

    class ResizeObserverMock {
      constructor(callback) {
        resizeCallbacks.push(callback);
      }

      observe() {}

      disconnect() {}
    }

    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    const dialogPrototype = globalThis.HTMLDialogElement.prototype;
    const originalShowModal = dialogPrototype.showModal;
    const originalClose = dialogPrototype.close;
    const showModal = vi.fn(function showModalMock() {
      this.open = true;
    });
    const close = vi.fn(function closeMock() {
      this.open = false;
    });
    Object.defineProperty(dialogPrototype, "showModal", {
      configurable: true,
      writable: true,
      value: showModal,
    });
    Object.defineProperty(dialogPrototype, "close", {
      configurable: true,
      writable: true,
      value: close,
    });
    let wrapper = await mountInteractiveView({
      attachTo: document.body,
      initialTurnMemory: {
        turn_no: 3,
        case_intake_dossier: {
          dossier_version: 2,
          quality_score: 72,
          ready_for_next_step: false,
          admission_recommendation: "NEED_MORE_INFO",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "复杂售后争议",
              one_sentence_summary: longSummary,
            },
            references: {
              order_reference: "ORDER-1",
              after_sales_reference: "AFTER-1",
              logistics_reference: "LOG-1",
            },
            party_positions: {
              user_claim: longStatement,
              merchant_claim: "商家已明确回应。",
            },
            claim_resolution: {
              initiator_role: "USER",
              requested_resolution: "REFUND",
              normalized_statement: "用户请求退款。",
              original_statement: longStatement,
            },
            respondent_attitude: {
              respondent_role: "MERCHANT",
              attitude: "AGREE",
              position: "商家同意退款。",
            },
            dispute_core_state: {
              core_conflict: "退款执行细节仍待确认。",
              facts_in_dispute: [],
              next_verification_focus: verificationItems,
            },
            dispute_focus: {
              core_issue: "退款执行细节",
              facts_to_verify: verificationItems,
            },
            missing_information: {
              blocking_gaps: verificationItems,
              nice_to_have_gaps: [],
              next_questions: [],
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
            },
            intake_quality: {
              score: 72,
              threshold: 80,
              ready_for_next_step: false,
              improvement_reason: "",
            },
            admission: {
              recommendation: "NEED_MORE_INFO",
            },
          },
        },
      },
    });

    try {
      const summaryContent = wrapper.get(
        '[data-dossier-fulltext-trigger="summary"] [data-expandable-content]',
      ).element;
      const originContent = wrapper.get(
        '[data-dossier-fulltext-trigger="origin"] [data-expandable-content]',
      ).element;
      for (const element of [summaryContent, originContent]) {
        Object.defineProperty(element, "clientHeight", {
          value: 72,
          configurable: true,
        });
        Object.defineProperty(element, "scrollHeight", {
          value: 160,
          configurable: true,
        });
      }
      resizeCallbacks.forEach((callback) => callback());
      await wrapper.vm.$nextTick();

      expect(wrapper.findAll("[data-verification-gap-item]")).toHaveLength(4);
      expect(wrapper.get("[data-verification-gap-overflow]").text()).toContain(
        "另有 6 项",
      );

      const summaryTrigger = wrapper.get(
        '[data-dossier-fulltext-trigger="summary"] [data-expandable-trigger]',
      );
      summaryTrigger.element.focus();
      await summaryTrigger.trigger("click");
      await flushPromises();

      let dialog = wrapper.get("[data-dossier-fulltext-dialog]");
      expect(dialog.element.tagName).toBe("DIALOG");
      expect(dialog.element.open).toBe(true);
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(dialog.text()).toContain(longSummary);
      expect(document.activeElement).toBe(dialog.element);

      await wrapper.get("[data-dismiss-dossier-fulltext]").trigger("click");
      await flushPromises();
      expect(close).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(summaryTrigger.element);

      const originTrigger = wrapper.get(
        '[data-dossier-fulltext-trigger="origin"] [data-expandable-trigger]',
      );
      originTrigger.element.focus();
      await originTrigger.trigger("click");
      await flushPromises();

      dialog = wrapper.get("[data-dossier-fulltext-dialog]");
      expect(dialog.element.open).toBe(true);
      expect(showModal).toHaveBeenCalledTimes(2);
      expect(dialog.text()).toContain(longStatement);
      expect(document.activeElement).toBe(dialog.element);

      await dialog.trigger("cancel");
      await flushPromises();
      expect(close).toHaveBeenCalledTimes(2);
      expect(wrapper.find("[data-dossier-fulltext-dialog]").exists()).toBe(false);
      expect(document.activeElement).toBe(originTrigger.element);

      await originTrigger.trigger("click");
      await flushPromises();
      expect(showModal).toHaveBeenCalledTimes(3);
      wrapper.unmount();
      wrapper = null;
      expect(close).toHaveBeenCalledTimes(3);
    } finally {
      wrapper?.unmount();
      document.body.innerHTML = "";
      if (originalShowModal === undefined) {
        delete dialogPrototype.showModal;
      } else {
        Object.defineProperty(dialogPrototype, "showModal", {
          configurable: true,
          writable: true,
          value: originalShowModal,
        });
      }
      if (originalClose === undefined) {
        delete dialogPrototype.close;
      } else {
        Object.defineProperty(dialogPrototype, "close", {
          configurable: true,
          writable: true,
          value: originalClose,
        });
      }
      vi.unstubAllGlobals();
    }
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("infers the initiator from immutable party messages and keeps the respondent locked", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const { initiator_role: _initiatorRole, ...analysisWithoutInitiator } = analysis;
    const wrapper = await mountInteractiveView({
      initialAnalysis: analysisWithoutInitiator,
      initialMessages: [
        {
          id: "MESSAGE_AGENT_1",
          sequence_no: 1,
          sender_role: "CUSTOMER_SERVICE",
          message_text: "agent prompt",
          created_at: "2026-07-05T00:00:00Z",
        },
        {
          id: "MESSAGE_MERCHANT_1",
          sequence_no: 2,
          sender_role: "MERCHANT",
          message_text: "merchant intake answer",
          created_at: "2026-07-05T00:01:00Z",
        },
      ],
    });

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.find("[data-intake-locked-chat]").exists()).toBe(true);
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(false);
    expect(wrapper.find("[data-intake-waiting-for-initiator]").exists()).toBe(true);
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("lays out the submit and resolve actions as a two-column action bar", async () => {
    const { wrapper } = await mountView();

    const actions = wrapper.get(".intake-dossier__actions");

    expect(actions.find("[data-confirm-admission]").exists()).toBe(true);
    expect(actions.find("[data-resolve-without-dispute]").exists()).toBe(true);
    expect(actions.classes()).toContain("intake-dossier__actions--two-column");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps persisted handoff remarks out of the right-side judgment area without adding a second input box", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 5,
        case_intake_dossier: {
          dossier_version: 3,
          quality_score: 91,
          ready_for_next_step: true,
          admission_recommendation: "ACCEPTED",
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "签收未收到争议",
              one_sentence_summary: "用户补充了进入下一轮前需要带给证据书记官的备注。",
            },
            references: {
              order_reference: "ORDER-1",
              after_sales_reference: "AFTER-1",
              logistics_reference: "SF1234567890",
            },
            party_positions: {
              user_claim: "物流显示签收但我没有收到商品。",
              merchant_claim: "商家要求等待物流核查。",
            },
            dispute_focus: {
              core_issue: "SIGNED_NOT_RECEIVED",
              facts_to_verify: [],
            },
            requested_resolution: {
              requested_outcome: "REFUND",
              expected_resolution_text: "用户希望退款。",
            },
            risk_assessment: {
              case_grade: "MEDIUM",
              risk_signals: [],
              reasoning: "",
            },
            handoff_notes: {
              remark_status: "HAS_REMARKS",
              latest_remark: "请证据书记官重点核查快递柜取件记录。",
              remarks: [
                {
                  role: "USER",
                  text: "请证据书记官重点核查快递柜取件记录。",
                  source_message_id: "MESSAGE_REMARK_1",
                },
              ],
            },
            intake_quality: {
              score: 91,
              threshold: 80,
              ready_for_next_step: true,
              improvement_reason: "",
            },
            admission: {
              recommendation: "ACCEPTED",
            },
          },
        },
      },
    });

    expect(wrapper.text()).not.toContain("下一轮备注");
    expect(wrapper.text()).not.toContain("请证据书记官重点核查快递柜取件记录。");
    expect(wrapper.find(".intake-dossier__confirm textarea").exists()).toBe(false);
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps agent memory internals out of the party intake room UI", async () => {
    const wrapper = await mountInteractiveView({
      initialTurnMemory: {
        turn_no: 11,
        memory_frame: {
          memory_modes: {
            short_term_enabled: true,
            summary_enabled: true,
            long_term_enabled: false,
            short_term_round_limit: 5,
            summary_window_round_limit: 10,
            compressed_token_limit: 200,
          },
          short_term_rounds: [
            {
              turn_no: 7,
              messages: [
                { role: "USER", content: "round 7 user answer" },
                {
                  role: "DISPUTE_INTAKE_OFFICER",
                  content: "round 7 agent question",
                },
              ],
            },
          ],
          compressed_summary: "compressed ten-round intake memory",
          long_term_slots: [],
        },
      },
    });

    expect(wrapper.find("[data-memory-panel]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("round 7 user answer");
    expect(wrapper.text()).not.toContain("compressed ten-round intake memory");
    expect(wrapper.text()).not.toContain("Mem0");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("persists intake cancellation instead of only changing local state", async () => {
    const confirmAction = vi.fn();
    const cancelAction = vi.fn().mockResolvedValue({
      case_id: "CASE_INTAKE_1",
      case_status: "CANCELLED",
      current_room: null,
    });
    const { wrapper, router } = await mountView(confirmAction, null, cancelAction);
    disputeStore.list.data = [
      { id: "CASE_INTAKE_1" },
      { id: "CASE_REMAINS" },
    ];
    disputeStore.current.data = { id: "CASE_INTAKE_1" };

    await wrapper.get("[data-resolve-without-dispute]").trigger("click");
    await flushPromises();

    expect(cancelAction).toHaveBeenCalledWith(
      expect.objectContaining({
        reason: "resolved_before_admission",
      }),
    );
    expect(confirmAction).not.toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe("/disputes");
    expect(disputeStore.list.data).toEqual([{ id: "CASE_REMAINS" }]);
    expect(disputeStore.current.data).toBeNull();
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps reviewer identities from sending party dialogue to the intake agent", async () => {
    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";

    const { wrapper } = await mountView();

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.text()).toContain("切换为用户或商家身份");
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-intake-actions-readonly]").exists()).toBe(true);
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps the respondent locked until the initiator completes intake", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";

    const wrapper = await mountInteractiveView({
      initialAnalysis: { ...analysis, initiator_role: "USER" },
    });

    expect(wrapper.find(".conversation-stream__composer").exists()).toBe(false);
    expect(wrapper.find("[data-intake-locked-chat]").exists()).toBe(true);
    expect(wrapper.get("[data-intake-locked-chat]").text()).toContain("接待会话尚未开放");
    expect(wrapper.find("[data-confirm-admission]").exists()).toBe(false);
    expect(wrapper.find("[data-resolve-without-dispute]").exists()).toBe(false);
    expect(wrapper.find("[data-enter-evidence-room]").exists()).toBe(false);
  });

  it("keeps a locked respondent mutation-gated even when projection is ready", async () => {
    actor.id = "merchant-local";
    actor.role = "MERCHANT";
    const postMessageAction = vi.fn();
    const wrapper = await mountInteractiveView({
      initialAnalysis: { ...analysis, initiator_role: "USER" },
      initialIntakeStatus: intakeStatusWithProjection(
        currentProcessProjection({
          writer_mode: "TEMPORAL",
          room_phase: "OPEN",
          command_admission_state: "READY",
        }),
        {
          can_use_intake: false,
          current_actor_completed: false,
          initiator_status: "OPEN",
          respondent_status: "LOCKED",
        },
      ),
      postMessageAction,
    });

    await wrapper.vm.$.setupState.postMessage({
      message_text: "程序化提交不应绕过接待权限。",
    });
    await flushPromises();

    expect(postMessageAction).not.toHaveBeenCalled();
    expect(wrapper.find("[data-intake-locked-chat]").exists()).toBe(true);
    wrapper.unmount();
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("clears intake messages and latest memory immediately when actor changes in-place", async () => {
    const wrapper = await mountInteractiveView({
      initialMessages: [
        {
          id: "MESSAGE_USER_PRIVATE",
          sequence_no: 1,
          sender_role: "USER",
          message_text: "USER private intake chat should vanish",
        },
      ],
      initialTurnMemory: {
        turn_no: 3,
        case_intake_dossier: {
          quality_score: 88,
          ready_for_next_step: true,
          dossier: {
            schema_version: "intake_case_detail.v1",
            case_story: {
              title: "USER private right board",
              one_sentence_summary: "USER-only dossier text",
            },
            party_positions: {
              user_claim: "USER-only dossier text",
            },
            intake_quality: {
              score: 88,
              ready_for_next_step: true,
            },
          },
        },
      },
    });

    expect(wrapper.text()).toContain("用户 private intake chat should vanish");
    expect(wrapper.text()).toContain("用户-only dossier text");

    actor.id = "user-other";
    actor.role = "USER";
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).not.toContain("用户 private intake chat should vanish");
    expect(wrapper.text()).not.toContain("USER-only dossier text");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("ignores stale intake refresh results from the previous actor", async () => {
    let resolveUserMemory;
    const messagesLoader = vi
      .fn()
      .mockResolvedValueOnce([
        {
          id: "MESSAGE_USER_INITIAL",
          sequence_no: 1,
          sender_role: "USER",
          message_text: "USER initial intake chat",
        },
      ])
      .mockResolvedValue([
        {
          id: "MESSAGE_MERCHANT_CURRENT",
          sequence_no: 1,
          sender_role: "MERCHANT",
          message_text: "MERCHANT current intake chat",
        },
      ]);
    const turnMemoryLoader = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveUserMemory = resolve;
          }),
      )
      .mockResolvedValue({ turn_no: 1 });

    const wrapper = await mountInteractiveView({
      initialMessages: null,
      initialTurnMemory: null,
      messagesLoader,
      turnMemoryLoader,
      eventStreamer: vi.fn(async () => {}),
    });
    await flushPromises();

    actor.id = "user-other";
    actor.role = "USER";
    await wrapper.vm.$nextTick();

    resolveUserMemory({
      turn_no: 9,
      case_intake_dossier: {
        dossier: {
          schema_version: "intake_case_detail.v1",
          case_story: {
            title: "USER stale right board",
            one_sentence_summary: "USER stale right board",
          },
        },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("商家 current intake chat");
    expect(wrapper.text()).not.toContain("USER stale right board");
  });

  // 业务位置：【前端接待室】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
  it("keeps the intake room outer cards at a fixed non-stretching height", () => {
    const source = readUtf8Source("src/views/disputes/IntakeRoomView.vue");

    expect(source).toContain("--intake-panel-height: 740px;");
    expect(source).toContain(
      "grid-template-rows: 60px minmax(0, 1fr) 52px;",
    );
    expect(source).toContain("grid-template-rows: 44px 412px 96px;");
    expect(source).toContain("line-height: 1.2;");
    expect(source).toContain(
      "grid-template-columns: repeat(2, minmax(0, 1fr));",
    );
    expect(source).toContain(
      "grid-template-columns: repeat(3, minmax(0, 1fr));",
    );
    expect(source).toContain(
      "grid-template-columns: repeat(2, minmax(0, 1fr));",
    );
    expect(source).toMatch(
      /\.intake-case-detail__todo-text\s*\{[\s\S]*?text-overflow: ellipsis;[\s\S]*?white-space: nowrap;/,
    );
    expect(source).toContain("-webkit-line-clamp: 2;");
    expect(source).toContain(
      "@container room-workspace (min-width: 1060px)",
    );
    expect(source).not.toContain("@media (max-width: 980px)");
    expect(source).not.toMatch(
      /@media \(max-width: 580px\)[\s\S]*?intake-dossier__actions--two-column[\s\S]*?grid-template-columns: 1fr/,
    );
  });
});
