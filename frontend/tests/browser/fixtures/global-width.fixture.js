import { expect } from "@playwright/test";

export const GLOBAL_CASE_IDS = {
  intake: "CASE_GLOBAL_INTAKE",
  evidence: "CASE_GLOBAL_EVIDENCE",
  hearing: "CASE_GLOBAL_HEARING",
  outcome: "CASE_GLOBAL_OUTCOME",
};

export const GLOBAL_REVIEW_ID = "REVIEW_GLOBAL";

const GLOBAL_ROOM_MESSAGE_ENDPOINTS = {
  [GLOBAL_CASE_IDS.intake]: "INTAKE",
  [GLOBAL_CASE_IDS.evidence]: "EVIDENCE",
  [GLOBAL_CASE_IDS.hearing]: "HEARING",
};

export function assertGlobalRoomMessageEndpoint(caseId, roomType) {
  const expectedRoomType = GLOBAL_ROOM_MESSAGE_ENDPOINTS[caseId];
  if (!expectedRoomType) {
    throw new Error(
      `Global width fixture case ${caseId} does not expose a room messages endpoint.`,
    );
  }
  if (expectedRoomType !== roomType) {
    throw new Error(
      `Global width fixture case ${caseId} maps to ${expectedRoomType} room messages, not ${roomType}.`,
    );
  }
  return expectedRoomType;
}

const actors = {
  USER: { id: "user-local", role: "USER", label: "用户" },
  PLATFORM_REVIEWER: {
    id: "reviewer-local",
    role: "PLATFORM_REVIEWER",
    label: "平台审核员",
  },
};

const futureDeadline = "2026-07-12T18:00:00+08:00";
const serverNow = "2026-07-11T10:00:00+08:00";

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

function dispute(caseId, room, overrides = {}) {
  return {
    id: caseId,
    order_id: `ORDER_${caseId}`,
    after_sale_id: `AFTER_${caseId}`,
    logistics_id: `LOGISTICS_${caseId}`,
    initiator_role: "USER",
    source_type: "INTAKE_CREATED",
    dispute_type: "SIGNED_NOT_RECEIVED",
    case_status: `${room}_OPEN`,
    current_room: room,
    deadline_at: futureDeadline,
    risk_level: "MEDIUM",
    pending_action: "CONTINUE_PROCESSING",
    title: `${room} compact width smoke`,
    description: "物流显示签收，但用户本人及家人均未收到商品。",
    ...overrides,
  };
}

function intakeTurnMemory() {
  const gaps = [
    "核验签收人身份",
    "核验投递位置",
    "核验物流底单",
    "确认商家回应",
  ];
  return {
    turn_no: 4,
    case_intake_dossier: {
      dossier_version: 2,
      quality_score: 88,
      ready_for_next_step: true,
      admission_recommendation: "ACCEPTED",
      dossier: {
        schema_version: "intake_case_detail.v1",
        case_story: {
          title: "签收记录与实际收货情况不一致",
          one_sentence_summary:
            "物流记录显示商品已经签收，但用户本人及家人均确认没有收到商品。",
        },
        references: {
          order_reference: "ORDER-GLOBAL-1",
          after_sales_reference: "AFTER-GLOBAL-1",
          logistics_reference: "SF-GLOBAL-1",
        },
        party_positions: {
          user_claim: "用户请求核验签收链路并退款。",
          merchant_claim: "商家尚未回应。",
        },
        claim_resolution: {
          initiator_role: "USER",
          requested_resolution: "REFUND",
          requested_amount: "299.00",
          normalized_statement: "用户请求核验后退款。",
          original_statement:
            "物流显示昨天下午签收，但本人和家人都没有收到，希望平台核验后退款。",
        },
        respondent_attitude: {
          respondent_role: "MERCHANT",
          attitude: "NOT_RESPONDED",
          position: "商家尚未明确回应退款诉求。",
        },
        dispute_core_state: {
          core_conflict: "签收记录与用户未收货陈述冲突。",
          facts_in_dispute: ["用户是否实际收到商品"],
          next_verification_focus: gaps,
        },
        dispute_focus: {
          core_issue: "签收记录与实际收货情况不一致",
          facts_to_verify: gaps,
        },
        missing_information: {
          blocking_gaps: gaps,
          nice_to_have_gaps: [],
          next_questions: [],
        },
        risk_assessment: {
          case_grade: "MEDIUM",
          risk_signals: ["签收事实存在冲突"],
        },
        intake_quality: {
          score: 88,
          threshold: 80,
          ready_for_next_step: true,
          improvement_reason: "",
        },
        admission: {
          recommendation: "ACCEPTED",
          reasoning: "现有信息足以进入证据阶段。",
          confidence: 0.88,
        },
      },
    },
  };
}

