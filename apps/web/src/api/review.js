import { apiRequest, newIdempotencyKey } from "./client";

export const ACTIVE_REVIEW_STATUSES = Object.freeze([
  "PENDING",
  "ASSIGNED",
  "IN_REVIEW",
]);

export const REVIEW_DECISIONS = Object.freeze([
  "APPROVE",
  "MODIFY_AND_APPROVE",
  "ESCALATE_MANUAL",
]);

function objectValue(value) {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value
    : {};
}

function firstValue(source, ...keys) {
  for (const key of keys) {
    const value = source?.[key];
    if (value !== undefined && value !== null) return value;
  }
  return undefined;
}

function normalizedStatus(value) {
  return String(value || "").trim().toUpperCase();
}

export function normalizeReviewTask(value) {
  const root = objectValue(value);
  const source = objectValue(
    root.review_task || root.reviewTask || root.task || root,
  );
  return {
    ...root,
    ...source,
    id: firstValue(source, "id", "task_id", "taskId") || "",
    case_id: firstValue(source, "case_id", "caseId") || "",
    plan_id: firstValue(source, "plan_id", "planId") || "",
    packet_id: firstValue(source, "packet_id", "packetId") || "",
    status: normalizedStatus(firstValue(source, "status", "task_status", "taskStatus")),
    priority: normalizedStatus(firstValue(source, "priority")),
    required_role:
      normalizedStatus(firstValue(source, "required_role", "requiredRole")) ||
      "PLATFORM_REVIEWER",
    assigned_reviewer_id:
      firstValue(
        source,
        "assigned_reviewer_id",
        "assignedReviewerId",
        "reviewer_id",
        "reviewerId",
      ) || "",
    due_at: firstValue(source, "due_at", "dueAt", "deadline", "expires_at", "expiresAt") || "",
    created_at: firstValue(source, "created_at", "createdAt") || "",
  };
}

// Queue state is deliberately summary-only. Frozen packet bodies and proposals
// remain available only through the reviewer-authorized packet endpoint.
export function toReviewTaskSummary(value) {
  const task = normalizeReviewTask(value);
  return {
    id: task.id,
    case_id: task.case_id,
    plan_id: task.plan_id,
    packet_id: task.packet_id,
    status: task.status,
    priority: task.priority,
    required_role: task.required_role,
    assigned_reviewer_id: task.assigned_reviewer_id,
    due_at: task.due_at,
    created_at: task.created_at,
  };
}

export function mergeActiveReviewTasks(groups) {
  const summaries = (Array.isArray(groups) ? groups : [])
    .flatMap((group) => (Array.isArray(group) ? group : []))
    .map(toReviewTaskSummary)
    .filter(
      (task) => task.id && ACTIVE_REVIEW_STATUSES.includes(task.status),
    );
  return Array.from(new Map(summaries.map((task) => [task.id, task])).values());
}

export function normalizeReviewPacket(value) {
  if (!value) return value;
  const root = objectValue(value);
  const source = objectValue(
    root.review_packet || root.reviewPacket || root.packet || root,
  );
  const task = normalizeReviewTask(
    root.review_task || root.reviewTask || root.task || {},
  );
  return {
    ...root,
    ...source,
    id: firstValue(source, "id", "packet_id", "packetId") || "",
    case_id: firstValue(source, "case_id", "caseId") || task.case_id || "",
    plan_id: firstValue(source, "plan_id", "planId") || task.plan_id || "",
    packet_version:
      firstValue(source, "packet_version", "packetVersion", "version") || 1,
    content_hash:
      firstValue(source, "content_hash", "contentHash", "packet_hash", "packetHash") || "",
    action_hash:
      firstValue(source, "action_hash", "actionHash", "approved_action_hash", "approvedActionHash") || "",
    frozen_at: firstValue(source, "frozen_at", "frozenAt") || "",
    expires_at:
      firstValue(source, "expires_at", "expiresAt") ||
      firstValue(source, "review_deadline", "reviewDeadline", "deadline") ||
      task.due_at ||
      "",
    review_deadline:
      firstValue(source, "review_deadline", "reviewDeadline", "deadline") ||
      task.due_at ||
      "",
    status: normalizedStatus(
      firstValue(source, "status", "packet_status", "packetStatus"),
    ),
    review_task_status:
      normalizedStatus(
        firstValue(root, "review_task_status", "reviewTaskStatus"),
      ) || task.status,
    assigned_reviewer_id:
      firstValue(root, "assigned_reviewer_id", "assignedReviewerId") ||
      task.assigned_reviewer_id,
  };
}

function normalizeDecisionCommand(command) {
  const source = objectValue(command);
  const decision = normalizedStatus(source.decision);
  const reason = String(source.reason || "").trim();
  if (!REVIEW_DECISIONS.includes(decision)) {
    throw new Error("不支持的审核决定");
  }
  if (!reason) throw new Error("请填写审核理由");
  return {
    decision,
    reason,
    approved_plan:
      firstValue(source, "approved_plan", "approvedPlan") || null,
    confirmed: true,
  };
}

export const reviewApi = {
  list: async (actor, status = "PENDING") => {
    const data = await apiRequest(`/reviews?status=${status}`, actor);
    const items = Array.isArray(data) ? data : data?.items || data?.tasks || [];
    return items.map(normalizeReviewTask);
  },
  packet: async (actor, taskId) =>
    normalizeReviewPacket(await apiRequest(`/reviews/${taskId}/packet`, actor)),
  start: (actor, taskId) =>
    apiRequest(`/reviews/${taskId}/start`, actor, { method: "POST" }),
  queryCopilot: (actor, taskId, question) =>
    apiRequest(`/reviews/${taskId}/copilot/query`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("review-copilot") },
      body: JSON.stringify({ question }),
    }),
  activeCopilotRuns: (actor, taskId) =>
    apiRequest(`/reviews/${taskId}/copilot/active`, actor),
  decide: (actor, taskId, command) =>
    apiRequest(`/reviews/${taskId}/decision`, actor, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey("review") },
      body: JSON.stringify(normalizeDecisionCommand(command)),
    }),
};
