import { expect } from "@playwright/test";

export const CASE_ID = "CASE_HEARING_LAYOUT";
export const INTERNAL_A2A_TEXT = "INTERNAL_A2A_ONLY_DO_NOT_RENDER";

const actors = {
  USER: { id: "user-local", role: "USER", label: "User" },
  MERCHANT: { id: "merchant-local", role: "MERCHANT", label: "Merchant" },
  PLATFORM_REVIEWER: {
    id: "reviewer-local",
    role: "PLATFORM_REVIEWER",
    label: "Reviewer",
  },
};

function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

function buildHearing() {
  return {
    rounds: [
      {
        round_id: "ROUND_1",
        round_no: 1,
        status: "COMPLETED",
        dossier_version: 1,
        stop_reason: "BOTH_PARTIES_SUBMITTED",
        summary_json: JSON.stringify({
          judge: "The first hearing round is sealed.",
        }),
      },
      {
        round_id: "ROUND_2",
        round_no: 2,
        status: "OPEN",
        dossier_version: 2,
        round_deadline_at: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
        submitted_roles: [],
        current_actor_submitted: false,
        summary_json: JSON.stringify({
          judge: "Explain how the delivery record matches the evidence.",
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

function buildMessages({ count = 4, longMessageLength = 0 } = {}) {
  const roles = ["JUDGE", "USER", "MERCHANT", "JURY"];
  const messages = Array.from({ length: count }, (_, index) => {
    const sequence = index + 1;
    const senderRole =
      longMessageLength && sequence === count
        ? "USER"
        : roles[index % roles.length];
    const longText =
      longMessageLength && sequence === count
        ? "L".repeat(longMessageLength)
        : repeatToLength(`Hearing statement ${sequence}. `, 128);
    const isJury = senderRole === "JURY";
    return {
      id: `MESSAGE_${sequence}`,
      sequence_no: sequence,
      sender_role: senderRole,
      message_type: isJury ? "JURY_REVIEW_REPORT" : "PARTY_TEXT",
      hearing_round: 2,
      message_text: isJury
        ? JSON.stringify({
            risk_level: "MEDIUM",
            confidence_score: 0.78,
            summary: longText,
          })
        : longText,
      created_at: `2026-07-11T10:${String(sequence % 60).padStart(2, "0")}:00+08:00`,
    };
  });
  messages.push({
    id: "MESSAGE_A2A_INTERNAL",
    sequence_no: count + 1,
    sender_role: "JURY",
    message_type: "A2A_AGENT_MESSAGE",
    visibility: "SYSTEM_AUDIT_ONLY",
    hearing_round: 2,
    message_text: INTERNAL_A2A_TEXT,
    created_at: "2026-07-11T11:00:00+08:00",
  });
  return messages;
}

function buildEvidenceCatalog({ count = 4 } = {}) {
  return {
    case_id: CASE_ID,
    items: Array.from({ length: count }, (_, index) => {
      const submittedByRole = index % 2 === 0 ? "USER" : "MERCHANT";
      const sequence = index + 1;
      return {
        evidence_id: `EVIDENCE_${sequence}`,
        evidence_type: index % 3 === 0 ? "IMAGE" : "DOCUMENT",
        submitted_by_role: submittedByRole,
        visibility: "PARTIES",
        content_url: `/api/disputes/${CASE_ID}/evidence/EVIDENCE_${sequence}/content`,
        redacted: false,
        verification_status: index % 2 === 0 ? "VERIFIED" : "PLAUSIBLE",
        confidence_score: 0.8,
        confidence_level: "HIGH",
        verification_feedback: "Deterministic browser-layout evidence.",
        source_type: `${submittedByRole}_UPLOAD`,
        original_filename: `${submittedByRole.toLowerCase()}-evidence-${sequence}-${"long-name-".repeat(10)}.pdf`,
        parsed_text: `Evidence summary ${sequence}`,
        submission_status: "SUBMITTED",
      };
    }),
  };
}

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

export async function installHearingCourtFixture(page, options = {}) {
  const actor = actors[options.role || "USER"];
  if (!actor) throw new Error(`Unsupported hearing fixture role: ${options.role}`);
  const hearing = buildHearing();
  const messages = buildMessages(options.messages);
  const evidenceCatalog = buildEvidenceCatalog(options.evidence);

  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, actor);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(request.headers()).toMatchObject({
      "x-user-id": actor.id,
      "x-role": actor.role,
    });

    if (request.method() === "GET" && url.pathname === "/api/notifications") {
      return fulfillJson(route, []);
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return fulfillJson(route, { unread_count: 0 });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/hearing`
    ) {
      if (options.loadError) {
        return route.fulfill({
          status: 502,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            message: options.loadError,
          }),
        });
      }
      return fulfillJson(route, hearing);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/evidence`
    ) {
      return fulfillJson(route, evidenceCatalog);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/rooms/HEARING/messages`
    ) {
      return fulfillJson(route, messages);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/events/replay`
    ) {
      return fulfillJson(route, []);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/events`
    ) {
      return route.fulfill({
        status: 200,
        headers: { "content-type": "text/event-stream" },
        body: ": deterministic-playwright-heartbeat\n\n",
      });
    }
    throw new Error(
      `Unhandled browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}