function evidenceCatalog(caseId) {
  return {
    case_id: caseId,
    initiator_role: "USER",
    items: [
      {
        evidence_id: "EVIDENCE_GLOBAL_1",
        evidence_type: "DELIVERY_RECORD",
        submitted_by_role: "USER",
        visibility: "PRIVATE",
        content_url: null,
        redacted: false,
        verification_status: "VERIFIED",
        confidence_score: 0.88,
        confidence_level: "HIGH",
        verification_feedback: "来源与时间线可核对。",
        original_filename: "global-delivery-record.pdf",
        submission_status: "SUBMITTED",
        submitted_at: serverNow,
        submission_batch_id: "BATCH_GLOBAL_1",
      },
    ],
  };
}

function hearingSnapshot() {
  return {
    rounds: [
      {
        round_id: "ROUND_GLOBAL_1",
        round_no: 1,
        status: "OPEN",
        dossier_version: 1,
        round_deadline_at: futureDeadline,
        submitted_roles: [],
        current_actor_submitted: false,
        summary_json: JSON.stringify({
          judge: "请双方围绕签收记录说明事实。",
        }),
      },
    ],
    settlements: [],
    status: {
      hearing_phase: "ROUNDS_ACTIVE",
      can_complete_hearing: false,
      review_gate_ready: false,
    },
  };
}

function hearingMessages() {
  return [
    {
      id: "HEARING_MESSAGE_GLOBAL_1",
      sequence_no: 1,
      sender_role: "JUDGE",
      message_type: "AGENT_MESSAGE",
      hearing_round: 1,
      message_text: "小法庭已开启，请围绕签收争点陈述。",
      created_at: serverNow,
    },
  ];
}

function outcomeSnapshot() {
  return {
    case_id: GLOBAL_CASE_IDS.outcome,
    title: "签收未收到争议",
    case_status: "CLOSED",
    closed_at: serverNow,
    final_decision: {
      conclusion: "支持用户退款请求",
      explanation: "现有材料不足以证明用户本人或授权人签收。",
      review_reason: "审核员已确认现有证据链。",
      source: "HUMAN_REVIEW",
      human_confirmed: true,
    },
    actions: [
      {
        action_record_id: "ACTION_GLOBAL_1",
        action_type: "REFUND",
        execution_status: "SUCCEEDED",
        result: { amount: 299, currency: "CNY" },
        external_result_ref: "REFUND-GLOBAL-1",
      },
    ],
  };
}

function reviewTasks() {
  return [
    {
      id: GLOBAL_REVIEW_ID,
      case_id: GLOBAL_CASE_IDS.outcome,
      status: "PENDING",
      priority: "MEDIUM",
      required_role: "PLATFORM_REVIEWER",
      due_at: futureDeadline,
    },
  ];
}

function reviewPacket() {
  return {
    id: "PACKET_GLOBAL",
    case_id: GLOBAL_CASE_IDS.outcome,
    packet_version: 1,
    dossier_version: 1,
    ruleset_version: "rules-global",
    frozen_at: serverNow,
    expires_at: futureDeadline,
    case_summary: { title: "签收未收到争议", risk_level: "MEDIUM" },
    claims: { user: "未收到商品", merchant: "物流显示签收" },
    issues: ["签收人身份是否可信"],
    evidence_matrix: [
      { issue: "签收人身份", supporting: ["EVIDENCE_GLOBAL_1"] },
    ],
    draft: {
      conclusion: "建议退款",
      reviewer_attention: ["核实代签关系"],
    },
    remedy: {
      actions: [{ type: "REFUND", amount: 299 }],
    },
    risk_flags: ["SIGNATURE_MISMATCH"],
    status: "FROZEN",
  };
}

function roomMessages(roomType) {
  if (roomType === "INTAKE") {
    return [
      {
        id: "INTAKE_MESSAGE_GLOBAL_1",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "请补充说明签收时间与地点。",
      },
    ];
  }
  if (roomType === "EVIDENCE") {
    return [
      {
        id: "EVIDENCE_MESSAGE_GLOBAL_1",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "我会围绕接待室收敛的案情核验本批原件。",
      },
    ];
  }
  return hearingMessages();
}

function isKnownCaseId(caseId) {
  return Object.values(GLOBAL_CASE_IDS).includes(caseId);
}

export async function installGlobalWidthFixture(page, options = {}) {
  const actor = actors[options.role || "USER"];
  if (!actor) {
    throw new Error(`Unsupported global width fixture role: ${options.role}`);
  }

  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, actor);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const path = url.pathname;

    expect(request.headers()).toMatchObject({
      "x-user-id": actor.id,
      "x-role": actor.role,
    });

    if (method === "GET" && path === "/api/notifications") {
      return fulfillJson(route, []);
    }
    if (method === "GET" && path === "/api/notifications/unread-count") {
      return fulfillJson(route, { unread_count: 0 });
    }
    if (method === "GET" && path === "/api/disputes") {
      return fulfillJson(route, {
        items: [
          dispute(GLOBAL_CASE_IDS.intake, "INTAKE"),
          dispute(GLOBAL_CASE_IDS.evidence, "EVIDENCE"),
          dispute(GLOBAL_CASE_IDS.hearing, "HEARING"),
          dispute(GLOBAL_CASE_IDS.outcome, "OUTCOME", {
            case_status: "CLOSED",
          }),
        ],
      });
    }

    const exactDispute = path.match(/^\/api\/disputes\/([^/]+)$/);
    if (method === "GET" && exactDispute && isKnownCaseId(exactDispute[1])) {
      const caseId = exactDispute[1];
      const room = Object.entries(GLOBAL_CASE_IDS).find(
        ([, value]) => value === caseId,
      )?.[0]?.toUpperCase();
      return fulfillJson(route, dispute(caseId, room || "INTAKE"));
    }

    const roomMessage = path.match(
      /^\/api\/disputes\/([^/]+)\/rooms\/(INTAKE|EVIDENCE|HEARING)\/messages$/,
    );
    if (method === "GET" && roomMessage) {
      const roomType = assertGlobalRoomMessageEndpoint(
        roomMessage[1],
        roomMessage[2],
      );
      return fulfillJson(route, roomMessages(roomType));
    }

    if (
      method === "GET" &&
      path ===
        `/api/disputes/${GLOBAL_CASE_IDS.intake}/rooms/INTAKE/turn-memory/latest`
    ) {
      return fulfillJson(route, intakeTurnMemory());
    }
    if (
      method === "GET" &&
      path === `/api/disputes/${GLOBAL_CASE_IDS.evidence}/evidence`
    ) {
      return fulfillJson(route, evidenceCatalog(GLOBAL_CASE_IDS.evidence));
    }
    if (
      method === "GET" &&
      path === `/api/disputes/${GLOBAL_CASE_IDS.evidence}/evidence/completion`
    ) {
      return fulfillJson(route, {
        case_id: GLOBAL_CASE_IDS.evidence,
        user_completed: false,
        merchant_completed: false,
        sealed: false,
        next_room: null,
        deadline_at: futureDeadline,
      });
    }
    if (
      method === "GET" &&
      path === `/api/disputes/${GLOBAL_CASE_IDS.hearing}/hearing`
    ) {
      return fulfillJson(route, hearingSnapshot());
    }
    if (
      method === "GET" &&
      path === `/api/disputes/${GLOBAL_CASE_IDS.hearing}/evidence`
    ) {
      return fulfillJson(route, evidenceCatalog(GLOBAL_CASE_IDS.hearing));
    }
    if (
      method === "GET" &&
      path === `/api/disputes/${GLOBAL_CASE_IDS.hearing}/events/replay`
    ) {
      return fulfillJson(route, []);
    }
    if (
      method === "GET" &&
      path === `/api/disputes/${GLOBAL_CASE_IDS.outcome}/outcome`
    ) {
      return fulfillJson(route, outcomeSnapshot());
    }
    if (method === "GET" && path === "/api/reviews") {
      return fulfillJson(route, reviewTasks());
    }
    if (
      method === "GET" &&
      path === `/api/reviews/${GLOBAL_REVIEW_ID}/packet`
    ) {
      return fulfillJson(route, reviewPacket());
    }

    const eventStream = path.match(/^\/api\/disputes\/([^/]+)\/events$/);
    if (method === "GET" && eventStream && isKnownCaseId(eventStream[1])) {
      return route.fulfill({
        status: 200,
        headers: { "content-type": "text/event-stream" },
        body: ": deterministic-global-width-heartbeat\n\n",
      });
    }

    throw new Error(
      `Unhandled global-width API request: ${method} ${path}${url.search}`,
    );
  });
}
